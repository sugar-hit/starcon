-- ===========================================================================
-- V399 · cost_structure —— 13.4.2.1 成本结构
-- ===========================================================================
--
-- **本文件做什么**：给 reporting 服务（13 利润与经营分析的唯一归属服务）加**一张成本事实表**
-- 与**一张阈值规则表**，让「成本构成占比」这件事**在查询期**算出来。
--
-- ---------------------------------------------------------------------------
-- 0. 蓝图原文（本迁移要兑现的全部内容）
-- ---------------------------------------------------------------------------
--
--   ### 13.4.2.1 成本结构
--   - **职责**：分析成本构成占比：采购/头程/平台费/物流/广告/退货/其他，
--             按 SKU、店铺、类目、时间维度拆解，看成本结构变化趋势。
--   - **输入**：成本分摊结果；历史基线。
--   - **输出**：占比结构图 + 趋势 + 异常提示。
--   - **关系**：上游 = 12.4.2；下游 = 13.4.2.2。
--             **成本结构变化比绝对值更有信号**：广告占比突然上升往往先于利润下滑发生。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 为什么「七类成本」必须是**一张表的一个枚举列**
-- ---------------------------------------------------------------------------
--
-- 蓝图职责句把成本拆成**七类**：采购 / 头程 / 平台费 / 物流 / 广告 / 退货 / 其他。
-- 若拆成七张表，占比就变成「七张表的行数比」——那不是占比。
-- ⇒ **一张表 + 一个受 CHECK 约束的枚举列**：同一笔窗口内七类成本互相可加、可比。
--
-- ⚠️ **七类与上游口径的对应关系**（上游给什么，我们就归什么，**不重算**）：
--
--   | 成本类别   | 口径                        | 上游                                     |
--   |------------|-----------------------------|------------------------------------------|
--   | PURCHASE   | 采购成本（出库成本）        | 12.4.1.2 成本计算条目（销售出库）        |
--   | HEAD_MILE  | 头程（首公里）分摊          | 5.2.1.3 成本分摊明细（逐 SKU）           |
--   | PLATFORM_FEE | 平台费（佣金 / 平台服务费）| 5.2.3.1 实际台账 + 12.3.1.2 佣金        |
--   | LOGISTICS_FEE | 物流费（FBA / 运费）     | 5.2.3.2 实际台账 + 12.3.1.2 FBA 费用    |
--   | AD_FEE     | 广告费                      | 12.3.1.2 广告费明细                      |
--   | RETURN     | 退货（退款 / 退货成本）     | 10.4 售后退货单（按受理时刻）             |
--   | OTHER      | 其它（订阅 / 人员 / 办公）  | 12.4.2.1 费用归集条目                    |
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 「算不出来」与「等于 0」必须分开
-- ---------------------------------------------------------------------------
--
-- 占比算不出来的情况是**真实存在的**：
--   * 这一类成本在这个窗口里**根本没有事实**（上游没交、没同步）；
--   * **分母**缺失或为 0（总成本口径下窗口里一分钱成本都没有；收入口径下没有销售额）；
--   * **历史基线**不足（要前 N 期才有基线，历史不够就判不了异常）。
--
-- ⇒ 服务层把这些情况一律报成 **null + 一句原因**，
--   **绝不写成 0.00 / 0%**（「占比 0.00%」正是蓝图加粗句要消灭的现象）。
--
-- ---------------------------------------------------------------------------
-- 3. 维度列（空串而不是 NULL）
-- ---------------------------------------------------------------------------
--
-- 七类成本的**归属维度本来就不一样**（上游事实决定的，不是设计偏好）：
--   采购 → SKU；头程 → SKU；平台费 → 平台 + 站点 + 店铺；
--   物流 → 渠道 + 承运商 + 运输方式；广告 → 平台 + 店铺；
--   退货 → 店铺；其它 → 期间（无明细维度）。
--
-- ⇒ 维度列**全部显式列出**，空串 = 该类成本不适用这个维度。
--
-- ⚠️ **空串而不是 NULL**：PostgreSQL 的 UNIQUE 把 NULL 视为互不相等，
--   「平台级」行（其它维度都为空）会因为 NULL != NULL 插出第二条同样的行。
--   这里用 **NOT NULL DEFAULT ''** 把「不适用」与「空值」统一掉，幂等键才真的成立。
--
-- ⚠️ **类目维度（category_code）是本模块自己补的**：上游成本事实里没有类目，
--   由 catalog 的 SKU 档案映射得到；**映射不到就留空串**（不猜一个类目）。
--
-- ---------------------------------------------------------------------------
-- 4. 期间（两列）与金额（可负）
-- ---------------------------------------------------------------------------
--
-- 期间用两列（period_start / period_end）：逐日事实两列相同，
-- 但**费用归集条目**是「一个费用池覆盖一段期间」⇒ 单列表达不了。
--
-- ⚠️ **实际侧允许负数**：退款 / 冲回是真实发生的费用变化，
--   把它截成 0 就等于把「平台退了这笔钱」写成「平台没收这笔钱」。
--
-- ---------------------------------------------------------------------------
-- 5. 阈值规则表：为什么异常阈值**不能写死**
-- ---------------------------------------------------------------------------
--
-- 蓝图关系句说「**成本结构变化比绝对值更有信号**」⇒ 异常判定必然要一个阈值。
-- 阈值**写死在代码里**的问题是：它既不能按租户调（不同品类波动幅度差一个数量级），
-- 也不能随历史数据变（一个静态百分比对上一条平稳的品类和一条季节性强烈的品类
-- 含义完全不同）。
--
-- ⇒ 阈值落成**一张租户级规则表**（reporting_cost_structure_rule）：
--   * threshold_mode = 'FIXED'  —— 固定百分点阈值（可按租户改）；
--   * threshold_mode = 'STDDEV' —— **可计算**阈值：基线占比标准差的若干倍，
--     阈值随这条成本线自己的历史波动自动伸缩（波动大的品类门槛自动更高）。
--
-- ⚠️ **规则表是租户自己的口径**，服务层只播种一份**默认**规则（可在接口上改）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_cost_structure_fact —— 成本结构事实
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某类成本 / 某个归属维度 / 某个期间 / 某个币种」的一笔金额。
--
-- ⚠️ **只追加投影，随时可重建**：它由上游算好的分摊 / 归集结果拉取而来，
--   清空后重放同一批上游事实即可回到同一状态。
CREATE TABLE reporting_cost_structure_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 成本类别（**本模块自己的口径**，七类穷尽蓝图职责句）。
  cost_category text NOT NULL
    CHECK(cost_category IN ('PURCHASE','HEAD_MILE','PLATFORM_FEE','LOGISTICS_FEE','AD_FEE','RETURN','OTHER')),

  -- 成本子类型（上游口径原样保留：台账来源 / 结算类别 / 分摊来源 / …）。
  -- 空串 = 上游没给子类型。
  cost_sub_type varchar(32) NOT NULL DEFAULT '',

  -- ===== 归属维度（空串 = 该类成本不适用这个维度）=====
  platform_code varchar(32) NOT NULL DEFAULT '',
  site_code varchar(32) NOT NULL DEFAULT '',
  channel_code varchar(32) NOT NULL DEFAULT '',
  carrier_code varchar(32) NOT NULL DEFAULT '',
  shipping_method_code varchar(32) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  category_code varchar(64) NOT NULL DEFAULT '',

  -- 期间（逐日事实两列相同；费用池型事实 period_end > period_start）。
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- 币种（**不跨币种相加**：仓库铁律）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 金额。numeric(24,4)，**允许负数**（退款 / 冲回）。
  amount numeric(24,4) NOT NULL,

  -- 数量（归集条目可能带数量；没有就是 0 —— 数量**不参与**占比计算）。
  quantity numeric(19,6) NOT NULL DEFAULT 0,

  -- 来源服务与上游行标识（幂等 upsert 的另一半键）。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',

  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 期间不能倒过来。
  CHECK(period_end >= period_start)
);

