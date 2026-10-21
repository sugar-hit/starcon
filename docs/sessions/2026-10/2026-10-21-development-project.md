# 2026-10-21: 6.6.1.1 开发立项 —— 把「开发-打样-试产-量产」纳入同一编号体系

> 车道 `feat/6-6-1-1`，worktree `.worktrees/6.6.1.1`，基线 `a10ec0ec`（integration/module-dev）。
> 能力蓝图卷 06「采购与供应」L3 锚点 6.6.1，阶段 D6，L4 模块 6.6.1.1。
> **服务归属 = trade**（卷 06 后端主责服务 = trade + catalog，取列表第一项 = trade）。

## 摘要

本车道交付 6.6.1.1「开发立项」：把「要开发一个新品」变成一份**有凭据、状态受控、可交接**的开发单 ——
立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、打样与量产时间计划，
状态七态（评估 / 立项 / 开发中 / 打样 / 试产 / 量产 / 终止）。

蓝图原文那句加粗 ——「立项不是 ERP 传统模块，但是品牌化客户的源头」—— 落成了三处硬约束：

1. **状态机是结构，不是标签** —— 服务层（Java 枚举 + 显式转换规则）与数据库层
   （守卫触发器 + 事件表）**两处**都钉死；「终止」是终态；跳过打样 / 试产必须有显式豁免记录。
2. **目标毛利由服务端算** —— 调用方**报不上**一个毛利率；成本取不到就标 unavailable，
   **绝不拿 0 顶替**（成本 0 的毛利率是 100%，是彻头彻尾的假数据）。
3. **只产出候选参数，不建 SKU / 不建下游单** —— 3.1.1.1 建档归 catalog、6.6.1.2 / 6.6.2.1 未交付，
   本模块产**交接单**，不代它们建单。

七张新表、零 ALTER 既有表、零跨库 FK / 视图；16 条路径 / 25 个动作；真实工作区 1 个；
测试 46 例（后端，含多租户三断言）+ 23 例（前端）。

## 决策摘要

### 决策一：状态机在服务层 + 数据库层两处钉死

任务书点名的**第一个**易踩空点。落法是**同构两份**：

- Java 侧 `DevelopmentProjectRules.allowedTargets(from)`：返回允许的目标状态集合；
  `requireTransition(from, to, exemptionCode, exemptionNote)` 给出可读的 400。
- DB 侧 V257 迁移的 `guard_development_project_status()` BEFORE UPDATE 触发器：同一张 CASE 图。

关键的三条规则：

| 规则 | 服务层 | 数据库层 |
| --- | --- | --- |
| 终止是终态 | `TERMINATED` 的出边集合为空 | CASE 的 `TERMINATED` 分支只有 `ELSE false` |
| 不许跳过打样 / 试产 | `requiresExemption(from, to)` | 守卫查豁免记录，缺则 `RAISE` |
| 每次迁移都要有事件 | 事务内先 INSERT 事件再 UPDATE 状态 | 守卫查「有没有对应事件」，没有就 `RAISE` |

第三条是本模块最值得记的一条：**「改了状态却没有事件」在数据库层就做不到**，
不是靠应用层自觉。守卫触发器在状态变更时反向把那条事件标成「已用掉」
（`applied_at` 从 NULL 变成一次时刻），同一条事件不能被复用第二次。

留痕表的不可改写用**触发器**而不是只靠 `REVOKE`：`applied_at` 之外任何列被改写、
或整行被删除，都会被 `guard_development_project_transition_immutable()` 拒绝。
插入时 `applied_at` 恒为 NULL（`guard_development_project_transition_insert()`），
所以应用无法伪造「这条事件已经被用掉了」。

### 决策二：毛利率刻度取 6 位，而不是任务书举例的 2 位

任务书说「除不尽要定死舍入口径（例如统一 `RoundingMode.HALF_UP` 保留两位）」——
「例如」是**举例**，而仓库既有的口径才是可落地的锚点：**5.3.1.1 利润测算器的毛利率就是 6 位**
（`ProfitCalculatorRules`，费率 / 毛利率 6 位 HALF_UP）。

任务书那句括注里的理由是「不同地方用不同舍入会让同一个立项在不同页面上显示两个毛利」。
**照抄 5.3.1.1 的 6 位正好消灭这个风险**：同一个立项在定价页（5.3.1.1）与立项页（6.6.1.1）
显示的是同一个数。

- canonical 值 = `(售价 − 成本) ÷ 售价`，**单次** HALF_UP 到 6 位小数（`MARGIN_RATE_SCALE = 6`）。
- 百分比的两位小数**只是显示格式**（`费率 × 100` 再舍入），是同一个 canonical 值的格式化结果，
  **不是第二次计算** —— 前端 `marginText()` 只做这一次格式化。
