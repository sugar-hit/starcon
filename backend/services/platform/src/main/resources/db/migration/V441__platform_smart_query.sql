-- ===========================================================================
-- V441 · platform_smart_query —— 18.4.1.2「智能查询」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.4.1.2 原文：
--
--   「**职责**：结构化智能查询：自然语言转查询条件（筛选、聚合、排序），
--     生成报表草稿，支持保存为常用查询。
--     **输入**：自然语言查询意图；可用指标与维度清单。
--     **输出**：查询结果表 / 报表配置草稿。
--     **关系**：上游 = 18.4.1.1；下游 = 13.6.1.1 自定义报表。
--     **自然语言只映射到受控语义层指标，不生成任意 SQL。**」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不把任意自然语言直译成任意 SQL**。这是蓝图加粗句「自然语言只映射到
--     受控语义层指标，不生成任意 SQL」的物理边界：解析结果只能落到本文件建的
--     两张**封闭词表**（platform_smart_query_metric / platform_smart_query_dimension）
--     里的条目上；**用户输入永远不进 SQL 文本**，只作为绑定参数传给预编译 SQL。
--     本仓**没有**「自由 SQL」入口，也**没有**任何「让模型生成 SQL」的通道。
--
--   * **不重算口径**。指标口径的唯一真相是 13.1.1.1 的
--     `reporting_metric_definition`（reporting 服务），维度白名单的唯一真相是
--     13.6.1.1 的 5 个维度码（`ReportingCustomReportCatalog.Dimension`）。
--     本模块**只读**那两份目录（跨服务只走授权 HTTP API），执行也**委托**给
--     13.6.1.1 的查询引擎 ⇒ 口径不会分裂成第二个版本。
--
--   * **不跨库读表**。本模块自己的四张表只放：语义层词表、查询台账、
--     明细依据（查询结果表的每一行都要能下钻）。
--
--   * **不假设有 AI 供应商**。本仓没有任何真实的 LLM 适配器 ⇒ 问句解析是
--     **封闭词表 + 确定性打分**（强词权重 3 / 弱词权重 1），不是模型自由理解；
--     解析不到受控条目时如实答「不支持这个问法 + 缺什么 + 归谁」，
--     **不返回任何看似成功的数字**。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_smart_query_metric     —— **受控语义层：指标目录**（封闭词表）
--   platform_smart_query_dimension  —— **受控语义层：维度目录**（封闭词表）
--   platform_smart_query            —— **查询台账 + 常用查询**（一行 = 一次查询）
--   platform_smart_query_evidence   —— **一条明细依据**（结果表的每一行）★
--
-- 四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 「自然语言只映射到受控语义层指标」怎么落成结构
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句与 18.4.1.1 的「答案必须附明细链接」是同一条纪律的两面：
-- 说出来的指标**必须**是受控词表里的那一个，说出来的数字**必须**能下钻回真表。
--
-- ⇒ ① 指标与维度各是一张**封闭词表表**（不是 JSON 列、不是自由文本）：
--      每一行都带 `strong_keywords` / `weak_keywords`（确定性匹配用）、
--      `owner_module`（口径归谁）、`source_service` / `source_endpoint`
--      （真实可调用的只读端点）、`source_caliber`（口径说明）。
--
--    ② 查询台账里的 `metric_code` / `dimension_code` 都带 CHECK 约束 +
--      外键到同租户的词表行 ⇒ **表层面就不可能记下一个词表外的指标**。
--
--    ③ `resolved=false` 的行**不带** `report_id`：没解析出来就没有报表草稿，
--      也就没有结果、没有数字 —— 「不出结论」在数据层是可查的。
--
--    ④ 明细依据是一张表：事实服务 / **真表名**（reporting_metric_fact）/
--      **真字段名**（指标码）/ 事实引用（维度组合）/ **前端真实路由**
--      （/analytics/custom-report?reportId=…）/ 口径说明 / 快照时间。
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
-- 1. platform_smart_query_metric —— 受控语义层：指标目录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个**受控指标**（自然语言只允许映射到这里的条目）。
--
-- 🔴 **每条指标的口径都来自 13.1.1.1 的标准口径表**（`reporting_metric_definition`
--   的播种内容）：指标码 / 单位 / 聚合方式 / 口径说明 / 归属模块**逐字沿用**，
--   本模块**不重写一份**。服务端在查询前会拿本表的 `metric_code` 去
--   reporting 的 `/reporting/custom-report/metrics` 核对一遍 ——
--   **该指标没在租户口径表里登记 ⇒ 这一格如实答「未接通」，不返回任何数字。**
--
-- 🔴 **strong_keywords 是封闭词表**：识别是**确定性关键词匹配**（强词 3 分 / 弱词 1 分），
--   不是模型自由理解。**只命中弱词不算数**（只命中一个「店」就判成店铺毛利是错的）。
--   一个强词都没命中 ⇒ 走「不支持这个问法」路径，**不猜**。
--
-- 🔴 `source_endpoint` 是**真实可调用的只读端点**：
--   `/reporting/custom-report/metrics`（口径目录）与
--   `/reporting/custom-report/reports/{reportId}`（查询结果与明细）。
--
CREATE TABLE platform_smart_query_metric (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 指标码（与 reporting_metric_definition.metric_code 同名；**逻辑引用**，不跨库建 FK）。
  metric_code varchar(48) NOT NULL,
  -- 指标名（页面上显示）。
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY / COUNT / QUANTITY / PERCENT（与 13.1.1.1 的单位词表一致）。
  unit varchar(16) NOT NULL
    CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  -- 聚合方式：SUM / LAST / DERIVED_DIFF / DERIVED_RATIO（口径表登记的聚合方式）。
  aggregation varchar(16) NOT NULL
    CHECK(aggregation IN ('SUM','LAST','DERIVED_DIFF','DERIVED_RATIO')),
  -- 是否涉币（涉币指标**不跨币种相加**）。
  currency_aware boolean NOT NULL DEFAULT true,

  -- 🔴 **封闭词表**：强关键词（权重 3，命中即基本可判定）/ 弱关键词（权重 1，只做加权）。
  strong_keywords text[] NOT NULL DEFAULT '{}'::text[],
  weak_keywords text[] NOT NULL DEFAULT '{}'::text[],

  -- =========================================================================
  -- 数据源：**只读**上游（服务名 + 真实端点路径）
  -- =========================================================================
  source_service varchar(32) NOT NULL
    CHECK(source_service IN ('reporting','finance','trade','crm')),
  source_endpoint varchar(200) NOT NULL,
  -- 🔴 **口径说明**：这个数字是怎么来的、算的是哪一段、哪些格子算不出来。
  source_caliber varchar(500) NOT NULL DEFAULT '',
  -- 明细链接落到的**前端真实路由**（相对路径）。
  detail_route varchar(200) NOT NULL DEFAULT '',
  -- 口径归属模块（指标口径归谁；空串 = 上游已在本服务内闭环）。
  owner_module varchar(32) NOT NULL DEFAULT '',

  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, metric_code),

  CHECK(btrim(metric_code) <> '' AND length(metric_code) <= 48),
  CHECK(btrim(display_name) <> '' AND length(display_name) <= 80),
  -- 🔴 **关键词不能为空**：没有强词的指标永远匹配不上 ⇒ 是个死条目。
  CHECK(cardinality(strong_keywords) > 0),
  CHECK(btrim(source_endpoint) <> '' AND length(source_endpoint) <= 200),
  CHECK(length(source_caliber) <= 500),
  CHECK(length(detail_route) <= 200),
  CHECK(length(owner_module) <= 32)
);

