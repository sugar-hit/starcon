-- ===========================================================================
-- V343 · finance_payment_plan —— 12.1.2.2 付款计划
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.1.2.2 付款计划
--   - **职责**：按付款条件（账期、里程碑、到期日）生成付款计划与到期提醒，
--     支持按供应商与到期窗口批量安排付款。
--   - **输入**：应付到期日；账期规则；资金计划。
--   - **输出**：付款计划表 + 到期提醒 + 资金需求预测。
--   - **关系**：上游 = 12.1.2.1；下游 = 12.2.2.1、1.3 提醒。
--     **付款计划与资金预测联动**：跨境资金常有回款延迟与付款刚性，
--     错配会导致现金流断裂。
--
-- ---------------------------------------------------------------------------
-- 0. 四条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **三种付款条件是三种建模，不是一个 type 字段上的三行取值**
--      * `DUE_DATE`（到期日）—— 条件日 = 应付单自带的到期日（12.1.2.1 的事实）。
--      * `TERM`（账期）  —— 条件日 = 起算基准日 + **账期规则**里的账期天数。
--        账期规则是**租户自己维护**的一张表（finance_payment_term_rule），
--        刻意**不预置任何一行**：预置账期等于替用户把资金政策定了。
--      * `MILESTONE`（里程碑）—— 一张应付可以拆成**多条计划**，
--        每个里程碑一条（finance_payment_milestone 带比例 ratio）。
--      ⇒ 「账期 / 里程碑 / 到期日」三者**不是照抄上游到期日**：
--        账期要规则、里程碑要拆条、到期日才是直接取上游事实。
--
--   2. **付款执行不可用 —— 数据层就不存在「已付款」这个状态**（下游 12.2.2.1 未交付）
--      * `finance_payment_plan.execution_available` 与
--        `finance_payment_batch.execution_available` 都是
--        `CHECK(... = false)`：**表级 CHECK 只允许这一个取值**。
--      * `execution_owner_module` 恒为 '12.2.2.1'：归属写在数据上，
--        不是写在注释里（参照 trade 的 PENDING_FINANCE 写法）。
--      * ⇒ 本模块**绝不**造付款单、**绝不**写「已付款」。
--        12.2.2.1 交付后写的是 12.1.2.1 的行级 paid_amount，不是这里。
--
--   3. **到期提醒的记录归本模块，推送通道不归**（下游 1.3 未交付）
--      * `finance_payment_reminder` 存**真实的提醒条目**与**真实的到期判定**
--        （UPCOMING / DUE / OVERDUE）+ 真实的处理状态（PENDING /
--        ACKNOWLEDGED / DISMISSED）。
--      * `push_status` 恒为 'NOT_PUSHED'（表级 CHECK），
--        `push_owner_module` 恒为 '1.3'。
--        ⇒ **没有** sent_at、**没有**「已发送」这个取值、**没有**假的通知计数。
--
--   4. **资金需求预测与计划联动**（蓝图加粗句）
--      * 预测**不是一张表**：它是按 (币种 × 到期窗口) 对
--        `finance_payment_plan` 与 `finance_receivable` **实时聚合**出来的。
--        计划改了 ⇒ 预测当场跟着变，不需要任何同步动作。
--      * 流入侧取自 12.1.1.1 应收单的未核销余额与到期日（**只读**，
--        不写、不改结构、不加触发器）；流出侧是本模块的付款计划。
--      * 净额 = 流入 − 流出，负数 ⇒ 现金流断裂风险（净流出窗口）。
--        **绝不跨币种相加**（USD 与 EUR 加起来不是任何一个金额）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_payment_term_rule        —— 账期规则（租户自己维护，不预置任何行）
--   finance_payment_milestone_rule   —— 里程碑规则
--   finance_payment_milestone        —— 里程碑明细（比例 / 触发日）
--   finance_payment_batch             —— 付款批次（=「按供应商与到期窗口批量安排」的安排结果）
--   finance_payment_plan              —— 付款计划（核心）
--   finance_payment_plan_event        —— 计划事件流（append-only 举证凭据）
--   finance_payment_reminder          —— 到期提醒
--
--   七张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)、数量 numeric(19,6)、比例 numeric(19,6)；**全链路禁浮点**。
--   * 币种 char(3) + CHECK 正则；**任何汇总都按币种分组**。
--   * 计划金额 planned_amount ≤ 未付余额快照 unpaid_snapshot（DB CHECK）。
--   * 批次金额 planned_amount / plan_count **由计划派生**（触发器），不是录入的。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_payment_term_rule —— 账期规则
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条「什么条件下付钱」的规则。
--
-- ⚠️ **刻意不预置任何一行**：预置一条「30 天账期」等于替用户把资金政策定了。
--   没有启用的规则 ⇒ TERM 条件**排不出计划**（服务层拒绝并说明原因）。
--
-- 适用范围由两个可空列表达：
--   counterparty_ref —— NULL = 适用于所有供应商；非空 = 只适用于该供应商。
--   currency        —— NULL = 适用于所有币种；非空 = 只适用于该币种。
--   （两个都为空 = 全局规则；服务层选规则时**最具体优先**。）
CREATE TABLE finance_payment_term_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  name varchar(120) NOT NULL,
  counterparty_ref uuid,
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  -- 起算基准：应付确认日 / 应付到期日。
  base_kind text NOT NULL DEFAULT 'CONFIRMED_DATE'
    CHECK(base_kind IN ('CONFIRMED_DATE','DUE_DATE')),
  -- 账期天数：0 = 基准日当天到期。上限 365 天。
  term_days int NOT NULL CHECK(term_days >= 0 AND term_days <= 365),
  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, name)
);

