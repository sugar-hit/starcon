-- ===========================================================================
-- V403 · inventory_analysis —— 13.5.1.1 库存分析
-- ===========================================================================
--
-- **本文件做什么**：给 reporting 服务（13 利润与经营分析的唯一归属服务）加
-- **四张分析投影表 + 一张阈值规则表**，让「库存分析报表」这件事**在查询期**算出来：
--
--   reporting_inventory_analysis_position    —— 库存头寸（数量 × **成本单价** = 库存金额）
--   reporting_inventory_analysis_sales_fact  —— 销售出库（**成本口径**，周转率的分子）
--   reporting_inventory_analysis_aging_fact  —— 库龄分桶 + 呆滞汇总（warehouse 7.6.1.1 的投影）
--   reporting_inventory_analysis_stocktake_fact —— 盘点历史（warehouse 7.4.2.1 的投影）
--   reporting_inventory_analysis_rule        —— 阈值（ABC 累计占比线 / 周转目标）
--
-- ---------------------------------------------------------------------------
-- 0. 蓝图原文（本迁移要兑现的全部内容）
-- ---------------------------------------------------------------------------
--
--   ### 13.5.1.1 库存分析
--   - **职责**：库存分析报表：库存金额结构、周转率、库龄分布、缺货与超储、
--             ABC 分类、呆滞占比，按仓库/类目/店铺拆解。
--   - **输入**：库存数据（7）；销售数据；盘点历史。
--   - **输出**：库存分析报表 + 图表 + 下钻。
--   - **关系**：上游 = 7 库存、9 订单；下游 = 6 采购、5 调价。
--             **周转率必须按成本口径算**，用售价算会虚高——这是库存报表最常见的错误。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 本迁移**不建库存账本**
-- ---------------------------------------------------------------------------
--
-- 库存余额的事实归 **warehouse（卷 7 库存）**所有（`inventory_balance` / `stock_bucket`）。
-- 本模块只做**分析投影**：把上游的库存头寸按天拍成快照，供报表做金额结构、周转率、
-- 库龄、ABC 与呆滞占比。
--
-- ⚠️ **再建一张「库存余额表」就会出现第二个真相**：上游改一次库存，两个表对不上，
--   而报表通常比账本更被人当真 ⇒ 那就是假账。
--
-- ⚠️ **跨服务不直连对方库**（AGENTS.md「模块边界纪律」）：本表的数据**全部**由
--   reporting 服务经**授权只读 API**（warehouse / catalog / channel / trade）拉取后落地，
--   禁止跨库 FK、跨库视图或直连对方 schema。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 周转率**必须按成本口径算**（蓝图加粗句）
-- ---------------------------------------------------------------------------
--
-- 蓝图那句加粗是本模块的命门：
--
--   | 口径             | 分子                   | 分母                     | 结论                   |
--   |------------------|------------------------|--------------------------|------------------------|
--   | ✅ **成本口径**   | 销售出库**成本**        | 库存**金额**（成本口径）  | 本模块采用             |
--   | ❌ 售价口径       | 销售出库**金额**（售价） | 库存**金额**（成本口径）  | **会虚高**（毛利率越高越虚高） |
--
-- ⇒ **库存金额** = 在手数量 × **catalog 5.2.1.2 成本构成里的单位成本**
--   （采购成本 + 包装成本 + 其它直接成本，三项都是**单位**成本）；
--   **销售出库成本** = 销量 × **同一个**单位成本。
--
-- ⚠️ **分子与分母必须用同一套成本单价**：一个用移动加权、一个用标准成本，
--   周转率就是一个没有意义的数。
--
-- ⚠️ **单位成本取不到 ⇒ 那一格「算不出来」，不是 0**：
--   `unit_cost` / `inventory_value` / `cost_amount` **可空**。
--   成本没维护的 SKU 在报表上是「—（成本未维护）」，
--   **不是 0 元**（0 元会被读成「这些货不占资金」，那比报错危险得多）。
--
-- ⚠️ **本模块不重算上游口径**：成本单价在 catalog、成本分摊在 catalog / finance，
--   本服务只做**搬运与聚合**。重算就会出现第二个真相。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 「算不出来」与「等于 0」必须分开
-- ---------------------------------------------------------------------------
--
-- 下列情形在本模块里一律是 **NULL + 一句原因**（页面上是「—（不可比）」），**绝不是 0**：
--
--   * 分母为 0（窗口里没有库存金额）⇒ 周转率算不出来；
--   * SKU 的成本单价没维护 ⇒ 库存金额算不出来；
--   * 上游没交事实（没有销售 / 没有盘点 / 没有库龄分桶）⇒ 那一段是「读不到」；
--   * 某段期间没有期初或期末快照 ⇒ 平均库存算不出来，只能退回期末口径并写明。
--
-- ---------------------------------------------------------------------------
-- 4. 多租户（RLS isolation）+ 授权
-- ---------------------------------------------------------------------------
--
-- 五张表都是租户级表：tenant_id uuid NOT NULL + ENABLE **与 FORCE** RLS
-- + CREATE POLICY isolation + GRANT reporting_app / starcon_app。
-- 与 V398 / V399 / V401 **完全同构**。
--
-- ⚠️ **零 ALTER 既有表**（V376 / V389 / V394 / V399 都是纯 CREATE TABLE）：
--   并行车道同时提交迁移时，ALTER 既有表是锁表 + 冲突的双重风险。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_inventory_analysis_position —— 库存头寸快照
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某一天 / 某个 SKU / 某个仓库」的库存头寸**与它的成本口径金额**。
--
-- ⚠️ **as_of 是快照日，不是业务日**：库存是**时点**事实，一天一张快照。
--   有了历史快照才谈得上「平均库存」（周转率的分母）。
--
-- ⚠️ **库存金额（inventory_value）可空**：成本单价取不到时它就是 NULL，
--   **不是 0** —— 见文件头第 2 / 3 节。
CREATE TABLE reporting_inventory_analysis_position (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 快照日（业务日，date；不带时区 —— 库龄与库存都是「哪一天」的事）。
  as_of date NOT NULL,

  -- ===== SKU =====
  sku_id uuid NOT NULL,
  sku_code text NOT NULL,
  sku_name text NOT NULL DEFAULT '',

  -- ===== 仓库 =====
  warehouse_id uuid NOT NULL,
  warehouse_code text NOT NULL DEFAULT '',
  warehouse_name text NOT NULL DEFAULT '',

  -- ===== 货权法人（warehouse 的登记快照）=====
  legal_entity_id uuid,
  legal_entity_code text NOT NULL DEFAULT '',

  -- ===== 类目（catalog 拥有；映射不到留空串，**不猜一个类目**）=====
  category_code text NOT NULL DEFAULT '',

  -- ===== 店铺（channel 拥有；**只在唯一映射时归属**，多店铺共用或无映射留空串）=====
  shop_code text NOT NULL DEFAULT '',
  shop_name text NOT NULL DEFAULT '',

  -- ===== 数量（numeric(19,6)；**全仓无浮点**）=====
  on_hand numeric(19,6) NOT NULL DEFAULT 0,
  reserved numeric(19,6) NOT NULL DEFAULT 0,
  available numeric(19,6) NOT NULL DEFAULT 0,

  -- ===== 超储阈值（warehouse 的阈值策略；未设阈值 ⇒ NULL，**不是 0**）=====
  max_on_hand numeric(19,6),

  -- ===== 成本口径（catalog 5.2.1.2 成本构成台账的**自有**部分）=====
  -- 单位成本 = 采购成本 + 包装成本 + 其它直接成本（三项都是**单位**成本）。
  -- 🔴 **取不到 ⇒ NULL**（成本没维护 / 币种缺失），**不是 0**。
  unit_cost numeric(19,6),
  -- 计价币种（**不跨币种相加**：仓库铁律）。
  cost_currency char(3),
  -- 成本法（MOVING_AVERAGE / STANDARD …；页面上要写明用的是哪一种）。
  cost_method text NOT NULL DEFAULT '',
  -- 库存金额 = 在手数量 × 单位成本（**成本口径**）。取不到 ⇒ NULL。
  inventory_value numeric(24,4),

  -- ===== 缺货 / 超储标记（warehouse 7.1.1.1 库存总览的判定，本模块原样搬运）=====
  zero_stock boolean NOT NULL DEFAULT false,
  overstock boolean NOT NULL DEFAULT false,
  threshold_configured boolean NOT NULL DEFAULT false,

  -- ===== 溯源 =====
  source_service text NOT NULL,
  source_reference text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一批上游事实重放**不会翻倍**（同一租户 + 同一快照日 + 同一 SKU + 同一仓库
  --   就是同一行事实，重放时覆盖而不是插入）。
  UNIQUE(tenant_id, as_of, sku_id, warehouse_id),

  -- 🔴 **一致性写进 CHECK**：金额要么是「算出来的」（单价与金额都非空且币种合法），
  --   要么是「算不出来」（三者皆空）。**不允许出现「有金额但没单价」这种半吊子状态** ——
  --   它会让「金额合计」看起来完整，其实漏了一部分。
  CHECK(
    (unit_cost IS NOT NULL
       AND cost_currency IS NOT NULL
       AND cost_currency ~ '^[A-Z]{3}$'
       AND inventory_value IS NOT NULL
       AND inventory_value = round(on_hand * unit_cost, 4))
    OR (unit_cost IS NULL AND cost_currency IS NULL AND inventory_value IS NULL)
  )
);

