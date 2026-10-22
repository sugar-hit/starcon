# 2026-10-22 01:15 集成头同步 main（第 16 轮 · 冲突未并入 · 冲突面 5 → 1 文件）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 同步。
本轮**结果：冲突未并入** —— 合并在 **1 个文件**上真冲突，按协议 `git merge --abort` 后如实上报，
**未自行解冲突、未 push、未跑预检**（无合并结果可检）。

这是同一同步任务的**连续第 16 轮**，但**本轮与前 15 轮有两处实质变化**：

1. **`origin/main` 自 15 轮以来首次前进** —— tip 从 `9efa2fee` 走到 **`ba9c11ab`**（+22 颗提交，
   前轮待并入 20 颗）。
2. **冲突面从 5 个文件缩到 1 个文件** —— 前轮 5 个冲突文件里有 **4 个已自动合并干净**
   （`api-proxy.ts` + 3 个 trade/warehouse Java 文件），**只剩 1 个**会话日志文档冲突。

## 决策 / 结论

1. **冲突只报不解**（`SYNC-MAIN.md` §0 硬规定 + 任务 prompt 逐字要求）。
   `git merge --abort` 退出码 0，`MERGE_HEAD` 已清（`git rev-parse --verify MERGE_HEAD` → `fatal`），
   `git diff --name-only --diff-filter=U` 为 0 行，工作区干净，HEAD 停在 `3babe0d6`（== 本轮 PRE），**未 push**。

2. **闸：一步抢到，未等。**
   - `01:15:0x` `gate status` → `free / stale=false` ⇒ 直接 `gate acquire` 成功（owner pid `18797`，
     时间戳 `2026-10-21T17:15:14.299Z` = 01:15:14 +0800）。
   - 本轮**没有出现闸争用**，未进入「密集 acquire 竞态」路径；全程闸由本轮持有至收尾 release。

3. **唯一的冲突是纯文档冲突，且可零损失机械解决**（取证结论，**解法仍属人工 / 流水线 `merge-ready`**）：
   - 冲突文件：`docs/sessions/2026-10/2026-10-22-batch-56-dispatch-4-ads-lanes.md`
   - **冲突类型：add/add**（真分叉，非 content）——
     `git merge-base origin/main origin/integration/module-dev` = `1bb270a0`，
     该路径**不在 merge base 里**（`git ls-tree $BASE <path>` 为空），
     两侧各自独立新增：集成头侧来自 `eefb0c27`，main 侧来自 `ba9c11ab`。
   - ⚠️ `merge-resolve.mjs` 把类型标成 `content`（**类型标签不可信**，与上轮同款问题），但**结论可信**：
     main 侧 blob `981e977a` 在集成分支自己的历史里**逐字节可达**
     （`eefb0c27` 的该路径 blob 正是 `981e977a`）⇒ 祖先快照判定成立 ⇒ 判「取集成分支侧」。
   - ✅ **本轮补强一条更硬的证据**：main 侧内容是集成头侧内容的**精确前缀** ——
     把集成头 blob `792c2a65` 的前 `wc -c < main_blob` 字节切出来与 main blob 逐字节 diff，**完全相同**
     ⇒ 集成头侧 = main 侧 + 追加的「4 条车道并入预演」一节 ⇒ **取集成分支侧零内容丢失**，
     不存在「取一边会丢信息」的风险。
   - `merge-resolve.mjs --base origin/main --head origin/integration/module-dev` **退出码 0**
     ⇒ 判「所有冲突都有可机械执行的解法」⇒ 流水线下一轮 `merge-ready` 走工具通道即可自动落。

