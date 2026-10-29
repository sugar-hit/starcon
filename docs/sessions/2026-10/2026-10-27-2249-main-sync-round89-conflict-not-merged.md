# 2026-10-27: 集成头同步 main 第 89 轮 —— 冲突未并入（11 个，与第 85/86/87/88 轮逐个一致）＋ 等闸 5 小时 34 分取证

> 本轮 17:15 起跑、**22:49:07 取到闸**、22:5x 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **11 个**（10 add/add + 1 content），与第 85 轮 `fa26ddbd`、第 86 轮 `18f812e5`、
> 第 87 轮 `eb7a585e`、第 88 轮 `5bfcc23c` **逐个一致**。
> 本轮新增取证集中在**取闸这一侧**（等闸 5 小时 34 分）：闸的续期机制、持闸会话崩溃后的
> 孤儿 keepalive、`stale` 假阳性的实际形态、以及**另一个同类同步实例并发等闸**。

## 摘要

| 项                     | 值                                                                        |
| ---------------------- | ------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（11 个冲突，10 add/add + 1 content）                        |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                         |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–88 轮同一颗 squash）    |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                  |
| 台账                   | **417 已交付 / 0 进行中 / 48 未开发**（465 行，`sync.mjs --check` ✅ 一致） |
| 台账变化               | **无**（未并入任何内容；写版 `sync.mjs` 无需跑，`--check` 已一致）          |
| 冲突                   | 11 个（见下「冲突面」）                                                     |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                          |
| push                   | **未 push**（本地领先远端 60 个提交，发布是流水线的决定）                    |
| 闸                     | **已释放**                                                                 |
| 等闸时长               | **5 小时 34 分**（17:15 → 22:49:07）                                       |

## 一、取闸：等了 5 小时 34 分（本轮主体时间花在闸上）

### 1.1 闸的持有者：一条**活着的**流水线 tick

17:15 `gate status` ⇒ `held` / `stale=false` / `ageSeconds=117`，owner 文件为
`20138 / 2026-10-27T08:00:52.914Z`（即 16:00:52 取得）。

⚠️ **owner PID 不是存活证据**（协议原文）：`gate acquire` 是短命进程，写完 `owner` 就退出。

按协议走**竞态 `gate acquire`**（`mkdirSync` 原子互斥；持闸且未过期时失败且**无任何副作用**），
1 秒间隔密集重试，**不做低频 `status` 观测**。等闸全程**只做只读取证**：
`gate status` / `cat owner` / `ps` / `git log` / `git status`；
**连 `git fetch` 都没跑**，**没有跑第二个构建**（流水线的 `mvnw verify` 与预检共用
`backend/**/target/` 与 PG 容器，并发必假红）。

### 1.2 闸外活性取证（持闸者确实活着，判定依据来自闸外）

| 时刻    | 闸外证据                                                                  |
| ------- | ------------------------------------------------------------------------- |
| 16:00   | `git reflog`：流水线 land `13.1.2.2 达成追踪` / `13.2.2.1 店铺与站点`     |
| 16:25   | `git reflog`：fix-trade-clock-domain 落地 + `merge`（HEAD → `6445d5de`）   |
| 16:03– | 车道 `13.2.1.2 分时与分周模式` 在跑，`lanes.json` 心跳每 25 分钟续期一次     |
| 17:46   | `ps`：**车道 worktree 里 `pnpm typecheck` 在跑**（pid 51440，turbo typecheck）|
| 18:23   | `ps`：**全量 L2 `mvnw -fae verify` 在飞**（pid 62019 → 后继 72285）        |
| 20:28   | `ps`：L2 跑到 `services/channel` 的 surefire                               |
| 21:07   | `git log`：流水线写下 `01e2ef06 docs(round-114): …`                        |
| 21:40–  | 闸目录内 `keepalive-r114-N-<epoch>` 持续续期                                |
| 22:49   | 持闸者**释放**（`gate release` ⇒ 闸目录被删）⇒ 本轮 `acquire` 命中          |

⇒ **闸是被活着的流水线 tick 持有的**，按协议**等它释放**，不抢、不清、不 force。

### 1.3 本轮新发现 A：闸的续期靠**闸目录 mtime**，而 mtime 会漏

`lane.mjs` 的闸龄判据是 `statSync(GATE_DIR).mtimeMs`；续期手段是**往闸目录里写
`keepalive-*` 文件**（写文件会刷新目录 mtime）。本轮实测到两种此前没记录过的形态：

