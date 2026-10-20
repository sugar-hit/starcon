# 2026-10-21: 6.2.1.2 补货建议 —— 按覆盖天数算建议补货量，结合 MOQ / 箱规 / 起订额取整，给出供应商与到货日

> 车道 `feat/6-2-1-2`，worktree `.worktrees/6.2.1.2`，基线 `7eef1875`（merge(6.3.2.2): 到货与逾期）。
> 能力蓝图卷 06「采购与供应」L3 锚点 6.2.1，阶段 D6，L4 模块 6.2.1.2。

## 摘要

本车道交付 6.2.1.2「补货建议」：按 **目标库存覆盖天数 × 日均销量 −（可用 + 在途）** 算出
净需求量，再结合 **MOQ / 箱规 / 采购起订额** 做取整修正，给出**建议供应商**与**预计到货日**，
输出补货建议清单并支持**接受 / 调整 / 忽略**三种处置（处置人 + 处置时间 + 处置说明全部真落库）。

蓝图输出句点名的四项，在代码里各有物理落点：

1. **建议量** —— `replenishment_suggestion.suggested_quantity`（基础单位）
   与 `suggested_purchase_quantity`（采购单位）**两个都存**，口径不靠前端折算。
2. **取整说明** —— `rounding_note`（一句话说清「整箱 / MOQ / 起订额」各抬了多少），
   逐项拆解落在 `replenishment_suggestion_step`。
3. **预计到货日** —— `expected_arrival_date` + `arrival_basis`（**口径随值一起返回**）。
4. **预计费用** —— `estimated_cost` = 采购单位数 × 适用阶梯单价。

「**必须展示计算过程**」不是靠前端现算，而是靠 `replenishment_suggestion_step` 把每一步的
数量与依据原文**落库**，前端原样展示 —— 前端不重算后端算出的任何中间量。

## 决策摘要

### 六张表全新建，零 ALTER 既有表

`V238__replenishment_suggestion.sql` 建六张表：两张配置表（覆盖天数 / 采购起订额与生产周期）

- 一张批次表 + 一张建议表 + 一张过程拆解表 + 一张处置留痕表。

**零 ALTER 既有表、零跨库 FK、零跨库视图**。上一批有车道给既有表建 FK，打破了同服务既有测试的
TRUNCATE（L2 直接红）；本迁移对既有测试零回归面。

### 输入全部来自已交付的真实事实，跨服务只经授权 HTTP API 读

| 输入                           | 来源                                                   | 说明                                                  |
| ------------------------------ | ------------------------------------------------------ | ----------------------------------------------------- |
| 日均销量                       | 6.2.1.1 `stockout_sales_velocity`                      | **只 SELECT**，不写别人的表                           |
| 可用量 / 在途                  | 6.2.1.1 `StockoutInventoryPort`                        | warehouse 7.1.1.3 可售量 + 7.5.1.2 逐腿在途（含 ETA） |
| MOQ / 阶梯价 / 交期 / 采购单位 | catalog 3.6.2 `GET /catalog/supplier-quotes/effective` | **刻意不传 supplierId**，一次拿全部候选再择优         |
| 箱规                           | catalog `sku_unit_conversion`                          | role ∈ PURCHASE / PACKAGING，`quantity_per_base`      |

### 单位纪律：基础单位 vs 采购单位，两套不能混

这是本模块最容易算错的地方，一次算错**全绿**（因为结果只是一个数字）。

- 日均销量 / 可用 / 在途 / 目标库存 / 净需求 / 建议量 → **基础单位**
- MOQ 与阶梯价 → **采购单位**（catalog 的 `applicableTier` 把数量与 `minQuantity`/`moqQuantity` 同单位比）
- 箱规 = `quantity_per_base` = 1 采购单位含多少基础单位
- 预计费用 = 采购单位数 × 适用阶梯单价

取整三步都在**采购单位**里做（整箱 → MOQ → 起订额），最后一步才折回基础单位。

### 供应商择优是「可解释顺序」，不是评分

交期短 → 起订门槛低（折算基础单位）→ 同币种内单价低 → 供应商名。

- **6.1.1.3 供应商评级未交付 ⇒ 不按评级择优**（不编一个假评分出来排序）。
- **跨币种绝不比价**：两家报价币种不同就各报各的，不用一个编出来的汇率折算。
  起订额也只在**币种与报价一致且取得到适用阶梯价**时才参与取整。

### 10 头程时效未交付 ⇒ 到货日口径写明，不用编

warehouse 只有逐票 ETA（`first_mile_shipment` / `first_mile_tracking`），**没有可用的时效基准**。
所以**预计到货日 = 评估日 + 供应商交期 + 生产周期**，并在 `arrival_basis` 与 `capabilities`
里显式写明「10 头程时效未交付」—— 到货日是一个**已知不含头程段**的估计，不假装是全链路。

### 五种建议类别互不折叠，只有一种能处置

`REPLENISH`（建议补货）/ `HOLD`（库存已覆盖目标，无需补）/ `NO_SALES_DATA`（没有销售速度登记）/
`NO_QUOTE`（没有有效报价）/ `NO_BOX_SPEC`（catalog 未登记箱规换算）。

- **只有 REPLENISH 可处置**，其余 409 并写明「这条建议是 xxx，不是可处置的建议」。
- **箱规量不到就不猜成 1** —— 猜成 1 算出来的建议量是错的，而且看不出来。
- **null = 答不出来，绝不写 0**：没有销售速度时目标库存与建议量都是 null。

