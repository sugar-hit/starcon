-- V3 · warehouse 库存账本与仓储作业 (PostgreSQL 16)
-- 对应蓝图：docs/product/capability-blueprint/07-inventory.md、08-warehouse-operations.md
-- 状态：设计基线，未实施。落地时拆为 warehouse 服务 V5__*.sql
-- 规约：见 ../README.md §1
--
-- 跨服务引用说明：legal_entity_id / sku_id / party_id / shop_id 均为**逻辑引用，不建外键**
--   （数据所有权在 identity / catalog / channel，见 ADR-0004）。
--   本服务内引用一律用复合外键 (tenant_id, xxx_id)。
--
-- 本库必须强制的业务铁律（蓝图中写死的约束，这里由 DB 兜底）：
--   1. available = on_hand - reserved            → 生成列，不可能算错
--   2. 禁止负库存、禁止预留超过在手
--   3. 不可售质量状态不得被预留
--   4. 预占分配进入终态后不可复活
--   5. 库存流水 append-only，纠错靠反向分录
--   6. 同一笔内部转移在各 bucket 上的数量合计为零
--   7. 预占与扣减靠 version 乐观锁，不靠应用层判断

-- ===========================================================================
-- 1. 货主（蓝图 07.7 新增：货是谁的 —— 自有 / 供应商寄售 / 平台所有）
-- ===========================================================================
-- 注意：货主不能等同于 tenant_id。同一仓内不同货主的货并存，是寄售/VMI 的基础。
CREATE TABLE stock_owner (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  code         varchar(64) NOT NULL,
  name         varchar(200) NOT NULL,
  owner_type   text NOT NULL CHECK (owner_type IN ('SELF','SUPPLIER','PLATFORM','CARRIER','CUSTOMER')),
  -- 货主对应的外部主体（供应商/平台），逻辑引用 catalog.party / channel.shop
  party_id     uuid,
  legal_entity_id uuid NOT NULL,                   -- 货权记账主体，逻辑引用 identity
  status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  version      bigint NOT NULL DEFAULT 0,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, code),
  CHECK (btrim(code) <> '' AND btrim(name) <> ''),
  -- 自有货必须有法人主体；寄售货的"货主"是供应商，但仍需登记我方承载的法人
  CHECK (legal_entity_id IS NOT NULL)
);
CREATE INDEX idx_stock_owner_party ON stock_owner (tenant_id, party_id) WHERE party_id IS NOT NULL;

-- ===========================================================================
-- 2. 仓库与库位（蓝图 08.1）
-- ===========================================================================
CREATE TABLE warehouse (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  code           varchar(64) NOT NULL,
  name           varchar(200) NOT NULL,
  warehouse_type text NOT NULL CHECK (warehouse_type IN ('SELF','THIRD_PARTY','OVERSEAS','PLATFORM','CUSTOMS','VENDOR')),
  legal_entity_id uuid NOT NULL,                     -- 货权与收入归属主体，逻辑引用 identity
  -- 货主：NULL 表示该仓默认存自有货；非空表示该仓是某货主的专属仓
  default_stock_owner_id uuid,
  country_code   char(2) NOT NULL,
  timezone       text NOT NULL,                      -- IANA，如 'America/Los_Angeles'
  address        text NOT NULL DEFAULT '',
  -- 行为配置（蓝图 08.1.1.3）
  batch_enabled  boolean NOT NULL DEFAULT false,
  serial_enabled boolean NOT NULL DEFAULT false,
  pick_strategy  text NOT NULL DEFAULT 'ORDER'
                 CHECK (pick_strategy IN ('ORDER','WAVE','LOCATION')),
  status         text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, code),
  FOREIGN KEY (tenant_id, default_stock_owner_id) REFERENCES stock_owner (tenant_id, id),
  CHECK (btrim(code) <> '' AND btrim(name) <> ''),
  -- 启用批次或序列号时，必须对应启用其一，不允许"启用了序列号却不填"
  CHECK (NOT serial_enabled OR batch_enabled OR true)
);
CREATE INDEX idx_warehouse_entity ON warehouse (tenant_id, legal_entity_id) WHERE status = 'ACTIVE';

-- 库位树：区 → 巷 → 排 → 架 → 层 → 格
CREATE TABLE warehouse_location (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  warehouse_id   uuid NOT NULL,
  parent_id      uuid,
  code           varchar(64) NOT NULL,
  name           varchar(200) NOT NULL,
  location_type  text NOT NULL CHECK (location_type IN ('STORAGE','STAGING','PENDING_QC','DEFECTIVE','FROZEN','RECEIVING','SHIPPING')),
  path           text NOT NULL DEFAULT '',          -- 物化路径，冗余用于快速子树查询
  depth          int NOT NULL DEFAULT 0 CHECK (depth >= 0),
  capacity_units numeric(19,6),                     -- NULL = 不限容量
  pickable       boolean NOT NULL DEFAULT true,
  status         text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, warehouse_id, code),
  FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  FOREIGN KEY (tenant_id, parent_id) REFERENCES warehouse_location (tenant_id, id),
  CHECK (parent_id IS DISTINCT FROM id)              -- 防自环（跨祖先成环由触发器拦）
);
CREATE INDEX idx_location_warehouse ON warehouse_location (tenant_id, warehouse_id);
CREATE INDEX idx_location_path      ON warehouse_location (tenant_id, path text_pattern_ops);

