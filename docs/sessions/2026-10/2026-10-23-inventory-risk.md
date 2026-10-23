# 1.1.3.1 库存风险（阶段 D7 · L3 锚点 1.1.3 · 卷 01 工作台与门户）

> 会话记录：**决策摘要 + 关键 trade-off + 文件清单 + 后续待办**，不复述过程。

## 决策摘要

落地蓝图卷 01 的「1.1.3.1 库存风险」：集中呈现**缺货 / 超卖风险 / 呆滞 / 超储**四类库存问题，
区分本地仓 / 海外仓 / FBA / 在途，输出带建议动作的风险清单。

卷 01 的定位写死了两件事，本模块全部按它执行：

1. **不产生新的业务事实，只聚合各系统的投影** ⇒ **本模块没有任何迁移、没有任何新表、没有快照**。
   派生值落库只会比它描述的库存更早过期，而过期的那个数恰好是防超卖用的数（与 7.1.1.3 同一纪律）。
2. **跨服务的聚合在前端做** ⇒ 可用量 / 阈值 / 预占 / 在途由 **warehouse** 的只读投影端点提供，
   日均销由 **trade** 已放行的端点提供，**前端合并成风险清单**。两个服务之间没有新增调用、没有跨库 SQL。

### 四类风险的判定规则（阈值全部来自既有表，没有一个是新造的常量）

| 风险 | 判定 | 阈值来源 | 数据来源表 | 分母定义 |
| --- | --- | --- | --- | --- |
| 缺货 | `可用量 < 最低保有量` | `inventory_policy.min_on_hand`，解析顺序 SKU > WAREHOUSE > DEFAULT（V1 文件头）；**未配置则不判定** | `stock_bucket`（Σ `available`） | — |
| 超卖 | `活跃预占未了结量 > 可用量` | **无阈值**（结构性判定） | `reservation_allocation.remaining`（`status ∈ HELD, COMMITTED`）× `inventory_reservation` | 可用量 = Σ `stock_bucket.available`（生成列 `on_hand − reserved`） |
| 呆滞 | `库龄 ≥ 呆滞线` | `inventory_aging_policy.obsolete_days`（逐 SKU 唯一）；未配置回落 7.6.1.1 的 `DEFAULT_OBSOLETE_DAYS` | `stock_bucket` + `inventory_lot.received_on` | — |
| 超储 | `在手量 > 最高保有量` | `inventory_policy.max_on_hand`，SKU > WAREHOUSE > DEFAULT；**未配置则不判定** | `stock_bucket`（Σ `on_hand`） | — |

- **「未配置阈值」与「阈值 = 0」严格分开**：没配 `min_on_hand` 的行**不参与缺货判定**，
  并在界面与模型里标「未配置阈值」。当成 0 会让每个 SKU 都变成缺货。
- **库龄口径与 7.6.1.1 逐字一致**：`coalesce(inventory_lot.received_on, stock_bucket.created_at::date)`，
  **刻意不用 `last_movement_at`**（每天出 10 件的滞销品会被算成库龄 0 天）。
- **在途口径与 7.5.1.2 逐字一致**：在途量 = 仍在途的腿的 `inventory_transfer.quantity` 之和，
  **不是** `quantity − settled_quantity`（既有清单展示的就是 `quantity`，两处口径分叉会让「在途」这一列对不上）。
- **超卖用分配明细而不是预占需求**：`inventory_reservation` 没有仓库维度，`reservation_allocation` 才有；
  用后者才能落到 (仓 × SKU) 粒度。

### 可用天数的除法

蓝图输出句要求「日均销 / 可用天数」，而这两个数**分属两个服务**（日均销在 trade，可用量在 warehouse），
所以除法只能在卷 01 的前端聚合层做。AGENTS.md 禁浮点，于是：

- 数量一律转成**缩放整数**（BigInt，scale 6，与 `numeric(19,6)` 同精度），
  除法在整数上做、**向下取整为整数天** —— `0.3 ÷ 0.1` 得 **3** 而不是浮点的 2。
- 分母语义写死：**日均销登记为 0**（登记事实：本 SKU 无消耗）或**未登记销售速度**，
  可用天数都是**不可判定（null）**，既不是 0 也不是无穷大。三种状态在模型里是
  `OK / ZERO_VELOCITY / NO_VELOCITY` 三个值，界面上分别写清原因。

## 关键 trade-off

- **为什么不复用 6.2.1.1（trade 的断货风险）而要新开一条 warehouse 投影**：
  6.2.1.1 是**快照式**的（要 POST 触发一次计算才落库）、粒度是**逐 SKU**、只管**缺货**一类；
  本模块要的是**逐 (仓 × SKU)** 的四类风险的**实时只读**视图。
  复用它就等于在工作台点一下触发一次写动作 —— 与「工作台只读 + 跳转」直接冲突。
