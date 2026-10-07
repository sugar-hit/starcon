-- 7.5.1.2 在途库存。
--
-- 蓝图 07-inventory.md 7.5.1.2 原文：「跟踪所有在途库存（调拨在途、头程在途、采购在途、
-- FBA 发货在途），含起止仓、批次、数量、预计到达」；输入「调拨发出与头程发运事件；预计到达
-- 时间；收货事件」；输出「在途库存清单 + 到达预警（逾期未达）」。
--
-- ===========================================================================
-- 一、本迁移**不建第二张在途表** —— 这是本模块最重要的一条设计决定
-- ===========================================================================
-- V1 建的 inventory_transfer 已经是全服务在途的唯一事实，四个既有模块都按它求在途：
--   7.1.1.1 总览  InventoryOverviewService.IN_TRANSIT
--   7.2.2.1 可售量 InventoryAvailabilityService:130
--   7.1.3.1 多仓分布 InventoryDistributionService
--   7.5.1.1 调拨单 InventoryTransferService（ship 挂行 / receive 拆行）
--
-- 蓝图的关系段写得很直接：「在途漏算会导致重复补货，这是跨境库存积压的最常见原因」。
-- 漏算的根因永远是**同一个在途有两套账**（一张 inventory_transfer + 一张新表），
-- 于是某次改造只更新了其中一套，6.2.1.1 补货扣的就不是真实在途。
--
-- 所以本迁移走**扩列**路线：头程/采购/FBA 发货在途也写进 inventory_transfer。
-- 这样「所有在途」天然在一处，补货口径只需要一条 sum，且四个既有读者
-- **一行都不用改**就自动把新类型在途算了进去（它们只按 status='IN_TRANSIT' 求和，
-- 不关心这条腿是调拨还是头程 —— 这恰好是它们本来就应该关心的东西）。
--
-- ===========================================================================
-- 二、跨服务边界：上游模块未交付，所以用「定位键 + 可读名快照」
-- ===========================================================================
-- 蓝图的输入是「调拨发出与**头程发运事件**」，但头程属 10 物流与头程、采购属 6 采购、
-- FBA 发货属 4 外部仓 —— 这三条都**尚未交付**，本服务不能连它们的库。
--
-- AGENTS.md 模块边界纪律：禁止跨服务 FK / 跨库视图 / 读写对方表。因此外部在途腿只带：
--   source_system   来源系统标识（PURCHASING / FIRST_MILE / FBA …），
--                   将来上游交付后由它自己写入，本服务永不代写对方语义；
--   source_reference 来源单据号（对方系统里的业务键），
--                   与 source_system 组成 UNIQUE，作为**幂等锚点**：
--                   上游重复投递同一条发运事件时，本表按这条唯一键去重，不会双记在途。
--
-- 起止仓同理：供应商工厂、Amazon FBA 仓**不是**本租户的 warehouse 行，
-- 所以这两列放宽为可空，并各带一个可读名快照列（from/to_warehouse_name）。
-- 展示口径 = coalesce(本仓 code/name, 快照)。仓在租户内就以仓为准（权威），
-- 仓不在租户内才落快照 —— 快照是「对方系统怎么称呼这一站」，不是本地权威列。
--
-- 将来交接方式：10 / 6 / 4 交付后，它们通过各自的适配器调本服务
-- 「登记在途腿」接口（POST /warehouse/inventory/in-transit）或直接发事件，
-- source_system / source_reference 就是对接契约，本表结构不需要再变。
--
-- ===========================================================================
-- 三、批次维度
-- ===========================================================================
-- 蓝图要求清单含「批次」。inventory_transfer 原先没有批次维度，而 inventory_lot 就在
-- **本服务**内（V2 建），所以这里可以正当地加复合外键 (tenant_id, lot_id) ——
-- 同服务内引用，不是跨服务外键。既有行为空：7.5.1.1 的调拨契约是 SKU × 数量
-- （不按批次拆分），那部分行 lot_id 保持 null，行为不变。
--
-- ===========================================================================
-- 四、预计到达与逾期预警
-- ===========================================================================
-- V1 的 inventory_transfer **没有**预计到达列 —— 逾期未达在库层根本算不出来，
-- 只能靠人记。这正是蓝图要的输出「到达预警（逾期未达）」的前置缺口。
--
-- 本迁移加 expected_arrive_at（可空，不改任何既有列的语义）：
--   * 调拨腿：7.5.1.1 不写这一列，读侧按 line_id → transfer_order.expected_arrive_at
--     取（见 InventoryInTransitService 的 eta 表达式），既有单据立刻有 ETA，
--     **不需要改 7.5.1.1 的任何一行代码**；
--   * 外部腿：登记时如实写入。
-- 两类都拿不到 ETA 的（历史行）不参与逾期判定，只在清单里显示为「未登记预计到达」，
-- 不假装它们是准点的 —— 把没有 ETA 的行算成逾期会让预警一上线就全是噪声。
--
-- 数量一律 numeric(19,4)，Java 侧一律 BigDecimal，全程禁浮点记账（沿用 V1 口径）。

