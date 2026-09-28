# 2026-09-29 D1–D2 期启动：channel 服务与 /channels 页面

> 起点：465 个 L4 模块的功能蓝图已就绪（`docs/product/capability-blueprint/`），本轮开始按蓝图的 D1–D7 分期落地。

## 决策摘要

**不做 465 个模块一次性铺开，按蓝图 §8 的 D1–D7 分期推进，每期交付可验证的完整闭环。**

- 选型：一次做透一个系统（迁移 + API + 页面 + 测试全齐），而不是生成 465 个空壳页面。
  理由：空壳页会制造"看起来有其实空"的假象，正是交付红线禁止的形态。
- 首个交付：D1–D2 期的「建租户 → 建商品 → 授权一个店铺」闭环，落在 02 渠道与店铺。
- 后端写法：新服务严格用 jOOQ + 显式 SQL（AGENTS.md 要求），现有 identity/catalog 薄服务保持不动。
- 测试：Testcontainers 真实起 PostgreSQL，RLS 三条断言必须实跑。

## 本轮完成

### 基础设施修复：Testcontainers 在当前 Docker 上跑不起来

现象：所有集成测试在 `<clinit>` 阶段报 `Could not find a valid Docker environment`，但 Docker Desktop 明明健康。

定位过程（值得留档，因为结论反直觉）：

1. `curl --unix-socket .../v1.44/info` 全部 200 → Docker 端没问题。
2. Testcontainers 1.21.3 内嵌的 docker-java 请求的是 `GET /v1.32/info`（用 socket 代理抓到原始请求）。
3. `curl /v1.32/info` 返回 **400**，`/v1.40` 起返回 200 —— Docker Desktop 4.90 声明 `MinAPIVersion=1.40`。
4. 即：client 协商的 API 版本低于引擎下限。

修复：根 pom 统一给 surefire 配 `api.version=1.44`，所有服务继承。commit `9d8dc85`。

### 新增 channel 服务（02 渠道与店铺）

- **数据模型**：`platform` / `marketplace` / `platform_enum_mapping` 是全局码表（无 tenant_id）；
  `credential_ref` / `channel_connection` / `shop` / `shop_marketplace` 是租户业务表（RLS 强制）。
- **凭据安全**：`CredentialMetaView` 只暴露 provider / 引用 / 有效期 / 权限范围。
  wire model 里**不存在**能装明文令牌的类型，因此新增端点不可能意外泄露凭据。
- **RLS**：租户表 FORCE + isolation；`shop.owner_id` 由触发器锁为创建时快照，
  转让必须走显式端点（`app.transfer` 标志位），保证绩效归属变化可审计。
- **能力矩阵**（2.1.1.2）：`platform.capabilities` 是跨系统隐性依赖，09 订单 / 07 库存 / 10 物流 / 11 售后
  引用它来禁用不支持的按钮，而不是让用户点了才报错。

commit `8c3fc96`。

### 前端 /channels

三段式工作区（平台目录 / 授权连接 / 店铺与站点），含凭据轮换、连接健康诊断、站点启停、经营模式说明。
按蓝图 §7 挂在**顶层固定入口**（跨职能，每个角色都要用），不属于任何职能 Tab。

## 关键 trade-off

**契约可空性**：SpringDoc 只从 Bean Validation 注解推导 `required`，导致 `UUID` 字段（含可空的
`connectionId`）被标成必填。曾经的修法是改 codegen 让非 required 字段可空 —— 但这会让 209 行既有契约
变可空、打断 catalog 现有代码。**放弃这条路**，改为在后端用 `@Schema(nullable = true)` 显式声明，
让契约在源头正确。教训：契约的准确性要在生成源修，不在生成器里打补丁。

## 踩坑记录

| 坑 | 结论 |
|---|---|
| `marketplace.code` 声明全局 UNIQUE | 站点码只在**平台内**唯一（每个平台都有 US / DE） |
| jOOQ 绑 timestamptz | **只有 `LocalDateTime` 可用**；`Instant` / `OffsetDateTime` / String 全被绑成 varchar。SQL 里写 `?::timestamptz` 或 `cast(? as ...)` 都会让 jOOQ 报 bad grammar |
| jOOQ 搜索占位符 | `where (?='' or a ilike ? or b ilike ?)` 这类混合写法会让 jOOQ 误判参数个数。改为在 Java 侧决定是否加过滤片段 |
| RLS 策略拼权限码 | `channel_operation()` 返回的已是完整权限码，再拼前缀会导致策略永不命中 |
| shop_marketplace 查询 | 漏了 `tenant_id` 条件，RLS 之外又多一层显式隔离 |
| 代理路由 | `/api/iam` 只是本地别名，**完整路径（含 `identity/`）要原样转发给上游** |

## 后续待办

**D1–D2 期剩余**：
- 17 组织权限：`legal_entity`（法人）与店铺数据范围绑定 —— 店铺已存 `legal_entity_id`，但 identity 侧还没有法人实体
- 02.4 同步运行 / 02.5 平台回写 / 02.6 消息接入（本期未做，`sync_cursor` / `platform_operation` / `webhook_receipt` 尚未建表）

**D3 期**：07 库存核算 + 08 仓储作业

**横切**：
- `spotless:apply` 在 Java 25 上崩溃（`NoSuchMethodError: Log$DeferredDiagnosticHandler`），
  是本仓库既有问题，`./mvnw verify` 不受影响（未绑定到构建生命周期）
- 契约无漂移检查已覆盖 channel（`tools/codegen/catalog-contracts.py` 已加入 8083）

## 文件清单

新增
- `backend/services/channel/`（pom、application.yml、ChannelApplication、AccessService、
  ChannelModels、ChannelService、ChannelController、ApiErrorHandler、
  V1__channel_registry_connections_and_shops.sql、2 个测试）
- `backend/services/identity/src/main/resources/db/migration/V4__channel_page_permissions.sql`
- `apps/web/src/components/channel/channel-workspace.tsx`
- `apps/web/src/app/channels/page.tsx`
- `apps/web/src/app/api/channel/[...path]/route.ts`
- `packages/api-contracts/src/channel.openapi.json`

修改
- `backend/pom.xml`（channel 模块 + surefire api.version）
- `apps/web/src/lib/api.ts`（多服务前缀分发）
- `apps/web/src/lib/api-proxy.ts`（服务表驱动的路由 + channel allowlist）
- `packages/ui/src/lib/nav-config.ts`（/channels 顶层入口）
- `packages/ui/src/lib/task-pages.ts`（/channels 任务页）
- `packages/ui/src/lib/{nav-routes,task-pages}.test.ts`（不变量修正：顶层固定页不计入业务菜单）
- `tools/codegen/catalog-contracts.py`（接入 channel）

## 验证结果

- `cd backend && ./mvnw verify` → **39 tests green**（identity 8 / catalog 11 / channel 20），真实 PostgreSQL
- `pnpm typecheck` → 通过
- `pnpm lint` → 通过
- `pnpm test` → **56 tests green**
- `pnpm build` → Compiled successfully
