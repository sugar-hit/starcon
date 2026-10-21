# 跨时钟域断言：拿 JVM `now()` 去比 DB 的 `now()` 的测试会假红（2026-10-20 取证）

**优先级：P1。已复现两轮，已定位到具体一行。不是本批回归，但它会随机把 L2 打红。**

## 现象

batch-38 的 L2 **第一轮**后端红，只红 1 例（1133 例里）：

```
[ERROR] Tests run: 18, Failures: 1, Errors: 0 -- in com.starcon.channel.ListingPublishJobIntegrationTest
[ERROR]   ListingPublishJobIntegrationTest.dispatchedAtRoundTrips:982
Expecting actual: 2026-10-20T20:20:49.933357+08:00
to be strictly after: 2026-10-20T20:21:37.802934+08:00
```

- **隔离复跑该类：18/18 全绿**（`./mvnw -pl services/channel -am test -Dtest='ListingPublishJobIntegrationTest'`）。
- **第二轮全量 L2：六阶段全绿**，该例没再出现（后端 5955 例 / 前端 5056 例全过）。
- 本批唯一的新迁移 `V221__return_disposal.sql` 是**纯 CREATE TABLE、零 ALTER 既有表**，
  根本没碰 `listing_publish_job_target` ⇒ **不是本批引入的回归**。

## 根因

`ListingPublishJobService` 写的是：

```sql
update listing_publish_job_target
set state = 'SUBMITTED', attempts = attempts + 1, dispatched_at = now(), ...
```

**PostgreSQL 的 `now()` 是「事务开始时刻」，不是语句时刻。**

而测试拿 **JVM 的墙钟** 去比它，只留 **2 秒** 余量：

```java
OffsetDateTime before = OffsetDateTime.now().minusSeconds(2);   // JVM 墙钟
publishes.dispatch(...);                                          // 写 dispatched_at = now()（PG 事务开始时刻）
assertThat(target.dispatchedAt()).isAfter(before);                // 跨时钟域比较
```

全量跑时，这个 UPDATE 所在的事务已经开了 **~50 秒** ⇒ DB 侧时刻比 JVM 侧早 50 秒 ⇒ **断言必假红**；
隔离复跑时事务只开了 8 秒 ⇒ 通过。**绿红取决于同一套代码跑在全量还是单类**，与被测逻辑无关。

**已排除的解释**：容器与宿主时钟实测只差 ~1 秒（临时起 `postgres:16-alpine` 对拍 `date +%s`），
不是 Docker Desktop VM 的持续漂移。

## 正确写法

- 基准必须**取自 DB 自己的 `now()`**，不要拿 JVM 的 `OffsetDateTime.now()` 去比 DB 写回的值；
  或者把余量放宽到**分钟级**。
- 同理适用于任何「DB 写回的时刻 vs 应用层读到的时刻 vs 进程墙钟」的三方比较。

## 处置

**本批没有夹带修它** —— 缺陷属**已交付模块 4.3.1.1**（刊登一键发布 / 发布任务），
批次里改别的模块的测试属于「重写别人的模块」。
⇒ **下一轮单独派一个修复车道**：只改
`backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java`
里 `dispatchedAtRoundTrips` 的基准（外加全仓扫一遍还有没有同形状的断言），
**不许**改业务代码、不许改断言的**意图**（仍要断言写回的值与库里一致）。

## 证据

- 首轮 L2：`.tmp/l2-batch38.log`（1/1133，唯一失败例）。
- 隔离复跑：18/18 绿。
- 写路径：`ListingPublishJobService.java:876` 与 `:1085`（`dispatched_at = now()`）。
- 断言位置：`ListingPublishJobIntegrationTest.java:982`。
- 时钟对拍：容器 `date +%s` 与宿主差 ~1 秒 ⇒ 排除容器漂移。
