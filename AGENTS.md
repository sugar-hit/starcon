# AGENTS.md

starcon — 企业级跨境电商 ERP 系统。Monorepo + Modular Monolith + Cloud-Native。
本文件是任何 AI 编码 agent (OpenCode / Codex / Cursor / Aider / Devin / Gemini CLI / Mavis / ...) 接入此项目时的**第一入口**。请先读完再动手。

---

## 项目一句话

starcon 是一个面向跨境电商（Amazon / Shopify / eBay / TikTok Shop / 1688 等多平台运营）公司的企业级 SaaS ERP，核心能力：多平台订单聚合、库存协同、履约调度、多币种财务结算。

## Setup commands

- Node: `>= 20.11.0`（见 `.nvmrc`）
- 包管理器: **pnpm** `>= 9.0.0`（`package.json#packageManager`）
- 安装依赖: `pnpm install`
- 启动所有 app 的 dev: `pnpm dev`（turbo 并行）
- 单启一个 app: `pnpm --filter @starcon/web dev` 或 `@starcon/api` `@starcon/admin`
- 构建: `pnpm build`
- 测试: `pnpm test` / E2E: `pnpm test:e2e`
- 类型检查: `pnpm typecheck`
- Lint: `pnpm lint`
- Format: `pnpm format`
- 数据库迁移: `pnpm db:migrate`，生成 schema client: `pnpm db:generate`
- 内部 CLI: `pnpm cli`

### 后端（Kotlin / Gradle，见 [ADR-0003](docs/decisions/0003-backend-runtime-kotlin-spring-boot.md)）

后端是**独立构建**，不进 pnpm / turbo pipeline。

- 构建根: `backend/`
- 构建: `cd backend && ./gradlew build`
- 单模块测试: `cd backend && ./gradlew :apps:api:test`
- 全部测试（含 Testcontainers 集成测试）: `cd backend && ./gradlew test`
- 数据库迁移（Flyway）: `cd backend && ./gradlew :apps:api:flywayMigrate`
- 本地库: `docker compose -f deploy/docker/docker-compose.dev.yml up -d postgres`

## 项目布局

> 完整说明见 `docs/architecture/overview.md`。这里只给最关键的高层骨架。

- `apps/` — 可部署的**前端**单元
  - `web/` — SaaS 客户端主面板 (Next.js)
  - `admin/` — 内部运营/客服后台
- `backend/` — **后端**，Kotlin + Spring Boot 3 + Gradle 独立构建（[ADR-0003](docs/decisions/0003-backend-runtime-kotlin-spring-boot.md)）
  - `apps/api/` — HTTP 入口（REST + OpenAPI）
  - `apps/worker/` — outbox 消费、平台同步、定时任务
  - `libs/` — 领域模块与共享库：`tenancy` `money` `errors` `observability` `domain-*` `platform-connectors`
  - 数据访问: **jOOQ**（不用 JPA/Hibernate 做主路径），迁移: **Flyway**，DB: **PostgreSQL 16**
- `packages/` — 前端侧内部共享库（不可独立部署）。`ui` `api-contracts`（OpenAPI 生成，只读）`sdk`（OpenAPI 生成，只读）`config-eslint` `config-tailwind` `config-tsconfig` `types`
- `services/` — 异构技术栈（Python OCR、Go 数据管道等）
- `workers/` — 独立 worker 进程
- `db/` — 迁移、种子、复杂 SQL、schema 文档
- `tests/` — 横切 E2E/集成/压测/契约测试（TS 侧）
- `infra/` — Terraform / Helm / ArgoCD / K8s manifests
- `deploy/` — Dockerfile、compose、部署脚本（产物在 `deploy/artifacts/`，gitignored）
- `tools/` — 内部 CLI、codegen、脚本
- `docs/` — 知识沉淀 + 沟通上下文（**重要，见下节**）

## 代码风格

**本项目是双语言栈，不是全栈 TS。** 前端/契约侧 TypeScript，后端 Kotlin（[ADR-0003](docs/decisions/0003-backend-runtime-kotlin-spring-boot.md)）。

### 前端 / TS 侧

