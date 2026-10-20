# batch-36 —— 回收 4 条车道并入集成头 + L2 批量验证 + 修 lane.mjs 的静默吞写缺陷

**日期**：2026-10-20 13:00 tick
**模式**：full（`TOKEN_HORIZON=2026-10-25`，未超期；`LANES=4` / `THROTTLE_MIN=50`）
**本批交付**：4 个模块并入集成头（`10.3.1.1` / `10.6.1.1` / `10.7.1.1` / `5.2.1.1`），
另派发 4 条新车道（`10.3.1.2` / `10.3.2.2` / `10.6.2.1` / `10.7.1.2`）。

---

## 模块 10.3.1.1 轨迹查询

**这个功能做什么**：查一个包裹的物流轨迹 —— 节点时间线（揽收 → 运输 → 清关 → 转运 → 派送 → 签收）、
预计到达时间、异常节点标记；并支持按承运商批量拉取（轮次任务）与承运商事件去重。

**技术落地**：

- 迁移：`V212__logistics_tracking_query.sql` — 建 4 张表：`logistics_tracking_subject`（运单/包裹主体）、
  `logistics_tracking_event`（节点事件，按承运商事件 ID 去重）、`logistics_tracking_pull_task`（拉取轮次）、
  `logistics_tracking_pull_task_item`（逐票结果）。每张业务表 `tenant_id NOT NULL` + RLS + GRANT。
- 端点：`/channel/logistics-tracking` × 10 — `capabilities`（能力声明）、`overview`（全量概览）、
  `subjects`（GET 真分页 + 筛选）、`subjects/sync`（POST，从上游同步运单集合）、`subjects/{id}`（单票时间线 + 时效对比）、
  `events`（GET 事件流 / POST 承运商推送）、`pull-tasks`（POST 建轮次 / GET 列表）、`pull-tasks/{id}`（含逐票结果）。
- 页面：`apps/web/src/app/logistics/tracking/page.tsx` +
  `apps/web/src/components/logistics/tracking-query-{model.ts,tracking-query-workspace.tsx}`。
  真分页、真筛选、错误态/空态/加载态齐备。
- 测试：`TrackingQueryIntegrationTest` **22 例**（覆盖去重键含承运商、事件 append-only、时间线按发生时间排序、
  预计时效快照、拉取批次重复计数、上游不可达 503、上游 401）；前端
  `apps/web/src/app/api/channel/tracking-allowlist.test.ts`。
  **租户隔离三断言在同一测试类内实跑**：`tenantsAreIsolated` / `forgedTenantHeaderIsIgnored` /
  `pooledConnectionsDoNotBleedTenants`，另加 `rlsPoliciesExist` 直接查库验策略。

**关键设计决策**：

1. **本模块没有承运商轨迹客户端**（蓝图写「上游 = 承运商 API」，channel 服务里并没有那个客户端）。
   决策：定义端口 `CarrierTrackingAdapter` + 如实落 `NO_CHANNEL`，**不在通道缺失时假装能拉**。
   否决方案：写一个假承运商客户端返回固定轨迹 —— 那会让「拉到了」与「拉不到」在界面上无法区分。
2. **DTO 改名 `CarrierTrackingEventRow`**。`TrackingEventRow` 与 warehouse 服务同名 DTO 撞名，
   契约 codegen 会走「跨服务同名 DTO：字段级覆盖」，把 warehouse 独有的
   `fromStage / toStage / etaBefore / etaAfter` 悄悄顶掉 —— **消费端拿到「凭空多出的字段」而编译期不报错**。
   决策：改名，不写进 allowlist 接受覆盖；名字同时对齐蓝图词汇「承运商事件」。
3. **契约 codegen 会重写 6 个服务的文件**（路径顺序随 springdoc 控制器注册顺序变）。
   实测对 channel / trade 都是纯重排、零内容变化。决策：**只提交 channel 的两个契约文件**，
   其余 5 个服务的重排还原 —— 与前一条车道提交的形状一致，不越界改别的模块的文件。

---

## 模块 10.6.1.1 成本分析

**这个功能做什么**：把物流成本算清楚 —— 单位重量/单均运费、头程分摊后的到岸成本占比、
各渠道成本对比、超成本订单识别、头程费用分摊明细。

