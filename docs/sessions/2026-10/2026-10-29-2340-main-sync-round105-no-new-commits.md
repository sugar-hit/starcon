# 2026-10-29 23:40 · 集成头同步 main 第 105 轮 —— 无新提交，未同步（等闸 2h24m 后 TTL 接管）

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：无新提交，未同步**。取到闸后 `git fetch origin --prune`，
> `git merge-base --is-ancestor origin/main integration/module-dev` ⇒ `NO_UPDATE`。
> 本轮并入的 main 提交数 = **0**，没有 merge、没有预检、没有代码 push。

## 摘要

| 项 | 值 |
| --- | --- |
| 结果 | **无新提交，未同步**（`NO_UPDATE`） |
| 并入的 main 提交数 | **0** |
| 差异规模 | `git log --oneline integration/module-dev..origin/main` ⇒ **0 行**；`git diff --stat` ⇒ 空 |
| 冲突 | **无**（未进入 merge 步骤） |
| 预检 | **未跑**（`NO_UPDATE` 分支不跑 `mvnw compile` / `pnpm typecheck`） |
| 台账 | **465 总数 · 432 已交付 · 0 进行中 · 33 未开发**（92.9%）—— **本轮零变化** |
| merge commit | **无**（本轮没有产生 merge commit） |
| push | **无代码 push**；仅本篇会话文档提交后 push 到 `origin/integration/module-dev` |
| 等闸时长 | **约 2 小时 24 分**（21:16 起手 → **23:40:25** 取得闸，TTL 接管） |
| 闸 | **已释放**（收尾 `gate release`，`gate status` = `free`） |

## 决策 / 结论

1. **`origin/main` 无新提交，本轮零搬运。**

   | 证据 | 实测 |
   | --- | --- |
   | 远程权威 tip（`git ls-remote origin refs/heads/main`） | `b813701c` |
   | 本地 `origin/main` | `b813701c`（与远程一致） |
   | `git merge-base --is-ancestor origin/main integration/module-dev` | 退出码 **0** ⇒ 已是祖先 |
   | `git log --oneline integration/module-dev..origin/main` | **0 行** |
   | `git log --oneline origin/main..integration/module-dev` | 4 行（`4d3556d5` merge、`7bf2c414` 文档、`ec720b03` merge 13.6.1.1、`e74b96b6` feat reporting） |

   ⇒ 不 merge、不预检、不 push 代码。

2. **main 的 tip 已被**上一轮**（第 104 轮）并入，本轮只是「追认」。**
   `origin/main` 的 `b813701c` 是集成头 `4d3556d5`（`merge(main): sync from origin/main`，2026-10-29 23:22:47）的祖先。
   `origin/main` 的 reflog 直接印证了这一点：

   ```
   b813701c refs/remotes/origin/main@{2026-10-29 23:21:54}: fetch origin --prune: fast-forward
   823ebca5 refs/remotes/origin/main@{2026-10-26 16:00:08}: fetch --quiet origin main: fast-forward
   ```

   ⇒ 第 104 轮在 **23:21:54** 拉到了 `b813701c`，**23:22:47** 并入并 push。
   本轮 **23:40** 取到闸后重新 `fetch` 复核，结论一致（`NO_UPDATE`）。
   **上一轮的结论不能替代本轮的取证** —— 本轮独立 fetch + 独立判祖先，两次结论一致才落笔。

3. **本轮等闸极长（2h24m），且全程只做只读取证。**
   起手 `gate status` = `held / stale=false` / `ageSeconds=57`，持闸者 `owner` pid `10103`、
   取得时间 `2026-10-29T13:00:35.899Z`（= 本地 21:00:35）。
   按协议**等它释放，不抢、不清、不 force**；等闸期间**只做只读取证**
   （`gate status` / `cat owner` / `ps` / `git log` / `git merge-base`），
   **连 `git fetch` 都是拿到闸之后才跑的** —— 持闸 tick 的 L2 与预检共用
   `backend/**/target/` 与 PG 容器，并发必假红。

