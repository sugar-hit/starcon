# 2026-10-21: 4.3.1.1 派发时刻断言的跨时钟域假红

## 摘要

修能力蓝图模块 **4.3.1.1「一键刊登」**（channel 服务，阶段 D5，L3 锚点 4.3.1，**已交付模块的缺陷修复**）
里一条跨时钟域的测试断言假红：`ListingPublishJobIntegrationTest#dispatchedAtRoundTrips`
拿 **JVM 墙钟** `OffsetDateTime.now().minusSeconds(2)` 去比服务层写的 `dispatched_at = now()`。
PG 的 `now()` 是**事务开始时刻**、不是语句时刻 ⇒ 两者是**跨时钟域**比较，且只留 2 秒余量 ⇒
事务一开久（或 DB 与 JVM 时钟本身有偏斜）就必假红。它在 2026-10-20 batch-38 的 L2 第一轮
（1133 例里红 1 例）已经赔掉过整整一轮，隔离复跑却 18/18 全绿。

**只改这一条断言的基准**：换成**同源 DB 时钟**，并补一条把两个必翻车现场都造出来的确定性证明。
**服务层业务逻辑一字未改**（`dispatched_at = now()` 才是正确写法），**无新迁移**，**契约零改动**。

## 决策 / 结论

- **新基准 = 同源 DB 时钟，两个界**：
  1. **下界**取「写入 `dispatched_at` 的那一次 UPDATE 的事务时刻」——
     同一条 UPDATE 里 `dispatched_at` 与 `updated_at` 是同一个 `now()` 的两次落笔
     ⇒ `updated_at` 就是那次事务的时刻（同一次写入、同一个事务 ⇒ 同源）。
  2. **上界**取 DB 自己的 `clock_timestamp()`（**语句时刻**，不是事务开始时刻）。
  ⇒ 断言 `dispatched_at` 的时刻等于那次事务的时刻、且不晚于 DB 当前时刻。
  **全程不碰 JVM 墙钟、不猜余量**：事务开多久、两个时钟偏多少，都不影响判定。
- **确定性证明（新增用例 `dispatchedAtBaselineIsDbSourced`）**：两个现场都**真实构造**出来，
  旧写法必须红、新写法必须绿：
  1. **老事务现场** —— 用**真实服务** + 外层事务（`TransactionTemplate`，REQUIRED ⇒ 会并进
     `access.run` 的事务）把派发塞进一次**已经开了 3 秒**的事务里 ⇒ `now()` 固定在事务开始
     时刻 ⇒ 写进去的时刻比「现在」老 3 秒 ⇒ 旧的 2 秒余量必然不够。
  2. **可控的跨时钟域倒挂** —— 显式构造「JVM 墙钟比 DB 时钟快 50 秒」= 全量跑时打红的形状。
  两个现场里旧写法都用 `assertThatThrownBy` **钉死为红**（不许有人把旧写法搬回来）。
- **真回归仍然抓得住**：临时把写入改成 `now() - interval '10 minutes'` ⇒ 新断言立即红
  （`dispatchedAtRoundTrips` 与新用例双双 FAIL），临时改动已全部回滚。

## 关键 trade-off / 讨论

- **否决「把 2 秒余量放宽到分钟级」**：那是**跨时钟域比较**的补丁 —— 只要 DB 与 JVM 的时钟
  偏出那个余量，假红照样复发（batch-38 那次两台机器差了 ~48 秒）。同源时钟才是根治。
- **否决「读一次派发前的 `clock_timestamp()` 当下界」**：它是 DB 时钟，但对**事务年龄**不免疫 ——
  若派发所在事务开始于读参照之前，写进去的时刻就早于参照 ⇒ 新断言反而会假红。
  下界必须取「**那次事务自己的时刻**」，才能同时免疫时钟偏斜与事务年龄。
- **否决「把断言改成永远成立 / 直接删掉」**：那会让真回归（写回时刻写错、写早、写未来）漏过去。
  新断言仍然能抓住：`dispatched_at` 与那次事务的时刻不等、或晚于 DB 当前时刻，都会红。
- **「同一次写入的事务时刻」取 `updated_at` 而不是作业行的 `dispatched_at`**：前者是**同一条
  UPDATE 里同一个 `now()` 的两次落笔**（同表同行 ⇒ 同源、无跨表耦合）；后者隔着一次
  `coalesce(dispatched_at, now())` 语义，重试路径下含义会漂。
- **老的 JVM 基准整条删掉，不留「分钟级」兜底**：留着就是留着一个跨域比较的雷。

## 涉及文件

- `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java`
  （改：`dispatchedAtRoundTrips` 的基准；新增 `dispatchedAtBaselineIsDbSourced`；
  新增辅助 `dbTimestamp(String)`；`@Autowired PlatformTransactionManager`）
- `docs/product/capability-blueprint/module-progress.md`（只改 4.3.1.1 那一行的「证据」格，追加本次修复）
- `docs/sessions/2026-10/2026-10-21-listing-publish-clock-assert-fix.md`（本文件）

**零改动**：`ListingPublishJobService`（业务逻辑）、任何 Flyway 迁移、`packages/api-contracts`、
任何前端文件。

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ⇒ 成功。
- **L1 模块闸**：`./mvnw -pl services/channel -am test -Dtest='ListingPublishJobIntegrationTest'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ⇒
  **19 例全绿**（原 18 + 新增 1），0 failure / 0 error。
- **手工制造矛盾**：临时把 `dispatched_at` 写成 `now() - interval '10 minutes'` ⇒
  `dispatchedAtRoundTrips` 与 `dispatchedAtBaselineIsDbSourced` **双双 FAIL**；
  临时改动已 `git checkout` 回滚，`git diff` 只剩目标测试文件与台账行。
- `pnpm typecheck` ⇒ 3 包全绿（本车道未动 TS）。

## 后续待办

- **不属本车道**：batch-38 记录里那句「凡是断言「DB `now()` vs JVM `now()`」的测试都有复发风险」
  仍然成立 —— 全仓还有别的模块用 JVM 墙钟比 DB 时刻的写法（本次只修 4.3.1.1 这一条，
  超出本车道范围，未动）。
- 建议后续单开一条车道扫一遍 `backend/services/**/src/test/**` 里的
  `OffsetDateTime.now()` / `Instant.now()` 与 DB 时刻的跨域比较，统一换成同源基准。

## 关联

- `docs/sessions/2026-10/2026-10-20-one-click-publish.md`（4.3.1.1 原始交付）
- `docs/sessions/2026-10/2026-10-20-batch-38-return-disposal-delivery.md`
  （本缺陷的取证与「下轮单独派一个修复车道」的结论）
- `docs/product/capability-blueprint/04-listing-and-content.md` §4.3.1.1（蓝图原文）
- `docs/product/capability-blueprint/module-progress.md`（台账 4.3.1.1 行）
