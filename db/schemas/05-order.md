> **历史设计稿，已由 [V2 微服务数据模型](v2/README.md) 取代。** 下文跨域外键、事务与服务划分不可直接用于新迁移。

# 05 · 订单与履约

owner 域：`order`（订单）/ `fulfillment`（出库）/ `shipment`（包裹与运单）。首批接 Shopify（见 [ADR-0003](../../docs/decisions/0003-backend-runtime-kotlin-spring-boot.md)）。

## 两条硬规则

**1. 快照优先。** 订单行一旦落库，`商品名称 / 规格 / 单价 / 税费 / 汇率` 全部以订单内的副本为准。主数据之后怎么改，历史订单不动。这条是 [主数据调研](../../docs/investment/2026-09-27-backend-and-master-data.md) 的明确要求，也是 ERP 和电商后台最本质的区别。

**2. 同步幂等靠数据库约束，不靠代码判断。** `(tenant, shop_id, external_order_no)` 唯一 —— 平台重推同一订单时插入会失败，捕获约束冲突即视为「已处理」。这比「先查再插」可靠，因为后者在并发下有两个请求同时查到「不存在」。

## 表

### 订单

```sql
CREATE TABLE sales_order (
  id                  UUID        PRIMARY KEY,
  tenant_id           UUID        NOT NULL REFERENCES tenant (id),
  shop_id             UUID        NOT NULL,
  order_no            TEXT        NOT NULL,   -- 内部单号
  external_order_no   TEXT        NOT NULL,   -- 平台单号
  customer_id         UUID,
  status              TEXT        NOT NULL DEFAULT 'CREATED',
  currency            CHAR(3)     NOT NULL,

  -- 金额快照（金额与币种成对）
  subtotal_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
  discount_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
  shipping_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
  tax_amount          NUMERIC(19,4) NOT NULL DEFAULT 0,
  grand_total_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,

  -- 买家信息快照：不引用 app_user，平台买家不是我们的用户
  buyer_name          TEXT        NOT NULL,
  buyer_email         TEXT,
  buyer_phone         TEXT,

  -- 收货地址快照：不引用 address，地址主数据变更不得影响历史订单
  ship_to_country     CHAR(2)     NOT NULL,
  ship_to_state       TEXT,
  ship_to_city        TEXT        NOT NULL,
  ship_to_line1       TEXT        NOT NULL,
  ship_to_line2       TEXT,
  ship_to_postal_code TEXT,

  placed_at           timestamptz,     -- 平台下单时间
  cancelled_at        timestamptz,
  cancel_reason       TEXT,
  raw_payload         JSONB,            -- 平台原始报文，仅用于排障
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT fk_order_shop     FOREIGN KEY (tenant_id, shop_id) REFERENCES shop (tenant_id, id),
  CONSTRAINT fk_order_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customer (tenant_id, id),
  CONSTRAINT uq_order_tenant_no        UNIQUE (tenant_id, order_no),
  CONSTRAINT uq_order_shop_external    UNIQUE (tenant_id, shop_id, external_order_no),
  CONSTRAINT uq_order_tenant_id        UNIQUE (tenant_id, id),
  CONSTRAINT ck_order_status CHECK (status IN (
      'CREATED', 'CONFIRMED', 'ALLOCATED', 'SHIPPED', 'DELIVERED',
      'CANCELLED', 'RETURNED', 'CLOSED'
  )),
  CONSTRAINT ck_order_amounts CHECK (
    grand_total_amount = subtotal_amount - discount_amount + shipping_amount + tax_amount
  ),
  CONSTRAINT ck_order_cancel CHECK (
    (status = 'CANCELLED' AND cancelled_at IS NOT NULL) OR status <> 'CANCELLED'
  )
);
```

几个刻意的约束：

- `ck_order_amounts` 让**总额必须等于分项算式**。平台偶尔会送来总额与分项不一致的报文，这条约束会直接把它挡在库外，而不是留一条对不上的账等人去查。
- `raw_payload` 是**唯一允许的 JSONB**（平台原始报文，仅排障用），不参与任何查询与计算。
- 收货地址用**内联快照列**而不是 `address_id` 外键：主数据地址改了，历史订单必须纹丝不动。
- `ck_order_cancel` 保证取消态一定带取消时间与原因 —— 取消原因事后查不到是运营最常见的痛点。

### 订单行

