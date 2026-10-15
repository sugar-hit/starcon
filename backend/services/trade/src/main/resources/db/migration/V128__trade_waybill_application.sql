-- ===========================================================================
-- 10.2.1.2 运单申请
-- ===========================================================================
-- 蓝图原文（10-logistics-and-freight.md:132）：
--   职责：向承运商申请运单号：传入收寄信息、包裹明细、申报信息，支持批量申请、
--         失败重试、接口限流控制。
--   输入：待发货包裹；渠道；申报信息（3.7 报关属性）；idempotency_key。
--   输出：运单号 + 面单数据；失败原因。
--   关系：上游 = 9.4.1.1、8.3.4.2；下游 = 10.2.2.1、10.3.1.1。
--         **批量申请要按渠道限流排队**，否则触发接口限流影响整批。
--
-- ---------------------------------------------------------------------------
-- 零、本模块的边界与上游缺口（读迁移前必看）
-- ---------------------------------------------------------------------------
-- 上游盘点（台账核实 + 本 worktree grep 复核，两处都对得上）：
--
--   * 9.4.1.1 待发货 —— **同服务同库**，V124 六张表。台账明确记录
--     「本模块不调用承运商下单接口（属 10 物流，未交付）」，
--     「运单单号由承运商回执登记（/waybill-acks）」—— **本模块就是那个缺口**。
--     同一服务同库可以直接读 shipment_pending_unit / shipment_pending_parcel，
--     但**每张表只有一个写入所有者**：V124 的六张表由 9.4.1.1 写，本迁移
--     **只读它们、不 ALTER 它们、不在上面加触发器**，本模块的运单申请事实
--     全部另起自己的表。跨车道 DDL 回归面 = 0。
--
--   * 8.3.4.2 面单与标签打印 —— **跨服务**（warehouse）。跨库 SQL / 跨服务 FK /
--     共享视图都被 AGENTS.md 禁止，所以面单模板走 warehouse 的授权 HTTP API
--     （CarrierLabelTemplatePort，形态照抄 PackingQueryPort）。本模块只把
--     template_ref 记在申请上，**不自己渲染 ZPL**，不自建第二个打印引擎。
--
--   * 3.7.1.1 HS 编码与申报要素、3.7.1.2 目的地报关版本 —— **跨服务**（catalog）。
--     同上：只能经授权只读 API 读（CustomsAttributePort），不直连对方库。
--
-- ⚠️ 10.1 物流渠道**全部未交付**（10.1.1.1 承运商列表 / 10.1.1.2 渠道配置 /
--    10.1.1.3 时效与限制 / 10.1.2.1 运费计算器 / 10.1.2.2 渠道比价，台账全「未开发」）。
--    也就是说：**没有承运商连接配置、没有渠道计费与限制配置**。而本模块的
--    「向承运商申请运单号」与「按渠道限流排队」两条职责都依赖它。
--
--    正确做法（本迁移的形状）：把承运商与渠道建模成**可登记、可扩展的本地事实**
--    （logistics_carrier）+ **明确的适配器端口**（CarrierWaybillPort，每个 vendor
--    独立包、只暴露统一接口），而**不是**凭空造一个「已接通的承运商」或写死一个
--    假 API 返回运单号。未接通的渠道在 capabilities 里 available=false 并点名
--    ownerModule=10.1.1.1。真实承运商连接由 channel 服务的 10.1 提供；在此之前，
--    本模块能给出的最诚实答案是「这条渠道未接通」，而不是一个编出来的运单号。
--
--    因此本迁移刻意**不造**任何运单号：logistics_waybill_request.waybill_no
--    只能由**适配器真实返回**时写入，测试里也不例外（测试用的替身是承运商
--    侧 HTTP 替身，与 9.4.1.1 用 warehouse HTTP 替身同一手法，不是数据替身）。
--
-- 被本迁移约束到的既有表 = 无（只读引用 V124，不 ALTER、不加触发器）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 单号发号器：WAR-yyyyMMdd-000001（运单申请单号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE waybill_request_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 2. logistics_carrier · 承运商与渠道登记（**本地事实，可登记可扩展**）
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么自己建这张表而不是等 10.1.1.1：
--   10.1.1.1「承运商列表」在 **channel** 服务（蓝图卷 10「channel 拥有物流商
--   连接与轨迹」），而本模块在 trade 拥有运单文档。跨服务不得建 FK / 视图，
--   所以 trade 侧要能**独立登记**本模块要用的承运商代码与渠道口径，
--   等 10.1 接通后再由 10.1 的连接表喂进来（届时这张表降级为本地登记镜像）。
--
--   此刻它只有**登记**语义：**没有连接配置**（endpoint / 凭据一律不落本表，
--   凭据归 secret manager，见 AGENTS.md「安全」一节）。connected=false 的承运商
--   在 capabilities 里如实标 available=false，并点名 ownerModule=10.1.1.1。
CREATE TABLE logistics_carrier (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- 承运商代码：与 V124 shipment_pending_unit.carrier_code **同一口径**
  -- （同一张待发货单上的两种拼法会让「按承运商排队」算错，见第 4 节注释）
  code varchar(64) NOT NULL CHECK(btrim(code) <> ''),
  display_name varchar(200) NOT NULL CHECK(btrim(display_name) <> ''),

  -- 承运渠道：与 V124 的 carrier_channel 同一词表（同一个枚举，不同服务各存一份）
  carrier_channel varchar(24) NOT NULL CHECK(carrier_channel IN
    ('PLATFORM_INTEGRATED','THIRD_PARTY','SELF_PICKUP','POSTAL','FREIGHT_FORWARDER')),

  -- 适配器键：决定用哪个 vendor 适配器（CarrierWaybillPort 的实现）。
  -- 只登记**键名**，不登记 endpoint / 账号 / 密钥 —— 后者由 10.1.1.1 持有。
  adapter_key varchar(64) NOT NULL CHECK(btrim(adapter_key) <> ''),

  -- 是否已接通。**默认 false**：10.1 未交付，没有任何渠道真正接通。
  -- 手工置 true 也不等于能下单 —— 真正能否下单由适配器端口在运行时判定，
  -- 两道口径都要，界面才不会把「登记过」显示成「能出单」。
  connected boolean NOT NULL DEFAULT false,
  unavailable_reason varchar(400) NOT NULL DEFAULT '',

  -- 限流口径（蓝图：「批量申请要按渠道限流排队，否则触发接口限流影响整批」）。
  -- 未登记时用迁移内置默认值（见第 5 节的 waybill_channel_rate_limit 视图），
  -- **默认值在本文件里是单一真相**，不散落成 Java 常量。
  rate_limit_per_window int NOT NULL DEFAULT 20 CHECK(rate_limit_per_window > 0),
  rate_window_seconds int NOT NULL DEFAULT 60 CHECK(rate_window_seconds > 0),
  -- 退避基数秒：失败重试的下一次等待 = base * 2^(已失败次数 - 1)
  retry_backoff_base_seconds int NOT NULL DEFAULT 60 CHECK(retry_backoff_base_seconds > 0),
  max_retry_attempts int NOT NULL DEFAULT 3 CHECK(max_retry_attempts > 0),

  remark varchar(400) NOT NULL DEFAULT '',
  registered_by_membership_id uuid NOT NULL,
  registered_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  -- 同一租户下承运商代码唯一：排队窗口按承运商分组，重复代码会把两票货并进一条队列
  UNIQUE(tenant_id,code),
  -- 没接通必须写明原因，否则界面上只有一片灰色而没人知道该找谁
  CHECK(connected OR btrim(unavailable_reason) <> '')
);