### 计算结论由 DB 触发器冻结，处置留痕 append-only

`guard_replenishment_suggestion_frozen` 让建议行的计算结论落库后不可改写（只允许动处置相关列），
`guard_replenishment_append_only` 让批次 / 过程拆解 / 处置留痕三张表只追加。
「重算」是一次**新的批次**，不是对已落库建议的改写。

批次表**不存 `decided_count` 列**（append-only 表不适合存派生量），改为读时 count 子查询。

### 覆盖天数配置版本化：新配置收口旧配置

优先级：**SKU 配置 > 分类配置 > 租户默认配置 > 计算时传入的兜底值**，来源与来源说明逐行返回。
配置不是「改一行」，而是**新登记收口旧登记** —— 「3 月 1 日那天用的覆盖天数是多少」必须答得上来。

### 采购起订额与生产周期放在 trade 自有的表里

catalog 的报价里**没有「起订额」这个事实**（`min_order_amount` 之类不在 `supplier_quote` 上）。
所以这两项放在 trade 自有的 `replenishment_order_policy`，按供应商 / 供应商角色 / 租户默认配置。
不为了一个字段去改别的服务的表。

## 关键 trade-off / 讨论

### 为什么建 6 张新表而不是复用 6.2.1.1 的快照表

6.2.1.1 的快照是**风险视角**（可支撑天数 / 预计断货日），本模块是**补货视角**（目标库存 / 净需求 /
取整后建议量 / 供应商 / 到货日 / 预计费用）。两者的生命周期、配置维度、处置状态都不同。
硬塞进同一张表会让 6.2.1.1 的 append-only 快照带上本模块的可变字段，两边一起坏。
**两张表互不依赖对方的结构**，只共享「日均销量 / 可用 / 在途」这三个读口径。

### 为什么不用 `Draft` record 而是具名字段类

`Draft` 承载 40+ 个字段。用位置参数 record 的话，**少传一个参数就会把字段整体错位** ——
而这类 bug 会全绿（结果仍是一个合法数字）。改成具名字段类后，编译期就能挡住漏传。

## 涉及文件

**新增**

- `backend/services/trade/src/main/resources/db/migration/V238__replenishment_suggestion.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/ReplenishmentSuggestionModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReplenishmentSuggestionRules.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReplenishmentCatalogPort.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReplenishmentSuggestionService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReplenishmentSuggestionController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ReplenishmentSuggestionCalculationTest.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ReplenishmentSuggestionIntegrationTest.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ReplenishmentSuggestionTenancyIntegrationTest.java`
- `apps/web/src/components/purchase/replenishment-suggestion-model.ts`
- `apps/web/src/components/purchase/replenishment-suggestion-workspace.tsx`
- `apps/web/src/components/purchase/replenishment-suggestion-model.test.ts`
- `apps/web/src/app/purchase/demand/replenishment/page.tsx`
- `apps/web/src/app/api/trade/replenishment-suggestion-allowlist.test.ts`

**修改**

- `apps/web/src/lib/api-proxy.ts`（**只新增一条独立 `services[]` 元素**，既有条目一字未动）
- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`
  （codegen 产物；`catalog.ts` / `catalog.openapi.json` 的 diff 是集成头既有的契约漂移）
- `docs/product/capability-blueprint/module-progress.md`（只改 6.2.1.2 一行的状态与证据两格）

## 验证

- **L0**：`./mvnw -q compile` exit 0；`pnpm typecheck` 3/3 通过。
- **L1**：`./mvnw -pl services/trade -am test -Dtest='ReplenishmentSuggestion*'` →
  **45 例 0 失败 0 错误 BUILD SUCCESS**（计算 13 + 集成 28 + 租户 4），Flyway 实跑到 v238。
- **前端**：本模块 2 个测试文件 **19 例全绿**；`pnpm vitest run apps/web/src/app/api/trade`
  **48 文件 254 例全绿**。
- **多租户三断言**（`ReplenishmentSuggestionTenancyIntegrationTest`）：两租户互不可见 /
  伪造 tenant header 无效 / 连接池归还后不串租户；另加**六张表 RLS ENABLE + FORCE 逐表断言**。

## 后续待办

- **10 头程时效基准**（10.4.1.1 / 10.4.1.2）落地后，`expected_arrival_date` 应加上头程段，
  并把 `arrival_basis` 的口径文案改掉 —— 本模块的到货日目前**已知不含头程**。
- **6.1.1.3 供应商评级**落地后，可在供应商择优里增加「同分时按评级」这一档；
  当前顺序刻意不含评级。
- **6.2.1.3 采购计划 / 6.2.2.1 采购申请**落地后，本模块的处置结果可被消费成单据。
  本车道**刻意不代它们开 api-proxy 形状**。

## 关联

- 能力蓝图：`docs/product/capability-blueprint/06-purchase-and-supply.md`（L3 锚点 6.2.1）
- 兄弟模块：6.2.1.1 缺货与断货风险（`docs/sessions/2026-10/2026-10-21-stockout-risk.md`）、
  6.3.2.2 到货与逾期
- 多租户：`docs/decisions/0002-multi-tenancy-strategy.md`、`docs/architecture/multi-tenancy.md`
