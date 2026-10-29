# 2026-10-27: 集成头同步 main 第 85 轮 —— 冲突未并入（11 个，比第 84 轮多 1 个）

## 摘要

本轮**结果是冲突未并入**：取到闸 → `git fetch origin --prune` → `merge-base --is-ancestor` ⇒ `HAS_UPDATE`
（待并入 **1** 个提交）⇒ `git merge --no-ff origin/main` **报 11 个冲突** ⇒ 按协议 `git merge --abort`
（退出码 0，无 `MERGE_HEAD` 残留，unmerged 路径 0，工作区干净）⇒ **没有预检目标、没有 merge commit、没有 push**，
**并入的 main 提交数 = 0**。

与第 84 轮（01:17）的差别：**冲突从 10 个涨到 11 个**，且台账**不再是 414/0/51**
（本轮台账 = 414/0/51，与第 84 轮相同 —— 但集成头已前进 28 个提交，其中含流水线自己的车道成果）。

## 决策 / 结论

1. **`origin/main` 仍停在 `823ebca5`（squash 形态），自第 79 轮起不再前进。**

   | 项                        | 值                                                                                |
   | ------------------------- | --------------------------------------------------------------------------------- |
   | `origin/main` tip         | `823ebca5`「Integration/module dev (#66)」                                        |
   | 父                        | **仅 1 个**：`6522c497`（= 与集成头的 merge-base）⇒ **squash，不是 merge commit** |
   | 待并入提交数              | **1**                                                                             |
   | 集成头 tip（合并时）      | `8e08f5aa`（= 第 110 轮流水线日志提交）                                           |
   | 与集成头的树差异（3-dot） | **1032 文件 / +510134 / −45765**                                                  |
   | 冲突文件数                | **11**（10 个 add/add + 1 个 content）                                            |
   | 并入的 main 提交数        | **0**（冲突中止）                                                                 |

2. **🔴 冲突从第 84 轮的 10 个涨到 11 个 —— 新增的是流水线自己车道的文件。**

   | 冲突文件                                                             | 类型        | ours 行 | theirs 行 | ours 独有行 | theirs 独有行 | 性质                       |
   | -------------------------------------------------------------------- | ----------- | ------- | --------- | ----------- | ------------- | -------------------------- |
   | `apps/web/src/components/dashboard/workbench-card-registry.ts`       | add/add     | 207     | 184       | 16          | 1             | 旧快照                     |
   | `apps/web/src/components/dashboard/workbench-layout-model.test.ts`   | add/add     | 293     | 286       | 3           | 1             | 旧快照                     |
   | `apps/web/src/lib/api-proxy.ts`                                      | **content** | 12853   | 12470     | 261         | **0**         | 集成头**严格超集**         |
   | `backend/services/finance/.../FundAnalysisIntegrationTest.java`      | add/add     | 932     | 925       | 9           | 2             | 旧快照                     |
   | `backend/services/identity/.../NotificationInboxController.java`     | add/add     | 166     | 166       | 3           | 3             | 双向各改（1.3.x 交付声明） |
   | `backend/services/identity/.../NotificationPreferenceService.java`   | add/add     | 425     | 425       | 2           | 2             | 双向各改（1.3.2.x 交付声明）|
   | `backend/services/identity/.../ApprovalTodoIntegrationTest.java`     | add/add     | **634** | **585**   | **41**      | **1**         | **🆕 本轮新增**            |
   | `backend/services/reporting/.../ReportingFinancePort.java`           | add/add     | 241     | 192       | 28          | **0**         | 集成头**严格超集**         |
   | `backend/services/reporting/.../ReportingUpstreamClient.java`        | add/add     | 156     | 112       | 12          | **0**         | 集成头**严格超集**         |
   | `backend/services/trade/.../AnomalyOrderIntegrationTest.java`        | add/add     | 737     | 610       | 121         | 34            | 旧快照                     |
   | `backend/services/trade/.../AnomalyOrderTenancyIntegrationTest.java` | add/add     | 365     | 345       | 25          | 10            | 旧快照                     |

   **新增的第 11 个冲突 = `ApprovalTodoIntegrationTest.java`**，根因已定位到具体提交：

   | 证据                                                              | 实测                                                                    |
   | ----------------------------------------------------------------- | ----------------------------------------------------------------------- |
   | 该文件在 main 侧存在                                               | 是（较旧快照，585 行）                                                  |
   | 引入它的提交 `b2ec6b97`（1.1.1.3 审批待办只读端点，10-23 16:28）   | **是第 84 轮 tip `6aaefb48` 的祖先** ⇒ 该文件第 84 轮就存在            |
   | 改动它的提交 `32a16a6a`（审批待办超时夹具改用 DB 时钟，10-27 02:35）| **不在**第 84 轮 tip 里 ⇒ **第 84 轮之后才落地**                        |
   | 该提交对本文件的改动量                                             | +50 / −1（ours 585 → 634 行）                                          |

   ⇒ **第 84 轮时两侧此文件逐字节相同 ⇒ 无冲突**；流水线的「跨时钟域抖动修复」车道
   （`32a16a6a`，10-27 02:35）在第 84 轮之后改了它 ⇒ **两侧分叉 ⇒ add/add 冲突**。

   ⇒ **这直接印证了第 83 轮的预判**：「集成头再前进只会让冲突变多，不会变少」——
   本轮实测 **10 → 11**，且新增的那个是流水线自己的成果，**不是 main 带进来的**。

3. **3 个文件 main 侧是集成头的严格更旧子集 ⇒ 合并方向在这几条上是反的。**

   用 `diff <(main侧) <(ours侧)` 逐文件复核（不是行级计数，是真实 diff 方向）：

   | 文件                                       | 从 main 删掉的行 | ours 新增的行 | 判定             |
   | ------------------------------------------ | ---------------- | ------------- | ---------------- |
   | `apps/web/src/lib/api-proxy.ts`            | **0**            | 383           | ours ⊇ main      |
   | `backend/services/reporting/.../ReportingFinancePort.java`    | **0** | 49  | ours ⊇ main |
   | `backend/services/reporting/.../ReportingUpstreamClient.java` | **0** | 44  | ours ⊇ main |

   ⇒ main 侧**一行都没被 ours 删掉**，只增不减 ⇒ 合并方向应当是「集成头 → main」，不是「main → 集成头」。
   ⇒ 根因仍是 PR #66 用了 **squash merge**（压掉集成分支的祖先链）。

4. **`merge-union` 驱动器按预期只兜底契约与台账两类文件。**

   本轮用真实两侧内容**逐个复跑驱动**（不依赖 merge 时的副作用），确认 7 个受管文件的走向：

   | 文件                                        | 驱动分支                       |
   | ------------------------------------------- | ------------------------------ |
   | `packages/api-contracts/src/channel.openapi.json`      | 按键求并 ✅ |
   | `packages/api-contracts/src/contracts/channel.ts`       | 按键求并 ✅ |
   | `packages/api-contracts/src/contracts/identity.ts`      | 按键求并 ✅ |
   | `packages/api-contracts/src/contracts/reporting.ts`     | 按键求并 ✅ |
   | `packages/api-contracts/src/identity.openapi.json`      | 按键求并 ✅ |
   | `packages/api-contracts/src/reporting.openapi.json`     | 按键求并 ✅ |
   | `docs/.../module-progress.md`                          | 台账按模块 ID 求并，折叠 465 行同 ID 记录 ✅ |

   ⇒ 驱动 stderr 的「走了行级兜底并集」**只来自台账文件**（`module-progress.md` 的行级兜底路径，
   它随后又按模块 ID 折叠了 465 行）⇒ **6 个契约产物全部走按键求并，没有一个掉进兜底**。
   ⇒ 剩下 11 个全是**代码 / 测试**文件，需要人工语义裁决，**协议不允许本任务自行解**。

5. **闸：本轮是 TTL 接管（不是竞态命中），未清闸、未 force、未停任何守护。**

   | 证据                 | 实测                                                                     |
   | -------------------- | ------------------------------------------------------------------------ |
   | 起始 `gate status`   | `gate=held` / `ageSeconds=3182` / `ttlSeconds=4200` / **`stale=false`** |
   | 持闸 owner           | pid `28317`，取闸时刻 `2026-10-27T00:27:22.443Z`（= 08:27:22 本地）   |
   | 持闸者 PID 28317     | **进程不存在**（`ps -p` 无此 PID）⇒ 持闸者已崩                        |
   | 闸目录最后一次续期   | `keepalive-092230`（09:22:30）⇒ 之后 76 分钟无人续期                   |
   | 闸外构建进程         | `ps` 全仓**零** maven / classworlds / surefire / next / vitest         |
   | 闸龄越线时刻         | 09:22:30 + 4200s = **10:32:30** ⇒ `stale` 转 `true`                    |
   | 竞态命中时刻         | **10:38:57**（owner = 本轮 pid `77513`）                                |
   | 同期另一个竞态者     | `.tmp/sync-race-r111.sh`（pid 29655，流水线 r111 的守护脚本）也在排队   |

   ⇒ 持闸者是 **08:27:22 起闸、09:45:47 因 `net::ERR_CONNECTION_RESET` 崩掉的同步会话**
   （会话 `mvs_0b33243e5e5c4e38960eec304526564c`，status=`idle` + `errorCode 50113`）
   ⇒ 崩溃时**没有 `gate release`**，闸被遗留在磁盘上直到 TTL 自然过期。
   ⇒ 本轮按协议 §2「等闸」用 **1 秒密度 `gate acquire` 竞态**，在 TTL 越线后命中；
   **未清闸、未 force、未 rm -rf 闸目录、未停任何守护**。
   ⇒ ⚠️ 注意 r111 的竞态脚本用的是**更严的判据**（`stale=true` 还要求「无活构建进程 + 工作区干净 + HEAD 稳定 ≥120s」），
   本轮命中的判据与之等价（无构建进程、工作区干净、HEAD 稳定）⇒ **两边的接管条件不冲突**。

6. **🟢 流水线 L2 红的根因（`api-proxy.ts` TS1117）已经修好了 —— 本轮亲自复跑确认。**

   第 83/84 轮定位的根因：车道提交 `8badb7435` 加 `prefix: 'channel/'` 条目时漏了 `},` 闭合 ⇒
   与 `c107dcbe9` 的 `prefix: 'reporting/'` 条目塌进同一个对象字面量 ⇒ TS1117 ×4。

   | 证据                                                    | 实测                                                                     |
   | ------------------------------------------------------- | ------------------------------------------------------------------------ |
   | 修复提交                                                | `3dcfa0b6`（10-27 02:13，`fix(web): 修复 api-proxy.ts 服务元素字面量被并成一块`）|
   | 是否在第 84 轮 tip `6aaefb48` 里                        | **不在** ⇒ 第 84 轮之后才落地                                           |
   | 复跑 `pnpm typecheck`（**`turbo run typecheck --force`，绕缓存**） | **3 successful / 3 total，Cached: 0**，退出码 **0** ✅           |
   | 复核 `api-proxy.ts:12705-12712`                        | `pattern:` 正则行之后**已有 `},`** ⇒ 缺陷已修                            |

   ⇒ 本轮 typecheck 是**强制绕过 turbo 缓存真跑**的（首跑是 cache hit，路径指向别的 worktree，不可信 ⇒ 已 `--force` 复跑）。
   ⇒ ⇒ **流水线 round-107/109 的前端侧红因已消除**，下一轮流水线的 L2 前端侧应可回绿。

7. **后端快速预检绿：`./mvnw -q compile` 退出码 0。**

   ⇒ 集成头 HEAD 在**不并入 main** 的前提下**编译通过**（退出码 0，仅 jansi/guava 的 JVM 警告）。
   ⇒ 本轮**没有跑全量 `mvnw verify`**（发热高峰，属流水线 L2）。
   ⇒ ⚠️ L2 r109（09:20:33，`L2 RESULT: RED`）红在 **backend=1**：
   `com.starcon.reporting.StoreComparisonIntegrationTest` 16 例 ERROR；
   但流水线自带的**隔离复跑**（`.tmp/storecomparison-isolated-r109.log`）**20 例全绿、BUILD SUCCESS**
   ⇒ 该红是**并发容器争抢类假红**（同第 103 轮的容器清道夫事故同类），**不是代码缺陷**。

8. **台账：414 / 0 / 51，与第 84 轮完全相同（零文件改动）。**

   `node tools/module-progress/sync.mjs` ⇒ 「✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 **414** · 进行中 **0** · 未开发 **51**」，
   **零文件改动**；`sync.mjs --check` ⇒ 「✓ 台账与蓝图一致」。
   ⇒ 本轮并入 main 的 **0** 个提交 ⇒ 台账基线不变。

9. **本轮不 push。**

   - 冲突 ⇒ 没有 merge commit ⇒ **没有属于本轮的东西可推**（与第 81/82/83/84 轮同一处置）；
   - 集成头当前比 `origin/integration/module-dev` **领先 28 个提交**，这些是流水线自己的车道成果；
   - 流水线的 push 权责在它自己的 L2 绿之后（`PIPELINE.md` §5.3：③ push 只在 L2 绿、且说明已 commit 之后）；
   - ⇒ 本篇文档**本地 commit、不推**，留给下一轮流水线连同它自己的批次一起推。

10. **`git merge --abort` 干净退出**，事后核查：`MERGE_HEAD` ⇒ 无残留；
    `git diff --diff-filter=U` ⇒ 0；`git status --porcelain` ⇒ 0 行（工作区干净）；`HEAD` 回到 `8e08f5aa`（= 合并前 `PRE`，逐字节相同）。

## 后续待办

1. **main 侧需要一次「用集成头快照刷新 main」的正常合并（保留祖先链）**，而不是让同步任务硬解 11 个冲突。
   根因仍是 PR #66 用了 **squash merge**（压掉集成分支的祖先链 ⇒ main 是集成头的严格更旧子集）。
   ⇒ 若后续 PR 改用 **merge commit**，本类冲突应当自然消失。
   ⇒ 合并方向应当是「集成头 → main」，不是「main → 集成头」。
2. **流水线可以重跑 L2 了**：前端侧红因（`api-proxy.ts` TS1117）已由 `3dcfa0b6` 修掉，本轮 `--force` 复跑 typecheck 绿。
   ⇒ 下一轮 L2 前端侧预期回绿；后端侧若仍红，先看是不是 `StoreComparisonIntegrationTest` 的**容器争抢假红**（隔离复跑 20 例已全绿）。
3. **同步任务之间需要互斥**（第 81 轮提出，仍未修）：
   取闸后应写入自己的 owner 标识，动 worktree 前校验「当前闸 owner 是自己」。
   ⇒ 本轮观察到**崩溃持闸者把闸遗留在磁盘上，直到 TTL 自然过期才可接管**（08:27:22 起闸 → 10:32:30 才 stale）。
   ⇒ 建议：崩溃持闸者的闸应能被**心跳判活**快速识别，而不是硬等满 70 分钟 TTL。
4. **keepalive 守护应随 tick 一起结束**（P0 登记表待办，仍未修）：
   守护应带「持有者 PID 存活检测」，持有者一死就自杀；否则每个崩掉的 tick 都会留一个孤儿守护，把闸永久顶成「假 held」。
   ⇒ 本轮实测到 **r111 的竞态脚本（pid 29655）仍在排队等闸**；它用的是更严的判据（stale + 无构建 + 干净 + HEAD 稳定 ≥120s），与本轮不冲突，但**本轮释放后它会立刻接管** ⇒ 下一轮流水线可直接用它。
5. `lane.mjs` 的 `gate release` 仍是无条件 `rmSync(GATE_DIR)`，并发下会互清；
   `gate status` 的年龄仍取自「闸目录 mtime」而非「最新 keepalive 文件 mtime」。
6. 本任务**不动冲突**，下一轮再试；若 main 届时仍停在 `823ebca5`，
   预期冲突**只会更多不会更少**（第 83 轮预判、第 84/85 轮连续实测兑现：10 → 11）。

## 文件清单

| 文件                                                                             | 动作           |
| -------------------------------------------------------------------------------- | -------------- |
| `docs/sessions/2026-10/2026-10-27-1050-main-sync-round85-conflict-not-merged.md` | 新增（本文件） |

> 本轮**未改任何业务代码**：只搬运 commit（结果为 0 个），未产生 merge commit、未 push 集成头、未 push main。
> 本轮**未跑全量 `mvnw verify`**（发热高峰，属流水线 L2）；只在持闸且确认无并发构建时跑了快速预检
> （后端 `./mvnw -q compile` 退出码 0 + 前端 `pnpm turbo run typecheck --force` 3/3 绿）。
> 本轮**未清闸、未 force、未停任何守护**，收尾 `lane.mjs gate release`（本轮**确实 acquire 成功过**）。
