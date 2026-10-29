# 2026-10-29 · batch-111 · 商品维度报表（14.3.1.3）＋ 库存分析（13.5.1.1）

> 本批为**并行车道批次**：`lane.mjs plan --lanes 2` 派发 → 2 条车道并行开发 →
> `lane.mjs merge-ready` 并入集成头 `integration/module-dev` → 集成头上跑**一次** L2 批量验证 → 本说明随台账同一个 commit 推送。
> 本文件是**批次总说明**；每个模块的完整设计决策与验证细节见各自的模块会话日志（见每节末尾「模块详细记录」）。

- 集成头：`integration/module-dev`，批次并入 commit `0907e76d`（14.3.1.3）、`f2c5f630`（13.5.1.1）
- 车道：`feat/14-3-1-3`（V402，channel 服务）、`feat/13-5-1-1`（V403，reporting 服务）
- 静态预检（已知互相破坏形状）：**无命中** —— 本批两条新迁移均无 `ADD COLUMN` 无 `DEFAULT`、无 `SET NOT NULL`、无新增 `CHECK` 约束
- 台账：已交付 426 → **428**，未开发 39 → **37**

---

## 模块 14.3.1.3 商品维度报表

**这个功能做什么**：运营按 **SKU / SPU / Listing** 三个维度看广告到底花了多少钱、带来了多少销售额，
并回答一个只看 ROAS 答不出来的问题 —— **广告销售占比（广告单 ÷ 总单）**：
广告到底是在带来增量，还是在抢自然单。同时给出 ACOS / TACOS / 广告后净利 / 广告占比利润，
并支持导出 CSV。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V402__product_dimension_ad_report.sql`
  —— 导出留痕表 `ad_product_report_export`（纯新建、**零 ALTER 任何既有表** ⇒ 跨车道 DDL 回归面 = 0）；
  `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + `data_scope` RESTRICTIVE + GRANT，`starcon_app` 零权限。
- 端点：`/channel/ad-product-reports` × 5
  | 端点 | 负责什么 |
  | --- | --- |
  | `GET /capabilities` | 能力声明：哪些上游真接通、哪些确实拿不到（利润侧 / 编码侧降级原因） |
  | `GET /options` | 筛选项下拉 + 逐指标口径定义 + **归因规则原文** |
  | `GET /rows` | 商品广告报表（真分页 + 真筛选 + 真排序 + 汇总） |
  | `GET /export` | 导出 CSV（`text/csv` 由代理原样透传） |
  | `GET /exports` | 导出留痕历史（真分页） |
  权限复用 identity 已有权限码 `channel.operation.read`（**identity 零改动**）。
- 页面：`/ads/product-reports`
  —— `apps/web/src/app/ads/product-reports/page.tsx` + `apps/web/src/components/ads/ad-product-reports.tsx`
  + 纯函数模型 `ad-product-report-model.ts`。
  筛选维度 / 排序 / 分页 / 每行指标 / 汇总区 / 导出动作齐备；**加载态、错误态、空态三态文案各不相同**；
  每行单独显示**归因方式**（DIRECT / ALLOCATED_EVENLY / MIXED），并单列 `unattributedSpend` / `unattributedSales`。
- 测试：`AdProductReportIntegrationTest` **16 例**（业务口径）+ `AdProductReportTenancyIntegrationTest` **3 例**（多租户三条断言）
  + `ad-product-report-model.test.ts` **29 例** + `ad-product-report-allowlist.test.ts` **5 例**（契约反查双向断言）。
  共享夹具 `AdProductReportFixture.java` 用 `channel_app`（nobypassrls）证明 RLS 真实生效。

**关键设计决策**：

1. **落 channel 服务，不落 reporting** —— 蓝图卷 14 主责服务是 `channel + reporting`，列表第一项即 channel；
   广告事实（`ad_campaign_daily_metric`）本就由 channel 自己拥有，利润侧与商品编码侧走**授权只读 API**
   （`AdProductProfitPort` → `GET /reporting/sku-profit/report`；`AdProductCatalogPort` → catalog）。
   **零跨库 SQL / 跨库 FK / 共享视图。**
2. **商品维度的数字是「分摊出来的」—— 规则固定并公示** —— 广告事实只到活动级，
   「投的是哪个商品」来自投放关系 ⇒ 固定两级等分规则（活动→Listing 按投放商品数等分，Listing→内部 SKU 按变体数等分），
   并随报表 / CSV 表头 / 导出留痕一起返回**归因标记**。
   *否决方案*：只做「活动恰好只投一个商品」的 DIRECT 口径 —— 每个数字都是平台原值但只覆盖一部分投放，
   答不了「多商品活动里哪个商品更值得投」。
