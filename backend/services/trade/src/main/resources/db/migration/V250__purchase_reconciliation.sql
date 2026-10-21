-- ===========================================================================
-- V250 —— 6.5.1.1 采购对账（系统 06 采购与供应 / 6.5 采购结算）
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/06-purchase-and-supply.md §6.5.1.1）：
--
--   职责：采购侧发起与供应商的对账：按期间汇总应收数量、单价、金额、代扣代缴费用、索赔，
--         生成对账单发给供应商确认，差异挂起。
--   输入：期间内的收货记录、质检扣量、索赔；供应商确认回执。
--   输出：对账单（明细 + 汇总）；差异清单；确认状态。
--   关系：上游 = 8 收货、6.4.2 质检；下游 = 12 财务（应付核对与付款）。
--         **对账由采购发起、12 财务执行付款**，两侧数据以对账单为交接点，
--         避免采购擅自确认应付。
--
-- ---------------------------------------------------------------------------
-- 一、本模块的五条硬纪律
-- ---------------------------------------------------------------------------
--
--   1. **对账基数只取自已交付事实**。本模块**不**建「收货影子表」、**不**建采购单影子表：
--        应收基数取 **6.3.2.2 到货流水**（`purchase_arrival_receipt` ⋈
--        `purchase_arrival_tracking`，同库只读），索赔取 **11.2.3.3**（`supplier_claim*`，同库只读）。
--        6.3.1.x 采购单未交付 ⇒ 本模块**不**以采购单为基数，也不为它建表。
--        6.4.2.1 质检单**同批并行开发、此刻不存在** ⇒ 质检扣量以
--        `quality_deduction_status='UNAVAILABLE'` + `quality_deduction_quantity IS NULL`
--        显式标注，**绝不按 0 计入、绝不假造**。
--        12 财务服务**不存在** ⇒ `finance_handoff_status` 恒为 'PENDING_FINANCE_SERVICE'，
--        本模块**没有**「应付已确认 / 已付款」这类状态，也**没有**对应端点。
--
--   2. **采购侧不确认应付**。表里只有 `suggested_payable_amount`（采购侧建议数），
--        没有 `payable_confirmed_amount` / `paid_amount` / `payment_status`。
--        交接给 12 财务的载体就是**对账单本身**。
--
--   3. **币种 + 汇率快照 + 单位换算必录**（卷 06 硬约束）。
--        每行带 `currency` + `fx_rate` + `fx_rate_source` + `fx_rate_at`，
--        折算后的 `statement_amount` 由 DB 触发器算（`amount × fx_rate`）。
--        **跨币种绝不按 1:1 折算** —— 触发器拒绝「币种与对账单币种不同却按
--        SAME_CURRENCY 记 1:1」的行。每行还带 `quantity` / `unit_code` /
--        `unit_conversion` / `base_quantity`（基础单位数量由 DB 算）。
--
--   4. **期间语义由服务端裁决、DB 兜底**（`purchase_reconciliation_period_ok`）：
--        月度 = 某月 1 号到月末；季度 = 季首月 1 号到季末月末。
--        对不上 ⇒ 服务层 400、DB CHECK 同样拒收 ⇒ **不会悄悄改写**。
--
--   5. **差异要成行可查**：与供应商回执不一致的差异**逐条**落一行
--        （`purchase_reconciliation_discrepancy`），记清差在哪个字段、我方值、
--        供应商值、差额；差异可**挂起**（HELD）后处理，不聚合成一个差额总数。
--
-- ---------------------------------------------------------------------------
-- 二、跨车道纪律（回归面 = 0）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V188 / V235 / V238 / V242 的表一个字节都不碰
--     —— 没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖。
--   * **只 SELECT 别人的表**：`purchase_arrival_tracking` / `purchase_arrival_receipt` /
--     `supplier_claim` / `supplier_claim_item` 全部只读，**不建外键到它们**
--     （它们的写入所有权分别是 6.3.2.2 与 11.2.3.3）。
--   * **不建跨服务外键**：供应商主数据在 catalog 的独立库里 ⇒ 供应商只存 id + 快照。
--   * 新增函数全部带 `purchase_reconciliation_` 前缀，与既有函数名不冲突。
-- ===========================================================================

