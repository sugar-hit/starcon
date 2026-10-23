-- ===========================================================================
-- V351 · 12.4.2.1 费用归集
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.4.2.1 费用归集）原文：
--
--   职责：归集各类费用到指定维度：按月分摊的平台费/订阅费、按单分摊的物流费、
--         按 SKU 分摊的头程费、按期间分摊的人员与办公费用，保留归集依据。
--   输入：费用来源（平台、物流、订阅、人工录入）；分摊目标维度。
--   输出：归集明细（费用项 → 分摊对象 → 金额）。
--   关系：上游 = 12.3.1.2、10 物流、14 广告；下游 = 12.4.2.2。
--         **归集与分摊要分开**：先按规则归集费用池，再按维度分摊，便于核对与调整。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移做什么 / 不做什么（先把边界钉死，再写 DDL）
-- ---------------------------------------------------------------------------
--
--   本模块**只做归集**：把各类费用按规则收进「费用池」，并把**归集依据**钉在条目上。
--   本模块**不做分摊**：分摊到 SKU / 订单 / 期间的结果表**一张都不建**
--   （那是下游 12.4.2.2「分摊规则」的职责，本车道不抢做）。
--
--   ⇒ 边界在**数据层**表达，不靠注释：
--       · 费用池与池条目上都有 `allocation_available boolean NOT NULL DEFAULT false`
--         + 表级 CHECK 钉死为 false，`allocation_owner_module` 恒为 '12.4.2.2'；
--       · 池条目上 `allocated_amount numeric(19,4) NOT NULL DEFAULT 0`
--         + CHECK 钉死为 0，`allocation_status` 恒为 'UNALLOCATED'。
--     ⇒ **「已分摊」这个状态在数据层写不出来**，
--       「池条目 = 分摊结果」在结构上就是假的（集成测试直接断言这一点）。
--
-- ---------------------------------------------------------------------------
-- 1. 本模块刻意**不做**什么（上游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **12.4.2.2 分摊规则 —— 未交付** ⇒ 本模块**不实现**分摊算法、
--     **不产出**分摊结果与舍入差处理（那是下游，且蓝图要求
--     「分摊结果之和必须等于被分摊总额」，这条等式由下游负责）。
--   * **13 利润 —— 未交付**（reporting 服务不在本仓）⇒ 本模块**不产出任何利润数字**。
--   * **10.1.2.1 运费计算器 / 10.1.2.2 比价 —— 未交付** ⇒ 本模块**不自己算运费**，
--     物流费只从 10.2.1.1 的**运单运费登记**（`logistics_waybill_freight`）读，
--     那张表里的金额是**登记的**事实（人工登记或渠道对账单登记），不是算出来的。
--   * **卷 02 同步 —— 未交付平台交易报告同步端点** ⇒ 平台费只从 12.3.1.2
--     的**结算明细行**（`finance_settlement_line`）读，**绝不造一份「同步来的」假账单**。
--
-- ---------------------------------------------------------------------------
-- 2. 四张表：费用项 → 归集规则 → 费用池条目 → 归集依据
-- ---------------------------------------------------------------------------
--
--   蓝图输出写的是「归集明细（**费用项 → 分摊对象 → 金额**）」，
--   而「关系」那句强调「**先按规则归集费用池，再按维度分摊**」。
--   ⇒ 四层各用一张表，把链条完整表达出来：
--
--     finance_cost_collection_rule   归集规则（费用来源 → 费用项 → 目标维度 → 费用池）
--     finance_cost_collection_pool   费用池（按 池标识 × 维度 × 归属期间 × 币种 唯一）
--     finance_cost_collection_entry  费用池条目（**归集的最小单位**：一笔费用一项）
--     finance_cost_collection_basis  归集依据（条目的那条凭据：凭据号 / 原始金额 / 分项明细）
--
--   为什么「依据」要单独一张表而不是塞进条目：
--   蓝图把「**保留归集依据**」单列成职责，而依据的**结构随来源而变**
--   （结算明细行是「结算单号 + 行号」，运单运费是「运单号 + 运费登记类型」，
--   头程发运单是「运费 / 保险费 / 处理费」三段分项，人工录入是「单据号 + 凭据号」）。
--   一张表用 jsonb 装分项 + 固定的凭据列，**既统一又不丢信息**。
--
-- ---------------------------------------------------------------------------
-- 3. 归集规则把「四类分摊口径」钉成数据层不变量
-- ---------------------------------------------------------------------------
--
--   蓝图职责句点名的四类口径：
--     · 按月   分摊的平台费 / 订阅费
--     · 按单   分摊的物流费
--     · 按 SKU 分摊的头程费
--     · 按期间 分摊的人员与办公费用
--
--   ⇒ 规则表上的 (fee_source, fee_category, target_dimension, period_basis)
--     四元组由**表级 CHECK 钉死**为下面七组合法配对：
--
--     PLATFORM_SETTLEMENT / PLATFORM_FEE     / MONTH  / CALENDAR_MONTH   ← 12.3.1.2
--     AD_SPEND            / AD_FEE           / MONTH  / CALENDAR_MONTH   ← 14.3.1.1
--     SUBSCRIPTION        / SUBSCRIPTION_FEE / MONTH  / CALENDAR_MONTH   ← 人工录入
--     LOGISTICS_WAYBILL   / LOGISTICS_FEE    / ORDER  / CALENDAR_MONTH   ← 10.2.1.1
--     HEAD_MILE_SHIPMENT  / HEAD_MILE_FEE    / SKU    / CALENDAR_MONTH   ← 10.4.1.1
--     PERSONNEL           / PERSONNEL_COST   / PERIOD / ACCOUNTING_PERIOD ← 人工录入
--     OFFICE              / OFFICE_COST      / PERIOD / ACCOUNTING_PERIOD ← 人工录入
--
--   为什么钉死而不是自由配：
--     「按 SKU 分摊的平台费」这种配对在业务上说不通（平台费是按月结的，
--     没有 SKU 粒度的平台账单），而**说不通的配对一旦能存进库，
--     界面就会允许用户选它** ⇒ 用户配错 ⇒ 池里混进维度不一致的费用 ⇒
--     下游分摊时口径对不上。
--     钉死之后「口径配错」在 DB 层就进不来。
--
--   ⚠️ **规则由租户自己维护，系统不预置任何一行**（与 12.1.2.2 账期规则同一纪律）：
--     费用池的划分是租户自己的账务口径，系统替用户默认一个池名就是替用户记账。
--     服务层提供**蓝图标准绑定模板**（只读，不落库），供界面一键按模板建规则。
--
-- ---------------------------------------------------------------------------
-- 4. 币种：一个池一个币种，条目必须与池同币
-- ---------------------------------------------------------------------------
--
--   「绝不跨币种相加」（AGENTS.md 财务纪律 + 同服务 12.7.2.1 的同一口径）：
--   USD 池与 EUR 池是两张表行，不是同一个「合计」。
--   ⇒ 费用池的唯一键含 currency；条目插入时由触发器核对币种与维度一致，
--     币种不一致直接报错（而不是让两条不同币种的条目混进一个池）。
--
-- ---------------------------------------------------------------------------
-- 5. 归属期间（按月 / 按期）
-- ---------------------------------------------------------------------------
--
--   · CALENDAR_MONTH：归属期间 = 基准日期所在的**日历月**（UTC 日历日口径）。
--     平台费 / 广告费 / 订阅费 / 物流费 / 头程费都走这一档。
--   · ACCOUNTING_PERIOD：归属期间由操作员**显式给出**（起止日）。
--     人员与办公费用走这一档 —— 它天然按「会计期间」而不是「自然月」发生
--     （季末计提、年终奖），强行按自然月切会把一笔跨月的费用切碎。
--
--   归属期间落在**费用池**上（池 = 期间 × 维度 × 币种 × 池标识），
--   条目通过 pool_id 取到自己的期间 ⇒ **期间只有一处真相**，不会两份数据对不上。
--
-- ---------------------------------------------------------------------------
-- 6. 幂等：同一笔上游事实只许归集一次
-- ---------------------------------------------------------------------------
--
--   UNIQUE(tenant_id, fee_source, source_ref_key)：
--   同一张结算明细行 / 同一行广告报表 / 同一票运单运费 / 同一张头程发运单
--   **只能进池一次**。归集是「收钱进池」，同一笔钱进两次 = 费用凭空翻倍。
--   ⇒ 人工录入也要求给出 `source_ref_key`（单据号），
--     否则同一个人的两次误操作会造出两笔一样的费用。
--
-- ---------------------------------------------------------------------------
-- 7. 金额与数量：BigDecimal + NUMERIC，禁浮点（AGENTS.md）
-- ---------------------------------------------------------------------------
--
--   金额 NUMERIC(19,4)（与同服务 V345 / V347 一致）；
--   数量 NUMERIC(19,6)（AGENTS.md 明文要求）。
--   时间列一律 timestamptz + OffsetDateTime 绑定 + `?::timestamptz`。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 归集规则
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_collection_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_code varchar(64) NOT NULL,
  rule_name varchar(160) NOT NULL,

  -- 费用来源：这条规则从哪一类上游事实取费用。
  fee_source varchar(32) NOT NULL,
  -- 费用项：这条规则把费用收进哪个费用桶（下游分摊的对象类别）。
  fee_category varchar(32) NOT NULL,
  -- 分摊目标维度（**声明**，不是分摊结果）：MONTH / ORDER / SKU / PERIOD。
  target_dimension varchar(16) NOT NULL,
  -- 归属期间口径：CALENDAR_MONTH / ACCOUNTING_PERIOD。
  period_basis varchar(24) NOT NULL,

  -- 归集到哪个费用池（池标识 + 池名；池行本身按 期间 × 维度 × 币种 唯一）。
  pool_key varchar(64) NOT NULL,
  pool_name varchar(160) NOT NULL DEFAULT '',

  -- 生效区间：基准日期必须落在区间内，区间外的事实不得归集（防止改规则改历史）。
  effective_from date NOT NULL,
  effective_to date,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code),

  CHECK(btrim(rule_code) <> ''),
  CHECK(btrim(rule_name) <> ''),
  CHECK(btrim(pool_key) <> ''),
  CHECK(status IN ('ACTIVE','INACTIVE')),
  CHECK(effective_to IS NULL OR effective_to >= effective_from),

  -- 四类分摊口径由 DB 层钉死（见文件头第三节）。
  CHECK(
    (fee_source = 'PLATFORM_SETTLEMENT' AND fee_category = 'PLATFORM_FEE'
       AND target_dimension = 'MONTH'  AND period_basis = 'CALENDAR_MONTH') OR
    (fee_source = 'AD_SPEND'            AND fee_category = 'AD_FEE'
       AND target_dimension = 'MONTH'  AND period_basis = 'CALENDAR_MONTH') OR
    (fee_source = 'SUBSCRIPTION'        AND fee_category = 'SUBSCRIPTION_FEE'
       AND target_dimension = 'MONTH'  AND period_basis = 'CALENDAR_MONTH') OR
    (fee_source = 'LOGISTICS_WAYBILL'   AND fee_category = 'LOGISTICS_FEE'
       AND target_dimension = 'ORDER'  AND period_basis = 'CALENDAR_MONTH') OR
    (fee_source = 'HEAD_MILE_SHIPMENT'  AND fee_category = 'HEAD_MILE_FEE'
       AND target_dimension = 'SKU'    AND period_basis = 'CALENDAR_MONTH') OR
    (fee_source = 'PERSONNEL'           AND fee_category = 'PERSONNEL_COST'
       AND target_dimension = 'PERIOD' AND period_basis = 'ACCOUNTING_PERIOD') OR
    (fee_source = 'OFFICE'              AND fee_category = 'OFFICE_COST'
       AND target_dimension = 'PERIOD' AND period_basis = 'ACCOUNTING_PERIOD')
  )
);

