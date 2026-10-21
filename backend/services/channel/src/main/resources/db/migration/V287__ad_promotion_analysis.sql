-- ===========================================================================
-- 14.4.2.1 促销效果分析（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.4.2.1 促销效果分析
--   - 职责：促销效果分析：促销期间销量/销售额/毛利变化、增量判断、补贴成本、ROI，
--           与活动前的基期对比。
--   - 输入：促销数据；历史基线；利润数据。
--   - 输出：效果报表 + 增量估算 + ROI。
--   - 关系：上游 = 14.4.1；下游 = 13.4 利润、14.2.2.1 预算。
--           **促销期间毛利可能大幅下滑**，评估必须看毛利而不是销售额。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 策略**，零 ALTER 任何既有表：
--   * V284 `ad_promotion`（**同库同服务**，14.4.1.1 已交付）—— 促销定义与窗口
--   * V284 `ad_promotion_product`（同库同服务）—— 促销覆盖的商品
--   * V171 `listing`（同库同服务）—— 商品本体
--   * V203 `listing_performance_snapshot`（**同库同服务**，4.1.x 已交付）——
--     平台口径的**每日**销量与销售额：促销期与基期的**唯一**销量/销售额事实源
--   * V1 `marketplace`（全局码表）—— 商品所属站点的**当地时区**（自然日切分用）
--   * V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）—— 数据范围收敛用
--
-- ---------------------------------------------------------------------------
-- 一、🔴 四条事实边界（写进库，不是写进注释而已）
-- ---------------------------------------------------------------------------
-- 1. **5 定价在 catalog 服务 ⇒ 禁止跨服务 SQL。**
--    成本（`sku_cost_profile` / `landed_cost`）与售价（`pricing_rule` 等，V215 /
--    V223 / V231 / V236）都在 **catalog**，channel 不得 join catalog 的表，
--    不建跨库 FK，不建共享视图，也不写 `catalog.` 限定名。
--    ⇒ 毛利与补贴要用到的**单位成本**与**促销前基准价**，
--      **必须作为快照写进 channel 自己的表**（`ad_promotion_cost_snapshot`，
--      NUMERIC 列），由本模块的写入口登记。
--      这不是"少做一步"：跨服务读一次就多一次口径漂移的来源，
--      而毛利是**促销决策**要用的数字，必须能被复盘。
--
-- 2. **13.4 利润服务未交付**（`reporting` 服务不在本仓）⇒
--    **毛利 / 增量 / ROI 由本模块自己算**，算式写在服务层的显式 SQL 里，
--    分子分母**各自落库**（`roi_numerator` / `roi_denominator` /
--    `increment_gross_profit` …），事后可逐项复核。
--    本模块**不引用任何还不存在的利润端点**。
--
-- 3. **促销期销量/销售额只有一个真实来源**：`listing_performance_snapshot`
--    （平台回报口径，按 listing × 自然日）。本模块**只读同库**这张表，
--    不新建"促销销量"表 —— 那会造出第二份与平台口径打架的事实。
--    ⚠️ 该表的 UNIQUE 是 `(tenant_id, listing_id, stat_date, source)`，
--    **同一天可能有 PLATFORM 与 API 两条来源**。直接 `sum()` 会把同一天的成交
--    算两遍 ⇒ 本模块取**同一天最近收到的那一条**（`received_at desc, id desc`），
--    不相加。口径写在 `metric_definition_note` 里随响应返回。
--
-- 4. 🔴 **"促销期间毛利大幅下滑"是本模块存在的理由** ⇒ 判定**看毛利不看销售额**。
--    所以 `increment_verdict` 的判据是**增量毛利**（`increment_gross_profit`），
--    销售额增量只作为并列指标出现；`margin_alert` 专门标出
--    "销售额涨了但毛利率跌了"这一类**放量不赚**的活动。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 基期（baseline）口径：与促销期**等长**、且**紧邻**促销开始之前
-- ---------------------------------------------------------------------------
-- 取数窗口 = 促销开始之前的**同样天数**（`baseline_from_date = 促销首日 − 促销天数`，
-- `baseline_to_date = 促销首日 − 1`）。等长的两个窗口可以直接相减，
-- 于是「增量 = 当期 − 基期」是**总量口径**而不是日均口径，评审时不必再换算；
-- 同时把日均值也落库（`baseline_daily_*`），方便按天口径复核。
--
-- 🔴 **自然日的切分**：平台自然日由**站点时区**决定（`marketplace.default_timezone`），
--   不是 UTC 日。窗口换算写成 `(instant AT TIME ZONE tz)::date`。
--   ⚠️ 时区名来自 `marketplace`（租户可维护的码表），拼错会让 `AT TIME ZONE` 直接抛错。
--   ⇒ 换算**只认 `pg_timezone_names` 里真实存在的时区**，认不到就退回 UTC
--   （`left join pg_timezone_names`），不让一条脏码表把整次分析打挂。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 补贴成本的口径：让利额 × **实际销量**（不是"最坏情况"）
-- ---------------------------------------------------------------------------
-- 14.4.1.1 的 `BUDGET_CAP` 护栏算的是**最坏情况**让利额（销量未知 ⇒ 取上界），
-- 那是**事前**拦截用的。本模块是**事后**分析，知道实际销量 ⇒ 补贴成本 =
-- 单件让利 × **实际促销期销量**。
--
-- 单件让利与 14.4.1.1 的折后价口径**逐字一致**（照抄它的语义）：
--   PERCENT_OFF      让利单价 = 基准价 × 折扣率
--   FIXED_AMOUNT_OFF 让利单价 = 固定减免额
--   BOGO / BUNDLE / FREE_SHIPPING **没有可折算的价格系数 ⇒ 让利单价不可比（NULL）**
-- ⇒ 补贴成本**不可比时给 NULL，不是 0**（0 会被渲染成"这次没花钱"，那是假事实）。
-- 基准价取自**成本/售价快照**（`ad_promotion_cost_snapshot.base_price`）：
--   PERCENT_OFF      需要基准价（让利 = 基准价 × 折扣率）⇒ 缺基准价 ⇒ 补贴不可比
--   FIXED_AMOUNT_OFF 不需要基准价（让利 = 固定减免额）⇒ 缺基准价也**可算**
-- 缺单位成本 ⇒ **毛利**不可比。
-- 毛利与补贴是**两件事**：有成本没基准价 ⇒ 毛利可算、补贴不可比（百分比促销），
-- 反之亦然 —— 所以两列各自独立可空，谁也不牵连谁。
--
-- ===========================================================================
-- 1. ad_promotion_cost_snapshot · 成本与售价快照（跨服务纪律的物理落点）
-- ===========================================================================
-- 🔴 catalog 的成本与售价**被复制进本表**，channel 永远不 join catalog。
-- 快照带 `captured_at` + `source_ref`：事后能回答"这个成本是**什么时候**、按
-- **哪条口径**登记的"，而不是"现在 catalog 里是多少"（后者早就变了）。
CREATE TABLE ad_promotion_cost_snapshot (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 14.4.1.1 促销（V284，复合 FK 带 tenant_id ⇒ 跨租户挂载不可能）===
  promotion_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, promotion_id)
    REFERENCES ad_promotion(tenant_id, id) ON DELETE CASCADE,

  -- === 上游 4.1.1.1 listing（V171，同库同服务）===
  listing_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, listing_id)
    REFERENCES listing(tenant_id, id) ON DELETE RESTRICT,

  currency char(3) NOT NULL DEFAULT '',

  -- 🔴 单位成本快照（catalog 侧 landed cost / sku cost 口径，**复制进来**）
  unit_cost NUMERIC(19,4) NOT NULL CHECK (unit_cost >= 0),

  -- 🔴 促销前基准价快照（可空 = **没登记** ⇒ 补贴不可比，**不是 0**）
  base_price NUMERIC(19,4) CHECK (base_price IS NULL OR base_price >= 0),

  -- 🔴 来源说明：catalog 的哪个口径 / 哪条记录。事后复盘时不必猜。
  source_ref varchar(200) NOT NULL DEFAULT '',

  -- 🔴 快照时刻：**成本是随时间变的**，不记时刻就不知道这个数字说的是哪天。
  captured_at timestamptz NOT NULL DEFAULT now(),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个促销一个商品一条快照（重登记 = 覆盖，历史在 version / updated_at 里）
  UNIQUE(tenant_id, promotion_id, listing_id)
);

