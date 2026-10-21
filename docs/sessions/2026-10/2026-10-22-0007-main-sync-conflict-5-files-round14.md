# 2026-10-22 00:07 集成头同步 main（第 14 轮 · 冲突未并入 · 冲突面仍是同样 5 文件）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 同步。
本轮**结果：冲突未并入** —— 合并在同样 5 个文件上真冲突，按协议 `git merge --abort` 后如实上报，
**未自行解冲突、未 push、未跑预检**（无合并结果可检）。

这是同一同步任务的**连续第 14 轮**。与上轮（`2026-10-22-0001-main-sync-conflict-5-files.md`，
5 文件）相比：**冲突面一字未变**，`origin/main` tip 仍停在 `9efa2fee`（本轮 `git fetch` 后无新提交），
merge base 仍是 `1bb270a0`，待并入提交仍是 20 颗 —— **main 侧自上轮起没有前进，冲突面自然原地不动**。

## 决策 / 结论

1. **冲突只报不解**（`SYNC-MAIN.md` §0 硬规定 + 任务 prompt 逐字要求）。
   `git merge --abort` 退出码 0，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `7b034555`（== 本轮 PRE），**未 push**。

2. **闸：等约 49 分钟才拿到，两段式密集竞态。**
   - 23:15:44 起 `gate status` → `held / stale=false`（age 109s，TTL 4200s）⇒ 持闸者是**活着的**流水线 tick。
   - 取证：闸目录 `owner` 文件自 **22:00:21** 起未变（pid 90371）；L2 `mvnw verify` 在**同一 worktree** 跑
     （23:10 起 → catalog → channel → trade → warehouse/finance 逐服务推进，23:48 前后收敛）。
   - ⚠️ **`ageSeconds` 回落不是交接窗口，是心跳**：闸目录里的 `keepalive-<HHMMSS>` 文件
     （22:00/22:05/22:06/22:10/22:42/22:58/23:13/23:34/23:48…）**每 5~20 分钟 touch 一次闸目录**，
     刷新目录 mtime ⇒ `ageSeconds` 一直在 109~590 之间跳。`owner` 文件不变才说明**整轮闸从未被释放**。
   - 第一段 1s 间隔竞态 1500s（25 分钟）**全军覆没**；第二段 1223s（20 分钟）后抢到闸
     （owner pid 89314，`2026-10-21T16:05:18.621Z` = 00:05:18 +0800）。
     ⇒ 交接窗口确实窄到 1s 间隔也抓不住，靠的是运气密度。**建议后续把竞态间隔压到 20ms 级别**（上轮已实证可行）。
   - 拿到闸时**集成头 worktree 内已无构建进程**；同时段在跑的两个 `mvnw verify` 都在**别的 worktree**
     （`/tmp/wt_path.txt` → `.worktrees/main-merge-234421`，分支 `chore/sync-module-dev`）——
     即**反方向合流任务**（main ← 集成头）。它不与本轮抢 `backend/**/target/`，但会抢 Testcontainers PG 容器。

3. **本轮只做只读取证，未跑任何构建**：等闸期间不 fetch、不 merge、不编译 ——
   与持闸 tick 的 L2 共用 PG 容器与 target 目录，并发必假红。

4. **独立复核（不采信上轮结论）后与上轮一致**：4 个 add/add 冲突文件的 **main 侧 blob 在集成分支自己的历史里
   逐字节可达**（本轮用 `git rev-list integration/module-dev --objects` 重新验证）⇒
   main 侧不是新交付，是集成分支工作的**旧快照** ⇒ 两侧线性演进，取集成分支侧成立且与 squash 根因无关。

5. **`api-proxy.ts` 与其余 4 个文件性质不同，不能一起处理**：
   本轮两侧 blob 为 `f6624aec`(main) vs `7f844ce6`(集成头) ⇒ **真正分叉**，7 处冲突区，
   两层 alternation 都要并，不是取一边。

6. **台账本轮无变化，但已复核自检绿**：`node tools/module-progress/sync.mjs --check` → `✓ 台账与蓝图一致`。
   合并驱动虽按模块 ID 求并集（折叠 465 行同 ID 记录），但总览/分阶段/分系统三张**汇总表**走整行去重兜底 ⇒
   下一轮**解完冲突后必须跑一次 `sync.mjs` 重生成再判绿**，不能直接沿用合并驱动写出的汇总表。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | **冲突未并入**（5 个文件，与上轮完全相同） |
| `origin/main` HEAD | `9efa2fee` Merge PR #23（**fetch 后无新提交**，与上轮同一 tip） |
| 待并入 main 提交数 | **20**（`d28188d6` … `9efa2fee`） |
| 差异规模 | 1158 文件、+721124 / -93143 行（与上轮逐字相同） |
| merge base | `1bb270a0`（**与前 13 轮完全相同** —— PR #11 是 squash 合并，抹掉了祖先关系） |
| 冲突文件 | 见下表（5 个） |
| 自动合并成功 | `module-progress.md`（台账求并 ⚠ 行级兜底 + 折叠 465 行）、`catalog/channel/trade/warehouse.openapi.json`、`contracts/{catalog,channel,trade,warehouse}.ts`（⚠ 驱动自报「走了行级兜底并集，请人工确认这个文件改对了」） |
| 合并 commit | 无（abort 后未落） |
| 预检 | **未跑**（无合并结果可检；协议第 4 步前提不成立） |
| push | **未 push**（合并未落地；本轮会话日志以纯文档本地提交落盘，同样不 push） |
| 集成头 HEAD | `7b034555` —— 前后未变（== PRE） |
| 台账（同步前后无变化） | 已交付 **297** / 进行中 **0** / 未开发 **168**（共 465，完成率 63.9%） |
| 闸 | 23:15 等闸 → **00:05:18 acquire**（owner pid 89314）→ 收尾 release |
| 活 tick | 22:00 tick 持闸 2h05m+，22:06–23:48 跑 L2 `mvnw verify` |

