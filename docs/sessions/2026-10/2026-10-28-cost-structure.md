# 2026-10-28: 13.4.2.1 成本结构

## 摘要

卷 13「利润与经营分析」L3 13.4.2 成本拆解下的 **13.4.2.1 成本结构**落地：
把上游算好的成本事实归到**七个可相加的桶**（采购 / 头程 / 平台费 / 物流 / 广告 / 退货 / 其它），
在**查询期**算出**占比结构图 + 趋势 + 异常提示**，并按 **SKU / 店铺 / 类目 / 平台 / 时间**五个维度拆解。

本条车道是**崩溃恢复接手**：上一轮 worker 在 2026-10-28 19:03 因 Token Plan 用量上限被强杀
（会话 `mvs_7cb9160e7252420aabb24aa88693c0c8`，报「已达到 Token Plan 用量上限 (2056)」），
**代码写完了、但没验证、没 commit**。本轮**接着往下做**，没有推翻重来：
先跑 L0 编译闸定位断点，再逐项复核实现、补齐验证、收尾台账与会话日志、commit。

**复核结论：上一轮的实现主体是完整且自洽的**（迁移 / 后端五件 / 测试两件 / 前端四件都在），
本轮实际改动只有一处代码级修正 + 两项缺失交付物（台账行、会话日志）+ commit。

## 决策 / 结论

### 1. 七类成本必须是**一张表的一个枚举列**，不是七张表

蓝图职责句把成本拆成七类。若拆成七张表，占比就变成「七张表的行数比」——那不是占比。
⇒ `reporting_cost_structure_fact` 一张表 + `cost_category` 受 CHECK 约束的枚举列，
同一笔窗口内七类成本**互相可加、可比**。

七类与上游口径的对应（**只归桶、不重算**）：

| 成本类别 | 口径来源 | 上游 |
| --- | --- | --- |
| `PURCHASE` | 采购成本（出库成本） | 12.4.1.2 成本计算条目（销售出库） |
| `HEAD_MILE` | 头程分摊 | 5.2.1.3 成本分摊明细（逐 SKU） |
| `PLATFORM_FEE` | 平台费（佣金 / 平台服务费） | 5.2.3.1 实际台账 + 12.3.1.2 佣金 |
| `LOGISTICS_FEE` | 物流费（FBA / 运费） | 5.2.3.2 实际台账 + 12.3.1.2 FBA 费用 |
| `AD_FEE` | 广告费 | 12.3.1.2 广告费明细 |
| `RETURN` | 退货（退款 / 退货成本） | 11.1.1.1 售后单（按受理时刻） |
| `OTHER` | 其它（订阅 / 人员 / 办公） | 12.4.2.1 费用归集条目 |

### 2. 「算不出来」与「等于 0」在**三层**分开

这是本模块最核心的一条，也是蓝图那句加粗（「成本结构变化比绝对值更有信号」）真正要消灭的现象。

| 层 | 做法 |
| --- | --- |
| 采集 | 上游没有事实 ⇒ **不写行**（而不是写一行 0） |
| 查询 | 占比 / 环比差 / 同比差 / 基线占比算不出来 ⇒ `null` ⇒ **不可比**，不是 0% |
| 页面 | `null` 渲染成「—」+ 服务端给的原因，**绝不显示成 0.00 / 0%** |

「占比 0.00%」与「这一格没算出来」在报表里长得一模一样，含义却完全相反。

### 3. 归属维度列用**空串**而不是 NULL

七类成本的归属维度本来就不一样（上游事实决定的，不是设计偏好）：
采购 → SKU；头程 → SKU；平台费 → 平台 + 站点 + 店铺；物流 → 渠道 + 承运商 + 运输方式；
广告 → 平台 + 店铺；退货 → 店铺；其它 → 期间（无明细维度）。

⇒ 维度列全部显式列出，`NOT NULL DEFAULT ''` = 「该类成本不适用这个维度」。
PostgreSQL 的 UNIQUE 把 NULL 视为互不相等，「平台级」行会插出第二条同样的行 ⇒ 幂等键不成立。

### 4. 期间用**两列**（`period_start` / `period_end`）

逐日事实两列相同；但**费用归集条目**是「一个费用池覆盖一段期间」⇒ 单列表达不了。

### 5. 实际侧**允许负数**

退款 / 冲回是真实发生的费用变化，截成 0 等于把「平台退了这笔钱」写成「平台没收这笔钱」。

