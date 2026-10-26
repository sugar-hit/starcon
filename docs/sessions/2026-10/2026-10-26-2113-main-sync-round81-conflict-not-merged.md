# 2026-10-26: 集成头同步 main 第 81 轮 —— 冲突未并入（9 个冲突，与第 80 轮同形；本轮并发取证）

## 摘要

本轮**结果是冲突未并入**：取到闸 → `git fetch origin --prune` → `merge-base --is-ancestor` ⇒ `HAS_UPDATE`
⇒ `git merge --no-ff origin/main` **报 9 个冲突** ⇒ 按协议 `git merge --abort`（退出码 0，无 `MERGE_HEAD` 残留）
⇒ **没有预检、没有 push、没有产生 merge commit**，并入的 main 提交数 = **0**。

冲突面与第 80 轮**完全同形（9 个）**：7 个沿用 + 2 个 identity 通知声明。
main 的 tip 自第 79 轮起仍是 `823ebca5`（squash 形态），一动没动；集成头继续前进。

**本轮的主要成本同样在闸，而且本轮额外撞上了「同一个 worktree 里的并发同步 agent」**：
起手 `gate status` = `held / ageSeconds 710 / stale=false`，持闸者是流水线的 **L2 第 105 轮**
（同一 worktree 内跑 `mvnw verify`）。本轮密集竞态 `gate acquire` **等闸约 66 分钟**才取到闸，
期间先后撞上两次系统级与 agent 级的意外（见结论 3、4）。

## 决策 / 结论

