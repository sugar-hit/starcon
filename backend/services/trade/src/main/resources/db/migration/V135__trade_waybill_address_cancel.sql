-- ===========================================================================
-- 10.2.1.3 改址与取消
-- ===========================================================================
-- 蓝图原文（10-logistics-and-freight.md:140）：
--   职责：处理运单层面的改址（部分渠道支持且有时限）与取消，联动订单地址变更与平台通知。
--   输入：改址/取消请求；渠道规则与时限。
--   输出：渠道操作结果；订单地址变更记录；买家通知任务（15）。
--   关系：上游 = 10.2.1.1、9.1.1.3；下游 = 承运商接口、15 客户与客服。
--         **改址是买家高频诉求**，超时的改址请求要明确告知能否成功而不是让客服猜。
--
-- ---------------------------------------------------------------------------
-- 零、本模块的边界与上游盘点（读迁移前必看，本 worktree 逐个 grep 核实）
-- ---------------------------------------------------------------------------
--   * 10.2.1.2 运单申请 —— **同服务同库**，V128。运单号与出号时刻的唯一写入所有者。
--     本模块**只读** logistics_waybill_request：改址/取消**只对已出号（ISSUED）的运单**
--     有意义（还没出号的作废属 10.2.1.2 的 cancel）。本迁移不 ALTER 它、不在它上面挂
--     触发器、不建跨模块 FK、不写它任何一列。承运商登记 logistics_carrier 同理只读。
--     跨车道 DDL 回归面 = 0。
--
--   * 9.1.1.3 订单变更与取消 —— **同服务同库**，V119（十张表，零 ALTER 既有表）。
--     ⚠️ **不重复建订单变更的单据表**：order_change_request / order_change_address_field
--     已经在 trade 实现了订单侧的地址变更，那两张表的写入所有者**永远是 9.1.1.3**。
--     本模块是**运单侧**的事实：它持有一张运单的改址 / 取消单据，并可携带
--     order_change_request_id 与订单侧地址变更**联动**（服务层校验其存在、类型为
--     ADDRESS_MODIFY、与本单同订单），但**本模块不替 9.1.1.3 写它任何一列**。
--     这正是蓝图那句「联动订单地址变更」的边界：联动 = 建立引用，不是另起一套单据。
--
--   * 15 客户与客服 —— **跨服务**（crm，端口 8086）。买家通知只能经授权 API / event，
--     不直连对方库、不建跨库 FK / 视图（AGENTS.md 模块边界纪律）。本模块因此把
--     「买家通知任务」落成**一条真实的待发任务行**（状态恒为 QUEUED，ownerModule=15），
--     由 15 接走发送 —— 本模块**不自己发通知、也不假装已发**。
--
--   * 10.1 物流渠道 —— **全部未交付**（10.1.1.1 / 10.1.1.2 / 10.1.1.3 / 10.1.2.1 /
--     10.1.2.2 台账全「未开发」），即**没有任何承运商连接配置**。承运商那一跳走显式端口
--     （CarrierRedirectPort，每个 vendor 独立包、只暴露统一接口），未接通时 resolve
--     如实返回 Unavailable —— 与 10.2.1.1 / 10.2.1.2 完全同构，**绝不凭空造一个已接通的
--     承运商、也绝不编一个「改址成功」出来**。
--
-- 被本迁移约束到的既有表 = 无。零 ALTER、零触发器挂载到别模块的表、零 FK 指向别模块的表。
--
-- ---------------------------------------------------------------------------
-- 一、本模块最要紧的一条：**「超时的改址请求要明确告知能否成功」**（蓝图加粗句）
-- ---------------------------------------------------------------------------
-- 改址能不能成，取决于两个**彼此独立**的事实：
--   1) 时限：渠道对改址有时限（多数承运商只允许出号后若干小时内改），过了就**不能成**，
--      而且**再也不会成** —— 不是重试一下就能过；
--   2) 连通：渠道接口有没有接通，决定「现在能不能把它发出去」。
-- 把这两件事混成一个「失败」就是让客服猜 —— 恰好是蓝图点名要避免的。
-- 所以本表把判定拆成**两个独立、可解释、可测试**的字段：
--   verdict（时间 / 规则维度）：WITHIN_WINDOW / DEADLINE_PASSED / CHANNEL_UNSUPPORTED
--                            / RULE_UNREGISTERED
--   can_succeed + can_succeed_reason（给客服的**直白结论**，不是异常堆栈）
-- 并把**判定的基准时刻** deadline_at 显式落库 —— 测试钉死这个时刻，不依赖 now()。
--
-- ⚠️ 时限的基准是**出号时刻 issued_at + 渠道登记的 deadline_hours**，两者都是**真实数据**
--   （issued_at 来自 10.2.1.2 已出号的那一票；deadline_hours 来自本模块的渠道规则登记）。
--   不是代码里的一个 if (hoursSinceIssue < 24)。渠道规则是租户可维护的表
--   （照抄 V119 的 order_platform_change_rule 形状）：各承运商的改址窗口本来就不一样。
--
-- ---------------------------------------------------------------------------
-- 二、四张表的关系
-- ---------------------------------------------------------------------------
--   logistics_carrier_redirect_rule        渠道规则与时限（**租户可维护的输入**，蓝图输入句第二项）
--        │ 1:N（按承运商代码）
--        ├──> logistics_waybill_redirect    改址单据（一张运单一次改址一条）
--        └──> logistics_waybill_cancel      运单取消单据（一张运单一次取消一条）
--              └──> logistics_buyer_notification_task  买家通知任务（15，输出句第三项）
--
-- 三张单据表与规则表 / 上游表之间**只做逻辑引用 + BEFORE 触发器校验**（同 V132 理由：
-- 跨模块外键会在对方 ALTER DDL 时产生回归面；真实存在性由服务层事务内核 +
-- 本文件的触发器双保险，两层都错不了才算数）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 单号发号器（RAC 改址 / WAC 运单取消 / BNT 买家通知）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE waybill_redirect_no_seq START 1;
CREATE SEQUENCE waybill_cancel_no_seq START 1;
CREATE SEQUENCE buyer_notification_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 2. logistics_carrier_redirect_rule · 渠道改址 / 取消规则与时限（**本模块的输入事实**）
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么是租户可维护的表而不是代码常量：「渠道规则与时限」是蓝图**输入句第二项**，
--   而各承运商的改址窗口、是否支持改址 / 取消本来就是**商务事实**
--   （有的承运商给 24 小时窗口，有的根本不接中途改址）。写死在 Java 里就等于宣称
--   「所有渠道都一个窗口」，那是假的 —— 与 V119 把平台可变更窗口做成表的同一理由。
--   本表只登记**规则**，不登记 endpoint / 凭据（后者归 secret manager 与 10.1）。
CREATE TABLE logistics_carrier_redirect_rule (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- === 承运商（与 V128 / V124 同一口径，不另起一套拼法）===
  -- 逻辑引用 logistics_carrier(code)：同一张待发货单上承运商代码只有一种拼法，
  -- 「按承运商分组」错一个字就会把两家的单并进一条规则。
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),

  -- === 改址能力与时限（蓝图输入句：渠道规则与时限）===
  redirect_supported boolean NOT NULL DEFAULT false,
  -- 改址窗口：从**出号时刻**起算多少小时内还能改。必须 > 0（0 意味着出号即截止，
  -- 那用 redirect_supported=false 表达，不用一个 0 小时的窗口 —— 两者语义不同：
  -- 「不支持」与「只给 0 小时」在客服话术里不是一回事）。
  redirect_deadline_hours int CHECK(redirect_deadline_hours IS NULL OR redirect_deadline_hours > 0),

  -- === 取消能力与时限 ===
  cancel_supported boolean NOT NULL DEFAULT false,
  cancel_deadline_hours int CHECK(cancel_deadline_hours IS NULL OR cancel_deadline_hours > 0),

  -- 「都不支持」时写明原因，界面上才有话说（否则只有一片灰而没人知道该找谁）
  unsupported_reason varchar(400) NOT NULL DEFAULT '',

  remark varchar(400) NOT NULL DEFAULT '',
  registered_by_membership_id uuid NOT NULL,
  registered_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  -- 同一租户下每个承运商一条规则：两条规则会让「按承运商分组」无解。
  UNIQUE(tenant_id,carrier_code),

  -- 支持改址就必须有窗口，否则「支持但没有时限」= 改址可以无限期成功，
  -- 而现实里没有承运商是这样。这条 CHECK 逼登记人把窗口填出来。
  CHECK(redirect_supported = (redirect_deadline_hours IS NOT NULL)),
  CHECK(cancel_supported = (cancel_deadline_hours IS NOT NULL)),
  -- 不支持却给了窗口，是登记矛盾（要么支持要么不支持，不能两头下注）。
  CHECK(redirect_supported OR redirect_deadline_hours IS NULL),
  CHECK(cancel_supported OR cancel_deadline_hours IS NULL),
  -- 两者都不支持必须写明原因（与 V128 logistics_carrier 的 connected 同一纪律）。
  CHECK(redirect_supported OR cancel_supported OR btrim(unsupported_reason) <> '')
);

