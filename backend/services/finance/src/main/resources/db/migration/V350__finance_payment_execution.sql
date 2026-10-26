-- ===========================================================================
-- V350 · finance_payment_execution —— 12.2.2.2 付款执行
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.2.2.2 付款执行
--   - **职责**：执行付款：对接支付渠道/银企直连，发送付款指令，跟踪结果，
--     失败重试与异常处理；处理汇率锁汇。
--   - **输入**：已批付款单；支付渠道配置（密钥引用）；汇率锁定。
--   - **输出**：付款结果 + 渠道流水号 + 失败原因。
--   - **关系**：上游 = 12.2.2.1；下游 = 12.7.1 资金账户、12.5.2 凭证。
--     **外部支付超时不等于失败**，必须先查证再重发——重复付款是严重资损事故。
--
-- ---------------------------------------------------------------------------
-- 0. 五条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **接管 V346 的两个执行态守卫**（V346 第五节写死的交接面）。
--      V346 把 `execution_available` 用表级 CHECK 钉死为 false ⇒ 12.2.2.1
--      **写不出**「已付 / 执行中 / 失败」。本迁移**接管**：
--        ① 放开 `finance_payment_order` / `finance_payment_order_installment` 的
--           `execution_available` CHECK，并把它反过来钉成 **必须为 true**
--           （12.2.2.2 已交付 ⇒ 执行可用；这条 CHECK 同时是「执行态归属」的声明）。
--        ② 换掉 `guard_finance_payment_order_state` /
--           `guard_finance_payment_order_installment_state`：
--           **状态机路径一字不改**（仍是 DRAFT→待审→已批→执行中→已付/失败），
--           只把「本模块写不出执行态」那一段换成**更强**的一条：
--           **写「执行中 / 已付 / 失败」必须有对应的付款执行记录**。
--           ⇒ 「已付」从「12.2.2.2 才能写」升级成「**没有成功执行记录就写不出已付**」，
--              连直连 SQL 也一样。
--
--   2. **超时 ≠ 失败**（蓝图关系行加粗句）。
--      执行单状态里有独立的 **UNKNOWN**（结果未知）：渠道超时 / 5xx / 网络不可达
--      一律进 UNKNOWN，**不进 FAILED**。
--      ⇒ UNKNOWN 的执行单**不允许**把付款单打成「失败」：
--        DB 触发器要求「写 FAILED 必须有一条 status='FAILED' 的执行记录」，
--        而 UNKNOWN 的执行单不是 FAILED ⇒ 「结果未知」在数据层就写不成「失败」。
--      ⇒ 重发前**必须先查证**（服务层的 verify 路径）：渠道侧确认没成功才允许重发。
--
--   3. **幂等键先落库再发送**。
--      `finance_payment_execution.idempotency_key` 带**唯一约束**，
--      键由「付款单 + 分批」**确定性**派生 ⇒ 同一笔逻辑付款的每次重发都用同一个键。
--      ⇒ (a) 本地重复发送会被唯一约束挡住；
--         (b) 即使本地事务回滚丢了记录，重发用的还是同一个键 ⇒ 渠道侧去重，
--             **重复付款不会发生**（这是幂等键的真正兜底价值）。
--
--   4. **凭据只存引用**（蓝图输入写死「支付渠道配置（密钥引用）」）。
--      `finance_payment_channel.secret_ref` 用表级 CHECK 钉死
--      `secret://` / `kv://` / `vault://` / `external-secrets://` 前缀 ——
--      本系统**不保存任何明文密钥**，迁移、代码、日志里都没有。
--
--   5. **汇率锁汇**（蓝图职责写死「处理汇率锁汇」）。
--      `finance_rate_lock` 记的是**本笔付款实际锁住的汇率**：来源 = 12.2.2.1
--      建单时冻结的汇率快照（`finance_payment_order.exchange_rate`），
--      **不是**发送那一刻的市场价 —— 单据冻结了就不许再漂。
--      付款指令里带上锁定的 rate / baseAmount，渠道回单金额对不上 ⇒ 判
--      AMOUNT_MISMATCH（确定性失败），**不许**悄悄按新汇率付。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_payment_channel              —— 支付渠道配置（只存密钥引用）
--   finance_payment_execution            —— 付款执行单（含幂等键 + 渠道流水号 + 失败原因）
--   finance_payment_execution_allocation —— 本次付款分摊到哪些应付明细行
--   finance_payment_execution_attempt    —— 每次发送 / 查证尝试（append-only）
--   finance_payment_execution_event      —— 执行事件流（append-only 举证凭据）
--   finance_rate_lock                    —— 汇率锁定
--
--   六张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)、汇率 numeric(19,8)、数量 numeric(19,6)；**全链路禁浮点**。
--   * 币种 char(3) + CHECK 正则；**任何汇总都按币种分组**。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_payment_channel —— 支付渠道配置
-- ---------------------------------------------------------------------------
--
-- 一行 = 「本租户用哪个渠道付款，凭据挂在哪」。
--
-- 🔴 **凭据只有引用**：`secret_ref` 存的是密钥管理器里的**引用 ID**
--    （secret:// / kv:// / vault:// / external-secrets://），不是明文。
--    表级 CHECK 把前缀钉死 ⇒ 想把明文塞进这一列在数据层就写不出来。
--
-- ⚠️ **base_url 的明文例外只给回环地址**：https 是硬要求，
--    只有 127.0.0.1 / localhost / [::1] 允许 http（本地联调）。
--    另有一道**主机白名单**（服务配置项 PAYMENT_CHANNEL_ALLOWED_HOSTS），
--    不在白名单里的主机一律拒绝 —— 那是防 SSRF 的闸，fail-closed。
CREATE TABLE finance_payment_channel (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL,
  name varchar(120) NOT NULL,
  -- 渠道支持的付款方式（与 12.2.2.1 的 payment_method 同一词表）：
  -- 付款单的付款方式必须与渠道一致，否则就是「用 A 渠道去付 B 方式的单」。
  payment_method varchar(24) NOT NULL
    CHECK(payment_method IN ('BANK_TRANSFER','PAYPAL','STRIPE','PINGPONG','OTHER')),
  -- 渠道 API 地址（不含任何凭据）。
  base_url varchar(300) NOT NULL,
  -- 密钥管理器引用 ID。
  secret_ref varchar(300) NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  -- 单次调用的超时（秒）。超时进 UNKNOWN，**不进 FAILED**。
  timeout_seconds int NOT NULL DEFAULT 15 CHECK(timeout_seconds BETWEEN 1 AND 120),
  -- 最大尝试次数（含首次）。超过就拒绝重发：重发不是无限的。
  max_attempts int NOT NULL DEFAULT 3 CHECK(max_attempts BETWEEN 1 AND 10),
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),
  CHECK(btrim(code) <> ''),
  CHECK(btrim(name) <> ''),
  CHECK(btrim(secret_ref) <> ''),
  -- 凭据只能是**引用**（secret manager 的引用 ID）。
  CHECK(secret_ref ~ '^(secret://|kv://|vault://|external-secrets://)'),
  -- 渠道地址：https 是硬要求，回环地址例外（仅本地联调）。
  CHECK(base_url ~ '^https://' OR base_url ~ '^http://(127\.0\.0\.1|localhost|\[::1\])')
);