CREATE INDEX ad_promotion_cost_snapshot_promotion
  ON ad_promotion_cost_snapshot(tenant_id, promotion_id);
CREATE INDEX ad_promotion_cost_snapshot_listing
  ON ad_promotion_cost_snapshot(tenant_id, listing_id);

-- ===========================================================================
-- 2. ad_promotion_effect_baseline · 基期快照（基线口径 + 取数窗口）
-- ===========================================================================
-- 「历史基线」是**输入**（蓝图：输入 = 促销数据；历史基线；利润数据），
-- 但它**必须落库**：基期是事后复盘时唯一的对照组 ——
-- 平台快照会被下一次同步覆盖，基期不存下来，"当时我们比的是哪一段"就答不出来。
CREATE TABLE ad_promotion_effect_baseline (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  promotion_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, promotion_id)
    REFERENCES ad_promotion(tenant_id, id) ON DELETE CASCADE,

  listing_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, listing_id)
    REFERENCES listing(tenant_id, id) ON DELETE RESTRICT,

  currency char(3) NOT NULL DEFAULT '',

  -- 🔴 取数窗口（基期口径的物理落点）。timestamptz 存**时刻**，
  --   自然日的换算在服务层的显式 SQL 里按站点时区做。
  window_from_at timestamptz NOT NULL,
  window_to_at timestamptz NOT NULL,
  -- 🔴 窗口天数（与促销期**等长**，见迁移头第二节）
  window_days integer NOT NULL CHECK (window_days > 0),

  -- 🔴 基期事实（平台回报口径，listing_performance_snapshot 汇总）
  baseline_units NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (baseline_units >= 0),
  baseline_sales NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (baseline_sales >= 0),

  -- 🔴 基期成本与毛利（成本来自成本快照；**没登记成本 ⇒ NULL = 不可比，不是 0**）
  --   ⚠️ 毛利**可以是负数**（基期就在亏钱）⇒ 不加 >= 0 约束。
  --   加了这条 CHECK，本模块最该报出来的"促销期毛利下滑"场景反而写不进库。
  baseline_cost NUMERIC(19,4) CHECK (baseline_cost IS NULL OR baseline_cost >= 0),
  baseline_gross_profit NUMERIC(19,4),
  -- 🔴 毛利率（分母为 0 ⇒ NULL = 不可比；生成列 ⇒ 绕服务层也编不出与原始量对不上的比率）
  baseline_gross_margin_rate NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN baseline_sales > 0 AND baseline_gross_profit IS NOT NULL
         THEN round(baseline_gross_profit / baseline_sales, 6) ELSE NULL END
  ) STORED,

  -- 🔴 日均口径（窗口**等长**时与总量等价，留着是为了按天口径复核）
  baseline_daily_units NUMERIC(19,6) NOT NULL DEFAULT 0,
  baseline_daily_sales NUMERIC(19,4) NOT NULL DEFAULT 0,

  -- 🔴 窗口内**真正有平台快照**的天数（覆盖度）。0 天 ⇒ 基期是空的，
  --   与"有数据但销量是 0"是两回事，必须分开。
  baseline_covered_days integer NOT NULL DEFAULT 0 CHECK (baseline_covered_days >= 0),

  -- 🔴 数据截至（本次分析用到的平台快照里最新的一条的接收时刻）
  data_as_of timestamptz NOT NULL,

  computed_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, promotion_id, listing_id),
  -- 🔴 约束**起名**：PG 自动生成的约束名看不懂，报错时要能一眼知道是哪条纪律被破的
  CONSTRAINT ad_promotion_effect_baseline_window_order CHECK (window_to_at > window_from_at)
);

