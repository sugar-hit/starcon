-- ===========================================================================
-- 16.5.1.1 黑名单 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 203-208 行）：
--   ### 16.5.1.1 黑名单
--   - 职责：维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），
--           下单时自动标记并可拦截。
--   - 输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
--   - 输出：黑名单记录；订单拦截标记。
--   - 关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。
--     **黑名单应支持申诉解除**，永久不解除会让误判的正常买家永久损失。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本迁移存在的理由：**黑名单是会伤到真人的名单**
-- ---------------------------------------------------------------------------
-- 「黑名单」是本仓里少数几个「写错一行就真的挡住一个真人订单」的地方。所以它的表结构
-- 照着「判定凭据」而不是「普通业务表」来设计：
--   * `buyer_blacklist_entry`        —— **登记凭据**，append-only：原始登记（谁、何时、
--                                      为什么、什么范围）**一次写入就不可改**。
--   * `buyer_blacklist_status_event` —— **状态流转留痕**，append-only：生效 / 解除 /
--                                      申诉通过 / 申诉驳回 / 升级 / 重新生效，
--                                      **每一次都留一行**（谁、何时、为什么）。
--   * `buyer_blacklist_appeal`       —— **申诉单**：提交 → 裁决（通过 ⇒ 解除；驳回 ⇒ 维持）。
--   * `buyer_blacklist_order_flag`   —— **订单拦截标记**：下单时命中黑名单的那一笔事实。
--
-- 🔴 **加粗句「黑名单应支持申诉解除，永久不解除会让误判的正常买家永久损失」的物理落点**：
--   1. 解除**不是**把登记行删掉或改掉，而是**追加一条状态流转事件**（actor + 理由 + 时刻）；
--   2. 解除**必须**有申诉或人工理由 —— 没有理由的解除等于「悄悄放过」，客服无从解释；
--   3. 解除**可逆**：解除后复发走 REINSTATED 事件，历史一条不少；
--   4. 登记行**不可 UPDATE / DELETE**（触发器直接 RAISE）—— 历史不可抹掉。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 零 ALTER 任何既有表、零跨服务外键
-- ---------------------------------------------------------------------------
-- 本表与 channel V311/V312/V313/V315/V316 **同批并行**开发 ⇒ 本迁移**纯新建表**，
-- **零 ALTER 任何既有表**、**零 DROP**、**零跨服务外键**（与 channel V315/V316 同一纪律）。
-- 跨表引用（status_event → entry、appeal → entry、order_flag → entry）**不建外键**，
-- 改由 BEFORE INSERT 触发器校验「指向的 entry 必须真实存在且同租户」。
--
-- 🔴 **刻意不碰 16.5.1.2 的 `buyer_risk_score`**：
--   上游的 `buyer_risk_score.blacklisted` / `blacklist_reason` 两列**不是**本模块的回写目标位 ——
--   实读上游代码（`BuyerRiskScoreService` 第 558-559 行）确认：这两列是**评分时刻**
--   从 trade 的 `order_review_buyer_profile` 抄来的**快照**，而且 V316 给整张表挂了
--   `BEFORE UPDATE OR DELETE` 的 append-only 触发器（`guard_buyer_risk_immutable`），
--   **任何 UPDATE 都会被直接 RAISE 掉**。
--   ⇒ 本模块**不改**它（改了要么被上游触发器拒，要么就得 ALTER 上游的触发器 = 跨车道 DDL 回归面）。
--   本模块**自建**黑名单登记表承载权威事实；16.5.1.2 将来若要在评分里体现「已被拉黑」，
--   由**它自己**读本模块（同服务同库 jOOQ 直读），那是它的改动，不是本车道能代劳的。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 命名空间隔离
-- ---------------------------------------------------------------------------
-- 本模块 ⇒ `buyer_blacklist_*`。
-- 与既有命名空间彼此不同：channel 库的 `buyer_risk_*`（16.5.1.2）、`compliance_rule_*` /
-- `compliance_intercept_*`（16.2.x）、`epr_*`（16.3.2.2）、`ip_risk_*`（16.4.1.1）、
-- `listing_compliance_*`（4.2.3.2）、`platform_violation_*`（16.1.2.1）、`tax_registration_*`。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 多租户隔离：tenant_id NOT NULL + ENABLE **与 FORCE** ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- 与 channel V311/V312/V313/V315/V316 同一套写法。租户由 backend/libraries/tenancy 在事务内
-- 设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
-- 授权：GRANT channel_app / REVOKE starcon_app（跨服务只读角色拿不到任何业务表）。
--
-- 🔴 **只带 isolation 策略，不带 data_scope（RESTRICTIVE）策略**：
--   买家黑名单是**租户级主体**的名单，不是某家店 / 某个部门的资产。硬套店铺收敛会让
--   「这个买家在全租户范围内的黑名单事实在只有 SELF 范围的成员面前凭空消失」。
--
-- ===========================================================================
-- 一、buyer_blacklist_entry · 黑名单登记凭据（append-only）
-- ===========================================================================
-- 🔴 **登记行一次写入就不可改**：原始登记（谁、何时、为什么、什么范围、什么等级）
--    永远是当时的原样。解除 / 升级 / 申诉的结果一律**追加到 status_event**，
--    由「最新一条事件」派生出当前状态。
--
-- 🔴 **主体键 buyer_key**：md5(收件人三件套)，与 trade 的
--    `order_review_buyer_profile.buyer_key`、channel 的 `buyer_risk_score.buyer_key`
--    **逐字同一公式**（分隔符是**不可见字符** U+001F，不是 '|' ——
--    收件人字段由运营自由录入，'|' 会让两组不同字段拼出同一个串，那是一次跨买家的错误合并）。
--
-- 🔴 **生效范围 scope 是受控值域**（DB 层 CHECK 钉死）：
--    SHOP_ONLY —— 只在本店铺生效，此时 shop_code **必填**；
--    ALL_SHOPS —— 全店铺生效，此时 shop_code **必须为空串**。
--    两条 CHECK 互相咬死（`CHECK((scope='ALL_SHOPS') = (btrim(shop_code)=''))`），
--    杜绝「范围说全店铺、店铺却填了一个」这种自相矛盾的登记。
CREATE TABLE buyer_blacklist_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 主体键：md5(收件人三件套)，与 trade / channel 同口径。
  buyer_key char(32) NOT NULL CHECK(buyer_key ~ '^[0-9a-f]{32}$'),
  -- 可读快照：md5 谁也念不出来，客服要能对得上人。
  buyer_name  varchar(120) NOT NULL DEFAULT '',
  buyer_phone varchar(64)  NOT NULL DEFAULT '',
  buyer_email varchar(254) NOT NULL DEFAULT '',

  -- 🔴 生效范围（受控值域）。
  scope varchar(16) NOT NULL CHECK(scope IN ('SHOP_ONLY','ALL_SHOPS')),
  -- SHOP_ONLY 时是本租户某个真实店铺的 code；ALL_SHOPS 时恒为空串。
  shop_code varchar(64) NOT NULL DEFAULT '',

  -- 风险等级（受控值域）。
  risk_level varchar(16) NOT NULL CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  -- 原因必填：没有理由的黑名单，客服无法向买家解释。
  reason varchar(600) NOT NULL CHECK(btrim(reason) <> ''),

  -- 来源：MANUAL 手工录入 / RISK_SIGNAL 自动（来自风控信号）/ SCORE_RULE 由评分规则自动。
  source varchar(24) NOT NULL CHECK(source IN ('MANUAL','RISK_SIGNAL','SCORE_RULE')),
  -- 自动来源**必须**能追溯到触发它的信号 / 评分（否则没人说得清这条黑名单哪来的）。
  source_ref varchar(160) NOT NULL DEFAULT '',

  -- 登记时刻与登记人（谁把它拉黑的 —— 解除留痕要能对上这条）。
  registered_at timestamptz NOT NULL,
  registered_by varchar(120) NOT NULL CHECK(btrim(registered_by) <> ''),
  note varchar(600) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 scope 与 shop_code 互相咬死：全店铺 ⇔ 店铺为空。
  CHECK((scope = 'ALL_SHOPS') = (btrim(shop_code) = '')),
  -- 🔴 手工录入不许带信号引用（那是自动来源的痕迹）；自动来源必须带。
  CHECK((source = 'RISK_SIGNAL') OR (source = 'SCORE_RULE') OR btrim(source_ref) = ''),
  CHECK(source = 'MANUAL' OR btrim(source_ref) <> '')
);
COMMENT ON TABLE buyer_blacklist_entry IS
  '16.5.1.1 · 黑名单登记凭据（append-only）。原始登记一次写入就不可改：'
  '解除 / 升级 / 申诉一律追加到 buyer_blacklist_status_event，当前状态由最新一条事件派生。'
  'scope 是受控值域（SHOP_ONLY / ALL_SHOPS），与 shop_code 互相咬死。';

