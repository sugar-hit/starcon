-- ===========================================================================
-- 14.1.1.2 活动详情（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.1.1.2 活动详情
--   - 职责：单活动全景：基本设置、预算与消耗曲线、广告组列表、投放商品、关键词、创意（视频/图片）、
--           每日表现明细、平台侧修改历史。
--   - 输入：campaign_id；聚合子资源。
--   - 输出：详情视图 + 操作入口（调预算、改竞价、暂停、复制结构）。
--   - 关系：上游 = 14.1.1.1；下游 = 14.1.2.1 广告组、14.3.1.1 报表。
--           **"复制结构"是高价值操作**：把跑得好的活动结构复制到新店铺/新 ASIN，是广告提效的核心动作。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 策略**，零 ALTER 任何既有表：
--   * V273 `ad_campaign`（**已交付的上游 14.1.1.1**）—— 活动本体。
--   * V171 `listing`（**已交付的上游 4.1.1.1**，同库同服务）—— 投放商品指向的 listing。
--   * V1 `shop`（复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能）—— 数据范围随店铺收敛。
--
-- 🔴 **关键词不建表**：14.1.3.1 关键词管理（V274 `ad_keyword`）**已交付**，本模块**只读消费**它 ——
--   重复建一张「广告活动的关键词」表，会让同一个词在两个地方各有一份，
--   而 V274 的表是按**平台侧真实关键词 id** 建的（改价、否定、报表都指着它）。
--   本模块的「关键词」段按 (platform_id, shop_id, external_campaign_id) 读 V274。
--
-- 🔴 **平台侧修改历史不建表**：上游没有平台广告 API 同步器，**没有同步源**。
--   `ad_campaign_operation_log`（V273）是**本地**操作审计，不是平台侧改历史。
--   伪造一张「平台改历史」表就是编数据 —— 能力声明里显式标 available=false + ownerModule + 影响，
--   界面上说清「平台侧修改历史依赖平台广告 API 同步（未交付），当前只展示本地操作审计」。
--
-- 表名一律走 `ad_campaign_*` 命名空间（与 V273 的 `ad_campaign*` 并列而不混用），
-- **不碰** V274 的 `ad_keyword*`、V275 的 `ad_rule*`、V171 的 `listing*`。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这五张表存在的理由：广告组 / 商品 / 创意是「结构事实」，不是「视图」
-- ---------------------------------------------------------------------------
-- 蓝图把「广告组列表、投放商品、创意」写进了 14.1.1.2 的职责，但：
--   * 14.1.2.1 广告组管理 —— **未交付**（台账 module-progress.md 已确认）
--   * 14.1.3.1 关键词管理 —— 已交付，但**只管关键词自己的报表**，不管组 / 商品 / 创意
--   * 14.1.1.2 活动详情 —— **就是本模块**
-- 于是这三类结构化数据在本批**没有别的落点**。两种做法：
--   1. 用空数组 / 硬编码常量当「广告组列表」—— 界面看起来像「这个活动没有广告组」，
--      而真相是「结构数据还没同步进来」。这与蓝图加粗句「不能用空数据假装没有投放」同罪。
--   2. **本模块自带结构的真实写入口**：表 + 写入端点让结构数据真的有落点，
--      并把「结构截至 X 时间」变成库里可查的事实（照抄 V273 的 `data_as_of` 纪律）。
-- 本迁移选第 2 条。五张表：
--
--   ad_campaign_group            广告组（默认竞价 / 组级预算 / 定向 / 状态）
--   ad_campaign_group_product    投放商品（广告组 → listing 的投放关系）
--   ad_campaign_creative         创意（视频 / 图片 / 图集 / 文本）
--   ad_campaign_group_bid_log    改竞价审计（14 章关键约束「调整要留审计」）
--   ad_campaign_structure_copy   复制结构审计（源活动 → 目标活动的结构副本）
--
-- ---------------------------------------------------------------------------
-- 二、🔴 「复制结构」的语义：结构副本是真的，平台侧还不是
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「"复制结构"是高价值操作：把跑得好的活动结构复制到新店铺/新 ASIN，
-- 是广告提效的核心动作。」
--
-- 🔴 但**平台回写队列未交付**（平台适配层 channel/vendor）。于是「复制结构」的诚实语义是：
--   **在本地把结构复制成真的**（新建一个目标活动 + 真的广告组 / 商品 / 创意行），
--   并**明确标出「平台侧还没有这份结构」**：
--     * 目标活动 `pending_platform_writeback = true`（沿用 V273 的那一列，语义一致：
--       本地状态与平台状态不一致）；
--     * 目标活动的 `external_campaign_id` 落成 `LOCAL-COPY-{uuid}` ——
--       **这是本地生成的标记，不是平台侧 id**。平台侧真建出来之前没有平台 id，
--       编一个像平台 id 的字符串会让「同步回读」把这条草稿认成平台活动；
--     * 目标活动状态落 `PAUSED`（复制出来**不投放**），备注写明来源与「平台侧尚未创建」。
--
-- 🔴 **关键词不复制**：V274 的 `ad_keyword` 按**平台侧真实关键词 id** 建，
--   而关键词 id 是平台在创建广告组时才分配的。平台侧还没建这个活动，
--   本地造一批关键词 id 就是往「平台原文」表里塞假 id ——
--   那是比"少复制一段"严重得多的错。复制记录里 `keyword_count` 恒为 0 并写明原因。
--
-- ---------------------------------------------------------------------------
-- 三、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 默认竞价 / 组级预算 / 消耗 / 销售额一律 **NUMERIC(19,4)**（与 V273 同口径）；
-- 曝光 / 点击 / 订单是**计数**，一律 **NUMERIC(19,6)**（与 V273 同口径）。
-- **没有一列是 float/double** —— 竞价直接参与 14.2.1.1 竞价管理与 14.3.1.1 报表。
--
-- 🔴 「组级预算为空」= **继承活动预算**，不是 0。写成 0 会让「组没单独设预算」
-- 变成「这个组不许花钱」，是相反的两件事。
--
-- ---------------------------------------------------------------------------
-- 四、多租户隔离与数据范围
-- ---------------------------------------------------------------------------
-- 五张表 tenant_id NOT NULL + ENABLE/FORCE RLS isolation（照抄 V273 的写法），
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 另加 data_scope（RESTRICTIVE）策略：广告组 / 商品 / 创意随**活动 → 店铺**收敛
-- （与 V273 的 ad_campaign / ad_campaign_daily_metric 同构）——
-- 不允许绕过店铺直接枚举投放结构。
--
-- ===========================================================================
-- 1. ad_campaign_group · 广告组
-- ===========================================================================
CREATE TABLE ad_campaign_group (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 14.1.1.1 活动（复合 FK 带 tenant_id）===
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- 平台侧广告组 id（原文照抄）
  external_group_id varchar(128) NOT NULL CHECK (btrim(external_group_id) <> ''),
  group_name varchar(200) NOT NULL DEFAULT '',

  -- 🔴 归一后的状态（与 V273 的 ad_campaign.status 同口径）
  status varchar(32) NOT NULL DEFAULT 'UNKNOWN'
    CHECK (status IN ('ENABLED','PAUSED','ENDED','ARCHIVED','PENDING_REVIEW','UNKNOWN')),

  -- 🔴 默认竞价（钱）。NULL = 平台侧没有给默认竞价（不是 0）
  default_bid NUMERIC(19,4) CHECK (default_bid IS NULL OR default_bid >= 0),
  bid_currency char(3) NOT NULL DEFAULT '',

  -- 🔴 组级日预算。NULL = **继承活动预算**（不是 0）
  daily_budget NUMERIC(19,4) CHECK (daily_budget IS NULL OR daily_budget >= 0),

  -- 定向 / 匹配类型（平台原文；14.1.3.2 投放定位未交付，这里只存原文不解读）
  targeting_type varchar(64) NOT NULL DEFAULT '',

  -- 🔴 这组结构的数据截至时刻（结构也可能是旧的，必须能说清）
  data_as_of timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一活动下的平台侧广告组 id 唯一（重放同步不会造出两条）
  UNIQUE(tenant_id, campaign_id, external_group_id)
);

