-- ===========================================================================
-- V406 · logistics_analysis —— 13.5.3.1 物流分析
-- ===========================================================================
--
-- **本文件做什么**：给 reporting 服务（13 利润与经营分析的唯一归属服务）加
-- **八张分析投影表 + 一张阈值规则表**，让「物流分析报表 + 渠道对比」这件事
-- **在查询期**算出来：
--
--   reporting_logistics_analysis_cost_fact            —— 逐币种全口径成本快照（as_of 粒度）
--   reporting_logistics_analysis_channel_fact         —— 渠道（承运商 × 渠道）成本对比（as_of 粒度）
--   reporting_logistics_analysis_timeliness_fact      —— 时效达成率矩阵（渠道 × 国家，as_of 粒度）
--   reporting_logistics_analysis_waybill_fact         —— 运单级尾程（国家维度 + 单均运费分母）
--   reporting_logistics_analysis_allocation_fact      —— 头程分摊单（头程分摊后成本）
--   reporting_logistics_analysis_allocation_line_fact —— 头程分摊明细行（SKU 级 ⇒ 类目维度）
--   reporting_logistics_analysis_exception_fact       —— 异常件（窗口内）
--   reporting_logistics_analysis_sku_category_fact    —— SKU → 类目映射
--   reporting_logistics_analysis_rule                 —— 阈值规则
--
-- ---------------------------------------------------------------------------
-- 0. 蓝图原文（本迁移要兑现的全部内容）
-- ---------------------------------------------------------------------------
--
--   ### 13.5.3.1 物流分析
--   - **职责**：物流分析：单均运费、头程分摊后成本、渠道对比、时效达成率、
--             各国成本对比、异常件占比。
--   - **输入**：物流数据（10）；成本数据。
--   - **输出**：物流报表 + 渠道对比。
--   - **关系**：上游 = 10 物流与头程；下游 = 5 定价（运费测算校准）、13.4 利润。
--             **物流成本占比高的类目必须单独定价**，统一毛利率会吃掉利润。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 本迁移**不建第二个物流真相**
-- ---------------------------------------------------------------------------
--
-- 运单 / 轨迹 / 时效 / 异常件的事实**全部归 channel 服务所有**（卷 10 物流与头程；
-- 集成头里没有独立的 logistics 服务，卷 10 的模块按「谁拥有物流商连接与轨迹」
-- 落在 channel 与 catalog）。
--
-- ⇒ 本模块只做**分析投影**：把上游**已经算好**的结果拉成本服务的快照与行级事实，
--   供报表做单均运费、头程分摊后成本、渠道对比、时效达成率、各国成本对比、
--   异常件占比。
--
-- ⚠️ **跨服务不直连对方库**（AGENTS.md「模块边界纪律」）：本表的数据**全部**由
--   reporting 服务经**授权只读 API**（channel / trade / catalog）拉取后落地，
--   禁止跨库 FK、跨库视图或直连对方 schema。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 三套上游口径**互斥**，否则同一笔成本会被算两次
-- ---------------------------------------------------------------------------
--
--   | 事实源                                        | 粒度                    | 本模块用它做什么                |
--   |-----------------------------------------------|------------------------|---------------------------------|
--   | channel 10.6.1.1 `/logistics-cost-analysis/overview` | 币种 × 成本分量    | **全口径成本**（尾程 / 头程分摊 / 关税 / 包材 / 合计） |
--   | channel 10.6.1.1 `/logistics-cost-analysis/by-channel` | 承运商 × 渠道      | **渠道对比**                    |
--   | channel 10.6.1.1 `/logistics-cost-analysis/allocations` | 头程分摊单        | **头程分摊后成本**（分摊单 + 明细行） |
--   | channel 10.6.2.1 `/logistics-timeliness/*`     | 渠道 × 国家            | **时效达成率**                  |
--   | channel 10.3.1.2 `/logistics-tracking/exceptions` | 异常件一行           | **异常件占比**（窗口内）        |
--   | trade 10.2.1.1 `/trade/logistics/waybills/list` | 一张运单一行          | **各国成本对比** + **单均运费的订单分母** |
--
-- ⚠️ **channel 自己的 `perOrderFreight` 不是「单均运费」**：它的分母是
--   **已登记运费的运单票数**（票），不是订单数。一单多票（拆单发运）时，
--   票数 > 订单数 ⇒ 上游那个数**偏小**。本模块的 `singleFreightPerOrder`
--   用**订单数**（同一批运单上的 distinct `sales_order_no`）作分母，
--   并把上游那个数原样搬过来放在 `upstreamPerWaybillFreight` 一列做对照 ——
--   两个口径**并排显示**，不混为一谈。
--
-- ⚠️ **时效达成率、异常件占比一律读上游判定，不重算**：
--   达成率归 10.6.2.1（准点数 ÷ 可比数），异常件归 10.3.1.2（异常件是**已判定**的件）。
--   本模块自己再判一次就会与上游的处置清单对不上。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 「算不出来」与「等于 0」必须分开
-- ---------------------------------------------------------------------------
--
-- 下列情形在本模块里一律是 **NULL + 一句原因**（页面上是「—（不可比）」），**绝不是 0**：
--
--   * 没有订单 ⇒ 单均运费**不可比**（分母为 0）；
--   * 窗口里没有运单 ⇒ 异常件占比**不可比**（分母为 0）；
--   * 上游没交事实（channel 的成本报表没同步）⇒ 那一段是「读不到」；
--   * 头程分摊单没有明细行 ⇒ 类目维度不可归属（**不是 0 元**）；
--   * 类目没有销售额 ⇒ 物流成本占比**不可比**（不是 0%）。
--
-- ---------------------------------------------------------------------------
-- 4. 🔴 多币种绝不相加
-- ---------------------------------------------------------------------------
--
-- 本服务**不自行折算币种**（本仓没有汇率同步器，任务书明写「没有汇率表也没有汇率同步器」）。
-- ⇒ 每一行带自己的币种；跨币种的「总计」**不存在**（单币种时才给）。
-- 各国成本对比**逐币种**计算：某币种的国家成本 ÷ **同币种**的合计。
--
-- ---------------------------------------------------------------------------
-- 5. 多租户（RLS isolation）+ 授权
-- ---------------------------------------------------------------------------
--
-- 九张表都是租户级表：tenant_id uuid NOT NULL + ENABLE **与 FORCE** RLS
-- + CREATE POLICY isolation + GRANT reporting_app / starcon_app。
-- 与 V398 / V399 / V401 / V403 / V405 **完全同构**。
--
-- ⚠️ **零 ALTER 既有表**（并行车道同时提交迁移时，ALTER 既有表是锁表 + 冲突的双重风险）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_logistics_analysis_cost_fact —— 逐币种全口径成本快照
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个 as_of（= 刷新窗口的止日）× 一个币种」的**上游成本报表快照**。
--
-- ⚠️ **as_of 是快照日，不是业务日**：成本报表是**整窗汇总**，
--   它的「业务日」就是窗口本身 ⇒ 一窗一张快照，取数时按
--   「as_of ≤ 窗口止日里最新的一张」取（与 13.5.1.1 库存分析的 latestAsOf 同纪律）。
--
-- ⚠️ **subtotal 可空**：上游的合计是「已计入分量之和」——
--   有分量取不到数时（仓储费在本仓**没有来源**）合计是**算不出来**，不是 0。
--   missing_components 把没算谁逐条列出来。
--
-- ⚠️ **金额要么带币种、要么两者皆空**：不允许「有金额但没币种」
--   （那会让金额在跨币种统计里被悄悄丢掉）。
CREATE TABLE reporting_logistics_analysis_cost_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 快照轴 =====
  -- 快照日（= 本次刷新窗口的止日）。
  as_of date NOT NULL,
  -- 本次快照所覆盖的窗口（随行留证 ⇒ 报表可复盘「这批数是哪个窗口算的」）。
  window_from date NOT NULL,
  window_to date NOT NULL,

  -- ===== 币种（**不跨币种相加**：仓库铁律）=====
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 五个成本分量（可空 = 这一位取不到数，**不是 0**）=====
  -- 尾程运费（承运商侧实际登记的运费）。
  last_mile_freight numeric(24,4),
  -- 头程分摊后成本（按分摊规则版本 + 结账时点归集）。
  first_mile_allocation numeric(24,4),
  -- 关税与增值税 / GST（实缴进合计，未缴只进估算序列）。
  duty_and_tax numeric(24,4),
  -- 包材成本（**参数**口径：租户维护的每票包材成本）。
  packaging numeric(24,4),
  -- 合计（= 已计入分量之和；**有分量缺失 ⇒ NULL**，不是 0）。
  subtotal numeric(24,4),

  -- ===== 覆盖说明 =====
  -- 本币种**已登记运费**的运单票数（= 上游的分母口径，是「每票」不是「每单」）。
  registered_waybills bigint NOT NULL DEFAULT 0 CHECK(registered_waybills >= 0),
  -- 本币种**未登记运费**的运单票数（一票都没登记 ≠ 登记为 0）。
  not_registered_waybills bigint NOT NULL DEFAULT 0 CHECK(not_registered_waybills >= 0),
  -- 已登记运单的毛重合计（kg）。
  total_weight_kg numeric(19,6),
  -- 上游的「每票运费」（= 已登记运费 ÷ 已登记票数）。
  -- ⚠️ **这不是单均运费**（分母是票不是单）；本模块的单均运费另算，见 waybill_fact。
  upstream_per_waybill_freight numeric(24,4),
  -- 上游的「每公斤运费」。
  upstream_per_kg_freight numeric(24,4),

  -- 计入合计的分量（逗号分隔；空 = 一个都没计入）。
  included_components text NOT NULL DEFAULT '',
  -- 没取到数的分量（逗号分隔；**不等于 0**）。
  missing_components text NOT NULL DEFAULT '',

  -- 上游整窗扫描到的运单票数（= 上游 scannedWaybills）。
  scanned_waybills bigint NOT NULL DEFAULT 0 CHECK(scanned_waybills >= 0),
  -- 上游是否撞到扫描上限（撞到了就不能把统计说成全量）。
  truncated boolean NOT NULL DEFAULT false,

  source_service text NOT NULL DEFAULT 'channel',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一个 (租户, 快照日, 币种) 只有一行（重放不翻倍）。
  UNIQUE(tenant_id, as_of, currency),

  -- 金额不得为负。
  CHECK(last_mile_freight IS NULL OR last_mile_freight >= 0),
  CHECK(first_mile_allocation IS NULL OR first_mile_allocation >= 0),
  CHECK(duty_and_tax IS NULL OR duty_and_tax >= 0),
  CHECK(packaging IS NULL OR packaging >= 0),
  CHECK(subtotal IS NULL OR subtotal >= 0),
  CHECK(total_weight_kg IS NULL OR total_weight_kg >= 0),
  CHECK(upstream_per_waybill_freight IS NULL OR upstream_per_waybill_freight >= 0),
  CHECK(upstream_per_kg_freight IS NULL OR upstream_per_kg_freight >= 0),
  -- 窗口不能倒过来。
  CHECK(window_to >= window_from)
);

