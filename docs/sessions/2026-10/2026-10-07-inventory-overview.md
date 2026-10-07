# 7.1.1.1 库存总览 · warehouse 服务 bootstrap + 总览聚合

日期: 2026-10-07 · 分支 `feat/7-1-1-1-inventory-overview` · 基线 `feat/3-1-3-2-combination-uniqueness` (31ef22c)

## 决策摘要

1. **新建 `warehouse` 服务**（端口 8084），照 catalog 的写法 bootstrap。07 卷蓝图明确 warehouse 是
   `inventory_balance` / `stock_bucket` / `inventory_transaction` 等的唯一所有者，而 `backend/services/`
   此前只有 identity / catalog / channel。
2. **`available` 是 `GENERATED ALWAYS ... STORED` 生成列**，总览可用量只由它聚合得出，没有第三个来源。
3. **质量状态做成租户级真实字典表 `inventory_quality_status`**，余额按它分行；不用 enum 常量。
4. **在途建了最小真实表 `inventory_transfer`**，按收货仓归属，不写死 0。
5. **类目 / 品牌 / 店铺筛选经投影端口，未接入时显式 422**，不返回空集。
6. **总览是纯 SQL 聚合，不存汇总表**；合计与行读同一条 FROM，"总览 == 明细逐行求和"结构上无从违反。
7. **超储阈值建真实租户级 `inventory_policy`**，未设阈值单独计数。

## 关键 trade-off

### 键从 `(tenant,sku,warehouse)` 扩到 `(tenant,sku,warehouse,legal_entity,quality_status)`

设计稿 `db/schemas/04-inventory.md` 的键是 `(tenant, sku, warehouse)`，且明确**不含 `location_id`**。
本迁移保留"不含 location_id"，但加了两个维度：

- **legal_entity_id**：7.1.1.1 要求"按仓库/法人/SKU 维度汇总"，7.1.1.2 把最小单位定义为
  "法人货权 × SKU × 仓库 × 库位 × 批次 × 质量状态"。同一仓内不同法人的货不是同一批货；
  混在一行就答不出"这批货算哪个法人的"，货权维度也无法守恒。
- **quality_status_id**：否则待检/残次只能塞进一列再由应用层拆，拆出来的数会与 on_hand 长期漂移。

代价是行数变多、聚合变慢；收益是守恒由外键 + UNIQUE 保证，且 `available` 仍是每行一个生成列。
差异已逐条写在迁移文件头，不静默偏离设计稿。

### 设计稿的 `fk_balance_sku` 没有采纳

`sku` 属 catalog，warehouse 有自己的库，跨服务 FK 违反 AGENTS.md 模块边界纪律。
改为 `sku_ref` 引用登记（逻辑 ID + 展示快照）。同理 `legal_entity_ref`。
集成测试 `跨服务不得建外键_sku只以逻辑ID存在` 直接查 `information_schema` 断言这一点。

### 类目级阈值可存但不参与解析

`spu.category` 是 catalog 侧自由文本列，warehouse 的余额行不知道 SKU 属于哪个类目。
把 CATEGORY 塞进解析链会得到一条**永远不命中**的假阈值 —— 那比没有阈值更糟（界面显示"已配置阈值"
却永远不超储）。所以策略表支持 CATEGORY 范围（可存可读，为 7.2.2 留口），
但本模块解析链是 `SKU > WAREHOUSE > DEFAULT`，原因写在 `policyLateral()` 注释里。

### 在途并入手、不并入可用

守恒式 **在手 = 可售 + 待检 + 残次 + 在途**。在途并入手（货权已归本租户，否则调拨在途期间
凭空少一批货），但**绝不**并入可用 —— 未到货的库存拿去预占就是超卖，而超卖不可逆。
口径写在迁移文件头，`balanceCheck` 在响应里当场复核，不成立时界面直接报警。

### 7.1.1.2 未开发，下钻没有死链

行点击**在总览页内就地展开**仓库级/状态级构成（同一份聚合响应里的数据，不额外造数），
同时每行输出 `detailHref` 指向 7.1.1.2。**不**建占位页、**不**放 404 链接、**不**写"即将上线"。
路由待落地记在 `docs/backlog/2026-10/2026-10-07-inventory-detail-route-pending.md`。

### 预占只能落在可售质量状态

