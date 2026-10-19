-- 11.2.3.3 供应商索赔（卷 11 售后与退货 / L3 11.2.3 退货处置的第三格）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：对质量原因退货向供应商发起索赔：关联批次与责任判定、索赔金额、协商记录、赔付与冲减应付处理。
--   输入：质检结论与责任判定；索赔金额；协商记录。
--   输出：索赔单 + 冲减应付或退款记录。
--   关系：上游 = 11.1.2.2、11.2.3.1；下游 = 12 财务（冲减应付）、6.5.1 对账。
--   **索赔不是自动到账**，系统只跟踪流程与金额，冲减应付需财务确认。
--
-- 归属：**trade 服务**（与 11.2.3.1 质检与判定、11.1.2.2 商品问题归因同一条退货侧单据链）。
--
-- 本迁移**只新建五张表与触发器、零 ALTER 零 DROP 既有表**：
--   * return_quality_inspection（V179，11.2.3.1 已交付）**只读**：
--     质检结论（verdict / verdict_label / conclusion_note）、**原值 original_value**、
--     币种 salvage_currency、实检数量 inspected_qty —— 本模块的索赔基数真值来源。
--   * product_issue_attribution（V180，11.1.2.2 已交付）**只读**：
--     责任判定（attributed_stage）、批次快照（lot_id / lot_code / lot_supplier_ref_id）、
--     SKU 快照。
--   * quality_supplier_ref（V185，11.5.2.1 已交付）**只读**：
--     供应商引用镜像（supplier_ref_id ⇄ 编码 / 名称）。
--     供应商主数据在 catalog 独立库，按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / FK / 视图**。
--   跨表一致性由触发器在**写入时刻**真查一次钉死（刻意不建对外 FK —— 加 FK 会让本迁移
--     对别的车道的表产生 DDL 依赖，而同批另有三条车道也在往 trade 落迁移）。
--
-- ===========================================================================
-- 一、「索赔不是自动到账」这句话落在哪里 —— 为什么它不是一个 if
-- ===========================================================================
--
-- 蓝图加粗句「**索赔不是自动到账**，系统只跟踪流程与金额，冲减应付需财务确认」。
-- 它必须落成**结构**，而不是落成一段提示文案：
--
--   ① **冲减金额与「已生效的冲减金额」是两列**。
--      supplier_claim_settlement.requested_amount 是「登记的赔付金额」，
--      supplier_claim_settlement.effective_amount 是**已生效**的冲减金额。
--      两者在财务确认之前**必须不相等**（effective 恒为 0）。
--   ② **表级 CHECK 钉死**（守卫④）：
--        settlement_status <> 'CONFIRMED'  ⇒ effective_amount = 0
--        settlement_status =  'CONFIRMED'  ⇒ effective_amount = requested_amount
--                                        且 confirmed_by / confirmed_at 非空
--                                        且 finance_reference 非空
--      ⇒ 「没经过财务确认就把冲减金额写成生效」在任何 SQL 路径下都存不进去，
--        绕过后端直连数据库也不行。
--   ③ **索赔单头上也有同样的两列**（applied_offset_amount），
--      且由 DB 触发器从赔付分录**重算** —— 头尾两处都得对得上。
--   ④ **终态不可逆**：已被财务确认的赔付分录**整行冻结**，
--      已被冲减生效的索赔单**整行冻结**。冲减生效是一次财务动作，
--      事后反悔要走人工冲销流程，不是点一下改个数。
--
-- ⚠️ **下游 12 财务（finance 服务）当前不存在**（backend/services/ 下只有
--   identity / catalog / channel / warehouse / trade / crm）。
--   所以本模块**不写任何跨服务分录**：冲减应付当前以 **trade 侧自有事实记录**
--   （supplier_claim_settlement）落地，等 finance 服务落地后再经**授权 API / event** 衔接。
--   同样，6.5.1 采购对账当前**未交付** ⇒ 本模块不写对账单、不建对账视图。
--
-- ===========================================================================
-- 二、索赔的两条硬规则
-- ===========================================================================
--
-- 【硬规则 1】**必须同时有质检结论与责任判定，且两者指向同一批货**
--
--   蓝图输入句写的是「质检结论**与**责任判定」—— 两个都要，不是二选一：
--   * 只有质检结论（11.2.3.1）⇒ 知道「这批货坏了」，但不知道**该找谁赔**；
--   * 只有责任判定（11.1.2.2）⇒ 知道「该找供应商」，但没有「坏成什么样、值多少钱」的举证。
--   本模块的「一行索赔明细」= 一条质检结论 + 一条责任判定，
--   且两者的 inspection_id 必须一致（守卫②）——
--   拿 A 批货的质检结论配 B 批货的责任判定，在对质时一眼就穿帮。
--
--   ⚠️ **责任环节必须落在「可向供应商索赔」的集合**（守卫②）：
--     可索赔 = PRODUCTION（生产）/ DESCRIPTION（描述不符）
--     不可索赔 = TRANSPORT（运输）/ WAREHOUSE（仓储）/ BUYER_FACTOR（买家因素）
--                 / UNDETERMINED（责任待定）
--   「责任待定」时发起索赔 = 拿不确定的责任去和供应商对质；
--   运输与仓储环节的责任在承运商与我们自己身上，不在供应商的产品上。
--
-- 【硬规则 2】**索赔金额不得超过索赔基数，基数由 DB 取，调用方传不进来**
--
--   索赔基数 = 该质检单的**原值 original_value**（= 订单行单价 × 实收数量，V179 算的真值）。
--   传输层**没有**基数字段 ⇒ 调用方想传也传不进来（守卫②）。
--   逐行金额上限还要按数量折算：
--       max_claim_amount = round(original_value × claim_qty / inspected_qty, 4)
--   ⇒ 「4 件里只坏 1 件，却按 4 件的价索赔」在本模块没有代码路径。
--   头表另有 claimable_base_amount = Σ 逐行基数、claimed_amount = Σ 逐行金额，
--   两者都由 DB 触发器**重算**（守卫⑤），并由表级 CHECK 兜住「总额不得超过基数」。
--
-- ===========================================================================
-- 三、状态机：一个状态列到底
-- ===========================================================================
--
-- DRAFT       草稿：明细还在录，**没有**向供应商发起
-- SUBMITTED   已提交：已向供应商发起索赔
-- NEGOTIATING 协商中：已记录协商记录
-- AGREED      已达成：协商达成一致（agreed_amount 取自「达成一致」那条协商记录）
-- SETTLED     已结算：赔付 / 冲减应付**已被财务确认**生效
-- REJECTED    已驳回：供应商拒绝或内部驳回（终态）
-- WITHDRAWN   已撤回：主动撤回（终态）
--
-- ⚠️ 三条流转约束（守卫③）：
--   ① 提交前必须**至少一条明细**且索赔金额 > 0 ——
--      没有明细的索赔单发出去就是一句「你们赔钱」。
--   ② **协商是必经环节**：SUBMITTED → NEGOTIATING 必须已有协商记录，
--      NEGOTIATING → AGREED 必须有一条 type='AGREEMENT' 且 agreed_amount > 0 的记录，
--      且达成金额**必须等于**那条记录里的金额（不许在单头上另填一个数）。
--   ③ SETTLED **只能**由赔付分录的触发器驱动，且必须确实存在一条
--      settlement_status='CONFIRMED' 的赔付分录。
-- ⚠️ **终态不可逆**：SETTLED / REJECTED / WITHDRAWN 三个终态**整行冻结**（守卫⑥）。
--   索赔单是要拿去和供应商对质的凭据，「我们当时报了多少、协商成多少、
--   财务确认冲减了多少」出现两个答案比报错更难收拾。
--
-- ===========================================================================
-- 四、冲减应付的「事实记录」与「生效」是两件事
-- ===========================================================================
--
-- 蓝图输出句：「索赔单 + **冲减应付或退款记录**」。
-- 本模块把「冲减应付或退款」建模成**赔付方式** settlement_method：
--   OFFSET_PAYABLE   冲减应付（供应商货款里扣）
--   SUPPLIER_REFUND  供应商退款（供应商把钱退回来）
-- 两种方式**金额口径与财务确认要求完全一样** —— 蓝图说的是「冲减应付**或**退款记录」，
-- 二者都是「供应商认了、钱要落地」这一步，都**不是自动到账**。
--
-- ===========================================================================
-- 五、跨服务边界
-- ===========================================================================
--
-- * 12 财务（冲减应付）：**当前不存在** ⇒ 本模块只落 trade 侧自有事实记录，
--   财务确认动作在 trade 侧以「财务确认」这一独立动作 + 独立权限码落地，
--   finance 服务落地后经**授权 API / event** 衔接，不在本模块写跨库分录。
-- * 6.5.1 采购对账：**未交付** ⇒ 本模块不写对账单、不建共享视图。
-- * 供应商主数据：catalog 独立库 ⇒ 只读 trade 侧镜像表 quality_supplier_ref。
-- * 批次真值：warehouse 独立库 ⇒ 只读 11.1.2.2 已落库的 lot_code / lot_supplier_ref_id 快照列。
--
-- ===========================================================================
-- 六、类目与命名
-- ===========================================================================
CREATE TABLE supplier_claim (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  claim_no varchar(32) NOT NULL,

  -- ---- 供应商：引用 trade 侧镜像表 quality_supplier_ref（V185）----
  --   ⚠️ 供应商主数据在 catalog 独立库，跨库读不到；这里引用的 supplier_ref_id
  --   与 11.1.2.2 的 lot_supplier_ref_id 是**同一套内部口径**（V185 镜像表的既定形状）。
  supplier_ref_id uuid NOT NULL,
  -- 快照两列由 DB 触发器填（守卫①），调用方传不进来。
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 索赔原因（职责句「对质量原因退货向供应商发起索赔」）----
  claim_reason_code varchar(24) NOT NULL CHECK(claim_reason_code IN (
      'QUALITY_DEFECT','WRONG_ITEM','SHORTAGE','DAMAGED','OTHER'
  )),
  claim_reason_note text NOT NULL DEFAULT '' CHECK(length(claim_reason_note) <= 2000),

  -- ---- 币种与金额：全部 NUMERIC(19,4)，禁浮点（AGENTS.md）----
  claim_currency char(3) NOT NULL CHECK(claim_currency ~ '^[A-Z]{3}$'),
  -- 逐行金额之和 / 逐行基数之和：均由 DB 触发器**重算**（守卫⑤），调用方传不进来。
  claimed_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(claimed_amount >= 0),
  claimable_base_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(claimable_base_amount >= 0),
  -- 达成金额：**只能**来自「达成一致」那条协商记录（守卫③）。
  agreed_amount numeric(19,4) CHECK(agreed_amount IS NULL OR agreed_amount >= 0),

  -- ---- 状态机（第三节）----
  claim_status varchar(16) NOT NULL CHECK(claim_status IN (
      'DRAFT','SUBMITTED','NEGOTIATING','AGREED','SETTLED','REJECTED','WITHDRAWN'
  )),

  -- ---- 冲减应付（trade 侧自有事实记录的汇总口径）----
  --   ⚠️ applied_offset_amount 在**财务确认之前恒为 0**（第一节）。
  offset_status varchar(24) NOT NULL DEFAULT 'NOT_APPLIED' CHECK(offset_status IN (
      'NOT_APPLIED','PENDING_FINANCE_CONFIRM','APPLIED','FINANCE_REJECTED'
  )),
  applied_offset_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(applied_offset_amount >= 0),

  -- ---- 动作留痕：谁在什么时候把它推到哪一步 ----
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  submitted_by uuid,
  submitted_at timestamptz,
  rejected_by uuid,
  rejected_at timestamptz,
  reject_reason text NOT NULL DEFAULT '' CHECK(length(reject_reason) <= 1000),
  withdrawn_by uuid,
  withdrawn_at timestamptz,
  withdraw_reason text NOT NULL DEFAULT '' CHECK(length(withdraw_reason) <= 1000),
  updated_at timestamptz NOT NULL DEFAULT now(),

  -- 乐观锁：改状态都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,claim_no),

  -- **总额不得超过总额基数**（与逐行的 CHECK 互为兜底）。
  CHECK(claimed_amount <= claimable_base_amount),
  -- 已达成必须有金额；未达成不许有金额。
  CHECK(claim_status <> 'AGREED' OR (agreed_amount IS NOT NULL AND agreed_amount > 0)),
  CHECK(claim_status IN ('AGREED','SETTLED') OR agreed_amount IS NULL),
  -- 已结算 ⇒ 冲减已生效（金额 > 0）。这一条把「已结算」和「财务已确认」钉在一起。
  CHECK(claim_status <> 'SETTLED' OR (applied_offset_amount > 0 AND offset_status = 'APPLIED')),
  -- **未确认的冲减不得生效**：offset_status 非 APPLIED 时生效金额恒为 0。
  CHECK(offset_status <> 'APPLIED' OR applied_offset_amount > 0),
  -- 驳回 / 撤回必须留理由与人和时刻。
  CHECK(claim_status <> 'REJECTED'
        OR (rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND length(btrim(reject_reason)) > 0)),
  CHECK(claim_status <> 'WITHDRAWN'
        OR (withdrawn_by IS NOT NULL AND withdrawn_at IS NOT NULL AND length(btrim(withdraw_reason)) > 0)),
  -- 提交必须留人和时刻。
  CHECK(claim_status NOT IN ('SUBMITTED','NEGOTIATING','AGREED','SETTLED')
        OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL))
);

