# 2026-10-25: main → 集成头同步（并发实例的交接与复核 · 无新提交，未同步）

> 本文件记录的是**同一轮同步任务的一个并发实例**（下文称「本实例」）的完整过程。
> 同一轮已由另一实例完成并推送（见「关联」第 1 条），本实例的价值在于：
> **① 接手并释放被中断 tick 遗留的陈旧闸；② 对另一实例的结论做独立复核。**

- 时间：2026-10-25 00:15:00（起手读参数）/ 00:15:5x（起手 `gate status` 判定）/ 01:30:55（取闸成功，TTL 接管）/ 01:31–01:36（复核 + 收尾）
- 集成头 worktree：`/Users/guan/Projects/starcon-worktrees/integration`（主工作区 `/Users/guan/Projects/starcon` 全程未做任何 git 操作）
- 分支：`integration/module-dev`
  - 起手 HEAD = `7ecd64f5`「merge(12.3.2.3): 自动匹配规则」
  - 取闸后 HEAD = `988d6632`「docs(sessions): 集成头同步 main 第 66 轮 —— 零新提交，未同步」（**取闸前 11 秒由并发实例提交**）
  - 收尾时 `origin/integration/module-dev` = `988d6632` = 本地 HEAD（ahead/behind = 0/0）
- `origin/main` tip：`6522c497`（2026-10-23 09:18:01 +0800「chore: 同步集成分支最新进度」，**约 40 小时未再前进**）
- `merge-base --is-ancestor origin/main integration/module-dev` ⇒ **成立**（exit 0）
- `git log --oneline integration/module-dev..origin/main` ⇒ **空**（0 个）；`origin/main..integration/module-dev` ⇒ **932 个**
- 结果：**无新提交，未同步**（NO_UPDATE）—— 且该结论已由并发实例先行落地并推送
- 闸：起手 `held` / `stale: false` / `ageSeconds: 91`（TTL 4200）⇒ **等闸约 75 分钟**；
  01:30:55 由 TTL 过期（01:28:55）后的 `mkdirSync` 原子接管取得；本轮已 `gate release`

---

## 摘要

本实例**没有产生合并，也没有推送任何代码**。`origin/main` 的 tip `6522c497` 早已被第 59 轮的 merge commit
`ac0b1509` 并入集成头 ⇒ `integration/module-dev..origin/main` 为空集 ⇒ 按协议第 2 步的 NO_UPDATE 分支，
**不 merge、不造空 merge commit**。

本实例的实际贡献是两件事，**都不是搬运 commit**：

1. **接手并释放被中断 tick 遗留的陈旧闸**。起手时闸被 owner pid `59636` 持有，随后在 00:18:55 被 pid `88196`
   重新持有；这两个 pid **都已死**，闸目录里只剩 `owner` 一个文件、**没有任何 keepalive 续期**，
   目录 mtime 冻结在 00:18:55 ⇒ 闸的 TTL 只能自然走到 01:28:55。本实例按协议第 1 步跑「密集 `acquire` 竞态」
   （1 秒一次，直接竞 `mkdirSync`，失败无副作用），**未清闸、未 force**，
   最终由 TTL 过期后的原子接管取得闸 ⇒ 收尾 `gate release`，**把闸交还流水线**（否则下一轮流水线 tick 要再等 70 分钟）。
2. **对并发实例的结论做独立复核**（见下节「独立复核」）—— 结论一致，且本实例额外确认了
   **已上远端的代码树与 batch-90 全量 L2 GREEN 的树逐字节相同**。

---

## 独立复核（对并发实例的产出逐项取证）

| 项 | 复核方法 | 结果 |
| --- | --- | --- |
| main 无新提交 | `git fetch origin --prune` 后 `git rev-parse origin/main` | `6522c497`（与起手读数一致） |
| 同上 | `git merge-base --is-ancestor origin/main integration/module-dev` | **成立**（exit 0） |
| 同上 | `git merge-base origin/main integration/module-dev` | `6522c497` = **tip 本身** |
| 同上 | `git rev-list --count integration/module-dev..origin/main` | **0** |
| 远端一致性 | `git ls-remote origin refs/heads/main refs/heads/integration/module-dev` | main `6522c497` / integration `988d6632` |
| 推送到位 | `git rev-parse HEAD` vs `origin/integration/module-dev` | `988d6632` = `988d6632`（**ahead/behind = 0/0**） |
| 台账一致 | `node tools/module-progress/sync.mjs --check` | **✓ 台账与蓝图一致** |
| 台账数字 | `docs/product/capability-blueprint/module-progress.md` | 465 总数 / **400 已交付 / 0 进行中 / 65 未开发** |
| 预检（日志留痕） | `.dev-agent/sync-main-r66-compile.log` | 9 行，`BUILD FAILURE` / `[ERROR]` 命中 **0** |
| 预检（日志留痕） | `.dev-agent/sync-main-r66-typecheck.log` | `Tasks: 3 successful, 3 total` |
| **已推代码 = L2 绿代码** | `git diff --stat 988d6632 7ecd64f5 -- backend/ apps/ packages/ db/ services/ workers/ tools/ infra/ deploy/` | **空** ⇒ 代码路径逐字节相同（988d6632 只多两个 docs 文件） |

