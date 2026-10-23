-- ===========================================================================
-- V349 · finance_supplier_reconciliation —— 12.1.2.3 供应商对账（财务侧）
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.1.2.3 供应商对账
--   - **职责**：财务侧供应商对账：核对采购侧对账单（6.5.1）与收货/质检数据，
--     差异处理与确认，最终生成应付依据。
--   - **输入**：供应商对账单；收货与质检明细；差异说明。
--   - **输出**：对账确认结果 + 差异清单 + 应付生成依据。
--   - **关系**：上游 = 6.5.1 采购发起；下游 = 12.1.2.1 应付。
--     **财务是最终确认方**，采购不能自行确认应付——这是职责分离。
--
-- 本迁移把最后那句加粗句变成**数据层做不到的事**。
--
-- ---------------------------------------------------------------------------
-- 0. 三条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **采购侧的数据在财务确认前不得变成已确认应付**（蓝图加粗句）
--      * 采购侧对账单（trade 的 `purchase_reconciliation`，6.5.1.1）在本服务里
--        只有**快照**（`finance_supplier_reconciliation*`），不是应付。
--      * 应付单（`finance_payable`，12.1.2.1）**只能**由本模块的「财务确认」动作产生，
--        且必须挂在一条**已裁决完的**对账单上。
--      * 数据层的钉死：
--        - `finance_supplier_reconciliation.status='CONFIRMED'`
--          ⇒ 必须恰好有一条 `finance_supplier_reconciliation_payable`（应付依据），
--            且依据金额 = 确认金额（触发器 guard_finance_supplier_reconciliation_confirm）。
--        - 确认时若还有未裁决差异 ⇒ 触发器直接拒绝。
--        - 应付依据插入时校验：引用的应付单**真的是** `source='SUPPLIER_STATEMENT'`
--          且 `source_reference` = 本对账单号、币种与金额一致、且未作废
--          （触发器 guard_finance_supplier_reconciliation_payable_basis）。
--        ⇒ 「采购侧自己把金额变成了已确认应付」这条路径在**数据库层**写不进去。
--
--   2. **采购发起 / 财务确认是两个动作、两个权限**
--      * 权限码分离在**服务层**（`finance.suppliereconciliation.update` 与
--        `finance.suppliereconciliation.confirm`，见控制器）。
--      * 数据层的分离体现在**状态机**上：`IMPORTED → MATCHED → DIFFERENCES → CONFIRMED`
--        只能由确认动作推进，`CONFIRMED` / `REJECTED` 是**终态**（整单冻结）。
--      * 采购侧能做的：导入对账单（采购发起的事实落到财务侧）、跑核对、登记差异说明。
--      * 财务侧才能做的：裁决差异、最终确认、生成应付依据、驳回。
--
--   3. **依据链要能自证**（与 12.1.1.3 坏账核销同一手法）
--      * 读到的关键值（对账单号 / 金额 / 数量 / 单价 / 汇率 / 状态 / 收货单号 /
--        质检三量）**一律以快照列落库**，不靠事后去查上游表里的当前值。
--      * 上游事实读不到 ⇒ 显式不可用（NULL + 状态标注），**绝不写 0**：
--        写 0 会被读成「没有差异」，而真实情况是「我们还没读到那个事实」。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_supplier_reconciliation           —— 对账单头（财务侧快照 + 状态机）
--   finance_supplier_reconciliation_line      —— 对账明细行（上游快照 + 财务侧核对结果）
--   finance_supplier_reconciliation_inspection—— 质检证据（逐张质检单的快照）
--   finance_supplier_reconciliation_difference—— 差异清单
--   finance_supplier_reconciliation_explanation—— 差异说明（append-only 举证）
--   finance_supplier_reconciliation_event      —— 事件流（append-only 举证）
--   finance_supplier_reconciliation_payable   —— 应付生成依据（对账单 ↔ 应付单）
--
--   七张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)、数量 numeric(19,6)、汇率 numeric(19,8)；**全链路禁浮点**。
--   * 币种 char(3) + CHECK 正则；**任何汇总都按币种分组**（USD 与 EUR 加起来不是任何一个金额）。
--   * 对账单币种（`statement_currency`）是本模块唯一可加总的币种：
--     上游明细行的原币金额一律按**上游自己的汇率快照**折算到对账单币种后才参与汇总。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_supplier_reconciliation —— 对账单头
-- ---------------------------------------------------------------------------
--
-- **这张表是「采购侧结论 ≠ 应付」的物化形态**：它存的是采购侧对账单的**快照**，
-- 不是应付事实。应付事实是 `finance_payable`（12.1.2.1），只有「财务确认」动作能产生。
CREATE TABLE finance_supplier_reconciliation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 财务侧对账单号：SR-20261101-a1b2c3d4（租户内唯一、可读）。
  recon_no varchar(64) NOT NULL CHECK(btrim(recon_no) <> ''),
  -- 上游 6.5.1.1 的对账单 id（trade 的 purchase_reconciliation.id）：**幂等键**。
  upstream_recon_id uuid NOT NULL,
  -- 上游对账单号（快照，便于人读与对账）。
  upstream_recon_no varchar(64) NOT NULL DEFAULT '',

  -- ---- 供应商：catalog 独立库 ⇒ 逻辑引用 + 展示快照（不建跨库外键）----
  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 期间（语义由上游口径决定；这里只快照）----
  period_type text NOT NULL CHECK(period_type IN ('MONTHLY','QUARTERLY')),
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- 对账单币种：**本模块唯一可加总的币种**。
  statement_currency char(3) NOT NULL CHECK(statement_currency ~ '^[A-Z]{3}$'),

  -- 状态机：
  --   IMPORTED   已导入（采购侧对账单的快照落库）
  --   MATCHED    已核对（收货与质检都核过一遍）
  --   DIFFERENCES 有差异待裁决
  --   CONFIRMED  财务已确认（**终态**：已生成应付依据，整单冻结）
  --   REJECTED   财务已驳回（**终态**，整单冻结）
  status text NOT NULL DEFAULT 'IMPORTED'
    CHECK(status IN ('IMPORTED','MATCHED','DIFFERENCES','CONFIRMED','REJECTED')),

  -- 上游状态快照（DRAFT / SENT / DISPUTED / CONFIRMED / CLOSED）。
  -- ⚠️ 只有上游已由供应商确认（CONFIRMED / CLOSED）的对账单才可被财务导入：
  --    草稿与「已发送未回执」都不是一份最终的对账单。
  upstream_status varchar(16) NOT NULL DEFAULT '',

  -- ---- 采购侧口径（快照，来自上游对账单）----
  statement_received_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(statement_received_amount >= 0),
  statement_deduction_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(statement_deduction_amount >= 0),
  statement_claim_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(statement_claim_amount >= 0),
  -- 上游**建议**的应付数（名字里的 suggested 是上游自己的措辞：它是采购侧的建议，不是应付事实）。
  statement_suggested_amount numeric(19,4) NOT NULL DEFAULT 0,
  statement_total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(statement_total_quantity >= 0),

  -- ---- 财务侧口径（核对之后才有值；核对前恒 NULL，**不填 0**）----
  -- 财务确认的应付金额（对账单币种口径）。
  finance_confirmed_amount numeric(19,4)
    CHECK(finance_confirmed_amount IS NULL OR finance_confirmed_amount >= 0),
  -- 财务确认的数量合计（基础单位口径）。
  finance_confirmed_quantity numeric(19,6)
    CHECK(finance_confirmed_quantity IS NULL OR finance_confirmed_quantity >= 0),
  -- 差异裁决带来的调整合计（有符号：对账单 → 财务口径的差额）。
  finance_adjusted_amount numeric(19,4) NOT NULL DEFAULT 0,

  -- ---- 计数（运营字段，不参与冻结判定）----
  line_count int NOT NULL DEFAULT 0 CHECK(line_count >= 0),
  difference_count int NOT NULL DEFAULT 0 CHECK(difference_count >= 0),
  open_difference_count int NOT NULL DEFAULT 0 CHECK(open_difference_count >= 0),
  matched_line_count int NOT NULL DEFAULT 0 CHECK(matched_line_count >= 0),
  unmatched_line_count int NOT NULL DEFAULT 0 CHECK(unmatched_line_count >= 0),

  -- ---- 核对口径说明（人话）：这次核对用了哪些上游事实 ----
  receipt_basis_source varchar(64) NOT NULL DEFAULT 'PURCHASE_ARRIVAL_6.3.2.2',
  quality_basis_source varchar(64) NOT NULL DEFAULT 'QUALITY_INSPECTION_6.4.2.1',

  -- 上游的供应商确认回执（快照）。
  upstream_supplier_confirmed_at timestamptz,
  upstream_supplier_reference varchar(128) NOT NULL DEFAULT '',

  -- ---- 财务确认 / 驳回（**财务侧专属动作**的留痕）----
  confirmed_by uuid,
  confirmed_at timestamptz,
  rejected_by uuid,
  rejected_at timestamptz,
  reject_reason varchar(500) NOT NULL DEFAULT '',

  -- 生成的应付单（12.1.2.1）：**确认之后**才会有值。
  payable_id uuid,
  payable_no varchar(48) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, recon_no),
  -- 幂等键：同一份上游对账单**只有一张**财务侧对账单（重复导入命中同一条）。
  UNIQUE(tenant_id, upstream_recon_id),
  -- 指向本服务的应付单（12.1.2.1，同库 ⇒ 可建外键）。
  FOREIGN KEY(tenant_id, payable_id) REFERENCES finance_payable(tenant_id, id),

  -- 🔴 状态与留痕字段必须自洽：确认 ⇒ 有确认人 + 确认时刻 + 确认金额 + 应付单；
  --    驳回 ⇒ 有驳回人 + 驳回时刻 + 理由，且**没有**应付单；
  --    其余状态 ⇒ 这些字段一律为空（**确认之前不许预填**）。
  CONSTRAINT finance_supplier_reconciliation_state_consistent CHECK(
    (status = 'CONFIRMED'
      AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL
      AND finance_confirmed_amount IS NOT NULL AND payable_id IS NOT NULL
      AND rejected_by IS NULL AND rejected_at IS NULL AND btrim(reject_reason) = '')
    OR (status = 'REJECTED'
      AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND btrim(reject_reason) <> ''
      AND confirmed_by IS NULL AND confirmed_at IS NULL AND payable_id IS NULL)
    OR (status NOT IN ('CONFIRMED','REJECTED')
      AND confirmed_by IS NULL AND confirmed_at IS NULL
      AND rejected_by IS NULL AND rejected_at IS NULL
      AND payable_id IS NULL AND finance_confirmed_amount IS NULL)
  ),
  CONSTRAINT finance_supplier_reconciliation_period_consistent CHECK(period_end >= period_start)
);

