-- 9.3.3.2 履约流程监控。
--
-- 蓝图 09-order-and-fulfillment.md 9.3.3.2 原文（实查第 199-204 行）：
--   职责：监控履约流程的异常与超时：预占失败、库存不足、流程卡在某步、超时未发货，
--         提供重试、切换路径、人工介入入口。
--   输入：履约流程状态；超时阈值配置。
--   输出：异常流程清单 + 处置操作 + 处置记录。
--   关系：上游 = 9.3.3.1；下游 = 1 工作台待办、7.2.1.3 释放。
--         **超时不是失败**：流程卡住时先查证再决定重试或释放预占，否则会出现"重试造成重复发货"。
--
-- 归属：**trade 服务**（蓝图 09 卷「trade 服务拥有订单与售后单据」＋ L2 表
-- 「9.3 履约编排 → 主责服务 trade」）。
--
-- ===========================================================================
-- 零、与上游 9.3.3.1 的关系：**读它的流程状态，不另起一套状态机**
-- ===========================================================================
-- 实查集成头 a052a025：V152__trade_fulfillment_request.sql 已交付，七张表
-- （fulfillment_request 头行 + _reservation / _outbound_order / _work_order /
-- _step / _timeout / _event）与其 Java 侧 FulfillmentRequestService 全部在位。
-- 蓝图加粗句「流程状态必须持久化」已经由 V152 落地，**流程状态就是
-- fulfillment_request 头行的 status + current_step + process_version 三件套**。
--
-- ⇒ 所以本迁移**零 ALTER、零 UPDATE 任何既有表**，只：
--   * 读 fulfillment_request / _step / _reservation 检出异常（**同服务同库**，
--     这是合法的读，且 V152 的 REVOKE 本来就不许我写它们）；
--   * 对 (tenant_id, request_id) 建复合外键，把异常案例**挂**在真实履约流程上；
--   * 把判定证据**快照**进案例行（rule_evidence / threshold_seconds /
--     overdue_seconds），这样 9.3.3.1 事后改了流程状态或阈值，
--     历史案例也不会被重新解释成「当时就是按新规则算的」（照 V126 第 4 节同一纪律）。
--
-- ⚠ 刻意**不**把「异常」再造一个状态机：异常是**对流程状态的一次判定**，
--   不是流程的第 N 个状态。流程状态只有 V152 头行那一处；本模块的 case.status
--   走的是「未处置 → 查证中 → 已查证 → 已处置」这条**处置**轴，两条轴正交。
--
-- ===========================================================================
-- 一、本模块的加粗句是硬约束，不是提示语
-- ===========================================================================
-- 蓝图：「**超时不是失败**：流程卡住时先查证再决定重试或释放预占，
--   否则会出现"重试造成重复发货"。」
--
-- 这句话在应用层是"请先查证"，在 DB 层必须是三道闸门，缺一道就等于没约束：
--
--   闸门 1（**查证前置**）：
--     disposition 里任何 action_kind='RETRY' 的行，都必须引用一条
--     conclusion='VERIFIED' 的 verification，且该 verification 的 case_id
--     与 disposition 的 case_id **必须是同一个案例**。
--     → 由 fulfill_monitor_disposition_retried() 触发器强制；
--       「A 案例查过了就去重试 B 案例」这种张冠李戴过不去。
--
--   闸门 2（**并发互斥**）：
--     同一 (tenant_id, request_id, attempt) 只允许存在**一条未取消的 RETRY 处置**，
--     由部分唯一索引保证。两个并发重试请求 → 一个成功、一个撞 23505。
--     刻意不做成「内存里的 in-flight 集合」：进程重启即失效，
--     而重复发货是**钱和货**的事，必须由 DB 兜住。
--
--   闸门 3（**乐观并发**）：
--     案例行有 monitor_version，唯一的发起函数以 CAS 方式
--     `WHERE id=? AND monitor_version=? AND status='VERIFIED'` 推进。
--     并发下两个处置都读到 version=7，只在 Java 里 if 一下的话谁都觉得自己是第一个。
--
-- 另外：**超时本身不改变流程状态**。本模块检出超时只落一条 case，
-- 是否把流程推回 RUNNING / 是否重试，由处置动作决定并经 V152 的窄函数发起。
-- 本迁移**不代 9.3.3.1 改状态**（UPDATE 权不在我这儿，语义上也不该我改）。
--
-- ===========================================================================
-- 二、两个下游都未开发：照实报出，不假装
-- ===========================================================================
--   * **1 工作台待办**：卷 1 未交付，独立服务。本模块的"人工介入入口"
--     落成**租户可维护的待办项表** fulfillment_monitor_todo：
--     带 source_kind ∈ ( TENANT_MAINTAINED / MODULE_1_WORKBENCH_TODO )
--     与 captured_at（采集时刻，不是登记时刻）。工作台接上后由同步器按同一形状写入。
--   * **7.2.1.3 释放预占**：在 **warehouse 服务独立库**。AGENTS.md 模块边界纪律
--     禁止跨服务 SQL / FK / 共享视图 ⇒ 本模块**不写 warehouse 库存、
--     不声称货已释放**。释放动作落成**释放指令登记** fulfillment_monitor_release：
--     缺省 status='REQUESTED'（指令已登记，待 7.2 回执），收到回执才变 'RELEASED'。
--     这与 V152 的 reservation 缺省 REQUESTED 是同一条纪律，两边都守着
--     「没有下游回执就不算锁定 / 不算释放」这条线。
--
-- ⚠ 因此 capabilities 里 reservationReleaseDispatched=false、
--   workbenchTodoPushed=false 显式报出，**不让调用方从 404 或空数组里猜哪一环没通**。
--
-- ===========================================================================
-- 三、超时判定必须用数据库里的真实时间
-- ===========================================================================
-- 全部时间列 timestamptz。检出 SQL 用 **clock_timestamp()**（不是 now()：
-- 一个事务里 now() 是冻结的，扫描多次会把上次结果当成这次的）计算
--   overdue_seconds = extract(epoch from (clock_timestamp() - deadline_at))
-- 绝不在 Java 里用启动时刻缓存或用系统时钟反推 —— 时钟偏了，监控就会
-- 把 8 小时后的超时当成已经超时，或者反过来把已超时的当成正常。
--
-- 阈值来自 fulfillment_monitor_threshold（租户可维护表）：解析顺序
--   仓库覆盖 > 租户默认(warehouse_ref IS NULL) > 内置兜底，
--   与 8.5.3.1 出库时效（V148）的三档解析同一纪律，并且**每个被解析出的
--   阈值都带 source**（TENANT_OVERRIDE / TENANT_DEFAULT / BUILTIN）——
--   界面上必须答得出「这个 2 小时是谁定的」。
--
-- ⚠ V152 头行上已经存了 reserve/outbound/work/track_timeout_seconds（建单时的快照）。
--   本表是**监控口径**的租户可配置值：解析时以本表为准（监控要能调阈值，
--   但不允许回头改一张已经建好的流程单的历史快照），并把这个口径差别
--   在 case.threshold_source 里带出来，不让两处阈值悄悄打架。
--
-- ===========================================================================
-- 四、金额与数量
-- ===========================================================================
-- 数量 numeric(19,6)、金额 numeric(19,4)、耗时秒 numeric(12,3)，全程 BigDecimal 禁浮点。
-- 本模块**不做计价**（计价在 7.2 / 10 财务），但切换路径可能要带成本差估值，
-- 仍是 BigDecimal + currency_code 成对出现（只给金额不给币种，下游必然按错币种入账）。
--
-- ===========================================================================
-- 五、建表顺序：按外键依赖
-- ---------------------------------------------------------------------------
--   阈值 → 案例 → 查证记录 / 处置 / 释放登记 / 待办项 → 事件
-- 案例引用 V152 的 fulfillment_request（既有表，先于本迁移存在）。
-- 处置同时引用案例与查证记录，所以查证表排在处置之前。
--
-- ---------------------------------------------------------------------------
-- 1. fulfillment_monitor_threshold · 租户可维护的超时阈值配置
-- ---------------------------------------------------------------------------
-- UNIQUE NULLS NOT DISTINCT：让租户默认档（warehouse_ref IS NULL）也在 DB 层唯一，
-- 否则同一个默认档能插两行，解析出哪一行只取决于扫描顺序。
CREATE TABLE fulfillment_monitor_threshold (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 按仓覆盖；NULL = 租户默认档。
  warehouse_ref varchar(64),
  step_kind varchar(16) NOT NULL CHECK(step_kind IN ('RESERVE','OUTBOUND','WORK','TRACK')),
  threshold_seconds int NOT NULL CHECK(threshold_seconds>0),
  -- 这条阈值是谁定的：建它的租户成员。事后能答得出「谁把 RESERVE 调到 5 分钟的」。
  created_by_membership_id uuid NOT NULL,
  notes varchar(400) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE NULLS NOT DISTINCT(tenant_id,warehouse_ref,step_kind),
  -- 仓覆盖档必须给一个真实的仓号，不许用空串冒充 NULL（空串≠NULL，会变成第二条默认档）。
  CHECK(warehouse_ref IS NULL OR btrim(warehouse_ref)<>''),
  CHECK(updated_at>=created_at)
);
CREATE INDEX fulfillment_monitor_threshold_step
  ON fulfillment_monitor_threshold(tenant_id,step_kind);