CREATE INDEX ad_promotion_effect_baseline_promotion
  ON ad_promotion_effect_baseline(tenant_id, promotion_id);
CREATE INDEX ad_promotion_effect_baseline_listing
  ON ad_promotion_effect_baseline(tenant_id, listing_id);

-- ===========================================================================
-- 3. ad_promotion_effect · 促销效果结果表（商品维度的事实 + 增量 + ROI）
-- ===========================================================================
-- 粒度 = 促销 × 商品。**活动维度由报表端点用 SQL GROUP BY 真聚合**出来，
-- 不在这里再存一份"活动级汇总行" —— 两份数会漂移，而漂移的那份没人会发现。
--
-- 🔴 **分子分母各自落库**（蓝图要求「计算口径要落库可复核」）：
--   * `increment_gross_profit` = 增量毛利（分子）
--   * `roi_numerator`          = 增量毛利（ROI 分子）
--   * `roi_denominator`        = 补贴成本（ROI 分母）
--   * `roi_value`              = GENERATED 列（分母 ≤ 0 ⇒ NULL = 不可比）
-- 事后任何人拿这两列自己除一遍都能复核，不用相信本模块。
CREATE TABLE ad_promotion_effect (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  promotion_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, promotion_id)
    REFERENCES ad_promotion(tenant_id, id) ON DELETE CASCADE,

  listing_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, listing_id)
    REFERENCES listing(tenant_id, id) ON DELETE RESTRICT,

  currency char(3) NOT NULL DEFAULT '',

  -- 🔴 促销期窗口（与促销定义同源：start_at / end_at 的原始时刻）
  period_from_at timestamptz NOT NULL,
  period_to_at timestamptz NOT NULL,
  period_days integer NOT NULL CHECK (period_days > 0),
  -- 🔴 促销期内**真正有平台快照**的天数（覆盖度）。0 天 ⇒ 不可比。
  period_covered_days integer NOT NULL DEFAULT 0 CHECK (period_covered_days >= 0),

  -- 🔴 促销期事实（平台回报口径）
  promo_units NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (promo_units >= 0),
  promo_sales NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (promo_sales >= 0),

  -- 🔴 促销期成本与毛利（成本来自成本快照；**没登记成本 ⇒ NULL = 不可比，不是 0**）
  --   ⚠️ 毛利**可以是负数**（促销把商品卖亏了）⇒ 不加 >= 0 约束。
  promo_cost NUMERIC(19,4) CHECK (promo_cost IS NULL OR promo_cost >= 0),
  promo_gross_profit NUMERIC(19,4),
  -- 🔴 毛利率（分母为 0 ⇒ NULL = 不可比）
  promo_gross_margin_rate NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN promo_sales > 0 AND promo_gross_profit IS NOT NULL
         THEN round(promo_gross_profit / promo_sales, 6) ELSE NULL END
  ) STORED,

  -- 🔴 基期（引用基期快照表：基线事实只有一份，不复制）
  baseline_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, baseline_id)
    REFERENCES ad_promotion_effect_baseline(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 增量（**总量口径**：当期 − 基期；两窗口等长，可直接相减）
  --   ⚠️ 增量**可以是负数**（促销期比基期差）⇒ 不加 >= 0 约束。
  increment_units NUMERIC(19,6) NOT NULL DEFAULT 0,
  increment_sales NUMERIC(19,4) NOT NULL DEFAULT 0,
  -- 🔴 增量毛利（NULL = 不可比：当期或基期缺成本）
  increment_gross_profit NUMERIC(19,4),

  -- 🔴 单件让利（照抄 14.4.1.1 折后价口径；不可比 ⇒ NULL，**不是 0**）
  subsidy_unit NUMERIC(19,4) CHECK (subsidy_unit IS NULL OR subsidy_unit >= 0),
  -- 🔴 补贴成本 = 单件让利 × 实际促销期销量
  subsidy_cost NUMERIC(19,4) CHECK (subsidy_cost IS NULL OR subsidy_cost >= 0),

  -- 🔴 ROI：分子分母各自落库，比率是生成列（分母 ≤ 0 ⇒ NULL = 不可比）
  --   ⚠️ 分子（增量毛利）**可以是负数** ⇒ 不加 >= 0 约束。
  --      负 ROI 正是"促销把毛利做没了"最直接的读数，挡住它等于把坏消息藏起来。
  roi_numerator NUMERIC(19,4),
  roi_denominator NUMERIC(19,4) CHECK (roi_denominator IS NULL OR roi_denominator >= 0),
  roi_value NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN roi_denominator IS NOT NULL AND roi_denominator > 0
              AND roi_numerator IS NOT NULL
         THEN round(roi_numerator / roi_denominator, 6) ELSE NULL END
  ) STORED,

  -- 🔴 增量判定（**判据是增量毛利，不是增量销售额** —— 蓝图加粗句）
  --   INCREMENTAL      增量毛利 > 0
  --   FLAT             增量毛利 = 0
  --   DECREMENTAL      增量毛利 < 0
  --   INSUFFICIENT_DATA 数据不足以判定（缺成本 / 两期都没覆盖）
  increment_verdict text NOT NULL CHECK (increment_verdict IN (
    'INCREMENTAL','FLAT','DECREMENTAL','INSUFFICIENT_DATA')),

  -- 🔴 **放量不赚**告警：销售额（或销量）上去了但毛利率跌了。
  --   这是"促销期间毛利可能大幅下滑"最常见的形态，必须能一眼看见。
  margin_alert boolean NOT NULL DEFAULT false,

  -- 🔴 数据截至（本次分析用到的平台快照里最新的一条的接收时刻）
  data_as_of timestamptz NOT NULL,

  computed_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, promotion_id, listing_id),
  CONSTRAINT ad_promotion_effect_period_order CHECK (period_to_at > period_from_at)
);

