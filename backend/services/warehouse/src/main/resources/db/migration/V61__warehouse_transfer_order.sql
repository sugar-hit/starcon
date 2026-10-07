-- 7.5.1.1 调拨单。
--
-- 蓝图 07-inventory.md 7.5.1.1 原文：「发起仓库间调拨：选择源仓与目标仓、调拨 SKU 与数量、
-- 预计发出与到达时间，跟踪发出、在途、目标实收」；输出「调拨单（草稿/待发/在途/部分收/已收/
-- 差异）；库存分录（发出转入途、实收转入目标仓）」。
--
-- ===========================================================================
-- 一、本模块最重要的一条语义：调拨**不是瞬时**
-- ===========================================================================
-- 蓝图原文：「**调拨不是瞬时**：发出后进入在途 bucket，目的仓分次收货逐笔转入，
-- 『异地收发同一瞬间』是错的」。
--
-- 因此本迁移**刻意不提供**「一次调用同时扣源仓加目标仓」的写入口：
--   发出（ship）    → 源仓 inventory_balance.on_hand 减少 + TRANSFER_OUT 分录
--                     + inventory_transfer 新增一条在途行（货权离开发货仓，实物尚未到达）
--   实收（receive） → 目的仓 inventory_balance.on_hand 增加 + TRANSFER_IN 分录
--                     + **分次**：每一次收货都是一笔独立事件，逐条冲减在途行
--   结案（close）   → 仍挂在在途但已确认短少的部分如实记为差异，在途归零
--
-- 守恒式（本迁移用 DB 触发器钉死，见 guard_transfer_line_conservation）：
--
--     每条明细行：已发出 = 已实收 + 已确认差异 + 仍在途
--
-- 少了它，「源仓扣了、货丢了、在途却一直挂着」这类账实不符会长期潜伏 ——
-- 而 7.1.2.2 账实核对正是要来查这件事的。
--
-- ===========================================================================
-- 二、为什么沿用 V1 已有的 inventory_transfer，而不是另建一张在途表
-- ===========================================================================
-- V1 建的 inventory_transfer 已经是全服务在途的唯一事实：
-- 7.1.1.1 总览、7.1.2.2 核对、7.2.2.1 可售量、7.1.3.1 多仓分布**全都**按
--     sum(inventory_transfer.quantity) where status='IN_TRANSIT'
-- 求在途（grep 可见 InventoryOverviewService.IN_TRANSIT / SellablePolicyService /
-- InventoryAvailabilityService / InventoryDistributionService）。
--
-- 所以本迁移把 quantity 钉死为**「这一行当前还有多少没到」**（余量口径）：
-- 整条收完时该行置为 RECEIVED 并带收货时刻；只收了一部分时**拆行** ——
-- 原行保留未到的剩余量、另起一行记已收到的那一段。
--
-- 为什么是「拆行」而不是「把余量改成 0」：V1 给这张表留了两条 CHECK ——
--   CHECK(quantity>0) 与 CHECK((status='IN_TRANSIT')=(received_at IS NULL))。
-- 归零会直接撞上第一条；而「非在途状态必须带一个了结时刻」是第二条要求的，
-- 所以 CANCELLED（结案确认短少）也必须带 received_at（记结案时刻）。
-- 拆行让这四条在途求和仍是**精确的未达量**，而两条 CHECK 都还成立。
--
-- 「这一腿当初发了多少」这个事实并不因此丢失：它 immutable 地落在
-- inventory_transaction / inventory_entry（append-only）与
-- transfer_order_line.shipped_quantity 上 —— 那才是权威出处，
-- inventory_transfer 只是**当前还有多少在路上**的余额行。
-- 发出明细因此也从分录派生，而不是从在途行拼。
--
-- 本迁移只**加一列** line_id（可空，不改任何既有列的语义与约束）：
-- 每一腿要能指回是哪条调拨明细行发出的，否则「这一行到底还有多少在途」答不出来，
-- 上面的守恒式也无从按行校验。既有行的 line_id 为 null，不受任何影响。
--
-- ===========================================================================
-- 三、跨仓库调拨是**同租户内**的事
-- ===========================================================================
-- 源仓与目标仓都用复合外键 (tenant_id, warehouse_id) 指向 warehouse：
-- DB 层直接堵死「源仓指向别的租户」这个口子，不依赖应用层记得校验。
-- 蓝图的 7.5.2.1 跨法人调拨是另一条车道的事（需要定价与内部结算），
-- 本模块**不**做跨法人：legal_entity_id 只表示「这批货算哪个法人的货权」，
-- 不表示转出与转入方不同法人。
--
-- 数量一律 numeric(19,4)，Java 侧一律 BigDecimal，全程禁浮点记账（沿用 V1 口径）。

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：TR-yyyyMMdd-000001
-- ---------------------------------------------------------------------------
-- 单号由 DB 发，不在应用层拼：应用层拼出来的单号在并发建单时必然撞号，
-- 而撞号会被 UNIQUE(tenant_id,order_code) 变成 409 —— 那是一个本不该发生的错误。
-- 序列是全局的（不是每租户一个），唯一性约束本来就只要求租户内唯一。
CREATE SEQUENCE transfer_order_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 调拨单头（草稿 → 待发 → 在途 → 部分收 → 已收 / 差异）
-- ---------------------------------------------------------------------------
CREATE TABLE transfer_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_code varchar(32) NOT NULL,
  -- 源仓与目标仓都必须是**本租户**的仓：复合外键 (tenant_id, warehouse_id)。
  from_warehouse_id uuid NOT NULL,
  to_warehouse_id uuid NOT NULL,
  -- 货权法人：这批货算哪个法人的账。与 7.5.2.1 跨法人调拨无关，
  -- 那是「转出与转入是不同法人、需要定价与内部结算」的另一件事。
  legal_entity_id uuid NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
    'DRAFT','PENDING_SHIPMENT','IN_TRANSIT','PARTIALLY_RECEIVED','RECEIVED','DISCREPANCY')),
  -- 预计发出与到达时间：调拨在途的时效预期，逾期未达由 7.5.1.2 在途库存负责预警，
  -- 本模块只如实存下这两个时刻，不做预警。
  expected_ship_at timestamptz,
  expected_arrive_at timestamptz,
  reason varchar(200) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  -- 乐观锁：蓝图把 expected_version 写进了输入契约，并发发货/收货时版本不符必须拒绝。
  version bigint NOT NULL DEFAULT 0,
  confirmed_at timestamptz,
  shipped_at timestamptz,
  completed_at timestamptz,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_code),
  FOREIGN KEY(tenant_id,from_warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,to_warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(order_code)<>''),
  -- 同仓调拨没有意义（发到同一个仓的货不会产生在途），DB 层直接拒。
  CHECK(from_warehouse_id<>to_warehouse_id),
  -- 预计到达早于预计发出 = 录反了，而这种单据会让 7.5.1.2 的逾期预警永远不触发。
  CHECK(expected_arrive_at IS NULL OR expected_ship_at IS NULL
        OR expected_arrive_at>=expected_ship_at)
);

