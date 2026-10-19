# 2026-10-19: 修复超期退回测试对当天时刻的依赖

## 摘要

`ReturnReceiptAllocationIntegrationTest.超期退回按收货时刻与寄回时限判定且时刻零漂移` 是一个**依赖本地当天时刻**的测试：上午 09:30 之前跑必绿，09:30 之后跑必红。它在 11:04 的 L2 全量验证里红了。根因是测试构造 `receivedAt` 时用 `.withHour(9).withMinute(30)` 改写了时刻，把本应「恰好晚 4 天」的偏移变成了「4 天 − (当前时刻 − 09:30)」，而服务端 `Duration.toDays()` 向下取整，于是断言 `daysOverdue == 4` 在 09:30 之后必然拿到 3。修复方式是让 `receivedAt` 直接由 `returnByAt.plusDays(4)` 得出，偏移恒为 4 天整，与当天几点无关。服务端算法未改。

## 决策 / 结论

- **服务端是对的，不改**。`ReturnReceiptAllocationService.java:790-791` 的
  `Duration.between(returnByAt, receivedAt).toDays()` 向下取整是合理业务语义（「满 N 天才算超期 N 天」），缺陷只在测试构造方式。
- **测试偏移必须由两个时标直接相减得出**，不能在夹具里改写时刻。凡是要断言两个 `OffsetDateTime` 之间差值的测试，一律不许对其中一个做 `withHour` / `withMinute` / `withSecond` 之类的时刻改写。
- **保留全部原断言**：两条 `isEqualTo` 写回零漂移断言（`returnByAt()` / `receivedAt()`）与 reconcile / `OVERDUE_RETURNED` 断言全部原样保留，未做任何放宽或删除。
- 已在测试里就地留注释说明「为什么不能改写时刻」，避免后续有人为了「让时刻好看」再加回来。

## 涉及文件

- `backend/services/trade/src/test/java/com/starcon/trade/ReturnReceiptAllocationIntegrationTest.java:577-586`（唯一改动点）
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnReceiptAllocationService.java:790-791`（只读，未改）

## 关键 trade-off / 讨论

**为什么原来会绿**：夹具用 `now8()`（`OffsetDateTime.now(ZoneOffset.ofHours(8))`）取相对时间，再 `plusDays(10)` 得到寄回时限 —— 这是**刻意为之**的，因为 V161 有「买家寄回时限必须晚于当前时刻」的 DB 校验，写死日历日期会让夹具在某天之后假失败（该文件 `now8()` 的 javadoc 已记录此事）。问题出在时限本身用对了相对时间，但**收货时刻**又被额外改写成了 09:30，两处一叠加就引入了时刻依赖。

**为什么现在会红**：11:04 跑时 `now8()` 时刻是 11:04，`returnByAt.plusDays(4)` 是 11:04，被改写成 09:30 后真实偏移变成 4 天 − 1 小时 34 分，`toDays()` 向下取整得 3，断言 4 红。全天 1440 个分钟点里有 **869 个**算不出 4（即 09:31 之后到 23:59 整段），也就是说这个测试一天里有 60.3% 的时间是红的。

**被否决的方案 A：把断言改成 `isBetween(3, 4)` / `isCloseTo`**。否决 —— 这会把一个精确的 4 天语义变成模糊区间，丢掉原本想验证的「超期天数算得准」这层信息，且掩盖真实回归。

**被否决的方案 B：改服务端 `toDays()` 为向上取整或改成按日历日差**。否决 —— 服务端行为本身正确（满 N 天才记 N 天），为了迁就一个测试夹具去改生产语义是把测试缺陷当缺陷修，是本末倒置；且会波及所有走同一算法的超期判定路径。

**被否决的方案 C：把夹具时间冻结在某个固定时刻（Clock 注入 / 固定 `T1`）**。否决 —— 时限仍受 V161「必须晚于当前时刻」校验约束，写死日历日期会重新引入日历漂移型假失败；且本测试的被测语义正是「写回值不漂移」，用相对时间才忠实。

**验证手段的取舍**：`TZ` 无法用来伪造「当天时刻」—— `now8()` 读的是系统瞬间再套固定 `+08:00` 偏移，改时区不改变系统瞬间（三个 TZ 实测均绿，但那是必然绿，不能作为时刻无关的证据）。环境无 `libfaketime`。因此改用与服务端**完全相同**的 `java.time` 运算，对全天 1440 个分钟点做扫描取证。

## 后续待办

- 无阻塞待办。建议后续新增夹具时沿用同一条纪律：**断言两个时标差值的测试，两个时标都由同一次 `now8()` 基准相减得出，不各自改写时刻**。

## 关联

- 服务端算法：`backend/services/trade/src/main/java/com/starcon/trade/ReturnReceiptAllocationService.java:790-791`
- 11.2.2.2 实收分配：`docs/sessions/2026-10/2026-10-19-11-2-2-2-receipt-allocation.md`
