# 2026-10-21: 6.2.2.2 需求合并与批量（阶段 D6）

## 摘要

车道 `6.2.2.2`：在 `integration/module-dev` 基线上交付蓝图 06 卷 6.2.2.2「需求合并与批量」，
后端主责服务 **trade**。

交付内容：迁移 `V253__purchase_demand_merge.sql`（六张新表 + 四道分组闸 + DB 层触发器 + RLS）、
模型 / 服务 / 控制器三个 Java 类、前端页面 `/purchase/demand/merge`（工作区 + 纯函数模型）、
api-proxy 一条**独立**白名单元素 + 双向 allowlist 断言、契约重生成（+24 DTO / +12 路径）、
台账与本会话记录。

L0 编译闸绿；L1 模块闸 `PurchaseDemandMerge*` **34 例 0 失败 0 错误 BUILD SUCCESS**；
`pnpm typecheck` 3/3 通过；前端 `apps/web` **374 文件 5763 例全绿**（含全量 api-proxy 白名单回归
134 文件 789 例）；eslint 零告警；prettier 全部合规。

> 本车道是**续做**：上一轮 worker 在写测试时被 `net::ERR_NETWORK_CHANGED` 打断。
> 本轮接手时迁移 / 模型 / 服务 / 控制器已 commit（`0ebf57ce`），三个测试文件是半成品。

## 决策 / 结论

### 1. 接手先判「写完没有」，再决定补哪里 —— 而不是重写

上一轮留下三个测试文件（854 / 465 / 258 行）。读完发现**结构完整、缺的是跑得通**：
编译过、但 L1 有 5 失败 + 1 错误。于是本轮的工作不是重写测试，而是
**把失败逐条归因到「谁的锅」**，修完人再把测试补成能钉住不变量的样子。

### 2. 🔴 接手后查出**三处真缺陷**（上一轮交付的迁移 / 服务里）

这三处不是「测试写错了」，是**功能直接不可用**：

| 缺陷 | 现象 | 根因 | 修法 |
| --- | --- | --- | --- |
| 迁移缺 `affected_line_count` 列 | **批量改数量与批量改供应商全部 409** | 服务层 `insertAdjustment` 写这一列、`toAdjustmentRow` 读这一列，但 V253 建表时没有它 ⇒ PG 42703 ⇒ jOOQ `DataAccessException` ⇒ 被统一错误处理翻成 409 | 补列（NOT NULL DEFAULT 1 + CHECK >= 1） |
| 打印读行表漏了草稿连接 | 批量打印 **400「字段格式不正确」** | `print` 用 `select *` 读行表再映射，而行的供应商展示快照在**草稿**上（草稿只有一个供应商，行表只有 `supplier_id`）⇒ jOOQ 取不到 `supplier_code` / `supplier_name` ⇒ `IllegalArgumentException` ⇒ 翻成 400 | 改用 `LINE_COLUMNS`（连草稿取快照），与 `batchDetail` / `draftDetail` / `pageLines` 同一口径 |
| 草稿行 `sort_rank` 写死成 1 | 同一张草稿里所有行位次相同，排序失去意义 | insert 里把位次写成了字面量 `1` | 按组内序次逐行递增（0,1,2…） |

**教训（值得留给下一轮）**：trade 服务的统一错误处理器把 `DataAccessException` 翻成
一句与现象无关的「履约路径数据与既有记录冲突」，把 `IllegalArgumentException` 翻成
「字段格式不正确，请检查类型、数量和必填项」。⇒ **同一个 409/400 可能来自完全不同的地方**，
归因必须回到 SQL 与 schema 去查，不能照着错误文案猜。
（`warehouse` 服务在自己的 `InTransitTrackingService` 里踩过**完全同款**的坑并留了注释：
「jOOQ 抛的是 `IllegalArgumentException`，被统一错误处理翻译成 400，看上去像『字段格式不正确』，
实际是『回读时少查了一列』。这种 400 极难从现象反推。」—— 本车道是第二次撞上。）

### 3. 🔴 蓝图加粗句「合并只在同一供应商内进行」用**四道闸**钉死

1. **分组键 = 供应商**：草稿表 `UNIQUE(tenant_id,batch_id,supplier_id)`
   ⇒ 一个批次内一个供应商最多一张草稿，一行需求只可能落进**一张**草稿。
2. **DB 层触发器** `guard_demand_merge_line_single_supplier`：行的 `supplier_id` 必须等于
   所属草稿的 `supplier_id`，不等直接 RAISE 23514 ⇒ **绕服务层写 SQL 同样被拒**。
3. **草稿的供应商 / 目标仓 / 法人 / 币种不可改写**（`guard_demand_merge_draft_guard`）
   ⇒「改供应商」只允许把行**搬到**另一个供应商的草稿，不允许就地改写草稿的供应商。
