# 17.4.1.2 审批规则（触发规则 + 免审规则）

## 结论

能力蓝图 **17.4.1.2 审批规则** 在阶段 D4 交付：identity 服务新增 `V89__approval_trigger_rules.sql`、
`ApprovalRuleController`（7 个端点）、`ApprovalRuleIntegrationTest`（18 例），
以及 `/org/approval-rules` 配置工作区与前端模型（20 例单测）。

上游 17.4.1.1 审批流定义已交付并被本模块真实引用（`approval_flow` /
`approval_document_type` / `approval_position` / `role`）。

## 蓝图原文与落点

> 「配置审批触发规则：金额区间、跨法人、跨部门、新供应商、高风险商品等条件触发不同流程，
> 支持免审配置（小额免审）。」
> 「输入：触发条件配置；免审规则。输出：规则集；生效策略。」
> 「**免审要设上限金额**，否则"小额免审"会被用来绕过大额管控。」

## 决策摘要

### 1. 与 17.4.1.1 的分工写在文件头，不是靠约定

- 17.4.1.1 回答「这张单据**谁审**」：按单据类型注册流程、节点、金额阈值分支、超时升级。
- 17.4.1.2（本模块）回答「这次提交**会不会触发审批**」：触发条件、免审规则、
  以及一条都没命中时的生效策略。

因此本模块**不定义流程**：`approval_trigger_rule.target_flow_id` 只能指向 17.4.1.1
已存在的**生效**流程，写入前校验「必须是本单据类型的流程且状态为 ACTIVE」，
跨单据类型 / 悬空 id / 草稿流程各 400。避免两套节点体系。

### 2. 蓝图那句警告落成**一条数据库 CHECK**，不是应用层自律

绕过大额管控有两条路，所以要卡两处：

| 走法 | 拦截 |
| --- | --- |
| ① 没有上限的免审（金额再大都免审） | `amount_currency_code NOT NULL` + `amount_max_amount NOT NULL AND > 0` |
| ② 有上限但顺手豁免高风险条件 | 四个维度 **不接受 `REQUIRED`** |

第 ② 条容易被漏：一条「1000 以内免审」再叠一个「`REQUIRED` 跨法人」，
就把跨法人小额也免掉了 —— 上限还在，管控面已经被从侧面挖穿。
只有卡金额是不够的，两条都写进 `approval_trigger_rule_decision_shape`，
Java 侧只是把同样规则翻译成人能读懂的 400。

### 3. 四个布尔维度用**三态** ANY / REQUIRED / FORBIDDEN

蓝图点名的后四个条件（跨法人、跨部门、新供应商、高风险商品）原本是布尔，
但布尔只有一个 `false`，却要表达三件不同的事：

- 必须命中（`REQUIRED`）
- 必须不命中（`FORBIDDEN`）
- 不约束（`ANY`）

把「不约束」和「必须不命中」压成同一个 `false`，「小于 500 元的普通采购单」这条规则
就会莫名其妙地要求本次提交**不得**跨部门 —— 把 AND 写成了「非」的形状。
三态让每一维的判定只有一个答案。

### 4. 判定语义

- 规则集内 **按 priority 升序，先命中先生效**；`priority` 唯一，否则「命中哪条」没有答案。
- 金额区间是**半开区间 `[min, max)`**：上一条上限等于下一条下限是正常接续。
- 端点为 NULL 表示该侧无界；**免审规则的上限永远有界**（蓝图那句警告要保住的就是这个性质）。
- 币种参与匹配：USD 口径的免审额度不套用到 EUR 金额上。
- 一条都没命中 → 走规则集 `default_action`；没有生效规则集 → `controlled=false`，
  即蓝图「未注册的变更类操作默认可直接执行」。

### 5. 「默认免审 + 存在送审规则」在配置期就拒

这是本模块最容易配出来的静默漏洞：配规则的人以为大额会被送审，
实际上一条都没命中的提交直接放行。所以 `checkDefaultAction` 在保存时 400，
结构体检再给一条 `DEFAULT_EXEMPT` 提醒。

### 6. 规则集设为生效时，上游必须已有生效流程

写入顺序被写成显式约束（400，提示「请先在 17.4.1.1 注册并生效流程」），
而不是留到运行时才发现「送审无处可送」。草稿状态则允许先存后配。

## 关键 trade-off

