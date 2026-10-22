-- ===========================================================================
-- 16.1.2.2 申诉管理 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 105-110 行）：
--   ### 16.1.2.2 申诉管理
--   - 职责：管理平台申诉：申诉类型、举证材料收集、申诉文本、提交跟踪、结果记录与申诉次数统计。
--   - 输入：违规记录；申诉材料；提交动作。
--   - 输出：申诉单 + 提交记录 + 结果（通过/驳回）。
--   - 关系：上游 = 16.1.2.1；下游 = 平台申诉接口。**申诉配额有限**，需记录已用次数并优先申诉高影响违规。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 函数 / 策略**，**零 ALTER 任何既有表**：
--   * `shop` / `platform`（V1，02 渠道与店铺）—— 店铺目录的事实来源；
--   * `platform_violation` / `platform_penalty`（V308，16.1.2.1 违规与处罚）—— **同服务同库**，
--     本模块**只读**它们：校验申诉引用的违规真实存在、且归属同一店铺，
--     并按上游已派生的**影响档位**（停号 > 下架 > 限制销售·扣款 > 警告）排「优先申诉」队列。
--
-- 🔴🔴 **本模块绝不写上游的表**：上游 16.1.2.1 已交付，它的状态机（APPEALING 等）与列
--   是**它自己的**写权限边界。本模块只**读** platform_violation / platform_penalty。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.1.2.2，合规域）⇒ `platform_appeal_*`（本迁移）。
--   * 上游兄弟 16.1.2.1 ⇒ `platform_violation_*` / `platform_penalty_*`（V308，既有）。
--   * 16.1.1.1 ⇒ `account_health_*`（V300）；16.1.1.2 ⇒ `account_health_performance_*`（V304）。
--   四个命名空间彼此隔离，本迁移**不碰**上游的任何一张表。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 值域必须由 **DB 层 CHECK 钉死**，不能只靠应用层 enum
-- ---------------------------------------------------------------------------
--   * 申诉类型（蓝图点名的「申诉类型」，未列举）⇒ 本模块给出一份**默认值域**并用 CHECK 钉死。
--     🔴 这份值域是**本模块的默认口径、不是平台事实**（各平台的申诉分类并不通用），
--     能力声明里写明这一点。
--   * 申诉状态 ⇒ DRAFT（草稿）/ SUBMITTED（已提交）/ APPROVED（通过）/ REJECTED（驳回）/
--     WITHDRAWN（已撤回），**合法转换由触发器钉死**（见第五节）。
--   * 结果（通过/驳回）⇒ APPROVED / REJECTED，由 CHECK 钉死。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 蓝图加粗句「申诉配额有限，需记录已用次数并优先申诉高影响违规」
-- ---------------------------------------------------------------------------
-- 这句话**不是可选项**，落成三道结构闸：
--   1. **配额是真的表**：`platform_appeal_quota`（店铺 + 周期 + 上限 + 已用次数）。
--      `used_count <= quota_limit` 由 CHECK 钉死 ⇒ 超额提交在 **DB 层**就被拒。
--   2. **已用次数只能由提交动作递增**：触发器 `guard_platform_appeal_quota_used_count`
--      拒绝任何「直接改写已用次数」的写入（只能 +1）⇒ 已用次数不是可以被随手抹平的数字。
--   3. **优先申诉高影响违规**：影响档位**派生**（不落库，见第四节），申诉清单默认按
--      **影响档位优先**排序，另有一条「优先申诉队列」端点列出**该申诉什么**的违规。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 影响档位是「派生」的，不是存的
-- ---------------------------------------------------------------------------
-- 上游 16.1.2.1 已把影响档位定义为「由处罚措施集合派生」（停号 > 下架 > 限制销售·扣款 > 警告，
-- 「尚无处罚措施」是它自己的档位）。把它**再存一列**到申诉单上就会与上游的措施表漂移
-- （上游改了一条措施、申诉单的档位还停在旧值）。
-- 所以本模块用函数 `platform_appeal_impact_level()` **现算**（与上游 Java 规则
-- `PlatformViolationRules.impactLevel` 由集成测试逐档位对拍，见 16.1.2.2 的集成测试）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 状态机：显式枚举 + 显式合法转换（DB 与服务层都拒非法转换）
-- ---------------------------------------------------------------------------
--   DRAFT（草稿）     → SUBMITTED（已提交）/ WITHDRAWN（已撤回）
--   SUBMITTED（已提交）→ APPROVED（通过）/ REJECTED（驳回）
--   REJECTED（驳回）  → DRAFT（补充举证材料后再次申诉）
--   APPROVED / WITHDRAWN ⇒ **终态**（不可再改）
-- 🔴 驳回后允许回到 DRAFT 再提交，是为了让「提交记录」与「申诉次数统计」**不是摆设**：
--   每次提交都写一条**追加式**的 platform_appeal_submission，并消耗**一次配额**。
--   「重新申诉」消耗新配额 —— 这正是「申诉配额有限」这句话的真实含义。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 举证材料 / 提交记录 / 结果记录三张表全部 **append-only**
-- ---------------------------------------------------------------------------
--   * `platform_appeal_evidence` 举证材料 —— 「当时交了什么」的证据
--     ⇒ 触发器 `guard_platform_appeal_evidence_immutable` + `..._no_delete` 钉死
--       （照抄上游 `guard_platform_violation_evidence_immutable` 的做法）。
--       可逆性靠**追加新凭证**冲销，不靠改历史。
--   * `platform_appeal_submission` 提交记录 —— 提交是**已经发生的事实** ⇒ 同样不可改写不可删。
--   * `platform_appeal_decision` 结果记录 —— 平台裁定是**已经发生的事实** ⇒ 同样不可改写不可删。
--   驳回后再次申诉**不会**抹掉上一次的结果记录：历史留在一张表里，当前状态在申诉单上。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 零外键（跨表引用一律用触发器校验）
-- ---------------------------------------------------------------------------
-- 与 V300 / V304 / V308 同一套写法：`shop_id` / `violation_id` / `appeal_id` 的存在性
-- 由 BEFORE 触发器校验，**不建 FK**（FK 会打破同服务既有测试的 TRUNCATE 纪律）。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 多租户隔离：tenant_id NOT NULL + ENABLE **与 FORCE** ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- 🔴 本模块五张表**全部是店铺维度**的资产 ⇒ 全部带 data_scope（RESTRICTIVE）策略，
--   可见性随店铺负责人 / 部门收敛（与 V300 / V304 / V308 同构）。
--
-- ===========================================================================

