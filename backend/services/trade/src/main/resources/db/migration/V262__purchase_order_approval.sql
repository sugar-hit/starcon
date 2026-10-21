-- 6.3.1.3 采购单审批（trade 服务）。
--
-- 蓝图 06-purchase-and-supply.md 原文：
--   职责：按金额阈值与采购员分级审批，支持批量审批、驳回并附意见，审批通过后锁定单据生成应付。
--   输入：待审批采购单；17 组织的审批规则（金额阈值、层级）。
--   输出：审批结果 + 审批链路记录；通过后触发 12 生成应付、8 允许收货。
--   关系：上游 = 17 审批、6.3.1.2；下游 = 12 应付、8 收货。
--         **审批通过的采购单不可直接改数量与单价**——如需变更，走变更单或红冲重下，
--         避免已生成应付与实际收货对不上。
--
-- 归属：**trade 服务**（卷 06 后端主责 = trade + catalog（party），取列表第一项 = trade）。
--
-- ===========================================================================
-- 一、本模块拥有什么、不拥有什么
-- ===========================================================================
--
-- ⚠️ **「采购单」这个聚合本身归 6.3.1.1**（V254 的 purchase_order / purchase_order_line /
--   purchase_order_event 三张表）。本迁移**不改那三张表的结构**：
--   零 ALTER、零 DROP、零改既有约束、零改既有触发器、零 CREATE（撞 relation already exists
--   会让整批 Flyway 挂掉）。
--
-- **唯一的例外是第 1.2 节的一个新增触发器**（`guard_purchase_order_line_approved_lock`），
--   它挂在 6.3.1.1 的 purchase_order_line 上，**只增不改**：
--   蓝图那句「审批通过的采购单不可直接改数量与单价」必须在 DB 层钉死，
--   而数量与单价就住在行表上 ⇒ 触发器只能挂在那里。
--   先例：V258（6.3.1.2）同样在 purchase_order_line 上**新增**过一个 BEFORE DELETE 触发器
--   （guard_purchase_order_line_delete_guard），且明确「不改任何既有列 / 约束 / 触发器」。
--   本迁移与它是**同一种纯增量**，6.3.1.1 的结构回归面 = 0。
--
-- 本模块**自己新建**七张表：
--
--   1. purchase_order_approval_rule          审批规则（金额阈值 + 适用采购员分级 → 层级与人数）
--   2. purchase_order_approval_buyer         采购员分级（谁是哪一档的采购员）
--   3. purchase_order_approval_approver      审批人 / 层级配置（谁能审哪一档、金额上限多少）
--   4. purchase_order_approval_case          审批单（一张采购单一条链，冻结命中结果）
--   5. purchase_order_approval_record        审批链路记录（追加式）
--   6. purchase_order_payable                应付登记（trade 侧自有事实记录，登记额 / 生效额分列）
--   7. purchase_order_receipt_release        收货放行事实（08 在独立库，trade 只落事实）
--
-- ===========================================================================
-- 二、🔴 七个最容易踩空的地方，本文件逐条钉死
-- ===========================================================================
--
-- ① **purchase_order 表已存在（V254）** ⇒ 本迁移**绝不** CREATE TABLE purchase_order。
--   本文件只出现 purchase_order_**approval_*** / purchase_order_payable /
--    purchase_order_receipt_release 这些**新名字**。
--
-- ② **purchase_order.status 的 CHECK 域已钉死**（V254 第 138 行）：
--   DRAFT / PENDING_APPROVAL / APPROVED / REJECTED / IN_FULFILLMENT / CLOSED。
--   ⇒ 本模块**不 ALTER** 这个 CHECK。PENDING_APPROVAL / APPROVED / REJECTED 三个状态
--   就是给本模块留好的：审批前 PENDING_APPROVAL，通过后 APPROVED，驳回后 REJECTED。
--
-- ③ **状态机跃迁已由 guard_purchase_order_status 钉死**（V254 第 332–335 行）。
--   ⇒ 本模块**只走** PENDING_APPROVAL → APPROVED / REJECTED 这两条既有跃迁，
--   **不发明新跃迁、不改那个触发器**。
--
-- ④ **V258（6.3.1.2）建的五张表**（行快照 / 提交快照 / 发货地址 / 附件 / 草稿来源）
--   ⇒ 本迁移**只 SELECT 读**提交快照里的金额口径口径，**不重建、不 ALTER**。
--
-- ⑤ **12 财务服务不存在**（backend/services/ 下只有 identity / catalog / channel /
--   warehouse / trade / crm）⇒ 「生成应付」**不读写任何 finance**、不建跨服务 FK、
--   不建跨库视图。按本仓既有先例（11.2.3.3 的 supplier_claim_settlement）落成
--   **trade 侧自有事实记录**：登记金额与生效金额**分两列**，登记时生效恒为 0，
--   表级 CHECK 钉死「未确认 ⇒ 生效 = 0；确认 ⇒ 生效 = 登记额且确认人与凭证齐全」。
--
-- ⑥ **17 组织的审批规则服务不提供「按采购员分级」的判定口径** ⇒ 金额阈值与采购员分级
--   的规则由 **trade 侧自有表**承载（规则表 + 采购员分级表 + 审批人/层级配置表），
--   命中判定放在 **DB 函数**（purchase_order_approval_match）里，服务端与 DB 共用
--   **同一个**判定函数 ⇒ 不存在「两套判定漂移」。
--   ⇒ **不接受调用方传入「我已审批」**这类结论：审批结论只能由本模块的判定 + 留痕产生。
--
-- ⑦ **08 仓储在独立库** ⇒ 「允许收货」**不改 warehouse 的表**、不建跨库 FK。
--   本模块只产出 `purchase_order_receipt_release` 这条**放行事实**，
--   收货资格的真正放行由 warehouse 侧经**授权 API / event** 衔接。
--
-- ===========================================================================
-- 三、审批链的形状（金额阈值 + 采购员分级 ⇒ 层级 + 人数）
-- ===========================================================================
--
--   规则 = （适用采购员分级, 币种, 金额区间） ⇒ （要求的审批层级 required_tier,
--                                          要求的审批人数 required_approvals）
--
--   * 金额区间是**半开区间** [min_amount, max_amount)：max_amount 为 NULL = 无上限。
--     半开区间保证相邻两条规则**既不重叠也不留缝**（闭区间会在边界上命中两条）。
--   * buyer_level / currency 上的 'ANY' 是**通配**；命中多条时按
--     「具体维度多的优先 → priority 小的优先 → 建得早的优先」取**一条**。
--   * 一条都命中不了 ⇒ rule_match='DEFAULT'，按**最低层级 L1、1 人**处理。
--     ⚠️ **绝不**因为「没配规则」就把单子当成已批 —— 那等于把审批这一环悄悄跳过。
--
--   谁可以批：审批人表里**启用**、且
--     (a) approval_tier >= 案子的 required_tier（层级够）、
--     (b) 金额区间覆盖本单金额、币种匹配（授权额度够）、
--     (c) **不是本单的采购员本人**（不能自审）、且
--     (d) 没有在这张单上批过（UNIQUE(tenant_id,case_id,approver_user_id) 兜底）。
--
--   人数：required_approvals 个人**各批一次**才算通过（不能一个人批两次凑数）。
--   通过 ⇒ 采购单 APPROVED + 登记应付 + 落收货放行事实；
--   驳回 ⇒ 采购单 REJECTED（驳回意见**必填**）。
--
-- ===========================================================================
-- 四、审批链在什么时候定下来（冻结）
-- ===========================================================================
--
--   命中结果在**提交那一刻**冻结进 purchase_order_approval_case：
--   DRAFT → PENDING_APPROVAL 的 UPDATE 会触发 AFTER UPDATE 触发器
--   `purchase_order_approval_case_after_submit`，由 DB 调用判定函数并落一条审批单。
--
--   为什么在 DB 层冻结：
--     * 「提交那一刻要几级审批」是**审计事实**，事后规则一改它不能跟着变；
--     * 「一张待审批的采购单必然有一条审批链」是**不变式** —— 否则界面上的
--       「当前待审层级」会随规则改动而变，审批人批的和自己看到的不是一回事。
--
--   ⚠️ 这条触发器是**纯增量**：6.3.1.2 的 submit 只 UPDATE 单头、不动行表
--   （已逐行核对 PurchaseOrderEditService.submit：行只进快照表，行本身不动），
--   ⇒ 本触发器不会打断 6.3.1.2 的任何行为。
--
-- ===========================================================================
-- 五、多租户
-- ===========================================================================
--
--   七张业务表一视同仁 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + GRANT。
--   租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
--   客户端的 X-Tenant-Id 头进不来也改不动它。

