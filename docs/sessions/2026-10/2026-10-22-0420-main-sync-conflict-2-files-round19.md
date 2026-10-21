# 2026-10-22 04:20 集成头同步 main（第 19 轮 · 冲突未并入 · 冲突面与第 18 轮逐行相同 · 台账塌陷首次实锤）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 19 轮。

**结果：冲突未并入，未推送任何合并内容，集成头 HEAD 保持 `e220f8e2` 不变。**
四选一里的「**冲突未并入**」。

本轮撞的是**第 18 轮（04:04，同日）的同一堵墙**，且是**逐行复现**：
`origin/main` 的 tip 仍是 `a105acf0`（提交时间 02:42:58）——**main 自第 18 轮结束以来零推进**；
`merge-base` 仍是 `1bb270a0`；`git merge --no-ff origin/main` 仍在**同样的 2 个文件、同样行号的
10 个冲突区**上告终。

**本轮的两个独立增量**（不采信历史会话日志，全部重新取证）：

1. **冲突面 100% 复现第 18 轮**（`api-proxy.ts` 7 区 + `MAIN_MERGE_CRON_PROMPT.md` 3 区，
   冲突区起始行号逐一相同：`500/648/5021/5434/5542/5835/6733` 与 `152/184/220`），
   `git merge-file --diff3` 的逐区 ours/base/theirs 行数与第 18 轮记录**逐格相同**。
   ⇒ **main 没动 ⇒ 冲突必然原样复现**，本轮没有任何新信息可用于解冲突。
2. **合并驱动在台账上的塌陷，第一次被「实测 + 可复现地红」坐实**（详见「取证五」）：
   第 18 轮只记录了驱动打印的告警横幅，本轮把合并产物抓下来与 `sync.mjs` 生成物逐字节比对，
   并**实测 `sync.mjs --check` 在合并产物上退出码 1** ⇒ 坐实
   `docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md` 对
   `SYNC-MAIN.md` 这条路径的预判，且**比预判更严重**（不只是渲染坏，还会把 `sync --check` 弄红）。

## 决策 / 结论

1. **冲突只报不解**（协议 `tools/module-progress/SYNC-MAIN.md` §0 硬规定 + 第 3 步，
   且 `PIPELINE.md:489` 同口径：「不要自己强行解冲突」）。
   本轮 `git merge --abort`，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `e220f8e2`，
   **未 push 任何合并内容**。

