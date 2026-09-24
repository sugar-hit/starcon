# 2026-09-25: 仓库骨架设计 + 首次落地

## 摘要

设计并落地 starcon（企业级跨境电商 ERP SaaS）的完整仓库目录结构、根级配置、知识库骨架与首次会话语档。Monorepo + Modular Monolith + Cloud-Native 全栈定型。

## 决策 / 结论

1. **Monorepo (pnpm + Turborepo)** — 见 [ADR-0001](../../decisions/0001-monorepo-and-modular-monolith.md)
2. **Modular Monolith 起步**，后续按流量临界点拆服务
3. **多租户模型**：Shared Schema + Postgres Row-Level Security (RLS) — 见 [`docs/architecture/multi-tenancy.md`](../../architecture/multi-tenancy.md)
4. **三方适配器分目录**：`apps/api/src/platform-connectors/` `payment-gateways/` `shipping-carriers/`，每个 vendor 独立包，统一接口
5. **沟通上下文归档规范建立**：
   - `docs/decisions/` — ADR 永久不可改
   - `docs/sessions/` — AI 会话日志，按月归档
   - `docs/backlog/` — 跨模块 TODO 集中地
   - `docs/investment/` — 一次性调研笔记
   - `docs/architecture/` — 系统设计活文档
6. **AGENTS.md (agents.md 标准)** 作为任何 AI 编码 agent 的第一入口
7. **源码 / 产物严格分离**：`dist` `.next` `.turbo` `node_modules` 全部 gitignore；镜像通过 CI 推仓库不进仓

## 涉及文件

### 根级配置

- `package.json` — workspace 根，pnpm + Turborepo scripts
- `pnpm-workspace.yaml` — workspace 范围
- `turbo.json` — 构建编排 + 缓存策略
- `tsconfig.base.json` — TS strict + noUncheckedIndexedAccess + exactOptionalPropertyTypes
- `.gitignore` — 产物、env、IDE、IDE 数据库全隔离
- `.gitattributes` — linguist-generated 标记生成文件
- `.editorconfig` / `.prettierrc` / `.nvmrc` / `.dockerignore` / `.env.example`
- `LICENSE` — Proprietary

### 文档入口

- `AGENTS.md` — AI 协作入口（含沟通上下文规范）
- `README.md` — 人读入口
- `CHANGELOG.md` — 用户可见变更
- `CONTRIBUTING.md` — 贡献流程

### docs/ 知识库

- `docs/README.md` — 索引
- `docs/architecture/overview.md` — 系统架构 + mermaid 图
- `docs/architecture/multi-tenancy.md` — 多租户设计
- `docs/architecture/data-flow.md` — 5 条核心数据流
- `docs/architecture/security.md` — 安全架构
- `docs/decisions/README.md` + `template.md` + `0001-monorepo-and-modular-monolith.md`
- `docs/sessions/README.md` — 会话语档收录规范（本文件即首篇）
- `docs/backlog/README.md` — 跨模块 TODO 规范
- `docs/onboarding/README.md` — 新人入职
- `docs/product/README.md` / `docs/operations/README.md` / `docs/compliance/README.md` / `docs/investment/README.md`

### 目录骨架（127 个目录）

- `apps/` — 6 个 deployable：web / admin / api (含 11 个 modules + 3 个 adapters 目录) / worker / scheduler / notification-gateway
- `packages/` — 15 个共享库占位（ui / types / database / event-bus / logger / ...）
- `services/` — 异构技术栈（Python OCR / Go 数据管道）
- `workers/` — 独立 worker 进程
- `db/` — migrations / seeds / schemas / queries
- `tests/` — e2e / integration / load / contract / fixtures
- `infra/` — terraform / helm / argocd / k8s
- `deploy/` — docker / scripts / artifacts (gitignored)
- `tools/` — cli / codegen / scripts
- `scripts/` — 仓库根级脚本

## 关键 trade-off / 讨论

### Monorepo vs Polyrepo

- 选 Monorepo 因为 starcon 跨模块类型 / 事件 / 上下文共享极多（订单 schema、租户上下文、币种）
- Polyrepo 会让"一个 PR 改 5 个 repo"成为日常，类型同步靠 npm private registry 成本高
- 早期团队 < 10 人，polyrepo 完全不值得

### Modular Monolith vs Microservices

- 选 Monolith 起步，因为分布式事务 + 调试链路过早在小团队会吃光所有时间
- 但**代码层模块边界严格**（每个 `apps/api/src/modules/<domain>/` 独立目录），后续改 deployment 拓扑即可拆服务
- 这是 Shopify / GitHub / Linear 等成熟 SaaS 都走过的路

### Shared Schema + RLS vs DB-per-tenant

- 选 Shared Schema + RLS，物理共享逻辑隔离，运维成本最低
- DB-per-tenant 在跨境 SaaS 不可行：跨租户 BI 分析、连接数爆炸、schema 迁移爆炸
- 用 trigger + CI lint 双重保证不会出"忘带 tenant_id 的新表"

### 平台适配器目录化 vs 服务化

- 选**目录化**：所有 platform-connectors / payment-gateways / shipping-carriers 都在 `apps/api/src/` 下，跟业务模块同进程
- 服务化时机未到：调用频率不高 / 业务耦合强
- 后续真要独立，抽出 `apps/<connector>-service/` 即可

## 后续待办

### 短期（本周）

- [ ] ADR-0002: 确认消息队列 (Kafka 推荐)
- [ ] ADR-0003: 确认 API 风格 (REST + OpenAPI 推荐)
- [ ] ADR-0004: 确认 ORM (Drizzle 或 Prisma，待评估)
- [ ] ADR-0005: 确认后端框架 (NestJS 推荐)
- [ ] `apps/api/src/infra/` 的 Postgres / Redis / Kafka 客户端抽象层
- [ ] `packages/tenancy` 包实现 (RLS 中间件 + 上下文工具)
- [ ] `packages/database` 包骨架 (Prisma schema / migrations pipeline)
- [ ] CI workflow 起草 (.github/workflows/ci.yml)

### 中期（本月）

- [ ] Terraform 基础 modules: VPC / K8s / RDS / MSK / S3
- [ ] Helm chart 骨架: starcon-api / starcon-web / starcon-worker
- [ ] docker-compose.dev.yml: Postgres + Redis + Kafka + MailHog
- [ ] platform-connectors/amazon/ 的 SP-API client 适配器
- [ ] 第一个真实模块落地: `apps/api/src/modules/auth/` (基础认证)
- [ ] CODEOWNERS 文件建立

### 长期（本季度）

- [ ] ArgoCD application 配置 + GitOps pipeline
- [ ] 可观测性栈部署 (OTel + Prometheus + Grafana + Tempo + Loki)
- [ ] 数据合规文档: GDPR / PIPL / SOC2
- [ ] 第一个 RBAC 设计文档
- [ ] E2E 测试框架选定 (Playwright vs Cypress)

## 关联

- ADR: [`0001-monorepo-and-modular-monolith`](../../decisions/0001-monorepo-and-modular-monolith.md)
- 架构总览: [`docs/architecture/overview.md`](../../architecture/overview.md)
- 多租户: [`docs/architecture/multi-tenancy.md`](../../architecture/multi-tenancy.md)
- 数据流: [`docs/architecture/data-flow.md`](../../architecture/data-flow.md)
- 安全: [`docs/architecture/security.md`](../../architecture/security.md)
- AI 协作入口: [`AGENTS.md`](../../../AGENTS.md)