CREATE INDEX platform_smart_query_metric_by_code
  ON platform_smart_query_metric(tenant_id, metric_code);

-- ---------------------------------------------------------------------------
-- 2. platform_smart_query_dimension —— 受控语义层：维度目录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个**受控维度**。维度码与 13.6.1.1 的白名单**逐字一致**
-- （SCOPE_TYPE / LEGAL_ENTITY / SHOP / BUCKET_DATE / CURRENCY）——
-- 本模块**不另立一套维度**，否则「报表里能按店铺分，智能查询里不能」就分裂了。
--
-- 🔴 `value_kind` 决定「这个维度的筛选值能从自然语言里解析出什么」：
--   TEXT  —— 文本（币种：USD / EUR / CNY …）
--   UUID  —— uuid 文本（法人 / 店铺）：**自然语言里没有 uuid** ⇒ 受控词表只认
--             「显式传 uuid」这一条路，其余情况如实答「不支持这种问法」。
--   DATE  —— 日期（日历日）：时间窗口由「上周 / 本月」这类**受控时间词**解析。
--
-- 🔴 `allowed_operators` 是 13.6.1.1 的同一套运算符白名单（EQ / IN / GTE / LTE / BETWEEN）。
--
CREATE TABLE platform_smart_query_dimension (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 维度码（与 13.6.1.1 的白名单逐字一致）。
  dimension_code varchar(48) NOT NULL,
  -- 维度名（页面上显示）。
  display_name varchar(80) NOT NULL,
  -- 取值形态：TEXT / UUID / DATE。
  value_kind varchar(16) NOT NULL
    CHECK(value_kind IN ('TEXT','UUID','DATE')),
  -- 可用的筛选运算符（13.6.1.1 的同一套白名单）。
  allowed_operators text[] NOT NULL DEFAULT '{}'::text[],

  -- 🔴 **封闭词表**（强词 3 分 / 弱词 1 分；只命中弱词不算数）。
  strong_keywords text[] NOT NULL DEFAULT '{}'::text[],
  weak_keywords text[] NOT NULL DEFAULT '{}'::text[],

  -- 维度归属模块（空串 = 口径已在上游闭环）。
  owner_module varchar(32) NOT NULL DEFAULT '',

  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, dimension_code),

  CHECK(btrim(dimension_code) <> '' AND length(dimension_code) <= 48),
  CHECK(btrim(display_name) <> '' AND length(display_name) <= 80),
  CHECK(cardinality(allowed_operators) > 0),
  CHECK(cardinality(strong_keywords) > 0),
  CHECK(length(owner_module) <= 32)
);