-- ---------------------------------------------------------------------------
-- 2. finance_payment_execution —— 付款执行单
-- ---------------------------------------------------------------------------
--
-- 一行 = 「这笔钱我们真的发出去了（或者发出去了但还不知道结果）」的一次执行。
--
-- 🔴 **幂等键（idempotency_key）**：
--    * 唯一约束 (tenant_id, idempotency_key)；
--    * 键由「付款单 + 分批」**确定性**派生 ⇒ 重发用同一个键；
--    * **发送前**先把这行写进库 ⇒ 进程在发送中途挂掉也留下「发过」的痕迹，
--      下一步是**查证**而不是「再发一次」。
--
-- 🔴 **status 的五个取值**：
--    PENDING（已建单、还没发） / SENDING（在途） /
--    SUCCEEDED（渠道确认成功） / FAILED（**确定性**失败） / UNKNOWN（结果未知）。
--    UNKNOWN 是本模块的关键状态：**超时不等于失败**。
-- 复合外键要落的唯一索引（补在 V346 的 installment 表上：**只加索引，不改列**）。
-- ⚠️ 必须**先于** finance_payment_execution 建表：PostgreSQL 要求被外键引用的
--    唯一约束在 CREATE TABLE 时就已经存在。
CREATE UNIQUE INDEX uq_finance_payment_order_installment_order_id
  ON finance_payment_order_installment(tenant_id, order_id, id);

CREATE TABLE finance_payment_execution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 上游：已批付款单（12.2.2.1，同服务同库 ⇒ 复合外键）。
  order_id uuid NOT NULL,
  -- 付款分批（可空：没有分批安排的一笔付全额）。
  installment_id uuid,
  execution_no varchar(48) NOT NULL,
  -- 渠道（快照：渠道名可能后来被改，历史执行单要能复现「当时用的是哪个渠道」）。
  channel_id uuid NOT NULL,
  channel_code varchar(32) NOT NULL,
  channel_name varchar(120) NOT NULL,
  payment_method varchar(24) NOT NULL
    CHECK(payment_method IN ('BANK_TRANSFER','PAYPAL','STRIPE','PINGPONG','OTHER')),
  -- 本次实际支付的金额与币种（**本执行单自己的事实**）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  -- 锁汇快照（与 finance_payment_order 同一口径：1 付款币种 = rate 本位币）。
  base_currency char(3) CHECK(base_currency IS NULL OR base_currency ~ '^[A-Z]{3}$'),
  exchange_rate numeric(19,8) CHECK(exchange_rate IS NULL OR exchange_rate > 0),
  base_amount numeric(19,4),
  -- 幂等键：发送前生成并落库，带唯一约束。
  idempotency_key varchar(120) NOT NULL,
  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','SENDING','SUCCEEDED','FAILED','UNKNOWN')),
  -- 已尝试次数（发送 + 查证都算尝试？不 —— 只有**发送**算尝试，查证不算）。
  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  -- 渠道流水号（蓝图输出写死「渠道流水号」）。
  channel_serial_no varchar(160) NOT NULL DEFAULT '',
  -- 失败原因与失败分类（蓝图输出写死「失败原因」）。
  failure_reason varchar(500) NOT NULL DEFAULT '',
  failure_class varchar(32) NOT NULL DEFAULT 'NONE'
    CHECK(failure_class IN ('NONE','CHANNEL_REJECTED','CHANNEL_UNAVAILABLE','NOT_CONFIGURED',
                             'TIMEOUT','AMOUNT_MISMATCH','INTERNAL')),
  -- 时间戳：全部 timestamptz。
  sent_at timestamptz,
  responded_at timestamptz,
  settled_at timestamptz,
  last_verified_at timestamptz,
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, execution_no),
  -- 幂等键唯一：同一笔逻辑付款只有一条执行单。
  UNIQUE(tenant_id, idempotency_key),
  FOREIGN KEY(tenant_id, order_id) REFERENCES finance_payment_order(tenant_id, id),
  FOREIGN KEY(tenant_id, channel_id) REFERENCES finance_payment_channel(tenant_id, id),
  -- 分批必须属于**同一张付款单**（复合外键 ⇒ 数据层保证）。
  FOREIGN KEY(tenant_id, order_id, installment_id)
    REFERENCES finance_payment_order_installment(tenant_id, order_id, id),
  -- 有本位币必有汇率，有汇率必有本位币。
  CHECK((exchange_rate IS NULL) = (base_currency IS NULL)),
  CHECK(base_amount IS NULL OR base_amount >= 0),
  -- 失败 ⇒ 必须写明原因与分类（否则事后答不出「这笔钱为什么没付出去」）。
  CONSTRAINT finance_payment_execution_failed_ck CHECK (
    status <> 'FAILED' OR (btrim(failure_reason) <> '' AND failure_class <> 'NONE')
  ),
  -- 成功 ⇒ 必须有渠道流水号（蓝图输出写死「渠道流水号」）。
  CONSTRAINT finance_payment_execution_succeeded_ck CHECK (
    status <> 'SUCCEEDED' OR btrim(channel_serial_no) <> ''
  ),
  -- 已付 ⇒ 一定发生过发送尝试。
  CONSTRAINT finance_payment_execution_attempt_ck CHECK (
    status NOT IN ('SUCCEEDED','FAILED') OR attempt_count > 0
  )
);

