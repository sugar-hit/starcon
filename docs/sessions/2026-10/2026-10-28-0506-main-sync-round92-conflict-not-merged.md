# 2026-10-28: 集成头同步 main 第 92 轮 —— 冲突未并入（11 个，在**第二个 PRE 基线**上独立复现）＋ 等闸 3 小时 40 分的完整取证链

> 本轮 01:26:02 起跑、**05:06:23 取到闸**、05:06:35 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **11 个**（10 add/add + 1 content），与第 85–91 轮**逐个一致**。
> 本轮与第 91 轮**不是重复劳动**：第 91 轮在 PRE=`f8b9383a` 上并过一次，
> 本轮在 PRE=`d166853e`（比它再前进两个提交）上**独立再并一次**，
> 冲突集仍然逐个一致 ⇒ **冲突集与 PRE 基线无关**，是 merge-base 被拉回 `6522c497` 的结构性后果。

## 摘要

| 项                     | 值                                                                        |
| ---------------------- | ------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（11 个冲突，10 add/add + 1 content）                        |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                         |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–91 轮同一颗 squash）    |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                |
| 台账                   | **417 已交付 / 0 进行中 / 48 未开发**（465 行，`sync.mjs --check` ✅ 一致） |
| 台账变化               | **无**（未并入任何内容）                                                   |
| 冲突                   | 11 个（见下「冲突面」）                                                    |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                         |
| push                   | **未 push**（本地领先远端 **66** 个提交；且 head 的最后一次全量 L2 是红的） |
| 闸                     | **已释放**（05:06:35）                                                    |
| 等闸时长               | **3 小时 40 分**（01:26:02 → 05:06:23）                                   |

## 一、取闸：3 小时 40 分，等的是一条**四段接力**的闸

### 1.1 闸的四段持有史（与第 91 轮的取证链对齐）

| 时刻      | 持闸者                | 闸的状态                                    |
| --------- | --------------------- | ------------------------------------------- |
| 23:02:59  | **round-115 tick**    | `owner` pid 47540；L2 `mvnw verify` 在飞    |
| 02:01:27  | round-115 **会话崩**  | `net::ERR_HTTP2_PING_FAILED`（闸成孤儿）      |
| 03:03:27  | **round-116 tick**    | 释放 r115 孤儿闸后**重新取闸**；跑 channel L2 |
| 05:02:40  | **round-117 tick**    | 取闸但**没有起 keepalive**                    |
| 05:05:11  | **同类同步实例（第 91 轮）** | 取闸 → 并入尝试 → 提交日志 → 05:06 释放 |
| 05:06:23  | **本轮**              | 取闸 → 并入尝试 → 05:06:35 释放             |

### 1.2 本轮新增取证 A：r115 的 L2 终判是**红**，红在 `channel` 与前端

`origin/main` 同步与否与它无关，但它是本轮闸持有链的起点，故记在这里：

- STEP 1/6 `mvnw verify`（backend 全量，23:04:51 → 01:28:11，**2h23m**）⇒ **rc=1**，
  红在 `services/channel`：**2383 例中 11 例失败**
  （`OAuthAuthorizationIntegrationTest` 10 例 —— 其中
  `confirmRequiresAtLeastOneShop` 期望 400 实得 409、
  `confirmingTwiceReturnsTheSameConnectionInsteadOfDuplicating` 期望 200 实得 409；
  外加 `IpRiskLibraryIntegrationTest.lifecycleRules` 的「停用时刻写回不得偏 8 小时」）；
- STEP 4/6 `pnpm test`（前端全量）⇒ **rc=1**：`finance-software-allowlist.test.ts` 1 例超时，
  另有 2 个 vitest pool worker 启动超时 ⇒ 属负载型假红形态；
- STEP 2/6 typecheck、3/6 lint、5/6 Next build、6/6 `sync --check` ⇒ **rc=0 全绿**；
- 终判：`mvn=1 typecheck=0 lint=0 test=1 build=0 sync=0` ⇒ **RED**，01:44:58 收尾。

⚠️ r115 会话在 **02:01:27** 因 `net::ERR_HTTP2_PING_FAILED` 崩掉 —— 那正是机器从休眠唤醒的时刻
（01:45 → 02:01 期间 `keepalive-r115*`、`lane-watch.log`、L2 日志全部同时停写，唤醒后一并恢复）。
⇒ **闸的持有者是被网络/休眠打断的，不是正常收尾**，它永远不会再 `gate release`。

### 1.3 本轮新增取证 B：有界 keepalive 让 `stale` 在 2 小时里恒为 `false`

r115 的两个有界续期脚本（`r115` 150 次 × 60s、`r115b` 180 次 × 60s）在会话崩掉后**仍在跑**，
每 60s 往闸目录写一个新文件 ⇒ 目录 mtime 持续刷新 ⇒ `ageSeconds` 一直被压回两位数
⇒ `stale` 恒为 `false` ⇒ **「等 TTL 过期再接管」在这 2 小时里根本不是可用的兜底**。
（与第 91 轮 §1.6 同一机制，此处是本轮独立复现。）

⇒ 本轮全程**只做只读取证**：`gate status` / `cat owner` / `ps` / `git log` / `git status` /
`git merge-tree --write-tree`（只读冲突预演）；
**连 `git fetch` 都没跑**，**没有跑第二个构建**（流水线的 `mvnw verify` 与预检共用
`backend/**/target/` 与 PG 容器，并发必假红）。

### 1.4 本轮新增取证 C：闸目录里**只有 owner、没有 keepalive**，是「孤儿闸」的机械指纹

