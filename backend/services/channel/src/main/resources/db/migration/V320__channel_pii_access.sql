-- ===========================================================================
-- 16.6.1.1 PII 管理 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 231-236 行）：
--   ### 16.6.1.1 PII 管理
--   - 职责：管理个人信息（买家姓名、地址、电话、邮箱）的访问与脱敏：默认脱敏展示、
--           按角色授权查看完整信息、访问留痕、导出审批。
--   - 输入：PII 访问请求；授权配置；导出申请。
--   - 输出：脱敏数据或授权后的完整数据；访问审计记录。
--   - 关系：上游 = 15 客户、9 订单；下游 = 客服与运营页面。
--           **完整地址查看需留痕**——批量导出 PII 是数据泄露事件的典型形态。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本表存在的理由：**PII 明文不进本服务**
-- ---------------------------------------------------------------------------
-- 蓝图把「访问与脱敏」划给本模块，而**买家 PII 的真值在别人库里**：
--   * 9 订单（trade 服务）：`order_address_snapshot` 的 recipient_name / recipient_phone /
--     recipient_email / address_line1 —— **订单上冻结的那一份地址快照**。
--   * 15 客户（crm 服务）：`crm_buyer_email` 的 buyer_name / buyer_email、
--     `crm_customer_contact` 的 customer_name / operator_id。
-- AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ 本模块**只经授权 HTTP API 读**。
--
-- 🔴 所以本迁移建的六张表里**没有一张存买家 PII 明文**：
--   `pii_access_policy`          策略（导出上限 / 审批有效期 / 是否必须写理由 / 每日查看上限）
--   `pii_access_grant`           授权配置（角色 × 字段 × 成员范围）
--   `pii_access_view_audit`      访问审计（append-only）
--   `pii_access_export_request`  导出申请与审批
--   `pii_access_export_item`     导出申请里的主体清单（append-only）
--   `pii_access_export_batch`    导出执行凭据（append-only）
-- 主体标识（order id / buyer_ref）不是 PII；**人读得懂的姓名 / 电话 / 邮箱 / 街道一律不落库**。
-- 审计里的 `subject_label` 存的是**脱敏标签**，导出凭据里的 `subject_digest` 存的是
-- 主体清单的 md5 —— 事后能证明「导出了哪一批」而**不复制一份 PII 出来**。
-- （复制一份 PII 到本服务 = 新增一个泄露面，那是本模块最该避免的事。）
--
-- ---------------------------------------------------------------------------
-- 二、🔴 表命名空间隔离（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.6.1.1）⇒ `pii_access_*`。
--   与既有命名空间**彼此不同**：channel 库的 `compliance_rule_*` / `compliance_intercept_*` /
--   `epr_*` / `ip_risk_*` / `buyer_risk_*` / `brand_authz_*` / `brand_cred_*` /
--   `product_certification_*` / `tax_registration_*` / `listing_compliance_*`。
--   ⇒ 本迁移**不建** `pii_*` 同名表，也**不 ALTER** 任何既有表。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 零外键（与 channel V300 / V308 / V312 / V315 / V316 同一纪律）
-- ---------------------------------------------------------------------------
-- channel 的既有集成测试用 `truncate <表清单>` 清库，外键会让 TRUNCATE 报 23503。
-- 所以本迁移**不建任何外键**，跨表引用改由 BEFORE INSERT 触发器校验。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V197 / V300 / V308 / V312 / V316 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **只带 isolation 策略，不带 data_scope（RESTRICTIVE）策略**：
--   买家 / 订单是**租户级主体**，不是某家店 / 某个部门的资产。硬套店铺收敛会让
--   「这个买家在这家跨境店铺下的 PII」在只有 SELF 范围的成员面前凭空消失。
--   （与 V316 的 `buyer_risk_*` 同一处理：**主体侧不收敛，收敛靠授权配置里的 scope_mode**。）
--
-- ---------------------------------------------------------------------------
-- 五、🔴 状态机与不变量：DB 层钉死，服务层复查一次
-- ---------------------------------------------------------------------------
--   * 导出申请 PENDING → APPROVED / REJECTED → EXECUTED。
--   * **审批人 ≠ 申请人**（自己批自己的审批形同虚设）。
--   * **未审批的导出一律不许执行**。
--   * 导出条数 ≤ 申请时记录的上限快照（DB 层 CHECK + 延迟约束触发器核对条数）。
--   * 审计与导出凭据 **append-only**（GRANT 只给 SELECT,INSERT，**刻意不给 UPDATE/DELETE**）。
--
-- ===========================================================================

