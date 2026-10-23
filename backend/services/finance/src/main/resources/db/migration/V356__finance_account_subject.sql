-- ===========================================================================
-- V356 · finance_account_subject —— 12.5.1.1 会计科目
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.5.1.1 会计科目）原文：
--
--   职责：维护法人账簿的会计科目表：科目编码、名称、类别（资产/负债/权益/成本/损益）、
--         余额方向、是否明细科目、关联税务属性；支持科目映射到业务事件。
--   输入：科目定义；业务事件映射（如发货 → 收入 + 结转成本）。
--   输出：科目表 + 映射规则。
--   关系：上游 = 用户会计体系；下游 = 12.5.2.1 凭证、12.5.2.2 报表。
--         **科目体系是用户自定的**，系统提供模板但必须允许完全自定义。
--
-- ---------------------------------------------------------------------------
-- 0. 与同批 12.5.2.1「凭证管理」（V357）的分工：**本表是它的上游**
-- ---------------------------------------------------------------------------
--
--   * 本模块**拥有**科目表：科目编码、名称、类别、余额方向、是否明细、税务属性、
--     科目树、以及「业务事件 → 科目」的映射规则。
--   * 12.5.2.1（凭证）**不得重复建科目表** —— 它按**科目编码软引用**本表：
--       finance_account_subject.subject_code
--     软引用成立的三个前提在本迁移里被钉死：
--       ① 科目编码在**租户内唯一**（UNIQUE(tenant_id, subject_code)）
--          ⇒ 凭证行只带 subject_code 就能唯一定位到科目，不必再带账簿维度；
--       ② 科目编码**一经入账不可修改**（BEFORE UPDATE 触发器钉死）
--          ⇒ 已过账凭证里的编码不会指向另一个科目；
--       ③ 编码不可修改、科目可停用（status='DISABLED'）
--          ⇒ 历史凭证引用的科目不会消失。
--   * 凭证行要预填「类别 / 余额方向 / 是否明细」时，走本模块的
--     `GET /finance/account-subjects/by-code?subjectCode=xxx`（唯一命中）或
--     `GET /finance/account-subjects/{id}`。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_account_subject_template            —— 系统科目模板（**系统参考数据**，见第 6 节）
--   finance_account_subject                      —— 科目表（租户业务表）
--   finance_account_subject_event_mapping        —— 业务事件 → 科目的映射规则（头）
--   finance_account_subject_event_mapping_line   —— 映射规则的分录行（借贷两行成一对）
--   finance_account_subject_event                —— 变更留痕（append-only）
--
--   后四张全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 作用域：**法人账簿**
-- ---------------------------------------------------------------------------
--
--   蓝图说「维护**法人账簿**的会计科目表」⇒ 科目必须落到具体的一本账上。
--   账簿与法人主数据在 **identity 服务**（17.1.2.2 accounting_book / 17.1.2.1 legal_entity），
--   跨服务**不建 FK、不建视图、不直连对方库**（AGENTS.md：禁止跨服务 SQL / FK / 共享视图），
--   这里只存逻辑 ID + 编码快照。
--
--   ⚠️ identity 的 V43 注释原文：「期间设置……只定义**规则**，不生成期间行：
--     12.5 会计科目与期间才是按这套规则生成并封账的地方，identity 不越界替 finance 建账。」
--   ⇒ 本模块只管**科目**（12.5.1.1），**不生成会计期间行**（那是 12.5.1.2，未开发）。
--
-- ---------------------------------------------------------------------------
-- 3. 科目编码：唯一 + 稳定 + 不可改
-- ---------------------------------------------------------------------------
--
--   * UNIQUE(tenant_id, subject_code)：**租户内唯一**（下游凭证软引用的前提）。
--   * 触发器钉死「**编码一经入账不可改**」：科目名 / 类别 / 余额方向 / 税务属性都能改，
--     编码不能 —— 改了会让已过账凭证里的编码指向另一个科目。
--   * 编码格式：允许中文会计常用的层级编码（`1122.01`）⇒ 正则允许点号。
--
-- ---------------------------------------------------------------------------
-- 4. 科目树与「借贷平衡」的守卫（都钉在 DB 层，服务层与直连 SQL 都绕不过）
-- ---------------------------------------------------------------------------
--
--   G1 编码不可改（BEFORE UPDATE）
--   G2 科目不可跨租户 / 跨账簿移动
--   G3 下级科目类别必须与上级一致（资产负债权益不能互挂）
--   G4 科目层级不得成环（沿上级链回溯，深度上限 50）
--   G5 level 由触发器派生（服务层写不进去）
--   G6 ACTIVE 科目不可物理删除（先停用）
--   G7 映射行只能指向**明细科目**且该科目必须 ACTIVE
--   G8 映射行引用的科目必须与规则属于**同一本账簿**
--   G9 映射规则启用前必须**借贷齐备**（至少一条借方 + 一条贷方）
--      —— 模板不固定金额，所以「平衡」按**借贷双向齐备**判定，
--         金额相等由下游 12.5.2.1 在生成凭证时按业务事件实算。
--
-- ---------------------------------------------------------------------------
-- 5. 金额与时间
-- ---------------------------------------------------------------------------
--
--   本模块**不含金额**：科目是配置，凭证才是金额的载体（12.5.2.1）。
--   唯一的数值列是税率 tax_rate numeric(9,6)（0.13 = 13%），**不是** double。
--   时间列一律 timestamptz，服务层用 OffsetDateTime + `?::timestamptz` 绑定。
--
-- ===========================================================================

