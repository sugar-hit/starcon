-- ===========================================================================
-- V341 · finance_receipt —— 12.2.1.1 收款单
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.2.1.1 收款单）原文：
--
--   职责：登记与跟踪资金收入：平台回款、银行到账、PayPal/Stripe/PingPong 等第三方支付到账、
--         其他收入；记录资金账户、金额币种、到账时间与来源。
--   输入：到账流水导入或手工登记；结算数据。
--   输出：收款单 + 未核销余额。
--   关系：上游 = 12.3.1 结算；下游 = 12.2.1.2 核销、12.7.1 资金账户。
--         **一笔收款可核销多笔应收**（合并回款），也可能是预收。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么（上游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **12.3.1.1 结算单管理未交付** ⇒ 「平台回款」这个来源**当前不可产生**。
--     DB 词表**保留** `PLATFORM_SETTLEMENT`（上游交付后还要能用），
--     但服务层拒绝用它建单，错误消息写明归属模块 12.3.1.1；
--     对应的 `settlement_no` 列由 CHECK 钉死**恒为 NULL** ——
--     填 0 或填一个假单号都会被读成「这笔来自某张结算单」，那是编事实。
--
--   * **12.7.1.1 账户管理未交付** ⇒ 没有**受管**资金账户可引用。
--     `fund_account_ref uuid` 由 CHECK 钉死**恒为 NULL**（归属 12.7.1.1）；
--     人能填的只有 `fund_account_label`（**自由文本**，例如 "招商银行 6222****1234"），
--     它**不是**受管资金账户，也没有与任何账户主数据关联 —— 只是登记时的备注式标识。
--
--   * **12.2.1.2 核销未交付** ⇒ 本模块**不实现核销**，也**不提供**任何
--     「手工标记已核销」的端点（那会让界面以为核销已交付）。
--     `settled_amount` 是**留给 12.2.1.2 写的唯一入口**，本模块不写它，
--     所以 `unsettled_amount`（生成列）**恒等于收款金额**。
--     这一点写进能力声明与会话日志，不靠猜。
--
--   * **不实现「预留 ≠ 核销」的混淆**：`finance_receipt_allocation` 登记的是
--     「这笔钱**打算**核销到哪几笔应收」的**预留关系**，
--     它**不减少**未核销余额（余额只由 settled_amount 决定）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_receipt            —— 收款单头（一次到账 = 一张单）
--   finance_receipt_allocation —— 预留核销关系（收款 ↔ 应收，多对多；合并回款）
--   finance_receipt_event      —— 单据事件流（append-only 举证凭据）
--
--   三张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 为什么收款单**没有明细行**
-- ---------------------------------------------------------------------------
--
--   12.1.1.1 应收单有明细行，是因为应收单是「一堆应收事实的汇总」，
--   而核销必须精确到行（卷 12 卷首）。
--   收款单不是：一次到账流水**本身就是一笔资金事实**（银行到账 / 第三方支付到账），
--   金额是原子的、可直接记账的。为它编一条「明细行」只是为了凑结构，
--   那是**假明细**。
--   ⇒ 收款单的金额直接登记在单头上（> 0 由 CHECK 钉死），
--     「一笔收款对应哪几笔应收」由 `finance_receipt_allocation` 表达。
--
-- ---------------------------------------------------------------------------
-- 3. 关键约束怎么落到数据层
-- ---------------------------------------------------------------------------
--
--   * 「金额一律 BigDecimal + NUMERIC，**禁止浮点**」
--     ⇒ amount / settled_amount / reserved_amount 一律 numeric(19,4)，本文件没有一处 float。
--
--   * 「**未核销余额**」= amount − settled_amount，用**生成列**表达：
--     它没有第二个写入口，所以「未核销余额」和「收款金额」不可能各说各话。
--
--   * 「**到账时间**」是核心字段（timestamptz）：跨时区到账记错 8 小时就是错账。
--     ⇒ 列是 timestamptz，服务层一律用 OffsetDateTime 绑定 + `?::timestamptz`。
--
--   * 「凭证过账后不可改行」在收款单上的落法：**到账事实不可改**。
--     金额、币种、到账时间、来源、付款方、资金账户标识一旦登记就冻结；
--     合法变化只有三种：① 作废（红冲的等价物，终态）、② 12.2.1.2 写核销金额、
--     ③ 预收标记的开关（且必须没有任何预留核销关系）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_receipt —— 收款单头
-- ---------------------------------------------------------------------------
--
-- ⚠️ 词表里的 `PLATFORM_SETTLEMENT`（平台回款）**当前不可产生**：
--   上游 12.3.1.1 结算单管理**未交付**。值保留（上游交付后还要能用），
--   但服务层拒绝用它建单并写明归属模块 —— 详见文件头第 0 节。
CREATE TABLE finance_receipt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receipt_no varchar(48) NOT NULL,
  -- 来源：平台回款 / 银行到账 / 第三方支付到账 / 人工登记 / 其他收入。
  source text NOT NULL
    CHECK(source IN ('PLATFORM_SETTLEMENT','BANK_TRANSFER','PAYMENT_GATEWAY','MANUAL','OTHER')),
  -- 来源系统：manual / bank / paypal / stripe / pingpong / …（自由文本，可追溯）。
  -- 与 source_reference 组成**幂等键**：同一来源系统的同一笔流水只允许一张单。
  source_system varchar(32) NOT NULL DEFAULT 'manual',
  -- 来源流水号（银行流水号 / 支付平台交易号）。人工登记可空。
  source_reference varchar(128),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 收款金额（一次到账 = 一笔资金事实，金额直接登记在单头上）。
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  -- 已核销金额：**核销（12.2.1.2）的唯一入口**，本模块不提供写端点 ⇒ 当前恒为 0。
  settled_amount numeric(19,4) NOT NULL DEFAULT 0
    CHECK(settled_amount >= 0 AND settled_amount <= amount),
  -- 未核销余额：**生成列** ⇒ 永远等于 amount − settled_amount，不可能有第二个写入口。
  -- 核销未交付前它恒等于收款金额 —— 这是口径，不是 bug。
  unsettled_amount numeric(19,4) GENERATED ALWAYS AS (amount - settled_amount) STORED,
  -- 预收：**还没有对应应收**的资金（钱先到了，货/服务还没到）。
  advance_flag boolean NOT NULL DEFAULT false,
  -- RECORDED（已登记）→ VOID（已作废，终态，整单冻结）。
  status text NOT NULL DEFAULT 'RECORDED' CHECK(status IN ('RECORDED','VOID')),
  -- 到账时间（**核心字段**）：timestamptz，跨时区到账记错 8 小时就是错账。
  received_at timestamptz NOT NULL,
  -- 付款方。
  payer_type text NOT NULL
    CHECK(payer_type IN ('PLATFORM','CUSTOMER','PARTNER','OTHER')),
  payer_name varchar(160) NOT NULL DEFAULT '',
  payer_ref uuid,
  -- 受管资金账户引用：12.7.1.1 账户管理**未交付** ⇒ 由 CHECK 钉死**恒为 NULL**。
  -- （填 0 或填一个假 uuid 会被读成「这笔进了某个已登记的账户」，那是编事实。）
  fund_account_ref uuid CHECK(fund_account_ref IS NULL),
  -- 资金账户标识（**自由文本**，不是受管资金账户，也没有与账户主数据关联）。
  -- 例："招商银行 6222****1234" / "PingPong-USD"。12.7.1.1 交付后由它回填受管引用。
  fund_account_label varchar(120) NOT NULL DEFAULT '',
  -- 结算单号：12.3.1.1 结算单管理**未交付** ⇒ 由 CHECK 钉死**恒为 NULL**。
  settlement_no varchar(64) CHECK(settlement_no IS NULL),
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, receipt_no),
  -- 幂等键：**来源系统 + 来源流水号**（可追溯的组合）。
  -- source_reference 为 NULL 时（人工登记）Postgres 视 NULL 互不相等 ⇒ 不受影响。
  UNIQUE(tenant_id, source_system, source_reference)
);

