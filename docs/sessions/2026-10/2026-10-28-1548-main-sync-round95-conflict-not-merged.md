# 2026-10-28: 集成头同步 main 第 95 轮 —— 冲突未并入（26 个，与第 93/94 轮同墙）＋ 冲突面「全线性演进」的独立取证

> 本轮 15:15 起跑、**15:45:48 取到闸**、15:5x 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **26 个**（25 add/add + 1 content），与第 93/94 轮**逐个同名同类型**。
> main tip 仍是 `823ebca5`（同一颗 squash），**已连续 16 轮（第 79–95 轮）撞同一堵墙**。
>
> **本轮唯一的新结论**：26 个冲突**全部**是「线性演进」（main 侧 = 集成头历史上的旧快照），
> 逐个用 blob 身份独立取证 ⇒ **这堵墙 100% 可机械解开**。
> ⇒ P0 的根因不是「冲突解不开」，而是 **main 的 tip 是一颗内容空转的 squash 快照**。

## 摘要

| 项                     | 值                                                                          |
| ---------------------- | --------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（26 个冲突，25 add/add + 1 content）                          |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                          |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–94 轮同一颗 squash）     |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                  |
| 台账                   | **422 已交付 / 0 进行中 / 43 未开发**（465 行，`sync.mjs --check` ✅ 一致）  |
| 台账变化               | **无**（本轮 `sync.mjs` 重跑零 diff）                                       |
| 冲突                   | 26 个（见「三、冲突面」）                                                   |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                          |
| push                   | **未 push**（集成头 L2 是**红**的；且本轮无 merge 可发）                     |
| 闸                     | **已释放**                                                                  |
| 等闸时长               | **约 30 分钟**（15:16 → 15:45:48，密集竞态，未命中；闸由第 94 轮释放）    |

## 一、取闸：等 30 分钟，闸由第 94 轮**故意**在对手竞态循环退出后才放

15:15 首读 `gate status` ⇒ `held` / `ageSeconds=29` / `ttl=4200` / **`stale=false`**
⇒ 活体持闸，按协议**不抢、不清、不 force**，走**密集 `gate acquire` 竞态**（每 1 秒一次，硬截止 1800s）。

等闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log` / `git merge-base`），
**没有跑第二个构建**（流水线的 `mvnw verify` 与预检共用 `backend/**/target/` 与 PG 容器）。

### 1.1 活体证据

| 时点  | 观察                                                                 | 判定                    |
| ----- | -------------------------------------------------------------------- | ----------------------- |
| 15:15 | `owner` = pid `71877`，时间戳 `2026-10-28T07:14:46.198Z`（= 本地 15:14:46） | 第 94 轮 **TTL 接管**取得 |
| 15:16 | `ps` 取到 `watch-lanes.sh`（pid 22174，已跑 23 小时）常驻 watcher  | worktree 里有活体进程   |
| 15:19 | `ps` 取到**零** maven / java / pnpm / turbo 进程                     | 持闸会话在非构建相位     |
| 15:21 | `git status --porcelain` 为空                                        | 工作区干净，可待合并     |

> ⚠️ 与第 94 轮一致的判据：**闸龄单调上涨不能判死**，只有 `stale` 字段作数。

### 1.2 为什么最后是我拿到闸 —— 第 94 轮在**等我的竞态循环退出**

第 94 轮的会话语档（`2026-10-28-1515-main-sync-round94-...md` §1.4）已写明它检测到
**第二个并发 `gate acquire` 竞态循环**（PID 76640，15:15:23 起，硬截止 `now+1800s`），
并援引 backlog 里的 P0 闸泄漏向量：**并发竞态循环会在 release 瞬间把闸抢回来并持有到 TTL（4200s）**
⇒ 持闸方选择**等那个循环自然退出再 release**（代价：闸多占约 30 分钟；对比「泄 70 分钟挡住下一轮流水线」明显更小）。

本轮时间线正好对上：

```
15:16:0x  本轮竞态循环启动（deadline ≈ 15:46:0x）
15:45:2x  第 94 轮确认本轮循环已到硬截止、已退出 ⇒ release 闸
15:45:48  本轮复核 gate status = free
15:45:5x  本轮 gate acquire —— 第 1 次命中（ok:true）
```

⇒ 闸是**正常交接**的，**没有清闸、没有 force、没有闸泄漏**。
⇒ 复核：本轮取得闸后 `gate status` = `held` / `ageSeconds=6` / `stale=false`（owner = 本轮）。

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85–94 轮**同一颗** squash（**单父**，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main           ⇒ 6522c497
```

### 2.1 三条独立取证：main 就是集成头的一份旧快照（本轮复核）