-- ===========================================================================
-- 第一节：系统科目模板（**系统参考数据**，不是租户业务表）
-- ===========================================================================
--
-- ⚠️ 这张表**没有 tenant_id、也不启用 RLS** —— 它是**系统提供**的模板，
--   所有租户看到的是同一份模板（与 identity 的 permission / page_resource 同性质：
--   系统参考数据）。运行时角色对它只有 SELECT 权，写权限一律不授予 ——
--   模板只能由迁移播种，不能被运行期改写。
--
--   「科目体系是用户自定的，系统提供模板但必须允许完全自定义」这句加粗句的落地方式：
--   模板**只是导入源**（POST /finance/account-subjects/templates/apply 把它复制成租户自己的科目），
--   复制之后用户可以随便改名 / 改类别 / 改余额方向 / 停用 / 增删 ——
--   **系统不提供任何写死的科目枚举**，服务层也没有「标准科目必须存在」这类校验。
CREATE TABLE finance_account_subject_template (
  template_key        varchar(64)  NOT NULL,
  subject_code        varchar(64)  NOT NULL CHECK(subject_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
  subject_name        varchar(200) NOT NULL CHECK(btrim(subject_name) <> ''),
  category            text         NOT NULL
                      CHECK(category IN ('ASSET','LIABILITY','EQUITY','COST','PROFIT_LOSS')),
  balance_direction   text         NOT NULL CHECK(balance_direction IN ('DEBIT','CREDIT')),
  is_detail           boolean      NOT NULL DEFAULT true,
  parent_subject_code varchar(64),
  tax_treatment       varchar(24)  NOT NULL DEFAULT 'TAXABLE'
                      CHECK(tax_treatment IN ('TAXABLE','EXEMPT','ZERO_RATED','NON_TAXABLE','REVERSE_CHARGE')),
  tax_rate            numeric(9,6) CHECK(tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate <= 1)),
  tax_note            varchar(200) NOT NULL DEFAULT '',
  sort_order          int          NOT NULL DEFAULT 0,
  PRIMARY KEY (template_key, subject_code),
  CHECK(parent_subject_code IS NULL OR parent_subject_code <> subject_code)
);

CREATE INDEX finance_account_subject_template_list
  ON finance_account_subject_template(template_key, sort_order, subject_code);

