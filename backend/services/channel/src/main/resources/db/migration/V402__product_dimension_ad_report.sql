-- ===========================================================================
-- 14.3.1.3 商品维度报表（channel 服务）
--
-- 蓝图原文：「商品维度广告效果：SKU/SPU/Listing 维度的广告花费、广告销售额、总销售额、
-- ACOS、TACOS、广告占比利润。输入：商品维度；时间范围；利润数据（13）。
-- 输出：商品广告报表 + 广告销售占比 + 广告后净利。
-- 关系：上游 = 14.3.1.1、13.4.1.2；下游 = 13.4 利润。
-- 广告销售占比（广告单/总单）是判断广告是否带来增量还是抢自然单的关键，
-- 只看广告 ROAS 无法回答这个问题。」
--
-- ---------------------------------------------------------------------------
-- 本迁移**只新建一张表**：导出留痕（ad_product_report_export）。
--
-- 🔴 本模块**不建第二套广告事实表**（照抄 14.3.1.1 的纪律）：
--   * 广告花费 / 广告销售额 / 曝光 / 点击 / 订单 ⇒ 直接读 14.1.1.1 的
--     `ad_campaign_daily_metric`（V273，本服务同库）。
--   * 活动 ↔ 投放商品的投放关系 ⇒ 直接读 14.1.1.2 的
--     `ad_campaign_group_product`（V277，本服务同库）。
--   * Listing ↔ 内部 SKU 的映射现状 ⇒ 直接读 4.1.1.1 的
--     `listing` / `listing_variant`（V171，本服务同库）。
--   * 总销售额 / 净利 / 广告费 ⇒ 走**授权只读 API**读 13.4.1.2（reporting 服务），
--     跨服务**零 SQL / 零 FK / 零视图**（AGENTS.md 模块边界纪律）。
--
-- 🔴 本迁移**不 ALTER、不 DROP、不加约束到任何既有表** ⇒ 跨车道 DDL 回归面 = 0。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. ad_product_report_export · 商品维度广告报表的导出留痕
-- ---------------------------------------------------------------------------
-- 形状与 14.3.1.1 的 `ad_campaign_report_export`（V279）**一一对应**，
-- 只多两列本模块特有的东西：
--   * `dimension` 取值域换成 LISTING / SKU / SPU（14.3.1.1 是五种活动维度）。
--   * `attribution_note` —— **导出当时的归因口径原文**（两级等分规则）。
--     少了它，日后复盘「为什么这个商品分到这么多花费」就没有答案了 ——
--     而这正是广告与财务对账最容易吵架的点。
CREATE TABLE ad_product_report_export (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 谁导的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,

  -- 导出时用的商品维度（照抄请求当时的值，不做「按今天重新解释」）。
  --
  -- 🔴 **刻意没有「时间粒度」列**：蓝图 14.3.1.3 的职责句点名的输入是
  --   「商品维度；时间范围；利润数据（13）」—— **没有「按日 / 周 / 月」**。
  --   「按日/周/月」是 14.3.1.1 活动报表的职责（那一张表的行 = 一个活动 × 一个时间桶）。
  --   本模块的行 = **一个商品 × 一个币种**（整个时间窗口的汇总），
  --   留一个永远只会写同一个值的列不如不留。
  dimension text NOT NULL CHECK(dimension IN ('LISTING','SKU','SPU')),

  -- === 上游 2.1.1.1 平台目录（全局码表）===
  platform_id uuid REFERENCES platform(id),

  -- === 上游 2.3.1.1 店铺（UNIQUE(tenant_id,code)，复合 FK ⇒ 跨租户挂载不可能）===
  shop_code varchar(64),
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 筛选条件（可空 = 那一维没筛）
  internal_ad_type varchar(32),
  status varchar(32),
  date_from date,
  date_to date,

  -- 🔴 导出了多少行（按**分组后**的行数，不是明细行数）
  row_count int NOT NULL DEFAULT 0 CHECK(row_count >= 0),

  -- 🔴 这次导出是**对着哪个水位**算出来的（成功同步的水位）。
  --   留痕只写「时间」不写「哪个水位」，事后复盘时仍然要猜。
  data_as_of timestamptz,

  -- 🔴 导出当时的逐指标口径定义（一并留痕：日后复盘时不必猜「当时用的是哪份定义」）
  metric_definition_note text NOT NULL DEFAULT '',

  -- 🔴 导出当时的**归因口径原文**（活动 → Listing → SKU 两级等分规则）
  attribution_note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_product_report_export_recent
  ON ad_product_report_export(tenant_id, created_at DESC);
CREATE INDEX ad_product_report_export_actor
  ON ad_product_report_export(tenant_id, requested_by, created_at DESC);

-- ===========================================================================
-- 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V203 / V273 / V279 同一套写法：
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['ad_product_report_export'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（与 V273 的 ad_campaign / V279 的导出留痕同一口径）
-- ---------------------------------------------------------------------------
-- shop_code 有值 ⇒ 那次导出限定在这个店铺，必须在这个店铺的数据范围内。
-- shop_code 为空 ⇒ 那次导出跨了本租户可见的全部店铺，
--   要求导出者**至少在一个**店铺上有数据范围（否则他本来也导不出任何行）。
CREATE POLICY data_scope ON ad_product_report_export AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_product_report_export.tenant_id
                 AND (ad_product_report_export.shop_code IS NULL OR s.code=ad_product_report_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_product_report_export.tenant_id
                 AND (ad_product_report_export.shop_code IS NULL OR s.code=ad_product_report_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 账号与授权（沿用 V1/V146/V273/V279 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON ad_product_report_export TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V146 / V273 / V279 同口径）。
REVOKE ALL ON ad_product_report_export FROM starcon_app;