### 6. 异常阈值**不写死在代码里**，落成**租户级规则表**

蓝图关系句说「成本结构变化比绝对值更有信号」⇒ 异常判定必然要阈值。阈值写死的问题：
既不能按租户调（不同品类波动幅度差一个数量级），也不能随历史数据变。

⇒ `reporting_cost_structure_rule`：固定百分点（`FIXED`）/ 基线占比标准差的若干倍（`STDDEV`）两种口径，
外加最少历史期数与判定方向（`BOTH` / `UP` / `DOWN`——蓝图点名的是「广告占比突然**上升**」，
所以 `UP` 必须在默认方向的可用集里）。服务层只播种一份**默认**规则，可在接口上改。

### 7. 基线来自**真实历史投影数据**，分母口径写在响应里

- 基线 = 前 N 期**同口径占比**的均值（N = 该类成本的 `min_history_periods`），**不是**写死的基线常量。
- 占比的分母口径（`TOTAL_COST` 总成本 / `REVENUE` 收入）**写在响应里**（`denominatorCaliber` / `denominatorNote`）——
  「占总成本」与「占收入」是两个口径，混着读等于骗人。

### 8. 类目维度是**本模块自己补的**，映射不到就留空

上游成本事实里没有类目，由 catalog 的 SKU 档案映射得到；**映射不到就留空串，不猜一个类目**。

### 9. `dataUpdatedAt` 空表 ⇒ `null`，不是 `-infinity`

用 `-infinity` 当哨兵时，空表会算出一个「比公元无限还早」的时间戳，
页面上会显示成「数据截至 -infinity」——那是一个长得像时间的假值。宁可给 null（页面写「尚未投影任何成本事实」）。

### 10. 多币种绝不相加

每一行都带自己的币种，聚合键里始终带着币种。`currency` 过滤只接受 ISO alpha-3。

## 接手上一轮半成品：复核了什么 / 改了什么

上一轮的**设计与实现主体是对的，予以保留**（逐文件复核）：

- **V399 迁移**（232 行，注释块写满了设计推导）：两表拆分、RLS isolation policy、
  幂等 identity 唯一索引（列序与 upsert 的 `on conflict` 逐字一致）、空串维度列、`numeric(24,4)` / `numeric(19,6)`。
- **`ReportingCostStructurePort`**：只补两路上游（catalog `GET /catalog/skus` 拿 SKU→类目、
  finance `GET /finance/cost-collections/entries` 拿「其它」这一类），
  八路上游里六路已被既有端口覆盖 ⇒ 不重复造端口。
- **`ReportingCostStructureRefreshService`**：七路上游拉取 + 归桶，幂等 upsert，
  期间归属规则逐条写明（头程按**生效日**、退货按**受理时刻**、结算侧费用**取负**才是费用）。
- **`ReportingCostStructureService`**：归桶 / 算占比 / 算基线 / 判异常；`dimensionExpression` 是白名单常量再拼进 SQL。
- **测试两件**：三条租户断言齐全（见下），17 个业务用例覆盖了「不可比 ≠ 0」「幂等重放」「跨币种不相加」等。
- **前端四件**：纯函数模型层 + 真实工作区（加载 / 错误 / 空三态齐全），前端**不重算任何比率**。
- **api-proxy**：**新增一条独立 `services[]` 元素**（`prefix: 'reporting/'`），**既有条目一字未改、也不重排**。

本轮**实际改动**：

### ① 收入口径的指标码改引用 `ReportingMetricCatalog.SALES_AMOUNT`（口径单一来源）

原实现把收入口径的指标码写成字面量 `"SALES_AMOUNT"`，而 reporting 服务里这个口径码
**只有一处定义**（`ReportingMetricCatalog.SALES_AMOUNT`）。字面量副本是**第二个真相**：
上游改了口径码，这里会静默查不到数据（占比恒为「算不出来」），而且没有任何报错。

⇒ 改为引用常量。**与同服务的 `ReportingSalesTrendService` / `ReportingTimePatternService` /
`ReportingTargetMetricCatalog` 写法一致**（口径唯一是卷 13 的既有纪律）。

### ② 补上两项上一轮没来得及做的交付物

- **台账行**：`docs/product/capability-blueprint/module-progress.md` 的 13.4.2.1 那一行
  从 `未开发` 改为 `已交付` 并补齐证据列（**只改自己那一行的「状态」「证据」两格**）。
