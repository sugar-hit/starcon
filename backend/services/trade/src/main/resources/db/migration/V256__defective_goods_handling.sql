-- ===========================================================================
-- V256 6.4.2.2 不良品处理（不合格品的处置：退换货补货 / 返修 / 让步接收 / 报废 / 转为促销品）
--
-- 蓝图 06-purchase-and-supply.md 6.4.2.2 原文：
--   职责：不合格品的处置：退换货补货、返修、让步接收、报废、转为促销品，
--         处置结论联动库存状态与供应商索赔。
--   输入：质检不合格明细；处置决策；索赔金额。
--   输出：处置单 + 库存状态变更指令 + 供应商索赔记录。
--   关系：上游 = 6.4.2.1；下游 = 7 库存（调整）、12 财务（索赔/退款）。
--         **让步接收需要权限与原因，不能由质检员随手放行。**
--
-- 归属：**trade 服务**（卷 06 L2 表「6.4 加工与质检 → trade + warehouse」列表第一项是 trade；
-- 同卷 6.4.1.1 加工单、6.4.2.1 质检单、6.2.1.3 采购计划都在 trade）。
-- 前端路由 /purchase/quality/defectives 是**前端**路由；后端命名空间按服务走
-- （与 /trade/quality-inspections、/trade/purchase-plans 同一纪律）。
--
-- ---------------------------------------------------------------------------
-- 一、本模块在数据模型上的位置：处置是「质检结论之后」的一张独立单据
-- ---------------------------------------------------------------------------
-- 6.4.2.1 质检单（V249，trade）已经落库的是**质检结论**：合格量 / 不合格量 / 让步接收量
-- 三量互斥且完备（qualified + defective + concession = lot_size，DB CHECK 强制）。
-- 本模块**不改那张单**（它是凭据，终态不可改写），而是**消费**它：
--
--   * 处置单的每一个数量都**从质检单回读**，**不接受调用方自报** ——
--     让操作员再敲一遍数字等于让同一个结论在两个地方各有一份，迟早对不上。
--   * 本文件**不建指向 quality_inspection_order 的外键**（与 V249 的 guard 同一手法）：
--     加跨表外键会让别的车道 ALTER 那张表时炸掉，而本文件对既有表的 DDL 依赖面必须为 0。
--     跨表一致性由**触发器在写入时刻真查一次**钉死。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 三条硬规则各自落在哪里
-- ---------------------------------------------------------------------------
-- 规则①「**让步接收需要权限与原因，不能由质检员随手放行**」
--   * DB 层：`defective_disposition_line` 的表级 CHECK —— CONCESSION 行的
--     `concession_reason` 与 `permission_code` **缺一拒绝**；非 CONCESSION 行
--     这两栏**必须为空**（不许借别的类型夹带权限码）。
--   * 服务层：记录人**必须真的持有** `trade.defective-disposition.concession`
--     这个权限码 —— 只写一个码不算放行。
--   * 权限码与「质检结论录入」**分开**：`trade.defective-dispection.concession`
--     比 `trade.quality-inspection.update` 高一档，质检员手里的码放不了行。
--   * 让步凭据**append-only**（REVOKE UPDATE/DELETE + 触发器），事后查得到
--     「谁、凭什么、为什么放行了这一批」。
--
-- 规则②「质检结论只有一个来源」
--   * 处置单头上的 `inspection_*_quantity` 四个数全部由**触发器**从
--     `quality_inspection_order` 回读核对（服务层写错也会被 DB 挡回来）。
--   * `disposed_quantity` / `released_quantity` 由**明细行求和重算**（守卫②），
--     本单**没有**「直接写处置量」的入口。
--
-- 规则③「处置量受质检结论约束」
--   * **可售量上限** = 合格量 + 让步接收量（质检结论给出的**可售部分**）——
--     它被**冻结**在处置单的 `sellable_ceiling` 栏，处置**不得侵占**它：
--     处置量只作用在**不合格量**上。
--   * **处置量 ≤ 质检单的不合格量**（服务层 + DB 守卫③）。
--   * **累计处置量 ≤ 质检单的不合格量**（同一张质检单可分多张处置单处置，
--     但加起来不能超过不合格量 —— 服务层 + DB 守卫③）。
--   * **放行量 ≤ 质检单的扣量合计**（让步接收 / 转促销品把货放回可售池，
--     只能从「已经判不合格」的那部分里出）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 跨服务边界：库存状态变更**只发指令、不建账**
-- ---------------------------------------------------------------------------
-- 蓝图 6.4.2.2 的输出句是「处置单 + **库存状态变更指令** + 供应商索赔记录」——
-- 措辞是**指令**，不是「改库存」。而库存分录的写入所有权在 warehouse（7 库存 / 8.2.2.2 待检处理）：
--
--   * warehouse 已交付的 `/warehouse/pending-inspections/**` 全部**挂在 warehouse 自有的
--     `pending_inspection_task` 上**：建单要 `source_bucket_id`（stock_bucket.id）与
--     `receipt_line_id`（goods_receipt_line.id），处置要 `target_quality_status_id` /
--     `target_location_id` —— **全是 warehouse 库里的内部外键**，
--     且那张待检处理单**必须**挂在一条真实收货差异/收货行上。
--     trade 侧没有「按质检结论把货登记到残次 bucket」的授权写入口。
--   * ⇒ 本模块**只落指令**（`defective_inventory_instruction`），**绝不往 warehouse 的表里
--     插分录**（那是跨库写入，AGENTS.md 明令禁止）。
--   * 指令的 `status` 如实报出：**没有可用写端点时恒为 `UNAVAILABLE`** ——
--     **绝不出现 `DISPATCHED` 却其实没分流**的那种假记录（同 6.4.2.1 的分流纪律）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 12 财务（finance 服务）在本仓不存在
-- ---------------------------------------------------------------------------
-- `git ls-tree integration/module-dev backend/services/` 只有
-- catalog / channel / crm / identity / trade / warehouse —— **没有 finance**。
-- 所以：
--   * 索赔记录**落在 trade 本库**（谁写谁负责）：`defective_supplier_claim`。
--   * 「登记的索赔金额」与「**已生效**的冲减金额」是**两列**：
--     `claimed_amount` / `agreed_amount`（供应商认可的赔付额，可登记）
--     与 `effective_amount`（已入账冲减额，**恒为 0**，DB CHECK 钉死）。
--   * `finance_status` **恒为 `PENDING_FINANCE`** —— 财务侧入账 / 退款执行
--     要等 12 财务交付后由它接管；本模块**不假装已经入账**。
--
-- ---------------------------------------------------------------------------
-- 五、数量与时间口径（与 V249 / V252 / V235 完全一致，不另立一套）
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,6) + Java BigDecimal，全程禁浮点；金额 NUMERIC(19,4)。
--   时间一律 timestamptz，Java 侧绑 OffsetDateTime，SQL values 位置写 ?::timestamptz。
--   （用 Timestamp.valueOf(LocalDateTime) 绑 timestamptz 会静默偏 −28800s。）
--
-- ===========================================================================
-- 0. 角色（可能已由运维预置，只在缺失时创建，与 V1 同口径）
-- ===========================================================================
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='trade_app') THEN CREATE ROLE trade_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO trade_app;

