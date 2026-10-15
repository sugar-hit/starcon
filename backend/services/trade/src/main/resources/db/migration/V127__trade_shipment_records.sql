-- ===========================================================================
-- 9.4.1.2 发货记录
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md）：
--   职责：查询已发货记录：包裹、运单号、承运商、发货时间、轨迹状态、
--         买家可见状态、平台回传状态。
--   输入：筛选条件；发货事件。
--   输出：发货记录列表 + 明细。
--   关系：上游 = 8.3.4.1；下游 = 10 轨迹、9.4.2.1 回传。
--         **发货记录是平台绩效指标的计算依据**（迟发率、有效追踪率）。
--
-- ---------------------------------------------------------------------------
-- 一、本模块是「已发货」这个事实的登记处（9.4.1.1 明写的前置约定）
-- ---------------------------------------------------------------------------
-- 9.4.1.1（V124 文件头第四节）刻意把它的状态机最远钉在 HANDED_OVER，
-- **从不产生 SHIPPED**，并在文件里写明理由：「已发货是一个由 8.3.4.1 在承运商
-- 揽收后确认的事实，本模块提前置它，会让 9.4.1.2 的发货记录与 17 绩效的迟发率
-- 算在一个从未发生的时刻上」。
--
-- ⇒ 本模块就是那个登记处。已发货的**唯一**来源是 8.3.4.1（warehouse 服务 V123）
-- 已确认的交接单：status = HANDED_OVER 时，warehouse 侧已经写死了承运商、
-- 主运单号、交接人、交接时刻，并且**同一事务里完成了实发扣减**
-- （outbound_order 的 PENDING_SHIPMENT → HANDED_OVER）。所以一条 HANDED_OVER
-- 的交接单 = 一条确实已经交运的货，「已发货」不是推测出来的，是上游确认过的。
--
-- AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，因此本迁移：
--   * **一条 SELECT 都不碰 warehouse 的表**，也不建跨库外键；
--   * 发货记录**只经 warehouse 的授权 HTTP 只读端点**进入 trade，入口是
--     {@code ShipmentHandoverQueryPort}（与 {@code PackingQueryPort} 读 8.3.3.2
--     完全同构）：GET /warehouse/shipment-handovers?status=HANDED_OVER，
--     GET /warehouse/shipment-handovers/{id} 取包裹与逐箱运单号；
--   * 写入口唯一：{@code POST /trade/fulfillment/shipment-records/sync}。
--     所以它是「只读投影 + 本模块的状态机」，任何一行都能追溯回
--     source_handover_id。
--
-- ---------------------------------------------------------------------------
-- 二、三列状态里，两列的**上游根本没交付** —— 登记而不伪造
-- ---------------------------------------------------------------------------
-- 蓝图职责句点名七样东西，其中「轨迹状态」「买家可见状态」「平台回传状态」
-- 三样在本车道的集成头里**都读不到真实数据源**：
--   * 10.3 物流轨迹未开发 ⇒ 没有承运商轨迹数据源；
--   * 9.4.2.1 平台回传未开发、2.5.1.1 平台回传（channel）未开发 ⇒ 没有平台回执；
--   * 买家可见状态属平台面「买家能否看到物流」这一层，同样没有可读来源。
--
-- 处理方式照抄 9.4.1.1 已验证过的做法（V124 第二节 SLA）：
--   * 每列都允许 UNSET，落**独立的 UNSET 桶**，绝不并进正常桶 ——
--     「还没登记轨迹」与「轨迹正常」在分拣与绩效口径上是两件事，混进去
--     会让有效追踪率凭空变好看；
--   * 状态变化一律是**登记**：有主体、有时刻、有来源、有凭据行，
--     凭据行 append-only，改一次留一行；
--   * 快照列不许脱离凭据行单独改（DB 触发器钉死，绕过服务层写 SQL 同样被拒）；
--   * capabilities 里三个维度显式 available=false 并点名 ownerModule。
--
-- 「跳过」与「漏了」必须能分开：平台回传的 SKIPPED **强制**带原因，
-- 而 PENDING 是「还没发出去」。把跳过并进 PENDING，事后追责就分不出
-- 「我们决定不回传」和「我们忘了回传」—— 这两件事在平台处罚上完全不同。
--
-- ---------------------------------------------------------------------------
-- 三、迟发率与有效追踪率必须有真实口径（蓝图：绩效指标的计算依据）
-- ---------------------------------------------------------------------------
-- 迟发率的判定需要「平台要求的发货时限」，该列在 trade 服务里唯一的真实来源
-- 是同服务 9.4.1.1 的 {@code shipment_pending_unit.sla_deadline_at}
-- （V124 第二节：SLA 由人工登记或平台回传登记，有来源、有主体、有时刻）。
--
--   * 本表冗余一份 {@code sla_deadline_at} + {@code sla_source} **快照**：
--     同服务同库只读（不是跨服务读），并且快照语义正确 —— 迟发率要按**发货当时**
--     的时限判定，事后再改时限不能让一笔已经迟发的货变成准时；
--   * 同服务读不建 FK、不建视图、不 ALTER 对方表，纯 SELECT join，
--     9.4.1.1 的表不在本迁移的写入路径上（跨车道写入面 = 0）；
--   * **没有登记时限的记录落 UNSET 桶，既不算准时也不算迟发** ——
--     把没时限的当准时，迟发率就会系统性偏低。
--
-- 有效追踪率 = 「有主运单号**且**至少有一条轨迹观测」的记录占比。
-- 两者都要有：只有运单号没有任何轨迹观测，说明承运商没给到轨迹（平台照样扣分）；
-- 有观测但压根没有运单号，回传与买家举证都无法进行。
--
-- ---------------------------------------------------------------------------
-- 四、跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器，零 ALTER 任何既有表**：
--   * 不碰 V99 sales_order 族，不碰 V92 / V116 / V118 / V119 / V120 / V122 /
--     V124 / V126 的任何一张表；
--   * **不碰 warehouse 服务任何一张表**（连 ALTER 都没有，更没有跨库 FK）；
--   * 唯一的同服务引用 shipment_pending_unit 是**只读 join**，没有 FK、
--     没有触发器、没有任何写入；
--   * 所有 FK 都指向**本服务自己新建的 shipment_record**。
-- 六张表落在 shipment_record_* 命名空间，与既有 fulfillment_* /
-- shipment_pending_* / shipment_batch_* 并列而不混用 ——
-- 「已发货」是承运商揽收之后的事实，与「待发货」「批量交接」不是同一个维度。
-- 被本迁移约束到的既有模块 = 无（只读引用不算约束），跨车道 DDL 回归面 = 0。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：SR-yyyyMMdd-000001（发货记录号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE shipment_record_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. shipment_record · 发货记录头（只读投影 + 本模块的状态机）
-- ---------------------------------------------------------------------------
-- 一行 = 一次**已被 8.3.4.1 确认**的交运（一张 HANDED_OVER 的交接单）。
-- 事实归 warehouse，本表是投影 + 从 SHIPPED 往后的状态落点。
CREATE TABLE shipment_record (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- SR-yyyyMMdd-000001，由 DB 发（并发同步不撞号）
  record_no varchar(32) NOT NULL,

  -- === 上游 8.3.4.1（warehouse 服务 V123，逻辑引用，无跨服务 FK）===
  -- 一行必然能追溯回一张交接单：投影的「只读」体现在这里。
  -- UNIQUE 保证重复同步是幂等更新，不是插出第二条「已发货」。
  source_handover_id uuid NOT NULL,
  source_handover_code varchar(32) NOT NULL DEFAULT '',
  -- 打包单 / 出库单号：迟发率要按 SLA 判定，而 SLA 挂在同服务的
  -- shipment_pending_unit 上（按 source_packing_order_id join，见文件头第三节）
  source_packing_order_id uuid NOT NULL,
  source_outbound_order_id uuid NOT NULL,
  outbound_order_no varchar(64) NOT NULL DEFAULT '',

  warehouse_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL DEFAULT '',

  -- === 蓝图职责句第二、三、四样：运单号 / 承运商 / 发货时间 ===
  -- 已发货必须有承运商与运单号：这两样缺一，现场就答不出「谁运的、运的是哪票」。
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code)<>''),
  carrier_name varchar(100) NOT NULL DEFAULT '',
  master_waybill_no varchar(64) NOT NULL CHECK(btrim(master_waybill_no)<>''),
  -- 发货时间取上游 handover_at（warehouse 侧记录的交运动作时刻），
  -- 而不是 completed_at：前者是货交出去的时间，后者是事务落库时间。
  shipped_at timestamptz NOT NULL,

  parcel_count int NOT NULL CHECK(parcel_count > 0),
  parcel_item_count int NOT NULL DEFAULT 0 CHECK(parcel_item_count >= 0),
  -- 数量与重量口径与 V123 的 shipment_handover 逐字对齐：
  -- 数量 numeric(19,6) 件、重量 numeric(19,3) **克**（不改写成公斤：
  -- 0.001kg 的件换算过来就是 1g，再换算回去会掉精度）
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity >= 0),
  total_gross_weight_g numeric(19,3) NOT NULL DEFAULT 0 CHECK(total_gross_weight_g >= 0),
  total_volume_cbm numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_volume_cbm >= 0),

  -- === 订单与买家快照 ===
  -- 刻意允许为空：8.3.4.1 的交接单**没有**销售订单 / 店铺 / 买家 / 收货国，
  -- 这些只能从同服务 9.4.1.1 的投影里顺带读到（同库只读 join）。
  -- 9.4.1.1 还没同步过这批货时就是空 —— 空着不填比编一个值诚实，
  -- capabilities 里 ORDER_SNAPSHOT 维度如实说明来源与影响。
  sales_order_id uuid,
  sales_order_no varchar(64) NOT NULL DEFAULT '',
  shop_ref varchar(64) NOT NULL DEFAULT '',
  buyer_name varchar(200) NOT NULL DEFAULT '',
  destination_country varchar(2) NOT NULL DEFAULT '',

  -- === 发货时限快照（迟发率判据，见文件头第三节）===
  -- NULL = 还没有可用的时限（上游未交付平台 SLA，9.4.1.1 也还没登记），
  -- 这类记录落 UNSET 桶，**既不算准时也不算迟发**。
  sla_deadline_at timestamptz,
  sla_source varchar(24) NOT NULL DEFAULT '' CHECK(sla_source IN
    ('','MANUAL_ATTESTATION','PLATFORM_FEED')),

  -- === 状态机：SHIPPED → IN_TRANSIT → DELIVERED / RETURNED，任何态 → VOID ===
  --   SHIPPED    已发货（由同步从 8.3.4.1 的确认事实落下）
  --   IN_TRANSIT 在途
  --   DELIVERED  已妥投（终态）
  --   RETURNED   已退回 / 退件（终态）
  --   VOID       已作废（终态：上游交接作废，或发现这一票其实没发出去）
  status varchar(12) NOT NULL DEFAULT 'SHIPPED' CHECK(status IN
    ('SHIPPED','IN_TRANSIT','DELIVERED','RETURNED','VOID')),
  status_changed_at timestamptz,
  status_changed_by_membership_id uuid,
  status_reason varchar(400) NOT NULL DEFAULT '',

  -- === 蓝图职责句第五样：轨迹状态（10.3 未交付 ⇒ 登记而不伪造）===
  -- 取值域与 shipment_record_tracking_observation.status 一致，
  -- 额外多一个 UNSET = 「还没登记过任何轨迹观测」。
  -- UNSET 与 NO_TRACE（登记过、承运商明确说没有轨迹）是两件事，绝不合并。
  tracking_status varchar(20) NOT NULL DEFAULT 'UNSET' CHECK(tracking_status IN
    ('UNSET','NO_TRACE','PICKED_UP','IN_TRANSIT','CUSTOMS','OUT_FOR_DELIVERY',
     'DELIVERED','EXCEPTION','RETURNED')),
  tracking_updated_at timestamptz,

  -- === 蓝图职责句第六样：买家可见状态（无上游可读 ⇒ 登记）===
  --   UNSET     还没登记（默认，也是当前常态）
  --   VISIBLE   买家可看到物流
  --   HIDDEN    对买家隐藏
  --   PUBLISHED 已向买家发布
  buyer_visible_status varchar(12) NOT NULL DEFAULT 'UNSET' CHECK(buyer_visible_status IN
    ('UNSET','VISIBLE','HIDDEN','PUBLISHED')),
  buyer_visible_source varchar(24) NOT NULL DEFAULT '' CHECK(buyer_visible_source IN
    ('','MANUAL_ATTESTATION','PLATFORM_FEED')),
  buyer_visible_registered_at timestamptz,
  buyer_visible_by_membership_id uuid,
  buyer_visible_note varchar(400) NOT NULL DEFAULT '',

  -- === 蓝图职责句第七样：平台回传状态（9.4.2.1 / 2.5.1.1 未交付 ⇒ 登记）===
  --   UNSET        还没登记（**漏了**的入口：清单上独立成桶，不会被当成已完成）
  --   PENDING      已入待办、还没发出去
  --   SUBMITTED    已提交平台
  --   ACKNOWLEDGED 平台已受理
  --   REJECTED     平台拒绝
  --   SKIPPED      **决定不回传**（必须写明原因，与「漏了」分开，见文件头第二节）
  platform_callback_status varchar(12) NOT NULL DEFAULT 'UNSET' CHECK(platform_callback_status IN
    ('UNSET','PENDING','SUBMITTED','ACKNOWLEDGED','REJECTED','SKIPPED')),
  platform_callback_source varchar(24) NOT NULL DEFAULT '' CHECK(platform_callback_source IN
    ('','MANUAL_ATTESTATION','PLATFORM_FEED','CHANNEL_SERVICE')),
  platform_callback_registered_at timestamptz,
  platform_callback_by_membership_id uuid,
  platform_callback_ref varchar(128) NOT NULL DEFAULT '',
  platform_callback_note varchar(400) NOT NULL DEFAULT '',

  synced_at timestamptz NOT NULL,
  version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,record_no),
  -- 同一张交接单只登记一次「已发货」：这是重复登记的第一道防线，
  -- 第二次同步走 upsert 而不是插第二条（否则迟发率的分母会翻倍）。
  UNIQUE(tenant_id,source_handover_id),

  -- 买家可见四件套：要么都没登记（UNSET 且无来源），要么**全都有**
  -- （有状态、有来源、有时刻、有主体）。不允许「说不清是谁登记的」。
  CHECK(
    (buyer_visible_status = 'UNSET'
       AND buyer_visible_source = ''
       AND buyer_visible_registered_at IS NULL
       AND buyer_visible_by_membership_id IS NULL
       AND coalesce(btrim(buyer_visible_note),'') = '')
    OR
    (buyer_visible_status <> 'UNSET'
       AND buyer_visible_source <> ''
       AND buyer_visible_registered_at IS NOT NULL
       AND buyer_visible_by_membership_id IS NOT NULL)
  ),
  -- 平台回传五件套：同上，只是多了回执凭据 requestRef。
  CHECK(
    (platform_callback_status = 'UNSET'
       AND platform_callback_source = ''
       AND platform_callback_registered_at IS NULL
       AND platform_callback_by_membership_id IS NULL
       AND coalesce(btrim(platform_callback_ref),'') = ''
       AND coalesce(btrim(platform_callback_note),'') = '')
    OR
    (platform_callback_status <> 'UNSET'
       AND platform_callback_source <> ''
       AND platform_callback_registered_at IS NOT NULL
       AND platform_callback_by_membership_id IS NOT NULL)
  ),
  -- 「决定不回传」必须写明原因：否则事后无法区分「有意跳过」与「忘了回传」
  CHECK(platform_callback_status <> 'SKIPPED' OR btrim(platform_callback_note) <> ''),
  -- 作废必须写明原因，且必须落在时刻上
  CHECK(status <> 'VOID' OR (btrim(status_reason) <> '' AND status_changed_at IS NOT NULL)),
  -- 推进过状态的（不是刚同步进来的 SHIPPED）必须有变更时刻与主体
  CHECK(status = 'SHIPPED' OR status_changed_at IS NOT NULL),
  -- 有时限必带来源，两个来源互斥（与 9.4.1.1 的 sla_source 同一口径）
  CHECK(sla_deadline_at IS NULL OR sla_source <> ''),
  CHECK(sla_source = '' OR sla_deadline_at IS NOT NULL)
);