COMMENT ON TABLE supplier_claim IS
  '11.2.3.3 供应商索赔单：关联质检结论与责任判定，跟踪协商与赔付；冲减应付需财务确认后才生效';

-- ===========================================================================
-- 二、索赔明细（一行 = 一条质检结论 + 一条责任判定）
-- ===========================================================================
CREATE TABLE supplier_claim_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  claim_id uuid NOT NULL
      REFERENCES supplier_claim(id) ON DELETE CASCADE,

  -- ---- 上游 11.2.3.1：质检结论（**已判定**才可索赔）----
  inspection_id uuid NOT NULL,
  -- ---- 上游 11.1.2.2：责任判定（**已归因**才可索赔）----
  attribution_id uuid NOT NULL,

  -- 标的物与批次快照：归因单上的批次优先（它是入库那一刻的交接凭据）。
  lot_id uuid,
  lot_code varchar(128) NOT NULL DEFAULT '',
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- 索赔数量 = 本行覆盖的实检数量（NUMERIC(19,6)，禁浮点）。
  claim_qty numeric(19,6) NOT NULL CHECK(claim_qty > 0),

  -- ---- 金额 ----
  -- 本行索赔金额（调用方给，但**不得超过** max_claim_amount）。
  claim_amount numeric(19,4) NOT NULL CHECK(claim_amount >= 0),
  -- 币种：由 DB 从质检结论取，调用方传不进来。
  claim_currency char(3) NOT NULL CHECK(claim_currency ~ '^[A-Z]{3}$'),
  -- 索赔基数（= 质检单原值，全量）：由 DB 触发器取，调用方传不进来。
  claimable_base_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(claimable_base_amount >= 0),
  -- 本行金额上限（按数量折算）：由 DB 触发器取，调用方传不进来。
  max_claim_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(max_claim_amount >= 0),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一张质检结论只能被索赔一次：重复索赔会让同一批货的损失被算两遍。
  UNIQUE(tenant_id,inspection_id),

  -- **本行金额不得超过本行上限**（= 原值按数量折算）。
  CHECK(claim_amount <= max_claim_amount),
  -- 本行上限不得超过全量基数。
  CHECK(max_claim_amount <= claimable_base_amount)
);

