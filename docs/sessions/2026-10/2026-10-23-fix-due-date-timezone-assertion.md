# 2026-10-23: 到期日断言改在 UTC 日历日上比对（finance 偶发红 · 本地 00:00-08:00 窗口）

## 摘要

finance 的两个集成测试在**本地时间 00:00–08:00（UTC+8）** 窗口内必红，报错形态是「到期日差一天」：
期望 `2026-11-23`、实际 `2026-11-22`。其余时间（08:00–24:00）一直绿 —— 这是典型的**跟墙钟绑定**的偶发红。

根因不在服务端：**服务端的到期日本来就是按 UTC 日历日算的（刻意为之，有注释）**，错的是**测试断言写在了
JVM 本地时区里**。本车道把两处断言改成在 UTC 日历日上比对，并各补一条**把该契约钉死**的回归测试
（含跨日边界夹具）。**未改动任何生产代码。**

## 症状

改动前跑 `ReceivableIntegrationTest` + `PayableIntegrationTest`（本地 00:34 CST / UTC 16:34 前一天）：

```
[ERROR] PayableIntegrationTest.建单后单头金额等于明细合计且已付为零未付等于金额:395
expected: 2026-11-23 (java.time.LocalDate)
 but was: 2026-11-22 (java.time.LocalDate)
[ERROR] ReceivableIntegrationTest.建单后单头金额等于明细合计且状态是未核销:293
expected: 2026-11-23 (java.time.LocalDate)
 but was: 2026-11-22 (java.time.LocalDate)
[ERROR] Tests run: 69, Failures: 2, Errors: 0, Skipped: 0
```

## 机制（本车道实测验证，非照抄推断）

### 1. 服务端：到期日按 UTC 日历日推导（**是对的，不是缺陷**）

- `ReceivableService.java:450-453`：
  `OffsetDateTime confirmedAt = OffsetDateTime.now(ZoneOffset.UTC);` → `confirmedDate = confirmedAt.toLocalDate();`
  → `dueDate = confirmedDate.plusDays(policy.dueDays())`。
- `PayableService.java:478-481`：同一口径。
- `ReceivableService.java:49` / `PayableService.java:58`：`static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);`
- 账龄/逾期 SQL 同样用 UTC：`ReceivableService.java:297/298/342/791-794`、`PayableService.java:331/332/367/920-921`
  —— `(now() at time zone 'UTC')::date`。
- 设计理由（`ReceivableService.java:783` 注释原文）：
  「⚠️ **按 UTC 日历日分桶**：用会话时区的话，换一条连接就换一套分桶，分布会凭空多出空桶。」

⇒ **服务端口径正确，不能动。**

### 2. 读回：`confirmed_at` 的偏移是 **JDBC 按 JVM 本地时区**给的

⚠️ **这里与最初的推断不同**：最初以为是 Jackson 的 `ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 把它转成本地偏移，
**但不是**。这两个测试是**直接调服务层**（`receivables.create(...)` / `payables.create(...)`），返回值不经过 JSON 往返。
`confirmed_at` 列类型是 `timestamp with time zone`，值由 jOOQ `r.get("confirmed_at", OffsetDateTime.class)` 读出，
**PostgreSQL JDBC 驱动在 timestamptz → OffsetDateTime 转换时按 JVM 默认时区给偏移**。

实测探针（临时打印，已删除）：

```
PROBE|confirmedAt=2026-10-24T00:34:17.873873+08:00|offset=+08:00
      |localDate=2026-10-24|utcDate=2026-10-23|dueDate=2026-11-22
      |systemZone=Asia/Shanghai|nowLocal=2026-10-24|nowUtc=2026-10-23
      |dbUtcDay=2026-10-23|dbDueDate=2026-11-22|colType=timestamp with time zone