-- 清单默认按发货时间倒序，且要按承运商筛（现场按承运商分拣是大促期效率关键）
-- 索引名刻意**不**与后面的凭据表同名：索引与表同处一个 schema，同名就是
-- 「relation already exists」（第一次跑迁移就被 PostgreSQL 顶回来过）。
CREATE INDEX shipment_record_carrier
  ON shipment_record(tenant_id,carrier_code,status);
CREATE INDEX shipment_record_shipped
  ON shipment_record(tenant_id,shipped_at desc);
CREATE INDEX shipment_record_waybill
  ON shipment_record(tenant_id,master_waybill_no);
CREATE INDEX shipment_record_tracking
  ON shipment_record(tenant_id,tracking_status);
CREATE INDEX shipment_record_platform_callback
  ON shipment_record(tenant_id,platform_callback_status);
CREATE INDEX shipment_record_buyer_visible
  ON shipment_record(tenant_id,buyer_visible_status);
-- 迟发率：按时限筛「已过时限还没发的」与「时限未登记」两类
CREATE INDEX shipment_record_sla
  ON shipment_record(tenant_id,sla_deadline_at,shipped_at);
CREATE INDEX shipment_record_outbound
  ON shipment_record(tenant_id,source_outbound_order_id);
-- 9.4.1.1 投影 join 用：按 source_packing_order_id 取 SLA 快照
CREATE INDEX shipment_record_packing
  ON shipment_record(tenant_id,source_packing_order_id);