COMMENT ON TABLE supplier_claim_item IS
  '11.2.3.3 索赔明细：一行 = 一条已判定的质检结论 + 一条已归因的责任判定';

-- ===========================================================================
-- 三、协商记录（职责句输入项「协商记录」；追加式）
-- ===========================================================================
CREATE TABLE supplier_claim_negotiation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  claim_id uuid NOT NULL
      REFERENCES supplier_claim(id) ON DELETE CASCADE,

  -- 序号由 DB 排（守卫④）：协商的**先后顺序**是证据链的一部分，
  -- 让调用方自己填序号就等于让「谁先提的、谁后答的」变成可以改的东西。
  negotiation_seq int NOT NULL CHECK(negotiation_seq > 0),

  -- 五种协商形态：内部复核 → 供应商答复 → 对方还价 → 达成一致 → 协商破裂。
  negotiation_type varchar(20) NOT NULL CHECK(negotiation_type IN (
      'CLAIM_REVIEW','SUPPLIER_RESPONSE','COUNTER_OFFER','AGREEMENT','REJECTION'
  )),
  -- 记录这一条的是哪一方。
  participant_role varchar(12) NOT NULL CHECK(participant_role IN ('INTERNAL','SUPPLIER')),

  -- 提议金额（对方或我方的开价）：没有金额的协商记录不是协商记录。
  proposed_amount numeric(19,4) CHECK(proposed_amount IS NULL OR proposed_amount >= 0),
  -- 达成金额：**只有** 'AGREEMENT' 类型有值。
  agreed_amount numeric(19,4) CHECK(agreed_amount IS NULL OR agreed_amount >= 0),

  negotiation_note text NOT NULL DEFAULT '' CHECK(length(negotiation_note) <= 2000),

  -- 协商发生的时间（可能早于录入时间 —— 补录是常态）。
  occurred_at timestamptz NOT NULL,
  recorded_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,claim_id,negotiation_seq),

  -- 「达成一致」必须有达成金额（守卫④再钉一道）。
  CHECK(negotiation_type <> 'AGREEMENT' OR (agreed_amount IS NOT NULL AND agreed_amount > 0)),
  -- 非「达成一致」不许带达成金额：免得「达成金额」在记录里有第二个出处。
  CHECK(negotiation_type = 'AGREEMENT' OR agreed_amount IS NULL),
  -- 至少要有一个金额或一段说明。
  CHECK(proposed_amount IS NOT NULL OR length(btrim(negotiation_note)) > 0)
);

COMMENT ON TABLE supplier_claim_negotiation IS
  '11.2.3.3 协商记录：追加式证据链，序号由 DB 排，达成金额只出自 AGREEMENT 类型';

-- ===========================================================================
-- 四、赔付 / 冲减应付分录（**trade 侧自有事实记录**）
-- ===========================================================================
--
-- ⚠️ 蓝图输出句的「冲减应付或退款记录」在本表落地。
--   下游 12 财务（finance 服务）**当前不存在** ⇒ 本表是 trade 侧的事实记录，
--   与 12 财务的最终对账留待 finance 落地后经**授权 API / event** 衔接。
--   本模块**绝不**去读或写一个不存在的 finance 服务。
CREATE TABLE supplier_claim_settlement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  claim_id uuid NOT NULL
      REFERENCES supplier_claim(id) ON DELETE CASCADE,

  -- 赔付方式：冲减应付 / 供应商退款。二者的财务确认要求完全一样（第四节）。
  settlement_method varchar(20) NOT NULL CHECK(settlement_method IN (
      'OFFSET_PAYABLE','SUPPLIER_REFUND'
  )),

  settlement_currency char(3) NOT NULL CHECK(settlement_currency ~ '^[A-Z]{3}$'),

  -- 登记的赔付金额（要退 / 要扣多少钱）。
  requested_amount numeric(19,4) NOT NULL CHECK(requested_amount > 0),
  -- **已生效**的冲减金额：财务确认之前**恒为 0**（第一节）。
  effective_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(effective_amount >= 0),

  settlement_status varchar(24) NOT NULL CHECK(settlement_status IN (
      'PENDING_FINANCE_CONFIRM','CONFIRMED','REJECTED'
  )),

  -- 财务确认后回填的单据号：事后要答得出「冲的是哪一笔」。
  finance_reference varchar(128) NOT NULL DEFAULT '',
  confirmed_by uuid,
  confirmed_at timestamptz,
  rejected_by uuid,
  rejected_at timestamptz,
  reject_reason text NOT NULL DEFAULT '' CHECK(length(reject_reason) <= 1000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),

  -- **「没有财务确认就没有生效」的三条 CHECK**（第一节 ②）：
  --   未确认 ⇒ 生效金额恒为 0；
  --   已确认 ⇒ 生效金额 = 登记金额，且确认人 / 确认时刻 / 财务单据号齐全。
  CHECK(settlement_status <> 'CONFIRMED'
        OR (effective_amount = requested_amount
            AND confirmed_by IS NOT NULL
            AND confirmed_at IS NOT NULL
            AND length(btrim(finance_reference)) > 0)),
  CHECK(settlement_status <> 'PENDING_FINANCE_CONFIRM' OR effective_amount = 0),
  CHECK(settlement_status <> 'REJECTED'
        OR (effective_amount = 0 AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL))
);

COMMENT ON TABLE supplier_claim_settlement IS
  '11.2.3.3 赔付/冲减应付分录：trade 侧自有事实记录；effective_amount 在财务确认前恒为 0';

-- ===========================================================================
-- 五、留痕（追加式）
-- ===========================================================================
CREATE TABLE supplier_claim_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  claim_id uuid NOT NULL,
  event_type varchar(28) NOT NULL CHECK(event_type IN (
      'CREATED','ITEM_ADDED','ITEM_REMOVED',
      'SUBMITTED','NEGOTIATION_STARTED','NEGOTIATION_RECORDED','AGREED',
      'REJECTED','WITHDRAWN',
      'SETTLEMENT_REGISTERED','SETTLEMENT_CONFIRMED','SETTLEMENT_REJECTED','SETTLED'
  )),
  from_status varchar(16),
  to_status varchar(16),

  -- 事件专属金额：达成事件记达成金额，结算确认事件记生效金额。
  amount numeric(19,4),
  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

