# 2026-10-26: 集成头同步 main 第 77 轮 —— 无新提交，未同步（等闸命中交接窗口 · 与第 76 轮并发）

## 摘要

本轮**结果是无新提交、未同步**：取到闸后 `git fetch origin --prune`，
`git merge-base --is-ancestor origin/main integration/module-dev` ⇒ `NO_UPDATE` ——
`origin/main` 的 tip `6522c497`（2026-10-23「chore: 同步集成分支最新进度」）已是集成头的祖先，
本轮**没有 merge、没有预检、没有 push**，并入的 main 提交数 = **0**。

本轮的主要成本在闸：起手 `gate status` = `held / stale=false`，且闸目录 mtime 被一个
**孤儿 keepalive 守护**每 4 分钟刷新 ⇒ `stale` 永远不会转 `true`。本轮**没有清闸、没有 force**，
严格按 `tools/module-progress/SYNC-MAIN.md` 的「等闸」纪律用 **1 秒密度的 `gate acquire` 竞态**，
在 **10:19:04** 命中闸的交接窗口拿到闸（`{"ok": true, "gate": "acquired"}`），拿到后立刻 `fetch` 取证。

并发取证发现一件值得记的事：**同一同步任务的另一个实例（第 76 轮）本轮正在等同一个闸**，
并在 10:20:46 于集成头提交了 `aaf368d4`。两个实例结论一致（都是 NO_UPDATE），
但 `gate release` 的实现是**无条件 `rmSync(GATE_DIR)`** ⇒ 并发实例之间存在**互相清闸**的窗口
（见「后续待办」第 2 条，那正是 P0 登记在案的根因之一）。

## 决策 / 结论

1. **闸的现状是「孤儿 keepalive 死锁」，与第 76 轮的取证一致。**
   持闸 tick `round-102 / batch-101` 已崩，但它的 nohup 孤儿守护 `.dev-agent/keepalive-r102.sh`
   仍每 4 分钟新建文件刷新闸目录 mtime ⇒ `statSync(GATE_DIR).mtimeMs` 永远新鲜
   ⇒ `ageSeconds` 永远 < TTL(4200) ⇒ `stale` 永远 `false` ⇒ 纯靠等是等不到的。
   闸 `owner` 里的 PID `64416` 查无此进程。

2. **本轮没有复刻第 76 轮的「停守护 + release + acquire」接管**，走的是协议里的**等闸竞态**：

   | 证据 | 实测 |
   | --- | --- |
   | `gate status` 起手 | `held / ageSeconds 181 / stale=false` |
   | 持闸者进程 | 全仓无 `mvnw` / `java` / `pnpm` / `node` 流水线进程；`owner` PID `64416` 不存在 |
   | 构建进程 | `pgrep` 无 surefire / maven / vitest；无 Testcontainers PG 容器 |
   | 闸龄是否在推进 | `ageSeconds` 从 211 → **31 骤降**（10:16:06）⇒ 持闸者释放过、下一个 tick 重新 acquire ⇒ **存在零宽度交接窗口** |
   | 最终取闸 | **10:19:04** `gate acquire` ⇒ `{"ok": true}`（退出码 0），**未清闸、未 force、未杀任何进程** |

   ⚠️ 闸龄骤降恰恰说明「先观测到 free 再 acquire」会整段错过交接窗口 —— 本轮用 1 秒密度竞态是对的。

3. **等闸期间只做只读取证，未跑任何构建**：`gate status` / `cat owner` / `ps` / `git log` /
   `git merge-base`（对已 fetch 的 refs 做祖先判断）。**连 `git fetch` 都是拿到闸之后才跑的**
   —— 与持闸 tick 的 L2 抢 `backend/**/target/` 和 PG 容器会并发假红。
   本轮 NO_UPDATE 分支**根本没跑到预检**（`mvnw compile` / `pnpm typecheck` 均未执行）。

4. **`origin/main` 无新提交，本轮零搬运。**
   `origin/main` tip = `6522c497`（2026-10-23 09:18:01），是集成头的祖先；
   `git log --oneline integration/module-dev..origin/main` ⇒ **0 行**；`git diff --stat` ⇒ **空**。
   ⇒ 不 merge、不预检、不 push；**没有属于本轮的提交要推**。

5. **台账无变化**：`node tools/module-progress/sync.mjs --check` ⇒ `✓ 台账与蓝图一致`；
   465 总数 · **408 已交付** · **0 进行中** · **57 未开发**（完成率 87.7%）。
   本轮**未跑写模式的 `sync.mjs`** —— NO_UPDATE 分支不需要改台账，跑写模式只会把工作区弄脏。

6. **收尾已 `gate release`**，`gate status` = `free`、闸目录已不存在 ⇒ 下一轮流水线 tick 可正常取闸。
   收尾前另确认：**没有任何 keepalive 守护进程存活**（第 76 轮的 `STOP-KEEPALIVE-R102` 已生效）。