CREATE INDEX ad_campaign_group_campaign ON ad_campaign_group(tenant_id, campaign_id);
CREATE INDEX ad_campaign_group_status ON ad_campaign_group(tenant_id, status);
CREATE INDEX ad_campaign_group_watermark ON ad_campaign_group(tenant_id, data_as_of DESC);

-- ===========================================================================
-- 2. ad_campaign_group_product · 投放商品
-- ===========================================================================
-- 蓝图 14.1.2.1 加粗句：「广告组与 SKU 的映射是利润归因的基础——投放的是 listing，
-- 归因要落到内部 SKU。」本表是那条映射的**投放侧**（listing → 内部 SKU 的映射归
-- V171 listing_variant / 4.1.2.1，不在本模块重复实现）。
CREATE TABLE ad_campaign_group_product (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 冗余一份 campaign_id：数据范围策略与列表查询都不必先 join 组
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  group_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, group_id)
    REFERENCES ad_campaign_group(tenant_id, id) ON DELETE CASCADE,

  -- === 上游 4.1.1.1 listing（同库同服务，复合 FK 带 tenant_id）===
  listing_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, listing_id)
    REFERENCES listing(tenant_id, id) ON DELETE RESTRICT,

  -- 平台侧商品 id 原文快照（listing 里也有一份；改标题不该改掉投放时挂着的原文）
  external_item_id varchar(128) NOT NULL DEFAULT '',
  title varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一组里同一个 listing 只挂一次
  UNIQUE(tenant_id, group_id, listing_id)
);

