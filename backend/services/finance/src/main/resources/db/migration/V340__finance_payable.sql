-- ===========================================================================
-- V340 · finance_payable —— 12.1.2.1 应付单管理
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.1.2.1 应付单管理
--   - **职责**：管理应付账款：来源（采购收货、供应商对账、物流费用、平台费用、其他）、
--     应付对象、金额与币种、到期日、已付金额与未付余额。
--   - **输入**：采购收货确认（8.2.1.1）；服务费用（物流、平台、广告）；供应商对账单（6.5.1）；人工录入。
--   - **输出**：应付单 + 明细 + 未付清单。
--   - **关系**：上游 = 6 采购、10 物流、14 广告；下游 = 12.2.2.1 付款。
--     **应付、预付款与采购承诺分开建模**；
--     确认依据由业务合同和已批准政策决定，不能仅凭下单或收货统一推导。
--
-- 本迁移把上面那两句加粗句变成**数据层做不到的事**。
--
-- ---------------------------------------------------------------------------
-- 0. 三条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **应付、预付款与采购承诺分开建模**（蓝图加粗句）
--      * `finance_payable` / `finance_payable_line` / `finance_payable_event`
--        —— **应付**（已确认的付款义务）。
--      * `finance_prepayment` / `finance_prepayment_allocation`
--        —— **预付款**（预付挂账与抵扣），**独立的一张聚合，不与应付共用任何一张表**。
--      * **采购承诺**：本服务**不建模**。采购承诺的事实所有者是 6 采购（trade 的采购单），
--        另建一张 finance 表等于把同一份事实写两遍（AGENTS.md：每张事实表只有一个写入所有者）。
--        词表里保留 `COMMITMENT` 这个义务类型（见 finance_payable_policy.obligation_kind），
--        归属模块写 6.3.1.1，**能力声明里如实报出「本服务不建模」**。
--      ⇒ 三者的区分**不在一个 type 字段上糊过去**，而是**两张表 + 一个词表 + 能力声明**。
--
--   2. **确认依据由业务合同和已批准政策决定，不能仅凭下单或收货统一推导**（蓝图加粗句）
--      * `finance_payable_policy` —— 每个（来源 × 义务类型）一份**已批准政策**。
--        **刻意不预置任何一行**：预置 enabled=true 等于替用户把会计政策定了。
--        没有启用的政策 ⇒ 该来源**一张应付都产生不了**（服务层拒绝，错误消息写清原因）。
--      * 「不能仅凭收货推导」在数据层的落点：**应付单的金额只能由明细行派生**
--        （guard_finance_payable_amount），而明细行必须有**单价快照 + 数量**
--        —— 这两样都来自**合同/上游事实**，不是「收到货了所以欠钱」。
--        上游给不出单价快照或币种的行**导不进来**（服务层跳过并报警告），**绝不填 0**。
--
--   3. **已付金额与未付余额是派生的，不是录入的**
--      * 行级 `paid_amount` 是**付款的唯一入口**（12.2.2.1 付款单与审批交付后由它写）。
--        本模块**不提供**任何改 paid_amount 的端点。
--      * 单头 `paid_amount` 由触发器从明细求和派生；
--        单头 `unpaid_amount` 是**生成列**（amount − paid_amount），永远等于未付余额。
--      * 12.2.2.1 未交付 ⇒ paid_amount 恒为 0、单头恒为「未付」—— 这是**事实**，不是缺口：
--        钱确实还没付。能力声明里把它写成「付款写入口未交付」，而不是把它说成不可用。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_payable_policy         —— 应付/预付确认政策（**租户自己维护**，不预置任何行）
--   finance_payable                —— 应付单头
--   finance_payable_line           —— 应付明细行（付款精确到行）
--   finance_payable_event          —— 单据事件流（append-only 举证凭据）
--   finance_prepayment             —— 预付款单头（**独立聚合**）
--   finance_prepayment_allocation  —— 预付款抵扣明细
--
--   六张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)、数量 numeric(19,6)、汇率 numeric(19,8)；**全链路禁浮点**。
--   * 币种 char(3) + CHECK 正则；**任何汇总都按币种分组**（USD 与 EUR 加起来不是任何一个金额）。
--   * 本位币金额是**生成列** round(amount × exchange_rate, 4) ⇒ 汇率快照与本位币金额不可能漂移。
--     没有汇率时 base_amount 恒为 NULL（「未折算」≠「折算了 0 块」）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_payable_policy —— 应付 / 预付确认政策
-- ---------------------------------------------------------------------------
--
-- **这张表是「不替用户定会计政策」这条红线的物化形态。**
--
-- 一行 = 一个（来源 × 义务类型）。两列决定「这个来源能不能产生这类义务」：
--   enabled   —— 开关。关掉 ⇒ 这个来源**一张单都产生不了**。
--   due_days  —— 默认账期天数（0 = 确认即到期）。
--
-- 刻意**不预置任何一行**：预置 enabled=true 就等于系统替用户选了会计政策。
-- 租户第一次用某个来源时自己建这一行。
--
-- owner_module 记的是「这个来源的上游归谁」：
--   PURCHASE_RECEIPT  的上游是 6.3.2.2 到货与逾期（trade）      —— 已交付
--   LOGISTICS_FEE     的上游是 10.4.1.1 头程发运单（warehouse） —— 已交付
--   ADVERTISING_FEE   的上游是 14.3.1.1 活动报表（channel）      —— 已交付
--   SUPPLIER_STATEMENT 的上游是 12.1.2.3 供应商对账（finance）    —— **未交付**
--   PLATFORM_FEE      的上游是 12.3.1.1 结算单管理（finance）    —— **未交付**
-- 词表保留全部取值：上游交付后不需要改结构，只需要把 enabled 打开。
CREATE TABLE finance_payable_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 应付来源词表（与 finance_payable.source 同一份）。
  source text NOT NULL
    CHECK(source IN ('PURCHASE_RECEIPT','LOGISTICS_FEE','ADVERTISING_FEE','SUPPLIER_STATEMENT','PLATFORM_FEE','MANUAL','OTHER')),
  -- 义务类型：**应付 / 预付款 / 采购承诺**三者在词表上分开（蓝图加粗句）。
  --   PAYABLE     —— 应付（本服务的 finance_payable 聚合）
  --   PREPAYMENT  —— 预付款（本服务的 finance_prepayment 聚合，**另一张表**）
  --   COMMITMENT  —— 采购承诺：**本服务不建模**，归属 6 采购（trade 的采购单）
  obligation_kind text NOT NULL DEFAULT 'PAYABLE'
    CHECK(obligation_kind IN ('PAYABLE','PREPAYMENT','COMMITMENT')),
  enabled boolean NOT NULL DEFAULT false,
  -- 0 = 确认即到期；上限 365 天。
  due_days int NOT NULL DEFAULT 0 CHECK(due_days >= 0 AND due_days <= 365),
  note varchar(500) NOT NULL DEFAULT '',
  -- 上游归属模块（如 '12.1.2.3'）。**空串 = 上游已在本服务内闭环或人工来源**。
  owner_module varchar(16) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个来源 × 一个义务类型只有一份政策：政策不是「版本化」的，改了就是改了。
  UNIQUE(tenant_id, source, obligation_kind)
);