-- ---------------------------------------------------------------------------
-- 2. finance_receipt_allocation —— 预留核销关系（收款 ↔ 应收）
-- ---------------------------------------------------------------------------
--
-- 蓝图原话：「**一笔收款可核销多笔应收**（合并回款），也可能是预收。」
--   ⇒ 这张表就是「合并回款」的载体：一行 = 「这笔钱打算核销到这一笔应收上」。
--
-- ⚠️ **预留 ≠ 核销**：reserved_amount **不减少**未核销余额（余额只由 settled_amount 决定）。
--   真正的核销金额由 12.2.1.2 写 `finance_receipt.settled_amount`。
--   所以本表的 status 只有 RESERVED / RELEASED，**没有** SETTLED。
CREATE TABLE finance_receipt_allocation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  -- 指向应收单（**同服务同库**，可以建复合外键；跨服务才禁止）。
  receivable_id uuid NOT NULL,
  -- 预留金额（**不是**核销金额）。
  reserved_amount numeric(19,4) NOT NULL CHECK(reserved_amount > 0),
  status text NOT NULL DEFAULT 'RESERVED' CHECK(status IN ('RESERVED','RELEASED')),
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, receipt_id) REFERENCES finance_receipt(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, receivable_id) REFERENCES finance_receivable(tenant_id, id)
);

