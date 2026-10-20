# 2026-10-20 09:15 main → 集成头同步：等闸 27 分钟，main 无新提交，零改动

> 本轮**未 merge、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「无新提交」**。
> 与 04:15 轮（闸一次即得）不同，本轮**闸被一个活着的流水线 tick 持住，等了 27 分钟**才拿到。

## 一句话结论

09:15 触发时调度闸 `gate=held`（`stale=false`），持闸者是**09:00 tick 的 batch-33 L2**（`mvnw verify` pid 68239，
08:57 起跑，09:42 结束）。按协议用**密集 `acquire` 竞态**等待，**1607 秒（27 分钟）后取得闸**（owner pid 86321）。
取得闸后 `git fetch origin --prune` 无 ref 更新，`origin/main` 仍停在 `1bb270a0`（batch-28 tip），
且它**本就是集成头的祖先** ⇒ `merge-base --is-ancestor origin/main integration/module-dev` 判定为真
⇒ **要同步的内容为零**，本轮零改动（除本会话文档），闸已释放。

## 本轮参数（实时读取，非历史文档）

| 项 | 值 | 来源 |
| --- | --- | --- |
| 集成头 worktree | `/Users/guan/Projects/starcon-worktrees/integration` | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端 | `origin` | 同上 → `PUSH_REMOTE` |
| 协议文件 | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**，主工作区那份是旧版） | 本轮实际读取路径 |
| 集成头 head（提交前） | `8ec8cbb3`（== `origin/integration/module-dev`，零偏差） | `git rev-parse` |
| `origin/main` tip | `1bb270a0`（batch-28 tip，**自 01:15 轮以来连续第 5 轮未前进**） | `git rev-parse` + `git ls-remote` 复核 |
| 车道状态 | 24 条：**全部 `merged`**，`running=0` / `ready=0` | `lane.mjs status` |
| 本轮 acquire 时刻 | **09:44:42**（owner pid 86321） | `dispatch.gate/owner` |
| 本轮等闸时长 | **1607 秒 ≈ 27 分钟**（竞态轮询到成功） | 竞态脚本计数 |
| 二次 acquire（写本文档前） | 09:47:40 一次即得（owner pid 86984） | `dispatch.gate/owner` |

## 取证一：闸被活 tick 持住，等了 27 分钟

```
$ node tools/module-progress/lane.mjs gate status        # 09:15 首次
{ "gate": "held", "ageSeconds": 57, "ttlSeconds": 4200, "stale": false }
```

`held + stale=false` ⇒ **活着的流水线 tick 持闸** ⇒ 按协议**等它释放**，不抢、不清、不 force。

**活性取证（闸外）**：`ps` 显示 `mvnw verify` 的 java 进程（pid 68239）在**同一 worktree** 里跑，
且 `.dev-agent/dispatch.gate/` 下每分钟新增 `keepalive-takeover-HHMM` 文件（闸目录 mtime 被持续刷新）
⇒ 确认是活 tick，不是陈旧闸。

**本轮实际观察到的一个机制细节**：闸目录 mtime 由 `keepalive-takeover-<HHMM>` 续期，
所以 `ageSeconds` 会被**重置回几十秒**，不会线性增长到 TTL。
这意味着**不能靠 `ageSeconds` 变大来判断「快过期了」** —— 必须一直以 `stale` 字段为准。
本轮 `ageSeconds` 全程在 1～116 秒之间反复跳变，而 `stale` 始终为 `false`。

**等待期间的取证纪律**（协议明确要求）：

- 等闸期间**只做只读取证**：`gate status` / `cat owner` / `ps` / `git log` / `git status`。
- **没有跑第二个构建**（协议：持闸 tick 的 L2 与本任务预检共用 `backend/**/target/` 与 PG 容器，并发必假红）。
- **连 `git fetch` 都没跑**（协议：等闸期间先别 fetch），fetch 推迟到取得闸之后。
- 期间发现工作区有 3 个未提交改动（`module-progress.md` + 2 个 backlog 文件），
  经 mtime 比对（08:58 / 09:00）确认是**持闸 tick 自己写的**，**未做任何处理**（既不 stash 也不 reset）。