CREATE INDEX finance_cost_collection_rule_by_source
  ON finance_cost_collection_rule(tenant_id, fee_source, effective_from);
CREATE INDEX finance_cost_collection_rule_by_status
  ON finance_cost_collection_rule(tenant_id, status, effective_from);

-- ---------------------------------------------------------------------------
-- 2. 费用池（归集的容器）
-- ---------------------------------------------------------------------------
--   一个池 = 池标识 × 维度 × 归属期间 × 币种。
--   合计与笔数由**触发器**从条目派生（下方第五节），不靠应用层维护 ——
--   应用层维护的合计迟早会和条目对不上，而「池合计 ≠ Σ条目」正是财务最不能接受的错。
CREATE TABLE finance_cost_collection_pool (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  pool_key varchar(64) NOT NULL,
  pool_name varchar(160) NOT NULL,
  dimension varchar(16) NOT NULL,

  -- 归属期间（按月 / 按期）。
  period_start date NOT NULL,
  period_end date NOT NULL,
  currency char(3) NOT NULL,

  status varchar(16) NOT NULL DEFAULT 'OPEN',
  -- 合计与笔数：由 finance_cost_collection_refresh_pool_totals 派生。
  total_amount numeric(19,4) NOT NULL DEFAULT 0,
  entry_count integer NOT NULL DEFAULT 0,

  -- 🔴 **归集 ≠ 分摊**：本模块不产出分摊结果，这两列把边界写在数据上。
  allocation_available boolean NOT NULL DEFAULT false,
  allocation_owner_module varchar(32) NOT NULL DEFAULT '12.4.2.2',
  allocation_note text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, pool_key, dimension, period_start, period_end, currency),

  CHECK(btrim(pool_key) <> ''),
  CHECK(btrim(pool_name) <> ''),
  CHECK(dimension IN ('MONTH','ORDER','SKU','PERIOD')),
  CHECK(period_end >= period_start),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(status IN ('OPEN','CLOSED')),
  CHECK(total_amount >= 0),
  CHECK(entry_count >= 0),
  CHECK(allocation_available = false),
  CHECK(allocation_owner_module = '12.4.2.2')
);