-- ===========================================================================
-- 一、触发器与函数
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1.1 命中判定：金额阈值 + 采购员分级 ⇒ 层级与人数
--
--     🔴 这是**唯一的**判定实现：DB 触发器与服务端都调它 ⇒ 两侧不可能漂移。
-- ---------------------------------------------------------------------------
CREATE FUNCTION purchase_order_approval_match(p_tenant_id uuid, p_order_id uuid)
RETURNS TABLE (
  rule_id uuid,
  rule_name varchar,
  rule_match varchar,
  buyer_user_id uuid,
  buyer_level varchar,
  amount numeric,
  currency char(3),
  required_tier varchar,
  required_approvals int
) LANGUAGE plpgsql STABLE AS $$
DECLARE
  v_amount numeric(19,6);
  v_currency char(3);
  v_buyer uuid;
  v_buyer_level varchar(24);
  v_rule record;
BEGIN
  SELECT o.total_amount, o.currency, o.owner_user_id
    INTO v_amount, v_currency, v_buyer
    FROM purchase_order o
   WHERE o.tenant_id = p_tenant_id AND o.id = p_order_id;

  IF NOT FOUND THEN
    RETURN;                       -- 单不存在：调用方负责报「不存在」，这里不猜。
  END IF;

  -- 采购员分级：取不到就是 UNREGISTERED（**不是 ANY** ——
  -- 「没有登记分级」与「不分级通配」是两件事，前者不该被通配规则蒙混过关）。
  SELECT b.buyer_level INTO v_buyer_level
    FROM purchase_order_approval_buyer b
   WHERE b.tenant_id = p_tenant_id
     AND b.user_id = v_buyer
     AND b.status = 'ACTIVE';
  v_buyer_level := coalesce(v_buyer_level, 'UNREGISTERED');

  SELECT r.id, r.rule_name, r.required_tier, r.required_approvals
    INTO v_rule
    FROM purchase_order_approval_rule r
   WHERE r.tenant_id = p_tenant_id
     AND r.status = 'ACTIVE'
     AND (r.buyer_level = 'ANY' OR r.buyer_level = v_buyer_level)
     AND (r.currency = 'ANY' OR r.currency = v_currency)
     AND v_amount >= r.min_amount
     AND (r.max_amount IS NULL OR v_amount < r.max_amount)
   ORDER BY
     -- 具体优先：分级、币种、区间都写死的规则比通配的更具体。
     ((CASE WHEN r.buyer_level = 'ANY' THEN 0 ELSE 1 END)
    + (CASE WHEN r.currency = 'ANY' THEN 0 ELSE 1 END)
    + (CASE WHEN r.max_amount IS NULL THEN 0 ELSE 1 END)) DESC,
     r.priority ASC,
     r.created_at ASC,
     r.id ASC
   LIMIT 1;

  IF FOUND THEN
    RETURN QUERY SELECT v_rule.id, v_rule.rule_name, 'RULE'::varchar,
                        v_buyer, v_buyer_level, round(v_amount, 4), v_currency,
                        v_rule.required_tier, v_rule.required_approvals;
  ELSE
    -- 一条都没命中 ⇒ 走默认档（最低层级、1 人）。
    -- ⚠️ **绝不**因为「没配规则」就当已批：审批这一环不能被悄悄跳过。
    RETURN QUERY SELECT NULL::uuid, ''::varchar, 'DEFAULT'::varchar,
                        v_buyer, v_buyer_level, round(v_amount, 4), v_currency,
                        'L1'::varchar, 1;
  END IF;