CREATE INDEX ad_promotion_effect_promotion
  ON ad_promotion_effect(tenant_id, promotion_id);
CREATE INDEX ad_promotion_effect_listing
  ON ad_promotion_effect(tenant_id, listing_id);
-- 🔴 报表端点支持「按毛利排序」⇒ 排序键要有索引，否则每次都全表排
CREATE INDEX ad_promotion_effect_gross_profit
  ON ad_promotion_effect(tenant_id, promo_gross_profit DESC NULLS LAST, id);
CREATE INDEX ad_promotion_effect_increment
  ON ad_promotion_effect(tenant_id, increment_gross_profit DESC NULLS LAST, id);
CREATE INDEX ad_promotion_effect_sales
  ON ad_promotion_effect(tenant_id, promo_sales DESC, id);

-- ===========================================================================
-- 4. ad_promotion_effect_export · 效果导出留痕
-- ===========================================================================
-- 形状与 14.3.1.1 `ad_campaign_report_export`（V279）、14.3.1.2
-- `ad_group_keyword_report_export`（V283）**一一对应**：
-- 留痕存的是**筛选条件**（不存报表数据快照 —— 快照会在下一次分析后与
-- "当时的报表"对不上，那反而是假事实）。
CREATE TABLE ad_promotion_effect_export (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 谁导的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,

  -- 🔴 导出时的维度（活动 / 商品）
  dimension text NOT NULL CHECK (dimension IN ('PROMOTION','PRODUCT')),

  -- 筛选条件（可空 = 那一维没筛）
  promotion_id uuid,
  platform_id uuid REFERENCES platform(id),
  shop_code varchar(64),
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,
  currency varchar(8),
  increment_verdict varchar(32),
  date_from date,
  date_to date,

  -- 🔴 导出了多少行（按**聚合后**的行数，不是明细行数）
  row_count integer NOT NULL DEFAULT 0 CHECK (row_count >= 0),

  -- 🔴 导出当时的三个口径定义（一并留痕：日后复盘时不必猜「当时用的是哪份定义」）
  metric_definition_note text NOT NULL DEFAULT '',

  -- 🔴 这次导出是**对着哪个水位**算出来的
  data_as_of timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK (date_from IS NULL OR date_to IS NULL OR date_from <= date_to)
);