-- 取窗口内的最新快照（as_of ≤ 窗口止日里最新的一张）。
CREATE INDEX reporting_logistics_analysis_cost_fact_by_as_of
  ON reporting_logistics_analysis_cost_fact(tenant_id, as_of);
CREATE INDEX reporting_logistics_analysis_cost_fact_by_currency
  ON reporting_logistics_analysis_cost_fact(tenant_id, currency, as_of);

-- ---------------------------------------------------------------------------
-- 2. reporting_logistics_analysis_channel_fact —— 渠道对比（承运商 × 渠道）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个 as_of × 一个承运商 × 一个渠道 × 一个币种」。
--
-- ⚠️ **渠道维度来自上游**（channel 10.1.1.2 登记的渠道）：承运商编码 + 渠道枚举。
--   本模块**不另造一套渠道词表**（那会让「哪些渠道存在」有两个真相）。
--
-- ⚠️ **逐币种**：跨币种的渠道成本合计**不存在**。
CREATE TABLE reporting_logistics_analysis_channel_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 快照轴 =====
  as_of date NOT NULL,
  window_from date NOT NULL,
  window_to date NOT NULL,

  -- ===== 渠道维度（上游登记的渠道）=====
  carrier_code text NOT NULL DEFAULT '',
  carrier_channel text NOT NULL DEFAULT '',
  carrier_channel_label text NOT NULL DEFAULT '',

  -- ===== 币种（**不跨币种相加**）=====
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 指标 =====
  -- 该渠道该币种的运费合计。
  amount numeric(24,4) NOT NULL CHECK(amount >= 0),
  -- 该渠道该币种的运单票数。
  waybills bigint NOT NULL DEFAULT 0 CHECK(waybills >= 0),
  -- 该渠道该币种的毛重合计（kg）。
  total_weight_kg numeric(19,6),
  -- 上游的「每票运费」（**不是单均运费**：分母是票不是单）。
  upstream_per_waybill_freight numeric(24,4),

  source_service text NOT NULL DEFAULT 'channel',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**。
  UNIQUE(tenant_id, as_of, carrier_code, carrier_channel, currency),

  CHECK(total_weight_kg IS NULL OR total_weight_kg >= 0),
  CHECK(upstream_per_waybill_freight IS NULL OR upstream_per_waybill_freight >= 0),
  CHECK(window_to >= window_from)
);