-- ===========================================================================
-- 第二节：科目表（租户业务表）
-- ===========================================================================
CREATE TABLE finance_account_subject (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  -- 法人账簿（identity 17.1.2.2 accounting_book.id；**跨服务不建 FK**）。
  accounting_book_id  uuid NOT NULL,
  -- 法人（identity 17.1.2.1 legal_entity.id；**跨服务不建 FK**）。
  legal_entity_id     uuid NOT NULL,
  -- 科目编码：**租户内唯一**（下游 12.5.2.1 凭证按它软引用）。
  subject_code        varchar(64) NOT NULL CHECK(subject_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
  subject_name        varchar(200) NOT NULL CHECK(btrim(subject_name) <> ''),
  -- 类别：资产 / 负债 / 权益 / 成本 / 损益。
  category            text NOT NULL
                      CHECK(category IN ('ASSET','LIABILITY','EQUITY','COST','PROFIT_LOSS')),
  -- 余额方向：借方 / 贷方。
  -- ⚠️ **不与 category 联动**：累计折旧是资产类却记贷方余额，强行联动会堵死真实账。
  balance_direction   text NOT NULL CHECK(balance_direction IN ('DEBIT','CREDIT')),
  -- 是否明细科目：false = 汇总科目（只能有子科目，不能被凭证直接记账）。
  is_detail           boolean NOT NULL DEFAULT true,
  -- 上级科目（软引用自身的 subject_code；同库同服务 ⇒ 建复合 FK，
  -- 既保证「上级真实存在」，又保证**不会挂到别的租户的科目上**）。
  parent_subject_code varchar(64),
  -- 层级：**由触发器派生**（服务层写不进去）。
  level               int NOT NULL DEFAULT 1 CHECK(level >= 1),
  -- 关联税务属性。
  tax_treatment       varchar(24) NOT NULL DEFAULT 'TAXABLE'
                      CHECK(tax_treatment IN ('TAXABLE','EXEMPT','ZERO_RATED','NON_TAXABLE','REVERSE_CHARGE')),
  tax_rate            numeric(9,6) CHECK(tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate <= 1)),
  tax_note            varchar(200) NOT NULL DEFAULT '',
  -- 状态：ACTIVE 在用 / DISABLED 停用（**停用不是删除**：历史凭证仍要能引用到它）。
  status              text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  -- 来源：CUSTOM 用户自定义 / TEMPLATE 从系统模板导入。
  source              text NOT NULL DEFAULT 'CUSTOM' CHECK(source IN ('CUSTOM','TEMPLATE')),
  template_key        varchar(64),
  version             bigint NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 科目编码的**租户内唯一**：下游凭证软引用它的前提。
  UNIQUE(tenant_id, subject_code),
  FOREIGN KEY (tenant_id, parent_subject_code)
    REFERENCES finance_account_subject(tenant_id, subject_code) ON DELETE RESTRICT,
  CHECK(parent_subject_code IS NULL OR parent_subject_code <> subject_code)
);

CREATE INDEX finance_account_subject_list
  ON finance_account_subject(tenant_id, accounting_book_id, category, subject_code);
CREATE INDEX finance_account_subject_status
  ON finance_account_subject(tenant_id, status);
CREATE INDEX finance_account_subject_parent
  ON finance_account_subject(tenant_id, parent_subject_code);

