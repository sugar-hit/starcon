# 2026-10-29: 13.5.2.1 采购分析

## 摘要

卷 13「利润与经营分析」L3 13.5.2 采购分析下的 **13.5.2.1 采购分析**落地：
从 **6 采购（trade）** 与 **6.1.1.1 供应商档案（catalog）** 的**授权只读 API** 拉取事实，
落成本服务的**分析投影**，算出**采购金额趋势与占比、供应商集中度（+ 风险信号）、价格波动、
到货准时率、采购周期、采购成本占销售额比**，并给出**供应商对比**与**下钻**。

蓝图原文：「**职责**：采购分析：采购金额趋势与占比、供应商集中度、价格波动、到货准时率、
采购周期、采购成本占销售额比。**输入**：采购数据（6）；供应商评级。
**输出**：采购报表 + 趋势 + 供应商对比。**关系**：上游 = 6 采购与供应；下游 = 13.4 利润。
**供应商集中度是供应链风险指标**，单一供应商占比过高会在断供时直接击穿业务。」

车道：`feat/13-5-2-1`（worktree `.worktrees/13.5.2.1`），基线 `integration/module-dev`（f2c5f630）。
Flyway 版本号：**V405**（调度者预分配）。

## 决策 / 结论

### 1. 🔴 任务书对上游归属的猜测被证伪 —— 以**已交付现实**为准

任务书写「供应商主数据 + 到货/质检记录 → catalog（6.1.1.1 / 6.1.1.2 / 6.1.1.3 均已交付）」，
但实测：

| 事实 | 实际归属 | 证据 |
| --- | --- | --- |
| 供应商列表 / 采购侧档案 / 到货记录 | **catalog** | `/catalog/supply/vendors`（V137） |
| 供应商详情（账期 / 资质） | **catalog** | `/catalog/supply/vendors/{roleId}`（V142） |
| 供应商**评级** | **trade**（不是 catalog） | `/trade/supplier-ratings/ratings`（V266） |
| 采购到货流水（金额 / 单价 / 准时） | **trade** | `/trade/purchase-analytics/facts`（V251，6.5.2.1） |

⇒ 全部按**已交付现实**接线，不按任务书的猜测。
台账里 6.1.1.2 那一行已经记过同类修正（任务书写 trade、实际落在 catalog）。

### 2. 🔴 两套金额口径必须**互斥**，否则同一笔采购会被算两次

上游有两处带采购金额的事实：

* trade 6.5.2.1 的到货流水（`fact_kind=COST` / `source_type=ARRIVAL_RECEIPT`）
  —— 粒度是**每一次到货一行**（卷 06 硬约束「采购收货是分批、可有差异的」）；
* catalog 6.1.1.1 的供应商到货记录 —— **也带金额**。

⇒ **金额全部取 trade 一处**；catalog 的到货记录**只用于采购周期**
（**全仓唯一同时有「下单日」与「到货日」的事实源**；trade 的事实台账只有到货时刻与承诺到货日）。

**取舍**：也可以两处都用、按来源分列。但那要在每个合计里做来源拆分，
而且两处随时可能不一致（同一笔货 catalog 录了、trade 没录）⇒ 放弃。
**落地方式不是注释提醒，而是表结构**：`reporting_procurement_analysis_delivery_fact`
**刻意没有金额 / 数量 / 币种三列** ⇒ 同一笔采购被算两次这件事**在结构上不可能发生**。

### 3. 🔴 集中度给的是**风险信号**，不是一个中性的百分比

蓝图加粗句是本模块的命门。落地成**两种风险分开判**：

| 等级 | 判据 | 含义 |
| --- | --- | --- |
| **HIGH** | **单一供应商**占比 ≥ 单家阈值（默认 40%） | 断供时直接击穿业务 |
| **MEDIUM** | **前 N 家**（默认 3）合计占比 ≥ 合计阈值（默认 60%） | 无单点依赖，但结构偏集中 |
| **LOW** | 其余 | 分散 |

响应里除等级外还有**风险说明**：写明是哪一家、占比多少、断供时哪一档采购额会中断。

**取舍**：HHI 也算了（0~10000），但**只作参考指数、不参与定级** ——
4 家 30/30/20/20 的 HHI 是 2600，可最大供应商只有 30%，
把它叫「高风险」比叫「中风险」更离谱 ⇒ 定级只由**租户自己可配的两个阈值**决定
（`concentration_top_n` / `concentration_top_share_pct` / `single_supplier_share_pct`）。

### 4. 🔴 销售额分母**引用** 13.1.1.1 的投影，不重算

「采购成本占销售额比」的分母（销售额）取
`reporting_metric_fact` 的 `SALES_AMOUNT` / `scope_type='COMPANY'`
（卷 13 卷首「**口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算」）。
参照同卷 13.4.1.2 SKU 利润读 `reporting_profit_fact` 的同一做法。

⇒ 该投影没跑时，比率是 **null（不可比，不是 0%）**，
能力声明里报出归属模块号（「13.1.1.1 经营总览的投影」）与影响。

**取舍**：也可以自己投影一份销售额，模块就自洽了，但那是**第二个真相** ⇒ 放弃。

### 5. 🔴 「算不出来」与「等于 0」分开（四条贯穿全模块）