-- ---------------------------------------------------------------------------
-- 2. fulfillment_monitor_case · 异常流程清单（蓝图输出第一项）
-- ---------------------------------------------------------------------------
-- anomaly_kind 覆盖蓝图点名的四类异常：
--   RESERVATION_FAILED  预占失败（V152 的 reservation 被拒 / 失败凭据）
--   INSUFFICIENT_STOCK  库存不足（V152 的 step.failure_code 就有这个词）
--   STEP_STUCK          流程卡在某步（超过当前步 deadline 仍没推进）
--   SHIPMENT_TIMEOUT    超时未发货（已过 track 阈值仍未完成）
-- 前两类**不是**靠 deadline 判的，是靠真实失败凭据判的 —— 把「库存不足」
-- 和「卡住」混成一种异常，处置动作就会选错（缺货该补货或换仓，卡住该查步骤）。
CREATE TABLE fulfillment_monitor_case (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 异常单号（DB 发号，审计与工单系统直接引用）。
  case_no varchar(32) NOT NULL,
  -- 挂在真实履约流程上：这条异常**必须**有流程，否则答不出「哪个订单卡住了」。
  request_id uuid NOT NULL,
  -- 决策时刻的快照（V152 事后可能改这些列，JOIN 出来的是当前值而不是当时值）。
  request_code varchar(64) NOT NULL,
  order_ref varchar(64) NOT NULL,
  sku_ref varchar(64) NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  step_kind varchar(16) NOT NULL CHECK(step_kind IN ('RESERVE','OUTBOUND','WORK','TRACK')),
  attempt int NOT NULL CHECK(attempt>=1),
  anomaly_kind varchar(24) NOT NULL CHECK(anomaly_kind IN (
    'RESERVATION_FAILED','INSUFFICIENT_STOCK','STEP_STUCK','SHIPMENT_TIMEOUT')),
  -- INFO 只提示 / WARNING 要人看 / CRITICAL 必须立刻处置（已过深 / 预占失败）。
  severity varchar(8) NOT NULL CHECK(severity IN ('INFO','WARNING','CRITICAL')),
  -- ==== 判定证据（照 V126 第 4 节：事后改策略不会让历史异常看起来按新规则算）====
  -- 阈值与阈值来源；overdue_seconds 是**检出当时**用数据库时钟算出的真实差值。
  threshold_seconds int NOT NULL CHECK(threshold_seconds>0),
  threshold_source varchar(24) NOT NULL CHECK(threshold_source IN (
    'TENANT_OVERRIDE','TENANT_DEFAULT','BUILTIN','SNAPSHOT_FROM_REQUEST')),
  overdue_seconds numeric(12,3) NOT NULL CHECK(overdue_seconds>=0),
  -- 算式原文（如「已超 RESERVE 阈值 1823.4 秒，阈值 1800 秒（租户默认）」），
  -- 界面上直接展示这行就能复现判定，不需要再点进去看。
  rule_evidence varchar(400) NOT NULL,
  detected_at timestamptz NOT NULL DEFAULT now(),
  detected_by_membership_id uuid,
  -- ==== 处置轴（与流程状态正交）====
  -- OPEN → INVESTIGATING（有人接手查）→ VERIFIED（查证通过，可以处置）
  --        → DISPOSED（处置完成）/ DISMISSED（证伪，不是异常）
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN (
    'OPEN','INVESTIGATING','VERIFIED','DISPOSED','DISMISSED')),
  -- 乐观并发：唯一的发起函数以它做 CAS。
  monitor_version bigint NOT NULL DEFAULT 0 CHECK(monitor_version>=0),
  investigating_at timestamptz,
  investigating_by_membership_id uuid,
  verified_at timestamptz,
  verified_by_membership_id uuid,
  resolved_at timestamptz,
  resolved_by_membership_id uuid,
  resolution_note varchar(400) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,case_no),
  -- 同一次尝试同一种异常只检出一条：反复跑 scan 不会刷屏。
  -- 证伪（DISMISSED）必须能再次检出 —— 一个被证伪的延迟在下一个周期重新
  -- 逾期时是一条**新事实**，所以这条唯一性靠部分唯一索引表达（见下）。
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  CHECK(btrim(request_code)<>''),
  CHECK(btrim(order_ref)<>''),
  CHECK(btrim(sku_ref)<>''),
  CHECK(btrim(warehouse_ref)<>''),
  CHECK(btrim(rule_evidence)<>''),
  -- 查证通过必须有人有时刻；进入查证中同理。
  CHECK(status NOT IN ('VERIFIED','DISPOSED')
        OR (verified_at IS NOT NULL AND verified_by_membership_id IS NOT NULL)),
  CHECK(status<>'INVESTIGATING'
        OR (investigating_at IS NOT NULL AND investigating_by_membership_id IS NOT NULL)),
  -- 已处置 / 已证伪必须写明怎么结的（处置带人，证伪带理由）。
  CHECK(status<>'DISPOSED'
        OR (resolved_at IS NOT NULL AND resolved_by_membership_id IS NOT NULL)),
  CHECK(status<>'DISMISSED' OR btrim(resolution_note)<>''),
  -- 人工查证过的案例不允许再退回未查证：否则"先查证"就可以被一个 UPDATE 抹掉。
  CHECK(status NOT IN ('OPEN','INVESTIGATING')
        OR (verified_at IS NULL AND resolved_at IS NULL)),
  CHECK(updated_at>=created_at)
);
-- 同一 (request, step, attempt, kind) 在**未结案**状态下唯一 → 反复跑 scan 不刷屏。
CREATE UNIQUE INDEX fulfillment_monitor_case_active_uq
  ON fulfillment_monitor_case(tenant_id,request_id,step_kind,attempt,anomaly_kind)
  WHERE status NOT IN ('DISPOSED','DISMISSED');
