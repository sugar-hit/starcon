# 2026-10-21 17:20: main 同步第 9 轮 —— 撞 4 文件冲突，main 模块领先数仍为 0

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 9 轮。

**结果：冲突未并入，未推送任何合并内容，集成头 HEAD 保持 `5f6d41af` 不变。**
四选一里的「**冲突未并入**」。

本轮与前 8 轮（0115 / 0315 / 0415 / 0515 / 0643 / 0740 / 1127 / 1145）撞的是**同一堵墙**：
`origin/main` 是用 **squash** 合入的，`merge-base` 仍被拉回 `1bb270a0`，
`git merge --no-ff origin/main` 仍以冲突告终。

**本轮的独立增量**（不采信历史会话日志，全部重新取证）：

1. **冲突面从 3 文件变 4 文件** —— `docs/backlog/.../main-squash-merge-divergence.md`
   与 `PriceHistoryIntegrationTest.java` 这两个老冲突**已消失**，
   换成了 **3 个 trade 服务 port 接口的 add/add**：
   `ReplenishmentCatalogPort.java` / `StockoutInventoryPort.java` / `SupplierPartyQueryPort.java`。
   ⇒ 冲突面**随 main 前进而漂移**，不是固定集合 ⇒ 这是「squash 分叉」的必然后果，不是新 bug。
2. **main 侧模块领先数仍然 = 0**（本轮逐模块 ID 独立重算，见取证三）。
3. **闸：本轮等待仅 ~2 分 17 秒**，未复现前几轮 2h25m 的闸情。

## 决策 / 结论

1. **冲突只报不解**（协议 `tools/module-progress/SYNC-MAIN.md` §0 硬规定 + 第 3 步）。
   本轮 `git merge --abort`，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `5f6d41af`，**未 push 合并内容**。

2. **本轮 4 个冲突文件的结构（base / ours / theirs 行数 + main 侧独有内容）**：

   | 冲突文件 | git 冲突类型 | base / ours / theirs | ours→theirs numstat | main 侧独有内容 | 判定 |
   | --- | --- | --- | --- | --- | --- |
   | `apps/web/src/lib/api-proxy.ts` | content | 2634 / 5840 / 5722 | `+0 -118` | **0 行** | ours 是**严格超集**；main 侧是集成分支的祖先快照 |
   | `backend/services/trade/.../ReplenishmentCatalogPort.java` | add/add | 0 / 481 / 250 | `+0 -231` | **0 行** | ours 严格超集，main 侧少 231 行 |
   | `backend/services/trade/.../StockoutInventoryPort.java` | add/add | 0 / 253 / 227 | `+0 -26` | **0 行** | ours 严格超集，main 侧少 26 行 |
   | `backend/services/trade/.../SupplierPartyQueryPort.java` | add/add | 0 / 213 / 171 | `+0 -42` | **0 行** | ours 严格超集，main 侧少 42 行 |

   ⇒ **四个文件的 numstat 全是 `+0 -N`**（theirs 相对 ours 零插入、只删 N 行）
   ⇒ **main 侧没有任何一行是集成头没有的新代码**。
   ⇒ `merge-resolve.mjs --base origin/main --head integration/module-dev` 对四个文件
   **一致给出「取集成分支侧」**（`ancestorSnapshot=true`，累积型三件套全成立），且 **`needHuman: 0`**。

3. **`merge-union` 驱动按预期工作**：台账 `module-progress.md` 按模块 ID 求并集折叠 465 行同 ID 记录（**无冲突**）；
   `packages/api-contracts/**` 8 个文件全部干净合并；1 个文件走了「行级兜底并集」并打印人工确认提示。

4. **台账数字**（merge abort 后，集成头 `5f6d41af`）：已交付 **282** / 进行中 **0** / 未开发 **183**（465 个模块，完成率 60.6%）。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动）。
   台账行数 **465**，模块 ID **无重复**。

