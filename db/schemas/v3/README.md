# V3：物理数据结构设计（PostgreSQL 16）

> 日期：2026-09-28。状态：**设计基线，未实施**。除标注「已实施」外，本文与同目录 `.sql` 均**不是** Flyway 迁移，不直接执行到业务库。
> 逻辑前置：[`v2/README.md`](../v2/README.md)（实体目录、业务关系、开发阶段）、[V2 功能蓝图](../../product/capability-blueprint/README.md)（465 个模块）。
> 已实施现状：只有 `identity` 与 `catalog` 有真实迁移（`backend/services/*/src/main/resources/db/migration/V1~V3`）。本文的规约**从这两套已实施迁移中提炼**，不推翻它们。

本文回答：**这些表在 PG 里长什么样**。v2 回答"有哪些实体、谁拥有"，v3 回答"列、类型、约束、索引、触发器、物理规则"。

---

## 0. 先说复检发现的问题

复检功能蓝图本身**没有发现问题**（树/详情一致、四项字段完整、编号无重复、编码无损）。但把蓝图与**已实施的迁移**对照后，发现 6 个真实缺陷——它们不是蓝图的问题，是**已落地代码与蓝图之间的差距**：

| # | 问题 | 证据 | 影响 | 处置 |
|---|---|---|---|---|
| **D1** | `product_compliance`、`product_asset` 是 `LIKE product_research INCLUDING ALL` 的**同构复制** | `catalog V1:77-80` | 三张表列结构完全相同（title/notes/reference/locale…）。合规表**装不了 HS 编码、认证编号、有效期**；素材表**装不了文件键、尺寸、格式、引用计数**。蓝图 3.7 与 3.4 需求无法落库 | V3 拆为独立表，见 §4.2 |
| **D2** | 缺 `inbox_message` | 全仓 grep 0 命中 | 蓝图 02.6.1.2「重复 webhook 不产生重复订单」的物理基础不存在 | V3 纳入技术表模板，见 §4.1 |
| **D3** | 缺 `idempotency_request` | 全仓 grep 0 命中 | 蓝图 02.5.1.1 要求回写任务带幂等键，无处落地 | 同上 |
| **D4** | `spu.brand` / `spu.category` 是自由文本 `varchar` | `catalog V1:3` | 品牌/分类无法成为实体，**防侵权、品牌授权状态、类目属性要求**（蓝图 3.3.1.2 / 16.4.2.1）都无从实现 | V3 增 `brand` / `category` 树表，spu 改引用；兼容期保留旧列 |
| **D5** | `department` 是平表无层级 | `identity V3:1-3` | 蓝图 17.2.1.1 要求部门树 + 防环 + 负责人 | V3 增 `parent_id` + 环检测触发器 |
| **D6** | `role_assignment.scope_mode` 只有 ALL/DEPARTMENT/SELF，且只按 `owner_id`/`department_id` 过滤 | `catalog V3` 的 `catalog_scope()` | 蓝图 17.3.2.2 要求**按法人/店铺/仓库/部门四维**限定数据范围。当前模型无法表达"只能看 A 法人 + C 仓库" | V3 增 `role_assignment_scope` 多维范围表 |

**D2/D3/D4/D5/D6 属"已实施但要演进"，处理方式见 §7 迁移演进；D1 需拆表，必须在新表落地后再迁数据。**

---

## 1. 物理设计规约（权威，全服务适用）

以下规则**从已实施迁移提炼**并收紧。任何人写新表必须遵守；违反视为设计缺陷，不是实现细节。

### 1.1 主键与标识

| 规则 | 规范 | 依据 |
|---|---|---|
| 主键 | `id uuid PRIMARY KEY`，**UUIDv7 由应用生成**（库不生成），时间有序利于 B-tree | 现有迁移 `id uuid PRIMARY KEY` |
| 编码 | 业务可读编码单列（如 `code`），**是标识不是外键** | 实体目录 §5 |
| 业务 ID | 不使用自增整型对外暴露 | AGENTS.md |

### 1.2 多租户与外键（最重要的一组）

```sql
tenant_id uuid NOT NULL,
UNIQUE(tenant_id, id)                                   -- 所有租户业务表必须有
FOREIGN KEY(tenant_id, spu_id) REFERENCES spu(tenant_id, id)   -- 本地复合外键
```

