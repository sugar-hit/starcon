-- ===========================================================================
-- 9.4.2.1 平台回传
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md）：
--   职责：把发货信息（运单号、承运商、发货时间）回传平台并跟踪结果：
--         成功/失败/结果未知；支持批量重试与人工修正。
--   输入：发货事件；`idempotency_key`；平台回传接口。
--   输出：回传任务与状态（经 2 队列）；平台侧确认状态。
--   关系：上游 = 8.3.4.1、9.3.1.2；下游 = 2.5.1.1。**回传失败会导致平台
--         显示未发货并可能判定迟发**，因此回传状态必须可监控可补偿；
--         结果未知要先查证再重发。
--
-- ---------------------------------------------------------------------------
-- 一、命名：为什么是 shipment_writeback_* 而不是 platform_writeback_*
-- ---------------------------------------------------------------------------
-- 本服务里已经存在两张名字很近的表，**都不是本模块**：
--   V119 order_platform_writeback_task   改单 / 取消的平台回写（owner_module='2.5.1.1'）
--   V144 order_consolidation_writeback_task 合单的物流号回写
-- 两者都**只在 trade 本地登记**、从不真正调平台（它们是 2.5.1.1 的登记表，
-- 不是队列生产者）。本模块不一样：蓝图「输出」明写「回传任务与状态（**经 2 队列**），
-- 下游 = 2.5.1.1」，也就是本模块**真的把动作投递到 channel 的 platform_operation**。
--
-- 所以三张表刻意用三个不同前缀，界面上、SQL 里、事后追责时不会指错：
--   shipment_writeback_*        本模块（9.4.2.1，发货信息回传，唯一真的投递方）
--   order_platform_writeback_*  9.4.2.x 改单/取消（V119，只登记）
--   order_consolidation_writeback_* 9.3.1.2 合单物流号（V144，只登记）
--
-- ---------------------------------------------------------------------------
-- 二、三态必须真的三态：「结果未知」不是失败的同义词
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「**结果未知要先查证再重发**」。这句话否掉两种偷懒实现：
--   (a) 把 UNKNOWN 塞进 REJECTED  → 平台其实**已经**收到货，重发 = 平台侧重复发货；
--   (b) 把 UNKNOWN 当成成功       → 平台压根没收到，迟发判定成立且无人知道。
-- ⇒ PENDING_VERIFICATION 是**独立状态**，且带独立的 verification 结论三选一
--   （NOT_REQUIRED / CONFIRMED_APPLIED / CONFIRMED_NOT_APPLIED / STILL_UNKNOWN，
--   取值域与 channel V2 的 platform_operation.verification **逐字对齐**，
--   避免同一个词在两个服务里两套值）。
--
-- 状态取值域也**逐字沿用同服务两个兄弟表的既有词汇**（V119 / V144 都用
-- PENDING/SUBMITTED/CONFIRMED/REJECTED/PENDING_VERIFICATION）——
-- ShipmentRecordService 纪律里那句「同一个词两处定义，事后没人说得清哪个是真的」
-- 在这里同样适用，所以本表**不另起一套 SUCCEEDED/FAILED**。
--
-- ---------------------------------------------------------------------------
-- 三、幂等键：一个稳定的命名空间 + 修正时**必须换键**
-- ---------------------------------------------------------------------------
-- 蓝图把 `idempotency_key` 列为输入，本表 UNIQUE(tenant_id, idempotency_key)。
-- 键的构造：``ship-update:<record_id>:r<revision>``，由服务端生成，不接受调用方
-- 自拟 —— 调用方自拟就能两次「不同键、同一次发货」，恰好绕开幂等。
--
-- **人工修正必须换键，这是本模块最容易做错的一处**：channel 的 enqueue 是
-- 「键命中就复用既有任务、不产生第二次平台动作」。若修正后沿用旧键，平台侧会
-- 静默丢弃这次修正（当成重复），现场看到的是「我改了内容，平台还是老的」。
-- 所以 revision 一涨，键跟着涨，两件事在服务层同一个事务里一起做。
--
-- 与兄弟表的键**不冲突**：V119 / V144 根本不存幂等键（本地登记表，无跨服务投递），
-- 命名空间无交集。
--
-- ---------------------------------------------------------------------------
-- 四、ship_id 这个 UUID 是本模块独有的新约束（一个必须说清的发现）
-- ---------------------------------------------------------------------------
-- channel 的 platform_operation 有 `shop_id uuid NOT NULL REFERENCES shop(tenant_id,id)`，
-- 而 trade 侧只有 **shop_code 字符串**（同服务 9.4.1.2 的 shipment_record.shop_ref）。
-- 2.3 店铺目录在 channel 的库里，而 AGENTS.md 禁止跨服务 SQL / 跨库视图 / 跨库 FK，
-- 所以本服务**查不到** shop_code → shop_id 的映射。
-- ⇒ shop_id 由**调用方指定**（UI 从店铺选择器带入），本模块不校验其存在性：
--   传错了由 channel 侧的 FK 顶回来，本模块把那个真实错误落成 REJECTED + last_error，
--   **不静默、不编造一个店铺**。这一点在 capabilities 里显式声明。
--   两个兄弟表不需要 shop_id，正因为它们从不真的投递到 channel。
--
-- ---------------------------------------------------------------------------
-- 五、跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器，零 ALTER 任何既有表**：
--   * 不碰 V99 / V119 / V122 / V124 / V126 / V127 / V144 的任何一张表；
--   * 不碰 warehouse / channel 任何一张表（连 ALTER 都没有，更没有跨库 FK）；
--   * 唯一的同服务引用 shipment_record 是**同库读 + 同库 FK**，不是跨服务；
--   * 所有 FK 都指向本服务自己新建的表或既有的 shipment_record。
-- 四张表落在 shipment_writeback_* 命名空间，与既有 fulfillment_* /
-- shipment_record_* / order_* 写回族并列而不混用。跨车道 DDL 回归面 = 0。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：PB-yyyyMMdd-000001（回传任务号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE shipment_writeback_task_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. shipment_writeback_task · 回传任务头
-- ---------------------------------------------------------------------------
-- 一行 = 一次「把某张发货记录的发货信息回传给某个平台」的动作。
-- 事实源 = 同服务 9.4.1.2 的 shipment_record（它自己又只来自 8.3.4.1 已确认交接），
-- 本表**不重新登记运单号 / 承运商 / 发货时间**，只登记「这份信息回传得怎么样」。
CREATE TABLE shipment_writeback_task (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- PB-yyyyMMdd-000001，由 DB 发（并发建任务不撞号）
  task_no varchar(32) NOT NULL,

  -- === 上游：同服务 9.4.1.2 发货记录（V127，同库 FK）===
  -- UNIQUE(source_record_id, revision) 保证同一版只发一次；
  -- UNIQUE(tenant_id,source_record_id) 只在下面按 partial index 表达「当前有效版本」。
  source_record_id uuid NOT NULL,
  -- 快照：回传时点上「报的是哪一票货」。变了要从明细里能看出来，而不是回头去 join。
  source_record_no varchar(32) NOT NULL DEFAULT '',
  sales_order_no varchar(64) NOT NULL DEFAULT '',
  carrier_code varchar(64) NOT NULL DEFAULT '',
  master_waybill_no varchar(64) NOT NULL DEFAULT '',
  shipped_at timestamptz,

  -- === 平台与店铺 ===
  platform_code varchar(32) NOT NULL CHECK(btrim(platform_code)<>''),
  -- 业务键：可从 9.4.1.2 的 shop_ref 读到，只作展示与排查用（见文件头第四节）。
  shop_code varchar(64) NOT NULL DEFAULT '',
  -- channel platform_operation.shop_id 要求的 **uuid**。跨服务读 2.3 店铺目录被禁，
  -- 故由调用方指定；传错由 channel 侧 FK 顶回来，本模块落成 REJECTED + 真实错误。
  shop_id uuid NOT NULL,
  -- 平台侧订单号（external order no）：平台按它定位。跨服务只存业务键不猜 uuid。
  platform_order_ref varchar(128) NOT NULL DEFAULT '',

  -- === 幂等 ===
  -- 构造见文件头第三节：ship-update:<record_id>:r<revision>，服务端生成。
  -- 人工修正 revision 涨 → 键跟着涨（沿用旧键会被 channel 当重复静默丢弃）。
  idempotency_key varchar(160) NOT NULL CHECK(btrim(idempotency_key)<>''),
  -- payload 是**冻结的那一次要报的内容**（运单号 / 承运商 / 发货时间 / 逐箱运单号）。
  -- 冻结的理由：平台侧收到的是发出那一刻的内容；事后改 shipment_record 不能让
  -- 「当时到底报了什么」变得无法复盘 —— 那正是平台申诉要的证据。
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  payload_checksum varchar(64) NOT NULL DEFAULT '',

  -- channel 侧 platform_operation.id：投递后拿到，用于查证与对账。
  channel_operation_id uuid,
  platform_request_id varchar(128) NOT NULL DEFAULT '',

  -- === 三态状态机（取值域逐字沿用 V119 / V144，见文件头第二节）===
  --   PENDING             待回传（已入待办、还没发出去）
  --   SUBMITTED           已提交到 2 队列，等平台回执
  --   CONFIRMED           平台已受理（= 成功，终态）
  --   REJECTED            平台明确拒绝（= 失败，可补偿重试）
  --   PENDING_VERIFICATION 结果未知，**必须先查证再决定重发**（不是失败！）
  --   SKIPPED             决定不回传（必须写明原因，与「压根没建任务」分开）
  --   SUPERSEDED          已被人工修正版本取代（原任务保留作证据）
  status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(status IN (
    'PENDING','SUBMITTED','CONFIRMED','REJECTED','PENDING_VERIFICATION',
    'SKIPPED','SUPERSEDED')),

  -- === 查证结论（与 channel V2 verification 逐字同域）===
  --   NOT_REQUIRED          当前状态不需要查证（还没到未知，或已定论）
  --   CONFIRMED_APPLIED     查证结论：平台**确实已经**应用 ⇒ 不得重发
  --   CONFIRMED_NOT_APPLIED 查证结论：平台**确实没有**应用 ⇒ 可以重发
  --   STILL_UNKNOWN         查证后仍不确定 ⇒ 继续禁止重发，等下一次查证
  verification varchar(24) NOT NULL DEFAULT 'NOT_REQUIRED' CHECK(verification IN
    ('NOT_REQUIRED','CONFIRMED_APPLIED','CONFIRMED_NOT_APPLIED','STILL_UNKNOWN')),
  verified_at timestamptz,

  -- === 重试与退避（蓝图：「支持批量重试」，要有次数与退避）===
  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  max_attempts int NOT NULL DEFAULT 5 CHECK(max_attempts >= 1),
  -- next_attempt_at 是退避的下一次可发时刻（指数退避由服务层算，落库成可查询列）。
  -- 只有「还可能重发」的态才要求它非空，见下方两条 CHECK。
  next_attempt_at timestamptz,

  last_error varchar(800) NOT NULL DEFAULT '',

  -- === 时刻 ===
  submitted_at timestamptz,
  settled_at timestamptz,
  skipped_at timestamptz,

  -- === 人工修正链 ===
  revision int NOT NULL DEFAULT 1 CHECK(revision >= 1),
  -- 被本任务取代的那一版（第一版为 NULL）。
  correction_of uuid,
  -- 修正必须写明原因：改了什么、为什么改，是平台申诉时要交代的东西。
  correction_reason varchar(400) NOT NULL DEFAULT '',
  corrected_by_membership_id uuid,

  created_by_membership_id uuid,
  version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_no),
  -- 幂等锚点：同一键撞 UNIQUE 而不是产生第二次平台动作。
  UNIQUE(tenant_id,idempotency_key),
  -- 同一张发货记录的同一版只建一次任务；建两次 = 回传两次 = 平台侧重复发货。
  UNIQUE(tenant_id,source_record_id,revision),

  FOREIGN KEY(tenant_id,source_record_id) REFERENCES shipment_record(tenant_id,id),
  FOREIGN KEY(tenant_id,correction_of) REFERENCES shipment_writeback_task(tenant_id,id),

  -- 「成功」必须有平台侧凭据，否则「已回传」是无内容的一句谎（与 V144 同纪律）。
  CONSTRAINT shipment_writeback_confirmed_needs_evidence
    CHECK(status <> 'CONFIRMED' OR btrim(platform_request_id) <> ''
                   OR channel_operation_id IS NOT NULL),
  -- 「已提交」必须落在时刻上，且必须有队列凭据（否则查证时无从定位那条动作）。
  CONSTRAINT shipment_writeback_submitted_needs_queue_ref
    CHECK(status <> 'SUBMITTED' OR (submitted_at IS NOT NULL AND channel_operation_id IS NOT NULL)),
  CONSTRAINT shipment_writeback_settled_status_only
    CHECK((settled_at IS NULL)
          OR status IN ('CONFIRMED','REJECTED','PENDING_VERIFICATION','SKIPPED','SUPERSEDED')),
  -- 「决定不回传」必须写明原因 —— 这是「跳过」与「漏了」的唯一分界。
  CONSTRAINT shipment_writeback_skipped_needs_reason
    CHECK(status <> 'SKIPPED' OR (btrim(correction_reason) <> '' OR btrim(last_error) <> '')),
  -- 结果未知必须带错误说明：不知道是没发出去还是发出去了 —— 两者的处置完全相反。
  CONSTRAINT shipment_writeback_unknown_needs_error
    CHECK(status <> 'PENDING_VERIFICATION' OR btrim(last_error) <> ''),
  -- 未知态**不许**同时声称已确认应用 / 未应用：那是自相矛盾的举证。
  CONSTRAINT shipment_writeback_unknown_not_yet_verified
    CHECK(status <> 'PENDING_VERIFICATION'
          OR verification IN ('NOT_REQUIRED','STILL_UNKNOWN')),
  -- 查证结论落定必带查证时刻。
  CONSTRAINT shipment_writeback_verification_needs_time
    CHECK(verification = 'NOT_REQUIRED' OR verified_at IS NOT NULL),
  -- 可重试的两态必须排下一次尝试时刻（退避），否则「批量重试」无节奏可言。
  CONSTRAINT shipment_writeback_retryable_needs_schedule
    CHECK(status NOT IN ('PENDING','REJECTED') OR next_attempt_at IS NOT NULL),
  -- 进行中与终态不需要排下一次尝试。
  CONSTRAINT shipment_writeback_terminal_has_no_schedule
    CHECK(status NOT IN ('SUBMITTED','CONFIRMED','PENDING_VERIFICATION','SKIPPED','SUPERSEDED')
          OR next_attempt_at IS NULL),
  -- 次数上限由 DB 兜一层：应用层算错时也不至于无限重发。
  CONSTRAINT shipment_writeback_attempts_within_cap
    CHECK(attempt_count <= max_attempts),
  -- 第一版没有上一版；修正版必须有上一版且 revision 更大，否则链条会指错。
  CONSTRAINT shipment_writeback_revision_chain
    CHECK((revision = 1) = (correction_of IS NULL)),
  CONSTRAINT shipment_writeback_correction_needs_reason
    CHECK(correction_of IS NULL OR (btrim(correction_reason) <> ''
                                    AND corrected_by_membership_id IS NOT NULL)),
  CONSTRAINT shipment_writeback_timestamps_ordered
    CHECK(updated_at >= created_at)
);