4. **前轮 5 个冲突文件中 4 个已自动合并干净**（本轮独立复核，非采信上轮结论）：
   逐个比对三方 blob，`api-proxy.ts` / `PurchaseRequestModels.java` / `PurchaseRequestService.java` /
   `InventoryAdjustmentService.java` 在 **main 侧与集成头侧 blob 已完全相同**
   （`7f844ce6` / `02084026` / `f113aae4` / `3e8da6ab`），故 git 直接取同一 blob，**无冲突无求并**。
   ⇒ 前轮「必须人工把两层 alternation 并起来」的担忧**已被 main 的前进自然消解**，无需人工并。

5. **台账本轮零变化（重要）**：
   - `git ls-tree` 显示集成头与合并结果树的 `module-progress.md` blob **同为 `b9c8dc62`** ⇒ **逐字节相同**。
   - 抽查 main 本轮交付的模块（`6.1.1.3` 供应商评级 / `6.1.2.2` 同款货源推荐 / `16.4.2.1` 品牌授权 /
     `14.1.1.1` 活动列表 / `11.5.1.1` 退货分析）在台账里**早已是 `已交付`** ——
     说明这些交付的台账行**此前已随 `chore: 同步集成分支最新进度` 往返进集成头**。
   - ⇒ **同步前后台账数字完全一致：465 / 已交付 297 / 进行中 0 / 未开发 168（63.9%）**，
     本轮**不改变进度基线**。

6. **契约 8 个文件全部干净求并，结果 blob == main 侧 blob**
   （`catalog/channel/trade/warehouse.openapi.json` + `contracts/{catalog,channel,trade,warehouse}.ts`）
   ⇒ merge-union 的按键求并在本轮**逐文件得到与 main 侧完全一致的结果**，无行级兜底并集的残留告警。

7. **根因仍未消除**：`d28188d6`（PR #11）是 squash 合并 ⇒ merge base 仍停在 `1bb270a0`，
   **与前 15 轮完全相同**。main 的前进靠 `chore: 同步集成分支最新进度` 这类 squash 提交带过来，
   **不会前进 merge base**。⇒ 只要 main 侧继续用 squash，合流方向就一直停在同一条 base 上；
   正解仍是让反方向合流任务以**真合并**（保留两个父提交）落进 main。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | **冲突未并入**（1 个文件，较上轮 5 → 1） |
| `origin/main` HEAD | **`ba9c11ab`** chore: 同步集成分支最新进度（**15 轮以来首次前进**，前轮 `9efa2fee`） |
| 待并入 main 提交数 | **22**（`d28188d6` … `ba9c11ab`；上轮 20） |
| 差异规模 | **1289 文件、+814486 / -92692 行**（上轮 1158 文件、+721124 / -93143） |
| merge base | `1bb270a0`（**与前 15 轮完全相同** —— PR #11 是 squash 合并） |
| 集成头 PRE = HEAD | **`3babe0d6`**（前后未变，== 本轮 PRE） |
| 冲突文件 | **1 个**（见下表） |
| 自动合并成功 | `module-progress.md`（blob **逐字节未变** `b9c8dc62`）、契约 8 文件（结果 == main 侧 blob）、`api-proxy.ts` + 3 个 trade/warehouse Java 文件（两侧 blob 已相同） |
| 合并结果树与集成头差异 | **32 个文件**（`git merge-tree --write-tree` 只读取证） |
| 合并 commit | **无**（abort 后未落） |
| 预检 | **未跑**（无合并结果可检；协议第 4 步前提不成立） |
| push | **未 push**（合并未落地） |
| 台账（同步前后无变化） | 已交付 **297** / 进行中 **0** / 未开发 **168**（共 465，完成率 63.9%） |
| 闸 | **01:15:14 acquire**（owner pid 18797）→ 收尾 release |
| 闸争用 | **无**（本轮 `gate status` 即为 `free`） |

## 冲突文件清单（1 个）

