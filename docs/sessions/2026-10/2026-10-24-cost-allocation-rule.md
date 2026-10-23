# 12.4.2.2 分摊规则（V354 · 阶段 D6 · 车道 12.4.2.2）

> 蓝图原文：`docs/product/capability-blueprint/12-finance.md` 的 **12.4.2.2 分摊规则**。
> 车道分支 `feat/12-4-2-2`，基线 `integration/module-dev` 的 `6a1b2fc0`。
> 迁移版本 **V354**（调度者预分配；同批另外三条车道是 V355 / V356 / V357，未触碰）。
> 上游 12.4.2.1「费用归集」已并入集成头（V351，四张表 `finance_cost_collection_*`）——
> 本模块的「待分摊费用池」就是它的 `finance_cost_collection_pool`。

## 决策摘要

### 1. 🔴 唯一必须恒成立的等式：**Σ分摊结果 = 被分摊总额**

蓝图关系句：「**分摊结果之和必须等于被分摊总额**（含舍入差处理），否则利润报表总额对不上
—— 这是财务最不能接受的错误。」

⇒ **三道保障**，缺一道都不算数：

| 层 | 做法 |
| --- | --- |
| 算法 | 向下取整 + 零头**补回去**（零头不消失） |
| 应用 | 写完结果行后**自己再算一遍** Σ，不等就抛异常回滚（不写出一张对不上的单） |
| 数据 | 批次 `CHECK(allocated_total = source_total)` + **延迟约束触发器**在提交时复核 Σ 结果行 |

集成测试里这条等式是**单独一条用例**断言的（并额外在库里 `sum(allocated_amount)` 复核一遍），
另有三条用例分别覆盖零头落到指定对象、基数不可得、撤销后重摊 —— 每一条都断言 Σ 仍等于总额。

### 2. 舍入差：**向下取整 + 补零头**，而不是「四舍五入后把差额补给某个对象」

后者有个致命处：差额可能为**负**，落到对象上会让它的分摊额变成**负成本** —— 负成本比差几分钱更糟。
⇒ 用「先 `trunc(理论额, 4)`、再把零头补回去」，保证每一行都 ≥ 0，且 Σ 恒等于总额。

零头补给谁由规则的 `rounding_policy` 决定（最大余数优先 / 最小余数优先 / 第一个 / 最后一个 /
**指定对象**）。蓝图说的「保留舍入差额到**指定对象**」由 `SPECIFIED` 策略直接兑现：
规则必须点名对象编码，指名的那一行拿到的零头逐行记在 `rounding_adjustment` 上，
批次上记 `rounding_difference` 与落点对象 —— **零头不消失、也不含糊**。

### 3. 放宽 12.4.2.1 钉死的三个 CHECK（**只放宽，绝不收紧**）

12.4.2.1 在池与条目上钉了三个收紧的 CHECK，把「已分摊」这个状态**写不出来**；
V351 第七节原话是「由它在自己的迁移里**放宽**」⇒ 那正是本迁移的活。

| 列 | 之前 | 之后 |
| --- | --- | --- |
| `pool.allocation_available` | `= false` | 可 true（**语义变了**：从「本模块不做分摊」改为「**该池已完成分摊**」） |
| `entry.allocated_amount` | `= 0` | `>= 0`（真实分摊金额） |
| `entry.allocation_status` | `= 'UNALLOCATED'` | 词表 `('UNALLOCATED','ALLOCATED')` |

`allocation_owner_module` **一个字未改**（恒 `'12.4.2.2'`，归属方就是本模块）。

⚠️ **方向不能反**：12.4.2.1 的 INSERT **不写**这三列（走默认值）。收紧成别的值会让它的老 INSERT
当场违约、它自己的集成测试变红，而本车道的 L1 收窄到本模块**看不到**那个红 —— 红的是批次末的 L2。
⇒ 重加的 CHECK 全部满足「默认取值仍合法」，且集成测试专门断言了一轮：
默认池/条目插入成功、`allocated_amount = 0`、`allocation_status = 'UNALLOCATED'`。

### 4. 关键 trade-off：**分摊结果表自持 + 上游三列做状态镜像**（两者都做）

调度者给了两条路：把分摊金额硬写回 `entry.allocated_amount`，或另建分摊结果表并回链。

⇒ **两者都做，但分工明确**：

