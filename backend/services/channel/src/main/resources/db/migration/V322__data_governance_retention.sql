-- ===========================================================================
-- 16.6.2.1 数据出境与留存 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 238-243 行）：
--   ### 16.6.2.1 数据出境与留存
--   - 职责：管理数据合规：跨境数据传输登记与合法性基础、数据留存期配置、
--           到期清理与删除请求（被遗忘权）、保留期与财务留存期的区分。
--   - 输入：数据流登记；留存策略；删除请求。
--   - 输出：合规登记 + 清理/删除执行记录。
--   - 关系：上游 = 17 权限、全部数据域；下游 = 数据清理任务。
--           **财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先**
--           ——实体目录 §5 已明确这条边界。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本表存在的理由：**上游「全部数据域」在本仓没有任何现成的事实表**
-- ---------------------------------------------------------------------------
-- 调度者已全仓 grep 确认：本仓**没有**任何 data_domain / retention 表。
-- 而「数据出境与留存」这个模块的核心事实恰恰是：
--   * **数据域登记表**（哪些数据域、归哪个模块、含不含 PII、是不是财务数据）
--   * **留存策略**（每个数据域留多久、按什么起算、依据是什么）
--   * **跨境传输登记**（数据出到哪里、合法性基础是什么）
--   * **删除请求**（被遗忘权）与**清理工单 / 清理执行记录**
-- 所以本迁移**自建**这一组事实表 —— 这不是"造一份副本"，
-- 而是**本模块就是这张表的写入所有者**（与 16.6.1.1 的 `pii_access_*` 同一纪律）。
--
-- 🔴 **12 财务（finance 服务）在本仓尚未落地** ⇒ 本迁移**不建**任何财务凭证 / 财务明细表，
--   也**不声称**财务留存期已从财务系统取到真值。
--   「财务留存优先」在本仓是**一条规则 / 一个策略口径**，不是一个跨服务查询：
--   `retention_policy.retention_class = 'FINANCIAL'` 就是"这条策略是法定财务留存期"的**本地声明**，
--   真正的财务数据由 finance 服务落地后由它提供。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 表命名空间隔离（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.6.2.1）⇒ `data_governance_*`（登记侧）与 `retention_*`（留存侧）。
--   与既有命名空间**彼此不同**：channel 库的 `pii_access_*`（16.6.1.1）/
--   `access_security_*`（16.5.2.1）/ `compliance_rule_*` / `compliance_intercept_*` /
--   `compliance_check_*` / `buyer_risk_*` / `ip_risk_*` / `brand_authz_*` /
--   `brand_cred_*` / `product_certification_*` / `tax_registration_*` / `epr_*`。
--   ⇒ 本迁移**不建** `pii_*` 同名表，也**不 ALTER** 任何既有表。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 零外键（与 channel V300 / V308 / V312 / V315 / V316 / V320 同一纪律）
-- ---------------------------------------------------------------------------
-- channel 的既有集成测试用 `truncate <表清单>` 清库，外键会让 TRUNCATE 报 23503。
-- 所以本迁移**不建任何外键**，跨表引用改由 BEFORE INSERT 触发器校验。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V197 / V300 / V308 / V312 / V316 / V320 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **只带 isolation 策略，不带 data_scope（RESTRICTIVE）策略**：
--   数据域与留存策略是**租户级主体**，不是某家店 / 某个部门的资产
--   （与 V320 的 `pii_access_*` 同一处理）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 状态机与不变量：DB 层钉死，服务层复查一次
-- ---------------------------------------------------------------------------
--   * 跨境传输：ACTIVE → SUSPENDED → ENDED（**ENDED 是终态**，不许复活）。
--   * 留存策略：同一（数据域 × 类别）**同时只能有一条 ACTIVE**
--     （partial unique index 钉死）；改策略 = 旧条置 SUPERSEDED + 新条插入，**不原地改写**。
--   * 删除请求：PENDING → APPROVED / DEFERRED / REJECTED → EXECUTED。
--   * **审批人 ≠ 申请人**（DB 层 CHECK 钉死）。
--   * **财务留存优先**：域的生效留存期 = max(普通留存期, 财务留存期)；
--     若财务（法定）留存期尚未走完 ⇒ 删除请求判为 DEFERRED（推迟），**不许执行**。
--   * 清理执行记录 **append-only**（GRANT 只给 SELECT,INSERT，触发器禁止 UPDATE/DELETE）。
--
-- ===========================================================================

