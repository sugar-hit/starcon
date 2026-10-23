# 2026-10-23: 常用页面收藏（1.2.2.1）

## 摘要

交付能力蓝图 **1.2.2.1 常用页面收藏**（卷 01 工作台与门户，L3 1.2.2 快捷入口下的第一格，
阶段 D7）。模块是**纯个人配置**：复用 1.4.1.2 建好的 `user_preference` 表，用本模块自己的
`page_key = dashboard.favorites`，**不建新表**（V338 预分配但不占用）。落 5 个端点
（identity 服务 `/identity/page-favorites`）与真实前端工作区 `/dashboard/favorites`，
输出蓝图要的「收藏列表（有序、支持拖拽排序）；点击直达」。

## 决策 / 结论

1. **复用 `user_preference`，不建新表 ⇒ V338 预分配但不占用。**
   蓝图原文写明「共享数据：纯个人配置，存 `user_preference`」，而 1.4.1.2 已把它建好
   （`UNIQUE(tenant_id,membership_id,page_key)`，一页一行，二次写入是覆盖不是新增）。
   同批还有 1.4.1.1 / 1.4.2.1 两条车道在用那张表 —— **加一个没有 DEFAULT 的列会让对方的
   INSERT 当场违约**，所以本模块连 ALTER 都不做。V338 空着比撞号好：撞号会让另一条车道的
   Flyway 直接炸掉。
2. **权限闸在数据层，不是返回后再置灰。**
   一条收藏带着目标页面的权限页码 `pageCode`；`<pageCode>.read` 不在我的权限里 ⇒ 该条
   **不返回**，并在 `hiddenByPermission` 里报数。与 1.2.2.2 的权限闸同一纪律。
   `pageCode` 留空 = 该页在权限目录里没有 read 权限 ⇒ **没有闸就没有隐藏**。
3. **隐藏 ≠ 删除，且必须能清掉。**
   权限是临时收回的，隐藏的那条仍留在存储里，权限恢复后自动回来。但用户**看不见也就永远
   删不掉**，于是会攒下一堆幽灵收藏 —— 所以额外提供 `DELETE /identity/page-favorites/hidden`
   一次性清理当前被权限闸挡住的那些。
4. **排序用「移动到第 N 位」而不是「整份顺序覆盖」。**
   前者是**增量**语义，客户端与服务端并发改动时不会互相把对方的顺序整份冲掉。
   拖拽与「上移/下移」按钮走**同一个端点同一套语义**（目标位是**可见清单**的下标），
   两条路径不可能给出不同的顺序。
5. **排序位存在载荷里（`order` 字段）而不是新建一列。**
   `user_preference` 是「一页一行 + JSONB」的形状（V336 已定死），拖拽排序用**数组顺序**
   表达，`order` 只是数组下标的冗余副本（读时以数组顺序为准）。
6. **刻意不新登记权限码。**
   本端点只能碰到「我自己」的收藏 —— 边界是**数据归属**（tenant_id + membership_id + RLS），
   不是功能开关。另立 `identity.page_favorite.*` 只会带来一个坏处：管理员随时会新建角色，
   而新角色默认没有这个码，于是普通成员的收藏静默失效。
7. **租户与 membership_id 只从已认证会话解析**（`session_access`）。
   `X-Tenant-Id` 头从头到尾没有出现过；请求体**不接受** membershipId / userId / tenantId。
   伪造租户头改不动 `app.current_tenant`，也拿不到别人的 membership_id。
8. **「只看自己的」在两层保证。**
   RLS 保证「本租户」，SQL 里的 `membership_id = ?` 保证「本租户里的这个人」。
   少任何一层都会变成「能看同事收藏了哪些页面」—— 集成测试把这一条单独断言了。
9. **路径必须是站内相对路径。**
   拒掉外站地址（钓鱼）、协议相对 `//`（浏览器当外站）、`..`（路径穿越）、
   反斜杠（部分代理会规范化成 `/`）、空白与控制字符、超长。
