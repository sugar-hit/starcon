-- ===========================================================================
-- 10.3.2.1 交运批次（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.3.2.1 交运批次
--   - 职责：把已发货包裹按承运商与交运时间组批，交接给承运商并登记交接清单，
--           减少交接差错。
--   - 输入：包裹集合；承运商；交运时间。
--   - 输出：交运批次 + 交接清单 + 承运商回执。
--   - 关系：上游 = 9.4.1.1；下游 = 承运商接口、10.3.2.2。
--           **批次交接是责任分界点**：批次内包裹的丢失责任在承运商，
--           需要交接清单留证。
--
-- 归属 channel：蓝图卷 10 卷首「后端归属：channel 拥有物流商连接与轨迹」，
-- L2 表 10.3 物流跟踪 = channel。交运批次是「与物流商交互的那一跳」，
-- 落 channel 而不是 trade（trade 拥有运单与报关单据，10.4.2.1 海外仓连接
-- 同理落 channel，见 V93 文件头同款理由）。
--
-- ---------------------------------------------------------------------------
-- 一、批次交接是责任分界点 ⇒ 交接清单是法律凭据，不是展示用列表
-- ---------------------------------------------------------------------------
-- 蓝图加粗句是本模块的全部要害：**批次内包裹的丢失责任在承运商，
-- 需要交接清单留证**。所以 handover_manifest_item 不是「批次里有哪些件」的
-- 方便查询，而是**证明「哪几件、在什么状态、什么重量尺寸、几号几点、
-- 交给了谁」的唯一凭据**。落地方式是 append-only 双保险：
--
--   ① GRANT SELECT,INSERT，**刻意不给 UPDATE/DELETE**（权限层）；
--   ② BEFORE UPDATE OR DELETE 触发器（写入层）——照抄 9.4.1.1 的 shipment_event。
--
-- 单靠 ① 会被「把 GRANT 补回去」绕过，单靠 ② 会被「drop 触发器」绕过，
-- 两层都在才谈得上「事后不可静默改写」。测试两层各试一次。
--
-- 「批次一旦交接，其成员与交接内容不可被静默改写」还有第三道：
-- handover_batch 状态推进到 HANDED_OVER 之后，batch 的成员集合
-- （handover_manifest_item 的增删）与批次头都不许再动 —— 由
-- guard_handover_batch_frozen 触发器钉死。所以「先组批再悄悄把某件摘出去」
-- 这条路在 DB 层就走不通。
--
-- ---------------------------------------------------------------------------
-- 二、候选池来自 9.4.1.1 的 HANDED_OVER 单元，跨服务只经授权 HTTP API
-- ---------------------------------------------------------------------------
-- 上游 9.4.1.1 待发货（trade 服务，V124）已交付，其状态机是
-- PENDING → WAYBILL_REQUESTED → LABEL_PRINTED → **HANDED_OVER**，
-- 到 HANDED_OVER 为止**刻意不产生已发货**。
--
-- 本模块的候选池就是这些 HANDED_OVER 单元 —— 这正是蓝图「已发货包裹」的
-- 落点：9.4.1.1 的 HANDED_OVER = 已完成打包与面单、已交给本模块去交运。
--
-- ⚠️ AGENTS.md 模块边界纪律：禁止跨服务 SQL / FK / 共享视图。所以
-- **本迁移一条 SELECT 都不碰 trade 的库，也不建任何跨库外键或视图**。
-- 取数只经 trade 的**授权 HTTP API**（PendingShipmentPort，照抄 9.4.1.1
-- 自己的 PackingQueryPort 模式），转发的是**调用方自己的 bearer**：
-- 租户隔离是 channel 侧 RLS + trade 侧 RLS **两道**，不是本服务替对方
-- 决定租户。source_unit_id 是逻辑引用，一行必然能追溯回一个上游单元。
--
-- 投影而不是每次穿透调用，理由与 9.4.1.1 同款（写在这里以免日后被「简化」掉）：
--   ① 组批要按「承运商 + 交运时间」在**全量**候选上做 GROUP BY，
--      逐页穿透聚合会漏掉没翻到的页，分组结果本身不可信；
--   ② 交接清单是凭据，凭据不能因为上游一次超时就查不出来 —— 清单已落库，
--      就不必在事后追责时依赖上游还在不在。
--
-- ---------------------------------------------------------------------------
-- 三、组批规则：按承运商 + 交运时间，真 GROUP BY，一条批次不许跨承运商
-- ---------------------------------------------------------------------------
-- 蓝图输入句列了两个维度：「包裹集合；承运商；交运时间」，输出是
-- 「交运批次 + 交接清单」。所以：
--
--   * **一条批次不许跨承运商**（batch.carrier_code NOT NULL，
--     且每个候选单元的 carrier_code 必须等于批次承运商 —— 由
--     guard_handover_batch_carrier 触发器在写入路径上钉死）。
--     理由照抄 9.4.1.1：「按承运商分组是大促期效率关键，混在一起会导致
--     交接混乱」；交接现场按承运商分拣，混批必然漏发，而漏发的货
--     按本模块的加粗句是要算在承运商头上的 —— 批次混了，责任分界就废了。
--
--   * **交运时间是一个被 pin 住的业务时刻**（batch.handover_at），
--     不是墙上时钟。分档用 handover_bucket（按 handover_at 的日期），
--     服务层 GROUP BY 真算，**不拉全量在 Java 里 filter**。
--     测试同样 pin 住这个时刻，所以用例不会只在某个钟点通过。
--
--   * 批次头计数（parcel_count / total_weight_kg / unit_count）**不接受
--     调用方直接指定**：由服务层从逐件结果聚合，DB 侧 guard_handover_batch_counts
--     在写批次状态时**重算一遍逐件清单**并比对，对不上直接拒 ——
--     照抄 9.4.1.1 的 guard_shipment_batch_task_counts，绕过服务层直接写 SQL
--     同样被拒。
--
-- ---------------------------------------------------------------------------
-- 四、承运商回执：10.1 渠道全部未开发，所以只留**登记**入口不伪造对接
-- ---------------------------------------------------------------------------
-- 蓝图输出句点名「承运商回执」。但本轮集成头里：
--   * 10.1.1.1 承运商列表 / 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制 /
--     10.1.2.1 运费计算器 / 10.1.2.2 渠道比价 —— 台账确认**全部未开发**，
--     没有承运商连接、没有渠道配置，交接与取回执这一跳**没有真实对手方**；
--   * 因此本模块**不写死一个假承运商 API 返回假回执**。
--
-- 正确做法（AGENTS.md 外部适配器纪律：每个 vendor 独立包、只暴露统一接口）：
--   * 交接与回执走 **CarrierHandoverAdapter 端口**，vendor 包留空 ——
--     没有真承运商账号就不该有 vendor 实现，一个假的实现比没有更糟，
--     因为它会让「已交接给承运商」在数据上看起来发生过；
--   * 批次状态 DRAFT → **DISPATCH_PENDING**（已登记交接、等待真实通道接通）
--     → HANDED_OVER，这条中间态**如实表达「承运商还没接通」**，
--     而不是假装交接成功；
--   * capabilities 里 carrier_dispatch / carrier_receipt 两项显式
--     available=false 并点名 ownerModule=10.1.1.1。
--
-- ---------------------------------------------------------------------------
-- 五、运单号：10.2.1.2 未交付 ⇒ 如实留空并写明原因，绝不拿别的号冒充
-- ---------------------------------------------------------------------------
-- 蓝图输出句要「交接清单」，清单逐件必须有运单号。但 10.2.1.2 运单申请
-- 在本轮**并行开发、此刻尚未交付**，候选单元可能还没有运单号。
--
-- 9.4.1.1 已经踩过一次并明确禁止：**绝不拿出库单号冒充运单号**
-- （V124 shipment_waybill 是运单号的真实来源，出库单号是另一回事）。
-- 所以本表的处理是：
--   * waybill_no 允许为空；
--   * **为空时 waybill_gap_reason 必须写明原因**（CHECK 钉死），
--     原因取值来自上游真实读数（上游没签发 / 上游通道不可读），
--     不是我们自己编的「暂无」；
--   * 界面与详情里，「无运单号」是**一条如实说明的状态**，不是空白。
--
-- ---------------------------------------------------------------------------
-- 六、跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器 / 函数，零 ALTER 任何既有表**：
--   * 不碰 V1 channel_registry / V2 sync_runtime / V3 租户覆盖 /
--     V4 授权向导 / V93 海外仓连接族的任何一张表
--   * **不碰 trade 服务任何一张表**（一条 SELECT 都没有，更没有跨库 FK）
--   * FK 全部指向**本服务自己新建的表**，属同库引用
-- 六张表落在 handover_* 命名空间，与 warehouse_*（10.4.2.1 海外仓）
-- 并列而不混用 —— 那是「外部仓连接与观测」，与「把包裹交给承运商」是两件事。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：HNB-yyyyMMdd-000001（交运批次号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE handover_batch_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. handover_candidate · 候选包裹单元（上游 9.4.1.1 HANDED_OVER 的只读投影）
-- ---------------------------------------------------------------------------
-- 一行 = 一个已完成打包并已由 9.4.1.1 推到 HANDED_OVER 的出库单元。
-- 事实归 trade，本表是投影 + 组批候选池。
CREATE TABLE handover_candidate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 上游 9.4.1.1（trade 服务，逻辑引用，无跨服务 FK）===
  -- 一行必然能追溯回一个上游待发货单元：投影的「只读」体现在这里
  source_unit_id uuid NOT NULL,
  source_packing_order_id uuid NOT NULL,
  packed_at timestamptz NOT NULL,
  -- 上游推 HANDED_OVER 的时刻（交运候选资格的时间来源）
  handed_over_at timestamptz NOT NULL,
  parcel_count int NOT NULL CHECK(parcel_count > 0),
  total_weight_kg numeric(19,6) NOT NULL CHECK(total_weight_kg >= 0),

  -- === 出库 / 订单快照（不引用实时订单，改单不影响已交运的这一票）===
  warehouse_ref varchar(64) NOT NULL CHECK(btrim(warehouse_ref) <> ''),
  outbound_order_no varchar(64) NOT NULL CHECK(btrim(outbound_order_no) <> ''),
  sales_order_no varchar(64) NOT NULL CHECK(btrim(sales_order_no) <> ''),
  destination_country varchar(2) NOT NULL DEFAULT '',

  -- === 蓝图点名的分组维度之一：承运商 ===
  -- 候选必须有承运商才能组批：没选路由的单元交运不出去。
  -- 这与 9.4.1.1 的处理不同（那边 carrier_code 允许空是因为「待发货」
  -- 本来就还没选路由；本模块的候选池是**已交运**单元，没有承运商就没有交运对象）
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),
  carrier_channel varchar(24) NOT NULL CHECK(carrier_channel IN
    ('PLATFORM_INTEGRATED','THIRD_PARTY','SELF_PICKUP','POSTAL','FREIGHT_FORWARDER')),

  -- === 运单号（见文件头第五节：如实留空并写明原因）===
  -- 10.2.1.2 未交付 ⇒ 可能还没有运单号。**绝不拿出库单号冒充**。
  waybill_no varchar(128) NOT NULL DEFAULT '',
  waybill_gap_reason varchar(200) NOT NULL DEFAULT '',
  -- 缺口原因的结构化取值（与清单逐件同一套码表，界面才能把两种缺口画成同一种标记）
  waybill_gap_code varchar(32) NOT NULL DEFAULT '' CHECK(waybill_gap_code IN
    ('','UPSTREAM_NOT_ISSUED','UPSTREAM_UNREADABLE','MANUAL_OVERRIDE')),

  -- === 组批状态 ===
  -- PENDING  在候选池里，还没进任何批次
  -- BATCHED  已进入某个交运批次（batch_id 指明是哪一个）
  status varchar(12) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','BATCHED')),
  batch_id uuid,

  synced_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一个上游单元在同一个租户下只投影一次（重跑同步是幂等的）
  UNIQUE(tenant_id,source_unit_id),
  -- 进批次必须有批次；不在批次必须是 PENDING —— 这条「状态与批次同进同退」
  -- 是逐件计数的可信前提，否则「这个件到底算不算在批次里」永远答不清
  CHECK((status = 'BATCHED') = (batch_id IS NOT NULL)),
  -- 无运单号必须写明原因；有运单号就不该再挂一个缺口原因
  -- （照抄 V124 shipment_waybill 的同款纪律：答不出「为什么没有」就不该落库）
  CHECK(waybill_no <> '' OR btrim(waybill_gap_reason) <> ''),
  CHECK(waybill_no = '' OR waybill_gap_code = ''),
  CHECK(waybill_gap_code = '' OR btrim(waybill_gap_reason) <> '')
);