-- ===========================================================================
-- 一、platform_appeal · 申诉单
-- ===========================================================================
CREATE TABLE platform_appeal (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 店铺（本服务 channel 自家库里的 `shop.id`）。零外键，存在性由触发器校验。
  shop_id       uuid NOT NULL,
  -- 🔴 上游 16.1.2.1 的违规记录 id。零外键，存在性与**同店铺**由触发器校验。
  violation_id  uuid NOT NULL,
  -- 🔴 申诉类型（受控值域，DB 层钉死）。**本模块的默认口径，不是平台事实**。
  appeal_type   varchar(32) NOT NULL
                CHECK(appeal_type IN (
                  'POLICY_MISINTERPRETATION',  -- 政策误判（平台对规则理解有偏）
                  'EVIDENCE_PROVIDED',        -- 补充证据（材料齐备，请求重新判定）
                  'PROCESS_ERROR',            -- 处理流程有误（事实认定与证据不符）
                  'DUPLICATE_PENALTY',        -- 重复处罚（同一事实被罚了两次）
                  'RESTORATION_REQUEST',      -- 恢复措施申请（解除下架 / 限制销售）
                  'OTHER')),                  -- 其他
  -- 🔴 申诉状态（受控值域）。合法转换由触发器钉死。
  status        varchar(16) NOT NULL DEFAULT 'DRAFT'
                CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','WITHDRAWN')),
  -- 🔴 申诉文本（人话，不许只写一个状态码）。
  appeal_text   text NOT NULL,
  -- 🔴 最近一次提交时刻（历史提交见 platform_appeal_submission）。
  submitted_at  timestamptz,
  submitted_by  uuid,
  -- 撤回时刻（WITHDRAWN 必填）。
  withdrawn_at  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(appeal_text) <> ''),
  -- 🔴 「已提交」必须真的提交过。
  CHECK(status <> 'SUBMITTED' OR submitted_at IS NOT NULL),
  -- 🔴 「已撤回」必须真的撤回过。
  CHECK(status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL),
  -- 🔴 草稿态不许带着提交痕迹（驳回后回到 DRAFT 时由服务层清空）。
  CHECK(status <> 'DRAFT' OR (submitted_at IS NULL AND withdrawn_at IS NULL))
);
COMMENT ON TABLE platform_appeal IS
  '16.1.2.2 · 申诉单。appeal_type 是**本模块的默认口径、不是平台事实**；'
  'status 值域与合法转换由 DB 层 CHECK + 触发器钉死。'
  '**影响档位不落库**（派生自上游的处罚措施集合，见 platform_appeal_impact_level）。';
