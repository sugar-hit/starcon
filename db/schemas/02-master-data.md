# 02 · 主数据：商品、客户、供应商、承运商

owner 域：`catalog`（商品）/ `partner`（往来单位）。这是所有单据引用的**唯一真相源**。

## 商品

### 分类 / 品牌 / 单位

```sql
CREATE TABLE category (
  id         UUID        PRIMARY KEY,
  tenant_id  UUID        NOT NULL REFERENCES tenant (id),
  parent_id  UUID,
  code       TEXT        NOT NULL,
  name       TEXT        NOT NULL,
  sort_order INT         NOT NULL DEFAULT 0,
  status     TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fk_category_parent FOREIGN KEY (tenant_id, parent_id) REFERENCES category (tenant_id, id),
  CONSTRAINT uq_category_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_category_tenant_id  UNIQUE (tenant_id, id),
  CONSTRAINT ck_category_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE brand (
  id         UUID        PRIMARY KEY,
  tenant_id  UUID        NOT NULL REFERENCES tenant (id),
  code       TEXT        NOT NULL,
  name       TEXT        NOT NULL,
  status     TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_brand_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_brand_tenant_id   UNIQUE (tenant_id, id)
);

CREATE TABLE unit_of_measure (
  id         UUID        PRIMARY KEY,
  tenant_id  UUID        NOT NULL REFERENCES tenant (id),
  code       TEXT        NOT NULL,          -- 'PCS' / 'KG' / 'CTN'
  name       TEXT        NOT NULL,
  dimension  TEXT        NOT NULL,          -- 'COUNT' | 'WEIGHT' | 'VOLUME' | 'LENGTH'
  status     TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_uom_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_uom_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_uom_dimension  CHECK (dimension IN ('COUNT', 'WEIGHT', 'VOLUME', 'LENGTH'))
);
```

`category` 用邻接表（`parent_id`）而非物化路径。层级深且不常变，邻接表 + 递归 CTE 足够；物化路径每次移动节点要重写整棵子树，得不偿失。

### 属性定义

```sql
CREATE TABLE product_attribute (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  code         TEXT        NOT NULL,
  name         TEXT        NOT NULL,
  value_type   TEXT        NOT NULL,     -- 'ENUM' | 'TEXT' | 'NUMBER' | 'BOOLEAN'
  is_sku_level BOOLEAN     NOT NULL DEFAULT TRUE,
  sort_order   INT         NOT NULL DEFAULT 0,
  CONSTRAINT uq_attribute_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_attribute_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_attribute_value_type  CHECK (value_type IN ('ENUM', 'TEXT', 'NUMBER', 'BOOLEAN'))
);

CREATE TABLE product_attribute_value (
  id          UUID        PRIMARY KEY,
  tenant_id   UUID        NOT NULL REFERENCES tenant (id),
  attribute_id UUID       NOT NULL,
  value       TEXT        NOT NULL,
  sort_order  INT         NOT NULL DEFAULT 0,
  CONSTRAINT fk_attr_value_attr FOREIGN KEY (tenant_id, attribute_id)
    REFERENCES product_attribute (tenant_id, id),
  CONSTRAINT uq_attr_value UNIQUE (tenant_id, attribute_id, value)
);
```

`is_sku_level` 区分两类属性：**产品级**（材质、系列，对同一 SPU 的所有 SKU 都一样）和**变体级**（颜色、尺码，逐 SKU 不同）。这决定了它挂在 `spu_attribute` 还是 `sku_attribute`。

### SPU / SKU

```sql
CREATE TABLE spu (
  id          UUID        PRIMARY KEY,
  tenant_id   UUID        NOT NULL REFERENCES tenant (id),
  code        TEXT        NOT NULL,
  name        TEXT        NOT NULL,
  brand_id    UUID,
  category_id UUID,
  description TEXT,
  status      TEXT        NOT NULL DEFAULT 'ACTIVE',
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_spu_brand    FOREIGN KEY (tenant_id, brand_id)    REFERENCES brand (tenant_id, id),
  CONSTRAINT fk_spu_category FOREIGN KEY (tenant_id, category_id) REFERENCES category (tenant_id, id),
  CONSTRAINT uq_spu_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_spu_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_spu_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED'))
);

CREATE TABLE sku (
  id                     UUID        PRIMARY KEY,
  tenant_id              UUID        NOT NULL REFERENCES tenant (id),
  spu_id                 UUID        NOT NULL,
  code                   TEXT        NOT NULL,
  name                   TEXT        NOT NULL,
  barcode                TEXT,
  spec_text              TEXT,          -- 展示用规格串，如 "红 / L"
  unit_id                UUID,
  weight_kg              NUMERIC(19,6) NOT NULL DEFAULT 0,
  volume_cm3             NUMERIC(19,6) NOT NULL DEFAULT 0,
  standard_cost_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
  standard_cost_currency CHAR(3)       NOT NULL,
  is_serial_tracked      BOOLEAN      NOT NULL DEFAULT FALSE,
  status                 TEXT         NOT NULL DEFAULT 'ACTIVE',
  created_at             timestamptz  NOT NULL DEFAULT now(),
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT fk_sku_spu  FOREIGN KEY (tenant_id, spu_id)  REFERENCES spu (tenant_id, id),
  CONSTRAINT fk_sku_unit FOREIGN KEY (tenant_id, unit_id) REFERENCES unit_of_measure (tenant_id, id),
  CONSTRAINT uq_sku_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_sku_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_sku_status  CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_sku_cost    CHECK (standard_cost_amount >= 0)
);
```

