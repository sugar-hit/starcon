# 2026-10-24: 第 65 轮 main → 集成头同步（零新提交，未同步 · 等闸 52 分钟）

- 时间：2026-10-24 22:15:00（起手）/ 22:15:0x（起手取闸判定）/ 23:07:49（取闸成功）/ 23:08–23:14（取证 + 收尾）
- 集成头 worktree：`/Users/guan/Projects/starcon-worktrees/integration`
- 分支：`integration/module-dev`
  - 起手 HEAD = `759d2503`「merge(12.3.2.2): 差异处理」
  - 取闸后 HEAD = `ea31272d`「docs(batch-89): 12.3.2.2 —— 差异处理并入集成头，全量 L2 绿灯」
    （**在等闸期间被持闸 tick 推进**，见「关键 trade-off」第 3 条）
  - 收尾时 `origin/integration/module-dev` = `ea31272d` = 本地 HEAD，ahead/behind = 0/0
- `origin/main` tip：`6522c497`（2026-10-23 09:18:01 +0800「chore: 同步集成分支最新进度」，自该时刻起 **约 38.8 小时未再前进**）
- `merge-base(origin/main, integration/module-dev)` = `6522c497` —— **等于 `origin/main` tip 本身**
- 结果：**无新提交，未同步**（NO_UPDATE）
- 闸：起手 `held` / `stale: false` / `ageSeconds: 234`（TTL 4200）⇒ **等闸约 52 分钟**，23:07:49 取得；本轮已 `gate release`

---

## 摘要

本轮同步任务**没有产生合并**。`origin/main` 的 tip `6522c497` 早已被**第 59 轮**的 merge commit
`ac0b1509` 并入集成头，因此 `merge-base --is-ancestor origin/main integration/module-dev` 成立，
`integration/module-dev..origin/main` 为空集 —— 没有可搬运的提交。集成头在 main tip 之后又前进了 **928 个提交**。

与第 64 轮最大的不同是**取闸过程**：第 64 轮等闸约 10 分钟，本轮**等闸约 52 分钟** ——
本系列最长的一次。原因是持闸 tick 这一轮跑完了**完整的 L2**：
`mvnw verify`（约 59 分钟，`surefire` fork 1→6 逐个推进）+ `@starcon/web` 的 Next.js production build，
直到 23:06:55 才打出 `===== L2 GREEN =====`，随后提交 + 推送 + 释放闸，本轮在 23:07:49 抢到交接窗口。

本轮还实测到**起手工作区是脏的**，且归属是**持闸 tick 的在途产物**（不是上一轮同步任务的残骸），
取闸后已自行变干净 —— 这是「脏工作区先分清归属」这条经验的新样本。

---

## 决策 / 结论

1. **`origin/main` 已完全包含在集成头内** —— `merge-base` 直接落在 `origin/main` 的 tip 上，
   说明第 59 轮之后 main 没有再前进过。取证四件套：
   - `git ls-remote origin refs/heads/main` = `6522c497…`（**实时远端**，与本地 `origin/main` 一致）
   - `git merge-base --is-ancestor origin/main integration/module-dev` ⇒ 成立（exit 0）
   - `git merge-base integration/module-dev origin/main` = `6522c497` ⇒ **等于 tip 本身**
   - `git log --oneline integration/module-dev..origin/main` ⇒ **空**（0 个）；`origin/main..integration/module-dev` ⇒ **928 个提交**
   - 附带确认 `git ls-remote origin refs/heads/integration/module-dev` = `ea31272d` = 本地 HEAD ⇒ 集成头已推到位，无需 push 代码
2. **不跑快速预检**（沿用第 60–64 轮结论）。协议第 4 步的 `mvnw -q compile` + `pnpm typecheck`
   是**合并之后**的守门；本轮没有 merge、没有代码变化。更强的一条证据是**逐路径校验**：
   `git diff --stat 759d2503..ea31272d -- backend/ apps/ packages/ db/ services/ workers/ tools/` ⇒ **空**，
   即当前代码树与 23:06:55 刚写下 `===== L2 GREEN =====` 的那棵树**逐字节相同**
   （L2 证据：`.dev-agent/l2-run-batch-89.log` 末行 `===== L2 END 2026-10-24 23:06:55 =====`）。
   为空集重跑编译只会凭空造热量（发热高峰是流水线 L2 的专属预算），撞车还会产生**假红**。