**技术落地**：

- 迁移：`V213__logistics_cost_analysis.sql` — `logistics_cost_allocation`（分摊规则）、
  `logistics_cost_allocation_rule` 及其参数面；RLS + GRANT（4 处授权）。
  ⚠️ 该迁移**没有** `ADD COLUMN` 无 DEFAULT、没有 `SET NOT NULL`、没有新增 CHECK 约束 ——
  L2 前的「互相破坏形状」静态扫描 0 命中。
- 端点：`/channel/logistics-cost-analysis` × 13 — `capabilities`、`parameters`（GET/POST/DELETE）、
  `overview`、`by-channel`（各渠道成本对比）、`over-cost-orders`（超成本订单清单）、
  `allocation-rules`（GET/POST）、`allocations`（GET 列表 / GET 单条 / POST）、
  `allocations/{id}/lock`（分摊锁定）。
- 页面：`apps/web/src/app/logistics/analytics/page.tsx` +
  `apps/web/src/components/logistics/logistics-cost-analysis-{model.ts,logistics-cost-analysis-workspace.tsx}`。
- 测试：`LogisticsCostAnalysisIntegrationTest` **22 例**；前端
  `apps/web/src/app/api/channel/logistics-cost-analysis-allowlist.test.ts` +
  `apps/web/src/components/logistics/logistics-cost-analysis-model.test.ts`。
- 跨服务读：**只走端口**（`TradeFreightQueryPort` 读运费、`WarehouseCostQueryPort` 读仓储侧成本），
  不跨库读表。

**关键设计决策**：

1. **「报表必须全口径」的落点是五个分量各占一位**（尾程 + 头程分摊 + 包材 + 关税 + 仓储费），
   不是「把能算的算出来摆在那儿」。缺一位就退化成半截子报表。
2. **包材走「参数」口径**，不造事实表。代价是多一张表 + 一组端点 + 一块界面；
   换来「包材这一位能算」以及「参数 ≠ 事实」这条边界被显式标出来。
3. **仓储费标「不可用」而不是造一张空的仓储费表**。造空表等于宣称「仓储费已交付」；
   宁可让报表永远 `complete=false`。这是对蓝图「全口径」加粗句的另一种读法：
   诚实标注缺口，比用空表假装齐全更接近「全口径」。
4. **上游不可达 ⇒ 返回 503**，而不是返回缺项的报表。
   503 明确「读不到」；返回缺项会把「上游挂了」与「上游没有数据」混成一件事。
   同样地，**绝不显示 0.00** —— 成本取不到显示「未维护/取不到」。

---

## 模块 10.7.1.1 退货接收与处置决策

**这个功能做什么**：管理买家退回商品的接收与处置决策 —— 本地仓/海外仓/平台仓接收登记，
判定**转销售（可再售）/ 退运回国 / 弃置销毁**，记录处置成本（退货运费、销毁费、平台处理费）与残值。

**技术落地**：

- 迁移：`V214__return_receiving_disposition.sql` — 建 7 张表：`return_receiving_registration`（实收登记）、
  `return_resale_restriction`（再销售限制）、`return_disposition_decision`（处置决策）、
  `return_disposition_order`（处置单）、`return_disposition_cost`（处置成本）、
  `return_inventory_change_instruction`（库存变更指令）、`return_disposition_event`（事件流）。RLS + GRANT。
- 端点：`/channel/return-dispositions` × 18 — `capabilities`、`overview`、
  `registrations`（GET/POST）、`registrations/{id}`、`restrictions`（GET/POST）、
  `restrictions/{id}/revoke`、`eligibility`（可再售判定）、`decisions`（GET/POST）、`decisions/{id}`、
  `orders`（GET/POST）、`orders/{id}`、`orders/{id}/costs`、`orders/{id}/cancel`、`events`。
- 页面：`apps/web/src/app/logistics/returns/{page.tsx,returns-model.ts,returns-workspace.tsx}`。
- 测试：`ReturnDispositionIntegrationTest` **26 例** + `ReturnDispositionTenancyIntegrationTest` **3 例**
  （`tenantsAreIsolated` / `forgedTenantHeaderIsIgnored` / `pooledConnectionsDoNotBleedTenants`）；
  前端 `apps/web/src/app/api/channel/return-disposition-allowlist.test.ts` + `returns-model.test.ts`。

