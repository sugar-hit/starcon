-- ===========================================================================
-- V346 · finance_payment_order —— 12.2.2.1 付款单与审批
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.2.2.1 付款单与审批
--   - **职责**：发起付款申请：选择应付明细、付款账户、币种与汇率、付款方式、
--     分批安排，走分级审批。
--   - **输入**：付款申请；审批规则（金额阈值）；汇率快照。
--   - **输出**：付款单（草稿/待审/已批/执行中/已付/失败）+ 审批记录。
--   - **关系**：上游 = 12.1.2.1、12.1.2.2；下游 = 12.2.2.2、17 审批。
--     **付款审批是资金安全的第一道控制**，大额付款必须双人复核或多级审批。
--
-- ---------------------------------------------------------------------------
-- 0. 五条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **审批引擎不归本模块**（蓝图关系行写死「下游 = 17 审批」）。
--      * 17 审批已交付（identity 服务）：审批流定义 / 审批规则 / 待我审批 / 审批记录。
--      * ⇒ 本模块**只做两件事**：
--        ① 把付款单**提交**成 identity 的一条审批实例（`POST /identity/approval-instances`）；
--        ② 把 identity 的审批链路**镜像**回本地 `finance_payment_approval_record`。
--      * ⇒ **本模块不造第二套审批引擎**：没有本地的「审批人」表、没有本地的审批动作端点、
--        没有本地的并行/串行判定。**金额阈值分支由 identity 的 approval_flow_amount_branch
--        真实生效**（提交时 identity 自己按金额选起始节点）。
--      * ⇒ 「双人复核 / 多级审批」由 identity 的**节点结构**承担：
--        同阶段 PARALLEL 节点 = 双人复核；多阶段 SERIAL 节点 = 多级审批。
--        本模块**如实展示**该结构，但不自己判定。
--
--   2. **已付 / 失败写不出来**（下游 12.2.2.2 未交付）。
--      * 蓝图要求付款单有完整六态（草稿/待审/已批/执行中/已付/失败）⇒ **状态值域是齐的**。
--      * 但 `execution_available` 由**表级 CHECK 钉死为 false**（同 V343 的写法），
--        `execution_owner_module` 恒为 '12.2.2.2'（归属写在数据上，不是写在注释里）。
--      * ⇒ 触发器 `guard_finance_payment_order_state` 拒绝任何写入 EXECUTING / PAID / FAILED
--        的尝试 —— **本模块写不出「已付」**，直连 SQL 也一样。
--      * ⇒ 12.2.2.2 交付时**接管**这两个守卫（见文末第五节）。
--
--   3. **付款必须精确到应付明细行**（蓝图 12.1.2.1 的同一条纪律）。
--      * `finance_payment_order_line.payable_line_id` 是 **NOT NULL 外键**，
--        指向 12.1.2.1 的 `finance_payable_line`（**同服务同库** ⇒ 复合外键，不是跨库 FK）。
--      * ⇒ 「付款单只挂应付单头、不指到行」在数据层就写不出来。
--      * 部分唯一索引保证**同一条应付明细行不会被两张在册付款单同时占用**
--        （付款单进入终态时释放，与 V343「取消后可以重新排」同一手法）。
--
--   4. **汇率快照建单时冻结**（蓝图输入写死「汇率快照」）。
--      * `exchange_rate` / `base_currency` 写进单头即冻结，`base_amount` 是**生成列**
--        （round(amount * exchange_rate, 4)）⇒ 永远与金额、汇率自洽。
--      * 汇率**事实**来自 catalog 服务的 5.2.2.1（跨服务授权 HTTP API，**不跨库 SQL**）。
--
--   5. **本模块只读上游两处**（12.1.2.1 应付 / 12.1.2.2 付款计划）。
--      * 读它们的表走 jOOQ 显式 SQL（同服务同库）。
--      * **不写、不改结构、不加触发器、不改 GRANT** —— 与 V343 / V344 / V345 同一纪律。
--      * ⚠️ 特别地：**本模块不写** `finance_payable_line.paid_amount`。
--        V340 原文说「12.2.2.1 付款单与审批交付后由它写」，但**已付事实的写入口
--        必须在真正付出钱的那一刻** —— 那是 12.2.2.2「付款执行」的职责。
--        本模块只承诺「这些明细已被一张已批的付款单占用」，付款成功后才由 12.2.2.2
--        回写行级 paid_amount。⇒ 审批通过 ≠ 钱已付出。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_payment_order             —— 付款单头（六态状态机 + 汇率快照 + 审批回指）
--   finance_payment_order_line        —— 付款明细行（精确到应付明细行）
--   finance_payment_order_installment —— 分批安排（蓝图「分批安排」）
--   finance_payment_order_event       —— 付款单事件流（append-only 举证凭据）
--   finance_payment_approval_record   —— 审批记录（identity 审批链路的本地镜像）
--
--   五张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)、汇率 numeric(19,8)、数量 numeric(19,6)；**全链路禁浮点**。
--   * 币种 char(3) + CHECK 正则；**任何汇总都按币种分组**（USD 与 EUR 加起来不是任何一个金额）。
--   * 分批金额之和 = 单头金额：**跨行约束，交给服务层整体校验**（与 V343 的里程碑比例同一手法：
--     整套明细是整体替换写入的，逐行触发器校验会把「替换到一半」判成不一致）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_payment_order —— 付款单头
-- ---------------------------------------------------------------------------
--
-- 一行 = 「我们打算付这笔钱」的一次申请。
--
-- 🔴 **六态状态机是蓝图原文的六个词，一个不多一个不少**：
--    草稿 / 待审 / 已批 / 执行中 / 已付 / 失败。
--    「已驳回」**不是**一个状态：被驳回的付款单**退回草稿**（可改可再提交），
--    驳回这件事记在审批记录与事件流里 —— 单据状态只描述「这笔钱现在走到哪一步」。
CREATE TABLE finance_payment_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_no varchar(48) NOT NULL,
  -- 付款对象类型：与 12.1.2.1 同一词表。
  counterparty_type text NOT NULL
    CHECK(counterparty_type IN ('SUPPLIER','CARRIER','PLATFORM','OTHER')),
  counterparty_ref uuid,
  counterparty_name varchar(160) NOT NULL DEFAULT '',
  -- 币种与金额。amount > 0：金额为 0 的付款单没有意义。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  -- 汇率快照：建单时冻结，之后不可改（同 12.1.2.1 的 finance_payable 同一手法）。
  base_currency char(3) CHECK(base_currency IS NULL OR base_currency ~ '^[A-Z]{3}$'),
  exchange_rate numeric(19,8) CHECK(exchange_rate IS NULL OR exchange_rate > 0),
  -- 本位币金额：**生成列** ⇒ 永远等于 round(amount * exchange_rate, 4)。
  base_amount numeric(19,4) GENERATED ALWAYS AS (round(amount * exchange_rate, 4)) STORED,
  -- 付款方式。
  payment_method varchar(24) NOT NULL
    CHECK(payment_method IN ('BANK_TRANSFER','PAYPAL','STRIPE','PINGPONG','OTHER')),
  -- 付款账户：**12.7.1.1 资金账户（未交付）** ⇒ 本模块只登记「引用 + 名称 + 币种快照」，
  -- **不建账户表、不做账户余额**（那会造出第二份资金账户真相，两份真相必然打架）。
  payment_account_id uuid,
  payment_account_name varchar(160) NOT NULL DEFAULT '',
  payment_account_currency char(3)
    CHECK(payment_account_currency IS NULL OR payment_account_currency ~ '^[A-Z]{3}$'),
  -- 账户真相的归属模块（恒为 12.7.1.1 —— 它未交付）：归属写在数据上。
  payment_account_owner_module varchar(16) NOT NULL DEFAULT '12.7.1.1'
    CHECK(payment_account_owner_module = '12.7.1.1'),
  -- 付款计划（12.1.2.2，**同服务同库** ⇒ 复合外键）。可空：手工发起付款单不挂计划。
  payment_plan_id uuid,
  payment_plan_no varchar(48) NOT NULL DEFAULT '',
  -- 状态机（蓝图六态）。
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','PENDING_APPROVAL','APPROVED','EXECUTING','PAID','FAILED')),
  -- 付款执行归属：**12.2.2.2 未交付** ⇒ 执行不可用，归属写死在数据上。
  execution_available boolean NOT NULL DEFAULT false
    CHECK(execution_available = false),
  execution_owner_module varchar(16) NOT NULL DEFAULT '12.2.2.2'
    CHECK(execution_owner_module = '12.2.2.2'),
  -- ---- 审批回指（identity 服务的 17 审批） ----
  approval_flow_id uuid,
  approval_flow_name varchar(120) NOT NULL DEFAULT '',
  approval_instance_id uuid,
  approval_instance_no varchar(48) NOT NULL DEFAULT '',
  -- 审批状态：未提交 / 待审 / 已批 / 已驳回。
  approval_status text NOT NULL DEFAULT 'NOT_SUBMITTED'
    CHECK(approval_status IN ('NOT_SUBMITTED','PENDING','APPROVED','REJECTED')),
  -- 当前停在哪个审批节点（identity 按金额阈值分支选出来的那个节点）。
  approval_current_node_key varchar(32) NOT NULL DEFAULT '',
  approval_current_node_name varchar(100) NOT NULL DEFAULT '',
  -- 起始节点的路由依据：AMOUNT_BRANCH（命中金额阈值）/ FLOW_ORDER（流程顺序默认）。
  approval_routed_by varchar(24) NOT NULL DEFAULT '',
  submitted_by uuid,
  submitted_at timestamptz,
  approved_by uuid,
  approved_at timestamptz,
  -- 失败原因（**12.2.2.2 写**；本模块写不出 FAILED ⇒ 它恒为空串）。
  failure_reason varchar(500) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, order_no),
  -- 有本位币必有汇率，有汇率必有本位币：两个快照必须成对出现。
  CHECK((exchange_rate IS NULL) = (base_currency IS NULL)),
  -- 付款计划引用是同服务同库的复合外键（12.1.2.2 已交付）。
  FOREIGN KEY(tenant_id, payment_plan_id) REFERENCES finance_payment_plan(tenant_id, id),
  -- 已提交 ⇒ 提交人、提交时刻、审批实例三样齐全。
  CONSTRAINT finance_payment_order_submitted_ck CHECK (
    status = 'DRAFT'
    OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL
        AND approval_instance_id IS NOT NULL AND submitted_at <= updated_at)
  ),
  -- 已批 / 执行中 / 已付 / 失败 ⇒ 审批人与审批时刻齐全（它们都经过了一次通过的审批）。
  CONSTRAINT finance_payment_order_approved_ck CHECK (
    status NOT IN ('APPROVED','EXECUTING','PAID','FAILED')
    OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)
  ),
  -- 审批状态与单据状态必须一致：**不允许出现「单据已批但审批还没批」**。
  CONSTRAINT finance_payment_order_approval_consistency_ck CHECK (
    (status = 'DRAFT'      AND approval_status IN ('NOT_SUBMITTED','REJECTED'))
    OR (status = 'PENDING_APPROVAL' AND approval_status = 'PENDING')
    OR (status IN ('APPROVED','EXECUTING','PAID','FAILED') AND approval_status = 'APPROVED')
  ),
  -- 付款账户带币种 ⇒ 必须与单头币种一致：**币种对不上的付款在数据层写不出来**。
  CONSTRAINT finance_payment_order_account_currency_ck CHECK (
    payment_account_currency IS NULL OR payment_account_currency = currency
  ),
  -- 有付款账户 ⇒ 必须有账户名（不能只挂一个空引用）。
  CONSTRAINT finance_payment_order_account_ck CHECK (
    payment_account_id IS NULL OR btrim(payment_account_name) <> ''
  ),
  -- 失败 ⇒ 必须写明原因（否则事后答不出「这笔钱为什么没付出去」）。
  CONSTRAINT finance_payment_order_failed_ck CHECK (
    status <> 'FAILED' OR btrim(failure_reason) <> ''
  )
);