3. **归不到商品的花费单列，不摊给任何商品、也不摊成 0** —— 摊成 0 等于说「这些广告没花钱」。
4. **LISTING 维度的利润常常归不到唯一 listing** —— 利润事实只到 SKU 粒度，
   只有「挂 1 个 SKU 且该 SKU 只挂 1 个 listing」才有利润，否则该行利润列**为空并写明原因**，不摊给任何 listing。
5. **TACOS / 广告销售占比的分子必须与利润侧同范围** —— 另给 `scopedSpend` / `scopedSales` 与 `scopedScopeNote`，
   避免「全量花费 ÷ 部分销售额」这种没有意义的比值。
6. **口径一律先汇总再相除** —— `Σ分子 / Σ分母`，不是各行比率的平均；分母为 0 一律 `null`（不可比）而非 0。
7. **上游读不到 = 降级 + 明确原因（不是 503）** —— 广告侧永远可用，利润侧 / 编码侧为空并带
   `profitUnavailableReason` / `catalogUnavailableReason`，候选集被截断也如实报 `candidateTruncated`。
8. **不提供粒度参数、不提供趋势端点** —— 蓝图输入句没有「按日/周/月」、输出句没有趋势图；
   白名单刻意不放行 `ad-product-reports/trend`。
9. **修掉一处契约不一致** —— `roas` 在 `METRIC_DEFINITIONS` 里声明可用，但行 / 汇总 DTO 没有该字段而 CSV 表头已有 ROAS 列
   ⇒ 补齐 `roas` 字段，CSV 改用同一字段，不再就地另算。
10. **BigDecimal 的两种序列化** —— 后端 Jackson 运行时是 JSON number，OpenAPI 契约标注 `string`（防消费方按 JS number 丢精度）
    ⇒ 前端类型 `number | string | null`，统一用 `toNumber()` 转换，**非数字一律当 `null`（不可比），不当 0**。

**模块详细记录**：`docs/sessions/2026-10/2026-10-29-ad-product-report.md`

---

## 模块 13.5.1.1 库存分析

**这个功能做什么**：从库存与订单的**授权只读 API**拉事实，落成分析投影，算出
**库存金额结构、周转率、库龄分布、缺货与超储、ABC 分类、呆滞占比**，
按 **仓库 / 类目 / 店铺 / SKU** 拆解，并给出 SKU 级下钻与盘点历史（准确率）。
ABC 切在**库存金额**上 —— 直接回答「哪些 SKU 占了大部分资金」。

**技术落地**：

- 迁移：`backend/services/reporting/src/main/resources/db/migration/V403__inventory_analysis.sql`
  —— **四张分析投影表 + 一张阈值规则表**，全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + GRANT；
  `CHECK` 约束把「算得出金额」与「三者皆 NULL」钉在数据层，**不允许「有金额但没单价」的半吊子状态**。
  投影表设计：

  | 表 | 粒度 | 为什么这样切 |
  | --- | --- | --- |
  | `reporting_inventory_analysis_position` | as_of × SKU × 仓库 | 库存是**时点**事实；有了历史快照才谈得上「平均库存」 |
  | `reporting_inventory_analysis_sales_fact` | 售出日 × SKU × 上游行 | 周转率的分子；**刻意不落售价**（一张存着售价的表迟早会被拿来算一次周转率） |
  | `reporting_inventory_analysis_aging_fact` | as_of × 范围 × 指标 | 库龄桶与呆滞汇总**粒度不同**，用判别列而非拆两张表 |
  | `reporting_inventory_analysis_stocktake_fact` | 盘点单 | 盘点是**独立事实**，与头寸不同源 |

- 端点：`/reporting/inventory-analysis` × 11
  `GET /capabilities`（能力声明 + `notDelivered` 清单）、`GET /structure`（库存金额结构）、
  `GET /turnover`（周转率 + 趋势）、`GET /aging`（库龄 + 呆滞）、`GET /stock-status`（缺货 / 超储）、
  `GET /abc`（ABC 分类）、`GET /drilldown`（SKU 级下钻）、`GET /stocktakes`（盘点历史）、
  `GET /rules` + `PUT /rules/{ruleType}`（阈值规则）、`POST /refresh`（刷新投影）。
