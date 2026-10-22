# Testcontainers PG 容器在单次 `mvnw verify` 内累积不回收

发现：2026-10-22 05:00 批次（batch-60）的 L2 批量验证期间。
状态：待排期（跨模块，不阻塞流水线）。

## 现象

一次集成头全量 `./mvnw verify`（单 maven session）期间，带
`org.testcontainers.sessionId` 标签的 `postgres:16-alpine` 容器数**单调增长到 62+**，
创建间隔约 5 秒，全部挂在**同一个 session id** 下。
`docker stats` 汇总容器内存约 **5.4 GiB**，Docker VM 进程 RSS 约 **3.0 GiB**；
机器 swap 使用 4.6 GiB / 5 GiB。

Ryuk 只在 **JVM 退出时**按 session 标签回收，所以整个 verify 跑完之前容器不会消失。

## 根因

仓库既有约定不统一：

| 形态 | 文件数 | 行为 |
| --- | --- | --- |
| 使用 `PostgreSQLContainer` | 343 | 每个集成测试类起一个容器 |
| 其中显式 `.stop()` | 246 | 容器在类结束时立即回收 |
| 其中**不** `.stop()` | **97** | 容器留到整个 maven session 结束才被 Ryuk 回收 |
| 使用 `@Testcontainers` 注解托管 | 1 | 唯一「零泄漏」写法 |

即**约 97 个集成测试类**在同一个 session 内把容器堆起来。
本批新合入的 6 个测试类（`AdTargetingIntegrationTest` / `AdTargetingTenancyIntegrationTest` /
`AdCouponIntegrationTest` / `AdCouponTenancyIntegrationTest` /
`InfluencerIntegrationTest` / `InfluencerTenancyIntegrationTest`）
都是「static 容器 + `@BeforeAll` 里 `pg.start()` + **无 `.stop()`**」形态 ——
它们**沿用了仓库既有主流写法**，不是本批引入的回归。

## 为什么值得单独记一笔

1. **PG 闸的口径会失真**。`.dev-agent/PIPELINE.local.md` 的 `PG_CONTAINER_LIMIT=6`
   是**派发前**的闸（派发时实测 0 个容器，通过）。但批内 L2 + N 条车道的 L1 并行时
   实测峰值 10+，单跑一次 L2 时可达 62+。⇒ 「PG 容器数」这个指标在批内不是闸，
   只是资源观测值，别把它当成过载判据。
2. **机器资源是被这批任务吃掉的**，不是被流水线配置吃掉的。
   Docker VM 3 GiB + 5.4 GiB 容器内存 + swap 4.6 GiB 是真实压力源。
3. **温度/负载闸看不到它**：load 采样全程 2.3–8.5（阈值 12，未命中），
   `pmset -g therm` 全程无降频项。**容器数是比 load 更灵敏的过载信号**。

## 建议处置（待人工决策，调度器不自作主张）

| 方案 | 做法 | 代价 | 风险 |
| --- | --- | --- | --- |
| A（推荐） | 给 97 个未 `.stop()` 的集成测试类补 `@AfterAll` 里 `pg.stop()`，或统一改用 `@Testcontainers` 注解托管 | 一次性改 97 个文件 | 改多了可能把「类间共享容器」的隐含依赖改坏，需逐服务验证 |
| B | 拆分 maven session：每个服务一个 session，session 结束即 Ryuk 回收 | 改 `pom.xml` 的 surefire 配置分模块跑 | verify 总时长变长（发热峰值次数变多，与组批协议的省时前提冲突） |
| C | 维持现状，但在 L2 期间加一条「容器数 > N 就提前收尾」的看门狗 | 只加观测 | 不能解决内存压力，只是早发现 |
| D | 调小 `PG_CONTAINER_LIMIT` | 一行配置 | ❌ **无效**：闸是派发前的，批内根本不起作用，改了只会误伤派发决策 |

⚠️ **不要用 D 来「解决」这个问题** —— 那会让人误以为限住了资源，实际只是把派发批次砍小，
而资源峰值发生在批内（此时闸已经放行了）。

## 取证

- 观测命令：`docker ps --filter 'label=org.testcontainers.sessionId' --format '{{.CreatedAt}} {{.Label "org.testcontainers.sessionId"}}'`
- 计数口径：**只数带 `org.testcontainers.sessionId` 标签的**。
  本机唯一无标签的 postgres 容器是 `langfuse-postgres-1`（postgres:15-alpine，2026-04-18 起），
  属用户常驻应用，**不是孤儿容器，不要清理**。
- 相关既有记录：`docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`
  （同一套 Testcontainers 基础设施的另一类问题）。