-- 当前有效版本：同一张发货记录最多一版未结清（非 SKIPPED / SUPERSEDED / CONFIRMED）。
-- 用 partial unique index 而不是额外一列：多一列就多一个能写歪的地方。
CREATE UNIQUE INDEX shipment_writeback_task_open_per_record
  ON shipment_writeback_task(tenant_id,source_record_id)
  WHERE status NOT IN ('CONFIRMED','SKIPPED','SUPERSEDED');
-- 清单默认按创建时刻倒序；三态分拣与退避排程各有索引。
CREATE INDEX shipment_writeback_task_status
  ON shipment_writeback_task(tenant_id,status,next_attempt_at);
CREATE INDEX shipment_writeback_task_platform
  ON shipment_writeback_task(tenant_id,platform_code,status);
CREATE INDEX shipment_writeback_task_carrier
  ON shipment_writeback_task(tenant_id,carrier_code,status);
CREATE INDEX shipment_writeback_task_queue
  ON shipment_writeback_task(tenant_id,channel_operation_id);
-- 批量重试的候选集：WHERE 里就是这条索引的谓词形状。
CREATE INDEX shipment_writeback_task_retryable
  ON shipment_writeback_task(tenant_id,next_attempt_at)
  WHERE status IN ('PENDING','REJECTED');
