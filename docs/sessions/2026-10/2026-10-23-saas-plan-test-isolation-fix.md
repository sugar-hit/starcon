# 2026-10-23: 17.7.1.1 套餐与权益测试隔离修复

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`fix/17-7-1-1-test-isolation`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/17.7.1.1-fix`）
- **基线**：`integration/module-dev` @ `7208cb40`（已含 17.7.1.1 成果）
- **性质**：纯测试修复 —— **零生产代码、零迁移、零契约、零前端**

## 摘要

17.7.1.1 的 `SaasPlanIntegrationTest` 用共享的 root owner（`local-admin`）当平台运营身份，
`POST /identity/saas-platform/roles` 会把 PLATFORM_OPS 角色**永久挂到这个共享成员身上**，
污染同一次 maven 运行里的**所有** identity 测试类（identity 的 17 个测试类共用同一个 Testcontainers PG16 实例）。

后果：`local-admin` 的角色从 1 个（OWNER）变成 2 个（OWNER + PLATFORM_OPS），
而 `/auth/session` 的 `grants` 是**无序聚合**（V47 的 `session_access` 用 `jsonb_agg` 且没有 `ORDER BY`），
`IdentityIntegrationTest.ownerIsProtectedAndTransferIsExplicitAndCannotBeUsedToEscalate`
靠 `grants.get(0)` 猜 OWNER 角色 —— 一旦第一个落到 PLATFORM_OPS，
`DELETE /identity/roles/{PLATFORM_OPS}` 就**合法**（它不是 OWNER），返回 200 而不是期望的 403。

本车道修两处：**A** 让 17.7.1.1 的测试不再污染共享 owner；**B** 让 OWNER 角色的挑选不依赖无序聚合的偶然顺序。

## 根因（三段因果链）

1. **grants 是无序的** —— `backend/services/identity/src/main/resources/db/migration/V47__member_role_assignment.sql:167`
   的 `session_access(hash)`，其中第 174 行用
   `jsonb_agg(jsonb_build_object('roleId',...,'roleCode',...,'scopeMode',...,'permissions',...))`
   聚合角色，**没有 `ORDER BY`** ⇒ PostgreSQL 不保证返回顺序，取到哪个角色取决于查询计划，不取决于数据语义。
2. **17.7.1.1 给共享的 root owner 多挂了一个角色** ——
   `SaasPlanIntegrationTest.platformToken()` 原本就是 `login()`（以 `local-admin` 登录，root 租户的 OWNER），
   打 V323 的 `saas_platform_register_role`，该函数最后一句（`V323__saas_plan_entitlement.sql:373`）是
   `INSERT INTO role_assignment(tenant_id,membership_id,role_id,scope_mode,assigned_by) VALUES(tid,a.membership_id,rid,'ALL',a.user_id)`
   —— 把 PLATFORM_OPS 分配给**调用者本人**。
3. **两个角色时 `grants.get(0)` 不再确定** —— 落到 PLATFORM_OPS 时删角色请求合法 ⇒ 200 ≠ 403 ⇒ 测试红。

## 决策

### 修复 A（主责）：给平台运营身份一个**专属身份**，不再用共享的 local-admin —— 选 **A1**

做法（`SaasPlanIntegrationTest`）：

- 新增 `platformOpsOwnerToken()`：用真实的 `POST /identity/children` 造一个**专属子租户**，
  租户所有者是本次用例专属的账号（`saas-platform-ops-<随机标记>`），返回该所有者的 token。
- 新增 `registerPlatformRole(String token)`：平台运营角色登记（`POST /identity/saas-platform/roles` + `X-Internal-Key`）。
- `platformToken()` 改为 `platformOpsOwnerToken()` + `registerPlatformRole(token)`。
- `platformRoleRegistrationIsServerToServerOnly` 原本直接用 `login()`，一并换成 `platformOpsOwnerToken()` ——
  **这条路径同样会往调用者本人身上挂 PLATFORM_OPS**，是漏掉它就会残留污染的那一处。

为什么 A1 可行（结合 `saas_platform_gate` 按租户判定）：

- `saas_platform_gate`（`V323__saas_plan_entitlement.sql:323`）只要求 `saas_platform_role.tenant_id = a.tenant_id`，
  **没有根租户要求** ⇒ 子租户的所有者可以在**自己租户里**登记 PLATFORM_OPS 并通过闸门。
- 平台运营身份因此可以是专属的：它所在的租户就是一个普通子租户，
  跨租户能力仍由「该租户登记了平台运营角色 + 会话持有该角色 + scope_mode='ALL'」三道条件保证，
  与调用者是不是根租户无关。
- 共享的 `local-admin` 从头到尾只有 OWNER 一个角色 ⇒ **共享状态零污染**。

为什么不用 A2（跑完清理）：

清理要额外承担「清理失败怎么办」的分支（角色可能已被删、清理本身可能 403），
而且清理只在**本测试类之后**生效 —— 万一漏掉一条登记路径，污染依然泄漏。
专属身份是从**根上**断掉污染源，没有「清理干净了没」的第二种状态。

为什么不用 A3（其它等价做法）：

「每个用例用独立租户、跑完删库」这类做法在本仓不可行：identity 的 17 个测试类共用
**同一个** Testcontainers PG16 实例与**同一个** `local-admin`，没有 per-class schema 切换机制；
强行引入要改共享底座 `IdentityIntegrationTestBase`，超出本车道授权范围。

### 修复 B（次责，调度者已授权）：`IdentityIntegrationTest` 按 roleCode 显式挑 OWNER

只改 `ownerIsProtectedAndTransferIsExplicitAndCannotBeUsedToEscalate` 里取 `ownerRole` 的那一句，
不重构该方法、不改它的其它断言、不动这个测试类的其它用例。

改前：

```java
String ownerRole = me.get("grants").get(0).get("roleId").asText(),
    ownerMember = me.get("membershipId").asText();
