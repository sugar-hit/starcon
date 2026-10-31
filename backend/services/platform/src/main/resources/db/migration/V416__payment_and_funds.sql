-- ===========================================================================
-- V416 · payment_and_funds —— 18.2.2.1「支付与资金」
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.2「财务与资金」→ 18.2.2「支付与资金」→ 18.2.2.1：
--
--   「**职责**：对接支付与资金服务（PayPal、Stripe、PingPong、连连、万里汇）：
--      余额查询、收款登记、付款执行、汇率查询、手续费明细同步。
--    **输入**：资金操作请求；接口配置（**密钥引用**）。
--    **输出**：操作结果 + 资金流水同步。
--    **关系**：上游 = 12.2 收付款、12.7 资金账户；下游 = 支付服务商。
--    **所有第三方资金操作必须有幂等键与查询兜底**，超时先查证再重试（实体目录 §6）。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个迁移**刻意不做**什么（先说清楚边界，后面每张表都按这条边界设计）
-- ---------------------------------------------------------------------------
--
--   * **不存任何支付服务商的密钥明文**。蓝图写的是「接口配置（**密钥引用**）」——
--     存的是**引用**（密钥管理器里的 ID），不是密钥本身。
--     ⇒ 沿用 18.1.1.1 应用凭证（V377）的纪律：`credential_ref` 存引用、
--       `credential_ref_lookup` 存**引用的** SHA-256 十六进制摘要（只用于按引用定位这一行）、
--       `credential_hint` 存人眼分辨用的末四位。**Secret 侧刻意没有可定位摘要**。
--
--   * **不抄 finance 的表**。上游 12.2 收付款 / 12.7 资金账户住在 finance 服务里，
--     AGENTS.md「模块边界纪律」禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒
--     本模块**只经 finance 的授权 HTTP 端点读**，不建跨服务外键、不抄余额历史。
--
--   * **不给支付服务商发假请求**。本仓没有任何真实的服务商密钥与网络通道 ⇒
--     服务商适配器逐家如实答「未接入」，**一次数据都不写**（与 18.2.1.1 的
--     `UnavailableFinanceSoftwarePushPort` 同一纪律）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单（七张）
-- ---------------------------------------------------------------------------
--
--   platform_payment_provider  支付服务商连接档案（接口配置：密钥**引用**）
--   platform_payment_operation 资金操作记录（**幂等键** + 请求摘要 + 状态机）
--   platform_payment_attempt   每次尝试的留痕（SEND / QUERY）—— 「查证兜底」的证据
--   platform_payment_balance   余额观测快照（服务商侧余额）
--   platform_fund_flow         **资金流水台账**（输出里的「资金流水同步」）
--   platform_payment_fee       手续费明细（同步 / 导入）
--   platform_payment_rate      汇率观测（catalog 汇率事实 / 服务商报价）
--
-- 七张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「幂等键与查询兜底」怎么落到数据层（实体目录 §6 的物理形态）
-- ---------------------------------------------------------------------------
--
--   * `platform_payment_operation.idempotency_key` 上有
--     **UNIQUE(tenant_id, idempotency_key)** ⇒ 同一个幂等键在租户内**只可能有一行**。
--     重复提交同键 ⇒ 服务层查到已有行 ⇒ **原样返回同一结果，绝不第二次调用服务商**
--     （重复扣款是严重资损事故）。
--   * `request_digest`（请求内容的 SHA-256 十六进制）钉住「同键必须同请求」：
--     同键但请求内容不同 ⇒ 409（那不是重放，那是**另一个请求**）。
--   * `platform_payment_attempt.attempt_kind` 有 'SEND' 与 'QUERY' 两种：
--     **每一次重发之前必须先有一条 QUERY 留痕**，否则「超时先查证再重试」只是一句注释。
--     服务层的重试端点强制检查这一点。
--   * `platform_payment_operation.status` 里**没有**「已成功但不知道」的隐含值：
--     超时 / 5xx / 不可达一律进 **UNKNOWN**（结果未知），**绝不进 FAILED** ——
--     「失败」是确定性结论，「未知」是「必须先查证」。
--
-- ---------------------------------------------------------------------------
-- 3. 金额与数量
-- ---------------------------------------------------------------------------
--
--   * 金额 / 余额 / 手续费 / 流水额一律 `numeric(20,6)`（**禁浮点**，与本服务
--     18.2.1.1 的 `numeric(20,6)` 同一口径）。
--   * 汇率用 `numeric(24,12)`：汇率不是金额，但同样**不许用 double 算**。
--   * 数量（重试上限等）用 `numeric(19,6)`（AGENTS.md 明文口径）。
--   * 时间列一律 `timestamptz`；业务日期用 `date`。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_payment_provider —— 支付服务商连接档案（接口配置：密钥**引用**）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个服务商（PayPal / Stripe / PingPong / 连连 / 万里汇）在本租户下的一份配置。
--
-- 🔴 **库里没有任何密钥明文**，与 V377（18.1.1.1 应用凭证）同一纪律：
--   * `credential_ref`      —— 密钥管理器里的**引用 ID**（如 `secret://payments/paypal/main`）。
--                              它**不是**密钥本身 —— 拿着它去密钥管理器才换得出凭据。
--   * `credential_ref_lookup` —— SHA-256 十六进制摘要，**只用于按引用定位这一行**。
--                              ⚠️ 与 V377 的差别要说清：V377 摘要的是**API Key 本体**，
--                              而 API Key 本体是 256 bit CSPRNG 随机串（高熵，摘要不可枚举）；
--                              这里摘要的是**引用串**，引用串本身不是机密（它是「去哪里取密钥」的指针），
--                              摘要它既不泄露密钥、又能按引用定位。**密钥本体从不进这张表**，
--                              所以**不存在**「Secret 侧可定位摘要」这个风险面。
--   * `credential_hint`     —— 末四位 / 人眼分辨用（非机密）。
--
-- 🔴 **系统零预置行**：不替用户默认一个「PayPal 账号」。服务商配置由租户自己登记。
CREATE TABLE platform_payment_provider (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 服务商代码（受控词表，与蓝图列的五个一一对应）。
  provider_code text NOT NULL
    CHECK(provider_code IN ('PAYPAL','STRIPE','PINGPONG','LIANLIAN','WORLDFIRST')),
  -- 配置名（租户内可有多个同一服务商的配置，比如「美国站 PayPal」「欧洲站 PayPal」）。
  name varchar(120) NOT NULL,

  -- 服务商接口地址（登记时校验是 http/https 形状；真正发起外呼前还要过主机白名单）。
  base_url varchar(300) NOT NULL DEFAULT '',

  -- 🔴 密钥**引用**（不是密钥本体）。空串 = 还没配凭据（该配置不可用于任何真实调用）。
  credential_ref varchar(200) NOT NULL DEFAULT '',
  -- 「引用」的 SHA-256 十六进制摘要：只用于按引用定位这一行（唯一索引）。
  credential_ref_lookup varchar(64) NOT NULL DEFAULT '',
  -- 末四位 / 人眼分辨用（非机密）。
  credential_hint varchar(16) NOT NULL DEFAULT '',

  -- 该服务商的结算币种（三个大写字母）。
  settlement_currency varchar(3) NOT NULL DEFAULT '',

  -- 配置状态：
  --   ACTIVE    启用
  --   DISABLED 停用（**不删**：历史流水与操作记录要能复现）
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),

  -- 单次外呼超时秒数 / 最大重试次数（数量，NUMERIC(19,6)，禁浮点）。
  timeout_seconds numeric(19,6) NOT NULL DEFAULT 15 CHECK(timeout_seconds > 0),
  max_attempts numeric(19,6) NOT NULL DEFAULT 3 CHECK(max_attempts >= 1),

  remark varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一租户内配置名唯一：两个同名配置会让运营在界面上分不清钱打给了谁。
  UNIQUE(tenant_id, name),
  -- 同一租户内**同一个引用**只能登记一次（定位列唯一索引）。
  UNIQUE(tenant_id, credential_ref_lookup),

  CHECK(btrim(name) <> '' AND length(name) <= 120),
  -- 「填了引用就必须同时填定位摘要与提示」——三者是同一条凭据的三个面，缺一个就说不清配的是哪条。
  CHECK(
    (credential_ref = '' AND credential_ref_lookup = '' AND credential_hint = '')
    OR (btrim(credential_ref) <> '' AND credential_ref_lookup <> '' AND btrim(credential_hint) <> '')
  ),
  CHECK(
    settlement_currency = ''
    OR (length(settlement_currency) = 3 AND settlement_currency = upper(settlement_currency))
  )
);

