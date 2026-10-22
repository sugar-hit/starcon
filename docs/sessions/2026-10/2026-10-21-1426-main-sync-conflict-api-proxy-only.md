# 2026-10-21: 集成头同步 main（第 11 轮 · 冲突面仍 1 文件，main 仍无任何领先）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 的同步。
本轮**结果：冲突未并入** —— 合并在 `apps/web/src/lib/api-proxy.ts` 上真冲突，按协议
`git merge --abort` 后如实上报，**未自行解冲突、未 push**。

与第 10 轮（14:23，`a10ec0ec`）相比**冲突面、待并入集合、merge base 全部不变**：
待并入仍是同样 5 颗提交（`d28188d6` / `ec225641` / `a9f3b4d0` / `3bedc528` / `a257a922`），
`origin/main` tip 仍是 `a257a922`，merge base 仍钉在 `1bb270a0` ⇒ **本轮是一次完全同题的复现**。

> ⚠️ **第 10 轮的并发竞态在本轮再次出现**：本轮 14:15 起跑时闸被**另一个同步会话**持着
> （owner `39097`），本轮密集竞态在 **400 秒**后（14:17→14:23:45）才拿到闸。
> 第 10 轮记录的那条 P0（调度层无单例约束）**至今未根治**。

## 决策 / 结论

1. **冲突只报不解**（协议 `SYNC-MAIN.md` §0 硬规定 + 本任务 prompt 逐字要求）。
   本轮 `git merge --abort` 退出码 0，`MERGE_HEAD` 已清，工作区干净，
   HEAD 停在 `a10ec0ec`，**未 push**。

2. **闸：等闸 400 秒后拿到**（14:17 起竞态 → 14:23:45 acquire 成功），比第 10 轮的 62 分钟短。
   期间观测到 **1 次「零宽度交接」**（闸 ageSeconds 被重置：213 → 4，持闸 tick 始终 `stale: false`）。
   取闸靠**密集竞态**（1 秒间隔）；**等闸期间未跑任何构建、未 fetch、未 merge**，
   只做只读取证（`gate status` / `cat owner` / `ps` / `git log`）——
   期间实测同 worktree 内有 `./mvnw compile` 与 `-Dtest=OpenApiContractTest` 在跑，
   并发构建会与持闸 tick 的 L2 共用 `backend/**/target/` 与 PG 容器 ⇒ 必假红。

3. **本轮独立复核（不采信工具结论）后与工具一致**：
   `apps/web/src/lib/api-proxy.ts` 的 `pattern:` 条目数 —— base `1bb270a0` **46** 条、
   ours（集成头 `a10ec0ec`）**107** 条、theirs（`origin/main` `a257a922`）**105** 条：

   | 校验项 | 结果 |
   | --- | --- |
   | ① 集合包含：theirs 每条都出现在 ours | ✓ **缺 0** |
   | ② 有序子序列：theirs 保持相对顺序 | ✓ **匹配 105/105** |
   | ③ 零删除：base 的 46 条全部保留 | ✓ **丢 0** |
   | ours 独有（theirs 没有） | **2 条**：`brand-credentials`（16.4.2.2 授权凭证）、`pending-inspections`（8.2.2.2 待检处理） |

   ⇒ **取集成头侧在结果上等同于完整求并**，冲突纯属**数组锚点撞车**（两侧各自往同一个
   `services[]` 数组尾部追加新元素），不是内容分歧。
   ✅ 仓库自带判定工具 `merge-resolve.mjs`（**只读**，已核验无写操作）本轮退出码 0，
   结论同样是「[取集成分支侧] · 累积型三件套 ✓ · 条目 105 → 107」。
   ⚠️ 但该工具的用法写在**反方向**任务 `MAIN_MERGE_CRON_PROMPT.md` §4.2/§4.3
   （main ← 集成头，走 PR 流程）—— **不是本方向的授权通道**。
   ⇒ 本轮**只把工具结论当取证，不据此解冲突**。