-- ---------------------------------------------------------------------------
-- 2. finance_payable —— 应付单头
-- ---------------------------------------------------------------------------
CREATE TABLE finance_payable (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  payable_no varchar(48) NOT NULL,
  -- 来源：与 policy.source 同一词表。
  source text NOT NULL
    CHECK(source IN ('PURCHASE_RECEIPT','LOGISTICS_FEE','ADVERTISING_FEE','SUPPLIER_STATEMENT','PLATFORM_FEE','MANUAL','OTHER')),
  -- 来源系统：manual / trade / warehouse / channel。与 05 价目的 projection 手法一致。
  source_system varchar(32) NOT NULL DEFAULT 'manual',
  -- **上游单号**（如 trade 的 purchase_arrival_payable_confirmation.confirmation_no）。
  -- 与 source 组成幂等键：同一笔上游事实重复导入只会命中同一条，不会长出第二张单。
  source_reference varchar(128),
  -- 应付对象类型：供应商 / 承运商 / 平台 / 其他。
  counterparty_type text NOT NULL
    CHECK(counterparty_type IN ('SUPPLIER','CARRIER','PLATFORM','OTHER')),
  counterparty_ref uuid,
  counterparty_name varchar(160) NOT NULL DEFAULT '',
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 单头金额：**由明细行派生**（见触发器 guard_finance_payable_amount），不是录进来的。
  amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(amount >= 0),
  -- 汇率快照：建单时写死，之后不可改。
  exchange_rate numeric(19,8) CHECK(exchange_rate IS NULL OR exchange_rate > 0),
  base_currency char(3) CHECK(base_currency IS NULL OR base_currency ~ '^[A-Z]{3}$'),
  -- 本位币金额：**生成列** ⇒ 永远等于 round(amount * exchange_rate, 4)。
  base_amount numeric(19,4) GENERATED ALWAYS AS (round(amount * exchange_rate, 4)) STORED,
  -- 已付金额：**派生**（明细行 paid_amount 之和）。写入口只有 12.2.2.1 付款。
  paid_amount numeric(19,4) NOT NULL DEFAULT 0
    CHECK(paid_amount >= 0 AND paid_amount <= amount),
  -- 未付余额：**生成列**（amount − paid_amount）⇒ 不可能与金额对不上。
  unpaid_amount numeric(19,4) GENERATED ALWAYS AS (amount - paid_amount) STORED,
  -- DRAFT → OPEN → PARTIALLY_PAID → PAID ；任意非终态 → VOID。
  -- PAID 与 VOID 是**终态**：整单冻结。
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','OPEN','PARTIALLY_PAID','PAID','VOID')),
  -- 到期日：确认日 + 政策账期（或上游自带账期）。**date 而不是 timestamptz**：
  -- 账期是日历天数，不是某一瞬间。
  due_date date NOT NULL,
  -- 应付确认时刻（到货确认时刻 / 人工录入时刻）。
  confirmed_at timestamptz NOT NULL,
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, payable_no),
  -- 幂等键：同一来源 + 同一上游单号只允许一张单。
  -- source_reference 为 NULL 时（人工录入）Postgres 视 NULL 互不相等 ⇒ 不受影响。
  UNIQUE(tenant_id, source, source_reference),
  -- 有本位币必有汇率，有汇率必有本位币：两个快照必须成对出现。
  CHECK((exchange_rate IS NULL) = (base_currency IS NULL)),
  -- 已付金额不得超过单头金额。
  CHECK(paid_amount <= amount)
);

