-- 15.3.2.2 接触记录：记录与客户的所有接触（客服通话、消息、邮件、营销触达、售后沟通），
-- 可由各模块自动写入，支持时间线检索。
--
-- 蓝图 15 卷 15.3.2.2 的原话：
--   「职责：记录与客户的所有接触（客服通话、消息、邮件、营销触达、售后沟通），
--     可由各模块自动写入，支持时间线检索。
--     输入：各模块接触事件；手工补录。
--     输出：接触时间线。
--     关系：上游 = 15.1、11 售后、15.5.2；下游 = 15.3.1.2。
--     **接触记录是自动化的豁免依据**——曾明确表示"不要再联系我"的客户，营销触达需排除。」
--
-- ============================================================ 零、本文件与既有模块的边界
--
-- 本文件是 **crm 数据库的第七个迁移**（V99 = 15.4.2.1 知识库，V220 = 15.2.2.1 索评请求，
-- V228 = 15.2.2.2 索评规则，V292 = 15.1.2.1 买家邮件，V293 = 15.1.1.1 消息收件箱，
-- V295 = 15.3.2.1 客户标签，V296 = 15.6.2.2 购物车挽回）。
--
-- 与兄弟模块的边界 —— 三者**不共用任何一张表**，写入所有者也各自独立：
--   * V293（消息收件箱）：回答「平台/买家跟我们说了什么」。
--   * V292（买家邮件）：回答「邮件怎么回、回了没有」。
--   * V295（本卷兄弟 客户标签）：回答「这位买家属于哪一类人」。
--   * V299（本文件）：回答「我们与这位客户**什么时候、以什么方式**接触过，
--     以及**他现在是不是还允许我们营销他**」。
-- ⇒ 本文件**不改 V99 / V220 / V228 / V292 / V293 / V295 / V296 的任何一张表的结构**
--   （零 ALTER、零 DROP、零改列）。
--
-- ============================================================ 一、跨服务纪律（零跨库 SQL / FK / 视图）
--
-- 上游事实的归属：
--   * **15.1 消息收件箱**（V293，`crm_message`）与 **15.1.2.1 买家邮件**（V292，
--     `crm_buyer_email`）在 **crm 服务自己的库里** ⇒ 本模块**可以**读它们（同服务同库，
--     不是跨服务访问），见第五节的「库内自动物化」。
--   * **11 售后**（`aftersales_case`）在 **trade 服务**（端口 8085）⇒ 跨服务，
--     **禁止跨库 SQL / FK / 共享视图**。本模块为它留了**授权 HTTP 的写入入口**
--     （`POST /crm/customer-contacts/ingest`），但 **trade 侧尚未接线** ⇒
--     能力声明里 `aftersales_auto_write` 维度 available=false（ownerModule=11.1.1.1）。
--   * **15.5.2 关怀与催办**（营销触达）**未交付** ⇒ 营销触达维度 available=false
--     （ownerModule=15.5.2.1）。本模块**只产出判定与记录**，不发送任何触达。
--   * **15.3.1.1 客户列表 / 15.3.1.2 客户详情未交付** ⇒ 本仓**没有买家档案主数据**，
--     `customer_ref` 是**逻辑引用**（同卷兄弟 15.1.1.1 的台账里明确写过这一点）。
--
-- ============================================================ 二、加粗句的落点：「接触记录是自动化的豁免依据」
--
-- 蓝图把这句话写成**加粗句**，它必须落成**产品行为上的约束**，不是注释。本文件用四道闸：
--
--   1. **退订状态是一等事实**（`crm_customer_opt_out`）：记录「这位客户明确表示不要再联系我」。
--      它带**登记人 / 登记时刻 / 依据（reason 必填 + evidence 快照）** ⇒ 可审计。
--   2. **退订不可被普通接触记录悄悄覆盖或撤销**（DB 层强制）：
--      * `crm_customer_opt_out` 的行**只能**从 ACTIVE 迁到 REVOKED，且**撤销必须带
--        撤销人 / 撤销时刻 / 撤销原因**（CHECK + 触发器）；
--      * `crm_customer_opt_out_event` 是**只追加**的审计流水（UPDATE / DELETE 一律被触发器拒）；
--      * 「普通接触记录」根本**碰不到**退订状态：写一条接触记录只会追加
--        `crm_customer_contact`，不改 `crm_customer_opt_out` 的任何一列。
--   3. **撤销退订是显式动作且留痕**：`POST /crm/customer-contacts/opt-outs/{id}/revoke`
--      —— 必须写撤销原因，并**同时**写一条 REVOKED 审计事件与一条时间线接触记录。
--   4. **有一个可查询的判定**（`GET /crm/customer-contacts/opt-out-check`）：
--      「这个客户当前是不是不可营销」—— 这是 15.5.2 将来要消费的豁免依据。
--      判定只看**生效中**的退订；已撤销的不再豁免（但留痕还在）。
--
-- 此外还有一道**反向闸**（第 6.1 节的触发器）：
-- 客户处于生效退订时，**营销触达**类接触记录**必须显式承认违规**（compliance_state='VIOLATION'）
-- 才能落库 —— 豁免依据不是装饰品，它**真的拦得住**。
-- 判定用 `occurred_at`（接触发生时刻）与退订登记时刻比较：**退订之后**发生的营销触达才算违规；
-- 退订之前的历史触达照常补录（补录历史不是补造违规）。
--
-- ============================================================ 三、三张表：接触记录 / 退订状态 / 退订审计流水
--
--   crm_customer_contact        接触记录（时间线的一行）
--   crm_customer_opt_out        退订 / 免打扰的**状态**（一段「退订期」的登记）
--   crm_customer_opt_out_event  退订状态变更的**只追加**审计流水
--
-- 为什么退订要「状态表 + 事件表」两张：
--   * 状态表回答「**现在**能不能营销他」（一次查得到，索引可用）；
--   * 事件表回答「**曾经**发生过什么」—— 客户退订 → 被撤销 → 又退订，
--     每一段都是一条不可篡改的流水。把这些流水塞进状态表的一列里，
--     「他到底什么时候说过不要联系我」就再也查不清了。
--
-- ============================================================ 四、幂等与「自动写入」的可追溯性
--
-- `crm_customer_contact` 的唯一键：
--   UNIQUE (tenant_id, source_module, source_ref)  —— **部分索引**（source_ref <> ''）
-- ⇒ 同一个上游记录（同一个模块 + 同一个上游 id）**只可能对应一条接触记录**：
--   * 重复自动写入（重试、平台重复推送）⇒ 幂等命中，**不重复记账**；
--   * 人工补录时若引用了同一条上游记录 ⇒ 409 冲突（而不是悄悄记两遍）。
--
-- 每条自动写入的记录都带 `source_module`（上游模块号，如 '15.1.1.1'）与
-- `source_ref`（上游记录 id）+ `source_no`（上游单号快照）⇒
-- 「这条接触记录是哪来的」永远查得到，不是一段没有来源的悬空事实。
--
-- ============================================================ 五、禁浮点与时间纪律
--
--   * 数量（通话时长等）：**numeric(19,6)** + BigDecimal，**禁浮点**。
--   * 金额：本表不存金额（金额属订单/售后事实，在 trade 服务）。
--   * 时间：一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区静默偏 −28800 秒。
--
-- ============================================================ 六、发布后不可变的强制位置
--
--   1. 接触记录的身份列（customer_ref / contact_no / source_module / source_ref）
--      建后不可改：改了之后「这条接触记录是哪一位客户的、哪一次接触」在历史上会出现两个答案。
--   2. 退订记录**不可删**（只能撤销）：删掉之后「他什么时候说过不要联系我」就凭空消失。
--   3. 退订审计流水**不可改、不可删**（只追加）。
--
-- ⚠️ 为什么用**触发器回表**而不是**数据库外键**：
--   同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）会被外键打破。本文件**不给任何既有表建外键**，本文件自己新建的表之间
--   也**一律用触发器做引用校验**，与 V99 / V220 / V228 / V292 / V293 / V295 同一手法。
--
-- ============================================================================
-- 零之一、角色与序列
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V293 / V295 同一口径：角色可能已由运维预置，
-- 因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
END $$;

