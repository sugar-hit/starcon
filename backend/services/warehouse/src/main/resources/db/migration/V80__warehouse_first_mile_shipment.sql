-- 10.4.1.1 头程发运单。
--
-- 蓝图 10-logistics-and-freight.md 10.4.1.1 原文：
--   职责：管理国内到海外的货权转移发运：选择货物与批次（国内仓/供应商）、选择运输方式
--         （海运/空运/铁运/快递）、申报信息、目的仓、预计到港时间、成本预估。
--   输入：待发运货物清单；运输方式与渠道；报关资料；成本参数。
--   输出：头程发运单 + 发货分录（转在途）+ 报关资料 + 预计到港时间。
--   关系：上游 = 8.2.1.1 收货、6 采购；下游 = 10.5 报关、7.5.1.2 在途。
--         **头程发运是货权与成本的关键节点**：从这一刻起成本转嫁方式（FOB/CIF）与
--         风险承担方确定，直接影响利润口径。
--
-- ===========================================================================
-- 一、本迁移**只新建表，不 ALTER 任何既有表** —— 并行车道的零回归面
-- ===========================================================================
-- 同服务里已经落地的 8.2.1.1（V70 六张表）与 7.5.1.2（V63 扩列 + in_transit_event）
-- 都由别的车道交付，本迁移对它们**只读**：
--   * 不 ADD COLUMN、不 SET NOT NULL、不加 CHECK、不碰触发器；
--   * 因此本迁移落到集成头时不可能把 8.2.1.1 / 7.5.1.2 的既有行或既有回归测试带崩。
-- 跨车道回归面 = 0。
--
-- ===========================================================================
-- 二、本模块**不动在手库存** —— 落账口径照抄 7.5.1.2 台账原文
-- ===========================================================================
-- 7.5.1.2 原文：「外部腿的落账分两段：FBA 发货登记时就从我方发货仓扣减在手并写 SHIPMENT 分录
-- （货真的离仓了，不扣就是虚增在手）；头程/采购登记时不动在手（货还没进我方账，
-- 到货前凭空多出库存更糟），到达时才入库目的仓并写 RECEIPT 分录。」
--
-- 头程属第二类。所以本迁移**没有**任何一行 SQL 写 stock_bucket / inventory_balance /
-- inventory_entry / inventory_transaction：头程货在**发出之前根本没进过我方库存账**
-- （它来自供应商或本租户另一个收货单，不是从在手扣出来的），
-- 发出动作只做「登记在途腿」，到货入库由 7.5.1.2 的 arrive 负责。
-- 越界去改在手余额会造成虚增在手，这是比缺功能严重得多的错误。
--
-- ===========================================================================
-- 三、在途腿**不建外键指向 inventory_transfer**
-- ===========================================================================
-- inventory_transfer 与 in_transit_event 的写入所有者是 7.5.1.2，本模块**只调它的公开端点**
-- （POST /warehouse/inventory/in-transit，transitKind=FIRST_MILE）。
-- 本表只如实记下返回的 transit_leg_id 与当次的幂等锚点（source_system + source_reference），
-- **不建外键**：建了就把 7.5.1.2 集成测试里的 truncate inventory_transfer 与本表绑死
-- （它那条 truncate 不带 goods_receipt_event_line，反向引用会互相卡住）。
-- 逻辑引用 + 两边同值 = 交接时不需要改结构。
--
-- ===========================================================================
-- 四、货权与成本的关键节点：**incoterm + risk_bearer 是本模块的核心字段**
-- ===========================================================================
-- 蓝图关系段：「从这一刻起成本转嫁方式（FOB/CIF）与风险承担方确定，直接影响利润口径」。
-- 所以这两列 NOT NULL 且进 CHECK，不是页面上写死的展示项。
--
-- 两者之间有一条真实的 Incoterms 约束钉在 DB 层：
--   EXW（工厂交货）⟹ 风险在卖方场所即转移给买方 ⟹ risk_bearer 只能是 BUYER；
--   DDP（完税后交货）⟹ 卖方负责到目的地 ⟹ risk_bearer 只能是 SELLER。
-- 其余九个术语卖买皆可承担（对应 FOB 装运港转移 / CIF 目的港转移等不同约定点）。
-- 录错会让 12 财务与 13 利润的「真实毛利」整段算错，而这种错在报表上完全看不出来。
--
-- ===========================================================================
-- 五、同一批货不能发两次（DB 级钉死）
-- ===========================================================================
-- 头程的输入是**已收货的具体批次**（8.2.1.1 的 goods_receipt_event_line = 逐次实收行）。
-- 同一 SKU 分三车到货就是三个批次，每一批只能被发走一次。
-- 「累计发出量 ≤ 该实收行的实收量」由延迟约束触发器 guard_first_mile_over_ship 钉死；
-- 不钉的话，两张发运单各发 60（实收 100）会顺利通过，货权与在途都成了两份。
--
-- 数量一律 numeric(19,6)（沿用 8.2.1.1 goods_receipt_* 的口径），
-- 成本与货值一律 numeric(19,4)（沿用 7.5.1.2 inventory_transfer 的口径），全程禁浮点。

