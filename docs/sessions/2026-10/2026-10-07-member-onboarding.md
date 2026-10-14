# 17.2.2.2 成员开通与入职

- **日期**：2026-10-07
- **车道**：`feat/17-2-2-2`（worktree `.worktrees/17.2.2.2`）
- **阶段**：D1–D2，L3 锚点 17.2.2

## 决策摘要

**这个模块与 17.2.2.1 成员管理怎么分工。** 17.2.2.1 已经有 `/identity/members` 的完整 CRUD，
包括建成员时「填密码 = 新建账号、留空 = 关联已有账号」。如果 17.2.2.2 再做一遍同样的事，
它就只是 17.2.2.1 的别名。所以分工划在两个维度上：

- 17.2.2.1 管「**已经在册的成员**怎么改」——改部门、改角色、停用、移除；
- 17.2.2.2 管「**一个人怎么进来**」——建立账号、建立成员关系、设定初始部门与角色。

落地上体现为一张独立的 `member_onboarding` 表 + 一组独立的页面权限
（`identity.onboarding.*`，不与 `identity.member.*` 共用），而不是给成员管理换个路由。

**凭据纪律是本模块的真正差异点，也是设计重心。** 蓝图要求「不在页面或日志返回完整初始凭据」。
最直接的实现是让管理员自己填密码——但那样 17.2.2.1 已经做完了。真正有区别的做法是：

- 初始凭据**由服务端生成**，管理员没有选择余地（也就没有弱密码这条路）；
- 明文**只在创建那一次响应里**返回，之后服务端再也没有任何地方能取到它；
- 入职表上**只有 `credential_issued` 布尔位与时刻，没有任何凭据列，也没有哈希列**——
  哈希只有 `app_user.password_hash` 一份，再存一份只是多一个泄露点。

这条约束由三个层次共同保证：DTO 形状（列表里根本没有凭据字段）、审计快照（不构造 credential 键）、
数据库表结构（没有列可查）。测试用 `information_schema.columns` 把最后一条钉死。

**LINKED 时返回 null 而不是编一个凭据。** 关联已有账号时服务端根本不知道对方的密码，
返回一个假的比不返回更糟——管理员会当真并转交一个登不上去的密码。

**页面权限独立于成员管理。** 「改在册的人」和「放人进来」风险面不同：
前者改的是已有身份，后者涉及账号创建与初始凭据。绑在同一组权限码上，
会让「能改部门的人也能发账号」。

## 关键 trade-off

**1. 服务端生成凭据 ⇒ LINKED 时白算一次 bcrypt。**
先查账号存不存在再决定要不要生成，听起来更省。但运行时角色 `identity_app` 对 `app_user`
**根本没有 GRANT**（这正是「全局用户表不可枚举」的实现方式），Java 侧查不了；
生成与否只能由数据库函数判定，而函数要拿到哈希才知道该不该建号。
开通是低频管理操作，这个代价可以接受，换来的是「调用方不能选择初始密码」这条更强的约束。

**2. 与 `member_user` 刻意不同：账号已存在时不再报「不能重置已有账号」。**
17.2.2.1 那条拒绝规则成立的前提是**密码由管理员填**——继续走下去等于允许借「建成员」
改掉一个已有账号的登录凭据。本模块的哈希一律服务端生成、调用方不能选也不能看到，
关联分支直接丢弃即可，没有可被劫持的输入。

**3. 业务规则校验同时放 Java 与数据库。**
Java 侧校验是为了让「选了停用角色」返回 400 而不是笼统的 409（前者告诉用户哪里填错了，
后者会让人以为是并发冲突去刷新页面）；数据库侧保留同样的判断，因为 `onboard_member`
可能被别的服务直接调用、绕过这一层。与 V42 的处理一致。

**4. 蓝图排除的邮件邀请 / 接受令牌 / 自助激活没有做。**
这是蓝图**点名**排除的，划给后续独立 PRD。落地时是「表里一个邀请态列都没有」——
没有 invitation_token、没有 expires_at、没有 consumed_at。
将来补齐时必须连同令牌摘要存储、有效期、单次使用、防枚举与撤销规则一起加，
只加一列 token 就当邀请功能做完是不行的。

