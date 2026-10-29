# 2026-10-28: 集成头同步 main 第 96 轮 —— 冲突未并入（26 个，同墙第 17 轮）＋ 远端集成头首次分叉取证

> 本轮 17:15 起跑、**17:16:32 取到闸**（TTL 到期接管）、17:3x 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **26 个**（25 add/add + 1 content），与第 93/94/95 轮**逐个同名同类型**。
> main tip 仍是 `823ebca5`（同一颗 squash），**已连续 17 轮（第 79–96 轮）撞同一堵墙**。
>
> **本轮唯一的新结论**：`origin/integration/module-dev` **首次与本地集成头分叉**
> （远端多 1 个提交、本地多 102 个）⇒ `git push` 已被拒（non-fast-forward）。

## 摘要

| 项                     | 值                                                                          |
| ---------------------- | --------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（26 个冲突，25 add/add + 1 content）                          |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                          |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–95 轮同一颗 squash）     |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                  |
| 台账                   | **422 已交付 / 0 进行中 / 43 未开发**（465 行，本轮 `sync.mjs` 重跑**零 diff**） |
| 台账变化               | **无**                                                                      |
| 冲突                   | 26 个（见「三、冲突面」）                                                   |
| 预检                   | **绿**（`./mvnw -q compile` rc=0；`pnpm typecheck` 3/3 成功）—— 但见 §4 说明 |
| push                   | **未 push**（三条独立理由，任一条都足够，见 §5）                             |
| 闸                     | **已释放**                                                                  |
| 等闸时长               | **约 1.5 分钟**（17:15 → 17:16:32，TTL 边界接管）                            |

## 一、取闸：TTL 边界接管（比前几轮快得多）

首读 `gate status` ⇒ `held` / `ageSeconds=4115` / `ttl=4200` / **`stale=false`**
⇒ 按协议**不抢、不清、不 force**，走**密集 `gate acquire` 竞态**（每 1 秒一次，硬截止 900s）。

闸在 **17:16:31** 跨过 TTL 边界，竞态循环在 **17:16:32** 命中（`ok:true`）—— **1 秒之差**。

### 1.1 持闸者已崩的四重证据

| 证据                                   | 取值                                                       |
| -------------------------------------- | ---------------------------------------------------------- |
| `owner` 文件                           | pid `20822` / `2026-10-28T08:03:26.284Z`（= 16:03:26）   |
| `ps -p 20822`                          | **no such process**                                       |
| 全仓构建进程（mvn/java/pnpm/turbo/vite）| **零**                                                     |
| 闸目录 keepalive 文件                   | `keepalive-160343` / `-160558` / `-160631` ⇒ **最后一次 touch 16:06:31**，之后停更 |

> ⚠️ 与第 94/95 轮一致：**`owner` 里的 PID 不是闸存活证据**（`gate acquire` 是短命进程）。
> 判活只能用 `stale` 字段；活性取证要看闸外（`ps`）。本轮两者都指向「持闸会话已崩」。

### 1.2 闸龄判据实测有效（第 94 轮遗留待办 #4 已落地）

本轮 `ageSeconds` 与闸龄的换算落点是 **keepalive 文件的 mtime（16:06:31）**，而不是 `owner` 的写入时间（16:03:26）
⇒ `ageSeconds` 从 keepalive 停止那一刻起算 ⇒ **16:06:31 + 4200s = 17:16:31** ⇒ 与我 17:16:32 的接管时刻**精确对齐**。

