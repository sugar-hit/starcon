# 2026-10-27: trade 服务两处跨钟域取时缺陷 —— order_split 记账时刻归 DB ＋ 纠纷夹具改用 DB 钟

> 车道 `fix/trade-clock-domain`（从集成头 `integration/module-dev` @ `18f812e5` 切出）。
> 这是 L2 批次闸定位到的**回归修复**，不是新蓝图模块 —— **无迁移、无端点、无页面、无契约改动**。
> 本车道是**重派**（上一轮零 commit，等于从头做）。

## 摘要

| 项 | 值 |
| --- | --- |
| 缺陷① | `order_split` 一族的 `created_at` 绑 JVM 墙钟，而 `advance_order_split_status` 写 `updated_at=now()`（DB 钟）⇒ `CHECK(updated_at>=created_at)` 比较两个钟域 |
| 缺陷② | `PlatformDisputeTenancyIntegrationTest` 夹具用宿主钟写 `evidence_deadline`，而判超期全在 DB 钟域 |
| 改动 | 1 个 Java 服务类 + 2 个既有测试类 + 1 个 backlog 登记 + 1 份会话日志 |
| 迁移 / 端点 / 页面 | **无 / 无 / 无** |
| L0 | `mvnw -q compile` exit 0；`pnpm typecheck` 3/3 successful |
| L1 | **连跑 3 次，每次 `Tests run: 42, Failures: 0, Errors: 0` + `BUILD SUCCESS`** |
| PG 容器 | 测试期间最多 **3** 个（含 ryuk）；结束后**已回收**，无 `postgres:16-alpine` 残留 |
| 同族隐患 | 登记在 `docs/backlog/2026-10-2026-10-27-trade-timestamps-ordered-cross-clock-domain.md` |

## 决策摘要

### ① order_split 一族的记账时刻归 DB（**改取时刻的方式，不动约束**）

V151 给 `order_split` / `order_split_unit` / `order_split_reservation_command` /
`order_split_writeback_task` 钉了 `CHECK(updated_at>=created_at)`，
而 `advance_order_split_status(...)` 推进状态时写的是 `updated_at=now()`（**DB 钟**）。
服务层建行时绑的却是 `OffsetDateTime.now()`（**JVM 墙钟**）⇒ 约束比较的是两个钟域的时刻。

⇒ **修法：把 INSERT 的 `created_at` / `updated_at` 改成 SQL 表达式 `now()`，让 DB 自己取时刻。**

- **约束一个字没动** —— 它防的是「记账时刻早于建行时刻」这个真实错误；
  错的是喂给它的跨钟域值。
- **DB 函数一个字没动** —— `updated_at=now()` 本来就是对的。

**关键 trade-off：换一端还是换两端？**
只把 `created_at` 换成 DB 钟、留着 `updated_at` 绑 JVM 墙钟，等于**新造**一条跨钟域依赖
（宿主钟慢 1ms，一次合法的回传补录就被挡下）。⇒ **同一张表的两个端必须一起换**：
`recordReservation` / `recordWriteback` 里那两处 `updated_at` 一并改成 DB 的 `now()`。

**范围取舍**：本车道收了 **order_split 这一族 7 张表**（头 / 单元 / 明细 / 候选 / 预占指令 / 回传任务 / 事件流）。
**没收**同文件里的三张**配置表**（`order_split_rule` / `order_split_warehouse` / `order_split_stock_snapshot`）——
它们的两端都是服务层自己写的 JVM 钟域，**暂时自洽**；一次修 32 张表会把修复车道变成大重构。
⇒ 登记进 backlog。

**业务时刻保持原样**：`planned_at` / `ship_by_at` / `occurred_at` / `collected_at` 由入参给，
DB 不拿它们跟 `created_at` 比（V151 刻意不给这些列加 CHECK）⇒ 不动。

### ② 纠纷夹具改用 DB 钟域（**改夹具，不动生产代码**）

`platform_dispute.evidence_deadline` 的判定**本来就统一在 DB 钟域**
（V265 守卫① 判 `evidence_deadline <= now()`、状态机守卫判 `evidence_deadline > now()` 就拒绝「提前判负」、
服务层读时收口 `expireOverdueDisputes()` 也用 `now()`）⇒ **生产侧这一侧是对的**。

⇒ **改夹具**：`OffsetDateTime.now(UTC).minusMinutes(1)` → `dbNow().minusMinutes(1)`
（`dbNow()` 照抄 `com.starcon.warehouse.StockOwnershipIntegrationTest#dbNow()`，未自创）。
**断言一条没弱**：`expected "RESOLVED_LOST"` 仍然是 `RESOLVED_LOST`。

## 关键取证（都在本机实测，不是推断）

1. **钟差是毫秒级、且会变号** ⇒ 这族缺陷**空载时一次跑就绿**。
   基线 L1 就是**绿的**（40 例全过），正是任务书提醒的「一次绿不算数」。
   本机实测：PG 容器钟领先宿主 JVM 约 **3.5ms ~ 50ms**（随负载变化）。
2. **缺陷①的回归断言是确定性的**（不靠钟差）：
   PostgreSQL 的 `now()` 是**事务开始时刻**，同一事务里写的每一行拿到的都是**同一个值**
   ⇒ 断言「同一笔拆单的整族行共享同一个 `created_at`」。
   绑 JVM 墙钟时每次 `now()` 都是新瞬间，整族行必然参差。
   **实测：把修复回退后，这条断言每次都红** —— 14 行的时刻互不相同（差在微秒级）：
   `expected: 2026-10-27T16:15:29.008498+08:00 but was: 2026-10-27T16:15:29.009403+08:00`。
