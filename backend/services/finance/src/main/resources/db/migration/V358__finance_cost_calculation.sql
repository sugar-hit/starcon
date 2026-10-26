-- ===========================================================================
-- V358 · 12.4.1.2 成本计算执行
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.4.1.2 成本计算执行）原文：
--
--   职责：执行成本计算：对销售出库、发货、平台仓转出计算应结转成本，
--         生成成本层与成本分录；支持按期间批量重算与差异对比。
--   输入：库存流水（7.1.2.1）；计价方法；成本层数据；计算期间。
--   输出：成本计算结果（成本层 + 结转金额）+ 差异对比。
--   关系：上游 = 7 库存流水、12.4.1.1；下游 = 13 利润、12.5.2 凭证。
--         **数量账在 warehouse、价值账在 finance**——这是两个服务的职责划分，重算不能写库存。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移做什么 / 不做什么（先把边界钉死，再写 DDL）
-- ---------------------------------------------------------------------------
--
--   本模块是**成本计算的执行面**：把「这段时间里卖出去的货，按当时生效的计价方法
--   应该结转多少成本」**算出来并落成账**。
--
--   **本模块写三张表**（全部新建，零 ALTER 零 ADD COLUMN 到别人的表）：
--     finance_cost_calculation_run     成本计算批次（表头：期间 / 范围 / 方法 / 汇总）
--     finance_cost_calculation_layer   成本层（哪一层被消耗了多少、单价多少）
--     finance_cost_calculation_entry   成本分录（每一笔出库应结转多少成本）
--
--   **本模块不写的**：
--     · **不写库存**（warehouse 的 inventory_transaction / inventory_entry 是数量账，
--       本模块只**读**，一条都不改 —— AGENTS.md 的跨服务纪律 + 蓝图那句「重算不能写库存」）；
--     · **不建凭证**（12.5.2 凭证已交付，本模块只产出金额，由下游取）；
--     · **不建利润**（13 未交付）；
--     · **不建差异分析报表**（12.4.1.3 成本差异分析未交付；本模块的「差异对比」
--       是**两次计算批次之间**的逐 SKU 对比，见下）。
--
-- ---------------------------------------------------------------------------
-- 1. 12.4.1.1 留给本模块的三个位置，本模块全部接上
-- ---------------------------------------------------------------------------
--
--   12.4.1.1（V355）刻意**没有**建成本层表、**没有**建结转金额表、
--   **没有**写任何重算结果 ⇒ 「已重算」这个状态在 12.4.1.1 那侧**结构上就写不出来**。
--   ⇒ 本模块接三处：
--     (a) **成本层与结转金额表由本模块建**（上面三张）；
--     (b) `finance_cost_valuation_method.recalculation_status` 是「需要重算」的**标记**，
--         V355 的注释已写明「12.4.1.2 落地后由它自己把标记改掉」——
--         本模块在**重算成功**后把该范围的 ACTIVE 版本由 'PENDING' 改回 'NOT_REQUIRED'。
--         ⚠️ 这是**改已有列的取值**，不是 ALTER：V355 的 CHECK 只允许
--         ('NOT_REQUIRED','PENDING') 两个值，本模块**只用这两个值**，不改表结构；
--         且 V355 的历史不可改写触发器**不覆盖** recalculation_status，改它是允许的。
--     (c) 计价方法本身**不抄一份**：本模块读 `finance_cost_valuation_method` 里
--         「落在期间内的那一版」（同服务同库，直接查），把方法 + 版本号 + 配置行 ID
--         **记进批次行**（快照），这样半年后仍能回答「这批成本当时是用哪一版方法算的」。
--
-- ---------------------------------------------------------------------------
-- 2. 三张表：批次（表头） → 成本层 → 成本分录
-- ---------------------------------------------------------------------------
--
--   finance_cost_calculation_run     一行 = 一次成本计算（一个期间 + 一个范围 + 一个方法）
--   finance_cost_calculation_layer   一行 = 一个 SKU 的一层成本被消耗了多少
--   finance_cost_calculation_entry   一行 = 一笔出库的一个 SKU 应结转多少成本
--
--   为什么拆三张而不是一张宽表：
--     · **成本层**是「按批次扣减」的口径（一层被 N 笔出库分摊），
--       **成本分录**是「按出库记账」的口径（一笔出库消耗了 M 层）。
--       两者是**多对多**的两端，压进一张表就必然要么重复要么丢失；
--     · 差异对比要按 **SKU 聚合**（同一 SKU 在期间内的总结转成本），
--       这个数**不在任何一行里** —— 它是 Σ 分录。
--       ⇒ 聚合由服务层**现算**（从两批分录里 Σ 出来），**不建第四张「差异」表**：
--       再存一份就是第二个真相来源，两边对不上时反而不知道该信谁。
--
-- ---------------------------------------------------------------------------
-- 3. 跨服务引用：只存逻辑 ID + 名称快照，**不建 FK、不建影子表**
-- ---------------------------------------------------------------------------
--
--   · **库存流水**来自 warehouse 7.1.2.1（inventory_transaction / inventory_entry）——
--     AGENTS.md 禁止跨服务 SQL / 跨库 FK ⇒ 本模块经**授权只读端点**
--     `GET /warehouse/inventory/ledger` 读，分录行里存
--     `flow_transaction_id` / `flow_ref_type` / `flow_ref_id`（逻辑引用）；
--   · **成本层**来自 catalog 5.2.1.1 成本构成（真实到货记录派生的批次清单）——
--     经只读端口 `GET /catalog/sku-cost-compositions/{skuId}` 读；
--   · **计价范围**（法人 / 品类）来自 identity / catalog —— 逻辑引用 + 名称快照，
--     与 12.4.1.1 口径完全一致（跨服务只读，不建影子表）。
--
-- ---------------------------------------------------------------------------
-- 4. 重算链：重算 = **另起一个批次**，旧批次被取代但**不被改写**
-- ---------------------------------------------------------------------------
--
--   「切换方法需重算」的重算**不改旧数字** —— 旧批次是那次计算的证据。
--   ⇒ 重算产生**新批次**（新 id），并把旧批次的 `superseded_by_run_id` 指过去；
--     新批次的 `supersedes_run_id` 指回旧批次 ⇒ 重算链可追。
--   ⇒ 「已完成的批次不可改写」由触发器钉死（见下 §5 (b)(c)）。
--
-- ---------------------------------------------------------------------------
-- 5. 数据层不变量（把蓝图的话变成数据层做不到的事）
-- ---------------------------------------------------------------------------
--
--   (a) **覆盖量 + 缺口量 = 出库量**（触发器钉死）。
--       出库的量必须被成本层**完整解释**：要么被覆盖（算出了成本），
--       要么明确是缺口（库存不足 / 上游层不够）。缺口被写成 0 就会让
--       「按 FIFO 结转 1200.00」这种半截成本看起来像完整成本。
--   (b) **已完成的批次不可改写**（触发器钉死）：重算请另起批次。
--   (c) **已完成批次的成本层与成本分录不可改写 / 不可删除**（触发器钉死）：
--       历史即证据。
--   (d) **有缺口 ⇒ 状态必须是 INCOMPLETE 且不给单价**（CHECK 钉死）：
--       半截成本不能被显示成完整单价。
--
-- ---------------------------------------------------------------------------
-- 6. 金额与数量：BigDecimal + NUMERIC，禁浮点（AGENTS.md）
-- ---------------------------------------------------------------------------
--
--   金额 NUMERIC(19,4)（与同服务 V351 / V355 一致）；数量 NUMERIC(19,6)。
--   时间列一律 timestamptz + OffsetDateTime 绑定 + `?::timestamptz`；
--   到货日是日历日，用 `date`。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 成本计算批次（表头）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_calculation_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 计价范围：按法人 / 按品类（与 12.4.1.1 的范围口径完全一致）。
  scope_type varchar(16) NOT NULL,
  -- 跨服务逻辑引用（identity 法人 / catalog 品类），**不建 FK**（AGENTS.md）。
  scope_ref_id uuid NOT NULL,
  scope_code varchar(64) NOT NULL DEFAULT '',
  -- 名称快照：计算当时的范围名称（改名不改历史）。
  scope_name varchar(160) NOT NULL DEFAULT '',

  -- 本批次用的计价方法（先进先出 / 移动加权平均 / 批次成本 / 指定成本法）。
  valuation_method varchar(24) NOT NULL,
  -- 当时**生效的那一版**配置（12.4.1.1 的版本链）—— 快照，写进历史行。
  -- 🔴 可为空：该范围在期间起点**没有生效的配置**时为空（此时方法由本次请求显式指定）。
  valuation_method_version integer,
  -- 12.4.1.1 的配置行 ID（逻辑引用，同服务同库也不建 FK —— 表结构可演进）。
  valuation_method_config_id uuid,
  -- 计价币种（成本以哪个币种计量；**绝不跨币种相加**）。
  currency char(3) NOT NULL,

  -- 计算期间（timestamptz，左闭右开：from 含、to 不含）。
  period_from timestamptz NOT NULL,
  period_to timestamptz NOT NULL,

  -- 批次类型：首次计算 / 方法切换后的重算 / 人工重算。
  recalculation_reason varchar(24) NOT NULL DEFAULT 'INITIAL',
  -- 状态：PENDING → COMPLETED / FAILED。
  status varchar(16) NOT NULL DEFAULT 'PENDING',

  -- 上游来源：数量账来自 warehouse 7.1.2.1，成本层来自 catalog 5.2.1.1。
  flow_source_service varchar(32) NOT NULL DEFAULT 'warehouse',
  layer_source_service varchar(32) NOT NULL DEFAULT 'catalog',

  -- 口径说明（随结果一起返回，避免半年后没人说得清口径）。
  basis_note text NOT NULL DEFAULT '',
  -- 来源完整性说明（如实写：上游读不到 / 排除了什么 / 缺口多少）。
  source_note text NOT NULL DEFAULT '',

  -- 汇总（算完写回；未算完是 0）。
  sku_count integer NOT NULL DEFAULT 0,
  layer_count integer NOT NULL DEFAULT 0,
  entry_count integer NOT NULL DEFAULT 0,
  -- 覆盖不全的分录条数（有缺口 ⇒ 成本不完整，必须能被看见）。
  incomplete_entry_count integer NOT NULL DEFAULT 0,
  -- 期间出库总量（Σ 出库数量）。
  total_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 已结转成本的数量（Σ 被成本层覆盖的数量）—— 与 total_quantity 的差就是缺口。
  total_covered_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 缺口总量（Σ 缺口数量）。
  total_shortfall_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 应结转成本总额（Σ 结转金额）。
  total_cost_amount numeric(19,4) NOT NULL DEFAULT 0,

  -- 重算链：重算 = 另起批次，旧批次被取代但**不被改写**。
  supersedes_run_id uuid,
  superseded_by_run_id uuid,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,

  UNIQUE(tenant_id, id),

  FOREIGN KEY (tenant_id, supersedes_run_id) REFERENCES finance_cost_calculation_run(tenant_id, id),
  FOREIGN KEY (tenant_id, superseded_by_run_id) REFERENCES finance_cost_calculation_run(tenant_id, id),

  CHECK(scope_type IN ('LEGAL_ENTITY','CATEGORY')),
  CHECK(valuation_method IN ('FIFO','MOVING_AVERAGE','BATCH','SPECIFIED')),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(period_to > period_from),
  CHECK(status IN ('PENDING','COMPLETED','FAILED')),
  CHECK(recalculation_reason IN ('INITIAL','METHOD_SWITCH','MANUAL')),
  CHECK(valuation_method_version IS NULL OR valuation_method_version >= 1),
  CHECK(sku_count >= 0 AND layer_count >= 0 AND entry_count >= 0 AND incomplete_entry_count >= 0),
  CHECK(total_quantity >= 0 AND total_cost_amount >= 0),
  CHECK(total_covered_quantity >= 0 AND total_shortfall_quantity >= 0),
  -- 完成了就必须有完成时刻（否则「算完了」这件事没有时间证据）。
  CHECK(status <> 'COMPLETED' OR completed_at IS NOT NULL)
);

