-- ===========================================================================
-- 9.4.1.1 待发货
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md）：
--   职责：待发货订单看板：按仓库、承运渠道、承运商、发货时限分组展示，
--         支持批量申请运单号、批量打单、批量交接。
--   输入：已完成打包的出库单；发货时限要求（平台 SLA）。
--   输出：待发货清单 + 批量操作任务。
--   关系：上游 = 8.3.3.2；下游 = 10 物流下单、8.3.4.1 交接。
--         **按承运商分组是大促期效率关键**，混在一起会导致交接混乱。
--
-- ---------------------------------------------------------------------------
-- 一、本模块最关键的架构决定：待发货清单的数据来源
-- ---------------------------------------------------------------------------
-- 上游 8.3.3.2（warehouse 服务，V121）把打包结果落在 **warehouse 自己的库**：
--   packing_order / parcel / parcel_item / packing_event
--
-- AGENTS.md 模块边界纪律写死「跨 backend/services/<name>/ 必须走授权 API / event，
-- 禁止跨服务 SQL / FK / 共享视图」。因此本迁移：
--
--   * **一条 SELECT 都不碰 warehouse 的表**，也不建跨库外键；
--   * 待发货单元的数据**只经 warehouse 的授权 HTTP 只读端点**进入 trade，
--     入口是 {@code PackingQueryPort}（与 6.4.1.1 的 {@code BomVersionPort}
--     读 catalog 完全同构）：GET /warehouse/packing-orders?status=COMPLETED
--     取已完成打包的打包单，GET /warehouse/packing-orders/{id} 取包裹与明细；
--   * 读到的东西落在下面这两张**只读投影表**里，看板分组 / 分页 / 排序全部走
--     本表真实 SQL。投影而不是每次穿透调用，理由有两条且都写在这里：
--       ① 分组维度「仓库 / 承运渠道 / 承运商 / 发货时限」要在**全量**待发货单元上
--          聚合，逐页穿透聚合会漏掉没翻到的页，聚合值本身不可信；
--       ② 三个批量操作都要留下**本地任务凭据**（哪些单元被哪张任务覆盖、
--          逐条成功失败），这些凭据天然属于 trade，不属于 warehouse。
--
-- 投影表的写入**只有一个来源**：{@code POST /trade/fulfillment/pending-shipment/sync}。
-- 所以它是「只读投影」而不是事实表 —— 事实仍以 warehouse 的打包单为准，
-- 本表任何一行都能追溯回 source_packing_order_id。
--
-- ---------------------------------------------------------------------------
-- 二、发货时限（平台 SLA）：没有上游可读，所以登记而不伪造
-- ---------------------------------------------------------------------------
-- 蓝图输入句第二个词是「发货时限要求（平台 SLA）」，但本车道的集成头里：
--   * warehouse V121 的打包单**没有** SLA 列（打包现场不知道平台时限）；
--   * trade V99 的 sales_order **没有** SLA 列，status 的 CHECK 只有
--     ('DRAFT','SUBMITTED')（审单队列状态），payment_method 是**约定方式**
--     不是实收流水 —— 与 V122 文件头第二节记过的是同一件事；
--   * 2.5.1.1 平台回传未交付，9.1 订单聚合未暴露平台时限字段。
--
-- 因此本模块**不伪造**一个「已读到的平台 SLA」：
--   * sla_deadline_at 允许为 NULL，并在 {@code shipment_sla_bucket} 里落到
--     UNSET 分组 —— 「还不知道时限」与「时限是明天」在分拣上是两件事，
--     混进 DUE_TODAY 就是把最该盯的一批货藏起来了；
--   * 时限由 {@code POST .../units/{id}/sla} **登记**，是有主体、有时刻、
--     有来源的真实凭据（MANUAL_ATTESTATION / PLATFORM_FEED 两值互斥）；
--   * capabilities 里 SLA 维度显式 available=false 并点名 ownerModule。
--
-- ---------------------------------------------------------------------------
-- 三、三个批量操作是真任务，不是「返回一个列表」
-- ---------------------------------------------------------------------------
-- 蓝图点名的三个批量操作（批量申请运单号 / 批量打单 / 批量交接）各自有
-- 一张 shipment_batch_task + 逐条 shipment_batch_task_item：
--
--   WAYBILL_REQUEST  申请运单号 → 落 shipment_waybill（承运商、单号、签发时刻）
--   LABEL_PRINT      批量打单   → 逐包裹置 label_printed（渲染属 8.3.4.2）
--   HANDOVER         批量交接   → 单元推 HANDED_OVER 交给下游 8.3.4.1
--
-- 任务状态机（PENDING → RUNNING → SUCCEEDED / PARTIAL / FAILED，CANCELLED 旁路）：
--   PENDING   已建任务，逐条还没处理
--   RUNNING   正在逐条处理
--   SUCCEEDED 全部成功
--   PARTIAL   **部分**成功（部分失败是常态，必须显式区分，绝不并成 SUCCEEDED）
--   FAILED    全部失败（且必须写明 failure_reason）
--   CANCELLED 已取消
--
-- 任务创建时**必填承运商**（task 头的 carrier_code NOT NULL，且默认从所选单元
-- 取多数承运商）：蓝图那句「按承运商分组是大促期效率关键，混在一起会导致
-- 交接混乱」在本层被钉死 —— 一次交接任务不许跨承运商，否则现场按承运商
-- 分拣时必然漏发。
--
-- 逐条 item 的状态（PENDING / SUCCEEDED / FAILED / SKIPPED）：SKIPPED 表示
-- 「这条单元当时不满足前置条件」（例如已有运单号再申请一次），**必须写明
-- 原因**，否则「跳过」与「漏了」在事后追责时无法区分。
--
-- ---------------------------------------------------------------------------
-- 四、状态机边界：本模块**从不**把单元置成「已发货」
-- ---------------------------------------------------------------------------
-- 蓝图 9.4.1.2「发货记录」的上游是 8.3.4.1，9.4.2.1 回传的上游也是 8.3.4.1。
-- 本模块的最远只到 HANDED_OVER（已交给 8.3.4.1），**不产生 SHIPPED**：
-- 「已发货」是一个由 8.3.4.1 在承运商揽收后确认的事实，本模块提前置它，
-- 会让 9.4.1.2 的发货记录与 17 绩效的迟发率算在一个从未发生的时刻上。
-- DB CHECK 与状态机触发器同时钉这一条。
--
-- ---------------------------------------------------------------------------
-- 五、跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器，零 ALTER 任何既有表**：
--   * 不碰 V99 sales_order 族、不碰 V92 / V116 / V118 / V122 的任何一张表
--   * **不碰 warehouse 服务任何一张表**（连 ALTER 都没有，更没有跨库 FK）
--   * 两个 FK 都指向**本服务自己的表**（task → unit），属同库引用，
--     不是 AGENTS.md 禁止的跨服务 FK
-- 六张表落在 shipment_pending_* / shipment_batch_* / shipment_waybill /
-- shipment_event，与既有 fulfillment_* 命名空间并列而不混用 ——
-- 「待发货」是履约末端的状态，与「选仓」（fulfillment_allocation）
-- 「选路径」（fulfillment_routing）不是同一个维度。
-- 被本迁移约束到的既有模块 = 无，跨车道 DDL 回归面 = 0。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：SBT-yyyyMMdd-000001（批量任务号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE shipment_batch_task_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. shipment_pending_unit · 待发货单元（只读投影 + 本模块的状态机）
-- ---------------------------------------------------------------------------
-- 一行 = 一张**已完成打包**的出库单。事实归 warehouse，本表是投影 + 状态机落点。
CREATE TABLE shipment_pending_unit (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- === 上游 8.3.3.2（warehouse 服务，逻辑引用，无跨服务 FK）===
  -- 一行必然能追溯回一个 packing_order：投影的「只读」体现在这里
  source_packing_order_id uuid NOT NULL,
  source_outbound_order_id uuid NOT NULL,
  -- 打包完成时刻（warehouse 侧读数快照）
  packed_at timestamptz NOT NULL,
  parcel_count int NOT NULL CHECK(parcel_count > 0),
  -- 重量口径与 V121 的实测重量对齐：numeric(19,6) 公斤，不用整数克（0.0015kg 的件会被抹掉）
  total_weight_kg numeric(19,6) NOT NULL CHECK(total_weight_kg >= 0),

  -- === 出库 / 订单快照（不引用实时订单，改单不影响已打包待发的这一票）===
  warehouse_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL CHECK(btrim(warehouse_ref)<>''),
  outbound_order_no varchar(64) NOT NULL CHECK(btrim(outbound_order_no)<>''),
  sales_order_id uuid NOT NULL,
  sales_order_no varchar(64) NOT NULL CHECK(btrim(sales_order_no)<>''),
  shop_ref varchar(64) NOT NULL DEFAULT '',
  destination_country varchar(2) NOT NULL DEFAULT '',
  buyer_name varchar(200) NOT NULL DEFAULT '',

  -- === 蓝图点名的两个分组维度：承运渠道与承运商 ===
  -- 承运渠道：走哪个链路（平台面单 / 第三方物流 / 自提 / 邮政）
  carrier_channel varchar(24) NOT NULL CHECK(carrier_channel IN
    ('PLATFORM_INTEGRATED','THIRD_PARTY','SELF_PICKUP','POSTAL','FREIGHT_FORWARDER')),
  -- 承运商：**允许为空**（还没选路由），但为空时任务建不起来（见第 3 节）
  carrier_code varchar(64) NOT NULL DEFAULT '' CHECK(carrier_code = '' OR btrim(carrier_code) <> ''),

  -- === 发货时限（见文件头第二节：登记而不伪造）===
  sla_deadline_at timestamptz,
  -- 有时限必带来源；两个来源互斥，不允许「说不清是谁登记的」
  sla_source varchar(24) NOT NULL DEFAULT '' CHECK(sla_source IN
    ('','MANUAL_ATTESTATION','PLATFORM_FEED')),
  sla_registered_by_membership_id uuid,
  sla_registered_at timestamptz,
  sla_note text NOT NULL DEFAULT '',

  -- === 状态机：PENDING → WAYBILL_REQUESTED → LABEL_PRINTED → HANDED_OVER ===
  --   PENDING            已打包待发货
  --   WAYBILL_REQUESTED  已申请到运单号
  --   LABEL_PRINTED      已批量打单
  --   HANDED_OVER        **已交接**给 8.3.4.1（本模块的最远态，**不是已发货**）
  --   CANCELLED          已作废（退回仓库，不产生发货记录）
  status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN
    ('PENDING','WAYBILL_REQUESTED','LABEL_PRINTED','HANDED_OVER','CANCELLED')),

  -- 当前生效运单号（取自 shipment_waybill 的最新一条签发行，本表冗余一份供看板直接筛）
  waybill_no varchar(128) NOT NULL DEFAULT '',
  handed_over_at timestamptz,
  cancel_reason varchar(400) NOT NULL DEFAULT '',

  synced_at timestamptz NOT NULL,
  version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张打包单只投影一次：重复同步是幂等更新，不是插第二行
  UNIQUE(tenant_id,source_packing_order_id),
  -- 交接必须知道交**给谁**：承运商还没选（还没走 9.3.x 路由）就不许交接，
  -- 否则下游 8.3.4.1 拿到一张没有承运商的单，现场只能靠打电话问。
  CHECK(status <> 'HANDED_OVER' OR btrim(carrier_code) <> ''),
  -- 时限四件套：要么都没有（未登记），要么**全都有**（登记过且有出处）
  CHECK(
    (sla_deadline_at IS NULL
       AND sla_source = ''
       AND sla_registered_by_membership_id IS NULL
       AND sla_registered_at IS NULL
       AND coalesce(btrim(sla_note),'') = '')
    OR
    (sla_deadline_at IS NOT NULL
       AND sla_source <> ''
       AND sla_registered_by_membership_id IS NOT NULL
       AND sla_registered_at IS NOT NULL)
  ),
  -- 作废必须写明原因
  CHECK(status <> 'CANCELLED' OR btrim(cancel_reason) <> ''),
  -- 本模块**不产生**已发货：这一行刻意只放行 HANDED_OVER（交给 8.3.4.1），
  -- CHECK 与 guard_shipment_pending_unit_state 触发器两道一起钉住这条边界。
  CHECK(status <> 'HANDED_OVER' OR handed_over_at IS NOT NULL)
);

