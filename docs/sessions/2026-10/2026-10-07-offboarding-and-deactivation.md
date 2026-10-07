# 17.2.2.3 离职与停用（identity，阶段 D1–D2）

车道 `feat/17-2-2-3`，基线 `integration/module-dev` @ `b9e5a0a`。
上游 = 17.2.2.1 成员管理（membership / role_assignment / `/members` CRUD），
并行兄弟 = 17.2.1.2 部门负责人（占 V45）、17.2.1.2（占 V44），本车道迁移固定 **V46**。

## 决策摘要

按蓝图 17.2.2.3 交付「停用成员（非删除）+ 转移在职单据与审批责任 + 回收待办与任务 +
保留历史操作记录」四块，四件套：迁移 `V46__member_offboarding.sql`、
端点 `/identity/offboarding` 下 8 个、页面 `/org/offboarding`、
测试 `OffboardingIntegrationTest` 14 例（后端）+ 10 例（前端）。

四块职责**各自都有真实读出口**，不是一个「离职」按钮配四段文案：

| 职责 | 真实读出口 |
| --- | --- |
| 停用成员 | `membership.status=INACTIVE` + 案卷 `memberName/status/effectiveAt` |
| 转移在职单据与审批责任 | `offboarding_responsibility_item` 的 PENDING→TRANSFERRED 明细 |
| 回收待办与任务 | `offboarding_reclaim_item` 的 RECLAIMED / REASSIGNED 明细 |
| 保留历史操作记录 | 案卷行 + audit 留痕 + 物理删除被 DB 拒绝 |

## 关键 trade-off

### 1. 「停用而非删除」做成四道防线，不靠调用方自觉

蓝图这句话被当硬约束实现，而不是措辞：

1. 控制器**不提供任何 DELETE 端点**（DELETE 打到案卷路径返回 405）；
2. 迁移**刻意不授予 `identity_app` DELETE** —— 物理删除在权限层就不存在；
3. 指向 `membership` 的外键全部 `ON DELETE RESTRICT`；
4. `membership` 上加 `guard_member_offboarding_delete` 触发器，沿用 V5
   `guard_legal_entity_delete` 的先例。

第 1 条被测试钉住（405），第 4 条被测试直连表钉住（抛「仍关联 N 条离职与停用记录」）。

### 2. 跨服务边界：只落台账，不读下游表

审批 17.4.2.2 与 01 工作台待办尚未交付，AGENTS.md 明令禁止跨服务 SQL/FK/视图。
因此 `source_system + reference_id` 只是**跨服务定位键，刻意不建外键**，
identity 也不去 trade / workflow 库里核对单据是否真的存在。

取舍是「宁可停在 PENDING，不伪装已完成」：案卷状态由**待结条目数推导**
（`settle()`），有 PENDING 就是 OPEN。下游缺席时台账如实显示未结，
而不是由操作人点一下就变成「处理完成」。

### 3. 责任转移与回收是两种不同的台账

待办（TODO）可关闭，任务（TASK）通常改派。若合成一张表加一个布尔「已处理」，
事后无法回答这条到底是消失了还是换了人。所以两张表两种状态机，
`RECLAIMED` 与 `REASSIGNED` 分开，且 CHECK 保证「改派必有接手人、直接回收不许有接手人」。

### 4. 撤销可逆，但条目不回滚

撤销恢复的是案卷里记下的 `previousStatus`，不是硬编码 `ACTIVE`（停用动作本身要如实留痕）。
已转移/已回收的条目**保留在台账里不删**：它们已经发生在下游服务中，
identity 无权假装没发生，这些行正是「当时转过什么」的证据。
`OPEN` 与 `COMPLETED` 都可撤销（台账已结清才发现人是误停是真实场景），只有 `REVERTED` 是终态。

### 5. 部门负责人必须先移交

离职成员若还挂着 `department.leader_membership_id`，部门会指向一个已离职的人，
审批链兜底人选一个停用账号等于没有兜底。因此未指定接手人时直接 403，
且拒绝后成员状态与案卷都不落地（测试钉住「不留半个结果」）。