- **所有租户业务表 `tenant_id NOT NULL`**；例外仅限全局码表（`platform` `unit_of_measure` 等）与 `app_user`
- **本地引用必须用复合外键**（含 `tenant_id`），单列外键会跨租户串数据
- **跨服务只存逻辑 ID，不建外键**（实体目录 §2）
- 列表索引**以 `tenant_id` 开头**

### 1.3 行级安全（RLS）

沿用已实施的三件套，**每个服务每张表都要有**：

```sql
ALTER TABLE x ENABLE ROW LEVEL SECURITY;
ALTER TABLE x FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON x
  USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
```

- 运行账号必须 `NOSUPERUSER NOBYPASSRLS`（`deploy/docker/catalog-init/01-databases.sh` 已如此）
- 事务内 `set_config('app.current_tenant', ?, true)`，**无上下文即拒绝访问**
- **引导/登录路径用 `SECURITY DEFINER` 窄函数**，不给普通请求绕过 RLS 的权限（identity V1 已示范）

### 1.4 数值类型与精度（禁止浮点）

| 语义 | 类型 | 说明 |
|---|---|---|
| 金额 | `numeric(19,4)` | `BigDecimal`，禁 float/double |
| 单价、汇率 | `numeric(19,8)` | 汇率要 8 位才够 |
| 数量 | `numeric(19,6)` | 单位换算后落基础单位 |
| 比例/权重 | `numeric(9,6)` | 分摊比例 |
| 业务计数 | `bigint` | 版本号、配额 |

**每张含金额的表必须显式带 `currency char(3)`**，不允许"默认本币"隐式约定。

### 1.5 时间

| 语义 | 类型 | 说明 |
|---|---|---|
| 事件发生时间 | `timestamptz` | 库存流水、事件、审计 |
| 业务日期 | `date` | 记账日、发货日、结算周期 |
| 时区 | `text` IANA（如 `America/Los_Angeles`） | 店铺/仓库时区，**不用 varchar(3) 缩写** |
| 业务时区 | `text` IANA | "需要解释业务日"的场景单独存 |

**禁止用 `created_at` 代替业务时间**（实体目录 §3.9）。一张单据可能同时有 `placed_at`（平台下单）、`received_at`（入库）、`posted_on`（记账日）、`created_at`（系统写入）。

### 1.6 状态与状态机

- **状态用 `text` + `CHECK`，不用 PG `enum`**（enum 加值要 `ALTER TYPE`，迁移与回滚都麻烦）
- `status text NOT NULL DEFAULT 'X' CHECK(status IN (...))` —— 与现有迁移一致
- **CHECK 只管"有值是否合法"，转换合法性由服务层状态机管**（AGENTS.md：有值不等于可转换）
- 需要 DB 强约束的转换（如库存预占终态不可复活）用触发器，示例见 `02-warehouse-inventory.sql`

### 1.7 乐观锁与时间戳

```sql
version     bigint      NOT NULL DEFAULT 0,   -- 更新必须带 expected_version
created_at  timestamptz NOT NULL DEFAULT now(),
updated_at  timestamptz NOT NULL DEFAULT now(),   -- 注意：不自动更新
```

`updated_at` **不会自动更新**（现有迁移即如此）：更新路径必须显式写 `updated_at = now()`。这是常见遗漏点。

### 1.8 不可变与 append-only

流水类表（库存流水、凭证、事件、审计）**禁止应用账号 UPDATE/DELETE**：

```sql
REVOKE UPDATE, DELETE ON inventory_entry FROM warehouse_app;
CREATE TRIGGER no_update BEFORE UPDATE OR DELETE ON inventory_entry
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
```

纠错方式是**追加反向分录**，不是改历史行（实体目录 §9）。

### 1.9 NULL 语义

- 列表索引里 **`NULL` 唯一**用 PG 15+ 的 `UNIQUE NULLS NOT DISTINCT`（catalog V1 已用）
- 业务上"明确清空"与"未设置"是**两种状态**（如属性 `state IN ('SET','CLEAR')`），不要用 `NULL` 表达
- 批次无值时用显式 `NULL` 并配 `NULLS NOT DISTINCT` 唯一索引

### 1.10 JSONB 边界