-- ===========================================================================
-- 1. 处置单（单头）
-- ===========================================================================
CREATE TABLE defective_disposition_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 处置单号：租户内唯一（DG-20261021-a1b2c3d4）。
  disposition_no varchar(64) NOT NULL CHECK(btrim(disposition_no) <> ''),
  -- ---- 上游 6.4.2.1 质检单（同一服务 → 逻辑引用 + 可读快照；**不建外键**，见文件头）----
  inspection_order_id uuid NOT NULL,
  inspection_no varchar(64) NOT NULL CHECK(btrim(inspection_no) <> ''),
  -- 质检结论快照（触发器回读核对，**不接受调用方自报**）。
  inspection_result varchar(24) NOT NULL
    CHECK(inspection_result IN ('PASSED','FAILED','PARTIAL','CONCESSION_ACCEPTED')),
  -- SKU / 供应商：逻辑 ID + 名称快照（catalog 拥有主数据 ⇒ 不建影子表）。
  sku_id uuid,
  sku_code varchar(128) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200),
  supplier_id uuid,
  supplier_name varchar(200),
  receipt_code varchar(64),
  batch_no varchar(64),
  -- ---- 质检结论的四个量（全部从 6.4.2.1 回读）----
  -- 本次质检覆盖的批量。
  inspection_lot_size numeric(19,6) NOT NULL CHECK(inspection_lot_size > 0),
  -- **不合格量**：处置的唯一物资来源。
  inspection_defective_quantity numeric(19,6) NOT NULL CHECK(inspection_defective_quantity >= 0),
  -- 合格量。
  inspection_qualified_quantity numeric(19,6) NOT NULL CHECK(inspection_qualified_quantity >= 0),
  -- 让步接收量（6.4.2.1 阶段已放行的部分）。
  inspection_concession_quantity numeric(19,6) NOT NULL CHECK(inspection_concession_quantity >= 0),
  -- 🔴 **可售量上限 = 合格量 + 让步接收量**（质检结论给出的可售部分）。
  -- 处置**不得侵占**它：处置量只作用在不合格量上，这一栏被冻结下来作为对照。
  sellable_ceiling numeric(19,6) NOT NULL CHECK(sellable_ceiling >= 0),
  -- 扣量合计：放行量（让步接收 / 转促销品）只能从这里出。
  gross_deducted_quantity numeric(19,6) NOT NULL CHECK(gross_deducted_quantity >= 0),
  -- ---- 本单的数量（由明细行求和**重算**，见守卫②）----
  -- 处置总量 = Σ 明细行数量。
  disposed_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(disposed_quantity >= 0),
  -- 放行量 = Σ 明细行放行量（让步接收 + 转促销品：这些货回到可售 / 促销池）。
  released_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(released_quantity >= 0),
  -- 状态机：
  --   DRAFT      草稿（明细可改）
  --   DECIDED    已决策（决策定稿，生成库存指令与索赔单）
  --   EXECUTING  执行中（库存指令已派发，等索赔结论）
  --   COMPLETED  已完成
  --   CANCELLED  已作废
  -- DECIDED / EXECUTING / COMPLETED / CANCELLED 是终态，不可逆。
  status varchar(24) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','DECIDED','EXECUTING','COMPLETED','CANCELLED')),
  -- **处置理由**：决策要留理由（不是只有让步接收才要理由）。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  decided_by uuid,
  decided_at timestamptz,
  -- 库存状态变更指令的汇总状态。
  inventory_status varchar(24) NOT NULL DEFAULT 'NOT_DISPATCHED'
    CHECK(inventory_status IN ('NOT_DISPATCHED','PARTIAL','UNAVAILABLE','DISPATCHED','FAILED')),
  -- 供应商索赔的汇总状态：NONE 无索赔 / PENDING 已建待提交 / SUBMITTED 已提交 /
  -- SETTLED 已结 / REJECTED 已拒赔 / WITHDRAWN 已撤回。
  claim_status varchar(24) NOT NULL DEFAULT 'NONE'
    CHECK(claim_status IN ('NONE','PENDING','SUBMITTED','SETTLED','REJECTED','WITHDRAWN')),
  -- ---- 卷 06 加粗约束：币种必须记录 ----
  currency_code varchar(8),
  -- 索赔金额（登记口径）。**BigDecimal + NUMERIC，禁浮点。**
  claim_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(claim_amount >= 0),
  version bigint NOT NULL DEFAULT 0,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  completed_by uuid,
  cancelled_at timestamptz,
  cancelled_by uuid,
  cancel_reason text NOT NULL DEFAULT '',
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,disposition_no)
);

