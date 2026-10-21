# 2026-10-21 21:23: main 同步第 10 轮 —— 冲突面 4 文件 → 1 文件，main 模块领先数仍为 0

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 10 轮。

**结果：冲突未并入，未推送任何合并内容，集成头 HEAD 保持 `fdce2c5d` 不变。**
四选一里的「**冲突未并入**」。

本轮与前 9 轮（0115 / 0315 / 0415 / 0515 / 0643 / 0740 / 1127 / 1145 / 1720）撞的是**同一堵墙**：
`origin/main` 仍用 **squash** 合入，`merge-base` 仍停在 `1bb270a0`，
`git merge --no-ff origin/main` 仍以冲突告终。

**本轮的独立增量**（不采信历史会话日志，全部重新取证）：

1. **冲突面从 4 文件缩到 1 文件** —— 上一轮的 3 个 trade port add/add 冲突
   （`ReplenishmentCatalogPort` / `StockoutInventoryPort` / `SupplierPartyQueryPort`）
   **本轮两侧已逐字节相同**，git 干净合并；只剩 `apps/web/src/lib/api-proxy.ts` 一个文件。
   ⇒ 冲突面**继续随 main 前进而漂移**（上一轮「3 老冲突消失、3 新增」，本轮「3 个又消失」）。
2. **本轮把冲突性质从「内容分歧」精化为「表达方式分歧」**（新证据，见取证四）：
   两侧**路由覆盖完全相同**（62 vs 62 个 path token，**对称差为 0**），
   差别只在 **4 个模块被集成头从 trade 的「超长单行 alternation」里提升为独立 `services[]` 条目**
   ⇒ main 侧**没有任何一行是集成头没有的**（`git diff ours theirs` 的 `+1` 是巨型正则的换行重排假象）。
3. **main 侧模块领先数仍然 = 0**（本轮逐模块 ID 独立重算，见取证三）。
4. **闸：本轮等待仅 ~53 秒**（密集 `acquire` 竞态，53 次命中），未复现前几轮 2h25m 的闸情。

## 决策 / 结论

1. **冲突只报不解**（协议 `tools/module-progress/SYNC-MAIN.md` §0 硬规定 + 第 3 步）。
   本轮 `git merge --abort`，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `fdce2c5d`，**未 push 合并内容**。

2. **本轮唯一冲突文件的结构**（4 个冲突区，**main 侧全部为空**）：

   | 冲突区 | `<<<<<<<` / `=======` / `>>>>>>>` 行号 | ours 行数 | theirs 行数 |
   | --- | --- | --- | --- |
   | 1 | 3852 / 3894 / 3895 | 41 | **0** |
   | 2 | 4201 / 4274 / 4275 | 72 | **0** |
   | 3 | 5529 / 5600 / 5601 | 70 | **0** |
   | 4 | 5943 / 6016 / 6017 | 72 | **0** |

   ⇒ 合计 ours **255 行** vs theirs **0 行**。
   ⇒ 4 个冲突区恰好对应集成头多出的 **4 条独立 `services[]` 条目**（各带一段模块说明注释）。

3. **`services[]` 条目数**：集成头 **121** 条（6108 行）vs main **117** 条（5840 行）⇒ 差 4 条。
   这 4 条（由注释里的模块 ID 独立取证）是：

   | 模块 ID | 名称 | 归属 |
   | --- | --- | --- |
   | 6.3.2.1 | 跟单与交期 | trade（卷 06 采购与供应） |
   | 6.3.1.3 | 采购单审批 | trade（卷 06 采购与供应） |
   | 6.6.2.1 | 试产单 | trade（卷 06 采购与供应） |
   | 11.4.1.1 | 平台纠纷列表 | trade（卷 11 售后与退货） |

   ⇒ 这 4 个模块在 main 侧**都还在那条超长 alternation 里**（路由 token 相同可证），
   集成头把它们**拆成了独立条目**（文件注释里写明：「本条目是新增一条 services[] 元素，
   不是往既有 trade 分支的 alternation 里并分支」——因为「多车道同时并进去 git 必然判冲突」）。

4. **路由覆盖比对（两侧 path token 集合，脚本独立取证）**：
   `pattern:` 字面量 ours=5 / main=5；抽出的 path token **ours=62 / main=62**；
   **对称差 = 0**（only-in-main = 0，only-in-ours = 0）。
   ⇒ **main 没有引入任何集成头不具备的放行路径。**

5. **`merge-union` 驱动按预期工作**：台账 `module-progress.md` 按模块 ID 求并集折叠 465 行同 ID 记录（**无冲突**）；
   `packages/api-contracts/**` 7 个文件全部干净合并；1 个文件走了「行级兜底并集」并打印人工确认提示。

6. **台账数字**（merge abort 后，集成头 `fdce2c5d`）：已交付 **287** / 进行中 **0** / 未开发 **178**
   （465 个模块，完成率 61.7%）。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动）。
   台账行数 **465**，模块 ID **无重复**。