CREATE INDEX shipment_writeback_task_created
  ON shipment_writeback_task(tenant_id,created_at desc);
CREATE INDEX shipment_writeback_task_correction
  ON shipment_writeback_task(tenant_id,correction_of);

-- ---------------------------------------------------------------------------
-- 2. shipment_writeback_attempt · 每次尝试凭据（append-only）
-- ---------------------------------------------------------------------------
-- 「重试要有次数/退避」要有凭据：光有 attempt_count 一个数字，事后无法回答
-- 「第三次那笔到底报的是什么、平台回了什么」。
--
-- ⚠️ 一行 = **一次尝试的最终结果**，不是「一次尝试的生命周期」。
-- 刻意在**结果定局时**才落行，而不是投递时就落一行、事后再改它：
--   * 凭据表是 append-only 的（下面有 REVOKE + 触发器两层），投递后改结果
--     本来就写不进去；
--   * PENDING_VERIFICATION（结果未知）**不是**一次尝试的结局 —— 它要等查证
--     定论。所以未知期间本表没有行；未知这个事实由 task.status 与
--     shipment_writeback_verification 承担。
-- 投递动作本身不是没有凭据：task 的 channel_operation_id + submitted_at
-- + attempt_count 就是那一份（见任务表）。
CREATE TABLE shipment_writeback_attempt (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- 从 1 递增；UNIQUE 让「同一次尝试落两行结果」当场撞库，而不是悄悄覆盖。
  attempt_no int NOT NULL CHECK(attempt_no >= 1),

  -- 本次用的幂等键快照 + 队列凭据：重试复用同一键（这才是幂等的含义），
  -- 人工修正则换键（见文件头第三节）。两件事都能从这一行上复盘。
  idempotency_key varchar(160) NOT NULL,
  channel_operation_id uuid,
  platform_request_id varchar(128) NOT NULL DEFAULT '',

  -- 尝试的最终结果只有两种：平台已应用，或确认没应用。
  -- 「结果未知」不是结果 —— 它是等查证的中间态，落在 task.status 上。
  outcome varchar(24) NOT NULL CHECK(outcome IN ('CONFIRMED','REJECTED')),

  request_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  response_ref varchar(128) NOT NULL DEFAULT '',
  error_message varchar(800) NOT NULL DEFAULT '',
  -- 退避：本次失败/未知后算出的下一次可发时刻，写进凭据行便于复盘节奏。
  next_attempt_at timestamptz,

  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL,
  actor_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_id,attempt_no),
  FOREIGN KEY(tenant_id,task_id) REFERENCES shipment_writeback_task(tenant_id,id)
    ON DELETE CASCADE,
  -- 拒绝必须写明原因：「平台说不行」而不带原因，客服与开发都无从下手。
  CONSTRAINT shipment_writeback_attempt_rejected_needs_error
    CHECK(outcome <> 'REJECTED' OR btrim(error_message) <> ''),
  CONSTRAINT shipment_writeback_attempt_times_ordered
    CHECK(finished_at >= started_at)
);