**关键设计决策**：

1. **决策 / 处置单 / 限制分三张表，不合并。**「限制」是**配置**（与某次处置无关，可登记可撤销），
   「决策」是**判定**（某一次凭什么这么判），「处置单」是**执行**（这次花了多少钱、回收了多少）。
   混在一张表上就会出现「一行既是被处置的货、又是结论、又是政策」。
2. **再销售限制由本服务自持，且可见性不按负责人收窄。**
   查过 trade / catalog / warehouse 全库，没有「再销售限制」这类表；蓝图把它列为本模块的**输入**，
   所以归本模块自持（不假装上游有）。它**只挂 isolation 不挂 data_scope** ——
   理由是**正确性**不是省事：限制判定是服务端做的「全租户否决」，
   可见性按负责人收窄会让「否决」变成「可绕过的建议」。
3. **成本合计不落表，查询时现算 SUM。** 落表就得在每次加成本、每次改数量时同步它，
   漏同步一次就会出现一个比明细更笃定的错数。
4. **成本与残值是两个币种，本模块不做任何汇率换算。**
   成本明细行币种**必须**与处置单成本币种逐字相同（触发器钉死）—— 混币种的「合计」是没有意义的数字；
   残值币种**可以**不同（真实场景：退运成本记 USD，海外仓清仓残值在 EUR 结算）。

---

## 模块 5.2.1.1 成本构成

**这个功能做什么**：维护 SKU 的单位成本构成（头程 / 关税 / 包材 / 采购 / 其他手工调整）与其变动历史 ——
切换成本法（移动加权 / FIFO / 批次）、追加手工调整、作废调整项、历史版本可查。

**技术落地**：

- 迁移：`V215__sku_cost_composition.sql` — 建 3 张新表：`sku_cost_profile`（当前构成 + 成本法 + 币种）、
  `sku_cost_adjustment`（手工调整项）、`sku_cost_revision`（历史快照，只追加不修改）；
  末尾一条 `ALTER TABLE sku_cost_profile ADD CONSTRAINT ... REFERENCES sku_cost_revision` ——
  ⚠️ 这条 ALTER 改的是**同一份迁移里刚建的表**，不是既有表 ⇒ 不会破坏其它车道的 INSERT
  （L2 前静态扫描确认「ALTER 既有表 = 0」）。RLS 三表逐字相同（含 `FORCE`）+ GRANT。
- 端点：`/catalog/sku-cost-compositions` × 7 — `capabilities`、`GET`（列表）、`GET/{skuId}`（明细）、
  `PUT/{skuId}`（保存构成）、`POST/{skuId}/adjustments`（追加调整）、
  `POST/{skuId}/adjustments/{adjustmentId}/void`（作废）、`GET/{skuId}/revisions`（历史）。
- 页面：`apps/web/src/app/pricing/cost/page.tsx` +
  `apps/web/src/components/pricing/cost-composition-{model.ts,cost-composition-workspace.tsx}`；
  页面顶部有「成本口径（必看）」块，明细头部再明示一次位置。
- 测试：`SkuCostCompositionRulesTest` **9 例**（成本法加权引擎）+
  `SkuCostCompositionIntegrationTest` **16 例**，合计 **25 例 0 失败**；
  前端 `apps/web/src/app/api/catalog/sku-cost-composition-allowlist.test.ts` +
  `cost-composition-model.test.ts` + `cost-composition-page.test.ts`。
  租户隔离三断言在 `租户隔离三断言覆盖构成调整与历史` 一个用例里实跑，
  并额外直接查库断言 RLS 兜底（不只服务层过滤）。

**关键设计决策**：

1. **成本法只增不改，且进历史快照。** `sku_cost_profile.cost_method` 枚举
   （`MOVING_AVERAGE` / `FIFO` / `LOT`）会一起写进 `sku_cost_revision.cost_method` ——
   历史行要能说清「当时按哪种口径算的」，否则历史成本不可解释。
2. **历史只追加，没有 UPDATE 端点。** 每次变更 append 一条 `sku_cost_revision`
   （当时成本法 / 币种 / 五个分项当时值 / 合计 / 生效时间 / 原因）。