-- 改址 / 取消判定要按承运商取规则，这条索引就是那个「取」的主路径。
CREATE INDEX carrier_redirect_rule_lookup
  ON logistics_carrier_redirect_rule(tenant_id,carrier_code);

-- ---------------------------------------------------------------------------
-- 3. logistics_waybill_redirect · 改址单据（**本模块的核心事实**）
-- ---------------------------------------------------------------------------
-- 一行 = **一张已出号运单上的一次改址请求**。
--
-- ⚠️ 为什么存新地址的完整快照而不是引用订单：改址改的是**这一票货**的投递地址，
--   它可以与订单地址不同（换仓发货、分仓、买家临时改约），而且承运商侧看到的就是
--   这个快照。订单地址是 9.1.1.3 的事实，本表不复制它的所有权 —— 只在
--   order_change_request_id 上建立**联动引用**（服务层校验）。
CREATE TABLE logistics_waybill_redirect (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- RAC-yyyyMMdd-000001
  redirect_no varchar(32) NOT NULL CHECK(btrim(redirect_no) <> ''),

  -- === 上游 10.2.1.2（逻辑引用，理由同第零节）===
  request_id uuid NOT NULL,
  request_no varchar(32) NOT NULL CHECK(btrim(request_no) <> ''),
  -- 改址只对**已出号**的运单有意义；快照下来是为了判据可复算（触发器会核对）。
  waybill_no varchar(128) NOT NULL CHECK(btrim(waybill_no) <> ''),
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),

  -- 出号时刻快照 + 由它算出的**改址截止时刻**。
  -- ⚠️ 这两个是本模块的判定基准，必须落库成可查询的事实：
  --   「这张票当时还能不能改」是事后会被追问的问题，答案不能只存在于一次计算的中间态。
  issued_at_snapshot timestamptz NOT NULL,
  -- deadline_at = issued_at_snapshot + 规则.redirect_deadline_hours；
  -- 规则未登记 / 不支持改址时为 NULL（**不假装有一个时限**）。
  -- 触发器按规则重算并核对，见 guard_waybill_redirect_target。
  deadline_at timestamptz,

  -- === 新地址快照（要发给承运商的那一份）===
  recipient_name varchar(200) NOT NULL CHECK(btrim(recipient_name) <> ''),
  recipient_country char(2) NOT NULL CHECK(recipient_country ~ '^[A-Z]{2}$'),
  address_line varchar(400) NOT NULL DEFAULT '',
  city varchar(120) NOT NULL DEFAULT '',
  postal_code varchar(32) NOT NULL DEFAULT '',

  -- 改址原因（客服 / 买家诉求原文，是回访与纠纷时的凭据）
  reason varchar(500) NOT NULL CHECK(btrim(reason) <> ''),

  -- === 判定结果：给客服的**明确结论**（蓝图加粗句的落点）===
  -- 维度一：时间 / 规则。四个值互斥且穷尽。
  verdict varchar(24) NOT NULL CHECK(verdict IN
    ('WITHIN_WINDOW','DEADLINE_PASSED','CHANNEL_UNSUPPORTED','RULE_UNREGISTERED')),
  -- 维度二：能否成功。**刻意独立于 verdict**，因为「在时限内但渠道没接通」
  -- 仍然是 can_succeed=false（现在发不出去），而话术要说的是「接通后仍可成功」。
  can_succeed boolean NOT NULL,
  can_succeed_reason varchar(500) NOT NULL DEFAULT '',

  -- === 渠道操作结果（蓝图输出句第一项：渠道操作结果）===
  -- 未接通 / 未派发时 outcome = NOT_ATTEMPTED，carrier_ref 为空。
  -- 承运商真实回话才写 ACCEPTED / REJECTED —— 绝不编一个「改址成功」。
  outcome varchar(16) NOT NULL DEFAULT 'NOT_ATTEMPTED' CHECK(outcome IN
    ('NOT_ATTEMPTED','ACCEPTED','REJECTED')),
  carrier_ref varchar(128) NOT NULL DEFAULT '',
  outcome_reason varchar(500) NOT NULL DEFAULT '',
  dispatched_at timestamptz,

  -- === 联动订单地址变更（9.1.1.3，**逻辑引用**，本模块不写它）===
  -- 携带它 = 这次改址对应订单侧那张 ADDRESS_MODIFY 单据。
  -- 服务层校验：该单存在、change_kind='ADDRESS_MODIFY'、与本运单同订单。
  order_change_request_id uuid,
  sales_order_no varchar(64) NOT NULL DEFAULT '',

  requested_by_membership_id uuid NOT NULL,
  -- 请求发起的时刻 = **时限判定的基准**（不是提交入库的时刻）。
  -- 运营补录一条昨天发起的改址时，判据必须是「昨天发起」，否则会被误判成超时。
  requested_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,redirect_no)
);

