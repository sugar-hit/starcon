-- ===========================================================================
-- V437 · crm_customer_merge —— 15.3.1.3「跨店合并」的三张事实表 + 运行台账
-- ===========================================================================
--
-- 蓝图 15 卷 15.3.1.3 跨店合并 原话：
--
--   职责：把同一自然人在不同店铺的身份合并到一个逻辑客户，支持按邮箱/电话/姓名+地址/
--         平台买家标识匹配，给出置信度并需人工确认，保留合并前映射可拆分。
--   输入：候选匹配（自动建议）；人工确认；拆分请求。
--   输出：合并后的逻辑客户 + 映射关系 + 操作记录。
--   关系：上游 = 15.3.1.1；下游 = 15.3.1.2、13 分析（客户价值跨店汇总）。
--         **自动合并风险高**（同名不同人、同人不同邮箱），因此只给建议不自动执行，且必须可拆分。
--
-- ---------------------------------------------------------------------------
-- 一、本文件与 15.3.1.1 的边界（写入所有者各自独立）
-- ---------------------------------------------------------------------------
--
-- 15.3.1.1（V432）的文件头写死了它的边界：
--
--   「跨店合并（15.3.1.3 跨店合并）是**另一个模块**的事：它拥有「逻辑客户」这张事实表
--     与合并映射。本文件**不建**逻辑客户主表、**不建**合并映射表、
--     **不提前**做任何合并 —— 那会同时踩两条线：
--       (1) 「每张事实表只有一个写入所有者」；
--       (2) 15.3.1.3 的上游就是本模块，它会自己拥有合并事实表。」
--
-- ⇒ 本文件建的就是那三张表（外加一张运行台账）：
--
--   crm_customer_merge_logical    逻辑客户主表（合并后的那个「人」）
--   crm_customer_merge_mapping    合并映射（逻辑客户 ↔ 来源店铺客户行）
--   crm_customer_merge_candidate  候选匹配（自动建议，待人工确认）
--   crm_customer_merge_operation  操作记录（**只追加**的审计流水）
--   crm_customer_merge_run        候选生成运行台账（这次扫了多少、产出多少、砍掉多少）
--
-- 🔴 **本模块一个字都不写 `crm_customer_profile`**（15.3.1.1 的写入所有者）：
--   合并**不修改来源行**，它只是在本模块自己的映射表里把两条来源行挂到同一个逻辑客户下。
--   这正是「拆分能把逻辑客户还原回原来的来源行」得以成立的前提 ——
--   来源行从头到尾没被改过，拆分只需要把映射从 ACTIVE 改成 SPLIT。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 只给建议，不自动合并
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句「**自动合并风险高**（同名不同人、同人不同邮箱），因此只给建议不自动执行」。
-- 在 DB 层的落点：
--
--   * 候选表 `crm_customer_merge_candidate` 的 `status` 初值只能是 'SUGGESTED'；
--   * 'CONFIRMED' / 'REJECTED' 只能由**人工**端点写入，且必须带 `reviewed_by` +
--     `reviewed_at` + `review_note`（DB 触发器强制）；
--   * 「逻辑客户 + ACTIVE 映射」这一对事实**只能**由合并动作一次性写出，
--     候选表本身**不写**任何一行映射 —— 候选是候选，事实是事实。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 必须可拆分
-- ---------------------------------------------------------------------------
--
-- 「保留合并前映射可拆分」在 DB 层的落点：
--
--   * 映射行**不删**：拆分 = `state` 从 'ACTIVE' 变成 'SPLIT'，
--     并写上 `split_at` / `split_by` / `split_reason`（触发器强制「拆分必须留痕」）；
--   * 'SPLIT' → 'ACTIVE' **不允许**（触发器拒绝）：重新合并要**新写一行**映射，
--     历史那一行原样留着 —— 这样「这个人被合并过、后来拆开、又合并过」在审计流水里查得到；
--   * 因此 UNIQUE 约束是**部分唯一索引**（只约束 ACTIVE 行），而不是全表唯一约束。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 匹配信号分级（置信度要能解释「凭什么认为是同一个人」）
-- ---------------------------------------------------------------------------
--
-- 蓝图点名四种匹配依据，它们的**证据强度不同**，不能揉成一个神秘分数：
--
--   PLATFORM_BUYER_ID  平台买家标识（上游订单里的 customer_party_id）相同   最强
--   EMAIL              邮箱相同
--   PHONE              电话相同
--   NAME_ADDRESS       姓名相同**且**地址相同
--
--   NAME_ONLY（只有姓名相同）**不产出候选**：那正是蓝图说的「同名不同人」。
--
-- 所以 `match_type` 存**最强的那一个命中信号**，`evidence` 里逐信号记明细，
-- `signal_summary` 是一句人话（「邮箱相同；电话相同」）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 联系方式脱敏（照抄 15.3.1.1 的纪律）
-- ---------------------------------------------------------------------------
--
-- `evidence` 里存的邮箱 / 电话**只有脱敏形态**（a***@***.com / 138****1234）：
-- 匹配在内存里做（比对明文），落库的只有脱敏形态。
-- 明文要读时，走 15.3.1.1 的 `crm_customer_profile` 明文列，
-- 且必须**同时**有 `crm.customers.pii.read` 权限码**并**调用方显式要求 ——
-- 否则 403，不静默退回脱敏。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 禁浮点与时间纪律
-- ---------------------------------------------------------------------------
--
--   * 置信度：numeric(5,4) + BigDecimal（**不是** 0..1 的 double）；
--   * 金额：本文件**不存金额**（金额归 15.3.1.1 的 `crm_customer_amount`，
--     按币种分行，跨币种**不可相加**）；逻辑客户页面的金额是**按币种聚合**出来的，
--     同一币种内相加，跨币种分行展示；
--   * 数量：numeric(19,6)；
--   * 时间：一律 timestamptz + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区**静默偏 −28800 秒**。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 外键纪律：零外键，一律触发器
-- ---------------------------------------------------------------------------
--
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 同一手法：
-- 本文件**不给任何既有表建外键**，本文件自己新建的表之间也**一律用触发器**做引用校验。
-- 原因：同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a
-- foreign key constraint）会被外键打破。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 零、角色（与 V99 / V220 / V299 / V432 同一口径：缺失才建，保证可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;
GRANT USAGE ON SCHEMA public TO starcon_app;