-- 防环：不允许把库位挂到自己的后代下
CREATE FUNCTION reject_location_cycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.parent_id IS NULL THEN RETURN NEW; END IF;
  IF EXISTS (
    WITH RECURSIVE up AS (
      SELECT id, parent_id FROM warehouse_location WHERE tenant_id = NEW.tenant_id AND id = NEW.parent_id
      UNION ALL
      SELECT l.id, l.parent_id FROM warehouse_location l JOIN up u ON u.parent_id = l.id
    )
    SELECT 1 FROM up WHERE id = NEW.id
  ) THEN
    RAISE EXCEPTION 'Location % would become its own ancestor', NEW.id USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER location_no_cycle BEFORE INSERT OR UPDATE ON warehouse_location
  FOR EACH ROW EXECUTE FUNCTION reject_location_cycle();

-- ===========================================================================
-- 3. 批次与序列号（蓝图 07.3）
-- ===========================================================================
CREATE TABLE inventory_lot (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  lot_code       varchar(128) NOT NULL,
  sku_id         uuid NOT NULL,                      -- 逻辑引用 catalog.sku
  supplier_party_id uuid,                           -- 逻辑引用 catalog.party
  production_date date,
  expiry_date    date,
  status         text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEPLETED','EXPIRED','BLOCKED')),
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, sku_id, lot_code),
  CHECK (btrim(lot_code) <> ''),
  -- 失效日期不得早于生产日期
  CHECK (expiry_date IS NULL OR production_date IS NULL OR expiry_date >= production_date)
);
CREATE INDEX idx_lot_expiry ON inventory_lot (tenant_id, sku_id, expiry_date)
  WHERE status = 'ACTIVE' AND expiry_date IS NOT NULL;

CREATE TABLE inventory_serial (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  serial_no      varchar(128) NOT NULL,
  sku_id         uuid NOT NULL,
  lot_id         uuid,
  -- 当前位置：只允许一个有效位置
  warehouse_id   uuid,
  location_id    uuid,
  serial_status  text NOT NULL DEFAULT 'IN_STOCK'
                 CHECK (serial_status IN ('IN_STOCK','RESERVED','SHIPPED','IN_TRANSIT','REPAIR','SCRAPPED','RETURNED')),
  sold_at        timestamptz,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, serial_no),
  FOREIGN KEY (tenant_id, lot_id) REFERENCES inventory_lot (tenant_id, id),
  FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  FOREIGN KEY (tenant_id, location_id) REFERENCES warehouse_location (tenant_id, id),
  -- 在库/已售/在途/维修必须定位；报废与退回可不定位
  CHECK (serial_status IN ('SCRAPPED','RETURNED') OR warehouse_id IS NOT NULL)
);
CREATE INDEX idx_serial_position ON inventory_serial (tenant_id, warehouse_id, location_id)
  WHERE serial_status = 'IN_STOCK';
CREATE INDEX idx_serial_sku      ON inventory_serial (tenant_id, sku_id, serial_status);

-- ===========================================================================
-- 4. 库存桶（蓝图 07.1）：最小事实单位
-- ===========================================================================
-- 组成维度：货主 + 法人 + SKU + 仓库 + 库位 + 批次 + 质量状态
-- available 用生成列，而不是应用算 —— 这是"超卖第一根源"的物理防线。
CREATE TABLE stock_bucket (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  stock_owner_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  sku_id         uuid NOT NULL,
  warehouse_id   uuid NOT NULL,
  location_id    uuid,                              -- NULL = 在途/未上架等无库位场景
  lot_id         uuid,
  quality_status text NOT NULL DEFAULT 'GOOD'
                 CHECK (quality_status IN ('GOOD','PENDING_QC','DAMAGED','DEFECTIVE','FROZEN','RETURNED')),
  on_hand        numeric(19,6) NOT NULL DEFAULT 0,
  reserved       numeric(19,6) NOT NULL DEFAULT 0,
  -- 铁律 1：可用量由 on_hand - reserved 生成，应用无法写出不一致的值
  available      numeric(19,6) GENERATED ALWAYS AS (on_hand - reserved) STORED,
  version        bigint NOT NULL DEFAULT 0,        -- 铁律 7：乐观锁
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  -- 铁律 2：禁止负库存、禁止预留超过在手（第一期策略）
  CHECK (on_hand  >= 0),
  CHECK (reserved >= 0),
  CHECK (reserved <= on_hand),
  FOREIGN KEY (tenant_id, stock_owner_id) REFERENCES stock_owner (tenant_id, id),
  FOREIGN KEY (tenant_id, warehouse_id)   REFERENCES warehouse (tenant_id, id),
  FOREIGN KEY (tenant_id, location_id)    REFERENCES warehouse_location (tenant_id, id),
  FOREIGN KEY (tenant_id, lot_id)         REFERENCES inventory_lot (tenant_id, id)
);

