# 2026-10-21: 集成头同步 main（第 12 轮 · 冲突面扩到 4 文件 · 与另一同步会话撞车）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 同步。
本轮**结果：冲突未并入** —— 合并在 4 个文件上真冲突，按协议 `git merge --abort` 后如实上报，
**未自行解冲突、未 push**。

与第 11 轮（14:26，`a10ec0ec`）相比有两个**实质变化**：

1. **冲突面从 1 个文件扩到 4 个**：除 `apps/web/src/lib/api-proxy.ts` 外，新增 3 个 **add/add** 冲突
   （`trade` 服务的 3 个 Port 接口）。
2. **待并入提交从 5 颗增到 8 颗**：`origin/main` tip 从 `a257a922` 推进到 `478778ef`。

> ⚠️ **本轮出现调度层并发撞车**：17:16:31 本轮 abort 收尾、17:16:4x 放闸后，
> **另一个同步会话**在 **17:16:48** 取到闸，并于 **17:18:06** 在**同一 worktree** 里重新发起
> 同一笔 merge（`MERGE_HEAD` = `478778ef`，且 `MERGE_MSG` 与本轮正文**逐字节相同**
> —— 它复用了本轮留在 git dir 里的合并信息）。本轮放闸后**未再碰过工作区**，
> 也**没有再放闸**（当前持闸者是别的活会话，`stale=false`，放闸即等于清闸）。
> merge base 仍是 `1bb270a0` —— 与前 11 轮完全相同，**squash 抹祖先的根因依旧未变**。

## 决策 / 结论

1. **冲突只报不解**（`SYNC-MAIN.md` §0 硬规定 + 本任务 prompt 逐字要求）。
   本轮 `git merge --abort` 退出码 0，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `5f6d41af`，**未 push**。

2. **闸：等闸约 40 分钟才拿到**（16:15 起竞态 → 17:16 前后 acquire 成功）。
   期间持闸 tick 在跑 **L2**（`l2-run-batch52.log` → 失败于 `:catalog`），
   随后 **17:30:33 起重跑**（`l2-run-batch52-rerun.log` → **全绿**）。
   - 首次 L2 失败 = `TitleLibraryIntegrationTest` 静态初始化异常导致 **13 个 error**
     （`NoClassDefFound Could not initialize class` / `ExceptionInInitializer` at `<clinit>:121`）；
     重跑同批 ⇒ **绿**，判定为**并发/资源抖动型偶发**，非代码缺陷（供流水线参考）。
   - 等闸期间**未跑任何构建、未 fetch、未 merge**，只做只读取证（`gate status` / `cat owner` / `ps` /
     `git log` / `git merge-base`）—— 与持闸 tick 的 L2 共用 `backend/**/target/` 与 PG 容器，并发必假红。
   - 取闸靠**密集竞态**（1 秒间隔）；期间实测闸 age 被 keepalive 重置过 3 次
     （`keepalive-161659` / `163033` / `164533`），**交接窗口窄到必须竞态**，低频 `status` 观测会整段错过。

3. **本轮独立复核（不采信工具结论）后与工具一致**：4 个冲突文件里，**`origin/main` 侧独有行数全部为 0**
   （即集成头侧是严格超集）：

   | 冲突文件 | ours 独有行 | theirs 独有行 |
   | --- | --- | --- |
   | `apps/web/src/lib/api-proxy.ts` | 118 | **0** |
   | `backend/.../trade/ReplenishmentCatalogPort.java` | 231 | **0** |
   | `backend/.../trade/StockoutInventoryPort.java` | 26 | **0** |
   | `backend/.../trade/SupplierPartyQueryPort.java` | 42 | **0** |

   ✅ 仓库自带判定工具 `merge-resolve.mjs`（**只读**，已核验无写操作）本轮退出码 0，
   `needHuman: 0`，4 个文件全部判为「**取集成分支侧**」，理由一致：
   *main 侧 blob 出现在集成分支该路径的历史里 ⇒ main 是集成分支的**祖先快照** ⇒ 两侧线性演进，取新侧不丢内容*；
   `api-proxy.ts` 额外通过**累积型三件套**（集合包含 ✓ / 有序子序列 ✓ / 零删除 ✓）。
   ⚠️ 该工具的授权出处是**反方向**任务 `MAIN_MERGE_CRON_PROMPT.md` §4.2/§4.3（main ← 集成头，走 PR 流程），
   **不是本方向的授权通道** ⇒ 本轮**只把工具结论当取证，不据此解冲突**。

