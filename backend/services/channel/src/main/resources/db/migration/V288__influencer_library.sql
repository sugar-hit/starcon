-- ===========================================================================
-- 14.5.1.1 达人库（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.5.1.1 达人库
--   - 职责：管理达人/联盟资源：达人档案（类目、粉丝层级、带货 GMV、互动率、合作历史）、
--           标签分组、评级与黑名单。
--   - 输入：达人数据同步（平台接口或手动录入）；标签与评级。
--   - 输出：达人库 + 筛选检索。
--   - 关系：上游 = 平台数据；下游 = 14.5.1.2 建联、15 客户与客服。
--           **达人效果数据（GMV/退款率）必须入库**，否则无法筛选优质达人。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 策略 / 触发器**，零 ALTER 任何既有表：
--   * V1 `platform`（全局平台码表）+ V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
--   * V273 `ad_campaign`（**同库同服务**，14.1.1.1 已交付）—— 合作历史的「活动」挂它，
--     写法照抄 V273 自己给 `ad_campaign_group_product` 用的 `listing_id` + 复合 FK
--
-- ---------------------------------------------------------------------------
-- 一、🔴 四条事实边界（写进库，不是写进注释而已）
-- ---------------------------------------------------------------------------
-- 1. **平台达人数据适配器未交付 ⇒ 同步只能是「手动录入」。**
--    channel 的适配层（`vendor/`）目前**只有 OAuth 适配器**（Amazon / Shopify / eBay /
--    Walmart / Temu / Lazada / Shopee / TikTok Shop 共 13 个文件），**没有任何达人数据
--    适配器**。所以本表有 `sync_source`（MANUAL / PLATFORM_API）与 `platform_sync_status`
--    两列，且服务层的同步端口对所有平台返回「未接入」——
--    **不编造一个假的平台接口，也不返回硬编码的达人列表**。
--
-- 2. **达人效果数据（GMV / 退款率）必须入库**（蓝图加粗句）。
--    所以 `gmv` / `gmv_currency` / `refund_rate` / `engagement_rate` 都是**真实的列**，
--    不是从别处 join 出来的临时值。没录就是 null（不可比），**不是 0**。
--
-- 3. **合作历史挂「活动」（ad_campaign），不是自由文本。**
--    `influencer_cooperation.campaign_id` → `ad_campaign(tenant_id,id)` 复合 FK。
--    自由文本的活动名会答不出「这个达人跟哪几个活动合作过」，而那正是这张表存在的理由。
--
-- 4. **14.5.2.1 带货归因未交付 ⇒ 档案 GMV 与合作合计是两个口径，不能混成一个数。**
--    `influencer.gmv` 是**档案口径**（手动录入 / 未来平台同步入库）；
--    「合作历史合计」是本地按 `influencer_cooperation` 行求和的**合作口径**。
--    归因口径（订单归因 + 佣金成本）归 14.5.2.1，交付后由它接管。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 粉丝层级为什么是触发器推导的列
-- ---------------------------------------------------------------------------
-- 粉丝层级是**粉丝数的函数**（1 万以下 NANO、10 万以下 MICRO、50 万以下 MID、
-- 100 万以下 MACRO、500 万以下 MEGA、其余 TOP）。写成普通列的话，服务层一次
-- 「改粉丝数忘了改层级」就会造出「10 万粉标成 MACRO」的行，而筛选按层级走 ——
-- 错标会直接把优质达人筛掉。
-- 所以层级由**触发器从粉丝数推导**（服务层写什么都不作数），照抄 V273 `ad_campaign`
-- 「归一类型那三列由档案说了算」的同一手法。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 评分为什么是「算出来的」而不是「手填的」
-- ---------------------------------------------------------------------------
-- 蓝图要求「评级」，同时要求「**达人效果数据必须入库，否则无法筛选优质达人**」。
-- 两句话合起来 ⇒ 评级必须**由效果数据算出来**，否则「优质」只是运营的主观标签。
--   * `influencer_rating_rule`（租户级评分口径）：三个权重（GMV / 互动率 / 退款率）
--     与三个归一化上限。DB CHECK 钉死「三个权重之和 = 1」——
--     权重不归一的评分口径算出来的分数没有可比性。
--   * `influencer_rating_tier`（档位）：分数区间 + 排序位。
--   * 服务层按生效口径算 `influencer.rating_score`，再落档位。
-- **口径未配置 ⇒ 评分与档位都是 null**（不是 0）：0 分会被读成「这个达人是 0 分」，
-- 而真相是「还没配口径」。
-- ---------------------------------------------------------------------------
-- 建表顺序（有外键依赖，顺序不能换）：
--   1 influencer_tag_group   标签分组（配置）
--   2 influencer_tag         标签（配置，挂分组）
--   3 influencer_rating_tier 评级档位（配置）
--   4 influencer_rating_rule 评分口径（配置）
--   5 influencer             达人档案（事实，引用档位）
--   6 influencer_tag_assignment 达人 × 标签（事实）
--   7 influencer_blacklist   黑名单（事实）
--   8 influencer_cooperation 合作历史（事实）
-- ===========================================================================

