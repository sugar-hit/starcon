-- ===========================================================================
-- V429 · ai_diagnosis —— 18.4.2.3「异常诊断」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.4.2.3 原文：
--
--   「**职责**：AI 异常诊断：对库存异常、利润异常、发货延迟、同步失败、广告亏损等给出可能原因
--     分析（自动关联相关数据）与处置建议。输入：异常事件 + 关联数据（订单、库存、成本、历史记录）。
--     输出：诊断结论 + 依据 + 建议动作。关系：上游 = 1 工作台待办、各域异常；下游 = 对应处理页。
--     **诊断要给依据**（"因为 SKU X 的头程成本上涨 18%"），不能只给结论。」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不假装有 AI 推理引擎**。本仓没有任何 LLM 推理服务（见 18.4.2.1 V380 / 18.4.2.2 V424
--     的同一判断）⇒ 本模块做的是**确定性、可解释归因**：按写死的阈值与口径，把**真实存在的**
--     事实行与异常对上，给出候选原因 + **量化依据** + 处置建议。
--     🔴 **绝不返回编造的诊断结论**：没有读到支撑事实 ⇒ **不出结论**，
--       运行记录里如实写明「哪一格读不到」。
--
--   * **不假设「工作台待办表」与「各域异常事件表」存在**。它们**不存在**
--     （卷 01 工作台待办、卷 13 / 14 / 16 的异常事件表都未交付）⇒ 异常事件来源只有两条：
--       ① SUBMITTED        —— 调用方通过端点提交；
--       ② PLATFORM_INTERNAL —— 本模块从**平台自己的失败源**（V413 / V414 / V422）聚合。
--     这条限制写进能力声明（available=false + 未接通原因 + 归属系统），页面照抄。
--
--   * **不改上游一个字**。V413（投递记录）、V414（调用监控）、V422（规则执行日志）三张事实表
--     **只读**：每张事实表只有一个写入所有者，诊断模块**没有**那个所有权。
--     本模块自己的诊断记录**全部落在下面这四张新表**里。
--
--   * **不跨库读、不跨库写**。warehouse / trade / reporting / channel 的事实一律走**只读端点**
--     （HTTP），不建跨库 FK、不建共享视图、不直连对方库。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_diagnosis_event      —— 异常事件（提交 / 内部失败源聚合出来的「发生了什么」）
--   platform_diagnosis_run        —— 一次诊断运行（规则集版本 / 读了哪些源 / 结论与依据条数）
--   platform_diagnosis_conclusion —— 一条归因结论（候选原因 + 量化影响 + 处置建议）
--   platform_diagnosis_evidence   —— **一条依据**（事实表 + 字段 + 数值 + 快照时间）★
--
-- 四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 「诊断要给依据」怎么落成结构
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句「诊断要给依据（"因为 SKU X 的头程成本上涨 18%"），不能只给结论」——
-- 只把依据塞进一段说明文字，等于「不可下钻的结论」：事后没人能回答「这个 18% 是哪一行算出来的」。
--
-- ⇒ **依据是一张表，不是一个字符串字段**：
--
--   platform_diagnosis_evidence 的每一行都带**四件套**：
--
--     fact_service   —— 事实住在哪个服务（warehouse / trade / reporting / channel / platform）
--     fact_table     —— **真表名**（inventory_balance / purchase_analytics_fact /
--                       order_timeliness_fact / reporting_sales_trend_fact /
--                       ad_campaign_daily_metric / platform_api_call_log / …）
--     fact_field     —— **真字段名**（available / unit_price / ship_overdue_seconds / acos / …）
--     fact_value     —— **数值**（NUMERIC(19,6)，禁浮点；文字型取值写 fact_value_text）
--     snapshot_at    —— **快照时间**：读到这条事实的时刻
--     fact_ref       —— 下钻引用（主键 / 单号 / 维度键）：拿着它能回到那条事实行
--
-- 与 18.4.2.2 的 `platform_forecast_factor` 同一纪律，但更严一格：
-- 因素表存的是「这个数是多少 + 口径说明」，依据表存的是「这个数是**哪张表哪个字段**的那一行」。
--
-- 🔴 **每条结论至少有一条依据**：服务层只输出**有事实支撑**的结论，
--   读不到事实 ⇒ 那一条**根本不出结论**，而不是出一条没有依据的结论。
--   结论与依据在一对多关系里被结构上保证（conclusion_id 外键 + 同一事务写入）。
--
-- ---------------------------------------------------------------------------
-- 3. 时间与数值
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 **timestamptz**；服务层用 **OffsetDateTime** 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--   * 数量与金额一律 **numeric(19,6)**（BigDecimal），比率一律 **numeric(9,6)**。
--   * 百分比**不存浮点**：要么存比率（numeric(9,6)），要么存**百分比文本**
--     （如 "+18.4%"，impact_text 列）。**本文件没有一个 double / real / float。**
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_diagnosis_event —— 异常事件
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「发生了异常」的事实。
--
-- 🔴 **来源只有两条**（工作台待办表与各域异常事件表不存在）：
--   SUBMITTED        —— 调用方通过端点提交（租户自己的业务事实）
--   PLATFORM_INTERNAL —— 从平台自己的失败源（V413 / V414 / V422）聚合
--
-- 🔴 **同一异常不重复登记**：source='PLATFORM_INTERNAL' 时用
--   (tenant_id, source, source_ref) 做幂等键 —— 同一个失败源事实只登记一次，
--   重复同步不会把同一件事刷成几十行（与 V414 platform_api_call_alert 同一纪律）。
--
CREATE TABLE platform_diagnosis_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 人可读的单号（租户内唯一）：EVT-<租户前 8 位>-<6 位序号>。
  event_no varchar(32) NOT NULL,

  -- =========================================================================
  -- 异常类别：蓝图点名的五类
  -- =========================================================================
  anomaly_type text NOT NULL
    CHECK(anomaly_type IN (
      'INVENTORY',      -- 库存异常
      'PROFIT',         -- 利润异常
      'SHIPPING_DELAY', -- 发货延迟
      'SYNC_FAILURE',   -- 同步失败
      'AD_LOSS'         -- 广告亏损
    )),

  -- 异常对象：诊断要拿它去对事实（SKU 码 / 订单号 / 活动 id / 订阅 id / 接口码 / 规则 id）。
  subject_type text NOT NULL
    CHECK(subject_type IN ('SKU','ORDER','CAMPAIGN','SUBSCRIPTION','APP','ENDPOINT','RULE')),
  subject_ref varchar(200) NOT NULL,
  subject_label varchar(200) NOT NULL DEFAULT '',

  -- 异常发生时刻（业务事实的时刻）与被本模块发现 / 登记的时刻。
  occurred_at timestamptz NOT NULL,
  detected_at timestamptz NOT NULL DEFAULT now(),

  -- =========================================================================
  -- 来源
  -- =========================================================================
  source text NOT NULL CHECK(source IN ('SUBMITTED','PLATFORM_INTERNAL')),
  -- 来源定位键：
  --   SUBMITTED        —— 调用方自填（如「运营手工登记的断货」）
  --   PLATFORM_INTERNAL —— 失败源事实的定位键（如 platform_delivery_record.id）
  source_ref varchar(200) NOT NULL DEFAULT '',

  -- 上报 / 聚合出来的度量值（如超时秒数、ACOS、缺口件数）。**可空**：
  -- 有些异常本身就是「某个状态」而不是「某个数」（如投递进了死信）。
  reported_value numeric(19,6),
  reported_unit varchar(24) NOT NULL DEFAULT '',

  -- 严重度。调用方提交时可以自报；平台内部失败源聚合时由聚合口径给出。
  severity text NOT NULL DEFAULT 'WARNING'
    CHECK(severity IN ('INFO','WARNING','CRITICAL')),

  -- 状态机：OPEN → DIAGNOSED → RESOLVED。
  --   OPEN      已登记，还没诊断
  --   DIAGNOSED 已诊断过（至少有一次运行落库）
  --   RESOLVED  人工标记为已处置
  -- 🔴 **没有 EXECUTED**：处置动作的执行归各自的域（本模块**不**改订单、不停广告、不补货）。
  status text NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','DIAGNOSED','RESOLVED')),

  note varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, event_no),

  CHECK(btrim(event_no) <> '' AND length(event_no) <= 32),
  CHECK(btrim(subject_ref) <> '' AND length(subject_ref) <= 200),
  CHECK(length(subject_label) <= 200),
  CHECK(length(source_ref) <= 200),
  CHECK(reported_value IS NULL OR reported_value >= 0),
  CHECK(length(reported_unit) <= 24),
  CHECK(length(note) <= 500),
  -- 人工处置必须留一句话：不然「为什么关掉」无从追问。
  CHECK(status <> 'RESOLVED' OR btrim(note) <> '')
);

