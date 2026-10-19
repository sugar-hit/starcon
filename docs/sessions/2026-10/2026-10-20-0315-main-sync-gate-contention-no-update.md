# 2026-10-20 03:15: main → 集成头同步：等闸 22 分钟，main 无新提交，零改动

> 本轮**未 merge、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「无新提交」**。
> 本轮最有价值的新取证是一对**正反证据**：
> **owner 里的 PID 早就死了，但持闸的 tick 活得很好** —— 闸的活性证据必须来自闸外。

## 摘要

03:15 触发本轮时，调度闸被一个**活着的流水线 tick** 持有着（`gate=held`, `stale=false`, age=369s，
持闸者 03:08:58 acquire）。按协议不抢、不清、不 force，只等。

等待期间用 `ps` 拿到了**决定性的活性证据**：该 tick 正在**同一个 worktree** 里跑 `mvnw verify`
（step 1/5，surefire 从 warehouse 推进到 trade），而 `.dev-agent/dispatch.gate/owner` 里记的 PID `15173`
**查无此进程**。⇒ 「owner PID 不存在」与「持闸者活着」两件事在本轮**同时成立**。

03:37:44 本轮 `gate acquire` 成功（owner pid 25887），比上一轮（02:36 那轮）释放闸的时刻 03:36:18 **晚 86 秒**。

拿到闸后 `git fetch origin --prune` **无任何 ref 更新**，`origin/main` 仍停在 `1bb270a0`（batch-28 的 tip），
且它**本就是集成头的祖先** ⇒ `merge-base --is-ancestor origin/main integration/module-dev` 判定为真
⇒ **要同步的内容为零**，本轮零改动。

## 决策 / 结论

### 1. 🔑 owner 的 PID 不构成闸存活证据（本轮拿到正反两面）

| 证据 | 观测 | 指向 |
| --- | --- | --- |
| `.dev-agent/dispatch.gate/owner` 里的 PID | `15173` → `ps -p 15173` **查无此进程** | 像是"持闸者已死" |
| 同 worktree 里的 `mvnw verify` | PID `15347`，`-Dmaven.multiModuleProjectDirectory=…/integration/backend` | **持闸者活着** |

两件事同时为真 ⇒ 「owner PID 不存在」**不构成**持闸者已死的证据。
原因：`gate acquire` 是**短命进程**，写完 `owner` 就退出；真正的持闸者是后续长时间跑的调度器。
⇒ 判活**只能**用 `gate status` 的 `stale` 字段（协议明文），**不能**看 PID。
（02:36 轮已从「owner PID 查无此进程但闸确实被活着的人持有」观察到这一点，本轮补上了正反两面。）

### 2. 🔑 活性证据要取自「闸外」：同 worktree 里的构建进程

判定持闸者是否活着，**不要**只看 `.dev-agent/` 里的文件，要看**它正在做什么**：

```
$ ps -eo pid,etime,pcpu,command | grep -E 'surefirebooter|mvnw|classworlds'
15347  13:11  … { … cd …/starcon-worktrees/integration && { sync.mjs; cd backend && ./mvnw verify; … } }
15354  13:11  … -Dmaven.multiModuleProjectDirectory=/Users/guan/Projects/starcon-worktrees/integration/backend … Launcher verify
17365   6:55  …/openjdk-25.0.2/…/surefirebooter-…jar …/services/warehouse/target/surefire
```

`maven.multiModuleProjectDirectory` 指向**本集成头 worktree 的 backend** ⇒ 它跑在**我们的 worktree 里**。

### 3. ⛔ 绝不并发第二个构建（本轮差点踩的坑，已避开）

持闸 tick 正跑 `mvnw verify`，与它**共用 `backend/**/target/` 与 PG 容器**。
本轮若在等闸期间跑预检（`./mvnw -q compile`），会与它的 surefire **抢同一批 `target/` 目录**
⇒ 大概率**假红**（2026-10-19 20:00 实测：PG 峰值 16、1364 例里 27 个假红）。

⇒ **预检必须等持闸 tick 彻底结束、本轮拿到闸之后才跑**。等闸期间只做**只读**取证。
本轮等闸期间只做过：`gate status` / `cat owner` / `ps` / `git log` / `git show`（纯读，不取锁）——
**刻意没有 `git fetch`**，避免与持闸 tick 争 `refs/remotes/origin/*` 的锁。

