-- ===========================================================================
-- V396 · lifecycle_segmentation —— 13.3.1.2 生命周期与分层
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:149-154）：
--
--   ### 13.3.1.2 生命周期与分层
--   - **职责**：按生命周期与表现分层商品：新品、爆款、旺款、平款、滞销款、衰退款，
--     按 ABC 分类（销量/金额），输出分层结果与建议动作。
--   - **输入**：销量与毛利历史；分层规则配置。
--   - **输出**：分层结果 + 各层建议（备货/清仓/下架/推广）。
--   - **关系**：上游 = 13.3.1.1；下游 = 6.2.1.2 备货、5.4 调价、7.6.1 呆滞。
--     **分层是资源分配的依据**：爆款备货、滞销清仓，没有分层就只能平均用力。
--
-- ---------------------------------------------------------------------------
-- 0. 这三张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_lifecycle_segmentation_rule     —— **分层规则配置**（阈值 / 分类口径 / 版本 / 生效区间）
--   reporting_lifecycle_segmentation_result   —— **分层结果**（每个商品维度一行：档位 + ABC + 建议动作）
--   reporting_lifecycle_segmentation_amount   —— **分层结果的按币种金额行**（销售额 / 毛利额 / ABC 按金额）
--
-- ⚠️ **本文件仍然只是投影**：业务事实仍然在各自的业务域（订单在 trade、成本在 finance、
--   库存在 warehouse）。本服务只**读**它们已经落成的读模型，把结果落成**分层读模型**。
--   任何数字与业务系统冲突，**以业务系统为准**（卷 13 卷首原文）。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 为什么**不**另起一套口径表（口径纪律）
-- ---------------------------------------------------------------------------
--
--   分层的依据是**销量**与**毛利**。这两个口径**已经在** 13.3.1.1 的
--   `reporting_product_performance_metric_definition`（毛利额 = 销售额 − 出库成本）
--   与 13.2.2.2 的 `reporting_product_metric_definition`（销量 / 销售额）里**登记**了。
--
--   ⇒ 本模块**只引用指标码**（规则表里的 quantity_metric_code / amount_metric_code /
--     gross_profit_metric_code），**绝不另起一张口径表重复登记同一批指标** ——
--     重复登记就是第二个真相：租户改了 13.3.1.1 的毛利口径，本模块若有一份自己的登记，
--     两边就会算出两个不同的毛利，分层结果随即失真。
--
--   ⇒ 本模块**零 ALTER 既有表**：V376 / V389 / V394 / V395 都是纯 CREATE TABLE，
--     本模块也只 CREATE。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 为什么金额要单独一张表（币种纪律）
-- ---------------------------------------------------------------------------
--
--   🔴 **绝不跨币种相加**：金额按币种分别统计。分层结果一个商品可能同时有 USD 与 EUR
--   的成交额，**没法塞进一行**（硬塞进去就得选一个币种丢另一个，或把两者相加 —— 两者都是造假）。
--
--   ⇒ 主表只放**与币种无关**的量（销量件数）与**档位 / ABC / 建议动作**；
--     金额（销售额 / 毛利额 / 按金额的 ABC 分档）落在**按币种分行**的子表里，
--     一行 = 一个（商品维度 × 币种）。
--
--   🔴 **ABC 按金额必须按币种分别算**：金额跨币种不可比（1 USD 与 1 EUR 谁大没有答案）。
--     ⇒ 子表的 `abc_by_amount` 是**在同一个币种内部**排名得到的。
--     一个商品有两个币种时，它在两个币种里可能是不同的档 —— 这就是真相，不许抹平。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 「算不出来」与「等于 0」必须分开
-- ---------------------------------------------------------------------------
--
--   * 档位算不出来（历史不足 / 口径未投影 / 窗口内没有事实）⇒
--     `lifecycle_segment` 为 **NULL**，`segment_availability` 说明是哪一种，
--     `segment_note` 写清原因与归属模块。**绝不用 0 或某个默认档位冒充。**
--   * 毛利算不出来（销售额有、出库成本没有）⇒ `gross_profit_amount` 为 **NULL**，
--     `gross_profit_availability` = NO_DATA。**绝不用 0 冒充「毛利为 0」** ——
--     「没投影到成本」与「成本真的是 0」是两件事。
--   * 商品**没有销量事实** ⇒ 不进 ABC 榜（`abc_by_quantity` 为 NULL），
--     「数了它，排名分母会把『没数据』当成『表现差』」（13.3.1.1 同一条纪律）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_lifecycle_segmentation_rule —— 分层规则配置（可配置、可审计）
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：规则是**租户自己**的（13.1.1.2 / 13.2.2.2 已经是这个纪律）。
--   服务层在第一次分层时按标准规则播种一份（播种内容是服务层的常量，**不是数据源**）。
--
-- 🔴 **可审计**：版本号 + 生效区间 + 启停 + 变更人 + 变更说明。
--   改规则**不改历史结果**（结果行上钉死了 rule_id / rule_version），
--   改完规则重跑一次分层才会生效 ⇒ 半年后回看，仍然知道当时是按哪版规则分的层。
--
-- 🔴 **同一租户同一维度层同时只允许一条 enabled 的规则**（部分唯一索引）——
--   两条同时生效 ⇒ 「这个商品算爆款还是旺款」取决于读到哪条，报表就成了薛定谔的。
CREATE TABLE reporting_lifecycle_segmentation_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_code varchar(48) NOT NULL,
  rule_name varchar(120) NOT NULL,
  -- 🔴 **分层维度层**：SKU（内部 SKU）/ SPU（标准产品单元）。
  --   LISTING 层不做生命周期分层 —— 平台商品是**上架形态**，不是商品本身
  --   （同一个商品在多个 Listing 上就是多行，「这个商品活得好不好」问的是商品层）。
  dimension_type varchar(16) NOT NULL CHECK(dimension_type IN ('SKU','SPU')),
  -- 版本号：改一次规则 = 加一个版本（旧的停用，历史结果仍指向旧版本）。
  version int NOT NULL DEFAULT 1 CHECK(version >= 1),
  -- 🔴 **历史窗口长度**（天）：分层需要「历史」，窗口外的事实**不参与计算**。
  window_days int NOT NULL CHECK(window_days BETWEEN 7 AND 730),
  -- ---- 生命周期阈值 ----
  -- 新品：首次销售距判定基准日不超过这么多天。
  new_max_days_since_first_sale int NOT NULL CHECK(new_max_days_since_first_sale > 0),
  -- 🔴 **首次销售的回溯窗口**（天）：判定基准日往前这么多天里找「第一次卖出去的那天」。
  --   为什么要显式配置：新品判定靠的是「第一次卖出去是什么时候」，
  --   回溯窗口太短会把老品误判成新品，太长会把「窗口外卖过一次」的沉睡品算成新品。
  first_sale_lookback_days int NOT NULL CHECK(first_sale_lookback_days > 0),
  -- 衰退：近半窗销量相对前半窗下滑超过这个比例。
  -- 🔴 **比例一律是百分数**（40.0000 = 40%），不是分数 —— 与 13.3.1.1 的 PERCENT 口径同一纪律。
  declining_min_decline_ratio numeric(24,4) NOT NULL
    CHECK(declining_min_decline_ratio >= 0 AND declining_min_decline_ratio <= 100),
  -- 衰退判定所需的最小历史天数：不足则**衰退判定不可算**（不是「没衰退」）。
  min_history_days int NOT NULL CHECK(min_history_days > 0),
  -- ---- 表现阈值（窗口内销量份额）----
  hot_min_sales_share_ratio numeric(24,4) NOT NULL
    CHECK(hot_min_sales_share_ratio >= 0 AND hot_min_sales_share_ratio <= 100),
  strong_min_sales_share_ratio numeric(24,4) NOT NULL
    CHECK(strong_min_sales_share_ratio >= 0 AND strong_min_sales_share_ratio <= 100),
  steady_min_sales_share_ratio numeric(24,4) NOT NULL
    CHECK(steady_min_sales_share_ratio >= 0 AND steady_min_sales_share_ratio <= 100),
  -- 滞销：窗口内日均销量低于这个值（且**有在手库存** —— 没库存的卖不动不叫滞销）。
  slow_max_sales_per_day numeric(24,4) NOT NULL CHECK(slow_max_sales_per_day >= 0),
  -- ---- ABC 阈值（累计贡献比）----
  abc_a_cumulative_ratio numeric(24,4) NOT NULL
    CHECK(abc_a_cumulative_ratio > 0 AND abc_a_cumulative_ratio <= 100),
  abc_b_cumulative_ratio numeric(24,4) NOT NULL
    CHECK(abc_b_cumulative_ratio > 0 AND abc_b_cumulative_ratio <= 100),
  -- ---- 🔴 口径引用（**引用，不重复登记**）----
  -- 销量口径码（引 13.2.2.2 的 PRODUCT_SALES_QUANTITY）。
  quantity_metric_code varchar(48) NOT NULL,
  -- 销售额口径码（引 13.2.2.2 的 PRODUCT_SALES_AMOUNT）。
  amount_metric_code varchar(48) NOT NULL,
  -- 毛利口径码（引 13.3.1.1 的 PRODUCT_GROSS_PROFIT_AMOUNT —— 派生口径，
  --   分子分母由**那张**口径表说了算，本模块只按它求值）。
  gross_profit_metric_code varchar(48) NOT NULL,
  -- ---- 审计 ----
  effective_from date NOT NULL,
  effective_to date,
  enabled boolean NOT NULL DEFAULT true,
  created_by varchar(80) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个规则码的同一个版本只有一行。
  UNIQUE(tenant_id, rule_code, version),
  -- 🔴 **档位阈值必须单调**：爆款门槛 ≥ 旺款门槛 ≥ 平款门槛，
  --   否则同一个份额会同时命中两档，档位取决于判别顺序而不是数值。
  CHECK(hot_min_sales_share_ratio >= strong_min_sales_share_ratio),
  CHECK(strong_min_sales_share_ratio >= steady_min_sales_share_ratio),
  -- 🔴 **ABC 阈值必须单调**：A 的累计比必须小于 B。
  CHECK(abc_a_cumulative_ratio < abc_b_cumulative_ratio),
  CHECK(effective_to IS NULL OR effective_to >= effective_from)
);