## 踩到并修掉的三个真问题

1. **回收 UPDATE 的 cast 写错了列**。
   `reclaimed_by_membership_id=?::timestamptz` —— cast 落在了 UUID 列上而不是 `reclaimed_at`，
   CHECK 约束当场拒绝，又被 `ApiErrorHandler` 映射成 409，症状完全不像 cast 写错。
   改为 `reclaimed_at=?::timestamptz` 且位置避开末位。
2. **OWNER 判定用了不带租户条件的标量子查询**。
   `role_id=(select id from role where code='OWNER')` 在多租户库里每户各有一个 OWNER 角色，
   结果不确定（要么报错，要么命中别人家的）。改为 join 到 `role` 再按 code 判定，
   与 `AdministrationController.owner()` 同一写法。
3. **停用成员重新登录返回 403 而不是 401**。
   凭据本身没删（`app_user` 仍 ACTIVE），是 membership 停用导致建会话失败。
   按真实状态码断言，不为了好看把测试改成期望值。

时间字段沿用仓库纪律：`?::timestamptz` 一律放在 values **中部**而非末位
（末位会让 jOOQ 整体错位一格，裸 `?` 又会被当 varchar）。

## 文件清单

| 类别 | 路径 |
| --- | --- |
| 迁移 | `backend/services/identity/src/main/resources/db/migration/V46__member_offboarding.sql` |
| 后端 | `backend/services/identity/src/main/java/com/starcon/identity/OffboardingController.java` |
| 后端测试 | `backend/services/identity/src/test/java/com/starcon/identity/OffboardingIntegrationTest.java` |
| 契约 | `packages/api-contracts/src/identity.openapi.json`、`packages/api-contracts/src/catalog.ts` |
| 白名单 | `apps/web/src/lib/api-proxy.ts` |
| 白名单测试 | `apps/web/src/app/api/iam/[...path]/route.test.ts` |
| 前端 | `apps/web/src/components/iam/offboarding-workspace.tsx`、`offboarding-model.ts`、`offboarding-model.test.ts`、`apps/web/src/app/org/offboarding/page.tsx` |
| 导航 | `apps/web/src/components/iam/identity-workspace.tsx`（租户概览入口） |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（仅 17.2.2.3 行） |

## 验证

- `./mvnw verify`（backend）BUILD SUCCESS：identity 84（基线 70 + 新增 14）、
  catalog 1205、channel 66、warehouse 70，后三者与基线逐个相同。
- `pnpm typecheck` / `pnpm lint` / `pnpm test`（879 例，基线 869 + 新增 10）全绿。
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` 成功，路由表含 `/org/offboarding`。
- `node tools/module-progress/sync.mjs --check` 通过（已交付 72→73）。
- 契约重生成顺序：先跑 `OpenApiContractTest` 刷新 `backend/target/openapi`，再 codegen。
  diff 逐字核对：`identity.openapi.json` +7 path / +9 schema、**零删除、零既有 schema 变更**；
  `catalog.ts` 类型名集合**只增不减**，多的正是 9 个 `Offboarding*`；
  `catalog.openapi.json` 经语义比对为纯键序重排（301 path / 495 schema 完全一致），
  已 `git checkout` 还原，**不与本模块一起提交**。

## 后续待办

1. **下游消费台账**（本服务之外的接线，等 17.4.2.2 与 01 工作台交付）：
   trade / workflow 需经授权 API 登记 `offboarding_responsibility_item`，
   01 工作台需消费 `offboarding_reclaim_item`。本模块只提供台账与状态流转，
   不预判下游协议。
2. **事件外发**：`AdministrationController` 走 `identity_outbox`，本控制器目前只写 `identity_audit`。
   若下游选择订阅事件而非轮询，需补 outbox 写入（届时应与 audit 同事务）。
3. `OffboardingInput.departmentLeaderSuccessorMembershipId` 目前只在服务端校验，
   前端表单未做「该成员是否负责部门」的预判 —— 需一次额外成员-部门关系查询，
   成本高于收益，暂由服务端 403 + 明确文案兜底。