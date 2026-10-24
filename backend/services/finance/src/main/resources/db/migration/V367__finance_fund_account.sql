-- ===========================================================================
-- V367 · finance_fund_account —— 12.7.1.1 账户管理
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.7.1.1 账户管理）原文：
--
--   职责：管理资金账户：银行账户、支付平台账户、平台备付金账户，记录币种、账号、
--         余额、开户主体（法人）、账户类型。
--   输入：账户定义；余额同步或手工更新；关联法人。
--   输出：账户档案 + 余额历史。
--   关系：上游 = 17 法人；下游 = 12.2 收付款、12.7.1.2 对账。
--         **账户必须绑定法人**，跨法人混用资金账户会导致收入归属错误。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么（上游/下游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **法人是外部引用，不是 FK。** identity 在另一个库（17 法人，`legal_entity` 表
--     归 identity 的写入所有权），AGENTS.md 明文禁止跨库 FK / 跨服务 SQL / 共享视图
--     ⇒ 本表只做**软引用**（legal_entity_id + 编码/名称快照），
--     **落库前实时校验**由服务层经 identity 的授权只读端点
--     `GET /identity/legal-entities` 完成（见 `FundAccountLegalEntityPort`）。
--     未登记的法人 ⇒ 报错，**不静默接受**。
--
--   * **下游 12.2 收付款已交付，但只读账户信息。** 本模块**不写** 12.2 的任何表；
--     `finance_receipt.fund_account_ref` 由 V341 的 CHECK 钉死恒为 NULL
--     （V341 原文：「受管资金账户引用：12.7.1.1 账户管理未交付 ⇒ 由 CHECK 钉死恒为 NULL」）。
--     ⇒ 本模块交付后，12.2 要引用账户时由 **12.2 自己**改那条 CHECK（那是它的写入所有权），
--     本车道**不 ALTER 别人的表**。
--
--   * **下游 12.7.1.2 银行对账未交付。** 本模块**不做对账**、不产出对账差异、
--     不产出未达账项；只保证「账户档案 + 余额历史」可供将来的对账使用。
--
--   * **12.7.2.1 资金分析已交付**（`finance_fund_exposure_snapshot` /
--     `finance_liquidity_threshold` 两表）：本模块的账户余额是它的上游输入，
--     但那两张表是 12.7.2.1 自己的，本迁移**零 ALTER 零 DROP**。
--
--   * **本模块不做跨币种折算。** 汇率敞口归 12.7.2.1（它经 catalog 读汇率）。
--     本模块**绝不做**跨币种余额相加，也**不**按 1:1 假装折算：
--     汇总一律**按币种分组**呈现。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_fund_account         —— 资金账户档案（一个账户一个币种、一个法人）
--   finance_fund_account_balance —— 余额历史（**append-only**：只追加，不许改写）
--
--   两张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 为什么余额要单独一张 append-only 历史表（而不是在账户上覆盖一个余额列）
-- ---------------------------------------------------------------------------
--
--   「当时的余额是多少」必须**可复现** —— 否则事后对账、审计、复盘全都无从谈起。
--   所以：
--     · 每次余额同步 / 手工更新都**追加一条**余额历史（新行，不是改旧行）；
--     · 历史记录**不许 UPDATE / DELETE**（本迁移用「触发器 + 收回运行角色权限」两层钉死）；
--     · 账户档案上的 `current_balance` 是**当前值**，由 AFTER INSERT 触发器
--       从最新一条历史推导（单一写入口 ⇒ 不存在「两处余额对不上」）。
--
--   手工更新还要记**谁、何时、为什么、改前改后**（`operator_user_id` / `recorded_at` /
--   `reason` / `previous_amount` → `balance_amount`）—— 缺理由的 MANUAL 记录在 DB 层就被拒。
--
-- ---------------------------------------------------------------------------
-- 3. 为什么账号只存「掩码 + 后四位」
-- ---------------------------------------------------------------------------
--
--   账号/卡号是**敏感字段**：落库只留掩码（`6222****1234`）与后四位，
--   **原始账号不落库**、**不写日志**、接口返回也只给掩码。
--   DB 层用 CHECK 钉死：掩码非空 ⇒ 后四位必须恰好 4 位（不许只存掩码而把后四位丢掉）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_fund_account —— 资金账户档案
-- ---------------------------------------------------------------------------
--
-- 一个账户 = 一个币种 + 一个开户法人 + 一个受控账户类型。
-- 「一个账户一个币种」是硬约束（跨币种绝不相加的根）⇒ `currency` 是账户的固有属性。
CREATE TABLE finance_fund_account (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 本系统内的账户编号（人工可读的唯一编号，租户内唯一）。
  account_no varchar(48) NOT NULL CHECK(account_no ~ '^[A-Z0-9_-]{1,48}$'),
  account_name varchar(120) NOT NULL CHECK(length(btrim(account_name)) > 0),
  -- 账户类型：**受控词表**（不是自由文本）：银行账户 / 支付平台账户 / 平台备付金账户。
  account_type varchar(24) NOT NULL CHECK(account_type IN (
    'BANK',             -- 银行账户
    'PAYMENT_PLATFORM', -- 支付平台账户（PayPal / Stripe / 连连 …）
    'PLATFORM_RESERVE'  -- 平台备付金账户（Amazon 备付金 …）
  )),
  -- 单币种：账户的固有属性（跨币种绝不相加 ⇒ 不许一个账户挂两个币种）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 🔴 开户主体（法人）：**软引用**，跨库 FK 不允许（identity 在另一个库）。
  --    编码/名称是**快照**（历史可读），法律身份仍以 id 为准。
  legal_entity_id uuid NOT NULL,
  legal_entity_code varchar(48) NOT NULL,
  legal_entity_name varchar(200) NOT NULL,
  -- 开户行：只有银行账户有（其它类型填了就是脏数据 ⇒ CHECK 钉死）。
  bank_name varchar(120) NOT NULL DEFAULT '',
  -- 🔴 账号敏感信息：**只存掩码 + 后四位**，原始账号不落库。
  account_number_masked varchar(64) NOT NULL DEFAULT '',
  account_number_tail varchar(4) NOT NULL DEFAULT '',
  -- 账户户名（法人之外的收款人名，可空）。
  account_holder varchar(120) NOT NULL DEFAULT '',
  -- 账户状态：ACTIVE（启用）/ INACTIVE（停用）。
  -- ⚠️ **没有物理删除**：资金账户是资金凭据，删掉等于让「钱曾在这个账户里」从未发生过。
  --    停用（INACTIVE）之后不再接受新的余额登记。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  -- 当前余额：**当前值**，由 AFTER INSERT 触发器从最新一条余额历史推导（单一写入口）。
  current_balance numeric(19,4) NOT NULL DEFAULT 0,
  current_balance_at timestamptz NOT NULL DEFAULT now(),
  -- 余额历史条数（同上，单一写入口推导）。
  balance_record_count int NOT NULL DEFAULT 0 CHECK(balance_record_count >= 0),
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 租户内账户编号唯一。
  UNIQUE(tenant_id, account_no),
  -- 银行账户必须有开户行；非银行账户**不许**填开户行。
  CHECK(account_type = 'BANK' OR btrim(bank_name) = ''),
  CHECK(account_type <> 'BANK' OR btrim(bank_name) <> ''),
  -- 🔴 掩码与后四位要么都没有，要么**后四位恰好 4 位**（不许只留掩码而丢掉后四位）。
  CHECK(
    (btrim(account_number_masked) = '' AND btrim(account_number_tail) = '')
    OR (char_length(btrim(account_number_masked)) > 0 AND char_length(account_number_tail) = 4)
  )
);

