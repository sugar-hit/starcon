-- 17.1.2.2 本位币与账簿。
--
-- 账簿挂在**法人**上（legal_entity_id），不挂租户：一个租户可以有多个法人，
-- 每个法人都有自己的记账基准，"店铺收入记到哪本账"取决于店铺绑的是哪个法人。
--
-- 本位币归法人，租户默认币种只是展示默认，两者不能混（实体目录 §5）。
-- 所以 accounting_book.base_currency 是**法人的本位币在这本账上的落地副本**：
-- 它必须等于所属 legal_entity.base_currency，由应用层校验（可读报错），
-- 而 legal_entity 才是唯一真相源。base_currency_changed_at 记录本账最近一次
-- 切换本位币的时刻 —— 中途换币会让历史凭证的换算基准前后不一致，
-- 必须能回答"从哪一刻起这本账按哪个币记"，下游 12 财务结算要按它分段。
--
-- 期间设置（period_type / fiscal_year_start_month / first_period_start）在这里
-- 只定义**规则**，不生成期间行：12.5 会计科目与期间才是按这套规则生成并封账的地方，
-- identity 不越界替 finance 建账。

CREATE TABLE accounting_book (
  id                      uuid PRIMARY KEY,
  tenant_id               uuid NOT NULL REFERENCES tenant(id),
  legal_entity_id         uuid NOT NULL,
  code                    varchar(64) NOT NULL,
  name                    varchar(200) NOT NULL,
  -- BOOK 总账（法人唯一，记全套科目）/ CASH 现金账簿 / BANK 银行账簿。
  -- 一个法人可以有多本银行账（不同银行账户），但只能有一本总账。
  book_type               text NOT NULL DEFAULT 'BOOK'
                          CHECK(book_type IN ('BOOK','CASH','BANK')),
  base_currency           varchar(3) NOT NULL,
  -- 记账规则：金额与数量是两套口径。金额按本位币最小单位舍入，数量按计量单位舍入，
  -- 所以各带自己的 scale 与 rounding —— 用一把尺子量两种量纲是 12 财务试算不平的常见原因。
  amount_scale            int NOT NULL DEFAULT 2 CHECK(amount_scale BETWEEN 0 AND 6),
  amount_rounding         text NOT NULL DEFAULT 'HALF_UP'
                          CHECK(amount_rounding IN ('HALF_UP','HALF_EVEN','DOWN','UP')),
  quantity_scale          int NOT NULL DEFAULT 3 CHECK(quantity_scale BETWEEN 0 AND 6),
  quantity_rounding       text NOT NULL DEFAULT 'HALF_UP'
                          CHECK(quantity_rounding IN ('HALF_UP','HALF_EVEN','DOWN','UP')),
  -- 期间设置：期间粒度 + 会计年度起始月 + 首期起点。
  period_type             text NOT NULL DEFAULT 'MONTH'
                          CHECK(period_type IN ('MONTH','WEEK','QUARTER')),
  fiscal_year_start_month int NOT NULL DEFAULT 1
                          CHECK(fiscal_year_start_month BETWEEN 1 AND 12),
  first_period_start      timestamptz NOT NULL,
  -- 本位币切换时刻，建账时与 first_period_start 同点
  base_currency_changed_at timestamptz NOT NULL DEFAULT now(),
  status                  text NOT NULL DEFAULT 'ACTIVE'
                          CHECK(status IN ('ACTIVE','SUSPENDED')),
  version                 bigint NOT NULL DEFAULT 0,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  -- legal_entity 上有 UNIQUE(tenant_id,id)，复合外键才能同时钉住租户归属与法人存在性，
  -- 不会出现"账簿指向别的租户的法人"。
  FOREIGN KEY (tenant_id, legal_entity_id)
    REFERENCES legal_entity(tenant_id, id) ON DELETE RESTRICT,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, legal_entity_id, code),
  CHECK(btrim(code)<>'' AND btrim(name)<>'' AND length(btrim(base_currency))=3)
);

-- 首期对齐校验用触发器而不是 CHECK：约束条件依赖 period_type 决定模数
-- （MONTH 看 12、QUARTER 看 3、WEEK 不看），写成 CHECK 需要在每行重复这段分支。
-- MONTH → 首期月份 ≡ 起始月 (mod 12)；QUARTER → ≡ 起始月 (mod 3)；WEEK → 不检查。
CREATE FUNCTION accounting_book_period_aligned() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m int; step int;
BEGIN
  IF NEW.period_type='WEEK' THEN RETURN NEW; END IF;
  step := CASE WHEN NEW.period_type='QUARTER' THEN 3 ELSE 12 END;
  -- timestamptz 显式按 UTC 取月：会话时区（Asia/Shanghai）会把月初那天挪走，
  -- 造成同一份数据在不同时区跑出两种对齐结论。
  m := extract(month from NEW.first_period_start at time zone 'UTC')::int;
  IF mod(m - NEW.fiscal_year_start_month + step*2, step) <> 0 THEN
    RAISE EXCEPTION '首期起点月份与会计年度起始月/期间粒度不对齐：期间类型=%、起始月=%、首期月份=%',
      NEW.period_type, NEW.fiscal_year_start_month, m USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER accounting_book_period_check
  BEFORE INSERT OR UPDATE OF period_type,fiscal_year_start_month,first_period_start
  ON accounting_book FOR EACH ROW EXECUTE FUNCTION accounting_book_period_aligned();

-- 一本总账：法人级唯一约束用部分索引表达，现金账与银行账不受限
-- （同一法人可以按银行账户开多本银行账）。
CREATE UNIQUE INDEX accounting_book_main ON accounting_book(tenant_id, legal_entity_id)
  WHERE book_type='BOOK';
CREATE INDEX accounting_book_list ON accounting_book(tenant_id, legal_entity_id, book_type, code);
CREATE INDEX accounting_book_currency ON accounting_book(tenant_id, base_currency);

DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['accounting_book'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 17.1.2.2 会计账簿：与法人档案一样按页面 CRUD 授权，组织变更沿用"仅 OWNER"纪律。
INSERT INTO page_resource(code,name) VALUES ('identity.accounting_book','会计账簿')
ON CONFLICT (code) DO NOTHING;
-- unnest(ARRAY[...]) 的元素类型由字面量推断，显式给 text[]，否则 PG 会把单元素
-- 数组推成 text 再与 varchar 比较，报 "text = integer"
INSERT INTO permission SELECT 'identity.accounting_book.'||action,'identity.accounting_book',action
FROM unnest(ARRAY['read','create','update','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.accounting_book.%' ON CONFLICT DO NOTHING;

GRANT SELECT,INSERT,UPDATE,DELETE ON accounting_book TO identity_app;