CREATE INDEX handover_candidate_pool
  ON handover_candidate(tenant_id,status,carrier_code,handed_over_at);
CREATE INDEX handover_candidate_batch
  ON handover_candidate(tenant_id,batch_id) WHERE batch_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. handover_batch · 交运批次（按承运商 + 交运时间组批）
-- ---------------------------------------------------------------------------
CREATE TABLE handover_batch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  batch_no varchar(32) NOT NULL CHECK(btrim(batch_no) <> ''),

  -- === 蓝图两个分组维度 ===
  -- 一条批次不许跨承运商（见文件头第三节，由触发器在写入路径上钉死）
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),
  carrier_channel varchar(24) NOT NULL CHECK(carrier_channel IN
    ('PLATFORM_INTEGRATED','THIRD_PARTY','SELF_PICKUP','POSTAL','FREIGHT_FORWARDER')),
  -- 交运时间是被 pin 住的业务时刻，不是墙上时钟
  handover_at timestamptz NOT NULL,
  -- 分档（handover_at 的日期）：看板按这个分档而不是按读取时刻
  handover_bucket date NOT NULL,

  -- === 批次头计数（不接受调用方直接指定，由逐件结果聚合 + DB 复查）===
  unit_count int NOT NULL DEFAULT 0 CHECK(unit_count >= 0),
  parcel_count int NOT NULL DEFAULT 0 CHECK(parcel_count >= 0),
  total_weight_kg numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_weight_kg >= 0),

  -- === 状态机 ===
  -- DRAFT             已组批、清单已生成，尚未交给承运商
  -- DISPATCH_PENDING  已登记交接，**等待承运商通道接通**（10.1 未交付时的真实状态）
  -- HANDED_OVER       承运商已确认接收 —— 责任分界点已过
  -- CANCELLED         作废（退回候选池）
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','DISPATCH_PENDING','HANDED_OVER','CANCELLED')),
  status_detail varchar(600) NOT NULL DEFAULT '',

  -- === 谁在什么时候登记的交接（凭据必须有主体）===
  dispatched_by_membership_id uuid,
  dispatched_at timestamptz,
  -- 承运商回执（10.1 未交付 ⇒ 由人工登记带回执，不是系统对接取回）
  receipt_ref varchar(128) NOT NULL DEFAULT '',
  receipt_status varchar(16) NOT NULL DEFAULT '' CHECK(receipt_status IN
    ('','ACCEPTED','ACCEPTED_WITH_DISCREPANCY','REJECTED')),
  receipt_detail varchar(600) NOT NULL DEFAULT '',
  receipt_recorded_by_membership_id uuid,
  receipt_recorded_at timestamptz,

  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,batch_no),
  -- 分档必须与交运时刻的日期一致，否则看板分组与实际交运时间对不上
  CHECK(handover_bucket = (handover_at at time zone 'UTC')::date),
  -- 已交接必须有人有时刻，否则「交给谁」答不出来 = 凭据不成立
  CHECK(status <> 'HANDED_OVER' OR (dispatched_by_membership_id IS NOT NULL
    AND dispatched_at IS NOT NULL)),
  -- 有回执状态就必须有回执凭据与登记主体
  CHECK(receipt_status = '' OR (btrim(receipt_ref) <> ''
    AND receipt_recorded_by_membership_id IS NOT NULL AND receipt_recorded_at IS NOT NULL))
);

