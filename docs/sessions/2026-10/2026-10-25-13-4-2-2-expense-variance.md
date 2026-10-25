# 2026-10-25: 13.4.2.2 费用差异

## 摘要

卷 13「利润与经营分析」L3 13.4.2 成本拆解下的 **13.4.2.2 费用差异**落地：
把「预计费用」（catalog 5.2.3 规则测算）与「实际费用」（12.3.1.2 结算明细 + 上游实际台账）
**拆成两张独立的投影事实表**，差异在**查询期**算出来，输出**差异表 + 偏差趋势 + 规则调优建议 + SKU 级差异**。

本条车道是**崩溃恢复重派**：上一轮 worker 在 2026-10-25 10:20 左右因网络断开被运行时杀掉，
写了一半的代码全在工作区里、**没有 commit**。本轮**接着往下做**：
逐个复核上一轮的代码（Port 抽象、`Unavailable*` 降级设计、V379 迁移的思路**都是对的，保留**），
修掉 3 处真实缺陷，补完前端（上一轮**完全没写前端**），跑绿 L0 / L1 全部验证闸。

## 决策 / 结论

### 1. 预计与实际**拆成两张表**，不是一个表两个金额列

仓库既有先例（V226 平台费、V232 物流头程费）已经写明：
「把『规则算出来的数』和『平台实收的数』写进同一列，就再也答不出『这个月是规则定错了，还是平台多扣了』」。

⇒ `reporting_expense_estimate_fact` / `reporting_expense_actual_fact` 两张表。
差异额与差异率**不落库**（落库的会变成「第三个真相」，规则一改就过期）。

### 2. 「算不出来」与「等于 0」在**三层**分开

这是本模块最核心的一条。三个层次分别是：

| 层 | 做法 |
| --- | --- |
| 采集 | 上游算不出预计值时**不写事实行**（而不是写一行 0） |
| 查询 | 差异额 / 差异率为 `null` ⇒ **不可比**，不是 0% |
| 页面 | `null` 渲染成「—」+ 服务端给的原因，**绝不显示成 0.00 / 0%** |

「差异 0.00」正是蓝图加粗句要消灭的现象。

### 3. 归属维度列用**空串**而不是 NULL

PostgreSQL 的 UNIQUE 把 NULL 视为互不相等 ⇒「平台级」行（其它维度都为空）
会因为 `NULL != NULL` 插出第二条同样的行，幂等键就不成立了。
⇒ 所有维度列 `NOT NULL DEFAULT ''`，与 `platform_fee_rule` 的 `COALESCE` 同一手法，但更省事。

### 4. 规则只在 catalog 一处 —— 本服务**不重算费率**

预计费用**一律取上游 `/estimate` 的结果**。在本服务重算费率会出现**第二个真相**：
规则一改，本模块的差异就变成假的。

### 5. 广告费的预计侧是**预算分配额**，不是费用规则

5.2.3 交付的是平台费与物流头程费的规则；广告那边交付的是**预算分配方案**。
⇒ 广告费的「预计」= 14 广告预算分配方案的逐活动分配额（期间 = 方案的统计窗口），
实际 = 12.3.1.2 的广告费明细。两者口径不同源，比较的是「**预算 vs 实际**」，能力声明里如实写明。

### 6. 符号口径固定为 **差异 = 实际 − 预计**，并写进响应与页面

差异为**负** ⇒ 实际比预计少 ⇒ **预计高估**（费用规则配高了）；
差异为**正** ⇒ 实际比预计多 ⇒ **预计低估**（规则配低了）。
蓝图那句「**持续为负的差异说明费用规则配错了**」就是在这个口径下成立的。

### 7. 上游依赖自检结论：**全部已交付，无需 BLOCKED**

发车前逐条 grep 集成头确认：

| 上游 | 端点 | 状态 |
| --- | --- | --- |
| catalog 5.2.3.1 平台费 | `/catalog/platform-fees/actuals`、`/estimate` | ✅ 已交付 |
| catalog 5.2.3.2 物流费 | `/catalog/logistics-freight/actuals`、`/estimate` | ✅ 已交付 |
| catalog 5.2.1.3 成本分摊 | `/catalog/cost-allocations`、`/{id}` | ✅ 已交付 |
| catalog 5.2.1.1 成本构成 | `/catalog/sku-cost-compositions/{skuId}` | ✅ 已交付 |
| finance 12.3.1.2 结算明细 | `/finance/transactions` | ✅ 已交付 |
| channel 14 广告预算 | `/channel/ad-budgets/plans`、`/{id}` | ✅ 已交付 |

