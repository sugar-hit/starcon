# 2026-09-26 后端语言选型调研

## 背景

starcon 是企业级跨境电商 SaaS ERP,用户明确诉求:

> 不要都是 JS,调研哪种语言适合后端维护

仓库现状(2026-09-26 扫描):
- 前端:`apps/web` + `apps/admin` = TypeScript / Next.js(已完成 UI 框架)
- 后端:`apps/api/` + `apps/worker/` + `apps/scheduler/` + `apps/notification-gateway/` 全部空(只有 `.gitkeep`)
- 共享包:`packages/{database,tenancy,auth,money,errors,...}` 全部空
- 预留异构栈:`services/`(Python OCR / Go 数据管道)+ `workers/` 独立进程

也就是说后端**完全空白**,这是定语言的最佳时机。

## 业务特征 → 语言需求

starcon 业务复杂度高,先把硬需求摆出来:

| 需求 | 影响 |
|---|---|
| SKU 多对多关系 + 变型 + 多语言 | 关系映射、领域建模 |
| 多租户 + RLS + 业务隔离 | DB 集成 + 中间件 |
| 库存/财务不能有 race | 事务管理 + 并发原语 |
| 订单聚合(高并发 I/O) | 异步运行时 |
| 平台/支付/物流 adapter 多 | SDK 生态 |
| 财务对账 / 数据导出(CPU 密集) | 性能 |
| 长期维护(SaaS 5-10 年) | 类型安全 + 招人 + 生态稳定性 |
| 多币种 / 多时区 / 多语言 | 国际化支持 |

## 候选语言横向对比

| 维度 | TS/Node | Go | Rust | Java | Kotlin | Python | C# |
|---|---|---|---|---|---|---|---|
| 业务领域建模 | ⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ |
| 类型系统强度 | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐ | ⭐⭐⭐⭐ |
| 异步 / 并发 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐(虚拟线程)| ⭐⭐⭐⭐⭐(协程) | ⭐⭐⭐ | ⭐⭐⭐ |
| 性能(吞吐量)| ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ |
| 部署轻量 | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐ |
| 招人难度(国内) | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| 跨境电商生态 | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| 类型与前端共享 | ⭐⭐⭐⭐⭐ | ⭐ | ⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐ |
| 长期维护稳定性 | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| 学习曲线 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |

## 关键维度的逐项分析

### TypeScript/Node.js

**优势**
- 与前端 `@starcon/web` 共享类型(`packages/types`)
- monorepo 统一
- Hono/Fastify 写 API gateway 很快
- 异步 I/O 适合聚合层

**劣势**
- 业务核心复杂度(财务对账 / SKU 多对多) → 缺 sum type / sealed class
- 财务计算性能瓶颈(CPU 密集)
- 内存占用大
- 用户已否决"不要都是 JS"

**结论**:不推荐作为核心业务语言,仅适合 API gateway / BFF

### Go

**优势**
- 性能好、部署轻、并发原语干净
- 数据管道 / 高并发 worker 首选
- 国内中大厂主流(字节/腾讯/阿里/滴滴)

**劣势**
- 错误处理冗长
- 领域建模能力弱(无 ADT / sealed class / sum type)
- Web 框架成熟度不如 Spring

**结论**:适合 worker / 数据管道 / 高并发模块,**不适合核心业务**

### Rust

**优势**:性能 + 内存安全 + 类型最强
**劣势**:招人极难(国内 < 2 万)、学习曲线陡、过度工程

**结论**:**不推荐**,招人成本与业务复杂度不匹配

### Java

**优势**
- 企业级标配,跨境电商行业主流(SAP/Oracle/金蝶出身)
- 招人最广,生态最完整
- JPA / Hibernate 处理关系数据最强
- Spring 事务管理 + 安全 + 缓存 / 消息 / 调度全套
- 长期维护性 ⭐⭐⭐⭐⭐

**劣势**
- 启动慢、内存大
- 配置繁琐
- 跟前端类型不能共享