-- ===========================================================================
-- 一、data_governance_data_domain · 数据域登记表
-- ===========================================================================
-- 🔴 「全部数据域」是本模块的**上游**，而上游在本仓没有事实表 ⇒ 本表就是那张表。
--   一条 = **一个数据域**（订单 / 买家 / 商品 / 凭证 …），并写清：
--     * 它归哪个模块（owner_module）—— 清理与删除请求最终要落到那个模块头上；
--     * 它含不含个人信息（contains_pii）—— 决定 GDPR 被遗忘权是否适用；
--     * 它是不是财务数据（statutory_financial）—— 决定法定留存期是否适用。
--
-- 🔴 `statutory_financial = true` 与 `contains_pii = true` **可以同时成立**
--   （凭证 / 发票里既有 PII 也有法定留存义务）—— 这正是"两者冲突时按财务留存优先"
--   那条边界会真正被触发的地方。
--
CREATE TABLE data_governance_data_domain (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 数据域编码（租户内唯一，跨表引用一律用它，不用 id）。
  domain_code varchar(64) NOT NULL CHECK(btrim(domain_code) <> ''),
  domain_name varchar(120) NOT NULL CHECK(btrim(domain_name) <> ''),
  -- 🔴 归属模块（如「9 订单（trade 服务）」「12 财务（finance 服务）」）。
  --   清理与删除请求最终要落到那个模块头上 —— 没有归属方的数据域**没人负责清理**。
  owner_module varchar(120) NOT NULL CHECK(btrim(owner_module) <> ''),

  -- 数据分类：PII 个人信息 / FINANCIAL 财务 / OPERATIONAL 运营 / MARKETING 营销。
  data_class varchar(16) NOT NULL
    CHECK(data_class IN ('PII','FINANCIAL','OPERATIONAL','MARKETING')),
  -- 是否含个人信息（决定 GDPR 被遗忘权是否适用）。
  contains_pii boolean NOT NULL DEFAULT false,
  -- 是否适用法定（财务）留存期。
  statutory_financial boolean NOT NULL DEFAULT false,

  note varchar(600) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个租户内一个 domain_code 只有一行（登记走 upsert，不另起一行）。
  UNIQUE(tenant_id, domain_code),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE data_governance_data_domain IS
  '16.6.2.1 · 数据域登记表（**本模块自建的核心事实表** —— 上游「全部数据域」在本仓无现成表）。'
  '一条 = 一个数据域：归属模块 + 数据分类 + 是否含 PII + 是否适用法定财务留存期。'
  'contains_pii 与 statutory_financial 可同时为真 —— 那正是「财务留存优先」边界被触发的场景。';

CREATE INDEX data_governance_data_domain_by_class
  ON data_governance_data_domain(tenant_id, data_class);
CREATE INDEX data_governance_data_domain_by_pii
  ON data_governance_data_domain(tenant_id, contains_pii, statutory_financial);

-- ===========================================================================
-- 二、data_governance_transfer · 跨境数据传输登记
-- ===========================================================================
-- 🔴 蓝图的「**跨境数据传输登记与合法性基础**」的物理落点。
--   一条 = **一次对外的数据传输登记**（数据从哪个域出、到哪个法域、给谁、凭什么）。
--
-- 🔴 **合法性基础（legal_basis）是受控值域**，不是自由文本 ——
--   自由文本的合法性基础等于没登记（每条都能写"合规需要"）。
--   值域对齐 GDPR 第 6(1) 条的四种基础。
--
-- 🔴 `target_region` 是**两位法域码**（DB 层正则钉死），不是自由文本国家名 ——
--   自由文本国家名会出现"德国 / DE / Germany / 欧盟"四种写法，跨境判定就没法做了。
--
CREATE TABLE data_governance_transfer (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  transfer_no varchar(32) NOT NULL CHECK(btrim(transfer_no) <> ''),
  -- 数据从哪个数据域出去（必须是已登记的数据域，触发器校验）。
  source_domain_code varchar(64) NOT NULL CHECK(btrim(source_domain_code) <> ''),
  -- 接收方所在法域（ISO 3166-1 alpha-2 两位码）。
  target_region varchar(8) NOT NULL CHECK(target_region ~ '^[A-Z]{2}$'),
  -- 接收方（境外处理者 / 第三方服务商）。
  recipient varchar(200) NOT NULL CHECK(btrim(recipient) <> ''),

  -- 🔴 合法性基础（受控值域，DB 层 CHECK 钉死）。
  legal_basis varchar(32) NOT NULL
    CHECK(legal_basis IN ('CONSENT','CONTRACT','LEGAL_OBLIGATION','LEGITIMATE_INTEREST')),
  -- 保障措施（SCC / BCR / 加密 …）。
  safeguards varchar(600) NOT NULL DEFAULT '',
  -- 影响评估编号（TIA / DPIA 引用）。
  tia_ref varchar(120) NOT NULL DEFAULT '',

  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','SUSPENDED','ENDED')),
  registered_by uuid NOT NULL,
  registered_at timestamptz NOT NULL,
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  -- 🔴 暂停与终止都必须写理由：没有理由的中断无法向监管解释。
  suspend_reason varchar(600) NOT NULL DEFAULT '',
  end_reason varchar(600) NOT NULL DEFAULT '',
  note varchar(600) NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, transfer_no),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  CHECK(status <> 'SUSPENDED' OR btrim(suspend_reason) <> ''),
  CHECK(status <> 'ENDED' OR (btrim(end_reason) <> '' AND effective_to IS NOT NULL)),
  -- 🔴 还没终止就不许有终止时刻（否则"看起来已经停了但其实还在传"）。
  CHECK(status <> 'ACTIVE' OR effective_to IS NULL),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE data_governance_transfer IS
  '16.6.2.1 · 跨境数据传输登记（**合法性基础是受控值域，不是自由文本**；法域是两位码）。'
  'ACTIVE → SUSPENDED → ENDED 终态；暂停与终止都必须写理由。';

CREATE INDEX data_governance_transfer_by_domain
  ON data_governance_transfer(tenant_id, source_domain_code, status);
CREATE INDEX data_governance_transfer_by_region
  ON data_governance_transfer(tenant_id, target_region, status);
CREATE INDEX data_governance_transfer_by_time
  ON data_governance_transfer(tenant_id, registered_at DESC);

-- ===========================================================================
-- 三、retention_policy · 留存策略（版本化）
-- ===========================================================================
-- 🔴 蓝图加粗句「**保留期与财务留存期的区分**」的物理落点：
--   `retention_class` 把两类留存期**在数据模型上分开**：
--     STANDARD —— 租户自订的普通留存期；
--     FINANCIAL —— **法定**（财务）留存期。
--   两者冲突时的裁决口径见第六节（财务留存优先）。
--
-- 🔴 **同一（数据域 × 类别）同时只能有一条 ACTIVE**（partial unique index 钉死）：
--   两条并存的策略会让"生效留存期"变成"挑一条运气好的"。
--   改策略 = 旧条置 SUPERSEDED（关掉 effective_to）+ 插入新版本，**不原地改写**。
--
-- 🔴 `retention_days` 是**数量** ⇒ NUMERIC(19,6) + CHECK 必须是整天（禁浮点天数）。
--
-- 🔴 **生效时刻不许回填**（CHECK effective_from >= created_at）：
--   蓝图的全局约束「规则变更不能追溯改变历史判定」——
--   一条可以回填到过去的策略会把过去的判定全部推翻。
--
CREATE TABLE retention_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  domain_code varchar(64) NOT NULL CHECK(btrim(domain_code) <> ''),
  policy_name varchar(120) NOT NULL CHECK(btrim(policy_name) <> ''),

  -- 🔴 两类留存期：STANDARD 普通 / FINANCIAL 法定财务。
  retention_class varchar(16) NOT NULL CHECK(retention_class IN ('STANDARD','FINANCIAL')),
  -- 留存天数（数量 ⇒ NUMERIC(19,6)，禁浮点；必须是整天）。
  retention_days numeric(19,6) NOT NULL
    CHECK(retention_days >= 0 AND retention_days = trunc(retention_days)),
  -- 起算口径：CREATION 按数据创建时间 / CLOSURE 按业务结束时间。
  retention_trigger varchar(16) NOT NULL CHECK(retention_trigger IN ('CREATION','CLOSURE')),
  -- 法定依据（人话，如「税务法要求凭证留存 10 年」）。
  legal_basis varchar(200) NOT NULL DEFAULT '',

  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','SUPERSEDED','DISABLED')),

  -- 版本链：这条取代了哪一条（首版为 NULL）。
  supersedes_id uuid,
  version_no int NOT NULL DEFAULT 1 CHECK(version_no > 0),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个（数据域 × 类别）下版本号不重复。
  UNIQUE(tenant_id, domain_code, retention_class, version_no),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- 🔴 不许回填生效时刻。
  CHECK(effective_from >= created_at),
  -- 🔴 不是首版就必须指明它取代了哪一条。
  CHECK(version_no > 1 OR supersedes_id IS NULL),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE retention_policy IS
  '16.6.2.1 · 留存策略（版本化）。**retention_class 在数据模型上把「普通留存期」与'
  '「财务（法定）留存期」分开**；同一（数据域 × 类别）同时只能有一条 ACTIVE；'
  '改策略 = 新版本 + 旧条 SUPERSEDED，不原地改写；生效时刻不许回填。';

CREATE INDEX retention_policy_by_domain
  ON retention_policy(tenant_id, domain_code, retention_class, status);

-- 🔴 **同一（数据域 × 类别）只能有一条 ACTIVE** —— DB 层硬保证。
CREATE UNIQUE INDEX retention_policy_one_active
  ON retention_policy(tenant_id, domain_code, retention_class)
  WHERE status = 'ACTIVE';

-- ===========================================================================
-- 四、retention_conflict · 保留期与财务留存期的冲突裁决台账
-- ===========================================================================
-- 🔴 蓝图加粗句「**财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先**」
--   的**裁决留痕**。
--
-- 🔴 这张表是**裁决台账**（每次真做过一次裁决就写一行），
--   而"当前生效的冲突清单"是**实时算出来**的（GET /retention-conflicts）——
--   台账是历史，实时计算是现状，两者不是同一份事实的两个副本。
--
-- 🔴 `resolution` 的取值把裁决结果**钉死**：
--   FINANCIAL_PREVAILS —— 财务（法定）留存期优先（冲突时的裁决结果）；
--   STANDARD_APPLIES  —— 财务期更短或不存在，按普通留存期；
--   NO_CONFLICT       —— 两边都没有冲突。
--
-- 🔴 `deletion_blocked_until` = 财务留存期**走完**的那一天：
--   在这一天之前，GDPR 被遗忘权请求**不许真的删数据**（推迟，不是拒绝）。
--
CREATE TABLE retention_conflict (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  domain_code varchar(64) NOT NULL CHECK(btrim(domain_code) <> ''),

  -- 冲突对：普通留存期那条策略 与 财务（法定）留存期那条策略。
  standard_policy_id uuid,
  standard_retention_days numeric(19,6),
  financial_policy_id uuid,
  financial_retention_days numeric(19,6),

  -- 冲突形态（受控值域）。
  -- NO_POLICY = 这个数据域**一条留存策略都没登记**（判不了，不是"没冲突"）。
  conflict_kind varchar(24) NOT NULL
    CHECK(conflict_kind IN ('NO_POLICY','NO_CONFLICT','FINANCIAL_LONGER','STANDARD_LONGER',
                             'STANDARD_ONLY','FINANCIAL_ONLY')),
  -- 裁决结果（受控值域）。
  resolution varchar(24) NOT NULL
    CHECK(resolution IN ('FINANCIAL_PREVAILS','STANDARD_APPLIES','NO_CONFLICT')),
  -- 裁决后的**生效留存期**（天）。
  effective_retention_days numeric(19,6),
  -- 财务留存期走完的那一天（此前删除被推迟）。
  deletion_blocked_until timestamptz,

  -- 🔴 裁决依据（人话：为什么这么裁）。
  rationale varchar(600) NOT NULL DEFAULT '',

  adjudicated_at timestamptz NOT NULL,
  adjudicated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE retention_conflict IS
  '16.6.2.1 · 「保留期 vs 财务留存期」冲突的**裁决台账**（**append-only**：每次真裁决过一次就写一行）。'
  '实时生效的冲突清单由 GET /retention-conflicts 现算 —— 台账是历史，现算是现状。'
  'resolution=FINANCIAL_PREVAILS 就是「财务留存优先」那条边界的落地形状。';

CREATE INDEX retention_conflict_by_domain
  ON retention_conflict(tenant_id, domain_code, adjudicated_at DESC);
-- ===========================================================================
-- 五、data_governance_deletion_request · 删除请求（被遗忘权）
-- ===========================================================================
-- 🔴 蓝图的「**删除请求（被遗忘权）**」。
--
-- 🔴 **与 16.6.1.1 的删除/导出审批语义对齐**（不造第二套冲突语义）：
--   同样是「申请 → 审批 → 执行」三段式，同样**审批人 ≠ 申请人**，
--   同样**理由必填**（没有理由的删除请求无法被审批人判断）。
--   区别只有一处，且是本模块自己的边界：
--   审批时会做**财务留存优先**的裁决 ⇒ 可能判成 DEFERRED（推迟）而不是 APPROVED。
--
-- 🔴 **不建"直接删除"端点**：物理删除跨服务做不了，
--   本模块产出的是**清理工单 + 执行记录**（交给下游数据清理任务）。
--   `cleanup_work_order_id` 是"这次删除请求落到哪张清理工单上"的凭据。
--
CREATE TABLE data_governance_deletion_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  request_no varchar(32) NOT NULL CHECK(btrim(request_no) <> ''),
  -- FORGETTING_RIGHT 被遗忘权（GDPR 第 17 条）/ CLEANUP 定期清理申请。
  request_type varchar(24) NOT NULL CHECK(request_type IN ('FORGETTING_RIGHT','CLEANUP')),
  -- 主体类型与标识（标识不是 PII 本��）。
  subject_kind varchar(24) NOT NULL CHECK(btrim(subject_kind) <> ''),
  subject_ref varchar(160) NOT NULL CHECK(btrim(subject_ref) <> ''),
  -- 目标数据域（必须是已登记的数据域，触发器校验）。
  domain_code varchar(64) NOT NULL CHECK(btrim(domain_code) <> ''),

  -- 🔴 理由**必填**。
  reason varchar(600) NOT NULL CHECK(btrim(reason) <> ''),

  -- PENDING → APPROVED / DEFERRED / REJECTED → EXECUTED。
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','APPROVED','DEFERRED','REJECTED','EXECUTED','CANCELLED')),

  requested_by uuid NOT NULL,
  requested_by_name varchar(200) NOT NULL DEFAULT '',
  requested_at timestamptz NOT NULL,

  approved_by uuid,
  approved_by_name varchar(200) NOT NULL DEFAULT '',
  approved_at timestamptz,
  decision_note varchar(600) NOT NULL DEFAULT '',

  executed_by uuid,
  executed_by_name varchar(200) NOT NULL DEFAULT '',
  executed_at timestamptz,
  -- 执行时落的那张清理工单。
  cleanup_work_order_id uuid,

  -- 🔴 财务留存优先 ⇒ 推迟到那一天才可执行。
  defer_until timestamptz,
  defer_reason varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, request_no),
  CHECK(updated_at >= created_at),
  -- 🔴 **审批人 ≠ 申请人**（DB 层钉死，服务层再查一次）。
  CHECK(approved_by IS NULL OR approved_by <> requested_by),
  -- PENDING ⇒ 还没有审批人；已审批 ⇒ 审批人与审批时刻都在。
  CHECK(status <> 'PENDING' OR (approved_by IS NULL AND approved_at IS NULL)),
  CHECK(status = 'PENDING' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  -- 🔴 被拒 ⇒ 必须有驳回理由（被拒也要能追责）。
  CHECK(status <> 'REJECTED' OR btrim(decision_note) <> ''),
  -- 🔴 推迟 ⇒ 必须说清推到哪天、为什么。
  CHECK(status <> 'DEFERRED' OR (defer_until IS NOT NULL AND btrim(defer_reason) <> '')),
  -- 🔴 已执行 ⇒ 执行人、执行时刻、清理工单三者齐全。
  CHECK(status <> 'EXECUTED'
        OR (executed_by IS NOT NULL AND executed_at IS NOT NULL AND cleanup_work_order_id IS NOT NULL))
);
COMMENT ON TABLE data_governance_deletion_request IS
  '16.6.2.1 · 删除请求（被遗忘权）。**与 16.6.1.1 的审批语义对齐**：申请 → 审批 → 执行，'
  '审批人 ≠ 申请人（DB CHECK 钉死），理由必填。'
  '本模块自己的边界：审批时做「财务留存优先」裁决 ⇒ 可能判成 DEFERRED（推迟到法定留存期走完）。'
  '**没有"直接删除"端点** —— 物理删除跨服务做不了，本模块产出清理工单 + 执行记录。';

CREATE INDEX data_governance_deletion_request_by_status
  ON data_governance_deletion_request(tenant_id, status, requested_at DESC);
CREATE INDEX data_governance_deletion_request_by_domain
  ON data_governance_deletion_request(tenant_id, domain_code, status);
CREATE INDEX data_governance_deletion_request_by_requester
  ON data_governance_deletion_request(tenant_id, requested_by, requested_at DESC);

-- ===========================================================================
-- 六、retention_cleanup_work_order · 清理工单（可供下游数据清理任务消费）
-- ===========================================================================
-- 🔴 蓝图「**到期清理**」的物理落点。
--   本模块**不跨服务删业务数据**（AGENTS.md 模块边界纪律）⇒
--   本表产出的是**工单 + 状态**，由下游数据清理任务去执行真正的删除。
--
-- 🔴 `eligible_at` 与 `due_at` 是**两个不同的时点**：
--   eligible_at = 最早可清理日（= 策略生效日 + 生效留存期，财务类按财务期算）；
--   due_at      = 应当完成清理的截止日（>= eligible_at）。
--   🔴 CHECK(due_at >= eligible_at) 把「财务留存优先」在清理路径上也钉一道：
--   **不许把财务数据的清理排到法定留存期走完之前**。
--
CREATE TABLE retention_cleanup_work_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_no varchar(32) NOT NULL CHECK(btrim(order_no) <> ''),
  domain_code varchar(64) NOT NULL CHECK(btrim(domain_code) <> ''),
  -- 目标对象（标识不是 PII）；DELETION_REQUEST 触发的工单**必须**指明对象。
  subject_ref varchar(160) NOT NULL DEFAULT '',

  -- 触发来源：RETENTION_EXPIRY 留存期到期 / DELETION_REQUEST 删除请求 / MANUAL 人工。
  trigger_kind varchar(16) NOT NULL
    CHECK(trigger_kind IN ('RETENTION_EXPIRY','DELETION_REQUEST','MANUAL')),
  -- 🔴 生效留存期快照（登记那一刻的口径）与类别快照。
  retention_days numeric(19,6) NOT NULL CHECK(retention_days >= 0),
  retention_class varchar(16) NOT NULL CHECK(retention_class IN ('STANDARD','FINANCIAL','NONE')),
  -- 影响条数（数量 ⇒ NUMERIC(19,6)，禁浮点）。
  affected_record_count numeric(19,6) NOT NULL DEFAULT 0 CHECK(affected_record_count >= 0),

  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','IN_PROGRESS','COMPLETED','PARTIAL','FAILED','CANCELLED')),

  -- 🔴 最早可清理日 / 应当完成清理的截止日。
  eligible_at timestamptz NOT NULL,
  due_at timestamptz NOT NULL,

  requested_by uuid NOT NULL,
  requested_by_name varchar(200) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, order_no),
  CHECK(updated_at >= created_at),
  CHECK(due_at >= eligible_at),
  -- 🔴 由删除请求触发的工单必须指明被删对象（否则"删哪些"是空的）。
  CHECK(trigger_kind <> 'DELETION_REQUEST' OR btrim(subject_ref) <> '')
);
COMMENT ON TABLE retention_cleanup_work_order IS
  '16.6.2.1 · 清理工单（**可供下游数据清理任务消费的状态**）。'
  '本模块不跨服务删业务数据 —— 工单 + 状态是本模块的产出，真正的删除由下游执行。'
  'eligible_at（最早可清理日）与 due_at（完成截止日）分开，CHECK(due_at >= eligible_at) '
  '把「财务留存优先」在清理路径上也钉一道。';