-- 监控看板的主查询路径：按严重度 + 待办时长排。
CREATE INDEX fulfillment_monitor_case_board
  ON fulfillment_monitor_case(tenant_id,severity,detected_at DESC)
  WHERE status IN ('OPEN','INVESTIGATING','VERIFIED');
CREATE INDEX fulfillment_monitor_case_request
  ON fulfillment_monitor_case(tenant_id,request_id,detected_at DESC);
CREATE INDEX fulfillment_monitor_case_verified_pending
  ON fulfillment_monitor_case(tenant_id,verified_at)
  WHERE status='VERIFIED';

-- ---------------------------------------------------------------------------
-- 3. fulfillment_monitor_verification · 查证记录（加粗句的凭据）
-- ---------------------------------------------------------------------------
-- 蓝图：「流程卡住时**先查证**再决定重试或释放预占」。
-- 「查证」不是点一下按钮，是一个有内容、有结论、有时刻的动作：
-- 查了什么（finding）、看了哪些下游凭据（evidence）、结论是什么。
-- 没有这张表，第 4.1 节的闸门 1 无从校验，而"先查证"就只是一句提示语。
CREATE TABLE fulfillment_monitor_verification (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  verification_no varchar(64) NOT NULL,
  -- 查证结论：CONFIRMED 坐实了异常 / NOT_CONFIRMED 证伪（流程其实已经好了）。
  -- 只有 CONFIRMED 才解锁重试。
  conclusion varchar(16) NOT NULL CHECK(conclusion IN ('CONFIRMED','NOT_CONFIRMED')),
  -- 查证对象：这张异常到底是「下游真的没动」还是「下游动了但回执没回来」。
  -- 这个字段就是防重复发货的**判断依据**本身：查了 downstream 侧确认没动，
  -- 才敢重试；只查了本地就说重试，就是重复发货的起点。
  verified_scope varchar(24) NOT NULL CHECK(verified_scope IN (
    'DOWNSTREAM_ACK','LOCAL_STATE_ONLY','WAREHOUSE_STOCK','CARRIER_TRACE')),
  -- 下游凭据引用：本次查证看到的东西（预占回执号 / 库存快照号 / 轨迹号）。
  evidence_ref varchar(128) NOT NULL DEFAULT '',
  -- 查证结论的正文：必须写「看到了什么」，不能只写「已查证」。
  finding text NOT NULL CHECK(btrim(finding)<>''),
  verified_at timestamptz NOT NULL,
  verified_by_membership_id uuid NOT NULL,
  -- 幂等键：网络重发不会造出两条"我查过了"（同键重放返回既有查证，见第 9.1 节）。
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,verification_no),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,case_id) REFERENCES fulfillment_monitor_case(tenant_id,id),
  -- 证伪必须点名查证范围与依据：否则下次同类会被一并放过。
  CHECK(conclusion<>'NOT_CONFIRMED' OR btrim(evidence_ref)<>''),
  -- 查证**可以**是过去发生的（人先查完、三分钟后才录入），禁的是显著未来。
  -- 上限判据与第 9.1 节窄函数里的 `p_verified_at > clock_timestamp()+1min` 一致，
  -- 不在这里再设一个更严的「相对 created_at 的下界」，那会把合法录入判成非法。
  CHECK(verified_at <= created_at + interval '1 minute')
);
-- 闸门 1 的查询路径：某个案例有没有 CONFIRMED 的查证。
CREATE INDEX fulfillment_monitor_verification_case
  ON fulfillment_monitor_verification(tenant_id,case_id,verified_at DESC);
CREATE UNIQUE INDEX fulfillment_monitor_verification_confirmed_uq
  ON fulfillment_monitor_verification(tenant_id,case_id)
  WHERE conclusion='CONFIRMED';

-- ---------------------------------------------------------------------------
-- 4. fulfillment_monitor_disposition · 处置操作 + 处置记录（蓝图输出第二、三项）
-- ---------------------------------------------------------------------------
-- 蓝图点名的四个入口全部在此：重试 / 切换路径 / 人工介入 /（释放预占见第 5 节登记）。
-- status 走 PENDING → SENT → COMPLETED / CANCELLED；
-- 本服务只**登记**动作与回执，不假装下游已经执行（见文件头第二节）。
CREATE TABLE fulfillment_monitor_disposition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  disposition_no varchar(32) NOT NULL,
  case_id uuid NOT NULL,
  request_id uuid NOT NULL,
  -- 发起处置时该流程的第几次尝试（案例行 attempt 的快照）。
  -- 刻意冗余存一份而不是只挂 case_id：闸门 2 的部分唯一索引要在这张表上**直接**表达
  -- 「同一流程同一次尝试只允许一条未取消的重试」，跨表 JOIN 做不了索引。
  attempt int NOT NULL CHECK(attempt>=1),
  action_kind varchar(24) NOT NULL CHECK(action_kind IN (
    'RETRY','SWITCH_PATH','MANUAL_INTERVENTION','RELEASE_RESERVATION','DISMISS')),
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN (
    'PENDING','SENT','ACKNOWLEDGED','COMPLETED','CANCELLED','FAILED')),
  -- ==== 闸门 1 的载体：RETRY 必须指一条 CONFIRMED 的查证 ====
  verification_id uuid,
  -- 重试时带的新截止时刻偏移秒数：V152 的 retry_fulfillment_request 会顺延 deadline，
  -- 本列只是把「打算给多长」记录下来，两边都能对账。
  retry_grant_seconds int CHECK(retry_grant_seconds IS NULL OR retry_grant_seconds>0),
  -- 切换路径的目标（9.3.2.2 的 routing_decision_ref 是**逻辑引用**，不跨模块 JOIN）。
  target_route_ref varchar(64) NOT NULL DEFAULT '',
  target_warehouse_ref varchar(64) NOT NULL DEFAULT '',
  -- 切换路径的成本差估值（金额必须带币种，否则下游按错币种入账）。
  estimated_cost_delta numeric(19,4),
  estimated_cost_currency_code varchar(3) CHECK(
    estimated_cost_currency_code IS NULL OR estimated_cost_currency_code ~ '^[A-Z]{3}$'),
  note text NOT NULL CHECK(btrim(note)<>''),
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  acknowledged_at timestamptz,
  completed_at timestamptz,
  cancelled_at timestamptz,
  -- 下游回执的原文（如 7.2 回的「已释放 RSV-xxx」），供事后对账。
  outcome varchar(400) NOT NULL DEFAULT '',
  last_error varchar(400) NOT NULL DEFAULT '',
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,disposition_no),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,case_id) REFERENCES fulfillment_monitor_case(tenant_id,id),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  FOREIGN KEY(tenant_id,verification_id)
    REFERENCES fulfillment_monitor_verification(tenant_id,id),
  CHECK((estimated_cost_delta IS NULL) = (estimated_cost_currency_code IS NULL)),
  CHECK(action_kind<>'SWITCH_PATH' OR btrim(target_route_ref)<>''),
  CHECK(action_kind<>'RETRY' OR retry_grant_seconds IS NOT NULL),
  -- **加粗句的落点（结构侧）**：RETRY 必须带 verification_id。
  -- 「内容侧」由 4.1 节的触发器强制（那条指到的查证必须 CONFIRMED 且属于同一案例）。
  CHECK(action_kind<>'RETRY' OR verification_id IS NOT NULL),
  CHECK(status NOT IN ('SENT','ACKNOWLEDGED','COMPLETED') OR sent_at IS NOT NULL),
  CHECK(status NOT IN ('ACKNOWLEDGED','COMPLETED') OR acknowledged_at IS NOT NULL),
  CHECK(status<>'COMPLETED' OR (completed_at IS NOT NULL AND btrim(outcome)<>'')),
  CHECK(status<>'CANCELLED' OR cancelled_at IS NOT NULL),
  CHECK(status<>'FAILED' OR btrim(last_error)<>''),
  CHECK(updated_at>=created_at)
);
-- **闸门 2：同一流程同一次尝试只允许一条未取消的 RETRY。**
-- 刻意做在 DB 而不是"服务层判一下 in-flight"：进程重启即失效，
-- 而重复发货是钱和货的事。两处并发重试 → 一个成功，另一个撞 23505。
CREATE UNIQUE INDEX fulfillment_monitor_retry_once_uq
  ON fulfillment_monitor_disposition(tenant_id,request_id,attempt)
  WHERE action_kind='RETRY' AND status<>'CANCELLED';
