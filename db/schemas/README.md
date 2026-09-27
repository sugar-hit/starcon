# starcon 数据模型设计

> 性质：**设计稿**（非可执行迁移）。SQL 是 DDL 草稿，供评审与讨论。
> 落地为 Flyway 迁移是后续里程碑（M1），本目录不产出可直接执行的迁移。
> 决策依据见 [ADR-0002 多租户](../../docs/decisions/0002-multi-tenancy-strategy.md)、[ADR-0003 后端运行时](../../docs/decisions/0003-backend-runtime-kotlin-spring-boot.md)、[选型调研](../../docs/investment/2026-09-27-data-model-and-orm-decision.md)。
> 未决问题统一记在 [`docs/backlog/2026-09/`](../../docs/backlog/2026-09/)，不写在本文里。

日期：2026-09-27。数据库：**PostgreSQL 16**。数据访问：**jOOQ**（不是 ORM，见下）。

---

## 目录

| 文件                                           | 域                                                        | 事实表 owner          |
| ---------------------------------------------- | --------------------------------------------------------- | --------------------- |
| [01-tenancy.md](01-tenancy.md)                 | 租户、组织、用户、成员、角色权限                          | tenancy               |
| [02-master-data.md](02-master-data.md)         | 商品 SPU/SKU/属性/分类/品牌/单位、供应商/客户/承运商/地址 | catalog + partner     |
| [03-channel.md](03-channel.md)                 | 平台、店铺、凭据引用、listing、外部 SKU 映射、同步状态    | channel               |
| [04-inventory.md](04-inventory.md)             | 仓库、库位、库存余额、库存流水、预占                      | inventory             |
| [05-order.md](05-order.md)                     | 订单、订单行、状态机、出库、包裹、运单轨迹                | order + fulfillment   |
| [06-purchase.md](06-purchase.md)               | 采购单、收货、退货退款                                    | purchase + aftersales |
| [07-finance.md](07-finance.md)                 | 币种、汇率快照、结算、付款、双分录                        | finance               |
| [08-platform-infra.md](08-platform-infra.md)   | outbox、幂等、审计、附件元数据                            | platform              |
| [09-pricing-and-crm.md](09-pricing-and-crm.md) | 定价、价目阶梯、客户标签与接触记录                        | pricing + crm         |
| [erd.md](erd.md)                               | 核心关系图                                                | —                     |

---

## 全局约定

以下约定对**每一张业务表**强制生效。不符合的表不允许合入。

> 各域文件（`01` ~ `09`）**不重复**下面的 RLS / 复合外键 / 唯一约束样板，只写该域特有的约束。域文件里没出现 RLS 语句不代表该表不开 RLS —— 落地迁移时按本节模板逐表生成。

### 1. 主键

```sql
id UUID PRIMARY KEY
```

- **UUIDv7**，由**应用层**（Kotlin）生成
- 不用 `bigserial`：多写入源时序列会成瓶颈；且外部同步需要在写库前就拿到 ID
- 不用 PG16 内置 `uuidv7()`：PG 16 **没有**这个函数（PG 18 才有），所以应用层生成是必须的，不是可选项
- UUIDv7 的时间前缀让 B-tree 插入保持局部性，优于 v4 的随机分布

### 2. 租户与 RLS

每张业务表强制 `tenant_id UUID NOT NULL`，并启用 RLS：

```sql
ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <table> FORCE  ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON <table>
  USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

三个细节是刻意的：

- `current_setting(..., true)` 第二个参数 = 缺失时返回 NULL 而不抛异常
- `NULLIF(..., '')` 兜住「设了空串」的情况
- 两者合起来 → **上下文未注入时策略求值为 NULL → 查不到任何行**，即 [ADR-0002](../../docs/decisions/0002-multi-tenancy-strategy.md) 要求的 **fail-closed**，而不是整站 500

`FORCE ROW LEVEL SECURITY` 让表 owner 也受策略约束。配合「运行角色不是 superuser、不带 `BYPASSRLS`、且不是表 owner」两道保险。

**例外**：`tenant` 与 `user` 表自身没有 `tenant_id`。`tenant` 用「只能读到自己那行」的策略；`user` 是跨租户全局身份，不参与租户隔离，其可访问性由 `membership` 表约束。

### 3. 复合外键必须带租户维度

跨表引用不能只带 `id`，必须带 `tenant_id`，否则就能构造出「A 租户的行引用 B 租户的 SKU」这种越权数据：

```sql
-- 被引用表需要有这个唯一约束
ALTER TABLE sku ADD CONSTRAINT uq_sku_tenant_id UNIQUE (tenant_id, id);