**竞争者**：另有一个 50Hz 竞态脚本（`/tmp/starcon-sync-gate-race.mjs`，09:08 启动）在同时等闸。
它带「活构建守卫」（仅当 age > TTL 且**无活构建进程**才按 TTL 接管），逻辑更保守。
最终**本轮先取得闸**（09:44:42），对方进程随后自行退出，**未发生争抢**。

## 取证二：main 无新提交，祖先关系为真

```
$ git fetch origin --prune
(无任何 ref 更新)

$ git merge-base --is-ancestor origin/main integration/module-dev \
  && echo "NO_UPDATE" || echo "HAS_UPDATE"
NO_UPDATE

$ git rev-list --left-right --count integration/module-dev...origin/main
82      0                                       # left=集成头领先 82，right=main 领先 0

$ git ls-remote origin refs/heads/main
1bb270a054f878c56075bfd7f7fbe6a40c8dc851        # 与本地 origin/main 一致，排除 ref 过期
```

⇒ `origin/main` 的 tip **就是**两分支的 merge-base，main 的全部历史都已在集成头里。

## 决策 / 结论

### 1. 🔑 判据方向不能反（连续第 5 轮验证）

| 命令 | 本轮结果 | 含义 |
| --- | --- | --- |
| `git rev-list --count integration/module-dev..origin/main` | **0** | ✅ 正确判据：main 没有集成头缺的东西 |
| `git rev-list --count origin/main..integration/module-dev` | **82** | ⚠️ 反向：集成头比 main 多 82 个 commit |

反向那 82 个是**发布侧（module-dev → main）的欠账**，由另一台机器的发布任务负责推。
本轮若误用反向计数，就会凭空造出「82 个 main 提交待并入」的假象。**本任务不代劳、不越界。**

> 82 比 04:15 轮的 39 多出的 43 个，主要是 **batch-31 / batch-32 / batch-33 三批并入的成果**
> （期间发生的 4 个 `merge(...)` + 车道 `feat(...)` + 批说明 `docs(batch-*)`）。

### 2. 🔑 新观察：等闸 27 分钟，等的是一个**正在跑 L2 的 tick**

本轮与 02:36 轮（等 81 分钟）、03:15 轮（等 22 分钟）同属「闸被占」型，但**占用原因不同**：

| 轮次 | 等闸 | 持闸者在做什么 |
| --- | --- | --- |
| 02:36 | 81 分钟 | （老闸未释放） |
| 03:15 | 22 分钟 | 活 tick |
| 04:15 | 0 秒 | 闸空闲（派发完即释放） |
| **09:15（本轮）** | **27 分钟** | **batch-33 的 L2 `mvnw verify` 整批重跑** |

本轮把「持闸 tick 干什么」这件事**完整观测了一遍**（借 `ps` + keepalive 文件的时间线）：

```
08:57:29   mvnw verify 启动（pid 68239，= batch-33 L2 第 1 步）
08:58:03   backlog 诊断文档被写入（持闸 tick 记录时钟漂移根因）
09:00:14   module-progress.md 被写入（台账刷新）
09:41–09:42  L2 第 4/5 步 vitest 4822 例全绿 → 第 5/5 步 next build 成功
09:42:29   L2 DONE，SUMMARY: mvn=0 typecheck=0 lint=0 test=0 build=0 sync=0
09:42+     tick 提交 batch-33（8ec8cbb3）并 push，随后做 gc 清理车道 worktree
09:44:42   闸释放 → 本轮 acquire 成功
```

⇒ **闸的持有时长 ≈ 整个 L2 验证时长**（本轮 08:57→09:44 ≈ 47 分钟），
与 `PIPELINE.md` 里「一个 tick 持闸跑完 merge + L2 + 三件套，实测单轮 40–100 分钟」完全吻合。
⇒ **本轮等闸不是「闸坏掉没人管」，而是「闸正在被高价值工作占用」** —— 等是对的。