-- 一个案例同一时刻只能有一个未终结的处置：反复点"重试"不产生第二条活。
CREATE UNIQUE INDEX fulfillment_monitor_disposition_open_uq
  ON fulfillment_monitor_disposition(tenant_id,case_id)
  WHERE status IN ('PENDING','SENT','ACKNOWLEDGED');
CREATE INDEX fulfillment_monitor_disposition_case
  ON fulfillment_monitor_disposition(tenant_id,case_id,created_at DESC);
CREATE INDEX fulfillment_monitor_disposition_board
  ON fulfillment_monitor_disposition(tenant_id,status,created_at DESC);

-- ---------------------------------------------------------------------------
-- 5. fulfillment_monitor_release · 释放预占指令（下游 7.2.1.3，**未交付**）
-- ---------------------------------------------------------------------------
-- 缺省 REQUESTED：指令已登记，待 7.2 回执。**没有回执就不算释放** ——
-- 与 V152 的 reservation 缺省 REQUESTED 是同一条纪律。
-- 绝不跨库写 warehouse：释放的是 warehouse 的库存，本表只是本服务内的登记。
CREATE TABLE fulfillment_monitor_release (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  release_no varchar(32) NOT NULL,
  case_id uuid NOT NULL,
  request_id uuid NOT NULL,
  disposition_id uuid NOT NULL,
  -- 释放对象是 V152 登记的那条预占指令（同服务，可建外键）。
  reservation_id uuid NOT NULL,
  -- 登记来源：TENANT_MAINTAINED = 租户按 V150 既有解法自行登记；
  -- MODULE_7_2_1_3 = 正式接口到位后由同步器按同一形状写入。
  source_kind varchar(24) NOT NULL CHECK(source_kind IN (
    'TENANT_MAINTAINED','MODULE_7_2_1_3_RELEASE')),
  -- 采集时刻，不是登记时刻：登记时刻会把「十分钟前释放的」说成「刚刚释放的」。
  captured_at timestamptz NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'REQUESTED' CHECK(status IN (
    'REQUESTED','RELEASED','REJECTED')),
  -- 下游回执凭据（RELEASED 时才有）。
  release_ref varchar(64) NOT NULL DEFAULT '',
  released_qty numeric(19,6),
  reject_reason varchar(400) NOT NULL DEFAULT '',
  requested_at timestamptz NOT NULL DEFAULT now(),
  acknowledged_at timestamptz,
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,release_no),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,case_id) REFERENCES fulfillment_monitor_case(tenant_id,id),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  FOREIGN KEY(tenant_id,disposition_id)
    REFERENCES fulfillment_monitor_disposition(tenant_id,id),
  FOREIGN KEY(tenant_id,reservation_id)
    REFERENCES fulfillment_request_reservation(tenant_id,id),
  -- 已释放必须带回执凭据、正数释放量与回执时刻：没有回执就不许说货已释放。
  CHECK(status<>'RELEASED' OR (btrim(release_ref)<>'' AND released_qty IS NOT NULL
     AND released_qty>0 AND acknowledged_at IS NOT NULL)),
  CHECK(status<>'REJECTED' OR btrim(reject_reason)<>''),
  CHECK(status='REQUESTED' OR acknowledged_at IS NOT NULL),
  CHECK(released_qty IS NULL OR released_qty>0),
  CHECK(acknowledged_at IS NULL OR acknowledged_at>=requested_at)
);
CREATE INDEX fulfillment_monitor_release_case
  ON fulfillment_monitor_release(tenant_id,case_id,requested_at DESC);
-- 一次释放登记只对应一条预占指令：同键重放不会让两边各释放一批。
-- 被拒（REJECTED）不算 —— 释放被拒后可以重新登记一条新的释放指令。
CREATE UNIQUE INDEX fulfillment_monitor_release_reservation_uq
  ON fulfillment_monitor_release(tenant_id,reservation_id)
  WHERE status<>'REJECTED';
CREATE INDEX fulfillment_monitor_release_pending
  ON fulfillment_monitor_release(tenant_id,requested_at)
  WHERE status='REQUESTED';

-- ---------------------------------------------------------------------------
-- 6. fulfillment_monitor_todo · 人工介入入口（下游 1 工作台待办，**未交付**）
-- ---------------------------------------------------------------------------
-- 租户可维护的待办项表（照 V150 order_risk_signal 的既有解法）：
-- source_kind + captured_at，正式对接后由同步器按同一形状写入 MODULE_1_WORKBENCH_TODO，
-- **表结构与逻辑都不用改**。
CREATE TABLE fulfillment_monitor_todo (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  todo_no varchar(64) NOT NULL,
  case_id uuid NOT NULL,
  request_id uuid NOT NULL,
  -- 待办要人工做什么（照蓝图「人工介入入口」）。
  action_kind varchar(24) NOT NULL CHECK(action_kind IN (
    'REVIEW_CASE','CONFIRM_DOWNSTREAM','ESCALATE','CONTACT_SUPPLIER','OTHER')),
  -- 紧急度直接继承案例严重度，不另设一套词表（两套词表迟早打架）。
  severity varchar(8) NOT NULL CHECK(severity IN ('INFO','WARNING','CRITICAL')),
  title varchar(200) NOT NULL CHECK(btrim(title)<>''),
  detail varchar(600) NOT NULL DEFAULT '',
  source_kind varchar(32) NOT NULL CHECK(source_kind IN (
    'TENANT_MAINTAINED','MODULE_1_WORKBENCH_TODO')),
  captured_at timestamptz NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN (
    'OPEN','CLAIMED','DONE','CANCELLED')),
  claimed_by_membership_id uuid,
  claimed_at timestamptz,
  done_at timestamptz,
  done_note varchar(400) NOT NULL DEFAULT '',
  -- 谁登记的这条待办：工作台接上之前，这是本服务内唯一的责任人凭据。
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,todo_no),
  UNIQUE(tenant_id,case_id,action_kind),
  FOREIGN KEY(tenant_id,case_id) REFERENCES fulfillment_monitor_case(tenant_id,id),
  FOREIGN KEY(tenant_id,request_id) REFERENCES fulfillment_request(tenant_id,id),
  CHECK(status<>'CLAIMED' OR (claimed_at IS NOT NULL AND claimed_by_membership_id IS NOT NULL)),
  CHECK(status<>'DONE' OR done_at IS NOT NULL)
);
CREATE INDEX fulfillment_monitor_todo_board
  ON fulfillment_monitor_todo(tenant_id,severity,captured_at DESC)
  WHERE status IN ('OPEN','CLAIMED');