-- ---------------------------------------------------------------------------
-- 3. finance_payable_line —— 应付明细行
-- ---------------------------------------------------------------------------
--
-- 「付款必须精确到明细行」（12.2.2.1 的付款单要选应付明细）：所以已付金额落在**行**上。
--
-- ⚠️ 行上的 quantity / unit_price **不是必填**：服务费用（物流、广告）天然没有数量与单价，
-- 只有金额 —— 硬要匹配会产生大量虚假异常。**但「采购收货」来源必须带单价快照**，
-- 否则就是「仅凭收货推导应付」，那是蓝图明令禁止的（见服务层）。
CREATE TABLE finance_payable_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  payable_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),
  description varchar(200) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  -- 上游明细引用（到货流水号 / 发运单号 / 活动外部 id）。可空。
  source_reference varchar(128),
  quantity numeric(19,6) CHECK(quantity IS NULL OR quantity > 0),
  unit_price numeric(19,4) CHECK(unit_price IS NULL OR unit_price >= 0),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  -- 行级已付金额。**12.2.2.1 付款单与审批交付后由它写**；本模块不提供写端点。
  paid_amount numeric(19,4) NOT NULL DEFAULT 0
    CHECK(paid_amount >= 0 AND paid_amount <= amount),
  -- 行级付款状态：**生成列**，由 paid_amount 与 amount 派生 ⇒ 不可能与金额打架。
  payment_status text GENERATED ALWAYS AS (
    CASE
      WHEN paid_amount = 0 THEN 'UNPAID'
      WHEN paid_amount < amount THEN 'PARTIALLY_PAID'
      ELSE 'PAID'
    END
  ) STORED,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, payable_id, line_no),
  FOREIGN KEY(tenant_id, payable_id) REFERENCES finance_payable(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 4. finance_payable_event —— 单据事件流（append-only 举证凭据）
-- ---------------------------------------------------------------------------
--
-- 与 trade 的 purchase_arrival_event 同一手法：事件流是「当时发生了什么」的**唯一**出处，
-- 运行角色的 UPDATE / DELETE 已收回 —— 事件只能追加。
CREATE TABLE finance_payable_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  payable_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'CREATED','CONFIRMED','IMPORTED','CONFIRMED_UPSTREAM','VOIDED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, payable_id) REFERENCES finance_payable(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 5. finance_prepayment —— 预付款单头（**独立聚合**）
-- ---------------------------------------------------------------------------
--
-- 🔴 **本表不是 finance_payable 的一部分**：蓝图写死「应付、预付款与采购承诺分开建模」。
--
-- 与应付的区别在**语义**上，不在字段上：
--   * 应付   = 已确认的付款义务（有到期日、有已付/未付余额）。
--   * 预付款 = 预付挂账（先付/先约定付的那一部分），**没有「已付金额」字段** ——
--     付款是 12.2.2.1 的职责，本模块只登记预付事实与它的抵扣，**不产生任何「已付款」回执**。
--   * 采购承诺 = 本服务不建模（归属 6 采购）。
--
-- amount 是**录入的事实**（租户登记「已约定/已预付给供应商 X 多少钱」），不是派生值 ——
-- 它没有明细行可加。可抵扣余额 unallocated_amount = amount − allocated_amount 是**生成列**。
CREATE TABLE finance_prepayment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  prepayment_no varchar(48) NOT NULL,
  counterparty_type text NOT NULL
    CHECK(counterparty_type IN ('SUPPLIER','CARRIER','PLATFORM','OTHER')),
  counterparty_ref uuid,
  counterparty_name varchar(160) NOT NULL DEFAULT '',
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 预付金额：录入的事实，必须大于 0。
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  -- 已抵扣金额：**派生**（finance_prepayment_allocation.amount 之和）。
  allocated_amount numeric(19,4) NOT NULL DEFAULT 0
    CHECK(allocated_amount >= 0 AND allocated_amount <= amount),
  -- 可抵扣余额：**生成列**。
  unallocated_amount numeric(19,4) GENERATED ALWAYS AS (amount - allocated_amount) STORED,
  -- OPEN → PARTIALLY_ALLOCATED → ALLOCATED ；任意非终态 → VOID。
  status text NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','PARTIALLY_ALLOCATED','ALLOCATED','VOID')),
  -- 预计付款日（**不是**实际付款日 —— 实际付款属 12.2.2.1）。
  expected_paid_date date NOT NULL,
  -- 关联采购单号 / 合同号（自由文本引用，**不建跨服务外键**）。
  source_reference varchar(128),
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, prepayment_no)
);