-- 「哪些渠道可下单」是本模块最高频的读，按 tenant + 通不通切
CREATE INDEX logistics_carrier_lookup ON logistics_carrier(tenant_id,connected);
CREATE INDEX logistics_carrier_channel ON logistics_carrier(tenant_id,carrier_channel,connected);

-- ---------------------------------------------------------------------------
-- 3. logistics_waybill_request · 运单申请（**本模块的事实**，逐包裹一行）
-- ---------------------------------------------------------------------------
-- 一行 = **一个包裹的一张运单申请**。蓝图输入句是「待发货**包裹**」，
-- 而 9.4.1.1 的批量对象也是包裹（shipment_pending_parcel），二者对齐。
--
-- ⚠️ 为什么逐包裹而不是逐出库单：一张出库单可能有多个包裹、不同重量尺寸，
--   承运商对每个包裹单独出号（面单也是逐件打印）。逐出库单申请会让第二个包裹
--   拿不到自己的面单 —— 这是运单申请最常见的实现偷懒。
CREATE TABLE logistics_waybill_request (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- WAR-yyyyMMdd-000001
  request_no varchar(32) NOT NULL,

  -- ⚠️ 幂等键是**输入契约**（蓝图输入句第四项），不是可选优化：
  --   重复提交同一次申请**不得**产生第二张运单。DB 层用唯一索引钉死，
  --   并有反例测试证明它真的挡住了（直接 INSERT 第二行会撞 23505）。
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key) <> ''),

  -- === 上游 9.4.1.1（同库逻辑引用；V124 是唯一写入者，本模块只读）===
  -- 逻辑引用而非 FK：V124 是别的模块拥有的表，同服务内不跨模块建 FK
  -- （9.6.1.3 的 V126 只在**自己新增**的表之间建同库 FK，不挂到别的模块的表）。
  -- 真实约束由服务层在事务里核（找不到包裹就 404，不用 500）。
  pending_unit_id uuid NOT NULL,
  pending_parcel_id uuid NOT NULL,
  outbound_order_no varchar(64) NOT NULL CHECK(btrim(outbound_order_no) <> ''),
  sales_order_no varchar(64) NOT NULL CHECK(btrim(sales_order_no) <> ''),
  warehouse_ref varchar(64) NOT NULL CHECK(btrim(warehouse_ref) <> ''),

  -- === 承运商与渠道（与 V124 同一口径，不另起一套）===
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),
  carrier_channel varchar(24) NOT NULL CHECK(carrier_channel IN
    ('PLATFORM_INTEGRATED','THIRD_PARTY','SELF_PICKUP','POSTAL','FREIGHT_FORWARDER')),

  -- === 收寄信息（申请当时的事实快照，不引用实时地址）===
  ship_from_name varchar(200) NOT NULL CHECK(btrim(ship_from_name) <> ''),
  ship_from_country varchar(2) NOT NULL CHECK(ship_from_country <> ''),
  ship_to_name varchar(200) NOT NULL CHECK(btrim(ship_to_name) <> ''),
  ship_to_country varchar(2) NOT NULL CHECK(ship_to_country <> ''),
  ship_to_address_line varchar(400) NOT NULL DEFAULT '',
  ship_to_city varchar(120) NOT NULL DEFAULT '',
  ship_to_postal_code varchar(32) NOT NULL DEFAULT '',

  -- === 包裹明细（重量与尺寸取 9.4.1.1 投影的实测读数，快照不重算）===
  gross_weight_kg numeric(19,6) NOT NULL CHECK(gross_weight_kg >= 0),
  length_mm numeric(19,6) NOT NULL CHECK(length_mm >= 0),
  width_mm numeric(19,6) NOT NULL CHECK(width_mm >= 0),
  height_mm numeric(19,6) NOT NULL CHECK(height_mm >= 0),

  -- === 申报信息（3.7 报关属性；明细在 logistics_customs_declaration_item）===
  declared_currency char(3) NOT NULL,
  -- 申报价值合计：NUMERIC(19,4) + BigDecimal，**禁浮点**（AGENTS.md）
  declared_value_total numeric(19,4) NOT NULL DEFAULT 0 CHECK(declared_value_total >= 0),
  -- 申报信息取自 catalog 3.7 的哪个口径（留证据，不是装饰列）
  customs_source varchar(64) NOT NULL DEFAULT '',

  -- === 面单模板（8.3.4.2，跨服务，只记引用不自己渲染）===
  label_template_ref varchar(128) NOT NULL DEFAULT '',

  -- === 状态机：QUEUED → SUBMITTING → ISSUED / FAILED ===
  --   QUEUED      已入队（限流排队中等窗口）
  --   SUBMITTING  已取出、正在调承运商
  --   ISSUED      **承运商真实返回了运单号**（唯一能进这个态的路径）
  --   FAILED      失败（失败原因必须写明，可重试）
  --   CANCELLED   作废（还没出号的才允许作废；已出号的要 10.2.1.3 取消）
  status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK(status IN
    ('QUEUED','SUBMITTING','ISSUED','FAILED','CANCELLED')),

  waybill_no varchar(128) NOT NULL DEFAULT '',
  label_format varchar(24) NOT NULL DEFAULT '',
  label_uri varchar(400) NOT NULL DEFAULT '',
  issued_at timestamptz,
  failed_at timestamptz,
  -- 失败原因（蓝图输出第二条：「失败原因」）。NOT NULL 默认空 = 还没失败。
  failure_code varchar(48) NOT NULL DEFAULT '',
  failure_reason varchar(400) NOT NULL DEFAULT '',
  cancel_reason varchar(400) NOT NULL DEFAULT '',

  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  -- 已重试次数；到达承运商登记的上限就停，不再空转（retryable 不等于无限重试）
  retry_count int NOT NULL DEFAULT 0 CHECK(retry_count >= 0),
  -- 下一次允许提交的时刻（退避窗口，限流排队的一部分）
  not_before timestamptz,

  requested_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_no),
  -- ★ 幂等键的唯一索引：**模块最不可让步的一条 DB 闸门**
  --   注意它不是 (tenant_id, idempotency_key) 之外再加条件索引 —— 就是完整唯一。
  --   没有它，「网络重试」会把同一票货出两张运单，承运商按两张计费。
  UNIQUE(tenant_id,idempotency_key),

  -- 出号必须带凭据；没出号不许有单号（防止界面显示一个凭空出现的运单号）
  CHECK((status = 'ISSUED') = (btrim(waybill_no) <> '')),
  CHECK(status <> 'ISSUED' OR issued_at IS NOT NULL),
  CHECK(status <> 'FAILED' OR btrim(failure_reason) <> ''),
  CHECK(status <> 'CANCELLED' OR btrim(cancel_reason) <> ''),
  -- 出过号的申请不许被改成别的态（凭据不可回退）
  CHECK(attempt_count >= retry_count)
);

