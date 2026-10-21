# 2026-10-22 00:24 集成头同步 main（第 15 轮 · 冲突未并入 · 冲突面仍是同样 5 文件）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 同步。
本轮**结果：冲突未并入** —— 合并在同样 5 个文件上真冲突，按协议 `git merge --abort` 后如实上报，
**未自行解冲突、未 push、未跑预检**（无合并结果可检）。

这是同一同步任务的**连续第 15 轮**。与上轮（`2026-10-22-0007-main-sync-conflict-5-files-round14.md`，
5 文件）相比：**冲突面一字未变**（5 个文件的 ours/theirs blob 哈希与上轮逐字相同），
`origin/main` tip 仍停在 `9efa2fee`，merge base 仍是 `1bb270a0`，待并入提交仍是 20 颗 ——
**main 侧自上轮起没有前进，冲突面自然原地不动**。

## 决策 / 结论

1. **冲突只报不解**（`SYNC-MAIN.md` §0 硬规定 + 任务 prompt 逐字要求）。
   `git merge --abort` 退出码 0，`MERGE_HEAD` 已清，`git ls-files -u` 为 0 行，
   工作区干净，HEAD 停在 `eefb0c27`（== 本轮 PRE），**未 push**。

2. **闸：00:15:44 起等，约 9 分钟拿到，1s 间隔竞态。**
   - 00:15:44 `gate status` → `held / stale=false`（age 426s，TTL 4200s）⇒ 持闸者是**活着的**流水线 tick。
   - 取证：闸 `owner` 文件 pid `90539`、时间戳 `2026-10-21T16:08:02.910Z`（= 00:08:02 +0800）全程未变
     ⇒ **整轮闸从未被释放**。同 worktree 外的 `.worktrees/main-merge-234421`（分支 `chore/sync-module-dev`，
     反方向合流任务）正在跑 `mvnw verify`（23:48 起，catalog→channel→trade 逐服务推进）。
   - 按协议**不低频观测 status**，直接竞态 `gate acquire`（1s 间隔），
     **00:24:18 抢到闸**（owner pid 96610，acquire 用时约 9 分钟）。
   - 等闸期间**只做只读取证**：未 fetch、未 merge、未跑任何构建 ⇒ 不与持闸 tick 的 L2 争
     `backend/**/target/` 与 Testcontainers PG 容器，杜绝并发假红。

3. **等闸期间集成头被流水线推进了一格**：HEAD 从 `fa73a14e` 走到 `eefb0c27`
   （`docs(batch-56): 14.1.1.2 / 14.2.2.1 / 14.3.1.1 / 14.3.2.2 —— 派发轮：4 条卷 14（广告与增长）车道开工，迁移 V277–V280`）。
   ⇒ 本轮 PRE 是 `eefb0c27`，**不是**上轮的 `7b034555`。这属正常并行推进，非冲突。

4. **本轮独立复核（不采信上轮结论）与上轮一致，且补强了一处**：5 个冲突文件的 **main 侧 blob
   全部在集成分支自己的历史里逐字节可达**（本轮用 `git rev-list HEAD --objects` 重新验证）。
   其中 **`api-proxy.ts` 的 main 侧 blob `f6624aec` 也不例外** ——
   `git log --all --find-object=f6624aec` 显示它存在于集成分支的
   `99923993` / `87d2b41d` / `6707d30c`（均为 integration HEAD 的祖先）。

