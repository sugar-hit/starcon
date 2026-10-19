# 2026-10-19: 夜间全速流水线 + main → module-dev 同步任务

## 摘要

用户要求：**在本机性能允许的前提下尽可能快地完成功能开发**（token 在 2026-10-25 00:00 前无限量），
并且 **每天 0:00 之后 main 被其他 agent 合并更新后，我们的 `module-dev` 也要同步更新**。

结论：**复用仓库里已有的并行车道流水线协议**，不另起炉灶；把协议中写死的
「另一台机器的路径与远端」外置成本地参数；新建两个定时任务。

| 定时任务 | cron_id | 表达式 | 职责 |
| --- | --- | --- | --- |
| starcon 并行车道功能开发流水线 | `96f8ea76-977c-4807-ae89-5d4412780fc9` | `0 * * * *` | 派发 4 条车道并行开发 + 每批一次 L2 全量验证，绿了才 push 集成头 |
| starcon main → module-dev 集成头同步 | `01450d5d-67a0-4c7f-8805-b17a7767ab95` | `15 * * * *` | 把 `origin/main` 的新提交并入集成头，预检绿才 push |
| （既有）starcon 交付进度看板每日刷新 | `8f4761bb-c483-4695-b95f-e0c8bdc92444` | `0 8 * * *` | 未改动 |

## 决策摘要

1. **协议参数外置**：`PIPELINE.md` / `PIPELINE_CRON_PROMPT.md` 原先写死
   `/Users/tommy/Project/starcon`（6+14 处）与远端 `upstream`（本机不存在该远端）。
   现改为从 `.dev-agent/PIPELINE.local.md` 读 `PROJECT_ROOT` / `PUSH_REMOTE` / `LANES` /
   `THROTTLE_MIN` / `SILENT_WINDOW` / `TOKEN_HORIZON` / `CRON_ID`。
   **策略留在版本库，机器参数进本地文件** —— 调参不产生 `perf(pipeline)` 噪音 commit。
2. **集成头跑在独立 worktree**：`/Users/guan/Projects/starcon-worktrees/integration`。
   原因：`lane.mjs merge-ready` 会在 ROOT 里 `checkout integration/module-dev`；
   若 ROOT 是主工作区，会删掉 `main` 上的 `tools/progress-site/`（进度看板任务依赖它），
   并劫持留给人工的主 checkout。车道 worktree 落在 `<集成头>/.worktrees/`（已被 gitignore）。
3. **静默窗口从「写死」改成「开关」**：`SILENT_WINDOW=1` 是历史档位（20:30→08:00 不开工），
   `0` 是全天运行。**本次默认 0**，理由见下节 trade-off。
4. **token 期限做成自动降档**：`TOKEN_HORIZON=2026-10-25T00:00:00+0800`，
   过期后 `LANES` 减半、节流闸翻倍。不需要人确认，也不改变停机条件（停机只看 465 个模块是否全部已交付）。
5. **同步任务与流水线共用同一个调度闸**（`lane.mjs gate`），互斥，避免两个任务并发改集成头留 MERGE_HEAD 残骸。

## 关键 trade-off

| 决策 | 取舍 |
| --- | --- |
| **默认全天运行（`SILENT_WINDOW=0`）** | 历史上 20:30→08:00 静默是用户 10-07/10-08 因「电脑太热」「风扇声音非常大」定的。本次用户明确要「尽可能快」+「token 无限量」，据此判断为**时段闸已解除**。温度闸、容量闸、节流闸**全部照旧生效** —— 关掉的只是「按钟点停工」。要恢复夜间静默：把本地配置改成 `SILENT_WINDOW=1` 即可，一行。 |
| **同步任务只跑快速预检，不跑全量 L2** | 全量 `mvnw verify`（2829 例）是流水线**每批一次**的发热高峰。同步任务再跑一次 = 双倍发热峰值。折中：合并后跑后端 `compile` + `pnpm typecheck`（秒级～分钟级），预检红则 `reset --hard` 回退不推送。迁移违约/跨车道破坏留给流水线 L2 暴露。 |
| **同步任务「等闸」而不是「抢闸」** | 流水线一轮持闸 40–60 分钟，闸空闲窗口只有 0–20 分钟且时间不定。同步任务无截止时间，等待比放弃更符合「一旦 main 更新就同步」的意图。闸 TTL 70 分钟，崩掉的闸最多挡一轮。 |
| **`LANES=4` 而非更高** | runtime 子任务并发上限是 4。配 6 会出现「排出来 ≠ 派得出去」，每轮白规划再释放。发热峰值由「每批一次全量」决定，与车道数无关，所以 4 条不增加峰值。 |
| **节流闸阈值 50 分钟（cron 栅格 60 分钟）** | 阈值必须小于栅格间隔并留出「落盘耗时」余量。照抄 60 会被静默拦下 —— 节奏悄悄变成每 2 小时一次，且零报错。50 留 10 分钟余量（实测最坏落盘 5 分 47 秒）。 |
| **没有动对方 agent 的 `MAIN_MERGE_CRON_PROMPT.md`** | 它是**反方向**（module-dev → main，发布侧），且写死了另一台机器的 `/Users/tommy/...` 与 `upstream` 远端。本机不适用，改了反而制造跨机冲突。两侧任务合起来正好闭环：module-dev → main（发布）→ main → module-dev（回收）。 |