-- 接触记录号（CT-yyyyMMdd-000001）用的序列：单独建、单独授权。
-- 🔴 GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成「第一条接触记录就报权限不足」。
CREATE SEQUENCE crm_customer_contact_no_seq START 1;

-- ============================================================================
-- 1. crm_customer_contact —— 接触记录（时间线的一行）
-- ============================================================================
-- 接触类型（contact_type）照蓝图点名的五类归位：
--   CALL       客服通话
--   MESSAGE    消息（平台站内信 / 买家消息）
--   EMAIL      邮件
--   MARKETING  营销触达（15.5.2.1 未交付 ⇒ 当前只可能由人工补录或违规留痕产生）
--   AFTERSALES 售后沟通（11 售后在 trade 服务，跨服务自动写入**尚未接线**）
--   OPT_OUT    **退订 / 免打扰**动作本身的记录（由退订端点写入，普通人不能写）
--   OTHER      其它
CREATE TABLE crm_customer_contact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 接触记录号：租户内唯一，**建后不可改**。
  contact_no varchar(32) NOT NULL,

  -- ===== 客户：逻辑引用，不是主数据 =====
  -- 本仓**没有买家档案**（15.3.1.1 客户列表未交付）⇒ customer_ref 只是
  -- 一条**逻辑引用**（平台买家号 / 上游 customer_party_id 等），不建任何跨库外键。
  customer_ref varchar(128) NOT NULL CHECK (btrim(customer_ref) <> ''),
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 接触本体 =====
  contact_type varchar(24) NOT NULL
    CHECK (contact_type IN ('CALL','MESSAGE','EMAIL','MARKETING','AFTERSALES','OPT_OUT','OTHER')),
  -- INBOUND 客户来的 · OUTBOUND 我们发的。
  direction varchar(16) NOT NULL CHECK (direction IN ('INBOUND','OUTBOUND')),
  -- MANUAL 手工补录 · AUTO 上游模块自动写入。
  origin varchar(16) NOT NULL CHECK (origin IN ('MANUAL','AUTO')),

  -- ===== 🔴 自动写入的来源（幂等键的一半）=====
  -- 上游模块号（'15.1.1.1' / '15.1.2.1' / '11.1.1.1' …）与上游记录 id。
  source_module varchar(32) NOT NULL DEFAULT '',
  source_ref varchar(128) NOT NULL DEFAULT '',
  -- 上游单号快照（消息号 MSG-… / 邮件号 BE-…）：上游单号后来被改也不影响追溯。
  source_no varchar(64) NOT NULL DEFAULT '',
  -- 上游来源渠道（人工补录时写 'MANUAL'）。
  source_label varchar(64) NOT NULL DEFAULT '',

  -- ===== 上下文快照（不建外键：店铺属 channel、订单属 trade）=====
  shop_id uuid,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  order_reference varchar(64) NOT NULL DEFAULT '',

  subject varchar(400) NOT NULL DEFAULT '',
  summary text NOT NULL DEFAULT '',
  -- 通话时长（秒）：数量一律 numeric(19,6)，禁浮点。
  duration_seconds numeric(19,6),

  -- 接触**发生**的时刻（平台时间 / 通话时间），不是本系统的登记时刻。
  occurred_at timestamptz NOT NULL,
  -- 接待人（人工补录时由 AccessService.Access 写入，不接受客户端传值）。
  operator_id uuid,
  created_by uuid,
  -- 补充信息快照（jsonb）。
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 🔴 合规状态：客户处于生效退订时，营销触达必须显式承认违规才能落库（见 6.1）。
  compliance_state varchar(16) NOT NULL DEFAULT 'COMPLIANT'
    CHECK (compliance_state IN ('COMPLIANT','VIOLATION')),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, contact_no),

  -- 自动写入必须说清来源（模块 + 上游记录 id），否则「它自动写的」无从追溯。
  CHECK (origin <> 'AUTO' OR (btrim(source_module) <> '' AND btrim(source_ref) <> ''))
);