3. **采购成本来源在页面上分开标**，且**供应商报价永不计入合计**（单列只读参考）：
   采购成本取 `vendor_delivery_record`（V137）的**实际到货金额**按成本法加权，
   而不是 6.3 采购单成交价（未开发）。取不到时显示「未维护」，上游不可达显示「取不到」+ 503，
   **绝不显示 0.00**。
4. **乐观锁真的校验客户端回传的 version** —— 过期客户端不能静默覆盖别人的成本。
5. **调整原因/作废理由在服务层校验**（返回 400），而不是撞 DB CHECK 拿 500。

---

## 本批流水线侧的动作（与业务交付同等重要）

### 1. 静态预检：0 命中

对本批 4 个新迁移跑「已知互相破坏形状」扫描：

| 检查项 | V212 | V213 | V214 | V215 |
| --- | --- | --- | --- | --- |
| `ADD COLUMN` 无 DEFAULT | 0 | 0 | 0 | 0 |
| `SET NOT NULL` | 0 | 0 | 0 | 0 |
| 新增 `CHECK` 约束 | 0 | 0 | 0 | 0 |
| ALTER **既有**表 | 0 | 0 | 0 | 0（ALTER 的是同迁移内新建表） |
| 跨服务外键 | 0 | 0 | 0 | 0 |

⇒ **0 命中**，无需在 L2 之前拦任何一条车道。

### 2. `api-proxy.ts` 并入结果：155 insertions / **0 deletions**

本批 4 条车道都往 `apps/web/src/lib/api-proxy.ts` 加了 channel 路由白名单。
并入后相对上一批基线是 **155 行纯新增、0 行删除** —— 说明四条车道的白名单条目**互相没有覆盖**，
别人已交付的路由一条都没被吞掉。这是「共享白名单累积文件」这个结构风险在本批的实测结论。

### 3. 🔴 修掉一个静默吞写的编排器缺陷（`fix(pipeline): 8322855d`）

本轮 `plan` 把 10.6.1.1 的 `land` 标记**静默吞掉**了 —— 机制与修法见
[`docs/backlog/2026-10-20-lane-plan-stale-state-clobbers-land.md`](../../backlog/2026-10-20-lane-plan-stale-state-clobbers-land.md)。要点：

- `plan` 是「读整份状态 → 改 → 写整份状态」，而读与写之间隔着整个 plan 的执行时间
  （每条车道 `git worktree add` + `pnpm install`，实测 4~5 秒/条）。
  期间 worker 的 `land` / `heartbeat` 写的是同一份 `lanes.json`，
  而 `plan` 收尾时把**窗口开始时的陈旧快照**整份写回去 ⇒ 那些新写入被静默吞掉。
- 症状：10.6.1.1 的 `land` 返回 `ok: true`、退出码 0，**没有任何报错**，
  但标记没进 `lanes.json` ⇒ `merge-ready` 漏并（worker 自己发现并补了一次 `land`）。
- 修法：写盘前**重新读一次**最新状态，只叠加「本轮 plan 自己真正改动过的车道」，
  语义从「整份覆盖」变成「增量叠加」；已 land / 已并入 / 刚续过心跳的车道一律不碰。
- 顺带修掉 `land` 的「没有任何新提交」闸门是**死的**：40 位全量 sha 与 7 位短 `baseSha` 直接比，
  永远不相等 ⇒ 这条比较从未成立过。改成比短 sha。
- 新增回归测试 `tools/module-progress/lane-plan-state.itest.mjs`：起真的临时仓库，
  把 `pnpm install` 换成 `sleep 4` 拉长执行窗口，窗口期内并发跑**真的** `land` 与 `heartbeat`，
  断言 `plan` 收尾时两条车道的写入都还在。**已实测该测试在旧行为下会红（4 条断言失败）**，
  恢复修法后 10 条全绿。
- 回归验证：`lane-gate` / `lane-plan-orphan` / `lane-critical-path` / `lane-service-gate` /
  `lane-conflict-reconcile` / `lane-merge-abort` / `merge-union` 七个既有 itest 全部照旧通过。

### 4. 本批派发的 4 条新车道