-- 运行号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一次生成候选就报权限不足」。
CREATE SEQUENCE crm_customer_merge_run_no_seq START 1;
-- 逻辑客户号序列（LC-yyyyMMdd-000001）：同样单独授权。
CREATE SEQUENCE crm_customer_merge_logical_no_seq START 1;

-- ===========================================================================
-- 1. crm_customer_merge_logical —— 逻辑客户主表
-- ===========================================================================
CREATE TABLE crm_customer_merge_logical (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 逻辑客户标识：LC-20261102-000001（人可读，便于客服口头指认）。
  logical_ref varchar(64) NOT NULL CHECK (btrim(logical_ref) <> ''),

  -- 展示名：取主档行的买家名快照；主档行被拆走时换另一个成员的名字。
  display_name varchar(200) NOT NULL DEFAULT '',

  -- 主成员（来源店铺客户行）：**逻辑引用**，不建跨表外键。
  primary_customer_id uuid,

  -- 成员数与来源店铺数（**拆分后**会变；成员行本身留着，只是不再 ACTIVE）。
  member_count integer NOT NULL DEFAULT 0 CHECK (member_count >= 0),
  source_shop_count integer NOT NULL DEFAULT 0 CHECK (source_shop_count >= 0),
  -- 订单数可以跨店铺相加（**不是金额**）—— 金额仍然按币种分行，不相加。
  total_order_count integer NOT NULL DEFAULT 0 CHECK (total_order_count >= 0),

  -- 🔴 继承的触达限制：任一成员存在**生效中**的退订（15.3.2.2 crm_customer_opt_out）
  --   ⇒ 合并后的逻辑客户整体标记 ACTIVE ⇒ 合并到一个逻辑客户会影响**所有来源**的触达，
  --   这正是「退订」作为**排除信号**在合并这一层的落点。
  opt_out_state varchar(16) NOT NULL DEFAULT 'NONE'
    CHECK (opt_out_state IN ('NONE','ACTIVE','REVOKED')),

  -- ACTIVE 正常 / DISSOLVED 已拆分干净（没有 ACTIVE 成员了）。
  -- 已解散的逻辑客户**不删**：审计流水与映射行都要留着。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','DISSOLVED')),

  first_order_at timestamptz,
  last_order_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, logical_ref)
);