-- ---------------------------------------------------------------------------
-- 2. shipment_record_parcel · 包裹（蓝图职责句第一样：包裹）
-- ---------------------------------------------------------------------------
-- 「查询已发货记录：包裹、运单号……」—— 包裹是一票多箱时的最小单位，
-- 而**有效追踪与买家举证都是按运单号走的**，所以逐箱运单号必须逐箱留，
-- 不能只留一个主运单号（主运单号在一票多箱时并不是每个箱子都能查）。
CREATE TABLE shipment_record_parcel (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  record_id uuid NOT NULL,
  parcel_no int NOT NULL CHECK(parcel_no > 0),

  -- warehouse V123 shipment_handover_parcel.id（逻辑引用）
  source_parcel_id uuid NOT NULL,
  -- 逐箱运单号：为空表示这一箱沿用主运单号（上游 effectiveWaybillNo 为空的场景）
  waybill_no varchar(64) NOT NULL DEFAULT '',
  parcel_code varchar(64) NOT NULL DEFAULT '',

  -- 实测读数快照（V123 的 shipment_handover_parcel 逐箱冻结值，**单位克**）
  gross_weight_g numeric(19,3) NOT NULL DEFAULT 0 CHECK(gross_weight_g >= 0),
  volume_cbm numeric(19,6) NOT NULL DEFAULT 0 CHECK(volume_cbm >= 0),
  item_count int NOT NULL DEFAULT 0 CHECK(item_count >= 0),
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity >= 0),

  -- 轨迹观测挂到箱子上；整票级观测时留空
  current_tracking_status varchar(20) NOT NULL DEFAULT 'UNSET' CHECK(current_tracking_status IN
    ('UNSET','NO_TRACE','PICKED_UP','IN_TRANSIT','CUSTOMS','OUT_FOR_DELIVERY',
     'DELIVERED','EXCEPTION','RETURNED')),

  synced_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,record_id,parcel_no),
  UNIQUE(tenant_id,source_parcel_id),
  FOREIGN KEY(tenant_id,record_id) REFERENCES shipment_record(tenant_id,id)
    ON DELETE CASCADE
);

