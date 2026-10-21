# 子任务会话可以「静默早退」：worker 死了但车道仍显示 running，车道位空转最长 60 分钟（2026-10-21 取证）

**优先级：P2 —— 吞吐损失（不是正确性缺陷）。** 不影响代码正确性，代价是**一个车道位白等 60 分钟**。

## 现象

2026-10-21 12:00 tick 派发的 4 条车道里，`6.2.2.2 需求合并与批量` 的 worker **会话在开工约 4 分钟后直接结束**
（`task` 返回 `succeeded`，但**最终输出只有一段「我在读蓝图 / 在读上游实现」的叙述**，
没有任何交付报告）：

- worktree `git status --porcelain` = **空**（**零个文件改动**）
- worktree `git log --oneline -1` = `d84939b4`（**零个新 commit**）
- `lane.mjs land` **没被调用** ⇒ 车道状态仍是 `running`
- 最后一次心跳停在 **12:45:04**（note:「读蓝图 6.2.2.2 与上游 6.2.2.1 实现中，依赖自检进行中」）

## 为什么调度器不能立刻从心跳看出它死了

`lane.mjs` 的两条判活口径（`tools/module-progress/lane.mjs`）：

| 判据 | 口径 | 本例 |
| --- | --- | --- |
| `laneIsStale` | 心跳距今 > `TTL_MINUTES` = **90 分钟** | 12:45 + 90min ⇒ 14:15 才算 stale |
| `laneIsUnclaimed` | `!sessionId` **且** 心跳距今 > `CLAIM_GRACE_MINUTES` = **60 分钟** | 12:45 + 60min ⇒ **13:45** 才算孤儿 |

两者都**要等 60 分钟以上**。而 `CLAIM_GRACE_MINUTES` 的设计意图是
「心跳才是判活依据：活着的 worker 绝不会被误判成孤儿」（25 分钟心跳周期 vs 60 分钟宽限），
**这个取舍对「慢 worker」是对的，对「会话已经死了」是错的** ——
**会话死亡这个信号只存在于调度器自己的 task 句柄里，不在 lanes.json 里。**

⇒ 结果：**这条车道位从 12:45 到 13:45 整整一小时既不开发也不回收**，
而 `plan` 的 `orphaned` 在这期间为 0 ⇒ 下一轮 `plan` 还会照常排出**新车道**，
于是「在途车道数」在无人察觉的情况下悄悄越过了 `RUNNING_QUOTA`。

## 本轮的实际处置（已做）

调度者**没有等 60 分钟**，而是直接取证后重派：

1. `task_query` 确认该 subagent 状态 = `succeeded`（会话已结束，不是还在跑）
2. worktree **零改动 + 零 commit** ⇒ **不存在「双 writer」风险**，可以安全重派
3. 重新取闸 → 用**同一份任务书 + 一段「这是重派、先确认 worktree 干净且 HEAD 正确」的说明**重派
   → 新 worker 第一条心跳即回报「worktree 干净、HEAD=d84939b4」
4. 释放闸

重派后 4 条车道心跳全部新鲜（距今 0.4–7.5 分钟），车道路由恢复正常。

## 建议（供后续轮次参考，**未改代码**）

1. **调度器每轮先看 `task_query` / `task_output` 的终态**，
   而不是只看 `lanes.json` 的心跳。
   **subagent 终态 = `succeeded` 但该车道既没 `land` 也没新 commit ⇒ 这条车道已经死了**，
   应当**立即重派**，不要等 60/90 分钟的孤儿判据。
2. **任务书里加一条「开工 10 分钟内先发一条心跳」**（本轮重派时已加进 `6.2.2.2` 的任务书），
   这样「会话早退」和「worker 还在跑但没写代码」至少在心跳上可区分。
3. **若要改 `lane.mjs`**：可以考虑让 `plan` 接受一个「已知死亡车道」的清单，
   或把 `CLAIM_GRACE_MINUTES` 从 60 降到 30（仍高于 25 分钟心跳周期一倍，容错够用）。
   ⚠️ 但**降低宽限是有代价的**（慢 worker 被误判成孤儿的概率上升），
   **这是用户的决定，调度者不得自行改**。

## 关联

- `tools/module-progress/lane.mjs` —— `laneIsStale` / `laneIsUnclaimed` / `CLAIM_GRACE_MINUTES` / `TTL_MINUTES`
- `tools/module-progress/PIPELINE.md` —— 「心跳：每 25 分钟续一次，超 90 分钟失联会被判定崩掉并回收重派」
- 本轮批次说明：`docs/sessions/2026-10/2026-10-21-batch-49-brand-authorization.md`