⇒ 并发实例的结论与产出**全部复核通过**，本实例**无需重做**任何一步。

---

## 决策 / 结论

1. **本轮结果 = 无新提交，未同步（NO_UPDATE）**。`origin/main` 自 2026-10-23 09:18 起静止约 40 小时，
   tip 仍是 `6522c497`；集成头已领先 main **932 个提交**。本实例**未 merge、未造空 merge commit**。
2. **不跑预检**：本实例**没有要 push 的代码**（并发实例已把 `988d6632` 推上远端，本实例只追加一个纯 docs 提交）。
   代码侧的可编译性由**更强**的两条证据覆盖：① batch-90 的全量 L2 GREEN（12 个退出码全 0）；
   ② 上表「已推代码 = L2 绿代码」逐字节比对为空。
   ⇒ 纯 docs 提交不可能引入编译/类型失败 ⇒ 重跑 `mvnw compile` / `typecheck` 只会**凭空造热量**（发热高峰属流水线 L2 的专属预算），
   且可能与下一轮 tick 的 L2 抢 `backend/**/target/` 与 PG 容器造成**假红**。
3. **闸的判据只用 `stale` 字段**，全程未按 `ageSeconds` 自行判断，也未用 owner PID 判活
   （协议明写：`gate acquire` 是短命进程，**owner PID 不是存活证据**）。
   本实例的活性取证全部在**闸外**做（`ps` / L2 日志 / keepalive 目录 / reflog）。
4. **并发实例的仲裁是安全的**：两轮靠 `mkdirSync` 的原子性互斥，**同一时刻只有一方能持闸**。
   并发实例 01:28:56 接管 TTL 点并完成推送，本实例 01:30:55 在它 `gate release` 之后的交接窗口里取得闸 ⇒
   **写操作全程不重叠**。这不是「双跑同步」。
5. **batch-90 的 session doc 仍然不提交**（与并发实例同一判断）：它带着未填的
   `<!-- THREE_PIECE_PLACEHOLDER -->`，是被中断 tick 的**在途产物**；
   收口（填占位符 + 记三件套结论）属**流水线 tick** 的职责，本任务边界是「只搬运 commit、不改业务代码」。
   **原样保留、不 `git checkout --` 抹掉**（第 65 轮教训：抹掉就是弄丢持闸 tick 的在途工作）。

---

## 涉及文件

- 本实例**没有**改动任何业务代码 / 契约 / 迁移，**没有**产生 merge commit，**没有**推送代码。
- 本实例新增：
  - `docs/sessions/2026-10/2026-10-25-0131-main-sync-concurrent-instance-gate-handover.md`（本文件）
- 本实例**提交并推送**的既有变更：**无**（并发实例的 `988d6632` 已先行推送，本实例只追加本文件这一个纯 docs 提交）。
- **未提交、未触碰**（明确留给流水线）：
  - `docs/sessions/2026-10/2026-10-24-batch-90-merge-12-3-2-3-whitelist-union.md`
    （batch-90 的 session doc，**仍是未跟踪文件**，正文占位符未填）
- 只读取证涉及：
  - `tools/module-progress/SYNC-MAIN.md`（本任务协议）、`tools/module-progress/PIPELINE.md`（§5.3 收尾三件套 / §5.6 分工）
  - `tools/module-progress/lane.mjs`（`gate status` / `gate acquire` / `gate release` 的实现语义）
  - `tools/module-progress/sync.mjs`（台账刷新器 + `--check`）
  - `.dev-agent/l2-run-batch-90.log`（batch-90 的 L2 绿证据）、`.dev-agent/keepalive-b90.sh`（闸续期机制）
  - `.dev-agent/sync-main-r66-compile.log` / `.dev-agent/sync-main-r66-typecheck.log`（并发实例的预检留痕）
  - `docs/sessions/2026-10/2026-10-25-0129-main-sync-round66-zero-net-change.md`（并发实例的同轮留痕）

---

## 关键 trade-off / 讨论

- **🔴 等闸 75 分钟（本系列最长一次）**，比第 65 轮的 52 分钟还长。原因是这次要跨过**两段**等待：
  ① 00:15–00:18:55 闸由**活着的 batch-90 tick** 持有（它正在跑全量 L2，`mvnw verify` 约 58 分）⇒ 按协议**必须等**；
  ② 00:18:55 之后该 tick **崩溃**（L2 已 GREEN，但没跑到 commit/push/release），闸变成**陈旧闸** ⇒
  又要等 TTL 自然过期到 01:28:55 才能原子接管。**全程没有清闸、没有 force、没有提前 `rmSync`** ——
  「没拿到过闸就 release」这条禁令在本实例被严格遵守（否则会删掉别人活着的闸）。