COMMENT ON TABLE defective_disposition_order IS
  '6.4.2.2 不良品处理：针对一张 6.4.2.1 质检单的不合格量做出的处置决策单据。'
  '所有数量从质检单回读，不接受调用方自报。';
COMMENT ON COLUMN defective_disposition_order.sellable_ceiling IS
  '可售量上限 = 合格量 + 让步接收量（质检结论给出的可售部分）。处置不得侵占它：'
  '处置量只作用在不合格量上。';
COMMENT ON COLUMN defective_disposition_order.disposed_quantity IS
  '处置总量。由明细行求和重算（DB 守卫②），本单没有「直接写处置量」的入口。';

-- ===========================================================================
-- 2. 处置明细（一个处置单 → 1..N 个去向）
-- ===========================================================================
CREATE TABLE defective_disposition_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 🔴 处置类型：蓝图 6.4.2.2 职责句点名的五个去向，词表在 DB 层钉死。
  --   RETURN_EXCHANGE  退换货补货（退回去并要求供应商补货）
  --   REPAIR           返修（送回供应商/工厂返修）
  --   CONCESSION       让步接收（放行）—— **需要权限与原因**
  --   SCRAP            报废
  --   PROMOTION        转为促销品
  disposition_type varchar(32) NOT NULL
    CHECK(disposition_type IN ('RETURN_EXCHANGE','REPAIR','CONCESSION','SCRAP','PROMOTION')),
  -- **明细的先后次序**：由建单时的录入顺序决定。
  -- 不用「创建时间 + id」排序 —— 同一笔事务里插入的多行创建时间完全相同，
  -- 再按随机 UUID 排就得到一个**每次都不同**的次序，界面上的去向顺序会乱跳。
  sort_order int NOT NULL DEFAULT 0,
  -- 该去向的数量。
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  -- **放行量**：只有 CONCESSION（放行回可售）与 PROMOTION（转促销品，仍可卖）非零，
  -- 其余类型恒为 0（报废 / 退换货 / 返修的货都**离仓**，不进可售池）。
  released_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(released_quantity >= 0),
  -- 🔴 **让步接收的原因与权限码，缺一拒绝**（蓝图 6.4.2.2 加粗句的纪律）。
  -- 非 CONCESSION 行这两栏必须为空 —— 不许借别的类型夹带一个权限码。
  concession_reason text NOT NULL DEFAULT '',
  permission_code varchar(64) NOT NULL DEFAULT '',
  -- 退换货补货：期望到货日（业务日历用 date，不用 timestamptz）。
  expected_return_date date,
  -- 返修：返修方。
  repair_vendor varchar(200),
  -- 转促销品：促销方案。
  promotion_plan varchar(200),
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES defective_disposition_order(tenant_id,id) ON DELETE CASCADE,
  -- 🔴 让步接收：原因与权限码缺一拒绝；非让步行必须两栏皆空。
  CONSTRAINT defective_disposition_line_concession_guard CHECK(
    (disposition_type = 'CONCESSION'
      AND btrim(concession_reason) <> '' AND btrim(permission_code) <> '')
    OR (disposition_type <> 'CONCESSION'
      AND concession_reason = '' AND permission_code = '')
  ),
  -- 放行量只可能出现在让步接收与转促销品两种类型上；
  -- 且这两种类型**整行**都回可售池（放行量 = 该行数量）——
  -- 一行只表达一个去向，「50 件里接受 30 件」要写成 CONCESSION 30 + SCRAP 20 两行。
  CONSTRAINT defective_disposition_line_released_guard CHECK(
    (disposition_type IN ('CONCESSION','PROMOTION') AND released_quantity = quantity)
    OR (disposition_type NOT IN ('CONCESSION','PROMOTION') AND released_quantity = 0)
  )
);