1. **分母为 0 ⇒ null**（准时率 / 成本占比 / 占比 / 环比都是 null，**不是 0%**）；
2. **没有金额的到货流水是「—」**，不进任何合计（**不是 0 元**），并在趋势里**单独计数**；
3. **没有到货记录 ⇒ 采购周期不可比**（**不是 0 天**）；
4. **没有评级 ⇒ 评级列是「未评级」**（**不是 0 分** ——
   把「没评过」写成 0 分等于替上游断言「这家很差」）。

前端把这四种 `null` 渲染成「—（暂无数据）」并用**中性**配色
（不用警告色：「这一格没数据」是中性事实，不是异常）。

### 6. 🔴 准时与周期的口径归属不同，各写各的

* **准时率**：判定归 **trade 6.3.2.2**（`on_time_status`），本模块**只搬运、不重算**；
  分母 = **已到货**流水，**PENDING（未到货）不进分母**（另计）。
* **采购周期**：**到货日 − 下单日**（catalog 6.1.1.1）。
  「承诺到货日 − 下单日」是**承诺交期**，**不是**实际采购周期 —— 两者不能混，
  响应里 `caliberNote` 写明这一点。
* **采购金额含 PENDING**（已承诺待到货的金额也是采购承诺额），
  但 PENDING **不进准时率分母** —— 两处口径各自在响应里写清。

### 7. 工程上踩到的两个坑（都已在代码里留注释）

* **jOOQ 解析器不吃 `ON CONFLICT DO UPDATE` 里引用目标表的 `case when`** ——
  报 `bad SQL grammar`。改成**先读后写**（读出档案里已有的编码 / 名称再决定写哪个值）。
* **`timestamptz` 列必须写 `?::timestamptz` + 绑 `OffsetDateTime`** ——
  不写显式 cast 时 PG 按 varchar 送过去，列类型却是 timestamptz ⇒ 落库直接炸。

## 文件清单

**迁移**

* `backend/services/reporting/src/main/resources/db/migration/V405__procurement_analysis.sql`

**后端**（`backend/services/reporting/src/main/java/com/starcon/reporting/`）

* `ReportingProcurementAnalysisModels.java` —— DTO（26 个契约类型）
* `ReportingProcurementAnalysisPort.java` —— 跨服务只读端口（catalog / trade）
* `ReportingProcurementAnalysisRefreshService.java` —— 投影摄取（幂等 upsert）
* `ReportingProcurementAnalysisService.java` —— 读模型（七件指标 + 阈值规则）
* `ReportingProcurementAnalysisController.java` —— 12 个端点

**后端测试**

* `backend/services/reporting/src/test/java/com/starcon/reporting/ProcurementAnalysisIntegrationTest.java`（29 条）
* `backend/services/reporting/src/test/java/com/starcon/reporting/ProcurementAnalysisTenancyIntegrationTest.java`（3 条）

**契约**（codegen 产物，只改不改）

* `packages/api-contracts/src/contracts/reporting.ts`（+26 行）
* `packages/api-contracts/src/reporting.openapi.json`（+12 path / +26 schema，零删除零改动）

**前端**

* `apps/web/src/app/analytics/procurement/analysis/page.tsx`
* `apps/web/src/components/analytics/procurement-analysis-model.ts`
* `apps/web/src/components/analytics/procurement-analysis-workspace.tsx`
* `apps/web/src/components/analytics/procurement-analysis-model.test.ts`（26 条）
* `apps/web/src/app/api/reporting/procurement-analysis-allowlist.test.ts`（6 条）
* `apps/web/src/lib/api-proxy.ts` —— **纯新增一条** `reporting/`（procurement-analysis）元素，
  插入锚点 = `inventory-analysis` 那条之后

**台账**

* `docs/product/capability-blueprint/module-progress.md` —— 只改 13.5.2.1 那一行

## 验证

* L0 编译闸：`./mvnw -q compile` + `pnpm typecheck` 全绿
* L1 模块闸：`./mvnw -pl services/reporting -am test -Dtest='ProcurementAnalysis*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  = **32 tests / 0 failures / 0 errors**
* 前端：`pnpm vitest run apps/web/src/components/analytics/procurement-analysis-model.test.ts
  apps/web/src/app/api/reporting/procurement-analysis-allowlist.test.ts` = **32 tests 全绿**
* 全量 allowlist 回归（改了共享白名单）：`pnpm vitest run apps/web/src/app/api`
  = **270 files / 1527 tests 全绿**
* `pnpm lint` 全绿

## 后续待办

* **13.5.3.1 物流分析**（同一 L3 的下一格）：可复用本模块的
  「逐币种分段 + 风险信号 + 阈值规则表」这套骨架。
* **采购成本占销售额比的下游**：13.4 利润模块可以读
  `GET /reporting/procurement-analysis/cost-to-sales`
  （蓝图 6.5.2.1 关系句：「采购成本占比是利润健康度的先行指标」）——
  本模块**已交付**，等 13.4 侧接线。
* **采购金额含 PENDING 的口径**目前只在 `caliberNote` 里写明，
  若下游 13.4 需要「已到货口径」的采购成本，本模块要**再加一个口径**而不是让下游自己减 ——
  那会让两个口径并存 ⇒ 届时应在本模块加显式口径位并让下游引用。
* **trade 6.5.2.1 的采购事实台账需要先同步**（`POST /trade/purchase-analytics/facts/sync`）
  才有到货流水；本模块的 `POST /reporting/procurement-analysis/refresh`
  只做**投影**，**不代跑**上游的同步 —— 能力声明里报出「上游事实台账为空」这一事实。
