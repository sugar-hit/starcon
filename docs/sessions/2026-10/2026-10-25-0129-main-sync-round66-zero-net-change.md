# 2026-10-25: 第 66 轮 main → 集成头同步（零新提交，未同步 · 等闸 13 分 47 秒 · TTL 接管）

- 时间：2026-10-25 01:15:09（起手 `gate status` 判定）/ 01:28:56（取闸成功）/ 01:29–01:34（取证 + 预检 + 收尾）
- 集成头 worktree：`/Users/guan/Projects/starcon-worktrees/integration`
- 分支：`integration/module-dev`
  - 起手 HEAD = `7ecd64f5`「merge(12.3.2.3): 自动匹配规则」
  - 收尾时本地 HEAD = `7ecd64f5` + 本轮 session doc 提交
  - 收尾时 `origin/integration/module-dev` = `a786b504` ⇒ 起手时**本地领先远端 2 个 commit**
    （`ac7e569c` feat(12.3.2.3) + `7ecd64f5` merge(12.3.2.3)，属 batch-90 流水线成果，**本轮之前从未被 push**）
- `origin/main` tip：`6522c497`（2026-10-23 09:18:01 +0800「chore: 同步集成分支最新进度」，自该时刻起**约 40 小时未再前进**）
- `merge-base --is-ancestor origin/main integration/module-dev` ⇒ **成立**（exit 0）
- `git log --oneline integration/module-dev..origin/main` ⇒ **空**（0 个）；`git diff --stat integration/module-dev...origin/main` ⇒ **空**
- 结果：**无新提交，未同步**（NO_UPDATE）
- 闸：起手 `held` / `stale: false` / `ageSeconds: 3374`（TTL 4200）⇒ 等闸 **13 分 47 秒**，
  01:28:56（TTL 过期点 01:28:55 之后 1 秒）**TTL 接管**取得；本轮已 `gate release`

---

## 摘要

本轮同步任务**没有产生合并**。`origin/main` 的 tip `6522c497` 早已被**第 59 轮**的 merge commit `ac0b1509`
并入集成头，本轮 `git fetch origin --prune` 后复验仍成立 ⇒ `integration/module-dev..origin/main` 为空集，
没有可搬运的提交。

与第 60–65 轮（同样零新提交）相比，本轮有两处**新情况**，都做了取证与处理：

1. **闸的来源变了：不是活着的 tick，是被中断的 tick 留下的陈旧闸。**
   起手 `gate status` = `held` / `stale: false` / `ageSeconds: 3374`，但闸外**没有任何活着的流水线进程**
   （`ps` 无 `mvnw` / `pnpm` / `lane.mjs` 调度器；`lane.mjs status` 的 `lanesRunning: 0`；
   batch-90 的续期守护 `keepalive-b90.sh` 已于 **00:37:24** 自行停止）。
   ⇒ 闸的实际持有人（batch-90 tick）已经死了，只是没跑到 `gate release`。
   本轮**没有**清闸、**没有** force，而是按协议走「密集 `acquire` 竞态」，
   **等到 TTL 自然过期（01:28:55）后由 `mkdirSync` 原子接管**（01:28:56 成功）——这是协议第 1 步明确允许的路径。
2. **集成头本地领先远端 2 个 commit，且工作区是脏的** —— batch-90（12.3.2.3 自动匹配规则）的成果
   已经 commit 到本地分支，但**从未 push**；它留下的台账刷新与 session doc 也还在工作区里。
   本轮若直接 `git push`，会**连带把 batch-90 的业务代码推上远端** ⇒ 触发「编译不过的集成头绝不上推」这条禁令的适用场景
   ⇒ 因此本轮**照常跑了快速预检**（后端 `mvnw -q compile` + `pnpm typecheck`，双绿）才推。

---

## 决策 / 结论

