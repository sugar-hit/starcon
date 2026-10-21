# 跨时钟域的第 3 例：平台可变更规则的 `effective_from` 默认值用 JVM 墙钟，而生效窗口判定在 DB 时钟上（2026-10-21 取证）

**优先级：P1 —— 生产正确性缺陷（不是测试脆弱性），但窗口很短、不像 9.3.3.1 那样会间歇拒掉合法操作。**

**状态：测试侧已修（车道 `fix/9-1-1-3-rule-effective-window`，并入 `3fe19e10`）；生产侧这条默认值**未修**，留待下一轮单独派车道。**

## 现象

2026-10-21 08:00 tick 的**第二次** L2 后端红：trade `OrderChangeCancelIntegrationTest` 40 例里红 6 例
（3 failures + 3 errors），报错统一是 `400 没有登记「减少数量」的可变更规则`。
红的全部是**用到规则夹具**的用例（该类 40 例里只有 6 例用到）⇒ 规则夹具整体失效。

## 归因（逐条实测）

1. **不是本批回归**：`git diff --name-only dd607c5b 1755c23b` 对 `OrderChangeCancel*` / `order_platform_change_rule` 零命中；
   本批对 trade 的新迁移 V246/V249/V250/V251 只建新表，RLS/GRANT 循环只作用于各自新表的显式清单。
2. **基线绿**：合并前 `dd607c5b` 上单跑该类 ⇒ 40/40 绿。
3. **同树绿**：`1755c23b` 上单跑该类 ⇒ 40/40 绿；跑完整 trade 模块 94 类 / 1768 例 ⇒ 全绿。
4. **不是权限问题**：失败现场用超管连接查 `order_platform_change_rule` ⇒ 规则行**确实存在**，
   `platform_code=AMAZON` / `shop_code=*` / `change_kind=QUANTITY_DECREASE` / `effective_to=NULL` 与查询条件逐项一致；
   `pg_policies` 显示 `isolation` 策略正常、`relrowsecurity=t` `relforcerowsecurity=t`
   ⇒ 行是被 WHERE 里的 `now()` 窗口排除的，不是被 RLS 挡掉的。
5. **等价注入复现（决定性）**：把夹具种子的 `OffsetDateTime.now()` 改成 `OffsetDateTime.now().plusSeconds(1)`
   （**等价于「DB 时钟比宿主慢 1 秒」**）⇒ 单跑该类**复现出与 L2 完全一致的 6 例红**：同样 3 failures + 3 errors、
   同样 6 个用例名、同样报错文本。

## 根因

**测试用 JVM 墙钟写 `effective_from`，服务端用 DB 时钟 `now()`（= `transaction_timestamp()`）判「规则是否落在生效窗口内」** ——
**跨时钟域**比较，只有「两个时钟恰好同步」才成立，健康态余量只有**插入到查询之间的约 50ms**。
L2 全量跑时机器上有 43 个 Testcontainers PG 容器在跑、load 约 5/10 核，Docker 虚拟机时钟相对宿主滞后数百毫秒
⇒ 刚种下的规则被判成「还没生效」。

> 健康态偏差实测（`extract(epoch from clock_timestamp())` 对宿主 `date +%s.%N`）：DB 侧在 ±20ms 内且略快 ⇒ 余量确实只有几十毫秒量级。

## 已修（测试侧，只改测试、零生产代码、零迁移）

- 种子 `effective_from` 改成**由 DB 自己算**的 `(now() - interval '1 minute')`（同一时钟域 + 1 分钟余量）；
- `createRule`/`updateRule` 调用**显式给 DB 时钟值**（服务层 default 是 JVM 墙钟、且 `updateRule` 会用它**覆写** `effective_from`）；
- 新增 **1 例可执行不变量**（种下去的 `effective_from` ≤ 查询时刻 DB `now()`、且与 DB `now()` 间隔 ≥ 30 秒 ⇒ 改回 JVM 墙钟必红）。

验证：目标类 41/41 绿；变异测试（种子改成「DB 时钟 + 1 小时」）复现 7 例红；三个时区各 41/41 绿；trade 全量 1769 例全绿。

## 未修（本条 backlog 的正题）—— 生产侧同类写入口

`backend/services/trade/src/main/java/com/starcon/trade/OrderChangeCancelService.java`：

- `createRule(...)`：`input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom()`
- `updateRule(...)`：同上，且 update 会用这个 default **覆写** `effective_from`

⇒ **真实 API 调用建出的规则**（`POST /trade/order-change/.../rules`）的 `effective_from` 写在 **JVM 墙钟**上，
而判定在 **DB 时钟**上 ⇒ 同样的跨域窗口：**规则刚建出来时可能「查不到」**。
在时钟同步良好时窗口只有几十毫秒（用户基本感知不到），但在容器/虚拟化环境下 DB 时钟滞后数百毫秒时会明显放大。

**建议修法**（下一轮单独派车道，**零迁移**）：
把这两处 default 从 JVM 墙钟改成 **DB 时钟**，二选一：
1. 在服务层用 `select now()` 取时刻（与仓库既有做法一致：服务端已大量用 `select now()` 拿「同源 DB 时钟」）；
2. 或引入 `java.time.Clock` 注入，服务层不再直接调 `OffsetDateTime.now()`。

**注意**：改这两处会改变「刚建规则立刻生效」的行为在**跨时钟域**下的表现，属于**收敛而非改变契约** ——
判定口径（DB `now()`）不动，只把写入侧对齐到同一时钟域。

## 同族

- `docs/backlog/2026-10-2026-10-21-trade-fulfillment-clock-domain-db-constraint.md` —— 跨时钟域第 2 例（9.3.3.1 的 DB CHECK，已修，P0）
- `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` —— L2 间歇假红的通用归因线索
- 本 tick 的 5.5.1.1 修复（`docs/sessions/2026-10/2026-10-21-price-history-clock-window-fix.md`）—— 跨时钟域第 1 例（测试侧，已修）

**建议**：下一轮做一次**全仓扫描**，找「JVM 墙钟写时刻 + DB `now()` 判窗口/比较」的全部形状，不只修被 L2 撞到的那几个。
