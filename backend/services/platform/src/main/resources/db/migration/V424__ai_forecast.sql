-- ===========================================================================
-- V424 · ai_forecast —— 18.4.2.2「选品与需求预测」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.4.2.2 原文：
--
--   「**AI 预测能力**：销量预测（含促销与大促影响）、补货量建议、价格弹性与调价建议、
--     爆品识别与机会品发现。
--     输入：历史销量、库存、在途、价格、促销日历；预测参数。
--     输出：预测结果 + 置信区间 + 关键影响因素说明。
--     关系：上游 = 13 分析、6 采购、7 库存；下游 = 6.2.1.2 补货建议、5.4 调价。
--     **预测必须给置信区间和影响因素**，单点数字会让用户过度信任；
--     建议只作为参考输入，不自动执行。」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不写下游的表**。6.2.1.2「补货建议」与 5.4「调价」是别的模块的表：
--     本模块只产出**建议**，落自己的建议台账（platform_forecast_suggestion），
--     状态机显式区分「建议已生成 / 已采纳 / 已驳回」。
--     🔴 **没有 EXECUTED 状态**：本模块**没有**「执行」这个动作 ——
--     补货单归 6 采购，调价归 5 定价，跨服务写它们的库是 AGENTS.md 明令禁止的。
--     建议被采纳后由下游自己去取（取的是「已采纳」的那一份）。
--
--   * **不假装有 AI 模型**。本仓没有任何外部 AI / LLM 服务（见 18.4.2.1 V380 的同一判断）。
--     ⇒ 预测用**确定性、可解释**的算法（移动平均 / 指数平滑 + 促销因子），
--     算法口径、参数、来源表逐条写进 /platform/ai/forecast/capabilities。
--     「可解释」不是修辞：区间怎么来的、因素怎么来的，页面上都能逐条展开。
--
--   * **不拥有订单事实**。历史销量在本模块里是**预测输入台账**
--     （platform_forecast_demand_history），不是订单副本：
--     * `source='MANUAL'`  —— 运营手工录入的逐日销量（**租户自己的业务事实**）；
--     * `source='REPORTING'` —— 从 **13 分析**（reporting 服务）同步过来的销量事实。
--     每行都带 source + source_ref ⇒ **来源可查**。
--     订单事实仍然住在 trade 的库里，本模块只读不抄。
--
--   * **促销日历是租户自己的业务事实**（platform_forecast_promotion），
--     `source` 标明录入方式（MANUAL / IMPORT），**不假装是从上游同步来的**。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_forecast_promotion      —— 促销 / 大促日历（租户级 + SKU 范围）
--   platform_forecast_demand_history —— 需求历史台账（预测输入；逐日销量）
--   platform_forecast_run            —— 预测运行（算法 / 参数 / 统计窗口 / 状态）
--   platform_forecast_result         —— 一个 SKU 在一次运行里的预测结果（点估计 + 区间）
--   platform_forecast_factor         —— 关键影响因素（因素名 + 方向 + 取值 + 口径说明）
--   platform_forecast_suggestion     —— 建议台账（补货 / 调价；**只建议，不执行**）
--
-- 六张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 置信区间为什么能算出来（口径写死在这里，避免服务层各写各的）
-- ---------------------------------------------------------------------------
--
--   样本 = 统计窗口内的**整条日历序列**：有记录的日子取登记的销量，
--   **没有记录的日子按 0 销量计**（台账登记的是逐日销量；没登记 = 当天没有销量）。
--   ⇒ 基线 = 整条序列的移动平均 / 指数平滑水平；
--   ⇒ 残差 σ = 一步预测误差的样本标准差（分母 n-1）；
--   ⇒ 预测期总量点估计 = 基线 × 促销因子 × 预测天数；
--   ⇒ 区间 = 点估计 ± z × σ × √预测天数（独立同分布的日误差累加）。
--
--   🔴 **样本天数不足就不给区间，也不给点估计**：该 SKU 记为「跳过」并写明原因。
--     一个「拍脑袋的窄区间」比没有区间更危险。
--
--   🔴 **区间下界不小于 0**：需求不能是负数。
--
-- ---------------------------------------------------------------------------
-- 3. 时间与数量
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 **timestamptz**；服务层用 **OffsetDateTime** 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--   * 日历日一律 **date**（business_date / forecast 日期），按 UTC 日历切分。
--   * 数量与金额一律 **numeric(19,6)**（BigDecimal），比率一律 **numeric(9,6)**，
--     置信水平 **numeric(5,4)**。**本文件没有一个 double / real / float。**
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_forecast_promotion —— 促销 / 大促日历
-- ---------------------------------------------------------------------------
--
-- 🔴 **expected_lift（预期销量提升率）是必填的**：没有它，预测就**无法**计入促销影响 ——
--   与其用一个藏在代码里的默认系数（谁都不知道它从哪来），不如要求录入时就说清楚。
--
-- 🔴 **discount_rate（折扣率）与 expected_lift 是两件事**：折扣率是「让了多少」，
--   提升率是「销量涨了多少」。两者没有固定的换算关系（品类差异极大），
--   所以本表**两个都记**，预测只用提升率，页面两个都显示。
--
CREATE TABLE platform_forecast_promotion (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 促销名（如「黑五」「会员日」）。
  name varchar(120) NOT NULL,

  -- 促销类型。
  promotion_type text NOT NULL DEFAULT 'PROMO'
    CHECK(promotion_type IN ('PROMO','BIG_SALE','FLASH','SEASONAL','CLEARANCE')),

  -- 适用范围：
  --   ALL —— 租户级（对全部 SKU 生效）
  --   SKU —— 指定 SKU（scope_value = SKU 码）
  scope_type text NOT NULL DEFAULT 'ALL' CHECK(scope_type IN ('ALL','SKU')),
  -- SKU 码；scope_type='ALL' 时必须为空串。
  scope_value varchar(64) NOT NULL DEFAULT '',

  -- 生效区间（timestamptz）。与预测期有交集才计入预测。
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,

  -- 折扣率：0.2 = 降价 20%（1 折写 0.9）。可空（没有标价的促销）。
  discount_rate numeric(9,6),
  -- 预期销量提升率：0.3 = 销量 +30%。**必填**（没有它预测无法计入促销影响）。
  expected_lift numeric(9,6) NOT NULL,

  -- 🔴 **数据来源与录入方式**：MANUAL（运营在页面上录入）/ IMPORT（批量导入）。
  --   **不假装是从上游同步来的** —— 促销计划是租户自己的业务事实。
  source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('MANUAL','IMPORT')),
  source_ref varchar(200) NOT NULL DEFAULT '',

  note varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(btrim(name) <> '' AND length(name) <= 120),
  CHECK(ends_at > starts_at),
  CHECK(discount_rate IS NULL OR (discount_rate > 0 AND discount_rate <= 1)),
  -- 提升率不能低于 -100%（销量不可能归零以下）。
  CHECK(expected_lift > -1),
  CHECK(length(scope_value) <= 64),
  -- 指定 SKU 时必须给出 SKU 码；租户级时不得带 SKU 码。
  CHECK((scope_type = 'ALL' AND scope_value = '') OR (scope_type = 'SKU' AND btrim(scope_value) <> '')),
  CHECK(length(source_ref) <= 200)
);