-- ===========================================================================
-- 一、pii_access_policy · 一租户一条策略配置
-- ===========================================================================
-- 🔴 「默认脱敏」**不是这里的一个开关**：本表**没有**「是否脱敏」这一列，
--   脱敏是写死在服务层与 `PiiAccessRules` 里的**硬默认**，任何配置都关不掉它。
--   （蓝图把脱敏写成默认职责，任务书把「默认脱敏是硬默认，不是可选开关」写死。）
--
-- 🔴 本表**也没有**「导出是否需要审批」这一列 —— 审批是硬规则（蓝图的加粗句）。
--   把它做成可配置就等于留了一个「关掉审批就能全量导出」的后门。
--
CREATE TABLE pii_access_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  policy_name varchar(120) NOT NULL CHECK(btrim(policy_name) <> ''),

  -- ---- 导出限量（蓝图的加粗句：批量导出 PII 是数据泄露事件的典型形态）----
  -- 单次导出**条数上限**。DB 层钉死 ≤ 500：不给「一键全量导出」留任何余地。
  max_export_records int NOT NULL CHECK(max_export_records > 0 AND max_export_records <= 500),
  -- 导出审批的**有效期（小时）**：批准后过这么久没执行就作废。
  export_approval_ttl_hours int NOT NULL
    CHECK(export_approval_ttl_hours > 0 AND export_approval_ttl_hours <= 720),
  -- 单个成员**每日**可查看完整 PII 的次数上限；0 = 不限（由租户自己选）。
  max_reveal_per_day int NOT NULL DEFAULT 0 CHECK(max_reveal_per_day >= 0),
  -- 查看完整值是否**必须写理由**（默认要）。
  require_reason_on_reveal boolean NOT NULL DEFAULT true,

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一租户一条策略。
  UNIQUE(tenant_id),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE pii_access_policy IS
  '16.6.1.1 · PII 访问与导出策略（一租户一条）。**刻意没有「是否脱敏」「导出是否需审批」两列** ——'
  '脱敏是硬默认、审批是硬规则，做成可配置等于留后门。本表只管限量、有效期与每日查看次数上限。';

-- ===========================================================================
-- 二、pii_access_grant · 授权配置（角色 × 字段 × 成员范围）
-- ===========================================================================
-- 🔴 蓝图写的是「**按角色**授权查看完整信息」+「授权配置」⇒ 授权的粒度是
--   （角色 × 字段类别），不是「谁能看这个买家」。字段级别授权是为了
--   「客服能看姓名电话，但地址要主管批」这种真实运营口径。
--
-- 🔴 **scope_mode 的语义**（与 identity 侧 AccessService 用的同一套词）：
--   ALL         —— 该角色的任何成员都能查看完整值。
--   DEPARTMENT  —— 该角色的成员**必须在某个部门里**（identity 侧解析出来的 department 非空）。
--   SELF        —— 只可查看**本人经手**的主体的完整值。
--     · CUSTOMER 主体：crm 侧接触记录带 operator_id，能判。
--     · ORDER 主体：trade 的 `sales_order` / `order_address_snapshot` **没有经手人列**
--       ⇒ 该主体**判不了** ⇒ 拒绝（不是默认放行）。判不了就是拒绝，这是本仓的同一纪律。
--
CREATE TABLE pii_access_grant (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- identity 的角色 code（不是 id：跨服务 id 在本仓没有稳定形状）。
  role_code varchar(64) NOT NULL CHECK(btrim(role_code) <> ''),
  -- 哪个字段类别可以看完整值。
  field_code varchar(16) NOT NULL
    CHECK(field_code IN ('NAME','PHONE','EMAIL','ADDRESS')),
  -- 成员范围。
  scope_mode varchar(16) NOT NULL CHECK(scope_mode IN ('ALL','DEPARTMENT','SELF')),
  -- 该授权下查看完整值是否必须写理由。
  reason_required boolean NOT NULL DEFAULT true,

  -- ---- 有效期 ----
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  CHECK(effective_to IS NULL OR effective_to > effective_from),

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),

  granted_by uuid NOT NULL,
  granted_at timestamptz NOT NULL,
  revoked_by uuid,
  revoked_at timestamptz,
  revoke_reason varchar(600) NOT NULL DEFAULT '',
  -- 🔴 撤销必须写理由 + 撤销人 + 撤销时刻（三者缺一就不是撤销）。
  CHECK(status <> 'REVOKED' OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND btrim(revoke_reason) <> '')),
  -- 反向也要钉住：没撤销就不许有撤销信息（否则「撤销了但看起来还生效」）。
  CHECK(status <> 'ACTIVE' OR (revoked_by IS NULL AND revoked_at IS NULL)),

  note varchar(600) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一租户内一个（角色 × 字段）只有一条授权配置；撤销后再授权走**同一条的更新**，
  -- 不另起一行（否则同一对（角色,字段）会有两条互相矛盾的授权，判定就成了「挑一条运气好的」）。
  UNIQUE(tenant_id, role_code, field_code),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE pii_access_grant IS
  '16.6.1.1 · 「按角色授权查看完整 PII」的授权配置（角色 × 字段 × 成员范围）。'
  '**默���脱敏是硬默认**：本表只决定「谁在什么范围内可以看完整值」，'
  '不决定「要不要脱敏」—— 没有本表任何一行，界面也只是脱敏，绝不退化成明文。';

