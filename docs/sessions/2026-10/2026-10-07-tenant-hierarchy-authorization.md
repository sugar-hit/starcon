# 17.1.1.3 层级授权（identity）

车道 `17.1.1.3`，分支 `feat/17-1-1-3`，基线 `a42f848`（integration/module-dev）。

## 决策摘要

ADR-0005 的硬约束是「父子关系只表示结构，不自动赋权」。本模块就是这条约束的**实现**：
集团要看子公司数据，必须由父租户所有者显式开一次口；没有记录就是拒绝。
交付：授权配置（增改查 + 撤销）、访问判定（服务端单一入口）、审计与事件外发。

`tenant_hierarchy_grant` 一行 = 一对父子租户之间的一处开口，承载四段职责里蓝图点名的每一段：

- **哪些子租户** —— `child_tenant_id`，且必须是 `tenant_closure` 里 `depth > 0` 的真后代。
- **哪些数据域** —— `domains text[]`，非空。一行只管一个租户的一组域，
  所以可以只开 `catalog.spu` 读、另一行开 `catalog.sku` 写。
- **只读还是可写** —— `access_level` READ / WRITE，降级单向：READ 永远推不出 WRITE。
- **有效期与撤销** —— `valid_from` / `valid_until`（NULL = 长期）+ `status` 终态 REVOKED。

`tenant_id` 记的是**授权方（父）**而不是被访问方。理由是 RLS 锚点必须是当前会话所在租户
能直接命中的那一列；否则会出现「进入子租户反而能给自己发授权」的自证循环。
V3 的 `tenant_closure` 是同一条约定：闭包行归属于祖先。

## 关键 trade-off

- **判定返回 reason 枚举而不是 boolean**。下游要能区分「没有授权记录」与「授权过期了」——
  前者找管理员配置，后者等过期或续期。只给 true/false 会让调用方把两者当同一件事，
  日志里也看不出授权为什么失效。
- **判定按 (目标租户, 数据域, 所需级别) 逐次鉴权**，刻意不提供「进入一个子租户就等于拿到
  全部后代」的开关。这直接对应 ADR-0005 的「汇总查询按目标逐一鉴权，不扩大 RLS 为全部后代」。
- **配置权限与使用权限是两件事**。配置是 Owner + ALL 专属（复用 `administration_tenant`）；
  但「能否使用这处开口」另看该租户自己的角色是否带 `tenant.hierarchy.read`。
  配了授权不等于租户里每个人都能跨租户读——这是本模块最容易漏掉的一层。
- **撤销是终态**，撤销后不能改回 ACTIVE，重复撤销与修改已撤销记录都 403。
  「清楚的隔离边界和撤权路径」要求撤权是真的撤。
- **列表里 `status` 与 `effectiveStatus` 并存**。只看 status 的话，一条上个月到期但没人撤销的
  授权看起来仍是 ACTIVE，管理员会以为权限还在。
- **审计快照用回读而不是入参拼**：timestamptz 落库按库时区取值，入参拼出来的可能差一个时区偏移。
  审计要记的是真正生效的那一行。

## 踩到并修掉的两个真问题

1. **jOOQ 在纯文本语句里无法为 `OffsetDateTime` 推断类型**——不显式写 `?::timestamptz`，
   驱动按 varchar 处理，`create_hierarchy_grant(...)` 直接报 `does not exist`
   （错误信息里参数类型会显示成 `character varying`）。与 `AuditController` 是同一纪律。
2. **PL/pgSQL 的 `RETURN QUERY` 不接受 `varchar(500)` 到 `text` 的赋值转换**，
   `hierarchy_grants` 整条列表报 `structure of query does not match function result type`。
   `note` 必须写 `::text`。定位方式是逐列探针（把 15 个 OUT 参数逐个替换成真列），
   只有 `g.note` 那一列失败。

## 文件清单

- 迁移 `backend/services/identity/src/main/resources/db/migration/V42__tenant_hierarchy_authorization.sql`
- 端点 `backend/services/identity/src/main/java/com/starcon/identity/HierarchyAuthorizationController.java`
- 测试 `backend/services/identity/src/test/java/com/starcon/identity/HierarchyAuthorizationIntegrationTest.java`
- 复用既有 RLS 断言 `backend/services/identity/src/test/java/com/starcon/identity/IdentityIntegrationTest.java`（表名加入 FORCE RLS 清单）
- 前端 `apps/web/src/components/iam/hierarchy-authorization-workspace.tsx`、`apps/web/src/app/org/hierarchy/page.tsx`
- 导航 `apps/web/src/components/iam/identity-workspace.tsx`、`packages/ui/src/lib/task-pages.ts`
- 代理白名单与断言 `apps/web/src/lib/api-proxy.ts`、`apps/web/src/app/api/iam/[...path]/route.test.ts`
- 契约 `packages/api-contracts/src/identity.openapi.json`、`catalog.ts`

## 后续待办

- 「被授权方的知情权」：子租户目前看不到父租户给了它什么授权（`hierarchy_grants` 只返回本租户
  作为授权方的记录）。这是刻意留白，归属待定模块，需要时应单独评审而不是顺手加进本模块。
- 判定函数目前只被前端判定面板调用。下游服务（catalog / trade / warehouse）真正做跨租户汇总
  查询时还要各自接入 `evaluate_hierarchy_access`，属于各服务自己的模块。
- `catalog.openapi.json` 与 `catalog.ts` 的本次 diff 含非本模块内容：重新生成时 SpringDoc 的
  `operationId` 计数随已并入的 catalog 控制器漂移（`create_6` → `create_7` 等），
  以及 `1` 与 `1.0` 的浮点序列化差异。属 codegen 产物的机械结果，不是语义变更。