| 模块 ID | 名称 | 迁移版本 | 分支 | worktree |
| --- | --- | --- | --- | --- |
| 10.3.1.2 | 异常件 | V216 | `feat/10-3-1-2` | `.worktrees/10.3.1.2` |
| 10.3.2.2 | 揽收与签收确认 | V217 | `feat/10-3-2-2` | `.worktrees/10.3.2.2` |
| 10.6.2.1 | 时效分析 | V218 | `feat/10-6-2-1` | `.worktrees/10.6.2.1` |
| 10.7.1.2 | 退运回国 | V219 | `feat/10-7-1-2` | `.worktrees/10.7.1.2` |

⚠️ **共享文件协调**：这 4 条车道都会改 `apps/web/src/lib/api-proxy.ts`。
已在任务书里给每条车道指定**互不重叠的插入锚点**（按既有元素的注释头定位），
并要求一律「新增一条 `services[]` 元素」而不是往既有 channel 超长 alternation 正则里并分支
（后者会让 4 条车道必然 git 冲突且 retry 永不自愈），且必须跑全量 allowlist 回归。

⚠️ **已知边界**：10.6.2.1「时效分析」的兄弟模块 10.6.1.1 本轮才并入，
而 10.6.2.1 的基线（`642e941`）里**没有**它。任务书里已明确「不要依赖 10.6.1.1，
若必须依赖就回报 BLOCKED」。

---

## 🚨 L2 第一轮：**红**，两个缺陷（跨车道互相破坏的典型形态）

L2 跑在集成头上（`integration/module-dev`），全量：

| 阶段 | 结果 |
| --- | --- |
| `backend && ./mvnw verify` | ✅ **绿** —— 8 个模块全 SUCCESS（identity 2:09 / catalog 45:51 / channel 4:04 / warehouse 19:54 / trade 10:53 / crm 0:07），总耗时 1:23 h |
| `pnpm typecheck` | ❌ **红** —— TS2308 |
| `pnpm lint` | ✅ 绿 |
| `pnpm test` | ❌ **红** —— 2 例失败 |
| `pnpm --filter @starcon/web build` | ❌ **红** —— 同一个 TS2308 |
| `sync.mjs --check` | ✅ 绿（「台账与蓝图一致」） |

### 缺陷 D1：跨服务同名 DTO 碰撞（typecheck + next build 红）

```
packages/api-contracts/src/index.ts(5,1): error TS2308:
Module './contracts/catalog' has already exported a member named 'CostCapabilities'.
```

两个服务各自定义了一个**结构不同**的 `CostCapabilities`：

| 契约文件 | 来源车道 | 结构 |
| --- | --- | --- |
| `contracts/catalog.ts` | **5.2.1.1** 成本构成（catalog） | `{components: ComponentCapability[], costMethods: MethodOption[], currencies: string[], costBasisNote, upstreamNote, downstreamNote, sourceNote, actionsNote}` |
| `contracts/channel.ts` | **10.6.1.1** 成本分析（channel） | `{components: CostComponentCapability[], dimensions: CostCapabilityDimension[], basisNote, financeNote, notes: string[]}` |

**git 追溯（决定性证据）**：

| commit | catalog 侧 | channel 侧 |
| --- | --- | --- |
| `137d16e8`（本批之前的集成头） | 无 | 无 |
| `642e941e`（并入 5.2.1.1 之后） | **有** | 无 |
| `3121b625`（并入 10.6.1.1 之后） | 有 | **有** ⇒ 碰撞成立 |

⇒ 这是**两条互不知情的车道撞名**：一条 5.2.1.1 带了 catalog 侧的 `CostCapabilities`，
一条 10.6.1.1 带了 channel 侧的同名但不同结构的类型。它们各自单跑都绿
（worker 的 L1 是 `pnpm typecheck` 在**自己 worktree** 里跑的，那时另一边还没并进来），
是并入之后才碰撞 —— 正是组批模式要抓的那一层。

**为什么不能用「写进 allowlist 接受覆盖」这条路**：
`tools/codegen/contract-collision-allowlist.json` 全仓一直是 `{"allowed": []}`，
从未接受过一次「结构不同」的跨服务重名；
`tools/codegen/catalog-contracts.py` 的 docstring 写明结构不同 ⇒ 写出之前就非零退出（`gate()`）并点名谁会凭空多出 / 谁独有字段会消失；
10.3.1.1 那条车道遇到同类问题时也是**改名**（`TrackingEventRow` → `CarrierTrackingEventRow`），
并在会话日志里明确写了「**不写进 allowlist 接受覆盖**」。