CREATE INDEX finance_cost_collection_pool_by_period
  ON finance_cost_collection_pool(tenant_id, period_start, period_end);
CREATE INDEX finance_cost_collection_pool_by_key
  ON finance_cost_collection_pool(tenant_id, pool_key, period_start);

-- ---------------------------------------------------------------------------
-- 3. 费用池条目（归集的最小单位：一笔费用一项）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_collection_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  pool_id uuid NOT NULL,
  rule_id uuid NOT NULL,

  -- 条目自带口径（由触发器与规则核对，不许各说各话）。
  fee_source varchar(32) NOT NULL,
  fee_category varchar(32) NOT NULL,
  target_dimension varchar(16) NOT NULL,

  -- 分摊基准日期：归属期间由它推出（CALENDAR_MONTH），也是下游分摊的锚。
  base_date date NOT NULL,

  currency char(3) NOT NULL,
  amount numeric(19,4) NOT NULL,
  quantity numeric(19,6),

  -- 上游事实的引用与幂等锚点。
  source_ref_type varchar(32) NOT NULL,
  source_ref_key varchar(200) NOT NULL,
  source_ref_code varchar(200) NOT NULL DEFAULT '',
  source_ref_amount numeric(19,4),

  -- 🔴 **归集 ≠ 分摊**：条目上没有「已分摊金额」，也没有别的分摊状态。
  allocated_amount numeric(19,4) NOT NULL DEFAULT 0,
  allocation_status varchar(16) NOT NULL DEFAULT 'UNALLOCATED',
  allocation_owner_module varchar(32) NOT NULL DEFAULT '12.4.2.2',

  note text NOT NULL DEFAULT '',
  collected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一笔上游事实只许归集一次（见文件头第六节）。
  UNIQUE(tenant_id, fee_source, source_ref_key),

  FOREIGN KEY (tenant_id, pool_id) REFERENCES finance_cost_collection_pool(tenant_id, id),
  FOREIGN KEY (tenant_id, rule_id) REFERENCES finance_cost_collection_rule(tenant_id, id),

  CHECK(target_dimension IN ('MONTH','ORDER','SKU','PERIOD')),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(amount > 0),
  CHECK(quantity IS NULL OR quantity >= 0),
  CHECK(source_ref_amount IS NULL OR source_ref_amount >= 0),
  CHECK(btrim(source_ref_key) <> ''),
  CHECK(allocated_amount = 0),
  CHECK(allocation_status = 'UNALLOCATED'),
  CHECK(allocation_owner_module = '12.4.2.2')
);

