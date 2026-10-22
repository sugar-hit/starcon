# 16.1.1.2 绩效指标监控（阶段 D7，L3 锚点 16.1.1）

- **日期**：2026-10-22
- **车道**：`feat/16-1-1-2`（worktree `integration/.worktrees/16.1.1.2`）
- **迁移版本**：`V304__account_health_performance.sql`（channel 服务，预分配版本号）
- **蓝图原文**（`docs/product/capability-blueprint/16-compliance-and-risk.md` 91-97 行）：
  > 职责：监控各平台核心绩效指标的趋势与阈值：订单缺陷率、迟发率、取消率、有效追踪率、响应时间、账户评级，支持自定义阈值预警与趋势预测（是否会跌破阈值）。
  > 输入：指标时间序列；阈值配置；预测模型参数。
  > 输出：指标趋势图 + 阈值预警 + 跌破预测。
  > 关系：上游 = 16.1.1.1；下游 = 1 工作台预警、9 订单履约（迟发预警前置）。**预测跌破比已跌破更有价值**——提前几天知道就能提前改。

## 决策摘要

### 1. 🔴 六项指标分属两个来源：**互补切分，不 ALTER 上游**

蓝图列了六项指标，但上游 16.1.1.1 的 `account_health_metric.metric_code` 的 CHECK
**只钉死了四个码**（ODR / 迟发率 / 取消率 / 有效追踪率），而本批约定**零 ALTER 任何既有表**
—— 改那个 CHECK 就等于破坏 16.1.1.1 已交付的契约与它的既有测试。

处置：**按指标划分归属，两张表的取值域互补且不重叠**。

| 指标                               | 数据在哪                                          | 谁拥有   |
| ---------------------------------- | ------------------------------------------------- | -------- |
| ODR / 迟发率 / 取消率 / 有效追踪率 | `account_health_metric`（上游，**本模块只读**）   | 16.1.1.1 |
| 响应时间 / 账户评级                | `account_health_performance_metric`（本模块自有） | 16.1.1.2 |

> 选**互补切分**而不是「六个码都塞进本模块的表」：后者会让 ODR 等四项**出现第二个真相来源**
> （上游表里有一份、本表里又有一份），两者迟早打架，而打架时没人说得清哪份对。
> 互补切分让每个指标**有且只有一个来源**，并用 CHECK 把这件事钉死。

这个「双来源」事实在**三处如实表达**，不假装是同一个来源：

- 能力声明的 `metricCatalog`（每项指标的 `ownerModule` / `sourceModule`）；
- 每条趋势序列的 `sourceModule`（上游 16.1.1.1 / 本模块自有）；
- 页面上每条序列的「来源」徽章 + 能力声明里的六项指标归属清单。

### 2. 🔴 量纲不混：比值 / 小时 / 星级是三种不同的量

上游那四项是 **0..1 比值**（`0.0280` = 2.8%），但**响应时间**是小时、**账户评级**是星级。
把它们塞进同一套 `numeric(5,4)` + `0..1 CHECK` 会立刻失真（「24 小时」被当成 `0.24`）。

处置：**每个指标带 `unit`**（`RATIO` / `HOURS` / `SCORE`），DB 层用 CHECK 钉住
「量纲必须与指标码相符」+ 各自的取值范围；DB 层与前端层都按 `unit` 格式化，
**绝不把「24.00 小时」渲染成「2400.00%」**。

> 账户评级的 **0..5 星制是本模块的默认口径，不是平台事实**（各平台评级体系不同），
> 与 16.1.1.1 的内置默认阈值同一纪律：写明是默认值，可按租户覆盖。

### 3. 🔴 趋势是**区间口径**，不是瞬时点

上游一行 = **一个区间的一个指标**（`period_start ~ period_end`），**不是日粒度点**。
序列按 `period_end` 排序聚起，响应里带 `granularity=PERIOD_RANGE` 与口径说明，
**不把区间值伪装成每日值**。

### 4. 🔴 「量不到」既不是 0，也不是通过

NULL 的点**不进已知数、不算通过**，并产出一条 **UNKNOWN 预警凭据**（量不到本身就是需要
关注的事 —— 你不知道自己会不会掉号）。均值**分母只算量到的点**且分母写清楚；环比对比的是
序列里**上一个周期**，上一个周期量不到 ⇒ **环比不可知**（不是「没变化」）。

