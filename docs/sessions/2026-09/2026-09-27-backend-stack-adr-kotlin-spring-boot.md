# 2026-09-27: 服务端起点 — 冻结后端技术栈 ADR

## 摘要

用户问「服务端从哪里开始做起」。扫描发现后端**一行代码都没有**，且 `docs/investment/` 里两份选型调研结论相反。用户拍定 **Kotlin + Spring Boot 3** 与 **首个平台 Shopify**。本轮把结论固化为 ADR-0002 / ADR-0003，并消除仓库内与该结论冲突的文档。**本轮未写后端代码。**

## 扫描到的现状

- `apps/api`、`apps/worker`、`apps/scheduler`、`apps/notification-gateway` 只有 `.gitkeep`；`apps/api` 连 `package.json` 都没有
- `packages/{database,tenancy,auth,money,errors,event-bus,logger,observability,i18n}` 全空
- `db/{migrations,schemas,queries,seeds}` 全空
- 已完成的只有前端：`apps/web` 19 个页面 + 设计体系；近一周 commit 全是 UI
- `deploy/docker/docker-compose.dev.yml` 已有 postgres:16-alpine，可直接起本地库

## 发现的三处仓库不一致（本轮已修）

1. `docs/decisions/README.md` 把 `0002-multi-tenancy-strategy.md` 标为 Accepted，但**该文件不存在**
2. `docs/architecture/multi-tenancy.md` 又把同一个 ADR 写成 `0006`，编号自相矛盾
3. `AGENTS.md` 写「全栈 TS」，与 Kotlin 决策直接冲突 —— 而 AGENTS.md 会覆盖所有 agent 的默认行为，不改就等于 ADR 架空

## 决策

- **ADR-0002 多租户**：Shared Schema + RLS。把已声明但缺失的文件落盘，并编号为 0002（不是 0003）
- **ADR-0003 后端运行时**：Kotlin 2.x + Spring Boot 3.x + Gradle + **JDK 21 工具链** + PostgreSQL 16 + **jOOQ** + **Flyway** + JDBC + REST/OpenAPI + PostgreSQL outbox + Testcontainers + Micrometer/OTel
- 构建根定为 `backend/`，与 pnpm/turbo 并列的独立构建
- 首批平台连接器：Shopify
- 明确第一期**不引入** Kafka / ClickHouse / service mesh / Redis 队列 / 多集群

## 关键 trade-off

- **jOOQ 而非 JPA/Hibernate**：库存与结算的正确性依赖「这段 SQL 到底做了什么」可逐行审阅。Hibernate 的隐式 flush 与懒加载会让关键扣减不可预测，而本项目最不能出错的就是这部分。jOOQ 用生成代码换编译期类型检查，同时保留 SQL 可见性。
- **JDBC 而非 R2DBC**：RLS 的 `SET LOCAL` 事务级租户上下文（ADR-0002 的核心风险点）与 jOOQ 都以 JDBC 为第一公民。
- **JDK 21 而非本机 25**：本机只有 Oracle JDK 25，Gradle toolchain 固定到 21 LTS 以保证构建可复现，也避免 Spring Boot 3.x 对 JDK 25 的支持不确定性。
- **两处调研不删，只标状态**：`2026-09-27` 那份的「服务端语言」章节作废，但主数据归属表、一致性 7 条、纵向闭环顺序、失败路径验收清单**继续有效**，没有丢信息。
- **修正 ADR-0001 的乐观措辞**：它写「拆微服务时只改 deployment 拓扑，代码结构几乎不动」，这不成立 —— 拆分还涉及事务边界、数据迁移、失败恢复与接口版本。ADR 不可改写，故在 0003 中记录该修正，0001 状态标为「部分措辞由 0003 修正」。

## 涉及文件

- `docs/decisions/0002-multi-tenancy-strategy.md`（新建）
- `docs/decisions/0003-backend-runtime-kotlin-spring-boot.md`（新建）
- `docs/decisions/README.md`（索引 + Pending 清单）
- `docs/architecture/multi-tenancy.md`（ADR 编号 0006→0002；TS 中间件示例换成 Kotlin；自检清单去掉 Kafka）
- `docs/investment/2026-09-26-backend-language-research.md`（标为 ADR-0003 依据）
- `docs/investment/2026-09-27-backend-and-master-data.md`（语言章节标作废，其余保留）
- `AGENTS.md`（拆成前端 TS / 后端 Kotlin 双栈；补后端命令、目录映射、RLS 三条断言、codegen 产物只读纪律）
- 本会话日志

## 验证

- 未跑 `pnpm lint/typecheck/test`：本轮只改 Markdown，`prettier --check` 覆盖 md，改动已按现有格式书写
- 环境已确认（下一轮要用）：JDK 25.0.2 可用但 Gradle 未安装、`JAVA_HOME` 为空；`services.gradle.org` / Maven Central / gradle-wrapper.jar 均 200 可达；Docker daemon 运行中（29.7.2），Testcontainers 可用

## 后续待办

按顺序，都是完整交付、不留 TODO：

1. **物理重构**（独立 atomic commit）：新建 `backend/` Gradle 构建根；移除 `apps/{api,worker,scheduler,notification-gateway}` 与 `packages/{database,tenancy,auth,money,errors,event-bus,logger,observability,i18n}` 的空占位，避免「后端在哪」出现双份真相
2. **后端底座**：`backend/apps/api` + `backend/apps/worker` 可构建可跑，`backend/libs/tenancy` 落地，用 **Testcontainers 真跑通 ADR-0002 的三条隔离断言**（两租户互不可见、伪造 tenant header 无效、连接池不串租户）
3. **主数据**：SKU / 店铺 / 仓库真实持久化 + 导入，验证重复编码、外部映射冲突、跨租户外键拦截，再让前端读真实列表
4. **第一条纵向闭环**：Shopify 订单 → SKU 映射 → 原子预占 → 出库 → 库存扣减 → 待结算记录。无真实物流接口时明确标记人工出库，**不伪造回传成功**
5. **失败路径先验收**：重复 webhook 不重复扣库、并发最后一件只成一单、取消能释放、事务回滚不丢库存、worker 重启可续跑、乱序事件不回退状态、历史金额不随汇率变化
6. **扩第二个平台**，检验统一模型是否成立

## 关联

- [ADR-0002 多租户](../../decisions/0002-multi-tenancy-strategy.md)
- [ADR-0003 后端运行时](../../decisions/0003-backend-runtime-kotlin-spring-boot.md)
- [ADR-0001 Monorepo + Modular Monolith](../../decisions/0001-monorepo-and-modular-monolith.md)
- [主数据与一致性调研](../../investment/2026-09-27-backend-and-master-data.md)
- [后端语言选型调研](../../investment/2026-09-26-backend-language-research.md)
