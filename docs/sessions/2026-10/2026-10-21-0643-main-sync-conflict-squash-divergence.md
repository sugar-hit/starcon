# 2026-10-21 06:43 main → 集成头同步：等闸 26 分钟，merge 冲突 6 文件，未并入

> 本轮**未 push 业务代码、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「冲突未并入」**。
> 这是 `origin/main` 自 `d28188d6`（2026-10-20 20:39 squash PR #11）之后的**连续第 5 轮 HAS_UPDATE 撞同一堵墙**。

## 一句话结论

06:43 触发时调度闸被**上一轮流水线 tick 的 L2**（后端 `mvnw verify`）持有且 `stale=false`
⇒ 按协议**密集竞态 `gate acquire`**（1 秒一次，不做低频 status 观测），
**第 1568 次尝试（等闸 26 分 08 秒）拿到闸**（owner pid 3515，06:43:39 CST）。

取得闸后 `git fetch origin --prune` 无 ref 更新，`origin/main` 仍是 `d28188d6`，
且它**不是集成头的祖先** ⇒ `merge-base --is-ancestor` 判定为假 ⇒ **HAS_UPDATE**，要同步 1 个提交。

`git merge --no-ff origin/main` 在**同样的 6 个文件上冲突** ⇒ 按协议 `git merge --abort`，
**未自行解冲突、未 push 业务代码**，集成头仍停在 `97c257dc`（与远端零偏差）。

## 本轮参数（实时读取，非历史文档）

| 项                    | 值                                                                                            | 来源                                            |
| --------------------- | --------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 集成头 worktree       | `/Users/guan/Projects/starcon-worktrees/integration`                                          | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端              | `origin`                                                                                      | 同上 → `PUSH_REMOTE`                            |
| 协议文件              | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**）                                      | 本轮实际读取路径                                |
| 集成头 head（合并前） | `97c257dc`（batch-45：5.3.2.2 / 5.4.1.2 / 6.2.1.2 / 6.1.2.3 并入 + L2 绿）                    | `git rev-parse`                                 |
| `origin/main` tip     | `d28188d6`（Integration/module dev (#11)，**GitHub squash 合并**，2026-10-20 20:39:44 +0800） | `git log -1 origin/main`                        |
| merge-base            | `1bb270a0`（batch-28 tip）                                                                    | `git merge-base`                                |
| 本轮 acquire 时刻     | **06:43:39 CST**（owner pid 3515，**第 1568 次尝试**）                                        | `dispatch.gate/owner`                           |
| 本轮等闸时长          | **26 分 08 秒**                                                                               | 竞态循环计数                                    |
| 台账（合并前 / 后）   | 465 个 L4 模块：**262 已交付 · 0 进行中 · 203 未开发**（完成率 56.3%）                        | `sync.mjs` + 台账 `## 总览` 表                  |

## 取证一：本轮闸是被**流水线 L2** 持有，不是陈旧残留

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 376, "ttlSeconds": 4200, "stale": false }
```

`held + stale=false` ⇒ **有活着的流水线 tick 持闸** ⇒ 等它释放，不抢、不清、不 force。

活性取证（闸外）：`ps` 看到 `mvnw verify` 正在同一 worktree 里跑（后端 L2），
`.tmp-l2-backend.log` 里 `Tests run:` 行持续增长（215 → 251 个测试类），
前端 L2 脚本 `.tmp-l2-frontend.sh` 在等 `BACKEND_EXIT=0`。

⚠️ **`ageSeconds` 中途掉到 23 又升回** —— 那**不是**换人，是同一 tick 的 **keepalive 续期**
（`dispatch.gate/` 里多出 `keepalive-061842`，`owner` 文件始终是 pid 77593）。
⇒ 判活只能看 `stale`，不能按 `ageSeconds` 猜（协议明写）。

⚠️ **等闸期间没跑第二个构建** —— 协议写死：持闸 tick 的 L2 与本任务的预检共用
`backend/**/target/` 与 PG 容器 ⇒ 并发必假红。等闸期间只做只读取证
（`gate status` / `cat owner` / `ps` / `git log`），**连 `git fetch` 都等到拿到闸之后才跑**。

⚠️ 竞态脚本按协议写成**密集 `acquire`**（1 秒一次），
而不是「每 20 秒 status 观测到 free 再 acquire」—— 后者会整段错过零宽度交接窗口。
本轮实测：tick 在 06:43 释放，本轮第 1568 次尝试（26 分 08 秒后）命中。

## 取证二：**独立重验**「main 的树 == 集成头祖先的树」—— 不采信历史会话日志

协议要求「所有判断必须从仓库现状重新取证，不能凭记忆」。本轮**重新跑了一遍**：

```
$ git rev-parse origin/main^{tree}
06607694b31c6fdfe354ed4783c22d4a0e0b8c20
$ git rev-parse d22bc1e1^{tree}
06607694b31c6fdfe354ed4783c22d4a0e0b8c20     ← 逐字节相同