4. **服务层改完重新校验**：每张草稿 `count(distinct supplier_id) > 1` ⇒ 整批拒绝。

跨供应商的需求**不是被丢掉**，而是进 `purchase_demand_merge_unmerged` 并带
**明确原因码 + 中文原因原文**（SUPPLIER_MISMATCH / SUPPLIER_MISSING / PRICE_CONFLICT / …）。
理由：静默丢弃会让用户以为合掉了（用户以为合掉了 = 需求没下单）；偷偷归到第一个供应商会付错钱。

### 4. 🔴 「批量改供应商」会破坏第 3 条的分组不变式 ⇒ 改完**重新校验** + **整体生效或整体拒绝**

改供应商 = 把一批行从 SA 的草稿**搬到** SB 的草稿：

- SB 的草稿里已有同一 SKU ⇒ 数量相加、来源行改挂到既有行上（可追溯）；
- 两行单价冲突 ⇒ **整批拒绝**（合成一行会把其中一条需求的价格算错 —— 那正是对账与付款错误的来源）；
- 原草稿被搬空 ⇒ 草稿删除（空草稿不是凭据）；
- 搬完**重新校验分组**，不满足 ⇒ 整个事务回滚。

整个动作在**一个事务**里 ⇒ **库里不留「改了一半」的中间态**。批量改数量同理。
`affected_line_count`（本次批量改了多少行）与逐行留痕合起来是「改了多少」的凭据。

### 5. 🔴 6.3.1.2 采购单**未交付** ⇒ 交付的是「采购单**草稿**」

`backend/services/` 下没有任何采购单实体 ⇒ 本模块**绝不**造一张占位的假采购单表，
而是把合并产物落在自己的三张表上（草稿头 + 草稿行 + 行来源）。
草稿与采购单是**两张单据**（与 6.2.2.1「申请 ≠ 采购单」同一纪律）。
能力声明 `purchaseOrderDelivered=false`；页面**不出现**「采购单已创建」的字样；
**没有**「直接下单 / 提交采购单」端点；api-proxy 也不放行 `convert` / `purchase-orders`。

### 6. 上游只读，不碰别人的事实表

6.2.2.1 的 `purchase_request` / `purchase_request_line` **只 SELECT**：不 ALTER、不加约束、
**不建跨表外键**（外键会在对方删申请时连带处理本表，且等于把对方的生命周期绑到本模块上）。
「这行已被合并过」判断走本模块自己的 `purchase_demand_merge_line_source` 的
`UNIQUE(tenant_id,source_request_line_id)` ⇒ **一条需求行只能被合并一次**，
**不**往上游表上加列来打标记。

### 7. 前端：白名单**纯增量**，且反向钉住「不该存在的形状」

api-proxy 是多车道共用的**累积型**文件，取错一侧会静默删掉别人已交付的路由，
表现是页面 404 而 **typecheck / lint / vitest / Playwright 全部全绿**（白名单只在浏览器端生效）。
⇒ 本车道**新增一条独立 `services[]` 元素**（`prefix: 'trade/'` + 自己的 pattern），
**没有**往既有 trade 巨型正则里并分支；插入锚点固定在既有 `prefix: 'trade/'`
（6.5.1.1 采购对账）那条**之前**。
落地前用脚本逐条核对了本文件里**全部 108 条既有正则**：`demand-merges/**` **零命中**。

allowlist 测试做成**双向**：① 从本模块模型源码**反向抽取真实调用点**逐条断言被放行
（写死一份清单只会验证「清单与白名单一致」，真正会出事的是「源码加了新形状、白名单忘了登记」）；
② **未交付 / 不该存在的形状仍然 404**（`convert` / `purchase-orders` / `delete` / `approve` /
`reject` / `export` / `lines/{uuid}`）—— 放行不该存在的路径比 404 更糟，
它会让界面以为「已经可用」；③ 不误伤其它车道已登记的 trade 分支。

### 8. 台账只改**自己那一行**的两格，汇总数由脚本重算

台账 `docs/product/capability-blueprint/module-progress.md` 由
`node tools/module-progress/sync.mjs` 生成，**「状态」「证据」两列是人工维护的**，
脚本按模块 ID 继承。本车道手工改 6.2.2.2 那一行的两格后跑一次 sync，
脚本重算汇总数（已交付 274→275、未开发 191→190、完成率 58.9%→59.1%、D6 31→32、卷 06 14→15）。
**零行增删、零重排**。契约同理：只提交 trade 那一份，**别的服务的漂移已还原**
（那是别的车道未再生物）。

## 涉及文件

**后端（trade）**

- `backend/services/trade/src/main/resources/db/migration/V253__purchase_demand_merge.sql`（迁移）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseDemandMergeModels.java`（契约模型）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseDemandMergeService.java`（服务）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseDemandMergeController.java`（端点）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseDemandMergeFixture.java`（夹具）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseDemandMergeIntegrationTest.java`（30 例）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseDemandMergeTenancyIntegrationTest.java`（4 例）