CREATE INDEX platform_smart_query_dimension_by_code
  ON platform_smart_query_dimension(tenant_id, dimension_code);

-- ---------------------------------------------------------------------------
-- 3. platform_smart_query —— 查询台账 + 常用查询
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次**智能查询**（问句 → 受控查询条件 → 报表草稿 → 结果表）。
--
-- 🔴 **常用查询是同一张表上的一个标志**（`saved` + `display_name`），不是第二张表：
--   常用查询 = **固化下来的一条查询条件**（指标 / 维度 / 筛选 / 排序 / 时间窗口），
--   它的内容与当初那次查询**逐字一致** —— 存两份就会分叉，而分叉出来的常用查询
--   永远解释不了「它当初查的是什么」。
--
-- 🔴 `metric_code` / `dimension_code` **外键到同租户的词表行**：
--   表层面就不可能记下一个词表外的指标 / 维度。
--
-- 🔴 **resolved=false ⇒ report_id 为空**：没解析出来就没有报表草稿，
--   也就没有结果表、没有数字。「不出结论」在数据层是可查的，不是靠注释声明的。
--
-- 🔴 `filters` / `sort_spec` 是 **JSONB**：里面装的是**词表里的码**
--   （维度码 + 运算符 + 绑定值），不是 SQL 片段。
--
CREATE TABLE platform_smart_query (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 发起查询的人（**来自已认证会话**，不在请求体里）。
  owner_user_id uuid NOT NULL,

  -- 问句原文。
  question_text varchar(500) NOT NULL,
  -- 常用查询的名字（空串 = 还不是常用查询）。
  display_name varchar(120) NOT NULL DEFAULT '',
  -- 是不是常用查询。
  saved boolean NOT NULL DEFAULT false,

  -- 命中方式：KEYWORD（封闭词表确定性匹配）/ EXPLICIT（显式传了指标码或维度码）。
  match_mode varchar(24) NOT NULL
    CHECK(match_mode IN ('KEYWORD','EXPLICIT')),

  -- =========================================================================
  -- 解析出来的**受控查询条件**
  -- =========================================================================
  -- 指标码（**null = 没解析出来**）。
  -- ⚠️ 用 null 而不是空串：下面有同租户外键，空串会指向一个不存在的词表行。
  metric_code varchar(48) DEFAULT NULL,
  -- 维度码（**null = 公司口径，不分组**）。
  dimension_code varchar(48) DEFAULT NULL,
  -- 筛选条件：[{dimensionCode, operator, valueText, valueText2}]（**词表里的码 + 绑定值**）。
  filters jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 排序：{by, direction}（by = METRIC / DIMENSION）。
  sort_spec jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 时间窗口（受控时间词解析出来的结果；**不是「上周」这种词**）。
  period_code varchar(32) NOT NULL DEFAULT 'LAST_30_DAYS'
    CHECK(period_code IN ('LAST_7_DAYS','LAST_30_DAYS','LAST_WEEK','THIS_MONTH')),
  period_from date NOT NULL,
  period_to date NOT NULL,

  -- =========================================================================
  -- 下游 13.6.1.1 的**报表配置草稿**（跨服务，只存 id 与编码，不建跨库外键）
  -- =========================================================================
  report_id uuid,
  report_code varchar(64) NOT NULL DEFAULT '',

  -- 这次查询**出没出结论**。
  resolved boolean NOT NULL DEFAULT false,
  -- 没出结论的原因（缺什么 + 归谁）。
  unavailable_reason varchar(500) NOT NULL DEFAULT '',
  -- 没接通的部分归谁。
  owner_module varchar(32) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY (tenant_id, metric_code)
    REFERENCES platform_smart_query_metric(tenant_id, metric_code) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, dimension_code)
    REFERENCES platform_smart_query_dimension(tenant_id, dimension_code) ON DELETE RESTRICT,

  CHECK(btrim(question_text) <> '' AND length(question_text) <= 500),
  CHECK(length(display_name) <= 120),
  CHECK(length(metric_code) <= 48),
  CHECK(length(dimension_code) <= 48),
  CHECK(jsonb_typeof(filters) = 'array'),
  CHECK(jsonb_typeof(sort_spec) = 'object'),
  CHECK(period_from <= period_to),
  CHECK(length(report_code) <= 64),
  CHECK(length(unavailable_reason) <= 500),
  CHECK(length(owner_module) <= 32),
  -- 🔴 **出结论 ⇒ 必须有指标码与报表草稿**；没出结论 ⇒ 一定两者都没有。
  CHECK(
    (resolved AND metric_code IS NOT NULL AND report_id IS NOT NULL)
      OR (NOT resolved AND metric_code IS NULL AND report_id IS NULL)
  )
);