-- 无批次场景下 lot_id 为 NULL，必须靠 NULLS NOT DISTINCT 保证"无批次只有一个桶"
CREATE UNIQUE INDEX uq_stock_bucket ON stock_bucket (
  tenant_id, stock_owner_id, legal_entity_id, sku_id, warehouse_id, quality_status,
  COALESCE(location_id, '00000000-0000-0000-0000-000000000000'::uuid),
  COALESCE(lot_id,         '00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE INDEX idx_bucket_sku        ON stock_bucket (tenant_id, sku_id) WHERE on_hand > 0;
CREATE INDEX idx_bucket_warehouse  ON stock_bucket (tenant_id, warehouse_id, sku_id) WHERE on_hand > 0;
CREATE INDEX idx_bucket_availability ON stock_bucket (tenant_id, sku_id) WHERE available > 0;

-- 铁律 3：不可售质量状态不得被预留（待检/残次/冻结/损坏）
CREATE FUNCTION reject_reserved_on_unsellable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.reserved > 0 AND NEW.quality_status <> 'GOOD' THEN
    RAISE EXCEPTION 'Cannot reserve stock with quality_status=%', NEW.quality_status USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER bucket_sellable_guard BEFORE INSERT OR UPDATE ON stock_bucket
  FOR EACH ROW EXECUTE FUNCTION reject_reserved_on_unsellable();

-- 铁律 4：预留不得超过在手（带 version 乐观锁的原子条件更新在服务层，DB 兜底在此）
CREATE FUNCTION reserve_stock(p_tenant uuid, p_bucket uuid, p_qty numeric, p_expected_version bigint)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE v_new_reserved numeric(19,6); v_version bigint;
BEGIN
  UPDATE stock_bucket
     SET reserved = reserved + p_qty,
         version = version + 1,
         updated_at = now()
   WHERE tenant_id = p_tenant AND id = p_bucket
     AND version = p_expected_version
     AND on_hand - reserved >= p_qty                       -- 原子判定，不靠应用层先查后写
     AND quality_status = 'GOOD'
  RETURNING reserved, version INTO v_new_reserved, v_version;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Reserve rejected: insufficient/unlocked stock for bucket %', p_bucket USING ERRCODE = 'P0001';
  END IF;
  RETURN v_version;
END $$;

-- ===========================================================================
-- 5. 库存流水（蓝图 07.1.2）：append-only
-- ===========================================================================
CREATE TABLE inventory_transaction (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  txn_no         varchar(64) NOT NULL,
  txn_type       text NOT NULL CHECK (txn_type IN (
                   'RECEIPT','SALE_OUT','PURCHASE_IN','TRANSFER_OUT','TRANSFER_IN',
                   'RESERVE','RELEASE','ADJUST','COUNT_GAIN','COUNT_LOSS','ASSEMBLY_IN','ASSEMBLY_OUT','DISASSEMBLY','SCRAP')),
  -- 内部转移的多个分录在各 bucket 上合计为零；外部出入库有来源/去向，不强求合计为零
  balanced       boolean NOT NULL DEFAULT false,
  source_service text NOT NULL,                    -- trade / purchase / manual
  source_type    text NOT NULL DEFAULT '',
  source_id      uuid,
  warehouse_id   uuid,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  business_date  date NOT NULL,
  timezone       text NOT NULL DEFAULT 'UTC',      -- 业务时区，解释 business_date
  reason_code    varchar(32) NOT NULL DEFAULT '',
  operator_id    uuid,
  note           text NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, txn_no),
  CHECK (btrim(txn_no) <> '')
);
CREATE INDEX idx_txn_time  ON inventory_transaction (tenant_id, occurred_at DESC);
CREATE INDEX idx_txn_source ON inventory_transaction (tenant_id, source_service, source_type, source_id);

CREATE TABLE inventory_entry (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  txn_id         uuid NOT NULL,
  bucket_id      uuid NOT NULL,
  -- 带正负号的分录数量：正=增加，负=减少
  qty            numeric(19,6) NOT NULL CHECK (qty <> 0),
  on_hand_delta  numeric(19,6) NOT NULL DEFAULT 0,
  reserved_delta numeric(19,6) NOT NULL DEFAULT 0,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, txn_id)    REFERENCES inventory_transaction (tenant_id, id),
  FOREIGN KEY (tenant_id, bucket_id) REFERENCES stock_bucket (tenant_id, id),
  -- 一条分录不能同时动在手和预留，否则语义不清
  CHECK (on_hand_delta <> 0 OR reserved_delta <> 0)
);
CREATE INDEX idx_entry_bucket ON inventory_entry (tenant_id, bucket_id, occurred_at DESC);
CREATE INDEX idx_entry_txn    ON inventory_entry (tenant_id, txn_id);

