# 2026-10-26: 集成头同步 main 第 80 轮 —— 冲突未并入（9 个冲突：7 个沿用 + 2 个新增 identity 通知声明）

## 摘要

本轮**结果是冲突未并入**：取到闸 → `git fetch origin --prune` → `merge-base --is-ancestor` ⇒ `HAS_UPDATE`
⇒ `git merge --no-ff origin/main` **报 9 个冲突** ⇒ 按协议 `git merge --abort`（退出码 0）⇒
**没有预检、没有 push、没有产生 merge commit**，并入的 main 提交数 = **0**。

与第 79 轮同为「冲突未并入」，但**冲突面从 7 个涨到 9 个**：新增的两个都落在
`backend/services/identity/` 的**通知能力声明**上，根因是集成头在第 79 轮之后又前进了
（`f94d7d49` 把通知接口里对相邻模块的声明从「未交付」改成「已交付」），而 main 侧仍是旧快照。

**本轮的主要成本同样在闸**：起手 `gate status` = `held / ageSeconds 30 / stale=false`，
持闸者是流水线的 **L2 第 105 轮**（同一个 worktree 里在跑 `mvnw verify`），
本轮按协议**只做只读取证 + 竞态 `gate acquire`**（1 秒密度），**竞态跑了约 2785 秒（46 分钟）**，
在持闸 tick 结束、驱动进程退出的瞬间**抢到闸**（21:02:05）。

## 决策 / 结论