-- ---------------------------------------------------------------------------
-- 7. fulfillment_monitor_event · 事件流（append-only）
-- ---------------------------------------------------------------------------
-- 与 V152 的 _event 同构：两段状态同时变化的那一瞬只记一段就答不出复盘问题。
CREATE TABLE fulfillment_monitor_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  event_kind varchar(24) NOT NULL CHECK(event_kind IN (
    'DETECTED','INVESTIGATING','VERIFIED','NOT_CONFIRMED','DISPOSITION_CREATED',
    'DISPOSITION_SENT','DISPOSITION_ACKNOWLEDGED','DISPOSITION_COMPLETED',
    'DISPOSITION_FAILED','DISPOSITION_CANCELLED','RELEASE_REGISTERED','RELEASE_ACK',
    'TODO_CREATED','TODO_CLAIMED','TODO_DONE','TODO_CANCELLED','THRESHOLD_CHANGED')),
  -- from/to 既可能是案例状态（最长 12）也可能是动作名（RELEASE_RESERVATION = 19），
  -- 所以给到 24 而不是按案例状态的长度给 16。
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  -- 事件版本与案例的 monitor_version 对齐，供下游**有序**消费。
  monitor_version bigint NOT NULL CHECK(monitor_version>=0),
  detail text NOT NULL DEFAULT '',
  actor_id uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,case_id) REFERENCES fulfillment_monitor_case(tenant_id,id),
  CHECK(btrim(detail)<>'' OR event_kind NOT IN ('VERIFIED','NOT_CONFIRMED'))
);
CREATE INDEX fulfillment_monitor_event_feed
  ON fulfillment_monitor_event(tenant_id,case_id,monitor_version DESC);
CREATE INDEX fulfillment_monitor_event_consumers
  ON fulfillment_monitor_event(tenant_id,event_kind,occurred_at DESC);

-- ===========================================================================
-- 8. DB 级不变量
-- ===========================================================================
-- 8.1 **加粗句闸门 1**：RETRY 必须指向一条 CONFIRMED 且属于**同一案例**的查证。
--     刻意做成 BEFORE INSERT 触发器而不是服务层 if：
--     「先查证再重试」要拦的是并发与绕过，不是界面上的一个按钮。
CREATE FUNCTION guard_fulfillment_monitor_retry_verified() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_conclusion varchar(16); v_case uuid; v_at timestamptz;
BEGIN
 IF NEW.action_kind <> 'RETRY' THEN RETURN NEW; END IF;
 SELECT conclusion, case_id, verified_at INTO v_conclusion, v_case, v_at
   FROM fulfillment_monitor_verification
  WHERE id = NEW.verification_id AND tenant_id = NEW.tenant_id;
 IF v_conclusion IS NULL THEN
  RAISE EXCEPTION '处置 % 引用的查证记录 % 不存在或不属于本租户', NEW.disposition_no,
    NEW.verification_id USING ERRCODE='23503';
 END IF;
 IF v_case <> NEW.case_id THEN
  RAISE EXCEPTION '处置 % 的查证记录属于案例 %，与本处置的案例 % 不是同一个：拿 A 案例的查证结论去重试 B 案例，等于没查证',
    NEW.disposition_no, v_case, NEW.case_id USING ERRCODE='23514',
    HINT='查证必须针对本案例本身重新做一次';
 END IF;
 IF v_conclusion <> 'CONFIRMED' THEN
  RAISE EXCEPTION '查证结论是「%」，不允许发起重试。蓝图说「超时不是失败，先查证再决定重试或释放预占，否则会出现重试造成重复发货」：只有查证坐实（CONFIRMED）才允许重试，证伪的应走 DISMISS',
    v_conclusion USING ERRCODE='23514';
 END IF;
 IF v_at > clock_timestamp() + interval '1 minute' THEN
  RAISE EXCEPTION '查证记录 % 的查证时刻在未来（%），这不是查证', NEW.verification_id, v_at
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fulfillment_monitor_disposition_retried
  BEFORE INSERT ON fulfillment_monitor_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_retry_verified();

-- 8.2 案例状态机：只允许沿处置轴前进，且终态不许复活。
CREATE FUNCTION guard_fulfillment_monitor_case_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'UPDATE' AND NEW.status <> OLD.status THEN
  IF NOT (
       (OLD.status = 'OPEN'           AND NEW.status IN ('INVESTIGATING','VERIFIED','DISMISSED'))
    OR (OLD.status = 'INVESTIGATING'  AND NEW.status IN ('VERIFIED','DISMISSED'))
    OR (OLD.status = 'VERIFIED'       AND NEW.status IN ('DISPOSED','DISMISSED'))
  ) THEN
   RAISE EXCEPTION '异常案例状态不能从「%」改到「%」：查证与处置是单向流程，倒退回去等于让"已查证"凭空消失', OLD.status, NEW.status USING ERRCODE='23514',
     HINT='合法转移见 V154 第 8.2 节；结案要新建一条案例，不是把老案例改回未处理';
  END IF;
 END IF;
 IF TG_OP = 'UPDATE' AND OLD.status IN ('DISPOSED','DISMISSED') THEN
  RAISE EXCEPTION '异常案例当前状态「%」是终态，不允许再改', OLD.status USING ERRCODE='23514';
 END IF;
 IF TG_OP = 'UPDATE' AND NEW.monitor_version <= OLD.monitor_version THEN
  RAISE EXCEPTION 'monitor_version 必须严格递增（% → %）：并发控制不能靠"顺手改一下"绕过',
    OLD.monitor_version, NEW.monitor_version USING ERRCODE='23514';
 END IF;
 -- 已查证的案例不许再退回未查证（查证痕迹一旦存在就必须留痕）。
 IF TG_OP = 'UPDATE' AND OLD.status IN ('VERIFIED','DISPOSED')
    AND (NEW.verified_at IS NULL OR NEW.verified_by_membership_id IS NULL) THEN
  RAISE EXCEPTION '案例 % 已经查证过，不允许清掉查证痕迹："先查证再重试"的凭据必须一直留存', OLD.id USING ERRCODE='23514';
 END IF;
 -- 进入 VERIFIED 必须真的有一条 CONFIRMED 查证支撑（与 8.1 互为正反面）。
 IF TG_OP = 'UPDATE' AND NEW.status = 'VERIFIED'
    AND NOT EXISTS (
      SELECT 1 FROM fulfillment_monitor_verification v
       WHERE v.tenant_id = NEW.tenant_id AND v.case_id = NEW.id
         AND v.conclusion = 'CONFIRMED') THEN
  RAISE EXCEPTION '案例 % 标记为已查证，但没有任何一条 CONFIRMED 的查证记录：状态不能说有凭据', NEW.id USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fulfillment_monitor_case_state BEFORE INSERT OR UPDATE
  ON fulfillment_monitor_case
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_case_state();

