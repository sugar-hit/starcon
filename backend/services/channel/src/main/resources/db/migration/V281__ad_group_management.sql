-- ===========================================================================
-- 14.1.2.1 广告组管理（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.1.2.1 广告组管理
--   - 职责：管理广告组：关联投放商品、匹配类型、定向设置、默认竞价，查看组级表现。
--   - 输入：广告组设置；关联商品选择（从 4 listing 中选）。
--   - 输出：广告组记录 + 组级报表入口。
--   - 关系：上游 = 14.1.1.2；下游 = 14.1.3.1 关键词、14.2.1.1 竞价。
--           **广告组与 SKU 的映射是利润归因的基础**——投放的是 listing，归因要落到内部 SKU。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 ALTER 上游已交付的广告组表 + CREATE 一张审计表**：
--   * V277 `ad_campaign_group`（**已交付的上游 14.1.1.2**，同库同服务）——
--     广告组本体。**不重复建表**：同一个广告组在两处各有一份，改一边不改另一边，
--     最后谁也说不清「这个组现在是哪个状态」。
--   * V277 `ad_campaign_group_product`（同上游）—— 投放商品（广告组 → listing）。
--   * V171 `listing` + `listing_variant`（**已交付的上游 4.1.1.1**，同库同服务）——
--     投放商品指向的 listing 与「平台变体 → 内部 SKU」的映射现状（**只读**）。
--   * V273 `ad_campaign`（**已交付的上游 14.1.1.1**）—— 活动本体（复合 FK 带 tenant_id）。
--
-- 🔴 **不引用**任何跨服务表：catalog 的 sku / trade 的订单 / finance 的账，
--   一律**不 join、不建 FK、不建视图**。`listing_variant.internal_sku_id` 是
--   **逻辑 ID**（catalog V1 sku 的 id），只读带出，不跨库校验它存不存在。
--
-- 🔴 **不引用**同批并行车道的任何表：14.3.1.2 广告组与词报表（V283）本批**不在**本车道，
--   本模块**不 import 它的类、不建它的表、不猜它的 URL**。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 为什么是「ALTER 上游表 + 一张审计表」，而不是「再建一张广告组表」
-- ---------------------------------------------------------------------------
-- 14.1.1.2 的台账明确写了：「广告组的完整管理（定向设置 / 组级报表入口）归 14.1.2.1
-- （未交付，本模块只给结构的读与『复制结构』这一个写动作）」——
-- 也就是说 **广告组的写入口归本模块**，而**事实表只有一张**（V277 的 `ad_campaign_group`）。
-- 于是本迁移做的是：
--   (1) 在 `ad_campaign_group` 上**补齐蓝图点名、而 V277 还没有的列**：
--       匹配类型（`match_type`）、定向设置原文（`targeting_settings`）；
--   (2) 在 `ad_campaign_group` 上**补齐组级的「本地已改 / 尚未回写平台」标记**：
--       `pending_platform_writeback` + `local_changed_at`；
--   (3) **新建一张审计表** `ad_group_operation_log`：组级每一次写都要留痕
--       （14 章关键约束：「批量调整预算与竞价要走规则并留审计」）。
--
-- 🔴 两条写路径的分工（写清楚，避免「谁在改这个组」这个问题无解）：
--   * 14.1.1.2 `POST /campaigns/{id}/structure` —— **平台侧结构同步登记**，
--     语义是「替换」：一次代表「这是平台返回的该活动当前完整结构」。
--   * 14.1.2.1（本模块）—— **运营对单个广告组的日常管理**：新建一组 / 改一组设置 /
--     设这一组的投放商品。粒度是**单组**，不是整个活动。
--   两者写在**同一张事实表**上，因为「这个广告组」这件事只有一份真相。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 匹配类型与定向：只存平台原文，不解读
-- ---------------------------------------------------------------------------
-- 14.1.3.2「投放定位解读」**未交付** ⇒ 定向与匹配类型**只存原文**：
--   * `match_type`         —— 匹配类型原文（如 EXACT / PHRASE / BROAD）。
--   * `targeting_type`     —— 定向类型原文（V277 已有，本迁移**不动它**）。
--   * `targeting_settings` —— 定向设置原文（平台返回的原文串，**不拆解、不归一**）。
-- 本模块**不解读**这些值，也**不据此给建议** —— 那正是 14.1.3.2 的职责。
--
-- 🔴 三列都是 **NOT NULL DEFAULT ''**：V277 已经交付的 INSERT（14.1.1.2 的
--   结构登记与复制结构）**不带**这三列，缺默认值会让那些已交付的老 INSERT 直接违约。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 组级指标：没有事实来源，所以本模块**不建**组级指标表
-- ---------------------------------------------------------------------------
-- 现有事实表里**没有组级指标表**：`ad_campaign_daily_metric`（V273）是
-- **campaign_id 维度**的，**没有 group_id**。
-- ⇒ 「查看组级表现」这一项**没有可算的真实指标**。
--
-- 🔴 本模块**不把活动级指标冒充组级指标**（那是假事实，比缺功能严重得多），
--   也**不建一张没人写的组级指标表**（空表会被界面渲染成「没有数据」，
--   那比明说「拿不到」严重得多 —— 与 14.1.1.2 处理「平台侧修改历史」同一款做法）。
--   处理方式：能力声明里该维度 **available=false + ownerModule=14.3.1.2 + 影响说明**，
--   同时给出**组级报表入口**（指向 14.3.1.2 的路由标识）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 组级预算沿用活动预算口径 **NUMERIC(19,4)**（与 V273 / V277 同口径）。
-- **没有一列是 float/double** —— 组级预算直接参与 14.2.1.1 与 14.3.1.1 的计算。
--
-- 🔴 「组级预算为空」= **继承活动预算**，不是 0（与 V277 同一纪律，
--   写成 0 会让「组没单独设预算」变成「这个组不许花钱」，是相反的两件事）。
--
-- ---------------------------------------------------------------------------
-- 五、多租户隔离与数据范围
-- ---------------------------------------------------------------------------
-- 新表 `ad_group_operation_log` tenant_id NOT NULL + ENABLE/FORCE RLS isolation
-- （照抄 V277 的写法），租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 另加 data_scope（RESTRICTIVE）策略：审计随**活动 → 店铺**收敛
-- （与 V277 的 ad_campaign_group / ad_campaign_group_product 同构）——
-- 不允许绕过店铺直接枚举广告组的操作历史。
--
-- ALTER 上去的两张上游表（`ad_campaign_group` / `ad_campaign_group_product`）
-- **沿用 V277 已有的 isolation + data_scope 策略**，本迁移不改它们 ——
-- 加列不改变可见性，动了反而是跨车道的隐性回归面。

