-- ===========================================================================
-- V342 · finance_bad_debt_case / finance_bad_debt_evidence / finance_bad_debt_event
--                     —— 12.1.1.3 坏账与核销
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.1.1.3 坏账与核销
--   - **职责**：处理坏账：争议损失确认、买家拒付、平台冻结无法收回的核销，
--     保留核销依据与审批。
--   - **输入**：核销申请；依据材料；审批。
--   - **输出**：坏账核销记录 + 会计处理 + 审计留痕。
--   - **关系**：上游 = 12.1.1.2、11.4.1.1 纠纷；下游 = 12.5.2.1 凭证。
--     **核销是财务终审动作**，需要完整依据链，不允许批量一键核销无审批。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不改 12.1.1.1 的任何一张表**：本文件是纯新增（三张新表 + 索引 + 授权）。
--     `finance_receivable` / `finance_receivable_line` 上 V338 留下的冻结守卫
--     （`guard_finance_receivable_frozen` / `guard_finance_receivable_line_frozen`）
--     与 CHECK 约束**一个字都不动，也不再往上加触发器**。
--
--   * **不写 `finance_receivable_line.settled_amount`**：V338 的注释原文写明
--     「只放行 settled_amount（核销金额）—— 那是 **12.2.1.2 的写入所有权**」。
--     本模块的核销走 V338 留的另一个口子：**作废 + 新单**
--     （作废原应收单 → 为仍可收回的余额新建一张承接单）。
--     ⇒ `settled_amount` 的写者在本仓恒为 12.2.1.2 一个，批次内两条车道不撞同一列。
--
--   * **不建跨模块外键**：`finance_receivable` 是 12.1.1.1 的表，
--     本模块对它是**只读 + 快照**：外键会在两个模块之间建立结构级耦合
--     （一方要改表结构，另一方就被挡住），所以这里只留 `receivable_id`（uuid）
--     与一组**快照列**（单号 / 币种 / 金额 / 已核销额 / 未核销余额 / 对象 / 店铺 / 到期日）。
--     快照是「核销当时的账」—— 依据链要能自证，不能靠事后去查别人表里的当前值。
--
--   * **不产生会计处理**：下游 12.5.2.1「凭证管理」未交付 ⇒ 本模块**不造凭证**：
--     `accounting_ref` 由 CHECK 钉死为空串、`accounting_status` 恒为
--     `PENDING_FINANCE`、`accounting_owner_module` 恒为 `12.5.2.1`。
--     「会计处理不可用」是**事实**，不是 0。
--
--   * **不做批量一键核销**：蓝图加粗句「**不允许批量一键核销无审批**」。
--     ⇒ 本文件与配套服务层**不提供任何批量核销端点**；
--     每一次核销都必须有申请、有依据材料、有审批（且审批人 ≠ 提交人）。
--
-- ---------------------------------------------------------------------------
-- 1. 三张表
-- ---------------------------------------------------------------------------
--
--   finance_bad_debt_case     —— 坏账核销申请单头（申请 / 审批 / 核销状态机）
--   finance_bad_debt_evidence —— 依据材料（一行 = 一项举证材料）
--   finance_bad_debt_event    —— 审计留痕（append-only）
--
--   三张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 状态机（枚举穷举，非法转换在 DB 层就写不出来）
-- ---------------------------------------------------------------------------
--
--   DRAFT ──提交审批──▶ PENDING_APPROVAL ──审批通过──▶ APPROVED ──执行核销──▶ WRITTEN_OFF
--     │                       │                         （终态）              （终态）
--     │                       ├──审批驳回──▶ REJECTED（终态）
--     │                       └──作废──▶ VOID（终态）
--     └──作废──▶ VOID（终态）
--
--   ⚠️ **终态整行冻结**（REJECTED / WRITTEN_OFF / VOID）：一列都不许再改。
--   ⚠️ **没有「一键批量核销」**：状态机只能一张一张走。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_bad_debt_case —— 坏账核销申请单头
-- ---------------------------------------------------------------------------
--
-- 「核销申请；依据材料；审批」三样输入各有各的落法：
--   申请 → 本表（DRAFT → PENDING_APPROVAL）
--   依据 → finance_bad_debt_evidence（子表，提交审批时**必须**至少一条）
--   审批 → 本表的 decided_by / decided_at / decision_comment
--
-- **金额全部 NUMERIC(19,4)**（禁浮点）；数量本模块没有 —— 坏账核销只处理金额。
CREATE TABLE finance_bad_debt_case (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 核销单号：BD- + 12 位大写十六进制。与 12.1.1.1 的 AR- 同一手法。
  case_no varchar(48) NOT NULL,
  -- ---- 目标应收（12.1.1.1 的 finance_receivable）**快照** ----
  -- ⚠️ 刻意**不建外键**（理由见文件头 0 节）：只留 uuid + 快照列。
  receivable_id uuid NOT NULL,
  receivable_no varchar(48) NOT NULL,
  shop_id uuid,
  shop_name varchar(120) NOT NULL DEFAULT '',
  counterparty_ref uuid,
  counterparty_name varchar(160) NOT NULL DEFAULT '',
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 核销**当时**这张应收的账：总额 / 已核销额 / 未核销余额。
  -- 三列是快照，不是实时读出来的 —— 依据链必须自证「当时是什么账」。
  receivable_amount numeric(19,4) NOT NULL CHECK(receivable_amount > 0),
  receivable_settled numeric(19,4) NOT NULL DEFAULT 0
    CHECK(receivable_settled >= 0 AND receivable_settled <= receivable_amount),
  open_balance numeric(19,4) NOT NULL CHECK(open_balance > 0),
  -- 核销金额：0 < 核销金额 ≤ 未核销余额。
  write_off_amount numeric(19,4) NOT NULL CHECK(write_off_amount > 0),
  -- ---- 坏账事由 ----
  -- DISPUTE_LOSS=争议损失确认 / BUYER_REFUSAL=买家拒付 /
  -- PLATFORM_FROZEN=平台冻结无法收回 / OTHER=其他（必须写明明细）。
  reason_type text NOT NULL
    CHECK(reason_type IN ('DISPUTE_LOSS','BUYER_REFUSAL','PLATFORM_FROZEN','OTHER')),
  reason_detail varchar(1000) NOT NULL CHECK(btrim(reason_detail) <> ''),
  -- ---- 11.4.1.1 纠纷（trade 服务）**快照** ----
  -- 跨服务 ⇒ 无外键、无跨库视图；只留 id + 快照列。
  -- dispute_id 为空 = 这笔坏账与平台纠纷无关（买家拒付 / 内部核销）。
  dispute_id uuid,
  dispute_no varchar(64) NOT NULL DEFAULT '',
  dispute_status varchar(24) NOT NULL DEFAULT '',
  dispute_amount numeric(19,4),
  dispute_currency char(3) CHECK(dispute_currency IS NULL OR dispute_currency ~ '^[A-Z]{3}$'),
  -- ---- 状态机 ----
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','WRITTEN_OFF','VOID')),
  -- 提交人 / 提交时刻
  submitted_by uuid,
  submitted_at timestamptz,
  -- 审批人 / 审批时刻 / 审批意见（**意见必填**，且审批人 ≠ 提交人）
  decided_by uuid,
  decided_at timestamptz,
  decision_comment varchar(1000) NOT NULL DEFAULT '',
  -- ---- 核销执行（写路径 = 「作废 + 新单」） ----
  write_off_at timestamptz,
  -- VOID_AND_REPLACE=作废原单并新建承接单；本模块不写 settled_amount，
  -- 所以没有第二种路径（留空串 = 还没核销）。
  write_off_method text NOT NULL DEFAULT ''
    CHECK(write_off_method IN ('','VOID_AND_REPLACE')),
  -- 被作废的原单号
  voided_receivable_no varchar(48) NOT NULL DEFAULT '',
  -- 承接单（核销后仍可收回的余额）；核销全额时为空。
  replacement_receivable_id uuid,
  replacement_receivable_no varchar(48) NOT NULL DEFAULT '',
  -- 承接单到期日是否被调整过（12.1.1.1 的建单端点不允许到期日早于确认日）。
  replacement_due_date_adjusted boolean NOT NULL DEFAULT false,
  -- ---- 会计处理（下游 12.5.2.1 未交付 ⇒ **不可用**） ----
  -- accounting_status 恒为 PENDING_FINANCE；accounting_ref 恒为空串。
  -- 「会计处理不可用」是事实，不是 0，也绝不造一条假凭证。
  accounting_status text NOT NULL DEFAULT 'PENDING_FINANCE'
    CHECK(accounting_status IN ('PENDING_FINANCE')),
  accounting_owner_module varchar(16) NOT NULL DEFAULT '12.5.2.1',
  accounting_ref varchar(64) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, case_no),
  -- 核销金额不得超过未核销余额。
  CONSTRAINT finance_bad_debt_amount_ck CHECK(write_off_amount <= open_balance),
  -- 未核销余额 = 总额 − 已核销额（快照自洽）。
  CONSTRAINT finance_bad_debt_balance_ck
    CHECK(open_balance = receivable_amount - receivable_settled),
  -- 已提交 ⇒ 提交人与提交时刻齐全。
  CONSTRAINT finance_bad_debt_submitted_ck CHECK (
    status = 'DRAFT'
    OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)
  ),
  -- 已审批 ⇒ 审批人 / 审批时刻 / 审批意见三样齐全，**且审批人 ≠ 提交人**。
  CONSTRAINT finance_bad_debt_decided_ck CHECK (
    status NOT IN ('APPROVED','REJECTED')
    OR (decided_by IS NOT NULL AND decided_at IS NOT NULL
        AND btrim(decision_comment) <> '' AND decided_by <> submitted_by)
  ),
  -- 已核销 ⇒ 核销时刻、核销路径、归属模块三样齐全。
  CONSTRAINT finance_bad_debt_written_off_ck CHECK (
    status <> 'WRITTEN_OFF'
    OR (write_off_at IS NOT NULL AND write_off_method <> '' AND btrim(voided_receivable_no) <> '')
  ),
  -- **绝不造凭证**：凭证号恒为空串。
  CONSTRAINT finance_bad_debt_accounting_ck CHECK(accounting_ref = ''),
  -- 有纠纷引用 ⇒ 必须带上纠纷快照（单号 + 状态），否则依据链断了一截。
  CONSTRAINT finance_bad_debt_dispute_ck CHECK (
    dispute_id IS NULL OR (btrim(dispute_no) <> '' AND btrim(dispute_status) <> '')
  )
);