### 5. 🔴 预警阈值用哪条阈值，写清楚；改阈值不追溯改变历史预警

解析顺序（服务端算，前端只显示结论与依据）：

1. `account_health_performance_alert_rule`（本模块自有，租户自定义）⇒ `TENANT_CUSTOM`
2. `account_health_threshold`（16.1.1.1 的租户覆盖，仅四个码有）⇒ `UPSTREAM_TENANT_CUSTOM`
3. 模块内置默认 ⇒ `BUILTIN_DEFAULT`

**绝不改 `account_health_threshold`**（那是 16.1.1.1 的规则表，改它会破坏它的 `rule_version`
触发器语义与既有测试）。每条预警凭据**冻结当时用到的阈值快照**（`thresholds` jsonb），
改阈值**不追溯改变历史预警**；改阈值**必然推进 `rule_version`**（触发器钉住，改值不推进
版本或版本回退直接拒写）。

### 6. 🔴 预测要有明确、可解释、可复核的口径；**绝不给一个假预测**

- 取窗口内最近 N 个周期，对**量到的**点做**最小二乘线性回归**（x = 周期序号，y = 值）；
- **量不到的周期占位但不进回归**（序号不压缩 —— 压缩掉会让斜率算错）；
- 以**最后一个量到的周期**为起点向后推 k 个周期，看第几个周期沿危险方向越线；
- 用到的周期明细 / 点数 / 斜率 / 预测值**全部写进凭据**；
- 已知点数不足 3 个 ⇒ **`CANNOT_PREDICT` + 原因**（点数不足 / 全是量不到）；
- 已经越线的报 **`ALREADY_BREACHED`（那是现状，不是预测）**，趋势远离的报 `NO_BREACH`。

> **预测跌破比已跌破更有价值**（蓝图加粗的那句）：所以 `WILL_BREACH` 在页面的统计卡里排第一。

### 7. 🔴 下游 1 / 9 未交付 ⇒ 一律不编造

「1 工作台预警」与「9 订单履约（迟发预警前置）」当前**都未交付**：能力声明里如实标
`available=false` + `ownerModule` + 影响说明；api-proxy 白名单里**刻意不放行**对应的形状
（`workbench-alerts` / `fulfillment-alerts`），**放行一个不存在的入口比 404 更糟**。

### 8. 凭据是追加式的

预警凭据与预测凭据都是 **append-only**：触发器 `..._immutable` + `..._no_delete` 钉住，
`UPDATE` / `DELETE` 被 DB 拒。**已知且未命中的指标不产预警凭据**（没有预警就没有凭据）。

## 关键 trade-off

| 选择                                                | 代价                             | 收益                                                                                           |
| --------------------------------------------------- | -------------------------------- | ---------------------------------------------------------------------------------------------- |
| 六指标**互补切分**两个来源                          | 两张表并存，读取时要多走一次分支 | 每个指标**有且只有一个真相来源**；零 ALTER 上游                                                |
| 阈值解析**两层**（本模块 > 上游 > 默认）            | 同一条指标有两个可能的规则来源   | 不改上游规则表；预警阈值与风险等级阈值**各管各的**                                             |
| 预测用**线性最小二乘**                              | 只抓线性趋势，拐点会失真         | **口径可解释可复核**（写下用了哪几个周期、斜率多少）；比黑盒模型更可审计                       |
| 预警凭据**按指标一行**（不是一个评估一行）          | 一次评估写多行                   | 每条预警自带**该指标**的阈值快照，列表可按指标筛选，不必解 JSON                                |
| 新增 `GET /shops` 端点                              | 多一个端点                       | 「跑评估 / 跑预测 / 录入指标」都是**按店铺**的动作，没有真实店铺清单时**第一次录入都无从下手** |
| api-proxy **新增独立元素**（不并既有 channel 分支） | services[] 多一条                | 并行车道同时改同一文件时 git 能干净求并（结构性，retry 不会自愈）                              |

## 文件清单

**迁移（channel）**

