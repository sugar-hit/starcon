# 2026-10-21 05:15 main → 集成头同步：闸一次拿到，merge 冲突 6 文件，未并入

> 本轮**未 push 业务代码、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「冲突未并入」**。
> 这是 `origin/main` 自 `d28188d6`（2026-10-20 20:39 squash PR #11）之后的**连续第 4 轮 HAS_UPDATE 撞同一堵墙**。

## 一句话结论

05:15 触发时调度闸 `gate=free`（`stale=false`）⇒ **第 1 次尝试就 `acquire` 成功**（owner pid 70686，
05:15:17 CST），**本轮没有等闸**（与 01:15 / 03:15 / 04:15 三轮的 45 / 40 / 27 分钟等待形成鲜明对照）。

取得闸后 `git fetch origin --prune` 无 ref 更新，`origin/main` 仍是 `d28188d6`，
且它**不是集成头的祖先** ⇒ `merge-base --is-ancestor` 判定为假 ⇒ **HAS_UPDATE**，要同步 1 个提交。

`git merge --no-ff origin/main` 在**同样的 6 个文件上冲突** ⇒ 按协议 `git merge --abort`，
**未自行解冲突、未 push 业务代码**，集成头仍停在 `aa986d83`（与远端零偏差）。

## 本轮参数（实时读取，非历史文档）

| 项                    | 值                                                                                            | 来源                                            |
| --------------------- | --------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 集成头 worktree       | `/Users/guan/Projects/starcon-worktrees/integration`                                          | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端              | `origin`                                                                                      | 同上 → `PUSH_REMOTE`                            |
| 协议文件              | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**）                                      | 本轮实际读取路径                                |
| 集成头 head（合并前） | `aa986d83`（batch-44：零派发只读轮 —— 节流闸拦截，本轮并入/派发/L2 均未执行）                 | `git rev-parse`                                 |
| `origin/main` tip     | `d28188d6`（Integration/module dev (#11)，**GitHub squash 合并**，2026-10-20 20:39:44 +0800） | `git log -1 origin/main`                        |
| merge-base            | `1bb270a0`（batch-28 tip）                                                                    | `git merge-base`                                |
| 本轮 acquire 时刻     | **05:15:17 CST**（owner pid 70686，**第 1 次尝试**）                                          | `dispatch.gate/owner`                           |
| 本轮等闸时长          | **0**（闸本来就是空的）                                                                       | `gate status` → `free`                          |
| 台账（合并前 / 后）   | 465 个 L4 模块：**258 已交付 · 0 进行中 · 207 未开发**（完成率 55.5%）                        | `sync.mjs` + 台账 `## 总览` 表                   |

## 取证一：闸是空的，第 1 次尝试就拿到 —— 与前三轮的等待形成对照

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "free", "ageSeconds": null, "ttlSeconds": 4200, "stale": false }
```

`free + stale=false` ⇒ 按协议直接 `gate acquire` 继续。**没有等闸，也就不存在「等闸期间跑第二个构建」
的风险**（本轮全程只跑了一次 `git merge` 与两次只读取证，没碰 `backend/**/target/`）。

竞态脚本仍然按协议写（`acquire` 失败才 sleep 重试），只是**第 1 次就命中**：

```
GATE ACQUIRED (attempt 1)
--- status after acquire ---
{ "gate": "held", "ageSeconds": 0, "ttlSeconds": 4200, "stale": false }
```

⚠️ **本轮特意没有沿用前三轮「先 20 秒低频 status 观测」的写法**，直接用密集竞态 ——
按协议的说法，交接窗口可以窄到零宽度（2026-10-20 03:04:02 实测过同一秒释放又 reacquire），
低频观测会整段错过。本轮闸本来是空的，但**写法本身不该退化**。

⚠️ 拿到闸之后**没有在 merge 阶段结束时提前 release**，而是一直持有到 push 完才 release ——
本轮 acquire 成功 1 次、release 1 次，**配平**，没有误删别人活着的闸。

## 取证二：main 的 tip 仍是那颗 squash，merge-base 仍被拉回 `1bb270a0`

```
$ git fetch origin --prune            # 无 ref 更新
$ git merge-base --is-ancestor origin/main integration/module-dev ; echo $?
1        # ⇒ origin/main 不是集成头的祖先 ⇒ HAS_UPDATE