-- 取某个快照日的头寸（结构 / ABC / 下钻都走它）。
CREATE INDEX reporting_inventory_analysis_position_by_asof
  ON reporting_inventory_analysis_position(tenant_id, as_of);
-- 取某个 SKU 的时间序列（周转率与趋势）。
CREATE INDEX reporting_inventory_analysis_position_by_sku
  ON reporting_inventory_analysis_position(tenant_id, sku_code, as_of);
-- 按仓库 / 类目 / 店铺拆解（三个维度各自一条）。
CREATE INDEX reporting_inventory_analysis_position_by_warehouse
  ON reporting_inventory_analysis_position(tenant_id, as_of, warehouse_code);
CREATE INDEX reporting_inventory_analysis_position_by_category
  ON reporting_inventory_analysis_position(tenant_id, as_of, category_code);
CREATE INDEX reporting_inventory_analysis_position_by_shop
  ON reporting_inventory_analysis_position(tenant_id, as_of, shop_code);

-- ---------------------------------------------------------------------------
-- 2. reporting_inventory_analysis_sales_fact —— 销售出库（成本口径）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某一天 / 某个 SKU」卖掉了多少、**成本口径**是多少钱。
--
-- ⚠️ **只有销量与成本口径金额，没有售价金额**：本模块**刻意不落售价** ——
--   蓝图加粗句说「用售价算会虚高」，而一张存着售价的表迟早会被拿来算一次周转率。
--   成本口径的金额在**摄取时**由数量 × 单位成本算好，查询期不再重算。
--
-- ⚠️ **cost_amount 可空**：SKU 的成本单价没维护时，这笔出库的成本**算不出来**。
--   NULL 在分子里意味着「这一段周转率不可比」，而不是「成本为 0」。
CREATE TABLE reporting_inventory_analysis_sales_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  sold_on date NOT NULL,
  sku_id uuid NOT NULL,
  sku_code text NOT NULL,
  sku_name text NOT NULL DEFAULT '',

  -- ===== 拆解维度（**快照**，取自摄取那一刻的 catalog / channel 映射）=====
  --
  -- ⚠️ **为什么这里要带维度**：周转率的分子是**按 SKU** 的销售出库成本，
  --   而分母（库存金额）是**按 SKU × 仓库**的。若不在这里带上类目 / 店铺，
  --   查询期就得靠 join 头寸表反推维度 —— 而**卖光的 SKU 已经没有头寸行了**，
  --   join 会把那笔成本**整笔丢掉**（分子少一块，周转率偏低还不自知）。
  --
  -- ⚠️ **这是投影里的快照，不是第二个真相**：类目与店铺的**权威**在 catalog / channel，
  --   本列只是「摄取那一刻的归属」；它与 position 表的同名列来自**同一次上游拉取**，
  --   同一轮刷新内必然一致。
  category_code text NOT NULL DEFAULT '',
  shop_code text NOT NULL DEFAULT '',
  shop_name text NOT NULL DEFAULT '',

  -- 销量（numeric(19,6)）。
  quantity numeric(19,6) NOT NULL DEFAULT 0,

  -- 🔴 **成本口径**：单位成本 / 计价币种 / 出库成本金额。取不到 ⇒ 三个皆 NULL。
  unit_cost numeric(19,6),
  cost_currency char(3),
  cost_amount numeric(24,4),

  source_service text NOT NULL,
  source_reference text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 幂等键：同一天、同一 SKU、同一上游订单行 ⇒ 只有一条。
  UNIQUE(tenant_id, sold_on, sku_id, source_reference),

  -- 与 position 表同一套一致性约束（金额要么算得出来，要么明确算不出来）。
  CHECK(
    (unit_cost IS NOT NULL
       AND cost_currency IS NOT NULL
       AND cost_currency ~ '^[A-Z]{3}$'
       AND cost_amount IS NOT NULL
       AND cost_amount = round(quantity * unit_cost, 4))
    OR (unit_cost IS NULL AND cost_currency IS NULL AND cost_amount IS NULL)
  )
);