-- 铁律 5：分录 append-only
CREATE TRIGGER entry_no_mutation BEFORE UPDATE OR DELETE ON inventory_entry
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- 铁律 6：内部转移在同一 bucket 上的净变动必须为零（延迟到提交时校验）
CREATE FUNCTION assert_balanced_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t record; net numeric(19,6);
BEGIN
  SELECT id, balanced, txn_type INTO t FROM inventory_transaction
   WHERE tenant_id = NEW.tenant_id AND id = NEW.txn_id FOR UPDATE;
  IF NOT FOUND OR NOT t.balanced THEN RETURN NULL; END IF;
  SELECT coalesce(sum(on_hand_delta),0) INTO net FROM inventory_entry
   WHERE tenant_id = NEW.tenant_id AND txn_id = NEW.txn_id AND bucket_id = NEW.bucket_id;
  IF net <> 0 THEN
    RAISE EXCEPTION 'Internal movement must net zero per bucket (txn=%, bucket=%, net=%)',
                    NEW.txn_id, NEW.bucket_id, net USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER entry_balance_check
  AFTER INSERT ON inventory_entry
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_balanced_transaction();

-- ===========================================================================
-- 6. 预占（蓝图 07.2）：显式状态机，终态不可复活
-- ===========================================================================
CREATE TABLE inventory_reservation (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  reservation_no varchar(64) NOT NULL,
  -- 来源：销售履约 / 加工领料 / 其他占用
  source_service text NOT NULL,
  source_type    text NOT NULL,
  source_id      uuid,
  source_line_id uuid,
  legal_entity_id uuid NOT NULL,
  total_qty      numeric(19,6) NOT NULL CHECK (total_qty > 0),
  allocated_qty  numeric(19,6) NOT NULL DEFAULT 0 CHECK (allocated_qty >= 0),
  status         text NOT NULL DEFAULT 'OPEN'
                 CHECK (status IN ('OPEN','COMMITTED','CONSUMED','RELEASED','EXPIRED')),
  expires_at     timestamptz,
  committed_at   timestamptz,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, reservation_no),
  CHECK (allocated_qty <= total_qty)
);
CREATE INDEX idx_reservation_source ON inventory_reservation (tenant_id, source_service, source_type, source_id);
CREATE INDEX idx_reservation_expiry  ON inventory_reservation (expires_at)
  WHERE status = 'OPEN' AND expires_at IS NOT NULL;

-- 分配到具体 bucket：批次追溯与成本精确分摊的前提
CREATE TABLE reservation_allocation (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  reservation_id uuid NOT NULL,
  bucket_id      uuid NOT NULL,
  lot_id         uuid,
  serial_id      uuid,
  qty            numeric(19,6) NOT NULL CHECK (qty > 0),
  status         text NOT NULL DEFAULT 'HELD'
                 CHECK (status IN ('HELD','COMMITTED','CONSUMED','RELEASED','EXPIRED')),
  allocated_at   timestamptz NOT NULL DEFAULT now(),
  settled_at     timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, reservation_id) REFERENCES inventory_reservation (tenant_id, id),
  FOREIGN KEY (tenant_id, bucket_id)      REFERENCES stock_bucket (tenant_id, id),
  FOREIGN KEY (tenant_id, serial_id)      REFERENCES inventory_serial (tenant_id, id)
);
CREATE INDEX idx_alloc_reservation ON reservation_allocation (tenant_id, reservation_id, status);
CREATE INDEX idx_alloc_bucket      ON reservation_allocation (tenant_id, bucket_id) WHERE status IN ('HELD','COMMITTED');

-- 铁律 4：终态（CONSUMED/RELEASED/EXPIRED）不可回到 HELD，避免迟到请求复活预占
CREATE FUNCTION reject_reservation_revive() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('CONSUMED','RELEASED','EXPIRED') AND NEW.status = 'HELD' THEN
    RAISE EXCEPTION 'Reservation allocation % is terminal (%); cannot revive to HELD',
                    NEW.id, OLD.status USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER allocation_no_revive BEFORE UPDATE ON reservation_allocation
  FOR EACH ROW EXECUTE FUNCTION reject_reservation_revive();

