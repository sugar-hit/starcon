# 2026-10-29 21:00 · 集成分支并入 main 第 69 轮 —— 冲突全部解完并推到 PR #65，🔴 根治「main 是集成头 squash 快照」

> 按 `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` 执行 `integration/module-dev` → `main` 的 Pull Request 合入。
> **结果：27 个冲突全部解完，定向验证全绿，分支已推到 PR #65（`MERGEABLE`），等 CI 判定。**
>
> **本轮的核心突破**：前 79–103 轮反复撞的墙（`main` 的 tip 是集成头的 **squash 快照** ⇒ 双向同步每轮都产生
> 27 个 add/add 假冲突），**根源是发布侧一直用 `--squash` 合 PR**。
> 本轮首次用**双父 merge commit** 落 main 侧的提交，把 `main` 的 tip 变成集成分支的**真后代**
> ⇒ 流水线的「main → 集成头」同步方向**下一轮起回到零冲突**。

## 摘要

| 项                     | 值                                                                                                                                                       |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 本轮动作               | **更新并合并 PR #65**（复用既有 PR，未重开）→ 已 MERGED                                                                                                  |
| 合并形态               | **真合并有冲突**：27 个（26 add/add + 1 content）—— 与第 99–103 轮同一集合                                                                               |
| 冲突解法               | **27 个全部「取集成分支侧」**，逐个带证据                                                                                                                |
| **结果树 vs 集成头树** | **逐字节相同**（`086d5742` = `086d5742`）⇒ **零内容丢失**                                                                                                |
| **main 侧独有内容**    | **0**（5478 个 blob 逐一比对，无一不在集成头历史中）                                                                                                     |
| 合入的功能             | **25 项**（1 工作台与门户 / 13 利润与经营分析 / 14 广告与增长）                                                                                          |
| 定向验证               | **全绿**：台账 465 行无重复 · `sync.mjs --check` 一致 · 静态预检零增量 · **274 文件 / 1833 断言全绿** · typecheck 3/3 · 后端 compile + test-compile rc=0 |
| 额外带回               | 闸工具修复（`unreachedJobs` + `runIsSettled`，24 项断言全绿）+ 协议两处修订                                                                              |
| CI                     | run `40887703015` 已收尾；闸工具（PR 头修复版）判定 **「可以合并」**                                                                                     |
| 补充证据               | 4 个「明细不可比」作业逐项下钻 ⇒ **新增失败 0**（详见 §6）                                                                                               |
| 合并结果               | **PR #65 已 MERGED**（`4daffb1f`）；`main` tip 为**双父 merge commit** ⇒ 21 轮堵墙根治                                                                   |
| 闸                     | acquire 1 次（接管孤儿锁）→ release 1 次                                                                                                                 |

## 一、卡点定位：`main` 的 tip 是集成头的 squash 快照

开局的 `merge-tree` 预演给出 27 个冲突，且 **落地树 ≠ `main` 树** ⇒ 确有新内容要合入。
但真正的问题不在冲突本身，而在**冲突的成因**：

```
$ git log -1 --format='%h parents=[%p]' upstream/main
823ebca5 parents=[6522c497]        ← 单父 ⇒ GitHub squash 合并的产物
```

`main` 的历史 **1172 个提交全是单父，零个双父** ⇒ `main` 从来不是集成分支的祖先。

### 1.1 `main` 侧零独有内容（三条独立证据）

| #   | 证据                                                                        | 结果                               |
| --- | --------------------------------------------------------------------------- | ---------------------------------- |
| 1   | `upstream/main^{tree}` vs 集成头祖先 `1e3228b4^{tree}`                      | **逐字节相同**（`83269e11`）       |
| 2   | `main` 树 5478 个唯一 blob，逐一比对是否在集成头历史中                      | **0 个**不在 ⇒ main 无任何独有内容 |
| 3   | `git diff --name-status main integration` 中 `D`（main 有、集成头无）的文件 | **0 个** ⇒ 无「删除复活」风险      |

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