JSONB **只用于**：事件载荷、外部平台扩展字段、脱敏快照。
**禁止用于**：核心查询字段、数量、金额、关系。任何"先存 JSONB 以后再说"都是把设计债推到运行时。

### 1.11 索引策略

- 每个业务表按需建 2–4 个索引，**不先给每列建索引**
- 常用：`(tenant_id, status, created_at DESC)` 状态列表；`(tenant_id, header_id)` 单据行；`(tenant_id, external_source, external_id)` 幂等唯一
- 外部来源必须建**幂等唯一索引**，这是去重的基础
- 大表分区**按实际规模再定**，不在第一期过度设计

### 1.12 命名

表名单数 snake_case；外键列 `<单数表名>_id`；索引 `idx_<表>_<用途>` 或沿用现有 `<表>_<用途>`；触发器函数 `snake_case` 动词短语。

---

## 2. 全量表清单（18 系统 → 表）

`服务` = 数据所有权（[ADR-0004](../../decisions/0004-java-spring-boot-microservices.md)）；`批次` = 建议实施顺序。

### 2.1 identity（治理）— 部分已实施

| 表 | 状态 | 蓝图 |
|---|---|---|
| `tenant` `tenant_closure` `app_user` `membership` `department` | 已实施 | 17.1 / 17.2 |
| `role` `permission` `page_resource` `role_permission` `role_assignment` | 已实施 | 17.3 |
| `auth_session` | 已实施 | — |
| `legal_entity` `accounting_book` | 待建 | 17.1.2 / 12.5.1 |
| `role_assignment_scope` | 待建（修 D6） | 17.3.2.2 |
| `org_unit`（部门层级，D5 演进） | 演进 `department` | 17.2.1.1 |
| `approval_flow` `approval_instance` `approval_step` | 待建 | 17.4 |
| `number_rule` | 待建 | 17.5.1.2 |
| `audit_event` | 演进（加变更前后值） | 17.5.3.1 |

### 2.2 catalog（商品与价目）

| 表 | 状态 | 蓝图 |
|---|---|---|
| `spu` `sku` `attribute_definition` `attribute_option` `attribute_value` `attribute_value_item` | 已实施 | 03.1 / 03.2 |
| `brand` `category` `spu_category_link`（修 D4） | 待建 | 03.3 |
| `unit_of_measure` `sku_uom_conversion` `sku_packaging` | 待建 | 03.1.2.4 / 03.3.3 |
| `spu_media` `sku_media` `file_object` | 待建（修 D1） | 03.4.1 |
| `spu_translation` `sku_translation` `copy_template` | 待建 | 03.4.3 / 03.4.2 |
| `product_compliance` `sku_customs_profile`（拆表，修 D1） | 待建 | 03.7 |
| `bundle_version` `bundle_component` `bom_version` `bom_line` | 待建 | 03.5 |
| `party` `supplier_profile` `customer_profile` `carrier_profile` `supplier_offer` | 待建 | 03.6 |
| `price_list` `price_list_item` | 待建 | 05.1 |
| `sku_identifier` | 待建 | 03.1.2.3 |
| `sku_merge_log`（3.9.1.2） | 待建 | 03.9 |

### 2.3 channel（平台与店铺）

`platform` `marketplace` `channel_connection` `credential_ref` `shop` `shop_marketplace` `listing` `listing_variant` `listing_mapping_version` `listing_mapping_component` `external_customer_identity` `sync_cursor` `sync_run` `sync_failure` `webhook_receipt` `external_snapshot` `platform_operation` → 蓝图 02 / 04

### 2.4 warehouse（库存与仓储）— **本文首个完整 DDL**

`stock_owner` `warehouse` `warehouse_location` `stock_bucket` `inventory_transaction` `inventory_entry` `inventory_reservation` `reservation_allocation` `reservation_entry` `inventory_lot` `inventory_serial` `goods_receipt` `goods_receipt_line` `outbound_order` `outbound_order_line` `pick_task` `pick_task_line` `parcel` `parcel_item` `inventory_transfer` `inventory_transfer_line` `stock_count` `stock_count_line` `stock_adjustment` `assembly_order` `assembly_component` `external_stock_observation` `stock_allocation_rule` `stock_allocation_result` → 蓝图 07 / 08

### 2.5 trade（订单与采购售后）

