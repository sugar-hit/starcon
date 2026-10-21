# 2026-10-21: 6.2.1.1 缺货与断货风险 —— 按日均销量、可用库存、在途量、备货周期算出预计断货日

> 车道 `feat/6-2-1-1`，worktree `.worktrees/6.2.1.1`，基线 `1f628c40`（merge(5.2.2.2): 汇率快照）。
> 能力蓝图卷 06「采购与供应」L3 锚点 6.2.1，阶段 D6，L4 模块 6.2.1.1。

## 摘要

本车道交付 6.2.1.1「缺货与断货风险」：按日均销量、可用库存、在途量、备货周期计算
**预计断货日**，按紧急度排序输出断货风险清单。

蓝图输出句点名七项，**一个不能少**：

> 输出：断货风险清单（SKU、可用量、日均销、可支撑天数、预计断货日、缺口量、涉及店铺），
> 按紧急度排序。

七项在代码里有**七个物理落点**（缺一都不算数）：

1. **SKU** —— `stockout_risk_item.sku_id / sku_code / sku_name`（跨服务只存逻辑 ID + 快照）。
2. **可用量** —— `available_quantity`，**直接取 warehouse 7.1.1.3 的可售量（sellable）**，
   本模块不重算（见「口径决策」一节）。
3. **日均销** —— `daily_sales_quantity`，来自本服务自建的销售速度登记。
4. **可支撑天数** —— `coverage_days`，**整数天、向下取整**。
5. **预计断货日** —— `expected_stockout_date` = 评估日 + 可支撑天数。
6. **缺口量** —— `shortage_quantity` = max(0, 日均销 × 备货周期 − 有效可用量 − 备货周期内可到达在途)。
7. **涉及店铺** —— `stockout_risk_item_shop`（一行 = 一个 SKU 涉及的一个店铺，来自 channel 的映射）。

「按紧急度排序」不是排完就完：**分档判据与排序依据原文（`urgency_basis`）随每行一起返回**，
清单页直接显示这行就能复现判定，不必点进去再猜。

## 决策摘要

### 「无数据」是一等状态，绝不渲染成 0

这是本模块最容易搞错、代价也最大的一格。

日均销量缺失时，如果给 0：可支撑天数 = 可用量 ÷ 0 → **无穷大** → 健康度被高估 →
看起来最健康的那个 SKU 其实最危险。所以：

- 没有登记 → 日均销 / 可支撑天数 / 预计断货日一律 **null**，紧急度 `NO_DATA`。
- 登记为 0 → 是**事实**（本 SKU 本期确实没卖），与「没登记」走不同分支，紧急度 `LOW`。

前端 `NO_DATA` 用**中性灰**而不是红色：它不是「危险」，是「答不出来」。
用红色会把它和真断货混在一起，真正紧急的那几条反而被淹掉。

### 有效可用量直接取可售量，本模块不重算

warehouse 7.1.1.3 的原文是「**可售量是全系统的单一口径来源**，不允许各系统各自算」。
本模块若自己实现一遍 `available − 安全缓冲`，那个数必然与防超卖的那个数漂移，
而漂移的方向正好是「看起来比实际多」—— 那正是最坏的漂移方向。

所以 `available_quantity` = warehouse 的 `sellable`，**只有 `sellableBeforeBuffer` 与
`safetyBuffer` 作为拆解项一并落库**（供人复算），判定本身不重做。

### 在途取逐腿明细而不是净额聚合，并排除 FBA_OUTBOUND

warehouse 有两个在途出口：`/in-transit`（逐腿）与 `/in-transit/netting`（按目的仓 × SKU 聚合，
注释里明写是「6.2.1.1 的直接输入」）。本模块取**逐腿**，因为：

- 缺口判定要问「**备货周期内能不能到**」，而净额聚合只给 `latestExpectedArriveAt`，
  分不出「多少量在备货周期内到、多少量更晚才到」。

纳入 `TRANSFER` / `FIRST_MILE` / `PURCHASE` 三类（都是「往我方仓里补货」的路），
**排除 `FBA_OUTBOUND`** —— 那是我方仓发往平台仓，方向相反；把它算成补货会让缺口
被一个**正在离开**的量掩盖，那是重复补货的另一个来源。