5. **本轮只提交了本会话文档**（AGENTS.md「每次有结论的会话都要落到 `docs/sessions/`」），
   **业务代码零改动**；文件名带轮次时间戳，main 侧不存在同名路径 ⇒ 不会给下一轮制造 add/add 冲突。

## 取证

### 一、闸：等了 2 分 17 秒，前一个持闸者是活的

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 108, "ttlSeconds": 4200, "stale": false }
```

⇒ `stale=false` ⇒ 有活着的 tick 持闸 ⇒ 等它释放（不抢、不清、不 force）。
`owner` 里的 pid `74781` **查无此进程** —— 与协议 §1 的警告一致：
`gate acquire` 是短命进程，真正的持闸者是后续长跑的调度器。
闸外取证：`ps` 看到 pid **76738** 正在跑 `node tools/module-progress/merge-resolve.mjs --base`（17:15 启动）⇒ 活性确认。

**未按 `ageSeconds` 猜**，全程只看 `stale` 字段。
按协议用**密集 `acquire` 竞态**（`mkdirSync` 原子互斥，失败无副作用）而不是「先观测到 free 再 acquire」：

```
GATE ACQUIRED at 17:16:48 after 27 attempts
```

前一个持闸者在 17:16 前完成了它自己的 merge-resolve 并 release；它留下的是一次**已 abort 的 merge**
（`MERGE_HEAD` 已清、HEAD 仍在 `5f6d41af`）⇒ 本轮接手时工作区是干净的。

### 二、fetch 与差异规模

```
$ git fetch origin --prune
$ git merge-base --is-ancestor origin/main integration/module-dev  →  HAS_UPDATE
$ git rev-list --count integration/module-dev..origin/main  →  8
$ git diff --stat integration/module-dev...origin/main | tail -3
 1028 files changed, 652281 insertions(+), 93320 deletions(-)
```

并入的 8 个 main 提交（`git log --oneline integration/module-dev..origin/main`）：

| 提交 | 内容 |
| --- | --- |
| `478778ef` | 需求合并与批量、采购单列表等 4 项能力交付 |
| `a83380c6` | 记录统一错误处理器掩盖代码缺陷的取证结论 |
| `73791049` | 待检处理与授权凭证交付 |
| `a257a922` | 品牌授权交付 |
| `3bedc528` | 同步集成分支最新进度（无新增模块） |
| `a9f3b4d0` | 平台价格同步、采购申请等 7 项能力交付 |
| `ec225641` | 到岸成本、成本分摊等 24 项能力交付 |
| `d28188d6` | Integration/module dev (#11) |

### 三、模块级差距（逐模块 ID + 状态 join，两边各 465 行，独立重算）

```
集成头 (5f6d41af)：282 已交付 / 0 进行中 / 183 未开发
origin/main      ：280 已交付 / 0 进行中 / 185 未开发

6.3.1.2: ours=已交付 main=未开发
6.6.1.2: ours=已交付 main=未开发
⇒ differing modules: 2 | main 领先: 0 | 集成头领先: 2
```

⇒ **main 没有任何一个模块是集成头没有的。** 8 个 main 提交带来的能力（24 项 + 7 项 + 4 项 + 品牌授权 +
待检处理 + 授权凭证 + 批量搬迁），在集成头侧**全部已是「已交付」**。
⇒ 冲突**纯属 squash 抹血 + 并行新增的副作用**，不是内容分歧。

### 四、合并结果与 abort

```
$ git merge --no-ff origin/main -F <merge-msg>
Auto-merging apps/web/src/lib/api-proxy.ts
CONFLICT (content): Merge conflict in apps/web/src/lib/api-proxy.ts
CONFLICT (add/add): Merge conflict in .../ReplenishmentCatalogPort.java
CONFLICT (add/add): Merge conflict in .../StockoutInventoryPort.java
CONFLICT (add/add): Merge conflict in .../SupplierPartyQueryPort.java
Automatic merge failed; fix conflicts and then commit the result.

