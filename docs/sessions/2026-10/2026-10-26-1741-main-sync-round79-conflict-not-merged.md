# 2026-10-26: 集成头同步 main 第 79 轮 —— 冲突未并入（main 首次前进，7 个语义型冲突）

## 摘要

本轮**结果是冲突未并入**：取到闸 → `git fetch origin --prune` → `merge-base --is-ancestor` ⇒ `HAS_UPDATE`
⇒ `git merge --no-ff origin/main` **报 7 个冲突** ⇒ 按协议 `git merge --abort`（退出码 0）⇒
**没有预检、没有 push、没有产生 merge commit**，并入的 main 提交数 = **0**。

与第 76 / 77 / 78 轮（全是 `NO_UPDATE`）不同，**`origin/main` 的 tip 这次真的动了**：
`6522c497`（2026-10-23「chore: 同步集成分支最新进度」）→ **`823ebca5`「Integration/module dev (#66)」。
该提交是 GitHub **squash merge**（**单父**，父 = `6522c497`），即「集成分支快照整体压进 main」，
不是普通的 merge commit ⇒ 它与集成头的关系是**两个平行的同源快照**，冲突面天然比「线性前进」大。

本轮的**主要成本在闸**：起手 `gate status` = `held / ageSeconds 2801 / stale=false`，
本轮按协议**只做只读取证 + 竞态 `gate acquire`**（1 秒密度），**竞态跑了 1216 秒**，
最终由 `acquire` **自己走 TTL 接管分支**取到闸（闸龄 4163s → TTL 4200s 越线后命中）。

## 决策 / 结论

1. **`origin/main` 真的前进了，且是 squash 形态。**

   | 项 | 值 |
   | --- | --- |
   | `origin/main` tip | `823ebca5`「Integration/module dev (#66)」 |
   | 父 | **仅 1 个**：`6522c497`（= 与集成头的 merge-base） |
   | 待并入提交数 | **1** |
   | 与集成头的树差异（2-dot） | **51 文件 / +5311 / −18936** |
   | 3-dot diff 规模 | 1032 文件 / +510134 / −45765（= squash 打包进去的整条集成分支历史） |

   ⇒ **`823ebca5` 是 squash**：它的父不是集成分支 tip，而是 main 的旧 tip `6522c497`。
   这解释了为什么「只差 1 个提交」却「差 51 个文件」：差的这 51 个文件是**集成分支在此之后继续演进**的部分。

2. **合并的 7 个冲突是 `add/add`（6 个）+ `content`（1 个），且全部是「同一文件两侧各自新增」。**

   | 冲突文件 | 类型 | ours→theirs 行差 | 性质 |
   | --- | --- | --- | --- |
   | `apps/web/src/components/dashboard/workbench-card-registry.ts` | add/add | +1 / −11 | 语义 |
   | `apps/web/src/components/dashboard/workbench-layout-model.test.ts` | add/add | +1 / −6 | 语义 |
   | `apps/web/src/lib/api-proxy.ts` | **content** | +0 / −86 | 语义（同一区域两侧都改） |
   | `backend/services/finance/.../FundAnalysisIntegrationTest.java` | add/add | +2 / −9 | 语义 |
   | `backend/services/reporting/.../ReportingFinancePort.java` | add/add | +0 / −49 | 语义 |
   | `backend/services/trade/.../AnomalyOrderIntegrationTest.java` | add/add | +35 / −162 | 语义 |
   | `backend/services/trade/.../AnomalyOrderTenancyIntegrationTest.java` | add/add | +11 / −31 | 语义 |

   ⇒ **每一行都是 ours 是 theirs 的超集**（集成头侧是**更新**的一版，main 侧是 PR 时的**旧**快照）。

3. **本轮冲突是「语义型」，不是历史上那种「纯 Prettier 格式漂移」**（这点与第 45 / 53 / 54 轮不同）。
   逐文件取证（`git diff <theirs>:<file> <ours>:<file>`）：`ReportingFinancePort.java` 的 49 行差额
   是**实打实的新能力**（`ReceiptCurrencySummary` / `ReceiptPage` / `receipts(...)` ——
   12.2.1.1 收款单/回款口径），不是排版。
   ⇒ 「两侧都过一遍 Prettier 再比」的老办法**本轮不适用**，不能照抄第 45 轮的裁决口径。

4. **`merge-union` 驱动器按预期只兜底了契约与台账两类文件**：
   合并日志明写「台账按模块 ID 求并集，折叠了 465 行同 ID 记录」，
   `packages/api-contracts/src/{identity,reporting}.openapi.json` 与
   `docs/product/capability-blueprint/module-progress.md` 被**自动并集**（无需人工）。
   ⇒ 冲突只剩上表 7 个**代码/测试**文件。

5. **`git merge --abort` 本轮成功（退出码 0），P0「abort 失败 / `MERGE_HEAD` 残留」未复发。**
   见 `docs/backlog/2026-10-22-merge-abort-fails-on-merge-union-ledger-file.md`：
   第 25 轮在 **merge-union 改写台账之后** abort 会退出 128 并残留 `MERGE_HEAD`。
   **本轮的前置条件同样命中**（merge-union 确实折叠了台账），但 abort 干净退出，
   且事后 `git rev-parse -q --verify MERGE_HEAD` ⇒ **无残留**、`git status --porcelain` ⇒ **空**。
   ⇒ 该 P0 仍是**偶发**而非必现，但排查口径不变（abort 后必须查 `MERGE_HEAD`）。

6. **闸：本轮走的是协议写明的「TTL 接管」分支，不是 keepalive 假重置。**
   判据与取证：

   | 证据 | 实测 |
   | --- | --- |
   | 闸龄轨迹 | `2801 → 2890 → 2990 → 3118 → 3246 → 3371 → 3491 → 3553 → 3613 → 3673 → 3733 → 3792 → 3852 → 3911 → 3974 → 4035 → 4095 → 4163` —— **单调递增，无重置** |
   | 是否有 keepalive 守护 | `pgrep -fl keepalive` ⇒ **0 个存活**（`.dev-agent/` 下留有 11 个历史 keepalive 脚本与 STOP 标记，全是**旧批次**的） |
   | 闸目录 mtime | 无 keepalive 定时新建文件 ⇒ 判龄不会被"刷新" |
   | owner PID | `2558` ⇒ `ps -p 2558` **查无此进程**（协议已说明：owner 只是短命写入进程，不是持闸者） |
   | 机器上有没有在跑构建 | `pgrep` 查 `mvnw/surefire/java/vitest` ⇒ **0**（等闸期间**只做只读取证**，没跑任何构建） |

   ⇒ 与第 76 / 77 / 78 轮的「keepalive 让死闸永不陈旧」**不同源**：本轮闸龄是**自然增长**到 TTL 的
   ⇒ 协议写明的 `held + stale=true ⇒ 可以 acquire 接管` 正常生效。
   **本轮没有清闸、没有 force、没有停任何守护**（无守护可停），全程只用脚本自带 API。

7. **等闸期间严格只读**：`gate status` / `cat owner` / `ps` / `git log` / `git rev-list` /
   `git cat-file -e`（对 base/ours/theirs 三侧做文件存在性比对）。
   **连 `git fetch` 都是拿到闸之后才跑的**（协议：等闸期间连 `git fetch` 都先别跑）。

8. **本轮不 push。** `git rev-list --count origin/integration/module-dev..integration/module-dev` = **0**
   ⇒ 本地集成头**与远端完全一致**，没有「第 78 轮那样要连带推 23 个 L2 红成果」的问题。
   但本轮**同步本身失败**（没有 merge commit、没有预检绿）⇒ 按协议本轮**不 push**。
   本篇文档**本地提交、不推**（与第 76 / 77 / 78 轮同一处置），留给下一轮流水线连同它自己的批次一起推。

9. **本篇文档必须 commit（不是留 untracked）**：batch-101 记录的
   「崩在 commit 前 ⇒ 未跟踪文件让 `lane.mjs merge-ready` 的「工作区干净」检查不通过 ⇒ 并入通道堵死」
   **已连续复发两次**。本篇若只落盘不提交就会**亲手制造第三次** ⇒ 提交说明与台账在同一个 commit。

10. **台账无变化**：`docs/product/capability-blueprint/module-progress.md` 当前为
    **已交付 408 · 进行中 0 · 未开发 57**（465 个 L4 模块，完成率 87.7%）。
    本轮**没跑 `sync.mjs`**（同步未发生，台账不该因一次失败的合并而变）。

11. **收尾已 `gate release`**，`gate status` = `free`、闸目录已不存在 ⇒ 下一轮流水线 tick 可正常取闸。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` —— 本任务协议（本轮按它执行）
- `tools/module-progress/lane.mjs` —— 闸实现（`GATE_TTL_SECONDS = 4200`，`acquire` 在 `age > TTL` 时走接管分支）
- `tools/module-progress/PIPELINE.md` —— 流水线协议（与 SYNC-MAIN.md 冲突时以它为准）
- `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md` —— 「格式债制造假冲突」历史模式（**本轮不适用**，见结论 3）
- `docs/backlog/2026-10-22-merge-abort-fails-on-merge-union-ledger-file.md` —— P0：merge-union 改写台账后 abort 失败 / `MERGE_HEAD` 残留（**本轮未复发**，见结论 5）
- `docs/backlog/2026-10-23-gate-stale-false-positive-live-tick-heartbeat-gap.md` —— 闸龄判据的历史盲区
- `docs/sessions/2026-10/2026-10-26-1232-main-sync-round78-no-new-commits.md` —— 上一轮同步（NO_UPDATE，keepalive 假重置接管）
- `docs/sessions/2026-10/2026-10-26-1022-main-sync-round77-no-new-commits.md` —— 第 77 轮同步（NO_UPDATE，靠真实交接窗口取闸）
- 本篇：`docs/sessions/2026-10/2026-10-26-1741-main-sync-round79-conflict-not-merged.md`

## 关键 trade-off / 讨论

**为什么 `823ebca5` 明明只有 1 个提交却冲突这么大？**
因为它是 **squash merge**：把集成分支整条历史压成 1 个提交挂到 main 上，
父提交是 main 的旧 tip ⇒ **它和集成分支是平行同源的两个快照，不存在线性祖先关系**。
Git 的三方合并（base=`6522c497`）因此要把「集成分支 PR 时的内容」与「集成分支现在的内容」对齐，
凡是 PR 之后集成分支继续改过的文件，两侧都是「各自新增」⇒ `add/add` 冲突。

**为什么不自动取 ours？**
协议明写同步任务**不自解冲突**；而且本轮冲突经取证是**语义型**（ours 含 12.2.1.1 收款单等新能力），
不是第 45 轮那种「两边只有排版差」的机械情形 ⇒ 机械取 ours 有把 main 侧独有改动悄悄吞掉的风险。
⇒ 留给流水线/人工裁决。

**为什么不 push 这篇文档？**
同步本轮失败 ⇒ 没有 merge commit 可推；且 push 属于「同步成功后」的第 5 步。
第 78 轮拒绝 push 的理由（怕连带推 L2 红成果）本轮**不成立**（本地领先 0），
但**授权边界**照样不成立 ⇒ 本篇只本地提交。

**为什么本轮不跑 `sync.mjs`？**
台账只在「同步确实发生」后刷新才有意义；一次被 abort 的合并不该改动台账基线。

## 后续待办

- [ ] **下一轮同步需要先裁决这 7 个冲突**，且**不要沿用第 45 轮的「过一遍 Prettier 再比」口径**
      （本轮已取证是语义型）。可行的裁决输入：ours 一侧普遍是 theirs 的超集，
      但 `api-proxy.ts` 是**同一区域两侧都改**的 content 冲突 ⇒ 需要逐个看 hunk。
- [ ] **main 的 tip 现在是 squash 形态** ⇒ 后续每轮「main 前进」都可能再撞同一类 add/add 冲突。
      建议流水线在把集成分支 PR 进 main 时，**明确 squash vs merge 的策略**，
      并让同步任务知道该按哪种形态取冲突证据。
- [ ] **P0「merge-union 改写台账后 abort 失败」本轮未复发但前置条件命中**（merge-union 确实折叠了台账）
      ⇒ 仍需保留「abort 后必查 `MERGE_HEAD`」这条纪律，并考虑把 abort 兜底（`git reset --hard HEAD`）写进协议。
- [ ] `lane.mjs` 的闸龄判据仍应从「闸目录 mtime」改成「最新 keepalive 文件的 mtime」，
      且 `gate release` 应只删自己持有的闸（现为无条件 `rmSync(GATE_DIR)`，并发下会互清）。
- [ ] 本地集成头将比 `origin/integration/module-dev` 领先 **1 个提交**（本篇文档，纯 docs）。
      下一轮流水线 push 时会连带推出。

## 关联

- ADR-0002 多租户策略 / ADR-0004 Java 微服务 —— 与本轮无关，列出仅为完整性
- `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md` —— 历史冲突模式对照
- `docs/backlog/2026-10-22-merge-abort-fails-on-merge-union-ledger-file.md` —— 本轮未复发的 P0
- `docs/sessions/2026-10/2026-10-26-1232-main-sync-round78-no-new-commits.md` —— 上一轮同步
- `docs/sessions/2026-10/2026-10-26-1022-main-sync-round77-no-new-commits.md` —— 第 77 轮同步
