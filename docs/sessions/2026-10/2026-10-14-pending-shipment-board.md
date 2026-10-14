# 2026-10-14 · 9.4.1.1 待发货（阶段 D4，L3 锚点 9.4.1）

- **车道**：`feat/9-4-1-1`（worktree `/Users/tommy/Project/starcon/.worktrees/9.4.1.1`，基线 `integration/module-dev` @ `f002cc8f`）
- **状态**：已交付（四件齐全：迁移 / API / 页面 / 测试）

## 决策摘要

### 1. 待发货清单的数据来源（本模块最关键的架构决定）

选了 **「warehouse 授权 API + trade 侧只读投影表」的组合**，不是纯穿透、也不是投影表单飞。

上游 8.3.3.2（warehouse，`V121`）把打包结果落在 `packing_order` / `parcel` / `parcel_item`，
**这些表在 warehouse 服务自己的库里**。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，
所以一条 `SELECT` 都不碰对方库，也不建跨库视图。

读取走 `PackingQueryPort`（与 6.4.1.1 的 `BomVersionPort` 读 catalog 逐字同构）：
`GET /warehouse/packing-orders?status=COMPLETED` 取已完成打包的打包单，
`GET /warehouse/packing-orders/{id}` 取包裹与实测读数。
转发的是**调用方自己的 bearer** —— 租户隔离因此是 trade 侧 RLS + warehouse 侧 RLS **两道**，
而不是「本服务替对方决定租户」。

**为什么落到投影表而不是每次穿透**（两条都写进了迁移文件头）：

1. 四个分组维度要在**全量**待发货单元上聚合。逐页穿透再聚合会漏掉没翻到的页，聚合值本身不可信 ——
   而大促期正是靠这一屏决定先发哪个承运商。
2. 三个批量操作要留下**本地任务凭据**（哪些单元被哪张任务覆盖、逐条成功 / 失败），
   这些凭据天然属于 trade，不属于 warehouse。

投影表**唯一写入口**是 `POST /sync`，每行都能追溯回 `source_packing_order_id`。
上游已不在 COMPLETED 列表的旧行**只报数不删除** —— 那些行可能已带运单号、打单凭据与交接记录，
删掉等于把一次已经发生的交接从台账上抹掉。
仓库不可达时**显式 503** 而不是返回空清单：空清单会被现场当成「今天没有待发货的货」而收工。

### 2. 平台 SLA：登记而不伪造

集成头里 warehouse `V121` 打包单**没有** SLA 列，trade `V99` 的 `sales_order` 也**没有**
（`V100` 与 `V122` 文件头已两次记过这件事），2.5.1.1 平台回传未交付。

所以本模块不假装读到了平台时限：未登记的单元落在 **UNSET 独立一桶**，
时限由 `POST /units/{id}/sla` **登记**（有主体、有时刻、有来源 `MANUAL_ATTESTATION` / `PLATFORM_FEED` 四件套互斥）。
capabilities 里 SLA 维度显式 `available=false` 并点名 ownerModule 与影响。

### 3. 本模块最远只到 HANDED_OVER

蓝图 9.4.1.2（发货记录）与 9.4.2.1（回传状态）的上游都是 **8.3.4.1**，不是本模块。
「已发货」是 8.3.4.1 在承运商揽收后确认的事实；提前置它会让发货记录与 17 绩效的**迟发率**
算在一个从未发生的时刻上。DB CHECK 与状态机触发器两道一起钉住，前端也刻意不出现「已发货」字样。

### 4. 一次批量任务不许跨承运商

蓝图那句「**按承运商分组是大促期效率关键**，混在一起会导致交接混乱」在本层被钉死：
给定 `unitIds` 若混了多个承运商，服务层直接 400 **并把冲突的承运商列出来** —— 让人去拆任务，
而不是替他猜该拆成几张。

### 5. 三个批量操作是真任务

任务 + 逐条 item + 运单回执 + 事件流四张表。执行时**任务最终状态由逐条结果算出**
（不接受调用方直接指定）：`SUCCEEDED` 要求零失败零跳过，有跳过只能是 `PARTIAL`，
全部失败必须写 `failure_reason`。

两个上游未交付的地方刻意做成**显式不可成功**而不是假成功：

- **运单**：不调用承运商下单接口（属 10 物流，未交付），单号靠 `/waybill-acks` 登记回执补上；
- **面单**：8.3.4.2 未交付，所以没有真实 `jobRef` 的包裹在打单任务里被记为 **`SKIPPED` 并写明原因**，
  凭据由 `/labels` 回填后重跑才成功。「跳过」与「漏了」必须能区分。