## 涉及文件

**版本库（已推送到 `origin/integration/module-dev`，head `5d9e8cc7`）**

- `tools/module-progress/PIPELINE.md` — 新增「0. 本地参数」章；静默窗口改开关；新增 token 期限档位；新增 5.6 集成头与 main 的关系
- `tools/module-progress/PIPELINE_CRON_PROMPT.md` — 同上 + 第 0.5 步强度档位；cron_id 改为从本地参数读
- `tools/module-progress/SYNC-MAIN.md` — **新增**：main → 集成头同步任务的完整协议
- `tools/module-progress/SETUP-MAC-B.md` — 标注其路径/cron_id 属于 Mac B 那台机器
- `docs/sessions/2026-10/2026-10-19-night-pipeline-and-main-sync.md` — 本文件

**本地（不入版本库，`.dev-agent/` 已 gitignore）**

- `/Users/guan/Projects/starcon/.dev-agent/PIPELINE.local.md` — 本机参数与两个 cron_id
- `/Users/guan/Projects/starcon-worktrees/integration/.dev-agent/` — 编排器状态（lanes.json / dispatch.gate / last-batch-at），并软链到上面的配置

## ⛔ 收尾三件套：写说明 → commit → push（用户明确要求，22:2x 追加）

用户要求「**每次开发完成都要 comment commit and push**」。核对协议后发现**中间那一步从来没写过**：

| 协议原文 | 现状 |
| --- | --- |
| 5.3 写详细功能说明（写文件） | 有 |
| 5.4 push（推远端） | 有 |
| **把说明文件 commit 进版本库** | **缺** |

照字面执行的后果：说明文件是未跟踪/未提交状态，**push 不会带上它**，
远端永远缺这一批的说明；而 5.4 又要求「push 前确认工作区干净」——
两条规则互相打架，调度者要么带着脏工作区 push（说明丢失），
要么卡在「工作区不干净」直接不推（整批交付卡在本地）。

**修法**（PIPELINE.md 5.3 / PIPELINE_CRON_PROMPT.md 5.3 / 两个 cron 任务书同步）：

1. 5.3 改名 **「收尾三件套：写说明 → commit → push」**，顺序写死，三件缺任一件
   **本轮不算完成**，汇报里必须点名缺哪一件
2. 补上 ② commit 的完整命令：`sync.mjs` → `git add 说明 + 台账` →
   `commit -m "docs(batch-<n>): <模块清单> —— <一句话>"`，
   **说明文件与台账必须同一个 commit**
3. 批次号 `<n>` **接着已有往后排**（`ls docs/sessions/$(date +%Y-%m)/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1`）
   —— 这条分支上可能有别的 agent 同时在写批次文档，重号会互相覆盖
4. L2 红的路径也明确：**说明照写、commit 照做**（记清红在哪、二分到哪条车道），
   只是**不 push**；成果与诊断留在集成头本地，修复后随下一批一起 push。
   这样既不违反「L2 没绿不 push」，也不让诊断信息丢失
5. 同步任务（main → module-dev）也补上：**合并 commit 的正文要列清并入的 main 提交**，
   它同时是回归时的首要定位依据
6. 顺带修掉一条与新配置矛盾的禁令：「不要改 cron 的 `8-19` 时段」→
   「不要自行改 `SILENT_WINDOW` 与 cron 的时段（由本地配置决定）」