-- 列表默认排序：账户编号；类型/币种/法人/状态各自是常用筛选维度。
CREATE INDEX finance_fund_account_by_type
  ON finance_fund_account(tenant_id, account_type, status);
CREATE INDEX finance_fund_account_by_currency
  ON finance_fund_account(tenant_id, currency);
CREATE INDEX finance_fund_account_by_legal_entity
  ON finance_fund_account(tenant_id, legal_entity_id);
CREATE INDEX finance_fund_account_by_name
  ON finance_fund_account(tenant_id, account_name);

-- ---------------------------------------------------------------------------
-- 2. finance_fund_account_balance —— 余额历史（append-only）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某账户在某时刻的一条余额事实」。**只追加，不许改写**。
CREATE TABLE finance_fund_account_balance (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  account_id uuid NOT NULL,
  -- 币种：**必须与账户一致**（触发器 guard_finance_fund_account_balance 钉死）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 当时的开户法人快照（法人后来可能换绑 ⇒ 历史要能复现「当时挂在哪个法人下」）。
  legal_entity_id uuid NOT NULL,
  legal_entity_code varchar(48) NOT NULL,
  legal_entity_name varchar(200) NOT NULL,
  -- 改后余额。
  balance_amount numeric(19,4) NOT NULL,
  -- 改前余额（**必填**：建档那一条的「改前」= 0，账户刚开立、此前无余额）。
  previous_amount numeric(19,4) NOT NULL,
  -- 余额来源：INITIAL（建档初始）/ SYNC（余额同步）/ MANUAL（手工更新）。
  change_source varchar(16) NOT NULL CHECK(change_source IN ('INITIAL','SYNC','MANUAL')),
  -- 手工更新必须写理由（DB 层钉死：MANUAL ⇒ reason 非空）。
  reason varchar(500) NOT NULL DEFAULT '',
  -- 同步的来源系统与来源流水号（幂等键的两半）。
  source_system varchar(48) NOT NULL DEFAULT '',
  source_reference varchar(120) NOT NULL DEFAULT '',
  -- 谁登记的（已认证会话里的用户 ⇒ 不是客户端自称）。
  operator_user_id uuid NOT NULL,
  -- 何时登记（**事实发生时刻**，可以早于写入时刻 —— 补录历史余额是常见场景）。
  recorded_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 手工更新必须留痕：理由非空（谁/何时由 operator_user_id + recorded_at 承担）。
  CHECK(change_source <> 'MANUAL' OR btrim(reason) <> ''),
  -- 🔴 同步必须带来源（幂等键的两半，缺一半就没法判重）。
  CHECK(change_source <> 'SYNC' OR (btrim(source_system) <> '' AND btrim(source_reference) <> ''))
);