-- ---------------------------------------------------------------------------
-- 2. finance_payment_order_line —— 付款明细行
-- ---------------------------------------------------------------------------
--
-- 一行 = 「付这应付明细行的这笔钱」。
--
-- 🔴 **必须精确到明细行**（蓝图 12.1.2.1 的纪律）：payable_line_id 是 NOT NULL 外键，
--    指向 12.1.2.1 的 finance_payable_line（同服务同库）。
--    ⇒ 「付款单只挂应付单头」在数据层写不出来。
--
-- payable_amount_snapshot / payable_paid_snapshot 是**建单那一刻**该应付明细行的
-- 金额与已付金额快照：写进单据就冻结，后来应付那边变了也不改这里
-- （历史单据必须能复现「当时看到的是哪一版」）。
CREATE TABLE finance_payment_order_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),
  -- 应付明细行（12.1.2.1）。
  payable_line_id uuid NOT NULL,
  description varchar(200) NOT NULL DEFAULT '',
  -- 本行要付的金额与币种（**本付款单自己的事实**，不是应付那边的）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  -- 建单那一刻的应付明细快照。
  payable_amount_snapshot numeric(19,4) NOT NULL CHECK(payable_amount_snapshot >= 0),
  payable_paid_snapshot numeric(19,4) NOT NULL CHECK(payable_paid_snapshot >= 0),
  -- 这条明细是否仍占用该应付明细行。付款单进入终态（已付 / 失败）时释放
  -- ⇒ 释放后可以重新排（与 V343「取消后可以重新排」同一手法）。
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, order_id, line_no),
  FOREIGN KEY(tenant_id, order_id) REFERENCES finance_payment_order(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, payable_line_id) REFERENCES finance_payable_line(tenant_id, id),
  CONSTRAINT finance_payment_order_line_paid_ck
    CHECK(payable_paid_snapshot <= payable_amount_snapshot),
  -- 付款金额不得超过该应付明细行在**建单那一刻**的未付余额。
  CONSTRAINT finance_payment_order_line_unpaid_ck
    CHECK(amount <= payable_amount_snapshot - payable_paid_snapshot)
);

