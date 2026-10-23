# 12.4.2.1 费用归集（V351 · 阶段 D6 · 车道 12.4.2.1）

> 蓝图原文：`docs/product/capability-blueprint/12-finance.md` 的 **12.4.2.1 费用归集**。
> 车道分支 `feat/12-4-2-1`，基线 `integration/module-dev` 的 `2874248d`。
> 迁移版本 **V351**（调度者预分配；集成头里 finance 到 V349 为止，V350 被另一条车道占用）。

## 决策摘要

### 1. 最重要的一条：**归集与分摊要分开，且这条边界写在数据层**

蓝图关系句：「**归集与分摊要分开**：先按规则归集费用池，再按维度分摊，便于核对与调整。」
下游 12.4.2.2「分摊规则」本轮**未开发**（下一格，不抢做）。

⇒ 边界不靠注释，靠**数据层写不出来**：

| 列 | 约束 | 含义 |
| --- | --- | --- |
| `finance_cost_collection_pool.allocation_available` | `NOT NULL DEFAULT false` + CHECK `= false` | 本模块**没有**「可分摊」这个状态 |
| `finance_cost_collection_pool.allocation_owner_module` | CHECK `= '12.4.2.2'` | 分摊归下游 |
| `finance_cost_collection_entry.allocated_amount` | `NOT NULL DEFAULT 0` + CHECK `= 0` | 条目上**没有**分摊金额 |
| `finance_cost_collection_entry.allocation_status` | CHECK `= 'UNALLOCATED'` | 条目上**没有**别的分摊状态 |

**本模块一张分摊结果表都不建**（集成测试直接断言：本模块的表恰好是规则 / 池 / 条目 / 依据这四张）。
页面侧同样克制：列表里的「分摊目标维度」一律写成「按…分摊」而不是「已按…分摊」，
页面顶部有一条显式的「本页只做归集，不做分摊」提示。

### 2. 四类归集口径由**表级 CHECK** 钉死

蓝图职责句点名的四类口径：按月（平台费 / 订阅费）、按单（物流费）、按 SKU（头程费）、
按期间（人员与办公费用）。

规则表上的 `(fee_source, fee_category, target_dimension, period_basis)` 四元组被**表级 CHECK** 钉成
七组合法配对。为什么不自由配：「按 SKU 分摊的平台费」在业务上说不通（平台费是按月结的，
没有 SKU 粒度的平台账单），而**说不通的配对一旦能存进库，界面就会允许用户选它**
⇒ 用户配错 ⇒ 池里混进维度不一致的费用 ⇒ 下游分摊时口径对不上。

服务层提前拦一道并给出**能直接展示给人看**的原因；绕开服务层直写库也被 CHECK 挡住 ——
两条都在集成测试里断言了。

### 3. 四张表把蓝图输出的链条表达完整

蓝图输出是「归集明细（**费用项 → 分摊对象 → 金额**）」，关系句强调「先按规则归集**费用池**」。

- `finance_cost_collection_rule` —— 归集规则（费用来源 → 费用项 → 目标维度 → 费用池）
- `finance_cost_collection_pool` —— 费用池（**池标识 × 维度 × 归属期间 × 币种** 唯一）
- `finance_cost_collection_entry` —— 费用池条目（归集的最小单位：一笔费用一项）
- `finance_cost_collection_basis` —— 归集依据（凭据号 / 原始金额 / 上游分项 / 凭据引用）

**为什么依据单独一张表**：蓝图把「保留归集依据」单列成职责，而依据的**结构随来源而变**
（结算明细行是「结算单号 + 行号 + 交易类型」，运单运费是「运单号 + 订单号 + 承运商 + 登记来源」，
头程发运单是「运费 / 保险费 / 处理费」三段分项，人工录入是「单据号 + 凭据号 + 归属说明」）。
一张表用**固定字段**（不是自由 JSON）装分项 —— 17 个字段里每个来源只填其中几个，
五个工厂方法（`settlementLine` / `adReport` / `waybillFreight` / `headMile` / `manualDocument`）
各写一份 ⇒ 位置参数写错一个 `null` 就会把「运费分项」写成「保险费分项」而不报错，这种错工厂方法挡得住。

### 4. 池合计由**触发器**从条目派生，不靠应用层维护

「池合计 = Σ条目金额」是本模块唯一必须恒成立的等式。
放触发器里而不是应用层：应用层漏一次更新，合计就永远错了 ——
而「合计」正是下游分摊要拿来当**被分摊总额**的数，错了整条利润链都错。

