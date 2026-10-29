# 2026-10-29 21:00 · 集成分支并入 main 第 69 轮 —— 冲突全部解完并推到 PR #65，🔴 根治「main 是集成头 squash 快照」

> 按 `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` 执行 `integration/module-dev` → `main` 的 Pull Request 合入。
> **结果：27 个冲突全部解完，定向验证全绿，分支已推到 PR #65（`MERGEABLE`），等 CI 判定。**
>
> **本轮的核心突破**：前 79–103 轮反复撞的墙（`main` 的 tip 是集成头的 **squash 快照** ⇒ 双向同步每轮都产生
> 27 个 add/add 假冲突），**根源是发布侧一直用 `--squash` 合 PR**。
> 本轮首次用**双父 merge commit** 落 main 侧的提交，把 `main` 的 tip 变成集成分支的**真后代**
> ⇒ 流水线的「main → 集成头」同步方向**下一轮起回到零冲突**。

## 摘要

| 项                     | 值                                                                    |
| ---------------------- | --------------------------------------------------------------------- |
| 本轮动作               | **更新 PR #65**（复用既有 PR，未重开）                                |
| 合并形态               | **真合并有冲突**：27 个（26 add/add + 1 content）—— 与第 99–103 轮同一集合 |
| 冲突解法               | **27 个全部「取集成分支侧」**，逐个带证据                            |
| **结果树 vs 集成头树** | **逐字节相同**（`086d5742` = `086d5742`）⇒ **零内容丢失**           |
| **main 侧独有内容**   | **0**（5478 个 blob 逐一比对，无一不在集成头历史中）                  |
| 合入的功能             | **25 项**（1 工作台与门户 / 13 利润与经营分析 / 14 广告与增长）      |
| 定向验证               | **全绿**：台账 465 行无重复 · `sync.mjs --check` 一致 · 静态预检零增量 · **274 文件 / 1833 断言全绿** · typecheck 3/3 · 后端 compile + test-compile rc=0 |
| 额外带回               | 闸工具修复（`unreachedJobs` + `runIsSettled`，24 项断言全绿）+ 协议两处修订 |
| CI                     | run `40878114765` 运行中（本轮报告时未收尾）                       |
| PR 状态                | `MERGEABLE` / `UNSTABLE`，base `823ebca5` → head `1cf4512f`          |
| 闸                     | acquire 1 次（接管孤儿锁）→ release 1 次                             |

## 一、卡点定位：`main` 的 tip 是集成头的 squash 快照

开局的 `merge-tree` 预演给出 27 个冲突，且 **落地树 ≠ `main` 树** ⇒ 确有新内容要合入。
但真正的问题不在冲突本身，而在**冲突的成因**：

```
$ git log -1 --format='%h parents=[%p]' upstream/main
823ebca5 parents=[6522c497]        ← 单父 ⇒ GitHub squash 合并的产物
```

`main` 的历史 **1172 个提交全是单父，零个双父** ⇒ `main` 从来不是集成分支的祖先。

### 1.1 `main` 侧零独有内容（三条独立证据）

| # | 证据                                                                                          | 结果                                            |
| - | --------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 1 | `upstream/main^{tree}` vs 集成头祖先 `1e3228b4^{tree}`                                          | **逐字节相同**（`83269e11`）                    |
| 2 | `main` 树 5478 个唯一 blob，逐一比对是否在集成头历史中                                          | **0 个**不在 ⇒ main 无任何独有内容              |
| 3 | `git diff --name-status main integration` 中 `D`（main 有、集成头无）的文件                        | **0 个** ⇒ 无「删除复活」风险                  |

⇒ **`main` 的 tip 不是「main 侧的新交付」，而是集成头在 2026-10-25 23:16 时刻的旧快照。**

### 1.2 这就是 21 轮撞墙的机制

流水线侧的「main → 集成头同步」每轮做 `git merge --no-ff origin/main`：
`origin/main` 是 squash 产物 ⇒ 与集成头**没有共同祖先可依** ⇒ merge-base 被拉回 85 个提交之前 ⇒
集成头在 batch-28 之后新增的文件在 base 上根本不存在 ⇒ **两侧各自新增 ⇒ add/add 冲突**。

第 79–103 轮的日志逐轮记录了同一个 27 文件冲突集合，一字不差。
`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（P0）的「待办内容」第 1 条早已写明
**根治办法在发布侧**：让 `main` 的 tip 重新成为集成头的祖先。

## 二、冲突解法：27 个全部「取集成分支侧」，逐个带证据

判定工具（协议 §4.2 入口）逐个给出：

```
$ node tools/module-progress/merge-resolve.mjs --base upstream/main --head upstream/integration/module-dev
[取集成分支侧] apps/web/src/components/dashboard/workbench-card-registry.ts
    依据：main 侧是集成分支历史上的祖先快照 ⇒ 两侧是线性演进，取新侧不丢内容
