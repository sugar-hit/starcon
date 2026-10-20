-- ===========================================================================
-- 10.3.1.1 轨迹查询（channel 服务）
-- ===========================================================================
-- 蓝图原文（10-logistics-and-freight.md）：
--   职责：查询并展示包裹物流轨迹：节点时间线（揽收-运输-清关-转运-派送-签收）、
--         预计时效对比、异常节点标记，支持按承运商批量拉取。
--   输入：运单/包裹集合；承运商轨迹接口；轮询任务。
--   输出：轨迹时间线 + 预计到达时间 + 异常标记。
--   关系：上游 = 承运商 API；下游 = 10.3.1.2、15 客服（查询应答）、13 分析。
--         **按承运商事件 ID 去重**，同一节点重复推送不产生重复记录。
--
-- ---------------------------------------------------------------------------
-- 一、运单真值在 trade，本服务不碰对方的表
-- ---------------------------------------------------------------------------
-- AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以：
--   * 运单 / 发货记录的真值在 **trade**（9.4.1.2 shipment_record 族，V127），
--     包裹的真值在 **warehouse**（parcel / parcel_item，V125）；
--   * 本服务**只经 trade 的授权 HTTP 只读接口**读（ShipmentTrackingQueryPort），
--     **转发调用方自己的 bearer** —— 租户隔离是 channel 侧 RLS + trade 侧 RLS
--     **两道**，而不是「本服务替对方决定租户」；
--   * 本服务一条 SELECT 都不碰 trade / warehouse 的表，也没有跨库 FK / 视图。
--
-- ⚠️ 于是「运单标识」在本服务里是**调用方带入的业务键**：trade 的
--    shipment_record.id 原样落在 logistics_tracking_subject.source_shipment_record_id，
--    **本服务不编造跨服务 uuid**（照抄本服务 writeback_target 的做法）。
--
-- ---------------------------------------------------------------------------
-- 二、轨迹事件本服务自持 —— 去重落在 DB 唯一约束上
-- ---------------------------------------------------------------------------
-- 承运商事件（carrier_event_id 是承运商自己的事件号）是**本模块的事实**：
-- 它来自承运商推送或本模块按承运商批量拉取，不是 trade 的观测登记
-- （trade 的 shipment_record_tracking_observation 是 9.4.1.2 的**人工 / 平台**
-- 凭据行，source 里 CARRIER_FEED 那一档本来就是留给本模块落地的）。
--
-- 所以本服务建**自持的事件账本** logistics_tracking_event：
--   * append-only（REVOKE UPDATE,DELETE + BEFORE UPDATE OR DELETE 触发器）——
--     轨迹是「当时它在哪」的举证，事后改写等于伪造物流史；
--   * **UNIQUE (tenant_id, carrier_code, carrier_event_id)** —— 蓝图加粗句
--     「按承运商事件 ID 去重，同一节点重复推送不产生重复记录」的物理落点。
--     去重**不是**服务层的 if 判断：绕过服务层直接写 SQL 插同一条事件同样撞
--     23505，测试用应用账号与迁移超级账号**各试一次**。
--
-- ⚠️ 去重键为什么带 carrier_code：不同承运商的事件号空间互相独立，
--    同一个 "EVT-1" 在 A 家与 B 家是两条不同的事件。按 (tenant_id, event_id)
--    去重会把 B 家的第一条误判成 A 家的重复。
--
-- ---------------------------------------------------------------------------
-- 三、主体表是「投影 + 派生状态」，不是第二份运单真值
-- ---------------------------------------------------------------------------
-- logistics_tracking_subject 一行 = 一票**已发货**（trade 的 shipment_record）。
-- 它带三组东西：
--   1. 标识（运单号 / 承运商 / 目的国 / 订单号）—— 来自 trade 的只读投影；
--   2. 轨迹派生状态（最后一节点 / 事件数 / 异常标记）—— 由事件账本聚合；
--   3. **时效承诺快照**（承诺天数 / 承诺到达时刻）—— 来自同库只读的
--      logistics_transit_rule（V139，10.1.1.3 已交付）。
--
-- 🔴 承诺时效是**快照**而不是实时 join：迟发率与「是否延误」要按**发货当时**
--    的口径判定，事后改档期不能让一笔已经延误的货变成准点。V127 的
--    sla_deadline_at 是同一口径的先例。
--
-- ---------------------------------------------------------------------------
-- 四、异常节点标记 vs 异常件处置 —— 边界划在哪里
-- ---------------------------------------------------------------------------
-- 蓝图 10.3.1.2（异常件，**未交付**）的输入句里有「轨迹异常规则」，
-- 而 10.3.1.1 的职责句里是「异常**节点标记**」。所以本模块只做**标记**：
--   * 事件级：node_type='EXCEPTION' 的节点**必须**带 exception_code
--     （表级 CHECK：标了异常却说不清是什么异常 = 假标记）；
--   * 主体级：exception_flag / exception_count / last_exception_* 是事件的聚合。
-- 处置（联系承运商 / 理赔 / 补发 / 改址）归 10.3.1.2，本模块**不提供**任何
-- 处置入口，capabilities 里显式 available=false 并点名 ownerModule。
--
-- ---------------------------------------------------------------------------
-- 五、轮询任务：本模块提供**拉取入口**，不自带调度器
-- ---------------------------------------------------------------------------
-- logistics_tracking_pull_task + logistics_tracking_pull_task_item 是一轮
-- 「按承运商批量拉取」的**可审计账本**：谁触发、拉了哪几票、每票取回几条、
-- 几条是重复、哪几票失败。触发方是外部调度（K8s CronJob / 平台任务执行器），
-- 本服务**不装 @Scheduled**：多副本下自带定时器要做主节点选举与分布式锁，
-- 那是平台能力不是领域能力，而外部调度触发一次入口反而天然单次、可重试、可审计。
--
-- ---------------------------------------------------------------------------
-- 六、跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器 / 策略，零 ALTER 任何既有表**：
--   * 不碰 V129 handover 族，不碰 V134 / V136 / V139 / V141 的任何一张表；
--   * 唯一引用的既有表是 logistics_channel_config（V136，同库复合外键）；
--   * 跨车道 DDL 回归面 = 0。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：TRK-yyyyMMdd-000001（拉取任务号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE logistics_tracking_pull_task_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. logistics_tracking_subject · 轨迹主体（运单级投影 + 派生状态）
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_tracking_subject (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 上游 9.4.1.2 trade（跨服务 uuid 由调用方带入，本服务不编造）===
  source_shipment_record_id uuid NOT NULL,
  source_record_no varchar(32) NOT NULL DEFAULT '',
  sales_order_no varchar(64) NOT NULL DEFAULT '',
  outbound_order_no varchar(64) NOT NULL DEFAULT '',
  warehouse_ref varchar(64) NOT NULL DEFAULT '',

  -- === 运单标识（蓝图输出第一条：轨迹时间线的主键是运单号）===
  carrier_code varchar(64) NOT NULL CHECK (btrim(carrier_code) <> ''),
  master_waybill_no varchar(64) NOT NULL CHECK (btrim(master_waybill_no) <> ''),
  destination_country varchar(2) NOT NULL DEFAULT ''
    CHECK (destination_country = '' OR destination_country ~ '^[A-Z]{2}$'),

  -- === 10.1.1.2 渠道配置（同库复合外键，带 tenant_id ⇒ 跨租户挂载不可能）===
  -- NULL = 这票货还没归到任何一条渠道配置上（承运商登记了但没建渠道）。
  -- 用 RESTRICT 而不是 SET NULL：复合外键的 SET NULL 会把 tenant_id 一起置空，
  -- 而 tenant_id 是 NOT NULL —— 那是删渠道配置时炸库，不是降级。
  channel_id uuid,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE RESTRICT,

  -- === 时效口径：重量单位是**公斤**（V127 的 total_gross_weight_g 是克，
  --     换算在服务层用 numeric 除法做，禁浮点）===
  gross_weight_kg numeric(19,6) NOT NULL DEFAULT 0 CHECK (gross_weight_kg >= 0),
  service_level text NOT NULL DEFAULT 'STANDARD'
    CHECK (service_level IN ('ECONOMY','STANDARD','EXPRESS')),
  parcel_count int NOT NULL DEFAULT 0 CHECK (parcel_count >= 0),
  shipped_at timestamptz,

  -- === 时效承诺快照（10.1.1.3 logistics_transit_rule，同库只读）===
  -- 三个字段要么都给要么都不给：承诺天数与承诺到达时刻必须成对，
  -- 否则「有承诺天数却算不出到达时刻」这种半截状态会让人以为算过。
  promised_transit_days_min int CHECK (promised_transit_days_min IS NULL OR promised_transit_days_min >= 1),
  promised_transit_days_max int CHECK (promised_transit_days_max IS NULL OR promised_transit_days_max >= 1),
  promised_eta_earliest_at timestamptz,
  promised_eta_at timestamptz,
  CHECK (
    (promised_transit_days_min IS NULL AND promised_transit_days_max IS NULL
     AND promised_eta_earliest_at IS NULL AND promised_eta_at IS NULL)
    OR
    (promised_transit_days_min IS NOT NULL AND promised_transit_days_max IS NOT NULL
     AND promised_eta_earliest_at IS NOT NULL AND promised_eta_at IS NOT NULL
     AND promised_transit_days_max >= promised_transit_days_min)
  ),
  -- 承运商给的预计到达（承运商说的，不是我方承诺）
  carrier_eta_at timestamptz,
  -- 命中了几条时效承诺：>1 = 同一承运商有多个渠道都承诺了这个国家与重量段，
  -- 取「承诺最紧」的一条，命中条数如实暴露这个歧义，不藏。
  matched_promise_count int NOT NULL DEFAULT 0 CHECK (matched_promise_count >= 0),

  -- === 轨迹派生状态（由事件账本聚合，不是调用方指定的）===
  latest_node varchar(20) NOT NULL DEFAULT 'NO_TRACE' CHECK (latest_node IN
    ('NO_TRACE','PICKED_UP','IN_TRANSIT','CUSTOMS','TRANSFER','OUT_FOR_DELIVERY',
     'DELIVERED','EXCEPTION','RETURNED')),
  latest_event_code varchar(64) NOT NULL DEFAULT '',
  latest_description varchar(400) NOT NULL DEFAULT '',
  latest_occurred_at timestamptz,
  latest_received_at timestamptz,
  event_count int NOT NULL DEFAULT 0 CHECK (event_count >= 0),
  exception_flag boolean NOT NULL DEFAULT false,
  exception_count int NOT NULL DEFAULT 0 CHECK (exception_count >= 0),
  last_exception_code varchar(40) NOT NULL DEFAULT '',
  last_exception_note varchar(400) NOT NULL DEFAULT '',
  last_exception_at timestamptz,

  -- ACTIVE 在途 · DELIVERED 已签收 · RETURNED 已退回 · VOID 已作废
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','DELIVERED','RETURNED','VOID')),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一个发货记录只对应一个轨迹主体：重复同步是幂等更新，不是插出第二条
  UNIQUE (tenant_id, source_shipment_record_id)
);