CREATE INDEX reporting_logistics_analysis_channel_fact_by_as_of
  ON reporting_logistics_analysis_channel_fact(tenant_id, as_of);
CREATE INDEX reporting_logistics_analysis_channel_fact_by_channel
  ON reporting_logistics_analysis_channel_fact(tenant_id, carrier_code, carrier_channel, as_of);

-- ---------------------------------------------------------------------------
-- 3. reporting_logistics_analysis_timeliness_fact —— 时效达成率（渠道 × 国家）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个 as_of × 一个渠道 × 一个国家」。
--
-- ⚠️ **达成率一律搬上游的判定**（准点数 ÷ 可比数）：本模块**不重算**。
--   准点 / 超时是业务事实（承诺 vs 实际），重算就与上游的处置清单对不上。
--
-- ⚠️ **分母叫「可比数」**：没有承诺送达日的票**不进分母**（NO_PROMISE），
--   未签收的票也不进（IN_TRANSIT）。分母为 0 ⇒ 达成率**不可比**（不是 0%）。
CREATE TABLE reporting_logistics_analysis_timeliness_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 快照轴 =====
  as_of date NOT NULL,
  window_from date NOT NULL,
  window_to date NOT NULL,

  -- ===== 维度：渠道 × 国家 =====
  channel_id uuid,
  channel_code text NOT NULL DEFAULT '',
  channel_name text NOT NULL DEFAULT '',
  country_code text NOT NULL DEFAULT '',

  -- ===== 计数（口径全部来自上游 10.6.2.1）=====
  shipped_count int NOT NULL DEFAULT 0 CHECK(shipped_count >= 0),
  delivered_count int NOT NULL DEFAULT 0 CHECK(delivered_count >= 0),
  -- 可比数 = 准点数 + 超时数（**分母**）。
  comparable_count int NOT NULL DEFAULT 0 CHECK(comparable_count >= 0),
  on_time_count int NOT NULL DEFAULT 0 CHECK(on_time_count >= 0),
  overdue_count int NOT NULL DEFAULT 0 CHECK(overdue_count >= 0),
  -- 没有承诺送达日（**不进分母**）。
  no_promise_count int NOT NULL DEFAULT 0 CHECK(no_promise_count >= 0),
  -- 在途未签收（**不进分母**）。
  pending_count int NOT NULL DEFAULT 0 CHECK(pending_count >= 0),
  returned_count int NOT NULL DEFAULT 0 CHECK(returned_count >= 0),

  -- ===== 比率（**可空 = 不可比，不是 0%**）=====
  -- 达成率 = 准点数 ÷ 可比数（上游已定标为百分数）。
  on_time_rate numeric(19,6),
  -- 妥投率 = 签收数 ÷ 发出数。
  delivery_rate numeric(19,6),
  -- 超时率 = 超时数 ÷ 可比数。
  overdue_rate numeric(19,6),
  -- 平均实际时效（小时）。
  avg_transit_hours numeric(19,4),
  -- 平均时效偏差（小时，带符号：正 = 晚于承诺）。
  avg_deviation_hours numeric(19,4),
  -- 时效评分（0~100；可空 = 样本不足，不可比，不是 0 分）。
  score numeric(19,4),
  score_grade text NOT NULL DEFAULT '',

  source_service text NOT NULL DEFAULT 'channel',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**。
  UNIQUE(tenant_id, as_of, channel_id, country_code),

  -- 计数之间的一致性：可比数 = 准点 + 超时（**上游口径**，不一致就说明读错了）。
  CHECK(comparable_count = on_time_count + overdue_count),
  -- 达成率与超时率都不得超过 100%。
  CHECK(on_time_rate IS NULL OR (on_time_rate >= 0 AND on_time_rate <= 100)),
  CHECK(overdue_rate IS NULL OR (overdue_rate >= 0 AND overdue_rate <= 100)),
  CHECK(delivery_rate IS NULL OR (delivery_rate >= 0 AND delivery_rate <= 100)),
  CHECK(score IS NULL OR (score >= 0 AND score <= 100)),
  CHECK(window_to >= window_from)
);