CREATE INDEX finance_cost_calculation_run_by_period
  ON finance_cost_calculation_run(tenant_id, period_from DESC, period_to);
CREATE INDEX finance_cost_calculation_run_by_scope
  ON finance_cost_calculation_run(tenant_id, scope_type, scope_ref_id, period_from DESC);
CREATE INDEX finance_cost_calculation_run_by_method
  ON finance_cost_calculation_run(tenant_id, valuation_method, period_from DESC);
CREATE INDEX finance_cost_calculation_run_by_status
  ON finance_cost_calculation_run(tenant_id, status, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. 成本层（按批次扣减的口径）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_calculation_layer (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,

  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  -- 该 SKU 在本批次内的层序号（同一批次内唯一，代价是层名可以重复）。
  layer_index integer NOT NULL,

  -- 来源批次（catalog 5.2.1.1 的 lotRef）与到货日。
  lot_ref varchar(64) NOT NULL DEFAULT '',
  received_date date,

  -- 该层**可消耗**的数量 / 金额（窗口内的真实到货记录，来自 catalog 5.2.1.1）。
  layer_quantity numeric(19,6) NOT NULL,
  layer_amount numeric(19,4) NOT NULL,
  -- 本批次结转时**实际采用**的单价。
  --   · FIFO / BATCH = 该层自己的进价；
  --   · MOVING_AVERAGE = 消耗那一刻的移动加权平均价；
  --   · SPECIFIED = 12.4.1.1 维护的指定单价。
  -- ⚠️ 所以这一列**不等于** layer_amount / layer_quantity —— 移动加权平均与指定成本法
  --   本来就不是「按这一层的进价」结转的。原始进价在 layer_amount 里查得到。
  unit_cost numeric(19,4) NOT NULL,

  -- 本批次**实际消耗**的数量 / 金额。
  consumed_quantity numeric(19,6) NOT NULL,
  consumed_amount numeric(19,4) NOT NULL,

  currency char(3) NOT NULL,
  layer_source_service varchar(32) NOT NULL DEFAULT 'catalog',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一批次里，同一个 SKU 的同一层只有一行（幂等锚点）。
  UNIQUE(tenant_id, run_id, sku_id, layer_index),

  FOREIGN KEY (tenant_id, run_id) REFERENCES finance_cost_calculation_run(tenant_id, id),

  CHECK(layer_index >= 0),
  CHECK(layer_quantity > 0),
  CHECK(layer_amount >= 0),
  CHECK(unit_cost >= 0),
  -- 消耗量必须为正：没有消耗的层不写进来（写进来会让「层数」虚高）。
  CHECK(consumed_quantity > 0),
  CHECK(consumed_amount >= 0),
  -- 消耗不得超过该层可消耗量。
  CHECK(round(consumed_quantity, 6) <= round(layer_quantity, 6)),
  CHECK(currency ~ '^[A-Z]{3}$')
);

CREATE INDEX finance_cost_calculation_layer_by_run
  ON finance_cost_calculation_layer(tenant_id, run_id);
CREATE INDEX finance_cost_calculation_layer_by_sku
  ON finance_cost_calculation_layer(tenant_id, sku_id, run_id);

-- ---------------------------------------------------------------------------
-- 3. 成本分录（按出库记账的口径：应结转成本）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_calculation_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,

  -- 上游库存流水（warehouse 7.1.2.1）—— 跨服务逻辑引用，**不建 FK**。
  flow_transaction_id uuid NOT NULL,
  flow_ref_type varchar(32) NOT NULL DEFAULT '',
  flow_ref_id uuid,
  flow_kind varchar(24) NOT NULL,
  occurred_at timestamptz NOT NULL,

  -- 业务类型：销售出库 / 发货 / 平台仓转出（蓝图点名的三类）。
  business_type varchar(24) NOT NULL,

  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',

  -- 出库数量（来自 warehouse 的真实带符号分录，取绝对值）。
  issue_quantity numeric(19,6) NOT NULL,
  -- 被成本层覆盖的数量 / 缺口数量（两者之和 = 出库量，由触发器钉死）。
  covered_quantity numeric(19,6) NOT NULL,
  shortfall_quantity numeric(19,6) NOT NULL,

  -- 单位成本（**覆盖不全时为 NULL** —— 半截成本不能当成完整单价）。
  unit_cost numeric(19,4),
  -- 应结转金额（= 被覆盖部分的成本）。
  cost_amount numeric(19,4) NOT NULL,

  currency char(3) NOT NULL,
  -- 本笔出库消耗了几层成本。
  layer_count integer NOT NULL DEFAULT 0,
  -- 状态：CALCULATED（完整）/ INCOMPLETE（有缺口）。
  status varchar(16) NOT NULL DEFAULT 'CALCULATED',
  -- 缺口原因（如实写，不留给下游猜）。
  gap_note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 幂等：同一批次里，同一笔出库的同一个 SKU 只有一行。
  UNIQUE(tenant_id, run_id, flow_transaction_id, sku_id),

  FOREIGN KEY (tenant_id, run_id) REFERENCES finance_cost_calculation_run(tenant_id, id),

  CHECK(issue_quantity > 0),
  CHECK(covered_quantity >= 0),
  CHECK(shortfall_quantity >= 0),
  CHECK(cost_amount >= 0),
  CHECK(unit_cost IS NULL OR unit_cost >= 0),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(layer_count >= 0),
  CHECK(business_type IN ('SALES_OUTBOUND','SHIPMENT','PLATFORM_TRANSFER_OUT')),
  CHECK(status IN ('CALCULATED','INCOMPLETE')),
  -- 有缺口 ⇒ 状态必须是 INCOMPLETE 且**不给单价**（缺口必须能被看见）。
  CHECK((shortfall_quantity = 0 AND status = 'CALCULATED' AND unit_cost IS NOT NULL)
     OR (shortfall_quantity > 0 AND status = 'INCOMPLETE' AND unit_cost IS NULL))
);