END $$;

COMMENT ON FUNCTION purchase_order_approval_match(uuid, uuid) IS
  '6.3.1.3 审批命中判定（唯一实现）：金额阈值 + 采购员分级 ⇒ 审批层级与人数';

-- ---------------------------------------------------------------------------
-- 1.2 🔴 审批通过后数量与单价不可直接改（DB 层钉死）
--
--     蓝图加粗句：「审批通过的采购单不可直接改数量与单价——如需变更，走变更单或红冲重下，
--     避免已生成应付与实际收货对不上。」
--
--     为什么触发器而不是服务层校验：服务层校验可以被绕过（别的写入口、脚本、直接 SQL），
--     而「已批的单子改数量」是**会算错钱**的那一类错误。V254 自己的口径同款：
--     「CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行」。
--
--     判定口径与 V258 的 guard_purchase_order_line_delete_guard **完全同款**
--     （head_status IS DISTINCT FROM 'DRAFT' ⇒ 不许改行）：
--     提交之后（PENDING_APPROVAL 及以后）数量与单价一律锁死；
--     其中 APPROVED / IN_FULFILLMENT / CLOSED 就是蓝图点名的那一段。
--
--     ⚠️ 本触发器**只加不改**：不改 V254 的 guard_purchase_order_line_amount、
--     不改 V258 的 guard_purchase_order_line_delete_guard、不改任何既有列。
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_purchase_order_line_approved_lock() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  head_status varchar(20);
  head_no varchar(64);