COMMENT ON TABLE defective_disposition_line IS
  '处置明细：一次处置的去向拆分（退换货补货 / 返修 / 让步接收 / 报废 / 转促销品）。';

CREATE INDEX defective_disposition_line_by_order
  ON defective_disposition_line(tenant_id, order_id, sort_order, disposition_type);

-- ===========================================================================
-- 3. 库存状态变更指令（outbox —— 本模块只发指令，不建账）
-- ===========================================================================
CREATE TABLE defective_inventory_instruction (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  line_id uuid NOT NULL,
  -- 指令动作（与处置类型一一对应）：
  --   SCRAP_OUT          报废离仓
  --   RETURN_OUT         退换货离仓
  --   REPAIR_HOLD        转返修暂存
  --   DEFECTIVE_HOLD     转残次留存
  --   PROMOTION_RELEASE  转促销品（可卖，按促销价）
  --   CONCESSION_RELEASE 让步接收放行（回可售）
  action varchar(32) NOT NULL
    CHECK(action IN ('SCRAP_OUT','RETURN_OUT','REPAIR_HOLD','DEFECTIVE_HOLD',
                     'PROMOTION_RELEASE','CONCESSION_RELEASE')),
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  -- **写入所有权归谁**（如实标注，不假装已执行）。
  owner_module varchar(64) NOT NULL CHECK(btrim(owner_module) <> ''),
  -- 派发状态：
  --   NOT_DISPATCHED 未派发
  --   UNAVAILABLE   上游无授权写端点（**本仓当前的真实状态**）
  --   DISPATCHED    已登记
  --   FAILED        派发失败
  status varchar(24) NOT NULL DEFAULT 'NOT_DISPATCHED'
    CHECK(status IN ('NOT_DISPATCHED','UNAVAILABLE','DISPATCHED','FAILED')),
  -- 为什么是这个状态（如实说明，不写空话）。
  reason text NOT NULL DEFAULT '',
  -- 对端回执（真的派发成功时才有）。
  external_reference varchar(128) NOT NULL DEFAULT '',
  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  dispatched_at timestamptz,
  dispatched_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 幂等：同一处置明细的同一动作只允许一条指令（重跑 decide 不会产生第二条）。
  UNIQUE(tenant_id,line_id,action),
  FOREIGN KEY(tenant_id,order_id) REFERENCES defective_disposition_order(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,line_id) REFERENCES defective_disposition_line(tenant_id,id) ON DELETE CASCADE,
  -- 已登记的指令不可再改（凭据）：改一条已派发的指令等于让「货到底动没动」没有答案。
  CONSTRAINT defective_inventory_instruction_dispatched_guard CHECK(
    status <> 'DISPATCHED' OR (dispatched_at IS NOT NULL AND external_reference <> '')
  )
);

COMMENT ON TABLE defective_inventory_instruction IS
  '6.4.2.2 的库存状态变更**指令**（outbox）。库存分录的写入所有权在 warehouse；'
  '本表只记录「要让它变成什么样」，warehouse 无授权写端点时 status 恒为 UNAVAILABLE。';

CREATE INDEX defective_inventory_instruction_by_order
  ON defective_inventory_instruction(tenant_id, order_id, status);
CREATE INDEX defective_inventory_instruction_by_status
  ON defective_inventory_instruction(tenant_id, status, created_at desc, id);