-- 看板按承运商分组是蓝图点名的核心，索引必须站在分组键上
CREATE INDEX shipment_pending_unit_carrier
  ON shipment_pending_unit(tenant_id,carrier_code,status);
CREATE INDEX shipment_pending_unit_channel
  ON shipment_pending_unit(tenant_id,carrier_channel,status);
CREATE INDEX shipment_pending_unit_warehouse
  ON shipment_pending_unit(tenant_id,warehouse_id,status);
-- 时限分组：未登记（NULL）与最紧急的到期单必须能被一条索引切出来
CREATE INDEX shipment_pending_unit_sla
  ON shipment_pending_unit(tenant_id,sla_deadline_at,status);
CREATE INDEX shipment_pending_unit_status
  ON shipment_pending_unit(tenant_id,status,packed_at);
CREATE INDEX shipment_pending_unit_outbound
  ON shipment_pending_unit(tenant_id,source_outbound_order_id);

-- ---------------------------------------------------------------------------
-- 2. shipment_pending_parcel · 包裹投影（批量打单的对象是**包裹**，不是单元）
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_pending_parcel (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  parcel_no int NOT NULL CHECK(parcel_no > 0),
  -- warehouse V121 的 parcel.id（逻辑引用）
  source_parcel_id uuid NOT NULL,

  -- 实测重量与尺寸（V121 的读数快照）
  gross_weight_kg numeric(19,6) NOT NULL CHECK(gross_weight_kg >= 0),
  length_mm numeric(19,6) NOT NULL CHECK(length_mm >= 0),
  width_mm numeric(19,6) NOT NULL CHECK(width_mm >= 0),
  height_mm numeric(19,6) NOT NULL CHECK(height_mm >= 0),

  -- 面单打印（渲染属 8.3.4.2，本模块只记「打过了」与凭据）
  label_printed boolean NOT NULL DEFAULT false,
  label_printed_at timestamptz,
  label_printed_by_membership_id uuid,
  label_job_ref varchar(128) NOT NULL DEFAULT '',
  printed_by_batch_task_id uuid,

  synced_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,unit_id,parcel_no),
  UNIQUE(tenant_id,source_parcel_id),
  FOREIGN KEY(tenant_id,unit_id) REFERENCES shipment_pending_unit(tenant_id,id)
    ON DELETE CASCADE,
  -- 「打单了」必须带时刻与打印任务凭据，否则「打过了」无法追责
  CHECK(NOT label_printed
        OR (label_printed_at IS NOT NULL AND btrim(label_job_ref) <> ''))
);

