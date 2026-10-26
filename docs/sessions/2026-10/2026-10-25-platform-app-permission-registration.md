# 2026-10-25: 18.1.1.1 platform.app 权限码登记（identity 侧补缺）

## 摘要

给已落地的 18.1.1.1「应用与凭证」补上它在 **identity 服务**里缺失的**权限码登记**。

上一任车道（commit `0e6474fe`）在 `platform` 服务落地了 23 条端点，端点用
`access.require(bearer, "platform.app." + permission)` 强制鉴权，实际只用到 4 个动作：
`read / create / update / revoke`。但 **identity 的 `permission` 表里从来没有注册过
`platform.app` 任何一行** —— 而 `role_permission.permission_code` 有指向 `permission(code)`
的外键 ⇒ **「没有任何角色可能持有 `platform.app.read`」** ⇒ 这 23 条端点在**真实环境里恒 403**。

上一任的集成测试用的是**假 identity 服务**（直接返回权限码），所以测试全绿也没暴露这个缺口；
上一任**刻意没有自己补**，理由是「Flyway 版本号全局唯一，跨车道乱加会撞号」—— 顾虑成立，
本次由调度者**预分配 V378**。

本车道只做一件事：**一个 identity 迁移 + 一个测试类 + 台账那一行 + 本会话日志**。
未改 platform 服务、未改 23 条端点、未改契约、未改前端。

## 决策 / 结论

1. **🔴 登记 4 个码，`page_code` 取 `platform.app`。**
   `platform.app.read / .create / .update / .revoke` —— 与 `AppCredentialController` 里
   `call(auth, "<动作>", ...)` 的实际调用点一一对应（逐个端点核对过，只有这 4 个动作）。
   `page_code = 'platform.app'` 的理由：`permission.page_code` 有外键指向 `page_resource(code)`，
   而 `page_resource` 对应**菜单入口**不是一个动作 —— 「应用与凭证」是 18.1 的**同一个页面**
   （应用列表与凭证详情同一处管理），拆成 4 个 `page_resource` 会在菜单里多出 3 个重复条目。
   前缀取 `platform.` 而不是 `identity.`：权限码跟着**消费它的服务**走（`platform.app.read`
   由 platform 服务消费），与 `catalog.spu` / `warehouse.facilities` 的「服务名.页面」口径一致。

2. **🔴 吊销是独立的一个码，不并进 `update`。**
   能改应用档案的人不该顺手把别人的凭证掐了 —— 这与端点侧的既有判断一致
   （`AppCredentialController` 文件头写明「吊销是独立的一个码」）。

3. **🔴 OWNER 补权用 `LIKE 'platform.app.%'` 扫整个集合，而不是只补 `read` 一条。**
   这是本任务最容易漏的一步：`new` 租户的 OWNER 由 `create_child`（V47）把 `permission` 表里
   的**全部**权限码授过去，所以**新租户天生就有这 4 个码**；但**存量租户**的 OWNER 角色是在
   这 4 个码登记**之前**建的，不会自动获得它们 ⇒ 只补 `read` 的话，`create / update / revoke`
   对既有租户的 OWNER 依然不可见，迁移上线后这些租户会突然「什么都点不动」。
   与 V3 / V8 / V104 / V330 同口径（V104 的文件头把这个坑写得很清楚）。

4. **刻意只登记这 4 个动作**：没有端点的权限码不注册（与 identity V104 / V53 / V330 同一纪律）。

5. **三段式全部带 `ON CONFLICT DO NOTHING`**，迁移幂等；`page_resource` 的 `name` 取
   「应用与凭证」，与 `identity.platform_flag`「平台参数与开关」、`warehouse.facilities`
   「仓库档案」同一命名口径。

6. **不用 identity 现有的 `GRANT` 再补一刀**：`identity_app` 对 `permission` / `page_resource`
   已有 `SELECT`、对 `role` / `role_permission` 已有全量 DML（V3:49-50），V378 不需要新的授权。

## 关键 trade-off / 讨论

- **Flyway 版本号由调度者预分配（V378），不在本车道自己找最大值。**
  identity 目录当前最大版本是 V373；V376 = reporting 车道、V377 = platform 车道自己的表结构
  迁移、V378 = 本车道。跨车道乱加版本号会让另一条车道的 Flyway 直接炸掉 —— 上一任正是因此
  刻意不自己补。
- **测试读的是 classpath 上的迁移文件本体**（`/db/migration/V378__platform_app_permission.sql`），
  **不在测试里复刻一份 SQL** —— 复刻的那份与真正上线的那份迟早会漂移，漂移了就测不到真东西。
  断言「V378 应当是三段式」把迁移的形状也钉住了。
- **重跑迁移用迁移身份（容器超级用户），与应用身份不同。**
  迁移脚本要在**没有租户上下文**的情况下给所有租户的存量 OWNER 补权；用应用身份跑会撞 RLS、
  只能覆盖一个租户 —— 那是另一件事，也不是上线时的行为。