CREATE INDEX handover_batch_list
  ON handover_batch(tenant_id,status,carrier_code,handover_at DESC);
CREATE INDEX handover_batch_bucket
  ON handover_batch(tenant_id,handover_bucket,carrier_code);

-- 批次 ↔ 候选单元的同库引用（两个 FK 都指向本服务自己的表）
ALTER TABLE handover_candidate
  ADD CONSTRAINT handover_candidate_batch_fk
  FOREIGN KEY(tenant_id,batch_id) REFERENCES handover_batch(tenant_id,id);

-- ---------------------------------------------------------------------------
-- 3. handover_manifest_item · 交接清单逐件（🔴 法律凭据，append-only）
-- ---------------------------------------------------------------------------
-- 「批次交接是责任分界点：批次内包裹的丢失责任在承运商，需要交接清单留证。」
-- 这一张表就是那份「证」。逐件记录：交接时刻、包裹标识、运单号（有则记，
-- 无则如实留空并写明原因）、重量尺寸、经手人。
CREATE TABLE handover_manifest_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  candidate_id uuid NOT NULL,
  -- 逐包裹标识（一票多箱时清单是逐箱的，不是逐单元的 —— 「打单/交接失败」
  -- 恰恰发生在某个箱子上，并成一条就再也说不出是哪箱没交）
  parcel_no int NOT NULL CHECK(parcel_no > 0),
  parcel_ref varchar(64) NOT NULL DEFAULT '',

  -- === 交接时刻与经手人（凭据必须有主体与时刻）===
  handover_at timestamptz NOT NULL,
  handed_by_membership_id uuid NOT NULL,

  -- === 运单号（见文件头第五节）===
  waybill_no varchar(128) NOT NULL DEFAULT '',
  -- 没有运单号时必须写明原因；**绝不出库单号冒充**
  waybill_gap_reason varchar(200) NOT NULL DEFAULT '',
  -- 缺运单号的原因取值来自上游真实读数，不由本模块编造
  waybill_gap_code varchar(32) NOT NULL DEFAULT '' CHECK(waybill_gap_code IN
    ('','UPSTREAM_NOT_ISSUED','UPSTREAM_UNREADABLE','MANUAL_OVERRIDE')),

  -- === 重量尺寸（numeric 禁浮点，与 DB 的 numeric(19,6) 对齐）===
  gross_weight_kg numeric(19,6) NOT NULL CHECK(gross_weight_kg >= 0),
  length_mm numeric(19,6) NOT NULL DEFAULT 0 CHECK(length_mm >= 0),
  width_mm numeric(19,6) NOT NULL DEFAULT 0 CHECK(width_mm >= 0),
  height_mm numeric(19,6) NOT NULL DEFAULT 0 CHECK(height_mm >= 0),

  -- === 交接现场逐件备注（异常件初筛，见 10.3.1.2 未交付声明）===
  remark varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES handover_batch(tenant_id,id),
  FOREIGN KEY(tenant_id,candidate_id) REFERENCES handover_candidate(tenant_id,id),
  -- 同一批次里同一件包裹只登记一次（重跑组批不会凭空多出一行「交了两遍」）
  UNIQUE(tenant_id,batch_id,candidate_id,parcel_no),
  -- 无运单号必须写明原因；**有运单号就不许再挂一个缺口原因**
  CHECK(waybill_no <> '' OR btrim(waybill_gap_reason) <> ''),
  CHECK(waybill_no = '' OR waybill_gap_code = ''),
  CHECK(waybill_gap_code = '' OR btrim(waybill_gap_reason) <> '')
);