CREATE INDEX shipment_pending_parcel_unit
  ON shipment_pending_parcel(tenant_id,unit_id);
CREATE INDEX shipment_pending_parcel_unprinted
  ON shipment_pending_parcel(tenant_id,label_printed);

-- ---------------------------------------------------------------------------
-- 3. shipment_batch_task · 批量操作任务（蓝图输出第二条：批量操作任务）
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_batch_task (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- SBT-yyyyMMdd-000001，由 DB 发（并发建任务不撞号）
  task_no varchar(32) NOT NULL,

  -- 三个批量操作三值，逐一对应蓝图点名的三句话
  task_type varchar(20) NOT NULL CHECK(task_type IN
    ('WAYBILL_REQUEST','LABEL_PRINT','HANDOVER')),

  -- === 分组维度落在任务头上：一次任务**不许跨承运商**（文件头第三节）===
  warehouse_id uuid,
  carrier_channel varchar(24) CHECK(carrier_channel IS NULL OR carrier_channel IN
    ('PLATFORM_INTEGRATED','THIRD_PARTY','SELF_PICKUP','POSTAL','FREIGHT_FORWARDER')),
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code)<>''),

  -- === 状态机 ===
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN
    ('PENDING','RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELLED')),
  unit_count int NOT NULL CHECK(unit_count > 0),
  succeeded_count int NOT NULL DEFAULT 0 CHECK(succeeded_count >= 0),
  failed_count int NOT NULL DEFAULT 0 CHECK(failed_count >= 0),
  skipped_count int NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),

  -- 执行结果凭据
  failure_reason varchar(600) NOT NULL DEFAULT '',
  cancel_reason varchar(400) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',

  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  version int NOT NULL DEFAULT 1,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_no),
  -- 交接必须知道在哪个仓交（承运商上门取件的位置）
  CHECK(task_type <> 'HANDOVER' OR warehouse_id IS NOT NULL),
  CHECK(succeeded_count + failed_count + skipped_count <= unit_count),
  -- 结束态必须带时刻，否则「做完了」在时间线上无处落
  CHECK(status NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')
        OR (started_at IS NOT NULL AND finished_at IS NOT NULL)),
  -- 全失败必须写明原因，否则界面无法处置
  CHECK(status <> 'FAILED' OR btrim(failure_reason) <> ''),
  -- 取消必须写明原因
  CHECK(status <> 'CANCELLED' OR btrim(cancel_reason) <> '')
);

