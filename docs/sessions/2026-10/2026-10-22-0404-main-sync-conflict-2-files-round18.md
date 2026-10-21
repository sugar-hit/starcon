# 2026-10-22 04:04 集成头同步 main（第 18 轮 · 冲突未并入 · 冲突面仍是同样 2 文件）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 18 轮。

**结果：冲突未并入，未推送任何合并内容，集成头 HEAD 保持 `11c59c69` 不变。**
四选一里的「**冲突未并入**」。

> 轮次编号说明：上一轮 `2026-10-22-0250-main-sync-conflict-two-files-round18.md`
> 的**标题**写的是「第 17 轮」（其文件名后缀 `round18` 是笔误）。按标题口径，
> 本轮是第 18 轮。

本轮与前 17 轮撞的是**同一堵墙**：`origin/main` 仍用 **squash** 合入，
`merge-base` 仍停在 `1bb270a0`，`git merge --no-ff origin/main` 仍以冲突告终。

**本轮的两个独立增量**（不采信历史会话日志，全部重新取证）：

1. **冲突面从 4 → 10 个冲突区**：`api-proxy.ts` 从 4 个冲突区涨到 **7 个**，
   且**性质从「纯 ours 侧新增」变成全部「双向」** —— 这是第 17 轮没有的新情况。
2. **冲突根因第一次被证到「blob 级线性祖先」**：`origin/main` 的 `api-proxy.ts`
   与集成头在 `976d7d42`（`merge(14.2.2.2): 自动规则`）处的版本**逐字节相同**，
   而 `976d7d42` 是集成头当前的**线性祖先** ⇒ main 侧对这份文件的全部贡献
   **已经完整包含在集成头当前版本里** ⇒ 该文件的冲突**可证明无损地取集成头侧**。
   上一轮只说「结构性冲突，retry 不会自愈」，本轮把它证成了**可解且无损**。

## 决策 / 结论

1. **冲突只报不解**（协议 `tools/module-progress/SYNC-MAIN.md` §0 硬规定 + 第 3 步，
   且 `PIPELINE.md:489` 同口径：「不要自己强行解冲突」）。
   本轮 `git merge --abort`，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `11c59c69`，
   **未 push 任何合并内容**。