CREATE INDEX platform_appeal_shop
  ON platform_appeal(tenant_id, shop_id, created_at DESC, id);
CREATE INDEX platform_appeal_violation
  ON platform_appeal(tenant_id, violation_id, created_at DESC);
CREATE INDEX platform_appeal_status
  ON platform_appeal(tenant_id, status, created_at DESC);
CREATE INDEX platform_appeal_type
  ON platform_appeal(tenant_id, appeal_type, created_at DESC);
-- 🔴 同一条违规**同时只能有一个在办的申诉**（草稿 / 已提交）。
--   驳回 / 通过 / 撤回之后可以再提 —— 那要消耗**新的配额**。
CREATE UNIQUE INDEX platform_appeal_open_violation
  ON platform_appeal(tenant_id, violation_id) WHERE status IN ('DRAFT','SUBMITTED');

-- ===========================================================================
-- 二、platform_appeal_evidence · 举证材料（**append-only**）
-- ===========================================================================
CREATE TABLE platform_appeal_evidence (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  shop_id        uuid NOT NULL,
  appeal_id      uuid NOT NULL,
  evidence_type  varchar(24) NOT NULL
                 CHECK(evidence_type IN (
                   'DOCUMENT','IMAGE','VIDEO','LINK','NOTE','INVOICE','CERTIFICATE')),
  -- 凭证引用（文件名 / 链接 / 附件 id）。与 summary 至少给一个。
  evidence_ref   varchar(500) NOT NULL DEFAULT '',
  summary        varchar(1000) NOT NULL DEFAULT '',
  submitted_by   uuid,
  submitted_at   timestamptz NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(evidence_ref) <> '' OR btrim(summary) <> '')
);
COMMENT ON TABLE platform_appeal_evidence IS
  '16.1.2.2 · 申诉举证材料（**append-only**，触发器钉住不可改写不可删）。'
  '可逆性靠追加新凭证冲销，不靠改历史。';
CREATE INDEX platform_appeal_evidence_appeal
  ON platform_appeal_evidence(tenant_id, appeal_id, submitted_at DESC);

