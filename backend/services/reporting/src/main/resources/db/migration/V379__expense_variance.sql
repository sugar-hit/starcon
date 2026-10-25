-- ===========================================================================
-- V379 · expense_variance —— 13.4.2.2 费用差异（预计费用 vs 实际费用）
-- ===========================================================================
--
-- **本文件做什么**：给 reporting 服务（13 利润与经营分析的唯一归属服务）加**两张投影事实表**，
-- 让「预计费用」与「实际费用」**各自独立成事实**，差异在**查询期**算出来。
--
-- ---------------------------------------------------------------------------
-- 0. 蓝图原文（本迁移要兑现的全部内容）
-- ---------------------------------------------------------------------------
--
--   ### 13.4.2.2 费用差异
--   - **职责**：对比**预计费用与实际费用**的差异：平台费、物流费、广告费、头程分摊，
--             按 SKU 与期间展示差异率，定位高估/低估的系统性偏差。
--   - **输入**：测算费用（5.2.3）；实际费用（12.3.1.2）；时间范围。
--   - **输出**：差异表 + 偏差趋势 + 规则调优建议。
--   - **关系**：上游 = 5.2.3 费用规则、12.3.1.2；下游 = 5.2.3（规则校准）。
--             **持续为负的差异说明费用规则配错了**，长期不校准会让所有利润测算失准。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 为什么是**两张表**而不是一张表两个金额列
-- ---------------------------------------------------------------------------
--
-- 仓库既有先例（V226 平台费、V232 物流头程费）已经把这件事写在脸上了：
-- 「**不能合并成一个字段**。把『规则算出来的数』和『平台实收的数』写进同一列，
--   就再也答不出『这个月是规则定错了，还是平台多扣了』」。
--
-- ⇒ 本迁移把「预计」与「实际」拆成两张**互不相干**的投影事实表：
--
--   reporting_expense_estimate_fact —— 预计费用（**规则测算**出来的那一侧）
--   reporting_expense_actual_fact   —— 实际费用（**真的发生**的那一侧）
--
-- 差异（差异额 / 差异率）在**查询期**由服务层算，**不落库**：
-- 落库的那个数会变成「第三个真相」，而规则一改它就过期了。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 「算不出来」与「等于 0」必须分开
-- ---------------------------------------------------------------------------
--
-- 预计费用算不出来的情况是**真实存在的**：
--   * 该平台 / 该渠道**没有生效的规则**；
--   * 规则存在但**不适用于**这个金额（阶梯落不进档）；
--   * 规则是**按比例**的，而这一行**没有销售额基数**（如结算明细只给费用金额）；
--   * 币种不一致（混币不许相加）。
--
-- 这些情况下服务层**不写预计费用事实行**（而不是写一行 0），
-- 查询时该组的预计费用是 **null** + 一句原因 ——
-- **「差异 0.00」正是蓝图加粗句要消灭的现象**。
--
-- ---------------------------------------------------------------------------
-- 3. 维度怎么定（为什么有这么多列）
-- ---------------------------------------------------------------------------
--
-- 四个费用类型的**归属维度本来就不一样**，这是上游事实决定的，不是设计偏好：
--
--   | 费用类型     | 预计来自                        | 实际来自                  | 归属维度                    |
--   |--------------|---------------------------------|---------------------------|-----------------------------|
--   | PLATFORM_FEE | catalog 5.2.3.1 平台费规则       | catalog 实际费用台账 / 12 结算 | 平台 + 站点                 |
--   | LOGISTICS_FEE| catalog 5.2.3.2 渠道计费规则     | catalog 实际运费台账 / 12 结算 | 渠道 + 承运商 + 运输方式     |
--   | AD_FEE       | channel 14 广告预算分配方案      | 12.3.1.2 广告费明细          | 平台 + 店铺                 |
--   | HEAD_FREIGHT | catalog 5.2.1.1 成本构成的 FIRST_MILE | catalog 5.2.1.3 成本分摊  | SKU（+ 发运单）             |
--
-- ⇒ 维度列**全部显式列出**（空串 = 该费用类型不适用这个维度），
--   不塞进一个 JSON 列：JSON 列没法建索引，也没法在 SQL 里 GROUP BY。
--
-- ⚠️ **空串而不是 NULL**：PostgreSQL 的 UNIQUE 把 NULL 视为互不相等，
--   「平台级」行（其它维度都为空）会因为 NULL != NULL 插出第二条同样的行。
--   这里**用 NOT NULL DEFAULT ''** 把「不适用」与「空值」统一掉，
--   幂等键才真的成立（与 platform_fee_rule 的 COALESCE 同一手法，但更省事）。
--
-- ---------------------------------------------------------------------------
-- 4. 期间（period_start / period_end）为什么是两列
-- ---------------------------------------------------------------------------
--
-- 大多数费用事实是**逐日**的（period_start = period_end = 那天），
-- 但**广告预算计划**是**一个窗口**（windowStart ~ windowEnd）。
-- 差异要按「预计费用的期间」与「落在该期间内的实际费用」对照，
-- 所以期间必须能表达「一段」而不只是「一天」。
--
-- ---------------------------------------------------------------------------
-- 5. 多租户（RLS isolation）+ 授权
-- ---------------------------------------------------------------------------
--
-- 两张表都是租户级业务表：tenant_id uuid NOT NULL + ENABLE **与 FORCE** RLS
-- + GRANT reporting_app / starcon_app。与 V376 的七张表**完全同构**。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_expense_estimate_fact —— 预计费用事实（规则测算的那一侧）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个费用类型 / 某个归属维度 / 某个期间」的**预计费用**。
--
-- ⚠️ **只追加投影，随时可重建**：它由上游规则算出来，清空后重放同一批上游事实即可回到同一状态
-- （事件账本 + 幂等键 + 重建纪元三条纪律见 ReportingProjectionService）。
CREATE TABLE reporting_expense_estimate_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 费用类型（**本模块自己的口径**，四个值穷尽蓝图职责句点名的四类费用）。
  fee_type text NOT NULL
    CHECK(fee_type IN ('PLATFORM_FEE','LOGISTICS_FEE','AD_FEE','HEAD_FREIGHT')),

  -- 费用子类型（上游口径原样保留：平台费的 COMMISSION / REFERRAL / …，
  -- 运费的计费口径 PER_WEIGHT / PER_VOLUME / …）。空串 = 上游没给子类型。
  fee_sub_type varchar(32) NOT NULL DEFAULT '',

  -- ===== 归属维度（空串 = 该费用类型不适用这个维度）=====
  platform_code varchar(32) NOT NULL DEFAULT '',
  site_code varchar(32) NOT NULL DEFAULT '',
  channel_code varchar(32) NOT NULL DEFAULT '',
  carrier_code varchar(32) NOT NULL DEFAULT '',
  shipping_method_code varchar(32) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',

  -- 期间（逐日事实两列相同；窗口型事实 period_end > period_start）。
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- 币种（**不跨币种相加**：仓库铁律）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 预计费用金额。numeric(24,4)，**没有第二个金额来源**。
  estimated_amount numeric(24,4) NOT NULL,

  -- 测算基数（**留痕用**：这一侧的数是用哪个销售额 / 数量算出来的）。
  -- base_amount  = 销售额 / 货值基数；base_quantity = 数量基数。
  base_amount numeric(24,4) NOT NULL DEFAULT 0 CHECK(base_amount >= 0),
  base_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(base_quantity >= 0),

  -- 来源服务与上游行标识（幂等 upsert 的另一半键）。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',

  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 期间不能倒过来。
  CHECK(period_end >= period_start)
);