CREATE INDEX reporting_logistics_analysis_timeliness_fact_by_as_of
  ON reporting_logistics_analysis_timeliness_fact(tenant_id, as_of);
CREATE INDEX reporting_logistics_analysis_timeliness_fact_by_country
  ON reporting_logistics_analysis_timeliness_fact(tenant_id, country_code, as_of);
CREATE INDEX reporting_logistics_analysis_timeliness_fact_by_channel
  ON reporting_logistics_analysis_timeliness_fact(tenant_id, channel_code, as_of);

-- ---------------------------------------------------------------------------
-- 4. reporting_logistics_analysis_waybill_fact —— 运单级尾程（国家维度 + 订单分母）
-- ---------------------------------------------------------------------------
--
-- 一行 = **一张运单**（trade 10.2.1.1 运单清单的一行）。
--
-- ⚠️ **本表是「各国成本对比」与「单均运费分母」的唯一来源**：
--   运单是**运费与目的国共同的最小颗粒** —— 渠道维度的成本报表按渠道分组，
--   装不下「目的国」这一维；只有运单级事实同时带运费金额与目的国。
--
-- ⚠️ **运费没登记 ≠ 运费是 0**：{@code freight_gap_code = 'NOT_REGISTERED'} 的票
--   **有票数、没有金额**，它进「未登记票数」，**不进任何金额合计**。
--
-- ⚠️ **订单分母用同一批运单上的 distinct sales_order_no**：
--   这样「运费总额」与「订单数」的**范围天然一致**（同一批运单、同一窗口）。
--   产生不了运单的订单既没有分子也没有分母 —— 那才是同范围。
CREATE TABLE reporting_logistics_analysis_waybill_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 时间轴 =====
  -- 运单签发日（业务日；上游 createdAt 在本次刷新时区下的日历日）。
  occurred_on date NOT NULL,
  -- 原始时刻（timestamptz，**上游原样搬运**）。
  occurred_at timestamptz NOT NULL,

  -- ===== 上游锚点（幂等的另一半）=====
  waybill_id uuid NOT NULL,
  waybill_no text NOT NULL DEFAULT '',
  -- 销售订单号（**单均运费的订单分母就是它**）。
  sales_order_no text NOT NULL DEFAULT '',

  -- ===== 渠道与目的国 =====
  carrier_code text NOT NULL DEFAULT '',
  carrier_channel text NOT NULL DEFAULT '',
  destination_country text NOT NULL DEFAULT '',

  -- ===== 数量与金额（全仓无浮点）=====
  gross_weight_kg numeric(19,6),
  freight_amount numeric(24,4),
  freight_currency char(3),
  -- REGISTERED 已登记 / NOT_REGISTERED 未登记（**与金额的可空性一致**）。
  freight_gap_code text NOT NULL DEFAULT 'NOT_REGISTERED'
    CHECK(freight_gap_code IN ('REGISTERED','NOT_REGISTERED')),

  source_service text NOT NULL DEFAULT 'trade',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一张运单只有一行（重放不翻倍）。
  UNIQUE(tenant_id, waybill_id),

  -- 🔴 **一致性写进 CHECK**：有金额 ⇒ 有币种且缺口码是「已登记」；
  --   没金额 ⇒ 缺口码必须是「未登记」。
  --   ⚠️ **允许「有币种但没金额」**：币种已知、金额还没登记是真实存在的中间态
  --   （上游 channel 的成本分析也按币种统计这类票），它**有票数、没有金额**，
  --   进「未登记票数」，**不进任何金额合计**。
  CHECK(
    (freight_amount IS NOT NULL AND freight_currency IS NOT NULL
       AND freight_currency ~ '^[A-Z]{3}$' AND freight_gap_code = 'REGISTERED')
    OR (freight_amount IS NULL AND freight_gap_code = 'NOT_REGISTERED')
  ),
  -- 毛重与金额不得为负。
  CHECK(gross_weight_kg IS NULL OR gross_weight_kg >= 0),
  CHECK(freight_amount IS NULL OR freight_amount >= 0)
);