⇒ 第 94 轮 §五.4 提的「闸龄判据改成最新 keepalive 文件 mtime」**已在 `lane.mjs` 里生效**，本轮**实证通过**。

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85–95 轮**同一颗** squash（**单父**，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main           ⇒ 6522c497
```

### 2.1 三条独立取证：main 就是集成头的一份旧快照（本轮复核）

1. **单父结构**：`git log -1 --format='parents=[%P]' origin/main` ⇒ `[6522c497]`（单父 ⇒ GitHub squash）。
2. **模块级 join**（465 行台账逐模块按 ID + 状态 join，本轮重算）：

| 台账版本                     | 已交付  | 进行中 | 未开发 |
| ---------------------------- | ------- | ------ | ------ |
| `origin/main` tip `823ebca5` | 406     | 0      | 59     |
| 集成头 `c5842fca`            | **422** | 0      | **43** |

- **main 领先（main 已交付、集成头未交付）= 0 个** ⇒ **本次合并不携带任何新交付**；
- **集成头领先 = 16 个**（与第 93–95 轮同数、同集合：`1.1.2.1` `1.1.2.2` `1.3.2.1` `1.3.2.2` `1.5.2.1`
  `13.1.2.1` `13.1.2.2` `13.2.1.1` `13.2.1.2` `13.2.2.1` `13.2.2.2` `13.3.1.1` `13.3.1.2` `13.3.2.1` `13.3.2.2` `14.2.1.2`）。

⇒ **合并不带新交付，却要冒 16 个已交付模块被回退的风险** ⇒ 本轮同样**不并入**。

## 三、冲突面（26 个 = 第 93/94/95 轮逐个同名同类型）

`git merge --no-ff origin/main`（PRE=`c5842fca`）⇒ **26 个冲突**，
类型按 `git merge-tree --write-tree` 的 `CONFLICT (...)` 判定：
**25 个 add/add + 1 个 content**，文件名与第 93/94/95 轮**逐个相同**（与 `.tmp/conflicts-r94.txt` 逐行一致）。

| 冲突类型 | 数量 | 文件                                                                                                                                                                                                                                                                                      |
| -------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| add/add  | 25   | `apps/web/src/components/dashboard/workbench-card-registry.ts`、`apps/web/src/components/dashboard/workbench-layout-model.test.ts`、`backend/services/channel/src/test/java/com/starcon/channel/{DataGovernanceIntegrationTest,DataGovernanceTenancyIntegrationTest,FilingCalendarIntegrationTest,FilingCalendarTenancyIntegrationTest,GlobalSearchGroupIntegrationTest,GlobalSearchIntegrationTest,LinkageRiskIdentityStub,LinkageRiskIntegrationTest,LinkageRiskTenancyIntegrationTest,SalesTaxIntegrationTest,SalesTaxTenancyIntegrationTest,SyncHealthIntegrationTest,SyncHealthTenancyIntegrationTest,VatIossIntegrationTest,VatIossTenancyIntegrationTest}.java`、`backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java`、`backend/services/identity/src/main/java/com/starcon/identity/{NotificationInboxController,NotificationPreferenceService}.java`、`backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java`、`backend/services/reporting/src/main/java/com/starcon/reporting/{ReportingFinancePort,ReportingUpstreamClient}.java`、`backend/services/trade/src/test/java/com/starcon/trade/{AnomalyOrderIntegrationTest,AnomalyOrderTenancyIntegrationTest}.java` |
| content  | 1    | `apps/web/src/lib/api-proxy.ts`                                                                                                                                                                                                                                                         |

### 3.1 冲突**全部**是线性演进 —— 逐个用 blob 身份独立取证

对每个冲突文件取 stage 3（main 侧）的 blob，在 `integration/module-dev` 的**完整历史**里逐提交比对该路径的 blob：

```
git rev-list integration/module-dev -- <file>   # 逐提交
git rev-parse <commit>:<file>                    # 该提交下该路径的 blob
```

**结果：26 / 26 全部命中** ⇒ main 侧的 blob 在集成头历史里**原样存在**
⇒ 判定：**main 侧 = 集成头历史上的旧快照，集成头侧 = 演进的终点**
⇒ **26 个冲突全部是线性演进，逐个「取集成分支侧」都是无损的**，**没有一个是真分叉**。

> 这解释了 add/add 的成因：merge-base 被 squash 钉在 `6522c497`，
> 该点之后集成头新增的文件在 base 上**根本不存在** ⇒ 两侧都是「各自新增」⇒ git 报 add/add，
> 但两侧内容其实**同源**（main 的那一版是集成头旧快照）。
>
> ⇒ **P0 的修法不是「学会解这堵墙」**（它本来就 100% 可机械解开），
> **而是让 `origin/main` 的 tip 变成一次正常 merge（双父、基于集成头最新 tip）**。

### 3.2 未解任何冲突（协议硬边界）

`git merge --abort` 已执行，**`MERGE_HEAD` 已清**，HEAD 回到 `c5842fca`，
`git status --porcelain` 为空（0 个改动文件），`git diff --diff-filter=U` 为空。

## 四、预检：绿，但**检的不是合并结果**

本轮按「冲突即中止」推进（协议第 3 步），**没有 merge commit 可检**；
但为了给流水线留基线信号，仍在**未合并**的 HEAD（`c5842fca`）上跑了快速预检：

| 检查                     | 结果                                    |
| ------------------------ | --------------------------------------- |
| `cd backend && ./mvnw -q compile` | **rc=0**（仅 JDK/Maven 无关的 WARNING） |
| `pnpm typecheck`         | **3/3 successful**（3m9s，2 个缓存命中） |

> ⚠️ **口径提醒**：这两个绿只说明**当前未合并的集成头**编译与类型是干净的，
> **不代表「合并后」也是绿的** —— 本轮没有合并发生，所以它们**不能**当作合并结果的预检结论。
> 真正的合并结果预检要等 main 的 tip 变成正常 merge、这堵墙被拆掉之后才有意义。

## 五、为什么没 push（三条独立理由，任一条都足够）

1. **本轮没有产生任何可发布的合并** —— 冲突即中止，没有 merge commit。
2. **集成头的 L2 是红的** —— `.tmp/l2-batch108.status` ⇒ `backend_verify=1`
   （16:05:58 起、16:35:10 止；`identity` 服务 `Tests run: 639, Failures: 38, BUILD FAILURE`，
   失败类含 `ApprovalTodoIntegrationTest` / `ApprovalInboxTenancyIntegrationTest` /
   `ApprovalRecordTenancyIntegrationTest` / `ShortcutTemplateIntegrationTest` /
   `GlobalSearchRecentIntegrationTest` / `ApprovalRecordIntegrationTest` /
   `ApprovalInboxIntegrationTest` / `DepartmentLeaderIntegrationTest` / `AlertRuleIntegrationTest`）。
   `.tmp/l2-batch107b.status` 同样是 `backend_verify=1`。
   ⇒ 按 `PIPELINE.md` 硬规定「**L2 没绿绝对不 push**」不能推；
   而 `SYNC-MAIN.md` 开篇写明「**两者不一致时以 PIPELINE.md 为准**」。
3. **push 会被直接拒**（本轮新增，见 §6）—— `git push --dry-run` ⇒
   `! [rejected] integration/module-dev -> integration/module-dev (non-fast-forward)`。

本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。

## 六、【本轮新增】远端集成头首次与本地分叉 ⇒ push 已被拒

`git fetch origin --prune` 时远端**动了**：

```
e6f443ae..94192b24  integration/module-dev -> origin/integration/module-dev
```

### 6.1 四个取证

| #   | 证据                                                              | 取值                                                                                   |
| --- | ----------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| 1   | `origin/integration/module-dev` 的 tip                            | `94192b24`（作者 Mavis，**2026-10-28 16:46:06**，仅 1 个文件、198 行新增）              |
| 2   | 它的父提交                                                       | `e6f443ae`（**10-26 21:45** 的旧 tip）⇒ **远端是从旧基线上长出来的**                    |
| 3   | `e6f443ae` 是不是本地 HEAD 的祖先                                | **是** ⇒ 两边在 `e6f443ae` 分叉，**不是**「远端领先」而是「双向分叉」                    |
| 4   | 分叉规模                                                         | `git rev-list --left-right --count origin/integration/module-dev...HEAD` ⇒ **`1  102`** |

### 6.2 远端那 1 个提交是什么

`94192b24` = `docs(sessions/2026-10/2026-10-28-1600-pipeline-round107-state-recovery-and-dispatch.md`
—— 流水线第 107 轮的会话语档（该轮自述「本地集成头落后上游 742 个提交 ⇒ 快进到 `e6f443ae`」），
**纯文档，零业务代码**。

### 6.3 后果与可解性

- **后果**：`git push origin integration/module-dev` 被拒（non-fast-forward）
  —— 因为远端那 1 个提交不在本地 102 个提交里。
- **可解性（已实测）**：`git merge-tree --write-tree HEAD origin/integration/module-dev` ⇒ **rc=0、无冲突**
  ⇒ 远端那 1 个提交与本地 102 个提交**内容上完全可调和**（本地根本没有那个文件名）。
- **本轮为何不动它**：本任务只搬运 `origin/main` 的 commit；把远端集成头合回本地属于**发布侧**动作，
  且会改写集成头提交图，**须在 L2 绿的窗口由流水线统一做**，不在本任务的职责内。
  ⇒ 已写进 `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md`。

## 七、后续待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙（已连续 **17 轮**：第 79–96 轮）。
   见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **【本轮新增】P0（发布侧）**：远端集成头与本地**首次双向分叉**（远端 +1 / 本地 +102），
   ⇒ push 被 non-fast-forward 拒。已实测可无冲突调和，但**必须等 L2 绿**再统一处理。
   见 `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md`。
3. **P0（流水线侧，未变）**：batch 107b / 108 的 L2 **连续红**，
   `identity` 38 个失败例（失败类见 §5）。下一轮流水线应从 `identity` 服务复跑并按
   「最多 2 次全量 `bisect`」定位。
4. **待办（来自第 94 轮 §1.3）**：持闸会话崩在闸里 ⇒ 闸泄漏到 TTL 到期才被接管。
   **本轮再次实证**（§1.1）⇒ 建议 `lane.mjs` 让 `gate release` 只删自己持有的闸。
5. **待办（来自第 94 轮 §1.4）**：并发 `gate acquire` 竞态循环会让闸被抢回并持有到 TTL。
   本轮仍走密集竞态（每 1 秒），**未触发**（1.5 分钟内命中）；根治仍应在调度侧保证同一时刻只有一个竞态循环。
6. **协议待补**：`SYNC-MAIN.md` 冲突段可补一条判据 ——
   **「add/add 不等于真分叉」**：先查 main 侧 blob 是否在集成头历史中原样存在，
   命中即线性演进、可机械取集成分支侧。这条比 `merge-resolve.mjs` 的措辞可靠
   （第 95 轮实测该工具在 add/add 上的 base 查找会 fatal，其「content 冲突」理由不成立）。
7. **协议待补（本轮新增）**：`SYNC-MAIN.md` 第 1 步可补「**闸龄从最新 keepalive mtime 起算**」
   （第 94 轮遗留待办 #4 已在 `lane.mjs` 落地，本轮 17:16:31 边界接管实证通过）。

## 八、涉及文件

- 新增：本文件
- 新增：`docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md`
- 未新增任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify
- 闸：17:16:32 取到 → 收尾时释放

## 九、关联

- `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md` —— 本轮新增
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源
- `docs/sessions/2026-10/2026-10-28-1548-main-sync-round95-conflict-not-merged.md` —— 上一轮（同墙）
- `docs/sessions/2026-10/2026-10-28-1515-main-sync-round94-conflict-not-merged.md` —— 上上轮（同墙）
- `tools/module-progress/SYNC-MAIN.md` · `tools/module-progress/PIPELINE.md`
