-- 9.3.3.1 履约请求。
--
-- 蓝图 09-order-and-fulfillment.md 9.3.3.1 原文：
--   职责：编排一次履约：锁定预占 → 生成出库单 → 编排作业 → 跟踪完成；持久化流程状态，
--         支持断点恢复与超时重试。
--   输入：分仓与拆合单结果；库存预占指令；`expected_version`。
--   输出：履约请求 + 出库单 + 流程状态记录 + 事件（下游消费）。
--   关系：上游 = 9.3.1/9.3.2；下游 = 8.3.1 出库单、7.2 预占、10 物流下单。
--         **流程状态必须持久化**，不能只放内存——断电重启后要能继续（实体目录 trade 段）。
--
-- 归属：**trade 服务**（蓝图 09 卷「后端归属：trade 服务拥有订单与售后单据」，
-- L2 表「9.3 履约编排 → 主责服务 trade」）。它是这条履约链的**编排层**：9.3.1 拆合单
-- 决定「一单切成几个履约单元」，9.3.2 分仓 / FBA 选择决定「从哪个仓、走哪条路径」，
-- 本模块负责把两者拼成**一次可推进、可恢复、可重试的流程**。
--
-- ===========================================================================
-- 零、与同批上游的边界：能读的两条，不能读的一条
-- ===========================================================================
-- 本模块在集成头 20ccd017 上实查（不是照抄任务书的结论）：
--   * 9.3.2.1 分仓规则    → V116__trade_warehouse_allocation.sql（fulfillment_warehouse_allocation_*）已交付
--   * 9.3.2.2 FBA 选择    → V92__trade_fulfillment_routing.sql（fulfillment_routing_*）已交付
--   * 9.3.1.2 合单        → V144__trade_order_consolidation.sql（order_consolidation_*）已交付
--   * 9.3.1.1 拆单        → **未交付**：集成头里没有任何 split 相关建表语句
--
-- 所以「输入：分仓与拆合单结果」的处理方式分两种：
--   (1) 分仓（V116）与合单（V144）**同服务、已并入**，本模块**只存逻辑引用**
--       （allocation_decision_ref / consolidation_ref）+ 决策时刻的**快照列**，
--       仍然**不 JOIN 它们的表**。理由与 V116 不读 V92 同源：同服务内读别人的事实表
--       看似便宜，但那条车道改一列就静默改掉本模块的判定；而且一单多仓多批次场景下，
--       本模块要为**每个履约单元**留一份「当时按哪个仓判的」凭据，JOIN 出来的是
--       **当前**状态而不是决策时状态 —— 快照才是举证凭据。
--   (2) 拆单（V151，另一条并行车道）**此刻不存在**：split_unit_ref 只是逻辑引用列，
--       **不建表、不读表、不校验存在性**，并在 capabilities 里
--       crossServiceReadAvailable=false / available=false 如实报出「拆单未接通」。
--       等那条车道落地后由它往本表补引用即可，本模块结构不需要改。
--
-- ===========================================================================
-- 一、下游三个：全部跨库不可读，全部表达为本服务内的「下游指令记录」
-- ===========================================================================
-- 8.3.1 出库单、7.2 预占在 **warehouse 服务**，10 物流在别的服务，各有独立数据库。
-- AGENTS.md「模块边界纪律」禁止跨服务 SQL / FK / 视图。所以本模块**不假装**已经
-- 调度了下游，而是把每一次「锁定预占」「生成出库单」「编排作业」表达为
-- **本服务内登记的下游指令行**：带状态、来源、幂等键，等下游接上后按同一形状回填。
--
-- ⚠ 这里直接踩在 9.3.2.1（V116）踩过的坑上，必须把话说死：
--   V116 的证据写着「本模块不预占 warehouse 库存，capabilities 显式
--   reservesUpstreamStock=false，否则下游落地时两边都以为占了同一批货」。
--   本模块**就是那个下游**。所以：
--     * 本模块**不写 warehouse 的库存**，也不声称「货已经锁住」——
--       reservation 行的缺省状态是 REQUESTED（指令已登记，待下游回执），
--       只有收到回执才变 LOCKED；
--     * capabilities 里 reservesUpstreamStock=false、
--       outboundOrderDispatched=false、logisticsOrderPlaced=false 全部显式报出，
--       **不让调用方从 404 或空数组里猜**；
--     * 幂等键（idempotency_key）逐条落在下游指令行上：将来真接上 warehouse 时，
--       同一个键重放不会让两边各锁一批货。
--
-- 10 物流下单同理：蓝图把它列进下游，但本模块的四步（锁定预占 → 生成出库单 →
-- 编排作业 → 跟踪完成）在出库单之后才轮到运单，运单主权在 9.4.1.1 / 8.3.4.2。
-- 本模块在 capabilities 里报 logisticsOrderPlaced=false，不自建运单表。
--
-- ===========================================================================
-- 二、流程状态必须持久化：这是本模块的加粗句，也是全部设计的出发点
-- ===========================================================================
-- 蓝图：「**流程状态必须持久化**，不能只放内存——断电重启后要能继续」
--   （实体目录 trade 段）。
--
-- 落法不是「把状态塞进一个 JSON 列」（那样查不了、也证明不了）：
--   * fulfillment_request 是**可推进的头行**：current_step + status + process_version
--     三件套就是持久化的流程状态本身；
--   * fulfillment_request_step 是**流程状态记录**（蓝图输出第二项），
--     「每步每次尝试一行」的 append-only 表 —— 它是断点恢复的**依据**：
--     要知道现在该从哪继续，只需要数清楚哪些步已经完成；
--   * fulfillment_request_event 是**事件**（蓝图输出第四项），下游消费用，
--     全程 append-only。
--
-- 「断点恢复」在 DB 上是可验证的，不是口号：current_step 有一个**阶梯**，
--   RESERVE → OUTBOUND → WORK → TRACK，
-- 延迟约束触发器（回表读当前行）强制「current_step 之前的每一步都必须有一条
-- status=COMPLETED 的 step 行」。于是「从 OUTBOUND 恢复」这句话在数据库层面
-- 意味着「RESERVE 步真的完成过」，做不到的话提交不了；
-- 「先跳到 TRACK 再补前面的步」这种作弊被同一个触发器挡掉。
--
-- ===========================================================================
-- 三、超时重试：阈值是租户口径，超时是事实，重试是决定
-- ===========================================================================
-- 蓝图只点名「支持断点恢复与超时重试」，并把**异常清单与处置操作**判给 9.3.3.2
-- （「超时不是失败……先查证再决定重试或释放预占」）。所以本模块的边界是：
--   * **做**：按租户阈值检出本流程自己卡住的步骤，落一条 timeout 记录，
--     允许从 current_step **原地重试**（attempt+1，deadline 顺延）；
--   * **不做**：跨请求的异常监控看板、人工处置入口、释放预占 —— 那是 9.3.3.2 的主权。
--
-- timeout 表的 detection_kind 允许 TIMEOUT / STEP_FAILED / STALE_ACK 三种：
-- 都是**本模块在自己的编排过程中能自己检出**的事实（步骤报失败、回执迟迟不来），
-- 不是「扫一遍全租户找异常」—— 那正是 9.3.3.2 要做的事。
--
-- ⚠ 超时判定完全依赖时间正确性，所以：所有时间列 timestamptz，服务层用
-- OffsetDateTime 绑定并在 values 位置写 ?::timestamptz（用 Timestamp.valueOf
-- (LocalDateTime) 会静默偏 -28800s，把 deadline 提前 8 小时，所有请求刚建就超时）。
--
-- ⚠ 同理，三个推进函数的 deadline 用 **clock_timestamp()** 而不是 now()：now() 在
-- 一个事务里是**冻结**的，同一事务里连推两步且两步阈值相同时 deadline 会完全相等，
-- 撞上第 8.1 节的「deadline 必须顺延」不变式自己把自己拒掉。所以 deadline 取
-- greatest(clock_timestamp()+阈值, 原 deadline+1 微秒)：既保证严格递增，
-- 又保证「至少是新的阈值之后」，不会因为顺延把真实超时点说晚。
--
-- ===========================================================================
-- 四、乐观并发：`expected_version` 是蓝图输入里点名的那个字段
-- ===========================================================================
-- process_version 逐次 +1，四条窄函数（advance / block / retry / cancel）**全部**要求
-- p_expected_version 与当前值一致，不一致报 23514 / HTTP 409。
-- 刻意做在 **DB 层**而不是只在 Java 里 if 一下：并发下两个操作都读到 version=7，
-- 只在 Java 里比的话谁都觉得自己是第一个写。
--
-- ===========================================================================
-- 五、金额与数量
-- ===========================================================================
-- 数量 numeric(19,6)、金额 numeric(19,4)、耗时秒 numeric(12,3)，全程 BigDecimal 禁浮点。
-- 预占 / 出库 / 作业三处的数量都必须等于 requested_qty —— 一次履约请求对应**一个**
-- 履约单元（拆单已在上游把它切成多个了，本模块不再二次切分），
-- 少发 / 多发都不是「部分履约」，而是流程没编排对。
-- 结算口径统一 HALF_UP 到分（照抄 V116 / V92 / 10.5.1.3 同一约定）。
--
-- ===========================================================================
-- 六、建表顺序：按外键依赖，不是按职责顺序
-- ===========================================================================
--   head → 预占 → 出库单 → 作业 → 流程状态记录 → 超时 → 事件
-- step 表引用 reservation_id / outbound_order_id，所以必须排在它们**之后**。
--
-- ---------------------------------------------------------------------------
-- 1. 履约请求头（流程状态本身：可推进，但只经四条窄函数）
-- ---------------------------------------------------------------------------
-- 为什么这张表允许 UPDATE 而下面六张表不允许：
--   * 它是**当前**流程状态，不是举证凭据 —— 进程重启后要能从它继续，
--     「只追加」在这里反而会让「现在到哪一步了」没有唯一答案；
--   * 但**每一次**推进都会在 ..._step / ..._event 留痕，且 UPDATE 权限被
--     REVOKE 掉，只能经 advance/block/retry/cancel 四个窄函数发起（第 10 节），
--     所以 append-only 的实质并没有丢。
--
-- idempotency_key：蓝图输出没有点名，但「重复 webhook / 重复事件只产生一张订单」
-- 是 09 卷的关键约束，履约请求同样要防重复建单，所以 UNIQUE(tenant_id,idempotency_key)。
CREATE TABLE fulfillment_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 业务请求号：同租户唯一，界面与日志里认它。
  request_code varchar(64) NOT NULL CHECK(btrim(request_code)<>''),
  -- ---- 上游逻辑引用（**只存引用 + 快照，不 JOIN**，理由见文件头第零节）----
  order_ref varchar(64) NOT NULL,
  order_line_ref varchar(64) NOT NULL DEFAULT '',
  -- 9.3.1.1 拆单（V151 并行车道，集成头**未交付**）：只占列，不建表不读表不校验。
  split_unit_ref varchar(64) NOT NULL DEFAULT '',
  -- 9.3.1.2 合单（V144，已交付）
  consolidation_ref varchar(64) NOT NULL DEFAULT '',
  -- 9.3.2.1 分仓（V116，已交付）
  allocation_decision_ref varchar(64) NOT NULL DEFAULT '',
  -- 9.3.2.2 FBA / 自发货路径（V92，已交付）
  routing_decision_ref varchar(64) NOT NULL DEFAULT '',
  sku_ref varchar(64) NOT NULL,
  platform_code varchar(32) NOT NULL DEFAULT '',
  -- ---- 决策时刻的快照（上游事后都会变，存引用答不出「当时按什么判的」）----
  warehouse_ref varchar(64) NOT NULL CHECK(btrim(warehouse_ref)<>''),
  legal_entity_code varchar(64) NOT NULL CHECK(btrim(legal_entity_code)<>''),
  destination_country_code varchar(2) NOT NULL CHECK(length(destination_country_code)=2),
  destination_region_code varchar(32) NOT NULL DEFAULT '*',
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty>0),
  -- 金额：本模块不做计价（计价在 7.2 / 10），但出库与作业可能带估值，必须 BigDecimal。
  estimated_handling_amount numeric(19,4),
  currency_code varchar(3) NOT NULL DEFAULT 'USD' CHECK(currency_code=upper(currency_code)),
  -- ---- 流程状态（蓝图加粗句的落点）----
  status varchar(24) NOT NULL CHECK(status IN (
    'DRAFT','RESERVING','RESERVED','OUTBOUND_PENDING','OUTBOUND_CONFIRMED',
    'WORKING','COMPLETED','BLOCKED','CANCELLED')),
  -- 当前卡在哪一步 —— **断点恢复就是从这里继续**。阶梯见文件头第二节。
  current_step varchar(16) NOT NULL CHECK(current_step IN ('RESERVE','OUTBOUND','WORK','TRACK')),
  -- 乐观并发版本：蓝图输入里点名的 expected_version 就是与它比对。
  process_version bigint NOT NULL DEFAULT 0 CHECK(process_version>=0),
  -- 第几次尝试：首次建单 = 1，每次重试 +1。
  attempt int NOT NULL DEFAULT 1 CHECK(attempt>=1),
  -- 每个流程步的超时阈值（秒，租户口径）。只给步级，不给请求级总时长：
  -- 「卡在哪一步」比「总共跑了多久」可处置得多。
  reserve_timeout_seconds int NOT NULL DEFAULT 3600 CHECK(reserve_timeout_seconds>0),
  outbound_timeout_seconds int NOT NULL DEFAULT 7200 CHECK(outbound_timeout_seconds>0),
  work_timeout_seconds int NOT NULL DEFAULT 14400 CHECK(work_timeout_seconds>0),
  track_timeout_seconds int NOT NULL DEFAULT 86400 CHECK(track_timeout_seconds>0),
  -- 幂等键：重复建单只产生一张履约请求（09 卷关键约束）。
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
  -- ---- 时间 ----
  requested_at timestamptz NOT NULL,
  -- 当前步的截止时刻。**只由 retry / 建单 / 推进写**，不由服务层随手改 ——
  -- 否则「卡住了」这件事会被一次普通推进悄悄抹掉。
  deadline_at timestamptz NOT NULL,
  last_step_at timestamptz NOT NULL,
  blocked_at timestamptz,
  blocked_reason_code varchar(32) NOT NULL DEFAULT '' CHECK(blocked_reason_code IN (
    '','RESERVE_TIMEOUT','RESERVE_REJECTED','OUTBOUND_TIMEOUT','OUTBOUND_REJECTED',
    'WORK_TIMEOUT','TRACK_TIMEOUT','STALE_ACK','UPSTREAM_MISMATCH')),
  blocked_reason varchar(500) NOT NULL DEFAULT '',
  completed_at timestamptz,
  cancelled_at timestamptz,
  cancel_reason varchar(400) NOT NULL DEFAULT '',
  notes varchar(400) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_code),
  UNIQUE(tenant_id,idempotency_key),
  CHECK(btrim(order_ref)<>''),
  CHECK(btrim(sku_ref)<>''),
  -- 上游引用至少要给一个：履约请求是「拆合单 / 分仓的结果」，四者皆空说明
  -- 调用方不知道自己为什么要发这批货。
  CHECK(btrim(split_unit_ref)<>'' OR btrim(consolidation_ref)<>''
     OR btrim(allocation_decision_ref)<>'' OR btrim(routing_decision_ref)<>''),
  -- 取消必须带原因：取消一个履约流程等于把货停在半路，不写理由等于查不出谁停的。
  CHECK(status<>'CANCELLED' OR (cancelled_at IS NOT NULL AND btrim(cancel_reason)<>'')),
  CHECK(status<>'COMPLETED' OR completed_at IS NOT NULL),
  -- COMPLETED 必须站在阶梯顶端：跟踪完成之前不可能说完成。
  CHECK(status<>'COMPLETED' OR current_step='TRACK'),
  CHECK(status<>'BLOCKED' OR (blocked_at IS NOT NULL AND btrim(blocked_reason_code)<>'')),
  CHECK(status='BLOCKED' OR (blocked_at IS NULL AND btrim(blocked_reason_code)='')),
  -- deadline 必须晚于「这一步开始」，否则建单即超时（这正是 -28800s 时区偏移的现场）。
  CHECK(deadline_at>last_step_at),
  CHECK(updated_at>=created_at),
  CHECK(completed_at IS NULL OR completed_at>=requested_at),
  CHECK(cancelled_at IS NULL OR cancelled_at>=requested_at),
  CHECK(blocked_at IS NULL OR blocked_at>=requested_at)
);
CREATE INDEX fulfillment_request_board
  ON fulfillment_request(tenant_id,status,deadline_at);