### 3. 🔑 新发现：闸释放与批次收尾**不是原子**的，中间有窗口

本轮实测到一个此前会话没记过的时序细节：

- 持闸 tick 在 **09:42:29** 跑完 L2，**09:42 后**提交并 push 了 batch-33，
- 但闸**没有在 push 完成的瞬间释放** —— 之后还跑了一段 `gc`（清理 24 条车道的 worktree），
- 闸实际释放落在 **09:44:42 前后**。

⇒ 含义：**「看到 L2 绿了」不等于「闸空了」**。若下一轮 agent 在 L2 绿后立刻去看 `gate status`，
会看到 `held + stale=false`，从而误判「还有人持闸」而继续等 —— 这是对的，但要知道原因在 tick 的收尾阶段。
⇒ 也解释了为什么 04:15 轮能一次拿到闸：**那个时刻 tick 处于「派发完车道、已释放闸、worker 在各自 worktree 干活」的阶段**
（与 04:15 轮记录的「闸 free 但 4 条车道 running」一致）。
⇒ **闸互斥的是 L2/合批阶段，不是 worker 干活阶段** —— 这条结论在本轮再次得到验证。

### 4. ⛔ 预检未触发（NO_UPDATE 分支不跑预检）

协议第 4 步的快速预检（`./mvnw -q compile` + `pnpm typecheck`）**只在 HAS_UPDATE 分支跑**。
本轮 `NO_UPDATE` ⇒ 没有 merge 就没有需要验证的代码 ⇒ **未跑预检、未跑 L2、未产生任何构建产物**。
既省了发热峰值（协议硬规定：不跑全量 `mvnw verify`），也没有把编译不过的集成头推上去的风险。

### 5. 📌 `sync.mjs` 仍然没有 `--help`，传了也会照跑

本轮为了读台账，执行了 `node tools/module-progress/sync.mjs --help`，
它**忽略 `--help` 直接执行了一次同步**（输出 `✓ 同步 18 卷 / 465 个 L4 模块`，已交付 234）。
所幸**零 diff**（`git status --porcelain` 为空）⇒ 台账本就最新，无副作用。

> ⚠️ 这是 04:15 轮已经记过的教训，**本轮又踩了一次**。
> 补充一条更准确的观察：**它把台账刷成了「当前工作区」的状态**，
> 而本轮工作区相对 `8ec8cbb3` 是干净的，所以刷出来零 diff。
> 若在**脏工作区**下调用它，它会把未提交的改动**一并写进台账文件** ⇒ 有把在途改动误固化进台账的风险。
> ⇒ **结论不变：`sync.mjs` 是写操作脚本，不要拿它当只读查询用。**
> 想读台账直接读 `docs/product/capability-blueprint/module-progress.md`。

### 6. 📊 台账 234 是 batch-33 的功劳，不是本轮的

本轮 `sync.mjs` 跑完**零 diff** ⇒ 台账已是最新。
「已交付 230 → 234」全部来自 batch-33 的 `docs(batch-33)` 提交（`8ec8cbb3` 的正文已明确记录该归因），**本轮零贡献**。

| 指标 | 02:36 轮 | 03:15 轮 | 04:15 轮 | **本轮** | 变化来源 |
| --- | --- | --- | --- | --- | --- |
| 模块总数 | 465 | 465 | 465 | **465** | — |
| 已交付 | 222 | 222 | 222 | **234** | batch-31/32/33（流水线，非本任务） |
| 进行中 | 0 | 0 | 0 | **0** | — |
| 未开发 | 243 | 243 | 243 | **231** | 同上 |
| 完成率 | 47.7% | 47.7% | 47.7% | **50.3%** | 同上 |

### 7. 交叉验证：集成头相对 main 的差异规模