-- 队列取件：按渠道 + 可提交时刻切出「现在轮到谁了」
CREATE INDEX waybill_request_queue
  ON logistics_waybill_request(tenant_id,carrier_code,status,not_before);
-- 同一个待发货包裹**同一承运商**只能有一张有效申请：
-- 重复申请 = 重复出号 = 重复计费。已 CANCELLED 的不占位（可以重新申请一张）。
-- 条件唯一索引（而不是全表唯一）：作废后要能重新申请一张，
-- 而「还在 QUEUED/SUBMITTING/ISSUED/FAILED」的那张必须挡住第二次。
CREATE UNIQUE INDEX waybill_request_parcel_guard
  ON logistics_waybill_request(tenant_id,pending_parcel_id,carrier_code)
  WHERE status <> 'CANCELLED';
CREATE INDEX waybill_request_tenant_created
  ON logistics_waybill_request(tenant_id,created_at);
CREATE INDEX waybill_request_parcel ON logistics_waybill_request(tenant_id,pending_parcel_id);
CREATE INDEX waybill_request_status ON logistics_waybill_request(tenant_id,status);

-- ---------------------------------------------------------------------------
-- 4. logistics_waybill_queue_entry · 限流排队（**可见的持久化队列**）
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么不是同步阻塞循环：蓝图那句「批量申请要按渠道限流排队」的意思是
--   一次提交**全部入队**、然后按渠道窗口逐条放行。把 200 个包裹的申请放进
--   一个 for 循环里顺序调承运商，第 3 个就会撞限流，而且**整个 HTTP 请求**
--   被占住 —— 大促期这一条请求会超时 60 秒，现场只能重复点，点一次多一单。
--
--   所以队列必须是**一行一条**的持久化事实：看得见（谁在排第几位）、
--   可重试（失败单独放回）、可解释（放行时刻是算出来的，不是猜的）。
--
-- ⚠️ **一条队列不许跨承运商**（照抄 9.4.1.1 已验证的做法）：蓝图那句「按承运商
--   分组是大促期效率关键，混在一起会导致交接混乱」在这里同样是承运商限流的
--   前提 —— A 家的配额不能拿来放 B 家的件。CHECK 钉住。
CREATE TABLE logistics_waybill_queue_entry (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),

  -- 队列状态：WAITING 等窗口 → READY 可提交 → DISPATCHING 提交中 → DONE/FAILED
  status varchar(16) NOT NULL DEFAULT 'WAITING' CHECK(status IN
    ('WAITING','READY','DISPATCHING','DONE','FAILED')),

  -- 排到的窗口时刻（按承运商窗口序号算出来的**可解释**时刻，不是 now()+猜测）
  scheduled_at timestamptz NOT NULL,
  -- 实际放行时刻（真发生时才写；没放行就是 NULL，不假装已经放过）
  dispatched_at timestamptz,
  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  last_failure_reason varchar(400) NOT NULL DEFAULT '',
  -- 每次重排都追加，不覆盖：放行历史要看得见
  reschedule_count int NOT NULL DEFAULT 0 CHECK(reschedule_count >= 0),

  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  -- 一个申请同一时刻只在队列里出现一次（重试是 UPDATE 不是 INSERT）
  UNIQUE(tenant_id,request_id),
  FOREIGN KEY(tenant_id,request_id)
    REFERENCES logistics_waybill_request(tenant_id,id) ON DELETE CASCADE,
  -- 一个请求只对应一个承运商：队列行的 carrier_code 必须与申请一致，
  -- 否则排 A 家队却把件发给 B 家，是本模块最贵的错。
  -- （服务层同事务内写入两者，DB 再钉一道 —— 两层都错不了才算数。）
  CHECK(status <> 'DISPATCHING' OR dispatched_at IS NOT NULL)
);