-- ⚠️ 刻意**不建**「一张运单同时只能有一条未出结果改址」的条件唯一索引：
--   看起来它能挡住重复提交，但 outcome=NOT_ATTEMPTED 在本模块**不是「在途」**，
--   它是「没有受理凭据」—— 渠道未接通时那张单会**永远**停在这个值。
--   建了那个索引，运营接通渠道后想重试会撞 23505，而界面上只显示「唯一键冲突」：
--   一个把「重试」堵死的约束，代价远大于它挡住的重复提交。
--   重复提交的正确处置由服务层承担：同一票可以一票多次，每次都是独立凭据，
--   而**派发是同步的**（见 WaybillRedirectModels.CarrierOutcome 的注释），
--   根本不存在「两个请求同时在飞」的窗口需要索引去挡。
CREATE INDEX waybill_redirect_request
  ON logistics_waybill_redirect(tenant_id,request_id,requested_at desc);
CREATE INDEX waybill_redirect_carrier
  ON logistics_waybill_redirect(tenant_id,carrier_code,requested_at desc);
CREATE INDEX waybill_redirect_verdict
  ON logistics_waybill_redirect(tenant_id,verdict,requested_at desc);
CREATE INDEX waybill_redirect_order_change
  ON logistics_waybill_redirect(tenant_id,order_change_request_id)
  WHERE order_change_request_id IS NOT NULL;

