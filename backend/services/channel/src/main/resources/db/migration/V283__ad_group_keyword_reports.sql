-- ===========================================================================
-- 14.3.1.2 广告组与词报表（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.3.1.2 广告组与词报表
--   - 职责：广告组与关键词维度报表：单组/单词效果、搜索词质量、否定效果评估，支持批量选中导出。
--   - 输入：维度选择（组/词/搜索词）；时间范围。
--   - 输出：明细报表 + 汇总。
--   - 关系：上游 = 14.1.3.1；下游 = 14.1.3.1 否定决策。
--           **关键词级数据量极大**，查询必须分页与缓存，不能实时全表扫。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 两张表**，并给上游的**指标表**补两条**窗口索引**（只加索引，
-- **零 ALTER 任何列、零改任何数据**）：
--   * V274 `ad_keyword` / `ad_keyword_metric` / `ad_search_term` / `ad_negative_keyword`
--     —— **已交付的上游 14.1.3.1**，本模块的**词指标与搜索词指标的事实源就是它们**。
--     本模块**不复制**任何一列指标，也**不建**第二套指标表。
--   * V277 `ad_campaign_group` / `ad_campaign_group_product`
--     —— **已交付的上游 14.1.1.2**，广告组的**结构事实**（组名 / 状态 / 默认竞价 /
--     投放商品数 / 所属活动）在这里。本模块**只读**这张表，**不改它的任何列**
--     （同批并行的 14.1.2.1 广告组管理负责它的写，它可能会 ALTER）。
--   * V273 `ad_campaign` —— 活动主表（组 → 活动 → 店铺 / 平台 的连接）。
--   * V1 `platform`（全局平台码表）+ V1 `shop` —— 只读引用。
--
-- 🔴 **不跨服务**：一切在 channel 库内。禁止 join catalog / trade / finance 的表。
--    币种问题：指标表带 `currency`，**跨币种相加没有意义** ⇒ 报表按币种分组、
--    汇总按币种拆开（详见「三、币种」）。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 组级指标**没有事实源** —— 这是本迁移最重要的取舍
-- ---------------------------------------------------------------------------
-- 蓝图职责句点名「单组效果」。**实测取证**：现有事实表里**没有任何一张组级指标表** ——
--   * `ad_campaign_daily_metric`（V273）是 **campaign_id 维度**的，没有 group_id；
--   * `ad_keyword_metric` / `ad_search_term`（V274）是 **keyword_id 维度**的。
-- 而且更硬的一条：**`ad_keyword` 与 `ad_search_term` 根本没有组的引用列**
-- （没有 `external_group_id`、没有 `group_id`；`external_group_id` 只存在于
-- V277 的 `ad_campaign_group` 上）。⇒ 词指标**在物理上无法归到组**。
--
-- 🔴 所以「单组效果」在现有数据里**算不出来**。两条出路：
--   (a) 把活动级指标冒充组级指标 —— **那是假事实，比缺功能严重得多**；
--   (b) 造一张空的组级指标表 —— **空表会被界面渲染成「没有数据」，
--      比明说「拿不到」严重得多**。
-- ⇒ 本模块走 (c)：**在能力声明里把组级指标维度显式标 available=false + ownerModule
--   + 影响说明**（照抄 14.1.1.2 处理「平台侧修改历史」的做法），
--   同时把**组级「结构」事实**（组名 / 状态 / 默认竞价 / 投放商品数 / 所属活动）
--   真实地展示出来 —— 那部分来自 `ad_campaign_group` / `ad_campaign_group_product`，
--   是事实，不是指标。
--
-- 责任模块：**14.1.2.1 广告组管理**（它负责广告组的写与平台侧结构同步，
-- 组级指标必须由它把 `ad_keyword` 补上组引用或另建组级指标事实表后才有分母）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴🔴 否定效果评估：口径由本模块**定死并公示**
-- ---------------------------------------------------------------------------
-- `ad_negative_keyword` 有 `evidence_from` / `evidence_to` / `applied_at`，
-- 但**否定之后该词的表现数据是否还在采集，没有事实保证**。
-- ⇒ 评估口径必须固定并写清，否则历史报表不可比（广告与财务对账最容易吵架的点）。
--
--   否定生效日 effective_date
--     = applied_at 的 **UTC 日期**（平台回执生效日）；
--       applied_at 为空 ⇒ 回退到 **evidence_to**（依据区间末日，即「从这天起应当不再有数据」）；
--       两者皆空 ⇒ **无法评估**（NOT_EVALUABLE），不是「否定后没数据」。
--
--   否定后仍有数据（STILL_REPORTING）
--     = 生效日**之后**（严格大于）仍存在匹配该否定词的指标 / 搜索词行，
--       且 Σ花费 > 0 或 Σ点击 > 0。
--     生效日之后的曝光/点击**应该**归零（否定已生效）；
--     若数据源仍在报，要能识别并标注「否定后仍有数据」，
--     **而不是把它当成 0** —— 「没有数据」（可能只是平台停报）与「有数据但都是 0」是两件事。
--
--   否定前 / 否定后窗口
--     前 = [from, min(to, effective_date)]      后 = [max(from, effective_date + 1 天), to]
--
-- 🔴 这套口径写在**返回值**里（`negativeEffectNote` / `metricDefinitionNote`），
--   也写进导出的 CSV 表头 —— 不写在注释里当口头约定。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 币种：不许把不同币种加在一起
-- ---------------------------------------------------------------------------
-- 本仓**没有汇率源**（无汇率表、无汇率同步器）⇒ 报表**按币种分组**、
-- 汇总**按币种拆开**。于是「USD 的花费」与「EUR 的花费」永远是两行，
-- 不会被加成一个假总数。跨币种统一口径在 capabilities 里显式 available=false。
--
-- ---------------------------------------------------------------------------
-- 四、🔴🔴 关键词级：必须分页与缓存，不能实时全表扫
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「**关键词级数据量极大**，查询必须分页与缓存，不能实时全表扫。」
-- 本迁移给出**三件**东西来兑现这句话：
--
--   (a) **两条窗口索引**（本迁移唯一碰上游表的地方，只加索引不改列）：
--       * `ad_keyword_metric_report_window (tenant_id, bucket_date, keyword_id)`
--         V274 既有索引是 `(tenant_id, keyword_id, bucket_date)` —— **keyword_id 在前**，
--         对「先按时间范围收窄、再按词过滤」的报表访问路径**用不上**。
--         本模块的谓词顺序是「先 tenant_id、再 bucket_date 范围、最后 keyword_id」，
--         所以补一条 **bucket_date 在前**的窗口索引。
--       * `ad_search_term_report_window (tenant_id, external_campaign_id, bucket_date)`
--         否定效果评估要按「外部活动 id + 时间范围」找搜索词；
--         V274 既有索引里没有 external_campaign_id 这一列 ⇒ 补一条。
--       （两条索引名都带 `report_window` 前缀，不与任何既有索引重名。）
--
--   (b) **真分页**（LIMIT / OFFSET）+ **时间范围必填** + **跨度封顶**（服务层执行）。
--
--   (c) **缓存**：`ad_group_keyword_report_cache`（见下）。
--
-- 🔴 同 (keyword_id, bucket_date) 的唯一约束要**吃进查询设计**：
--   `ad_keyword_metric` 上有 UNIQUE(tenant_id, keyword_id, bucket_date)
--   ⇒ 同一个词同一天**只有一行**，区间求和**不会重复计数**。
--   本模块把「有数据的天数」作为 `activeDays` 暴露出来，并把这个约束写进口径说明 ——
--   否则「Σ花费」到底加了几次，外界无从判断。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 缓存必须说清楚「什么时候过期」
-- ---------------------------------------------------------------------------
-- `ad_group_keyword_report_cache` 里存的是**渲染好的报表页**（jsonb）。
-- 🔴 三件事必须同时落库，否则就是「缓存了但不知道什么时候过期」：
--   * `data_as_of`      —— **失效依据 1**：算这份缓存时的**数据截至时刻**
--                           （= 该维度事实表里最大的 `synced_at` / `data_as_of`）。
--                           水位变了 ⇒ 这份缓存作废，重算。
--   * `source_batch_key`—— **失效依据 2**：水位那一行来自**哪一次同步批次**。
--                           事后复盘时能追到「这份报表对着哪一批数据算的」。
--   * `expires_at`      —— **明确的过期时刻**（TTL）。过了这一刻缓存作废，
--                           重算。响应里把 `expiresAt` 一起返回。
--
-- 🔴 缓存**按数据范围隔离**：`scope_fingerprint` = 当前调用者的数据范围指纹
--   （`app.grants` + `app.department` + `app.permission` 的 md5）。
--   范围窄的成员算出来的缓存，范围宽的成员**看不到**（RLS 策略直接挡住），
--   缓存键里也带这个指纹 ⇒ 两个人不会互相拿到对方的口径。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 导出留痕：沿用 14.3.1.1 的**同一套形状**，不造第三种口径
-- ---------------------------------------------------------------------------
-- `ad_campaign_report_export`（V279）的 dimension 有 CHECK 约束
-- （CAMPAIGN / PLATFORM / SHOP / AD_TYPE / CURRENCY），本模块的维度
-- （GROUP / KEYWORD / SEARCH_TERM / NEGATIVE_EFFECT）**不在其中**。
-- 🔴 ALTER 它的 CHECK 会动到**别的模块已交付的表**（本任务不许），
--   而「批量选中导出」又确实要留痕 ⇒ 按任务书允许的第二条路：
--   **按同一套形状新建一张** `ad_group_keyword_report_export`。
--   形状与 V279 一一对应（requested_by / platform_id / shop_code /
--   internal_ad_type / status / date_from / date_to / data_as_of /
--   metric_definition_note / created_at），只多两列：
--   * `selection jsonb` —— **批量选中的维度主键**（导出的是「选中的那几条」，
--     不是「当前筛选的全部」；不选就是当前筛选的全部）。
--   * `dimension` 的取值域换成本模块的四个维度。
--   ⇒ 全仓仍然只有**一种导出留痕口径**（同一张表的形状），不是第三种。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + 数据范围
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V205 / V273 / V274 / V277 / V279 同一套写法：
--   * `tenant_id uuid NOT NULL` + `ENABLE` + **`FORCE`** ROW LEVEL SECURITY
--     （只 ENABLE 的话表 owner 能绕过策略，隔离测试就成了自欺欺人）。
--   * `data_scope`（RESTRICTIVE）按**店铺**收敛：导出留痕随 `shop_code`，
--     缓存随 `scope_fingerprint`。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. ad_group_keyword_report_export · 批量选中导出留痕
-- ---------------------------------------------------------------------------
-- 🔴 与 V279 `ad_campaign_report_export` 同一套形状（同一张表的列名与语义），
--   只是维度取值域换成本模块的四个维度，并多一列 `selection`（批量选中的主键）。
--   留痕里存的是**筛选条件 + 选中集合**，**不存报表数据本身** ——
--   报表随时可以按同样的条件重算，而数据快照会在下一次同步后与「当时的报表」
--   对不上，留一份反而是假事实。
CREATE TABLE ad_group_keyword_report_export (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 谁导的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,

  -- 导出时用的维度（照抄请求当时的值，不做「按今天重新解释」）
  dimension text NOT NULL
    CHECK (dimension IN ('GROUP','KEYWORD','SEARCH_TERM','NEGATIVE_EFFECT')),

  -- 导出时用的时间粒度（TOTAL / DAY / WEEK / MONTH）——
  -- 必须留痕，否则同一份 CSV 事后没法复现是哪一种切法
  granularity text NOT NULL DEFAULT 'TOTAL'
    CHECK (granularity IN ('TOTAL','DAY','WEEK','MONTH')),

  -- === 上游 2.1.1.1 平台目录（全局码表）===
  platform_id uuid REFERENCES platform(id),

  -- === 上游 2.3.1.1 店铺（UNIQUE(tenant_id,code)，复合 FK ⇒ 跨租户挂载不可能）===
  shop_code varchar(64),
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 筛选条件（可空 = 那一维没筛）
  internal_ad_type varchar(32),
  status varchar(32),
  -- 🔴 外部活动 id 筛选（广告组 / 词 / 否定都挂在一个活动下）
  external_campaign_id varchar(128),
  -- 🔴 词文本筛选（KEYWORD / SEARCH_TERM / NEGATIVE_EFFECT 维度用）
  keyword_text varchar(200),
  date_from date NOT NULL,
  date_to date NOT NULL,

  -- 🔴 导出了多少行（按**分组后**的行数，不是明细行数）
  row_count int NOT NULL DEFAULT 0 CHECK (row_count >= 0),

  -- 🔴 **批量选中的维度主键**（jsonb 数组；空数组 = 导出当前筛选的全部）。
  --   「支持批量选中导出」是蓝图的职责句，所以「选了哪几条」必须留痕 ——
  --   否则事后没人能回答「这份 CSV 到底是哪一批选出来的」。
  selection jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- 🔴 这次导出是**对着哪个水位**算出来的。
  data_as_of timestamptz,

  -- 🔴 导出当时的口径定义（一并留痕：日后复盘时不必猜「当时用的是哪份定义」）
  metric_definition_note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK (date_from <= date_to)
);