-- ===========================================================================
-- 第三节：业务事件 → 科目的映射规则（头 + 分录行）
-- ===========================================================================
--
-- 蓝图输入：「业务事件映射（如**发货 → 收入 + 结转成本**）」。
-- 一个事件对应**一条规则**，规则下有**若干分录行**（收入一行、结转成本一行）。
--
-- ⚠️ event_type **不是枚举**：它是用户自定义的业务事件编码
--   （大写字母数字下划线），系统只校验格式。写死成枚举就等于把用户的
--   业务事件体系写死 —— 那与「科目体系是用户自定的」同一种错误。
CREATE TABLE finance_account_subject_event_mapping (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  accounting_book_id uuid NOT NULL,
  -- 业务事件编码（用户自定义；格式受约束，取值不受约束）。
  event_type         varchar(64) NOT NULL CHECK(event_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  -- 事件的人话名称（给界面看）。
  event_label        varchar(200) NOT NULL DEFAULT '',
  -- DRAFT 草稿（可随便改）/ ACTIVE 启用（借贷齐备才让启用）/ INACTIVE 停用。
  status             text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','INACTIVE')),
  -- 生效起始时刻（timestamptz，可空 = 立即生效）。
  effective_from     timestamptz,
  note               varchar(500) NOT NULL DEFAULT '',
  version            bigint NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一本账里，一个业务事件只能有一条规则。
  UNIQUE(tenant_id, accounting_book_id, event_type)
);

CREATE INDEX finance_account_subject_event_mapping_list
  ON finance_account_subject_event_mapping(tenant_id, accounting_book_id, status, event_type);

-- 映射分录行：一行 = 事件发生后要记的一个科目方向。
CREATE TABLE finance_account_subject_event_mapping_line (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  mapping_id   uuid NOT NULL,
  -- 科目编码（同库同服务 ⇒ 复合 FK 钉住「科目真实存在且属于同一租户」）。
  subject_code varchar(64) NOT NULL,
  -- 借贷方向。
  direction    text NOT NULL CHECK(direction IN ('DEBIT','CREDIT')),
  -- 金额口径：这个分录行取业务事件的哪个量（金额相等由下游按此口径实算）。
  amount_basis text NOT NULL
               CHECK(amount_basis IN ('ORDER_ITEM_AMOUNT','ITEM_COST','SHIPPING_AMOUNT','TAX_AMOUNT',
                                      'PLATFORM_FEE','REFUND_AMOUNT','ADJUSTMENT_AMOUNT','OTHER')),
  sequence     int NOT NULL CHECK(sequence > 0),
  note         varchar(500) NOT NULL DEFAULT '',
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一规则内的行序唯一。
  UNIQUE(tenant_id, mapping_id, sequence),
  FOREIGN KEY (tenant_id, mapping_id)
    REFERENCES finance_account_subject_event_mapping(tenant_id, id) ON DELETE CASCADE,
  -- 🔴 科目被引用 ⇒ 不可物理删除（RESTRICT）：映射指向的科目不能凭空消失。
  FOREIGN KEY (tenant_id, subject_code)
    REFERENCES finance_account_subject(tenant_id, subject_code) ON DELETE RESTRICT
);

CREATE INDEX finance_account_subject_event_mapping_line_subject
  ON finance_account_subject_event_mapping_line(tenant_id, subject_code);
CREATE INDEX finance_account_subject_event_mapping_line_mapping
  ON finance_account_subject_event_mapping_line(tenant_id, mapping_id, sequence);

-- ===========================================================================
-- 第四节：变更留痕（append-only）
-- ===========================================================================
--
-- 科目是**会被引用**的配置：改了它，已经按老科目编好的凭证解释就变了。
-- 所以「改了什么」必须有独立出处 —— 事件流**只能追加**
-- （运行角色的 UPDATE / DELETE 已收回，见第六节）。
CREATE TABLE finance_account_subject_event (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  -- 留痕对象：SUBJECT 科目 / MAPPING 映射规则。
  target_type text NOT NULL CHECK(target_type IN ('SUBJECT','MAPPING')),
  target_id  uuid NOT NULL,
  event_type varchar(48) NOT NULL,
  -- 变更的字段与前后值（科目改名、改类别、改余额方向…）。
  field_name varchar(64) NOT NULL DEFAULT '',
  from_value varchar(500) NOT NULL DEFAULT '',
  to_value   varchar(500) NOT NULL DEFAULT '',
  reason     varchar(500) NOT NULL DEFAULT '',
  actor_id   uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id)
);

CREATE INDEX finance_account_subject_event_target
  ON finance_account_subject_event(tenant_id, target_type, target_id, occurred_at desc, id desc);

