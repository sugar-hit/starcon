-- ===========================================================================
-- V438 · platform_assistant —— 18.4.1.1「对话助手」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.4.1.1 原文：
--
--   「**职责**：自然语言助手：用中文提问业务问题（"上周哪个店利润最差""哪些 SKU 该补货了"），
--     返回答案并附数据来源链接；支持追问与上下文延续。
--     **输入**：用户问题；当前上下文（租户、数据范围、当前页面）。
--     **输出**：答案 + 数据明细链接 + 建议的下一步操作。
--     **关系**：上游 = 各域只读查询接口；下游 = 无（只读）。
--     **答案必须附明细链接**——不给依据的 AI 数字不能用于决策。」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不把任意自然语言直译成任意 SQL**。这是 18.4.1.2「智能查询」的边界。
--     本模块只认**受控意图目录**（platform_assistant_intent，≤ 8 条）里的问题形态，
--     每条形态对应**一个写死的只读查询**（上游端点 + 参数）——
--     所以「上周哪个店利润最差」永远走「店铺对比矩阵」那一条查询，
--     而不是让模型临时拼一条 SQL 出来。
--
--   * **不假设有 AI 供应商**。本仓没有任何真实的 LLM 适配器（openai / anthropic / qwen
--     一个都没有，见 AiTextGenerationPort 与 18.4.2.1 V380 的同一判断）
--     ⇒ 自由问句路径**如实答「AI 生成通道未接通 + 缺什么 + 归谁」**，
--     **一次数据都不写**，**绝不返回任何看似成功的答案**。
--
--   * **不跨库读表**。所有事实来自 reporting / finance / trade / crm 的**只读 HTTP 端点**
--     （转发调用方自己的 bearer，租户由上游按已认证成员关系解析）。
--     本模块自己的四张表只放：意图目录、会话上下文、问答台账、**明细依据**。
--
--   * **不碰 platform_notify_* / /platform/notifications**（那是 18.5.2.1 的地盘）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_assistant_intent   —— **受控意图目录**（≤ 8 条；问题形态 → 只读查询 → 答案 + 明细链接）
--   platform_assistant_session  —— **会话上下文**（追问与上下文延续的载体）
--   platform_assistant_turn     —— 一轮问答台账（问句 / 命中意图 / 答案 / 口径与时间范围）
--   platform_assistant_evidence —— **一条明细依据**（真实记录 + 可点的明细链接）★
--
-- 四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 「答案必须附明细链接」怎么落成结构
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句「**答案必须附明细链接**——不给依据的 AI 数字不能用于决策」。
-- 只把链接塞进一段说明文字，等于「不可下钻的结论」：事后没人能回答
-- 「这个 -18.4% 是哪一行算出来的」。
--
-- ⇒ **明细链接是一张表，不是一个字符串字段**：
--   platform_assistant_evidence 的每一行都带**四件套 + 一个能点的路由**：
--
--     fact_service   —— 事实住在哪个服务（reporting / finance / trade / crm）
--     fact_table     —— **真表名**（下钻的第一跳）
--     fact_field     —— **真字段名**（下钻的第二跳）
--     fact_ref       —— 下钻引用（店铺码 / SKU 码 / 订单号 / 客户引用）
--     detail_route   —— **前端真实路由**（不是假链接、不是占位 URL）
--     snapshot_at    —— 读到这条事实的时刻
--
-- 🔴 **服务层只输出有明细依据的答案**：读不到事实 ⇒ 那一条**根本不出结论**，
--   而不是出一条没有依据的结论（与 18.4.2.3 V429 的依据表同一纪律）。
--
-- ---------------------------------------------------------------------------
-- 3. 时间与数值
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 **timestamptz**；服务层用 **OffsetDateTime** 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--   * 数量与金额一律 **numeric(19,6)**（BigDecimal），比率一律 **numeric(7,4)**。
--     **本文件没有一个 double / real / float。**
--   * 🔴 **相对时间（上周 / 本月）用 DB 的 now() 算**：服务层不拿 JVM 墙钟和 DB 时钟比大小
--     （跨时钟域比较会产出恒定的假红）。窗口的起止日由一条 `now() at time zone 'UTC'` 取出来，
--     再在 Java 里按同一个「今天」推导。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_assistant_intent —— 受控意图目录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个**受控**业务意图。
--
-- 🔴 **每个租户一份**：意图目录是租户可审计的资产（谁被允许问什么、问到的数据来自哪里、
--   明细链接指向哪个页面），所以它是**业务表** ⇒ 必须带 tenant_id 并受 RLS 约束。
--   首次访问时由服务层按内置目录**幂等落库**（ON CONFLICT DO NOTHING）。
--
-- 🔴 **match_keywords 是封闭词表**：意图识别是**确定性关键词匹配**，
--   不是「模型自由理解」—— 自由问句走 AI 端口并如实答「未接通」。
--   match_keywords 的每一项都是中文关键词（店铺 / 利润 / 补货 / 库存 / 卖得最好 / 现金流 /
--   流失 / 待发货 …），服务层按「命中词最多」选出意图；一个都没命中 ⇒ 走自由问句路径。
--
-- 🔴 **next_actions 里放的是前端**真实存在**的路由**（如 /analytics/inventory/analysis）。
--   路由写错 ⇒ 点了 404 ⇒ 比不给建议更糟。
--
CREATE TABLE platform_assistant_intent (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 意图码（封闭词表；服务层按它路由到那条只读查询）。
  intent_code varchar(64) NOT NULL,
  -- 意图名（页面上显示）。
  intent_name varchar(120) NOT NULL,
  -- 问题形态示例（帮助用户知道能怎么问；不是自由文本入口）。
  question_examples text[] NOT NULL DEFAULT '{}'::text[],
  -- 命中关键词（封闭词表；确定性匹配用）。
  match_keywords text[] NOT NULL DEFAULT '{}'::text[],

  -- =========================================================================
  -- 数据源：**只读**上游（服务名 + 真实端点路径）
  -- =========================================================================
  source_service varchar(32) NOT NULL
    CHECK(source_service IN ('reporting','finance','trade','crm')),
  source_endpoint varchar(200) NOT NULL,
  -- 🔴 **口径说明**：这个答案的数字是怎么来的、时间范围是什么、哪些格子算不出来。
  --   答案里必须带它（蓝图「答案 + 数据明细链接 + 建议的下一步」三件套之外的口径要求）。
  source_caliber varchar(500) NOT NULL DEFAULT '',
  -- 明细链接落到的**前端真实路由**（相对路径）。
  detail_route varchar(200) NOT NULL DEFAULT '',

  -- 建议的下一步操作：[{label, route, moduleId}]，1–3 条。
  -- 🔴 route 必须落在前端**已存在**的页面上。
  next_actions jsonb NOT NULL DEFAULT '[]'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, intent_code),

  CHECK(btrim(intent_code) <> '' AND length(intent_code) <= 64),
  CHECK(btrim(intent_name) <> '' AND length(intent_name) <= 120),
  -- 🔴 **关键词不能为空**：没有关键词的意图永远命中不了 ⇒ 是个死条目。
  CHECK(cardinality(match_keywords) > 0),
  CHECK(btrim(source_endpoint) <> '' AND length(source_endpoint) <= 200),
  CHECK(length(source_caliber) <= 500),
  CHECK(length(detail_route) <= 200),
  CHECK(jsonb_typeof(next_actions) = 'array')
);