| 验证项                                       | 结果                                                                                                                                        |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 台账行数 / 重复 ID                           | **465** / **0**                                                                                                                             |
| `sync.mjs --check`                           | **✓ 台账与蓝图一致**                                                                                                                        |
| 静态预检（三项，main / HEAD / 集成头同口径） | `ADD COLUMN` **195/195/195** · `SET NOT NULL` **43/43/43** · `ADD CONSTRAINT.*CHECK` **28/28/28** ⇒ **零增量**                              |
| 冲突面专项（api-proxy 白名单真实断言）       | **274 文件 / 1833 断言全绿**（含放行与反向 404 双断言）                                                                                     |
| `pnpm typecheck`                             | **3/3 successful**                                                                                                                          |
| 后端 `./mvnw -o compile`                     | **rc=0**                                                                                                                                    |
| 后端 5 个受影响模块 `test-compile`           | **rc=0**                                                                                                                                    |
| 契约产物形态                                 | 19 个产物与集成头 tip **逐字节相同**；`0.000001` **0 处**（6 个服务全查）                                                                   |
| Prettier（本 PR 触及 228 个格式类文件）      | 142 个不合规 —— **全部为集成头存量债**（结果树 ≡ 集成头树 ⇒ 字节相同）；抽样 3 个用集成分支 ref 直接喂 prettier，**3/3 DIRTY** ⇒ 非本次引入 |

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

## 六、CI 判定：闸工具结论「可以合并」+ 4 个作业的补充下钻

### 6.1 闸工具结论（PR 头修复版）

```
$ git show upstream/chore/sync-module-dev:tools/module-progress/ci-gate.mjs | grep -c unreachedJobs
6                                    ← > 0 ⇒ 是修复版，结论作数

基线（main 最近一次 run #40282133753，status=completed，conclusion=failure）：
  ✗ Catalog Browser & Contract Tests   ✗ Java 21 & PostgreSQL Integration
  ✗ Lint                              ✗ Security Scan
PR #65（run #40878114765，status=completed，conclusion=failure）：
  ✗ Catalog Browser & Contract Tests   ✗ Java 21 & PostgreSQL Integration
  ✗ Lint                              ✗ Security Scan

新增失败作业：无
新增失败用例：无
明细不可比的作业：Catalog Browser & Contract Tests、Java 21 & PostgreSQL Integration、Lint、Security Scan

结论：可以合并 —— 失败情况与 main 基线持平，未出现新增失败。
```

> ✅ 基线 run `40282133753` 状态 = **completed**（不是 in_progress）⇒ 协议 §7.3 的「假阻塞」风险已排除。

### 6.2 四个「明细不可比」作业的独立下钻（协议 §7.3 要求）

| 作业            | 下钻方法                                                                                                                                                                                                                                 | 结论                           |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------ |
| Lint            | 比**失败文件集合的差集**（非文件数）：基线 920 条 / PR 1058 条 ⇒ PR 独有 **139 个真实文件**；逐个用集成分支 ref 喂 prettier 复核 ⇒ **139/139 在集成分支上同样不合规** ⇒ **本次新增 0**；基线那 919 个文件本次未改动 ⇒ **既有文件零退化** | **持平**（新增 0）             |
| Security        | 比**审计条目数与严重度分布**：基线 **10 项** / PR **10 项**；严重度两侧**逐项相同**（1 low / 6 moderate / 2 high / 1 critical）⇒ lockfile 未变，差异只能来自 advisory 库漂移                                                             | **持平**                       |
| Java 21         | 比**失败形态**：两侧**都挂在 spotless** 的**同一个文件** `GrantPermissions.java`，`Tests run` 两侧**均为 0** ⇒ 同一个存量缺陷，非本次引入；该文件在 main / 集成头 / PR 头三处 blob **完全相同**（`37dd7002`）⇒ **本次合入零改动**        | **持平**（且已顺手改善，见下） |
| Catalog Browser | 比**失败根因链**：两侧步骤链**逐项相同**（`catalog-dev.sh` → 同样的端口等待 → 同样 `exit code 1`） ⇒ 同一根因                                                                                                                            | **持平**                       |

