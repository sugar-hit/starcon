# 2026-10-07 在途库存（7.5.1.2，阶段 D3）

## 决策摘要

### 1. 不建第二张在途表，只扩 `inventory_transfer`（最重要的一条）

V1 建的 `inventory_transfer` 已经是全服务在途的唯一事实，`InventoryOverviewService.IN_TRANSIT`、
`InventoryAvailabilityService:130`、`InventoryDistributionService`、`InventoryTransferService.ship`
四条既有查询都按 `sum(quantity) where status='IN_TRANSIT'` 求在途。

蓝图 7.5.1.2 关系段说「在途漏算会导致重复补货，这是跨境库存积压的最常见原因」。漏算的根因
永远是**同一个在途有两套账**，某次改造只更新了其中一套，6.2.1.1 补货扣的就不是真实在途。

所以头程/采购/FBA 发货在途也写进 `inventory_transfer`（V63 扩列），四条既有读者**一行都不用改**
就自动把新类型在途算进去 —— 它们只按状态求和，本来就不该关心这条腿是调拨还是头程。

### 2. 调拨腿只读，ETA 读侧派生

- 登记端点显式拒绝 `transitKind=TRANSFER`：调拨在途由 7.5.1.1 调拨单发出时产生，
  两处都能写同一批货会让在途数翻倍。
- 调拨腿的 ETA 用 `coalesce(t.expected_arrive_at, transfer_order.expected_arrive_at)` 在读侧派生，
  **不需要改 7.5.1.1 的任何一行代码**，它的既有单据立刻可算逾期。

### 3. 逾期是真算的，库里没有可写的标记列

档位 = ETA 与「此刻」的差实时分桶（ON_TIME / MILD 24h / OVERDUE 72h / SEVERE 168h / CRITICAL），
服务层 `InTransitOverdueLevel.of` 与 SQL 表达式 `overdueLevel()` 共用同一套阈值，
两个页面上同一条腿不会给出互相矛盾的档位。改 ETA 档位立刻跟着变。

两个刻意的不算成逾期：**没有 ETA 的历史行**（如实显示「未登记预计到达」，否则预警一上线全是噪声）、
**已了结的腿**（货都到了还进待办清单）。

### 4. 外部腿落账分两段

| 类型 | 登记时 | 到货时 |
| --- | --- | --- |
| FBA 发货 | **立刻扣减发货仓在手** + 写 `SHIPMENT` 分录（货真的离仓了） | 只了结在途，**不入库**（平台仓库存属 7.1.1.2 外部仓观测，未交付） |
| 头程 / 采购 | **不动在手**（货还没进我方账） | 入库目的仓 + 写 `RECEIPT` 分录 |

FBA 登记不扣就是在途与在手双向虚增；头程登记就入库等于货到之前凭空多出库存。

### 5. 跨服务边界：定位键 + 可读名快照

头程属 10 物流与头程、采购属 6 采购、FBA 属 4 外部仓，**三者均未交付**。AGENTS.md 禁止跨服务
SQL/FK/视图，所以只带 `source_system + source_reference`（跨服务定位键兼幂等锚点，部分唯一索引
挡重复投递双记在途）与写入时的可读名快照，不建外键也不连查它们的库。

供应商工厂与 Amazon FBA 仓不在本租户 `warehouse` 内，因此**放宽** `from/to_warehouse_id` 的
NOT NULL —— 硬塞一个本租户仓进去就是在编造事实。两站至少一站是本租户仓（`ck_transfer_endpoint`），
仓外那一站必须有可读名快照（`ck_transfer_endpoint_name`）。

**将来交接**：上游交付后由各自适配器调本服务「登记在途腿」接口或直接发事件，
`source_system`/`source_reference` 就是对接契约，本表结构不需要再变。

## 关键 trade-off

- **放宽 `inventory_transfer` 两列 NOT NULL**（共享表）vs 新建一张外部在途表。
  选前者：新建表会让在途有两套账，正是蓝图警告的漏算根因。放宽只影响新写入，既有行两列都有值，
  老查询与 CHECK 一行未改。
- **`settled_quantity` 而非 `arrived_quantity`**：短少不是「到了」，混在一个 arrived 列里
  「原发 = 已了结 + 未达」会算错。
- **已了结的腿 `quantity` 不能归零**（V1 的 `CHECK(quantity>0)`），所以 `declaredQuantity` 在结案后
  以 `settled_quantity` 为准 —— 这一点由 V63 的 `guard_in_transit_settled` 延迟约束
  （`settled_quantity = 到达事件之和`）钉死，是测试里从「970 vs 500」那个失败反推出来的。
- **清单只列仍在途的腿**：已了结的腿答不出「还在不在路上」，走详情端点看到达经过。
  这样下拉条数与汇总口径天然一致。

## 文件清单

新增：
- `backend/services/warehouse/src/main/resources/db/migration/V63__warehouse_in_transit_leg.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryInTransitModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryInTransitService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryInTransitController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryInTransitIntegrationTest.java`

改动：
- `docs/product/capability-blueprint/module-progress.md`（仅 7.5.1.2 一行的状态与证据两格）

## 验证

- `./mvnw -pl services/warehouse test -Dtest=InventoryInTransitIntegrationTest` → **24 例全绿**
  （Testcontainers PostgreSQL 16，连接池压到 1，含租户隔离三断言）
- `./mvnw -pl services/warehouse test -Dtest=OpenApiContractTest` → 1 例通过，spec 产出到
  `backend/target/openapi/warehouse.openapi.json`
- `InTransit*` 全部 18 个 DTO 名在 `packages/api-contracts/src/*.json` 中**未被占用**

## 后续待办

1. **前端页面 `/inventory/in-transit`**（未做，硬停机到点）。做完时**必须**在
   `apps/web/src/lib/api-proxy.ts` 的仓库白名单登记，且分支要放在 `inventory/(?:…)` 组内、
   每个子路径前面带 `/`。本车道故意没登记 —— 没有调用点，登记了就是给未交付模块放行。
2. **重新生成前端契约**：codegen 需要四个服务的 spec 同时在 `backend/target/openapi`，
   要跑一次全量 `./mvnw verify`。本次只跑了 warehouse 段，所以 `packages/api-contracts` 与
   `packages/sdk` 未更新。
3. **全量验证未跑**：`./mvnw verify`、`pnpm typecheck/lint/test`、
   `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`、
   `node tools/module-progress/sync.mjs --check` 全部未执行。补做时按交接说明先量基线测试数。
4. 调拨腿的「已到达」权威口径在调拨单的已实收列（7.5.1.1），本模块详情只带出调拨单号。
   若要在一行里同时看到「这腿已到多少」，需要 7.5.1.1 那边把分次收货的到达量也回写到在途腿 ——
   那是 7.5.1.1 的写侧，本车道刻意没动。
