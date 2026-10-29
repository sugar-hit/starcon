# 2026-10-26: 集成头同步 main 第 78 轮 —— 无新提交，未同步（r103 孤儿 keepalive 死锁接管）

## 摘要

本轮**结果是无新提交、未同步**：取到闸后 `git fetch origin --prune`，
`git merge-base --is-ancestor origin/main integration/module-dev` ⇒ `NO_UPDATE` ——
`origin/main` 的 tip `6522c497`（2026-10-23「chore: 同步集成分支最新进度」）已是集成头 `f64946c9` 的祖先，
本轮**没有 merge、没有预检、没有 push**，并入的 main 提交数 = **0**。

本轮的主要成本在闸：起手 `gate status` = `held / ageSeconds 67 / stale=false`，
但**持闸者早已死亡**，留下孤儿 keepalive 守护每 4 分钟刷新闸目录 mtime ⇒ `stale` 永远不会转 `true`
⇒ **纯靠等是等不到的**。本轮按 P0 登记在案的「持闸者已死 ⇒ 停掉孤儿守护后接管」纪律接管了闸 ——
这是同步任务**第二次**使用这条纪律（第 76 轮首次使用）。

## 决策 / 结论

1. **闸的现状是「孤儿 keepalive 死锁」，不是「活着的流水线 tick 持闸」。**
   持闸 tick `mvs_3f0ac6f12e5b4f65840f734f0af804ee`（round-103 / batch-102 流水线）在 **11:27:52**
   崩在传输层（`net::ERR_HTTP2_PING_FAILED`），但它 nohup 出来的
   `.dev-agent/keepalive-r103.sh`（PID 80021）**每 4 分钟新建一个文件刷新闸目录 mtime**
   ⇒ `statSync(GATE_DIR).mtimeMs` 永远新鲜 ⇒ `ageSeconds` 永远 < TTL(4200) ⇒ `stale` 永远 `false`
   ⇒ **每一轮 tick 都会撞闸、每一轮都零派发**。

2. **六重独立证据（与 P0 登记表的判据逐条对齐，全部指向「持闸者已死」）**：

   | # | 证据 | 实测 |
   | --- | --- | --- |
   | 1 | 闸 `owner` 文件里的 PID | `80008` ⇒ `ps -p 80008` **查无此进程** |
   | 2 | 持闸 tick 的会话状态 | `mvs_3f0ac6f1…` = **`error`**（`net::ERR_HTTP2_PING_FAILED`，50001），最后活动 **11:27:52** |
   | 3 | 机器上有没有在跑构建/测试 | `pgrep -fl 'Launcher\|surefire\|vitest\|next build\|mvnw'` ⇒ **0** |
   | 4 | 持闸 tick 自己的产物 | `isolate-channel-r103.log`（11:03:15 BUILD SUCCESS）、`isolate-channel-r103-fix.log`（11:04:31 BUILD SUCCESS）；**无任何 r103 L2 driver 日志** ⇒ 它崩在 L2 之前 |
   | 5 | 闸归属是否变更过 | `owner` mtime 恒为 **11:01:41**（持闸时刻）⇒ 本轮**不存在任何交接窗口** |
   | 6 | 它留下的在途车道 | `1.3.2.2` / `1.1.2.2` 两条车道 `state=running` 但 `sessionId=null`、心跳停在 **11:07**（已 85–86 分钟）、**无任何 worker 进程** ⇒ 双孤儿 |

   > 📌 第 5 条是本轮与**第 77 轮的关键分歧点**：第 77 轮测到闸龄 `211 → 31` 骤降，判定为**真实交接窗口**
   > （持闸者释放、下一 tick 同秒重新 acquire），于是**不碰闸**、纯竞态就取到了闸。
   > 本轮闸龄的重置**不是交接**，而是 keepalive 每 4 分钟新建文件造成的**假重置**
   > （实测 keepalive 文件时间戳：12:09:59 / 12:13:59 / 12:17:59 / 12:21:59 / 12:25:59，**严格 4 分钟等距**）
   > ⇒ **等是等不到的**，只能按 P0 纪律接管。

3. **接管用的是守护脚本自身文档化的停止机制**（不是 `rm -rf` 硬闯、不是 `kill -9`、不是清闸）：

   ```bash
   touch .dev-agent/STOP-KEEPALIVE-R103   # 脚本自己的停止标记
   kill 80021                             # 结束孤儿守护（父 wrapper 80018 随之退出）
   node tools/module-progress/lane.mjs gate release     # 脚本自带 API
   node tools/module-progress/lane.mjs gate acquire     # ⇒ {"ok": true, "gate": "acquired"}，退出码 0
   ```

   与先例一致：batch-84 / batch-101 / **第 76 轮**用过同一套 `STOP-KEEPALIVE` 机制。
   接管后 `gate status` = `held / ageSeconds 0 / stale false`（闸目录刚被 `mkdir` 重建）。
   本轮**没有启动自己的续期守护** —— 本轮只做只读取证 + 一篇文档提交，远短于 TTL(70 分钟)。