-- ---------------------------------------------------------------------------
-- 6. finance_prepayment_allocation —— 预付款抵扣明细
-- ---------------------------------------------------------------------------
--
-- 抵扣的目标可以是本服务的应付单，也可以是外部的采购单（自由文本引用）。
--
-- 🔴 **抵扣不改应付单的 paid_amount**：应付的「已付」只有一个来源 —— 12.2.2.1 付款写明细行。
-- 抵扣在这里只是**预付款的账**（这笔预付款花到哪里了），它给付款模块当输入用。
CREATE TABLE finance_prepayment_allocation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  prepayment_id uuid NOT NULL,
  target_type text NOT NULL CHECK(target_type IN ('PAYABLE','PURCHASE_ORDER')),
  -- target_type = 'PAYABLE' 时必须是本服务的一张应付单。
  payable_id uuid,
  -- target_type = 'PURCHASE_ORDER' 时的采购单号 / 合同号。
  target_reference varchar(128),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  allocated_at timestamptz NOT NULL,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, prepayment_id) REFERENCES finance_prepayment(tenant_id, id) ON DELETE CASCADE,
  -- 指向本服务的应付单时必须带 payable_id，且它必须真的存在（触发器校验）。
  FOREIGN KEY(tenant_id, payable_id) REFERENCES finance_payable(tenant_id, id),
  -- 两种目标各写各的：PAYABLE 写 payable_id，PURCHASE_ORDER 写 target_reference。
  CHECK((target_type = 'PAYABLE' AND payable_id IS NOT NULL AND target_reference IS NULL)
     OR (target_type = 'PURCHASE_ORDER' AND payable_id IS NULL AND target_reference IS NOT NULL))
);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 单头金额 / 已付金额 / 状态：**全部由明细行派生**。
--
--     应用层算错时这三列不会跟着错 —— 它们没有第二个写入口。
--     单头只有一个状态列到底；付款状态**只有一个出处**（明细行的 paid_amount）。
--
--     ⚠️ 触发器要能在「单头已被级联删除」时安静退出：明细行 ON DELETE CASCADE
--     会反过来打到这个触发器，那时单头已经不存在了。
CREATE OR REPLACE FUNCTION guard_finance_payable_amount() RETURNS trigger AS $$
DECLARE
  head finance_payable%ROWTYPE;
  line_sum numeric(19,4);
  paid_sum numeric(19,4);
  next_status text;
