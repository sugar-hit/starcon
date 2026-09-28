# ADR-0003: 后端运行时采用 Kotlin + Spring Boot 3

- **状态**: Accepted
- **日期**: 2026-09-27
- **决策者**: Architecture Team
- **关联**: [ADR-0001](0001-monorepo-and-modular-monolith.md)、[ADR-0002](0002-multi-tenancy-strategy.md)、[`docs/investment/2026-09-26-backend-language-research.md`](../investment/2026-09-26-backend-language-research.md)、[`docs/investment/2026-09-27-backend-and-master-data.md`](../investment/2026-09-27-backend-and-master-data.md)

> 本 ADR 解决 `docs/investment/` 中两份调研结论相反的问题：`2026-09-26` 推荐 Kotlin + Spring Boot 3，`2026-09-27` 推荐 TypeScript + NestJS/Fastify。两者只能存活其一，本 ADR 采用前者。后者文档中的**语言选型章节作废**，其余关于主数据归属、一致性与验收顺序的内容**继续有效**，见文末「关联文档的有效性」。

## 背景与问题

截至 2026-09-27，starcon 的服务端是**完全空白**的：

- `apps/api`、`apps/worker`、`apps/scheduler`、`apps/notification-gateway` 只有 `.gitkeep`，`apps/api` 连 `package.json` 都没有
- `packages/{database,tenancy,auth,money,errors,event-bus,logger,observability,i18n}` 同样只有 `.gitkeep`
- `db/{migrations,schemas,queries,seeds}` 全空
- 已完成的是前端：`apps/web` 19 个页面与设计体系

也就是说后端一行代码都没有，这正是锁定语言的窗口期。语言与框架一旦选定，会决定仓库布局、CI、部署产物、招聘方向与未来五年维护成本，属于最难回退的决策。

业务侧对后端的硬性要求：SKU 多对多与变型、多租户强隔离、库存与财务不可有竞态、多平台订单聚合、多币种多时区多语言、以及 5–10 年长期维护。

同时存在一个明确的用户诉求：**「不要都是 JS，调研哪种语言适合后端维护」**。

## 评估的选项

### 选项 A: Kotlin + Spring Boot 3

- 优点:
  - 非空类型 + `sealed class` / 值类，适合表达订单状态机、结算单据这类「有限状态 + 强不变量」的业务模型
  - 值类（value class）可在不牺牲类型安全的前提下表达 `Money` / `SkuId` / `TenantId`，直接消除「把金额当 double 传」这类事故
  - Spring Data、Flyway、jOOQ、Testcontainers 在 JVM 上是一等公民，企业软件生态与招人面都宽
  - 协程可用但不必强上；订单聚合这类 I/O 密集场景用 WebFlux 或虚拟线程都有成熟路径
  - 财务/对账类 CPU 密集任务有 JIT 兜底，不至于像 Node 一样需要提前拆进程
- 缺点:
  - 全栈 TS 单仓被打破，前后端不能共享同一份 TS 类型
  - 需要跨语言契约机制（OpenAPI codegen）
  - 团队需同时维护两套工具链与两套测试
  - 冷启动与内存占用高于 Node

### 选项 B: TypeScript + NestJS(Fastify) + PostgreSQL

- 优点:
  - 与现有 monorepo、严格 TS 规范、`packages/*` 共享类型完全一致，交付最快
  - 前后端同语言，接口联调与类型共享零摩擦
  - 订单聚合这类高并发 I/O 天然契合
- 缺点:
  - 与用户「不要都是 JS」的明确诉求冲突
  - 业务核心复杂度（结算状态机、SKU 多对多变型、财务对账）缺少 sum type / sealed class 表达力
  - 金额运算与 CPU 密集型对账导出是已知短板，需要靠库与纪律弥补
  - 内存占用大，长期运行稳定性弱于 JVM

### 选项 C: 双栈（核心域 JVM + 连接器/BFF TS）

- 优点: 能力最全，两种语言各取所长
- 缺点: 早期要同时运维两套构建、两套部署、两套可观测性；跨栈调试链路长；团队 < 10 人时这是纯负担

## 决策

选 **选项 A: Kotlin + Spring Boot 3**，并把下列技术栈一并锁定。

### 运行时与构建