$ git merge --abort        →  MERGE_HEAD 已清，git status --porcelain 为空，HEAD = 5f6d41af
```

⚠️ **本轮没有跑快速预检（`mvnw -q compile` + `pnpm typecheck`）**：
预检的对象是**合并后的树**，而合并已 abort ⇒ 树未变 ⇒ 跑它没有信息增量，
且 `mvnw verify` 级构建是发热高峰（协议 §4 禁令）。

## 关键 trade-off / 讨论

- **为什么不按 `merge-resolve.mjs` 的判定直接「取集成分支侧」把冲突解掉**：
  判定工具本身给出的是**有证据的**解法（4 个文件全部「取集成分支侧」、`needHuman: 0`），
  证据也支持它（本轮独立取证：numstat 全是 `+0 -N`，main 侧零独有行）。
  **但本任务的方向是 main → 集成头，协议 `SYNC-MAIN.md` §0 写死「❌ 自行解冲突」，
  第 3 步写死「冲突时 `git merge --abort` … 不要自己解。下一轮再试」。**
  ⇒ **本轮遵守协议，报冲突不自行解决。**
  另需注意：`merge-resolve.mjs` 与 `MAIN_MERGE_CRON_PROMPT.md`（**反向**任务：集成头 → main，走 PR）
  才配套 —— 那份任务书 §9 明确写了「不许无证据地取一边；必须先跑 4.2 的判定工具」，
  **它的判定流程属于那个任务，不属于本任务**。
  ⇒ 两个方向的协议不要混用。

- **闸的竞态写法**：协议 §1 给了实测数据 —— 交接窗口可窄到零宽度，
  「每 20 秒 `gate status` → 看到 free 再 acquire」会**整段错过**它。
  本轮直接用**密集 `acquire` 竞态**（1 秒 1 次），27 次命中。

## 涉及文件

- 冲突文件（**未解决，已 abort**，4 个）：
  - `apps/web/src/lib/api-proxy.ts`
  - `backend/services/trade/src/main/java/com/starcon/trade/ReplenishmentCatalogPort.java`
  - `backend/services/trade/src/main/java/com/starcon/trade/StockoutInventoryPort.java`
  - `backend/services/trade/src/main/java/com/starcon/trade/SupplierPartyQueryPort.java`
- 本会话文档（新增，已提交）：
  - `docs/sessions/2026-10/2026-10-21-1720-main-sync-conflict-4-trade-ports.md`
- 业务代码 —— **零改动**；集成头 —— **零推送**（只推了本会话文档）

## 后续待办

1. **（根治项，仍未解决）发布侧不要再用 squash 合入 `main`**：
   连续 9 轮同步全部撞在「main 的树 ≠ 集成分支任何祖先树」这一条上。
   发布侧改用**真实提交**（非 squash）⇒ merge-base 自然前移 ⇒ 冲突随之消失。
   这与 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` 的结论一致。
2. **（本轮新增取证）冲突面会随 main 前进而漂移，不是固定集合**：
   本轮 3 个老冲突消失、新增 3 个 trade port 的 add/add。
   ⇒ 每轮都必须**重新取证冲突文件名单**，不能沿用上一轮的结论。
3. **（协议侧，可选）本任务与反向任务在「冲突是否自行解」上规则相反**：
   `SYNC-MAIN.md` 禁止自行解冲突，`MAIN_MERGE_CRON_PROMPT.md` 则允许在判定工具给出证据后取一边。
   建议在两份任务书里各写一句「本规则只适用于本方向」，避免下一个 session 混用。

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`（本轮执行依据）
- 反向任务协议：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（集成头 → main，走 PR，**不是本任务**）
- backlog：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`
- 上一轮同题：`docs/sessions/2026-10/2026-10-21-1145-main-sync-conflict-3-files.md`
- 历史同题：`docs/sessions/2026-10/2026-10-21-1127-main-sync-conflict-main-advanced.md` 等 8 轮