-- ---------------------------------------------------------------------------
-- 2. finance_payment_milestone_rule —— 里程碑规则
-- ---------------------------------------------------------------------------
--
-- 一行 = 一套「这笔钱分几期付」的规则。里程碑本身在 finance_payment_milestone。
--
-- 适用范围的表达方式与账期规则一致（counterparty_ref / currency 可空）。
CREATE TABLE finance_payment_milestone_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  name varchar(120) NOT NULL,
  counterparty_ref uuid,
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, name)
);

-- ---------------------------------------------------------------------------
-- 3. finance_payment_milestone —— 里程碑明细
-- ---------------------------------------------------------------------------
--
-- 一个里程碑 = 一个比例 + 一个触发日。
--
-- 触发日两种写法**二选一**（CHECK 钉死）：
--   offset_days  —— 相对**应付确认日**的偏移（可为负 ⇒ 预付）。
--   trigger_date —— 绝对日（合同写死的里程碑日期）。
-- 两者都填或都不填都会被 DB 拒掉：「这个里程碑到底什么时候触发」不允许有歧义。
CREATE TABLE finance_payment_milestone (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  seq int NOT NULL CHECK(seq > 0),
  name varchar(120) NOT NULL,
  offset_days int CHECK(offset_days IS NULL OR offset_days >= -365),
  trigger_date date,
  -- 付款比例：0 < ratio ≤ 1。
  ratio numeric(19,6) NOT NULL CHECK(ratio > 0 AND ratio <= 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_id, seq),
  FOREIGN KEY(tenant_id, rule_id) REFERENCES finance_payment_milestone_rule(tenant_id, id) ON DELETE CASCADE,
  -- 触发日两种写法互斥。
  CHECK((offset_days IS NULL) <> (trigger_date IS NULL))
);