-- 同一租户同一维度层**同时只允许一条生效规则**（可审计的历史版本不受影响）。
CREATE UNIQUE INDEX reporting_lifecycle_segmentation_rule_active
  ON reporting_lifecycle_segmentation_rule(tenant_id, dimension_type)
  WHERE enabled;

-- 按规则码取历史版本（审计用）。
CREATE INDEX reporting_lifecycle_segmentation_rule_by_code
  ON reporting_lifecycle_segmentation_rule(tenant_id, rule_code, version desc);

-- ---------------------------------------------------------------------------
-- 2. reporting_lifecycle_segmentation_result —— 分层结果（每个商品维度一行）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个（商品维度 × 判定窗口 × 规则版本）的分层结论。
--
-- ⚠️ **规则版本钉在行上**：结果必须能回答「这是按哪版规则分的层」。
--
-- ⚠️ **金额不在这里**：金额按币种分行，在 `reporting_lifecycle_segmentation_amount`。
CREATE TABLE reporting_lifecycle_segmentation_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 🔴 规则版本（可审计）：改规则不改历史结果。
  rule_id uuid NOT NULL,
  rule_code varchar(48) NOT NULL,
  rule_version int NOT NULL CHECK(rule_version >= 1),
  dimension_type varchar(16) NOT NULL CHECK(dimension_type IN ('SKU','SPU')),
  dimension_id uuid NOT NULL,
  dimension_key varchar(160) NOT NULL CHECK(btrim(dimension_key) <> ''),
  dimension_name varchar(200) NOT NULL DEFAULT '',
  -- 判定基准日（= 窗口末日）与窗口区间。**窗口外的事实不参与计算。**
  bucket_date date NOT NULL,
  window_from date NOT NULL,
  window_to date NOT NULL,
  -- 窗口内销量（**件数**，与币种无关 ⇒ 可以跨币种合计）。
  sales_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(sales_quantity >= 0),
  -- ---- 生命周期判定的依据 ----
  -- 首次销售日（**从历史里查出来的**）。查不到 ⇒ null（历史不足，不猜）。
  first_sale_date date,
  -- 窗口内有销售事实的天数。
  history_days int NOT NULL DEFAULT 0 CHECK(history_days >= 0),
  -- 近半窗 / 前半窗销量（衰退判定用）。
  recent_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(recent_quantity >= 0),
  prior_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(prior_quantity >= 0),
  -- 下滑比例。**null = 算不出来**（历史不足 / 前半窗为 0），不是 0。
  -- 🔴 **百分数**（75.0000 = 75%），服务端已 ×100。
  decline_ratio numeric(24,4)
    CHECK(decline_ratio IS NULL OR (decline_ratio >= 0 AND decline_ratio <= 100)),
  -- 窗口内销量份额。**null = 算不出来**（窗口内没有销量）。
  -- 🔴 **百分数**（88.2400 = 88.24%），服务端已 ×100。
  sales_share numeric(24,4)
    CHECK(sales_share IS NULL OR (sales_share >= 0 AND sales_share <= 100)),
  -- ---- 档位 ----
  -- 🔴 六档：新品 / 爆款 / 旺款 / 平款 / 滞销款 / 衰退款。**null = 算不出来**。
  lifecycle_segment varchar(16) CHECK(
    lifecycle_segment IS NULL
    OR lifecycle_segment IN ('NEW','HOT','STRONG','STEADY','SLOW','DECLINING')),
  -- 🔴 「算不出来」的**种类**：AVAILABLE / NO_DATA（窗口内没有事实）/
  --   NOT_COMPARABLE（历史不足，比不了）/ NOT_DELIVERED（口径未投影，归谁管）。
  segment_availability varchar(24) NOT NULL CHECK(
    segment_availability IN ('AVAILABLE','NO_DATA','NOT_COMPARABLE','NOT_DELIVERED')),
  -- 档位不可算时的原因与归属模块（页面上原样显示）。
  segment_note varchar(500) NOT NULL DEFAULT '',
  -- ---- ABC（按销量）----
  abc_by_quantity char(1) CHECK(abc_by_quantity IS NULL OR abc_by_quantity IN ('A','B','C')),
  abc_rank_by_quantity int CHECK(abc_rank_by_quantity IS NULL OR abc_rank_by_quantity >= 1),
  abc_total_by_quantity int CHECK(abc_total_by_quantity IS NULL OR abc_total_by_quantity >= 1),
  -- ---- 建议动作（备货 / 清仓 / 下架 / 推广）----
  recommended_action varchar(16) CHECK(
    recommended_action IS NULL OR recommended_action IN ('RESTOCK','CLEARANCE','DELIST','PROMOTE')),
  -- 建议动作的依据（页面上照着显示，让用户知道为什么给这个建议）。
  action_note varchar(300) NOT NULL DEFAULT '',
  -- ---- 库存与库龄（时点快照，来自 13.3.1.1）----
  on_hand_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(on_hand_quantity >= 0),
  aging_days numeric(24,4) CHECK(aging_days IS NULL OR aging_days >= 0),
  -- 🔴 **上游快照的观测时刻**：这一行分层结果依据的库存 / 库龄**时点快照**是什么时候观测到的。
  --   🔴 **绑定纪律**：这一列用 {@code ?::timestamptz} + {@code OffsetDateTime} 绑定 ——
  --     用 {@code Timestamp.valueOf(LocalDateTime)} 会**静默偏 −28800s**（JVM 本地时区当成 UTC）。
  source_observed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(window_to >= window_from),
  -- 🔴 **算出来了 ⇒ 状态必须是 AVAILABLE**。
  CHECK(lifecycle_segment IS NULL OR segment_availability = 'AVAILABLE'),
  -- 🔴 **算出来了 ⇒ 必须给建议动作**（分层是资源分配的依据，没动作的分层没有意义）。
  CHECK(lifecycle_segment IS NULL OR recommended_action IS NOT NULL),
  -- 🔴 **算不出来 ⇒ 必须说清是哪一种 + 为什么**（不许静默省略）。
  CHECK(
    lifecycle_segment IS NOT NULL
    OR (segment_availability <> 'AVAILABLE' AND btrim(segment_note) <> '')),
  -- 🔴 **给了建议动作 ⇒ 必须写依据**。
  CHECK(recommended_action IS NULL OR btrim(action_note) <> ''),
  -- 🔴 **进了 ABC 榜 ⇒ 必须有排名与榜内样本数**。
  CHECK(
    abc_by_quantity IS NULL
    OR (abc_rank_by_quantity IS NOT NULL AND abc_total_by_quantity IS NOT NULL)),
  -- 🔴 **没销量 ⇒ 不进 ABC 榜**（把「没数据」算成「表现差」会歪掉分母）。
  CHECK(abc_by_quantity IS NULL OR sales_quantity > 0)
);