-- 「同一笔收款对同一笔应收只允许一条**未释放**的预留」。
--
-- ⚠️ 用**部分唯一索引**（WHERE status='RESERVED'）而不是表级 UNIQUE：
--   表级 UNIQUE 会让「释放之后重新预留」变成不可能 ——
--   而释放后再预留是**正常动作**（计划变了，先撤再改）。
--   释放掉的那条要留在表里（它是「当时登记过」的留痕），所以不能靠删。
CREATE UNIQUE INDEX finance_receipt_allocation_reserved_unique
  ON finance_receipt_allocation(tenant_id, receipt_id, receivable_id)
  WHERE status = 'RESERVED';

-- ---------------------------------------------------------------------------
-- 3. finance_receipt_event —— 单据事件流（append-only 举证凭据）
-- ---------------------------------------------------------------------------
--
-- 与 finance_receivable_event 同一手法：事件流是「当时发生了什么」的**唯一**出处，
-- 运行角色的 UPDATE / DELETE 已收回 —— 事件只能追加。
CREATE TABLE finance_receipt_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'CREATED','IMPORTED','RESERVED','RELEASED','VOIDED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, receipt_id) REFERENCES finance_receipt(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 到账事实一旦登记就**不可改**；合法变化只有：作废、预收标记开关、核销金额（12.2.1.2 写）。
--
--     冻结清单：金额、币种、到账时间、来源、来源系统、来源流水号、付款方、资金账户标识。
--     理由：一笔到账是**已经发生的事实** —— 允许事后改金额/时间，等于给账目留了一个
--     可以改写历史数字的后门（与 12.1.1.1 的汇率快照冻结同一条纪律）。
--
--     ⚠️ **受管账户引用（fund_account_ref）与结算单号（settlement_no）刻意不在冻结清单里**：
--       它们各自由 CHECK（恒为 NULL）钉死，交给 CHECK 报错更直白 ——
--       报错里能直接看到是哪一个列被钉住了，而不是一句笼统的「不可再改」。
--
--     作废（VOID）是**终态**：整单冻结，一列都不许改。
CREATE OR REPLACE FUNCTION guard_finance_receipt_frozen() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'VOID' THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '收款单 % 已作废（终态），不可再改：只能新建一张收款单', OLD.receipt_no
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.amount IS DISTINCT FROM OLD.amount
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.received_at IS DISTINCT FROM OLD.received_at
     OR NEW.source IS DISTINCT FROM OLD.source
     OR NEW.source_system IS DISTINCT FROM OLD.source_system
     OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
     OR NEW.payer_type IS DISTINCT FROM OLD.payer_type
     OR NEW.payer_name IS DISTINCT FROM OLD.payer_name
     OR NEW.payer_ref IS DISTINCT FROM OLD.payer_ref
     OR NEW.fund_account_label IS DISTINCT FROM OLD.fund_account_label THEN
    RAISE EXCEPTION '收款单 % 的到账事实（金额/币种/到账时间/来源/付款方/资金账户标识）不可再改：只能作废重登',
      OLD.receipt_no USING ERRCODE = 'check_violation';
  END IF;

  -- 状态只允许走到 VOID（终态）。
  IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status <> 'VOID' THEN
    RAISE EXCEPTION '收款单 % 的状态只能走到 VOID（已作废），不可改成 %', OLD.receipt_no, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 预收标记：只有在**没有任何预留核销关系**时才能打开。
  --   「有预留的收款」已经有对应应收了，不是预收；反过来也一样。
  IF NEW.advance_flag IS DISTINCT FROM OLD.advance_flag AND NEW.advance_flag THEN
    IF EXISTS (
      SELECT 1 FROM finance_receipt_allocation a
       WHERE a.tenant_id = OLD.tenant_id AND a.receipt_id = OLD.id AND a.status = 'RESERVED'
    ) THEN
      RAISE EXCEPTION '收款单 % 已有预留核销关系（有对应应收），不能改成预收', OLD.receipt_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_receipt_frozen
  BEFORE UPDATE ON finance_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_finance_receipt_frozen();