4. **等闸期间只做只读取证，未跑任何构建**：起手 `gate status` / `cat owner` / `ps` / `git log` /
   `git worktree list` / `mavis session list`。**竞态 `gate acquire` 跑了约 13 分钟（12:15:56 → 12:28:55）
   始终未命中**，这本身就是第 2 条「等不到」的实证。
   （`git fetch` 在拿到闸之后才跑 —— 协议明确「等闸期间连 `git fetch` 都先别跑」；
   本轮仅在**确认无任何构建进程**（证据 3）后、接管完成后才 fetch。）

5. **`origin/main` 无新提交，本轮零搬运。**
   `origin/main` tip = `6522c497`（2026-10-23 09:18:01），是集成头 `f64946c9` 的祖先；
   `git log --oneline integration/module-dev..origin/main` ⇒ **0 行**；`git diff --stat` ⇒ **空**。
   ⇒ 不 merge、不预检、不 push；**没有属于本轮的东西要推**。

6. **台账无变化**：`node tools/module-progress/sync.mjs --check` ⇒ `✓ 台账与蓝图一致`；
   写模式 `sync.mjs` ⇒ **18 卷 / 465 个 L4 模块**，**已交付 408 · 进行中 0 · 未开发 57**（完成率 87.7%）。
   写模式跑完工作区**仍然干净**（台账本来就与蓝图一致）。

7. **本轮不 push。** 本地集成头 `f64946c9` 比 `origin/integration/module-dev`（`1e3228b4`，第 75 轮）**领先 25 个提交**：
   23 个是流水线**刻意不推**的车道成果（`e36db01e` 提交信息明写「L2 红在 warehouse/channel/platform（三处均定性为假红），**本轮未 push**」），
   另 2 个是第 76 / 77 轮的会话文档提交。推本篇文档会**连带把它们推上去** ⇒ 那是流水线的决定，不是同步任务的决定。
   ⇒ 本轮只**提交本篇文档、不 push**，与第 76 / 77 轮同一处置。

8. **本篇文档必须 commit（不是留 untracked）**：batch-101 记录的「崩在 commit 前 ⇒ 未跟踪文件让
   `lane.mjs merge-ready` 的「工作区干净」检查不通过 ⇒ 并入通道堵死」**已连续复发两次**。
   本轮工作区此前干净，本篇若只落盘不提交就会**亲手制造第三次** ⇒ 提交说明与台账在同一个 commit。