4. **模块级重算（独立于工具）**：台账两边各 465 行，按模块 ID + 状态 join：

   | 口径 | 集成头 `a10ec0ec` | `origin/main` `a257a922` |
   | --- | --- | --- |
   | 已交付 | **276** | 274 |
   | 进行中 | 0 | 0 |
   | 未开发 | 189 | 191 |
   | main 已交付但集成头未交付 | — | **0** |

   ⇒ **main 在任何模块上都不领先集成头**；集成头反而多出 2 个已交付模块
   （`8.2.2.2` 待检处理、`16.4.2.2` 授权凭证）。
   ⇒ 本轮要并入的 5 颗提交**内容上集成头全部已覆盖** ⇒ 并入对台账**无实质增量**。

5. **根因未变**：merge base 仍停在 **`1bb270a0`**（与 11:45 / 12:45 / 14:23 三轮**完全相同**）。
   本轮逐颗核实 5 颗待并入提交**全部单父**（`git log --merges` 在待并入集合内**无任何真实 merge**）：

   | 提交 | 父提交 | 性质 |
   | --- | --- | --- |
   | `a257a922` | `3bedc528` | 单父（squash 产物） |
   | `3bedc528` | `a9f3b4d0` | 单父 |
   | `a9f3b4d0` | `ec225641` | 单父 |
   | `ec225641` | `d28188d6` | 单父 |
   | `d28188d6` | `1bb270a0` | 单父（squash 产物，血缘链起点） |

   ⇒ **main 上 5 颗全是 GitHub squash 产物，祖先关系被抹掉**，
   所以「main → 集成头」永远从 `1bb270a0` 重新分叉，撞同一个数组锚点。

6. **协议一致性核对**：`git diff integration/module-dev origin/main -- tools/module-progress/SYNC-MAIN.md tools/module-progress/PIPELINE.md`
   为**空** ⇒ main 侧没有偷偷改过本任务的协议，「冲突只报不解 / 下一轮再试」仍是当前有效规则。

7. **`merge-union` 驱动按预期工作**（只覆盖契约/台账两类文件）：
   台账 `module-progress.md` 按模块 ID 求并集折叠 465 行同 ID 记录（**无冲突**）；
   `packages/api-contracts/**` 8 个文件全部干净合并；1 个文件走「行级兜底并集」并打印人工确认提示。