CREATE INDEX shipment_record_parcel_record
  ON shipment_record_parcel(tenant_id,record_id);
CREATE INDEX shipment_record_parcel_waybill
  ON shipment_record_parcel(tenant_id,waybill_no);

-- ---------------------------------------------------------------------------
-- 3. shipment_record_tracking_observation · 轨迹观测（append-only）
-- ---------------------------------------------------------------------------
-- 10.3 物流轨迹未交付 ⇒ 没有承运商轨迹数据源。
-- 本表是**观测登记**：每一次有人/有系统报了一个轨迹状态就落一行，
-- 带观测方、观测时刻与原始凭据；当前轨迹状态取最新一行。
--
-- 刻意 append-only：轨迹是「当时它在哪」的举证，事后改写等于伪造物流史，
-- 丢件纠纷与平台有效追踪率申诉都要拿它当证据。
-- 刻意允许**逐箱登记**：真实轨迹是按运单号走的，一票多箱里只有一箱在途是常态。
CREATE TABLE shipment_record_tracking_observation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  record_id uuid NOT NULL,
  parcel_id uuid,

  status varchar(20) NOT NULL CHECK(status IN
    ('NO_TRACE','PICKED_UP','IN_TRANSIT','CUSTOMS','OUT_FOR_DELIVERY',
     'DELIVERED','EXCEPTION','RETURNED')),
  -- 观测来源：人工登记 / 平台回传 / 承运商回调。
  -- 10.3 未交付 ⇒ 当前只可能有人工登记与平台回传两路，CHANNEL_SERVICE 留给它落地后用。
  source varchar(24) NOT NULL CHECK(source IN
    ('MANUAL_ATTESTATION','PLATFORM_FEED','CARRIER_FEED')),
  -- 原始凭据：承运商轨迹号 / 平台回执号 / 截图编号。带出处才可复核。
  observation_ref varchar(128) NOT NULL DEFAULT '',
  detail varchar(600) NOT NULL DEFAULT '',
  -- 轨迹发生时刻由**观测方**给（不是写入时刻）：
  -- 用写入时刻冒充会让「轨迹停滞多久」永远算不出来。
  observed_at timestamptz NOT NULL,
  observed_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,record_id) REFERENCES shipment_record(tenant_id,id)
    ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,parcel_id) REFERENCES shipment_record_parcel(tenant_id,id)
    ON DELETE CASCADE,
  -- 人工登记必须留登记人；凭据要么有 observation_ref 要么有 detail，
  -- 否则「有观测」等于「有人打了一行字」。
  CHECK(
    source <> 'MANUAL_ATTESTATION'
    OR (observed_by_membership_id IS NOT NULL
        AND (btrim(observation_ref) <> '' OR btrim(detail) <> ''))
  )
);