-- ---------------------------------------------------------------------------
-- 2. finance_supplier_reconciliation_line —— 对账明细行
-- ---------------------------------------------------------------------------
--
-- 一行 = 上游对账单的一行**快照** + **财务侧的核对结果**。
--
-- ⚠️ 两侧的值**都留着**（采购侧口径 vs 财务侧口径），差额由差异清单解释 ——
-- 只留一个数就答不出来「差在哪、差多少、谁认这笔账」。
CREATE TABLE finance_supplier_reconciliation_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  recon_id uuid NOT NULL,

  -- 行号（同一张对账单内唯一；差异清单按行号指认「差在哪一行」）。
  line_no int NOT NULL CHECK(line_no > 0),
  -- 上游行 id（trade 的 purchase_reconciliation_line.id）：刷新时按它对齐。
  upstream_line_id uuid NOT NULL,

  -- 行类型（与上游同一份词表）。
  line_type text NOT NULL CHECK(line_type IN ('RECEIPT','CLAIM','DEDUCTION')),
  source_type varchar(32) NOT NULL DEFAULT '',
  source_ref_id uuid,
  source_ref_code varchar(64) NOT NULL DEFAULT '',
  source_line_ref_id uuid,
  -- 对 RECEIPT 行：这是**仓库收货单号**（质检单按它关联）。
  source_line_ref_code varchar(64) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,

  -- ---- 数量三件套 + 单位换算（上游快照）----
  quantity numeric(19,6) CHECK(quantity IS NULL OR quantity > 0),
  unit_code varchar(32),
  unit_conversion numeric(19,6),
  base_quantity numeric(19,6),
  -- 单价快照（每 1 个 unit_code）。索赔行与费用行没有单价 ⇒ NULL。
  unit_price numeric(19,4) CHECK(unit_price IS NULL OR unit_price >= 0),

  -- ---- 币种 + 汇率快照（上游给的，不是本模块算的）----
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  fx_rate numeric(19,8) NOT NULL CHECK(fx_rate > 0),
  fx_rate_source varchar(64) NOT NULL DEFAULT '',
  fx_rate_at timestamptz,
  -- 原币金额（上游口径）。
  amount numeric(19,4) NOT NULL CHECK(amount >= 0),
  -- 折算到对账单币种的金额（上游口径）—— 汇总只加这个。
  statement_amount numeric(19,4) NOT NULL DEFAULT 0,

  deduction_type varchar(32),
  claim_id uuid,

  -- ---- 上游的质检扣量状态（快照）----
  -- 上游 6.5.1.1 未接入 6.4.2.1 ⇒ RECEIPT 行恒为 'UNAVAILABLE'（扣量数量为 NULL，不是 0）。
  upstream_quality_status varchar(16) NOT NULL DEFAULT 'NOT_APPLICABLE'
    CHECK(upstream_quality_status IN ('NOT_APPLICABLE','UNAVAILABLE','APPLIED')),
  upstream_quality_quantity numeric(19,6),
  upstream_quality_note text NOT NULL DEFAULT '',

  -- ---- 财务侧核对结果（核对之前一律 NULL / false，**不填 0**）----
  -- 是否找到了对应的到货事实。
  receipt_matched boolean NOT NULL DEFAULT false,
  -- 到货流水（6.3.2.2）的实到数量。
  receipt_arrived_qty numeric(19,6),
  -- 到货流水挂着的仓库收货单号（质检单按它关联）。
  receipt_warehouse_code varchar(64) NOT NULL DEFAULT '',
  -- 是否找到了质检证据。
  quality_matched boolean NOT NULL DEFAULT false,
  -- 质检状态：NOT_INSPECTED 未找到质检记录 / INSPECTED 已找到 / NOT_APPLICABLE 不适用。
  quality_status varchar(16) NOT NULL DEFAULT 'NOT_INSPECTED'
    CHECK(quality_status IN ('NOT_INSPECTED','INSPECTED','NOT_APPLICABLE')),
  -- 质检扣量合计（上游口径：gross_deducted_quantity = 不合格量 + 让步接收量）。
  quality_deducted_qty numeric(19,6),
  -- 财务侧认定的数量 = 实到数量 − 质检扣量合计。
  finance_quantity numeric(19,6),
  -- 财务侧认定的金额（对账单币种口径）：finance_quantity × 单价 × 汇率。
  finance_amount numeric(19,4),
  -- 与采购侧口径的差额（数量 / 金额）。
  variance_quantity numeric(19,6) NOT NULL DEFAULT 0,
  variance_amount numeric(19,4) NOT NULL DEFAULT 0,

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, recon_id, line_no),
  UNIQUE(tenant_id, recon_id, upstream_line_id),
  FOREIGN KEY(tenant_id, recon_id) REFERENCES finance_supplier_reconciliation(tenant_id, id) ON DELETE CASCADE,

  CONSTRAINT finance_supplier_reconciliation_line_quantity_consistent CHECK(
    (quantity IS NULL AND unit_code IS NULL AND unit_conversion IS NULL AND base_quantity IS NULL)
    OR (quantity IS NOT NULL AND unit_code IS NOT NULL AND btrim(unit_code) <> ''
        AND unit_conversion IS NOT NULL AND unit_conversion > 0 AND base_quantity IS NOT NULL)
  ),
  -- 核对过了 ⇒ 必须有财务侧的数量与金额；没核对 ⇒ 一律 NULL。
  CONSTRAINT finance_supplier_reconciliation_line_finance_consistent CHECK(
    (finance_amount IS NULL AND finance_quantity IS NULL)
    OR (finance_amount IS NOT NULL AND finance_quantity IS NOT NULL AND finance_quantity >= 0)
  ),
  -- 找到质检记录 ⇒ 必须有扣量数量（可能是 0，但**不能是 NULL**：NULL 读成「没核」）。
  CONSTRAINT finance_supplier_reconciliation_line_quality_consistent CHECK(
    quality_matched = false OR quality_deducted_qty IS NOT NULL
  )
);