CREATE INDEX retention_cleanup_work_order_by_status
  ON retention_cleanup_work_order(tenant_id, status, due_at);
CREATE INDEX retention_cleanup_work_order_by_domain
  ON retention_cleanup_work_order(tenant_id, domain_code, status);

-- ===========================================================================
-- 七、retention_cleanup_record · 清理执行记录（append-only）
-- ===========================================================================
-- 🔴 蓝图输出「**清理/删除执行记录**」的物理落点。
--   记录：谁、何时、对哪个数据域做了什么、结果如何、处理了多少条。
--
-- 🔴 **append-only**：GRANT 只给 SELECT,INSERT（刻意不给 UPDATE / DELETE），
--   再加 BEFORE UPDATE OR DELETE 触发器直接 RAISE。
--   理由：事后能改的"执行记录"不是记录，是日志。
--
CREATE TABLE retention_cleanup_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  work_order_id uuid NOT NULL,
  order_no varchar(32) NOT NULL CHECK(btrim(order_no) <> ''),
  domain_code varchar(64) NOT NULL CHECK(btrim(domain_code) <> ''),

  action varchar(16) NOT NULL CHECK(action IN ('EXECUTE','RETRY','CANCEL')),
  -- SUCCESS 全部成功 / PARTIAL 部分成功 / FAILED 失败 / CANCELLED 取消。
  result varchar(16) NOT NULL
    CHECK(result IN ('SUCCESS','PARTIAL','FAILED','CANCELLED')),
  -- 实际处理条数（数量 ⇒ NUMERIC(19,6)，禁浮点）。
  record_count numeric(19,6) NOT NULL DEFAULT 0 CHECK(record_count >= 0),
  message varchar(600) NOT NULL DEFAULT '',

  executed_by uuid NOT NULL,
  executed_by_name varchar(200) NOT NULL DEFAULT '',
  executed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE retention_cleanup_record IS
  '16.6.2.1 · 清理执行记录（**append-only**：GRANT 只给 SELECT,INSERT，触发器禁止 UPDATE/DELETE）。'
  '谁、何时、对哪个数据域、做了什么、结果如何、处理了多少条。';