CREATE INDEX fulfillment_request_step_ladder
  ON fulfillment_request(tenant_id,current_step,last_step_at DESC);
CREATE INDEX fulfillment_request_order
  ON fulfillment_request(tenant_id,order_ref,requested_at DESC);
CREATE INDEX fulfillment_request_blocked
  ON fulfillment_request(tenant_id,blocked_at DESC) WHERE status='BLOCKED';
CREATE INDEX fulfillment_request_overdue
  ON fulfillment_request(tenant_id,deadline_at) WHERE status NOT IN ('COMPLETED','CANCELLED');

-- ---------------------------------------------------------------------------
-- 2. 锁定预占指令（下游 7.2；本服务内登记，**不写 warehouse 库存**）
-- ---------------------------------------------------------------------------
-- ⚠ 这张表是文件头第一节那条纪律的载体：
--   缺省状态是 REQUESTED（**指令已登记，待下游回执**），不是 LOCKED。
--   没有下游回执就写 LOCKED，等于替 warehouse 宣布「这批货锁住了」——
--   而 warehouse 那边其实什么都没锁，两边都以为占了同一批货（V116 踩过的坑）。
CREATE TABLE fulfillment_request_reservation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  sku_ref varchar(64) NOT NULL,
  -- 本次指令要预占的数量：等于该履约请求的需求量（一次请求 = 一个履约单元）。
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty>0),
  -- 缺省就是「还没锁」：本模块不声称锁了上游库存。
  status varchar(16) NOT NULL DEFAULT 'REQUESTED' CHECK(status IN (
    'REQUESTED','LOCKED','REJECTED','RELEASED')),
  -- 下游（7.2 warehouse 服务）回执里的凭据；跨库不可读，这里只是收到后的登记。
  reservation_ref varchar(64) NOT NULL DEFAULT '',
  locked_qty numeric(19,6),
  reject_reason varchar(400) NOT NULL DEFAULT '',
  requested_at timestamptz NOT NULL,
  acknowledged_at timestamptz,
  released_at timestamptz,
  -- 幂等键：将来真接 warehouse 时，同键重放不会让两边各锁一批。
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
  attempt int NOT NULL DEFAULT 1 CHECK(attempt>=1),
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,idempotency_key),
  UNIQUE(tenant_id,request_id,attempt),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  CHECK(btrim(warehouse_ref)<>''),
  CHECK(btrim(sku_ref)<>''),
  -- 已锁：必须带下游凭据、正数锁定量与回执时间。
  CHECK(status<>'LOCKED' OR (btrim(reservation_ref)<>'' AND locked_qty IS NOT NULL
     AND locked_qty>0 AND acknowledged_at IS NOT NULL)),
  -- 已锁数量不得超过指令数量（锁多了就是超发，正是 9.3 要解决的问题）。
  CHECK(locked_qty IS NULL OR locked_qty<=requested_qty),
  -- 已拒：必须带原因，且不得同时带锁定痕迹。
  CHECK(status<>'REJECTED' OR (btrim(reject_reason)<>'' AND locked_qty IS NULL)),
  -- REJECTED **也**是回执，所以允许带 acknowledged_at；只有「还在等回执」的 REQUESTED
  -- 不许有回执时间（带了就等于把「还没等到」说成「已经回过」）。
  -- RELEASED 同样可以是主动释放（未收到回执就释放），所以也不约束。
  CHECK(status <> 'REQUESTED' OR acknowledged_at IS NULL),
  CHECK(status<>'RELEASED' OR released_at IS NOT NULL),
  CHECK(acknowledged_at IS NULL OR acknowledged_at>=requested_at),
  CHECK(released_at IS NULL OR released_at>=requested_at)
);
CREATE INDEX fulfillment_request_reservation_request
  ON fulfillment_request_reservation(tenant_id,request_id,attempt DESC);
CREATE INDEX fulfillment_request_reservation_pending
  ON fulfillment_request_reservation(tenant_id,requested_at)
  WHERE status='REQUESTED';