-- 清单读取路径：按批次读全部逐件（这是追责时最常走的一条）
CREATE INDEX handover_manifest_batch
  ON handover_manifest_item(tenant_id,batch_id,parcel_no);
CREATE INDEX handover_manifest_candidate
  ON handover_manifest_item(tenant_id,candidate_id);

-- ---------------------------------------------------------------------------
-- 4. handover_event · 事件流（append-only 时间线）
-- ---------------------------------------------------------------------------
CREATE TABLE handover_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  batch_id uuid,
  candidate_id uuid,
  event_kind varchar(40) NOT NULL CHECK(btrim(event_kind) <> ''),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  detail varchar(800) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES handover_batch(tenant_id,id),
  FOREIGN KEY(tenant_id,candidate_id) REFERENCES handover_candidate(tenant_id,id),
  -- 一个事件至少挂在批次或候选之一上，否则时间线上会出现无主事件
  CHECK(batch_id IS NOT NULL OR candidate_id IS NOT NULL)
);

CREATE INDEX handover_event_batch
  ON handover_event(tenant_id,batch_id,occurred_at);
CREATE INDEX handover_event_candidate
  ON handover_event(tenant_id,candidate_id,occurred_at);

-- ---------------------------------------------------------------------------
-- 5. 触发器：把「不变量」钉在写入路径上（绕过服务层写 SQL 同样被拒）
-- ---------------------------------------------------------------------------