-- 幂等键：同一 (租户, 成本类别, 维度, 期间, 币种, 来源服务, 上游行) 只有一条成本事实。
CREATE UNIQUE INDEX reporting_cost_structure_fact_identity
  ON reporting_cost_structure_fact(
    tenant_id, cost_category, cost_sub_type,
    platform_code, site_code, channel_code, carrier_code, shipping_method_code,
    shop_code, sku_code, category_code,
    period_start, period_end, currency, source_service, source_reference);

-- 按窗口聚合用：租户 + 成本类别 + 期间 + 币种。
CREATE INDEX reporting_cost_structure_fact_by_period
  ON reporting_cost_structure_fact(tenant_id, cost_category, period_start, currency);
-- 按维度钻选用：租户 + SKU / 店铺 / 类目。
CREATE INDEX reporting_cost_structure_fact_by_scope
  ON reporting_cost_structure_fact(tenant_id, sku_code, shop_code, category_code, period_start);

-- ---------------------------------------------------------------------------
-- 2. reporting_cost_structure_rule —— 异常判定阈值（租户自己的口径）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某类成本的异常判定阈值」。这张表存在的唯一理由是：
-- **阈值不写死在代码里**（见上文第 5 节）。
CREATE TABLE reporting_cost_structure_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  cost_category text NOT NULL
    CHECK(cost_category IN ('PURCHASE','HEAD_MILE','PLATFORM_FEE','LOGISTICS_FEE','AD_FEE','RETURN','OTHER')),

  -- 阈值口径：FIXED = 固定百分点；STDDEV = 基线占比标准差的若干倍（**可计算**）。
  threshold_mode text NOT NULL CHECK(threshold_mode IN ('FIXED','STDDEV')),

  -- 阈值取值。FIXED ⇒ 百分点（例如 5.000000 = 5 个百分点）；
  -- STDDEV ⇒ 标准差倍数（例如 2.000000 = 2 倍标准差）。
  threshold_value numeric(19,6) NOT NULL CHECK(threshold_value >= 0),

  -- 最少历史期数：基线要**前 N 期**同口径占比才成立（历史不够就判不了异常）。
  min_history_periods int NOT NULL CHECK(min_history_periods >= 1),

  -- 判定方向：BOTH = 升或降都算异常；UP = 只认「占比突然上升」；
  -- DOWN = 只认「占比突然下降」。
  -- ⚠️ 蓝图关系句点名的是「**广告占比突然上升**往往先于利润下滑发生」
  --   ⇒ 默认方向里必须有 UP 这一档。
  direction text NOT NULL CHECK(direction IN ('BOTH','UP','DOWN')),

  enabled boolean NOT NULL DEFAULT true,

  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个成本类别只有一条规则。
  UNIQUE(tenant_id, cost_category)
);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_cost_structure_fact','reporting_cost_structure_rule'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_cost_structure_fact, reporting_cost_structure_rule TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_cost_structure_fact, reporting_cost_structure_rule TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.4.2.2 费用差异**：读同一批上游事实，算「预计 vs 实际」的差异率 —— 本表不提供差异口径。
--   * **13.4.1.1 多模式利润报表 / 13.4.1.2 SKU 利润**：引用本表的七类成本口径拼利润表。
--   * **13.4.3.1 利润预警**：成本结构异常是预警的输入之一 —— 预警阈值配置不在本模块。
--   * **12.4.2 成本分摊**：本模块**只读**上游分摊 / 归集结果，**不重算分摊**。
