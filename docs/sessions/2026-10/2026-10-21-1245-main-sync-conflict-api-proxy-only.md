# 2026-10-21: 集成头同步 main（第 9 轮 · 冲突面从 3 文件缩到 1 文件）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 的同步。
本轮**结果：冲突未并入** —— 合并在 `apps/web/src/lib/api-proxy.ts` 上真冲突，按协议
`git merge --abort` 后如实上报，**未自行解冲突、未 push**。

与前 8 轮（01:15 / 03:15 / 04:15 / 05:15 / 06:43 / 07:40 / 11:27 / 11:45）相比，本轮有两个**实质变化**：

1. **冲突面从 3 个文件缩到 1 个**（11:45 轮是 `api-proxy.ts` + `PriceHistoryIntegrationTest.java` +
   `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`）。
   后两个已被 batch-49 车道消解，本轮只剩 `api-proxy.ts` 一个文件、**且只剩 1 个 hunk**。
2. **`origin/main` 真的前进了**：从 `ec225641` 推进到 `3bedc528`，待并入提交从 1 颗变 4 颗
   （新增 `a9f3b4d0` 7 项能力 + `3bedc528` chore）。
   ⇒ **但内容上 main 依然没有任何集成头没有的东西**（见下「ours 是严格超集」）。

> ⚠️ **根因未变**：merge base 仍停在 `1bb270a0`（与 11:45 轮**完全相同**）⇒
> main 侧 `d28188d6` / `ec225641` 仍是 GitHub squash 产物，祖先关系被抹掉。

## 决策 / 结论

1. **冲突只报不解**（协议 SYNC-MAIN.md §0 硬规定）。本轮 `git merge --abort`，
   `MERGE_HEAD` 已清，工作区干净，HEAD 停在 `d84939b4`，**未 push**。

2. **本轮唯一冲突文件 `apps/web/src/lib/api-proxy.ts` 的性质：ours 是严格超集，冲突纯属数组锚点撞车。**

   | 口径 | base (`1bb270a0`) | ours（集成头 `d84939b4`） | theirs（`origin/main` `3bedc528`） |
   | --- | --- | --- | --- |
   | 文件行数 | 2634 | 5411 | 5359 |
   | `services[]` 元素数（`prefix:`） | 72 | **136** | 135 |
   | numstat（相对 base） | — | **+2839 / −62** | +2787 / −62 |
   | `pattern:` 正则**独有**条目 | — | **1 条**：`brand-authorizations`（16.4.2.1 品牌授权） | **0 条** |

   ⇒ 逐条比对 `pattern:` 集合：**theirs 的每一条都出现在 ours 里**，
   ours 仅多出 1 条 `brand-authorizations`（16.4.2.1 品牌授权，batch-49 刚交付）。
   ⇒ **两侧各自往同一个 `services[]` 数组的同一锚点插入新元素 ⇒ 纯位置冲突**，
   不是内容分歧。**按 ours 收即可保住全部内容**，但仍需人工确认（协议不允许本任务代劳）。

3. **⚠️ 纠正 11:45 轮的一处措辞（独立核验）**：11:45 轮记「11:27 轮说 `ec225641` 是双父 merge，
   后更正为单父 squash」。本轮独立核验：`git log -1 --format='parents=[%P]' ec225641` ⇒
   **`parents=[d28188d6]`，单父**，`d28188d6` 亦为单父 squash ⇒ 11:45 轮的更正是对的，
   两轮结论一致：**main 上两颗提交都是 squash**，merge base 被钉在 `1bb270a0`。

4. **merge-union 驱动按预期工作**：台账 `module-progress.md` 按模块 ID 求并集折叠 465 行同 ID 记录
   （**无冲突**）；`packages/api-contracts/**` 8 个文件全部干净合并；
   1 个文件走了「行级兜底并集」并打印人工确认提示（与 11:45 轮同一现象）。

5. **闸情（本轮正常，无 keepalive 续命）**：
   - `gate status`：`held` / `stale: false` ⇒ 持闸 tick **活着**（闸外取证：`ps` 见同一 worktree
     内 `./mvnw verify` 正在跑，进程从 `channel` 服务推进到 `warehouse` 服务）⇒ 协议要求**等待**，不抢。
   - 按协议用**密集 `gate acquire` 竞态**（1 秒间隔，**未**采用「先 status 观测到 free 再 acquire」
     的写法 —— 协议明文记录交接窗口可窄到零宽度）⇒ **1685 秒（约 28 分钟）后拿到闸**。
   - 期间**未跑任何构建、未 fetch、未 merge**（协议：等闸期间只做只读取证，
     并发构建会与持闸 tick 的 L2 共用 `backend/**/target/` 与 PG 容器 ⇒ 必假红）。
   - 收尾 `gate release` ⇒ `gate: free`。