-- 承运商筛选（列表主筛选 + 「按承运商批量拉取」的取件范围）
CREATE INDEX logistics_tracking_subject_by_carrier
  ON logistics_tracking_subject(tenant_id, carrier_code);

-- 运单号检索（客服按单号查轨迹的主路径）
CREATE INDEX logistics_tracking_subject_by_waybill
  ON logistics_tracking_subject(tenant_id, master_waybill_no);

-- 列表默认排序：最近有轨迹的排前面
CREATE INDEX logistics_tracking_subject_by_latest
  ON logistics_tracking_subject(tenant_id, latest_occurred_at DESC NULLS LAST);

-- 异常筛选（只捞有异常的）用部分索引：异常票是少数
CREATE INDEX logistics_tracking_subject_by_exception
  ON logistics_tracking_subject(tenant_id, latest_occurred_at DESC NULLS LAST)
  WHERE exception_flag;

-- 状态筛选
CREATE INDEX logistics_tracking_subject_by_status
  ON logistics_tracking_subject(tenant_id, status);

-- ---------------------------------------------------------------------------
-- 2. logistics_tracking_event · 承运商事件账本（append-only + DB 级去重）
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_tracking_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  subject_id uuid NOT NULL,
  -- ⚠️ ON DELETE **RESTRICT** 而不是 CASCADE：本表 append-only（触发器拒 DELETE），
  --    CASCADE 在这里其实是**死代码** —— 删主体时级联删除会被 BEFORE DELETE 触发器
  --    拦下，抛出一句「append-only」而不是干净的 FK 违例，排查时容易看不懂。
  --    RESTRICT 把意图写死：**事件是举证凭据，不能把主体连着证据一起删掉**；
  --    主体作废走 status='VOID'，不是 DELETE。
  FOREIGN KEY (tenant_id, subject_id)
    REFERENCES logistics_tracking_subject(tenant_id, id) ON DELETE RESTRICT,

  carrier_code varchar(64) NOT NULL CHECK (btrim(carrier_code) <> ''),
  -- 🔴 去重键的一半：承运商自己的事件号。同一节点重复推送时它是同一个值。
  carrier_event_id varchar(128) NOT NULL CHECK (btrim(carrier_event_id) <> ''),
  -- 节点类型（蓝图职责句括号里的六个：揽收-运输-清关-转运-派送-签收，
  -- 外加 NO_TRACE / EXCEPTION / RETURNED 三档）
  node_type varchar(20) NOT NULL CHECK (node_type IN
    ('NO_TRACE','PICKED_UP','IN_TRANSIT','CUSTOMS','TRANSFER','OUT_FOR_DELIVERY',
     'DELIVERED','EXCEPTION','RETURNED')),

  -- 承运商原始状态码：留着才能对上承运商的文档口径，也便于人工核对
  event_code varchar(64) NOT NULL DEFAULT '',
  description varchar(400) NOT NULL DEFAULT '',
  location varchar(200) NOT NULL DEFAULT '',

  -- 发生时刻由**承运商**给（不是写入时刻）：用写入时刻冒充会让
  -- 「轨迹停滞多久」永远算不出来。
  occurred_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL,
  -- 承运商给的预计到达时刻（**承运商说的，不是我方承诺**）。
  -- 我方承诺在主体表上是另一列（promised_eta_at，来源是 10.1.1.3 的时效规则），
  -- 两者绝不合并 —— 合并之后界面上就分不清「谁承诺的」。
  estimated_delivery_at timestamptz,
  source varchar(24) NOT NULL CHECK (source IN
    ('CARRIER_PUSH','CARRIER_PULL','MANUAL_ATTESTATION')),

  -- === 异常标记（只标记，不处置；处置归 10.3.1.2）===
  exception_code varchar(40) NOT NULL DEFAULT '',
  exception_note varchar(400) NOT NULL DEFAULT '',

  -- 原始凭据（承运商给的整条报文）：带出处才可复核
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,

  registered_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴🔴 去重的物理落点：蓝图「按承运商事件 ID 去重，同一节点重复推送
  --    不产生重复记录」。绕过服务层直接 INSERT 同一条事件同样撞 23505。
  UNIQUE (tenant_id, carrier_code, carrier_event_id),
  -- 标了异常就必须说清是什么异常：假标记比不标记更糟
  CHECK (node_type <> 'EXCEPTION' OR btrim(exception_code) <> '')
);