-- ---------------------------------------------------------------------------
-- 3. 出库单（下游 8.3.1；同样是本服务内登记的下游指令）
-- ---------------------------------------------------------------------------
-- 蓝图输出第二项写的是「出库单」，但 8.3.1 在 warehouse 服务的独立数据库里，
-- 跨库建表 / JOIN / FK 被明令禁止。所以这里登记的是**出库单请求凭据**：
-- 出了哪张单、发哪个仓、多少量、下游回了什么。8.3.1 落地后由它按同一形状对接。
CREATE TABLE fulfillment_request_outbound_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  outbound_qty numeric(19,6) NOT NULL CHECK(outbound_qty>0),
  -- 下游 8.3.1 的出库单号（回执后才有；REQUESTED 阶段为空是正常的）。
  outbound_ref varchar(64) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'REQUESTED' CHECK(status IN (
    'REQUESTED','CONFIRMED','REJECTED','CANCELLED')),
  -- 一个包裹可能拆多个出库单（分仓规则允许部分分配时），
  -- 所以 **一个请求可以有多张出库单**，合计不得超过需求量。
  package_ref varchar(64) NOT NULL DEFAULT '',
  reject_reason varchar(400) NOT NULL DEFAULT '',
  requested_at timestamptz NOT NULL,
  acknowledged_at timestamptz,
  cancelled_at timestamptz,
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
  attempt int NOT NULL DEFAULT 1 CHECK(attempt>=1),
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  CHECK(btrim(warehouse_ref)<>''),
  CHECK(status<>'CONFIRMED' OR (btrim(outbound_ref)<>'' AND acknowledged_at IS NOT NULL)),
  CHECK(status<>'REJECTED' OR (btrim(reject_reason)<>'' AND acknowledged_at IS NOT NULL)),
  CHECK(status<>'CANCELLED' OR (cancelled_at IS NOT NULL AND btrim(reject_reason)<>'')),
  CHECK(status='CONFIRMED' OR (outbound_ref='' AND acknowledged_at IS NULL)),
  CHECK(acknowledged_at IS NULL OR acknowledged_at>=requested_at)
);
CREATE INDEX fulfillment_request_outbound_order_request
  ON fulfillment_request_outbound_order(tenant_id,request_id,attempt DESC);
CREATE INDEX fulfillment_request_outbound_order_pending
  ON fulfillment_request_outbound_order(tenant_id,requested_at)
  WHERE status='REQUESTED';

-- ---------------------------------------------------------------------------
-- 4. 编排作业（「编排作业」这一步的载体，append-only）
-- ---------------------------------------------------------------------------
-- 作业行是**仓库侧**要做的事（拣货 / 复核 / 打包 / 交接）。本模块只登记编排结果与
-- 回执，不假装已经叫了人。task_type 的分档与 8.3.3 打包、8.3.4 交接对齐，
-- 但**不建外键**（跨库）。
CREATE TABLE fulfillment_request_work_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  -- 同一个请求可有多条作业（拣货 / 复核 / 打包…），所以工作单号在请求内唯一。
  work_order_code varchar(64) NOT NULL,
  task_type varchar(16) NOT NULL CHECK(task_type IN ('PICK','RECHECK','PACK','HANDOVER')),
  warehouse_ref varchar(64) NOT NULL,
  sku_ref varchar(64) NOT NULL,
  qty numeric(19,6) NOT NULL CHECK(qty>0),
  status varchar(16) NOT NULL DEFAULT 'ASSIGNED' CHECK(status IN (
    'ASSIGNED','RUNNING','DONE','FAILED','SKIPPED')),
  -- 作业由谁认领（下游作业系统回填；本服务只存 ID 逻辑引用）。
  assignee_ref varchar(64) NOT NULL DEFAULT '',
  started_at timestamptz,
  finished_at timestamptz,
  failure_reason varchar(400) NOT NULL DEFAULT '',
  -- 单条作业的幂等键：重放编排不会凭空多出一条作业。
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
  attempt int NOT NULL DEFAULT 1 CHECK(attempt>=1),
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_id,work_order_code),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  CHECK(btrim(work_order_code)<>''),
  CHECK(btrim(warehouse_ref)<>''),
  -- 作业终态不许复活：append-only 表用 CHECK 表达「状态词表封闭」，
  -- 与已落库的作业凭据同纪律（改主意请新开一条作业）。
  CHECK(status IN ('ASSIGNED','RUNNING','DONE','FAILED','SKIPPED')),
  CHECK(status NOT IN ('DONE','FAILED') OR finished_at IS NOT NULL),
  CHECK(status='DONE' OR btrim(failure_reason)=''),
  CHECK(status<>'FAILED' OR btrim(failure_reason)<>''),
  CHECK(status IN ('ASSIGNED','RUNNING') OR started_at IS NOT NULL),
  CHECK(finished_at IS NULL OR started_at IS NULL OR finished_at>=started_at)
);
CREATE INDEX fulfillment_request_work_order_request
  ON fulfillment_request_work_order(tenant_id,request_id,task_type);
CREATE INDEX fulfillment_request_work_order_open
  ON fulfillment_request_work_order(tenant_id,status)
  WHERE status IN ('ASSIGNED','RUNNING');

-- ---------------------------------------------------------------------------
-- 5. 流程状态记录（append-only；每步每次尝试一行 = 断点恢复的凭据）
-- ---------------------------------------------------------------------------
-- 为什么是「每步每次尝试一行」而不是「一行一列存四个状态」：
--   后者在重试时要么覆盖第一次的结果（丢掉「第一次为什么失败」），
--   要么得再加 first_at/retry_at 两套列（列爆炸）。前者天然 append-only，
--   「哪些步完成过」= exists(status='COMPLETED')，
--   「这个步失败过几次」= count(status='FAILED')。
--
-- ⚠ 本表**必须**排在第 2 / 3 节之后：它对 reservation_id / outbound_order_id
-- 建了复合外键，被引用表不存在时 CREATE TABLE 会直接失败。
CREATE TABLE fulfillment_request_step (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  step_kind varchar(16) NOT NULL CHECK(step_kind IN ('RESERVE','OUTBOUND','WORK','TRACK')),
  -- 该步第几次尝试：与请求头的 attempt 对齐，便于「第二次才成功」一眼可读。
  attempt int NOT NULL CHECK(attempt>=1),
  status varchar(16) NOT NULL CHECK(status IN ('PENDING','RUNNING','COMPLETED','FAILED','SKIPPED')),
  -- 本步涉及的下游行（指向本服务内的下游指令表，不跨服务）。
  reservation_id uuid,
  outbound_order_id uuid,
  work_order_count int NOT NULL DEFAULT 0 CHECK(work_order_count>=0),
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  elapsed_seconds numeric(12,3),
  -- 失败 / 超时的原因：COMPLETED 时必须为空（成功行不许带失败理由）。
  failure_code varchar(32) NOT NULL DEFAULT '' CHECK(failure_code IN (
    '','RESERVE_REJECTED','OUTBOUND_REJECTED','WORK_FAILED','TIMEOUT','STALE_ACK',
    'UPSTREAM_MISMATCH','INSUFFICIENT_STOCK')),
  failure_reason varchar(500) NOT NULL DEFAULT '',
  operated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一步同一次尝试只有一行：两次 RUNNING 意味着「这一步开始了两次」，
  -- 取哪一次的 finished_at 无解。
  UNIQUE(tenant_id,request_id,step_kind,attempt),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  FOREIGN KEY(tenant_id,reservation_id)
    REFERENCES fulfillment_request_reservation(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_order_id)
    REFERENCES fulfillment_request_outbound_order(tenant_id,id),
  -- 终态与时间必须自洽：跑完就必须有时间，没跑完就不许有结束时间。
  CHECK((status IN ('COMPLETED','FAILED','SKIPPED')) = (finished_at IS NOT NULL)),
  CHECK(status<>'COMPLETED' OR (btrim(failure_code)='' AND btrim(failure_reason)='')),
  CHECK(status<>'FAILED' OR btrim(failure_code)<>''),
  CHECK(finished_at IS NULL OR finished_at>=started_at),
  -- elapsed 只在跑完时才有意义；留空而不是 0 是因为 0 会被读成「一瞬间完成」。
  CHECK(elapsed_seconds IS NULL OR (finished_at IS NOT NULL AND elapsed_seconds>=0)),
  -- 本步真的动了东西就必须报出动了多少：一步 COMPLETED 的 OUTBOUND
  -- 说「出了 0 张出库单」，那等于没出库。
  CHECK(step_kind<>'OUTBOUND' OR status<>'COMPLETED' OR outbound_order_id IS NOT NULL),
  CHECK(step_kind<>'RESERVE' OR status<>'COMPLETED' OR reservation_id IS NOT NULL),
  CHECK(step_kind<>'WORK' OR status<>'COMPLETED' OR work_order_count>0),
  -- 完成 / 失败步必须指向真实存在的凭据：报「预占完成」却没预占单、
  -- 报「出库完成」却没出库单，等于把没做的事写成了做过。两条分开写，
  -- 不合并成一条「或」，否则 OUTBOUND 步挂一个 reservation_id 也能通过。
  CHECK(step_kind<>'RESERVE'
     OR status NOT IN ('COMPLETED','FAILED') OR reservation_id IS NOT NULL),
  CHECK(step_kind<>'OUTBOUND'
     OR status NOT IN ('COMPLETED','FAILED') OR outbound_order_id IS NOT NULL)
);
CREATE INDEX fulfillment_request_step_feed
  ON fulfillment_request_step(tenant_id,request_id,started_at DESC);
CREATE INDEX fulfillment_request_step_completed
  ON fulfillment_request_step(tenant_id,request_id,step_kind)
  WHERE status='COMPLETED';

-- ---------------------------------------------------------------------------
-- 6. 超时 / 失败检出记录（append-only；「超时重试」的事实侧）
-- ---------------------------------------------------------------------------
-- 只记**本模块自己编排时能检出**的事实。跨请求的异常监控看板与人工处置入口
-- 判给 9.3.3.2（见文件头第三节）—— 本表是它的数据来源，不是它的替代品。
CREATE TABLE fulfillment_request_timeout (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  step_kind varchar(16) NOT NULL CHECK(step_kind IN ('RESERVE','OUTBOUND','WORK','TRACK')),
  attempt int NOT NULL CHECK(attempt>=1),
  detection_kind varchar(16) NOT NULL CHECK(detection_kind IN (
    'TIMEOUT','STEP_FAILED','STALE_ACK')),
  -- 判定依据：超时的那个 deadline 时刻、已流逝秒数。带 threshold_seconds
  -- 是为了让界面上「卡了 2 小时 5 分 / 阈值 1 小时」两句话都能说清。
  due_at timestamptz NOT NULL,
  detected_at timestamptz NOT NULL,
  overdue_seconds numeric(12,3) NOT NULL CHECK(overdue_seconds>=0),
  threshold_seconds int NOT NULL CHECK(threshold_seconds>0),
  reason_code varchar(32) NOT NULL CHECK(reason_code IN (
    'RESERVE_TIMEOUT','RESERVE_REJECTED','OUTBOUND_TIMEOUT','OUTBOUND_REJECTED',
    'WORK_TIMEOUT','TRACK_TIMEOUT','STALE_ACK','UPSTREAM_MISMATCH')),
  reason varchar(500) NOT NULL CHECK(btrim(reason)<>''),
  -- 检出后把请求挂到 BLOCKED：真正「卡住」这件事由头行承担，
  -- 本行是它的凭据（延迟约束强制 BLOCKED 必须有这样的行）。
  blocked_at timestamptz NOT NULL,
  detected_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一步同一次尝试只检出一条：重复检出说明扫描被跑了两次。
  UNIQUE(tenant_id,request_id,step_kind,attempt),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  -- 只有「真的超时」才要求检出/挂起时刻晚于 deadline：
  -- 步骤**报失败**或回执陈旧时，流程是在阈值之前就该被发现的，
  -- 拿 deadline 去卡它等于把「提前发现的问题」判成非法。
  CHECK(detected_at>=due_at OR detection_kind<>'TIMEOUT'),
  CHECK(blocked_at>=due_at OR detection_kind<>'TIMEOUT')
);
CREATE INDEX fulfillment_request_timeout_feed
  ON fulfillment_request_timeout(tenant_id,detected_at DESC);