CREATE INDEX platform_payment_provider_by_code
  ON platform_payment_provider(tenant_id, provider_code, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. platform_payment_operation —— 资金操作记录（**幂等键** + 请求摘要 + 状态机）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次资金操作请求（余额查询 / 收款登记 / 付款执行 / 汇率查询 / 手续费同步）。
--
-- 🔴 **幂等键是本模块最硬的一条约束**：
--   UNIQUE(tenant_id, idempotency_key) ⇒ 同键重复提交**只可能命中同一行**，
--   服务层据此原样返回既有结果，**绝不第二次调用服务商**。
--
-- 🔴 **状态机**（服务层负责转换，DB 层只做取值域约束）：
--   PENDING        已受理，还没发出
--   SENDING        已发出，等服务商回单
--   SUCCEEDED      **确定性成功**（服务商明确给了成功回单，或本地登记已完成）
--   FAILED         **确定性失败**（服务商明确拒绝）—— 可以（查证后）重试
--   UNKNOWN        **结果未知**（超时 / 5xx / 不可达 / 查证查不出来）—— **必须先查证**
--   NOT_CONNECTED  服务商**未接入**（没有凭据 / 主机不在白名单 / 适配器未交付）
--
--   🔴 **UNKNOWN 与 FAILED 刻意是两个值**：超时不等于失败。
--      把超时记成 FAILED 会让重试逻辑跳过查证，直接二次付款 —— 那就是资损。
CREATE TABLE platform_payment_operation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 🔴 幂等键（租户内唯一）。调用方可自带；不带时由服务端按请求内容派生（同一个请求 ⇒ 同一个键）。
  idempotency_key varchar(120) NOT NULL,

  operation_type text NOT NULL
    CHECK(operation_type IN ('BALANCE_QUERY','RECEIPT_REGISTER','PAYMENT_EXECUTE','RATE_QUERY','FEE_SYNC')),

  -- 关联的服务商配置（**配置不存在** ⇒ 400，不允许对着不存在的服务商发指令）。
  provider_id uuid NOT NULL,
  -- 服务商代码冗余一份：台账查询按代码筛比按 id join 快，且服务商改代码后历史仍可读。
  provider_code text NOT NULL
    CHECK(provider_code IN ('PAYPAL','STRIPE','PINGPONG','LIANLIAN','WORLDFIRST')),

  -- 资金方向：INBOUND 进（收款）/ OUTBOUND 出（付款）。查询类操作方向为空。
  direction text NOT NULL DEFAULT '' CHECK(direction IN ('','INBOUND','OUTBOUND')),

  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','SENDING','SUCCEEDED','FAILED','UNKNOWN','NOT_CONNECTED')),

  -- 金额与币种（余额查询 / 汇率查询可以为空）。
  amount numeric(20,6),
  currency varchar(3) NOT NULL DEFAULT '',
  -- 本位币折算（汇率查询与付款锁汇用；**BigDecimal**，禁浮点）。
  base_amount numeric(20,6),
  base_currency varchar(3) NOT NULL DEFAULT '',
  exchange_rate numeric(24,12),

  -- 业务单号（付款单号 / 收款单号 / 账单号）：给人看的，对账时能对上。
  reference varchar(120) NOT NULL DEFAULT '',
  -- 收款方（付款执行用）/ 付款方（收款登记用）。
  counterparty_name varchar(200) NOT NULL DEFAULT '',
  counterparty_ref varchar(200) NOT NULL DEFAULT '',
  counterparty_type varchar(40) NOT NULL DEFAULT '',

  -- 🔴 请求内容摘要（SHA-256 十六进制）：钉住「同一个幂等键必须对应同一个请求」。
  --   同键不同请求 ⇒ 409（那不是重放，是另一个请求借用了旧键）。
  request_digest varchar(64) NOT NULL DEFAULT '',

  -- 服务商流水号（明确成功时才有值）。
  provider_serial_no varchar(120) NOT NULL DEFAULT '',
  -- 失败 / 未接入 / 结果未知的理由（**人可读**，写清「缺什么、归谁」）。
  failure_reason varchar(500) NOT NULL DEFAULT '',
  -- 失败分类：REJECTED（服务商明确拒绝）/ TIMEOUT（超时）/ UNAVAILABLE（不可达）
  --           / NOT_CONFIGURED（未接入）—— 「超时」与「拒绝」必须可区分。
  failure_class text NOT NULL DEFAULT ''
    CHECK(failure_class IN ('','REJECTED','TIMEOUT','UNAVAILABLE','NOT_CONFIGURED')),

  -- 本次操作**是否已与服务商通道核对过**（服务商侧返回了明确结论）。
  --   收款登记在本地台账里成功 **不等于** 服务商侧已登记 ⇒ 这一格刻意分开。
  provider_notified boolean NOT NULL DEFAULT false,
  -- 服务商侧**没**被告知 / 没核对时的说明（为什么没告知）。
  provider_notice varchar(500) NOT NULL DEFAULT '',

  -- 操作来源：
  --   DIRECT  走服务商通道（或试图走）
  --   IMPORT  从服务商账单 / 对账单导入（**根本没调用**服务商通道）
  source text NOT NULL DEFAULT 'DIRECT' CHECK(source IN ('DIRECT','IMPORT')),

  -- 已尝试次数（SEND + QUERY 合计）。
  attempt_count int NOT NULL DEFAULT 0,
  -- 是否允许重试：**UNKNOWN 时**只有「查证结果明确说服务商侧没有这笔」才为真。
  retry_allowed boolean NOT NULL DEFAULT false,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 幂等键的物理保证：同键只能有一行。
  UNIQUE(tenant_id, idempotency_key),

  FOREIGN KEY(tenant_id, provider_id) REFERENCES platform_payment_provider(tenant_id, id),

  CHECK(btrim(idempotency_key) <> '' AND length(idempotency_key) <= 120),
  CHECK(amount IS NULL OR amount > 0),
  CHECK(currency = '' OR (length(currency) = 3 AND currency = upper(currency))),
  CHECK(base_currency = '' OR (length(base_currency) = 3 AND base_currency = upper(base_currency))),
  CHECK(exchange_rate IS NULL OR exchange_rate > 0),
  -- 方向与操作类型必须自洽：只有付款是出、只有收款是进。
  CHECK(
    (operation_type = 'PAYMENT_EXECUTE' AND direction = 'OUTBOUND')
    OR (operation_type = 'RECEIPT_REGISTER' AND direction = 'INBOUND')
    OR (operation_type IN ('BALANCE_QUERY','RATE_QUERY','FEE_SYNC') AND direction = '')
  )
);

