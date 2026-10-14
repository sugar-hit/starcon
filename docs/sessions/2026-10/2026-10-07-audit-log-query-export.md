# 17.5.3.1 审计日志收口（D1–D2）

## 做了什么

把 17.5.3.1「审计日志」从台账里的「进行中」收口为「已交付」。开工前 `identity_audit` 只有
V3 建的六个骨架列（`id/tenant_id/actor_id/resource_id/action/occurred_at`）、只 `GRANT INSERT`，
15 个写入点全是裸动作名（`audit(a, id, "RoleSaved")`）：记录写得进去，但运营在 API 层查不到任何一条。

补齐的七项（蓝图职责逐条对应）：

| 蓝图要求               | 落地                                                                  |
| ---------------------- | --------------------------------------------------------------------- |
| 不可篡改               | `identity_audit_immutable()` 触发器 + `REVOKE UPDATE,DELETE`，双层    |
| 操作人 / 时间          | `actor_name` 快照 + `occurred_at` 显式绑定 UTC                        |
| 动作                   | 已有 15 个动作名，本轮补上 before/after 与资源类型                    |
| 资源                   | `resource_type` + `resource_label`（写入时快照，改名/删除后仍可渲染） |
| 授权上下文             | `authorization_context` jsonb：角色编码 + 数据范围 + 权限集           |
| 脱敏的变更前后值       | `changes` jsonb，写入侧经 `AuditRedactor` 处理                        |
| 按资源/操作人/时间检索 | 三组索引 + `GET /identity/audit`                                      |
| 导出                   | `GET /identity/audit/export`，与检索共用同一套条件拼装                |
| 变更详情               | `GET /identity/audit/{id}`                                            |

## 关键 trade-off

### 1. 跨服务审计表不聚合

蓝图写「上游 = 全部业务系统」，而 `catalog` 有自己的 `audit_event`（`CatalogService.java:519`）、
`channel` 也有各自的写入。**结论是不聚合**：AGENTS.md 明令禁止跨服务数据库访问、跨库 FK 与共享视图。
所以 `identity_audit` 是 identity **自己拥有的**审计事实，只覆盖本服务的 15 类关键操作。

代价是「全平台统一审计视图」现在**不存在**。这不是遗漏，是边界：统一视图属于事件总线/合规系统的职责，
应由各服务把 `audit_event` 发到总线再由下游聚合，而不是让 identity 去读别人的表。若后续要做，
走事件而非跨库查询。

### 2. 脱敏放在写入侧，不在读取侧

`AuditRedactor` 在 `AuditLog.record()` 里执行，落库的就已经是脱敏后的值。只在读取时过滤等于把明文留在库里，
换一个读取方（运维导出、BI、误配置的 SQL 台）就漏一次。测试因此直接连库 grep 明文，而不是只断言 API 返回值。

规则分两类，因为二者的风险不同：

- **凭证类**（password / token / secret / api key / hash…）→ 整值替换 `***`。连长度信息都不留，
  因为 `$2a$10$` 前缀本身就泄露 bcrypt 强度。
- **身份联系类**（证件号 / 银行卡 / 手机 / 邮箱）→ 保留头尾打码。审计要能回答「这个证件号改成了另一个」，
  只知道「某字段变了」不够用。
- **完整地址** → 整值替换。蓝图的明文禁区，保留任何片段都可能拼回原文。
- 另有**按值判定**兜底：`remark` 里写「联系我 13800138000」这类无害字段名藏敏感值的情况挡得住。

### 3. timestamptz 绑定的正确姿势（与常见建议不同）

任务书给的 `Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC))` **实测是错的**：
在本机 `Asia/Shanghai` 下 drift = −28800 秒。因为 `Timestamp.valueOf` 会把这串「UTC 墙钟」按 JVM 默认时区
换算成 epoch，等于又偏回本地时区一次。实测三种写法：

```
valueOf(...UTC)  drift = -28800s
Timestamp.from   drift = 0
OffsetDateTime   drift = 0
```

最终用 `OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)`：类型语义就是「带偏移的绝对时刻」，
PG 驱动按 timestamptz 直接解释，不经过任何本地时区换算。注意 jOOQ 在纯文本语句里无法为 `OffsetDateTime`
推断类型，SQL 侧还要显式 `?::timestamptz`，否则报类型错误。

`occurredAtRoundTripsAsUtcWithoutTimezoneDrift` 断言的是**写回值**（API + 直连数据库双向比对）。
已验证该测试确实能抓住回归：手动注入 −28800s 后测试失败，还原后通过。

### 4. 审计只读，不注册写权限