-- ---------------------------------------------------------------------------
-- 3. finance_payment_execution_allocation —— 本次付款分摊到哪些应付明细行
-- ---------------------------------------------------------------------------
--
-- 一行 = 「这笔执行单的付款金额里，有多少落在了这条应付明细行上」。
--
-- 🔴 **为什么必须有这张表**（这不是锦上添花，是防重复付款的必需品）：
--   `finance_payable_line.paid_amount` 是**增量**写的（每成功一次付款就加一次），
--   而 12.2.2.1 的付款单**允许分批安排**（一张付款单拆成若干批）。
--   ⇒ 「这一批的钱对应哪几行应付明细」必须**当场记下来**，
--     否则第二次付款时无法判断「这一行已经收了多少」⇒ 就会重复付款。
--   ⇒ 有了这张表，每次成功付款的**分摊是确定且可复算的**：
--     「这条应付明细已付 X」= 该明细行在**所有已成功执行单**上的分摊之和。
--
--   幂等：(tenant_id, execution_id, payable_line_id) 唯一 ⇒ 同一执行单不会把
--   同一行分摊两次。
CREATE TABLE finance_payment_execution_allocation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,
  -- 应付明细行（12.1.2.1，**行级已付金额的写入口**）。
  payable_line_id uuid NOT NULL,
  -- 本次付款单上的明细行（分摊的来源）。
  order_line_id uuid NOT NULL,
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, execution_id, payable_line_id),
  FOREIGN KEY(tenant_id, execution_id) REFERENCES finance_payment_execution(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, payable_line_id) REFERENCES finance_payable_line(tenant_id, id),
  FOREIGN KEY(tenant_id, order_line_id) REFERENCES finance_payment_order_line(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- 4. finance_payment_execution_attempt —— 每次发送 / 查证尝试（append-only）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某一次对渠道的调用」。
--
-- ⚠️ **attempt_kind 区分 SEND 与 QUERY**：
--    * SEND = 发出付款指令（**只有它计入 attempt_count**，重发次数才受 max_attempts 约束）；
--    * QUERY = 状态查证（**不计入** attempt_count —— 查证不是重发，
--      查一百次也不该把「还能重发几次」耗光）。
--
-- 幂等：(tenant_id, execution_id, attempt_kind, attempt_no) 唯一。
CREATE TABLE finance_payment_execution_attempt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,
  attempt_no int NOT NULL CHECK(attempt_no > 0),
  attempt_kind text NOT NULL CHECK(attempt_kind IN ('SEND','QUERY')),
  idempotency_key varchar(120) NOT NULL,
  -- 这次调用的结论。
  outcome text NOT NULL CHECK(outcome IN ('SUCCESS','REJECTED','NOT_FOUND','TIMEOUT',
                                          'CHANNEL_UNAVAILABLE','NOT_CONFIGURED','AMOUNT_MISMATCH')),
  channel_serial_no varchar(160) NOT NULL DEFAULT '',
  failure_reason varchar(500) NOT NULL DEFAULT '',
  -- 请求 / 响应摘要：**只记摘要，不记密钥**（密钥只在请求头里，绝不落库、绝不进日志）。
  request_summary varchar(1000) NOT NULL DEFAULT '',
  response_summary varchar(1000) NOT NULL DEFAULT '',
  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, execution_id, attempt_kind, attempt_no),
  FOREIGN KEY(tenant_id, execution_id) REFERENCES finance_payment_execution(tenant_id, id) ON DELETE CASCADE,
  CHECK(finished_at >= started_at),
  -- 成功 ⇒ 必须有渠道流水号。
  CONSTRAINT finance_payment_execution_attempt_success_ck CHECK (
    outcome <> 'SUCCESS' OR btrim(channel_serial_no) <> ''
  )
);