COMMENT ON TABLE supplier_claim_event IS
  '11.2.3.3 索赔留痕：追加式，DB 触发器自动追加，运行角色 UPDATE/DELETE 已收回';

-- ===========================================================================
-- 六、DB 守卫（服务层校验可以绕开，触发器绕不开）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 守卫① guard_supplier_claim_header：供应商必须存在且是 ACTIVE，快照由 DB 填
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_supplier_claim_header() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_ref record;
BEGIN
  SELECT supplier_code, supplier_name, ref_status INTO t_ref
    FROM quality_supplier_ref
   WHERE tenant_id = NEW.tenant_id AND supplier_ref_id = NEW.supplier_ref_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '供应商引用不存在或不属于当前租户：supplier_ref_id=%', NEW.supplier_ref_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_ref.ref_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '供应商引用已停用（当前 %），不能对它发起索赔：supplier_ref_id=%',
      t_ref.ref_status, NEW.supplier_ref_id USING ERRCODE = 'check_violation';
  END IF;

  -- 快照由 DB 填：调用方传的供应商编码 / 名称一律被覆盖。
  -- 供应商名字以后在镜像表里改了，这里跟着改 —— 名字是显示用的，金额才是事实。
  IF TG_OP = 'INSERT' OR NEW.supplier_ref_id IS DISTINCT FROM OLD.supplier_ref_id THEN
    NEW.supplier_code := t_ref.supplier_code;
    NEW.supplier_name := t_ref.supplier_name;
  END IF;

  -- 供应商只在草稿期可换：已提交之后换供应商等于把一封已经发出去的信换个收件人。
  IF TG_OP = 'UPDATE'
     AND NEW.supplier_ref_id IS DISTINCT FROM OLD.supplier_ref_id
     AND OLD.claim_status <> 'DRAFT' THEN
    RAISE EXCEPTION '索赔单已不是草稿（当前 %），不能换供应商：claim_id=%', OLD.claim_status, OLD.id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_supplier_claim_header
  BEFORE INSERT OR UPDATE OF supplier_ref_id ON supplier_claim
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_claim_header();

-- ---------------------------------------------------------------------------
-- 守卫② guard_supplier_claim_item_source：**硬规则 1 + 硬规则 2** 的落点
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_supplier_claim_item_source() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_claim record;
  t_inspection record;
  t_attribution record;
  t_max numeric(19,4);
BEGIN
  -- ---- 索赔单：本租户 + 必须是草稿 ----
  SELECT * INTO t_claim
    FROM supplier_claim
   WHERE tenant_id = NEW.tenant_id AND id = NEW.claim_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '索赔单不存在或不属于当前租户：claim_id=%', NEW.claim_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_claim.claim_status <> 'DRAFT' THEN
    RAISE EXCEPTION '索赔单已不是草稿（当前 %），不能再改明细（已提交的索赔金额是发给供应商的数，改了要重发）：claim_id=%',
      t_claim.claim_status, NEW.claim_id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 上游 11.2.3.1：质检结论必须**已判定** ----
  SELECT inspection_status, original_value, salvage_currency, inspected_qty,
         sku_id, sku_code, sku_name
    INTO t_inspection
    FROM return_quality_inspection
   WHERE tenant_id = NEW.tenant_id AND id = NEW.inspection_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '质检结论不存在或不属于当前租户：inspection_id=%', NEW.inspection_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_inspection.inspection_status <> 'CONCLUDED' THEN
    RAISE EXCEPTION '质检结论还没有判定（当前 %），不能索赔：inspection_id=%',
      t_inspection.inspection_status, NEW.inspection_id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 上游 11.1.2.2：责任判定必须**已归因** ----
  SELECT attribution_status, attributed_stage, stage_label, inspection_id,
         lot_id, lot_code, lot_supplier_ref_id, sku_id, sku_code, sku_name
    INTO t_attribution
    FROM product_issue_attribution
   WHERE tenant_id = NEW.tenant_id AND id = NEW.attribution_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '责任判定不存在或不属于当前租户：attribution_id=%', NEW.attribution_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_attribution.attribution_status <> 'ATTRIBUTED' THEN
    RAISE EXCEPTION '责任判定还没有出结论（当前 %），不能索赔：attribution_id=%',
      t_attribution.attribution_status, NEW.attribution_id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 硬规则 1 之二：环节必须落在「可向供应商索赔」的集合 ----
  IF t_attribution.attributed_stage NOT IN ('PRODUCTION','DESCRIPTION') THEN
    RAISE EXCEPTION '该责任判定落在「%」环节，不是供应商的产品责任，不能向供应商索赔（可索赔环节：生产 / 描述不符；运输与仓储的责任在承运商与我们自己身上，买家因素与责任待定更不能算到供应商头上）：attribution_id=%',
      t_attribution.attributed_stage, NEW.attribution_id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 硬规则 1 之三：质检结论与责任判定必须指向**同一批货** ----
  IF t_attribution.inspection_id IS NULL THEN
    RAISE EXCEPTION '该责任判定没有挂质检结论，不能索赔（蓝图输入句要求「质检结论与责任判定」两者都有）：attribution_id=%',
      NEW.attribution_id USING ERRCODE = 'check_violation';
  END IF;

  IF t_attribution.inspection_id <> NEW.inspection_id THEN
    RAISE EXCEPTION '质检结论与责任判定不是同一批货（该责任判定挂的质检结论是 %，本行填的是 %）',
      t_attribution.inspection_id, NEW.inspection_id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 供应商一致性：归因的批次供应商与索赔单的供应商必须是同一家 ----
  IF t_attribution.lot_supplier_ref_id IS NOT NULL
     AND t_attribution.lot_supplier_ref_id <> t_claim.supplier_ref_id THEN
    RAISE EXCEPTION '该归因的批次供应商与本索赔单的供应商不是同一家（批次供应商 %，索赔单供应商 %）',
      t_attribution.lot_supplier_ref_id, t_claim.supplier_ref_id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 数量不得超过实检数量 ----
  IF NEW.claim_qty > t_inspection.inspected_qty THEN
    RAISE EXCEPTION '索赔数量（%）不得超过实检数量（%）：inspection_id=%',
      NEW.claim_qty, t_inspection.inspected_qty, NEW.inspection_id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 硬规则 2：币种与基数由 DB 取 ----
  --   币种取质检结论的原值币种（索赔的是那批货的钱，币种跟着那批货走）。
  IF t_inspection.salvage_currency <> t_claim.claim_currency THEN
    RAISE EXCEPTION '索赔单币种（%）与质检结论的原值币种（%）不一致，换算口径会错',
      t_claim.claim_currency, t_inspection.salvage_currency USING ERRCODE = 'check_violation';
  END IF;

  NEW.claim_currency       := t_inspection.salvage_currency;
  NEW.claimable_base_amount := t_inspection.original_value;

  -- 本行上限：按索赔数量折算原值。
  IF t_inspection.inspected_qty > 0 THEN
    t_max := round(t_inspection.original_value * NEW.claim_qty / t_inspection.inspected_qty, 4);
  ELSE
    t_max := t_inspection.original_value;
  END IF;
  NEW.max_claim_amount := t_max;

  -- 快照：归因单上的批次与 SKU 优先（它是入库那一刻的交接凭据）。
  NEW.lot_id   := t_attribution.lot_id;
  NEW.lot_code := coalesce(t_attribution.lot_code, '');
  NEW.sku_id   := coalesce(t_attribution.sku_id, t_inspection.sku_id);
  NEW.sku_code := coalesce(nullif(t_attribution.sku_code, ''), nullif(t_inspection.sku_code, ''), '');
  NEW.sku_name := coalesce(nullif(t_attribution.sku_name, ''), nullif(t_inspection.sku_name, ''), '');

  -- **索赔金额不得超过本行上限**（表级 CHECK 再兜一道）。
  IF NEW.claim_amount > NEW.max_claim_amount THEN
    RAISE EXCEPTION '索赔金额（%）不得超过本行上限（%）= 质检原值 % × 数量 % ÷ 实检数量 %',
      NEW.claim_amount, NEW.max_claim_amount, t_inspection.original_value,
      NEW.claim_qty, t_inspection.inspected_qty USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_supplier_claim_item_source
  BEFORE INSERT ON supplier_claim_item
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_claim_item_source();