CREATE INDEX platform_diagnosis_event_by_time
  ON platform_diagnosis_event(tenant_id, occurred_at DESC, id DESC);

CREATE INDEX platform_diagnosis_event_by_type
  ON platform_diagnosis_event(tenant_id, anomaly_type, status, occurred_at DESC);

-- 幂等：平台内部失败源聚合出来的同一件事只登记一次。
CREATE UNIQUE INDEX platform_diagnosis_event_internal_once
  ON platform_diagnosis_event(tenant_id, source, source_ref)
  WHERE source = 'PLATFORM_INTERNAL' AND source_ref <> '';

-- 发号序列（人可读单号）。
CREATE SEQUENCE platform_diagnosis_event_no_seq;

-- ---------------------------------------------------------------------------
-- 2. platform_diagnosis_run —— 一次诊断运行
-- ---------------------------------------------------------------------------
--
-- 一行 = 对某一个异常事件跑了一次归因。
--
-- 🔴 **参数与连通性随运行落库**（而不是读全局配置）：否则「这次诊断为什么没给出结论」
--   在事后无从回答 —— 到底是规则没命中，还是那一格根本没读出来。
--
CREATE TABLE platform_diagnosis_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 人可读的单号（租户内唯一）：DG-<租户前 8 位>-<6 位序号>。
  run_no varchar(32) NOT NULL,

  -- 被诊断的事件（同一租户内引用）。
  event_id uuid NOT NULL,

  -- 状态机：PENDING → RUNNING → COMPLETED / FAILED。
  status text NOT NULL DEFAULT 'RUNNING'
    CHECK(status IN ('PENDING','RUNNING','COMPLETED','FAILED')),

  -- 🔴 **规则集版本**：归因口径是会演进的（阈值会调）。
  --   落库当时用的版本号 ⇒ 半年后回看，规则改了也能说清「当时按哪一版判的」。
  rule_version varchar(32) NOT NULL,

  -- 本次运行产出的结论条数与依据条数。
  conclusion_count int NOT NULL DEFAULT 0 CHECK(conclusion_count >= 0),
  evidence_count int NOT NULL DEFAULT 0 CHECK(evidence_count >= 0),

  -- 🔴 **哪几格读到了 / 哪几格读不到**（逗号分隔的事实源码）。
  --   读不到的一律如实写出来 —— 「没有结论」和「读不到」是两件事，页面要分开显示。
  sources_read varchar(500) NOT NULL DEFAULT '',
  sources_unavailable varchar(500) NOT NULL DEFAULT '',

  -- 这次运行的一句话结论说明（为什么没结论 / 结论概要）。
  note varchar(500) NOT NULL DEFAULT '',

  requested_by uuid,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, run_no),

  FOREIGN KEY(tenant_id, event_id) REFERENCES platform_diagnosis_event(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(run_no) <> '' AND length(run_no) <= 32),
  CHECK(btrim(rule_version) <> '' AND length(rule_version) <= 32),
  CHECK(length(sources_read) <= 500),
  CHECK(length(sources_unavailable) <= 500),
  CHECK(length(note) <= 500),
  -- 终态必须有完成时刻。
  CHECK(status <> 'COMPLETED' OR finished_at IS NOT NULL),
  -- 依据条数不得少于结论条数：每条结论至少一条依据。
  CHECK(evidence_count >= conclusion_count)
);

