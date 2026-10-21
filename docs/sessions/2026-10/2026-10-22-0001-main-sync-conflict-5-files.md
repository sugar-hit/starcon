# 2026-10-22 00:01 集成头同步 main（冲突未并入 · 冲突面 5 文件 · api-proxy.ts 仍需人工求并）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 同步。
本轮**结果：冲突未并入** —— 合并在 5 个文件上真冲突，按协议 `git merge --abort` 后如实上报，
**未自行解冲突、未 push**。

这是同一同步任务的**连续第 13+ 轮**（main 上 `92066867` 已归档前 12 轮取证）。本轮与上轮
（`2026-10-21-1615-main-sync-conflict-4-files.md`，4 文件）相比：**冲突面从 4 扩到 5**，
`origin/main` tip 从 `478778ef` 推进到 `9efa2fee`，待并入提交从 8 颗增到 20 颗。

## 决策 / 结论

1. **冲突只报不解**（`SYNC-MAIN.md` §0 硬规定 + 任务 prompt 逐字要求）。
   `git merge --abort` 退出码 0，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `1c6dba79`，**未 push**。

2. **闸：等了 1 小时 40 分钟才拿到，且必须靠密集竞态**。
   22:15 起 `gate status` 显示 `held / stale=false`，持闸者是**活着的**流水线 tick
   （owner pid 90371，自 22:00:21 连续持闸）。取证：
   - 该 tick 在同一 worktree 跑 `./mvnw verify`（L2），22:06 起、22:48 结束；
   - 闸目录里有 `keepalive-<HHMMSS>` 标记文件（22:00/22:05/22:06/22:10/22:42/22:58/23:13/23:34），
     **每 5~20 分钟 touch 一次闸目录刷新 mtime** ⇒ `ageSeconds` 回落**不是**交接窗口，是心跳；
   - `owner` 文件自 22:00:21 起从未变过 ⇒ **整轮闸从未被释放**。
   ⇒ 低频 `gate status` 观测完全无效（`ageSeconds` 一直在 280~1205 之间跳）。
   本轮改用**进程内 20ms 间隔的密集 `mkdirSync` 竞态**（`/tmp/starcon-gate-race.mjs`，
   语义逐条对齐 `lane.mjs` 的 `gateAcquire`），前 1900s 跑 169,000 次、**零副作用**、
   `freeWindowsSeen=0`；重启后 **731s（34,351 次尝试）抢到闸**。
   ⚠️ 交接窗口确实窄到 1s 间隔抓不住（第一版竞态器 1900s 全军覆没）。

3. **本轮只做只读取证，未跑任何构建**：等闸期间不 fetch、不 merge、不编译 ——
   与持闸 tick 的 L2 共用 `backend/**/target/` 与 PG 容器，并发必假红。

4. **本轮独立复核（不采信工具结论）后与工具一致**：5 个冲突文件里 4 个的 **main 侧 blob 能在
   集成分支自己的历史里逐字节找到** ⇒ main 侧不是新交付，是集成分支工作的旧快照 ⇒ 两侧线性演进。
   ⚠️ `merge-resolve.mjs` 的授权出处是**反方向**任务 `MAIN_MERGE_CRON_PROMPT.md` §4.2/§4.3
   （main ← 集成头，走 PR 流程），**不是本方向的授权通道** ⇒ 本轮**只把工具结论当取证，不据此解冲突**。

5. **`api-proxy.ts` 与其余 4 个文件性质不同，不能一起处理**：
   `merge-resolve.mjs` 对它的累积型三件套**全部不成立**
   （集合包含 ✗ 缺 1 条 · 有序子序列 ✗ 乱序 109 处 · 零删除 ✗ 少 1 条），
   判定 `并入（人工）`；其余 4 个判 `取集成分支侧`。
   `needHuman: 1` ⇒ **只有 `api-proxy.ts` 是真需要人工求并的**。

6. **台账合并后必须重生成**：合并驱动按模块 ID 求并集（折叠 465 行同 ID 记录），
   但总览/分阶段/分系统三张**汇总表**不是模块 ID 行，走整行去重兜底 ⇒
   main 侧的旧计数会被整行留下并与模块行矛盾，`sync.mjs --check` 必红。
   本轮 abort 了，不涉及；但**下一轮解完冲突后必须跑一次 `sync.mjs` 重生成再判绿**。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | **冲突未并入**（5 个文件） |
