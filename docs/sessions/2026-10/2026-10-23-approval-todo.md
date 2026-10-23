# 1.1.1.3 审批待办（阶段 D7 · L3 锚点 1.1.1 / 路由 /dashboard/todo）

> 会话记录：**决策摘要 + 关键 trade-off + 文件清单 + 后续待办**，不复述过程。
> 本车道是卷 01（工作台与门户）在 1.1.1.2「异常订单队列」之后的第二格。

## 决策摘要

交付 1.1.1.3「审批待办」：identity 服务的 1 个只读端点（`GET /identity/approval-todos`）+
前端 `/dashboard/todo/approvals` 工作区 + 16 例后端集成测试（Testcontainers PostgreSQL 16）
+ 27 例前端模型单测 + 4 例 api-proxy 双向白名单测试。

**本模块是只读投影，不新建任何表、不占用 V337、不建后端微服务、不提供任何写端点。**
卷 01 的头把边界写死：「不产生新的业务事实，只聚合各系统的投影」「不做：任何写业务数据的操作。
工作台只读 + 跳转；一切状态变更回到归属系统完成」，蓝图 1.1.1.3 又写死
「**工作台不执行审批动作**，避免两处审批逻辑不一致」。
⇒ 审批通过 / 驳回 / 转交在能力声明里一律 `available=false` 并写明归属 17.4.2.1，
页面上渲染成**带缺口的说明**而不是第二个能改单子的按钮。

**上游表就在 identity 服务里**（卷 17 整服务尚未并入本仓，本仓只有 catalog / channel / crm /
identity / trade / warehouse 六个服务）。本模块不跨库查任何别的服务的表：

| 用途 | 事实来源 | 说明 |
| --- | --- | --- |
| 单据头信息、金额、申请人、当前节点、停留起点 | `approval_instance` | 17.4.2.1 拥有，本模块**只读** |
| 单据类型与流程名 | `approval_flow` + `approval_document_type` | 流程按单据类型注册 |
| 当前节点名称 / 顺序 / 审批人类型 | `approval_flow_node` | 按 `(flow_id, node_key)` 定位 |
| **超时口径** | `approval_flow_escalation.timeout_hours` | 节点上配的最小时限；同节点多条取**最先触发**的那条 |
| **金额阈值口径** | `approval_flow_amount_branch` | 流程上配的金额区间分支；上界**开区间**，按 `priority desc` 取档 |

「归我」的判定**逐字对齐**上游 `ApprovalInboxController`：被转交指派的人优先，否则按当前节点的
审批人类型判定（USER 指定人 / ROLE 角色分配）；POSITION 节点解析不出具体的人，本模块**不假装能解析**。
测试里有一条**清单等价性**断言把两边钉在一起 —— 两边一旦漂移，就会给出两套待办清单。

## 关键 trade-off

- **法人级金额阈值「判不了」就如实报判不了**（蓝图共享数据写的是
  `legal_entity`「金额阈值按法人配置」）：`legal_entity` 表**没有**金额阈值列，
  `approval_instance` 也**不带** `legal_entity_id`（它连法人是谁都记不下来）。
  取舍：在 `capabilities.filters` 里如实报 `available=false` 并写明缺什么，
  **不拿一个硬编码常量冒充审批 SLA 口径**。真正能用的是**流程上配的金额阈值分支**
  （`approval_flow_amount_branch`）—— 那是本仓真实存在的阈值配置。
- **「金额阈值分支」从「声明可用但筛不了」补成真筛选**：本车道开工时，前一轮留下的控制器把
  `AMOUNT_BRANCH` 报成 `available=true`，却**没有任何请求参数**能按它筛 —— 那是一条**假能力**
  （页面要么给一个点了没用的下拉，要么干脆不提供）。取舍：补 `amountBranchName` 请求参数
  + `amountBranches` 整集合分档，且**打在同一个 lateral 别名 `br` 上** ——
  于是「筛出来的那一档」与「行上显示的档位」是**同一个口径**，不会出现两边对不上。
  代价是多改一个端点契约；换来的是蓝图标名的「按金额阈值筛选」真的能用。
- **一个端点而不是三个**（`/approval-todos` + `/capabilities` + `/options`）：
  维度计数必须是**整集合**的计数，不是当前页的计数（前端只拿得到分页后的切片，
  用切片算出来的分档数会让「点一下就按这一类筛」对不上）。
  取舍：能力声明与四维分档**都放进同一个响应体**，前端一次请求拿全。
  代价是响应体更大；换来的是**计数与清单永远同源**、且少一次往返。
- **权限码沿用 `identity.approval_inbox.read`，不另立工作台专属码**：
  本模块读的就是「我的待审批」这同一批事实，另立一个码只会带来一个坏处 ——
  新建的角色默认没有这个码，于是管理员明明配了审批权限，待办页却静默空着。
  权限决定「能不能用这个页面」，节点决定「哪些单子归他」，两件事分开。