-- 取窗口内的销售出库（周转率的分子）。
CREATE INDEX reporting_inventory_analysis_sales_fact_by_day
  ON reporting_inventory_analysis_sales_fact(tenant_id, sold_on);
CREATE INDEX reporting_inventory_analysis_sales_fact_by_sku
  ON reporting_inventory_analysis_sales_fact(tenant_id, sku_code, sold_on);

-- ---------------------------------------------------------------------------
-- 3. reporting_inventory_analysis_aging_fact —— 库龄分桶 + 呆滞汇总
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个快照日 / 某个范围（租户或仓库）/ 某个指标 / 某个取值」的一个聚合值。
--
-- ⚠️ **库龄与呆滞的判定归 warehouse（7.6.1.1）**：它有批次收货日、有库龄阈值策略、
--   有「该 SKU 配的滞销线 vs 兜底默认值」。本模块**只搬运它的判定结果**，
--   **不重算库龄、不重算呆滞档位** —— 重算就会与 warehouse 的处置清单对不上。
--
-- ⚠️ **metric_type 两类，粒度不同，所以用判别列而不是拆两张表**：
--   * AGING_BUCKET —— 库龄分桶（AGE_0_30 / AGE_31_60 / AGE_61_90 / AGE_90_PLUS / NO_INBOUND_DATE）；
--   * SLOW_MOVING —— 呆滞汇总（OBSOLETE 呆滞 / SLOW_MOVING 滞销 / OBSOLETE_AND_UP 呆滞及以上）。
--   两类都**不含金额**（warehouse 的库存事实层没有成本列）⇒ 金额由本模块用
--   position 表的成本口径**另行聚合**，**不与这里的数量混算**。
CREATE TABLE reporting_inventory_analysis_aging_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  as_of date NOT NULL,

  -- 范围：TENANT（全租户）/ WAREHOUSE（单个仓库）。
  scope_type text NOT NULL CHECK(scope_type IN ('TENANT','WAREHOUSE')),
  -- 范围键：WAREHOUSE 时是仓库 id；TENANT 时是空串。
  scope_key text NOT NULL DEFAULT '',
  scope_label text NOT NULL DEFAULT '',

  -- 指标类型：库龄分桶 / 呆滞汇总。
  metric_type text NOT NULL CHECK(metric_type IN ('AGING_BUCKET','SLOW_MOVING')),
  -- 指标取值（AGE_0_30 … / OBSOLETE …）。
  metric_key text NOT NULL,
  metric_label text NOT NULL DEFAULT '',

  -- 库龄桶的**天数边界**（上游的闭区间边界；非库龄桶为 NULL）。
  -- ⚠️ 上游的「90+」真实下界是 91（90 天归 61-90 桶）——
  --   边界必须**原样搬过来**，否则页面上会画出一个与上游对不上的分桶。
  lower_days int,
  upper_days int,

  -- 命中的 SKU 数 / bucket 行数（**各桶的 sku 数不可直接相加**：同一 SKU 可横跨多桶）。
  sku_count bigint NOT NULL DEFAULT 0,
  bucket_count bigint NOT NULL DEFAULT 0,

  -- 数量口径：在手 / 预占 / 可用。
  on_hand numeric(19,6) NOT NULL DEFAULT 0,
  reserved numeric(19,6) NOT NULL DEFAULT 0,
  available numeric(19,6) NOT NULL DEFAULT 0,

  -- 最老库龄天数（呆滞汇总用；没有则 NULL）。
  oldest_aging_days int,

  source_service text NOT NULL DEFAULT 'warehouse',
  source_reference text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 幂等键：同一天的同一个指标值只有一行。
  UNIQUE(tenant_id, as_of, scope_type, scope_key, metric_type, metric_key)
);