CREATE INDEX shipment_batch_task_open_queue
  ON shipment_batch_task(tenant_id,status,created_at);
CREATE INDEX shipment_batch_task_carrier
  ON shipment_batch_task(tenant_id,carrier_code,task_type);
CREATE INDEX shipment_batch_task_type
  ON shipment_batch_task(tenant_id,task_type,status);

-- ---------------------------------------------------------------------------
-- 4. shipment_batch_task_item · 逐条执行结果
-- ---------------------------------------------------------------------------
-- 「部分成功是常态」这句话的落点：逐条留成功 / 失败 / 跳过与原因，
-- 否则 PARTIAL 这个状态在事后无法拆开。
CREATE TABLE shipment_batch_task_item (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  parcel_id uuid,

  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN
    ('PENDING','SUCCEEDED','FAILED','SKIPPED')),
  detail varchar(600) NOT NULL DEFAULT '',
  processed_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES shipment_batch_task(tenant_id,id)
    ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,unit_id) REFERENCES shipment_pending_unit(tenant_id,id),
  FOREIGN KEY(tenant_id,parcel_id) REFERENCES shipment_pending_parcel(tenant_id,id),
  -- 结束态必须带时刻；跳过 / 失败必须写明原因
  CHECK(status = 'PENDING' OR processed_at IS NOT NULL),
  CHECK(status NOT IN ('FAILED','SKIPPED') OR btrim(detail) <> '')
);

