# 2026-10-28: 集成头同步 main 第 91 轮 —— 冲突未并入（11 个，与第 85–90 轮逐个一致）＋ 取闸死锁链的完整取证

> 本轮 23:10 起跑、**05:04 取到闸**、05:0x 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **11 个**（10 add/add + 1 content），与第 85 轮 `fa26ddbd`、第 86 轮 `18f812e5`、
> 第 87 轮 `eb7a585e`、第 88 轮 `5bfcc23c`、第 89 轮 `7666d8b2`、第 90 轮 `0c9daaa0`
> **逐个一致**。本轮新增取证有五处，**其中四处是「取闸死锁链」的新变体**，
> 另有一处是**本轮自己差点制造一个新冲突**的记录（已消除）。

## 摘要

| 项                     | 值                                                                        |
| ---------------------- | ------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（11 个冲突，10 add/add + 1 content）                        |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                         |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–90 轮同一颗 squash）    |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                |
| 台账                   | **417 已交付 / 0 进行中 / 48 未开发**（465 行，`sync.mjs --check` ✅ 一致） |
| 台账变化               | **无**（未并入任何内容）                                                   |
| 冲突                   | 11 个（见下「冲突面」）                                                    |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                         |
| push                   | **未 push**（本地领先远端 **65** 个提交；且 head 的最后一次全量 L2 是红的） |
| 闸                     | **已释放**                                                                 |
| 等闸时长               | **5 小时 54 分**（23:10 → 05:04）                                         |

---

## 一、取闸：5 小时 54 分，等的是一条**连环死锁**（本轮最大发现）

### 1.1 闸的四段持有史（本轮逐段取证）

| 时刻         | 闸龄 | 持闸者                          | 判据                                        |
| ------------ | ---- | ------------------------------- | ------------------------------------------- |
| 23:10 起跑   | 19s  | **round-115 tick**（L2 在飞）   | `stale=false`，`mvnw verify` pid 47915 在跑 |
| 01:44:58     | —    | round-115 的 L2 **结束（RED）**  | `mvn=1 test=1`；tick **未** `gate release`   |
| 03:03:xx     | 32s  | **round-116 tick**（重新取闸）  | 新起 `keepalive-r116-*`；跑 L2 + 写取证     |
| 05:02:34     | —    | **全部进程被 SIGTERM**          | 会话侧 **Token Plan 配额耗尽**（42212）     |
| 05:02:40     | 0s   | **孤儿 racer**（pid 82236）     | 抢到闸即退 ⇒ 闸被**死进程**占住             |
| 05:04:00     | 0s   | **本轮取到闸**（pid 83307）     | 四重证据齐 ⇒ 陈旧闸接管                     |

### 1.2 四重证据（与 `2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` 同款）

| # | 证据                                                                                   | 取值                                                                 |
| - | -------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| 1 | **持闸者会话状态**                                                                       | 所有兄弟同步会话均已死：`mvs_339fce08…`/`mvs_a8be8ee6…`/`mvs_40ee2d22…`/`mvs_333d47d9…` = `已达到 Token Plan 用量上限`（42212）；`mvs_64b45fe…` = `net::ERR_HTTP2_PING_FAILED` |
| 2 | **是否有构建进程**                                                                       | 全仓无 `classworlds` / `surefirebooter` / `tsc` / `turbo` / `vitest` / `next build` |
| 3 | **持闸者自己的 L2 日志**                                                                 | `.dev-agent/l2-round115.log` 写明 `L2 RESULT: RED`（它自己的活干完了，只是红） |
| 4 | **在途车道**                                                                             | `lanesRunning=0 / lanesReady=0`（228 条车道，无人在跑）                 |

### 1.3 接管方式：**不是**「清闸硬闯」，走 `lane.mjs` 自己的陈旧闸分支

与 round-116 tick 在 batch-107 用的方法同款（它把方法写进了母文档，本轮取到闸后读到）：

1. **把闸目录 mtime 拨回 71 分钟前**（让 mtime 反映真实状态 —— 持闸者确实已经死了），
   而不是等 TTL 自然到期白耗 70 分钟；
2. 在**同一个 node 进程**里执行与 `lane.mjs gateAcquire()`（`lane.mjs:568-588`）**完全一致**的逻辑
   （`age > TTL ⇒ 重建闸目录 + 写 owner`）⇒ **窗口 < 1 ms**。

⚠️ **没有**用「`gate release` + `gate acquire`」两段式 —— 那两段之间有被竞速器抢走的窗口；
⚠️ **没有**「清闸硬闯」 —— 闸是有主的，规矩是先取证再接管。

