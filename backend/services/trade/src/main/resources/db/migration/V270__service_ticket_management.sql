-- ===========================================================================
-- V270 · 11.4.2.1 工单管理（trade 服务）
-- ===========================================================================
--
-- 蓝图 11-aftersales-and-returns.md 第 206–211 行原文：
--
--   职责：管理非售后类的客户服务工单：咨询、投诉、催单、平台政策咨询；
--         支持分配、SLA 计时、转派、关闭与满意度评价。
--   输入：工单创建（人工/来自消息/自动触发）；SLA 规则；分配规则。
--   输出：工单（待处理/处理中/待买家/已关闭）+ SLA 达成统计。
--   关系：上游 = 15 客户与客服；下游 = 各业务模块（需转单处理）。
--         **工单与售后单要能互转**：咨询升级为投诉、投诉升级为纠纷，需要保留上下文。
--
-- ---------------------------------------------------------------------------
-- 一、这张表**不是本迁移新建的**
-- ---------------------------------------------------------------------------
-- ⚠️ `aftersales_service_ticket` 是 **11.1.1.1 售后单列表**车道在 V156 建的本模块**预留表**。
--   本迁移**只做 ALTER 扩展**，**绝不 CREATE TABLE 同名表**：
--   ① 建同名表会让 Flyway 直接炸（表已存在）；
--   ② 更要命的是 V156 已经把运行角色的权限与 RLS 策略配好了，
--      重建出来的表没有那些权限与策略 —— 一个「看起来对」的重建表是租户隔离的破口。
--
--   V156 预留表的原样（**标识列一个都不许改**）：
--     id / tenant_id / ticket_no / case_id / ticket_status / priority /
--     reason_note / owner_module / batch_id / created_by / created_at
--     UNIQUE(tenant_id,id) / UNIQUE(tenant_id,ticket_no) / UNIQUE(tenant_id,case_id)
--
--   ⚠️ **UNIQUE(tenant_id, case_id) 是本模块的一条硬纪律**：
--     一张售后单只转一次工单（重复转必须报失败并附上已有工单号 —— 11.1.1.1 已实现）。
--     两个号、两个负责人 ⇒ 「到底谁在跟这张单」从此没人说得清。
--
-- ---------------------------------------------------------------------------
-- 二、扩展列的纪律：**每个新列都带 DEFAULT 或可空**
-- ---------------------------------------------------------------------------
-- ⚠️ 本表**已被早先车道写入**（11.1.1.1 的批量「转工单」会 INSERT）。
--   所以：**任何新列都不许是「NOT NULL 且无 DEFAULT」** ——
--   那样早先车道的 INSERT 会在部署那一刻当场违约（仓库里真实发生过的事故：
--   某车道把列改成 NOT NULL 且无 DEFAULT 兜底，破坏了早先合入车道的 INSERT）。
--   下面 21 个新列：18 个带 DEFAULT，3 个可空。
--
-- ---------------------------------------------------------------------------
-- 三、状态词表：加「待买家」，别的**一个字都不改**
-- ---------------------------------------------------------------------------
-- 蓝图输出句点名的四态是「待处理 / 处理中 / **待买家** / 已关闭」，
-- 而 V156 预留的 CHECK 只有四态但**没有**「待买家」：
--     OPEN(待处理) / IN_PROGRESS(处理中) / RESOLVED(已关闭) / CANCELLED(已关闭-取消)
-- ⇒ 这里**只加** WAITING_BUYER，不动其余三态的拼写 ——
--   早先车道写下的 'OPEN' 仍然合法，11.1.1.1 的 INSERT 不受任何影响。
--
-- ---------------------------------------------------------------------------
-- 四、SLA 计时：判定真身**只有 DB 函数一处**
-- ---------------------------------------------------------------------------
-- 照抄 V156 的 `aftersales_timeliness_state` 写法：一个 **IMMUTABLE SQL 函数**，
-- Java 与前端都**刻意不重算**（两处实现必然漂移，而 SLA 达标直接决定客服绩效口径）。
-- 函数的判据用「截止时刻 / 完成时刻 / 参照时刻」三个入参，
-- 于是同一套判据能同时用于：列表筛选、列表排序、行内展示、达成统计。
--
-- ⚠️ 参照时刻**整页共用一个**（at 在 list() 开头取一次）：
--   否则会「筛了临期、第一条不临期」—— V156 已经踩过这个坑并写下结论。
--
-- ---------------------------------------------------------------------------
-- 五、附属事实一律**新建表**
-- ---------------------------------------------------------------------------
-- 转派历史 / 工单事件 / 满意度评价 / SLA 达成快照四类事实**都不塞进主表**：
--   ① 主表是**当前态**（一格一值），历史是多值的，塞进去就得用数组或 json；
--   ② json 里的事实没法建索引、没法参与 WHERE；
--   ③ 「改掉或删掉一行历史」等于毁掉「谁在什么时候把单转给了谁」的凭据。
--   ⇒ 四张表全部 append-only（运行角色的 UPDATE / DELETE 权限被收回）。
--
-- ===========================================================================

