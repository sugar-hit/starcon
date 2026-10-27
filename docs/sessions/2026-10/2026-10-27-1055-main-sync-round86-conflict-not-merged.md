# 2026-10-27: 集成头同步 main 第 86 轮 —— 冲突未并入（11 个，同第 85 轮）＋ 闸崩链取证

> 本轮 08:15 起跑、10:51 取到闸、10:55 收尾。**结果同样是「冲突未并入」**，
> 冲突面 **11 个**与第 85 轮**逐个一致**。
> 但第 85 轮（`fa26ddbd`）写的是「同步本身」的取证；**本轮补的是第 85 轮没有的那部分**：
> ① 这一整天闸是怎么被占住、怎么崩的、崩成什么样；② 第 109 轮 L2 为什么红；
> ③ 为什么本轮**不 push**（不只是「没东西可 push」）；④ main 这次到底带了几个模块进来。
> 冲突面逐文件分析见第 85 轮文档，本轮不重复。

## 摘要

| 项                      | 值                                                                       |
| ----------------------- | ------------------------------------------------------------------------ |
| 结果                    | **冲突未并入**（11 个冲突，10 个 add/add + 1 个 content）                 |
| 取闸时刻                | **10:51:27**（`free handover` —— 第 85 轮正常释放）                     |
| 等闸时长                | **2 小时 36 分**（08:15 → 10:51）                                       |
| `origin/main` tip       | `823ebca5`「Integration/module dev (#66)」—— **与第 85 轮完全相同，未前进** |
| 待并入提交数            | **1**                                                                   |
| **并入的 main 提交数**  | **0**（冲突中止，无 merge commit）                                      |
| 预检                    | **未跑**（没有可检对象：合并已中止）                                     |
| push                    | **未做**（理由见 §4，**不是**「没东西可 push」）                          |
| 台账                    | **414 已交付 / 0 进行中 / 51 未开发**（465 行，`sync.mjs --check` ✅ 一致）|
| 闸                      | **已释放**（收尾 `gate release`）                                       |

## 一、这一轮闸是怎么被占住、又怎么崩的（**本轮最有价值的新增取证**）

本轮 08:15 起跑时，闸已被**第 109 轮流水线**持着（`mvnw -fae verify` 在跑 L2）。
到 10:51 取到闸为止，闸的持有者换了三轮，**每一轮都不是「正常等就轮到」的形态**：

| 时刻       | 闸的状态                                     | 持闸者                                             | 判活证据                                                    | 本轮动作                                       |
| ---------- | -------------------------------------------- | -------------------------------------------------- | ------------------------------------------------------------ | ---------------------------------------------- |
| 08:15      | `held` / `age=3474` / `stale=false`         | 第 109 轮流水线（session `mvs_38debcf…`，`started`）| **同 worktree 内 `mvnw -fae verify` + surefire JVM 在跑**    | 等（不抢）                                     |
| 08:27:21   | `stale` 窗口打开（`age>4200`）              | 同上                                                | L2 **仍在 STEP 1/6**                                         | **不接管**（见 §2）                             |
| 08:27:22   | 闸被**接管**（owner 换人）                  | **并行的 07:15 同步轮**（`mvs_e996b0d…`）          | —                                                            | 继续等                                          |
| 09:03:20   | 闸**被泄漏**（无人释放）                    | 同上 —— 该轮 **崩了**：`net::ERR_CONNECTION_CLOSED`  | session `status=error`                                       | 记录                                            |
| 09:38:32   | —                                          | 第 109 轮流水线**也崩了**：`net::ERR_HTTP2_PING_FAILED` | session `status=error`                                       | 记录                                            |
| 09:22:30   | 闸最后一次续期（`keepalive-092230`）        | 崩溃轮的孤儿进程                                      | 无活着的持闸会话                                             | 见 §2                                          |
| 10:38:57   | 闸**再次被接管**（owner 换人）              | **并行的 10:15 同步轮**（`mvs_28aa4f4…`）           | 该轮正常完成了合并尝试（11 个冲突）后于 10:51 释放         | 等其释放                                        |
| **10:51:27** | **`free handover` ⇒ 本轮取到闸**            | —                                                  | —                                                            | 开始同步                                        |

**同时段的基础设施故障（不是代码问题）**：08:47–09:49 期间 **GitHub 的 TLS 全断**
（`git ls-remote` ⇒ `SSL_ERROR_SYSCALL`，`curl https://github.com` ⇒ `http=000`，DNS 正常），
**09:49 恢复**（`http=200`）。同一时段 agent runtime 也连续抛
`ERR_CONNECTION_CLOSED` / `ERR_CONNECTION_RESET` / `ERR_HTTP2_PING_FAILED` ——
**三个 cron 会话（流水线 + 07:15 同步轮 + 本轮自己）都在这段时间被网络打断过**。
⇒ 闸的占用与泄漏是**网络故障的下游后果**，不是调度逻辑的问题。

## 二、为什么没有在 `stale=true` 时接管（**本轮刻意偏离协议字面的地方，已留证**）

协议第 1 步写的是「`held` + `stale=true` ⇒ 可以 `acquire` 接管」。
本轮**在 08:27:22 的 stale 窗口刻意没有接管**，理由有硬证据：

- 08:27:22 那个 stale 窗口里，**第 109 轮流水线的 L2 还在跑**（`mvnw -fae verify` + surefire JVM 活着，
  L2 wrapper 脚本 `l2-r109.sh` 也活着）⇒ 持闸者是**活的**，只是心跳没续上。
- `docs/backlog/2026-10-23-gate-stale-false-positive-live-tick-heartbeat-gap.md` 已经记过这个坑：
  **接管活闸 ⇒ 流水线 tick 随后的 `git reset` 会把同步的合并整个抹掉**（2026-10-23 实测事故）。
- 协议同节也写明「判活只能看 `stale` 字段；但**活性取证要看闸外**：同 worktree 里的构建进程」。

⇒ 本轮据此把「密集 `acquire` 竞态」改成了**带护栏的竞态**（`tools/module-progress/` 外的 `.tmp/sync-race-r111.sh`）：

| 闸态                | 本轮的取闸条件                                                                  |
| ------------------- | ------------------------------------------------------------------------------- |
| `free`              | **工作区干净 且 无构建进程在跑** ⇒ 立刻取（抓交接窗口）                          |
| `held` + `stale`    | **且**无构建（`l2-r*.sh` / maven / surefire / `turbo` / `tsc` / `vitest` / `next build`）**且**工作区干净 **且** HEAD 稳定 ≥120s ⇒ 才接管 |
| `held` + 非 stale   | **一律不碰**（不抢、不清、不 force）                                              |

⚠️ 顺带发现并处置的一个闸泄漏向量：07:15 崩溃轮留下的**孤儿进程**
（`/tmp/starcon-sync-quiesce.sh`，父进程已死、reparent 到 init）仍在
**每 1 秒重试 `gate acquire`** —— 它只会在闸变 stale 的瞬间抢到闸，然后打印一行
`GATE_HELD` 就退出，**留下一个无人使用的闸整整 70 分钟**（正好挡住下一轮流水线）。
本轮**只杀了这个孤儿进程**（`kill 32190 32187`），**没有动闸目录、没有清闸、没有 force**：
闸此后按 TTL 自然过期于 10:41:54+4200s。

## 三、main 这次到底带了几个模块进来（**第 85 轮没有的模块级证明**）

第 85 轮给了逐文件冲突分析，本轮补**模块级**的口径（三份台账都是 465 行，逐模块 join）：

| 台账版本                                | 已交付 | 进行中 | 未开发 |
| --------------------------------------- | ------ | ------ | ------ |
| merge-base `6522c497`（= main 的父）    | 340    | 0      | 125    |
| `origin/main` `823ebca5`（squash 快照） | **406**| 0      | 59     |
| 集成头 `fa26ddbd`（合并时）             | **414**| 0      | 51     |

- 相对 merge-base，**main 的 squash 带了 66 个模块**（1.1.1.1 我的待办 … 18.4.2.1 文本与翻译，
  含卷 01 工作台、卷 12 财务、卷 13 经营分析、卷 16 税务合规、卷 17 平台运维、卷 18 集成）——
  **这 66 个模块集成头已全部交付**。
- ⇒ **main 领先（main 已交付、集成头未交付）的模块数 = 0**。

⇒ **本次合并不携带任何新交付**；11 个冲突全部是「squash 把集成头自己的祖先链压掉 ⇒
merge-base 被拉回 `6522c497` ⇒ 两侧在 base 上都不存在的文件变成 add/add」的**结构性副作用**，
不是 main 带进来的代码。**根治仍只能由发布侧把集成头的真实 commit（不 squash）推上 main**，
见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。

## 四、为什么本轮**不 push**（不是「没东西可 push」）

| 项                                          | 实测                                                          |
| ------------------------------------------- | ------------------------------------------------------------- |
| 远端 `refs/heads/integration/module-dev`    | `e6f443ae`（10-26 21:45）                                     |
| 本地 `integration/module-dev`（合并前）     | `fa26ddbd`（第 85 轮文档提交，10-27 10:51）                   |
| **本地领先远端的提交数**                    | **28**（batch-105 四车道 + 两条跨时钟域修复车道 + 流水线日志）  |
| 这些提交为什么没被推上去                    | **第 109 轮 L2 是红的** ⇒ 流水线自己的规则「L2 没绿绝对不 push」 |

⇒ 本轮若 `git push`，会把**这 28 个 L2 未绿的提交**一并推上远端集成头 ——
那是**流水线的发布决定，不是同步任务该越权做的**。同步协议第 5 步的 push 前提是
「合并成功 + 预检绿」，本轮两者都不成立 ⇒ **不 push**。

**好消息（供流水线决策参考）**：第 109 轮 L2 红的原因**不是代码缺陷**，而是环境抖动 ——

| 证据                                              | 实测                                                                                 |
| ------------------------------------------------- | ------------------------------------------------------------------------------------ |
| L2 红了哪个                                       | 只有 `reporting/StoreComparisonIntegrationTest`（16 errors），其余 5 步全绿              |
| 根因                                              | `java.lang.ExceptionInInitializerError` → `Caused by: org.postgresql.util.PSQLException: 进行 SSL 连线时发生错误` |
| 单独复跑（`.tmp/storecomparison-isolated-r109.log`）| `Tests run: 15, Failures: 0, Errors: 0` + `Tests run: 5, Errors: 0` + **BUILD SUCCESS** |
| 结论                                              | **PG 容器 SSL 握手抖动 ⇒ 假红**；`reporting` 模块代码本身是绿的                         |

⇒ 流水线重跑一次 L2 大概率转绿，届时那 28 个提交（连同第 85/86 轮的文档提交）可一并发布。

## 五、冲突面（第 85 轮已逐个分析，本轮只列名单 + 一致性核对）

本轮**用真实 `git merge` 复核**（不是预测）：`git merge --no-ff origin/main`
⇒ **11 个冲突**，与第 85 轮**逐个一致**：

```
apps/web/src/components/dashboard/workbench-card-registry.ts            add/add
apps/web/src/components/dashboard/workbench-layout-model.test.ts        add/add
apps/web/src/lib/api-proxy.ts                                           content
backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java       add/add
backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxController.java      add/add
backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceService.java   add/add
backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java      add/add
backend/services/reporting/src/main/java/com/starcon/reporting/ReportingFinancePort.java           add/add
backend/services/reporting/src/main/java/com/starcon/reporting/ReportingUpstreamClient.java        add/add
backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderIntegrationTest.java           add/add
backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderTenancyIntegrationTest.java    add/add
```

**本轮的前瞻性独立验证**（合并**之前**做的，只读）：用 `git merge-tree --write-tree`
（会调用仓库自己的 `merge.contract-union` 驱动）算出冲突面，**结果与真实 merge 逐个一致**；
再用 blob 三边（base/ours/theirs）自算行数，得出的 ours/theirs/独有行数与第 85 轮的表**完全相同**
（如 `api-proxy.ts` ours=12853 / theirs=12470 / theirs 独有 **0** 行 ⇒ 集成头严格超集）。
⇒ **「只读预演」可以替代一次真实 merge 来定位冲突面**，代价零、不动工作区。

## 六、收尾

- `git merge --abort` ⇒ 退出码 0，**无 `MERGE_HEAD` 残留**，unmerged 路径 0，工作区干净，HEAD = `fa26ddbd`（与合并前一致）。
- 未解任何冲突（协议硬边界）。
- 未跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- 竞态循环**先停后放闸**（`kill $(cat .tmp/r111-race.pid)` ⇒ 确认进程已退出 ⇒ 再 `gate release`），
  避免「release 瞬间被自己的循环抢回来 ⇒ 闸泄漏 70 分钟」。
- **`gate release` 已执行** ⇒ 闸不泄漏，下一轮流水线可正常取闸。

## 七、后续待办

1. **（P0，发布侧）把集成头的真实 commit（不 squash）推上 main** ⇒ 本任务回到「无新提交」的零改动常态。
   本轮再次证实：**main 领先模块数 = 0**，合并没有任何内容价值。
2. **（流水线）重跑 L2**：`reporting` 的红是 PG SSL 抖动（单独复跑已绿），重跑应转绿；
   转绿后再把那 28 个未推提交一并发布。
3. **（治理）调度侧保证同一时刻只有一个 main 同步轮** —— 本轮实测同时有 **3 个**同步轮在跑
   （07:15、08:15、10:15），后两个都撞在同一个闸上。
4. **（治理）给 `gate release` 加「只删自己持有的闸」** —— `lane.mjs:590` 是无条件 `rmSync(GATE_DIR)`，
   持闸者收尾时会删掉别人的闸；本轮已用「先停自己的竞态循环再 release」规避，但根因未修。
5. **（治理）崩溃轮的孤儿进程会无限续命闸** —— 建议 `keepalive` / quiesce 脚本跟随持闸会话的存活，
   否则每崩一轮就泄漏 70 分钟（本轮手工清掉 1 个孤儿，根因仍在）。