-- ---------------------------------------------------------------------------
-- 2. 调拨单明细行（SKU × 质量状态 × 数量）
-- ---------------------------------------------------------------------------
-- 按 SKU × 质量状态分行而不是只按 SKU：可售与残次不是同一批货，混在一行就答不出
-- 「这批残次货调不调得动」。与 inventory_balance / stock_bucket 的分维度口径一致。
--
-- requested  请求调拨量（建单时定下）
-- shipped    累计发出量（可分多次发，每次减源仓余额并挂一条在途行）
-- received   累计实收量（可分多次收，每次转入目的仓余额并冲减在途）
-- discrepancy 累计确认差异量（结案时把「已发出但永远收不到」的部分如实记账）
CREATE TABLE transfer_order_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  quality_status_id uuid NOT NULL,
  requested_quantity numeric(19,4) NOT NULL,
  shipped_quantity numeric(19,4) NOT NULL DEFAULT 0,
  received_quantity numeric(19,4) NOT NULL DEFAULT 0,
  discrepancy_quantity numeric(19,4) NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id,sku_id,quality_status_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES transfer_order(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  CHECK(requested_quantity>0),
  CHECK(shipped_quantity>=0 AND shipped_quantity<=requested_quantity),
  CHECK(received_quantity>=0 AND received_quantity<=shipped_quantity),
  CHECK(discrepancy_quantity>=0),
  -- 已实收 + 已确认差异不得超过已发出：否则「收的比发的多」会被算成差异来源，
  -- 而这在调拨里只可能意味着记账出错。
  CHECK(received_quantity+discrepancy_quantity<=shipped_quantity)
);