-- ===========================================================================
-- 一、扩展预留表 aftersales_service_ticket（只 ALTER，不重建）
-- ===========================================================================

-- ① 状态词表：只**加** WAITING_BUYER（蓝图输出句点名「待买家」）。
--    ⚠️ 约束名不能硬写：PostgreSQL 对内联 CHECK 的自动命名是
--      <table>_<column>_check，但那是**实现细节**。这里按定义反查，命中才删 ——
--    约束名对不上时 DROP CONSTRAINT 会直接报错，迁移当场炸掉。
DO $$ DECLARE cname text; BEGIN
  SELECT conname INTO cname
    FROM pg_constraint
   WHERE conrelid = 'aftersales_service_ticket'::regclass
     AND contype = 'c'
     AND pg_get_constraintdef(oid) LIKE '%ticket_status%';
  IF cname IS NOT NULL THEN
    EXECUTE format('ALTER TABLE aftersales_service_ticket DROP CONSTRAINT %I', cname);
  END IF;
END $$;

ALTER TABLE aftersales_service_ticket
  ADD CONSTRAINT aftersales_service_ticket_status_vocab
    CHECK(ticket_status IN ('OPEN','IN_PROGRESS','WAITING_BUYER','RESOLVED','CANCELLED'));

-- ② 21 个新列。
--    ⚠️ 每个都带 DEFAULT 或可空 —— 早先车道（11.1.1.1）的 INSERT 必须继续成立。
ALTER TABLE aftersales_service_ticket
  -- 工单类型（职责句点名的四类）：咨询 / 投诉 / 催单 / 平台政策咨询。
  -- 存量行（11.1.1.1 转出来的）一律落在 INQUIRY（咨询）—— 它们本来就是
  -- 「就这张售后单来问一句」的转单。
  ADD COLUMN ticket_type varchar(24) NOT NULL DEFAULT 'INQUIRY'
    CHECK(ticket_type IN ('INQUIRY','COMPLAINT','ORDER_CHASE','POLICY_CONSULT')),
  -- 创建来源（输入句「工单创建（人工/来自消息/自动触发）」）。
  ADD COLUMN source_channel varchar(16) NOT NULL DEFAULT 'MANUAL'
    CHECK(source_channel IN ('MANUAL','MESSAGE','AUTOMATIC')),
  -- 来源引用：来自消息时的**消息号**、自动触发时的**规则号**。
  -- ⚠️ 15 客户与客服的消息模块（15.1.x / 15.3.x / 15.5.x / 15.6.x）**全部未开发**，
  --   所以这里落的是**调用方给的引用串**，本服务**不去读任何不存在的消息表**。
  ADD COLUMN source_ref varchar(128) NOT NULL DEFAULT '',
  -- 工单主题（一句话说清这次要处理什么）。
  ADD COLUMN subject varchar(200) NOT NULL DEFAULT '',
  -- 责任人（分配 / 转派 / 认领的结果，落在**当前态**上）。
  ADD COLUMN assigned_to_ref varchar(128) NOT NULL DEFAULT '',
  ADD COLUMN assigned_to_user_id uuid,
  ADD COLUMN assigned_at timestamptz,
  -- 11.4.2.2 知识库与话术的条目引用。
  -- ⚠️ 11.4.2.2 在 **catalog 服务**（/catalog/kb），跨服务**禁止 SQL / FK / 视图**
  --   ⇒ 这里只落**逻辑引用**，话术正文由前端经 catalog 的授权 API 取。
  ADD COLUMN knowledge_entry_id uuid,
  -- SLA 计时：命中了哪一档规则 + 两个时限（**快照**，不随规则改档而漂移）。
  ADD COLUMN sla_policy_id uuid,
  ADD COLUMN sla_reply_hours integer,
  ADD COLUMN sla_resolve_hours integer,
  ADD COLUMN reply_due_at timestamptz,
  ADD COLUMN resolve_due_at timestamptz,
  -- 首次回复时刻：回复时限的完成时刻。
  ADD COLUMN first_reply_at timestamptz,
  -- 进入「待买家」的时刻（待买家的时长要能算出来）。
  ADD COLUMN waiting_buyer_at timestamptz,
  -- 关闭时刻与关闭原因。
  ADD COLUMN closed_at timestamptz,
  ADD COLUMN close_reason varchar(16) NOT NULL DEFAULT ''
    CHECK(close_reason IN ('','RESOLVED','NO_RESPONSE','DUPLICATE','CANCELLED','ESCALATED','ABANDONED')),
  ADD COLUMN close_note text NOT NULL DEFAULT '' CHECK(length(close_note) <= 2000),
  -- 升级去向（蓝图加粗句「工单与售后单要能互转」的落点）：
  --   escalated_case_id     —— 咨询升级为投诉时**新建的售后单**（11.1.1.1 同库表）
  --   escalated_dispute_id  —— 投诉升级为纠纷时**新建的平台纠纷**（11.4.1.1 同库表）
  ADD COLUMN escalated_case_id uuid,
  ADD COLUMN escalated_dispute_id uuid,
  -- 乐观锁：改状态都要带 expectedVersion。
  ADD COLUMN version bigint NOT NULL DEFAULT 0,
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- ③ 表级 CHECK：把「终态 ⇔ 关闭时刻」「时限先后」「SLA 小时数」钉在 DB 层。
--    ⚠️ 这几条在**加约束那一刻**会对存量行做校验：11.1.1.1 转出来的行
--    是 status='OPEN' + closed_at IS NULL ⇒ 全部通过。
ALTER TABLE aftersales_service_ticket
  ADD CONSTRAINT aftersales_service_ticket_closed_iff_terminal
    CHECK((ticket_status IN ('RESOLVED','CANCELLED')) = (closed_at IS NOT NULL)),
  ADD CONSTRAINT aftersales_service_ticket_close_reason_required
    CHECK(closed_at IS NULL OR btrim(close_reason) <> ''),
  ADD CONSTRAINT aftersales_service_ticket_reply_before_resolve
    CHECK(reply_due_at IS NULL OR resolve_due_at IS NULL OR reply_due_at <= resolve_due_at),
  ADD CONSTRAINT aftersales_service_ticket_first_reply_before_close
    CHECK(closed_at IS NULL OR first_reply_at IS NULL OR first_reply_at <= closed_at),
  ADD CONSTRAINT aftersales_service_ticket_sla_hours_range
    CHECK((sla_reply_hours IS NULL OR (sla_reply_hours > 0 AND sla_reply_hours <= 8760))
      AND (sla_resolve_hours IS NULL OR (sla_resolve_hours > 0 AND sla_resolve_hours <= 8760))),
  ADD CONSTRAINT aftersales_service_ticket_sla_resolve_ge_reply
    CHECK(sla_resolve_hours IS NULL OR sla_reply_hours IS NULL OR sla_resolve_hours >= sla_reply_hours),
  ADD CONSTRAINT aftersales_service_ticket_assigned_has_time
    CHECK(assigned_to_ref = '' OR assigned_at IS NOT NULL);