CREATE INDEX platform_payment_operation_by_key
  ON platform_payment_operation(tenant_id, idempotency_key);
CREATE INDEX platform_payment_operation_by_status
  ON platform_payment_operation(tenant_id, status, created_at DESC);
CREATE INDEX platform_payment_operation_by_provider
  ON platform_payment_operation(tenant_id, provider_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_payment_attempt —— 每次尝试的留痕（SEND / QUERY）
-- ---------------------------------------------------------------------------
--
-- 一行 = 对某个操作的一次尝试。
--
-- 🔴 **这张表是「超时先查证再重试」的证据**：任何一次**重发**之前，
--   必须已经存在一条 attempt_kind='QUERY' 的留痕（服务层的重试端点强制检查）。
--   没有它，「先查证」只是一句注释；有了它，事后能逐条核对「有没有查过再重发」。
--
-- ⚠️ attempt_no 在**同一个操作内**递增（SEND 与 QUERY 共用一个序号），
--   这样「先查证再重发」在时间序上就是自证的：QUERY 的序号一定小于重发的 SEND 序号。
CREATE TABLE platform_payment_attempt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  operation_id uuid NOT NULL,

  -- 同一个操作内的尝试序号（SEND 与 QUERY 共用一条递增序列）。
  attempt_no int NOT NULL CHECK(attempt_no >= 1),
  -- 尝试种类：
  --   SEND  发起（付款 / 收款登记 / 余额查询 / 手续费拉取）
  --   QUERY **查证**（超时或未知之后，向服务商确认这笔到底有没有发生）
  attempt_kind text NOT NULL CHECK(attempt_kind IN ('SEND','QUERY')),

  -- 幂等键冗余一份：留痕单独取出来时也知道它查的是哪一笔。
  idempotency_key varchar(120) NOT NULL,

  -- 结论：
  --   SUCCESS       服务商明确给成功
  --   REJECTED      服务商明确拒绝（确定性失败）
  --   NOT_FOUND     **查证**结果：服务商侧确实没有这一笔（只有 QUERY 才可能出这个值）
  --   TIMEOUT       超时（**结果未知**）
  --   UNKNOWN       回单无法解析 / 状态无法识别（**结果未知**）
  --   NOT_CONNECTED 服务商未接入（**根本没发出去**，与「发了不知道」是两回事）
  outcome text NOT NULL
    CHECK(outcome IN ('SUCCESS','REJECTED','NOT_FOUND','TIMEOUT','UNKNOWN','NOT_CONNECTED')),

  provider_serial_no varchar(120) NOT NULL DEFAULT '',
  failure_reason varchar(500) NOT NULL DEFAULT '',
  -- 请求 / 回单摘要（**绝不含凭据**）。
  request_summary varchar(500) NOT NULL DEFAULT '',
  response_summary varchar(500) NOT NULL DEFAULT '',

  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个操作内的尝试序号唯一。
  UNIQUE(tenant_id, operation_id, attempt_no),

  FOREIGN KEY(tenant_id, operation_id) REFERENCES platform_payment_operation(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 「查证查不到」只允许出现在 QUERY 上：SEND 语境里「查不到」没有意义。
  CHECK(attempt_kind = 'QUERY' OR outcome <> 'NOT_FOUND')
);

CREATE INDEX platform_payment_attempt_by_operation
  ON platform_payment_attempt(tenant_id, operation_id, attempt_no);

-- ---------------------------------------------------------------------------
-- 4. platform_payment_balance —— 余额观测快照
-- ---------------------------------------------------------------------------
--
-- 一行 = 某个服务商某个币种在某个时刻的**余额观测值**。
--
-- 🔴 **append-only**：余额历史不许改写（与 12.7.1.1 资金账户余额历史同一纪律）——
--   能改的余额历史等于没有余额历史。
--
-- 来源：
--   PROVIDER  服务商直查回来的（服务商未接入时**恒为空**）
--   IMPORT    从服务商账单 / 对账单导入的（今天就能用的路径）
CREATE TABLE platform_payment_balance (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  provider_id uuid NOT NULL,

  currency varchar(3) NOT NULL,
  -- 可用余额与账面余额**分开记**（服务商常常两者不等：冻结 / 在途）。
  available_amount numeric(20,6),
  balance_amount numeric(20,6) NOT NULL,

  -- 观测值的来源与事实时刻。
  source text NOT NULL CHECK(source IN ('PROVIDER','IMPORT')),
  -- 事实发生时刻（服务商用的是**它那边**的时刻，不是我们的）。
  observed_at timestamptz NOT NULL,
  -- 导入 / 同步的来源说明（账单文件名、批次号…）。
  source_ref varchar(200) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, provider_id) REFERENCES platform_payment_provider(tenant_id, id),

  CHECK(length(currency) = 3 AND currency = upper(currency)),
  CHECK(balance_amount IS NOT NULL),
  CHECK(available_amount IS NULL OR available_amount >= 0)
);