BEGIN
  SELECT o.status, o.order_no INTO head_status, head_no
    FROM purchase_order o
   WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.order_id;

  IF head_status IS DISTINCT FROM 'DRAFT'
     AND (NEW.quantity IS DISTINCT FROM OLD.quantity
          OR NEW.unit_price IS DISTINCT FROM OLD.unit_price) THEN
    RAISE EXCEPTION '采购单「%」当前状态是 %，它的数量与单价不可再改：审批通过的采购单已生成应付与收货放行，要改请走变更单或红冲重下',
      coalesce(head_no, NEW.order_id::text), coalesce(head_status, '不存在')
      USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER purchase_order_line_approved_lock
  BEFORE UPDATE ON purchase_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_line_approved_lock();

COMMENT ON FUNCTION guard_purchase_order_line_approved_lock() IS
  '6.3.1.3 审批后锁定：提交之后（PENDING_APPROVAL 及以后）采购单行的数量与单价不可改';

-- ===========================================================================
-- 二、采购规则
-- ===========================================================================
CREATE TABLE purchase_order_approval_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_no varchar(64) NOT NULL CHECK(btrim(rule_no) <> ''),
  rule_name varchar(200) NOT NULL CHECK(btrim(rule_name) <> ''),

  -- ---- 适用采购员分级（'ANY' = 通配）----
  buyer_level varchar(24) NOT NULL DEFAULT 'ANY'
      CHECK(buyer_level IN ('ANY','JUNIOR','SENIOR','MANAGER')),

  -- ---- 币种（'ANY' = 通配；金额阈值与币种绑定，绝不跨币种比大小）----
  currency char(3) NOT NULL DEFAULT 'ANY'
      CHECK(currency = 'ANY' OR currency ~ '^[A-Z]{3}$'),

  -- ---- 金额阈值：**半开区间** [min_amount, max_amount)，max 为 NULL = 无上限 ----
  min_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(min_amount >= 0),
  max_amount numeric(19,4) CHECK(max_amount IS NULL OR max_amount > min_amount),

  -- ---- 命中后要求的审批层级与人数 ----
  required_tier varchar(8) NOT NULL CHECK(required_tier IN ('L1','L2','L3')),
  required_approvals int NOT NULL DEFAULT 1
      CHECK(required_approvals >= 1 AND required_approvals <= 5),

  priority int NOT NULL DEFAULT 100,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rule_no)
);