7. **本轮不 push。** 本地集成头比 `origin/integration/module-dev`（`1e3228b4`，第 75 轮）**领先 22 个提交**：
   21 个是流水线**刻意不推**的 L2 红成果（batch-100 明写「L2 红在 warehouse/channel/platform，本轮未 push」），
   第 22 个是第 76 轮的会话文档提交。推本篇文档会**连带把它们推上去** ⇒ 不是本任务的授权范围。
   ⇒ 本轮只**提交本篇文档、不 push**，与第 76 轮同一处置。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` —— 本任务协议（本轮按它执行）
- `tools/module-progress/lane.mjs` —— 闸实现（`GATE_TTL_SECONDS = 4200`，判龄用闸目录 mtime；
  `gate release` 是无条件 `rmSync(GATE_DIR)`）
- `tools/module-progress/PIPELINE.md` —— 流水线协议（与 SYNC-MAIN.md 冲突时以它为准）
- `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` —— **P0 死锁登记与处置纪律**
- `docs/sessions/2026-10/2026-10-26-1020-main-sync-round76-no-new-commits.md` —— **本轮并发实例（第 76 轮）**
- `docs/sessions/2026-10/2026-10-26-batch-101-gate-takeover-l2-and-dispatch.md` —— batch-101 的同款接管先例
- `docs/sessions/2026-10/2026-10-23-1955-main-sync-round56-parallel-gate-race.md` —— 「闸龄骤降 = 交接」判据来源
- 本篇：`docs/sessions/2026-10/2026-10-26-1022-main-sync-round77-no-new-commits.md`

## 关键 trade-off / 讨论

**为什么不等满 TTL、也不清闸，而是竞态等交接窗口？**
P0 登记的接管纪律是「持闸者已死 ⇒ 停掉孤儿守护 → release + acquire」，
但那条路本质上是**清闸**，且本任务 prompt 明确「不要清闸、不要 force」。
本轮发现闸确实存在**真实的交接窗口**（闸龄 211 → 31 骤降，持闸者释放后下一 tick 同秒重新 acquire），
所以**不必清闸也能合规取闸**：1 秒密度竞态 `gate acquire` 天然是原子的（`mkdirSync`），
拿到就拿到，拿不到就继续等 —— 这是协议写明的等闸姿势，且全程零副作用。
10:19:04 命中 ⇒ 本轮**没有杀任何进程、没有 `rm` 任何闸目录**（`gate release` 只在收尾删自己那一份）。

**为什么明知第 76 轮也在等，还不直接判定「有人会替我干」而退出？**
两个实例并发等闸会放大「`gate release` 无条件清闸」的互斥缺陷（见下）。但退出并不能消除它，
反而会让本轮**丧失一次零成本的取闸机会**。本轮取到闸后**只做了只读取证 + 一篇文档提交**，
没有 merge、没有构建、没有 push ⇒ 即使与第 76 轮短暂重叠，**也不会出现并发构建假红**。

**为什么第 76 轮的结论可以采信、而本轮仍要自己 fetch？**
第 76 轮 10:20:46 的结论是「`origin/main` tip `6522c497` 已是集成头祖先」。
本轮取闸后**重新 fetch** 复核了一遍，结论一致（`NO_UPDATE`）。
理由：`origin/main` 随时可能被别的 agent 推进，**上一轮的结论不能替代本轮的取证**。

## 后续待办

- [ ] **P0 未解**：孤儿 keepalive 自杀机制仍未实现 —— 守护脚本应带「持有者 PID 存活检测，持有者一死就自杀」。
      这已是**第四次**同款复发（batch-84 → batch-101 → 第 76 轮 → 孤儿守护 64427），
      **说明不是偶发，而是「每轮 tick 崩在闸里就复发一次」**。
      见 `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` 后续待办第 2 条。
- [ ] **`lane.mjs` 的 `gate release` 应只删自己持有的闸**（现在是无条件 `rmSync(GATE_DIR)`）。
      本轮实测到**同一同步任务的两个实例并发等闸** ⇒ 任一实例收尾时会把另一实例刚拿到的闸删掉，
      互斥在最坏情况下会被静默破坏。配合「闸龄判据从闸目录 mtime 改成最新 keepalive 文件 mtime」，
      可从根上消灭「keepalive 让死闸永不陈旧」+「并发实例互相清闸」这两个盲区。
- [ ] **下一轮流水线 tick 的第一件事**：`docs/sessions/2026-10/2026-10-26-batch-101-gate-takeover-l2-and-dispatch.md`
      **仍是 untracked**（round-102 崩在 commit 前）⇒ `lane.mjs merge-ready` 的「工作区干净」前置检查会拒绝切分支，
      **并入通道是堵死的**。这与 batch-101 记录的 batch-100 同一个坑第二次复发，
      需要流水线 tick 自己**把说明文件与台账放进同一个 commit** 补齐（**本轮刻意没有代劳** ——
      那是流水线的记录，不是同步任务的）。
- [ ] 本地集成头领先 `origin/integration/module-dev` **22 个提交**且刻意未推（21 个 L2 红成果 + 第 76 轮文档）。
      下一轮流水线须先让 L2 转绿，再走「说明 / commit / push」三件套，
      push 时会**连带推出第 76 轮与本轮的会话文档**（两篇均已本地提交）。

## 关联

- `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` —— 本轮判据依据的 P0
- `docs/sessions/2026-10/2026-10-26-1020-main-sync-round76-no-new-commits.md` —— 本轮并发实例，结论一致
- `docs/sessions/2026-10/2026-10-25-2315-main-sync-round75-no-new-commits.md` —— 上一轮同步（同为 NO_UPDATE）
- ADR-0002 多租户策略 / ADR-0004 Java 微服务 —— 与本轮无关，列出仅为完整性