CREATE INDEX ad_group_keyword_report_export_recent
  ON ad_group_keyword_report_export(tenant_id, created_at DESC);
CREATE INDEX ad_group_keyword_report_export_actor
  ON ad_group_keyword_report_export(tenant_id, requested_by, created_at DESC);
CREATE INDEX ad_group_keyword_report_export_dimension
  ON ad_group_keyword_report_export(tenant_id, dimension, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. ad_group_keyword_report_cache · 报表页缓存（带**明确的**失效依据）
-- ---------------------------------------------------------------------------
-- 🔴 这张表存在的唯一理由是蓝图加粗句「关键词级数据量极大，查询必须分页与缓存」。
--   它存的是**渲染好的报表页**（items + summaries + total 的 jsonb）。
--
-- 🔴 三件必须同时落库（否则就是「缓存了但不知道什么时候过期」）：
--   * data_as_of        —— 失效依据 1：算这份缓存时的**数据截至时刻**（水位变了就作废）
--   * source_batch_key  —— 失效依据 2：水位那一行来自**哪一次同步批次**
--   * expires_at        —— **明确的过期时刻**（TTL），响应里一并返回
--
-- 🔴 scope_fingerprint：当前调用者的**数据范围指纹**（app.grants + app.department +
--   app.permission 的 md5）。范围窄的成员算出来的缓存，范围宽的成员**看不到**
--   （RLS 策略直接挡住）—— 报表是「谁能看到哪些店铺的投放表现」，缓存不能绕过它。
CREATE TABLE ad_group_keyword_report_cache (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 查询指纹：维度 + 粒度 + 区间 + 筛选 + 排序 + 分页 + 范围指纹
  cache_key varchar(700) NOT NULL,
  dimension text NOT NULL
    CHECK (dimension IN ('GROUP','KEYWORD','SEARCH_TERM','NEGATIVE_EFFECT')),

  -- 🔴 渲染好的报表页（items + summaries + total）
  payload jsonb NOT NULL,

  -- 这一页的行数（便于运维一眼看出缓存了什么）
  row_count int NOT NULL DEFAULT 0 CHECK (row_count >= 0),

  -- 🔴 失效依据 1：算这份缓存时的数据截至时刻（= 该维度事实表里最大的 synced_at）
  data_as_of timestamptz NOT NULL,
  -- 🔴 失效依据 2：水位那一行来自哪一次同步批次
  source_batch_key varchar(160) NOT NULL DEFAULT '',
  -- 🔴 失效依据 3：**失效令牌** = md5(max(synced_at) + max(updated_at))。
  --   单看 synced_at 不够：补录的历史数据 synced_at 可能**早于**已有水位，
  --   水位不动 ⇒ 缓存会被当成「仍然新鲜」⇒ 报表少算了一段却没人知道。
  --   updated_at 记的是「这行什么时候被写进来」，任何插入/更新都会把它顶上去。
  source_watermark varchar(80) NOT NULL,

  -- 🔴 明确的过期时刻（不是「不知道什么时候过期」）
  expires_at timestamptz NOT NULL,

  -- 🔴 数据范围指纹（RLS 策略按它隔离缓存）
  scope_fingerprint varchar(64) NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, cache_key)
);