-- ===========================================================================
-- 1. influencer_tag_group · 标签分组
-- ===========================================================================
-- 蓝图：「标签分组」= 标签的组织方式（美妆达人 / 家居达人 / 高退货达人 …）。
-- 分组是**租户级配置事实**（不按店铺收敛），与 V284 `ad_promotion_guardrail`、
-- V278 `ad_budget_rule` 同一纪律。
CREATE TABLE influencer_tag_group (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  group_code varchar(64) NOT NULL,
  group_name varchar(200) NOT NULL,
  description text NOT NULL DEFAULT '',

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, group_code),
  CHECK (btrim(group_code) <> '' AND btrim(group_name) <> '')
);

CREATE INDEX influencer_tag_group_status ON influencer_tag_group(tenant_id, status);

-- ===========================================================================
-- 2. influencer_tag · 标签（属于一个分组）
-- ===========================================================================
CREATE TABLE influencer_tag (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 分组：标签必须挂在一个分组下（分组是标签的组织方式，不是达人属性）。
  group_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, group_id)
    REFERENCES influencer_tag_group(tenant_id, id) ON DELETE CASCADE,

  tag_code varchar(64) NOT NULL,
  tag_name varchar(200) NOT NULL,
  description text NOT NULL DEFAULT '',

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, tag_code),
  CHECK (btrim(tag_code) <> '' AND btrim(tag_name) <> '')
);

CREATE INDEX influencer_tag_group_idx ON influencer_tag(tenant_id, group_id, status);

-- ===========================================================================
-- 3. influencer_rating_tier · 评级档位
-- ===========================================================================
-- 档位 = 分数区间 + 排序位。分数落在哪一档由服务层算，**区间由租户自己配**。
CREATE TABLE influencer_rating_tier (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  tier_code varchar(32) NOT NULL,
  tier_name varchar(100) NOT NULL,

  -- 🔴 档位区间（评分口径算出来的分数落在 [min_score, max_score]）。
  min_score NUMERIC(19,6) NOT NULL CHECK (min_score >= 0 AND min_score <= 1),
  max_score NUMERIC(19,6) NOT NULL CHECK (max_score >= 0 AND max_score <= 1),

  -- 排序位：档位排序（「按评级排」）用这个，不靠 tier_code 的字典序
  -- （字典序会把 'A' 排在 'S' 前面 —— 那是错的）。
  sort_order int NOT NULL DEFAULT 0,

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, tier_code),
  CHECK (btrim(tier_code) <> '' AND btrim(tier_name) <> ''),
  CHECK (max_score > min_score)
);

CREATE INDEX influencer_rating_tier_status ON influencer_rating_tier(tenant_id, status, sort_order);