CREATE INDEX platform_assistant_intent_by_code
  ON platform_assistant_intent(tenant_id, intent_code);

-- ---------------------------------------------------------------------------
-- 2. platform_assistant_session —— 会话上下文
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个**会话**的当前上下文。
--
-- 🔴 **追问与上下文延续就落在这张表**：
--   「那第二名呢」  → 沿用上一轮的 intent_code 与口径，只换排序位次；
--   「换成上周」    → 沿用上一轮的 intent_code 与数据范围，只换时间窗口；
--   其它维度（店铺 / 数据范围）**没有被显式修改就沿用上一轮**。
--
-- 🔴 **租户不可从客户端传入**：会话 id 由服务端生成（uuid），
--   且每张会话行的 tenant_id 由 RLS + 触发器强制等于当前租户上下文。
--   拿到别人的会话 id 也读不到 —— RLS 直接挡住。
--
-- 🔴 period_from / period_to 是**已解析**的窗口（不是「上周」这种词）：
--   相对时间用 DB 的 now() 算出来再落库（见文件头第 3 节）。
--
CREATE TABLE platform_assistant_session (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 上一轮识别到的意图（空串 = 还没识别成功过）。
  intent_code varchar(64) NOT NULL DEFAULT '',
  -- 上一轮的店铺（空串 = 未限定店铺）。
  shop_code varchar(64) NOT NULL DEFAULT '',
  -- 数据范围（COMPANY / LEGAL_ENTITY / SHOP）。
  scope_type varchar(32) NOT NULL DEFAULT 'COMPANY'
    CHECK(scope_type IN ('COMPANY','LEGAL_ENTITY','SHOP')),
  -- 时间范围代号（LAST_7_DAYS / LAST_30_DAYS / LAST_WEEK / THIS_MONTH）。
  period_code varchar(32) NOT NULL DEFAULT 'LAST_30_DAYS'
    CHECK(period_code IN ('LAST_7_DAYS','LAST_30_DAYS','LAST_WEEK','THIS_MONTH')),
  -- 已解析的时间窗口（相对时间的解析结果）。
  period_from date NOT NULL,
  period_to date NOT NULL,

  -- 上一轮的问句（回显用）。
  last_question varchar(500) NOT NULL DEFAULT '',
  -- 已完成的轮数。
  turn_count int NOT NULL DEFAULT 0,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(length(intent_code) <= 64),
  CHECK(length(shop_code) <= 64),
  CHECK(length(last_question) <= 500),
  CHECK(turn_count >= 0),
  CHECK(period_from <= period_to)
);