- TypeScript 严格模式（`strict: true` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes`）
- Prettier: 单引号、100 列宽、LF、尾随逗号必加
- ESLint: 共享 preset 在 `packages/config-eslint`
- 包命名: `@starcon/<name>`（如 `@starcon/web`、`@starcon/ui`）
- 提交前必须 `pnpm lint && pnpm typecheck`

### 后端 / Kotlin 侧

- Kotlin 2.x，**JDK 21 工具链**（Gradle toolchain 固定，不跟随本机 JDK）
- 不可变优先；金额与业务 ID 用**值类**（value class）表达，如 `Money` / `TenantId` / `SkuId`
- 状态机用 `sealed class` / `sealed interface` 穷尽表达
- 数据访问用 **jOOQ**，不写隐式 SQL，不用 JPA 懒加载掩盖关键扣减逻辑
- 迁移写 Flyway SQL，进版本库、评审看 diff
- 禁止浮点记账：金额一律 `BigDecimal` + 数据库 `NUMERIC`
- 跨模块调用走对方公开 service，禁止直接读对方表

## 测试

**前端 / TS 侧**

- 单元测试: Vitest（`*.test.ts`，放在被测文件同目录）
- E2E: Playwright（`tests/e2e/`）
- 契约测试: OpenAPI Schema 校验（`tests/contract/`）—— 校验 `packages/api-contracts` / `packages/sdk` 与后端 spec 同步，生成物**只读**
- 压测: k6 / Artillery 脚本在 `tests/load/`

**后端 / Kotlin 侧**

- 单元测试: JUnit 5 + AssertK（`src/test/kotlin/`，与主源码同包）
- 集成测试: **Testcontainers PostgreSQL**，必须真实跑 RLS 隔离断言（见下「多租户」）
- 命令: `cd backend && ./gradlew test`

**通用**

- **每个新行为必须有测试**，CI 红了禁止合入

## 多租户

starcon 是 SaaS，**所有业务表必须带 `tenant_id` NOT NULL**，DB 层启用 Row-Level Security。完整策略见 [ADR-0002](docs/decisions/0002-multi-tenancy-strategy.md) 与 [`docs/architecture/multi-tenancy.md`](docs/architecture/multi-tenancy.md)。

后端任何 SQL / 缓存 key 设计都必须经 `backend/libs/tenancy` 获取当前租户上下文，**禁止硬编码租户**。租户 ID 由已认证用户的成员关系解析，不信任客户端 `X-Tenant-Id` 头。

集成测试**必须**证明这三条，缺一不可：两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户。

## 模块边界纪律

- 跨 `backend/libs/domain-<name>/` 的访问必须经由对方暴露的 service / event，禁止直接读对方表（除非走只读共享视图）
- 每张事实表只有**一个写入所有者**
- 外部适配器（Shopify / Amazon / ...）放在 `backend/libs/platform-connectors/`，每个 vendor 独立包，只暴露统一接口
- 前后端类型不同源，**OpenAPI 契约是唯一共享面**；`packages/api-contracts` 与 `packages/sdk` 由后端 spec codegen 生成，手改即视为破坏契约

## PR & commit 规范

- 分支: `<type>/<ticket-id>-<short-desc>`，从 `main` 切
- Conventional Commits: `feat:` `fix:` `refactor:` `docs:` `test:` `chore:` `perf:`
- 一个 atomic 改动 = 一个 commit（不堆积）
- PR 必须有 description + 影响面 + 测试说明 + 截图（UI 改动）
- 必跑 CI: lint / typecheck / unit / build，**全绿才能合**

## 安全

- **任何 .env / secret 一律不入仓库**。`.env.example` 只放变量名和占位
- 接入第三方 API（Amazon/PayPal/...）的 key 全部走 secret manager（K8s External Secrets / Vault）
- GDPR / 数据出境合规见 `docs/compliance/`
- 不要在日志里打 user PII 或全量请求体

## 沟通上下文（AI 协作必读）

跨会话上下文必须落 `docs/`，**不能只在 chat 里**：

| 类别 | 路径 | 性质 | 谁写 |
|---|---|---|---|
| 架构决策（不可逆） | `docs/decisions/000N-*.md` | 永久 | 人 + AI |
| 系统设计（活文档） | `docs/architecture/` | 演进 | 人 + AI |
| 视觉规范（设计令牌 / 组件调性） | `docs/design/` | 演进 | 前端 + AI |
| 产品需求 | `docs/product/prds/` | 演进 | 人 |
| 跨模块 TODO | `docs/backlog/` | 临时集中 | AI（自动） |
| 调研 / 探索 | `docs/investment/` | 一次性 | AI |
| **AI 会话日志** | `docs/sessions/YYYY-MM/` | 项目级可检索 | AI（每轮结束） |

**会话语档收录规范**（详见 `docs/sessions/README.md`）：
- 每次有结论的会话都要落到 `docs/sessions/YYYY-MM/YYYY-MM-DD-<topic>.md`
- 写**决策摘要 + 关键 trade-off + 文件清单 + 后续待办**，不复述整段对话
- 命名用 kebab-case topic，不超过 8 个词
- 当月归档，下一年移入 `archive/`

## 仓库目录速记（给 agent 自己查）

**先分清语言栈再动手**：改前端/契约 → TS 侧；改后端/DB/租户隔离 → `backend/`（Kotlin）。

- 我想看后端领域模块 → `backend/libs/domain-*/`
- 我想看平台对接 → `backend/libs/platform-connectors/`
- 我想看租户上下文实现 → `backend/libs/tenancy/`
- 我想看 DB 迁移 → `backend/apps/api/src/main/resources/db/migration/`（Flyway）
- 我想看前后端契约 → `packages/api-contracts/`（生成物，只读）
- 我想看 ADR → `docs/decisions/`（**动手前先查有没有已决策的同类问题**）
- 我想看上次会话决定 → `docs/sessions/`（按日期排序找最新）
- 我想看跨模块 TODO → `docs/backlog/`
- 我想看视觉规范 / 设计令牌 → `docs/design/visual-spec.md`
- 我想加新平台适配器 → 复制 `backend/libs/platform-connectors/` 下任一 vendor，按统一接口实现
- 我想跑前端服务 → `pnpm --filter @starcon/<name> dev`
- 我想跑后端 → `cd backend && ./gradlew :apps:api:bootRun`

## 禁止事项

- 不要把 `dist` `.next` `.turbo` `node_modules` `build` `.gradle` 提交（已 gitignore，但 agent 也不要用 file write 写到这些路径）
- 不要手改 `packages/api-contracts` / `packages/sdk`（codegen 产物），要改就改后端 DTO 再重新生成
- 不要把 secret / API key 写进代码或 commit message
- 不要在后端用 JPA/Hibernate 懒加载处理库存或财务扣减（关键 SQL 必须可审阅）
- 不要跳过测试合入
- 不要直接 push `main`
- 不要在 PR description 里贴 token / password