-- ---------------------------------------------------------------------------
-- 0. 扩列：在途腿补齐「类型 / 预计到达 / 批次 / 来源定位键 / 起止站可读名」
-- ---------------------------------------------------------------------------
-- 全部是**加列**，不改任何既有列的语义与约束；既有行的默认值让新 CHECK 全部成立。
-- transit_kind 默认 'TRANSFER'：既有行都是 7.5.1.1 写的调拨腿。
ALTER TABLE inventory_transfer
  -- 在途类型：调拨 / 头程 / 采购 / FBA 发货。同一张表、同一套求和口径。
  ADD COLUMN transit_kind varchar(16) NOT NULL DEFAULT 'TRANSFER'
    CHECK(transit_kind IN ('TRANSFER','FIRST_MILE','PURCHASE','FBA_OUTBOUND')),
  -- 预计到达：逾期未达预警的**计算依据**（不是人工标记）。
  ADD COLUMN expected_arrive_at timestamptz,
  -- 批次：库存_lot 在本服务内，可以正当地加复合外键。
  ADD COLUMN lot_id uuid,
  -- 跨服务定位键（见文件头第二节）。空串 = 本服务内部产生的腿（调拨）。
  ADD COLUMN source_system varchar(32) NOT NULL DEFAULT '',
  ADD COLUMN source_reference varchar(64) NOT NULL DEFAULT '',
  -- 起止站可读名快照：供应商工厂 / Amazon FBA 仓不在本租户 warehouse 内。
  ADD COLUMN from_warehouse_name varchar(200) NOT NULL DEFAULT '',
  ADD COLUMN to_warehouse_name varchar(200) NOT NULL DEFAULT '',
  -- 承运与运单：头程/海运在途没有单号就答不出「这批货现在在哪」。
  ADD COLUMN carrier_name varchar(100) NOT NULL DEFAULT '',
  ADD COLUMN tracking_no varchar(100) NOT NULL DEFAULT '',
  -- 累计**已了结**量 = 已到达 + 已确认短少。外部腿分次到达/结案时累加，
  -- 于是「原发 = 已了结 + 未达」这条等式对每一行都成立（见本文件第三节的不变量）。
  -- 调拨腿恒为 0 —— 7.5.1.1 的分次收货设计把到达量拆成独立的 RECEIVED 兄弟行
  -- （见 V61 第 2 节），调拨腿的到达权威在调拨单的已实收列，不在本列。
  ADD COLUMN settled_quantity numeric(19,4) NOT NULL DEFAULT 0 CHECK(settled_quantity>=0),
  -- 乐观锁：并发登记到达 / 结案时版本不符必须拒绝（沿用 V61 的纪律）。
  ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- 承运商/单号去重锚点：同一来源单据只允许一条在途腿（重复投递不双记在途）。
-- 部分唯一索引而非 UNIQUE 约束：空串行（调拨腿）可以有很多条，
-- 而 part 条件让「真给了 source_reference 的腿」在租户内唯一。
CREATE UNIQUE INDEX inventory_transfer_source_ref
  ON inventory_transfer(tenant_id,source_system,source_reference)
  WHERE btrim(source_reference)<>'';

-- 批次外键：inventory_lot 是**本服务**的表（V2），不是跨服务引用。
ALTER TABLE inventory_transfer
  ADD CONSTRAINT fk_transfer_lot FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id);

-- ---------------------------------------------------------------------------
-- 1. 放宽起止仓的可空性：头程与 FBA 发运的一端不在本租户仓内
-- ---------------------------------------------------------------------------
-- 这是**放宽**不是破坏：既有行两列都有值，那几条 CHECK/外键一行都不受影响。
-- 放宽的理由只有一条 —— 头程的起点是供应商工厂、FBA 发货的终点是平台仓，
-- 硬塞一个本租户 warehouse 行进去就是在编造事实（凭空造一个叫「供应商」的仓）。
ALTER TABLE inventory_transfer ALTER COLUMN from_warehouse_id DROP NOT NULL;
ALTER TABLE inventory_transfer ALTER COLUMN to_warehouse_id DROP NOT NULL;

-- 新增落点自洽：起止两站**至少有一站是本租户的仓**，且不能两站都没有。
-- 「两站都空」意味着这批货既不在我方任何仓、也不指向任何一站 —— 那不是在途，是脏数据。
DO $$ BEGIN
  ALTER TABLE inventory_transfer ADD CONSTRAINT ck_transfer_endpoint
    CHECK(from_warehouse_id IS NOT NULL OR to_warehouse_id IS NOT NULL);