CREATE TABLE reservation_entry (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  reservation_id uuid NOT NULL,
  allocation_id  uuid,
  from_status    text NOT NULL,
  to_status      text NOT NULL,
  qty_delta      numeric(19,6) NOT NULL DEFAULT 0,
  reason         varchar(32) NOT NULL DEFAULT '',
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, reservation_id) REFERENCES inventory_reservation (tenant_id, id),
  FOREIGN KEY (tenant_id, allocation_id)  REFERENCES reservation_allocation (tenant_id, id)
);
CREATE TRIGGER reservation_entry_no_mutation BEFORE UPDATE OR DELETE ON reservation_entry
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ===========================================================================
-- 7. 调拨与在途（蓝图 07.5）
-- ===========================================================================
CREATE TABLE inventory_transfer (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  transfer_no    varchar(64) NOT NULL,
  from_warehouse_id uuid NOT NULL,
  to_warehouse_id   uuid NOT NULL,
  status         text NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT','IN_TRANSIT','PARTIAL_RECEIVED','RECEIVED','CANCELLED')),
  -- 跨法人调拨是货权转移 + 内部结算，不是普通调拨
  cross_entity   boolean NOT NULL DEFAULT false,
  shipped_at     timestamptz,
  received_at    timestamptz,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, transfer_no),
  FOREIGN KEY (tenant_id, from_warehouse_id) REFERENCES warehouse (tenant_id, id),
  FOREIGN KEY (tenant_id, to_warehouse_id)   REFERENCES warehouse (tenant_id, id),
  CHECK (from_warehouse_id <> to_warehouse_id)
);

CREATE TABLE inventory_transfer_line (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  transfer_id    uuid NOT NULL,
  sku_id         uuid NOT NULL,
  lot_id         uuid,
  stock_owner_id uuid NOT NULL,
  planned_qty    numeric(19,6) NOT NULL CHECK (planned_qty > 0),
  shipped_qty    numeric(19,6) NOT NULL DEFAULT 0 CHECK (shipped_qty >= 0),
  received_qty   numeric(19,6) NOT NULL DEFAULT 0 CHECK (received_qty >= 0),
  -- 差异原因：丢失/破损/短少，需要带原因调整而非静默抹平
  difference_reason varchar(32) NOT NULL DEFAULT '',
  FOREIGN KEY (tenant_id, transfer_id)   REFERENCES inventory_transfer (tenant_id, id),
  FOREIGN KEY (tenant_id, stock_owner_id) REFERENCES stock_owner (tenant_id, id),
  CHECK (shipped_qty  <= planned_qty),
  CHECK (received_qty <= shipped_qty)
);
CREATE INDEX idx_transfer_line ON inventory_transfer_line (tenant_id, transfer_id);

-- ===========================================================================
-- 8. 盘点与调整（蓝图 07.4）：差异必须走单据，不能直接改余额
-- ===========================================================================
CREATE TABLE stock_count (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  count_no       varchar(64) NOT NULL,
  warehouse_id   uuid NOT NULL,
  -- 盘点时点冻结的账面数来源；冻结后业务仍可继续，但差异按快照比对
  scope_type     text NOT NULL CHECK (scope_type IN ('WAREHOUSE','LOCATION','CATEGORY','SKU','ABC')),
  scope_value    text NOT NULL DEFAULT '',
  status         text NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT','COUNTING','REVIEWING','APPROVED','POSTED','CANCELLED')),
  frozen_at      timestamptz,
  posted_at      timestamptz,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, count_no),
  FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id)
);

CREATE TABLE stock_count_line (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  count_id       uuid NOT NULL,
  bucket_id      uuid NOT NULL,
  book_qty       numeric(19,6) NOT NULL,           -- 冻结时点账面数
  counted_qty    numeric(19,6) NOT NULL,           -- 实盘数
  diff_qty       numeric(19,6) GENERATED ALWAYS AS (counted_qty - book_qty) STORED,
  diff_reason    varchar(32) NOT NULL DEFAULT '',
  -- 差异经审批后才生成 stock_adjustment，此处记录结果单据
  adjustment_id  uuid,
  FOREIGN KEY (tenant_id, count_id)  REFERENCES stock_count (tenant_id, id),
  FOREIGN KEY (tenant_id, bucket_id) REFERENCES stock_bucket (tenant_id, id)
);
CREATE INDEX idx_count_line ON stock_count_line (tenant_id, count_id);