CREATE INDEX platform_assistant_session_by_recent
  ON platform_assistant_session(tenant_id, updated_at DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_assistant_turn —— 一轮问答台账
-- ---------------------------------------------------------------------------
--
-- 一行 = 一轮「问句 → 答案」。
--
-- 🔴 **match_mode 如实记录这一轮是怎么命中的**：
--   KEYWORD    —— 命中受控目录的关键词（真走了只读查询）
--   FOLLOW_UP  —— 追问（沿用上一轮意图与口径）
--   FREE_TEXT  —— 自由问句（**AI 通道未接通 ⇒ 如实答「未接通」，没有答案**）
--   一轮里没有出结论时，answer_summary 写明「为什么没出结论」，**不写一个像样的答案**。
--
CREATE TABLE platform_assistant_turn (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  session_id uuid NOT NULL,
  -- 会话内递增的轮号（从 1 开始）。
  turn_no int NOT NULL,

  -- 问句原文（截断到 1000 字符）。
  question_text varchar(1000) NOT NULL,
  -- 命中的意图（FREE_TEXT 时为空串）。
  intent_code varchar(64) NOT NULL DEFAULT '',
  intent_name varchar(120) NOT NULL DEFAULT '',
  match_mode varchar(32) NOT NULL
    CHECK(match_mode IN ('KEYWORD','FOLLOW_UP','FREE_TEXT')),

  -- 答案（一句话结论）。
  answer_summary varchar(1000) NOT NULL,
  -- 🔴 **口径与时间范围**：答案里必须带（数字从哪来、算的是哪一段）。
  answer_scope varchar(500) NOT NULL DEFAULT '',
  -- 本轮的时间窗口。
  period_from date NOT NULL,
  period_to date NOT NULL,

  -- 事实来自哪个上游的哪个端点（FREE_TEXT 时为空串）。
  source_service varchar(32) NOT NULL DEFAULT '',
  source_endpoint varchar(200) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, session_id, turn_no),

  FOREIGN KEY (tenant_id, session_id)
    REFERENCES platform_assistant_session(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(question_text) <> '' AND length(question_text) <= 1000),
  CHECK(length(intent_code) <= 64),
  CHECK(length(intent_name) <= 120),
  CHECK(btrim(answer_summary) <> '' AND length(answer_summary) <= 1000),
  CHECK(length(answer_scope) <= 500),
  CHECK(source_service = '' OR source_service IN ('reporting','finance','trade','crm')),
  CHECK(length(source_endpoint) <= 200),
  CHECK(period_from <= period_to)
);

CREATE INDEX platform_assistant_turn_by_session
  ON platform_assistant_turn(tenant_id, session_id, turn_no);