-- ===========================================================================
-- 4. influencer_rating_rule · 评分口径（租户级配置）
-- ===========================================================================
-- 一个租户**同一时刻只能有一套生效的评分口径**（partial unique index 钉死）。
-- 两套权重同时生效时，「这个达人是几分」就没有答案了。
CREATE TABLE influencer_rating_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  rule_code varchar(64) NOT NULL DEFAULT 'DEFAULT',

  -- 🔴 三个权重（GMV / 互动率 / 退款率），和必须是 1 —— DB CHECK 钉死。
  --   退款率是**负向**指标：它的得分是 (1 − 退款率归一)，权重为正表示「退款率越高扣分越多」。
  weight_gmv NUMERIC(19,6) NOT NULL CHECK (weight_gmv >= 0 AND weight_gmv <= 1),
  weight_engagement NUMERIC(19,6) NOT NULL CHECK (weight_engagement >= 0 AND weight_engagement <= 1),
  weight_refund NUMERIC(19,6) NOT NULL CHECK (weight_refund >= 0 AND weight_refund <= 1),

  -- 🔴 三个归一化上限：超过上限按满分算（不封顶的话一个超级达人能把所有人压成 0 分）。
  gmv_cap NUMERIC(19,4) NOT NULL CHECK (gmv_cap > 0),
  engagement_cap NUMERIC(19,6) NOT NULL CHECK (engagement_cap > 0),
  refund_cap NUMERIC(19,6) NOT NULL CHECK (refund_cap > 0),

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code),
  CHECK (weight_gmv + weight_engagement + weight_refund = 1)
);

CREATE UNIQUE INDEX influencer_rating_rule_single_active
  ON influencer_rating_rule(tenant_id) WHERE status = 'ACTIVE';

-- ===========================================================================
-- 5. influencer · 达人档案主表
-- ===========================================================================
CREATE TABLE influencer (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 2.1.1.1 平台目录（全局码表，无 tenant_id）===
  platform_id uuid NOT NULL REFERENCES platform(id),

  -- === 上游 2.3.1.1 店铺（UNIQUE(tenant_id,code)，复合 FK ⇒ 跨租户挂载不可能）===
  -- 达人挂在店铺下：运营不能绕过店铺直接枚举别家的达人库。
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE RESTRICT,

  -- 🔴 平台侧达人 id。**空串 = 还没登记平台侧 id**（手动录入的达人可能没有），
  --   所以唯一性用 partial unique index（只约束非空串），不让「都没登记」互相撞。
  external_influencer_id varchar(128) NOT NULL DEFAULT '',
  display_name varchar(200) NOT NULL,
  handle varchar(128) NOT NULL DEFAULT '',

  -- 🔴 类目：蓝图的「类目」（运营口径：美妆 / 家居 / 3C …）。
  --   自由文本而不是枚举：类目词表由平台与租户经营口径决定，
  --   收进 CHECK 枚举等于把词表写死在库里，改一次就得改一次迁移。
  category varchar(64) NOT NULL,

  -- 🔴 粉丝数。粉丝层级由**触发器**从它推导（见第二节）。
  follower_count bigint NOT NULL DEFAULT 0 CHECK (follower_count >= 0),
  follower_tier text NOT NULL CHECK (follower_tier IN (
    'NANO','MICRO','MID','MACRO','MEGA','TOP')),

  -- 🔴 互动率（0 ~ 1）。null = **还没录**（不可比），不是 0。
  engagement_rate NUMERIC(19,6)
    CHECK (engagement_rate IS NULL OR (engagement_rate >= 0 AND engagement_rate <= 1)),

  -- 🔴🔴 带货 GMV（蓝图加粗句：「达人效果数据必须入库」）。
  --   null = **还没录**（不可比），不是 0 —— 0 GMV 会被读成「这个达人一单没带过货」。
  gmv NUMERIC(19,4) CHECK (gmv IS NULL OR gmv >= 0),
  gmv_currency char(3) NOT NULL DEFAULT '',

  -- 🔴 退款率（0 ~ 1）。null = 还没录。
  refund_rate NUMERIC(19,6)
    CHECK (refund_rate IS NULL OR (refund_rate >= 0 AND refund_rate <= 1)),

  -- 🔴 评级结果（由服务层按生效的评分口径算出来，见第三节）。
  --   评分口径未配置 ⇒ 两列都是 null（**不是 0 分**）。
  rating_score NUMERIC(19,6)
    CHECK (rating_score IS NULL OR (rating_score >= 0 AND rating_score <= 1)),
  rating_tier_id uuid,
  FOREIGN KEY (tenant_id, rating_tier_id)
    REFERENCES influencer_rating_tier(tenant_id, id) ON DELETE RESTRICT,

  -- 🔴 数据来源：MANUAL 手动录入 / PLATFORM_API 平台接口。
  --   平台达人数据适配器未交付 ⇒ 本批代码里**没有任何路径**写入 PLATFORM_API。
  sync_source text NOT NULL DEFAULT 'MANUAL'
    CHECK (sync_source IN ('MANUAL','PLATFORM_API')),

  -- 🔴 平台同步状态：LOCAL_ONLY 本地已录、平台侧还没有 / PENDING_SYNC 待同步
  --   / SYNCED 平台侧已一致（只能由平台达人适配器产生，**该适配器未交付**）
  --   / FAILED 同步失败。
  platform_sync_status text NOT NULL DEFAULT 'LOCAL_ONLY' CHECK (platform_sync_status IN (
    'LOCAL_ONLY','PENDING_SYNC','SYNCED','FAILED')),

  -- 本地状态：ACTIVE 在库可用 / INACTIVE 停用（**不等于拉黑** —— 拉黑是黑名单表的事）。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK (btrim(display_name) <> '' AND btrim(category) <> '')
);