1. **守护进程会死**：16:03 起的旧守护进程（pid 21165，命令形如
   `for i in $(seq 1 45); do touch "$GATE/keepalive-r114-$i-$(date +%s)"; sleep 120; done`）
   在 **21:19 前后退出**；21:19 由 round 114 的会话另起了一个**有界**守护（同样 45 次 ×120s）。
   ⇒ **keepalive 的续期者是 agent 会话派生的进程，不是流水线本体。**
2. **守护进程会卡**：实测两次 **15–21 分钟**的续期空窗（如 17:47 → 21:19 之间只有
   `keepalive-r114-5` 一枚）。**⇒ 「每 2 分钟续期一次」是名义值，不是保证值。**

后果：`ageSeconds` 会在一段时间里持续增长（实测峰值 1013s、1270s），
**离 TTL(4200s) 还很远但确实在涨** —— 这正是 `stale` 假阳性的土壤。

### 1.4 本轮新发现 B：`stale=true` 的假阳性本轮**实际成立**，故本轮拒绝接管

仓库里已有该判据的已知缺陷登记：`docs/backlog/2026-10-23-gate-stale-false-positive-live-tick-heartbeat-gap.md`
（第 52 轮事故：同步任务按协议合法接管 → 持闸 tick 随后 `reset` 抹掉了同步任务的 merge 态，
并在同一 worktree 里起了 L2 ⇒ **互斥失效**）。

本轮据此**没有**用裸 `gate acquire`（它在 `age > TTL` 时会无条件接管），
而是把竞态脚本改成**带存活判据的竞态**：

- `gate` 为 `free` ⇒ 立刻 `acquire`（正常释放路径，**不吃零宽度交接窗口**）；
- `gate` 为 `held` 且 `stale=false` ⇒ 继续密集竞态；
- `gate` 为 `held` 且 `stale=true` ⇒ **先查闸外存活证据**（`lanes.json` 里是否有
  `state=running` 且心跳 < 45 分钟的车道）⇒ **仍判活则不接管**，继续等；只有判死才接管。

⇒ 本轮**从未**走 TTL 接管路径，**从未清闸**，**从未 force**。

### 1.5 本轮新发现 C：持闸会话崩溃 ⇒ 闸被孤儿 keepalive 继续占住

流水线 round 114 的会话日志（`docs/sessions/2026-10/2026-10-27-2100-pipeline-round114-l2-inflight-gate-held.md`）
自陈：**round 113 的 agent 会话在 20:47:55 崩掉**（`net::ERR_CONNECTION_CLOSED`），
它的 **L2 / watcher / keepalive 三件套作为脱离会话的进程继续活着** ——
**L2 跑完没人写说明、没人 commit、没人 push**，闸也一直被占着。
round 114 接管的是这段尾巴（写说明 + commit + push），**但它自己也「撞闸，不取闸、不派发」**。

⇒ **闸的持有者不是「一次 tick」，而是「一次 tick + 它派生的一串脱离会话的进程」**；
会话崩了，闸仍被占、L2 仍在飞。这解释了本轮为什么能等满 5 小时 34 分。

### 1.6 本轮新发现 D：同一个 cron 任务的**第二个实例**在并发等闸

21:20 前后出现另一条同任务实例的进程链（父进程是同一个 MCode helper 95174）：
`/tmp/starcon_sync_racer.sh`（pid 21713，PPID=1）+ 一个监视
`/tmp/starcon_sync_racer.log` 的循环（pid 22526），日志文件为空 ⇒ **它也一直在等**。
本轮取闸时它已退出（pid 21713 gone，日志仍 0 字节 ⇒ 它**从未**取得闸）。

⇒ **两个实例各自持有竞态脚本时，谁的 `mkdirSync` 先成功谁取到闸**；
本轮是本实例在 22:49:07 命中。**本轮未对另一个实例做任何干预**（不 kill、不清它的日志）。