- 算式原文随结论一起返回（`marginBasis`），「这个毛利率怎么来的」不靠复算、不靠读代码。

**输入模型里根本没有毛利率字段**：`DevelopmentProjectCreateInput` / `DevelopmentProjectUpdateInput`
都没有 `targetMarginRate`，而后端开着 `fail-on-unknown-properties: true` ⇒
调用方硬塞一个毛利率进来会直接 400。这比「服务端忽略它」更硬。

### 决策三：成本取不到的三种状态，DB 层与服务层同步

`development_project` 上有三条 CHECK 互相咬合：

```
cost_consistent     : cost_available ⇒ 有 target_cost + cost_currency = currency + 来源非 UNAVAILABLE
                      NOT cost_available ⇒ target_cost IS NULL（不是 0）+ 来源 = UNAVAILABLE + 点名归谁管
margin_consistent   : cost_available ⇔ target_margin_rate IS NOT NULL
margin_range        : target_margin_rate < 1（成本非负 ⇒ 毛利 < 1）
```

**「成本真的是 0」与「成本取不到」是两件事**：`targetMarginRate(price, BigDecimal.ZERO)` 返回
`1.000000`（这是「这个品白送」的结论，不是假数据）；而「取不到」是 `null` +
`cost_owner_module` 点名归谁管。两者在单元测试里各有一条用例钉住。

成本来源三条：

1. `CATALOG_ESTIMATE` —— 经 `DevelopmentCostEstimatePort` 读 catalog 的 5.3.1.1 利润测算器
   （`POST /catalog/profit-calculator/calculate`）瀑布图的「采购成本」行。
   透传**调用方自己的** Authorization 头，租户由 catalog 从成员关系解析（与 `BrandQueryPort` 同构）。
2. `MANUAL_ESTIMATE` —— 租户按供应商报价 / BOM 手工估算（新品还没建档时的常态）。
3. `UNAVAILABLE` —— 取不到。

🔴 跨币种判为「取不到」并点名 5.2.2.1 汇率查询（**绝不按 1:1 折算**）；
🔴 应用估算失败时返回 503 并**保留原有成本**（静默清空会让页面上的毛利无声地变成「无数据」，
而人以为成本还在）。

### 决策四：14 广告的市场数据如实标不可用，手工录入必须带来源与采集时间

任务书点名的**第三个**易踩空点。实查：`git ls-tree integration/module-dev backend/services/`
只有 catalog / channel / crm / identity / trade / warehouse —— **reporting 不在**。
卷 14 的主责服务是 channel + reporting，缺 reporting ⇒ 系统侧市场数据（类目规模 /
竞品价位 / 搜索热度）**取不到**。

落法：

- 能力声明 `marketDataAvailable=false` + `ownerModule='14 广告'` + 影响说明。
- **零个市场规模表** —— 建了就是编一个不存在的上游。
- 租户**手工录入**的观察值走 `development_project_market_observation`：
  `source` 与 `collected_at` 都 NOT NULL 且非空（DB CHECK + 服务层校验，缺一个就 400）。
  「没有来源的市场数据比没有市场数据更危险」—— 它会被当成系统的结论。

### 决策五：只产交接单，不代下游建单

- **3.1.1.1 SKU 建档**在 catalog 服务（已交付），SKU 的唯一写入所有者是 catalog ⇒
  本模块**只产候选参数**（`GET .../{id}/sku-candidate`：品类 / 目标市场与平台 / 目标售价区间 /
  规格候选 / 上市时间目标 / 打样与量产计划）+ 交接单（`handoff_type='SKU_ONBOARDING'`，
  `target_module='3.1.1.1'`）。**不建 SKU、不写 catalog 的表。**
- **6.6.1.2 打样 / 6.6.2.1 试产未交付** ⇒ 只产交接单（`SAMPLING_ORDER` / `PILOT_ORDER`），
  `target_module` 由 CHECK 钉死。**本模块的运行不依赖任何未交付模块。**
- 交接单载荷是**候选参数**的 JSON，不是「下游单据」—— 界面上显示「已交接，待 6.6.1.2 消费」，
  而不是「打样单已创建」。

## 踩过的坑

1. **catalog 侧 5.3.1.1 的 calculate 需要 `skuId`** —— 新品还没建档时没有 SKU。
   所以成本基准靠**参考 SKU**（`reference_sku_id`）挂载：挂了才有系统来源的成本，
   没挂就只能手工估算。这是**如实建模**而不是绕开：能力声明里写清楚了
   「5.3.1.1 已交付，但它的取数入口是 SKU 维度的，新品在建档前没有 SKU」。
2. **codegen 跨服务同名 DTO 撞车** —— `StatusOption`（warehouse）与 `StatusCount`（channel）
   与本模块的结构不同，codegen 直接非零退出。按仓库建议**加模块前缀**消解
   （`DevelopmentStatusOption` / `DevelopmentStatusCount`），**不写碰撞白名单**。