-- 🔴 幂等键：同一个上游记录只对应一条接触记录（重试 / 平台重复推送不重复记账）。
-- 部分索引：source_ref = '' 的（纯手工补录）不参与唯一性。
CREATE UNIQUE INDEX crm_customer_contact_source_key
  ON crm_customer_contact(tenant_id, source_module, source_ref)
  WHERE btrim(source_ref) <> '';

-- 时间线检索（按客户 + 发生时刻倒序）：15.3.1.2 客户详情与本模块页面都走这条。
CREATE INDEX crm_customer_contact_by_customer
  ON crm_customer_contact(tenant_id, customer_ref, occurred_at DESC, id DESC);
-- 列表页按类型 / 方向 / 来源筛选。
CREATE INDEX crm_customer_contact_by_type
  ON crm_customer_contact(tenant_id, contact_type, occurred_at DESC);
-- 「某模块自动写过来过什么」。
CREATE INDEX crm_customer_contact_by_source
  ON crm_customer_contact(tenant_id, source_module, occurred_at DESC)
  WHERE btrim(source_module) <> '';
-- 合规复核：违规记录必须能被筛出来。
CREATE INDEX crm_customer_contact_by_compliance
  ON crm_customer_contact(tenant_id, compliance_state, occurred_at DESC)
  WHERE compliance_state = 'VIOLATION';