```sql
CREATE TABLE sales_order_line (
  id              UUID        PRIMARY KEY,
  tenant_id       UUID        NOT NULL REFERENCES tenant (id),
  order_id        UUID        NOT NULL,
  line_no         INT         NOT NULL,

  -- 内部 SKU：解析成功才有；未映射订单为 NULL，由 order 域挂起
  sku_id          UUID,
  -- 平台侧原始标识：无论是否映射成功都必须留
  external_sku    TEXT        NOT NULL,

  -- ↓↓↓ 商品快照：主数据变更不影响这里
  product_name    TEXT        NOT NULL,
  spec_text       TEXT,
  unit_name       TEXT,

  quantity        NUMERIC(19,4) NOT NULL,
  unit_price_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  discount_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  tax_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
  line_total_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  -- 成本快照：下单时点的标准成本，用于毛利预估
  cost_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,

  fulfilled_qty   NUMERIC(19,4) NOT NULL DEFAULT 0,
  returned_qty    NUMERIC(19,4) NOT NULL DEFAULT 0,

  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT fk_order_line_order FOREIGN KEY (tenant_id, order_id) REFERENCES sales_order (tenant_id, id),
  CONSTRAINT fk_order_line_sku   FOREIGN KEY (tenant_id, sku_id)   REFERENCES sku (tenant_id, id),
  CONSTRAINT uq_order_line_no     UNIQUE (tenant_id, order_id, line_no),
  CONSTRAINT uq_order_line_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT ck_order_line_qty      CHECK (quantity > 0),
  CONSTRAINT ck_order_line_fulfilled CHECK (fulfilled_qty >= 0 AND fulfilled_qty <= quantity),
  CONSTRAINT ck_order_line_returned  CHECK (returned_qty  >= 0 AND returned_qty  <= fulfilled_qty)
);
```

- `sku_id` **可空**：平台推来未映射的 external_sku 时，行照样落库并标记订单挂起，而不是丢单或猜 SKU。补映射后回填 `sku_id` 即可
- `ck_order_line_fulfilled` / `ck_order_line_returned` 是**数据库层的发货/退货上限**，防止 service 累加时写错符号或多减
- `cost_amount` 快照保证「这单当时赚多少」可以复算，不受今天改成本价影响

### 状态机规则与审计

```sql
-- 合法迁移：数据驱动，改状态机不用改代码
CREATE TABLE order_status_transition (
  id             UUID        PRIMARY KEY,
  from_status    TEXT        NOT NULL,
  to_status      TEXT        NOT NULL,
  required_permission TEXT,
  requires_stock BOOLEAN     NOT NULL DEFAULT FALSE,
  CONSTRAINT uq_order_transition UNIQUE (from_status, to_status)
);

CREATE TABLE order_status_history (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  order_id     UUID        NOT NULL,
  from_status  TEXT,
  to_status    TEXT        NOT NULL,
  reason       TEXT,
  changed_by   UUID        REFERENCES app_user (id),   -- 平台自动流转时为 NULL
  changed_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_order_hist_order FOREIGN KEY (tenant_id, order_id) REFERENCES sales_order (tenant_id, id),
  CONSTRAINT uq_order_history UNIQUE (tenant_id, id)
);

CREATE INDEX idx_order_history_order ON order_status_history (tenant_id, order_id, changed_at);
```

状态机做**数据驱动**（`order_status_transition` 表）而不是散落在代码里的 `when` 分支：运营要加一条「发货后 7 天自动确认收货」这类规则时，改一行数据就行，不用改代码重新发版。

`changed_by` 允许 NULL，因为订单会被平台 webhook 推动，那时候没有用户。

`order_status_history` 是 append-only 的状态变更审计 —— 客诉纠纷时「这单什么时候变成已发货的」必须查得到。

## 履约

### 出库单

```sql
CREATE TABLE shipment (
  id            UUID        PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenant (id),
  warehouse_id  UUID        NOT NULL,
  order_id      UUID        NOT NULL,
  shipment_no   TEXT        NOT NULL,
  status        TEXT        NOT NULL DEFAULT 'CREATED',
  carrier_id    UUID,
  tracking_no   TEXT,
  shipped_at    timestamptz,
  delivered_at  timestamptz,
  -- 无真实物流接口时置 TRUE，明确标记「人工出库」
  is_manual     BOOLEAN     NOT NULL DEFAULT TRUE,
  manual_note   TEXT,
  created_by    UUID        NOT NULL REFERENCES app_user (id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_shipment_wh    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  CONSTRAINT fk_shipment_order FOREIGN KEY (tenant_id, order_id)     REFERENCES sales_order (tenant_id, id),
  CONSTRAINT fk_shipment_carrier FOREIGN KEY (tenant_id, carrier_id) REFERENCES carrier (tenant_id, id),
  CONSTRAINT uq_shipment_tenant_no  UNIQUE (tenant_id, shipment_no),
  CONSTRAINT uq_shipment_order      UNIQUE (tenant_id, order_id),   -- 一单一出库单
  CONSTRAINT uq_shipment_tenant_id  UNIQUE (tenant_id, id),
  CONSTRAINT ck_shipment_status CHECK (status IN ('CREATED', 'PICKED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
  CONSTRAINT ck_shipment_shipped_at CHECK (
    (status IN ('SHIPPED', 'DELIVERED') AND shipped_at IS NOT NULL) OR status NOT IN ('SHIPPED', 'DELIVERED')
  ),
  CONSTRAINT ck_shipment_manual CHECK (
    is_manual = FALSE OR manual_note IS NOT NULL    -- 人工出库必须写原因
  )
);
```