-- 同一条应付明细行**不会被两张在册付款单同时占用**。
CREATE UNIQUE INDEX uq_finance_payment_order_line_active_payable_line
  ON finance_payment_order_line(tenant_id, payable_line_id)
  WHERE active;

-- ---------------------------------------------------------------------------
-- 3. finance_payment_order_installment —— 分批安排
-- ---------------------------------------------------------------------------
--
-- 蓝图职责里写死的「**分批安排**」：一笔付款单可以拆成若干批，每批一个计划付款日与金额。
--
-- ⚠️ **分批金额之和 = 单头金额**是**跨行**约束 ⇒ **刻意交给服务层整体校验**，
--    不做触发器（与 V343 的里程碑比例同一手法：整套明细是整体替换写入的，
--    逐行触发器会把「替换到一半」判成不一致）。
--    DB 层只兜单行（amount > 0、seq > 0）。
--
-- 状态与单头同口径：PLANNED / EXECUTING / PAID / FAILED，
-- 且 EXECUTING / PAID / FAILED 的**写入权属 12.2.2.2**（同单头的守卫）。
CREATE TABLE finance_payment_order_installment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  seq int NOT NULL CHECK(seq > 0),
  -- 计划付款日：**日历日**（账期与排期是日历概念，不是某一瞬间）⇒ date 而不是 timestamptz。
  planned_paid_date date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  status text NOT NULL DEFAULT 'PLANNED'
    CHECK(status IN ('PLANNED','EXECUTING','PAID','FAILED')),
  -- 付款执行归属：**12.2.2.2 未交付** ⇒ 执行不可用。
  execution_available boolean NOT NULL DEFAULT false
    CHECK(execution_available = false),
  execution_owner_module varchar(16) NOT NULL DEFAULT '12.2.2.2'
    CHECK(execution_owner_module = '12.2.2.2'),
  failure_reason varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, order_id, seq),
  FOREIGN KEY(tenant_id, order_id) REFERENCES finance_payment_order(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT finance_payment_order_installment_failed_ck
    CHECK(status <> 'FAILED' OR btrim(failure_reason) <> '')
);

