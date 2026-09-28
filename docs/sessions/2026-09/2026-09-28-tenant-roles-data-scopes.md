# 2026-09-28: 租户角色、子租户与数据范围

## 决策摘要

在 `codex/workspace-account` 上保留进入会话时的 52 项未提交状态，在原 identity/catalog、商品工作区和 Next 代理模式内实现角色配置、成员分配、部门、子租户与受限范围代码。初稿由外部编码 CLI 产出，其沙箱内无法完成验收（`~/.m2` 只读、Docker socket 拒绝、字体网络受限），因此**初稿零提交**。接手后在完整权限环境下重跑全部验证、修掉实测发现的缺陷并完成提交，详见「本轮修正」与「真实验证结果」。不将源码编译或前端通过等同于后端功能可用。

- identity 授权响应保留每个角色分配的权限和 scopeMode，并返回认证 membership、部门；服务不信任 X-Tenant-Id。
- CRUD 需同角色包含对应 read；授权/组织写操作限定目标租户 Owner，所有者角色保护与显式转移避免通用分配提权。管理事务锁定会话和租户，等待锁后重新鉴权。
- 子租户创建需显式首位所有者，维护闭包，根下最大深度 5。父级关系不产生成员身份或业务权限，目标租户成员管理须先合法切换；祖先停用阻断后代身份核验。
- catalog 用事务局部 actor/department/permission/grants 及 restrictive RLS 实现 ALL、DEPARTMENT、SELF。权限和范围由同一分配满足，不合并后拼接。SKU/属性沿用 SPU 归属，详情独立裁剪页面读取权限；前端按钮逐记录检查范围。
- 所有新业务表启用 FORCE RLS；全局 page_resource 是发布码表。新增 Flyway V3，不修改已应用的 V1/V2。

## 关键 trade-off

- 当前管理授权由 Owner 执行，普通角色可读取获授权的管理页面；避免未经证明的普通管理员委派链路产生提权。
- 本部门是当前 membership 的单个所属部门；记录归属是创建快照。调岗不隐式迁移历史数据，客户端不能改归属。
- 历史创建人从 catalog 本库审计恢复，历史部门不跨服务猜测。部门未知不匹配 DEPARTMENT，创建人未知仅允许 ALL；此项不会假装历史数据已按部门归类。
- 子租户创建可以显式选择当前账号作为首位所有者，也可以选择其他已有账号或创建本地账号。任何情况下都不因父级身份隐式授权。
- 无新增权限缓存，每个 catalog 请求核验 identity 的最新授权；请求内使用该次授权快照。
- SKU 新建/编辑还校验同商品的属性 read 范围，避免缺少属性读取时使用空表单覆写现有值；按钮采用相同条件。
- 单独导出 SpringDoc 文档支持无数据库契约生成；这不代替 PostgreSQL 测试。

## 文件清单

### identity

- `backend/services/identity/src/main/java/com/starcon/identity/AdministrationController.java`
- `backend/services/identity/src/main/java/com/starcon/identity/IdentityController.java`
- `backend/services/identity/src/main/java/com/starcon/identity/LocalBootstrap.java`
- `backend/services/identity/src/main/java/com/starcon/identity/ApiErrorHandler.java`
- `backend/services/identity/src/main/resources/db/migration/V3__tenant_administration.sql`
- `backend/services/identity/src/test/java/com/starcon/identity/IdentityIntegrationTest.java`
- `backend/services/identity/src/test/java/com/starcon/identity/OpenApiContractTest.java`

### catalog

- `backend/services/catalog/src/main/java/com/starcon/catalog/AccessService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogController.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`
- `backend/services/catalog/src/main/resources/db/migration/V3__record_data_scopes.sql`
- `backend/services/catalog/src/test/java/com/starcon/catalog/CatalogIntegrationTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/OpenApiContractTest.java`

### 前端、契约与文档

- `apps/web/src/app/products/{roles,members,departments,children}/page.tsx`
- `apps/web/src/components/catalog/administration-panel.tsx`
- `apps/web/src/components/catalog/administration-model.ts`、`administration-model.test.ts`
- `apps/web/src/components/catalog/catalog-workspace.tsx`、`product-records.tsx`
- `apps/web/src/app/api/catalog/[...path]/route.ts`、`route.test.ts`
- `tools/codegen/catalog-contracts.py`
- `packages/api-contracts/src/catalog.ts`、`identity.openapi.json`、`catalog.openapi.json`（生成物）
- `docs/operations/catalog-local-development.md`
- 本会话日志。

## 本轮修正（接手后）

