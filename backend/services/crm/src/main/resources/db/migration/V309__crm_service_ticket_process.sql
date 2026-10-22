-- ===========================================================================
-- V309 · 15.4.1.2 工单处理（crm 服务）
-- ===========================================================================
--
-- 蓝图 15-crm-and-service.md 第 197–201 行原文：
--
--   ### 15.4.1.2 工单处理
--   - 职责：处理工单：回复、备注、状态流转、关联订单与售后单、解决确认、
--           满意度回收、SLA 暂停/恢复。
--   - 输入：处理动作；关联单据；满意度评分。
--   - 输出：工单状态 + 处理记录 + 关联单据更新。
--   - 关系：上游 = 15.4.1.1；下游 = 11 售后（升级）、9 订单。
--           **工单关闭不等于问题解决**，需确认实际结果（退款到账、换货发出）
--           才计为解决。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块**不建工单表**：工单的事实来源仍在 trade 服务
-- ---------------------------------------------------------------------------
-- 工单（`aftersales_service_ticket` 及其附属事实表）由 **11.4.2.1 工单管理**
-- （trade 服务）拥有，是那张表的**唯一写入所有者**。本模块（15.4.1.2）与同 L3
-- 兄弟 15.4.1.1（V306）**刻意不共用一张表**：15.4.1.1 是只读列表，本模块是处理。
--
-- AGENTS.md 模块边界纪律：**禁止跨服务 SQL / 跨库 FK / 共享视图**。
--   ⇒ 本服务**一条 SQL 都不碰** trade 的库；
--   ⇒ 本服务经 **trade 的授权 HTTP API** 写（`POST /trade/service-tickets/{id}/...`），
--     转发的是**调用方自己的 Authorization bearer**，租户由 trade 侧按已认证
--     成员关系解析并强制 RLS（租户隔离两道：crm 侧 RLS + trade 侧 RLS）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 **工单关闭 ≠ 问题解决** ⇒ 解决确认是**另一张表**、**另一个动作**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「工单关闭不等于问题解决，需确认实际结果（退款到账、换货发出）
-- 才计为解决。」
--
--   ⇒ **关闭**（close）走 trade 的 `POST /{id}/close`：落终态
--     （RESOLVED / CANCELLED），这是**流程上不再跟了**；
--   ⇒ **解决确认**（resolution）落在**本模块自己的**
--     `crm_service_ticket_resolution`：这是**实际结果确实到了**
--     （退款到账 / 换货发出 / 买家接受方案）。
--
-- 两者**不能合并成一个 status**：合并之后，「关掉了但退款没到」的单会与
-- 「退款已到」的单在库里长得一模一样 —— 而这正是加粗句要避免的误判。
-- 所以 `crm_service_ticket_resolution` 独立存在，且确认动作**要求工单已关闭**
-- （先关后确认，顺序本身就是这条纪律的落点）。
--
-- ---------------------------------------------------------------------------
-- 三、本模块自建的五张表（trade **没有**对应端点的那些）
-- ---------------------------------------------------------------------------
-- 1. crm_service_ticket_note         —— 回复 / 备注（append-only）
-- 2. crm_service_ticket_action       —— 处理记录（append-only，整个处理动作的时间线）
-- 3. crm_service_ticket_link         —— 关联订单与售后单（工单 ↔ 关联单据的**关系**）
-- 4. crm_service_ticket_resolution   —— 解决确认（**关闭之外的另一个结论**）
-- 5. crm_service_ticket_sla_pause    —— SLA 暂停区间（**在营业时间轴上**暂停）
--
-- ⚠️ **满意度不建表**：trade 已有 `POST /{id}/satisfaction`（11.4.2.1 拥有），
--   本模块**转发**它 —— 同一件事两个事实源，迟早会打架。
--
-- ⚠️ **reply / status / close 也不建表**：这三件事的事实归 trade
--   （`aftersales_ticket_event` 等附属事实表），本模块**转发**并在
--   `crm_service_ticket_action` 里留一条**处理记录**（append-only 的动作时间线）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 SLA 暂停必须落在**营业时间轴**上，不是 `createdAt + N 小时`
-- ---------------------------------------------------------------------------
-- 蓝图 15.4.1.1 的加粗句「SLA 计时需按营业时间而非自然时间」在本模块继续成立，
-- 且暂停 / 恢复必须作用在**同一条轴**上：
--   * 轴的形状读本服务自己的 `crm_service_business_hours` /
--     `crm_service_business_holiday`（15.4.1.1 建的，**同库同服务**，不是跨库）；
--   * 暂停区间记的是**墙钟区间** [paused_at, resumed_at)，
--     但**落在区间里的营业时间不计入 SLA 消耗** —— 由服务层在营业时间轴上算；
--   * 没配营业时间 ⇒ 一律 NO_CALENDAR，**刻意不退回自然时间**。
--
-- `scope_key` 记在暂停行上：**当时是按哪条口径记的暂停**要查得到 ——
-- 租户后来改了营业时间，回头查 SLA 口径才说得清。
--
-- ===========================================================================
-- 1. crm_service_ticket_note · 回复与备注（append-only）
-- ===========================================================================
-- ⚠️ **note_type 二选一**，不是两套表：
--   * 'NOTE' = 内部备注（客服写给客服看的）
--   * 'REPLY' = 对买家的回复（**必须带渠道** —— 没有渠道的回复，
--     事后没人说得清这句话是发到哪儿去的）
CREATE TABLE crm_service_ticket_note (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 🔴 **不建外键**：工单在 trade 服务的库里，跨库 FK 被 AGENTS.md 明文禁止。
  --   这里存的是「上游工单的 id 引用」，删工单 / 改工单不由本服务负责。
  ticket_id uuid NOT NULL,

  -- NOTE = 内部备注；REPLY = 对买家的回复。
  note_type varchar(16) NOT NULL CHECK (note_type IN ('NOTE','REPLY')),

  -- 正文。空备注没有意义（写进去只是一条噪音）。
  content text NOT NULL CHECK (btrim(content) <> ''),

  -- 回复渠道（**仅 REPLY 必填**）。
  reply_channel varchar(32) CHECK (
    reply_channel IS NULL
    OR reply_channel IN ('PLATFORM_MESSAGE','EMAIL','CHAT','PHONE','OTHER')
  ),

  -- ⚠️ REPLY ⇒ 必须带渠道；NOTE ⇒ 渠道必须为空。
  --   「内部备注」带个渠道只会让人分不清这条是给谁看的。
  CHECK (
    (note_type = 'REPLY' AND reply_channel IS NOT NULL)
    OR (note_type = 'NOTE' AND reply_channel IS NULL)
  ),

  -- 谁写的（已认证成员，服务端从 AccessService.Access 取，不接受客户端传值）。
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 读一张工单的备注 / 回复（时间倒序，新写的在最前）。
CREATE INDEX crm_service_ticket_note_by_ticket
  ON crm_service_ticket_note(tenant_id, ticket_id, created_at DESC);

-- ===========================================================================
-- 2. crm_service_ticket_action · 处理记录（append-only）
-- ===========================================================================
-- 🔴 这是蓝图「输出：工单状态 + **处理记录** + 关联单据更新」里的「处理记录」。
--   它是**动作时间线**：每一次处理动作留一行，谁在什么时候对哪张工单做了什么。
--   ⚠️ **只记成功的动作**：失败的动作会随事务回滚一起消失（那才是真相 ——
--   「写了但没生效」留在时间线里比不写更糟）。
CREATE TABLE crm_service_ticket_action (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  ticket_id uuid NOT NULL,

  action_type varchar(24) NOT NULL CHECK (
    action_type IN (
      'REPLY',              -- 回复（同时转发上游 first-reply 时效）
      'NOTE',               -- 内部备注
      'STATUS_CHANGE',      -- 状态流转
      'CLOSE',              -- 关闭（**不等于解决**）
      'RESOLUTION_CONFIRM', -- 解决确认（**关闭之外的另一个结论**）
      'RESOLUTION_REVOKE',  -- 撤销解决确认（写错了要能改 —— 假的「退款已到账」比没有更糟）
      'SATISFACTION',       -- 满意度回收（转发上游）
      'SLA_PAUSE',          -- SLA 暂停
      'SLA_RESUME',         -- SLA 恢复
      'LINK_ORDER',         -- 关联订单
      'LINK_CASE',          -- 关联售后单
      'ESCALATE'            -- 升级（转投诉 / 转纠纷 ⇒ 下游 11 售后）
    )
  ),

  -- 状态流转的前后状态（其余动作留空）。
  from_status varchar(16),
  to_status varchar(16),

  -- 动作的补充说明（原文照录，不做改写）。
  detail text,

  -- 动作涉及的关联单据（链接动作 / 升级动作填）。
  related_order_no varchar(64),
  related_case_no varchar(64),

  -- 解决确认的结果类型（仅 RESOLUTION_CONFIRM 填）。
  resolution_type varchar(32),

  -- 谁做的（已认证成员）。
  actor_id uuid NOT NULL,

  -- 动作发生的时刻（服务端写入，不是客户端传的）。
  occurred_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

CREATE INDEX crm_service_ticket_action_by_ticket
  ON crm_service_ticket_action(tenant_id, ticket_id, occurred_at DESC);

-- ===========================================================================
-- 3. crm_service_ticket_link · 关联订单与售后单
-- ===========================================================================
-- 🔴 本表只存**关系**（这张工单绑的是哪一单 / 哪一张售后单），
--   **不复制**订单或售后单的**任何一列**（金额 / 状态 / 平台都在上游）——
--   复制一份就成了两个事实源。
--   关联单据的**当前事实**在读取时经 trade 的授权 API 现取（见服务层）。
--
-- ⚠️ 一张工单**一条**关联订单、**一条**关联售后单（trade 侧工单恒挂一张售后单），
--   所以 UNIQUE (tenant_id, ticket_id, link_type) —— 重复关联走**改写**。
CREATE TABLE crm_service_ticket_link (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  ticket_id uuid NOT NULL,

  -- ORDER = 关联订单；AFTER_SALES_CASE = 关联售后单。
  link_type varchar(16) NOT NULL CHECK (link_type IN ('ORDER','AFTER_SALES_CASE')),

  -- 🔴 不建外键（跨库 FK 被 AGENTS.md 明文禁止）。
  order_id uuid,
  order_no varchar(64),
  case_id uuid,
  case_no varchar(64),

  -- ⚠️ 关联**哪一种单据**，那一侧的编号就必填 ——
  --   「关联了订单」却没订单号，等于没关联。
  CHECK (
    (link_type = 'ORDER' AND order_no IS NOT NULL)
    OR (link_type = 'AFTER_SALES_CASE' AND case_no IS NOT NULL)
  ),

  -- 关联的来源：MANUAL = 人工关联；UPSTREAM = 工单本来就挂着它；
  -- ESCALATION = 升级动作新建出来的（下游 11 售后）。
  link_source varchar(16) NOT NULL CHECK (
    link_source IN ('MANUAL','UPSTREAM','ESCALATION')
  ),

  -- 最近一次「关联单据更新」的内容与时刻。
  -- ⚠️ 上游 trade **没有**「改写已有售后单 / 订单」的端点 ⇒
  --   这里记的是**本模块这边**的更新落点，**不假装推给了上游**。
  last_sync_note text,
  last_synced_at timestamptz,

  -- 谁关联的（已认证成员）。
  linked_by uuid NOT NULL,
  linked_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, ticket_id, link_type)
);

CREATE INDEX crm_service_ticket_link_by_ticket
  ON crm_service_ticket_link(tenant_id, ticket_id);

-- ===========================================================================
-- 4. crm_service_ticket_resolution · 解决确认（**关闭之外的另一个结论**）
-- ===========================================================================
-- 🔴 本表是蓝图加粗句「工单关闭不等于问题解决」的**落点**：
--   **关闭**落终态（trade 侧），**解决确认**落这张表（业务侧）——
--   「退款到账」「换货发出」「买家接受方案」这些**实际结果**在这里。
--
-- ⚠️ 撤销（revoked）：确认写错了要能改 —— 留着一条假的「退款已到账」
--   比没有更糟（它会被当成「可以结案」的依据）。撤销**不删行**，
--   只把 revoked_at 标上，动作时间线里另有一条 REVOKE 记录。
CREATE TABLE crm_service_ticket_resolution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  ticket_id uuid NOT NULL,

  -- 实际结果的类型（**词表钉死**，不写自由文本 ——
  --   自由文本没法统计，也没法让「退款到账」与「换货发出」被区别对待）。
  resolution_type varchar(32) NOT NULL CHECK (
    resolution_type IN (
      'REFUND_RECEIVED',   -- 退款到账
      'REPLACEMENT_SHIPPED',-- 换货已发出
      'RETURN_RECEIVED',    -- 退货已签收
      'ORDER_CANCELLED',    -- 订单已取消
      'GUIDANCE_ACCEPTED',  -- 买家接受方案（无需退款）
      'OTHER'               -- 其它（必须写说明）
    )
  ),

  -- 结果说明（'OTHER' 时必填 —— 「其它」不带说明等于没写）。
  result_note text,
  CHECK (resolution_type <> 'OTHER' OR (result_note IS NOT NULL AND btrim(result_note) <> '')),

  -- 确认时点关联的订单 / 售后单（当时的指向，不是当前指向）。
  linked_case_id uuid,
  linked_order_no varchar(64),

  -- 谁确认的、什么时候确认的（服务端写入）。
  confirmed_by uuid NOT NULL,
  confirmed_at timestamptz NOT NULL,

  -- 撤销（可选）。
  revoked_at timestamptz,
  revoked_by uuid,
  CHECK (revoked_at IS NULL OR revoked_at >= confirmed_at),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一张工单同一种结果类型只留一条（重复确认走**改写**，
  --   历史留在 crm_service_ticket_action 的动作时间线里）。
  UNIQUE (tenant_id, ticket_id, resolution_type)
);