- **结果表是权威**：`finance_cost_allocation_result` 一行 = 「这一次分摊里某个对象摊到了多少、
  怎么摊的」（蓝图输出句的「分摊结果 + 分摊明细」）；批次上是「哪一次分摊、用的哪条规则、
  被分摊总额、舍入差、零头落点」。历史可查、可撤销、可留档。
- **上游三列是状态镜像**：由**触发器**随批次同生共死（批次落库 ⇒ 池标成已分摊、条目标成已分摊；
  批次撤销 ⇒ 全部回到未分摊）。**不由应用层随手改** —— 改一次就与分摊事实对不上。

只做回写不够（下游 13 利润拿不到「谁摊到了多少」）；只做结果表则上游那三列永远是死的，
下游读上游表时会以为「这个池还没分摊」。⇒ 两个都留，权威在结果表。

### 5. 分摊对象来自**真实上游事实**，快照落库；绝不造数

蓝图输入只有两项（规则配置 + 待分摊费用池），但「摊给谁」必须有对象与基数。⇒ 从上游**授权 HTTP
只读端口**取（AGENTS.md 模块边界纪律：禁止跨服务 SQL / 跨库 FK / 共享视图）：

| 维度 | 真实来源 | 可得基数 |
| --- | --- | --- |
| ORDER / SHOP | trade 平台订单（9.1.2.1）+ channel 店铺主数据（2.3.1.1） | 金额 / 等额 |
| SKU / SPU / CATEGORY | trade 销售订单行（9.1.1.1）+ catalog SKU 主数据与包装档案（3.1.2.1 / 3.1.2.2） | 数量 / 金额 / 重量 / 体积 / 等额 |

新增两个只读端口：`SalesOrderLinePort`（trade）、`CatalogSkuPort`（catalog）。

对象**快照**落库（`finance_cost_allocation_target`）—— 采集时用的基数留在库里，
之后上游改了也不影响已发生的分摊（蓝图：「历史分摊不能被新规则追溯改写」）。

### 6. 基数不可得：**留空 + 留痕 + 不参与分母**；全部不可得 ⇒ 拒绝分摊

「未维护」与「真的是 0」是两回事 —— 0 会被当成「这个 SKU 重量为零」，静默改变分母。
⇒ 取不到的基数**留空并写明原因**（「catalog 没维护这个 SKU 的包装重量」）；
不可得的对象**明确记成一行**（分摊额 0 + `basis_available = false` + 原因），
它「没分到」这件事必须看得见，而不是安静地消失。

⚠️ **全部**对象都取不到 ⇒ **拒绝分摊**（422），不摊出一张全 0 的单 —— 那张单会让下游以为
「这个池不用花钱」。这条与 catalog 5.2.1.3（V224）逐字同构。

⚠️ **SPU / 类目的重量 / 体积**：只要**有一个**构成 SKU 没维护包装，这一档就**整体不可得** ——
少算的和不叫缺失，叫算错。

### 7. 规则命中的语义：**启用 + 池标识匹配 + 生效区间覆盖池的归属期间**，按 `priority` 取第一条

生效区间落在**池的归属期间**上（不是「今天」）⇒ 池的期间不在区间内的规则不命中，
所以**新规则不会追溯改写历史分摊**；多条命中时按分摊顺序数字小的先用（页面上看得见用了哪条规则）。

### 8. 引用校验用**触发器**，不建 FOREIGN KEY

catalog V224 的纪律：给既有表加 FK 会打破同服务既有测试的 `TRUNCATE`（级联删不掉就报错）。
⇒ 本迁移**一个 FOREIGN KEY 都不建**，跨表引用一律用触发器核对；触发器只在 INSERT/UPDATE 时跑，
`TRUNCATE` 不触发 ⇒ 不影响别的车道的既有测试。

### 9. 其它纪律

- **绝不跨币种相加**：采集时按池的币种过滤，被跳过的条数**如实计数**并写进采集结果。
- **时间字段**：`timestamptz` 一律 OffsetDateTime + `?::timestamptz`；`date` 一律 `?::date`。
  集成测试**断言写回的值**（`extract(epoch from allocated_at)` 与接口返回的 `toEpochSecond()` 相等、
  偏移量是 UTC）—— 不用 `Timestamp.valueOf(LocalDateTime...)`（那会静默偏 −28800s）。
- **契约与台账是共用的**：契约由 codegen 生成（禁止手改），台账只改自己那一行。

## 关键 trade-off 与已知限制

