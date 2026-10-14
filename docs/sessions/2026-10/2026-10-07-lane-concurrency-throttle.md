# 并行车道并发降档（配额 8→6，load 阈值定 12）

时间：2026-10-07 21:34–21:45 | 触发：用户「并发是不是太高了？适当调低 load 并发吧」

## 决策摘要

把并行车道的并发从「全局 8 条 / 每 tick `--lanes 4` / load 阈值 8」改为
「**全局配额 6 / 每 tick `--lanes 2` / load 阈值 12**」，A、B 两个 tick 的 prompt **同步修改**。

## 取证：为什么 8 是错的

19:17 那次定 8 条的采样是 **3 条车道、load 2.55 / 10 核**，据此外推「内存和磁盘都不是墙，CPU 才是」。

21:34 复测：**只剩 2 条车道**并发跑 `mvnw verify`，load 就是 **22.06 / 9.93 / 11.53**（1min 在爬升）。

同刻进程取证：

| 负载源                                                | 读数                                         |
| ----------------------------------------------------- | -------------------------------------------- |
| `java` (openjdk-25)                                   | 160% CPU（刚起 13s 的 maven）                |
| Docker VM (`com.apple.Virtualization.VirtualMachine`) | 56% → 93% CPU / 614MB RSS                    |
| Testcontainers                                        | 新起 2 个 `postgres:16-alpine` + 2 个 `ryuk` |
| WeChat                                                | 51% → 45% CPU                                |

**结论：错在采样时机，不在算术。** 单条车道并发 verify 的真实成本 =
1 个 maven JVM + N 个 surefire fork JVM + 1 个独立 PG 容器 + 1 个 ryuk。
load 不随车道数线性涨，而是随**同时处于构建高峰的车道数**阶跃涨。

## 取证：为什么 load 阈值不能低于 12（这是本轮最容易改错的一处）

我第一版把 load 阈值设成 6，实测**打脸**：

```
21:39:58  lanesRunning: 0   load 1min 8.12
21:40:43  lanesRunning: 0   load 1min 7.60
21:41:28  lanesRunning: 0   load 1min 7.04   ← 仍在降，但降不下去
```

**`lanesRunning: 0` 时机器并不空闲** —— 仍有 **4 个 java 进程 + 2 个 Testcontainers PG 容器**存活，
Docker VM 常年 56%–93% CPU。真基线约 4–8。

于是取 **12 = 核数 × 1.2**：空载能过（7 < 12），2 车道构建高峰被拦（22 > 12）。

⚠️ 阈值 6 连空载都超 —— 那不是「保护 CPU」，是**把流水线整个停掉**。
`running` 配额才是防无上界增长的**主闸**；load 阈值只是辅助闸，**不该比空载基线还低**。

## 关键 trade-off

- 吞吐会下降（约 −50% 派发量），换取不再压垮 10 核机器。硬停机 00:00 前还剩 8 次 tick。
- 没有杀任何车道 —— 杀车道会丢工作量，也不是调度器该干的事；只**停止继续加**。
- 只调低**增量**（`--lanes`），不动 tick 间隔（15 分钟）。若仍嫌高，下一步是 tick 拉到 30 分钟。

## 变更文件清单

- `tools/module-progress/PIPELINE.md` —— 配额段落重写，补「配额为什么 8→6」「load 阈值为什么 12」两节
- cron `f029d0bf`（B 组，`7,22,37,52`，active）—— prompt 全文更新
- cron `fcf14c15`（A 组，`0,15,30,45`，**paused**）—— prompt 全文更新

**A 组虽已暂停仍必须同步改**：只改一边等于没改，重新 enable 就会跑回旧参数。

两个 prompt 里都写了「不要把 `--lanes` 改回 4 / 不要把 load 阈值改到 12 以下」，
并附本次取证数据，防止下一任调度 agent 凭直觉调回去。

## 顺带修掉的两个流水线缺陷

1. `plan` 有副作用（会真的建分支 + worktree），prompt 里补了「**不要用 `plan` 试探**」。
2. 补了主工作区 `UU` 冲突残留的处置：不要自行 `git merge --abort`，点名冲突文件等下一轮。

## 后续待办

- [ ] 00:00 收尾模式会关闭两个 tick —— 确认 A/B 都真的 `enabled: false`（历史上关不掉过）
- [ ] **Testcontainers 容器回收不及时**：`lanesRunning: 0` 时仍有 2 个 PG 容器存活，持续吃 Docker VM CPU。
      可考虑给 worker 任务书加「verify 跑完确认容器已回收」，或在 `gc` 里加一步 `docker ps` 巡检
- [ ] 若今晚观察到全程 STOP（吞吐为 0），把 load 阈值从 12 放宽到 16，而不是回退 `--lanes` 到 4