BEGIN
  SELECT * INTO head FROM finance_payable
   WHERE tenant_id = NEW.tenant_id AND id = NEW.payable_id
   FOR UPDATE;
  -- 单头已不存在（级联删除中）：无事可做。
  IF NOT FOUND THEN RETURN NULL; END IF;

  SELECT coalesce(sum(amount), 0), coalesce(sum(paid_amount), 0)
    INTO line_sum, paid_sum
    FROM finance_payable_line
   WHERE tenant_id = NEW.tenant_id AND payable_id = NEW.payable_id;

  -- DRAFT 与 VOID 的状态由**服务层**决定（建单 / 作废），不由行派生：
  -- 草稿就是草稿（还没确认），作废就是作废（终态，不会被行改回来）。
  IF head.status = 'DRAFT' THEN
    IF head.amount IS DISTINCT FROM line_sum THEN
      UPDATE finance_payable
         SET amount = line_sum, updated_at = now(), version = version + 1
       WHERE tenant_id = head.tenant_id AND id = head.id;
    END IF;
    RETURN NULL;
  END IF;

  IF head.status = 'VOID' THEN
    IF head.amount IS DISTINCT FROM line_sum OR head.paid_amount IS DISTINCT FROM paid_sum THEN
      UPDATE finance_payable
         SET amount = line_sum, paid_amount = paid_sum, updated_at = now()
       WHERE tenant_id = head.tenant_id AND id = head.id;
    END IF;
    RETURN NULL;
  END IF;

  -- 付款状态由行的已付金额派生：
  --   全部付清 ⇒ PAID；部分付 ⇒ PARTIALLY_PAID；一分未付 ⇒ OPEN。
  IF paid_sum = 0 THEN
    next_status := 'OPEN';
  ELSIF paid_sum < line_sum THEN
    next_status := 'PARTIALLY_PAID';
  ELSE
    next_status := 'PAID';
  END IF;

  IF head.amount IS DISTINCT FROM line_sum
     OR head.paid_amount IS DISTINCT FROM paid_sum
     OR head.status IS DISTINCT FROM next_status THEN
    UPDATE finance_payable
       SET amount = line_sum,
           paid_amount = paid_sum,
           status = next_status,
           updated_at = now(),
           version = version + 1
     WHERE tenant_id = head.tenant_id AND id = head.id;
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payable_line_amount
  AFTER INSERT OR UPDATE OR DELETE ON finance_payable_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payable_amount();