-- ---------------------------------------------------------------------------
-- 3. finance_supplier_reconciliation_inspection —— 质检证据（逐张质检单的快照）
-- ---------------------------------------------------------------------------
--
-- 质检单（6.4.2.1，trade 的 `quality_inspection_order`）是**上游事实**：
-- 本模块只存快照（不跨库、不跨服务 SQL）。
--
-- 一行 = 一张质检单在一行对账明细上的命中情况。
CREATE TABLE finance_supplier_reconciliation_inspection (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  recon_id uuid NOT NULL,
  line_id uuid NOT NULL,

  -- 上游质检单 id / 单号（快照）。
  inspection_id uuid NOT NULL,
  inspection_no varchar(64) NOT NULL DEFAULT '',
  -- 关联键：仓库收货单号（到货流水挂着的那个）。
  receipt_code varchar(64) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',

  -- 上游三量与扣量（快照）。
  arrived_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(arrived_quantity >= 0),
  qualified_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(qualified_quantity >= 0),
  defective_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(defective_quantity >= 0),
  concession_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(concession_quantity >= 0),
  -- 扣量合计（= 不合格量 + 让步接收量）：**应付要扣的就是它**。
  deducted_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(deducted_quantity >= 0),
  -- 上游算好的扣量金额（快照，供比对；本模块的应付调整额自己按单价 × 汇率算）。
  deducted_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(deducted_amount >= 0),
  currency_code varchar(8) NOT NULL DEFAULT '',

  inspection_status varchar(16) NOT NULL DEFAULT '',
  inspection_result varchar(24) NOT NULL DEFAULT '',
  completed_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一张质检单在一行上只挂一次。
  UNIQUE(tenant_id, line_id, inspection_id),
  FOREIGN KEY(tenant_id, recon_id) REFERENCES finance_supplier_reconciliation(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, line_id) REFERENCES finance_supplier_reconciliation_line(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 4. finance_supplier_reconciliation_difference —— 差异清单
-- ---------------------------------------------------------------------------
--
-- 蓝图输出句之一：「对账确认结果 + **差异清单** + 应付生成依据」。
--
-- **差异的金额口径是「不重不漏」的**：同一行上的多条差异，其 `variance_amount` 之和
-- 必须恰好等于该行的差额 ⇒ 触发器逐条校验。
CREATE TABLE finance_supplier_reconciliation_difference (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  recon_id uuid NOT NULL,
  line_id uuid,
  line_no int NOT NULL DEFAULT 0 CHECK(line_no >= 0),

  -- 差异类型：
  --   RECEIPT_QUANTITY_MISMATCH    收货数量与对账单不符
  --   RECEIPT_EVIDENCE_MISSING     找不到对应的到货事实（依据缺失）
  --   QUALITY_DEDUCTION_NOT_APPLIED 质检扣量未在对账单中扣除
  --   QUALITY_EVIDENCE_MISSING     有仓库收货单号但找不到质检记录（依据缺失）
  --   AMOUNT_RESIDUAL              金额残差（数量与单价都解释不了的那部分）
  difference_type text NOT NULL CHECK(difference_type IN (
    'RECEIPT_QUANTITY_MISMATCH','RECEIPT_EVIDENCE_MISSING',
    'QUALITY_DEDUCTION_NOT_APPLIED','QUALITY_EVIDENCE_MISSING','AMOUNT_RESIDUAL')),

  -- OPEN 未处理 / EXPLAINED 已登记说明但未裁决 / RESOLVED 已裁决。
  status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','EXPLAINED','RESOLVED')),

  -- ---- 差异的两侧（快照）----
  -- 采购侧的值。
  statement_value numeric(19,6),
  -- 财务侧的值。
  finance_value numeric(19,6),
  -- 差额（财务 − 采购）。
  variance_quantity numeric(19,6) NOT NULL DEFAULT 0,
  variance_amount numeric(19,4) NOT NULL DEFAULT 0,
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),

  -- ---- 裁决（**财务侧专属动作**）----
  -- APPLIED 采纳财务侧口径（应付按差额调整） / KEPT 沿用采购侧口径（不调整）。
  resolution varchar(16) CHECK(resolution IS NULL OR resolution IN ('APPLIED','KEPT')),
  -- 调整额：**生成列** ⇒ 裁决与调整额不可能对不上。
  adjustment_amount numeric(19,4) GENERATED ALWAYS AS (
    CASE WHEN resolution = 'APPLIED' THEN variance_amount ELSE 0 END
  ) STORED,
  resolution_note varchar(500) NOT NULL DEFAULT '',
  resolved_by uuid,
  resolved_at timestamptz,

  -- ---- 差异说明（最新一条的摘要；全文在 explanation 表）----
  explanation text NOT NULL DEFAULT '',
  explained_by uuid,
  explained_at timestamptz,

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, recon_id) REFERENCES finance_supplier_reconciliation(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, line_id) REFERENCES finance_supplier_reconciliation_line(tenant_id, id) ON DELETE CASCADE,

  -- 裁决必须完整：已裁决 ⇒ 有裁决方式 + 裁决人 + 裁决时刻 + 裁决说明；
  -- 未裁决 ⇒ 这些字段一律为空。
  CONSTRAINT finance_supplier_reconciliation_diff_resolution_consistent CHECK(
    (status = 'RESOLVED' AND resolution IS NOT NULL AND resolved_by IS NOT NULL
      AND resolved_at IS NOT NULL AND btrim(resolution_note) <> '')
    OR (status <> 'RESOLVED' AND resolution IS NULL AND resolved_by IS NULL AND resolved_at IS NULL)
  )
);