# 在集成头全部 207 个历史提交里逐一比对树哈希：
$ for c in $(git rev-list integration/module-dev); do
    [ "$(git rev-parse $c^{tree})" = "$MAIN_TREE" ] && git log --oneline -1 $c
  done
d22bc1e1 docs(batch-37): 10.3.1.2 / 10.3.2.2 / 10.6.2.1 / 10.7.1.2 —— 四车道并入 + L2 绿的修复与验证
```

⇒ **`origin/main` 的树与集成头自己的祖先提交 `d22bc1e1`（batch-37）逐字节相同**。

推论（本轮报告的**核心**）：**main 上的每一条路径，其内容都是集成头某次历史提交里的内容**，
集成头要么保留了原样、要么已更新到更晚版本
⇒ **main 不可能领先于集成头的任何一行代码**；这次 merge 在**内容上是空转**的。
它之所以炸成冲突，纯粹是 squash 抹掉血缘，把 merge-base 拉回 `1bb270a0`，
让 git 把同一份工作当成「两边各自新增」。

⚠️ 那条 `406 files changed, 262390 insertions(+)` 的 diffstat 是
**squash 把 85 个 commit 压成一颗造成的行数重放**，不是真有 26 万行新代码 —— **不要按它判断工作量**。

## 取证三：merge 冲突 6 个文件，与前四轮完全一致

真实 `git merge --no-ff origin/main`：

```
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
Automatic merge failed; fix conflicts and then commit the result.
```

本轮用 `git cat-file` 把冲突类型拆到底（base / ours / theirs 三段行数）：

| #   | 文件                                                                                               | merge-base 上是否存在 | 冲突类型 | ours(集成头) / theirs(main) 行数 |
| --- | -------------------------------------------------------------------------------------------------- | --------------------- | -------- | -------------------------------- |
| 1   | `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`                                      | **不存在**            | add/add  | 94 / 72                          |
| 2   | `apps/web/src/components/pricing/cost-composition-model.test.ts`                                   | **不存在**            | add/add  | 361 / 349                        |
| 3   | `apps/web/src/components/pricing/cost-composition-model.ts`                                        | **不存在**            | add/add  | 615 / 614                        |
| 4   | `apps/web/src/components/pricing/cost-composition-workspace.tsx`                                   | **不存在**            | add/add  | 1047 / 1034                      |
| 5   | `apps/web/src/lib/api-proxy.ts`                                                                    | 存在                  | content  | 4753 / 3867                      |
| 6   | `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java` | **不存在**            | add/add  | 1256 / 1124                      |

**5 个 add/add 的成因一句话讲清**：这 5 个文件在 merge-base（batch-28）上**根本不存在**，
是集成头在 batch-28 之后**新增**的；而 squash 让 main 也「新增」了同路径的文件
（main 那份是集成头早期的版本）⇒ git 判成「两边各自新增」⇒ add/add。

## 取证四：main 侧独有文本的完整分布 —— 15 个文件，逐个归类

把「main → 集成头」方向上 **main 有、集成头没有的行**全部列出来
（`git diff --numstat origin/main HEAD` 的第二列 > 0），共 **15 个文件**：

| 归类                                                                              | 文件数 | 处置结果        |
| --------------------------------------------------------------------------------- | ------ | --------------- |
| 由 `merge-union` 自动求并（契约 7 个 `*.openapi.json` / `contracts/*.ts` + 台账） | 8      | ✅ 自动合上     |
| git 三方合并干净合上（非契约、非台账）                                            | 3      | ✅ 自动合上     |
| **构成冲突**（main 侧确有独有文本，故三方合不了）                                 | 4      | ❌ abort + 上报 |
| **不在这 15 个之内但仍冲突**（main 独有行 = 0，集成头严格超集）                   | 2      | ❌ abort + 上报 |

那 3 个 git 干净合上的文件是：
`apps/web/src/app/api/crm/[...path]/route.ts`（main 独有 4 行）、
`backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRequestService.java`（90 行）、
`backend/services/trade/src/test/java/com/starcon/trade/FulfillmentRequestIntegrationTest.java`（2 行）。

⚠️ **另有 2 个冲突文件不在上面这 15 个之内** ——
`cost-composition-workspace.tsx` 与 `api-proxy.ts` 的 **main 独有行 = 0**（集成头是严格超集），
它们之所以仍冲突，纯粹是因为 **add/add**（两边都「新增」同一条路径，git 无从判断谁更全）。
所以**冲突文件总数 = 4（在这 15 个里）+ 2（严格超集，不在这 15 个里）= 6**。

⇒ **main 侧的「独有文本」全部落在已重构/已收口的旧写法上，没有一处是 main 侧的新模块交付**。

## 取证五：台账逐行比对 ⇒ main 侧**零新交付**，且比集成头落后 **20** 个模块

直接读两个快照各自的 `## 总览` 表（不去数全文里的状态词 —— 那会把说明文字里的
「已交付 / 未开发 / 进行中」也数进去，得到错数）：

```
$ git show origin/main:docs/product/capability-blueprint/module-progress.md | sed -n '/^## 总览/,/^## /p'
| 模块总数 | 465 |
| 已交付   | 242 |   ← 52.0%
| 进行中   | 0   |
| 未开发   | 223 |

$ sed -n '/^## 总览/,/^## /p' docs/product/capability-blueprint/module-progress.md
| 模块总数 | 465 |
| 已交付   | 262 |   ← 56.3%
| 进行中   | 0   |
| 未开发   | 203 |
```

| 指标     | main 快照 `d28188d6` | 集成头 `97c257dc` | 变化    |
| -------- | -------------------- | ----------------- | ------- |
| 模块总数 | 465                  | 465               | —       |
| 已交付   | **242**（52.0%）     | **262**（56.3%）  | **+20** |
| 进行中   | 0                    | 0                 | ±0      |
| 未开发   | 223                  | 203               | −20     |

⚠️ 差距从上一轮的 16 扩大到 **20** —— 差的正是 batch-44 / batch-45 新交付的 4 个模块
（5.3.2.2 价格规则 / 5.4.1.2 批量调价 / 6.2.1.2 补货建议 / 6.1.2.3 供应商资质）。
**main 侧不前进 ⇒ 差距只增不减。** 这本身就是「squash 快照已经过时」的最好证据。

⇒ **main 没有任何一个模块是集成头没有的。** 本任务想同步的内容，**集成头已经有了（且更新）**。

## abort 后的状态核验

```
$ git merge --abort            # ABORT_EXIT=0
$ git status --porcelain       # 空
$ git rev-parse HEAD           # 97c257dc74bc8ab734a06b3e7c29ee652f42079a   （= 合并前 PRE）
$ git rev-parse --verify MERGE_HEAD   # 失败 ⇒ MERGE_HEAD 已清除，不会堵死流水线的 merge-ready
$ git rev-parse origin/integration/module-dev
97c257dc74bc8ab734a06b3e7c29ee652f42079a   ← 与远端零偏差
```

⇒ 集成头**完全回到合并前**，无残留、无半合并状态，流水线可以正常继续。

## 为什么本轮**没有**跑快速预检

预检（`./mvnw -q compile` + `pnpm typecheck`）在协议里是**合并成功后**验证「合进来的东西能不能编译」的闸。
本轮**合并已 abort，集成头一个字节都没变**（仍是 `97c257dc`），
而这个提交正是上一轮流水线 **batch-45 的 L2 全量验证对象**（`mvnw verify` + 前端全量 + Next build）
⇒ **树与 L2 验证过的内容逐字节相同，再跑一次预检是纯重复**。
⇒ 本轮**不跑**预检、**不 push** 业务代码。合并失败就没有「预检绿才 push」的适用对象。

## 台账变化

| 指标     | 合并前 | 合并后（abort + `sync.mjs`） | 变化 |
| -------- | ------ | ---------------------------- | ---- |
| 模块总数 | 465    | 465                          | —    |
| 已交付   | 262    | 262                          | ±0   |
| 进行中   | 0      | 0                            | ±0   |
| 未开发   | 203    | 203                          | ±0   |
| 完成率   | 56.3%  | 56.3%                        | ±0   |

`node tools/module-progress/sync.mjs` 输出 `✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 262 · 进行中 0 · 未开发 203`，
且 `git status --porcelain` **为空（零 diff）** ⇒ 台账已由 batch-45 刷新到位，本轮无新增。

参照：main 的台账快照是 **已交付 242 / 进行中 0 / 未开发 223**（52.0%），
比集成头少 **20** 个已交付 —— **这是 squash 快照的时差，不是本任务的功劳，也不是本任务的欠账**。

## 关键 trade-off / 讨论

**要不要自己把这 6 个冲突解掉？** **不解。**
协议写死「❌ 自行解冲突」「**冲突不自行解决** —— `git merge --abort` 后把冲突文件名列进汇报」，
AGENTS.md 的模块边界纪律同样禁止 agent 自行裁决。
本轮还多一条证据让「解」更不划算：**main 的树与集成头祖先 `d22bc1e1` 逐字节相同** ⇒ 这次 merge 本质是空转；
即便如此，第 1/2/3/6 个文件上 main 仍有 2–18 行「已被推翻的旧断言」需要逐行核对，
协议不允许，且「大概率对」不构成放行依据。

**要不要在 abort 后还 push 一个「已同步」的空提交？** **不。**
没有合并就没有「已同步」；把 abort 包装成成功会直接毁掉下一棒 L2 的排查依据。
本轮只推**本会话文档 + backlog 更新**（与 01:15 / 03:15 / 04:15 / 05:15 轮同构）。

**本轮与前四轮最大的不同是什么？** **闸是被流水线的 L2 持有的，不是空的。**
前四轮等闸 45 / 40 / 27 / **0** 分钟，本轮等闸 **26 分钟**（持闸者在跑后端 `mvnw verify`）。
⇒ 再次确认：**闸的等待不是本任务的瓶颈**，瓶颈**只有一个**：main 的 tip 是集成头的 squash。
**闸的问题在发布侧改一次就永久消失**，不必再在本任务上做任何优化。

**第 5 轮还撞同一堵墙，要不要改协议？** **不改协议，改发布流程。**
连续 5 轮完全相同的空转 merge 说明：**协议是对的（它正确地挡住了无意义的内容重放），
错的是发布侧把集成头 squash 进了 main**。协议第 6 步「冲突不自行解决」应保留。

## 后续待办

1. **发布侧（本任务的长期阻塞点，连续 5 轮撞同一堵墙）**：main 的 tip 是集成头的 **squash**，
   导致 merge-base 被拉回 `1bb270a0`、6 个文件冲突，**且 main 侧零新交付**。
   **建议发布侧改成推真实 commit（不 squash）**，本任务即可回到「无新提交」的常态。
   已按 backlog 规范落档：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（已更新到第 5 轮）。
2. **修复车道（若坚持要并入 main 这次的内容）**：单独开一条 fix 车道解这 6 个冲突，
   方向「以集成头为准 + 逐行核对 main 那 2–18 行独有内容是否需要保留」，
   **不要用 `git checkout --ours/--theirs` 批量覆盖**。解完**必须重跑 L2**
   （这批内容从未经过本流水线的 L1/L2）。
3. **下一轮流水线的 L2 排查线索**：本轮**没有产生 main merge commit** ⇒ 协议里
   「L2 红了先查 main merge commit」这条**本轮不适用**；L2 若红，仍应先查 batch-45 的四条车道
   （5.3.2.2 / 5.4.1.2 / 6.2.1.2 / 6.1.2.3）。
4. **同步任务的收尾保障**：本轮 acquire 成功 1 次 / release 1 次，**配平**；
   2026-10-21 02:15 那个回合被截断时既没写日志也没 release（闸泄漏 70 分钟）的问题**本轮未复现**。

## 涉及文件

- `docs/sessions/2026-10/2026-10-21-0643-main-sync-conflict-squash-divergence.md` —— 本文件（新增，单独 docs commit）
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— **更新**（轮次 4 → 5，补本轮取证）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 零 diff）
- 业务代码 —— **零改动**（合并已 abort）

## 关联

- `tools/module-progress/SYNC-MAIN.md` —— 本轮执行依据的协议（集成头内那份）
- `tools/module-progress/PIPELINE.md` —— 完整流水线协议（与 SYNC-MAIN 冲突时以它为准）
- `docs/sessions/2026-10/2026-10-21-0515-main-sync-conflict-squash-divergence.md` —— 上一轮（**同样 6 文件冲突**，等闸 0 分钟）
- `docs/sessions/2026-10/2026-10-21-0415-main-sync-conflict-squash-divergence.md` —— 再上一轮（**同样 6 文件冲突**，等闸 27 分钟）
- `docs/sessions/2026-10/2026-10-21-0315-main-sync-conflict-squash-divergence.md` —— 再再上一轮（**同样 6 文件冲突**，等闸 40 分钟）
- `docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` —— 再再再上一轮（**同样 6 文件冲突**，等闸 45 分钟）
- `docs/sessions/2026-10/2026-10-21-batch-45-pricing-rules-and-sourcing.md` —— batch-45（四车道并入 + L2 全绿）
- `docs/backlog/2026-10/2026-10-21-api-proxy-shared-file-merge-conflict.md` —— `api-proxy.ts` 共享白名单文件的结构性冲突根因
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）

## 闸

已 `gate release`。本轮 **06:43:39 确实 `acquire` 成功之后**才释放（acquire 成功 1 次、release 1 次，配平）。
全程从未 release 过别人持有的闸 ⇒ 没有误删活着的闸。