-- 幂等键：同一 (租户, 费用类型, 维度, 期间, 币种, 来源服务, 上游行) 只有一条预计费用。
CREATE UNIQUE INDEX reporting_expense_estimate_fact_identity
  ON reporting_expense_estimate_fact(
    tenant_id, fee_type, fee_sub_type,
    platform_code, site_code, channel_code, carrier_code, shipping_method_code,
    shop_code, sku_code,
    period_start, period_end, currency, source_service, source_reference);

-- 按窗口聚合用：租户 + 费用类型 + 期间 + 币种。
CREATE INDEX reporting_expense_estimate_fact_by_period
  ON reporting_expense_estimate_fact(tenant_id, fee_type, period_start, currency);
-- 按维度钻选用：租户 + 费用类型 + SKU / 店铺 / 平台。
CREATE INDEX reporting_expense_estimate_fact_by_scope
  ON reporting_expense_estimate_fact(tenant_id, fee_type, sku_code, shop_code, platform_code, period_start);

-- ---------------------------------------------------------------------------
-- 2. reporting_expense_actual_fact —— 实际费用事实（真的发生的那一侧）
-- ---------------------------------------------------------------------------
--
-- ⚠️ **允许负数**：退款 / 冲回是真实发生的费用变化，
--   把它截成 0 就等于把「平台退了这笔钱」写成「平台没收这笔钱」。
--
-- ⚠️ **允许 0**：平台这笔确实没收钱（0）是一个真实的观察值，
--   而它与「规则算出应该扣 3.72」之间的差异恰恰是最有信息量的那一类。
CREATE TABLE reporting_expense_actual_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  fee_type text NOT NULL
    CHECK(fee_type IN ('PLATFORM_FEE','LOGISTICS_FEE','AD_FEE','HEAD_FREIGHT')),
  fee_sub_type varchar(32) NOT NULL DEFAULT '',

  platform_code varchar(32) NOT NULL DEFAULT '',
  site_code varchar(32) NOT NULL DEFAULT '',
  channel_code varchar(32) NOT NULL DEFAULT '',
  carrier_code varchar(32) NOT NULL DEFAULT '',
  shipping_method_code varchar(32) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',

  period_start date NOT NULL,
  period_end date NOT NULL,

  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 实际发生额（可负 = 冲回）。
  actual_amount numeric(24,4) NOT NULL,

  -- 实际这一侧的基数（结算明细通常**没有**销售额基数 ⇒ 0，
  -- 而「没有基数」与「基数是 0」在下游是两种不同的可计算性）。
  base_amount numeric(24,4) NOT NULL DEFAULT 0 CHECK(base_amount >= 0),
  base_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(base_quantity >= 0),

  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',

  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(period_end >= period_start)
);