CREATE INDEX reporting_inventory_analysis_aging_fact_by_asof
  ON reporting_inventory_analysis_aging_fact(tenant_id, as_of);

-- ---------------------------------------------------------------------------
-- 4. reporting_inventory_analysis_stocktake_fact —— 盘点历史
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次盘点单（warehouse 7.4.2.1）。
--
-- ⚠️ **盘点历史是「准确率」的原料**：账面行数 / 已盘行数 / 差异行数三列都在上游，
--   本模块**只搬运**，不重算差异（重算就要复制盘点的三列口径，那是业务事实）。
--
-- ⚠️ **盘点差异不是库存余额**：`stocktake_variance_adjustment` 那条链（差异 → 调整单 → 审批
--   → 改余额）归 warehouse；本模块**只读不改**。
CREATE TABLE reporting_inventory_analysis_stocktake_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  stocktake_id uuid NOT NULL,
  code text NOT NULL,
  scope_type text NOT NULL,
  scope_label text NOT NULL DEFAULT '',
  status text NOT NULL,

  -- 冻结时刻（timestamptz）。盘点单的账面数是**冻结那一刻**的数。
  frozen_at timestamptz,

  -- 三列计数：账面行数 / 已录实盘行数 / 有差异行数。
  line_count bigint NOT NULL DEFAULT 0,
  counted_line_count bigint NOT NULL DEFAULT 0,
  variance_line_count bigint NOT NULL DEFAULT 0,

  -- 账面合计（冻结值合计；**它不是当前库存余额**）。
  book_total numeric(19,6) NOT NULL DEFAULT 0,

  source_service text NOT NULL DEFAULT 'warehouse',
  source_reference text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 幂等键：同一张盘点单只有一行（重放不翻倍）。
  UNIQUE(tenant_id, stocktake_id),

  -- 三列计数必须自洽：已盘行数 ≤ 账面行数；差异行数 ≤ 已盘行数。
  CHECK(counted_line_count <= line_count),
  CHECK(variance_line_count <= counted_line_count)
);

