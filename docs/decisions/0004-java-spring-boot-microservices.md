# ADR-0004: Java + Spring Boot 微服务

- 日期：2026-09-27
- 状态：Accepted（用户明确决定 Java、Spring Boot、微服务；下面细分边界为设计基线，允许评审演进）
- 取代：ADR-0001 的模块化单体部署决策、ADR-0003 的 Kotlin / Gradle / 单 API 布局。
- 保留：Monorepo、前端 TS、OpenAPI codegen、PostgreSQL、jOOQ、Flyway、多租户 RLS。
- 补充 ADR-0002：Shared Schema 指每个服务数据库内部的多租户共享表，不是所有服务共享业务表。

## 原因

用户主要熟悉 Java，需要能够直接阅读、维护后端，并明确要求微服务。仓库尚无 backend 工程，当前只有旧架构文档和数据模型草稿，此时调整没有业务数据迁移成本。旧 ADR 原文保留为历史，不再作为当前实现指令。

## 决策

- Java 21，Spring Boot，Maven Wrapper 多模块；前端继续 pnpm / Turbo。框架具体受支持版本与 jOOQ/Flyway 兼容矩阵在搭骨架时锁定，不照搬旧稿的 Boot 3.x 版本号。
- 每个服务是独立 Spring Boot 应用，有自己的迁移、数据库账号、数据库、部署与测试。可以共用 PostgreSQL 16 集群，禁止跨服务 SQL、外键、视图和写表。
- 业务服务按一致性边界拆分，不按前端菜单或每张表拆分。目标边界：identity、catalog、channel、trade、warehouse、finance；support 后续承担附件与通知，reporting 只消费投影。
- Java 的 record 表达 Money / TenantId 等值对象，BigDecimal 表达金额，enum 配合显式状态转换表达生命周期；不用 Kotlin 源码或 Kotlin 构建脚本。
- jOOQ 生成数据库访问代码，Flyway SQL 是数据库结构唯一可执行来源；禁止实体反向自动建表。JUnit 5 + AssertJ + Testcontainers 验证行为和隔离。
- 服务内本地事务；跨服务可靠消息 + 补偿。每个服务本地 outbox / inbox，不能远程调用公共 outbox 服务实现“同事务”。首个跨服务闭环可用 outbox relay + 内部 HTTP + inbox，消息中间件待吞吐与运维需求确认，不先搭全套 Kafka/注册中心/配置中心。
- 公共 Java 库只放租户、安全、观测、事件信封等技术能力，不共享领域实体、数据库访问对象或业务 service。服务契约可版本化，消费者维护自身模型。

## 代价与控制

微服务带来网络失败、重复/乱序、数据延迟和更多部署。通过六个较粗边界、逐步启用服务、持久化流程状态、超时重试与对账来控制；不承诺跨库强事务或 exactly-once。库存流水与余额同属 warehouse，订单与售后同属 trade，避免拆散关键本地事务。

## 目录（规划，尚未创建）

```text
backend/
  pom.xml / mvnw / .mvn/
  services/{identity,catalog,channel,trade,warehouse,finance}/
    pom.xml
    src/main/java/
    src/main/resources/db/migration/
    src/test/java/
  libraries/{tenancy,security,observability,event-envelope}/
```

计划命令：`./mvnw verify`，单服务 `./mvnw -pl services/identity -am test`。现阶段没有 Wrapper，不声称这些命令已可运行。

## 依据

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)：Java 21 在当前文档支持范围，具体版本在实现时复核。
- [Database per service](https://microservices.io/patterns/data/database-per-service)：数据只能通过拥有服务的接口访问。
- [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox)：业务变更和待发消息同本地事务。
- [PostgreSQL 16 RLS](https://www.postgresql.org/docs/16/ddl-rowsecurity.html)：默认拒绝、运行角色及 owner 绕过限制。

## 关联

- [V2 数据模型](../../db/schemas/v2/README.md)
- [第一阶段字段设计](../../db/schemas/v2/01-foundation.md)
- [架构总览](../architecture/overview.md)