`git diff --stat origin/main integration/module-dev`（**未并入本轮，仅供参照**）：
`287 files changed, 184863 insertions(+), 31330 deletions(-)`。
上轮（04:15）记的是 `124 files / 78032 / 13326` ⇒ 增量的 163 个文件正是 batch-31/32/33 的交付内容，**数值自洽**。

## 涉及文件

- `docs/sessions/2026-10/2026-10-20-0915-main-sync-gate-contention-no-update.md` —— 本文件（新增，单独 docs commit）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 跑完零 diff）
- 业务代码 —— **零改动**

## 关键 trade-off / 讨论

**要不要为「无新提交」这一轮专门写会话文档？** 写。理由（与 04:15 轮一致）：
连续 5 轮 NO_UPDATE 本身是一条**趋势信号** —— `origin/main` 自 00:00 起就没动过，
说明**发布侧（module-dev → main）欠了 82 个 commit 且长时间未推**。
这条信息在 sync 任务自己的会话日志里是可检索的；散在 chat 里则下轮 agent 取不到。

**要不要在等闸的 27 分钟里「先做点别的」？** 不做。协议明令：等闸期间只做只读取证，
不跑第二个构建、连 `git fetch` 都先别跑。理由是持闸 tick 的 L2 与本任务的预检**共用
`backend/**/target/` 与 PG 容器** ⇒ 并发必假红；**假红会污染下一轮流水线的判断**。

**本轮比前几轮多做了什么？** 多观测了一次**完整的「持闸 tick 生命周期」**（L2 启动 → 绿 → 提交 push → gc → 释放），
把「闸为什么被占、占多久、什么时候会放」这三件事第一次量化清楚（§2、§3）。
这对下轮 agent 判断「等多久是正常的」有直接价值。

## 后续待办

1. **发布侧**（本任务的长期阻塞点）：集成头领先 main **82** 个 commit
   （batch-29 ~ batch-33 + 各会话文档），`origin/main` 已连续 5 轮停在 `1bb270a0`。
   等发布侧任务推上 main；那之后 main 的 tip 再次成为集成头祖先，sync 任务继续零改动。
2. **下一轮流水线 L2**：本轮**没有**产生 main merge commit ⇒
   协议里「L2 红了先查 main merge commit」这条排查线索本轮**不适用**；
   L2 若红仍应先查上一批 4 条车道（4.4.1.3 / 9.2.1.1 / 9.2.2.1 / 4.5.2.2）。
3. **闸的使用节奏**：闸空闲时**一次 acquire 即可**（本轮二次 acquire 1 秒即得）；
   只有 `held + stale=false` 才需要竞态等待。竞态等待的**预期时长**参考本轮实测：
   **L2 全量验证约 45 分钟 ⇒ 持闸约 47 分钟 ⇒ 等闸 20–30 分钟属正常**。
4. **别用 `ageSeconds` 猜闸快过期了**：持闸 tick 会用 `keepalive-<新文件名>` 续期，
   `ageSeconds` 会被重置回几十秒。**判据只能是 `stale` 字段。**
5. 若下一轮 main 真正带来集成头缺的内容（`HAS_UPDATE`），预检红 ⇒ `git reset --hard` 回退、**不 push**，
   并在汇报里点名具体编译错误文件。

## 关联

- `tools/module-progress/SYNC-MAIN.md` —— 本轮执行依据的协议（集成头内那份）
- `tools/module-progress/PIPELINE.md` —— 完整流水线协议（与 SYNC-MAIN 冲突时以它为准）
- `docs/sessions/2026-10/2026-10-20-0415-main-sync-no-new-commits.md` —— 上一轮（闸一次即得，零改动）
- `docs/sessions/2026-10/2026-10-20-0315-main-sync-gate-contention-no-update.md` —— 03:15 轮（等闸 22 分钟）
- `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` —— §六之三：batch-33 L2 第一次红的根因（时钟漂移）
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）

## 闸

已 `gate release`（本轮 09:44:42 确实 `acquire` 成功之后才释放；
写本文档前曾二次 `acquire` 并在提交后再次释放）。
