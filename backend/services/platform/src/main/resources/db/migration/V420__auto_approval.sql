-- ===========================================================================
-- V420 · auto_approval —— 18.3.3.1「自动审批」
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.3「业务规则引擎」→ 18.3.3「自动审批」→ 18.3.3.1：
--
--   「**职责**：自动化审批场景：自动通过低风险小额单据、自动驳回明显不合规单据、
--      金额分层路由审批人、超时自动升级。
--    **输入**：审批规则配置；单据风险信号。
--    **输出**：自动审批结果 + 决策依据记录。
--    **关系**：上游 = 17.4 审批；下游 = 各业务系统。
--    **自动审批只处理"明确安全"的场景**（小额 + 供应商已验证 + 无风险信号），
--    其余仍走人工——审批是内控底线。」
--
-- ---------------------------------------------------------------------------
-- 0. 🔴 本迁移**刻意不做**什么（先说清楚边界，后面每张表都按这条边界设计）
-- ---------------------------------------------------------------------------
--
--   * **不在 platform 侧另起一套审批状态机**。
--     上游 17.4（identity 服务）拥有 `approval_instance` / `approval_instance_action`：
--     谁在哪张单上做了什么审批动作、实例停在哪个节点、最终批没批 —— 那些是**审批事实**，
--     由 identity 独家写入。本迁移**不复制**实例、不复制节点、不复制审批动作，
--     也不在本服务里造第二套 `PENDING / APPROVED / REJECTED` 的实例状态机。
--     ⇒ 本模块（18.3.3.1）拥有的是**「自动审批决策」**这一层事实：
--     「这一张单据，本系统判它自动通过 / 自动驳回 / 必须走人工，**依据**是什么」。
--     人工审批本身仍然只在 identity 侧发生。
--
--   * **不跨库读写 identity 的表**（AGENTS.md「模块边界纪律」：禁止跨服务 SQL / FK / 共享视图）。
--     审批流程定义、金额分层分支（`approval_flow_amount_branch`）、超时升级规则
--     （`approval_flow_escalation`）全部经**授权 HTTP 端口**读 identity，本迁移里
--     只有 `upstream_flow_id` 这个**只读引用**，**不建跨库外键**。
--
--   * **不预置任何业务数据**：没有「默认小额上限」、没有「默认风险信号码表」。
--     规则由租户自己登记；系统零预置行（与 V418 同纪律）。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 与上游 17.4 的边界（本车道**亲自查证**过的事实，不是推测）
-- ---------------------------------------------------------------------------
--
-- 上游 identity 的审批端点只有三种形状：
--
--   POST /identity/approval-instances      **建单（intake）**：
--                                            X-Internal-Key + 已认证会话 → 建一条 PENDING 实例，
--                                            按金额分层分支选起始节点。**这是程序化通道。**
--   POST /identity/my-approvals/{id}/decide **人工决策**：
--                                            绑定调用者自己的会话；`actor_membership_id` 恒为调用者；
--                                            取单条件是「这条待办归我」（指派 / 节点指定人 / 节点角色）。
--   POST /identity/my-approvals/{id}/transfer 同上（转交也只能由**当前审批人**发起）。
--
-- ⇒ **上游没有「程序化通过 / 驳回」通道**。因此本模块的形态是
--    **「判定 + 记录 + 经授权端口转交」**：
--      · AUTO_APPROVED / AUTO_REJECTED → **不建上游实例**（没有单子需要人审，
--        就没有待办；这与上游自己「免审 EXEMPT」的语义一致），
--        决策与依据落在本模块的**决策台账**里，供下游各业务系统取用；
--      · MANUAL_REQUIRED → 经授权端口调上游 intake **真的建单**，让人去审；
--        建单之后这张单的状态**只由 identity 管**，本模块只留一个引用。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 本模块最核心的正确性要求：「只处理明确安全的场景」
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句：**「自动审批只处理『明确安全』的场景（小额 + 供应商已验证 + 无风险信号），
-- 其余仍走人工——审批是内控底线。」**
--
-- 这条要求在本迁移里被钉成**三个物理事实**：
--
--   (a) **小额上限是 `NUMERIC` 列**（`auto_approve_max_amount`），不是浮点常量、不是代码里的字面量。
--       金额比较一律 `BigDecimal.compareTo`（scale 不同 `equals` 会误判相等）。
--   (b) **「供应商已验证」是判定引擎里的硬条件**，不可配置、不可关闭。
--   (c) **风险信号分两类，DB 层就把取值域钉死**：
--         `VIOLATION` = 确定性违规（必填缺失 / 金额越界 / 黑名单命中）
--                       ⇒ **可以**自动驳回；
--         `RISK`      = 主观风险（「风险偏高」这类判断）
--                       ⇒ **绝不**自动驳回，只能走人工。
--       判定引擎只认**租户信号码表里登记过的**码；未登记的码按 `RISK` 处理
--       （未知 ⇒ 从严），所以调用方**无法**靠编一个码来把单子驳掉。
--
-- ⚠️ **没有「风险偏高就驳回」这条路**：这是内控底线，不是可以调参的偏好。
--
-- ---------------------------------------------------------------------------
-- 3. 表清单（四张）
-- ---------------------------------------------------------------------------
--
--   platform_auto_approval_policy    **自动审批规则配置**（输入的第一项：审批规则配置）
--   platform_auto_approval_signal    **风险信号码表**（输入的第二项：单据风险信号的取值域）
--   platform_auto_approval_decision  **自动审批决策台账**（输出：结果 + 决策依据快照；只追加）
--   platform_auto_approval_escalation **超时升级台账**（超时自动升级的判定与执行留痕）
--
-- 四张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 4. 金额与数量
-- ---------------------------------------------------------------------------
--
--   * 金额一律 `numeric(19,6)`（AGENTS.md：金额用 BigDecimal + NUMERIC，禁浮点记账）。
--   * 数量（超时小时数、已等待小时数）一律 `numeric(19,6)`（AGENTS.md 明文口径）。
--   * 时间列一律 `timestamptz`；服务层用 `OffsetDateTime` 绑定并在 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_auto_approval_policy —— 自动审批规则配置
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个租户在一张单据类型 + 一个币种下的一条**生效**自动审批规则。
--
-- 🔴 **「小额上限」与「硬上限」是两个不同的东西，不是同一个数的两种叫法**：
--   * `auto_approve_max_amount` —— **小额天花板**：金额超过它 ⇒ 这一条「小额」条件不成立
--     ⇒ **走人工**（不是驳回！金额大不是「明显不合规」，只是不该自动放行）。
--   * `amount_hard_limit` —— **硬上限**：金额超过它 ⇒ 金额越界（确定性违规）⇒ **可自动驳回**。
--     为空 = 本租户不设硬上限（任何金额都只可能是「非小额」或「越界」，由上限是否配置决定）。
--
-- `auto_approve_max_amount` 为空 ⇒ **任何金额都不许自动通过**（该规则退化成「只判违规、其余走人工」）。
--
-- 🔴 `upstream_flow_id` 是**只读引用**（identity 17.4 的 `approval_flow.id`）：
--   **不建跨库外键**（AGENTS.md：禁止跨服务 FK）——它指向的是另一个服务的库。
--   人工审批时用它指定「这张单该走哪条审批流」；金额分层路由（哪一档审批人）
--   由上游那条流程的 `approval_flow_amount_branch` 决定，本模块**只读不另造**。
CREATE TABLE platform_auto_approval_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则编码（租户内唯一）：给人看的稳定标识。
  rule_code varchar(64) NOT NULL,
  rule_name varchar(120) NOT NULL,

  -- 单据类型码（与上游 `approval_document_type.code` 同一取值域；只读引用，不建 FK）。
  document_type_code varchar(32) NOT NULL,
  -- 币种：自动审批的上限**按币种**配置（不同币种的「小额」不是一回事）。
  currency_code char(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),

  -- 🔴 小额天花板（NULL = 不允许自动通过任何金额）。
  auto_approve_max_amount numeric(19,6),
  -- 🔴 硬上限（NULL = 无硬上限）。超过它 = 金额越界 = 确定性违规。
  amount_hard_limit numeric(19,6),

  -- 上游审批流（**只读引用**，不建跨库 FK）：人工审批时提交给哪条流程。
  upstream_flow_id uuid,

  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','DRAFT','DISABLED')),
  priority int NOT NULL DEFAULT 0,

  remark varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code),

  CHECK(btrim(rule_code) <> '' AND length(rule_code) <= 64),
  CHECK(btrim(rule_name) <> '' AND length(rule_name) <= 120),
  CHECK(btrim(document_type_code) <> '' AND length(document_type_code) <= 32),
  CHECK(auto_approve_max_amount IS NULL OR auto_approve_max_amount >= 0),
  CHECK(amount_hard_limit IS NULL OR amount_hard_limit >= 0),
  -- 小额天花板必须**不大于**硬上限：反过来的话「小额」区间会落在硬上限之外，
  -- 于是同一笔金额会同时「够小」又「越界」，判定自相矛盾。
  CHECK(
    auto_approve_max_amount IS NULL
    OR amount_hard_limit IS NULL
    OR auto_approve_max_amount <= amount_hard_limit
  )
);