CREATE INDEX finance_cost_collection_entry_by_pool
  ON finance_cost_collection_entry(tenant_id, pool_id);
CREATE INDEX finance_cost_collection_entry_by_source
  ON finance_cost_collection_entry(tenant_id, fee_source, base_date);
CREATE INDEX finance_cost_collection_entry_by_dimension
  ON finance_cost_collection_entry(tenant_id, target_dimension, base_date);
CREATE INDEX finance_cost_collection_entry_by_category
  ON finance_cost_collection_entry(tenant_id, fee_category, base_date);

-- ---------------------------------------------------------------------------
-- 4. 归集依据（条目的那条凭据）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_collection_basis (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  entry_id uuid NOT NULL,

  -- 依据类型（随来源而变，词表固定 ⇒ 可按类型做核对口径）。
  basis_type varchar(32) NOT NULL,
  -- 凭据号：结算单号 / 运单号 / 发运单号 / 费用单据号。
  basis_code varchar(200) NOT NULL,
  basis_currency char(3) NOT NULL,
  -- 上游事实的原始金额：与条目金额一致时说明「原样归集」，
  -- 不一致时说明「口径有调整」—— 差额能被看见，才谈得上核对。
  basis_amount numeric(19,4) NOT NULL,
  basis_date date,
  -- 凭据引用（发票号 / 合同号 / 对账单号 / 附件路径）。
  evidence_ref varchar(200) NOT NULL DEFAULT '',
  -- 分项明细（随来源而变，如头程的运费 / 保险费 / 处理费三段）。
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一条条目一条依据（1:1）：归集依据描述的是**这一条**为什么是这个金额。
  UNIQUE(tenant_id, entry_id),

  FOREIGN KEY (tenant_id, entry_id) REFERENCES finance_cost_collection_entry(tenant_id, id),

  CHECK(basis_type IN ('SETTLEMENT_LINE','AD_REPORT_ROW','WAYBILL_FREIGHT',
                       'FIRST_MILE_SHIPMENT','MANUAL_DOCUMENT')),
  CHECK(btrim(basis_code) <> ''),
  CHECK(basis_currency ~ '^[A-Z]{3}$'),
  CHECK(basis_amount >= 0)
);

