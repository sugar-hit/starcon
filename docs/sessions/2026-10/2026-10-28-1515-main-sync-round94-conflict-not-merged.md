# 2026-10-28: 集成头同步 main 第 94 轮 —— 冲突未并入（26 个，与第 93 轮同墙）＋ 孤儿闸 TTL 接管取证

> 本轮 12:15 起跑、**15:14:46 取到闸**（TTL 接管）、15:16 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **26 个**（25 add/add + 1 content），与第 93 轮**逐个同名同类型**。
> main tip 仍是 `823ebca5`（同一颗 squash），**已连续 15 轮（第 79–94 轮）撞同一堵墙**。

## 摘要

| 项                     | 值                                                                          |
| ---------------------- | --------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（26 个冲突，25 add/add + 1 content）                         |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                          |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–93 轮同一颗 squash）     |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                  |
| 台账                   | **422 已交付 / 0 进行中 / 43 未开发**（465 行，`sync.mjs --check` ✅ 一致） |
| 台账变化               | **无**（本轮 `sync.mjs` 重跑零 diff —— 上一轮 round-121 已把 summary 修好） |
| 冲突                   | 26 个（见「三、冲突面」）                                                   |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                          |
| push                   | **未 push**（本地领先远端 **100** 个提交；且集成头 L2 是**红**的）          |
| 闸                     | **已释放**                                                                  |
| 等闸时长               | **约 3 小时**（12:15 → 15:14:46，TTL 接管）                                 |

## 一、取闸：等了 3 小时，根因是**持闸会话中途崩在闸里**

首读 `gate status` ⇒ `held` / `ageSeconds=778` / `ttl=4200` / **`stale=false`**。
按协议第 1 步只能等，不能抢、不能清、不能 force ⇒ 走**密集 `gate acquire` 竞态**（每 1 秒一次）。

等闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log` / `git merge-tree` 预演），
**没有跑第二个构建**（流水线的 `mvnw verify` 与预检共用 `backend/**/target/` 与 PG 容器）。

### 1.1 持闸者是活体，不是残骸 —— 证据随时间翻转

| 时段             | 闸目录 mtime（= 最新 keepalive） | `ageSeconds` | 判定                   |
| ---------------- | -------------------------------- | ------------ | ---------------------- |
| 12:15 首读       | `keepalive-120210`（12:02:10）   | 778          | 活体，等               |
| 12:46            | `keepalive-124630`               | **17**       | 闸龄骤降 = 续期，活体  |
| 12:57 车道落地后 | `keepalive-125722` / `-125740`   | 0            | 合并前 + L2 前各续一次 |
| 13:23:52         | `keepalive-132352`               | —            | 仍在续                 |
| 14:04:45         | `keepalive-140445`               | —            | 仍在续                 |
| 14:04:45 之后    | **再无新 keepalive**             | 3690 → 4180  | 续期停了               |

⇒ 闸龄从 2541s **骤降到 17s**（第 93 轮确立的判据：`ageSeconds` 骤降 + `owner` 变更 = 交接，
单靠 `ageSeconds` 单调上涨不能判死），靠这个才确认持闸者活着。

### 1.2 活体 tick 做了什么（只读观察，未干预）

- **12:57:27** 两条车道并入集成头：`1555038b merge(13.3.1.2): 生命周期与分层`、
  `495f4412 merge(13.3.2.2): 关联营销效果`；
- **12:57:44** 起跑本批 L2（`mvnw verify` + typecheck + lint + test + Next build）；
- 首次 L2 进程树**被杀**（`.tmp/l2-batch107.log` 只有 117 字节的 START 头，无结果）；
- tick 于是改用「脱离式 runner」重跑（`.tmp/run-l2-batch107.sh`，规避 harness 墙钟上限）——
  第一次 `setsid` 在 macOS 不存在，14:05:48 重试成功；
- **14:42:16** 重跑的 L2 **判红**：`identity` 服务 surefire 失败 ⇒ `backend_verify=1`
  ⇒ 后续前端步骤被 runner 主动跳过。

⇒ **集成头当前的 L2 判据是「红」**，这一点决定了本轮**不可能也不应该 push**（见「五」）。

### 1.3 持闸会话最终崩在闸里 ⇒ TTL 到期接管

14:04:45 之后闸再没被续过；本轮 `ps` 取证：**零** maven / surefire / java / `git bisect` 进程、
**零**带 `org.testcontainers.sessionId` 标签的 PG 容器、唯一相关的只有一条跑了 22 小时的
`.tmp/watch-lanes.sh` 常驻 watcher。

对端会话 `mvs_a54d73019c8b45efb060a6ed75481540`（cron「starcon 并行车道功能开发流水线」）
状态从 `started`（`net::ERR_HTTP2_PING_FAILED`）变成 **`error`**（`net::ERR_CONNECTION_CLOSED`，
errorCode 50113），`updatedAt` 停在 14:08 前后 ⇒ **会话在中途断开，闸泄漏在集成头里**。

⇒ **15:14:45**（最后 keepalive 14:04:45 + TTL 4200s）闸龄越过 TTL，`stale` 翻 `true`，
本轮竞态循环第 **567** 次命中，`gate acquire` 走 TTL 接管分支成功（owner pid `71877`，
`ageSeconds=4`、`stale=false`），**不是抢、不是清、不是 force**。

> ⚠️ `lane.mjs` 的 `gateAcquire` 与 `gate status` 用的是**同一套阈值与同一个锚点**
> （`GATE_DIR` 的 mtime，`GATE_TTL_SECONDS=4200`）⇒ 竞态循环拿到的时刻与 `stale` 翻真的时刻**精确一致**，
> 不存在「我按自己的 ageSeconds 猜」的偏差。

### 1.4 收尾时撞上**并发竞态循环** —— 本轮为此多等了约 30 分钟才敢释放闸

本轮 15:14:46 取到闸后，`ps` 取到**第二个 `gate acquire` 竞态循环**（PID `76640`，
15:15:23 起，硬截止 `now+1800s`，与本轮**不是同一个实例**）⇒ 是一个**并行的 main 同步轮次**在等闸。

⚠️ 这正是 backlog 里记过的 **P0 闸泄漏向量**：

> 「禁止并发开多个 `gate acquire` 竞态循环 —— 否则 release 瞬间闸被抢回来。
> 只要本轮 `gate release`，第二个循环会在同一秒把闸抢回来并持有到 TTL（4200s），
> 而它的持闸进程随即退出 ⇒ 等价于**闸泄漏 70 分钟**，白挡下一轮流水线。」

⇒ 本轮的处置：**不抢、不杀、不清**，而是**等那个循环自己走到硬截止自然退出**（约 15:45:23），
确认**没有任何存活的竞态循环**之后才 `gate release`。
代价是闸多被占约 30 分钟；对比「泄 70 分钟且挡住下一轮流水线」，这个代价明显更小。

> ⚠️ 取证小坑：`ps -p <pid> -o pid,etime` 在进程**确实活着**时也可能返回空输出且退出码为 0
> （本轮实测误报过一次「peer loop GONE」）⇒ 判活必须用 `kill -0 <pid>`
> （或 `ps -p <pid> -o etime=` 并**核对非空**），不能用「输出为空」当死亡证据。

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85–93 轮**同一颗** squash（**单父**，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main           ⇒ 6522c497
```

### 2.1 三条独立取证：main 就是集成头的一份旧快照

1. **单父结构**：`git log -1 --format='parents=[%P]' origin/main` ⇒ `[6522c497]`（单父 ⇒ GitHub squash）。
2. **树逐字节相同**：main 的树 `83269e11` 在集成头**全部历史提交**里唯一命中
   `1e3228b4` ⇒ main 的内容就是集成头历史上某个 commit 的快照。
3. **模块级 join**（465 行台账逐模块按 ID + 状态 join）：

| 台账版本                     | 已交付  | 进行中 | 未开发 |
| ---------------------------- | ------- | ------ | ------ |
| `origin/main` tip `823ebca5` | 406     | 0      | 59     |
| 集成头 `7c93310b`            | **422** | 0      | **43** |