10. **载荷校验做成不碰数据库的纯函数**（`PageFavoriteRules`），规则要能被单元测试逐条钉住，
    而不是只能靠集成测试间接观察。读载荷时**逐条跳过不合法项**（少一条好过整个收藏区打不开），
    且**同一路径只留第一条**。
11. **页面注册表只读共享的 nav-config，另建一份权限页码映射。**
    `packages/ui/src/lib/nav-config.ts` 是多车道共用的导航真相源，本模块**一字未动**；
    权限码是后端 `permission` 表的东西，nav-config 里没有这个维度，所以映射放在本车道
    （`apps/web/src/lib/favorite-page-registry.ts`）。
12. **与 1.2.2.2 快捷入口配置严格分工。**
    那边是「按角色/团队**预置**的模板」（表 `shortcut_entry_template`，迁移 V335），
    本模块是「用户**自己收藏**的页面」。两者**不共表、不共端点、不共实现**。
    1.2.2.2 的台账里写明「覆盖项不能凭空新增入口」，正是为了不给本模块造出第二个真相。
13. **路由另开 `/dashboard/favorites`，不动 1.2.2.2 的 `/dashboard/shortcuts`。**
    蓝图把 1.2.2 的路由锚在 `/dashboard/shortcuts`，但那一格已被 1.2.2.2 交付占用
    （Next.js 同一路由不能有两个 `page.tsx`），而本格做的是另一件事（个人收藏 vs 岗位模板）。
    台账里 1.2.2.1 那一行的路由列**保持原样未改**（纪律：只改自己那一行的状态与证据两格）。
14. **拖拽用浏览器原生 DnD（零依赖）+ 一组键盘与读屏可用的上移/下移按钮。**
    本仓前端没有拖拽组件，引入一个重量级依赖只为拖一条列表不划算（多数拖拽库默认**不**
    支持键盘）。两条路径走同一个端点、同一套语义。

## 关键 trade-off

- **端点挂在 identity 服务**，而不是新开一个服务：收藏是纯个人配置，与 1.4 个人设置同域；
  catalog / trade / channel / warehouse 都不拥有「个人偏好」这个概念。
- **取消收藏的目标路径走 `?href=` 查询参数**，而不是路径段：路径里带 `/` 要转义，
  查询参数更不容易出错，也不会让 api-proxy 白名单多一个「路径段里含斜杠」的形状。
- **api-proxy 用独立元素而不是往既有 `identity/` 巨型 alternation 里并分支**：本文件是多车道
  共用的累积型白名单，**没有** union merge driver（`merge-union.mjs` 只并 api-contracts），
  两条车道改同一行正则必然 git 冲突（结构性，retry 永远不会自愈）。
  锚点选在 1.2.2.2 `shortcut-templates` 那条**之后**，**与 1.1.1.3 的锚点
  （`user-preferences` 元素之后，约第 1381 行）相距 1538 行**，远大于 300 行的要求。
- **侧边栏嵌入没做（明确的边界，不是半成品）**：蓝图的**输出**一栏（收藏列表 + 有序 +
  拖拽排序 + 点击直达）已全部兑现；而「工作台侧边快捷区」的容器是**共享**的
  `packages/ui/src/components/layout/app-shell.tsx` 里的 `<aside>`（硬编码，没有给 apps/web
  注入的插槽），且该包是纯展示层、不做取数。改它等于动多车道共用的无 union driver 文件
  （与 api-proxy.ts 同类问题），风险高于收益，故本格交付独立工作区页面。

## 涉及文件

**迁移**：无（复用 `V336__identity_user_preference.sql`；**V338 预分配但不占用**）

**后端**
- `backend/services/identity/src/main/java/com/starcon/identity/PageFavoriteModels.java`
- `backend/services/identity/src/main/java/com/starcon/identity/PageFavoriteRules.java`（纯函数校验与排序）
- `backend/services/identity/src/main/java/com/starcon/identity/PageFavoriteService.java`
- `backend/services/identity/src/main/java/com/starcon/identity/PageFavoriteController.java`