CREATE INDEX finance_cost_collection_basis_by_type
  ON finance_cost_collection_basis(tenant_id, basis_type, basis_date);

-- ===========================================================================
-- 5. 数据层不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 条目守卫：口径、期间、币种、规则生效区间
-- ---------------------------------------------------------------------------
--   「条目说按 SKU、规则说按月」「条目金额进了 USD 池却是 EUR」
--   「用一条停用规则继续归集」「基准日期在规则生效区间外」
--   —— 这四类错在应用层是能写进去的（只是没人会手滑而已），
--   而它们一旦写进去，池里的钱就再也对不上了。
--   ⇒ 在 INSERT 前逐条核对，核对不过直接报错。
CREATE OR REPLACE FUNCTION finance_cost_collection_entry_guard() RETURNS trigger AS $$
DECLARE
  r finance_cost_collection_rule%ROWTYPE;
  p finance_cost_collection_pool%ROWTYPE;
BEGIN
  SELECT * INTO r
    FROM finance_cost_collection_rule x
   WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.rule_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '归集规则不存在或不属于本租户' USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF r.status <> 'ACTIVE' THEN
    RAISE EXCEPTION '归集规则 % 已停用，不能继续归集', r.rule_code USING ERRCODE = 'check_violation';
  END IF;

  IF r.fee_source <> NEW.fee_source
     OR r.fee_category <> NEW.fee_category
     OR r.target_dimension <> NEW.target_dimension THEN
    RAISE EXCEPTION '费用来源 / 费用项 / 目标维度必须与归集规则 % 一致', r.rule_code
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.base_date < r.effective_from
     OR (r.effective_to IS NOT NULL AND NEW.base_date > r.effective_to) THEN
    RAISE EXCEPTION '基准日期不在归集规则 % 的生效区间内', r.rule_code
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT * INTO p
    FROM finance_cost_collection_pool x
   WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.pool_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '费用池不存在或不属于本租户' USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF p.dimension <> NEW.target_dimension THEN
    RAISE EXCEPTION '费用池 % 的分摊维度是 %，与条目声明的 % 不一致', p.pool_key,
      p.dimension, NEW.target_dimension USING ERRCODE = 'check_violation';
  END IF;

  -- 🔴 绝不跨币种相加：币种不一致直接拒绝，不允许「先混进去再算」。
  IF p.currency <> NEW.currency THEN
    RAISE EXCEPTION '费用池 % 的币种是 %，与条目币种 % 不一致 —— 绝不跨币种相加', p.pool_key,
      p.currency, NEW.currency USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_collection_entry_guard
  BEFORE INSERT ON finance_cost_collection_entry
  FOR EACH ROW EXECUTE FUNCTION finance_cost_collection_entry_guard();