CREATE INDEX platform_forecast_promotion_by_window
  ON platform_forecast_promotion(tenant_id, starts_at, ends_at);

CREATE INDEX platform_forecast_promotion_by_scope
  ON platform_forecast_promotion(tenant_id, scope_type, scope_value);

-- ---------------------------------------------------------------------------
-- 2. platform_forecast_demand_history —— 需求历史台账（预测输入）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个 SKU 在一天里的销量。
--
-- 🔴 **唯一键是 (tenant_id, sku_code, business_date)**：同一个 SKU 同一天**只能有一条**。
--   理由：两套口径（人工 + 上游同步）落在同一天会**重复计数**，
--   而重复计数会直接把基线抬高 —— 预测偏高 ⇒ 补货偏多。
--   ⇒ 同步与录入撞键时**明确报冲突**，不静默取平均、也不静默覆盖。
--
-- 🔴 **unit_price / revenue 可空**：13 分析只给销量时，单价**算不出来**
--   （用 0 冒充会让价格弹性算出一个假的 0）。
--
CREATE TABLE platform_forecast_demand_history (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- SKU 码（**只记引用，不建跨服务 FK**：商品主数据归 catalog）。
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- 自然日（UTC 日历）。
  business_date date NOT NULL,

  -- 销量（件）。可以为 0（当天确实没卖）。
  units numeric(19,6) NOT NULL,

  -- 单位价（可空：上游只给销量时算不出来）。
  unit_price numeric(19,6),
  -- 销售额（可空）。
  revenue numeric(19,6),
  -- 币种：有金额就必须有币种；**不跨币种相加**。
  currency varchar(3),

  -- 渠道（店铺 / 平台）编码，可空。
  channel_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴 **来源**：MANUAL（运营手工录入）/ REPORTING（从 13 分析同步）。
  --   source_ref 记上游的定位键（口径 / 窗口 / 指标码），可回查。
  source text NOT NULL CHECK(source IN ('MANUAL','REPORTING')),
  source_ref varchar(200) NOT NULL DEFAULT '',

  recorded_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一 SKU 同一天只能有一条（防重复计数）。
  UNIQUE(tenant_id, sku_code, business_date),

  CHECK(btrim(sku_code) <> '' AND length(sku_code) <= 64),
  CHECK(length(sku_name) <= 200),
  CHECK(units >= 0),
  CHECK(unit_price IS NULL OR unit_price >= 0),
  CHECK(revenue IS NULL OR revenue >= 0),
  -- 有金额就必须有币种（三个字母）。
  CHECK((unit_price IS NULL AND revenue IS NULL) OR (currency IS NOT NULL AND currency ~ '^[A-Z]{3}$')),
  CHECK(length(channel_code) <= 64),
  CHECK(length(source_ref) <= 200)
);