- 页面：`/analytics/inventory/analysis`
  —— `apps/web/src/app/analytics/inventory/analysis/page.tsx` +
  `apps/web/src/components/analytics/inventory-analysis-workspace.tsx` + 纯函数模型 `inventory-analysis-model.ts`。
  筛选维度 / 拆解维度切换 / 下钻 / 规则编辑 / 刷新动作齐备；**加载态、错误态、空态三态齐全且文案各不相同**；
  「算不出来」的 null 在界面上与 0% 长得**不一样**（「暂无数据」+ 中性配色而非警告色）。
- 测试：`InventoryAnalysisIntegrationTest` **16 例**（业务口径）+ `InventoryAnalysisTenancyIntegrationTest` **3 例**（多租户三条断言）
  + `inventory-analysis-model.test.ts` + `inventory-analysis-allowlist.test.ts`（合计前端 25 例）。

**关键设计决策**：

1. **只做分析投影，不建第二个库存真相** —— 库存账本（`inventory_balance` / `stock_bucket`）归卷 7 warehouse 所有；
   再建一张「库存余额表」就会出现第二个真相，而报表通常比账本更被人当真 ⇒ 那就是假账。
   数据**全部**经**授权只读 API**拉取后落地（warehouse 库存总览 / 库龄与呆滞 / 盘点单，catalog SKU 档案与成本构成，
   channel 平台商品，trade 销售订单），**不直连对方库、不建跨库 FK 或跨库视图**。
2. **周转率按成本口径算，且分子分母用同一套成本单价** —— 用售价算会虚高（毛利率越高越虚高）；
   移动加权 vs 标准成本混用 ⇒ 周转率没有意义。
   *取舍*：成本单价有两个来源，本模块**不选**，按上游 `cost_method` 原样搬运并在响应里写明用的是哪一种 ⇒ 口径唯一性归 catalog。
3. **「算不出来」与「等于 0」必须分开** —— 没维护成本单价 ⇒ 金额**皆 NULL**（不是 0 元）；
   分母为 0 ⇒ 周转率 **NULL**（不是 0 次）；没有期初快照 ⇒ 退回期末口径并写明；
   账面行数为 0 的盘点单 ⇒ 准确率 **NULL**（不是 0%）。
4. **按仓库拆解的周转率不可比 —— 如实报出来，不摊** —— 销售订单不带仓库维度，而 warehouse 的出库实发量在
   8.3.2.1 / 8.3.4.1 交付前拿不到 ⇒ 把销售成本摊给某个仓就是**编造**；
   WAREHOUSE 维度返回 **NULL + 明确原因**，类目 / 店铺 / SKU / 全租户四个维度精确可算。
5. **先汇总再相除** —— 周转率 = Σ 销售出库成本 ÷ Σ 平均库存金额，不是对逐 SKU 周转率求平均（后者会让小 SKU 的极端值主导整个数字）。
6. **金额算不出来的 SKU 不进任何合计** —— 否则用户会把「已知部分合计」当成全量金额；
   由响应级 `costMissingSkuCount` / `costMissingQuantity` 如实报出数量。
7. **缺货超储：计数按维度、金额按币种** —— 计数与币种无关（按币种拆开会让「这个仓有几个 SKU 断货」答不出来），
   金额**必须**按币种拆开（绝不跨币种相加）。
8. **库龄与呆滞只搬运、不重算** —— 判定与阈值归 warehouse 7.6.1.1，重算就会与它的处置清单对不上；
   且 warehouse 事实层**没有任何成本列** ⇒ 库龄 / 呆滞占比**只报量**。
9. **ABC 用「累计到本件之后」的占比，且必须用未取整的累计值** —— 拿展示用的 4 位小数判档会在 80% / 95% 边界上判错一件 SKU；
   跨过阈值的那一件落在它**跨入**的那一类，不降级。
10. **阈值不写死** —— ABC 累计占比线与周转目标落在租户自己的规则表（`aCumPct ≥ bCumPct` 由 DB 的 CHECK 挡住）。
11. **position 与 snapshot 合成一张表** —— 分成两张表 ⇒ 同一份库存数据两个落点（第二个真相）。
12. **销售事实带维度快照** —— 若不带 `category_code` / `shop_code`，查询期要 join 头寸表反推维度，
    而**卖光的 SKU 已经没有头寸行** ⇒ 那笔成本整笔丢掉（分子少一块，周转率偏低还不自知）。