-- ===========================================================================
-- 1. ALTER ad_campaign_group：补齐蓝图点名的列 + 组级回写标记
-- ===========================================================================

-- 🔴 匹配类型（平台原文）。NOT NULL DEFAULT ''：V277 已交付的老 INSERT 不带这列。
ALTER TABLE ad_campaign_group
  ADD COLUMN match_type varchar(64) NOT NULL DEFAULT '';

-- 🔴 定向设置（平台原文串，不拆解、不归一 —— 解读归 14.1.3.2，未交付）。
ALTER TABLE ad_campaign_group
  ADD COLUMN targeting_settings text NOT NULL DEFAULT '';

-- 🔴 组级的「本地已改、尚未回写平台」标记。
--   平台回写队列（平台适配层 channel/vendor）**未交付** ⇒ 本模块的所有本地写
--   （改状态 / 改定向 / 改匹配类型 / 改组级预算 / 改投放商品）都只落本地，
--   必须在这里显式标出来 —— 否则界面上的「已暂停」会被当成平台侧已暂停，那是假事实。
--   与 V273 `ad_campaign.pending_platform_writeback`（活动级）同一语义，
--   这里是**组级**等价标记（改一个组不该把整个活动标成待回写）。
ALTER TABLE ad_campaign_group
  ADD COLUMN pending_platform_writeback boolean NOT NULL DEFAULT false;

