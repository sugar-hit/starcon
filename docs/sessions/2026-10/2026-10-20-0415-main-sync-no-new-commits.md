# 2026-10-20 04:15 main → 集成头同步：闸一次即得，main 无新提交，零改动

> 本轮**未 merge、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「无新提交」**。
> 与 02:36 / 03:15 两轮不同，本轮**闸一次就拿到**（无等待），
> 并且观察到一件值得记的事：**流水线正在跑 4 条车道，而闸是 free 的**。

## 一句话结论

04:15 触发本轮时调度闸 `gate=free`（`stale=false`），一次 `acquire` 即成功（04:15:09，owner pid 31595）。
`git fetch origin --prune` **无任何 ref 更新**，`origin/main` 仍停在 `1bb270a0`（batch-28 的 tip），
且它**本就是集成头的祖先** ⇒ `merge-base --is-ancestor origin/main integration/module-dev` 判定为真
⇒ **要同步的内容为零**，本轮零改动（除本会话文档），闸已释放。

## 本轮参数（实时读取，非历史文档）

| 项 | 值 | 来源 |
| --- | --- | --- |
| 集成头 worktree | `/Users/guan/Projects/starcon-worktrees/integration` | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端 | `origin` | 同上 → `PUSH_REMOTE` |
| 协议文件 | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**，主工作区那份是旧版） | 本轮实际读取路径 |
| 集成头 head（提交前） | `11f19237`（== `origin/integration/module-dev`，零偏差） | `git rev-parse` |
| `origin/main` tip | `1bb270a0`（batch-28 tip，**自 01:15 轮以来连续第 4 轮未前进**） | `git rev-parse` |
| 车道状态 | 16 条：**12 `merged` + 4 `running`** | `.dev-agent/lanes.json` |
| 本轮 acquire 时刻 | **04:15:09**（owner pid 31595） | `dispatch.gate/owner` |
| 本轮等闸时长 | **0 秒**（首次 `gate status` 即 `free`） | 计时 |

## 取证一：闸一次即得，没抢、没清

```
$ node tools/module-progress/lane.mjs gate status        # 04:15 首次
{ "gate": "free", "ageSeconds": null, "ttlSeconds": 4200, "stale": false }

$ node tools/module-progress/lane.mjs gate acquire
{ "ok": true, "gate": "acquired" }

$ cat .dev-agent/dispatch.gate/owner
31595
2026-10-19T20:15:09.636Z          # = 2026-10-20 04:15:09 +0800
```

`gate=free` ⇒ 走 `acquire` 正常路径。**没有**遇到 02:36 轮（等 81 分钟）、03:15 轮（等 22 分钟）那种活 tick 持闸。
本轮**未执行**密集 `acquire` 竞态轮询 —— 那是**闸被占时**才需要的手段，本轮闸本就空闲，用它反而多余。

## 取证二：main 无新提交，祖先关系为真

```
$ git fetch origin --prune
(无任何 ref 更新)

$ git merge-base --is-ancestor origin/main integration/module-dev \
  && echo "NO_UPDATE" || echo "HAS_UPDATE"
NO_UPDATE

$ git merge-base origin/main integration/module-dev
1bb270a054f878c56075dfd7f7fbe6a40c8dc851     # == origin/main 的 tip

$ git rev-list --left-right --count integration/module-dev...origin/main
39      0                                       # left=集成头领先 39，right=main 领先 0
```

⇒ `origin/main` 的 tip **就是**两分支的 merge-base，main 的全部历史都已在集成头里。

## 决策 / 结论

### 1. 🔑 判据方向不能反（本轮第四次验证）

| 命令 | 本轮结果 | 含义 |
| --- | --- | --- |
| `git rev-list --count integration/module-dev..origin/main` | **0** | ✅ 正确判据：main 没有集成头缺的东西 |
| `git rev-list --count origin/main..integration/module-dev` | **39** | ⚠️ 反向：集成头比 main 多 39 个 commit |

反向那 39 个是**发布侧（module-dev → main）的欠账**，由另一台机器的发布任务负责推。
本轮若误用反向计数，就会凭空造出「39 个 main 提交待并入」的假象。**本任务不代劳、不越界。**

> 39 比上一轮（03:15）的 38 多的那 1 个，正是**上一轮自己的会话文档提交 `11f19237`** ——
> 发布侧欠账里混着 sync 任务自己的文档提交，属预期。

### 2. 🔑 新观察：闸是 free，但**流水线正在跑 4 条车道**

本轮 `gate status` 返回 `free`，而 `.dev-agent/lanes.json` 同时显示 **4 条 `running`** 车道：

| 车道 | 名称 | 创建时刻 (UTC) | 最近心跳 (UTC) |
| --- | --- | --- | --- |
| 4.3.1.3 | 发布任务 | 20:05:30 | 20:10:36 |
| 4.1.1.3 | 生命周期与下架 | 20:05:35 | 20:12:25 |
| 4.4.1.1 | 链接采集 | 20:05:39 | 20:05:39 |
| 4.5.1.1 | Listing 表现 | 20:05:44 | 20:14:02 |

心跳在 20:14 仍在更新 ⇒ worker **活着**。

⇒ 结论：**闸互斥的是流水线的「合批 + 验证」阶段，不是「worker 干活」阶段。**
调度 tick 派发完 4 条车道后即释放闸，worker 在各自 worktree 里继续干活。
所以 sync 任务与一批车道**并行**是正常态，不构成冲突。