-- 🔴 同一个平台侧达人 id 在同一店铺下只能有一条（一次同步重放不会造出两条）。
--   partial：只约束**真的登记了平台侧 id** 的行。
CREATE UNIQUE INDEX influencer_external_unique
  ON influencer(tenant_id, platform_id, shop_code, external_influencer_id)
  WHERE external_influencer_id <> '';

-- 🔴 筛选主索引：租户 + 类目 + 粉丝层级 + GMV（蓝图输出「达人库 + 筛选检索」的主路径）。
CREATE INDEX influencer_filter ON influencer(tenant_id, category, follower_tier, gmv DESC);
CREATE INDEX influencer_shop ON influencer(tenant_id, shop_code, status);
CREATE INDEX influencer_platform ON influencer(tenant_id, platform_id, status);
CREATE INDEX influencer_rating ON influencer(tenant_id, rating_score DESC, rating_tier_id);
CREATE INDEX influencer_followers ON influencer(tenant_id, follower_count DESC);

-- 🔴 粉丝层级由触发器推导：服务层写什么都不作数。
CREATE OR REPLACE FUNCTION stamp_influencer_follower_tier() RETURNS trigger AS $$
BEGIN
  NEW.follower_tier := CASE
    WHEN NEW.follower_count < 10000 THEN 'NANO'
    WHEN NEW.follower_count < 100000 THEN 'MICRO'
    WHEN NEW.follower_count < 500000 THEN 'MID'
    WHEN NEW.follower_count < 1000000 THEN 'MACRO'
    WHEN NEW.follower_count < 5000000 THEN 'MEGA'
    ELSE 'TOP'
  END;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER influencer_follower_tier_trg
  BEFORE INSERT OR UPDATE OF follower_count ON influencer
  FOR EACH ROW EXECUTE FUNCTION stamp_influencer_follower_tier();

-- ===========================================================================
-- 6. influencer_tag_assignment · 达人 × 标签（多对多）
-- ===========================================================================
-- 打标是**多对多**：一个达人多个标签、一个标签多个达人。
-- 独立关联表 + 复合 FK（带 tenant_id）⇒ 跨租户挂标签在物理上不可能。
CREATE TABLE influencer_tag_assignment (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  influencer_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, influencer_id)
    REFERENCES influencer(tenant_id, id) ON DELETE CASCADE,

  tag_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, tag_id)
    REFERENCES influencer_tag(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 打标时刻（timestamptz）：标签是带时间的判断（「上个月打过标」这件事要能查）。
  assigned_at timestamptz NOT NULL DEFAULT now(),
  note text NOT NULL DEFAULT '',

  UNIQUE(tenant_id, id),
  -- 同一个达人对同一个标签只挂一次（重复打标是幂等的，不是报错）
  UNIQUE(tenant_id, influencer_id, tag_id)
);

