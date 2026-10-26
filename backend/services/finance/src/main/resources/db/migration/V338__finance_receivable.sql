-- ===========================================================================
-- V338 · finance_receivable —— 12 财务核算服务的第一张业务表 + 12.1.1.1 应收单管理
-- ===========================================================================
--
-- **本文件同时承担两件事**（新服务只有一次「服务引导」的机会，合在一起最省事）：
--
--   1. **服务引导**：finance 是蓝图 12 卷「后端归属」写死的服务
--      （唯一拥有应收应付、收付款、对账、成本、总账、发票税务）。
--      本仓此前**不存在** backend/services/finance/，卷 12 的 32 个模块全被卡住。
--      本迁移建出 finance 服务的第一批业务表，并让 12.1.1.1 应收单管理落在它们上面。
--
--   2. **12.1.1.1 应收单管理**（蓝图 12-finance.md）：
--      「管理应收账款：来源（平台结算、平台代扣、B2B 客户账期、其他）、
--        应收对象、金额与币种、到期日、账龄、明细核销状态。」
--      输出：「应收单 + 明细行 + 账龄分布；生成催收任务。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个服务**刻意不做**什么（卷 12 卷首「不做」原文）
-- ---------------------------------------------------------------------------
--
--   * **不把订单状态当资金流水（未发货订单不等于收入）**。
--     ⇒ 本文件里**没有任何一张表**会因为「订单存在 / 已下单」而长出应收行。
--     应收行只有一个产生路径：要么是**发货确认事实**（经 trade 的授权 API 读到的
--     9.7.1.1 账期应收），要么是**人工录入**（人明确说「这笔要收」）。
--     「什么业务确认为应收」由租户自己配置（finance_receivable_policy），
--     不是由订单状态推导出来的 —— 卷 12 卷首写死「不自己定会计政策」。
--
--   * **不替用户决定成本算法**（先进先出 / 移动加权 / 批次）：本文件里没有成本表。
--     12.4.1.1 计价方法配置交付时自带。
--
--   * **不提前实现 12.1.1.2 账龄 / 12.1.1.3 坏账核销 / 12.2.1.2 核销**：
--     它们各自的字段与状态在本文件里**留好了位置**（见下），但写入路径不开放。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_receivable_policy —— 应收确认政策（**租户自己维护**，不是系统推导）
--   finance_receivable        —— 应收单头
--   finance_receivable_line   —— 应收明细行（核销必须精确到行）
--   finance_collection_task   —— 催收任务（12.1.1.1 的输出之一）
--   finance_receivable_event  —— 单据事件流（append-only 举证凭据）
--
--   五张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 关键约束怎么落到数据层（卷 12 卷首「关键约束」逐条）
-- ---------------------------------------------------------------------------
--
--   * 「金额一律 BigDecimal + NUMERIC，**禁止浮点**」
--     ⇒ amount / unit_price numeric(24,4)，quantity numeric(19,6)，汇率 numeric(19,8)。
--       本仓没有一处 double / float。
--
--   * 「**汇率快照**必须留在单据上，今天汇率变了不能让上月报表变」
--     ⇒ exchange_rate / base_currency 是**单据上的两列快照**，建单时写死；
--       base_amount 是**生成列**（round(amount * exchange_rate, 4)），
--       所以「金额改了但本位币金额没改」这种漂移在数据层根本写不出来，
--       而报表读的是单据上的快照，**不按今天的汇率重算**。
--       exchange_rate 为 NULL ⇒ base_amount 恒为 NULL（**不是 0**）：
--       「没折算」与「折算了 0 块」必须能被区分开。
--
--   * 「凭证过账后**不可改行**，只能红冲」
--     ⇒ 应收单**确认之后行不可改**：明细行的金额、数量、单价、描述全部冻结
--       （guard_finance_receivable_line_frozen）；之后只有两种合法变化：
--       ① **核销**（12.2.1.2 写 settled_amount —— 本模块不提供该写端点），
--       ② **作废**（红冲的等价物：status → VOID，终态，整单冻结）。
--
--   * 「平台账单**不强制每行都有订单**（佣金、罚款、调整类无订单行）」
--     ⇒ finance_receivable_line.order_no **可空**，source_reference 可空。
--       12.3.1.2 交易明细交付后直接用同一张明细表，不需要改结构。
--
-- ---------------------------------------------------------------------------
-- 3. 为什么「单头金额」是**派生**的而不是录入的
-- ---------------------------------------------------------------------------
--
--   header.amount 由触发器 guard_finance_receivable_amount 从明细行求和得出。
--   理由：应收单的金额与明细合计**永远不可能对不上** ——
--   「单头 100、明细合计 98.5」这种错误在应用层算错时如果没人兜住，
--   会一路活到核销那天才被发现，而那时已经做完了对外承诺。
--
--   同理 header.settled_amount 与 header.status 也由明细行派生
--   （guard_finance_receivable_settlement）：
--   核销状态**只有一个出处** —— 明细行的 settled_amount。
--   12.2.1.2 交付时只需要写明细行的核销金额，单头状态自动跟上，
--   不会出现「单头说已核销、明细还说未核销」。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_receivable_policy —— 应收确认政策
-- ---------------------------------------------------------------------------
--
-- **这张表是「不自己定会计政策」这条红线的物化形态。**
--
-- 一行 = 一个应收来源（source）。两列决定「这个来源能不能产生应收」：
--   enabled   —— 开关。关掉这个来源 ⇒ 这个来源**一张应收都产生不了**。
--   due_days  —— 默认账期天数（0 = 确认即到期）。
--
-- 刻意**不预置任何一行**：预置 enabled=true 就等于系统替用户选了会计政策。
-- 租户第一次用某个来源时自己建这一行。
--
-- owner_module 记的是「这个来源的上游归谁」：
--   PLATFORM_SETTLEMENT / PLATFORM_WITHHOLDING 的上游是 12.3.1 结算（**未交付**），
--   所以它们**当前不可自动产生** —— 表里能写这两个值，但服务层拒绝，
--   并且能力声明会如实报出 ownerModule。
CREATE TABLE finance_receivable_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 与 finance_receivable.source 同一词表（DB 层 CHECK 见下）。
  source text NOT NULL
    CHECK(source IN ('PLATFORM_SETTLEMENT','PLATFORM_WITHHOLDING','B2B_CREDIT_TERM','MANUAL','OTHER')),
  enabled boolean NOT NULL DEFAULT false,
  -- 0 = 确认即到期；上限 365 天。
  due_days int NOT NULL DEFAULT 0 CHECK(due_days >= 0 AND due_days <= 365),
  note varchar(500) NOT NULL DEFAULT '',
  -- 上游归属模块（如 '12.3.1.1'）。**空串 = 上游已在本服务内闭环**。
  owner_module varchar(16) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个来源只有一份政策：政策不是「版本化」的，改了就是改了。
  UNIQUE(tenant_id, source)
);