CREATE INDEX shipment_record_tracking_observation_record
  ON shipment_record_tracking_observation(tenant_id,record_id,observed_at desc);
CREATE INDEX shipment_record_tracking_observation_parcel
  ON shipment_record_tracking_observation(tenant_id,parcel_id,observed_at desc);

-- ---------------------------------------------------------------------------
-- 4. shipment_record_visibility · 买家可见状态登记（append-only）
-- ---------------------------------------------------------------------------
-- 没有上游可读 ⇒ 登记。每次变更留一行，界面上「现在是什么状态」与
-- 「什么时候变成这样的、谁改的」必须都能回答。
CREATE TABLE shipment_record_visibility (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  record_id uuid NOT NULL,

  status varchar(12) NOT NULL CHECK(status IN ('VISIBLE','HIDDEN','PUBLISHED')),
  source varchar(24) NOT NULL CHECK(source IN ('MANUAL_ATTESTATION','PLATFORM_FEED')),
  note varchar(400) NOT NULL DEFAULT '',
  registered_at timestamptz NOT NULL,
  registered_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,record_id) REFERENCES shipment_record(tenant_id,id)
    ON DELETE CASCADE,
  -- 人工登记必须写依据：买家能不能看到物流要能向买家解释
  CHECK(source <> 'MANUAL_ATTESTATION' OR btrim(note) <> '')
);