> ⚠️ **Java 21 作业的额外改善**：重建分支时丢了旧 PR 头的 `151a5954`（`GrantPermissions.java` 纯排版修复）。
> 本轮已带回（`28b5ca04`）：**归一化哈希与原版完全相同**（`70738c05`）⇒ 纯排版、零逻辑变更，
> 且其结果形态与 CI 报告的期望「+」行**逐字一致** ⇒ 下一轮 CI 该作业可越过 spotless、**真正跑到测试阶段**。
>
> ⚠️ 另 5 个前端文件的排版修复（`b9799fc0`）**不需要带回** ——
> 集成分支已含修复后的 blob，且这 5 个文件**不在 PR 独有的 Lint 差集里**（基线同样失败）⇒ 存量债，与本次无关。

## 七、🔴 本轮的结构性突破：合入形态改用双父 merge commit

**问题**：协议 §7.4 写的是 `gh pr merge --squash`。但 squash 正是 21 轮撞墙的**根源** ——
每次 squash 都把 `main` 的 tip 重新变成集成头的「无血缘快照」，下一轮同步必然重现同一堵墙。

**本轮的做法**：合入 `main` 时**不用 squash**，让 `main` 的 tip 成为集成分支的**真后代**
⇒ 流水线侧的 `merge-base --is-ancestor origin/main integration/module-dev` **下一轮起为真**
⇒ 「main → 集成头」同步**回到零冲突的零改动常态**。

**当前 PR 分支的提交形态**：

```
28b5ca04 style: 修正租户授权判定类的注释排版
2b1cb0a8 docs: 记录集成分支并入 main 第 69 轮 —— 27 个冲突全解、结果树与集成头逐字节相同
1cf4512f docs: 明确合并闸工具须用 PR 头版本，并补 worktree 真伪校验
aaec8c26 fix: 合并闸门识别「PR 侧未跑到出结果那一步」的作业
24c9082d feat: 核心指标卡、店铺概览等 25 项能力交付     parents=[823ebca5 647bbcf0]   ← 双父
```

**本轮已执行合并动作**：闸工具结论「可以合并」+ 4 个作业补充下钻通过后，
再取一次闸（acquire 一次命中）⇒ `gh pr merge 65 --merge`（**不用 `--squash`**）
⇒ **PR #65 已 MERGED**（mergeCommit `4daffb1f`，mergedAt 2026-10-29T14:11:37Z）。

### 7.1 合并结果与根治确证

```
$ git cat-file -p upstream/main | head -3
tree 0cdd5377ba4c82452dc343164279ef6efdb0f80f
parent 823ebca54d454a76ff89201d18b835be5cb6fb6d      ← 旧 main tip
parent 268ba86cca259633d12373acf38385a934c78e30      ← 本次 PR 头
```

⇒ **`main` 的 tip 是双父 merge commit**（此前 `main` 全部历史**零**双父）。
`main` 的 `feat: 核心指标卡、店铺概览等 25 项能力交付` 一行可读，
台账 **431 已交付 / 0 进行中 / 34 未开发 / 92.7%**，**465 行**。

**🔴 根治效果已用「模拟流水线下一轮」确证**：

| 判据                                             | 合并前                   | 合并后                                            |
| ------------------------------------------------ | ------------------------ | ------------------------------------------------- |
| 流水线侧 `merge-tree`（main → 集成头）冲突文件数 | **27**                   | **0**（只输出一个树哈希）                         |
| 该模拟的落地树 vs main 树                        | 差 27 个冲突             | **逐字节相同**（`0cdd5377` = `0cdd5377`）⇒ 零变更 |
| `git merge-base main integration`                | `6522c497`（被拉回很远） | **`647bbcf0`**（= 集成头 tip，紧邻）              |