-- ===========================================================================
-- 2. crm_customer_merge_mapping —— 合并映射（逻辑客户 ↔ 来源店铺客户行）
-- ===========================================================================
CREATE TABLE crm_customer_merge_mapping (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 逻辑客户（**逻辑引用**，触发器校验）。
  logical_customer_id uuid NOT NULL,
  -- 来源店铺客户行（15.3.1.1 crm_customer_profile 的行，**逻辑引用**，触发器校验）。
  -- 🔴 本模块**只读**那张表，一个字都不写。
  customer_profile_id uuid NOT NULL,

  -- 来源行快照（拆开以后也要知道这条映射原来指向谁）。
  source_shop varchar(64) NOT NULL CHECK (btrim(source_shop) <> ''),
  customer_ref varchar(128) NOT NULL CHECK (btrim(customer_ref) <> ''),
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- PRIMARY 主成员 / MEMBER 普通成员。
  role varchar(16) NOT NULL DEFAULT 'MEMBER'
    CHECK (role IN ('PRIMARY','MEMBER')),
  -- ACTIVE 生效中 / SPLIT 已拆分。
  state varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (state IN ('ACTIVE','SPLIT')),

  merged_at timestamptz NOT NULL,
  merged_by uuid,
  split_at timestamptz,
  split_by uuid,
  split_reason varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 🔴 一个来源客户行**同时只能**属于一个逻辑客户（只约束 ACTIVE 行）。
CREATE UNIQUE INDEX crm_customer_merge_mapping_active_customer
  ON crm_customer_merge_mapping(tenant_id, customer_profile_id)
  WHERE state = 'ACTIVE';

-- 同一个逻辑客户里，同一个来源客户行不能有两条 ACTIVE 映射。
CREATE UNIQUE INDEX crm_customer_merge_mapping_active_pair
  ON crm_customer_merge_mapping(tenant_id, logical_customer_id, customer_profile_id)
  WHERE state = 'ACTIVE';

CREATE INDEX crm_customer_merge_mapping_by_logical
  ON crm_customer_merge_mapping(tenant_id, logical_customer_id, state);

CREATE INDEX crm_customer_merge_mapping_by_customer
  ON crm_customer_merge_mapping(tenant_id, customer_profile_id, state);

-- ===========================================================================
-- 3. crm_customer_merge_candidate —— 候选匹配（自动建议，待人工确认）
-- ===========================================================================
CREATE TABLE crm_customer_merge_candidate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 候选的两侧：两个来源店铺客户行（15.3.1.1 的行 id）。
  -- 🔴 强制 left < right：同一对客户只有**一行**候选，重复扫描是 upsert 而不是插一堆。
  left_customer_id uuid NOT NULL,
  right_customer_id uuid NOT NULL,

  -- 两侧的来源店铺 / 客户标识 / 买家名快照（列表直接展示，不必回查）。
  left_source_shop varchar(64) NOT NULL DEFAULT '',
  left_customer_ref varchar(128) NOT NULL DEFAULT '',
  left_customer_name varchar(200) NOT NULL DEFAULT '',
  right_source_shop varchar(64) NOT NULL DEFAULT '',
  right_customer_ref varchar(128) NOT NULL DEFAULT '',
  right_customer_name varchar(200) NOT NULL DEFAULT '',

  -- 最强命中信号（见文件头第四节）。
  match_type varchar(24) NOT NULL
    CHECK (match_type IN ('PLATFORM_BUYER_ID','EMAIL','PHONE','NAME_ADDRESS')),

  -- 置信度 numeric(5,4)（禁浮点）：0.6000 ~ 0.9900。
  -- 低于 0.6000 的组合**不产出候选**（同名不同人那一档）。
  confidence numeric(5,4) NOT NULL
    CHECK (confidence >= 0 AND confidence <= 1),

  -- 一句人话：「凭什么认为这两个人是同一个人」。
  signal_summary varchar(600) NOT NULL DEFAULT '',
  -- 逐信号明细（JSON）。**邮箱 / 电话只存脱敏形态**（见文件头第五节）。
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 排除信号（JSON 数组）：OPT_OUT / ALREADY_MERGED / SAME_SHOP / NO_ADDRESS …
  exclusion_flags jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 一侧存在生效中的退订 ⇒ 合并会影响所有来源的触达 ⇒ 人工确认时必须写理由。
  opt_out_involved boolean NOT NULL DEFAULT false,

  -- SUGGESTED 待确认 / CONFIRMED 已合并 / REJECTED 已拒绝 / STALE 已失效（来源行不见了）。
  status varchar(16) NOT NULL DEFAULT 'SUGGESTED'
    CHECK (status IN ('SUGGESTED','CONFIRMED','REJECTED','STALE')),

  -- 确认后写回的逻辑客户。
  logical_customer_id uuid,

  -- 人工复核留痕（status != 'SUGGESTED' 时**必须**齐全，触发器强制）。
  reviewed_by uuid,
  reviewed_at timestamptz,
  review_note varchar(600) NOT NULL DEFAULT '',

  detected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, left_customer_id, right_customer_id),
  CHECK (left_customer_id < right_customer_id)
);