-- 🔴 本地最后一次改动时刻（null = 本地没动过这个组）。
ALTER TABLE ad_campaign_group
  ADD COLUMN local_changed_at timestamptz;

COMMENT ON COLUMN ad_campaign_group.match_type IS
  '14.1.2.1 · 匹配类型（平台原文，不解读；解读归 14.1.3.2 未交付）';
COMMENT ON COLUMN ad_campaign_group.targeting_settings IS
  '14.1.2.1 · 定向设置原文（平台原文串，不拆解不归一；解读归 14.1.3.2 未交付）';
COMMENT ON COLUMN ad_campaign_group.pending_platform_writeback IS
  '14.1.2.1 · 组级「本地已改、尚未回写平台」标记（平台回写队列未交付）';
COMMENT ON COLUMN ad_campaign_group.local_changed_at IS
  '14.1.2.1 · 本地最后一次改动时刻（null = 本地没动过这个组）';

-- ===========================================================================
-- 2. ad_group_operation_log · 广告组操作审计
-- ===========================================================================
-- 14 章关键约束：「批量调整预算与竞价要走规则并留审计」。
-- V277 的 `ad_campaign_group_bid_log` 只记**改竞价**（单活动单组），
-- 组设置 / 投放商品 / 新建组这几种写动作**无处留痕** —— 本表补上。
CREATE TABLE ad_group_operation_log (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 14.1.1.1 活动（复合 FK 带 tenant_id）===
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 目标广告组 id：**不建 FK**。
  --   审计要能记下「我想改的那个组压根不存在」—— 有 FK 的话这条审计自己写不进去，
  --   「为什么这条没生效」就永远查不出来（与 V277 的 ad_campaign_group_bid_log 同一纪律）。
  group_id uuid NOT NULL,

  -- 写动作种类：
  --   CREATE_GROUP     新建广告组（平台侧还没有这个组）
  --   UPDATE_SETTINGS  改组设置（组名 / 状态 / 组级预算 / 定向原文 / 匹配类型原文）
  --   SET_PRODUCTS     设投放商品（替换语义）
  operation varchar(32) NOT NULL
    CHECK (operation IN ('CREATE_GROUP','UPDATE_SETTINGS','SET_PRODUCTS')),

  -- 🔴 变更前后的**设置快照**（紧凑 JSON 原文）。
  --   为什么是快照而不是「哪一列变了」：组设置是一个整体（组名 + 状态 + 预算 + 定向 + 匹配类型），
  --   拆成列会让审计随列增减而漂移；快照在复盘时不用猜「当时到底是什么值」。
  before_value text NOT NULL DEFAULT '',
  after_value text NOT NULL DEFAULT '',

  -- 🔴 三种结果**都**留痕（APPLIED / REJECTED / SKIPPED）——
  --   被拒的那几条同样要能复盘「为什么这条没生效」，只留成功的那几条就查不出来。
  result varchar(16) NOT NULL CHECK (result IN ('APPLIED','REJECTED','SKIPPED')),
  message text NOT NULL DEFAULT '',

  -- 谁发起的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,
  reason text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_group_operation_log_group ON ad_group_operation_log(tenant_id, group_id, created_at DESC);
CREATE INDEX ad_group_operation_log_campaign ON ad_group_operation_log(tenant_id, campaign_id, created_at DESC);

-- ===========================================================================
-- 3. 多租户隔离：ad_group_operation_log tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V273 / V277 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[ 'ad_group_operation_log' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**活动 → 店铺**收敛（照抄 V277 的 ad_campaign_group 同构）
-- ---------------------------------------------------------------------------
CREATE POLICY data_scope ON ad_group_operation_log AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_group_operation_log.tenant_id AND c.id=ad_group_operation_log.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_group_operation_log.tenant_id AND c.id=ad_group_operation_log.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 4. 账号与授权（沿用 V1/V146/V273/V277 的条件建角色，保证迁移可重复执行）
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
  ad_group_operation_log TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V146 / V273 / V277 同口径）。
REVOKE ALL ON ad_group_operation_log FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：广告组管理属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.1.1.2 活动详情、14.1.2.2 广告类型差异同族，因此 identity 服务零改动。