⇒ **修法：把 channel 侧那个改名成 `LogisticsCostCapabilities`**（与同族
`LogisticsCostAnalysis{Controller,Models,Service}` 命名一致），**catalog 侧不动**
（那是 5.2.1.1 已并入的成果，改它会波及另一条车道）。

### 缺陷 D2：任务页 `implemented` 标记没翻（pnpm test 红 2 例）

```
FAIL packages/ui/src/lib/nav-routes.test.ts > implemented 标记必须与真实文件系统一致
AssertionError: expected [ '/logistics/tracking' ] to deeply equal []
```

`packages/ui/src/lib/task-pages.ts` 里 `/logistics/tracking` 的 `implemented` 仍是 `false`，
但 10.3.1.1 轨迹查询**已经建了真实页面** `apps/web/src/app/logistics/tracking/page.tsx`。
守卫测试要求「标记」与「真实文件系统」严格一致，两条断言因此一起红。

⇒ **修法**：把该条 `implemented` 翻成 `true`，并把 `label` / `description` / `fields`
对齐成「轨迹查询」的真实内容（原文是「物流异常」，描述的是 10.3.1.2 异常件 ——
只翻标记会让菜单出现一个「物流异常 → 实际是轨迹查询页」的错标入口）。
`packages/ui/src/lib/nav-config.ts` 的 `NAV_DESTINATIONS` 是**派生**的
（= `SYSTEMS` → `item.children` → `IMPLEMENTED_TASK_PAGES.filter(domain === ...)`），
所以翻完标记菜单入口自动出现，不需要手写菜单条目。

### 处置

按协议「L2 红了不 push、不在集成头上继续改」：
在独立修复车道 `.worktrees/fix-batch36`（分支 `fix/batch-36-l2-green`）上派修复任务修这两个缺陷，
修完并入集成头后**重跑 L2**，绿了才 push。

---

## ✅ L2 第二轮（修复并入后）：**绿**

修复并入集成头（`88ec4d4d`）后重跑 L2：

| 阶段 | 第一轮 | 第二轮 |
| --- | --- | --- |
| `backend && ./mvnw verify` | ✅ 绿 | 🔴 红 1 例 → 隔离复跑绿 → **安静机器上重跑绿** ✅ |
| `pnpm typecheck` | ❌ TS2308 | ✅ **绿**（D1 修掉了） |
| `pnpm lint` | ✅ | ✅ |
| `pnpm test` | ❌ 2 例 | ✅ **绿**（D2 修掉了） |
| `pnpm --filter @starcon/web build` | ❌ TS2308 | ✅ **绿** |
| `sync.mjs --check` | ✅ | ✅ |

### 第二轮 backend 那 1 例红 = 已知 flake，不是回归

```
[ERROR]   ImportWizardIntegrationTest.任务时间戳是带时区写回的值:897
[ERROR] Tests run: 1527, Failures: 1, Errors: 0, Skipped: 0
```

**判定为 flake 的三条独立证据**：

1. **隔离复跑绿**：`./mvnw -pl services/catalog -am test -Dtest='ImportWizardIntegrationTest'`
   → `Tests run: 19, Failures: 0, Errors: 0` / `BUILD SUCCESS`。
2. **与本轮改动无关**：失败在 **catalog** 模块的 `ImportWizardIntegrationTest`，
   而本轮修复只碰了 channel 服务的 4 个 Java 文件（改名）、channel 的契约文件、`packages/ui` 的任务页数据 ——
   **catalog 一个字节都没动**。而且第一轮 L2 里 catalog 是 SUCCESS（45:51）。