CREATE INDEX crm_customer_merge_candidate_by_status
  ON crm_customer_merge_candidate(tenant_id, status, confidence DESC, id);

CREATE INDEX crm_customer_merge_candidate_by_left
  ON crm_customer_merge_candidate(tenant_id, left_customer_id);

CREATE INDEX crm_customer_merge_candidate_by_right
  ON crm_customer_merge_candidate(tenant_id, right_customer_id);

-- ===========================================================================
-- 4. crm_customer_merge_operation —— 操作记录（只追加的审计流水）
-- ===========================================================================
CREATE TABLE crm_customer_merge_operation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- MERGE 合并 / SPLIT 拆分 / REJECT 拒绝候选。
  -- 🔴 REJECT 也在流水里：拒绝**也是**一个人的决定 ——
  --   事后要能回答「为什么不是同一个人」，而候选行只留最后一次复核结论，
  --   流水留的是「谁在什么时候拒的、理由是什么」。
  operation varchar(16) NOT NULL CHECK (operation IN ('MERGE','SPLIT','REJECT')),

  logical_customer_id uuid,
  -- 由哪个候选确认而来（拆分时为空）。
  candidate_id uuid,
  -- 拆分时被拆出来的那一条来源行。
  target_customer_id uuid,
  -- 操作后的成员数。
  member_count integer NOT NULL DEFAULT 0 CHECK (member_count >= 0),

  -- 理由**必填**：合并与拆分都是人的决定，必须能回答「为什么」。
  reason varchar(600) NOT NULL CHECK (btrim(reason) <> ''),

  operator_id uuid,
  occurred_at timestamptz NOT NULL,
  -- 快照（JSON）：本次操作涉及的来源行、信号、置信度等。
  details jsonb NOT NULL DEFAULT '{}'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

CREATE INDEX crm_customer_merge_operation_by_logical
  ON crm_customer_merge_operation(tenant_id, logical_customer_id, occurred_at DESC);

CREATE INDEX crm_customer_merge_operation_by_created
  ON crm_customer_merge_operation(tenant_id, occurred_at DESC, id DESC);

