> **历史设计稿，已由 [V2 微服务数据模型](v2/README.md) 取代。** 下文跨域外键、事务与服务划分不可直接用于新迁移。

# 06 · 采购、收货与售后

owner 域：`purchase`（采购与收货）/ `aftersales`（退货退款）。这些表是**库存流入**与**库存回流**的来源。

## 采购

### 采购单

```sql
CREATE TABLE purchase_order (
  id                UUID        PRIMARY KEY,
  tenant_id         UUID        NOT NULL REFERENCES tenant (id),
  supplier_id       UUID        NOT NULL,
  order_no          TEXT        NOT NULL,
  external_order_no TEXT,                       -- 1688 等平台的采购单号
  status            TEXT        NOT NULL DEFAULT 'DRAFT',
  currency          CHAR(3)     NOT NULL,
  subtotal_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
  shipping_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
  tax_amount        NUMERIC(19,4) NOT NULL DEFAULT 0,
  grand_total_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  placed_at         timestamptz,
  expected_at       timestamptz,
  closed_at         timestamptz,
  created_by        UUID        NOT NULL REFERENCES app_user (id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_po_supplier FOREIGN KEY (tenant_id, supplier_id) REFERENCES supplier (tenant_id, id),
  CONSTRAINT uq_po_tenant_no      UNIQUE (tenant_id, order_no),
  CONSTRAINT uq_po_tenant_external UNIQUE (tenant_id, supplier_id, external_order_no),
  CONSTRAINT uq_po_tenant_id      UNIQUE (tenant_id, id),
  CONSTRAINT ck_po_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'PARTIAL_RECEIVED', 'RECEIVED', 'CANCELLED', 'CLOSED')),
  CONSTRAINT ck_po_amounts CHECK (
    grand_total_amount = subtotal_amount + shipping_amount + tax_amount
  )
);
```

`external_order_no` 允许 NULL，但唯一约束在 PG 里 NULL 不参与唯一性 —— 多个 NULL 不会冲突，所以没填外部单号的采购单可以有多条。这是期望行为，不需要 `COALESCE` 之类的技巧。

### 采购行

```sql
CREATE TABLE purchase_order_line (
  id               UUID        PRIMARY KEY,
  tenant_id        UUID        NOT NULL REFERENCES tenant (id),
  purchase_order_id UUID       NOT NULL,
  line_no          INT         NOT NULL,
  sku_id           UUID        NOT NULL,
  supplier_sku_id  UUID,                          -- → supplier_sku，可空
  quantity         NUMERIC(19,4) NOT NULL,
  received_qty     NUMERIC(19,4) NOT NULL DEFAULT 0,
  rejected_qty     NUMERIC(19,4) NOT NULL DEFAULT 0,
  unit_price_amount NUMERIC(19,4) NOT NULL,
  discount_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,
  tax_amount       NUMERIC(19,4) NOT NULL DEFAULT 0,
  line_total_amount NUMERIC(19,4) NOT NULL,
  CONSTRAINT fk_po_line_order FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_order (tenant_id, id),
  CONSTRAINT fk_po_line_sku   FOREIGN KEY (tenant_id, sku_id)           REFERENCES sku (tenant_id, id),
  CONSTRAINT fk_po_line_sup_sku FOREIGN KEY (tenant_id, supplier_sku_id) REFERENCES supplier_sku (tenant_id, id),
  CONSTRAINT uq_po_line_no      UNIQUE (tenant_id, purchase_order_id, line_no),
  CONSTRAINT uq_po_line_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT ck_po_line_qty      CHECK (quantity > 0),
  CONSTRAINT ck_po_line_received CHECK (received_qty >= 0 AND received_qty <= quantity),
  CONSTRAINT ck_po_line_rejected CHECK (rejected_qty >= 0 AND rejected_qty + received_qty <= quantity)
);
```