-- 时间线主查询：某一票的事件按发生时刻倒序
CREATE INDEX logistics_tracking_event_by_subject
  ON logistics_tracking_event(tenant_id, subject_id, occurred_at DESC);

-- 事件流列表（跨票）
CREATE INDEX logistics_tracking_event_by_received
  ON logistics_tracking_event(tenant_id, received_at DESC);

-- 异常节点筛选
CREATE INDEX logistics_tracking_event_by_exception
  ON logistics_tracking_event(tenant_id, occurred_at DESC)
  WHERE btrim(exception_code) <> '';

-- ---------------------------------------------------------------------------
-- 3. logistics_tracking_pull_task · 按承运商批量拉取（轮询任务）
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_tracking_pull_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- TRK-yyyyMMdd-000001
  task_no varchar(32) NOT NULL DEFAULT '',

  carrier_code varchar(64) NOT NULL CHECK (btrim(carrier_code) <> ''),
  channel_id uuid,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE RESTRICT,

  -- PENDING 待执行 · RUNNING 执行中 · SUCCEEDED 全部取回 ·
  -- PARTIAL 部分失败 · FAILED 整体失败 · NO_CHANNEL 承运商轨迹通道未接通
  status varchar(20) NOT NULL CHECK (status IN
    ('PENDING','RUNNING','SUCCEEDED','PARTIAL','FAILED','NO_CHANNEL')),
  trigger_source varchar(16) NOT NULL CHECK (trigger_source IN ('MANUAL','SCHEDULED')),

  requested_count int NOT NULL DEFAULT 0 CHECK (requested_count >= 0),
  succeeded_count int NOT NULL DEFAULT 0 CHECK (succeeded_count >= 0),
  duplicate_count int NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
  no_event_count int NOT NULL DEFAULT 0 CHECK (no_event_count >= 0),
  failed_count int NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
  inserted_event_count int NOT NULL DEFAULT 0 CHECK (inserted_event_count >= 0),

  started_at timestamptz,
  finished_at timestamptz,
  -- 失败 / 未接通的理由必须写明：NO_CHANNEL 不写理由就变成「拉了但没拉到」
  failure_reason varchar(400) NOT NULL DEFAULT '',

  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, task_no)
);