-- ===========================================================================
-- 三、platform_appeal_submission · 提交记录（**append-only**，提交跟踪）
-- ===========================================================================
CREATE TABLE platform_appeal_submission (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  shop_id            uuid NOT NULL,
  appeal_id          uuid NOT NULL,
  -- 🔴 第几次提交（驳回后补材料再提 ⇒ 第 2 次、第 3 次…）。
  attempt_no         int NOT NULL CHECK(attempt_no >= 1),
  submitted_at       timestamptz NOT NULL,
  submitted_by       uuid,
  -- 🔴 平台侧受理号 / 回执号（可空：还没拿到回执时提交已经发生了）。
  platform_reference varchar(200) NOT NULL DEFAULT '',
  note               varchar(500) NOT NULL DEFAULT '',
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE platform_appeal_submission IS
  '16.1.2.2 · 申诉提交记录（**append-only**）。'
  'attempt_no 让「驳回后再次提交」在数据上可数；每次提交消耗**一次申诉配额**。';
CREATE INDEX platform_appeal_submission_appeal
  ON platform_appeal_submission(tenant_id, appeal_id, attempt_no);
CREATE INDEX platform_appeal_submission_shop
  ON platform_appeal_submission(tenant_id, shop_id, submitted_at DESC);

-- ===========================================================================
-- 四、platform_appeal_decision · 结果记录（**append-only**，通过 / 驳回）
-- ===========================================================================
CREATE TABLE platform_appeal_decision (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  shop_id            uuid NOT NULL,
  appeal_id          uuid NOT NULL,
  -- 🔴 结果（受控值域，DB 层钉死）。
  outcome            varchar(16) NOT NULL CHECK(outcome IN ('APPROVED','REJECTED')),
  -- 🔴 平台给出的裁定理由（人话）。
  reason             text NOT NULL DEFAULT '',
  decided_at         timestamptz NOT NULL,
  decided_by         uuid,
  -- 🔴 平台侧回执号（可空）。
  platform_reference varchar(200) NOT NULL DEFAULT '',
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE platform_appeal_decision IS
  '16.1.2.2 · 申诉结果记录（**append-only**）。'
  '驳回后再次申诉**不会**抹掉这一条 —— 历史留痕，当前状态在 platform_appeal.status 上。';
CREATE INDEX platform_appeal_decision_appeal
  ON platform_appeal_decision(tenant_id, appeal_id, decided_at DESC);
CREATE INDEX platform_appeal_decision_shop
  ON platform_appeal_decision(tenant_id, shop_id, decided_at DESC);

-- ===========================================================================
-- 五、platform_appeal_quota · 申诉配额（**已用次数**）
-- ===========================================================================
CREATE TABLE platform_appeal_quota (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  shop_id       uuid NOT NULL,
  -- 🔴 配额周期（timestamptz，闭区间）。用 timestamptz 而不是 date 是为了**消除时区歧义**
  --   —— `timestamptz::date` 会随会话 TimeZone 变，周期边界会漂。
  period_start  timestamptz NOT NULL,
  period_end    timestamptz NOT NULL,
  -- 🔴 配额上限（本周期最多可提交多少次）。
  quota_limit   int NOT NULL CHECK(quota_limit >= 0),
  -- 🔴 已用次数（= 本周期已提交次数）。**只能由提交动作 +1**，不能直接改写。
  used_count    int NOT NULL DEFAULT 0 CHECK(used_count >= 0),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一店铺同一周期只有一条配额。
  UNIQUE(tenant_id, shop_id, period_start),
  CHECK(period_end >= period_start),
  -- 🔴 超额提交在 **DB 层**就被拒（不是只有服务层拦）。
  CHECK(used_count <= quota_limit)
);
COMMENT ON TABLE platform_appeal_quota IS
  '16.1.2.2 · 申诉配额（店铺 + 周期）。**申诉配额有限** ⇒ 上限与已用次数都落库，'
  'used_count <= quota_limit 由 CHECK 钉死，已用次数只能由提交动作递增一次。';
CREATE INDEX platform_appeal_quota_shop
  ON platform_appeal_quota(tenant_id, shop_id, period_start DESC);

-- ===========================================================================
-- 六、触发器与函数
-- ===========================================================================

-- 🔴 (a) 申诉状态机：**非法转换直接拒写**。
CREATE FUNCTION guard_platform_appeal_status_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  allowed boolean := false;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT'     THEN NEW.status IN ('SUBMITTED','WITHDRAWN')
    WHEN 'SUBMITTED' THEN NEW.status IN ('APPROVED','REJECTED')
    WHEN 'REJECTED'  THEN NEW.status IN ('DRAFT')
    WHEN 'APPROVED'  THEN false   -- 终态：通过的申诉不可再改
    WHEN 'WITHDRAWN' THEN false   -- 终态：撤回的申诉不可再改
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION
      '申诉 % 的状态不能从 % 转到 %', OLD.id, OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_status_transition BEFORE UPDATE ON platform_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_status_transition();

-- 🔴 (b) 申诉单**不可改写身份字段**：申诉类型 / 归属违规 / 归属店铺一旦立下就不许换
--   （换了就等于把一份申诉单改成另一份，而「当时申诉的是什么」必须留在历史里）。
--   🔴 申诉文本**只在草稿态可改**：草稿本来就是拿来改的；一旦提交，文本就冻结 ——
--   「提交时到底写了什么」必须能从这一行读出来。
CREATE FUNCTION guard_platform_appeal_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.appeal_type IS DISTINCT FROM OLD.appeal_type
     OR NEW.violation_id IS DISTINCT FROM OLD.violation_id
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION
      '申诉 % 的申诉类型 / 归属违规 / 归属店铺不可改写', OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.appeal_text IS DISTINCT FROM OLD.appeal_text AND OLD.status <> 'DRAFT' THEN
    RAISE EXCEPTION
      '申诉 % 当前状态是 %，申诉文本只有在草稿态可改', OLD.id, OLD.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_identity_immutable BEFORE UPDATE ON platform_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_identity_immutable();

-- 🔴 (c) 举证材料**不可追溯改写**：提交了什么就是提交了什么。
CREATE FUNCTION guard_platform_appeal_evidence_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.evidence_type IS DISTINCT FROM OLD.evidence_type
     OR NEW.evidence_ref IS DISTINCT FROM OLD.evidence_ref
     OR NEW.summary IS DISTINCT FROM OLD.summary
     OR NEW.appeal_id IS DISTINCT FROM OLD.appeal_id
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
     OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
     OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN
    RAISE EXCEPTION
      '申诉举证材料 % 是追加式凭据，不可改写', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_evidence_immutable BEFORE UPDATE ON platform_appeal_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_evidence_immutable();

-- 🔴 (d) 举证材料**不可删除**：它是「当时交了什么」的证据。
CREATE FUNCTION guard_platform_appeal_evidence_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    '申诉举证材料 % 是追加式凭据，不可删除', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER platform_appeal_evidence_no_delete BEFORE DELETE ON platform_appeal_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_evidence_no_delete();

-- 🔴 (e) 提交记录**不可改写**：提交是已经发生的事实。
CREATE FUNCTION guard_platform_appeal_submission_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.attempt_no IS DISTINCT FROM OLD.attempt_no
     OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
     OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
     OR NEW.platform_reference IS DISTINCT FROM OLD.platform_reference
     OR NEW.note IS DISTINCT FROM OLD.note
     OR NEW.appeal_id IS DISTINCT FROM OLD.appeal_id
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION
      '申诉提交记录 % 是已发生的事实，不可改写', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_submission_immutable BEFORE UPDATE ON platform_appeal_submission
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_submission_immutable();

-- 🔴 (f) 提交记录**不可删除**。
CREATE FUNCTION guard_platform_appeal_submission_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    '申诉提交记录 % 是已发生的事实，不可删除', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER platform_appeal_submission_no_delete BEFORE DELETE ON platform_appeal_submission
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_submission_no_delete();

-- 🔴 (g) 结果记录**不可改写**：平台裁定是已经发生的事实。
CREATE FUNCTION guard_platform_appeal_decision_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.outcome IS DISTINCT FROM OLD.outcome
     OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
     OR NEW.decided_by IS DISTINCT FROM OLD.decided_by
     OR NEW.platform_reference IS DISTINCT FROM OLD.platform_reference
     OR NEW.appeal_id IS DISTINCT FROM OLD.appeal_id
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION
      '申诉结果记录 % 是已发生的事实，不可改写', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_decision_immutable BEFORE UPDATE ON platform_appeal_decision
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_decision_immutable();

-- 🔴 (h) 结果记录**不可删除**。
CREATE FUNCTION guard_platform_appeal_decision_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    '申诉结果记录 % 是已发生的事实，不可删除', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER platform_appeal_decision_no_delete BEFORE DELETE ON platform_appeal_decision
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_decision_no_delete();

-- 🔴 (i) 跨表引用校验（**零外键**）：shop_id 必须在本租户的店铺目录里真实存在。
CREATE FUNCTION guard_platform_appeal_shop_ref() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  ref_shop uuid;
BEGIN
  ref_shop := NEW.shop_id;
  IF NOT EXISTS (
       SELECT 1 FROM shop s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = ref_shop) THEN
    RAISE EXCEPTION
      '平台申诉相关行引用了一个在本租户店铺目录里不存在的店铺（或超出当前权限范围）：%',
      ref_shop
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_shop_ref BEFORE INSERT OR UPDATE ON platform_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_shop_ref();
CREATE TRIGGER platform_appeal_evidence_shop_ref BEFORE INSERT OR UPDATE ON platform_appeal_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_shop_ref();
CREATE TRIGGER platform_appeal_submission_shop_ref BEFORE INSERT OR UPDATE ON platform_appeal_submission
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_shop_ref();
CREATE TRIGGER platform_appeal_decision_shop_ref BEFORE INSERT OR UPDATE ON platform_appeal_decision
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_shop_ref();
CREATE TRIGGER platform_appeal_quota_shop_ref BEFORE INSERT OR UPDATE ON platform_appeal_quota
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_shop_ref();

-- 🔴 (j) 跨表引用校验（**零外键**）：申诉引用的违规必须真实存在，且**属于同一店铺**。
--   同店铺这条很实：跨店铺申诉会把 A 店的违规拿到 B 店的申诉单里，平台一看就对不上。
CREATE FUNCTION guard_platform_appeal_violation_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM platform_violation v
        WHERE v.tenant_id = NEW.tenant_id
          AND v.id = NEW.violation_id
          AND v.shop_id = NEW.shop_id) THEN
    RAISE EXCEPTION
      '申诉 % 引用的违规记录不存在于本租户或不属于同一店铺：%', NEW.id, NEW.violation_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_violation_ref BEFORE INSERT OR UPDATE ON platform_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_violation_ref();

-- 🔴 (k) 跨表引用校验（**零外键**）：子表的 appeal_id 必须落在本租户的申诉单里，
--   且子表的 shop_id 必须与申诉单的 shop_id 一致。
CREATE FUNCTION guard_platform_appeal_parent_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM platform_appeal a
        WHERE a.tenant_id = NEW.tenant_id
          AND a.id = NEW.appeal_id
          AND a.shop_id = NEW.shop_id) THEN
    RAISE EXCEPTION
      '平台申诉子表引用了一条本租户不存在或店铺不一致的申诉单：%', NEW.appeal_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_evidence_parent_ref BEFORE INSERT OR UPDATE ON platform_appeal_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_parent_ref();