CREATE INDEX fulfillment_request_timeout_request
  ON fulfillment_request_timeout(tenant_id,request_id);

-- ---------------------------------------------------------------------------
-- 7. 事件流（append-only；蓝图输出第四项「事件（下游消费）」）
-- ---------------------------------------------------------------------------
-- 用 event_kind 区分来源，而不是造六张形状雷同的表：事后复盘要回答的是
-- 「谁在什么时候把这个流程推到了哪一步」，只留当前状态答不出来。
CREATE TABLE fulfillment_request_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  event_kind varchar(24) NOT NULL CHECK(event_kind IN (
    'CREATED','RESERVATION_REQUESTED','RESERVATION_LOCKED','RESERVATION_REJECTED',
    'OUTBOUND_REQUESTED','OUTBOUND_CONFIRMED','OUTBOUND_REJECTED',
    'WORK_ASSIGNED','WORK_DONE','WORK_FAILED',
    'BLOCKED','RETRY_REQUESTED','RESUMED','COMPLETED','CANCELLED','NOTE')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  -- 事件版本：与头行 process_version 对齐，下游据此做**有序**消费。
  -- 事件乱序到达时，「版本倒流」这条比「时间戳倒流」可靠（同一微秒可写多行）。
  process_version bigint NOT NULL CHECK(process_version>=0),
  detail text NOT NULL DEFAULT '',
  actor_id uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  CHECK(btrim(detail)<>'' OR event_kind<>'NOTE'),
  CHECK(event_kind NOT IN ('COMPLETED','CANCELLED')
     OR btrim(from_status)<>'' OR btrim(to_status)<>'')
);
CREATE INDEX fulfillment_request_event_feed
  ON fulfillment_request_event(tenant_id,request_id,process_version DESC);