-- (b) 单头的**快照字段**在确认之后冻结。
--
--     冻结清单：币种、汇率、本位币、确认时刻、来源、来源单号、应付对象、到期日。
--     理由：卷 12 卷首要「今天汇率变了不能让上月报表变」——
--     允许事后改汇率快照，等于给报表留了一个可以改写历史数字的后门。
--
--     金额（amount）不在冻结清单里：它由触发器从明细行派生，
--     而明细行在确认后同样被 (c) 冻结 ⇒ 确认之后金额实际上也改不动了。
--
--     作废（VOID）与已付清（PAID）是**终态**：整单冻结，一行都不许改。
CREATE OR REPLACE FUNCTION guard_finance_payable_frozen() RETURNS trigger AS $$
BEGIN
  IF OLD.status IN ('VOID', 'PAID') THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '应付单 % 已是终态（%），不可再改：只能新建一张冲销单', OLD.payable_no, OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status <> 'DRAFT' THEN
    IF NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.exchange_rate IS DISTINCT FROM OLD.exchange_rate
       OR NEW.base_currency IS DISTINCT FROM OLD.base_currency
       OR NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at
       OR NEW.source IS DISTINCT FROM OLD.source
       OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
       OR NEW.counterparty_type IS DISTINCT FROM OLD.counterparty_type
       OR NEW.counterparty_ref IS DISTINCT FROM OLD.counterparty_ref
       OR NEW.counterparty_name IS DISTINCT FROM OLD.counterparty_name
       OR NEW.due_date IS DISTINCT FROM OLD.due_date THEN
      RAISE EXCEPTION '应付单 % 已确认，币种/汇率/到期日/来源/应付对象等快照字段不可再改', OLD.payable_no
        USING ERRCODE = 'check_violation';
    END IF;
    -- 确认之后状态只允许往前走（付款由 12.2.2.1 写），不许退回草稿。
    IF NEW.status = 'DRAFT' THEN
      RAISE EXCEPTION '应付单 % 已确认，不可退回草稿', OLD.payable_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payable_frozen
  BEFORE UPDATE ON finance_payable
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payable_frozen();

-- (c) 明细行在单头确认之后**不可改**（卷 12 卷首「过账后不可改行」在本模块的落法）。
--
--     只放行 paid_amount（付款金额）—— 那是 12.2.2.1 的写入所有权。
--     单头被作废（VOID）后，行彻底冻结。
CREATE OR REPLACE FUNCTION guard_finance_payable_line_frozen() RETURNS trigger AS $$
DECLARE head_status text;
BEGIN
  SELECT status INTO head_status FROM finance_payable
   WHERE tenant_id = NEW.tenant_id AND id = NEW.payable_id;
  IF head_status IS NULL THEN RETURN NEW; END IF;

  IF head_status = 'VOID' THEN
    RAISE EXCEPTION '应付单已作废，明细行不可再改' USING ERRCODE = 'check_violation';
  END IF;

  IF head_status <> 'DRAFT' THEN
    IF TG_OP = 'INSERT' THEN
      RAISE EXCEPTION '应付单已确认，不可再增加明细行' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.quantity IS DISTINCT FROM OLD.quantity
       OR NEW.unit_price IS DISTINCT FROM OLD.unit_price
       OR NEW.description IS DISTINCT FROM OLD.description
       OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
       OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
       OR NEW.line_no IS DISTINCT FROM OLD.line_no THEN
      RAISE EXCEPTION '应付单已确认，明细行的金额/数量/单价/描述不可再改（只能付款或作废）'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payable_line_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON finance_payable_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payable_line_frozen();

-- (d) 明细行金额必须为正。
--
--     「一行 0 块的应付」要么是录入错误，要么是想用明细行绕过「至少一行」的校验。
CREATE OR REPLACE FUNCTION guard_finance_payable_line_positive() RETURNS trigger AS $$
BEGIN
  IF NEW.amount <= 0 THEN
    RAISE EXCEPTION '应付明细行金额必须大于 0（收到 %）', NEW.amount
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payable_line_positive
  BEFORE INSERT OR UPDATE ON finance_payable_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payable_line_positive();

-- (e) 确认（OPEN）时必须有明细行，且单头金额必须等于明细合计。
CREATE OR REPLACE FUNCTION guard_finance_payable_confirm() RETURNS trigger AS $$
DECLARE line_count int; line_sum numeric(19,4);
BEGIN
  IF NEW.status = 'OPEN' AND OLD.status = 'DRAFT' THEN
    SELECT count(*), coalesce(sum(amount), 0) INTO line_count, line_sum
      FROM finance_payable_line
     WHERE tenant_id = NEW.tenant_id AND payable_id = NEW.id;
    IF line_count = 0 THEN
      RAISE EXCEPTION '应付单 % 没有任何明细行，不可确认', NEW.payable_no
        USING ERRCODE = 'check_violation';
    END IF;
    IF line_sum <= 0 THEN
      RAISE EXCEPTION '应付单 % 明细合计必须大于 0', NEW.payable_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payable_confirm
  BEFORE UPDATE ON finance_payable
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payable_confirm();