CREATE INDEX pii_access_grant_by_role ON pii_access_grant(tenant_id, role_code, field_code);
CREATE INDEX pii_access_grant_by_status ON pii_access_grant(tenant_id, status, field_code);

-- ===========================================================================
-- 三、pii_access_view_audit · 访问审计（append-only）
-- ===========================================================================
-- 🔴 蓝图加粗句「**完整地址查看需留痕**」的物理落点。
--
-- 🔴 **append-only**：
--   * GRANT 只给 SELECT, INSERT —— **刻意不给 UPDATE / DELETE**。
--   * 再加 BEFORE UPDATE OR DELETE 触发器直接 RAISE（连迁移超级账号都改不动）。
--   理由：一次「谁看了哪个买家的哪一栏、什么时候、依据哪条授权」的记录，
--   如果事后能改，那它就不是审计，只是日志。
--
-- 🔴 **field_code 的两种含义**（枚举里刻意留了 'ALL'）：
--   'ALL'        —— 脱敏视图（**默认路径**）：整条主体按脱敏规则展示了一次。
--   'NAME' 等    —— 针对**某一个字段**看完整值（授权命中的那次）。
--   区分开是为了能回答两个不同的问题：「谁看过这个买家」与「谁看过这个买家的完整电话」。
--
CREATE TABLE pii_access_view_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 主体类型与标识（**标识不是 PII**）。
  subject_kind varchar(16) NOT NULL CHECK(subject_kind IN ('CUSTOMER','ORDER')),
  subject_ref varchar(160) NOT NULL CHECK(btrim(subject_ref) <> ''),
  -- 🔴 **脱敏标签**，不是明文标签 —— 审计表也不该变成第二个 PII 副本。
  subject_label varchar(200) NOT NULL DEFAULT '',

  -- 'ALL' = 脱敏视图；'NAME'/'PHONE'/'EMAIL'/'ADDRESS' = 看该字段的完整值。
  field_code varchar(16) NOT NULL
    CHECK(field_code IN ('ALL','NAME','PHONE','EMAIL','ADDRESS')),
  -- VIEW_MASKED 脱敏展示 / VIEW_FULL 看完整值 / EXPORT 导出。
  action varchar(16) NOT NULL CHECK(action IN ('VIEW_MASKED','VIEW_FULL','EXPORT')),
  -- ALLOWED 放行 / DENIED 拒绝（**越权尝试也要留痕**，那正是最该被看见的一类记录）。
  decision varchar(16) NOT NULL CHECK(decision IN ('ALLOWED','DENIED')),

  -- 依据哪条授权（脱敏视图与被拒的尝试都没有 ⇒ NULL）。
  grant_id uuid,
  -- 命中授权时的角色 code 快照（授权后来被撤销了，审计仍要能说清当时依据的是哪条）。
  grant_role_code varchar(64) NOT NULL DEFAULT '',
  -- 被拒时的人话原因。
  decision_reason varchar(600) NOT NULL DEFAULT '',

  actor_id uuid NOT NULL,
  actor_name varchar(200) NOT NULL DEFAULT '',
  -- 查看完整值的理由（授权要求写理由时必填）。
  reason varchar(600) NOT NULL DEFAULT '',

  -- 导出时指向那张导出申请。
  export_request_id uuid,

  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 脱敏视图 ⇒ 一定没有授权、没有导出单（脱敏不需要授权）。
  CHECK(action <> 'VIEW_MASKED' OR (decision = 'ALLOWED' AND grant_id IS NULL AND export_request_id IS NULL)),
  -- 🔴 导出 ⇒ 一定挂在某张导出申请上（导出必须走审批 ⇒ 每次导出都有单可查）。
  CHECK(action <> 'EXPORT' OR export_request_id IS NOT NULL),
  -- 🔴 看完整值被放行 ⇒ 必须指得出依据的授权。
  CHECK(decision <> 'ALLOWED' OR action <> 'VIEW_FULL' OR grant_id IS NOT NULL),
  -- 🔴 被拒 ⇒ 没有授权（被拒正是因为没授权）。
  CHECK(decision <> 'DENIED' OR grant_id IS NULL),
  CHECK(occurred_at <= created_at + interval '1 day')
);
COMMENT ON TABLE pii_access_view_audit IS
  '16.6.1.1 · PII 访问审计（**append-only**：GRANT 只给 SELECT,INSERT，触发器禁止 UPDATE/DELETE）。'
  '完整值查看、越权尝试与导出逐条留痕；脱敏视图也留痕（field_code=ALL）。'
  '**本表不存买家 PII 明文**：subject_label 是脱敏标签。';