1. **采集是 N+1**（trade 订单详情 + catalog SKU 档案各一次调用），有取数上限
   （订单 10 页 × 200、SKU 档案 200 个、catalog SKU 列表 10 页）。
   触到上限时**如实写明**（「超出单次取数上限」），不静默截断。
   后续若量级变大，可换成上游提供批量端点。
2. **上游侧「已分摊」只有两个状态**（未分摊 / 已分摊），没有「部分分摊」——
   因为本模块的分摊是**整池原子**的（要么摊完，要么没摊）。
3. **13 利润（13.4.1.1）与 12.5.2.1 凭证未交付**：本模块**不产出任何利润数字**、
   分摊批次**不生成会计凭证**（蓝图没写这一格，不越界做）。能力声明里如实标为缺口并写明归属。
4. **导航未登记**：`packages/ui/src/lib/task-pages.ts` 是多车道并发修改的共享文件，
   本车道**刻意未登记**，留给调度者统一处理（与 12.4.2.1 同一处理）。
5. **api-proxy 首 token 与 catalog 5.2.1.3 同名**（都是 `cost-allocations`）：
   两者**前缀作用域不同**（`catalog/` vs `finance/`），实测 202 条既有正则里
   finance/ 作用域下对 `cost-allocations` **零命中** ⇒ 互不吃掉。
   两者是不同模块（一个摊货件成本，一个摊费用池）。

## 文件清单

**迁移**

- `backend/services/finance/src/main/resources/db/migration/V354__finance_cost_allocation.sql`
  —— 四张新表 + 放宽上游三个 CHECK + 5 个触发器（规则守卫 / 对象守卫 / 结果守卫 /
  等式延迟约束触发器 / 上游状态同步）+ RLS + GRANT。

**后端**

- `CostAllocationModels.java`（传输模型 + 词表枚举）
- `CostAllocationService.java`（规则 CRUD / 池 / 对象采集 / 分摊算法 / 撤销 / 列表 / 筛选项 / 能力声明）
- `CostAllocationController.java`（13 个端点，挂 `/finance/cost-allocations`）
- `SalesOrderLinePort.java`（trade 只读端口）
- `CatalogSkuPort.java`（catalog 只读端口）

**测试**

- `CostAllocationIntegrationTest.java`（21 条：等式 / 舍入差 / 基数不可得 / 生效区间 /
  分摊顺序 / 重复分摊 / 撤销 / 上游三列 / 时间字段写回值 / 采集幂等 / 真分页汇总）
- `CostAllocationTenancyIntegrationTest.java`（8 条：三条隔离断言 + RLS 强制 + 端点隔离）

**前端**

- `apps/web/src/components/finance/cost-allocation-model.ts`（端点 + 可读名 + 格式化）
- `apps/web/src/components/finance/cost-allocation-workspace.tsx`（真实工作区）
- `apps/web/src/app/finance/cost-allocation/page.tsx`（路由页）
- `apps/web/src/components/finance/cost-allocation-model.test.ts`（12 条）
- `apps/web/src/app/api/finance/cost-allocation-allowlist.test.ts`（4 条，双向断言）
- `apps/web/src/lib/api-proxy.ts`（**新增一条**独立 services[] 元素，既有条目一字未改）

**契约（codegen 产物）**

- `packages/api-contracts/src/contracts/finance.ts`
- `packages/api-contracts/src/finance.openapi.json`

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md`（**只改 12.4.2.2 那一行**）
- `docs/sessions/2026-10/2026-10-24-cost-allocation-rule.md`（本文件）

## 后续待办

1. **13 利润（13.4.1.1）**接本模块的分摊结果（`finance_cost_allocation_result`）——
   本模块产出的是费用侧输入，利润数字不在本仓。
2. **12.5.2.1 凭证**：分摊批次生成会计凭证（蓝图未写，留给该模块）。
3. **导航注册**：`packages/ui/src/lib/task-pages.ts` 由调度者统一处理。
4. **契约再生成的前置**：本仓 `pnpm contracts:generate` 需要**全部 7 个服务**的 spec；
   只跑 finance 的 `OpenApiContractTest` 时，其余 6 个要用**已提交的快照**补齐 ——
   ⚠️ 但那样 `.ts` 的键序会与既有产物不一致（快照是排序过的，活 spec 是声明序），
   ⇒ 正确做法是跑一遍**全服务**的 `OpenApiContractTest` 再生成，否则 diff 会波及别人的文件。
5. **上游若提供批量端点**（订单行 / SKU 包装），把采集的 N+1 换掉。