CREATE INDEX shipment_batch_task_item_task
  ON shipment_batch_task_item(tenant_id,task_id,status);
CREATE INDEX shipment_batch_task_item_unit
  ON shipment_batch_task_item(tenant_id,unit_id);
-- 「一个单元在这张任务里只出现一次」这条去重口径按**逐包裹**与**逐单元**分开落：
--   * 批量打单的对象是**包裹**（一票三个箱子就是三条，逐单元去重会把三个箱子并成一条，
--     而「打单失败」恰恰发生在某个箱子上 —— 并成一条就再也说不出是哪箱没打上）；
--   * 另外两个操作的对象是单元，逐包裹去重反而会漏。
-- 所以用两个**部分唯一索引**而不是一条表级 UNIQUE：表级 UNIQUE 表达不了
-- 「parcel_id 为 NULL 时按单元去重、有值时按包裹去重」这个二选一。
CREATE UNIQUE INDEX shipment_batch_task_item_unit_once
  ON shipment_batch_task_item(tenant_id,task_id,unit_id) WHERE parcel_id IS NULL;
CREATE UNIQUE INDEX shipment_batch_task_item_parcel_once
  ON shipment_batch_task_item(tenant_id,task_id,parcel_id) WHERE parcel_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. shipment_waybill · 运单号申请结果