$ git log --oneline integration/module-dev..origin/main
d28188d6 Integration/module dev (#11)

$ git merge-base integration/module-dev origin/main
1bb270a054f878c56075bfd7f7fbe6a40c8dc851     # batch-28 tip
```

`d28188d6` 的形态与前三轮取证一致：**单父提交**（父 = `1bb270a0`）⇒ GitHub squash 合并产物。
`git cat-file -p d28188d6` 确认 `parent` 只有一行。

## 取证三：**独立重验**「main 是集成头的祖先快照」—— 本轮不采信历史文档

前三轮的会话日志已经写下「origin/main 的树与集成头祖先 `d22bc1e1` 逐字节相同」。
协议要求「**所有判断必须从仓库现状重新取证，不能凭记忆**」，所以本轮**重新跑了一遍**，
而不是引用上一轮的结论：

```
$ git rev-parse origin/main
d28188d63337a2eda641c1ee1e93db77039786b6

$ git rev-parse origin/main^{tree}
06607694b31c6fdfe354ed4783c22d4a0e0b8c20
$ git rev-parse d22bc1e1^{tree}
06607694b31c6fdfe354ed4783c22d4a0e0b8c20

$ git merge-base --is-ancestor d22bc1e1 integration/module-dev ; echo $?
0        # ⇒ d22bc1e1 是集成头的祖先

$ [ "$(git rev-parse origin/main^{tree})" = "$(git rev-parse d22bc1e1^{tree})" ] && echo IDENTICAL
IDENTICAL
```

⇒ **`origin/main` 的树与集成头自己的祖先提交 `d22bc1e1`（batch-37）逐字节相同**，
且 `d22bc1e1` 确实是集成头的祖先。

推论（这一条是本轮报告的**核心**）：**main 上的每一条路径，其内容都是集成头某次历史提交里的内容**，
集成头要么保留了原样、要么已经更新到更晚的版本。
⇒ **main 不可能领先于集成头的任何一行代码**；这次 merge 在**内容上是空转**的。
它之所以炸成冲突，纯粹是 squash 抹掉了血缘，把 merge-base 拉回 `1bb270a0`，
让 git 把同一份工作当成「两边各自新增」。

⚠️ 前一轮留下的建议是「下轮沿用 03:15 轮的**干跑优先**顺序（先 `git merge-tree --write-tree` 取证再动手）」。
本轮是**真合并 + abort**（因为要拿到真实冲突集合与 git 的 add/add vs content 分类），
事后核验结果与前几轮**完全一致**（同样 6 个文件），结论未受影响；但**下一轮仍建议按干跑优先**。

## 取证四：merge 冲突 6 个文件，与前三轮完全一致

真实 `git merge --no-ff origin/main`：

```
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
Automatic merge failed; fix conflicts and then commit the result.
```

✅ **契约与台账两类文件由 `merge-union` 驱动自动求并成功**（406 个文件里的绝大部分）：
`packages/api-contracts/src/contracts/{catalog,channel,trade}.ts`、
`packages/api-contracts/src/{catalog,channel,crm,trade}.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`（按模块 ID 求并集，465 行同 ID 记录折叠）。

❌ **6 个文件冲突**（协议规定**不自行解**）。本轮用 `git cat-file -e` 逐个核对它们在 merge-base
`1bb270a0` 上的存在性，把冲突类型**拆到底**（前三轮只给了 git 的原始措辞）：

| #   | 文件                                                                                               | merge-base 上是否存在 | 冲突类型 | 集成头独有行 / main 独有行 |
| --- | -------------------------------------------------------------------------------------------------- | --------------------- | -------- | -------------------------- |
| 1   | `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`                                      | **不存在**            | add/add  | +40 / −18                  |
| 2   | `apps/web/src/components/pricing/cost-composition-model.test.ts`                                   | **不存在**            | add/add  | +19 / −7                   |
| 3   | `apps/web/src/components/pricing/cost-composition-model.ts`                                        | **不存在**            | add/add  | +8 / −7                    |
| 4   | `apps/web/src/components/pricing/cost-composition-workspace.tsx`                                   | **不存在**            | add/add  | +13 / −0                   |
| 5   | `apps/web/src/lib/api-proxy.ts`                                                                    | 存在                  | content  | +675 / −0                  |
| 6   | `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java` | **不存在**            | add/add  | +134 / −2                  |

（「+N」= 集成头独有行数，「−N」= main 独有行数；口径 = `git diff --numstat origin/main HEAD -- <file>`）

**5 个 add/add 的成因一句话讲清**：这 5 个文件在 merge-base（batch-28）上**根本不存在**，
是集成头在 batch-28 之后**新增**的；而 squash 让 main 也「新增」了同路径的文件
（main 那份是集成头早期的版本）⇒ git 判成「两边各自新增」⇒ add/add。

⚠️ 第 1/2/3/6 个文件里 main 侧确有 2–18 行**集成头没有的文本**，
是否需要保留必须逐文件人工看（例如 `cost-composition-model.ts` 里「未交付」文案换成「已交付」后，
兄弟模块的口径说明措辞可能还需要保留）。**批量取一边 = 埋雷**，所以本轮仍然 abort + 上报。

## 取证五：main 侧独有文本的完整分布 —— 15 个文件，逐个归类

本轮补了一条更精确的口径：把「main → 集成头」方向上 **main 有、集成头没有的行**全部列出来
（`git diff --numstat origin/main HEAD` 的第二列 > 0），共 **15 个文件**。这 15 个文件的归宿：

| 归类                                                                             | 文件数 | 处置结果             |
| -------------------------------------------------------------------------------- | ------ | -------------------- |
| 由 `merge-union` 自动求并（契约 `*.openapi.json` + `contracts/*.ts` + 台账）        | 8      | ✅ 自动合上           |
| git 三方合并干净合上（非契约、非台账）                                            | 3      | ✅ 自动合上           |
| **构成冲突**（main 侧确有独有文本，故三方合不了）                                 | 4      | ❌ abort + 上报       |

那 3 个 git 干净合上的文件是：
`apps/web/src/app/api/crm/[...path]/route.ts`（+25 / −4）、
`backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRequestService.java`（+155 / −90）、
`backend/services/trade/src/test/java/com/starcon/trade/FulfillmentRequestIntegrationTest.java`（+165 / −2）。

⚠️ **另有 2 个冲突文件不在上面这 15 个之内** ——
`cost-composition-workspace.tsx` 与 `api-proxy.ts` 的 **main 独有行 = 0**（集成头是严格超集），
它们之所以仍冲突，纯粹是因为 **add/add**（两边都「新增」同一条路径，git 无从判断谁更全）。
所以**冲突文件总数 = 4（在这 15 个里）+ 2（严格超集，不在这 15 个里）= 6**。

⇒ **main 侧的「独有文本」全部集中在已重构/已收口的旧写法上，没有一处是 main 侧的新模块交付**；
其中 8 个由 `merge-union` 求并、3 个由 git 三方合并干净收掉，只剩 6 个文件需要人来定夺。

## 取证六：台账逐行比对 ⇒ main 侧**零新交付**，且比集成头落后 16 个模块

本轮**直接读两个快照各自的 `## 总览` 表**（不去数全文里的状态词 —— 那会把说明文字里的
「已交付 / 未开发 / 进行中」也数进去，得到 336 / 254 / 7 这种错数）：

```
$ git show origin/main:docs/product/capability-blueprint/module-progress.md | sed -n '/^## 总览/,/^## /p'
| 模块总数 | 465 |
| 已交付   | 242 |
| 进行中   | 0   |
| 未开发   | 223 |
| 完成率   | 52.0% |

$ git show HEAD:docs/product/capability-blueprint/module-progress.md | sed -n '/^## 总览/,/^## /p'
| 模块总数 | 465 |
| 已交付   | 258 |
| 进行中   | 0   |
| 未开发   | 207 |
| 完成率   | 55.5% |
```

| 指标     | main 快照 `d28188d6` | 集成头 `aa986d83` | 变化 |
| -------- | -------------------- | ----------------- | ---- |
| 模块总数 | 465                  | 465               | —    |
| 已交付   | **242**（52.0%）     | **258**（55.5%）   | +16  |
| 进行中   | 0                    | 0                 | ±0   |
| 未开发   | 223                  | 207               | −16  |

模块集合两边**完全相同**（各 465 行，`comm` 差集为空），状态不同的 16 个模块**方向全部一致**
（main = 未开发、集成头 = 已交付），本轮逐行比对结果：

| 模块 ID  | 名称             | main 快照 | 集成头     |
| -------- | ---------------- | --------- | ---------- |
| 5.2.1.2  | 到岸成本         | 未开发    | **已交付** |
| 5.2.1.3  | 成本分摊         | 未开发    | **已交付** |
| 5.2.2.1  | 汇率查询         | 未开发    | **已交付** |
| 5.2.2.2  | 汇率快照         | 未开发    | **已交付** |
| 5.2.3.1  | 平台费用         | 未开发    | **已交付** |
| 5.2.3.2  | 物流与头程费用   | 未开发    | **已交付** |
| 5.3.1.1  | 利润测算器       | 未开发    | **已交付** |
| 5.3.1.2  | 批量测算         | 未开发    | **已交付** |
| 5.3.2.1  | 定价模板         | 未开发    | **已交付** |
| 5.5.2.1  | 异常告警         | 未开发    | **已交付** |
| 6.1.2.1  | 档口与货源池     | 未开发    | **已交付** |
| 6.2.1.1  | 缺货与断货风险   | 未开发    | **已交付** |
| 6.3.2.2  | 到货与逾期       | 未开发    | **已交付** |
| 10.7.1.3 | 弃置与销毁       | 未开发    | **已交付** |
| 15.2.2.1 | Request a Review | 未开发    | **已交付** |
| 15.2.2.2 | 索评规则         | 未开发    | **已交付** |

⇒ **main 没有任何一个模块是集成头没有的**（`main 领先` 的集合为空）；
main 是集成头**早 16 个模块的旧快照**。本任务想同步的内容，**集成头已经有了（且更新）**。

## abort 后的状态核验

```
$ git merge --abort
$ git status --porcelain        # 空
$ git rev-parse HEAD            # aa986d83e0cdf4d785e3b392fdc23528af0a3d46   （= 合并前 PRE）
$ test -f .git/MERGE_HEAD       # NO   ← 没有 MERGE_HEAD 残留，不会堵死流水线的 merge-ready
$ git rev-parse origin/integration/module-dev
aa986d83e0cdf4d785e3b392fdc23528af0a3d46   ← 与远端零偏差
```

⇒ 集成头**完全回到合并前**，无残留、无半合并状态，流水线可以正常继续。

## 台账变化

| 指标     | 合并前 | 合并后（abort + `sync.mjs`） | 变化 |
| -------- | ------ | --------------------------- | ---- |
| 模块总数 | 465    | 465                         | —    |
| 已交付   | 258    | 258                         | ±0   |
| 进行中   | 0      | 0                           | ±0   |
| 未开发   | 207    | 207                         | ±0   |
| 完成率   | 55.5%  | 55.5%                       | ±0   |

`node tools/module-progress/sync.mjs` 输出 `✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 258 · 进行中 0 · 未开发 207`，
且 `git status --porcelain` **为空（零 diff）** ⇒ 台账已由 batch-43/44 刷新到位，本轮无新增。

参照：main 的台账快照是 **已交付 242 / 进行中 0 / 未开发 223**（52.0%），
比集成头少 16 个已交付 —— **这是 squash 快照的时差，不是本任务的功劳，也不是本任务的欠账**。

## 关键 trade-off / 讨论

**要不要自己把这 6 个冲突解掉？** **不解。**
协议写死「❌ 自行解冲突」「**冲突不自行解决** —— `git merge --abort` 后把冲突文件名列进汇报」，
AGENTS.md 的模块边界纪律同样禁止 agent 自行裁决。本轮还多一条证据让「解」更不划算：
**main 的树与集成头祖先 `d22bc1e1` 逐字节相同** ⇒ 这次 merge 本质是空转；
即便如此，第 1/2/3/6 个文件上 main 仍有 2–18 行「已被推翻的旧断言」需要逐行核对，
协议不允许，且「大概率对」不构成放行依据。

**要不要在 abort 后还 push 一个「已同步」的空提交？** **不。**
没有合并就没有「已同步」；把 abort 包装成成功会直接毁掉下一棒 L2 的排查依据。
本轮只推**本会话文档 + backlog 更新**（与 01:15 / 03:15 / 04:15 轮同构）。

**本轮与前三轮最大的不同是什么？** **闸是空的。**
前三轮分别等 45 / 40 / 27 分钟，本轮 **0 等待**。⇒ **闸的等待不是本任务的瓶颈**，
瓶颈**只有一个**：main 的 tip 是集成头的 squash。**闸的问题在发布侧改一次就永久消失**，
不必再在本任务上做任何优化。

**第 4 轮还撞同一堵墙，要不要改协议？** **不改协议，改发布流程。**
连续 4 轮完全相同的空转 merge 说明：**协议是对的（它正确地挡住了无意义的内容重放），
错的是发布侧把集成头 squash 进了 main**。协议第 6 步「冲突不自行解决」应保留。

## 后续待办

1. **发布侧（本任务的长期阻塞点，连续 4 轮撞同一堵墙）**：main 的 tip 是集成头的 **squash**，
   导致 merge-base 被拉回 `1bb270a0`、6 个文件冲突，**且 main 侧零新交付**。
   **建议发布侧改成推真实 commit（不 squash）**，本任务即可回到「无新提交」的常态。
   已按 backlog 规范落档：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（已更新到第 4 轮）。
2. **修复车道（若坚持要并入 main 这次的内容）**：单独开一条 fix 车道解这 6 个冲突，
   方向「以集成头为准 + 逐行核对 main 那 2–18 行独有内容是否需要保留」，
   **不要用 `git checkout --ours/--theirs` 批量覆盖**。解完**必须重跑 L2**
   （这批内容从未经过本流水线的 L1/L2）。
3. **下一轮流水线的 L2 排查线索**：本轮**没有产生 main merge commit** ⇒ 协议里
   「L2 红了先查 main merge commit」这条**本轮不适用**；L2 若红，仍应先查 batch-43 的六条车道
   （5.2.3.2 / 5.3.1.2 / 5.3.2.1 / 6.1.2.1 / 6.2.1.1 / 6.3.2.2）。
4. **同步任务的收尾保障**：本轮 acquire 成功 1 次 / release 1 次，**配平**；
   2026-10-21 02:15 那个回合被截断时既没写日志也没 release（闸泄漏 70 分钟）的问题**本轮未复现**。

## 涉及文件

- `docs/sessions/2026-10/2026-10-21-0515-main-sync-conflict-squash-divergence.md` —— 本文件（新增，单独 docs commit）
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— **更新**（轮次 3 → 4，补本轮取证）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 零 diff）
- 业务代码 —— **零改动**（合并已 abort）

## 关联

- `tools/module-progress/SYNC-MAIN.md` —— 本轮执行依据的协议（集成头内那份）
- `tools/module-progress/PIPELINE.md` —— 完整流水线协议（与 SYNC-MAIN 冲突时以它为准）
- `docs/sessions/2026-10/2026-10-21-0415-main-sync-conflict-squash-divergence.md` —— 上一轮（**同样 6 文件冲突**，等闸 27 分钟）
- `docs/sessions/2026-10/2026-10-21-0315-main-sync-conflict-squash-divergence.md` —— 再上一轮（**同样 6 文件冲突**，等闸 40 分钟）
- `docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` —— 再再上一轮（**同样 6 文件冲突**，等闸 45 分钟）
- `docs/sessions/2026-10/2026-10-21-batch-43-six-lane-merge.md` —— batch-43（六车道并入 + L2 全绿）
- `docs/backlog/2026-10/2026-10-21-api-proxy-shared-file-merge-conflict.md` —— `api-proxy.ts` 共享白名单文件的结构性冲突根因
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）

## 闸

已 `gate release`。本轮 **05:15:17 确实 `acquire` 成功之后**才释放（acquire 成功 1 次、release 1 次，配平）。
全程从未 release 过别人持有的闸 ⇒ 没有误删活着的闸。