-- 🔴 **一个单据类型 + 币种只能有一条生效规则**：两条生效规则同时命中时
--    「哪条说了算」没有答案（而自动审批的方向一旦选错，代价是真金白银）。
--    用**部分唯一索引**（只收 ACTIVE）：停用的旧规则留在库里（历史决策要能复现），
--    同时不妨碍租户登记新规则。
CREATE UNIQUE INDEX platform_auto_approval_policy_active
  ON platform_auto_approval_policy(tenant_id, document_type_code, currency_code)
  WHERE status = 'ACTIVE';

CREATE INDEX platform_auto_approval_policy_by_document_type
  ON platform_auto_approval_policy(tenant_id, document_type_code, status);

-- ---------------------------------------------------------------------------
-- 2. platform_auto_approval_signal —— 风险信号码表
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个租户承认的**风险信号码**，以及它**属于哪一类**。
--
-- 🔴 `signal_kind` 是本模块最重要的一列取值域，它决定「能不能自动驳回」：
--   VIOLATION 确定性违规 —— 事实层面已经不合规（必填缺失 / 金额越界 / 黑名单命中）。
--              命中它 ⇒ **可以**自动驳回。
--   RISK      主观风险 —— 「风险偏高」这类需要人判断的信号。
--              命中它 ⇒ **只能**走人工，**绝不**自动驳回。
--
-- 🔴 **调用方不能自带「这条信号是什么性质」**：判定引擎只按**码表**查性质，
--    查不到 ⇒ 按 `RISK` 从严处理。一个没登记过的码**永远**只能让人工审，
--    编一个码就能把单子驳掉这种事在本模块里**不可能**发生。
CREATE TABLE platform_auto_approval_signal (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  signal_code varchar(64) NOT NULL,
  signal_name varchar(120) NOT NULL,

  -- 🔴 见上：VIOLATION 才可自动驳回，RISK 永远只走人工。
  signal_kind text NOT NULL CHECK(signal_kind IN ('VIOLATION','RISK')),

  description varchar(300) NOT NULL DEFAULT '',
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, signal_code),

  CHECK(btrim(signal_code) <> '' AND length(signal_code) <= 64),
  CHECK(btrim(signal_name) <> '' AND length(signal_name) <= 120)
);

