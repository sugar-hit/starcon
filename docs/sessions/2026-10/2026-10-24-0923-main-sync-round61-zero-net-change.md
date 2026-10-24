# 2026-10-24: 第 61 轮 main → 集成头同步（零新提交，未同步 · 等闸 65 分钟）

- 时间：2026-10-24 08:15（起手）/ 09:20:49（首次取闸）/ 09:22:36（闸被误删后复查）/ 09:23:05（重新取闸）/ 09:24（台账刷新 + 收尾）
- 集成头 worktree：`/Users/guan/Projects/starcon-worktrees/integration`
- 分支：`integration/module-dev`（HEAD = `3ec03681`「docs(batch-82): …8 格交付 + 3 条修复车道」，与 `origin/integration/module-dev` 一致，ahead/behind = 0/0）
- `origin/main` tip：`6522c497`（2026-10-23 09:18:01 +0800「chore: 同步集成分支最新进度」，自该时刻起 **约 24 小时未再前进**）
- `merge-base(origin/main, integration/module-dev)` = `6522c497` —— **等于 `origin/main` tip 本身**
- 结果：**无新提交，未同步**（NO_UPDATE）
- 闸：起手 `held`（`stale: false`）⇒ 等闸 **65 分钟**后 09:20:49 取得；09:22:36 闸目录被持闸 tick 的**迟到 release** 抹掉，09:23:05 在空闲态重新取闸；本轮已 `gate release`

---

## 摘要

本轮同步任务**没有产生合并**。`origin/main` 的 tip `6522c497` 早已被**第 59 轮**的 merge commit
`ac0b1509` 并入集成头，因此 `merge-base --is-ancestor origin/main integration/module-dev` 成立，
`integration/module-dev..origin/main` 为空集 —— 没有可搬运的提交。集成头在 main tip 之后又前进了 **841 个提交**。

本轮与前 60 轮最大的不同是**闸的争用形态**：起手即被一个活着的流水线 tick 持有，
而**协议示例里那套「起 node 子进程 + sleep 1」的竞态跑满整整 1 小时（3600 秒）一次都没中**；
换成单进程内 5ms 间隔直接 `mkdirSync` 的密集竞态后，**138 秒 / 19652 次尝试即命中**。

另有一个**新现象**：我 09:20:49 取到的闸，在 09:20:49–09:22:36 之间被持闸 tick 的**迟到 `gate release`**
整个删掉（tick 收尾时先建 `STOP-KEEPALIVE`、后放闸，放闸动作落在我取闸之后）⇒ 闸目录消失。
这是 `gate release` 无条件 `rmSync(GATE_DIR)` 的固有副作用，与协议里警告过的「并发实例互撞」同源。

---

## 决策 / 结论

1. **`origin/main` 已完全包含在集成头内** —— `merge-base` 直接落在 `origin/main` 的 tip 上，
   说明第 59 轮之后 main 没有再前进过。取证四件套：
   - `git ls-remote origin refs/heads/main` = `6522c497…`（**实时远端**，与本地 `origin/main` 一致）
   - `git merge-base --is-ancestor origin/main integration/module-dev` ⇒ 成立（exit 0）
   - `git merge-base integration/module-dev origin/main` = `6522c497` ⇒ **等于 tip 本身**
   - `git log --oneline integration/module-dev..origin/main` ⇒ **空**；`origin/main..integration/module-dev` ⇒ **841 个提交**
2. **本轮不跑快速预检**（沿用第 60 轮结论）。协议里 `mvnw -q compile` + `pnpm typecheck` 位于第 4 步，
   是**合并之后**的守门；本轮没有 merge、没有代码变化，工作树与已被流水线 L2 验证过的树**逐字节相同**
   （`git diff --stat origin/main integration/module-dev` 显示集成头领先 main 618 文件 / +295509 行）。
   为空集重跑编译只会凭空造热量（发热高峰是流水线 L2 的专属预算），撞车还会产生**假红**。
3. **台账已刷新且零漂移**。`node tools/module-progress/sync.mjs` 输出
   `✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 380 · 进行中 0 · 未开发 85`，
   随后 `git status --porcelain` **为空** ⇒ 刷新是纯 no-op，台账本来就与蓝图一致。
   （基线对比：第 60 轮为 368 / 0 / 97 ⇒ 流水线这期间多交付了 12 个模块。）
4. **闸的判据只用 `stale` 字段**。全程 `stale: false`，从未据此判定持闸者已死而抢闸；
   持闸 tick 的活性另在闸外取证（`ps` 看到 `mvnw verify` → surefire 跑 `services/trade`/`services/finance`
   集成测试，`l2-guard.log` 持续写入）。