新增触发器 `guard_balance_reservable`：DEFECTIVE / PENDING_INSPECTION 的货一旦被预占，
`available` 会算成"可发货"，这是超卖的另一种形态。

## 文件清单

**新增 · 后端**

- `backend/services/warehouse/pom.xml`
- `backend/services/warehouse/src/main/resources/application.yml`（8084）
- `backend/services/warehouse/src/main/resources/db/migration/V1__warehouse_inventory_core.sql`
- `.../com/starcon/warehouse/WarehouseApplication.java`
- `.../AccessService.java`（`warehouse.` 权限前缀）
- `.../ApiErrorHandler.java`（422 单独 handler）
- `.../CatalogProjectionPort.java` + `UnwiredCatalogProjectionPort.java`
- `.../WarehouseModels.java`
- `.../InventoryOverviewService.java`（纯 SQL 聚合）
- `.../WarehouseReferenceService.java`（参照数据 + 阈值）
- `.../InventoryController.java`
- `backend/services/warehouse/src/test/java/.../InventoryOverviewIntegrationTest.java`（30 例）
- `backend/services/warehouse/src/test/java/.../OpenApiContractTest.java`

**改动 · 后端**

- `backend/pom.xml`（`<modules>` 加 `services/warehouse`）
- `backend/services/identity/src/main/resources/db/migration/V8__warehouse_page_permissions.sql`
- `deploy/docker/docker-compose.catalog.yml`（warehouse + migrate-warehouse）
- `deploy/docker/catalog-init/01-databases.sh`（`starcon_warehouse` 库 + `warehouse_app` 角色）
- `tools/codegen/catalog-contracts.py`（服务列表加 warehouse:8084）

**新增 · 前端**

- `apps/web/src/app/api/warehouse/[...path]/route.ts` + `route.test.ts`
- `apps/web/src/components/inventory/api.ts`
- `apps/web/src/components/inventory/inventory-model.ts` + `inventory-model.test.ts`
- `apps/web/src/components/inventory/inventory-workspace.tsx`

**改动 · 前端**

- `apps/web/src/app/inventory/page.tsx`（`DomainPlaceholder` → 真实工作区）
- `apps/web/src/lib/api-proxy.ts`（warehouse allowlist）
- `apps/web/src/lib/api.ts`（warehouse 路由）

**文档**

- `docs/product/capability-blueprint/module-progress.md`（7.1.1.1 → 已交付）
- `docs/backlog/2026-10/2026-10-07-inventory-detail-route-pending.md`
- 本文件

## 踩坑记录

- **Java 文本块首行换行被 JLS 丢掉**：`"""` 之后那一个换行会被剥离，直接拼字符串会得到
  `as in_transitfrom inventory_balance b` 这种只在运行时炸的错误。加了 `clause()` 统一补前导空格。
- **LATERAL 必须在基础表之后 join**：子查询引用 `b.*`，`b` 得先在 FROM 里出现。
- **主键是全局 uuid 不是复合键**：测试里两个租户共用固定 UUID 会撞主键，改成按租户派生。
- **append-only 触发器挡住测试清场**：`inventory_entry` 的 DELETE 被拒是正确行为，
  测试改用 `TRUNCATE`（不触发行级触发器）重建夹具。
- **PG 报错位置是字节偏移不是行号**。

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS，warehouse 31 例全绿（30 集成 + 1 契约）
- `pnpm typecheck` → 3/3 成功
- `pnpm lint` → 3/3 成功
- `pnpm test` → 22 文件 / 222 例全绿
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `/inventory` 105 kB
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致
- 迁移在临时 PG16 容器上逐条跑通（`EXIT=0`）

## 后续待办

- 7.1.1.2 库存明细（见 backlog）：补 `location` / `inventory_lot`，接 `detailHref` 真实路由
- 7.1.2.1 库存流水台账：复用已建好的 `inventory_transaction` + `inventory_entry`
- 7.2.2 库存策略：在 `inventory_policy` 上扩补货点 / 安全库存 / 提前期（列已留），
  并在接入 catalog 授权读取后把 CATEGORY 纳入阈值解析链
- `CatalogProjectionPort` 接入 catalog / channel 真实适配器后，替换 `UnwiredCatalogProjectionPort` 即可，
  总览与前端筛选逻辑无需改动