-- ============================================================================
-- 2. crm_customer_opt_out —— 退订 / 免打扰状态
-- ============================================================================
-- 每一行代表**一段退订期**。客户退订 → 被撤销 → 又退订 ⇒ 三行（两段生效过、一段已撤销），
-- 不会把「他曾经说过什么」压扁成一行。
CREATE TABLE crm_customer_opt_out (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  customer_ref varchar(128) NOT NULL CHECK (btrim(customer_ref) <> ''),
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- ALL       不要再联系我（**全部**触达都排除）
  -- MARKETING 只排除营销触达（其它客服沟通照常）
  scope varchar(16) NOT NULL CHECK (scope IN ('ALL','MARKETING')),

  -- ACTIVE 生效中 · REVOKED 已被显式撤销（保留记录，不物理删）。
  state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','REVOKED')),

  -- 🔴 依据必填：没有依据的退订没法复核，也没法纠正。
  reason varchar(600) NOT NULL CHECK (btrim(reason) <> ''),
  -- 依据快照（例如：客户在哪条消息里说的、原文摘要、截图编号）。
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- ===== 🔴 谁在什么时候记下的 =====
  recorded_by uuid NOT NULL,
  recorded_at timestamptz NOT NULL,

  -- ===== 🔴 撤销留痕 =====
  revoked_by uuid,
  revoked_at timestamptz,
  revoke_reason varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),

  -- 已撤销的行必须说清是谁撤的、什么时候撤的、为什么撤。
  CHECK (
    state <> 'REVOKED'
    OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND btrim(revoke_reason) <> '')
  )
);

-- 🔴 同一客户 + 同一范围，最多一条生效中的退订（判定就是靠这条索引）。
CREATE UNIQUE INDEX crm_customer_opt_out_active
  ON crm_customer_opt_out(tenant_id, customer_ref, scope)
  WHERE state = 'ACTIVE';
-- 客户的退订历史（时间线 / 复核用）。
CREATE INDEX crm_customer_opt_out_by_customer
  ON crm_customer_opt_out(tenant_id, customer_ref, recorded_at DESC);
-- 全部生效中的退订（「当前有谁在免打扰」清单）。
CREATE INDEX crm_customer_opt_out_by_state
  ON crm_customer_opt_out(tenant_id, state, recorded_at DESC);