-- ===========================================================================
-- 5. crm_customer_merge_run —— 候选生成运行台账
-- ===========================================================================
CREATE TABLE crm_customer_merge_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- CM-yyyyMMdd-000001
  run_no varchar(32) NOT NULL DEFAULT '',

  -- SUCCEEDED 成功 / FAILED 中途失败（一条候选都不落）。
  status varchar(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),

  -- 扫描到的来源客户行数（**只数不同来源店铺之间可比的那些对**）。
  scanned_customer_count integer NOT NULL DEFAULT 0 CHECK (scanned_customer_count >= 0),
  -- 产出候选数（进入人工确认队列）。
  candidate_count integer NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
  -- 命中了信号但置信度不足（同名不同人那一档）：**不产出**，只计数。
  below_threshold_count integer NOT NULL DEFAULT 0 CHECK (below_threshold_count >= 0),
  -- 一侧已经属于某个逻辑客户 ⇒ 本轮不再提建议（已合并过的人不该被反复提）。
  excluded_already_merged_count integer NOT NULL DEFAULT 0 CHECK (excluded_already_merged_count >= 0),
  -- 两侧同属一个来源店铺 ⇒ 不算「跨店合并」，本模块不管。
  excluded_same_shop_count integer NOT NULL DEFAULT 0 CHECK (excluded_same_shop_count >= 0),
  -- 候选里带退订排除信号的条数。
  opt_out_candidate_count integer NOT NULL DEFAULT 0 CHECK (opt_out_candidate_count >= 0),
  -- 地址解析成功 / 失败的来源客户数（姓名+地址信号要靠它）。
  address_resolved_count integer NOT NULL DEFAULT 0 CHECK (address_resolved_count >= 0),
  address_unavailable_count integer NOT NULL DEFAULT 0 CHECK (address_unavailable_count >= 0),
  -- catalog 客户档案读不到的来源客户数（联系方式无从比较）。
  party_unavailable_count integer NOT NULL DEFAULT 0 CHECK (party_unavailable_count >= 0),
  -- 扫描被截断（名单不是全量），如实标出来。
  truncated boolean NOT NULL DEFAULT false,

  failure_reason varchar(600) NOT NULL DEFAULT '',
  -- 判定依据快照（JSON）。
  facts jsonb NOT NULL DEFAULT '{}'::jsonb,

  operator_id uuid,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, run_no)
);

CREATE INDEX crm_customer_merge_run_by_created
  ON crm_customer_merge_run(tenant_id, created_at DESC, id DESC);

-- ===========================================================================
-- 6. 触发器（**一律不用外键** —— 理由见文件头第七节）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 6.1 guard_crm_customer_merge_mapping_target · 映射必须指向存在的行
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_merge_mapping_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_ref varchar(128); v_shop varchar(64); v_logical uuid;
BEGIN
  SELECT customer_ref, source_shop INTO v_ref, v_shop FROM crm_customer_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.customer_profile_id;
  IF v_ref IS NULL THEN
    RAISE EXCEPTION '来源客户行 % 不存在（tenant=%），不能挂合并映射',
      NEW.customer_profile_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  SELECT id INTO v_logical FROM crm_customer_merge_logical
   WHERE tenant_id = NEW.tenant_id AND id = NEW.logical_customer_id;
  IF v_logical IS NULL THEN
    RAISE EXCEPTION '逻辑客户 % 不存在（tenant=%），不能挂合并映射',
      NEW.logical_customer_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_merge_mapping_target_trg
  BEFORE INSERT OR UPDATE ON crm_customer_merge_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_mapping_target();

-- ---------------------------------------------------------------------------
-- 6.2 guard_crm_customer_merge_mapping_state · 拆分只进不退，且必须留痕
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_merge_mapping_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.state IS DISTINCT FROM OLD.state AND NEW.state = 'ACTIVE' THEN
    RAISE EXCEPTION '已拆分的映射不能直接改回生效（映射 %）：重新合并请新写一行，历史那一行原样留着',
      OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.state = 'SPLIT' AND (
       NEW.split_at IS NULL OR btrim(NEW.split_reason) = '') THEN
    RAISE EXCEPTION '拆分必须留痕（映射 %）：拆分时刻与拆分理由缺一不可', OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.merged_at IS DISTINCT FROM OLD.merged_at THEN
    RAISE EXCEPTION '合并时刻不可改（映射 %）：它是「这次合并发生在什么时候」的唯一凭据',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_merge_mapping_state_trg
  BEFORE UPDATE ON crm_customer_merge_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_mapping_state();

-- ---------------------------------------------------------------------------
-- 6.3 guard_crm_customer_merge_candidate_target · 候选两侧必须都是真实来源行
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_merge_candidate_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_left uuid; v_right uuid;
BEGIN
  SELECT id INTO v_left FROM crm_customer_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.left_customer_id;
  IF v_left IS NULL THEN
    RAISE EXCEPTION '候选左侧客户行 % 不存在（tenant=%）',
      NEW.left_customer_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  SELECT id INTO v_right FROM crm_customer_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.right_customer_id;
  IF v_right IS NULL THEN
    RAISE EXCEPTION '候选右侧客户行 % 不存在（tenant=%）',
      NEW.right_customer_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_merge_candidate_target_trg
  BEFORE INSERT OR UPDATE ON crm_customer_merge_candidate
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_candidate_target();