1. **`origin/main` 自上轮以来没有前进，仍停在 `823ebca5`（squash 形态）。**

   | 项 | 值 |
   | --- | --- |
   | `origin/main` tip | `823ebca5`「Integration/module dev (#66)」 |
   | 父 | **仅 1 个**：`6522c497`（= 与集成头的 merge-base） |
   | 待并入提交数 | **1** |
   | 集成头 tip（fetch 后） | `d70ce0e8`「docs(batch-104): 1.3.2.2 / 1.1.2.2 …」 |
   | 集成头相对 merge-base 的提交数 | **1049** |
   | 与集成头的树差异（2-dot） | **87 文件 / +6702 / −33439** |
   | 3-dot diff 规模 | 1032 文件 / +510134 / −45765 |

   ⇒ main 自第 79 轮起**一动没动**；变的只是集成头继续往前走。

2. **main 侧内容是集成头的严格「更旧子集」—— 合并在内容上本应是 no-op。**

   2-dot diff 的 `A`（只存在于 main 的文件）条目数 = **0**；
   `D`（只存在于集成头的文件）条目数 = **70**。
   即：**main 没有任何一个文件是集成头没有的**，main 缺的是集成头后来新增的
   1.3.1.1 通知列表 / 1.3.2.1 预警规则 / 1.3.2.2 外部渠道推送 / 1.1.2.1 核心指标卡 /
   1.1.2.2 店铺概览 等模块文件。

   ⇒ **9 个冲突全部是「squash 压合 vs 线性历史」的历史形状产物**，
   不是「main 带进来新代码、和集成头真冲突」。
   （`823ebca5` 是 GitHub squash merge，**单父**；被压进 main 的集成分支历史在
   main 侧不再保留祖先链 ⇒ git 只能把它当成「与集成头平行同源的两个快照」来 diff。）

3. **9 个冲突构成：6 个 add/add + 1 个 content（沿用第 79 轮的 7 个）+ 2 个 add/add（新增）。**

   | 冲突文件 | 类型 | ours 行 | theirs 行 | ours 独有行 | theirs 独有行 | 性质 |
   | --- | --- | --- | --- | --- | --- | --- |
   | `apps/web/src/components/dashboard/workbench-card-registry.ts` | add/add | 207 | 184 | 24 | 1 | 语义（集成头更新） |
   | `apps/web/src/components/dashboard/workbench-layout-model.test.ts` | add/add | 293 | 286 | 8 | 1 | 语义（集成头更新） |
   | `apps/web/src/lib/api-proxy.ts` | **content** | 12668 | 12470 | 198 | **0** | 语义（集成头**严格超集**） |
   | `backend/services/finance/.../FundAnalysisIntegrationTest.java` | add/add | 932 | 925 | 9 | 2 | 语义（集成头更新） |
   | `backend/services/identity/.../NotificationInboxController.java` | add/add | 166 | 166 | 3 | 3 | **语义（双向各改，1.3.x 声明）** |
   | `backend/services/identity/.../NotificationPreferenceService.java` | add/add | 425 | 425 | 2 | 2 | **语义（双向各改，1.3.2.x 声明）** |
   | `backend/services/reporting/.../ReportingFinancePort.java` | add/add | 241 | 192 | 49 | **0** | 语义（集成头**严格超集**：收款单 12.2.1.1） |
   | `backend/services/trade/.../AnomalyOrderIntegrationTest.java` | add/add | 737 | 610 | 162 | 35 | 语义（集成头更新） |
   | `backend/services/trade/.../AnomalyOrderTenancyIntegrationTest.java` | add/add | 365 | 345 | 31 | 11 | 语义（集成头更新） |

   ⇒ 7 个沿用冲突的形态与第 79 轮一致（ours 普遍是 theirs 的超集）。
   ⇒ **两个新增冲突是唯一「双向各改」的**（ours-only == theirs-only），
   但方向同样清楚：**集成头侧是对的、main 侧是旧快照**（详见下条）。

4. **两个新增冲突的根因：集成头把「相邻模块已交付」如实写进了通知接口的能力声明。**

   对应集成头提交 `f94d7d49`「fix(identity): 通知能力的相邻模块声明从『未交付』改成『已交付』
   —— 别让 API 对着已上线的模块说它没上线」。

   | 文件 | 集成头（ours，正确） | main（theirs，旧快照） |
   | --- | --- | --- |
   | `NotificationInboxController.java` | `1.3.1.2 通知偏好 … 已交付` / `1.3.2.1 预警规则已交付` / `1.3.2.2 外部渠道推送已交付` | 三条全是 `未交付` |
   | `NotificationPreferenceService.java` | `1.3.2.1 预警规则已交付` / `1.3.2.2 外部渠道推送已交付` | `1.3.2.1 预警规则未交付` / `1.3.2.2 外部渠道推送未交付` |

   ⇒ 这不是「两份都对、随便挑一份」的对等冲突，而是**新旧快照差**：
   main 说 1.3.2.1 / 1.3.2.2 未交付，是因为压合那一刻它们确实还没交付；
   集成头说已交付，是因为它们**现在**确实已交付。

5. **`merge-union` 驱动器仍只兜底契约与台账两类文件**，行为与第 79 轮一致：

   - 合并日志明写「台账按模块 ID 求并集，折叠了 465 行同 ID 记录」
     ⇒ `docs/product/capability-blueprint/module-progress.md` 被自动并集；
   - `packages/api-contracts/src/contracts/{identity,reporting}.ts` 与
     `{identity,reporting}.openapi.json` 自动并集；
   - ⇒ 剩下 9 个全是**代码 / 测试**文件，需要人工语义裁决，协议不允许本任务自行解。

6. **`git merge --abort` 干净退出，P0「abort 失败 / `MERGE_HEAD` 残留」未复发。**
   事后核查：`git rev-parse -q --verify MERGE_HEAD` ⇒ **无残留**；
   `git status --porcelain` ⇒ **空**；`HEAD` 回到 `d70ce0e8`；
   `git status --short --branch` ⇒ 与 `origin/integration/module-dev` **齐平**（无 ahead/behind）。
   （注意：abort 同时把 merge-union 改写过的台账与契约文件一并还原了 —— 工作区回到干净态。）

7. **闸：本轮走的是「正常交接」路径 —— 竞态 `acquire` 抢在持闸 tick 退出后立刻取到。**

   | 证据 | 实测 |
   | --- | --- |
   | 持闸者 | 流水线 **L2 第 105 轮**（同一 worktree 内 `mvnw -pl services/{warehouse,trade,crm,finance,reporting,platform,channel} verify`） |
   | 起手判据 | `gate status` = `held / ageSeconds 30 / stale=false` ⇒ **活闸，不能接管** |
   | owner 文件时间戳 | `2026-10-26T11:02:25.386Z`（= 19:02:25 +08:00）⇒ 流水线 **19:02:25** 取的闸 |
   | keepalive 现状 | `.dev-agent/keepalive-r105.sh` 每 20 分钟新建 `keepalive-r105-*` 文件刷新闸目录 mtime（另有 `wait-gate-safe.sh` 每 30 秒新建 `keepalive-poll-*`）⇒ **闸龄被持续压住，TTL 接管分支不会触发** |
   | 活性取证（闸外） | `ps` 抓到 `classworlds … -pl services/warehouse -am verify` 等真实构建进程 ⇒ 持闸者活着 |
   | 交接 | 21:02:05 竞态 `acquire` 返回 `ok: true`，owner 改写为本轮 pid `52727`；同刻 `ps` 查 L2 驱动与 keepalive 进程**均已退出**，channel 全量复跑已 **BUILD SUCCESS**（2344 例 / 0 失败） |
   | 闸龄 | 全程被 keepalive 压住（`stale` 始终 false），**未走 TTL 接管**、**未清闸**、**未 force** |

   ⇒ 等待期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log` / `git diff`），
   **没有跑 `git fetch`、没有跑任何构建**，因此没有与 L2 争抢
   `backend/**/target/` 与 PG 容器 —— 预检不会被并发构建假红。

8. **台账无变化（未同步 ⇒ 不刷新）。**
   同步前后均为 **已交付 410 / 进行中 0 / 未开发 55 / 完成率 88.2%**（`sync.mjs --check` ⇒
   「✓ 台账与蓝图一致」，退出码 0）。相对第 79 轮的 408 / 0 / 57 **多 2 个已交付模块**，
   那是流水线 batch-103 / batch-104 交付 1.3.2.2 外部渠道推送 与 1.1.2.2 店铺概览 带来的，
   **与本任务无关**。

## 后续待办

1. **main 侧需要一次「用集成头快照刷新 main」的正常合并**，而不是让同步任务硬解 9 个冲突。
   根因是 PR #66 用了 **squash merge**：压合掉了集成分支的祖先链，
   于是每一次「main 落后于集成头」的同步都会在「同源快照」上重新长出冲突。
   ⇒ 若后续 PR 改用 **merge commit**（保留祖先链），本类冲突应当自然消失。
2. **第 79 轮遗留问题在本轮依然成立**：`823ebca5` 自 2026-10-26 17:41 起就不再前进，
   而集成头已前进到 `d70ce0e8`（相对 merge-base 1049 个提交）。
   ⇒ 同步通道已连续两轮无法把 main 的任何内容并入集成头（虽然经取证，main 的内容本来就是集成头的子集，
   **实质上没有新东西需要并入**）。
3. 本任务**不动冲突**，下一轮再试；若 main 届时仍停在 `823ebca5`，
   预期仍是同样的 9 个冲突（集成头再前进只会让新增声明类冲突变多，不会变少）。

## 文件清单

| 文件 | 动作 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-26-2102-main-sync-round80-conflict-not-merged.md` | 新增（本文件） |

> 本轮**未改任何业务代码**：只搬运 commit（结果为 0 个），未产生 merge commit、未 push 集成头、未 push main。
