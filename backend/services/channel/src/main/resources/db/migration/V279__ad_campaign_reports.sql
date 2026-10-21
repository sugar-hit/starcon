-- ===========================================================================
-- 14.3.1.1 活动报表（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.3.1.1 活动报表
--   - 职责：活动维度效果报表：花费、曝光、点击、CTR、CPC、订单、转化率、销售额、ACOS/ROAS/TACOS，
--           按日/周/月与多维对比，支持导出。
--   - 输入：时间范围；维度；广告数据。
--   - 输出：报表表 + 趋势图 + 口径说明。
--   - 关系：上游 = 14.1.1.1；下游 = 14.3.2.1。
--           **ACOS/ROAS/TACOS 三个口径要在表头写明定义**，否则跨部门沟通会各说各话。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 一张表 / 索引 / 策略**，零 ALTER 任何既有表：
--   * V273 `ad_campaign` + `ad_campaign_daily_metric` —— **已交付的上游 14.1.1.1**，
--     报表的事实表就在那儿。本模块**不复制**任何一列指标，也不另立一张「报表事实表」。
--   * V1 `platform`（全局平台码表）+ V1 `shop`（复合 FK 带 tenant_id）—— 只读引用。
--
-- 🔴 上游 14.1.2.2 广告类型差异（V146 `ad_type_profile`）只**间接**参与：
--   归一后的内部类型 `ad_campaign.internal_ad_type` 由 14.1.1.1 的触发器从档案复制，
--   本模块直接读那一列，不再建第二套归一口径。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这张表为什么存在：导出是**留痕**的事实，不是「点一下下载」
-- ---------------------------------------------------------------------------
-- 蓝图的输出写着「报表表 + 趋势图 + 口径说明」，职责句末尾写着「支持导出」。
-- 广告花费与销售额是**直接进财务与归因**的钱，导出一次就是一份可以外传的账。
-- 所以本模块给导出留一条审计：
--
--   ad_campaign_report_export  报表导出留痕（谁、什么时候、什么筛选条件、导出了多少行）
--
-- 🔴 留痕里存的是**筛选条件**（粒度 / 维度 / 平台 / 店铺 / 类型 / 状态 / 起止日），
--   **不存报表数据本身** —— 报表随时可以按同样的条件重算出来，
--   而数据快照会在下一次同步后与「当时的报表」对不上，留一份反而是假事实。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 为什么聚合口径必须是「先汇总再相除」
-- ---------------------------------------------------------------------------
-- `ad_campaign_daily_metric` 上有 `ctr` / `acos` / `roas` 三个 **GENERATED 列**
-- （V273），它们是**单行**的口径：那一行的消耗 / 那一行的销售额。
-- 报表是**多行聚合**的口径，正确算法是 Σ分子 / Σ分母：
--
--   ACOS  = Σ消耗 / Σ销售额      （**不是** avg(每行 ACOS)）
--   ROAS  = Σ销售额 / Σ消耗      （**不是** avg(每行 ROAS)）
--   CTR   = Σ点击 / Σ曝光
--   CPC   = Σ消耗 / Σ点击
--   CVR   = Σ订单 / Σ点击
--
-- 🔴 对生成列求平均是**数学错误**：把两条 ACOS 100% 的活动平均成 50%，
--   会让运营以为「整体在盈亏线上」—— 它其实在亏。
-- 这条纪律由服务层的聚合 SQL 保证（本迁移只建表，不建视图；
-- 建视图会让「先汇总再相除」这件事变成第二套要维护的口径）。
--
-- 🔴 分母为 0 一律给 **NULL（不可比）**，不是 0：
--   「可比但真的是零」与「压根算不出来」在界面上必须分开。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 币种：不许把不同币种加在一起
-- ---------------------------------------------------------------------------
-- `ad_campaign.currency` 是**每条活动自己的**币种（V273）。上游 14.1.1.1 的台账已写明：
-- 「跨平台单位统一不在本模块（属 14.3.1.1 报表口径）」—— **本模块就是那个口径的落点**。
--
-- 本仓**没有汇率源**（无汇率表、无汇率同步器），因此本模块**不做**跨币种换算，
-- 改为：**报表的分组键里始终带币种，汇总也按币种拆开**。
-- 于是「Amazon 的 USD 消耗」与「Amazon 的 EUR 消耗」永远是两行，不会被加成一个假总数。
-- 跨币种比较能力在 capabilities 里显式 available=false（责任模块 = 汇率源，未交付）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 TACOS：现在**没有分母**，不许造一个
-- ---------------------------------------------------------------------------
-- TACOS = 广告花费 / **总销售额**。总销售额来自 13.4 利润模块，
-- 而卷 13 的主责服务 reporting **不在集成头**（plan 的 serviceBlocked 已点名），
-- 13.4.1.x 的利润表因此在本服务里**根本不存在**。
--
-- 🔴 也就是说：TACOS 现在**没有分母**。
--   * 不许用 0 当分母（那会得到无穷大）；
--   * 不许用**广告销售额**顶替总销售额（那算出来的是 ACOS，不是 TACOS）；
--   * 本迁移**不建**任何「总销售额影子表」来凑分母 —— 那会把一个假口径固化成事实。
--
-- 正确做法：TACOS 在能力声明里显式 `available=false`，
-- ownerModule 指向 13.4，impact 写清「总销售额口径未接入，当前不出 TACOS 数值」，
-- 界面上照实显示这个状态。ACOS / ROAS **有**分母，正常出数。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 报表查询的性能纪律（蓝图原话）
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「**关键词级数据量极大**，查询必须分页与缓存，不能实时全表扫。」
-- 本模块是**活动维度**，量级比关键词级小，但日/周/月 × 多维对比一样容易变成全表扫。
-- 因此本模块的纪律是：
--   * 时间范围**必填**（服务层不给默认区间），且**有上限**（按粒度算，最多 400 个桶）；
--   * 所有聚合查询走 jOOQ 显式 SQL，谓词顺序对齐上游索引
--     `ad_campaign_daily_metric_window (tenant_id, metric_date, campaign_id)`：
--     **先 tenant_id、再 metric_date 范围**；
--   * **真分页**（LIMIT/OFFSET）+ **汇总单独一条聚合查询**（统计的是筛选后的全集，不是当前页）。
--
-- 本迁移**不改**上游那张明细表的索引：它的窗口索引已经覆盖本模块的访问路径。
--
-- ---------------------------------------------------------------------------
-- 六、多租户隔离与数据范围
-- ---------------------------------------------------------------------------
-- tenant_id NOT NULL + ENABLE/FORCE RLS isolation（照抄 V273 的写法），
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 另加 data_scope（RESTRICTIVE）策略：报表与广告活动一样挂在**店铺**下，
-- 可见性随店铺 owner / department 收敛（与 V273 的 ad_campaign 同一口径）。
-- 导出留痕的 shop_code 为空时表示「这次导出跨了本租户可见的全部店铺」，
-- 此时要求导出者在**至少一个**店铺上有数据范围 —— 否则连一条都导不出来。
--
-- ===========================================================================

