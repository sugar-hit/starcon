# 2026-09-27: Java 微服务与数据模型 V2

## 摘要

用户明确要求 Java + Spring Boot + 微服务，先做数据结构设计。核实 backend 工程不存在，但 db/schemas 已有旧单体草稿。新增 ADR-0004 和 V2 数据模型，不执行旧 SQL，不创建业务代码。

## 决策 / 结论

- 用 Java 21 / Spring Boot / Maven，保留 PostgreSQL、jOOQ、Flyway；框架具体版本骨架阶段锁定。
- 六个业务服务边界为 identity/catalog/channel/trade/warehouse/finance，独立数据库、账号与迁移，逐个实现。
- 每库内部 RLS；跨服务逻辑引用，禁止跨库 FK/视图；每服务本地 outbox/inbox。
- 全域实体清单补齐法人、组织、变型、组合、批次/序列号、拆单、在途、盘点、退款与支付核销；首批身份/商品实体细化字段与约束。
- 从租户权限+商品开始，随后库存与手工订单闭环，再接外部平台和财务。

## 关键 trade-off / 讨论

遵从用户微服务方向，以较粗一致性边界控制分布式复杂度。库存账与实物作业留在一个服务；售后业务和资金执行分离。保留旧草稿和 ADR 原文，入口明确标注失效与替代关系。数据模型是可评审基线，尚非可执行 DDL；品类、首个平台/仓型、成本法和财务目标仍需业务收敛。

## 涉及文件

- AGENTS.md
- docs/decisions/0004-java-spring-boot-microservices.md、docs/decisions/README.md
- docs/architecture/overview.md
- db/schemas/v2/README.md、db/schemas/v2/01-foundation.md
- db/schemas/README.md、01–09、erd.md：新增历史提示
- docs/backlog/2026-09/2026-09-27-data-model-open-questions.md：补新基线状态

## 验证

检查新文档本地链接、代码块闭合与差异；没有业务实现，未运行 lint/typecheck/后端测试或数据库迁移。工作区已有世界时钟/视觉规范等修改，保持原样，未纳入本轮工作。

## 后续待办

- 评审 D1/D2 字段后创建 identity/catalog Java 工程与各自迁移。
- 真实 PostgreSQL 验证 RLS、成员引导、复合 FK、SKU 唯一组合。
- D3 前细化仓储表物理字段及并发事务测试。
- 业务确认见 V2 第 8 节；旧 backlog 中 Kotlin/Gradle 相关项按 ADR-0004 替换。

## 关联

[架构决策](../../decisions/0004-java-spring-boot-microservices.md) · [数据模型](../../../db/schemas/v2/README.md)