**结论**:**核心业务强候选**。如果团队 Java 多就 Java

### Kotlin(Spring Boot)

**优势**(Java 之上)
- 空安全、data class、sealed class(ADT)、协程
- 比 Java 简洁 30-40%
- 同样吃 Spring 全家桶
- 与 Java 100% 互操作

**劣势**
- 国内 Kotlin 工程师少于 Java
- Spring 启动慢问题仍在(但 GraalVM native image 可解决)

**结论**:**核心业务最佳**。现代化 + 企业级 + 协程并发 + 类型安全,starcon 业务复杂度的最优解

### Python

**优势**
- AI/ML/OCR 生态无敌
- FastAPI 类型注解现代化
- 数据科学 / 报表生成事实标准
- starcon 已经有 `services/` 预留

**劣势**
- 性能瓶颈(GIL)
- 动态类型 + 复杂业务 → bug 风险高
- 不适合 CPU 密集 / 高并发核心

**结论**:**留给 `services/` 数据管道 / OCR / AI 模块**,不参与核心业务

### C# (.NET)

跨境电商国内主流是 Java/Go,C# 团队组建成本高,生态适配弱。**不推荐**

## starcon 架构分层(已预留)

```
┌─────────────────────────────────────────────────────────┐
│  apps/web / apps/admin       Next.js (TypeScript)        │  ← 前端(已定)
├─────────────────────────────────────────────────────────┤
│  apps/api                    API gateway + 业务编排       │  ← 推荐 TypeScript Hono
│                              (轻量、BFF、与前端共享类型)     │
├─────────────────────────────────────────────────────────┤
│  business-core               核心业务服务                  │  ← 推荐 Kotlin Spring
│  (商品/订单/库存/财务/...)    (复杂业务、强事务、长期维护)    │
├─────────────────────────────────────────────────────────┤
│  apps/worker                 异步任务                     │  ← 推荐 Kotlin 同一套栈
│  apps/scheduler              定时调度                     │
│  apps/notification-gateway   推送 / WebSocket            │
├─────────────────────────────────────────────────────────┤
│  services/                   数据管道 / OCR / AI          │  ← Python(FastAPI)
│  (Python OCR / Go 管道)       留给异构技术                 │
├─────────────────────────────────────────────────────────┤
│  db / postgres + RLS          单一真相源                   │
└─────────────────────────────────────────────────────────┘
```

## 推荐方案

### 主推:**Kotlin + Spring Boot 3**(核心业务层)

**理由(排序)**
1. **业务复杂度匹配**:SKU 多对多 / 库存协同 / 财务对账是典型 OOP 重业务,Kotlin 的 sealed class + data class + 协程是 Java 之上最现代的表达力
2. **协程并发**:订单聚合 / 平台数据拉取都是 I/O 密集,协程比虚拟线程更轻
3. **Spring 生态最完整**:JPA(关系映射)+ Spring Security(权限)+ Spring Integration(平台 adapter)+ Spring Batch(数据导入)
4. **类型与 TS 互操作**:用 OpenAPI / protobuf 自动生成 TS 类型,共享 contract
5. **GraalVM Native Image** 可解决启动慢问题
6. **招人中等**:国内 3-5 万 Kotlin 工程师,可从 Java 团队转型

### 备选 A:**Java + Spring Boot 3**(如果团队 Java 多)

- 优势:招人最广,生态最熟
- 劣势:冗长,缺 sealed class / 协程

### 备选 B:**TypeScript(Hono/Fastify)+ Drizzle**(全 TS 路线)

- 用户已否决(不要都是 JS)
- 适合小团队 MVP,不适合 starcon 这种长期 SaaS

### 不推荐

- ❌ 全 Go(领域建模弱)
- ❌ Rust(招人难,过度工程)
- ❌ Python(性能 + 类型)
- ❌ C#(生态适配)

## polyglot 边界的纪律

starcon 已是 monorepo,引入多语言需要明确边界:

### API contract 单一真相源

```
packages/api-contracts/
  ├── openapi/                 # OpenAPI 3.1 spec
  │   ├── product.yaml
  │   ├── order.yaml
  │   └── ...
  └── generated/               # 自动生成的客户端
      ├── ts/                  # 给前端用
      └── kotlin/              # 给后端用
```

**所有跨语言调用经 OpenAPI / protobuf codegen**,不直接互调。手写 client 是反模式。

### 模块边界纪律(扩展现有规则)

> 跨语言模块之间通过 API gateway + OpenAPI,**禁止直连数据库**(除非共享 view)。

```
Kotlin 核心业务 ←→ OpenAPI ←→ TypeScript API gateway
                                ↑
                       前端(TS,共享 types)
```

### 跨语言事务

跨语言的事务用 saga pattern(异步补偿),不试图做强分布式事务。starcon 的库存扣减 + 财务记账走 saga:

```
订单创建 → 库存预占(saga)→ 财务记账 → 失败 → 补偿回滚
```

## 实施路径(如果采纳推荐方案)

### 第一阶段:确认选型 + 起 Kotlin 骨架

1. ADR:`docs/decisions/0002-backend-language-kotlin-spring-boot.md`
2. 起 `apps/business-core/` Spring Boot 3 + Kotlin 2.0 + JDK 21
3. 起 `packages/database/` (Drizzle 不适合 Kotlin,改用 jOOQ 或 Exposed)
4. 起 `packages/tenancy/` Kotlin 版(`withTenantContext`)
5. CI matrix:Java 21 + Gradle + Kotlin

### 第二阶段:第一个模块 — 商品 SKU

跟之前规划的"tenancy + auth + 商品(SKU)"一致,只是把语言从 TS 改成 Kotlin:

- `apps/business-core/src/main/kotlin/com/starcon/product/`
  - `ProductAggregate.kt`(sealed class + 状态机)
  - `ProductService.kt`(领域服务)
  - `ProductController.kt`(REST API + OpenAPI)
  - `ProductRepository.kt`(jOOQ)
- `db/schemas/0001_init.sql` — tenants / users / products / product_variants + RLS
- `apps/api/` TypeScript gateway 转发到 Kotlin 业务服务
- `apps/web/src/app/products/page.tsx` 接真实数据,干掉 DomainPlaceholder

### 第三阶段:扩展到 worker / scheduler

Kotlin 同一套栈,引入 Spring Batch / Quartz 调度。异构优势:
- 高并发模块(订单聚合)→ Kotlin 协程
- 数据导出 / 报表 → Python(FastAPI + Celery)
- 长连接推送 → Kotlin(协程 WebSocket)

## 招人 / 团队组建成本估算

| 语言 | 国内可招 | 月薪中位数 | starcon 团队组建周期 |
|---|---|---|---|
| Java | 100w+ | 25-40k | 1-2 周 |
| Kotlin | 5-8w | 28-45k | 2-4 周(可 Java 转) |
| Go | 30w+ | 25-45k | 2-3 周 |
| TS/Node | 80w+ | 20-35k | 1 周 |
| Python | 100w+ | 20-35k | 1 周 |
| Rust | 1-2w | 35-55k | 8-12 周 |

starcon 5-10 年长期项目,Kotlin 团队组建 2-4 周可接受。

## 结论

**核心业务 → Kotlin + Spring Boot 3**(主推)
**API gateway / BFF → TypeScript + Hono**(轻量)
**数据管道 / AI → Python**(预留 `services/`)
**可选高并发 → Go**(数据管道补充)

如果只选一种,选 **Kotlin**。

## 后续动作

等用户确认选型后:
1. 写 ADR `docs/decisions/0002-backend-language-kotlin-spring-boot.md`
2. 起 Kotlin 项目骨架
3. 写"第一刀"任务清单到 `docs/backlog/`
4. 开干 tenancy + auth + 商品 SKU