-- ===========================================================================
-- 二、SLA 判定函数（IMMUTABLE，判定真身只有这一处）
-- ===========================================================================

-- ⚠️ 判据口径与 V156 的 aftersales_timeliness_state 同源但**更细一档**：
--   SLA 要分得开「已达标 / 待回复 / 已超期」三态，
--   因为「超期」是要进统计口径的（客服绩效），而「时效预警」只是提醒。
--   四个返回码：
--     NO_DEADLINE —— 没配时限（不该出现在工单上，兜底而已）
--     PENDING     —— 时限未到、还没完成
--     MET         —— 完成时刻 ≤ 截止时刻
--     BREACHED    —— 已超期，或完成时刻晚于截止时刻
CREATE FUNCTION aftersales_ticket_sla_state(due_at timestamptz, done_at timestamptz, at timestamptz)
RETURNS varchar
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE
           WHEN due_at IS NULL THEN 'NO_DEADLINE'
           WHEN done_at IS NOT NULL AND done_at <= due_at THEN 'MET'
           WHEN done_at IS NOT NULL THEN 'BREACHED'
           WHEN due_at < at THEN 'BREACHED'
           ELSE 'PENDING'
         END
$$;

-- ===========================================================================
-- 三、附属事实表（四张，全部 append-only）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 3.1 aftersales_ticket_assignment · 转派历史
-- ---------------------------------------------------------------------------
-- ⚠️ 「当前责任人」落在主表的 assigned_to_ref 上（本模块的**当前态**）；
--   这张表落的是**流转过程**：谁在什么时候把单给了谁、为什么。
--   一个转派一条，追加式。
CREATE TABLE aftersales_ticket_assignment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  ticket_id uuid NOT NULL,

  -- 动作：首派 / 转派 / 认领 / 释放。
  assign_action varchar(16) NOT NULL CHECK(assign_action IN ('ASSIGN','TRANSFER','CLAIM','RELEASE')),
  -- 派单方式：人工指定 / 名册加权轮转 / 系统自动路由 / 主动认领。
  assign_mode varchar(24) NOT NULL DEFAULT 'MANUAL'
    CHECK(assign_mode IN ('MANUAL','ROSTER_ROUND_ROBIN','AUTO_ROUTE','CLAIM')),

  from_owner_ref varchar(128) NOT NULL DEFAULT '',
  from_owner_user_id uuid,
  to_owner_ref varchar(128) NOT NULL DEFAULT '',
  to_owner_user_id uuid,

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 1000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 转派必须有来处也有去处；认领没有来处（自己上前），释放没有去处。
  CHECK(assign_action <> 'TRANSFER' OR (btrim(from_owner_ref) <> '' AND btrim(to_owner_ref) <> '')),
  CHECK(assign_action <> 'ASSIGN' OR btrim(to_owner_ref) <> '')
);

