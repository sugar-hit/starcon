# 2026-10-29 · 13.5.3.1 物流分析

> 车道：`feat/13-5-3-1`（worktree `.worktrees/13.5.3.1`，基线 `integration/module-dev` @ `cfa76762`）
> 阶段：D7 · L3 锚点 13.5.3 · 落地服务：**reporting**（卷 13 的后端主责服务）

## 决策摘要

- **落 reporting 服务，不落 channel**。卷 10 的物流事实（运单 / 轨迹 / 时效 / 异常件）归 channel 与 trade 所有；
  集成头里**没有独立的 logistics 服务**。本模块是**分析投影**（as_of 粒度的成本 / 渠道 / 时效快照 +
  运单 / 分摊 / 异常件的行级事实 + 阈值规则表），**不建第二个物流真相**、不直连对方库、不建跨库 FK 或视图。
- **Flyway 版本号 `V406`**（调度者预分配；同批另一条车道 14.3.2.1 用 V404）。
- **权限码沿用 reporting 服务族**：`reporting.logistics-analysis.read` / `.write` + 既有 `reporting.projection.write`。
  ⚠️ 全仓存在 `reporting.*` 权限码未在 identity 登记的**既有缺口**（14.3.1.3 / 13.5.1.1 会话日志已记录），
  那是 reporting 服务族的问题，**本车道不修**、也不新造一个需要登记的码。
- **契约重新生成**（codegen 产物，禁手改）：13 条 path + 27 个 DTO，**纯新增、零删除、零改动**已交付 DTO。

## 关键 trade-off

1. **单均运费的分母口径（最要紧的一处）**
   channel 自己的 `perOrderFreight` 字段名叫「单均运费」，但它的分母是**已登记运费的运单票数**（票），
   不是订单数。一单多票（拆单发运）时票数 > 订单数 ⇒ 那个数**偏小**。
   ⇒ 本模块的 `perOrder` 用**订单数**（同一批运单上的 distinct `sales_order_no`），
     上游那个数原样搬进 `upstreamPerWaybillFreight` **并排对照**，两个口径在页面上同时出现、绝不混为一谈。
   ⇒ 「只有未登记运费的订单」**不进分母**（它没有分子，分母里也不该有它），差额由 `orderCountAll` 如实报出。
2. **各国成本对比为什么读 trade 的运单清单，而不是 channel**
   channel 的成本报表按**渠道**分组，装不下「目的国」这一维；只有**运单级事实**同时带运费金额与目的国。
   而运单本来就是尾程运费的最小颗粒 —— channel 自己的成本分析也是经
   `GET /trade/logistics/waybills/list` 读同一批运单 ⇒ **同一个上游端点，不是第二个真相**。
3. **类目维度的物流成本占比只覆盖头程分摊部分**
   蓝图关系句要求「物流成本占比高的类目必须单独定价」，所以必须有类目维度的物流成本。
   但尾程运费落在**运单**上、一票可能多 SKU，**没有 SKU 维度** ⇒ 只有头程分摊（本来就按 SKU 行分摊）能落到类目。
   ⇒ 分子 = 类目下的头程分摊后成本；分母 = **13.4.1.2 的 `reporting_profit_fact`（component='REVENUE'）**（与利润侧同口径，不自算销售额）；
   **响应与页面都写明「类目维度只覆盖头程分摊」**，不让下游把它当全口径。
4. **异常件占比的分子分母必须同范围**
   channel 的 `/exceptions/overview` 是**累计全量**（端点没有时间窗）。拿它除以「窗口内运单数」会得到一个**没有意义**的比率。
   ⇒ 本模块投影**异常件行**（按发现日归窗），分子取窗口内的件、分母取同一窗口的运单数；上游累计口径**不参与占比计算**。
5. **头程分摊后成本不重算**
   分摊规则 / 基准 / 结账时点（`cost_as_of`）全归 channel 10.6.1.1。本模块只搬结果，
   并把「分摊单逐币种合计」与「上游成本总览的头程分摊位」**并排对照**（`upstreamOverviewAmount` / `differenceToOverview`），
   对不上时以 channel 为准 —— 两个分摊口径会让「物流成本占比」有两个答案。
6. **不落 channel、不建跨库视图**
   运单 / 分摊 / 时效 / 异常件的抓取一律经**授权只读 HTTP**（转发调用方自己的 bearer，租户由上游按已认证成员关系解析），
   租户隔离是**两道**（reporting 侧 RLS + 上游侧 RLS）。
7. **`as_of` 快照 vs 行级事实两种粒度并存**
   成本 / 渠道 / 时效是**整窗汇总** ⇒ 落成 `as_of`（= 窗口止日）快照，查询取「as_of ≤ 窗口止日里最新的一张」；
   运单 / 分摊 / 异常件是**行级事实** ⇒ 按业务日 / 结账时点查窗口。

## 文件清单

### 后端（Java，reporting 服务）

| 文件 | 说明 |
| --- | --- |
| `backend/services/reporting/src/main/resources/db/migration/V406__logistics_analysis.sql` | 八张分析投影表 + 一张阈值规则表；RLS isolation + GRANT；纯 CREATE TABLE |
| `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLogisticsAnalysisPort.java` | 跨服务只读端口（channel / trade / catalog 三路） |
| `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLogisticsAnalysisModels.java` | 响应 / 请求模型（27 个契约类型） |
| `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLogisticsAnalysisRefreshService.java` | 投影摄取（幂等 upsert；上游读不到如实报 FAILED） |
| `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLogisticsAnalysisService.java` | 读模型（jOOQ 显式 SQL） |
| `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLogisticsAnalysisController.java` | 13 个端点挂 `/reporting/logistics-analysis` |
| `backend/services/reporting/src/test/java/com/starcon/reporting/LogisticsAnalysisIntegrationTest.java` | 15 条业务口径断言 |
| `backend/services/reporting/src/test/java/com/starcon/reporting/LogisticsAnalysisTenancyIntegrationTest.java` | 3 条多租户隔离断言 |