-- ---------------------------------------------------------------------------
-- 5. finance_supplier_reconciliation_explanation —— 差异说明（append-only）
-- ---------------------------------------------------------------------------
--
-- 蓝图输入句之一：「供应商对账单；收货与质检明细；**差异说明**」。
--
-- 说明是**举证**：只能追加，不能改、不能删（授权在前、收回在后）。
CREATE TABLE finance_supplier_reconciliation_explanation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  recon_id uuid NOT NULL,
  difference_id uuid NOT NULL,

  -- 说明正文（必填）。
  explanation text NOT NULL CHECK(btrim(explanation) <> ''),
  -- 说明由谁给出：PURCHASE 采购侧 / FINANCE 财务侧 / SUPPLIER 供应商。
  provided_role varchar(16) NOT NULL DEFAULT 'FINANCE'
    CHECK(provided_role IN ('PURCHASE','FINANCE','SUPPLIER')),
  provided_by uuid NOT NULL,
  -- 依据引用（合同号 / 收货单号 / 质检单号 …）：自由文本，**不建跨服务外键**。
  evidence_ref varchar(128) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, recon_id) REFERENCES finance_supplier_reconciliation(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, difference_id) REFERENCES finance_supplier_reconciliation_difference(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 6. finance_supplier_reconciliation_event —— 事件流（append-only 举证）
-- ---------------------------------------------------------------------------
--
-- 与 12.1.2.1 的 `finance_payable_event` 同一手法：事件流是「当时发生了什么」的**唯一**出处。
CREATE TABLE finance_supplier_reconciliation_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  recon_id uuid NOT NULL,

  event_type text NOT NULL CHECK(event_type IN (
    'IMPORTED','REFRESHED','MATCHED','DIFFERENCE_RAISED','EXPLANATION_ADDED',
    'DIFFERENCE_RESOLVED','CONFIRMED','REJECTED','PAYABLE_GENERATED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 动作方：PURCHASE 采购侧动作 / FINANCE 财务侧动作。
  actor_role varchar(16) NOT NULL DEFAULT 'FINANCE'
    CHECK(actor_role IN ('PURCHASE','FINANCE')),
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, recon_id) REFERENCES finance_supplier_reconciliation(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 7. finance_supplier_reconciliation_payable —— 应付生成依据
-- ---------------------------------------------------------------------------
--
-- 蓝图输出句之一：「对账确认结果 + 差异清单 + **应付生成依据**」。
--
-- 一行 = 一份对账单 → 一张应付单的对应关系（**依据链的最后一环**）。
--
-- 🔴 **一张对账单只能有一张应付**：UNIQUE(tenant_id, recon_id) ⇒
--    「同一份对账单重复确认」不会造出两张应付。
CREATE TABLE finance_supplier_reconciliation_payable (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  recon_id uuid NOT NULL,
  payable_id uuid NOT NULL,
  payable_no varchar(48) NOT NULL,

  -- 应付金额（对账单币种口径）= 采购侧口径 + 裁决调整额。
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 采购侧口径金额与调整额（拆开存 ⇒ 依据链能看出「按采购口径是多少、我们调了多少」）。
  statement_amount numeric(19,4) NOT NULL DEFAULT 0,
  adjustment_amount numeric(19,4) NOT NULL DEFAULT 0,

  -- 依据说明（人话）：这张应付是怎么算出来的。
  basis_note text NOT NULL DEFAULT '',
  generated_by uuid NOT NULL,
  generated_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, recon_id),
  UNIQUE(tenant_id, payable_id),
  FOREIGN KEY(tenant_id, recon_id) REFERENCES finance_supplier_reconciliation(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, payable_id) REFERENCES finance_payable(tenant_id, id),

  -- 金额必须能拆回「采购侧口径 + 调整额」，否则依据链对不上。
  CONSTRAINT finance_supplier_reconciliation_payable_amount_consistent CHECK(
    amount = statement_amount + adjustment_amount
  )
);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 确认 ⇒ 必须恰好有一条应付依据，且金额一致，且没有未裁决差异。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_finance_supplier_reconciliation_confirm() RETURNS trigger AS $$
DECLARE
  basis_count int;
  basis_amount numeric(19,4);
  open_diff int;
BEGIN
  -- 终态冻结：CONFIRMED / REJECTED 之后，快照字段与状态一律不许改。
  -- ⚠️ 只冻结**快照与状态**字段：计数（difference_count / open_difference_count …）
  --    是运营字段，DB 触发器派生它们时仍要能写进去。
  IF OLD.status IN ('CONFIRMED','REJECTED') THEN
    IF NEW.status IS DISTINCT FROM OLD.status
       OR NEW.recon_no IS DISTINCT FROM OLD.recon_no
       OR NEW.upstream_recon_id IS DISTINCT FROM OLD.upstream_recon_id
       OR NEW.upstream_recon_no IS DISTINCT FROM OLD.upstream_recon_no
       OR NEW.supplier_ref_id IS DISTINCT FROM OLD.supplier_ref_id
       OR NEW.supplier_code IS DISTINCT FROM OLD.supplier_code
       OR NEW.supplier_name IS DISTINCT FROM OLD.supplier_name
       OR NEW.period_type IS DISTINCT FROM OLD.period_type
       OR NEW.period_start IS DISTINCT FROM OLD.period_start
       OR NEW.period_end IS DISTINCT FROM OLD.period_end
       OR NEW.statement_currency IS DISTINCT FROM OLD.statement_currency
       OR NEW.upstream_status IS DISTINCT FROM OLD.upstream_status
       OR NEW.statement_received_amount IS DISTINCT FROM OLD.statement_received_amount
       OR NEW.statement_deduction_amount IS DISTINCT FROM OLD.statement_deduction_amount
       OR NEW.statement_claim_amount IS DISTINCT FROM OLD.statement_claim_amount
       OR NEW.statement_suggested_amount IS DISTINCT FROM OLD.statement_suggested_amount
       OR NEW.statement_total_quantity IS DISTINCT FROM OLD.statement_total_quantity
       OR NEW.finance_confirmed_amount IS DISTINCT FROM OLD.finance_confirmed_amount
       OR NEW.finance_confirmed_quantity IS DISTINCT FROM OLD.finance_confirmed_quantity
       OR NEW.finance_adjusted_amount IS DISTINCT FROM OLD.finance_adjusted_amount
       OR NEW.confirmed_by IS DISTINCT FROM OLD.confirmed_by
       OR NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at
       OR NEW.rejected_by IS DISTINCT FROM OLD.rejected_by
       OR NEW.rejected_at IS DISTINCT FROM OLD.rejected_at
       OR NEW.reject_reason IS DISTINCT FROM OLD.reject_reason
       OR NEW.payable_id IS DISTINCT FROM OLD.payable_id
       OR NEW.payable_no IS DISTINCT FROM OLD.payable_no
       OR NEW.note IS DISTINCT FROM OLD.note THEN
      RAISE EXCEPTION '对账单 % 已是终态（%），不可再改：只能新建一张对账单', OLD.recon_no, OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.status = 'CONFIRMED' THEN
    IF NEW.confirmed_by IS NULL OR NEW.confirmed_at IS NULL THEN
      RAISE EXCEPTION '对账单 % 确认必须记录确认人与确认时刻', OLD.recon_no
        USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*), coalesce(max(amount), 0) INTO basis_count, basis_amount
      FROM finance_supplier_reconciliation_payable
     WHERE tenant_id = NEW.tenant_id AND recon_id = NEW.id;
    IF basis_count <> 1 THEN
      RAISE EXCEPTION '对账单 % 确认必须恰好有一条应付依据（当前 % 条）：没有应付依据就不是「已确认应付」',
        OLD.recon_no, basis_count USING ERRCODE = 'check_violation';
    END IF;
    IF basis_amount IS DISTINCT FROM NEW.finance_confirmed_amount THEN
      RAISE EXCEPTION '对账单 % 的确认金额（%）与应付依据金额（%）不一致',
        OLD.recon_no, NEW.finance_confirmed_amount, basis_amount USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*) INTO open_diff
      FROM finance_supplier_reconciliation_difference
     WHERE tenant_id = NEW.tenant_id AND recon_id = NEW.id AND status <> 'RESOLVED';
    IF open_diff > 0 THEN
      RAISE EXCEPTION '对账单 % 还有 % 条差异未裁决，不可确认', OLD.recon_no, open_diff
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  IF NEW.status = 'REJECTED' THEN
    IF NEW.rejected_by IS NULL OR NEW.rejected_at IS NULL OR btrim(NEW.reject_reason) = '' THEN
      RAISE EXCEPTION '对账单 % 驳回必须记录驳回人与理由', OLD.recon_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_supplier_reconciliation_confirm
  BEFORE UPDATE ON finance_supplier_reconciliation
  FOR EACH ROW EXECUTE FUNCTION guard_finance_supplier_reconciliation_confirm();

