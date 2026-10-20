# 2026-10-20 02:36: main → 集成头同步：等闸 81 分钟，main 无新提交

> 本轮**未 merge、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「无新提交」**。
> 但这轮真正有价值的结论在**调度闸的时序**上 —— 闸的释放窗口窄到 20 秒轮询会整个错过。

## 摘要

02:15 触发本轮时，调度闸被一个**活着的流水线 tick** 持有着（`gate=held`, `stale=false`）。
按协议只能等，不能抢、不能清、不能 force。等闸过程跨了 **81 分钟**（02:15:43 → 03:36:18），
期间**两次**踩到闸释放瞬间的竞态窗口（详见「决策 2」）。
03:36:18 拿到闸后 `git fetch origin --prune` **无任何 ref 更新**，
`origin/main` 仍停在 `1bb270a0`（batch-28 的 tip），且它**本就是集成头的祖先**
⇒ `merge-base --is-ancestor origin/main integration/module-dev` 判定为真
⇒ **要同步的内容为零**，本轮零改动。

期间流水线把 batch-30（`4.3.1.1` / `16.2.2.1` / `4.2.3.2` / `4.3.2.2`）合进了集成头并推上远端，
集成头从 `fbad4286` 前进到 `72b4bde9`。**台账因此从 218 涨到 222 —— 那是 batch-30 的功劳，不是本轮的**。

## 决策 / 结论

### 1. 判据方向不能反（沿用 01:15 轮的结论，本轮再次验证）

协议第 2 步原文：`git merge-base --is-ancestor origin/main integration/module-dev`。

| 方向 | 本轮结果 | 含义 |
| --- | --- | --- |
| `rev-list --count integration/module-dev..origin/main` | **0** | ✅ main 没有集成头缺的东西 |
| `rev-list --count origin/main..integration/module-dev` | **37** | ⚠️ 反向：集成头比 main 多 37 个 commit |

反向那 37 个是**发布侧（module-dev → main）的欠账**，由另一台机器的发布任务负责推。
本轮若误用反向计数，就会凭空造出「37 个 main 提交待并入」的假象。
**本任务不代劳、不越界。**

### 2. 🔑 闸的释放窗口窄到「轮询 status」必丢，必须直接竞态 `acquire`

本轮等闸实测出的**关键运维事实**：

| 时刻 | 观察 |
| --- | --- |
| 02:15:43 | `held`, age=688（持闸者 02:04:02 acquire 的 tick） |
| 03:04:00 | `held`, age=3585 —— 仍被同一 tick 持有（整 60 分钟） |
| **03:04:20** | **age 断崖 3585 → 18** ⇒ 03:04:02 释放，下一 tick **同一秒**重新 acquire |
| 03:36:12 | `held`, age=1634（03:04:02 那个 tick 持有了约 32 分钟） |
| **03:36:18** | 本轮 `acquire` 成功 ⇒ 持闸者在前 6 秒内释放，本轮赢得 mkdir 竞态 |

**教训**：第一版看门狗是「每 20 秒 `gate status` → 看到 free 再 `acquire`」，
在 03:04:02 那个**零宽度**的交接窗口上**整段错过**，直到 03:36 才拿到闸。
改成**每 1 秒直接 `gate acquire`、不依赖观测到 free**之后，竞态是直接去赢 mkdir 的。

依据是 `lane.mjs` 的实现：`gateAcquire()` 用 `mkdirSync` 做原子互斥 ——
持有且未过期时 mkdir 失败且**无任何副作用**；空闲时 mkdir 成功即取得；
`age > GATE_TTL_SECONDS` 时按 TTL 接管。
⚠️ 该 TTL 判据与 `gate status` 的 `stale` 字段**是同一套阈值**（同一 `mtime`、同一 `GATE_TTL_SECONDS`），
不是「自己按 ageSeconds 猜」。

**给后续轮次的建议**：等闸一律用「密集 `gate acquire` 竞态」，不要用「低频 `gate status` 轮询」。

### 3. `dispatch.gate/owner` 里的 PID **不构成闸存活证据**

本轮拿到闸时 owner 是 PID `12348`，`ps -p 12348` **查无此进程** ——
但闸确实是被活着的 tick 持有着。
原因：`gate acquire` 是**短命进程**，写完 `owner` 就退出；真正的持闸者是后续长时间跑的调度器。
⇒ 判活**只能**用 `gate status` 的 `stale` 字段，不能看 PID。