### 4. 等闸要「密集 `acquire` 竞态」，不要「低频 `status` 观测」

02:36 轮实测：闸的交接窗口可以**窄到零宽度**（03:04:02 释放，下一 tick 同一秒重新 acquire），
"每 20 秒 `gate status` → 看到 free 再 `acquire`" 的看门狗**整段错过**，直到 03:36 才拿到闸。

本轮的做法**第一版就是密集竞态**：每 20 秒直接调 `lane.mjs gate acquire`，
**不依赖观测到 free**（`gateAcquire()` 用 `mkdirSync` 做原子互斥：持有且未过期时 mkdir 失败且无副作用；
空闲时 mkdir 成功即取得；`age > TTL` 时按 TTL 接管 —— 与 `gate status` 的 `stale` 是**同一套阈值**）。

实测：02:36 轮 03:36:18 释放 → 本轮 03:37:44 取得，**间隔 86 秒** ⇒ 20 秒密度**足以赢下**这一轮交接。
但 02:36 轮建议的 **1 秒密度更保险**（窗口可能比 86 秒更窄）。
⚠️ 该建议已写回 `tools/module-progress/SYNC-MAIN.md` 第 1 步（本轮一并提交）。

### 5. 没拿到过闸就不要 `gate release`

协议第 6 步写「无论走到哪一步都必须 release」，但它的前提是**本轮持过闸**。
`gate release` 的实现是 `rmSync(GATE_DIR, {recursive:true, force:true})` —— **无条件删闸**，
所以若本轮全程没拿到闸就 release，会把**别人活着的闸删掉**（= cron prompt 禁止的"清闸"），
直接破坏互斥语义。
⇒ **本轮是在 03:37:44 真的 `acquire` 成功之后才 release 的。**

### 6. 判据方向不能反（沿用 01:15 / 02:36 轮的结论，本轮第三次验证）

| 命令 | 本轮结果 | 含义 |
| --- | --- | --- |
| `git rev-list --count integration/module-dev..origin/main` | **0** | ✅ 正确判据：main 没有集成头缺的东西 |
| `git rev-list --count origin/main..integration/module-dev` | **38** | ⚠️ 反向：集成头比 main 多 38 个 commit |

反向那 38 个是**发布侧（module-dev → main）的欠账**，由另一台机器的发布任务负责推。
本轮若误用反向计数，就会凭空造出「38 个 main 提交待并入」的假象。**本任务不代劳、不越界。**

### 7. 预检未触发（NO_UPDATE 分支不跑预检）

协议第 4 步的快速预检（`./mvnw -q compile` + `pnpm typecheck`）**只在 HAS_UPDATE 分支跑**。
本轮 `NO_UPDATE` ⇒ 没有 merge 就没有需要验证的代码 ⇒ **未跑预检、未跑 L2、未产生任何构建产物**。
既省了发热峰值（协议硬规定：不跑全量 `mvnw verify`），也没有把编译不过的集成头推上去的风险。

### 8. 台账 222 不是本轮的功劳

`node tools/module-progress/sync.mjs` 跑完**零 diff**（`git status --porcelain` 为空）⇒ 台账已是最新。
「已交付 218 → 222」全部来自 batch-30 的 `docs(batch-30)` 提交（02:36 轮已归因），**本轮零贡献**。

## 本轮参数（实时读取，非历史文档）

| 项 | 值 | 来源 |
| --- | --- | --- |
| 集成头 worktree | `/Users/guan/Projects/starcon-worktrees/integration` | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端 | `origin` | 同上 → `PUSH_REMOTE` |
| 协议文件 | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**，主工作区那份是旧版） | 本轮实际读取路径 |
| 集成头 head（本轮提交前） | `ba8d2984`（== `origin/integration/module-dev`，零偏差） | `git rev-parse` |
| `origin/main` tip | `1bb270a0`（batch-28 tip，**自 01:15 轮以来未前进**） | `git rev-parse` |
| 车道状态 | 12 条全 `merged` | `.dev-agent/lanes.json` |
| 持闸者 acquire 时刻 | 03:08:58（owner pid 15173，**该 pid 已退出**） | `dispatch.gate/owner` |
| 本轮 acquire 时刻 | **03:37:44**（owner pid 25887） | `dispatch.gate/owner` |
| 本轮等闸时长 | **22 分 37 秒**（03:15:07 → 03:37:44），密集 acquire 轮询第 **65** 次命中 | 计时 |