**一个池一个币种一个期间**：条目插入时由 BEFORE INSERT 守卫核对币种与维度，
币种不一致直接报错（而不是让两条不同币种的条目混进一个池再算）—— 绝不跨币种相加。

### 5. 归属期间：自然月 vs 会计期间

- `CALENDAR_MONTH`：归属期间 = 基准日期所在的日历月（UTC 日历日口径）。平台费 / 广告费 /
  订阅费 / 物流费 / 头程费都走这一档。
- `ACCOUNTING_PERIOD`：归属期间由操作员**显式给出**（起止日）。人员与办公费用走这一档 ——
  它天然按「会计期间」而不是「自然月」发生（季末计提、年终奖），
  强行按自然月切会把一笔跨月的费用切碎。

归属期间落在**费用池**上（池 = 期间 × 维度 × 币种 × 池标识），条目经 `pool_id` 取到自己的期间
⇒ **期间只有一处真相**，不会两份数据对不上。

### 6. 上游事实来源：如实接通，绝不造数

| 费用来源 | 上游事实 | 读法 | 口径纪律 |
| --- | --- | --- | --- |
| 平台费 | 12.3.1.2 `finance_settlement_line` | **同服务直读** | 只取佣金 / FBA 费用 / 仓储费 / 配送费；**不含 ADVERTISING** |
| 广告费 | 14.3.1.1 活动报表 | channel 只读端口（既有 `AdSpendPort`） | 取**花费（spend）**不是销售额；行不带单一日期 ⇒ 基准日显式给出 |
| 物流费 | 10.2.1.1 运单运费登记 | trade 只读端口（**新建 `WaybillFreightPort`**） | 只归集**已登记且金额 > 0**；「未登记」≠「登记了 0 元」 |
| 头程费 | 10.4.1.1 头程发运单 | warehouse 只读端口（既有 `FirstMilePort`） | 运费 + 保险费 + 处理费；**关税估算不算**；**只归集已发运的** |
| 订阅费 / 人员费用 / 办公费用 | **人工录入** | 本模块自己的录入端点 | 蓝图输入里点名的第四类来源，不是兜底的假数据源 |

**为什么广告费不归「平台费」那一档**：结算单里的 `ADVERTISING` 行与活动报表的花费是
**同一笔广告费的两种口径**（账单口径 vs 花费口径）。两个都归集 ⇒ 同一笔钱进池两次。
⇒ 广告费只走 `AD_SPEND` 一个口径，结算单那一档明确排除并在代码与台账里写明。

**为什么规则由租户自己维护、系统不预置任何一行**：费用池怎么划分是租户自己的账务口径，
系统替用户默认一个池名就是替用户记账。提供**蓝图标准绑定模板**（只读，不落库）供界面一键建规则。

### 7. 幂等：同一笔上游事实只许归集一次

`UNIQUE(tenant_id, fee_source, source_ref_key)`。归集是「收钱进池」，同一笔钱进两次 = 费用凭空翻倍。
人工录入也要求给出单据号当幂等锚点 ⇒ 同一个人的两次误操作不会造出两笔一样的费用。

### 8. 契约与代理

- 契约由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成
  （**禁止手改**）；本次只改了 `packages/api-contracts/src/contracts/finance.ts` 与
  `packages/api-contracts/src/finance.openapi.json`（其余六个服务的契约文件**一字未动**）。
  可空的字段在 Java 侧用 `@Schema(nullable = true)` 标注 ⇒ 生成的 TS 类型**如实反映可空性**
  （否则前端会把 `null` 当成 `string`，类型检查却全绿）。
- api-proxy **新增一条独立 services[] 元素**（prefix `finance/`、首 token `cost-collections`），
  插入锚点选在 global-search 那组之前 —— 与同批其它车道的插入点相距 10k 行以上，并入时 git 能干净求并。
  **刻意不放行**：裸路径 `cost-collections`（后端没有这个汇总接口）、
  `allocation` / `allocations` / `entries/{uuid}/allocate`（分摊属 12.4.2.2，未交付）。

### 9. 「撤掉一条归集条目」用 POST 而不是 DELETE

蓝图关系句：「便于核对与调整」⇒ 费用进错了池要能撤出来重新归集。

同服务的代理出口 `apps/web/src/app/api/finance/[...path]/route.ts` **刻意不导出 DELETE**
（那条出口对**全部** finance 路径生效，导出 DELETE 会把「能不能删」的决定权扩散到别的模块 ——
应收单是凭据，只许作废不许删）。⇒ 本模块用 POST 的动作形状
`POST /entries/{id}/remove`（与 12.1.1.3 的 `bad-debt/{uuid}/evidence/{uuid}/remove` 同一约定），
路径是本模块自己的，不会误伤其它模块。**没有去改那个共享出口**。