-- ---------------------------------------------------------------------------
-- 2. finance_receivable —— 应收单头
-- ---------------------------------------------------------------------------
CREATE TABLE finance_receivable (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receivable_no varchar(48) NOT NULL,
  -- 来源：与 policy.source 同一词表。
  source text NOT NULL
    CHECK(source IN ('PLATFORM_SETTLEMENT','PLATFORM_WITHHOLDING','B2B_CREDIT_TERM','MANUAL','OTHER')),
  -- 来源系统：manual / trade。与 05 价目的 projection 手法一致（来源 + 采集时刻）。
  source_system varchar(32) NOT NULL DEFAULT 'manual',
  -- **上游单号**（如 trade 的 b2b_receivable.receivable_no）。
  -- 与 source 组成幂等键：同一笔上游事实重复导入只会命中同一条，不会长出第二张单。
  source_reference varchar(128),
  -- 应收对象。
  counterparty_type text NOT NULL
    CHECK(counterparty_type IN ('PLATFORM','CUSTOMER','OTHER')),
  counterparty_ref uuid,
  counterparty_name varchar(160) NOT NULL DEFAULT '',
  shop_id uuid,
  shop_name varchar(120) NOT NULL DEFAULT '',
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 单头金额：**由明细行派生**（见触发器），不是录进来的。
  amount numeric(24,4) NOT NULL DEFAULT 0 CHECK(amount >= 0),
  -- 汇率快照：建单时写死，之后不可改（见 guard_finance_receivable_frozen）。
  exchange_rate numeric(19,8) CHECK(exchange_rate IS NULL OR exchange_rate > 0),
  base_currency char(3) CHECK(base_currency IS NULL OR base_currency ~ '^[A-Z]{3}$'),
  -- 本位币金额：**生成列** ⇒ 永远等于 round(amount * exchange_rate, 4)，
  -- 不可能与快照汇率漂移。没有汇率时恒为 NULL（「未折算」≠「折算了 0」）。
  base_amount numeric(24,4) GENERATED ALWAYS AS (round(amount * exchange_rate, 4)) STORED,
  -- 已核销金额：**派生**（明细行 settled_amount 之和）。
  settled_amount numeric(24,4) NOT NULL DEFAULT 0
    CHECK(settled_amount >= 0 AND settled_amount <= amount),
  -- DRAFT → OPEN → PARTIALLY_SETTLED → SETTLED ；任意非终态 → VOID。
  -- SETTLED 与 VOID 是**终态**：整单冻结。
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','OPEN','PARTIALLY_SETTLED','SETTLED','VOID')),
  -- 到期日：确认日 + 政策账期（或上游自带账期）。**date 而不是 timestamptz**：
  -- 账期是日历天数，不是某一瞬间。
  due_date date NOT NULL,
  -- 应收确认时刻（发货确认时刻 / 人工录入时刻）。
  confirmed_at timestamptz NOT NULL,
  -- 「争议中」：跨境应收最大的风险是平台争议冻结。
  -- 争议事实来自 11.4.1.1 纠纷（trade 服务），**当前未自动联动**：
  -- 本模块只记录**人工标注**的状态，并在能力声明里写明上游归属。
  dispute_flag boolean NOT NULL DEFAULT false,
  dispute_note varchar(500) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, receivable_no),
  -- 幂等键：同一来源 + 同一上游单号只允许一张单。
  -- source_reference 为 NULL 时（人工录入）Postgres 视 NULL 互不相等 ⇒ 不受影响。
  UNIQUE(tenant_id, source, source_reference),
  -- 有本位币必有汇率，有汇率必有本位币：两个快照必须成对出现。
  CHECK((exchange_rate IS NULL) = (base_currency IS NULL)),
  -- 已核销金额不得超过单头金额。
  CHECK(settled_amount <= amount)
);