## 接手上一轮半成品：读懂了什么 / 改了什么

上一轮的设计**是对的，予以保留**：

- **Port 抽象**（`ReportingExpensePort` / `ReportingSettlementPort` / `ReportingAdBudgetPort`）——
  跨服务只读、转发调用方自己的 bearer、**不传租户号**（租户只来自已认证成员关系）。
- **`decimal()` / `date()` 解析失败必须炸** —— 静默当 0 会把「读不到」变成「这笔费用是 0」。
- **V379 迁移**的两表拆分、RLS、幂等索引、空串维度列 —— 全部照抄。
- **`ReportingExpenseRefreshService`** 的五路上游拉取、逐行测算（阶梯规则必须按行选档）、
  `MAX_ESTIMATE_CALLS` 触顶时**如实报 truncated** —— 全部照抄。

改掉的 3 处**真实缺陷**（不是风格问题，是跑不起来 / 算错）：

### ① `dataUpdatedAt` 的 SQL 多了一个右括号（14 个用例全挂在这一个查询上）

```
select greatest(
         coalesce((select max(updated_at) from reporting_expense_estimate_fact where tenant_id = ?), '-infinity'::timestamptz),
         coalesce((select max(updated_at) from reporting_expense_actual_fact where tenant_id = ?), '-infinity'::timestamptz))
       ) as updated_at
```

第三个 `coalesce(...)` 后面多了一个 `)`，把 `greatest(` 提前关掉了 ⇒ PostgreSQL
`syntax error at or near ")"`。**定位方式**：把 SQL 抄进真实 `postgres:16-alpine` 容器逐段二分，
确认是**多余的右括号**而不是 `::timestamptz` 或 `-infinity` 的问题。

⇒ 改写成**逐字照抄 13.1.1.1 `ReportingOverviewService.dataUpdatedAt` 的写法**
（两条简单查询 + Java 侧比较），顺带修掉第二个隐患：
原写法用 `'-infinity'::timestamptz` 当哨兵，**两张表都空时会返回 `-infinity` 当「数据更新时间」** ——
那是一个长得像时间的假值。现在两张表都空返回 `null`（页面上写「尚未投影任何费用事实」）。

### ② `skuVariance` 把实际侧写成引用**外层未分组列**的相关子查询

PostgreSQL：`subquery uses ungrouped column "e.tenant_id" from outer query`。

⇒ 改成 **CTE 两侧先各自聚合、再按期间窗口对照**（实际侧按**日**聚合，落进预计费用的期间窗口内的那几天）。
语义不变，且**计数与明细现在用同一个分组口径**（原来两处的 GROUP BY 写法不同，total 与 items 可能对不上）。

### ③ 两处**测试自身**的期望错位

- `规则调优建议只给系统性偏差`：期望 `-10%`，但**自己的夹具是实际 85（⇒ -15%）** ⇒ 按夹具改正为 `-15.0000` / `impliedRate 0.085000`。
- `断言2_伪造租户头无效`：期望 B 有一条 `50.0000` 的事实，但**该用例从头到尾没给 B 播种** ⇒
  补上 B 自己的事实。断言因此**更强**：B 的差异表里只有自己那一条、金额不变（伪造的 999 没落进 B）。

## 关键 trade-off

| 选择 | 备选 | 为什么这么选 |
| --- | --- | --- |
| 预计 / 实际**两张表** | 一张表两个金额列 | 后者答不出「规则定错了 vs 平台多扣了」（仓库 V226/V232 既有先例） |
| 差异**查询期**算 | 落库一个差异列 | 落库的会变成「第三个真相」，规则一改就过期 |
| 上游 `/estimate` 结果直取 | 本服务重算费率 | 重算 = 第二个真相；规则改动会让差异变成假的 |
| SKU 级差异用 **CTE** | 相关子查询 | 相关子查询引用外层未分组列 ⇒ PostgreSQL 直接拒绝 |
| 前端**不重算**差异率 | 前端算一遍 | 浮点误差 + 界面与报表对不上（13.1.1.1 已定这条纪律） |
| api-proxy **新增独立元素** | 改 13.1.1.1 那条正则 | 不动别人车道登记的分支；两条正则的命名空间互不相交 |

