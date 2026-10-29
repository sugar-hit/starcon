# 2026-10-27: 集成头同步 main 第 87 轮 —— 冲突未并入（11 个，与第 85/86 轮逐个一致）＋ 取闸三小时取证

> 本轮 11:15 起跑、**14:14 取到闸**、14:17 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **11 个**（10 add/add + 1 content），与第 85 轮 `fa26ddbd`、第 86 轮 `18f812e5`
> **逐个一致**。
> 本轮新增的取证在**闸**上：取闸等了 **2 小时 59 分**，期间把 `docs/backlog/2026-10-23-gate-stale-false-positive-live-tick-heartbeat-gap.md`
> 记的那个「`stale` 会把活着的 tick 判成僵死闸」的坑**完整撞了一遍**，并**钉死了这次不是假阳性**。

## 摘要

| 项                     | 值                                                                       |
| ---------------------- | ------------------------------------------------------------------------ |
| 结果                   | **冲突未并入**（11 个冲突，10 add/add + 1 content）                       |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                        |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85/86 轮同一颗 squash）    |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                 |
| 台账                   | **414 已交付 / 0 进行中 / 51 未开发**（465 行，`sync.mjs --check` ✅ 一致）|
| 台账变化               | **无**（未并入任何内容；与第 86 轮基线逐字相同）                            |
| 冲突                   | 11 个（见下「冲突面」）                                                    |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                        |
| push                   | **未 push**（本地领先远端 31 个提交，发布是流水线的决定）                  |
| 闸                     | **已释放**                                                                |

## 一、取闸：等了 2 小时 59 分（本轮主要取证）

### 1.1 闸一直被活着的 tick 持着

11:15 起手 `gate status` ⇒ `held` / `stale=false` / `ageSeconds=151`。按协议走**竞态 `gate acquire`**
（`mkdirSync` 原子互斥；持闸且未过期时失败且**无任何副作用**），不低频观测 `status`。

闸外取证（`ps`）确认持闸者是**活着的流水线 tick r112**：

| PID   | 命令                                            | 含义                                        |
| ----- | ----------------------------------------------- | ------------------------------------------- |
| 91980 | `bash .tmp/keepalive-r112.sh`                  | tick r112 的**心跳续期脚本**                |
| 91986 | `bash .tmp/l2-r112.sh`                         | tick r112 的 **L2 脚本**                    |
| 91989 | `java … plexus … Launcher -fae verify`         | **L2 全量 `mvnw verify`**（跑在集成头 worktree） |

L2 的测试报告持续在写（11:11 identity → 11:27 channel → 11:40 warehouse → 13:11 trade →
13:30 crm → 13:47 finance），⇒ **不是僵死进程，是在真跑**。

### 1.2 `ageSeconds` 乱跳的根因（本轮钉死）

`gate status` 的 `ageSeconds` 全程**非单调**：151 → 251 → 504 → 535 → **89** → 219 → … → 4008。
根因是 `lane.mjs` 按 **`GATE_DIR` 目录 mtime** 算闸龄，而**每次 `gate acquire` 尝试都会在闸目录里
留下一个 keepalive 文件**（本轮实测产生了 `keepalive-r112-134709-manual`），**顺带刷新了目录 mtime**
⇒ 把闸龄**人为压小**、`stale` 一直不翻。

⚠️ 这条对**同步任务本身**是个副作用：**反复 `gate acquire` 会给持闸者的闸「续命」**，
让 `stale` 迟迟不出现。好处是本轮**绝不会误接管活闸**；代价是**接管僵死闸会被推迟**。
（协议没写这一条，属于本轮新发现。）

### 1.3 为什么判定「不是 `stale` 假阳性」

`docs/backlog/2026-10-23-gate-stale-false-positive-live-tick-heartbeat-gap.md` 记的是
「`stale:true` 但 tick 其实活着」。本轮是**反过来的**：闸自始至终 `stale=false`，且三条判据全部指向「活着」：

1. 闸目录里有 **13:47:09 / 13:48:12** 两个**很新**的 keepalive（tick 在动）；
2. `l2-r112.sh` + `mvnw verify` + surefire 子进程**全程存活**，测试报告持续产出；
3. owner 里的 PID 91898 查无此进程 —— 但协议早就写明 **owner PID 不是存活证据**
   （`gate acquire` 是短命进程），**真实持闸者就是那两个 `.tmp/` 脚本**。

⇒ **没有接管任何活闸。** tick r112 在 14:14 前正常跑完 L2、**正常释放了闸**，
本轮的 `gate acquire` 是**在闸空闲时取得**的（14:14:16，owner=10976）。