5. ⚠️ **上轮对 `api-proxy.ts` 的「真分叉 / 两层 alternation 都要并」判断，本轮证据指向需要修正**
   （仅作取证，**解法仍属人工**）：
   - `git diff f6624aec 7f844ce6` = **+478 / -1**；那 1 处删除是 `services[]` 里
     **单行超长 `inventory/...` 正则的整行替换** —— 集成头侧是**超集**（多出 `adjustment-approvals` 分支）。
   - `services[]` 元素按 `prefix:` 逐条比对：main **120** 条 / 集成头 **129** 条，
     **main 的 120 个 prefix 全部出现在集成头侧，一个不缺**（多出的 9 条是同 prefix 的新增 pattern 元素）。
   - ⇒ 集成头侧 `7f844ce6` 在两层上都是 main 侧 `f6624aec` 的**超集**，
     「集合包含 ✓」成立；「有序子序列 ✓ / 零删除 ✓」仍需人工按上轮给的三件套逐条跑
     （`apps/web/src/app/api/[...path]/route.test.ts` 的真 proxy 断言，含反向用例）。
   - ⚠️ 但上轮的「真分叉」判据本身没错 —— 冲突是**真的**（stage 1/2/3 齐全，base blob `2bcc22a8` 更老，
     两侧相对 base 都改过同一区域）。变的是**处置建议**：本轮证据支持「取集成分支侧」很可能即可，
     而不是必须人工把两层 alternation 手工并起来。**请人工以三件套判据复核后再定，不要照抄上轮结论。**

6. **台账本轮无变化，自检绿**：`node tools/module-progress/sync.mjs --check` → `✓ 台账与蓝图一致`。
   合并驱动本轮照常走行级兜底并集（驱动自报「走了行级兜底并集，请人工确认这个文件改对了」+
   「台账按模块 ID 求并集，折叠了 465 行同 ID 记录」），但因冲突 abort，**台账未落盘**。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | **冲突未并入**（5 个文件，与上轮完全相同） |
| `origin/main` HEAD | `9efa2fee` Merge PR #23（fetch 前后均无新提交） |
| 待并入 main 提交数 | **20**（`d28188d6` … `9efa2fee`） |
| 差异规模 | 1158 文件、+721124 / -93143 行 |
| merge base | `1bb270a0`（**与前 14 轮完全相同** —— PR #11 是 squash 合并，抹掉了祖先关系） |
| 冲突文件 | 见下表（5 个） |
| 自动合并成功 | `module-progress.md`（台账求并 ⚠ 行级兜底 + 折叠 465 行）、`catalog/channel/trade/warehouse.openapi.json`、`contracts/{catalog,channel,trade,warehouse}.ts`（⚠ 驱动自报「走了行级兜底并集，请人工确认这个文件改对了」） |
| 合并 commit | 无（abort 后未落） |
| 预检 | **未跑**（无合并结果可检；协议第 4 步前提不成立） |
| push | **未 push**（合并未落地） |
| 集成头 HEAD | `eefb0c27` —— 前后未变（== PRE） |
| 台账（同步前后无变化） | 已交付 **297** / 进行中 **0** / 未开发 **168**（共 465，完成率 63.9%） |
| 闸 | 00:15:44 等闸 → **00:24:18 acquire**（owner pid 96610）→ 收尾 release |
| 活 tick | 00:08:02 持闸（pid 90539），并发跑 `mvnw verify`（反方向合流任务 worktree `main-merge-234421`） |

## 冲突文件清单（5 个 · 与上轮逐字相同）

| 冲突文件 | 冲突类型 | 冲突区 | ours blob（集成头） | theirs blob（main） | 独立复核结论 | 处置 |
| --- | --- | --- | --- | --- | --- | --- |
| `apps/web/src/lib/api-proxy.ts` | content（stage 1/2/3 齐全） | 7 处 | `7f844ce6` | `f6624aec` | 两侧 blob 均在集成头历史内可达；集成头侧为超集（services prefix 120→129 全包含） | **人工复核后倾向取集成分支侧**（见结论 5） |
| `apps/web/src/app/inventory/adjustments/page.tsx` | add/add（无 base） | 4 处 | `d528389e` | `ea84c378` | main 侧 blob 在集成头历史内可达 ⇒ 旧快照 | 取集成分支侧 |
| `backend/services/trade/.../PurchaseRequestModels.java` | add/add（无 base） | 1 处 | `02084026` | `d4978d24` | main 侧 blob 在集成头历史内可达 ⇒ 旧快照 | 取集成分支侧 |
| `backend/services/trade/.../PurchaseRequestService.java` | add/add（无 base） | 1 处 | `f113aae4` | `99d099cb` | main 侧 blob 在集成头历史内可达 ⇒ 旧快照 | 取集成分支侧 |
| `backend/services/warehouse/.../InventoryAdjustmentService.java` | add/add（无 base） | 1 处 | `3e8da6ab` | `1ff2de04` | main 侧 blob 在集成头历史内可达 ⇒ 旧快照 | 取集成分支侧 |