**没有 ETA 的在途不计入缺口**：它救不了确定的那一天。

### 销售速度是本服务自建的输入事实（append-only + 生效期版本化）

四个输入里，**日均销量是唯一没有其它服务能给的** —— 13 销量/报表服务在本仓不存在。
所以它必须是一张**可写入、可追溯、版本化**的事实表，而不是从代码常量里取的假数。

- **统计口径冻结**（触发器）：SKU / 日均销量 / 统计窗口 / 生效起点 / 来源一律不可改。
  唯一可动的 `effective_to` **只能设定一次**。
- **append-only**：DELETE 被 REVOKE（第一道闸），触发器再挡一次（第二道闸，
  连表 owner 直连 SQL 也删不掉）。
- **当前版本唯一**：部分唯一索引 `stockout_sales_velocity_open_uq` 钉死
  「同一 SKU 同时只允许一条未收口的登记」⇒「当前日均销」永远只有一个答案。
- **新登记收口旧登记，而不是覆盖**：覆盖会让「当时按哪个数算的断货日」永远查不回来。

### 风险清单落库成快照（而不是纯实时计算视图）

「逐项展开计算过程」「扣减来源可查」「排序依据可见」「涉及店铺」这四件事都要求结果**落得住**。
一次计算 = 一个 snapshot + 若干 item + 十步拆解 + 店铺明细。快照与拆解**只追加**
（REVOKE + 触发器双保险）—— 它是「那一刻算出来的那份结论」的凭据，重算就是新的一份。

### SKU 全集包含「没登记销售速度」的 SKU

备选方案是把没有销售速度登记的 SKU 踢出清单（只留判得出来的）。
**没有采用**：那一档是 `NO_DATA`，它要告诉运营「这个 SKU 答不出来，请去登记销售速度」。
把它踢掉等于让数据缺口隐形 —— 而缺口一旦隐形，就永远没人补。
计算结果里另报「有 N 个 SKU 未登记销售速度」。

### 备货周期由调用方给定，不从供应商评级反推

6.1.1.3 供应商评级未开发；而且评级是**加权分**，与「备货周期多少天」不是同一个量。
在本模块里顺手给一个综合分会让「评级由客观数据算出、人工权重单独标记」那条要求被绕过。
所以备货周期是计算的**显式输入**（`leadTimeDays`，必填）。

### 时钟用数据库的，日期口径统一 UTC

评估基准日取 `(current_timestamp at time zone 'UTC')::date`，计算时刻取 `clock_timestamp()`，
不用 Java 的 `Instant.now()`。所有日期口径统一按 UTC ——
「评估日」与「ETA 落在哪一天」各按各的时区算，差一天就足以把「今天断货」说成「明天断货」。

`as_of_date`（评估基准日，可回溯）与 `created_at`（落库时刻）**分开存**：
混成一个会让回溯算出来的历史结论看起来像是当时算的。

## 关键 trade-off

| 决策点 | 备选 | 采用 | 代价 |
| --- | --- | --- | --- |
| 可用量口径 | 本模块自算 `available − buffer` | 直接取 warehouse 7.1.1.3 的 `sellable` | 依赖上游口径；上游改了本模块跟着改（这正是「单一口径来源」的含义） |
| 在途数据源 | `/in-transit/netting` 聚合 | `/in-transit` 逐腿 | 调用方要自己聚合；换来的是「周期内能不能到」可判 |
| 在途类型 | 含 `FBA_OUTBOUND` | 排除 `FBA_OUTBOUND` | FBA 仓的补货要等 4 外部仓那条线 |
| 无速度登记的 SKU | 踢出清单 | 留在清单（NO_DATA 档） | 清单变长；换来数据缺口可见 |
| 结论载体 | 实时计算视图 | 快照落库 | 占存储；换��结论可复算、可回溯 |
| 权限码 | 新增 `trade.demand.*` | 沿用 `trade.stockout.read/update` | 不动 identity 的权限真相源（那是别的服务的迁移版本号） |
| 头程在途归属 | 按任务书走 channel | 按事实走 warehouse | 与任务书表述不符，已在台账与会话日志里如实记录 |

## 与任务书的偏差（如实记录）