CREATE INDEX finance_cost_calculation_entry_by_run
  ON finance_cost_calculation_entry(tenant_id, run_id);
CREATE INDEX finance_cost_calculation_entry_by_sku
  ON finance_cost_calculation_entry(tenant_id, sku_id, run_id);
CREATE INDEX finance_cost_calculation_entry_by_flow
  ON finance_cost_calculation_entry(tenant_id, flow_transaction_id);
CREATE INDEX finance_cost_calculation_entry_by_occurred
  ON finance_cost_calculation_entry(tenant_id, occurred_at DESC, id);

-- ===========================================================================
-- 4. 数据层不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 成本分录守卫：覆盖量 + 缺口量 = 出库量
-- ---------------------------------------------------------------------------
--   「这批货的成本算全了没有」是成本计算里最容易被糊弄过去的一格：
--   缺口量被写成 0，界面就会显示「按 FIFO 结转 1200.00」，
--   而实际上库存只够一半 ⇒ 那个数字是假的。
--   ⇒ 等式由 DB 层钉死（与 12.4.1.1 试算的覆盖量 + 缺口量 = 试算量同一纪律）。
CREATE OR REPLACE FUNCTION finance_cost_calculation_entry_guard() RETURNS trigger AS $$
BEGIN
  -- 批次必须存在且属于本租户（FK 是 (tenant_id, run_id) 复合键，跨租户引用会被挡住）。
  PERFORM 1 FROM finance_cost_calculation_run r
   WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.run_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '成本计算批次不存在或不属于本租户' USING ERRCODE = 'foreign_key_violation';
  END IF;

  -- 出库量取自分录本身（触发器里 NEW.issue_quantity 就是权威值）。
  IF round(NEW.covered_quantity + NEW.shortfall_quantity, 6) <> round(NEW.issue_quantity, 6) THEN
    RAISE EXCEPTION '覆盖数量（%）+ 缺口数量（%）必须等于出库数量（%）',
      NEW.covered_quantity, NEW.shortfall_quantity, NEW.issue_quantity USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_calculation_entry_guard
  BEFORE INSERT OR UPDATE ON finance_cost_calculation_entry
  FOR EACH ROW EXECUTE FUNCTION finance_cost_calculation_entry_guard();