CREATE TRIGGER platform_appeal_submission_parent_ref BEFORE INSERT OR UPDATE ON platform_appeal_submission
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_parent_ref();
CREATE TRIGGER platform_appeal_decision_parent_ref BEFORE INSERT OR UPDATE ON platform_appeal_decision
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_parent_ref();

-- 🔴 (l) 举证材料**只在草稿态收集**：提交之后再往里加材料，就是伪造「当时交了什么」。
CREATE FUNCTION guard_platform_appeal_evidence_draft_only() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_status text;
BEGIN
  SELECT a.status INTO current_status
    FROM platform_appeal a
   WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.appeal_id;
  IF current_status IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION
      '申诉 % 当前状态是 %，举证材料只能在草稿态收集', NEW.appeal_id, current_status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
-- 🔴 触发器按**名字字母序**触发（PG 的固定行为）⇒ 这个名字排在
--   `platform_appeal_evidence_parent_ref` **之后**，保证「申诉单存在」先被校验过，
--   这里报出来的状态不会是空值。
CREATE TRIGGER platform_appeal_evidence_state_draft BEFORE INSERT ON platform_appeal_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_evidence_draft_only();

-- 🔴 (m) 申诉提交记录**只能对应一次提交**：申诉单状态必须正好是「即将变成已提交」。
CREATE FUNCTION guard_platform_appeal_submission_state() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_status text;
BEGIN
  SELECT a.status INTO current_status
    FROM platform_appeal a
   WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.appeal_id;
  IF current_status IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION
      '申诉 % 当前状态是 %，只能在草稿态提交', NEW.appeal_id, current_status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_submission_state BEFORE INSERT ON platform_appeal_submission
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_submission_state();