3. **缺陷②的取证**（走查 V265 守卫链得出）：
   守卫①（`BEFORE UPDATE`）在 `evidence_deadline <= now()` 时把状态改写成 `RESOLVED_LOST`，
   状态机守卫在 `evidence_deadline > now()` 时**拒绝**「提前判负」 —— 两者互为补集。
   ⇒ 夹具用宿主钟时，「到没到期」这一问的答案**取决于两台钟的差**，而不是取决于夹具写的是什么。
   实测本机两钟只差 3.5ms ⇒ 这台机器上**复现不出** L2 的失败，这正是它「一次绿不算数」的原因。
   ⇒ 因此缺陷②的断言写成**保证式**的：「写进去的截止时刻必然不晚于 DB 钟的『现在减 1 分钟』」
   （两个端点同钟域 ⇒ 恒真），而不是去比某个具体差值。

## 文件清单

| 文件 | 改了什么 |
| --- | --- |
| `backend/services/trade/src/main/java/com/starcon/trade/OrderSplitService.java` | 7 张表的 `created_at`/`updated_at` 改用 DB 的 `now()`；`recordReservation` / `recordWriteback` 的 `updated_at` 一并换；`split()` 补钟域纪律的注释 |
| `backend/services/trade/src/test/java/com/starcon/trade/OrderSplitIntegrationTest.java` | 新增 `dbNow()` + `splitBookkeepingTimes()` + 回归用例 `拆单记账时刻与DB钟同域` |
| `backend/services/trade/src/test/java/com/starcon/trade/PlatformDisputeTenancyIntegrationTest.java` | 新增 `dbNow()`；夹具改用 DB 钟；新增写回值断言；新增 `超期判定走DB钟域_未来截止时刻不判负` |
| `docs/backlog/2026-10-2026-10-27-trade-timestamps-ordered-cross-clock-domain.md` | **新建**：同族隐患登记（32 张表 / 11 个迁移 / trade 231 处 + 全后端 593 处绑定点） |
| `docs/sessions/2026-10/2026-10-27-trade-clock-domain-fix.md` | **新建**：本文件 |

**未改**：任何迁移文件、任何端点 / DTO / 契约、`packages/api-contracts`、`packages/sdk`、
`docs/product/capability-blueprint/module-progress.md`（本车道不是蓝图模块，没有对应行）。

## 验证

### L0 编译闸

```
$ cd backend && ./mvnw -q compile
COMPILE_EXIT=0

$ pnpm typecheck
 Tasks:    3 successful, 3 total
TYPECHECK_EXIT=0
```

### L1 模块闸（连跑 3 次，每次都贴真实输出）

```
$ cd backend && ./mvnw -pl services/trade -am test \
    -Dtest='PlatformDisputeTenancyIntegrationTest,OrderSplitIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false

===== L1 RUN 1 =====
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.trade.PlatformDisputeTenancyIntegrationTest
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.trade.OrderSplitIntegrationTest
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

===== L1 RUN 2 =====
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.trade.PlatformDisputeTenancyIntegrationTest
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.trade.OrderSplitIntegrationTest
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

===== L1 RUN 3 =====
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.trade.PlatformDisputeTenancyIntegrationTest
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.trade.OrderSplitIntegrationTest
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**测试期间同时存活的 PG 容器数：最多 3**（含 ryuk reaper；两个测试类各自的 PG 容器有交叠）。
**结束前已被回收** —— 跑完后 `docker ps` 里已无 `postgres:16-alpine`，只剩 ryuk 自身。

### 多租户三断言

`PlatformDisputeTenancyIntegrationTest` 的三条断言**保持强断言并全绿**
（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）。
本车道不新建表，改动不触碰 RLS 策略；所有新增查询都带租户上下文。

## 后续待办

1. **同族隐患收口**（已登记，见 backlog）：32 张表 / trade 231 处 + 全后端 593 处绑定点。
   **一条修复车道收不了这个口** ⇒ 按模块拆车道，**逐表判定**「`updated_at` 到底是谁写的」再动手。
2. **`OrderSplitService` 里剩的 9 处 JVM 墙钟绑定**（三张配置表）—— 两端自洽，但宿主钟回跳一样会撞约束。
3. **trade 其余 34 个文件里的 220 处绑定点**，以及 warehouse / channel / catalog / crm 侧的 362 处。
4. **缺陷②的复现环境**：本机两钟只差 3.5ms，**复现不出** L2 的失败。
   要复现需要一个能制造**秒级**钟差的运行环境（例如把 PG 容器放在独立 VM 上并人为偏钟）——
   本轮**没有**搭这样的环境，故缺陷②的修复**靠构造保证 + 保证式断言**，
   不靠「复现出来再修好」证明。

## 关联

- 同族前两例的修法：`32a16a6a`（identity 审批待办）、`8375e648`（warehouse 货权档漂移）。
- 上一例的 backlog：`docs/backlog/2026-10-2026-10-21-trade-fulfillment-clock-domain-db-constraint.md`。
- 纪律来源：AGENTS.md「数据访问用 jOOQ」「每个新行为必须有测试」「多租户不可省」。