## 取证

```
$ node tools/module-progress/lane.mjs gate status        # 03:15 首次
{ "gate": "held", "ageSeconds": 369, "ttlSeconds": 4200, "stale": false }

$ ps -p 15173 -o pid=,etime=,comm=
PID 15173 NOT RUNNING                                    # owner pid 已死，但闸被活着的人持有

$ node tools/module-progress/lane.mjs gate acquire        # 03:37:44，第 65 次竞态命中
{ "ok": true, "gate": "acquired" }

$ git fetch origin --prune
(无 ref 更新)

$ git merge-base --is-ancestor origin/main integration/module-dev && echo NO_UPDATE || echo HAS_UPDATE
NO_UPDATE

$ git rev-list --count integration/module-dev..origin/main
0
$ git rev-list --count origin/main..integration/module-dev
38
$ git merge-base origin/main integration/module-dev
1bb270a054f878c56075dfd7f7fbe6a40c8dc851     # == origin/main 的 tip
```

集成头相对 main 的差异规模（**未并入本轮，仅供参照**）：
`122 files changed, 77814 insertions(+), 13325 deletions(-)`。

## 台账现状（`sync.mjs` 实时值，跑完零 diff）

| 指标 | 01:15 轮 | 02:36 轮 | 本轮 | 变化来源 |
| --- | --- | --- | --- | --- |
| 模块总数 | 465 | 465 | 465 | — |
| 已交付 | 218 | 222 | **222** | batch-30（02:36 轮已归因） |
| 进行中 | 0 | 0 | **0** | — |
| 未开发 | 247 | 243 | **243** | 同上 |
| 完成率 | 46.9% | 47.7% | **47.7%** | 同上 |

## JDK 说明（供后续轮次预检参考）

- 本机 `java` 默认 **25.0.2**；另装有 **JDK 21**（`/Users/guan/Library/Java/JavaVirtualMachines/openjdk-21/Contents/Home`）。
- `backend/pom.xml` 固定 `java.version=21` / `maven.compiler.release=21`。
- 持闸 tick 的 L2 实测就跑在**ambient JDK 25** 上并跑通（surefire 命令行可见 `openjdk-25.0.2`）。
- ⇒ 预检按协议原样跑 `./mvnw -q compile`（ambient JDK）即可与流水线 L2 保持同环境。
  ⚠️ 万一预检红，**先判断是代码问题还是 JDK/环境问题**，别把环境问题当成"main 与集成头不兼容"就 `reset --hard`。

## 涉及文件

- `docs/sessions/2026-10/2026-10-20-0315-main-sync-gate-contention-no-update.md` —— 本文件（新增）
- `tools/module-progress/SYNC-MAIN.md` —— 第 1 步补记「等闸用密集 `acquire` 竞态」「owner PID 非活性证据」
  「release 前提是本轮真的持过闸」（策略改动写协议文件，不动 cron prompt 字段）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 跑完零 diff）

## 后续待办

1. **发布侧**：集成头领先 main **38** 个 commit（batch-29 + batch-30 + 各会话文档），
   等发布侧任务推上 main；那之后 main 的 tip 再次成为集成头祖先，sync 任务继续零改动。
2. **等闸方式**：已写回 `SYNC-MAIN.md`，后续 sync 轮次用「密集 `gate acquire` 竞态（建议 1 秒级）」，
   不要用低频 `gate status` 轮询。
3. **下一轮流水线 L2**：本轮**没有**产生 main merge commit ⇒
   协议里「L2 红了先查 main merge commit」这条排查线索本轮**不适用**；
   L2 若红仍应先查 batch-30 的 4 条车道。
4. 若下一轮 main 真正带来集成头缺的内容（`HAS_UPDATE`），预检红 ⇒ `git reset --hard` 回退、**不 push**，
   并在汇报里点名具体编译错误文件。

## 闸

已 `gate release`（本轮 03:37:44 确实 `acquire` 成功之后才释放）。
