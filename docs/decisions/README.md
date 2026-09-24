# Architecture Decision Records (ADR)

本目录记录 starcon 所有**架构层面**的决策。每条 ADR 一旦写入**不可改写**，要变就新增一条并 supersede 旧的。

## 怎么写

复制 [`template.md`](template.md)，编号递增（`0001-`, `0002-`, ...），文件名 kebab-case 简短描述。

## 什么时候必须写

- 选 / 换技术栈（语言 / 框架 / DB / 队列 / ORM）
- 模块边界 / 拆分 / 合并
- 多租户 / 安全 / 合规模型
- 部署 / 编排 / IaC 选型
- 跨模块 API 风格 / 事件 schema 变更

## 状态机

- **Proposed**: 已提议，待 review
- **Accepted**: 已通过，正在 / 即将落地
- **Deprecated**: 被新 ADR 取代，新 ADR 在 Superseded by 字段写明
- **Superseded**: 已废弃

## 已决策清单

| 编号 | 标题 | 状态 |
|---|---|---|
| [0001](0001-monorepo-and-modular-monolith.md) | Monorepo + Modular Monolith 起步 | Accepted |
| [0002](0002-multi-tenancy-strategy.md) | 多租户：Shared Schema + RLS | Accepted（待补） |
| _(后续编号陆续补)_ | | |

## Pending（待决策）

- 后端框架 (NestJS / Hono / Fastify / Spring)
- ORM (Prisma / Drizzle / Kysely)
- 消息队列 (Kafka / RabbitMQ / NATS JetStream)
- API 风格 (REST + OpenAPI / tRPC / GraphQL)
- 前端状态管理 (TanStack Query + Zustand vs Redux Toolkit)
- 可观测性栈 (Prometheus + Grafana + Tempo + Loki vs Datadog vs 自托管 ELK)
- K8s service mesh (Istio vs Linkerd vs Cilium)