-- ---------------------------------------------------------------------------
-- 4. finance_payment_batch —— 付款批次（=「批量安排付款」的安排结果）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「按供应商 + 到期窗口把付款排进某个付款窗口」的结果。
--
-- ⚠️ **一批 = 一个币种**：跨币种的批次会让 planned_amount 变成一个没有意义的数字
--   （USD 与 EUR 加起来不是任何一个金额）。所以排期时**按币种分批**。
--
-- 两个窗口的区别（不要混）：
--   due_window_*      —— 应付**到期**窗口：这一批挑的是哪些到期日的应付。
--   payment_window_*  —— **付款**窗口：这一笔钱要在哪段时间里花出去。
-- 计划的条件日（condition_date）可能落在付款窗口之外 ⇒ 服务层把它**压进窗口**，
-- 并在计划上留下 clamped 标记（生成列，不是一个可以随手改的字段）。
CREATE TABLE finance_payment_batch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  batch_no varchar(48) NOT NULL,
  counterparty_type text NOT NULL
    CHECK(counterparty_type IN ('SUPPLIER','CARRIER','PLATFORM','OTHER')),
  counterparty_ref uuid,
  counterparty_name varchar(160) NOT NULL DEFAULT '',
  -- 应付到期窗口（挑选范围）。
  due_window_start date NOT NULL,
  due_window_end date NOT NULL,
  -- 付款窗口（花钱的时间段）。
  payment_window_start date NOT NULL,
  payment_window_end date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 计划金额与笔数：**由计划派生**（触发器 guard_finance_payment_batch_amount）。
  planned_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(planned_amount >= 0),
  plan_count int NOT NULL DEFAULT 0 CHECK(plan_count >= 0),
  status text NOT NULL DEFAULT 'SCHEDULED'
    CHECK(status IN ('SCHEDULED','CANCELLED')),
  -- 付款执行归属：**12.2.2.1 未交付** ⇒ 执行不可用，归属写死在数据上。
  execution_owner_module varchar(16) NOT NULL DEFAULT '12.2.2.1'
    CHECK(execution_owner_module = '12.2.2.1'),
  execution_available boolean NOT NULL DEFAULT false
    CHECK(execution_available = false),
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, batch_no),
  CHECK(due_window_start <= due_window_end),
  CHECK(payment_window_start <= payment_window_end)
);

-- ---------------------------------------------------------------------------
-- 5. finance_payment_plan —— 付款计划（核心）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「这笔钱打算在什么时候、按什么条件付」。
--
-- 🔴 **本表不产生任何付款**：
--   付款的唯一写入口是 12.1.2.1 的行级 paid_amount（归 12.2.2.1，未交付）。
--   本表的 execution_available 由表级 CHECK 钉死为 false ⇒ **数据层不存在「已付款」**。
--
-- 🔴 **一张应付不能被重复排两次**（否则就是重复付款的种子）：
--   部分唯一索引保证 —— 非里程碑条件下每张应付**最多一条**在册计划；
--   里程碑条件下每张应付**每个里程碑最多一条**。
--   （CANCELLED 的计划不在索引里 ⇒ 取消后可以重新排。）
--
-- condition_date 与 planned_paid_date **是两个日期**，这是有意为之：
--   condition_date    —— 按付款条件算出来的日子（到期日 / 基准日+账期 / 里程碑触发日）。
--   planned_paid_date —— 真正排进资金计划的那一天（可能被付款窗口压过）。
--   clamped 是**生成列** ⇒ 「条件日 ≠ 计划日」这件事不可能被改错。
CREATE TABLE finance_payment_plan (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  plan_no varchar(48) NOT NULL,
  -- 关联的应付单（**同一服务内的引用**，不是跨服务外键）。
  payable_id uuid NOT NULL,
  -- 付款批次：可空 —— 「只生成未安排」是合法的中间态。
  batch_id uuid,
  -- 付款条件：账期 / 里程碑 / 到期日。
  payment_condition text NOT NULL
    CHECK(payment_condition IN ('TERM','MILESTONE','DUE_DATE')),
  -- 账期规则（仅 TERM）。
  term_rule_id uuid,
  -- 里程碑（仅 MILESTONE）。
  milestone_id uuid,
  -- 条件日：按付款条件算出来的日子。
  condition_date date NOT NULL,
  -- 计划付款日：排进资金计划的那一天。
  planned_paid_date date NOT NULL,
  -- 是否被付款窗口压过：**生成列** ⇒ 不可能与两个日期对不上。
  clamped boolean GENERATED ALWAYS AS (condition_date <> planned_paid_date) STORED,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 计划付款金额：里程碑按比例、其余按未付余额。
  planned_amount numeric(19,4) NOT NULL CHECK(planned_amount > 0),
  -- 应付未付余额快照（生成计划那一刻）。
  unpaid_snapshot numeric(19,4) NOT NULL CHECK(unpaid_snapshot >= 0),
  status text NOT NULL DEFAULT 'PLANNED'
    CHECK(status IN ('PLANNED','SCHEDULED','CANCELLED')),
  -- 付款执行归属：**12.2.2.1 未交付** ⇒ 执行不可用。
  execution_owner_module varchar(16) NOT NULL DEFAULT '12.2.2.1'
    CHECK(execution_owner_module = '12.2.2.1'),
  execution_available boolean NOT NULL DEFAULT false
    CHECK(execution_available = false),
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, plan_no),
  FOREIGN KEY(tenant_id, payable_id) REFERENCES finance_payable(tenant_id, id),
  FOREIGN KEY(tenant_id, batch_id) REFERENCES finance_payment_batch(tenant_id, id),
  FOREIGN KEY(tenant_id, term_rule_id) REFERENCES finance_payment_term_rule(tenant_id, id),
  FOREIGN KEY(tenant_id, milestone_id) REFERENCES finance_payment_milestone(tenant_id, id),
  -- 计划金额不得超过它所依据的未付余额快照。
  CHECK(planned_amount <= unpaid_snapshot),
  -- 三种条件与它们的依据**严格对应**（不给「没有条件的 TERM」留位置）。
  CHECK(
       (payment_condition = 'TERM'      AND term_rule_id IS NOT NULL AND milestone_id IS NULL)
    OR (payment_condition = 'MILESTONE' AND term_rule_id IS NULL     AND milestone_id IS NOT NULL)
    OR (payment_condition = 'DUE_DATE'  AND term_rule_id IS NULL     AND milestone_id IS NULL)
  )
);