COMMENT ON TABLE purchase_order_approval_rule IS
  '6.3.1.3 审批规则：采购员分级 + 币种 + 金额阈值 ⇒ 要求的审批层级与人数';

-- ===========================================================================
-- 三、采购员分级（谁是哪一档的采购员）
-- ===========================================================================
CREATE TABLE purchase_order_approval_buyer (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  user_id uuid NOT NULL,
  user_name varchar(200) NOT NULL DEFAULT '',
  buyer_level varchar(24) NOT NULL CHECK(buyer_level IN ('JUNIOR','SENIOR','MANAGER')),

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  -- 一个人在本租户**只有一个**分级：两条分级会让「这单该走哪一档」没有答案。
  UNIQUE(tenant_id,user_id)
);

COMMENT ON TABLE purchase_order_approval_buyer IS
  '6.3.1.3 采购员分级：决定这张采购单命中哪一档审批规则';

-- ===========================================================================
-- 四、审批人 / 层级配置（谁能审哪一档、金额上限多少）
-- ===========================================================================
CREATE TABLE purchase_order_approval_approver (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  user_id uuid NOT NULL,
  user_name varchar(200) NOT NULL DEFAULT '',
  approval_tier varchar(8) NOT NULL CHECK(approval_tier IN ('L1','L2','L3')),

  -- ---- 这个人的**金额授权区间**（同样按币种，绝不跨币种比大小）----
  currency char(3) NOT NULL DEFAULT 'ANY'
      CHECK(currency = 'ANY' OR currency ~ '^[A-Z]{3}$'),
  min_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(min_amount >= 0),
  max_amount numeric(19,4) CHECK(max_amount IS NULL OR max_amount > min_amount),

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,user_id,approval_tier)
);

COMMENT ON TABLE purchase_order_approval_approver IS
  '6.3.1.3 审批人与层级：审批层级 + 金额授权区间（缺了区间就是「这个人什么都能批」）';

-- ===========================================================================
-- 五、审批单（一张采购单一条链；提交那一刻冻结命中结果）
-- ===========================================================================
CREATE TABLE purchase_order_approval_case (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,

  case_no varchar(64) NOT NULL CHECK(btrim(case_no) <> ''),

  -- ---- 命中的规则（rule_match='DEFAULT' 时没有具体规则）----
  rule_id uuid,
  rule_name varchar(200) NOT NULL DEFAULT '',
  rule_match varchar(16) NOT NULL CHECK(rule_match IN ('RULE','DEFAULT')),

  -- ---- 采购员（= 采购单负责人）与其分级 ----
  buyer_user_id uuid NOT NULL,
  buyer_level varchar(24) NOT NULL DEFAULT 'UNREGISTERED'
      CHECK(buyer_level IN ('UNREGISTERED','JUNIOR','SENIOR','MANAGER')),

  -- ---- 审批金额口径（提交那一刻的总额，**币种与采购单一致**）----
  order_amount numeric(19,4) NOT NULL CHECK(order_amount >= 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  required_tier varchar(8) NOT NULL CHECK(required_tier IN ('L1','L2','L3')),
  required_approvals int NOT NULL CHECK(required_approvals >= 1 AND required_approvals <= 5),
  approved_count int NOT NULL DEFAULT 0 CHECK(approved_count >= 0),

  status varchar(16) NOT NULL DEFAULT 'PENDING'
      CHECK(status IN ('PENDING','APPROVED','REJECTED')),

  rejected_by uuid,
  rejected_at timestamptz,
  reject_reason text NOT NULL DEFAULT '' CHECK(length(reject_reason) <= 2000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id),
  UNIQUE(tenant_id,case_no),

  CONSTRAINT purchase_order_approval_case_count_le CHECK(approved_count <= required_approvals),
  -- 通过 ⇒ 人数凑齐且没有驳回。
  CONSTRAINT purchase_order_approval_case_approved_consistent CHECK(
    status <> 'APPROVED'
    OR (approved_count = required_approvals AND rejected_by IS NULL)),
  -- 驳回 ⇒ 必须有驳回人与时刻与**意见**（「驳回而不说为什么」等于没驳回）。
  CONSTRAINT purchase_order_approval_case_rejected_consistent CHECK(
    status <> 'REJECTED'
    OR (rejected_by IS NOT NULL AND rejected_at IS NOT NULL
        AND length(btrim(reject_reason)) > 0))
);

COMMENT ON TABLE purchase_order_approval_case IS
  '6.3.1.3 审批单：提交那一刻冻结命中结果；一条采购单只有一条审批链';

-- ===========================================================================
-- 六、审批链路记录（追加式）
-- ===========================================================================
CREATE TABLE purchase_order_approval_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,
  case_id uuid NOT NULL,
  seq int NOT NULL CHECK(seq > 0),

  action varchar(16) NOT NULL CHECK(action IN ('APPROVED','REJECTED')),

  -- ---- 审批人与其层级（留痕要能答「谁以什么身份批的」）----
  approval_tier varchar(8) NOT NULL CHECK(approval_tier IN ('L1','L2','L3')),
  approver_user_id uuid NOT NULL,
  approver_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 审批当时的金额与币种（**当时的数**，不是现在的数）----
  order_amount numeric(19,4) NOT NULL CHECK(order_amount >= 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ---- 依据的规则（留痕要能答「按哪条规则批的」）----
  rule_id uuid,
  rule_name varchar(200) NOT NULL DEFAULT '',
  buyer_level varchar(24) NOT NULL DEFAULT 'UNREGISTERED',

  -- ---- 审批意见（驳回时必填；通过时可选）----
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),

  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,case_id,seq),
  -- 🔴 一个人在一张单上**只能批一次**：不能自己批两次凑够人数。
  UNIQUE(tenant_id,case_id,approver_user_id)
);