-- 🔴 (n) 结果记录**只能对应一次裁定**：申诉单状态必须正好是「已提交」。
CREATE FUNCTION guard_platform_appeal_decision_state() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_status text;
BEGIN
  SELECT a.status INTO current_status
    FROM platform_appeal a
   WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.appeal_id;
  IF current_status IS DISTINCT FROM 'SUBMITTED' THEN
    RAISE EXCEPTION
      '申诉 % 当前状态是 %，只能裁定已提交的申诉', NEW.appeal_id, current_status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_decision_state BEFORE INSERT ON platform_appeal_decision
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_decision_state();

-- 🔴 (o) 申诉单进入「已提交」⇒ **消耗一次申诉配额**（蓝图加粗句「申诉配额有限」）。
--   没配配额 ⇒ 直接拒（不许「没配额就当无限」）；超配额 ⇒ 直接拒。
CREATE FUNCTION guard_platform_appeal_quota_consume() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_quota_id uuid;
  v_limit int;
  v_used int;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status OR NEW.status <> 'SUBMITTED' THEN
    RETURN NEW;
  END IF;
  SELECT q.id, q.quota_limit, q.used_count
    INTO v_quota_id, v_limit, v_used
    FROM platform_appeal_quota q
   WHERE q.tenant_id = NEW.tenant_id
     AND q.shop_id = NEW.shop_id
     AND q.period_start <= NEW.submitted_at
     AND q.period_end   >= NEW.submitted_at
   ORDER BY q.period_start DESC
   LIMIT 1;
  IF v_quota_id IS NULL THEN
    RAISE EXCEPTION
      '店铺 % 在提交时刻 % 没有配置申诉配额，无法提交申诉',
      NEW.shop_id, NEW.submitted_at
      USING ERRCODE='23514';
  END IF;
  IF v_used + 1 > v_limit THEN
    RAISE EXCEPTION
      '申诉配额已用尽：店铺 % 本周期上限 % 次，已用 % 次', NEW.shop_id, v_limit, v_used
      USING ERRCODE='23514';
  END IF;
  -- 🔴 打个**事务内**的标记：下面那个「已用次数只许由提交动作递增」的触发器靠它辨认
  --   「这一次递增是提交动作干的」。没有这个标记，谁都能自己写 `used_count = used_count + 1`。
  PERFORM set_config('app.appeal_quota_consume', v_quota_id::text, true);
  UPDATE platform_appeal_quota
     SET used_count = used_count + 1, updated_at = now()
   WHERE id = v_quota_id;
  RETURN NEW;