-- ===========================================================================
-- 4. 供应商索赔记录（落在 trade 本库 —— 谁写谁负责）
-- ===========================================================================
CREATE TABLE defective_supplier_claim (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  claim_no varchar(64) NOT NULL CHECK(btrim(claim_no) <> ''),
  order_id uuid NOT NULL,
  supplier_id uuid,
  supplier_name varchar(200),
  -- 索赔类型（由处置明细推导，不是自由文本）：
  --   QUALITY_DEFECT       质量索赔
  --   REPLENISHMENT        退换货补货
  --   REPAIR_COST          返修费用
  --   SCRAP_LOSS           报废损失
  --   PROMOTION_DEDUCTION  转促销品折让
  claim_type varchar(32) NOT NULL
    CHECK(claim_type IN ('QUALITY_DEFECT','REPLENISHMENT','REPAIR_COST',
                         'SCRAP_LOSS','PROMOTION_DEDUCTION')),
  -- 🔴 卷 06 加粗约束：币种必须记录（索赔跨币种，绝不合并）。
  currency_code varchar(8) NOT NULL CHECK(btrim(currency_code) <> ''),
  -- **登记的索赔金额**（BigDecimal + NUMERIC(19,4)，禁浮点）。
  claimed_amount numeric(19,4) NOT NULL CHECK(claimed_amount > 0),
  -- 供应商认可的赔付额（可登记；供应商尚未答复时为 NULL）。
  agreed_amount numeric(19,4) CHECK(agreed_amount IS NULL OR agreed_amount >= 0),
  -- 🔴 **已生效的冲减金额**：12 财务（finance 服务）在本仓**不存在**，
  -- 财务侧入账 / 退款执行归 12 ⇒ 本列**恒为 0**，DB CHECK 钉死。
  -- 「登记了索赔」与「已经冲减了应付」是两件事，混成一个数就是假装已入账。
  effective_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(effective_amount = 0),
  -- 财务侧状态：本仓恒为 PENDING_FINANCE（等 12 财务接管）。
  finance_status varchar(24) NOT NULL DEFAULT 'PENDING_FINANCE'
    CHECK(finance_status = 'PENDING_FINANCE'),
  -- 财务回执（本仓恒为空 —— 没有财务就没有回执）。
  finance_reference varchar(128) NOT NULL DEFAULT '',
  -- 索赔单状态机（对供应商的过程）：
  --   DRAFT 草稿 → SUBMITTED 已提交 → ACKNOWLEDGED 供应商已确认
  --   → AGREED 已达成赔付共识 / REJECTED 供应商拒赔
  --   WITHDRAWN 已撤回（DRAFT / SUBMITTED 都能撤）
  status varchar(24) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','SUBMITTED','ACKNOWLEDGED','AGREED','REJECTED','WITHDRAWN')),
  submitted_at timestamptz,
  acknowledged_at timestamptz,
  settled_at timestamptz,
  decided_reason text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,claim_no),
  FOREIGN KEY(tenant_id,order_id) REFERENCES defective_disposition_order(tenant_id,id) ON DELETE CASCADE,
  -- 已达成赔付共识 ⇒ 必须有认可的金额；未达成时不得凭空填一个。
  CONSTRAINT defective_supplier_claim_agreed_guard CHECK(
    status <> 'AGREED' OR agreed_amount IS NOT NULL
  ),
  -- 供应商拒赔必须写理由（拒赔是要跟供应商对账的凭据）。
  CONSTRAINT defective_supplier_claim_reject_guard CHECK(
    status <> 'REJECTED' OR btrim(decided_reason) <> ''
  )
);

COMMENT ON TABLE defective_supplier_claim IS
  '6.4.2.2 的供应商索赔记录。落在 trade 本库（谁写谁负责）；'
  '财务侧入账 / 退款执行归 12 财务（finance 服务在本仓不存在）⇒ effective_amount 恒为 0。';

CREATE INDEX defective_supplier_claim_by_order
  ON defective_supplier_claim(tenant_id, order_id, status);
CREATE INDEX defective_supplier_claim_by_supplier
  ON defective_supplier_claim(tenant_id, supplier_id, created_at desc, id);

-- ===========================================================================
-- 5. 事件台账（append-only）
-- ===========================================================================
CREATE TABLE defective_disposition_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  event_type varchar(32) NOT NULL
    CHECK(event_type IN ('CREATED','UPDATED','DECIDED','INVENTORY_DISPATCHED',
                         'INVENTORY_UNAVAILABLE','INVENTORY_FAILED','CLAIM_SUBMITTED',
                         'CLAIM_AGREED','CLAIM_REJECTED','CLAIM_WITHDRAWN','COMPLETED','CANCELLED')),
  -- 事件发生时的业务时间（不是行写入库的时刻）。
  occurred_at timestamptz NOT NULL,
  actor_id uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES defective_disposition_order(tenant_id,id) ON DELETE CASCADE
);

CREATE INDEX defective_disposition_event_by_order
  ON defective_disposition_event(tenant_id, order_id, occurred_at, id);

-- ===========================================================================
-- 6. 守卫触发器
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 6.1 建单时的上游校验：质检单必须存在、属于本租户、且**已完结**
--     （未完结的质检单没有结论，谈不上处置）
-- ---------------------------------------------------------------------------
-- 为什么不用 CHECK：跨表条件写不进 CHECK，只能靠触发器回读。
-- 刻意**不建**指向 quality_inspection_order 的外键 —— 那会让别的车道 ALTER 那张表时炸掉。
CREATE OR REPLACE FUNCTION guard_defective_disposition_order_inspection() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_status text;
  v_result text;
  v_defective numeric(19,6);
  v_qualified numeric(19,6);
  v_concession numeric(19,6);
  v_lot numeric(19,6);
  v_gross numeric(19,6);
  v_no varchar(64);
