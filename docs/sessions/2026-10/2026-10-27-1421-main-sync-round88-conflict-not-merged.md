# 2026-10-27: 集成头同步 main 第 88 轮 —— 冲突未并入（11 个，与第 85/86/87 轮逐个一致）＋ 闸内并发 land 取证

> 本轮 14:15 起跑、**14:18:20 取到闸**、14:19:40 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **11 个**（10 add/add + 1 content），与第 85 轮 `fa26ddbd`、第 86 轮 `18f812e5`、
> 第 87 轮 `eb7a585e` **逐个一致**。
> 本轮新增取证有两处：**①** 取闸只等 **3 分 20 秒**（上轮等 2 小时 59 分），
> 密集竞态在第 87 轮提交完日志、释放闸后 **9 秒**就抢到；
> **②** 本轮持闸期间，流水线的 **land 提交仍写进了集成头**（`cfa2ac6c`，14:20:44）——
> **闸并没有把「落批提交」这一路与同步任务互斥开**（新发现，见第五节）。

## 摘要

| 项                     | 值                                                                        |
| ---------------------- | ------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（11 个冲突，10 add/add + 1 content）                        |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                         |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85/86/87 轮同一颗 squash）   |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                  |
| 台账                   | **414 已交付 / 0 进行中 / 51 未开发**（465 行，`sync.mjs --check` ✅ 一致） |
| 台账变化               | **无**（未并入任何内容；与第 87 轮基线逐字相同）                             |
| 冲突                   | 11 个（见下「冲突面」）                                                     |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                          |
| push                   | **未 push**（本地领先远端 33 个提交，发布是流水线的决定）                    |
| 闸                     | **已释放**                                                                 |

## 一、取闸：等了 3 分 20 秒

14:15 `gate status` ⇒ `held` / `stale=false` / `ageSeconds=52`，owner 文件为
`10976 / 2026-10-27T06:14:16.428Z`。

⚠️ **owner PID 不是存活证据**（协议原文）：`gate acquire` 是短命进程。真实持闸者是
**第 87 轮同步任务自己** —— 它的会话日志提交 `eb7a585e` 时间戳为 14:18:11，与本轮
取到闸的 14:18:20 相差 **9 秒**。

按协议走**竞态 `gate acquire`**（`mkdirSync` 原子互斥，持闸且未过期时失败且无副作用），
1 秒间隔密集重试，**不做低频 `status` 观测**。闸外 `ps` 取证见 14:15 时刻：

| PID   | 命令                                                | 含义                              |
| ----- | --------------------------------------------------- | --------------------------------- |
| 11609 | `cd …/integration/.worktrees/13.2.2.1/backend && ./mvnw -q compile` | 车道 13.2.2.1 的**编译**在跑（车道 worktree，不在集成头） |

### 1.1 闸序合规性

等闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log` / `git status`），
**连 `git fetch` 都没跑**；**没有跑第二个构建**（流水线的 `mvnw verify` 与预检共用
`backend/**/target/` 与 PG 容器，并发必假红）。取到闸后 `git status --porcelain` 为空才动手。

### 1.2 交接窗口实测（对协议第 1 步的补充证据）

协议实测过「交接窗口可窄到零宽度」（2026-10-20 03:04:02）。本轮再次撞到：
第 87 轮 14:18:11 提交完日志 → 14:18:20 本轮 `acquire` 成功 ⇒ **窗口 ≤ 9 秒**。
**若按「先观测到 free 再 acquire」的写法，本轮大概率整段错过。**

## 二、main 侧：这一轮**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85/86/87 轮**同一颗** squash（单父提交，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main           ⇒ 6522c497
```

### 2.1 模块级口径（三份台账逐模块 join，本轮独立复算）

| 台账版本                     | 已交付 | 进行中 | 未开发 |
| ---------------------------- | ------ | ------ | ------ |
| merge-base `6522c497`        | 340    | 0      | 125    |
| `origin/main` tip `823ebca5` | 406    | 0      | 59     |
| 集成头（合并时 `eb7a585e`）  | **414**| 0      | **51** |

- 该 squash 相对 merge-base 带入 **66 个**模块交付；
- main 与集成头状态不一致的模块共 **8 个**，**方向全部是「main 未开发 / 集成头已交付」**：

| 模块 ID   | 名称            | main     | 集成头   |
| --------- | --------------- | -------- | -------- |
| 1.1.2.1   | 核心指标卡      | 未开发   | 已交付   |
| 1.1.2.2   | 店铺概览        | 未开发   | 已交付   |
| 1.3.2.1   | 预警规则        | 未开发   | 已交付   |
| 1.3.2.2   | 外部渠道推送    | 未开发   | 已交付   |
| 1.5.2.1   | 服务与任务状态  | 未开发   | 已交付   |
| 13.1.2.1  | 目标设定        | 未开发   | 已交付   |
| 13.2.1.1  | 销量趋势        | 未开发   | 已交付   |
| 13.2.2.2  | SKU/SPU/Listing | 未开发   | 已交付   |

⇒ **main 领先（main 已交付、集成头未交付）的模块数 = 0** ⇒ **本次合并不携带任何新交付**。