CREATE INDEX buyer_blacklist_entry_by_buyer
  ON buyer_blacklist_entry(tenant_id, buyer_key, registered_at DESC);
CREATE INDEX buyer_blacklist_entry_by_scope
  ON buyer_blacklist_entry(tenant_id, scope, shop_code);

-- ===========================================================================
-- 二、buyer_blacklist_status_event · 状态流转留痕（append-only）
-- ===========================================================================
-- 🔴 **这张表是「黑名单应支持申诉解除」的物理落点**：解除不是 UPDATE 掉登记行，
--    而是**追加一条事件**（谁 / 何时 / 为什么）。历史一条不少。
--
-- 🔴 **状态机**（from → to）：
--    REGISTERED      ACTIVE → ACTIVE  （seq 恒为 1，登记那一刻）
--    ESCALATED       ACTIVE → ACTIVE  （升级风险等级；等级只升不降，触发器钉死）
--    APPEAL_REJECTED ACTIVE → ACTIVE  （申诉驳回，维持黑名单）
--    RELEASED        ACTIVE → RELEASED（人工解除）
--    APPEAL_APPROVED ACTIVE → RELEASED（申诉通过并解除）
--    REINSTATED      RELEASED → ACTIVE（解除后复发，重新生效）
--
-- 🔴 **每一条事件都带 reason**（人话理由）：没有理由的解除等于「悄悄放过」。
CREATE TABLE buyer_blacklist_status_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 逻辑引用（不建外键，见文件头第二节）；由触发器校验它真实存在且同租户。
  entry_id uuid NOT NULL,

  -- 同一 entry 内单调递增的序号（1 起）。
  event_seq int NOT NULL CHECK(event_seq > 0),

  from_status varchar(16) NOT NULL CHECK(from_status IN ('ACTIVE','RELEASED')),
  to_status   varchar(16) NOT NULL CHECK(to_status   IN ('ACTIVE','RELEASED')),
  event_type  varchar(24) NOT NULL CHECK(event_type IN (
      'REGISTERED','ESCALATED','APPEAL_REJECTED','RELEASED','APPEAL_APPROVED','REINSTATED')),
  -- 事件发生**当时**的风险等级（当前等级 = 最新一条事件的等级）。
  risk_level varchar(16) NOT NULL CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),

  -- 谁做的（人工操作写操作人；自动事件写系统标识）。
  actor varchar(120) NOT NULL CHECK(btrim(actor) <> ''),
  -- 为什么（**每条事件都必填**）。
  reason varchar(600) NOT NULL CHECK(btrim(reason) <> ''),
  -- 申诉类事件必须指向一张真实的申诉单。
  appeal_id uuid,

  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, entry_id, event_seq),
  -- 🔴 第一条只能是 REGISTERED。
  CHECK((event_type = 'REGISTERED') = (event_seq = 1)),
  -- 🔴 申诉类事件与 appeal_id 一一对应。
  CHECK((event_type IN ('APPEAL_APPROVED','APPEAL_REJECTED')) = (appeal_id IS NOT NULL))
);
COMMENT ON TABLE buyer_blacklist_status_event IS
  '16.5.1.1 · 黑名单状态流转留痕（append-only）。解除 / 升级 / 申诉裁决**全部追加到这里**，'
  '原始登记行不可改。当前状态 = 最新一条事件的 to_status。';