END $$;
-- 🔴 触发器按**名字字母序**触发 ⇒ 这个名字排在
--   `platform_appeal_status_transition` **之后**（submit > status），
--   非法状态转换在**消耗配额之前**就被拒。
CREATE TRIGGER platform_appeal_submit_quota_consume BEFORE UPDATE ON platform_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_quota_consume();

-- 🔴 (p) 配额**已用次数只能由提交动作递增**：不许直接改写成任意数字，
--   连「自己写一句 used_count = used_count + 1」也不行 ——
--   那需要**提交动作**留下的事务内标记（app.appeal_quota_consume）。
CREATE FUNCTION guard_platform_appeal_quota_used_count() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  marker text;
BEGIN
  IF NEW.used_count IS DISTINCT FROM OLD.used_count THEN
    marker := current_setting('app.appeal_quota_consume', true);
    IF marker IS DISTINCT FROM OLD.id::text THEN
      RAISE EXCEPTION
        '申诉配额的已用次数只能由提交动作递增一次，不可直接改写：%', OLD.id
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_appeal_quota_used_count BEFORE UPDATE ON platform_appeal_quota
  FOR EACH ROW EXECUTE FUNCTION guard_platform_appeal_quota_used_count();

-- 🔴 (q) 影响档位**派生**（不落库）：由上游违规身上的处罚措施集合算出。
--   与上游 Java 规则 `PlatformViolationRules.impactLevel` 逐档位对拍（见 16.1.2.2 集成测试）。
CREATE FUNCTION platform_appeal_impact_level(p_tenant uuid, p_violation uuid) RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT CASE
    WHEN EXISTS (SELECT 1 FROM platform_penalty p
                  WHERE p.tenant_id = p_tenant AND p.violation_id = p_violation
                    AND p.measure = 'SUSPEND_ACCOUNT') THEN 'CRITICAL'
    WHEN EXISTS (SELECT 1 FROM platform_penalty p
                  WHERE p.tenant_id = p_tenant AND p.violation_id = p_violation
                    AND p.measure = 'DELIST') THEN 'HIGH'
    WHEN EXISTS (SELECT 1 FROM platform_penalty p
                  WHERE p.tenant_id = p_tenant AND p.violation_id = p_violation
                    AND p.measure IN ('RESTRICT_SALES','DEDUCTION')) THEN 'MEDIUM'
    WHEN EXISTS (SELECT 1 FROM platform_penalty p
                  WHERE p.tenant_id = p_tenant AND p.violation_id = p_violation
                    AND p.measure = 'WARNING') THEN 'LOW'
    ELSE 'NONE'
  END;