BEGIN
  SELECT o.status, o.result, o.defective_quantity, o.qualified_quantity,
         o.concession_quantity, o.lot_size, o.gross_deducted_quantity, o.inspection_no
    INTO v_status, v_result, v_defective, v_qualified, v_concession, v_lot, v_gross, v_no
    FROM quality_inspection_order o
   WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.inspection_order_id;

  IF v_status IS NULL THEN
    RAISE EXCEPTION '质检单不存在或不属于本租户：%', NEW.inspection_order_id USING ERRCODE='23503';
  END IF;
  IF v_status <> 'COMPLETED' THEN
    RAISE EXCEPTION '质检单 % 还没完结（当前状态 %），没有结论就没有可处置的不良品',
      v_no, v_status USING ERRCODE='23514';
  END IF;
  IF v_defective <= 0 THEN
    RAISE EXCEPTION '质检单 % 的不合格量为 %，没有不良品可处置', v_no, v_defective
      USING ERRCODE='23514';
  END IF;

  -- 🔴 四个数**必须**与质检单一致 —— 服务层写错也要被挡回来。
  IF NEW.inspection_lot_size IS DISTINCT FROM v_lot
     OR NEW.inspection_defective_quantity IS DISTINCT FROM v_defective
     OR NEW.inspection_qualified_quantity IS DISTINCT FROM v_qualified
     OR NEW.inspection_concession_quantity IS DISTINCT FROM v_concession
     OR NEW.gross_deducted_quantity IS DISTINCT FROM v_gross
     OR NEW.inspection_result IS DISTINCT FROM v_result THEN
    RAISE EXCEPTION '处置单上的质检结论快照与质检单 % 不一致：结论只有一个来源，必须从质检单回读',
      v_no USING ERRCODE='23514';
  END IF;

  -- 🔴 **可售量上限 = 合格量 + 让步接收量**，被冻结下来作为对照。
  IF NEW.sellable_ceiling IS DISTINCT FROM v_qualified + v_concession THEN
    RAISE EXCEPTION '可售量上限（合格量 + 让步接收量）应为 %，与质检单 % 不符',
      (v_qualified + v_concession), v_no USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER defective_disposition_order_inspection_guard
  BEFORE INSERT OR UPDATE ON defective_disposition_order
  FOR EACH ROW EXECUTE FUNCTION guard_defective_disposition_order_inspection();

-- ---------------------------------------------------------------------------
-- 6.2 处置量与放行量的上界（服务层与数据库层各挡一道）
--     * 处置量 ≤ 质检单的不合格量
--     * 同一张质检单下**累计**处置量 ≤ 质检单的不合格量
--     * 放行量 ≤ 质检单的扣量合计
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_defective_disposition_order_bounds() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_defective numeric(19,6);
  v_gross numeric(19,6);
  v_others numeric(19,6);
  v_no varchar(64);
BEGIN
  SELECT o.defective_quantity, o.gross_deducted_quantity, o.inspection_no
    INTO v_defective, v_gross, v_no
    FROM quality_inspection_order o
   WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.inspection_order_id;

  IF v_defective IS NULL THEN
    RAISE EXCEPTION '质检单不存在或不属于本租户：%', NEW.inspection_order_id USING ERRCODE='23503';
  END IF;

  -- 同一张质检单下其它处置单已处置的量。
  SELECT COALESCE(SUM(d.disposed_quantity), 0) INTO v_others
    FROM defective_disposition_order d
   WHERE d.tenant_id = NEW.tenant_id
     AND d.inspection_order_id = NEW.inspection_order_id
     AND d.id <> NEW.id
     AND d.status <> 'CANCELLED';

  IF NEW.disposed_quantity + v_others > v_defective THEN
    RAISE EXCEPTION '处置量超限：本次 % + 其它处置单已处置 % > 质检单 % 的不合格量 %',
      NEW.disposed_quantity, v_others, v_no, v_defective USING ERRCODE='23514';
  END IF;

  IF NEW.released_quantity > v_gross THEN
    RAISE EXCEPTION '放行量 % 超过质检单 % 的扣量合计 %：放行只能从「已经判不合格」的那部分里出',
      NEW.released_quantity, v_no, v_gross USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER defective_disposition_order_bounds_guard
  BEFORE INSERT OR UPDATE ON defective_disposition_order
  FOR EACH ROW EXECUTE FUNCTION guard_defective_disposition_order_bounds();

