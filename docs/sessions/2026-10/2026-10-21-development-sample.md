# 2026-10-21: 6.6.1.2 打样与样品管理 —— 把「这一轮要打一个样」变成有凭据的台账

> 车道 `feat/6-6-1-2`，worktree `.worktrees/6.6.1.2`，基线 `cc0e50c7`（integration/module-dev）。
> 能力蓝图卷 06「采购与供应」L3 锚点 6.6.1，阶段 D6，L4 模块 6.6.1.2。
> **服务归属 = trade**（卷 06 后端主责服务 = trade + catalog，取列表第一项 = trade）。

## 摘要

本车道交付 6.6.1.2「打样与样品管理」：样品申请、样品来源（供应商 / 自有产 / 档口）、
样品物流、确认结果（通过 / 修改 / 放弃）、样品成本。

蓝图那句加粗 ——「打样费用是新品开发的真实成本，需进费用归集，否则新品利润被高估」——
与同一句里的「与达人寄样（14.5.1.2）区分打样与营销样品」—— 落成了三处硬约束：

1. **打样与营销样品在存储层就分得开** —— 「来源 / 用途」两个受控维度，用途词表**只有**
   「开发打样」一个值，**没有「其它」桶**。
2. **打样单与样品台账是两个东西** —— 打样单是**一次打样活动**，样品台账是**一件样品**
   （1 张单 : N 件样品）；确认结果必须由一条**不可改写**的确认记录授权。
3. **成本记事实，但不假装已入账** —— 成本落 trade 本库；12 费用（finance 服务）**在本仓不存在**
   ⇒ `expense_posted` 被 DB CHECK 钉死为 `false`。

九张新表、零 ALTER 既有表、零跨库 FK / 视图；17 条路径 / 25 个动作；真实工作区 1 个；
测试 62 例（后端，含多租户三断言）+ 23 例（前端）。

## 决策摘要

### 决策一：打样与达人寄样 —— 「来源 / 用途」两个维度，用途词表只有一个值

蓝图职责句把两件事写在同一句里：「样品来源（供应商/自有产）……；**与达人寄样（14.5.1.2）
区分打样与营销样品**」。第一眼看去，一个「来源」枚举就够了 —— 但**不够**：

> 达人寄样**同样来自供应商**。只靠「来源」分，达人寄样与供应商打样长得一模一样。

所以本模块落成**两个正交维度**：

| 维度 | 列 | 取值 | 作用 |
| --- | --- | --- | --- |
| 来源 | `source_type` | `SUPPLIER` / `OWN_FACTORY` / `STALL` | 这个样品**从哪来** |
| 用途 | `usage_type` | **只有 `DEVELOPMENT`** | 这个样品**是干什么的** |

关键在于**用途词表里没有「其它」，也没有营销寄样**。这不是省略，是**设计**：

- 达人寄样属 **14 卷**（14.5.1.2），是**营销样品**，本来就不该由本模块管；
- 留一个「其它」桶 = 留了一条「营销寄样也能塞进来」的旁路 =
  **营销费用污染开发成本** = 蓝图担心的「新品利润被高估」；
- DB CHECK `usage_type = 'DEVELOPMENT'` 把它挡在**存储层**，
  绕过服务层直连 SQL 也插不进来。

「来源 ↔ 来源实体」也钉死：供应商打样必须挂供应商、档口打样必须挂档口、
**自有产打样不许挂供应商或档口**（自有产没有外部来源）—— 避免出现「挂着供应商却说是自有产」
这种答不出「这批样到底哪来的」的行。

DB 层还有第二道：样品行的 `source_type` / `usage_type` / `currency`
必须与**它的打样单**一致（`guard_development_sample_source_consistent()`）——
挂在单子下却写着另一个来源，成本归集就没法做了。

**服务层与前端同口径**：前端的「样品用途」输入框**锁死显示为「开发打样」**，
不是可选项；下拉里也不存在「其它」。

### 决策二：样品成本怎么汇总、跟开发成本是什么关系

蓝图关系句写死：「下游 = 6.6.2.1 试产、**12 费用（样品成本）**」。
但 **12 费用在本仓不存在** —— `backend/services/` 下只有
catalog / channel / crm / identity / trade / warehouse，**没有 finance**。

