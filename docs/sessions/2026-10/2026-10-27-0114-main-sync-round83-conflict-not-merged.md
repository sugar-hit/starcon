# 2026-10-27: 集成头同步 main 第 83 轮 —— 冲突未并入（10 个 = 第 82 轮 9 个 + 1 新增）

## 摘要

本轮**结果是冲突未并入**：取到闸 → `git fetch origin --prune` → `merge-base --is-ancestor` ⇒ `HAS_UPDATE`
（待并入 **1** 个提交）⇒ `git merge --no-ff origin/main` **报 10 个冲突** ⇒ 按协议 `git merge --abort`
（退出码 0，无 `MERGE_HEAD` 残留，unmerged 路径 0）⇒ **没有预检目标、没有 push、没有产生 merge commit**，
**并入的 main 提交数 = 0**。

本轮的主要成本在闸上：**从 22:29 等到 01:12（约 2 小时 43 分钟）**。
其中前段是**活着的流水线 tick 持闸**（round-107 的 L2 `mvnw verify` 真的在跑），
后段约 1 小时 40 分钟是**孤儿 keepalive 守护进程顶出来的「假 held」** ——
持闸 tick 已崩，但守护每 4 分钟刷新闸目录 mtime ⇒ `ageSeconds` 永远 < TTL ⇒ `stale` 永远 `false` ⇒ 纯等等不到。
最终按 P0 登记的「持闸者已死 ⇒ 停掉孤儿守护后接管」纪律接管（与第 76 轮同一套 `STOP-KEEPALIVE` 机制）。

本轮另有一项**超出本任务职责但对流水线有直接价值**的发现：
流水线 L2（round-107）红的**根因已定位到具体行与具体车道提交** —— 见结论 5。

## 决策 / 结论