**后端测试**
- `backend/services/identity/src/test/java/com/starcon/identity/PageFavoriteIntegrationTest.java`（17 例）
- `backend/services/identity/src/test/java/com/starcon/identity/PageFavoriteTenancyIntegrationTest.java`（4 例，三断言）
- `backend/services/identity/src/test/java/com/starcon/identity/PageFavoriteRulesTest.java`（17 例）

**前端**
- `apps/web/src/lib/favorite-page-registry.ts`（页面注册表：路由 + 标题 + 所属系统 + 权限页码）
- `apps/web/src/components/dashboard/page-favorite-model.ts`（纯函数：解析 / 排序 / 筛选 / 权限判定）
- `apps/web/src/components/dashboard/page-favorite-workspace.tsx`（工作区）
- `apps/web/src/app/dashboard/favorites/page.tsx`

**前端测试**
- `apps/web/src/components/dashboard/page-favorite-model.test.ts`（25 例）
- `apps/web/src/app/api/page-favorite-allowlist.test.ts`（5 例，双向钉住白名单）

**共享文件（按纪律只增量，不改既有行）**
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立的 `services[]` 元素**）
- `packages/api-contracts/src/contracts/identity.ts` + `packages/api-contracts/src/identity.openapi.json`
  （`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重生成）
- `docs/product/capability-blueprint/module-progress.md`（只改 1.2.2.1 那一行）

**端点**
- `GET    /identity/page-favorites` — 收藏清单（有序，无权限的自动隐藏）
- `POST   /identity/page-favorites` — 收藏一个页面（同路径重复收藏是覆盖）
- `PUT    /identity/page-favorites/order` — 把某条移到可见清单第 N 位
- `DELETE /identity/page-favorites?href=…` — 取消收藏（幂等）
- `DELETE /identity/page-favorites/hidden` — 清理因权限不足被隐藏的收藏

## 验证

- L0：`cd backend && ./mvnw -q compile` → BUILD 绿；`pnpm typecheck` → 3 包全绿
- L1 后端：`./mvnw -pl services/identity -am test -Dtest='PageFavorite*'` → **38 例全过**
  （Testcontainers PostgreSQL 16.15，连接池压到 1 条）
- L1 前端：`pnpm vitest run apps/web/src/components/dashboard/page-favorite-model.test.ts
  apps/web/src/app/api/page-favorite-allowlist.test.ts` → **30 例全过**
- 全量 allowlist 回归（改了 api-proxy.ts 必跑）：`pnpm vitest run apps/web/src/app/api`
  → **207 文件 / 1183 例全过**
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  → identity 新增 4 个 `PageFavorite*` 类型与 3 条路径，**其余 5 个服务逐字节不变**；
  **重跑 codegen 与产物逐字节一致**（确认是生成物不是手改）
- api-proxy 零命中**本车道独立实测**（不引用他车道结论）：既有 192 条正则里 191 条可解析，
  8 个形状（3 放行 + 5 刻意不放行）逐条 `test()` **全零命中**；反向把既有 140 个字面量
  token 喂给本模块正则，**零误伤**

## 后续待办

1. **工作台侧边快捷区嵌入**未做（见上面 trade-off 第 4 条）：需要先给
   `packages/ui` 的 `app-shell` 开一个插槽，或把侧边栏改成由 apps/web 注入，
   否则动共享文件的风险高于收益。
2. **1.4.1.1 工作台布局**将来复用**同一张** `user_preference` 表、用它自己的
   `page_key`（例如 `dashboard.layout`）—— 本模块**不需要** ALTER 那张表，也不需要数据迁移。
3. 台账总览与分阶段统计由 `node tools/module-progress/sync.mjs` 重算；
   本车道按纪律**只改了自己那一行**，没有跑 sync（跑它会顺手改别的行的路由列并重排整表）。