-- 凭据与结果的**自洽性**：有回执必有结果，有结果必有派发时刻。
--
-- ⚠️ 这里刻意**不**要求「NOT_ATTEMPTED ⇒ dispatched_at 为空」：服务层对每一次
--   真正发出的调用都会落 dispatched_at，而「发了但承运商不可达」如实落成
--   outcome=NOT_ATTEMPTED（没有受理凭据）—— 那**是一次真实的尝试**，
--   不是「没发过」。把它记成「从未派发」会把「我们试过、承运商没响应」
--   与「渠道压根没接通」混成同一个值，而这两件事在客服话术里完全不同。
--   真正要禁止的是**没有结果却带回执号**（那才是凭空造出的受理）。
CREATE OR REPLACE FUNCTION guard_waybill_redirect_outcome() RETURNS trigger AS $$
BEGIN
  IF NEW.outcome = 'NOT_ATTEMPTED' AND btrim(NEW.carrier_ref) <> '' THEN
    RAISE EXCEPTION '改址单 % 未受理（outcome=NOT_ATTEMPTED）却带了承运商回执 %',
      NEW.redirect_no, NEW.carrier_ref USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.outcome <> 'NOT_ATTEMPTED' AND (NEW.dispatched_at IS NULL OR btrim(NEW.carrier_ref) = '') THEN
    RAISE EXCEPTION '改址单 % 已产生渠道结果（outcome=%）却没有派发时刻或回执号',
      NEW.redirect_no, NEW.outcome USING ERRCODE = 'check_violation';
  END IF;
  -- 「能不能成功」与结论必须同时有话说：说得出 true/false 就必须写得出理由，
  -- 否则界面上又是一片空白让客服猜（这正是蓝图点名要避免的）。
  IF btrim(NEW.can_succeed_reason) = '' THEN
    RAISE EXCEPTION '改址单 % 缺少 can_succeed_reason：能否成功必须给直白结论',
      NEW.redirect_no USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_redirect_outcome_trg
  BEFORE INSERT OR UPDATE ON logistics_waybill_redirect
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_redirect_outcome();

-- 目标一致性 + **截止时刻由规则重算并核对**（照抄 V132 guard_waybill_freight_target 的形状：
-- 回表读上游当前行，不信调用方填的快照）。
--
-- 为什么必须重算 deadline_at：它是「超时改址」判定的唯一基准。服务层算错一次，
-- 界面上就会出现「明明没超时却说超时了」，而这一票从此再也不能改 —— 最贵的一种错。
-- DB 层再算一遍，两层不一致当场拒。
CREATE OR REPLACE FUNCTION guard_waybill_redirect_target() RETURNS trigger AS $$
DECLARE
  want_no varchar(128); want_carrier varchar(64); want_request_no varchar(32);
  want_issued timestamptz; want_status varchar(20);
  rule_hours int; rule_supported boolean;
  want_deadline timestamptz;
BEGIN
  SELECT waybill_no, carrier_code, request_no, issued_at, status
    INTO want_no, want_carrier, want_request_no, want_issued, want_status
    FROM logistics_waybill_request
   WHERE tenant_id = NEW.tenant_id AND id = NEW.request_id;

  IF want_no IS NULL THEN
    RAISE EXCEPTION '运单申请 % 不存在（tenant=%），不能对它改址',
      NEW.request_id, NEW.tenant_id USING ERRCODE = 'foreign_key_violation';
  END IF;

  -- 还没出号的运单**改不了址**：没单号承运商那边就没有可改的对象。
  -- （这种情况应该走 10.2.1.2 的作废，不是本模块。）
  IF want_status <> 'ISSUED' THEN
    RAISE EXCEPTION '运单申请 % 当前状态为 %，只有已出号（ISSUED）的运单才能改址；未出号请走 10.2.1.2 作废',
      NEW.request_no, want_status USING ERRCODE = 'check_violation';
  END IF;

  IF want_no <> NEW.waybill_no OR want_carrier <> NEW.carrier_code
     OR want_request_no <> NEW.request_no THEN
    RAISE EXCEPTION
      '改址单(%/%/%)与运单申请 % 的(%/%/%)不一致',
      NEW.waybill_no, NEW.carrier_code, NEW.request_no, NEW.request_id,
      want_no, want_carrier, want_request_no
      USING ERRCODE = 'check_violation';
  END IF;

  -- 出号时刻必须与上游一致：它与 deadline_at 一起构成判定基准，错了整票判错。
  IF want_issued IS DISTINCT FROM NEW.issued_at_snapshot THEN
    RAISE EXCEPTION '改址单 % 的出号时刻快照 % 与运单申请 % 的 % 不一致',
      NEW.redirect_no, NEW.issued_at_snapshot, NEW.request_no, want_issued
      USING ERRCODE = 'check_violation';
  END IF;

  -- 按渠道规则重算截止时刻（规则可能未登记 → 规则列为空 → deadline 必须为空）。
  SELECT redirect_supported, redirect_deadline_hours INTO rule_supported, rule_hours
    FROM logistics_carrier_redirect_rule
   WHERE tenant_id = NEW.tenant_id AND carrier_code = NEW.carrier_code;

  IF NOT FOUND THEN
    IF NEW.deadline_at IS NOT NULL THEN
      RAISE EXCEPTION '承运商 % 未登记改址规则，却给了改址截止时刻 %',
        NEW.carrier_code, NEW.deadline_at USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NOT rule_supported THEN
    IF NEW.deadline_at IS NOT NULL THEN
      RAISE EXCEPTION '承运商 % 不支持改址，却给了改址截止时刻 %',
        NEW.carrier_code, NEW.deadline_at USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  want_deadline := want_issued + make_interval(hours => rule_hours);
  IF NEW.deadline_at IS DISTINCT FROM want_deadline THEN
    RAISE EXCEPTION '改址单 % 的截止时刻 % 与规则重算值 % 不一致（出号 % + % 小时）',
      NEW.redirect_no, NEW.deadline_at, want_deadline, want_issued, rule_hours
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_redirect_target_trg
  BEFORE INSERT OR UPDATE ON logistics_waybill_redirect
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_redirect_target();