CREATE INDEX crm_service_ticket_resolution_by_ticket
  ON crm_service_ticket_resolution(tenant_id, ticket_id);

-- ===========================================================================
-- 5. crm_service_ticket_sla_pause · SLA 暂停区间
-- ===========================================================================
-- 🔴 暂停**必须作用在营业时间轴上**（蓝图 15.4.1.1 加粗句在本模块继续成立）：
--   暂停区间记的是墙钟区间 [paused_at, resumed_at)，
--   但**落在区间里的营业时间不计入 SLA 消耗** ——
--   服务层沿 BusinessHoursCalendar 这条轴把「消耗掉的营业时间」扣掉。
--
-- ⚠️ **没配营业时间 ⇒ 不给暂停**：没有轴就没有「暂停掉的那段营业时间」，
--   记一条暂停只会让人以为 SLA 被冻结了（实际并没有这回事）。
--   这条由服务层挡（NO_CALENDAR ⇒ 400），不在 DB 层猜。
CREATE TABLE crm_service_ticket_sla_pause (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  ticket_id uuid NOT NULL,

  -- 暂停哪一条时限：REPLY = 回复时限；RESOLVE = 处理时限；BOTH = 两条都暂停。
  sla_target varchar(16) NOT NULL CHECK (sla_target IN ('REPLY','RESOLVE','BOTH')),

  -- 🔴 记下**当时按哪条口径**记的暂停（租户后来改了营业时间，
  --   回头查 SLA 口径才说得清）。
  scope_key varchar(64) NOT NULL,

  -- 暂停区间（左闭右开）。
  paused_at timestamptz NOT NULL,
  resumed_at timestamptz,
  CHECK (resumed_at IS NULL OR resumed_at >= paused_at),

  -- 暂停理由（**必填**：没有理由的暂停事后无从判断该不该恢复）。
  pause_reason text NOT NULL CHECK (btrim(pause_reason) <> ''),
  resume_reason text,

  -- 谁暂停的（已认证成员）。
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 一张工单**同时最多一条**未恢复的暂停（部分唯一索引）。
-- ⇒ 「暂停两次」在 DB 层就进不来，服务层不必再猜。
CREATE UNIQUE INDEX crm_service_ticket_sla_pause_open
  ON crm_service_ticket_sla_pause(tenant_id, ticket_id)
  WHERE resumed_at IS NULL;

CREATE INDEX crm_service_ticket_sla_pause_by_ticket
  ON crm_service_ticket_sla_pause(tenant_id, ticket_id, paused_at);

-- ===========================================================================
-- 6. 触发器
-- ===========================================================================
-- ---------------------------------------------------------------------------
-- 6.1 guard_crm_service_ticket_note_append_only · 备注只追加
-- ---------------------------------------------------------------------------
-- 为什么：备注与回复是**处理留痕**。改掉一条旧备注，
--   「当时跟买家说了什么」就再也复核不了了。
CREATE FUNCTION guard_crm_service_ticket_note_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '工单备注与回复**只追加**：改它会让「当时跟买家说了什么」变得不可复核'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_service_ticket_note_update_trg
  BEFORE UPDATE ON crm_service_ticket_note
  FOR EACH ROW EXECUTE FUNCTION guard_crm_service_ticket_note_append_only();

CREATE TRIGGER crm_service_ticket_note_delete_trg
  BEFORE DELETE ON crm_service_ticket_note
  FOR EACH ROW EXECUTE FUNCTION guard_crm_service_ticket_note_append_only();

-- ---------------------------------------------------------------------------
-- 6.2 guard_crm_service_ticket_action_append_only · 处理记录只追加
-- ---------------------------------------------------------------------------
-- 为什么：处理记录是**审计线**。可改可删的动作历史等于没有历史 ——
--   「谁在什么时候把 SLA 停了」必须查得到。
CREATE FUNCTION guard_crm_service_ticket_action_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '工单处理记录**只追加**：可改可删的动作历史等于没有历史'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_service_ticket_action_update_trg
  BEFORE UPDATE ON crm_service_ticket_action
  FOR EACH ROW EXECUTE FUNCTION guard_crm_service_ticket_action_append_only();

CREATE TRIGGER crm_service_ticket_action_delete_trg
  BEFORE DELETE ON crm_service_ticket_action
  FOR EACH ROW EXECUTE FUNCTION guard_crm_service_ticket_action_append_only();

-- ---------------------------------------------------------------------------
-- 6.3 guard_crm_service_ticket_sla_pause · 暂停区间不许重叠
-- ---------------------------------------------------------------------------
-- 为什么：重叠的暂停区间会让「暂停掉的那段营业时间」被**扣两次** ——
--   SLA 消耗会被算少，到期时刻被推后（客服被冤枉没超时）。
--   未恢复的暂停（resumed_at IS NULL）视作延伸到无穷远，
--   所以「还没恢复就再暂停一次」在 DB 层就进不来。
CREATE FUNCTION guard_crm_service_ticket_sla_pause() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_conflict uuid;
BEGIN
  SELECT p.id INTO v_conflict
    FROM crm_service_ticket_sla_pause p
   WHERE p.tenant_id = NEW.tenant_id
     AND p.ticket_id = NEW.ticket_id
     AND p.id <> NEW.id
     AND p.paused_at < COALESCE(NEW.resumed_at, 'infinity'::timestamptz)
     AND COALESCE(p.resumed_at, 'infinity'::timestamptz) > NEW.paused_at
   LIMIT 1;
  IF v_conflict IS NOT NULL THEN
    RAISE EXCEPTION '同一张工单的 SLA 暂停区间**不许重叠**（与 % 冲突）：重叠会让暂停掉的营业时间被扣两次，SLA 到期时刻被推后',
      v_conflict USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_service_ticket_sla_pause_trg
  BEFORE INSERT OR UPDATE ON crm_service_ticket_sla_pause
  FOR EACH ROW EXECUTE FUNCTION guard_crm_service_ticket_sla_pause();

-- ===========================================================================
-- 7. 行级安全（RLS）与授权
-- ===========================================================================
-- 与 V99 / V220 / V228 / V292 / V293 / V295 / V296 / V297 / V299 / V301 / V302 / V303
-- / V305 / V306 / V307 同一口径：ENABLE **与** FORCE 都要 ——
-- 只有 FORCE 才挡得住「运行角色被误授 owner」。
-- 策略名统一 isolation，USING 与 WITH CHECK 用**同一表达式**（两侧都写，
-- 否则 WITH CHECK 漏写就等于留了一条写入别的租户的口子）。
DO $$
DECLARE
  tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'crm_service_ticket_note',
    'crm_service_ticket_action',
    'crm_service_ticket_link',
    'crm_service_ticket_resolution',
    'crm_service_ticket_sla_pause'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_service_ticket_note,
  crm_service_ticket_action,
  crm_service_ticket_link,
  crm_service_ticket_resolution,
  crm_service_ticket_sla_pause TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_service_ticket_note,
  crm_service_ticket_action,
  crm_service_ticket_link,
  crm_service_ticket_resolution,
  crm_service_ticket_sla_pause TO starcon_app;

-- 🔴 备注 / 回复 / 处理记录**只追加**：改它或删它都会让处理过程不可复核。
REVOKE UPDATE,DELETE ON crm_service_ticket_note FROM crm_app;
REVOKE UPDATE,DELETE ON crm_service_ticket_note FROM starcon_app;
REVOKE UPDATE,DELETE ON crm_service_ticket_action FROM crm_app;
REVOKE UPDATE,DELETE ON crm_service_ticket_action FROM starcon_app;

REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ===========================================================================
-- 8. 留给上下游的口子（不是本模块的缺口）
-- ===========================================================================
--   * **15.4.1.1 工单列表**（crm 服务 V306）：本模块的**上游**，只读。
--     它**不建工单表**，SLA 口径（营业时间轴）由它建立；
--     本模块的 SLA 暂停 / 恢复**必须作用在同一条轴上**
--     （读同一套 crm_service_business_hours / _holiday 表）。
--   * **11.4.2.1 工单管理**（trade 服务）：工单事实的**唯一所有者**。
--     回复（首次）/ 状态流转 / 关闭 / 满意度 / 升级一律**转发**它的授权 HTTP API，
--     **一个字都不写它的表**，不建跨库 FK、不建共享视图、不直连对方库。
--   * **11 售后（升级）** 与 **9 订单**（下游）：
--     升级动作经 trade 的 `POST /{id}/escalate` **真的**在 trade 库里建出
--     售后单 / 平台纠纷（那是对方的唯一写入所有者干的活），
--     本模块只把**关系**记在 crm_service_ticket_link 里。
--   * **实际回复的投递**由 15.1.2.1 消息发送负责；本模块负责**工单侧的回复记录**
--     与**回复时限事实**（与上游 first-reply 同一口径）。
