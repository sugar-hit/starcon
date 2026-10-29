# 2026-10-29: 13.5.1.1 库存分析

## 摘要

卷 13「利润与经营分析」L3 13.5.1 库存分析下的 **13.5.1.1 库存分析**落地：
从 **7 库存**（warehouse）与 **9 订单**（trade）的**授权只读 API**拉取事实，
落成本服务的**分析投影**，算出**库存金额结构、周转率、库龄分布、缺货与超储、ABC 分类、呆滞占比**，
按**仓库 / 类目 / 店铺 / SKU** 拆解，并给出**下钻**与**盘点历史**。

蓝图原文：「**职责**：库存分析报表：库存金额结构、周转率、库龄分布、缺货与超储、ABC 分类、呆滞占比，
按仓库/类目/店铺拆解。**输入**：库存数据（7）；销售数据；盘点历史。**输出**：库存分析报表 + 图表 + 下钻。
**关系**：上游 = 7 库存、9 订单；下游 = 6 采购、5 调价。
**周转率必须按成本口径算**，用售价算会虚高——这是库存报表最常见的错误。」

车道：`feat/13-5-1-1`（worktree `.worktrees/13.5.1.1`），基线 `integration/module-dev`（3e83aa41）。
Flyway 版本号：**V403**（调度者预分配）。

## 决策 / 结论

### 1. 🔴 库存余额的事实归 warehouse，本模块只做**分析投影**

库存账本（`inventory_balance` / `stock_bucket`）归**卷 7 库存**所有。
再建一张「库存余额表」就会出现**第二个真相**：上游改一次库存，两个表对不上，
而报表通常比账本更被人当真 ⇒ 那就是假账。

⇒ 本模块只建**四张分析投影表 + 一张阈值规则表**，
数据**全部**由 reporting 服务经**授权只读 API**拉取后落地
（warehouse 库存总览 / 库龄与呆滞 / 盘点单，catalog SKU 档案与成本构成，
channel 平台商品，trade 销售订单），
**不直连对方库、不建跨库 FK 或跨库视图**（AGENTS.md「模块边界纪律」）。

**取舍**：也可以给「库存头寸」建一张独立账，用事件增量维护。
但那样本模块就有了第二个写入所有者，而库存余额的真值在 warehouse ⇒ 放弃。

### 2. 🔴 周转率按**成本口径**算，且**分子分母用同一套成本单价**

蓝图那句加粗是本模块的命门。用售价算会虚高（毛利率越高越虚高）。

| 口径 | 分子 | 分母 | 结论 |
| --- | --- | --- | --- |
| ✅ **成本口径** | 销售出库**成本** = 销量 × catalog 单位成本 | 库存**金额**（成本口径） | 本模块采用 |
| ❌ 售价口径 | 销售出库**金额**（售价） | 库存**金额**（成本口径） | **会虚高** |

**分子与分母必须用同一套成本单价**（移动加权 vs 标准成本混用 ⇒ 周转率没有意义）。

**取舍**：成本单价有两个来源 —— catalog 的**移动加权**（`cost_method`）与**标准成本**。
本模块**不选**，按上游给的 `cost_method` 原样搬运并在响应里写明用的是哪一种 ⇒
口径唯一性归 catalog，本模块不重算。

### 3. 🔴 「算不出来」与「等于 0」必须分开（DB 层就挡住半吊子状态）

下列情形一律是 **NULL + 一句原因**，**绝不是 0**：

* 没有维护成本单价 ⇒ `unit_cost` / `inventory_value` **皆 NULL**（**不是 0 元**）；
* 分母为 0（窗口里没有库存金额）⇒ 周转率 **NULL**（**不是 0 次**）；
* 没有期初快照 ⇒ 退回**期末口径**并在 `caliber` / `note` 里写明；
* 账面行数为 0 的盘点单 ⇒ 准确率 **NULL**（**不是 0%**）。

⇒ 迁移里用 **CHECK** 把这件事钉在数据层：
金额要么算得出来（单价与金额都非空、币种合法、`金额 = round(数量 × 单价, 4)`），
要么**三者皆 NULL** —— **不允许出现「有金额但没单价」这种半吊子状态**。

### 4. 🔴 **按仓库拆解的周转率不可比** —— 如实报出来，不摊

销售订单**不带仓库维度**（trade 的 `shippingWarehouse` 字段**恒为 null**），
而 warehouse 的出库实发量（`shippedQuantity`）在 8.3.2.1 / 8.3.4.1 交付前**拿不到**
（`pickedQuantity` 恒为 null，只有计划量）。
把一笔销售成本按某种规则摊给某个仓就是**编造**。

⇒ WAREHOUSE 维度的周转率返回 **NULL + 明确原因**，并在 `capabilities.notDelivered` 里写清。
类目 / 店铺 / SKU / 全租户四个维度**精确可算**（SKU 归属唯一 ⇒ 归属无歧义）。