-- 改址的判定是一经写下就是凭据的东西：「当时判成能不能改」会被事后追问
-- （买家投诉、平台纠纷、客服复盘），就地改掉等于让「当时是怎么说的」失去唯一答案。
-- 与 V132 的运费登记同一纪律。
--
-- ⚠️ 但「凭据」分成**两段**，这条触发器必须分清，否则会把承运商真实回话也挡掉：
--   * **永久不可改**（时间与规则维度）：运单归属、出号时刻、截止时刻、verdict、
--     requested_at。它们是判定**基准**，一旦写下就代表「当时算的是这一套」。
--   * **只允许在出结果之前定稿一次**（结论维度）：can_succeed + can_succeed_reason。
--     插入时服务层先按「在时限内 + 渠道通不通」给一版**预判**；派发后拿到承运商
--     真实回话（ACCEPTED / REJECTED / 不可达）要把它定稿成**终稿**。
--     若把这两列也钉成永久不可改，一次真实受理会撞 check_violation 回滚 ——
--     最贵的一种错：界面永远停在「未受理」，而承运商那边已经改了。
--     所以这里只允许 OLD.outcome='NOT_ATTEMPTED'（还没有结果）时改写一次；
--     一旦有了结果就冻结，"当时承运商怎么回的"同样不能被事后抹掉。
CREATE OR REPLACE FUNCTION guard_waybill_redirect_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.tenant_id            IS DISTINCT FROM OLD.tenant_id
     OR NEW.request_id       IS DISTINCT FROM OLD.request_id
     OR NEW.request_no       IS DISTINCT FROM OLD.request_no
     OR NEW.waybill_no       IS DISTINCT FROM OLD.waybill_no
     OR NEW.carrier_code     IS DISTINCT FROM OLD.carrier_code
     OR NEW.issued_at_snapshot IS DISTINCT FROM OLD.issued_at_snapshot
     OR NEW.deadline_at      IS DISTINCT FROM OLD.deadline_at
     OR NEW.verdict          IS DISTINCT FROM OLD.verdict
     OR NEW.requested_at     IS DISTINCT FROM OLD.requested_at THEN
    RAISE EXCEPTION
      '改址判定是凭据：不允许改写运单归属、判定基准与时间维度结论；要重新判定请发起一条新的改址单'
      USING ERRCODE = 'check_violation';
  END IF;
  -- 结论维度只在「还没有结果」时允许定稿一次；出了结果之后一律冻结。
  IF OLD.outcome <> 'NOT_ATTEMPTED'
     AND (NEW.can_succeed IS DISTINCT FROM OLD.can_succeed
       OR NEW.can_succeed_reason IS DISTINCT FROM OLD.can_succeed_reason) THEN
    RAISE EXCEPTION
      '改址单 % 已产生渠道结果（outcome=%），结论 can_succeed/can_succeed_reason 不允许再改写',
      OLD.redirect_no, OLD.outcome USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_redirect_immutable_trg
  BEFORE UPDATE ON logistics_waybill_redirect
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_redirect_immutable();

-- ---------------------------------------------------------------------------
-- 4. logistics_waybill_cancel · 运单取消单据（**已出号运单的取消**）
-- ---------------------------------------------------------------------------
-- ⚠️ 与 10.2.1.2 的 cancel（作废**还没出号**的申请）是两件事，刻意分成两张表：
--   * 10.2.1.2 cancel：还没出号 → 没有任何承运商侧事实要撤销 → 作废一张申请；
--   * 本表：已出号 → 承运商那边已经有号、可能已经在路上 → 必须**去承运商侧撤销**。
-- 把两者合到一张表里，「取消」就会在「没号 / 有号」之间静默改变语义，
-- 而这正是承运商按已出票计费的起点。命名空间也不同（本表 cancel_no 前缀 WAC，
-- 上游是申请作废），白名单与单号都不会互相撞。
CREATE TABLE logistics_waybill_cancel (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- WAC-yyyyMMdd-000001
  cancel_no varchar(32) NOT NULL CHECK(btrim(cancel_no) <> ''),

  -- === 上游 10.2.1.2（逻辑引用）===
  request_id uuid NOT NULL,
  request_no varchar(32) NOT NULL CHECK(btrim(request_no) <> ''),
  waybill_no varchar(128) NOT NULL CHECK(btrim(waybill_no) <> ''),
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),

  -- 与改址同一套判据：出号时刻 + 截止时刻（取消通常比改址宽松，但**各家不同**，
  -- 所以同样是登记来的数据而不是常量）。
  issued_at_snapshot timestamptz NOT NULL,
  deadline_at timestamptz,

  -- 取消原因（凭据：退件、买家改主意、地址不可达、重复下单……）
  reason varchar(500) NOT NULL CHECK(btrim(reason) <> ''),

  verdict varchar(24) NOT NULL CHECK(verdict IN
    ('WITHIN_WINDOW','DEADLINE_PASSED','CHANNEL_UNSUPPORTED','RULE_UNREGISTERED')),
  can_succeed boolean NOT NULL,
  can_succeed_reason varchar(500) NOT NULL DEFAULT '',

  -- 承运商操作结果（蓝图输出句第一项）
  outcome varchar(16) NOT NULL DEFAULT 'NOT_ATTEMPTED' CHECK(outcome IN
    ('NOT_ATTEMPTED','ACCEPTED','REJECTED')),
  carrier_ref varchar(128) NOT NULL DEFAULT '',
  outcome_reason varchar(500) NOT NULL DEFAULT '',
  dispatched_at timestamptz,

  -- 联动订单取消（9.1.1.3 order_cancellation_request，**逻辑引用**，本模块不写它）。
  -- 与改址一样：联动 = 引用，不是另起一套单据。
  order_cancellation_request_id uuid,
  sales_order_no varchar(64) NOT NULL DEFAULT '',

  requested_by_membership_id uuid NOT NULL,
  requested_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,cancel_no)
);