-- ---------------------------------------------------------------------------
-- 3. 发出动作（一次 ship = 一行；同时是 TRANSFER_OUT 分录的幂等锚点）
-- ---------------------------------------------------------------------------
-- 为什么单独建表而不是直接拿 inventory_transfer 的行当「这一笔发出」：
-- 一次 ship 可以同时发多行明细，而 inventory_transfer 是**按明细行**记在途的。
-- 发出行给出这次动作的时刻与操作人，并作为 inventory_transaction.ref_id
-- —— 同一笔发出重复投递时靠 UNIQUE(tenant_id,ref_type,ref_id) 去重。
CREATE TABLE transfer_shipment (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  shipped_at timestamptz NOT NULL,
  actor_id uuid NOT NULL,
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES transfer_order(tenant_id,id)
);
CREATE INDEX transfer_shipment_order ON transfer_shipment(tenant_id,order_id,shipped_at);

-- ---------------------------------------------------------------------------
-- 4. 收货事件（一次 receive = 一行；kind=SHORTAGE 是结案时的差异登记）
-- ---------------------------------------------------------------------------
-- 分次收货必须**逐笔留痕**：只记「总共收了 60」就答不出「这 60 是哪几笔到的」，
-- 而货损追责与在途逾期的起点都是那几笔到达时间。
--
-- SHORTAGE 复用同一张表而不是另建差异表：差异本来就是一次收货动作的结果 ——
-- 「这趟少了 5 件」和「这趟到了 60 件」在业务上是同一件事的两面，
-- 放一起才能按发生顺序读出这趟调拨的完整经过。
CREATE TABLE transfer_receipt (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  kind varchar(12) NOT NULL CHECK(kind IN ('RECEIVED','SHORTAGE')),
  received_at timestamptz NOT NULL,
  -- 实收落到哪个库位。required：实收必须能落到一个库位，否则目的仓的余额行
  -- 有 bucket 时会因为 V2 的守恒触发器无处安放而失败，
  -- 且「货到了哪个库位」本来就是收货单据必须回答的问题。
  location_id uuid,
  actor_id uuid NOT NULL,
  -- 差异原因：SHORTAGE 必须给（丢失 / 破损 / 被拒收），如实记录不自动抹平。
  reason varchar(32) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES transfer_order(tenant_id,id),
  FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_location(tenant_id,id),
  -- 实收必有库位，差异登记必无库位（货根本没到，没有落点）与必有原因。
  CHECK((kind='RECEIVED')=(location_id IS NOT NULL)),
  CHECK(kind='RECEIVED' OR btrim(reason)<>'')
);

-- 每笔收货要落到「哪张调拨单的哪一行」，记在这张明细事件表上：
CREATE TABLE transfer_receipt_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  line_id uuid NOT NULL,
  quantity numeric(19,4) NOT NULL,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES transfer_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES transfer_order_line(tenant_id,id),
  CHECK(quantity>0)
);
CREATE INDEX transfer_receipt_line_receipt ON transfer_receipt_line(tenant_id,receipt_id);
CREATE INDEX transfer_receipt_line_line ON transfer_receipt_line(tenant_id,line_id);

-- ---------------------------------------------------------------------------
-- 5. 把在途行接回明细行
-- ---------------------------------------------------------------------------
-- **只加一列，不改任何既有列的语义**：既有行的 line_id 为 null，
-- 7.1.1.1 / 7.2.2.1 / 7.1.3.1 那几条 sum(quantity) where status='IN_TRANSIT'
-- 一行都不用改，语义也不变。
ALTER TABLE inventory_transfer ADD COLUMN line_id uuid;
CREATE INDEX inventory_transfer_line ON inventory_transfer(tenant_id,line_id)
  WHERE line_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. DB 级状态机：状态与三个时间戳必须互相自洽