```

⇒ `confirmedAt()` 拿到的是 **+08:00 偏移**，`.toLocalDate()` 取到的是**本地日历日 2026-10-24**；
而服务端写进去的到期日是 **UTC 日历日 2026-10-23 + 30 = 2026-11-22**。两者差一天。

### 3. 差一天只出现在本地 00:00–08:00

本地 00:30 = UTC 前一天 16:30 ⇒ 本地日历日 = UTC 日历日 **+1** ⇒ 断言的期望值比服务端大一天。
本地 08:00 以后两者同一天 ⇒ 断言恒绿。**这就是「只有深夜红」的全部原因。**

## 决策 / 结论

1. **改测试，不改服务端。** 服务端的 UTC 日历日口径是刻意的设计决定（有注释、有账龄桶一致性要求），
   改它会连带破坏逾期分桶与账龄口径；错的是断言写在了本地时区里。
2. **断言改为在 UTC 日历日上比对**：`.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()`，
   两个测试类各抽一个 `utcCalendarDate(OffsetDateTime)` 私有静态方法作为口径唯一出处，并在断言处留注释
   （防止下一个人觉得「改成 UTC 是多余的」又改回本地时区）。
3. **各补一条回归测试** `到期日按UTC日历日推导而不是跟着JVM本地时区走`，四段断言：
   - ① 契约：`dueDate == confirmedAt 的 UTC 日历日 + 账期`；
   - ② 落库口径：由 DB 独立算 `(confirmed_at at time zone 'UTC')::date + 账期 == due_date`（不受读回偏移影响）；
   - ③ 本地日历日与 UTC 日历日不同时（本地 00:00–08:00 窗口），显式断言 `dueDate != 本地日历日 + 账期`；
   - ④ 跨日边界夹具 `2026-10-01T16:30:00Z`：UTC 下是 10-01、+08:00 下是 10-02，
     断言口径落在 **UTC 那一天**推出的 `2026-10-31`，而不是本地那天的 `2026-11-01`（对任何 JVM 时区都成立，不依赖运行时刻）。

### 关键 trade-off

- **不改全局时区**：`TimeZone.setDefault(...)` 是全局状态，并行跑测试会污染别的测试类 ⇒ 改用「构造跨日边界的
  `confirmedAt` 夹具」达到同样目的，不动全局。
- **回归测试的覆盖边界（诚实说明）**：服务端 `create()` 用的是 `OffsetDateTime.now(ZoneOffset.UTC)`，
  **没有可注入的 `Clock` 缝**（已 grep 确认 `backend/` 全仓无 `Clock` 用法），因此「若把生产代码改成本地时区口径」
  这一失败模式，**只能在本地 00:00–08:00 窗口内被观测到**（① 与 ② 在该窗口内会红）。
  在 UTC 机器 / 非该窗口运行时，①② 恒绿 —— 这是「跟墙钟绑定的口径」这一类缺陷的固有属性，不是本次能消除的。
  ④ 则是**确定性**的：它对任何 JVM 时区都成立，负责钉死「口径是 UTC 日历日」这条约定本身。
- **回归测试写在两个类里各一条**（而非只写一条）：`ReceivableService` 与 `PayableService` 是两份独立实现，
  各自的口径需要各自钉住。
- **不抽公共测试基类**：本次只允许碰这两个文件，抽基类会扩大改动面。

## 涉及文件

- `backend/services/finance/src/test/java/com/starcon/finance/ReceivableIntegrationTest.java`
  —— 修断言（:296）+ 新增 `utcCalendarDate` 辅助 + 新增回归测试 `到期日按UTC日历日推导而不是跟着JVM本地时区走`。
- `backend/services/finance/src/test/java/com/starcon/finance/PayableIntegrationTest.java`
  —— 同上（:398）。
- `docs/sessions/2026-10/2026-10-23-fix-due-date-timezone-assertion.md` —— 本文件。

**未改动**（刻意）：`ReceivableService` / `PayableService` / Flyway 迁移 / OpenAPI 契约 / 前端任何文件 /
`docs/product/capability-blueprint/module-progress.md`（台账由调度者处理）。

## 验证结果

均在 worktree `12.1.1.1-fix`、本地 **00:30–00:52 CST**（红窗口内）执行。

| # | 验证 | 命令 | 结果 |
| - | ---- | ---- | ---- |
| 1 | **红**（改动前） | `./mvnw -pl services/finance -am test -Dtest='ReceivableIntegrationTest,PayableIntegrationTest'` | ❌ `Tests run: 69, Failures: 2`（expected 2026-11-23 / but was 2026-11-22） |
| 2 | **绿**（改动后，同一命令） | 同上 | ✅ `Tests run: 71, Failures: 0, Errors: 0` · `BUILD SUCCESS`（69 → 71 = 两条新回归测试） |
| 3 | **变异验证 A**：把测试里的口径退回本地时区（`instant.toLocalDate()`） | 同上 | ❌ `Tests run: 71, Failures: 4` —— 两条修好的断言 **和** 两条新回归测试**全部**变红 ⇒ 回归测试不是摆设 |
| 4 | **变异验证 B**：把**生产代码**改成本地时区口径（`OffsetDateTime.now()`） | 同上 | ❌ `Tests run: 71, Failures: 4` —— 同上 ⇒ 回归测试能钉住服务端口径。**变异已完整还原**（`git diff -- backend/services/finance/src/main/` 为空） |
| 5 | L0 编译闸 | `cd backend && ./mvnw -q compile` | ✅ exit 0 |
| 6 | L0 类型闸 | `pnpm typecheck` | ✅ exit 0（3 packages successful） |
| 7 | 全 finance 服务回归 | `./mvnw -pl services/finance -am test` | ✅ `BUILD SUCCESS` —— ReceivableIntegrationTest 32 / PayableIntegrationTest 39 / 其余 6 个测试类全绿 |

## 全仓同形写法排查（只报告、未修）

在 `backend/**/src/test/**` 全量 grep `.toLocalDate()`，逐条判定：

- **同形缺陷（本次已修）**：`ReceivableIntegrationTest` / `PayableIntegrationTest` 各一处 —— 其余全仓**没有第二处**
  「服务端按 UTC 推日期、断言按本地日历日比」的写法。
- **不是同形，但同属「跟墙钟绑定」的脆弱写法（本次未改，仅报告）**：
  - `catalog/CustomsAttributeIntegrationTest.java:675`
  - `catalog/CustomsDestinationVersionIntegrationTest.java:1158`

  两处都是 `xxxAt.toLocalDate()` 与 `OffsetDateTime.now().toLocalDate()` 比较 —— **两侧都用本地口径**，
  服务端用 SQL `now()` 落 timestamptz，语义上是一致的（「审核/发布时刻与墙钟同一天」），**不会系统性差一天**；
  只在「建单与断言之间正好跨过本地午夜」的秒级窗口里有理论风险。与本次的缺陷不同源。
- **已是 UTC 口径（正确，可作先例）**：`channel/LogisticsPickupSignoffIntegrationTest.java:250`
  （`atZoneSameInstant(ZoneOffset.UTC)`）、`crm/AutoReplyIntegrationTest.java:601/621`
  （`OffsetDateTime.now(ZoneOffset.UTC)`）。

## 后续待办

1. **调度者**：本车道未改台账，12.1.1.1 的台账行由调度者统一处理；并入集成头也由调度者做。
2. **可选（不阻塞）**：`catalog` 那两处「跟墙钟绑定」的比较可考虑改成与墙钟无关的锚点
   （或至少在注释里写明「本断言依赖建单与断言不跨本地午夜」）。属独立车道议题，本车道未动。
3. **可选（不阻塞）**：finance 服务若将来要支持「按租户时区分桶」，需要先给 `create()` 注入 `Clock` 缝
   （当前全仓无 `Clock` 用法），否则这类口径无法做确定性测试。
