-- ===========================================================================
-- 16.2.1.2 拦截执行 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 119-124 行）：
--   ### 16.2.1.2 拦截执行
--   - 职责：在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
--           展示拦截原因与解除方式（提交资质、申请豁免）。
--   - 输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
--   - 输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
--   - 关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
--           **豁免必须有审批与理由**，否则阻断会被随意绕过。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 同库已交付、本迁移**真实读取**的上游：
--   * `compliance_rule` / `compliance_rule_version` / `compliance_rule_term` /
--     `compliance_rule_scope`（channel V311，16.2.1.1 规则库）—— **只读**。
--     本迁移**不写上游任何一张表**、**零 ALTER 任何既有表**。
--   * `shop` / `platform` / `marketplace`（channel V1）—— 店铺目录。
--
-- 🔴 **判定必须绑定「当时生效的那个 version」** —— 与 16.2.1.1 的
--   「规则版本化 ⇒ 历史判定可解释」同口径：判定凭据行里冻结
--   `rule_version_id`，规则后来改了也不追溯改变历史判定。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.2.1.2，合规域）⇒ `compliance_intercept_*`（本迁移）。
--   * 16.2.1.1 ⇒ `compliance_rule_*`（V311，既有）。
--   * 16.2.2.1 ⇒ `compliance_check_*`（V197，既有）。
--   * 4.2.3.2  ⇒ `listing_compliance_*`（V198，既有）。
--   * 16.2.2.2 ⇒ `product_certification_*`（V247，既有）。
--   * 16.4.2.1 ⇒ `brand_authz_*`（V248，既有）；16.4.2.2 ⇒ `brand_cred_*`（V255，既有）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴🔴 结论是**三态 + 一个「判不了」**
-- ---------------------------------------------------------------------------
-- 蓝图只点了「通过 / 警告 / 阻断」三个结论，但**「判不了」必须与「通过」分开**：
--   * 规则读不到、适用版本查不到、规则集为空 ⇒ **不能返回 PASS**
--     （返回 PASS 等于放行），必须返回 `UNDECIDABLE` 并给出原因。
--   * 因此结论值域是 **PASS / WARN / BLOCK / UNDECIDABLE** 四值，
--     `release_granted`（能不能放行）与 `blocked`（是不是被阻断）**分开记**：
--       BLOCK       ⇒ blocked=true,  release_granted=false
--       UNDECIDABLE ⇒ blocked=false, release_granted=false  ← 判不了 ≠ 放行
--       WARN / PASS ⇒ blocked=false, release_granted=true
--
-- ---------------------------------------------------------------------------
-- 三、🔴 受控值域一律由 **DB 层 CHECK 钉死**（值域要可审阅，不只靠应用层 enum）
-- ---------------------------------------------------------------------------
--   * 拦截点（场景）= LISTING_PUBLISH（刊登发布前）/ SHIPMENT（发货前）/
--     WITHDRAWAL（结算提现前）—— 蓝图点名的三个关键业务节点。
--   * 校验对象（主体）= PRODUCT（商品）/ ORDER（订单）/ SHOP（店铺）。
--   * 结论 = PASS / WARN / BLOCK / UNDECIDABLE。
--   * 原因码 = 具名枚举（见 `compliance_intercept_reason.reason_code`）。
--   * 豁免状态 = PENDING / APPROVED / REJECTED。
--
-- ---------------------------------------------------------------------------
-- 四、🔴🔴 豁免：审批 + 理由 + 有效期（蓝图加粗句的物理落法）
-- ---------------------------------------------------------------------------
-- 「**豁免必须有审批与理由**，否则阻断会被随意绕过」—— 落成五道闸：
--   1. **状态机**：PENDING → APPROVED / REJECTED；**终态不许回退**
--      （触发器 `guard_compliance_intercept_exemption_state` 钉死：
--      一旦离开 PENDING，状态与身份字段全部冻结）。
--   2. **申请人 ≠ 审批人**：DB 层 CHECK（`approver_id <> applicant_id`）
--      **与**服务层判定双重钉住。
--   3. **理由必填**：申请人理由（`applicant_reason`）与审批意见（`decision_note`）
--      都必须非空且非空白。
--   4. **必须有有效期**：`valid_from` / `valid_to` 都必填，且 `valid_to > valid_from`；
--      审批时 `valid_to` 必须**尚未过期**（不能批一张已经过期的豁免）。
--   5. **豁免只针对一条规则**：`rule_id` 必填 —— 一张「什么都豁免」的空白豁免
--      等于把阻断整个关掉，那不是豁免。
--
-- 🔴 **因豁免放行必须可审计**：判定行上 `waived=true` + `exemption_id`，
--   结论里逐条列出「因豁免放行 → 豁免单 id」——
--   阻断在审计上**不能无声消失**。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 判定凭据 append-only（一次判定一行，事后改写不了）
-- ---------------------------------------------------------------------------
-- `compliance_intercept_decision`（一次判定一行）与 `compliance_intercept_reason`
-- （一条原因一行）都**只追加**：UPDATE / DELETE 一律被触发器拒。
-- ⇒ 规则后来改了、发新版本了，**历史判定不被追溯改变**（版本快照冻在行里）。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 多租户隔离：tenant_id NOT NULL + ENABLE **与 FORCE** ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- 本模块四张表都是**租户级**的（判定凭据 / 豁免单 / 策略）⇒ isolation 即可，
-- 不套 RESTRICTIVE 的 data_scope（店铺维度的数据范围由 16.2.1.1 的
-- `compliance_rule_scope` 自己那一套决定，本模块**不另造一套**）。
--
-- ===========================================================================