## 复核中修掉的三个真缺陷（派发链路，否则每轮都跑偏）
1. **worker 的 shell 里没有 `$PROJECT_ROOT`。** 子任务模板里的心跳/落地命令
   `cd "$PROJECT_ROOT" && lane.mjs heartbeat` 是给调度者用的变量；
   占位符没替换时 worker 的 `cd ""` 留在自己 worktree，`lane.mjs` 的 ROOT 随之变成
   worktree —— 状态被写进 `<worktree>/.dev-agent/lanes.json`，
   **调度者永远看不到心跳与落地标记，车道会被当成崩掉回收重派**。
   改成 `<INTEGRATION_ROOT>` 显式占位符，并在派发步骤加了
   「逐字检查无残留占位符」的要求与替换对照表。
2. **协议里写死了过期基线数字**（126 已交付 / 339 未开发 / 5 批/天 / 夜间静默 / 24 天）。
   cron 任务书自己写着「不要引用任何历史文档里写死的数字」，
   而这两处正是这种数字 —— 调度者读了会报错进度。改成按当前本地配置现算。
3. **cron 会话起在主工作区，而主工作区的协议文件是旧版。**
   主工作区在 `main` 上，那两份协议还写着你那台机器的路径 ——
   直接读会跑不起来。两个 cron prompt 都已改成**先 `cd "$PROJECT_ROOT"`，
   再读集成头 worktree 里的协议文件**，并明确写了「主工作区那份是旧版，不要读」。

## 本机环境事实（2026-10-19 22:0x 实测）

| 项 | 值 |
| --- | --- |
| 机器 | 10 核 / 12.5G / macOS |
| Docker | 28.4.0，overlay2；`postgres:16-alpine` 与 `testcontainers/ryuk:0.12.0` 本地已有且为原名（Testcontainers 直接可用） |
| Node / pnpm / Java | v26.7.0 / 9.0.0 / 25.0.2 |
| 集成头台账 | 465 个 L4 模块：**已交付 210 / 进行中 0 / 未开发 255** |
| 温度 | `pmset -g therm` 无降频记录 |
| Testcontainers PG 容器 | 0 个（无孤儿容器占额度） |

## 冒烟验证（已实跑，不是推断）

在集成头 worktree 实跑了一条完整链路，验证后**已清理干净**（`git worktree list` / `lanes.json` / 分支均复原）：

1. `lane.mjs init` → 集成头 `127db297`
2. `lane.mjs gate acquire` → 拿到闸
3. `lane.mjs plan --lanes 1` → 选出 11.2.3.3「供应商索赔」，预分配 `V188`，
   **在集成头内部成功创建嵌套 worktree** `.worktrees/11.2.3.3` 并装好依赖
4. 车道内提交 → `lane.mjs land --lane 11.2.3.3` → `state=ready`
5. `lane.mjs merge-ready` → 并入集成头成功（`61365bf0` → `b5603af8`）
6. `lane.mjs gc` + `git reset --hard` + 删分支 + 清 lanes.json → 复原到 `61365bf0`
7. `lane.mjs gate release` → 闸已释放

**未验证项**：全量 L2（`mvnw verify` 全量 + Next build）本次未跑 —— 它是流水线的
每批一次发热高峰，由第一个流水线 tick 执行并决定是否 push。协议改动本身只涉及 markdown。

**同步任务首跑（22:15:00 实际触发）**：cron 正常派发，agent 取到协议后
按步骤取闸 → 判定 `origin/main` 无新提交 → 释放闸 → 结束。
闸已确认释放，集成头工作区干净。

**网络抖动**：22:3x 推 `80ceecac` 时连续两次 `SSL_ERROR_SYSCALL`（github.com:443），
第 3 次成功。这台机器的网络本身不稳（其他会话也有 `ERR_NETWORK_IO_SUSPENDED`）——
定时任务里的 `git fetch` 失败要如实报，不要当成「远端没更新」。

## 后续待办

- [ ] 第一个流水线 tick（23:00）验证：4 条车道派发 → L2 全量 → push 集成头
- [ ] 观察首轮的实际单轮耗时，据此校准 `THROTTLE_MIN`（现在 50 分钟，预估一轮 40–60 分钟）
- [ ] 观察机器温升；若过热，回退只需 `SILENT_WINDOW=1`（恢复夜间静默）
- [ ] 2026-10-25 00:00 后确认自动降档生效（LANES 4→2、节流闸 50→100）
- [ ] 与另一台机器的发布任务（module-dev → main）核对节拍，确认闭环无冲突
- [ ] 主工作区 5 个未推送的 `progress-site` 提交仍待推送（与本任务无关，属另一条线）