-- ===========================================================================
-- 1. 期间语义的唯一裁决口径（月度 / 季度）
-- ===========================================================================
CREATE OR REPLACE FUNCTION purchase_reconciliation_period_ok(
  p_type text, p_start date, p_end date) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE p_type
    WHEN 'MONTHLY' THEN
      p_start = date_trunc('month', p_start)::date
      AND p_end = (date_trunc('month', p_start) + interval '1 month - 1 day')::date
    WHEN 'QUARTERLY' THEN
      p_start = date_trunc('quarter', p_start)::date
      AND p_end = (date_trunc('quarter', p_start) + interval '3 months - 1 day')::date
    ELSE false
  END;
$$;

COMMENT ON FUNCTION purchase_reconciliation_period_ok(text, date, date) IS
  '6.5.1.1 对账期间的唯一口径：月度 = 某月 1 号到月末；季度 = 季首月 1 号到季末月末。对不上就拒收，不悄悄改写。';

-- ===========================================================================
-- 2. 对账单（头）
-- ===========================================================================
CREATE TABLE purchase_reconciliation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 对账单号：租户内唯一、可读（PR-20261101-a1b2c3d4）。
  recon_no varchar(64) NOT NULL CHECK(btrim(recon_no) <> ''),

  -- ---- 供应商：catalog 独立库 ⇒ 逻辑引用 + 展示快照（不建跨库外键）----
  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 期间：语义由 purchase_reconciliation_period_ok 钉死 ----
  period_type text NOT NULL CHECK(period_type IN ('MONTHLY','QUARTERLY')),
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- ---- 对账单币种：所有行按 fx_rate 折算到它（跨币种绝不 1:1）----
  statement_currency char(3) NOT NULL CHECK(statement_currency ~ '^[A-Z]{3}$'),

  -- ---- 状态机：DRAFT 草稿 → SENT 已发送 → DISPUTED 有差异 / CONFIRMED 已确认 → CLOSED 已关闭 ----
  --      ⚠️ **刻意没有**「应付已确认 / 已付款」：付款是 12 财务的事，采购侧不确认应付。
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','SENT','DISPUTED','CONFIRMED','CLOSED')),

  -- ---- 汇总口径（全部由 DB 触发器从明细行重算，调用方传不进来）----
  -- 已到货应收（对账单币种口径）。
  received_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(received_amount >= 0),
  -- 代扣代缴费用（对账单币种口径）。
  deduction_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(deduction_amount >= 0),
  -- 索赔（对账单币种口径，冲减）。
  claim_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(claim_amount >= 0),
  -- 采购侧**建议**应付数 = 已到货应收 − 代扣代缴 − 索赔。
  -- ⚠️ 名字里有 SUGGESTED：这是交给 12 财务的建议数，**不是应付事实**。
  suggested_payable_amount numeric(19,4) NOT NULL DEFAULT 0,
  -- 已到货数量合计（基础单位口径）。
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity >= 0),

  -- ---- 基数来源口径（写清对账基数取自哪些已交付事实）----
  receipt_basis_source varchar(64) NOT NULL DEFAULT 'PURCHASE_ARRIVAL_6.3.2.2',
  claim_basis_source varchar(64) NOT NULL DEFAULT 'SUPPLIER_CLAIM_11.2.3.3',

  -- ---- 未接线事实的显式标注 ----
  -- 质检扣量（6.4.2.1）未交付 ⇒ false，**不按 0 计入**。
  quality_basis_available boolean NOT NULL DEFAULT false,
  -- 12 财务服务不存在 ⇒ 恒为 PENDING_FINANCE_SERVICE（表级 CHECK 只允许这一个值，
  -- 即：本模块**没有任何路径**能把交接状态写成「财务已确认」）。
  finance_handoff_status text NOT NULL DEFAULT 'PENDING_FINANCE_SERVICE'
    CHECK(finance_handoff_status IN ('PENDING_FINANCE_SERVICE')),

  -- ---- 供应商确认回执（最新一条的摘要；明细在 purchase_reconciliation_confirmation）----
  supplier_confirmed_at timestamptz,
  supplier_reference varchar(128) NOT NULL DEFAULT '',
  open_discrepancy_count int NOT NULL DEFAULT 0 CHECK(open_discrepancy_count >= 0),

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,recon_no),

  -- 期间必须与期间类型自洽（DB 兜底，服务层同样 400）。
  CONSTRAINT purchase_reconciliation_period_consistent CHECK(
    purchase_reconciliation_period_ok(period_type, period_start, period_end)
  ),
  -- 关闭必须留确认时刻与供应商回执号：没有回执就「已确认」等于自己替供应商签字。
  CONSTRAINT purchase_reconciliation_closed_consistent CHECK(
    status <> 'CONFIRMED' OR supplier_confirmed_at IS NOT NULL
  ),
  CONSTRAINT purchase_reconciliation_closed_state CHECK(
    status <> 'CLOSED' OR supplier_confirmed_at IS NOT NULL
  )
);