CREATE INDEX reporting_inventory_analysis_stocktake_fact_by_frozen
  ON reporting_inventory_analysis_stocktake_fact(tenant_id, frozen_at DESC);

-- ---------------------------------------------------------------------------
-- 5. reporting_inventory_analysis_rule —— 阈值规则（租户自己的口径）
-- ---------------------------------------------------------------------------
--
-- 两类规则：
--
--   | rule_type         | 字段                       | 语义                                     |
--   |-------------------|----------------------------|------------------------------------------|
--   | ABC_CLASS         | a_cum_pct / b_cum_pct      | ABC 分类的**累计金额占比**线（百分数）    |
--   | TURNOVER_TARGET   | target_turnover            | 目标周转率（次）                          |
--
-- ⚠️ **阈值不能写死在代码里**：不同品类、不同店铺的库存结构差一个数量级，
--   写死的 80/95 对上一条平稳的品类和一条季节性强烈的品类含义完全不同。
--   ⇒ 阈值落成**租户级规则表**；服务层只播种一份**默认**规则（可在接口上改）。
CREATE TABLE reporting_inventory_analysis_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_type text NOT NULL CHECK(rule_type IN ('ABC_CLASS','TURNOVER_TARGET')),

  -- ABC：A 类累计金额占比上限（百分数，80 = 前 80% 算 A 类）。
  a_cum_pct numeric(19,6),
  -- ABC：B 类累计金额占比上限（百分数，95 = 前 95% 算 B 类，其余是 C 类）。
  b_cum_pct numeric(19,6),

  -- 周转目标（次 / 期间）。
  target_turnover numeric(19,6),

  -- 最少历史期数：周转率要够长的历史期才谈得上「达标」。
  min_history_days int NOT NULL DEFAULT 1 CHECK(min_history_days >= 1),

  enabled boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_type),

  -- 🔴 **取值范围写进 CHECK**：不可能的阈值在数据库层就被挡住。
  --   * ABC 必须 0 < a_cum_pct < b_cum_pct <= 100（否则「A 类」会吞掉「B 类」）；
  --   * 周转目标必须 >= 0。
  --   * **两类规则的字段互斥**：不写死「一条规则带两套阈值」。
  CHECK(
    (rule_type = 'ABC_CLASS'
       AND a_cum_pct IS NOT NULL AND b_cum_pct IS NOT NULL
       AND a_cum_pct > 0 AND b_cum_pct > a_cum_pct AND b_cum_pct <= 100
       AND target_turnover IS NULL)
    OR (rule_type = 'TURNOVER_TARGET'
       AND target_turnover IS NOT NULL AND target_turnover >= 0
       AND a_cum_pct IS NULL AND b_cum_pct IS NULL)
  )
);