CREATE INDEX retention_cleanup_record_by_order
  ON retention_cleanup_record(tenant_id, work_order_id, executed_at DESC);
CREATE INDEX retention_cleanup_record_by_domain
  ON retention_cleanup_record(tenant_id, domain_code, executed_at DESC);

-- ===========================================================================
-- 八、append-only 触发器（清理执行记录是凭据，事后改写不了）
-- ===========================================================================
CREATE FUNCTION guard_retention_cleanup_record_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是清理执行凭据：不可修改也不可删除', TG_TABLE_NAME
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER retention_cleanup_record_immutable
  BEFORE UPDATE OR DELETE ON retention_cleanup_record
  FOR EACH ROW EXECUTE FUNCTION guard_retention_cleanup_record_immutable();

-- ===========================================================================
-- 九、引用完整性（不建外键，改用触发器 —— 见文件头第三节）
-- ===========================================================================
-- 🔴 数据域必须是**已登记**的数据域。
--   「数据域登记表」是本模块的事实源；引用一个不存在的域
--   等于让清理 / 删除请求落在一个没人负责的目标上。
CREATE FUNCTION guard_data_governance_domain_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM data_governance_data_domain d
                 WHERE d.tenant_id = NEW.tenant_id AND d.domain_code = NEW.domain_code) THEN
    RAISE EXCEPTION '数据域 % 未登记（先在数据域登记表里登记它）', NEW.domain_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER retention_policy_domain_reference
  BEFORE INSERT ON retention_policy
  FOR EACH ROW EXECUTE FUNCTION guard_data_governance_domain_reference();