## 二、main 侧：这一轮**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85–88 轮**同一颗** squash（单父提交，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main           ⇒ 6522c497
```

### 2.1 模块级口径（两份台账逐模块 join，本轮独立复算）

| 台账版本                     | 已交付 | 进行中 | 未开发 |
| ---------------------------- | ------ | ------ | ------ |
| `origin/main` tip `823ebca5` | 406    | 0      | 59     |
| 集成头（合并时 `e90c962b`）  | **417**| 0      | **48** |

- 状态不同的模块共 **11 个**，**方向全部是「main 未开发 / 集成头已交付」**：

| 模块 ID   | 名称            | main     | 集成头   |
| --------- | --------------- | -------- | -------- |
| 1.1.2.1   | 核心指标卡      | 未开发   | 已交付   |
| 1.1.2.2   | 店铺概览        | 未开发   | 已交付   |
| 1.3.2.1   | 预警规则        | 未开发   | 已交付   |
| 1.3.2.2   | 外部渠道推送    | 未开发   | 已交付   |
| 1.5.2.1   | 服务与任务状态  | 未开发   | 已交付   |
| 13.1.2.1  | 目标设定        | 未开发   | 已交付   |
| 13.1.2.2  | 达成追踪        | 未开发   | 已交付   |
| 13.2.1.1  | 销量趋势        | 未开发   | 已交付   |
| 13.2.1.2  | 分时与分周模式  | 未开发   | 已交付   |
| 13.2.2.1  | 店铺与站点      | 未开发   | 已交付   |
| 13.2.2.2  | SKU/SPU/Listing | 未开发   | 已交付   |

⇒ **main 领先（main 已交付、集成头未交付）的模块数 = 0** ⇒ **本次合并不携带任何新交付**。

## 三、冲突面（11 个，与第 85/86/87/88 轮逐个一致）

`git merge --no-ff origin/main`（PRE=`e90c962b`）⇒ **11 个冲突**：

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

根因（与第 85–88 轮同一）：`origin/main` 的 tip 是**集成头自身历史被 GitHub 压成的 squash**，
merge-base 被拉回 `6522c497`，batch-28 之后集成头交付的模块在 merge-base 上**根本不存在**
⇒ 两边都是「各自新增」⇒ add/add。**本轮实测验证**：

```
git cat-file -e 6522c497:apps/web/src/components/dashboard/workbench-card-registry.ts  ⇒ ABSENT
git cat-file -e 6522c497:backend/services/identity/.../NotificationInboxController.java ⇒ ABSENT
git cat-file -e 6522c497:backend/services/trade/.../AnomalyOrderIntegrationTest.java   ⇒ ABSENT
git cat-file -e 6522c497:apps/web/src/lib/api-proxy.ts                                  ⇒ PRESENT  ⇒ 唯一的 content 冲突
```

**未解任何冲突**（协议硬边界）：`git merge --abort` 已执行，`MERGE_HEAD` 已清，
工作区回到 `e90c962b` 且 `git status --porcelain` 为空。

### 3.1 附带取证：`merge-union` 驱动在本轮的实际行为

合并时 `merge-union` 驱动接手了**契约与台账两类文件**：

- `docs/product/capability-blueprint/module-progress.md` ⇒ **台账按模块 ID 求并集，折叠了 465 行同 ID 记录**
  （驱动自报「走了行级兜底并集，请人工确认这个文件改对了」）；
- `packages/api-contracts/src/{channel,identity,reporting}.{openapi.json,ts}` ⇒ 自动合并，无冲突。

⇒ **11 个冲突里没有一个是契约/台账类文件** ⇒ 驱动在本轮**够不着**，冲突全部落在业务代码/测试上。

## 四、为什么没跑预检 / 没 push

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**：本地集成头领先 `origin/integration/module-dev` **60 个提交**、落后 0 个；
  本轮**没有产生任何可发布的合并**，发布是流水线的决定（第 85/86/87/88 轮同此口径）。
- **台账**：`sync.mjs --check` ⇒ ✅ 台账与蓝图一致 ⇒ 写版 `sync.mjs` 是空操作，未跑。

## 五、待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （已连续 **11 轮**：第 79–89 轮）。见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **闸机制待修（本轮新增证据）**：
   - `gate status` 的 `stale` 判据基于**闸目录 mtime**，而 mtime 靠 keepalive 文件刷新；
     **keepalive 守护是 agent 会话派生的进程**（会话崩 ⇒ 守护成孤儿继续占闸，见 1.5 节）。
   - 实测 keepalive 存在 **15–21 分钟**续期空窗（1.3 节）⇒ `ageSeconds` 会真实地往 TTL 涨。
   - ⇒ 建议把判据改成「**最新 keepalive 文件的时间戳**」而非目录 mtime（backlog 已给方案 2），
     并让 keepalive 守护**不由会话派生**（由流水线本体托管）。
3. **同步任务的竞态脚本应带存活判据（本轮已本地实现，未改仓库文件）**：
   `stale=true` 时先查闸外存活证据（运行中车道的心跳），判活则不接管。
   这条值得写进 `SYNC-MAIN.md` 第 1 步 —— 现在协议只说「按 `stale` 字段判」，而该字段**会假阳性**。
4. **多实例并发**：同一 cron 任务存在**第二个实例**同时等同一把闸（1.6 节）。
   取到闸的那个实例干完活后，另一个实例会**再跑一遍同样的合并**。
   建议加「同一时间窗只允许一个同步实例」的编排去重。
5. 下一轮同步若 main 仍无新提交，预期仍是「冲突未并入」；**除非 P0 被解，否则本任务会一直空转**。

## 六、文件清单

- 新增：本文件
- 未改任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify、未改台账