任务书写「10 头程主责 channel」，但 **10.4.1.1 / 10.4.1.2 实际交付在 warehouse**
（V80 `first_mile_shipment` / V84 `first_mile_tracking`），不是 channel。
本模块按事实走 warehouse 的在途腿（`transitKind=FIRST_MILE`），并在 `capabilities` 里
如实报出每个输入的真实归属，不假装 channel 有这个能力。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V234__stockout_risk.sql` ——
  五张新表、触发器、索引、RLS + GRANT。**零 ALTER 既有表、零跨库 FK**。
- `backend/services/trade/src/main/java/com/starcon/trade/StockoutRiskModels.java` —— 传输模型。
- `backend/services/trade/src/main/java/com/starcon/trade/StockoutInventoryPort.java` ——
  warehouse 端口（可用量 + 在途），不可达一律 503，**绝不返回空集合冒充「零库存」**。
- `backend/services/trade/src/main/java/com/starcon/trade/StockoutChannelPort.java` ——
  channel 端口（SKU → 店铺），逐 SKU 查。
- `backend/services/trade/src/main/java/com/starcon/trade/StockoutRiskService.java` ——
  计算 + 落库 + 查询（jOOQ 显式 SQL）。
- `backend/services/trade/src/main/java/com/starcon/trade/StockoutRiskController.java` ——
  7 个端点，命名空间 `/trade/stockout-risk`。
- `backend/services/trade/src/test/java/com/starcon/trade/StockoutRiskIntegrationTest.java` —— 15 例。
- `backend/services/trade/src/test/java/com/starcon/trade/StockoutRiskTenancyIntegrationTest.java` —— 4 例（三断言）。

**前端**

- `apps/web/src/components/purchase/stockout-risk-model.ts` + `.test.ts`
- `apps/web/src/components/purchase/stockout-risk-workspace.tsx`
- `apps/web/src/app/purchase/demand/page.tsx`
- `apps/web/src/app/api/trade/stockout-risk-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（30 行新增、0 行删除）。

**契约 / 台账**

- `packages/api-contracts/src/contracts/trade.ts` —— codegen 产物，纯新增 13 个 `Stockout*` type。
- `packages/api-contracts/src/trade.openapi.json` —— codegen 产物。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.2.1.1 一行的「状态」「证据」两格。

## 验证

- L0：`cd backend && ./mvnw -q compile` 全绿；`pnpm typecheck` 全绿；
  `pnpm --filter @starcon/web lint` 本模块无告警。
- L1：`./mvnw -pl services/trade -am test -Dtest='StockoutRisk*'` —— **19 例 0 失败**。
- 前端：`pnpm vitest run`（本模块两个文件）—— **16 例 0 失败**；
  **全量 allowlist 回归 `pnpm vitest run apps/web/src/app/api/trade` —— 46 文件 243 例全绿**。
- api-proxy 自检：`grep -c "prefix: '"` = **111**（基线 110 + 本车道 1），
  `git diff` **30 行新增、0 行删除**。

## 后续待办

- **6.2.1.2 补货建议 / 6.2.1.3 采购计划**：本模块只输出缺口量与紧急度，**不代它们下单**；
  它们接入后应当消费本模块的快照，而不是自己再算一遍缺口（那会与防超卖的可售量口径漂移）。
- **6.3.1.x 采购单**：未交付 ⇒ 在途只落来源单据号（`sourceReference`），不建跨库外键；
  采购单交付后应改为逻辑引用并补齐采购在途的 ETA。
- **6.1.1.3 供应商评级**：未开发 ⇒ 备货周期由调用方给定。
- **6.4.1.1 加工单**（已交付）在本模块的补货口径里**未纳入**：加工中的组件不是「在途」也不是
  「可用」，将来要决定它算不算缺口的一部分。
- **headroom**：`stockout_sales_velocity` 目前只有页面人工登记入口；
  批量导入（`source_kind=IMPORT`）与外部同步（`EXTERNAL_SYNC`）两个来源枚举已留好，
  但**尚无写入端点** —— 13 销量服务交付后应优先接它，而不是继续人工登记。
- **契约漂移**：`packages/api-contracts/src/trade.openapi.json` 此前从未吸收
  9.2.x address-payment-check 那批端点，本次 codegen 顺带修正了它 ——
  那是集成头既有的漂移，与本车道无关，但值得记一笔。
