# 2026-10-24 测试在「本地日历日」取日、服务端在「UTC 日历日」取日 ⇒ 本地 00:00–08:00 窗口内必红

发现：2026-10-24 00:0x（batch-79 派发的 12.1.1.3 车道）。发现人：批次调度者。
影响范围：**finance 服务的 12.1.1.1 应收单管理**与 **12.1.2.1 应付单管理**，
以及**每一个落在本地 00:00–08:00 窗口内的批次 L2** —— 它会把整批验证打成红的。

## 这是「墙钟依赖型红」这个类的**第二个实例**

⚠️ 同类第一个实例见 [`2026-10-20-cross-clock-domain-test-assertion.md`](2026-10-20-cross-clock-domain-test-assertion.md)
（batch-62 的 `InTransitTrackingIntegrationTest.节点_时间往返`：DB 写回的时刻 vs JVM 墙钟）。
**两者的机制不同，别当成同一个 bug 处理**：

| | 2026-10-20 那一个 | 本条 |
| --- | --- | --- |
| 根因 | 三方比较（DB 写回时刻 / 应用层读到的时刻 / 进程墙钟）**不是同一个时刻** | **日历日的取值时区不同** —— 服务端取 UTC 日历日，断言取本地日历日 |
| 表现 | 时刻差几秒 / 差几天 | **恰好差一天**，且只在每天的一个时间窗口里 |
| 触发窗口 | 全天偶发 | **本地 00:00–08:00（= UTC 前一天 16:00–24:00）必红**，其余时间全绿 |

⇒ 共同教训：**批次 L2 的红，必须先问「是不是只有某个时间窗口才红」** ——
在 08:00–24:00 跑绿、00:00–08:00 跑红的用例，**不是回归**，是时钟/时区依赖。

## 现象

```
ReceivableIntegrationTest  →  到期日差一天
PayableIntegrationTest     →  到期日差一天
```

**基线可复现**：12.1.1.3 车道的 worker 用 `git stash` 在基线 `a70ea6f1` 上复现过 ——
**与本车道改动无关**，是既有缺陷。

## 根因（缺陷在测试，不在领域逻辑）

### 1. 服务端的到期日是按 **UTC 日历日**算的 —— 这是**刻意的设计决定**

```java
// ReceivableService.java:49 / PayableService.java:58
static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);
```

`ReceivableService.java:783` 的注释原文：

> ⚠️ **按 UTC 日历日分桶**：用会话时区的话，换一条连接就换一套分桶，分布会凭空多出空桶。

SQL 侧同一口径：`where r.due_date < (now() at time zone 'UTC')::date`（`ReceivableService.java:297/298/342`、`PayableService.java:331/332/367`）。

**⇒ 服务端是对的，不许改。** 账龄/逾期判定用会话时区会让同一条记录在不同连接下落到不同桶里。

### 2. 测试断言却是在**本地时区**里取日历日

```java
// ReceivableIntegrationTest.java:293
assertThat(receivable.dueDate()).isEqualTo(receivable.confirmedAt().toLocalDate().plusDays(30));
// PayableIntegrationTest.java:395
assertThat(payable.dueDate()).isEqualTo(payable.confirmedAt().toLocalDate().plusDays(30));
```