1. **单父结构**：`git log -1 --format='parents=[%P]' origin/main` ⇒ `[6522c497]`（单父 ⇒ GitHub squash）。
2. **树逐字节相同**：main 的树 `83269e11` 在集成头**全部历史提交**里唯一命中 `1e3228b4`
   ⇒ main 的内容就是集成头历史上某个 commit 的快照。
3. **模块级 join**（465 行台账逐模块按 ID + 状态 join，本轮重算）：

| 台账版本                     | 已交付  | 进行中 | 未开发 |
| ---------------------------- | ------- | ------ | ------ |
| `origin/main` tip `823ebca5` | 406     | 0      | 59     |
| 集成头 `eed3d57d`            | **422** | 0      | **43** |

- **main 领先（main 已交付、集成头未交付）= 0 个** ⇒ **本次合并不携带任何新交付**；
- **集成头领先 = 16 个**（与第 94 轮同数、同集合）。

⇒ **合并不带新交付，却要冒 16 个已交付模块被回退的风险** ⇒ 本轮同样**不并入**。

## 三、冲突面（26 个 = 第 93/94 轮逐个同名同类型）

`git merge --no-ff origin/main`（PRE=`eed3d57d`）⇒ **26 个冲突**，
类型按 `git ls-files -u` 的 stage 数判定（2 stage = add/add，3 stage = content）：
**25 个 add/add + 1 个 content**，文件名与第 93/94 轮**逐个相同**。

| 冲突类型 | 数量 | 文件 |
| -------- | ---- | ---- |
| add/add  | 25   | `apps/web/src/components/dashboard/workbench-card-registry.ts`、`apps/web/src/components/dashboard/workbench-layout-model.test.ts`、`backend/services/channel/src/test/java/com/starcon/channel/{DataGovernanceIntegrationTest,DataGovernanceTenancyIntegrationTest,FilingCalendarIntegrationTest,FilingCalendarTenancyIntegrationTest,GlobalSearchGroupIntegrationTest,GlobalSearchIntegrationTest,LinkageRiskIdentityStub,LinkageRiskIntegrationTest,LinkageRiskTenancyIntegrationTest,SalesTaxIntegrationTest,SalesTaxTenancyIntegrationTest,SyncHealthIntegrationTest,SyncHealthTenancyIntegrationTest,VatIossIntegrationTest,VatIossTenancyIntegrationTest}.java`、`backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java`、`backend/services/identity/src/main/java/com/starcon/identity/{NotificationInboxController,NotificationPreferenceService}.java`、`backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java`、`backend/services/reporting/src/main/java/com/starcon/reporting/{ReportingFinancePort,ReportingUpstreamClient}.java`、`backend/services/trade/src/test/java/com/starcon/trade/{AnomalyOrderIntegrationTest,AnomalyOrderTenancyIntegrationTest}.java` |
| content  | 1    | `apps/web/src/lib/api-proxy.ts` |

### 3.1 【本轮新结论】26 个冲突**全部**是线性演进 —— 逐个用 blob 身份取证

`tools/module-progress/merge-resolve.mjs` 对本轮判据给出 `[取集成分支侧]` × 26、
`[并入（人工）]` **0** 个、退出码 0。**但该工具的 base 查找在 add/add 文件上打了**
`fatal: path '<file>' exists on disk, but not in '6522c497…'`
⇒ 它的「content 冲突」措辞与「共同祖先里有这个文件」的理由**自相矛盾**，不能直接采信。

本轮改用**不依赖该工具**的独立取证：对每个冲突文件取 stage 3（main 侧）的 blob，
在 `integration/module-dev` 的**完整历史**里逐提交比对该路径的 blob：

```
git rev-list integration/module-dev -- <file>   # 逐提交
git rev-parse <commit>:<file>                    # 该提交下该路径的 blob
```

**结果：26 / 26 全部命中** —— main 侧的 blob 在集成头历史里**原样存在**（如
`workbench-card-registry.ts` @ `57b48b69`、`api-proxy.ts` @ `84179e67`、
`SalesTaxIntegrationTest.java` @ `e4df6ce6`、`NotificationInboxController.java` @ `3aba3bc5`）。

⇒ 判定：**main 侧 = 集成头历史上的旧快照，集成头侧 = 演进的终点**
⇒ **26 个冲突全部是线性演进，逐个「取集成分支侧」都是无损的**，**没有一个是真分叉**。

> 这解释了 add/add 的成因：merge-base 被 squash 钉在 `6522c497`，
> 该点之后集成头新增的文件在 base 上**根本不存在** ⇒ 两侧都是「各自新增」⇒ git 报 add/add，
> 但两侧内容其实**同源**（main 的那一版是集成头旧快照）。
>
> ⇒ **P0 的修法不是「学会解这堵墙」**（它本来就 100% 可机械解开），
> **而是让 `origin/main` 的 tip 变成一次正常 merge（双父、基于集成头最新 tip）**。
> 只要 main 的 tip 还是 squash 快照，每一轮同步都会在同一个 base 上重建同一堵墙
> （已连续 **16 轮**：第 79–95 轮）。