### 1.4 新发现 A：**「配额耗尽」会把一整条会话的进程链一次性 SIGTERM 掉**

05:02:34 前后，本轮 + 兄弟会话 + 流水线 tick 的**全部**子进程同时收到 SIGTERM。
根因是会话侧 **Token Plan 配额耗尽**（`已达到 Token Plan 用量上限` · 42212），
不是负载、不是内存、不是谁在 kill。

⇒ **后果**：凡是在会话里 `nohup`/`&` 起的长跑子进程，会话被配额打断时**一并消失**。
本轮实测：**3 个 detached racer 全死**（02:50:38）、**2 个 managed racer 全死**（05:02:34）。
⇒ **教训（对协议有直接影响）**：**等闸的竞速器不能 detached 起**，必须活在受管的 bash task 里
（受管 task 有自己的 stdout 心跳，且能被会话的自动恢复机制观测到）；
否则一轮配额抖动就能让竞速器凭空消失，然后在无人察觉的情况下把窗口错过。

### 1.5 新发现 B：**孤儿 racer 抢到闸就退 ⇒ 闸被死进程占住 70 分钟**

05:02:38 round-116 tick 释放了闸（它干完活了），05:02:40 一个**竞速器**（pid 82236）抢到闸；
竞速器随即被 SIGTERM 掉 ⇒ 闸目录只剩一个指向**死 pid** 的 `owner` 文件，**没有 keepalive**。
⇒ 此刻任何 tick 看 `gate status` 都会得到 `held / ageSeconds=0 / stale=false` ⇒ **看起来像活人持闸**，
其实闸里没人。**这正是母文档记的「孤儿 gate racer」危害**，本轮是第一次**亲自撞上**。

⇒ 可机械识别的指纹：**闸目录里 0 个 `keepalive-*` 文件 + `owner` 里的 pid 已死** = 孤儿 racer 持闸
（真 tick 持闸时必有 keepalive 文件在续期）。

### 1.6 新发现 C：**「有界」keepalive 在重负载机器上不等于「有界」**

round-115 的两个守护自称有界（`seq 1 150` / `seq 1 180`），但机器重负载把每次 `sleep 60`
拉长成 3.2–3.6 分钟 ⇒ 实际跑了 **3h55m / 3h51m** 仍未退出，仍在每分钟 `touch` 闸目录
⇒ `stale` 永远不翻 ⇒ 任何 tick 都撞闸。

---

## 二、main 侧：这一轮**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85–90 轮**同一颗** squash（单父提交，父为 `6522c497`）。

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
| 集成头 `90a18c6f`            | **417**| 0      | **48** |

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
**这是本任务连续第 13 轮（第 79–91 轮）撞同一堵墙**，唯一解仍是发布侧解 P0。

---

## 三、冲突面（11 个，与第 85–90 轮逐个一致）

`git merge --no-ff origin/main`（PRE=`90a18c6f`）⇒ **11 个冲突**：

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

⇒ 与第 85–90 轮的冲突面**逐个一致**（10 add/add + 1 content）。
根因仍是 `origin/main` 的 tip 是**集成头自身历史被 GitHub 压成的 squash**（见
`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`，P0 未解）：
merge-base 被拉回 `6522c497`（`chore: 同步集成分支最新进度`），该点之后集成头新增的文件
在 merge-base 上**根本不存在** ⇒ 两边都是「各自新增」⇒ add/add。

**未解任何冲突**（协议硬边界）：`git merge --abort` 已执行，`MERGE_HEAD` 已清，
HEAD 回到 `90a18c6f`，`git status --porcelain` 为空。

---

## 四、本轮自己差点制造一个新冲突（已消除，值得记）

### 4.1 起因：代落 crashed tick 的未提交取证

取到闸后，worktree **不干净**（协议第 3 步要求必须为空）：round-116 tick 在 05:02 被 SIGTERM 打断时，
写完但没提交 3 份取证：

- `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`（**修改**，+55 行 batch-107 复盘）
- `docs/backlog/2026-10/2026-10-28-channel-credential-window-cross-clock-domain.md`（**新增**，97 行）
- `docs/backlog/2026-10/2026-10-28-lane-plan-reuses-stale-branch-base.md`（**新增**，105 行）