-- 明细的删除同样只允许在草稿期。
CREATE FUNCTION guard_supplier_claim_item_delete() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(16);
BEGIN
  SELECT claim_status INTO t_status
    FROM supplier_claim
   WHERE tenant_id = OLD.tenant_id AND id = OLD.claim_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '索赔单不存在或不属于当前租户：claim_id=%', OLD.claim_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status <> 'DRAFT' THEN
    RAISE EXCEPTION '索赔单已不是草稿（当前 %），不能删除明细：claim_id=%', t_status, OLD.claim_id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN OLD;
END
$$;

CREATE TRIGGER trg_guard_supplier_claim_item_delete
  BEFORE DELETE ON supplier_claim_item
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_claim_item_delete();

-- ---------------------------------------------------------------------------
-- 守卫③ guard_supplier_claim_transition：**状态机**（第三节的三条流转约束）
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_supplier_claim_transition() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_items int;
  t_agreed numeric(19,4);
  t_confirmed int;
BEGIN
  IF NEW.claim_status IS NOT DISTINCT FROM OLD.claim_status THEN
    RETURN NEW;
  END IF;

  IF OLD.claim_status IN ('SETTLED','REJECTED','WITHDRAWN') THEN
    RAISE EXCEPTION '索赔单已是终态（%），不可再流转：claim_id=%', OLD.claim_status, OLD.id
      USING ERRCODE = 'check_violation';
  END IF;

  CASE NEW.claim_status
    WHEN 'SUBMITTED' THEN
      IF OLD.claim_status <> 'DRAFT' THEN
        RAISE EXCEPTION '只有草稿可以提交（当前 %）：claim_id=%', OLD.claim_status, OLD.id
          USING ERRCODE = 'check_violation';
      END IF;
      SELECT count(*) INTO t_items
        FROM supplier_claim_item
       WHERE tenant_id = NEW.tenant_id AND claim_id = NEW.id;
      IF t_items = 0 THEN
        RAISE EXCEPTION '索赔单还没有明细，不能提交（发出去的索赔单要能被对方看懂要赔哪几批货）：claim_id=%', NEW.id
          USING ERRCODE = 'check_violation';
      END IF;
      IF NEW.claimed_amount <= 0 THEN
        RAISE EXCEPTION '索赔金额必须大于 0：claim_id=%', NEW.id USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'NEGOTIATING' THEN
      IF OLD.claim_status <> 'SUBMITTED' THEN
        RAISE EXCEPTION '只有已提交的索赔单可以进入协商（当前 %）：claim_id=%', OLD.claim_status, OLD.id
          USING ERRCODE = 'check_violation';
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM supplier_claim_negotiation n
         WHERE n.tenant_id = NEW.tenant_id AND n.claim_id = NEW.id) THEN
        RAISE EXCEPTION '还没有协商记录，不能进入协商中：claim_id=%', NEW.id
          USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'AGREED' THEN
      IF OLD.claim_status <> 'NEGOTIATING' THEN
        RAISE EXCEPTION '只有协商中的索赔单可以置为已达成（当前 %）：claim_id=%', OLD.claim_status, OLD.id
          USING ERRCODE = 'check_violation';
      END IF;
      -- 达成金额**只能**出自「达成一致」那条协商记录。
      SELECT max(agreed_amount) INTO t_agreed
        FROM supplier_claim_negotiation n
       WHERE n.tenant_id = NEW.tenant_id AND n.claim_id = NEW.id
         AND n.negotiation_type = 'AGREEMENT' AND n.agreed_amount IS NOT NULL;
      IF t_agreed IS NULL THEN
        RAISE EXCEPTION '还没有「达成一致」的协商记录，不能置为已达成：claim_id=%', NEW.id
          USING ERRCODE = 'check_violation';
      END IF;
      IF NEW.agreed_amount IS DISTINCT FROM t_agreed THEN
        RAISE EXCEPTION '达成金额必须等于协商记录里的达成金额（%），不许在单头上另填一个数：claim_id=%',
          t_agreed, NEW.id USING ERRCODE = 'check_violation';
      END IF;
      IF t_agreed > NEW.claimed_amount THEN
        RAISE EXCEPTION '达成金额（%）不得高于索赔金额（%）', t_agreed, NEW.claimed_amount
          USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'SETTLED' THEN
      -- **只能**由赔付分录的触发器驱动：必须确实有一条已被财务确认的分录。
      SELECT count(*) INTO t_confirmed
        FROM supplier_claim_settlement s
       WHERE s.tenant_id = NEW.tenant_id AND s.claim_id = NEW.id
         AND s.settlement_status = 'CONFIRMED';
      IF t_confirmed = 0 THEN
        RAISE EXCEPTION '没有财务确认过的赔付分录，不能置为已结算（**索赔不是自动到账**，冲减应付需财务确认）：claim_id=%',
          NEW.id USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'REJECTED' THEN
      IF OLD.claim_status NOT IN ('SUBMITTED','NEGOTIATING') THEN
        RAISE EXCEPTION '只有已提交或协商中的索赔单可以驳回（当前 %）：claim_id=%', OLD.claim_status, OLD.id
          USING ERRCODE = 'check_violation';
      END IF;
      IF length(btrim(NEW.reject_reason)) = 0 THEN
        RAISE EXCEPTION '驳回必须写理由：claim_id=%', NEW.id USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'WITHDRAWN' THEN
      IF OLD.claim_status NOT IN ('DRAFT','SUBMITTED','NEGOTIATING') THEN
        RAISE EXCEPTION '只有草稿、已提交或协商中的索赔单可以撤回（当前 %）：claim_id=%', OLD.claim_status, OLD.id
          USING ERRCODE = 'check_violation';
      END IF;
      IF length(btrim(NEW.withdraw_reason)) = 0 THEN
        RAISE EXCEPTION '撤回必须写理由：claim_id=%', NEW.id USING ERRCODE = 'check_violation';
      END IF;

    ELSE
      RAISE EXCEPTION '未知的索赔状态流转：% -> %（claim_id=%）', OLD.claim_status, NEW.claim_status, NEW.id
        USING ERRCODE = 'check_violation';
  END CASE;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_supplier_claim_transition
  BEFORE UPDATE OF claim_status ON supplier_claim
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_claim_transition();

