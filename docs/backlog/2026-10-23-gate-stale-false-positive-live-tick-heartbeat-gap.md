# 调度闸的 `stale` 判据会把**活着的**流水线 tick 判成僵死闸

- **日期**：2026-10-23
- **发现于**：main→集成头同步第 52 轮（`docs/sessions/2026-10/2026-10-23-1445-main-sync-round52.md`）
- **严重度**：高（会让两个 agent 同时在集成头 worktree 里改同一个分支）
- **影响面**：`tools/module-progress/lane.mjs` 的调度闸（`gateAcquire` / `gateStatus` / `gateRelease`）
- **是否已修**：否 —— 同步任务「不改任何业务代码」是协议硬边界，本轮只取证

## 现象

同步任务按协议第 1 步取闸，先读 `gate status`：

```
{"gate":"held","ageSeconds":3118,"ttlSeconds":4200,"stale":false}   # 13:23 起手
...
{"gate":"held","ageSeconds":4200,"ttlSeconds":4200,"stale":true}    # 14:33 判定为僵死闸
```

按协议，`stale:true` ⇒ 可以 `acquire` 接管。**但持闸者当时是活的。**

接管后 2 分钟内，持闸者（流水线 tick，batch-75）在**同一个 worktree** 里：

- 14:35:51 `reset: moving to HEAD` —— 把同步任务正在冲突态的 merge 整个抹掉；
- 14:35:51–14:35:56 并入 4 条车道（`378f3b3c` 1.2.1.2 / `2f7a63f7` 1.2.2.2 / `b99aad7c` 1.4.1.2 / `7725fdd0` 1.5.1.1）；
- 14:38:32 启动 batch-75 L2（`mvnw verify`，55 分钟，全绿）。

⇒ 同步任务与流水线**同时**操作 `integration/module-dev`，同步任务的合并态被 `reset` 抹除。

## 根因

`lane.mjs` 的闸龄**不是**按 `owner` 文件算的，而是按 **`GATE_DIR` 目录 mtime** 算的：

- `tools/module-progress/lane.mjs:575`（`gateAcquire`）
  `const age = (Date.now() - statSync(GATE_DIR).mtimeMs) / 1000;`
- `tools/module-progress/lane.mjs:612`（`gateStatus`）同样按目录 mtime
- `tools/module-progress/lane.mjs:590`（`gateRelease`）
  `rmSync(GATE_DIR, { recursive: true, force: true })` —— **无条件删整个闸目录**

tick 的续期机制是**往 `GATE_DIR` 里写 `keepalive-<HHMMSS>` 文件**（写文件会刷新目录 mtime，这就是它的续期手段）。
但 tick **只在它自己正在执行步骤时**才写 keepalive。

实测时间线（本地时间）：

| 时刻         | 事件                                                          |
| ------------ | ------------------------------------------------------------- |
| 13:23:14     | tick `gate acquire` ⇒ 闸目录建立                              |
| 13:23–14:35  | tick 在等 4 条车道交付（车道寿命中位数 35 分钟）**一次 keepalive 都没写** |
| 14:33:14     | 同步任务 `gate status` ⇒ `age=4200s > TTL=4200s` ⇒ `stale:true` ⇒ 接管 |
| 14:35:29 起  | tick 恢复活跃，**往同步任务的闸目录里**写 keepalive（它完全不知情） |

⇒ **TTL(4200s = 70 分钟) < tick 的心跳空窗（实测 72 分钟）** ⇒ 闸被活着的 tick 持有时，
`gate status` 照样报 `stale:true` ⇒ 同步任务按协议合法接管 ⇒ **互斥失效**。

## 判定依据（为什么不能用别的信号替代）

- `owner` 里的 PID **不是**存活证据：`gate acquire` 是短命进程，写完 `owner` 就退出
  （`SYNC-MAIN.md` 已记录）。实测持闸 tick 的 `owner` pid `73525` 查无此进程，但 tick 本身活着。
- 闸外取证（`ps`）也不可靠：tick 的主体是 agent 会话，不是能按命令行识别的常驻进程。
- `keepalive-*` 文件**是**可靠证据，但只反映「tick 最近一次活跃动作」，不覆盖它的等待期。

## 建议修法（三选一或组合）

1. **让 tick 在等待车道期间也续期**（最小改动、治本）：
   在 tick 的调度循环里加一个心跳（哪怕 60 秒一次）写 `keepalive-<HHMMSS>`，
   让目录 mtime 不出现 > 70 分钟的空窗。
2. **把闸龄判据从「目录 mtime」改成「最新 keepalive 文件的时间戳」**：
   最新 keepalive 距今 > TTL ⇒ 判持闸者僵死；否则持有中。
   这样判据只看**最后一次活跃证据**，语义更贴切。
3. **错开两个 agent 的工作窗口**（调度层缓解）：
   例如同步任务只在「最近一次 keepalive 超过 Y 分钟」时才接管，或加「连续两次观测到 stale 才接管」的确认窗口。

⚠️ 修 1 或 2 时**同时**要修 `gateRelease` 的无条件 `rmSync`：
持闸者收尾时会把**别人**的闸删掉（协议已警告「没拿到过闸就绝对不要 `gate release`」，
但同样的坑在持闸者一侧也成立 —— tick 的 `gate release` 会删掉同步任务刚接管的闸）。
建议 `gateRelease` 改成「只删自己持有的闸」（比对 `owner` 里的 pid / token）。

## 复现与验证

```bash
cd /Users/guan/Projects/starcon-worktrees/integration
node tools/module-progress/lane.mjs gate status
ls -t .dev-agent/dispatch.gate/          # 看 keepalive-* 的时间戳分布
```

判定「假阳性」的三条特征同时出现即命中本 bug：

1. `stale:true` 但 `GATE_DIR` 里存在**很新**的 `keepalive-*`（说明 tick 在动）；
2. `keepalive-*` 的时间分布存在 > TTL 的空窗（说明 tick 在「等」而不是「死」）；
3. 接管后很快出现别人在同一个 worktree 里 merge / build 的痕迹。

## 关联

- 同步协议：`tools/module-progress/SYNC-MAIN.md`（第 1 步取闸 / 等闸）
- 流水线协议：`tools/module-progress/PIPELINE.md`（第 3.3 节取闸顺序同样按 `stale` 判）
- 第 51 轮曾记录「闸外取证：surefire 进程在跑 ⇒ 持闸者活着，不是僵死闸」——
  说明这个问题**已经被撞见过一次**，但当时用 `ps` 判活，**没有定位到判据本身的缺陷**。
  本轮把根因钉到了「TTL < 心跳空窗」这一条上。