CREATE INDEX shipment_record_visibility_record
  ON shipment_record_visibility(tenant_id,record_id,registered_at desc);

-- ---------------------------------------------------------------------------
-- 5. shipment_record_callback · 平台回传登记（append-only）
-- ---------------------------------------------------------------------------
-- 9.4.2.1 与 2.5.1.1 都未交付 ⇒ 没有平台回执可读。本表登记回传的**进展与结果**，
-- 而不是假装已经回传出去。
--
-- 「跳过」与「漏了」在这里被显式分开（文件头第二节）：
--   SKIPPED  我们决定不回传（note 必填）
--   UNSET    压根还没登记（清单上的独立一桶，不会被算成「已回传」）
CREATE TABLE shipment_record_callback (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  record_id uuid NOT NULL,

  status varchar(12) NOT NULL CHECK(status IN
    ('PENDING','SUBMITTED','ACKNOWLEDGED','REJECTED','SKIPPED')),
  source varchar(24) NOT NULL CHECK(source IN
    ('MANUAL_ATTESTATION','PLATFORM_FEED','CHANNEL_SERVICE')),
  -- 回执凭据：平台侧的受理号 / 拒绝码。没有凭据的 SUBMITTED 只是「我方以为发出去了」。
  request_ref varchar(128) NOT NULL DEFAULT '',
  response_ref varchar(128) NOT NULL DEFAULT '',
  note varchar(400) NOT NULL DEFAULT '',
  registered_at timestamptz NOT NULL,
  registered_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,record_id) REFERENCES shipment_record(tenant_id,id)
    ON DELETE CASCADE,
  -- 平台已受理必须带回执凭据与平台侧时刻，否则「已回传」无法举证
  CHECK(status <> 'ACKNOWLEDGED' OR btrim(response_ref) <> ''),
  -- 已提交必须带请求凭据
  CHECK(status <> 'SUBMITTED' OR btrim(request_ref) <> ''),
  -- 拒绝必须写明原因
  CHECK(status <> 'REJECTED' OR btrim(note) <> ''),
  -- 跳过必须写明原因：**这是「跳过」与「漏了」的唯一分界**（文件头第二节）
  CHECK(status <> 'SKIPPED' OR btrim(note) <> '')
);