-- ---------------------------------------------------------------------------
-- 3. finance_receivable_line —— 应收明细行
-- ---------------------------------------------------------------------------
--
-- 「核销必须精确到明细行」（卷 12 核销那条加粗句）：所以核销状态落在**行**上。
--
-- order_no **可空**：平台账单里的佣金 / 罚款 / 调整天然没有订单行，
-- 硬要匹配会产生大量虚假异常（卷 12 卷首写死「不强制每行都有订单」）。
CREATE TABLE finance_receivable_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receivable_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),
  description varchar(200) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  order_no varchar(64),
  -- 上游明细引用（发货单号 / 平台流水号 / 结算行号）。可空。
  source_reference varchar(128),
  quantity numeric(19,6) CHECK(quantity IS NULL OR quantity > 0),
  unit_price numeric(24,4) CHECK(unit_price IS NULL OR unit_price >= 0),
  amount numeric(24,4) NOT NULL CHECK(amount > 0),
  -- 行级已核销金额。**12.2.1.2 核销交付后由它写**；本模块不提供写端点。
  settled_amount numeric(24,4) NOT NULL DEFAULT 0
    CHECK(settled_amount >= 0 AND settled_amount <= amount),
  -- 行级核销状态：**生成列**，由 settled_amount 与 amount 派生 ⇒ 不可能与金额打架。
  settlement_status text GENERATED ALWAYS AS (
    CASE
      WHEN settled_amount = 0 THEN 'OPEN'
      WHEN settled_amount < amount THEN 'PARTIALLY_SETTLED'
      ELSE 'SETTLED'
    END
  ) STORED,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, receivable_id, line_no),
  FOREIGN KEY(tenant_id, receivable_id) REFERENCES finance_receivable(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 4. finance_collection_task —— 催收任务
-- ---------------------------------------------------------------------------
--
-- 12.1.1.1 的输出之一：「生成催收任务」。
-- 级别三档：REMINDER（到期前提醒）/ OVERDUE（已逾期）/ ESCALATION（升级处理）。
CREATE TABLE finance_collection_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receivable_id uuid NOT NULL,
  task_no varchar(48) NOT NULL,
  level text NOT NULL CHECK(level IN ('REMINDER','OVERDUE','ESCALATION')),
  due_date date NOT NULL,
  status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','DONE','CANCELLED')),
  assignee_name varchar(120) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, task_no),
  -- 同一张应收同一级别只允许一条任务：重复催收是同一次催收。
  UNIQUE(tenant_id, receivable_id, level),
  -- 已完成 / 已取消的任务必须写完成时刻，未完成的必须没有。
  CHECK((status = 'OPEN') = (completed_at IS NULL)),
  FOREIGN KEY(tenant_id, receivable_id) REFERENCES finance_receivable(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 5. finance_receivable_event —— 单据事件流（append-only 举证凭据）
-- ---------------------------------------------------------------------------
--
-- 与 trade 的 b2b_order_event 同一手法：事件流是「当时发生了什么」的**唯一**出处，
-- 运行角色的 UPDATE / DELETE 已收回 —— 事件只能追加。
CREATE TABLE finance_receivable_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receivable_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'CREATED','CONFIRMED','IMPORTED','DISPUTE_FLAGGED','DISPUTE_CLEARED',
    'VOIDED','COLLECTION_TASK_CREATED','COLLECTION_TASK_CLOSED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, receivable_id) REFERENCES finance_receivable(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 单头金额 / 已核销金额 / 状态：**全部由明细行派生**。
--
--     应用层算错时这三列不会跟着错 —— 它们没有第二个写入口。
--     单头只有一个状态列到底；核销状态**只有一个出处**（明细行的 settled_amount）。
--
--     ⚠️ 触发器要能在「单头已被级联删除」时安静退出：明细行 ON DELETE CASCADE
--     会反过来打到这个触发器，那时单头已经不存在了。
CREATE OR REPLACE FUNCTION guard_finance_receivable_amount() RETURNS trigger AS $$
DECLARE
  head finance_receivable%ROWTYPE;
  line_sum numeric(24,4);
  settled_sum numeric(24,4);
  next_status text;
BEGIN
  SELECT * INTO head FROM finance_receivable
   WHERE tenant_id = NEW.tenant_id AND id = NEW.receivable_id
   FOR UPDATE;
  -- 单头已不存在（级联删除中）：无事可做。
  IF NOT FOUND THEN RETURN NULL; END IF;

  SELECT coalesce(sum(amount), 0), coalesce(sum(settled_amount), 0)
    INTO line_sum, settled_sum
    FROM finance_receivable_line
   WHERE tenant_id = NEW.tenant_id AND receivable_id = NEW.receivable_id;

  -- DRAFT 与 VOID 的状态由**服务层**决定（建单 / 作废），不由行派生：
  -- 草稿就是草稿（还没确认），作废就是作废（终态，不会被行改回来）。
  IF head.status = 'DRAFT' THEN
    IF head.amount IS DISTINCT FROM line_sum THEN
      UPDATE finance_receivable
         SET amount = line_sum, updated_at = now(), version = version + 1
       WHERE tenant_id = head.tenant_id AND id = head.id;
    END IF;
    RETURN NULL;
  END IF;

  IF head.status = 'VOID' THEN
    IF head.amount IS DISTINCT FROM line_sum OR head.settled_amount IS DISTINCT FROM settled_sum THEN
      UPDATE finance_receivable
         SET amount = line_sum, settled_amount = settled_sum, updated_at = now()
       WHERE tenant_id = head.tenant_id AND id = head.id;
    END IF;
    RETURN NULL;
  END IF;

  -- 核销状态由行的核销金额派生：
  --   全部核销 ⇒ SETTLED；部分核销 ⇒ PARTIALLY_SETTLED；一分未核销 ⇒ OPEN。
  IF settled_sum = 0 THEN
    next_status := 'OPEN';
  ELSIF settled_sum < line_sum THEN
    next_status := 'PARTIALLY_SETTLED';
  ELSE
    next_status := 'SETTLED';
  END IF;

  IF head.amount IS DISTINCT FROM line_sum
     OR head.settled_amount IS DISTINCT FROM settled_sum
     OR head.status IS DISTINCT FROM next_status THEN
    UPDATE finance_receivable
       SET amount = line_sum,
           settled_amount = settled_sum,
           status = next_status,
           updated_at = now(),
           version = version + 1
     WHERE tenant_id = head.tenant_id AND id = head.id;
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_receivable_line_amount
  AFTER INSERT OR UPDATE OR DELETE ON finance_receivable_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_receivable_amount();

-- (b) 单头的**快照字段**在确认之后冻结。
--
--     冻结清单：币种、汇率、本位币、确认时刻、来源、来源单号、应收对象、店铺、到期日。
--     理由：卷 12 卷首要「今天汇率变了不能让上月报表变」——
--     允许事后改汇率快照，等于给报表留了一个可以改写历史数字的后门。
--
--     金额（amount）不在冻结清单里：它由触发器从明细行派生，
--     而明细行在确认后同样被 (c) 冻结 ⇒ 确认之后金额实际上也改不动了。
--
--     作废（VOID）与已核销（SETTLED）是**终态**：整单冻结，一行都不许改。
CREATE OR REPLACE FUNCTION guard_finance_receivable_frozen() RETURNS trigger AS $$
BEGIN
  IF OLD.status IN ('VOID', 'SETTLED') THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '应收单 % 已是终态（%），不可再改：只能新建一张冲销单', OLD.receivable_no, OLD.status
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
       OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.shop_name IS DISTINCT FROM OLD.shop_name
       OR NEW.due_date IS DISTINCT FROM OLD.due_date THEN
      RAISE EXCEPTION '应收单 % 已确认，币种/汇率/到期日/来源/应收对象等快照字段不可再改', OLD.receivable_no
        USING ERRCODE = 'check_violation';
    END IF;
    -- 确认之后状态只允许往前走（核销由 12.2.1.2 写），不许退回草稿。
    IF NEW.status = 'DRAFT' THEN
      RAISE EXCEPTION '应收单 % 已确认，不可退回草稿', OLD.receivable_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_receivable_frozen
  BEFORE UPDATE ON finance_receivable
  FOR EACH ROW EXECUTE FUNCTION guard_finance_receivable_frozen();

-- (c) 明细行在单头确认之后**不可改**（卷 12 卷首「过账后不可改行」在本模块的落法）。
--
--     只放行 settled_amount（核销金额）—— 那是 12.2.1.2 的写入所有权。
--     单头被作废（VOID）后，行彻底冻结。
CREATE OR REPLACE FUNCTION guard_finance_receivable_line_frozen() RETURNS trigger AS $$
DECLARE head_status text;
BEGIN
  SELECT status INTO head_status FROM finance_receivable
   WHERE tenant_id = NEW.tenant_id AND id = NEW.receivable_id;
  IF head_status IS NULL THEN RETURN NEW; END IF;

  IF head_status = 'VOID' THEN
    RAISE EXCEPTION '应收单已作废，明细行不可再改' USING ERRCODE = 'check_violation';
  END IF;

  IF head_status <> 'DRAFT' THEN
    IF TG_OP = 'INSERT' THEN
      RAISE EXCEPTION '应收单已确认，不可再增加明细行' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.quantity IS DISTINCT FROM OLD.quantity
       OR NEW.unit_price IS DISTINCT FROM OLD.unit_price
       OR NEW.description IS DISTINCT FROM OLD.description
       OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
       OR NEW.order_no IS DISTINCT FROM OLD.order_no
       OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
       OR NEW.line_no IS DISTINCT FROM OLD.line_no THEN
      RAISE EXCEPTION '应收单已确认，明细行的金额/数量/单价/描述不可再改（只能核销或作废）'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_receivable_line_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON finance_receivable_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_receivable_line_frozen();

-- (d) 明细行金额必须为正。
--
--     「一行 0 块的应收」要么是录入错误，要么是想用明细行绕过「至少一行」的校验。
--     两种都不该进得来。允许**没有明细行**的应收单吗？不允许 ——
--     服务层在确认时要求至少一行（guard 见下），本 CHECK 保证进来的一定有内容。
CREATE OR REPLACE FUNCTION guard_finance_receivable_line_positive() RETURNS trigger AS $$
BEGIN
  IF NEW.amount <= 0 THEN
    RAISE EXCEPTION '应收明细行金额必须大于 0（收到 %）', NEW.amount
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_receivable_line_positive
  BEFORE INSERT OR UPDATE ON finance_receivable_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_receivable_line_positive();

-- (e) 确认（OPEN）时必须有明细行，且单头金额必须等于明细合计。
--
--     金额合计由触发器 (a) 保证；这里保证的是「**至少有一行**」与「不是 0 元单」。
--     0 元应收单在账龄分布里是纯噪声（它永远不算逾期，但会让「张数」虚高）。
CREATE OR REPLACE FUNCTION guard_finance_receivable_confirm() RETURNS trigger AS $$
DECLARE line_count int; line_sum numeric(24,4);
BEGIN
  IF NEW.status = 'OPEN' AND OLD.status = 'DRAFT' THEN
    SELECT count(*), coalesce(sum(amount), 0) INTO line_count, line_sum
      FROM finance_receivable_line
     WHERE tenant_id = NEW.tenant_id AND receivable_id = NEW.id;
    IF line_count = 0 THEN
      RAISE EXCEPTION '应收单 % 没有任何明细行，不可确认', NEW.receivable_no
        USING ERRCODE = 'check_violation';
    END IF;
    IF line_sum <= 0 THEN
      RAISE EXCEPTION '应收单 % 明细合计必须大于 0', NEW.receivable_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_receivable_confirm
  BEFORE UPDATE ON finance_receivable
  FOR EACH ROW EXECUTE FUNCTION guard_finance_receivable_confirm();

-- (f) 新建应收单**必须**先是草稿。
--
--     为什么：确认（DRAFT→OPEN）那一步会校验「至少一条明细行」与「明细合计大于 0」。
--     如果允许直接 INSERT 一张 OPEN 的单，这条校验就被绕过去了 ——
--     「一张 0 元、零明细的应收单」就会出现在账龄分布里（它永远不算逾期，
--     但会让「张数」虚高）。
--     钉住入口 ⇒ 每一张单都必须走过「建单 → 落明细 → 确认」这条路。
CREATE OR REPLACE FUNCTION guard_finance_receivable_insert_draft() RETURNS trigger AS $$
BEGIN
  IF NEW.status <> 'DRAFT' THEN
    RAISE EXCEPTION '新建应收单必须先是草稿：确认要走 DRAFT→OPEN（确认时才会校验明细行与合计）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_receivable_insert_draft
  BEFORE INSERT ON finance_receivable
  FOR EACH ROW EXECUTE FUNCTION guard_finance_receivable_insert_draft();

-- (g) 政策改动的留痕由服务层写事件流；这里只保证政策行**存在且唯一**
--     （UNIQUE(tenant_id, source) 已在表定义里）。

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：到期日 + id（资金计划按到期日排，不是按创建时间排）。
CREATE INDEX finance_receivable_by_due
  ON finance_receivable(tenant_id, status, due_date, id);
-- 按来源查（导入幂等键 + 「这个来源产生了多少」）。
CREATE INDEX finance_receivable_by_source
  ON finance_receivable(tenant_id, source, source_reference);
-- 按应收对象查（B2B 客户 / 平台）。
CREATE INDEX finance_receivable_by_counterparty
  ON finance_receivable(tenant_id, counterparty_ref, status, due_date);
-- 争议中筛选（12.1.1.2 账龄要单列「争议中」金额）。
CREATE INDEX finance_receivable_by_dispute
  ON finance_receivable(tenant_id, dispute_flag, due_date)
  WHERE dispute_flag;
-- 明细行按单取。
CREATE INDEX finance_receivable_line_by_receivable
  ON finance_receivable_line(tenant_id, receivable_id, line_no);
-- 催收任务按状态 + 到期日排。
CREATE INDEX finance_collection_task_by_status
  ON finance_collection_task(tenant_id, status, due_date, id);
CREATE INDEX finance_collection_task_by_receivable
  ON finance_collection_task(tenant_id, receivable_id);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_receivable_event_by_receivable
  ON finance_receivable_event(tenant_id, receivable_id, occurred_at, id);
-- 政策按来源查（唯一索引已覆盖，这里只补 updated_at 排序用不到，略）。

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_receivable_policy','finance_receivable','finance_receivable_line',
    'finance_collection_task','finance_receivable_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_receivable_policy, finance_receivable, finance_receivable_line,
  finance_collection_task, finance_receivable_event TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_receivable_policy, finance_receivable, finance_receivable_line,
  finance_collection_task, finance_receivable_event TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：事件流是**举证凭据**，只能追加。
-- 收回之后，即使服务层写错（误 UPDATE / 误 DELETE）也改不动已经记下的历史。
REVOKE UPDATE,DELETE ON finance_receivable_event FROM finance_app;

-- 催收任务不需要物理删除：取消走 status='CANCELLED'，删掉就没有痕迹了。
REVOKE DELETE ON finance_collection_task FROM finance_app;

-- ===========================================================================
-- 第五节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.1.1.2 应收账龄（未开发）**：账龄分布已在本模块作为「输出」提供
--     （GET /finance/receivables/aging），但**按店铺 / 客户 / 币种三个维度下钻**
--     与「逾期清单」属 12.1.1.2。12.1.1.2 交付后应读同一张 finance_receivable，
--     不需要改结构。争议中金额已由 dispute_flag 单列。
--
--   * **12.1.1.3 坏账与核销（未开发）**：核销写入路径是**行级 settled_amount**
--     （本文件的触发器 (a) 会把单头状态与已核销金额一起派生好）。
--     12.1.1.3 交付后**不需要**在本表上再加列；坏账核销走「作废 + 新单」或
--     直接写行级 settled_amount，两条路都不会撞本文件的冻结守卫。
--
--   * **12.2.1.2 核销（未开发）**：同上。核销必须精确到**明细行**
--     （卷 12 卷首「核销必须精确到明细行」），所以核销金额落在行上。
--
--   * **12.3.1 结算（未开发）**：平台结算与平台代扣两个来源**当前不可自动产生**
--     —— 上游结算单还不存在。字段（source / source_system / source_reference）
--     已按「同一张表承接上游」的方式留好，12.3.1 落地时经**授权 API / event**
--     写入，不跨库不跨服务 SQL。明细行的 order_no 可空也是为它准备的：
--     佣金 / 罚款 / 调整类行天然没有订单。
--
--   * **12.5.2.1 凭证（未开发）**：应收单是凭证的**来源**而不是凭证本身。
--     凭证过账后不可改行这条约束在总账落地时由凭证侧自己保证；
--     本模块只保证「应收确认后行不可改」这一半。