1. **`origin/main` 自上轮以来没有前进，仍停在 `823ebca5`（squash 形态）。**

   | 项 | 值 |
   | --- | --- |
   | `origin/main` tip | `823ebca5`「Integration/module dev (#66)」 |
   | 父 | **仅 1 个**：`6522c497`（= 与集成头的 merge-base） |
   | 待并入提交数 | **1** |
   | 集成头 tip（合并时） | `aa9cf056`「docs(round-106): 21:00 轮 …」 |
   | 集成头相对 merge-base 的提交数 | **1049** |
   | 与集成头的树差异（2-dot） | **87 文件 / +6702 / −33439** |
   | 3-dot diff 规模 | 1032 文件 / +510134 / −45765 |

   ⇒ main 自第 79 轮起**一动没动**；变的只是集成头继续往前走。

2. **main 侧内容是集成头的严格「更旧子集」—— 合并在内容上本应是 no-op。**

   本轮独立复核（方向：`git diff integration/module-dev origin/main`）：
   - 只存在于 main 的文件（`A`）= **0**
   - 只存在于集成头的文件（`D`）= **72**

   即 **main 没有任何一个文件是集成头没有的**，main 缺的只是集成头后来新增的模块文件。
   ⇒ **9 个冲突全部是「squash 压合 vs 线性历史」的历史形状产物**，
   不是「main 带进来新代码、和集成头真冲突」。

3. **本轮撞上「系统睡眠冻结 keepalive 计时器」，差点导致一次不安全的 TTL 接管。**

   | 证据 | 实测 |
   | --- | --- |
   | 闸龄轨迹 | 起手 `ageSeconds=710`（持闸者活着）⇒ 一路等；**但 19:47→20:03 出现 28 分钟的「闸龄倒退」**（1196 → 1126） |
   | 根因 | `pmset -g log` ⇒ Mac 进入 **Maintenance Sleep 993 秒 + 990 秒**；macOS 的 `CLOCK_MONOTONIC` 在系统睡眠期间**暂停** ⇒ `keepalive-r105.sh` 里的 `sleep 1200` **计时被冻结**，闸目录 mtime 停在 19:23:17 |
   | 危险点 | 若闸目录 mtime 继续冻结，`ageSeconds` 会在 20:33 越过 TTL(4200) ⇒ `gate acquire` 会走**接管分支**把闸抢走 ⇒ **而持闸 tick 此刻可能还活着**（流水线还在跑 L2）⇒ 双跑 → 假红 + 互斥被破坏 |
   | 处置 | 本轮**改用带活性判据的竞态脚本**（`/tmp/starcon-gate-race.mjs`）：只有「闸空闲」或「闸已超 TTL **且** worktree 内无任何活构建进程」才接管；观察到 `sleep 1200` 在 20:17:30 恢复刷新 ⇒ TTL 重新推满 ⇒ **未发生任何越线接管** |

   ⇒ 协议写明的「必须走 `gate status` 的 `stale` 字段判据」在**系统睡眠**这个边界上不够用：
   `stale` 只看「闸目录 mtime 的年龄」，而 mtime 可能被系统睡眠**冻结**而不是被持闸者刷新。
   建议把活性判据补进协议（见「后续待办」第 3 条）。

4. **本轮撞上了「同一 worktree 里的并发同步 agent」—— 同一次 merge 被做了两遍。**

   本轮 21:03:5x 执行 merge 时，**git 拒绝执行**并报
   `error: Merging is not possible because you have unmerged files.`
   ⇒ 说明**同一 worktree 里已经有另一个 merge 在进行中**。
   事后取证：

   | 证据 | 实测 |
   | --- | --- |
   | reflog | `d70ce0e8 HEAD@{21:03:55}: reset: moving to HEAD` ⇒ **另一个 agent 在 21:03:55 abort 了它自己的 merge** |
   | 分支 reflog | `integration/module-dev@{0}: commit: docs(sync): 第 80 轮 main→集成头同步 —— 冲突未并入（9 个：…）` ⇒ 提交 `e6cb8f1e`（21:05:29） |
   | 会话日志 | `docs/sessions/2026-10/2026-10-26-2102-main-sync-round80-conflict-not-merged.md` ⇒ **第 80 轮同步 agent**在本轮之前 6 分钟刚跑完同一个任务 |
   | 冲突集一致 | 第 80 轮记录的 9 个冲突文件与本轮**逐个一致** ⇒ 同一 `823ebca5` + 同一集成头 ⇒ 同一结果 |

   ⇒ 本轮**不是第 80 轮的重复劳动**，而是**独立复核**：本轮在 `aa9cf056`（比第 80 轮多两个 docs commit）上
   **自己跑了一遍 merge**，独立得到同样的 9 个冲突 ⇒ 结论可信。

   ⚠️ 但「两个同步 agent 共用同一个 worktree 且不互斥」是真实缺陷：
   同步任务**没有闸的互斥保护**（协议只要求它与流水线互斥），
   两轮同步并发时会出现「A 的 merge 被 B 的 abort 掉」这种互相破坏。
   本轮之所以受害，是因为 21:02:59 我取闸时**第 80 轮 agent 正在跑它自己的 merge**。

5. **9 个冲突构成：8 个 add/add + 1 个 content（与第 80 轮逐个一致）。**

   | 冲突文件 | 类型 | ours 行 | theirs 行 | theirs 独有行 | ours 独有行 | 性质 |
   | --- | --- | --- | --- | --- | --- | --- |
   | `apps/web/src/components/dashboard/workbench-card-registry.ts` | add/add | 207 | 184 | 1 | 24 | 语义（集成头更新） |
   | `apps/web/src/components/dashboard/workbench-layout-model.test.ts` | add/add | 293 | 286 | 1 | 8 | 语义（集成头更新） |
   | `apps/web/src/lib/api-proxy.ts` | **content** | 12668 | 12470 | **0** | 198 | 语义（集成头**严格超集**） |
   | `backend/services/finance/.../FundAnalysisIntegrationTest.java` | add/add | 932 | 925 | 2 | 9 | 语义（集成头更新） |
   | `backend/services/identity/.../NotificationInboxController.java` | add/add | 166 | 166 | 3 | 3 | **双向各改（1.3.x 交付声明）** |
   | `backend/services/identity/.../NotificationPreferenceService.java` | add/add | 425 | 425 | 2 | 2 | **双向各改（1.3.2.x 交付声明）** |
   | `backend/services/reporting/.../ReportingFinancePort.java` | add/add | 241 | 192 | **0** | 49 | 语义（集成头**严格超集**：收款单 12.2.1.1） |
   | `backend/services/trade/.../AnomalyOrderIntegrationTest.java` | add/add | 737 | 610 | 35 | 162 | 语义（集成头更新） |
   | `backend/services/trade/.../AnomalyOrderTenancyIntegrationTest.java` | add/add | 365 | 345 | 11 | 31 | 语义（集成头更新） |

   ⇒ `api-proxy.ts` 与 `ReportingFinancePort.java` 的 **theirs 独有行 = 0** ⇒ 集成头侧是**严格超集**。
   ⇒ **两个双向冲突的差异方向也已逐行确认**（集成头侧正确、main 侧是旧快照）：

   ```
   NotificationInboxController.java
   < (theirs/main)  "1.3.2.1 预警规则未交付"
   > (ours/集成头)   "1.3.2.1 预警规则已交付（命中产出归它，本接口只消费已入箱的站内通知）"
   ```

   ⇒ 这不是「两份都对、随便挑一份」的对等冲突，而是**新旧快照差**：
   main 说未交付，是因为压合那一刻它们确实还没交付；集成头说已交付，是因为它们**现在**确实已交付。

6. **`merge-union` 驱动器仍只兜底契约与台账两类文件**，行为与第 79 / 80 轮一致：
   - `docs/product/capability-blueprint/module-progress.md` 按模块 ID 求并集，折叠了 465 行同 ID 记录；
   - `packages/api-contracts/src/contracts/{identity,reporting}.ts` 与 `{identity,reporting}.openapi.json` 自动并集；
   - ⇒ 剩下 9 个全是**代码 / 测试**文件，需要人工语义裁决，**协议不允许本任务自行解**。

7. **`git merge --abort` 干净退出，P0「abort 失败 / `MERGE_HEAD` 残留」未复发。**
   事后核查：`git rev-parse -q --verify MERGE_HEAD` ⇒ **无残留**；
   `git status --porcelain` ⇒ **空**；`git diff --diff-filter=U` ⇒ **0**；
   `HEAD` 回到 `aa9cf056`。

8. **本轮不跑预检、不 push。**
   冲突 ⇒ 没有 merge commit ⇒ 没有预检目标 ⇒ 按协议不跑 `./mvnw compile` / `pnpm typecheck`，
   更不 push 集成头（推一个没合成的集成头没有意义）。
   本篇文档**本地 commit、不推**（与第 79 / 80 轮同一处置），留给下一轮流水线连同它自己的批次一起推。

9. **台账无变化（未同步 ⇒ 不刷新）。**
   `node tools/module-progress/sync.mjs` ⇒ 「✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 410 · 进行中 0 · 未开发 55」，
   **零文件改动**（`git status --porcelain` 同步前后均为空）。
   相对第 79 轮的 408 / 0 / 57 多 2 个已交付模块，那是流水线 batch-103 / batch-104 带来的，**与本任务无关**。

10. **闸：本轮走的是「正常交接」路径 —— 竞态 `acquire` 在持闸 tick 退出后取到，未清闸、未 force。**

    | 证据 | 实测 |
    | --- | --- |
    | 持闸者 | 流水线 **L2 第 105 轮**（同一 worktree 内 `mvnw verify`；channel 2344 例全量复跑 BUILD SUCCESS） |
    | 起手判据 | `gate status` = `held / ageSeconds 710 / stale=false` ⇒ 活闸，不能接管 |
    | 活性取证（闸外） | `ps` 抓到 `classworlds … verify` / `surefirebooter` 等真实构建进程 ⇒ 持闸者活着 |
    | 等闸时长 | 约 **66 分钟**（21:02:59 → 21:12:38），期间只做只读取证（`gate status` / `cat owner` / `ps` / `git log`），**连 `git fetch` 都是取闸后才跑** |
    | 交接 | 21:12:38 竞态 `acquire` 命中（free path）⇒ owner = 本轮 pid |
    | 闸龄 | 全程被 keepalive 压住（`stale` 始终 false），**未走 TTL 接管**、**未清闸**、**未 force** |

## 后续待办

1. **main 侧需要一次「用集成头快照刷新 main」的正常合并**，而不是让同步任务硬解 9 个冲突。
   根因是 PR #66 用了 **squash merge**：压合掉了集成分支的祖先链，
   于是每一次「main 落后于集成头」的同步都会在「同源快照」上重新长出冲突。
   ⇒ 若后续 PR 改用 **merge commit**（保留祖先链），本类冲突应当自然消失。
2. **第 79 / 80 / 81 轮遗留问题依然成立**：`823ebca5` 自 2026-10-26 17:41 起就不再前进，
   而集成头已前进到 `aa9cf056`。⇒ 同步通道已连续三轮无法把 main 的任何内容并入集成头
   （虽然经取证，main 的内容本来就是集成头的子集，**实质上没有新东西需要并入**）。
3. **`gate status` 的 `stale` 判据应补一条活性旁证**（本轮实测踩到）：
   系统睡眠会**冻结**持闸者的 `sleep` 计时器 ⇒ 闸目录 mtime 停止刷新 ⇒ `ageSeconds` 虚高 ⇒
   `stale` 可能为 **true 而持闸 tick 仍然活着**。
   ⇒ 建议 `lane.mjs gate status` 在 `stale=true` 时额外输出「最近 keepalive 文件年龄」，
   并把「TTL 接管」的条件收紧为「超 TTL **且** 无活构建进程」（本轮临时脚本即按此判定）。
4. **同步任务之间需要互斥**：本轮与第 80 轮同步 agent 在同一 worktree 并发，
   出现「A 的 merge 被 B 的 abort 掉」。⇒ 同步任务取闸后应写入**自己的 owner 标识**，
   并在动 worktree 前校验「当前闸 owner 是自己」。
5. `lane.mjs` 的 `gate release` 仍是无条件 `rmSync(GATE_DIR)`，并发下会互清；
   `gate status` 的年龄仍取自「闸目录 mtime」而非「最新 keepalive 文件 mtime」。
   （第 79 轮已登记，本轮再次确认。）
6. 本任务**不动冲突**，下一轮再试；若 main 届时仍停在 `823ebca5`，
   预期仍是同样的 9 个冲突（集成头再前进只会让新增声明类冲突变多，不会变少）。

## 文件清单

| 文件 | 动作 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-26-2113-main-sync-round81-conflict-not-merged.md` | 新增（本文件） |

> 本轮**未改任何业务代码**：只搬运 commit（结果为 0 个），未产生 merge commit、未 push 集成头、未 push main。