3. **台账已刷新且零漂移**。`node tools/module-progress/sync.mjs --check` 输出
   `✓ 台账与蓝图一致`，台账数字 **399 已交付 / 0 进行中 / 66 未开发**（共 465）。
   （基线对比：第 64 轮为 392 / 0 / 73 ⇒ 期间的 392→399 增量来自流水线 batch-89 的合并 commit `ea31272d`，**与本轮同步无关**。）
4. **闸的判据只用 `stale` 字段**。起手 `gate status` = `held` / `stale: false` / `ageSeconds: 234`，
   按协议第 1 步跑「密集 `acquire` 竞态」循环（1 秒一次，直接竞 `mkdirSync`，不低频观测 `status`），
   23:07:49 取得；复查 `gate status` = `held` / `stale: false` / `ageSeconds: 0`，
   且闸目录 `owner` 文件 = `58822` / `2026-10-24T15:07:49.341Z` ⇒ 是本轮写入的。
5. **闸外活性取证**：起手 owner 文件里的 PID `35560` **查无此进程**，但这**不能**用来判闸已释放 ——
   协议第 1 步已写明 `gate acquire` 是短命进程、owner PID 不是存活证据，**唯一判据是 `stale`**。
   本轮是这条规则的又一次正向印证：PID 已死，闸却仍被活着的 tick 持到 23:07 之后。
   真正的活性证据在闸外：整个等闸窗口内都能看到该 tick 的 `mvnw verify` / `next build` 进程。
6. **等闸期间只做只读取证**：全程未跑 `git fetch`、未跑任何构建，未在等闸窗口里制造第二个 `mvnw` / `pnpm` 进程。
   只执行过 `gate status` / `cat owner` / `ps` / `git log` / `git reflog` / `git status` 这类只读命令。

---

## 涉及文件

- 本轮**没有**改动任何业务代码 / 契约 / 迁移。
- 本轮新增：`docs/sessions/2026-10/2026-10-24-2215-main-sync-round65-zero-net-change.md`（本文件）
- 只读取证涉及：
  - `tools/module-progress/SYNC-MAIN.md`（本任务协议）
  - `tools/module-progress/PIPELINE.md`（§5.6 确认「merge main 是同步任务的职责」，与本任务无冲突）
  - `tools/module-progress/lane.mjs`（`gate status` / `gate acquire` / `gate release`）
  - `tools/module-progress/sync.mjs`（台账刷新器，含 `--check` 只校验模式）
  - `docs/product/capability-blueprint/module-progress.md`（台账，**内容未变**）
  - `.dev-agent/l2-run-batch-89.log`、`.dev-agent/l2-guard-b89.log`（L2 结果取证）
  - `docs/sessions/2026-10/2026-10-24-1725-main-sync-round64-zero-net-change.md`（上一轮留痕格式参照）

---

## 关键 trade-off / 讨论

- **🔴 等闸 52 分钟（本系列最长）**：持闸 tick 跑了**完整的 L2**（`mvnw verify` 约 59 分钟 + Next build）。
  闸覆盖整个 tick，不只是 L2 那一段（第 64 轮已记录此规律）。协议第 1 步的「密集竞态 `acquire`」写法
  在这种长窗口下是唯一可靠的拿闸方式 —— 本轮 1 秒一次的密度在 tick 释放的瞬间即命中，未错过交接窗口。
- **🔴 起手工作区是脏的，但归属明确**：起手 `git status --porcelain` =
  ` M docs/product/capability-blueprint/module-progress.md` +
  `?? docs/sessions/2026-10/2026-10-24-batch-89-reclaim-1-lane-dispatch-1.md`。
  这**不是**上一轮同步任务的残骸，而是**持闸 tick 正在写的在途产物**（batch-89 的台账刷新 + 会话日志）——
  文件名与内容都指向 batch-89。取闸后再次 `git status --porcelain` ⇒ **空**（tick 已把它们提交为 `ea31272d`）。
  ⇒ 再次印证「**等闸期间的脏工作区不要 `git checkout --` 抹掉**」：抹掉就是把持闸 tick 的在途工作弄丢。
  本轮全程只读、只等，取闸后工作区自然干净。