-- ---------------------------------------------------------------------------
-- 6.4 guard_crm_customer_merge_candidate_order · 候选的两侧顺序不可变
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_merge_candidate_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.left_customer_id >= NEW.right_customer_id THEN
    RAISE EXCEPTION '候选的左侧必须小于右侧（% >= %）：顺序变了同一对客户就会变成两行候选',
      NEW.left_customer_id, NEW.right_customer_id USING ERRCODE='23514';
  END IF;
  -- 🔴 「两侧不可改」**只在 UPDATE 时检查**：INSERT 时 OLD 全是 NULL，
  --   与 NEW 比必然「不同」，那样会连正常的插入一起挡掉。
  IF TG_OP = 'UPDATE' THEN
    IF NEW.left_customer_id IS DISTINCT FROM OLD.left_customer_id
       OR NEW.right_customer_id IS DISTINCT FROM OLD.right_customer_id THEN
      RAISE EXCEPTION '候选的两侧不可改（候选 %）：改了它就不是同一对客户了',
        OLD.id USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_merge_candidate_order_trg
  BEFORE INSERT OR UPDATE ON crm_customer_merge_candidate
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_candidate_order();

-- ---------------------------------------------------------------------------
-- 6.5 guard_crm_customer_merge_candidate_review · 人工确认必须留痕，且只能向前推
-- ---------------------------------------------------------------------------
-- 🔴 「只给建议不自动执行」在候选表上的落点：**候选只能以 SUGGESTED 诞生**。
--   直接 INSERT 一条 status='CONFIRMED' 的候选（绕过服务层）会被挡下来 ——
--   没有人工复核就没有 CONFIRMED。
CREATE FUNCTION guard_crm_customer_merge_candidate_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status IS DISTINCT FROM 'SUGGESTED' THEN
      RAISE EXCEPTION '候选只能以 SUGGESTED（待确认）状态创建（候选 %）：确认与拒绝只能由人工复核动作写出',
        NEW.id USING ERRCODE='23514';
    END IF;
    IF NEW.reviewed_by IS NOT NULL OR NEW.reviewed_at IS NOT NULL THEN
      RAISE EXCEPTION '候选刚创建时不应带复核留痕（候选 %）：复核是人工动作，创建只是机器建议',
        NEW.id USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF OLD.status <> 'SUGGESTED' THEN
      RAISE EXCEPTION '候选 % 已经复核过（%），不能再改状态：复核结论是终态',
        OLD.id, OLD.status USING ERRCODE='23514';
    END IF;
    IF NEW.status NOT IN ('CONFIRMED','REJECTED','STALE') THEN
      RAISE EXCEPTION '候选 % 的状态只能推进到 CONFIRMED / REJECTED / STALE', OLD.id USING ERRCODE='23514';
    END IF;
  END IF;
  IF NEW.status <> 'SUGGESTED' AND (NEW.reviewed_by IS NULL OR NEW.reviewed_at IS NULL) THEN
    RAISE EXCEPTION '候选 % 已复核（%），复核人与复核时刻缺一不可', NEW.id, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_merge_candidate_review_trg
  BEFORE INSERT OR UPDATE ON crm_customer_merge_candidate
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_candidate_review();