3. **codegen 把 enum 内联成字符串联合**，不导出具名类型 ⇒ 前端不能
   `import type { DevelopmentStatus }`。改为在模型里**显式声明**三个字面量联合
   （并写明为什么），不 import 一个并不存在的名字。
4. **集成头里 channel 的契约是漂的**（16.4.2.2 品牌凭证车道的既有产物 + `1e-06` 浮点格式差异）。
   codegen 跑一遍会把 channel 也改掉 —— 但那是**别的车道的工作**，本车道
   **还原了 channel 那两份**，只提交 trade 的变更（合并驱动按类型名 / 路径求并，不会互相覆盖）。

## 文件清单

### 后端（trade 服务）

- `backend/services/trade/src/main/resources/db/migration/V257__product_development_project.sql`
  —— 七张表 + RLS + 索引 + GRANT + 五个触发器（迁移事件插入归一 / 迁移事件不可改写 /
  状态守卫 / 立项身份列冻结）。
- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentProjectModels.java`
  —— 传输模型（24 个 DTO，全部以 `Development` / `DevelopmentProject` 开头，避免 springdoc 键冲突）。
- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentProjectRules.java`
  —— 纯算式与状态机（不碰数据库，可单测）。
- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentCostEstimatePort.java`
  —— 读 5.3.1.1 利润测算器的跨服务只读端口。
- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentProjectService.java`
  —— jOOQ 显式 SQL 业务层。
- `backend/services/trade/src/main/java/com/starcon/trade/DevelopmentProjectController.java`
  —— 16 条路径 / 25 个动作。
- `backend/services/trade/src/test/java/com/starcon/trade/DevelopmentProjectRulesTest.java`（20 例）
- `backend/services/trade/src/test/java/com/starcon/trade/DevelopmentProjectIntegrationTest.java`（23 例）
- `backend/services/trade/src/test/java/com/starcon/trade/DevelopmentProjectTenancyIntegrationTest.java`（3 例）

### 前端

- `apps/web/src/components/purchase/development-project-model.ts` —— 类型 / 调用点 / 展示口径。
- `apps/web/src/components/purchase/development-project-model.test.ts`（17 例）
- `apps/web/src/components/purchase/development-project-workspace.tsx` —— 真实工作区。
- `apps/web/src/app/purchase/development/projects/page.tsx` —— 前端路由。
- `apps/web/src/app/api/trade/development-project-allowlist.test.ts`（6 例）
- `apps/web/src/lib/api-proxy.ts` —— 白名单**外科式最小追加**（新增一条 `development-projects` 元素，
  追加在既有 `purchase-plans` 元素之后；既有条目一字未动）。

### 契约与台账

- `packages/api-contracts/src/contracts/trade.ts` / `trade.openapi.json` —— codegen 重生成
  （**只改 trade 两份**）。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.6.1.1 一行的「状态」「证据」两格。

## 验证

```
cd backend && ./mvnw -q compile                                   → 退出 0
cd backend && ./mvnw -pl services/trade -am test \
    -Dtest='DevelopmentProject*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
                                                               → 46 例 0 失败 0 错误
pnpm typecheck                                                   → 3/3 通过
pnpm lint                                                        → 通过（本车道零告警）
pnpm vitest run apps/web/src/components/purchase/development-project-model.test.ts \
                   apps/web/src/app/api/trade/development-project-allowlist.test.ts
                                                               → 23 例全绿
pnpm vitest run apps/web/src/app/api/trade                        → 54 文件 290 例全绿
```

**全量 allowlist 回归**是必跑的：白名单只在浏览器端生效，取错一侧会静默删掉别人已交付的路由，
表现是页面 404 而 typecheck / lint / vitest / Playwright **全部全绿**。

## 后续待办

1. **6.6.1.2 打样与样品管理**（未交付）交付后，消费本模块的 `SAMPLING_ORDER` 交接单；
   打样费用是新品开发的真实成本，需进费用归集，否则新品利润被高估。
2. **6.6.2.1 试产单**（未交付）交付后，消费 `PILOT_ORDER` 交接单。
3. **6.6.2.2 转量产与建档**交付后，消费 `SKU_ONBOARDING` 交接单（候选参数 → 正式 SKU）。
4. **14 广告（市场数据）**：卷 14 的 reporting 服务在本仓不存在 ⇒ 系统侧市场数据持续 unavailable。
   待其交付后，可把 `development_project_market_observation` 升级为「系统值 + 人工观察」两栏并存。
5. **5.2.2.1 汇率查询**未交付 ⇒ 跨币种的目标成本判为「取不到」。待其交付后，
   `DevelopmentCostEstimatePort` 可支持跨币种折算（方向显式：1 成本币种 = X 售价币种）。