-- 一张应付最多一条在册计划（非里程碑条件）。
CREATE UNIQUE INDEX uq_finance_payment_plan_open_payable
  ON finance_payment_plan(tenant_id, payable_id)
  WHERE status <> 'CANCELLED' AND payment_condition <> 'MILESTONE';
-- 一张应付每个里程碑最多一条在册计划。
CREATE UNIQUE INDEX uq_finance_payment_plan_open_milestone
  ON finance_payment_plan(tenant_id, payable_id, milestone_id)
  WHERE status <> 'CANCELLED' AND payment_condition = 'MILESTONE';

-- ---------------------------------------------------------------------------
-- 6. finance_payment_plan_event —— 计划事件流（append-only 举证凭据）
-- ---------------------------------------------------------------------------
--
-- 与 12.1.2.1 的 finance_payable_event 同一手法：事件流是「当时发生了什么」的**唯一**出处，
-- 运行角色的 UPDATE / DELETE 已收回 —— 事件只能追加。
CREATE TABLE finance_payment_plan_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'GENERATED','SCHEDULED','RESCHEDULED','CANCELLED',
    'REMINDER_ACKNOWLEDGED','REMINDER_DISMISSED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, plan_id) REFERENCES finance_payment_plan(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 7. finance_payment_reminder —— 到期提醒
