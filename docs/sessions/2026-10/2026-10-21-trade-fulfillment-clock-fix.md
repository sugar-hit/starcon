# 2026-10-21 9.3.3.1 履约请求：跨时钟域缺陷修复

## 决策摘要

修 9.3.3.1「履约请求」（trade 服务，阶段 D4）的一个**跨时钟域**缺陷：
同一行的两个时间戳此前来自**两个不同的时钟源**，表级约束拿它们比大小，
一次**完全合法**的「履约步完成 / 预占回执」会被 DB **随机拒掉**，流程卡在 RUNNING / RESERVING。

- 根因：服务层用 **JVM 墙钟**落「请求侧」时刻（`requested_at` / `deadline_at` / `last_step_at` /
  step 的 `started_at` / 事件的 `occurred_at`），V152 第 10 节的窄函数用 **DB 时钟**
  落「转移侧」时刻（`finished_at` / `acknowledged_at` / `completed_at` / `blocked_at` …）。
  ⇒ `fulfillment_request_step` 的 `CHECK (finished_at >= started_at)` 在 JVM 比 DB 快
  几十微秒时就违约。实测失败行（2026-10-20 23:29）：`started_at=23:29:54.130301` vs
  `finished_at=23:29:54.130226`（JVM 快 75µs）。
- 修法（**路线 A：两个时间戳都取 DB 时钟**）：本模块**落库的每一个时间戳都取 DB 时钟**
  （`clock_timestamp()`），JVM 墙钟一个都不落库；窄函数 `complete_fulfillment_step` 改成
  4 参并**自己**算 `elapsed_seconds`。
- 那条 CHECK **原样保留、没放宽也没删**：它防的是「结束早于开始」这个真错误。

## 关键 trade-off

1. **选 A（都取 DB 时钟），不选 B（都取 JVM 时钟）。**
   - 这一步的「开始」与「完成」是**两次独立的 HTTP 请求**（发起预占 → 收到 warehouse 回执），
     多副本部署里可能落在**不同实例**上。B 只是把「JVM vs DB」的比较换成「主机 A vs 主机 B」
     —— 失败率变小，**缺陷类别没消失**（NTP 同步在毫秒级，主机间差几毫秒就足以让完成早于开始）。
     DB 时钟是所有实例**共享**的那一个时钟，取它才是结构性修好。
   - 同模块的 `fulfillment_request_work_order` 就是这么写的：它的 `started_at` 与 `finished_at`
     **都由 DB 窄函数取 `clock_timestamp()`**，并同样挂着一条 `finished_at >= started_at` 的 CHECK。
     step 表是唯一的例外。
   - 本模块的「状态转移时刻」本来就都是 DB 时钟（`last_step_at` / `completed_at` / `blocked_at` /
     `acknowledged_at` / 作业的 started+finished），服务层写的那几列是唯一的另一套时钟。
2. **不只修 step 表，把整个模块的落库时间戳收口到 DB 时钟。**
   实测发现**同一个根因在本模块还有 6 处**：`_reservation` 的
   `CHECK (acknowledged_at >= requested_at)` 与 `released_at`、`_outbound_order` 的
   `acknowledged_at`、头行的 `completed_at` / `cancelled_at` / `blocked_at`。
   只修 step 表 = 半成品：预占回执仍会被随机拒掉，流程照样卡在 RESERVING
   （第一次跑回归用例时就是这个失败：JVM 快 5 秒 ⇒ 预占回执被
   `fulfillment_request_reservation_check5` 拒掉）。
   全部在 **9.3.3.1 自己的表**上，零跨模块改动。
3. **不放宽 CHECK，也不夹逼 `finished_at`。**
   `finished_at = greatest(clock_timestamp(), started_at)` 会让 CHECK 变成永真 ——
   一个被写坏的 `started_at` 会被悄悄抹平，约束再也拦不住任何东西。约束必须继续有牙齿：
   负耗时由窄函数明确报错（23514），手工塞的「结束早于开始」行仍被表级约束拒掉。
4. **`elapsed_seconds` 交给 DB 算。**
   旧实现在 JVM 侧用 `Duration.between(started, finished)` 算，时钟倒挂时算出**负数**。
   现在由 DB 用**库里存着的那两个值**算：`round(extract(epoch from (finished_at - started_at))::numeric, 3)`
   ⇒ 三个字段在构造上就自洽，且不会为负。