CREATE INDEX pii_access_view_audit_by_time ON pii_access_view_audit(tenant_id, occurred_at DESC);
CREATE INDEX pii_access_view_audit_by_actor ON pii_access_view_audit(tenant_id, actor_id, occurred_at DESC);
CREATE INDEX pii_access_view_audit_by_subject ON pii_access_view_audit(tenant_id, subject_kind, subject_ref, occurred_at DESC);
CREATE INDEX pii_access_view_audit_by_export ON pii_access_view_audit(tenant_id, export_request_id);
CREATE INDEX pii_access_view_audit_by_action ON pii_access_view_audit(tenant_id, action, decision, occurred_at DESC);

-- ===========================================================================
-- 四、pii_access_export_request · 导出申请与审批
-- ===========================================================================
-- 🔴 蓝图加粗句「批量导出 PII 是数据泄露事件的典型形态」的物理落点：
--   **申请 → 审批 → 执行** 三段式，缺一段都不许导出。
--
-- 🔴 **审批人 ≠ 申请人**（DB 层 CHECK 钉死，服务层再查一次）。
--   自己批自己的审批形同虚设。
--
-- 🔴 **未审批的导出请求一律拒绝**（status 必须是 APPROVED 才可执行）。
--
CREATE TABLE pii_access_export_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  request_no varchar(32) NOT NULL CHECK(btrim(request_no) <> ''),
  -- 导出哪一类主体的哪个字段。
  subject_kind varchar(16) NOT NULL CHECK(subject_kind IN ('CUSTOMER','ORDER')),
  field_code varchar(16) NOT NULL CHECK(field_code IN ('NAME','PHONE','EMAIL','ADDRESS')),
  -- 申请导出多少条（**上限**，实际条数由清单决定，见 pii_access_export_item）。
  record_count int NOT NULL CHECK(record_count > 0),
  -- 申请时的条数上限快照：执行时除了查当时的策略，还要被这个快照卡一道。
  max_records_limit int NOT NULL CHECK(max_records_limit > 0),

  -- 🔴 导出理由**必填**：没有理由的导出申请无法被审批人判断。
  purpose varchar(600) NOT NULL CHECK(btrim(purpose) <> ''),

  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','APPROVED','REJECTED','EXECUTED')),

  requested_by uuid NOT NULL,
  requested_by_name varchar(200) NOT NULL DEFAULT '',
  requested_at timestamptz NOT NULL,

  approved_by uuid,
  approved_by_name varchar(200) NOT NULL DEFAULT '',
  approved_at timestamptz,
  decision_note varchar(600) NOT NULL DEFAULT '',

  executed_by uuid,
  executed_at timestamptz,
  -- 执行时落的那张导出凭据。
  export_batch_id uuid,

  -- 批准后多久之内必须执行（超时作废）。
  expires_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, request_no),
  CHECK(updated_at >= created_at),
  -- 🔴 **审批人 ≠ 申请人**（DB 层钉死）。
  CHECK(approved_by IS NULL OR approved_by <> requested_by),
  -- 🔴 PENDING ⇒ 还没有审批人；已审批 ⇒ 审批人与审批时刻都在。
  CHECK(status <> 'PENDING' OR approved_by IS NULL),
  CHECK(status = 'PENDING' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  -- 🔴 EXECUTED ⇒ 执行人、执行时刻、执行凭据三者齐全。
  CHECK(status <> 'EXECUTED'
        OR (executed_by IS NOT NULL AND executed_at IS NOT NULL AND export_batch_id IS NOT NULL)),
  -- 🔴 申请条数不得超过申请时的上限快照（**「限量」是硬闸，不是建议**）。
  CHECK(record_count <= max_records_limit),
  -- 🔴 被拒 ⇒ 审批人、审批时刻、驳回理由都在（被拒也要能追责）。
  CHECK(status <> 'REJECTED' OR btrim(decision_note) <> '')
);
COMMENT ON TABLE pii_access_export_request IS
  '16.6.1.1 · PII 导出申请与审批（申请 → 审批 → 执行）。**审批人 ≠ 申请人由 DB CHECK 钉死**；'
  '**未审批不许执行**；申请条数不得超过申请时记录的上限快照；导出理由必填。';