7. **本轮只提交了本会话文档**（AGENTS.md「每次有结论的会话都要落到 `docs/sessions/`」），
   **业务代码零改动**；文件名带轮次时间戳，main 侧不存在同名路径 ⇒ 不会给下一轮制造 add/add 冲突。

## 取证

### 一、闸：等了 53 秒，密集 acquire 竞态命中

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 31, "ttlSeconds": 4200, "stale": false }
```

⇒ `stale=false` ⇒ 有活着的 tick 持闸 ⇒ 等它释放（不抢、不清、不 force）。
`owner` 里的 pid `69134` **查无此进程** —— 与协议 §1 的警告一致：
`gate acquire` 是短命进程，真正的持闸者是后续长跑的调度器（PID 不能当活性证据）。
闸外取证：`ps` 未见同 worktree 内的 java / mvnw / pipeline node 进程（取闸瞬间持闸者可能正处于非构建阶段）。

**未按 `ageSeconds` 猜**，全程只看 `stale` 字段。
按协议用**密集 `acquire` 竞态**（`mkdirSync` 原子互斥，失败无副作用）而不是「先观测到 free 再 acquire」：

```
GATE ACQUIRED after 53s
```

⚠️ 取闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log`），**连 `git fetch` 都没跑**，
避免与持闸 tick 的 L2 争抢 `backend/**/target/` 与 PG 容器。

### 二、fetch 与差异规模

```
$ git fetch origin --prune
  [new branch]  chore/sync-module-dev -> origin/chore/sync-module-dev
$ git merge-base --is-ancestor origin/main integration/module-dev  →  HAS_UPDATE
$ git rev-list --count integration/module-dev..origin/main  →  9
$ git diff --stat integration/module-dev...origin/main | tail -3
 1061 files changed, 672546 insertions(+), 93183 deletions(-)
```

并入的 9 个 main 提交（`git log --oneline integration/module-dev..origin/main`）：

| 提交 | 内容 |
| --- | --- |
| `05fb216e` | 采购单创建与编辑、打样与样品管理交付（**本轮相对上一轮新增**） |
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
集成头 (fdce2c5d)：287 已交付 / 0 进行中 / 178 未开发
origin/main      ：282 已交付 / 0 进行中 / 183 未开发

11.4.1.1: ours=已交付 main=未开发
6.3.1.3 : ours=已交付 main=未开发
6.3.2.1 : ours=已交付 main=未开发
6.6.2.1 : ours=已交付 main=未开发
7.4.1.1 : ours=已交付 main=未开发
⇒ differing modules: 5 | main 领先: 0 | 集成头领先: 5
```

⇒ **main 没有任何一个模块是集成头没有的。** 9 个 main 提交带来的能力
（24 项 + 7 项 + 4 项 + 品牌授权 + 待检处理 + 授权凭证 + 采购单创建编辑 + 打样样品 + 批量搬迁），
在集成头侧**全部已是「已交付」**。
⇒ 冲突**纯属 squash 抹血 + 并行新增的副作用**，不是内容分歧。

### 四、冲突性质精化（本轮新证据）

**上一轮**的结论是「ours 是严格超集，main 侧零独有行」。
**本轮**进一步把「超集」拆细，避免把它误读成「main 有代码没同步过来」：

| 维度 | 集成头 | origin/main | 结论 |
| --- | --- | --- | --- |
| `services[]` 条目数 | 121 | 117 | 集成头多 4 条**独立条目** |
| 文件行数 | 6108 | 5840 | 集成头多 268 行（4 条目 + 注释） |
| `pattern:` 字面量数 | 5 | 5 | 相同 |
| 抽出的 path token 数 | **62** | **62** | **相同** |
| path token 对称差 | — | — | **0**（main 无独有、集成头无独有） |
| `git diff ours theirs` numstat | — | — | `+1 -269`（**+1 是巨型正则换行重排的假象**） |
| 3 个 trade port | — | — | 本轮两侧**逐字节相同** ⇒ 干净合并 |

⇒ 冲突的根因是**表达粒度分歧**：main 把 4 个 trade 模块留在那条超长 alternation 里，
集成头把它们提升为独立 `services[]` 条目（正是为了让多车道并行时 git 能干净求并——
文件注释里明确写了这个设计动机）。git 无法把「一条巨型 alternation」与「巨型 alternation + 4 条独立条目」
自动对齐 ⇒ 4 个冲突区，**main 侧全空**。

### 五、合并结果与 abort

```
$ git merge --no-ff origin/main -F <merge-msg>
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
Auto-merging apps/web/src/lib/api-proxy.ts
CONFLICT (content): Merge conflict in apps/web/src/lib/api-proxy.ts
Automatic merge failed; fix conflicts and then commit the result.

$ git diff --name-only --diff-filter=U   →  apps/web/src/lib/api-proxy.ts   （仅此 1 个）