-- ---------------------------------------------------------------------------
-- 4. finance_payment_order_event —— 付款单事件流（append-only 举证凭据）
-- ---------------------------------------------------------------------------
--
-- 与 12.1.2.1 的 finance_payable_event、12.1.2.2 的 finance_payment_plan_event 同一手法：
-- 事件流是「当时发生了什么」的**唯一**出处，运行角色的 UPDATE / DELETE 已收回 ——
-- 事件只能追加。
CREATE TABLE finance_payment_order_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'CREATED','UPDATED','SUBMITTED','APPROVAL_SYNCED','APPROVED','RETURNED_TO_DRAFT',
    'EXECUTION_STARTED','PAID','FAILED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, order_id) REFERENCES finance_payment_order(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 5. finance_payment_approval_record —— 审批记录（identity 审批链路的本地镜像）
-- ---------------------------------------------------------------------------
--
-- 蓝图输出写死「付款单 + **审批记录**」。
--
-- 🔴 **这张表不是审批引擎，是镜像**：真正的审批实例、审批节点、审批动作、审批人
--   **全部住在 identity 服务**（17 审批，已交付）。本表存的是「从 identity 读回来的
--   那条链路」—— 目的有两个：
--   ① 付款单详情页不必为每次渲染都去跨服务调一次 identity；
--   ② 审批链路成为**付款单自己的举证凭据**（钱付错了，要能回答「谁批的、批到哪一步、
--      在哪个节点压了多久」）。
--
-- action 取值与 identity 的 approval_instance_action.action 同词表
-- （APPROVE / REJECT / TRANSFER），另加一条本模块自己写的 'SUBMIT'（提交那一刻）。
--
-- 幂等：按 (approval_instance_id, action_id) 去重 ⇒ 反复同步不会灌出重复行。
CREATE TABLE finance_payment_approval_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- identity 侧的审批实例。
  approval_instance_id uuid NOT NULL,
  approval_instance_no varchar(48) NOT NULL DEFAULT '',
  -- identity 侧的审批动作 id（提交那条没有 ⇒ 为 NULL）。
  action_id uuid,
  action varchar(16) NOT NULL CHECK(action IN ('SUBMIT','APPROVE','REJECT','TRANSFER')),
  node_key varchar(32) NOT NULL DEFAULT '',
  node_name varchar(100) NOT NULL DEFAULT '',
  node_order int NOT NULL DEFAULT 0,
  actor_id uuid,
  actor_name varchar(160) NOT NULL DEFAULT '',
  comment varchar(500) NOT NULL DEFAULT '',
  decided_at timestamptz NOT NULL,
  -- 这一步压了多久（identity 侧现算的 dwell_seconds）。
  dwell_seconds bigint NOT NULL DEFAULT 0,
  -- 镜像来源系统（恒为 'finance'：本模块是提交方）。
  source_system varchar(32) NOT NULL DEFAULT 'finance',
  synced_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, order_id) REFERENCES finance_payment_order(tenant_id, id) ON DELETE CASCADE
);