-- (a) 🔴 交接清单 append-only：这一道挡「事后静默改写交接内容」
--     （照抄 9.4.1.1 的 shipment_event 做法）。
CREATE OR REPLACE FUNCTION reject_handover_manifest_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION
    'handover_manifest_item 是交运交接的法律凭据（批次交接后包裹丢失责任在承运商），不允许修改或删除'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER handover_manifest_append_only_trg
  BEFORE UPDATE OR DELETE ON handover_manifest_item
  FOR EACH ROW EXECUTE FUNCTION reject_handover_manifest_mutation();

-- (b) 事件流是凭据：连运行角色都不给改删（与 (a) 同款双保险）
CREATE OR REPLACE FUNCTION reject_handover_event_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'handover_event 是 append-only 的凭据，不允许修改或删除'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER handover_event_append_only_trg
  BEFORE UPDATE OR DELETE ON handover_event
  FOR EACH ROW EXECUTE FUNCTION reject_handover_event_mutation();

-- (c) 批次状态只能单向推进：DRAFT → DISPATCH_PENDING → HANDED_OVER，
--     DRAFT/DISPATCH_PENDING → CANCELLED。HANDED_OVER 是终态。
--     刻意**不**用「NEW.status 与 OLD.status 不同就放行」—— 那会把「改备注」
--     也一起放行，退化成没有闸门。这里按白名单逐对校验。
CREATE OR REPLACE FUNCTION guard_handover_batch_state() RETURNS trigger AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  IF OLD.status IN ('DRAFT','DISPATCH_PENDING') AND NEW.status = 'CANCELLED'
     THEN allowed := true; END IF;
  IF OLD.status = 'DRAFT' AND NEW.status = 'DISPATCH_PENDING'
     THEN allowed := true; END IF;
  IF OLD.status = 'DISPATCH_PENDING' AND NEW.status = 'HANDED_OVER'
     THEN allowed := true; END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION
      'handover_batch % 状态不可从 % 推进到 %（HANDED_OVER 是责任分界终态，不再变动）',
      OLD.batch_no, OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_handover_batch_state_trg
  BEFORE UPDATE ON handover_batch
  FOR EACH ROW EXECUTE FUNCTION guard_handover_batch_state();