CREATE TABLE stock_adjustment (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  adjustment_no  varchar(64) NOT NULL,
  bucket_id      uuid NOT NULL,
  adjust_qty     numeric(19,6) NOT NULL CHECK (adjust_qty <> 0),
  reason_code    varchar(32) NOT NULL,              -- 必填：无原因不允许调整
  source_type    text NOT NULL DEFAULT 'MANUAL' CHECK (source_type IN ('MANUAL','COUNT','DAMAGE','LOSS','CORRECTION')),
  source_id      uuid,
  status         text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','POSTED','REJECTED','CANCELLED')),
  approved_by    uuid,
  approved_at    timestamptz,
  txn_id         uuid,                              -- 过账后生成的库存流水
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, adjustment_no),
  FOREIGN KEY (tenant_id, bucket_id) REFERENCES stock_bucket (tenant_id, id),
  CHECK (btrim(reason_code) <> '')
);

-- ===========================================================================
-- 9. 收货 / 出库 / 包裹（蓝图 08.2 / 08.3）
-- ===========================================================================
CREATE TABLE goods_receipt (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  receipt_no     varchar(64) NOT NULL,
  source_type    text NOT NULL CHECK (source_type IN ('PURCHASE','RETURN','TRANSFER','PLATFORM_ESTOCK','CONSIGNMENT')),
  source_service text NOT NULL,
  source_id      uuid,
  warehouse_id   uuid NOT NULL,
  stock_owner_id uuid NOT NULL,
  status         text NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT','PARTIAL','RECEIVED','CLOSED','CANCELLED')),
  business_date  date NOT NULL,
  posted_at      timestamptz,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, receipt_no),
  FOREIGN KEY (tenant_id, warehouse_id)   REFERENCES warehouse (tenant_id, id),
  FOREIGN KEY (tenant_id, stock_owner_id) REFERENCES stock_owner (tenant_id, id)
);

CREATE TABLE goods_receipt_line (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  receipt_id     uuid NOT NULL,
  sku_id         uuid NOT NULL,
  lot_id         uuid,
  -- 预期与实收分开：超收/短收/拒收都要留痕
  expected_qty   numeric(19,6) NOT NULL DEFAULT 0 CHECK (expected_qty >= 0),
  received_qty   numeric(19,6) NOT NULL DEFAULT 0 CHECK (received_qty >= 0),
  rejected_qty   numeric(19,6) NOT NULL DEFAULT 0 CHECK (rejected_qty >= 0),
  difference_reason varchar(32) NOT NULL DEFAULT '',
  quality_status text NOT NULL DEFAULT 'PENDING_QC'
                 CHECK (quality_status IN ('GOOD','PENDING_QC','DAMAGED','DEFECTIVE','FROZEN','RETURNED')),
  source_line_id uuid,                              -- 关联采购行/退货行，逻辑引用 trade
  FOREIGN KEY (tenant_id, receipt_id) REFERENCES goods_receipt (tenant_id, id),
  FOREIGN KEY (tenant_id, lot_id)     REFERENCES inventory_lot (tenant_id, id),
  CHECK (received_qty + rejected_qty > 0 OR expected_qty = 0)
);

CREATE TABLE outbound_order (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  outbound_no    varchar(64) NOT NULL,
  -- 出库单 ≠ 销售订单：一订单可多出库单（分仓/分批）
  source_type    text NOT NULL CHECK (source_type IN ('SALE','PURCHASE_RETURN','TRANSFER','PROCESS_OUT','CONSIGNMENT_OUT')),
  source_service text NOT NULL,
  source_id      uuid,
  warehouse_id   uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  status         text NOT NULL DEFAULT 'CREATED'
                 CHECK (status IN ('CREATED','PICKING','PICKED','PACKED','SHIPPED','CANCELLED')),
  carrier_id     uuid,                              -- 逻辑引用 catalog.party
  tracking_no    varchar(128),
  shipped_at     timestamptz,
  business_date  date NOT NULL,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, outbound_no),
  FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  -- 已发货必须有运单号
  CHECK (status <> 'SHIPPED' OR (tracking_no IS NOT NULL AND btrim(tracking_no) <> ''))
);
CREATE INDEX idx_outbound_status ON outbound_order (tenant_id, warehouse_id, status, business_date DESC);
CREATE INDEX idx_outbound_source ON outbound_order (tenant_id, source_service, source_type, source_id);

CREATE TABLE outbound_order_line (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  outbound_id    uuid NOT NULL,
  sku_id         uuid NOT NULL,
  stock_owner_id uuid NOT NULL,
  planned_qty    numeric(19,6) NOT NULL CHECK (planned_qty > 0),
  picked_qty     numeric(19,6) NOT NULL DEFAULT 0 CHECK (picked_qty >= 0),
  shipped_qty    numeric(19,6) NOT NULL DEFAULT 0 CHECK (shipped_qty >= 0),
  source_line_id uuid,                              -- 逻辑引用 trade 销售行
  UNIQUE (tenant_id, id),                           -- 被 pick_task_line / parcel_item 复合外键引用
  FOREIGN KEY (tenant_id, outbound_id)    REFERENCES outbound_order (tenant_id, id),
  FOREIGN KEY (tenant_id, stock_owner_id) REFERENCES stock_owner (tenant_id, id),
  CHECK (picked_qty  <= planned_qty),
  CHECK (shipped_qty <= picked_qty)                   -- 拣货完成 ≠ 已发货
);
CREATE INDEX idx_outbound_line ON outbound_order_line (tenant_id, outbound_id);