2. **本轮冲突面 = 2 个文件、10 个冲突区**（`git merge` 实测的冲突区数）：

   | 文件 | 冲突区 | 性质 | 取证结论 |
   | --- | --- | --- | --- |
   | `apps/web/src/lib/api-proxy.ts` | 7（行 500/648/5021/5434/5542/5835/6733） | **全部双向** | main 的 blob == 集成头 `976d7d42` 的 blob（线性祖先）；diff main vs head = **0 行只属于 main**、404 行只属于 head ⇒ **取集成头侧可证明无损** |
   | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` | 3（行 152/184/220） | 双向 | **真双向**：44 行只属于 main（`a105acf0` 新增的「落地树哈希」判据段），6 行只属于集成头 ⇒ **需人工求并，不能单边取** |

   ⇒ `api-proxy.ts` 的冲突是**累积型白名单 + 双向叠加**的必然结果：
   main 在「集成头 → main」那轮 PR 里把这份文件取成了集成头当时的快照，
   集成头此后又陆续并入 8 个 14.x 广告/增长车道（每个车道都往同一个
   `services[]` 里新增一条独立元素），两边改的是**同一批插入锚点** ⇒ 必冲突。

3. **main 相对集成头依然零业务新增**（本轮三路重新取证）：

   | 维度 | 方法 | 结论 |
   | --- | --- | --- |
   | 台账模块领先数 | 逐模块 ID（465 个）比对 main 台账 vs 集成头台账 | main 领先 **0**；集成头领先 **8**（14.1.1.2 / 14.1.1.3 / 14.1.2.1 / 14.2.2.1 / 14.3.1.1 / 14.3.1.2 / 14.3.2.2 / 14.4.1.1） |
   | 契约产物 | 4 份 openapi.json 的 paths / schemas 集合差 | main 侧**独有 0 个** paths、**0 个** schemas；channel 侧集成头多 **67 paths / 155 schemas**（8 个广告车道端点）；catalog/trade/warehouse 三份规模完全相同 |
   | 树差异分类 | `git diff --name-status` tip 对 tip | **94 D**（42 backend + 40 apps/web = 8 个广告车道的代码，main 没有）+ **23 A**（16 个 docs + 8 个 `tools/progress-site/**`，main 独有）+ **12 M**（api-proxy / 台账 / 7 份契约 / `.gitignore` / cron prompt） |

   ⇒ main 侧 8 个 `feat:` 提交（供应商评级 10 项、到岸成本 24 项、平台价格同步 7 项、
   采购单审批 5 项、需求合并 4 项、采购单创建 2 项、待检处理+授权凭证 2 项、品牌授权 1 项）
   **在集成头里已经逐字节存在** —— 它们是被 squash 合入 main 的集成头自身产出。

4. **`merge-union` 驱动按预期工作**：台账 `module-progress.md` 按模块 ID 求并集，
   打印「折叠了 465 行同 ID 记录」；`packages/api-contracts/**` 7 个文件全部干净合并；
   `.gitignore` 干净合并；**唯一走兜底行级并集的是台账本身**（打印人工确认提示，属预期）。

5. **台账数字**（merge abort 后，集成头 `11c59c69`）：已交付 **305** / 进行中 **0** / 未开发 **160**
   （465 个模块，完成率 65.6%）。上一轮（`bf6179b8`）为 301 / 0 / 164，
   差额 **+4 已交付** 来自 batch-59 的 4 条广告车道并入。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动）。

6. **本轮只提交了本会话文档**（AGENTS.md「每次有结论的会话都要落到 `docs/sessions/`」），
   **业务代码零改动**；文件名带轮次时间戳，main 侧不存在同名路径 ⇒ 不会给下一轮制造 add/add 冲突。

## 关键 trade-off

1. **`api-proxy.ts` 解法选「取集成头侧」而非「逐条求并」** ⇒ 有 blob 级证据支撑：
   main 的版本是集成头 `976d7d42` 的逐字节副本，而 `976d7d42` 是集成头当前版本的祖先
   ⇒ 集成头当前版本是 main 版本的**严格超集**（diff 方向：main→head 只增 404 行、
   **删 0 行**）⇒ 取集成头侧 = 完整求并，**零丢失**。
   （对比：2026-10-22 00:55 那轮「集成头 → main」的 PR 里，同一个文件用了
   「120 条 → 129 条、四元组逐字节相同、alternation 分支 167→179 旧分支丢失 0 条」
   的逐条求并证明 —— 那轮是**两个方向**的同一份文件，取证口径不能直接搬过来，
   本轮用的是更硬的 blob 祖先判据。）

2. **`MAIN_MERGE_CRON_PROMPT.md` 不跟 `api-proxy.ts` 同办** ⇒ 它的 44 行 main 独有内容
   是 `a105acf0` 新增的「落地树哈希」判据段，**集成头侧没有**（6 行独有内容是集成头自己
   的小改）⇒ 单边取任一侧都会丢内容，必须人工求并。这是本轮**唯一需要真决策**的冲突面。

3. **本轮不做「冲突只报」的例外** ⇒ 协议 §0 与 `PIPELINE.md:489` 都是「只报不解」的硬规定，
   即使证据表明解法可证无损，也**不自行解**。解法的证据留给有授权的人/调度器使用。

## 涉及文件

- 冲突文件（本轮未改动，已 abort）：
  - `apps/web/src/lib/api-proxy.ts`（7 个冲突区）
  - `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（3 个冲突区）
- 本轮新增（唯一提交）：`docs/sessions/2026-10/2026-10-22-0404-main-sync-conflict-2-files-round18.md`
- 合并中已干净求并但**随 abort 丢弃**的产物（未进入最终树）：
  `docs/product/capability-blueprint/module-progress.md`、
  `packages/api-contracts/src/{catalog,channel,trade,warehouse}.openapi.json`、
  `packages/api-contracts/src/contracts/{catalog,channel,trade,warehouse}.ts`、`.gitignore`

## 验证结果

- **闸**：`gate status` ⇒ `held / stale:false`（age 184s / TTL 4200s）⇒ 判定持闸 tick 活着。
  按协议**密集竞态 `gate acquire`**（不是低频观测 status，避免错过零宽度交接窗口），
  **2778 次尝试后取得**，等待约 46 分钟（19:18Z → 20:04:36Z）。
- **fetch**：`git fetch origin --prune` ⇒ 引入 `origin/chore/sync-module-dev`（新分支），
  `origin/main` 领先集成头 **26 个提交**。
- **merge**：`git merge --no-ff origin/main` ⇒ 退出码 1，2 文件冲突；
  `git merge --abort` ⇒ 退出码 0，`MERGE_HEAD` 已清，工作区 `git status --porcelain` 为空，
  HEAD 回到 `11c59c69`。
- **台账**：`sync.mjs --check` ⇒ ✓ 与蓝图一致（465 个模块行，编号零重复）。
- **业务代码**：本轮零改动，未跑 `mvnw verify`（属流水线 L2），未跑预检
  （merge 已 abort，工作区无合并产物可预检）。

## 取证

### 一、闸：等了约 46 分钟，密集 acquire 竞态命中

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 184, "ttlSeconds": 4200, "stale": false }
```

`stale: false` ⇒ 持闸 tick 活着，**等它释放，不抢不清不 force**。
按协议 §「等闸」用**密集 `gate acquire` 竞态**（`mkdirSync` 原子互斥）：

```
still waiting... attempts=2700 20:03:12Z
GATE ACQUIRED after 2778 attempt(s)
ACQUIRE_AT=2026-10-21T20:04:36Z
```

⚠️ `owner` 文件里的 PID（`56008`）**不是**闸存活证据 —— 取证只看 `stale` 字段。
⚠️ 等闸期间**未跑** `git fetch`、未跑任何构建（并发构建会共用
`backend/**/target/` 与 PG 容器 ⇒ 假红）。

### 二、差异规模：三个口径，别被大数误导

| 口径 | 结果 | 说明 |
| --- | --- | --- |
| `git diff --stat A...B`（三点，协议 §2 建议） | 1292 files / +815033 / -92692 | **误导**：三点 diff 从 merge-base 起算，把 main 上「源自集成头自己」的内容全算进来 |
| `git diff --name-status A B`（两点，tip 对 tip） | **129 files**：94 D / 23 A / 12 M | **真实差异** |
| `git log --oneline A..origin/main` | **26 commits** | main 领先的提交数 |

### 三、冲突面取证：两个文件，10 个冲突区

```
$ git diff --name-only --diff-filter=U
apps/web/src/lib/api-proxy.ts
tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
```

`api-proxy.ts` 的 7 个冲突区**全部双向**（第 17 轮是 4 个「纯 ours 侧新增」）。
用 `git merge-file --diff3`（纯函数，不碰工作区）逐区量行数：

| 冲突区 | ours 行数 | theirs 行数 | 性质 |
| --- | --- | --- | --- |
| 1（行 500–839） | 242 | 94 | 双向 |
| 2（行 2550–3195） | 619 | 8 | 双向 |
| 3（行 3209–3923） | 47 | 658 | 双向 |
| 4（行 4384–4443） | 54 | 1 | 双向 |
| 5（行 4539–4645） | 51 | 51 | 双向 |
| 6（行 5880–6076） | 118 | 75 | 双向 |
| 7（行 6111–9427） | 1763 | 1550 | 双向 |

**根因（blob 级证明）**：

```
$ git rev-parse origin/main:apps/web/src/lib/api-proxy.ts
7f844ce6483b008186aa99c060fd582d98ba2d6e
$ git rev-parse 976d7d42:apps/web/src/lib/api-proxy.ts   # merge(14.2.2.2): 自动规则
7f844ce6483b008186aa99c060fd582d98ba2d6e        ← 逐字节相同
$ git merge-base --is-ancestor 976d7d42 integration/module-dev
YES                                                ← 线性祖先
$ diff main版 head当前版
  只属于 main 的行： 0        ← 取集成头侧零丢失
  只属于 head 的行： 404