-- ---------------------------------------------------------------------------
-- (b) 批次守卫：已完成的批次不可改写（重算请另起批次）
-- ---------------------------------------------------------------------------
--   「切换方法需重算」的重算如果**就地改**旧批次里的数字，
--   那旧批次就不再是「当时算出来的那批」的证据，
--   差异对比也变成「同一个批次跟自己比」。
--   ⇒ 已完成的批次只允许改 `superseded_by_run_id`（重算链）与 `updated_at` 无关列。
CREATE OR REPLACE FUNCTION finance_cost_calculation_run_guard() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'COMPLETED' THEN
    IF NEW.scope_type IS DISTINCT FROM OLD.scope_type
       OR NEW.scope_ref_id IS DISTINCT FROM OLD.scope_ref_id
       OR NEW.scope_code IS DISTINCT FROM OLD.scope_code
       OR NEW.scope_name IS DISTINCT FROM OLD.scope_name
       OR NEW.valuation_method IS DISTINCT FROM OLD.valuation_method
       OR NEW.valuation_method_version IS DISTINCT FROM OLD.valuation_method_version
       OR NEW.valuation_method_config_id IS DISTINCT FROM OLD.valuation_method_config_id
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.period_from IS DISTINCT FROM OLD.period_from
       OR NEW.period_to IS DISTINCT FROM OLD.period_to
       OR NEW.recalculation_reason IS DISTINCT FROM OLD.recalculation_reason
       OR NEW.sku_count IS DISTINCT FROM OLD.sku_count
       OR NEW.layer_count IS DISTINCT FROM OLD.layer_count
       OR NEW.entry_count IS DISTINCT FROM OLD.entry_count
       OR NEW.incomplete_entry_count IS DISTINCT FROM OLD.incomplete_entry_count
       OR NEW.total_quantity IS DISTINCT FROM OLD.total_quantity
       OR NEW.total_cost_amount IS DISTINCT FROM OLD.total_cost_amount
       OR NEW.status IS DISTINCT FROM OLD.status THEN
      RAISE EXCEPTION '已完成的成本计算批次不可改写（重算请另起批次）'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- 完成的时刻只允许写一次（时间证据不能被追认）。
  IF OLD.completed_at IS NOT NULL AND NEW.completed_at IS DISTINCT FROM OLD.completed_at THEN
    RAISE EXCEPTION '成本计算批次的完成时刻不可改写' USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_calculation_run_guard
  BEFORE UPDATE ON finance_cost_calculation_run
  FOR EACH ROW EXECUTE FUNCTION finance_cost_calculation_run_guard();