-- 8.3 **加粗句闸门 3**：案例的当前状态必须与它最新的处置一致（回表读当前行）。
--     DISPOSED ⇒ 至少一条非 CANCELLED 的处置行。反过来「点了处置却案例还 OPEN」
--     会让看板显示"待处理"，而活已经发出去了。
CREATE FUNCTION guard_fulfillment_monitor_case_disposition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status = 'DISPOSED'
    AND NOT EXISTS (
      SELECT 1 FROM fulfillment_monitor_disposition d
       WHERE d.tenant_id = NEW.tenant_id AND d.case_id = NEW.id
         AND d.status <> 'CANCELLED') THEN
  RAISE EXCEPTION '案例 % 标记为已处置，但没有任何一条有效处置记录："已处置"必须有处置动作支撑', NEW.id USING ERRCODE='23514';
 END IF;
 IF NEW.status = 'DISMISSED'
    AND EXISTS (
      SELECT 1 FROM fulfillment_monitor_disposition d
       WHERE d.tenant_id = NEW.tenant_id AND d.case_id = NEW.id
         AND d.status <> 'CANCELLED') THEN
  RAISE EXCEPTION '案例 % 被证伪，却已经有处置动作发出：已发出去的动作不能靠改案例状态当作没发生', NEW.id USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fulfillment_monitor_case_disposition
  AFTER INSERT OR UPDATE ON fulfillment_monitor_case
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_case_disposition();

-- 8.4 处置必须挂在**已查证**的案例上（闸门 1 的案例侧）。
CREATE FUNCTION guard_fulfillment_monitor_disposition_case_ready() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c_status varchar(16);
BEGIN
 SELECT status INTO c_status FROM fulfillment_monitor_case
  WHERE id = NEW.case_id AND tenant_id = NEW.tenant_id;
 IF c_status IS NULL THEN
  RAISE EXCEPTION '异常案例 % 不存在或不属于本租户', NEW.case_id USING ERRCODE='23503';
 END IF;
 IF c_status NOT IN ('VERIFIED','DISPOSED') THEN
  RAISE EXCEPTION '案例 % 当前状态是「%」，还不能发起处置：处置前必须先查证（蓝图：超时不是失败，先查证再决定）', NEW.case_id, c_status
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fulfillment_monitor_disposition_case_ready
  BEFORE INSERT ON fulfillment_monitor_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_disposition_case_ready();

-- 8.5 写路径纪律：四张**当前状态**表只经窄函数，两张**留痕**表严格 append-only。
--     事件与查证记录是凭据，不许改；案例与处置只经 SECURITY DEFINER 窄函数推进。
CREATE FUNCTION guard_fulfillment_monitor_trail_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是留痕表，只允许追加（% 不允许）', TG_TABLE_NAME, TG_OP
  USING ERRCODE='23514',
  HINT='查证记录与事件是"先查证再重试"的举证凭据，改它们等于把凭据改没';
 RETURN NULL;
END $$;
CREATE FUNCTION guard_fulfillment_monitor_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 不允许删除', TG_TABLE_NAME USING ERRCODE='23514';
 RETURN NULL;
END $$;
CREATE TRIGGER fulfillment_monitor_verification_append_only BEFORE UPDATE OR DELETE
  ON fulfillment_monitor_verification
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_trail_append_only();
CREATE TRIGGER fulfillment_monitor_event_append_only BEFORE UPDATE OR DELETE
  ON fulfillment_monitor_event
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_trail_append_only();
CREATE TRIGGER fulfillment_monitor_case_no_delete BEFORE DELETE ON fulfillment_monitor_case
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_no_delete();
CREATE TRIGGER fulfillment_monitor_disposition_no_delete BEFORE DELETE ON fulfillment_monitor_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_no_delete();
CREATE TRIGGER fulfillment_monitor_release_no_delete BEFORE DELETE ON fulfillment_monitor_release
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_no_delete();
CREATE TRIGGER fulfillment_monitor_todo_no_delete BEFORE DELETE ON fulfillment_monitor_todo
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_no_delete();
CREATE TRIGGER fulfillment_monitor_threshold_no_delete BEFORE DELETE ON fulfillment_monitor_threshold
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_monitor_no_delete();

-- ===========================================================================
-- 9. 窄函数（SECURITY DEFINER）：唯一被授权的状态推进路径
-- ===========================================================================
-- 9.1 查证：登记一次查证并把案例推到 VERIFIED。
--     monitor_version 做 CAS —— 并发下两个查证都读到 version=0，
--     只在 Java 里 if 一下的话谁都觉得自己是第一个。
CREATE FUNCTION verify_fulfillment_monitor_case(
  p_case_id uuid, p_expected_version bigint, p_conclusion varchar, p_scope varchar,
  p_evidence_ref varchar, p_finding text, p_verified_at timestamptz,
  p_idempotency_key varchar, p_actor uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record; ver_id uuid; newver bigint;
BEGIN
 SELECT id, tenant_id, status, monitor_version, attempt
   INTO head FROM fulfillment_monitor_case WHERE id = p_case_id;
 -- SECURITY DEFINER 绕过 RLS，租户判据必须自己写（与 V152 同一段纪律）。
 IF head IS NULL
    OR head.tenant_id IS DISTINCT FROM nullif(current_setting('app.current_tenant',true),'')::uuid THEN
  RAISE EXCEPTION '异常案例 % 不存在或不属于本租户', p_case_id USING ERRCODE='23503';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version <> head.monitor_version THEN
  RAISE EXCEPTION '异常案例 % 版本冲突：期望 %, 当前 %。请重新读取后再查证',
    p_case_id, p_expected_version, head.monitor_version USING ERRCODE='40001';
 END IF;
 IF head.status NOT IN ('OPEN','INVESTIGATING','VERIFIED') THEN
  RAISE EXCEPTION '异常案例当前状态是「%」，已结案的不允许再查证', head.status
   USING ERRCODE='23514';
 END IF;
 IF p_conclusion NOT IN ('CONFIRMED','NOT_CONFIRMED') THEN
  RAISE EXCEPTION '查证结论只能是 CONFIRMED / NOT_CONFIRMED，收到「%」', p_conclusion
   USING ERRCODE='23514';
 END IF;
 IF p_scope NOT IN ('DOWNSTREAM_ACK','LOCAL_STATE_ONLY','WAREHOUSE_STOCK','CARRIER_TRACE') THEN
  RAISE EXCEPTION '查证范围「%」不在允许词表内', p_scope USING ERRCODE='23514';
 END IF;
 IF btrim(COALESCE(p_finding,''))='' THEN
  RAISE EXCEPTION '查证必须写明看到了什么：只写"已查证"等于没查证' USING ERRCODE='23514';
 END IF;
 -- 时间一致性：查证时刻不许比当前时间晚太多（防时钟写错把未来当成查过）。
 IF p_verified_at IS NULL OR p_verified_at > clock_timestamp() + interval '1 minute' THEN
  RAISE EXCEPTION '查证时刻「%」不合法：不能为空，也不能显著晚于服务器当前时间',
    p_verified_at USING ERRCODE='23514';
 END IF;
 -- 幂等：同键重放返回既有查证的版本，不报错也不重复落一条"我查过了"。
 SELECT id INTO ver_id FROM fulfillment_monitor_verification
  WHERE tenant_id = head.tenant_id AND idempotency_key = p_idempotency_key;
 IF ver_id IS NOT NULL THEN
  RETURN head.monitor_version;
 END IF;
 INSERT INTO fulfillment_monitor_verification(
   id, tenant_id, case_id, verification_no, conclusion, verified_scope, evidence_ref,
   finding, verified_at, verified_by_membership_id, idempotency_key)
 VALUES (
   gen_random_uuid(), head.tenant_id, head.id,
   'VER-' || replace(head.id::text,'-','') || '-' || left(p_idempotency_key, 8),
   p_conclusion, p_scope, COALESCE(p_evidence_ref,''), p_finding,
   p_verified_at, p_actor, p_idempotency_key);
 newver := head.monitor_version + 1;
 UPDATE fulfillment_monitor_case
    SET status = CASE WHEN p_conclusion = 'CONFIRMED' THEN 'VERIFIED' ELSE 'DISMISSED' END,
        monitor_version = newver,
        investigating_at = COALESCE(investigating_at, clock_timestamp()),
        investigating_by_membership_id = COALESCE(investigating_by_membership_id, p_actor),
        verified_at = p_verified_at, verified_by_membership_id = p_actor,
        resolved_at = CASE WHEN p_conclusion = 'CONFIRMED' THEN NULL ELSE p_verified_at END,
        resolved_by_membership_id = CASE WHEN p_conclusion = 'CONFIRMED' THEN NULL ELSE p_actor END,
        resolution_note = CASE WHEN p_conclusion = 'CONFIRMED' THEN resolution_note
                               ELSE left(p_finding, 400) END,
        updated_at = clock_timestamp()
  WHERE id = p_case_id AND monitor_version = p_expected_version
    AND status IN ('OPEN','INVESTIGATING','VERIFIED');
 IF NOT FOUND THEN
  RAISE EXCEPTION '异常案例 % 版本冲突：查证时 monitor_version 已被别人改过（期望 %）',
    p_case_id, p_expected_version USING ERRCODE='40001';
 END IF;
 RETURN newver;
END $$;

-- 9.2 结案：处置动作完成后把案例落到 DISPOSED（版本 CAS）。
--     刻意要求「至少一条 COMPLETED 的处置」才允许结案 —— 只发出去（SENT）不算处置完，
--     而把 SENT 当成 DISPOSED 会让看板显示「已处置」，货却还在半路。
CREATE FUNCTION close_fulfillment_monitor_case(
  p_case_id uuid, p_expected_version bigint, p_note varchar, p_actor uuid)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record;
BEGIN
 SELECT id, tenant_id, status, monitor_version INTO head
   FROM fulfillment_monitor_case WHERE id = p_case_id;
 IF head IS NULL
    OR head.tenant_id IS DISTINCT FROM nullif(current_setting('app.current_tenant',true),'')::uuid THEN
  RAISE EXCEPTION '异常案例 % 不存在或不属于本租户', p_case_id USING ERRCODE='23503';
 END IF;
 IF head.status <> 'VERIFIED' THEN
  RAISE EXCEPTION '异常案例当前状态是「%」，只有已查证（VERIFIED）的案例才允许结案',
    head.status USING ERRCODE='23514';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version <> head.monitor_version THEN
  RAISE EXCEPTION '异常案例 % 版本冲突：期望 %, 当前 %', p_case_id, p_expected_version,
    head.monitor_version USING ERRCODE='40001';
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM fulfillment_monitor_disposition d
    WHERE d.tenant_id = head.tenant_id AND d.case_id = head.id AND d.status = 'COMPLETED') THEN
  RAISE EXCEPTION '案例 % 没有任何一条已完成的处置：只发出（SENT）不算处置完，把 SENT 当成已处置会让看板说货已经好了',
    head.id USING ERRCODE='23514',
    HINT='先 POST /cases/{id}/disposition/{id}/status 把处置推进到 COMPLETED';
 END IF;
 UPDATE fulfillment_monitor_case
    SET status = 'DISPOSED', monitor_version = monitor_version + 1,
        resolved_at = clock_timestamp(), resolved_by_membership_id = p_actor,
        resolution_note = left(COALESCE(p_note,''), 400),
        updated_at = clock_timestamp()
  WHERE id = p_case_id AND monitor_version = p_expected_version AND status = 'VERIFIED';
 IF NOT FOUND THEN
  RAISE EXCEPTION '异常案例 % 版本冲突：结案时 monitor_version 已被别人改过（期望 %）',
    p_case_id, p_expected_version USING ERRCODE='40001';
 END IF;
 RETURN p_expected_version + 1;