- `backend/services/channel/src/main/resources/db/migration/V304__account_health_performance.sql`
  —— 4 张新表（`account_health_performance_metric` / `_alert_rule` / `_alert` / `_forecast`），
  零 ALTER、零外键、ENABLE + FORCE RLS、GRANT channel_app / REVOKE starcon_app。

**后端（channel，包 `com.starcon.channel`）**

- `PerformanceMetricModels.java` —— 传输模型（含 `PerformanceMetricShop` / `PerformanceMetricShopPage`）。
- `PerformanceMetricRules.java` —— 规则层（阈值解析、预警判定、跌破预测），**不碰 DB / HTTP，可单测**。
- `PerformanceMetricService.java` —— jOOQ 显式 SQL + 聚合 + 落库。
- `PerformanceMetricController.java` —— `/channel/performance-metrics/**`。

**后端测试**

- `PerformanceMetricRulesTest.java`（19 例）、`PerformanceMetricIntegrationTest.java`（17 例）、
  `PerformanceMetricTenancyIntegrationTest.java`（3 例）。

**前端**

- `apps/web/src/components/performance-metrics/performance-metrics-model.ts` + `.test.ts`（23 例）
- `apps/web/src/components/performance-metrics/performance-metrics-workspace.tsx`
- `apps/web/src/app/compliance/metrics/page.tsx`（路由 `/compliance/metrics`）
- `apps/web/src/app/api/channel/performance-metrics-allowlist.test.ts`（6 例）
- `apps/web/src/lib/api-proxy.ts` —— **只新增一条** `services[]` 元素（锚点：`account-health` 之后）。

**契约（codegen 产物，只 channel 一份有改动）**

- `packages/api-contracts/src/channel.openapi.json`（+12 条路径，零删除零改写）
- `packages/api-contracts/src/contracts/channel.ts`（+20 个 DTO）

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md` —— **只改 `| 16.1.1.2 |` 那一行**的「状态」「证据」两格。
- 本文件。

## 验证

```
./mvnw -q compile                                    → exit 0
./mvnw -pl services/channel -am test -Dtest='PerformanceMetric*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
                                                    → 39 例 0 失败 0 错误 BUILD SUCCESS
  （另连跑上游 AccountHealth* 36 例确认新迁移未破坏 16.1.1.1 ⇒ 合计 75 例全绿）
pnpm typecheck                                       → 3/3 通过
pnpm vitest run apps/web/src/app/api                 → 177 文件 1022 例全绿（api-proxy 全量 allowlist 回归）
pnpm vitest run <本模块两个前端测试文件>                → 29 例全绿
pnpm exec eslint <本模块前端文件>                      → 零告警
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
                                                    → 只有 channel 一份有改动，其余五份零漂移
```

**api-proxy 首 token 零命中核对**（`.tmp/allowlist-check.cjs`，结果落 `.tmp/allowlist-check.txt`）：
既有全文里 `performance-metrics` 出现次数 = **0**；既有**全部 153 条** `services[]` 正则
（单行与多行都抽出来，注释里引用的正则已剔除）× 本模块 13 个 shape ⇒ **命中 0 处**；
既有 `account-health` 那条正则对本模块 13 个 shape 亦**零命中**（两者互不遮蔽）；
本条正则放行自己的 13 个形状、拒绝 9 个刻意不放行的形状、不误伤既有 channel 模块入口。

## 后续待办

- **1 工作台预警**（下游，未交付）：本模块的预警凭据只落在自己的表里，**没有**推送到任何
  工作台。等 1 交付后由那一侧来读 `account_health_performance_alert`。
- **9 订单履约 · 迟发预警前置**（下游，未交付）：迟发率的预警与跌破预测只在本模块内成立，
  **不会**自动改变履约侧的任何行为。
- **2.4 同步运行**（平台账号绩效适配器，未交付）：上游那四项指标目前只能**人工录入**
  （`source=MANUAL`），录入条目页面上标「人工录入」，不冒充平台同步。
- 本模块的预警阈值与 16.1.1.1 的风险等级阈值**故意是两套**：若将来要合并口径，
  需先决定「预警阈值」与「等级阈值」是不是同一件事，再动 `account_health_threshold`
  （它的 `rule_version` 触发器语义不能破）。