-- ---------------------------------------------------------------------------
-- 6.3 明细行 → 单头的数量**重算**（本单没有「直接写处置量」的入口）
--     守卫②：本表**没有**任何一条路径可以绕过明细行直接改处置总量。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_defective_disposition_line_sync() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_order uuid;
  v_tenant uuid;
  v_qty numeric(19,6);
  v_rel numeric(19,6);
BEGIN
  -- 🔴 DELETE 触发器里 NEW 是**未赋值**的（PG 里访问会报
  -- 「record new is not assigned yet」），所以必须按 TG_OP 分支取键。
  IF TG_OP = 'DELETE' THEN
    v_order := OLD.order_id;
    v_tenant := OLD.tenant_id;
  ELSE
    v_order := NEW.order_id;
    v_tenant := NEW.tenant_id;
  END IF;

  SELECT COALESCE(SUM(l.quantity), 0), COALESCE(SUM(l.released_quantity), 0)
    INTO v_qty, v_rel
    FROM defective_disposition_line l
   WHERE l.tenant_id = v_tenant AND l.order_id = v_order;

  UPDATE defective_disposition_order o
     SET disposed_quantity = v_qty,
         released_quantity = v_rel,
         updated_at = now()
   WHERE o.tenant_id = v_tenant
     AND o.id = v_order
     AND (o.disposed_quantity IS DISTINCT FROM v_qty
          OR o.released_quantity IS DISTINCT FROM v_rel);

  RETURN NULL;
END $$;

CREATE TRIGGER defective_disposition_line_sync_guard
  AFTER INSERT OR UPDATE OR DELETE ON defective_disposition_line
  FOR EACH ROW EXECUTE FUNCTION guard_defective_disposition_line_sync();

-- ---------------------------------------------------------------------------
-- 6.4 单头的处置量必须等于明细行之和（挡住「绕过明细直接改总量」）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_defective_disposition_order_sum() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_qty numeric(19,6);
  v_rel numeric(19,6);
BEGIN
  SELECT COALESCE(SUM(l.quantity), 0), COALESCE(SUM(l.released_quantity), 0)
    INTO v_qty, v_rel
    FROM defective_disposition_line l
   WHERE l.tenant_id = NEW.tenant_id AND l.order_id = NEW.id;

  IF NEW.disposed_quantity <> v_qty OR NEW.released_quantity <> v_rel THEN
    RAISE EXCEPTION '处置量与明细行对不上：单头 % / %，明细合计 % / %（处置量只能由明细行重算）',
      NEW.disposed_quantity, NEW.released_quantity, v_qty, v_rel USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER defective_disposition_order_sum_guard
  BEFORE INSERT OR UPDATE ON defective_disposition_order
  FOR EACH ROW EXECUTE FUNCTION guard_defective_disposition_order_sum();

-- ---------------------------------------------------------------------------
-- 6.5 状态机 + 内容不可改写
--     * 合法流转：DRAFT → DECIDED → EXECUTING → COMPLETED，任一非终态可 → CANCELLED。
--       DECIDED / EXECUTING / COMPLETED / CANCELLED 是终态，不可逆。
--     * 一旦离开 DRAFT，**内容字段**（处置量 / 放行量 / 理由 / 质检引用）一律不许再改 ——
--       「当时处置了什么」是一份凭据，改掉就没有留痕价值。
--     * 状态与汇总状态（inventory_status / claim_status）由服务层的显式动作推进。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_defective_disposition_order_closed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  -- ① 合法流转（状态不变也算合法 —— 明细行变更触发的重算 UPDATE 保持 DRAFT 不变）。
  IF NOT (
       (OLD.status = NEW.status)
    OR (OLD.status = 'DRAFT'     AND NEW.status IN ('DECIDED','CANCELLED'))
    OR (OLD.status = 'DECIDED'   AND NEW.status IN ('EXECUTING','COMPLETED','CANCELLED'))
    OR (OLD.status = 'EXECUTING' AND NEW.status IN ('COMPLETED','CANCELLED'))
  ) THEN
    RAISE EXCEPTION '处置单状态不能从 % 变到 %：合法流转是 DRAFT → DECIDED → EXECUTING → COMPLETED，'
      '非终态可作废', OLD.status, NEW.status USING ERRCODE='23514';
  END IF;

  -- ② 离开 DRAFT 之后，内容字段不可改写。
  IF OLD.status <> 'DRAFT' THEN
    IF NEW.disposed_quantity IS DISTINCT FROM OLD.disposed_quantity
       OR NEW.released_quantity IS DISTINCT FROM OLD.released_quantity
       OR NEW.reason IS DISTINCT FROM OLD.reason
       OR NEW.inspection_order_id IS DISTINCT FROM OLD.inspection_order_id
       OR NEW.disposition_no IS DISTINCT FROM OLD.disposition_no
       OR NEW.sellable_ceiling IS DISTINCT FROM OLD.sellable_ceiling
       OR NEW.inspection_defective_quantity IS DISTINCT FROM OLD.inspection_defective_quantity THEN
      RAISE EXCEPTION '处置单已决策（当前状态 %），不允许再改处置量 / 放行量 / 理由 / 质检引用：'
        '处置结论是凭据，改掉就没有留痕价值', OLD.status USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER defective_disposition_order_closed_guard
  BEFORE UPDATE ON defective_disposition_order
  FOR EACH ROW EXECUTE FUNCTION guard_defective_disposition_order_closed();