05:02:40 取闸者（owner pid 82236）**没有起 keepalive** ⇒ 闸目录里只有 `owner` 一个文件
⇒ 目录 mtime 冻结在 05:02 ⇒ `stale` 本应在 06:12 才翻。
本轮在 05:06:23 抢到闸（owner pid 88012），**走的是 05:05:11 那一轮正常释放出来的窗口**，
不是 TTL 硬闯 —— 交接窗口只有 **~70 秒**，本轮抓到。

⇒ 可机械识别的指纹（与第 91 轮 §1.5 一致）：**闸目录里 0 个 `keepalive-*` 文件
+ `owner` 里的 pid 已死 = 孤儿 racer 持闸**，而**真 tick 持闸时必有 keepalive 在续期**。

### 1.5 本轮新增取证 D：并发同步实例会**互相接力**完成同一轮协议

05:05:11 另一条同类同步实例（第 91 轮）取闸，独立完成了「并入尝试 → 提交两份文档 → 释放闸」，
并在 05:06:10 提交了 `d166853e`（第 91 轮会话日志）。
本轮 05:06:23 接到的闸，**PRE 已经比本轮起跑时前进了两个提交**
（`90a18c6f` 收尾 r116 遗留取证、`d166853e` 第 91 轮日志）。
⇒ **PRE 基线必须取闸后现取**；等闸期间的只读取证结论不能直接当合并基线用。

## 二、main 侧：这一轮**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85–91 轮**同一颗** squash（单父提交，父为 `6522c497`）。

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
| 集成头（合并时 `d166853e`）  | **417**| 0      | **48** |

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
**这是本任务连续第 13 轮（第 79–92 轮）撞同一堵墙**，唯一解仍是发布侧解 P0。

## 三、冲突面（11 个，与第 85–91 轮逐个一致）

`git merge --no-ff origin/main`（PRE=`d166853e`）⇒ **11 个冲突**：

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
第 89 轮 `7666d8b2`、第 90 轮 `0c9daaa0`、第 91 轮 `d166853e` 的冲突面**逐个一致**。

**本轮的关键增量**：第 91 轮的 PRE 是 `f8b9383a`（其 `git merge --abort` 在 reflog 留下
`05:05:11 reset: moving to f8b9383a`），本轮的 PRE 是 `d166853e`
（其 `git merge --abort` 留下 `05:06:34 reset: moving to HEAD`），
两次在**不同基线**上得到**逐个一致**的冲突集
⇒ **冲突集与 PRE 基线无关**，它由 merge-base 被拉回 `6522c497` 这一条结构性事实决定：
该点之后集成头新增的文件在 merge-base 上根本不存在 ⇒ 两边都是「各自新增」⇒ add/add。
唯一在 merge-base 上已存在的 `api-proxy.ts` ⇒ content。

**未解任何冲突**（协议硬边界）：`git merge --abort` 已执行，`MERGE_HEAD` 已清，
HEAD 回到 `d166853e`，`git status --porcelain` 为空（0 个改动文件）。

### 3.1 只读预演与真实 merge 逐个一致（沿用第 90 轮建立的方法）

等闸期间用 `git merge-tree --write-tree --name-only integration/module-dev origin/main`
做了**只读**冲突预演（不碰工作区、不改引用），它给出的冲突文件清单与**取闸后真实 merge**
**逐个一致**（11 个，同名同类型）。
⇒ **「是否要进合并流程」这个判断，可以在取闸前就做完** ——
好处是**闸内时间只花在真正要做的合并上**。
⚠️ 前提是**只读**：`merge-tree` 会调用 `merge-union` 驱动
（打印「走了行级兜底并集，请人工确认这个文件改对了」与「台账按模块 ID 求并集，折叠了 465 行同 ID 记录」）
—— 这两条提示在真实 merge 里同样出现，**是已知现象，不是新增异常**。

## 四、为什么没 push / 没跑预检

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**：本地集成头领先 `origin/integration/module-dev` **66 个提交**、落后 0 个。
  本轮**没有产生任何可发布的合并**，发布是流水线的决定（第 85–91 轮同此口径）。
  ⚠️ head 的最后一次全量 L2 是**红**的（见 §1.2），按流水线禁令「L2 没绿绝对不 push」，
  发布侧此刻也**不该**推。

## 五、待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （已连续 **13 轮**：第 79–92 轮）。见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **待办（来自 §1.2）**：流水线的 L2 红点集中在 `channel` 的
   `OAuthAuthorizationIntegrationTest` 与 `IpRiskLibraryIntegrationTest.lifecycleRules`
   —— 其中 `expect 400 but was 409` 属**契约形状**不符（不是负载型假红），
   与 `IpRiskLibrary` 的「偏 8 小时」属**跨钟域**假红，两类要分开定性。
3. **协议待补（来自 §1.3）**：`SYNC-MAIN.md` 第 1 步应写明
   **「持闸者只要还在续期，`stale` 就恒为 false，TTL 不是兜底路径」**。
4. **协议待补（来自 §1.4）**：应写明**孤儿闸的机械指纹**
   （闸目录 0 个 `keepalive-*` + `owner` 的 pid 已死），以及**交接窗口可能只有几十秒**。
5. **协议待补（来自 §1.5）**：应写明**同类同步实例会并发等同一个闸并互相接力**，
   **PRE 基线必须取闸后现取**。
6. 下一轮同步若 main 仍无新提交，预期仍是「冲突未并入」；**除非 P0 被解，否则本任务会一直空转**。

## 六、文件清单

- 新增：本文件
- 未改任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify、未改台账（`sync.mjs --check` 一致）
