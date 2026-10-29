-- ===========================================================================
-- V405 · procurement_analysis —— 13.5.2.1 采购分析
-- ===========================================================================
--
-- **本文件做什么**：给 reporting 服务（13 利润与经营分析的唯一归属服务）加
-- **三张分析投影表 + 一张阈值规则表**，让「采购分析报表」这件事**在查询期**算出来：
--
--   reporting_procurement_analysis_purchase_fact  —— 采购到货流水（金额 / 数量 / 单价 / 准时判定）
--   reporting_procurement_analysis_delivery_fact  —— 到货记录（**只用于采购周期**，**不含金额**）
--   reporting_procurement_analysis_supplier_fact  —— 供应商档案 + 供应商评级
--   reporting_procurement_analysis_rule            —— 阈值（集中度风险线 / 准时率目标 / 采购周期目标）
--
-- ---------------------------------------------------------------------------
-- 0. 蓝图原文（本迁移要兑现的全部内容）
-- ---------------------------------------------------------------------------
--
--   ### 13.5.2.1 采购分析
--   - **职责**：采购分析：采购金额趋势与占比、供应商集中度、价格波动、到货准时率、
--             采购周期、采购成本占销售额比。
--   - **输入**：采购数据（6）；供应商评级。
--   - **输出**：采购报表 + 趋势 + 供应商对比。
--   - **关系**：上游 = 6 采购与供应；下游 = 13.4 利润。
--             **供应商集中度是供应链风险指标**，单一供应商占比过高会在断供时直接击穿业务。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 本迁移**不建采购账本**
-- ---------------------------------------------------------------------------
--
-- 采购事实归 **trade（卷 6 采购与供应）**所有（`purchase_analytics_fact` /
-- `purchase_order` / `purchase_arrival_tracking`），供应商主数据与采购侧档案归
-- **catalog**（`party` / `party_role` / `vendor_supply_profile` / `vendor_delivery_record`）。
-- 本模块只做**分析投影**：把上游的到货流水与供应商档案拍成本服务的快照，供报表做
-- 金额趋势、供应商集中度、价格波动、到货准时率、采购周期与成本占销售额比。
--
-- ⚠️ **再建一张「采购金额表」就会出现第二个真相**：上游改一次采购，两个表对不上，
--   而报表通常比账本更被人当真 ⇒ 那就是假账。
--
-- ⚠️ **跨服务不直连对方库**（AGENTS.md「模块边界纪律」）：本表的数据**全部**由
--   reporting 服务经**授权只读 API**（trade / catalog）拉取后落地，
--   禁止跨库 FK、跨库视图或直连对方 schema。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 两套金额口径必须**互斥**，否则同一笔采购会被算两次
-- ---------------------------------------------------------------------------
--
-- 上游有两处带采购金额的事实，语义不同：
--
--   | 事实源                              | 粒度               | 本模块用它做什么         |
--   |-------------------------------------|--------------------|--------------------------|
--   | trade 6.5.2.1 采购分析事实台账       | **每一次到货一行** | **全部金额口径**（金额趋势、集中度、价格波动、成本占销售额比） |
--   | catalog 6.1.1.1 供应商到货记录       | 每一次到货一行     | **只算采购周期**（到货日 − 下单日） |
--
-- ⚠️ **delivery_fact 表刻意**不存金额 / 数量 / 币种：结构上杜绝「两处金额都被加进合计」
--   （那会把采购金额翻一倍，而报表不会报错）。采购周期是**时长**，不是金额，
--   它与金额口径**不冲突**，所以可以并存。
--
-- ⚠️ **准时率一律取 trade 6.3.2.2 的准时判定**（`on_time_status`），
--   **不重算**：准时与否是业务事实（承诺日 vs 实际到货日），重算就与上游的处置清单对不上。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 「算不出来」与「等于 0」必须分开
-- ---------------------------------------------------------------------------
--
-- 下列情形在本模块里一律是 **NULL + 一句原因**（页面上是「—（不可比）」），**绝不是 0**：
--
--   * 分母为 0（窗口里没有采购金额 / 没有销售额）⇒ 占比、成本占比算不出来；
--   * 上游没交事实（trade 的采购事实台账没同步）⇒ 那一段是「读不到」；
--   * 供应商没有到货记录 ⇒ 采购周期不可比（**不是 0 天**）；
--   * 供应商没有评级 ⇒ 评级那一列是「—（未评级）」，**不是 0 分**。
--
-- ---------------------------------------------------------------------------
-- 4. 🔴 多币种绝不相加
-- ---------------------------------------------------------------------------
--
-- 本服务**不自行折算币种**（没有汇率台账；trade 的事实行虽带折算快照，但那是上游口径）。
-- ⇒ 每一行带自己的币种；跨币种的「总计」**不存在**（单币种时才给）。
-- 成本占销售额比**逐币种**计算：某币种的采购额 ÷ **同币种**的销售额。
--
-- ---------------------------------------------------------------------------
-- 5. 多租户（RLS isolation）+ 授权
-- ---------------------------------------------------------------------------
--
-- 四张表都是租户级表：tenant_id uuid NOT NULL + ENABLE **与 FORCE** RLS
-- + CREATE POLICY isolation + GRANT reporting_app / starcon_app。
-- 与 V398 / V399 / V401 / V403 **完全同构**。
--
-- ⚠️ **零 ALTER 既有表**（V376 / V398 / V399 / V403 都是纯 CREATE TABLE）：
--   并行车道同时提交迁移时，ALTER 既有表是锁表 + 冲突的双重风险。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_procurement_analysis_purchase_fact —— 采购到货流水（金额口径）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「一次到货流水」（trade 6.5.2.1 `purchase_analytics_fact` 中
-- `fact_kind = 'COST'` 且 `source_type = 'ARRIVAL_RECEIPT'` 的那一行）。
--
-- ⚠️ **分批到货按流水累加，不压成「一张采购单一行」**：卷 06 硬约束
--   「采购收货是分批、可有差异的」—— 压成一行会让金额看起来对不上批次。
--
-- ⚠️ **occurred_on 用上游已经算好的业务日**（trade 的 `occurred_date`）：
--   上游已按租户所在时区换算，本服务**不再换算一次**（换第二次就会偏一天）。
--   occurred_at 保留原始时刻（timestamptz），供下钻回上游逐条核对。
CREATE TABLE reporting_procurement_analysis_purchase_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 时间轴 =====
  -- 业务日历日（上游已按租户时区换算好的 date）。
  occurred_on date NOT NULL,
  -- 原始发生时刻（timestamptz，**上游原样搬运**）。
  occurred_at timestamptz NOT NULL,

  -- ===== 上游事实键（幂等的另一半）=====
  -- trade 的 `fact_key`（来源 + 来源单号 + 来源行号）。同一批上游事实重放**不会翻倍**。
  source_fact_key text NOT NULL CHECK(btrim(source_fact_key) <> ''),
  -- 上游单号（采购单号 / 到货跟踪号），供下钻回原单。
  source_doc_no text NOT NULL DEFAULT '',
  source_ref_no text NOT NULL DEFAULT '',

  -- ===== 供应商（catalog 拥有档案，本表只存引用 + 快照）=====
  -- ⚠️ supplier_id 就是 catalog 的 `party_role.id`（供应商**角色** id，不是主体 id）：
  --   trade 的 `supplier_ref_id` 与它**逐字同源**（trade 侧用同一个 id 去 catalog 反查主体），
  --   所以本表可以直接按 id 与 catalog 的供应商档案对齐，**不需要按名称模糊匹配**。
  supplier_id uuid,
  supplier_code text NOT NULL DEFAULT '',
  supplier_name text NOT NULL DEFAULT '',

  -- ===== SKU =====
  sku_id uuid,
  sku_code text NOT NULL DEFAULT '',
  sku_name text NOT NULL DEFAULT '',

  -- ===== 拆解维度（**快照**，取自摄取那一刻上游给的归属）=====
  -- 类目来自 catalog 的 SKU 主数据、店铺来自大促日历（上游快照）。
  category_code text NOT NULL DEFAULT '',
  category_name text NOT NULL DEFAULT '',
  shop_code text NOT NULL DEFAULT '',
  warehouse_code text NOT NULL DEFAULT '',

  -- ===== 数量与金额（全仓无浮点）=====
  -- 数量 numeric(19,6)；单价 numeric(19,4)；金额 numeric(24,4)。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  unit_price numeric(19,4),
  amount numeric(24,4),
  -- 计价币种（**不跨币种相加**：仓库铁律）。
  currency char(3),

  -- ===== 到货准时（**判定归 trade 6.3.2.2**，本模块只搬运，不重算）=====
  -- ON_TIME 准时 / LATE 逾期 / PENDING 未到货 / NOT_APPLICABLE 不适用。
  on_time_status text NOT NULL DEFAULT 'NOT_APPLICABLE' CHECK(on_time_status IN
    ('ON_TIME','LATE','PENDING','NOT_APPLICABLE')),
  -- 承诺到货日（上游带过来的；没有 ⇒ NULL，**不是 0**）。
  promised_arrival_date date,

  source_service text NOT NULL DEFAULT 'trade',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一批上游事实重放**不会翻倍**。
  UNIQUE(tenant_id, source_fact_key),

  -- 🔴 **一致性写进 CHECK**：金额要么带币种、要么两者皆空 —— 不允许「有金额但没币种」
  --   （那会让金额在跨币种统计里被悄悄丢掉），也不允许「有币种但金额为空且没说为什么」。
  CHECK(
    (amount IS NOT NULL AND currency IS NOT NULL AND currency ~ '^[A-Z]{3}$')
    OR (amount IS NULL AND currency IS NULL)
  ),
  -- 数量不得为负。
  CHECK(quantity >= 0),
  -- 单价与金额不得为负。
  CHECK(unit_price IS NULL OR unit_price >= 0),
  CHECK(amount IS NULL OR amount >= 0),
  -- 准时 / 逾期判定必须有承诺日可比（否则「准时」是拿什么比的？）。
  CHECK(on_time_status <> 'ON_TIME' OR promised_arrival_date IS NOT NULL),
  CHECK(on_time_status <> 'LATE' OR promised_arrival_date IS NOT NULL)
);