END $$;

-- 9.3 推进处置状态（PENDING → SENT → ACKNOWLEDGED → COMPLETED / FAILED / CANCELLED）。
--     乐观并发在**状态上的 CAS**：只有当前状态匹配才推进，
--     同一回执重放两次第二次被挡，不会把 COMPLETED 翻成 SENT。
CREATE FUNCTION advance_fulfillment_monitor_disposition(
  p_disposition_id uuid, p_from_status varchar, p_to_status varchar,
  p_outcome varchar, p_last_error varchar) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE row_state varchar(16); row_tenant uuid;
BEGIN
 SELECT tenant_id, status INTO row_tenant, row_state
   FROM fulfillment_monitor_disposition WHERE id = p_disposition_id;
 IF row_state IS NULL
    OR row_tenant IS DISTINCT FROM nullif(current_setting('app.current_tenant',true),'')::uuid THEN
  RAISE EXCEPTION '处置 % 不存在或不属于本租户', p_disposition_id USING ERRCODE='23503';
 END IF;
 IF row_state <> p_from_status THEN
  RAISE EXCEPTION '处置 % 当前状态是「%」，不是期望的「%」，回执登记失败',
    p_disposition_id, row_state, p_from_status USING ERRCODE='40001';
 END IF;
 IF NOT (
      (p_from_status = 'PENDING'      AND p_to_status IN ('SENT','CANCELLED','FAILED'))
   OR (p_from_status = 'SENT'         AND p_to_status IN ('ACKNOWLEDGED','COMPLETED','FAILED','CANCELLED'))
   OR (p_from_status = 'ACKNOWLEDGED' AND p_to_status IN ('COMPLETED','FAILED','CANCELLED'))
 ) THEN
  RAISE EXCEPTION '处置状态不能从「%」改到「%」', p_from_status, p_to_status USING ERRCODE='23514';
 END IF;
 UPDATE fulfillment_monitor_disposition
    SET status = p_to_status,
        sent_at = CASE WHEN p_to_status IN ('SENT','ACKNOWLEDGED','COMPLETED') THEN clock_timestamp() ELSE sent_at END,
        acknowledged_at = CASE WHEN p_to_status IN ('ACKNOWLEDGED','COMPLETED') THEN clock_timestamp() ELSE acknowledged_at END,
        completed_at = CASE WHEN p_to_status = 'COMPLETED' THEN clock_timestamp() ELSE completed_at END,
        cancelled_at = CASE WHEN p_to_status = 'CANCELLED' THEN clock_timestamp() ELSE cancelled_at END,
        outcome = CASE WHEN btrim(COALESCE(p_outcome,''))<>'' THEN left(p_outcome,400) ELSE outcome END,
        last_error = CASE WHEN btrim(COALESCE(p_last_error,''))<>'' THEN left(p_last_error,400) ELSE last_error END,
        updated_at = clock_timestamp()
  WHERE id = p_disposition_id AND status = p_from_status;
 IF NOT FOUND THEN
  RAISE EXCEPTION '处置 % 状态已被改动（期望「%」），回执登记失败',
    p_disposition_id, p_from_status USING ERRCODE='40001';
 END IF;
END $$;

-- 9.4 登记 7.2.1.3 的释放回执（REQUESTED → RELEASED / REJECTED）。
CREATE FUNCTION ack_fulfillment_monitor_release(
  p_release_id uuid, p_status varchar, p_release_ref varchar,
  p_released_qty numeric, p_reject_reason varchar) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE row_state varchar(16); row_tenant uuid;