-- 按业务日取窗口（各国成本对比 / 单均运费都走它）。
CREATE INDEX reporting_logistics_analysis_waybill_fact_by_day
  ON reporting_logistics_analysis_waybill_fact(tenant_id, occurred_on);
-- 按目的国取（各国成本对比）。
CREATE INDEX reporting_logistics_analysis_waybill_fact_by_country
  ON reporting_logistics_analysis_waybill_fact(tenant_id, destination_country, occurred_on);
-- 按渠道取（渠道维度的交叉核对）。
CREATE INDEX reporting_logistics_analysis_waybill_fact_by_channel
  ON reporting_logistics_analysis_waybill_fact(tenant_id, carrier_code, carrier_channel, occurred_on);

-- ---------------------------------------------------------------------------
-- 5. reporting_logistics_analysis_allocation_fact —— 头程分摊单
-- ---------------------------------------------------------------------------
--
-- 一行 = **一张头程分摊单**（channel 10.6.1.1 自持的分摊单事实，V213）。
--
-- ⚠️ **本模块不重算一套分摊**：分摊规则、基准、结账时点（cost_as_of）全归
--   channel 10.6.1.1。本表只是把**它算好的**结果搬过来 ——
--   两个分摊口径会让「物流成本占比」有两个答案。
--
-- ⚠️ **结账时点（cost_as_of）随行留证**：成本算到哪一刻的头上要查得到，
--   否则同一个 SKU 今天 30% 明天 25% 没人解释得清。
--
-- ⚠️ **目的国**：分摊单带 `destination_country_code` ⇒ 各国头程成本从这里出。
CREATE TABLE reporting_logistics_analysis_allocation_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 上游锚点 =====
  allocation_id uuid NOT NULL,
  allocation_code text NOT NULL DEFAULT '',

  -- ===== 分摊规则版本（**版本化**，改版留历史）=====
  rule_id uuid,
  rule_code text NOT NULL DEFAULT '',
  rule_version int NOT NULL DEFAULT 0,
  allocation_basis text NOT NULL DEFAULT '',
  allocation_basis_label text NOT NULL DEFAULT '',

  -- ===== 金额（**逐币种**：不跨币种相加）=====
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 来源总额（头程发运单的总成本）。
  source_total_amount numeric(24,4) NOT NULL CHECK(source_total_amount >= 0),
  -- 分摊后的合计（= 明细行之和；分摊是拆分，不缩水）。
  allocated_total_amount numeric(24,4) NOT NULL CHECK(allocated_total_amount >= 0),
  line_count int NOT NULL DEFAULT 0 CHECK(line_count >= 0),

  -- ===== 归属维度 =====
  source_shipment_code text NOT NULL DEFAULT '',
  source_carrier_name text NOT NULL DEFAULT '',
  destination_country text NOT NULL DEFAULT '',

  -- ===== 状态与时点 =====
  -- COMPUTED 已算 / LOCKED 已结账 / SUPERSEDED 已被新版本取代。
  status text NOT NULL DEFAULT '',
  status_label text NOT NULL DEFAULT '',
  computed_at timestamptz,
  cost_as_of timestamptz,
  locked_at timestamptz,
  superseded_at timestamptz,

  source_service text NOT NULL DEFAULT 'channel',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一张分摊单只有一行。
  UNIQUE(tenant_id, allocation_id)
);