-- 同一审批动作只镜像一次（反复同步不灌重复行）。
CREATE UNIQUE INDEX uq_finance_payment_approval_record_action
  ON finance_payment_approval_record(tenant_id, approval_instance_id, action_id)
  WHERE action_id IS NOT NULL;
-- 提交记录：**每次提交尝试**留一条（同一张单被驳回后可以再提交 ⇒ 那是新的审批实例，
-- 不是重复的那一条）⇒ 唯一键带上 approval_instance_id。
CREATE UNIQUE INDEX uq_finance_payment_approval_record_submit
  ON finance_payment_approval_record(tenant_id, order_id, approval_instance_id, action)
  WHERE action = 'SUBMIT';

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 付款单状态机：单向 + 执行态不可写。
--
--     ① 允许的路径（**只有这五条**）：
--          DRAFT → PENDING_APPROVAL      （提交审批）
--          PENDING_APPROVAL → DRAFT      （被驳回：退回草稿，可改可再提交）
--          PENDING_APPROVAL → APPROVED   （审批通过）
--          APPROVED → EXECUTING          （12.2.2.2 接手执行）
--          EXECUTING → PAID / FAILED     （12.2.2.2 回写结果）
--     ② **EXECUTING / PAID / FAILED 本模块写不了**：
--        execution_available 由表级 CHECK 钉死为 false ⇒ 触发器直接拒绝。
--        「已付」不是一段没写的代码路径，而是**走不通的路径**。
--     ③ 提交之后，钱的那些事实一律冻结（金额 / 币种 / 汇率 / 付款方式 / 付款账户 /
--        付款对象）—— 改了就等于「审批批的是 A、付出去的是 B」。
CREATE OR REPLACE FUNCTION guard_finance_payment_order_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status IN ('EXECUTING','PAID','FAILED') AND NOT NEW.execution_available THEN
      RAISE EXCEPTION '付款单 % 的执行态（%）的写入权属 12.2.2.2「付款执行」（未交付）：execution_available 由表级 CHECK 钉死为 false，本模块写不出「已付」'
        , OLD.order_no, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;

    IF NOT (
         (OLD.status = 'DRAFT'          AND NEW.status = 'PENDING_APPROVAL')
      OR (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('DRAFT','APPROVED'))
      OR (OLD.status = 'APPROVED'        AND NEW.status = 'EXECUTING')
      OR (OLD.status = 'EXECUTING'       AND NEW.status IN ('PAID','FAILED'))
    ) THEN
      RAISE EXCEPTION '付款单 % 的状态只能沿 草稿→待审→已批→执行中→已付/失败 单向流转，% → % 不允许'
        , OLD.order_no, OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  IF OLD.status <> 'DRAFT' THEN
    IF NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.exchange_rate IS DISTINCT FROM OLD.exchange_rate
       OR NEW.base_currency IS DISTINCT FROM OLD.base_currency
       OR NEW.payment_method IS DISTINCT FROM OLD.payment_method
       OR NEW.payment_account_id IS DISTINCT FROM OLD.payment_account_id
       OR NEW.payment_account_name IS DISTINCT FROM OLD.payment_account_name
       OR NEW.payment_account_currency IS DISTINCT FROM OLD.payment_account_currency
       OR NEW.counterparty_type IS DISTINCT FROM OLD.counterparty_type
       OR NEW.counterparty_ref IS DISTINCT FROM OLD.counterparty_ref
       OR NEW.counterparty_name IS DISTINCT FROM OLD.counterparty_name
       OR NEW.payment_plan_id IS DISTINCT FROM OLD.payment_plan_id THEN
      RAISE EXCEPTION '付款单 % 已提交（%），金额 / 币种 / 汇率 / 付款方式 / 付款账户 / 付款对象不可再改：需要改请被驳回后退回草稿再改'
        , OLD.order_no, OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_order_state
  BEFORE UPDATE ON finance_payment_order
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_order_state();