COMMENT ON TABLE purchase_reconciliation IS
  '6.5.1.1 采购对账单：采购侧按期间与供应商发起，输出明细 + 汇总 + 差异 + 确认状态；**不确认应付**（付款在 12 财务）';

-- ===========================================================================
-- 3. 对账明细行（明细 + 汇总的来源）
-- ===========================================================================
CREATE TABLE purchase_reconciliation_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  recon_id uuid NOT NULL,
  -- 行号：同一张对账单内唯一（差异清单按行号指认「差在哪一行」）。
  line_no int NOT NULL CHECK(line_no > 0),

  -- ---- 行类型：RECEIPT 已到货应收 / CLAIM 索赔冲减 / DEDUCTION 代扣代缴费用 ----
  line_type text NOT NULL CHECK(line_type IN ('RECEIPT','CLAIM','DEDUCTION')),

  -- ---- 来源（可追溯到具体事实行）----
  -- 来源类型：PURCHASE_ARRIVAL（6.3.2.2 到货流水）/ SUPPLIER_CLAIM（11.2.3.3 索赔明细）
  --           / MANUAL（采购登记的代扣代缴费用）。
  source_type text NOT NULL CHECK(source_type IN ('PURCHASE_ARRIVAL','SUPPLIER_CLAIM','MANUAL')),
  -- 来源单据引用（跟踪单 id / 索赔单 id）与编号（采购单号 / 索赔单号）。
  source_ref_id uuid,
  source_ref_code varchar(64) NOT NULL DEFAULT '',
  -- 来源**事实行**引用（到货流水 id / 索赔明细 id）与仓库收货单号快照。
  source_line_ref_id uuid,
  source_line_ref_code varchar(64) NOT NULL DEFAULT '',
  -- 事实发生时刻（到货时刻 / 索赔登记时刻）：对账按它归期间。
  occurred_at timestamptz NOT NULL,

  -- ---- 数量三件套 + 单位换算（卷 06 硬约束）----
  -- 数量（按 unit_code 计量）。代扣代缴费用行没有数量 ⇒ NULL。
  quantity numeric(19,6) CHECK(quantity IS NULL OR quantity > 0),
  -- 计量单位编码。
  unit_code varchar(32),
  -- **1 个 unit_code = 多少基础单位**。数量与单价同单位时恒为 1；
  -- 跨单位时必须 > 1，且 base_quantity 由 DB 算（不让人自己填基础数量）。
  unit_conversion numeric(19,6),
  -- 折算到基础单位的数量：由触发器算（quantity × unit_conversion）。
  base_quantity numeric(19,6),
  -- 单价（每 1 个 unit_code）。索赔行与费用行没有单价 ⇒ NULL。
  unit_price numeric(19,4) CHECK(unit_price IS NULL OR unit_price >= 0),

  -- ---- 币种 + 汇率快照（跨币种绝不 1:1）----
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 汇率快照：原币 → 对账单币种。必须 > 0。
  fx_rate numeric(19,8) NOT NULL CHECK(fx_rate > 0),
  -- 汇率来源说明（人话，如「财务提供的 11 月结汇汇率」）。跨币种时**必填**。
  fx_rate_source varchar(64) NOT NULL DEFAULT '',
  -- 汇率快照时刻。
  fx_rate_at timestamptz NOT NULL DEFAULT now(),
  -- 原币金额。
  amount numeric(19,4) NOT NULL CHECK(amount >= 0),
  -- 折算到对账单币种的金额：由触发器算（amount × fx_rate）。
  statement_amount numeric(19,4) NOT NULL DEFAULT 0,

  -- ---- 代扣代缴费用 ----
  -- 费用类型：TAX 代扣代缴税费 / WITHHOLDING 代扣 / FREIGHT 代付运费 / REBATE 折让 / OTHER。
  deduction_type varchar(32) CHECK(deduction_type IS NULL
    OR deduction_type IN ('TAX','WITHHOLDING','FREIGHT','REBATE','OTHER')),
  -- 索赔单 id（CLAIM 行）。
  claim_id uuid,

  -- ---- 质检扣量（6.4.2.1 未交付 ⇒ 显式不可用，**不按 0 计入**）----
  -- NOT_APPLICABLE 不适用（索赔行 / 费用行）/ UNAVAILABLE 上游未接线 / APPLIED 已取到扣量。
  quality_deduction_status text NOT NULL DEFAULT 'NOT_APPLICABLE'
    CHECK(quality_deduction_status IN ('NOT_APPLICABLE','UNAVAILABLE','APPLIED')),
  -- 质检扣量数量：UNAVAILABLE 时**必须为 NULL**（0 会被读成「没有扣量」）。
  quality_deduction_quantity numeric(19,6),
  -- 影响说明：未接线时写清「为什么这一行没有扣量」。
  quality_deduction_note text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,recon_id,line_no),
  FOREIGN KEY(tenant_id,recon_id) REFERENCES purchase_reconciliation(tenant_id,id) ON DELETE CASCADE,

  -- 有数量 ⇒ 必须有单位与换算比例；没数量 ⇒ 三者皆 NULL。
  CONSTRAINT purchase_reconciliation_line_quantity_consistent CHECK(
    (quantity IS NULL AND unit_code IS NULL AND unit_conversion IS NULL AND base_quantity IS NULL)
    OR (quantity IS NOT NULL AND unit_code IS NOT NULL AND btrim(unit_code) <> ''
        AND unit_conversion IS NOT NULL AND unit_conversion > 0 AND base_quantity IS NOT NULL)
  ),
  -- 基础数量必须等于数量 × 换算比例（由触发器算，CHECK 兜底）。
  CONSTRAINT purchase_reconciliation_line_base_quantity_consistent CHECK(
    base_quantity IS NULL
    OR base_quantity = round(quantity * unit_conversion, 6)
  ),
  -- 已到货应收行必须带数量、单价与来源事实（不然「这笔应收是怎么来的」答不出来）。
  CONSTRAINT purchase_reconciliation_line_receipt_consistent CHECK(
    line_type <> 'RECEIPT' OR (
      quantity IS NOT NULL AND unit_price IS NOT NULL AND source_type = 'PURCHASE_ARRIVAL'
      AND source_ref_id IS NOT NULL AND source_line_ref_id IS NOT NULL
    )
  ),
  -- 索赔行必须挂到具体的索赔明细上。
  CONSTRAINT purchase_reconciliation_line_claim_consistent CHECK(
    line_type <> 'CLAIM' OR (
      source_type = 'SUPPLIER_CLAIM' AND claim_id IS NOT NULL AND source_line_ref_id IS NOT NULL
    )
  ),
  -- 代扣代缴费用行必须有费用类型，且是采购登记的。
  CONSTRAINT purchase_reconciliation_line_deduction_consistent CHECK(
    line_type <> 'DEDUCTION' OR (source_type = 'MANUAL' AND deduction_type IS NOT NULL)
  ),
  -- 质检扣量未接线 ⇒ 数量必须为 NULL（不是 0）。
  CONSTRAINT purchase_reconciliation_line_quality_consistent CHECK(
    quality_deduction_status <> 'UNAVAILABLE' OR quality_deduction_quantity IS NULL
  ),
  -- 质检扣量已取到 ⇒ 必须有数量与说明。
  CONSTRAINT purchase_reconciliation_line_quality_applied_consistent CHECK(
    quality_deduction_status <> 'APPLIED'
    OR (quality_deduction_quantity IS NOT NULL AND btrim(quality_deduction_note) <> '')
  )
);