`standard_cost` 是**缓存的当前标准成本**，方便估价与毛利预估；真实成本历史在 `purchase_order_line` 与 `inventory_ledger.unit_cost_amount` 里。改采购价不追溯改这个字段，但要由 purchase 域的 service 同步刷新。

### 变型关联

```sql
CREATE TABLE spu_attribute (
  id           UUID PRIMARY KEY,
  tenant_id    UUID NOT NULL REFERENCES tenant (id),
  spu_id       UUID NOT NULL,
  attribute_id UUID NOT NULL,
  value_id     UUID,
  value_text   TEXT,          -- value_type 非 ENUM 时直接存值
  CONSTRAINT fk_spu_attr_spu  FOREIGN KEY (tenant_id, spu_id)  REFERENCES spu (tenant_id, id),
  CONSTRAINT fk_spu_attr_attr FOREIGN KEY (tenant_id, attribute_id) REFERENCES product_attribute (tenant_id, id),
  CONSTRAINT fk_spu_attr_val  FOREIGN KEY (tenant_id, value_id) REFERENCES product_attribute_value (tenant_id, id),
  CONSTRAINT uq_spu_attribute UNIQUE (tenant_id, spu_id, attribute_id),
  CONSTRAINT ck_spu_attr_shape CHECK (
    (value_type_is_enum AND value_id IS NOT NULL AND value_text IS NULL) OR
    (NOT value_type_is_enum AND value_id IS NULL)
  )
);
```

`spu_attribute` 和 `sku_attribute` 结构相同，只是外键指向 `spu` 还是 `sku`。**分成两张表而不是一张带 `product_type` 多态外键的表** —— 多态外键拿不到引用完整性，而这里的正确性直接影响「同一个 SPU 下是否会生成重复 SKU 组合」。

`value_id` 与 `value_text` 二选一，由 `product_attribute.value_type` 决定，用 CHECK 约束保证。`value_type_is_enum` 在 CHECK 里通过子查询不可直接写，落地时改为触发器或在 CHECK 里只约束「两者不同时非空」：

```sql
-- 可直接落地的简化约束
CONSTRAINT ck_spu_attr_value_xor CHECK (num_nonnulls(value_id, value_text) <= 1)
```

## 往来单位

### 供应商 / 客户 / 承运商

```sql
CREATE TABLE supplier (
  id                   UUID        PRIMARY KEY,
  tenant_id            UUID        NOT NULL REFERENCES tenant (id),
  code                 TEXT        NOT NULL,
  name                 TEXT        NOT NULL,
  country_code         CHAR(2)     NOT NULL,
  default_currency     CHAR(3)     NOT NULL,
  payment_terms_days   INT         NOT NULL DEFAULT 0,
  status               TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_supplier_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_supplier_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_supplier_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLACKLISTED'))
);

CREATE TABLE customer (
  id                    UUID        PRIMARY KEY,
  tenant_id             UUID        NOT NULL REFERENCES tenant (id),
  code                  TEXT        NOT NULL,
  external_customer_id  TEXT,              -- 平台侧客户 ID
  name                  TEXT        NOT NULL,
  email                 TEXT,
  phone                 TEXT,
  country_code          CHAR(2)     NOT NULL,
  default_currency      CHAR(3)     NOT NULL,
  status                TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_customer_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_customer_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

CREATE TABLE carrier (
  id                     UUID        PRIMARY KEY,
  tenant_id              UUID        NOT NULL REFERENCES tenant (id),
  code                   TEXT        NOT NULL,
  name                   TEXT        NOT NULL,
  tracking_url_template  TEXT,             -- 含 {tracking_no} 占位
  status                 TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_carrier_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_carrier_tenant_id   UNIQUE (tenant_id, id)
);
```

`customer.external_customer_id` 单独拆出来是因为**同一个平台客户在同一店铺下才是稳定的**，跨店铺会重复。真正的唯一性约束在 [03-channel.md](03-channel.md) 的店铺级映射里，这里 `external_customer_id` 只是辅助查找，不加唯一约束。