- **会话日志**：本文件。

### ③ 验证补齐（上一轮没跑到验证这一步）

| 闸 | 结果 |
| --- | --- |
| L0 编译（backend） | ✅ `MVNW_EXIT=0`，**没有半截方法 / 缺 import** |
| L0 类型（前端） | ✅ 但第一次是 turbo **cache hit**（命中的是别的 worktree 的缓存）⇒ 用 `pnpm typecheck --force` 重跑真编译 |
| L1 模块 | ✅ **20 tests / 0 failures / 0 errors**（17 业务 + 3 租户） |
| 前端模型单测 | ✅ **21 tests** |
| **全量 allowlist 回归**（改了 `api-proxy.ts`） | ✅ **11 files / 67 tests** |
| 契约可重现 | ✅ 重跑 codegen 与交付版**逐字节一致**（对着**重新导出的** spec） |
| 契约导出 | ✅ `OpenApiContractTest` 1 test 通过 |

## 上游依赖自检结论：**全部已交付，无需 BLOCKED**

发车前逐条核对集成头台账（`module-progress.md`）：

| 上游 | 状态 |
| --- | --- |
| 2.3.2.1 经营模式 | ✅ 已交付 |
| 12.4.2.1 费用归集 | ✅ 已交付 |
| 12.4.2.2 分摊规则 | ✅ 已交付 |
| 12.3.1.2 交易明细 | ✅ 已交付 |
| 5.2.1.3 成本分摊 | ✅ 已交付 |
| 5.2.3.1 平台费用 | ✅ 已交付 |
| 5.2.3.2 物流与头程费用 | ✅ 已交付 |
| 13.4.2.2 费用差异 | ✅ 已交付 |

另核对了收入口径分母依赖的 `SALES_AMOUNT`：`ReportingRefreshService` 以
`scope_type = 'COMPANY'` 写入 `reporting_metric_fact`（`fact(...)` 帮手方法），
本模块的分母查询条件与生产实际**对得上**，不是只在测试夹具里成立。

## 多租户隔离：三条断言都实跑

`CostStructureTenancyIntegrationTest`（真实 `postgres:16-alpine` + FORCE RLS，运行角色 `NOBYPASSRLS`）：

1. **两租户互不可见** —— A 只看得到自己的 100，B 只看得到自己的 50；
   再加一格「按主键直查 B 的行也拿不到」。
2. **伪造租户上下文无效** —— 事务外没有可沿用的默认租户；事务内绑定值精确等于可信参数、提交后立刻清空；
   **HTTP 边界**带租户 A 的合法会话 + 伪造的 `X-Tenant-Id`（租户 B）⇒ 返回的仍是 A 的数据，
   反向（会话 B、伪造头 A）也成立；写侧 `WITH CHECK` 挡住「绑定 A 却往 B 名下行写」。
3. **连接池归还后不串租户** —— **连接池固定为 1 条连接**（池大于 1 时「没串」可能只是碰巧换了条干净连接），
   连续 5 轮 A / B 交替读取，事务外一律看不到任何行。

## 关键 trade-off

| 选择 | 备选 | 为什么这么选 |
| --- | --- | --- |
| **一张表 + 枚举列** | 七张表各一张 | 七张表算出来的是「行数比」，不是占比 |
| 占比在**查询期**算 | 落库一个占比列 | 落库的会变成「第三个真相」，上游口径一改就过期 |
| 阈值落**租户级规则表** | 写死在代码里 | 不能按租户调、也不能随历史波动伸缩（`STDDEV` 口径） |
| 基线用**前 N 期真实占比** | 写死基线常量 | 季节性品类与平稳品类的基线含义完全不同 |
| 维度列用**空串** | NULL | PostgreSQL UNIQUE 把 NULL 视为互不相等 ⇒ 幂等键不成立 |
| 实际侧**允许负数** | 截成 0 | 退款 / 冲回是真实发生的费用变化 |
| 类目**映射不到就留空** | 猜一个类目 | 猜出来的类目是假数据，比没有更糟 |
| 前端**不重算**任何比率 | 前端算一遍 | 浮点误差 + 界面与报表对不上（13.1.1.1 已定这条纪律） |
| api-proxy **新增独立元素** | 改 13.4.2.2 那条正则 | 不动别人车道登记的分支；两条正则命名空间互不相交 |

## 涉及文件