-- ===========================================================================
-- 第五节：DB 层守卫（服务层与直连 SQL 都绕不过）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- G1~G5：科目守卫（编码不可改 / 不可跨租户跨账簿移动 / 类别一致 / 不成环 / level 派生）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_finance_account_subject_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_parent finance_account_subject%ROWTYPE;
  v_cur    finance_account_subject%ROWTYPE;
  v_depth  int := 0;
  v_count  bigint;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    -- G1：科目编码一经入账不可修改 —— 下游凭证按编码软引用它。
    IF NEW.subject_code IS DISTINCT FROM OLD.subject_code THEN
      RAISE EXCEPTION '科目编码一经入账不可修改（下游凭证按科目编码软引用）：% → %',
        OLD.subject_code, NEW.subject_code USING ERRCODE = 'check_violation';
    END IF;
    -- G2：科目不可跨租户 / 跨账簿搬家。
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
      RAISE EXCEPTION '科目不可跨租户移动' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.accounting_book_id IS DISTINCT FROM OLD.accounting_book_id THEN
      RAISE EXCEPTION '科目不可跨账簿移动（下游按账簿取科目）' USING ERRCODE = 'check_violation';
    END IF;
    -- G3 的另一半：改本级类别时，下级科目的类别也要跟着改，否则树上会出现
    -- 「资产下面挂着负债」这种荒唐结构。
    IF NEW.category IS DISTINCT FROM OLD.category THEN
      SELECT count(*) INTO v_count
        FROM finance_account_subject
       WHERE tenant_id = NEW.tenant_id
         AND parent_subject_code = NEW.subject_code
         AND category IS DISTINCT FROM NEW.category;
      IF v_count > 0 THEN
        RAISE EXCEPTION '科目 % 下还有 % 个下级科目的类别与新类别不同，请先改下级科目再改本级',
          NEW.subject_code, v_count USING ERRCODE = 'check_violation';
      END IF;
    END IF;
    -- 停用前必须先解掉引用：已启用的映射规则正在用这个科目。
    IF NEW.status = 'DISABLED' AND OLD.status IS DISTINCT FROM 'DISABLED' THEN
      SELECT count(*) INTO v_count
        FROM finance_account_subject_event_mapping_line l
        JOIN finance_account_subject_event_mapping m
          ON m.tenant_id = l.tenant_id AND m.id = l.mapping_id
       WHERE l.tenant_id = NEW.tenant_id
         AND l.subject_code = NEW.subject_code
         AND m.status = 'ACTIVE';
      IF v_count > 0 THEN
        RAISE EXCEPTION '科目 % 正被 % 条已启用的映射规则引用，请先停用这些规则再停用科目',
          NEW.subject_code, v_count USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;

  IF NEW.parent_subject_code IS NOT NULL THEN
    IF NEW.parent_subject_code = NEW.subject_code THEN
      RAISE EXCEPTION '科目不能把自己设为上级科目：%', NEW.subject_code
        USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO v_parent FROM finance_account_subject
     WHERE tenant_id = NEW.tenant_id AND subject_code = NEW.parent_subject_code;
    IF NOT FOUND THEN
      RAISE EXCEPTION '上级科目不存在：%', NEW.parent_subject_code
        USING ERRCODE = 'foreign_key_violation';
    END IF;
    -- G3：下级科目类别必须与上级一致。
    IF v_parent.category <> NEW.category THEN
      RAISE EXCEPTION '下级科目类别必须与上级一致（上级 % 的类别是 %，本科目是 %）',
        v_parent.subject_code, v_parent.category, NEW.category USING ERRCODE = 'check_violation';
    END IF;
    -- G4：沿上级链回溯，不得成环。
    v_depth := 0;
    v_cur := v_parent;
    WHILE v_cur.parent_subject_code IS NOT NULL LOOP
      v_depth := v_depth + 1;
      IF v_depth > 50 THEN
        RAISE EXCEPTION '科目层级超过 50 层，数据疑似成环' USING ERRCODE = 'check_violation';
      END IF;
      IF v_cur.parent_subject_code = NEW.subject_code THEN
        RAISE EXCEPTION '科目层级成环：% 的上级链回到了自己', NEW.subject_code
          USING ERRCODE = 'check_violation';
      END IF;
      SELECT * INTO v_cur FROM finance_account_subject
       WHERE tenant_id = NEW.tenant_id AND subject_code = v_cur.parent_subject_code;
      IF NOT FOUND THEN
        EXIT;
      END IF;
    END LOOP;
    -- G5：level 由上级派生，服务层写不进去。
    NEW.level := v_parent.level + 1;
  ELSE
    NEW.level := 1;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER finance_account_subject_guard
  BEFORE INSERT OR UPDATE ON finance_account_subject
  FOR EACH ROW EXECUTE FUNCTION trg_finance_account_subject_guard();

