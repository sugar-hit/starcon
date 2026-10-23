# 2026-10-24: channel 锁预算导致的 teardown 崩溃修复

## 摘要

16.3.2.3「销售税与 marketplace facilitator」批次交付后，集成头跑全量 `mvnw verify` 时 channel 服务
唯一一条用例报错：`OAuthAuthorizationIntegrationTest.stop:197 » PSQL ERROR: out of shared memory`
（`Tests run: 2267, Failures: 0, Errors: 1`）。根因是该测试 `@AfterAll` 里的一句
`drop schema public cascade` 需要在**一个事务**里拿下 channel schema 的每一个对象，而 V352 新增的
27 个对象恰好把它推过了 PostgreSQL 锁表上限。本车道删除这句**本来就多余**的语句并补上根因注释，
未改动任何迁移、端点或其它模块。

## 决策 / 结论

1. **🔴 症状发生在 `@AfterAll` 清理阶段，不是业务断言失败。**
   `Failures: 0`，唯一的 `Errors: 1` 是 teardown 里的 `drop schema public cascade`。
2. **🔴 根因是锁表预算，不是语法 bug。**
   PostgreSQL 锁表大小 = `max_locks_per_transaction (64) × max_connections (100)` = **6400** 个锁位；
   `DROP SCHEMA ... CASCADE` 必须在**一个事务**里对 schema 里**每一个对象**各拿一个锁。
   channel 现状（`backend/services/channel/src/main/resources/db/migration/` 共 89 个迁移，实测统计）：
   **380 表 + 859 索引 + 371 触发器 + 16 序列 ≈ 1600 个对象**，加上测试自己起的 app 连接持有的锁已逼近上限。
   本批 V352（`V352__channel_sales_tax.sql`）新增 **6 表 + 11 索引 + 10 触发器 = 27 个对象**，恰好越线。
3. **🔴 正确的修法是「删掉多余的操作」，不是调大 `max_locks_per_transaction`。**
   上调阈值只是把临界点往后推 —— schema 每加一个迁移就更大一分，很快会再撞；
   更糟的是它掩盖了「这条语句本来就多余」这个事实。
4. **🔴 不能改成等价的重置方式（逐表 drop / `TRUNCATE` / 其它「一个事务里拿下整个 schema」的写法）。**
   病根是「一个事务里拿下整个 schema」这个**动作**本身，不是 `drop` 的语法。
5. **🔴 这句 drop 的动机（清干净 schema）由「容器销毁」自动满足。**
   `pg.stop()` 直接销毁容器，schema 里的东西随之消失；且这个容器是本测试类私有的，
   全仓库没有任何其它测试引用本类 ⇒ 不存在「别人依赖这个 schema 被清干净」。
6. **🔴 原来的顺序还是坏顺序。**
   drop 发生在 `oauth.close()` / `identity.stop()` **之前**；此刻若还有 in-flight 请求打在 channel app 上，
   会撞上「schema 已被删」而报错。先停服务比先删 schema 安全。
7. **⚠️ 遗留：`drop schema public cascade` 这类「一把 drop 整个 schema」的语句，今后任何测试都不要写。**
   channel 每加一个迁移都会让锁预算更紧张。

## 取证结论（本地复核）

| 待核事实 | 复核方式 | 结论 |
| --- | --- | --- |
| 容器私有、无其它测试依赖 | `grep -rn OAuthAuthorizationIntegrationTest`（全仓） | 仅命中本文件自身（类声明 + 自引用）+ 一条历史会话语档 ⇒ 成立 |
| 容器紧接着被销毁 | 读 `stop()` 方法体 | `oauth.close(); identity.stop(0); pg.stop();` 紧跟其后 ⇒ 成立 |
| 全仓库只有这一处 drop schema | `grep -rni 'drop\s+schema'`（`backend/`） | 唯一命中 `OAuthAuthorizationIntegrationTest.java:197` ⇒ 成立 |
| V352 的对象增量 | 统计 `V352__channel_sales_tax.sql` | 6 表 + 11 索引 + 10 触发器 + 0 序列 = 27 ⇒ 与调度者取证一致 |
| channel schema 总量 | 统计全部 89 个迁移 | 380 表 + 859 索引 + 371 触发器 + 16 序列 ≈ 1626 ⇒ 与「≈1600」一致 |

## 涉及文件

- `backend/services/channel/src/test/java/com/starcon/channel/OAuthAuthorizationIntegrationTest.java`
  —— `@AfterAll stop()`：删除 `drop schema public cascade; create schema public;` 及其专属的
  try-with-resources 块，补一段写明「为什么原来要 drop / 为什么现在不需要 / 为什么不能换别的重置方式 /
  每加一个迁移数字就更大 ⇒ 不要再写这类语句」的 javadoc。
- `docs/sessions/2026-10/2026-10-24-channel-lock-budget-teardown-fix.md`（本文件）

**未改动**：任何迁移（含 V352）、端点、前端页面、台账、其它模块的代码与测试。

## 关键 trade-off / 讨论

- **为什么不留一句「清理」语句占位？** 占位也会让人以为「这里需要清理」，下一个人很可能顺手加回来。
  注释直接说明「不需要」+「为什么不需要」+「为什么换成别的写法也不行」，比留一个空壳更能防复发。
- **为什么不顺手把 `ListingPublishJobTenancyIntegrationTest` 的 spotless 违规一起修？**
  那是 412a38d1（4.3.1.1 一键刊登）带入的**既有**违规，不在本车道范围内（本车道只碰一个测试文件）。
  已上报调度者，见「后续待办」。

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q compile                     # → exit 0

# L1 模块闸（只跑本测试类）
cd backend && ./mvnw -pl services/channel -am test \
  -Dtest='OAuthAuthorizationIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# → Tests run: 23, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```

补充：`./mvnw -B -ntp -pl services/channel spotless:check`（需 JDK 21）确认本文件**无格式违规**。
日志里 `out of shared memory` 出现次数 = **0**。

## 后续待办

- **⚠️ 既有 spotless 违规（非本车道引入）**：`backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobTenancyIntegrationTest.java`
  在 `spotless:check`（google-java-format 1.22.0）下不合规 ⇒ CI 的 `./mvnw -B -ntp verify spotless:check` 会红。
  需在集成头修掉（一行 javadoc 换行重排即可）。
- **⚠️ 本地 JDK 25 会让 spotless 崩**：`google-java-format 1.22.0` 与 JDK 25 的 javac 内部 API 不兼容
  （`NoSuchMethodError: Log$DeferredDiagnosticHandler.getDiagnostics()`）。本地跑 spotless 需 `JAVA_HOME` 指向 JDK 21
  （CI 用 temurin 21）。这不影响 `mvnw verify` 本身。

## 关联

- `docs/sessions/2026-10/2026-10-24-sales-tax-marketplace-facilitator.md`（本批次 16.3.2.3 交付）
- `docs/decisions/0004-java-spring-boot-microservices.md`（Java 21 工具链、Flyway 迁移规范）
- 蓝图：`docs/product/capability-blueprint/16-compliance-and-risk.md` §16.3.2.3