13. **销售口径只算已提交**（`documentStatus=SUBMITTED`）—— 草稿单不是销量，算进分子就是虚高。

**模块详细记录**：`docs/sessions/2026-10/2026-10-29-inventory-analysis.md`

---

## 本批共享文件的并入取证

两条车道都改了 `apps/web/src/lib/api-proxy.ts`（**共享白名单累积正则**），而该文件**不在**
`merge=contract-union` 驱动覆盖范围内（覆盖范围只有 `packages/api-contracts/src/**` 与台账）⇒ 走普通 git 三方合并。

- 14.3.1.3（channel）插入锚点：`ad-product-reports` 之后，约 L3040
- 13.5.1.1（reporting）插入锚点：`inventory-analysis` 之后，约 L720

两处相距 **2300+ 行**（> 10000 行的安全距离未满足，但**区段互不重叠**）
⇒ 并入结果 `git diff 8da86b05..HEAD -- apps/web/src/lib/api-proxy.ts` = **+95 行 / 0 删除**，
既有条目一字未改、未重排、未删除；已逐行核对无冲突标记。
⚠️ 下批派发时同批车道的 api-proxy 锚点仍须相距 10000 行以上或分处明确不同区段。

## 本批 L2 批量验证

- 静态预检（`ADD COLUMN` 无 `DEFAULT` / `SET NOT NULL` / `ADD CONSTRAINT CHECK`）：**三条全无命中**
- L2 在集成头 `f2c5f630` 上跑**一次**全量：`./mvnw verify` → `pnpm typecheck --force` → `pnpm lint` → `pnpm test` → `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `sync.mjs --check`
- 结果见本轮汇报与 `.dev-agent/l2/l2-r129.log`

### 补证（2026-10-29 12:22，batch-112 轮）

本批 L2 首跑（round-129，`.dev-agent/l2/l2-r129.log`）在 channel 红 1 例：
`ListingPublishJobIntegrationTest.endpointsRoundTrip` 期望 200 实得 **503**。
取证判定为**负载型假红**（identity 进程内 HttpServer 桩是 JDK 默认单线程 dispatcher，
客户端 keep-alive 残留把下一个请求堵死 ⇒ 超时 ⇒ 503；
同一缺陷在 round-119 换了个受害模块重演，而受害模块每轮都在漂移 ⇒ 随机/负载依赖，非跨车道互相破坏）。

**batch-112 轮用 `-fae`（fail at end）复跑同一集成头内容（含本批 14.3.1.3 与 13.5.1.1）：
11 个后端模块全绿 · 后端 Tests run 11,222 / Failures 0 / Errors 0 · 前端 679 files / 9589 tests 全绿 ·
Next 构建 EXIT=0 · `sync --check` EXIT=0 ⇒ L2 绿。** 详见 `2026-10-29-batch-112-procurement-analysis.md` 与
`.dev-agent/l2/l2-r130.log`。**round-129 的 503 未复现，印证「负载型假红」判定，未做二分。**

## 遗留与后续（不在本批职责内，如实记录）

1. **单量口径的广告销售占比** —— 13.4.1.1 的利润事实只给金额与件数、没有 SKU 级订单数 ⇒ 要交付得先在 13.4.1.1 补订单数口径。
2. **跨币种统一口径** —— 本仓没有汇率表也没有汇率同步器，需要汇率源落地后才能做。
3. **`reporting.*` 权限码未在 identity 登记** —— 全仓 grep 无命中 ⇒ `reporting.sku-profit.read` 在真实用户下无法被授予，
   生产环境 channel → reporting 的利润读取可能 403。**这是 reporting 服务族的既有缺口**，需 identity 侧补注册。
4. **周转率按仓库拆解** —— 等 8.3.2.1 拣货 / 8.3.4.1 发货交接交付后，warehouse 的 `shippedQuantity` 才有值，
   WAREHOUSE 维度即可从「不可比」变成「可比」。**不需要改表结构**。
5. **退货冲减** —— 周转率分子目前没扣 11 售后的退货，等 11 售后交付后可在分子里减去退货成本。
6. **投放关系与 SKU 映射取当前登记态**（上游没有历史版本）—— 改了投放商品或 SKU 映射，历史报表的分配比例会跟着变。
7. **本批在途车道** —— 14.3.2.1 归因分析（V404，channel）、13.5.2.1 采购分析（V405，reporting），
   已在本轮派发，下一轮 tick 回收并入后跑下一批 L2。