6. **本轮未跑预检、未 push**：合并未落地，没有可检的合并结果；**编译不过或未合并的集成头一律不推**。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | **冲突未并入**（`apps/web/src/lib/api-proxy.ts`，唯一 1 个文件 / 1 个 hunk / 4157–4211 行） |
| `origin/main` HEAD | `3bedc528` chore: 同步集成分支最新进度 |
| main 推进 | `a9f3b4d0..3bedc528`（fetch 前 tip 为 `a9f3b4d0`） |
| 待并入 main 提交数 | **4**（`d28188d6` / `ec225641` / `a9f3b4d0` / `3bedc528`） |
| merge base | `1bb270a0`（**与 11:45 轮相同** ⇒ squash 抹祖先的根因未变） |
| 冲突文件 | `apps/web/src/lib/api-proxy.ts`（唯一 1 个） |
| 自动合并成功 | `module-progress.md`（台账求并 465 行）、`packages/api-contracts/**` 8 个文件、`docs/backlog/2026-10/2026-10-21-ci-baseline-vite-advisory-blocker.md`、`docs/sessions/2026-10/2026-10-21-0900-main-sync-vite-baseline-block.md`、`docs/sessions/2026-10/2026-10-21-0930-main-sync-baseline-refresh-merge.md` |
| 合并 commit | **无**（abort 后未落；拟写的 4 提交清单正文未使用） |
| 预检 | **未跑**（无合并结果可检） |
| push | **未 push**（合并未落地） |
| 集成头 HEAD | `d84939b4` docs(batch-49): 16.4.2.1 品牌授权 |
| `origin/integration/module-dev` | `d84939b4`（与本地一致 ⇒ 集成头无未推提交） |
| 台账（同步前后无变化） | 已交付 **274** / 进行中 **0** / 未开发 **191**（共 465，完成率 59.1%） |
| 台账校验 | `sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动） |
| 闸 | `held`(stale=false) → 竞态 **1685s** 后 acquire → 收尾 release（`free`） |
| 活 tick | 同一 worktree 内 `./mvnw verify` 在跑（L2 进行中，`channel` → `warehouse`） |

## 下一轮解法（给人工 / 并入任务）

1. **`apps/web/src/lib/api-proxy.ts` 可以按 ours 收**：theirs 的 `pattern:` 集合是 ours 的真子集，
   ours 仅多 1 条 `brand-authorizations`（16.4.2.1）⇒ **两侧都要留**，
   解法 = 把 `brand-authorizations` 元素并进 main 侧的 `services[]`（**不是二选一**）。
   解完必须跑 `apps/web/src/app/api/[...path]/route.test.ts` 的真 proxy 断言（含反向用例）——
   **白名单错一条就是静默 404**。
2. **根治项仍是发布侧**：让 main 侧改推**真实提交**而非 GitHub squash。
   `ec225641` 已证明单父常规提交可行；把剩下落后的 7 个模块
   （5.4.2.1 平台价格同步 / 6.2.2.1 采购申请 / 6.4.2.1 质检单 / 6.5.1.1 采购对账 /
   6.5.2.1 采购分析 / 7.7.2.2 平台托管与全托管备货 / 16.2.2.2 认证与资质）
   也以常规提交推上去，merge base 才能自然前移，**本冲突随之消失**。
   ⇒ 本轮这 4 颗提交**一颗都不解决 merge base 问题**（`3bedc528` 是 chore，无内容；
   `a9f3b4d0` 是常规提交但仍以 `ec225641` 为父 ⇒ 血缘链仍挂在 squash 后面）。
3. **同步任务不应把 backlog / 会话文档提交进集成头**（11:45 轮已提，本轮沿用）：
   本轮会话文档写在**主工作区**（未入库），不进集成头 ⇒ 避免自造下一个 add/add 冲突文件。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/lane.mjs` — `gate status` / `acquire` / `release` 语义
- `tools/module-progress/sync.mjs` — 台账同步器（`--check` 绿）
- `apps/web/src/lib/api-proxy.ts` — 本轮唯一冲突文件（累积型 `services[]` 白名单）
- `docs/product/capability-blueprint/module-progress.md` — 台账（274 / 0 / 191，`sync --check` 绿）
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` — 本问题的 backlog 记录（集成头侧）
- 上一轮同题：`docs/sessions/2026-10/2026-10-21-1145-main-sync-conflict-3-files.md`（该文件在集成头侧）
- 本轮文档位置：`docs/sessions/2026-10/2026-10-21-1245-main-sync-conflict-api-proxy-only.md`（主工作区，未入库）