-- ============================================================================
-- 3. crm_customer_opt_out_event —— 退订状态变更的只追加审计流水
-- ============================================================================
CREATE TABLE crm_customer_opt_out_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  opt_out_id uuid NOT NULL,
  customer_ref varchar(128) NOT NULL,

  -- GRANTED 登记退订 · REVOKED 显式撤销退订。
  event_type varchar(16) NOT NULL CHECK (event_type IN ('GRANTED','REVOKED')),
  -- 操作人（已认证成员，由服务端从 AccessService.Access 取）。
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  reason varchar(600) NOT NULL CHECK (btrim(reason) <> ''),
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

CREATE INDEX crm_customer_opt_out_event_by_opt_out
  ON crm_customer_opt_out_event(tenant_id, opt_out_id, occurred_at);
CREATE INDEX crm_customer_opt_out_event_by_customer
  ON crm_customer_opt_out_event(tenant_id, customer_ref, occurred_at DESC);

-- ============================================================================
-- 4. 触发器（**一律不用外键** —— 理由见文件头第六节）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 4.1 guard_crm_customer_contact_marketing_opt_out
--     客户处于生效退订时，营销触达必须显式承认违规才能落库
-- ---------------------------------------------------------------------------
-- 🔴 加粗句「接触记录是自动化的豁免依据」的反向闸：
--   豁免依据如果只是「一条可读的事实」，它拦不住任何东西。
--   这里让它**真的拦得住**：客户在退订生效之后又发生了营销触达，
--   要么明确记为 VIOLATION（把违规留痕），要么**落不了库**。
--
-- ⚠️ 判定用 `occurred_at` 与退订登记时刻比较，而不是「现在」：
--   退订之前的历史触达照常补录（补录历史不是补造违规）；
--   只有**退订之后**发生的触达才算违规。
CREATE FUNCTION guard_crm_customer_contact_marketing_opt_out() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_opt_out_id uuid;
  v_scope varchar(16);
  v_recorded_at timestamptz;
BEGIN
  IF NEW.contact_type <> 'MARKETING' THEN
    RETURN NEW;
  END IF;
  -- 找出该客户当前生效的退订（ALL 或 MARKETING 都拦营销触达）。
  SELECT id, scope, recorded_at INTO v_opt_out_id, v_scope, v_recorded_at
    FROM crm_customer_opt_out
   WHERE tenant_id = NEW.tenant_id
     AND customer_ref = NEW.customer_ref
     AND state = 'ACTIVE'
     AND scope IN ('ALL','MARKETING')
     AND recorded_at <= NEW.occurred_at
   ORDER BY recorded_at DESC
   LIMIT 1;
  IF v_opt_out_id IS NULL THEN
    RETURN NEW;
  END IF;
  IF NEW.compliance_state <> 'VIOLATION' THEN
    RAISE EXCEPTION '客户 % 已于 % 登记退订（范围 %，退订记录 %）：退订生效之后的营销触达不能当作合规触达落库。要留痕必须显式标记为违规（complianceState=VIOLATION）',
      NEW.customer_ref, v_recorded_at, v_scope, v_opt_out_id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_contact_marketing_opt_out_trg
  BEFORE INSERT ON crm_customer_contact
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_contact_marketing_opt_out();

-- ---------------------------------------------------------------------------
-- 4.2 guard_crm_customer_contact_identity · 接触记录的身份列建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：这一行是「我们与哪位客户、什么时候、怎么接触过」的凭据。
-- 改了客户 / 记录号 / 来源键之后，「这次接触是谁的」在历史上会出现两个答案。
CREATE FUNCTION guard_crm_customer_contact_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.customer_ref IS DISTINCT FROM OLD.customer_ref THEN
    RAISE EXCEPTION '接触记录的客户建后不可改：改它会让「这次接触是谁的」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.contact_no IS DISTINCT FROM OLD.contact_no THEN
    RAISE EXCEPTION '接触记录号建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.source_module IS DISTINCT FROM OLD.source_module THEN
    RAISE EXCEPTION '接触记录的来源模块建后不可改：改它会让「这条接触是哪来的」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.source_ref IS DISTINCT FROM OLD.source_ref THEN
    RAISE EXCEPTION '接触记录的来源记录建后不可改：改它会让「幂等写入会不会重复记账」的判定在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_contact_identity_trg
  BEFORE UPDATE ON crm_customer_contact
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_contact_identity();