-- (f) 新建应付单**必须**先是草稿。
--
--     钉住入口 ⇒ 每一张单都必须走过「建单 → 落明细 → 确认」这条路。
CREATE OR REPLACE FUNCTION guard_finance_payable_insert_draft() RETURNS trigger AS $$
BEGIN
  IF NEW.status <> 'DRAFT' THEN
    RAISE EXCEPTION '新建应付单必须先是草稿：确认要走 DRAFT→OPEN（确认时才会校验明细行与合计）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payable_insert_draft
  BEFORE INSERT ON finance_payable
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payable_insert_draft();

-- (g) 预付款：已抵扣金额与状态**由抵扣明细派生**。
--
--     理由与应付单头一样：预付款的「花到哪里了」只有一个出处 —— 抵扣明细。
CREATE OR REPLACE FUNCTION guard_finance_prepayment_amount() RETURNS trigger AS $$
DECLARE
  head finance_prepayment%ROWTYPE;
  alloc_sum numeric(19,4);
  next_status text;
BEGIN
  SELECT * INTO head FROM finance_prepayment
   WHERE tenant_id = NEW.tenant_id AND id = NEW.prepayment_id
   FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;

  SELECT coalesce(sum(amount), 0) INTO alloc_sum
    FROM finance_prepayment_allocation
   WHERE tenant_id = NEW.tenant_id AND prepayment_id = NEW.prepayment_id;

  -- VOID 是终态：状态与已抵扣金额都不再随行变。
  IF head.status = 'VOID' THEN
    IF head.allocated_amount IS DISTINCT FROM alloc_sum THEN
      UPDATE finance_prepayment
         SET allocated_amount = alloc_sum, updated_at = now()
       WHERE tenant_id = head.tenant_id AND id = head.id;
    END IF;
    RETURN NULL;
  END IF;

  IF alloc_sum = 0 THEN
    next_status := 'OPEN';
  ELSIF alloc_sum < head.amount THEN
    next_status := 'PARTIALLY_ALLOCATED';
  ELSE
    next_status := 'ALLOCATED';
  END IF;

  IF head.allocated_amount IS DISTINCT FROM alloc_sum
     OR head.status IS DISTINCT FROM next_status THEN
    UPDATE finance_prepayment
       SET allocated_amount = alloc_sum,
           status = next_status,
           updated_at = now(),
           version = version + 1
     WHERE tenant_id = head.tenant_id AND id = head.id;
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_prepayment_allocation_amount
  AFTER INSERT OR UPDATE OR DELETE ON finance_prepayment_allocation
  FOR EACH ROW EXECUTE FUNCTION guard_finance_prepayment_amount();

-- (h) 预付款的「金额 / 币种 / 应付对象 / 预计付款日」在**发生抵扣之后**冻结。
--
--     理由：抵扣是「这笔钱花到哪里」的证据，改了金额就会让证据对不上。
--     未发生抵扣（OPEN）之前允许改 —— 那只是修正登记。
CREATE OR REPLACE FUNCTION guard_finance_prepayment_frozen() RETURNS trigger AS $$
BEGIN
  IF OLD.status IN ('VOID', 'ALLOCATED') THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '预付款 % 已是终态（%），不可再改', OLD.prepayment_no, OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.allocated_amount > 0 THEN
    IF NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.counterparty_type IS DISTINCT FROM OLD.counterparty_type
       OR NEW.counterparty_ref IS DISTINCT FROM OLD.counterparty_ref
       OR NEW.counterparty_name IS DISTINCT FROM OLD.counterparty_name
       OR NEW.expected_paid_date IS DISTINCT FROM OLD.expected_paid_date THEN
      RAISE EXCEPTION '预付款 % 已发生抵扣，金额/币种/应付对象/预计付款日不可再改', OLD.prepayment_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_prepayment_frozen
  BEFORE UPDATE ON finance_prepayment
  FOR EACH ROW EXECUTE FUNCTION guard_finance_prepayment_frozen();