-- ---------------------------------------------------------------------------
-- (c) 结果守卫：已完成批次的成本层与成本分录不可改写 / 不可删除
-- ---------------------------------------------------------------------------
--   历史即证据：算完的数字改了，就再也说不清「当时结转了多少成本」。
CREATE OR REPLACE FUNCTION finance_cost_calculation_result_guard() RETURNS trigger AS $$
DECLARE
  v_tenant uuid;
  v_run uuid;
  v_status varchar(16);
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_tenant := OLD.tenant_id;
    v_run := OLD.run_id;
  ELSE
    v_tenant := NEW.tenant_id;
    v_run := NEW.run_id;
  END IF;

  SELECT r.status INTO v_status FROM finance_cost_calculation_run r
   WHERE r.tenant_id = v_tenant AND r.id = v_run;

  IF v_status = 'COMPLETED' THEN
    RAISE EXCEPTION '已完成的成本计算结果不可改写或删除（重算请另起批次）'
      USING ERRCODE = 'check_violation';
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_calculation_layer_guard
  BEFORE UPDATE OR DELETE ON finance_cost_calculation_layer
  FOR EACH ROW EXECUTE FUNCTION finance_cost_calculation_result_guard();

CREATE TRIGGER finance_cost_calculation_entry_result_guard
  BEFORE UPDATE OR DELETE ON finance_cost_calculation_entry
  FOR EACH ROW EXECUTE FUNCTION finance_cost_calculation_result_guard();