-- ---------------------------------------------------------------------------
-- (b) 应付依据必须真的指向「一张属于这份对账单、且已生效」的应付单。
-- ---------------------------------------------------------------------------
--
-- 这条守卫是「采购侧不能自行确认应付」在数据层的最后一环：
-- 应付单的来源必须是 SUPPLIER_STATEMENT，来源单号必须是**这份对账单的对账单号**，
-- 币种与金额必须与依据一致，且它不是作废单。
CREATE OR REPLACE FUNCTION guard_finance_supplier_reconciliation_payable_basis() RETURNS trigger AS $$
DECLARE
  recon finance_supplier_reconciliation%ROWTYPE;
  pay finance_payable%ROWTYPE;
BEGIN
  SELECT * INTO recon FROM finance_supplier_reconciliation
   WHERE tenant_id = NEW.tenant_id AND id = NEW.recon_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '应付依据找不到对应的对账单' USING ERRCODE = 'check_violation';
  END IF;

  -- 对账单已是终态 ⇒ 不许再挂应付（重复确认不会造出第二张应付）。
  IF recon.status IN ('CONFIRMED','REJECTED') THEN
    RAISE EXCEPTION '对账单 % 已是终态（%），不可再挂应付依据', recon.recon_no, recon.status
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT * INTO pay FROM finance_payable
   WHERE tenant_id = NEW.tenant_id AND id = NEW.payable_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '应付依据引用的应付单 % 不存在', NEW.payable_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF pay.source <> 'SUPPLIER_STATEMENT' THEN
    RAISE EXCEPTION '应付依据引用的应付单来源是 %，必须是 SUPPLIER_STATEMENT（供应商对账单）', pay.source
      USING ERRCODE = 'check_violation';
  END IF;
  IF pay.source_reference IS DISTINCT FROM recon.recon_no THEN
    RAISE EXCEPTION '应付依据引用的应付单来源单号是 %，必须是对账单号 %',
      coalesce(pay.source_reference, 'NULL'), recon.recon_no USING ERRCODE = 'check_violation';
  END IF;
  IF pay.status = 'VOID' THEN
    RAISE EXCEPTION '应付依据不能指向已作废的应付单 %', pay.payable_no
      USING ERRCODE = 'check_violation';
  END IF;
  IF pay.currency <> NEW.currency THEN
    RAISE EXCEPTION '应付依据币种（%）与应付单币种（%）不一致', NEW.currency, pay.currency
      USING ERRCODE = 'check_violation';
  END IF;
  IF pay.amount IS DISTINCT FROM NEW.amount THEN
    RAISE EXCEPTION '应付依据金额（%）与应付单金额（%）不一致', NEW.amount, pay.amount
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_supplier_reconciliation_payable_basis
  BEFORE INSERT ON finance_supplier_reconciliation_payable
  FOR EACH ROW EXECUTE FUNCTION guard_finance_supplier_reconciliation_payable_basis();