COMMENT ON TABLE purchase_order_approval_record IS
  '6.3.1.3 审批链路记录：追加式，运行角色 UPDATE/DELETE 已收回';

-- ===========================================================================
-- 七、应付登记（12 财务不存在 ⇒ trade 侧自有事实记录）
-- ===========================================================================
-- ⚠️ 照抄本仓既有先例 11.2.3.3 的 supplier_claim_settlement：
--   **登记金额与生效金额分两列**，登记时生效恒为 0；
--   表级 CHECK 钉死「未确认 ⇒ 生效 = 0，确认 ⇒ 生效 = 登记额且确认人与凭证齐全」。
--   12 财务落地后经授权 API / event 衔接最终对账，本模块**不读写任何 finance**。
CREATE TABLE purchase_order_payable (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,

  payable_no varchar(64) NOT NULL CHECK(btrim(payable_no) <> ''),

  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 登记的应付金额（= 审批通过时那张采购单的金额口径）。
  registered_amount numeric(19,4) NOT NULL CHECK(registered_amount > 0),
  -- **已生效**的应付金额：财务确认之前**恒为 0**。
  effective_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(effective_amount >= 0),

  status varchar(24) NOT NULL
      CHECK(status IN ('PENDING_FINANCE_CONFIRM','CONFIRMED','REJECTED')),

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
  UNIQUE(tenant_id,order_id),
  UNIQUE(tenant_id,payable_no),

  -- **「没有财务确认就没有生效」的三条 CHECK**（与 supplier_claim_settlement 同款）：
  -- ⚠️ 约束**显式命名**：PostgreSQL 自动生成的 check1/check2 在报错里没法读，
  --    运维看到报错时必须一眼知道是哪一条口径被破了。
  CONSTRAINT purchase_order_payable_confirmed_complete CHECK(
    status <> 'CONFIRMED'
    OR (effective_amount = registered_amount
        AND confirmed_by IS NOT NULL
        AND confirmed_at IS NOT NULL
        AND length(btrim(finance_reference)) > 0)),
  CONSTRAINT purchase_order_payable_pending_zero CHECK(
    status <> 'PENDING_FINANCE_CONFIRM' OR effective_amount = 0),
  CONSTRAINT purchase_order_payable_rejected_zero CHECK(
    status <> 'REJECTED'
    OR (effective_amount = 0 AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL))
);