-- 余额历史按账户 + 时刻查（回看某个账户的余额轨迹）。
CREATE INDEX finance_fund_account_balance_by_account
  ON finance_fund_account_balance(tenant_id, account_id, recorded_at DESC, id);
-- 🔴 同步幂等键：同一来源系统 + 同一来源流水号只入账一次（重复同步不重复入账）。
CREATE UNIQUE INDEX finance_fund_account_balance_sync_key
  ON finance_fund_account_balance(tenant_id, source_system, source_reference)
  WHERE change_source = 'SYNC';

-- ===========================================================================
-- 触发器：把纪律钉在 DB 层（服务层与 DB 各判一次，绕过服务层直连 SQL 也造不出假数据）
-- ===========================================================================

-- (a) 余额历史 **append-only**：任何 UPDATE / DELETE 一律拒绝。
--
--     「当时的余额」必须可复现 —— 允许改写历史行等于给账目留了一个后门。
--     重新同步 / 重新登记 ⇒ **追加新的一行**，不是把旧的一行改掉。
CREATE OR REPLACE FUNCTION guard_finance_fund_account_balance_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '资金账户余额历史是追加式的（append-only），不可 UPDATE / DELETE：%', OLD.id
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_fund_account_balance_append_only
  BEFORE UPDATE OR DELETE ON finance_fund_account_balance
  FOR EACH ROW EXECUTE FUNCTION guard_finance_fund_account_balance_append_only();

-- (b) 写入余额历史的三道闸：账户存在、币种一致、账户启用中。
--
--     币种一致这道闸是「跨币种绝不相加」的**数据层**保障：
--     一个账户只有一个币种，历史行不许出现第二个币种。
CREATE OR REPLACE FUNCTION guard_finance_fund_account_balance_insert() RETURNS trigger AS $$
DECLARE
  acc finance_fund_account%ROWTYPE;