-- 取窗口内的采购流水（趋势 / 集中度 / 价格波动都走它）。
CREATE INDEX reporting_procurement_analysis_purchase_fact_by_day
  ON reporting_procurement_analysis_purchase_fact(tenant_id, occurred_on);
-- 按供应商取（集中度 / 供应商对比）。
CREATE INDEX reporting_procurement_analysis_purchase_fact_by_supplier
  ON reporting_procurement_analysis_purchase_fact(tenant_id, supplier_id, occurred_on);
-- 按 SKU 取时间序列（价格波动）。
CREATE INDEX reporting_procurement_analysis_purchase_fact_by_sku
  ON reporting_procurement_analysis_purchase_fact(tenant_id, sku_code, occurred_on);
-- 按类目拆解（采购金额占比）。
CREATE INDEX reporting_procurement_analysis_purchase_fact_by_category
  ON reporting_procurement_analysis_purchase_fact(tenant_id, occurred_on, category_code);
-- 按店铺拆解（采购金额占比）。
CREATE INDEX reporting_procurement_analysis_purchase_fact_by_shop
  ON reporting_procurement_analysis_purchase_fact(tenant_id, occurred_on, shop_code);

-- ---------------------------------------------------------------------------
-- 2. reporting_procurement_analysis_delivery_fact —— 到货记录（**只用于采购周期**）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条 catalog 6.1.1.1 的供应商到货记录。
--
-- 🔴 **本表刻意没有金额 / 数量 / 币种三列**：本模块的**全部金额口径**都取自
--   trade 的采购事实台账，而 catalog 的到货记录**也可能带金额**。
--   两处都存金额，就一定会有人把两处都加进合计 ⇒ **采购金额翻一倍，而报表不会报错**。
--   把金额从这张表里拿掉，是**用表结构杜绝**这件事发生，而不是靠注释提醒。
--
-- ⚠️ **采购周期 = 到货日 − 下单日**：这是全仓**唯一**同时有「下单日」与「到货日」
--   的事实源（trade 的事实台账只有到货时刻与承诺到货日，没有下单日）。
--   「承诺到货日 − 下单日」是**承诺交期**，不是**实际采购周期** —— 两者不能混。
--
-- ⚠️ **ordered_on 用「ordered_at 在本次刷新所用时区下的日历日」**：
--   到货日与下单日必须落在**同一个时区**下做差，否则会出现 0 天或负数天。
--   本表把该时区**随行存下来**（timezone 列），查询期不再换算 ⇒ 报表可复盘。
CREATE TABLE reporting_procurement_analysis_delivery_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 上游到货记录 id（幂等键）。
  delivery_id uuid NOT NULL,
  delivery_ref text NOT NULL DEFAULT '',

  -- ===== 供应商 =====
  supplier_id uuid NOT NULL,
  supplier_code text NOT NULL DEFAULT '',
  supplier_name text NOT NULL DEFAULT '',

  -- ===== SKU =====
  sku_id uuid,
  sku_code text NOT NULL DEFAULT '',
  sku_name text NOT NULL DEFAULT '',

  -- ===== 时间轴 =====
  -- 下单时刻（timestamptz，**上游原样搬运**）。
  ordered_at timestamptz NOT NULL,
  -- 下单日（**本次刷新所用时区**下的日历日；与 received_date 同口径才能做差）。
  ordered_on date NOT NULL,
  -- 承诺到货日（上游给的）。
  due_date date NOT NULL,
  -- 实际到货日（上游给的）。
  received_date date NOT NULL,
  -- 采购周期（天）= received_date − ordered_on。
  cycle_days int NOT NULL CHECK(cycle_days >= 0),

  -- 本行 ordered_on 换算所用的时区（**随行留证**，报表可复盘）。
  timezone text NOT NULL DEFAULT 'UTC',

  -- 上游已作废的记录（作废的行不计入任何指标，但**留在库里**可追溯）。
  is_void boolean NOT NULL DEFAULT false,

  source_service text NOT NULL DEFAULT 'catalog',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一条到货记录只有一行（重放不翻倍）。
  UNIQUE(tenant_id, delivery_id),

  -- 到货日不得早于下单日（负周期是脏数据，宁可让 CHECK 挡住也别让它进报表）。
  CHECK(received_date >= ordered_on),
  -- 承诺日与实际到货日都在（上游两条都是必填）。
  CHECK(due_date IS NOT NULL AND received_date IS NOT NULL)
);