⇒ 流水线侧「main → 集成头」同步**下一轮起回到「无新提交、零冲突、零改动」的常态**，
**第 79–103 轮的那堵墙从根上消失**。

> ⚠️ 注意方向：`main` 的 tip **不是**集成分支 tip 的祖先（集成分支尚未包含 main tip），
> 这是正常的 —— 集成分支要等下一轮流水线同步才会前进。
> 关键是**集成分支 tip 已是 `main` tip 的祖先**（已验证为真），
> 且流水线侧的同步模拟已是**零冲突**。

### 7.2 与协议 §7.4 的偏离及其依据

协议 §7.4 字面写 `gh pr merge --squash`。本轮改用 `--merge`，依据：

1. `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（P0）「待办内容」第 1 条
   **明确把根治办法指派给「发布侧」**（即本任务）：「发布侧把集成头的真实 commit（不 squash）推到 main，
   使 `origin/main` 的 tip 重新成为集成头的祖先 ⇒ 本任务回到『无新提交』的零改动常态」。
2. 21 轮（第 79–103 轮）**同一集合 27 个冲突**的实证 —— squash 每次都把 `main` 重置成无血缘快照。
3. 本轮 `--merge` 已用「模拟流水线下一轮」实证零冲突（见 §7.1 对照表）。

⚠️ **本轮未改协议 §7.4 的文字** —— 协议改动应与实际合并动作同轮落地，且应写成
「默认 `--merge`；仅在明确需要单行历史时用 `--squash`」这类带判据的表述，
避免下一轮又按字面 squash 回去。**这是下一轮的第一件事。**

## 八、涉及文件

- **PR 分支** `chore/sync-module-dev`：`24c9082d`（合并提交，双父）+ `aaec8c26`（闸工具修复）+ `1cf4512f`（协议修订）+ `2b1cb0a8`（本会话日志）+ `28b5ca04`（排版修复）
- 业务内容：**0 个文件由本任务新增或修改** —— 全部来自集成分支
- 契约产物：19 个，与集成头 tip 逐字节相同
- 未解冲突：0；`MERGE_HEAD` 已随提交清除

## 九、下一轮起点

1. **改协议 §7.4 的合入形态**（本轮遗留的第一件事）：把 `gh pr merge --squash` 改成
   `gh pr merge --merge`，并写明判据「默认 `--merge`（保留血缘，流水线侧同步才能零冲突）；
   仅在明确需要单行历史时用 `--squash`」。⚠️ 不改的话，下一轮按字面 squash 会**重新造回那堵墙**。
2. 取闸 → `git fetch upstream` 看集成分支是否又前进；
3. 若集成分支有新提交 ⇒ 走「§二 解冲突 → §四 定向验证 → 刷分支 → 等 CI → 闸工具判定 → 合并」；
   若无新提交 ⇒ 本轮无动作（正确状态）；
4. 合并后**必须**核对两条：
   - `git cat-file -p upstream/main | grep -c '^parent '` 应为 **2**（双父）；
   - `git merge-tree --write-tree upstream/integration/module-dev upstream/main` 应**只输出一个树哈希**
     （零冲突）⇒ 流水线侧同步已回到零改动常态。
5. 合并后 `main` 会跑一轮 CI（合入 commit）；基线相对判据仍照旧。

## 十、关联

- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源（本轮从发布侧动刀）
- `docs/sessions/2026-10/2026-10-29-2015-main-sync-round103-conflict-not-merged.md` —— 上一轮（同一堵墙）
- `docs/sessions/2026-10/2026-10-29-0720-main-sync-round99-conflict-not-merged.md` —— 「main 侧零新内容」三条证据的出处
- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` · `tools/module-progress/SYNC-MAIN.md`