CREATE INDEX buyer_blacklist_status_event_by_entry
  ON buyer_blacklist_status_event(tenant_id, entry_id, event_seq);

-- ===========================================================================
-- 三、buyer_blacklist_appeal · 申诉单（可裁决一次）
-- ===========================================================================
-- 🔴 蓝图加粗句「黑名单应支持申诉解除，永久不解除会让误判的正常买家永久损失」的落点之二：
--    申诉是**一张单子**，不是一个布尔位。谁提交的、什么时候、什么理由、被谁裁决、
--    裁决理由是什么 —— 全部留在这张表里。
--    裁决 PENDING → APPROVED / REJECTED 由触发器钉死**只许走一次**。
CREATE TABLE buyer_blacklist_appeal (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 逻辑引用（不建外键）；由触发器校验它真实存在且同租户。
  entry_id uuid NOT NULL,

  appeal_reason varchar(800) NOT NULL CHECK(btrim(appeal_reason) <> ''),
  submitted_by varchar(120) NOT NULL CHECK(btrim(submitted_by) <> ''),
  submitted_at timestamptz NOT NULL,

  -- PENDING 待裁决 / APPROVED 通过（并解除）/ REJECTED 驳回（维持黑名单）。
  decision varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(decision IN ('PENDING','APPROVED','REJECTED')),
  decided_by varchar(120) NOT NULL DEFAULT '',
  decided_at timestamptz,
  decision_reason varchar(800) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 待裁决 ⇔ 还没有裁决时刻 / 裁决人 / 裁决理由（三样一起齐）。
  CHECK((decision = 'PENDING') = (decided_at IS NULL)),
  CHECK(decision = 'PENDING' OR btrim(decided_by) <> ''),
  CHECK(decision = 'PENDING' OR btrim(decision_reason) <> ''),
  CHECK(decision = 'PENDING' OR decided_at IS NOT NULL)
);
COMMENT ON TABLE buyer_blacklist_appeal IS
  '16.5.1.1 · 黑名单申诉单。谁提交、什么时候、什么理由、被谁裁决、裁决理由，全部留痕。'
  '裁决只许走一次（PENDING → APPROVED / REJECTED，由触发器钉死）。';