CREATE INDEX reporting_logistics_analysis_allocation_fact_by_cost_as_of
  ON reporting_logistics_analysis_allocation_fact(tenant_id, cost_as_of);
CREATE INDEX reporting_logistics_analysis_allocation_fact_by_country
  ON reporting_logistics_analysis_allocation_fact(tenant_id, destination_country, cost_as_of);
CREATE INDEX reporting_logistics_analysis_allocation_fact_by_currency
  ON reporting_logistics_analysis_allocation_fact(tenant_id, currency, cost_as_of);

-- ---------------------------------------------------------------------------
-- 6. reporting_logistics_analysis_allocation_line_fact —— 分摊明细行（SKU 级）
-- ---------------------------------------------------------------------------
--
-- 一行 = **一张分摊单的一行**（channel 10.6.1.1 的分摊明细）。
--
-- 🔴 **本表是「类目维度物流成本占比」的唯一来源**：蓝图关系句
--   「**物流成本占比高的类目必须单独定价**」需要「按类目的物流成本」，
--   而头程分摊**本来就是按 SKU 行分摊的**（分摊明细带 sku_code），
--   尾程则**没有 SKU 维度**（运费落在运单上，一票可能多 SKU）。
--   ⇒ 类目维度**只覆盖头程分摊部分**，这件事必须写在口径说明里，
--     不能让下游以为那是全口径物流成本。
--
-- ⚠️ **category 是摄取那一刻的快照**：类目改了，历史报表的口径跟着变 ——
--   这与全仓其它「维度快照」一致，随行留 source_reference 以便复盘。
CREATE TABLE reporting_logistics_analysis_allocation_line_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 所属分摊单 =====
  allocation_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),

  -- ===== SKU 与类目 =====
  sku_id uuid,
  sku_code text NOT NULL DEFAULT '',
  sku_name text NOT NULL DEFAULT '',
  -- 类目（来自 catalog 的 SKU 档案；**快照**）。
  category_code text NOT NULL DEFAULT '',

  -- ===== 分摊基准与结果 =====
  basis_value numeric(24,4) NOT NULL CHECK(basis_value >= 0),
  basis_unit text NOT NULL DEFAULT '',
  ratio numeric(19,6),
  allocated_amount numeric(24,4) NOT NULL CHECK(allocated_amount >= 0),

  source_service text NOT NULL DEFAULT 'channel',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一张分摊单的同一行只有一行。
  UNIQUE(tenant_id, allocation_id, line_no)
);

-- 按 SKU 取（类目维度汇总）。
CREATE INDEX reporting_logistics_analysis_allocation_line_fact_by_sku
  ON reporting_logistics_analysis_allocation_line_fact(tenant_id, sku_code);
-- 按类目取。
CREATE INDEX reporting_logistics_analysis_allocation_line_fact_by_category
  ON reporting_logistics_analysis_allocation_line_fact(tenant_id, category_code);

