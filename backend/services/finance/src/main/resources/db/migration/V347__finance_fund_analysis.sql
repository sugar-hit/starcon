-- ===========================================================================
-- V347 · finance_fund_analysis —— 12.7.2.1 资金分析
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.7.2.1 资金分析）原文：
--
--   职责：资金分析：现金流入流出趋势、各币种资金分布与汇率敞口、资金周转天数、
--         付款集中度与流动性预警。
--   输入：资金流水；应收应付账期；汇率数据。
--   输出：资金报表 + 汇率敞口测算 + 流动性预警。
--   关系：上游 = 12.7.1、12.1.2.2；下游 = 1 工作台。
--         **汇率敞口是跨境特有风险**：大量外币存货 + 本币负债的组合下，汇率波动直接影响利润。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么（上游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **12.7.1.1 账户管理未交付** ⇒ **「资金账户余额」这个数据源当前不存在**。
--     全仓 grep 过：`backend/services/finance/src/main/resources/db/migration/` 下
--     **没有任何资金账户表**（`fund_account` / `bank_account` 零命中），
--     `finance_receipt.fund_account_ref` 由 V341 的 CHECK 钉死**恒为 NULL**
--     （V341 原文：「受管资金账户引用：12.7.1.1 账户管理未交付 ⇒ 由 CHECK 钉死恒为 NULL」），
--     只有自由文本 `fund_account_label`（人工备注，例如 "招商银行 6222****1234"）。
--     ⇒ 本模块**不产出账户级余额**，也**不产出账户级的资金分布**：
--       · DB 层用 CHECK 把「受管账户归属」钉死为恒 NULL + 归属模块写死在数据上
--         （`fund_account_ref` / `fund_account_owner_module`）；
--       · 能力声明里如实写「账户级资金分布不可用，归属模块 12.7.1.1」；
--       · **绝不**用硬编码数组 / 常量冒充账户余额。
--     本模块的「各币种资金分布」是**币种级**的（按币种汇总真实流水与余额），
--     它的口径是「累计已到账现金」而不是「在手账户余额」——
--     在没有账户表的情况下这两者**不相等**，响应里逐处标注。
--
--   * **12.7.1.2 银行对账未交付** ⇒ **「银行流水」这个数据源当前不存在**
--     （同样零命中：`bank_statement`）。本模块**不做银行对账**、
--     **不产出对账差异**、**不产出未达账项**。
--     ⇒ DB 层用 CHECK 把「银行流水归属」钉死为恒 NULL + 归属模块写死在数据上
--       （`bank_statement_ref` / `bank_statement_owner_module`）。
--
--   * **12.1.2.2 付款计划已交付，但计划表写不出「已付款」**：
--     V343 里 `finance_payment_plan.execution_available` 与
--     `finance_payment_batch.execution_available` 都由**表级 CHECK 钉死为 false**
--     （V343 原文：「付款的唯一写入口是 12.2.2.1 的行级 paid_amount（归 12.2.2.1，未交付）
--     ⇒ 本表 execution_available 由表级 CHECK 钉死为 false ⇒ **数据层不存在「已付款」**」）。
--     ⇒ 本模块的流出侧一律按**计划流出**口径报出，**绝不**把它当成实际流出。
--
--   * **12.2.2.1 付款单与审批未交付** ⇒ `finance_payable_line.paid_amount`
--     （进而 `finance_payable.paid_amount`）**当前恒为 0**，
--     而且**付款行上没有付款时刻字段** ⇒ 实际流出**没有日期维度**，
--     无法进入任何按日期分桶的趋势线。本模块如实把它单列在「累计已付」口径里，
--     并在能力声明里写明归属模块 12.2.2.1。
--
--   * **1 工作台（下游）未交付** ⇒ 本模块**只产出报表与预警本身**，
--     **不产出工作台卡片数据**（不写待办、不写卡片位）。
--
--   * **汇率数据来自 catalog 服务（5.2.2.1）**，本服务**不建汇率表、不抄汇率**：
--     跨服务只经 catalog 的**授权 HTTP API**读（见 CatalogExchangeRatePort），
--     AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_liquidity_threshold   —— 流动性预警阈值配置（租户自己维护；没配过时用蓝图默认档）
--   finance_fund_exposure_snapshot —— 汇率敞口测算快照（把一次测算的**输入与结论**钉住，可回看）
--
--   两张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 为什么敞口要落一张快照表（而不是每次实时算完就丢）
-- ---------------------------------------------------------------------------
--
--   「汇率敞口」是**跨境特有风险**：汇率波动直接影响利润 ⇒ 这个数字需要**能回看**
--   （上个月的外币敞口是多少？用了哪一天的汇率？），否则事后根本无法复盘。
--
--   快照落库时把**三样东西一起钉住**：
--     · 测算基准日 as_of_date（UTC 日历日）
--     · 用到的汇率 exchange_rate + rate_effective_date（汇率是外部事实，必须记出处）
--     · 敞口的两端金额（资产端应收未核销余额 / 负债端应付未付余额）
--
--   ⇒ 「这张快照是用 7.1234 的汇率、算 2026-10-24 的敞口」是**可复现**的，
--   而不是一句「当时大概是这个数」。
--
-- ---------------------------------------------------------------------------
-- 3. 「汇率敞口」的口径（本模块自己的定义，写死在这里）
-- ---------------------------------------------------------------------------
--
--   资产端 = 该币种的**未核销应收余额**（finance_receivable.amount − settled_amount，
--            状态 OPEN / PARTIALLY_SETTLED）—— 未来将收到的外币。
--   负债端 = 该币种的**未付应付余额**（finance_payable.unpaid_amount，
--            状态 OPEN / PARTIALLY_PAID）—— 未来将支付的外币。
--   净敞口 = 资产端 − 负债端（正 = 多外币资产，负 = 多外币负债）。
--
--   ⚠️ **已到账现金（finance_receipt）不计入敞口净额**：
--     一笔收款与它对应的那笔应收**可能是同一笔钱**（12.2.1.2 核销会把收款核销到应收明细行，
--     核销后收款的 unsettled_amount 归零、应收的 settled_amount 同步上升）。
--     两个口径并起来会**重复计算同一笔钱**。已到账现金单列在「各币种资金分布」里。
--
--   ⚠️ **本模块不把存货算进敞口**：蓝图举的例子是「大量外币存货 + 本币负债」，
--     但**存货不在本服务**（07 库存核算 / 08 仓储作业 / 13 利润与经营分析）。
--     缺的那一段在能力声明里如实写明归属，本模块**不用 0 冒充**。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_liquidity_threshold —— 流动性预警阈值配置
-- ---------------------------------------------------------------------------
--
-- 阈值全部是**比例或天数**，不是金额 ——
-- 金额阈值必须指定币种（USD 与 EUR 加起来不是任何一个金额），
-- 而规则是「跨币种统一的口径」，所以阈值这一层刻意**不设金额档**。
--
-- ⚠️ 租户没配过时生效的是**蓝图默认档**（服务层负责），表里**不预置任何一行** ——
-- 预置行会让人以为「这是系统认定必须配的」，
-- 而实际上阈值是可选的（有默认档）。
CREATE TABLE finance_liquidity_threshold (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 规则码：与服务层枚举一一对应（见 FundAnalysisModels.LiquidityRule）。
  code varchar(48) NOT NULL CHECK(code IN (
    'PAYMENT_CONCENTRATION',   -- 单期计划付款额占窗口总额的比例上限
    'NET_OUTFLOW_GAP',         -- 计划流出 ÷ 流入 的上限（> 1 即为缺口）
    'RECEIVABLE_OVERDUE',      -- 逾期未核销应收 ÷ 未核销应收 的上限
    'CASH_BUFFER_DAYS'         -- 现金可支撑天数（按日均计划流出计）的下限
  )),
  label varchar(80) NOT NULL,
  -- 阈值本体：比例（0~1）或天数。语义由 code 决定。
  threshold_value numeric(19,6) NOT NULL CHECK(threshold_value >= 0),
  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个租户一条规则一个阈值（整体替换语义）。
  UNIQUE(tenant_id, code)
);