CREATE INDEX platform_payment_balance_by_provider
  ON platform_payment_balance(tenant_id, provider_id, currency, observed_at DESC);

-- ---------------------------------------------------------------------------
-- 5. platform_fund_flow —— **资金流水台账**
-- ---------------------------------------------------------------------------
--
-- 一行 = 一笔资金进出（收款 / 付款 / 手续费 / 调整）。
--
-- 🔴 **append-only**（触发器钉死）：资金流水是账，改一笔账就等于改历史。
--
-- 来源：
--   OPERATION  由一次资金操作（付款执行 / 收款登记）产生
--   IMPORT     从服务商账单导入
CREATE SEQUENCE platform_fund_flow_no_seq;

CREATE TABLE platform_fund_flow (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 流水号（人可读、单调递增；跨租户不保证连续 —— 流水号是**定位线索**不是**密级依据**）。
  flow_no varchar(40) NOT NULL,

  provider_id uuid NOT NULL,
  -- 产生这笔流水的操作（导入来源为空）。
  operation_id uuid,

  flow_type text NOT NULL CHECK(flow_type IN ('RECEIPT','PAYMENT','FEE','ADJUSTMENT')),
  direction text NOT NULL CHECK(direction IN ('INBOUND','OUTBOUND')),

  amount numeric(20,6) NOT NULL,
  currency varchar(3) NOT NULL,
  base_amount numeric(20,6),
  base_currency varchar(3) NOT NULL DEFAULT '',
  exchange_rate numeric(24,12),

  -- 服务商流水号（同一服务商同一笔的唯一标识，用于与服务商侧对账）。
  provider_serial_no varchar(120) NOT NULL DEFAULT '',
  -- 业务单号（付款单号 / 收款单号）。
  reference varchar(120) NOT NULL DEFAULT '',
  counterparty_name varchar(200) NOT NULL DEFAULT '',

  -- 事实发生时刻。
  occurred_at timestamptz NOT NULL,
  source text NOT NULL CHECK(source IN ('OPERATION','IMPORT')),
  remark varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, flow_no),

  FOREIGN KEY(tenant_id, provider_id) REFERENCES platform_payment_provider(tenant_id, id),
  FOREIGN KEY(tenant_id, operation_id) REFERENCES platform_payment_operation(tenant_id, id),

  CHECK(amount > 0),
  CHECK(length(currency) = 3 AND currency = upper(currency)),
  CHECK(base_currency = '' OR (length(base_currency) = 3 AND base_currency = upper(base_currency))),
  CHECK(exchange_rate IS NULL OR exchange_rate > 0),
  -- 「由操作产生」的流水**必须**挂在操作上：没有操作号的流水就断了幂等键的链。
  CHECK((source = 'OPERATION') = (operation_id IS NOT NULL))
);