-- ---------------------------------------------------------------------------
-- 6.5.1 guard_crm_customer_merge_mapping_confirmation · 映射必须由人工确认产生
-- ---------------------------------------------------------------------------
-- 🔴 「只给建议不自动执行」的**第二道**钉子：
--   一条 ACTIVE 映射等于「这两个人被合并了」，而合并**只能**由人工确认触发。
--   所以映射行插入时必须能找到一个**已被人工确认**的候选（该来源行是它的一侧）。
--   绕过服务层直接 INSERT 映射 ⇒ 找不到 CONFIRMED 候选 ⇒ 被挡。
CREATE FUNCTION guard_crm_customer_merge_mapping_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_confirmed integer;
BEGIN
  SELECT count(*) INTO v_confirmed FROM crm_customer_merge_candidate c
   WHERE c.tenant_id = NEW.tenant_id
     AND c.status = 'CONFIRMED'
     AND (c.left_customer_id = NEW.customer_profile_id OR c.right_customer_id = NEW.customer_profile_id);
  IF v_confirmed = 0 THEN
    RAISE EXCEPTION '来源客户行 % 没有一条**已人工确认**的候选：合并映射只能由人工确认动作写出（直接写 SQL 同样被挡）',
      NEW.customer_profile_id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_merge_mapping_confirmation_trg
  BEFORE INSERT ON crm_customer_merge_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_mapping_confirmation();

-- ---------------------------------------------------------------------------
-- 6.5.2 guard_crm_customer_merge_mapping_no_delete · 映射行不删，只标 SPLIT
-- ---------------------------------------------------------------------------
-- 🔴 「保留合并前映射可拆分」的落点：映射行**不可删除**。
--   删一行就等于「把合并痕迹抹掉」—— 那既不是拆分（拆分是 ACTIVE → SPLIT 并留痕），
--   也会让「这个人被合并过、后来拆开、又合并过」在审计流水里查不到。
CREATE FUNCTION guard_crm_customer_merge_mapping_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '合并映射行不可删除（%）：拆分是把 state 改成 SPLIT 并留痕，不是抹掉痕迹',
    OLD.id USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_customer_merge_mapping_no_delete_trg
  BEFORE DELETE ON crm_customer_merge_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_mapping_no_delete();

-- ---------------------------------------------------------------------------
-- 6.6 guard_crm_customer_merge_operation_append_only · 操作记录只追加
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_merge_operation_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '合并操作记录只追加，不改不删（%）：它是「谁在什么时候把谁合并了」的凭据',
    OLD.id USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_customer_merge_operation_append_only_trg
  BEFORE UPDATE OR DELETE ON crm_customer_merge_operation
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_operation_append_only();

-- ---------------------------------------------------------------------------
-- 6.7 guard_crm_customer_merge_logical_state · 已解散的逻辑客户不能再改成员
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_merge_logical_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'DISSOLVED' AND NEW.member_count <> OLD.member_count THEN
    RAISE EXCEPTION '逻辑客户 % 已解散，不能再改成员数', OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'DISSOLVED' AND NEW.member_count <> 0 THEN
    RAISE EXCEPTION '逻辑客户 % 解散时成员数必须为 0（实际 %）',
      NEW.id, NEW.member_count USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_merge_logical_state_trg
  BEFORE UPDATE ON crm_customer_merge_logical
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_merge_logical_state();

-- ===========================================================================
-- 7. RLS（ENABLE + FORCE）与授权
-- ===========================================================================
-- 与 V99 / V220 / V299 / V432 同一口径：策略名固定 isolation，
-- USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'crm_customer_merge_logical',
   'crm_customer_merge_mapping',
   'crm_customer_merge_candidate',
   'crm_customer_merge_operation',
   'crm_customer_merge_run'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_customer_merge_logical, crm_customer_merge_mapping, crm_customer_merge_candidate,
  crm_customer_merge_operation, crm_customer_merge_run TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_customer_merge_logical, crm_customer_merge_mapping, crm_customer_merge_candidate,
  crm_customer_merge_operation, crm_customer_merge_run TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一次生成候选就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_customer_merge_run_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_customer_merge_run_no_seq TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_customer_merge_logical_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_customer_merge_logical_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ===========================================================================
-- 8. 留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **15.3.1.2 客户详情**（并行车道）：它聚合跨店铺身份关系时读本模块的
--     `crm_customer_merge_mapping`（ACTIVE 行）与 `crm_customer_merge_logical` ——
--     本模块**只写**自己的表，它去读是跨模块只读，纪律不变。
--   * **13 分析（客户价值跨店汇总）**：读本模块的逻辑客户表按**币种分行**聚合，
--     跨币种仍然不相加。
--   * **15.3.1.1 客户列表**：「按逻辑客户展示」是它的**下游增强**（读本模块的映射），
--     本模块**不写**它的表 —— 它的写入所有权仍然是它自己的。