-- (b) 预留核销关系的三道闸：币种一致、预留总额不超收款金额、预收单不得有预留。
--
--     预算是**登记**「打算核销到哪几笔应收」，不是核销本身：
--     它**不减少**未核销余额（余额只由 settled_amount 决定），
--     所以本触发器刻意**不碰** finance_receipt.settled_amount。
CREATE OR REPLACE FUNCTION guard_finance_receipt_allocation() RETURNS trigger AS $$
DECLARE
  head finance_receipt%ROWTYPE;
  target_currency char(3);
  reserved_sum numeric(19,4);
BEGIN
  IF TG_OP = 'DELETE' THEN
    SELECT * INTO head FROM finance_receipt
     WHERE tenant_id = OLD.tenant_id AND id = OLD.receipt_id;
    IF head.status = 'VOID' THEN
      RAISE EXCEPTION '收款单 % 已作废（终态），预留核销关系不可再改', head.receipt_no
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
  END IF;

  SELECT * INTO head FROM finance_receipt
   WHERE tenant_id = NEW.tenant_id AND id = NEW.receipt_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION '收款单不存在或不属于当前租户' USING ERRCODE = 'check_violation';
  END IF;
  IF head.status = 'VOID' THEN
    RAISE EXCEPTION '收款单 % 已作废（终态），不可再改', head.receipt_no
      USING ERRCODE = 'check_violation';
  END IF;
  IF head.advance_flag THEN
    RAISE EXCEPTION '收款单 % 是预收（尚无对应应收），不能有核销预留：预收 = 还没有对应应收的资金',
      head.receipt_no USING ERRCODE = 'check_violation';
  END IF;

  SELECT currency INTO target_currency FROM finance_receivable
   WHERE tenant_id = NEW.tenant_id AND id = NEW.receivable_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '应收单 % 不存在或不属于当前租户', NEW.receivable_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF target_currency <> head.currency THEN
    RAISE EXCEPTION '跨币种不允许预留核销：收款单 % 是 %，应收单 % 是 %',
      head.receipt_no, head.currency, NEW.receivable_id, target_currency
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT coalesce(sum(reserved_amount), 0) INTO reserved_sum
    FROM finance_receipt_allocation
   WHERE tenant_id = NEW.tenant_id
     AND receipt_id = NEW.receipt_id
     AND status = 'RESERVED'
     AND (TG_OP = 'INSERT' OR id <> NEW.id);
  IF reserved_sum + NEW.reserved_amount > head.amount THEN
    RAISE EXCEPTION '预留核销合计 % 超过收款单 % 的金额 %：预留不是核销，未核销余额不会因预留而减少',
      reserved_sum + NEW.reserved_amount, head.receipt_no, head.amount
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_receipt_allocation_guard
  BEFORE INSERT OR UPDATE OR DELETE ON finance_receipt_allocation
  FOR EACH ROW EXECUTE FUNCTION guard_finance_receipt_allocation();