-- (b) 付款单**不可删除**：删掉就没有「这笔曾经被申请过」的痕迹了。
CREATE OR REPLACE FUNCTION guard_finance_payment_order_no_delete() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '付款单 % 不可删除：付款单是资金凭据，只能推进状态', OLD.order_no
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_order_no_delete
  BEFORE DELETE ON finance_payment_order
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_order_no_delete();

-- (c) 明细行与分批：单据提交后一律冻结。
--
--     草稿态允许整体替换（改单就是「删旧行、插新行」）⇒ DELETE 权限保留，
--     但由本触发器挡住在非草稿态之外。
CREATE OR REPLACE FUNCTION guard_finance_payment_order_child_frozen() RETURNS trigger AS $$
DECLARE
  parent_status text;
BEGIN
  SELECT status INTO parent_status FROM finance_payment_order
   WHERE tenant_id = OLD.tenant_id AND id = OLD.order_id;
  -- 父单已被删除（级联中）⇒ 无事可做。
  IF parent_status IS NULL THEN
    RETURN OLD;
  END IF;
  IF parent_status <> 'DRAFT' THEN
    RAISE EXCEPTION '付款单已提交（%），明细与分批不可再改：需要改请被驳回后退回草稿再改', parent_status
      USING ERRCODE = 'check_violation';
  END IF;
  -- ⚠️ **UPDATE 必须返回 NEW**：BEFORE 触发器返回 OLD 等于「把这一行改回旧值」——
  --    那会让每一次更新都静默失效（语句成功、行没变），比报错更难查。
  --    DELETE 才返回 OLD（DELETE 的返回值语义是 NEW 为 NULL，返回 OLD 才是对的）。
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_order_line_frozen
  BEFORE UPDATE OR DELETE ON finance_payment_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_order_child_frozen();

