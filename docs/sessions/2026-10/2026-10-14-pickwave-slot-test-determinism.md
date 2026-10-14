# 8.3.2.2 波次时段用例钉住 created_at，消除 L2 对墙上时钟的依赖

日期：2026-10-14
分支：`fix/8-3-2-2-pickwave-slot-test`（基线 `integration/module-dev` @ f002cc8f）

## 根因

L2 全量 1422 例里唯一失败的是
`PickWaveIntegrationTest.plannedPickAtIsDerivedFromTheRuleSlotInWarehouseLocalTime`：

```
规则 R-SLOT-PLAN ... / 时段 [9,18) 在这个仓里没有匹配的待拣出库单
样本：[OB-PLAN/.../时区小时=19/命中规则=null/在波中=null]
```

服务端按**出库单的创建时刻**判时段是否落窗（`PickWaveService.java:186` 把
`o.created_at` 按 `warehouse.timezone` 换算成当地小时，`:241-242` 用
`local_hour >= start and local_hour < end` 匹配规则）。测试的 `outbound(...)`
辅助方法**没有设置 `created_at`**，走数据库默认值 `now()`。

于是这条用例的成败取决于**跑测试那一刻的墙上时钟**：Asia/Shanghai 当前小时
必须落在 09:00–17:59 才可能通过。流水线批次栅格 08:00 / 10:30 / 13:00 / 15:30 / 18:00
里，**08:00（hour=8）与 18:00（hour=18）必然红**，一天 5 格至少 2 格被阻断。

**不是本批引入的回归**：本批合入 8.3.3.2（V121）与 9.6.1.2（V122），
`git diff --name-only 36a03abd f002cc8f | grep -i pickwave` 为空，未触碰任何 PickWave 文件。

**生产代码是对的**：时段口径就是「按订单创建时刻落窗」，这是 8.3.2.2 的既定语义
（`PickWaveService.TIME_SLOT_BASIS`）。同文件兄弟用例
`timeSlotIsEvaluatedInWarehouseLocalHoursNotUtc` 早就用 `setCreatedAt(...)` 钉住了两张单，
所以它全天稳定。出错的那条只是**忘了钉**。

## 改法

只改 `PickWaveIntegrationTest.java` 一个文件（+3 行），照抄兄弟用例的 `setCreatedAt(...)`
用法，把 `OB-PLAN` 的 `created_at` 钉到仓本地当天 10:00（确定落在 [9,18) 内）：

```java
UUID plan = outbound(a, "OB-PLAN", "SF", sku(a, 1));
setCreatedAt(a, plan, LocalDate.now(WAREHOUSE_ZONE).atTime(10, 0)
    .atZone(WAREHOUSE_ZONE).toOffsetDateTime());
pickTask(a, plan);
```

日期同样用 `LocalDate.now(WAREHOUSE_ZONE)`，与断言里的
`atStartOfDay(WAREHOUSE_ZONE).plusHours(9)` 保持同一天，避免跨午夜错位。

**没有 mock、没有删改断言、没有给 `PickWaveService` 加特例分支，生产代码一行未动。**

## 反向对照实验（关键证据）

绿一次不能证明修好了——19 点多跑，一个「什么都没改」的版本本来就是红的。
所以在同一墙上时钟时刻做了反向对照：

| 步骤 | pinned 值 | 墙上时钟 | 结果 | 失败输出里的 local_hour |
| --- | --- | --- | --- | --- |
| 1 未改 | 无（走 `now()`） | 19:21 CST | **红** (30 例 1 错) | `时区小时=19` |
| 2 钉时段外 | 仓本地 20:00 | 19:22 CST | **红** (30 例 1 错) | `时区小时=20` |
| 3 钉时段内 | 仓本地 10:00 | 19:22 CST | **绿** (30 例全过) | — |

步骤 2 与 3 墙上时钟小时相同（都是 19 点）却得到相反结果 ⇒
该用例的成败**只由 pinned 值决定**，与墙上时钟无关，依赖已解除。
步骤 3 即为最终提交状态。

## 同类漏洞审计

全类里用**显式非 0/24 时段**的用例共 3 处：

- `twoActiveRulesCannotClaimTheSameCarrierZoneAndSlot`（约 456 行，规则 [9,18) 与 [18,24)）
  —— **不受影响，无需修**。它只写/读规则台账并断言唯一约束报错，
  从不建出库单、也不调 `wave()` / `candidates()`，`local_hour` 压根没进这条路径。
- `timeSlotIsEvaluatedInWarehouseLocalHoursNotUtc`（约 596 行，规则 [8,19)）
  —— **已钉好**。两张单的 `created_at` 分别钉在仓本地 08:00 与 20:00，断言直接读 `localHour()`，全天稳定。
- `plannedPickAtIsDerivedFromTheRuleSlotInWarehouseLocalTime`（约 628 行，规则 [9,18)）
  —— **本次修复对象**。

其余用例一律用 [0,24) 全时段，不受墙上时钟影响，未动。

## 验证

- `./mvnw -pl services/warehouse -am test -Dtest=PickWaveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  → Tests run: 30, Failures: 0, Errors: 0（修复后）
- `./mvnw -pl services/warehouse -am test`（整个 warehouse 服务）→ 见提交说明

## 遗留风险

- 用例仍以 `LocalDate.now(WAREHOUSE_ZONE)` 取当天。理论上在跨仓本地午夜的瞬间
  跑，钉住值与断言值可能落到不同日期——但这是整类既有的写法（兄弟用例同样如此），
  窗口只有毫秒级，本次不扩大改动范围。
- 更彻底的长期解法是给测试注入 `Clock`（服务层 `slotStartToday`、SQL 口径一起注入），
  属于生产代码改造，需单独立项，不在本次修复范围内。