CREATE INDEX ad_promotion_effect_export_recent
  ON ad_promotion_effect_export(tenant_id, created_at DESC);
CREATE INDEX ad_promotion_effect_export_actor
  ON ad_promotion_effect_export(tenant_id, requested_by, created_at DESC);

-- ===========================================================================
-- 5. ad_promotion_analysis_run · 分析运行留痕
-- ===========================================================================
-- 一次分析**跑了什么、算了哪些、跳过了哪些、为什么跳过**，都要能复盘。
-- 跳过的促销（没起止时间 / 没有适用商品 / 时区脏数据）**不许静默消失**。
CREATE TABLE ad_promotion_analysis_run (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 谁跑的（已认证成员）
  requested_by uuid NOT NULL,

  -- 🔴 运行状态：SUCCESS 全部算完 / PARTIAL 部分跳过 / FAILED 整体失败
  status text NOT NULL CHECK (status IN ('SUCCESS','PARTIAL','FAILED')),

  -- 🔴 本次分析的目标（NULL = 按筛选条件批量跑）
  promotion_id uuid,
  FOREIGN KEY (tenant_id, promotion_id)
    REFERENCES ad_promotion(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 算出来的促销数 / 跳过的促销数
  analyzed_count integer NOT NULL DEFAULT 0 CHECK (analyzed_count >= 0),
  skipped_count integer NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),

  -- 🔴 跳过的明细（jsonb 数组：{promotionId, promotionCode, reason}）
  skipped jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- 🔴 数据截至（本次分析用到的平台快照里最新的一条的接收时刻）
  data_as_of timestamptz,

  -- 🔴 本次运行的口径定义（一并留痕）
  metric_definition_note text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_promotion_analysis_run_recent
  ON ad_promotion_analysis_run(tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V171 / V203 / V273 / V277 / V278 / V284 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_promotion_cost_snapshot', 'ad_promotion_effect_baseline',
    'ad_promotion_effect', 'ad_promotion_effect_export',
    'ad_promotion_analysis_run' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（照抄 V284 `ad_promotion` / V277 同构）
-- ---------------------------------------------------------------------------
-- 促销挂在店铺下，运营不能绕过店铺直接枚举别家的促销效果。
-- 成本快照 / 基期 / 效果三张表都挂在促销下 ⇒ 跟着促销的店铺收敛。
-- `ad_promotion_effect_export` 的 shop_code 可空（跨店铺导出）⇒
--   要求导出者**至少在一个**店铺上有数据范围（否则他本来也导不出任何行）。
CREATE POLICY data_scope ON ad_promotion_cost_snapshot AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_cost_snapshot.tenant_id AND p.id=ad_promotion_cost_snapshot.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_cost_snapshot.tenant_id AND p.id=ad_promotion_cost_snapshot.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_promotion_effect_baseline AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_effect_baseline.tenant_id AND p.id=ad_promotion_effect_baseline.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_effect_baseline.tenant_id AND p.id=ad_promotion_effect_baseline.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_promotion_effect AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_effect.tenant_id AND p.id=ad_promotion_effect.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_effect.tenant_id AND p.id=ad_promotion_effect.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_promotion_effect_export AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_promotion_effect_export.tenant_id
                 AND (ad_promotion_effect_export.shop_code IS NULL OR s.code=ad_promotion_effect_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_promotion_effect_export.tenant_id
                 AND (ad_promotion_effect_export.shop_code IS NULL OR s.code=ad_promotion_effect_export.shop_code)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_promotion_analysis_run AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_promotion_analysis_run.tenant_id
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_promotion_analysis_run.tenant_id
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V273/V277/V278/V284 的条件建角色，保证迁移可重复执行）
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
  ad_promotion_cost_snapshot, ad_promotion_effect_baseline,
  ad_promotion_effect, ad_promotion_effect_export,
  ad_promotion_analysis_run TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V284 同口径）。
REVOKE ALL ON
  ad_promotion_cost_snapshot, ad_promotion_effect_baseline,
  ad_promotion_effect, ad_promotion_effect_export,
  ad_promotion_analysis_run FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：促销效果分析属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.2.2.1 预算分配、14.4.1.1 促销列表同族，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
