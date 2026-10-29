-- ===========================================================================
-- 14.3.2.1 归因分析（channel 服务）
--
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.3.2.1 归因分析
--   - 职责：广告归因：把广告花费归因到 SKU/订单（支持平台归因窗口口径），
--           计算广告后净利、TACOS、增量效果估计。
--   - 输入：平台广告归因数据；订单数据；利润数据；归因规则配置（末次点击/平台归因窗口）。
--   - 输出：归因明细 + 广告后利润报表。
--   - 关系：上游 = 14.3.1.3、13.4.1.2；下游 = 13.4 利润、5 定价。
--           **归因规则必须固定并公示**，规则变了历史报表不可比——这是广告与财务对账最容易吵架的点。
--
-- ---------------------------------------------------------------------------
-- 一、本迁移只新建五张表，零 ALTER 任何既有表 ⇒ 跨车道 DDL 回归面 = 0
-- ---------------------------------------------------------------------------
--   1 ad_attribution_rule   归因规则（租户级配置事实；一个租户同一时刻只有一套生效口径）
--   2 ad_attribution_order  订单级归因候选（平台广告归因数据：订单 × SKU × 活动的金额 + 币种）
--   3 ad_attribution_click  点击触点（一个订单的多个点击 ⇒ 末次点击口径需要它们）
--   4 ad_attribution_fact   归因结果（规则 × 订单 ⇒ 命中哪一击、归到了哪个 SKU、金额多少）
--   5 ad_attribution_export 导出留痕（**带规则快照** ⇒ 事后能解释「当时用的是哪套口径」）
--
-- 🔴 **本模块不建第二套广告事实表**（照抄 14.1.1.1 / 14.3.1.3 的纪律）：
--   * 活动级广告花费 / 曝光 / 点击 / 订单 / 销售额 ⇒ 直接读 14.1.1.1 的
--     `ad_campaign_daily_metric`（V273，本服务同库）。
--   * 活动 ↔ 投放商品的投放关系 ⇒ 直接读 14.1.1.2 的
--     `ad_campaign_group_product`（V277，本服务同库）。
--   * Listing ↔ 内部 SKU 的映射现状 ⇒ 直接读 4.1.1.1 的
--     `listing` / `listing_variant`（V171，本服务同库）。
--   * 总销售额 / 净利 / 件数 / 广告费 ⇒ 走**授权只读 API**读 13.4.1.2（reporting 服务），
--     跨服务**零 SQL / 零 FK / 零视图**（AGENTS.md 模块边界纪律）。
--
-- 🔴 **本模块与 14.3.1.3 的分工（两者的「广告花费」不是同一个数，必须分开说）**：
--   * 14.3.1.3 给的是**投放关系分摊**：活动 → Listing → SKU 两级等分（回答
--     「这个商品上分摊了多少广告费」）。
--   * 本模块给的是**订单级归因**：哪一笔订单归到了哪个 SKU（回答
--     「这些订单是广告带来的吗」）。
--   两者**不可互相替代**：同一个 SKU 的「广告后净利」在两张报表上不相等是**正常的**，
--   口径说明随响应一起返回（见服务层的 CALIBER_NOTE）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 归因规则必须固定并公示（规则变了历史报表不可比）
-- ---------------------------------------------------------------------------
--   * 规则是**租户级配置事实**，partial unique index 保证一个租户同时
--     **只有一套生效口径** —— 两套口径同时生效时，「这单归给了谁」没有答案。
--   * 每条归因结果**自带规则快照**（规则编码 / 版本 / 归因模型 / 归因窗口天数）：
--     规则改了以后，历史归因仍然能被解释清楚（快照是**当时的**口径），
--     报表也能按 rule 过滤出「当前口径」与「历史口径」两段。
--   * 规则行本身**可以被改**（改完 version+1），但**已算出的归因结果不重算**
--     —— 已归因是事实（报表已经拿它跟财务对过账了）。
--   * 导出留痕也带规则快照（`ad_attribution_export`）⇒ 事后翻出一张 CSV 时，
--     能回答「这张 CSV 是哪套口径导出来的」。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 归不上的金额单列，不摊给任何 SKU，也不摊成 0
-- ---------------------------------------------------------------------------
--   * `ad_attribution_order` 里 internal_sku_id 为空 ⇒ 归因到了订单、归不到 SKU。
--   * 窗口内没有任何合格点击（NO_ELIGIBLE_CLICK）/ 平台没标记归因点击
--     （NO_PLATFORM_ATTRIBUTED_CLICK）⇒ 这一单归因不成立。
--   * 这两类金额在汇总里**单列**（unattributedSpend / unattributedSales），
--     摊成 0 就是说「这些广告没花钱、没带来销售」。
--
-- ---------------------------------------------------------------------------
-- 四、金额 / 数量 / 币种纪律
-- ---------------------------------------------------------------------------
--   * 金额 NUMERIC(19,4)、数量 NUMERIC(19,6)（与 V273 同口径），**没有浮点**。
--   * 币种 char(3) 随归因行落库 ⇒ 跨币种**绝不相加**（本仓没有汇率源）。
--   * 点击时刻 / 下单时刻用 timestamptz ⇒ 服务层用 OffsetDateTime 绑定，
--     values 位置写 `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime)
--     会静默偏 −28800s）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. ad_attribution_rule · 归因规则（租户级配置事实）
-- ---------------------------------------------------------------------------
CREATE TABLE ad_attribution_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 规则编码（租户内唯一）。历史规则不删除、不改名 ⇒ 日后可回看「当时用的是哪套口径」。
  rule_code varchar(64) NOT NULL CHECK(btrim(rule_code) <> ''),

  -- 🔴 归因模型（决定「一笔订单归给哪一次点击」）：
  --   LAST_CLICK        末次点击：窗口内**最后一次点击**拿到归因（其余点击落选）
  --   PLATFORM_WINDOW   平台归因窗口：采信平台自己标记的那一次归因点击
  --                      （平台没标记 ⇒ 这一单归因不成立，**不替平台编一个**）
  attribution_model text NOT NULL CHECK(attribution_model IN (
    'LAST_CLICK','PLATFORM_WINDOW')),

  -- 🔴 归因窗口（天）：点击到下单的最大间隔。超窗的点击**不参与**归因。
  --   0 = 只有与下单同时刻的点击才算（等价于无窗口）。
  attribution_window_days int NOT NULL DEFAULT 30
    CHECK(attribution_window_days >= 0 AND attribution_window_days <= 365),

  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','PAUSED')),

  -- 规则版本：每次改动 +1。归因结果里的快照带版本 ⇒ 历史口径可追。
  version bigint NOT NULL DEFAULT 0,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code)
);

-- 🔴 一个租户同时只能有一套生效口径（两套口径同时生效 ⇒ 归因没有答案）。
CREATE UNIQUE INDEX ad_attribution_rule_single_active
  ON ad_attribution_rule(tenant_id) WHERE status = 'ACTIVE';

CREATE INDEX ad_attribution_rule_status
  ON ad_attribution_rule(tenant_id, status, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. ad_attribution_order · 订单级归因候选（平台广告归因数据）
-- ---------------------------------------------------------------------------
-- 一行 = 一个订单 × 一个内部 SKU × 一个活动 上，平台报告的归因金额。
CREATE TABLE ad_attribution_order (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 14.1.1.1 广告活动（同库同服务，复合 FK 带 tenant_id）===
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- === 上游 2.3.1.1 店铺（复合 FK 带 tenant_id ⇒ 跨租户挂载不可能）===
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 平台侧订单号（原文照抄）。跨渠道**不唯一** ⇒ 唯一键里带平台与活动。
  platform_code varchar(64) NOT NULL CHECK(btrim(platform_code) <> ''),
  external_order_no varchar(128) NOT NULL CHECK(btrim(external_order_no) <> ''),

  -- 下单时刻（平台报告的订单时刻）。
  order_at timestamptz NOT NULL,

  -- 🔴 内部 SKU（4.1.1.1 映射现状位）。**可空** = 平台归因到了这一单，
  --   但归不到唯一内部 SKU ⇒ 归因不成立，不摊给任何 SKU（不摊成 0）。
  --   刻意**不建外键**：内部 SKU 归 catalog 服务所有，跨服务不建 FK。
  internal_sku_id uuid,

  -- 🔴 币种随归因行落库 ⇒ 跨币种绝不相加（没有汇率源就不换算）。
  currency char(3) NOT NULL DEFAULT ''
    CHECK(currency = '' OR currency ~ '^[A-Z]{3}$'),

  -- 🔴 金额 NUMERIC(19,4) / 数量 NUMERIC(19,6)（与 V273 同口径），没有浮点。
  attributed_spend numeric(19,4) NOT NULL DEFAULT 0 CHECK(attributed_spend >= 0),
  attributed_sales numeric(19,4) NOT NULL DEFAULT 0 CHECK(attributed_sales >= 0),
  attributed_orders numeric(19,6) NOT NULL DEFAULT 0 CHECK(attributed_orders >= 0),
  -- 🔴 广告归因**件数**（平台归因报表给的 units）。
  --   它是「增量效果估计」的分母侧原料（与 13.4.1.2 的总件数配对算单件均价差）。
  attributed_units numeric(19,6) NOT NULL DEFAULT 0 CHECK(attributed_units >= 0),

  -- 平台这批归因数据的数据截至时刻（对账时要看「算的是哪一版」）。
  data_as_of timestamptz NOT NULL DEFAULT now(),

  imported_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(btrim(external_order_no) <> '')
);

-- 🔴 幂等键：同一订单（平台 + 订单号 + 活动 + SKU）重复导入是**刷新**，不是插重复行。
--   internal_sku_id 可空 ⇒ 用 coalesce 让 NULL 行之间也能冲突（否则 PG 视 NULL != NULL，
--   同一单会被插成多行 ⇒ 金额翻倍）。
CREATE UNIQUE INDEX ad_attribution_order_natural
  ON ad_attribution_order(
    tenant_id, campaign_id, platform_code, external_order_no,
    coalesce(internal_sku_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX ad_attribution_order_window
  ON ad_attribution_order(tenant_id, order_at);
CREATE INDEX ad_attribution_order_sku
  ON ad_attribution_order(tenant_id, internal_sku_id, order_at);

-- ---------------------------------------------------------------------------
-- 3. ad_attribution_click · 点击触点（末次点击口径需要它）
-- ---------------------------------------------------------------------------
-- 一行 = 一次点击。它挂在**订单**上（不是活动上）：一次点击影响哪一单是平台的事实。
CREATE TABLE ad_attribution_click (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  order_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, order_id)
    REFERENCES ad_attribution_order(tenant_id, id) ON DELETE CASCADE,

  -- 平台侧点击 id（原文照抄）。
  click_id varchar(128) NOT NULL CHECK(btrim(click_id) <> ''),

  -- 点击时刻（timestamptz ⇒ 归因窗口是**真实的时间差**，不是日期差）。
  click_at timestamptz NOT NULL,

  -- 🔴 平台自己标记的归因点击（PLATFORM_WINDOW 口径用它）。
  --   平台没标记 ⇒ 不替平台编一个归因。
  platform_attributed boolean NOT NULL DEFAULT false,

  imported_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一订单下同一个点击 id 只登记一次（重复导入是刷新，不是插重复行）。
  UNIQUE(tenant_id, order_id, click_id)
);

CREATE INDEX ad_attribution_click_order
  ON ad_attribution_click(tenant_id, order_id, click_at DESC, click_id);

-- ---------------------------------------------------------------------------
-- 4. ad_attribution_fact · 归因结果（规则 × 订单）
-- ---------------------------------------------------------------------------
-- 一行 = 一条订单在**某一套规则**下的归因结论 + **规则快照**。
CREATE TABLE ad_attribution_fact (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 归因规则（复合 FK 带 tenant_id）===
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id)
    REFERENCES ad_attribution_rule(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 规则快照（口径固定并公示）：规则行后来被改了也不影响历史归因的可解释性。
  rule_code varchar(64) NOT NULL,
  rule_version bigint NOT NULL,
  attribution_model text NOT NULL CHECK(attribution_model IN (
    'LAST_CLICK','PLATFORM_WINDOW')),
  attribution_window_days int NOT NULL CHECK(attribution_window_days >= 0),

  -- === 源（订单级归因候选）===
  order_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, order_id)
    REFERENCES ad_attribution_order(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 源快照（从订单行复制一份 ⇒ 报表不必再 join 就能解释清楚）
  campaign_id uuid NOT NULL,
  shop_code varchar(64) NOT NULL,
  platform_code varchar(64) NOT NULL,
  external_order_no varchar(128) NOT NULL,
  internal_sku_id uuid,
  currency char(3) NOT NULL DEFAULT '',
  order_at timestamptz NOT NULL,

  -- 🔴 命中哪一次点击（LAST_CLICK 口径下是窗口内最后一次点击）。
  --   空串 = 没有命中（归因不成立）；platform_attributed=false 且模型是
  --   PLATFORM_WINDOW 时，chosen_click_id 为空 ⇒ 平台没给归因点击。
  chosen_click_id varchar(128) NOT NULL DEFAULT '',
  chosen_click_at timestamptz,

  -- 🔴 归因状态（**点击 / 模型口径**的结论）：
  --   ATTRIBUTED                  规则命中了一次点击（模型侧归因成立）
  --   NO_ELIGIBLE_CLICK            窗口内没有任何合格点击（超窗 / 没有点击）
  --   NO_PLATFORM_ATTRIBUTED_CLICK 平台归因窗口口径下平台没标记归因点击
  -- 🔴 「归不到内部 SKU」**不是**这一列：它是 internal_sku_id 为空，
  --   与点击口径是**两个正交维度**（两者可以同时成立）。
  attribution_status text NOT NULL CHECK(attribution_status IN (
    'ATTRIBUTED','NO_ELIGIBLE_CLICK','NO_PLATFORM_ATTRIBUTED_CLICK')),

  -- 🔴 派生列（GENERATED ALWAYS ⇒ 服务层写不进去，绕过服务层也编不出
  --   与原始量对不上的口径）：**这一行进不进 SKU 报表** = 点击口径成立
  --   **且** 归到了唯一内部 SKU。报表的分子分母全部按这一列切，
  --   归不上的金额单列（不摊给任何 SKU，也不摊成 0）。
  attributed boolean GENERATED ALWAYS AS (
    attribution_status = 'ATTRIBUTED' AND internal_sku_id IS NOT NULL
  ) STORED,

  -- 🔴 归因金额（从订单行复制；状态非 ATTRIBUTED 时仍然保留 ⇒ 汇总里能算「归不上的钱」）
  attributed_spend numeric(19,4) NOT NULL DEFAULT 0 CHECK(attributed_spend >= 0),
  attributed_sales numeric(19,4) NOT NULL DEFAULT 0 CHECK(attributed_sales >= 0),
  attributed_orders numeric(19,6) NOT NULL DEFAULT 0 CHECK(attributed_orders >= 0),
  attributed_units numeric(19,6) NOT NULL DEFAULT 0 CHECK(attributed_units >= 0),

  -- 🔴 窗口内的合格点击数（0 ⇒ 没有合格点击；>1 且只有一次命中 ⇒ 落选了几次）。
  eligible_click_count int NOT NULL DEFAULT 0 CHECK(eligible_click_count >= 0),

  attributed_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 同一套规则的**同一个版本**下，同一订单只归因一次。
  --   版本进唯一键 ⇒ **改规则（version+1）后重跑产生的是新版本的事实**，
  --   旧版本的事实原样保留 —— 「规则变了历史报表不可比」这条纪律因此不会
  --   被自己破坏（不会出现「旧报表的行被新口径悄悄改写」）。
  UNIQUE(tenant_id, rule_id, rule_version, order_id)
);

CREATE INDEX ad_attribution_fact_window
  ON ad_attribution_fact(tenant_id, rule_id, rule_version, order_at);
CREATE INDEX ad_attribution_fact_sku
  ON ad_attribution_fact(tenant_id, rule_id, rule_version, internal_sku_id, order_at);
CREATE INDEX ad_attribution_fact_order
  ON ad_attribution_fact(tenant_id, order_id);
CREATE INDEX ad_attribution_fact_campaign
  ON ad_attribution_fact(tenant_id, campaign_id, order_at);
-- 🔴 报表汇总的聚合入口：按「进不进报表」这一列切（不分页的汇总走它）。
CREATE INDEX ad_attribution_fact_attributed
  ON ad_attribution_fact(tenant_id, rule_id, rule_version, attributed, order_at);

-- ---------------------------------------------------------------------------
-- 5. ad_attribution_export · 导出留痕（**带规则快照**）
-- ---------------------------------------------------------------------------
-- 🔴 本模块的纪律是「归因规则必须固定并公示」⇒ 导出的那张 CSV 必须能回答
--   「当时用的是哪套口径」。所以留痕里存**规则快照**（编码 / 版本 / 模型 / 窗口），
--   而不是只存「用了当前规则」—— 当前规则后来改了，事后就没法解释旧报表了。
CREATE TABLE ad_attribution_export (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 谁导的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,

  -- === 导出时用的规则快照（照抄当时的规则行，不做「按今天重新解释」）===
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id)
    REFERENCES ad_attribution_rule(tenant_id, id) ON DELETE RESTRICT,
  rule_code varchar(64) NOT NULL,
  rule_version bigint NOT NULL,
  attribution_model text NOT NULL CHECK(attribution_model IN (
    'LAST_CLICK','PLATFORM_WINDOW')),
  attribution_window_days int NOT NULL CHECK(attribution_window_days >= 0),

  -- 筛选条件（可空 = 那一维没筛）
  shop_code varchar(64),
  campaign_id uuid,
  sku_id uuid,
  currency char(3),
  date_from date,
  date_to date,

  -- 🔴 导出了多少行（按**分组后**的行数，不是明细行数）
  row_count int NOT NULL DEFAULT 0 CHECK(row_count >= 0),

  -- 🔴 这次导出是**对着哪个水位**算出来的。
  data_as_of timestamptz,

  -- 🔴 导出当时的逐指标口径定义与归因口径原文（一并留痕：日后不必猜「当时用的是哪份定义」）
  metric_definition_note text NOT NULL DEFAULT '',
  attribution_note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_attribution_export_recent
  ON ad_attribution_export(tenant_id, created_at DESC);
CREATE INDEX ad_attribution_export_actor
  ON ad_attribution_export(tenant_id, requested_by, created_at DESC);

-- ===========================================================================
-- 多租户隔离：五张表 tenant_id NOT NULL + ENABLE **且** FORCE ROW LEVEL SECURITY
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V273 / V279 / V290 / V402 同一套写法：
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_attribution_rule',
    'ad_attribution_order',
    'ad_attribution_click',
    'ad_attribution_fact',
    'ad_attribution_export' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（与 V273 / V279 / V290 / V402 同一口径）
-- ---------------------------------------------------------------------------
-- 归因规则是**租户级配置事实**，不按店铺收敛（与 V290 的 influencer_attribution_rule
-- 同纪律）—— 规则本身不是某个店铺的数据。
CREATE POLICY data_scope ON ad_attribution_order AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_attribution_order.tenant_id AND s.code=ad_attribution_order.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_attribution_order.tenant_id AND s.code=ad_attribution_order.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 点击触点随它所属的订单收敛（点击表自己不带店铺列）。
CREATE POLICY data_scope ON ad_attribution_click AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_attribution_order o WHERE o.tenant_id=ad_attribution_click.tenant_id AND o.id=ad_attribution_click.order_id
             AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=o.tenant_id AND s.code=o.shop_code
                AND channel_scope(channel_operation(),s.owner_id,s.department_id))))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_attribution_order o WHERE o.tenant_id=ad_attribution_click.tenant_id AND o.id=ad_attribution_click.order_id
             AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=o.tenant_id AND s.code=o.shop_code
                AND channel_scope(channel_operation(),s.owner_id,s.department_id))));

-- 归因结果随它所属的订单收敛。
CREATE POLICY data_scope ON ad_attribution_fact AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_attribution_order o WHERE o.tenant_id=ad_attribution_fact.tenant_id AND o.id=ad_attribution_fact.order_id
             AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=o.tenant_id AND s.code=o.shop_code
                AND channel_scope(channel_operation(),s.owner_id,s.department_id))))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_attribution_order o WHERE o.tenant_id=ad_attribution_fact.tenant_id AND o.id=ad_attribution_fact.order_id
             AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=o.tenant_id AND s.code=o.shop_code
                AND channel_scope(channel_operation(),s.owner_id,s.department_id))));

-- 导出留痕按**导出范围里的店铺**收敛：限定了店铺就必须在这个店铺的数据范围内；
-- 没限定店铺 ⇒ 要求导出者至少在一个店铺上有数据范围（否则他本来也导不出任何行）。
CREATE POLICY data_scope ON ad_attribution_export AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_attribution_export.tenant_id
                 AND (ad_attribution_export.shop_code IS NULL OR s.code=ad_attribution_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_attribution_export.tenant_id
                 AND (ad_attribution_export.shop_code IS NULL OR s.code=ad_attribution_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 账号与授权（沿用 V1/V273/V402 的条件建角色，保证迁移可重复执行）
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
  ad_attribution_rule, ad_attribution_order, ad_attribution_click, ad_attribution_fact,
  ad_attribution_export TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V402 同口径）。
REVOKE ALL ON
  ad_attribution_rule, ad_attribution_order, ad_attribution_click, ad_attribution_fact,
  ad_attribution_export FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码：
-- 本模块**不新增权限码**（归因分析属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.3.1.3 商品维度报表同族）⇒ identity 服务零改动，跨车道回归面 = 0。
-- ---------------------------------------------------------------------------