-- ---------------------------------------------------------------------------
-- 2. finance_bad_debt_evidence —— 依据材料
-- ---------------------------------------------------------------------------
--
-- 「输入：核销申请；**依据材料**；审批」—— 依据材料是**独立的一层**，
-- 不是单据上的一个备注字段：一项材料有自己的类型、标题、外部单号、内容、
-- 附件引用与发生日期。
--
-- ⚠️ **提交审批后依据不可再动**（守卫 ④b）：审批人看到的依据链必须与提交时**逐字相同**，
-- 否则「审批依据」这件事本身就不成立。
CREATE TABLE finance_bad_debt_evidence (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  evidence_no int NOT NULL CHECK(evidence_no > 0),
  -- PLATFORM_DISPUTE_DECISION=平台裁决 / PLATFORM_FROZEN_NOTICE=平台冻结通知 /
  -- BUYER_REFUSAL=买家拒付凭证 / CHARGEBACK=拒付/退单记录 /
  -- INTERNAL_REVIEW=内部复核 / OTHER=其他
  evidence_type text NOT NULL CHECK(evidence_type IN (
    'PLATFORM_DISPUTE_DECISION','PLATFORM_FROZEN_NOTICE','BUYER_REFUSAL',
    'CHARGEBACK','INTERNAL_REVIEW','OTHER')),
  title varchar(200) NOT NULL CHECK(btrim(title) <> ''),
  -- 外部单号（平台裁决单号 / 拒付流水号 …）。可空。
  reference_no varchar(128) NOT NULL DEFAULT '',
  -- 材料内容（摘要 / 结论）。**必填**：没有内容的依据不是依据。
  content text NOT NULL CHECK(btrim(content) <> ''),
  -- 附件引用（对象存储 key）。可空 —— 材料可以是纯文本结论。
  attachment_ref varchar(255) NOT NULL DEFAULT '',
  -- 材料发生日期（date：裁决日 / 拒付日是日历日，不是某一瞬间）。
  occurred_on date,
  -- 移除标记：取消走 {@code status='REMOVED'}，**不物理删除** ——
  -- 与 12.1.1.1 的催收任务同一手法（取消走 status='CANCELLED'，删掉就没有痕迹了）。
  -- ⚠️ 运行角色对这张表的 DELETE 权限是收回的 ⇒ 物理删除在本服务里根本做不到。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REMOVED')),
  removed_at timestamptz,
  removed_by uuid,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 已移除 ⇒ 移除时刻与移除人齐全；未移除 ⇒ 两样都没有。
  CHECK((status = 'REMOVED') = (removed_at IS NOT NULL)),
  -- 同一张申请里，材料序号唯一（追加式，不复用序号）。
  UNIQUE(tenant_id, case_id, evidence_no),
  FOREIGN KEY(tenant_id, case_id) REFERENCES finance_bad_debt_case(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 3. finance_bad_debt_event —— 审计留痕（append-only）
-- ---------------------------------------------------------------------------
--
-- 与 12.1.1.1 的 finance_receivable_event、12.2.1.1 的 finance_receipt_event 同一手法：
-- 事件流是「当时发生了什么」的**唯一**出处，运行角色的 UPDATE / DELETE 已收回 ——
-- 事件只能追加。
--
-- ⚠️ 本模块**同时**记录两件留痕：
--   ① 申请单自己的状态流转（申请 / 提交 / 审批 / 驳回 / 核销 / 作废）；
--   ② **对 12.1.1.1 应收单的核销动作**（作废 + 承接单）。
--      后者必须留在本模块 —— 12.1.1.1 的 finance_receivable_event 的 event_type
--      是 CHECK 死的（CREATE TABLE 时写死的词表，本模块不得 ALTER 别人的表），
--      塞不进「坏账核销」这个事件类型；而**改别人的表**恰恰是本车道明令禁止的。
CREATE TABLE finance_bad_debt_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'CREATED','EVIDENCE_ADDED','EVIDENCE_REMOVED','SUBMITTED','APPROVED',
    'REJECTED','WRITTEN_OFF','VOIDED','ACCOUNTING_PENDING')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, case_id) REFERENCES finance_bad_debt_case(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================
--
-- **六道守卫**，每一道都在**服务层与 DB 各判一次**：
--   服务层给 HTTP 400 + 一句人话，DB 层给 check_violation（直连 SQL 也一样）。
--
--   ① 新单必须先是草稿          （finance_bad_debt_insert_draft）
--   ② 终态整行冻结              （finance_bad_debt_frozen）
--   ③ 状态机只允许合法转换      （finance_bad_debt_transition）
--   ④ 提交审批必须有依据材料    （finance_bad_debt_evidence_required）
--   ⑤ 审批人 ≠ 提交人 + 意见必填（finance_bad_debt_decision）
--   ⑥ 会计处理不可用（不造凭证）（finance_bad_debt_accounting）
--
--   ④b 依据链冻结（提交后依据不可增删改）（finance_bad_debt_evidence_frozen）
--
-- ---------------------------------------------------------------------------
-- ① 新建申请单**必须**先是草稿。
-- ---------------------------------------------------------------------------
--
--     为什么：确认（提交审批）那一步会校验「至少一条依据材料」。
--     如果允许直接 INSERT 一张 PENDING_APPROVAL 的单，这条校验就被绕过去了 ——
--     一张「零依据、已经躺在审批人面前」的申请单。
--     钉住入口 ⇒ 每张申请都必须走过「建单 → 落依据 → 提交」这条路。
CREATE OR REPLACE FUNCTION guard_finance_bad_debt_insert_draft() RETURNS trigger AS $$
BEGIN
  IF NEW.status <> 'DRAFT' THEN
    RAISE EXCEPTION '新建坏账核销申请必须先是草稿：提交审批要走 DRAFT→PENDING_APPROVAL（提交时才会校验依据材料）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bad_debt_insert_draft
  BEFORE INSERT ON finance_bad_debt_case
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bad_debt_insert_draft();

-- ---------------------------------------------------------------------------
-- ② 终态整行冻结。
-- ---------------------------------------------------------------------------
--
--     REJECTED / WRITTEN_OFF / VOID 是终态：一列都不许再改。
--     理由：核销是**终审动作** —— 批过就是批过了，驳回就是驳回了。
--     留痕靠事件流（append-only），不是靠「事后还能改单据」。
CREATE OR REPLACE FUNCTION guard_finance_bad_debt_frozen() RETURNS trigger AS $$
BEGIN
  IF OLD.status IN ('REJECTED','WRITTEN_OFF','VOID') THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '坏账核销申请 % 已是终态（%），不可再改：只能新建一张申请', OLD.case_no, OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bad_debt_frozen
  BEFORE UPDATE ON finance_bad_debt_case
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bad_debt_frozen();

-- ---------------------------------------------------------------------------
-- ③ 状态机只允许合法转换（枚举穷举）。
-- ---------------------------------------------------------------------------
--
--     非法转换在 DB 层写不出来 —— 直连 SQL 也一样。
--     顺带把「终态整行冻结」之外的状态语义钉死：
--     已提交的单子不许退回草稿（草稿可以随便改，已提交的不行 —— 改要重新提交）。
CREATE OR REPLACE FUNCTION guard_finance_bad_debt_transition() RETURNS trigger AS $$
DECLARE
  allowed boolean := false;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;

  allowed :=
    (OLD.status = 'DRAFT'             AND NEW.status IN ('PENDING_APPROVAL','VOID'))
    OR (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('APPROVED','REJECTED','VOID'))
    OR (OLD.status = 'APPROVED'        AND NEW.status IN ('WRITTEN_OFF','VOID'));

  IF NOT allowed THEN
    RAISE EXCEPTION '坏账核销申请 % 的状态不能从 % 变成 %', OLD.case_no, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.status = 'DRAFT' THEN
    RAISE EXCEPTION '坏账核销申请 % 不可退回草稿', OLD.case_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bad_debt_transition
  BEFORE UPDATE ON finance_bad_debt_case
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bad_debt_transition();

-- ---------------------------------------------------------------------------
-- ④ 提交审批**必须**已有依据材料。
-- ---------------------------------------------------------------------------
--
--     这是蓝图加粗句「核销是财务终审动作，需要完整依据链」在数据层的落法：
--     一张零依据的申请单**进不了审批**。
--     ⚠️ 直连 SQL 也一样被拒 —— 绕过服务层提交不出一个「无依据」的申请。
CREATE OR REPLACE FUNCTION guard_finance_bad_debt_evidence_required() RETURNS trigger AS $$
DECLARE
  evidence_count int;
BEGIN
  IF NEW.status = 'PENDING_APPROVAL' AND OLD.status = 'DRAFT' THEN
    SELECT count(*) INTO evidence_count
      FROM finance_bad_debt_evidence
     WHERE tenant_id = NEW.tenant_id AND case_id = NEW.id;
    IF evidence_count = 0 THEN
      RAISE EXCEPTION '坏账核销申请 % 没有依据材料，不可提交审批：核销是终审动作，必须有完整依据链', NEW.case_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bad_debt_evidence_required
  BEFORE UPDATE ON finance_bad_debt_case
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bad_debt_evidence_required();

-- ---------------------------------------------------------------------------
-- ④b 依据链冻结：申请一旦提交，依据材料就不可再增删改。
-- ---------------------------------------------------------------------------
--
--     理由：审批人看到的依据必须与提交时**逐字相同**。
--     提交之后再补一条「平台裁决书」，那这份审批就名不副实了。
CREATE OR REPLACE FUNCTION guard_finance_bad_debt_evidence_frozen() RETURNS trigger AS $$
DECLARE
  head_status text;
  target_case_id uuid;
BEGIN
  target_case_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.case_id ELSE NEW.case_id END;

  SELECT status INTO head_status FROM finance_bad_debt_case
   WHERE tenant_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END
     AND id = target_case_id;
  IF head_status IS NULL THEN RETURN NEW; END IF;

  IF head_status <> 'DRAFT' THEN
    RAISE EXCEPTION '坏账核销申请已提交，依据材料不可再改：依据链必须是提交时的样子（作废后请重新申请）'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 已移除的依据**不可复活**：依据链只进不出（append-only）。
  IF TG_OP = 'UPDATE' AND OLD.status = 'REMOVED' AND NEW.status = 'ACTIVE' THEN
    RAISE EXCEPTION '已移除的依据材料不可恢复：依据链只进不出，请另加一条说明'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bad_debt_evidence_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON finance_bad_debt_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bad_debt_evidence_frozen();

-- ---------------------------------------------------------------------------
-- ⑤ 审批人 ≠ 提交人，审批意见必填。
-- ---------------------------------------------------------------------------
--
--     蓝图加粗句「核销是财务终审动作」的两条硬要求：
--       · 审批人与提交人不能是同一个人（自己批自己的坏账 = 没有终审）；
--       · 审批意见必填（没有意见的审批无法追溯依据）。
--     表级 CHECK 已钉住形状，这里再钉一次**执行时刻**的完整性：
--     直连 SQL 也改不出「自己批自己」或「没意见就通过」的申请。
CREATE OR REPLACE FUNCTION guard_finance_bad_debt_decision() RETURNS trigger AS $$
BEGIN
  IF NEW.status IN ('APPROVED','REJECTED') AND OLD.status IS DISTINCT FROM NEW.status THEN
    IF NEW.decided_by IS NULL THEN
      RAISE EXCEPTION '坏账核销申请 % 缺少审批人', NEW.case_no USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.decided_at IS NULL THEN
      RAISE EXCEPTION '坏账核销申请 % 缺少审批时刻', NEW.case_no USING ERRCODE = 'check_violation';
    END IF;
    IF btrim(coalesce(NEW.decision_comment, '')) = '' THEN
      RAISE EXCEPTION '坏账核销申请 % 缺少审批意见：审批意见必填', NEW.case_no
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.submitted_by IS NOT NULL AND NEW.decided_by = NEW.submitted_by THEN
      RAISE EXCEPTION '坏账核销申请 % 的审批人与提交人是同一个人：核销是终审动作，不能自己批自己', NEW.case_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bad_debt_decision
  BEFORE UPDATE ON finance_bad_debt_case
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bad_debt_decision();

-- ---------------------------------------------------------------------------
-- ⑥ 会计处理不可用：不造凭证。
-- ---------------------------------------------------------------------------
--
--     下游 12.5.2.1「凭证管理」未交付 ⇒ 本模块**不产生**会计处理。
--     守卫把这件事钉在**核销那一刻**：核销完成后，凭证号仍必须是空串、
--     状态仍必须是 PENDING_FINANCE、归属模块仍必须是 12.5.2.1。
--     ⇒ 「会计处理不可用」永远表现为「归属模块 + 空凭证号」，
--        绝不表现为「凭证号 = 0」或一条编造的凭证。
CREATE OR REPLACE FUNCTION guard_finance_bad_debt_accounting() RETURNS trigger AS $$
BEGIN
  IF NEW.status = 'WRITTEN_OFF' AND OLD.status IS DISTINCT FROM NEW.status THEN
    IF btrim(coalesce(NEW.accounting_ref, '')) <> '' THEN
      RAISE EXCEPTION '坏账核销申请 % 不可写凭证号：下游 12.5.2.1 凭证管理未交付', NEW.case_no
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.accounting_status <> 'PENDING_FINANCE' THEN
      RAISE EXCEPTION '坏账核销申请 % 的会计处理状态必须是 PENDING_FINANCE（12.5.2.1 未交付）', NEW.case_no
        USING ERRCODE = 'check_violation';
    END IF;
    IF btrim(coalesce(NEW.accounting_owner_module, '')) = '' THEN
      RAISE EXCEPTION '坏账核销申请 % 必须写明会计处理归属模块', NEW.case_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bad_debt_accounting
  BEFORE UPDATE ON finance_bad_debt_case
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bad_debt_accounting();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================
--
-- 列表默认排序：提交时刻倒序 + id（审批台账看的是最近提交的）。
CREATE INDEX finance_bad_debt_case_by_status
  ON finance_bad_debt_case(tenant_id, status, submitted_at DESC, id);
-- 按目标应收查（这张应收有没有被申请过坏账核销）。
CREATE INDEX finance_bad_debt_case_by_receivable
  ON finance_bad_debt_case(tenant_id, receivable_id, status);
-- 按事由查（争议损失 / 拒付 / 冻结各多少）。
CREATE INDEX finance_bad_debt_case_by_reason
  ON finance_bad_debt_case(tenant_id, reason_type, status);
-- 按币种查（**不跨币种合计**）。
CREATE INDEX finance_bad_debt_case_by_currency
  ON finance_bad_debt_case(tenant_id, currency, status);
-- 按上游纠纷查（11.4.1.1 的纠纷 → 本模块的核销）。
CREATE INDEX finance_bad_debt_case_by_dispute
  ON finance_bad_debt_case(tenant_id, dispute_id)
  WHERE dispute_id IS NOT NULL;
-- 依据材料按单取。
CREATE INDEX finance_bad_debt_evidence_by_case
  ON finance_bad_debt_evidence(tenant_id, case_id, evidence_no);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_bad_debt_event_by_case
  ON finance_bad_debt_event(tenant_id, case_id, occurred_at, id);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================
--
-- 与 V338 / V339 / V340 / V341 同一套谓词：
-- `tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid`，
-- ENABLE **与 FORCE** 都要 —— 只有 FORCE 才挡得住「运行角色被误授 owner」。
DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_bad_debt_case','finance_bad_debt_evidence','finance_bad_debt_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_bad_debt_case, finance_bad_debt_evidence, finance_bad_debt_event TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_bad_debt_case, finance_bad_debt_evidence, finance_bad_debt_event TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：事件流是**举证凭据**，只能追加。
-- 收回之后，即使服务层写错（误 UPDATE / 误 DELETE）也改不动已经记下的历史。
REVOKE UPDATE,DELETE ON finance_bad_debt_event FROM finance_app;

-- 依据材料与申请单是一对多的事实记录：删掉就没有痕迹了。
-- 依据只允许「移除」（服务层把 status 置 'REMOVED' 并写 EVIDENCE_REMOVED 事件），
-- 而「移除」本身也是事件流里的一条 —— 所以物理删除权限收回。
REVOKE DELETE ON finance_bad_debt_evidence FROM finance_app;

-- ===========================================================================
-- 第五节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.5.2.1 凭证（未交付）**：本模块的 `accounting_status` 恒为
--     `PENDING_FINANCE`、`accounting_ref` 恒为空串、`accounting_owner_module`
--     恒为 `12.5.2.1`。12.5.2.1 落地后：读本模块的核销记录（金额 / 事由 /
--     依据 / 审批）生成凭证，并 DROP 掉 `finance_bad_debt_accounting_ck`
--     那条 CHECK —— **不需要改表结构**。
--
--   * **12.2.1.2 核销（未交付，同批次另一条车道）**：
--     `finance_receivable_line.settled_amount` 的写者恒为 12.2.1.2。
--     本模块的坏账核销走**作废 + 新单**，**一个字节都不写 settled_amount**。
--     ⇒ 两条车道在核销金额上不撞车。已部分回款的应收（settled_amount > 0）
--     本模块**不受理**：冲减「已核销」那部分属 12.2.1.2 的核销冲销（红冲）范围。
--
--   * **11.4.1.1 纠纷（已交付，trade 服务）**：本模块经 trade 的**授权 HTTP API**
--     读纠纷（`GET /trade/platform-disputes/{id}`），只读、不建跨库 FK、不建跨库视图。
--     读到的纠纷单号 / 状态 / 金额以**快照**落在本表 —— 依据链要能自证。
--
--   * **12.1.1.2 应收账龄（已交付）**：账龄分布读的是 12.1.1.1 的应收单；
--     本模块把已核销的应收**作废**掉 ⇒ 该笔金额**自动**从账龄分布里消失，
--     不需要改 12.1.1.2 的任何一列或一条 SQL。
