# 跨时钟域的第 2 例：DB **CHECK 约束**里比较了 JVM 时钟与 DB 时钟 ⇒ 生产上会间歇拒掉一次合法的履约步完成（2026-10-21 取证）

**优先级：P0 —— 这不是测试脆弱性，这是**生产正确性**缺陷。**

**状态：本轮已派修复车道（`fix/9-3-3-1-clock-domain`，模块 9.3.3.1 履约请求，trade 服务，迁移 `V211`）。**

## 现象

2026-10-20 23:00 tick 的 L2 **第一轮**后端红，trade 1494 例里红 **1 例**：

```
[ERROR] Tests run: 33, Failures: 0, Errors: 1 -- in com.starcon.trade.FulfillmentRequestIntegrationTest
[ERROR] FulfillmentRequestIntegrationTest.完整走通一次履约_从建单到完成:417
  » DataIntegrityViolation: new row for relation "fulfillment_request_step"
    violates check constraint "fulfillment_request_step_check3"
```

**隔离复跑 33/33 全绿** ⇒ 典型的间歇失败。
**本批在 trade 上一个文件都没改**（`git diff --name-only 8bcfc215..d6084add -- backend/services/trade/` → 0 个文件），
近期也没有并入 `main` 的 merge commit ⇒ **不是本批回归**。

## 根因（逐条取证）

1. **那条约束到底是什么**：用一张只含 V152 同款 CHECK 的探针表在 PG 16 上实测，
   表级 CHECK 的编号规则是「第 1 条叫 `<表>_check`，第 2 条 `check1`……第 4 条才是 `check3`」⇒
   **`fulfillment_request_step_check3` = `CHECK (finished_at IS NULL OR finished_at >= started_at)`**。

2. **同一行的两个时间戳来自两个不同的时钟源**：
   - `started_at` ← **JVM 墙钟**：`FulfillmentRequestService.markStepRunning(...)` 传 `OffsetDateTime.now()`；
   - `finished_at` ← **DB 时钟**：DB 函数 `complete_fulfillment_step(...)` 里写 `finished_at = clock_timestamp()`。

   ⇒ **跨时钟域比较**。JVM 比 DB 快哪怕几十微秒，约束就违约。

3. **失败行就是证据**：`started_at = 2026-10-20 23:29:54.130301+08`，
   `finished_at = 2026-10-20 23:29:54.130226+08` ⇒ **JVM 比 DB 快 75 微秒**。

4. **本机实测 PG 容器与宿主时钟的差**（5 组配对采样，`docker exec psql -c "select clock_timestamp()"` 对 `date -u`）：
   ```
   host=15:38:03.224051  pg=15:38:03.278     (PG 快 ~54ms)
   host=15:38:03.291385  pg=15:38:03.343     (PG 快 ~52ms)
   host=15:38:03.353972  pg=15:38:03.409     (PG 快 ~55ms)
   host=15:38:03.419742  pg=15:38:03.475     (PG 快 ~55ms)
   host=15:38:03.491360  pg=15:38:03.540     (PG 快 ~49ms)
   ```
   稳态下 PG 比宿主快 ~50ms ⇒ 约束**通常**满足；
   但重负载下（本轮 L2 与 4 条车道的 L1 并发，**PG 峰值 19 个容器**）宿主时钟可能瞬时回跳 ⇒ 约束被击穿。

5. **第二轮 L2 没复现**（机器空载，trade 8:20 min SUCCESS / 0 failures）⇒ 印证了「负载/时钟相关」。

## 为什么这比上一例严重

上一例（batch-38 记的 `ListingPublishJobIntegrationTest.dispatchedAtRoundTrips`）是**测试断言**假红 ——
只浪费一轮流水线。
**这一例是 DB 约束**：生产上一次**完全合法**的「履约步完成」写入会被**拒掉** ⇒ 履约流程可能卡在 RUNNING。

## 修法的硬要求（派给修复车道的）

- **同一行的 `started_at` 与 `finished_at` 必须来自同一个时钟源**。
  两条路线二选一：两个都取 **DB 时钟**（`started_at` 改由 DB 生成），
  或两个都取 **JVM 时钟**（`finished_at` 由服务层算好传进 DB 函数）。
- ⚠️ **不许**简单放宽 / 删掉那条 CHECK —— 它在防「结束早于开始」这个**真实**错误，
  放宽等于把真 bug 放进来。
- `elapsed_seconds` 必须与这两个时间戳**自洽**（现在它在 JVM 侧用 `Duration.between(started, finished)` 算，
  时钟倒挂时它会算成**负数**）。
- 必须补一条**确定性复现**时钟倒挂的回归测试（不要靠运气），并**断言写回的值**。

## 附：这一族缺陷的第 1 例（同一类，不同后果）

见 `docs/backlog/2026-10-2026-10-20-cross-clock-domain-test-assertion.md` ——
`ListingPublishJobService` 写 `dispatched_at = now()`（PG 的**事务开始**时刻），
测试拿 JVM 的 `OffsetDateTime.now().minusSeconds(2)` 去比，只留 2 秒余量。
已派修复车道（`fix/4-3-1-1-clock-assert`，模块 4.3.1.1 一键刊登）。

⇒ **家族规律：凡是把「DB 产生的时刻」与「JVM 产生的时刻」放在同一个比较里，都是雷。**