CREATE INDEX buyer_blacklist_appeal_by_entry
  ON buyer_blacklist_appeal(tenant_id, entry_id, submitted_at DESC);

-- ===========================================================================
-- 四、buyer_blacklist_order_flag · 订单拦截标记（append-only）
-- ===========================================================================
-- 🔴 蓝图输出里的「订单拦截标记」：**下单那一刻**命中黑名单的事实。
--    它是**时点事实**，不是状态 —— 一笔被标记过的订单不会因为后来解除了黑名单
--    就变成「当时没被标记」（那会是一次事后改写事实）。
--    所以这张表 append-only：只追加，不改，不删。
CREATE TABLE buyer_blacklist_order_flag (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  buyer_key char(32) NOT NULL CHECK(buyer_key ~ '^[0-9a-f]{32}$'),
  -- 下单的那家店（'' = 调用方没给店铺，按全店铺口径判）。
  shop_code varchar(64) NOT NULL DEFAULT '',
  -- 外部订单引用（trade 的 order id / order no 原样存）。
  order_ref varchar(160) NOT NULL DEFAULT '',

  -- 命中的是哪一条登记。
  entry_id uuid NOT NULL,
  -- 命中时那条登记的生效范围（SHOP_ONLY / ALL_SHOPS）与当时的风险等级。
  matched_scope varchar(16) NOT NULL CHECK(matched_scope IN ('SHOP_ONLY','ALL_SHOPS')),
  risk_level varchar(16) NOT NULL CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  -- 🔴 建议动作：**本模块只出建议**，真的拦不拦单是 9.2.1.3 的事。
  recommended_action varchar(16) NOT NULL CHECK(recommended_action IN ('BLOCK','REVIEW','WATCH')),

  flagged_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE buyer_blacklist_order_flag IS
  '16.5.1.1 · 订单拦截标记（append-only）。下单那一刻命中黑名单的**时点事实**：'
  '后续解除黑名单不会改写这一笔（那会是一次事后改写事实）。'
  'recommended_action 只是**建议** —— 真的拦不拦单是 9.2.1.3 的职责。';

CREATE INDEX buyer_blacklist_order_flag_by_buyer
  ON buyer_blacklist_order_flag(tenant_id, buyer_key, flagged_at DESC);
CREATE INDEX buyer_blacklist_order_flag_by_action
  ON buyer_blacklist_order_flag(tenant_id, recommended_action, flagged_at DESC);

-- ===========================================================================
-- 五、DB 守卫
-- ===========================================================================
-- ---------------------------------------------------------------------------
-- 5.1 登记行与状态事件**不可 UPDATE / DELETE**（append-only）
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_buyer_blacklist_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是凭据：不可修改也不可删除（解除 / 升级 / 申诉请追加状态流转事件）',
    TG_TABLE_NAME
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER buyer_blacklist_entry_immutable
  BEFORE UPDATE OR DELETE ON buyer_blacklist_entry
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_immutable();

CREATE TRIGGER buyer_blacklist_status_event_immutable
  BEFORE UPDATE OR DELETE ON buyer_blacklist_status_event
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_immutable();

CREATE TRIGGER buyer_blacklist_order_flag_immutable
  BEFORE UPDATE OR DELETE ON buyer_blacklist_order_flag
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_immutable();

-- ---------------------------------------------------------------------------
-- 5.2 状态机守卫：事件链必须首尾相接，序号必须连号
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_buyer_blacklist_event_chain() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_prev_seq    int;
  v_prev_status varchar(16);
  v_prev_level  varchar(16);
  v_prev_type   varchar(24);
  v_entry_level varchar(16);
  v_new_severity    int;
  v_prev_severity   int;
BEGIN
  -- 指向的登记必须真实存在且同租户。
  SELECT e.risk_level INTO v_entry_level
    FROM buyer_blacklist_entry e
   WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entry_id;
  IF v_entry_level IS NULL THEN
    RAISE EXCEPTION '黑名单登记 % 不存在或不属于当前租户', NEW.entry_id
      USING ERRCODE='23514';
  END IF;

  -- 申诉类事件必须指向一张**真实存在、且属于同一条登记**的申诉单。
  IF NEW.appeal_id IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM buyer_blacklist_appeal a
                    WHERE a.tenant_id = NEW.tenant_id
                      AND a.id = NEW.appeal_id
                      AND a.entry_id = NEW.entry_id) THEN
      RAISE EXCEPTION '申诉单 % 不存在、不属于当前租户，或不属于这条黑名单登记', NEW.appeal_id
        USING ERRCODE='23514';
    END IF;
  END IF;

  SELECT ev.event_seq, ev.to_status, ev.risk_level, ev.event_type
    INTO v_prev_seq, v_prev_status, v_prev_level, v_prev_type
    FROM buyer_blacklist_status_event ev
   WHERE ev.tenant_id = NEW.tenant_id AND ev.entry_id = NEW.entry_id
   ORDER BY ev.event_seq DESC
   LIMIT 1;

  IF v_prev_seq IS NULL THEN
    -- 第一条：必须是 REGISTERED，且序号为 1。
    IF NEW.event_type <> 'REGISTERED' OR NEW.event_seq <> 1 THEN
      RAISE EXCEPTION '一条黑名单登记的第一条状态事件必须是 REGISTERED（当前是 %，序号 %）',
        NEW.event_type, NEW.event_seq
        USING ERRCODE='23514';
    END IF;
    IF NEW.from_status <> 'ACTIVE' OR NEW.to_status <> 'ACTIVE' THEN
      RAISE EXCEPTION '登记事件的状态必须是 ACTIVE → ACTIVE' USING ERRCODE='23514';
    END IF;
    IF NEW.risk_level <> v_entry_level THEN
      RAISE EXCEPTION '登记事件的风险等级（%）必须与登记行一致（%）',
        NEW.risk_level, v_entry_level
        USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;

  -- 非第一条：序号必须连号，from_status 必须等于上一条的 to_status。
  IF NEW.event_seq <> v_prev_seq + 1 THEN
    RAISE EXCEPTION '状态事件序号必须连号（上一条 %，当前 %）', v_prev_seq, NEW.event_seq
      USING ERRCODE='23514';
  END IF;
  IF NEW.from_status <> v_prev_status THEN
    RAISE EXCEPTION '状态事件接不上：上一条 %（%）当前状态是 %，本条却从 % 起',
      v_prev_type, v_prev_status, v_prev_status, NEW.from_status
      USING ERRCODE='23514';
  END IF;

  -- 逐事件类型的合法迁移。
  IF NEW.event_type IN ('ESCALATED','APPEAL_REJECTED') THEN
    IF NEW.from_status <> 'ACTIVE' OR NEW.to_status <> 'ACTIVE' THEN
      RAISE EXCEPTION '% 只能在生效中的黑名单上发生（% → %）',
        NEW.event_type, NEW.from_status, NEW.to_status
        USING ERRCODE='23514';
    END IF;
  ELSIF NEW.event_type IN ('RELEASED','APPEAL_APPROVED') THEN
    IF NEW.from_status <> 'ACTIVE' OR NEW.to_status <> 'RELEASED' THEN
      RAISE EXCEPTION '% 只能把生效中的黑名单解除掉（% → %）',
        NEW.event_type, NEW.from_status, NEW.to_status
        USING ERRCODE='23514';
    END IF;
  ELSIF NEW.event_type = 'REINSTATED' THEN
    IF NEW.from_status <> 'RELEASED' OR NEW.to_status <> 'ACTIVE' THEN
      RAISE EXCEPTION 'REINSTATED 只能让已解除的黑名单重新生效（% → %）',
        NEW.from_status, NEW.to_status
        USING ERRCODE='23514';
    END IF;
  ELSE
    RAISE EXCEPTION '未知的状态事件类型 %', NEW.event_type USING ERRCODE='23514';
  END IF;

  -- 🔴 风险等级**只升不降**：降级必须走「先解除、再重新登记」，不能就地降。
  --
  -- 🔴 **严重度必须先算进变量再比较**：PL/pgSQL 的 IF 条件里**不能直接写 CASE** ——
  --   `IF CASE ... WHEN ... THEN ... END THEN` 里的那个 THEN 会被 plpgsql 当成
  --   **IF 自己的 THEN**，于是条件被截断、函数在解析期就报「syntax error at end of input」。
  --   （写 SQL 时极容易踩，因为纯 SQL 里它完全合法。）
  v_new_severity := CASE NEW.risk_level
                       WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3
                       WHEN 'MEDIUM' THEN 2 ELSE 1 END;
  v_prev_severity := CASE v_prev_level
                       WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3
                       WHEN 'MEDIUM' THEN 2 ELSE 1 END;
  IF NEW.event_type = 'ESCALATED' THEN
    IF v_new_severity <= v_prev_severity THEN
      RAISE EXCEPTION '风险等级只升不降（当前 %，上一条 %）', NEW.risk_level, v_prev_level
        USING ERRCODE='23514';
    END IF;
  ELSE
    IF NEW.risk_level <> v_prev_level THEN
      RAISE EXCEPTION '除 ESCALATED 外风险等级不得变动（当前 %，上一条 %）',
        NEW.risk_level, v_prev_level
        USING ERRCODE='23514';
    END IF;
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER buyer_blacklist_status_event_chain
  BEFORE INSERT ON buyer_blacklist_status_event
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_event_chain();