...
[取集成分支侧] apps/web/src/lib/api-proxy.ts
    依据：main 侧是集成分支历史上的祖先快照，且累积型三件套全部成立 ⇒ 取新侧在结果上等同于完整求并
    累积型三件套：集合包含 ✓ · 有序子序列 ✓ · 零删除 ✓（条目 224 → 241）
```

**本轮对该判定做了独立复核**（不只信工具输出）：

- **三件套独立验证**：`git diff --numstat main integration -- api-proxy.ts` ⇒ **删除 0 行**；
  pattern 条目 **254（main）→ 282（集成头）** ⇒ 集合包含 ✓ 零删除 ✓；
- **台账逐模块核对**：465 行模块行在合并结果与集成头 tip 之间**差异 0 行** ⇒ 无状态回退。

> ⚠️ 台账**汇总表**（已交付 431 / 未开发 34）在合并结果里一度是 main 的旧快照（406 / 59）。
> 这是 `merge-union` 驱动的求并保留了 main 侧汇总行。已按协议 §5.4 跑 `node tools/module-progress/sync.mjs`
> 重新生成 ⇒ 恢复 **431 已交付 / 0 进行中 / 34 未开发 / 92.7%**，
> 且重新生成后台账与集成头 tip **逐字节相同**。
> **教训**：台账冲突解完后**必须**跑一次 `sync.mjs`，否则汇总表会静默停留在旧快照。

## 三、决定性验证：结果树 = 集成头树

```
$ git write-tree
086d57429987cd42789ca74667a4af9129fe78af
$ git rev-parse upstream/integration/module-dev^{tree}
086d57429987cd42789ca74667a4af9129fe78af      ← 逐字节相同
```

⇒ **本次合入零内容丢失**：合并结果与集成分支 tip 在**每一个文件**上都完全一致。
（推分支前集成分支又推进了一个纯 docs 提交 `647bbcf0`，已重做合并，结论不变。）

PR 头相对集成分支 tip 的最终差异**只有 3 个工具/协议文件**，业务代码零偏差：

```
tools/module-progress/MAIN_MERGE_CRON_PROMPT.md | 28 ++++++++++++++++-
tools/module-progress/ci-gate.itest.mjs         | 61 +++++++++++++++++-
tools/module-progress/ci-gate.mjs               | 82 ++++++++++++++++++++++---
apps backend packages db 的差异                  → （空）
```

## 四、验证结果（定向，未跑本地全量 L2）

| 验证项                             | 结果                                                                |
| ---------------------------------- | ------------------------------------------------------------------- |
| 台账行数 / 重复 ID                 | **465** / **0**                                                     |
| `sync.mjs --check`                 | **✓ 台账与蓝图一致**                                                |
| 静态预检（三项，main / HEAD / 集成头同口径） | `ADD COLUMN` **195/195/195** · `SET NOT NULL` **43/43/43** · `ADD CONSTRAINT.*CHECK` **28/28/28** ⇒ **零增量** |
| 冲突面专项（api-proxy 白名单真实断言） | **274 文件 / 1833 断言全绿**（含放行与反向 404 双断言）           |
| `pnpm typecheck`                   | **3/3 successful**                                                  |
| 后端 `./mvnw -o compile`           | **rc=0**                                                            |
| 后端 5 个受影响模块 `test-compile` | **rc=0**                                                            |
| 契约产物形态                       | 19 个产物与集成头 tip **逐字节相同**；`0.000001` **0 处**（6 个服务全查） |
| Prettier（本 PR 触及 228 个格式类文件） | 142 个不合规 —— **全部为集成头存量债**（结果树 ≡ 集成头树 ⇒ 字节相同）；抽样 3 个用集成分支 ref 直接喂 prettier，**3/3 DIRTY** ⇒ 非本次引入 |

> ⚠️ 未跑本地全量 L2（协议 §5.1 默认）：本任务的增量只是「合并动作 + 冲突解法」，
> 流水线在推 `module-dev` 之前已跑过全量 L2；真正的全量闸是 CI（远端 runner，不占本机资源）。
> 冲突面专项 + typecheck + 后端编译已覆盖本次增量。

## 五、附带修复：把闸工具的「假绿灯」口子堵上

**发现**：重建分支时丢了两个只在旧 PR 头 `b3649153` 上的提交 ——
`75321f9d`（闸工具修复）与 `b3649153`（协议两处修订）。
丢了前者，协议 §7.3 要求的判定能力就没了。

**修复过程中的一个真实坑**：直接 `git cherry-pick 75321f9d` 会把
`ci-gate.mjs` 与 `ci-gate.itest.mjs` **混成两个不同演进线的版本** ——
main/集成头那份 `ci-gate.mjs`（234 行）与 `75321f9d` 那份**行数相同但内容不同**，
三方合并后 `itest` 引用了 `ci-gate.mjs` 里**并不存在**的 `runIsSettled` ⇒ `SyntaxError`。
> ⚠️ **判据**：同名同长度的文件**不等于同一版本**。必须用 blob hash 区分演进线
> （本轮 `055619f1` vs `7d17a021`），并**成对**取同一提交的两份文件。
> 最终解法：不走三方合并，**直接从 `75321f9d` 取两份文件的 blob** ⇒ 与源提交逐字节相同。

**验证**：`node tools/module-progress/ci-gate.itest.mjs` ⇒ **24 项断言全绿**，其中关键三条：
「PR 侧无明细 + 基线有明细 ⇒ 阻塞」「在飞 run 不算收尾」「取消的 run 不得当作有效判据」。

**协议修订**（`b3649153`）同样带回：§7.1 加 worktree 真伪硬校验、§7.3 闸工具副本硬校验、§9 两条禁令。
⇒ 修完后 PR 头的闸工具 `unreachedJobs` 计数 = **6**（> 0 ⇒ 是修复版）。

## 六、🔴 本轮的结构性突破：合入形态改用双父 merge commit

**问题**：协议 §7.4 写的是 `gh pr merge --squash`。但 squash 正是 21 轮撞墙的**根源** ——
每次 squash 都把 `main` 的 tip 重新变成集成头的「无血缘快照」，下一轮同步必然重现同一堵墙。

**本轮的做法**：合入 `main` 时**不用 squash**，让 `main` 的 tip 成为集成分支的**真后代**
⇒ 流水线侧的 `merge-base --is-ancestor origin/main integration/module-dev` **下一轮起为真**
⇒ 「main → 集成头」同步**回到零冲突的零改动常态**。

**当前 PR 分支的提交形态**：

```
1cf4512f docs: 明确合并闸工具须用 PR 头版本，并补 worktree 真伪校验
aaec8c26 fix: 合并闸门识别「PR 侧未跑到出结果那一步」的作业
24c9082d feat: 核心指标卡、店铺概览等 25 项能力交付     parents=[823ebca5 647bbcf0]   ← 双父
```

**本轮未执行合并动作**（CI 尚未收尾，协议 §7.3 的闸门结论未出）。
**下一轮取闸合并时，须用 `gh pr merge --merge`（不用 `--squash`）**，
否则 21 轮堵墙会原样复现。⚠️ 这一条**偏离了协议 §7.4 的字面写法**，但依据是
`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（P0）的「待办内容」第 1 条
（明确指派给**发布侧**，即本任务），以及 21 轮同集合冲突的实证。
**本轮未改协议 §7.4 的文字** —— 该改动应与实际的合并动作同一轮落地，避免协议与事实脱节。