COMMENT ON TABLE purchase_order_payable IS
  '6.3.1.3 应付登记：trade 侧自有事实记录；effective_amount 在财务确认前恒为 0';

-- ===========================================================================
-- 八、收货放行事实（08 仓储在独立库 ⇒ 只落事实，不代写收货）
-- ===========================================================================
CREATE TABLE purchase_order_receipt_release (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,

  status varchar(16) NOT NULL CHECK(status IN ('RELEASED','REVOKED')),

  released_by uuid NOT NULL,
  released_at timestamptz NOT NULL,
  -- 放行说明（收货口径以 08 仓储那边为准，这里只记「已放行」这个事实）。
  release_note text NOT NULL DEFAULT '' CHECK(length(release_note) <= 1000),

  revoked_by uuid,
  revoked_at timestamptz,
  revoke_reason text NOT NULL DEFAULT '' CHECK(length(revoke_reason) <= 1000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id),

  CHECK(status <> 'REVOKED'
        OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL
            AND length(btrim(revoke_reason)) > 0))
);

COMMENT ON TABLE purchase_order_receipt_release IS
  '6.3.1.3 收货放行事实：审批通过即放行；08 仓储侧经授权 API / event 衔接实际收货';

-- ===========================================================================
-- 九、审批单号序列
-- ===========================================================================
-- 审批单号 = 'PAC-' || 序列值（与 purchase_order_no_seq 同一写法，单号前缀不同 ⇒ 不会认错）。
CREATE SEQUENCE purchase_order_approval_case_no_seq START 1;

-- 应付登记单号 = 'PAP-' || 序列值（与审批单号同样式，前缀不同 ⇒ 不会认错）。
CREATE SEQUENCE purchase_order_payable_no_seq START 1;

-- ===========================================================================
-- 十、触发器（续）
-- ===========================================================================

-- 10.1 提交那一刻冻结审批链（DRAFT → PENDING_APPROVAL）。
CREATE FUNCTION materialize_purchase_order_approval_case() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  m record;
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status = 'PENDING_APPROVAL' THEN
    SELECT * INTO m
      FROM purchase_order_approval_match(NEW.tenant_id, NEW.id);

    IF FOUND THEN
      INSERT INTO purchase_order_approval_case(
        id,tenant_id,order_id,case_no,rule_id,rule_name,rule_match,
        buyer_user_id,buyer_level,order_amount,currency,
        required_tier,required_approvals,approved_count,status,created_at,updated_at)
      VALUES (
        gen_random_uuid(), NEW.tenant_id, NEW.id,
        'PAC-' || nextval('purchase_order_approval_case_no_seq'),
        m.rule_id, m.rule_name, m.rule_match,
        m.buyer_user_id, m.buyer_level, m.amount, m.currency,
        m.required_tier, m.required_approvals, 0, 'PENDING', now(), now())
      ON CONFLICT (tenant_id, order_id) DO NOTHING;
    END IF;
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER purchase_order_approval_case_after_submit
  AFTER UPDATE ON purchase_order
  FOR EACH ROW EXECUTE FUNCTION materialize_purchase_order_approval_case();

COMMENT ON FUNCTION materialize_purchase_order_approval_case() IS
  '6.3.1.3 提交那一刻冻结审批链：DRAFT → PENDING_APPROVAL 时按规则命中结果落一条审批单';

-- 10.2 审批链路记录是 **append-only**：不可改写、不可删。
--     「谁在什么时候按哪条规则批的」是事后追溯的凭据。
CREATE FUNCTION guard_purchase_order_approval_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：审批链路记录是「当时谁批的、依据哪条规则」的举证凭据',
    TG_TABLE_NAME, TG_OP
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER purchase_order_approval_record_append_only
  BEFORE UPDATE OR DELETE ON purchase_order_approval_record
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_approval_append_only();

-- ===========================================================================
-- 十一、索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 规则：命中判定按（租户, 状态, 分级, 币种, 金额）扫。
CREATE INDEX purchase_order_approval_rule_by_match
  ON purchase_order_approval_rule(tenant_id, status, buyer_level, currency, min_amount, priority);