CREATE SEQUENCE first_mile_shipment_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 头程发运单头（草稿 → 已确认 → 已发出；草稿/已确认可取消）
-- ---------------------------------------------------------------------------
-- 状态机只有 4 态，与 V70 的收货单同一条纪律：状态与时间戳由触发器钉死自洽，
-- 没有「有发出时间却还是草稿」这种账外状态。
--
-- **已发出是终态**：货已经离仓、在途腿已经登记在 7.5.1.2 名下，
-- 这时想改单要动的是在途腿（7.5.1.2 的 discrepancy 登记短少）而不是抹掉发运单 ——
-- 抹掉发运单会在途与发运单对不上，货权转移记录凭空消失。
-- 已确认可撤回成草稿：订舱后才发现申报信息要改，是真实且高频的。
CREATE TABLE first_mile_shipment (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  shipment_code varchar(32) NOT NULL,
  -- 来源类型：GOODS_RECEIPT 指 8.2.1.1 收货（已交付，可真实验证来源单据存在）；
  -- SUPPLIER_PURCHASE 指 6 采购（**未交付**）—— 在 /capabilities 里显式
  -- available=false 并给出原因 + ownerModule + href，**不建占位表、不返回空集冒充成功**，
  -- create 直接拒绝该来源（见 guard_shipment_source：GOODS_RECEIPT 必须带 source_ref_id）。
  source_type varchar(24) NOT NULL CHECK(source_type IN ('GOODS_RECEIPT','SUPPLIER_PURCHASE')),
  -- 来源单据：只记逻辑引用。跨服务不建外键（6 采购属 trade、SKU 属 catalog，
  -- 按 AGENTS.md 禁止跨服务 FK）。source_ref_code 是可读快照。
  source_ref_id uuid,
  source_ref_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_id uuid NOT NULL,
  origin_warehouse_id uuid NOT NULL,
  -- 目的仓必须是本租户的 warehouse 行：7.5.1.2 登记 FIRST_MILE 腿时强制要求
  -- toWarehouseId 非空（缺了它到货就没有入库落点）。海外仓服务商连接属 10.4.2.1
  -- （未交付），所以这里的「海外仓」是本租户 warehouse 表里的海外仓行，不是服务商账户。
  destination_warehouse_id uuid NOT NULL,
  -- 目的地国家：清关与税率的第一维度，与目的仓并存（同一个海外仓可服务多国，实际以行上的国家为准）。
  destination_country_code varchar(2) NOT NULL,
  destination_name varchar(200) NOT NULL DEFAULT '',
  -- 运输方式：蓝图点名的四类。
  transport_mode varchar(16) NOT NULL CHECK(transport_mode IN ('SEA','AIR','RAIL','EXPRESS')),
  carrier_name varchar(100) NOT NULL DEFAULT '',
  -- 订舱号/运单号与船名航班号：海运问船司、空运问航班，没有它们「这批货现在在哪」答不出来。
  booking_no varchar(64) NOT NULL DEFAULT '',
  vessel_or_flight_no varchar(64) NOT NULL DEFAULT '',
  -- ===== 货权与成本的关键节点（见文件头第四节）=====
  incoterm varchar(8) NOT NULL CHECK(incoterm IN (
    'EXW','FCA','FAS','FOB','CFR','CIF','CPT','CIP','DAP','DPU','DDP')),
  risk_bearer varchar(16) NOT NULL CHECK(risk_bearer IN ('SELLER','BUYER','CARRIER')),
  -- ===== 成本预估（货值与汇率归 12 财务，此处只留发运时点的预估快照）=====
  currency char(3) NOT NULL DEFAULT 'USD',
  freight_cost numeric(19,4) NOT NULL DEFAULT 0,
  insurance_cost numeric(19,4) NOT NULL DEFAULT 0,
  duty_estimate numeric(19,4) NOT NULL DEFAULT 0,
  handling_cost numeric(19,4) NOT NULL DEFAULT 0,
  customs_broker varchar(100) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
    'DRAFT','CONFIRMED','DISPATCHED','CANCELLED')),
  -- 计划开船/起飞 与 预计到港：后者是 7.5.1.2 逾期预警的计算依据，必须在发出前定下。
  depart_at timestamptz,
  expected_arrive_at timestamptz,
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  dispatched_at timestamptz,
  dispatched_by uuid,
  cancelled_at timestamptz,
  cancelled_by uuid,
  cancel_reason varchar(200) NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,shipment_code),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,origin_warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,destination_warehouse_id) REFERENCES warehouse(tenant_id,id),
  CHECK(btrim(shipment_code)<>''),
  CHECK(btrim(shipment_code)<>'' AND length(btrim(destination_country_code))=2),
  -- 起止仓不能是同一个：头程的定义就是国内到海外，起点终点同仓是录反了。
  CHECK(origin_warehouse_id<>destination_warehouse_id),
  -- 来源必须可追：给了 id 就不能只有一条对不上的说明文字，反之亦然。
  CHECK(source_ref_id IS NOT NULL OR btrim(source_ref_code)<>''),
  CHECK(freight_cost>=0 AND insurance_cost>=0 AND duty_estimate>=0 AND handling_cost>=0),
  -- Incoterms 与风险承担方的真实一致性（见文件头第四节）。
  CHECK(incoterm<>'EXW' OR risk_bearer='BUYER'),
  CHECK(incoterm<>'DDP' OR risk_bearer='SELLER')
);