END $$;

-- 预计到达不能早于发出：录反了会让逾期预警从第一天起就全量误报。
ALTER TABLE inventory_transfer ADD CONSTRAINT ck_transfer_eta
  CHECK(expected_arrive_at IS NULL OR expected_arrive_at>=shipped_at);

-- 外部腿的幂等与自洽：给了 source_system 就必须给 source_reference（反之亦然），
-- 且外部腿不允许声称自己是调拨腿（那会让补货把它算成「两仓之间的货」）。
ALTER TABLE inventory_transfer ADD CONSTRAINT ck_transfer_source
  CHECK((btrim(source_system)='')=(btrim(source_reference)='')
        AND (btrim(source_system)='' OR transit_kind<>'TRANSFER'));

-- 外部腿的一端落在本租户仓外时，必须有可读名快照，否则清单上那一站是空白，
-- 用户会以为是数据丢了而不是「这一站不在我们系统里」。
ALTER TABLE inventory_transfer ADD CONSTRAINT ck_transfer_endpoint_name
  CHECK((from_warehouse_id IS NOT NULL OR btrim(from_warehouse_name)<>'')
   AND (to_warehouse_id IS NOT NULL OR btrim(to_warehouse_name)<>''));

-- ---------------------------------------------------------------------------
-- 2. 到达事件（一次到货 / 一次差异登记 = 一行）
-- ---------------------------------------------------------------------------
-- 7.5.1.1 的调拨腿有 transfer_receipt 记它的到达事件；头程/采购/FBA 腿的到达
-- 没有上游单据可挂（10 物流、6 采购都未交付），所以本表是它们的到达时间线。
--
-- 为什么必须逐笔留痕而不只记「总共到了多少」：逾期追责、货损定责、
-- 以及「这批货现在在哪」都以**每批到达的时刻**为线索，
-- 只留合计就等于把「7 月 3 日到的 200 和 7 月 19 日到的 300」压成一行 500。
--
-- 同时是库存分录的幂等锚点：inventory_transaction 的
-- UNIQUE(tenant_id,ref_type,ref_id) 配 ref_type='IN_TRANSIT_EVENT' 保证
-- 同一笔到达重复投递不会重复入账。
CREATE TABLE in_transit_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  leg_id uuid NOT NULL,
  -- ARRIVED 到货入库 / SHORTAGE 确认短少结案。
  kind varchar(16) NOT NULL CHECK(kind IN ('ARRIVED','SHORTAGE')),
  occurred_at timestamptz NOT NULL,
  -- 本次到货/短少的数量。整腿到齐前 quantity 是余量口径，所以事件量可以逐笔小于原发量。
  quantity numeric(19,4) NOT NULL CHECK(quantity>0),
  -- 到货落库位。SHORTAGE 必空（货根本没到，没有落点）；
  -- ARRIVED 对「进我方仓的头程/采购在途」必填（否则目的仓余额无处安放，
  -- 且「货到哪个库位」是收货必答项）—— 但对 FBA 发货在途允许为空：
  -- 那一站的落点是平台仓，不在本租户的 warehouse_location 里，
  -- 硬塞一个本地库位就是在编造事实。入库类到货必填库位由服务层强制。
  location_id uuid,
  -- 到货落哪个批次：与在途腿的批次一致时可空（沿用腿上的批次）。
  lot_id uuid,
  actor_id uuid NOT NULL,
  -- 差异原因：SHORTAGE 必须给（丢失 / 破损 / 被拒收），如实记录不自动抹平。
  reason varchar(32) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,leg_id) REFERENCES inventory_transfer(tenant_id,id),
  FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  -- 差异登记必无库位（货没到，没有落点）；实到可有可无（见 location_id 列的注释）——
  -- 这是单向约束：只禁「短少却有落点」，不禁「到货没落点」（FBA 平台签收就没有本地库位）。
  CHECK(kind<>'SHORTAGE' OR location_id IS NULL),
  CHECK(kind='ARRIVED' OR btrim(reason)<>'')
);
CREATE INDEX in_transit_event_leg ON in_transit_event(tenant_id,leg_id,occurred_at);