- **为什么不把四类判定放在 warehouse 后端**：跨服务聚合要在前端做（卷 01 的定位）。
  后端只提供**事实与阈值**（含命中档位），判定与合并在前端模型里 —— 这样后端不会替别的服务说话。
- **日均销为什么不新增 trade 端点**：直接读**已放行**的 `GET /trade/stockout-risk/velocities`
  （api-proxy 白名单里本来就有），前端按生效期语义（`[effective_from, effective_to)` 覆盖评估日）
  挑出当前生效的那条。**「没登记」不在结果里** —— 那是「没登记」，不是「日均销 0」。
  这也让本车道**不必**在 api-proxy 里新开 trade 分支（锚点只有一处，避免与同批车道撞同一段 alternation）。
- **采购到货为什么没直接用 trade 的 `purchase_arrival_tracking`**：那张表以**采购单**为粒度、
  **没有 SKU 维度**，支撑不了逐 SKU 的风险清单。逐 SKU 的采购在途改用 warehouse 自己的
  `inventory_transfer` 的 `PURCHASE` 腿（同为在途、同有 SKU、同一张事实表）。
  这条在 `capabilities` 里如实报 `available=false` + `ownerModule=6.3.2.2/trade` + 影响面。
- **api-proxy 怎么插入**：本车道**新增一条独立的 `services[]` 元素**（`prefix: 'warehouse/'`），
  插在既有那条 `warehouse/` 元素**之前**。代理的判别是「`startsWith(prefix)` 收窄 → 再测正则」，
  所以同一服务可以有多条元素；两条的 `env` / `fallback` 完全一致，路由到同一个 warehouse 服务。
  既有那条巨型 alternation **一字未动、也不重排**。
- **页面入口**：台账里本模块的 L3 锚点路由是「—」。按仓内既有惯例，「—」锚点的模块做成
  **真实可访问的页面**，入口放在对应业务区 —— 本模块挂在 `/dashboard/inventory-risk`。
  **没有**去改 `apps/web/src/app/page.tsx` 首页（那是人工与别的模块共用的）。

## 文件清单

### 后端（warehouse 服务，只读投影，无迁移）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryRiskProjectionModels.java`（新）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryRiskProjectionService.java`（新）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryRiskProjectionController.java`（新）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryRiskTenancyIntegrationTest.java`（新，5 例）

端点：`GET /warehouse/inventory-risk/capabilities`、`GET /warehouse/inventory-risk/projection`
（权限码 `warehouse.inventory.read`，与 7.1.1.2 / 7.1.1.3 同源同权）。

### 前端
- `apps/web/src/components/dashboard/inventory-risk-model.ts`（新：判定 + 精确十进制除法 + 建议动作 + 汇总）
- `apps/web/src/components/dashboard/inventory-risk-model.test.ts`（新，27 例）
- `apps/web/src/components/dashboard/inventory-risk-workspace.tsx`（新：真实工作区）
- `apps/web/src/app/dashboard/inventory-risk/page.tsx`（新：路由）
- `apps/web/src/app/api/warehouse/inventory-risk-allowlist.test.ts`（新：白名单双向断言）
- `apps/web/src/lib/api-proxy.ts`（**只新增一条独立的 `services[]` 元素**，既有条目一字未动）

### 契约与台账
- `packages/api-contracts/src/contracts/warehouse.ts`、`packages/api-contracts/src/warehouse.openapi.json`
  （codegen 重生成产物，新增 `RiskProjectionRow` / `RiskProjectionPage` / `RiskProjectionCapabilities`）
- `docs/product/capability-blueprint/module-progress.md`（只改本模块那一行的「状态」「证据」两格）

## 后续待办

- **1.1.3.2 资金与回款风险**仍未交付：它的上游是 12 财务，而该服务在本仓不存在，永远排不出车道。
  本车道**没有**替兄弟模块实现任何东西。
- **下游跳转尚未接线**：蓝图的下游是「06 采购单创建 / 07 库存调拨 / 07 补货建议」，
  本模块的「建议动作」是**文案 + 依据**，不写业务数据（卷 01 只读）。等 06/07 的入口页
  稳定后，可把建议动作升级为深链（届时仍只做跳转，不在本页写数据）。
- **`inventory_policy` 的 CATEGORY 档未参与解析**：`stock_bucket` 没有类目维度，
  服务端解析只走 SKU > WAREHOUSE > DEFAULT；类目档要等 catalog 的类目树与映射就位后再补。
- **「占用资金」在呆滞风险里缺席**：warehouse 的库存事实层没有任何成本列（已 grep 全仓确认），
  成本口径属 12 财务，跨服务禁止读对方表。本模块**不编一个成本价** ——
  假成本比缺成本危险得多（一个猜出来的金额会被当真去定清仓价）。
