-- ===========================================================================
-- 10.3.2.2 揽收与签收确认（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.3.2.2 揽收与签收确认
--   - 职责：确认承运商揽收（避免超时未揽收被平台判未发货）与签收（触发售后窗口关闭
--           与评价邀请时机）。
--   - 输入：承运商揽收/签收事件；SLA 时限。
--   - 输出：状态更新 + 提醒任务。
--   - 关系：上游 = 10.3.1.1；下游 = 9.4.2.1 回传、15 客服（邀请评价）。
--           **揽收状态直接影响平台履约指标**，需主动监控而非等轨迹同步。
--
-- 归属 channel：卷 10 卷首「channel 拥有物流商连接与轨迹」，L2 表 10.3 物流跟踪 =
-- channel；同 L3 的 10.3.1.1 轨迹查询（V212）、10.3.2.1 交运批次（V129）都已在
-- channel，本模块是这两个的**汇合点**。
--
-- ---------------------------------------------------------------------------
-- 一、本模块的形状 = 交运责任（10.3.2.1）× 承运商事件（10.3.1.1）
-- ---------------------------------------------------------------------------
-- 蓝图输入句有两样东西：承运商揽收/签收事件、SLA 时限。而「超时未揽收」这个判定
-- 缺一样东西都算不出来 —— 没有交运责任起点，就不知道「超时」是相对谁而言的。
--
-- 所以本模块把两个上游接起来：
--   * **责任起点来自 10.3.2.1 交运批次**。蓝图加粗句「批次交接是责任分界点：
--     批次内包裹的丢失责任在承运商」意味着**交运清单逐件**才是「这一件何时、
--     交给了谁」的凭据（V129 的 handover_manifest_item，append-only）。本模块的
--     确认单**一件一行**（= 一个交运件 = 一个包裹），责任起点 = 该件的
--     handover_at，**不是**批次的粗粒度时刻。
--   * **证据来自 10.3.1.1 轨迹事件**。承运商推送的 PICKED_UP / DELIVERED 节点
--     落在 logistics_tracking_event（append-only，按承运商事件 ID 去重）。
--
-- ⚠️ 两边要**对齐**才谈得上「这一件被揽收了」，对齐的钥匙是运单号：
--     handover_manifest_item.waybill_no  ⇄  logistics_tracking_subject.master_waybill_no
-- 这是**同库同服务的逻辑引用**，不是跨服务 SQL / FK（运单真值在 trade，本模块
-- 一条 SQL 都不碰 trade 的库）。
--
-- 🔴 运单号对齐不上时**如实记缺口，不猜**：
--   * 交运件没有运单号（10.2.1.2 运单申请未交付，清单里 waybill_no 为空并写了
--     缺口原因）⇒ 无法与承运商事件对齐 ⇒ gap_code='NO_WAYBILL'，
--     只能靠人工确认（POST /cases/{id}/attest）推进；
--   * 找不到对应轨迹主体（上游 10.3.1.1 还没同步这一票）⇒ 'TRACKING_SUBJECT_MISSING'；
--   * 多个轨迹主体命中同一运单号（master_waybill_no 上没有唯一约束，
--     V212 只给了普通索引）⇒ 'SUBJECT_AMBIGUOUS'，**不自动确认** ——
--     猜错一件等于把别的货标成已揽收，而「已揽收」是要回传平台履约指标的。
--
-- ---------------------------------------------------------------------------
-- 二、时限是**快照**，改档期不能让已延误的货变成准点
-- ---------------------------------------------------------------------------
-- SLA 时限的来源是本表的 logistics_pickup_signoff_sla_rule（按承运商，可选再按
-- 渠道）。规则被改过之后，**已存在的确认单不受影响**：确认单上的
-- pickup_sla_hours / pickup_deadline_at 是**建单时算好的快照**，
-- 与 V212 的 promised_eta_at 同一个口径（迟发率必须按发货当时的承诺算）。
--
-- 快照只在**还没有时限**时才补上（deadline 为空才写）——
-- 否则「改了规则」就能把一笔已经超时的货洗成准点，那正是这套快照要防的事。
--
-- 🔴 时限单位是**小时**，落 numeric(19,6) + BigDecimal，**禁浮点**：
-- 0.1 小时的误差在跨 8 小时时区差上是 6 分钟，而「超时未揽收」直接决定平台
-- 会不会判我们未发货 —— 这 6 分钟不能是浮点误差。
--
-- ---------------------------------------------------------------------------
-- 三、超时判定是**读时**的，不是写死的
-- ---------------------------------------------------------------------------
-- pickup_state 的三档：
--   PENDING  未确认揽收且未超时
--   OVERDUE  未确认揽收且**已过时限**
--   CONFIRMED 已确认揽收
--
-- 「是否超时」取决于现在几点，所以 **OVERDUE 是在巡检时按 now() 判出来的**，
-- 且 overdue_hours 记录的是**巡检那一刻**的超时小时数（不是签收时的）——
-- 它是「到巡检为止晚了多久」的读数，会随巡检继续增长，这正是它的用途。
--
-- ---------------------------------------------------------------------------
-- 四、提醒任务与下游待通知：两本账，**都不假装下游已存在**
-- ---------------------------------------------------------------------------
-- 蓝图输出句点名「提醒任务」⇒ logistics_pickup_signoff_reminder。
--
-- 下游 = 9.4.2.1 回传（**已在 trade 交付**，但 channel 与它之间**没有接线**：
-- 本轮不跨服务调用它）、15 客服（15.2.2.1 索评邀请、售后窗口，**未开发**）。
-- 所以本模块的「回传 / 评价邀请」以**本服务内的待通知记录**落地：
-- logistics_pickup_signoff_notification，每条显式记 downstream_module 与
-- downstream_status —— 下游没接线时是 PENDING/READY，**不写「已回传」**。
--
-- 🔴 评价邀请与售后窗口的**时机**是真算的：签收时刻 + 规则里的
-- review_invite_delay_hours / after_sales_window_hours ⇒ due_at。
-- 到了 due_at 状态才从 PENDING 变 READY（待下游消费），没到就是 PENDING。
-- 「签收了但评价邀请还没到点」是一个真实的中间态，不是漏发。
--
-- ---------------------------------------------------------------------------
-- 五、跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器 / 函数 / 策略，零 ALTER 任何既有表**：
--   * 不碰 V129 handover 族、不碰 V212 轨迹族、不碰 V136 / V139 / V141；
--   * 唯一引用的既有表都是**同库复合外键**（带 tenant_id ⇒ 跨租户挂载不可能）：
--     handover_batch、handover_manifest_item、logistics_tracking_subject、
--     logistics_tracking_event、logistics_channel_config；
--   * 不碰 trade / warehouse 任何一张表；
--   * 复用 identity 已注册的 channel.operation.* 权限码，**identity 零改动**。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：PSO-yyyyMMdd-000001（确认单号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE logistics_pickup_signoff_case_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. logistics_pickup_signoff_sla_rule · 揽收/签收时限规则
-- ---------------------------------------------------------------------------
-- 一行 = 「这个承运商（可选再限定某个渠道）承运的货，揽收时限是多少小时、
-- 提前多久提醒、签收后多久邀请评价、售后窗口多长」。
--
-- 为什么按承运商 + 可选渠道两档：承运商的揽收时限是**承运商属性**（同一家承运商
-- 对所有渠道一个口径），但大客户专线会另谈一个更紧的时限 —— 所以渠道级规则
-- 优先于承运商级规则，**取最具体的一条**，不叠加。
CREATE TABLE logistics_pickup_signoff_sla_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  carrier_code varchar(64) NOT NULL CHECK (btrim(carrier_code) <> ''),
  -- NULL = 承运商级（对该承运商所有渠道生效）；非空 = 渠道级（更具体，优先）
  channel_id uuid,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE RESTRICT,

  -- === 时限（小时，numeric(19,6) + BigDecimal，禁浮点；见文件头第二节）===
  -- 承运商应当在交运后多久之内完成揽收
  pickup_sla_hours numeric(19,6) NOT NULL CHECK (pickup_sla_hours > 0),
  -- 到点前多久发「即将超时」提醒
  reminder_lead_hours numeric(19,6) NOT NULL DEFAULT 4 CHECK (reminder_lead_hours >= 0),
  -- 签收后延迟多久发起评价邀请（0 = 签收即到时机）
  review_invite_delay_hours numeric(19,6) NOT NULL DEFAULT 0
    CHECK (review_invite_delay_hours >= 0),
  -- 签收后售后窗口多长（小时）：到期才关窗口
  after_sales_window_hours numeric(19,6) NOT NULL DEFAULT 168
    CHECK (after_sales_window_hours > 0),

  status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 承运商级规则：一个承运商只允许一条（渠道级另算）