CREATE INDEX ad_group_keyword_report_cache_expiry
  ON ad_group_keyword_report_cache(tenant_id, expires_at);

-- ---------------------------------------------------------------------------
-- 3. 🔴 上游指标表的**窗口索引**（只加索引，零 ALTER 列、零改数据）
-- ---------------------------------------------------------------------------
-- 🔴 为什么不改上游的索引：V274 给 `ad_keyword_metric` 建的是
--   `(tenant_id, keyword_id, bucket_date)` —— **keyword_id 在前**。
--   本模块的报表谓词顺序是「先 tenant_id、再 bucket_date 范围、最后 keyword_id」，
--   对这个索引**用不上**时间范围这一段（会退化成把该租户的全部指标行扫一遍再过滤）。
--   蓝图加粗句点名「不能实时全表扫」⇒ 补一条 **bucket_date 在前**的窗口索引。
CREATE INDEX ad_keyword_metric_report_window
  ON ad_keyword_metric(tenant_id, bucket_date, keyword_id);

-- 否定效果评估要按「外部活动 id + 时间范围」找搜索词
-- （否定词是**活动级**的，否定记录上没有 keyword_id 时只能按活动找）。
-- V274 既有索引里没有 external_campaign_id 这一列 ⇒ 补一条。
CREATE INDEX ad_search_term_report_window
  ON ad_search_term(tenant_id, external_campaign_id, bucket_date);