### 4. 没拿到过闸就**不要** `gate release`

协议第 6 步写「无论走到哪一步都必须 release」，但它的前提是**本轮持过闸**。
若本轮全程没拿到闸就执行 `gate release`，会把**别人活着的闸删掉** ——
那正是 cron prompt 禁止的「清闸」，也会直接破坏闸的互斥语义。
本轮是在 03:36:18 真的 acquire 成功之后才 release 的。

### 5. 预检未触发（NO_UPDATE 分支不跑预检）

协议第 4 步的快速预检（`./mvnw -q compile` + `pnpm typecheck`）**只在 HAS_UPDATE 分支跑**。
本轮 `NO_UPDATE` ⇒ 没有 merge 就没有需要验证的代码 ⇒ **未跑预检、未跑 L2、未产生任何构建产物**。
既省了发热峰值（协议硬规定：不跑全量 `mvnw verify`），也没有把编译不过的集成头推上去的风险。

### 6. 台账涨 4 不是本轮的功劳

`node tools/module-progress/sync.mjs` 跑完**零 diff**（`git status --porcelain` 为空）⇒ 台账已是最新。
数字变化全部来自 batch-30 的 `docs(batch-30)` 提交。

## 本轮参数（实时读取，非历史文档）

| 项 | 值 | 来源 |
| --- | --- | --- |
| 集成头 worktree | `/Users/guan/Projects/starcon-worktrees/integration` | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端 | `origin` | 同上 → `PUSH_REMOTE` |
| 协议文件 | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**） | 本轮实际读取路径 |
| 集成头 head（合并前） | `72b4bde9`（== `origin/integration/module-dev`，零偏差） | `git rev-parse` |
| `origin/main` tip | `1bb270a0`（batch-28 tip，**自 01:15 轮以来未前进**） | `git rev-parse` |
| 车道状态 | 12 条全 `merged` | `.dev-agent/lanes.json` |
| 本轮 acquire 时刻 | 03:36:18（owner pid 25155） | `dispatch.gate/owner` |

## 取证

```
$ git fetch origin --prune
(无 ref 更新)

$ git merge-base --is-ancestor origin/main integration/module-dev && echo NO_UPDATE || echo HAS_UPDATE
NO_UPDATE

$ git rev-list --count integration/module-dev..origin/main
0
$ git rev-list --count origin/main..integration/module-dev
37
$ git merge-base origin/main integration/module-dev
1bb270a054f878c56075dfd7f7fbe6a40c8dc851     # == origin/main 的 tip
```

集成头相对 main 的差异规模（**未并入本轮，仅供参照**）：
`121 files changed, 77670 insertions(+), 13325 deletions(-)`。

## 台账现状（`sync.mjs` 实时值，跑完零 diff）

| 指标 | 01:15 轮 | 本轮 | 变化来源 |
| --- | --- | --- | --- |
| 模块总数 | 465 | 465 | — |
| 已交付 | 218 | **222** | **batch-30**（4.3.1.1 / 16.2.2.1 / 4.2.3.2 / 4.3.2.2） |
| 进行中 | 0 | 0 | — |
| 未开发 | 247 | **243** | 同上 |

## 涉及文件

- `docs/sessions/2026-10/2026-10-20-0236-main-sync-gate-contention-no-update.md` —— 本文件（新增，单独 docs commit）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 跑完零 diff）

## 后续待办

1. **发布侧**：集成头领先 main **37** 个 commit（batch-29 + batch-30 + 各会话文档），
   等发布侧任务推上 main；那之后 main 的 tip 再次成为集成头祖先，sync 任务继续零改动。
2. **等闸方式**：后续 sync 轮次改用「1 秒级密集 `gate acquire` 竞态」，
   不要用低频 `gate status` 轮询（03:04:02 那次交接窗口就是这么丢的）。
3. **下一轮流水线 L2**：本轮**没有**产生 main merge commit ⇒
   协议里「L2 红了先查 main merge commit」这条排查线索本轮**不适用**；
   L2 若红仍应先查 batch-30 的 4 条车道。
4. 若下一轮 main 真正带来集成头缺的内容（`HAS_UPDATE`），预检红 ⇒ `git reset --hard` 回退、**不 push**，
   并在汇报里点名具体编译错误文件。

## 闸

已 `gate release`（本轮 03:36:18 确实 acquire 成功之后才释放）。