CREATE INDEX shipment_writeback_attempt_task
  ON shipment_writeback_attempt(tenant_id,task_id,attempt_no desc);

-- ---------------------------------------------------------------------------
-- 3. shipment_writeback_verification · 查证记录（append-only）
-- ---------------------------------------------------------------------------
-- 蓝图加粗句的落地：UNKNOWN 不许直接重发，必须先走这里。
-- 一次查证一行，结论三选一（与 channel 2.5.1.2 的 verify 端点逐字对齐）。
CREATE TABLE shipment_writeback_verification (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  -- 查的是哪一次尝试的结果（UNKNOWN 是某一次投递造成的）。
  attempt_no int NOT NULL CHECK(attempt_no >= 1),

  -- CONFIRMED_APPLIED / CONFIRMED_NOT_APPLIED / STILL_UNKNOWN
  conclusion varchar(24) NOT NULL CHECK(conclusion IN
    ('CONFIRMED_APPLIED','CONFIRMED_NOT_APPLIED','STILL_UNKNOWN')),

  -- 查证走哪条路：CHANNEL_VERIFY = 经 2.5.1.2 的 POST /platform-operations/{id}/verify；
  -- MANUAL_ATTESTATION = 人工在平台上查过后登记（必须有主体与依据）。
  source varchar(24) NOT NULL CHECK(source IN ('CHANNEL_VERIFY','MANUAL_ATTESTATION')),
  -- 查证依据：平台回查到的请求号 / 截图号 / 平台侧订单当前发货状态。
  evidence varchar(800) NOT NULL DEFAULT '',
  queried_at timestamptz NOT NULL,
  queried_by_membership_id uuid,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_id,attempt_no),
  FOREIGN KEY(tenant_id,task_id) REFERENCES shipment_writeback_task(tenant_id,id)
    ON DELETE CASCADE,
  -- 人工查证必须留主体与依据，否则「人工确认平台已收到」等于没人说的话。
  CONSTRAINT shipment_writeback_verification_manual_needs_actor
    CHECK(source <> 'MANUAL_ATTESTATION'
          OR (queried_by_membership_id IS NOT NULL AND btrim(evidence) <> '')),
  -- 经 channel 查证时，那条队列动作必须已经存在（没有动作可查）。
  CONSTRAINT shipment_writeback_verification_channel_needs_operation
    CHECK(source <> 'CHANNEL_VERIFY' OR btrim(evidence) <> '')
);

