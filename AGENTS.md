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

## 项目布局

> 完整说明见 `docs/architecture/overview.md`。这里只给最关键的高层骨架。

- `apps/` — 可部署单元
  - `web/` — SaaS 客户端主面板 (Next.js)
  - `admin/` — 内部运营/客服后台
  - `api/` — 后端 modular monolith（业务在 `apps/api/src/modules/`，外部适配器在 `platform-connectors/` `payment-gateways/` `shipping-carriers/`）
  - `worker/` `scheduler/` `notification-gateway/` — 后台进程
- `packages/` — 内部共享库（不可独立部署）。`ui` `database` `event-bus` `logger` `observability` `auth` `tenancy` `money` `errors` `i18n` 等
- `services/` — 异构技术栈（Python OCR、Go 数据管道等）
- `workers/` — 独立 worker 进程
- `db/` — 迁移、种子、复杂 SQL、schema 文档
- `tests/` — 横切 E2E/集成/压测/契约测试
- `infra/` — Terraform / Helm / ArgoCD / K8s manifests
- `deploy/` — Dockerfile、compose、部署脚本（产物在 `deploy/artifacts/`，gitignored）
- `tools/` — 内部 CLI、codegen、脚本
- `docs/` — 知识沉淀 + 沟通上下文（**重要，见下节**）

## 代码风格

- TypeScript 严格模式（`strict: true` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes`），全栈 TS
- Prettier: 单引号、100 列宽、LF、尾随逗号必加
- ESLint: 共享 preset 在 `packages/config-eslint`
- 包命名: `@starcon/<name>`（如 `@starcon/api`、`@starcon/web`、`@starcon/logger`）
- 提交前必须 `pnpm lint && pnpm typecheck`

## 测试

- 单元测试: Vitest（`*.test.ts`，放在被测文件同目录）
- 集成测试: Vitest + Testcontainers（`tests/integration/`）
- E2E: Playwright（`tests/e2e/`）
- 契约测试: Pact 或 OpenAPI Schema 校验（`tests/contract/`）
- 压测: k6 / Artillery 脚本在 `tests/load/`
- **每个新行为必须有测试**，CI 红了禁止合入

## 多租户

starcon 是 SaaS，**所有业务表必须带 `tenant_id`**，DB 层启用 Row-Level Security（Postgres RLS）。任何 API/SQL/缓存 key 设计都必须经过 `@starcon/tenancy` 包获取当前租户上下文，**禁止硬编码租户**。

## 模块边界纪律

- 跨 `apps/api/src/modules/<domain>/` 的访问必须经由对方暴露的 service / event，禁止直接读对方表（除非走共享视图）
- 外部适配器（Amazon / Shopify / ...）只暴露统一接口，具体实现各自隔离，新增平台必须独立 package
- 共享类型放 `packages/types` 或就近放在 `apps/api/src/modules/<domain>/types.ts`，由 codegen 同步

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

- 我想看模块清单 → `apps/api/src/modules/`
- 我想看平台对接 → `apps/api/src/platform-connectors/`
- 我想看 ADR → `docs/decisions/`
- 我想看上次会话决定 → `docs/sessions/`（按日期排序找最新）
- 我想看跨模块 TODO → `docs/backlog/`
- 我想看 DB schema → `db/schemas/` 或 `packages/database/`
- 我想看视觉规范 / 设计令牌 → `docs/design/visual-spec.md`
- 我想加新平台适配器 → 复制 `apps/api/src/platform-connectors/` 下任一 vendor 目录，按统一 interface 实现
- 我想跑某服务 → `pnpm --filter @starcon/<name> dev`

## 禁止事项

- 不要把 `dist` `.next` `.turbo` `node_modules` 提交（已 gitignore，但 agent 也不要用 file write 写到这些路径）
- 不要把 secret / API key 写进代码或 commit message
- 不要跳过测试合入
- 不要直接 push `main`
- 不要在 PR description 里贴 token / password