$$;
COMMENT ON FUNCTION platform_appeal_impact_level(uuid, uuid) IS
  '16.1.2.2 · **派生**影响档位：按上游违规的处罚措施集合算（停号 > 下架 > 限制销售·扣款 > 警告）。'
  '**不落库**（落库就会与上游的措施表漂移）。';

-- ===========================================================================
-- 七、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_appeal','platform_appeal_evidence','platform_appeal_submission',
    'platform_appeal_decision','platform_appeal_quota' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V300 / V304 / V308 同构）：申诉是**店铺维度**的资产，
-- 可见性随店铺负责人 / 部门收敛。五张表**都带自己的 shop_id** ⇒ 策略形状一致。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_appeal','platform_appeal_evidence','platform_appeal_submission',
    'platform_appeal_decision','platform_appeal_quota' ] LOOP
    EXECUTE format($f$
      CREATE POLICY data_scope ON %I AS RESTRICTIVE
        USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=%I.tenant_id
                   AND s.id=%I.shop_id
                   AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
        WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=%I.tenant_id
                   AND s.id=%I.shop_id
                   AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
    $f$, tbl, tbl, tbl, tbl, tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 八、账号与授权
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
  platform_appeal, platform_appeal_evidence, platform_appeal_submission,
  platform_appeal_decision, platform_appeal_quota TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V308 同口径）。
REVOKE ALL ON
  platform_appeal, platform_appeal_evidence, platform_appeal_submission,
  platform_appeal_decision, platform_appeal_quota FROM starcon_app;

-- 🔴 **序列授权**：本迁移的五张表**全部用应用侧生成的 uuid 作主键**，没有任何
--   serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 / V300 / V304 / V308 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 申诉管理属于「平台操作 / 合规台账」语义，
-- 与 16.1.1.1 / 16.1.1.2 / 16.1.2.1 同族，复用 identity 已注册的 channel.operation.*
-- （读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 九、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **上游 16.1.2.1 违规与处罚**：**已交付**（同服务同库）⇒ 本模块**只读**
--     `platform_violation` / `platform_penalty`：校验申诉引用的违规真实存在且同店铺，
--     并按上游派生的影响档位排优先队列。**本模块不写上游的任何一行。**
--   * **下游 平台申诉接口**：**本仓不存在**（各平台的申诉提交通道未接入）⇒
--     本模块**不造指向不存在入口的放行形状**；提交动作只落**提交记录**，
--     能力声明里标 available=false + ownerModule + 影响说明。
-- ---------------------------------------------------------------------------