CREATE INDEX platform_auto_approval_signal_by_kind
  ON platform_auto_approval_signal(tenant_id, signal_kind, status);

-- ---------------------------------------------------------------------------
-- 3. platform_auto_approval_decision —— 自动审批决策台账（输出）
-- ---------------------------------------------------------------------------
--
-- 一行 = **一次自动审批判定**的结论与依据。
--
-- 🔴 **只追加**：已写下的决策与依据**不可修改、不可删除**。
--   决策依据是「当时依据的规则与风险信号」的**快照**（不是事后读当下的规则）——
--   规则改了以后再回看这一行，必须看到的仍是**当初**那份依据。
--   能改的审批依据等于没有审批依据。
--
-- 🔴 **快照三件套**（jsonb）：
--   `policy_snapshot`      —— 判定当时生效的那条规则（小额上限 / 硬上限 / 上游流程…）。
--   `risk_signal_snapshot` —— 判定当时收到的信号及其**性质**（VIOLATION / RISK）。
--   `routing_snapshot`     —— 金额分层路由的结论（命中哪一档、目标节点、审批人类型）。
--   三件套都只写不改 ⇒ 「为什么这张单被自动放行」永远可回溯。
CREATE TABLE platform_auto_approval_decision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 决策号：租户内可读的单据序号（'AA-' + 12 位），全局序列 + 租户内唯一。
  decision_no varchar(20) NOT NULL,

  -- ---- 被判定的单据事实 ----
  document_type_code varchar(32) NOT NULL,
  document_ref varchar(100) NOT NULL,
  subject_title varchar(200) NOT NULL DEFAULT '',
  source_system varchar(40) NOT NULL,
  currency_code char(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  amount numeric(19,6),
  supplier_id uuid,
  supplier_verified boolean NOT NULL DEFAULT false,

  -- ---- 决策 ----
  --   AUTO_APPROVED  自动通过（明确安全：小额 + 供应商已验证 + 无风险信号）
  --   AUTO_REJECTED  自动驳回（有确定性违规）
  --   MANUAL_REQUIRED 必须走人工（其余一切情形）
  decision text NOT NULL CHECK(decision IN ('AUTO_APPROVED','AUTO_REJECTED','MANUAL_REQUIRED')),
  -- 机器可判定的结果码（页面据此标色、脚本据此分支）。
  reason_code varchar(64) NOT NULL,
  reason_message varchar(500) NOT NULL DEFAULT '',

  -- 决策**是否需要落到人工审批**这一步：
  --   NOT_REQUIRED          自动决策，不需要人工（自动通过 / 自动驳回）
  --   SUBMITTED_TO_APPROVAL 已提交上游待人工审批
  --   SUBMIT_FAILED         判定为必须人工，但**提交上游失败**（如实记，不假装已提交）
  execution_state text NOT NULL
    CHECK(execution_state IN ('NOT_REQUIRED','SUBMITTED_TO_APPROVAL','SUBMIT_FAILED')),

  -- ---- 决策依据快照（只写不改） ----
  policy_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  risk_signal_snapshot jsonb NOT NULL DEFAULT '[]'::jsonb,
  routing_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 逐条判定明细（哪几项条件成立、哪一项不成立）—— 决策依据的**人可读**部分。
  checks jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- ---- 上游（identity 17.4）引用 ----
  -- 只有走人工时才有值。**只读引用**，不建跨库 FK。
  upstream_instance_id uuid,
  upstream_instance_no varchar(64),
  upstream_node_key varchar(32),
  upstream_routed_by varchar(32),

  decided_at timestamptz NOT NULL,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, decision_no),

  CHECK(btrim(document_ref) <> '' AND length(document_ref) <= 100),
  CHECK(btrim(source_system) <> '' AND length(source_system) <= 40),
  CHECK(amount IS NULL OR amount >= 0),
  -- 自动通过 ⇒ 不需要人工这一步；自动驳回 ⇒ 同样不需要（没有单子要人审）。
  CHECK(decision <> 'AUTO_APPROVED' OR execution_state = 'NOT_REQUIRED'),
  CHECK(decision <> 'AUTO_REJECTED' OR execution_state = 'NOT_REQUIRED'),
  -- 走人工 ⇒ 必须真的提交过（失败也要如实记 SUBMIT_FAILED，但**不能**是 NOT_REQUIRED）。
  CHECK(decision <> 'MANUAL_REQUIRED' OR execution_state <> 'NOT_REQUIRED'),
  -- 提交成功 ⇒ 必须有上游实例引用；提交失败 ⇒ 必须**没有**（否则就成了两套真相）。
  CHECK(
    (execution_state = 'SUBMITTED_TO_APPROVAL' AND upstream_instance_id IS NOT NULL)
    OR (execution_state <> 'SUBMITTED_TO_APPROVAL' AND upstream_instance_id IS NULL)
  )
);