CREATE UNIQUE INDEX logistics_pickup_signoff_sla_rule_by_carrier
  ON logistics_pickup_signoff_sla_rule(tenant_id, carrier_code)
  WHERE channel_id IS NULL;

-- 渠道级规则：一个承运商 + 一个渠道只允许一条
CREATE UNIQUE INDEX logistics_pickup_signoff_sla_rule_by_channel
  ON logistics_pickup_signoff_sla_rule(tenant_id, carrier_code, channel_id)
  WHERE channel_id IS NOT NULL;

-- 规则列表 / 命中查找
CREATE INDEX logistics_pickup_signoff_sla_rule_by_status
  ON logistics_pickup_signoff_sla_rule(tenant_id, status);

-- ---------------------------------------------------------------------------
-- 2. logistics_pickup_signoff_case · 确认单（一件一行 = 一个交运件）
-- ---------------------------------------------------------------------------
-- 一行 = 一个**交运件**（V129 handover_manifest_item 的一行）。
-- 为什么是包裹维度而不是运单维度：交接清单是逐件的（V129 的 UNIQUE
-- (tenant_id,batch_id,candidate_id,parcel_no)），而「超时未揽收」在纠纷里
-- 恰恰是**按箱**问的（「哪一箱没被揽收」）—— 逐件才有追责的落点。
--
-- 责任起点 = 该件的 handover_at（不是批次的时刻）：同一批里先交的两件与
-- 后交的两件，承运商的揽收义务是分别起算的。
CREATE TABLE logistics_pickup_signoff_case (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- PSO-yyyyMMdd-000001
  case_no varchar(32) NOT NULL DEFAULT '',

  -- === 上游 10.3.2.1 交运清单逐件（同库复合外键）===
  source_manifest_item_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, source_manifest_item_id)
    REFERENCES handover_manifest_item(tenant_id, id) ON DELETE RESTRICT,
  -- 一个交运件只对应一个确认单
  UNIQUE (tenant_id, source_manifest_item_id),

  source_batch_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, source_batch_id)
    REFERENCES handover_batch(tenant_id, id) ON DELETE RESTRICT,
  source_batch_no varchar(32) NOT NULL DEFAULT '',
  -- 批次状态快照：批次作废 ⇒ 责任从未转移
  source_batch_status varchar(16) NOT NULL DEFAULT '',

  -- === 上游 10.3.1.1 轨迹主体（同库复合外键，可空 = 还没同步到这一票）===
  source_subject_id uuid,
  FOREIGN KEY (tenant_id, source_subject_id)
    REFERENCES logistics_tracking_subject(tenant_id, id) ON DELETE RESTRICT,
  -- 命中了几个轨迹主体：>1 = 运单号不唯一，**不自动确认**（见文件头第一节）
  subject_match_count int NOT NULL DEFAULT 0 CHECK (subject_match_count >= 0),

  -- === 身份与快照 ===
  master_waybill_no varchar(128) NOT NULL DEFAULT '',
  parcel_no int NOT NULL DEFAULT 0 CHECK (parcel_no >= 0),
  parcel_ref varchar(64) NOT NULL DEFAULT '',
  carrier_code varchar(64) NOT NULL CHECK (btrim(carrier_code) <> ''),
  channel_id uuid,
  sales_order_no varchar(64) NOT NULL DEFAULT '',
  destination_country varchar(2) NOT NULL DEFAULT '',
  gross_weight_kg numeric(19,6) NOT NULL DEFAULT 0 CHECK (gross_weight_kg >= 0),

  -- === 责任起点与时限快照 ===
  handover_at timestamptz NOT NULL,
  dispatched_at timestamptz,
  sla_rule_id uuid,
  pickup_sla_hours numeric(19,6) CHECK (pickup_sla_hours IS NULL OR pickup_sla_hours > 0),
  reminder_lead_hours numeric(19,6) CHECK (reminder_lead_hours IS NULL OR reminder_lead_hours >= 0),
  pickup_deadline_at timestamptz,
  -- 有时限就必须有 SLA 快照，有 SLA 快照就必须有截止时刻（半截状态会让人以为算过）
  CHECK ((pickup_deadline_at IS NULL) = (pickup_sla_hours IS NULL)),

  -- === 揽收确认 ===
  pickup_state varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (pickup_state IN ('PENDING','CONFIRMED','OVERDUE')),
  pickup_confirmed_at timestamptz,
  pickup_event_id uuid,
  FOREIGN KEY (tenant_id, pickup_event_id)
    REFERENCES logistics_tracking_event(tenant_id, id) ON DELETE RESTRICT,
  pickup_evidence_source varchar(24) NOT NULL DEFAULT ''
    CHECK (pickup_evidence_source IN ('','CARRIER_EVENT','MANUAL_ATTESTATION')),
  pickup_evidence_ref varchar(128) NOT NULL DEFAULT '',

  -- === 签收确认 ===
  delivery_state varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (delivery_state IN ('PENDING','CONFIRMED')),
  delivery_confirmed_at timestamptz,
  delivery_event_id uuid,
  FOREIGN KEY (tenant_id, delivery_event_id)
    REFERENCES logistics_tracking_event(tenant_id, id) ON DELETE RESTRICT,
  delivery_evidence_source varchar(24) NOT NULL DEFAULT ''
    CHECK (delivery_evidence_source IN ('','CARRIER_EVENT','MANUAL_ATTESTATION')),
  delivery_evidence_ref varchar(128) NOT NULL DEFAULT '',

  -- === 异常标记（只标记，处置归 10.3.1.2）===
  exception_flag boolean NOT NULL DEFAULT false,
  exception_code varchar(40) NOT NULL DEFAULT '',
  exception_note varchar(400) NOT NULL DEFAULT '',
  exception_at timestamptz,

  -- === 派生读数 ===
  -- 巡检那一刻的超时小时数（会随巡检继续增长，这是它的用途）
  overdue_hours numeric(19,6) CHECK (overdue_hours IS NULL OR overdue_hours >= 0),

  -- === 确认单状态 ===
  -- PENDING_PICKUP 已交运待揽收 · PICKED_UP 已揽收在途 ·
  -- PICKUP_OVERDUE 超时未揽收 · DELIVERED 已签收 · VOID 批次作废（责任从未转移）
  status varchar(24) NOT NULL DEFAULT 'PENDING_PICKUP' CHECK (status IN
    ('PENDING_PICKUP','PICKED_UP','PICKUP_OVERDUE','DELIVERED','VOID')),
  -- 缺口码：为什么算不出 / 推进不了
  gap_code varchar(32) NOT NULL DEFAULT '' CHECK (gap_code IN
    ('','NO_WAYBILL','TRACKING_SUBJECT_MISSING','SUBJECT_AMBIGUOUS',
     'SLA_UNCONFIGURED','BATCH_VOID')),
  gap_reason varchar(400) NOT NULL DEFAULT '',

  last_reconciled_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, case_no),

  -- 状态与两个 state 必须自洽（DB 层钉死，绕过服务层写 SQL 同样被拒）
  CHECK (pickup_state <> 'CONFIRMED' OR pickup_confirmed_at IS NOT NULL),
  CHECK (pickup_state <> 'OVERDUE' OR pickup_confirmed_at IS NULL),
  CHECK (delivery_state <> 'CONFIRMED' OR delivery_confirmed_at IS NOT NULL),
  CHECK (
    (status = 'VOID')
    OR (status = 'PENDING_PICKUP' AND pickup_state = 'PENDING' AND delivery_state = 'PENDING')
    OR (status = 'PICKED_UP' AND pickup_state = 'CONFIRMED' AND delivery_state = 'PENDING')
    OR (status = 'PICKUP_OVERDUE' AND pickup_state = 'OVERDUE' AND delivery_state = 'PENDING')
    OR (status = 'DELIVERED' AND delivery_state = 'CONFIRMED')
  )
);

