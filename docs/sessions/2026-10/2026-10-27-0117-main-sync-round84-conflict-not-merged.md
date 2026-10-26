# 2026-10-27: 集成头同步 main 第 84 轮 —— 冲突未并入（10 个，与第 83 轮逐字节相同）

## 摘要

本轮**结果是冲突未并入**：取到闸 → `git fetch origin --prune` → `merge-base --is-ancestor` ⇒ `HAS_UPDATE`
（待并入 **1** 个提交）⇒ `git merge --no-ff origin/main` **报 10 个冲突** ⇒ 按协议 `git merge --abort`
（退出码 0，无 `MERGE_HEAD` 残留，unmerged 路径 0）⇒ **没有预检目标、没有 push、没有产生 merge commit**，
**并入的 main 提交数 = 0**。

本轮与第 83 轮（01:14，仅隔 3 分钟）的差别只有两点：**闸是等出来的而不是抢来的**（第 83 轮是停掉孤儿 keepalive 后接管，
本轮是纯竞态命中），以及**本轮独立复跑了 `pnpm typecheck`**，把第 83 轮定位的流水线 L2 红根因确认仍然存在。

## 决策 / 结论

1. **`origin/main` 仍停在 `823ebca5`（squash 形态），自第 79 轮起不再前进。**

   | 项                        | 值                                                                                |
   | ------------------------- | --------------------------------------------------------------------------------- |
   | `origin/main` tip         | `823ebca5`「Integration/module dev (#66)」                                        |
   | 父                        | **仅 1 个**：`6522c497`（= 与集成头的 merge-base）⇒ **squash，不是 merge commit** |
   | 待并入提交数              | **1**                                                                             |
   | 集成头 tip（合并时）      | `6aaefb48`（= 第 83 轮日志提交）                                                  |
   | 与集成头的树差异（3-dot） | **1032 文件 / +510134 / −45765**                                                  |
   | 冲突文件数                | **10**（9 个 add/add + 1 个 content）                                             |
   | 并入的 main 提交数        | **0**（冲突中止）                                                                 |

2. **冲突面与第 83 轮逐字节相同 ⇒ 第 83 轮 todo 7 的预判再次兑现。**

   第 83 轮预判「若 main 届时仍停在 `823ebca5`，预期仍是同样的 10 个冲突（集成头再前进只会让冲突变多，不会变少）」。
   本轮集成头只前进了 1 个纯文档提交（`6aaefb48` 第 83 轮日志），**不触及任何冲突文件** ⇒ 冲突集完全复现。
   逐文件行数差与第 83 轮的表**逐格相同**：

   | 冲突文件                                                             | 类型        | ours 行 | theirs 行 | ours 独有行 | theirs 独有行 | 性质                         |
   | -------------------------------------------------------------------- | ----------- | ------- | --------- | ----------- | ------------- | ---------------------------- |
   | `apps/web/src/components/dashboard/workbench-card-registry.ts`       | add/add     | 207     | 184       | 24          | 1             | 旧快照                       |
   | `apps/web/src/components/dashboard/workbench-layout-model.test.ts`   | add/add     | 293     | 286       | 8           | 1             | 旧快照                       |
   | `apps/web/src/lib/api-proxy.ts`                                      | **content** | 12851   | 12470     | 381         | **0**         | 集成头**严格超集**           |
   | `backend/services/finance/.../FundAnalysisIntegrationTest.java`      | add/add     | 932     | 925       | 9           | 2             | 旧快照                       |
   | `backend/services/identity/.../NotificationInboxController.java`     | add/add     | 166     | 166       | 3           | 3             | 双向各改（1.3.x 交付声明）   |
   | `backend/services/identity/.../NotificationPreferenceService.java`   | add/add     | 425     | 425       | 2           | 2             | 双向各改（1.3.2.x 交付声明） |
   | `backend/services/reporting/.../ReportingFinancePort.java`           | add/add     | 241     | 192       | 49          | **0**         | 集成头**严格超集**           |
   | `backend/services/reporting/.../ReportingUpstreamClient.java`        | add/add     | 156     | 112       | 44          | **0**         | 集成头**严格超集**           |
   | `backend/services/trade/.../AnomalyOrderIntegrationTest.java`        | add/add     | 737     | 610       | 162         | 35            | 旧快照                       |
   | `backend/services/trade/.../AnomalyOrderTenancyIntegrationTest.java` | add/add     | 365     | 345       | 31          | 11            | 旧快照                       |

   ⇒ 3 个文件（`api-proxy` / `ReportingFinancePort` / `ReportingUpstreamClient`）**theirs 独有行 = 0**
   ⇒ main 侧内容是集成头的**严格更旧子集**，合并方向在这几条上**是反的**。

3. **`merge-union` 驱动器按预期只兜底契约与台账两类文件**（与第 79–82 轮一致）：

   - `docs/product/capability-blueprint/module-progress.md` ⇒ 按模块 ID 求并集，折叠 465 行同 ID 记录；
   - `packages/api-contracts/src/{channel,identity,reporting}.ts` + 对应 `.openapi.json` ⇒ 自动并集；
   - 驱动还打印了一行 **`merge-union: 走了行级兜底并集，请人工确认这个文件改对了`**
     ⇒ 说明有某个 JSON 走了「行级兜底」而非「按键求并」，该文件需人工确认（本轮已 abort，未落入提交，风险为零）。

   ⇒ 剩下 10 个全是**代码 / 测试**文件，需要人工语义裁决，**协议不允许本任务自行解**。

4. **闸：本轮是纯竞态命中，没有停任何守护、没有清闸、没有 force。**

   | 证据               | 实测                                                                     |
   | ------------------ | ------------------------------------------------------------------------ |
   | 起始 `gate status` | `gate=held` / `ageSeconds=158` / `ttlSeconds=4200` / **`stale=false`**   |
   | 持闸 owner         | pid `15984`，取闸时刻 `2026-10-26T17:12:30.438Z`（= 第 83 轮取闸时刻）   |
   | 持闸者身份         | **第 83 轮同步任务自己**（它 01:15:32 提交会话日志后释放了闸）           |
   | 闸外构建进程       | `ps` 全仓**零** maven / java / surefire / vitest / next ⇒ 无并发构建风险 |
   | 处置               | 密集 `gate acquire` 竞态（1 秒 1 次），**第 22 次命中**（等闸约 22 秒）  |

   ⇒ 与第 83 轮的「孤儿 keepalive 死锁 ⇒ 停守护后接管」路径**不同**：本轮闸被正常释放，
   竞态直接命中 ⇒ **不需要 `STOP-KEEPALIVE`、不需要 `kill`、不需要 `gate release` 强取**。
   ⇒ 全程**未清闸、未 force、未 rm -rf 闸目录**；收尾已 `gate release`。

5. **🔴 流水线 L2 红的根因仍然存在（本轮亲自复跑 `pnpm typecheck` 复现，逐字相同）。**

   第 83 轮定位：`车道提交 8badb7435` 在 `api-proxy.ts` 的 `services[]` 里加 `prefix: 'channel/'` 条目时，
   写完 `pattern:` 正则**漏了 `},` 闭合对象**；`c107dcbe9` 紧接着追加自己的 `prefix: 'reporting/'` 条目
   ⇒ 两条目塌进同一个对象字面量 ⇒ `prefix` / `env` / `fallback` / `pattern` 各出现两次 ⇒ TS1117 ×4。

   本轮在持闸、无并发构建的前提下复跑 `pnpm typecheck`，四条错误**逐字相同**：

   ```
   src/lib/api-proxy.ts(12743,5): error TS1117: An object literal cannot have multiple properties with the same name.
   src/lib/api-proxy.ts(12744,5): error TS1117: ...
   src/lib/api-proxy.ts(12745,5): error TS1117: ...
   src/lib/api-proxy.ts(12746,5): error TS1117: ...
   ```

   ⇒ **Tasks: 2 successful, 3 total · Failed: @starcon/web#typecheck**（退出码 1）
   ⇒ 直接读 `apps/web/src/lib/api-proxy.ts:12705-12712` 复核：`pattern:` 正则行之后**确实没有 `},`**，
   下一条车道注释直接跟在后面 ⇒ 缺陷**在 HEAD 上仍未修复**。
   ⇒ **本任务不动业务代码**（协议硬边界），故只取证上报，由流水线车道处理。

6. **崩溃 tick 的未提交产物被逐字节保全（与第 83 轮同一手法）。**

   round-108 流水线崩在传输层，留下 4 个未提交文件，本轮开工时仍在：
   `docs/backlog/2026-10-2026-10-26-analytics-section-no-navigation-entry.md`（M）、
   `docs/product/capability-blueprint/module-progress.md`（M）、
   `docs/sessions/2026-10/2026-10-26-2100-pipeline-round106-gate-handover-and-dispatch.md`（M）、
   `docs/sessions/2026-10/2026-10-26-batch-105-four-lane-merge-and-l2.md`（未跟踪）。

   - 先取 MD5 基线：`30037680…` / `ed174fd7…` / `76d6a03d…` / `17cac92e…`
     （**与第 83 轮记录的 4 个 MD5 完全一致** ⇒ 这 4 个文件在这 3 分钟内没有被任何人改动）；
   - `git stash push -u` → 合并 → `git merge --abort` → `git stash pop`；
   - pop 后复取 MD5：**4 个逐一相同**，`git stash list` 已空 ⇒ 逐字节复原，未丢未改。

   ⇒ 注意 `module-progress.md` **在 main 侧也是 CHANGED** ⇒ 带着它直接 merge 会被 git 拒绝启动
   （`Your local changes ... would be overwritten`），先 stash 是必要步骤，不是多余动作。

7. **台账：无变化（未同步 ⇒ 不改变基线）。**

   `node tools/module-progress/sync.mjs` ⇒ 「✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 **414** · 进行中 **0** · 未开发 **51**」，
   **零文件改动**（该文件本就是崩溃 tick 未提交的 `sync.mjs` 产物，跑写模式后 MD5 不变 ⇒ 幂等）。
   ⇒ 与第 83 轮完全相同（414 / 0 / 51）⇒ 本轮并入 main 的 0 个提交**没有改变台账基线**。

8. **本轮不 push。**

   - 冲突 ⇒ 没有 merge commit ⇒ 没有属于本轮的东西可推（与第 81/82/83 轮同一处置）；
   - 集成头当前比 `origin/integration/module-dev` **领先 16 个提交**，这些是流水线自己的车道成果；
   - 而流水线 round-107 的 L2 是**红**的，且本轮已复跑确认**红因仍在**（结论 5）
     ⇒ **推一个已知红的集成头是净负收益**；
   - ⇒ 本篇文档**本地 commit、不推**，留给下一轮流水线连同它自己的批次一起推。

9. **`git merge --abort` 干净退出**，事后核查：`git rev-parse -q --verify MERGE_HEAD` ⇒ 无残留；
   `git diff --diff-filter=U` ⇒ 0；`HEAD` 回到 `6aaefb48`（= 合并前 `PRE`，逐字节相同）。

## 后续待办

1. **main 侧需要一次「用集成头快照刷新 main」的正常合并（保留祖先链）**，而不是让同步任务硬解 10 个冲突。
   根因仍是 PR #66 用了 **squash merge**（压掉集成分支的祖先链 ⇒ main 是集成头的严格更旧子集）。
   ⇒ 若后续 PR 改用 **merge commit**，本类冲突应当自然消失。
   ⇒ 合并方向应当是「集成头 → main」，不是「main → 集成头」。
2. **流水线需修 `api-proxy.ts:12709` 之后缺失的 `},`**（结论 5）⇒ 这是 round-107 L2 红的根因，
   本轮已复跑确认仍未修；修完 typecheck / Next build 才能回绿。**不属本同步任务职责**。
3. **`gate status` 的 `stale` 判据应补活性旁证**（第 81 轮提出、第 83/84 轮延续）：
   第 83 轮实测守护进程每 240 秒刷新闸目录 mtime ⇒ `stale` 恒 `false`。
   ⇒ 建议 `lane.mjs gate status` 在 `stale=true` 时额外输出「最新 keepalive 文件年龄」，
   并把「TTL 接管」收紧为「超 TTL **且** 无活构建进程 **且** 无 keepalive 守护在跑」。
4. **keepalive 守护应随 tick 一起结束**（P0 登记表待办，仍未修）：
   守护应带「持有者 PID 存活检测」，持有者一死就自杀；否则每个崩掉的 tick 都会留一个孤儿守护，
   把闸永久顶成「假 held」。
5. **同步任务之间需要互斥**（第 81 轮提出，仍未修）：
   取闸后应写入自己的 owner 标识，动 worktree 前校验「当前闸 owner 是自己」。
   ⇒ 本轮观察到**第 83 轮同步任务在 01:15:32 提交后、01:15:37 被本轮竞态接管**，交接窗口窄到 5 秒。
6. `lane.mjs` 的 `gate release` 仍是无条件 `rmSync(GATE_DIR)`，并发下会互清；
   `gate status` 的年龄仍取自「闸目录 mtime」而非「最新 keepalive 文件 mtime」。
7. 本任务**不动冲突**，下一轮再试；若 main 届时仍停在 `823ebca5`，
   预期仍是同样的 10 个冲突（集成头再前进只会让冲突变多，不会变少）。

## 文件清单

| 文件                                                                             | 动作           |
| -------------------------------------------------------------------------------- | -------------- |
| `docs/sessions/2026-10/2026-10-27-0117-main-sync-round84-conflict-not-merged.md` | 新增（本文件） |

> 本轮**未改任何业务代码**：只搬运 commit（结果为 0 个），未产生 merge commit、未 push 集成头、未 push main。
> 本轮**未跑全量 `mvnw verify`**（发热高峰，属流水线 L2）；只在持闸且确认无并发构建时跑了
> `pnpm typecheck`（快速预检的 TS 侧）用于复现并确认流水线 L2 红因**仍未修复**。
> 本轮**未触碰**崩溃 tick 留下的 4 个未提交文件（已 stash/pop 逐字节复原）。