-- ---------------------------------------------------------------------------
-- 为什么用触发器而不是只在服务层 if：状态与时间戳是**对账依据**。
-- 「已收但没有完成时间」或「草稿却有发出时间」都会让 7.5.1.2 的在途账实核对
-- 永远对不上，而且这种行只有一处写得出来，却要被四处读。
CREATE FUNCTION guard_transfer_order_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.status IN ('DRAFT')) <> (NEW.confirmed_at IS NULL) THEN
  RAISE EXCEPTION '状态「%」与确认时间不自洽：草稿不能有确认时间，待发及之后必须有', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF (NEW.status IN ('DRAFT','PENDING_SHIPMENT')) <> (NEW.shipped_at IS NULL) THEN
  RAISE EXCEPTION '状态「%」与发出时间不自洽：发出之前没有发出时间', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF (NEW.status NOT IN ('RECEIVED','DISCREPANCY')) <> (NEW.completed_at IS NULL) THEN
  RAISE EXCEPTION '状态「%」与完成时间不自洽：只有已收与差异是终态', NEW.status
   USING ERRCODE='23514';
 END IF;
 -- 草稿不能带任何货权流水：草稿只是意图，还不动库存。
 IF NEW.status='DRAFT' AND (NEW.shipped_at IS NOT NULL OR NEW.completed_at IS NOT NULL) THEN
  RAISE EXCEPTION '草稿不允许带发出或完成时间' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER transfer_order_state BEFORE INSERT OR UPDATE ON transfer_order
 FOR EACH ROW EXECUTE FUNCTION guard_transfer_order_state();

-- ---------------------------------------------------------------------------
-- 7. 守恒：已发出 = 已实收 + 已确认差异 + 仍在途
-- ---------------------------------------------------------------------------
-- 这是本模块**最硬**的一条不变量，也是「在途不丢账」的落点。
--
-- 用延迟约束触发器：一次收货本来就要「改在途行 + 改明细行 + 写分录」三件事，
-- 即时触发器会在事务中途对着半成品报差异。
--
-- 它挡住的具体事故：
--   * 实收了但忘了冲减在途 → 在途永远挂着，总览的「在手 = 可售 + 待检 + 残次 + 在途」失衡；
--   * 结案时把短少部分直接从在途抹掉而没记差异 → 货凭空消失且无人认账；
--   * 发出量与在途余量对不上（重复发货 / 漏记在途行）→ 明细行与在途清单永远对不上账。
CREATE FUNCTION guard_transfer_line_conservation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE outstanding numeric;
BEGIN
 SELECT coalesce(sum(i.quantity),0) INTO outstanding FROM inventory_transfer i
  WHERE i.tenant_id=NEW.tenant_id AND i.line_id=NEW.id AND i.status='IN_TRANSIT';
 IF NEW.shipped_quantity <> NEW.received_quantity + NEW.discrepancy_quantity + outstanding THEN
  RAISE EXCEPTION '调拨明细账实不符：已发出 % ≠ 已实收 % + 已确认差异 % + 仍在途 %（行 %）',
   NEW.shipped_quantity, NEW.received_quantity, NEW.discrepancy_quantity, outstanding, NEW.id
   USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER transfer_line_conservation
 AFTER INSERT OR UPDATE ON transfer_order_line
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_transfer_line_conservation();

-- 在途行归属：带 line_id 的在途行必须指向一张真实存在的明细行
-- （不加外键是因为 inventory_transfer 是 V1 的共享表，本模块只加列不改它的约束面；
--   这里的触发器保证「引用了行就一定有那张行」，等价于一条逻辑外键）。
CREATE FUNCTION guard_transfer_line_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.line_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM transfer_order_line l WHERE l.tenant_id=NEW.tenant_id AND l.id=NEW.line_id) THEN
  RAISE EXCEPTION '在途行 % 指向的调拨明细 % 不存在', NEW.id, NEW.line_id USING ERRCODE='23503';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER transfer_line_ref BEFORE INSERT OR UPDATE ON inventory_transfer
 FOR EACH ROW EXECUTE FUNCTION guard_transfer_line_ref();

-- ---------------------------------------------------------------------------
-- 8. 热读路径索引
-- ---------------------------------------------------------------------------
-- 列表按状态筛 + 按更新/创建时间排。
CREATE INDEX transfer_order_list ON transfer_order(tenant_id,status,updated_at DESC);
CREATE INDEX transfer_order_warehouse ON transfer_order(tenant_id,from_warehouse_id,to_warehouse_id);
CREATE INDEX transfer_order_line_order ON transfer_order_line(tenant_id,order_id);
CREATE INDEX transfer_order_line_sku ON transfer_order_line(tenant_id,sku_id);

-- ---------------------------------------------------------------------------
-- 9. 多租户：RLS + 授权（与 V1 第 9 节同一条纪律）
-- ---------------------------------------------------------------------------
-- 四张业务表一视同仁 FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'transfer_order','transfer_order_line','transfer_shipment','transfer_receipt','transfer_receipt_line'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON transfer_order, transfer_order_line, transfer_shipment,
  transfer_receipt, transfer_receipt_line TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条的话建单会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE transfer_order_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;