COMMENT ON TABLE purchase_reconciliation_line IS
  '6.5.1.1 对账明细行：已到货应收 / 索赔冲减 / 代扣代缴费用；每行带币种、汇率快照与单位换算';

-- ===========================================================================
-- 4. 差异清单（逐条成行，可挂起）
-- ===========================================================================
CREATE TABLE purchase_reconciliation_discrepancy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  recon_id uuid NOT NULL,
  -- 差异落在哪一行；整单级差异（供应商没列这行）时为 NULL。
  line_id uuid,
  line_no int,

  -- 差异类型：QUANTITY 数量 / UNIT_PRICE 单价 / AMOUNT 金额 /
  --           MISSING_LINE 我方有供应商没列 / EXTRA_LINE 供应商有我方没列 /
  --           CURRENCY 币种 / OTHER。
  discrepancy_type text NOT NULL CHECK(discrepancy_type IN (
    'QUANTITY','UNIT_PRICE','AMOUNT','MISSING_LINE','EXTRA_LINE','CURRENCY','OTHER'
  )),
  -- 差在哪个字段（人话，如「到货数量」「单价」）。
  field_name varchar(64) NOT NULL DEFAULT '',
  -- 我方值 / 供应商值 / 差额（差在数量上是数量，差在金额上是金额）。
  our_value numeric(19,6),
  supplier_value numeric(19,6),
  difference numeric(19,6) NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 差异状态：OPEN 待处理 / HELD 挂起 / RESOLVED 已解决。
  status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','HELD','RESOLVED')),
  -- 解决方式（如「按供应商实收数量调整」「承认我方单价笔误」）。
  resolution varchar(64) NOT NULL DEFAULT '',
  resolution_note text NOT NULL DEFAULT '',
  -- 这条差异**为什么**存在（供应商回执比对出来的差异记「与供应商回执不符」，
  -- 采购主动追加的记具体缘由）。
  note text NOT NULL DEFAULT '',
  resolved_by uuid,
  resolved_at timestamptz,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,recon_id) REFERENCES purchase_reconciliation(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,line_id) REFERENCES purchase_reconciliation_line(tenant_id,id) ON DELETE CASCADE,

  -- 差异必须带差额（差 0 不叫差异）。
  CONSTRAINT purchase_reconciliation_discrepancy_difference_nonzero CHECK(difference <> 0),
  -- 已解决必须留方式、说明、人与时刻。
  CONSTRAINT purchase_reconciliation_discrepancy_resolved_consistent CHECK(
    status <> 'RESOLVED' OR (
      btrim(resolution) <> '' AND btrim(resolution_note) <> ''
      AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL
    )
  ),
  -- 挂在行上的差异必须带上行号（差异清单要能指认「差在哪一行」）。
  CONSTRAINT purchase_reconciliation_discrepancy_line_consistent CHECK(
    (line_id IS NULL AND line_no IS NULL) OR (line_id IS NOT NULL AND line_no IS NOT NULL)
  )
);