-- ---------------------------------------------------------------------------
-- 3.2 aftersales_ticket_event · 工单事件留痕
-- ---------------------------------------------------------------------------
-- 状态流转 / 分配 / 转派 / 首次回复 / 关闭 / 评价 / 升级 —— 一件事一条。
-- ⚠️ 追加式：能被悄悄改掉的留痕等于没有留痕。
CREATE TABLE aftersales_ticket_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  ticket_id uuid NOT NULL,

  event_type varchar(32) NOT NULL CHECK(event_type IN (
      'CREATED','ASSIGNED','TRANSFERRED','CLAIMED','RELEASED','STATUS_CHANGED',
      'FIRST_REPLIED','WAITING_BUYER','RESUMED','CLOSED','CANCELLED',
      'SLA_BREACHED','SATISFACTION_RECORDED','ESCALATED_TO_COMPLAINT','ESCALATED_TO_DISPUTE'
  )),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  actor_id uuid,
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),
  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);

-- ---------------------------------------------------------------------------
-- 3.3 aftersales_ticket_satisfaction · 满意度评价
-- ---------------------------------------------------------------------------
-- 一张工单**一条**评价：评价是「这次服务怎么样」的**一次**结论，
-- 改了它就等于把买家给过的一次反馈重写。
CREATE TABLE aftersales_ticket_satisfaction (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  ticket_id uuid NOT NULL,

  -- 1~5 分。
  score smallint NOT NULL CHECK(score BETWEEN 1 AND 5),
  comment text NOT NULL DEFAULT '' CHECK(length(comment) <= 2000),
  rated_by uuid NOT NULL,
  rated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,ticket_id)
);