### 5. 🔴 **先汇总再相除**

周转率 = **Σ 销售出库成本 ÷ Σ 平均库存金额**，
**不是**对逐 SKU 的周转率求平均 —— 后者会让小 SKU 的极端值主导整个数字。
ABC 的占比同理：分母是该币种的全量合计，不是逐 SKU 占比的平均。

### 6. 🔴 金额「算不出来」的 SKU **不进任何合计**

没有维护成本的 SKU 在**结构表 / ABC / 合计**里都不出现，
而是由响应级的 `costMissingSkuCount` / `costMissingQuantity` **如实报出数量**。
否则用户会把「已知部分合计」当成全量金额。

**取舍**：也可以让它们进表（金额为 NULL）。但那样每一行都会挂一个「币种未知」的分组，
表格会被无意义的空行污染 —— 选了「不进表 + 单独报数」。

### 7. 🔴 缺货超储：**计数按维度、金额按币种**

缺货 / 超储的 SKU 数与件数是**与币种无关**的事实（按币种拆开会让
「这个仓有几个 SKU 断货」这个问题答不出来）；
而金额**必须**按币种拆开（**绝不跨币种相加**）。
⇒ `InventoryStockStatusRow.amounts` 是**一层一个币种**的清单。

### 8. 🔴 库龄与呆滞**只搬运，不重算**

库龄判定（批次收货日 vs 建档日代理值）、滞销线 / 呆滞线阈值策略、
「呆滞及以上的可用量」全部归 **warehouse 7.6.1.1**。
本模块把它的判定结果落成投影，**不重算** —— 重算就会与 warehouse 的处置清单对不上。

⚠️ **库龄与呆滞只有量口径**：warehouse 的库存事实层**没有任何成本列**
（它自己的 `AgingCapitalTie` 就如实写着「资金占用估算不可用」）⇒ 本页的库龄/呆滞占比**只报量**。

### 9. 🔴 ABC 切在**库存金额**上，且用「累计到本件之后」的占比

库存分析要回答的是「哪些 SKU 占了大部分资金」⇒ 切分依据是**金额**（不是销量、不是 SKU 数）。
口径：把 SKU 按金额降序排，**累计到本件之后**的占比 ≤ `aCumPct` 归 A 类、≤ `bCumPct` 归 B 类，其余 C 类
（跨过阈值的那一件落在它**跨入**的那一类，不降级）。

**分类必须用未取整的累计值** —— 拿展示用的 4 位小数去判档会在 80% / 95% 边界上判错一件 SKU。

### 10. 🔴 阈值**不写死**：ABC 累计占比线与周转目标落在租户自己的规则表

不同品类、不同店铺的库存结构差一个数量级，写死的 80/95 对谁都不对。
非法值（`aCumPct ≥ bCumPct`）由 DB 的 **CHECK** 在数据库层挡住。

### 11. 投影表的设计取舍

| 表 | 粒度 | 为什么这样切 |
| --- | --- | --- |
| `reporting_inventory_analysis_position` | as_of × SKU × 仓库 | 库存是**时点**事实，一天一张快照；有了历史快照才谈得上「平均库存」 |
| `reporting_inventory_analysis_sales_fact` | 售出日 × SKU × 上游行 | 周转率的分子；**刻意不落售价**（一张存着售价的表迟早会被拿来算一次周转率） |
| `reporting_inventory_analysis_aging_fact` | as_of × 范围 × 指标 | 库龄桶与呆滞汇总**粒度不同**，用判别列而不是拆两张表 |
| `reporting_inventory_analysis_stocktake_fact` | 盘点单 | 盘点是**独立事实**，与头寸不同源 |

**取舍**：position 与 snapshot 分成两张表 ⇒ 同一份库存数据有两个落点（第二个真相）。
本模块**合成一张**（`as_of` 进唯一键），当前头寸 = `max(as_of)`，历史 = 全部快照。

### 12. 销售事实**带维度快照**，为的是不丢分子

`reporting_inventory_analysis_sales_fact` 带 `category_code` / `shop_code`。
若不带，查询期就要靠 join 头寸表反推维度 —— 而**卖光的 SKU 已经没有头寸行了**，
join 会把那笔成本**整笔丢掉**（分子少一块，周转率偏低还不自知）。

### 13. 销售口径：只算**已提交**（`documentStatus=SUBMITTED`）的订单

草稿单**不是销量**，算进分子就是虚高。
trade 侧**没有履约概念**（`fulfillmentStatus` 恒为 null），所以「已提交」是上游能给的唯一可判定状态。

## 文件清单

**迁移**（1 个）
- `backend/services/reporting/src/main/resources/db/migration/V403__inventory_analysis.sql`