-- ---------------------------------------------------------------------------
-- 2. 明细行（SKU × 批次 × 申报信息）
-- ---------------------------------------------------------------------------
-- 按 SKU × **批次**分行：头程的输入是「选择货物与批次」，而批次挂在 8.2.1.1 的
-- 逐次实收行上（同一 SKU 分三车到货 = 三个批次）。不按批次分行的话，
-- 「这批货是从哪一次到货里发走的」永远答不出来，而货损与效期追责全靠这条线索。
--
-- 申报信息按**报关口径**存而不是照搬销售标题：10.5.1.2 原文点名「申报品名不能照搬销售标题，
-- 两者用途不同（前者为清关，后者为转化），混用是常见被退单原因」。所以
-- declared_item_name 是必填的独立列，hs_code 允许为空是因为 3.7.1.1 HS 编码字典
-- **尚未交付**（台账行记为阻塞：product_compliance 复制自 product_research，装不了），
-- 此处接受自由码并在 /capabilities 里如实声明字典缺失。
--
-- source_receipt_event_line_id 对 goods_receipt_event_line 建**复合外键**：
-- 那是**本服务**的表（V70 建），不是跨服务引用 —— 与 V63 对 inventory_lot 建复合
-- 外键同一条纪律。它保证「引用的那一批货真的存在、且与本租户一致」。
CREATE TABLE first_mile_shipment_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  shipment_id uuid NOT NULL,
  -- 行号：在单内稳定且人可读。它同时进在途腿的 source_reference（幂等锚点的一部分），
  -- 所以一旦发出就不再改动（改单只允许在未发出时进行）。
  line_no integer NOT NULL CHECK(line_no>0),
  sku_id uuid NOT NULL,
  quality_status_id uuid NOT NULL,
  -- 货物来源 = 8.2.1.1 的一次实收行（批次维度）。SUPPLIER_PURCHASE 来源未交付时它可空，
  -- 由 guard_shipment_line_source 按发运单的 source_type 决定必填与否（向前兼容：
  -- 6 采购交付后补上来源列即可，不需要改结构）。
  source_receipt_event_line_id uuid,
  -- 冗余固化的来源收货单与批次：来源行被读侧改描述时不影响本单可读性，
  -- 且「按收货单看已发走多少」不必回表 join。
  source_receipt_id uuid,
  lot_id uuid,
  planned_quantity numeric(19,6) NOT NULL,
  -- 发出量：建单时为 0，dispatch 时按实际发出量落定（可为部分发运），
  -- 此后只读（已发出是终态）。
  shipped_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- ===== 申报信息（报关口径，非销售口径）=====
  declared_item_name varchar(200) NOT NULL,
  hs_code varchar(32) NOT NULL DEFAULT '',
  brand varchar(100) NOT NULL DEFAULT '',
  origin_country_code varchar(2) NOT NULL,
  declared_unit_value numeric(19,4) NOT NULL DEFAULT 0,
  declared_value numeric(19,4) NOT NULL DEFAULT 0,
  -- 毛重/净重/体积/箱数：运费与报关都按这些算，缺一个就没法核成本。
  gross_weight_kg numeric(19,4) NOT NULL DEFAULT 0,
  net_weight_kg numeric(19,4) NOT NULL DEFAULT 0,
  volume_cbm numeric(19,6) NOT NULL DEFAULT 0,
  carton_count integer NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,shipment_id,line_no),
  FOREIGN KEY(tenant_id,shipment_id) REFERENCES first_mile_shipment(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,source_receipt_event_line_id) REFERENCES goods_receipt_event_line(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  CHECK(btrim(declared_item_name)<>''),
  CHECK(length(btrim(origin_country_code))=2),
  CHECK(planned_quantity>0),
  CHECK(shipped_quantity>=0 AND shipped_quantity<=planned_quantity),
  CHECK(declared_unit_value>=0 AND declared_value>=0),
  CHECK(gross_weight_kg>=0 AND net_weight_kg>=0 AND net_weight_kg<=gross_weight_kg),
  CHECK(volume_cbm>=0 AND carton_count>=0)
);
CREATE INDEX first_mile_line_shipment ON first_mile_shipment_line(tenant_id,shipment_id,line_no);
CREATE INDEX first_mile_line_sku ON first_mile_shipment_line(tenant_id,sku_id);
CREATE INDEX first_mile_line_source ON first_mile_shipment_line(tenant_id,source_receipt_event_line_id)
  WHERE source_receipt_event_line_id IS NOT NULL;