-- ---------------------------------------------------------------------------
-- 4.3 guard_crm_customer_opt_out_state · 退订状态只能 ACTIVE → REVOKED
-- ---------------------------------------------------------------------------
-- 🔴 加粗句「退订不可被后续的普通接触记录悄悄覆盖或撤销」的第二道闸：
--   已撤销的退订**不能**被改回生效（那等于「悄悄撤销撤销」）；
--   生效中的退订只能被**显式的撤销动作**改掉，且必须带撤销人 / 时刻 / 原因；
--   客户与范围是身份列，建后不可改。
CREATE FUNCTION guard_crm_customer_opt_out_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_actor text;
BEGIN
  IF NEW.customer_ref IS DISTINCT FROM OLD.customer_ref THEN
    RAISE EXCEPTION '退订记录的客户建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.scope IS DISTINCT FROM OLD.scope THEN
    RAISE EXCEPTION '退订记录的范围建后不可改：改它会让「当初免打扰的是什么」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF OLD.state = 'REVOKED' AND NEW.state = 'ACTIVE' THEN
    RAISE EXCEPTION '已撤销的退订不能改回生效：撤销要显式、可追溯，不能靠改状态悄悄恢复' USING ERRCODE='23514';
  END IF;
  IF NEW.state = 'REVOKED' AND OLD.state = 'ACTIVE' THEN
    IF NEW.revoked_at IS NULL OR NEW.revoked_by IS NULL OR btrim(NEW.revoke_reason) = '' THEN
      RAISE EXCEPTION '撤销退订必须写明撤销人、撤销时刻与撤销原因：没有留痕的撤销等于悄悄撤销' USING ERRCODE='23514';
    END IF;
    -- 撤销人必须是**当前已认证的操作人**（app.actor 由 AccessService 绑定）。
    -- 没有 actor 上下文时（例如库内物化）不做这项校验。
    v_actor := nullif(current_setting('app.actor', true), '');
    IF v_actor IS NOT NULL AND v_actor <> NEW.revoked_by::text THEN
      RAISE EXCEPTION '撤销人必须是当前已认证的操作人（当前 %，记录写的是 %）', v_actor, NEW.revoked_by USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_opt_out_state_trg
  BEFORE UPDATE ON crm_customer_opt_out
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_opt_out_state();

-- ---------------------------------------------------------------------------
-- 4.4 guard_crm_customer_opt_out_delete · 退订记录删不掉
-- ---------------------------------------------------------------------------
-- 为什么：退订是**凭据**（客户明确说过「不要再联系我」的证据）。
-- 删掉它之后，「他什么时候说过什么」在历史里就凭空消失了 ——
-- 不是「少了几条」，是**结论被改写**。要解除只能**显式撤销**（留痕）。
CREATE FUNCTION guard_crm_customer_opt_out_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '退订记录 % 删不掉：退订是「客户明确表示不要再联系我」的凭据，删掉会让这段历史凭空消失。要解除请走显式的撤销动作（POST /crm/customer-contacts/opt-outs/{id}/revoke）',
    OLD.id USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_customer_opt_out_delete_trg
  BEFORE DELETE ON crm_customer_opt_out
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_opt_out_delete();