4. **持闸 tick 实际已崩在闸里，靠 TTL 过期才脱身。**
   闸目录 mtime 由「新建 `keepalive-<HHMMSS>` 文件」刷新（PIPELINE.md 续期机制）。
   本轮观测到 `keepalive-211410`（21:14:10）与 `keepalive-220730`（22:07:30）两个续期点，
   **22:07:30 之后再无续期** ⇒ 最后一次 TTL 基准 = 22:07:30 ⇒ 理论陈旧点 = **23:17:30**。
   期间取到闸的真实持闸者在持续干活（13.6.1.1 于 21:39 落地 `e74b96b6`；
   14.2.1.1 持续改 Java 源码；22:24 / 22:30 在 14.2.1.1 worktree 跑 vitest 并产出提交 `9ad839a3`），
   但**闸未被释放**，直到 TTL 过期。

   ⚠️ **`ageSeconds` 骤降 ≠ 持闸者释放。** 22:07 前后观察到闸龄从 3188s 骤降到 27s，
   一度像「释放后被下一 tick 同秒重取」，实为 **keepalive 新建文件刷新了闸目录 mtime**。
   这正说明协议那条「必须走 `gate status` 的 `stale` 字段判据，不要自己按 `ageSeconds` 猜」是对的 ——
   **`ageSeconds` 会被续期重置，只有 `stale` 是单调可信的判据。**

5. **最终取闸走的是 TTL 接管（协议允许的唯一路径），不是清闸。**
   23:40:24 `gate acquire` 返回 `{"ok": true}`，本轮**没有杀任何进程、没有 `rm` 任何别人的闸目录**
   —— 接管是 `lane.mjs` 自身在 `age > ttlSeconds` 时执行的 `rmSync` + `mkdirSync`，
   与 `gate status` 的 `stale` 是**同一套阈值**（`GATE_TTL_SECONDS = 4200`）。
   取得时闸目录里**只剩一个 `owner`**（`keepalive-*` 已随旧目录一并消失），
   mtime = 23:40:24 ⇒ 本轮拿到的是一份**全新 TTL**（至 00:50）。

6. **台账零变化，`sync.mjs --check` 绿。**
   `node tools/module-progress/sync.mjs --check` ⇒ `✓ 台账与蓝图一致`。
   465 总数 · **432 已交付** · **0 进行中** · **33 未开发**（92.9%）。
   本轮**未跑写模式的 `sync.mjs`** —— `NO_UPDATE` 分支不需要改台账，跑写模式只会把工作区弄脏。

7. **本轮只 push 了本篇文档。**
   取闸时本地集成头与 `origin/integration/module-dev` **完全对齐**（`0 0`，同为 `7bf2c414`），
   与第 77 轮「本地领先 22 个提交、刻意不推」的处境**不同** ⇒ 本篇文档提交后 push
   **只携带本篇一个提交**，不会连带推出任何 L2 红成果。