1. **`origin/main` 仍停在 `823ebca5`（squash 形态），自第 79 轮起不再前进。**

   | 项 | 值 |
   | --- | --- |
   | `origin/main` tip | `823ebca5`「Integration/module dev (#66)」 |
   | 父 | **仅 1 个**：`6522c497`（= 与集成头的 merge-base）⇒ **squash，不是 merge commit** |
   | 待并入提交数 | **1** |
   | 集成头 tip（合并时） | `13131b0b`「merge(13.2.2.2): SKU/SPU/Listing」 |
   | 与集成头的树差异（3-dot） | **1032 文件 / +510134 / −45765** |
   | 冲突文件数 | **10**（9 个 add/add + 1 个 content） |
   | 并入的 main 提交数 | **0**（冲突中止） |

2. **冲突面 = 第 82 轮的 9 个 + 1 个新增，第 82 轮 todo 7 的预判成立。**

   新增的是 `backend/services/reporting/.../ReportingUpstreamClient.java`（add/add）。
   原因：第 82 轮之后集成头又并入了 4 条车道（1.5.2.1 / 13.1.2.1 / 13.2.1.1 / 13.2.2.2），
   reporting 服务多了一个 `ReportingUpstreamClient` 文件，而 main 侧是更旧快照 ⇒ 新长出一个 add/add。
   ⇒ **「集成头再前进只会让新增声明类冲突变多，不会变少」** —— 第 83 轮第 10 个冲突就是这条预判的第一次兑现。

   | 冲突文件 | 类型 | ours 行 | theirs 行 | ours 独有行 | theirs 独有行 | 性质 |
   | --- | --- | --- | --- | --- | --- | --- |
   | `apps/web/src/components/dashboard/workbench-card-registry.ts` | add/add | 207 | 184 | 24 | 1 | 旧快照 |
   | `apps/web/src/components/dashboard/workbench-layout-model.test.ts` | add/add | 293 | 286 | 8 | 1 | 旧快照 |
   | `apps/web/src/lib/api-proxy.ts` | **content** | 12851 | 12470 | 381 | **0** | 集成头**严格超集** |
   | `backend/services/finance/.../FundAnalysisIntegrationTest.java` | add/add | 932 | 925 | 9 | 2 | 旧快照 |
   | `backend/services/identity/.../NotificationInboxController.java` | add/add | 166 | 166 | 3 | 3 | 双向各改（1.3.x 交付声明） |
   | `backend/services/identity/.../NotificationPreferenceService.java` | add/add | 425 | 425 | 2 | 2 | 双向各改（1.3.2.x 交付声明） |
   | `backend/services/reporting/.../ReportingFinancePort.java` | add/add | 241 | 192 | 49 | **0** | 集成头**严格超集** |
   | `backend/services/reporting/.../ReportingUpstreamClient.java` | add/add | 156 | 112 | 44 | **0** | 集成头**严格超集** |
   | `backend/services/trade/.../AnomalyOrderIntegrationTest.java` | add/add | 737 | 610 | 162 | 35 | 旧快照 |
   | `backend/services/trade/.../AnomalyOrderTenancyIntegrationTest.java` | add/add | 365 | 345 | 31 | 11 | 旧快照 |

   ⇒ 3 个文件（api-proxy / ReportingFinancePort / ReportingUpstreamClient）**theirs 独有行 = 0**
   ⇒ main 侧内容是集成头的**严格更旧子集**，合并方向在这几条上**是反的**。

3. **`merge-union` 驱动器行为与第 79–82 轮一致**：只兜底契约与台账两类文件 ——
   `docs/product/capability-blueprint/module-progress.md` 按模块 ID 求并集（折叠 465 行同 ID 记录），
   `packages/api-contracts/src/{channel,identity,reporting}.ts` 与对应 `.openapi.json` 自动并集。
   ⇒ 剩下 10 个全是**代码 / 测试**文件，需要人工语义裁决，**协议不允许本任务自行解**。

4. **闸：本轮走的是「P0 孤儿 keepalive 死锁 ⇒ 停守护后接管」路径，不是等待竞态命中。**

   | 证据 | 实测 |
   | --- | --- |
   | 1. `dispatch.gate/owner` 的 PID | `96465` ⇒ `ps -p 96465` **查无此进程** |
   | 2. 持闸 tick 的会话状态 | `mvs_601ecba2…`（round-108 流水线）= **`error`**（`net::ERR_HTTP2_PING_FAILED`, 50001），最后活动 **00:28** |
   | 3. 机器上有没有在跑构建/测试 | `ps` 全仓**零** maven / java / surefire / vitest / next 进程 |
   | 4. 持闸 tick 自己的 L2 日志 | `.dev-agent/l2-round107.stdout` 已写完 5 步汇总（**L2 FAIL**，00:45 收口），`l2-round107.sh` 进程已退出 |
   | 5. 孤儿守护 | `keepalive-r108.sh` PID `96518`，父 `96515` **PPID=1**（孤儿），其命令原文写着 "holds the machine awake for the **orphaned** verify" ⇒ 守护自己都承认 verify 已成孤儿 |

   ⇒ 纯等**等不到**：守护每 240 秒 `touch` 一次闸目录里的新文件 ⇒ 目录 mtime 永远新鲜 ⇒
   `ageSeconds` 永远 < TTL(4200) ⇒ `stale` 永远 `false`（第 76 轮同一现象）。
   ⇒ 处置沿用 P0 登记表 `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` 的纪律：

   ```bash
   touch .dev-agent/STOP-KEEPALIVE-R108   # 守护自身的停止标记（与第 76 轮同款）
   kill 96518                             # 结束孤儿守护（SIGTERM，非 -9）
   node tools/module-progress/lane.mjs gate release     # 闸自带 API
   node tools/module-progress/lane.mjs gate acquire     # ⇒ {"ok": true, "gate": "acquired"}，退出码 0
   ```

   ⇒ 取闸时间戳 **2026-10-26T17:12:30.438Z**（= 01:12:30 CST），owner pid `15984`。
   ⇒ 全程**未清闸、未 force、未 rm -rf 闸目录**；收尾已 `gate release`。

5. **🔴 本轮附带定位：流水线 L2 红的根因（**不是** main 带进来的）。**

   流水线 round-107 的 L2 红了：`RC_MVN=0`（后端全绿）但 `RC_TYPECHECK=2`、`RC_PNPMTEST=1`、`RC_BUILD=1`。
   本轮在持有闸、无并发构建的前提下**亲自复跑 `pnpm typecheck`** 复现，四条错误逐字相同：

   ```
   src/lib/api-proxy.ts(12743,5): error TS1117: An object literal cannot have multiple properties with the same name.
   src/lib/api-proxy.ts(12744,5): error TS1117: ...
   src/lib/api-proxy.ts(12745,5): error TS1117: ...
   src/lib/api-proxy.ts(12746,5): error TS1117: ...
   ```

   **根因（`git blame` 逐行取证）**：

   - 车道提交 **`8badb7435`**（1.5.2.1 服务与任务状态，21:32）在 `api-proxy.ts` 的 `services[]` 里加了一条
     `prefix: 'channel/'` 条目，**写完 `pattern:` 正则后没有补 `},` 把对象闭合**；
   - 车道提交 **`c107dcbe9`**（13.2.2.2 SKU/SPU/Listing，21:44）紧接着在下一行追加了自己的
     `prefix: 'reporting/'` 条目 ⇒ **两条目塌进同一个对象字面量** ⇒ `prefix` / `env` / `fallback` / `pattern`
     各出现两次 ⇒ TS1117 ×4（报错行正是 12743–12746）。

   ⇒ **修法是补回 `8badb7435` 漏掉的 `},`（在 12709 行正则之后）**，与 main 无关、与本轮同步无关。
   ⇒ **本任务不动业务代码**（协议硬边界），故只取证上报，由流水线车道处理。
   ⇒ 这也印证了流水线自己在 round-106 日志里的判断：「若 L2 红，嫌疑对象是本批车道互相破坏，不是 main 带进来的」。

6. **崩溃 tick 的未提交产物被逐字节保全。**

   round-108 流水线崩在传输层（00:28），留下 4 个未提交文件：
   `docs/backlog/2026-10-2026-10-26-analytics-section-no-navigation-entry.md`（M）、
   `docs/product/capability-blueprint/module-progress.md`（M）、
   `docs/sessions/2026-10/2026-10-26-2100-pipeline-round106-gate-handover-and-dispatch.md`（M）、
   `docs/sessions/2026-10/2026-10-26-batch-105-four-lane-merge-and-l2.md`（未跟踪）。

   合并前 `git merge` 因 `module-progress.md` 有本地改动而**拒绝启动**（`Your local changes ... would be overwritten`，
   无副作用、无 `MERGE_HEAD` 残留）。
   ⇒ 本轮用 `git stash push -u` 暂存这 4 个文件 → 合并 → `git merge --abort` → `git stash pop`，
   **4 个文件 MD5 逐一比对全部一致**（`30037680…` / `ed174fd7…` / `76d6a03d…` / `17cac92e…`）⇒ 逐字节复原，未丢未改。

7. **台账：无变化（未同步 ⇒ 不改变基线）。**

   `node tools/module-progress/sync.mjs` ⇒ 「✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 **414** · 进行中 **0** · 未开发 **51**」，
   **零文件改动**（该文件本就是崩溃 tick 未提交的 `sync.mjs` 产物，跑写模式后 MD5 不变 ⇒ 幂等）。
   ⇒ 与第 82 轮的 410 / 0 / 55 相比 **+4 已交付 / −4 未开发**，
   对应第 82 轮之后落地的 4 条车道（1.5.2.1 / 13.1.2.1 / 13.2.1.1 / 13.2.2.2）—— **不是** main 带进来的。

8. **本轮不 push。**

   - 冲突 ⇒ 没有 merge commit ⇒ 没有属于本轮的东西可推（与第 81/82 轮同一处置）；
   - 且集成头当前比 `origin/integration/module-dev` **领先 15 个提交**，这些是流水线自己的车道成果，
     而流水线 round-107 的 L2 是**红**的 ⇒ **推不推是流水线的决定**，不是同步任务的决定；
   - ⇒ 本篇文档**本地 commit、不推**，留给下一轮流水线连同它自己的批次一起推。

9. **`git merge --abort` 干净退出**，事后核查：`git rev-parse -q --verify MERGE_HEAD` ⇒ 无残留；
   `git diff --diff-filter=U` ⇒ 0；`HEAD` 回到 `13131b0b`（= 合并前 `PRE`，逐字节相同）。

## 后续待办

1. **main 侧需要一次「用集成头快照刷新 main」的正常合并（保留祖先链）**，而不是让同步任务硬解 10 个冲突。
   根因仍是 PR #66 用了 **squash merge**（压掉集成分支的祖先链 ⇒ main 是集成头的严格更旧子集）。
   ⇒ 若后续 PR 改用 **merge commit**，本类冲突应当自然消失。
   ⇒ 合并方向应当是「集成头 → main」，不是「main → 集成头」。
2. **流水线需修 `api-proxy.ts:12709` 之后缺失的 `},`**（结论 5）⇒ 这是 round-107 L2 红的根因，
   修完 typecheck / Next build 才能回绿。**不属本同步任务职责**。
3. **`gate status` 的 `stale` 判据应补活性旁证**（第 81 轮提出、第 83 轮再次兑现）：
   守护进程每 240 秒刷新闸目录 mtime ⇒ `stale` 恒 `false`。
   ⇒ 建议 `lane.mjs gate status` 在 `stale=true` 时额外输出「最新 keepalive 文件年龄」，
   并把「TTL 接管」收紧为「超 TTL **且** 无活构建进程 **且** 无 keepalive 守护在跑」。
4. **keepalive 守护应随 tick 一起结束**（P0 登记表待办，仍未修）：
   守护应带「持有者 PID 存活检测」，持有者一死就自杀；否则每个崩掉的 tick 都会留一个孤儿守护，
   把闸永久顶成「假 held」。
5. **同步任务之间需要互斥**（第 81 轮提出，本轮未复发但仍未修）：
   取闸后应写入自己的 owner 标识，动 worktree 前校验「当前闸 owner 是自己」。
6. `lane.mjs` 的 `gate release` 仍是无条件 `rmSync(GATE_DIR)`，并发下会互清；
   `gate status` 的年龄仍取自「闸目录 mtime」而非「最新 keepalive 文件 mtime」。
7. 本任务**不动冲突**，下一轮再试；若 main 届时仍停在 `823ebca5`，
   预期仍是同样的 10 个冲突（集成头再前进只会让冲突变多，不会变少）。

## 文件清单

| 文件 | 动作 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-27-0114-main-sync-round83-conflict-not-merged.md` | 新增（本文件） |

> 本轮**未改任何业务代码**：只搬运 commit（结果为 0 个），未产生 merge commit、未 push 集成头、未 push main。
> 本轮**未跑全量 `mvnw verify`**（发热高峰，属流水线 L2）；仅在持闸且无并发构建时跑了
> `pnpm typecheck`（快速预检的 TS 侧）用于复现并定位流水线 L2 红。
> 本轮**未触碰**崩溃 tick 留下的 4 个未提交文件（已 stash/pop 逐字节复原）。