- **main 领先（main 已交付、集成头未交付）= 0 个** ⇒ **本次合并不携带任何新交付**；
- **集成头领先 = 16 个**（比第 93 轮的 14 个多 2 个，正是本轮新落地的两条车道）：

  | 模块 ID  | 名称            | main   | 集成头 |
  | -------- | --------------- | ------ | ------ |
  | 1.1.2.1  | 核心指标卡      | 未开发 | 已交付 |
  | 1.1.2.2  | 店铺概览        | 未开发 | 已交付 |
  | 1.3.2.1  | 预警规则        | 未开发 | 已交付 |
  | 1.3.2.2  | 外部渠道推送    | 未开发 | 已交付 |
  | 1.5.2.1  | 服务与任务状态  | 未开发 | 已交付 |
  | 13.1.2.1 | 目标设定        | 未开发 | 已交付 |
  | 13.1.2.2 | 达成追踪        | 未开发 | 已交付 |
  | 13.2.1.1 | 销量趋势        | 未开发 | 已交付 |
  | 13.2.1.2 | 分时与分周模式  | 未开发 | 已交付 |
  | 13.2.2.1 | 店铺与站点      | 未开发 | 已交付 |
  | 13.2.2.2 | SKU/SPU/Listing | 未开发 | 已交付 |
  | 13.3.1.1 | 产品表现分析    | 未开发 | 已交付 |
  | 13.3.1.2 | 生命周期与分层  | 未开发 | 已交付 |
  | 13.3.2.1 | 流量转化        | 未开发 | 已交付 |
  | 13.3.2.2 | 关联营销效果    | 未开发 | 已交付 |
  | 14.2.1.2 | 分时策略        | 未开发 | 已交付 |

⇒ **合并不带新交付，却要冒 16 个已交付模块被回退的风险** ⇒ 本轮同样**不并入**。

### 2.2 squash 里到底装了什么

以 merge-base `6522c497` 为基线做同一套 join：台账从 **340 已交付** 变成 **406 已交付**
⇒ 这颗 squash 把 **66 个 L4 模块的交付**压成了一颗提交。集成头早已把这 66 个全部交付完，
**还多交付了 16 个** ⇒ 这次合并在内容上是**空转**。

## 三、冲突面（26 个 = 第 93 轮逐个同名同类型）

`git merge --no-ff origin/main`（PRE=`7c93310b`）⇒ **26 个冲突**，
类型按 `git ls-files -u` 的 stage 数判定（2 stage = add/add，3 stage = content）：

| 冲突类型 | 数量 | 文件                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| -------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| add/add  | 25   | `apps/web/src/components/dashboard/workbench-card-registry.ts`、`apps/web/src/components/dashboard/workbench-layout-model.test.ts`、`backend/services/channel/src/test/java/com/starcon/channel/{DataGovernanceIntegrationTest,DataGovernanceTenancyIntegrationTest,FilingCalendarIntegrationTest,FilingCalendarTenancyIntegrationTest,GlobalSearchGroupIntegrationTest,GlobalSearchIntegrationTest,LinkageRiskIdentityStub,LinkageRiskIntegrationTest,LinkageRiskTenancyIntegrationTest,SalesTaxIntegrationTest,SalesTaxTenancyIntegrationTest,SyncHealthIntegrationTest,SyncHealthTenancyIntegrationTest,VatIossIntegrationTest,VatIossTenancyIntegrationTest}.java`、`backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java`、`backend/services/identity/src/main/java/com/starcon/identity/{NotificationInboxController,NotificationPreferenceService}.java`、`backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java`、`backend/services/reporting/src/main/java/com/starcon/reporting/{ReportingFinancePort,ReportingUpstreamClient}.java`、`backend/services/trade/src/test/java/com/starcon/trade/{AnomalyOrderIntegrationTest,AnomalyOrderTenancyIntegrationTest}.java` |
| content  | 1    | `apps/web/src/lib/api-proxy.ts`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |

**冲突面与第 93 轮完全一致**（26 个，同名同类型）⇒ 这堵墙是**结构性的**，不是偶发。

### 3.1 content 冲突那一项：main 侧是**严格子集**