CREATE TRIGGER finance_payment_order_installment_frozen
  BEFORE UPDATE OR DELETE ON finance_payment_order_installment
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_order_child_frozen();

-- (d) 分批的执行态同样归 12.2.2.2。
CREATE OR REPLACE FUNCTION guard_finance_payment_order_installment_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status IN ('EXECUTING','PAID','FAILED') AND NOT NEW.execution_available THEN
      RAISE EXCEPTION '付款分批 % 的执行态（%）的写入权属 12.2.2.2「付款执行」（未交付）'
        , OLD.seq, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;
    IF NOT (
         (OLD.status = 'PLANNED'  AND NEW.status = 'EXECUTING')
      OR (OLD.status = 'EXECUTING' AND NEW.status IN ('PAID','FAILED'))
    ) THEN
      RAISE EXCEPTION '付款分批的状态只能沿 已排期→执行中→已付/失败 单向流转，% → % 不允许'
        , OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_order_installment_state
  BEFORE UPDATE ON finance_payment_order_installment
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_order_installment_state();

-- (e) 付款单进入终态时，**释放**它占用的应付明细行。
--
--     理由：已付 / 已失败的付款单不再占用这些明细 ⇒ 允许重新排一笔新的付款单。
--     （真正防重复付款的是 12.1.2.1 的行级 paid_amount 与本模块的
--      「付款金额 ≤ 建单那一刻的未付余额」两道闸，不是这张索引。）
CREATE OR REPLACE FUNCTION guard_finance_payment_order_release_lines() RETURNS trigger AS $$
BEGIN
  IF NEW.status IN ('PAID','FAILED') AND OLD.status NOT IN ('PAID','FAILED') THEN
    UPDATE finance_payment_order_line
       SET active = false,
           updated_at = now()
     WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id AND active;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_order_release_lines
  AFTER UPDATE ON finance_payment_order
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_order_release_lines();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：创建时间倒序 + id（同一毫秒创建的单只看时间顺序不确定）。
CREATE INDEX finance_payment_order_by_status
  ON finance_payment_order(tenant_id, status, created_at DESC, id);
-- 按付款对象查（谁要付钱）。
CREATE INDEX finance_payment_order_by_counterparty
  ON finance_payment_order(tenant_id, counterparty_type, counterparty_ref, created_at DESC);
-- 按币种查（资金口径永远按币种分开）。
CREATE INDEX finance_payment_order_by_currency
  ON finance_payment_order(tenant_id, currency, created_at DESC);
-- 按审批实例反查（同步审批结果时定位单据）。
CREATE INDEX finance_payment_order_by_approval_instance
  ON finance_payment_order(tenant_id, approval_instance_id);
-- 按付款计划查（12.1.2.2 → 12.2.2.1 的交接面）。
CREATE INDEX finance_payment_order_by_plan
  ON finance_payment_order(tenant_id, payment_plan_id);
-- 明细行按单查 / 按应付明细查。
CREATE INDEX finance_payment_order_line_by_order
  ON finance_payment_order_line(tenant_id, order_id, line_no);
CREATE INDEX finance_payment_order_line_by_payable_line
  ON finance_payment_order_line(tenant_id, payable_line_id);
-- 分批按单查。
CREATE INDEX finance_payment_order_installment_by_order
  ON finance_payment_order_installment(tenant_id, order_id, seq);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_payment_order_event_by_order
  ON finance_payment_order_event(tenant_id, order_id, occurred_at, id);
