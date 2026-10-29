-- ===========================================================================
-- V408 · custom_report —— 13.6.1.1 自定义报表
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:226）：
--
--   ### 13.6.1.1 自定义报表
--   - **职责**：业务人员自助构建报表：选择数据源（受控的语义层指标，不是直连库表）、
--     维度、度量、筛选、图表类型、保存与共享、订阅推送。
--   - **输入**：报表配置；可用的语义层指标清单。
--   - **输出**：报表结果 + 分享链接 + 定时订阅。
--   - **关系**：上游 = 语义层指标定义；下游 = 13.6.2.1 导出。
--     **只允许基于语义层指标自助，不开放任意 SQL** —— 否则报表口径会分裂成几十个版本，
--     最终没人知道该信哪个。
--
-- ---------------------------------------------------------------------------
-- 0. 本模块的七张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_custom_report             —— 报表配置（数据源 / 图表类型 / 默认窗口 / 归属）
--   reporting_custom_report_measure     —— 度量（**只允许引用已登记的 metric_code**）
--   reporting_custom_report_dimension   —— 维度（**只允许白名单里的 5 个维度码**）
--   reporting_custom_report_filter      —— 筛选（维度码 + 运算符 + 值；值一律走绑定参数）
--   reporting_custom_report_share       —— 分享链接（令牌 + 范围）
--   reporting_custom_report_subscription —— 订阅计划（cron + 通道 + 收件目标）
--   reporting_custom_report_delivery    —— 待投递记录（投递队列；**实际投递属 13.6.2.1**）
--
-- ⚠️ **本文件不建任何「事实表」**：报表结果**不落库**。
--   报表结果 = 语义层指标口径表（V376 `reporting_metric_definition`）+ 投影事实
--   （V376 `reporting_metric_fact`）按报表配置**实时算出来**的读模型。
--   落一份结果就是第二个真相 —— 口径改了、事实刷新了，落库的那份不会跟着变。
--
-- ⚠️ **本文件不建任何「导出 / 投递」表**：
--   * 大文件导出（Excel / CSV / PDF）属 **13.6.2.1**（未交付）；
--   * 邮件 / 企业 IM 的**实际投递**也属 **13.6.2.1**（未交付）。
--   ⇒ 本模块只负责「订阅计划」与「待投递记录（队列）」，
--     界面上如实写明「未交付」，**不假装已经能导出 / 已经收到邮件**。
--
-- ⚠️ **零 ALTER 既有表**：reporting 自己的纪律是「零 ALTER 既有表」
--   （V376 的七张表纯 CREATE TABLE）⇒ 本文件纯新建七张表，
--   与 13.1.1.1 的七张表、13.2.x / 13.3.x / 13.4.x / 13.5.x 的各组表互不干扰。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 「不开放任意 SQL」落到数据层：度量必须引用**已登记的指标码**
-- ---------------------------------------------------------------------------
--
--   蓝图关系句的落点是**物理约束**，不是代码里的自觉：
--
--   * `reporting_custom_report_measure.metric_code` 上有 CHECK
--     （全大写下划线形状），挡住「把一段 SQL 塞进指标码」；
--   * 触发器 `guard_reporting_custom_report_measure_known` 进一步要求
--     该指标码**已经在本租户的 `reporting_metric_definition` 里登记** ——
--     口径唯一：没有登记的口径就没有那个指标，报表里也引用不了。
--
--   ⇒ 「按登记口径自助」在 DB 层就是「写不进未登记的指标码」。
--   服务层拼装查询时也**只按指标码映射到已登记的定义**，
--   用户输入**只作为参数值**（绑定参数），绝不拼进 SQL 文本。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 维度 / 筛选的「白名单」也是物理约束
-- ---------------------------------------------------------------------------
--
--   `dimension_code` 上有 CHECK，只认 5 个维度：
--     SCOPE_TYPE（公司 / 法人 / 店铺）、LEGAL_ENTITY（法人 uuid）、SHOP（店铺 uuid）、
--     BUCKET_DATE（日历日）、CURRENCY（币种）
--   —— 它们是 V376 `reporting_metric_fact` 上**真实存在的列**，
--   不是「让用户填任意列名」。服务层把这 5 个码映射到固定的 SQL 片段，
--   片段本身是**代码里的常量**，不接受任何用户输入。
--
--   ⚠️ LEGAL_ENTITY / SHOP 维度出的是 **uuid 文本**：
--     语义层（V376）不带法人 / 店铺的**名称档案**，所以报表里给 uuid 而不是编一个名字。
--     （编名字 = 第二个真相。）
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_custom_report —— 报表配置
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：报表是**租户自己**的业务人员建的（13.1.1.1 / 13.2.2.2
--   已经是这个纪律）。服务层不播种任何「示例报表」—— 播种出来的报表在生产里
--   是没人用的僵尸数据，还会让人以为「已经有人配过了」。
--
-- ⚠️ **owner_user_id 来自已认证会话**（identity 按成员关系解析出来的 user_id），
--   **不在入参里** ⇒ 客户端不能把报表「送给」别人当自己的。
CREATE TABLE reporting_custom_report (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 报表编码：租户内唯一（用于分享链接与订阅引用）。
  report_code varchar(64) NOT NULL CHECK (btrim(report_code) <> ''),
  display_name varchar(120) NOT NULL CHECK (btrim(display_name) <> ''),
  -- 口径 / 用途说明：页面上原样显示。
  description varchar(500) NOT NULL DEFAULT '',
  -- 创建者（已认证会话的 user_id）。**个人报表只有它能看到**。
  owner_user_id uuid NOT NULL,
  -- 可见范围：PRIVATE 个人 / TEAM 团队内成员。
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','TEAM')),
  -- 图表类型：TABLE 表格 / BAR 柱状 / LINE 折线 / PIE 饼图。
  chart_type text NOT NULL DEFAULT 'TABLE' CHECK (chart_type IN ('TABLE','BAR','LINE','PIE')),
  -- 默认时间窗口（空 = 用运行时的默认窗口「最近 30 天」）。
  window_from date,
  window_to date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个租户内报表编码唯一。
  UNIQUE(tenant_id, report_code),
  -- 窗口不能倒着。
  CHECK (window_from IS NULL OR window_to IS NULL OR window_from <= window_to)
);