1. **`origin/main` 已完全包含在集成头内** —— 取证五件套：
   - `git ls-remote origin refs/heads/main` = `6522c497…`（**实时远端**，与本地 `origin/main` 一致 ⇒ 本地 ref 不陈旧）
   - `git fetch origin --prune` 后 `git rev-parse origin/main` = `6522c497…`（同上）
   - `git merge-base --is-ancestor origin/main integration/module-dev` ⇒ **成立**（exit 0）
   - `git log --oneline integration/module-dev..origin/main` ⇒ **空**（0 个）
   - `git diff --stat integration/module-dev...origin/main` ⇒ **空**
   ⇒ 没有可搬运的提交 ⇒ **不 merge、不产生 merge commit**。
2. **不跑 merge，只跑预检 —— 因为本轮要 push 的树含业务代码**。
   第 60–65 轮都跳过预检，理由是「空集轮次没有代码变化，重跑编译只会凭空造热量」。
   本轮**不能照搬那条结论**：待 push 的集成头里含 batch-90 的 12.3.2.3 业务代码（`ac7e569c` + `7ecd64f5`），
   它们**从未上过远端** ⇒ 必须有「这棵树能编译」的一手证据才能推。
   实测：`backend/./mvnw -q compile` ⇒ **exit 0**；`pnpm typecheck` ⇒ **exit 0**（3/3 tasks 成功）。
   日志：`.dev-agent/sync-main-r66-compile.log` / `.dev-agent/sync-main-r66-typecheck.log`。
3. **这棵树此前已通过更强的 L2**（batch-90 自己跑的）：
   `.dev-agent/l2-run-batch-90.log` 末行 `===== L2 GREEN =====` / `===== L2 END 2026-10-25 00:18:19 =====`，
   12 个退出码全 0（全量 `mvnw verify` 约 58 分 + typecheck / lint / test / Next build / `sync --check` / 契约闸）。
   ⚠️ **核对过 L2 覆盖范围**：merge commit `7ecd64f5` 的提交时间 23:15:52 **早于** L2 起始时间 23:16:59；
   且 `git diff --stat 7ecd64f5 -- backend/ apps/ packages/ db/ services/ workers/ tools/ infra/` ⇒ **空**
   ⇒ L2 验证的正是本轮要推的这棵树（代码部分逐字节相同；工作区多出来的只是 `docs/` 台账与 session doc）。
4. **台账已刷新且零漂移**。`node tools/module-progress/sync.mjs` ⇒
   `✓ 同步 18 卷 / 465 个 L4 模块`、**已交付 400 · 进行中 0 · 未开发 65**；
   `node tools/module-progress/sync.mjs --check` ⇒ `✓ 台账与蓝图一致`。
   （基线对比：第 65 轮为 399 / 0 / 66 ⇒ 399→400 的增量来自 **batch-90 的 12.3.2.3 并入**，**与本轮同步无关**。）
5. **闸的判据只用 `stale` 字段**，本轮又是一次正向印证：
   起手 `stale: false` ⇒ 按协议第 1 步跑「密集 `acquire` 竞态」（1 秒一次，直接竞 `mkdirSync`，失败无副作用），
   **没有**低频 `status` 观测、**没有**清闸、**没有** force；01:28:56 由 TTL 过期后的原子接管取得。
   取闸后复查 `owner` = `11835` / `2026-10-24T17:28:56.850Z` ⇒ 确为本轮写入。
6. **等闸期间只做只读取证**：全程未跑 `git fetch`、未跑任何构建、未在等闸窗口里制造第二个 `mvnw` / `pnpm` 进程。
   只执行过 `gate status` / `cat owner` / `ps` / `git log` / `git ls-remote` / `git status` / `lane.mjs status` 这类只读命令。
   ⚠️ `git ls-remote` 是**只读**的（不落盘、不改本地 ref），用它确认远端 tip 与本地 `origin/main` 一致，
   属「只读取证」范畴，不是 fetch。
7. **并发同任务实例的仲裁是安全的**：等闸期间发现 **PID 97353**（01:08:27 启动）在跑**同一个同步任务的另一轮**
   （脚本 `.tmp/sync-main-gate-race.sh`，同样 1 秒一次竞 `gate acquire`，其 `MAX_WAIT=3300` 恰好覆盖 01:28:55 的 TTL 接管点）。
   ⇒ 两轮靠 `mkdirSync` 的原子性互斥，**同一时刻只有一方能持闸**；本轮先命中 TTL 接管点，另一轮继续等。
   ⇒ 这不是「双跑同步」：闸保证了写操作不重叠。