于是拆成两件事，分别处理：

**（a）成本事实：落 trade 本库（本模块自己的一张成本表 + 成本明细行）**

- `development_sample_cost`（成本头）：**一个打样单一张**、**一个币种**、合计金额；
- `development_sample_cost_line`（成本明细行）：打样费 / 物料费 / 模具费 / 物流费 / 检品费
  —— **封闭五值，无「其它」**（「其它」桶会让「这个钱花在哪」永远答不出来）。

三条纪律：

1. **合计金额由数据库触发器维护**（`guard_development_sample_cost_total()`），
   不靠应用层记得同步 —— 删一行、插一行，合计自动重算。
2. **一张单一个币种**：明细行的币种必须与成本头一致（触发器挡）。
   汇率查询（5.2.2.1）未交付 ⇒ **跨币种的数绝不相加**。
   概览因此**逐币种列**（`costByCurrency`），不给一个假的「总成本」。
3. **成本类型封闭**：五个类型之外没有第六个。

**（b）进费用归集 / 入账：如实标 `available=false`**

- 能力声明：`expenseAggregationAvailable=false` + `expenseAggregationOwnerModule='12 费用'`
  + 影响说明；
- 数据层：**DB CHECK `development_sample_cost_not_posted` 钉死 `expense_posted = false`** ——
  「不假装已经入账」这句话在**数据库层**生效，不只是接口文档里的一句承诺；
  12 费用交付后由那条车道的新迁移放开本约束；
- **不造一张「费用」表**去冒充 12 财务的交付物。

**与开发成本（6.6.1.1）的关系 —— 明确写清「谁汇总、怎么汇总」**：

- 6.6.1.1 的 `GET /trade/development-projects/{id}/cost-estimate` 与
  `DevelopmentCostEstimatePort` 是**开发成本估算**的口径，**归 6.6.1.1 所有**。
- 本模块**只记自己的样品成本事实**（本模块自有的两张成本表），
  **禁止 UPDATE `development_project` 的成本列**（`target_cost` / `cost_currency`）——
  那是上游模块的写入所有权。
- 「样品成本计入开发成本」这件事**本模块不做**：它需要跨到 12 费用与 5 定价做归集与折算，
  两者都不在本仓。**由谁汇总**：12 费用交付后，由它读本模块的成本事实做费用归集；
  在那之前，**新品利润仍可能被高估**（这是如实暴露的缺口，不是本模块能自己解决的）。
- 因此 6.6.1.1 的 `target_cost` 与本模块的 `total_amount` **是两个独立事实**，
  页面上不合并、不相加。

### 决策三：物流与图片两个跨服务能力，接线了没有

| 能力 | 接线？ | 怎么做的 / 为什么没做 |
| --- | --- | --- |
| **样品物流** | **半接线：只记快照，轨迹不接** | 真实物流轨迹属 **10 卷**（channel + trade + warehouse），本模块**没有**可读的真实轨迹来源 ⇒ 只记**物流快照**（发货单号 / 承运商 / 物流状态 / 发出时刻），`tracking_available` **恒为 false** + `tracking_owner_module='10 物流'`。**不硬造一个「物流已接入」的假接口**，白名单里也不放行 `.../tracking` 形状。 |
| **样品图片** | **接了（跨服务只读 + 快照）** | catalog 媒体库（3.4.1.1）**已交付** ⇒ 走 `SampleMediaQueryPort` 读 `GET /catalog/media`（列表候选 + 单条校验）。跨库只读 ⇒ `media_ref_id` + 元数据快照（名称 / 格式 / MIME / sha256 / 字节数 / 存储键），**不建跨库 FK、不跨库写**（`media_*` 表的唯一写入所有者是 catalog）。挂不存在的素材 id ⇒ 404，**不返回 null 让调用方自己判空**。 |

补充：catalog 媒体没有公开 URL 端点 ⇒ 样品图片在界面上呈现为**元数据卡片**
（名称 / 格式 / 哈希 / 大小），**不是**一个指向不存在地址的 `<img>`。

### 决策四：「打样单」与「样品台账」的基数 —— 不糊进一张表