### 前端（TS）

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/components/analytics/logistics-analysis-model.ts` | 纯函数层（格式化 / 语义色 / 口径文案） |
| `apps/web/src/components/analytics/logistics-analysis-workspace.tsx` | 真实工作区（十块报表 + 三态） |
| `apps/web/src/components/analytics/logistics-analysis-model.test.ts` | 17 条前端口径断言 |
| `apps/web/src/app/analytics/logistics/analysis/page.tsx` | 页面 |

### 共享产物（多车道共用，按规矩由 codegen / 并入驱动器求并）

- `packages/api-contracts/src/contracts/reporting.ts`、`packages/api-contracts/src/reporting.openapi.json`
- `apps/web/src/lib/api-proxy.ts`（**纯新增一条** `reporting/` 元素，既有条目一字未改）

### 台账 / 会话

- `docs/product/capability-blueprint/module-progress.md`（**只改 13.5.3.1 那一行的「状态」「证据」两格**）
- `docs/sessions/2026-10/2026-10-29-logistics-analysis.md`（本文件）

## 从上游读了哪些端点、每个端点提供哪个口径

| 上游端点 | 提供哪个口径 |
| --- | --- |
| `GET /channel/logistics-cost-analysis/overview` | **全口径成本**：逐币种的尾程 / 头程分摊 / 关税 / 包材 / 合计 + 覆盖说明（缺项 ≠ 0）+ 上游「每票运费」 |
| `GET /channel/logistics-cost-analysis/by-channel` | **渠道对比**：承运商 × 渠道 × 币种的金额与票数 |
| `GET /channel/logistics-cost-analysis/allocations` + `/{id}` | **头程分摊后成本**：分摊单（规则版本 / 结账时点 / 目的国）+ 逐 SKU 的分摊明细行 |
| `GET /channel/logistics-timeliness/reports/matrix` | **时效达成率**：渠道 × 国家矩阵（准点 / 超时 / 可比 / 无承诺 / 在途 + 达成率 / 妥投率 / 平均时效 / 评分） |
| `GET /channel/logistics-tracking/exceptions` | **异常件占比的分子**：一行一个已判定的异常件（发现日 / 类型 / 严重度 / 状态） |
| `GET /trade/logistics/waybills/list` | **各国成本对比**（运费金额 + 目的国）与**单均运费的订单分母**（distinct 销售订单号） |
| `GET /catalog/skus` | **SKU → 类目映射**（类目维度物流成本占比要用） |
| `GET /reporting/sku-profit/report`（同服务，读 `reporting_profit_fact`） | **类目占比的分母**：13.4.1.2 的销售额口径（component='REVENUE'），本模块不自算 |

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | ✅ 全绿 |
| L0 类型 | `pnpm typecheck` | ✅ 3 packages 全绿 |
| L1 模块 | `./mvnw -pl services/reporting -am test -Dtest='LogisticsAnalysis*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ **18 tests / 0 failures / 0 errors** |
| 前端单测 | `pnpm vitest run apps/web/src/components/analytics/logistics-analysis-model.test.ts` | ✅ **17 tests 全绿** |
| api-proxy 白名单回归 | `pnpm vitest run apps/web/src/app/api/trade` | ✅ **71 files / 394 tests 全绿** |

## 后续待办

0. **⚠️ 给调度器的提醒**：本车道在改完契约类型名（`LogisticsChannelRow` → `LogisticsChannelComparisonRow`，
   避开与 channel 的同名 DTO 冲突）之后跑 L1 时，**先红了一次**：
   `TestEngine with ID 'junit-jupiter' failed to discover tests`（Tests run: 0）。
   根因是**本地 `target/test-classes` 里的旧 class 仍引用已改名的类型** ⇒ 增量编译不会重编它，
   JUnit 在**发现阶段**加载类失败 ⇒ 整个引擎起不来（不是用例失败）。
   `./mvnw -pl services/reporting clean` 后重跑即 **18/18 全绿**。
   ⇒ **任何车道在改类名 / 改契约类型名后跑 L1 前，先 `clean` 或确认 `target/test-classes` 是新的**；
   这个坑与代码对错无关，但会让 L1 变成一个假红。
1. **`reporting.*` 权限码未在 identity 登记** —— 全仓既有缺口（14.3.1.3 / 13.5.1.1 会话日志已记录）。
   本模块沿用族内约定码；真实用户下 `reporting.logistics-analysis.read` 仍可能 403。
   **要修需要 identity 侧的迁移版本号**，不在本车道范围。
2. **类目维度只覆盖头程分摊** —— 若要「类目 × 全口径物流成本」，需要上游给尾程运单加 SKU 行维度
   （当前运单是一票一行、无 SKU 明细），属上游结构问题。
3. **汇率** —— 本仓没有汇率表也没有汇率同步器，本模块**绝不跨币种相加或相除**；
   跨币种汇总需要 5.2.2.1「汇率查询」先落地。
4. **通道口径不完整** —— 上游成本总览的「仓储费」在本仓**没有事实来源**，
   `missingComponents` 会逐条列明；仓储计费落地后本模块自动接上。
5. **异常件的累计口径未纳入占比** —— 若运营需要「历史累计异常率」，应另立模块（累计与窗口是两个口径）。