CREATE INDEX platform_forecast_demand_history_by_sku
  ON platform_forecast_demand_history(tenant_id, sku_code, business_date DESC);

CREATE INDEX platform_forecast_demand_history_by_date
  ON platform_forecast_demand_history(tenant_id, business_date DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_forecast_run —— 预测运行
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次预测运行。**参数随运行落库**（而不是读全局配置），
-- 否则「这条预测是怎么算出来的」在事后无从回答。
--
CREATE TABLE platform_forecast_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 人可读的单号（租户内唯一）。
  run_no varchar(32) NOT NULL,

  -- 算法：
  --   MOVING_AVERAGE        —— 移动平均（最近 ma_window_days 天的均值）
  --   EXPONENTIAL_SMOOTHING —— 指数平滑（level = α·当日 + (1-α)·上一期水平）
  algorithm text NOT NULL
    CHECK(algorithm IN ('MOVING_AVERAGE','EXPONENTIAL_SMOOTHING')),

  -- 算法参数（只生效一个，另一个仍然落库 —— 事后要能看懂这次用了什么）。
  ma_window_days int NOT NULL DEFAULT 3,
  smoothing_alpha numeric(9,6) NOT NULL DEFAULT 0.3,

  -- 统计窗口（历史天数）与预测期（未来天数）。
  window_days int NOT NULL,
  horizon_days int NOT NULL,

  -- 置信水平（0.90 / 0.95 / 0.99）与对应的 z 值。
  confidence_level numeric(5,4) NOT NULL,
  z_value numeric(9,6) NOT NULL,

  -- 🔴 **本次运行实际用到的历史来源**（台账里这个 SKU 的销量是哪来的）。
  history_source text NOT NULL DEFAULT 'MANUAL'
    CHECK(history_source IN ('MANUAL','REPORTING','MIXED')),

  -- 状态机：PENDING → RUNNING → COMPLETED / FAILED。
  status text NOT NULL DEFAULT 'COMPLETED'
    CHECK(status IN ('PENDING','RUNNING','COMPLETED','FAILED')),

  -- 本次运行的计数（为什么有 SKU 没出预测，写在 skipped_reason 里）。
  evaluated_sku_count int NOT NULL DEFAULT 0,
  forecast_sku_count int NOT NULL DEFAULT 0,
  skipped_sku_count int NOT NULL DEFAULT 0,
  skipped_reason varchar(500) NOT NULL DEFAULT '',

  -- 🔴 **上游接通情况**：库存 / 在途读不到时**如实记 false**，
  --   那一次运行就**没有**补货建议 —— 不能拿 0 冒充「库存为 0」。
  inventory_connected boolean NOT NULL DEFAULT false,
  in_transit_connected boolean NOT NULL DEFAULT false,
  purchase_connected boolean NOT NULL DEFAULT false,

  -- 提示词 / 说明。
  note varchar(500) NOT NULL DEFAULT '',

  requested_by uuid,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, run_no),

  CHECK(ma_window_days >= 1 AND ma_window_days <= 30),
  CHECK(smoothing_alpha > 0 AND smoothing_alpha < 1),
  CHECK(window_days >= 7 AND window_days <= 365),
  CHECK(horizon_days >= 1 AND horizon_days <= 180),
  CHECK(confidence_level > 0 AND confidence_level < 1),
  CHECK(z_value > 0),
  CHECK(evaluated_sku_count >= 0 AND forecast_sku_count >= 0 AND skipped_sku_count >= 0),
  CHECK(forecast_sku_count + skipped_sku_count <= evaluated_sku_count),
  -- 终态必须有完成时刻。
  CHECK(status <> 'COMPLETED' OR finished_at IS NOT NULL)
);