- 打样单 = **一次打样活动**（含多个样品）；
- 样品台账 = **样品实体**（一个样品一件）。

**1 张打样单 : N 件样品**，归属是 `development_sample.order_id` →
`development_sample_order.id`（**本模块自己的表 ⇒ 正常外键**）。
打样单详情与跨单台账是**两个查询**（`GET /{id}/samples` 与 `GET /samples`）。

糊进一张表的后果很具体：答不出「这张单打了几个样、其中几个确认通过」——
而这正是打样确认要回答的问题。

### 决策五：确认结果与制作进度分成两个入口

蓝图职责句点名的「确认结果（通过/修改/放弃）」与「样品物流」是**两类事实**，
混在一个端点里会让「改了状态却没有确认记录」变得可能。所以：

- **制作进度**（已申请 → 打样中 → 已发出 → 已到样）走 `POST .../transitions`，
  留一条**进度事件**；
- **确认结果**（通过 / 修改 / 放弃）走 `POST .../confirmations`，
  留一条**确认记录**（`confirm_seq` = 第几次确认）。

DB 守卫 `guard_development_sample_status()` 按**目标状态**分流：
目标是「已确认 / 修改中 / 已放弃」⇒ 必须有一条 result 对得上的**未消费**确认记录；
否则必须有一条对应的**进度事件**。两者都缺 ⇒ 状态变更直接被数据库拒绝。
消费靠 `applied_at` 从 NULL 变成一次时刻 —— 同一条记录不能被复用第二次。

「修改」必须带修改意见（DB CHECK + 服务层双挡）：只说「要改」不说改什么，
下一轮打样的人还是得猜。

**「修改中」不是终态**：可以回到「打样中」重新打样（轮次 +1），
也可以改完直接「已确认」。「已确认 / 已放弃」才是终态。

> 踩到并修掉的一个坑：`confirm_seq`（第几次**确认**）与 `revision_round`（第几轮**打样**）
> 最初**同名同义**，结果「修改中 → 直接通过」这条路会在**同一轮打样**里确认两次 ⇒ 唯一键冲突。
> 两者是**不同的轮次**，已拆开：确认记录用 `confirm_seq`，样品保留 `revision_round`。

## 关键 trade-off

- **用途词表只放一个值 vs 留「其它」**：只放一个值意味着「将来若要支持别的用途，
  得改 CHECK」。这是**故意**的 —— 营销寄样混进来污染开发成本，代价（新品利润被高估）
  远大于「以后加一个枚举值」的代价。
- **物流只记快照 vs 等 10 物流接了再一起做**：只记快照让「样品发出去了」有凭据
  （发货单号是必填），代价是页面上的物流信息是**人工登记**的。
  好处是**现在就有真实数据**，且**不假装**轨迹可查。
- **`expense_posted` 由 DB CHECK 钉死为 false vs 留一个可写的布尔列**：
  可写列在 12 费用交付前就可能被写成 `true`（谁写？什么时候？），
  钉死则**在数据库层就没有「假装已入账」的可能**。代价是 12 费用交付时需要一条新迁移放开约束。
- **成本类型封闭五值 vs 允许自由文本**：「其它」桶是费用归集的死穴，
  封闭词表让「这个钱花在哪」永远答得出来。
- **api-proxy 新增独立 services[] 元素 vs 并进既有 trade 长正则**：
  既有 trade 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不自愈）。
  新增元素插在 `development-projects` 之后，与常见锚点都隔开 ≥ 1500 行。

## 上游依赖自检（开工前 10 分钟，已通过）

| 依赖 | 状态 | 证据 |
| --- | --- | --- |
| 6.6.1.1 开发立项（trade V257） | ✅ 已交付 | `development_project` 表 + `GET /trade/development-projects/**` |
| 6.1.2.1 档口（trade V233） | ✅ 已交付 | `supplier_source_stall` 表 + `GET /trade/sources/stalls` |
| 6.1.2.3 供应商资质（trade V239） | ✅ 已交付 | 参照用 |
| 供应商清单（catalog） | ✅ 已交付 | `GET /catalog/supply/vendors` + `SupplierPartyQueryPort` |
| 媒体库（catalog 3.4.1.1） | ✅ 已交付 | `GET /catalog/media` |
| 12 费用（finance） | ❌ **服务不存在** | 能力声明 `available=false` + `ownerModule='12 费用'` |
| 6.6.2.1 试产单 | ❌ **未交付** | 能力声明 `available=false` + `ownerModule='6.6.2.1 试产单'` |
| 10 物流 | ❌ **无可读轨迹来源** | 只记快照 + `tracking_available=false` + `ownerModule='10 物流'` |