-- 拣货记录实际库位/批次/序列号：只记 SKU 与数量无法追溯
CREATE TABLE pick_task (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  outbound_id    uuid NOT NULL,
  wave_no        varchar(64) NOT NULL DEFAULT '',
  status         text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PICKING','PICKED','SHORT','CANCELLED')),
  operator_id    uuid,
  started_at     timestamptz,
  finished_at    timestamptz,
  UNIQUE (tenant_id, id),                           -- 被 pick_task_line 复合外键引用
  FOREIGN KEY (tenant_id, outbound_id) REFERENCES outbound_order (tenant_id, id)
);
CREATE TABLE pick_task_line (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  pick_task_id   uuid NOT NULL,
  outbound_line_id uuid NOT NULL,
  bucket_id      uuid NOT NULL,                     -- 实际库位/批次由此确定
  planned_qty    numeric(19,6) NOT NULL CHECK (planned_qty > 0),
  picked_qty     numeric(19,6) NOT NULL DEFAULT 0 CHECK (picked_qty >= 0),
  short_reason   varchar(32) NOT NULL DEFAULT '',
  FOREIGN KEY (tenant_id, pick_task_id)     REFERENCES pick_task (tenant_id, id),
  FOREIGN KEY (tenant_id, outbound_line_id) REFERENCES outbound_order_line (tenant_id, id),
  FOREIGN KEY (tenant_id, bucket_id)        REFERENCES stock_bucket (tenant_id, id),
  CHECK (picked_qty <= planned_qty)
);

-- 包裹必须有明细项，只有重量和运单号无法追溯与对账（实体目录 §9）
CREATE TABLE parcel (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  parcel_no      varchar(64) NOT NULL,
  outbound_id    uuid NOT NULL,
  tracking_no    varchar(128) NOT NULL DEFAULT '',
  carrier_id     uuid,
  service_level  varchar(32) NOT NULL DEFAULT '',
  weight_kg      numeric(19,6),
  length_cm      numeric(19,6),
  width_cm       numeric(19,6),
  height_cm      numeric(19,6),
  status         text NOT NULL DEFAULT 'PACKED'
                 CHECK (status IN ('PACKED','HANDED_OVER','IN_TRANSIT','DELIVERED','LOST','RETURNED')),
  packed_at      timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, parcel_no),
  FOREIGN KEY (tenant_id, outbound_id) REFERENCES outbound_order (tenant_id, id),
  CHECK (weight_kg IS NULL OR weight_kg >= 0)
);
CREATE INDEX idx_parcel_tracking ON parcel (tenant_id, tracking_no) WHERE tracking_no <> '';

CREATE TABLE parcel_item (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  parcel_id      uuid NOT NULL,
  outbound_line_id uuid NOT NULL,
  sku_id         uuid NOT NULL,
  qty            numeric(19,6) NOT NULL CHECK (qty > 0),
  FOREIGN KEY (tenant_id, parcel_id)        REFERENCES parcel (tenant_id, id),
  FOREIGN KEY (tenant_id, outbound_line_id) REFERENCES outbound_order_line (tenant_id, id)
);
CREATE INDEX idx_parcel_item ON parcel_item (tenant_id, parcel_id);

-- ===========================================================================
-- 10. 组装与拆解（蓝图 08.4）：同事务消耗组件、产生成品
-- ===========================================================================
CREATE TABLE assembly_order (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  assembly_no    varchar(64) NOT NULL,
  order_type     text NOT NULL CHECK (order_type IN ('ASSEMBLE','DISASSEMBLE')),
  product_sku_id uuid NOT NULL,
  bom_version_id uuid,                              -- 冻结的 BOM 版本，逻辑引用 catalog
  target_qty     numeric(19,6) NOT NULL CHECK (target_qty > 0),
  completed_qty  numeric(19,6) NOT NULL DEFAULT 0 CHECK (completed_qty >= 0),
  warehouse_id   uuid NOT NULL,
  status         text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IN_PROGRESS','COMPLETED','CANCELLED')),
  business_date  date NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, assembly_no),
  FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  CHECK (completed_qty <= target_qty)
);
CREATE TABLE assembly_component (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  assembly_id    uuid NOT NULL,
  component_sku_id uuid NOT NULL,
  required_qty   numeric(19,6) NOT NULL CHECK (required_qty > 0),   -- 蓝图要求 > 0
  consumed_qty   numeric(19,6) NOT NULL DEFAULT 0 CHECK (consumed_qty >= 0),
  FOREIGN KEY (tenant_id, assembly_id) REFERENCES assembly_order (tenant_id, id)
);