-- ---------------------------------------------------------------------------
--
-- 🔴 **推送通道不归本模块**：1.3 提醒系统未交付 ⇒
--   push_status 恒为 'NOT_PUSHED'（表级 CHECK），**没有** sent_at、
--   **没有**「已发送」这个取值、**没有**假的通知计数。
--   push_owner_module 恒为 '1.3'：归属写在数据上。
--
-- 本表存的是**真实的提醒条目**与**真实的处理状态**：
--   status ∈ PENDING / ACKNOWLEDGED / DISMISSED，
--   acknowledged_at + acknowledged_by 记录**真的**是谁在什么时候确认的。
CREATE TABLE finance_payment_reminder (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  -- 应付单（冗余一份，让「按供应商查提醒」不用每次都 JOIN）。
  payable_id uuid NOT NULL,
  -- 提醒类型：即将到期 / 今日到期 / 已逾期。
  reminder_type text NOT NULL
    CHECK(reminder_type IN ('UPCOMING','DUE','OVERDUE')),
  -- 提醒日（= 计划付款日）。
  remind_date date NOT NULL,
  -- 应付到期日（快照，提醒时显示「原本什么时候到期」）。
  due_date date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  amount numeric(19,4) NOT NULL CHECK(amount >= 0),
  -- 处理状态：待处理 / 已确认 / 已忽略。
  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','ACKNOWLEDGED','DISMISSED')),
  acknowledged_at timestamptz,
  acknowledged_by uuid,
  dismiss_reason varchar(500) NOT NULL DEFAULT '',
  -- 推送通道归属：**1.3 未交付** ⇒ 恒为未推送。
  push_owner_module varchar(16) NOT NULL DEFAULT '1.3'
    CHECK(push_owner_module = '1.3'),
  push_status text NOT NULL DEFAULT 'NOT_PUSHED'
    CHECK(push_status = 'NOT_PUSHED'),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一条计划最多一条提醒（提醒是「当前状态」，不是「推送历史」）。
  UNIQUE(tenant_id, plan_id),
  FOREIGN KEY(tenant_id, plan_id) REFERENCES finance_payment_plan(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, payable_id) REFERENCES finance_payable(tenant_id, id),
  -- 「已确认」与「有确认时刻 + 确认人」必须成对出现。
  CHECK(
    (status = 'ACKNOWLEDGED') =
      (acknowledged_at IS NOT NULL AND acknowledged_by IS NOT NULL)
  )
);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 批次金额与笔数：**全部由计划派生**。
--
--     理由与 12.1.2.1 的单头金额同源：批次的「要付多少钱 / 几笔」只有一个出处 ——
--     那批计划本身。应用层算错时这两列不会跟着错。
--
--     ⚠️ **DELETE 触发器里 NEW 是 NULL 记录，读它的字段会直接报错**
--       （PostgreSQL: record "new" is null）⇒ 必须按 TG_OP 分支取批次 id，
--       绝不能写 COALESCE(NEW.batch_id, OLD.batch_id)。
--     ⚠️ 计划在批次之间搬家时（重新排期），**旧批次也要重算** ⇒ 两个 id 都收进数组。
CREATE OR REPLACE FUNCTION guard_finance_payment_batch_amount() RETURNS trigger AS $$
DECLARE
  target_id uuid;
  target_ids uuid[];
  row_tenant uuid;
  amount_sum numeric(19,4);
  cnt int;
  batch_status text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    row_tenant := OLD.tenant_id;
    target_ids := ARRAY[OLD.batch_id];
  ELSIF TG_OP = 'INSERT' THEN
    row_tenant := NEW.tenant_id;
    target_ids := ARRAY[NEW.batch_id];
  ELSE
    row_tenant := NEW.tenant_id;
    target_ids := ARRAY(SELECT DISTINCT x FROM (
        SELECT NEW.batch_id AS x UNION ALL SELECT OLD.batch_id AS x) s WHERE x IS NOT NULL);
  END IF;

  FOREACH target_id IN ARRAY target_ids LOOP
    IF target_id IS NULL THEN CONTINUE; END IF;
    SELECT status INTO batch_status FROM finance_payment_batch
     WHERE tenant_id = row_tenant AND id = target_id
     FOR UPDATE;
    -- 批次已不存在（级联删除中）：无事可做。
    IF NOT FOUND THEN CONTINUE; END IF;
    -- 批次已取消：金额与笔数不再随计划变（终态）。
    IF batch_status = 'CANCELLED' THEN CONTINUE; END IF;

    SELECT coalesce(sum(planned_amount), 0), count(*)
      INTO amount_sum, cnt
      FROM finance_payment_plan
     WHERE tenant_id = row_tenant AND batch_id = target_id
       AND status <> 'CANCELLED';

    UPDATE finance_payment_batch
       SET planned_amount = amount_sum,
           plan_count = cnt,
           updated_at = now(),
           version = version + 1
     WHERE tenant_id = row_tenant AND id = target_id
       AND (planned_amount IS DISTINCT FROM amount_sum OR plan_count IS DISTINCT FROM cnt);
  END LOOP;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_plan_batch_amount
  AFTER INSERT OR UPDATE OR DELETE ON finance_payment_plan
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_batch_amount();

