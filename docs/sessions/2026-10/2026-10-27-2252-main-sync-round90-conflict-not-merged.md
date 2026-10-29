# 2026-10-27: 集成头同步 main 第 90 轮 —— 冲突未并入（11 个，与第 85–89 轮逐个一致）＋ 并发同步实例交接取证

> 本轮 21:15 起跑、**22:52:10 取到闸**、22:5x 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **11 个**（10 add/add + 1 content），与第 85 轮 `fa26ddbd`、第 86 轮 `18f812e5`、
> 第 87 轮 `eb7a585e`、第 88 轮 `5bfcc23c`、第 89 轮 `7666d8b2` **逐个一致**。
> 本轮新增取证有四处：**①** 与**另一条同类同步实例**（第 89 轮）**并发等同一个闸**、
> **交接发生在它提交完日志之后的 1 分钟内**；**②** 交接瞬间**集成头已经前进 2 个提交**
> （流水线 round 114 的收尾 + 第 89 轮的会话日志），证明**「等闸期间 HEAD 会漂」**，
> 取闸后必须重新取证；**③** 首次用 `git merge-tree --write-tree` 在**取闸前**做**只读**冲突预演，
> 结论与取闸后真实 merge **逐个一致**；**④** 模块级口径**独立复算**：本次合并**携带 0 个新交付**。

## 摘要

| 项                     | 值                                                                        |
| ---------------------- | ------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（11 个冲突，10 add/add + 1 content）                        |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                         |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–89 轮同一颗 squash）    |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                  |
| 台账                   | **417 已交付 / 0 进行中 / 48 未开发**（465 行，`sync.mjs --check` ✅ 一致） |
| 台账变化               | **无**（未并入任何内容；`sync.mjs --check` 已一致）                        |
| 冲突                   | 11 个（见下「冲突面」）                                                     |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                          |
| push                   | **未 push**（本地领先远端 61 个提交，发布是流水线的决定）                   |
| 闸                     | **已释放**                                                                 |
| 等闸时长               | **1 小时 37 分**（21:15 → 22:52:10）                                       |

## 一、取闸：等 1 小时 37 分，且与**另一条同步实例**并发等同一个闸

### 1.1 闸的持有者：一条**活着的**流水线 tick

21:15 `gate status` ⇒ `held` / `stale=false` / `ageSeconds=18`，owner 文件为
`20138 / 2026-10-27T08:00:52.914Z`（即 16:00:52 取得）。

⚠️ **owner PID 不是存活证据**（协议原文）：`gate acquire` 是短命进程，写完 `owner` 就退出。
实测 `ps -p 20138` ⇒ **进程不存在**，但闸确实被活着的东西持有。

按协议走**竞态 `gate acquire`**（`mkdirSync` 原子互斥；持闸且未过期时失败且**无任何副作用**），
1 秒间隔密集重试，**不做低频 `status` 观测**。等闸全程**只做只读取证**：
`gate status` / `cat owner` / `ps` / `git log` / `git status` / `git merge-tree`；
**连 `git fetch` 都没跑**，**没有跑第二个构建**（流水线的 `mvnw verify` 与预检共用
`backend/**/target/` 与 PG 容器，并发必假红）。

### 1.2 闸外活性取证（判定依据来自闸外）

| 时刻    | 闸外证据                                                              |
| ------- | --------------------------------------------------------------------- |
| 19:32   | `ps`：流水线 **L2 `mvnw -fae verify` 在飞**（pid 72288，reactor 11 模块） |
| 20:58   | `ps`：`services/trade` 的 surefire booter 在跑                        |
| 21:19   | `ps`：**L2 走到 `services/finance`**（surefire 报告落盘）⇒ 确实在推进 |
| 21:22   | `ps`：**L2 走到 `services/reporting` → `services/platform`**（reactor 尾部）|
| 21:24:05| L2 日志终判：`backend=1 typecheck=0 lint=0 test=0 nextbuild=0 synccheck=0` ⇒ **🔴 红** |
| 21:40–  | 闸目录内 `keepalive-r114-N-<epoch>` 持续续期（每 ~2 min 一枚）          |
| 22:43   | `keepalive-r114-14` ⇒ 续期者仍活着                                    |
| 22:52:10 | 持闸者**释放**，本轮 `acquire` 命中                                    |

⇒ **闸是被活着的流水线 tick 持有的**，按协议**等它释放**，不抢、不清、不 force。

### 1.3 本轮新发现 A：`stale` **不可能**在本轮翻转（机制取证）

本轮把「为什么不能靠 TTL 抢闸」从**经验**变成了**机制级**结论：