-- 取件主索引：等窗口的按 scheduled_at 顺序出来
CREATE INDEX waybill_queue_pick
  ON logistics_waybill_queue_entry(tenant_id,carrier_code,status,scheduled_at);
CREATE INDEX waybill_queue_request ON logistics_waybill_queue_entry(tenant_id,request_id);

-- 队列行与申请的承运商一致性由触发器钉住（跨表 CHECK 做不到，
-- 而这是最容易被服务层的一处疏忽放过去的地方 —— 宁可 DB 拒）。
CREATE OR REPLACE FUNCTION guard_waybill_queue_carrier() RETURNS trigger AS $$
DECLARE want varchar(64);
BEGIN
  SELECT carrier_code INTO want FROM logistics_waybill_request
   WHERE tenant_id = NEW.tenant_id AND id = NEW.request_id;
  IF want IS NULL THEN
    RAISE EXCEPTION '运单申请 % 不存在（tenant=%）', NEW.request_id, NEW.tenant_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF want <> NEW.carrier_code THEN
    RAISE EXCEPTION
      '队列承运商 % 与运单申请 % 的承运商 % 不一致（一次队列不许跨承运商）',
      NEW.carrier_code, NEW.request_id, want USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_queue_carrier_trg
  BEFORE INSERT OR UPDATE ON logistics_waybill_queue_entry
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_queue_carrier();