CREATE INDEX platform_fund_flow_by_provider
  ON platform_fund_flow(tenant_id, provider_id, occurred_at DESC);
CREATE INDEX platform_fund_flow_by_operation
  ON platform_fund_flow(tenant_id, operation_id);

-- ---------------------------------------------------------------------------
-- 6. platform_payment_fee —— 手续费明细
-- ---------------------------------------------------------------------------
--
-- 一行 = 一笔手续费（服务商侧的收费明细）。
--
-- 🔴 **append-only**（与流水同一纪律）。
--
-- 来源：
--   SYNC   从服务商直查回来的（服务商未接入时**恒为空**）
--   IMPORT 从服务商账单 / 对账单导入的（今天就能用的路径）
CREATE TABLE platform_payment_fee (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  provider_id uuid NOT NULL,
  operation_id uuid,

  -- 费用类型（受控词表外的自由文本，**允许扩展** —— 各服务商的费用名不一样）。
  fee_type varchar(80) NOT NULL,
  -- 手续费金额与币种。
  amount numeric(20,6) NOT NULL,
  currency varchar(3) NOT NULL,
  -- 被这笔费用**收取**的金额（实收 / 实付），用来与流水核对。
  charged_amount numeric(20,6),
  -- 这笔费用挂在哪笔流水 / 哪次操作上（服务商给的关联单号）。
  provider_serial_no varchar(120) NOT NULL DEFAULT '',
  provider_reference varchar(120) NOT NULL DEFAULT '',

  occurred_at timestamptz NOT NULL,
  source text NOT NULL CHECK(source IN ('SYNC','IMPORT')),
  summary varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, provider_id) REFERENCES platform_payment_provider(tenant_id, id),
  FOREIGN KEY(tenant_id, operation_id) REFERENCES platform_payment_operation(tenant_id, id),

  CHECK(amount >= 0),
  CHECK(length(currency) = 3 AND currency = upper(currency)),
  CHECK(charged_amount IS NULL OR charged_amount >= 0)
);