CREATE INDEX ad_campaign_group_product_campaign ON ad_campaign_group_product(tenant_id, campaign_id);
CREATE INDEX ad_campaign_group_product_group ON ad_campaign_group_product(tenant_id, group_id);
CREATE INDEX ad_campaign_group_product_listing ON ad_campaign_group_product(tenant_id, listing_id);

-- ===========================================================================
-- 3. ad_campaign_creative · 创意（视频 / 图片 / 图集 / 文本）
-- ===========================================================================
CREATE TABLE ad_campaign_creative (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 创意挂在组下；NULL = **活动级创意**（部分平台如此）。
  --   复合 FK 在 group_id 为 NULL 时不生效（MATCH SIMPLE），
  --   而 tenant_id 仍由 RLS 锚定 —— 与 V274 的 listing_keyword_id 同一纪律。
  group_id uuid,
  FOREIGN KEY (tenant_id, group_id)
    REFERENCES ad_campaign_group(tenant_id, id) ON DELETE CASCADE,

  external_creative_id varchar(128) NOT NULL CHECK (btrim(external_creative_id) <> ''),
  creative_name varchar(200) NOT NULL DEFAULT '',

  -- 🔴 视频 / 图片是蓝图点名的两类；图集与文本是平台确实存在的形态，不塞进「图片」里
  creative_type varchar(16) NOT NULL
    CHECK (creative_type IN ('IMAGE','VIDEO','CAROUSEL','TEXT')),
  asset_url text NOT NULL DEFAULT '',

  -- 视频时长（秒）。非视频创意为 NULL
  duration_seconds int CHECK (duration_seconds IS NULL OR duration_seconds >= 0),

  status varchar(32) NOT NULL DEFAULT 'UNKNOWN'
    CHECK (status IN ('ENABLED','PAUSED','ENDED','ARCHIVED','PENDING_REVIEW','UNKNOWN')),

  data_as_of timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一活动下平台侧创意 id 唯一（不论它挂在组下还是活动级）
  UNIQUE(tenant_id, campaign_id, external_creative_id)
);

CREATE INDEX ad_campaign_creative_campaign ON ad_campaign_creative(tenant_id, campaign_id);
CREATE INDEX ad_campaign_creative_group ON ad_campaign_creative(tenant_id, group_id);
CREATE INDEX ad_campaign_creative_type ON ad_campaign_creative(tenant_id, creative_type);