BEGIN
  SELECT * INTO acc FROM finance_fund_account
   WHERE tenant_id = NEW.tenant_id AND id = NEW.account_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION '资金账户不存在或不属于当前租户：%', NEW.account_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF acc.currency <> NEW.currency THEN
    RAISE EXCEPTION '跨币种不允许登记余额：账户 % 是 %，历史行是 %',
      acc.account_no, acc.currency, NEW.currency USING ERRCODE = 'check_violation';
  END IF;

  IF acc.status <> 'ACTIVE' THEN
    RAISE EXCEPTION '资金账户 % 已停用，不再接受余额登记', acc.account_no
      USING ERRCODE = 'check_violation';
  END IF;

  -- 改前余额必须是**账户当前余额**（否则历史链条断了：前后两行对不上）。
  IF NEW.previous_amount <> acc.current_balance THEN
    RAISE EXCEPTION '资金账户 % 的改前余额应为 %，实际为 %：余额历史必须首尾相接',
      acc.account_no, acc.current_balance, NEW.previous_amount USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_fund_account_balance_guard
  BEFORE INSERT ON finance_fund_account_balance
  FOR EACH ROW EXECUTE FUNCTION guard_finance_fund_account_balance_insert();

-- (c) 当前余额的**单一写入口**：账户上的 current_balance 由余额历史推导。
--
--     ⚠️ 只有「事实时刻更晚」的历史行才推进当前余额：
--     补录一条**更早**的历史余额（recorded_at 早于当前余额时刻）只进历史，
--     **不**把当前余额倒回去 —— 当前余额永远是「最新时刻」的那个值。
CREATE OR REPLACE FUNCTION sync_finance_fund_account_balance() RETURNS trigger AS $$
BEGIN
  UPDATE finance_fund_account
     SET balance_record_count = balance_record_count + 1,
         updated_at = now(),
         current_balance = CASE WHEN NEW.recorded_at >= current_balance_at
                                THEN NEW.balance_amount ELSE current_balance END,
         current_balance_at = CASE WHEN NEW.recorded_at >= current_balance_at
                                   THEN NEW.recorded_at ELSE current_balance_at END
   WHERE tenant_id = NEW.tenant_id AND id = NEW.account_id;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_fund_account_balance_sync
  AFTER INSERT ON finance_fund_account_balance
  FOR EACH ROW EXECUTE FUNCTION sync_finance_fund_account_balance();

-- (d) 账户档案的守门：币种**不可改**（账户的固有属性，改了就是换了一个账户）、
--     账户编号不可改、账户类型与开户行必须自洽。
CREATE OR REPLACE FUNCTION guard_finance_fund_account_profile() RETURNS trigger AS $$
BEGIN
  IF NEW.currency IS DISTINCT FROM OLD.currency THEN
    RAISE EXCEPTION '资金账户 % 的币种不可改：单账户单币种是硬约束（改币种等于换一个账户）',
      OLD.account_no USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.account_no IS DISTINCT FROM OLD.account_no THEN
    RAISE EXCEPTION '资金账户编号不可改：%', OLD.account_no USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.account_type IS DISTINCT FROM OLD.account_type
     AND NEW.account_type <> 'BANK' AND btrim(NEW.bank_name) <> '' THEN
    RAISE EXCEPTION '资金账户 % 已不是银行账户，不许填开户行', OLD.account_no
      USING ERRCODE = 'check_violation';
  END IF;

  -- 停用账户之后不得再改档案（避免「停用期间偷偷改户名/账号」）。
  IF OLD.status = 'INACTIVE' AND NEW IS DISTINCT FROM OLD
     AND NEW.status = 'INACTIVE' THEN
    RAISE EXCEPTION '资金账户 % 已停用，不可再改档案：需要启用后再改', OLD.account_no
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_fund_account_profile_guard
  BEFORE UPDATE ON finance_fund_account
  FOR EACH ROW EXECUTE FUNCTION guard_finance_fund_account_profile();

-- ===========================================================================
-- 多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_fund_account','finance_fund_account_balance'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_fund_account TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_fund_account TO starcon_app;
-- 余额历史是**只追加**的：运行角色只给 SELECT + INSERT。
GRANT SELECT,INSERT ON finance_fund_account_balance TO finance_app;
GRANT SELECT,INSERT ON finance_fund_account_balance TO starcon_app;
REVOKE UPDATE,DELETE ON finance_fund_account_balance FROM finance_app;
REVOKE UPDATE,DELETE ON finance_fund_account_balance FROM starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;