CREATE INDEX platform_payment_fee_by_provider
  ON platform_payment_fee(tenant_id, provider_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 7. platform_payment_rate —— 汇率观测
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次汇率观测（catalog 汇率事实 / 服务商锁汇报价）。
--
-- 🔴 **append-only**：汇率观测是「某个时刻看到的那个价」，改写它就没有意义了。
--
-- 来源：
--   CATALOG  catalog 5.2.2.1 汇率事实（本模块**真读**上游拿到的那一条）
--   PROVIDER 服务商侧报价（服务商未接入时**恒为空**）
--
-- 🔴 `inverted` 标记：这个汇率是不是**反推**出来的。catalog 只登记了反方向时
--   （1 CNY = 0.14 USD），取倒数得到 1 USD = 7.12 CNY —— 必须让人看见「这是推导值」。
CREATE TABLE platform_payment_rate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 服务商（服务商侧报价才有；catalog 汇率的观测**也要**挂一个服务商配置吗？——不挂，
  -- 但本表要求 provider_id NOT NULL 会让「通用汇率观测」无处安放。
  -- ⇒ 口径：**汇率观测总是挂在某个服务商配置下**，因为「按哪个服务商的通道看到的价」
  --   是汇率的一部分（服务商报价含点差）。catalog 汇率作为**参考价**记在同一张表里，
  --   挂在该租户登记的服务商配置下，source='CATALOG'。
  provider_id uuid NOT NULL,

  -- 方向必须显式：「1 base_currency = rate quote_currency」。
  base_currency varchar(3) NOT NULL,
  quote_currency varchar(3) NOT NULL,
  rate numeric(24,12) NOT NULL,

  source text NOT NULL CHECK(source IN ('PROVIDER','CATALOG')),
  -- 该汇率是否由反方向推导而来（true = 推导值，不是直接登记的事实）。
  inverted boolean NOT NULL DEFAULT false,
  -- 汇率生效日（catalog 口径）与观测时刻（我们什么时候看到的）。
  effective_date date,
  observed_at timestamptz NOT NULL,
  -- 来源说明（catalog 的来源码 / 服务商的报价单号）。
  source_ref varchar(200) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, provider_id) REFERENCES platform_payment_provider(tenant_id, id),

  CHECK(length(base_currency) = 3 AND base_currency = upper(base_currency)),
  CHECK(length(quote_currency) = 3 AND quote_currency = upper(quote_currency)),
  CHECK(base_currency <> quote_currency),
  CHECK(rate > 0)
);