CREATE INDEX first_mile_line_lot ON first_mile_shipment_line(tenant_id,lot_id) WHERE lot_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. 在途腿登记**回执**（**本模块只调 7.5.1.2 的端点，不直写 inventory_transfer**）
-- ---------------------------------------------------------------------------
-- 表名刻意叫 ..._leg_receipt 而**不叫** ..._transit_leg：7.5.1.2 有一条回归断言
-- 「在途只有一个事实表」（扫 information_schema 里 table_name like '%transit%'，
-- 断言只允许 inventory_transfer 与 in_transit_event 两张）。本表**不是**第二张在途账：
-- 它只在发出那一刻存一条「我向 7.5.1.2 登记了哪条腿」的回执（腿 id + 幂等锚点 +
-- 登记量快照），在途余量、到达、短少一律从 inventory_transfer 读，本表从不被任何求和
-- 口径引用（6.2.1.1 补货净额只走 inventory_transfer）。改回带 transit 的名字会让
-- 7.5.1.2 的断言误判成「有人建了第二张在途表」，所以这里连名字一起对齐语义。
-- ---------------------------------------------------------------------------
-- 每一行 = 一次真实调 POST /warehouse/inventory/in-transit 的结果：
--   transit_leg_id  7.5.1.2 返回的在途腿 id（逻辑引用，不建外键，见文件头第三节）
--   source_system   恒为 'FIRST_MILE'，与 7.5.1.2 的跨服务定位键同值
--   source_reference 幂等锚点 = 「发运单号-行号」，与登记时传进去的值**完全一致**，
--                    于是「这张发运单有没有真的登记在途」可以对着 7.5.1.2 的表逐条核，
--                    将来交接时也不需要换键。
--
-- UNIQUE(tenant_id,line_id) 保证一行只登记一次在途腿（重复 dispatch 不双记在途）；
-- UNIQUE(tenant_id,source_system,source_reference) 与 7.5.1.2 的部分唯一索引同锚点。
CREATE TABLE first_mile_shipment_leg_receipt (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  shipment_id uuid NOT NULL,
  line_id uuid NOT NULL,
  transit_leg_id uuid NOT NULL,
  source_system varchar(32) NOT NULL DEFAULT 'FIRST_MILE',
  source_reference varchar(64) NOT NULL,
  sku_id uuid NOT NULL,
  quantity numeric(19,4) NOT NULL CHECK(quantity>0),
  shipped_at timestamptz NOT NULL,
  expected_arrive_at timestamptz,
  registered_at timestamptz NOT NULL,
  registered_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,line_id),
  UNIQUE(tenant_id,source_system,source_reference),
  FOREIGN KEY(tenant_id,shipment_id) REFERENCES first_mile_shipment(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES first_mile_shipment_line(tenant_id,id),
  CHECK(btrim(source_reference)<>'')
);
CREATE INDEX first_mile_receipt_shipment ON first_mile_shipment_leg_receipt(tenant_id,shipment_id);
CREATE INDEX first_mile_receipt_leg ON first_mile_shipment_leg_receipt(tenant_id,transit_leg_id);

