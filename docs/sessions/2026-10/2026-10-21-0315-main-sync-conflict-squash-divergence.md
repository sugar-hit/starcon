# 2026-10-21 03:15 main → 集成头同步：等闸 40 分钟，merge 冲突 6 文件，未并入

> 本轮**未 push 业务代码、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「冲突未并入」**。
> 这是 `origin/main` 自 `d28188d6`（2026-10-20 20:39 squash PR #11）之后的**连续第 2 轮 HAS_UPDATE 撞同一堵墙**。

## 一句话结论

03:15 触发时调度闸 `gate=held`（`stale=false`，`ageSeconds=1796`），持闸者是 **02:15 触发的
`main → module-dev` 同步任务**（owner pid 13682 @ 02:45:29）—— 那个回合在 02:47:10 被截断，
**既没 `gate release` 也没写会话日志**，闸就这么泄漏了。

按协议用**密集 `acquire` 竞态**等待，**2092 次尝试、40 分钟后在 03:55:30 取得闸**
（TTL 4200s 到点 ⇒ `gateAcquire` 走「超 TTL 接管」分支自动夺回）。

取得闸后 `git fetch origin --prune` 无 ref 更新，`origin/main` 仍是 `d28188d6`，
且它**不是集成头的祖先** ⇒ `merge-base --is-ancestor` 判定为假 ⇒ **HAS_UPDATE**，要同步 1 个提交。

`git merge --no-ff origin/main` 在**同样的 6 个文件上冲突** ⇒ 按协议 `git merge --abort`，
**未自行解冲突、未 push**，集成头仍停在 `eea8b60a`（与远端零偏差）。

## 本轮参数（实时读取，非历史文档）

