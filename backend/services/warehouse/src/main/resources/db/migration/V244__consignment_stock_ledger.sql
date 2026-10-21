-- ===========================================================================
-- 7.7.2.2 平台托管与全托管备货
-- ===========================================================================
-- 蓝图原文（07-inventory.md 7.7.2.2）：
--   「职责：处理平台托管（全托管）下的"备货"语义：发给平台的货进入平台仓但**所有权与结算规则随模式变化**，
--     需与自有仓库存分账；支持备货单、平台仓收发对账、按件/按日结算口径标记。
--     输入：店铺经营模式（2.3.2.1）；备货单与发运数据；平台仓库存与结算回执。
--     输出：托管备货台账（备货量/平台在库/平台已售/结算未结）；与自有库存的对账结果；结算口径标记供 12 使用。
--     关系：上游 = 2.3.2.1 经营模式、10.4.2.2 备货预报；下游 = 12.3.1 结算、13.4.1.1 利润（按模式分组）。
--     **全托管下"备货"不等于"自有库存"**，平台仓在库数是平台回执的观测值，不得并入自有库存余额；
--     结算按平台规则（可能按件或按日）而非我方发货确认。」
--
-- ---------------------------------------------------------------------------
-- 一、本模块唯一不可让步的一条：备货 ≠ 自有库存
-- ---------------------------------------------------------------------------
-- 蓝图把它写成加粗句，07-inventory.md:5 的「不做」里也点名：
--   「**不在寄售/代销下虚构自有库存**」。
--
-- 本迁移不把它写成一句注释，而是**三道数据库不变量**：
--
--   1) consignment_stock_order_line.counts_as_owned_asset 恒为 false 且 CHECK 写死 false
--      —— 托管备货的货权不是本租户的「自有资产」；
--   2) **平台仓在库数永远进不了自有库存账本**：inventory_entry 上挂一个触发器
--      guard_consignment_stock_entry_not_added —— 凡 ref_type='CONSIGNMENT_STOCK' 的库存分录，
--      on_hand_delta **只许 ≤ 0**（货从我方仓出库是负数，本来就该有），
--      reserved_delta **必须 = 0**（平台在库不占我方预占）。
--      「平台在库数是平台回执的观测值，不得并入自有库存余额」这句加粗约束，
--      由这条触发器在**写入那一刻**挡住，而不是靠应用层记得判断 ——
--      绕过服务层直连 SQL 往 inventory_entry 插一条正数也插不进去。
--      ⚠️ 这条触发器只影响 ref_type='CONSIGNMENT_STOCK' 的分录，别的不动：
--      既有模块（7.1.2.1 库存流水等）的 ref_type 取值与本模块不重叠，回归面 = 0。
--   3) consignment_stock_order_line 上**没有第二个数量口径列**：
--      备货量只有 stocked_quantity 一个来源，平台在库 / 平台已售 / 结算未结
--      全部由平台回执（consignment_stock_receipt / consignment_stock_settlement_receipt）
--      **推导**得出，不落库 —— 落了就会漂移，而漂移的方向一定是「台账说有货、平台说没货」。
--
-- 自有库存那一侧（inventory_balance）与托管那一侧（平台回执）**永远是两个查询面**：
-- 服务层的对账读模型把两边的数**并排返回**，界面上没有「合计库存」这种相加的字段。
--
-- ---------------------------------------------------------------------------
-- 二、经营模式（2.3.2.1）只读，不建影子表
-- ---------------------------------------------------------------------------
-- 店铺经营模式属 channel 服务（shop.operating_mode，channel V1 交付，
-- CHECK(SELF_FULFILLED/FULL_CONSIGNMENT/SEMI_CONSIGNMENT/OVERSEAS_WAREHOUSE/LOCAL)）。
-- 本迁移**不复制**这一列、不建影子表：服务层经只读端口（ChannelShopModePort）读 channel 的
-- GET /channel/shops/{id}，把当时的取值**快照**到 consignment_stock_order.operating_mode。
--
-- 快照是必须的：经营模式是**建单时的事实**，半年后平台改了模式，
-- 去年那张备货单当时按什么模式备的仍然要说得清。
-- 但快照**不��**反过来成为权威 —— 要查店铺现在的经营模式，仍然读 channel。
--
-- 经营模式在本模块里**真的起作用**（不是摆设）：自发货（SELF_FULFILLED）模式下
-- 不存在「发给平台仓备货」这回事，建单直接被拒（400）并说明去哪儿；
-- 全托管 / 半托管才允许建单。
--
-- ---------------------------------------------------------------------------
-- 三、上游：10.4.2.2 备货预报在 channel，禁止跨服务 SQL
-- ---------------------------------------------------------------------------
-- 10.4.2.2 备货预报（channel V243__stock_forecast.sql）**已交付**，但它在**另一个服务**：
-- AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图。
-- 因此预报单与发运数据一律经**只读端口**（ChannelForecastPort，HTTP）读，
-- 读到的 forecast_id / forecast_no 只是**定位键**，本库不建外键、不建影子表。
--
-- 端口纪律（照抄 channel ForecastInventoryQueryPort 的同一套）：
--   * 上游不可达 → 503，明确报「读不到」；**绝不**用 0 或空集合冒充；
--   * 上游正常但没有这条记录 → known=false（无记录 ≠ 数量为 0）。
--
-- ---------------------------------------------------------------------------
-- 四、结算口径（按件 / 按日）
-- ---------------------------------------------------------------------------
-- 蓝图：「按件/按日结算口径标记」「结算按平台规则（可能按件或按日）而非我方发货确认」。
--
-- 本迁移把口径做成**两种真的算得出不同结果的算法**，而不是一个装饰性的标签：
--   PER_ITEM 按件：已结算量 = Σ 结算回执的 settled_quantity；
--                  结算未结 = 平台已售 − 已结算量。
--   PER_DAY  按日：结算回执覆盖一个**日期区间**（period_start ~ period_end）；
--                  已结算 = 落在已结算区间内的售出量；
--                  结算未结 = 落在**未**结算区间内的售出量。
--
-- 两种口径下「未结」不是同一个数：按件看的是**件数差**，按日看的是**哪几天的销还没结**。
-- 「按我方发货确认结算」是明确被蓝图否掉的：发货只能推出「备货量」，
-- 推不出「已售多少件」，所以未结量**只能**由平台回执算。
--
-- 结算口径在建单时确定，**离开草稿态就冻结**（触发器 guard_consignment_stock_basis_frozen）：
-- 中途改口径会让「未结多少」有两个答案，而未结量是要交给 12 结算的数。
--
-- ---------------------------------------------------------------------------
-- 五、单号、状态机与其它约束
-- ---------------------------------------------------------------------------
-- 单号由 DB 发（CSP-yyyyMMdd-000001），不在应用层拼，并发建单不会撞号。
-- 备货单状态机：DRAFT → SHIPPED → RECEIVED → CLOSED；DRAFT/SHIPPED/RECEIVED → CANCELLED。
-- 终态（CLOSED / CANCELLED）不可复活。
--
-- 平台仓收发回执与平台结算回执都是**观测凭据**：append-only（触发器 + 权限层双重禁改禁删）。
-- 两条「不能凭空」的守恒：
--   * 出库回执累计不得超过入库回执累计（不能卖出比收到的更多的货）；
--   * 结算回执累计不得超过出库回执累计（不能结算超过已售）。
--
-- 5 张表全部 tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：CSP-yyyyMMdd-000001
-- ---------------------------------------------------------------------------
CREATE SEQUENCE consignment_stock_order_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. consignment_stock_order · 平台托管备货单头
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_stock_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_code varchar(32) NOT NULL,
  -- 店铺（2.3.2.1 经营模式属 channel）。跨服务逻辑 ID：**不建外键**，
  -- 服务层经只读端口核对经营模式后才允许建单。
  shop_id uuid NOT NULL,
  shop_code varchar(64) NOT NULL,
  shop_name varchar(200) NOT NULL,
  -- 经营模式**快照**（2.3.2.1）。取值域与 channel 的 shop.operating_mode 逐字一致，
  -- 快照是「建单当时的事实」，要查店铺**现在**的模式仍然读 channel（不建影子表）。
  operating_mode varchar(24) NOT NULL CHECK(operating_mode IN
    ('SELF_FULFILLED','FULL_CONSIGNMENT','SEMI_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL')),
  -- 平台仓（仓账户）。10.4.2.1 的仓账户属 channel，本库只存**编码 + 名称快照**用于列表展示。
  platform_warehouse_code varchar(64) NOT NULL,
  platform_warehouse_name varchar(200) NOT NULL DEFAULT '',
  -- 账套法人：备货单归到哪个法人账下（复合外键强制同租户）。
  legal_entity_id uuid NOT NULL,
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 结算口径标记（供 12.3.1 使用）：按件 / 按日。
  settlement_basis varchar(16) NOT NULL CHECK(settlement_basis IN ('PER_ITEM','PER_DAY')),
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','SHIPPED','RECEIVED','CLOSED','CANCELLED')),
  -- 10.4.2.2 备货预报单（跨服务定位键，**不建外键**）。
  forecast_id uuid,
  forecast_no varchar(40) NOT NULL DEFAULT '',
  -- 发运 / 平台确认 / 关闭时刻。
  shipped_at timestamptz,
  platform_received_at timestamptz,
  closed_at timestamptz,
  cancelled_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_code),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(shop_code)<>'' AND btrim(shop_name)<>''),
  CHECK(btrim(platform_warehouse_code)<>''),
  CHECK(forecast_id IS NULL OR btrim(forecast_no)<>''),
  -- 生命周期与时点必须自洽：SHIPPED 必须有发货时刻，CLOSED 必须有关闭时刻。
  CHECK((status<>'SHIPPED') OR shipped_at IS NOT NULL),
  CHECK((status<>'RECEIVED') OR platform_received_at IS NOT NULL),
  CHECK((status<>'CLOSED') OR closed_at IS NOT NULL),
  CHECK((status<>'CANCELLED') OR cancelled_at IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 2. consignment_stock_order_line · 备货单明细（备货量的**唯一**落点）
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_stock_order_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL,
  -- 备货量：我方发给平台的量。**全表只有这一个数量来源**，
  -- 平台在库 / 平台已售 / 结算未结一律由平台回执推导，不在这里落列（落了必漂移）。
  stocked_quantity numeric(19,6) NOT NULL,
  unit varchar(16) NOT NULL DEFAULT 'PCS',
  -- 托管备货的货**不是本租户的自有资产**：CHECK 写死 false。
  -- 要把托管货算进自有资产，必须去动 7.7.1.1（V67）的 stock_owner 档案，
  -- 而那边的 owner_kind='PLATFORM' 同样 counts_as_owned_asset=false —— 两道闸。
  counts_as_owned_asset boolean NOT NULL DEFAULT false
    CHECK(counts_as_owned_asset = false),
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一备货单同一 SKU 只允许一行：两行会让「这 SKU 备了多少」出现第二个答案。
  UNIQUE(tenant_id,order_id,sku_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES consignment_stock_order(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  CHECK(stocked_quantity>0),
  CHECK(btrim(sku_code)<>'' AND btrim(sku_name)<>'')
);

-- ---------------------------------------------------------------------------
-- 3. consignment_stock_receipt · 平台仓收发回执（append-only 观测凭据）
-- ---------------------------------------------------------------------------
-- 平台仓在库数是**平台回执的观测值**（蓝图加粗句），所以它的唯一来源就是这张表。
-- 我方发出的备货量在 consignment_stock_order_line 里，平台确认收到的量在这里，
-- 两者之差就是「在途未确认」—— 对账结果就是这么算出来的。
CREATE TABLE consignment_stock_receipt (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  -- INBOUND 平台入库（平台确认收到我方发出的货）
  -- OUTBOUND 平台出库（平台从平台仓发出 = 平台已售）
  receipt_type varchar(16) NOT NULL CHECK(receipt_type IN ('INBOUND','OUTBOUND')),
  -- 平台回执号：幂等锚点。同一回执重复推送撞唯一约束，不会把在库量算两遍。
  platform_receipt_ref varchar(64) NOT NULL,
  quantity numeric(19,6) NOT NULL,
  -- 回执发生时刻（不是登记时刻）：平台仓的收发时间是业务事实。
  occurred_at timestamptz NOT NULL,
  -- 来源：MANUAL 租户/运营登记 / PLATFORM_FEED 平台对接送数。
  -- 来源必填且受词表约束：不允许出现「不知道这个数哪来的」的行。
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL'
    CHECK(source_system IN ('MANUAL','PLATFORM_FEED')),
  note text NOT NULL DEFAULT '',
  recorded_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_line_id,receipt_type,platform_receipt_ref),
  FOREIGN KEY(tenant_id,order_id) REFERENCES consignment_stock_order(tenant_id,id),
  FOREIGN KEY(tenant_id,order_line_id) REFERENCES consignment_stock_order_line(tenant_id,id),
  CHECK(quantity>0),
  CHECK(btrim(platform_receipt_ref)<>'')
);

-- ---------------------------------------------------------------------------
-- 4. consignment_stock_settlement_receipt · 平台结算回执（append-only）
-- ---------------------------------------------------------------------------
-- 结算口径标记的**落地**：回执自带口径（按件 / 按日），因为口径是平台合同规则，
-- 不是我方自选的。本单据是「结算未结」这个数的唯一来源。
CREATE TABLE consignment_stock_settlement_receipt (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  -- 结算口径（按件 / 按日）。与备货单上的口径一致，由服务层校验；
  -- 快照在这里是为了「半年后这张回执当初按什么口径结的」仍然说得清。
  settlement_basis varchar(16) NOT NULL CHECK(settlement_basis IN ('PER_ITEM','PER_DAY')),
  -- 归期区间（按日口径必填：按件口径下区间只是平台给的记账区间，不参与未结计算）。
  period_start date NOT NULL,
  period_end date NOT NULL,
  -- 已结算数量。PER_ITEM = 结算件数；PER_DAY = 该区间内结算的件数。
  settled_quantity numeric(19,6) NOT NULL,
  -- 平台回执里的金额。**可空**：平台没给金额时如实留空（null = 平台没给），
  -- 绝不用 0 冒充（0 会被读成「平台结了 0 元」）。金额归 12 财务，本模块不替它记账。
  settled_amount numeric(19,4),
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 平台结算回执号：幂等锚点。
  platform_ref varchar(64) NOT NULL,
  received_at timestamptz NOT NULL,
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL'
    CHECK(source_system IN ('MANUAL','PLATFORM_FEED')),
  note text NOT NULL DEFAULT '',
  recorded_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_line_id,platform_ref),
  FOREIGN KEY(tenant_id,order_id) REFERENCES consignment_stock_order(tenant_id,id),
  FOREIGN KEY(tenant_id,order_line_id) REFERENCES consignment_stock_order_line(tenant_id,id),
  CHECK(settled_quantity>=0),
  CHECK(settled_amount IS NULL OR settled_amount>=0),
  CHECK(period_end>=period_start),
  CHECK(btrim(platform_ref)<>'')
);

