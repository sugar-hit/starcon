# 2026-10-24 12.2.2.1 付款单与审批 —— L3 12.2.2 的第一格

> 车道：`feat/12-2-2-1`（worktree `.worktrees/12.2.2.1`），基线 `integration/module-dev` @ `ccc62410`。
> 阶段 D6，L3 锚点 **12.2.2 付款**（该 L3 下**只有 12.2.2.1 与 12.2.2.2 两格**，
> 12.2.2.2「付款执行」未开发 ⇒ 本车道是该 L3 的**第一格**）。
> 迁移版本号由调度者预分配为 **V346**（同批另三条车道占 V347 / V348 / V349）。

## 这个功能做什么

发起一张付款申请：**选择应付明细、付款账户、币种与汇率、付款方式、分批安排**，然后**走分级审批**。
产出是**付款单（六态）+ 审批记录**。

蓝图原文的关系行是：「上游 = 12.1.2.1、12.1.2.2；下游 = 12.2.2.2、17 审批。
**付款审批是资金安全的第一道控制**，大额付款必须双人复核或多级审批。」

## 决策摘要

### 1. 审批引擎**不归本模块** —— 对接已交付的 17 审批，不另造一套

这是本车道最关键的一个决定。identity 服务的 17 审批**四个模块都已交付**
（审批流定义 / 审批规则 / 待我审批 / 审批记录），所以本模块只做三件事：

| 动作 | 上游端点 | 作用 |
| --- | --- | --- |
| 提交审批 | `POST /identity/approval-instances` | 登记成一条待审批实例 |
| 查生效流程 | `GET /identity/approval-flows/active?documentTypeCode=PAYMENT_ORDER` | 拿 `flowId` + 节点结构 |
| 读回链路 | `GET /identity/approval-records/{id}` | 镜像成本地审批记录 |

**金额阈值分支在 identity 侧真的生效**：起始节点是 identity 自己按
`approval_flow_amount_branch` 选出来的，本模块只如实报出路由依据
（`AMOUNT_BRANCH` = 命中阈值 / `FLOW_ORDER` = 流程顺序默认）。

**双人复核 / 多级审批**由 identity 审批流的**节点结构**承担：
同阶段 `PARALLEL` 节点 = 双人复核，多阶段 `SERIAL` 节点 = 多级审批。
本模块把该结构**如实展示**给用户，**不自己判定**。

⇒ **本模块刻意不提供本地的「审批通过 / 驳回」端点**。
两套审批引擎必然打架（同一个单据两处状态），而且放行一个不存在的写入口比 404 更糟。

**跨服务走授权 HTTP API 的 Port 模式**（`ApprovalPort`）：转发**调用方自己的 bearer**，
租户由 identity 按已认证成员关系解析 ⇒ 租户隔离是**两道**（finance RLS + identity RLS），
不是「本服务替对方决定租户」。

### 2. 「已付」**写不出来** —— 但六态状态机是齐的

蓝图要求付款单有完整六态（草稿/待审/已批/执行中/已付/失败），但 12.2.2.2「付款执行」未交付。
解法沿用本仓两处现成写法（12.1.1.3 坏账的 `accounting_status` 钉死、12.1.2.2 付款计划的
`execution_available` 钉死）：

- 状态值域**是齐的**（六态全在 CHECK 里）；
- `execution_available` 由**表级 CHECK 钉死为 false**，`execution_owner_module` 恒为 `'12.2.2.2'`；
- 触发器 `guard_finance_payment_order_state` 拒绝任何写入 `EXECUTING / PAID / FAILED`。

⇒ **「已付」不是一段没写的代码路径，而是走不通的路径**（直连 SQL 也一样）。
12.2.2.2 交付时**接管**这两个守卫即可，状态机无需改动。

### 3. 「已驳回」**不是一个状态**

蓝图六态里没有「已驳回」。被驳回的付款单**退回草稿**（可改可再提交），
驳回这件事记在**审批记录与事件流**里 —— 单据状态只回答「这笔钱现在走到哪一步」。

### 4. 本模块**刻意不写** `finance_payable_line.paid_amount`

V340 原文写的是「12.2.2.1 付款单与审批交付后由它写」。但**「已付」事实必须等到 12.2.2.2
真的把钱付出去那一刻才成立** —— 审批阶段就写 `paid_amount`，会让「已付」早于它真正发生。

⇒ **审批通过 ≠ 钱已付出。** 这一句同时体现在：DB 守卫、能力声明、页面文案、
以及前端色调（**「已批」刻意不是绿色成功态**）。

### 5. 未注册审批流 ⇒ **拒绝提交**，不按「未注册即默认放行」处理

identity 那句「未注册的变更类操作默认可直接执行」说的是**变更类操作**。
付款是**资金动作**，蓝图把「付款审批」写成资金安全的第一道控制 ——
静默放行一笔没人审过的付款，正是这条加粗句要防的事。