-- ---------------------------------------------------------------------------
-- G6：ACTIVE 科目不可物理删除（先停用）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_finance_account_subject_delete_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'ACTIVE' THEN
    RAISE EXCEPTION '科目 % 仍在使用中，请先停用再删除', OLD.subject_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER finance_account_subject_delete_guard
  BEFORE DELETE ON finance_account_subject
  FOR EACH ROW EXECUTE FUNCTION trg_finance_account_subject_delete_guard();

-- ---------------------------------------------------------------------------
-- G7 + G8：映射行守卫（只能指向 ACTIVE 明细科目 / 必须同一本账簿）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_finance_account_subject_mapping_line_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_subject finance_account_subject%ROWTYPE;
  v_mapping finance_account_subject_event_mapping%ROWTYPE;
BEGIN
  IF TG_OP IN ('INSERT','UPDATE') THEN
    SELECT * INTO v_subject FROM finance_account_subject
     WHERE tenant_id = NEW.tenant_id AND subject_code = NEW.subject_code;
    IF NOT FOUND THEN
      RAISE EXCEPTION '映射行引用的科目不存在：%', NEW.subject_code
        USING ERRCODE = 'foreign_key_violation';
    END IF;
    -- G7：凭证只能记明细科目 ⇒ 映射行也只准指向明细科目。
    IF NOT v_subject.is_detail THEN
      RAISE EXCEPTION '映射行只能指向明细科目，% 是汇总科目', NEW.subject_code
        USING ERRCODE = 'check_violation';
    END IF;
    IF v_subject.status <> 'ACTIVE' THEN
      RAISE EXCEPTION '映射行不能指向已停用的科目：%', NEW.subject_code
        USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO v_mapping FROM finance_account_subject_event_mapping
     WHERE tenant_id = NEW.tenant_id AND id = NEW.mapping_id;
    -- G8：规则与科目必须属于同一本账簿。
    IF FOUND AND v_mapping.accounting_book_id <> v_subject.accounting_book_id THEN
      RAISE EXCEPTION '映射规则与科目必须属于同一本账簿（规则 % / 科目 %）',
        v_mapping.event_type, NEW.subject_code USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER finance_account_subject_mapping_line_guard
  BEFORE INSERT OR UPDATE OR DELETE ON finance_account_subject_event_mapping_line
  FOR EACH ROW EXECUTE FUNCTION trg_finance_account_subject_mapping_line_guard();

-- ---------------------------------------------------------------------------
-- G9a：映射规则启用前必须借贷齐备
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_finance_account_subject_mapping_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_debit  int;
  v_credit int;
BEGIN
  IF NEW.status = 'ACTIVE' THEN
    SELECT count(*) FILTER (WHERE direction = 'DEBIT'),
           count(*) FILTER (WHERE direction = 'CREDIT')
      INTO v_debit, v_credit
      FROM finance_account_subject_event_mapping_line
     WHERE tenant_id = NEW.tenant_id AND mapping_id = NEW.id;
    IF v_debit = 0 OR v_credit = 0 THEN
      RAISE EXCEPTION '映射规则启用前必须借贷齐备：当前借方 % 条 / 贷方 % 条（模板不固定金额，金额相等由凭证侧实算）',
        v_debit, v_credit USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER finance_account_subject_mapping_guard
  BEFORE INSERT OR UPDATE ON finance_account_subject_event_mapping
  FOR EACH ROW EXECUTE FUNCTION trg_finance_account_subject_mapping_guard();