## 七、涉及文件

- **PR 分支** `chore/sync-module-dev`：`24c9082d`（合并提交，双父）+ `aaec8c26`（闸工具修复）+ `1cf4512f`（协议修订）
- 业务内容：**0 个文件由本任务新增或修改** —— 全部来自集成分支
- 契约产物：19 个，与集成头 tip 逐字节相同
- 未解冲突：0；`MERGE_HEAD` 已随提交清除

## 八、下一轮起点

1. 取闸 → 先 `git fetch upstream` 看集成分支是否又前进；
2. 查 PR #65 的 CI run `40878114765` 是否 `status=completed`；
3. 用 **PR 头那份**闸工具判定（`unreachedJobs` 计数必须 > 0）：
   `cd <PR 头 worktree> && node tools/module-progress/ci-gate.mjs --pr 65`；
4. 闸门结论为「可以合并」⇒ 再取一次闸 ⇒ **`gh pr merge 65 --merge`（不用 --squash）**；
5. 合并后核对：`git log -1 --format='%p' upstream/main` 应为**双父**，
   且 `git merge-base --is-ancestor upstream/main upstream/integration/module-dev` 应为 **TRUE**
   ⇒ 下一轮流水线同步即可回到零冲突常态。

## 九、关联

- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源（本轮从发布侧动刀）
- `docs/sessions/2026-10/2026-10-29-2015-main-sync-round103-conflict-not-merged.md` —— 上一轮（同一堵墙）
- `docs/sessions/2026-10/2026-10-29-0720-main-sync-round99-conflict-not-merged.md` —— 「main 侧零新内容」三条证据的出处
- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` · `tools/module-progress/SYNC-MAIN.md`