-- ---------------------------------------------------------------------------
-- 5. waybill_channel_rate_limit · 渠道限流口径视图（**单一真相**）
-- ---------------------------------------------------------------------------
-- 未登记的承运商用内置默认值兜底，而不是「查不到就当无限流」——
-- 无限流会让一次大促批量把整个批次的承运商接口打挂，正是蓝图点名要防的那件事。
-- 默认值与 logistics_carrier 的列默认值逐字一致，且**只在本文件里定义一次**。
CREATE VIEW waybill_channel_rate_limit AS
SELECT c.tenant_id, c.id AS carrier_id, c.code AS carrier_code, c.carrier_channel,
       coalesce(c.rate_limit_per_window, 20)   AS rate_limit_per_window,
       coalesce(c.rate_window_seconds, 60)     AS rate_window_seconds,
       coalesce(c.retry_backoff_base_seconds, 60) AS retry_backoff_base_seconds,
       coalesce(c.max_retry_attempts, 3)       AS max_retry_attempts,
       c.connected, c.adapter_key
  FROM logistics_carrier c;

-- ---------------------------------------------------------------------------
-- 6. logistics_customs_declaration_item · 申报明细（3.7 报关属性）
-- ---------------------------------------------------------------------------
-- 蓝图输入句第三项是「申报信息（3.7 报关属性）」，而卷 10 边界那条写死
-- 「包裹必须有明细项，不能只有重量和运单号」—— 申报明细项同理：
-- 只有一张申报总额的行，承运商与海关都无法复核，申报差错直接导致扣货与罚款
-- （蓝图 10.5 那句「错误直接导致扣货与罚款」）。
--
-- 数据来源是 catalog 3.7.1.1 / 3.7.1.2 的**授权只读 API**（跨服务，不直连对方库）。
-- 本表存**申请当时取到的快照**：申报口径会改，但已提交给承运商的那一票必须
-- 保留当时的口径，否则事后无法解释「当时为什么这么报」。
CREATE TABLE logistics_customs_declaration_item (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,

  sku_id uuid NOT NULL,
  sku_ref varchar(64) NOT NULL CHECK(btrim(sku_ref) <> ''),
  product_name varchar(300) NOT NULL CHECK(btrim(product_name) <> ''),

  -- 申报数量 NUMERIC(19,6)（允许 0.5kg / 1.5 件这类实数；禁整数「件」假设）
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  unit_code varchar(16) NOT NULL CHECK(btrim(unit_code) <> ''),
  -- 单价与总价一律 NUMERIC(19,4) + BigDecimal，**禁浮点记账**（AGENTS.md）
  unit_price numeric(19,4) NOT NULL CHECK(unit_price >= 0),
  declared_value numeric(19,4) NOT NULL CHECK(declared_value >= 0),

  -- HS 编码（3.7.1.1）。承运商与海关只认这个，不认商品名。
  hs_code varchar(32) NOT NULL CHECK(btrim(hs_code) <> ''),
  origin_country varchar(2) NOT NULL DEFAULT '',
  -- 目的国报关版本（3.7.1.2）—— 版本要留痕：换了版本，报关口径就换了。
  destination_version_id uuid,
  customs_attribute_id uuid,

  UNIQUE(tenant_id,id),
  -- 一个申请内同一 SKU 不重复申报：同一件货报两次 = 数量翻倍 = 罚款
  UNIQUE(tenant_id,request_id,sku_id),
  -- 申报额必须自洽：单价 × 数量 = 申报额（DB 层算术，不信应用层）
  CHECK(declared_value = round(unit_price * quantity, 4)),
  FOREIGN KEY(tenant_id,request_id)
    REFERENCES logistics_waybill_request(tenant_id,id) ON DELETE CASCADE
);