⚠️ 4 个 add/add 在 `git ls-files -u` 里**只有 stage 2/3、没有 stage 1** ⇒ merge base `1bb270a0` 里根本没有这些路径，
工具若标成 `content` 则是类型标签不可信、结论可信。

## 下一轮解法（给人工）

1. **5 个文件全部先验证「取集成分支侧」**：本轮独立复核显示 5 个 main 侧 blob 全部在集成头历史内可达
   （含 `api-proxy.ts`），集成头侧是更晚的演进版本。
2. **`api-proxy.ts` 仍须跑三件套判据**：集合包含 ✓（本轮已验：main 120 个 prefix 全在集成头 129 条内）
   / 有序子序列 / 零删除 —— 并跑 `apps/web/src/app/api/[...path]/route.test.ts` 的真 proxy 断言（含反向用例）。
   白名单错一条就是静默 404。
3. **另外 4 个文件取集成分支侧**，依据是「main 侧 blob 出现在集成头历史里」，
   不依赖 merge base ⇒ 与 squash 抹祖先的根因无关，稳定可复算。
4. **解完必须跑 `node tools/module-progress/sync.mjs` 重生成台账**，再判 `sync.mjs --check` 绿。
5. **4 个 OpenAPI JSON 走了行级兜底并集**（驱动自报「请人工确认这个文件改对了」）⇒ 逐行核对。
   **按字节差判断「有没有丢内容」会误判** —— 规范化哈希相同但字节不同（驱动重排了导出顺序）。
6. **根因仍在**：`d28188d6`（PR #11）是 squash 合并，merge base 永远停在 `1bb270a0` ⇒ 每轮重复同一组冲突。
   **收敛的正解仍是让反方向合流任务（`.worktrees/main-merge-234421`，分支 `chore/sync-module-dev`）
   以**真合并**（保留两个父提交）落进 main** —— merge base 一旦前进，本方向下一轮就可能干净。
   本轮观测：那个任务 23:48 起跑 `mvnw verify`，**截至 00:24 仍未落进 `origin/main`**（tip 仍是 `9efa2fee`）。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/lane.mjs` — `gate acquire` / `gate status` / `gate release` 语义
- `tools/module-progress/sync.mjs` — 台账重生成 / `--check` 自检
- `tools/module-progress/merge-union.mjs` — 合并驱动（契约/台账按键求并）
- `docs/product/capability-blueprint/module-progress.md` — 台账（本轮无变化，`--check` 绿）
- `docs/sessions/2026-10/2026-10-22-0007-main-sync-conflict-5-files-round14.md` — 上轮取证
- `docs/sessions/2026-10/2026-10-22-0001-main-sync-conflict-5-files.md` — 上上轮取证

## 关键 trade-off

- **等 9 分钟的闸 vs 强行并入**：宁可等。持闸 tick 在同机跑 L2，
  并发 merge/build 会污染它的 `backend/**/target/`、PG 容器与后续 typecheck，并破坏调度互斥。
- **只报冲突、不解冲突**：协议与任务 prompt 双重硬规定；`merge-resolve.mjs` 的授权出处是**反方向**任务，
  本方向无授权通道 ⇒ 工具结论只作取证。
- **修正上轮结论 vs 保持一致**：本轮在**不采信上轮结论**的前提下独立复核，发现上轮对 `api-proxy.ts`
  的「必须人工并两层 alternation」建议与本轮证据不符（集成头侧是超集）。
  仍按协议**只报不改**：结论作为取证交给人工复核，避免把「大概率取 ours」写成既成事实。
- **会话日志落在集成头工作区、以纯文档本地提交落盘、不 push**：
  协议禁止在 main 上提交任何东西；留成未跟踪文件会弄脏工作区、堵死下一轮的 merge 前置条件
  （`git status --porcelain` 必须为空）。⇒ 与上轮同构。