$ git merge --abort        →  MERGE_HEAD 已清，git status --porcelain 为空，HEAD = fdce2c5d
```

⚠️ **本轮没有跑快速预检（`mvnw -q compile` + `pnpm typecheck`）**：
预检的对象是**合并后的树**，而合并已 abort ⇒ 树未变 ⇒ 跑它没有信息增量，
且 `mvnw verify` 级构建是发热高峰（协议 §4 禁令）。

## 关键 trade-off / 讨论

- **为什么不按 `merge-resolve.mjs` 的判定直接「取集成分支侧」把冲突解掉**：
  判定工具本身给出的是**有证据的**解法，且本轮独立取证也支持它
  （main 侧路由覆盖与集成头**完全相同**，零独有路径）。
  **但本任务的方向是 main → 集成头，协议 `SYNC-MAIN.md` §0 写死「❌ 自行解冲突」，
  第 3 步写死「冲突时 `git merge --abort` … 不要自己解。下一轮再试」。**
  ⇒ **本轮遵守协议，报冲突不自行解决。**
  另需注意：`merge-resolve.mjs` 与 `MAIN_MERGE_CRON_PROMPT.md`（**反向**任务：集成头 → main，走 PR）
  才配套 —— 那份任务书 §9 明确写了「不许无证据地取一边；必须先跑 4.2 的判定工具」，
  **它的判定流程属于那个任务，不属于本任务**。
  ⇒ 两个方向的协议不要混用。

- **闸的竞态写法**：协议 §1 给了实测数据 —— 交接窗口可窄到零宽度，
  「每 20 秒 `gate status` → 看到 free 再 acquire」会**整段错过**它。
  本轮直接用**密集 `acquire` 竞态**（1 秒 1 次），53 次命中。

- **取证方式的选择**：本轮**没有**用 `git merge-file` 的草稿结果下结论。
  第一次用 `git merge-file -p --diff3` 得到 6 个冲突区，与真实 merge 的 4 个对不上；
  查因是仓库对 `packages/api-contracts/**` 与台账配了 `merge.contract-union` 驱动、
  而 `api-proxy.ts` 走默认驱动，且 `--diff3` 风格会改变冲突区切分。
  ⇒ **改用真实 `git merge` 的结果 + `git diff --diff-filter=U`** 重新取证，
  并用 `git show` 逐侧取 blob 做 token 集合比对（不依赖工作区中间态）。

## 涉及文件

- 冲突文件（**未解决，已 abort**，1 个）：
  - `apps/web/src/lib/api-proxy.ts`
- 本会话文档（新增，已提交）：
  - `docs/sessions/2026-10/2026-10-21-2123-main-sync-conflict-1-file-api-proxy.md`
- 业务代码 —— **零改动**；集成头 —— **零推送合并内容**（只推了本会话文档）

## 后续待办

1. **（根治项，仍未解决）发布侧不要再用 squash 合入 `main`**：
   连续 10 轮同步全部撞在「main 的树 ≠ 集成分支任何祖先树」这一条上。
   发布侧改用**真实提交**（非 squash）⇒ merge-base 自然前移 ⇒ 冲突随之消失。
   这与 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` 的结论一致。
2. **冲突面会随 main 前进而漂移，不是固定集合**：
   上一轮 3 个老冲突消失、新增 3 个 trade port 的 add/add；**本轮那 3 个 trade port 已逐字节相同、干净合并**。
   ⇒ 每轮都必须**重新取证冲突文件名单**，不能沿用上一轮的结论。
3. **（本轮新增）冲突根因是「表达粒度」而非「内容」**：
   集成头把 4 个 trade 模块从巨型 alternation 提升为独立 `services[]` 条目，
   main 侧仍留在巨型 alternation 里 ⇒ 路由覆盖完全相同，但 git 对不上齐。
   ⇒ 若将来允许在**本方向**用判定工具解冲突，**这 4 个冲突区是「取集成分支侧」的正确解**
   （main 侧零独有路由，不会丢功能）—— 但那需要先修改 `SYNC-MAIN.md` 的协议，不是本任务能自行决定的。
4. **（协议侧，可选）本任务与反向任务在「冲突是否自行解」上规则相反**：
   `SYNC-MAIN.md` 禁止自行解冲突，`MAIN_MERGE_CRON_PROMPT.md` 则允许在判定工具给出证据后取一边。
   建议在两份任务书里各写一句「本规则只适用于本方向」，避免下一个 session 混用。

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`（本轮执行依据）
- 反向任务协议：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（集成头 → main，走 PR，**不是本任务**）
- backlog：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`
- 上一轮同题：`docs/sessions/2026-10/2026-10-21-1720-main-sync-conflict-4-trade-ports.md`
- 历史同题：`docs/sessions/2026-10/2026-10-21-1145-main-sync-conflict-3-files.md` 等 9 轮
