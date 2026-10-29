# 2026-10-29: 14.3.1.3 商品维度报表

## 摘要

卷 14「广告与增长」L3 14.3.1 效果分析下的 **14.3.1.3 商品维度报表**落地：
在 **SKU / SPU / Listing** 三个维度上给出广告花费、广告销售额、总销售额、
ACOS、TACOS、广告占比利润，并回答蓝图点名的那一个问题 ——
**广告销售占比（判断广告是带来增量还是抢自然单）**。

蓝图原文：「**职责**：商品维度广告效果：SKU/SPU/Listing 维度的广告花费、广告销售额、
总销售额、ACOS、TACOS、广告占比利润。**输入**：商品维度；时间范围；利润数据（13）。
**输出**：商品广告报表 + 广告销售占比 + 广告后净利。**关系**：上游 = 14.3.1.1、13.4.1.2；
下游 = 13.4 利润。**广告销售占比（广告单/总单）是判断广告是否带来增量还是抢自然单的关键**，
只看广告 ROAS 无法回答这个问题。」

车道：`feat/14-3-1-3`（worktree `.worktrees/14.3.1.3`），基线 `integration/module-dev`（3e83aa41）。

## 决策 / 结论

### 1. 🔴 落 channel 服务，不落 reporting

蓝图卷 14 的主责服务是 `channel + reporting`，**列表第一项是 channel**；
兄弟模块 14.3.1.1（活动报表）/ 14.3.1.2（广告组与词报表）都在 channel；
广告事实（`ad_campaign_daily_metric`）本来就由 channel 自己拥有。

⇒ 本模块落 **channel**，利润侧跨服务只经**授权只读 API**（`AdProductProfitPort` →
`GET /reporting/sku-profit/report`），商品编码 / SPU 归属同理走 catalog
（`AdProductCatalogPort`）。**零跨库 SQL / 跨库 FK / 共享视图。**
channel 里已有 `AdDaypartSourcePort` → reporting 的同类先例。

### 2. 🔴 商品维度的数字是**分摊出来的** —— 规则必须固定并公示

广告事实只到**活动**级，「投的是哪个商品」来自 14.1.1.2 的投放关系
（`ad_campaign_group_product`）⇒ 商品维度**必然**是分摊结果。

固定两级等分规则（原文随报表 / CSV 表头 / 导出留痕一起返回）：

| 级别 | 规则 | 归因标记 |
| --- | --- | --- |
| 活动 → Listing | 按该活动**当前**登记的投放商品数等分 | 投 1 个 = `DIRECT`（平台原值）；N>1 = `ALLOCATED_EVENLY`；混合 = `MIXED` |
| Listing → 内部 SKU | 按该 listing **当前**登记的 SKU 变体数等分 | 同上三态 |
| SPU | 名下**在报表内**的 SKU **相加** | 不再分摊一次 |

**取舍**：另一条路是只做「活动恰好只投一个商品」的 DIRECT 口径。
那样每个数字都是平台原值、绝不含糊，但**只覆盖一部分投放**，
运营要看的「多商品活动里哪个商品更值得投」就答不了。
⇒ 选**等分 + 公示 + 标记**，并把每行的归因方式单独显示 ——
把分摊值当平台原值看，会做出完全错误的投放决策。

### 3. 🔴 归不到商品的花费**单列**，不摊给任何商品，也不摊成 0

没登记投放商品的活动，它的花费与销售额**归不到任何商品**。
汇总里 `unattributedSpend` / `unattributedSales` 单列
（= 范围内总额 − 行合计），摊成 0 就是说「这些广告没花钱」。

### 4. 🔴 利润归属：**LISTING 维度常常归不到唯一 listing**

利润事实只到 SKU 粒度 ⇒ SKU / SPU 维度精确；
**LISTING 维度只有「挂 1 个 SKU 且该 SKU 只挂 1 个 listing」才有利润**，
否则该行利润列为空并写明原因，**不摊给任何一个 listing**（照抄 13.2.2.2 纪律）。