-- ===========================================================================
-- 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V203 / V273 / V274 / V277 / V279 同一套写法。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['ad_group_keyword_report_export',
                             'ad_group_keyword_report_cache'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（与 V273 / V274 / V279 同一口径）
-- ---------------------------------------------------------------------------
-- 导出留痕：shop_code 有值 ⇒ 那次导出限定在这个店铺，必须在这个店铺的数据范围内；
-- shop_code 为空 ⇒ 跨了本租户可见的全部店铺，要求导出者**至少在一个**店铺上有数据范围。
CREATE POLICY data_scope ON ad_group_keyword_report_export AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_group_keyword_report_export.tenant_id
                 AND (ad_group_keyword_report_export.shop_code IS NULL OR s.code=ad_group_keyword_report_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_group_keyword_report_export.tenant_id
                 AND (ad_group_keyword_report_export.shop_code IS NULL OR s.code=ad_group_keyword_report_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 🔴 缓存：按**范围指纹**隔离。范围窄的成员算出来的缓存，范围宽的成员看不到 ——
--   报表是「谁能看到哪些店铺的投放表现」，缓存不能绕过它。
CREATE POLICY data_scope ON ad_group_keyword_report_cache AS RESTRICTIVE
  USING (scope_fingerprint = md5(coalesce(current_setting('app.grants',true),'')
                                   || '|' || coalesce(current_setting('app.department',true),'')
                                   || '|' || coalesce(current_setting('app.permission',true),'')))
  WITH CHECK (scope_fingerprint = md5(coalesce(current_setting('app.grants',true),'')
                                       || '|' || coalesce(current_setting('app.department',true),'')
                                       || '|' || coalesce(current_setting('app.permission',true),'')));

-- ===========================================================================
-- 账号与授权（沿用 V1/V146/V273/V274/V277/V279 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON ad_group_keyword_report_export TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ad_group_keyword_report_cache TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V146 / V273 同口径）。
REVOKE ALL ON ad_group_keyword_report_export FROM starcon_app;
REVOKE ALL ON ad_group_keyword_report_cache FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：广告组与词报表是「平台操作」语义下的读能力，
-- 与 14.1.1.1 广告活动列表、14.3.1.1 活动报表同族，因此 identity 服务零改动。