```

⇒ main 在「集成头 → main」那轮 PR 里，把 `api-proxy.ts` 取成了集成头当时的快照；
集成头此后又并入 8 个 14.x 车道（`14.1.1.2 / 14.1.1.3 / 14.1.2.1 / 14.2.2.1 /
14.3.1.1 / 14.3.1.2 / 14.3.2.2 / 14.4.1.1`），每个车道都往同一个 `services[]`
里新增一条独立元素 ⇒ 双方改的是**同一批插入锚点** ⇒ 必然双向冲突。
**retry 不会自愈**：只要 main 侧不重新吸收这 8 个车道的白名单条目，
下一轮还是撞同一堵墙。

`MAIN_MERGE_CRON_PROMPT.md` 的 3 个冲突区是**真双向**：
main 独有 44 行（`a105acf0` 的「落地树哈希」判据段 + 「真实增量」措辞改写），
集成头独有 6 行 ⇒ **单边取任一侧都会丢内容**。

### 四、main 零业务新增：三路独立取证

**① 台账模块级比对**（465 个模块逐 ID）：

```
main 台账：        已交付 297 / 进行中 0 / 未开发 168
集成头台账：      已交付 305 / 进行中 0 / 未开发 160
状态不同的模块（8 个，全部是「main=未开发, head=已交付」）：
  14.1.1.2  14.1.1.3  14.1.2.1  14.2.2.1
  14.3.1.1  14.3.1.2  14.3.2.2  14.4.1.1