CREATE INDEX shipment_writeback_verification_task
  ON shipment_writeback_verification(tenant_id,task_id,queried_at desc);

-- ---------------------------------------------------------------------------
-- 4. shipment_writeback_event · 事件流（append-only 时间线）
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_writeback_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  event_kind varchar(40) NOT NULL CHECK(btrim(event_kind)<>''),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  detail varchar(800) NOT NULL DEFAULT '',
  attempt_no int,
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES shipment_writeback_task(tenant_id,id)
    ON DELETE CASCADE
);

CREATE INDEX shipment_writeback_event_task
  ON shipment_writeback_event(tenant_id,task_id,occurred_at desc,created_at desc);
CREATE INDEX shipment_writeback_event_kind
  ON shipment_writeback_event(tenant_id,event_kind,occurred_at desc);

-- ---------------------------------------------------------------------------
-- 5. 触发器：把不变量钉在写入路径上（绕过服务层写 SQL 同样被拒）
-- ---------------------------------------------------------------------------

-- (a) 任务状态机按白名单逐对校验。
--     刻意**不**用「NEW.status 与 OLD.status 不同就放行」—— 那会把「只改 last_error」
--     一起放行，退化成没有闸门。
--     两条最容易做错的边在这里被显式写死：
--       * PENDING_VERIFICATION → SUBMITTED 只在 CONFIRMED_NOT_APPLIED 之后允许
--         （未知没查证就重发 = 平台侧重复发货，正是蓝图要否掉的那种实现）；
--       * CONFIRMED / SKIPPED / SUPERSEDED 是终态，不可回退。
CREATE OR REPLACE FUNCTION guard_shipment_writeback_state() RETURNS trigger AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  IF OLD.status = 'PENDING' AND NEW.status IN ('SUBMITTED','SKIPPED','SUPERSEDED') THEN
    allowed := true;
  END IF;
  IF OLD.status = 'SUBMITTED'
     AND NEW.status IN ('CONFIRMED','REJECTED','PENDING_VERIFICATION') THEN
    allowed := true;
  END IF;
  -- 失败可补偿重试，或被决定跳过 / 被修正版取代。
  IF OLD.status = 'REJECTED'
     AND NEW.status IN ('SUBMITTED','SKIPPED','SUPERSEDED') THEN
    allowed := true;
  END IF;
  -- 未知态：查证定论后才动，且**只有 CONFIRMED_NOT_APPLIED 才允许重新提交**。
  IF OLD.status = 'PENDING_VERIFICATION' THEN
    IF NEW.status IN ('CONFIRMED','REJECTED','SKIPPED','SUPERSEDED') THEN
      allowed := true;
    END IF;
    IF NEW.status = 'SUBMITTED' AND NEW.verification = 'CONFIRMED_NOT_APPLIED' THEN
      allowed := true;
    END IF;
  END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION
      'shipment_writeback_task 状态不可从 % 推进到 %（CONFIRMED/SKIPPED/SUPERSEDED 是终态；'
      'PENDING_VERIFICATION 必须先查证，且只有 CONFIRMED_NOT_APPLIED 才允许重新提交 —— '
      '未知直接重发会导致平台侧重复发货）',
      OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_shipment_writeback_state_trg
  BEFORE UPDATE ON shipment_writeback_task
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_writeback_state();