## 关键 trade-off

| 选择 | 放弃了什么 | 为什么 |
| --- | --- | --- |
| 四元组 CHECK 钉死配对 | 租户自由定义「按 SKU 分摊的平台费」这类组合 | 说不通的配对能存进库就一定会被选中；蓝图那四类口径本身就是固定的 |
| 池合计由触发器派生 | 应用层维护合计的简单性 | 应用层漏一次更新，合计永远错，而它是下游的被分摊总额 |
| 广告费只走一个口径 | 结算单里 `ADVERTISING` 行也进池 | 两个口径是同一笔钱，进两次 = 费用翻倍 |
| 条目按**单票**头程发运单归集 | 直接按 SKU 落条目 | SKU 级拆分就是分摊，属 12.4.2.2；越界做会让两条车道互相打架 |
| 规则不预置 | 开箱即用 | 费用池划分是租户自己的账务口径 |
| POST 动作而不是 DELETE | REST 语义纯度 | 共享代理出口不导出 DELETE，导出它会波及其它模块 |

## 文件清单

**迁移**
- `backend/services/finance/src/main/resources/db/migration/V351__finance_cost_collection.sql`

**后端**（`backend/services/finance/src/main/java/com/starcon/finance/`）
- `CostCollectionModels.java`（枚举 / 能力声明 / 规则 / 池 / 条目 / 依据 / 输入 / 查询）
- `CostCollectionService.java`（能力声明、规则 CRUD、池与条目查询、五个归集入口、调整）
- `CostCollectionController.java`（15 个端点挂 `/finance/cost-collections`）
- `WaybillFreightPort.java`（**新建**：10.2.1.1 运单运费登记的跨服务只读端口）

**测试**（`backend/services/finance/src/test/java/com/starcon/finance/`）
- `CostCollectionIntegrationTest.java`（18 条）
- `CostCollectionTenancyIntegrationTest.java`（8 条，三条隔离断言）

**契约**（codegen 产物，只由生成器改）
- `packages/api-contracts/src/contracts/finance.ts`
- `packages/api-contracts/src/finance.openapi.json`

**前端**
- `apps/web/src/components/finance/cost-collection-model.ts`
- `apps/web/src/components/finance/cost-collection-model.test.ts`
- `apps/web/src/components/finance/cost-collection-workspace.tsx`
- `apps/web/src/app/finance/cost-collection/page.tsx`
- `apps/web/src/app/api/finance/cost-collection-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，既有条目一字未改）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（**只改 12.4.2.1 那一行的状态与证据两格**）

## 验证

- **L0 编译闸**：`./mvnw -q compile`（backend）· `pnpm typecheck`（前端）⇒ 全绿。
- **L1 模块闸**：
  `./mvnw -pl services/finance -am test -Dtest='CostCollection*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ `Tests run: 26, Failures: 0, Errors: 0`（CostCollectionIntegrationTest 18 + CostCollectionTenancyIntegrationTest 8）。
- **前端**：`pnpm vitest run apps/web/src/components/finance/cost-collection-model.test.ts
  apps/web/src/app/api/finance/cost-collection-allowlist.test.ts` ⇒ 2 文件 / 15 例通过；
  因改了共享的 `api-proxy.ts`，额外跑了**全量 allowlist 回归**
  `pnpm vitest run apps/web/src/app/api` ⇒ **222 文件 / 1259 例通过**。
- **本车道 verify 期间的容器**：同期存活 `postgres:16-alpine` 容器 **109 个**（四条车道并发，
  本车道占 2 个）+ `testcontainers/ryuk` 2 个；本车道跑完后本车道的 2 个已被 **ryuk 回收**
  （全局计数由 109 降到 89，其余属于仍在跑的车道）。

## 后续待办

- **12.4.2.2 分摊规则**（下一格，**未开发**）：读 `finance_cost_collection_pool` /
  `finance_cost_collection_entry` 即可开始分摊，**不必再抄一份金额**；
  它需要在自己的迁移里放宽本模块的 `allocation_available` / `allocated_amount` /
  `allocation_status` 三个约束（或另建分摊结果表并回链）。
- **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**（四条车道并发改同一文件必然冲突）
  ⇒ 待调度者统一处理。
- **identity 权限目录**：finance 服务的权限码（`finance.costcollection.*`）尚未进 identity 的
  `permission` 表 —— 这是**全服务既有的缺口**（`finance.receivable.*` / `finance.transaction.*` 同样未进），
  不属本车道写入所有权，本车道沿用既有约定（模块自己的 `finance.<module>.<action>` 词表）。
