# starcon 新人入职

> 给新加入 starcon 项目的开发者 / AI agent 用。

## 快速上手（30 分钟）

1. 读 [`README.md`](../../README.md) — 项目是什么
2. 读 [`AGENTS.md`](../../AGENTS.md) — 仓库约定 + AI 协作规范
3. 读 [`docs/architecture/overview.md`](../architecture/overview.md) — 系统架构
4. 读最近 3 篇 [`docs/sessions/`](../sessions/) — 最近发生了什么
5. 翻 [`docs/decisions/`](../decisions/) 全部 — 不可逆约束

## 环境搭建

```bash
# 1. 装 Node / pnpm
nvm install && nvm use
corepack enable && corepack prepare pnpm@9.0.0 --activate

# 2. 启动基础设施 (Postgres, Redis, Kafka)
docker compose -f deploy/docker/docker-compose.dev.yml up -d

# 3. 装依赖 + 初始化
pnpm install
cp .env.example .env
pnpm db:migrate
pnpm db:seed:dev

# 4. 启动 dev
pnpm dev
```

## 仓库约定

- 包名: `@starcon/<name>`
- 提交: Conventional Commits
- 分支: `feat/<ticket>-<desc>` / `fix/<ticket>-<desc>` / `refactor/<ticket>-<desc>`
- 一次原子改动 = 一次 commit
- 任何架构决策必须先 ADR

## 术语表

| 术语 | 含义 |
|---|---|
| 租户 (Tenant) | starcon 的 SaaS 客户公司，对应一个 `tenant_id` |
| SPU | Standard Product Unit，商品级（颜色/尺码归一前） |
| SKU | Stock Keeping Unit，库存级（颜色/尺码归一后） |
| Listing | 平台上的商品挂单（一个 SPU 可有多个 Listing） |
| 履约 (Fulfillment) | 拣货 → 打包 → 出库 → 交运的全流程 |
| 结算 (Settlement) | 平台 / 支付渠道打款给商家的对账 |
| RLS | Row-Level Security，Postgres 的行级权限 |
| Outbox | 业务库写消息的事件投递模式，避免分布式事务 |

## 模块 owner

| 模块 | Owner |
|---|---|
| `apps/api/src/modules/auth` | TBD |
| `apps/api/src/modules/tenant` | TBD |
| `apps/api/src/modules/product` | TBD |
| `apps/api/src/modules/inventory` | TBD |
| `apps/api/src/modules/order` | TBD |
| `apps/api/src/modules/fulfillment` | TBD |
| `apps/api/src/modules/shipment` | TBD |
| `apps/api/src/modules/finance` | TBD |
| `apps/api/src/modules/billing` | TBD |
| `apps/api/src/modules/analytics` | TBD |
| `apps/api/src/modules/notification` | TBD |
| `apps/api/src/platform-connectors` | TBD |
| `apps/api/src/payment-gateways` | TBD |
| `apps/api/src/shipping-carriers` | TBD |

（Owner 待各模块负责人填入，CI 会校验模块改动必须经过 owner review）