-- (b) 幂等键不可就地改写。
--     键是 channel 侧去重的锚点：就地改键等于让「这一版发过几次」变得无法复盘，
--     而修正版的正确做法是**新建一行**（revision 涨、键跟着涨），不是改这一行。
CREATE OR REPLACE FUNCTION guard_shipment_writeback_idempotency_key() RETURNS trigger AS $$
BEGIN
  IF NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key THEN
    RAISE EXCEPTION
      'shipment_writeback_task 的 idempotency_key 不可就地改写（它是平台侧去重的锚点）；'
      '人工修正请新建一版任务（revision + 1，键随之变化），原任务保留作证据'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_shipment_writeback_idempotency_key_trg
  BEFORE UPDATE ON shipment_writeback_task
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_writeback_idempotency_key();

-- (c) 三张凭据表 append-only：连运行角色都不给改删
--     （下面还有一道 REVOKE，这里是第二道；两层都在才不算死代码）。
CREATE OR REPLACE FUNCTION reject_shipment_writeback_credential_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '平台回传的凭据行是 append-only 的举证凭据，不允许修改或删除'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER shipment_writeback_attempt_append_only_trg
  BEFORE UPDATE OR DELETE ON shipment_writeback_attempt
  FOR EACH ROW EXECUTE FUNCTION reject_shipment_writeback_credential_mutation();