CREATE INDEX platform_forecast_run_by_started
  ON platform_forecast_run(tenant_id, started_at DESC);

-- 发号序列（人可读单号）。照抄 V380 的 platform_text_task_no_seq 纪律。
CREATE SEQUENCE platform_forecast_run_no_seq;

-- ---------------------------------------------------------------------------
-- 4. platform_forecast_result —— 一个 SKU 在一次运行里的预测结果
-- ---------------------------------------------------------------------------
--
-- 🔴 **point / lower / upper 三件齐全**：蓝图加粗句「预测必须给置信区间」——
--   只给单点数字 = 未完成。所以这三列**都是 NOT NULL**，
--   样本不足的 SKU **根本不进这张表**（它进的是 skipped 计数与原因）。
--
CREATE TABLE platform_forecast_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,

  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- 本次用的算法与参数（随结果落库，事后可复核）。
  algorithm text NOT NULL
    CHECK(algorithm IN ('MOVING_AVERAGE','EXPONENTIAL_SMOOTHING')),
  ma_window_days int NOT NULL DEFAULT 3,
  smoothing_alpha numeric(9,6) NOT NULL DEFAULT 0.3,
  window_days int NOT NULL,
  horizon_days int NOT NULL,

  -- 🔴 **历史来源**：这个 SKU 的销量来自人工录入还是 13 分析同步。
  history_source text NOT NULL CHECK(history_source IN ('MANUAL','REPORTING')),

  -- 样本口径：窗口里有记录的天数、覆盖率。
  sample_days int NOT NULL,
  coverage_rate numeric(9,6) NOT NULL,

  -- 基线日均（促销调整前）。
  baseline_daily numeric(19,6) NOT NULL,
  -- 促销因子（1 + 预测期内最大单次促销的提升率；无促销 = 1）。
  promo_factor numeric(9,6) NOT NULL DEFAULT 1,

  -- 🔴 **预测期总需求**：点估计 + 置信区间（NUMERIC(19,6)，禁浮点）。
  point numeric(19,6) NOT NULL,
  lower numeric(19,6) NOT NULL,
  upper numeric(19,6) NOT NULL,

  -- 区间是怎么来的（残差标准差 + z 值 + 置信水平）。
  sigma numeric(19,6) NOT NULL,
  z_value numeric(9,6) NOT NULL,
  confidence_level numeric(5,4) NOT NULL,

  -- 库存 / 在途 / 价格：**上游读不到时为 NULL**（不是 0）。
  inventory_qty numeric(19,6),
  in_transit_qty numeric(19,6),
  -- 采购侧未到货量（6 采购，只作参考，**不重复扣减**）。
  purchase_pending_qty numeric(19,6),
  unit_price numeric(19,6),
  currency varchar(3),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, run_id) REFERENCES platform_forecast_run(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(sku_code) <> '' AND length(sku_code) <= 64),
  CHECK(length(sku_name) <= 200),
  CHECK(sample_days >= 1),
  CHECK(coverage_rate >= 0 AND coverage_rate <= 1),
  CHECK(baseline_daily >= 0),
  CHECK(promo_factor >= 0),
  -- 🔴 **区间必须真的包住点估计**，且下界不能是负数。
  CHECK(lower >= 0),
  CHECK(lower <= point AND point <= upper),
  CHECK(sigma >= 0),
  CHECK(inventory_qty IS NULL OR inventory_qty >= 0),
  CHECK(in_transit_qty IS NULL OR in_transit_qty >= 0),
  CHECK(purchase_pending_qty IS NULL OR purchase_pending_qty >= 0),
  CHECK(unit_price IS NULL OR unit_price >= 0),
  CHECK(unit_price IS NULL OR (currency IS NOT NULL AND currency ~ '^[A-Z]{3}$'))
);