-- 一张运单同一时刻只能有一条**未出结果**的取消同理不建条件唯一索引（理由见改址那处）。
CREATE INDEX waybill_cancel_request
  ON logistics_waybill_cancel(tenant_id,request_id,requested_at desc);
CREATE INDEX waybill_cancel_carrier
  ON logistics_waybill_cancel(tenant_id,carrier_code,requested_at desc);
CREATE INDEX waybill_cancel_verdict
  ON logistics_waybill_cancel(tenant_id,verdict,requested_at desc);

-- 与改址同形，且同样区分「没发过」与「发了但没回话」：派发时刻允许与
-- outcome=NOT_ATTEMPTED 并存（承运商不可达是一次真实尝试），禁止的仍只是
-- 「没有结果却带回执号」。
CREATE OR REPLACE FUNCTION guard_waybill_cancel_outcome() RETURNS trigger AS $$
BEGIN
  IF NEW.outcome = 'NOT_ATTEMPTED' AND btrim(NEW.carrier_ref) <> '' THEN
    RAISE EXCEPTION '取消单 % 未受理（outcome=NOT_ATTEMPTED）却带了承运商回执 %',
      NEW.cancel_no, NEW.carrier_ref USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.outcome <> 'NOT_ATTEMPTED' AND (NEW.dispatched_at IS NULL OR btrim(NEW.carrier_ref) = '') THEN
    RAISE EXCEPTION '取消单 % 已产生渠道结果（outcome=%）却没有派发时刻或回执号',
      NEW.cancel_no, NEW.outcome USING ERRCODE = 'check_violation';
  END IF;
  IF btrim(NEW.can_succeed_reason) = '' THEN
    RAISE EXCEPTION '取消单 % 缺少 can_succeed_reason：能否成功必须给直白结论',
      NEW.cancel_no USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_cancel_outcome_trg
  BEFORE INSERT OR UPDATE ON logistics_waybill_cancel
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_cancel_outcome();

-- 目标一致性 + 截止时刻重算（与改址同一解法，回表读上游与规则，不信调用方快照）。
CREATE OR REPLACE FUNCTION guard_waybill_cancel_target() RETURNS trigger AS $$
DECLARE
  want_no varchar(128); want_carrier varchar(64); want_request_no varchar(32);
  want_issued timestamptz; want_status varchar(20);
  rule_hours int; rule_supported boolean;
  want_deadline timestamptz;
BEGIN
  SELECT waybill_no, carrier_code, request_no, issued_at, status
    INTO want_no, want_carrier, want_request_no, want_issued, want_status
    FROM logistics_waybill_request
   WHERE tenant_id = NEW.tenant_id AND id = NEW.request_id;

  IF want_no IS NULL THEN
    RAISE EXCEPTION '运单申请 % 不存在（tenant=%），不能取消',
      NEW.request_id, NEW.tenant_id USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF want_status <> 'ISSUED' THEN
    RAISE EXCEPTION '运单申请 % 当前状态为 %，只有已出号（ISSUED）的运单才走本模块的取消；未出号请走 10.2.1.2 作废',
      NEW.request_no, want_status USING ERRCODE = 'check_violation';
  END IF;

  IF want_no <> NEW.waybill_no OR want_carrier <> NEW.carrier_code
     OR want_request_no <> NEW.request_no THEN
    RAISE EXCEPTION
      '取消单(%/%/%)与运单申请 % 的(%/%/%)不一致',
      NEW.waybill_no, NEW.carrier_code, NEW.request_no, NEW.request_id,
      want_no, want_carrier, want_request_no
      USING ERRCODE = 'check_violation';
  END IF;

  IF want_issued IS DISTINCT FROM NEW.issued_at_snapshot THEN
    RAISE EXCEPTION '取消单 % 的出号时刻快照 % 与运单申请 % 的 % 不一致',
      NEW.cancel_no, NEW.issued_at_snapshot, NEW.request_no, want_issued
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT cancel_supported, cancel_deadline_hours INTO rule_supported, rule_hours
    FROM logistics_carrier_redirect_rule
   WHERE tenant_id = NEW.tenant_id AND carrier_code = NEW.carrier_code;

  IF NOT FOUND OR NOT rule_supported THEN
    IF NEW.deadline_at IS NOT NULL THEN
      RAISE EXCEPTION '承运商 % 没有可用的取消时限规则，却给了取消截止时刻 %',
        NEW.carrier_code, NEW.deadline_at USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  want_deadline := want_issued + make_interval(hours => rule_hours);
  IF NEW.deadline_at IS DISTINCT FROM want_deadline THEN
    RAISE EXCEPTION '取消单 % 的截止时刻 % 与规则重算值 % 不一致（出号 % + % 小时）',
      NEW.cancel_no, NEW.deadline_at, want_deadline, want_issued, rule_hours
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_cancel_target_trg
  BEFORE INSERT OR UPDATE ON logistics_waybill_cancel
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_cancel_target();