-- 审批记录按单查。
CREATE INDEX finance_payment_approval_record_by_order
  ON finance_payment_approval_record(tenant_id, order_id, decided_at, id);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_payment_order','finance_payment_order_line','finance_payment_order_installment',
    'finance_payment_order_event','finance_payment_approval_record'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_payment_order, finance_payment_order_line, finance_payment_order_installment,
  finance_payment_order_event, finance_payment_approval_record TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_payment_order, finance_payment_order_line, finance_payment_order_installment,
  finance_payment_order_event, finance_payment_approval_record TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：
--   * 事件流是**举证凭据**，只能追加；
--   * 审批记录是**镜像**，只能追加（重新同步走「按 action_id 去重」的幂等路径）；
--   * 付款单是**资金凭据**，不可删除（触发器也会挡一层）。
REVOKE UPDATE,DELETE ON finance_payment_order_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_payment_approval_record FROM finance_app;
REVOKE DELETE ON finance_payment_order FROM finance_app;

-- ===========================================================================
-- 第五节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.2.2.2 付款执行（未交付）**：本模块交付的是「申请 + 审批」，
--     **交付不了「执行」**。交接面：
--       ① 表 `finance_payment_order` 的 status 已有 EXECUTING / PAID / FAILED 三态，
--          但 `execution_available` 被表级 CHECK 钉死为 false ⇒ 触发器
--          `guard_finance_payment_order_state` 拒绝写入 ⇒ **本模块写不出「已付」**。
--          12.2.2.2 交付时**接管**这两个守卫（放开 execution_available 的 CHECK，
--          并把该触发器换成它自己的执行态守卫），随后：
--            APPROVED → EXECUTING → PAID / FAILED
--          由它推进，并回写 `failure_reason`（FAILED 必填，DB CHECK 已经要求）。
--       ② `finance_payment_order_installment` 同样已有 EXECUTING / PAID / FAILED 三态，
--          守卫函数 `guard_finance_payment_order_installment_state` 同样待它接管。
--       ③ **行级已付金额**（`finance_payable_line.paid_amount`，V340 列名）
--          **由 12.2.2.2 在真正付出钱的那一刻回写** ——
--          本模块**刻意不写**它：审批通过 ≠ 钱已付出，
--          在审批阶段就写 paid_amount 会让「已付」这个事实早于它真正发生。
--       ④ 本模块**不**碰 V343 的 `finance_payment_plan` / `finance_payment_batch`：
--          那两张表的 `execution_available` 恒为 false 的 CHECK **一个字都不动**。
--
--   * **17 审批（已交付，identity 服务）**：本模块**已接上**：
--       ① 提交：`POST /identity/approval-instances`（X-Internal-Key + 调用方 bearer）
--          —— 金额阈值分支由 identity 的 `approval_flow_amount_branch` 真实生效；
--       ② 查生效流程：`GET /identity/approval-flows/active?documentTypeCode=…`
--          —— 用来把「双人复核（PARALLEL）/ 多级审批（SERIAL 多阶段）」的节点结构
--          **如实展示**给用户，本模块**不自己判定**；
--       ③ 读回链路：`GET /identity/approval-records/{id}`
--          —— 镜像进 `finance_payment_approval_record`。
--       ⇒ 审批的**判定与动作**全部在 identity 侧完成，本模块**不提供**本地的
--         「审批通过 / 审批驳回」端点（那会造出第二套审批引擎）。
--
--   * **12.7.1.1 资金账户（未交付）**：付款账户在本模块只是
--     **引用 + 名称 + 币种快照**（`payment_account_owner_module` 恒为 '12.7.1.1'），
--     **没有账户表、没有余额** —— 账户真相归 12.7.1.1，
--     它交付后由它接管账户登记与余额，本表结构不用改。
--
--   * **汇率事实（5.2.2.1，catalog 服务，已交付）**：本模块经**授权 HTTP API**
--     读 `GET /catalog/exchange-rates/latest`，把汇率**快照**写进单头并冻结。
--     **不跨库 SQL、不跨库 FK、不跨库视图**（AGENTS.md 模块边界纪律）。