CREATE INDEX platform_forecast_result_by_run
  ON platform_forecast_result(tenant_id, run_id, sku_code);

CREATE INDEX platform_forecast_result_by_sku
  ON platform_forecast_result(tenant_id, sku_code, created_at DESC);

-- ---------------------------------------------------------------------------
-- 5. platform_forecast_factor —— 关键影响因素
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个影响因素。蓝图加粗句「**预测必须给……影响因素**」——
-- 只给一个标签（「促销影响」）运营没法据此判断，所以每条因素必须带：
--   ① 因素名（factor_label）② 方向（direction）③ 该因素的取值（value / value_text）
--   ④ **口径说明**（explanation：这个数是怎么算的、事实来自哪里）。
--
-- direction：
--   UP     —— 把预测往上推
--   DOWN   —— 把预测往下压
--   NEUTRAL —— 说明性因素（不改变预测量，但影响可信度，例如样本覆盖度）
--
CREATE TABLE platform_forecast_factor (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  result_id uuid NOT NULL,

  -- 因素码：
  --   BASE_LEVEL   基线水平（预测的底子）
  --   PROMOTION    促销 / 大促影响
  --   SEASONALITY  星期季节性
  --   PRICE_CHANGE 价格变动
  --   DATA_QUALITY 样本覆盖度（影响区间可信度，不改变点估计）
  factor_code text NOT NULL
    CHECK(factor_code IN ('BASE_LEVEL','PROMOTION','SEASONALITY','PRICE_CHANGE','DATA_QUALITY')),
  factor_label varchar(64) NOT NULL,

  direction text NOT NULL CHECK(direction IN ('UP','DOWN','NEUTRAL')),

  -- 该因素的取值（数值型；文字型取值写 value_text）。
  value numeric(19,6),
  value_text varchar(200) NOT NULL DEFAULT '',

  -- 该因素对**预测期总需求**的绝对影响量（件）。说明性因素为 0。
  impact numeric(19,6) NOT NULL DEFAULT 0,

  -- 🔴 **口径说明**：这个数怎么来的、事实来自哪里。页面直接显示，不自己编。
  explanation varchar(500) NOT NULL DEFAULT '',
  -- 该因素的事实来源（表名 / 上游模块）。
  source varchar(64) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, result_id) REFERENCES platform_forecast_result(tenant_id, id) ON DELETE CASCADE,

  CHECK(length(factor_label) <= 64),
  CHECK(length(value_text) <= 200),
  CHECK(length(explanation) <= 500),
  CHECK(length(source) <= 64)
);