| 项                    | 值                                                                                            | 来源                                            |
| --------------------- | --------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 集成头 worktree       | `/Users/guan/Projects/starcon-worktrees/integration`                                          | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端              | `origin`                                                                                      | 同上 → `PUSH_REMOTE`                            |
| 协议文件              | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**）                                      | 本轮实际读取路径                                |
| 集成头 head（合并前） | `eea8b60a`（batch-42：零派发只读轮）                                                          | `git rev-parse`                                 |
| `origin/main` tip     | `d28188d6`（Integration/module dev (#11)，**GitHub squash 合并**，2026-10-20 20:39:44 +0800） | `git log -1 origin/main`                        |
| merge-base            | `1bb270a0`（batch-28 tip）                                                                    | `git merge-base`                                |
| 本轮 acquire 时刻     | **03:55:30**（owner pid 37077，2092 次尝试）                                                  | `dispatch.gate/owner`                           |
| 本轮等闸时长          | **约 40 分钟**（03:15 → 03:55:30）                                                            | 竞态脚本计数                                    |
| 台账（合并前 / 后）   | 465 个 L4 模块：**252 已交付 · 0 进行中 · 213 未开发**（完成率 54.2%）                        | `sync.mjs` 输出                                 |

## 取证一：闸被 02:15 那个被截断的同步任务泄漏，等了 40 分钟

```
$ node tools/module-progress/lane.mjs gate status        # 03:15 首次
{ "gate": "held", "ageSeconds": 1796, "ttlSeconds": 4200, "stale": false }
```

`held + stale=false` ⇒ 按协议**等它释放**，不抢、不清、不 force。

**活性取证（闸外）**：`ps` 查 `mvnw|maven|surefire|java` ⇒ **零命中**，同 worktree 里没有任何
构建进程在跑；`owner` 里的 pid 13682 **查无此进程**。
⇒ 持闸者是**已死但未超 TTL**的泄漏闸 —— 这与 batch-42 会话日志记录的根因一致：
02:15 那个同步任务 02:45:29 取得闸 → merge 冲突 6 文件 → `git merge --abort` 干净回退
→ 回合在 02:47:10 被截断，**既没写会话日志，也没 `gate release`** ⇒ 闸泄漏到 03:55:29 超 TTL。

⚠️ **判活只能用 `stale` 字段**（协议与 `lane.mjs` 注释都明写）。
本轮 `stale` 全程 `false`（03:21 age=2182 → 03:49 age=3823），直到 TTL 到点才由 `gateAcquire`
的「超 TTL 接管」分支夺回。

**竞态等待（协议第 1 步「等闸」）**：

```bash
for i in $(seq 1 3000); do
  if node tools/module-progress/lane.mjs gate acquire 2>/dev/null | grep -q '"ok": true'; then
    echo "GATE ACQUIRED"; break
  fi
  sleep 1
done
```

⇒ **03:55:30 取得闸（第 2092 次尝试）**，`owner` 写成 pid 37077。

⚠️ **等闸期间没跑任何构建**（L2 与预检共用 `backend/**/target/` 与 PG 容器 ⇒ 并发必假红），
只做**只读取证**：`gate status` / `cat owner` / `ps` / `git log` / `git merge-tree` /
台账解析 / `sync.mjs --check`，**连 `git fetch` 都等到拿到闸之后才跑**。

## 取证二：main 有新提交，但它是集成头自己的 squash 快照

```
$ git fetch origin --prune        # 无 ref 更新
$ git merge-base --is-ancestor origin/main integration/module-dev ; echo $?
1        # ⇒ origin/main 不是集成头的祖先 ⇒ HAS_UPDATE

$ git log --oneline integration/module-dev..origin/main
d28188d6 Integration/module dev (#11)
```

`d28188d6` 的形态：

```
commit d28188d63337a2eda641c1ee1e93db77039786b6
Author: Tommy <...>
Commit: GitHub <noreply@github.com>
    Integration/module dev (#11)
parents: 1bb270a0        # 只有一个父提交 ⇒ squash 合并
```

⇒ **main 的 tip 是「集成头分支被 GitHub squash 进 main」的产物**，不是 main 侧的新交付。

**要同步的规模**：`git diff --stat integration/module-dev...origin/main`
⇒ `406 files changed, 262390 insertions(+), 34181 deletions(-)`
（这个数字看着吓人，是 squash 把 85 个 commit 压成一颗造成的**行数重放**，不是真有 26 万行新代码。）

## 取证三：台账逐行比对 ⇒ main 侧**零新交付**，且比集成头落后 10 个模块

这是本轮最关键的一条证据，**推翻「main 有新东西可并」的直觉**。

按 L4 模块 ID 逐行比对 main 快照与集成头的台账（465 行，两边**模块集合完全相同**，
`only in main = []`、`only in head = []`），**状态不同的有 10 个，方向全部一致**：

| 模块 ID  | 名称             | main 快照 | 集成头     |
| -------- | ---------------- | --------- | ---------- |
| 5.2.1.2  | 到岸成本         | 未开发    | **已交付** |
| 5.2.1.3  | 成本分摊         | 未开发    | **已交付** |
| 5.2.2.1  | 汇率查询         | 未开发    | **已交付** |
| 5.2.2.2  | 汇率快照         | 未开发    | **已交付** |
| 5.2.3.1  | 平台费用         | 未开发    | **已交付** |
| 5.3.1.1  | 利润测算器       | 未开发    | **已交付** |
| 5.5.2.1  | 异常告警         | 未开发    | **已交付** |
| 10.7.1.3 | 弃置与销毁       | 未开发    | **已交付** |
| 15.2.2.1 | Request a Review | 未开发    | **已交付** |
| 15.2.2.2 | 索评规则         | 未开发    | **已交付** |

台账汇总口径同样吻合：main 快照 **242 已交付 / 0 进行中 / 223 未开发**，
集成头 **252 / 0 / 213**（完成率 54.2%）。

⇒ **main 没有任何一个模块是集成头没有的**；main 是集成头**早 10 个模块的旧快照**。
本任务想同步的内容，**集成头已经有了（且更新）**。

## 取证四：merge 冲突 6 个文件，与 01:15 轮完全一致

**动手前先用只读的 `git merge-tree --write-tree` 干跑**（不碰工作区、不碰索引），
预测到的冲突集合与真实 merge **完全一致**（6 个文件，无遗漏无多余）。

真实 `git merge --no-ff origin/main` 的结果：

```
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
Automatic merge failed; fix conflicts and then commit the result.
```

✅ **契约与台账两类文件由 `merge-union` 驱动自动求并成功**（406 个文件里的绝大部分）：
`packages/api-contracts/src/contracts/{catalog,channel}.ts`、
`packages/api-contracts/src/{catalog,channel,trade,warehouse,crm}.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`（按模块 ID 求并集，465 行同 ID 记录折叠）。

❌ **6 个文件冲突**（协议规定**不自行解**）：

| #   | 文件                                                                                               | 冲突类型 | 冲突块数 | main 独有行 / 集成头独有行 |
| --- | -------------------------------------------------------------------------------------------------- | -------- | -------- | -------------------------- |
| 1   | `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`                                      | add/add  | 3        | +17 / −38                  |
| 2   | `apps/web/src/components/pricing/cost-composition-model.test.ts`                                   | add/add  | 1        | +7 / −18                   |
| 3   | `apps/web/src/components/pricing/cost-composition-model.ts`                                        | add/add  | 2        | +7 / −8                    |
| 4   | `apps/web/src/components/pricing/cost-composition-workspace.tsx`                                   | add/add  | 3        | +0 / −11                   |
| 5   | `apps/web/src/lib/api-proxy.ts`                                                                    | content  | 3        | +0 / −435                  |
| 6   | `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java` | add/add  | 8        | +2 / −125                  |

（「+N」= main 侧独有行数，「−N」= 集成头独有行数）

**abort 后的状态核验**：

```
$ git merge --abort
$ git status --porcelain        # 空
$ git rev-parse HEAD            # eea8b60abac94b0d30f714d3f987c5b48b24e4dc
$ test -f .git/MERGE_HEAD       # NO   ← 没有 MERGE_HEAD 残留，不会堵死流水线的 merge-ready
$ git rev-list --left-right --count origin/integration/module-dev...integration/module-dev
0	0                            # 与远端零偏差
```

⇒ 集成头**完全回到合并前**，无残留、无半合并状态，流水线可以正常继续。

## 取证五：6 个冲突文件上，main 那一侧是**过期快照**，不是新交付

这是本轮新增的取证（01:15 轮只统计了行数，没定性）。逐文件看 main 独有的内容：

| 文件                                    | main 独有内容是什么                                                                                         | 集成头现状                                                                                                                               |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `cost-composition-model.ts`             | `delivered: false` + 「🔴 5.2.1.2 **未交付**」/「🔴 5.2.1.3 **未交付**」的摘要文案                          | 5.2.1.2 / 5.2.1.3 **都已交付**，`delivered: true`                                                                                        |
| `cost-composition-model.test.ts`        | 断言 5.2.1.2 / 5.2.1.3 `delivered === false` 且摘要含「未交付」                                             | 集成头改成**随交付状态翻转**的断言，且明确不断言未交付分区的具体关键词                                                                   |
| `cost-composition-page.test.ts`         | 同上的「未交付」断言 + 三个 moduleId 的固定列表断言                                                         | 集成头已改为「已交付 ⇒ 摘要不得再写未交付；未交付 ⇒ 必须写明未交付」的通用断言                                                           |
| `ListingPublishJobIntegrationTest.java` | `OffsetDateTime before = now().minusSeconds(2); assertThat(dispatchedAt()).isAfter(before)`（JVM 墙钟基准） | 集成头已用 **DB 时钟同源**重写，并**专门写了一条 `assertThatThrownBy` 把这条旧写法钉死成必红**（跨时钟域假红的修复，batch-38/39 的成果） |
| `cost-composition-workspace.tsx`        | **0 行**（严格超集）                                                                                        | —                                                                                                                                        |
| `api-proxy.ts`                          | **0 行**（`services[]` 元素：main 100 条 vs 集成头 110 条）                                                 | 严格超集                                                                                                                                 |

⇒ **这 6 个文件上，main 那一侧是集成头自己工作的旧快照**，不是 main 侧的新交付；
其中 2 个文件（4、5）集成头是**严格超集**，另 4 个文件里 main 的「独有内容」是
**已经被后续交付推翻的旧断言**（集成头甚至把其中一条旧写法钉成必红）。

⚠️ **但这仍不等于「全选 ours 就是对的」**：第 1/2/3/6 个文件里 main 确实各有 2–17 行
集成头没有的**文本**，是否要保留必须逐文件人工看（例如 `cost-composition-model.ts` 里
「未交付」文案换成「已交付」后，兄弟模块的口径说明措辞可能还需要保留）。
**批量取一边 = 埋雷**，所以本轮仍然 abort + 上报。

## 台账变化

| 指标     | 合并前 | 合并后（abort + `sync.mjs`） | 变化 |
| -------- | ------ | ---------------------------- | ---- |
| 模块总数 | 465    | 465                          | —    |
| 已交付   | 252    | 252                          | ±0   |
| 进行中   | 0      | 0                            | ±0   |
| 未开发   | 213    | 213                          | ±0   |
| 完成率   | 54.2%  | 54.2%                        | ±0   |

`node tools/module-progress/sync.mjs` 输出：`✓ 同步 18 卷 / 465 个 L4 模块　已交付 252 · 进行中 0 · 未开发 213`，
**跑完零 diff**（`git status --porcelain` 为空）⇒ 台账已由 batch-41 刷新到位，本轮无新增。

参照：main 的台账快照是 **已交付 242 / 进行中 0 / 未开发 223**（完成率 52.0%），
比集成头少 10 个已交付 —— **这是 squash 快照的时差，不是本任务的功劳，也不是本任务的欠账**。

## 关键 trade-off / 讨论

**要不要自己把这 6 个冲突解掉？** **不解。**
协议写死「❌ 自行解冲突」「**冲突不自行解决** —— `git merge --abort` 后把冲突文件名列进汇报」。
本轮还多了一条证据让「解」更不划算：main 侧那几行独有内容是**过期断言**，
全取 ours 在这 6 个文件上大概率是对的，但协议不允许，且「大概率对」不构成放行依据。

**要不要在 abort 后还 push 一个「已同步」的空提交？** **不。**
没有合并就没有「已同步」；把 abort 包装成成功会直接毁掉下一棒 L2 的排查依据。
本轮只推**本会话文档**（与 01:15 / 12:15 / 09:15 / 04:15 轮同构）。

**为什么先跑 `git merge-tree` 干跑？** 因为它是**只读**的（不碰工作区、不碰索引），
能在动手前就拿到确切的冲突集合 ⇒ 对「会不会冲突」的判断是**取证来的，不是猜的**，
且真实 merge 的结果与干跑**逐条吻合**。

**为什么等闸等 40 分钟而不抢闸？** `stale=false` ⇒ 协议要求等。
本轮 `ps` 查不到任何构建进程、owner pid 也已死 —— 但**判据只能是 `stale` 字段**，
不能自己按 PID / ageSeconds 猜。抢闸 ⇒ 两个 tick 同时改集成头、同时跑 L2、抢
Testcontainers ⇒ 成片假红。**宁可等一轮，也不能双派发**。

## 后续待办

1. **发布侧（本任务的长期阻塞点，连续 2 轮撞同一堵墙）**：main 的 tip 是集成头的 **squash**，
   导致 merge-base 被拉回 `1bb270a0`、6 个文件 add/add 冲突，**且 main 侧零新交付**。
   **建议发布侧改成推真实 commit（不 squash）**，本任务即可回到「无新提交」的常态；
   否则每一轮 main 前进都会撞同一堵墙。
   已按 backlog 规范落档：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **同步任务的收尾保障（连续 2 轮暴露）**：02:15 那个回合被截断时**既没写会话日志也没
   `gate release`**，直接泄漏了一个 70 分钟的闸，白挡了 03:00 轮与本轮。
   建议在协议第 6 步加一道「回合被截断也要 release」的兜底（或由调度器在回合异常结束时
   统一 release）。
3. **修复车道（若坚持要并入 main 这次的内容）**：单独开一条 fix 车道解这 6 个冲突，
   方向「以集成头为准 + 逐行核对 main 那 2–17 行独有内容是否需要保留」，
   解完**必须重跑 L2**（这批内容从未经过本流水线的 L1/L2）。
4. **下一轮流水线的 L2 排查线索**：本轮**没有产生 main merge commit** ⇒ 协议里
   「L2 红了先查 main merge commit」这条**本轮不适用**；L2 若红，仍应先查 batch-41 的两条车道
   （15.2.2.2 索评规则 / 5.2.2.2 汇率快照）与 batch-40 的五条车道。
5. **闸的使用节奏（本轮数据点）**：`held + stale=false` ⇒ 密集竞态等待，实测 **40 分钟**拿到；
   历史参考区间 20–45 分钟。**TTL 超点接管是可靠兜底**（`gateAcquire` 的超龄分支）。

## 涉及文件

- `docs/sessions/2026-10/2026-10-21-0315-main-sync-conflict-squash-divergence.md` —— 本文件（新增，单独 docs commit）
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— **新增**（本轮取证的根因落档）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 跑完零 diff）
- 业务代码 —— **零改动**（合并已 abort）

## 关联

- `tools/module-progress/SYNC-MAIN.md` —— 本轮执行依据的协议（集成头内那份）
- `tools/module-progress/PIPELINE.md` —— 完整流水线协议（与 SYNC-MAIN 冲突时以它为准）
- `docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` —— 上一轮（**同样 6 文件冲突**，等闸 45 分钟）
- `docs/sessions/2026-10/2026-10-21-batch-42-gate-collision-readonly.md` —— batch-42 轮（记录了 02:15 同步任务被截断、闸泄漏的根因）
- `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md` —— `api-proxy.ts` 共享白名单文件的结构性冲突根因
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）

## 闸

已 `gate release`（本轮 **03:55:30 确实 `acquire` 成功之后**才释放；
等闸期间从未 release 过 ⇒ 没有误删别人活着的闸）。