-- 取消的判定同样是凭据，且**同样分两段**（理由见改址那处）：
-- 判定基准（运单归属 / 出号时刻 / 截止时刻 / verdict / requested_at）永久冻结；
-- 结论（can_succeed / can_succeed_reason）只在还没出结果时允许定稿一次。
CREATE OR REPLACE FUNCTION guard_waybill_cancel_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.tenant_id            IS DISTINCT FROM OLD.tenant_id
     OR NEW.request_id       IS DISTINCT FROM OLD.request_id
     OR NEW.request_no       IS DISTINCT FROM OLD.request_no
     OR NEW.waybill_no       IS DISTINCT FROM OLD.waybill_no
     OR NEW.carrier_code     IS DISTINCT FROM OLD.carrier_code
     OR NEW.issued_at_snapshot IS DISTINCT FROM OLD.issued_at_snapshot
     OR NEW.deadline_at      IS DISTINCT FROM OLD.deadline_at
     OR NEW.verdict          IS DISTINCT FROM OLD.verdict
     OR NEW.requested_at     IS DISTINCT FROM OLD.requested_at THEN
    RAISE EXCEPTION
      '取消判定是凭据：不允许改写运单归属、判定基准与时间维度结论；要重新判定请发起一条新的取消单'
      USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.outcome <> 'NOT_ATTEMPTED'
     AND (NEW.can_succeed IS DISTINCT FROM OLD.can_succeed
       OR NEW.can_succeed_reason IS DISTINCT FROM OLD.can_succeed_reason) THEN
    RAISE EXCEPTION
      '取消单 % 已产生渠道结果（outcome=%），结论 can_succeed/can_succeed_reason 不允许再改写',
      OLD.cancel_no, OLD.outcome USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_cancel_immutable_trg
  BEFORE UPDATE ON logistics_waybill_cancel
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_cancel_immutable();

-- ---------------------------------------------------------------------------
-- 5. logistics_buyer_notification_task · 买家通知任务（**蓝图输出句第三项**）
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么只落「任务」不自己发：15 客户与客服在 **crm 服务**（端口 8086），
--   跨服务禁止直连对方库 / 跨库 FK / 视图（AGENTS.md 模块边界纪律）。
--   而「已发出的通知」是一件**已经发生过的事**，不能靠本模块编一个 SENT 出来 ——
--   那会让客服以为买家已经收到而不再跟进。所以本表是**待发任务**：
--   状态只有 QUEUED（15 消费前一律 QUEUED），ownerModule=15，
--   真正发出后由 15 回写（或由本模块提供的人工回执形状登记，本模块刻意不提供）。
--
-- 什么时候**必须**建一条：改址 / 取消**产生了需要买家知道的结论**时。
--   * 受理成功 / 承运商拒绝 → 买家要知道自己那票怎么了；
--   * 超时不能改 → 蓝图加粗句点名的高频场景，**更**必须告知（否则客服被反复追问）；
--   * 渠道不支持 / 规则未登记 → 同样是买家侧需要一个明确答案。
--   而「还在等承运商回话」（NOT_ATTEMPTED 且渠道未接通）**不建任务** ——
--   那还没有结论可通知，提前发一条「正在处理」会让买家以为已经改好了。
CREATE TABLE logistics_buyer_notification_task (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- BNT-yyyyMMdd-000001
  notification_no varchar(32) NOT NULL CHECK(btrim(notification_no) <> ''),

  -- 来源：指向本模块的单据。刻意用**两个可空列 + CHECK 二选一**而不是一个多态
  -- source_id —— 多态外键在 PG 里建不了约束，两处都空或两处都填的错误就再也拦不住。
  redirect_id uuid,
  cancel_id uuid,
  CHECK((redirect_id IS NOT NULL)::int + (cancel_id IS NOT NULL)::int = 1),

  -- 通知对象与载体（15 去发；本模块**不持有买家联系方式的权威副本**）。
  -- 订单号是稳定的寻址依据，买家联系方式归 15 / 客户档案所有。
  sales_order_no varchar(64) NOT NULL DEFAULT '',
  waybill_no varchar(128) NOT NULL DEFAULT '',
  carrier_code varchar(64) NOT NULL DEFAULT '',

  -- 通知类型与**面向买家的措辞要点**（不是模板 ID —— 模板归 15）。
  -- 写清「发生了什么」而不是「请注意」：承运商时限一旦过了，买家需要知道
  -- 「这一票改不了了」而不是收到一条没有结论的提醒。
  notification_kind varchar(32) NOT NULL CHECK(notification_kind IN
    ('REDIRECT_ACCEPTED','REDIRECT_REJECTED','REDIRECT_EXPIRED',
     'REDIRECT_UNSUPPORTED','CANCEL_ACCEPTED','CANCEL_REJECTED',
     'CANCEL_EXPIRED','CANCEL_UNSUPPORTED')),
  subject_line varchar(200) NOT NULL DEFAULT '',
  body_summary varchar(800) NOT NULL DEFAULT '',

  -- 恒为 QUEUED：本模块不发送，也没有「已送达」可写。
  -- 15 消费后由它自己管发出状态，本表不越界代写（与「每张事实表只有一个写入所有者」一致）。
  status varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(status = 'QUEUED'),
  owner_module varchar(16) NOT NULL DEFAULT '15' CHECK(owner_module = '15'),

  created_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,notification_no),
  -- 同一条改址 / 取消**只建一次**通知任务：重复触发不会让买家收到两条一样的消息。
  UNIQUE(tenant_id,redirect_id),
  UNIQUE(tenant_id,cancel_id)
);