COMMENT ON TABLE purchase_reconciliation_discrepancy IS
  '6.5.1.1 对账差异清单：与供应商回执不一致的差异**逐条**成行，可挂起（HELD）后处理，不聚合成一个总数';

-- ===========================================================================
-- 5. 供应商确认回执（追加式）
-- ===========================================================================
CREATE TABLE purchase_reconciliation_confirmation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  recon_id uuid NOT NULL,
  confirmation_no varchar(64) NOT NULL CHECK(btrim(confirmation_no) <> ''),

  -- 供应商答复的时刻与回执号。
  supplier_replied_at timestamptz NOT NULL,
  supplier_replied_by varchar(200) NOT NULL DEFAULT '',
  supplier_reference varchar(128) NOT NULL DEFAULT '',
  -- 供应商答复：CONFIRMED 确认无误 / DIFFERENCES 有差异。
  supplier_result text NOT NULL CHECK(supplier_result IN ('CONFIRMED','DIFFERENCES')),
  -- 供应商确认的金额（他们签的数字）。
  supplier_confirmed_amount numeric(19,4) CHECK(supplier_confirmed_amount IS NULL OR supplier_confirmed_amount >= 0),
  supplier_confirmed_currency char(3) CHECK(supplier_confirmed_currency IS NULL
    OR supplier_confirmed_currency ~ '^[A-Z]{3}$'),
  -- 本次回执产生的差异条数（由服务层写，与差异清单同批落库）。
  discrepancy_count int NOT NULL DEFAULT 0 CHECK(discrepancy_count >= 0),

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,confirmation_no),
  FOREIGN KEY(tenant_id,recon_id) REFERENCES purchase_reconciliation(tenant_id,id) ON DELETE CASCADE
);