### 迁移
- `backend/services/reporting/src/main/resources/db/migration/V399__cost_structure.sql`（新建，232 行）

### 后端（`backend/services/reporting/src/main/java/com/starcon/reporting/`）
- `ReportingCostStructureController.java`（新建，303 行，6 个端点 + 规则 PUT）
- `ReportingCostStructureModels.java`（新建，247 行，读模型契约 + `CostCategory` 枚举）
- `ReportingCostStructurePort.java`（新建，144 行，catalog / finance 只读端口）
- `ReportingCostStructureRefreshService.java`（新建，607 行，七路上游拉取 + 幂等落库）
- `ReportingCostStructureService.java`（新建，1112 行，读模型；**本轮改了指标码引用**）

### 后端测试
- `backend/services/reporting/src/test/java/com/starcon/reporting/CostStructureIntegrationTest.java`（新建，17 个用例）
- `backend/services/reporting/src/test/java/com/starcon/reporting/CostStructureTenancyIntegrationTest.java`（新建，3 个用例）

### 前端
- `apps/web/src/app/analytics/profit/cost-structure/page.tsx`（新建）
- `apps/web/src/components/analytics/cost-structure-model.ts`（新建，纯函数层）
- `apps/web/src/components/analytics/cost-structure-model.test.ts`（新建，21 个用例）
- `apps/web/src/components/analytics/cost-structure-workspace.tsx`（新建，真实工作区）
- `apps/web/src/app/api/reporting/cost-structure-allowlist.test.ts`（新建，白名单**双向**断言）
- `apps/web/src/lib/api-proxy.ts`（改：新增一条 `reporting/`（cost-structure）元素，既有条目一字未改）

### 契约 / 台账 / 日志
- `packages/api-contracts/src/contracts/reporting.ts`、`packages/api-contracts/src/reporting.openapi.json`（codegen 重生成，**未手改**）
- `docs/product/capability-blueprint/module-progress.md`（只改 13.4.2.1 那一行的「状态」「证据」两格）
- `docs/sessions/2026-10/2026-10-28-cost-structure.md`（本文件）

## 端点一览（挂 `/reporting/cost-structure`）

| 端点 | 权限 | 说明 |
| --- | --- | --- |
| `GET /capabilities` | `reporting.cost-structure.read` | 能力声明（七类成本来源 / 哪些确实拆不出来） |
| `GET /structure` | `reporting.cost-structure.read` | 占比结构表 + 环比 / 同比基线 + 异常提示 |
| `GET /trend` | `reporting.cost-structure.read` | 占比趋势（按期间粒度） |
| `GET /anomalies` | `reporting.cost-structure.read` | 异常提示清单 |
| `GET /rules` | `reporting.cost-structure.read` | 异常判定阈值规则 |
| `PUT /rules/{costCategory}` | `reporting.cost-structure.write` | 改某一类成本的阈值（**只改自己的规则表**） |
| `POST /refresh` | `reporting.projection.write` | 投影摄取（拉七路上游 → 归桶 → 幂等落库） |

租户**不来自任何请求头**：先向 identity 确认已认证成员关系与权限，
再在一个事务里绑定 `app.current_tenant`，由 RLS 在 DB 层强制隔离。

## 后续待办

- **13.4.2.2 费用差异**（同 L3 的兄弟格，已交付）：本模块**只读**上游分摊 / 归集结果，
  **不重算分摊**；两张模块的输入是同一批上游事实，**不要各建一套**。
- **13.4.1.1 多模式利润报表 / 13.4.1.2 SKU 利润**：引用本表的七类成本口径拼利润表。
- **13.4.3.1 利润预警**：成本结构异常是预警的输入之一 —— 预警阈值配置不在本模块。
- **上游待补的能力**（已写进能力声明，**不是本模块的缺口**）：
  ① 平台费 / 物流费的 SKU 级结构算不出来（上游实际费用台账只带平台 / 站点 / 渠道维度）；
  ② 退货没有店铺与 SKU 维度（售后单给的是金额 / 数量 / 受理时刻）；
  ③ 头程分摊按**生效日**归属，跨期分摊不拆分、也不向后平移。
- **推事件路径尚未接上**：上游尚未产出成本结构型 outbox 事件 ⇒ 现在的事实来自
  `POST /reporting/cost-structure/refresh` 的拉取路径（推路径已就绪，与拉共用同一套幂等键）。
