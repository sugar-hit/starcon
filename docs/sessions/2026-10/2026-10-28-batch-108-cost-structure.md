# 2026-10-28: 第 108 批 —— 13.4.2.1 成本结构

> 批次说明：本批由**崩溃恢复接手**。上一轮 worker 在 2026-10-28 19:03 因 Token Plan 用量上限被强杀
> （会话 `mvs_7cb9160e7252420aabb24aa88693c0c8`），**代码写完但未验证、未 commit**。
> 本轮接着往下做，未推翻重来；完整设计与复核记录见
> `docs/sessions/2026-10/2026-10-28-cost-structure.md`（worker 自写）。

## 模块 13.4.2.1 成本结构

**这个功能做什么**：把上游算好的成本按 **采购 / 头程 / 平台费 / 物流 / 广告 / 退货 / 其它** 七类归桶，
在**查询期**算出「各类成本各占多少」，并按 **SKU / 店铺 / 类目 / 平台 / 时间** 五个维度拆解，
给出**占比结构图 + 变化趋势 + 异常提示**。
用户视角：运营打开页面就能看到「这个月广告费占比突然从 6% 涨到 14%」这类**比绝对金额更有信号**的结论，
而不是只看到一堆金额。

**技术落地**：

- **迁移**：`backend/services/reporting/src/main/resources/db/migration/V399__cost_structure.sql`
  （232 行，**两张表、纯 CREATE TABLE、零 ALTER 既有表、零跨库外键**）
  - `reporting_cost_structure_fact` —— 成本事实：一行 = 某类成本 / 某个归属维度 / 某个期间 / 某个币种的一笔金额。
    `cost_category` 受 CHECK 约束（七类枚举）；维度列一律 `NOT NULL DEFAULT ''`（空串 = 该类成本不适用这个维度）；
    期间两列 `period_start` / `period_end`；金额 `numeric(24,4)`、数量 `numeric(19,6)`（禁浮点）；
    实际侧允许负数（退款 / 冲回是真实发生的费用变化）；幂等唯一索引的列序与 upsert 的 `on conflict` 逐字一致。
  - `reporting_cost_structure_rule` —— 租户级异常阈值规则表（`FIXED` 固定百分点 / `STDDEV` 基线占比标准差若干倍，
    外加最少历史期数与判定方向 `BOTH` / `UP` / `DOWN`）。
  - 两表均启用 **RLS**（`ENABLE ROW LEVEL SECURITY` + isolation policy），`tenant_id NOT NULL`。

- **端点**：`ReportingCostStructureController`（`@RequestMapping("/reporting/cost-structure")`）共 **7 个**
  - `GET /capabilities` —— 本期支持哪些成本类别 / 哪些维度 / 分母口径有哪些
  - `GET /structure` —— 占比结构（按维度拆解）
  - `GET /trend` —— 变化趋势（环比 / 同比）
  - `GET /anomalies` —— 异常提示（按规则表判定）
  - `GET /rules` · `PUT /rules/{costCategory}` —— 租户级阈值规则的读 / 改
  - `POST /refresh` —— 拉取上游事实并归桶（幂等 upsert）

- **页面**：`/analytics/profit/cost-structure`
  （`apps/web/src/app/analytics/profit/cost-structure/page.tsx` +
  `apps/web/src/components/analytics/cost-structure-workspace.tsx` +
  `cost-structure-model.ts` 纯函数模型层）
  筛选维度：期间、成本类别、归属维度（SKU / 店铺 / 类目 / 平台）、分母口径（总成本 / 收入）；
  **加载态 / 错误态 / 空态三态齐全**；**前端不重算任何比率**（比率一律服务端算，前端只渲染）。

- **测试**：
  - `CostStructureIntegrationTest` —— **17 例**：七类归桶、占比与分母口径、基线来自真实历史投影、
    「不可比 ≠ 0」、幂等重放、跨币种不相加、异常判定、类目映射不到时留空、`dataUpdatedAt` 空表给 null。
  - `CostStructureTenancyIntegrationTest` —— **3 例**：**租户隔离三条断言齐全**
    （两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户），Testcontainers 真实 PostgreSQL 16。
  - 前端：`cost-structure-model.test.ts` **21 例**；
    `cost-structure-allowlist.test.ts` —— 共享 `api-proxy.ts` 白名单的**双向契约派生**回归
    （含「不得误伤兄弟车道路由」的断言）。
  - L1 实测：**`Tests run: 20, Failures: 0, Errors: 0` —— BUILD SUCCESS**。

**关键设计决策**：

1. **七类成本必须是「一张表的一个枚举列」，不是七张表** —— 拆成七张表，占比就变成「行数比」，那不是占比。
   一张表 + CHECK 约束的枚举列 ⇒ 同一窗口内七类**互相可加、可比**。