-- ---------------------------------------------------------------------------
-- 「批量申请运单号」的产出必须**逐票**落库：交接现场按运单号找人，
-- 只有一张「已申请」的勾而没有单号等于没申请。
CREATE TABLE shipment_waybill (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  task_id uuid,
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code)<>''),
  carrier_channel varchar(24) NOT NULL CHECK(carrier_channel IN
    ('PLATFORM_INTEGRATED','THIRD_PARTY','SELF_PICKUP','POSTAL','FREIGHT_FORWARDER')),
  -- REQUESTED 已提交承运商 / ISSUED 已签发 / FAILED 被拒
  status varchar(12) NOT NULL CHECK(status IN ('REQUESTED','ISSUED','FAILED')),
  waybill_no varchar(128) NOT NULL DEFAULT '',
  request_ref varchar(128) NOT NULL DEFAULT '',
  error_message varchar(600) NOT NULL DEFAULT '',
  requested_at timestamptz NOT NULL,
  issued_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,unit_id) REFERENCES shipment_pending_unit(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES shipment_batch_task(tenant_id,id),
  -- 签发必须带单号与时刻；失败必须写明原因
  CHECK(status <> 'ISSUED' OR (btrim(waybill_no) <> '' AND issued_at IS NOT NULL)),
  CHECK(status <> 'FAILED' OR btrim(error_message) <> ''),
  CHECK(status = 'ISSUED' OR issued_at IS NULL)
);

CREATE INDEX shipment_waybill_unit
  ON shipment_waybill(tenant_id,unit_id,status);
CREATE INDEX shipment_waybill_task
  ON shipment_waybill(tenant_id,task_id);
CREATE UNIQUE INDEX shipment_waybill_issued_once
  ON shipment_waybill(tenant_id,unit_id,carrier_code) WHERE status = 'ISSUED';

-- ---------------------------------------------------------------------------
-- 6. shipment_event · 事件流（append-only 时间线）
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  unit_id uuid,
  task_id uuid,
  event_kind varchar(40) NOT NULL CHECK(btrim(event_kind)<>''),
  from_status varchar(20) NOT NULL DEFAULT '',
  to_status varchar(20) NOT NULL DEFAULT '',
  detail varchar(800) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,unit_id) REFERENCES shipment_pending_unit(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES shipment_batch_task(tenant_id,id),
  -- 一个事件至少挂在单元或任务之一上，否则时间线上会出现无主事件
  CHECK(unit_id IS NOT NULL OR task_id IS NOT NULL)
);

CREATE INDEX shipment_event_unit
  ON shipment_event(tenant_id,unit_id,occurred_at);
CREATE INDEX shipment_event_task
  ON shipment_event(tenant_id,task_id,occurred_at);

-- ---------------------------------------------------------------------------
-- 7. 触发器：把「不变量」钉在写入路径上（绕过服务层写 SQL 同样被拒）
-- ---------------------------------------------------------------------------

-- (a) 单元状态只能单向推进：PENDING → WAYBILL_REQUESTED → LABEL_PRINTED → HANDED_OVER，
--     任一步可以 CANCELLED（退回仓库），已交接 / 已作废是终态。
--     刻意**不**用「看 NEW.status 与 OLD.status 不同就放行」—— 那会把「改备注」
--     也一起放行，退化成没有闸门。这里按 ordinal 白名单逐对校验。
CREATE OR REPLACE FUNCTION guard_shipment_pending_unit_state() RETURNS trigger AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  IF NEW.status = 'CANCELLED' THEN allowed := true; END IF;
  IF OLD.status = 'PENDING' AND NEW.status IN ('WAYBILL_REQUESTED','LABEL_PRINTED','HANDED_OVER')
     THEN allowed := true; END IF;
  IF OLD.status = 'WAYBILL_REQUESTED' AND NEW.status IN ('LABEL_PRINTED','HANDED_OVER')
     THEN allowed := true; END IF;
  IF OLD.status = 'LABEL_PRINTED' AND NEW.status = 'HANDED_OVER'
     THEN allowed := true; END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION
      'shipment_pending_unit 状态不可从 % 推进到 %（本模块最远只到 HANDED_OVER，不产生已发货）',
      OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_shipment_pending_unit_state_trg
  BEFORE UPDATE ON shipment_pending_unit
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_pending_unit_state();