-- ---------------------------------------------------------------------------
-- G9b：规则处于启用态时，增删改分录行都要保持借贷齐备
-- ---------------------------------------------------------------------------
-- ⚠️ 必须是 **AFTER** 触发器：BEFORE 触发器里「本次变更」还没落到表上，
--   数出来的还是旧账 —— 那样这条守卫形同虚设。
CREATE OR REPLACE FUNCTION trg_finance_account_subject_mapping_line_balance() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_mapping finance_account_subject_event_mapping%ROWTYPE;
  v_debit   int;
  v_credit  int;
  v_tenant  uuid;
  v_mapping_id uuid;
BEGIN
  v_tenant := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
  v_mapping_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.mapping_id ELSE NEW.mapping_id END;

  SELECT * INTO v_mapping FROM finance_account_subject_event_mapping
   WHERE tenant_id = v_tenant AND id = v_mapping_id;
  IF NOT FOUND OR v_mapping.status <> 'ACTIVE' THEN
    RETURN NULL;
  END IF;

  SELECT count(*) FILTER (WHERE direction = 'DEBIT'),
         count(*) FILTER (WHERE direction = 'CREDIT')
    INTO v_debit, v_credit
    FROM finance_account_subject_event_mapping_line
   WHERE tenant_id = v_tenant AND mapping_id = v_mapping_id;
  IF v_debit = 0 OR v_credit = 0 THEN
    RAISE EXCEPTION '已启用的映射规则必须保持借贷齐备：当前借方 % 条 / 贷方 % 条', v_debit, v_credit
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$;

CREATE TRIGGER finance_account_subject_mapping_line_balance
  AFTER INSERT OR UPDATE OR DELETE ON finance_account_subject_event_mapping_line
  FOR EACH ROW EXECUTE FUNCTION trg_finance_account_subject_mapping_line_balance();

-- ===========================================================================
-- 第六节：RLS 与授权
-- ===========================================================================
--
-- 四张租户业务表：ENABLE **与 FORCE** RLS isolation（策略与既有 finance 表逐字同构）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_account_subject',
    'finance_account_subject_event_mapping',
    'finance_account_subject_event_mapping_line',
    'finance_account_subject_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)', tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_account_subject,
  finance_account_subject_event_mapping,
  finance_account_subject_event_mapping_line TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_account_subject,
  finance_account_subject_event_mapping,
  finance_account_subject_event_mapping_line TO starcon_app;

-- 模板是系统参考数据：运行期**只读**（写权限一律不授予）。
GRANT SELECT ON finance_account_subject_template TO finance_app;
GRANT SELECT ON finance_account_subject_template TO starcon_app;

-- 变更留痕：只能追加。
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_account_subject_event TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_account_subject_event TO starcon_app;
REVOKE UPDATE,DELETE ON finance_account_subject_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_account_subject_event FROM starcon_app;

-- ===========================================================================
-- 第七节：系统模板播种（企业会计准则通用科目表）
-- ===========================================================================
--
-- ⚠️ 这份模板**只是导入源**：用户导入后可以改名 / 改类别 / 改余额方向 / 停用 / 增删，
--   系统不强制「标准科目必须存在」。模板里刻意带了一个**汇总科目 + 两个明细子科目**
--   （1122 应收账款）—— 让「是否明细科目」这条字段在模板里就有真实含义，
--   而不是永远为 true 的装饰列。
INSERT INTO finance_account_subject_template
  (template_key, subject_code, subject_name, category, balance_direction, is_detail,
   parent_subject_code, tax_treatment, tax_rate, tax_note, sort_order)