-- ---------------------------------------------------------------------------
-- (c) 单条差异的金额不得越过整行的差额（差异口径「不重不漏」的兜底）。
-- ---------------------------------------------------------------------------
--
-- 同一行上的多条差异是**互不重叠**的分量（数量差 / 质检扣量差 / 金额残差），
-- 它们之和恰好等于该行的差额 ⇒ 单条差异的金额不可能超过整行的差额。
CREATE OR REPLACE FUNCTION guard_finance_supplier_reconciliation_difference_total() RETURNS trigger AS $$
DECLARE
  line_variance numeric(19,4);
BEGIN
  IF NEW.line_id IS NULL THEN RETURN NEW; END IF;

  SELECT variance_amount INTO line_variance
    FROM finance_supplier_reconciliation_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.line_id;
  IF line_variance IS NULL THEN RETURN NEW; END IF;

  IF abs(NEW.variance_amount) > abs(line_variance) + 0.01 THEN
    RAISE EXCEPTION '单条差异的金额（%）超过该行的差额（%）：差异口径不重不漏',
      NEW.variance_amount, line_variance USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_supplier_reconciliation_difference_total
  AFTER INSERT OR UPDATE ON finance_supplier_reconciliation_difference
  FOR EACH ROW EXECUTE FUNCTION guard_finance_supplier_reconciliation_difference_total();