-- 报表清单按「创建者 / 可见范围 / 更新时间」筛。
CREATE INDEX reporting_custom_report_by_owner
  ON reporting_custom_report(tenant_id, owner_user_id, updated_at DESC);

-- ---------------------------------------------------------------------------
-- 2. reporting_custom_report_measure —— 度量
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个度量。**度量只能是已登记的 metric_code**（见第一节的触发器）。
--
-- ⚠️ **一个度量一个指标码**（UNIQUE(tenant_id, report_id, metric_code)）：
--   同一张报表里出现两个「销售额」度量就是口径要分裂的前兆。
CREATE TABLE reporting_custom_report_measure (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  report_id uuid NOT NULL,
  -- 指标码：与 reporting_metric_definition.metric_code 对应（**逻辑引用**，不建外键——
  --   口径表可能被重建，报表配置不该被口径变更卡住）。
  metric_code varchar(48) NOT NULL CHECK (metric_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
  -- 列顺序（报表结果里的度量列按它排）。
  display_order int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, report_id, metric_code)
);

CREATE INDEX reporting_custom_report_measure_by_report
  ON reporting_custom_report_measure(tenant_id, report_id, display_order);

-- ---------------------------------------------------------------------------
-- 3. reporting_custom_report_dimension —— 维度
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个维度。**维度只能是白名单里的 5 个码**（CHECK 钉死）。
--
-- ⚠️ **同一张报表里一个维度只能出现一次**（UNIQUE(tenant_id, report_id, dimension_code)）：
--   重复的维度列会让「按这个维度分组」出现两份一样的列。
CREATE TABLE reporting_custom_report_dimension (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  report_id uuid NOT NULL,
  dimension_code varchar(32) NOT NULL
    CHECK (dimension_code IN ('SCOPE_TYPE','LEGAL_ENTITY','SHOP','BUCKET_DATE','CURRENCY')),
  display_order int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, report_id, dimension_code)
);

CREATE INDEX reporting_custom_report_dimension_by_report
  ON reporting_custom_report_dimension(tenant_id, report_id, display_order);

-- ---------------------------------------------------------------------------
-- 4. reporting_custom_report_filter —— 筛选
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个筛选条件：维度码 + 运算符 + 值。
--
-- 🔴 **值只是值**：value_text / value_text2 一律作为**绑定参数**传给预编译 SQL，
--   **绝不拼进 SQL 文本**。这一条在 DB 层表达不了（DB 看不见 SQL 怎么拼），
--   但它在服务层是硬约束：**SQL 片段只来自维度白名单枚举，值只走 ? 绑定**。
--   本表里的 CHECK 只负责把「维度码」「运算符」关进白名单。
--
-- ⚠️ **BETWEEN 用两列**（value_text / value_text2）；其余运算符只用 value_text。
CREATE TABLE reporting_custom_report_filter (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  report_id uuid NOT NULL,
  dimension_code varchar(32) NOT NULL
    CHECK (dimension_code IN ('SCOPE_TYPE','LEGAL_ENTITY','SHOP','BUCKET_DATE','CURRENCY')),
  -- 运算符：EQ 等于 / IN 属于 / GTE 不早于 / LTE 不晚于 / BETWEEN 介于。
  operator varchar(16) NOT NULL CHECK (operator IN ('EQ','IN','GTE','LTE','BETWEEN')),
  -- 筛选值（**只作为绑定参数**，绝不拼进 SQL）。
  value_text varchar(200) NOT NULL DEFAULT '',
  -- BETWEEN 的上界（其余运算符恒为空串）。
  value_text2 varchar(200) NOT NULL DEFAULT '',
  display_order int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, report_id, dimension_code, display_order)
);

