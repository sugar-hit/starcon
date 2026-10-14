# 6.4.1.1 加工单：trade 建单据、warehouse 同事务扣料入库

- 车道：6.4.1.1（`feat/6-4-1-1`，worktree `.worktrees/6.4.1.1`）
- 基线：`integration/module-dev` @ 225ba19
- 迁移：trade `V97__trade_processing_order.sql`（warehouse 侧**不新建表**）

## 决策摘要

### 1. 归属 trade，不新建 purchase 服务

蓝图 README 系统地图 06 行写死「后端主责服务：**trade + catalog（party）**」，
本仓**不存在** `purchase` 服务。`tools/module-progress/lane-service-gate.itest.mjs`
的注释写明这条服务归属**不在代码里另抄一张表**，而是读蓝图 README 那一列 ——
所以 README 是权威源，V97 落 trade。

前端路由仍是 `/purchase/processing`（台账与蓝图都这么写），
后端命名空间按服务走（`/trade/processing`），与 9.3.2.2
（前端 `/orders`、后端 `/trade/fulfillment/routing`）同一纪律。

**开工前依赖自检结论**：上游 3.5.2.1 BOM 已交付（catalog V25，三张表 + R1–R7），
catalog 已暴露 V25 文件头第六节明确留给下游的两个接口：
`GET /catalog/boms/{id}/effective`（R4 时刻查询）与
`GET /catalog/bom-versions/{id}/requirement`（R7 单层需求量）。
下游 7.1/7.2 库存已交付（warehouse V1/V2），**8.4.1.1 组装单未开发**。

### 2. 模块边界：加粗句当验收项做

蓝图 6.4.1.1 关系段的加粗句「加工是『消耗组件产生成品』的库存动作，
必须在 warehouse 本地事务完成，不能由 trade 直接改库存」拆成两个服务：

| 服务                | 建了什么                                             | 库存动作                          |
| ------------------- | ---------------------------------------------------- | --------------------------------- |
| trade (V97)         | 加工单、组件领用记录、状态与进度事件流、入库需求通知 | **一张库存表都不碰**              |
| warehouse（无迁移） | —                                                    | 一个本地事务里组件出库 + 成品入库 |

warehouse 侧复用 V1 既有 `inventory_transaction` / `inventory_entry`
（`kind='ADJUSTMENT'`、`ref_type='PROCESSING_ORDER'`），
`UNIQUE(tenant_id, ref_type, ref_id)` 顺带给幂等 ——
与 8.2.1.2 收货差异处理「带符号增量不动既有库存表」同一纪律。

**顺序刻意「先出料、后入货」**：出料失败时成品还没入账；
反过来先入料再出料，失败时货已入库，账上凭空多出成品。

### 3. BOM 口径不重新定义

组件领用量经 `BomVersionPort` 调 catalog 授权接口取回 R7 三元组
（单位用量 × 产量 × (1 + 损耗率/100)，6 位小数 HALF_UP；损耗率是**百分数**不是比率）
并**原样冻结**。trade 不重算、不取整 —— 取整是 3.5.2.2 与 06 采购建议的职责。
catalog 不可达显式 503，**不返回空组件冒充「这个 BOM 没有组件」**。

## 关键 trade-off

**部分入库不推进终态。** 最初实收 8 / 待入库 10 也把加工单推到 `RECEIVED`，
但 `RECEIVED` 是终态（DB 触发器钉死不可离开），剩下 2 件将**永远没有地方登记** ——
正是仓库账实不符的起点。改为未收满时加工单停在 `COMPLETED`、通知转 `PARTIAL`，
收满才转 `RECEIVED`。这是测试逼出来的设计修正，不是测试期望写错。

**已领数量只增不减、冻结列不可改。** 已下达的单已经在仓库按当时的量领过料，
改需求量等于追溯改写「当时该领多少」（与 V25 差异 3 同源）。

## 踩到并修掉的真问题

1. **`apps/web/src/lib/api.ts` 的 `backends` 漏了 `trade` 一项** ——
   9.3.2.2 的模型已经在调 `trade/fulfillment/routing/...`，而本表没有对应分支，
   `api()` 会直接抛「未知的接口路径」。本车道**追加**该项（union-safe，不动既有四项）。