CREATE UNIQUE INDEX reporting_expense_actual_fact_identity
  ON reporting_expense_actual_fact(
    tenant_id, fee_type, fee_sub_type,
    platform_code, site_code, channel_code, carrier_code, shipping_method_code,
    shop_code, sku_code,
    period_start, period_end, currency, source_service, source_reference);

CREATE INDEX reporting_expense_actual_fact_by_period
  ON reporting_expense_actual_fact(tenant_id, fee_type, period_start, currency);
CREATE INDEX reporting_expense_actual_fact_by_scope
  ON reporting_expense_actual_fact(tenant_id, fee_type, sku_code, shop_code, platform_code, period_start);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_expense_estimate_fact','reporting_expense_actual_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_expense_estimate_fact, reporting_expense_actual_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_expense_estimate_fact, reporting_expense_actual_fact TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.4.1.1 多模式利润报表 / 13.4.1.2 SKU 利润**：读同一批事实，
--     按「预计费用 vs 实际费用」两个口径分别进利润表 —— 本表不提供利润口径。
--   * **13.4.2.1 成本结构**：按同一批费用类型算占比 —— 本表不提供占比口径。
--   * **13.4.3.1 利润预警**：差异率是预警的输入之一 —— 预警阈值配置不在本模块。
--   * **5.2.3 规则校准（下游）**：本模块只**产出**差异与建议，
--     **不写任何规则**（费用规则是 catalog 的写入所有权，跨服务写入被 AGENTS.md 禁止）。