-- ===========================================================================
-- 一、compliance_intercept_policy · 租户级的拦截策略（每个场景一行）
-- ===========================================================================
-- 🔴 为什么要有这张表：拦截**默认不生效**。
--   * 租户要先显式开启某个场景的拦截，刊登发布路径才会真的读服务端结论；
--   * 「判不了」时怎么办（BLOCK 失败关闭 / ALLOW 放行并记录）是租户自己的选择，
--     把它做成**数据**而不是写死在代码里。
-- 默认 `enabled=false`：没有显式开启的租户，刊登路径完全不受影响
-- （这与 4.2.3.2「未扫描过则不拦，避免把『还没扫过』当成『不合规』」同一纪律）。
CREATE TABLE compliance_intercept_policy (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 拦截点（受控值域，DB 层钉死）：刊登发布前 / 发货前 / 结算提现前。
  scene         varchar(32) NOT NULL
                CONSTRAINT compliance_intercept_policy_scene_chk CHECK (scene IN (
                  'LISTING_PUBLISH',   -- 刊登发布前
                  'SHIPMENT',          -- 发货前
                  'WITHDRAWAL')),     -- 结算提现前
  -- 🔴 该场景是否真的启用拦截（**默认不启用**：没有显式开启就没有拦截）。
  enabled       boolean NOT NULL DEFAULT false,
  -- 🔴 判不了（UNDECIDABLE）时怎么办：BLOCK = 失败关闭（默认）；
  --   ALLOW = 放行但**留下判定凭据**（审计上仍看得见「这次没判出来」）。
  undecidable_action varchar(8) NOT NULL DEFAULT 'BLOCK'
                CONSTRAINT compliance_intercept_policy_undecidable_chk CHECK (undecidable_action IN ('BLOCK','ALLOW')),
  note          varchar(500) NOT NULL DEFAULT '',
  created_by    uuid,
  updated_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一租户同一场景只有一条策略（幂等 upsert 的锚点）。
  UNIQUE(tenant_id, scene)
);
COMMENT ON TABLE compliance_intercept_policy IS
  '16.2.1.2 · 租户级拦截策略：每个拦截点一条。'
  'enabled=false（默认）⇒ 放行路径不读本模块；enabled=true ⇒ 放行路径必须读服务端结论。'
  'undecidable_action 决定「判不了」时失败关闭还是放行并留痕。';