COMMENT ON TABLE purchase_reconciliation_confirmation IS
  '6.5.1.1 供应商确认回执：追加式证据链，回执里的逐行供应商值是差异清单的来源';

-- ===========================================================================
-- 6. 留痕（追加式，运行角色的 UPDATE/DELETE 收回）
-- ===========================================================================
CREATE TABLE purchase_reconciliation_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  recon_id uuid NOT NULL,
  event_type varchar(48) NOT NULL CHECK(btrim(event_type) <> ''),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  amount numeric(19,4),
  reason_note text NOT NULL DEFAULT '',
  actor_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,recon_id) REFERENCES purchase_reconciliation(tenant_id,id) ON DELETE CASCADE
);

COMMENT ON TABLE purchase_reconciliation_event IS
  '6.5.1.1 对账留痕：追加式，发给供应商 / 收到回执 / 差异挂起与解决 / 重开都要留痕';

-- ===========================================================================
-- 7. 触发器：折算、基础数量与汇总口径都由 DB 算
-- ===========================================================================
-- 7.1 明细行：算 base_quantity 与 statement_amount；拒绝「跨币种却按 1:1 折算」。
CREATE OR REPLACE FUNCTION purchase_reconciliation_line_compute() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_statement_currency char(3);
BEGIN
  IF NEW.quantity IS NULL THEN
    NEW.base_quantity := NULL;
  ELSE
    NEW.base_quantity := round(NEW.quantity * NEW.unit_conversion, 6);
  END IF;

  NEW.statement_amount := round(NEW.amount * NEW.fx_rate, 4);

  SELECT r.statement_currency INTO v_statement_currency
    FROM purchase_reconciliation r
   WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.recon_id;

  -- 跨币种必须显式记汇率来源：把两种货币按 1:1 加总是最贵的一种错账。
  IF v_statement_currency IS NOT NULL
     AND NEW.currency <> v_statement_currency
     AND btrim(NEW.fx_rate_source) = '' THEN
    RAISE EXCEPTION
      '跨币种对账行必须记录汇率来源（fx_rate_source）：币种 % 与对账单币种 % 不同，不能按 1:1 折算',
      NEW.currency, v_statement_currency;
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER purchase_reconciliation_line_compute_trg
  BEFORE INSERT OR UPDATE ON purchase_reconciliation_line
  FOR EACH ROW EXECUTE FUNCTION purchase_reconciliation_line_compute();

-- 7.2 对账单头：汇总口径从明细行重算（调用方传不进来）。
CREATE OR REPLACE FUNCTION purchase_reconciliation_refresh_totals() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_recon uuid;
  v_tenant uuid;
BEGIN
  -- ⚠️ DELETE 触发器里 NEW 是未赋值记录 ⇒ 必须 COALESCE(NEW.*, OLD.*)，
  --    否则删一行明细时整张表的汇总会算到 NULL 租户上（结果是「汇总永远是 0」）。
  v_recon := COALESCE(NEW.recon_id, OLD.recon_id);
  v_tenant := COALESCE(NEW.tenant_id, OLD.tenant_id);

  UPDATE purchase_reconciliation r
     SET received_amount = COALESCE(t.received, 0),
         deduction_amount = COALESCE(t.deduction, 0),
         claim_amount = COALESCE(t.claim, 0),
         suggested_payable_amount = COALESCE(t.received, 0) - COALESCE(t.deduction, 0) - COALESCE(t.claim, 0),
         total_quantity = COALESCE(t.qty, 0),
         open_discrepancy_count = COALESCE(t.open_diff, 0),
         updated_at = now()
    FROM (
      SELECT
        COALESCE(sum(l.statement_amount) FILTER (WHERE l.line_type = 'RECEIPT'), 0) AS received,
        COALESCE(sum(l.statement_amount) FILTER (WHERE l.line_type = 'DEDUCTION'), 0) AS deduction,
        COALESCE(sum(l.statement_amount) FILTER (WHERE l.line_type = 'CLAIM'), 0) AS claim,
        COALESCE(sum(l.base_quantity) FILTER (WHERE l.line_type = 'RECEIPT'), 0) AS qty,
        (SELECT count(*) FROM purchase_reconciliation_discrepancy d
          WHERE d.tenant_id = v_tenant AND d.recon_id = v_recon
            AND d.status <> 'RESOLVED') AS open_diff
      FROM purchase_reconciliation_line l
      WHERE l.tenant_id = v_tenant AND l.recon_id = v_recon
    ) t
   WHERE r.tenant_id = v_tenant AND r.id = v_recon;

  RETURN NULL;