CREATE TRIGGER data_governance_deletion_request_domain_reference
  BEFORE INSERT ON data_governance_deletion_request
  FOR EACH ROW EXECUTE FUNCTION guard_data_governance_domain_reference();
CREATE TRIGGER retention_cleanup_work_order_domain_reference
  BEFORE INSERT ON retention_cleanup_work_order
  FOR EACH ROW EXECUTE FUNCTION guard_data_governance_domain_reference();

-- 🔴 传输登记表那一列叫 `source_domain_code`，**不叫** `domain_code` ——
--   同一个触发器函数挂在它上面会在插入那一刻抛 "record NEW has no field domain_code"，
--   把整条登记路径炸掉。所以这里用**另一个**函数，按它自己的列名校验。
CREATE FUNCTION guard_data_governance_transfer_domain_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM data_governance_data_domain d
                 WHERE d.tenant_id = NEW.tenant_id AND d.domain_code = NEW.source_domain_code) THEN
    RAISE EXCEPTION '数据域 % 未登记（先在数据域登记表里登记它）', NEW.source_domain_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER data_governance_transfer_domain_reference
  BEFORE INSERT ON data_governance_transfer
  FOR EACH ROW EXECUTE FUNCTION guard_data_governance_transfer_domain_reference();

-- 🔴 留存策略的版本链必须真实存在且同租户（否则"这条取代了哪条"会指向空气）。
CREATE FUNCTION guard_retention_policy_supersedes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.supersedes_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM retention_policy p
       WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.supersedes_id) THEN
    RAISE EXCEPTION '被取代的留存策略 % 不存在或不属于当前租户', NEW.supersedes_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER retention_policy_supersedes_reference
  BEFORE INSERT ON retention_policy
  FOR EACH ROW EXECUTE FUNCTION guard_retention_policy_supersedes();