- **🔴 本轮真正卡住的不是 main，是一次崩溃的流水线 tick**：batch-90 在 23:15:52 合并 12.3.2.3、
  23:16:59 起跑全量 L2、**00:18:19 L2 GREEN**，随后**崩溃**：keepalive 停摆、说明文件没 commit、没 push、闸没 release。
  崩溃点定位到字节级 —— 说明文件写完 310 行，但「收尾三件套」一节只剩 `<!-- THREE_PIECE_PLACEHOLDER -->`。
  ⇒ 并发实例接手把该批成果推上了远端（`ac7e569c` + `7ecd64f5`），并把台账刷新提交进 `988d6632`。
- **🟡 「等闸 75 分钟」不等于「什么都没做」**：等闸窗口内的**只读取证**（`ps` / L2 日志 / keepalive 目录 /
  `git reflog` / `git ls-remote`）恰好把崩溃 tick 的时间线、崩溃点、以及「已推代码 = L2 绿代码」这条
  最关键的 push 安全性证据全部拿到了 —— 这些正是流水线下轮认领 batch-90 收尾时需要的信息。
- **🟡 没为「空集」硬造 merge commit**：协议第 2 步的 NO_UPDATE 分支明确要求不 merge；
  即使本实例手上有 batch-90 的 commit 可以推，也没有借机造一个 `merge(main)` 空提交来「看起来干了活」。
- **🟡 关于「同一轮两个实例各写一份留痕」**：本实例选择**另起文件名**而不是改并发实例那份，
  以免出现「两轮文档互相覆盖 / 口径打架」；本文件只记录本实例**特有**的事实（并发交接、闸的接管链、独立复核），
  不复述并发实例已经写过的内容。

---

## 后续待办

1. **🔴 batch-90 的收尾要认领（仍未做）**：它的 session doc 还是未跟踪文件（带未填占位符）。
   下一轮流水线 tick（02:00）应 ① 填完占位符并补记三件套结论；② **不要重复 push 已推过的 commit**
   （`ac7e569c` / `7ecd64f5` / `988d6632` 均已上远端）。
2. **盯 `origin/main` 是否前进**：main 自 2026-10-23 09:18 起静止约 40 小时，
   若下一轮仍是同一 tip `6522c497`，本任务会持续输出「无新提交」；main 每次前进都要重新取证 merge-base 是否 == tip。
3. **协议修订建议（第九次提出，留人工裁决）** —— `tools/module-progress/SYNC-MAIN.md`：
   - 第 1 步补一条「**同一任务可能有多轮实例并发竞闸**」（本实例与并发实例实测同时在跑）
     ⇒ 靠 `mkdirSync` 原子性互斥即可，但**汇报里要写明是否有并发实例**，避免重复产出两份同轮留痕；
   - 第 3 步补一条「**起手工作区脏、且脏文件归属被中断的上一轮 tick 时**：先判定该 tick 的成果是否已过 L2
     （`l2-run-*.log` 末行 `L2 GREEN` + commit 时间早于 L2 起始时间），已过 L2 ⇒ 可连同台账一并 push；
     未过 L2 ⇒ 停下汇报」（并发实例已按此路径走通，本实例独立复核通过）；
   - `gate release` 加「只删自己持有的闸」校验（当前是无条件 `rmSync`，会误删别人的闸 —— 第 61 轮亲历）。
4. **本系列既有 P0 未动**（也没权动）：
   - `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md`
   - `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`
   - `docs/backlog/2026-10/2026-10-23-pipeline-service-gap-and-blueprint-cycle-blockers.md`（流水线零派发的结构性阻塞）

---

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`（完整流水线协议见 `tools/module-progress/PIPELINE.md`，两者不一致时以 PIPELINE.md 为准）
- **同一轮的并发实例留痕**：`docs/sessions/2026-10/2026-10-25-0129-main-sync-round66-zero-net-change.md`
  → 提交 `988d6632`（台账刷新 + 该实例的 session doc），01:30:44 提交、已推送
- 第 65 轮（上一轮，同样零新提交）：`docs/sessions/2026-10/2026-10-24-2215-main-sync-round65-zero-net-change.md`
- 第 59 轮（本系列最近一次实际合并）：`docs/sessions/2026-10/2026-10-24-0335-main-sync-round59-conflict-resolved-pushed.md`
  → merge commit `ac0b1509`，并入 20 个 main 提交
- batch-90（本轮附带补推的那批）：`docs/sessions/2026-10/2026-10-24-batch-90-merge-12-3-2-3-whitelist-union.md`（**未跟踪，待流水线认领**）
- 闸语义的历史取证：`docs/sessions/2026-10/2026-10-23-0006-main-sync-conflict-31-files-gate-contention.md`