### 地址

```sql
CREATE TABLE address (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  country_code CHAR(2)     NOT NULL,
  state        TEXT,
  city         TEXT        NOT NULL,
  district     TEXT,
  line1        TEXT        NOT NULL,
  line2        TEXT,
  postal_code  TEXT,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_address_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE customer_address (
  tenant_id   UUID NOT NULL REFERENCES tenant (id),
  customer_id UUID NOT NULL,
  address_id  UUID NOT NULL,
  usage       TEXT NOT NULL,           -- 'SHIPPING' | 'BILLING' | 'RETURN'
  is_default  BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT fk_cust_addr_cust FOREIGN KEY (tenant_id, customer_id) REFERENCES customer (tenant_id, id),
  CONSTRAINT fk_cust_addr_addr FOREIGN KEY (tenant_id, address_id)  REFERENCES address (tenant_id, id),
  CONSTRAINT uq_customer_address UNIQUE (tenant_id, customer_id, address_id, usage)
);

CREATE TABLE supplier_address (
  tenant_id   UUID NOT NULL REFERENCES tenant (id),
  supplier_id UUID NOT NULL,
  address_id  UUID NOT NULL,
  usage       TEXT NOT NULL,           -- 'SHIP_FROM' | 'REMIT_TO' | 'RETURN'
  CONSTRAINT fk_sup_addr_sup FOREIGN KEY (tenant_id, supplier_id) REFERENCES supplier (tenant_id, id),
  CONSTRAINT fk_sup_addr_addr FOREIGN KEY (tenant_id, address_id)  REFERENCES address (tenant_id, id),
  CONSTRAINT uq_supplier_address UNIQUE (tenant_id, supplier_id, address_id, usage)
);
```

`address` 做成独立实体 + 关联表，而不是把地址字段直接塞进 `customer` / `supplier`。因为一个客户会有多个地址，且**订单必须快照地址内容**（见 [05-order.md](05-order.md)）—— 地址主数据改了不能影响历史订单。

地址不做软删除：引用它的关联表会挡住删除，客户想「删」地址就是删关联行。

### 供应商货号

```sql
CREATE TABLE supplier_sku (
  id                 UUID        PRIMARY KEY,
  tenant_id          UUID        NOT NULL REFERENCES tenant (id),
  supplier_id        UUID        NOT NULL,
  sku_id             UUID        NOT NULL,
  supplier_sku_code  TEXT        NOT NULL,
  lead_time_days     INT         NOT NULL DEFAULT 0,
  min_order_qty      NUMERIC(19,4) NOT NULL DEFAULT 1,
  unit_price_amount  NUMERIC(19,4) NOT NULL,
  unit_price_currency CHAR(3)     NOT NULL,
  is_preferred       BOOLEAN     NOT NULL DEFAULT FALSE,
  status             TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fk_sup_sku_sup FOREIGN KEY (tenant_id, supplier_id) REFERENCES supplier (tenant_id, id),
  CONSTRAINT fk_sup_sku_sku FOREIGN KEY (tenant_id, sku_id)      REFERENCES sku (tenant_id, id),
  CONSTRAINT uq_supplier_sku_code UNIQUE (tenant_id, supplier_id, supplier_sku_code),
  CONSTRAINT ck_sup_sku_qty   CHECK (min_order_qty > 0),
  CONSTRAINT ck_sup_sku_price CHECK (unit_price_amount >= 0)
);
```

`supplier_sku_code` 是 1688 / 供应商体系的核心键。一个内部 SKU 可能有多个供应商供货，所以这里不是一对一。

## 跨域暴露

| 给谁      | 暴露什么                             | 怎么用                         |
| --------- | ------------------------------------ | ------------------------------ |
| channel   | `SkuCatalog.lookupByCode / findById` | 外部 SKU 映射时解析内部 SKU    |
| order     | `SkuCatalog.findByIds(ids)`          | 下单时批量取规格与单位         |
| inventory | `SkuCatalog.findByIds(ids)`          | 库存流水记账时校验 SKU 存在    |
| purchase  | `SkuCatalog` + `SupplierCatalog`     | 采购建行                       |
| finance   | `SkuCatalog.findByIds(ids)`          | 成本结转（读 `standard_cost`） |

## 关键不变量

- `sku.code`、`spu.code` 在租户内唯一，且**一旦有单据引用就不可改**
- 同一 `spu` 下，**变体属性组合必须唯一** —— 否则会生成两个完全相同的 SKU。约束落在 service 层（创建 SKU 前查重），因为它需要读 `product_attribute.is_sku_level` 才能判断
- `sku` 被任何单据引用后只能 `INACTIVE`，不能物理删除
- `address` 被单据引用后同样不删 —— 单据里有快照，但快照要能追溯回主数据