**前端（web）**

- `apps/web/src/components/purchase/demand-merge-model.ts`（调用点 + 展示口径）
- `apps/web/src/components/purchase/demand-merge-workspace.tsx`（工作区）
- `apps/web/src/components/purchase/demand-merge-model.test.ts`（21 例）
- `apps/web/src/app/purchase/demand/merge/page.tsx`（页面）
- `apps/web/src/app/api/trade/demand-merge-allowlist.test.ts`（12 例，双向断言）
- `apps/web/src/lib/api-proxy.ts`（**仅新增一条 `services[]` 元素**，既有条目一字未动）

**契约 / 台账 / 会话**

- `packages/api-contracts/src/trade.openapi.json`、`packages/api-contracts/src/contracts/trade.ts`
- `docs/product/capability-blueprint/module-progress.md`（6.2.2.2 那一行 + 脚本重算的汇总数）
- `docs/sessions/2026-10/2026-10-21-demand-merge-batch.md`（本文件）

## 关键 trade-off / 讨论

### 补迁移的列，还是改服务绕开那一列？

`affected_line_count` 这一列在**已提交的迁移里缺失**，但**已提交的模型与服务都引用它**。
两条路：① 补列；② 改服务绕开（模型去掉字段、或读时用子查询算出来）。

选 ①。理由：
- 字段承载真实信息（「本次批量改了多少行」是「整体生效或整体拒绝」的凭据之一），
  ② 是把一个有语义的事实降级成推导值；
- V253 是**本车道自己**的迁移、**尚未并入集成头**（trade 服务在集成头上 V253 是空号，
  V252 在 warehouse、V255 在 channel，都不冲突）⇒ 改它的 Flyway 校验和无实际影响；
- ② 要改**已提交的模型**（`PurchaseDemandBatchAdjustmentRow`），那是 API 契约的一部分，
  比补一列改动大得多。

### 改已提交的迁移可不可以？

任务书写着「不要重写已有的迁移……只在**确有 bug** 时修（修了就单独起一个 `fix:` commit，说明为什么）」。
本次是**确有 bug**（缺列 ⇒ 两个批量端点全 409），所以修是允许的，并且**单独起了
`fix(6.2.2.2)` commit** 说明为什么。迁移里加列而不是重写整个文件。

### 前端要不要直接复用 6.2.2.1 的模型？

「批量生成合并」需要用户挑**来源申请单**，而本模块没有「列申请单」的端点（那是上游 6.2.2.1 的）。
选**复用** `purchase-request-model.ts` 的 `fetchPurchaseRequests({status:'APPROVED'})`
（那条路径**本来就在白名单里**），**不改它的任何文件**。
同时留了退路：上游清单读不到时（权限不足 / 服务不可达）弹窗**不阻塞**，
退回「手填申请单 ID」并说清为什么读不到。

### 为什么前端不自己按供应商分组？

后端已经把行分进了草稿，前端再按 `supplier_id` 自己分一次就是**两套真相**。
分组键取自**草稿 id**（后端的事实），前端只把同一草稿的行摆在一起显示。

## 后续待办

- ① **6.3.1.2 采购单未交付** ⇒ 本模块只产出采购单**草稿**；6.3.1.2 交付后由它读这份草稿
  去建真正的采购单（并把草稿置为 `CONVERTED`）。
- ② `trade.demand-merge.*` 权限码**未在 identity 注册**（`permission` 表是 identity 的写入所有权，
  跨服务禁止）—— 与 trade 服务**全线**既有模块（`trade.purchase-request.*` / `trade.purchase-plan.*` /
  `trade.orders.*`）同一口径，需要时由 identity 侧补注册。
- ③ 上一轮遗留的**已知漂移**（与本车道无关，未处理）：committed 的
  `packages/api-contracts/src/trade.openapi.json` 比 `contracts/trade.ts` 新 ——
  本车道重生成后两者已一致；catalog / channel / warehouse 的漂移属于别的车道，随各自的
  契约 commit 收敛。

## 关联

- 蓝图：`docs/product/capability-blueprint/06-purchase-and-supply.md`（6.2.2.2 一节）
- 台账：`docs/product/capability-blueprint/module-progress.md`（6.2.2.2 那一行）
- 兄弟模块：6.2.2.1 采购申请（`docs/sessions/2026-10/2026-10-21-purchase-request.md`）
- 同款「少查一列 ⇒ 400」的坑：`backend/services/warehouse/.../InTransitTrackingService.java`
  （`reloadAlert` 附近的注释）与 `docs/sessions/2026-10/` 下相关会话
- 多租户纪律：`AGENTS.md`「多租户」一节 · `docs/architecture/multi-tenancy.md`