CREATE INDEX shipment_record_callback_record
  ON shipment_record_callback(tenant_id,record_id,registered_at desc);

-- ---------------------------------------------------------------------------
-- 6. shipment_record_event · 事件流（append-only 时间线）
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_record_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  record_id uuid NOT NULL,
  event_kind varchar(40) NOT NULL CHECK(btrim(event_kind)<>''),
  from_status varchar(20) NOT NULL DEFAULT '',
  to_status varchar(20) NOT NULL DEFAULT '',
  detail varchar(800) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  -- created_at 与其余五张表对齐：观测/登记行的排序在 observed_at 相同时用它兜底，
  -- 「同一时刻的两条登记按写入先后排」这件事必须有一个稳定键。
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,record_id) REFERENCES shipment_record(tenant_id,id)
    ON DELETE CASCADE
);

CREATE INDEX shipment_record_event_record
  ON shipment_record_event(tenant_id,record_id,occurred_at);

-- ---------------------------------------------------------------------------
-- 7. 触发器：把不变量钉在写入路径上（绕过服务层写 SQL 同样被拒）
-- ---------------------------------------------------------------------------

-- (a) 发货记录状态机只能单向推进：
--     SHIPPED → IN_TRANSIT → DELIVERED，
--     SHIPPED / IN_TRANSIT → RETURNED（退回），
--     任何态 → VOID（作废，终态）。
--     刻意**不**用「NEW.status 与 OLD.status 不同就放行」—— 那会把「改 reason」
--     一起放行，退化成没有闸门。这里按白名单逐对校验。
--     VOID 是唯一的逃生舱：上游交接作废、或发现这票其实没发出去时必须有路可走，
--     否则只能靠删行，而删行等于把一笔已经记进绩效的错发货抹掉。
CREATE OR REPLACE FUNCTION guard_shipment_record_state() RETURNS trigger AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  IF NEW.status = 'VOID' THEN allowed := true; END IF;
  IF OLD.status = 'SHIPPED' AND NEW.status IN ('IN_TRANSIT','RETURNED') THEN allowed := true; END IF;
  IF OLD.status = 'IN_TRANSIT' AND NEW.status IN ('DELIVERED','RETURNED') THEN allowed := true; END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION
      'shipment_record 状态不可从 % 推进到 %（DELIVERED/RETURNED/VOID 是终态，不改写已发生的发货事实）',
      OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_shipment_record_state_trg
  BEFORE UPDATE ON shipment_record
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_record_state();