CREATE INDEX platform_forecast_factor_by_result
  ON platform_forecast_factor(tenant_id, result_id, factor_code);

-- ---------------------------------------------------------------------------
-- 6. platform_forecast_suggestion —— 建议台账（**只建议，不执行**）
-- ---------------------------------------------------------------------------
--
-- 🔴 **状态机显式区分「建议已生成」与「已采纳 / 已执行」**：
--
--   SUGGESTED —— 建议已生成（本模块能做的**全部**；到这里为止本模块**一个下游动作都没做**）
--   ADOPTED   —— 人工采纳（**只是记下「人决定了采纳」**；本模块**不执行**任何下游动作）
--   REJECTED  —— 人工驳回
--
-- 🔴 **没有 EXECUTED**：执行补货单（6 采购）与改价（5 定价）是**别的模块的写入所有权**，
--   跨服务写它们的库是 AGENTS.md 明令禁止的。
--   ⇒ 本表的结构上**不可能**声称「已经补货」或「已经改价」。
--
-- 🔴 **REPLENISH（补货）**：建议量 = max(0, 预测期需求**上界** − 在手库存 − 在途净额)。
--   取上界是**保守口径**（宁可多备一点也别缺货），写在 basis 里。
--   库存 / 在途读不到 ⇒ **不生成**这一条建议（不用 0 冒充「库存为 0」）。
--
-- 🔴 **PRICE（调价）**：只有在**能估计出价格弹性**时才给建议；
--   弹性估不出来 ⇒ 不给建议，并在详情里写明「为什么给不出来」。
--
CREATE TABLE platform_forecast_suggestion (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  suggestion_type text NOT NULL CHECK(suggestion_type IN ('REPLENISH','PRICE')),

  -- 关联的运行与结果（只记引用；**不建跨服务 FK**）。
  run_id uuid,
  result_id uuid,

  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- 库存侧（上游读不到时为 NULL）。
  current_qty numeric(19,6),
  in_transit_qty numeric(19,6),
  -- 采购侧未到货量（6 采购；**只作参考，不重复扣减**）。
  purchase_pending_qty numeric(19,6),

  -- 补货建议。
  suggested_qty numeric(19,6),
  -- 需求侧（点估计 + 区间）。
  demand_point numeric(19,6),
  demand_lower numeric(19,6),
  demand_upper numeric(19,6),

  -- 调价建议。
  current_price numeric(19,6),
  suggested_price numeric(19,6),
  -- 建议调价幅度（0.1 = 降价 10%；负数 = 涨价）。
  price_delta_rate numeric(9,6),
  -- 预期销量变化率（弹性 × 调价幅度）。
  expected_units_delta_rate numeric(9,6),
  -- 价格弹性（算不出来时为 NULL）。
  elasticity numeric(19,6),
  currency varchar(3),

  -- 状态机：SUGGESTED → ADOPTED / REJECTED。
  status text NOT NULL DEFAULT 'SUGGESTED'
    CHECK(status IN ('SUGGESTED','ADOPTED','REJECTED')),

  -- 🔴 **口径说明**：这个建议是怎么算出来的（页面直接显示）。
  basis varchar(500) NOT NULL DEFAULT '',

  decided_by uuid,
  decided_at timestamptz,
  decision_note varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, run_id) REFERENCES platform_forecast_run(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, result_id) REFERENCES platform_forecast_result(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(sku_code) <> '' AND length(sku_code) <= 64),
  CHECK(length(sku_name) <= 200),
  CHECK(current_qty IS NULL OR current_qty >= 0),
  CHECK(in_transit_qty IS NULL OR in_transit_qty >= 0),
  CHECK(purchase_pending_qty IS NULL OR purchase_pending_qty >= 0),
  CHECK(suggested_qty IS NULL OR suggested_qty >= 0),
  CHECK(demand_point IS NULL OR demand_point >= 0),
  CHECK(demand_lower IS NULL OR demand_lower >= 0),
  CHECK(demand_upper IS NULL OR demand_upper >= 0),
  CHECK(current_price IS NULL OR current_price >= 0),
  CHECK(suggested_price IS NULL OR suggested_price >= 0),
  -- 🔴 **补货建议必须带需求区间**：单点数字会让用户过度信任（蓝图加粗句）。
  CHECK(suggestion_type <> 'REPLENISH' OR (suggested_qty IS NOT NULL AND demand_upper IS NOT NULL)),
  -- 🔴 **调价建议必须带弹性**：没有弹性依据的调价建议是拍脑袋。
  CHECK(suggestion_type <> 'PRICE' OR elasticity IS NOT NULL),
  -- 非初始态必须有人工决策时刻。
  CHECK(status = 'SUGGESTED' OR decided_at IS NOT NULL),
  CHECK(length(basis) <= 500),
  CHECK(length(decision_note) <= 500)
);