9. **收尾已 `gate release`**，`gate status` = `free`、闸目录已不存在 ⇒ 下一轮流水线 tick 可正常取闸。
   收尾前另确认：**没有任何 keepalive 守护进程存活**（r103 孤儿守护已停，且未启动本轮自己的守护）。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` —— 本任务协议（本轮按它执行）
- `tools/module-progress/lane.mjs` —— 闸实现（`GATE_TTL_SECONDS = 4200`，判龄用闸目录 mtime；
  `gate release` 是无条件 `rmSync(GATE_DIR)`）
- `tools/module-progress/PIPELINE.md` —— 流水线协议（与 SYNC-MAIN.md 冲突时以它为准）
- `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` —— **P0 死锁登记与处置纪律**
- `docs/sessions/2026-10/2026-10-26-1020-main-sync-round76-no-new-commits.md` —— 第 76 轮同款接管先例（同步任务首次）
- `docs/sessions/2026-10/2026-10-26-1022-main-sync-round77-no-new-commits.md` —— 上一轮同步（NO_UPDATE，**未接管**，靠真实交接窗口）
- `docs/sessions/2026-10/2026-10-26-batch-101-gate-takeover-l2-and-dispatch.md` —— 流水线侧同款接管先例
- `.dev-agent/keepalive-r103.sh` / `.dev-agent/STOP-KEEPALIVE-R103` —— 本轮停掉的孤儿守护（脚本自带停止标记）
- `.dev-agent/l2/isolate-channel-r103.log` / `.dev-agent/l2/isolate-channel-r103-fix.log` —— 持闸 tick 自己的产物
- 本篇：`docs/sessions/2026-10/2026-10-26-1232-main-sync-round78-no-new-commits.md`

## 关键 trade-off / 讨论

**为什么本轮接管、第 77 轮不接管？**
两轮的闸表象相同（`held` + `stale=false`），但**闸龄骤降的成因不同**：
第 77 轮测到 `211 → 31`，且当时持闸者尚有活动 ⇒ 真实交接 ⇒ 不碰闸也能合规取到闸；
本轮闸龄重置来自 keepalive 严格 4 分钟等距的新建文件，且持闸者会话已是 `error` 状态 1 小时 ⇒
**没有任何活着的进程会来释放闸** ⇒ 竞态等再久也是白等（实测 13 分钟零命中）。
⇒ 判据不是「`stale=false` 就一律等」，而是**「`stale=false` 且持闸者是否还活着」** ——
这正是 P0 登记表要求「先取证再决定」的原因。

**为什么「停守护 + release + acquire」不算「清闸硬闯」？**
① 守护脚本**自己提供**了 `STOP-KEEPALIVE-R103` 这个文档化的停止标记；
② `gate release` / `gate acquire` 都是脚本**自带 API**，全程没有任何人肉 `rm -rf`；
③ 等满 70 分钟 TTL 让 `acquire` 自己走接管分支（`rmSync` + `mkdirSync`）**本质上仍是清闸**，与 P0 精神相悖；
④ 本轮**不做**全量 `mvnw verify`（发热高峰，属于流水线的 L2）。

**为什么不 push？**
「只 push 集成头、绝不 push main」是硬规，但「顺带把别人 L2 红的成果推上远端」不是本任务的授权范围。
本地领先的 25 个提交里，23 个是流水线明确标注「本轮未 push」的 L2 红成果 ⇒ 等流水线 L2 转绿后由它自己推。

**为什么不代流水线回收那两条孤儿车道？**
`1.3.2.2` / `1.1.2.2` 的心跳已超 60 分钟且 `sessionId` 为空 ⇒ 按 `laneIsUnclaimed` 判据**下一轮流水线 tick
会自动把它们当孤儿重派**。本任务**刻意不代劳** —— 车道回收与重派是流水线的职责，不是同步任务的。

## 后续待办

- [ ] **P0 仍未解**：孤儿 keepalive 自杀机制未实现 —— 守护脚本应带「持有者 PID 存活检测，持有者一死就自杀」。
      本轮是**同款复发的第五次**（batch-84 → batch-101 → 第 76 轮 → 第 77 轮观测 → 本轮），
      **确认不是偶发，而是「每轮 tick 崩在闸里就复发一次」**。
      见 `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` 后续待办第 2 条。
- [ ] `lane.mjs` 的闸龄判据应从「闸目录 mtime」改成「**最新的 keepalive 文件**的 mtime」，
      且 `gate release` 只删自己持有的闸（现在是无条件 `rm -rf` 整个闸目录）。
      ⇒ 可从根上消灭「keepalive 让死闸永不陈旧」+「并发实例互相清闸」两个盲区。
- [ ] **本轮实测出「闸龄骤降」有两种成因**（真实交接 vs keepalive 假重置），
      ⇒ 建议把「keepalive 文件名时间戳是否严格等距」写进判据，避免下轮再花 13 分钟竞态空等。
- [ ] 持闸 tick `mvs_3f0ac6f1`（round-103 / batch-102）崩在 11:27:52，**它的 L2 从未跑过**，
      且它留下的 2 条车道是双孤儿 ⇒ 下一轮流水线 tick 需先确认这两条车道的处置（重派 or 回收），
      再走常规「回收 → 派发 → L2 → 推送」循环。
- [ ] 本地集成头领先 `origin/integration/module-dev` **25 个提交**且刻意未推（23 个 L2 红成果 + 2 篇同步文档 + 本篇）。
      下一轮流水线须先让 L2 转绿，再走「说明 / commit / push」三件套，
      push 时会**连带推出第 76 / 77 / 78 轮的会话文档**。

## 关联

- ADR-0002 多租户策略 / ADR-0004 Java 微服务 —— 与本轮无关，列出仅为完整性
- `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` —— 本轮处置所依据的 P0
- `docs/sessions/2026-10/2026-10-26-1020-main-sync-round76-no-new-commits.md` —— 同款接管的同步侧先例
- `docs/sessions/2026-10/2026-10-26-1022-main-sync-round77-no-new-commits.md` —— 上一轮同步（NO_UPDATE，未接管）
- `docs/sessions/2026-10/2026-10-26-batch-101-gate-takeover-l2-and-dispatch.md` —— 同款接管的流水线侧先例
- `docs/sessions/2026-10/2026-10-23-1955-main-sync-round56-parallel-gate-race.md` —— 「`ageSeconds` 骤降 + `owner` 变更 = 交接」判据来源