- **「存量 OWNER」如何在测试里造出来**：Flyway 已经跑完、码已经登记，没法再造一个「先于码存在」
  的角色。办法是**先删掉那 4 行**（等价于「这个角色比权限码更老」），再跑补权语句断言它拿回来了。
  断言分两步：先断言删完确实是空的（前置条件），再断言补权后 4 个码**全都在**。
- **补权断言做过反向验证（变异测试）**：把迁移里的 `LIKE 'platform.app.%'` 临时改成
  `= 'platform.app.read'`，核心用例**确实红**在「存量 OWNER 必须拿到全部 4 个码，而不只是 read」
  这一条上 ⇒ 该断言不是空跑。随后已还原并复跑全绿。
- **租户隔离三断言打在 `role` / `role_permission` 上**：这两张表由 V1 建了
  `ENABLE + FORCE RLS isolation`（policy 只认 `app.current_tenant`），所以「子租户直查父租户的
  角色 id 读到零行」是**库级**结论，不是应用层过滤写对了而已。另断言两张表
  `relforcerowsecurity = true` 且应用连接无 `bypassrls`。
- **台账只改 18.1.1.1 那一行**：把该行「如实标注缺口」的 ①（权限码未登记）改成「已登记」
  并指到 V378 —— 留着原文会与事实不符。整表未重排（diff = 1 行增 1 行删）。

## 涉及文件

**迁移**
- `backend/services/identity/src/main/resources/db/migration/V378__platform_app_permission.sql`（三段式，84 行）

**测试**
- `backend/services/identity/src/test/java/com/starcon/identity/PlatformAppPermissionIntegrationTest.java`（8 例）

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（仅 18.1.1.1 一行）
- `docs/sessions/2026-10/2026-10-25-platform-app-permission-registration.md`（本文件）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | **exit 0** |
| L1 模块闸 | `cd backend && ./mvnw -pl services/identity -am test -Dtest='PlatformAppPermission*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **8 例全绿**，0 失败 0 错误 0 跳过；实跑 Testcontainers PostgreSQL 16 |
| 变异（反向） | 把补权语句临时改成 `= 'platform.app.read'` 后复跑 | **如期红**在核心断言上 ⇒ 断言非空跑；已还原并复跑全绿 |

8 例清单：

| 用例 | 断言要点 |
| --- | --- |
| `四个权限码已登记且归属platform_app页面` | 4 行齐、`page_code`/`action` 正确、码 = `page_code.action`、`page_resource` 有该行 |
| `权限码目录接口能查到这四个码` | `GET /identity/permissions` 里看得到这 4 个码（授权界面看不到的码等于没登记） |
| `存量租户的OWNER角色补权后拿到全部四个码` | **核心**：删掉 4 行 → 断言空 → 跑迁移补权 → 断言 4 个码**全在** |
| `迁移重复执行两次不报错也不产生重复行` | 迁移共跑三遍，行数不变、无重复 `(租户, 角色, 码)` |
| `两租户的OWNER角色与授权行互不可见` | 双向直查对方角色 id / 授权行 → 零行；HTTP 角色列表亦然 |
| `伪造租户头无效` | 伪造随机租户 id 与子租户真实 id，可见范围均不变 |
| `连接池归还后多轮交替读写不串租户` | 池压到 1，5 轮交替读写不串 |
| `角色与授权表在两租户之间都查不到对方的行且均为强制RLS` | 事务外直查为空、`relforcerowsecurity` 为真、应用连接无 `bypassrls` |

## 后续待办

1. **并入集成头由调度者做**（本车道未 merge、未 push、未 checkout 集成分支）。
2. **`pnpm typecheck` 仍可能红**（`packages/api-contracts/src/contracts/reporting.ts` TS2306）——
   属 **13.1.1.1 reporting 车道**，与本车道无关，合并 reporting 后自然消失。
3. **下游未交付**（18.1.1.1 自己在 capabilities 里如实标 `available=false`）：18.1.1.2 接口目录、
   18.1.1.3 调用监控（本仓无 API 网关 ⇒ 用量采集器未交付，窗口用量只能手工回报）。
4. **无需其它补登记**：23 条端点用到的动作已逐个核对，只有这 4 个；platform 侧后续新增动作时，
   需要在 identity 侧同步补码（同一个 `platform.app.%` 集合，补权语句覆盖整个集合，新码自动带上）。

## 关联

- 蓝图：`docs/product/capability-blueprint/18-integration-and-automation.md` §18.1.1.1
- 台账：`docs/product/capability-blueprint/module-progress.md`（18.1.1.1 行）
- ADR：0002 多租户策略、0005 租户层级与页面 RBAC（page_resource ↔ 权限码模型）
- 先例迁移：`V324__platform_feature_flags.sql`（三段式）、`V104__warehouse_config_permission.sql`（存量 OWNER 补权）
- 前置会话：`docs/sessions/2026-10/2026-10-25-18-1-1-1-app-credential.md`（本车道上一任）