-- (c) 已作废的收款单不能被释放预留 / 再改 —— 由 (a) + (b) 一起兜住。

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：到账时间倒序（资金台账看的是最近到账）+ id。
CREATE INDEX finance_receipt_by_received
  ON finance_receipt(tenant_id, status, received_at DESC, id);
-- 按来源查（导入幂等键 + 「这个来源带来了多少」）。
CREATE INDEX finance_receipt_by_source
  ON finance_receipt(tenant_id, source, source_system, source_reference);
-- 按付款方查。
CREATE INDEX finance_receipt_by_payer
  ON finance_receipt(tenant_id, payer_type, payer_ref, received_at DESC);
-- 预收单（尚无对应应收的资金）。
CREATE INDEX finance_receipt_by_advance
  ON finance_receipt(tenant_id, received_at DESC)
  WHERE advance_flag;
-- 预留关系按收款取 / 按应收取（合并回款：几笔应收被同一笔收款覆盖）。
CREATE INDEX finance_receipt_allocation_by_receipt
  ON finance_receipt_allocation(tenant_id, receipt_id, status);
CREATE INDEX finance_receipt_allocation_by_receivable
  ON finance_receipt_allocation(tenant_id, receivable_id);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_receipt_event_by_receipt
  ON finance_receipt_event(tenant_id, receipt_id, occurred_at, id);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_receipt','finance_receipt_allocation','finance_receipt_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_receipt, finance_receipt_allocation, finance_receipt_event TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_receipt, finance_receipt_allocation, finance_receipt_event TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：事件流是**举证凭据**，只能追加。
REVOKE UPDATE,DELETE ON finance_receipt_event FROM finance_app;

-- ===========================================================================
-- 第五节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.2.1.2 核销（未开发）**：核销金额的**唯一**入口是
--     `finance_receipt.settled_amount`（本模块不写它，也没有写端点）。
--     核销同时要写 12.1.1.1 的 `finance_receivable_line.settled_amount` ——
--     那边是行级核销的唯一入口。两边都写完后，
--     `finance_receipt.unsettled_amount`（生成列）自动变成真正的未核销余额。
--     `finance_receipt_allocation` 里的 RESERVED 关系届时应被核销流程消费
--     （核销完成后释放或改判），本模块只负责登记与跟踪。
--
--   * **12.3.1.1 结算单管理（未开发）**：平台回款来源当前**不可产生**；
--     `settlement_no` 已留好并由 CHECK 钉死为 NULL，12.3.1 落地后
--     DROP 掉那条 CHECK 即可接上，**不需要改结构**。
--
--   * **12.7.1.1 账户管理（未开发）**：`fund_account_ref` 同理 ——
--     DROP CHECK 之后由账户主数据回填，`fund_account_label`（自由文本）保留为人工备注。
--
--   * **12.5.2.1 凭证（未开发）**：收款单是凭证的**来源**而不是凭证本身；
--     凭证的锁头 / 平衡 / 期间开放校验属总账。
