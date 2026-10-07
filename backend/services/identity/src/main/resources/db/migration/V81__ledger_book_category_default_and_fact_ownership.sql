-- 17.6.1.1 账簿与本位币（D6，L3 锚点 17.6.1）。
--
-- 17.1.2.2 已经交付了 accounting_book：账簿挂法人、本位币是法人本位币的副本、
-- 记账规则与期间设置。本条**不重做账簿**，只补齐跨境多账簿下真正缺的三件事：
--
-- 1. **业务账 vs 法定账**（book_category）：蓝图的输入里有"类型（业务账/法定账）"，
--    它与 17.1.2.2 已有的 book_type（总账/现金账簿/银行账簿）是**两条正交的轴**——
--    一本法定账同样可以是银行账簿，把两者塞进同一个枚举就再也表达不了"这是报税用的法定账"。
--
-- 2. **一本法人多本账，且各账本位币可以不同**（base_currency_mode）。
--    17.1.2.2 的纪律是"本位币归法人，账上只是落地副本"，跨境场景下这条纪律不够用：
--    同一个主体可能同时用人民币和美元记账（多币种平台结算 + 国内采购）。
--    所以区分两种来源：INHERIT 继承法人本位币（与 17.1.2.2 完全一致）、
--    OWN 由账簿自己决定本位币（海外账 USD）。INHERIT 是 DEFAULT，
--    既有行与既有 INSERT 都不会因为这次 ADD COLUMN 而违约。
--
-- 3. **跨账簿不混记 + 默认账簿**：见下面两张表/索引的注释。
--
-- 跨车道纪律：本迁移只做 ADD COLUMN ... DEFAULT / 新增表 / 换索引谓词，
-- **没有任何裸 ADD COLUMN NOT NULL 无 DEFAULT，也没有对既有表的收紧约束**，
-- 因此并行的 warehouse/trade 车道即使已经提交了不写这些列的 INSERT 也不会炸。

-- ------------------------------------------------------------------
-- 1. 账簿分类与本位币来源
-- ------------------------------------------------------------------

-- 业务账/法定账：业务流水走业务账，报税与法定报表走法定账，两者不混记。
ALTER TABLE accounting_book
  ADD COLUMN book_category text NOT NULL DEFAULT 'BUSINESS'
  CHECK(book_category IN ('BUSINESS','STATUTORY'));

-- 本位币来源。INHERIT 时 base_currency 必须等于 legal_entity.base_currency
-- （17.1.2.2 的纪律原样保留）；OWN 时账簿自己决定本位币，可以与法人不同。
ALTER TABLE accounting_book
  ADD COLUMN base_currency_mode text NOT NULL DEFAULT 'INHERIT'
  CHECK(base_currency_mode IN ('INHERIT','OWN'));

-- 科目体系来源：账簿决定科目体系与报表口径（蓝图"输出"里的报表口径）。
-- 只定义来源档位，不在 identity 里建科目——科目与期间是 12.5 的职责。
ALTER TABLE accounting_book
  ADD COLUMN chart_of_accounts_source text NOT NULL DEFAULT 'STANDARD'
  CHECK(chart_of_accounts_source IN ('STANDARD','LOCAL','GROUP'));

-- 默认账簿：一个法人一本，没有默认时的行为由应用层显式报错，不静默挑一本。
ALTER TABLE accounting_book
  ADD COLUMN is_default boolean NOT NULL DEFAULT false;

-- 唯一总账的口径从"一个法人一本"放宽到"一个法人**一个本位币**一本总账"：
-- 业务账 CNY 与海外账 USD 可以并存，但同一本位币下仍然只允许一本总账。
-- 这是 17.6.1.1 蓝图里"业务账 CNY 与海外账 USD 并存"的直接前提，
-- 而既有场景（INHERIT 的单一本位币）行为完全不变。
DROP INDEX IF EXISTS accounting_book_main;
CREATE UNIQUE INDEX accounting_book_main ON accounting_book(tenant_id, legal_entity_id, base_currency)
  WHERE book_type='BOOK';