-- ---------------------------------------------------------------------------
-- 守卫⑥ guard_supplier_claim_frozen：**终态不可逆**
-- ---------------------------------------------------------------------------
-- 一旦结算 / 驳回 / 撤回，这一行**整行**冻结。
-- 刻意不做「只冻结金额、其余要素还能改」：索赔单要拿去和供应商对质，
-- 「我们当时报了多少、协商成多少、财务确认冲减了多少」必须只有一个答案。
CREATE FUNCTION guard_supplier_claim_frozen() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.claim_status IN ('SETTLED','REJECTED','WITHDRAWN') AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION '索赔单已是终态（%），不可修改（终态不可逆）：claim_id=%', OLD.claim_status, OLD.id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_supplier_claim_frozen
  BEFORE UPDATE ON supplier_claim
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_claim_frozen();

-- ---------------------------------------------------------------------------
-- 守卫④ guard_supplier_claim_negotiation：协商记录只能挂在在途单上，序号由 DB 排
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_supplier_claim_negotiation() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(16);
  t_seq int;
BEGIN
  SELECT claim_status INTO t_status
    FROM supplier_claim
   WHERE tenant_id = NEW.tenant_id AND id = NEW.claim_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '索赔单不存在或不属于当前租户：claim_id=%', NEW.claim_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status NOT IN ('SUBMITTED','NEGOTIATING') THEN
    RAISE EXCEPTION '索赔单当前是「%」，不能再记协商（协商只发生在已提交与协商中两个状态）：claim_id=%',
      t_status, NEW.claim_id USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.negotiation_type = 'AGREEMENT'
     AND (NEW.agreed_amount IS NULL OR NEW.agreed_amount <= 0) THEN
    RAISE EXCEPTION '「达成一致」的协商记录必须写达成金额：claim_id=%', NEW.claim_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 序号由 DB 排：协商的先后顺序是证据链的一部分。
  SELECT coalesce(max(negotiation_seq), 0) + 1 INTO t_seq
    FROM supplier_claim_negotiation
   WHERE tenant_id = NEW.tenant_id AND claim_id = NEW.claim_id;
  NEW.negotiation_seq := t_seq;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_supplier_claim_negotiation
  BEFORE INSERT ON supplier_claim_negotiation
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_claim_negotiation();

-- ---------------------------------------------------------------------------
-- 守卫⑦ guard_supplier_claim_settlement：**冲减生效必须有财务确认**
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_supplier_claim_settlement() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_claim record;
BEGIN
  SELECT * INTO t_claim
    FROM supplier_claim
   WHERE tenant_id = NEW.tenant_id AND id = NEW.claim_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '索赔单不存在或不属于当前租户：claim_id=%', NEW.claim_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF TG_OP = 'INSERT' THEN
    -- 只有**已达成一致**的索赔单才能登记赔付。
    IF t_claim.claim_status <> 'AGREED' THEN
      RAISE EXCEPTION '只有已达成一致的索赔单才能登记赔付（当前 %）：claim_id=%',
        t_claim.claim_status, NEW.claim_id USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.settlement_currency <> t_claim.claim_currency THEN
      RAISE EXCEPTION '赔付币种（%）必须与索赔单币种（%）一致', NEW.settlement_currency, t_claim.claim_currency
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.requested_amount > t_claim.agreed_amount THEN
      RAISE EXCEPTION '登记赔付金额（%）不得超过达成金额（%）', NEW.requested_amount, t_claim.agreed_amount
        USING ERRCODE = 'check_violation';
    END IF;

    -- 登记时生效金额必须是 0：登记 ≠ 生效。
    IF NEW.effective_amount <> 0 THEN
      RAISE EXCEPTION '登记赔付时生效金额必须是 0（**没有财务确认就没有生效**）：settlement_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;

  ELSE
    -- **已被财务确认的分录是终态**：冲减生效是一次财务动作，不能事后反悔。
    IF OLD.settlement_status = 'CONFIRMED' AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '赔付分录已被财务确认，是终态不可修改：settlement_id=%', OLD.id
        USING ERRCODE = 'check_violation';
    END IF;

    -- 确认时必须留确认人、确认时刻与财务单据号。
    IF NEW.settlement_status = 'CONFIRMED'
       AND (NEW.confirmed_by IS NULL OR NEW.confirmed_at IS NULL
            OR length(btrim(NEW.finance_reference)) = 0) THEN
      RAISE EXCEPTION '财务确认必须留确认人、确认时刻与财务单据号（否则事后答不出「冲的是哪一笔」）：settlement_id=%',
        NEW.id USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.settlement_status = 'REJECTED'
       AND (NEW.rejected_by IS NULL OR NEW.rejected_at IS NULL) THEN
      RAISE EXCEPTION '财务驳回必须留驳回人与驳回时刻：settlement_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_supplier_claim_settlement
  BEFORE INSERT OR UPDATE ON supplier_claim_settlement
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_claim_settlement();

-- ---------------------------------------------------------------------------
-- 守卫⑤ supplier_claim_recalc_amounts：头表金额由明细**重算**
-- ---------------------------------------------------------------------------
-- 「报了多少」「最多能报多少」是**派生量**，不是调用方填的量。
-- 传进来也传不进（触发器每次都重算覆盖）。
CREATE FUNCTION supplier_claim_recalc_amounts() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_claim_id uuid;
  t_tenant uuid;
  t_claimed numeric(19,4);
  t_base numeric(19,4);
BEGIN
  IF TG_OP = 'DELETE' THEN
    t_claim_id := OLD.claim_id;
    t_tenant  := OLD.tenant_id;
  ELSE
    t_claim_id := NEW.claim_id;
    t_tenant  := NEW.tenant_id;
  END IF;

  SELECT coalesce(sum(claim_amount), 0), coalesce(sum(claimable_base_amount), 0)
    INTO t_claimed, t_base
    FROM supplier_claim_item
   WHERE tenant_id = t_tenant AND claim_id = t_claim_id;

  UPDATE supplier_claim
     SET claimed_amount = t_claimed,
         claimable_base_amount = t_base,
         updated_at = now()
   WHERE tenant_id = t_tenant AND id = t_claim_id;

  RETURN coalesce(NEW, OLD);
END
$$;

CREATE TRIGGER trg_supplier_claim_recalc_amounts
  AFTER INSERT OR UPDATE OR DELETE ON supplier_claim_item
  FOR EACH ROW EXECUTE FUNCTION supplier_claim_recalc_amounts();

-- ---------------------------------------------------------------------------
-- supplier_claim_sync_offset：冲减口径由赔付分录**重算**并回写索赔单
-- ---------------------------------------------------------------------------
-- ⚠️ 这是「冲减应付需财务确认」这条规则的**执行点**：
--   赔付分录一变成 CONFIRMED，头上的生效冲减金额才从 0 变成真实金额，
--   同时索赔单被推到 SETTLED（终态）。
--   分录还在 PENDING_FINANCE_CONFIRM 时，头上的生效金额恒为 0。
CREATE FUNCTION supplier_claim_sync_offset() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_claim_id uuid;
  t_tenant uuid;
  t_status varchar(24);
  t_amount numeric(19,4);