CREATE INDEX fulfillment_request_event_consumers
  ON fulfillment_request_event(tenant_id,event_kind,occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 8. DB 级不变量
-- ---------------------------------------------------------------------------
-- 8.1 头行状态机：只允许合法推进，且终态不许离开。
--     转移表与 Java 枚举 FulfillmentRequestStatus.allowedTargets() 逐条对应，
--     两边不一致由集成测试的「DB 与 Java 转移表一致」一节钉住。
CREATE FUNCTION guard_fulfillment_request_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- ---- 非法跳转一律拒（不是只拦终态，是白名单）----
 IF TG_OP = 'UPDATE' AND NEW.status <> OLD.status THEN
  IF NOT (
       (OLD.status = 'DRAFT'                AND NEW.status IN ('RESERVING','BLOCKED','CANCELLED'))
    OR (OLD.status = 'RESERVING'            AND NEW.status IN ('RESERVED','BLOCKED','CANCELLED'))
    OR (OLD.status = 'RESERVED'             AND NEW.status IN ('OUTBOUND_PENDING','BLOCKED','CANCELLED'))
    OR (OLD.status = 'OUTBOUND_PENDING'     AND NEW.status IN ('OUTBOUND_CONFIRMED','BLOCKED','CANCELLED'))
    OR (OLD.status = 'OUTBOUND_CONFIRMED'   AND NEW.status IN ('WORKING','BLOCKED','CANCELLED'))
    OR (OLD.status = 'WORKING'              AND NEW.status IN ('COMPLETED','BLOCKED','CANCELLED'))
    -- BLOCKED 只能回到「该步对应的可恢复状态」，或被取消。
    -- 落回哪一步由 current_step 决定（retry 函数里算好），这里只校验它与
    -- current_step 自洽：这一步没做完就不许说它做完了。
    OR (OLD.status = 'BLOCKED'              AND NEW.status IN ('RESERVING','OUTBOUND_PENDING','WORKING','CANCELLED'))
    -- DRAFT 也能挂起：建了单没人推进、阈值又过了，那也是「卡住」——
    -- 不放行的话超时扫描会把它整个漏掉，界面上看不到任何异常。
  ) THEN
   RAISE EXCEPTION '履约请求状态不能从「%」直接改到「%」：跳过步骤的流程一旦断电就永远接不回来',
     OLD.status, NEW.status USING ERRCODE='23514',
     HINT='合法转移见 V152 第 8.1 节；推进请走 advance_fulfillment_request_status';
  END IF;
 END IF;
 -- ---- 终态不许离开（只拦离开，不拦进入）----
 IF TG_OP = 'UPDATE' AND OLD.status IN ('COMPLETED','CANCELLED') THEN
  RAISE EXCEPTION '履约请求当前状态「%」是终态，不允许再改状态', OLD.status USING ERRCODE='23514',
    HINT='已完成 / 已取消的履约流程是凭据；重发请新建一条履约请求';
 END IF;
 -- ---- 版本必须单调递增（乐观并发被绕过时的最后一道钉子）----
 IF TG_OP = 'UPDATE' AND NEW.process_version <= OLD.process_version THEN
  RAISE EXCEPTION 'process_version 必须严格递增（% → %）：并发控制不能靠「顺手改一下」绕过',
    OLD.process_version, NEW.process_version USING ERRCODE='23514';
 END IF;
 -- ---- 从 BLOCKED 恢复时，落到的状态必须与 current_step 对得上 ----
 IF TG_OP = 'UPDATE' AND OLD.status = 'BLOCKED' AND NEW.status <> 'CANCELLED' THEN
  IF (NEW.current_step = 'RESERVE'  AND NEW.status <> 'RESERVING')
  OR (NEW.current_step = 'OUTBOUND' AND NEW.status <> 'OUTBOUND_PENDING')
  OR (NEW.current_step IN ('WORK','TRACK') AND NEW.status <> 'WORKING') THEN
   RAISE EXCEPTION '从卡住恢复时，状态「%」与 current_step「%」不自洽：这一步还没做完，不能标成已完成的那一步',
     NEW.status, NEW.current_step USING ERRCODE='23514';
  END IF;
 END IF;
 -- ---- 进入 BLOCKED 必须带原因；离开必须清干净 ----
 IF NEW.status = 'BLOCKED' AND (NEW.blocked_at IS NULL OR btrim(NEW.blocked_reason_code)='') THEN
  RAISE EXCEPTION '履约请求 % 挂起时必须带挂起时刻与原因码：卡住了却说不出为什么，超时就永远无法处置',
    NEW.id USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'BLOCKED'
    AND (NEW.blocked_at IS NOT NULL OR btrim(NEW.blocked_reason_code)<>'') THEN
  RAISE EXCEPTION '状态「%」不是挂起，不允许带挂起痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 -- ---- 重新推进后 deadline 必须顺延（否则重试出来立刻又超时）----
 -- 比较用 < 而不是 <=：deadline 取 greatest(now+阈值, 原值+1µs)，
 -- 单个事务内 now() 冻结时靠 +1µs 保证严格递增（见文件头第三节的 ⚠）。
 IF TG_OP = 'UPDATE' AND NEW.process_version > OLD.process_version
    AND NEW.deadline_at < OLD.deadline_at THEN
  RAISE EXCEPTION '推进/重试后 deadline 不得回退（原 % → 新 %），否则重试出来当场又超时',
    OLD.deadline_at, NEW.deadline_at USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fulfillment_request_state BEFORE INSERT OR UPDATE
  ON fulfillment_request
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_state();

-- 8.2 阶梯不变量：**current_step 之前的每一步都必须真的完成过**。
--     这是「断点恢复」在 DB 上的落点，也是本模块最重要的一条约束。
--     用**延迟约束触发器 + 回表读当前行**（不用 NEW 的可变列）：服务层写入顺序是
--     「插头 → 写预占 / 出库单 / 作业 / step / timeout → 写事件」，
--     延迟到提交时 INSERT 那一次拿着的仍是半成品 NEW，用它每次必报。
CREATE FUNCTION guard_fulfillment_request_ladder() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; want varchar(16);
BEGIN
 SELECT id, tenant_id, status, current_step, attempt
   INTO head FROM fulfillment_request WHERE id = NEW.id;
 IF head IS NULL THEN RETURN NULL; END IF;

 -- (1) 阶梯：current_step 之前的每一步都必须有一条 COMPLETED 的 step 行。
 --     RESERVE 是最低阶，不要求任何前置；其余三阶依次要求前置完成。
 IF head.current_step IN ('OUTBOUND','WORK','TRACK') THEN
  IF NOT EXISTS (SELECT 1 FROM fulfillment_request_step
                  WHERE tenant_id = head.tenant_id AND request_id = head.id
                    AND step_kind = 'RESERVE' AND status = 'COMPLETED') THEN
   RAISE EXCEPTION
     '履约请求 % 已推进到「%」但锁定预占步（RESERVE）没有完成记录：跳过预占直接出库就是超发',
     head.id, head.current_step USING ERRCODE='23514',
     HINT='先写一条 step_kind=RESERVE 且 status=COMPLETED 的 fulfillment_request_step 再提交';
  END IF;
 END IF;
 IF head.current_step IN ('WORK','TRACK') THEN
  IF NOT EXISTS (SELECT 1 FROM fulfillment_request_step
                  WHERE tenant_id = head.tenant_id AND request_id = head.id
                    AND step_kind = 'OUTBOUND' AND status = 'COMPLETED') THEN
   RAISE EXCEPTION
     '履约请求 % 已推进到「%」但出库步（OUTBOUND）没有完成记录：没有出库单就不存在可编排的作业',
     head.id, head.current_step USING ERRCODE='23514',
     HINT='先写一条 step_kind=OUTBOUND 且 status=COMPLETED 的 fulfillment_request_step 再提交';
  END IF;
 END IF;
 IF head.current_step = 'TRACK' THEN
  IF NOT EXISTS (SELECT 1 FROM fulfillment_request_step
                  WHERE tenant_id = head.tenant_id AND request_id = head.id
                    AND step_kind = 'WORK' AND status = 'COMPLETED') THEN
   RAISE EXCEPTION
     '履约请求 % 已推进到跟踪完成但作业步（WORK）没有完成记录：没有干完的活就说跟踪完成，等于没跟踪',
     head.id USING ERRCODE='23514',
     HINT='先写一条 step_kind=WORK 且 status=COMPLETED 的 fulfillment_request_step 再提交';
  END IF;
  -- 光有 WORK 步的完成行还不够：那行只说「派过活」。跟踪完成要求**确实干完了一条**，
  -- 否则「一张 DONE 都没有就宣布跟踪完成」会一路通到 COMPLETED。
  IF NOT EXISTS (SELECT 1 FROM fulfillment_request_work_order
                  WHERE tenant_id = head.tenant_id AND request_id = head.id
                    AND status = 'DONE') THEN
   RAISE EXCEPTION
     '履约请求 % 已推进到跟踪完成但没有一条已完成的编排作业：派了活不等于干完了活',
     head.id USING ERRCODE='23514',
     HINT='先用 complete_fulfillment_work_order 把作业登记为 RUNNING 再置 DONE';
  END IF;
 END IF;

 -- (2) 三个下游凭据按状态分档要求（全部回表统计，不看 NEW）。
 --     关键点：本模块只**登记**下游指令，锁没锁住由 warehouse 的回执说了算。
 IF head.status IN ('RESERVED','OUTBOUND_PENDING','OUTBOUND_CONFIRMED','WORKING','COMPLETED')
    AND NOT EXISTS (SELECT 1 FROM fulfillment_request_reservation
                     WHERE tenant_id = head.tenant_id AND request_id = head.id
                       AND status = 'LOCKED') THEN
  RAISE EXCEPTION
    '履约请求 % 状态是「%」但没有任何已锁定的预占记录：本模块只登记下游指令，'
    '锁没锁住由 warehouse 的回执说了算',
    head.id, head.status USING ERRCODE='23514',
    HINT='先登记 reservation 并把下游回执写成 LOCKED 再提交';
 END IF;
 IF head.status IN ('OUTBOUND_CONFIRMED','WORKING','COMPLETED')
    AND NOT EXISTS (SELECT 1 FROM fulfillment_request_outbound_order
                     WHERE tenant_id = head.tenant_id AND request_id = head.id
                       AND status = 'CONFIRMED') THEN
  RAISE EXCEPTION
    '履约请求 % 状态是「%」但没有任何已确认的出库单：出库单是下游 8.3.1 开的，'
    '没有它的回执就不许说「已出库」',
    head.id, head.status USING ERRCODE='23514',
    HINT='先登记 outbound_order 并把 8.3.1 的回执写成 CONFIRMED 再提交';
 END IF;
 -- 只在「已完成」上要求真有干完的作业：要求「进入作业编排中之前就已经有作业」
 -- 会强迫一次事务里先建作业再推进状态，而服务层的每次调用各自一个事务 ——
 -- 那是把事务边界写进业务规则，换来一条并不更有价值的约束。
 -- 「说在干却没有任何活可干」这条更实在的护栏留在 current_step='TRACK' 上（见上）。
 IF head.status = 'COMPLETED'
    AND NOT EXISTS (SELECT 1 FROM fulfillment_request_work_order
                     WHERE tenant_id = head.tenant_id AND request_id = head.id
                       AND status IN ('ASSIGNED','RUNNING','DONE')) THEN
  RAISE EXCEPTION
    '履约请求 % 状态是「%」但一条编排作业都没有：说在干活却没有任何活可干',
    head.id, head.status USING ERRCODE='23514',
    HINT='先写 fulfillment_request_work_order 再提交';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER fulfillment_request_ladder
  AFTER INSERT OR UPDATE ON fulfillment_request
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_ladder();

-- 8.3 BLOCKED ⇒ 必须有一条 timeout 记录承载（回表读当前行，避开 NEW）。
--     「卡住」是硬事实，必须有据可查，否则界面上会出现一个说不清为什么卡住的流程。
CREATE FUNCTION guard_fulfillment_request_blocked() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; cnt bigint;
BEGIN
 SELECT id, tenant_id, status, current_step, attempt INTO head
   FROM fulfillment_request WHERE id = NEW.id;
 IF head IS NULL OR head.status <> 'BLOCKED' THEN RETURN NULL; END IF;
 SELECT count(*) INTO cnt FROM fulfillment_request_timeout
  WHERE tenant_id = NEW.tenant_id AND request_id = head.id
    AND step_kind = head.current_step AND attempt = head.attempt;
 IF cnt = 0 THEN
  RAISE EXCEPTION
    '履约请求 % 处于挂起状态但没有对应的超时/失败检出记录：挂起必须说清是哪一步、因为什么、卡了多久',
    head.id USING ERRCODE='23514',
    HINT='先写一条 fulfillment_request_timeout（step_kind 必须是当前的 current_step）再提交';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER fulfillment_request_blocked
  AFTER INSERT OR UPDATE ON fulfillment_request
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_blocked();

-- 8.4 timeout 记录必须与头行状态一致（回表读当前行）。
--     「流程已经恢复走了却还挂着一条挂起记录」会让复盘两说并存。
CREATE FUNCTION guard_fulfillment_request_timeout_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record;
BEGIN
 SELECT id, tenant_id, status, current_step, attempt INTO head
   FROM fulfillment_request WHERE id = NEW.request_id AND tenant_id = NEW.tenant_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '超时记录指向的履约请求 % 不存在或不属于本租户', NEW.request_id USING ERRCODE='23503';
 END IF;
 IF head.attempt <> NEW.attempt THEN
  RAISE EXCEPTION
    '超时记录挂在第 % 次尝试上，但请求当前已是第 % 次：重试之后再补一条旧尝试的挂起记录会让复盘对不上',
    NEW.attempt, head.attempt USING ERRCODE='23514';
 END IF;
 -- 当前这次尝试已经不在 BLOCKED 上时，不允许再补挂起凭据（否则可事后「造」一个挂起）。
 IF head.status <> 'BLOCKED' AND head.attempt = NEW.attempt THEN
  RAISE EXCEPTION
    '履约请求 % 当前状态是「%」，不允许再补第 % 次尝试的挂起凭据',
    head.id, head.status, NEW.attempt USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER fulfillment_request_timeout_consistency
  AFTER INSERT ON fulfillment_request_timeout
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_timeout_consistency();

-- 8.5 预占行必须与它所属的 RESERVE 步一致（延迟约束，回表读当前行）。
--     「步说完成了、预占行还停在 REQUESTED」= 流程在自欺。
CREATE FUNCTION guard_fulfillment_request_reservation_step() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; step_row record;
BEGIN
 SELECT id, tenant_id, status INTO head
   FROM fulfillment_request WHERE id = NEW.request_id AND tenant_id = NEW.tenant_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '预占指令指向的履约请求 % 不存在或不属于本租户', NEW.request_id USING ERRCODE='23503';
 END IF;
 SELECT id, status, attempt, reservation_id INTO step_row
   FROM fulfillment_request_step
  WHERE tenant_id = NEW.tenant_id AND request_id = head.id
    AND step_kind = 'RESERVE' AND attempt = NEW.attempt;
 IF step_row IS NULL THEN RETURN NULL; END IF;
 IF step_row.status = 'COMPLETED' AND step_row.reservation_id IS DISTINCT FROM NEW.id THEN
  RAISE EXCEPTION
    'RESERVE 步（第 % 次）标为完成但指向的预占指令不是本行 %：完成凭据与凭据本身必须同一条',
    NEW.attempt, NEW.id USING ERRCODE='23514';
 END IF;
 -- 请求已取消：不得再有「已锁定的预占」挂着（货已经放回去了）。
 IF head.status = 'CANCELLED' AND NEW.status = 'LOCKED' THEN
  RAISE EXCEPTION '履约请求 % 已取消，不允许再登记一条已锁定的预占 %',
    head.id, NEW.id USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER fulfillment_request_reservation_step
  AFTER INSERT ON fulfillment_request_reservation
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_reservation_step();

-- 8.6 恢复之后，旧尝试的挂起凭据不得再被当成「当前挂起」。
--     （延迟约束，回表读当前行。）
CREATE FUNCTION guard_fulfillment_request_stale_timeout() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record;
BEGIN
 SELECT id, tenant_id, status, attempt INTO head FROM fulfillment_request WHERE id = NEW.id;
 IF head IS NULL OR head.status = 'BLOCKED' THEN RETURN NULL; END IF;
 IF EXISTS (SELECT 1 FROM fulfillment_request_timeout
             WHERE tenant_id = NEW.tenant_id AND request_id = head.id
               AND attempt = head.attempt) THEN
  RAISE EXCEPTION
    '履约请求 % 已从挂起恢复到「%」，但第 % 次尝试的挂起凭据仍在：恢复必须把旧凭据留在历史里，'
    '而不是继续把它当成当前状态',
    head.id, head.status, head.attempt USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER fulfillment_request_stale_timeout
  AFTER UPDATE ON fulfillment_request
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_stale_timeout();

-- ---------------------------------------------------------------------------
-- 9. 写路径纪律：四张**当前状态**表只经窄函数，三张**留痕**表严格 append-only
-- ---------------------------------------------------------------------------
-- 这里刻意分成两类，而不是「全 append-only」或「全可改」：
--
--   * **当前状态表**（fulfillment_request / _reservation / _outbound_order /
--     _work_order）：它们回答「现在到哪了」。这类行一旦只追加，「现在卡在哪一步、
--     锁住没有、作业干完没有」就没有唯一答案，而蓝图加粗句要的正是「重启后能继续」——
--     所以它们**允许**状态推进，但 UPDATE 权限一律 REVOKE 掉，只能经第 10 节的
--     窄函数发起，且每个窄函数都带版本 / 终态 / 单调性校验。
--   * **留痕表**（_step / _timeout / _event）：它们是**举证凭据**，
--     UPDATE 与 DELETE 一起 REVOKE（V92 第 8.5 节、V116 第 9 节的同一纪律）。
--
-- 「进程重启后要能继续」恰恰要求当前状态**就地**更新 —— 所以把这一类也做成
-- append-only 才是照抄过头：断点恢复要读的是 current_step，不是「把历史重放一遍」。
-- 留痕表仍然完整，所以「重启前发生过什么」一样答得出来。
CREATE FUNCTION guard_fulfillment_request_trail_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- _step 表有一个**唯一**合法的改写口：同一次尝试内 RUNNING → COMPLETED
 -- （重试会开 attempt+1 的新行，所以 append-only 的实质没丢）。这个窗口由
 -- complete_fulfillment_step 在事务内 set_config(..., true) 打开，运行角色伪造不了。
 -- 判据用 transaction-local GUC 而不是「看 NEW.status 放行」：后者会把
 -- 「把 failure_reason 改得好看一点」也一起放行。
 IF TG_TABLE_NAME = 'fulfillment_request_step'
    AND current_setting('app.fulfillment_step_complete', true) = 'on' THEN
  RETURN NEW;
 END IF;
 RAISE EXCEPTION '% 是不可改写的留痕表（%）：已落库的流程凭据是复盘「这批货走到哪、卡在哪」的证据',
  TG_TABLE_NAME, TG_OP USING ERRCODE='23514',
  HINT='留痕行只允许追加；推进流程请用 advance_fulfillment_request_status / retry_fulfillment_request / cancel_fulfillment_request';
END $$;
CREATE FUNCTION guard_fulfillment_request_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 的行不允许删除（%）：已落库的下游指令凭据是复盘「这批货走到哪」的证据',
  TG_TABLE_NAME, TG_OP USING ERRCODE='23514',
  HINT='要放弃一票请推进到 CANCELLED / REJECTED，不要删行';
END $$;
CREATE TRIGGER fulfillment_request_step_append_only BEFORE UPDATE OR DELETE
  ON fulfillment_request_step
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_trail_append_only();
CREATE TRIGGER fulfillment_request_timeout_append_only BEFORE UPDATE OR DELETE
  ON fulfillment_request_timeout
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_trail_append_only();
CREATE TRIGGER fulfillment_request_event_append_only BEFORE UPDATE OR DELETE
  ON fulfillment_request_event
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_trail_append_only();
-- 当前状态表：只挡 DELETE，UPDATE 留给各自的窄函数（REVOKE 在第 11 节）。
CREATE TRIGGER fulfillment_request_reservation_no_delete BEFORE DELETE
  ON fulfillment_request_reservation
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_no_delete();
CREATE TRIGGER fulfillment_request_outbound_order_no_delete BEFORE DELETE
  ON fulfillment_request_outbound_order
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_no_delete();
CREATE TRIGGER fulfillment_request_work_order_no_delete BEFORE DELETE
  ON fulfillment_request_work_order
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_no_delete();
CREATE TRIGGER fulfillment_request_no_delete BEFORE DELETE
  ON fulfillment_request
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_request_no_delete();

-- ---------------------------------------------------------------------------
-- 10. 四条 SECURITY DEFINER 窄函数：状态推进的唯一合法路径 + 乐观并发
-- ---------------------------------------------------------------------------
-- 刻意不做「整表可改」：那样 8.1 的状态机与 process_version 单调性就只剩注释了。
-- 四条函数**全部**要求 p_expected_version 与当前值一致 —— 乐观并发做在 DB 层，
-- 不靠 Java 里的 if（并发下两个操作都读到同一个 version，只在 Java 里比谁都以为自己是第一个）。
--
-- ⚠ deadline 一律取 greatest(clock_timestamp()+阈值, 原值+1 微秒)：
--   now() 在一个事务里是冻结的，同一事务连推两步且阈值相同时 deadline 会完全相等，
--   而 8.1 的不变式要求它不得回退。clock_timestamp() 取真实时钟，
--   +1µs 的下限保证「无论如何都不回退」。

-- 10.1 正常推进
CREATE FUNCTION advance_fulfillment_request_status(
  p_request_id uuid, p_status varchar, p_current_step varchar,
  p_expected_version bigint, p_actor uuid, p_step_timeout_seconds int)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record;
BEGIN
 SELECT id, tenant_id, status, current_step, process_version, deadline_at
   INTO head FROM fulfillment_request WHERE id = p_request_id;
 -- ⚠ SECURITY DEFINER 的函数体以**属主**身份执行，属主是超级用户 / 表属主，
 --   RLS 对它不生效。所以这里**必须**自己判租户：只靠函数外层的 RLS 是不够的 ——
--   否则「本租户已认证的用户拿着别家租户的 request_id」就能推进别人的流程。
 --   本仓已有的 advance_warehouse_allocation_status 缺这一层；本模块补上，
 --   并把判据统一成 head.tenant_id 与事务内 app.current_tenant 必须一致。
 IF head IS NULL
    OR head.tenant_id IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
  RAISE EXCEPTION '履约请求 % 不存在或不属于本租户', p_request_id USING ERRCODE='23503';
 END IF;
 IF head.status IN ('COMPLETED','CANCELLED') THEN
  RAISE EXCEPTION '履约请求当前状态「%」是终态，不允许推进到「%」', head.status, p_status
   USING ERRCODE='23514';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version <> head.process_version THEN
  RAISE EXCEPTION '履约请求 % 版本冲突：期望 %, 当前 %。请重新读取后再推进（同一流程被并发推进过）',
    head.id, p_expected_version, head.process_version USING ERRCODE='40001',
    HINT='expected_version 是蓝图输入里点名的字段，不要省';
 END IF;
 IF p_status IS NULL OR p_status NOT IN (
    'RESERVING','RESERVED','OUTBOUND_PENDING','OUTBOUND_CONFIRMED','WORKING','COMPLETED') THEN
  RAISE EXCEPTION '状态只能推进到 8.1 白名单里的状态，收到「%」', p_status USING ERRCODE='23514',
    HINT='挂起请走 block_fulfillment_request（要同时落 timeout 凭据与原因）';
 END IF;
 IF p_current_step NOT IN ('RESERVE','OUTBOUND','WORK','TRACK') THEN
  RAISE EXCEPTION 'current_step 必须是 RESERVE / OUTBOUND / WORK / TRACK，收到「%」', p_current_step
   USING ERRCODE='23514';
 END IF;
 IF p_status = 'COMPLETED' AND p_current_step <> 'TRACK' THEN
  RAISE EXCEPTION '跟踪完成必须站在阶梯顶端（current_step=TRACK），收到「%」', p_current_step
   USING ERRCODE='23514';
 END IF;
 IF p_step_timeout_seconds IS NULL OR p_step_timeout_seconds <= 0 THEN
  RAISE EXCEPTION '每一步的超时阈值必须为正秒数，收到 %', p_step_timeout_seconds USING ERRCODE='23514';
 END IF;
 UPDATE fulfillment_request
    SET status = p_status,
        current_step = p_current_step,
        process_version = process_version + 1,
        last_step_at = clock_timestamp(),
        deadline_at = greatest(
          clock_timestamp() + make_interval(secs => p_step_timeout_seconds),
          deadline_at + interval '1 microsecond'),
        completed_at = CASE WHEN p_status = 'COMPLETED' THEN clock_timestamp() ELSE completed_at END,
        blocked_at = NULL,
        blocked_reason_code = '',
        blocked_reason = '',
        updated_at = clock_timestamp()
  WHERE id = p_request_id
    AND process_version = p_expected_version;
 IF NOT FOUND THEN
  RAISE EXCEPTION '履约请求 % 版本冲突：推进时 process_version 已被别人改过（期望 %）',
    p_request_id, p_expected_version USING ERRCODE='40001';
 END IF;
 RETURN p_expected_version + 1;
END $$;

-- 10.2 挂起（超时 / 步骤失败 / 回执陈旧）
--     与 advance 分开是因为挂起**必须**同时落一条 timeout 凭据，
--     两件事在一个事务里做，DB 侧 8.3 再反查「没有凭据的 BLOCKED 提交不了」。
CREATE FUNCTION block_fulfillment_request(
  p_request_id uuid, p_step_kind varchar, p_detection_kind varchar,
  p_reason_code varchar, p_reason varchar, p_expected_version bigint,
  p_actor uuid, p_timeout_id uuid)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record; due timestamptz; th int; overdue numeric(12,3);
BEGIN
 SELECT id, tenant_id, status, current_step, process_version, attempt, deadline_at,
        reserve_timeout_seconds, outbound_timeout_seconds, work_timeout_seconds, track_timeout_seconds
   INTO head FROM fulfillment_request WHERE id = p_request_id;
 -- SECURITY DEFINER 绕过 RLS，租户判据必须自己写（见 advance_... 里的同一段说明）。
 IF head IS NULL
    OR head.tenant_id IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
  RAISE EXCEPTION '履约请求 % 不存在或不属于本租户', p_request_id USING ERRCODE='23503';
 END IF;
 IF head.status IN ('COMPLETED','CANCELLED') THEN
  RAISE EXCEPTION '履约请求当前状态「%」是终态，不允许挂起', head.status USING ERRCODE='23514';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version <> head.process_version THEN
  RAISE EXCEPTION '履约请求 % 版本冲突：期望 %, 当前 %。请重新读取后再挂起',
    head.id, p_expected_version, head.process_version USING ERRCODE='40001';
 END IF;
 IF p_step_kind <> head.current_step THEN
  RAISE EXCEPTION
    '挂起只能记在当前步「%」上，收到「%」：否则复盘会说流程卡在了它其实已经做完的那一步',
    head.current_step, p_step_kind USING ERRCODE='23514';
 END IF;
 IF p_detection_kind NOT IN ('TIMEOUT','STEP_FAILED','STALE_ACK') THEN
  RAISE EXCEPTION '检出方式必须是 TIMEOUT / STEP_FAILED / STALE_ACK，收到「%」', p_detection_kind
   USING ERRCODE='23514';
 END IF;
 IF p_reason_code NOT IN (
    'RESERVE_TIMEOUT','RESERVE_REJECTED','OUTBOUND_TIMEOUT','OUTBOUND_REJECTED',
    'WORK_TIMEOUT','TRACK_TIMEOUT','STALE_ACK','UPSTREAM_MISMATCH') THEN
  RAISE EXCEPTION '挂起原因码不在词表内，收到「%」', p_reason_code USING ERRCODE='23514';
 END IF;
 IF btrim(COALESCE(p_reason,''))='' THEN
  RAISE EXCEPTION '挂起必须写明原因：超时不是失败，说清卡在哪才能决定重试还是释放预占'
   USING ERRCODE='23514';
 END IF;
 th := CASE head.current_step
   WHEN 'RESERVE'  THEN head.reserve_timeout_seconds
   WHEN 'OUTBOUND' THEN head.outbound_timeout_seconds
   WHEN 'WORK'     THEN head.work_timeout_seconds
   ELSE head.track_timeout_seconds END;
 due := head.deadline_at;
 -- TIMEOUT 必须是**真的**超了：阈值未到就报超时，是把「还没等到」说成「卡住了」，
 -- 9.3.3.2 说的「先查证再决定」就没有依据了。
 IF p_detection_kind = 'TIMEOUT' AND clock_timestamp() < due THEN
  RAISE EXCEPTION '履约请求 % 的当前步尚未超时（截止 %，现在 %）：阈值没到就报超时，'
    '9.3.3.2 说的「先查证再决定」就没有依据了',
    head.id, due, clock_timestamp() USING ERRCODE='23514';
 END IF;
 overdue := CASE WHEN clock_timestamp() > due
                 THEN EXTRACT(EPOCH FROM (clock_timestamp() - due)) ELSE 0 END;
 UPDATE fulfillment_request
    SET status = 'BLOCKED', process_version = process_version + 1,
        blocked_at = clock_timestamp(), blocked_reason_code = p_reason_code,
        blocked_reason = p_reason, updated_at = clock_timestamp()
  WHERE id = p_request_id AND process_version = p_expected_version;
 IF NOT FOUND THEN
  RAISE EXCEPTION '履约请求 % 版本冲突：挂起时 process_version 已被别人改过（期望 %）',
    p_request_id, p_expected_version USING ERRCODE='40001';
 END IF;
 INSERT INTO fulfillment_request_timeout
  (id, tenant_id, request_id, step_kind, attempt, detection_kind, due_at, detected_at,
   overdue_seconds, threshold_seconds, reason_code, reason, blocked_at, detected_by_membership_id)
 VALUES (p_timeout_id, head.tenant_id, head.id, head.current_step, head.attempt,
   p_detection_kind, due, clock_timestamp(), round(overdue, 3), th, p_reason_code, p_reason,
   clock_timestamp(), p_actor);
 RETURN p_expected_version + 1;
END $$;

-- 10.3 重试 / 断点恢复：从 current_step 原地重来。
--     attempt+1（新一次尝试的凭据）、deadline 顺延、process_version+1。
--     「先查证再决定重试」的责任在调用方（人工或 9.3.3.2）；本函数只保证
--     重试永远是**显式**动作、带版本校验、且从断点而不是从头开始。
CREATE FUNCTION retry_fulfillment_request(
  p_request_id uuid, p_expected_version bigint, p_actor uuid, p_resume_reason varchar)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record; th int; target varchar(24);
BEGIN
 SELECT id, tenant_id, status, current_step, process_version, attempt,
        reserve_timeout_seconds, outbound_timeout_seconds, work_timeout_seconds, track_timeout_seconds
   INTO head FROM fulfillment_request WHERE id = p_request_id;
 -- SECURITY DEFINER 绕过 RLS，租户判据必须自己写（见 advance_... 里的同一段说明）。
 IF head IS NULL
    OR head.tenant_id IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
  RAISE EXCEPTION '履约请求 % 不存在或不属于本租户', p_request_id USING ERRCODE='23503';
 END IF;
 IF head.status <> 'BLOCKED' THEN
  RAISE EXCEPTION '只有挂起的履约请求才需要重试，当前状态是「%」：没卡住就不叫重试',
    head.status USING ERRCODE='23514';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version <> head.process_version THEN
  RAISE EXCEPTION '履约请求 % 版本冲突：期望 %, 当前 %。请重新读取后再重试',
    head.id, p_expected_version, head.process_version USING ERRCODE='40001';
 END IF;
 IF btrim(COALESCE(p_resume_reason,''))='' THEN
  RAISE EXCEPTION '重试必须写明为什么重试：蓝图说「超时不是失败」，没有理由的重试会变成重复发货'
   USING ERRCODE='23514';
 END IF;
 th := CASE head.current_step
   WHEN 'RESERVE'  THEN head.reserve_timeout_seconds
   WHEN 'OUTBOUND' THEN head.outbound_timeout_seconds
   WHEN 'WORK'     THEN head.work_timeout_seconds
   ELSE head.track_timeout_seconds END;
 -- 断点恢复：落回 current_step 对应的「进行中」状态，而不是从头 DRAFT 再说。
 target := CASE head.current_step
   WHEN 'RESERVE'  THEN 'RESERVING'
   WHEN 'OUTBOUND' THEN 'OUTBOUND_PENDING'
   ELSE 'WORKING' END;
 UPDATE fulfillment_request
    SET status = target, attempt = attempt + 1, process_version = process_version + 1,
        blocked_at = NULL, blocked_reason_code = '', blocked_reason = '',
        last_step_at = clock_timestamp(),
        deadline_at = greatest(
          clock_timestamp() + make_interval(secs => th),
          deadline_at + interval '1 microsecond'),
        notes = left(notes || E'\n[重试] ' || p_resume_reason, 400),
        updated_at = clock_timestamp()
  WHERE id = p_request_id AND process_version = p_expected_version;
 IF NOT FOUND THEN
  RAISE EXCEPTION '履约请求 % 版本冲突：重试时 process_version 已被别人改过（期望 %）',
    p_request_id, p_expected_version USING ERRCODE='40001';
 END IF;
 RETURN p_expected_version + 1;
END $$;

-- 10.4 取消：终态，且必须带原因。
CREATE FUNCTION cancel_fulfillment_request(
  p_request_id uuid, p_reason varchar, p_expected_version bigint, p_actor uuid)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record;
BEGIN
 SELECT id, tenant_id, status, process_version INTO head FROM fulfillment_request WHERE id = p_request_id;
 IF head IS NULL
    OR head.tenant_id IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
  RAISE EXCEPTION '履约请求 % 不存在或不属于本租户', p_request_id USING ERRCODE='23503';
 END IF;
 IF head.status IN ('COMPLETED','CANCELLED') THEN
  RAISE EXCEPTION '履约请求当前状态「%」是终态，不允许取消', head.status USING ERRCODE='23514';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version <> head.process_version THEN
  RAISE EXCEPTION '履约请求 % 版本冲突：期望 %, 当前 %。请重新读取后再取消',
    head.id, p_expected_version, head.process_version USING ERRCODE='40001';
 END IF;
 IF btrim(COALESCE(p_reason,''))='' THEN
  RAISE EXCEPTION '取消履约流程必须写明原因：取消等于把货停在半路，不写理由等于查不出谁停的'
   USING ERRCODE='23514';
 END IF;
 UPDATE fulfillment_request
    SET status = 'CANCELLED', process_version = process_version + 1,
        cancelled_at = clock_timestamp(), cancel_reason = p_reason,
        blocked_at = NULL, blocked_reason_code = '', blocked_reason = '',
        updated_at = clock_timestamp()
  WHERE id = p_request_id AND process_version = p_expected_version;
 IF NOT FOUND THEN
  RAISE EXCEPTION '履约请求 % 版本冲突：取消时 process_version 已被别人改过（期望 %）',
    p_request_id, p_expected_version USING ERRCODE='40001';
 END IF;
 RETURN p_expected_version + 1;
END $$;

-- 10.5 登记 7.2 的预占回执（REQUESTED → LOCKED / REJECTED / RELEASED）
--     乐观并发在这里是**状态上的 CAS**：只有 REQUESTED 能被改成终态，
--     同一回执重放两次第二次会被挡，不会把「已拒」翻成「已锁」。
CREATE FUNCTION ack_fulfillment_reservation(
  p_reservation_id uuid, p_status varchar, p_reservation_ref varchar,
  p_locked_qty numeric, p_reject_reason varchar)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE row_state varchar(16); row_tenant uuid;
BEGIN
 SELECT tenant_id, status INTO row_tenant, row_state
   FROM fulfillment_request_reservation WHERE id = p_reservation_id;
 -- SECURITY DEFINER 绕过 RLS，租户判据必须自己写（见 advance_... 里的同一段说明）。
 IF row_state IS NULL
    OR row_tenant IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
  RAISE EXCEPTION '预占指令 % 不存在或不属于本租户', p_reservation_id USING ERRCODE='23503';
 END IF;
 IF row_state <> 'REQUESTED' THEN
  RAISE EXCEPTION '预占指令 % 当前状态是「%」，只有待回执（REQUESTED）才能登记下游结果',
    p_reservation_id, row_state USING ERRCODE='23514',
    HINT='同一回执重放会被这里挡掉，不会把已拒翻成已锁';
 END IF;
 IF p_status NOT IN ('LOCKED','REJECTED','RELEASED') THEN
  RAISE EXCEPTION '预占回执只能是 LOCKED / REJECTED / RELEASED，收到「%」', p_status USING ERRCODE='23514';
 END IF;
 IF p_status = 'LOCKED'
    AND (COALESCE(btrim(p_reservation_ref),'')='' OR p_locked_qty IS NULL OR p_locked_qty <= 0) THEN
  RAISE EXCEPTION '登记「已锁定预占」必须给下游凭据与正数锁定量：没回执就不许说货锁住了'
   USING ERRCODE='23514';
 END IF;
 IF p_status = 'REJECTED' AND COALESCE(btrim(p_reject_reason),'')='' THEN
  RAISE EXCEPTION '登记「预占被拒」必须写明原因：不知道为什么被拒，下游就无法补货或改路径'
   USING ERRCODE='23514';
 END IF;
 UPDATE fulfillment_request_reservation
    SET status = p_status,
        reservation_ref = CASE WHEN p_status = 'LOCKED' THEN p_reservation_ref ELSE reservation_ref END,
        locked_qty = CASE WHEN p_status = 'LOCKED' THEN p_locked_qty ELSE locked_qty END,
        reject_reason = CASE WHEN p_status IN ('REJECTED','RELEASED')
                             THEN p_reject_reason ELSE reject_reason END,
        acknowledged_at = CASE WHEN p_status IN ('LOCKED','REJECTED')
                               THEN clock_timestamp() ELSE acknowledged_at END,
        released_at = CASE WHEN p_status = 'RELEASED' THEN clock_timestamp() ELSE released_at END
  WHERE id = p_reservation_id AND status = 'REQUESTED';
 IF NOT FOUND THEN
  RAISE EXCEPTION '预占指令 % 状态已被改动（期望 REQUESTED），回执登记失败', p_reservation_id
   USING ERRCODE='40001';
 END IF;
END $$;

-- 10.6 登记 8.3.1 的出库单回执（REQUESTED → CONFIRMED / REJECTED / CANCELLED）
CREATE FUNCTION ack_fulfillment_outbound_order(
  p_outbound_order_id uuid, p_status varchar, p_outbound_ref varchar,
  p_package_ref varchar, p_reject_reason varchar)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE row_state varchar(16); row_tenant uuid;
BEGIN
 SELECT tenant_id, status INTO row_tenant, row_state
   FROM fulfillment_request_outbound_order WHERE id = p_outbound_order_id;
 -- SECURITY DEFINER 绕过 RLS，租户判据必须自己写（见 advance_... 里的同一段说明）。
 IF row_state IS NULL
    OR row_tenant IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
  RAISE EXCEPTION '出库单指令 % 不存在或不属于本租户', p_outbound_order_id USING ERRCODE='23503';
 END IF;
 IF row_state <> 'REQUESTED' THEN
  RAISE EXCEPTION '出库单指令 % 当前状态是「%」，只有待回执（REQUESTED）才能登记下游结果',
    p_outbound_order_id, row_state USING ERRCODE='23514';
 END IF;
 IF p_status NOT IN ('CONFIRMED','REJECTED','CANCELLED') THEN
  RAISE EXCEPTION '出库单回执只能是 CONFIRMED / REJECTED / CANCELLED，收到「%」', p_status
   USING ERRCODE='23514';
 END IF;
 IF p_status = 'CONFIRMED' AND COALESCE(btrim(p_outbound_ref),'')='' THEN
  RAISE EXCEPTION '登记「出库单已确认」必须给出 8.3.1 开的单号：没有单号的「已出库」无法交接、无法回传'
   USING ERRCODE='23514';
 END IF;
 IF p_status IN ('REJECTED','CANCELLED') AND COALESCE(btrim(p_reject_reason),'')='' THEN
  RAISE EXCEPTION '出库单被拒 / 取消必须写明原因：查不出为什么没出库，下一次还会再拒一次'
   USING ERRCODE='23514';
 END IF;
 UPDATE fulfillment_request_outbound_order
    SET status = p_status,
        outbound_ref = CASE WHEN p_status = 'CONFIRMED' THEN p_outbound_ref ELSE outbound_ref END,
        package_ref = CASE WHEN COALESCE(btrim(p_package_ref),'')='' THEN package_ref
                           ELSE p_package_ref END,
        reject_reason = CASE WHEN p_status IN ('REJECTED','CANCELLED')
                             THEN p_reject_reason ELSE reject_reason END,
        acknowledged_at = CASE WHEN p_status IN ('CONFIRMED','REJECTED')
                               THEN clock_timestamp() ELSE acknowledged_at END,
        cancelled_at = CASE WHEN p_status = 'CANCELLED' THEN clock_timestamp() ELSE cancelled_at END
  WHERE id = p_outbound_order_id AND status = 'REQUESTED';
 IF NOT FOUND THEN
  RAISE EXCEPTION '出库单指令 % 状态已被改动（期望 REQUESTED），回执登记失败', p_outbound_order_id
   USING ERRCODE='40001';
 END IF;
END $$;

-- 10.7 登记作业状态（ASSIGNED → RUNNING → DONE / FAILED / SKIPPED）
--     同样用状态上的 CAS：一条作业不会被两个作业系统同时标成 DONE。
CREATE FUNCTION complete_fulfillment_work_order(
  p_work_order_id uuid, p_status varchar, p_assignee_ref varchar, p_failure_reason varchar)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE row_state varchar(16); row_tenant uuid;
BEGIN
 SELECT tenant_id, status INTO row_tenant, row_state
   FROM fulfillment_request_work_order WHERE id = p_work_order_id;
 -- SECURITY DEFINER 绕过 RLS，租户判据必须自己写（见 advance_... 里的同一段说明）。
 IF row_state IS NULL
    OR row_tenant IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
  RAISE EXCEPTION '编排作业 % 不存在或不属于本租户', p_work_order_id USING ERRCODE='23503';
 END IF;
 IF row_state IN ('DONE','FAILED','SKIPPED') THEN
  RAISE EXCEPTION '编排作业 % 已经是终态「%」，不允许再改', p_work_order_id, row_state USING ERRCODE='23514';
 END IF;
 IF p_status NOT IN ('RUNNING','DONE','FAILED','SKIPPED') THEN
  RAISE EXCEPTION '作业状态只能是 RUNNING / DONE / FAILED / SKIPPED，收到「%」', p_status USING ERRCODE='23514';
 END IF;
 -- ASSIGNED → RUNNING → DONE / FAILED / SKIPPED 是唯一合法的路径。
 -- 只挡「ASSIGNED 直接跳到 DONE」这一种：少了 RUNNING 就答不出
 -- 「这活是什么时候开始干的」，9.5 的出库时效分析也就没有依据。
 -- （注意这里**不能**写成 p_status IN ('RUNNING','DONE')：那会把合法的
 --   ASSIGNED → RUNNING 一起挡掉，第一次写出来时正是这个错。）
 IF row_state = 'ASSIGNED' AND p_status = 'DONE' THEN
  RAISE EXCEPTION '作业 % 还停在「已派工」，必须先登记 RUNNING 才能置为「已完成」',
    p_work_order_id USING ERRCODE='23514';
 END IF;
 IF p_status = 'FAILED' AND COALESCE(btrim(p_failure_reason),'')='' THEN
  RAISE EXCEPTION '作业失败必须写明原因：9.3.3.2 的处置入口要靠它决定换人还是改路径'
   USING ERRCODE='23514';
 END IF;
 UPDATE fulfillment_request_work_order
    SET status = p_status,
        assignee_ref = CASE WHEN COALESCE(btrim(p_assignee_ref),'')='' THEN assignee_ref
                            ELSE p_assignee_ref END,
        failure_reason = CASE WHEN p_status = 'FAILED' THEN p_failure_reason ELSE failure_reason END,
        started_at = COALESCE(started_at,
          CASE WHEN p_status IN ('RUNNING','DONE','FAILED') THEN clock_timestamp() END),
        finished_at = CASE WHEN p_status IN ('DONE','FAILED','SKIPPED')
                           THEN clock_timestamp() ELSE finished_at END
  WHERE id = p_work_order_id AND status = row_state;
 IF NOT FOUND THEN
  RAISE EXCEPTION '编排作业 % 状态已被改动（期望「%」），登记失败', p_work_order_id, row_state
   USING ERRCODE='40001';
 END IF;
END $$;

-- 10.8 把一步流程从 RUNNING 置为 COMPLETED（_step 表只授权 INSERT，所以必须有窄函数）
--     为什么 step 表严格 append-only 却还要「把一行改掉」：
--       append-only 保证的是「**每一次尝试**都留痕」——重试会开 attempt+1 的新行；
--       同一 attempt 内 RUNNING → COMPLETED 是**同一次尝试的完成**，不是新事实。
--       真正的不可改写性落在「不能把 COMPLETED 改成别的、不能删掉 COMPLETED」上，
--       由本函数的终态守卫 + 第 11 节收权共同保证。
CREATE FUNCTION complete_fulfillment_step(
  p_step_id uuid, p_reservation_id uuid, p_outbound_order_id uuid,
  p_work_order_count int, p_elapsed_seconds numeric)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE row_tenant uuid; row_status varchar(16); row_started timestamptz;
BEGIN
 SELECT tenant_id, status, started_at INTO row_tenant, row_status, row_started
   FROM fulfillment_request_step WHERE id = p_step_id;
 -- SECURITY DEFINER 绕过 RLS，租户判据必须自己写（见 advance_... 里的同一段说明）。
 IF row_status IS NULL
    OR row_tenant IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
  RAISE EXCEPTION '流程步骤记录 % 不存在或不属于本租户', p_step_id USING ERRCODE='23503';
 END IF;
 IF row_status <> 'RUNNING' THEN
  RAISE EXCEPTION '流程步骤记录 % 当前状态是「%」，只有执行中（RUNNING）才能标记完成',
    p_step_id, row_status USING ERRCODE='23514';
 END IF;
 IF p_elapsed_seconds IS NULL OR p_elapsed_seconds < 0 THEN
  RAISE EXCEPTION '耗时必须为非负数，收到 %', p_elapsed_seconds USING ERRCODE='23514';
 END IF;
 -- 置标记后 UPDATE：本函数是 SECURITY DEFINER，但触发器照样对所有 UPDATE 生效，
 -- 所以由这个函数自己开窗，且窗口只活在当前事务内。
 PERFORM set_config('app.fulfillment_step_complete', 'on', true);
 UPDATE fulfillment_request_step
    SET status = 'COMPLETED',
        reservation_id = COALESCE(p_reservation_id, reservation_id),
        outbound_order_id = COALESCE(p_outbound_order_id, outbound_order_id),
        work_order_count = p_work_order_count,
        finished_at = clock_timestamp(),
        elapsed_seconds = p_elapsed_seconds
  WHERE id = p_step_id AND status = 'RUNNING';
 IF NOT FOUND THEN
  RAISE EXCEPTION '流程步骤记录 % 状态已被改动（期望 RUNNING），标记完成失败', p_step_id
   USING ERRCODE='40001';
 END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 11. 多租户：RLS + 授权（与 trade V1 第 2 节、V116 第 10 节同一套写法）
-- ---------------------------------------------------------------------------
-- 七张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内
-- 设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'fulfillment_request',
    'fulfillment_request_step',
    'fulfillment_request_reservation',
    'fulfillment_request_outbound_order',
    'fulfillment_request_work_order',
    'fulfillment_request_timeout',
    'fulfillment_request_event'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_request,
  fulfillment_request_step,
  fulfillment_request_reservation,
  fulfillment_request_outbound_order,
  fulfillment_request_work_order,
  fulfillment_request_timeout,
  fulfillment_request_event TO trade_app;
-- 授权在前、收回在后：把 REVOKE 写在 GRANT **之前**会被上面这条 GRANT 全量覆盖回去，
-- append-only 于是只剩注释（V92 第 9 节、V116 第 10 节都踩过同一个坑）。
--
-- 分两批收回，语义不同：
--   * 留痕三表（_step / _timeout / _event）：UPDATE、DELETE 全收 —— 只允许追加。
--   * 当前状态四表（头行 / _reservation / _outbound_order / _work_order）：
--     UPDATE 与 DELETE **都收**，推进只经第 10 节的 SECURITY DEFINER 窄函数
--     （函数属主是迁移用户，UPDATE 权照常有；运行角色只能 EXECUTE）。
--     收到 UPDATE 而不是 DELETE 是一开始就查出来的真问题：只收 DELETE 时
--     直接 UPDATE 会先撞上 8.1 的触发器而被挡，但那是**副作用**不是保证 ——
--     一条顺手把 warehouse_ref / requested_qty 改掉、version 也照样递增的 UPDATE
--     就能通过全部校验，把「当时按哪个仓判的」这层快照改没。必须收权。
REVOKE UPDATE,DELETE ON
  fulfillment_request_step,
  fulfillment_request_timeout,
  fulfillment_request_event FROM trade_app;
REVOKE UPDATE,DELETE ON
  fulfillment_request,
  fulfillment_request_reservation,
  fulfillment_request_outbound_order,
  fulfillment_request_work_order FROM trade_app;
-- 状态推进的七个窄函数是 SECURITY DEFINER，必须逐个显式授权。
GRANT EXECUTE ON FUNCTION advance_fulfillment_request_status(uuid,varchar,varchar,bigint,uuid,int) TO trade_app;
GRANT EXECUTE ON FUNCTION block_fulfillment_request(uuid,varchar,varchar,varchar,varchar,bigint,uuid,uuid) TO trade_app;
GRANT EXECUTE ON FUNCTION retry_fulfillment_request(uuid,bigint,uuid,varchar) TO trade_app;
GRANT EXECUTE ON FUNCTION cancel_fulfillment_request(uuid,varchar,bigint,uuid) TO trade_app;
GRANT EXECUTE ON FUNCTION ack_fulfillment_reservation(uuid,varchar,varchar,numeric,varchar) TO trade_app;
GRANT EXECUTE ON FUNCTION ack_fulfillment_outbound_order(uuid,varchar,varchar,varchar,varchar) TO trade_app;
GRANT EXECUTE ON FUNCTION complete_fulfillment_work_order(uuid,varchar,varchar,varchar) TO trade_app;
GRANT EXECUTE ON FUNCTION complete_fulfillment_step(uuid,uuid,uuid,int,numeric) TO trade_app;
-- 本迁移**零 ALTER** 任何既有表（含 V92 / V116 / V144 的表与 V1 的 trade_tenancy_probe），
-- 被本迁移约束到的既有模块 = 无，跨车道 DDL 回归面 = 0。