CREATE INDEX platform_payment_rate_by_pair
  ON platform_payment_rate(tenant_id, provider_id, base_currency, quote_currency, observed_at DESC);

-- ===========================================================================
-- 8. 只追加的资金台账（余额 / 流水 / 手续费 / 汇率）
-- ===========================================================================
--
-- 🔴 四张表**只追加**：UPDATE / DELETE 一律被触发器拒绝。
-- 理由与 audit_log 同源 —— 能改的资金台账等于没有资金台账。
-- 与 18.1.1.1 的 `platform_app_audit_event` 同一写法。
CREATE FUNCTION refuse_platform_payment_ledger_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是只追加的资金台账，不允许修改', TG_TABLE_NAME;
END $$;

CREATE FUNCTION refuse_platform_payment_ledger_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是只追加的资金台账，不允许删除', TG_TABLE_NAME;
END $$;

CREATE TRIGGER guard_platform_payment_balance_update
  BEFORE UPDATE ON platform_payment_balance
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_payment_ledger_update();
CREATE TRIGGER guard_platform_payment_balance_delete
  BEFORE DELETE ON platform_payment_balance
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_payment_ledger_delete();

CREATE TRIGGER guard_platform_fund_flow_update
  BEFORE UPDATE ON platform_fund_flow
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_payment_ledger_update();
CREATE TRIGGER guard_platform_fund_flow_delete
  BEFORE DELETE ON platform_fund_flow
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_payment_ledger_delete();