-- 引用侧
FOREIGN KEY (tenant_id, sku_id) REFERENCES sku (tenant_id, id)
```

**所有** `REFERENCES` 都用复合形式。例外是引用全局表（`currency`、`permission`）。

### 4. 唯一约束一律带租户维度

```sql
CONSTRAINT uq_sku_tenant_code UNIQUE (tenant_id, code)
```

不带 `tenant_id` 的唯一约束会让 A 租户抢走 B 租户的编码，是跨租户干扰而非数据错误 —— 这类 bug 在测试里很难被偶然发现，所以从约束层面堵死。

### 5. 时间

- 一律 `timestamptz`。**禁止 `timestamp`**（无时区）。跨境 ERP 有多时区，`timestamp` 会静默丢信息
- `created_at` / `updated_at`：`timestamptz NOT NULL DEFAULT now()`
- 业务时间戳（`placed_at` / `shipped_at` / `occurred_at`）可空，语义由业务定义
- 日期（账单周期 `period_start` / `period_end`）用 `date`，不带时区

### 6. 金额

- 一律 `NUMERIC`，**禁止 `float` / `double` / `real`**
- 金额**永远与币种成对出现**，不允许只有一个 amount 列
- 小数位按用途区分，别一刀切

| 用途                   | 类型            |
| ---------------------- | --------------- |
| 单据金额、行金额、余额 | `NUMERIC(19,4)` |
| 单价、汇率             | `NUMERIC(19,8)` |
| 重量（kg）             | `NUMERIC(19,6)` |

- 币种用 `CHAR(3)` ISO-4217
- **小数位由 `currency.minor_unit` 决定，不写死在代码里** —— JPY 是 0 位，KWD 是 3 位。如果按「乘 100 转分」硬编码，JPY 金额会差 100 倍

```sql
-- 正确：单据头金额带币种
subtotal_amount  NUMERIC(19,4) NOT NULL,
subtotal_currency CHAR(3)       NOT NULL,
```

### 7. 状态

```sql
status TEXT NOT NULL CONSTRAINT ck_<table>_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'SHIPPED', 'CANCELLED'))
```

**不用 PG 原生 ENUM 类型**，理由：

- PG enum **删值 / 改名**需要重建整个类型，是已知的运维痛点；CHECK 约束就是一条普通迁移
- 枚举演进是常态（订单状态一定会加），让演进成本低比让 DDL 更「严谨」重要
- jOOQ codegen 对 CHECK 约束处理良好

状态机的**合法迁移**另建 `<entity>_status_transition` 表，需要时再加（见 [05-order.md](05-order.md)）。

### 8. 软删除

**默认不做软删除。** 用 `status` 表达生命周期（`INACTIVE` / `ARCHIVED`）。

理由：软删除会污染唯一约束（删了但还占着编码）、让统计口径不一致（`COUNT(*)` 到底含不含）、并且和「事实表 append-only」的原则直接冲突。

**明确的例外**（需要保留 tombstone，否则外部重推会丢映射关系）：

| 表                                    | 原因                                 |
| ------------------------------------- | ------------------------------------ |
| `listing`                             | 平台可能重推已下架商品               |
| `listing_sku_mapping`                 | 同上，映射关系丢了历史订单行会指向空 |
| `outbox_event` / `idempotency_record` | 本身就是 append-only 事实            |

例外表统一用 `deleted_at timestamptz NULL`，且**所有查询必须显式 `deleted_at IS NULL`**。这两处容易漏，所以只在必要时才引入。

### 9. 命名

- 表：复数 `snake_case`
- 主键恒为 `id`
- 外键列 = 目标表单数 + `_id`（`sku_id`、`warehouse_id`）
- 外键列名**带 ID 语义而非「引用的表名」** —— 列名表达它是什么，不表达它指向谁，这样以后换实现不用改列名
- 索引 `idx_<table>_<cols>`，唯一索引 `uq_<table>_<cols>`，检查约束 `ck_<table>_<rule>`，外键 `fk_<table>_<col>`

### 10. JSONB 使用边界

**只允许用于**：outbox 事件体、审计前后值、平台原始响应快照、以及平台侧不固定的自定义属性。

**禁止用于**：任何需要 `WHERE` / `JOIN` / `UNIQUE` / 排序 / 金额计算的字段。

理由：JSONB 一旦承载需要查询的字段，就会出现「同一份数据两个真相」—— 列存一份、`jsonb` 存一份，之后必然漂移。宁可早期多建一列。

### 11. 写入归属

**每张事实表只有一个 owner 域**，其他域只能经其公开 service 或只读视图访问。见各域文件的「跨域暴露」小节。

### 12. jOOQ codegen

- 源：**Flyway 迁移后的真实 schema**（不是实体类反推）
- 生成产物在 `build/generated`，**永不手改**
- 因此本目录的 DDL 草稿是迁移的输入，不是迁移本身

---

## 全表清单

| 表                                                        | 域          | 类型            | 说明                                         |
| --------------------------------------------------------- | ----------- | --------------- | -------------------------------------------- |
| `tenant`                                                  | tenancy     | 事实            | 租户，根实体                                 |
| `organization`                                            | tenancy     | 事实            | 组织，租户下可有多个                         |
| `app_user`                                                | tenancy     | 事实            | 全局用户身份，跨租户                         |
| `membership`                                              | tenancy     | 事实            | 用户 × 组织 × 角色，**租户上下文的解析依据** |
| `role` / `permission` / `role_permission`                 | tenancy     | 事实            | RBAC                                         |
| `spu` / `sku`                                             | catalog     | 事实            | 商品主数据                                   |
| `product_attribute` / `product_attribute_value`           | catalog     | 事实            | 属性定义与候选值                             |
| `spu_attribute` / `sku_attribute`                         | catalog     | 关联            | 变型关系                                     |
| `category` / `brand` / `unit_of_measure`                  | catalog     | 事实            | 分类树 / 品牌 / 单位                         |
| `supplier` / `customer` / `carrier`                       | partner     | 事实            | 供应商 / 客户 / 承运商                       |
| `supplier_sku`                                            | partner     | 事实            | 供应商货号与采购属性                         |
| `address` + `customer_address` / `supplier_address`       | partner     | 事实            | 地址主数据（单据侧另存快照）                 |
| `channel_type` / `shop` / `shop_credential`               | channel     | 事实            | 平台与店铺                                   |
| `listing` / `listing_sku_mapping`                         | channel     | 事实            | 平台商品与外部 SKU 映射                      |
| `channel_sync_state`                                      | channel     | 状态            | 同步游标与失败原因                           |
| `warehouse` / `location`                                  | inventory   | 事实            | 仓库与库位                                   |
| `inventory_balance`                                       | inventory   | 投影            | 余额，`available` 为生成列                   |
| `inventory_ledger`                                        | inventory   | **append-only** | 库存流水，余额的真相来源                     |
| `inventory_reservation`                                   | inventory   | 事实            | 预占                                         |
| `sales_order` / `sales_order_line`                        | order       | 事实            | 订单与行                                     |
| `order_status_history` / `order_status_transition`        | order       | 事实 / 规则     | 状态机审计与合法迁移                         |
| `shipment` / `shipment_line`                              | fulfillment | 事实            | 出库单                                       |
| `parcel` / `tracking_event`                               | shipment    | 事实            | 包裹与轨迹                                   |
| `purchase_order` / `purchase_order_line`                  | purchase    | 事实            | 采购单                                       |
| `goods_receipt` / `goods_receipt_line`                    | purchase    | 事实            | 收货                                         |
| `return_order` / `return_line`                            | aftersales  | 事实            | 退货退款                                     |
| `currency`                                                | finance     | 全局            | ISO-4217，含 `minor_unit`                    |
| `fx_rate_snapshot`                                        | finance     | append-only     | 汇率快照，**取数时点固定**                   |
| `settlement` / `settlement_fee`                           | finance     | 事实            | 平台结算单与费用明细                         |
| `payment`                                                 | finance     | 事实            | 收付款                                       |
| `ledger_account` / `journal_entry` / `journal_entry_line` | finance     | 事实            | 双分录总账（**设计已定，实现排在 backlog**） |
| `price_list` / `price_list_item`                          | pricing     | 事实            | 定价                                         |
| `customer_contact` / `customer_tag` / `customer_tag_link` | crm         | 事实            | CRM                                          |
| `outbox_event`                                            | platform    | **append-only** | 事务 outbox                                  |
| `idempotency_record`                                      | platform    | append-only     | webhook 去重                                 |
| `audit_log`                                               | platform    | append-only     | 审计                                         |
| `attachment`                                              | platform    | 事实            | 对象存储元数据（**只存元数据，字节不进库**） |