BEGIN
 SELECT tenant_id, status INTO row_tenant, row_state
   FROM fulfillment_monitor_release WHERE id = p_release_id;
 IF row_state IS NULL
    OR row_tenant IS DISTINCT FROM nullif(current_setting('app.current_tenant',true),'')::uuid THEN
  RAISE EXCEPTION '释放登记 % 不存在或不属于本租户', p_release_id USING ERRCODE='23503';
 END IF;
 IF row_state <> 'REQUESTED' THEN
  RAISE EXCEPTION '释放登记 % 当前状态是「%」，只有待回执（REQUESTED）才能登记下游结果',
    p_release_id, row_state USING ERRCODE='23514';
 END IF;
 IF p_status NOT IN ('RELEASED','REJECTED') THEN
  RAISE EXCEPTION '释放回执只能是 RELEASED / REJECTED，收到「%」', p_status USING ERRCODE='23514';
 END IF;
 IF p_status = 'RELEASED'
    AND (COALESCE(btrim(p_release_ref),'')='' OR p_released_qty IS NULL OR p_released_qty<=0) THEN
  RAISE EXCEPTION '登记「已释放」必须给下游凭据与正数释放量：7.2.1.3 未交付，没有回执就不许说货已释放' USING ERRCODE='23514';
 END IF;
 IF p_status = 'REJECTED' AND COALESCE(btrim(p_reject_reason),'')='' THEN
  RAISE EXCEPTION '登记「释放被拒」必须写明原因' USING ERRCODE='23514';
 END IF;
 UPDATE fulfillment_monitor_release
    SET status = p_status,
        release_ref = CASE WHEN p_status='RELEASED' THEN p_release_ref ELSE release_ref END,
        released_qty = CASE WHEN p_status='RELEASED' THEN p_released_qty ELSE released_qty END,
        reject_reason = CASE WHEN p_status='REJECTED' THEN p_reject_reason ELSE reject_reason END,
        acknowledged_at = clock_timestamp()
  WHERE id = p_release_id AND status = 'REQUESTED';
 IF NOT FOUND THEN
  RAISE EXCEPTION '释放登记 % 状态已被改动（期望 REQUESTED），回执登记失败', p_release_id
   USING ERRCODE='40001';
 END IF;
END $$;

-- 9.5 待办项认领 / 完成（1 工作台未交付，本服务内是权威待办）。
CREATE FUNCTION claim_fulfillment_monitor_todo(
  p_todo_id uuid, p_action varchar, p_note varchar, p_actor uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE row_state varchar(16); row_tenant uuid;
BEGIN
 SELECT tenant_id, status INTO row_tenant, row_state
   FROM fulfillment_monitor_todo WHERE id = p_todo_id;
 IF row_state IS NULL
    OR row_tenant IS DISTINCT FROM nullif(current_setting('app.current_tenant',true),'')::uuid THEN
  RAISE EXCEPTION '待办项 % 不存在或不属于本租户', p_todo_id USING ERRCODE='23503';
 END IF;
 IF p_action NOT IN ('CLAIM','DONE','CANCEL') THEN
  RAISE EXCEPTION '待办动作只能是 CLAIM / DONE / CANCEL，收到「%」', p_action USING ERRCODE='23514';
 END IF;
 IF p_action = 'CLAIM' AND row_state <> 'OPEN' THEN
  RAISE EXCEPTION '待办项 % 当前状态是「%」，只有待认领（OPEN）才能认领', p_todo_id, row_state
   USING ERRCODE='23514';
 END IF;
 IF p_action IN ('DONE','CANCEL') AND row_state NOT IN ('OPEN','CLAIMED') THEN
  RAISE EXCEPTION '待办项 % 当前状态是「%」，不允许结项', p_todo_id, row_state
   USING ERRCODE='23514';
 END IF;
 UPDATE fulfillment_monitor_todo
    SET status = CASE p_action WHEN 'CLAIM' THEN 'CLAIMED' WHEN 'DONE' THEN 'DONE' ELSE 'CANCELLED' END,
        claimed_by_membership_id = CASE WHEN p_action='CLAIM' THEN p_actor
                                        ELSE COALESCE(claimed_by_membership_id, p_actor) END,
        claimed_at = COALESCE(claimed_at, clock_timestamp()),
        done_at = CASE WHEN p_action IN ('DONE','CANCEL') THEN clock_timestamp() ELSE done_at END,
        done_note = CASE WHEN btrim(COALESCE(p_note,''))<>'' THEN left(p_note,400) ELSE done_note END
  WHERE id = p_todo_id;
END $$;

-- ===========================================================================
-- 10. 多租户：RLS + 授权（与 trade V1 第 2 节、V152 第 11 节同一套写法）
-- ===========================================================================
-- 七张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'fulfillment_monitor_threshold',
    'fulfillment_monitor_case',
    'fulfillment_monitor_verification',
    'fulfillment_monitor_disposition',
    'fulfillment_monitor_release',
    'fulfillment_monitor_todo',
    'fulfillment_monitor_event'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_monitor_threshold,
  fulfillment_monitor_case,
  fulfillment_monitor_verification,
  fulfillment_monitor_disposition,
  fulfillment_monitor_release,
  fulfillment_monitor_todo,
  fulfillment_monitor_event TO trade_app;
-- 授权在前、收回在后：REVOKE 写在 GRANT 之前会被上面这条全量覆盖回去
-- （V92 第 9 节、V116 第 10 节、V152 第 11 节都踩过同一个坑）。
--
-- 分两批收回，语义不同：
--   * 留痕两表（_verification / _event）：UPDATE、DELETE 全收 —— 只允许追加。
--   * 当前状态表（阈值 / 案例 / 处置 / 释放登记 / 待办）：
--     UPDATE 与 DELETE **都收**，状态推进只经第 9 节的 SECURITY DEFINER 窄函数。
--     尤其案例表：一条手工 UPDATE 能把 monitor_version 照样递增地改成 VERIFIED，
--     把"先查证"变成一句口头禅 —— 收权是唯一可靠的做法。
REVOKE UPDATE,DELETE ON
  fulfillment_monitor_verification,
  fulfillment_monitor_event FROM trade_app;
-- 阈值表**刻意保留 UPDATE**（照 V150 第 11 节 order_risk_rule 的同一处理）：
-- 它是租户可维护的**配置**，upsert（ON CONFLICT DO UPDATE）本来就需要 UPDATE 权；
-- 收回它等于把「改阈值」这条路堵死，只剩「删了重建」而那又违反 no_delete 触发器。
-- DELETE 仍然收回，理由同上。
REVOKE DELETE ON fulfillment_monitor_threshold FROM trade_app;
REVOKE UPDATE,DELETE ON
  fulfillment_monitor_case,
  fulfillment_monitor_disposition,
  fulfillment_monitor_release,
  fulfillment_monitor_todo FROM trade_app;
-- 状态推进的五个窄函数是 SECURITY DEFINER，必须逐个显式授权。
GRANT EXECUTE ON FUNCTION verify_fulfillment_monitor_case(uuid,bigint,varchar,varchar,varchar,text,timestamptz,varchar,uuid) TO trade_app;
GRANT EXECUTE ON FUNCTION close_fulfillment_monitor_case(uuid,bigint,varchar,uuid) TO trade_app;
GRANT EXECUTE ON FUNCTION advance_fulfillment_monitor_disposition(uuid,varchar,varchar,varchar,varchar) TO trade_app;
GRANT EXECUTE ON FUNCTION ack_fulfillment_monitor_release(uuid,varchar,varchar,numeric,varchar) TO trade_app;
GRANT EXECUTE ON FUNCTION claim_fulfillment_monitor_todo(uuid,varchar,varchar,uuid) TO trade_app;