-- ===========================================================================
-- 5. 多租户（RLS isolation）+ 授权
-- ===========================================================================

ALTER TABLE finance_cost_calculation_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_calculation_run FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_calculation_run
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_calculation_layer ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_calculation_layer FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_calculation_layer
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_calculation_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_calculation_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_calculation_entry
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_calculation_run TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_calculation_run TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_calculation_layer TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_calculation_layer TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_calculation_entry TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_calculation_entry TO starcon_app;

-- ===========================================================================
-- 6. 留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13 利润（未交付）**：本模块产出**应结转成本**（按 SKU、按期间），
--     利润自己去取 —— 本模块**不产出任何利润数字**。
--   * **12.5.2 凭证（已交付）**：凭证取本模块的 `finance_cost_calculation_entry.cost_amount`
--     作为借方金额。⚠️ 本模块**不写凭证**（跨模块写入 = 第二个写入所有者）。
--   * **12.4.1.3 成本差异分析（未交付）**：它的输入是本模块的批次 + 分录；
--     本模块的「差异对比」是**两次批次之间**的逐 SKU 对比（服务层现算，
--     **不落第四张表**），**不是**差异分析报表。
--   * **12.4.1.1 计价方法配置（已交付）**：本模块读它的生效版本；
--     重算成功后把该范围 ACTIVE 版本的 `recalculation_status` 由
--     'PENDING' 改回 'NOT_REQUIRED'（**只改取值，不改表结构**）。