-- (d) 🔴 一条批次不许跨承运商 —— 在**写入路径**上钉死，不靠服务层自觉。
--     候选的 carrier_code 必须等于批次的 carrier_code。
--     照抄 9.4.1.1 的理由：「按承运商分组是大促期效率关键，混在一起会导致
--     交接混乱」；本模块更硬 —— 批次混了，「丢失责任在承运商」的分界就废了。
CREATE OR REPLACE FUNCTION guard_handover_candidate_carrier() RETURNS trigger AS $$
DECLARE batch_carrier varchar(64); DECLARE bno varchar(32);
BEGIN
  IF NEW.batch_id IS NULL THEN RETURN NEW; END IF;
  SELECT carrier_code, batch_no INTO batch_carrier, bno FROM handover_batch
   WHERE tenant_id = NEW.tenant_id AND id = NEW.batch_id;
  IF batch_carrier IS NULL THEN
    RAISE EXCEPTION '交运批次不存在（tenant % / batch %）', NEW.tenant_id, NEW.batch_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.carrier_code <> batch_carrier THEN
    RAISE EXCEPTION
      '一条交运批次不许跨承运商：批次 %s 的承运商是 %s，而该件的承运商是 %s',
      bno, batch_carrier, NEW.carrier_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_handover_candidate_carrier_trg
  BEFORE INSERT OR UPDATE OF batch_id, carrier_code ON handover_candidate
  FOR EACH ROW EXECUTE FUNCTION guard_handover_candidate_carrier();