`api-proxy.ts` 的 `services[]` 条目数：main **241** 条 vs 集成头 **257** 条；
把两边的 `prefix: '...'` 去重后做集合差，**main 没有任何一个 prefix 是集成头没有的**
⇒ main 侧是**严格子集**，取 main 侧等于回退 16 条路由。

### 3.2 add/add 的成因

merge-base 被 squash 钉在 `6522c497`，该点之后集成头新增的文件在 merge-base 上**根本不存在**
⇒ 两侧都是「各自新增」⇒ add/add。**集成头每多交付一条车道的测试文件，冲突面就多几个** ——
本轮新落地的两条车道带进来的新文件与 main 的旧快照不在同一条路径上，故冲突面**没有再变宽**。

**未解任何冲突**（协议硬边界）：`git merge --abort` 已执行，**`MERGE_HEAD` 已清**，
HEAD 回到 `7c93310b`，`git status --porcelain` 为空（0 个改动文件）。

## 四、为什么没跑预检 / 没 push

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**，三条独立理由，任一条都足够：
  1. **本轮没有产生任何可发布的合并** —— 冲突即中止，没有 merge commit；
  2. **集成头的 L2 是红的**（14:42:16，batch 107 重跑判红，`identity` surefire 失败）⇒
     按 `PIPELINE.md` 硬规定「**L2 没绿绝对不 push**」不能推；
  3. `SYNC-MAIN.md` 开篇写明「**两者不一致时以 PIPELINE.md 为准**」，而 PIPELINE.md 的 push 条件
     （`PIPELINE.md:824` 「push 前必须确认工作区干净、L2 是绿的」、`:875` 「L2 没绿绝对不 push」）
     比 `SYNC-MAIN.md` 第 5 步的「预检绿就 push」更严 ⇒ 本轮按 PIPELINE.md 口径不推。
- 本地集成头领先 `origin/integration/module-dev` **100** 个提交、落后 0 个；
  远端最后一次成功 push 是 2026-10-26 21:45，已积压约 **40 小时**（round 121 已点名同一风险）。

## 五、后续待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （已连续 **15 轮**：第 79–94 轮）。见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **P0（流水线侧）**：12:00 tick 的会话在 L2 判红**之后**崩在闸里（`ERR_CONNECTION_CLOSED`），
   闸泄漏 ~70 分钟，直到 TTL 到期才被接管。**这次 L2 的红点（`identity` surefire 失败）至今没人二分定位**。
   下一轮流水线应从 `identity` 服务复跑并按「最多 2 次全量 `bisect`」定位。
3. **待办（来自 §1.3）**：持闸会话崩在闸里 ⇒ 闸泄漏到 TTL 到期才释放。
   建议 `lane.mjs` 的闸龄判据改成「**最新 keepalive 文件**的 mtime」（本仓已用该口径做人工取证），
   并让 `gate release` 只删自己持有的闸。
4. **待办（来自 §3）**：冲突面**随集成头前进而变宽**（11 → 26）。P0 不解，冲突面只会继续涨。
5. **协议待补**：`SYNC-MAIN.md` 第 1 步应写明**「闸龄骤降 = 续期/交接，不是死」**
   （本轮 2541s → 17s 的实测），以及**「持闸会话崩在闸里 ⇒ TTL 到期接管」**这条路径。
6. **待办（来自 §1.4）**：`gate release` 之前必须确认**没有存活的竞态循环**（用 `kill -0` 判活），
   否则 release 瞬间会被抢回去 ⇒ 闸泄漏 70 分钟。本轮已等对方循环自然退出后才释放。
   根治仍应在**调度侧**保证同一时刻只有一个 main 同步竞态循环。

## 六、涉及文件

- 新增：本文件
- 未新增任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify、未跑预检
- 闸：15:14:46 取到（TTL 接管）→ 收尾时释放

## 七、关联

- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源
- `docs/sessions/2026-10/2026-10-28-1127-main-sync-round93-conflict-not-merged.md` —— 上一轮（同墙）
- `docs/sessions/2026-10/2026-10-28-1300-pipeline-round121-gate-held-live-peer.md` —— 本轮等闸时的对端视角
- `tools/module-progress/SYNC-MAIN.md` · `tools/module-progress/PIPELINE.md`