-- 列表主筛选：承运商 + 状态
CREATE INDEX logistics_pickup_signoff_case_by_carrier
  ON logistics_pickup_signoff_case(tenant_id, carrier_code);

-- 列表默认排序：最近交运的排前面
CREATE INDEX logistics_pickup_signoff_case_by_handover
  ON logistics_pickup_signoff_case(tenant_id, handover_at DESC);

-- 状态筛选
CREATE INDEX logistics_pickup_signoff_case_by_status
  ON logistics_pickup_signoff_case(tenant_id, status);

-- 超时件筛选（只捞超时的）：超时是少数，用部分索引
CREATE INDEX logistics_pickup_signoff_case_by_overdue
  ON logistics_pickup_signoff_case(tenant_id, pickup_deadline_at)
  WHERE pickup_state = 'OVERDUE';

-- 批次维度（追责时「这一批里哪几件没揽收」）
CREATE INDEX logistics_pickup_signoff_case_by_batch
  ON logistics_pickup_signoff_case(tenant_id, source_batch_id);

-- 运单号检索（客服按单号查揽收状态的主路径）
CREATE INDEX logistics_pickup_signoff_case_by_waybill
  ON logistics_pickup_signoff_case(tenant_id, master_waybill_no);

-- 待巡检队列：还没确认揽收的
CREATE INDEX logistics_pickup_signoff_case_by_pending
  ON logistics_pickup_signoff_case(tenant_id, pickup_deadline_at)
  WHERE pickup_state = 'PENDING';