BEGIN
  IF TG_OP = 'DELETE' THEN
    t_claim_id := OLD.claim_id;
    t_tenant  := OLD.tenant_id;
  ELSE
    t_claim_id := NEW.claim_id;
    t_tenant  := NEW.tenant_id;
  END IF;

  SELECT
      CASE
        WHEN EXISTS (SELECT 1 FROM supplier_claim_settlement s
                      WHERE s.tenant_id = t_tenant AND s.claim_id = t_claim_id
                        AND s.settlement_status = 'CONFIRMED') THEN 'APPLIED'
        WHEN EXISTS (SELECT 1 FROM supplier_claim_settlement s
                      WHERE s.tenant_id = t_tenant AND s.claim_id = t_claim_id
                        AND s.settlement_status = 'PENDING_FINANCE_CONFIRM') THEN 'PENDING_FINANCE_CONFIRM'
        WHEN EXISTS (SELECT 1 FROM supplier_claim_settlement s
                      WHERE s.tenant_id = t_tenant AND s.claim_id = t_claim_id
                        AND s.settlement_status = 'REJECTED') THEN 'FINANCE_REJECTED'
        ELSE 'NOT_APPLIED'
      END,
      coalesce((SELECT sum(s.effective_amount) FROM supplier_claim_settlement s
                 WHERE s.tenant_id = t_tenant AND s.claim_id = t_claim_id
                   AND s.settlement_status = 'CONFIRMED'), 0)
    INTO t_status, t_amount;

  UPDATE supplier_claim
     SET offset_status = t_status,
         applied_offset_amount = t_amount,
         claim_status = CASE
           WHEN t_status = 'APPLIED' AND claim_status = 'AGREED' THEN 'SETTLED'
           ELSE claim_status
         END,
         updated_at = now()
   WHERE tenant_id = t_tenant AND id = t_claim_id;

  RETURN coalesce(NEW, OLD);
END
$$;

CREATE TRIGGER trg_supplier_claim_sync_offset
  AFTER INSERT OR UPDATE OR DELETE ON supplier_claim_settlement
  FOR EACH ROW EXECUTE FUNCTION supplier_claim_sync_offset();

