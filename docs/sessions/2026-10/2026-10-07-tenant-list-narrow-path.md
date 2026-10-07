# 17.1.1.1 租户列表（identity）

车道 `17.1.1.1`，分支 `feat/17-1-1-1`，基线 `12d71b3`（integration/module-dev）。

## 决策摘要

本模块是蓝图点名的**全系统唯一允许的跨租户列表查询路径**，但它是窄权限路径。
交付：可切换租户列表（名称 / 标识 / 状态 / 角色 / 最后登录）+ 当前上下文标识 +
租户管理员维护本租户基本信息。

窄权限语义由三层保证，缺一层就出现越权：

1. **结构上无处可传** —— `switchable_tenants` / `tenant_context` / `update_tenant_profile`
   的签名里只有会话令牌哈希，没有任何 `tenant_id` 参数。不是「不校验客户端传的租户」，
   而是根本没有落点。`X-Tenant-Id` 头在整条链路上没有任何读取点。
2. **集合从成员关系起算** —— 一律 `memberships_for(a.user_id)` 起手再按 membership 聚合角色。
   没有成员关系的租户进不了 `FROM`，而不是靠租户表 join 出去再在前端过滤。
3. **运行时角色兜底** —— `identity_app` 是 `nosuperuser nobypassrls`，
   `membership/role/role_assignment` 全部 FORCE RLS。

管理员同样只能看到自己有 membership 的租户。**刻意不提供按 tenant_id 任意查询的端点**，
api-proxy 白名单也只放行 `tenants` 与 `tenants/current` 两个形状、不接 `{uuid}` 段。

## 关键 trade-off

- **最后登录挂在 `membership` 而不是 `app_user`**：它回答「我上次进入**这个**租户是什么时候」。
  同一个人可以是 3 个租户的成员，三个租户各有各的时刻；挂用户上会把三者显示成同一个时间，
  而那个时间对另外两个租户没有含义。`create_session` 与 `switch_membership` 两处都打点——
  漏掉切换，「只切换不重新登录」的用户最后登录会永远是空的。
- **基本信息只开放 name + status，code 不可改**：`tenant.code` 全局唯一且被外部集成引用，
  改它等于让所有既有映射指向不存在的租户，而蓝图只要求「基本信息」维护。
- **租户停用不可逆**：停用会让该租户全部成员的现有会话当场失效（`memberships_for` 会把它
  从可切换集合剔除）。这是既有机制决定的，本模块不新造语义，但界面在提交前显式二次确认
  并写清后果。

## 过程中修掉的两个真问题

1. **审计快照读不到租户表**：`identity_app` 对 `tenant` **连读权限都没有**（租户目录不允许被枚举，
   这是既有设计）。最初直查 `tenant` 取 before/after 快照 → `permission denied for table tenant`。
   改为经 `tenant_context` 这条窄路径读，顺带证明「运行时角色无法枚举租户」这条不变量成立。
2. **停用永远存不下来**：变更后快照原本再查一次 `tenant_context`，但把本租户停用会让会话当场失效，
   于是 `Session unavailable` → 事务回滚 + 403，停用这个动作永远无法完成。
   改为直接由入参拼出 after 快照（code 不可改，只有 name/status 变化）。

## 文件清单

| 类别 | 位置                                                                                               |
| ---- | -------------------------------------------------------------------------------------------------- |
| 迁移 | `backend/services/identity/src/main/resources/db/migration/V40__tenant_list_context.sql`           |
| 端点 | `AdministrationController`：`GET /identity/tenants`、`GET                                          | PUT /identity/tenants/current` |
| 页面 | `apps/web/src/app/org/tenants/page.tsx` + `apps/web/src/components/iam/tenant-workspace.tsx`       |
| 代理 | `apps/web/src/lib/api-proxy.ts`（identity 白名单新增 `tenants(?:\/current)?`）                     |
| 测试 | `backend/.../TenantListIntegrationTest.java`（10 项）、`app/api/iam/[...path]/route.test.ts`（+1） |
| 契约 | `packages/api-contracts/src/{catalog.ts,identity.openapi.json}`（codegen 产物）                    |
| 台账 | `docs/product/capability-blueprint/module-progress.md` 第 507 行                                   |

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS，6 模块全绿
- `pnpm typecheck` / `pnpm lint` → 通过
- `pnpm test` → **750 passed / 44 files**
  （真实基线是 **749**，不是任务书里写的「约 739」：已用 `git checkout HEAD~1 -- .` 实测确认。
  本车道只 +1 个用例，所以通过数是上升的。）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 成功
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致

## 踩坑记录（留给后来者）

- **契约不要整包带入**：跑 `catalog-contracts.py --spec-dir` 会把并行车道的键序漂移一起带进来
  （`catalog.openapi.json` 1884 行纯重排；`catalog.ts` 里 SupplierQuote* 在已提交文件末尾是
  **乱序追加且无结尾换行**）。已把这些无关漂移剔除，本车道契约差异只剩 identity 的纯新增
  与 `catalog.ts` 的 +3 行。
- **既有产品规则会挡路**：成员不能被直接授予 OWNER（必须走转移所有者操作）；已存在账号
  再加成员身份时密码必须传 `null`（不允许借新建顺带重置）。这两条一开始都让测试 403/409。
- **失去成员关系后是 401 不是 403**：`identity.resolve()` 先失败，界面才不会把失效渲染成
  「你还没有租户」的空列表。

## 后续待办

- 17.1.1.3 层级授权（父租户访问子租户的显式授权）未开发，届时「父租户看得见子租户」才有合法路径。
- 蓝图 §17.1 锚点路由是 `/settings/tenants`，当前 17.1.1 这一族实际落在 `/org/*`；
  台账页面列按兄弟模块惯例保持 `—`，等整族迁完再统一。