CREATE INDEX logistics_customs_item_request
  ON logistics_customs_declaration_item(tenant_id,request_id);
CREATE INDEX logistics_customs_item_hs ON logistics_customs_declaration_item(tenant_id,hs_code);

-- 申请头的申报合计必须等于明细之和（服务层写，DB 层校验函数兜底）
--
-- ⚠️ 这个 BEFORE INSERT 触发器顺带**规定了写入顺序**（不是缺陷，是它能钉住
--   「申报合计是明细算出来的、不是调用方报上来的」的唯一办法）：
--     ① INSERT 申请头，declared_value_total 必须是 0（此刻还没有明细，明细之和就是 0）；
--     ② INSERT 逐条申报明细；
--     ③ UPDATE 申请头写明细之和。
--   写反顺序（先报总额再补明细）会在第 ① 步被拒 —— 那正是要拦的情况：
--   一个自称 120 美元却没有任何明细支撑的申报额，承运商与海关都会拒收。
CREATE OR REPLACE FUNCTION guard_waybill_declaration_total() RETURNS trigger AS $$
DECLARE expected numeric(19,4);
BEGIN
  SELECT coalesce(sum(declared_value), 0)::numeric(19,4) INTO expected
    FROM logistics_customs_declaration_item
   WHERE tenant_id = NEW.tenant_id AND request_id = NEW.id;
  IF NEW.declared_value_total <> expected THEN
    RAISE EXCEPTION
      '运单申请 % 的申报合计 % 与明细之和 % 不一致（申报差额会被承运商与海关拒收）',
      NEW.request_no, NEW.declared_value_total, expected USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_declaration_total_trg
  BEFORE INSERT OR UPDATE ON logistics_waybill_request
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_declaration_total();