-- 采购员分级：按人查。
CREATE INDEX purchase_order_approval_buyer_by_user ON purchase_order_approval_buyer(tenant_id, user_id);
-- 审批人：按人查 / 按层级与金额查（判定「谁能批这一笔」）。
CREATE INDEX purchase_order_approval_approver_by_user
  ON purchase_order_approval_approver(tenant_id, user_id, status);
CREATE INDEX purchase_order_approval_approver_by_band
  ON purchase_order_approval_approver(tenant_id, status, approval_tier, min_amount);
-- 审批单：按单读（详情页）+ 按状态扫（待审批台账）。
CREATE INDEX purchase_order_approval_case_by_order ON purchase_order_approval_case(tenant_id, order_id);
CREATE INDEX purchase_order_approval_case_by_status
  ON purchase_order_approval_case(tenant_id, status, created_at DESC, id);
-- 链路记录：按单按顺序回放。
CREATE INDEX purchase_order_approval_record_by_case
  ON purchase_order_approval_record(tenant_id, case_id, seq);
CREATE INDEX purchase_order_approval_record_by_order
  ON purchase_order_approval_record(tenant_id, order_id, occurred_at, id);
-- 应付：按单读 + 按状态扫。
CREATE INDEX purchase_order_payable_by_order ON purchase_order_payable(tenant_id, order_id);
CREATE INDEX purchase_order_payable_by_status
  ON purchase_order_payable(tenant_id, status, created_at DESC, id);
-- 收货放行：按单读。
CREATE INDEX purchase_order_receipt_release_by_order
  ON purchase_order_receipt_release(tenant_id, order_id);

-- ===========================================================================
-- 十二、多租户：RLS + 授权（与 trade V1 / V92 / V234 / V238 / V242 / V246 / V254 / V258 同一套写法）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_order_approval_rule','purchase_order_approval_buyer',
    'purchase_order_approval_approver','purchase_order_approval_case',
    'purchase_order_approval_record','purchase_order_payable',
    'purchase_order_receipt_release'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_order_approval_rule,purchase_order_approval_buyer,
  purchase_order_approval_approver,purchase_order_approval_case,
  purchase_order_approval_record,purchase_order_payable,
  purchase_order_receipt_release TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_order_approval_rule,purchase_order_approval_buyer,
  purchase_order_approval_approver,purchase_order_approval_case,
  purchase_order_approval_record,purchase_order_payable,
  purchase_order_receipt_release TO starcon_app;
-- 收回留痕表的改写权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON purchase_order_approval_record FROM trade_app;
REVOKE UPDATE,DELETE ON purchase_order_approval_record FROM starcon_app;

GRANT USAGE, SELECT ON SEQUENCE purchase_order_approval_case_no_seq TO trade_app;
GRANT USAGE, SELECT ON SEQUENCE purchase_order_approval_case_no_seq TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE purchase_order_payable_no_seq TO trade_app;
GRANT USAGE, SELECT ON SEQUENCE purchase_order_payable_no_seq TO starcon_app;

-- ---------------------------------------------------------------------------
-- 十三、本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **新建 7 张表 + 1 个序列 + 3 个函数 + 3 个触发器**，
--     对 V254 / V258 的表**零 ALTER、零 DROP、零改既有约束与触发器**。
--   * 唯一的跨表增量是**新增**一个 BEFORE UPDATE 触发器挂在 purchase_order_line 上
--     （guard_purchase_order_line_approved_lock），只增不改：
--     6.3.1.1 / 6.3.1.2 的既有行为与测试零变化（两者都不在提交后改行的数量或单价）。
--   * 6.3.1.2 的五张快照表只 SELECT 读，不 ALTER、不加约束、不建外键。
--   * 对 warehouse / finance 的数据**一律不碰**：收货放行与应付登记都是 trade 侧自有事实。
--   * 新增函数名全部带 purchase_order_approval_ / guard_purchase_order_ 前缀，与既有函数名不冲突。