-- 按到货日取窗口（采购周期的时间轴基准是**到货日**，不是下单日）。
CREATE INDEX reporting_procurement_analysis_delivery_fact_by_received
  ON reporting_procurement_analysis_delivery_fact(tenant_id, received_date);
-- 按供应商取（供应商对比里的周期列）。
CREATE INDEX reporting_procurement_analysis_delivery_fact_by_supplier
  ON reporting_procurement_analysis_delivery_fact(tenant_id, supplier_id, received_date);

-- ---------------------------------------------------------------------------
-- 3. reporting_procurement_analysis_supplier_fact —— 供应商档案 + 供应商评级
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个供应商（catalog 6.1.1.1 供应商列表）**加上**它最新一期的评级
-- （trade 6.1.1.3 供应商评级）。
--
-- ⚠️ **评级是「最新一期」的快照，不是历史**：同一供应商只有一行，
--   新一期的评级覆盖旧值。理由：13.5.2.1 要的是「供应商对比」，
--   而对比要看**当下**这家供应商是什么水平，不是它的评级史。
--   评级历史仍归 6.1.1.3 自己（那是它的写入所有权，本服务不复制）。
--
-- ⚠️ **评级列可空**：没有评级 ⇒ 那一列是「—（未评级）」，**不是 0 分**。
--   把「没评过」写成 0 分，会让排名把没评过的供应商排在最后 ——
--   那等于替上游断言「这家很差」。
CREATE TABLE reporting_procurement_analysis_supplier_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 供应商**角色** id（catalog 的 `party_role.id`；与 trade 的 `supplier_ref_id` 同源）。
  supplier_id uuid NOT NULL,
  supplier_code text NOT NULL DEFAULT '',
  supplier_name text NOT NULL DEFAULT '',

  -- ===== 档案快照（catalog 6.1.1.1 / 3.6.1.x，全部**只读**）=====
  party_status text NOT NULL DEFAULT '',
  cooperation_level text NOT NULL DEFAULT '',
  -- 采购侧合作状态（catalog 6.1.1.1 自有）。
  supply_status text NOT NULL DEFAULT '',
  country_code text NOT NULL DEFAULT '',

  -- ===== 评级快照（trade 6.1.1.3 供应商评级）=====
  -- 最终分（= 客观分 + 人工调整量；上游 DB 层 CHECK 钉死）。没有评级 ⇒ NULL。
  rating_score numeric(19,4),
  -- 评级档位（A / B / C …；上游词表）。
  rating_grade text NOT NULL DEFAULT '',
  -- 评级周期（起 / 止）。
  rating_period_start date,
  rating_period_end date,
  -- 五个维度的得分（都可空 = 这个周期没有可算的依据）。
  rating_delivery_score numeric(19,4),
  rating_quality_score numeric(19,4),
  rating_price_score numeric(19,4),
  rating_response_score numeric(19,4),
  rating_service_score numeric(19,4),
  -- 评级计算时刻（timestamptz）。
  rating_computed_at timestamptz,

  source_service text NOT NULL DEFAULT 'catalog',
  source_reference text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：一个供应商只有一行档案（重放不翻倍）。
  UNIQUE(tenant_id, supplier_id),

  -- 评级分不得为负。
  CHECK(rating_score IS NULL OR rating_score >= 0),
  -- 评级周期要么两个都在，要么两个都不在（半个周期是「算不出来」，不是「没有评级」）。
  CHECK(
    (rating_period_start IS NOT NULL AND rating_period_end IS NOT NULL)
    OR (rating_period_start IS NULL AND rating_period_end IS NULL)
  ),
  -- 周期起不得晚于周期止。
  CHECK(rating_period_start IS NULL OR rating_period_end IS NULL OR rating_period_end >= rating_period_start)
);