-- ---------------------------------------------------------------------------
-- 7. logistics_waybill_attempt · 逐次提交尝试（失败原因与重试证据）
-- ---------------------------------------------------------------------------
-- 「失败重试」不能只有 attempt_count 一个数字：承运商拒收的原因各不相同
-- （地址不可达 / 尺寸超限 / 余额不足 / 渠道限流），重试前必须能看见上一次为什么失败。
CREATE TABLE logistics_waybill_attempt (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  attempt_no int NOT NULL CHECK(attempt_no > 0),

  outcome varchar(24) NOT NULL CHECK(outcome IN
    ('SUCCEEDED','CARRIER_REJECTED','RATE_LIMITED','CARRIER_UNAVAILABLE','ADAPTER_MISSING')),
  -- 失败原因原文（承运商给的 errorMessage 逐字留一份，不做翻译式改写）
  failure_reason varchar(400) NOT NULL DEFAULT '',
  -- 承运商侧请求凭据（requestId），事后对账要用
  carrier_request_ref varchar(128) NOT NULL DEFAULT '',
  waybill_no varchar(128) NOT NULL DEFAULT '',
  attempted_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  -- 同一申请的同一次尝试号唯一（重试时 attempt_no 递增，不覆盖上一条）
  UNIQUE(tenant_id,request_id,attempt_no),
  FOREIGN KEY(tenant_id,request_id)
    REFERENCES logistics_waybill_request(tenant_id,id) ON DELETE CASCADE,
  CHECK(outcome = 'SUCCEEDED' OR btrim(failure_reason) <> ''),
  CHECK(outcome <> 'SUCCEEDED' OR btrim(waybill_no) <> '')
);

CREATE INDEX logistics_waybill_attempt_request
  ON logistics_waybill_attempt(tenant_id,request_id,attempt_no);