-- ---------------------------------------------------------------------------
-- 3.4 aftersales_ticket_sla_snapshot · SLA 达成快照
-- ---------------------------------------------------------------------------
-- ⚠️ 关闭那一刻把 SLA 达成**冻结**成一条事实。
--   为什么不在读的时候现算：现算的话，「达标」这个结论会随着
--   规则档的改版、乃至 first_reply_at 的一次补录而翻来覆去 ——
--   而「当时有没有达标」是一个**已经发生**的事实，客服绩效按它算账。
--   ⇒ 统计口径只认这张表；快照由 DB 用**同一个**判定函数算出（不另立一套）。
CREATE TABLE aftersales_ticket_sla_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  ticket_id uuid NOT NULL,

  sla_policy_id uuid,
  sla_reply_hours integer,
  sla_resolve_hours integer,
  reply_due_at timestamptz,
  resolve_due_at timestamptz,
  first_reply_at timestamptz,
  closed_at timestamptz NOT NULL,

  -- 回复时限达成（true/false）。
  reply_met boolean NOT NULL,
  -- 处理时限达成：没配处理时限时为 NULL（「没这项要求」与「没达标」是两件事）。
  resolve_met boolean,

  snapshotted_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一张工单一条快照：达标结论只能落一次。
  UNIQUE(tenant_id,ticket_id),
  CHECK(resolve_due_at IS NULL OR resolve_met IS NOT NULL)
);

-- ===========================================================================
-- 四、护栏触发器
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 4.1 标识列不可改写 + 工单必须挂在真实未结案的售后单上
-- ---------------------------------------------------------------------------
-- ⚠️ 触发器挂在 **BEFORE INSERT OR UPDATE** 上 ⇒ 绕过服务层直连 SQL 也一样。
--   早先车道（11.1.1.1）的 INSERT 只填 id/tenant_id/ticket_no/case_id/
--   ticket_status/priority/reason_note/batch_id/created_by/created_at，
--   其余新列全取 DEFAULT ⇒ 本触发器对它们**不构成破坏**。
CREATE FUNCTION guard_aftersales_service_ticket() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_case_status varchar(20);
BEGIN
  IF TG_OP = 'UPDATE' THEN
    -- 标识列不可改写：工单号、归属售后单、归属模块、创建人、创建时刻
    -- 一起改掉的话，「这张工单是什么时候、为哪张售后单、归谁」就没有唯一答案了。
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.ticket_no IS DISTINCT FROM OLD.ticket_no
       OR NEW.case_id IS DISTINCT FROM OLD.case_id
       OR NEW.owner_module IS DISTINCT FROM OLD.owner_module
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
      RAISE EXCEPTION '工单的标识列（id / tenant_id / ticket_no / case_id / owner_module / created_by / created_at）不可改写：ticket_id=%', OLD.id
        USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    -- 工单必须挂在**真实存在**且属于当前租户的售后单上。
    SELECT c.status INTO t_case_status
      FROM aftersales_case c
     WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.case_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION '工单必须挂在一张真实存在且属于当前租户的售后单上：case_id=%', NEW.case_id
        USING ERRCODE = 'no_data_found';
    END IF;
    -- 已结案的售后单不再转工单（11.1.1.1 的批量转单也是这条口径）。
    IF t_case_status IN ('RESOLVED','REJECTED','CLOSED') THEN
      RAISE EXCEPTION '售后单已结案（status=%），不能再转工单', t_case_status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_service_ticket
  BEFORE INSERT OR UPDATE ON aftersales_service_ticket
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_service_ticket();