-- ---------------------------------------------------------------------------
-- 3. logistics_pickup_signoff_event · 确认事件流（append-only）
-- ---------------------------------------------------------------------------
-- 确认单的状态变迁是**举证凭据**：「这一件什么时候被认定超时、什么时候被确认
-- 揽收、是谁人工确认的」都要留痕，且事后不许改写 —— 改了就等于伪造承运商履约史。
CREATE TABLE logistics_pickup_signoff_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, case_id)
    REFERENCES logistics_pickup_signoff_case(tenant_id, id) ON DELETE RESTRICT,

  event_kind varchar(32) NOT NULL CHECK (event_kind IN
    ('CASE_CREATED','SLA_APPLIED','PICKUP_CONFIRMED','PICKUP_DUE_SOON',
     'PICKUP_OVERDUE','DELIVERY_CONFIRMED','EXCEPTION_MARKED','VOIDED','ATTESTED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  detail varchar(600) NOT NULL DEFAULT '',
  -- 承运商事件号（证据来源是承运商推送时）
  carrier_event_id varchar(128) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 时间线主查询：某一单的事件按发生时刻倒序
CREATE INDEX logistics_pickup_signoff_event_by_case
  ON logistics_pickup_signoff_event(tenant_id, case_id, occurred_at DESC);

-- 事件流列表（跨单）
CREATE INDEX logistics_pickup_signoff_event_by_occurred
  ON logistics_pickup_signoff_event(tenant_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 4. logistics_pickup_signoff_reminder · 提醒任务
-- ---------------------------------------------------------------------------
-- 蓝图输出句点名「提醒任务」。三类：
--   PICKUP_DUE_SOON 即将超时（还没到时限，先提醒）
--   PICKUP_OVERDUE  已超时（承运商没揽收，平台会判我们未发货）
--   DELIVERY_CONFIRMED 已签收（该走评价邀请与售后窗口了）
--
-- dedupe_key 保证**同一单 + 同一类 + 同一责任窗口**只提醒一次：
-- 重跑巡检不会把提醒刷成一片，而责任窗口换了（重新交运）才允许再提醒。
CREATE TABLE logistics_pickup_signoff_reminder (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, case_id)
    REFERENCES logistics_pickup_signoff_case(tenant_id, id) ON DELETE RESTRICT,

  reminder_kind varchar(24) NOT NULL CHECK (reminder_kind IN
    ('PICKUP_DUE_SOON','PICKUP_OVERDUE','DELIVERY_CONFIRMED')),
  -- 去重键：case_id + 类型 + 责任窗口标识
  dedupe_key varchar(240) NOT NULL,

  -- PENDING 待处理 · ACKNOWLEDGED 已认领 · RESOLVED 已处置 · CANCELLED 已失效
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN
    ('PENDING','ACKNOWLEDGED','RESOLVED','CANCELLED')),
  title varchar(200) NOT NULL DEFAULT '',
  detail varchar(600) NOT NULL DEFAULT '',
  due_at timestamptz,

  assigned_to_membership_id uuid,
  acknowledged_at timestamptz,
  acknowledged_by_membership_id uuid,
  resolved_at timestamptz,
  resolution_note varchar(400) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴 同一单 + 同一类 + 同一责任窗口只提醒一次（DB 级去重）
  UNIQUE (tenant_id, dedupe_key)
);

-- 待处理提醒列表（默认排序）
CREATE INDEX logistics_pickup_signoff_reminder_by_status
  ON logistics_pickup_signoff_reminder(tenant_id, status, created_at DESC);

-- 按确认单读提醒
CREATE INDEX logistics_pickup_signoff_reminder_by_case
  ON logistics_pickup_signoff_reminder(tenant_id, case_id);

-- ---------------------------------------------------------------------------
-- 5. logistics_pickup_signoff_notification · 下游待通知（回传 / 评价邀请 / 售后窗口）
-- ---------------------------------------------------------------------------
-- 蓝图下游 = 9.4.2.1 回传、15 客服（邀请评价）。本模块**不跨服务调用**它们
-- （9.4.2.1 已在 trade 交付但 channel 未接线；15.2.2.1 索评邀请未开发），
-- 而是把「要通知下游什么、什么时候到时机」落成本服务内的待办记录。
--
-- 三类：
--   PLATFORM_FULFILLMENT_WRITEBACK 揽收确认后回传平台履约指标（9.4.2.1）
--   REVIEW_INVITATION              签收后到时机邀请评价（15.2.2.1）
--   AFTER_SALES_WINDOW_CLOSE       签收后售后窗口到期关闭（15）
--
-- 🔴 downstream_status 显式区分「还没到时机」与「到时机了但下游还没接线」：
--   PENDING  已生成，未到 due_at
--   READY    到时机了，待下游消费（**下游未接线时它就停在这里，不写「已回传」**）
--   CONSUMED 下游已确认消费
--   FAILED   消费失败，带失败原因
--   CANCELLED 已失效
CREATE TABLE logistics_pickup_signoff_notification (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, case_id)
    REFERENCES logistics_pickup_signoff_case(tenant_id, id) ON DELETE RESTRICT,

  notification_kind varchar(32) NOT NULL CHECK (notification_kind IN
    ('PLATFORM_FULFILLMENT_WRITEBACK','REVIEW_INVITATION','AFTER_SALES_WINDOW_CLOSE')),
  -- 缺口归属模块：9.4.2.1 回传（trade，未接线）· 15.2.2.1 索评（未开发）· 15 客服
  downstream_module varchar(16) NOT NULL CHECK (downstream_module IN ('9.4.2.1','15.2.2.1','15')),
  -- 下游待通知状态
  downstream_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (downstream_status IN
    ('PENDING','READY','CONSUMED','FAILED','CANCELLED')),

  -- 到时机时刻：签收时刻 + 规则里的延迟 / 窗口
  due_at timestamptz,
  -- 待通知载荷（下游消费时照此执行；数量用定点数字符串，不退化成浮点）
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,

  attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  last_error varchar(400) NOT NULL DEFAULT '',
  consumed_at timestamptz,
  consumed_by_membership_id uuid,
  -- 去重键：case_id + 类型 + 触发时刻（同一触发点只产生一条待通知）
  dedupe_key varchar(240) NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, dedupe_key)
);