-- (e) 🔴 批次头计数必须与逐件清单实际对得上 —— **不接受调用方直接指定**。
--     服务层从逐件结果聚合，DB 这里重算一遍并比对；绕过服务层直接写 SQL
--     把计数改成好看的数字，同样在这里被拒（照抄 9.4.1.1 的
--     guard_shipment_batch_task_counts）。
CREATE OR REPLACE FUNCTION guard_handover_batch_counts() RETURNS trigger AS $$
DECLARE m_count int; DECLARE m_weight numeric(19,6); DECLARE u_count int;
BEGIN
  -- 只有带清单的批次才复查（DRAFT 是组批刚建、清单还没写进去的瞬间）
  SELECT count(*), COALESCE(sum(gross_weight_kg), 0)
    INTO m_count, m_weight
    FROM handover_manifest_item
   WHERE tenant_id = NEW.tenant_id AND batch_id = NEW.id;

  IF m_count = 0 THEN
    IF NEW.parcel_count <> 0 OR NEW.unit_count <> 0 OR NEW.total_weight_kg <> 0 THEN
      RAISE EXCEPTION
        '交运批次 %s 还没有交接清单，却带了计数（件 % / 单元 % / 重量 %）',
        NEW.batch_no, NEW.parcel_count, NEW.unit_count, NEW.total_weight_kg
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  SELECT count(DISTINCT candidate_id) INTO u_count
    FROM handover_manifest_item
   WHERE tenant_id = NEW.tenant_id AND batch_id = NEW.id;

  IF NEW.parcel_count <> m_count OR NEW.unit_count <> u_count
     OR NEW.total_weight_kg <> m_weight THEN
    RAISE EXCEPTION
      '交运批次 %s 的头计数（件 % / 单元 % / 重量 %）与逐件清单实际（件 % / 单元 % / 重量 %）不符',
      NEW.batch_no, NEW.parcel_count, NEW.unit_count, NEW.total_weight_kg,
      m_count, u_count, m_weight USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_handover_batch_counts_trg
  BEFORE INSERT OR UPDATE ON handover_batch
  FOR EACH ROW EXECUTE FUNCTION guard_handover_batch_counts();

-- (f) 🔴 「批次一旦交接，其成员与交接内容不可被静默改写」。
--     冻结点取 **HANDED_OVER** 而不是 DISPATCH_PENDING，理由是一条具体的业务事实：
--     货权/责任真正转到承运商手上是在**承运商确认接收**那一刻。
--     DISPATCH_PENDING 时包裹还在仓库现场（承运商通道尚未接通，见文件头第四节），
--     此时组错了仍要能作废重组 —— 拦下它只会逼现场把错批次硬着头皮交出去。
--     而且「已经登记过交接」这件事**不会因为作废而消失**：它留在 append-only 的
--     handover_event 里（「谁在几点登记过交接又作废了」在追责时正是要查的那条）。
--
--     候选单元一旦进入已交接（HANDED_OVER）的批次：
--       * 不许换到别的批次、也不许退回 PENDING、也不许被删。
--     闸门看的是**它所属批次**的状态，所以要先按 OLD.batch_id 取一次批次。
--     （注意本触发器挂在 handover_candidate 上，候选行自己没有 batch_no 列。）
CREATE OR REPLACE FUNCTION guard_handover_candidate_frozen() RETURNS trigger AS $$
DECLARE st varchar(16); DECLARE bno varchar(32);
BEGIN
  -- ⚠️ BEFORE UPDATE 触发器**必须返回 NEW**：返回 OLD 等于把这行更新**静默丢弃**，
  --    触发器会退化成「看起来在拦、实际什么都不做」。DELETE 才返回 OLD。
  IF TG_OP = 'UPDATE' THEN
    IF OLD.batch_id IS NULL THEN RETURN NEW; END IF;
    SELECT status, batch_no INTO st, bno FROM handover_batch
     WHERE tenant_id = OLD.tenant_id AND id = OLD.batch_id;
    IF st IS NULL OR st <> 'HANDED_OVER' THEN RETURN NEW; END IF;
    -- 顺序有讲究：先判「退回候选池」再判「换批次」。一次退回**同时**改了
    -- batch_id 与 status，只看 batch_id 会报成「换到别的批次」—— 那是更泛的
    -- 症状，把真正的原因藏起来了。先报最具体的那条。
    IF NEW.status = 'PENDING' AND OLD.status = 'BATCHED' THEN
      RAISE EXCEPTION
        '交运批次 %s 已交接，不允许把件 % 退回候选池',
        bno, OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.batch_id IS DISTINCT FROM OLD.batch_id THEN
      RAISE EXCEPTION
        '交运批次 %s 已交接，不允许把件 % 换到别的批次（交接内容不可静默改写）',
        bno, OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  -- DELETE：成员不可被物理移出
  IF OLD.batch_id IS NULL THEN RETURN OLD; END IF;
  SELECT status, batch_no INTO st, bno FROM handover_batch
   WHERE tenant_id = OLD.tenant_id AND id = OLD.batch_id;
  IF st IS NULL OR st <> 'HANDED_OVER' THEN RETURN OLD; END IF;
  RAISE EXCEPTION
    '交运批次 %s 已交接（责任在承运商），其成员不允许被移出（交接内容不可静默改写）',
    bno USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_handover_candidate_frozen_trg
  BEFORE UPDATE OR DELETE ON handover_candidate
  FOR EACH ROW EXECUTE FUNCTION guard_handover_candidate_frozen();