-- 常用查询清单（按更新时间倒序）。
CREATE INDEX platform_smart_query_by_saved
  ON platform_smart_query(tenant_id, saved, updated_at DESC);

-- 查询台账（按创建时间倒序）。
CREATE INDEX platform_smart_query_by_created
  ON platform_smart_query(tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. platform_smart_query_evidence —— 一条明细依据 ★
-- ---------------------------------------------------------------------------
--
-- 一行 = 查询结果表里的**一行**。蓝图加粗句「自然语言只映射到受控语义层指标」
-- 配套的纪律是「**不给依据的数字不能用于决策**」（18.4.1.1 的加粗句）。
--
-- 四件套（服务 / 表 / 字段 / 值）+ 下钻引用 + **可点的明细路由** + 口径说明 + 快照时间。
--
-- 🔴 **真表名 `reporting_metric_fact`**：语义层的事实真的住在那里（13.1.1.1 的投影事实表），
--   **真字段名 = 指标码**（`metric_code`）—— 不是一个编出来的字段名。
--
-- 🔴 **metric_value 是 numeric(19,6)**：禁浮点；算不出来的值是 NULL（不是 0）。
--
CREATE TABLE platform_smart_query_evidence (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  query_id uuid NOT NULL,
  -- 结果表内的序号（从 1 开始；对应结果表的第几行）。
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
  -- 下钻引用（维度组合，如 `SHOP=1111…`）。
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
  UNIQUE(tenant_id, query_id, seq),

  FOREIGN KEY (tenant_id, query_id)
    REFERENCES platform_smart_query(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(fact_table) <> '' AND length(fact_table) <= 64),
  CHECK(btrim(fact_field) <> '' AND length(fact_field) <= 64),
  CHECK(length(fact_ref) <= 200),
  CHECK(length(fact_label) <= 200),
  CHECK(length(metric_code) <= 64),
  CHECK(length(metric_unit) <= 24),
  -- 🔴 **明细路由不能为空**：没有可点的明细链接 ⇒ 这一行不该作为依据存在。
  CHECK(btrim(detail_route) <> '' AND length(detail_route) <= 200),
  CHECK(length(detail_label) <= 200),
  CHECK(length(caliber_note) <= 500)
);

CREATE INDEX platform_smart_query_evidence_by_query
  ON platform_smart_query_evidence(tenant_id, query_id, seq);

-- 按事实源反查（哪个查询引用了这条事实）。
CREATE INDEX platform_smart_query_evidence_by_fact
  ON platform_smart_query_evidence(tenant_id, fact_service, fact_table);

-- ===========================================================================
-- 5. 租户隔离（RLS）
-- ===========================================================================
--
-- 四张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V380 /
-- V429 / V438 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
ALTER TABLE platform_smart_query_metric ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_smart_query_metric FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_smart_query_metric
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_smart_query_dimension ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_smart_query_dimension FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_smart_query_dimension
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_smart_query ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_smart_query FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_smart_query
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_smart_query_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_smart_query_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_smart_query_evidence
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 6. BEFORE INSERT / UPDATE 触发器兜底（租户上下文）
-- ===========================================================================
--
-- 🔴 RLS 的 WITH CHECK 已经挡住「写入的 tenant_id 不是当前租户」；
--   但它报出来的错是「违反行级安全策略」，看不出**是哪个租户**对不上。
--   触发器给出可读的错误（platform V429 / V438 的同一纪律）。
--
-- 🔴 **没有租户上下文时直接拒绝写入**：租户不是客户端自称的。
--
CREATE FUNCTION platform_smart_query_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入智能查询的数据'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '智能查询数据的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION platform_smart_query_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_smart_query_tenant_guard() TO platform_app;

CREATE TRIGGER platform_smart_query_metric_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_smart_query_metric
  FOR EACH ROW EXECUTE FUNCTION platform_smart_query_tenant_guard();

CREATE TRIGGER platform_smart_query_dimension_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_smart_query_dimension
  FOR EACH ROW EXECUTE FUNCTION platform_smart_query_tenant_guard();

CREATE TRIGGER platform_smart_query_tenant_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_smart_query
  FOR EACH ROW EXECUTE FUNCTION platform_smart_query_tenant_guard();

CREATE TRIGGER platform_smart_query_evidence_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_smart_query_evidence
  FOR EACH ROW EXECUTE FUNCTION platform_smart_query_tenant_guard();

-- ===========================================================================
-- 7. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的语义层词表与查询台账，
-- 能写（幂等落词表、记一次查询、落明细依据、改常用查询）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_smart_query_metric TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_smart_query_dimension TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_smart_query TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_smart_query_evidence TO platform_app;