`rejected_qty` 单独存在：到货有瑕疵是常态，**质检不合格的部分要能记数量**，否则只能全收或全退。`rejected_qty + received_qty <= quantity` 防止两者相加超过订购量。

### 收货

```sql
CREATE TABLE goods_receipt (
  id                UUID        PRIMARY KEY,
  tenant_id         UUID        NOT NULL REFERENCES tenant (id),
  warehouse_id      UUID        NOT NULL,
  purchase_order_id UUID        NOT NULL,
  receipt_no        TEXT        NOT NULL,
  received_at       timestamptz NOT NULL,
  created_by        UUID        NOT NULL REFERENCES app_user (id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_receipt_wh   FOREIGN KEY (tenant_id, warehouse_id)      REFERENCES warehouse (tenant_id, id),
  CONSTRAINT fk_receipt_po   FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_order (tenant_id, id),
  CONSTRAINT uq_receipt_tenant_no UNIQUE (tenant_id, receipt_no),
  CONSTRAINT uq_receipt_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE goods_receipt_line (
  id                  UUID        PRIMARY KEY,
  tenant_id           UUID        NOT NULL REFERENCES tenant (id),
  goods_receipt_id    UUID        NOT NULL,
  purchase_order_line_id UUID     NOT NULL,
  sku_id              UUID        NOT NULL,
  location_id         UUID,        -- 建议进 QUARANTINE 或质检区
  quantity            NUMERIC(19,4) NOT NULL,
  unit_cost_amount    NUMERIC(19,4) NOT NULL,
  CONSTRAINT fk_gr_line_receipt FOREIGN KEY (tenant_id, goods_receipt_id) REFERENCES goods_receipt (tenant_id, id),
  CONSTRAINT fk_gr_line_po_line FOREIGN KEY (tenant_id, purchase_order_line_id) REFERENCES purchase_order_line (tenant_id, id),
  CONSTRAINT fk_gr_line_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES sku (tenant_id, id),
  CONSTRAINT fk_gr_line_loc FOREIGN KEY (tenant_id, location_id) REFERENCES location (tenant_id, id),
  CONSTRAINT uq_gr_line UNIQUE (tenant_id, goods_receipt_id, purchase_order_line_id),
  CONSTRAINT ck_gr_line_qty CHECK (quantity > 0)
);
```

`goods_receipt_line.unit_cost_amount` 是**真实单位成本**的来源。收货完成后由 purchase 域触发：写 `inventory_ledger(direction='IN')`（带成本）、更新 `inventory_balance.on_hand`、并刷新 `sku.standard_cost`。这三步在同一事务。

`location_id` 可空 —— 小仓库不启用库位管理时直接记到仓库级。

## 售后

### 退货单

