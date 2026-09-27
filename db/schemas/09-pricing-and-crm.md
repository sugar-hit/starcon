# 09 · 定价与 CRM

owner 域：`pricing` / `crm`。这两个域不改动物流与账目，所以约束比核心域松，但仍有自己的不变量。

## 定价

```sql
CREATE TABLE price_list (
  id          UUID        PRIMARY KEY,
  tenant_id   UUID        NOT NULL REFERENCES tenant (id),
  shop_id     UUID,                       -- NULL = 全店铺通用价
  code        TEXT        NOT NULL,
  name        TEXT        NOT NULL,
  currency    CHAR(3)     NOT NULL,
  priority    INT         NOT NULL DEFAULT 0,   -- 多价目命中时的优先级
  status      TEXT        NOT NULL DEFAULT 'ACTIVE',
  valid_from  date,
  valid_to    date,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_price_list_shop FOREIGN KEY (tenant_id, shop_id) REFERENCES shop (tenant_id, id),
  CONSTRAINT uq_price_list_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_price_list_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_price_list_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_price_list_valid  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE TABLE price_list_item (
  id             UUID        PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES tenant (id),
  price_list_id  UUID        NOT NULL,
  sku_id         UUID        NOT NULL,
  min_quantity   NUMERIC(19,4) NOT NULL DEFAULT 1,
  unit_price_amount NUMERIC(19,4) NOT NULL,
  valid_from     date,
  valid_to       date,
  CONSTRAINT fk_price_item_list FOREIGN KEY (tenant_id, price_list_id) REFERENCES price_list (tenant_id, id),
  CONSTRAINT fk_price_item_sku  FOREIGN KEY (tenant_id, sku_id)        REFERENCES sku (tenant_id, id),
  CONSTRAINT uq_price_item UNIQUE (tenant_id, price_list_id, sku_id, min_quantity),
  CONSTRAINT ck_price_item_min_qty CHECK (min_quantity > 0),
  CONSTRAINT ck_price_item_amount CHECK (unit_price_amount >= 0)
);
```

设计取舍：

- **阶梯价用 `min_quantity` 表达，不用 JSONB 列** —— 阶梯价要参与计算和比价，放 JSONB 就得在应用层解析再比较，容易算错
- `(price_list, sku, min_quantity)` 唯一，防止同一定价项重复录入导致取价歧义
- `price_list.shop_id` 可空表示通用价。取价优先级：**店铺专属 > 通用**，同级按 `priority` 再按 `min_quantity` 降序（量大价优）
- `price_list` 只影响**新订单**。已存在的订单以 `sales_order_line.unit_price_amount` 快照为准，改定价不回溯

## CRM

```sql
CREATE TABLE customer_contact (
  id          UUID        PRIMARY KEY,
  tenant_id   UUID        NOT NULL REFERENCES tenant (id),
  customer_id UUID        NOT NULL,
  contact_type TEXT       NOT NULL,     -- 'EMAIL' | 'PHONE' | 'CHAT' | 'NOTE'
  contact_value TEXT      NOT NULL,
  note        TEXT,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  created_by  UUID        NOT NULL REFERENCES app_user (id),
  CONSTRAINT fk_contact_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customer (tenant_id, id),
  CONSTRAINT uq_contact_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT ck_contact_type CHECK (contact_type IN ('EMAIL', 'PHONE', 'CHAT', 'NOTE'))
);

CREATE TABLE customer_tag (
  id         UUID        PRIMARY KEY,
  tenant_id  UUID        NOT NULL REFERENCES tenant (id),
  code       TEXT        NOT NULL,
  name       TEXT        NOT NULL,
  color      TEXT,
  CONSTRAINT uq_customer_tag_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_customer_tag_tenant_id   UNIQUE (tenant_id, id)
);

CREATE TABLE customer_tag_link (
  tenant_id   UUID NOT NULL REFERENCES tenant (id),
  customer_id UUID NOT NULL,
  tag_id      UUID NOT NULL,
  tagged_at   timestamptz NOT NULL DEFAULT now(),
  tagged_by   UUID NOT NULL REFERENCES app_user (id),
  CONSTRAINT fk_tag_link_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customer (tenant_id, id),
  CONSTRAINT fk_tag_link_tag      FOREIGN KEY (tenant_id, tag_id)      REFERENCES customer_tag (tenant_id, id),
  CONSTRAINT uq_customer_tag_link UNIQUE (tenant_id, customer_id, tag_id)
);
```

`customer_tag` 做成**租户内的自定义标签**而不是枚举：运营要给客户打「高复购」「待跟进」这类标签，枚举意味着每次都要改表。`color` 存设计令牌的名字（不是色值），由前端映射，避免前端和后端各维护一套颜色。

`customer_contact` 是 append-only 的接触记录。`contact_type = 'NOTE'` 时 `contact_value` 存正文。

## 关键不变量

1. 同一定价项内 `(price_list, sku, min_quantity)` 唯一 → 取价无歧义
2. 改定价**不影响已存在的订单**（订单行价格是快照）
3. 同一客户同一标签只关联一次
4. `customer_tag.color` 存令牌名不存色值
5. `customer_contact` append-only