4. **模块级重算（独立于工具）**：台账两边各 465 行，按模块 ID + 状态 join：

   | 口径 | 集成头 `5f6d41af` | `origin/main` `478778ef` |
   | --- | --- | --- |
   | 已交付 | **282** | 280 |
   | 进行中 | 0 | 0 |
   | 未开发 | 183 | 185 |
   | main 已交付但集成头未交付 | — | **0** |

   ⇒ **main 在任何模块上都不领先集成头**；集成头反而多出 2 个已交付模块
   （`6.3.1.2` 采购单创建与编辑、`6.6.1.2` 打样与样品管理），
   均为**今天 16:02 流水线自己交付**的 ⇒ 本轮 8 颗待并入提交**内容上集成头已全部覆盖**，
   并入对台账**无实质增量**，只解决血缘分叉。

5. **新增冲突的来源**：3 个 Java Port 接口是 **add/add** ——
   `main` 侧由 `ec225641`（6.2.1.x 缺货/补货/供应商资质）引入，
   集成头侧由**今天 16:02 交付的 `6.3.1.2` 采购单创建与编辑**引入（同一批文件、同一服务、不同车道各自新增）。
   ⇒ **冲突面会随流水线自身交付而扩大**，这是本轮相对前 11 轮的新事实。

6. **`merge-union` 驱动按预期工作**（只覆盖契约/台账两类文件）：
   台账 `module-progress.md` 按模块 ID 求并集折叠 465 行同 ID 记录（无冲突）；
   `packages/api-contracts/**` 8 个文件全部干净合并；1 个文件走「行级兜底并集」并打印人工确认提示。

7. **协议一致性核对**：`git diff integration/module-dev origin/main -- tools/module-progress/SYNC-MAIN.md tools/module-progress/PIPELINE.md`
   为**空** ⇒ main 侧没有偷偷改过本任务的协议，「冲突只报不解 / 下一轮再试」仍是当前有效规则。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | **冲突未并入**（4 个文件：`api-proxy.ts` 1 个 content + `trade` 3 个 add/add） |
| `origin/main` HEAD | `478778ef` feat: 需求合并与批量、采购单列表等 4 项能力交付 |
| 待并入 main 提交数 | **8**（`d28188d6` / `ec225641` / `a9f3b4d0` / `3bedc528` / `a257a922` / `73791049` / `a83380c6` / `478778ef`） |
| merge base | `1bb270a0`（与前 11 轮相同 ⇒ squash 抹祖先的根因未变） |
| 差异规模 | 1028 个文件、+652281 / −93320 行 |
| 冲突文件 | `apps/web/src/lib/api-proxy.ts` · `trade/ReplenishmentCatalogPort.java` · `trade/StockoutInventoryPort.java` · `trade/SupplierPartyQueryPort.java` |
| 自动合并成功 | `module-progress.md`（台账求并 465 行）、`packages/api-contracts/**` 8 个文件、6 篇 docs、2 个 backlog |
| 合并 commit | **无**（abort 后未落；已拟好的 8 提交清单正文随 abort 丢弃） |
| 预检 | **未跑**（无合并结果可检） |
| push | **未 push**（合并未落地） |
| 集成头 HEAD | `5f6d41af`（本地 == `origin/integration/module-dev`，未推提交 0） |
| 台账（同步前后无变化） | 已交付 **282** / 进行中 **0** / 未开发 **183**（共 465，完成率 60.6%） |
| 闸 | `held`(stale=false) → 密集竞态 **约 40 分钟** → acquire → 本轮收尾 release（`free`）→ **17:16:48 被另一同步会话取走** |
| 并发同步会话 | 本轮放闸后**另一同步会话**立即取闸并重发同一笔 merge（`MERGE_MSG` 与本轮逐字节相同） |

## 下一轮解法（给人工 / 并入任务）