| 冲突文件 | 冲突类型 | 冲突区 | ours blob（集成头） | theirs blob（main） | 独立复核结论 | 处置 |
| --- | --- | --- | --- | --- | --- | --- |
| `docs/sessions/2026-10/2026-10-22-batch-56-dispatch-4-ads-lanes.md` | **add/add**（merge base 里无此路径，stage 1 缺失） | 1 处（行 271–324） | `792c2a65`（20993 B） | `981e977a`（17650 B） | main 侧 blob 在集成分支历史（`eefb0c27`）内可达 ⇒ 祖先快照；且 main 内容是集成头内容的**精确前缀** ⇒ 取集成头侧**零丢失** | 交流水线 `merge-ready` 走 `merge-resolve` 通道自动落 |

## 下一轮解法（给人工 / 流水线）

1. **唯一冲突直接取集成分支侧** —— 依据是「main 侧 blob 在集成分支历史内可达」+「main 内容是集成头内容的精确前缀」，
   **不依赖 merge base**，与 squash 抹祖先的根因无关，稳定可复算。
2. `merge-resolve.mjs` 对本轮退出码 0 ⇒ 流水线 `merge-ready` 应能**自动**落这个冲突；
   若 `merge-ready` 仍报冲突，说明它比对的 ref 组合与本轮不同，需人工核 ref。
3. **解完必须跑 `node tools/module-progress/sync.mjs` 重生成台账**，再判 `sync.mjs --check` 绿
   （本轮合并结果树的台账 blob 未变，但解冲突后仍需重跑确认）。
4. **推完必须重点看这个 merge commit 的 L2** —— 它的代码**没经过本流水线的 L1/L2**，
   且本轮带进 **1289 文件 / +814486 行**，是本方向迄今最大的一次合入。
5. **根因仍在**：merge base 停在 `1bb270a0` 不动 ⇒ 冲突会**周期性原地重现**
   （本轮只剩 1 个会话日志，下一轮 main 再前进又可能冒新的）。
   正解仍是让反方向合流任务以**真合并**（保留两个父提交）落进 main。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/lane.mjs` — `gate acquire` / `gate status` / `gate release` 语义
- `tools/module-progress/sync.mjs` — 台账重生成 / `--check` 自检（本轮 `✓ 台账与蓝图一致`）
- `tools/module-progress/merge-resolve.mjs` — 冲突解法判定器（本轮退出码 0，仅作取证）
- `tools/module-progress/merge-union.mjs` — 合并驱动（契约/台账按键求并，本轮 8 个契约文件结果 == main 侧）
- `docs/product/capability-blueprint/module-progress.md` — 台账（**本轮无变化**，blob `b9c8dc62`）
- `docs/sessions/2026-10/2026-10-22-0024-main-sync-conflict-5-files-round15.md` — 上轮取证（5 文件冲突面）

## 关键 trade-off

- **只报冲突、不解冲突**：协议与任务 prompt 双重硬规定；`merge-resolve.mjs` 的授权出处是**反方向**任务，
  本方向无授权通道 ⇒ 工具结论只作取证。即便证据已足够充分（「取集成头侧、零丢失」），
  本轮仍**不执行**解法，只把它写成可机械复算的判据交给下一轮 `merge-ready`。
- **补「精确前缀」这条硬证据 vs 只写「祖先快照」**：祖先快照只能证明 main 是旧版本，
  补前缀比对才能**排除「取 ours 会丢 main 侧新内容」**这一类风险 —— 本轮两侧本就是
  「同一文件 + 集成头侧多追加一节」，前缀成立 ⇒ 取 ours 零丢失。
- **会话日志落在集成头工作区、以纯文档本地提交落盘、不 push**：协议禁止在 `main` 上提交任何东西；
  留成未跟踪文件会弄脏工作区、堵死下一轮的 merge 前置条件（`git status --porcelain` 必须为空）。
  ⇒ 与上轮同构。
- **不跑预检 vs 跑一下看看**：无合并结果可检（merge 已 abort），强行构造预检等于绕过协议第 4 步的前提，
  且会与流水线 L2 争 `backend/**/target/` ⇒ 明确不跑。