CREATE OR REPLACE FUNCTION guard_handover_batch_delete() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'HANDED_OVER' THEN
    RAISE EXCEPTION
      '交运批次 %s 已交接（责任在承运商），是「丢失责任在承运商」的凭据，不允许删除',
      OLD.batch_no USING ERRCODE = 'check_violation';
  END IF;
  RETURN OLD;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_handover_batch_delete_trg
  BEFORE DELETE ON handover_batch
  FOR EACH ROW EXECUTE FUNCTION guard_handover_batch_delete();

-- (g) 交接清单的批次必须已登记交接才有意义：清单只往 DRAFT 批次写，
--     DRAFT 之后（新 DISPATCH_PENDING）的批次不许再往里加件 ——
--     否则「交接清单」的时间线会与「登记交接的时刻」对不上。
CREATE OR REPLACE FUNCTION guard_handover_manifest_batch_open() RETURNS trigger AS $$
DECLARE st varchar(16);
BEGIN
  SELECT status INTO st FROM handover_batch
   WHERE tenant_id = NEW.tenant_id AND id = NEW.batch_id;
  IF st IS NULL THEN
    RAISE EXCEPTION '交运批次不存在（tenant % / batch %）', NEW.tenant_id, NEW.batch_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF st <> 'DRAFT' THEN
    RAISE EXCEPTION
      '交运批次当前状态 % 已不允许再写交接清单（清单时间线必须早于登记交接）', st
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_handover_manifest_batch_open_trg
  BEFORE INSERT ON handover_manifest_item
  FOR EACH ROW EXECUTE FUNCTION guard_handover_manifest_batch_open();

-- ---------------------------------------------------------------------------
-- 6. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 同一套写法。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'handover_candidate',
    'handover_batch',
    'handover_manifest_item',
    'handover_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 7. 账号与授权（沿用 V1/V93 的条件建角色，保证迁移可重复执行）
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
  handover_candidate, handover_batch TO channel_app;

-- 🔴 交接清单与事件流是凭据：只给 SELECT,INSERT，**刻意不给 UPDATE/DELETE**。
-- 授权在前、收回在后 —— 反过来写会被上面的全量 GRANT 覆盖回去。
-- 双保险：即使有人把 (a)/(b) 的触发器删了，权限层仍然改不动已交接的凭据。
GRANT SELECT,INSERT ON handover_manifest_item, handover_event TO channel_app;
REVOKE UPDATE,DELETE ON handover_manifest_item FROM channel_app;
REVOKE UPDATE,DELETE ON handover_event FROM channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 同口径）。
REVOKE ALL ON
  handover_candidate, handover_batch,
  handover_manifest_item, handover_event FROM starcon_app;

-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE handover_batch_no_seq TO channel_app;

-- ---------------------------------------------------------------------------
-- 8. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：交运批次属于「平台/承运商操作」，复用 identity
-- V4 已有的 channel.operation.{read,create,update,delete}，因此 identity 服务
-- 零改动，跨车道回归面 = 0。