```sql
CREATE TABLE return_order (
  id                UUID        PRIMARY KEY,
  tenant_id         UUID        NOT NULL REFERENCES tenant (id),
  order_id          UUID        NOT NULL,
  shop_id           UUID        NOT NULL,
  external_return_no TEXT,
  return_type       TEXT        NOT NULL,     -- 'REFUND_ONLY' | 'RETURN_REFUND' | 'REPLACEMENT'
  status            TEXT        NOT NULL DEFAULT 'CREATED',
  currency          CHAR(3)     NOT NULL,
  total_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
  shipping_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
  refund_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
  restock           BOOLEAN     NOT NULL DEFAULT FALSE,   -- 是否回补库存
  requested_at      timestamptz NOT NULL,
  received_at       timestamptz,            -- 货回到仓库的时间
  completed_at      timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_return_order FOREIGN KEY (tenant_id, order_id) REFERENCES sales_order (tenant_id, id),
  CONSTRAINT fk_return_shop  FOREIGN KEY (tenant_id, shop_id)  REFERENCES shop (tenant_id, id),
  CONSTRAINT uq_return_shop_external UNIQUE (tenant_id, shop_id, external_return_no),
  CONSTRAINT uq_return_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT ck_return_type   CHECK (return_type IN ('REFUND_ONLY', 'RETURN_REFUND', 'REPLACEMENT')),
  CONSTRAINT ck_return_status CHECK (status IN ('CREATED', 'APPROVED', 'IN_TRANSIT', 'RECEIVED', 'REFUNDED', 'REJECTED', 'CANCELLED'))
);

CREATE TABLE return_line (
  id             UUID        PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES tenant (id),
  return_order_id UUID       NOT NULL,
  order_line_id  UUID        NOT NULL,
  sku_id         UUID        NOT NULL,
  quantity       NUMERIC(19,4) NOT NULL,
  amount         NUMERIC(19,4) NOT NULL,
  restocked_qty  NUMERIC(19,4) NOT NULL DEFAULT 0,
  condition      TEXT        NOT NULL DEFAULT 'UNCHECKED',  -- 'GOOD' | 'DAMAGED' | 'UNSELLABLE'
  CONSTRAINT fk_return_line_order FOREIGN KEY (tenant_id, return_order_id) REFERENCES return_order (tenant_id, id),
  CONSTRAINT fk_return_line_order_line FOREIGN KEY (tenant_id, order_line_id) REFERENCES sales_order_line (tenant_id, id),
  CONSTRAINT fk_return_line_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES sku (tenant_id, id),
  CONSTRAINT uq_return_line UNIQUE (tenant_id, return_order_id, order_line_id),
  CONSTRAINT ck_return_line_qty CHECK (quantity > 0)
);
```

`condition` 决定回补库存时的去向：`GOOD` → 正常库位；`DAMAGED` / `UNSELLABLE` → `QUARANTINE`，**不计入可预占可用量**。少了这个判断，损坏退货会重新流向买家。

`restocked_qty` 允许部分回补（一批退 3 件只补 2 件），但由 service 保证 `<= quantity`（跨表约束，放应用层）。

### 退货物流

```sql
CREATE TABLE return_shipment (
  id             UUID        PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES tenant (id),
  return_order_id UUID       NOT NULL,
  carrier_id     UUID,
  tracking_no    TEXT,
  status         TEXT        NOT NULL DEFAULT 'CREATED',
  shipped_at     timestamptz,
  delivered_at   timestamptz,
  is_manual      BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_return_ship_order FOREIGN KEY (tenant_id, return_order_id) REFERENCES return_order (tenant_id, id),
  CONSTRAINT fk_return_ship_carrier FOREIGN KEY (tenant_id, carrier_id) REFERENCES carrier (tenant_id, id),
  CONSTRAINT uq_return_shipment_order UNIQUE (tenant_id, return_order_id),
  CONSTRAINT ck_return_ship_status CHECK (status IN ('CREATED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED'))
);
```

与销售出库同构，`is_manual` 的诚实性约束同样适用。

## 跨域暴露

| 调用方    | 调什么                                                                           |
| --------- | -------------------------------------------------------------------------------- |
| inventory | `PurchaseService.confirmReceipt(receiptId)` → 触发入库流水 + 余额 + 刷新标准成本 |
| inventory | `AftersalesService.restock(returnLineId, locationId, qty)` → 触发入库流水        |
| finance   | `PurchaseService.unbilledReceivedLines(period)` → 应付                           |
| finance   | `AftersalesService.refundedAmount(period)` → 退款                                |

## 关键不变量

1. `received_qty + rejected_qty <= quantity`
2. `received_qty` 的累加与库存入库**在同一事务**
3. 退货的 `restocked_qty <= quantity`（应用层保证，跨表约束）
4. `condition != 'GOOD'` 的退货**只能进 `QUARANTINE`**，且不计入可用量
5. `is_manual = TRUE` 的出库必须写明原因，**不伪造平台回传成功**
6. 采购金额与订单一样走 `ck_*_amounts` 算式校验