`is_manual` + `ck_shipment_manual` 是**诚实性约束**：没有真实物流接口时，出库必须显式标记为人工并写明原因。**不允许把人工出库伪装成平台回传成功** —— 那会让对账时发现「平台说没发货，我们说发了」。

`uq_shipment_order` 限定一单一出库单。将来支持拆单发货时改为 `UNIQUE (tenant_id, order_id, sequence)`。

### 出库行 / 包裹 / 轨迹

```sql
CREATE TABLE shipment_line (
  id            UUID        PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenant (id),
  shipment_id   UUID        NOT NULL,
  order_line_id UUID        NOT NULL,
  sku_id        UUID        NOT NULL,
  quantity      NUMERIC(19,4) NOT NULL,
  CONSTRAINT fk_ship_line_shipment FOREIGN KEY (tenant_id, shipment_id) REFERENCES shipment (tenant_id, id),
  CONSTRAINT fk_ship_line_order_line FOREIGN KEY (tenant_id, order_line_id) REFERENCES sales_order_line (tenant_id, id),
  CONSTRAINT fk_ship_line_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES sku (tenant_id, id),
  CONSTRAINT uq_shipment_line UNIQUE (tenant_id, shipment_id, order_line_id),
  CONSTRAINT ck_shipment_line_qty CHECK (quantity > 0)
);

CREATE TABLE parcel (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  shipment_id  UUID        NOT NULL,
  package_index INT        NOT NULL,
  weight_kg    NUMERIC(19,6) NOT NULL DEFAULT 0,
  length_cm    NUMERIC(19,4) NOT NULL DEFAULT 0,
  width_cm     NUMERIC(19,4) NOT NULL DEFAULT 0,
  height_cm    NUMERIC(19,4) NOT NULL DEFAULT 0,
  tracking_no  TEXT,
  CONSTRAINT fk_parcel_shipment FOREIGN KEY (tenant_id, shipment_id) REFERENCES shipment (tenant_id, id),
  CONSTRAINT uq_parcel_shipment_index UNIQUE (tenant_id, shipment_id, package_index)
);

CREATE TABLE tracking_event (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  parcel_id    UUID        NOT NULL,
  carrier_id   UUID,
  event_code   TEXT        NOT NULL,
  event_desc   TEXT,
  location     TEXT,
  occurred_at  timestamptz NOT NULL,
  raw_payload  JSONB,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_tracking_parcel FOREIGN KEY (tenant_id, parcel_id) REFERENCES parcel (tenant_id, id),
  CONSTRAINT uq_tracking_event UNIQUE (tenant_id, parcel_id, carrier_id, event_code, occurred_at)
);
```

`uq_tracking_event` 让运单轨迹**天然幂等** —— 物流商重复推送同一事件不会产生重复行。

## 跨域暴露

| 调用方                   | 调什么                                           | 收到什么                                |
| ------------------------ | ------------------------------------------------ | --------------------------------------- |
| platform（Shopify 同步） | `OrderIngestService.ingest(shopId, payload)`     | 幂等 ingest，内部完成映射、落库、outbox |
| inventory                | `OrderService.linesForAllocation(orderId)`       | 待分配的行                              |
| fulfillment              | `ShipmentService.create / ship`                  | 出库，内部触发库存消耗                  |
| finance                  | `OrderService.settleableOrders(shopId, period)`  | 待结算订单（含原币金额）                |
| crm                      | `OrderService.customerOrders(customerId, limit)` | 客户历史                                |

order 域是「订单落库 + 库存预占 + outbox」这三个动作的**事务协调者**。它自己不写 `inventory_*` 表，而是调 inventory 的公开 service，三个动作共享同一个 DB 事务连接。

## 关键不变量

1. 订单总额恒等于分项算式（`ck_order_amounts`）
2. 订单行的 `sku_id` 可空，但 `external_sku` 必填 —— **丢单比猜错更安全**
3. `fulfilled_qty <= quantity`，`returned_qty <= fulfilled_qty`（DB 强制）
4. 同一 `(tenant, shop, external_order_no)` 只有一条订单 —— 平台重推不产生第二条
5. **单据内的商品名称/单价/成本是快照**，主数据变更不影响历史单据
6. 状态迁移必须存在于 `order_status_transition`，每次迁移写一条 `order_status_history`
7. 人工出库必须 `is_manual = TRUE` 且有 `manual_note`
