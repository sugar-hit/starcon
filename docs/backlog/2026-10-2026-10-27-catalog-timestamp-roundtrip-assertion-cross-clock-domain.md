# 跨时钟域隐患登记：catalog 的 `timestampsRoundTripWithoutTheSilentEightHourShift` 用 JVM 墙钟对比 DB 钟

> 登记时间：2026-10-27（pipeline round 114）
> 首次触发：round 113 的 L2 第 2 跑（`.tmp/l2-r113b.log`），`catalog` 模块 1998 例里 **1 例红**
> 归因：**环境型假红**（不是代码回归）—— 但它会**反复把 L2 批次闸打红**，必须收口。

## 现象

```
[ERROR] com.starcon.catalog.PlatformCategoryMappingIntegrationTest
        .timestampsRoundTripWithoutTheSilentEightHourShift
        AssertionError: [created_at 写回值必须落在测试时刻附近，偏 8 小时即为时区静默漂移]
Expecting actual: 15L to be less than: 5L
```

失败**前一行**紧挨着这条警告：

```
WARN  ... HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=15m52s273ms)
```

## 机制

断言本体（`PlatformCategoryMappingIntegrationTest.java:847-862`）拿**两个不同时钟域**做比较：

```java
OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);  // JVM 墙钟
...
OffsetDateTime dbCreated = ... select created_at ...;                        // PG 的 now()
assertThat(Math.abs(Duration.between(before, dbCreated).toMinutes())).isLessThan(5);  // 容差 5 分钟
```

- 它要防的是**真缺陷**：把 `timestamptz` 当 `LocalDateTime` 绑定 ⇒ 读出来偏 −28800s。
  这个守卫**必须保留**，不能删。
- 但容差只有 **5 分钟**，而比的是 **JVM 墙钟 vs PG 容器钟**。
  L2 跑全量时容器会被挤到停顿（`housekeeper delta=15m52s`），两个钟自然拉开 **15 分钟** ⇒ 守卫误报。
- 失败信息里的 `15L` 是**分钟**，不是小时 —— 断言消息写「偏 8 小时」容易让人误读。

## 为什么判定为假红（而不是本批回归）

| 证据                                                             | 结论                                   |
| ---------------------------------------------------------------- | -------------------------------------- |
| 本批 58 个待推送 commit 触及 catalog 的文件数 = **0**              | 与本批代码无关                         |
| 同一份 HEAD 的 **L2 第 1 跑**里，同一个测试类 `Tests run: 14, Failures: 0` | 同代码同测试 ⇒ 负载相关，不是稳定复现 |
| 失败前紧邻 `housekeeper delta=15m52s`（15m52s ≈ 失败差值 15 分钟） | 直接对应容器停顿导致的钟差             |
| 该日志同时有 **324 处 `Connection refused`**                       | PG 容器被反复重启/挤掉的同一族形状     |

## 建议的收口方向（**不要**在集成头上直接改，走车道）

1. **容差按「钟差」而非「墙钟差」**：断言改成比较**同一个钟域**的值 ——
   例如把 `before` 也取自 DB（`select now()`），断言 `dbCreated` 与 DB 的 `now()` 的差 < 5 分钟。
   这样守卫仍能抓「−28800s 静默漂移」（那是 8 小时的量级差），但不再对**容器停顿**敏感。
2. **更稳的写法**：直接断言 `dbCreated` 落在 `[before − 容差, before + 容差]`，
   容差放大到能覆盖容器停顿的量级（≥ 30 分钟）会**削弱**守卫力度 —— 不推荐单走这条。
   首选仍是「两边同钟域」。
3. **顺带排查同族写法**：全仓检索「JVM `OffsetDateTime.now()` 与 DB `now()` 直接相减」的断言
   （本仓已知另有 `ApprovalInboxIntegrationTest` 的潜伏写法，见 batch-106 待办 #8）。
   建议按「取时刻」与「比时刻」两侧一起收：**生产侧写库用 DB 钟，断言侧比 DB 钟**。

## 影响面

- **对业务代码**：无。`created_at` 仍由 PG 的 `now()` 生成，落库语义正确。
- **对流水线**：有 —— 这类假红会把 L2 批次闸打红 ⇒ 按「L2 没绿不 push」**整批 58 个 commit 卡住**，
  连带下一轮 tick 要再花 ~3 小时复跑 L2。**收口它比收口任何单个模块都更能提高吞吐。**