-- 待下游消费的记录（默认排序）
CREATE INDEX logistics_pickup_signoff_notification_by_status
  ON logistics_pickup_signoff_notification(tenant_id, downstream_status, due_at NULLS LAST);

-- 按确认单读待通知
CREATE INDEX logistics_pickup_signoff_notification_by_case
  ON logistics_pickup_signoff_notification(tenant_id, case_id);

-- ---------------------------------------------------------------------------
-- 6. append-only：确认事件流不可改写
-- ---------------------------------------------------------------------------
-- 与 V212 轨迹事件同款：REVOKE UPDATE,DELETE（权限层）+ 触发器（写入层）双保险。
CREATE OR REPLACE FUNCTION reject_logistics_pickup_signoff_event_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION
    'logistics_pickup_signoff_event 是确认变迁的举证凭据（append-only），'
    '要更正就追加一条新的确认事件' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER logistics_pickup_signoff_event_append_only_trg
  BEFORE UPDATE OR DELETE ON logistics_pickup_signoff_event
  FOR EACH ROW EXECUTE FUNCTION reject_logistics_pickup_signoff_event_mutation();

-- ---------------------------------------------------------------------------
-- 7. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V141 / V212 / V214 同一套写法。
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过
--    策略，隔离测试就成了自欺欺人。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_pickup_signoff_sla_rule',
    'logistics_pickup_signoff_case',
    'logistics_pickup_signoff_event',
    'logistics_pickup_signoff_reminder',
    'logistics_pickup_signoff_notification' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 8. 账号与授权（沿用 V1/V93/V129/V134/V136/V141/V212/V214 的条件建角色）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  logistics_pickup_signoff_sla_rule, logistics_pickup_signoff_case,
  logistics_pickup_signoff_event, logistics_pickup_signoff_reminder,
  logistics_pickup_signoff_notification TO channel_app;

-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE logistics_pickup_signoff_case_no_seq TO channel_app;

-- 确认事件流只许增，不许改 / 删（权限层，与触发器双保险）。
REVOKE UPDATE,DELETE ON logistics_pickup_signoff_event FROM channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V136 同口径）。
REVOKE ALL ON
  logistics_pickup_signoff_sla_rule, logistics_pickup_signoff_case,
  logistics_pickup_signoff_event, logistics_pickup_signoff_reminder,
  logistics_pickup_signoff_notification FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 9. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：揽收与签收确认属于「平台/承运商操作」，与 10.1.1.1 承运商、
-- 10.1.1.2 渠道配置、10.1.1.3 时效与限制、10.2.2.2 面单模板、10.3.1.1 轨迹查询、
-- 10.3.2.1 交运批次同族，因此 identity 服务零改动，跨车道回归面 = 0。