- **`target_flow_id` 用 ON DELETE RESTRICT 而不是 CASCADE**：删掉一条正被规则引用的流程时
  宁可失败（409），也不要静默删掉管控规则 —— 那会让该单据类型无声掉回默认放行，
  正是本模块要防的事。代价是 17.4.1.1 的删流程端点在被引用时会返回 409，
  这比「删成功然后管控悄悄消失」好。
- **不建 `approval_effective_policy` 视图**：V83 的 `approval_flow_no_registration` 视图
  在 FORCE RLS 下由视图属主权限执行；本模块改用控制器里的显式 SQL（始终带 `tenant_id=?`）
  来产出「生效策略」，避免引入一个需要额外论证 RLS 绕过风险的查询面。
- **`/evaluate` 只做只读试算**：它是「规则集 + 生效策略」这个输出的自然读出口，
  判定复用运行时同一个 `matches()` 方法，所以配置期试算结论 = 下游提交结论。
  但它**不建审批实例、不出待办、不发通知、不起定时器** —— 运行时是 17.4.2.1。
  条件全是结构化列（金额区间 + 四维三态），**不解析也不执行表达式**。
- **独立页面权限码** `identity.approval_rule.*`，不复用 `identity.approval_flow.*`：
  「配流程」和「决定这次提交要不要走流程」是两种不同风险，改后者能绕过前者。
- **不碰 ApprovalFlowController**：新增 FK 让 17.4.1.1 的 `DELETE /approval-flows/{id}`
  在被引用时返回 409，这个行为变化只落在库级 RESTRICT 上，没有改它的控制器。

## 文件清单

### 新增

- `backend/services/identity/src/main/resources/db/migration/V89__approval_trigger_rules.sql`
- `backend/services/identity/src/main/java/com/starcon/identity/ApprovalRuleController.java`
- `backend/services/identity/src/test/java/com/starcon/identity/ApprovalRuleIntegrationTest.java`
- `apps/web/src/components/iam/approval-rule-model.ts`
- `apps/web/src/components/iam/approval-rule-model.test.ts`
- `apps/web/src/components/iam/approval-rule-workspace.tsx`
- `apps/web/src/app/org/approval-rules/page.tsx`

### 修改

- `apps/web/src/lib/api-proxy.ts` —— identity 白名单加 `approval-rules` 五个形状
  （集合 / `{uuid}` / `{uuid}/validation` / `effective-policy` / `evaluate`），
  字面量段排在 `[0-9a-f-]+` 之前判别；**刻意不放行任何审批实例路径**。
  加完已用脚本验证：56 条既有放行路径全部仍放行、5 条伪造形状仍 404、两层 alternation 都在。
- `apps/web/src/components/iam/identity-workspace.tsx` —— 导航目的地 + `visible()` +
  `WorkspaceKind` + 渲染分支。
- `packages/api-contracts/src/catalog.ts` / `identity.openapi.json` —— codegen 产物，纯新增 11 个类型。
- `backend/services/identity/src/test/java/com/starcon/identity/ApprovalFlowIntegrationTest.java`
  —— **跨车道必要改动**：本模块 `target_flow_id` 外键指向 `approval_flow`，
  PostgreSQL 要求被引用表与引用表同批 `TRUNCATE`，所以它的 `@BeforeEach` 清空列表要带上
  `approval_trigger_rule, approval_rule_set`。这是 17 例之外唯一改到别人文件的地方。

## 验证

- `cd backend && ./mvnw verify` —— 全绿（identity 集成测试 18 + 既有全部）。
- `ApprovalRuleIntegrationTest` 18 例、`ApprovalFlowIntegrationTest` 27 例（跨车道回归）。
- `pnpm typecheck` / `pnpm lint` / `pnpm test`（87 文件 1607 例，其中新模型 20 例）/
  `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`（`/org/approval-rules` 已产出）。
- `node tools/module-progress/sync.mjs --check`。

## 后续待办

- **17.4.2.1 待我审批**是运行时消费方：本模块的 `default_action` 与规则集就是它要的「生效策略」，
  `evaluate` 的判定口径应当被它复用（同一份条件语义，不要各写一套）。
- **审批实例、待办、超时定时器刻意没做**，也不在本模块的表里留状态列 ——
  将来补齐时必须连同实例、待办、时限一起加，而不是在本模块加一列「已发起」。
- `/evaluate` 目前是本租户自助试算。若将来要开放给业务系统在提交前调用，
  需要单独评估跨服务调用面（AGENTS.md 禁止跨服务 SQL），不要让它变成跨服务直查。