- **页面挂在 `/dashboard/todo/approvals` 子路由，不改 `/dashboard/todo/page.tsx`**：
  那个文件是兄弟模块 1.1.1.2「异常订单队列」的页面，本车道**不碰别人的文件**，
  并入时也就不会与之判冲突。1.1.1.1「我的待办」未交付，同一锚点下由后续车道补另外一块。
- **台账只改自己那一行**：跑 `tools/module-progress/sync.mjs` 会**重排整表**并改掉
  别的模块的行（例如 1.4.1.2 的路由格被改成 `—`）。本车道因此**只手工改 1.1.1.3 那一行的
  「状态」「证据」两格**（1 insertion / 1 deletion），汇总数由调度器在批次末尾统一刷新 ——
  与最近三条车道（1.5.1.1 / 1.4.1.2 / 1.2.2.2）一致。
- **MockMvc 的 URL 模板不能预先 URL 编码**：预编码过的 `%E5%B0%8F` 会被模板二次编码成
  `%25E5%25B0%258F`，服务端解出来的是字面量 `%E5%B0%8F` 而不是中文。
  单元测试里带中文档名的查询参数必须**原样**写进模板，让 MockMvc 自己编码一次。
- **`timestamptz` 一律用 `OffsetDateTime` 绑定 + `?::timestamptz`**：
  `Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s。写时间的测试**断言写回的值**，
  不只断言「没抛异常」。

## 文件清单

**后端（identity 服务，只读）**
- `backend/services/identity/src/main/java/com/starcon/identity/ApprovalTodoController.java`（新增，1 个 GET）
- `backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java`（新增，12 例）
- `backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoTenancyIntegrationTest.java`（新增，4 例）
- **迁移：无**（只读既有表 ⇒ **不占用 V337**）

**契约（codegen 产物，只由 codegen 写）**
- `packages/api-contracts/src/contracts/identity.ts`（重生成，+7 个 `ApprovalTodo*` 类型）
- `packages/api-contracts/src/identity.openapi.json`（重生成）

**前端**
- `apps/web/src/components/dashboard/approval-todo-model.ts`（新增，展示口径）
- `apps/web/src/components/dashboard/approval-todo-model.test.ts`（新增，27 例）
- `apps/web/src/components/dashboard/approval-todo-workspace.tsx`（新增，工作区）
- `apps/web/src/app/dashboard/todo/approvals/page.tsx`（新增，页面）
- `apps/web/src/app/api/iam/approval-todo-allowlist.test.ts`（新增，4 例）
- `apps/web/src/lib/api-proxy.ts`（**只新增一条独立的 `services[]` 元素**，+44 行 / **0 删除**）

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（**只改 1.1.1.3 那一行**）
- `docs/sessions/2026-10/2026-10-23-approval-todo.md`（本文件）

## 验证

- **L0 编译闸**：`./mvnw -q compile`（backend）· `pnpm typecheck`（3/3 包通过）
- **L1 模块闸**：`./mvnw -pl services/identity -am test -Dtest='ApprovalTodo*'` ⇒ **16 例全绿**
  （12 + 4，Testcontainers PostgreSQL 16，连接池压到 1）
- **前端**：`pnpm vitest run apps/web/src/components/dashboard/approval-todo-model.test.ts` ⇒ 27 例绿；
  `pnpm vitest run apps/web/src/app/api`（**全量 allowlist 回归**）⇒ **207 文件 1182 例全过**
- **lint**：`pnpm lint` ⇒ 3/3 任务通过（输出里的 warning 全在本车道之外的既有文件）

## 后续待办

- **1.1.1.1 我的待办**仍未交付 —— 同一 L3 锚点 `/dashboard/todo` 下的另一格，
  由后续车道补；本车道已留好扩展位（页面不替它实现）。
- **法人级金额阈值**要真正可判，需要 `legal_entity` 增加金额阈值列、
  且 `approval_instance` 带上 `legal_entity_id` —— 那是 17 侧的 schema 变更，
  不是工作台能自己补的；届时本模块的 `LEGAL_ENTITY_AMOUNT_THRESHOLD`
  能力声明可以从 `available=false` 翻成 `true`。
- **`/org/my-approvals` 详情页**目前只到列表级，不支持按实例 id 深链；
  本模块的「去审批」入口因此只到列表页，页面上另给「复制单号」作为交接物。
  若 17 侧补上深链参数，本模块的 `OPEN_APPROVAL_DETAIL` 目标可随之细化。
- **审批动作的归属页**（17.4.2.1 的审批详情）若日后支持按单据 id 直达，
  本模块的清单行「去审批」可带实例 id 跳过去。
