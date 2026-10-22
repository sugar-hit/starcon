-- ===========================================================================
-- 14.1.3.2 投放定位（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.1.3.2 投放定位
--   - 职责：管理投放定位（地域、人群、设备、时段、匹配类型），支持按定位维度看效果，
--           指导出价调整。
--   - 输入：定位设置；定位维度效果数据。
--   - 输出：定位效果对比表 + 调整建议。
--   - 关系：上游 = 14.1.2.1；下游 = 14.2.1.2 分时策略。
--           **定位效果差异大时应收窄投放，无效定位的花费是最容易省下来的钱。**
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只引用已交付的上游表**（全部在 channel 服务同库）：
--   * V273 `ad_campaign`（**已交付的 14.1.1.1 活动列表**）—— 活动本体（复合 FK 带 tenant_id）。
--   * V277 `ad_campaign_group`（**已交付的 14.1.1.2 活动详情 / 14.1.2.1 广告组管理**）——
--     广告组本体（复合 FK 带 tenant_id）。本迁移**不改它的任何列**。
--   * V281 `ad_campaign_group.match_type` / `.targeting_settings` / `.pending_platform_writeback`
--     —— 平台原文（**只读消费**）。
--
-- 🔴 **不引用**任何跨服务表：catalog 的 sku / trade 的订单 / finance 的账，
--   一律**不 join、不建 FK、不建视图**。
--
-- 🔴 **不引用**同批并行车道的任何表：14.4.1.2 优惠券与秒杀 / 14.4.2.1 促销效果分析 /
--   14.5.1.1 达人库本批**不在**本车道，本模块**不 import 它们的类、不建它们的表、
--   不猜它们的 URL**。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 为什么不改 V281 的 `targeting_settings`，而是另建一张表
-- ---------------------------------------------------------------------------
-- V281 的注释写得很明白：`targeting_settings` 是**平台原文串，不拆解、不归一**，
-- 且**「解读归 14.1.3.2」**。也就是说：
--   * V281 的三列语义**一字不改**（改了就跨车道的隐性回归面）；
--   * **解读 / 归一化后的定位维度**由本模块**用自己的新表承载**。
-- ⇒ 事实有两份、职责不重叠：
--   * `ad_campaign_group.targeting_settings` = **平台原文**（同步下来的那一份）；
--   * `ad_targeting_setting`                = **归一后的定位维度**（解读出来的那一份），
--     并**保留原文**（`platform_raw_key` / `platform_raw_value`）以便回溯。
--
-- 🔴 **为什么不能只有原文那一层**：蓝图要求「**按定位维度看效果**」与
--   「**定位效果对比表**」—— 这两件事都必须**按维度**（地域 / 人群 / 设备 / 时段 /
--   匹配类型）聚合，不拆解就没法聚合，「差异大时收窄投放」也无从谈起。
--
-- ---------------------------------------------------------------------------
-- 二、三张表：定位设置 / 定位效果指标 / 操作留痕
-- ---------------------------------------------------------------------------
--   ad_targeting_setting        定位设置（**归一后的定位维度**：地域/人群/设备/时段/匹配类型）
--   ad_targeting_metric         定位效果指标（**按定位维度取值 × UTC 日**：展示/点击/转化/花费/销售额）
--   ad_targeting_operation_log  定位操作留痕（增删改/解读/同步/收窄都留痕）
--
-- 为什么「设置」与「指标」是两张表：设置是**当前状态**（一个定位取值一行），
-- 指标是**时间序列**（同一个定位取值每天一行）。混在一张表里就会出现
-- 「今天没数据 = 这个定位今天被删了」这种说不清的状态。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 归一维度取值（与 2.1.2.1「映射缺失必须显式报未映射」同一款纪律）
-- ---------------------------------------------------------------------------
-- `dimension` 的取值是**归一后的内部口径**：
--   GEO / AUDIENCE / DEVICE / DAYPART / MATCH_TYPE —— 五个蓝图点名的维度；
--   UNMAPPED —— **显式合法值**，表示「平台原文里的键本模块认不出来」，
--                **不是兜底**（照抄 V274 的 `internal_ad_type` 口径）。
-- 认不出的键**不丢弃**：落成 dimension='UNMAPPED' + `platform_raw_key` 存原文键，
-- 运营在界面上能看见「平台给了个我们不认识的定位维度」，而不是它人间蒸发。
--
-- 🔴 `dimension_value` = **归一后的取值**（去空白 + 大写 + 空白折叠）；
--   `platform_raw_value` = **平台原文里的取值**（原样保留）。
--   归一**不丢原文** —— 归一是为了能聚合，不是为了把原文抹掉。
--
-- ---------------------------------------------------------------------------
-- 四、🔴🔴 金额与数量纪律（禁浮点）
-- ---------------------------------------------------------------------------
--   * 金额（`spend` / `sales`）= `numeric(19,4)` + BigDecimal，**无 float/double**。
--     （与 V273 / V274 / V281 的金额口径同宽。）
--   * 数量（**时段小时数** `daypart_hours` / **出价系数** `bid_factor`）=
--     `numeric(19,6)` + BigDecimal（照抄 V228 crm「数量 / 小时数 / 权重 一律
--     numeric(19,6)」与 db/schemas 的「数量 → numeric(19,6)」口径）。
--   * 计数（`impressions` / `clicks` / `conversions`）= **整数**（`bigint` / `integer`），
--     **不用 numeric(19,6) 冒充计数、绝不用 double**（照抄 V274 `ad_keyword_metric`
--     的同一款纪律：计数就是计数，套小数位只是把整数写脏）。
--   * **ACOS / ROAS 不落表**：那是**派生比率**，由 SQL 现算；
--     **销售额为 0 时给 NULL（不可比），不是 0**（照抄 V274 同一款纪律）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 时间口径
-- ---------------------------------------------------------------------------
--   * 所有**时刻**列（`effective_from` / `effective_to` / `synced_at` /
--     `local_changed_at` / `created_at` / `updated_at`）都是 `timestamptz`，
--     服务层绑 `OffsetDateTime` + values 位置写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime...)` 会**静默偏 −28800s**。
--   * `bucket_date` 是 **UTC 日**（`date`）：指标按日归集，跨时区店铺看的也是同一份
--     UTC 日历（与 V274 同一款口径）。
--   * 蓝图要求的「数据截至时间」由 `synced_at` 承担（**显式一列**，不是页面上猜的）。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 多租户隔离与数据范围
-- ---------------------------------------------------------------------------
-- 三张表 `tenant_id uuid NOT NULL` + `ENABLE` + **`FORCE`** ROW LEVEL SECURITY
-- （只 ENABLE 的话表 owner 能绕过策略，隔离测试就成了自欺欺人）。
-- 所有复合外键都带 `tenant_id` ⇒ 跨租户挂引用在物理上不可能。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- **客户端的 X-Tenant-Id 头进不来也改不动它**。
--
-- 另加 data_scope（RESTRICTIVE）策略：可见性随**活动 → 店铺**收敛
-- （与 V277 的 `ad_campaign_group` 同构）—— 不允许绕过店铺直接枚举投放定位。
--
-- ===========================================================================
-- 1. ad_targeting_setting · 定位设置（归一后的定位维度）
-- ===========================================================================

CREATE TABLE ad_targeting_setting (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 14.1.1.1 活动（复合 FK 带 tenant_id）===
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- === 上游 14.1.2.1 广告组（复合 FK 带 tenant_id；为空 = 活动级定位）===
  group_id uuid,
  FOREIGN KEY (tenant_id, group_id)
    REFERENCES ad_campaign_group(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 归属层级：CAMPAIGN 活动级定位 / GROUP 组级定位。
  --   蓝图说「活动/组级定位维度」—— 两级都要能挂。
  scope_kind text NOT NULL CHECK (scope_kind IN ('CAMPAIGN','GROUP')),
  -- 🔴 scope_kind 与 group_id 必须自洽（活动级定位没有组，组级定位必须有组）。
  --   用 CHECK 而不是服务层自觉：这是**结构事实**，不该靠代码记得住。
  CHECK (
    (scope_kind = 'CAMPAIGN' AND group_id IS NULL)
    OR (scope_kind = 'GROUP' AND group_id IS NOT NULL)
  ),

  -- 🔴 归一后的定位维度（UNMAPPED = 平台给了个认不出来的键，**不是兜底**）
  dimension text NOT NULL
    CHECK (dimension IN ('GEO','AUDIENCE','DEVICE','DAYPART','MATCH_TYPE','UNMAPPED')),
  -- 🔴 归一后的取值（去空白 + 大写 + 空白折叠）
  dimension_value text NOT NULL CHECK (btrim(dimension_value) <> ''),
  -- 🔴 平台原文的键与取值（**原文不丢**：归一是为了能聚合，不是为了把原文抹掉）
  platform_raw_key text NOT NULL DEFAULT '',
  platform_raw_value text NOT NULL DEFAULT '',

  -- 🔴 状态：ACTIVE 在投 / PAUSED 已收窄（停投这条定位）/ ARCHIVED 已归档
  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','PAUSED','ARCHIVED')),

  -- 🔴 生效时间（索引按它建；生效区间可空 = 长期有效）
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,

  -- 🔴 时段窗口（**只有 DAYPART 维度有值**，其余维度为 NULL）：
  --   跨零点窗口 end < start（22:00-02:00 就是跨零点，不是数据坏了）。
  --   daypart_hours = 窗口覆盖的小时数（**数量：numeric(19,6) + BigDecimal**）。
  daypart_start_hour smallint CHECK (daypart_start_hour IS NULL OR (daypart_start_hour BETWEEN 0 AND 23)),
  daypart_end_hour smallint CHECK (daypart_end_hour IS NULL OR (daypart_end_hour BETWEEN 0 AND 23)),
  daypart_hours numeric(19,6) CHECK (daypart_hours IS NULL OR (daypart_hours > 0 AND daypart_hours <= 24)),
  daypart_crosses_midnight boolean NOT NULL DEFAULT false,

  -- 🔴 出价系数（相对基准的调整倍数；1 = 不调整；范围 0.1 ~ 10）。
  --   **本模块不据此改钱**：改竞价归 14.2.1.1（未交付）。这里只**记录**运营采纳的系数，
  --   让下游竞价模块能直接取用（系数是**比例**：numeric(19,6) + BigDecimal，禁浮点）。
  bid_factor numeric(19,6) NOT NULL DEFAULT 1 CHECK (bid_factor > 0 AND bid_factor <= 10),

  -- 🔴 本地已改、尚未回写平台（平台回写队列未交付 ⇒ 所有本地写都只落本地）
  pending_platform_writeback boolean NOT NULL DEFAULT false,
  local_changed_at timestamptz,

  -- 🔴 来源：PLATFORM 由平台原文解读而来 / MANUAL 人工设置
  source text NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('PLATFORM','MANUAL')),

  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 生效区间自洽（结束早于开始 = 数据坏了）
  CHECK (effective_to IS NULL OR effective_to >= effective_from),
  -- 🔴 窗口三列**要么全空、要么全有**（结构事实，不是服务层的自觉）：
  --   * 非 DAYPART 维度 ⇒ 必须全空；
  --   * DAYPART 维度 ⇒ 全空 = **时段取值解析不出小时窗口**（平台给了个看不懂的时段串，
  --     显式留白而不是编一个窗口出来），全有 = 解析成功。
  CHECK (
    (dimension <> 'DAYPART' AND daypart_start_hour IS NULL AND daypart_end_hour IS NULL
      AND daypart_hours IS NULL)
    OR (dimension = 'DAYPART' AND (
      (daypart_start_hour IS NULL AND daypart_end_hour IS NULL AND daypart_hours IS NULL)
      OR (daypart_start_hour IS NOT NULL AND daypart_end_hour IS NOT NULL
        AND daypart_hours IS NOT NULL)
    ))
  )
);

-- 🔴 同一归属下同一维度同一取值只有一条。
--   用**唯一索引 + coalesce** 而不是 UNIQUE 约束：PostgreSQL 的 UNIQUE 把 NULL 视为互不相同，
--   活动级定位（group_id IS NULL）会因此漏掉「同活动同维度同取值重复」的约束。
CREATE UNIQUE INDEX ad_targeting_setting_unique
  ON ad_targeting_setting(tenant_id, campaign_id, dimension, dimension_value, scope_kind,
    coalesce(group_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- 🔴 索引按**查询维度**建：租户 + 活动/组 + 维度 + 生效时间
CREATE INDEX ad_targeting_setting_campaign
  ON ad_targeting_setting(tenant_id, campaign_id, dimension, effective_from DESC);
CREATE INDEX ad_targeting_setting_group
  ON ad_targeting_setting(tenant_id, group_id, dimension, effective_from DESC)
  WHERE group_id IS NOT NULL;
CREATE INDEX ad_targeting_setting_dimension
  ON ad_targeting_setting(tenant_id, dimension, dimension_value, status);
CREATE INDEX ad_targeting_setting_effective
  ON ad_targeting_setting(tenant_id, effective_from DESC);

COMMENT ON TABLE ad_targeting_setting IS
  '14.1.3.2 投放定位设置：平台原文（V281 ad_campaign_group.targeting_settings）解读后的归一定位维度；活动级/组级皆可挂';
COMMENT ON COLUMN ad_targeting_setting.dimension IS
  '归一后的定位维度：GEO/AUDIENCE/DEVICE/DAYPART/MATCH_TYPE/UNMAPPED（UNMAPPED = 认不出来的键，显式合法值，不是兜底）';
COMMENT ON COLUMN ad_targeting_setting.dimension_value IS
  '归一后的取值（去空白+大写+空白折叠）；平台原文另存 platform_raw_value';
COMMENT ON COLUMN ad_targeting_setting.bid_factor IS
  '出价系数（比例，numeric(19,6)）：记录运营采纳的建议系数；本模块不据此改钱（改竞价归 14.2.1.1）';
COMMENT ON COLUMN ad_targeting_setting.daypart_hours IS
  '时段窗口覆盖的小时数（数量，numeric(19,6)）：只有 DAYPART 维度有值';

-- ===========================================================================
-- 2. ad_targeting_metric · 定位效果指标（按定位维度取值 × UTC 日）
-- ===========================================================================
-- 🔴 蓝图输入「**定位维度效果数据**」的落点：某个定位取值在某一天的表现
--   （展示 / 点击 / 转化 / 花费 / 销售额）。
--
-- 🔴 为什么把 campaign_id / group_id / dimension / dimension_value **冗余**在指标行上：
--   「定位效果对比表」要按活动/组/维度聚合，指标量大时每次都 join 设置表很贵；
--   冗余列让对比表**一次扫描**就能出结果。冗余列由服务层保证与 setting 一致
--   （写入时按 setting 取值填，不接受调用方自报）。
CREATE TABLE ad_targeting_metric (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 定位设置（复合 FK ⇒ 跨租户挂引用在物理上不可能）
  setting_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, setting_id)
    REFERENCES ad_targeting_setting(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 冗余的归属与维度列（服务层按 setting 填，调用方不能自报）
  campaign_id uuid NOT NULL,
  group_id uuid,
  dimension text NOT NULL,
  dimension_value text NOT NULL,

  -- 🔴 指标日（UTC 日历日；跨时区店铺看的也是同一份 UTC 日）
  bucket_date date NOT NULL,

  -- 🔴 展示次数（**计数**：整数，不是浮点）
  impressions bigint NOT NULL DEFAULT 0 CHECK (impressions >= 0),
  -- 🔴 点击次数（计数）
  clicks bigint NOT NULL DEFAULT 0 CHECK (clicks >= 0),
  -- 🔴 转化次数（计数）
  conversions integer NOT NULL DEFAULT 0 CHECK (conversions >= 0),
  -- 🔴 花费（金额：numeric(19,4) + BigDecimal，无浮点）
  spend numeric(19,4) NOT NULL DEFAULT 0 CHECK (spend >= 0),
  -- 🔴 销售额（金额：numeric(19,4)）
  sales numeric(19,4) NOT NULL DEFAULT 0 CHECK (sales >= 0),
  -- 🔴 指标币种（ACOS / CPC 是**同币种内**的比值，跨币种相加没有意义）
  currency varchar(8) NOT NULL CHECK (btrim(currency) <> ''),

  -- 🔴 数据截至时间（NOT NULL）：这批指标是截至什么时候的 —— 显式一列，不是页面上猜的
  synced_at timestamptz NOT NULL,
  -- 🔴 来源：PLATFORM 平台回报 / API 人工补录 / IMPORT 批量导入
  source text NOT NULL DEFAULT 'PLATFORM' CHECK (source IN ('PLATFORM','API','IMPORT')),
  -- 🔴 来源引用（平台侧批次 id 之类；用于把「哪一次同步带来的」追回去）
  source_ref varchar(160) NOT NULL DEFAULT '',
  -- 🔴 批次幂等键（同一批重投不重复累加：落两遍会让 ACOS 与 CPC 变成两倍）
  batch_key varchar(160) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 一个定位设置一天一行（同一天重投是**替换**不是累加）
  UNIQUE(tenant_id, setting_id, bucket_date),
  -- 🔴 点击数不该超过展示数（平台回报错乱时直接拒，而不是把错数据存进来）
  CHECK (clicks <= impressions)
);

-- 🔴 索引按**对比表的查询维度**建：租户 + 时间窗 + 维度
CREATE INDEX ad_targeting_metric_window
  ON ad_targeting_metric(tenant_id, bucket_date, dimension, dimension_value);
CREATE INDEX ad_targeting_metric_campaign
  ON ad_targeting_metric(tenant_id, campaign_id, dimension, bucket_date);
CREATE INDEX ad_targeting_metric_group
  ON ad_targeting_metric(tenant_id, group_id, dimension, bucket_date)
  WHERE group_id IS NOT NULL;
CREATE INDEX ad_targeting_metric_setting
  ON ad_targeting_metric(tenant_id, setting_id, bucket_date DESC);

COMMENT ON TABLE ad_targeting_metric IS
  '14.1.3.2 定位效果指标：按定位维度取值 × UTC 日；对比表与调整建议的**唯一指标来源**（不拿别的口径冒充）';

-- ===========================================================================
-- 3. ad_targeting_operation_log · 定位操作留痕
-- ===========================================================================
-- 14 章关键约束：「批量调整预算与竞价要走规则并留审计」。
-- 本模块的写动作有六种：新建定位设置 / 改定位设置 / 归档定位设置 /
-- 解读平台原文 / 同步定位效果数据 / 收窄投放。
--
-- 🔴 `setting_id` **不建 FK**：审计要能记下「我想改的那条压根不存在」——
--   有 FK 的话这条审计自己写不进去，「为什么这次没生效」就永远查不出来
--   （与 V281 `ad_group_operation_log` 同一款纪律）。
CREATE TABLE ad_targeting_operation_log (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 14.1.1.1 活动（复合 FK 带 tenant_id）===
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  group_id uuid,
  -- 🔴 目标定位设置 id：**不建 FK**（见上）
  setting_id uuid,

  -- 写动作种类：
  --   CREATE_SETTING      新建定位设置
  --   UPDATE_SETTING      改定位设置（值 / 状态 / 出价系数 / 生效时间）
  --   ARCHIVE_SETTING     归档定位设置
  --   INTERPRET_TARGETING 解读平台原文定位串（归一后落库）
  --   INGEST_METRIC       同步定位维度效果数据
  --   NARROW_TARGETING    收窄投放（按建议把一条定位置为暂停）
  operation varchar(32) NOT NULL
    CHECK (operation IN ('CREATE_SETTING','UPDATE_SETTING','ARCHIVE_SETTING',
                         'INTERPRET_TARGETING','INGEST_METRIC','NARROW_TARGETING')),

  -- 🔴 变更前后的**快照**（紧凑 JSON 原文）
  before_value text NOT NULL DEFAULT '',
  after_value text NOT NULL DEFAULT '',

  -- 🔴 三种结果**都**留痕（APPLIED / REJECTED / SKIPPED）——
  --   被拒的那几条同样要能复盘「为什么这条没生效」。
  result varchar(16) NOT NULL CHECK (result IN ('APPLIED','REJECTED','SKIPPED')),
  message text NOT NULL DEFAULT '',

  -- 谁发起的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,
  reason text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_targeting_operation_log_setting
  ON ad_targeting_operation_log(tenant_id, setting_id, created_at DESC);
CREATE INDEX ad_targeting_operation_log_campaign
  ON ad_targeting_operation_log(tenant_id, campaign_id, created_at DESC);

-- ===========================================================================
-- 4. 多租户隔离：三张表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V273 / V277 / V281 同一套写法。租户由
-- backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[ 'ad_targeting_setting', 'ad_targeting_metric',
                             'ad_targeting_operation_log' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**活动 → 店铺**收敛（照抄 V277 的 ad_campaign_group 同构）
-- ---------------------------------------------------------------------------
CREATE POLICY data_scope ON ad_targeting_setting AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_targeting_setting.tenant_id AND c.id=ad_targeting_setting.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_targeting_setting.tenant_id AND c.id=ad_targeting_setting.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_targeting_metric AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_targeting_metric.tenant_id AND c.id=ad_targeting_metric.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_targeting_metric.tenant_id AND c.id=ad_targeting_metric.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_targeting_operation_log AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_targeting_operation_log.tenant_id AND c.id=ad_targeting_operation_log.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_targeting_operation_log.tenant_id AND c.id=ad_targeting_operation_log.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 5. 账号与授权（沿用 V1/V146/V273/V277/V281 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  ad_targeting_setting, ad_targeting_metric, ad_targeting_operation_log TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V146 / V273 / V277 同口径）。
REVOKE ALL ON ad_targeting_setting, ad_targeting_metric, ad_targeting_operation_log FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：投放定位属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.1.2.1 广告组管理、14.1.3.1 关键词管理同族，因此 identity 服务零改动。