CREATE INDEX platform_diagnosis_run_by_event
  ON platform_diagnosis_run(tenant_id, event_id, started_at DESC);

CREATE INDEX platform_diagnosis_run_by_started
  ON platform_diagnosis_run(tenant_id, started_at DESC);

-- 发号序列（人可读单号）。
CREATE SEQUENCE platform_diagnosis_run_no_seq;

-- ---------------------------------------------------------------------------
-- 3. platform_diagnosis_conclusion —— 一条归因结论
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个候选原因。蓝图输出句「诊断结论 + 依据 + 建议动作」的三件套里，
-- 「结论」与「建议动作」落在这一张表，「依据」落在下一张表。
--
-- 🔴 **结论必须有依据**：conclusion_id 与 evidence.conclusion_id 一一对应，
--   服务层只写有事实支撑的结论（读不到事实 ⇒ 不出结论）。
--
-- 🔴 **evidence_level 说的是「依据有多硬」，不是「我有多确信」**：
--   STRONG       —— 多条独立事实指向同一原因
--   WEAK         —— 只有一条事实指向
--   INSUFFICIENT —— 事实读到了但不足以支撑归因（例如只有状态、没有量值）
--   **不写「置信度 87%」这种没有依据的数** —— 本仓没有可校准的置信度来源。
--
CREATE TABLE platform_diagnosis_conclusion (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  run_id uuid NOT NULL,
  event_id uuid NOT NULL,

  -- 规则码（与 /capabilities 里报出的口径一一对应）与规则名。
  rule_code varchar(64) NOT NULL,
  rule_label varchar(120) NOT NULL,

  anomaly_type text NOT NULL
    CHECK(anomaly_type IN ('INVENTORY','PROFIT','SHIPPING_DELAY','SYNC_FAILURE','AD_LOSS')),
  severity text NOT NULL CHECK(severity IN ('INFO','WARNING','CRITICAL')),

  -- 结论针对的对象（一般是事件的 subject）。
  subject_ref varchar(200) NOT NULL DEFAULT '',

  -- 结论一句话 + 详述（详述可为空字符串，但**不能省掉字段**）。
  summary varchar(500) NOT NULL,
  detail varchar(1000) NOT NULL DEFAULT '',

  -- 依据硬度（见上）。
  evidence_level text NOT NULL DEFAULT 'WEAK'
    CHECK(evidence_level IN ('STRONG','WEAK','INSUFFICIENT')),

  -- 量化影响：数值（数量 / 金额 / 秒数）+ 单位。
  impact numeric(19,6),
  impact_unit varchar(24) NOT NULL DEFAULT '',
  -- 🔴 **百分比用文本**（如 "+18.4%"）：禁浮点，也不假装这是一个可计算的精确比率。
  impact_text varchar(64) NOT NULL DEFAULT '',

  -- =========================================================================
  -- 处置建议
  -- =========================================================================
  -- 「建议」写清楚**做什么**；action_ref 指向**对应处理页**（蓝图关系句的下游）；
  -- action_owner 点名**归谁**（本模块**不执行**任何一个动作）。
  recommendation varchar(1000) NOT NULL,
  action_ref varchar(200) NOT NULL DEFAULT '',
  action_owner text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, run_id) REFERENCES platform_diagnosis_run(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, event_id) REFERENCES platform_diagnosis_event(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(rule_code) <> '' AND length(rule_code) <= 64),
  CHECK(length(rule_label) <= 120),
  CHECK(length(subject_ref) <= 200),
  CHECK(btrim(summary) <> '' AND length(summary) <= 500),
  CHECK(length(detail) <= 1000),
  CHECK(impact IS NULL OR impact >= 0),
  CHECK(length(impact_unit) <= 24),
  CHECK(length(impact_text) <= 64),
  -- 🔴 **处置建议不能省**：蓝图输出句里「建议动作」与「结论」是并列的三件套之一。
  CHECK(btrim(recommendation) <> '' AND length(recommendation) <= 1000),
  CHECK(length(action_ref) <= 200),
  CHECK(length(action_owner) <= 64)
);