- `lane.mjs:575` / `:612` 的闸龄判据是 `statSync(GATE_DIR).mtimeMs`；
- 续期手段是**往闸目录里写 `keepalive-*` 文件**（写文件会刷新目录 mtime）；
- 本轮实测续期节奏：`keepalive-r114-13`（22:43:44）→ `keepalive-r114-14`（22:45:44）
  ⇒ **目录 mtime 持续被刷新**，`ageSeconds` 被反复压回两位数；
- ⇒ 只要持闸者的续期进程还活着，`ageSeconds > 4200` **永远不会成立**，`stale` 恒为 `false`。
  **「等 TTL 过期再接管」不是本任务的兜底路径**，唯一的正常路径就是**等显式释放**。

⚠️ 同时实测到**续期空窗**：21:40 → 21:57 之间只有 `keepalive-r114-6` 一枚（17 分钟空窗），
`ageSeconds` 一度涨到 **1017s**。⇒ **「每 2 分钟续期一次」是名义值，不是保证值**
（与第 89 轮记录一致，本轮再次复现）。

### 1.4 本轮新发现 B：**并发同步实例**在同一个闸上排队，且**交接窗口极窄**

本轮不是唯一在等的同步任务：

| 实例                       | 状态                                                          |
| -------------------------- | ------------------------------------------------------------- |
| 第 89 轮同步（`mvs_7794b3…`）| 17:15 起跑，**22:49:07 取到闸**，22:49 提交日志 `7666d8b2` 后释放 |
| 本轮（第 90 轮）           | 21:15 起跑，**22:52:10 取到闸**                              |
| 流水线 tick                | 全程持闸直到 22:52 前后释放                                   |

⇒ **两条同类同步实例 + 一条流水线 tick 三方在同一个闸上排队**。
本轮取闸时，第 89 轮**刚刚**释放（22:49 提交日志）——**交接窗口 ≈ 3 分钟**。
若按「先观测到 free 再 acquire」的写法，本轮大概率整段错过（协议已两次实测过零宽度窗口）。

⚠️ 附带取证：闸目录里**同时存在两个互不相让的竞态器**（本轮的 + 第 89 轮的），
外加一个 `guarded racer` —— 三者共用**同一个** `mkdirSync` 原子互斥，
**天然保证同一时刻只有一个能持闸**，因此**不存在两个同步实例同时改集成头**的风险。

### 1.5 本轮新发现 C：等闸期间 **HEAD 会漂**，取闸后必须重新取证

本轮取到闸（22:52:10）时，集成头 HEAD 已经不是起跑时的 `01e2ef06`，而是 `7666d8b2`：

```
7666d8b2 docs(sync): 第 89 轮 main→集成头同步 —— 冲突未并入（11 个…）＋ 等闸 5 小时 34 分取证
e90c962b docs(round-114): L2 终判红（catalog 跨钟域断言·负载型假红）+ 修掉泄漏的 keepalive 守护 ⇒ 恢复调度闸接管路径
01e2ef06 docs(round-114): 撞闸+PG闸命中+L2在飞 ⇒ 零派发 —— 接管 round 113 崩掉后的收尾三件套
```

⇒ **「等闸期间的只读取证结论不能直接用于合并决策」**：PRE 基线必须**取闸后现取**，
本轮 PRE = `7666d8b2ca4b91322b867432151eb1af0ee0991a`。
⇒ 这也侧面印证了第 88 轮记录的「闸并不互斥 land 落批提交」——**闸内的 HEAD 仍会被流水线写前进**。