-- ---------------------------------------------------------------------------
-- 5.3 同一个买家在同一生效范围下**只能有一条生效中**的黑名单
-- ---------------------------------------------------------------------------
-- 🔴 这是**延迟约束触发器**（DEFERRABLE INITIALLY DEFERRED）：一个事务里
--    「登记新的一条」与「解除旧的一条」必须能一起提交（否则运营换一条登记就没法做），
--    而「两条同时生效」又必须被拒 —— 只有延迟到提交那一刻才判得准。
CREATE FUNCTION guard_buyer_blacklist_active_unique() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_buyer_key char(32);
  v_scope     varchar(16);
  v_shop      varchar(64);
  v_count     int;
BEGIN
  SELECT e.buyer_key, e.scope, e.shop_code INTO v_buyer_key, v_scope, v_shop
    FROM buyer_blacklist_entry e
   WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entry_id;
  IF v_buyer_key IS NULL THEN
    RETURN NULL;
  END IF;

  SELECT count(*) INTO v_count
    FROM buyer_blacklist_entry e
    JOIN LATERAL (
      SELECT ev.to_status
        FROM buyer_blacklist_status_event ev
       WHERE ev.tenant_id = e.tenant_id AND ev.entry_id = e.id
       ORDER BY ev.event_seq DESC
       LIMIT 1
    ) s ON true
   WHERE e.tenant_id = NEW.tenant_id
     AND e.buyer_key = v_buyer_key
     AND e.scope = v_scope
     AND e.shop_code = v_shop
     AND s.to_status = 'ACTIVE';

  IF v_count > 1 THEN
    RAISE EXCEPTION '同一个买家在同一生效范围下只能有一条生效中的黑名单（当前 % 条）', v_count
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER buyer_blacklist_active_unique
  AFTER INSERT ON buyer_blacklist_status_event
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_active_unique();