⇒ 报 404 并写明「单据类型 PAYMENT_ORDER 尚未注册生效审批流」，请去 17.4.1.1 注册。

## 关键 trade-off

| 选择 | 代价 | 收益 |
| --- | --- | --- |
| 审批走 identity 而非本地引擎 | 多一次跨服务调用 + 需要 `identity.approval_flow.read` / `identity.approval_record.read` 权限 | 只有一套审批真相；金额阈值与并行/串行是**已交付**的实现，不是重造 |
| 审批记录本地镜像一份 | 多一张表 + 同步逻辑 | 详情页不必每次跨服务调；「谁批的、批到哪一步」成为付款单自己的举证凭据 |
| 「已驳回」退回草稿而非独立状态 | 状态机少一态，与蓝图六态完全对齐 | 单据状态语义单一（走到哪一步）；驳回历史在审批记录与事件流里 |
| 分批合计校验放服务层而非触发器 | 跨行约束不在 DB 层 | 与 V343 里程碑比例同一手法：整套明细整体替换写入，逐行触发器会把「替换到一半」判成不一致 |
| 「已批」不是绿色成功态 | 视觉上「已批」与「待审」同色 | 不会被读成「钱已经付了」——这是资金安全的前端破口 |

## 文件清单

**迁移（1）**
- `backend/services/finance/src/main/resources/db/migration/V346__finance_payment_order.sql` ——
  五张新表（`finance_payment_order` / `_line` / `_installment` / `_event` / `finance_payment_approval_record`），
  零 ALTER 零 DROP 既有表。

**后端（5）**
- `backend/services/finance/src/main/java/com/starcon/finance/PaymentOrderModels.java`
- `.../PaymentOrderService.java`
- `.../PaymentOrderController.java`（10 个端点挂 `/finance/payment-orders`）
- `.../ApprovalPort.java`（17 审批，identity）
- `.../ExchangeRatePort.java`（5.2.2.1 汇率事实，catalog）

**前端（5）**
- `apps/web/src/app/finance/payment-orders/page.tsx`
- `apps/web/src/components/finance/payment-order-workspace.tsx`
- `apps/web/src/components/finance/payment-order-model.ts`
- `apps/web/src/components/finance/payment-order-model.test.ts`
- `apps/web/src/app/api/finance/payment-orders-allowlist.test.ts`

**共享文件（各改各的一处 / 一行）**
- `apps/web/src/lib/api-proxy.ts` —— **纯追加**一条独立 `services[]` 元素（首 token `payment-orders`，
  插入锚点 = 数组最开头）；既有条目一字未改、也不重排。
- `packages/api-contracts/src/contracts/finance.ts` + `src/finance.openapi.json` —— **codegen 产物，未手改**。
- `docs/product/capability-blueprint/module-progress.md` —— 只改本模块那一行的「状态」「证据」两格。

## 踩坑记录（供后续车道参考）

1. **BEFORE UPDATE 触发器返回 `OLD` 会让更新静默失效。**
   PostgreSQL 里 BEFORE UPDATE 触发器返回 `OLD` 等于「把这一行改回旧值」——
   语句成功、行没变，比报错更难查。守卫触发器必须**按 `TG_OP` 分支**：UPDATE 返回 `NEW`、DELETE 返回 `OLD`。
   （本次是「分批的已付写不出来」这条断言没报错才发现的。）
2. **BEFORE 触发器里的 `RAISE EXCEPTION` 占位符必须与参数个数一致。**
   `'...%...%...'` 配零个参数 ⇒ `too few parameters specified for RAISE` ⇒ 整条迁移失败。
3. **`finance_payable_line` 没有 `currency` 列** —— 币种在应付单头上。跨表取数时 JOIN 一次。
4. **服务层直接调用时没有 RLS 上下文** ⇒ 读不到任何行。测试里必须把调用包进
   `tenants.run(tenant, …)`（与既有 `PaymentPlanIntegrationTest` 同一写法）。
5. **汇总 SQL 的空 WHERE**：`where` 条件为空时拼 `... where group by` 是语法错误 ⇒ 条件为空就不拼 WHERE。

## 后续待办

- **12.2.2.2 付款执行（未交付）**：接管 `execution_available` 的 CHECK 与两个状态守卫触发器，
  推进 `APPROVED → EXECUTING → PAID / FAILED`，并在付款成功那一刻回写
  `finance_payable_line.paid_amount`。
- **12.7.1.1 资金账户（未交付）**：接管付款账户登记与余额；本模块的
  `payment_account_owner_module` 恒为 `'12.7.1.1'`，表结构不用改。
- **租户配置动作（非代码缺口）**：在 17.4.1.1 注册 `document_type_code = 'PAYMENT_ORDER'` 的
  `ACTIVE` 流程，否则付款单无法提交。
- **导航注册缺口**：页面路由 `/finance/payment-orders` 已建，但**刻意未**登记进
  `packages/ui/src/lib/task-pages.ts`（该文件是多车道共用的，四条车道并发改必然冲突），
  待调度者统一处理。