CREATE INDEX platform_diagnosis_conclusion_by_run
  ON platform_diagnosis_conclusion(tenant_id, run_id, rule_code);

CREATE INDEX platform_diagnosis_conclusion_by_event
  ON platform_diagnosis_conclusion(tenant_id, event_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. platform_diagnosis_evidence —— 一条依据 ★
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条**可下钻**的支撑事实。蓝图加粗句「诊断要给依据」的落点。
--
-- 四件套（服务 / 表 / 字段 / 值）+ 快照时间 + 下钻引用 + 口径说明。
-- 见本文件第 2 节。
--
-- 🔴 **snapshot_at 是必填**：没有快照时间的「依据」无法回答「这个结论是什么时候算的」。
--
-- 🔴 **fact_occurred_at 可空**：有些事实没有「发生时刻」（例如一条策略阈值本身）。
--
CREATE TABLE platform_diagnosis_evidence (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 所属结论（同一租户内引用）。
  conclusion_id uuid NOT NULL,
  run_id uuid NOT NULL,
  event_id uuid NOT NULL,

  -- =========================================================================
  -- 🔴 事实定位四件套
  -- =========================================================================
  -- 事实住在哪个服务。
  fact_service varchar(32) NOT NULL
    CHECK(fact_service IN ('warehouse','trade','reporting','channel','platform')),
  -- **真表名**（下钻的第一跳）。
  fact_table varchar(64) NOT NULL,
  -- **真字段名**（下钻的第二跳）。
  fact_field varchar(64) NOT NULL,
  -- 数值（NUMERIC(19,6)，禁浮点）。文字型取值写 fact_value_text。
  fact_value numeric(19,6),
  -- 文字型取值（如 ship_sla_state = 'OVERDUE'）。
  fact_value_text varchar(200) NOT NULL DEFAULT '',
  fact_unit varchar(24) NOT NULL DEFAULT '',

  -- 下钻引用：拿着它能回到那条事实行（主键 / 单号 / 维度键）。
  fact_ref varchar(200) NOT NULL DEFAULT '',

  -- 事实发生的时刻（可空：有些事实没有发生时刻）。
  fact_occurred_at timestamptz,
  -- 🔴 **快照时间**：读到这条事实的时刻。
  snapshot_at timestamptz NOT NULL,

  -- 🔴 **口径说明**：这个数是怎么来的、为什么它能支撑这条结论。页面直接显示。
  explanation varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, conclusion_id) REFERENCES platform_diagnosis_conclusion(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, run_id) REFERENCES platform_diagnosis_run(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, event_id) REFERENCES platform_diagnosis_event(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(fact_service) <> '' AND length(fact_service) <= 32),
  CHECK(btrim(fact_table) <> '' AND length(fact_table) <= 64),
  CHECK(btrim(fact_field) <> '' AND length(fact_field) <= 64),
  -- 🔴 **数值与文字至少有一个**：两个都空的「依据」什么也证明不了。
  CHECK(fact_value IS NOT NULL OR btrim(fact_value_text) <> ''),
  CHECK(length(fact_value_text) <= 200),
  CHECK(length(fact_unit) <= 24),
  CHECK(length(fact_ref) <= 200),
  CHECK(length(explanation) <= 500)
);