-- ---------------------------------------------------------------------------
-- 4.5 guard_crm_customer_opt_out_event_append_only · 审计流水只追加
-- ---------------------------------------------------------------------------
-- 为什么：审计流水如果可以改，那它就不是审计流水了。
-- GRANTED / REVOKED 两条事件一旦写下就不可变、不可删。
CREATE FUNCTION guard_crm_customer_opt_out_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '退订审计流水只追加：不允许 % （事件 %）',
    TG_OP, OLD.id USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_customer_opt_out_event_append_only_trg
  BEFORE UPDATE OR DELETE ON crm_customer_opt_out_event
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_opt_out_event_append_only();

-- ============================================================================
-- 5. 库内自动物化：15.1 消息 / 15.1.2.1 邮件 → 接触记录
-- ============================================================================
-- 🔴 「各模块自动写入」在本仓库的真实落点。
--
-- 15.1 消息收件箱（`crm_message`）与 15.1.2.1 买家邮件（`crm_buyer_email`）
-- 与本模块**同服务、同一个数据库** ⇒ 自动写入用**库内物化触发器**实现：
--   * **不跨服务**（同库同服务，不违反 AGENTS.md 的模块边界纪律）；
--   * **不改上游代码**（上游模块已交付且由其它车道并行改动，本车道不碰它们的文件）；
--   * **不改上游表结构**（只挂触发器，零 ALTER / 零 DROP / 零改列）。
--
-- 11 售后在 **trade 服务**（跨服务）⇒ 这里**没有**触发器，改为留
-- `POST /crm/customer-contacts/ingest` 这个**授权 HTTP** 入口；
-- trade 侧尚未接线 ⇒ 能力声明里如实标 available=false + ownerModule=11.1.1.1。
--
-- ⚠️ 物化触发器的三条纪律：
--   1. **没有租户上下文就不写**：租户上下文与本行租户不一致时直接跳过 ——
--      绝不往错误的租户名下写一行（也不会把别处的写入搞挂）。
--   2. **只物化真正的「与客户的接触」**：平台通知 / 合规通知不是与客户的接触；
--      邮件只有**已发出**的 OUTBOUND 才算一次触达（QUEUED / FAILED 不算）。
--   3. **幂等**：`ON CONFLICT DO NOTHING` + 唯一键（tenant_id, source_module, source_ref）。
--
-- ---------------------------------------------------------------------------
-- 5.1 materialize_crm_customer_contact_from_message
-- ---------------------------------------------------------------------------
CREATE FUNCTION materialize_crm_customer_contact_from_message() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_tenant text;
  v_seq bigint;
BEGIN
  v_tenant := nullif(current_setting('app.current_tenant', true), '');
  IF v_tenant IS DISTINCT FROM NEW.tenant_id::text THEN
    RETURN NEW; -- 没有匹配的租户上下文 ⇒ 不写（绝不写错租户）。
  END IF;
  IF NEW.message_type NOT IN ('PLATFORM_INBOX','BUYER_MESSAGE') THEN
    RETURN NEW; -- 平台通知 / 合规通知不是与客户的接触。
  END IF;
  SELECT nextval('crm_customer_contact_no_seq') INTO v_seq;
  INSERT INTO crm_customer_contact (
    id, tenant_id, contact_no, customer_ref, customer_name,
    contact_type, direction, origin,
    source_module, source_ref, source_no, source_label,
    shop_id, shop_name, platform_code, order_reference,
    subject, summary, occurred_at, detail, created_at, updated_at)
  VALUES (
    NEW.id, NEW.tenant_id,
    'CT-' || to_char(NEW.platform_time at time zone 'utc','YYYYMMDD') || '-' || lpad(v_seq::text, 6, '0'),
    NEW.buyer_reference, '',
    'MESSAGE', NEW.direction, 'AUTO',
    '15.1.1.1', NEW.id::text, NEW.message_no, '消息收件箱',
    NEW.shop_id, NEW.shop_name, NEW.platform_code, NEW.order_reference,
    NEW.subject, left(NEW.body, 2000), NEW.platform_time,
    jsonb_build_object('messageType', NEW.message_type, 'priority', NEW.priority, 'messageNo', NEW.message_no),
    now(), now())
  ON CONFLICT DO NOTHING;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_contact_from_message_trg
  AFTER INSERT ON crm_message
  FOR EACH ROW EXECUTE FUNCTION materialize_crm_customer_contact_from_message();