CREATE INDEX pii_access_export_request_by_status ON pii_access_export_request(tenant_id, status, requested_at DESC);
CREATE INDEX pii_access_export_request_by_requester ON pii_access_export_request(tenant_id, requested_by, requested_at DESC);
CREATE INDEX pii_access_export_request_by_time ON pii_access_export_request(tenant_id, requested_at DESC);

-- ===========================================================================
-- 五、pii_access_export_item · 导出申请的主体清单（append-only）
-- ===========================================================================
-- 🔴 导出的「导什么」必须**逐条列明**，不能是「导出所有 CUSTOMER」。
--   所以申请时就把主体清单钉下来（**不可增删**），执行时按清单逐条取 PII。
--   这也是「不要提供一键全量导出形状」的物理保证：**清单必须由人一条条挑出来**。
--
-- 🔴 这里存的是**主体标识**（order id / buyer_ref）—— 标识不是 PII。
--   subject_label 存**脱敏标签**。
--
CREATE TABLE pii_access_export_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  export_request_id uuid NOT NULL,

  subject_kind varchar(16) NOT NULL CHECK(subject_kind IN ('CUSTOMER','ORDER')),
  subject_ref varchar(160) NOT NULL CHECK(btrim(subject_ref) <> ''),
  subject_label varchar(200) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一张申请里同一个主体只能出现一次（否则导出条数会被重复项撑大）。
  UNIQUE(tenant_id, export_request_id, subject_ref)
);
COMMENT ON TABLE pii_access_export_item IS
  '16.6.1.1 · 导出申请的主体清单（append-only）。**导出对象必须逐条列明** —— '
  '这是「不要提供一键全量导出形状」的物理保证。只存主体标识与脱敏标签，不存 PII 明文。';

CREATE INDEX pii_access_export_item_by_request ON pii_access_export_item(tenant_id, export_request_id);

