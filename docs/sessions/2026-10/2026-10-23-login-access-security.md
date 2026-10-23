# 16.5.2.1 登录与访问安全（阶段 D7 · L3 锚点 16.5.2）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/16-5-2-1`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/16.5.2.1`）
- **基线**：`integration/module-dev` @ `e1b808e3`
- **迁移版本号**：`V319__channel_access_security.sql`（预分配；同批另外三条车道占用 V317 / V318 / V320）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/16-compliance-and-risk.md` 第 217-222 行）：

> ### 16.5.2.1 登录与访问安全
>
> - **职责**：管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、会话管理；平台授权凭据的访问审计。
> - **输入**：认证配置；登录事件；访问审计事件。
> - **输出**：安全配置 + 审计记录 + 告警。
> - **关系**：上游 = 17 identity；下游 = 2 凭据引用。**平台凭据是最值钱的资产**，访问必须有审计且最小权限。

本车道的落地要点：

1. **六个能力面逐一对应九张表，不合并、不省略。**
   `access_security_policy`（MFA 段 + 会话段 + IP 执行口径 + 异常阈值，一租户一条）·
   `access_security_policy_audit`（配置变更留痕）·
   `access_security_mfa_registration`（因子登记 / 绑定状态）·
   `access_security_mfa_challenge`（挑战下发与验证尝试）·
   `access_security_login_audit`（登录审计）·
   `access_security_alert` + `access_security_alert_action`（告警 + 处置留痕）·
   `access_security_ip_allowlist`（IP 白名单）·
   `access_security_credential_access`（平台授权凭据访问审计）。
2. **🔴 上游取证结论（本仓实查，不是推测）—— 任务书里的括号需要修正：**
   登录事件的真源头是 identity 的 **`operation_log`**（`event_type` ∈ LOGIN / LOGOUT，
   由 `OperationLog.login` / `OperationLog.logout` 写），**不是 `identity_audit`** ——
   后者由 `AuditLog.record` 记的是业务变更（成员 / 角色 / 字典…）。
   读面是 identity 的 `GET /identity/operation-log`（要 `identity.operation_log.read`）。
3. **🔴 identity 的登录事件不含来源 IP。**
   `IdentityController.login` 调 `operationLog.login(tenantId, requestId(), username, true, null, null)`
   —— `remoteHint` **恒为 null**。
   ⇒ 「按来源 IP 判定异常登录」在本仓**量不到**。本模块**不编造 IP**：
   表里保留 `source_ip` / `source_ip_available` 两列并如实标 `available=false`，
   能力声明写明归属模块（17 identity）与影响面。
   异常判定改用**实算**的「同账号在窗口内的失败次数 / 成功次数」—— 那是从真实上游事件算出来的。
4. **🔴 会话实体在 identity，本模块不造第二套会话系统。**
   identity 的 `auth_session` 只有 `token_hash / user_id / membership_id / expires_at` 四个列，
   且 **`expires_at` 没有任何读端点**、并发会话数也没有统计。
   ⇒ 会话到期 / 空闲 / 并发三个维度**读不到**，如实标 `available=false`；
   本模块管的是**本服务侧会话策略**（空闲 / 绝对 / 并发 / 换绑即撤销）+ 变更审计。
5. **🔴 三条硬纪律：**
   - **读不到 ≠ 没有**：上游不可达一律 **503**（绝不用空集合冒充「没有异常登录」）；
     上游正常但查无记录返回 `known=false` + 空集合。
   - **IP 白名单走 PostgreSQL `inet` 的 `<<=` 真网段包含运算**（不是字符串相等）；
     非法 CIDR 明确 400；**白名单为空 ≠ 放行所有** —— ENFORCE 口径下切不进去空白名单、
     也删不掉最后一条（守卫触发器在**数据层**钉死 fail-closed）。
   - **五张审计凭据表 append-only**：`BEFORE UPDATE OR DELETE` 直接 RAISE；
     `access_security_credential_access` 的 **GRANT 只给 SELECT,INSERT**（刻意不给 UPDATE/DELETE）。
6. **🔴 凭据访问审计「最小权限」是结构性的，不是靠自觉。**
   2.2.1.3 的 `credential_ref` 本身只存引用、不存明文令牌 ⇒ 本模块的审计表**没有令牌列**，
   受控访问只返回元信息（提供方 / 授权范围 / 状态 / 到期时刻）。

## 关键 trade-off

| 决策点                                                                              | 取舍                                                                               | 理由                                                                                                                                                                                                |
| ----------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 登录事件读面：`identity_audit` vs **`operation_log`**                               | **`operation_log`**                                                                | 本仓实查：`AuditLog.record` 只记业务变更；登录 / 登出由 `OperationLog.login` 写进 `operation_log`。任务书里的括号写成了 `identity_audit / auth_session`，实查后按 `operation_log` 落地              |
| IP 白名单列类型：`cidr` vs **`inet`**                                               | **`inet`**                                                                         | `cidr` 类型**拒绝带主机位的写法**（`192.168.1.5/24::cidr` 直接报错），而用户填网段时十有八九带主机位。`inet` 两种都收下且 `<<=` 语义一致                                                            |
| 同一网段唯一性：`CREATE UNIQUE INDEX ... NULLS NOT DISTINCT` vs **表约束**          | **表约束 `UNIQUE NULLS NOT DISTINCT`**                                             | 🔴 `CREATE INDEX` 的索引元素**只认** `NULLS FIRST\|LAST` —— 写成索引会直接语法错误（本次迁移首次跑就踩到了，Flyway 42601）。`NULLS NOT DISTINCT` 是 PG15+ 的**表约束**语法                          |
| 异常检测阈值：给默认值 vs **必须租户显式选**                                        | **必须显式选**                                                                     | 「系统给个默认阈值」等于「系统替租户决定了什么算异常」。没登记策略时扫描直接 422 拒绝                                                                                                               |
| 白名单为空 + ENFORCE：**放行所有** vs **拒绝所有**                                  | **拒绝所有（fail-closed）**                                                        | 蓝图口径「白名单为空 ≠ 放行所有」。守卫触发器覆盖三个入口（切口径 / 停用 / 删除），「先清空再切 ENFORCE」这条路也走不通                                                                             |
| 告警去重：每次扫描新开一条 vs **同一 (类型,主体) 只留一条未关闭**                   | **只留一条未关闭**（部分唯一索引）                                                 | 否则每扫一次就多一条一模一样的告警，运营的注意力会被淹掉。命中已有告警时**更新**它（事件数 / 窗口 / 严重度只升不降）                                                                                |
| 告警终态：可回退 vs **终态**                                                        | **已忽略 / 已处置是终态**                                                          | 事后要重新处置就**再开一条告警**，不改旧的那条 —— 处置留痕是取证材料，不能被改写                                                                                                                    |
| 凭据不存在时：抛 404 vs **先留痕再由控制器抛 404**                                  | **服务层不抛，控制器在事务提交后抛**                                               | 🔴 在事务里抛 404 会把刚写下的那条审计**一起回滚掉**。留痕必须活得比请求长                                                                                                                          |
| MFA 挑战：UPDATE 状态 vs **append-only 新行**                                       | **每次尝试新起一行**（`attempt_no` 串联）                                          | 验证尝试是**发生过的动作**，事后改写它等于伪造「验证了几次」                                                                                                                                        |
| MFA 挑战实现：**写打真实域名的假 HTTP 客户端** vs **只登记记录**                    | **只登记记录**                                                                     | 本仓**没有**真实 MFA 服务商凭据。假客户端是最典型的假实现；能力声明标 `available=false` + `ownerModule=17`                                                                                          |
| 会话管理：另起一套会话表 vs **只管策略 + 审计**                                     | **只管策略 + 审计**                                                                | 会话实体在 identity 的 `auth_session`。另起一套就是第二个登录系统                                                                                                                                   |
| `credential_ref` 的读权限码：`identity.audit.read` vs **`channel.connection.read`** | **`channel.connection.read`**                                                      | 🔴 `credential_ref` 上有一条 **RESTRICTIVE** 策略（channel V1 的 `credential_scope`），要求 `app.permission` 本身就是 `channel.connection.*` 权限 —— 用别的权限码读它会**静默读到零行**（不是报错） |
| 权限码：新增 vs **复用**                                                            | **复用** `identity.audit.read` / `channel.operation.*` / `channel.connection.read` | 新增权限码要动 identity 的权限真相源，是别的车道的事                                                                                                                                                |
| 登录审计要不要在本地留一份                                                          | **留**                                                                             | identity 的 `operation_log` 只有 **90 天留存期**（V49 的 `expires_at` + purge 端点），而登录审计是**合规证据**，不能跟着排障日志一起过期                                                            |

## 文件清单

**后端（channel 服务，包 `com.starcon.channel`，扁平包）**

- `backend/services/channel/src/main/resources/db/migration/V319__channel_access_security.sql`（9 张表 + RLS + GRANT + 守卫触发器）
- `backend/services/channel/src/main/java/com/starcon/channel/AccessSecurityModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AccessSecurityRules.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AccessSecuritySourcePort.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AccessSecurityService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AccessSecurityController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AccessSecurityIdentityStub.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AccessSecurityIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AccessSecurityTenancyIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AccessSecurityRulesTest.java`

**契约（codegen 产物，只读）**

- `packages/api-contracts/src/channel.openapi.json`
- `packages/api-contracts/src/contracts/channel.ts`

**前端**

- `apps/web/src/app/compliance/access-security/page.tsx`
- `apps/web/src/components/access-security/access-security-model.ts`
- `apps/web/src/components/access-security/access-security-workspace.tsx`
- `apps/web/src/components/access-security/access-security-model.test.ts`
- `apps/web/src/app/api/channel/access-security-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**纯新增一条独立的 `services[]` 元素**，既有条目一字未动）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（只改 `| 16.5.2.1 |` 那一行的「状态」「证据」两格）