```

改后：

```java
String ownerRole = null;
for (JsonNode grant : me.get("grants"))
  if ("OWNER".equals(grant.get("roleCode").asText()))
    ownerRole = grant.get("roleId").asText();
assertThat(ownerRole).as("会话里必须带 OWNER 角色").isNotNull();
String ownerMember = me.get("membershipId").asText();
```

### 为什么 A 和 B 都要做

- 只做 A：owner 恢复成单角色，`grants.get(0)` 又是确定的了 —— 但这条测试**仍然**依赖一个无序聚合的偶然顺序；
  将来任何一个模块再给 owner 加个角色，同样的坑会再踩一次。
- 只做 B：当前会绿，但 PLATFORM_OPS 仍然挂在共享 owner 身上，**其它**测试随时可能被它影响
  （owner 多了 `identity.plan.*` / `identity.subscription.*` 权限码）。
- 两者都做之后：**污染源被切断**（A）＋ **消费者不再依赖偶然顺序**（B）。

### 新增护栏（不是修复本身，是防复发）

`SaasPlanIntegrationTest` 加 `@AfterEach sharedOwnerKeepsExactlyOneRole()`：
每个用例跑完之后断言共享 owner 的 `grants` 仍然**只有 1 个**且 `roleCode == "OWNER"`，
并用一条直连库的 SQL 核对 `role_assignment` 里该成员**仍然只有 1 行**。

- 放在 `@AfterEach` 而不是某个用例里：污染一旦发生**当场红**，而不是等别的测试类炸出来。
- **已做反向验证**：把 `platformToken()` 临时改回污染形态后单跑该测试类，
  12 例全部以「共享 owner 的角色集合不得被本测试类扩大」（Expected size: 1 but was: 2）失败，
  实际抓到的 grants 正是 `[OWNER, PLATFORM_OPS]` —— 证明这条断言不是空转的。验证完已还原并复跑全量。

### 明确不做的事

- **没有**改 `session_access`（V47）去加 `ORDER BY`，**没有**新增迁移 `CREATE OR REPLACE FUNCTION session_access`。
  那是对全仓安全相关函数的改动，爆炸半径覆盖所有 identity 消费者，风险远大于本缺陷本身。
- 没有删断言、没有加 `@Disabled`、没有放宽期望状态码。
- 没有削弱任何租户隔离断言（多租户三断言原样保留）。
- 没有改 `docs/product/capability-blueprint/module-progress.md`（本车道是纯测试修复，不产生新交付）。

## 涉及文件

- `backend/services/identity/src/test/java/com/starcon/identity/SaasPlanIntegrationTest.java`（修复 A + 护栏）
- `backend/services/identity/src/test/java/com/starcon/identity/IdentityIntegrationTest.java`（修复 B）
- `docs/sessions/2026-10/2026-10-23-saas-plan-test-isolation-fix.md`（本文件）

**零生产代码、零迁移、零契约、零前端。**

## 验证结果

- L0 编译闸：`backend/ && ./mvnw -q compile` 通过；`pnpm typecheck` 3 个任务成功；
  `pnpm lint` 3 个任务成功（只有既有 warning，无 error）。
- L1 纪律：**跑整个 identity 模块的测试，且连跑两次**。
  理由写在这里：本缺陷是**同一 maven 运行内、跨测试类的共享状态污染** ——
  只跑本模块测试类时根本复现不出来（既有取证：单跑 `IdentityIntegrationTest` 是绿的，
  单跑 `SaasPlanIntegrationTest,IdentityIntegrationTest` 也是绿的，只有全套一起跑才红）。
  所以「收窄到本模块」在这里等于「把 bug 藏起来」。
- L1 第 1 次：`Tests run: 301, Failures: 0, Errors: 0, Skipped: 0` —— BUILD SUCCESS
- L1 第 2 次：`Tests run: 301, Failures: 0, Errors: 0, Skipped: 0` —— BUILD SUCCESS
- L1 第 3 次（反向验证还原后的复跑）：`Tests run: 301, Failures: 0, Errors: 0, Skipped: 0` —— BUILD SUCCESS
- 测试数与基线一致（301），未新增或删除任何用例：
  `SaasPlanIntegrationTest` 仍 12 例，`IdentityIntegrationTest` 仍 17 例。
- 「共享 owner 角色数仍为 1」的证据：见新增的 `@AfterEach` 护栏
  （API 层 `grants` + 数据库层 `role_assignment` 双向核对），
  并已用反向验证证明该护栏能真的抓到污染。

### 顺手看到的同类隐患（**不属于本车道职责，未动手，交调度者定夺**）

1. **`session_access` 的 `grants` 是无序聚合**（V47:167-181）。
   本车道按授权**没有**改它，但全仓别处仍有按位置取 grants 的风险。
   是否单开一个「给 `session_access` 加 `ORDER BY`」的迁移，需要单独评估爆炸半径（覆盖所有 identity 消费者）。
2. **`AdministrationController` 的 owner-transfer 里 `select id from role where code='OWNER'` 没有带租户条件**
   （`backend/services/identity/src/main/java/com/starcon/identity/AdministrationController.java:801`）。
   目前靠 RLS 兜住（`call(...)` 已设租户上下文），但这是**隐式**依赖，不是显式过滤，建议后续补 `tenant_id` 条件。
3. **`AdministrationController.deleteRole` 不删 `saas_platform_role` 登记**。
   删角色时只删 `role_assignment` / `role_permission` / `role`，V323 建的登记表里会留下指向已删 roleId 的孤儿登记。
   目前不影响闸门（登记必须配 `role_assignment` 才算数），但「已删角色」与「已登记角色」两套事实会不一致。

## 后续待办

- 调度者把本车道分支并入集成头（**本车道未自行 merge、未 checkout 集成分支、未 push**）。
- 调度者在集成头上按批次跑一次 `./mvnw verify`。
- 待决：`session_access` 是否加 `ORDER BY`（需单独评估爆炸半径，不在本车道范围）。
