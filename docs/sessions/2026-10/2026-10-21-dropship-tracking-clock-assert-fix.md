# 2026-10-21: 9.6.1.3 代发跟踪「承诺时限」断言的偶发假红

## 摘要

修能力蓝图模块 **9.6.1.3「代发履约跟踪与异常」**（trade 服务，阶段 D4，**已交付模块的假红修复**）里
一条测试断言的**偶发假红**：`DropshipTrackingIntegrationTest#建跟踪时两段各自独立_互不预填`
把 `at(2)` **调了两次** —— 一次写进库、一次当断言基准，两次之间隔着一次 DB 往返。

`at()` 每次都重读 JVM 墙钟并**截断到整秒** ⇒ 只要这两次跨过一个整秒边界，写进库的是 `T`、
断言用的是 `T+1s` ⇒ 断言差 1 秒。2026-10-21 18:43 集成头跑 `cd backend && ./mvnw verify`
全量 2829 例时，trade 模块 2132 例里红了**这一条**；隔离复跑该测试类 3 次 29 例全绿 ⇒ 偶发假红，
不是稳定缺陷。

**只改测试**：业务代码零改动、无新 Flyway 迁移、`packages/api-contracts` 未动、前端零改动。

## 根因

「写进去的值」与「拿来断言的值」**必须取自同一次时钟读取**，否则两次读取之间只要跨过整秒边界就差 1 秒。
本仓库既有的正确写法就在同一个文件里（`时间往返不发生静默偏移`）：先把时刻存进局部变量，建单与断言都用它。
出事的那处没这么做，写与断言各取了一次时钟。

## 决策 / 结论

- **修法：只取一次时钟**。`建跟踪时两段各自独立_互不预填` 改走新辅助方法
  `建跟踪并回读承诺时限(tenant, fixture)`：方法内部 `OffsetDateTime promised = at(2);` 取**一次**，
  建单用它、断言也用它（照 `时间往返不发生静默偏移` 的既有写法，不另起风格）。
  **辅助方法与回归用例共用** ⇒ 谁把「取两次时钟」的写法搬回来，回归用例在钉死的时钟下立刻红。
- **确定性证明（新增用例 `承诺时限只取一次时钟_跨整秒边界现场下依然成立`）**：
  - 把测试时钟抽成可钉死的 `clockSource`（默认仍读 JVM 墙钟，`at()` / `hoursAgo()` 都走它，
    用 `@AfterEach` 还原）；默认路径的取值与改造前**逐字等价**。
  - 用例把时钟钉死成「**每次读取整整晚 1 秒、且永远停在整秒边界前 1ns**」⇒ **任意两次相邻读取**
    截断到秒之后必然差**整 1 秒**（就是 `25.999 → 26.001` 那种必翻车的现场），且与「两次调用之间
    隔了多久」无关 ⇒ **确定性，不靠运气**。
  - 现场里：**旧写法必须红**（`assertThatThrownBy` 钉死）、**新写法必须绿**（写回库里的正是那唯一一个
    被取到的时刻 —— 断言值 == 钉死时钟的第一次读取）。
  - **变异验证**：把辅助方法改回「取两次时钟」⇒ 回归用例立刻红，失败形状与 L2 那次一致
    （`expected` 比 `was` 晚整整几秒）。

## 关键 trade-off / 讨论

- **否决「把断言改成容差比较 / 放宽到 ±N 秒」**：那是**掩盖**竞态而不是消除它 —— 时钟漂移一大就照样假红，
  而且真回归（写回值写错）会被容差吞掉。取一次时钟才是根治。
- **否决「在断言前 sleep 或重试」**：靠运气等它不跨边界，本质上仍是假红。
- **否决「把 `at()` 改成不截断到秒」**：截断到秒是本仓既有口径（落库列本身是 `timestamptz`，秒级足够），
  改它会牵连其余 34 个调用点的语义，超出本车道范围。
- **「钉死时钟」做成 `Supplier` 而不是注入 `Clock` Bean**：本模块业务侧一律用 `OffsetDateTime.now()`
  直读墙钟（未注入 `Clock`），**业务代码一行不动**是本车道的硬约束；测试侧自成一个时钟源即可造出
  现场，不需要动生产代码的取时方式。
- **回归用例用 `seedTenant2` 另起一张单跑「旧写法」**：`createTracking` 对同一张代发单是幂等的
  （`UNIQUE(tenant_id, order_id)`，已存在就直接返回既有行），两张单才跑得出「两次取时钟」的真实形状。