### 1.4 闸序合规性

等闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log`），
**连 `git fetch` 都没跑**；**没有跑第二个构建**（L2 的 `mvnw verify` 与预检共用
`backend/**/target/` 与 PG 容器，并发必假红）。取到闸后 `git status --porcelain` 为空才动手。

## 二、main 侧：这一轮**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85/86 轮**同一颗** squash（单父提交，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
```

### 2.1 模块级口径（三份台账逐模块 join）

| 台账版本                              | 已交付 | 进行中 | 未开发 |
| ------------------------------------- | ------ | ------ | ------ |
| merge-base `6522c497`                 | 340    | 0      | 125    |
| `origin/main` tip `823ebca5`          | 406    | 0      | 59     |
| 集成头 `f87635ca`（合并时）           | **414**| 0      | **51**|

- 该 squash 相对 merge-base 带入 **66 个**模块交付；
- main 与集成头状态不一致的模块共 **8 个**，**方向全部是「main 未开发 / 集成头已交付」**：

| 模块 ID   | 名称              | main     | 集成头   |
| --------- | ----------------- | -------- | -------- |
| 1.1.2.1   | 核心指标卡        | 未开发   | 已交付   |
| 1.1.2.2   | 店铺概览          | 未开发   | 已交付   |
| 1.3.2.1   | 预警规则          | 未开发   | 已交付   |
| 1.3.2.2   | 外部渠道推送      | 未开发   | 已交付   |
| 1.5.2.1   | 服务与任务状态    | 未开发   | 已交付   |
| 13.1.2.1  | 目标设定          | 未开发   | 已交付   |
| 13.2.1.1  | 销量趋势          | 未开发   | 已交付   |
| 13.2.2.2  | SKU/SPU/Listing   | 未开发   | 已交付   |

⇒ **main 领先（main 已交付、集成头未交付）的模块数 = 0** ⇒ **本次合并不携带任何新交付**。

## 三、冲突面（11 个，与第 85/86 轮逐个一致）

`git merge --no-ff origin/main`（PRE=`f87635ca`）⇒ **11 个冲突**：

| 冲突类型 | 文件 |
| -------- | ---- |
| add/add  | `apps/web/src/components/dashboard/workbench-card-registry.ts` |
| add/add  | `apps/web/src/components/dashboard/workbench-layout-model.test.ts` |
| content  | `apps/web/src/lib/api-proxy.ts` |
| add/add  | `backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java` |
| add/add  | `backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxController.java` |
| add/add  | `backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceService.java` |
| add/add  | `backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java` |
| add/add  | `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingFinancePort.java` |
| add/add  | `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingUpstreamClient.java` |
| add/add  | `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderIntegrationTest.java` |
| add/add  | `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderTenancyIntegrationTest.java` |

⇒ 与第 85 轮 `fa26ddbd`、第 86 轮 `18f812e5` 的冲突面**逐个一致**（10 add/add + 1 content）。
根因仍是 `origin/main` 的 tip 是**集成头自身历史被 GitHub 压成的 squash**（见
`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`，P0 未解）：
merge-base 被拉回 `6522c497`，batch-28 之后集成头交付的模块在 merge-base 上**根本不存在** ⇒ 两边都是「各自新增」⇒ add/add。

**未解任何冲突**（协议硬边界）：`git merge --abort` 已执行，`MERGE_HEAD` 已清，
工作区回到 `f87635ca` 且 `git status --porcelain` 为空。

## 四、为什么没 push / 没跑预检

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**：本地集成头领先 `origin/integration/module-dev` **31 个提交**、落后 0 个。
  本轮**没有产生任何可发布的合并**，发布是流水线的决定（第 86 轮同此口径）。

## 五、待办

1. **P0（发布侧）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （已连续 9 轮：第 79–87 轮）。见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **协议待补**：`SYNC-MAIN.md` 第 1 步的「竞态 `gate acquire`」应补一句副作用提示 ——
   **失败尝试会写 keepalive、刷新闸目录 mtime、延后 `stale` 翻转**（本轮 1.2 节取证）。
3. **`lane.mjs` 待修**（未修，协议边界）：`gateRelease` 仍是无条件 `rmSync(GATE_DIR)`，
   持闸者收尾会删掉别人刚接管的闸；闸龄判据仍按目录 mtime 而非最新 keepalive。
4. 下一轮同步若 main 仍无新提交，预期仍是「冲突未并入」；**除非 P0 被解，否则本任务会一直空转**。

## 六、文件清单

- 新增：本文件
- 未改任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify
