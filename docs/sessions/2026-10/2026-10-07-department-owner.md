# 2026-10-07 17.2.1.2 部门负责人

## 决策摘要

交付能力蓝图 17.2.1.2「部门负责人」（D1–D2，L3 锚点 17.2.1），上游 17.2.1.1 部门树，
下游对接 17.4 审批流。

核心设计判断：`department_leader` 表是「谁是负责人 + 他的授权」的**唯一事实源**，
V6 遗留的 `department.leader_membership_id` 降级为触发器维护的镜像列。

## 关键 trade-off

1. **事实源与镜像列的二选一**
   起初想在 `department_leader` 与 `department.leader_membership_id` 之间二选一，但两者都存
   「谁负责」会形成两个写入所有者（AGENTS.md 明令禁止）。最终选：`department_leader` 权威 +
   双向触发器同步镜像列。收益是 17.2.1.1 的树视图与既有端点**零改动**即可继续工作；
   代价是两个触发器互触发，用 `OLD IS DISTINCT FROM NEW` 守卫保证收敛。

2. **换人不清空授权口径**
   走部门表单换负责人时，`ON CONFLICT DO UPDATE` 只改 `membership_id`，保留原有
   `scope_mode` 与审批额度。新人接手既有范围是常见操作；「收紧授权」另有 `PUT /leader` 端点。

3. **审批额度必须带币种**
   只存数字没有币种时，「1000」在 USD 与 JPY 下是两回事，下游审批流无从比较。DB 层用
   `CHECK((approval_limit IS NULL) = (approval_currency IS NULL))` 强制成对出现。

4. **代管 vs 临时授权的语义切分**
   代管接手的是**整个职务**（继承负责人的范围与额度）；临时授权只借走一部分额度，
   必须有截止时间、额度不得超过负责人、币种必须一致。两者在审批链里分别以 PROXY / GRANT
   出现，顺序即审批优先级。

5. **审批链带 asOf 时间维度**
   权限随时间变化（代管、临时授权），历史单据必须能还原当时该谁批，所以
   `department_approval_chain(hash, dept, as_of)` 接受时刻参数，而不是只返回「此刻」。

6. **负责人可为空是硬约束**
   `department_leaders(hash)` 用 LEFT JOIN，无负责人的部门照样出现在列表里，
   审批链返回 `requiresFallback` 显式告诉 17.4 去配兜底人，而不是让下游拿到空数组后
   以为「这条单不需要审批」。

## 踩坑记录

- **`AFTER INSERT OR UPDATE ... WHEN (OLD...)` 被 PG 拒绝**（42P17）：INSERT 触发器的
  WHEN 不能引用 OLD。必须拆成 insert / update 两个触发器。
- **测试里直查表被 RLS 挡住**：集成测试直接用注入的 `DSLContext` 查
  `department_leader_grant` 读不到行——FORCE RLS 需要 `app.current_tenant`。
  测试必须自己开租户事务（`TenantTransactions` + 会话推导出的 tenant id）。
- **`@DecimalMin("0")` 默认含 0**：额度 0 会穿过 Java 校验撞上 DB CHECK 变成 409。
  改成 `@DecimalMin(value="0", inclusive=false)` 拿到语义正确的 400。
- **`@RequestParam OffsetDateTime` 不解析**：需要 `@DateTimeFormat(iso = ISO.DATE_TIME)`。
- **vitest 不认 `@/` 别名**：根 vitest 配置没有 `@/*`（那只在 Next 构建解析），
  model 文件要用相对路径 `'../../lib/api'`，与 `administration-model` 同一套约定。
- **`ResponseStatusException` 的错误体没有 `detail` 字段**（走 Spring 默认错误处理，
  不是 `ApiErrorHandler` 的 ProblemDetail）。测试改为断言安全性质本身
  （跨租户写被拒后父租户数据未变），而不是断言错误文案。

## 文件清单

迁移
- `backend/services/identity/src/main/resources/db/migration/V44__department_leader.sql`

后端
- `backend/services/identity/src/main/java/com/starcon/identity/AdministrationController.java`
  （17.2.1.2 段落：记录 + 7 个端点 + 快照/审计）
- `backend/services/identity/src/test/java/com/starcon/identity/DepartmentLeaderIntegrationTest.java`

前端
- `apps/web/src/app/org/department-leaders/page.tsx`
- `apps/web/src/components/iam/department-leader-workspace.tsx`
- `apps/web/src/components/iam/department-leader-model.ts`
- `apps/web/src/components/iam/department-leader-model.test.ts`
- `apps/web/src/components/iam/identity-workspace.tsx`（导航入口）
- `apps/web/src/components/iam/audit-model.ts`（新动作与资源类型中文标签）
- `apps/web/src/lib/api-proxy.ts`（identity 白名单）
- `apps/web/src/app/api/iam/[...path]/route.test.ts`（白名单正反用例）

契约（codegen 产物，勿手改）
- `packages/api-contracts/src/catalog.ts` 及四份 openapi 快照

台账
- `docs/product/capability-blueprint/module-progress.md`（仅 17.2.1.2 一行 + sync 重算的计数）

## 验证

- `cd backend && ./mvnw verify` — BUILD SUCCESS
- `pnpm typecheck` — 3/3 任务成功
- `pnpm lint` — 通过（仅 catalog 既有 warning）
- `pnpm test` — 49 文件 / 848 用例全绿（基线 48 / 840，净增 8，无回归）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` — Compiled successfully
- `node tools/module-progress/sync.mjs --check` — 台账与蓝图一致

## 后续待办

- **17.4 审批流**接入 `GET /identity/departments/{id}/approval-chain`：按部门 + 时刻解析审批人，
  并为 `requiresFallback=true` 的部门要求配置兜底人。
- 授权变更已写 `identity_audit`（resource_type `identity.department_leader` /
  `_proxy` / `_grant`）。V7 的历史回填 `CASE` 不含这三类，**存量**审计行若命中会落到
  `unknown`；新写入的走正常路径，不受影响。若后续要迁移历史数据再补。
- 管理范围 `scope_mode` 目前只参与审批链的输出展示，**尚未**接入 17.3.2.2 的数据范围隔离
  （那里是 `role_assignment.scope_mode` 的另一套语义，两者不要混）。