8. **收尾已 `gate release`**，`gate status` = `free`、闸目录已不存在 ⇒ 下一轮流水线 tick 可正常取闸。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` —— 本任务协议（本轮按它执行）
- `tools/module-progress/lane.mjs` —— 闸实现（`GATE_TTL_SECONDS = 4200`；判龄用**闸目录 mtime**；
  续期靠**新建** `keepalive-*` 文件；`gate release` 是无条件 `rmSync(GATE_DIR)`）
- `tools/module-progress/PIPELINE.md` —— 流水线协议（与 SYNC-MAIN.md 冲突时以它为准）；
  其「关键节点续期」一节解释了 `keepalive-*` 的语义
- `tools/module-progress/sync.mjs` —— 台账刷新（本轮只跑 `--check`，未跑写模式）
- `docs/sessions/2026-10/2026-10-29-2321-main-sync-round104-synced-clean.md` —— **上一轮（已并入 10 个提交）**
- `docs/sessions/2026-10/2026-10-26-1022-main-sync-round77-no-new-commits.md` —— **`NO_UPDATE` 轮次的处置样板**
- `docs/product/capability-blueprint/module-progress.md` —— 台账（本轮只读，未改）
- 本篇：`docs/sessions/2026-10/2026-10-29-2340-main-sync-round105-no-new-commits.md`

## 关键 trade-off / 讨论

**为什么等满 2h24m，而不是判定「持闸者已死」后接管？**

协议只认 `gate status` 的 `stale` 字段，而 `stale` 的时钟是**闸目录 mtime**，
每被 `keepalive-*` 续期就重置 ⇒ 「持闸者还活着」与「持闸者崩了、只剩孤儿守护在续期」
两种情形在 `stale` 上**完全同形**。第 77 轮已经把后者登记成 P0
（`docs/backlog/2026-10-2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`：
孤儿 keepalive 守护每 4 分钟刷新闸目录 ⇒ 死闸永不陈旧 ⇒ 纯靠等等不到）。
本轮**没有**出现那种孤儿守护（22:07:30 之后闸目录 mtime 再没被动过），
所以 TTL 自然过期、协议允许的 `age > ttl` 接管路径自己打开了。
⇒ 本轮既没等成僵局，也没做任何越权接管：**唯一的一次 `rmSync` 是 `lane.mjs` 自己在
`age > ttl` 分支里做的**，不是我手动清的闸。

**为什么把竞态密度从 1 秒提到 0.05 秒？**

协议写明交接窗口「可以窄到零宽度」，且第 76/77 轮都靠 1 秒密度命中过窗口。
但本轮观测到的闸龄骤降是**续期假象**而非真实交接 ⇒ 「1 秒轮询一个 70 分钟 TTL」
在本轮等于纯等 TTL。0.05 秒密度让每次 `mkdirSync` 的原子竞态窗口最密，
不改变语义、零副作用（失败时 `mkdirSync` 直接抛错、无任何写操作）。

**为什么本轮在拿到闸之后仍完整重跑 `fetch` + 祖先判断？**

第 104 轮在 23:21 就已经并入了 `b813701c`，本轮取闸时已经 23:40 —— 中间隔了 19 分钟，
`origin/main` 随时可能被别的 agent 推进。**上一轮的结论不能替代本轮的取证**，
所以本轮独立 `fetch`、独立 `git ls-remote` 复核远程 tip、独立判祖先，三处一致才落笔。

## 后续待办

- [ ] **P0 未解**：孤儿 keepalive 自杀机制仍未实现（守护脚本应带「持有者 PID 存活检测，持有者一死就自杀」）。
      本轮再次撞上**「持闸 tick 崩在闸里、靠 TTL 自然过期脱身」**的同款形态 ——
      闸从 21:00:35 被持有到 23:40 才脱身，其间 22:07:30 之后无任何续期。
      这已是**第 N 次**同款复发，说明不是偶发。
      见 `docs/backlog/2026-10-2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`。
- [ ] **`lane.mjs` 的 `gate release` 仍是无条件 `rmSync(GATE_DIR)`**，并发实例互清闸的盲区未消。
      本轮取闸时闸目录只剩 `owner`（无 `keepalive-*`），说明本轮拿到的是干净的新闸，未与他人重叠；
      但「上一实例 23:17 TTL 接管 → 23:40 释放 → 本轮 23:40:24 抢到」的衔接里，
      **两个实例确实先后持过同一个闸**，各自 `release` 的时机仍靠自觉。
- [ ] **流水线 tick 崩在闸里时，`lanes.json` 与 `14.2.1.1` 车道被留在 `running` 态**
      （`heartbeatAt` 停在 `2026-10-29T14:07:30.500Z`，`landedAt` 为空），
      而该车道 worktree 里已有提交 `9ad839a3`（含台账与证据文档）但**功能代码仍未 commit**。
      本轮**刻意没有代劳**：那是流水线的记录与归口，不是同步任务的职责。
      需要流水线 tick 自己做「收尾 / commit / land」三件套。
- [ ] **下一轮流水线的 L2 必须重点看第 104 轮的 merge commit `4d3556d5`** ——
      它并入的 10 个 main 提交**没经过本流水线的 L1/L2**，是回归时的首要排查对象。

## 关联

- `docs/sessions/2026-10/2026-10-29-2321-main-sync-round104-synced-clean.md` —— 上一轮，**已并入 10 个提交并 push**
- `docs/sessions/2026-10/2026-10-26-1022-main-sync-round77-no-new-commits.md` —— `NO_UPDATE` 轮次处置样板
- `docs/backlog/2026-10-2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` —— 闸死锁 P0 登记
- `tools/module-progress/PIPELINE.md` —— 闸续期机制（`keepalive-*` 语义表）