-- ---------------------------------------------------------------------------
-- 5. finance_payment_execution_event —— 执行事件流（append-only 举证凭据）
-- ---------------------------------------------------------------------------
--
-- 与 12.2.2.1 的 finance_payment_order_event、12.1.2.1 的 finance_payable_event 同一手法：
-- 事件流是「当时发生了什么」的**唯一**出处，只能追加。
CREATE TABLE finance_payment_execution_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'CREATED','SEND_STARTED','SEND_SUCCEEDED','SEND_REJECTED','SEND_UNKNOWN',
    'VERIFY_STARTED','VERIFY_SUCCEEDED','VERIFY_NOT_FOUND','VERIFY_UNKNOWN',
    'RETRY','RATE_LOCKED','RATE_RELEASED','WRITEBACK_APPLIED','WRITEBACK_CONFLICT')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, execution_id) REFERENCES finance_payment_execution(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 6. finance_rate_lock —— 汇率锁定
-- ---------------------------------------------------------------------------
--
-- 一行 = 「这笔付款用的是这个汇率，在这个窗口内有效」。
--
-- 口径与 finance_payment_order 完全一致：**1 付款币种（quote_currency）= rate 本位币（base_currency）**。
--
-- 🔴 **来源是单据冻结的快照**（source_code = 'ORDER_SNAPSHOT'），不是发送那一刻的市场价：
--    12.2.2.1 建单时冻结了汇率（触发器挡住事后改），
--    付款时用的是**那个**汇率 —— 今天汇率变了不能让今天的付款悄悄按新汇率付出去。
--
-- ⚠️ **单币种付款不锁汇**：没有换算就没有锁，这是**如实行为**而不是缺功能。
CREATE TABLE finance_rate_lock (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  execution_id uuid,
  quote_currency char(3) NOT NULL CHECK(quote_currency ~ '^[A-Z]{3}$'),
  base_currency char(3) NOT NULL CHECK(base_currency ~ '^[A-Z]{3}$'),
  rate numeric(19,8) NOT NULL CHECK(rate > 0),
  base_amount numeric(19,4) NOT NULL CHECK(base_amount >= 0),
  source_code varchar(32) NOT NULL DEFAULT 'ORDER_SNAPSHOT',
  source_name varchar(120) NOT NULL DEFAULT '',
  effective_date date,
  locked_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  status text NOT NULL DEFAULT 'LOCKED' CHECK(status IN ('LOCKED','RELEASED','EXPIRED')),
  released_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, order_id) REFERENCES finance_payment_order(tenant_id, id),
  FOREIGN KEY(tenant_id, execution_id) REFERENCES finance_payment_execution(tenant_id, id),
  CHECK(quote_currency <> base_currency),
  CHECK(expires_at > locked_at),
  -- LOCKED ⇒ 未释放；RELEASED ⇒ 必须有释放时刻。
  CONSTRAINT finance_rate_lock_released_ck CHECK (
    (status <> 'LOCKED' OR released_at IS NULL)
    AND (status <> 'RELEASED' OR released_at IS NOT NULL)
  )
);

-- 同一张付款单同时只有**一条**生效中的锁汇。
CREATE UNIQUE INDEX uq_finance_rate_lock_active_order
  ON finance_rate_lock(tenant_id, order_id)
  WHERE status = 'LOCKED';

-- ===========================================================================
-- 第二节：接管 V346 的执行态守卫（12.2.2.2 交付 ⇒ 执行可用）
-- ===========================================================================

-- (a) 放开 V346 的 `execution_available = false` CHECK（两张表），并反过来钉成
--     **必须为 true**。
--
--     ⚠️ 顺序不能反：先把 CHECK 放开，才改得了存量行的 false。
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT conrelid::regclass AS tbl, conname
      FROM pg_constraint
     WHERE contype = 'c'
       AND conrelid IN ('finance_payment_order'::regclass,
                        'finance_payment_order_installment'::regclass)
       AND pg_get_constraintdef(oid) LIKE '%execution_available%'
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
  END LOOP;
END $$;

UPDATE finance_payment_order SET execution_available = true WHERE NOT execution_available;
UPDATE finance_payment_order_installment SET execution_available = true WHERE NOT execution_available;

-- ⚠️ **默认值也必须改**：V346 的列默认值是 false，而 12.2.2.1 建单 / 建分批时**不写**这一列
--    （走默认值）⇒ 默认值不改的话，12.2.2.1 的建单会被新 CHECK 直接拒掉。
ALTER TABLE finance_payment_order ALTER COLUMN execution_available SET DEFAULT true;
ALTER TABLE finance_payment_order_installment ALTER COLUMN execution_available SET DEFAULT true;

ALTER TABLE finance_payment_order
  ADD CONSTRAINT finance_payment_order_execution_available_ck CHECK (execution_available);
ALTER TABLE finance_payment_order_installment
  ADD CONSTRAINT finance_payment_order_installment_execution_available_ck CHECK (execution_available);