-- 一个法人至多一本默认账簿。仍然不设"至少一本"的约束：
-- 刚建法人还没建账就是零本，硬约束会逼出一个假账簿才能过迁移。
-- 缺默认时的行为由 GET 默认账簿端点显式 409，不靠数据库猜。
CREATE UNIQUE INDEX accounting_book_default ON accounting_book(tenant_id, legal_entity_id)
  WHERE is_default;

-- 停用的账簿不能当默认账簿：默认账簿是单据落账的落点，
-- 落点必须是可用账簿。
CREATE FUNCTION accounting_book_default_active() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.is_default AND NEW.status <> 'ACTIVE' THEN
    RAISE EXCEPTION '默认账簿必须处于启用状态，请先启用该账簿或改选其他账簿作为默认账簿'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER accounting_book_default_guard
  BEFORE INSERT OR UPDATE OF is_default,status ON accounting_book
  FOR EACH ROW EXECUTE FUNCTION accounting_book_default_active();

CREATE INDEX accounting_book_category ON accounting_book(tenant_id, legal_entity_id, book_category);

-- ------------------------------------------------------------------
-- 2. 跨账簿不混记：业务事实归属账簿登记
-- ------------------------------------------------------------------
--
-- 蓝图加粗的那句"同一笔业务事实只在归属账簿记一次"是硬约束，不是文档里写一句就算。
-- 这里把它落成**可被数据库拒绝的事实**：
--
--   UNIQUE(tenant_id, fact_source, fact_ref)
--
-- 同一个业务事实（来源域 + 事实 ID）在**整个租户**只能有一行归属，
-- 因此第二本账簿无法再登记同一笔事实——写入方会拿到唯一约束冲突，
-- 应用层把它翻译成"该业务事实已归属账簿 X，跨账簿不混记"。
-- 这与 V5 的 legal_entity_reference 是同一套约定：下游卷 12 的单据与凭证
-- 通过授权 API 登记归属事实，而不是各写各的表。
--
-- fact_source 刻意**不**建闭集枚举：订单/采购单/供应商账单这些来源域属于下游卷 12，
-- identity 只校验格式并登记归属，不替 finance 定义它的业务词汇表。

CREATE TABLE book_fact_assignment (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  book_id       uuid NOT NULL,
  -- 业务事实来源域，由下游卷 12 约定（如订单、采购单、供应商账单）
  fact_source   text NOT NULL,
  -- 业务事实标识
  fact_ref      uuid NOT NULL,
  -- 业务事实自身的币种与金额口径留痕：归属登记要能回答"这笔事实按哪本账的哪个币记"，
  -- 不然后面汇算时无法判断该用哪本账的汇率。金额一律 NUMERIC，禁浮点。
  amount        numeric(19,6),
  currency      varchar(3),
  booked_at     timestamptz NOT NULL DEFAULT now(),
  created_at    timestamptz NOT NULL DEFAULT now(),
  -- 账簿只能引用同租户的账簿，且账簿被下游引用时不允许被静默删掉
  FOREIGN KEY (tenant_id, book_id)
    REFERENCES accounting_book(tenant_id, id) ON DELETE RESTRICT,
  -- 跨账簿不混记的落点
  UNIQUE(tenant_id, fact_source, fact_ref),
  CHECK(btrim(fact_source) <> '' AND btrim(fact_source) = upper(btrim(fact_source))),
  CHECK(amount IS NULL OR amount >= 0),
  CHECK(currency IS NULL OR length(btrim(currency)) = 3)
);

CREATE INDEX book_fact_assignment_book ON book_fact_assignment(tenant_id, book_id, fact_source);
CREATE INDEX book_fact_assignment_fact ON book_fact_assignment(tenant_id, fact_source, fact_ref);

DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['book_fact_assignment'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 默认账簿与归属登记沿用 17.1.2.2 已有的 identity.accounting_book 权限码与 OWNER 组织变更纪律，
-- 不另造一套授权身份：账簿与账簿归属本来就是同一份配置的组成部分。

GRANT SELECT,INSERT,UPDATE,DELETE ON book_fact_assignment TO identity_app;