`confirmedAt()` 是 `OffsetDateTime`，**Jackson 反序列化时默认会转到 context time zone**
（`ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 默认 `true`；finance 服务**没有**配 `spring.jackson.time-zone`，
`backend/services/finance/src/main/resources/` 下只有 `application.yml`），
于是拿到的偏移是 **+08:00**，`.toLocalDate()` 取到的是**本地日历日**。

### 3. 两者在本地 00:00–08:00 恰好差一天

本地 00:30 = UTC 前一天 16:30 ⇒ **本地日历日 = UTC 日历日 + 1** ⇒ 期望值比服务端的值大一天 ⇒ 红。
本地 08:00–24:00 时两者相等 ⇒ 绿。

## 全仓范围（2026-10-24 实测）

```bash
grep -rn "confirmedAt().toLocalDate()\|toLocalDate().plusDays" backend/services/finance/src/test/java/
```

⇒ **恰好 2 处**，就是上面那两个文件。
**本批新交付的三个车道（12.1.1.3 / 12.1.2.2 / 12.2.1.2）没有照抄这个写法** ——
所以这批的失败面**只有 2 条断言**，不是每条车道都炸。

## 第三个实例（同一个坑的第三条数据通路）：jOOQ 读 `timestamptz`

**12.2.1.2 核销车道在写自己的自动匹配逻辑时，独立撞上了同一个坑**（2026-10-24）：

> 实测抓到并修掉一个真 bug：**jOOQ 读 `timestamptz` 返回本机时区的 `OffsetDateTime`**，
> 直接 `toLocalDate()` 会比 SQL 侧 UTC 日期差一天 ⇒ 自动匹配**静默失配**
> （不报错、永远不命中）。已统一 `atZoneSameInstant(UTC)`。

⇒ **同一个「本地日历日 vs UTC 日历日」的差一天，有三条互相独立的入口**：

| 入口 | 谁踩到 | 症状 |
| --- | --- | --- |
| **Jackson 反序列化** `OffsetDateTime` → context time zone | 测试断言（`confirmedAt().toLocalDate()`） | 断言失败，差一天 |
| **jOOQ 读 `timestamptz`** → 本机时区的 `OffsetDateTime` | 12.2.1.2 的自动匹配 | **静默失配**（不报错、永远不命中） |
| **`Timestamp.valueOf(LocalDateTime)`** | AGENTS.md 已明令禁止（会静默偏 −28800s） | 时刻偏 8 小时 |

⚠️ 第二条最阴 —— **它不报错**，只是「永远匹配不上」，
在集成测试里如果只断言「命中 0 条」就会看起来像正常业务结果。

⇒ **统一处置口径**：
> **凡是「从库里读回来的时刻」再取日历日，一律先转到 DB 的口径时区（本仓是 UTC）**：
> - Jackson 出来的 `OffsetDateTime` → `withOffsetSameInstant(ZoneOffset.UTC)`
> - jOOQ 出来的 `OffsetDateTime` → `atZoneSameInstant(ZoneOffset.UTC)`
> - 然后再 `.toLocalDate()`。
>
> 想要本地日历日时**先想清楚**：服务端是按 UTC 日历日算的（账龄/逾期判定），
> 改成按本地时区会让**同一条记录在不同连接下落到不同桶里** —— 那才是真正的错。

## 处置

已派**修复车道** `fix/12-1-1-1-due-date-timezone-assertion`（车道 id 12.1.1.1）：

- 只改那 **2 行断言**，改成在 UTC 日历日上比对：
  `receivable.confirmedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().plusDays(30)`
- **不许改生产代码**（服务端的 UTC 口径是对的）
- **不许改弱断言**（不许 `isCloseTo`、不许 `@Disabled`、不许换成 `isNotNull`）
- **补一条回归测试**，用「UTC 日历日与本地日历日不同的 `confirmedAt`」（如 `2026-10-01T16:30:00Z`）
  把「按 UTC 日历日推导」这个契约**钉死** —— 否则下一个人仍可能把服务端改成本地时区，
  坑会在另一个窗口再炸一次。

## 写新测试时的通用纪律（从这一条 + 2026-10-20 那一条一起归纳）

1. **凡是要断言「某个日期/时刻」，先问一句：服务端是按哪个时区算的？**
   断言必须**与服务端同一个时区口径**比，否则就是拿两个日历日在比。
2. **不要用 `OffsetDateTime.now()` / `LocalDate.now()`（无参）做基准** ——
   它取的是 JVM 本地时区，而服务端与 DB 可能用 UTC。
   要用就写全 `LocalDate.now(ZoneOffset.UTC)`。
3. **`.toLocalDate()` 是本地时区陷阱**：`OffsetDateTime` 上的 `.toLocalDate()` 取的是
   **该值自身偏移量**下的日历日（Jackson 转过 context zone 之后就是 JVM 本地时区）。
   跨时区比较一律先 `withOffsetSameInstant(ZoneOffset.UTC)`。
4. **批次 L2 红了先看时间**：`uptime` / `date` 加上「上一轮绿的那次是什么时候」——
   如果两次差了一个固定窗口、且差值恰好是**一天**，基本可以断定是日历日/时区问题，不是回归。