-- ---------------------------------------------------------------------------
-- 3. DB 级不变量：到达事件只能来自真正在途的腿，且不超过未达量
-- ---------------------------------------------------------------------------
-- 「到货了但腿还挂在在途」会让补货把这批货**再扣一次** —— 货已经在仓里了，
-- 缺口计算却还当它没到，重复补货就是这么发生的。所以落库前钉死。
CREATE FUNCTION guard_in_transit_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE leg record;
BEGIN
 SELECT status,quantity,settled_quantity INTO leg FROM inventory_transfer
  WHERE tenant_id=NEW.tenant_id AND id=NEW.leg_id FOR UPDATE;
 IF NOT FOUND THEN
  RAISE EXCEPTION '到达事件指向的在途腿 % 不存在', NEW.leg_id USING ERRCODE='23503';
 END IF;
 IF leg.status<>'IN_TRANSIT' THEN
  RAISE EXCEPTION '在途腿 % 已是终态（%），不能再登记到达或差异', NEW.leg_id, leg.status
   USING ERRCODE='23514';
 END IF;
 IF NEW.quantity>leg.quantity THEN
  RAISE EXCEPTION '本次数量 % 超过该腿未达量 %', NEW.quantity, leg.quantity USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER in_transit_event_guard BEFORE INSERT ON in_transit_event
 FOR EACH ROW EXECUTE FUNCTION guard_in_transit_event();

-- 批次必须属于同一 SKU：在途腿的批次维度和事件落库的批次指向两批不同的货，
-- 这样的收货会让账实核对永远差一个批次。
CREATE FUNCTION guard_in_transit_leg_lot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.lot_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM inventory_lot l
    WHERE l.tenant_id=NEW.tenant_id AND l.id=NEW.lot_id AND l.sku_id=NEW.sku_id) THEN
  RAISE EXCEPTION '在途腿 % 的批次 % 不属于该 SKU', NEW.id, NEW.lot_id USING ERRCODE='23503';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER in_transit_leg_lot BEFORE INSERT OR UPDATE ON inventory_transfer
 FOR EACH ROW EXECUTE FUNCTION guard_in_transit_leg_lot();

-- 已了结量必须等于该腿全部到达/短少事件之和。
--
-- 为什么这条必须在 DB 层：settled_quantity 是「原发 − 未达」的权威出处，而未达量
-- 因 V1 的 CHECK(quantity>0) 在结案时无法归零，两者一旦对不上，
-- 清单上的「原发 = 已了结 + 未达」就会算出一个不存在的数 —— 而这个数正是补货要扣的基数。
-- 用延迟约束触发器：一次了结要同时改事件行与在途腿两处，即时触发器会对着半成品报错。
CREATE FUNCTION guard_in_transit_settled() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected numeric;
BEGIN
 SELECT coalesce(sum(e.quantity),0) INTO expected FROM in_transit_event e
  WHERE e.tenant_id=NEW.tenant_id AND e.leg_id=NEW.id;
 IF NEW.settled_quantity<>expected THEN
  RAISE EXCEPTION '在途腿 % 的已了结量 % 与到达事件之和 % 不符',
   NEW.id, NEW.settled_quantity, expected USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER in_transit_settled
 AFTER INSERT OR UPDATE ON inventory_transfer
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_in_transit_settled();

-- ---------------------------------------------------------------------------
-- 4. 热读路径索引（全部围绕「在途清单 + 逾期预警」这两条真实查询）
-- ---------------------------------------------------------------------------
-- 清单主路径：按租户取 status='IN_TRANSIT' 的行（四个既有模块的老索引按 sku 开头，
-- 清单是全量浏览，走不到那个索引，所以这里给一条按预计到达排的）。
CREATE INDEX inventory_transfer_eta ON inventory_transfer(tenant_id,expected_arrive_at,shipped_at)
  WHERE status='IN_TRANSIT';
-- 逾期预警：ETA 早于当前时刻且仍在途。
CREATE INDEX inventory_transfer_overdue ON inventory_transfer(tenant_id,expected_arrive_at)
  WHERE status='IN_TRANSIT' AND expected_arrive_at IS NOT NULL;
-- 按类型筛（调拨/头程/采购/FBA 各看各的）。
CREATE INDEX inventory_transfer_kind ON inventory_transfer(tenant_id,transit_kind,status);
-- 按目的仓聚合（6.2.1.1 补货扣在途的主查询形状：某仓某 SKU 还有多少在路上）。
CREATE INDEX inventory_transfer_destination
  ON inventory_transfer(tenant_id,to_warehouse_id,sku_id) WHERE status='IN_TRANSIT';
-- 批次维度下钻。
CREATE INDEX inventory_transfer_lot ON inventory_transfer(tenant_id,lot_id)
  WHERE lot_id IS NOT NULL;
-- 到达事件按腿查时间线。
CREATE INDEX in_transit_event_time ON in_transit_event(tenant_id,leg_id,occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 5. 多租户：RLS + 授权（与 V1/V61 同一条纪律）
-- ---------------------------------------------------------------------------
-- in_transit_event 是本模块唯一新增的业务表，一视同仁 ENABLE + FORCE。
-- inventory_transfer 的策略 V1 已经开过，这里**不重复**建策略（重名策略会让
-- 两条 OR 起来，是最隐蔽的隔离漏洞来源之一）。
-- 租户由 backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['in_transit_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON in_transit_event TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
