# 10.2.1.1 运单列表（trade / V132）

- 日期：2026-10-15
- 车道：`feat/10-2-1-1`（worktree `.worktrees/10.2.1.1`）
- 归属：**trade**，迁移 `V132__trade_waybill_listing.sql`

## 决策摘要

1. **归属 trade，只读 10.2.1.2。** 主上游 10.2.1.2 已交付在 trade（`V128`），
   同服务直接读上游优先于跨服务调用。本模块**不写** `logistics_waybill_request`
   任何一列 —— 运单号的唯一写入所有者永远是 10.2.1.2。清单行在**查询时现算**
   （jOOQ 显式 SQL），**不落投影表**：没有投影就没有「刷新前列表空着、
   现场以为今天没单」这种假活，也不需要 sync 定时任务。
   迁移**零 ALTER 既有表**、零触发器挂载、零 FK 指向别模块的表。

2. **蓝图七个展示态里四个没有数据源，不产出。** 加粗句「运单状态从承运商侧获取为准」
   要求 10.3，而 10.3 未交付、集成头无任何承运商轨迹数据源 ⇒
   **已揽收 / 运输中 / 已签收 / 异常不产出任何行**：枚举逐个带 `available` 标志，
   用它们筛选返回空页 + `statusFilterNote` 说明「没有数据源」，
   而**不退化成「今天一票都没发」**。不画 0、不留空、不默认「运输中」。

3. **「已取号」≠「在途中」，且不与蓝图的「异常」混用。**
   `statusSource` 恒标 `INTERNAL_REQUEST`；`issuedWithoutCarrierTracking`
   恒等于已取号数并在汇总里单独成列。
   另加内部态「申请失败」并**刻意不并进「异常」** —— 申请失败是「还没拿到号」，
   承运商异常是「号有了、货在途中出问题」，混成一个红标签会让现场以为票已在路上。

4. **运费是登记不是计算。** 全库 grep `freight|shipping_cost` 只在 V116 命中，
   那是 9.3.3.1 分仓决策的**每单估算快照** —— 既不是本票运单的实际运费，
   也不是同一个事实，故**不借用**。「未登记」是可读状态（NOT_REGISTERED）而不是
   空单元格，与「登记为 0」分开计数；汇总**按币种分组**（跨币种相加属 10.1.2.1，未交付）。

5. **三个批量动作真作用，但写入一律回到唯一写入所有者。** 「申请运单」委派
   10.2.1.2 的 `retry`、「取消」委派其 `cancel`，本模块**不**自己 UPDATE
   `logistics_waybill_request`；逐条把「成功/跳过/失败」分开并说清是哪一种跳过。
   已取号的取消逐条点名 10.2.1.3 并**不代做**。

6. **刻意不引入 8.3.4.1 的跨服务依赖。** 清单行在出号那一刻成立、交运发生在出号之后；
   为高频读列表引入「仓库不可达就整页 503」会让出号成功但仓库宕机时现场连单号都看不到。

## 关键 trade-off

- **投影表 vs 查询时现算**：选现算。多写一张投影表要额外背一份漂移与 sync 语义，
  而本模块要的列 7/8 已经在 V128 里（唯一缺的是运费，已由登记表补齐）。
- **运费凭据用列级授权而非全表授权**：`GRANT UPDATE (is_current)` + 触发器
  `guard_waybill_freight_immutable` 两层。更正是「追加新行 + 撤下旧行」，
  金额永远不可改写；测试**用应用账号与迁移超级账号各试一次**。
- **清单走 `/list` 而非裸路径**：与既有 `sales-orders/list` 同款，
  也让「裸路径 = 后端不存在的映射」这条反例继续成立。

## 文件清单

- `backend/services/trade/src/main/resources/db/migration/V132__trade_waybill_listing.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/WaybillListing{Models,Service,Controller}.java`
- `backend/services/trade/src/test/java/com/starcon/trade/WaybillListingIntegrationTest.java`
- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`（codegen）
- `apps/web/src/components/logistics/waybill-listing-{model.ts,model.test.ts,workspace.tsx}`
- `apps/web/src/app/logistics/shipments/page.tsx`
- `apps/web/src/app/api/trade/waybill-listing-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（外科式最小追加，889 行只改 1 行）
- `docs/product/capability-blueprint/module-progress.md`（仅 10.2.1.1 一行）

## 验证

- `./mvnw -q -o compile` 绿
- `./mvnw -pl services/trade -am test` **486 例全绿**（本模块 24 例）
- `pnpm typecheck` 3 包绿；`pnpm lint` 绿
- `pnpm vitest run apps/web/src/app/api` 32 文件 261 例绿（无兄弟回归）
- 本模块前端 24 例绿
- api-proxy 逐字节验证：去掉新增分支后与原文件完全相同

## 后续待办

- 10.3 交付后，`PICKED_UP/IN_TRANSIT/DELIVERED/EXCEPTION` 四个态可接上；
  `WaybillDisplayStatus` 的 `available` 逐个翻 true，`nextActions` 里
  那个显式写的多 case 分支需要**有意识地**改（不静默走 default）。
- 10.1.2.1 / 10.1.2.2 交付后运费可由费率算出，登记表降级为「渠道回执」来源。
- 8.3.4.2 交付后可补面单重新渲染/补打。
- ⚠️ **既有 flake（非本车道，未改）**：`DropshipTrackingIntegrationTest.建跟踪时两段各自独立_互不预填`
  把 `at(2)`（内部重新读 `now()` 并截断到秒）与服务写入时刻比较，
  跨一秒边界时必差 1s。首次全量跑命中一次，单跑 3/3 绿、全量复跑 486 全绿。
  属 9.6.1.3 的文件，建议该车道改成比较固定夹具时刻。