8. **台账校验**：`node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**
   （merge 已 abort，台账未被改动）。集成头本地与 `origin/integration/module-dev`
   **逐哈希一致**（`a10ec0ec`）⇒ 集成头无未推提交。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | **冲突未并入**（`apps/web/src/lib/api-proxy.ts`，唯一 1 个文件 / 2 个 hunk / 4693、5362 行起） |
| `origin/main` HEAD | `a257a922` feat: 品牌授权交付（fetch 前后一致，未推进） |
| 待并入 main 提交数 | **5**（`d28188d6` / `ec225641` / `a9f3b4d0` / `3bedc528` / `a257a922`） |
| merge base | `1bb270a0`（与前三轮相同 ⇒ squash 抹祖先的根因未变） |
| 冲突文件 | `apps/web/src/lib/api-proxy.ts`（唯一 1 个） |
| 自动合并成功 | `module-progress.md`（台账求并 465 行）、`packages/api-contracts/**` 8 个文件、2 个 V255/V252 迁移、brand-credential / pending-inspection 前后端与测试、6 篇 docs |
| 合并 commit | **无**（abort 后未落；拟写的 5 提交清单正文未使用） |
| 预检 | **未跑**（无合并结果可检） |
| push | **未 push**（合并未落地） |
| 集成头 HEAD | `a10ec0ec`（本地 == `origin/integration/module-dev`） |
| 台账（同步前后无变化） | 已交付 **276** / 进行中 **0** / 未开发 **189**（共 465，完成率 59.4%） |
| 台账校验 | `sync.mjs --check` ⇒ **✓ 一致** |
| 闸 | `held`(stale=false) → 密集竞态 **400s** 后 acquire → 收尾 release（`free`） |
| 并发同步会话 | 本轮等闸期间存在**另一个**同步会话持闸（owner `39097`），闸交接 1 次 |

## 下一轮解法（给人工 / 并入任务）

1. **`apps/web/src/lib/api-proxy.ts` 可以按 ours 收**：theirs 的 105 条 `pattern:` 是 ours 107 条的真子集，
   ours 仅多 2 条（`brand-credentials` 16.4.2.2、`pending-inspections` 8.2.2.2）⇒ **两侧都要留**，
   解法 = 把集成头侧的 2 条元素并进 main 侧的 `services[]`（**不是二选一**）。
   解完必须跑 `apps/web/src/app/api/[...path]/route.test.ts` 的真 proxy 断言（含反向用例）——
   **白名单错一条就是静默 404**，且 typecheck / lint / vitest / Playwright 会**全部全绿**骗过去。
2. **根治项仍是发布侧**：让 main 侧改推**真实提交**而非 GitHub squash。
   merge base 能自然前移，**本冲突随之消失**（11:45 / 12:45 / 14:23 三轮连续记录）。
3. **协议授权缺口（需人工决策）**：把 `merge-resolve.mjs` 正式接进本方向协议，两条路任选其一 ——
   (a) 在 `SYNC-MAIN.md` §0 增加一条：判定工具退出码 0 且结论为「取集成分支侧 + 累积三件套成立」时
   **允许机械解冲突**（并强制跑 api-proxy 全量 allowlist 回归）；
   (b) 维持「只报不解」，但把该工具的结论固定写进每轮汇报，作为人工决策的证据包。
   ⚠️ 两条路都有前提：解冲突前必须**独立复核三件套**，不能只信工具退出码。
4. **同步任务不应把 backlog / 会话文档提交进集成头**（11:45 轮已提，本轮沿用）：
   本轮会话文档写在**主工作区**（未入库），不进集成头 ⇒ 避免自造下一个 add/add 冲突文件。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/lane.mjs` — `gate status` / `acquire` / `release` 语义
- `tools/module-progress/sync.mjs` — 台账同步器（`--check` 绿）
- `tools/module-progress/merge-resolve.mjs` — 判定工具（只读，退出码 0，仅作取证）
- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` — 反方向任务的协议（工具授权出处，**非本方向**）
- `apps/web/src/lib/api-proxy.ts` — 本轮唯一冲突文件（累积型 `services[]` 白名单）
- `docs/product/capability-blueprint/module-progress.md` — 台账（276 / 0 / 189）
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` — 本问题的 backlog 记录（集成头侧）
- 上一轮同题：`docs/sessions/2026-10/2026-10-21-1423-main-sync-conflict-api-proxy-only.md`（主工作区）
- 本轮文档位置：`docs/sessions/2026-10/2026-10-21-1426-main-sync-conflict-api-proxy-only.md`（主工作区，未入库）

## 关键 trade-off

- **为什么有机械解法仍然 abort**：`merge-resolve.mjs` 的结论**技术上是对的**（本轮已独立复核确认三件套成立），
  但**协议授权**与**技术可行**是两件事。本任务 prompt 逐字要求「**冲突不自行解决**」。
  ⇒ 本轮按协议 abort，把工具结论作为证据留给人工决策，
  **没有让「语法上能过、语义上悄悄删路由」的 `api-proxy.ts` 白名单带着未审查的改动进主干**。
- **为什么不改协议来放行**：`SYNC-MAIN.md` 是**版本控制里的唯一协议**，改它属于架构决策，
  不该由一个 cron 轮次顺手改。**根治点见「下一轮解法」第 2、3 条。**