-- ===========================================================================
-- 六、pii_access_export_batch · 导出执行凭据（append-only）
-- ===========================================================================
-- 🔴 一次导出执行留一张凭据：**谁、何时、导了多少条、哪些字段、依据哪张申请**。
--   subject_digest 是主体清单的 md5 —— 事后能证明「导出了哪一批」，
--   而**不把 PII 明文复制一份到本服务**。
--
CREATE TABLE pii_access_export_batch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  export_request_id uuid NOT NULL,
  export_no varchar(32) NOT NULL CHECK(btrim(export_no) <> ''),
  record_count int NOT NULL CHECK(record_count > 0),
  field_code varchar(16) NOT NULL CHECK(field_code IN ('NAME','PHONE','EMAIL','ADDRESS')),
  -- 主体清单的 md5（**不是 PII 明文，也不是明文的可逆编码**）。
  subject_digest char(32) NOT NULL CHECK(subject_digest ~ '^[0-9a-f]{32}$'),

  exported_by uuid NOT NULL,
  exported_by_name varchar(200) NOT NULL DEFAULT '',
  exported_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, export_no),
  -- 一张申请只执行一次。
  UNIQUE(tenant_id, export_request_id)
);
COMMENT ON TABLE pii_access_export_batch IS
  '16.6.1.1 · 一次导出执行的凭据（append-only）。记录谁、何时、导了多少条、哪些字段、'
  '依据哪张申请；subject_digest 是主体清单的 md5 —— **不复制 PII 明文到本服务**。';

CREATE INDEX pii_access_export_batch_by_request ON pii_access_export_batch(tenant_id, export_request_id);
CREATE INDEX pii_access_export_batch_by_time ON pii_access_export_batch(tenant_id, exported_at DESC);

-- ===========================================================================
-- 七、append-only 触发器（审计与导出凭据是凭据，事后改写不了）
-- ===========================================================================
CREATE FUNCTION guard_pii_access_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是 PII 访问凭据：不可修改也不可删除', TG_TABLE_NAME
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER pii_access_view_audit_immutable
  BEFORE UPDATE OR DELETE ON pii_access_view_audit
  FOR EACH ROW EXECUTE FUNCTION guard_pii_access_immutable();
CREATE TRIGGER pii_access_export_item_immutable
  BEFORE UPDATE OR DELETE ON pii_access_export_item
  FOR EACH ROW EXECUTE FUNCTION guard_pii_access_immutable();
CREATE TRIGGER pii_access_export_batch_immutable
  BEFORE UPDATE OR DELETE ON pii_access_export_batch
  FOR EACH ROW EXECUTE FUNCTION guard_pii_access_immutable();

-- ===========================================================================
-- 八、引用完整性（不建外键，改用触发器 —— 见文件头第三节）
-- ===========================================================================
-- 审计引用的授权必须真实存在且同租户（否则「依据哪条授权」会指向一个不存在的授权）。
CREATE FUNCTION guard_pii_access_audit_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.grant_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM pii_access_grant g
       WHERE g.tenant_id = NEW.tenant_id AND g.id = NEW.grant_id) THEN
    RAISE EXCEPTION '授权配置 % 不存在或不属于当前租户', NEW.grant_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.export_request_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM pii_access_export_request r
       WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.export_request_id) THEN
    RAISE EXCEPTION '导出申请 % 不存在或不属于当前租户', NEW.export_request_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pii_access_view_audit_reference
  BEFORE INSERT ON pii_access_view_audit
  FOR EACH ROW EXECUTE FUNCTION guard_pii_access_audit_reference();

-- 导出清单必须挂在真实存在、同租户、且还没被执行的申请上。
CREATE FUNCTION guard_pii_access_export_item_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pii_access_export_request r
                 WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.export_request_id) THEN
    RAISE EXCEPTION '导出申请 % 不存在或不属于当前租户', NEW.export_request_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pii_access_export_item_reference
  BEFORE INSERT ON pii_access_export_item
  FOR EACH ROW EXECUTE FUNCTION guard_pii_access_export_item_reference();

-- 导出凭据必须挂在真实存在、同租户的申请上。
CREATE FUNCTION guard_pii_access_export_batch_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pii_access_export_request r
                 WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.export_request_id) THEN
    RAISE EXCEPTION '导出申请 % 不存在或不属于当前租户', NEW.export_request_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pii_access_export_batch_reference
  BEFORE INSERT ON pii_access_export_batch
  FOR EACH ROW EXECUTE FUNCTION guard_pii_access_export_batch_reference();