-- 按评级分排序（供应商对比里「评级由低到高」）。
CREATE INDEX reporting_procurement_analysis_supplier_fact_by_rating
  ON reporting_procurement_analysis_supplier_fact(tenant_id, rating_score);

-- ---------------------------------------------------------------------------
-- 4. reporting_procurement_analysis_rule —— 阈值规则（租户自己的口径）
-- ---------------------------------------------------------------------------
--
-- 三类规则：
--
--   | rule_type              | 字段                                        | 语义                       |
--   |------------------------|---------------------------------------------|----------------------------|
--   | CONCENTRATION_RISK     | concentration_top_n / concentration_top_share_pct / single_supplier_share_pct | 集中度风险线（单一供应商 / 前 N 家合计） |
--   | ON_TIME_TARGET         | on_time_target_pct                         | 准时率目标（百分数）       |
--   | PURCHASE_CYCLE_TARGET  | purchase_cycle_target_days                  | 采购周期目标（天）         |
--
-- ⚠️ **阈值不能写死在代码里**：不同的采购结构差一个数量级，
--   写死的 40% 对「三五个供应商」和「上百个供应商」含义完全不同。
--   ⇒ 阈值落成**租户级规则表**；服务层只播种一份**默认**规则（可在接口上改）。
--
-- ⚠️ **蓝图加粗句「单一供应商占比过高会在断供时直接击穿业务」**：
--   集中度规则不是装饰 —— 它是本模块唯一能把「报表上的一个百分比」翻译成
--   「断供时业务会不会被打穿」的判据。
CREATE TABLE reporting_procurement_analysis_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_type text NOT NULL CHECK(rule_type IN
    ('CONCENTRATION_RISK','ON_TIME_TARGET','PURCHASE_CYCLE_TARGET')),

  -- 集中度：参与判定的 N（前 N 家供应商）。
  concentration_top_n int,
  -- 集中度：**前 N 家合计**占比阈值（百分数，0~100）⇒ 达到即至少「中风险」。
  concentration_top_share_pct numeric(19,6),
  -- 集中度：**单一供应商**占比阈值（百分数，0~100）⇒ 达到即「高风险」。
  -- ⚠️ 这一条对应蓝图加粗句「**单一供应商**占比过高会在断供时直接击穿业务」——
  --   它与「前 N 家合计偏集中」是**两种不同的风险**，阈值必须分开可配。
  single_supplier_share_pct numeric(19,6),

  -- 准时率目标（百分数，0~100）。
  on_time_target_pct numeric(19,6),

  -- 采购周期目标（天，>= 0）。
  purchase_cycle_target_days numeric(19,6),

  -- 最少历史期数：样本太少时谈「达标」没有意义。
  min_history_days int NOT NULL DEFAULT 1 CHECK(min_history_days >= 1),

  enabled boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_type),

  -- 🔴 **取值范围写进 CHECK**：不可能的阈值在数据库层就被挡住。
  --   * 两类规则的字段互斥：不写死「一条规则带两套阈值」；
  --   * 集中度：top_n >= 1、占比在 (0, 100]；
  --   * 准时率目标在 [0, 100]；
  --   * 采购周期目标 >= 0。
  CHECK(
    (rule_type = 'CONCENTRATION_RISK'
       AND concentration_top_n IS NOT NULL AND concentration_top_n >= 1
       AND concentration_top_share_pct IS NOT NULL
       AND concentration_top_share_pct > 0 AND concentration_top_share_pct <= 100
       AND single_supplier_share_pct IS NOT NULL
       AND single_supplier_share_pct > 0 AND single_supplier_share_pct <= 100
       AND on_time_target_pct IS NULL AND purchase_cycle_target_days IS NULL)
    OR (rule_type = 'ON_TIME_TARGET'
       AND on_time_target_pct IS NOT NULL AND on_time_target_pct >= 0 AND on_time_target_pct <= 100
       AND concentration_top_n IS NULL AND concentration_top_share_pct IS NULL
       AND single_supplier_share_pct IS NULL AND purchase_cycle_target_days IS NULL)
    OR (rule_type = 'PURCHASE_CYCLE_TARGET'
       AND purchase_cycle_target_days IS NOT NULL AND purchase_cycle_target_days >= 0
       AND concentration_top_n IS NULL AND concentration_top_share_pct IS NULL
       AND single_supplier_share_pct IS NULL AND on_time_target_pct IS NULL)
  )
);