5. **「到没到点」的判定也挪到 DB 时钟。**
   `overdue` 列（SQL 里算）、扫描报告的「超时秒数」（`extract(epoch from (clock_timestamp() - deadline_at))`）、
   `overdueOnly` 过滤（`clock_timestamp()` 而非 `now()`，后者是事务开始时刻、长事务里会过期）
   —— deadline_at 既然是 DB 时钟写的，就该用同一个时钟去比。
6. **注入 `Clock` 作为测试把手（复用 trade 服务已有的那一个 Clock Bean，不新增第二个）。**
   新增第二个同类型 Bean 会让按类型注入变成歧义，整个 trade 服务起不来。
   回归测试靠它把 **JVM 时钟整体前移 5 秒**，确定性地制造「JVM 比 DB 快」的现场 ——
   真实机器上两者只差几十微秒，没有这根把手就钉不住。
   建单回执里的时刻改为**读回库里写下的那一份**（`insert ... returning requested_at, deadline_at`），
   保证「回执说建单于 T」与「库里就是 T」是同一件事。

## 涉及文件

- `backend/services/trade/src/main/resources/db/migration/V211__trade_fulfillment_step_clock.sql`
  （**新建**：`DROP` + `CREATE` 本模块自己那一个窄函数 `complete_fulfillment_step`（4 参）+
  重新授权；**零 ALTER 任何既有表、零新建表** ⇒ 跨车道 DDL 回归面 = 0）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRequestService.java`
  （服务层不再从 JVM 落任何时间戳；`Clock` 注入；overdue 判定挪到 SQL）
- `backend/services/trade/src/test/java/com/starcon/trade/FulfillmentRequestIntegrationTest.java`
  （+2 例：跨时钟域回归、约束未被放宽；`Clock`/`ZoneId`/`Duration` 导入；
  「模拟进程重启」那例的 service 构造改成两参）
- `docs/product/capability-blueprint/module-progress.md`
  （**只改 9.3.3.1 那一行的证据格**，追加本次修复说明）

## 验证

- L0：`cd backend && ./mvnw -q compile` → 绿。
- L1：`./mvnw -pl services/trade -am test -Dtest='FulfillmentRequest*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` → **35/35 绿**
  （原 33 例 + 新 2 例）；连 9.3.3.2 监控的 `FulfillmentMonitorIntegrationTest` 26 例一起绿。
- `pnpm typecheck` → 绿（3/3 turbo tasks）。
- 契约：对外 DTO / 端点**零变化** ⇒ 不重新生成 `packages/api-contracts`（那是 codegen 产物）。
- **变异验证**（证明回归用例真的有牙齿）：把 `markStepRunning` 的 `started_at` 改回 JVM 墙钟
  ⇒ 该用例立刻红；再把「落库时刻必须在 DB 时钟上」的两条断言临时放开 ⇒ 失败点变成
  DB 真的拒了这次写入：
  `ERROR: 流程步骤记录 … 的开始时刻 2026-10-21 01:10:57.969138+08 晚于完成时刻 2026-10-21 01:10:52.977953+08，耗时为负`。

## 后续待办

- **本模块之外的同类缺陷没查**：别的 trade 模块（以及 channel / warehouse 等服务）里
  「服务层 JVM 落请求侧时刻 + DB 窄函数落转移侧时刻 + CHECK 拿两者比大小」的形状一定还有。
  本车道只修 9.3.3.1（任务书限定），**建议下一轮单独派一条车道全仓扫一遍**
  （线索：`docs/backlog/2026-10/2026-10-20-cross-clock-domain-test-assertion.md`
  记的是同一类问题的**测试断言**那一侧，写侧这一侧还没系统扫过）。
- 头行的 `created_at` / `updated_at` 仍用 `DEFAULT now()`（事务开始时刻）而转移时刻用
  `clock_timestamp()`：同一个 DB 时钟的两次读数，正常不会出事，但 DB 时钟回跳时
  `CHECK (updated_at >= created_at)` 仍可能违约。**本轮未动**（改它要 ALTER 既有表，
  违反「既有表一律不 ALTER」的纪律），记在这里备查。