-- 🔴 「租户 + 标签」检索入口：按标签反查达人。
CREATE INDEX influencer_tag_assignment_tag ON influencer_tag_assignment(tenant_id, tag_id, influencer_id);
CREATE INDEX influencer_tag_assignment_influencer ON influencer_tag_assignment(tenant_id, influencer_id);

-- ===========================================================================
-- 7. influencer_blacklist · 黑名单（拉黑原因 / 生效时间 / 解除记录）
-- ===========================================================================
CREATE TABLE influencer_blacklist (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  influencer_id uuid NOT NULL,
  -- ON DELETE RESTRICT：有黑名单记录的达人是**举证凭据**，不许被物理删掉 ——
  --   悄悄删掉会让「我们拉黑过这个达人」这件事无声消失。
  FOREIGN KEY (tenant_id, influencer_id)
    REFERENCES influencer(tenant_id, id) ON DELETE RESTRICT,

  -- 🔴 拉黑原因：封闭词表 + 自由文本补充。词表里**没有「其它」桶** ——
  --   「其它」会让原因分析失去意义（所有说不清的都进「其它」）。
  reason_code text NOT NULL CHECK (reason_code IN (
    'FRAUD','INFRINGEMENT','POOR_QUALITY','NON_COMPLIANCE','BRAND_SAFETY','DATA_QUALITY')),
  reason text NOT NULL DEFAULT '',

  -- 🔴 生效时间（timestamptz）：拉黑不是「现在」，是一个有起点的区间事实。
  effective_at timestamptz NOT NULL DEFAULT now(),
  -- 🔴 解除记录：解除时刻 + 解除理由。**解除是留痕，不是删行** ——
  --   「什么时候解的、为什么解的」要能查，否则误拉黑无法复盘。
  released_at timestamptz,
  released_reason text NOT NULL DEFAULT '',

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK (released_at IS NULL OR released_at >= effective_at),
  -- 生效中的黑名单必须没有解除时刻；已解除的必须有解除时刻（否则「已解除」查不出来）。
  CHECK ((status = 'ACTIVE' AND released_at IS NULL) OR (status = 'RELEASED' AND released_at IS NOT NULL))
);

-- 🔴 一个达人**同时只能有一条生效中的黑名单**（partial unique index）。
--   已解除的记录保留下来做历史 —— 所以这里不是普通 UNIQUE。
CREATE UNIQUE INDEX influencer_blacklist_active
  ON influencer_blacklist(tenant_id, influencer_id) WHERE status = 'ACTIVE';

-- 🔴 「租户 + 黑名单」检索入口。
CREATE INDEX influencer_blacklist_lookup ON influencer_blacklist(tenant_id, status, effective_at DESC);
CREATE INDEX influencer_blacklist_influencer ON influencer_blacklist(tenant_id, influencer_id, status);