### 5. 🔴 TACOS / 广告销售占比的分子必须与利润侧**同范围**

拿「全量广告花费」除以「部分商品的总销售额」算出来的 TACOS 没有意义。
⇒ 汇总另给 `scopedSpend` / `scopedSales`（与利润侧同一范围的那一段花费与销售额），
并把「同一范围」是什么意思写进 `scopedScopeNote` 随报表返回。

### 6. 🔴 口径全部**先汇总再相除**

ACOS / ROAS / TACOS / 广告销售占比 / 广告后净利 / 广告占比利润一律 `Σ分子 / Σ分母`，
**不是各行比率的平均**（后者是数学错误：两条 ACOS 100% 的商品整体不是 50%）。
分母为 0 一律 `null`（不可比），**不是 0**。

### 7. 🔴 上游读不到 = **降级 + 明确原因**（不是 503）

与 `AdDaypartSourcePort` 的 503 不同：广告侧永远可用，
利润侧 / 编码侧为空并带 `profitUnavailableReason` / `catalogUnavailableReason`，
页面单独一区显示。候选集被截断也**如实报**（`candidateTruncated` + 说明），
不静默。

### 8. 不提供粒度参数、不提供趋势端点

蓝图 14.3.1.3 的输入句没有「按日/周/月」，输出句没有趋势图
⇒ 行 = 一个商品 × 一个币种，整个时间窗口汇总；**没有 trend 端点**
（白名单里刻意不放行 `ad-product-reports/trend`）。

### 9. 修掉一处契约不一致：`roas` 有定义却没有值

`METRIC_DEFINITIONS` 里声明了 `roas`（`available=true`），但行 / 汇总 DTO 里没有该字段，
而 CSV 表头**又已经有 ROAS 列** ⇒ 口径定义、DTO、CSV 三者对不齐。
⇒ 给行与汇总补 `roas`（`Σ广告销售额 / Σ广告花费`，分母为 0 ⇒ `null`），
CSV 改用同一个字段，不再就地另算。

### 10. 归一化 BigDecimal 的两种序列化

后端是 `BigDecimal`：Jackson 运行时序列化成 JSON **number**，
OpenAPI 契约里标注成 `string`（避免契约消费方按 JS number 丢精度）。
⇒ 前端类型写成 `number | string | null`，展示辅助统一用 `toNumber()` 转换，
**非数字一律当 `null`（不可比），不当 0**。

## 文件清单

**后端（channel 服务）**

| 文件 | 作用 |
| --- | --- |
| `backend/services/channel/src/main/resources/db/migration/V402__product_dimension_ad_report.sql` | 导出留痕表 `ad_product_report_export`（纯新建、零 ALTER；tenant_id NOT NULL + ENABLE/FORCE RLS isolation + `data_scope` RESTRICTIVE + GRANT，`starcon_app` 零权限） |
| `backend/services/channel/src/main/java/com/starcon/channel/AdProductReportModels.java` | 传输模型（行 / 汇总 / 能力声明 / 导出留痕） |
| `.../AdProductReportService.java` | CTE 链 `campaign_daily → campaign_targets → campaign_alloc → listing_metrics → listing_sku_map → sku_listing_count → listing_sku_info → sku_metrics`；两级等分归因；LISTING/SKU 用 SQL 真分页，SPU 在服务层归并后分页；汇总独立聚合查询；CSV 流式导出；导出留痕 |
| `.../AdProductReportController.java` | `/channel/ad-product-reports` 的 5 个端点，权限 `channel.operation.read`（复用 identity 已有权限码，identity 零改动） |
| `.../AdProductProfitPort.java` | channel → reporting 只读端口（利润侧） |
| `.../AdProductCatalogPort.java` | channel → catalog 只读端口（SKU 编码 / SPU 归属，批量上限 2000 SKU，超出如实报截断） |

**测试**