- **移除成员时释放部门**：原实现只撤销角色并停用身份，保留 `department_id`，导致该部门被外键永久引用、页面提示"已被成员引用的部门不能删除"且实际删除必然失败。现在移除成员同时清空部门归属，并在集成测试中覆盖"移除成员后可直接删除部门"。
- **契约测试落盘路径**：两个 `OpenApiContractTest` 依赖 `maven.multiModuleProjectDirectory`（surefire 不保证传入，取到 null 直接 NPE），改为从模块目录向上定位 backend 根，两份快照统一落到 `backend/target/openapi/`，供 `catalog-contracts.py --spec-dir` 直接消费。
- **管理页接入导航**：角色配置 / 成员管理 / 部门管理 / 子租户管理 原本只在商品工作区内部子导航可达，未进入 `TASK_PAGES`，侧栏与搜索都找不到。已补齐 `TASK_PAGES` 条目（含真实字段与三段式工作流文案），侧栏「商品档案」分组与工作区搜索均可直达。
- **`navigation-v2` 不再写死目的地数量**：断言由 55/56 改为从 `SYSTEMS` 推导，新增目的地不会再让回归用例失效。
- **spotless 格式化**：identity 6 个文件不符合 google-java-format，已在 JDK 21 容器内 `spotless:apply` 并重跑全部测试。

## 真实验证结果

全部在 JDK 21 Docker 工具链与真实 PostgreSQL 上执行，命令与结果如下。

- `pnpm test:backend`（Docker 内 Maven 3.9.9 + Temurin 21 + Testcontainers PostgreSQL 16）：**BUILD SUCCESS**，19 个测试全绿 —— identity 7 个集成测试 + 1 个契约测试，catalog 10 个集成测试 + 1 个契约测试。
- `spotless:check`（同一 JDK 21 容器）：**BUILD SUCCESS**。
- `pnpm dev:backend`：Flyway 在真实库上成功应用 V3（identity「tenant administration」、catalog「record data scopes」），`flyway_schema_history` 三个版本全部 `success=t`；`POST /auth/login` 返回真实 token 与 membership。
- `pnpm typecheck`、`pnpm lint`、`pnpm test`（36 个 Vitest）、`pnpm build`：**全部通过**。
- `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`：30 个 DTO + 两份 OpenAPI 快照，重新生成后与已提交内容零差异，未手改生成物。
- 浏览器真实验收（Playwright，真实后端栈）：新增 `tests/e2e/tenant-administration.spec.ts` 走完部门 → 角色 → 成员 → 子租户的创建、授权与回收，并验证两条边界：受限成员看不到管理页入口、看不到「新建 SPU」且直连 API 返回 403；子租户所有者在自己租户内具备管理能力但看不到父租户商品。**通过**。
- `tests/e2e/navigation-v2.spec.ts`：**通过**，59 个目的地逐个渲染正确，5 种视口宽度（320/390/768/1440/1920）工作台布局无回归。

## 已知问题（非本轮引入，未修）

- `pnpm audit --audit-level=high`：11 high + 2 critical，全部来自既有依赖 `next@14.2.35` / `postcss@8.4.31` 的公告，本轮未触碰依赖树。
- `pnpm format:check`：90 个文件不合规，绝大多数是本轮未改动的既有欠账（`packages/ui/src/lib/utils.ts`、`README.md`、`pnpm-lock.yaml` 等）。本轮新增/修改的文件已单独通过 `prettier --check`。
- `tests/e2e/scroll-shell.spec.ts`：`main` 内容不足一屏时不会产生滚动，断言 `scrollTop > 0` 失败。已用 `git stash` 对照验证，去掉本轮改动后同样失败，属既有的数据依赖型用例，且不在 CI 门禁内（CI 只跑 `catalog.spec.ts`）。

## 后续待办

1. 正式身份提供方（IdP）接入 —— 需要产品决策，不在本轮范围。
2. 租户属性库与类目模板、独立品牌/分类/单位主数据。
3. 商品列表服务端分页、素材上传与自动翻译、资质到期提醒。
4. outbox 投递 worker，让已写入 `outbox_event` 的领域事件真正投递。
5. 上表三项既有问题独立跟进，其中 scroll-shell 建议改为先造数据再断言滚动。

范围外未实施：IdP、outbox worker、主数据拆分、上传与自动翻译、类目模板。

## 关联

- [上一轮实现](2026-09-28-catalog-postgres-implementation.md)
- [ADR-0005](../../decisions/0005-tenant-hierarchy-and-page-rbac.md)
- [本地操作说明](../../operations/catalog-local-development.md)