-- 承运商任务列表（「这个承运商最近拉了几次」）
CREATE INDEX logistics_tracking_pull_task_by_carrier
  ON logistics_tracking_pull_task(tenant_id, carrier_code, created_at DESC);

-- 任务列表默认排序：最近一轮在前
CREATE INDEX logistics_tracking_pull_task_by_created
  ON logistics_tracking_pull_task(tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. logistics_tracking_pull_task_item · 逐票结果
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_tracking_pull_task_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  -- 同上：逐票结果 append-only，RESTRICT 而不是让 CASCADE 去撞触发器
  FOREIGN KEY (tenant_id, task_id)
    REFERENCES logistics_tracking_pull_task(tenant_id, id) ON DELETE RESTRICT,

  subject_id uuid,
  waybill_no varchar(64) NOT NULL CHECK (btrim(waybill_no) <> ''),
  -- PULLED 取回并写入 · DUPLICATE 全部是重复事件 · NO_EVENT 承运商说没有轨迹
  -- · FAILED 这一票拉取失败 · SKIPPED 不在拉取范围内
  status varchar(20) NOT NULL CHECK (status IN
    ('PULLED','DUPLICATE','NO_EVENT','FAILED','SKIPPED')),
  inserted_event_count int NOT NULL DEFAULT 0 CHECK (inserted_event_count >= 0),
  duplicate_event_count int NOT NULL DEFAULT 0 CHECK (duplicate_event_count >= 0),
  detail varchar(400) NOT NULL DEFAULT '',

  UNIQUE (tenant_id, id),
  -- 同一轮里一个运单号只出现一次
  UNIQUE (tenant_id, task_id, waybill_no)
);