CREATE TRIGGER shipment_writeback_verification_append_only_trg
  BEFORE UPDATE OR DELETE ON shipment_writeback_verification
  FOR EACH ROW EXECUTE FUNCTION reject_shipment_writeback_credential_mutation();
CREATE TRIGGER shipment_writeback_event_append_only_trg
  BEFORE UPDATE OR DELETE ON shipment_writeback_event
  FOR EACH ROW EXECUTE FUNCTION reject_shipment_writeback_credential_mutation();

-- ---------------------------------------------------------------------------
-- 6. 多租户与授权（与 trade V1 第 2 节、V127 第 8 节同一套写法）
-- ---------------------------------------------------------------------------
-- 四张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'shipment_writeback_task',
    'shipment_writeback_attempt',
    'shipment_writeback_verification',
    'shipment_writeback_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  shipment_writeback_task,
  shipment_writeback_attempt,
  shipment_writeback_verification,
  shipment_writeback_event TO trade_app;
-- 三张凭据表是举证凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON
  shipment_writeback_attempt,
  shipment_writeback_verification,
  shipment_writeback_event FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V127 同口径）。
REVOKE ALL ON
  shipment_writeback_task,
  shipment_writeback_attempt,
  shipment_writeback_verification,
  shipment_writeback_event FROM starcon_app;
-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE shipment_writeback_task_no_seq TO trade_app;