| `origin/main` HEAD | `9efa2fee` Merge pull request #23（progress-site 工具线合流） |
| 待并入 main 提交数 | **20**（`d28188d6` … `9efa2fee`） |
| 差异规模 | 1158 文件、+721124 / -93143 行 |
| merge base | `1bb270a0`（与前 12 轮完全相同 —— PR #11 是 squash 合并，抹掉了祖先关系） |
| 冲突文件 | 见下表（5 个） |
| 自动合并成功 | `module-progress.md`（台账求并 ⚠ 行级兜底）、`catalog/channel/trade/warehouse.openapi.json`、`contracts/{catalog,channel,trade,warehouse}.ts` |
| 合并 commit | 无（abort 后未落） |
| 预检 | 未跑（无合并结果可检） |
| push | 未 push（合并未落地） |
| 集成头 HEAD | `1c6dba79` docs(batch-55) —— 前后未变 |
| 台账（同步前后无变化） | 已交付 **297** / 进行中 0 / 未开发 **168**（共 465，完成率 63.9%） |
| 闸 | 22:15 等闸 → 00:01:37 acquire（owner pid 61580）→ 00:0x release |
| 活 tick | 22:00 tick 持闸 1h48m+，22:06–22:48 跑 L2 `mvnw verify`，23:13 更新 `lanes.json` |

## 冲突文件清单（5 个 · 按工具判定分组）

| 冲突文件 | 冲突类型 | 工具判定 | 依据 |
| --- | --- | --- | --- |
| `apps/web/src/lib/api-proxy.ts` | content（7 处冲突区） | **并入（人工）** | 累积三件套全不成立：缺 1 条 / 乱序 109 处 / 少 1 条；main 侧白名单 116 条 vs 集成头 125 条 |
| `apps/web/src/app/inventory/adjustments/page.tsx` | add/add（4 处） | 取集成分支侧 | main 侧 blob 是集成头历史里的祖先快照 |
| `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestModels.java` | add/add（1 处） | 取集成分支侧 | 同上 |
| `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestService.java` | add/add（1 处） | 取集成分支侧 | 同上 |
| `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentService.java` | add/add（1 处） | 取集成分支侧 | 同上 |

⚠️ 工具把 4 个 add/add 都标成 `content`（merge base `1bb270a0` 里没有这些路径，
`git cat-file -e` 可证）—— **类型标签不可信，结论可信**。

## 下一轮解法（给人工）

1. **`api-proxy.ts` 必须手工求并**（MAIN_MERGE_CRON_PROMPT §4.2）：
   两层 alternation 都要并（不是取一边），并跑 `apps/web/src/app/api/[...path]/route.test.ts`
   的真 proxy 断言（含反向用例）—— 白名单错一条就是静默 404。
   判据要落到「集合包含 ✓ / 有序子序列 ✓ / 零删除 ✓」三件套全成立。
2. **另外 4 个文件取集成分支侧**，依据是「main 侧 blob 出现在集成头历史里」，
   不依赖 merge base ⇒ 与 squash 抹祖先的根因无关，稳定可复算。
3. **解完必须跑 `node tools/module-progress/sync.mjs` 重生成台账**，再判 `sync.mjs --check` 绿。
4. **4 个 OpenAPI JSON 走了行级兜底并集**（驱动自报「走了行级兜底并集，请人工确认这个文件改对了」）
   ⇒ 逐行核对，不能默认它对。**按字节差判断「有没有丢内容」会误判** ——
   规范化哈希相同但字节不同（驱动重排了导出顺序）。
5. **根因仍在**：`d28188d6`（PR #11）是 squash 合并，merge base 永远停在 `1bb270a0`。
   要让冲突面真正收敛，得让 main ← 集成头 的合流走**真合并**（保留两个父提交），
   否则每一轮都会重复同一组冲突。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/lane.mjs` — `gate acquire` / `gate status` / `gate release` 语义
- `tools/module-progress/merge-resolve.mjs` — **只读**判定工具（本轮只取证，未据以解冲突）
- `docs/product/capability-blueprint/module-progress.md` — 台账（同步前后无变化）
- `docs/sessions/2026-10/2026-10-21-1615-main-sync-conflict-4-files.md` — 上轮取证（本轮并入的 main 提交里）
- `docs/sessions/2026-10/2026-10-21-1830-main-merge-purchase-edit-sample-delivery.md` — 反方向合流取证

## 关键 trade-off

- **等 1h40m 的闸 vs 强行并入**：宁可等。持闸 tick 正在同一 worktree 跑 L2，
  并发 merge/build 会污染它的 `backend/**/target/`、PG 容器与后续 typecheck，
  且会破坏调度互斥（本任务 §0 硬规定）。代价是本轮同步窗口被推迟。
- **只用工具取证、不据以解冲突**：工具的授权出处是反方向任务，本方向无授权通道。
  宁可下一轮再来，也不越界自动解冲突。
- **会话日志落在集成头工作区而不是 main**：协议禁止在 main 上提交任何东西；
  留成未跟踪文件又会弄脏工作区、堵死下一轮的 merge 前置条件
  （`git status --porcelain` 必须为空）⇒ 本轮以**纯文档本地提交**落盘，不 push。