CREATE INDEX platform_diagnosis_evidence_by_conclusion
  ON platform_diagnosis_evidence(tenant_id, conclusion_id);

CREATE INDEX platform_diagnosis_evidence_by_run
  ON platform_diagnosis_evidence(tenant_id, run_id);

-- 按事实表 + 字段建索引：页面要能「按事实源反查哪些结论引用了它」。
CREATE INDEX platform_diagnosis_evidence_by_fact
  ON platform_diagnosis_evidence(tenant_id, fact_table, fact_field);

-- ===========================================================================
-- 5. 租户隔离（RLS）
-- ===========================================================================
--
-- 四张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V380 / V424 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
ALTER TABLE platform_diagnosis_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_diagnosis_event FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_diagnosis_event
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_diagnosis_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_diagnosis_run FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_diagnosis_run
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_diagnosis_conclusion ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_diagnosis_conclusion FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_diagnosis_conclusion
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_diagnosis_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_diagnosis_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_diagnosis_evidence
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 6. BEFORE INSERT / UPDATE 触发器兜底（租户上下文）
-- ===========================================================================
--
-- 🔴 RLS 的 WITH CHECK 已经挡住「写入的 tenant_id 不是当前租户」；
--   但它报出来的错是「违反行级安全策略」，看不出**是哪个租户**对不上。
--   触发器给出可读的错误（identity V384 的 alert_rule_tenant_guard 同一纪律）。
--
-- 🔴 **没有租户上下文时直接拒绝写入**：租户不是客户端自称的。
--
CREATE FUNCTION platform_diagnosis_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入异常诊断的数据'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '异常诊断数据的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION platform_diagnosis_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_diagnosis_tenant_guard() TO platform_app;

CREATE TRIGGER platform_diagnosis_event_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_diagnosis_event
  FOR EACH ROW EXECUTE FUNCTION platform_diagnosis_tenant_guard();

CREATE TRIGGER platform_diagnosis_run_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_diagnosis_run
  FOR EACH ROW EXECUTE FUNCTION platform_diagnosis_tenant_guard();

CREATE TRIGGER platform_diagnosis_conclusion_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_diagnosis_conclusion
  FOR EACH ROW EXECUTE FUNCTION platform_diagnosis_tenant_guard();

CREATE TRIGGER platform_diagnosis_evidence_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_diagnosis_evidence
  FOR EACH ROW EXECUTE FUNCTION platform_diagnosis_tenant_guard();

-- ===========================================================================
-- 7. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的诊断台账，能写（登记异常事件、跑诊断、
-- 落结论与依据、同步内部失败源、人工标记已处置），能改（事件状态与备注）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_diagnosis_event TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_diagnosis_run TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_diagnosis_conclusion TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_diagnosis_evidence TO platform_app;

-- 发号序列的取号权限：没有它，服务层拿不到单号。
GRANT USAGE, SELECT ON SEQUENCE platform_diagnosis_event_no_seq TO platform_app;
GRANT USAGE, SELECT ON SEQUENCE platform_diagnosis_run_no_seq TO platform_app;