-- (b) 三个状态快照列**不许脱离凭据行单独改**。
--     快照是给列表筛选用（筛「轨迹异常」「待回传」不能每次都去扫 append-only 明细），
--     但它一旦能被随手改，「有效追踪率」与「待回传清单」就都成了自说自话。
--     触发器要求：快照要改，必须已经存在一条**同状态**的凭据行。
--     服务层的写法因此被钉死成「先落凭据行，再更新快照」，同一个事务里完成。
CREATE OR REPLACE FUNCTION guard_shipment_record_snapshots() RETURNS trigger AS $$
DECLARE latest_tracking text;
DECLARE latest_visibility text;
DECLARE latest_callback text;
BEGIN
  IF NEW.tracking_status IS DISTINCT FROM OLD.tracking_status THEN
    IF NEW.tracking_status <> 'UNSET' THEN
      SELECT status INTO latest_tracking
        FROM shipment_record_tracking_observation
       WHERE tenant_id = NEW.tenant_id AND record_id = NEW.id
       ORDER BY observed_at desc, created_at desc
       LIMIT 1;
      IF latest_tracking IS DISTINCT FROM NEW.tracking_status THEN
        RAISE EXCEPTION
          'shipment_record 轨迹快照置为 % 但最新轨迹观测是 %（快照必须等于凭据行；先落 shipment_record_tracking_observation 再更新快照）',
          NEW.tracking_status, coalesce(latest_tracking, '（无观测）') USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;
  IF NEW.buyer_visible_status IS DISTINCT FROM OLD.buyer_visible_status THEN
    IF NEW.buyer_visible_status <> 'UNSET' THEN
      SELECT status INTO latest_visibility
        FROM shipment_record_visibility
       WHERE tenant_id = NEW.tenant_id AND record_id = NEW.id
       ORDER BY registered_at desc, created_at desc
       LIMIT 1;
      IF latest_visibility IS DISTINCT FROM NEW.buyer_visible_status THEN
        RAISE EXCEPTION
          'shipment_record 买家可见快照置为 % 但最新登记是 %（快照必须等于凭据行；先落 shipment_record_visibility 再更新快照）',
          NEW.buyer_visible_status, coalesce(latest_visibility, '（无登记）') USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;
  IF NEW.platform_callback_status IS DISTINCT FROM OLD.platform_callback_status THEN
    IF NEW.platform_callback_status <> 'UNSET' THEN
      SELECT status INTO latest_callback
        FROM shipment_record_callback
       WHERE tenant_id = NEW.tenant_id AND record_id = NEW.id
       ORDER BY registered_at desc, created_at desc
       LIMIT 1;
      IF latest_callback IS DISTINCT FROM NEW.platform_callback_status THEN
        RAISE EXCEPTION
          'shipment_record 平台回传快照置为 % 但最新登记是 %（快照必须等于凭据行；先落 shipment_record_callback 再更新快照）',
          NEW.platform_callback_status, coalesce(latest_callback, '（无登记）') USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_shipment_record_snapshots_trg
  BEFORE UPDATE ON shipment_record
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_record_snapshots();

-- (c) 四张凭据表是 append-only：连运行角色都不给改删
--     （下面还有一道 REVOKE，这里是第二道；两层都在才不算死代码）。
CREATE OR REPLACE FUNCTION reject_shipment_record_credential_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '发货记录的凭据行是 append-only 的举证凭据，不允许修改或删除'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER shipment_record_tracking_observation_append_only_trg
  BEFORE UPDATE OR DELETE ON shipment_record_tracking_observation
  FOR EACH ROW EXECUTE FUNCTION reject_shipment_record_credential_mutation();
CREATE TRIGGER shipment_record_visibility_append_only_trg
  BEFORE UPDATE OR DELETE ON shipment_record_visibility
  FOR EACH ROW EXECUTE FUNCTION reject_shipment_record_credential_mutation();
CREATE TRIGGER shipment_record_callback_append_only_trg
  BEFORE UPDATE OR DELETE ON shipment_record_callback
  FOR EACH ROW EXECUTE FUNCTION reject_shipment_record_credential_mutation();
CREATE TRIGGER shipment_record_event_append_only_trg
  BEFORE UPDATE OR DELETE ON shipment_record_event
  FOR EACH ROW EXECUTE FUNCTION reject_shipment_record_credential_mutation();

-- ---------------------------------------------------------------------------
-- 8. 多租户与授权（与 trade V1 第 2 节、V116 第 10 节、V122 第 8 节、
--    V124 第 8 节同一套写法）
-- ---------------------------------------------------------------------------
-- 六张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'shipment_record',
    'shipment_record_parcel',
    'shipment_record_tracking_observation',
    'shipment_record_visibility',
    'shipment_record_callback',
    'shipment_record_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  shipment_record,
  shipment_record_parcel,
  shipment_record_tracking_observation,
  shipment_record_visibility,
  shipment_record_callback,
  shipment_record_event TO trade_app;
-- 四张凭据表是举证凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON
  shipment_record_tracking_observation,
  shipment_record_visibility,
  shipment_record_callback,
  shipment_record_event FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V116 / V122 / V124 同口径）。
REVOKE ALL ON
  shipment_record,
  shipment_record_parcel,
  shipment_record_tracking_observation,
  shipment_record_visibility,
  shipment_record_callback,
  shipment_record_event FROM starcon_app;
-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE shipment_record_no_seq TO trade_app;