**后端**（4 个，`backend/services/reporting/src/main/java/com/starcon/reporting/`）
- `ReportingInventoryAnalysisModels.java` —— 读模型契约（25 个 DTO）
- `ReportingInventoryAnalysisPort.java` —— 跨服务只读端口（warehouse / catalog / channel / trade）
- `ReportingInventoryAnalysisRefreshService.java` —— 投影摄取（幂等 upsert）
- `ReportingInventoryAnalysisService.java` —— 读模型与口径计算
- `ReportingInventoryAnalysisController.java` —— 11 个端点

**后端测试**（2 个）
- `backend/services/reporting/src/test/java/com/starcon/reporting/InventoryAnalysisIntegrationTest.java`
- `backend/services/reporting/src/test/java/com/starcon/reporting/InventoryAnalysisTenancyIntegrationTest.java`

**前端**（5 个）
- `apps/web/src/components/analytics/inventory-analysis-model.ts`
- `apps/web/src/components/analytics/inventory-analysis-model.test.ts`
- `apps/web/src/components/analytics/inventory-analysis-workspace.tsx`
- `apps/web/src/app/analytics/inventory/analysis/page.tsx` —— 路由 `/analytics/inventory/analysis`
- `apps/web/src/app/api/reporting/inventory-analysis-allowlist.test.ts`

**共享文件**（只加不改）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条** `reporting/`（inventory-analysis）元素，既有条目一字未改
- `packages/api-contracts/src/reporting.openapi.json` + `src/contracts/reporting.ts` —— codegen 产物

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md` —— 只改 13.5.1.1 那一行的「状态」「证据」两格
- 本文件

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` ✅（3 packages successful）
- **L1 模块闸**：
  `./mvnw -pl services/reporting -am test -Dtest='InventoryAnalysis*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  = **19 tests / 0 failures / 0 errors**（16 业务口径 + 3 多租户三条断言）
- **前端**：`pnpm vitest run apps/web/src/components/analytics/inventory-analysis-model.test.ts apps/web/src/app/api/reporting/inventory-analysis-allowlist.test.ts`
  = **25 tests 全绿**
- **全量 allowlist 回归**（改了共享白名单必须跑）：`pnpm vitest run apps/web/src/app/api` = **268 files / 1516 tests 全绿**
- **lint**：`pnpm lint` ✅（只有其它既有文件的 warning，本车道文件零 warning）

## 教训 / 坑

1. **🔴 `timestamptz` 绑定必须写 `?::timestamptz`**。`OffsetDateTime` 直接绑到 `timestamptz` 列会报错；
   绑定对了之后还要**断言写回的值**（本模块断言 `frozen_at` 逐字等于上游的时刻），
   只断言「没抛异常」查不出偏移。
2. **🔴 报表按币种分组时，「没有币种」的那组会变成一个 `currency = null` 的怪分组**。
   本模块的做法：**结构表只收「算得出金额」的行**（`inventory_value is not null`），
   缺失成本的 SKU 由响应级计数单独报出；**缺货超储则反过来**（计数按维度、金额按币种），
   因为计数与币种无关而金额必须按币种拆。
3. **🔴 口径变了要同步改测试的期望值，别改测试去迁就实现**。本轮有三处测试期望写错
   （草稿单窗口外、盘点单排序、ABC 的降序），改的是**期望**不是实现；
   唯一改了实现的是 ABC 的切分口径（从「累计到本件之前」改成「累计到本件之后」= 教科书口径），
   改的时候把**文档口径说明**一起改了。
4. **🔴 别用自己写的正则解析脚本当验证**。第一次写的 allowlist 核对脚本**解析出 0 个元素**，
   却照样打印「✅ 全部 404」—— 空集合让断言变成了空断言。
   真正的验证是仓库自己的 allowlist vitest（**真跑 proxy 断言 HTTP 状态码**）。

## 后续待办

- **周转率按仓库拆解**：等 **8.3.2.1 拣货** / **8.3.4.1 发货交接**交付后，
  warehouse 的 `shippedQuantity` 才有值 ⇒ 那时才能拿到**真实的按仓出库成本**，
  本模块的 WAREHOUSE 维度即可从「不可比」变成「可比」。**不需要改表结构**，只需把销售事实的
  归属从「trade 销售订单」切到「warehouse 出库单」。
- **退货冲减**：周转率的分子目前**没有**扣掉 11 售后的退货。
  等 11 售后交付后可在分子里减去退货成本（口径写在 `notDelivered` 里，已对外可见）。
- **成本单价补全**：本模块的金额覆盖度取决于 **catalog 5.2.1.2** 的成本维护率。
  未维护的 SKU 在页面上显示「—」并单独报数 —— 补全成本后覆盖率自然上升，**不需要改本模块**。
- **6 采购 / 5 调价**（蓝图的直接下游）：本模块只给**依据**
  （缺货与超储清单、ABC 分类、库龄与呆滞），真正的动作在它们那边。