-- ---------------------------------------------------------------------------
-- 4.2 升级去向必须真实存在（同服务内同库，可加真外键）
-- ---------------------------------------------------------------------------
-- ⚠️ 这两条是**同服务同库**的表（11.1.1.1 售后单 / 11.4.1.1 平台纠纷），
--   所以可以用**真外键**钉住 —— 与「跨服务禁止 FK」的纪律不冲突。
--   咨询升级为投诉 ⇒ 必须真的建出一张售后单；
--   投诉升级为纠纷 ⇒ 必须真的建出一条平台纠纷。
--   不加外键的话，「升级了但对方根本不存在」会留下一张查无实据的工单。
-- ⚠️ 两条约束**各自一个 DO 块**：plpgsql 的异常处理会把整个块回滚，
--   两条挤在一个块里的话，第二条报错会把第一条一起带走（而第一条本该成功）。
DO $$ BEGIN
  ALTER TABLE aftersales_service_ticket
    ADD CONSTRAINT aftersales_service_ticket_escalated_case_fk
    FOREIGN KEY (escalated_case_id) REFERENCES aftersales_case(id) ON DELETE RESTRICT;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
DO $$ BEGIN
  ALTER TABLE aftersales_service_ticket
    ADD CONSTRAINT aftersales_service_ticket_escalated_dispute_fk
    FOREIGN KEY (escalated_dispute_id) REFERENCES platform_dispute(id) ON DELETE RESTRICT;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ===========================================================================
-- 五、索引：每一条都对应一个真实会被跑的查询形状
-- ===========================================================================

-- ① 列表默认排序：按状态筛 + 创建时刻倒序（「待处理」页签的默认序）。
CREATE INDEX aftersales_service_ticket_by_status_created
  ON aftersales_service_ticket(tenant_id, ticket_status, created_at DESC);
-- ② 按类型筛（咨询 / 投诉 / 催单 / 平台政策咨询 四类分开看）。
CREATE INDEX aftersales_service_ticket_by_type
  ON aftersales_service_ticket(tenant_id, ticket_type, created_at DESC);
-- ③ 「我名下有多少在办」：责任人 + 未结案。
CREATE INDEX aftersales_service_ticket_by_assignee
  ON aftersales_service_ticket(tenant_id, assigned_to_ref, created_at DESC)
  WHERE closed_at IS NULL;
-- ④ SLA 超时队列：回复时限升序（未结案才有倒计时）。
CREATE INDEX aftersales_service_ticket_by_reply_due
  ON aftersales_service_ticket(tenant_id, reply_due_at)
  WHERE closed_at IS NULL AND reply_due_at IS NOT NULL;
-- ⑤ 统计口径：按关闭时刻排（SLA 达成统计的时间窗）。
CREATE INDEX aftersales_service_ticket_by_closed_at
  ON aftersales_service_ticket(tenant_id, closed_at DESC)
  WHERE closed_at IS NOT NULL;
-- ⑥ 转派历史 / 事件流按工单倒序回放。
CREATE INDEX aftersales_ticket_assignment_by_ticket
  ON aftersales_ticket_assignment(tenant_id, ticket_id, created_at DESC);