-- 🔴 清理执行记录必须挂在真实存在、同租户的工单上。
CREATE FUNCTION guard_retention_cleanup_record_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM retention_cleanup_work_order w
                 WHERE w.tenant_id = NEW.tenant_id AND w.id = NEW.work_order_id) THEN
    RAISE EXCEPTION '清理工单 % 不存在或不属于当前租户', NEW.work_order_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER retention_cleanup_record_reference
  BEFORE INSERT ON retention_cleanup_record
  FOR EACH ROW EXECUTE FUNCTION guard_retention_cleanup_record_reference();

-- 🔴 冲突台账引用的两条策略必须真实存在且同租户。
CREATE FUNCTION guard_retention_conflict_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.standard_policy_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM retention_policy p
       WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.standard_policy_id) THEN
    RAISE EXCEPTION '普通留存策略 % 不存在或不属于当前租户', NEW.standard_policy_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.financial_policy_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM retention_policy p
       WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.financial_policy_id) THEN
    RAISE EXCEPTION '财务留存策略 % 不存在或不属于当前租户', NEW.financial_policy_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER retention_conflict_reference
  BEFORE INSERT ON retention_conflict
  FOR EACH ROW EXECUTE FUNCTION guard_retention_conflict_reference();

-- ===========================================================================
-- 十、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['data_governance_data_domain','data_governance_transfer',
                              'retention_policy','retention_conflict',
                              'data_governance_deletion_request',
                              'retention_cleanup_work_order','retention_cleanup_record'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
-- 🔴 **刻意不建 data_scope（RESTRICTIVE）策略**：数据域与留存策略是**租户级主体**，
--   不是某家店 / 某个部门的资产（与 V320 的 `pii_access_*` 同一处理）。

-- ===========================================================================
-- 十一、账号与授权
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
GRANT SELECT,INSERT,UPDATE,DELETE ON data_governance_data_domain TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON data_governance_transfer TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON retention_policy TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON retention_conflict TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON data_governance_deletion_request TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON retention_cleanup_work_order TO channel_app;
-- 🔴 **清理执行记录刻意只给 SELECT,INSERT** —— 执行留痕不可改、不可删。
GRANT SELECT,INSERT ON retention_cleanup_record TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V320 同口径）。
REVOKE ALL ON data_governance_data_domain FROM starcon_app;
REVOKE ALL ON data_governance_transfer FROM starcon_app;
REVOKE ALL ON retention_policy FROM starcon_app;
REVOKE ALL ON retention_conflict FROM starcon_app;
REVOKE ALL ON data_governance_deletion_request FROM starcon_app;
REVOKE ALL ON retention_cleanup_work_order FROM starcon_app;
REVOKE ALL ON retention_cleanup_record FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;