`sales_order` `sales_order_line` `order_address_snapshot` `order_charge` `order_charge_allocation` `order_line_component` `order_status_history` `fulfillment_request` `fulfillment_request_line` `fulfillment_process` `purchase_order` `purchase_order_line` `purchase_receipt_allocation` `dropship_order` `dropship_allocation` `b2b_credit_term` `dealer_authorization` `return_authorization` `return_authorization_line` `return_receipt_allocation` `refund_request` `refund_request_line` `exchange_order_link` `purchase_return` `purchase_return_line` `shipment` `tracking_event` → 蓝图 09 / 06 / 11

### 2.6 finance（财务与税务）

`currency` `exchange_rate` `financial_account` `receivable` `payable` `obligation_line` `payment` `payment_allocation` `refund` `refund_attempt` `settlement` `settlement_line` `reconciliation_run` `reconciliation_match` `reconciliation_exception` `cost_layer` `inventory_cost_entry` `landed_cost_allocation` `ledger_account` `journal_entry` `journal_entry_line` `invoice` `invoice_line` `credit_note` `tax_rate` `vat_return` `points_liability` → 蓝图 12

### 2.7 support / reporting（后续）

`notification` `notification_template` `ticket` `ticket_message` `knowledge_article` `ad_campaign_daily` `profit_daily` `sales_daily` `product_daily` `stock_allocation_daily`（事件构建，可重建）→ 蓝图 01 / 13 / 15

### 2.8 每服务技术表（模板见 `01-technical-core.sql`）

`inbox_message` `idempotency_request` `outbox_event` `audit_event` `reference_projection` `job_run`

**约 150 张表**（含技术表），分 6 个服务库。数量大但**绝大多数是单据头 + 单据行的常规形态**，真正的复杂度集中在 warehouse 的账本与 finance 的凭证——本文优先把这两块写实。

---

## 3. 落地顺序（与 v2 的 D0–D7 对齐）

| 批次 | 内容 | 为什么先做 |
|---|---|---|
| **V4** | 技术表模板（`inbox` `idempotency`）+ 修 D5/D6 | 所有服务都要用，越早越省返工 |
| **V5** | warehouse 库存账本（本文 `02-*.sql`） | 约束最密集，错了后面全错；D3 阶段就要跑 |
| **V6** | trade 订单与采购售后 | 手工单闭环的前提 |
| **V7** | channel 平台接入 | 打通真实流量 |
| **V8** | finance 结算对账与成本 | 利润的地基 |
| **V9** | catalog 扩展（修 D1/D4）+ trade 剩余 | 依赖前面的稳定模型 |

---

## 4. 关键表的物理设计说明

### 4.1 跨服务技术表（`01-technical-core.sql`）

| 表 | 作用 | 关键约束 |
|---|---|---|
| `inbox_message` | 消费去重 | `UNIQUE(tenant_id, consumer, event_id)`，**与业务结果同事务写入** |
| `idempotency_request` | 主动操作幂等 | `UNIQUE(tenant_id, scope, key)` + `request_hash`（防同 key 不同参） |
| `outbox_event` | 可靠发事件 | 业务与事件同事务；relay 投递 |
| `audit_event` | 审计 | append-only；`actor_id` + 资源 + 脱敏变更 |
| `reference_projection` | 跨服务只读副本 | 具名投影，不建万能跨库实体表 |

**三条铁律**（实体目录 §6）：
1. `inbox` 标记消费**必须与业务结果同事务**，不能"先标已消费再写业务"
2. 幂等表**不能成为业务事实**，清理它不能让业务长期唯一约束失效
3. `outbox` 既是 append-only 又要更新投递字段 → **投递状态用独立列更新，不改 payload**；或用 `published_at` + `attempts` 而非改事件本体

### 4.2 catalog 拆表（修 D1）

`product_compliance` / `product_asset` 现在是 `product_research` 的同构复制，**无法承载蓝图需求**。应拆为：

- 合规 → `spu_compliance`（认证类型、编号、发证日、到期日、机构）+ `sku_customs_profile`（HS 编码、申报品名、申报价值、目的地版本）
- 素材 → `file_object`（对象存储键、mime、尺寸、哈希）+ `spu_media` / `sku_media`（绑定与排序）

### 4.3 warehouse 账本（`02-warehouse-inventory.sql`）

蓝图 07 的硬约束在 PG 里的落法：