-- ---------------------------------------------------------------------------
-- (b) 池合计由条目派生（应用层不维护合计）
-- ---------------------------------------------------------------------------
--   「池合计 = Σ条目金额」是本模块唯一必须恒成立的等式。
--   放触发器里而不是应用层：应用层漏一次更新，合计就永远错了，
--   而「合计」正是下游分摊要拿来当被分摊总额的数 —— 错了整条利润链都错。
--
--   ⚠️ 触发器刻意**不是** SECURITY DEFINER：以调用方身份执行 ⇒ RLS 生效 ⇒
--   跨租户串扰不可能发生（与 V348 的幂等守卫同一纪律）。
CREATE OR REPLACE FUNCTION finance_cost_collection_refresh_pool_totals() RETURNS trigger AS $$
DECLARE
  tid uuid;
BEGIN
  tid := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;

  -- 条目换池时，**旧池与新池都要重算**。
  IF TG_OP = 'UPDATE' AND OLD.pool_id IS DISTINCT FROM NEW.pool_id THEN
    UPDATE finance_cost_collection_pool p
       SET total_amount = COALESCE((SELECT sum(e.amount) FROM finance_cost_collection_entry e
                                     WHERE e.tenant_id = p.tenant_id AND e.pool_id = p.id), 0),
           entry_count = (SELECT count(*) FROM finance_cost_collection_entry e
                           WHERE e.tenant_id = p.tenant_id AND e.pool_id = p.id),
           updated_at = now()
     WHERE p.tenant_id = tid AND p.id = OLD.pool_id;

    UPDATE finance_cost_collection_pool p
       SET total_amount = COALESCE((SELECT sum(e.amount) FROM finance_cost_collection_entry e
                                     WHERE e.tenant_id = p.tenant_id AND e.pool_id = p.id), 0),
           entry_count = (SELECT count(*) FROM finance_cost_collection_entry e
                           WHERE e.tenant_id = p.tenant_id AND e.pool_id = p.id),
           updated_at = now()
     WHERE p.tenant_id = tid AND p.id = NEW.pool_id;
    RETURN NULL;
  END IF;

  UPDATE finance_cost_collection_pool p
     SET total_amount = COALESCE((SELECT sum(e.amount) FROM finance_cost_collection_entry e
                                   WHERE e.tenant_id = p.tenant_id AND e.pool_id = p.id), 0),
         entry_count = (SELECT count(*) FROM finance_cost_collection_entry e
                         WHERE e.tenant_id = p.tenant_id AND e.pool_id = p.id),
         updated_at = now()
   WHERE p.tenant_id = tid
     AND p.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.pool_id ELSE NEW.pool_id END;

  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_collection_entry_totals
  AFTER INSERT OR UPDATE OR DELETE ON finance_cost_collection_entry
  FOR EACH ROW EXECUTE FUNCTION finance_cost_collection_refresh_pool_totals();

-- ===========================================================================
-- 6. 多租户（RLS isolation）+ 授权
-- ===========================================================================

ALTER TABLE finance_cost_collection_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_collection_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_collection_rule
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_collection_pool ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_collection_pool FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_collection_pool
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_collection_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_collection_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_collection_entry
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_collection_basis ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_collection_basis FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_collection_basis
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_collection_rule TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_collection_rule TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_collection_pool TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_collection_pool TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_collection_entry TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_collection_entry TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_collection_basis TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_collection_basis TO starcon_app;

-- ===========================================================================
-- 7. 留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.4.2.2 分摊规则（未交付）**：本模块产出的是**待分摊的费用池**——
--     池标识、维度、归属期间、币种、条目与依据**全部落库**，
--     下游读 `finance_cost_collection_pool` / `..._entry` 即可开始分摊，
--     **不必再抄一份金额**。下游分摊结果写进它自己的表，
--     本模块的 `allocation_available` / `allocated_amount` 由它在自己的迁移里放宽。
--   * **13 利润（未交付）**：费用池是它的一处上游输入；本模块**不产出任何利润数字**。
--   * **12.1.2.1 应付（已交付）**：物流费与头程费在那边可能同时形成**应付**（欠承运商的钱）。
--     应付是**负债口径**、费用池是**归集口径**，两者不是同一笔钱的方向，
--     不构成重复记账 —— 但界面上必须分开显示，不许把「应付金额」当成「已归集金额」。