## 关键 trade-off

- **投影表 vs 纯穿透**：投影多两张表与一次同步动作，换来「聚合可信」+「本地任务凭据有处可落」。
  代价是数据有一份延迟 —— 同步时刻会随 `syncedAt` 如实报出，界面上同步按钮旁就写着来源与口径。
- **逐包裹 vs 逐单元的任务行**：批量打单按**逐包裹**去重、其余按**逐单元**去重，
  用两个**部分唯一索引**而不是一条表级 UNIQUE —— 表级 UNIQUE 表达不了
  「`parcel_id` 为空按单元、有值按包裹」这个二选一。
  「打单失败」恰恰发生在某个箱子上，并成一条就再也说不出是哪箱没打上。
- **`ParcelLabelInput` 改名**：与 warehouse 8.3.4.2 的 `ParcelLabelInput` 撞名。
  codegen 在**写盘前**就中止并点名两边的字段差异（warehouse 3 字段 / trade 1 字段，
  以及「trade 消费端会凭空多出哪两个、独有的 `jobRef` 拿不到」），所以改名为
  `PendingShipmentLabelInput`。撞名的后果是**另一条车道**的事故，不能由本车道单方面承担。

## 文件清单

**迁移**：`backend/services/trade/src/main/resources/db/migration/V124__trade_pending_shipment.sql`（六张表，零 ALTER 既有表）

**后端**（`backend/services/trade/src/main/java/com/starcon/trade/`）：

- `PackingQueryPort.java` — 8.3.3.2 授权只读跨服务端口
- `PendingShipmentModels.java` — 枚举与契约形状
- `PendingShipmentService.java` — jOOQ 显式 SQL；看板 / 清单 / 时限登记 / 批量任务
- `PendingShipmentController.java` — 13 个端点

**测试**：`backend/services/trade/src/test/java/com/starcon/trade/PendingShipmentIntegrationTest.java`（21 例）

**前端**：

- `apps/web/src/app/orders/fulfillment/pending-shipment/page.tsx`
- `apps/web/src/components/orders/pending-shipment-workspace.tsx`
- `apps/web/src/components/orders/pending-shipment-model.ts`
- `apps/web/src/components/orders/pending-shipment-model.test.ts`（18 例）
- `apps/web/src/app/api/trade/pending-shipment-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts` — **只在 trade 那一段追加** `fulfillment/pending-shipment` 一组

**契约**：`packages/api-contracts/src/contracts/trade.ts` + `trade.openapi.json`（codegen 产物，纯新增）

**台账**：`docs/product/capability-blueprint/module-progress.md` —— 只改 9.4.1.1 那一行的「状态」「证据」两格

## 验证

- L0：`cd backend && ./mvnw -q compile` → 干净；`pnpm typecheck` → 3/3 成功；`pnpm lint` → 3/3 成功
- L1：`cd backend && ./mvnw -pl services/trade -am test` → **Tests run: 268, Failures: 0, Errors: 0**
  - 其中本模块 `PendingShipmentIntegrationTest` **21 例**，实跑 Testcontainers PostgreSQL 16，连接池压到 1
- 前端：`pnpm vitest run apps/web/src/components/orders/pending-shipment-model.test.ts apps/web/src/app/api/trade/pending-shipment-allowlist.test.ts`
  → **Tests 23 passed**

## 后续待办

1. **9.4.1.2 发货记录**、**9.4.2.1 平台回传**：两者上游都是 8.3.4.1，本模块只交到 `HANDED_OVER`。
2. **8.3.3.2 的只读契约要补三个字段**：销售订单号 / 店铺 / 买家快照目前不在它给的契约里，
   本模块**如实留空**并在同步结果里说明，没有拿出库单号冒充销售订单号。
   9.1 订单侧补齐后由 trade 侧回填，表结构不用改。
3. **承运商从哪来**：当前由同步请求显式携带（来源是 9.3.x 路由决策的口径），
   等 9.3.x 的路由决策落稳后可以改成直接引用它的结论。
4. **平台 SLA 接上游**：2.5.1.1 交付后，把 `sla_source = PLATFORM_FEED` 的登记改成读取，
   `UNSET` 桶自然收敛为 0。
5. **面单打印**：8.3.4.2 交付后，`/labels` 的凭据回填可以省掉，打单任务不再有 `SKIPPED`。