CREATE INDEX platform_forecast_suggestion_by_status
  ON platform_forecast_suggestion(tenant_id, status, created_at DESC);

CREATE INDEX platform_forecast_suggestion_by_sku
  ON platform_forecast_suggestion(tenant_id, sku_code, created_at DESC);

-- ===========================================================================
-- 7. 租户隔离（RLS）
-- ===========================================================================
--
-- 六张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V380 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
ALTER TABLE platform_forecast_promotion ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_forecast_promotion FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_forecast_promotion
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_forecast_demand_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_forecast_demand_history FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_forecast_demand_history
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_forecast_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_forecast_run FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_forecast_run
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_forecast_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_forecast_result FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_forecast_result
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_forecast_factor ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_forecast_factor FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_forecast_factor
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_forecast_suggestion ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_forecast_suggestion FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_forecast_suggestion
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 8. BEFORE INSERT / UPDATE 触发器兜底（租户上下文）
-- ===========================================================================
--
-- 🔴 RLS 的 WITH CHECK 已经挡住「写入的 tenant_id 不是当前租户」；
--   但它报出来的错是「违反行级安全策略」，看不出**是哪个租户**对不上。
--   触发器给出可读的错误（identity V384 的 alert_rule_tenant_guard 同一纪律）。
--
-- 🔴 **没有租户上下文时直接拒绝写入**：租户不是客户端自称的。
--
CREATE FUNCTION platform_forecast_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入选品与需求预测的数据'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '选品与需求预测数据的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION platform_forecast_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_forecast_tenant_guard() TO platform_app;

CREATE TRIGGER platform_forecast_promotion_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_forecast_promotion
  FOR EACH ROW EXECUTE FUNCTION platform_forecast_tenant_guard();

CREATE TRIGGER platform_forecast_demand_history_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_forecast_demand_history
  FOR EACH ROW EXECUTE FUNCTION platform_forecast_tenant_guard();

CREATE TRIGGER platform_forecast_run_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_forecast_run
  FOR EACH ROW EXECUTE FUNCTION platform_forecast_tenant_guard();

CREATE TRIGGER platform_forecast_result_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_forecast_result
  FOR EACH ROW EXECUTE FUNCTION platform_forecast_tenant_guard();

CREATE TRIGGER platform_forecast_factor_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_forecast_factor
  FOR EACH ROW EXECUTE FUNCTION platform_forecast_tenant_guard();

CREATE TRIGGER platform_forecast_suggestion_tenant_guard
  BEFORE INSERT OR UPDATE ON platform_forecast_suggestion
  FOR EACH ROW EXECUTE FUNCTION platform_forecast_tenant_guard();

-- ===========================================================================
-- 9. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的预测台账，能写（登记促销、录历史、
-- 跑预测、落结果与建议、人工采纳 / 驳回建议），能删（改促销 / 改历史录入）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_forecast_promotion TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_forecast_demand_history TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_forecast_run TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_forecast_result TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_forecast_factor TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_forecast_suggestion TO platform_app;

-- 发号序列的取号权限：没有它，服务层拿不到单号。
GRANT USAGE, SELECT ON SEQUENCE platform_forecast_run_no_seq TO platform_app;