2. **jOOQ `fetch(String, Object...)` 收到 `List` 不会展开** ——
   `pageOrders` / `pageFactories` / warehouse `pageExecutions` 三处静默按单参数绑定，
   补 `.toArray()`。
3. **`pageExecutions` 的 count 查询拼出两个 `where`**（`ref_type` 已带 where，
   可选条件又写了 `where ref_id = ?`），SQL 直接语法错 —— 改 `and` 续写。
4. **部分入库推进终态**（见上）。
5. 另修 `postInbound` 的 SQL 字符串拼接笔误（原本会把 `on_hand` **减**掉）。

## api-proxy 合并纪律（两层 alternation 都要求并）

`apps/web/src/lib/api-proxy.ts` 是多车道共用的累积型白名单，本车道**只追加**：

- warehouse 外层 alternation：追加 `processing-orders(?:\/capabilities|\/boundary|\/issues|\/executions)?`
- trade 正则：由 `^fulfillment\/routing\/...$` 改成
  `^(?:fulfillment\/routing\/...|processing\/...)$`（**求并，不是二选一**）

守卫 `apps/web/src/app/api/trade/processing-order-allowlist.test.ts` 6 例，
判据是**真跑 proxy 断言 HTTP 状态码**（放行 = 200，拦截 = 404），
并从模型源码反向抽取真实调用点。

**实测反例确认守卫非空转**：故意把 trade 正则改成只放行 `processing`，
守卫立刻抓到 `9.3.2.2 的 trade/fulfillment/routing/capabilities 被本车道误伤`，
随后恢复 —— 证明这条断言真的会红，不是同义反复。

## 文件清单

**迁移**

- `backend/services/trade/src/main/resources/db/migration/V97__trade_processing_order.sql`

**后端（trade）**

- `ProcessingModels.java` / `ProcessingOrderService.java` / `ProcessingOrderController.java`
- `BomVersionPort.java`（catalog 跨服务端口）

**后端（warehouse）**

- `ProcessingOrderModels.java` / `ProcessingOrderService.java` / `ProcessingOrderController.java`

**测试**

- `backend/services/trade/src/test/java/com/starcon/trade/ProcessingOrderIntegrationTest.java`（27 例）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ProcessingOrderIntegrationTest.java`（16 例）
- `apps/web/src/components/purchase/processing-order-model.test.ts`（24 例）
- `apps/web/src/app/api/trade/processing-order-allowlist.test.ts`（6 例）

**前端**

- `apps/web/src/app/purchase/processing/page.tsx`
- `apps/web/src/components/purchase/processing-order-workspace.tsx`
- `apps/web/src/components/purchase/processing-order-model.ts`

**共享文件（追加式改动，并入时需求并）**

- `apps/web/src/lib/api-proxy.ts`（两处追加）
- `apps/web/src/lib/api.ts`（追加 `trade/` 一项）
- `packages/api-contracts/src/*`（codegen 产物，只跑脚本不手改）

## 验证

- `cd backend && ./mvnw verify` 全服务通过
- `pnpm typecheck` 全绿、`pnpm lint` 全绿（仅既有模块的 warning）
- `pnpm test` 100 文件 / 1840 例全绿
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` 通过，`/purchase/processing` 9.49 kB
- `node tools/module-progress/sync.mjs --check` 通过（已交付 117 / 465）

## 后续待办

- **8.4.1.1 组装单**（D4，未开发）：仓库现场作业单据未建模，
  当前只做库存层面的组件出库与成品入库，拣货 / 复核 / 上架的现场任务流尚无。
  `capabilities` 已显式 `available=false` 并写明 ownerModule 与影响。
- **6.4.1.2 BOM 关联**（D6，未开发）：同组兄弟模块的冻结入口未交付。
  效果上已被「下达时把当版 BOM 的组件与用量冻结进组件领用记录」覆盖，
  但独立的冻结动作入口仍缺。
- **6.4.2.1 质检单 / 6.4.2.2 不良品处理**：完工报数时无法做抽样判定与扣量。
- **6.5.1.1 采购对账**：加工单暂不参与对账单。