-- ---------------------------------------------------------------------------
-- 8. logistics_waybill_event · 事件流（append-only 凭据）
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_waybill_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  queue_entry_id uuid,
  event_kind varchar(32) NOT NULL CHECK(event_kind IN
    ('REQUESTED','QUEUED','DISPATCHED','ISSUED','FAILED','RETRY_SCHEDULED','CANCELLED')),
  from_status varchar(20) NOT NULL DEFAULT '',
  to_status varchar(20) NOT NULL DEFAULT '',
  detail varchar(400) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,request_id)
    REFERENCES logistics_waybill_request(tenant_id,id) ON DELETE CASCADE
);

CREATE INDEX logistics_waybill_event_request
  ON logistics_waybill_event(tenant_id,request_id,occurred_at);

-- 事件流是凭据：连运行角色都不给改删（触发器 + REVOKE 两道）
CREATE OR REPLACE FUNCTION reject_waybill_event_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'logistics_waybill_event 是 append-only 的凭据，不允许修改或删除'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER logistics_waybill_event_append_only_trg
  BEFORE UPDATE OR DELETE ON logistics_waybill_event
  FOR EACH ROW EXECUTE FUNCTION reject_waybill_event_mutation();

-- ---------------------------------------------------------------------------
-- 9. 状态机守卫：已出号的申请不可回退（凭据不可回退）
-- ---------------------------------------------------------------------------
-- 服务层的状态机是第 10 节那种 switch；这里是第二道 —— 直接 UPDATE 把
-- ISSUED 打回 FAILED，界面就少了「这张已经出过号」的凭据，而承运商那边单号还在。
CREATE OR REPLACE FUNCTION guard_waybill_request_state() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'ISSUED' AND NEW.status <> 'ISSUED' THEN
    RAISE EXCEPTION
      '运单申请 % 已出号（运单号 %），不可回退到 %',
      OLD.request_no, OLD.waybill_no, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  -- 已出号的凭据字段不可改写
  IF OLD.status = 'ISSUED'
     AND (NEW.waybill_no <> OLD.waybill_no OR NEW.issued_at IS DISTINCT FROM OLD.issued_at) THEN
    RAISE EXCEPTION '运单申请 % 的运单号与出号时刻是凭据，不可改写', OLD.request_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_request_state_trg
  BEFORE UPDATE ON logistics_waybill_request
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_request_state();

-- ---------------------------------------------------------------------------
-- 10. 多租户与授权（与 trade V1 / V116 / V122 / V124 / V126 同一套写法）
-- ---------------------------------------------------------------------------
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_carrier',
    'logistics_waybill_request',
    'logistics_waybill_queue_entry',
    'logistics_customs_declaration_item',
    'logistics_waybill_attempt',
    'logistics_waybill_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  logistics_carrier,
  logistics_waybill_request,
  logistics_waybill_queue_entry,
  logistics_customs_declaration_item,
  logistics_waybill_attempt,
  logistics_waybill_event TO trade_app;
-- 事件流是凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON logistics_waybill_event FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V116 / V122 / V124 同口径）。
REVOKE ALL ON
  logistics_carrier,
  logistics_waybill_request,
  logistics_waybill_queue_entry,
  logistics_customs_declaration_item,
  logistics_waybill_attempt,
  logistics_waybill_event FROM starcon_app;
-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE waybill_request_no_seq TO trade_app;

-- ⚠️ 视图**不会**从 GRANT ... ON ALL TABLES 继承授权，必须单独授予：
--   漏了这一条的症状是「表都读得到，一查这个视图就 permission denied」。
--   视图本身不携带 RLS 策略（策略在基表 logistics_carrier 上），
--   所以服务层查视图时**必须显式带 tenant_id 谓词** —— 已写入 carrierOf()。
GRANT SELECT ON waybill_channel_rate_limit TO trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V116 / V122 / V124 同口径）。
REVOKE ALL ON waybill_channel_rate_limit FROM starcon_app;