-- ===========================================================================
-- 4. ad_campaign_group_bid_log · 改竞价审计
-- ===========================================================================
-- 14 章关键约束：「批量调整预算与竞价要走规则并留审计」。
-- V273 的 `ad_campaign_operation_log` 的 operation 只有活动级的四种，
-- 且没有广告组维度 —— 组级改竞价在本模块自己留痕，不去改上游的枚举。
CREATE TABLE ad_campaign_group_bid_log (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 目标广告组 id：**不建 FK**。
  --   审计要能记下「我想改的那个组压根不存在」—— 有 FK 的话这条审计自己写不进去，
  --   「为什么这条没生效」就永远查不出来。审计表记的是**当时的目标**，
  --   不是「当时存在的行」（与 14.1.3.1 关键词表「不建跨车道 FK」同一纪律）。
  group_id uuid NOT NULL,

  before_bid NUMERIC(19,4),
  after_bid NUMERIC(19,4) NOT NULL CHECK (after_bid >= 0),
  bid_currency char(3) NOT NULL DEFAULT '',

  -- 🔴 三种结果**都**留痕（APPLIED / REJECTED / SKIPPED）——
  --   被拒的那几条同样要能复盘「为什么这条没生效」，只留成功的那几条就查不出来。
  --   与 V273 `ad_campaign_operation_log` 的同一纪律。
  result text NOT NULL CHECK (result IN ('APPLIED','REJECTED','SKIPPED')),
  message text NOT NULL DEFAULT '',

  -- 谁发起的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,
  reason text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_campaign_group_bid_log_group ON ad_campaign_group_bid_log(tenant_id, group_id, created_at DESC);
CREATE INDEX ad_campaign_group_bid_log_campaign ON ad_campaign_group_bid_log(tenant_id, campaign_id, created_at DESC);

-- ===========================================================================
-- 5. ad_campaign_structure_copy · 复制结构审计
-- ===========================================================================
CREATE TABLE ad_campaign_structure_copy (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 源活动与目标活动（复制出来的目标活动**真的落库**，不是一份计划）
  source_campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, source_campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,
  target_campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, target_campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- 复制到哪个店铺
  target_shop_code varchar(64) NOT NULL,
  -- 复制到哪个 ASIN（listing）；为空表示沿用源活动的投放商品
  target_listing_id uuid,
  FOREIGN KEY (tenant_id, target_listing_id)
    REFERENCES listing(tenant_id, id) ON DELETE SET NULL (target_listing_id),

  -- 🔴 KEEP_PRODUCTS  沿用源活动的投放商品（**只允许同店铺**：
  --   listing 属于店铺，跨店铺沿用商品在物理上就不成立）
  --   TARGET_LISTING 改投指定的 listing（该 listing 必须属于目标店铺）
  product_mode text NOT NULL
    CHECK (product_mode IN ('KEEP_PRODUCTS','TARGET_LISTING')),

  -- 复制了多少（结构副本**真的写进去了**）
  group_count int NOT NULL DEFAULT 0 CHECK (group_count >= 0),
  product_count int NOT NULL DEFAULT 0 CHECK (product_count >= 0),
  creative_count int NOT NULL DEFAULT 0 CHECK (creative_count >= 0),
  -- 🔴 恒为 0：平台侧还没建这个活动，关键词 id 无从分配。
  --   这一列存在的意义就是让「关键词没被复制」成为**可查的事实**，而不是一句说明。
  keyword_count int NOT NULL DEFAULT 0 CHECK (keyword_count = 0),

  requested_by uuid NOT NULL,
  reason text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_campaign_structure_copy_source ON ad_campaign_structure_copy(tenant_id, source_campaign_id, created_at DESC);
CREATE INDEX ad_campaign_structure_copy_target ON ad_campaign_structure_copy(tenant_id, target_campaign_id);
CREATE INDEX ad_campaign_structure_copy_shop ON ad_campaign_structure_copy(tenant_id, target_shop_code);

-- ===========================================================================
-- 6. 多租户隔离：五张表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V203 / V273 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_campaign_group', 'ad_campaign_group_product', 'ad_campaign_creative',
    'ad_campaign_group_bid_log', 'ad_campaign_structure_copy' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**活动 → 店铺**收敛（照抄 V273 的 ad_campaign_daily_metric 同构）
-- ---------------------------------------------------------------------------
CREATE POLICY data_scope ON ad_campaign_group AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_group.tenant_id AND c.id=ad_campaign_group.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_group.tenant_id AND c.id=ad_campaign_group.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_campaign_group_product AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_group_product.tenant_id AND c.id=ad_campaign_group_product.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_group_product.tenant_id AND c.id=ad_campaign_group_product.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_campaign_creative AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_creative.tenant_id AND c.id=ad_campaign_creative.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_creative.tenant_id AND c.id=ad_campaign_creative.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_campaign_group_bid_log AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_group_bid_log.tenant_id AND c.id=ad_campaign_group_bid_log.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_group_bid_log.tenant_id AND c.id=ad_campaign_group_bid_log.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_campaign_structure_copy AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_structure_copy.tenant_id AND c.id=ad_campaign_structure_copy.source_campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_structure_copy.tenant_id AND c.id=ad_campaign_structure_copy.source_campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 7. 账号与授权（沿用 V1/V146/V273 的条件建角色，保证迁移可重复执行）
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
  ad_campaign_group, ad_campaign_group_product, ad_campaign_creative,
  ad_campaign_group_bid_log, ad_campaign_structure_copy TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V146 / V273 同口径）。
REVOKE ALL ON
  ad_campaign_group, ad_campaign_group_product, ad_campaign_creative,
  ad_campaign_group_bid_log, ad_campaign_structure_copy FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：广告活动详情属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.1.2.2 广告类型差异同族，因此 identity 服务零改动。