main 领先：0 个
```

**② 契约产物集合差**（`paths` / `components.schemas` 精确集合比对）：

| 契约 | main | 集成头 | 只在 main | 只在 head |
| --- | --- | --- | --- | --- |
| `channel.openapi.json` | 547 paths / 1004 schemas | 614 / 1159 | **0 / 0** | 67 / 155 |
| `catalog.openapi.json` | 494 / 863 | 494 / 863 | 0 / 0 | 0 / 0 |
| `trade.openapi.json` | 789 / 1717 | 789 / 1717 | 0 / 0 | 0 / 0 |
| `warehouse.openapi.json` | 518 / 1077 | 518 / 1077 | 0 / 0 | 0 / 0 |

**③ 树差异分类**（tip 对 tip，129 个文件）：

| 方向 | 数量 | 内容 |
| --- | --- | --- |
| 集成头有、main 没有 | 94 | 42 个 `backend/services/**` + 40 个 `apps/web/**`（8 个广告车道的代码）+ 12 个 `docs/sessions/**` |
| main 有、集成头没有 | 23 | 16 个 docs（15 个会话日志 + 1 个 backlog）+ 8 个 `tools/progress-site/**`（进度看板工具） |
| 两侧都有、内容不同 | 12 | `api-proxy.ts`、台账、7 份契约、`.gitignore`、`MAIN_MERGE_CRON_PROMPT.md` |

⇒ **main 侧没有任何集成头不具备的业务内容。**
本轮即使把冲突解掉并合入，集成头净增的也只有 **16 个文档 + 8 个看板工具文件**，
业务代码净增 **0**，而 8 个广告车道模块的代码 **只有集成头有**。

## 后续待办

1. **（需有授权的人/调度器）解 `api-proxy.ts` 冲突**：取集成头侧即可，
   已有 blob 级证明「零丢失」（main 的 blob 是集成头 `976d7d42` 的副本，
   `976d7d42` 是当前集成头的线性祖先）。**不要**再逐条手写 alternation 求并。
2. **（需有授权的人/调度器）解 `MAIN_MERGE_CRON_PROMPT.md` 冲突**：这是**真双向**，
   必须人工求并 —— main 独有的 44 行「落地树哈希」判据段要保留，
   集成头独有的 6 行小改也要保留。
3. **解完冲突后不必重跑本同步任务**：本轮已证 main 侧零业务新增，
   合入收益只有文档与看板工具。**真正的缺口在反方向** ——
   `main` 落后集成头 8 个 14.x 广告/增长车道模块，
   需要重切一次「集成头 → main」的 PR（走 `MAIN_MERGE_CRON_PROMPT.md` 那套流程），
   而不是继续把 main 往集成头同步。
4. `main` 侧的 `api-proxy.ts` 落后集成头 404 行，是**第 18 轮同一堵墙的根**；
   只要 main 不重新吸收这 8 个车道的白名单条目，下一轮同步必然继续撞。