## 文件清单

**迁移（本车道自有，零 ALTER 既有表）**

- `backend/services/trade/src/main/resources/db/migration/V261__product_development_sample.sql`

**后端（trade 服务）**

- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentSampleModels.java`（传输模型 + 枚举）
- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentSampleRules.java`（纯规则：状态机 / 词表 / 校验）
- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentSampleService.java`（jOOQ 显式 SQL）
- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentSampleController.java`（17 条路径）
- `backend/services/trade/src/main/java/com/starcon/trade/SampleMediaQueryPort.java`（catalog 媒体库只读端口）

**后端测试**

- `backend/services/trade/src/test/java/com/starcon/trade/DevelopmentSampleRulesTest.java`（28 例）
- `backend/services/trade/src/test/java/com/starcon/trade/DevelopmentSampleIntegrationTest.java`（31 例）
- `backend/services/trade/src/test/java/com/starcon/trade/DevelopmentSampleTenancyIntegrationTest.java`（3 例）

**前端**

- `apps/web/src/app/purchase/development/samples/page.tsx`
- `apps/web/src/components/purchase/development-sample-workspace.tsx`
- `apps/web/src/components/purchase/development-sample-model.ts`
- `apps/web/src/components/purchase/development-sample-model.test.ts`（16 例）
- `apps/web/src/app/api/trade/development-sample-allowlist.test.ts`（7 例）

**共享文件（按纪律只做外科式追加 / 只改自己那一行）**

- `apps/web/src/lib/api-proxy.ts`（**新增一条 `development-samples` services[] 元素**，既有条目一字未动）
- `packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json`
  （codegen 重生成；**channel 那两份是集成头里 16.4.2.2 车道的既有漂移，本车道未改动、已还原**）
- `docs/product/capability-blueprint/module-progress.md`（**只改 6.6.1.2 那一行的「状态」「证据」两格**）

**会话日志**

- `docs/sessions/2026-10/2026-10-21-development-sample.md`（本文件）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `./mvnw -q compile` | 退出 0 |
| L0 类型 | `pnpm typecheck` | 3/3 通过 |
| L1 模块 | `./mvnw -pl services/trade -am test -Dtest='DevelopmentSample*' -Dsurefire.failIfNoSpecifiedTests=false` | **62 例 0 失败 0 错误**（Rules 28 + Integration 31 + Tenancy 3） |
| Lint | `pnpm lint` | 通过（本车道零告警） |
| 前端 | `pnpm vitest run apps/web/src/components/purchase/development-sample-model.test.ts apps/web/src/app/api/trade/development-sample-allowlist.test.ts` | **23 例全绿** |
| **全量 allowlist 回归**（改了 api-proxy.ts ⇒ 必须跑） | `pnpm vitest run apps/web/src/app/api` | **139 文件 806 例全绿** |

## 后续待办

1. **12 费用（finance）交付时**：由那条车道的新迁移放开
   `development_sample_cost_not_posted` 约束，并把 `expense_posted` 置位；
   在那之前**打样费用未进费用归集，新品利润仍可能被高估**。
2. **6.6.2.1 试产单交付时**：消费本模块台账里「已确认」的样品事实。
3. **10 物流接线时**：把 `tracking_available` 从 false 置真，并让 `tracking_owner_module` 退场。
4. **若将来要支持别的样品用途**（例如把「其它」正式化）：那是**产品决策**，
   需要重新评估「营销样品污染开发成本」这条线，不是本模块顺手加一个枚举值的事。
5. **台账同步**：`node tools/module-progress/sync.mjs --check` 在**基线上就已红**
   （集成头的总览计数落后于若干车道的行状态）。本车道按纪律**只改自己那一行**，
   计数由调度者 / sync 在批次级统一刷新。