-- (b) 计划终态冻结：**CANCELLED 之后一行都不许改**。
--
--     取消不是删除：删掉就没有「这笔曾经被排过」的痕迹了。
CREATE OR REPLACE FUNCTION guard_finance_payment_plan_frozen() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'CANCELLED' THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '付款计划 % 已取消（终态），不可再改：需要改请重新生成一条', OLD.plan_no
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  -- 付款条件与它的依据**在计划建出来之后就冻结**：
  -- 计划的身份是「这张应付、在这个条件下、打算这样付」。
  -- 换条件 = 取消旧计划 + 生成新计划（改一半的条件会让计划失去可追溯性）。
  IF NEW.payment_condition IS DISTINCT FROM OLD.payment_condition
     OR NEW.term_rule_id IS DISTINCT FROM OLD.term_rule_id
     OR NEW.milestone_id IS DISTINCT FROM OLD.milestone_id
     OR NEW.payable_id IS DISTINCT FROM OLD.payable_id
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.condition_date IS DISTINCT FROM OLD.condition_date THEN
    RAISE EXCEPTION '付款计划 % 的付款条件与依据不可再改：换条件请取消后重新生成', OLD.plan_no
      USING ERRCODE = 'check_violation';
  END IF;

  -- 状态只允许往前走：PLANNED → SCHEDULED → CANCELLED。
  IF OLD.status = 'SCHEDULED' AND NEW.status = 'PLANNED' THEN
    RAISE EXCEPTION '付款计划 % 已安排，不可退回「已生成」', OLD.plan_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_plan_frozen
  BEFORE UPDATE ON finance_payment_plan
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_plan_frozen();

-- (c) 计划取消时，它的那条提醒**当场失效**。
--
--     理由：提醒是「当前状态」而不是「推送历史」——
--     计划都取消了，提醒还挂着 PENDING 就是在骗人（会说「有一笔要付款」，
--     而实际上已经被撤掉了）。
CREATE OR REPLACE FUNCTION guard_finance_payment_reminder_on_cancel() RETURNS trigger AS $$
BEGIN
  IF NEW.status = 'CANCELLED' AND OLD.status <> 'CANCELLED' THEN
    UPDATE finance_payment_reminder
       SET status = 'DISMISSED',
           dismiss_reason = '计划已取消',
           acknowledged_at = NULL,
           acknowledged_by = NULL,
           updated_at = now()
     WHERE tenant_id = NEW.tenant_id AND plan_id = NEW.id
       AND status <> 'DISMISSED';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_plan_cancel_reminder
  AFTER UPDATE ON finance_payment_plan
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_reminder_on_cancel();

-- (d) 里程碑比例之和必须为 1 —— **这一条刻意交给服务层整体校验，不做触发器**。
--
--     理由：「整套里程碑加起来是不是 100%」是**跨行**约束，而一套里程碑是
--     **整体替换**写入的（先删旧、再插新）。若用 AFTER 触发器逐行校验，
--     替换过程中的中间态（只插了第一期、比例和 = 0.3）会把整次写入打掉 ——
--     那样就只可能「一套一套慢慢加」，永远加不到 100%。
--     ⇒ 做法与 12.1.1.2 的账龄区间「连续覆盖」一致：**服务层对着整份配置校验**，
--     DB 层只兜单行（0 < ratio ≤ 1，由上面的 CHECK 挡）。

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：计划付款日 + id（资金计划按付款日排，不是按创建时间排）。
CREATE INDEX finance_payment_plan_by_paid_date
  ON finance_payment_plan(tenant_id, status, planned_paid_date, id);