-- ---------------------------------------------------------------------------
-- 2. finance_fund_exposure_snapshot —— 汇率敞口测算快照
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某一天、某个本位币口径下，对某个币种做的一次敞口测算」。
--
-- 🔴 **12.7.1.1 账户管理未交付** ⇒ 受管资金账户归属钉死恒为 NULL，
--    归属模块写死在数据上（不是只写在文档里）。
-- 🔴 **12.7.1.2 银行对账未交付** ⇒ 银行流水归属钉死恒为 NULL，归属模块同样写死在数据上。
CREATE TABLE finance_fund_exposure_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  snapshot_no varchar(48) NOT NULL,
  -- 测算基准日：**UTC 日历日**（账期 / 敞口是天数概念，不是某一瞬间）。
  as_of_date date NOT NULL,
  -- 本位币：敞口折算的目标币种。
  base_currency char(3) NOT NULL CHECK(base_currency ~ '^[A-Z]{3}$'),
  -- 被测算的币种。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 方向：LONG_ASSET（多外币资产）/ SHORT_ASSET（多外币负债）/ FLAT（无净敞口）。
  direction text NOT NULL CHECK(direction IN ('LONG_ASSET','SHORT_ASSET','FLAT')),
  -- 资产端：未核销应收余额（外币）。
  receivable_open_amount numeric(19,4) NOT NULL CHECK(receivable_open_amount >= 0),
  -- 负债端：未付应付余额（外币）。
  payable_open_amount numeric(19,4) NOT NULL CHECK(payable_open_amount >= 0),
  -- 净敞口（外币）：资产端 − 负债端，可正可负。
  net_exposure_amount numeric(19,4) NOT NULL,
  -- 用到的汇率：1 外币 = exchange_rate 本位币。方向在字段名里，不靠调用方猜。
  exchange_rate numeric(19,8) NOT NULL CHECK(exchange_rate > 0),
  -- 汇率的生效日（来自 catalog 的汇率事实）—— 记下来才能复现。
  rate_effective_date date NOT NULL,
  -- 本位币口径的敞口 = 净敞口 × 汇率。
  base_exposure_amount numeric(19,4) NOT NULL,
  -- 敏感度：汇率每变动 1% / 5%，本位币敞口同向变动多少。
  sensitivity_per_1pct numeric(19,4) NOT NULL,
  sensitivity_per_5pct numeric(19,4) NOT NULL,
  -- 🔴 12.7.1.1 账户管理未交付 ⇒ 受管账户归属恒为 NULL，归属写死在数据上。
  fund_account_ref uuid CHECK(fund_account_ref IS NULL),
  fund_account_owner_module varchar(16) NOT NULL DEFAULT '12.7.1.1'
    CHECK(fund_account_owner_module = '12.7.1.1'),
  -- 🔴 12.7.1.2 银行对账未交付 ⇒ 银行流水归属恒为 NULL，归属写死在数据上。
  bank_statement_ref uuid CHECK(bank_statement_ref IS NULL),
  bank_statement_owner_module varchar(16) NOT NULL DEFAULT '12.7.1.2'
    CHECK(bank_statement_owner_module = '12.7.1.2'),
  -- 账户级余额不可用（12.7.1.1）：这条快照只能到**币种级**，到不了账户级。
  account_level_available boolean NOT NULL DEFAULT false
    CHECK(account_level_available = false),
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, snapshot_no),
  -- 本位币与被测算币种不能是同一个（否则敞口恒为 0，没有意义）。
  CHECK(base_currency <> currency)
);

-- 敞口快照按「基准日 + 币种」查（回看某个时点的敞口构成）。
CREATE INDEX finance_fund_exposure_snapshot_by_asof
  ON finance_fund_exposure_snapshot(tenant_id, as_of_date desc, currency);

-- 阈值配置按 code 查（服务层读配置时用；UNIQUE 已保证每个 code 至多一行，
-- 这个索引服务的是「按 code 命中」这条路径本身）。
CREATE INDEX finance_liquidity_threshold_by_code
  ON finance_liquidity_threshold(tenant_id, code);

-- ===========================================================================
-- 多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_liquidity_threshold','finance_fund_exposure_snapshot'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_liquidity_threshold, finance_fund_exposure_snapshot TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_liquidity_threshold, finance_fund_exposure_snapshot TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 敞口快照是**举证凭据**（一次测算的输入与结论）：只能追加，不能改。
-- 重新测算 ⇒ 追加新的一行（基准日可能不同），不是把旧的一行改掉。
REVOKE UPDATE,DELETE ON finance_fund_exposure_snapshot FROM finance_app;
