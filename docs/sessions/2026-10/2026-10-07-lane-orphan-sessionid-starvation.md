# 调度器修复：plan 把活着的车道当成孤儿，流水线卡死在 4 条

日期：2026-10-07 20:22–20:34（B 组 tick）
集成头：`a2a79b7`

## 背景

19:52 那条 tick 记过一个现象：`plan --lanes 4` 返回 `newlyPlanned: []`，
`toDispatch` 里是上一轮那 4 条、`redispatched` 递增。当时记下的判据是
「空 ⇒ 槽位已满 ⇒ 一条都不派」。**这次同一现象再次出现，结论对，但理由是错的。**

## 现象

20:22 tick：`lanesRunning: 4`，4 条车道 `hbAge=0min`（刚续过心跳，worker 明确在跑），
`plan --lanes 4` 仍返回 `newlyPlanned: []`，且这 4 条 `redispatched=2`。

## 根因

孤儿判据：

```js
state === 'running' && (!l.sessionId || laneIsStale(l));
```

cron 派发用的是 `plan --lanes 4`，**不带 `--session`**，于是每条车道的
`sessionId` 恒为 `null` —— `!l.sessionId` 对**所有**车道恒为真。

后果两条：

1. 正在跑的车道每轮被判成孤儿 → `reDispatched` 单调递增，`toDispatch` 永远是同 4 条。
   调度者照单全派就是同一 worktree 双 writer。
2. `pickModules(count - orphaned.length)` = `pickModules(4 - 4)` = **0**，
   `newlyPlanned` 恒为 `[]`，一条新车道都排不出来，**且不报任何错**。

`lanes.json` 实测四条车道的 `sessionId` 全是 `null`，坐实了这一点。

## 修法（commit `a2a79b7`）

心跳才是判活依据（worker 每 25 分钟续一次，TTL 90 分钟）。
新增 `laneIsUnclaimed()`：只有「既没有 `sessionId`、**又**超过 60 分钟没心跳」
才算没人接手。60 分钟高于心跳周期（活着的 worker 不会被误判），
又低于 TTL（崩掉的车道比原来更快被回收）。超 TTL 的车道无论有没有 `sessionId` 都照旧回收。

生效后 `plan` 立刻排出 4 条真新车道：`7.7.1.1` / `7.8.1.1` / `8.1.1.1` / `8.2.1.1`
（V67–V70，`redispatched=0`）。

## 测试

新增 `tools/module-progress/lane-plan-orphan.itest.mjs`，7 条断言。
用 `plan --lanes 0` 取样：孤儿判定发生在建车道之前，`count=0` 时 `pickModules`
立即返回空，既拿到 `toDispatch`（= 孤儿集合）又不会真的 `git worktree add` / `pnpm install`。

**反向验证**：把判据临时改回旧逻辑重跑，3 条断言失败，其中反向断言打出
「待派发=1.1.1,1.1.2,1.1.4，其中新鲜的=1.1.1」—— 证明不是恒真空断言。

写测试时踩了两个坑，都记在这里：

- `plan` 在认领孤儿时会**顺手把 `heartbeatAt` 刷成当前时间**（这是正确行为）。
  所以「派发前是否新鲜」必须在跑 plan **之前**取样；跑完再读 state 只会看到
  全部都是新鲜的，那条反向断言会变成恒真。
- `plan` 会往 stdout 打 `fatal: Needed a single revision`（来自容错的
  `git rev-parse --verify` 探测分支是否存在）。**这是既有噪声不是错误**，
  脚本退出码仍是 0，别照着它去查。

`merge-union` / `lane-conflict-reconcile` / `lane-plan-orphan` 三个 itest 全绿。

## 另一个坑：闸的 owner PID 恒为死进程

`gateAcquire` 写进 `.dev-agent/dispatch.gate/owner` 的是**那条
`node lane.mjs gate acquire` 命令自己的 pid**，而这条命令写完就退出。
所以 `ps -p <owner>` 必然查不到 —— **不能据此认为「持闸者已死」**。

20:22 我据此推断闸是孤儿、差点去动正在跑的 merge；实际上 A 组 tick 一直在正常工作，
20:23:24 提交了 `449da2d`（7.4.2.2 并入）。唯一有效的陈旧信号是 `gate status`
的 `ageSeconds` 与 600 秒 TTL。

## 本轮其他动作

- A 组 20:15 tick 在 `api-proxy.ts` 上与 7.4.2.2 冲突并手工解了 `449da2d`。
  我独立复核了该解法：token 级比对确认**纯增量**（没有丢任何已登记路由族），
  再用 `stocktake-variance-model.ts` 的 13 个真实调用点逐条打正则，全中。
  （首次跑出 3 个 MISS 是我的取样脚本把 `${query(...)}` 里的嵌套括号吃掉了，不是正则的问题。）
- gc 清掉 7.4.2.2 worktree。台账完整性：465 行、0 重复 ID。

## 遗留风险

**并发从设计的 8 条涨到 12 条。** 修好之后 A 组 20:30 tick 也排出了 4 条
（`7.7.1.2` V71 / `8.3.1.1` V72 / `8.3.4.2` V73 / `8.5.1.1` V74）。

观测时 load 3.00 且在下行、CPU 78% idle、Docker 1.3G/11.67G，都还没到
cron 任务书里的降并发阈值（load > 8）。但 `PhysMem` 已 23G used、
**compressor 9417M**、unused 仅 384M —— 内存压力偏高。12 条车道一旦同时进入
`./mvnw verify`，这是最可能先炸的维度。

按任务书规则现在不该杀车道（未达阈值），但下一轮若出现 `RESOURCE_BLOCKED`
或 load 持续 > 8，应优先砍掉 7.x 那批（同时段开跑、verify 最重）。
