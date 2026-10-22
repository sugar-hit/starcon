# 测试用墙钟相对值 + 硬编码绝对时刻 ⇒ 用例每天定时红

发现：2026-10-22 09:13（batch-62 的批次 L2）。发现人：批次调度者。
影响范围：**模块 10.4.1.2 在途跟踪**（warehouse 服务）的
`InTransitTrackingIntegrationTest.节点_时间往返`，以及**每一个批次 L2** ——
它会把整批验证打成红的。

## 现象

2026-10-22 08:45–09:13 的批次 L2 在集成头上失败：

```
[ERROR] InTransitTrackingIntegrationTest.节点_时间往返:718->asAWrite:303->lambda$节点_时间往返$2:720
        » IllegalArgument 新的预计到港 2026-12-01T08:00+08:00 早于当前 ETA 2026-12-01T09:00+08:00：
          把 ETA 往前调会让「已经晚了几天」算成负数，而这正是本模块最需要答对的数
[ERROR] Tests run: 1639, Failures: 0, Errors: 1, Skipped: 0   （warehouse）
[ERROR] Failed to execute goal ... on project warehouse
```

而**上一个绿批次**（2026-10-22 07:06，同一条用例）是通过的。

## 根因

**缺陷在测试，不在领域逻辑。**

1. 测试辅助方法（`InTransitTrackingIntegrationTest.java` 第 312–319 行）
   **保留当前的「小时」**：

   ```java
   static OffsetDateTime eta(int daysFromNow) {
     return OffsetDateTime.now(ZoneOffset.ofHours(8))
         .plusDays(daysFromNow)
         .withMinute(0).withSecond(0).withNano(0);
   }
   ```

2. 用例第 717 行把「改期后的新 ETA」**硬编码**成绝对时刻：

   ```java
   var newEta = OffsetDateTime.of(2026, 12, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8));
   ```

3. 两者混用 ⇒ 这条用例**只有在墙钟（UTC+8）小时数 < 08 时才成立**：

   | 跑批次的墙钟 | `eta(0)` 算出的当前 ETA | `newEta` 与它的关系 | 结果 |
   | --- | --- | --- | --- |
   | 07:06（上一个绿批次） | `2026-12-01T07:00+08:00` | 晚于它 | ✅ 绿 |
   | 09:13（本轮） | `2026-12-01T09:00+08:00` | 早于它 | ❌ 红 |

   ⇒ **每天 09:00–23:59（UTC+8）这条用例必红**，
   而 08:00–08:59 恰好相等（`isBefore` 是严格比较）所以侥幸绿。
   **这已经不是"偶发"，是"定时"失败** —— 往后的每个批次 L2 都会红。

4. 领域规则**是对的**（`InTransitTrackingService.java` 第 1059 行）：

   ```java
   if (currentEta != null && r.etaAfter().isBefore(currentEta)) {
     throw new IllegalArgumentException("新的预计到港 ... 早于当前 ETA ...");
   }
   ```

   「ETA 不许往前调，因为『已经晚了几天』会算成负数」是正确的业务纪律，**不许放宽这条规则**。

## 与最近并入车道的关系：**无关**

`git log da1f96f9..HEAD -- backend/services/warehouse/` **为空** ⇒ warehouse 后端代码与上一个绿批次
**逐字节相同**。最近并入的 4 条车道（15.1.1.1 / 15.2.1.1 / 15.3.2.1 / 15.6.2.2）**全在 crm 服务**，
不碰 warehouse 的任何代码或迁移。
⇒ 这是**既有缺陷被墙钟暴露**，不是跨车道互相破坏，**不要去二分本批的车道**。

## 取证补充

- 全仓后端测试树里，硬编码 `2026-12-01` **只有这一处**
  （`grep -rn '2026, 12, 1' backend/services/*/src/test/java/` 仅命中该文件第 717 行）。
- 但 `eta(0)` 在同文件里被大量复用（`occurredAt` / `location` 等），
  **是否还有别的「相对当前」与「固定时刻」混着比的地方，需要自己再扫一遍**。

## 处置

已派**修复车道** `10.4.1.2`（分支 `fix/10-4-1-2-eta-clock`，
worktree `.worktrees/10.4.1.2-fix`）：

- 修法约束：**让「改期后的新 ETA」与「记录里当前的 ETA」同源**（例如 `eta(0).plusDays(1)`），
  使其**无论墙钟几点都严格晚于当前 ETA**；
- **禁止**用硬编码绝对时刻去"对齐"（明天 09:00 又会红）；
- **禁止**放宽断言 / 删断言 / `@Disabled` / `assumeTrue` 绕过；
- 必须用**至少两个不同时刻基准**证明墙钟无关；
- **领域规则不许动**。

## ⚠️ 给下一个 tick 的提醒

**在这个修复车道落地之前，不要把又一轮 L2 的红归因到最近并入的车道上。**
本条目的根因已经取证完毕（warehouse 代码与绿批次逐字节相同）。
若你看到 `InTransitTrackingIntegrationTest.节点_时间往返` 报同一条
「新的预计到港 … 早于当前 ETA …」，那就是**同一条墙钟缺陷**，
不是新回归 —— 修复车道落地后它会自愈。