-- ---------------------------------------------------------------------------
-- 6.6 明细行：处置单离开 DRAFT 后不许再改明细
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_defective_disposition_line_closed() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_status text;
BEGIN
  SELECT o.status INTO v_status
    FROM defective_disposition_order o
   WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.order_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '处置单不存在或不属于本租户：%', NEW.order_id USING ERRCODE='23503';
  END IF;
  IF v_status <> 'DRAFT' THEN
    RAISE EXCEPTION '处置单已决策（当前状态 %），不允许再改处置明细', v_status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER defective_disposition_line_closed_guard
  BEFORE INSERT OR UPDATE ON defective_disposition_line
  FOR EACH ROW EXECUTE FUNCTION guard_defective_disposition_line_closed();

-- ---------------------------------------------------------------------------
-- 6.7 索赔记录：已结 / 已拒 / 已撤回是终态，不许再改
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_defective_supplier_claim_closed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('AGREED','REJECTED','WITHDRAWN') THEN
    RAISE EXCEPTION '索赔单已处于终态（%），不允许再改：索赔凭据改掉就没有对账价值', OLD.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER defective_supplier_claim_closed_guard
  BEFORE UPDATE ON defective_supplier_claim
  FOR EACH ROW EXECUTE FUNCTION guard_defective_supplier_claim_closed();

-- ---------------------------------------------------------------------------
-- 6.8 事件台账只追加
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_defective_disposition_event_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '处置事件台账只追加：改写或删除一条事件等于抹掉「谁在什么时候处置了什么」'
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER defective_disposition_event_append_only
  BEFORE UPDATE OR DELETE ON defective_disposition_event
  FOR EACH ROW EXECUTE FUNCTION guard_defective_disposition_event_append_only();

-- ===========================================================================
-- 7. 索引
-- ===========================================================================
-- 处置单清单页：按状态 / 质检单 / SKU / 供应商读。
CREATE INDEX defective_disposition_order_by_status
  ON defective_disposition_order(tenant_id, status, created_at desc, id);
CREATE INDEX defective_disposition_order_by_inspection
  ON defective_disposition_order(tenant_id, inspection_order_id, id);
CREATE INDEX defective_disposition_order_by_sku
  ON defective_disposition_order(tenant_id, sku_id, created_at desc, id);
CREATE INDEX defective_disposition_order_by_supplier
  ON defective_disposition_order(tenant_id, supplier_id, created_at desc, id);

-- ===========================================================================
-- 8. 多租户：RLS + 授权（与 trade V1 / V249 同一套写法）
-- ===========================================================================
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'defective_disposition_order','defective_disposition_line',
    'defective_inventory_instruction','defective_supplier_claim',
    'defective_disposition_event'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  defective_disposition_order,defective_disposition_line,
  defective_inventory_instruction,defective_supplier_claim,
  defective_disposition_event TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  defective_disposition_order,defective_disposition_line,
  defective_inventory_instruction,defective_supplier_claim,
  defective_disposition_event TO starcon_app;
-- 事件台账**只追加**：收回改写与删除权（"授权在前、收回在后"，顺序不能颠倒）。
REVOKE UPDATE,DELETE ON defective_disposition_event FROM trade_app;
-- 处置单与索赔单**不提供 DELETE**：处置结论与索赔凭据删掉就没有留痕价值。
REVOKE DELETE ON defective_disposition_order,defective_supplier_claim FROM trade_app;

-- ===========================================================================
-- 9. 本迁移的回归面（跨车道纪律）
-- ===========================================================================
--   * **不 ALTER 任何既有表**：V1 / V97 / V98 / V235 / V249 / V250 / V251 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不改 11.x 退货侧的 supplier_claim**（V188 的五张 `supplier_claim_*` 表）：
--     那套表的根是 11.2.3.3「退货处置的索赔」，本模块是 6.4.2.2「来料不良品处置的索赔」，
--     上游事实不同（return_quality_inspection vs quality_inspection_order），
--     表名前缀 `defective_supplier_claim` 与 `supplier_claim` 完全分开，互不覆盖。
--   * **不改 6.4.2.1 的五张 `quality_inspection_*` 表**：本文件只**读**它们
--     （触发器回读 + 服务层 SELECT），不建外链、不加约束、不加列。
--   * 对 warehouse / finance 的写入**一律不发**：库存只落指令，财务只落本库的索赔记录。
--   * 新增函数全部带 defective_disposition_ / defective_supplier_ 前缀，
--     与既有函数名不冲突。