CREATE INDEX platform_auto_approval_decision_by_document
  ON platform_auto_approval_decision(tenant_id, document_type_code, decided_at DESC);
CREATE INDEX platform_auto_approval_decision_by_decision
  ON platform_auto_approval_decision(tenant_id, decision, decided_at DESC);
-- 待人工且已提交上游的那些单，是超时升级扫描的输入范围。
CREATE INDEX platform_auto_approval_decision_pending_upstream
  ON platform_auto_approval_decision(tenant_id, upstream_instance_id)
  WHERE decision = 'MANUAL_REQUIRED' AND execution_state = 'SUBMITTED_TO_APPROVAL';

-- 决策号用全局序列：本表只存在于本服务，序列号不承载租户语义，
-- 租户内唯一由 UNIQUE(tenant_id, decision_no) 兜底（与 identity V160 同款做法）。
CREATE SEQUENCE platform_auto_approval_decision_no_seq;
CREATE OR REPLACE FUNCTION platform_next_auto_approval_decision_no() RETURNS varchar
LANGUAGE sql VOLATILE AS $$
  SELECT 'AA-' || lpad(nextval('platform_auto_approval_decision_no_seq')::text, 12, '0')
$$;

-- ---------------------------------------------------------------------------
-- 4. platform_auto_approval_escalation —— 超时升级台账
-- ---------------------------------------------------------------------------
--
-- 一行 = **一次超时升级的判定**（以及它的执行结论）。
--
-- 升级规则本身住在上游（identity 的 `approval_flow_escalation`：节点 + 超时小时数 +
-- 升级动作 + 升级目标），本表存的是**判定当时**的规则快照与判定结论。
--
-- 🔴 **升级时限的上游定义只定义、不放行**：上游的注释逐字写着
--   「本模块只定义它，不实现任何会去看它并放行的定时器」⇒ **看表并放行是本模块的活**。
--
-- 🔴 `timeout_action` 与上游同口径：
--   ESCALATE     超时则把审批权转给另一类人（转交目标见三列 escalate_*）。
--   AUTO_APPROVE 超时视同通过（上游自己的一条真实管控策略）。
--
-- 🔴 **「超时」只有一个时钟基准**：本模块用**上游算出来的停留秒数**
--   （identity 的 `approval_dwell_seconds`，DB 钟域，与升级规则的时限同一钟域）
--   与 `timeout_hours` 比较 —— 不用应用服务器墙钟去减一个上游时间戳
--   （两个钟域相减，差值是错的），也不用 `Timestamp.valueOf(LocalDateTime.now())`
--   （会静默偏 −28800s），更不 sleep 等时间过去。
CREATE TABLE platform_auto_approval_escalation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 关联的决策（同一租户）：升级的是「本模块判为必须人工、并已提交上游」的那张单。
  decision_id uuid NOT NULL,
  upstream_instance_id uuid NOT NULL,
  upstream_flow_id uuid NOT NULL,

  -- 判定时的节点与时限（上游规则快照）。
  node_key varchar(32) NOT NULL,
  timeout_hours int NOT NULL CHECK(timeout_hours > 0),
  timeout_action text NOT NULL CHECK(timeout_action IN ('ESCALATE','AUTO_APPROVE')),
  escalate_approver_type text CHECK(escalate_approver_type IN ('ROLE','POSITION','USER')),
  escalate_role_id uuid,
  escalate_position_id uuid,
  escalate_membership_id uuid,

  -- 已等待小时数（数量，NUMERIC(19,6)，禁浮点）：由上游停留秒数换算。
  elapsed_hours numeric(19,6) NOT NULL CHECK(elapsed_hours >= 0),
  -- 上游当时的停留秒数（原始值，判定依据之一）。
  upstream_dwell_seconds bigint NOT NULL CHECK(upstream_dwell_seconds >= 0),
  -- 判定时上游那条升级规则的快照（只写不改）。
  rule_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,

  status text NOT NULL DEFAULT 'DETECTED'
    CHECK(status IN ('DETECTED','EXECUTED','FAILED','SUPERSEDED')),
  -- 执行结论（人可读）：执行成功时是上游的结论，失败时是**为什么没能执行**。
  execution_detail varchar(500) NOT NULL DEFAULT '',

  detected_at timestamptz NOT NULL,
  executed_at timestamptz,
  executed_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 同一张上游单、同一节点、同一时限**只升级一次**：
  --    重复扫描不会把一条单子反复升级（否则升级台账会被同一条规则刷屏）。
  UNIQUE(tenant_id, upstream_instance_id, node_key, timeout_hours),

  FOREIGN KEY(tenant_id, decision_id) REFERENCES platform_auto_approval_decision(tenant_id, id),

  -- ESCALATE 必须指明转给谁；AUTO_APPROVE 不需要人，三列都得空着（与上游同口径）。
  CHECK(
    (timeout_action = 'ESCALATE'
       AND escalate_approver_type IS NOT NULL
       AND ((escalate_approver_type = 'ROLE'     AND escalate_role_id IS NOT NULL AND escalate_position_id IS NULL AND escalate_membership_id IS NULL)
         OR (escalate_approver_type = 'POSITION' AND escalate_role_id IS NULL AND escalate_position_id IS NOT NULL AND escalate_membership_id IS NULL)
         OR (escalate_approver_type = 'USER'     AND escalate_role_id IS NULL AND escalate_position_id IS NULL AND escalate_membership_id IS NOT NULL)))
    OR (timeout_action = 'AUTO_APPROVE'
       AND escalate_approver_type IS NULL
       AND escalate_role_id IS NULL
       AND escalate_position_id IS NULL
       AND escalate_membership_id IS NULL)
  )
);