-- ---------------------------------------------------------------------------
-- 7. reporting_logistics_analysis_exception_fact —— 异常件（窗口内）
-- ---------------------------------------------------------------------------
--
-- 一行 = **一个异常件**（channel 10.3.1.2 的异常件事实）。
--
-- ⚠️ **异常件占比的分母与分子必须同范围**：
--   channel 的 `/exceptions/overview` 是**累计全量**（没有时间窗），
--   拿它除以「窗口内运单数」会得到一个**没有意义**的比率。
--   ⇒ 本表按 **detected_on（发现日）** 落行，分子取窗口内的异常件，
--     分母取**同一窗口**的运单数（waybill_fact）⇒ 两者同范围。
--
-- ⚠️ **异常件是上游已判定的件**：本模块不重判「什么算异常」。
CREATE TABLE reporting_logistics_analysis_exception_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 上游锚点 =====
  case_id uuid NOT NULL,
  case_no text NOT NULL DEFAULT '',

  -- ===== 发现日（异常件占比的时间轴基准）=====
  detected_on date NOT NULL,
  detected_at timestamptz NOT NULL,

  -- ===== 分类 =====
  exception_type text NOT NULL DEFAULT '',
  exception_type_label text NOT NULL DEFAULT '',
  severity text NOT NULL DEFAULT '',
  severity_label text NOT NULL DEFAULT '',
  status text NOT NULL DEFAULT '',
  status_label text NOT NULL DEFAULT '',
  source text NOT NULL DEFAULT '',

  -- ===== 关联单据 =====
  waybill_no text NOT NULL DEFAULT '',
  carrier_code text NOT NULL DEFAULT '',
  sales_order_no text NOT NULL DEFAULT '',
  -- 是否已逾期（上游的处置 SLA 判定；本模块**只搬运**）。
  overdue boolean NOT NULL DEFAULT false,

  source_service text NOT NULL DEFAULT 'channel',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一个异常件只有一行。
  UNIQUE(tenant_id, case_id)
);

CREATE INDEX reporting_logistics_analysis_exception_fact_by_day
  ON reporting_logistics_analysis_exception_fact(tenant_id, detected_on);
CREATE INDEX reporting_logistics_analysis_exception_fact_by_type
  ON reporting_logistics_analysis_exception_fact(tenant_id, exception_type, detected_on);

-- ---------------------------------------------------------------------------
-- 8. reporting_logistics_analysis_sku_category_fact —— SKU → 类目映射
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个 SKU 的类目归属（catalog 6.1.1.1 的 SKU 档案）。
--
-- ⚠️ **本表只是「SKU → 类目」的映射，不是商品档案**：
--   catalog 拥有 SKU 主数据（SPU / SKU / 类目 / 规格），本模块**只读**。
--   本表**刻意不存价格、库存、状态** —— 那些不是本模块的口径，
--   存进来就等于复制了商品真相。
CREATE TABLE reporting_logistics_analysis_sku_category_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  sku_id uuid NOT NULL,
  sku_code text NOT NULL DEFAULT '',
  sku_name text NOT NULL DEFAULT '',
  -- 类目（catalog 的类目编码；空 = 该 SKU 没有挂类目）。
  category_code text NOT NULL DEFAULT '',

  source_service text NOT NULL DEFAULT 'catalog',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：一个 SKU 只有一行映射。
  UNIQUE(tenant_id, sku_id)
);

CREATE INDEX reporting_logistics_analysis_sku_category_fact_by_code
  ON reporting_logistics_analysis_sku_category_fact(tenant_id, sku_code);