审计记录由各操作点追加，不存在「编辑审计」这种业务动作，所以只注册 `identity.audit.read` 一项权限，
检索、详情、导出共用。不注册 create/update/delete 是刻意的——避免出现能改审计的角色。

## 过程中踩到的两个非显然问题

**`authorization` 是 PostgreSQL 保留字**，V7 建列时直接语法错误。改为 `authorization_context`。

**基类 `@AfterAll static void stop()` 会被每个子类继承并各自执行一次**：容器由 static 块启动一次被所有子类共享，
先跑完的测试类会把容器停掉，后面的类全部失败，且症状表现为业务断言不符（数据半截、租户深度超限），极难定位。
已改为不由测试类停容器（Ryuk 在 JVM 退出时回收）。抽 `IdentityIntegrationTestBase` 时顺手修掉了这个既有隐患。

**`childSnapshot` 不能直接查 `tenant` 表**：`identity_app` 对它没有 GRANT（V3 只给了 `child_tenants`
这个 SECURITY DEFINER 函数），直查 42501 会连带整个「创建子租户」失败。审计快照不构成绕过授权边界的理由，
改走 `child_tenants(hash)`。这个 bug 一度表现为「既有的 children 测试也挂了」。

## 文件清单

**迁移**

- `backend/services/identity/src/main/resources/db/migration/V7__audit_log.sql`

**后端**

- `backend/services/identity/src/main/java/com/starcon/identity/AuditController.java`（新增：检索 / 详情 / CSV 导出）
- `backend/services/identity/src/main/java/com/starcon/identity/AuditLog.java`（新增：写入侧，口径唯一入口）
- `backend/services/identity/src/main/java/com/starcon/identity/AuditRedactor.java`（新增：脱敏）
- `backend/services/identity/src/main/java/com/starcon/identity/AdministrationController.java`（15 个写入点接上前后值 + 授权上下文 + 资源类型）

**测试**

- `backend/services/identity/src/test/java/com/starcon/identity/AuditIntegrationTest.java`（新增 10 项）
- `backend/services/identity/src/test/java/com/starcon/identity/AuditRedactorTest.java`（新增 7 项）
- `backend/services/identity/src/test/java/com/starcon/identity/IdentityIntegrationTestBase.java`（新增：抽共享底座）
- `backend/services/identity/src/test/java/com/starcon/identity/IdentityIntegrationTest.java`（改为继承底座，断言未改）

**前端**

- `apps/web/src/app/org/audit/page.tsx`（新增）
- `apps/web/src/components/iam/audit-workspace.tsx`（新增：检索 / 详情 / 导出 / 空态错误态加载态）
- `apps/web/src/components/iam/audit-model.ts` + `audit-model.test.ts`（新增：可测逻辑 + 12 项单测）
- `apps/web/src/components/iam/identity-workspace.tsx`（导航加「审计日志」入口）
- `apps/web/src/lib/api-proxy.ts`（allowlist 放行 `audit` / `audit/export` / `audit/{id}`）
- `packages/ui/src/lib/task-pages.ts`（`/org/audit` 标记 implemented）

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/catalog.ts`（+3 个 DTO：AuditView / AuditDetail / AuditPage）
- `packages/api-contracts/src/identity.openapi.json`

**台账**

- `docs/product/capability-blueprint/module-progress.md`（17.5.3.1 → 已交付，`sync.mjs` 同步派生计数）

## 验证

```
cd backend && ./mvnw verify
  identity 35 · catalog 22 · channel 66 = 123 tests, 0 failures, BUILD SUCCESS

pnpm typecheck   3/3 tasks successful
pnpm lint        No ESLint warnings or errors
pnpm test        14 files / 93 tests passed
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build   /org/audit 4.12 kB
node tools/module-progress/sync.mjs --check   ✓ 台账与蓝图一致
```

租户隔离三条断言在 `auditIsTenantIsolatedAndHeaderForgeryIsIgnored` 里实跑真实 PostgreSQL 16：
两租户互不可见（含详情端点跨租户返回 404 而非 403，不泄露存在性）、伪造 `X-Tenant-Id` 无效、
连接池压到 1 后交替租户请求三轮不串租户。

## 后续待办

- **17.5.3.2 操作日志**：同一 L3 下的兄弟模块，未开发。登录流水 / API 调用摘要 / 错误堆栈 / 请求 ID 链路
  归它，不要往审计日志里塞——蓝图明确两者用途不同、不可互相替代。
- **跨服务审计的统一视图**：见上文 trade-off 1，需先定事件总线方案，不在本模块范围。
- **审计留存与归档**：蓝图说审计是合规长期留存、但没给期限。当前无清理机制；
  将来若要归档/清理，需要绕过 `identity_audit_immutable` 触发器，属于独立决策。