-- ===========================================================================
-- 8. influencer_cooperation · 合作历史（达人 × 活动）
-- ===========================================================================
CREATE TABLE influencer_cooperation (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  influencer_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, influencer_id)
    REFERENCES influencer(tenant_id, id) ON DELETE RESTRICT,

  -- 🔴 合作的活动：同库同服务的 14.1.1.1 广告活动（复合 FK 带 tenant_id）。
  --   写自由文本的活动名会答不出「这个达人跟哪几个活动合作过」。
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE RESTRICT,

  -- === 上游 2.3.1.1 店铺（复合 FK 带 tenant_id）===
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE RESTRICT,

  -- 合作状态：PLANNED 筹备中 / ACTIVE 合作中 / COMPLETED 已完成 / CANCELLED 已取消
  status text NOT NULL DEFAULT 'PLANNED' CHECK (status IN (
    'PLANNED','ACTIVE','COMPLETED','CANCELLED')),

  -- 🔴 合作起止（timestamptz）：服务层 OffsetDateTime 绑定 + values 位置写
  --   `?::timestamptz`（jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒）。
  started_at timestamptz,
  ended_at timestamptz,
  CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at > started_at),

  -- 🔴 合作效果：GMV / 订单量 / 佣金 / 退款率。
  --   null = **还没录**（不可比），不是 0。
  gmv NUMERIC(19,4) CHECK (gmv IS NULL OR gmv >= 0),
  order_count NUMERIC(19,6) CHECK (order_count IS NULL OR order_count >= 0),
  commission NUMERIC(19,4) CHECK (commission IS NULL OR commission >= 0),
  refund_rate NUMERIC(19,6)
    CHECK (refund_rate IS NULL OR (refund_rate >= 0 AND refund_rate <= 1)),
  currency char(3) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个达人在同一个活动上只登记一条合作（重复登记是幂等的）
  UNIQUE(tenant_id, influencer_id, campaign_id)
);

CREATE INDEX influencer_cooperation_influencer
  ON influencer_cooperation(tenant_id, influencer_id, started_at DESC);
CREATE INDEX influencer_cooperation_campaign ON influencer_cooperation(tenant_id, campaign_id);
CREATE INDEX influencer_cooperation_shop ON influencer_cooperation(tenant_id, shop_code, status);

-- ===========================================================================
-- 9. 多租户隔离：八张表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V93 / V146 / V273 / V277 / V284 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'influencer', 'influencer_tag_group', 'influencer_tag', 'influencer_tag_assignment',
    'influencer_rating_tier', 'influencer_rating_rule',
    'influencer_blacklist', 'influencer_cooperation' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（照抄 V273 `ad_campaign` 同构）
-- ---------------------------------------------------------------------------
-- 达人库挂在店铺下，运营不能绕过店铺直接枚举别家的达人。
-- 标签分组 / 标签 / 评级档位 / 评分口径是**租户级配置事实**，不按店铺收敛 ——
-- 与 V284 `ad_promotion_guardrail`、V278 `ad_budget_rule` 同一纪律。
CREATE POLICY data_scope ON influencer AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer.tenant_id AND s.code=influencer.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer.tenant_id AND s.code=influencer.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON influencer_tag_assignment AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM influencer i JOIN shop s ON s.tenant_id=i.tenant_id AND s.code=i.shop_code
             WHERE i.tenant_id=influencer_tag_assignment.tenant_id AND i.id=influencer_tag_assignment.influencer_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM influencer i JOIN shop s ON s.tenant_id=i.tenant_id AND s.code=i.shop_code
             WHERE i.tenant_id=influencer_tag_assignment.tenant_id AND i.id=influencer_tag_assignment.influencer_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON influencer_blacklist AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM influencer i JOIN shop s ON s.tenant_id=i.tenant_id AND s.code=i.shop_code
             WHERE i.tenant_id=influencer_blacklist.tenant_id AND i.id=influencer_blacklist.influencer_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM influencer i JOIN shop s ON s.tenant_id=i.tenant_id AND s.code=i.shop_code
             WHERE i.tenant_id=influencer_blacklist.tenant_id AND i.id=influencer_blacklist.influencer_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON influencer_cooperation AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer_cooperation.tenant_id AND s.code=influencer_cooperation.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer_cooperation.tenant_id AND s.code=influencer_cooperation.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V273/V277/V284 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  influencer, influencer_tag_group, influencer_tag, influencer_tag_assignment,
  influencer_rating_tier, influencer_rating_rule,
  influencer_blacklist, influencer_cooperation TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V277 / V284 同口径）。
REVOKE ALL ON
  influencer, influencer_tag_group, influencer_tag, influencer_tag_assignment,
  influencer_rating_tier, influencer_rating_rule,
  influencer_blacklist, influencer_cooperation FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：达人库管理属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.2.2.1 预算分配、14.4.1.1 促销列表同族，因此 identity 服务零改动，跨车道回归面 = 0。