CREATE INDEX compliance_intercept_policy_scene
  ON compliance_intercept_policy(tenant_id, scene);

-- ===========================================================================
-- 二、compliance_intercept_decision · 一次判定一行（append-only）
-- ===========================================================================
CREATE TABLE compliance_intercept_decision (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 拦截点（受控值域，DB 层钉死）。
  scene         varchar(32) NOT NULL
                CONSTRAINT compliance_intercept_decision_scene_chk CHECK (scene IN (
                  'LISTING_PUBLISH','SHIPMENT','WITHDRAWAL')),
  -- 🔴 校验对象（受控值域，DB 层钉死）：商品 / 订单 / 店铺。
  subject_type  varchar(16) NOT NULL
                CONSTRAINT compliance_intercept_decision_subject_type_chk CHECK (subject_type IN (
                  'PRODUCT','ORDER','SHOP')),
  -- 🔴 逻辑 id（**跨服务不建 FK**）：03 商品主数据 / 订单 / 店铺各自的逻辑 id。
  subject_id    varchar(128) NOT NULL,
  -- 🔴 判定所依据的店铺 / 平台 / 站点（**适用范围**靠它收敛；可空 = 未绑定店铺）。
  shop_id       uuid,
  platform_id   uuid,
  marketplace_id uuid,
  -- 🔴 结论（四值）：通过 / 警告 / 阻断 / **判不了**。
  --   「判不了」与「通过」分开 —— 返回 PASS 等于放行。
  conclusion    varchar(16) NOT NULL
                CONSTRAINT compliance_intercept_decision_conclusion_chk CHECK (conclusion IN (
                  'PASS','WARN','BLOCK','UNDECIDABLE')),
  -- 🔴 是不是被阻断（只有 BLOCK 为 true）。
  blocked       boolean NOT NULL,
  -- 🔴 能不能放行：**UNDECIDABLE 也是 false** —— 判不了不等于放行。
  release_granted boolean NOT NULL,
  -- 🔴 计数：参与判定的生效规则数 / 原因数 / 其中阻断数 / 因豁免放行的条数 /
  --   判不了的条数。全部从原因行聚合（服务层不许自己编）。
  rule_count       integer NOT NULL DEFAULT 0 CONSTRAINT compliance_intercept_decision_rule_count_chk CHECK (rule_count >= 0),
  reason_count     integer NOT NULL DEFAULT 0 CONSTRAINT compliance_intercept_decision_reason_count_chk CHECK (reason_count >= 0),
  blocking_reason_count integer NOT NULL DEFAULT 0 CONSTRAINT compliance_intercept_decision_blocking_count_chk CHECK (blocking_reason_count >= 0),
  waived_reason_count   integer NOT NULL DEFAULT 0 CONSTRAINT compliance_intercept_decision_waived_count_chk CHECK (waived_reason_count >= 0),
  undecidable_reason_count integer NOT NULL DEFAULT 0 CONSTRAINT compliance_intercept_decision_undecidable_count_chk CHECK (undecidable_reason_count >= 0),
  -- 🔴 因豁免放行的豁免单 id 列表（jsonb 数组，元素是 uuid 字符串）。
  --   「因豁免放行」必须**指向豁免单** —— 阻断不能在审计上无声消失。
  exemption_ids jsonb NOT NULL DEFAULT '[]'::jsonb
                CONSTRAINT compliance_intercept_decision_exemption_ids_chk CHECK (jsonb_typeof(exemption_ids)='array'),
  -- 🔴 判定时的被校验内容快照（标题 / 描述 / 卖点 / 要求状态）—— 事后可复算。
  subject_snapshot jsonb NOT NULL
                CONSTRAINT compliance_intercept_decision_snapshot_chk CHECK (jsonb_typeof(subject_snapshot)='object'),
  -- 🔴 判定时刻（**同源 DB 时钟**）。
  decided_at    timestamptz NOT NULL,
  decided_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE compliance_intercept_decision IS
  '16.2.1.2 · 一次判定一行，**只追加**（触发器钉死 UPDATE / DELETE）。'
  '结论四值：PASS / WARN / BLOCK / UNDECIDABLE —— 「判不了」与「通过」分开，'
  'release_granted 在 UNDECIDABLE 时也是 false（判不了 ≠ 放行）。'
  '规则后来改了也不追溯改变历史判定：版本快照冻在原因行里。';
CREATE INDEX compliance_intercept_decision_recent
  ON compliance_intercept_decision(tenant_id, scene, decided_at DESC, id);
CREATE INDEX compliance_intercept_decision_subject
  ON compliance_intercept_decision(tenant_id, subject_type, subject_id, decided_at DESC);
CREATE INDEX compliance_intercept_decision_conclusion
  ON compliance_intercept_decision(tenant_id, conclusion, decided_at DESC);

-- ===========================================================================
-- 三、compliance_intercept_exemption · 豁免申请与审批
-- ===========================================================================
CREATE TABLE compliance_intercept_exemption (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 豁免在哪个拦截点生效（受控值域）。
  scene         varchar(32) NOT NULL
                CONSTRAINT compliance_intercept_exemption_scene_chk CHECK (scene IN (
                  'LISTING_PUBLISH','SHIPMENT','WITHDRAWAL')),
  -- 🔴 豁免针对哪个主体（受控值域）+ 哪个逻辑 id。
  subject_type  varchar(16) NOT NULL
                CONSTRAINT compliance_intercept_exemption_subject_type_chk CHECK (subject_type IN (
                  'PRODUCT','ORDER','SHOP')),
  subject_id    varchar(128) NOT NULL,
  -- 🔴 豁免**只针对一条规则**（16.2.1.1 的规则 id，逻辑引用，不建 FK ——
  --   上游表由 16.2.1.1 拥有，本模块**不写**它）。
  --   一张「什么都豁免」的空白豁免等于把阻断整个关掉 —— 那不是豁免。
  rule_id       uuid NOT NULL,
  -- 🔴 豁免状态机：PENDING → APPROVED / REJECTED（终态不许回退）。
  status        varchar(16) NOT NULL DEFAULT 'PENDING'
                CONSTRAINT compliance_intercept_exemption_status_chk CHECK (status IN (
                  'PENDING','APPROVED','REJECTED')),
  -- 🔴 申请人。
  applicant_id  uuid NOT NULL,
  -- 🔴 申请人理由（**必填**，非空且非空白）。
  applicant_reason text NOT NULL
                CONSTRAINT compliance_intercept_exemption_applicant_reason_chk CHECK (btrim(applicant_reason) <> ''),
  -- 🔴 审批人（待审批时为 NULL）。
  approver_id   uuid,
  -- 🔴 审批意见（**审批后必填**，非空且非空白）。
  decision_note text NOT NULL DEFAULT '',
  -- 🔴 有效期（**必填**：豁免不能永久生效，过期即失效）。
  valid_from    timestamptz NOT NULL,
  valid_to      timestamptz NOT NULL,
  decided_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 有效期必须是一个真区间。
  CONSTRAINT compliance_intercept_exemption_window_chk CHECK (valid_to > valid_from),
  -- 🔴 待审批 ⇒ 没有审批人、没有审批时刻、没有审批意见。
  --   已审批 ⇒ 审批人、审批时刻、审批意见**都在**。
  CONSTRAINT compliance_intercept_exemption_shape_chk CHECK (
    (status = 'PENDING'
      AND approver_id IS NULL AND decided_at IS NULL AND btrim(decision_note) = '')
    OR
    (status IN ('APPROVED','REJECTED')
      AND approver_id IS NOT NULL AND decided_at IS NOT NULL AND btrim(decision_note) <> '')),
  -- 🔴🔴 **申请人 ≠ 审批人**（职责分离）：DB 层硬约束，不靠应用层自觉。
  CONSTRAINT compliance_intercept_exemption_separation_chk CHECK (
    approver_id IS NULL OR approver_id <> applicant_id)
);
COMMENT ON TABLE compliance_intercept_exemption IS
  '16.2.1.2 · 豁免申请与审批。PENDING → APPROVED / REJECTED，终态不可回退；'
  '申请人 ≠ 审批人（DB CHECK）；申请人理由与审批意见都必填；'
  '有效期必填（过期即失效）；一张豁免只针对一条规则。';
CREATE INDEX compliance_intercept_exemption_recent
  ON compliance_intercept_exemption(tenant_id, scene, status, created_at DESC, id);
CREATE INDEX compliance_intercept_exemption_subject
  ON compliance_intercept_exemption(tenant_id, subject_type, subject_id, rule_id);
CREATE INDEX compliance_intercept_exemption_rule
  ON compliance_intercept_exemption(tenant_id, rule_id, status);

-- ===========================================================================
-- 四、compliance_intercept_reason · 一条原因一行（append-only）
-- ===========================================================================
CREATE TABLE compliance_intercept_reason (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  decision_id   uuid NOT NULL,
  -- 🔴 关联规则（**逻辑引用**，不建 FK —— 上游表归 16.2.1.1 所有）。
  rule_id       uuid,
  -- 🔴 🔴 **当时生效的那个版本**（冻结在行里）：规则后来发新版本不追溯改历史判定。
  rule_version_id uuid,
  rule_code     varchar(64),
  rule_type     varchar(32),
  -- 🔴 严重度（取自那个生效版本）：阻断 / 警告 / 提示。
  severity      varchar(16)
                CONSTRAINT compliance_intercept_reason_severity_chk CHECK (
                  severity IS NULL OR severity IN ('BLOCKING','WARNING','INFO')),
  -- 🔴🔴 **原因码必须具名**（DB 层具名 CHECK 兜底）——
  --   匿名 CHECK 报错只说 check2，读报错的人根本不知道哪条口径被违反。
  reason_code   varchar(64) NOT NULL
                CONSTRAINT compliance_intercept_reason_code_chk CHECK (reason_code IN (
                  'WORD_TERM_HIT',            -- 命中词条（违禁词 / 极限词 / 侵权词）
                  'REQUIREMENT_NOT_SATISFIED', -- 要求未满足（认证 / 禁限售 / 标签 / 材质）
                  'RULE_SET_EMPTY',           -- 规则集为空（判不了）
                  'RULE_VERSION_UNRESOLVED',  -- 适用版本查不到（判不了）
                  'RULE_TERMS_MISSING',       -- 规则词库为空（判不了）
                  'REQUIREMENT_UNRESOLVED',    -- 要求状态缺失（判不了）
                  'RULE_SCOPE_UNRESOLVED',     -- 适用范围无法确定（判不了）
                  'RULE_READ_FAILED')),        -- 规则读不到（判不了）
  -- 🔴 人话（人能读的解释）。
  reason_text   text NOT NULL
                CONSTRAINT compliance_intercept_reason_text_chk CHECK (btrim(reason_text) <> ''),
  -- 🔴 命中的字段 / 命中的词（词条类原因才有）。
  field_key     varchar(64),
  matched_term  varchar(200),
  -- 🔴 是否因豁免放行（放行了也**必须留痕**，并指向豁免单）。
  waived        boolean NOT NULL DEFAULT false,
  exemption_id  uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CONSTRAINT compliance_intercept_reason_decision_fk
    FOREIGN KEY (tenant_id, decision_id) REFERENCES compliance_intercept_decision(tenant_id, id),
  CONSTRAINT compliance_intercept_reason_exemption_fk
    FOREIGN KEY (tenant_id, exemption_id) REFERENCES compliance_intercept_exemption(tenant_id, id),
  -- 🔴 「引擎级原因」（规则集为空 / 读不到）与「规则级原因」必须分清：
  --   引擎级原因**没有**关联规则，规则级原因**必须**有规则与当时生效的版本。
  CONSTRAINT compliance_intercept_reason_rule_ref_chk CHECK (
    (reason_code IN ('RULE_SET_EMPTY','RULE_READ_FAILED','RULE_SCOPE_UNRESOLVED')
       AND rule_id IS NULL AND rule_version_id IS NULL)
    OR
    (reason_code NOT IN ('RULE_SET_EMPTY','RULE_READ_FAILED','RULE_SCOPE_UNRESOLVED')
       AND rule_id IS NOT NULL AND rule_version_id IS NOT NULL)),
  -- 🔴 说「因豁免放行」就必须指向豁免单；不是豁免放行就不许挂豁免单。
  CONSTRAINT compliance_intercept_reason_waived_chk CHECK (
    (waived AND exemption_id IS NOT NULL) OR (NOT waived AND exemption_id IS NULL))
);
COMMENT ON TABLE compliance_intercept_reason IS
  '16.2.1.2 · 一条原因一行，**只追加**。原因码具名（DB 层具名 CHECK）；'
  'rule_version_id 冻结「当时生效的是哪一版」；'
  'waived=true 时必须指向豁免单 id —— 阻断不允许无声消失。';
CREATE INDEX compliance_intercept_reason_decision
  ON compliance_intercept_reason(tenant_id, decision_id);
CREATE INDEX compliance_intercept_reason_rule
  ON compliance_intercept_reason(tenant_id, rule_id);

-- ===========================================================================
-- 五、触发器
-- ===========================================================================

-- 🔴 (a) 判定凭据**只追加**：事后改写不了（规则变更不追溯改变历史判定）。
CREATE FUNCTION guard_compliance_intercept_decision_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    '合规判定凭据 % 不可修改或删除（判定凭据只追加：历史判定必须可解释）', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER compliance_intercept_decision_append_only
  BEFORE UPDATE OR DELETE ON compliance_intercept_decision
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_intercept_decision_append_only();

-- 🔴 (b) 原因行**只追加**。
CREATE FUNCTION guard_compliance_intercept_reason_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    '合规判定原因 % 不可修改或删除（判定凭据只追加）', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER compliance_intercept_reason_append_only
  BEFORE UPDATE OR DELETE ON compliance_intercept_reason
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_intercept_reason_append_only();

-- 🔴 (c) 豁免状态机：PENDING → APPROVED / REJECTED；**终态不可回退**，
--   且一旦判定，身份字段（场景 / 主体 / 规则 / 有效期）全部冻结。
CREATE FUNCTION guard_compliance_intercept_exemption_state() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'PENDING' THEN
    IF NEW.status <> 'PENDING' THEN
      -- 走的是 PENDING → APPROVED / REJECTED 这条唯一合法的边。
      IF NEW.status NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION
          '豁免单 % 的状态只能从 PENDING 走到 APPROVED / REJECTED（当前目标状态 %）', OLD.id, NEW.status
          USING ERRCODE='23514';
      END IF;
      IF NEW.approver_id IS NULL OR NEW.decided_at IS NULL OR btrim(NEW.decision_note) = '' THEN
        RAISE EXCEPTION
          '豁免单 % 审批必须同时给出审批人、审批时刻与审批意见', OLD.id
          USING ERRCODE='23514';
      END IF;
      IF NEW.approver_id = NEW.applicant_id THEN
        RAISE EXCEPTION
          '豁免单 % 的申请人不能审批自己（申请人 ≠ 审批人）', OLD.id
          USING ERRCODE='23514';
      END IF;
      IF NEW.valid_to <= now() THEN
        RAISE EXCEPTION
          '豁免单 % 的有效期已经过期，不能审批（豁免不能永久生效，也不能批一张过期的）', OLD.id
          USING ERRCODE='23514';
      END IF;
    END IF;
    RETURN NEW;
  END IF;

  -- 🔴 已判定（APPROVED / REJECTED）⇒ **终态**：状态不许回退，身份字段不许改。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    RAISE EXCEPTION
      '豁免单 % 已经是终态 %，不可回退或改判（终态不许回退）', OLD.id, OLD.status
      USING ERRCODE='23514';
  END IF;
  IF NEW.scene IS DISTINCT FROM OLD.scene
     OR NEW.subject_type IS DISTINCT FROM OLD.subject_type
     OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
     OR NEW.rule_id IS DISTINCT FROM OLD.rule_id
     OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
     OR NEW.valid_to IS DISTINCT FROM OLD.valid_to
     OR NEW.applicant_id IS DISTINCT FROM OLD.applicant_id
     OR NEW.applicant_reason IS DISTINCT FROM OLD.applicant_reason THEN
    RAISE EXCEPTION
      '豁免单 % 判定后身份字段不可改写（申请内容必须原样留存）', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_intercept_exemption_state
  BEFORE UPDATE ON compliance_intercept_exemption
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_intercept_exemption_state();

-- 🔴 (d) 豁免单**不可删除**：它是「当时凭什么放行」的证据。
CREATE FUNCTION guard_compliance_intercept_exemption_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    '豁免单 % 不可删除（它是「凭什么放行」的证据）', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER compliance_intercept_exemption_no_delete
  BEFORE DELETE ON compliance_intercept_exemption
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_intercept_exemption_no_delete();

-- 🔴 (e) 「不放行就必须有原因」：**延迟**约束触发器 ——
--   原因行是在判定行之后插的，所以这条检查必须等到事务提交时才算。
--   没有它，一个 BLOCK 判定可以零原因 ⇒ 运营看到「被拦了」却不知道为什么。
CREATE FUNCTION guard_compliance_intercept_decision_has_reason() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  reason_total int;
BEGIN
  SELECT count(*) INTO reason_total
    FROM compliance_intercept_reason r
   WHERE r.tenant_id = NEW.tenant_id AND r.decision_id = NEW.id;
  IF NEW.conclusion IN ('BLOCK','WARN','UNDECIDABLE') AND reason_total = 0 THEN
    RAISE EXCEPTION
      '合规判定 % 的结论是 %，必须至少有一条原因（不放行就要说清为什么）', NEW.id, NEW.conclusion
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER compliance_intercept_decision_has_reason
  AFTER INSERT ON compliance_intercept_decision
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_intercept_decision_has_reason();

-- ===========================================================================
-- 六、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'compliance_intercept_policy','compliance_intercept_decision',
    'compliance_intercept_exemption','compliance_intercept_reason' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 七、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  compliance_intercept_policy, compliance_intercept_decision,
  compliance_intercept_exemption, compliance_intercept_reason TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V312 同口径）。
REVOKE ALL ON
  compliance_intercept_policy, compliance_intercept_decision,
  compliance_intercept_exemption, compliance_intercept_reason FROM starcon_app;

-- 🔴 **序列授权**：本迁移的四张表**全部用应用侧生成的 uuid 作主键**，没有任何
--   serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 / V300 / V311 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 拦截执行属于「平台操作 / 合规台账」语义，
-- 与 16.2.1.1 同族，复用 identity 已注册的 channel.operation.*（读 / 录 / 改），
-- identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 八、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **上游 16.2.1.1 规则库**：**已交付**（同库 `compliance_rule*`），
--     本模块**只读**它 —— 判定绑定「当时生效的那个 version」。
--   * **下游 4.3.1.1 一键刊登**：**已交付**（同服务 `listing_publish_job*`）。
--     本模块在该服务的**派发路径**上落地服务端闸门（读服务端结论才放行）。
--   * **下游 8.3.4.1 发货**：warehouse 服务（**跨服务**）⇒ 本模块**只出结论**，
--     **不跨服务 SQL / 不造跨库 FK**；能力声明里 available=false + ownerModule。
--   * **下游 12 提现**：finance 服务在本仓**尚未落地** ⇒ 本模块**不建财务表、
--     不造提现调用**；能力声明里 available=false + ownerModule。
-- ---------------------------------------------------------------------------