**5. 没有做「首次登录强制改密」。**
凭据是服务端生成的强随机串（20 位、已剔除 I/l/1/O/0 等易混字符），
且只出现一次；强制首次改密属于认证/凭据生命周期那条线，和被排除的自助激活是同一片区域，
在本模块里加会越界。这一条记在后续待办里。

## 实现中踩到的坑

**`RETURNS TABLE` 的 OUT 参数与列同名会报 ambiguous。**
`onboard_member` 的 OUT 参数里有一个叫 `user_id`，与 `membership.user_id` 同名，
`EXISTS(SELECT 1 FROM membership WHERE tenant_id=tid AND user_id=uid)` 直接报
`column reference "user_id" is ambiguous`。加表别名（`FROM membership m WHERE m.tenant_id=...`）解决。
`onboardings` 是 `LANGUAGE sql`，同样的坑以「query 结构与函数返回类型不匹配」的形式等着，
所以那里所有列都带表别名。

**成员关系数 >1 时登录不返回 token。**
`IdentityController.login` 在用户属于多个租户且没指定 membershipId 时返回 `token: null`
（要用户自己选一个进入）。测试里「关联后原凭据仍有效」这条断言最初按单租户写，
结果拿到 `Bearer null` 报 401。改成直接断言 `token` 为 null 且 memberships 有两项——
这反而是「成员关系是两个租户各自的」更直接的证据。

**共享容器下不能断言「本租户正好一条」。**
Testcontainers 容器由整个测试类共享，基础租户里还有其它用例建的入职记录。
租户隔离断言改成「A 看到 aId 且**看不到** bId」，而不是 `containsExactly(aId)`。

## 文件清单

新增：

- `backend/services/identity/src/main/resources/db/migration/V45__member_onboarding.sql`
- `backend/services/identity/src/main/java/com/starcon/identity/MemberOnboardingController.java`
- `backend/services/identity/src/test/java/com/starcon/identity/MemberOnboardingIntegrationTest.java`
- `apps/web/src/components/iam/onboarding-model.ts`
- `apps/web/src/components/iam/onboarding-model.test.ts`
- `apps/web/src/components/iam/member-onboarding-workspace.tsx`
- `apps/web/src/app/org/onboarding/page.tsx`

改动：

- `apps/web/src/lib/api-proxy.ts`（白名单加 `onboardings` 与 `onboardings/{id}/complete`）
- `apps/web/src/components/iam/identity-workspace.tsx`（新增 onboarding 工作区入口与导航项）
- `packages/api-contracts/src/*`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（17.2.2.2 一行 + sync.mjs 重算的计数）

## 验证

- `cd backend && ./mvnw verify`：1366 tests，0 failures（基线 1358，+8 恰为本模块用例）
- `pnpm typecheck` / `pnpm lint`：通过（lint 仅剩 catalog 模块既有 warning）
- `pnpm test`：49 files / 850 tests 通过（基线 48 / 840）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`：通过，`/org/onboarding` 已生成
- `node tools/module-progress/sync.mjs --check`：台账与蓝图一致

## 后续待办

1. **首次登录强制改密**不在本模块。凭据是服务端生成的强随机串且只出现一次，
   但「本人首次登录后必须换掉管理员发放的初始密码」属于凭据生命周期那条线，
   建议与被排除的自助激活一起做，届时可能需要在 `app_user` 上加一个标记位。
2. **邮件邀请 / 接受令牌 / 自助激活**是蓝图点名的后续独立 PRD。
   落地时必须补齐：令牌摘要存储（不存明文）、有效期、单次使用、防枚举（枚举与不存在同答案）、
   撤销规则，以及邀请态与本模块 `member_onboarding` 的关系。
3. **批量开通**当前是逐个提交。要做批量时注意：每个账号的初始凭据各自只出现一次，
   批量响应需要重新设计承载方式（一次性导出一次性令牌或逐条返回），
   不能沿用逐条弹窗的形状。