| 文件 | 用例 |
| --- | --- |
| `backend/services/channel/src/test/java/com/starcon/channel/AdProductReportFixture.java` | 共享夹具：PG16 容器 + identity / reporting / catalog 三个 HTTP 桩（**按 bearer 分租户**）+ `queryStringsAsRlsTenant()`（用 `channel_app`（nobypassrls）证明 RLS） |
| `.../AdProductReportIntegrationTest.java` | **16 例**业务用例 |
| `.../AdProductReportTenancyIntegrationTest.java` | **3 例**多租户三断言 |

**契约（codegen 生成，非手改）**

- `packages/api-contracts/src/channel.openapi.json`（纯新增）
- `packages/api-contracts/src/contracts/channel.ts`（纯新增 10 个 `AdProduct*` DTO）

**前端**

| 文件 | 作用 |
| --- | --- |
| `apps/web/src/components/ads/ad-product-report-model.ts` | 前端模型 + 展示辅助 + 校验 |
| `apps/web/src/components/ads/ad-product-report-model.test.ts` | **29 例** |
| `apps/web/src/components/ads/ad-product-reports.tsx` | 工作区组件 |
| `apps/web/src/app/ads/product-reports/page.tsx` | 页面入口 `/ads/product-reports` |
| `apps/web/src/app/api/channel/ad-product-report-allowlist.test.ts` | **5 例**（契约反查双向断言） |
| `apps/web/src/lib/api-proxy.ts` | **新增一条独立 `channel/` 元素**（44 行纯新增 / 0 删除，既有条目一字未动、不重排） |

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md`（14.3.1.3 一行）
- `docs/sessions/2026-10/2026-10-29-ad-product-report.md`（本文）

## 验证

- L0：`cd backend && ./mvnw -q -pl services/channel -am compile -o` → 通过
- L0：`pnpm typecheck` → 3 包绿
- L1 后端：`./mvnw -pl services/channel -am test -o -Dtest='AdProductReport*' ...`
  → **19 例绿**（3 租户 + 16 业务），BUILD SUCCESS
- L1 前端：`ad-product-report-model.test.ts` **29 例绿**；`ad-product-report-allowlist.test.ts` **5 例绿**
- api-proxy 改动要求的全量 allowlist 回归：`apps/web/src/app/api/trade` **394 例绿（71 文件）**、
  `apps/web/src/app/api/channel` **408 例绿（81 文件）**
- 契约：9 个服务的 `OpenApiContractTest` 全绿 → `catalog-contracts.py` 重新生成
  （`Generated 7286 DTOs in 9 per-service files`），**只 channel 两个文件变更，其余 8 个服务逐字未变**
- Testcontainers 期间**同时存活的 PG 容器数 = 1**（`postgres:16-alpine`，05:39:02 → 05:39:20），
  测试结束后**已被 ryuk 回收**（`docker ps -a` 为空，连停止态容器都不留）

## 后续待办（不是本车道的活）

1. **单量口径的广告销售占比**（广告单数 ÷ 总单数）—— 13.4.1.1 的利润事实只给金额与件数、
   **没有 SKU 级订单数** ⇒ SKU 维度没有分母。要交付得先在 13.4.1.1 补订单数口径。
2. **跨币种统一口径** —— 本仓没有汇率表也没有汇率同步器，需要汇率源落地后才能做。
3. **广告归因（14.3.2.1）** —— 本模块给的是**投放关系分摊**，不是归因；
   归因规则归 14.3.2.1 固定并公示。
4. **`reporting.*` 权限码未在 identity 登记** —— 全仓 grep 无命中 ⇒
   `reporting.sku-profit.read` 在真实用户下无法被授予，生产环境 channel→reporting 的利润读取可能 403。
   本模块已按「上游读不到 ⇒ 降级 + 明确原因」处理，广告侧不受影响；**这是 reporting 服务族的既有缺口**。
5. **投放关系与 SKU 映射取当前登记态**（上游没有历史版本）—— 改了投放商品或 SKU 映射，
   历史报表的分配比例会跟着变。这是上游结构决定的，本模块改不了，但界面与文档都要说清。