CREATE TABLE ad_campaign_report_export (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 谁导的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,

  -- 导出时用的粒度与分组维度（照抄请求当时的值，不做「按今天重新解释」）
  granularity text NOT NULL CHECK (granularity IN ('DAY','WEEK','MONTH')),
  dimension text NOT NULL CHECK (dimension IN ('CAMPAIGN','PLATFORM','SHOP','AD_TYPE','CURRENCY')),

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
  row_count int NOT NULL DEFAULT 0 CHECK (row_count >= 0),

  -- 🔴 这次导出是**对着哪个水位**算出来的（成功同步的水位）。
  --   留痕只写「时间」不写「哪个水位」，事后复盘时仍然要猜。
  data_as_of timestamptz,

  -- 🔴 导出当时的三个口径定义（一并留痕：日后复盘时不必猜「当时用的是哪份定义」）
  metric_definition_note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_campaign_report_export_recent
  ON ad_campaign_report_export(tenant_id, created_at DESC);
CREATE INDEX ad_campaign_report_export_actor
  ON ad_campaign_report_export(tenant_id, requested_by, created_at DESC);

-- ===========================================================================
-- 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V203 / V273 同一套写法。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['ad_campaign_report_export'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（与 V273 的 ad_campaign 同一口径）
-- ---------------------------------------------------------------------------
-- shop_code 有值 ⇒ 那次导出限定在这个店铺，必须在这个店铺的数据范围内。
-- shop_code 为空 ⇒ 那次导出跨了本租户可见的全部店铺，
--   要求导出者**至少在一个**店铺上有数据范围（否则他本来也导不出任何行）。
CREATE POLICY data_scope ON ad_campaign_report_export AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_campaign_report_export.tenant_id
                 AND (ad_campaign_report_export.shop_code IS NULL OR s.code=ad_campaign_report_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_campaign_report_export.tenant_id
                 AND (ad_campaign_report_export.shop_code IS NULL OR s.code=ad_campaign_report_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 账号与授权（沿用 V1/V146/V273 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON ad_campaign_report_export TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V146 / V273 同口径）。
REVOKE ALL ON ad_campaign_report_export FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：广告报表是「平台操作」语义下的读能力，
-- 与 14.1.1.1 广告活动列表、14.1.2.2 广告类型差异同族，因此 identity 服务零改动。