CREATE TRIGGER guard_platform_payment_fee_update
  BEFORE UPDATE ON platform_payment_fee
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_payment_ledger_update();
CREATE TRIGGER guard_platform_payment_fee_delete
  BEFORE DELETE ON platform_payment_fee
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_payment_ledger_delete();

CREATE TRIGGER guard_platform_payment_rate_update
  BEFORE UPDATE ON platform_payment_rate
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_payment_ledger_update();
CREATE TRIGGER guard_platform_payment_rate_delete
  BEFORE DELETE ON platform_payment_rate
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_payment_ledger_delete();

-- ===========================================================================
-- 9. 租户隔离（RLS）
-- ===========================================================================
--
-- 七张表全部：tenant_id NOT NULL + ENABLE **与** FORCE RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_payment_provider ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_payment_provider FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_payment_provider
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_payment_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_payment_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_payment_operation
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_payment_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_payment_attempt FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_payment_attempt
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_payment_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_payment_balance FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_payment_balance
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_fund_flow ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_fund_flow FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_fund_flow
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_payment_fee ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_payment_fee FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_payment_fee
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_payment_rate ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_payment_rate FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_payment_rate
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 10. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的支付配置与资金台账、能写（登记配置 /
-- 执行操作 / 记流水 / 记手续费 / 记汇率）。
--
-- ⚠️ 四张只追加台账也给了 UPDATE / DELETE 授权，但**触发器**会拒绝它们 ——
--    「不可改」这件事由数据层的触发器钉死，而不是靠少授一个权限码
--    （少授会被「迁移脚本用 owner 身份跑」绕开，触发器不会）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_payment_provider TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_payment_operation TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_payment_attempt TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_payment_balance TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_fund_flow TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_payment_fee TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_payment_rate TO platform_app;
GRANT USAGE, SELECT ON SEQUENCE platform_fund_flow_no_seq TO platform_app;