5. **「取到闸又被删」的处置**：发现闸目录消失后**没有立刻 `gate release`**（此时闸已是空闲态，
   release 会变成删别人的闸），而是先确认 `gate status` = `free`、确认无并行同步实例
   （`ps` 无 `lane.mjs` / 竞态进程、`.dev-agent/` 近 10 分钟只多了 tick 自己的 `STOP-KEEPALIVE` 与 L2 日志），
   再干净地 `gate acquire` 一次，才做提交与推送。

---

## 涉及文件

- 本轮**没有**改动任何业务代码 / 契约 / 迁移。
- 本轮新增：`docs/sessions/2026-10/2026-10-24-0923-main-sync-round61-zero-net-change.md`（本文件）
- 本轮临时文件（未入库）：`.tmp/sync-race-dense.mjs`（单进程密集竞态取闸器）
- 只读取证涉及：
  - `tools/module-progress/SYNC-MAIN.md`（本任务协议）
  - `tools/module-progress/lane.mjs`（`gate status` / `gate acquire` / `gate release`；`gateAcquire()` 实现）
  - `tools/module-progress/sync.mjs`（台账刷新器）
  - `docs/product/capability-blueprint/module-progress.md`（台账，**内容未变**）
  - `.dev-agent/l2-guard.log`、`.dev-agent/l2-run-batch-82-guard-r1.log`（持闸 tick 的 L2 结论）

---

## 关键 trade-off / 讨论

- **等闸 65 分钟而不是抢闸**：持闸 tick 是**活着的**（keepalive 每 4 分钟续期、L2 在跑），
  `stale=false`。抢闸 ⇒ 两个构建并发跑 `backend/**/target/` 与 PG 容器 ⇒ **成片假红**。
  「宁可等，不可抢」在本轮再次被验证。
- **等闸期间只做只读取证**：未跑构建、未 merge、未改工作区；期间**未执行 `git fetch`**
  （按协议第 1 步「连 `git fetch` 都先别跑」），fetch 是在取到闸之后才做的。
- **协议示例的竞态密度实测不够（第二次实证）**：2026-10-23 那轮已记录「1 秒间隔竞态跑满 2h51m 一次都没中，
  改成单进程 5ms 竞态后一轮命中」；本轮独立复现同一结论 —— 1 秒间隔的 `node` 子进程竞态跑满 **3600 秒零命中**，
  单进程 5ms `mkdirSync` 竞态 **138 秒命中**。⇒ `SYNC-MAIN.md` 第 1 步的示例竞态写法应正式修订
  （**连续两轮已提，仍留给人工裁决，本轮未擅自改协议文件**）。
- **闸的交接窗口窄到会被「迟到 release」打断**：本轮取闸成功后又失闸，说明「取到闸」与
  「持闸者彻底收尾」之间还夹着 tick 自己的收尾动作（建 `STOP-KEEPALIVE` → commit → push → release）。
  ⇒ 竞态命中后应**立即复查闸目录是否还在**，而不是假定自己已稳定持闸。
- **「空集轮次要不要留痕」**：第 49 / 50 / 60 轮（零净变更）都留了会话文档 ⇒ 本轮沿用同一惯例。

---

## 后续待办

1. **盯 `origin/main` 是否前进**：main 自 2026-10-23 09:18 起静止（已 24 小时），
   若下一轮仍是同一 tip，本任务会持续输出「无新提交」。main 侧每次前进都应重新取证 merge-base 是否 == tip。
2. **下一轮流水线的 L2 仍应重点看 `ac0b1509`**：那是本系列最近一次**真正带代码进集成头**的合并
   （20 个 main 提交），它的代码没走过本流水线的 L1/L2。本轮是空集轮，不改变这个指向。
3. **协议修订（第三次提出，留人工裁决）**：把 `SYNC-MAIN.md` 第 1 步示例竞态从
   「起 node 子进程 + sleep 1」改成「单进程内 5ms 间隔直接 `mkdirSync` 竞态」；
   并考虑给 owner 文件加**任务 kind 标识**，让同 kind 的并行实例互相让位，
   以及给 `gate release` 加「只删自己持有的闸」的校验（当前是无条件 `rmSync`，会误删别人的闸 —— 本轮亲历）。
4. **本系列既有 P0 未动**（也没权动）：
   - `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md`
   - `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`

---

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`（完整流水线协议见 `tools/module-progress/PIPELINE.md`，两者不一致时以 PIPELINE.md 为准）
- 第 59 轮（本系列最近一次实际合并）：`docs/sessions/2026-10/2026-10-24-0335-main-sync-round59-conflict-resolved-pushed.md`
  → merge commit `ac0b1509`，并入 20 个 main 提交
- 第 60 轮（上一轮，同样零新提交）：`docs/sessions/2026-10/2026-10-24-0217-main-sync-round60-zero-net-change.md`
- 闸语义的历史取证：`docs/sessions/2026-10/2026-10-23-0006-main-sync-conflict-31-files-gate-contention.md`
  （并行实例互撞、`ageSeconds` 不可用于判活）