⚠️ 但反过来仍然成立：**worker 阶段不持闸，L2 阶段持闸**。
所以 sync 轮次仍必须取闸 —— 否则会与流水线的 L2 抢占 `backend/**/target/` 与 PG 容器。
本轮按协议取闸、且本轮**不跑任何构建**（NO_UPDATE 分支），无并发构建风险
（`ps` 确认本 worktree 无 `mvnw` / `surefirebooter` 进程）。

### 3. ⛔ 预检未触发（NO_UPDATE 分支不跑预检）

协议第 4 步的快速预检（`./mvnw -q compile` + `pnpm typecheck`）**只在 HAS_UPDATE 分支跑**。
本轮 `NO_UPDATE` ⇒ 没有 merge 就没有需要验证的代码 ⇒ **未跑预检、未跑 L2、未产生任何构建产物**。
既省了发热峰值（协议硬规定：不跑全量 `mvnw verify`），也没有把编译不过的集成头推上去的风险。

### 4. 📌 `sync.mjs` 没有 `--help`，传了也会照跑

本轮想用 `sync.mjs --help` 顺带读台账，结果它**忽略 `--help` 直接执行了一次同步**
（输出 `✓ 同步 18 卷 / 465 个 L4 模块 / 已交付 222 · 进行中 0 · 未开发 243`）。
所幸**零 diff**（`git status --porcelain` 与 `git diff --stat` 均为空）⇒ 台账本就最新，无副作用。
⇒ **教训**：`sync.mjs` 是写操作脚本，**不要拿它当只读查询用**；想读台账直接读
`docs/product/capability-blueprint/module-progress.md`。

### 5. 台账 222 不是本轮的功劳

本轮 `sync.mjs` 跑完**零 diff** ⇒ 台账已是最新。
「已交付 218 → 222」全部来自 batch-30 的 `docs(batch-30)` 提交（02:36 轮已归因），**本轮零贡献**。

| 指标 | 01:15 轮 | 02:36 轮 | 03:15 轮 | 本轮 | 变化来源 |
| --- | --- | --- | --- | --- | --- |
| 模块总数 | 465 | 465 | 465 | **465** | — |
| 已交付 | 218 | 222 | 222 | **222** | batch-30（02:36 轮已归因） |
| 进行中 | 0 | 0 | 0 | **0** | — |
| 未开发 | 247 | 243 | 243 | **243** | 同上 |
| 完成率 | 46.9% | 47.7% | 47.7% | **47.7%** | 同上 |

### 6. 交叉验证：差异规模与上轮对得上

集成头相对 main 的差异规模（**未并入本轮，仅供参照**）：
`124 files changed, 78032 insertions(+), 13326 deletions(-)`。
上轮（03:15）记的是 `122 files / 77814 / 13325` ⇒ 差值正是上轮自己的
`SYNC-MAIN.md` 协议补记 + 会话文档，**数值自洽**。

## 涉及文件

- `docs/sessions/2026-10/2026-10-20-0415-main-sync-no-new-commits.md` —— 本文件（新增，单独 docs commit）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 跑完零 diff）
- 业务代码 —— **零改动**

## 关键 trade-off / 讨论

**要不要为「无新提交」这一轮专门写会话文档？** 写。理由：连续 4 轮 NO_UPDATE 本身是一条**趋势信号**
——`origin/main` 自 01:15 起就没动过，说明**发布侧（module-dev → main）欠了 39 个 commit 且长时间未推**。
这条信息在 sync 任务自己的会话日志里是可检索的；散在 chat 里则下轮 agent 取不到。

## 后续待办

1. **发布侧**（本任务的长期阻塞点）：集成头领先 main **39** 个 commit
   （batch-29 + batch-30 + 各会话文档），`origin/main` 已连续 4 轮停在 `1bb270a0`。
   等发布侧任务推上 main；那之后 main 的 tip 再次成为集成头祖先，sync 任务继续零改动。
2. **下一轮流水线 L2**：本轮**没有**产生 main merge commit ⇒
   协议里「L2 红了先查 main merge commit」这条排查线索本轮**不适用**；
   L2 若红仍应先查本批 4 条车道（4.3.1.3 / 4.1.1.3 / 4.4.1.1 / 4.5.1.1）。
3. **闸的使用节奏**：闸空闲时**一次 acquire 即可**，不要无脑进入密集轮询；
   只有 `held + stale=false` 才需要竞态等待。
4. 若下一轮 main 真正带来集成头缺的内容（`HAS_UPDATE`），预检红 ⇒ `git reset --hard` 回退、**不 push**，
   并在汇报里点名具体编译错误文件。

## 关联

- `tools/module-progress/SYNC-MAIN.md` —— 本轮执行依据的协议（集成头内那份）
- `tools/module-progress/PIPELINE.md` —— 完整流水线协议（与 SYNC-MAIN 冲突时以它为准）
- `docs/sessions/2026-10/2026-10-20-0315-main-sync-gate-contention-no-update.md` —— 上一轮（等闸 22 分钟）
- `docs/sessions/2026-10/2026-10-20-0115-main-sync-no-new-commits.md` —— 01:15 轮（main 曾推进但仍无新内容）
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）

## 闸

已 `gate release`（本轮 04:15:09 确实 `acquire` 成功之后才释放）。