-- ---------------------------------------------------------------------------
-- 5.4 每条登记**至少要有一条状态事件**（延迟约束）
-- ---------------------------------------------------------------------------
-- 没有事件 ⇒ 这条登记**没有当前状态**，会从所有列表里凭空消失。
CREATE FUNCTION guard_buyer_blacklist_entry_has_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_count int;
BEGIN
  SELECT count(*) INTO v_count
    FROM buyer_blacklist_status_event ev
   WHERE ev.tenant_id = NEW.tenant_id AND ev.entry_id = NEW.id;
  IF v_count = 0 THEN
    RAISE EXCEPTION '黑名单登记 % 至少要有一条状态事件（当前没有）', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER buyer_blacklist_entry_has_event
  AFTER INSERT ON buyer_blacklist_entry
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_entry_has_event();

-- ---------------------------------------------------------------------------
-- 5.5 店铺必须真实存在且属于当前租户（零外键，改用触发器）
-- ---------------------------------------------------------------------------
-- 🔴 「仅本店铺生效」的黑名单如果挂在一个**不存在的店铺**上，那条黑名单**永远不会生效**
--    （判定时按店铺过滤，过滤不到）—— 运营会以为「已经拉黑了 A 店」，其实没有。
CREATE FUNCTION guard_buyer_blacklist_shop_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.scope = 'SHOP_ONLY' THEN
    IF NOT EXISTS (SELECT 1 FROM shop s
                    WHERE s.tenant_id = NEW.tenant_id AND s.code = NEW.shop_code) THEN
      RAISE EXCEPTION '店铺 % 不存在或不属于当前租户', NEW.shop_code
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER buyer_blacklist_entry_shop_reference
  BEFORE INSERT ON buyer_blacklist_entry
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_shop_reference();