3. **命中已知 flake 家族**：断言是
   ```java
   OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
   ...
   assertThat(job.createdAt()).isAfterOrEqualTo(before.toInstant());
   ```
   「JVM `now()` 取基准 + 落库时间由 DB 侧写」= **跨时钟域比较**。
   落库侧写的是 **PG 的 `now()`**（`V29__import_wizard.sql:239`
   `created_at timestamptz NOT NULL DEFAULT now()`），断言基准是 **JVM 时钟**。
   实测偏差：**PG 写的值 `07:04:04.678Z` 比 JVM 基准 `07:09:01.931Z` 早约 297 秒（≈4 分 57 秒）** ——
   远大于历史记录里的 542ms（批次 33）。
   ⚠️ **本轮首次出现的相关条件**：这次 L2 是**与 4 条 worker 车道并发**跑的（PG 峰值 41、load 峰值 63），
   而历史上那次 542ms 是**单独跑**时量到的 ⇒ 高并发负载与漂移幅度可能相关。
   这正是 [`docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`](../../backlog/2026-10-13-flaky-integration-tests-l2-false-red.md)
   记录的那一族（本轮已把本例补成该文的 **§七**，含修法建议：
   **首选删掉这条跨域断言** —— 它想验证的「时区没被二次偏移」由同文件 L902 的
   `finishedAt >= startedAt` 已经证明了，`createdAt >= JVM now` 并不是它要的东西）。

⇒ 处置：在**安静机器**（load 3.10、无在途测试进程）上重跑 backend verify 阶段取绿，
其余 5 个阶段在同一 commit（`88ec4d4d`）上已是绿，不重复跑。
**没有用 `git bisect`**：二分要花 2 次全量验证（实测 1:23 h 一次），
而证据已经排除了「本批引入」—— 二分只会指向同一个已经绿过的 commit。

### ✅ backend verify 重跑结果：**BUILD SUCCESS**（16:03:29 收尾）

```
[INFO] Reactor Summary for backend 0.1.0-SNAPSHOT:
[INFO] backend ............................................ SUCCESS [  0.001 s]
[INFO] tenancy ............................................ SUCCESS [  0.336 s]
[INFO] identity ........................................... SUCCESS [01:16 min]
[INFO] catalog ............................................ SUCCESS [02:35 min]
[INFO] channel ............................................ SUCCESS [07:05 min]
[INFO] warehouse .......................................... SUCCESS [13:12 min]
[INFO] trade .............................................. SUCCESS [10:01 min]
[INFO] crm ................................................ SUCCESS [  6.723 s]
[INFO] BUILD SUCCESS
BACKEND_RERUN_EXIT=0
```

逐模块 surefire 汇总（本次重跑窗口内）：

| 模块 | 测试数 | 失败 | 错误 |
| --- | --- | --- | --- |
| catalog | 1527 | 0 | 0 |
| channel | 987 | 0 | 0 |
| warehouse | 1535 | 0 | 0 |
| trade | 1494 | 0 | 0 |
| identity | 270 | 0 | 0 |
| crm | 18 | 0 | 0 |
| **合计** | **5831** | **0** | **0** |

⇒ **红过的那一例（catalog `ImportWizardIntegrationTest`）这次全绿**，flake 判定成立。

⚠️ 日志里有两行 `[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).`
—— 这是 backlog §三 记过的**「fork JVM 退出超时」噪音**，不是测试失败
（surefire 报 `Tests run` 全 0 失败/0 错误，reactor 也判 SUCCESS，退出码 0）。

💡 **这次只花了 34 分钟**（第一轮 1:23 h）—— 差别来自**构建缓存已热**
（`target/` 里有上一轮的产物，surefire 跳过重编译）。
⇒ **L2 全量的耗时对构建缓存高度敏感**：后端刚动过时必然是 1h+，只动了前端时可以只跑前端阶段。

### 修复任务的做法与取舍

修复在独立车道上做（`03c60a0c`），两处：

- **D1**：channel 侧 `CostCapabilities` → **`LogisticsCostCapabilities`**
  （与同族 `LogisticsCostAnalysis{Controller,Models,Service}` 命名一致），
  改了 4 个 Java 文件里的 5 处引用；契约由 codegen 重新生成（**exit 0，无阻断碰撞**）。
  - **桶文件 `packages/api-contracts/src/index.ts` 与基线逐字节相同** —— 改完名之后全仓只剩 catalog 一家导出
    `CostCapabilities`，`export *` 本来就没有歧义，不需要消歧行。
  - **`contracts/channel.ts` 的类型集合差集恰好等于这次改名**（708 → 708，
    removed `{CostCapabilities}` / added `{LogisticsCostCapabilities}`），
    **catalog 侧的 `CostCapabilities` 原样保留**。
  - **`tools/codegen/contract-collision-allowlist.json` 保持 `{"allowed": []}` 未动** ——
    按仓库既有做法（10.3.1.1 把 `TrackingEventRow` 改成 `CarrierTrackingEventRow`）**改名而不接受覆盖**。