END;
$$;

CREATE TRIGGER purchase_reconciliation_line_totals_trg
  AFTER INSERT OR UPDATE OR DELETE ON purchase_reconciliation_line
  FOR EACH ROW EXECUTE FUNCTION purchase_reconciliation_refresh_totals();

-- 7.3 差异状态变化 → 头部的未解决差异数同步。
CREATE OR REPLACE FUNCTION purchase_reconciliation_refresh_discrepancy_count() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE purchase_reconciliation r
     SET open_discrepancy_count = (
           SELECT count(*) FROM purchase_reconciliation_discrepancy d
            WHERE d.tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
              AND d.recon_id = COALESCE(NEW.recon_id, OLD.recon_id)
              AND d.status <> 'RESOLVED'),
         updated_at = now()
   WHERE r.tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
     AND r.id = COALESCE(NEW.recon_id, OLD.recon_id);
  RETURN NULL;
END;
$$;

CREATE TRIGGER purchase_reconciliation_discrepancy_count_trg
  AFTER INSERT OR UPDATE OR DELETE ON purchase_reconciliation_discrepancy
  FOR EACH ROW EXECUTE FUNCTION purchase_reconciliation_refresh_discrepancy_count();

-- ===========================================================================
-- 8. 索引
-- ===========================================================================
CREATE INDEX purchase_reconciliation_by_supplier
  ON purchase_reconciliation(tenant_id, supplier_ref_id, period_start desc, id);
CREATE INDEX purchase_reconciliation_by_status
  ON purchase_reconciliation(tenant_id, status, created_at desc, id);
CREATE INDEX purchase_reconciliation_by_period
  ON purchase_reconciliation(tenant_id, period_type, period_start desc, id);
CREATE INDEX purchase_reconciliation_line_by_recon
  ON purchase_reconciliation_line(tenant_id, recon_id, line_no);
CREATE INDEX purchase_reconciliation_line_by_source
  ON purchase_reconciliation_line(tenant_id, source_type, source_ref_id);
CREATE INDEX purchase_reconciliation_discrepancy_by_recon
  ON purchase_reconciliation_discrepancy(tenant_id, recon_id, status, id);
CREATE INDEX purchase_reconciliation_confirmation_by_recon
  ON purchase_reconciliation_confirmation(tenant_id, recon_id, created_at desc, id);
CREATE INDEX purchase_reconciliation_event_by_recon
  ON purchase_reconciliation_event(tenant_id, recon_id, occurred_at desc, id);

-- ===========================================================================
-- 9. 多租户：RLS + 授权（与 trade V1 / V92 / V188 / V235 / V238 / V242 同一套写法）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_reconciliation','purchase_reconciliation_line',
    'purchase_reconciliation_discrepancy','purchase_reconciliation_confirmation',
    'purchase_reconciliation_event'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_reconciliation,purchase_reconciliation_line,purchase_reconciliation_discrepancy,
  purchase_reconciliation_confirmation,purchase_reconciliation_event TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_reconciliation,purchase_reconciliation_line,purchase_reconciliation_discrepancy,
  purchase_reconciliation_confirmation,purchase_reconciliation_event TO starcon_app;

-- 留痕是**追加式**证据链：改写或删除留痕等于改掉「这张对账单是怎么走过来的」。
-- （"授权在前、收回在后"，顺序不能颠倒，否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去。）
REVOKE UPDATE,DELETE ON purchase_reconciliation_event FROM trade_app;
-- 对账单头与明细行**不提供 DELETE**：对账单是要拿去和供应商对质的凭据，
-- 差异清单与回执都指向它，删掉行等于删掉证据。
REVOKE DELETE ON purchase_reconciliation,purchase_reconciliation_line FROM trade_app;
-- 差异条目同样不可删：差异要么挂起、要么解决，没有「删掉就当没发生」。
REVOKE DELETE ON purchase_reconciliation_discrepancy FROM trade_app;
