# 2026-10-24 平台开关公告生效期「按值写回」断言 —— 纳秒 vs 微秒，与日历日时区那条不是同一个机制

创建：2026-10-24。发现人：main 同步任务（并入 `main` 的闸门）。
影响范围：**identity 服务的 17.7.2.1 平台参数与开关**（`announcement_from` / `announcement_to` 的写回一致性）。

本条是 `main` 合并闸门连续三轮（61 / 62 / 63）阻塞的直接原因：
PR 的 Java 作业比 `main` 基线多出一条失败用例，而这条用例**在 `main` 上不存在**，
是集成分支交付内容自带的 ⇒ 闸门判据「失败不得比基线更多」不成立 ⇒ PR 不能合。

⚠️ **与流水线已归档的「日历日时区」那条是两个不同的机制，不要当成同一个 bug 处理**：
[`2026-10-24-finance-due-date-calendar-zone-assertion.md`](2026-10-24-finance-due-date-calendar-zone-assertion.md)
是 finance 的**日历日取值时区**不同（恰好差一整天，只在本地 00:00–08:00 窗口内红）；
本条是 identity 的**亚微秒精度截断**（与时间窗口、与时区都无关，**每次跑都红**）。
两者都不在 `fix/12-1-1-1-due-date-timezone-assertion` 那个修复车道的覆盖范围内 ⇒ **需要单独修**。

## 现象

CI 断言原文（run `40005009323`，Java 作业）：

```
expected: 2026-09-23T18:42:19.524181159Z (java.time.OffsetDateTime)
 but was: 2026-09-23T18:42:19.524181Z (java.time.OffsetDateTime)
when comparing values using 'OffsetDateTime.timeLineOrder()'
	at ...PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示(PlatformFlagIntegrationTest.java:273)
```

- 期望值 **9 位小数（纳秒）**，实际值 **6 位小数（微秒）** ⇒ 实际值少了 **159 纳秒** ⇒ 按时刻序比较必然不等。
- ⚠️ 用例自己的注释预判的是「偏 8 小时（-28800s）」。**实际发生的是亚微秒截断** ——
  从断言数值可直接看出：**没有 28800 秒偏移** ⇒ 时区是对的，注释里的那个担心没有发生。两者根因不同、修法也不同。

## 根因（缺陷在测试，不在业务逻辑）

### 1. 存储层只有微秒精度

`backend/services/identity/src/main/resources/db/migration/V324__platform_feature_flags.sql:151`：

```sql
  announcement_from  timestamptz,
  announcement_to    timestamptz,
```

PostgreSQL 的 `timestamptz` 默认精度就是**微秒** ⇒ 写进去时纳秒位被截断。
这不是配置疏漏，是 PostgreSQL 的类型固有精度（与仓库里其它 `timestamptz` 用法一致）。

### 2. 测试却拿纳秒精度的值做逐值相等断言

`backend/services/identity/src/test/java/com/starcon/identity/PlatformFlagIntegrationTest.java`：

```java
var now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
var from = now.minusDays(30);
var to = now.minusDays(1);
request(token, "PUT", "/identity/platform-flags/settings/" + FLAG_ANNOUNCEMENT, announcementInput(from, to), 200);
var row = findSetting(settings(token), FLAG_ANNOUNCEMENT);
assertThat(OffsetDateTime.parse(row.get("announcementFrom").asText())).isEqualTo(from);
```

`announcementInput` 把 `from.toString()` 原样发出去，携带纳秒位；
回读值来自 `timestamptz` ⇒ 已被截断到微秒 ⇒ 与 `from` 逐值比较必然失败。

### 3. 为什么**不能**改成「服务端截断」来绕过

服务端确实可以把收到的 `OffsetDateTime` 截断到微秒再落库，但**那样这条用例依然红**：
断言比较的是**客户端那个未截断的纳秒值**（`from`），服务端无论如何都变不出客户端那 159 纳秒。
⇒ **唯一能让这条断言转绿、又不削弱它的办法，是修测试侧的精度假设。**

### 4. 台账里的「零漂移」承诺只在微秒输入下成立

功能进度台账 17.7.2.1 一行写着「公告生效期按值往返（+08:00 夹具断言写回的绝对时刻，**零漂移**）」。
这条承诺**对微秒精度的输入成立，对纳秒输入不成立**（亚微秒位被存储层截断，不可恢复）。
⇒ 修的时候需要把口径钉死，避免下一个人以为「纳秒也能零漂移」，坑会在别的模块再炸一次。

## 处置口径

**修在集成侧**（`integration/module-dev` 或从它切出的修复车道），**不要修在 main 同步分支上** ——
用例是集成分支交付内容的所有物，在同步分支上打补丁会让两边分叉，集成侧后续修复时还要再解一次冲突。

- **只改测试侧**：构造夹具时先把精度对齐到存储层，例如
  `OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)` 再派生 `from` / `to`；
  或在断言处改用容差比较（不推荐，容差会把真回归一起吞掉，**优先用截断**）。
- **不许改生产代码**（服务端当前的 `timestamptz` 口径是对的，与仓库其它模块一致）。
- **不许改弱断言**：不许 `@Disabled`、不许换成 `isNotNull`、不许把 `isEqualTo` 换成只比日期。
- **补一条把契约钉死的回归**：显式写入一个**微秒精度**的值并断言回读逐值相等，
  同时在注释里写明「亚微秒位由 `timestamptz` 存储精度截断，不是可恢复的漂移」。

## 影响面

- 模块 17.7.2.1（identity 服务的平台参数与开关）：`announcement_from` / `announcement_to` 的写回一致性。
- PR 合并：修好之前，`main` 合并闸门每一轮都会在同一处阻塞。
- 连带风险：Java 作业在 identity 模块失败后，reactor 会把 `catalog` / `channel` / `warehouse` / `trade` /
  `crm` / `finance` 全部 SKIPPED ⇒ 本次交付的 4 项财务能力（坏账与核销、付款计划、核销、结算单管理）
  的集成测试**在 CI 里一次都没跑过**。这条修好之后它们才会有 CI 证据。

## 完成标准

- `PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示` 在 CI 中转绿。
- identity 模块不再失败 ⇒ reactor 后续模块（catalog / channel / warehouse / trade / crm / finance）真正跑到测试。
- 该用例的精度契约（微秒精度输入逐值相等；亚微秒位被存储层截断）有回归测试钉住。

## 状态

- **Open**