CREATE INDEX buyer_notification_pending
  ON logistics_buyer_notification_task(tenant_id,status,created_at);
CREATE INDEX buyer_notification_order
  ON logistics_buyer_notification_task(tenant_id,sales_order_no,created_at desc);

-- 通知任务必须**真的挂在一条存在的单据上**（回表核对，顺带保证承运商与运单号一致）。
-- 没有这条，「为一张不存在的运单通知买家」会成为可能 —— 那是最不该发出去的消息。
CREATE OR REPLACE FUNCTION guard_buyer_notification_target() RETURNS trigger AS $$
DECLARE src_no varchar(32); src_waybill varchar(128); src_carrier varchar(64); src_order varchar(64);
BEGIN
  IF NEW.redirect_id IS NOT NULL THEN
    SELECT redirect_no, waybill_no, carrier_code, sales_order_no
      INTO src_no, src_waybill, src_carrier, src_order
      FROM logistics_waybill_redirect WHERE tenant_id = NEW.tenant_id AND id = NEW.redirect_id;
    IF src_no IS NULL THEN
      RAISE EXCEPTION '改址单 % 不存在（tenant=%），不能建通知任务',
        NEW.redirect_id, NEW.tenant_id USING ERRCODE = 'foreign_key_violation';
    END IF;
  ELSE
    SELECT cancel_no, waybill_no, carrier_code, sales_order_no
      INTO src_no, src_waybill, src_carrier, src_order
      FROM logistics_waybill_cancel WHERE tenant_id = NEW.tenant_id AND id = NEW.cancel_id;
    IF src_no IS NULL THEN
      RAISE EXCEPTION '取消单 % 不存在（tenant=%），不能建通知任务',
        NEW.cancel_id, NEW.tenant_id USING ERRCODE = 'foreign_key_violation';
    END IF;
  END IF;

  -- 通知内容必须与来源单据一致：把 A 单的结果通知成 B 单，客服会照着错误的消息回访。
  IF NEW.waybill_no <> src_waybill OR NEW.carrier_code <> src_carrier THEN
    RAISE EXCEPTION '通知任务的运单/承运商(%/%)与来源单据 % 的(%/%)不一致',
      NEW.waybill_no, NEW.carrier_code, src_no, src_waybill, src_carrier
      USING ERRCODE = 'check_violation';
  END IF;
  -- 订单号：来源单据有订单号时必须一致；来源没有（补录的运单）时通知也不许自己编一个。
  IF btrim(src_order) <> '' AND NEW.sales_order_no <> src_order THEN
    RAISE EXCEPTION '通知任务的订单号 % 与来源单据 % 的 % 不一致',
      NEW.sales_order_no, src_no, src_order USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_buyer_notification_target_trg
  BEFORE INSERT ON logistics_buyer_notification_task
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_notification_target();

-- 通知任务是**待发凭据**：一旦建成就不能改写收件对象与措辞，也不能删。
-- 「已发过一条措辞不同的通知」和「这条通知不存在」一样会让客服无法回溯。
CREATE OR REPLACE FUNCTION guard_buyer_notification_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '买家通知任务是凭据：只追加不修改不删除（发出去的通知无法收回）'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_buyer_notification_frozen_trg
  BEFORE UPDATE OR DELETE ON logistics_buyer_notification_task
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_notification_immutable();

-- ---------------------------------------------------------------------------
-- 6. 多租户与授权（与 trade V1 / V116 / V124 / V128 / V132 同一套写法）
-- ---------------------------------------------------------------------------
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_carrier_redirect_rule',
    'logistics_waybill_redirect',
    'logistics_waybill_cancel',
    'logistics_buyer_notification_task' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  logistics_carrier_redirect_rule,
  logistics_waybill_redirect,
  logistics_waybill_cancel,
  logistics_buyer_notification_task TO trade_app;

-- 买家通知任务是**只追加的待发凭据**：连运行角色都不给改删，
-- 兜底在权限层而不只在触发器里（与 V132 的运费 / 打印留痕同一纪律）。
-- 授权在前、收回在后 —— 反过来写会被上面那条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON logistics_buyer_notification_task FROM trade_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V116 / V128 / V132 同口径）。
REVOKE ALL ON
  logistics_carrier_redirect_rule,
  logistics_waybill_redirect,
  logistics_waybill_cancel,
  logistics_buyer_notification_task FROM starcon_app;

-- ⚠️ 三个发号器必须**逐个**授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
--   漏了症状是「写库时 sequence does not exist」，而那是迁移之后第一次下单才炸。
GRANT USAGE, SELECT ON SEQUENCE
  waybill_redirect_no_seq,
  waybill_cancel_no_seq,
  buyer_notification_no_seq TO trade_app;