- **🔴 起手 HEAD 在取闸后已过期（第二次实测）**：`759d2503` → `ea31272d`，两个 commit 都发生在等闸窗口内，
  且都在**我这个 worktree** 里（reflog 铁证）：
  ```
  ea31272d integration/module-dev@{0}: commit: docs(batch-89): 12.3.2.2 —— 差异处理并入集成头，全量 L2 绿灯
  759d2503 integration/module-dev@{1}: merge feat/12-3-2-2: Merge made by the 'ort' strategy.
  9a0f3ba1 integration/module-dev@{2}: commit: docs(batch-88): 1.5.1.2 / 12.3.2.1 / 1.3.1.2 —— 3 条车道并入集成头，全量 L2 绿灯
  ```
  ⇒ 协议第 3 步的 `PRE=$(git rev-parse HEAD)` **必须在拿到闸之后取**（协议当前顺序恰好是对的），
  任何「起手 HEAD」都不能当作本轮基线来写汇报。
- **闸在等闸期间被反复续期**：`ageSeconds` 在窗口内多次归零（234 → 118 → 21 → 88 → 39 → 49 …），
  与 PIPELINE.md 记载的 `touch .dev-agent/dispatch.gate/keepalive-<hmmss>` 续期写法一致
  ⇒ 持闸 tick 在跑 L2 期间主动续 TTL，保证闸覆盖到 push 结束。
- **owner PID 已死但闸仍 held**：再次证实协议里那条警告的价值 —— 任何以 PID 判活的写法都会在本轮误判成「闸已释放」，
  从而在一个活着的 tick 的工作树上抢闸。本轮严格只用 `stale`。
- **「空集轮次要不要留痕」**：第 49 / 50 / 60 / 61 / 62 / 63 / 64 轮（零净变更）都留了会话文档 ⇒ 本轮沿用同一惯例。
- **本轮不需要推代码**：`origin/integration/module-dev` 与本地分支在取闸后完全一致（0/0），
  且本轮唯一的产出是这份 session doc（纯 `docs/` 变更，不触及任何代码路径）⇒
  按前几轮惯例提交并推送留痕即可，不触发「编译不过的集成头绝不上推」这条禁令的适用场景。

---

## 后续待办

1. **盯 `origin/main` 是否前进**：main 自 2026-10-23 09:18 起静止（已约 38.8 小时），
   若下一轮仍是同一 tip，本任务会持续输出「无新提交」。main 侧每次前进都应重新取证 merge-base 是否 == tip。
2. **下一轮流水线的 L2 仍应重点看 `ac0b1509`**：那是本系列最近一次**真正带代码进集成头**的合并
   （20 个 main 提交），它的代码没走过本流水线的 L1/L2。本轮是空集轮，不改变这个指向。
3. **协议修订（第七次提出，留人工裁决）**：
   - 在 `SYNC-MAIN.md` 第 1 步补一句「**拿到闸之后重新取 HEAD / reflog**」——
     本轮实测证明持闸 tick 会在同一 worktree 里推进分支指针（`759d2503` → `ea31272d`），起手快照不能当基线；
   - 在第 1 步补一句「**owner PID 已死 ≠ 闸已释放**」（本轮 owner PID `35560` 查无此进程，闸却仍 held）；
   - 在第 3 步补一句「**脏工作区先分清归属**」——
     本轮实测到「持闸 tick 的在途产物」这一种脏法（台账 + 会话日志，指向 batch-89）；
     归属明确就等它提交，归属不明才停下汇报，**任何情况下都不要自作主张 `git checkout --` 抹掉别人的在途工作**；
   - 给 `gate release` 加「只删自己持有的闸」的校验（当前是无条件 `rmSync`，会误删别人的闸 —— 第 61 轮亲历）；
   - 在第 1 步把「等闸时长」的预期写明：持闸 tick 跑完整 L2 时可长达 **1 小时以上**，
     不要因为等待时间超预期就误判成陈旧闸并去抢闸（`stale` 才是唯一判据）。
4. **本系列既有 P0 未动**（也没权动）：
   - `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md`
   - `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`

---

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`（完整流水线协议见 `tools/module-progress/PIPELINE.md`，两者不一致时以 PIPELINE.md 为准）
- 第 64 轮（上一轮，同样零新提交）：`docs/sessions/2026-10/2026-10-24-1725-main-sync-round64-zero-net-change.md`
- 第 59 轮（本系列最近一次实际合并）：`docs/sessions/2026-10/2026-10-24-0335-main-sync-round59-conflict-resolved-pushed.md`
  → merge commit `ac0b1509`，并入 20 个 main 提交
- 第 60 轮：`docs/sessions/2026-10/2026-10-24-0217-main-sync-round60-zero-net-change.md`
- 闸语义的历史取证：`docs/sessions/2026-10/2026-10-23-0006-main-sync-conflict-31-files-gate-contention.md`
  （并行实例互撞、`ageSeconds` 不可用于判活）
