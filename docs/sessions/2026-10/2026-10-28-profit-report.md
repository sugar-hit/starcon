# 2026-10-28: 13.4.1.1 多模式利润报表 —— 模式不合并的利润报表（阶段 D7，L3 13.4.1 的第一个模块）

> 车道 `feat/13-4-1-1`（从集成头 `integration/module-dev` @ `22507bf8` 切出）。
> 蓝图：`docs/product/capability-blueprint/13-profit-and-analytics.md` → 13.4.1.1「多模式利润报表」。
> **本车道是 L3 13.4.1 的第一个模块** ⇒ 写法由本车道定（照抄 13.4.2.2 费用差异那套投影表纪律）。

## 摘要

| 项 | 值 |
| --- | --- |
| 模块 | 13.4.1.1 多模式利润报表（卷 13 L3 13.4.1 利润报表 → 第一格） |
| 迁移 | `V398__profit_report.sql`（两张新表，零 ALTER 既有表） |
| 端点 | 5 个，挂 `/reporting/profit-report` |
| 页面 | `/analytics/profit/profit-report` |
| 测试 | 后端 17 例（10 口径 + 3 租户隔离 + 4 投影链路）· 前端 20 例 · api-proxy allowlist 回归 264 文件 / 1492 例 |
| L0 | `./mvnw -q compile` exit 0；`pnpm typecheck` 3/3 successful |
| L1 | `ProfitReport*` ⇒ **17 例全绿**（Failures 0 / Errors 0）BUILD SUCCESS |
| 契约 | codegen 产物，**纯增量**（reporting.ts + reporting.openapi.json，removed = 0） |

## 决策摘要

### ① 经营模式是**上游字段的投影**，不是本模块的枚举

蓝图输入句点名的「模式标记（2.3.2.1）」在仓库里就是 channel 的 `shop.operating_mode`
（DB 层 CHECK 钉死五个值）。⇒ 本模块**不硬编码模式当真相**：模式落成一张**档案表**
（`reporting_profit_shop_profile`），事实行上的 `operating_mode` 是**投影当时**从档案抄下来的。

**为什么不是「直接 join 档案表」**：店铺改模式后，历史事实**不随当前模式回溯改写** ——
六月按自发货记下来的收入，六月把店铺改成全托管之后，它**仍然是六月的自发货**。
要改历史，走**重建投影**（清空后按新模式重放），与 reporting 的重建纪元同一纪律。

挂不上店铺的钱归 `UNKNOWN`「未归属」，在报表里**单列一行**，**不混进任何真实模式**
（混进去就是编造）。

### ② 两种收入口径并存，且**代码里分开取**

上游给两种数：`grand_total_amount`（订单总额，含订单级运费 / 税 / 折扣）与
`line_amount` 之和（订单行金额，不含订单级尾差）。**两者不是同一笔钱。**

⇒ 事实表用**同一张**（`component = 'REVENUE'`），靠**归属维度列是否为空**把两套口径分开：

- 订单总额口径行：`sku_code / spu_code / msku_code` 全空 ⇒ 经营模式 / 店铺 / 订单三个维度用它；
- 订单行金额口径行：`sku_code` 非空 ⇒ SKU / SPU / MSKU 三个维度用它。

读侧的过滤条件就一句：`component <> 'REVENUE' or (sku_code = '' and spu_code = '' and msku_code = '')`。
**两个口径绝不相加**（加出来就是重复计收入）。

**取舍**：SKU 维度的收入是**行金额**口径，与店铺维度的**订单总额**口径差一笔订单级尾差，
下钻时数字**对不上**。这是上游数据的真实形状，不是实现偷懒 ⇒ 口径写在行备注与页面上。
另一种做法（SKU 维度收入直接报 `null`，说「SKU 级收入归 13.4.1.2」）更整齐，但会让
SKU / SPU / MSKU 三个维度只剩成本、没有收入 —— 蓝图职责句点名要支持这三个维度，
所以选了「给行金额口径 + 写明差额」。

### ③ 一笔钱只有一个写入所有者（平台费不记两次）

12.3.1.2 结算明细（佣金 / FBA 费用 / 广告）**带店铺与订单**；
12.4 费用池里的**同名**费用是同一笔钱。⇒ **平台费 / 物流费 / 广告费的唯一写入所有者是结算明细**，
12.4.2.2 的分摊结果里遇到 `PLATFORM_FEE / LOGISTICS_FEE / AD_FEE` 三个类别**跳过并如实报数量**。

头程费（`HEAD_MILE_FEE`）与人员 / 办公 / 订阅成本（`PERSONNEL_COST / OFFICE_COST /
SUBSCRIPTION_FEE`）取 12.4 归集 + 12.4.2.2 分摊结果。

**代价（如实记录）**：上游若只归集了费用池而**没有**结算明细，这三项会显示为「算不出来」
（不是 0），能力声明里写明了这一点与去处。

**符号**：结算明细上游是「结算贡献」（负数），费用是减项 ⇒ 落库时**翻正**（与 13.4.2.2 同一约定）。