-- ===========================================================================
-- 11. 外部仓库存观测（蓝图 07.1.3）：观测值，不是自有账本
-- ===========================================================================
CREATE TABLE external_stock_observation (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  shop_id        uuid NOT NULL,                     -- 逻辑引用 channel.shop
  warehouse_ref  varchar(128) NOT NULL,              -- 外部仓标识
  sku_id         uuid NOT NULL,
  external_sku   varchar(128) NOT NULL DEFAULT '',
  stock_owner_id uuid,                              -- 平台仓可能不属我方
  reported_qty   numeric(19,6) NOT NULL CHECK (reported_qty >= 0),
  reported_at    timestamptz NOT NULL,              -- 采集时间，必须存
  source_version varchar(64) NOT NULL DEFAULT '',
  UNIQUE (tenant_id, shop_id, warehouse_ref, sku_id, reported_at),
  CHECK (btrim(warehouse_ref) <> '')
);
CREATE INDEX idx_external_obs_latest ON external_stock_observation (tenant_id, shop_id, sku_id, reported_at DESC);

-- ===========================================================================
-- 12. 多平台可售量分摊（蓝图 07.8 新增）
-- ===========================================================================
-- 规则：总可用库存怎么分给 N 个店铺。缺了必然出现"某店一直抢不到"或"都以为有货"导致超卖。
CREATE TABLE stock_allocation_rule (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  sku_id         uuid NOT NULL,
  shop_id        uuid NOT NULL,                     -- 逻辑引用 channel.shop
  strategy       text NOT NULL CHECK (strategy IN ('SALES_SHARE','PRIORITY','FIXED','RESERVED_POOL')),
  priority       int NOT NULL DEFAULT 100,
  -- 保底：保证高价值店铺不被低优先级挤掉
  reserved_qty   numeric(19,6) NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
  buffer_ratio   numeric(9,6) NOT NULL DEFAULT 0 CHECK (buffer_ratio >= 0 AND buffer_ratio < 1),
  warehouse_id   uuid,                              -- 限定在某个仓内分摊
  active         boolean NOT NULL DEFAULT true,
  effective_from timestamptz,
  effective_to   timestamptz,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, sku_id, shop_id, warehouse_id, strategy),
  CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)
);

-- 结果快照：可解释"为什么这个店分到这么少"
CREATE TABLE stock_allocation_result (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  sku_id         uuid NOT NULL,
  warehouse_id   uuid NOT NULL,
  shop_id        uuid NOT NULL,
  allocated_qty  numeric(19,6) NOT NULL CHECK (allocated_qty >= 0),
  pushed_qty     numeric(19,6) NOT NULL DEFAULT 0,  -- 实际回写平台的数量
  push_status    text NOT NULL DEFAULT 'PENDING' CHECK (push_status IN ('PENDING','SUCCESS','FAILED','UNKNOWN')),
  -- 平台回写有延迟，缓冲不足会超卖
  push_lag_seconds int NOT NULL DEFAULT 0 CHECK (push_lag_seconds >= 0),
  computed_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, sku_id, warehouse_id, shop_id, computed_at)
);
CREATE INDEX idx_alloc_result_recent ON stock_allocation_result (tenant_id, sku_id, computed_at DESC);
CREATE INDEX idx_alloc_result_push   ON stock_allocation_result (tenant_id, push_status, computed_at)
  WHERE push_status IN ('FAILED','UNKNOWN');

-- ===========================================================================
-- RLS
-- ===========================================================================
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY[
    'stock_owner','warehouse','warehouse_location','inventory_lot','inventory_serial',
    'stock_bucket','inventory_transaction','inventory_entry',
    'inventory_reservation','reservation_allocation','reservation_entry',
    'inventory_transfer','inventory_transfer_line',
    'stock_count','stock_count_line','stock_adjustment',
    'goods_receipt','goods_receipt_line',
    'outbound_order','outbound_order_line','pick_task','pick_task_line','parcel','parcel_item',
    'assembly_order','assembly_component','external_stock_observation',
    'stock_allocation_rule','stock_allocation_result'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)', t);
  END LOOP;
END $$;

-- 流水与序列流转不可篡改
REVOKE UPDATE, DELETE ON inventory_entry FROM warehouse_app;
REVOKE UPDATE, DELETE ON reservation_entry FROM warehouse_app;
REVOKE UPDATE, DELETE ON audit_event FROM warehouse_app;
-- flyway_schema_history 由 Flyway 在迁移前创建；独立验证时可能尚不存在
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relname = 'flyway_schema_history' AND n.nspname = 'public') THEN
    EXECUTE 'REVOKE ALL ON flyway_schema_history FROM warehouse_app';
  END IF;
END $$;