-- (i) 抵扣金额不得超过预付款金额（数据层兜底，应用层也会先查）。
CREATE OR REPLACE FUNCTION guard_finance_prepayment_allocation_limit() RETURNS trigger AS $$
DECLARE head_amount numeric(19,4); head_allocated numeric(19,4);
BEGIN
  SELECT amount, allocated_amount INTO head_amount, head_allocated
    FROM finance_prepayment
   WHERE tenant_id = NEW.tenant_id AND id = NEW.prepayment_id;
  IF head_amount IS NULL THEN RETURN NEW; END IF;
  IF head_allocated + NEW.amount > head_amount THEN
    RAISE EXCEPTION '预付款抵扣后金额（%）超过预付金额（%）', head_allocated + NEW.amount, head_amount
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_prepayment_allocation_limit
  BEFORE INSERT ON finance_prepayment_allocation
  FOR EACH ROW EXECUTE FUNCTION guard_finance_prepayment_allocation_limit();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：到期日 + id（资金计划按到期日排，不是按创建时间排）。
CREATE INDEX finance_payable_by_due
  ON finance_payable(tenant_id, status, due_date, id);
-- 按来源查（导入幂等键 + 「这个来源产生了多少」）。
CREATE INDEX finance_payable_by_source
  ON finance_payable(tenant_id, source, source_reference);
-- 按应付对象查（供应商 / 承运商 / 平台）。
CREATE INDEX finance_payable_by_counterparty
  ON finance_payable(tenant_id, counterparty_ref, status, due_date);
-- 明细行按单取。
CREATE INDEX finance_payable_line_by_payable
  ON finance_payable_line(tenant_id, payable_id, line_no);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_payable_event_by_payable
  ON finance_payable_event(tenant_id, payable_id, occurred_at, id);
-- 预付款按状态 + 预计付款日排。
CREATE INDEX finance_prepayment_by_status
  ON finance_prepayment(tenant_id, status, expected_paid_date, id);
CREATE INDEX finance_prepayment_by_counterparty
  ON finance_prepayment(tenant_id, counterparty_ref, status);
CREATE INDEX finance_prepayment_allocation_by_prepayment
  ON finance_prepayment_allocation(tenant_id, prepayment_id, allocated_at, id);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_payable_policy','finance_payable','finance_payable_line',
    'finance_payable_event','finance_prepayment','finance_prepayment_allocation'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_payable_policy, finance_payable, finance_payable_line,
  finance_payable_event, finance_prepayment, finance_prepayment_allocation TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_payable_policy, finance_payable, finance_payable_line,
  finance_payable_event, finance_prepayment, finance_prepayment_allocation TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：事件流是**举证凭据**，只能追加。
-- 收回之后，即使服务层写错（误 UPDATE / 误 DELETE）也改不动已经记下的历史。
REVOKE UPDATE,DELETE ON finance_payable_event FROM finance_app;

-- 预付款不需要物理删除：作废走 status='VOID'，删掉就没有痕迹了。
REVOKE DELETE ON finance_prepayment FROM finance_app;

-- ===========================================================================
-- 第五节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.2.2.1 付款单与审批（未开发）**：付款的唯一写入口是**行级 paid_amount**。
--     本文件的触发器 (a) 会把单头已付金额、未付余额（生成列）与付款状态一起派生好，
--     12.2.2.1 交付后写明细行的 paid_amount 即可，**不需要**在本表上再加列。
--     12.2.2.1 交付前，已付金额恒为 0、单头恒为「未付」—— 这是事实，不是缺口。
--
--   * **12.1.2.2 付款计划（未开发）**：按账期/里程碑排付款计划、到期提醒、资金预测。
--     本模块已经给出到期日与未付余额（按币种分开），12.1.2.2 读同一批表即可。
--
--   * **12.1.2.3 供应商对账（未开发）**：财务侧供应商对账（核对采购侧对账单 6.5.1 与
--     收货/质检数据、差异处理与确认、最终生成应付依据）。**本服务不建模采购承诺**，
--     采购承诺的事实所有者是 6 采购（trade 的采购单）。
--
--   * **12.3.1.1 结算单管理（未开发）**：平台费用来源当前不可产生 ——
--     平台账单事实还不存在（12.3.1 的结算单是它自己）。字段与来源枚举已留好，
--     12.3.1 落地后经授权 API / event 写入即可，不需要改结构。