-- ---------------------------------------------------------------------------
-- 5.6 申诉单与拦截标记的引用完整性（同租户）
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_buyer_blacklist_appeal_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM buyer_blacklist_entry e
                  WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entry_id) THEN
    RAISE EXCEPTION '黑名单登记 % 不存在或不属于当前租户', NEW.entry_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER buyer_blacklist_appeal_reference
  BEFORE INSERT ON buyer_blacklist_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_appeal_reference();

CREATE FUNCTION guard_buyer_blacklist_flag_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM buyer_blacklist_entry e
                  WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entry_id) THEN
    RAISE EXCEPTION '黑名单登记 % 不存在或不属于当前租户', NEW.entry_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER buyer_blacklist_order_flag_reference
  BEFORE INSERT ON buyer_blacklist_order_flag
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_flag_reference();

-- ---------------------------------------------------------------------------
-- 5.7 申诉**只许裁决一次**，且**只能改裁决那几列**
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_buyer_blacklist_appeal_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id <> OLD.tenant_id
     OR NEW.id <> OLD.id
     OR NEW.entry_id <> OLD.entry_id
     OR NEW.appeal_reason <> OLD.appeal_reason
     OR NEW.submitted_by <> OLD.submitted_by
     OR NEW.submitted_at <> OLD.submitted_at THEN
    RAISE EXCEPTION '申诉单的事实部分（提交人 / 提交时刻 / 申诉理由）不可修改'
      USING ERRCODE='23514';
  END IF;
  IF OLD.decision <> 'PENDING' THEN
    RAISE EXCEPTION '这张申诉单已经裁决过（%），不可二次裁决', OLD.decision
      USING ERRCODE='23514';
  END IF;
  IF NEW.decision NOT IN ('APPROVED','REJECTED') THEN
    RAISE EXCEPTION '裁决只能是 APPROVED 或 REJECTED（当前 %）', NEW.decision
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER buyer_blacklist_appeal_decision
  BEFORE UPDATE ON buyer_blacklist_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_appeal_decision();