| 项         | 决定                                                                                      |
| ---------- | ----------------------------------------------------------------------------------------- |
| 语言       | Kotlin 2.x                                                                                |
| 框架       | Spring Boot 3.x                                                                           |
| JDK        | **21 LTS**（Gradle toolchain 固定，不跟随本机 JDK；本机为 JDK 25，工具链会自动解析到 21） |
| 构建       | Gradle + Kotlin DSL，多模块，独立构建根                                                   |
| 构建根目录 | `backend/`（与 pnpm/turbo 并列的独立构建，不混进 turbo pipeline）                         |
| 包管理     | Maven Central                                                                             |

### 目录与模块布局

```
backend/
  settings.gradle.kts
  apps/
    api/                 # HTTP 入口（REST + OpenAPI）
    worker/              # outbox / 平台同步 / 定时任务
  libs/
    tenancy/             # 租户上下文（对应 ADR-0002）
    money/               # 金额值类 + 币种 + 汇率快照
    domain-<name>/       # 各领域模块：order / inventory / catalog / channel / fulfillment / finance ...
    platform-connectors/ # 外部平台适配器，每个 vendor 一个包
    observability/       # 日志 / 指标 / 追踪
    errors/              # 统一错误模型
```

对应到 TypeScript 侧，**职责重新划分**（这是本决策最容易被忽略、但必须一次讲清的后果）：

| 能力                                                                                | 归属                                                |
| ----------------------------------------------------------------------------------- | --------------------------------------------------- |
| 领域逻辑、事务、库存与财务计算                                                      | `backend/`（Kotlin，唯一写入所有者）                |
| 租户上下文、金额、错误模型                                                          | `backend/libs/*`（Kotlin），**不再是 TS 包**        |
| DB 访问、迁移                                                                       | `backend/` + Flyway，**不再是 `packages/database`** |
| 前端 UI                                                                             | `packages/ui`（保留）                               |
| 前后端契约                                                                          | `packages/api-contracts`（由 OpenAPI 生成，保留）   |
| 前端调用客户端                                                                      | `packages/sdk`（由 OpenAPI 生成，保留）             |
| ESLint / TS / Tailwind 配置                                                         | `packages/config-*`（保留）                         |
| `packages/{database,tenancy,auth,money,errors,event-bus,logger,observability,i18n}` | 移除空占位，职责上移到 `backend/libs/*`             |

`apps/{api,worker,scheduler,notification-gateway}` 这四个 TS 空占位目录同样移除，避免出现「后端到底在哪」的双份真相。`scheduler` 与 `notification-gateway` 的职责并入 `backend/apps/worker`，不再作为独立部署单元起步。

### 数据访问与一致性

| 项       | 决定                                                                                |
| -------- | ----------------------------------------------------------------------------------- |
| 数据库   | PostgreSQL 16，唯一交易事实源                                                       |
| 访问方式 | **jOOQ**（类型安全 SQL，生成的 SQL 可审阅）                                         |
| ORM      | 不用 JPA/Hibernate 作为主路径（隐式 SQL 与懒加载会掩盖库存/财务 SQL 的真实行为）    |
| 迁移     | Flyway，SQL 进版本库，评审时直接看 diff                                             |
| 驱动     | **JDBC**（不用 R2DBC：RLS 的 `SET LOCAL` 事务级上下文与 jOOQ 均以 JDBC 为第一公民） |
| 金额     | 数据库 `NUMERIC` + Kotlin 值类，**禁止浮点记账**                                    |
| 事务     | 「订单落库 + 库存预占 + outbox 事件」由应用用例在同一事务内协调                     |
| 库存扣减 | 条件更新或行锁，禁止「先读余量再无锁写回」                                          |
| 消息     | 第一期 PostgreSQL outbox + worker 轮询，**不引入 Kafka**                            |
| 幂等     | 消费者按事件 ID 去重；平台订单按 (shop, external order id) 去重                     |

选择 jOOQ 而非 JPA 的理由：库存与结算的正确性依赖「这段 SQL 到底做了什么」可被逐行审阅。Hibernate 的隐式 flush、懒加载与 N+1 会让关键扣减逻辑变得不可预测，而本项目最不能出错的就是这部分。jOOQ 用生成代码换取编译期类型检查，同时保留完整 SQL 可见性。

### API 与契约

- REST + OpenAPI 3，由 springdoc 从 Kotlin 代码生成
- 前后端类型不同源，**契约是唯一共享面**：OpenAPI → `packages/api-contracts`（TS 类型）+ `packages/sdk`（TS 客户端），两侧 codegen
- 不直接暴露 ORM / jOOQ 表模型给前端
- 首批平台连接器：**Shopify**（REST + Webhook 最简，适合先把纵向闭环跑通）