-- (b) 批量任务状态机：PENDING → RUNNING → (SUCCEEDED/PARTIAL/FAILED)，PENDING/RUNNING → CANCELLED。
--     结束态不可再动（结束态的计数与时刻是事后追责的凭据）。
CREATE OR REPLACE FUNCTION guard_shipment_batch_task_state() RETURNS trigger AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  IF OLD.status IN ('PENDING','RUNNING') AND NEW.status = 'CANCELLED' THEN allowed := true; END IF;
  IF OLD.status = 'PENDING' AND NEW.status = 'RUNNING' THEN allowed := true; END IF;
  IF OLD.status = 'RUNNING'
     AND NEW.status IN ('SUCCEEDED','PARTIAL','FAILED') THEN allowed := true; END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION
      'shipment_batch_task 状态不可从 % 推进到 %（结束态是追责凭据，不再变动）',
      OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_shipment_batch_task_state_trg
  BEFORE UPDATE ON shipment_batch_task
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_batch_task_state();

-- (c) 任务结束时的计数必须与逐条 item 实际对得上 —— 否则 PARTIAL 与
--     SUCCEEDED 的差别只由服务层说了算，「部分成功」会被并成「全成功」。
CREATE OR REPLACE FUNCTION guard_shipment_batch_task_counts() RETURNS trigger AS $$
DECLARE s int; DECLARE f int; DECLARE k int;
BEGIN
  IF NEW.status NOT IN ('SUCCEEDED','PARTIAL','FAILED') THEN RETURN NEW; END IF;
  SELECT count(*) FILTER (WHERE status='SUCCEEDED'),
         count(*) FILTER (WHERE status='FAILED'),
         count(*) FILTER (WHERE status='SKIPPED')
    INTO s, f, k
    FROM shipment_batch_task_item WHERE tenant_id = NEW.tenant_id AND task_id = NEW.id;
  IF NEW.succeeded_count <> s OR NEW.failed_count <> f OR NEW.skipped_count <> k THEN
    RAISE EXCEPTION
      'shipment_batch_task % 结束计数 (成功 % / 失败 % / 跳过 %) 与逐条实际 (成功 % / 失败 % / 跳过 %) 不符',
      NEW.task_no, NEW.succeeded_count, NEW.failed_count, NEW.skipped_count, s, f, k
      USING ERRCODE = 'check_violation';
  END IF;
  -- 部分成功绝不能被写成全成功：只要有一条非成功，就不能是 SUCCEEDED
  IF NEW.status = 'SUCCEEDED' AND (f > 0 OR k > 0) THEN
    RAISE EXCEPTION
      'shipment_batch_task % 有 % 条失败 / % 条跳过，不能记为 SUCCEEDED',
      NEW.task_no, f, k USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_shipment_batch_task_counts_trg
  BEFORE UPDATE ON shipment_batch_task
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_batch_task_counts();

-- (d) 事件流是凭据：连运行角色都不给改删（下面还有一道 REVOKE，这里是第二道）
CREATE OR REPLACE FUNCTION reject_shipment_event_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'shipment_event 是 append-only 的凭据，不允许修改或删除'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER shipment_event_append_only_trg
  BEFORE UPDATE OR DELETE ON shipment_event
  FOR EACH ROW EXECUTE FUNCTION reject_shipment_event_mutation();

-- ---------------------------------------------------------------------------
-- 8. 多租户与授权（与 trade V1 第 2 节、V116 第 10 节、V122 第 8 节同一套写法）
-- ---------------------------------------------------------------------------
-- 六张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'shipment_pending_unit',
    'shipment_pending_parcel',
    'shipment_batch_task',
    'shipment_batch_task_item',
    'shipment_waybill',
    'shipment_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  shipment_pending_unit,
  shipment_pending_parcel,
  shipment_batch_task,
  shipment_batch_task_item,
  shipment_waybill,
  shipment_event TO trade_app;
-- 事件流是凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON shipment_event FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V116 / V122 同口径）。
REVOKE ALL ON
  shipment_pending_unit,
  shipment_pending_parcel,
  shipment_batch_task,
  shipment_batch_task_item,
  shipment_waybill,
  shipment_event FROM starcon_app;
-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE shipment_batch_task_no_seq TO trade_app;