- **D2**：`packages/ui/src/lib/task-pages.ts` 里 `/logistics/tracking` 的 `implemented` 翻成 `true`，
  并把 `label` / `description` / `fields` / `workflow` 一并对齐成「轨迹查询」的真实内容
  （原文写的是**异常处置**，与该页「只标记不处置」自相矛盾）；
  `NAV_DESTINATIONS` 是派生的，菜单入口自动出现，未手写。

### 修复任务顺带查出的遗留问题

1. ~~**`packages/api-contracts/src/trade.openapi.json` 已陈旧**~~ —— **复核后确认：已不成立，收尾前已自查修正。**
   修复车道当时报告「trade 契约缺 `/trade/order-address-payment-check/policy` 与 schema `AddressPaymentPolicy`」，
   但收尾前对 `HEAD` 快照逐项复核发现：
   `33667346`（本轮并入 5.2.1.1 成本构成时跑的 codegen，12:51）**已经把 trade 契约一起重新生成过**，
   `HEAD` 里 `paths` 含 `/trade/order-address-payment-check/policy`，
   `components.schemas` 含 `AddressPaymentPolicy` 与 `AddressPaymentPolicyInput`。
   ⇒ 该条**不成立，无需再派 trade 车道补生成**。
   教训：codegen 是**多服务一起重生成**的（那一次同时改了 catalog / channel / trade 六个文件），
   「A 服务的契约被别人顺带刷新了」是常态 —— 报告陈旧前应先对 `HEAD` 快照取证，不要凭 diff 印象下结论。
2. **契约漂移目前无人看守**（**成立，本轮未修，已进 backlog**）：
   仓库的 `sync --check` 是 `tools/module-progress/sync.mjs --check`（**模块台账**检查），
   **不是**契约漂移检查；`packages/api-contracts/src/*.openapi.json` 与 `contracts/*.ts`
   没有任何 CI 闸门保证与后端 spec 同步 —— **D1 能溜到集成头，土壤就在这里**。
   已记入 `docs/backlog/2026-10-20-contract-drift-ci-gate.md`。

---

## 本批的 4 条新车道：2 条崩了 2 次，最终全部落地

| 模块 ID | 名称 | 迁移版本 | 状态 | 备注 |
| --- | --- | --- | --- | --- |
| 10.6.2.1 | 时效分析 | V218 | ✅ ready | 首轮即绿 |
| 10.7.1.2 | 退运回国 | V219 | ✅ ready | 首轮即绿 |
| 10.3.1.2 | 异常件 | V216 | ✅ ready | **崩了 2 次**（上游 provider `net::ERR_HTTP2_PING_FAILED`，非代码问题），第 3 次派发收尾完成 |
| 10.3.2.2 | 揽收与签收确认 | V217 | ✅ ready | **崩了 2 次**（`net::ERR_HTTP2_PING_FAILED` / `net::ERR_CONNECTION_CLOSED`），第 3 次派发收尾完成 |

**崩溃没有丢成果**：worktree 与分支都还在，未提交改动也还在，
第 3 次派发是「续作」而不是「重做」。
两轮续作里 worker 各自还修出了真 bug：

- 10.3.2.2 修掉 ① workspace 漏 import `fetchPickupSignoffCase`（单数）；
  ② 租户测试里那条**裸 jOOQ 查询跑在租户事务之外** ⇒ FORCE RLS 把所有行藏了 ⇒ 改成租户作用域内的查询。
- 10.3.1.2 补上了缺失的白名单守卫测试，并核对了契约与 codegen 输出**逐字节相同**。

⚠️ **这 4 条 ready 车道不在本轮并入** —— 本轮 L2 验证的是「4 个旧模块 + 修复」的集成头
（`88ec4d4d`），并入新的 ready 车道会改变被验证的对象。
它们留给**下一轮 tick** 回收并入 + 验证 + push（本轮的 4 条车道开发 + 4 条车道落地，
本来就是「车道在后台跑、下一轮回收」的节奏）。