CREATE INDEX reporting_procurement_analysis_rule_by_type
  ON reporting_procurement_analysis_rule(tenant_id, rule_type, enabled);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_procurement_analysis_purchase_fact',
    'reporting_procurement_analysis_delivery_fact',
    'reporting_procurement_analysis_supplier_fact',
    'reporting_procurement_analysis_rule'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_procurement_analysis_purchase_fact,
  reporting_procurement_analysis_delivery_fact,
  reporting_procurement_analysis_supplier_fact,
  reporting_procurement_analysis_rule TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_procurement_analysis_purchase_fact,
  reporting_procurement_analysis_delivery_fact,
  reporting_procurement_analysis_supplier_fact,
  reporting_procurement_analysis_rule TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.4 利润**：读 `GET /reporting/procurement-analysis/cost-to-sales`
--     （采购成本占销售额比）判断利润健康度（蓝图 6.5.2.1 的关系句：
--     「采购成本占比是利润健康度的先行指标」）；
--   * **13.5.1.1 库存分析**：读 `GET /reporting/procurement-analysis/suppliers`
--     的供应商对比，做补货与备选供应商判断。
--
-- ⚠️ **本模块没有任何「写采购事实」的权限**：采购下单归 6 采购，
--   供应商档案归 catalog，本模块只给**依据**。