-- 按窗口取结果（读模型的主查询路径）。
CREATE INDEX reporting_lifecycle_segmentation_result_by_window
  ON reporting_lifecycle_segmentation_result(
    tenant_id, dimension_type, window_from, window_to, bucket_date desc);

-- 按商品维度取结果（单商品钻取用）。
CREATE INDEX reporting_lifecycle_segmentation_result_by_dimension
  ON reporting_lifecycle_segmentation_result(tenant_id, dimension_type, dimension_id);

-- 按档位筛（页面上的档位筛选器）。
CREATE INDEX reporting_lifecycle_segmentation_result_by_segment
  ON reporting_lifecycle_segmentation_result(tenant_id, dimension_type, lifecycle_segment);

-- ---------------------------------------------------------------------------
-- 3. reporting_lifecycle_segmentation_amount —— 分层结果的按币种金额行
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个（商品维度 × 币种 × 判定窗口）。
--
-- 🔴 **金额行必须带币种**：金额行不带币种 = 金额没有归属 = 迟早被加到一起。
--   （件数类与币种无关的量走主表的 `sales_quantity`，那里没有币种列。）
--
-- 🔴 **ABC 按金额在同一个币种内部排名**：金额跨币种不可比。
CREATE TABLE reporting_lifecycle_segmentation_amount (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 归属的分层结果行（同租户）。
  result_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  dimension_type varchar(16) NOT NULL CHECK(dimension_type IN ('SKU','SPU')),
  dimension_id uuid NOT NULL,
  -- 判定窗口（与主表一致，落在这里是为了汇总查询不必每次 join）。
  bucket_date date NOT NULL,
  window_from date NOT NULL,
  window_to date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 窗口内销售额（同币种）。
  sales_amount numeric(24,4) NOT NULL DEFAULT 0 CHECK(sales_amount >= 0),
  -- 🔴 毛利额。**null = 算不出来**（出库成本没投影），不是 0。
  gross_profit_amount numeric(24,4),
  -- 毛利额的可得性：AVAILABLE / NO_DATA（没有出库成本事实）/
  --   NOT_COMPARABLE（分子分母不齐）/ NOT_DELIVERED（口径未投影，归谁管）。
  gross_profit_availability varchar(24) NOT NULL CHECK(
    gross_profit_availability IN ('AVAILABLE','NO_DATA','NOT_COMPARABLE','NOT_DELIVERED')),
  gross_profit_note varchar(300) NOT NULL DEFAULT '',
  -- 🔴 **ABC 按金额**（在同一个币种内部排名）。
  abc_by_amount char(1) CHECK(abc_by_amount IS NULL OR abc_by_amount IN ('A','B','C')),
  abc_rank_by_amount int CHECK(abc_rank_by_amount IS NULL OR abc_rank_by_amount >= 1),
  abc_total_by_amount int CHECK(abc_total_by_amount IS NULL OR abc_total_by_amount >= 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个结果的同一个币种只有一行。
  UNIQUE(tenant_id, result_id, currency),
  CHECK(window_to >= window_from),
  -- 🔴 **毛利额有值 ⇒ 可得性必须是 AVAILABLE**。
  CHECK(
    gross_profit_amount IS NULL
    OR (gross_profit_availability = 'AVAILABLE' AND gross_profit_amount IS NOT NULL)),
  -- 🔴 **毛利额不可算 ⇒ 必须说清原因**。
  CHECK(
    gross_profit_amount IS NOT NULL
    OR (gross_profit_availability <> 'AVAILABLE' AND btrim(gross_profit_note) <> '')),
  -- 🔴 **进了金额榜 ⇒ 必须有排名与榜内样本数，且金额必须为正**。
  CHECK(
    abc_by_amount IS NULL
    OR (sales_amount > 0 AND abc_rank_by_amount IS NOT NULL AND abc_total_by_amount IS NOT NULL))
);

-- 按结果行取金额（列表页一次取回每个商品的金额）。
CREATE INDEX reporting_lifecycle_segmentation_amount_by_result
  ON reporting_lifecycle_segmentation_amount(tenant_id, result_id);

-- 按窗口 + 币种取金额（分档汇总用）。
CREATE INDEX reporting_lifecycle_segmentation_amount_by_window
  ON reporting_lifecycle_segmentation_amount(tenant_id, dimension_type, window_from, window_to, currency);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 金额行的 result_id 必须真的存在（同租户）⇒ 没有孤儿金额行。
--
-- 🔴 **为什么需要**：分层结果是按窗口**先删后写**的。删了结果却留下金额行，
--   汇总就会把上一轮的数字混进来 —— 而汇总页面上看不出任何异常。
CREATE OR REPLACE FUNCTION guard_reporting_lifecycle_segmentation_amount_parent() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_lifecycle_segmentation_result
     WHERE tenant_id = NEW.tenant_id AND id = NEW.result_id) THEN
    RAISE EXCEPTION '分层结果 % 不存在，不能落金额行', NEW.result_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_lifecycle_segmentation_amount_parent
  BEFORE INSERT OR UPDATE ON reporting_lifecycle_segmentation_amount
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_lifecycle_segmentation_amount_parent();