CREATE INDEX reporting_custom_report_filter_by_report
  ON reporting_custom_report_filter(tenant_id, report_id, display_order);

-- ---------------------------------------------------------------------------
-- 5. reporting_custom_report_share —— 分享链接
-- ---------------------------------------------------------------------------
--
-- 🔴 **分享链接不等于公开访问**（本模块的硬约束）：
--   * 令牌是 32 位十六进制随机串（128 bit 熵），**不可枚举**；
--   * 令牌**绑定租户**（行落在 tenant_id 下，RLS 强制）⇒ 别的租户拿着同一个令牌
--     在自己的租户上下文里**查不到**；
--   * 令牌**绑定报表归属**（PRIVATE 仅创建者 / TEAM 团队内成员）；
--   * 未授权访问与「报表不存在」返回**逐字相同**的 404（服务层负责，DB 层保证不了）。
--
-- ⚠️ **令牌全局唯一**（UNIQUE(share_token)，不带 tenant_id）：
--   两个租户撞同一个令牌的概率是 2^-128，但真撞上了也要**报错而不是静默覆盖**。
CREATE TABLE reporting_custom_report_share (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  report_id uuid NOT NULL,
  -- 分享令牌：32 位小写十六进制。
  share_token varchar(64) NOT NULL CHECK (share_token ~ '^[0-9a-f]{32}$'),
  -- 分享范围：PRIVATE 仅创建者 / TEAM 团队内成员（**不继承报表的可见范围，
  --   可以单独把个人报表分享给团队**）。
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','TEAM')),
  -- 谁建的这条分享（已认证会话的 user_id）。
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  -- 撤销：撤销后链接立即失效（行留着当凭据）。
  revoked_at timestamptz,
  UNIQUE(tenant_id, id),
  UNIQUE(share_token)
);