-- ---------------------------------------------------------------------------
-- 留痕追加（只写「真变了」的事）
-- ---------------------------------------------------------------------------
CREATE FUNCTION supplier_claim_append_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.claim_status IS NOT DISTINCT FROM OLD.claim_status THEN
    RETURN NEW;
  END IF;

  INSERT INTO supplier_claim_event(
      id, tenant_id, claim_id, event_type, from_status, to_status,
      amount, reason_note, occurred_by, occurred_at)
  VALUES (
      gen_random_uuid(),
      NEW.tenant_id,
      NEW.id,
      CASE
        WHEN TG_OP = 'INSERT'                    THEN 'CREATED'
        WHEN NEW.claim_status = 'SUBMITTED'      THEN 'SUBMITTED'
        WHEN NEW.claim_status = 'NEGOTIATING'    THEN 'NEGOTIATION_STARTED'
        WHEN NEW.claim_status = 'AGREED'         THEN 'AGREED'
        WHEN NEW.claim_status = 'SETTLED'        THEN 'SETTLED'
        WHEN NEW.claim_status = 'REJECTED'       THEN 'REJECTED'
        WHEN NEW.claim_status = 'WITHDRAWN'      THEN 'WITHDRAWN'
        ELSE 'UPDATED'
      END,
      CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.claim_status END,
      NEW.claim_status,
      CASE
        WHEN NEW.claim_status = 'AGREED'  THEN NEW.agreed_amount
        WHEN NEW.claim_status = 'SETTLED' THEN NEW.applied_offset_amount
        ELSE NULL
      END,
      CASE
        WHEN TG_OP = 'INSERT'               THEN '登记索赔草稿：' || coalesce(NEW.claim_reason_code, '')
        WHEN NEW.claim_status = 'SUBMITTED' THEN '已向供应商发起索赔'
        WHEN NEW.claim_status = 'NEGOTIATING' THEN '进入协商'
        WHEN NEW.claim_status = 'AGREED'    THEN '协商达成一致：' || coalesce(NEW.agreed_amount::text, '')
        WHEN NEW.claim_status = 'SETTLED'   THEN '财务确认冲减生效：' || coalesce(NEW.applied_offset_amount::text, '')
        WHEN NEW.claim_status = 'REJECTED'  THEN coalesce(nullif(btrim(NEW.reject_reason), ''), '驳回')
        WHEN NEW.claim_status = 'WITHDRAWN' THEN coalesce(nullif(btrim(NEW.withdraw_reason), ''), '撤回')
        ELSE '草稿要素变更'
      END,
      coalesce(NEW.created_by, NEW.submitted_by, NEW.rejected_by, NEW.withdrawn_by),
      -- ⚠️ **每类事件用它自己那一刻**，不是 coalesce 出一列通用值。
      CASE
        WHEN TG_OP = 'INSERT'                THEN NEW.created_at
        WHEN NEW.claim_status = 'SUBMITTED'  THEN coalesce(NEW.submitted_at, now())
        WHEN NEW.claim_status = 'NEGOTIATING' THEN coalesce(NEW.updated_at, now())
        WHEN NEW.claim_status = 'AGREED'     THEN coalesce(NEW.updated_at, now())
        WHEN NEW.claim_status = 'SETTLED'    THEN coalesce(NEW.updated_at, now())
        WHEN NEW.claim_status = 'REJECTED'   THEN coalesce(NEW.rejected_at, now())
        WHEN NEW.claim_status = 'WITHDRAWN'  THEN coalesce(NEW.withdrawn_at, now())
        ELSE now()
      END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_supplier_claim_event
  AFTER INSERT OR UPDATE OF claim_status ON supplier_claim
  FOR EACH ROW EXECUTE FUNCTION supplier_claim_append_event();

-- 明细增删也留痕：「这批货是什么时候被报上去索赔的」要答得出来。
CREATE FUNCTION supplier_claim_append_item_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_claim_id uuid;
  t_tenant uuid;
  t_by uuid;
  t_note text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    t_claim_id := OLD.claim_id;
    t_tenant  := OLD.tenant_id;
    t_by      := OLD.created_by;
    t_note    := '移除索赔明细：' || coalesce(OLD.sku_code, '') || ' × ' || OLD.claim_qty::text;
  ELSE
    t_claim_id := NEW.claim_id;
    t_tenant  := NEW.tenant_id;
    t_by      := NEW.created_by;
    t_note    := '加入索赔明细：' || coalesce(NEW.sku_code, '') || ' × ' || NEW.claim_qty::text
                 || '，金额 ' || NEW.claim_amount::text;
  END IF;

  INSERT INTO supplier_claim_event(
      id, tenant_id, claim_id, event_type, from_status, to_status,
      amount, reason_note, occurred_by, occurred_at)
  SELECT gen_random_uuid(), t_tenant, t_claim_id,
         CASE WHEN TG_OP = 'INSERT' THEN 'ITEM_ADDED' ELSE 'ITEM_REMOVED' END,
         -- ⚠️ 明细增删**不是**状态流转 ⇒ from_status 留空（事件表的 CHECK 要求两端不等）。
         NULL, c.claim_status,
         CASE WHEN TG_OP = 'INSERT' THEN NEW.claim_amount ELSE OLD.claim_amount END,
         t_note, t_by, now()
    FROM supplier_claim c
   WHERE c.tenant_id = t_tenant AND c.id = t_claim_id;

  RETURN coalesce(NEW, OLD);
END
$$;

CREATE TRIGGER trg_supplier_claim_item_event
  AFTER INSERT OR DELETE ON supplier_claim_item
  FOR EACH ROW EXECUTE FUNCTION supplier_claim_append_item_event();

-- 协商记录留痕。
CREATE FUNCTION supplier_claim_append_negotiation_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  INSERT INTO supplier_claim_event(
      id, tenant_id, claim_id, event_type, from_status, to_status,
      amount, reason_note, occurred_by, occurred_at)
  SELECT gen_random_uuid(), NEW.tenant_id, NEW.claim_id, 'NEGOTIATION_RECORDED',
         NULL, c.claim_status,
         coalesce(NEW.agreed_amount, NEW.proposed_amount),
         '协商记录 #' || NEW.negotiation_seq::text || '（' || NEW.negotiation_type
           || '）：' || coalesce(nullif(btrim(NEW.negotiation_note), ''), '（无说明）'),
         NEW.recorded_by, NEW.occurred_at
    FROM supplier_claim c
   WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.claim_id;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_supplier_claim_negotiation_event
  AFTER INSERT ON supplier_claim_negotiation
  FOR EACH ROW EXECUTE FUNCTION supplier_claim_append_negotiation_event();

-- 赔付分录留痕。
CREATE FUNCTION supplier_claim_append_settlement_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_type varchar(28);
  t_amount numeric(19,4);
  t_note text;
  t_at timestamptz;
  t_by uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    t_type   := 'SETTLEMENT_REGISTERED';
    t_amount := NEW.requested_amount;
    t_note   := '登记赔付（' || NEW.settlement_method || '）：' || NEW.requested_amount::text
                || '，**等待财务确认后才生效**';
    t_at     := NEW.created_at;
    t_by     := NEW.created_by;
  ELSE
    t_type   := CASE NEW.settlement_status
                  WHEN 'CONFIRMED' THEN 'SETTLEMENT_CONFIRMED'
                  WHEN 'REJECTED'  THEN 'SETTLEMENT_REJECTED'
                  ELSE 'SETTLEMENT_REGISTERED'
                END;
    t_amount := NEW.effective_amount;
    t_note   := CASE NEW.settlement_status
                  WHEN 'CONFIRMED' THEN '财务确认冲减生效：' || NEW.effective_amount::text
                                      || '，财务单据号 ' || NEW.finance_reference
                  WHEN 'REJECTED'  THEN '财务驳回赔付：'
                                      || coalesce(nullif(btrim(NEW.reject_reason), ''), '（无理由）')
                  ELSE '赔付分录变更'
                END;
    t_at     := coalesce(NEW.confirmed_at, NEW.rejected_at, NEW.updated_at, now());
    t_by     := coalesce(NEW.confirmed_by, NEW.rejected_by, NEW.created_by);
  END IF;

  INSERT INTO supplier_claim_event(
      id, tenant_id, claim_id, event_type, from_status, to_status,
      amount, reason_note, occurred_by, occurred_at)
  SELECT gen_random_uuid(), NEW.tenant_id, NEW.claim_id, t_type,
         NULL, c.claim_status,
         t_amount, t_note, t_by, t_at
    FROM supplier_claim c
   WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.claim_id;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_supplier_claim_settlement_event
  AFTER INSERT OR UPDATE ON supplier_claim_settlement
  FOR EACH ROW EXECUTE FUNCTION supplier_claim_append_settlement_event();

-- ===========================================================================
-- 七、索引与发号器
-- ===========================================================================
-- ① 索赔单列表（按状态 + 时刻）。
CREATE INDEX supplier_claim_by_status
  ON supplier_claim(tenant_id, claim_status, created_at desc);
-- ② 按供应商反查（对账与供应商视角）。
CREATE INDEX supplier_claim_by_supplier
  ON supplier_claim(tenant_id, supplier_ref_id, created_at desc);
-- ③ 冲减口径统计（待财务确认队列的取数路径）。
CREATE INDEX supplier_claim_by_offset
  ON supplier_claim(tenant_id, offset_status, created_at desc);
-- ④ 明细按单读。
CREATE INDEX supplier_claim_item_by_claim
  ON supplier_claim_item(tenant_id, claim_id);
-- ⑤ 协商记录按单读（按序号）。
CREATE INDEX supplier_claim_negotiation_by_claim
  ON supplier_claim_negotiation(tenant_id, claim_id, negotiation_seq);
-- ⑥ 赔付分录按单读。
CREATE INDEX supplier_claim_settlement_by_claim
  ON supplier_claim_settlement(tenant_id, claim_id, created_at);
-- ⑦ **一张索赔只能有一条已确认的赔付分录**（DB 不变量而不是应用层先查一次 ——
--    并发下两个请求都能查到「还没有」，靠应用层判会双双通过）。
CREATE UNIQUE INDEX supplier_claim_one_confirmed_settlement
  ON supplier_claim_settlement(tenant_id, claim_id)
  WHERE settlement_status = 'CONFIRMED';
-- ⑧ 留痕回放。
CREATE INDEX supplier_claim_event_by_claim
  ON supplier_claim_event(tenant_id, claim_id, occurred_at);

CREATE SEQUENCE supplier_claim_no_seq;
GRANT USAGE,SELECT ON SEQUENCE supplier_claim_no_seq TO trade_app;

-- ===========================================================================
-- 八、多租户：RLS + 授权
-- ===========================================================================
-- 五张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：索赔单里同时有**供应商名、批次、SKU、金额、协商记录**，
--   是同一条链上信息最密的一张单据；端点又大多是**按 id 直达**的 URL ——
--   猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'supplier_claim','supplier_claim_item','supplier_claim_negotiation',
    'supplier_claim_settlement','supplier_claim_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  supplier_claim, supplier_claim_item, supplier_claim_negotiation,
  supplier_claim_settlement, supplier_claim_event TO trade_app;

-- 授权在前、收回在后。
-- ① **明细不许就地改**：改一条索赔明细等于改「我们报了什么」——
--    改错了就删掉重加（删除只在草稿期允许，触发器守着）。
--    金额、基数、上限、快照全由触发器填，更新也改不动它们。
REVOKE UPDATE ON supplier_claim_item FROM trade_app;
-- ② **协商记录是证据链，追加式**：能被悄悄改掉的证据等于没有证据。
REVOKE UPDATE,DELETE ON supplier_claim_negotiation FROM trade_app;
-- ③ **留痕追加式**：同理。
REVOKE UPDATE,DELETE ON supplier_claim_event FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：V179 的 return_quality_* 簇、V180 的 product_issue_* 簇、
-- V185 的 quality_supplier_ref 全部只读。
-- 本模块对它们的全部依赖都是 SELECT / JOIN 与「写入时刻查一次」，
-- 跨车道 DDL 回归面 = 0（同批另有三条车道也在往 trade 落迁移）。