-- ---------------------------------------------------------------------------
-- 9. reporting_logistics_analysis_rule —— 阈值规则（租户自己的口径）
-- ---------------------------------------------------------------------------
--
-- 四类规则：
--
--   | rule_type                  | 字段                                | 语义                     |
--   |----------------------------|-------------------------------------|--------------------------|
--   | ON_TIME_TARGET             | on_time_target_pct                   | 时效达成率目标（百分数） |
--   | FREIGHT_PER_ORDER_TARGET   | freight_per_order_target             | 单均运费目标（金额 / 单） |
--   | EXCEPTION_SHARE_TARGET     | exception_share_target_pct           | 异常件占比上限（百分数） |
--   | COST_TO_SALES_TARGET       | cost_to_sales_target_pct             | 物流成本占销售额比上限（百分数） |
--
-- ⚠️ **阈值不能写死在代码里**：一条 A 类目的物流成本占比 40% 可能正常，
--   一条高货值类目 40% 就是灾难 —— 阈值落成**租户级规则表**，
--   服务层只播种一份**默认**规则（可在接口上改）。
--
-- ⚠️ **单均运费目标带币种**：它是**金额**，不同币种的「目标」不是一个数。
CREATE TABLE reporting_logistics_analysis_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_type text NOT NULL CHECK(rule_type IN
    ('ON_TIME_TARGET','FREIGHT_PER_ORDER_TARGET','EXCEPTION_SHARE_TARGET','COST_TO_SALES_TARGET')),

  -- 时效达成率目标（百分数，0~100）。
  on_time_target_pct numeric(19,6),
  -- 单均运费目标（**金额**，必须带币种）。
  freight_per_order_target numeric(24,4),
  freight_per_order_currency char(3),
  -- 异常件占比上限（百分数，0~100）。
  exception_share_target_pct numeric(19,6),
  -- 物流成本占销售额比上限（百分数，0~100）。
  cost_to_sales_target_pct numeric(19,6),

  -- 最少历史期数：样本太少时谈「达标」没有意义。
  min_history_days int NOT NULL DEFAULT 1 CHECK(min_history_days >= 1),

  enabled boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_type),

  -- 🔴 **取值范围写进 CHECK**：不可能的阈值在数据库层就被挡住。
  --   * 四类规则的字段互斥（不写死「一条规则带两套阈值」）；
  --   * 单均运费目标要么两个都在、要么两个都不在（半个金额目标没意义）。
  CHECK(
    (rule_type = 'ON_TIME_TARGET'
       AND on_time_target_pct IS NOT NULL AND on_time_target_pct >= 0 AND on_time_target_pct <= 100
       AND freight_per_order_target IS NULL AND freight_per_order_currency IS NULL
       AND exception_share_target_pct IS NULL AND cost_to_sales_target_pct IS NULL)
    OR (rule_type = 'FREIGHT_PER_ORDER_TARGET'
       AND freight_per_order_target IS NOT NULL AND freight_per_order_target >= 0
       AND freight_per_order_currency IS NOT NULL AND freight_per_order_currency ~ '^[A-Z]{3}$'
       AND on_time_target_pct IS NULL AND exception_share_target_pct IS NULL
       AND cost_to_sales_target_pct IS NULL)
    OR (rule_type = 'EXCEPTION_SHARE_TARGET'
       AND exception_share_target_pct IS NOT NULL AND exception_share_target_pct >= 0
       AND exception_share_target_pct <= 100
       AND on_time_target_pct IS NULL AND freight_per_order_target IS NULL
       AND freight_per_order_currency IS NULL AND cost_to_sales_target_pct IS NULL)
    OR (rule_type = 'COST_TO_SALES_TARGET'
       AND cost_to_sales_target_pct IS NOT NULL AND cost_to_sales_target_pct >= 0
       AND cost_to_sales_target_pct <= 100
       AND on_time_target_pct IS NULL AND freight_per_order_target IS NULL
       AND freight_per_order_currency IS NULL AND exception_share_target_pct IS NULL)
  )
);

CREATE INDEX reporting_logistics_analysis_rule_by_type
  ON reporting_logistics_analysis_rule(tenant_id, rule_type, enabled);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_logistics_analysis_cost_fact',
    'reporting_logistics_analysis_channel_fact',
    'reporting_logistics_analysis_timeliness_fact',
    'reporting_logistics_analysis_waybill_fact',
    'reporting_logistics_analysis_allocation_fact',
    'reporting_logistics_analysis_allocation_line_fact',
    'reporting_logistics_analysis_exception_fact',
    'reporting_logistics_analysis_sku_category_fact',
    'reporting_logistics_analysis_rule'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_logistics_analysis_cost_fact,
  reporting_logistics_analysis_channel_fact,
  reporting_logistics_analysis_timeliness_fact,
  reporting_logistics_analysis_waybill_fact,
  reporting_logistics_analysis_allocation_fact,
  reporting_logistics_analysis_allocation_line_fact,
  reporting_logistics_analysis_exception_fact,
  reporting_logistics_analysis_sku_category_fact,
  reporting_logistics_analysis_rule TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_logistics_analysis_cost_fact,
  reporting_logistics_analysis_channel_fact,
  reporting_logistics_analysis_timeliness_fact,
  reporting_logistics_analysis_waybill_fact,
  reporting_logistics_analysis_allocation_fact,
  reporting_logistics_analysis_allocation_line_fact,
  reporting_logistics_analysis_exception_fact,
  reporting_logistics_analysis_sku_category_fact,
  reporting_logistics_analysis_rule TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **5 定价**：读 `GET /reporting/logistics-analysis/category-cost`
--     （类目维度物流成本占销售额比）做运费测算校准 ——
--     蓝图关系句：「**物流成本占比高的类目必须单独定价**，
--     统一毛利率会吃掉利润」。类目占比的**分母口径与利润侧一致**
--     （读 13.4.1.2 的 REVENUE 投影，不自算销售额）。
--   * **13.4 利润**：读 `GET /reporting/logistics-analysis/freight-per-order`
--     （单均运费）与 `/overview`（逐币种全口径成本）校准利润口径。
--
-- ⚠️ **本模块没有任何「写物流事实」的权限**：运单归 trade、渠道与时效归 channel、
--   商品档案归 catalog，本模块只给**依据**。