-- 正查：某张报表的分享链接。
CREATE INDEX reporting_custom_report_share_by_report
  ON reporting_custom_report_share(tenant_id, report_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 6. reporting_custom_report_subscription —— 订阅计划
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条「什么时候把这份报表推给谁」。
--
-- ⚠️ **cron 是 5 段式**（分 时 日 月 周），服务层只支持一个**明确写死的子集**：
--   `*`、单个值、`a-b`、`a-b/step`、`*/step`、逗号列表。
--   不支持的写法**报错**（不静默回落成「每天一次」—— 那样用户以为每天收，
--   实际收的频率完全不同）。
--
-- ⚠️ **channel / target 是「投递给谁」的配置，不是「已投递」的记录**：
--   真正的投递动作属 13.6.2.1（未交付）。本表只登记计划。
CREATE TABLE reporting_custom_report_subscription (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  report_id uuid NOT NULL,
  -- 5 段 cron 表达式（分 时 日 月 周）。
  cron_expression varchar(64) NOT NULL CHECK (btrim(cron_expression) <> ''),
  -- 投递通道：EMAIL 邮件 / IM 企业 IM（**实际投递属 13.6.2.1**）。
  channel varchar(16) NOT NULL CHECK (channel IN ('EMAIL','IM')),
  -- 收件目标（邮箱 / IM 群 id）。**只是配置**，不是「已收到」。
  target varchar(200) NOT NULL CHECK (btrim(target) <> ''),
  enabled boolean NOT NULL DEFAULT true,
  -- 下一次该执行的时刻（UTC）。cron 解析不出来时是 NULL（服务层会拒绝保存）。
  next_run_at timestamptz,
  -- 最近一次被落成待投递记录的时刻。
  last_enqueued_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

CREATE INDEX reporting_custom_report_subscription_by_report
  ON reporting_custom_report_subscription(tenant_id, report_id, created_at DESC);
-- 扫到期订阅用：租户 + 启用 + 下次执行时刻。
CREATE INDEX reporting_custom_report_subscription_due
  ON reporting_custom_report_subscription(tenant_id, enabled, next_run_at);

-- ---------------------------------------------------------------------------
-- 7. reporting_custom_report_delivery —— 待投递记录（投递队列）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某次计划时刻有一份报表等着投」。
--
-- ⚠️ **这**是**队列**，不是「已投递」的凭据**：status 初始是 PENDING，
--   真正的投递由 13.6.2.1（未交付）来推进。
--   ⇒ 界面上写「待投递（实际投递由 13.6.2.1 负责）」，
--   **不显示成「已发送」**。
--
-- ⚠️ **幂等键 = (租户, 订阅, 计划时刻)**：
--   同一个订阅的同一次计划时刻只有一条待投递记录 ⇒ 外部调度重复触发
--   不会把同一份报表排进去两次。
CREATE TABLE reporting_custom_report_delivery (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  report_id uuid NOT NULL,
  subscription_id uuid NOT NULL,
  -- 计划投递的时刻（UTC）。
  scheduled_for timestamptz NOT NULL,
  -- PENDING 待投递 / DELIVERED 已投递 / FAILED 投递失败 / CANCELLED 已取消。
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','DELIVERED','FAILED','CANCELLED')),
  attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  last_error varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 幂等键：同一订阅的同一次计划时刻只有一条。
  UNIQUE(tenant_id, subscription_id, scheduled_for)
);

-- 队列清单按状态 + 计划时刻排。
CREATE INDEX reporting_custom_report_delivery_by_status
  ON reporting_custom_report_delivery(tenant_id, status, scheduled_for DESC);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 度量的指标码**必须已在本租户的口径表登记**。
--
--     ⚠️ 触发器要能在「定义行已被删掉」时安静退出（只挡写入，不挡删除）。
CREATE OR REPLACE FUNCTION guard_reporting_custom_report_measure_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在口径表中登记，不能作为报表度量', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_custom_report_measure_known
  BEFORE INSERT OR UPDATE ON reporting_custom_report_measure
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_custom_report_measure_known();

-- (b) 订阅的 cron 表达式必须是 5 段（服务层再校验**子集**是否受支持）。
--
--     ⚠️ 这里只钉**段数**与字符集；「这一串具体是什么意思」由服务层的解析器负责，
--     解析不了就**拒绝保存**（不静默回落）。
CREATE OR REPLACE FUNCTION guard_reporting_custom_report_subscription_cron() RETURNS trigger AS $$
BEGIN
  IF array_length(string_to_array(btrim(NEW.cron_expression), ' '), 1) <> 5 THEN
    RAISE EXCEPTION 'cron 表达式必须是 5 段（分 时 日 月 周），收到的是 %', NEW.cron_expression
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_custom_report_subscription_cron
  BEFORE INSERT OR UPDATE ON reporting_custom_report_subscription
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_custom_report_subscription_cron();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_custom_report','reporting_custom_report_measure','reporting_custom_report_dimension',
    'reporting_custom_report_filter','reporting_custom_report_share',
    'reporting_custom_report_subscription','reporting_custom_report_delivery'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_custom_report, reporting_custom_report_measure, reporting_custom_report_dimension,
  reporting_custom_report_filter, reporting_custom_report_share,
  reporting_custom_report_subscription, reporting_custom_report_delivery TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_custom_report, reporting_custom_report_measure, reporting_custom_report_dimension,
  reporting_custom_report_filter, reporting_custom_report_share,
  reporting_custom_report_subscription, reporting_custom_report_delivery TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.6.2.1 导出与订阅（未交付）**：
--     - 大文件导出（Excel / CSV / PDF）需要**异步任务 + 文件有效期**
--       （蓝图关系句：「大导出走异步任务并给出有效期」）⇒ 本模块**不建导出任务表**；
--     - 邮件 / 企业 IM 的**实际投递**需要投递器 + 退信处理 ⇒ 本模块只登记
--       「待投递记录」，status 由 13.6.2.1 推进。
--     - ⇒ 本模块的 capabilities 里这两项**显式 available=false + ownerModule + 影响说明**。
--
--   * **13.6.1.2 看板搭建（未交付）**：看板要的是「组件 + 布局 + 角色可见范围」，
--     本模块的 `reporting_custom_report` 已经带了 `visibility`（个人 / 团队），
--     看板可以直接引用这张表当「组件来源」，**不需要改结构**。
--
--   * **18.4.1.1 自然语言取数（未交付）**：蓝图明确「自然语言只映射到受控语义层指标，
--     不生成任意 SQL」⇒ 它的下游就是本模块的**受控语义层**，两边共用同一张
--     `reporting_metric_definition`。