## 独立核对结论（本车道做的第二遍核对）

- 本文件全部 `at(...)` / `hoursAgo(...)` 调用点逐条过了一遍：**「写入值」与「断言值」各取一次时钟的
  只有被修的那一对**；其余的要么只取一次、要么本来就取到局部变量（`时间往返不发生静默偏移`）、
  要么成对传入的是**不同语义的时刻**（如 `hoursAgo(3), hoursAgo(2)` = 发货时刻 vs 最后事件时刻）。
  ⇒ **本文件内没有第二处同类竞态。**
- **⚠️ 但在别的模块发现了同一形状的第二处**（**不在本车道范围，未改**）：
  `backend/services/trade/src/test/java/com/starcon/trade/DropshipConfigIntegrationTest.java`
  第 944/945 行写入 `at(-3)` / `at(30)`，第 955/957 行又各取一次时钟来断言 ⇒ **同一形状的偶发假红**。
  归属模块 9.6.1.1，建议调度者单开一条车道修。

## 涉及文件

- `backend/services/trade/src/test/java/com/starcon/trade/DropshipTrackingIntegrationTest.java`
  （改：`建跟踪时两段各自独立_互不预填` 的承诺时限断言；新增辅助 `建跟踪并回读承诺时限`；
  新增可钉死的 `clockSource` / `WALL_CLOCK` / `now()` 与 `@AfterEach restoreClockSource`；
  新增回归用例 `承诺时限只取一次时钟_跨整秒边界现场下依然成立`；类注释补「时刻基准同源」一条）
- `docs/product/capability-blueprint/module-progress.md`
  （**只改 9.6.1.3 那一行的「证据」格**（在末尾追加本次修复说明），「状态」格保持 `已交付` 不变，
  未重排整表；证据格内分隔符用**全角 `｜`**，不引入 ASCII 半角 `|`）
- `docs/sessions/2026-10/2026-10-21-dropship-tracking-clock-assert-fix.md`（本文件）

**零改动**：`DropshipTrackingService` 等任何业务代码、任何 Flyway 迁移、`packages/api-contracts`、
任何前端文件。

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ⇒ 成功（退出码 0，仅 JDK 自身告警）。
- **L0 类型闸**：`pnpm typecheck` ⇒ 3/3 turbo tasks 成功（本车道未动任何 TS）。
- **L1 模块闸**：`./mvnw -pl services/trade -am test -Dtest='DropshipTrackingIntegrationTest'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ **30 例全绿**（原 29 + 新增 1），0 failure / 0 error，BUILD SUCCESS。
- **确定性连跑 5 轮**：每轮 30/30 全绿（10.21s / 10.01s / 9.95s / 9.89s / 9.96s）⇒ 新回归用例是确定性的。
- **变异验证**：把辅助方法改回「写入取一次时钟、断言再取一次时钟」⇒
  `承诺时限只取一次时钟_跨整秒边界现场下依然成立` 立刻 FAIL（`expected` 比 `was` 晚整秒），
  与 L2 那次的失败形状一致；变异已回滚（`git diff` 只剩目标测试文件、台账行、本会话文档）。
- **容器**：verify 期间同时存活的 testcontainers PG 容器峰值 = **1**（`postgres:16-alpine`）；
  跑完后 `docker ps --filter "label=org.testcontainers.sessionId"` = **0**，
  `docker ps -a --filter "ancestor=postgres:16-alpine"` 为空 ⇒ 已被 ryuk 回收干净。

## 后续待办

- **建议调度者单开一条车道**修 `DropshipConfigIntegrationTest`（9.6.1.1）里同一形状的双取时钟断言
  （第 944/945 行写、第 955/957 行断言）—— 本车道未越界修改。
- 本仓还散落着「JVM 墙钟 vs DB 时刻」的**跨时钟域**比较（例如 `DropshipMarginIntegrationTest`
  里 120 秒容差的时刻比较）：与本次「同一个值取两次」不是同一形状，但同属时间基准脆弱面，
  可在后续专项车道统一收敛（同源 DB 时钟）。

## 关联

- `docs/product/capability-blueprint/09-order-and-fulfillment.md` §9.6.1.3（蓝图原文）
- `docs/product/capability-blueprint/module-progress.md`（台账 9.6.1.3 行）
- `docs/sessions/2026-10/2026-10-21-listing-publish-clock-assert-fix.md`
  （同类假红的先例：4.3.1.1 派发时刻断言改用同源 DB 时钟）