CREATE INDEX platform_assistant_turn_by_created
  ON platform_assistant_turn(tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. platform_assistant_evidence —— 一条明细依据 ★
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条**可下钻**的事实。蓝图加粗句「答案必须附明细链接」的落点。
--
-- 四件套（服务 / 表 / 字段 / 值）+ 下钻引用 + **可点的明细路由** + 口径说明 + 快照时间。
--
-- 🔴 **每条答案至少有一条依据**：服务层只输出**有事实支撑**的答案，
--   读不到事实 ⇒ 那一轮**根本不出结论**，而是如实说明「哪一格读不到」。
--
-- 🔴 **metric_value 是 numeric(19,6)**：禁浮点；算不出来的值是 NULL（不是 0）。
--
CREATE TABLE platform_assistant_evidence (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  turn_id uuid NOT NULL,
  session_id uuid NOT NULL,
  -- 轮内序号（从 1 开始；对应答案里的第几条明细）。
  seq int NOT NULL,

  -- =========================================================================
  -- 🔴 事实定位四件套
  -- =========================================================================
  fact_service varchar(32) NOT NULL
    CHECK(fact_service IN ('reporting','finance','trade','crm')),
  -- **真表名**（下钻的第一跳）。
  fact_table varchar(64) NOT NULL,
  -- **真字段名**（下钻的第二跳）。
  fact_field varchar(64) NOT NULL,
  -- 下钻引用（店铺码 / SKU 码 / 订单号 / 客户引用）。
  fact_ref varchar(200) NOT NULL DEFAULT '',
  -- 人可读的标签（页面上显示）。
  fact_label varchar(200) NOT NULL DEFAULT '',

  -- 指标码 + 数值 + 单位（金额与数量一律 BigDecimal / NUMERIC）。
  metric_code varchar(64) NOT NULL DEFAULT '',
  metric_value numeric(19,6),
  metric_unit varchar(24) NOT NULL DEFAULT '',

  -- =========================================================================
  -- 🔴 明细链接：指向**前端真实存在**的页面
  -- =========================================================================
  detail_route varchar(200) NOT NULL,
  detail_label varchar(200) NOT NULL DEFAULT '',

  -- 口径说明（这一格是怎么算出来的）。
  caliber_note varchar(500) NOT NULL DEFAULT '',

  -- 🔴 **快照时间**：读到这条事实的时刻。
  snapshot_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, turn_id, seq),

  FOREIGN KEY (tenant_id, turn_id)
    REFERENCES platform_assistant_turn(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(fact_table) <> '' AND length(fact_table) <= 64),
  CHECK(btrim(fact_field) <> '' AND length(fact_field) <= 64),
  CHECK(length(fact_ref) <= 200),
  CHECK(length(fact_label) <= 200),
  CHECK(length(metric_code) <= 64),
  CHECK(length(metric_unit) <= 24),
  -- 🔴 **明细路由不能为空**：没有可点的明细链接 ⇒ 这一条不该作为依据存在。
  CHECK(btrim(detail_route) <> '' AND length(detail_route) <= 200),
  CHECK(length(detail_label) <= 200),
  CHECK(length(caliber_note) <= 500)
);

CREATE INDEX platform_assistant_evidence_by_turn
  ON platform_assistant_evidence(tenant_id, turn_id, seq);

-- 按事实源反查（哪个答案引用了这条事实）。
CREATE INDEX platform_assistant_evidence_by_fact
  ON platform_assistant_evidence(tenant_id, fact_service, fact_table);

-- ===========================================================================
-- 5. 租户隔离（RLS）
-- ===========================================================================
--
-- 四张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V380 / V429 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
ALTER TABLE platform_assistant_intent ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_assistant_intent FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_assistant_intent
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_assistant_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_assistant_session FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_assistant_session
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_assistant_turn ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_assistant_turn FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_assistant_turn
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_assistant_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_assistant_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_assistant_evidence
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 6. BEFORE INSERT / UPDATE 触发器兜底（租户上下文）
-- ===========================================================================
--
-- 🔴 RLS 的 WITH CHECK 已经挡住「写入的 tenant_id 不是当前租户」；
--   但它报出来的错是「违反行级安全策略」，看不出**是哪个租户**对不上。
--   触发器给出可读的错误（platform V429 的 diagnosis_tenant_guard 同一纪律）。
--
-- 🔴 **没有租户上下文时直接拒绝写入**：租户不是客户端自称的。
--
CREATE FUNCTION platform_assistant_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入对话助手的数据'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '对话助手数据的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION platform_assistant_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_assistant_tenant_guard() TO platform_app;

CREATE TRIGGER platform_assistant_intent_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_assistant_intent
  FOR EACH ROW EXECUTE FUNCTION platform_assistant_tenant_guard();

CREATE TRIGGER platform_assistant_session_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_assistant_session
  FOR EACH ROW EXECUTE FUNCTION platform_assistant_tenant_guard();

CREATE TRIGGER platform_assistant_turn_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_assistant_turn
  FOR EACH ROW EXECUTE FUNCTION platform_assistant_tenant_guard();

CREATE TRIGGER platform_assistant_evidence_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_assistant_evidence
  FOR EACH ROW EXECUTE FUNCTION platform_assistant_tenant_guard();

-- ===========================================================================
-- 7. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的意图目录与问答台账，
-- 能写（幂等落目录、开会话、记一轮问答、落明细依据）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_assistant_intent TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_assistant_session TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_assistant_turn TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_assistant_evidence TO platform_app;