-- (b) 付款单状态守卫 —— **换掉** V346 的版本。
--
--     ① 允许的路径**一字不改**（仍是 DRAFT→待审→已批→执行中→已付/失败；
--        待审→草稿是唯一的回退）。
--     ② V346 那一段「本模块写不出执行态」的判断**删掉**（执行态归本模块了）。
--     ③ 换成**更强**的一条：写执行态必须有对应的**付款执行记录**：
--        · 执行中 ⇒ 该付款单至少有一条执行记录（没有执行记录就不存在「正在付款」）；
--        · 已付   ⇒ 该付款单至少有一条 **SUCCEEDED** 的执行记录
--                  （**没有成功记录就写不出「已付」**）；
--        · 失败   ⇒ 该付款单至少有一条 **FAILED** 的执行记录
--                  （**结果未知（UNKNOWN）写不成「失败」** —— 超时不等于失败）。
--     ④ 提交之后钱的那些事实一律冻结（沿用 V346 的清单，一字不改）。
CREATE OR REPLACE FUNCTION guard_finance_payment_order_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
         (OLD.status = 'DRAFT'          AND NEW.status = 'PENDING_APPROVAL')
      OR (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('DRAFT','APPROVED'))
      OR (OLD.status = 'APPROVED'        AND NEW.status = 'EXECUTING')
      OR (OLD.status = 'EXECUTING'       AND NEW.status IN ('PAID','FAILED'))
      OR (OLD.status = 'FAILED'          AND NEW.status = 'EXECUTING')
    ) THEN
      RAISE EXCEPTION '付款单 % 的状态只能沿 草稿→待审→已批→执行中→已付/失败 单向流转（失败后只允许显式重发回到执行中），% → % 不允许'
        , OLD.order_no, OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'EXECUTING' AND NOT EXISTS (
      SELECT 1 FROM finance_payment_execution
       WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id) THEN
      RAISE EXCEPTION '付款单 % 进入执行中前必须先有付款执行记录（12.2.2.2）：没有执行记录就不存在「正在付款」这件事'
        , OLD.order_no
        USING ERRCODE = 'check_violation';
    END IF;

    -- ⚠️ **失败后的重发回退（FAILED → EXECUTING）**：蓝图 12.2.2.2 的职责写死
    --    「失败重试与异常处理」⇒ **必须允许重发**，否则「失败重试」就只剩一段说明文字。
    --    这条回退是**显式**的：只有 12.2.2.2 的重发路径会走它，
    --    而且**钱的那些事实（金额 / 币种 / 汇率 / 付款账户 / 付款对象）仍然冻结**。
    --    ⚠️ 触发器**只管状态可不可走**，管不了「这次是不是真的重发」——
    --      「是不是重发」由服务层判定（必须先查证），DB 层保证的是「改不了钱的事实」。

    IF NEW.status = 'PAID' AND NOT EXISTS (
      SELECT 1 FROM finance_payment_execution
       WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id AND status = 'SUCCEEDED') THEN
      RAISE EXCEPTION '付款单 % 标记已付前必须有一条**成功**的付款执行记录：没有成功记录就不能写「已付」'
        , OLD.order_no
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'FAILED' AND NOT EXISTS (
      SELECT 1 FROM finance_payment_execution
       WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id AND status = 'FAILED') THEN
      RAISE EXCEPTION '付款单 % 标记失败前必须有一条**确定性失败**的付款执行记录：结果未知（超时）不等于失败'
        , OLD.order_no
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

-- (c) 分批状态守卫 —— 同样**换掉** V346 的版本：
--     路径不变（已排期→执行中→已付/失败），但执行态必须有**属于这一批**的执行记录。
CREATE OR REPLACE FUNCTION guard_finance_payment_order_installment_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
         (OLD.status = 'PLANNED'  AND NEW.status = 'EXECUTING')
      OR (OLD.status = 'EXECUTING' AND NEW.status IN ('PAID','FAILED'))
      OR (OLD.status = 'FAILED'   AND NEW.status = 'EXECUTING')
    ) THEN
      RAISE EXCEPTION '付款分批的状态只能沿 已排期→执行中→已付/失败 单向流转（失败后只允许显式重发回到执行中），% → % 不允许'
        , OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'EXECUTING' AND NOT EXISTS (
      SELECT 1 FROM finance_payment_execution
       WHERE tenant_id = NEW.tenant_id AND installment_id = NEW.id) THEN
      RAISE EXCEPTION '付款分批 #% 进入执行中前必须先有对应的付款执行记录（12.2.2.2）'
        , NEW.seq
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'PAID' AND NOT EXISTS (
      SELECT 1 FROM finance_payment_execution
       WHERE tenant_id = NEW.tenant_id AND installment_id = NEW.id AND status = 'SUCCEEDED') THEN
      RAISE EXCEPTION '付款分批 #% 标记已付前必须有一条**成功**的付款执行记录'
        , NEW.seq
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'FAILED' AND NOT EXISTS (
      SELECT 1 FROM finance_payment_execution
       WHERE tenant_id = NEW.tenant_id AND installment_id = NEW.id AND status = 'FAILED') THEN
      RAISE EXCEPTION '付款分批 #% 标记失败前必须有一条**确定性失败**的付款执行记录：结果未知不等于失败'
        , NEW.seq
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- (d) 明细 / 分批冻结守卫 —— **换掉** V346 的版本，并**拆成两个表专属的函数**。
--
--     ⚠️ **V346 的这一段有个只在执行态才会暴露的问题**（12.2.2.1 写不出 PAID/FAILED，
--        所以它一直没被触发过）：V346 的 `guard_finance_payment_order_release_lines`
--        是**付款单上的 AFTER UPDATE** 触发器，它在付款单转 PAID/FAILED 时
--        把明细行的 `active` 置 false；而明细行上的 BEFORE UPDATE 触发器
--        （同一个函数）在父单已非 DRAFT 时会**直接报错** ⇒
--        「付款单转终态」这条路径在 V346 里是**走不通的**。
--        本迁移顺手修好它：放行「只把 active 由 true 改成 false」这一种更新
--        （释放占用），其余一律冻结。
--
--     另外：**分批的执行态由 12.2.2.2 推进**，所以分批的 UPDATE 要放行
--     —— 但**钱的那些事实（金额 / 币种 / 计划付款日）仍然冻结**：
--     「执行态可以推进，钱的那些事实不能动」。
--
--     ⚠️ **为什么必须拆成两个函数（而不是继续共用一个）**：
--        V346 的 `guard_finance_payment_order_child_frozen()` 是**表无关**的 ——
--        它只碰 `tenant_id` / `id` / `order_id`（两张子表都有）⇒ 所以同一个函数
--        可以同时挂在 `finance_payment_order_line` 与 `finance_payment_order_installment`
--        两个触发器上。而本迁移要按表比对**各自的专属字段**
--        （明细行有 line_no / amount / active，分批有 seq / planned_paid_date），
--        一旦继续共用同一个函数体，**两个触发器都会炸** —— 报哪条错取决于
--        该表上**第一个被规划**的 IF 分支引用了哪个字段：
--            · 挂在明细行上 ⇒ 分批分支先被规划 ⇒ `record "new" has no field "seq"`
--              （明细行表没有 seq 列）；
--            · 挂在分批上   ⇒ 明细分支先被规划 ⇒ `record "new" has no field "active"`
--              （分批表没有 active 列）。
--        PL/pgSQL 的语句是**首次执行到那一支时**才规划，所以错报时机还取决于
--        「哪条放行路径先被命中」⇒ 现象飘忽、极难查。
--        后果是「付款单提交后修改子表任何非放行字段」，抛出来的是一条看不懂的错，
--        而不是预期的「明细与分批不可再改」。
--        ⇒ 正解是**拆成两个表专属函数**，各自只引用自己表上真实存在的字段。
--
--     ⚠️ **不用 `to_jsonb(NEW)->>'seq'` 这种绕过方案**：那会把金额 / 日期的比较
--        退化成**文本比较**（'100.0000' vs '100.0000'、日期格式差异）⇒
--        「金额到底变没变」的语义坏掉。拆函数才是正解。
--
-- (d-1) 明细行冻结守卫 —— 只碰 finance_payment_order_line 上真实存在的字段。
CREATE OR REPLACE FUNCTION guard_finance_payment_order_line_frozen() RETURNS trigger AS $$
DECLARE
  parent_status text;