-- 按应付查（生成 / 安排时判断「这张应付排过了没有」）。
CREATE INDEX finance_payment_plan_by_payable
  ON finance_payment_plan(tenant_id, payable_id, status);
-- 按批次查（一批里的计划）。
CREATE INDEX finance_payment_plan_by_batch
  ON finance_payment_plan(tenant_id, batch_id, planned_paid_date, id);
-- 预测按窗口聚合用。
CREATE INDEX finance_payment_plan_by_currency_date
  ON finance_payment_plan(tenant_id, currency, planned_paid_date);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_payment_plan_event_by_plan
  ON finance_payment_plan_event(tenant_id, plan_id, occurred_at, id);
-- 提醒按状态 + 提醒日排（工作台视图）。
CREATE INDEX finance_payment_reminder_by_status
  ON finance_payment_reminder(tenant_id, status, remind_date, id);
CREATE INDEX finance_payment_reminder_by_payable
  ON finance_payment_reminder(tenant_id, payable_id, remind_date, id);
-- 账期规则按适用范围查（选规则时按供应商 / 币种筛）。
CREATE INDEX finance_payment_term_rule_by_scope
  ON finance_payment_term_rule(tenant_id, enabled, counterparty_ref, currency);
-- 里程碑按规则取。
CREATE INDEX finance_payment_milestone_by_rule
  ON finance_payment_milestone(tenant_id, rule_id, seq);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_payment_term_rule','finance_payment_milestone_rule','finance_payment_milestone',
    'finance_payment_batch','finance_payment_plan','finance_payment_plan_event',
    'finance_payment_reminder'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_payment_term_rule, finance_payment_milestone_rule, finance_payment_milestone,
  finance_payment_batch, finance_payment_plan, finance_payment_plan_event,
  finance_payment_reminder TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_payment_term_rule, finance_payment_milestone_rule, finance_payment_milestone,
  finance_payment_batch, finance_payment_plan, finance_payment_plan_event,
  finance_payment_reminder TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：事件流是**举证凭据**，只能追加。
REVOKE UPDATE,DELETE ON finance_payment_plan_event FROM finance_app;

-- 计划与批次不需要物理删除：取消走 status='CANCELLED'，删掉就没有痕迹了。
REVOKE DELETE ON finance_payment_plan FROM finance_app;
REVOKE DELETE ON finance_payment_batch FROM finance_app;

-- ===========================================================================
-- 第五节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.2.2.1 付款单与审批（未交付）**：付款的唯一写入口是**行级 paid_amount**
--     （12.1.2.1 的 finance_payable_line，触发器已把单头已付金额与未付余额派生好）。
--     本模块**不造付款单、不写「已付款」**：
--     execution_available 由表级 CHECK 钉死为 false，execution_owner_module 恒为 '12.2.2.1'。
--     12.2.2.1 交付后，它读本模块的批次与计划建付款单，
--     执行结果回写 12.1.2.1 的行级 paid_amount —— **不需要**在本表上再加列。
--
--   * **1.3 提醒系统（未交付）**：本模块提供**真实的提醒条目与到期判定**
--     （UPCOMING / DUE / OVERDUE + PENDING / ACKNOWLEDGED / DISMISSED），
--     但**不推送**：push_status 恒为 'NOT_PUSHED'，push_owner_module 恒为 '1.3'。
--     1.3 交付后由它消费本表（或经授权 API 读取），本表结构不用改。
--
--   * **12.7 资金账户（未交付）**：本模块的「资金需求预测」是**按计划实时聚合**出来的，
--     不是资金账户余额的投影 —— 真正的资金头寸属 12.7 域。
--     预测的**流入侧**取自 12.1.1.1 应收单（已交付）的未核销余额与到期日（**只读**）。