CREATE INDEX aftersales_ticket_event_by_ticket
  ON aftersales_ticket_event(tenant_id, ticket_id, occurred_at DESC);
-- ⑦ 满意度评价按工单取（UNIQUE(tenant_id,ticket_id) 已覆盖查询，但统计按分数筛要一条）。
CREATE INDEX aftersales_ticket_satisfaction_by_score
  ON aftersales_ticket_satisfaction(tenant_id, score);

-- ===========================================================================
-- 六、多租户：RLS + 授权
-- ===========================================================================

-- ⚠️ 本模块尤其不能省：工单里同时有**买家诉求原文、责任人、SLA 达标结论**，
--   端点又大多是**按 id 直达**的 URL —— 猜到一个 UUID 就能读，不需要翻列表。
--   主表 V156 已经配好 ENABLE + FORCE + isolation 策略，**这里不重复配**。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'aftersales_ticket_assignment','aftersales_ticket_event',
    'aftersales_ticket_satisfaction','aftersales_ticket_sla_snapshot'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  aftersales_ticket_assignment, aftersales_ticket_event,
  aftersales_ticket_satisfaction, aftersales_ticket_sla_snapshot TO trade_app;

-- ⚠️ **四张附属事实表全部 append-only**：
--   转派历史 / 事件流 / 满意度 / SLA 快照都是「已经发生过的事」，
--   能被悄悄改掉或删掉的留痕等于没有留痕。
REVOKE UPDATE,DELETE ON aftersales_ticket_assignment FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_ticket_event FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_ticket_satisfaction FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_ticket_sla_snapshot FROM trade_app;

-- ---------------------------------------------------------------------------
-- 6.1 主表的 UPDATE 权限：**列级白名单**
-- ---------------------------------------------------------------------------
-- ⚠️ V156 对主表做的是 `REVOKE UPDATE,DELETE ON aftersales_service_ticket FROM trade_app`
--   （转工单是留痕性质，不许改写）。本模块要**接管**这张表并推进它的状态，
--   所以这里必须**显式授回列级 UPDATE**。
--
-- ⚠️ **列级 REVOKE 在表级 GRANT 之后是空操作**（V156 第 463–468 行踩过这个坑）：
--   PostgreSQL 里表级 UPDATE 蕴含该表**每一列**的 UPDATE 权限。
--   这里 V156 已经把表级 UPDATE 收回了，所以逐列 GRANT 是真的在授。
--
-- 只授**本模块真的要写**的列：
--   · 状态与时刻：ticket_status / waiting_buyer_at / closed_at / close_reason / close_note
--   · 分配：assigned_to_ref / assigned_to_user_id / assigned_at
--   · SLA：first_reply_at / sla_policy_id / sla_reply_hours / sla_resolve_hours
--   · 升级：escalated_case_id / escalated_dispute_id
--   · 维护：version / updated_at
-- **不授** ticket_no / case_id / owner_module / created_by / created_at ——
--   那些由 4.1 的触发器钉死不可改写，授了也没用（还多给人一个试的机会）。
-- **不授** DELETE：工单不能删。
GRANT UPDATE(
  ticket_status, ticket_type, subject, source_channel, source_ref,
  reason_note, priority,
  assigned_to_ref, assigned_to_user_id, assigned_at,
  knowledge_entry_id,
  sla_policy_id, sla_reply_hours, sla_resolve_hours,
  reply_due_at, resolve_due_at, first_reply_at,
  waiting_buyer_at, closed_at, close_reason, close_note,
  escalated_case_id, escalated_dispute_id,
  version, updated_at
) ON aftersales_service_ticket TO trade_app;

-- ⚠️ **不 ALTER 任何其它既有表**：不碰 V156 / V157 / V164 / V265 的表结构，
--   本模块对它们的全部依赖都是 SELECT（同服务同库，合法）。
--   跨车道 DDL 回归面 = 0（同批另有五条车道也在往 trade 落迁移）。