CREATE INDEX logistics_tracking_pull_task_item_by_task
  ON logistics_tracking_pull_task_item(tenant_id, task_id);

-- ---------------------------------------------------------------------------
-- 5. append-only：轨迹事件与拉取任务明细不可改写
-- ---------------------------------------------------------------------------
-- 轨迹是「当时它在哪」的举证，事后改写等于伪造物流史；
-- 拉取任务的逐票结果是那一次拉取当时的凭据，改它等于让审计对不上账。
CREATE OR REPLACE FUNCTION reject_logistics_tracking_event_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION
    'logistics_tracking_event 是 append-only 的举证凭据（承运商事件按事件 ID 去重后不可改写），'
    '要更正就追加一条新的承运商事件' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER logistics_tracking_event_append_only_trg
  BEFORE UPDATE OR DELETE ON logistics_tracking_event
  FOR EACH ROW EXECUTE FUNCTION reject_logistics_tracking_event_mutation();

CREATE OR REPLACE FUNCTION reject_logistics_tracking_pull_item_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION
    'logistics_tracking_pull_task_item 是那一轮拉取当时的凭据，不许改写'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER logistics_tracking_pull_task_item_append_only_trg
  BEFORE UPDATE OR DELETE ON logistics_tracking_pull_task_item
  FOR EACH ROW EXECUTE FUNCTION reject_logistics_tracking_pull_item_mutation();

-- ---------------------------------------------------------------------------
-- 6. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V141 同一套写法。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_tracking_subject',
    'logistics_tracking_event',
    'logistics_tracking_pull_task',
    'logistics_tracking_pull_task_item' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 7. 账号与授权（沿用 V1/V93/V129/V134/V136/V141 的条件建角色，保证可重复执行）
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
  logistics_tracking_subject, logistics_tracking_event,
  logistics_tracking_pull_task, logistics_tracking_pull_task_item TO channel_app;

-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE logistics_tracking_pull_task_no_seq TO channel_app;

-- 事件账本与拉取结果只许增，不许改 / 删（权限层，与触发器双保险）。
REVOKE UPDATE,DELETE ON logistics_tracking_event FROM channel_app;
REVOKE UPDATE,DELETE ON logistics_tracking_pull_task_item FROM channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V136 同口径）。
REVOKE ALL ON
  logistics_tracking_subject, logistics_tracking_event,
  logistics_tracking_pull_task, logistics_tracking_pull_task_item FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 8. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：轨迹查询属于「平台/承运商操作」，与 10.1.1.1 承运商、
-- 10.1.1.2 渠道配置、10.1.1.3 时效与限制、10.2.2.2 面单模板、10.3.2.1 交运批次
-- 同族，因此 identity 服务零改动，跨车道回归面 = 0。
