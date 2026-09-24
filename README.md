# starcon

> Enterprise SaaS ERP for cross-border e-commerce.

starcon 是面向跨境电商公司（Amazon / Shopify / eBay / TikTok Shop / 1688 / ...）的企业级 SaaS ERP。覆盖多平台订单聚合、库存协同、履约调度、多币种财务结算。

---

## 架构总览

- **Monorepo** + pnpm workspaces + Turborepo
- **Modular Monolith** 后端起步（领域清晰隔离，后续可拆微服务）
- **Cloud-native**：Docker / Helm / K8s / Terraform / GitOps
- **多租户**：Postgres Row-Level Security + 租户上下文中间件
- **Adapter 模式**：平台 / 支付 / 物流三方对接统一接口
- **事件驱动**：Kafka / RabbitMQ（具体见 ADR）

详细架构见 [`docs/architecture/overview.md`](docs/architecture/overview.md)。

## 仓库结构速览

| 路径 | 作用 |
|---|---|
| `apps/` | 可部署单元（web / admin / api / worker / scheduler / notification-gateway） |
| `packages/` | 内部共享库（ui / database / event-bus / logger / auth / tenancy / money / ...） |
| `services/` | 异构服务（Python OCR / Go 数据管道） |
| `workers/` | 独立后台 worker |
| `db/` | 数据库迁移 / 种子 / 复杂 SQL / schema 文档 |
| `tests/` | E2E / 集成 / 压测 / 契约测试 |
| `infra/` | Terraform / Helm / ArgoCD / K8s |
| `deploy/` | Dockerfile / compose / 部署脚本（产物 gitignored） |
| `tools/` | 内部 CLI / codegen |
| `docs/` | 知识沉淀 + 沟通上下文 |

完整目录树和说明见 [`docs/architecture/overview.md`](docs/architecture/overview.md)。

## 快速开始

```bash
# 前置：Node >= 20.11, pnpm >= 9, Docker, Postgres, Redis, Kafka (开发用 docker-compose)
nvm use                                  # 或 nvm install
pnpm install
cp .env.example .env                     # 编辑填值
pnpm db:migrate
pnpm dev                                 # 启动所有 app 的 dev server
```

单启：

```bash
pnpm --filter @starcon/web dev           # 客户端 (Next.js)
pnpm --filter @starcon/api dev           # 后端
pnpm --filter @starcon/admin dev         # 内部后台
```

## 命令一览

| 命令 | 作用 |
|---|---|
| `pnpm dev` | 并行启动所有 app dev |
| `pnpm build` | 构建所有包 |
| `pnpm test` | 跑单元 + 集成测试 |
| `pnpm test:e2e` | E2E (Playwright) |
| `pnpm lint` | ESLint |
| `pnpm typecheck` | tsc --noEmit |
| `pnpm format` | Prettier 格式化 |
| `pnpm db:migrate` | DB 迁移 |
| `pnpm db:generate` | 生成 ORM client |
| `pnpm cli` | 内部 CLI |

## 文档导航

- 📐 [系统架构总览](docs/architecture/overview.md)
- 🏛️ [架构决策记录 (ADR)](docs/decisions/)
- 🧱 [多租户设计](docs/architecture/multi-tenancy.md)
- 🔌 [平台对接架构](docs/architecture/platform-connectors.md)（待写）
- 💰 [财务与多币种](docs/architecture/finance.md)（待写）
- 📋 [产品 PRD](docs/product/prds/)
- 📞 [运维 Runbook](docs/operations/runbooks/)
- 🤖 [AI Agent 协作规范](AGENTS.md)
- 🗣️ [历史会话上下文](docs/sessions/)

## 贡献

请阅读 [`CONTRIBUTING.md`](CONTRIBUTING.md)（待补充）。

## License

Proprietary. 见 [`LICENSE`](LICENSE)。