-- ---------------------------------------------------------------------------
-- 「清单条数 = 申请条数」与「申请条数 ≤ 上限快照」由**延迟约束触发器**钉死
-- ---------------------------------------------------------------------------
-- 🔴 为什么必须是延迟约束（DEFERRABLE INITIALLY DEFERRED）：
--   item 行是在申请行**之后**逐条插入的，插入当下还看不到全部兄弟行，
--   逐条校验必然误判 ⇒ 推迟到提交那一刻统一核对。
--
-- 🔴 这条不变量是「导出要限量」的**物理保证**：
--   申请写了 10 条，清单里就必须正好 10 条 —— 少一条说明清单没挑全，
--   多一条说明有人在申请之外偷偷加了东西。两种都不许悄悄发生。
--
CREATE FUNCTION guard_pii_access_export_item_count() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_request_id uuid;
  v_expected int;
  v_limit int;
  v_actual int;
BEGIN
  -- 🔴 这个触发器挂在**两张表**上（申请行 + 清单行），两张表的主键列名不一样：
  --   申请行是 id，清单行是 export_request_id。用错列名会让 PL/pgSQL 在提交那一刻
  --   直接抛 "record NEW has no field ..."，把整个事务炸掉 —— 而那正是导出申请
  --   最常走的路径。
  IF TG_TABLE_NAME = 'pii_access_export_request' THEN
    v_request_id := NEW.id;
  ELSE
    v_request_id := NEW.export_request_id;
  END IF;

  SELECT r.record_count, r.max_records_limit INTO v_expected, v_limit
    FROM pii_access_export_request r
   WHERE r.tenant_id = NEW.tenant_id AND r.id = v_request_id;
  IF v_expected IS NULL THEN
    RETURN NULL;
  END IF;
  SELECT count(*) INTO v_actual FROM pii_access_export_item
   WHERE tenant_id = NEW.tenant_id AND export_request_id = v_request_id;
  IF v_actual > v_limit THEN
    RAISE EXCEPTION '导出清单条数（%）超过申请时的上限快照（%）', v_actual, v_limit
      USING ERRCODE='23514';
  END IF;
  IF v_actual <> v_expected THEN
    RAISE EXCEPTION '导出清单条数（%）与申请条数（%）不一致', v_actual, v_expected
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
-- 🔴 触发器挂在**申请行**上而不是只挂清单行：只挂清单行时，
--   「一条清单都没插」就不会有任何触发器跑起来 ⇒ 申请写了 10 条、清单空了这种
--   最坏情况反而没人拦。挂在申请行上，提交那一刻必跑一次。
CREATE CONSTRAINT TRIGGER pii_access_export_item_count
  AFTER INSERT ON pii_access_export_request
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_pii_access_export_item_count();
CREATE CONSTRAINT TRIGGER pii_access_export_item_count_on_item
  AFTER INSERT ON pii_access_export_item
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_pii_access_export_item_count();

-- ===========================================================================
-- 九、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['pii_access_policy','pii_access_grant','pii_access_view_audit',
                              'pii_access_export_request','pii_access_export_item',
                              'pii_access_export_batch'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
-- 🔴 **刻意不建 data_scope（RESTRICTIVE）策略**：买家 / 订单是**租户级主体**，
--   不是某家店 / 某个部门的资产（与 V316 的 buyer_risk_* 同一处理）：
--   成员范围（ALL / DEPARTMENT / SELF）由 `pii_access_grant.scope_mode` 在**授权判定**里管。

-- ===========================================================================
-- 十、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pii_access_policy TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pii_access_grant TO channel_app;
-- 🔴 **审计表刻意只给 SELECT,INSERT** —— 访问留痕不可改、不可删。
GRANT SELECT,INSERT ON pii_access_view_audit TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pii_access_export_request TO channel_app;
-- 🔴 导出清单与导出凭据同样只给 SELECT,INSERT（它们是「导出了什么」的凭据）。
GRANT SELECT,INSERT ON pii_access_export_item TO channel_app;
GRANT SELECT,INSERT ON pii_access_export_batch TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V316 同口径）。
REVOKE ALL ON pii_access_policy FROM starcon_app;
REVOKE ALL ON pii_access_grant FROM starcon_app;
REVOKE ALL ON pii_access_view_audit FROM starcon_app;
REVOKE ALL ON pii_access_export_request FROM starcon_app;
REVOKE ALL ON pii_access_export_item FROM starcon_app;
REVOKE ALL ON pii_access_export_batch FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;