-- ---------------------------------------------------------------------------
-- 5. consignment_stock_audit · 审计（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_stock_audit (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  event_type varchar(32) NOT NULL,
  detail text NOT NULL DEFAULT '',
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES consignment_stock_order(tenant_id,id)
);

-- ---------------------------------------------------------------------------
-- 6. 索引（RLS 之后的实际查询面）
-- ---------------------------------------------------------------------------
CREATE INDEX idx_cs_order_list ON consignment_stock_order(tenant_id, status, created_at DESC);
CREATE INDEX idx_cs_order_shop ON consignment_stock_order(tenant_id, shop_id, status);
CREATE INDEX idx_cs_order_basis ON consignment_stock_order(tenant_id, settlement_basis);
CREATE INDEX idx_cs_line_order ON consignment_stock_order_line(tenant_id, order_id);
CREATE INDEX idx_cs_line_sku ON consignment_stock_order_line(tenant_id, sku_id);
-- 回执累计（入/出库）是台账与对账的主查询面。
CREATE INDEX idx_cs_receipt_line ON consignment_stock_receipt(tenant_id, order_line_id, receipt_type);
CREATE INDEX idx_cs_receipt_order ON consignment_stock_receipt(tenant_id, order_id, occurred_at DESC);
CREATE INDEX idx_cs_receipt_time ON consignment_stock_receipt(tenant_id, occurred_at);
CREATE INDEX idx_cs_settle_line ON consignment_stock_settlement_receipt(tenant_id, order_line_id);
CREATE INDEX idx_cs_settle_order ON consignment_stock_settlement_receipt(tenant_id, order_id, period_start DESC);
CREATE INDEX idx_cs_audit_order ON consignment_stock_audit(tenant_id, order_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 7. 触发器
-- ---------------------------------------------------------------------------

-- (a)【本模块的红线】平台仓在库数**永远进不了**自有库存账本。
--     凡 ref_type='CONSIGNMENT_STOCK' 的库存分录：
--       on_hand_delta 只许 ≤ 0（货从我方仓出库是负数，本就该有）；
--       reserved_delta 必须 = 0（平台在库不占我方预占）。
--     正数 on_hand_delta 就是「把平台在库数并进了自有库存余额」——
--     蓝图加粗句明令禁止的那件事，在**写入那一刻**被数据库挡住。
--
--     ⚠️ 回归面：只影响 ref_type='CONSIGNMENT_STOCK' 的分录。
--     既有模块的 ref_type 取值与它不重叠（7.1.2.1 库存流水用的是自己的 ref_type 词表），
--     所以既有插入路径完全不受影响；本触发器也**不改** inventory_entry 的任何列/约束/索引。
CREATE OR REPLACE FUNCTION guard_consignment_stock_entry_not_added() RETURNS trigger AS $$
DECLARE rt varchar(32);
BEGIN
  SELECT t.ref_type INTO rt FROM inventory_transaction t
   WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.transaction_id;
  IF rt = 'CONSIGNMENT_STOCK' THEN
    IF NEW.on_hand_delta > 0 THEN
      RAISE EXCEPTION '平台托管备货的单据不允许把货加进自有库存账本（在手增量 %）：'
        '全托管下「备货」不等于「自有库存」，平台仓在库数是平台回执的观测值，不得并入自有库存余额',
        NEW.on_hand_delta USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.reserved_delta <> 0 THEN
      RAISE EXCEPTION '平台托管备货的单据不允许预占自有库存（预占增量 %）：'
        '平台仓在库数不占我方预占，预占的是自有货',
        NEW.reserved_delta USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_entry_not_added
  BEFORE INSERT ON inventory_entry
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_entry_not_added();

-- (b) 备货单状态机：DRAFT → SHIPPED → RECEIVED → CLOSED；DRAFT/SHIPPED/RECEIVED → CANCELLED。
--     CLOSED / CANCELLED 是终态，不可复活。
CREATE OR REPLACE FUNCTION guard_consignment_stock_order_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT'     THEN NEW.status IN ('SHIPPED','CANCELLED')
    WHEN 'SHIPPED'   THEN NEW.status IN ('RECEIVED','CANCELLED')
    WHEN 'RECEIVED'  THEN NEW.status IN ('CLOSED','CANCELLED')
    ELSE false   -- CLOSED / CANCELLED 是终态
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION 'consignment stock order % cannot move % -> %', OLD.order_code, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_order_state
  BEFORE UPDATE ON consignment_stock_order
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_order_state();

-- (c) 结算口径冻结：离开草稿态后不许再改口径。
--     中途改口径会让「结算未结多少」出现第二个答案，而未结量是要交给 12 结算的数。
CREATE OR REPLACE FUNCTION guard_consignment_stock_basis_frozen() RETURNS trigger AS $$
BEGIN
  IF NEW.settlement_basis IS DISTINCT FROM OLD.settlement_basis THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION '备货单 % 已离开草稿态，结算口径（按件/按日）不可再改：'
        '中途改口径会让「结算未结多少」出现两个答案', OLD.order_code
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_basis_frozen
  BEFORE UPDATE ON consignment_stock_order
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_basis_frozen();

-- (d) 已关闭 / 已作废的备货单，其明细行不得再改数量（账已定稿）。
CREATE OR REPLACE FUNCTION guard_consignment_stock_line_frozen() RETURNS trigger AS $$
DECLARE st varchar(16);
BEGIN
  IF NEW.stocked_quantity IS NOT DISTINCT FROM OLD.stocked_quantity THEN
    RETURN NEW;
  END IF;
  SELECT status INTO st FROM consignment_stock_order
   WHERE tenant_id = NEW.tenant_id AND id = NEW.order_id;
  IF st IN ('CLOSED','CANCELLED') THEN
    RAISE EXCEPTION '备货单明细行 % 所属单据已 %，数量不可再改', NEW.id, st
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_line_frozen
  BEFORE UPDATE ON consignment_stock_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_line_frozen();

-- (e) 守恒一：平台出库累计不得超过入库累计（不能卖出比收到的更多的货）。
CREATE OR REPLACE FUNCTION guard_consignment_stock_receipt_bounds() RETURNS trigger AS $$
DECLARE inbound numeric(19,6); outbound numeric(19,6);
BEGIN
  SELECT coalesce(sum(quantity) FILTER (WHERE receipt_type='INBOUND'),0),
         coalesce(sum(quantity) FILTER (WHERE receipt_type='OUTBOUND'),0)
    INTO inbound, outbound
    FROM consignment_stock_receipt
   WHERE tenant_id = NEW.tenant_id AND order_line_id = NEW.order_line_id;
  IF NEW.receipt_type = 'OUTBOUND' THEN
    outbound := outbound + NEW.quantity;
  ELSE
    inbound := inbound + NEW.quantity;
  END IF;
  IF outbound > inbound THEN
    RAISE EXCEPTION '平台出库累计 % 超过入库累计 %：不能卖出平台没收到的货',
      outbound, inbound USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_receipt_bounds
  BEFORE INSERT ON consignment_stock_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_receipt_bounds();

-- (f) 守恒二：结算累计不得超过平台已售（出库回执累计）。
--     「结算按平台规则而非我方发货确认」的另一面：没卖出去的不许结。
CREATE OR REPLACE FUNCTION guard_consignment_stock_settlement_bounds() RETURNS trigger AS $$
DECLARE sold numeric(19,6); settled numeric(19,6);
BEGIN
  SELECT coalesce(sum(quantity),0) INTO sold
    FROM consignment_stock_receipt
   WHERE tenant_id = NEW.tenant_id AND order_line_id = NEW.order_line_id
     AND receipt_type = 'OUTBOUND';
  SELECT coalesce(sum(settled_quantity),0) INTO settled
    FROM consignment_stock_settlement_receipt
   WHERE tenant_id = NEW.tenant_id AND order_line_id = NEW.order_line_id;
  IF settled + NEW.settled_quantity > sold THEN
    RAISE EXCEPTION '结算累计 % 超过平台已售 %：没卖出去的不许结',
      settled + NEW.settled_quantity, sold USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_settlement_bounds
  BEFORE INSERT ON consignment_stock_settlement_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_settlement_bounds();

-- (g) 平台回执与结算回执是观测凭据：禁改禁删（权限层再收一道，见下）。
CREATE OR REPLACE FUNCTION guard_consignment_stock_receipt_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '平台回执是 append-only 凭据；不允许 %，要改请登记冲正回执', TG_OP
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_receipt_immutable
  BEFORE UPDATE OR DELETE ON consignment_stock_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_receipt_immutable();

CREATE OR REPLACE FUNCTION guard_consignment_stock_settlement_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '平台结算回执是 append-only 凭据；不允许 %', TG_OP
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_settlement_immutable
  BEFORE UPDATE OR DELETE ON consignment_stock_settlement_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_settlement_immutable();

-- (h) 审计 append-only。
CREATE OR REPLACE FUNCTION guard_consignment_stock_audit_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'consignment stock audit is append-only' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_stock_audit_append_only
  BEFORE UPDATE OR DELETE ON consignment_stock_audit
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_stock_audit_append_only();

-- ===========================================================================
-- 8. 多租户与授权（与 V1/V2/V67/V71/V115 同一套写法）
-- ===========================================================================
-- 五张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['consignment_stock_order','consignment_stock_order_line',
                             'consignment_stock_receipt','consignment_stock_settlement_receipt',
                             'consignment_stock_audit'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  consignment_stock_order, consignment_stock_order_line, consignment_stock_receipt,
  consignment_stock_settlement_receipt, consignment_stock_audit TO warehouse_app;
-- 平台回执、结算回执与审计是凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
REVOKE UPDATE,DELETE ON
  consignment_stock_receipt, consignment_stock_settlement_receipt, consignment_stock_audit FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V67/V71/V115 同口径）。
REVOKE ALL ON
  consignment_stock_order, consignment_stock_order_line, consignment_stock_receipt,
  consignment_stock_settlement_receipt, consignment_stock_audit FROM starcon_app;

-- 单号发号器同样要显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列
-- （漏了就是 "permission denied for sequence"，而错误文案完全指不到授权上）。
GRANT USAGE, SELECT ON SEQUENCE consignment_stock_order_code_seq TO warehouse_app;

-- ---------------------------------------------------------------------------
-- 9. 跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表/序列/索引/触发器**：
--   * 没有 ALTER TABLE consignment_*（7.7.1.2 已交付，V71）—— 表名前缀不同，互不相干；
--   * 没有 ALTER TABLE vmi_*（7.7.2.1 已交付，V115）—— 同上；
--   * 没有 ALTER TABLE stock_bucket / inventory_balance（7.1.1.3 的唯一写入所有者）——
--     本模块**只读** inventory_balance 做分账对账；
--   * 唯一的跨表动作是给 inventory_entry **加一个 BEFORE INSERT 触发器**
--     （不改列、不改约束、不改索引），且只对 ref_type='CONSIGNMENT_STOCK' 的分录生效，
--     既有模块的插入路径不受影响。
-- 因此既有模块的插入路径完全不受影响。