VALUES
  ('CN_STANDARD_V1','1001','库存现金','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',10),
  ('CN_STANDARD_V1','1002','银行存款','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',20),
  ('CN_STANDARD_V1','1012','其他货币资金','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',30),
  ('CN_STANDARD_V1','1101','交易性金融资产','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',40),
  ('CN_STANDARD_V1','1122','应收账款','ASSET','DEBIT',false,null,'TAXABLE',NULL,'汇总科目',50),
  ('CN_STANDARD_V1','1122.01','应收账款-境内','ASSET','DEBIT',true,'1122','TAXABLE',NULL,'',51),
  ('CN_STANDARD_V1','1122.02','应收账款-境外','ASSET','DEBIT',true,'1122','TAXABLE',NULL,'',52),
  ('CN_STANDARD_V1','1123','预付账款','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',60),
  ('CN_STANDARD_V1','1221','其他应收款','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',70),
  ('CN_STANDARD_V1','1401','材料采购','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',80),
  ('CN_STANDARD_V1','1403','原材料','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',90),
  ('CN_STANDARD_V1','1405','库存商品','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',100),
  ('CN_STANDARD_V1','1601','固定资产','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',110),
  ('CN_STANDARD_V1','1602','累计折旧','ASSET','CREDIT',true,null,'EXEMPT',NULL,'资产类但记贷方余额',120),
  ('CN_STANDARD_V1','1701','无形资产','ASSET','DEBIT',true,null,'TAXABLE',NULL,'',130),
  ('CN_STANDARD_V1','2202','应付账款','LIABILITY','CREDIT',true,null,'TAXABLE',NULL,'',140),
  ('CN_STANDARD_V1','2203','预收账款','LIABILITY','CREDIT',true,null,'TAXABLE',NULL,'',150),
  ('CN_STANDARD_V1','2211','应付职工薪酬','LIABILITY','CREDIT',true,null,'TAXABLE',NULL,'',160),
  ('CN_STANDARD_V1','2221','应交税费','LIABILITY','CREDIT',true,null,'TAXABLE',0.130000,'增值税等',170),
  ('CN_STANDARD_V1','2241','其他应付款','LIABILITY','CREDIT',true,null,'TAXABLE',NULL,'',180),
  ('CN_STANDARD_V1','4001','实收资本','EQUITY','CREDIT',true,null,'NON_TAXABLE',NULL,'权益投入不计入应税所得',190),
  ('CN_STANDARD_V1','4103','本年利润','EQUITY','CREDIT',true,null,'NON_TAXABLE',NULL,'',200),
  ('CN_STANDARD_V1','4104','利润分配','EQUITY','CREDIT',true,null,'NON_TAXABLE',NULL,'',210),
  ('CN_STANDARD_V1','5001','生产成本','COST','DEBIT',true,null,'TAXABLE',NULL,'',220),
  ('CN_STANDARD_V1','5101','制造费用','COST','DEBIT',true,null,'TAXABLE',NULL,'',230),
  ('CN_STANDARD_V1','6001','主营业务收入','PROFIT_LOSS','CREDIT',true,null,'TAXABLE',0.130000,'增值税税率',240),
  ('CN_STANDARD_V1','6051','其他业务收入','PROFIT_LOSS','CREDIT',true,null,'TAXABLE',0.130000,'',250),
  ('CN_STANDARD_V1','6401','主营业务成本','COST','DEBIT',true,null,'TAXABLE',NULL,'',260),
  ('CN_STANDARD_V1','6403','税金及附加','PROFIT_LOSS','DEBIT',true,null,'TAXABLE',0.120000,'附加税率',270),
  ('CN_STANDARD_V1','6601','销售费用','PROFIT_LOSS','DEBIT',true,null,'TAXABLE',NULL,'',280),
  ('CN_STANDARD_V1','6602','管理费用','PROFIT_LOSS','DEBIT',true,null,'TAXABLE',NULL,'',290),
  ('CN_STANDARD_V1','6603','财务费用','PROFIT_LOSS','DEBIT',true,null,'TAXABLE',NULL,'',300);