CREATE INDEX platform_auto_approval_escalation_by_status
  ON platform_auto_approval_escalation(tenant_id, status, detected_at DESC);

-- ---------------------------------------------------------------------------
-- 5. 只追加台账的触发器
-- ---------------------------------------------------------------------------
--
-- 🔴 决策台账是**只追加**的：用**触发器**而不是「少授一个权限码」——
--    少授会被「迁移脚本用 owner 身份跑」绕开，触发器不会（与 V418 的同步台账同纪律）。
CREATE FUNCTION refuse_platform_auto_approval_decision_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_auto_approval_decision 是只追加台账：自动审批决策与依据不可修改（%）',
    TG_OP;
END;
$$;

CREATE FUNCTION refuse_platform_auto_approval_decision_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_auto_approval_decision 是只追加台账：自动审批决策与依据不可删除（%）',
    TG_OP;
END;
$$;

CREATE TRIGGER guard_platform_auto_approval_decision_update
  BEFORE UPDATE ON platform_auto_approval_decision
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_auto_approval_decision_update();

CREATE TRIGGER guard_platform_auto_approval_decision_delete
  BEFORE DELETE ON platform_auto_approval_decision
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_auto_approval_decision_delete();

-- 🔴 升级台账**允许**一次状态推进（DETECTED → EXECUTED / FAILED）：
--    「判定到升级」与「升级执行了没有」是两个不同时刻，必须都能留痕。
--    但**只允许这一次推进**，且不允许改判定依据（时限、动作、目标、已等待时长一律不可改）。
CREATE FUNCTION guard_platform_auto_approval_escalation_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'DETECTED' THEN
    RAISE EXCEPTION 'platform_auto_approval_escalation 只允许 DETECTED → EXECUTED/FAILED 这一次推进（当前 %）',
      OLD.status;
  END IF;
  IF NEW.status NOT IN ('EXECUTED','FAILED') THEN
    RAISE EXCEPTION 'platform_auto_approval_escalation 的状态只能推进到 EXECUTED / FAILED（收到 %）',
      NEW.status;
  END IF;
  IF NEW.decision_id <> OLD.decision_id
     OR NEW.upstream_instance_id <> OLD.upstream_instance_id
     OR NEW.node_key <> OLD.node_key
     OR NEW.timeout_hours <> OLD.timeout_hours
     OR NEW.timeout_action <> OLD.timeout_action
     OR NEW.elapsed_hours <> OLD.elapsed_hours
     OR NEW.detected_at <> OLD.detected_at THEN
    RAISE EXCEPTION 'platform_auto_approval_escalation 的判定依据不可修改（升级依据必须与判定当时一致）';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER guard_platform_auto_approval_escalation_update
  BEFORE UPDATE ON platform_auto_approval_escalation
  FOR EACH ROW EXECUTE FUNCTION guard_platform_auto_approval_escalation_update();