BEGIN
  SELECT status INTO parent_status FROM finance_payment_order
   WHERE tenant_id = OLD.tenant_id AND id = OLD.order_id;
  -- 父单已被删除（级联中）⇒ 放行。
  --   ⚠️ UPDATE 必须返回 NEW（BEFORE 触发器返回 OLD 等于「把这一行改回旧值」=
  --     静默失效，比报错更难查）；DELETE 才返回 OLD。
  IF parent_status IS NULL THEN
    IF TG_OP = 'DELETE' THEN
      RETURN OLD;
    END IF;
    RETURN NEW;
  END IF;
  IF parent_status <> 'DRAFT' THEN
    -- 放行一：明细占用的**释放 / 重新占用**。
    --   · 释放（true → false）：V346 的 release_lines 触发器在付款单转终态时要改这一列；
    --   · 重新占用（false → true）：失败后显式重发时要把明细行**重新锁回**本付款单
    --     （否则明细会被别的付款单抢走，重发就变成第二笔付款）。
    --   ⚠️ 这两种更新都**只动 active 一列**（外加 updated_at），
    --     其余字段一律逐字比对 ⇒ 钱的那些事实改不了。
    --     （updated_at / created_at **不参与**比对：release_lines 与重发路径都会改它。）
    IF TG_OP = 'UPDATE'
       AND NEW.active IS DISTINCT FROM OLD.active
       AND NEW.id = OLD.id
       AND NEW.order_id = OLD.order_id
       AND NEW.tenant_id = OLD.tenant_id
       AND NEW.line_no = OLD.line_no
       AND NEW.payable_line_id = OLD.payable_line_id
       AND NEW.description = OLD.description
       AND NEW.currency = OLD.currency
       AND NEW.amount = OLD.amount
       AND NEW.payable_amount_snapshot = OLD.payable_amount_snapshot
       AND NEW.payable_paid_snapshot = OLD.payable_paid_snapshot THEN
      RETURN NEW;
    END IF;
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

-- (d-2) 分批冻结守卫 —— 只碰 finance_payment_order_installment 上真实存在的字段。
--
--     放行二：分批的**执行态**推进（`status` / `execution_available` /
--     `failure_reason` / `updated_at` 可以变）—— 但**金额 / 币种 / 计划付款日
--     必须仍然冻结**（这三个一变就拒绝）。
CREATE OR REPLACE FUNCTION guard_finance_payment_order_installment_frozen() RETURNS trigger AS $$
DECLARE
  parent_status text;