-- 🔴 裁决与「状态流转事件」必须**对得上**：申诉被批准 ⇒ 必须有一条 APPEAL_APPROVED 事件
--    指向它（否则库里会出现「申诉已批准、但黑名单还生效着」的自相矛盾状态）。
CREATE FUNCTION guard_buyer_blacklist_appeal_event_match() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_expected varchar(24);
  v_count    int;
BEGIN
  IF NEW.decision = 'PENDING' THEN
    RETURN NULL;
  END IF;
  v_expected := CASE WHEN NEW.decision = 'APPROVED' THEN 'APPEAL_APPROVED' ELSE 'APPEAL_REJECTED' END;

  SELECT count(*) INTO v_count
    FROM buyer_blacklist_status_event ev
   WHERE ev.tenant_id = NEW.tenant_id
     AND ev.entry_id = NEW.entry_id
     AND ev.appeal_id = NEW.id
     AND ev.event_type = v_expected;
  IF v_count = 0 THEN
    RAISE EXCEPTION '申诉 % 的裁决（%）必须配一条 % 状态事件',
      NEW.id, NEW.decision, v_expected
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER buyer_blacklist_appeal_event_match
  AFTER UPDATE ON buyer_blacklist_appeal
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_blacklist_appeal_event_match();

-- ===========================================================================
-- 六、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['buyer_blacklist_entry','buyer_blacklist_status_event',
                              'buyer_blacklist_appeal','buyer_blacklist_order_flag'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
-- 🔴 **刻意不建 data_scope（RESTRICTIVE）策略**：黑名单是**租户级主体**的名单，
--   不是某家店 / 某个部门的资产（与 V316 的 buyer_risk_* 同一处理）。

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

GRANT USAGE ON SCHEMA public TO channel_app;
-- 🔴 **append-only 表：刻意只给 SELECT,INSERT，不给 UPDATE / DELETE**
--   （登记凭据与状态事件都不可改 —— 由上面的触发器二次兜底）。
GRANT SELECT,INSERT ON buyer_blacklist_entry TO channel_app;
GRANT SELECT,INSERT ON buyer_blacklist_status_event TO channel_app;
GRANT SELECT,INSERT ON buyer_blacklist_order_flag TO channel_app;
-- 申诉单带**一次性的裁决**（PENDING → APPROVED / REJECTED），所以给 UPDATE，
-- 但**不给 DELETE**（申诉历史不可删）。
GRANT SELECT,INSERT,UPDATE ON buyer_blacklist_appeal TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V316 同口径）。
REVOKE ALL ON buyer_blacklist_entry FROM starcon_app;
REVOKE ALL ON buyer_blacklist_status_event FROM starcon_app;
REVOKE ALL ON buyer_blacklist_appeal FROM starcon_app;
REVOKE ALL ON buyer_blacklist_order_flag FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;