-- (b) 规则引用的口径码**必须真的登记在别人的口径表里**。
--
-- 🔴 **为什么在这里钉**：分层依据必须是**已登记的口径**，
--   否则规则里一个拼错的指标码会让分层结果永远「算不出来」，而没人知道是拼错了。
--
-- ⚠️ **口径表是按租户播种的**（第一次投影时播种）⇒ 播种规则**之前**，
--   13.3.1.1 / 13.2.2.2 的口径表对某个租户可能还是空的。
--   ⇒ 本触发器只在**口径表里已经登记过这个码**时才放行未知码？
--   不行 —— 那等于没有约束。
--
--   ⇒ 折中（与兄弟模块同一纪律）：**规则表的写入由服务层把关**（服务层播种规则时
--   先读口径表，登记不到就**拒绝播种并报错**），
--   而**已经存在的规则**若引用了后来被删掉的口径，分层时按「口径未投影」处理
--   （显式说明原因与归属模块，**不用 0 冒充**）。
--   ⇒ 本触发器只钉一条**不依赖其它表**的不变量：口径码不能为空串。
CREATE OR REPLACE FUNCTION guard_reporting_lifecycle_segmentation_rule_codes() RETURNS trigger AS $$
BEGIN
  IF btrim(NEW.quantity_metric_code) = ''
     OR btrim(NEW.amount_metric_code) = ''
     OR btrim(NEW.gross_profit_metric_code) = '' THEN
    RAISE EXCEPTION '分层规则必须引用非空的口径码（销量 / 销售额 / 毛利）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_lifecycle_segmentation_rule_codes
  BEFORE INSERT OR UPDATE ON reporting_lifecycle_segmentation_rule
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_lifecycle_segmentation_rule_codes();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_lifecycle_segmentation_rule',
    'reporting_lifecycle_segmentation_result',
    'reporting_lifecycle_segmentation_amount'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_lifecycle_segmentation_rule,
  reporting_lifecycle_segmentation_result,
  reporting_lifecycle_segmentation_amount TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_lifecycle_segmentation_rule,
  reporting_lifecycle_segmentation_result,
  reporting_lifecycle_segmentation_amount TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **6.2.1.2 备货**：本模块的 `recommended_action = 'RESTOCK'` + 档位 + 窗口销量
--     就是备货建议的输入（爆款 / 旺款 ⇒ 备货）。
--   * **5.4 调价**：本模块的档位（平款 / 衰退款）与毛利（按币种）可以驱动调价判定。
--   * **7.6.1 呆滞**：本模块的 `SLOW`（滞销款）+ 在手数量 + 库龄是呆滞清单的输入；
--     **本模块不消费 7.6.1 的呆滞清单**（它还没交付）—— 滞销判定用的是
--     13.3.1.1 的库存 / 库龄**时点快照**，不依赖 7.6.1。
--   * **13.3.1.1 产品表现分析**：本模块的**上游** —— 销量与毛利历史来自它的日事实，
--     本模块**只引用**它的口径，**不重复登记**。