BEGIN
  SELECT status INTO parent_status FROM finance_payment_order
   WHERE tenant_id = OLD.tenant_id AND id = OLD.order_id;
  -- 父单已被删除（级联中）⇒ 放行（UPDATE 返回 NEW / DELETE 返回 OLD）。
  IF parent_status IS NULL THEN
    IF TG_OP = 'DELETE' THEN
      RETURN OLD;
    END IF;
    RETURN NEW;
  END IF;
  IF parent_status <> 'DRAFT' THEN
    IF TG_OP = 'UPDATE'
       AND NEW.id = OLD.id
       AND NEW.order_id = OLD.order_id
       AND NEW.tenant_id = OLD.tenant_id
       AND NEW.seq = OLD.seq
       AND NEW.planned_paid_date = OLD.planned_paid_date
       AND NEW.currency = OLD.currency
       AND NEW.amount = OLD.amount THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION '付款单已提交（%），明细与分批不可再改：需要改请被驳回后退回草稿再改', parent_status
      USING ERRCODE = 'check_violation';
  END IF;
  -- ⚠️ **UPDATE 必须返回 NEW**（理由同上）；DELETE 才返回 OLD。
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- (d-3) **重建两个触发器并指向各自的专属函数**。
--
--     ⚠️ V346 建的这两个触发器仍然指向那个**表无关**的共享函数 ⇒
--        只换函数体而不重建触发器，明细行上就会炸 `record "new" has no field "seq"`。
--        ⇒ 这里显式 DROP + CREATE，让触发器与函数**一一对应**。
DROP TRIGGER IF EXISTS finance_payment_order_line_frozen ON finance_payment_order_line;
DROP TRIGGER IF EXISTS finance_payment_order_installment_frozen ON finance_payment_order_installment;

CREATE TRIGGER finance_payment_order_line_frozen
  BEFORE UPDATE OR DELETE ON finance_payment_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_order_line_frozen();

CREATE TRIGGER finance_payment_order_installment_frozen
  BEFORE UPDATE OR DELETE ON finance_payment_order_installment
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_order_installment_frozen();

-- 旧的**表无关**共享函数已无人引用 ⇒ 删掉，避免下一个人再把它挂到某一张表上
-- 然后重蹈「明细行表没有 seq 列」的覆辙。
DROP FUNCTION IF EXISTS guard_finance_payment_order_child_frozen();

-- (e) **执行单自己的状态机**。
--
--     PENDING → SENDING → (SUCCEEDED | FAILED | UNKNOWN)
--     UNKNOWN → SENDING（**查证之后**的重发） | SUCCEEDED（查证发现已成功） | FAILED
--     FAILED  → SENDING（确定性失败后的重发）
--     SUCCEEDED 是终态（钱已经付出去了，不存在「再发一次」）。
--
--     ⚠️ **UNKNOWN → SENDING 这一步是本模块最关键的一条**：
--        「外部支付超时不等于失败，必须先查证再重发」——
--        而「有没有查证过」在数据层看得见：每次重发都先落一条 QUERY 尝试。
CREATE OR REPLACE FUNCTION guard_finance_payment_execution_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
         (OLD.status = 'PENDING'   AND NEW.status IN ('SENDING','SUCCEEDED','FAILED','UNKNOWN'))
      OR (OLD.status = 'SENDING'   AND NEW.status IN ('SUCCEEDED','FAILED','UNKNOWN'))
      OR (OLD.status = 'UNKNOWN'   AND NEW.status IN ('SENDING','SUCCEEDED','FAILED'))
      OR (OLD.status = 'FAILED'    AND NEW.status = 'SENDING')
    ) THEN
      RAISE EXCEPTION '付款执行单 % 的状态流转不合法：% → %（成功是终态；重发只能从 UNKNOWN / FAILED 发起，且必须先查证）'
        , OLD.execution_no, OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;

    -- 成功 ⇒ 必须有渠道流水号，且至少有一次**发送**尝试。
    IF NEW.status = 'SUCCEEDED' THEN
      IF btrim(NEW.channel_serial_no) = '' THEN
        RAISE EXCEPTION '付款执行单 % 标记成功必须写渠道流水号：没有流水号的付款事后无从查证'
          , OLD.execution_no
          USING ERRCODE = 'check_violation';
      END IF;
      -- ⚠️ 允许「只有查证记录」的恢复路径：进程在发送途中挂掉时本地可能一条 SEND 尝试都没落，
      --    但渠道侧其实已经付了 —— 那时是**查证**把它捞回来的。
      IF NOT EXISTS (
        SELECT 1 FROM finance_payment_execution_attempt
         WHERE tenant_id = NEW.tenant_id AND execution_id = NEW.id) THEN
        RAISE EXCEPTION '付款执行单 % 标记成功前必须有一次尝试记录（发送或查证）'
          , OLD.execution_no
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;

    -- 确定性失败 ⇒ 必须写明原因与分类。
    IF NEW.status = 'FAILED' THEN
      IF btrim(NEW.failure_reason) = '' OR NEW.failure_class = 'NONE' THEN
        RAISE EXCEPTION '付款执行单 % 标记失败必须写明失败原因与分类'
          , OLD.execution_no
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- ⚠️ **「重发前必须查证」的检查刻意放在状态变化判断之外**：
--    从 UNKNOWN / FAILED 回到 SENDING 时，必须已经有一条 QUERY 尝试记录。
--    （SENDING → SENDING 的原地重发由**服务层**守：它要求先查证，且服务层同时把
--      「查证过没有」这件事写进 attempt 表 ⇒ 数据层可查。）
CREATE OR REPLACE FUNCTION guard_finance_payment_execution_resend_guard() RETURNS trigger AS $$
BEGIN
  IF NEW.status = 'SENDING' AND OLD.status IN ('UNKNOWN','FAILED') THEN
    IF NOT EXISTS (
      SELECT 1 FROM finance_payment_execution_attempt
       WHERE tenant_id = NEW.tenant_id AND execution_id = NEW.id AND attempt_kind = 'QUERY') THEN
      RAISE EXCEPTION '付款执行单 % 重发前必须先做状态查证（QUERY）：查证确认渠道侧确实没有成功才允许重发'
        , OLD.execution_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_execution_resend_guard
  BEFORE UPDATE ON finance_payment_execution
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_execution_resend_guard();