## 三、冲突面（11 个，与第 85/86/87 轮逐个一致）

`git merge --no-ff origin/main`（PRE=`eb7a585e`）⇒ **11 个冲突**：

| 冲突类型 | 文件                                                                                        |
| -------- | ------------------------------------------------------------------------------------------- |
| add/add  | `apps/web/src/components/dashboard/workbench-card-registry.ts`                                |
| add/add  | `apps/web/src/components/dashboard/workbench-layout-model.test.ts`                             |
| content  | `apps/web/src/lib/api-proxy.ts`                                                               |
| add/add  | `backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java`   |
| add/add  | `backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxController.java` |
| add/add  | `backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceService.java` |
| add/add  | `backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java` |
| add/add  | `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingFinancePort.java`      |
| add/add  | `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingUpstreamClient.java`   |
| add/add  | `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderIntegrationTest.java`       |
| add/add  | `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderTenancyIntegrationTest.java` |

⇒ 与第 85 轮 `fa26ddbd`、第 86 轮 `18f812e5`、第 87 轮 `eb7a585e` 的冲突面**逐个一致**（10 add/add + 1 content）。
根因仍是 `origin/main` 的 tip 是**集成头自身历史被 GitHub 压成的 squash**（见
`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`，P0 未解）：
merge-base 被拉回 `6522c497`，batch-28 之后集成头交付的模块在 merge-base 上**根本不存在** ⇒ 两边都是「各自新增」⇒ add/add。

**未解任何冲突**（协议硬边界）：`git merge --abort` 已执行，`MERGE_HEAD` 已清，
工作区回到 `eb7a585e` 且 `git status --porcelain` 为空。

## 四、为什么没 push / 没跑预检

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**：本地集成头领先 `origin/integration/module-dev` **33 个提交**、落后 0 个。
  本轮**没有产生任何可发布的合并**，发布是流水线的决定（第 85/86/87 轮同此口径）。

## 五、新取证：闸**不拦**流水线的 land 提交（本轮新发现）

本轮取闸时间 **14:18:20**，而流水线在 **14:20:44** 向集成头提交了

```
cfa2ac6c docs(batch-106): 补记 13.1.2.2 落地状态 —— L1 全绿、land 成功，等待下轮并入
```

⇒ **在同步任务持闸期间，流水线的落批提交仍然写进了集成头。**
本轮 `reflog` 全链：

```
14:20:44 cfa2ac6c  commit: docs(batch-106): 补记 13.1.2.2 落地状态 …   ← 流水线 land（闸内）
14:19:40 eb7a585e  reset: moving to HEAD                                ← 本轮 merge --abort
14:18:20 (owner=13726)                                                 ← 本轮取到闸
14:18:11 eb7a585e  commit: docs(sync): 第 87 轮 …                       ← 第 87 轮提交日志
14:17:01 f87635ca  reset: moving to HEAD                                ← 第 87 轮 merge --abort
14:14:16 (owner=10976)                                                 ← 第 87 轮取到闸
```

⇒ **调度闸目前只互斥「派发 / L2 / 同步」三路，并不互斥「land 落批提交」这一路。**
同步任务在持闸窗口内若做 `git merge`，**PRE 可能在合并途中被流水线顶掉**（本轮侥幸未中：
`cfa2ac6c` 是 docs-only，且与 11 个冲突文件**零重叠**——已用
`git show --name-only cfa2ac6c` 与冲突清单逐行比对确认）。

⚠️ 这意味着协议第 3 步「`git status --porcelain` 为空才动手」**不足以**保证合并基线稳定：
合并**开始后**仍可能被 land 提交插队。**待办见下第 3 条。**

## 六、待办

1. **P0（发布侧）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （已连续 **10 轮**：第 79–88 轮）。见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **协议待补**：`SYNC-MAIN.md` 第 1 步的「竞态 `gate acquire`」应补一句副作用提示 ——
   **失败尝试会写 keepalive、刷新闸目录 mtime、延后 `stale` 翻转**（第 87 轮 1.2 节取证）。
3. **协议/调度器待修（新）**：**闸要覆盖 land 落批提交**（本轮第五节），
   否则同步任务的合并基线在合并过程中可能被顶掉。
   另：`lane.mjs` 的 `gateRelease` 仍是无条件 `rmSync(GATE_DIR)`（未修，协议边界），
   持闸者收尾会删掉别人刚接管的闸；闸龄判据仍按目录 mtime 而非最新 keepalive。
4. **文档笔误更正**：第 87 轮会话日志把两个 backlog 文件路径写成了 `docs/backlog/2026-10/` 子目录，
   实际在**扁平目录**下 —— 正确路径是
   `docs/backlog/2026-10-23-gate-stale-false-positive-live-tick-heartbeat-gap.md` 与
   `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`
   （`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` 确实在子目录里）。
5. 下一轮同步若 main 仍无新提交，预期仍是「冲突未并入」；**除非 P0 被解，否则本任务会一直空转**。

## 七、文件清单

- 新增：本文件
- 未改任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify、未改台账