2. **本轮冲突面 = 2 个文件、10 个冲突区，与第 18 轮逐行相同**：

   | 文件                                              | 冲突区                                   | 性质       | 本轮取证结论                                                                                                                                                                            |
   | ------------------------------------------------- | ---------------------------------------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
   | `apps/web/src/lib/api-proxy.ts`                   | 7（行 500/648/5021/5434/5542/5835/6733） | 双向       | main 的 blob `7f844ce6` == 集成头 `976d7d42` 的 blob（`976d7d42` 是 HEAD 的**线性祖先**，已证 YES）⇒ 取集成头侧可证零丢失；**路由 token 集合比对：只属于 main = 0，只属于集成头 = 111** |
   | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` | 3（行 152/184/220）                      | **真双向** | main 独有的 44 行「落地树哈希」判据段（`a105acf0`）集成头侧没有 ⇒ 单边取任一侧都丢内容，**必须人工求并**                                                                                |

   ⇒ `api-proxy.ts` 的冲突是**累积型白名单 + 双向叠加**的必然结果：
   main 在「集成头 → main」那轮 PR 里把这份文件取成了集成头当时的快照，
   集成头此后又并入 8 个 14.x 广告/增长车道（每条车道往同一个 `services[]` 里新增一条独立元素），
   两边改的是**同一批插入锚点** ⇒ 必冲突。
   **retry 不会自愈**：只要 main 侧不重新吸收这 8 个车道的白名单条目，下一轮还是撞同一堵墙。

3. **main 相对集成头依然零业务新增**（本轮三路重新取证）：

   | 维度             | 方法                                          | 结论                                                                                                                             |
   | ---------------- | --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
   | 白名单路由 token | `api-proxy.ts` 两侧全文抽 path token 求对称差 | **只属于 main = 0**，只属于集成头 = **111**（`ad-budgets/**`、`ad-bulk-operations/**` 等广告车道路由）                           |
   | 树差异「A」侧    | `git diff --name-status HEAD origin/main`     | 23 个「main 有、集成头没有」的文件 = **15 个 docs + 8 个 `tools/progress-site/**`**；其中落在 `backend/` 或 `apps/` 下的 = **0** |
   | 台账模块数       | 465 个模块逐 ID 比对                          | main **297** 已交付 vs 集成头 **305** 已交付 ⇒ main 领先 **0**，集成头领先 8 个 14.x 广告车道                                    |

   ⇒ **main 侧没有任何集成头不具备的业务内容。**
   本轮即使把冲突解掉并合入，集成头净增的也只有 **15 个文档 + 8 个看板工具文件**，业务代码净增 **0**。

4. **合并驱动在台账上的塌陷已实锤，且会让 CI 红**（本轮相对第 18 轮的新增结论）：
   合并产物 `module-progress.md` 与 `sync.mjs` 生成物逐字节比对 ⇒
   **空行 18 → 1、分隔行 4 → 3**，并且**把 main 的 5 行陈旧汇总行注入了「模块明细」表末尾**
   （`| 已交付 | 297 |`、`| **未开发** | **168** |`、`| 完成率 | 63.9% |`、
   `| **D7** | 23 | 0 | 132 | 155 |`、`| 14 | 广告与增长系统 | D7 | 4 | 22 |`）。
   **实测 `node tools/module-progress/sync.mjs --check` 在该合并产物上退出码 1（✗ 台账与蓝图不一致）。**
   ⇒ 结论：**解完冲突后必须重跑 `sync.mjs` 重新生成台账再提交**，否则提交进去的就是一份
   「模块行没丢但汇总表陈旧 + 表格结构塌陷 + `sync --check` 红」的台账。
   （这正是 2026-10-22 00:55 那轮「集成头 → main」的 PR 里做过的事：「台账用生成器重新拉齐」。）

5. **台账数字**（merge abort 后，集成头 `e220f8e2`）：已交付 **305** / 进行中 **0** / 未开发 **160**
   （465 个模块，完成率 65.6%）。与第 18 轮记录的**完全相同**（第 18 轮在 `11c59c69` 上记的
   就是 305 / 0 / 160，本轮 HEAD `e220f8e2` 只是在其上多了一个会话文档提交）——
   本轮 main 零推进 ⇒ 台账零变化。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动）。

6. **本轮只提交了本会话文档**（AGENTS.md「每次有结论的会话都要落到 `docs/sessions/`」），
   **业务代码零改动**；文件名带轮次时间戳，main 侧不存在同名路径 ⇒ 不会给下一轮制造 add/add 冲突。

## 关键 trade-off

1. **第 18 轮已经备好证据，本轮是否还要重跑取证** ⇒ 要。
   协议第 8 行明写「所有判断必须从仓库现状重新取证，不能凭记忆」，
   且「main 没动」本身是**本轮的结论**，不是可以直接从上一轮会话日志继承的前提。
   本轮重新独立证了：merge-base、冲突区行号、逐区行数、blob 身份、路由 token 对称差、
   树差异分类、闸状态、fetch 结果。

2. **台账塌陷是「预期内」还是「新缺陷」** ⇒ **不是新缺陷，但严重性被本轮上调**。
   backlog 里已把它记为「渲染坏、不丢数据」，并已预判「`SYNC-MAIN.md` 那条路径提交的那一刻
   不会跑 `sync.mjs` ⇒ 那条路径产出的台账就是塌的」。
   本轮把它从「预判」推到「实测可复现地红」：**`sync --check` 会失败**，
   且不只是少空行/少分隔行，还会**注入陈旧汇总行**（backlog 未预判到这一条）。
   ⇒ 修驱动（backlog 建议 1/2/3 三选一）时，必须**把 `SYNC-MAIN.md` 这条路径纳入验收**。

3. **本轮不做「冲突只报」的例外** ⇒ 协议 §0 与 `PIPELINE.md:489` 都是「只报不解」的硬规定，
   即使证据表明 `api-proxy.ts` 的解法可证无损，也**不自行解**。解法的证据留给有授权的人/调度器使用。

## 涉及文件

- 冲突文件（本轮未改动，已 abort）：
  - `apps/web/src/lib/api-proxy.ts`（7 个冲突区）
  - `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（3 个冲突区）
- 本轮新增（唯一提交）：`docs/sessions/2026-10/2026-10-22-0420-main-sync-conflict-2-files-round19.md`
- 合并中已干净求并但**随 abort 丢弃**的产物（未进入最终树）：
  `docs/product/capability-blueprint/module-progress.md`、
  `packages/api-contracts/src/{catalog,channel,trade,warehouse}.openapi.json`、
  `packages/api-contracts/src/contracts/{catalog,channel,trade,warehouse}.ts`、`.gitignore`

## 验证结果

- **闸**：`gate status` ⇒ `free / stale:false` ⇒ **直接 `gate acquire` 成功**，本轮**未等待**（第 18 轮等了约 46 分钟）。
- **fetch**：`git fetch origin --prune` ⇒ `origin/main` tip = `a105acf0`（2026-10-22 02:42:58 +0800），
  领先集成头 **26 个提交**，`merge-base` = `1bb270a0`。
- **merge**：`git merge --no-ff -F <msg> origin/main` ⇒ 2 文件冲突（10 区）；
  `git merge --abort` ⇒ 退出码 0，`MERGE_HEAD` 已清，工作区 `git status --porcelain` 为空，
  HEAD 回到 `e220f8e2`。
- **远端**：`origin/integration/module-dev` == `e220f8e2` == 本地 HEAD ⇒ **未推送任何合并内容**。
- **台账**：`sync.mjs --check` ⇒ ✓ 与蓝图一致（465 个模块行，编号零重复）；
  在**合并产物**上 ⇒ ✗ 不一致（退出码 1），详见「取证五」。
- **业务代码**：本轮零改动，未跑 `mvnw verify`（属流水线 L2），未跑预检
  （merge 已 abort，工作区无合并产物可预检）。

## 取证

### 一、闸：本轮直接取得，未等待

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "free", "ageSeconds": null, "ttlSeconds": 4200, "stale": false }
$ node tools/module-progress/lane.mjs gate acquire
{ "ok": true, "gate": "acquired" }
```

⇒ 闸空闲且非 stale ⇒ 直接取得（第 18 轮是 `held` 状态密集竞态了 2778 次才拿到）。

### 二、main 自第 18 轮以来零推进（这是本轮全部结论的前提）

```
$ git log -1 --format='%H%n%ci%n%s' origin/main
a105acf013bda16ca36d6fdc7c120a7318ea0a60
2026-10-22 02:42:58 +0800
docs: 修正集成分支并入主分支的新内容判据
```

第 18 轮跑于同日 04:04，main 的最新提交时间是 **02:42:58** ⇒ **main 在第 18 轮之后没有新提交**。
⇒ 冲突面原样复现是**必然**的，不是巧合。

### 三、冲突面：2 个文件、10 个冲突区，与第 18 轮逐行相同

```
$ git diff --name-only --diff-filter=U
apps/web/src/lib/api-proxy.ts
tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
```

冲突区起始行号 —— `api-proxy.ts`：`500 / 648 / 5021 / 5434 / 5542 / 5835 / 6733`；
`MAIN_MERGE_CRON_PROMPT.md`：`152 / 184 / 220`。
**与第 18 轮记录逐一相同。**

`git merge-file --diff3 -p`（纯函数，不碰工作区）逐区量行数：

| 冲突区            | ours | base | theirs |
| ----------------- | ---- | ---- | ------ |
| 1（行 500–839）   | 242  | 0    | 94     |
| 2（行 2550–3195） | 619  | 15   | 8      |
| 3（行 3209–3923） | 47   | 6    | 658    |
| 4（行 4384–4443） | 54   | 1    | 1      |
| 5（行 4539–4645） | 51   | 1    | 51     |
| 6（行 5880–6076） | 118  | 0    | 75     |
| 7（行 6111–9427） | 1763 | 0    | 1550   |

⇒ 与第 18 轮记录**逐格相同**。

**blob 级证明（`api-proxy.ts`）**：

```
$ git rev-parse origin/main:apps/web/src/lib/api-proxy.ts
7f844ce6483b008186aa99c060fd582d98ba2d6e
$ git rev-parse 976d7d42:apps/web/src/lib/api-proxy.ts   # merge(14.2.2.2): 自动规则
7f844ce6483b008186aa99c060fd582d98ba2d6e        ← 逐字节相同
$ git merge-base --is-ancestor 976d7d42 integration/module-dev
YES                                                ← 线性祖先
```

⇒ main 在「集成头 → main」那轮 PR 里把这份文件取成了集成头当时的快照；
集成头此后并入 8 个 14.x 车道（`14.1.1.2 / 14.1.1.3 / 14.1.2.1 / 14.2.2.1 /
14.3.1.1 / 14.3.1.2 / 14.3.2.2 / 14.4.1.1`），每条车道往同一个 `services[]` 新增一条独立元素
⇒ 双方改的是**同一批插入锚点** ⇒ 必然双向冲突。

`MAIN_MERGE_CRON_PROMPT.md` 的 3 个冲突区是**真双向**：
main 独有 44 行（`a105acf0` 的「落地树哈希」判据段 + 「真实增量」措辞改写），
集成头独有 6 行 ⇒ **单边取任一侧都会丢内容**。

### 四、main 零业务新增：三路独立取证

**① 白名单路由 token 对称差**（本轮新增的一路，比第 18 轮更直接）：

```
ours (集成头)  = 6990 行, 抽出 path token 1881 个
theirs (main)  = 6586 行, 抽出 path token 1770 个
只属于 main 的 token = 0        ← main 没有任何一条集成头没有的路由
只属于集成头的 token = 111     ← ad-budgets/**、ad-bulk-operations/** 等广告车道
```

**② 树差异分类**（tip 对 tip，130 个文件 = 95 D / 23 A / 12 M）：

| 方向                | 数量 | 内容                                                                                                                          |
| ------------------- | ---- | ----------------------------------------------------------------------------------------------------------------------------- |
| 集成头有、main 没有 | 95   | 42 个 `backend/services/channel/**` + 40 个 `apps/web/src/**`（8 个广告车道的代码）+ 13 个 `docs/sessions/**`                 |
| main 有、集成头没有 | 23   | 15 个 docs（会话日志 + 1 个 backlog）+ 8 个 `tools/progress-site/**`（进度看板工具）⇒ **落在 `backend/` 或 `apps/` 下的 = 0** |
| 两侧都有、内容不同  | 12   | `.gitignore`、`api-proxy.ts`、台账、7 份契约、`MAIN_MERGE_CRON_PROMPT.md`                                                     |

**③ 台账模块级比对**：

```
main 台账：    已交付 297 / 进行中 0 / 未开发 168 / 63.9%
集成头台账：  已交付 305 / 进行中 0 / 未开发 160 / 65.6%
main 领先：0 个模块；集成头领先 8 个（14.1.1.2 / 14.1.1.3 / 14.1.2.1 / 14.2.2.1 /
          14.3.1.1 / 14.3.1.2 / 14.3.2.2 / 14.4.1.1）
```

⇒ **main 侧没有任何集成头不具备的业务内容。**
本轮即使把冲突解掉并合入，集成头净增的也只有 **15 个文档 + 8 个看板工具文件**，业务代码净增 **0**。

### 五、台账塌陷：实测坐实，并比 backlog 预判更严重

合并驱动在台账上打印了告警横幅：

```
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
```

第 18 轮把这条记为「属预期」。本轮把合并产物抓下来与 `sync.mjs` 生成物逐字节比对：

| 指标                           | `sync.mjs` 生成物（canonical） | 合并驱动产物 | 差异                                 |
| ------------------------------ | ------------------------------ | ------------ | ------------------------------------ |
| 空行数                         | 18                             | **1**        | **−17**                              |
| `\| --- \|` 分隔行数           | 4                              | **3**        | **−1**（「分系统进度」表失去分隔行） |
| 模块行数                       | 465                            | 465          | 0（不丢数据）                        |
| 唯一模块 ID                    | 465                            | 465          | 0                                    |
| 注入「模块明细」表的陈旧汇总行 | 0                              | **5**        | **+5**                               |

**注入的 5 行**（落在「模块明细」表末尾 `18.5.2.2` 之后）：

```
| 已交付 | 297 |
| **未开发** | **168** |
| 完成率 | 63.9% |
| **D7** | 23 | 0 | 132 | 155 |
| 14 | 广告与增长系统 | D7 | 4 | 22 |
```

⇒ main 的**陈旧汇总行被当成台账行并进了「模块明细」表**。

**决定性实测**：

```
$ cp /tmp/sc-merged-ledger.md docs/product/capability-blueprint/module-progress.md
$ node tools/module-progress/sync.mjs --check
✗ 台账与蓝图不一致，运行：node tools/module-progress/sync.mjs
exit=1
```

⇒ backlog `2026-10-20-merge-union-ledger-markdown-structure-collapse.md` 的预判
（「`SYNC-MAIN.md` 那条路径提交的那一刻不会跑 `sync.mjs` ⇒ 那条路径产出的台账就是塌的」）
**成立**；且实际后果不止「渲染坏」，还会**让 `sync --check` 红** ⇒ 提交前必须重跑 `sync.mjs` 重新生成。
（测量后已 `git checkout --` 还原台账，工作区干净。）

## 后续待办

1. **（需有授权的人/调度器）解 `api-proxy.ts` 冲突**：取集成头侧即可，
   已有 blob 级证明「零丢失」（main 的 blob 是集成头 `976d7d42` 的逐字节副本，
   `976d7d42` 是当前集成头的线性祖先），且本轮补了一条更直接的证据：
   **只属于 main 的路由 token = 0**。**不要**再逐条手写 alternation 求并。
2. **（需有授权的人/调度器）解 `MAIN_MERGE_CRON_PROMPT.md` 冲突**：这是**真双向**，
   必须人工求并 —— main 独有的 44 行「落地树哈希」判据段要保留，
   集成头独有的 6 行小改也要保留。
3. **解完冲突后必须重跑 `node tools/module-progress/sync.mjs` 再提交台账** ——
   本轮已实测：合并驱动的台账产物会让 `sync.mjs --check` 退出码 1。
4. **解完冲突后不必重跑本同步任务**：本轮已证 main 侧零业务新增，
   合入收益只有文档与看板工具。**真正的缺口在反方向** ——
   `main` 落后集成头 8 个 14.x 广告/增长车道模块，
   需要重切一次「集成头 → main」的 PR（走 `MAIN_MERGE_CRON_PROMPT.md` 那套流程），
   而不是继续把 main 往集成头同步。
5. **本同步任务在 main 零推进时无收益**：第 18、19 两轮连续撞同一堵墙且 main tip 未变。
   若下一轮 main 仍未推进，建议直接跳过本任务，或先确认反方向 PR 的进展。
6. **修 `merge-union` 台账分支时，必须把 `SYNC-MAIN.md` 这条路径纳入验收**
   （backlog 建议 1/2/3 三选一；验收除空行/分隔行计数外，还要加
   「`sync.mjs --check` 在合并产物上必须绿」+「模块明细表不得混入汇总行」）。

## 关联

- 同步协议：`tools/module-progress/SYNC-MAIN.md`
- 反向合并协议：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`
- 功能进度台账：`docs/product/capability-blueprint/module-progress.md`
- 已知缺陷：`docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md`、
  `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`
- 上一轮：`docs/sessions/2026-10/2026-10-22-0404-main-sync-conflict-2-files-round18.md`