-- ---------------------------------------------------------------------------
-- (d) 差异计数由 DB 派生（服务层不许自己算）。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_finance_supplier_reconciliation_difference_counts() RETURNS trigger AS $$
BEGIN
  UPDATE finance_supplier_reconciliation
     SET difference_count = (
           SELECT count(*) FROM finance_supplier_reconciliation_difference d
            WHERE d.tenant_id = NEW.tenant_id AND d.recon_id = NEW.recon_id),
         open_difference_count = (
           SELECT count(*) FROM finance_supplier_reconciliation_difference d
            WHERE d.tenant_id = NEW.tenant_id AND d.recon_id = NEW.recon_id AND d.status <> 'RESOLVED'),
         updated_at = now()
   WHERE tenant_id = NEW.tenant_id AND id = NEW.recon_id;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_supplier_reconciliation_difference_counts
  AFTER INSERT OR UPDATE OR DELETE ON finance_supplier_reconciliation_difference
  FOR EACH ROW EXECUTE FUNCTION guard_finance_supplier_reconciliation_difference_counts();

-- ---------------------------------------------------------------------------
-- (e) 终态之后：明细行、质检证据、差异、说明一律冻结。
-- ---------------------------------------------------------------------------
--
-- ⚠️ **DELETE 触发器必须返回 OLD**：PostgreSQL 的 BEFORE DELETE 触发器里 NEW 恒为 NULL，
-- 而「返回 NULL」= **跳过这次删除** ⇒ 返回 NEW 会让 DELETE 静默失效。
CREATE OR REPLACE FUNCTION guard_finance_supplier_reconciliation_line_frozen() RETURNS trigger AS $$
DECLARE recon_status text;
BEGIN
  -- ⚠️ DELETE 触发器里 NEW 恒为 NULL（字段也是 NULL），所以分支取 OLD；
  --    而且**必须返回 OLD**：BEFORE DELETE 返回 NULL = 跳过这次删除。
  IF TG_OP = 'DELETE' THEN
    SELECT status INTO recon_status FROM finance_supplier_reconciliation
     WHERE tenant_id = OLD.tenant_id AND id = OLD.recon_id;
  ELSE
    SELECT status INTO recon_status FROM finance_supplier_reconciliation
     WHERE tenant_id = NEW.tenant_id AND id = NEW.recon_id;
  END IF;
  IF recon_status IS NULL THEN
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;
  IF recon_status IN ('CONFIRMED','REJECTED') THEN
    RAISE EXCEPTION '对账单已是终态（%），明细行不可再改', recon_status
      USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_supplier_reconciliation_line_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON finance_supplier_reconciliation_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_supplier_reconciliation_line_frozen();

CREATE OR REPLACE FUNCTION guard_finance_supplier_reconciliation_difference_frozen() RETURNS trigger AS $$
DECLARE recon_status text;
BEGIN
  -- ⚠️ DELETE 触发器里 NEW 恒为 NULL（字段也是 NULL），所以分支取 OLD；
  --    而且**必须返回 OLD**：BEFORE DELETE 返回 NULL = 跳过这次删除。
  IF TG_OP = 'DELETE' THEN
    SELECT status INTO recon_status FROM finance_supplier_reconciliation
     WHERE tenant_id = OLD.tenant_id AND id = OLD.recon_id;
  ELSE
    SELECT status INTO recon_status FROM finance_supplier_reconciliation
     WHERE tenant_id = NEW.tenant_id AND id = NEW.recon_id;
  END IF;
  IF recon_status IS NULL THEN
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;
  IF recon_status IN ('CONFIRMED','REJECTED') THEN
    RAISE EXCEPTION '对账单已是终态（%），差异不可再改', recon_status
      USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_supplier_reconciliation_difference_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON finance_supplier_reconciliation_difference
  FOR EACH ROW EXECUTE FUNCTION guard_finance_supplier_reconciliation_difference_frozen();

CREATE OR REPLACE FUNCTION guard_finance_supplier_reconciliation_explanation_frozen() RETURNS trigger AS $$
DECLARE recon_status text;
BEGIN
  SELECT status INTO recon_status FROM finance_supplier_reconciliation
   WHERE tenant_id = NEW.tenant_id AND id = NEW.recon_id;
  IF recon_status IS NULL THEN RETURN NEW; END IF;
  IF recon_status IN ('CONFIRMED','REJECTED') THEN
    RAISE EXCEPTION '对账单已是终态（%），差异说明不可再登记', recon_status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_supplier_reconciliation_explanation_frozen
  BEFORE INSERT ON finance_supplier_reconciliation_explanation
  FOR EACH ROW EXECUTE FUNCTION guard_finance_supplier_reconciliation_explanation_frozen();