## 冲突文件清单（5 个 · 与上轮逐字相同）

| 冲突文件 | 冲突类型 | 冲突区 | 独立复核结论 | 处置 |
| --- | --- | --- | --- | --- |
| `apps/web/src/lib/api-proxy.ts` | content（stage 1/2/3 齐全） | 7 处 | 两侧 blob 真分叉（`f6624aec` vs `7f844ce6`） | **人工求并**（两层 alternation 都并） |
| `apps/web/src/app/inventory/adjustments/page.tsx` | add/add（无 base） | 4 处 | main 侧 blob `ea84c378` 在集成头历史内可达 ⇒ 旧快照 | 取集成分支侧 |
| `backend/services/trade/.../PurchaseRequestModels.java` | add/add（无 base） | 1 处 | main 侧 blob `d4978d24` 在集成头历史内可达 ⇒ 旧快照 | 取集成分支侧 |
| `backend/services/trade/.../PurchaseRequestService.java` | add/add（无 base） | 1 处 | main 侧 blob `99d099cb` 在集成头历史内可达 ⇒ 旧快照 | 取集成分支侧 |
| `backend/services/warehouse/.../InventoryAdjustmentService.java` | add/add（无 base） | 1 处 | main 侧 blob `1ff2de04` 在集成头历史内可达 ⇒ 旧快照 | 取集成分支侧 |

⚠️ 4 个 add/add 在 `git ls-files -u` 里**只有 stage 2/3、没有 stage 1** ⇒ merge base `1bb270a0` 里根本没有这些路径，
工具若标成 `content` 则是类型标签不可信、结论可信。

## 下一轮解法（给人工）

1. **`api-proxy.ts` 必须手工求并**（MAIN_MERGE_CRON_PROMPT §4.2）：
   两层 alternation 都要并（不是取一边），并跑 `apps/web/src/app/api/[...path]/route.test.ts`
   的真 proxy 断言（含反向用例）—— 白名单错一条就是静默 404。
   判据要落到「集合包含 ✓ / 有序子序列 ✓ / 零删除 ✓」三件套全成立。
2. **另外 4 个文件取集成分支侧**，依据是「main 侧 blob 出现在集成头历史里」，
   不依赖 merge base ⇒ 与 squash 抹祖先的根因无关，稳定可复算。
3. **解完必须跑 `node tools/module-progress/sync.mjs` 重生成台账**，再判 `sync.mjs --check` 绿。
4. **4 个 OpenAPI JSON 走了行级兜底并集**（驱动自报「请人工确认这个文件改对了」）⇒ 逐行核对。
   **按字节差判断「有没有丢内容」会误判** —— 规范化哈希相同但字节不同（驱动重排了导出顺序）。
5. **根因仍在**：`d28188d6`（PR #11）是 squash 合并，merge base 永远停在 `1bb270a0` ⇒ 每轮重复同一组冲突。
   **本轮观察到反方向合流任务（`.worktrees/main-merge-234421`，分支 `chore/sync-module-dev`）正在跑
   `mvnw verify`** —— 若它以**真合并**（保留两个父提交）落进 main，merge base 就会前进，
   本方向下一轮就可能干净。**这是收敛冲突面的正解，优先于在同步任务里手工解冲突。**

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/lane.mjs` — `gate acquire` / `gate status` / `gate release` 语义（`ageSeconds` 取自闸目录 mtime）
- `tools/module-progress/sync.mjs` — 台账重生成 / `--check` 自检
- `tools/module-progress/merge-union.mjs` — 合并驱动（契约/台账按键求并）
- `docs/product/capability-blueprint/module-progress.md` — 台账（本轮无变化，`--check` 绿）
- `docs/sessions/2026-10/2026-10-22-0001-main-sync-conflict-5-files.md` — 上轮取证
- `docs/sessions/2026-10/2026-10-21-1615-main-sync-conflict-4-files.md` — 上上轮取证（4 文件）

## 关键 trade-off

- **等 49 分钟的闸 vs 强行并入**：宁可等。持闸 tick 正在同一 worktree 跑 L2，
  并发 merge/build 会污染它的 `backend/**/target/`、PG 容器与后续 typecheck，并破坏调度互斥。
- **只报冲突、不解冲突**：协议与任务 prompt 双重硬规定；`merge-resolve.mjs` 的授权出处是**反方向**任务，
  本方向无授权通道 ⇒ 工具结论只作取证。
- **会话日志落在集成头工作区、以纯文档本地提交落盘、不 push**：
  协议禁止在 main 上提交任何东西；留成未跟踪文件会弄脏工作区、堵死下一轮的 merge 前置条件
  （`git status --porcelain` 必须为空）。⇒ 与上轮同构。