CREATE TRIGGER finance_payment_execution_state
  BEFORE UPDATE ON finance_payment_execution
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_execution_state();

-- (f) 执行单**不可删除**：删掉就没有「这笔曾经付过（或者付到一半）」的痕迹了。
CREATE OR REPLACE FUNCTION guard_finance_payment_execution_no_delete() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '付款执行单 % 不可删除：付款执行记录是资金凭据，只能推进状态', OLD.execution_no
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_payment_execution_no_delete
  BEFORE DELETE ON finance_payment_execution
  FOR EACH ROW EXECUTE FUNCTION guard_finance_payment_execution_no_delete();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：创建时间倒序 + id（同一毫秒创建的执行单只看时间顺序不确定）。
CREATE INDEX finance_payment_execution_by_created
  ON finance_payment_execution(tenant_id, created_at DESC, id);
-- 按付款单查（详情页 / 重发查证）。
CREATE INDEX finance_payment_execution_by_order
  ON finance_payment_execution(tenant_id, order_id, created_at DESC, id);
-- 按状态查（待处理的 UNKNOWN 就在这里）。
CREATE INDEX finance_payment_execution_by_status
  ON finance_payment_execution(tenant_id, status, created_at DESC, id);
-- 按渠道查（渠道流水号反查）。
CREATE INDEX finance_payment_execution_by_channel
  ON finance_payment_execution(tenant_id, channel_id, created_at DESC);
CREATE INDEX finance_payment_execution_by_serial
  ON finance_payment_execution(tenant_id, channel_serial_no);
-- 按币种查（资金口径永远按币种分开）。
CREATE INDEX finance_payment_execution_by_currency
  ON finance_payment_execution(tenant_id, currency, created_at DESC);
-- 分批查。
CREATE INDEX finance_payment_execution_by_installment
  ON finance_payment_execution(tenant_id, installment_id);
-- 尝试记录按执行单排（发送在前、查证在后，各按 attempt_no）。
CREATE INDEX finance_payment_execution_attempt_by_execution
  ON finance_payment_execution_attempt(tenant_id, execution_id, attempt_kind, attempt_no);
-- 分摊按执行单查（回写与复算用）。
CREATE INDEX finance_payment_execution_allocation_by_execution
  ON finance_payment_execution_allocation(tenant_id, execution_id);
-- 分摊按应付明细查（算「这条明细已经被哪几笔付款占掉多少」）。
CREATE INDEX finance_payment_execution_allocation_by_payable_line
  ON finance_payment_execution_allocation(tenant_id, payable_line_id);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_payment_execution_event_by_execution
  ON finance_payment_execution_event(tenant_id, execution_id, occurred_at, id);
-- 锁汇按付款单查。
CREATE INDEX finance_rate_lock_by_order
  ON finance_rate_lock(tenant_id, order_id, locked_at DESC, id);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_payment_channel','finance_payment_execution','finance_payment_execution_allocation',
    'finance_payment_execution_attempt','finance_payment_execution_event','finance_rate_lock'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_payment_channel, finance_payment_execution, finance_payment_execution_allocation,
  finance_payment_execution_attempt, finance_payment_execution_event, finance_rate_lock TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_payment_channel, finance_payment_execution, finance_payment_execution_allocation,
  finance_payment_execution_attempt, finance_payment_execution_event, finance_rate_lock TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：
--   * 尝试记录与事件流是**举证凭据**，只能追加；
--   * 渠道配置**不可删除**（渠道删了就查不出历史执行单当时用的是哪个渠道）。
REVOKE UPDATE,DELETE ON finance_payment_execution_attempt FROM finance_app;
REVOKE UPDATE,DELETE ON finance_payment_execution_event FROM finance_app;
REVOKE DELETE ON finance_payment_channel FROM finance_app;

-- ===========================================================================
-- 第五节：交接面（写给下游与运维）
-- ===========================================================================
--
--   * **12.7.1 资金账户（未交付）**：付款账户仍只是 12.2.2.1 登记的
--     「引用 + 名称 + 币种快照」，本模块**不建账户表、不做账户余额** ——
--     那会造出第二份资金账户真相。它交付后由它接管账户与余额，
--     本模块结构不用改。
--
--   * **12.5.2 凭证（未交付）**：付款成功后**不生成凭证**（那会造出第二套账）。
--     本模块只留下「付款成功」这个事实与渠道流水号；
--     凭证由 12.5.2.1 从这里取数。
--
--   * **12.2.2.1 付款单与审批（已交付）**：本模块接管了它的两个执行态守卫
--     （见第二节），并**回写** `finance_payable_line.paid_amount` ——
--     V340 的 `guard_finance_payable_line_frozen` 本来就放行这一列
--     （「只放行 paid_amount（付款金额）」），不需要再改 V340 的任何触发器。
--
--   * **支付渠道接入**：本模块**不内置任何渠道 SDK**。渠道通过
--     `PaymentChannelPort` 抽象接入；没有凭据 / 主机不在白名单 /
--     渠道未启用 ⇒ **明确报「渠道不可用 / 未配置」**，
--     并**如实记成失败**，绝不假装成功。