1. **4 个冲突文件都可以按「取集成头侧」收**（本轮已独立复核：theirs 独有行 = 0，
   工具 `needHuman: 0` 同结论）。
   在本方向（base=main, head=集成头）对应命令是 `git checkout --ours <file>` + `git add`，
   ⚠️ 与反方向任务（base=集成头, head=main）的 `--theirs` **恰好相反，别照抄**。
   解完必须跑 `apps/web/src/app/api/[...path]/route.test.ts` 的真 proxy 断言（含反向用例）——
   **白名单错一条就是静默 404**，且 typecheck / lint / vitest / Playwright 会**全部全绿**骗过去。

2. **根治项仍是发布侧**：让 main 侧改推**真实提交**而非 GitHub squash。
   merge base 能自然前移，本冲突随之消失（连续 11 轮记录）。

3. **协议授权缺口（需人工决策）**：把 `merge-resolve.mjs` 正式接进本方向协议，两条路任选其一 ——
   (a) 在 `SYNC-MAIN.md` §0 增加一条：判定工具退出码 0 且结论为「取集成分支侧 + 累积三件套成立」时
   **允许机械解冲突**（并强制跑 api-proxy 全量 allowlist 回归）；
   (b) 维持「只报不解」，但把该工具的结论固定写进每轮汇报，作为人工决策的证据包。
   ⚠️ 两条路都有前提：解冲突前必须**独立复核三件套**，不能只信工具退出码。

4. **调度层缺单例约束（仍未根治，本轮再次实证）**：本任务与流水线共享闸**只保证互斥、不保证串行**，
   同一 worktree 上可以出现**两个同步会话先后各发起一次 merge**，
   前一个会话的 `git merge --abort` 有可能**误杀后一个会话正在进行的 merge**。
   ⇒ 建议给「同步任务」加**调度层单例约束**（同一任务同一时刻只允许一个实例），
   并把「同步会话之间也互斥」写进 `SYNC-MAIN.md` §0。

5. **同步任务不应把 backlog / 会话文档提交进集成头**（11:45 轮已提，本轮沿用）：
   本轮会话文档写在**主工作区**（未入库），不进集成头 ⇒ 避免自造下一个 add/add 冲突文件。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/lane.mjs` — `gate status` / `acquire` / `release` 语义
- `tools/module-progress/sync.mjs` — 台账同步器
- `tools/module-progress/merge-resolve.mjs` — 判定工具（只读，退出码 0，`needHuman: 0`，仅作取证）
- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` — 反方向任务的协议（工具授权出处，**非本方向**）
- `apps/web/src/lib/api-proxy.ts` — 冲突文件（累积型 `services[]` 白名单）
- `backend/services/trade/src/main/java/com/starcon/trade/{ReplenishmentCatalogPort,StockoutInventoryPort,SupplierPartyQueryPort}.java`
  — 冲突文件（add/add，同服务不同车道各自新增）
- `docs/product/capability-blueprint/module-progress.md` — 台账（282 / 0 / 183）
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` — 本问题的 backlog 记录（集成头侧，**已存在**）
- `.dev-agent/l2-run-batch52.log` / `.dev-agent/l2-run-batch52-rerun.log` — 流水线 L2 首跑红 / 重跑绿
- 上一轮同题：`docs/sessions/2026-10/2026-10-21-1426-main-sync-conflict-api-proxy-only.md`（主工作区）
- 本轮文档位置：`docs/sessions/2026-10/2026-10-21-1615-main-sync-conflict-4-files.md`（主工作区，未入库）

## 关键 trade-off

- **为什么有机械解法仍然 abort**：`merge-resolve.mjs` 的结论**技术上是对的**
  （本轮已独立复核：4 个文件 theirs 独有行全为 0），但**协议授权**与**技术可行**是两件事。
  本任务 prompt 逐字要求「**冲突不自行解决**」⇒ 按协议 abort，把工具结论作为证据留给人工决策，
  **没有让「语法上能过、语义上悄悄删路由」的白名单带着未审查的改动进主干**。
- **为什么不改协议来放行**：`SYNC-MAIN.md` 是**版本控制里的唯一协议**，改它属于架构决策，
  不该由一个 cron 轮次顺手改。**根治点见「下一轮解法」第 2、3、4 条。**