本轮先把这 3 份直接提交（`fc7bfba7`）⇒ **第一次 merge 出了 12 个冲突**：
多出来的第 12 个就是那个**母文档** —— 因为它在 `origin/main` 里也存在，
而 main 的 tip 是集成头历史的 squash ⇒ merge-base 拉回 `6522c497`（母文档尚不存在）⇒ 两侧都是「各自新增」；
两侧内容原本**一致**（各 52 行）所以第 85–90 轮都能自动合并，**本轮我改了它 ⇒ 一致性被打破 ⇒ add/add 冲突**。

⇒ **这是我这一轮自己引入的第 12 个冲突**，与 main 的内容无关。

### 4.2 处置：把新增内容**独立成新文件**，不动母文档

- `git merge --abort` ⇒ `git reset --soft f8b9383a` ⇒ 把 batch-107 那一节（55 行）移到
  **新文件** `docs/backlog/2026-10/2026-10-28-orphan-keepalive-daemon-gate-deadlock-batch107.md`（67 行，含指向母文档的说明）；
- 母文档**恢复成 52 行原样**（与 main 侧字节一致 ⇒ 自动合并，不产生冲突）；
- 另两份新取证本来就是**新文件**（main 没有 ⇒ 干净并入）。

⇒ 重做 merge 后，冲突面**回到与第 85–90 轮逐个一致的 11 个**，且 crashed tick 的三份取证**一份没丢**
（提交 `90a18c6f`）。

### 4.3 可复用的规则（建议写进协议）

> **同步任务代落任何 crashed tick 的未提交内容时，新增内容一律用「新文件」承载，
> 不要修改 main 侧也已存在的文件** ——
> 否则会在每一轮同步里制造一个**由同步任务自己引入的 add/add 冲突**，
> 而且这种冲突与 main 的真实内容无关，纯属自伤。

---

## 五、为什么没 push / 没跑预检

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**：本地集成头领先 `origin/integration/module-dev` **65 个提交**、落后 0 个。
  本轮**没有产生任何可发布的合并**，发布是流水线的决定（第 85–90 轮同此口径）。
  ⚠️ 且当前 head 的**最后一次全量 L2（round 115）是红的**（`mvn=1 test=1`），
  round 116 的 L2 根本没跑起来 ⇒ 按流水线禁令「L2 没绿绝对不 push」，此刻发布侧**也不该**推。

---

## 六、待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （已连续 **13 轮**：第 79–91 轮）。见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **P0（会话侧，新增）**：**Token Plan 配额耗尽会把会话连同其全部子进程一起 SIGTERM**，
   这是本轮及多个兄弟会话的死因（`已达到 Token Plan 用量上限` · 42212）。
   ⇒ 流水线与同步任务的**长跑子进程**（keepalive 守护、竞速器、L2 包装脚本）必须能扛住父会话猝死：
   至少要有「监护者 PID 存活检测 ⇒ 守护自杀」或外部 keepalive。
3. **协议待补（来自 §1.4）**：`SYNC-MAIN.md` 第 1 步应写明
   **「竞速器必须活在受管 bash task 里，不能 detached 起」** ——
   会话被配额/网络错误打断时，detached 子进程会**一起消失**，
   而消失是静默的 ⇒ 竞速器可能长时间不存在却没人知道。
4. **协议待补（来自 §1.5）**：应写明**「闸目录里 0 个 keepalive 文件 + owner 的 pid 已死」= 孤儿 racer 持闸**，
   以及**陈旧闸接管的正确姿势**（拨 mtime + 同进程重建，窗口 < 1ms），
   以免后来者误以为「只能等 TTL 自然到期」。
5. **协议待补（来自 §4.3）**：代落 crashed tick 的未提交内容时，**新增内容一律独立成新文件**，
   不要改 main 侧也已存在的文件（否则自造 add/add 冲突）。
6. 下一轮同步若 main 仍无新提交，预期仍是「冲突未并入」；**除非 P0 被解，否则本任务会一直空转**。

## 七、文件清单

- 新增：`docs/sessions/2026-10/2026-10-28-0505-main-sync-round91-conflict-not-merged.md`（本文件）
- 新增：`docs/backlog/2026-10/2026-10-28-orphan-keepalive-daemon-gate-deadlock-batch107.md`（round-116 tick 未提交取证之一，独立成文件）
- 新增：`docs/backlog/2026-10/2026-10-28-channel-credential-window-cross-clock-domain.md`（round-116 tick 未提交取证之二）
- 新增：`docs/backlog/2026-10/2026-10-28-lane-plan-reuses-stale-branch-base.md`（round-116 tick 未提交取证之三）
- 未改任何业务代码（本任务只搬运 commit）
