# 派发顺序：L2 必须排在 `plan`/派发**之前**（2026-10-24 batch-86 取证）

**优先级：P1**（不修会导致成片假红，浪费一轮 40–60 分钟的发热高峰）

## 现象

`PIPELINE.md` 的步骤字面顺序是：

- 第 4 步：`plan --lanes N` → 派发 worker（后台跑 L0 编译闸 + L1 模块闸，**L1 会起 Testcontainers PG**）
- 第 5 步：L2 批量闸（`mvnw verify` 全量，**也会起 Testcontainers PG**）

⇒ 按字面顺序执行，**L2 与刚发车的 4 条车道的 L1 并发跑**。

## 为什么这是真问题

`PIPELINE.md`「最高优先级禁令」里已经写死了后果：

> **L2 不得与在途车道的 L1 并发跑** —— 多套 Testcontainers PG 互相抢资源，
> 2026-10-19 20:00 那轮实测：L2 与 4 条车道的 L1 并发时 PG 峰值 16，
> 后端 1364 例里 3 failures + 24 errors **全是假红**（`seed()` 503 / 连接拒绝）。

batch-86 实测的 PG 峰值：仅 L2 自己一轮 `mvnw verify` 就同时存在 **9 个**带
`org.testcontainers.sessionId` 标签的 `postgres:16-alpine`（surefire 多个 fork 各自持有一个
Testcontainers 上下文，ryuk 约 4 分钟回收一轮）。
⇒ `PG_CONTAINER_LIMIT=6` 在「L2 + 4 条车道 L1」并发的形状下**必然被击穿**，
资源争抢会以 `seed()` 503 / 连接拒绝的形式**伪装成回归**。

## 本轮采用的安全顺序

```
取闸 → merge-ready（并入已落地车道）→ sync → gc
     → 静态预检(5.1) → L2 全量(5.2)          ← 机器上没有任何在途车道的 L1
     → 写说明 → commit → push (5.3)
     → plan --lanes 4 → 派发新车道（后台跑 L0/L1）
     → gate release
```

派发放在最后 ⇒ 本轮 L2 独占机器，**不会与任何在途车道的 L1 抢 PG**。
新车道的 L0/L1 在本轮结束后的后台窗口里跑，由下一轮 tick 回收。

## 为什么不需要 batch-84 那种「待发车车道续心跳守护」

batch-84 的 `batch84-keepalive.sh` 存在，是因为它把 `plan` 放在 L2 **之前**：
plan 会立刻把 4 条车道标成 `running`，而接下来 41–56 分钟都在跑 L2，
车道不续心跳就会超过 90 分钟失联阈值被误判崩掉。

改成「L2 先、派发后」之后：派发发生在 L2 与 push **之后**，本轮随即结束，
worker 从发车起就有完整的 90 分钟窗口跑 L0/L1 并自行每 25 分钟续心跳
（子任务模板第 11 条），**不再需要额外守护进程** —— 也顺带消掉了
`2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` 记录的孤儿守护风险。

## 后续待办

- [ ] 把 `PIPELINE.md` 第 4 步与第 5 步的**执行顺序**改成「L2 在前、`plan`/派发在后」，
      并把上面的 PG 峰值实测（9 个）补进「已知互相破坏形状」段。
- [ ] `plan` 落盘状态时区分 `planned`（已排队未发车）与 `running`（已发车），
      让心跳 TTL 只对 `running` 生效 —— 从根上消除「待发车车道被误判崩掉」这个场景。
- [ ] **下一轮 tick 的零合并护栏**：`merge-ready` 并入 0 条车道时**不要跑 L2**
      （没有新代码要验证，跑一次全量只是白烧一次发热高峰）；本轮若沿用旧顺序，
      下一轮 tick 会在「4 条车道还没落地」时被迫做一次无意义的全量验证。