CREATE FUNCTION refuse_platform_auto_approval_escalation_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_auto_approval_escalation 是只追加台账：升级判定不可删除（%）',
    TG_OP;
END;
$$;

CREATE TRIGGER guard_platform_auto_approval_escalation_delete
  BEFORE DELETE ON platform_auto_approval_escalation
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_auto_approval_escalation_delete();

-- ===========================================================================
-- 6. 租户隔离（RLS）
-- ===========================================================================
--
-- 四张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / platform V377 / V416 / V418 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_auto_approval_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_auto_approval_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_auto_approval_policy
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_auto_approval_signal ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_auto_approval_signal FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_auto_approval_signal
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_auto_approval_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_auto_approval_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_auto_approval_decision
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_auto_approval_escalation ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_auto_approval_escalation FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_auto_approval_escalation
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 7. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的规则配置与决策台账、能写
-- （登记规则 / 登记信号码 / 记决策 / 记升级判定与执行结论）。
--
-- ⚠️ 决策台账与升级判定都给了 UPDATE / DELETE 授权，但**触发器**会拒绝它们 ——
--    「不可改」这件事由数据层的触发器钉死。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_auto_approval_policy TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_auto_approval_signal TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_auto_approval_decision TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_auto_approval_escalation TO platform_app;
GRANT USAGE,SELECT ON SEQUENCE platform_auto_approval_decision_no_seq TO platform_app;
GRANT EXECUTE ON FUNCTION platform_next_auto_approval_decision_no() TO platform_app;