### 3.2 未解任何冲突（协议硬边界）

`git merge --abort` 已执行，**`MERGE_HEAD` 已清**，HEAD 回到 `eed3d57d`，
`git status --porcelain` 为空（0 个改动文件），`git diff --diff-filter=U` 为空。

## 四、为什么没跑预检 / 没 push

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**，三条独立理由，任一条都足够：
  1. **本轮没有产生任何可发布的合并** —— 冲突即中止，没有 merge commit；
  2. **集成头的 L2 是红的**（本轮复核 `.tmp/l2-batch107b.status` ⇒ `backend_verify=1`，
     14:42:16 `identity` 服务 `Tests run: 639, Failures: 34, BUILD FAILURE`，
     失败类含 `AlertRuleIntegrationTest` / `ApprovalInboxIntegrationTest` /
     `ApprovalInboxTenancyIntegrationTest` / `ApprovalTodoIntegrationTest` /
     `DepartmentLeaderIntegrationTest` / `LedgerBookIntegrationTest` /
     `OperationLogIntegrationTest` / `PlatformFlagIntegrationTest`）
     ⇒ 按 `PIPELINE.md` 硬规定「**L2 没绿绝对不 push**」不能推；
  3. `SYNC-MAIN.md` 开篇写明「**两者不一致时以 PIPELINE.md 为准**」，而 PIPELINE.md 的 push 条件
     （工作区干净 + L2 绿）比 `SYNC-MAIN.md` 第 5 步的「预检绿就 push」更严 ⇒ 本轮按 PIPELINE.md 口径不推。
- 本地集成头领先 `origin/integration/module-dev` **101** 个提交、落后 0 个。

## 五、后续待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙（已连续 **16 轮**：第 79–95 轮）。
   见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **【本轮新增】P0 结论的加强**：这堵墙**不是**「解不开」，而是「解开了也没价值」——
   26 个冲突全线性演进（§3.1 已取证），但 main 侧**零新交付**（§2.1）。
   ⇒ 下一轮起**不必再为「怎么解这 26 个」投入**；应把力气放在让 main 的 tip 变成正常 merge 上。
3. **P0（流水线侧，未变）**：batch 107 的 L2 **红点至今没人二分定位** ——
   `identity` 34 个失败例（失败类见 §4）。下一轮流水线应从 `identity` 服务复跑并按
   「最多 2 次全量 `bisect`」定位。
4. **待办（来自第 94 轮 §1.3）**：持闸会话崩在闸里 ⇒ 闸泄漏到 TTL 到期才被接管。
   建议 `lane.mjs` 的闸龄判据改成「**最新 keepalive 文件**的 mtime」，并让 `gate release` 只删自己持有的闸。
5. **待办（来自第 94 轮 §1.4，本轮再次实证）**：并发 `gate acquire` 竞态循环会让闸被抢回并持有到 TTL。
   本轮与第 94 轮**双向确认**了这条：第 94 轮等本轮循环退出后才 release，本轮随即正常取得。
   ⇒ 根治应在**调度侧**保证同一时刻只有一个 main 同步竞态循环。
6. **协议待补**：`SYNC-MAIN.md` 第 1 步应写明**「闸龄骤降 = 续期/交接，不是死」**，
   以及**「持闸会话崩在闸里 ⇒ TTL 到期接管」**这条路径（见第 94 轮 §1.1/§1.3）。
7. **协议待补（本轮新增）**：`SYNC-MAIN.md` 冲突段可补一条判据 ——
   **「add/add 不等于真分叉」**：先查 main 侧 blob 是否在集成头历史中原样存在，
   命中即线性演进、可机械取集成分支侧。这条比 `merge-resolve.mjs` 的措辞可靠
   （本轮实测该工具在 add/add 上的 base 查找会 fatal，其「content 冲突」理由不成立）。

## 六、涉及文件

- 新增：本文件
- 未新增任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify、未跑预检
- 闸：15:45:48 取到 → 收尾时释放

## 七、关联

- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源
- `docs/sessions/2026-10/2026-10-28-1515-main-sync-round94-conflict-not-merged.md` —— 上一轮（同墙）
- `docs/sessions/2026-10/2026-10-28-1127-main-sync-round93-conflict-not-merged.md` —— 上上轮（同墙）
- `tools/module-progress/SYNC-MAIN.md` · `tools/module-progress/PIPELINE.md`