| 蓝图约束 | 物理实现 |
|---|---|
| `available = on_hand − reserved` | `stock_bucket.available` **生成列**（`GENERATED ALWAYS AS (on_hand - reserved) STORED`），不可能算错 |
| 不允许负库存 | `CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)` |
| 不可售质量状态禁止销售预占 | 触发器：质量状态 ∈ 不可售集合时 `reserved` 必须为 0 |
| 预占终态不可复活 | 触发器：`reservation_allocation.status` 终态后拒绝回到 held |
| 流水 append-only | `REVOKE UPDATE,DELETE` + 触发器 |
| bucket 内部转移数量合计为零 | 事务级校验函数：同一 `inventory_transaction` 内按 bucket 分组求和为 0 |
| 预占先于扣减、防超卖 | 条件更新 `WHERE version = :expected`，`reserved + :qty <= on_hand` |
| 货主维度（7.7 新增） | `stock_bucket` 含 `stock_owner_id`，**不与 `tenant_id` 混用** |
| 多平台分摊（7.8 新增） | `stock_allocation_rule`（策略）+ `stock_allocation_result`（结果快照） |

---

## 4.4 DDL 验证（在真实 PG16 上跑过）

本文的 `.sql` 已对 **PostgreSQL 16.14** 实际执行验证，并跑了约束冒烟。复现方式：

```bash
PG=starcon-catalog-dev-postgres-1
# 角色先决条件（正式环境由 deploy/docker/catalog-init/01-databases.sh 创建）
docker exec $PG psql -U starcon_migrator -d postgres -c \
  "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='warehouse_app')
     THEN CREATE ROLE warehouse_app NOLOGIN; END IF; END \$\$;"
# 独立库加载（不要碰业务库）
docker exec $PG psql -U starcon_migrator -d postgres \
  -c "DROP DATABASE IF EXISTS starcon_schema_check;" -c "CREATE DATABASE starcon_schema_check;"
for f in 01-technical-core 02-warehouse-inventory; do
  docker exec -i $PG psql -U starcon_migrator -d starcon_schema_check -v ON_ERROR_STOP=1 -q < db/schemas/v3/$f.sql
done
# 约束冒烟：验证蓝图铁律真的生效
docker exec -i $PG psql -U starcon_migrator -d starcon_schema_check -v ON_ERROR_STOP=1 -f /dev/null < db/schemas/v3/99-constraint-smoke.sql
```

当前结果：**35 张表 / 92 个 CHECK / 41 个复合外键 / 120 个索引 / 14 个触发器 / 35 张表启用 RLS**，冒烟 10 项全过。

冒烟覆盖：① `available` 生成列 = `on_hand - reserved`　② 拒绝负库存　③ 拒绝 `reserved > on_hand`　④ 拒绝不可售质量状态被预留　⑤ 预占终态不可复活　⑥ 流水 append-only 拒绝 UPDATE　⑦ 内部转移 bucket 净额不为零被拒（延迟约束）　⑧ 补反向分录后放行　⑨⑩ **RLS 以运行账号验证**跨租户读 0 行 / 本租户读 1 行。

> **两个必须记住的坑（都是本次实测踩到的）**：
> 1. **RLS 必须用运行账号测**。`starcon_migrator` 是超级用户，超级用户绕过 RLS（即使 `FORCE`），用它测会得到假阳性通过。
> 2. **复合外键要求被引用表有 `UNIQUE(tenant_id, id)`**。漏一个就报 `no unique constraint matching given keys`。本轮补了 6 张表。

---

## 5. 与缓存的边界

**库存、预占、余额、幂等、权限判定一律不缓存为权威值**。Redis 只做派生与加速，详见 [`03-cache-design.md`](03-cache-design.md)：分层、key 规范、可缓存/禁缓存清单、失效顺序、防护与监控。

一句话原则：**Redis 里可以放"读起来慢但允许短暂不准"的东西（报表投影、平台目录、只读主数据），不放"算错就会亏钱"的东西（可用量、预占、幂等键、权限判定）**。

---

## 6. 边界

**本文不做**：Java 实体类与 jOOQ 生成配置、索引容量测算（等真实数据量）、分区策略落地（等规模）、SQL 调优、报表物化视图设计。
**本文做**：表清单、物理规约、核心 DDL、缺陷清单、落地顺序。