2. **「算不出来」与「等于 0」在三层分开**（本模块最核心的一条）：
   采集层「上游没事实 ⇒ 不写行」（而不是写一行 0）；查询层「算不出来 ⇒ `null` ⇒ 不可比」；
   页面层「`null` 渲染成「—」+ 服务端给的原因」。
   **「占比 0.00%」与「这一格没算出来」在报表里长得一模一样，含义却完全相反** —— 蓝图加粗句要消灭的正是这个。
3. **归属维度列用空串而不是 NULL** —— PostgreSQL 的 UNIQUE 把 NULL 视为互不相等，
   「平台级」行会插出第二条同样的行 ⇒ 幂等键不成立。
4. **期间用两列**（`period_start` / `period_end`）—— 逐日事实两列相同，
   但「一个费用池覆盖一段期间」的费用归集条目，单列表达不了。
5. **实际侧允许负数** —— 退款 / 冲回是真实发生的费用变化；截成 0 等于把「平台退了这笔钱」写成「平台没收这笔钱」。
6. **异常阈值不写死在代码里，落成租户级规则表** —— 阈值写死就不能按租户调（不同品类波动幅度差一个数量级），
   也不能随历史数据变；`UP` 方向必须在默认可用集里（蓝图点名的是「广告占比突然**上升**」）。
7. **基线来自真实历史投影数据**，且**分母口径写在响应里**（`denominatorCaliber` / `denominatorNote`）——
   「占总成本」与「占收入」混着读等于骗人。
8. **类目维度是本模块自己补的，映射不到就留空串** —— 不猜一个类目。
9. **`dataUpdatedAt` 空表给 `null`，不是 `-infinity`** —— 用 `-infinity` 当哨兵会让页面显示「数据截至 -infinity」，
   那是一个长得像时间的假值。
10. **多币种绝不相加** —— 每行带自己的币种，聚合键里始终带着币种。

**本轮接手时的实际改动**（上一轮实现主体经逐文件复核后**予以保留**）：

- **① 收入口径指标码改引用 `ReportingMetricCatalog.SALES_AMOUNT`**（口径单一来源）。
  原实现写的是字面量 `"SALES_AMOUNT"`，而 reporting 服务里这个口径码**只有一处定义** ——
  字面量副本是**第二个真相**：上游改了口径码，这里会静默查不到数据（占比恒为「算不出来」）且无任何报错。
  与同服务 `ReportingSalesTrendService` / `ReportingTimePatternService` / `ReportingTargetMetricCatalog` 写法一致。
- **② 台账行**：`未开发` → `已交付` + 补齐证据（只改自己那一行的两格，未重排整表）。
- **③ 会话日志**：新建 `docs/sessions/2026-10/2026-10-28-cost-structure.md`（上一轮完全缺失）。
- **④ commit**：两个 commit —— `523be52a feat(reporting):` 收拢实现主体，
  `5c9d8717 fix(reporting):` 口径引用 + 台账 + 会话日志。

**验证输出（真实数字）**：

| 闸 | 结果 |
| --- | --- |
| `./mvnw -q compile` | `MVNW_EXIT=0`（末次在**已提交树**上复跑仍为 0） |
| `pnpm typecheck --force` | 3 packages，**`Cached: 0 cached, 3 total`** ⇒ **真实 tsc 重新执行**（首次命中 turbo 缓存且回放日志指向别的 worktree，故强制重跑） |
| L1 模块闸 | `Tests run: 20, Failures: 0, Errors: 0` · BUILD SUCCESS |
| 前端模型单测 | 21 passed |
| **reporting 全量 allowlist 回归** | **11 files / 67 tests passed**（改了共享 `api-proxy.ts` ⇒ 必须跑） |
| `pnpm lint` | `LINT_EXIT=0`（warning 全在未改动的既有文件） |
| 契约 | `OpenApiContractTest` 1 passed；**重新 codegen 与已提交契约逐字节一致** ⇒ 证明未手改 |

**迁移版本号无冲突**：集成头 `reporting` 原已用到 V398，V399 为空位；全仓 grep 无同名迁移文件。
**静态预检（互相破坏形状）**：V399 的 ① `ADD COLUMN` 无 `DEFAULT` **0** · ② `SET NOT NULL` **0** ·
③ `ADD CONSTRAINT…CHECK` **0** ⇒ 纯 `CREATE TABLE`，两种「必然违约」形状不可能发生。

**PG 容器**：本车道验证期间同时存活 **1 个** `postgres:16-alpine` + **1 个** ryuk；
测试 JVM 退出后两者**均被 ryuk 回收**（结束前 `docker ps` 计数归零）。

**已知边界（非本模块缺口，已写进能力声明）**：平台费 / 物流费无 SKU 级结构；退货无店铺与 SKU 维度；
头程按生效日归属、不跨期拆分；上游尚未产出成本结构型 outbox 事件，当前事实走 `POST /refresh` 拉取路径
（推路径已就绪，共用同一套幂等键）。

**commit**：`523be52a` + `5c9d8717`（分支 `feat/13-4-2-1`）· **并入集成头** `c6bfc32f merge(13.4.2.1): 成本结构`