CREATE INDEX reporting_inventory_analysis_rule_by_type
  ON reporting_inventory_analysis_rule(tenant_id, rule_type, enabled);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_inventory_analysis_position',
    'reporting_inventory_analysis_sales_fact',
    'reporting_inventory_analysis_aging_fact',
    'reporting_inventory_analysis_stocktake_fact',
    'reporting_inventory_analysis_rule'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_inventory_analysis_position,
  reporting_inventory_analysis_sales_fact,
  reporting_inventory_analysis_aging_fact,
  reporting_inventory_analysis_stocktake_fact,
  reporting_inventory_analysis_rule TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_inventory_analysis_position,
  reporting_inventory_analysis_sales_fact,
  reporting_inventory_analysis_aging_fact,
  reporting_inventory_analysis_stocktake_fact,
  reporting_inventory_analysis_rule TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **6 采购**：读 `GET /reporting/inventory-analysis/stock-status`（缺货与超储清单）
--     与 `GET /reporting/inventory-analysis/abc`（ABC 分类）决定补什么货；
--   * **5 调价**：读 `GET /reporting/inventory-analysis/aging`（库龄与呆滞）决定清什么货；
--   * **13.5.2.1 采购分析 / 13.5.3.1 物流分析**（都未交付）：本模块**只读**这五张投影表，
--     **不写**它们的表。
--
-- ⚠️ **本模块没有任何「写库存」的权限**：库存事实归 warehouse（7 库存），
--   采购下单归 6 采购，调价归 5 调价。本模块只给**依据**。