## 二、main 侧：这一轮**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85–89 轮**同一颗** squash（单父提交，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main           ⇒ 6522c497
```

### 2.1 模块级口径（465 行台账逐模块 join，本轮独立复算）

| 台账版本                     | 已交付 | 进行中 | 未开发 |
| ---------------------------- | ------ | ------ | ------ |
| merge-base `6522c497`        | 340    | 0      | 125    |
| `origin/main` tip `823ebca5` | 406    | 0      | 59     |
| 集成头（合并时 `7666d8b2`）  | **417**| 0      | **48** |

- 该 squash 相对 merge-base 带入 **66 个**模块交付；
- **main 已交付 / 集成头未交付**（= 本次合并能带入的新交付）= **0 个**；
- **集成头已交付 / main 未开发**（= 合并会丢失的部分）= **11 个**：

| 模块 ID   | 名称              | main   | 集成头   |
| --------- | ----------------- | ------ | -------- |
| 1.1.2.1   | 核心指标卡        | 未开发 | 已交付   |
| 1.1.2.2   | 店铺概览          | 未开发 | 已交付   |
| 1.3.2.1   | 预警规则          | 未开发 | 已交付   |
| 1.3.2.2   | 外部渠道推送      | 未开发 | 已交付   |
| 1.5.2.1   | 服务与任务状态    | 未开发 | 已交付   |
| 13.1.2.1  | 目标设定          | 未开发 | 已交付   |
| 13.1.2.2  | 达成追踪          | 未开发 | 已交付   |
| 13.2.1.1  | 销量趋势          | 未开发 | 已交付   |
| 13.2.1.2  | 分时与分周模式    | 未开发 | 已交付   |
| 13.2.2.1  | 店铺与站点        | 未开发 | 已交付   |
| 13.2.2.2  | SKU/SPU/Listing   | 未开发 | 已交付   |

⇒ **本次合并不携带任何新交付，却要冒 11 个已交付模块被回退的风险** ——
**这是本任务连续第 11 轮（第 79–90 轮）撞同一堵墙**，唯一解仍是发布侧解 P0。

## 三、冲突面（11 个，与第 85–89 轮逐个一致）

`git merge --no-ff origin/main`（PRE=`7666d8b2`）⇒ **11 个冲突**：

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

⇒ 与第 85 轮 `fa26ddbd`、第 86 轮 `18f812e5`、第 87 轮 `eb7a585e`、第 88 轮 `5bfcc23c`、
第 89 轮 `7666d8b2` 的冲突面**逐个一致**（10 add/add + 1 content）。
根因仍是 `origin/main` 的 tip 是**集成头自身历史被 GitHub 压成的 squash**（见
`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`，P0 未解）：
merge-base 被拉回 `6522c497`（`chore: 同步集成分支最新进度`），该点之后集成头新增的文件
在 merge-base 上**根本不存在** ⇒ 两边都是「各自新增」⇒ add/add。

**本轮逐文件核验**（`git cat-file -e 6522c497:<path>`）：11 个冲突文件里
**10 个在 merge-base 上不存在**（⇒ add/add），**只有 `api-proxy.ts` 在 merge-base 上已存在**
（⇒ content），而 **11 个在 `origin/main` 上全部存在**。
⇒ add/add 与 content 的分布由此**机械可证**，与本轮 merge 打印的类型标签逐个吻合。

**未解任何冲突**（协议硬边界）：`git merge --abort` 已执行，`MERGE_HEAD` 已清，
HEAD 回到 `7666d8b2`，`git status --porcelain` 为空。

### 3.1 本轮新发现 D：取闸**之前**就能**只读**预演冲突面

本轮等闸期间用 `git merge-tree --write-tree --name-only integration/module-dev origin/main`
做了**只读**冲突预演（不碰工作区、不改引用）。它给出的冲突文件清单与**取闸后真实 merge**
**逐个一致**（11 个，同名同类型）。

⇒ **「是否要进合并流程」这个判断，可以在取闸前就做完** ——
好处是**闸内时间只花在真正要做的合并上**，不浪费在「一进去就发现冲突、又要 abort」的往返上。
⚠️ 前提是**只读**：`merge-tree` 不写工作区；`merge-tree` 会调用 `merge-union` 驱动
（打印「走了行级兜底并集，请人工确认这个文件改对了」与「台账按模块 ID 求并集，折叠了 465 行同 ID 记录」）
—— 这两条提示在真实 merge 里同样出现，**是已知现象，不是本轮新增的异常**。

## 四、为什么没 push / 没跑预检

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**：本地集成头领先 `origin/integration/module-dev` **61 个提交**、落后 0 个。
  本轮**没有产生任何可发布的合并**，发布是流水线的决定（第 85–89 轮同此口径）。
  ⚠️ 流水线 round 114 的 L2 终判为**红**（backend=1），按流水线禁令「L2 没绿绝对不 push」，
  发布侧此刻也**不该**推。

## 五、待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （已连续 **12 轮**：第 79–90 轮）。见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **协议待补（新增，来自 §1.3）**：`SYNC-MAIN.md` 第 1 步应写明
   **「持闸者只要还在续期，`stale` 就恒为 false，TTL 不是兜底路径」** ——
   否则后来者会误以为「等久一点就能抢到闸」，白白空等。
3. **协议待补（新增，来自 §1.4）**：应写明**同类同步实例会并发等同一个闸**，
   交接窗口实测可达 3 分钟、也曾实测 ≤ 9 秒 ⇒ 竞态是唯一可靠写法。
4. **协议待补（新增，来自 §1.5）**：应写明**等闸期间 HEAD 会被流水线/其它实例写前进**，
   **PRE 基线必须取闸后现取**，等闸前的只读取证不能直接当合并基线用。
5. **闸的覆盖范围（沿用第 88 轮待办 3，仍未解）**：闸要覆盖 land 落批提交，
   否则同步任务的合并基线在合并过程中可能被顶掉。
   另：`lane.mjs` 的 `gateRelease` 仍是无条件 `rmSync(GATE_DIR)`（未修，协议边界）。
6. 下一轮同步若 main 仍无新提交，预期仍是「冲突未并入」；**除非 P0 被解，否则本任务会一直空转**。

## 六、文件清单

- 新增：本文件
- 未改任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify、未改台账