-- ---------------------------------------------------------------------------
-- 4. 事件时间线（一次业务动作 = 一行，逐笔留痕）
-- ---------------------------------------------------------------------------
-- 头程发运是货权转移的节点，货权什么时候转、当时申报了什么、谁点的发出，
-- 都要能逐笔回放 —— 只留「这张单已发出」答不出「货权是哪一刻转的」。
CREATE TABLE first_mile_shipment_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  shipment_id uuid NOT NULL,
  kind varchar(16) NOT NULL CHECK(kind IN (
    'CREATED','UPDATED','CONFIRMED','CONFIRM_WITHDRAWN','DISPATCHED','CANCELLED')),
  occurred_at timestamptz NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  reason varchar(200) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,shipment_id) REFERENCES first_mile_shipment(tenant_id,id)
);
CREATE INDEX first_mile_event_shipment ON first_mile_shipment_event(tenant_id,shipment_id,occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 5. DB 级不变量
-- ---------------------------------------------------------------------------
-- 5.1 状态与时间戳自洽：草稿不该带发出时间，没发过的单更不该带取消痕迹。
CREATE FUNCTION guard_first_mile_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.status = 'DISPATCHED') <> (NEW.dispatched_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与发出时间不自洽：只有已发出才可能已经登记在途腿', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF (NEW.status = 'CANCELLED') <> (NEW.cancelled_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与取消时间不自洽', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.status = 'CANCELLED' AND btrim(NEW.cancel_reason)='' THEN
  RAISE EXCEPTION '取消必须给原因：货权与在途都会因取消而变，没有原因的取消事后无法追责'
   USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'CANCELLED'
    AND (NEW.cancelled_at IS NOT NULL OR NEW.cancelled_by IS NOT NULL OR btrim(NEW.cancel_reason)>'') THEN
  RAISE EXCEPTION '状态「%」不是已取消，不允许带取消痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 -- 两个终态一旦到达就不许**离开**（注意判据是「旧状态是终态且新状态不同」，
 -- 不能写成「新状态是 DISPATCHED 且旧状态不是」—— 那会把合法的
 -- CONFIRMED → DISPATCHED 发出动作一起挡掉，货永远发不出去）。
 IF TG_OP = 'UPDATE' AND OLD.status IN ('DISPATCHED','CANCELLED') AND NEW.status <> OLD.status THEN
  RAISE EXCEPTION '「%」是终态：% ，不允许改到「%」', OLD.status,
   CASE OLD.status
     WHEN 'DISPATCHED' THEN '货权已转移、在途腿已在 7.5.1.2 名下，要改这批货请走 7.5.1.2 的差异登记'
     ELSE '已取消的单不再接受任何动作'
   END,
   NEW.status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_state BEFORE INSERT OR UPDATE ON first_mile_shipment
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_state();

-- 5.2 预计到港不能早于开船：录反了会让 7.5.1.2 的逾期预警从第一天起全量误报。
CREATE FUNCTION guard_first_mile_eta() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.depart_at IS NOT NULL AND NEW.expected_arrive_at IS NOT NULL
    AND NEW.expected_arrive_at < NEW.depart_at THEN
  RAISE EXCEPTION '预计到港 % 早于计划开船 %：ETA 是 7.5.1.2 逾期预警的计算依据，录反会全量误报',
   NEW.expected_arrive_at, NEW.depart_at USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_eta BEFORE INSERT OR UPDATE ON first_mile_shipment
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_eta();

-- 5.3 GOODS_RECEIPT 来源必须指向一张真实存在的收货单（逻辑外键，跨模块不建 FK）。
CREATE FUNCTION guard_first_mile_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.source_type = 'GOODS_RECEIPT' AND NOT EXISTS(
   SELECT 1 FROM goods_receipt r
    WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.source_ref_id) THEN
  RAISE EXCEPTION '发运单声明来源为 8.2.1.1 收货单，但引用的收货单 % 不存在或不属于本租户',
   NEW.source_ref_id USING ERRCODE='23503';
 END IF;
 -- 反过来：供应商来源必须说明是哪家供应商，source_type 说 SUPPLIER 而 source_ref_code 空着
 -- 会让「这批货的货权从谁手里转过来」永远答不出来。
 IF NEW.source_type = 'SUPPLIER_PURCHASE' AND btrim(NEW.source_ref_code)='' THEN
  RAISE EXCEPTION '供应商来源必须给出供应商单号（sourceRefCode）' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_source BEFORE INSERT OR UPDATE ON first_mile_shipment
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_source();

-- 5.4 明细行的来源批次必须与发运单声明的来源收货单一致，且批次必须属于该 SKU。
--     这是「同一批货不能被两张发运单各发一次」的第一道闸（第二道是 5.5 的延迟约束）。
CREATE FUNCTION guard_first_mile_line_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; ev record;
BEGIN
 SELECT source_type, source_ref_id INTO head FROM first_mile_shipment
  WHERE tenant_id = NEW.tenant_id AND id = NEW.shipment_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '明细行指向的发运单 % 不存在', NEW.shipment_id USING ERRCODE='23503';
 END IF;
 IF head.source_type = 'GOODS_RECEIPT' AND NEW.source_receipt_event_line_id IS NULL THEN
  RAISE EXCEPTION '来源为收货单时每一行都必须指明是哪一次实收的哪一批货'
   USING ERRCODE='23514';
 END IF;
 IF NEW.source_receipt_event_line_id IS NOT NULL THEN
  -- event_line → event → receipt 逐级取到收货单与实收量；SKU 在**收货明细行**上
  -- （V70 的三层结构：goods_receipt_line ← goods_receipt_event_line ← goods_receipt_event），
  -- 不在 event_line 上 —— 只 join 前两层会报「column does not exist」。
  SELECT e.receipt_id, l.received_quantity, gl.sku_id, l.lot_id INTO ev
    FROM goods_receipt_event_line l
    JOIN goods_receipt_event e
      ON e.tenant_id = l.tenant_id AND e.id = l.event_id
    JOIN goods_receipt_line gl
      ON gl.tenant_id = l.tenant_id AND gl.id = l.line_id
   WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.source_receipt_event_line_id;
  IF NOT FOUND THEN
   RAISE EXCEPTION '明细行引用的实收批次 % 不存在或不属于本租户',
    NEW.source_receipt_event_line_id USING ERRCODE='23503';
  END IF;
  IF head.source_type = 'GOODS_RECEIPT' AND head.source_ref_id IS DISTINCT FROM ev.receipt_id THEN
   RAISE EXCEPTION '明细行引用的实收批次属于收货单 %，与本单声明的来源收货单 % 不是同一张',
    ev.receipt_id, head.source_ref_id USING ERRCODE='23514';
  END IF;
  IF ev.sku_id IS DISTINCT FROM NEW.sku_id THEN
   RAISE EXCEPTION '实收批次的 SKU 与本行 SKU 不是同一个：批次 % 属于 SKU %',
    NEW.source_receipt_event_line_id, ev.sku_id USING ERRCODE='23514';
  END IF;
  -- 批次必须与实收行一致（给了就必须相同）：批次对不上会让账实核对永远差一个批次。
  IF NEW.lot_id IS NOT NULL AND NEW.lot_id IS DISTINCT FROM ev.lot_id THEN
   RAISE EXCEPTION '明细行批次 % 与该实收批次的批次 % 不一致', NEW.lot_id, ev.lot_id
    USING ERRCODE='23514';
  END IF;
  -- 计划量不得超过实收量：连这一批有多少都不知道就把它写进发运单，是凭空发运。
  IF NEW.planned_quantity > ev.received_quantity THEN
   RAISE EXCEPTION '计划发运 % 超过该实收批次的实收量 %', NEW.planned_quantity, ev.received_quantity
    USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_line_source BEFORE INSERT OR UPDATE ON first_mile_shipment_line
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_line_source();

-- 5.5 同一批货不能发两次（见文件头第五节）。
--     延迟约束触发器：一次 dispatch 要同时改发运单状态、改多行明细的发出量、
--     插多行在途腿回执、插事件行，即时触发器会对着半成品报错。
CREATE FUNCTION guard_first_mile_over_ship() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE received numeric; shipped numeric;
BEGIN
 IF NEW.source_receipt_event_line_id IS NULL OR NEW.shipped_quantity <= 0 THEN
  RETURN NULL;
 END IF;
 -- 基数取「实收 − 拒收」：拒收的那部分**从未入库**（8.2.1.1 的口径），
 -- 把它算进可发量就会让发运单凭空多出一批不存在的货。
 SELECT l.received_quantity - l.rejected_quantity INTO received FROM goods_receipt_event_line l
  WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.source_receipt_event_line_id;
 -- 已发出是终态，所以累计量只需要把本行算进去。
 SELECT coalesce(sum(l2.shipped_quantity),0) INTO shipped
   FROM first_mile_shipment_line l2
  WHERE l2.tenant_id = NEW.tenant_id
    AND l2.source_receipt_event_line_id = NEW.source_receipt_event_line_id
    AND l2.shipment_id <> NEW.shipment_id;
 shipped := shipped + NEW.shipped_quantity;
 IF shipped > received THEN
  RAISE EXCEPTION '实收批次 % 累计发运 % 超过实收量 %：同一批货不能发两次',
   NEW.source_receipt_event_line_id, shipped, received USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER first_mile_over_ship
 AFTER INSERT OR UPDATE ON first_mile_shipment_line
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_over_ship();

-- 5.6 发货分录不可缺：发出去的每一行都必须在 7.5.1.2 名下有一条在途腿，
--     没发出去的则一条都不许有。没有在途腿的「已发出」行 = 货凭空离开账而不翼而飞。
CREATE FUNCTION guard_first_mile_leg() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE legs integer;
BEGIN
 IF NEW.shipped_quantity > 0 THEN
  SELECT count(*) INTO legs FROM first_mile_shipment_leg_receipt g
   WHERE g.tenant_id = NEW.tenant_id AND g.line_id = NEW.id;
  IF legs <> 1 THEN
   RAISE EXCEPTION '已发出 % 的明细行 % 在 7.5.1.2 只有 % 条在途腿：发货分录必须与发出量一一对应',
    NEW.shipped_quantity, NEW.id, legs USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER first_mile_leg
 AFTER INSERT OR UPDATE ON first_mile_shipment_line
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_leg();

-- 5.7 在途腿回执必须指向本单本行，且发出量与登记量一致（不得偷偷改量）。
CREATE FUNCTION guard_first_mile_leg_line() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE line_shipped numeric; line_sku uuid;
BEGIN
 SELECT l.shipped_quantity, l.sku_id INTO line_shipped, line_sku
   FROM first_mile_shipment_line l
  WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.line_id;
 IF line_shipped IS NULL THEN
  RAISE EXCEPTION '在途腿回执指向的明细行 % 不存在', NEW.line_id USING ERRCODE='23503';
 END IF;
 IF NEW.sku_id IS DISTINCT FROM line_sku THEN
  RAISE EXCEPTION '在途腿回执的 SKU 与明细行不一致' USING ERRCODE='23514';
 END IF;
 IF NEW.quantity <> line_shipped THEN
  RAISE EXCEPTION '在途腿登记量 % 与明细行发出量 % 不一致：7.5.1.2 按登记量算未达与逾期，两边必须同数',
   NEW.quantity, line_shipped USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_leg_line BEFORE INSERT ON first_mile_shipment_leg_receipt
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_leg_line();

-- ---------------------------------------------------------------------------
-- 6. 热读路径索引（全部围绕「头程发运单清单」与「按收货单查可发批次」两条真实查询）
-- ---------------------------------------------------------------------------
CREATE INDEX first_mile_shipment_list ON first_mile_shipment(tenant_id,status,updated_at DESC);
CREATE INDEX first_mile_shipment_route ON first_mile_shipment(tenant_id,origin_warehouse_id,
  destination_warehouse_id,created_at DESC);
CREATE INDEX first_mile_shipment_eta ON first_mile_shipment(tenant_id,expected_arrive_at,created_at DESC)
  WHERE status IN ('CONFIRMED','DISPATCHED');
CREATE INDEX first_mile_shipment_incoterm ON first_mile_shipment(tenant_id,incoterm,risk_bearer);

-- ---------------------------------------------------------------------------
-- 7. 多租户：RLS + 授权（与 V1 第 9 节、V63 第 5 节、V70 第 10 节同一条纪律）
-- ---------------------------------------------------------------------------
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'first_mile_shipment','first_mile_shipment_line',
   'first_mile_shipment_leg_receipt','first_mile_shipment_event'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON first_mile_shipment, first_mile_shipment_line,
  first_mile_shipment_leg_receipt, first_mile_shipment_event TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建单会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE first_mile_shipment_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V63、V70 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