### ④ 毛利算不出来 vs 毛利等于 0 —— 「适用」按**租户**口径判定，不是按模式

这是本模块最容易做错的一格。三种情况必须分开：

| 情况 | 判定 | 页面 |
| --- | --- | --- |
| 租户在该维度**压根没有**这类成本 | **不适用**（不参与毛利计算） | 「—」+「若上游还有未投影的，毛利会偏高」 |
| 租户有、**本模式没有** | **缺项** ⇒ 毛利**算不出来** | 「—（不可比）」+ 缺哪几项 |
| 本模式有、成本齐全 | 可比 | 毛利 + 毛利率 |

**为什么用租户口径而不是模式口径**：用模式口径的话，模式 A 有广告费、模式 B 没有时，
A 的毛利因「缺别的项」变 null、B 的毛利却算得出来 —— **两个模式的判定口径不一致**，
报表没法横比。改成租户口径后：租户有广告费 ⇒ 每个模式都必须有才算毛利；租户压根没有广告费 ⇒
所有模式一律不适用 ⇒ **各模式判定一致**。

**已知缺口（如实记录）**：租户压根没有某类成本时，那几项**不参与**毛利计算 ⇒ 若上游其实有、
只是还没投影到本服务，毛利会偏高。**没有用假 0 冒充**（0 会被读成「这一项真的是 0」）；
改为在**报表级警告**里逐项点名「本租户在【店铺】维度上没有广告费事实」。

### ⑤ 事实表用**长表**（一行一个成本项），不用宽表

宽表（一行一个维度组合 + 每个成本项一列）里，「这个成本项**算不出来**」只能写 NULL ——
而一旦某次刷新只带了三个成本项，其余四个列全是 NULL，读侧**分不清**「那四项真的是 0」
与「那四项没取到」。⇒ 长表：`component` 把每一类金额分成独立一行，**缺哪一项就是真的没有那一项**。

## 文件清单

**迁移**
- `backend/services/reporting/src/main/resources/db/migration/V398__profit_report.sql`

**后端**（`backend/services/reporting/src/main/java/com/starcon/reporting/`）
- `ReportingProfitReportModels.java` —— 读模型契约（OperatingMode / Component / Dimension / Granularity 四个词表 + 各响应 record）
- `ReportingProfitReportPort.java` —— 跨服务只读端口（channel 店铺与平台商品、finance 归集规则 / 费用池 / 分摊批次 / 分摊结果）
- `ReportingProfitReportRefreshService.java` —— 四路上游的拉取适配器（模式 / 订单收入 / 结算费用 / 分摊成本）+ 幂等 upsert
- `ReportingProfitReportService.java` —— 读模型（利润报表 / 下钻 / 模式清单 / 能力声明 / 数据更新时间）
- `ReportingProfitReportController.java` —— 5 个端点，命名空间 `/reporting/profit-report`

**测试**（`backend/services/reporting/src/test/java/com/starcon/reporting/`）
- `ProfitReportIntegrationTest.java`（10 例：口径）
- `ProfitReportTenancyIntegrationTest.java`（3 例：租户隔离三断言）
- `ProfitReportRefreshIntegrationTest.java`（4 例：投影链路，带上游替身）

**前端**
- `apps/web/src/components/analytics/profit-report-model.ts`
- `apps/web/src/components/analytics/profit-report-model.test.ts`
- `apps/web/src/components/analytics/profit-report-workspace.tsx`
- `apps/web/src/app/analytics/profit/profit-report/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，锚点固定在 `time-pattern` 那条之后）

**契约 / 台账**
- `packages/api-contracts/src/contracts/reporting.ts`、`packages/api-contracts/src/reporting.openapi.json`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（只改 13.4.1.1 那一行的「状态」「证据」两格）

## 后续待办

1. **13.4.1.2 SKU 利润**（同 L3 的兄弟模块）：读同一批 `reporting_profit_fact`，
   但 SKU 级利润的口径归它 —— 本模块的 SKU / SPU / MSKU 维度只给**收入口径**与
   **该维度上真实存在的成本**。
2. **SKU 维度的成本口径**：上游 12.4.2.2 把费用池分摊到**店铺 / 订单**维度时，
   SKU 维度没有成本 ⇒ 该维度毛利不可比。要让 SKU 级毛利成立，需要上游提供
   **SKU 维度**的分摊（12.4.2.2 的 `target_dimension` 已支持 `SKU`，是**配置**问题不是代码问题）。
3. **刷新覆盖率常驻**：当前「本租户没有某类成本 ⇒ 毛利会偏高」只在报表级警告里点名，
   没有把**每一路上游最近一次刷新的条数**落在页面上。可考虑加一张刷新状态表。
4. **订单明细取数上限**：一次刷新最多取 200 张订单的明细（逐单取明细是 N+1），
   触顶时**如实报 TRUNCATED** —— 超出部分**没有行口径收入**（不是收入为 0），
   订单总额口径的收入仍完整。
