[English](README.md) | **简体中文**

<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="apps/web/public/logo-app-icon-light.svg">
  <source media="(prefers-color-scheme: light)" srcset="apps/web/public/logo-app-icon-dark.svg">
  <img src="apps/web/public/logo-app-icon-dark.svg" alt="starcon 应用图标" width="96" height="96">
</picture>

# starcon

### 跨境电商的共同业务操作系统。

一套贯穿商品、渠道、订单、库存、履约、财务、增长与客服的多租户业务底座。

[![License: PolyForm Noncommercial](docs/design/readme/license.svg)](LICENSE)
[![Java 21](docs/design/readme/java.svg)](backend/pom.xml)
[![Spring Boot 3.5](docs/design/readme/spring-boot.svg)](backend/pom.xml)
[![Next.js 15](docs/design/readme/next.svg)](apps/web/package.json)
[![TypeScript](docs/design/readme/typescript.svg)](apps/web/tsconfig.json)
[![PostgreSQL 16](docs/design/readme/postgresql.svg)](deploy/docker/docker-compose.catalog.yml)

[为什么做 starcon](#为什么做-starcon) · [项目亮点](#项目亮点) · [实现进度](#实现进度) · [快速体验](#快速体验) · [系统架构](#系统架构) · [使用许可](#使用许可)

</div>

---

## 为什么做 starcon

做跨境电商，往往意味着同时应付一堆互相对不上的系统：店铺后台、商品资料表、仓库系统、结算报表、广告后台、客服工单。同一个商品在每个平台各有一个编号，订单和库存越走越偏；团队一大，谁能看到哪位客户的数据反而说不清了。

starcon 用一套业务底座替掉这堆拼装。商品档案只有一个家，库存每一次变动都留痕，每一个操作都绑着明确的权限，每一个领域只拥有一份事实。跨平台协作于是从「对账」变回「日常」。

项目面向 Amazon、Shopify、eBay、TikTok Shop、Temu、Lazada、Shopee、Walmart 等平台。渠道服务已实现这些平台的 OAuth 授权适配器；订单接入、刊登同步、广告数据等实网业务接口仍待按验收记录逐项确认。

## 项目亮点

- **一条商品主线贯穿全链路**：SPU 与 SKU 承载规格组合、属性继承与显式覆写；品牌、单位、套装 BOM 与商品资料和刊登、定价、采购记录放在一起，而不是散在另一张表里。
- **隔离由数据库保证，不靠约定**：租户上下文来自已认证的成员关系；业务表带 `tenant_id NOT NULL` 并启用行级安全；页面权限与数据范围在同一个角色里一起授予。父租户不会因为层级关系就看见子租户的数据。
- **九个服务，一份事实只有一个主人**：identity、catalog、channel、trade、warehouse、crm、finance、reporting、platform 各自拥有数据库与 Flyway 迁移；跨域协作走授权 API 与事件——没有跨服务 SQL，没有跨库外键，也没有共享视图。
- **功能蓝图已全覆盖**：18 个业务系统的 465 个 L4 模块全部登记在交付台账里，每个都附上迁移、接口、页面与测试作为证据。
- **财务与分析是内建的，不是后补的**：应收应付、收付款、凭证、对账、成本、递延结算、利润报表、自定义看板与 SKU 级利润——金额用 `BigDecimal` 计算，落到 `NUMERIC` 列，从接口一直贯穿到账簿。
- **自动化与 AI 属于平台本身**：智能查询、AI 诊断与预测、带效果度量的自动化规则、API 网关、事件订阅与外部集成，都是正式服务，而不是挂在页面上的脚本。
- **契约让两侧都不跑偏**：后端 OpenAPI 生成 TypeScript 契约；CI 按服务跑 PostgreSQL 集成测试，再叠加前端单元测试、浏览器测试与蓝图台账一致性校验。
- **证据与代码放在一起**：四级功能蓝图、架构决策记录、模块台账与逐会话的验收记录，让评审能把一条需求一路追到实现。

## 实现进度

> 项目处于持续开发阶段。以下内容对齐 **2026-11-05** 的 `main` 分支，描述的是仓库里真实存在的东西，不代表已经可以生产上线。

[交付台账](docs/product/capability-blueprint/module-progress.md)记录了 **465 个规划模块，465 个全部标记为已交付（100.0%）**。模块只有迁移、接口、页面、测试四件齐全才算「已交付」，所以这个数字衡量的是**蓝图覆盖度**——不是生产成熟度，也不是与任何平台的实网连通性。`pnpm progress:check` 负责保证台账与蓝图不漂移。

| 领域                 | 卷    | 模块数 | 当前已有实现                                                       | 继续推进的方向              |
| -------------------- | ----- | ------ | ------------------------------------------------------------------ | --------------------------- |
| 工作台与门户         | 01    | 23     | 角色化工作台、待办入口、指标卡、店铺概览、个人设置                 | 指标口径与实时刷新          |
| 渠道与店铺           | 02    | 25     | 店铺连接、平台 OAuth 授权适配器、刊登映射、同步任务与回写管理      | 实网 API 验收与真实刊登发布 |
| 商品主数据           | 03    | 40     | SPU / SKU、属性、规格、品牌、单位、BOM、资料及往来方相关模块       | 商品资料治理                |
| 刊登与内容           | 04    | 26     | 刊登编辑器、类目映射、关键词、生命周期、发布任务、内容回写         | 真实刊登发布与平台同步      |
| 定价与价目           | 05    | 19     | 价格策略、价目表、汇率、批量维护与优先级冲突检测                   | 平台实时调价                |
| 采购与供应           | 06    | 26     | 采购单、到货、需求计划、供应商资质与评分                           | 供应商结算联动              |
| 库存与仓储           | 07–08 | 53     | 库存台账、预占、收货上架、调拨、盘点、拣货、打包与发货交接         | 多仓货权与全链路验收        |
| 订单、物流与售后     | 09–11 | 76     | 订单聚合、变更取消、履约路由、待发货、运费与头程、报关、退货与纠纷 | 平台订单实网接入            |
| 财务与利润           | 12–13 | 55     | 应收应付、收付款、凭证、对账、成本、结算、利润与自定义报表         | 多币种递延结算与正式报表    |
| 广告与增长           | 14    | 22     | 广告活动、预算、竞价、归因、促销、达人建联与投放诊断               | 平台广告实网接口            |
| 客户与客服           | 15    | 24     | 客户档案、生命周期、会员等级、积分、评价、服务工单与自动回复       | 全渠道会话接入              |
| 合规与风控           | 16    | 23     | 关联与 IP 风控、买家黑名单、平台违规与申诉、EPR/VAT 税务、数据留存 | 规则运营与外部合规服务接入  |
| 组织权限与系统设置   | 17    | 29     | 成员、角色、页面权限、数据范围、部门、子租户与审批规则             | 正式身份提供方接入          |
| 集成开放与智能自动化 | 18    | 24     | 智能查询、AI 诊断与预测、自动化规则、API 网关、事件订阅与效果评估  | 模型与外部系统实网验收      |

页面、配置记录或任务接口的存在，不等于外部平台已经跑通真实连接。范围与证据请查[功能蓝图](docs/product/capability-blueprint/README.md)、[交付台账](docs/product/capability-blueprint/module-progress.md)和[会话验收记录](docs/sessions/2026-11/)。项目的详细文档目前以中文为主。

## 快速体验

### 环境要求

- **Node.js ≥ 22.12.0** 与 **pnpm 9**——仓库固定 `pnpm@9.0.0`。
- Docker Desktop，或带 Compose 的 Docker Engine，用于跑 PostgreSQL 和后端服务。
- **JDK 21**，且只在宿主机直接构建后端时才需要；容器镜像自带 Java 工具链。

### 本地启动

```bash
git clone https://github.com/sugar-hit/starcon.git
cd starcon
nvm install
nvm use
corepack enable
pnpm install

# 构建并启动 PostgreSQL 与九个后端服务
pnpm dev:backend

# 启动前端（保持此终端运行）
pnpm --filter @starcon/web dev
```

打开 [http://localhost:3000/products](http://localhost:3000/products)。首次后端构建要下载依赖，等服务就绪后再登录。

首次启动会生成 `deploy/.env.catalog.local`。本地账号是 `local-admin`，密码取该文件的 `LOCAL_ADMIN_PASSWORD`；这个文件请留在本地，不要提交、不要外传。初次引导会建好开发租户和 Owner 成员，但不预置演示商品。细节见[本地开发指南](docs/operations/catalog-local-development.md)。

| 服务       | 本地端口 | 职责                  |
| ---------- | -------- | --------------------- |
| Web        | 3000     | 浏览器工作区          |
| identity   | 8081     | 身份、组织与权限      |
| catalog    | 8082     | 商品与主数据          |
| channel    | 8083     | 渠道、店铺与刊登      |
| warehouse  | 8084     | 库存与仓储作业        |
| trade      | 8085     | 订单、物流与售后      |
| crm        | 8086     | 客户与客服            |
| finance    | 8087     | 核算、结算与成本      |
| reporting  | 8088     | 看板、分析与导出      |
| platform   | 8089     | 自动化、AI 与开放集成 |
| PostgreSQL | 5433     | 各服务的独立数据库    |

服务端口绑定配置以 [`docker-compose.catalog.yml`](deploy/docker/docker-compose.catalog.yml) 为准。

### 值得走一遍的演示路线

1. **从工作台开始**：待办入口、指标卡与店铺概览，先看清业务的形状。
2. **建一份商品档案**：创建 SPU、配置属性与规格轴、建立 SKU，看清属性怎么继承、怎么覆写。
3. **划一条权限边界**：配置角色与成员，验证受限成员的可见范围，再看子租户如何看不见父租户。
4. **跟着实物走一圈**：从库存预占到拣货、打包、发货交接，再走进一次退货。
5. **把财务闭环合上**：读懂利润报表背后的凭证、对账任务与结算记录。
6. **试试自动化层**：跑一次智能查询、一次 AI 诊断，再执行一条自动化规则并查看效果评估。

后三步需要先备好上游业务数据与对应权限。各页面的前置条件和当前限制，见[模块台账](docs/product/capability-blueprint/module-progress.md)所链接的验收记录。

## 系统架构

![starcon service architecture](docs/design/readme/architecture.svg)

图中展示当前服务与数据所有权。业务服务还通过授权接口核验身份与权限；跨服务调用不直接读写对方数据库。服务边界见 [ADR-0004](docs/decisions/0004-java-spring-boot-microservices.md)，更完整的视图见[架构总览](docs/architecture/overview.md)。

| 层次 | 技术选择                                                          |
| ---- | ----------------------------------------------------------------- |
| 前端 | Next.js 15、React 18、TypeScript 严格模式、Tailwind CSS、Radix UI |
| 后端 | Java 21、Spring Boot、Maven Wrapper、jOOQ                         |
| 数据 | PostgreSQL 16、Flyway SQL 迁移、Row-Level Security                |
| 契约 | SpringDoc OpenAPI、TypeScript 代码生成                            |
| 验证 | Vitest、Playwright、JUnit 5、AssertJ、Testcontainers PostgreSQL   |
| 工程 | pnpm workspaces、Turborepo、Docker Compose、GitHub Actions        |

云原生部署与事件协作按阶段推进；规划中的中间件与基础设施不作为当前运行依赖。

## 仓库结构

```text
starcon/
├── apps/
│   ├── web/                 # Next.js 工作区（当前已实现的前端）
│   └── admin/ api/ worker/ scheduler/ notification-gateway/   # 仅有脚手架
├── backend/
│   ├── services/            # identity / catalog / channel / trade / warehouse /
│   │                       # crm / finance / reporting / platform
│   └── libraries/           # 租户、安全等共享技术能力
├── packages/                # 前端共享库：ui（设计系统）与 api-contracts（生成物）
│                            # 已落地，tenancy / money / observability / i18n / ... 仍为占位
├── services/                # 异构服务（OCR、数据管道）—— 仅有脚手架
├── workers/                 # 独立 worker（平台同步、发信、报表）—— 仅有脚手架
├── db/schemas/              # 数据模型设计；可执行迁移以各服务 Flyway 为准
├── deploy/                  # 容器镜像、Compose、本地启动与测试脚本
├── infra/                   # Terraform / Helm / K8s / ArgoCD —— 仅有脚手架
├── tests/                   # 跨模块、契约、集成、压测与浏览器测试
├── tools/                   # 内部 CLI、契约代码生成、台账同步
└── docs/                    # 蓝图、决策、设计、合规与验收记录
```

## 开发与验证

```bash
pnpm lint
pnpm typecheck
pnpm test                    # 前端单元测试
pnpm build                   # 前端与共享包构建
pnpm test:backend            # Docker 内 Java 21 + PostgreSQL 集成测试
pnpm test:e2e                # 浏览器测试；按测试配置准备运行服务
pnpm contracts:generate      # 由后端 OpenAPI 重新生成前端契约
pnpm db:migrate              # 执行各服务的 Flyway 迁移
pnpm db:generate             # 从已迁移数据库生成 jOOQ 代码
pnpm progress:check          # 校验蓝图与台账同步
```

宿主机使用 JDK 21 时，可在 `backend/` 执行 `./mvnw verify`。数据库变更一律新增 Flyway 迁移；前端契约由后端 OpenAPI 生成，不手工修改生成物。

## 文档导航

| 想了解什么           | 阅读入口                                                                                                                        |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| 产品覆盖范围与路线图 | [18 系统功能蓝图](docs/product/capability-blueprint/README.md)                                                                  |
| 哪些模块已完成       | [模块交付进度与证据](docs/product/capability-blueprint/module-progress.md)                                                      |
| 服务边界与技术决策   | [架构总览](docs/architecture/overview.md) · [ADR](docs/decisions/README.md)                                                     |
| 租户隔离与授权       | [多租户设计](docs/architecture/multi-tenancy.md) · [租户层级与页面 RBAC](docs/decisions/0005-tenant-hierarchy-and-page-rbac.md) |
| 本地运行与数据库操作 | [本地开发指南](docs/operations/catalog-local-development.md)                                                                    |
| 界面设计             | [视觉规范](docs/design/visual-spec.md)                                                                                          |
| 验收记录             | [会话归档](docs/sessions/README.md)                                                                                             |
| 如何参与             | [贡献指南](CONTRIBUTING.md) · [AI 协作规范](AGENTS.md)                                                                          |
| 使用与商业授权       | [许可说明](LICENSING.md) · [完整许可证](LICENSE)                                                                                |

## 使用许可

**源码公开，允许协议范围内的非商业体验、学习、研究、修改与分享；商业使用须事先取得相关权利人的单独书面授权。**

本项目采用 [PolyForm Noncommercial License 1.0.0](LICENSE)，SPDX 标识为 `PolyForm-Noncommercial-1.0.0`。限制商业用途的许可证属于 **source-available（源码可用）**，不属于 OSI 定义的开源许可证。

将本项目用于企业经营、收费 SaaS、销售衍生产品或商业交付等用途，不能仅凭仓库公开或 fork 权限取得商业许可。请通过[仓库维护者主页](https://github.com/sugar-hit)联系，或在 [Issues](https://github.com/sugar-hit/starcon/issues) 中提出不含敏感信息的授权咨询；咨询本身不构成授权。

完整权利、义务及标准协议中的例外以 [`LICENSE`](LICENSE) 为准；中英文说明、第三方素材与商业授权流程见 [`LICENSING.md`](LICENSING.md)。分发时须保留许可证和 [`NOTICE`](NOTICE) 中的 Required Notice。

---

<sub>最后更新：**2026-11-05**（内容对齐 `main` 分支）· <a href="https://github.com/sugar-hit/starcon/commits/main">提交历史</a> · <a href="https://github.com/sugar-hit/starcon/graphs/contributors">贡献者</a> · <a href="https://github.com/sugar-hit/starcon/issues">Issues</a></sub>