-- ---------------------------------------------------------------------------
-- (f) 质检证据同样随对账单冻结。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_finance_supplier_reconciliation_inspection_frozen() RETURNS trigger AS $$
DECLARE recon_status text;
BEGIN
  -- ⚠️ DELETE 触发器里 NEW 恒为 NULL（字段也是 NULL），所以分支取 OLD；
  --    而且**必须返回 OLD**：BEFORE DELETE 返回 NULL = 跳过这次删除。
  IF TG_OP = 'DELETE' THEN
    SELECT status INTO recon_status FROM finance_supplier_reconciliation
     WHERE tenant_id = OLD.tenant_id AND id = OLD.recon_id;
  ELSE
    SELECT status INTO recon_status FROM finance_supplier_reconciliation
     WHERE tenant_id = NEW.tenant_id AND id = NEW.recon_id;
  END IF;
  IF recon_status IS NULL THEN
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;
  IF recon_status IN ('CONFIRMED','REJECTED') THEN
    RAISE EXCEPTION '对账单已是终态（%），质检证据不可再改', recon_status
      USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_supplier_reconciliation_inspection_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON finance_supplier_reconciliation_inspection
  FOR EACH ROW EXECUTE FUNCTION guard_finance_supplier_reconciliation_inspection_frozen();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：期间结束日 + id（对账按期间排，不是按创建时间排）。
CREATE INDEX finance_supplier_reconciliation_by_period
  ON finance_supplier_reconciliation(tenant_id, status, period_end DESC, id);
-- 按供应商查（这个供应商还有哪些对账单没确认）。
CREATE INDEX finance_supplier_reconciliation_by_supplier
  ON finance_supplier_reconciliation(tenant_id, supplier_ref_id, status, period_end DESC);
-- 按上游单号查（幂等键）。
CREATE INDEX finance_supplier_reconciliation_by_upstream
  ON finance_supplier_reconciliation(tenant_id, upstream_recon_id);
-- 明细行按对账单取。
CREATE INDEX finance_supplier_reconciliation_line_by_recon
  ON finance_supplier_reconciliation_line(tenant_id, recon_id, line_no);
-- 质检证据按对账单 / 行取。
CREATE INDEX finance_supplier_reconciliation_inspection_by_recon
  ON finance_supplier_reconciliation_inspection(tenant_id, recon_id, line_id);
CREATE INDEX finance_supplier_reconciliation_inspection_by_line
  ON finance_supplier_reconciliation_inspection(tenant_id, line_id);
-- 差异清单按对账单取（未裁决的排前面）。
CREATE INDEX finance_supplier_reconciliation_difference_by_recon
  ON finance_supplier_reconciliation_difference(tenant_id, recon_id, status, id);
-- 差异说明按差异取。
CREATE INDEX finance_supplier_reconciliation_explanation_by_difference
  ON finance_supplier_reconciliation_explanation(tenant_id, difference_id, created_at, id);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_supplier_reconciliation_event_by_recon
  ON finance_supplier_reconciliation_event(tenant_id, recon_id, occurred_at, id);
-- 应付依据按对账单取。
CREATE INDEX finance_supplier_reconciliation_payable_by_recon
  ON finance_supplier_reconciliation_payable(tenant_id, recon_id);
CREATE INDEX finance_supplier_reconciliation_payable_by_payable
  ON finance_supplier_reconciliation_payable(tenant_id, payable_id);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_supplier_reconciliation','finance_supplier_reconciliation_line',
    'finance_supplier_reconciliation_inspection','finance_supplier_reconciliation_difference',
    'finance_supplier_reconciliation_explanation','finance_supplier_reconciliation_event',
    'finance_supplier_reconciliation_payable'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_supplier_reconciliation, finance_supplier_reconciliation_line,
  finance_supplier_reconciliation_inspection, finance_supplier_reconciliation_difference,
  finance_supplier_reconciliation_explanation, finance_supplier_reconciliation_event,
  finance_supplier_reconciliation_payable TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_supplier_reconciliation, finance_supplier_reconciliation_line,
  finance_supplier_reconciliation_inspection, finance_supplier_reconciliation_difference,
  finance_supplier_reconciliation_explanation, finance_supplier_reconciliation_event,
  finance_supplier_reconciliation_payable TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：事件流与差异说明是**举证凭据**，只能追加。
REVOKE UPDATE,DELETE ON finance_supplier_reconciliation_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_supplier_reconciliation_explanation FROM finance_app;

-- 对账单不需要物理删除：确认 / 驳回都是状态流转，删掉就没有痕迹了。
REVOKE DELETE ON finance_supplier_reconciliation FROM finance_app;

-- ===========================================================================
-- 第五节：留给下游 / 兄弟模块的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.1.2.1 应付单管理（已交付）**：本模块的「财务确认」动作**写** `finance_payable`
--     （同库 ⇒ 走授权 API 不可行，写库内事务是本仓既有做法，与 12.1.1.3 坏账核销写
--     `finance_receivable_line` / `finance_receipt` 同一手法）。
--     写入方式严格遵守 V340 的守卫：**先建 DRAFT 单头（不填金额）→ 落明细行
--     （金额由触发器 guard_finance_payable_amount 求和派生）→ DRAFT→OPEN 确认**
--     ⇒ 单头金额永远等于明细合计，本模块**不手填单头金额**。
--     幂等键 = ('SUPPLIER_STATEMENT', 对账单号)，与 V340 的
--     UNIQUE(tenant_id, source, source_reference) 对齐 ⇒ 同一份对账单重复确认
--     **不会造出第二张应付**。
--
--   * **12.2.2.1 付款单与审批（未交付）**：应付单的已付金额恒为 0（付款写入口未开放）。
--
--   * **6.5.1.1 采购对账（已交付）**：上游的 `finance_handoff_status` 恒为
--     'PENDING_FINANCE_SERVICE'（表级 CHECK 只允许这一个值 ⇒ 上游**没有任何路径**
--     能把交接状态写成「财务已确认」）。财务侧的确认结果在本服务自己的表里，
--     上游要看到它，走授权 API / event，本模块**不改上游的表**。