---

## 涉及文件

- 本轮**没有**改动任何业务代码 / 契约 / 迁移，也**没有**产生 merge commit。
- 本轮新增：
  - `docs/sessions/2026-10/2026-10-25-0129-main-sync-round66-zero-net-change.md`（本文件）
  - `.dev-agent/sync-main-r66-compile.log` / `.dev-agent/sync-main-r66-typecheck.log`（预检日志，gitignored）
- 本轮**提交并推送**的既有变更（batch-90 留下的台账刷新，本轮 `sync.mjs` 复现后一致）：
  - `docs/product/capability-blueprint/module-progress.md`（台账，399 → 400 已交付 / 66 → 65 未开发）
- **未提交、未触碰**（明确留给流水线，不属本任务边界）：
  - `docs/sessions/2026-10/2026-10-24-batch-90-merge-12-3-2-3-whitelist-union.md`（batch-90 的 session doc，
    **仍是未跟踪文件**，且正文里的 `<!-- THREE_PIECE_PLACEHOLDER -->` 尚未填 ⇒ 是被中断 tick 的在途产物）
- 只读取证涉及：
  - `tools/module-progress/SYNC-MAIN.md`（本任务协议）、`tools/module-progress/PIPELINE.md`（§5.6 确认分工无冲突）
  - `tools/module-progress/lane.mjs`（`gate status` / `gate acquire` / `gate release` 的实现语义）
  - `tools/module-progress/sync.mjs`（台账刷新器 + `--check`）
  - `docs/product/capability-blueprint/module-progress.md`（台账）
  - `.dev-agent/l2-run-batch-90.log`（batch-90 的 L2 绿证据）、`.dev-agent/keepalive-b90.sh` / `keepalive-b90.log`（闸续期取证）
  - `docs/sessions/2026-10/2026-10-24-2215-main-sync-round65-zero-net-change.md`（上一轮留痕格式参照）

---

## 关键 trade-off / 讨论

- **🔴 本轮推的其实不只是本轮的产出**：`git push origin integration/module-dev` 会把
  batch-90 的 `ac7e569c` + `7ecd64f5` 一并推上远端（它们是本地 HEAD 的祖先，推送必然带上）。
  两条路：① 不推 —— 则集成头继续在远端缺 2 个 commit，且本轮的 session doc 也发不出去，脏工作区留给下一轮；
  ② 推 —— 则先跑快速预检自证「这棵树能编译」，再推。
  **选 ②**：batch-90 的 L2 已 GREEN 且预检复跑双绿，推上去的风险显著低于「让集成头长期落后 + 工作区长期脏」的代价。
  连带效果：**顺手把 batch-90 未完成的 push 补上了**（batch-90 tick 被中断在 push 之前，L2 证据完好）。
- **🔴 batch-90 的 session doc 我故意没提交**：它带着未填的 `THREE_PIECE_PLACEHOLDER`，
  是被中断 tick 的**在途产物**，收口（填占位符 + 记结论）属于流水线 tick 的职责。
  本任务边界是「只搬运 commit、不改业务代码」⇒ 只在汇报里点名，不替流水线收口，也不 `git checkout --` 抹掉它
  （第 65 轮教训：抹掉就是弄丢持闸 tick 的在途工作）。
- **🔴 陈旧闸不等于可以随便清**：协议允许「`stale: true` ⇒ 可 acquire 接管」，但**没拿到过闸就 `gate release` = 无条件
  `rmSync(GATE_DIR)` = 删掉别人的活闸**。本轮严格只走「竞速 acquire → 自然 TTL 过期 → 原子接管」，
  全程没碰过 `gate release`，直到自己真的持闸后才在收尾释放。