-- ---------------------------------------------------------------------------
-- 5.2 materialize_crm_customer_contact_from_buyer_email
-- ---------------------------------------------------------------------------
CREATE FUNCTION materialize_crm_customer_contact_from_buyer_email() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_tenant text;
  v_seq bigint;
BEGIN
  v_tenant := nullif(current_setting('app.current_tenant', true), '');
  IF v_tenant IS DISTINCT FROM NEW.tenant_id::text THEN
    RETURN NEW;
  END IF;
  -- 收到的邮件一定是接触；发出的邮件**只有已发出**才算一次触达。
  IF NOT (NEW.direction = 'INBOUND' OR (NEW.direction = 'OUTBOUND' AND NEW.status = 'SENT')) THEN
    RETURN NEW;
  END IF;
  SELECT nextval('crm_customer_contact_no_seq') INTO v_seq;
  INSERT INTO crm_customer_contact (
    id, tenant_id, contact_no, customer_ref, customer_name,
    contact_type, direction, origin,
    source_module, source_ref, source_no, source_label,
    shop_id, shop_name, platform_code, order_reference,
    subject, summary, occurred_at, detail, created_at, updated_at)
  VALUES (
    NEW.id, NEW.tenant_id,
    'CT-' || to_char(NEW.received_at at time zone 'utc','YYYYMMDD') || '-' || lpad(v_seq::text, 6, '0'),
    NEW.buyer_ref, NEW.buyer_name,
    'EMAIL', NEW.direction, 'AUTO',
    '15.1.2.1', NEW.id::text, NEW.email_no, '买家邮件',
    NEW.shop_id, NEW.shop_name, NEW.platform_code, NEW.sales_order_no,
    NEW.subject, left(NEW.body, 2000), NEW.received_at,
    jsonb_build_object('emailStatus', NEW.status, 'conversationKey', NEW.conversation_key, 'emailNo', NEW.email_no),
    now(), now())
  ON CONFLICT DO NOTHING;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_contact_from_buyer_email_trg
  AFTER INSERT ON crm_buyer_email
  FOR EACH ROW EXECUTE FUNCTION materialize_crm_customer_contact_from_buyer_email();

-- ============================================================================
-- 6. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V293 / V295 同一口径：策略名固定 isolation，
-- USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_customer_contact','crm_customer_opt_out','crm_customer_opt_out_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_customer_contact, crm_customer_opt_out, crm_customer_opt_out_event TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_customer_contact, crm_customer_opt_out, crm_customer_opt_out_event TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条接触记录就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_customer_contact_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_customer_contact_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 7. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.5.2.1 关怀与催办**（**未交付**）：本模块**不发送**任何营销触达，
--     也不登记「待发触达」意图 —— 那是 15.5.2.1 的写入。
--     capabilities 里 marketing_outreach 维度 available=false（ownerModule=15.5.2.1）。
--     本模块只产出**判定**（opt-out-check）与**记录**。
--   * **11 售后**（trade 服务，跨服务）：本模块提供 `POST /crm/customer-contacts/ingest`
--     作为**授权 HTTP** 写入入口，但 **trade 侧尚未接线** ⇒
--     capabilities 里 aftersales_auto_write 维度 available=false（ownerModule=11.1.1.1）。
--   * **15.3.1.1 客户列表 / 15.3.1.2 客户详情**（**未交付**）：本仓**没有买家档案主数据**，
--     `customer_ref` 是**逻辑引用**；本模块**不假装**能按客户主档筛选，
--     页面按引用维度检索即可。capabilities 里 customer_profile 维度
--     available=false（ownerModule=15.3.1.2）。
--   * **15.6.2.1 生命周期与流失预警**：客户标识的跨店合并由 15.3.1.3 负责，
--     本模块的 customer_ref 是**单店口径**的逻辑引用。