## 影响面

- **零 ALTER 任何既有表、零跨服务外键、零既有表被改。**
- **identity 服务零改动**（跨车道回归面 = 0）：只经 `GET /identity/operation-log` 与 `GET /identity/auth/session`
  两个授权只读 API 读。
- **2.2.1.3 / 2.2.1.2 零改动**：不改 `credential_ref` 的表、端点与签名，只在它旁边加一层访问审计。
- `apps/web/src/lib/api-proxy.ts` 是**多车道共用**的累积型白名单 ⇒ 本车道只**新增一条独立元素**，
  插入锚点固定在 `ad-groups` 之后 / `11.4.1.1 平台纠纷列表` 之前（与同批其它三条车道的锚点不重叠）。

## 验证

- L0 编译闸：`./mvnw -q compile`（backend）与 `pnpm typecheck`（前端）全绿。
- L1 模块闸：`./mvnw -pl services/channel -am test -Dtest='AccessSecurity*'` →
  **33 例全绿**（集成 23 / 多租户三断言 3 / 纯函数 7）。
- 多租户三断言实跑 Testcontainers PostgreSQL 16，直查用**应用账号 `channel_app`**（超级用户绕过 RLS 会让断言失效）。
- 凭据访问审计的 append-only：**应用账号**（无 UPDATE/DELETE 授权）与**迁移超级账号**（守卫触发器 23514）**各试一次**。
- 前端：`pnpm vitest run apps/web/src/app/api/channel/access-security-allowlist.test.ts apps/web/src/components/access-security` → **17 例全绿**。
- api-proxy 累积正则全量回归：`pnpm vitest run apps/web/src/app/api/trade apps/web/src/app/api/channel` →
  **139 文件 / 732 例全绿**（零回归）。
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` → 只改 channel 两个文件。

## 后续待办

1. **🔴 2.2.1.2 授权向导与 2.2.1.3 换令牌这两条写入路径还没接到本审计表上。**
   它们在 `com.starcon.channel.oauth` / `secrets` 里，属别的模块的文件，本车道**不改别人已交付的文件**
   ⇒ 审计流水里现有的是**本模块受控访问**留下的那部分。
   能力声明与台账都已如实标注，接线需要 2.x 车道主动引入本模块的审计端口。
2. **identity 侧若补上登录来源 IP**（`OperationLog.login` 的 `remoteHint`），本模块的 IP 维度会自动生效
   —— 读面已经留好（`detail.remote`），不需要改本模块。
3. **identity 侧若补上 `auth_session.expires_at` 的读端点与会话并发统计**，本模块的会话维度可从
   `available=false` 转为可算 —— 表结构与能力声明都已预留。
4. 16.5.2.2 关联风险**未交付**：本模块**不编一张「关联账号」表**，api-proxy 也刻意不放行该形状。