## 涉及文件

### 迁移
- `backend/services/reporting/src/main/resources/db/migration/V379__expense_variance.sql`

### 后端（`backend/services/reporting/src/main/java/com/starcon/reporting/`）
- `ReportingExpenseController.java`（新建，6 个端点）
- `ReportingExpenseService.java`（新建，读模型）
- `ReportingExpenseModels.java`（新建，读模型契约）
- `ReportingExpenseRefreshService.java`（新建，五路上游拉取适配器）
- `ReportingExpensePort.java`（新建，catalog 只读端口）
- `ReportingSettlementPort.java`（新建，finance 只读端口）
- `ReportingAdBudgetPort.java`（新建，channel 只读端口）
- `ReportingProjectionModels.java`（改：新增 `ProjectionExpenseFact` + `EXPENSE_FACT` 事件类型 + 兼容构造）
- `ReportingProjectionService.java`（改：费用事实双表 upsert + 重建时清空）
- `ReportingRefreshService.java`（改：接入费用差异五路上游）

### 后端测试
- `backend/services/reporting/src/test/java/com/starcon/reporting/ExpenseVarianceIntegrationTest.java`（14 个用例）
- `backend/services/reporting/src/test/java/com/starcon/reporting/ExpenseVarianceTenancyIntegrationTest.java`（3 个用例）

### 前端
- `apps/web/src/app/analytics/profit/expense-variance/page.tsx`（新建）
- `apps/web/src/components/analytics/expense-variance-model.ts`（新建）
- `apps/web/src/components/analytics/expense-variance-model.test.ts`（新建）
- `apps/web/src/components/analytics/expense-variance-workspace.tsx`（新建）
- `apps/web/src/app/api/reporting/expense-variance-allowlist.test.ts`（新建）
- `apps/web/src/lib/api-proxy.ts`（改：新增一条 `reporting/`（expense-variance）元素）

### 契约 / 台账 / 日志
- `packages/api-contracts/src/contracts/reporting.ts`、`packages/api-contracts/src/reporting.openapi.json`（codegen 重生成）
- `docs/product/capability-blueprint/module-progress.md`（只改 13.4.2.2 那一行）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | ✅ |
| L0 类型 | `pnpm typecheck` | ✅ 3 packages |
| L1 模块 | `./mvnw -pl services/reporting -am test -Dtest='ExpenseVariance*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ **17 tests / 0 failures / 0 errors** |
| 前端单测 | `pnpm vitest run apps/web/src/components/analytics/expense-variance-model.test.ts apps/web/src/app/api/reporting/expense-variance-allowlist.test.ts` | ✅ 21 tests |
| **全量 allowlist 回归**（改了 `api-proxy.ts`） | `pnpm vitest run apps/web/src/app/api` | ✅ **249 files / 1406 tests** |
| Lint | `pnpm lint` | ✅（残留 warning 都在未改动的既有文件里） |
| 契约可重现 | 重跑 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | ✅ 与交付版**逐字节一致** |

## 后续待办

- **13.4.2.1 成本结构**（同 L3 13.4.2 的兄弟格）：本模块的**预计 / 实际两张事实表**正是它要用的输入，
  两边共用同一批事实，**不要各建一套**。
- **13.4.1.1 多模式利润报表 / 13.4.1.2 SKU 利润**：读同一批费用事实，按「预计 vs 实际」两个口径分别进利润表。
- **13.4.3.1 利润预警**：差异率是预警的输入之一（阈值配置不在本模块）。
- **5.2.3 规则校准（下游）**：本模块只**产出**差异与建议，**不写任何规则**
  （费用规则的写入所有权在 catalog，跨服务写入被 AGENTS.md 禁止）。
- **上游待补的能力**（已写进能力声明，不是本模块的缺口）：
  ① 平台费 / 物流费的实际台账若能带 **SKU**，SKU 级差异就不止头程分摊一类；
  ② 12.3.1.2 结算明细若能带**销售额 / 数量基数**，那一侧的预计值就能重算出来。