### 测试与可观测性

| 项        | 决定                                                                            |
| --------- | ------------------------------------------------------------------------------- |
| 单元测试  | JUnit 5 + AssertJ（对应 `docs/` 里「每个新行为必须有测试」的纪律）              |
| 集成测试  | **Testcontainers PostgreSQL**，必须真实跑 RLS 隔离断言                          |
| 隔离验收  | 集成测试必须证明：两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户 |
| 指标/追踪 | Micrometer + OpenTelemetry                                                      |

### 第一期不引入

Kafka、ClickHouse、Kubernetes service mesh、Redis 队列、多集群。`docs/architecture/overview.md` 里的这些是目标蓝图，**不代表已实现，也不是第一期前置条件**。缓存与消息队列都不得作为库存事实源。

## 理由

- 用户诉求「不要都是 JS」是明确输入，选项 B 直接违背它。
- starcon 最难出错的部分是**库存与财务**：不可有竞态、金额不可错、要能逐行审阅。Kotlin 的非空类型 + 值类 + sealed class 正好把这些约束提到编译期；jOOQ + Flyway 把 SQL 的可审阅性落到工具上。这两点是选 A 的决定性理由，而不是「JVM 比较流行」。
- 5–10 年维护周期与招人面：JVM 企业软件生态在 ERP / 结算 / 对账领域的成熟度是 TS 无法对标的。
- 选项 C 的收益要到团队规模与性能瓶颈出现后才兑现，而那时再引入第二个栈，成本远低于现在就同时运维两套。
- ADR-0001 的模块化单体方向不受影响：JVM 内部照样按模块包边界组织，跨模块走公开 service，拆分服务时按包切即可。**但需要修正 ADR-0001 的一处措辞**——它写的是「后续拆微服务时改的是 deployment 拓扑，代码结构几乎不动」，这过于乐观：拆分还涉及事务边界、数据迁移、失败恢复与接口版本。ADR-0001 不改写（ADR 不可改写），以本 ADR 与 ADR-0001 的「折衷/后续待办」为准。

## 后果

### 正面

- 库存、结算、金额的不变量由编译器与类型系统保障，而非依赖评审
- 关键 SQL 进版本库、可 diff、可审阅
- 领域模型（订单状态机、SKU 变型、结算单据）有恰当的表达力
- 企业软件生态、可观测性、招人面与长期维护性最优
- 与 ADR-0002 的 RLS 强一致：JDBC 事务 + `SET LOCAL` 是成熟路径

### 负面 / 风险

- **前后端不能共享 TS 类型**，必须靠 OpenAPI codegen 保证契约一致；codegen 未跑或被手改都会造成漂移 → CI 必须校验生成物与 spec 同步
- 两套工具链、两套测试、两套 CI 阶段，团队认知成本上升
- 前后端联调需要跑两个进程，E2E 环境比纯 TS 单仓复杂
- Kotlin + Gradle 冷启动慢，本地全量构建比 Node 慢
- ADR-0001 承诺的「几乎不动代码即可拆服务」被修正为不成立，拆分成本需重新评估

### 折衷 / 后续待办

- 契约漂移防护：CI 校验 `packages/api-contracts` / `packages/sdk` 与 OpenAPI spec 同步，生成物只读
- Python（OCR）、Go（数据管道）继续作为**独立服务**候选，不进入主交易链路
- 第一个代码里程碑：Gradle + Spring Boot 3 空壳可构建、`backend/libs/tenancy` 落地，并用 Testcontainers 证明 ADR-0002 的三条隔离断言
- 物理目录重构（移除 TS 空占位、新建 `backend/`）作为独立的 atomic commit，与本 ADR 分开
- 「何时拆服务」的临界指标（实测瓶颈、独立团队发布冲突、明确隔离 SLA）另行记录；**不因项目运行时长或「企业级」标签而拆**

## 关联文档的有效性

| 文档                                                                                                          | 状态                                                                                                                                         |
| ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| [`investment/2026-09-26-backend-language-research.md`](../investment/2026-09-26-backend-language-research.md) | 语言选型结论**被本 ADR 采纳**，作为决策依据保留                                                                                              |
| [`investment/2026-09-27-backend-and-master-data.md`](../investment/2026-09-27-backend-and-master-data.md)     | 「服务端语言」章节**作废**；主数据归属表、一致性 7 条、纵向闭环顺序与失败路径验收清单**继续有效**，并与本 ADR 的 jOOQ/Flyway/outbox 决策一致 |