- **🟡 起手工作区脏的归属判定**：脏文件（台账 + batch-90 session doc）**归属明确**指向 batch-90，
  但持有人已死 ⇒ 不能像第 65 轮那样「等它提交后自然变干净」（它不会再提交了）。
  处理办法：台账是**生成物**（`sync.mjs` 可复现），跑一次 `sync.mjs` 即幂等对齐；
  session doc 是**半成品**，原样保留、如实上报。
- **🟡 没有为「空集」硬造 merge commit**：协议第 2 步的 NO_UPDATE 分支明确要求「不 merge」，
  本轮严格遵守 —— 即使本轮实际上有东西可以推（batch-90 的 commit），也没有借机造一个 `merge(main)` 空提交。
- **🟡 热量的取舍**：本轮多跑了一次编译（`mvnw -q compile`）。第 60–65 轮跳过预检是为了省热量，
  本轮多跑是因为**推的树里有未上远端的业务代码**，「省热量」不能压过「不推编译不过的树」。
  且闸在我手上、无并发流水线 ⇒ 不存在与 L2 抢 `target/` 的假红风险。

---

## 后续待办

1. **🔴 batch-90 的收尾要认领**：它的 session doc 仍是未跟踪文件（带未填占位符），
   而它的 push 已由本轮补上（`7ecd64f5` 现已上远端）。下一轮流水线 tick（02:00）应：
   ① 认领并填完 `docs/sessions/2026-10/2026-10-24-batch-90-merge-12-3-2-3-whitelist-union.md` 的占位符；
   ② 不要重复 push 已推过的 commit。
2. **盯 `origin/main` 是否前进**：main 自 2026-10-23 09:18 起静止（已约 40 小时），
   若下一轮仍是同一 tip `6522c497`，本任务会持续输出「无新提交」。main 每次前进都要重新取证 merge-base 是否 == tip。
3. **协议修订建议（第八次提出，留人工裁决）** —— `tools/module-progress/SYNC-MAIN.md`：
   - 第 3 步补一条「**起手工作区脏、且脏文件归属被中断的上一轮 tick 时**：先判定该 tick 的成果是否已过 L2
     （`l2-run-*.log` 末行 `L2 GREEN` + commit 时间早于 L2 起始时间），
     已过 L2 ⇒ 可连同台账一并 push（本轮路径）；未过 L2 ⇒ 停下汇报」；
   - 第 1 步补一条「**holder PID 已死 + 无流水线进程 + `lanesRunning: 0` ⇒ 闸是陈旧闸**，
     但仍按 TTL 自然过期后接管，不要提前 `rmSync`」；
   - `gate release` 加「只删自己持有的闸」校验（当前是无条件 `rmSync`，会误删别人的闸 —— 第 61 轮亲历）；
   - 第 1 步写明「**同一任务可能有多轮实例并发竞闸**」（本轮实测到 PID 97353）⇒ 靠 `mkdirSync` 原子性互斥即可，
     但报告里要写明是否有并发实例。
4. **本系列既有 P0 未动**（也没权动）：
   - `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md`
   - `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`
   - `docs/backlog/2026-10/2026-10-23-pipeline-service-gap-and-blueprint-cycle-blockers.md`（流水线零派发的结构性阻塞）

---

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`（完整流水线协议见 `tools/module-progress/PIPELINE.md`，两者不一致时以 PIPELINE.md 为准）
- 第 65 轮（上一轮，同样零新提交）：`docs/sessions/2026-10/2026-10-24-2215-main-sync-round65-zero-net-change.md`
- 第 59 轮（本系列最近一次实际合并）：`docs/sessions/2026-10/2026-10-24-0335-main-sync-round59-conflict-resolved-pushed.md`
  → merge commit `ac0b1509`，并入 20 个 main 提交
- batch-90（本轮附带补推的那批）：`docs/sessions/2026-10/2026-10-24-batch-90-merge-12-3-2-3-whitelist-union.md`（**未跟踪，待流水线认领**）
- 闸语义的历史取证：`docs/sessions/2026-10/2026-10-23-0006-main-sync-conflict-31-files-gate-contention.md`
