# 2026-10-21: 集成头同步 main（第 10 轮 · 冲突面仍是 1 文件，main 仍无任何领先）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 的同步。
本轮**结果：冲突未并入** —— 合并在 `apps/web/src/lib/api-proxy.ts` 上真冲突，按协议
`git merge --abort` 后如实上报，**未自行解冲突、未 push**。

与第 9 轮（12:45，`d84939b4`）相比，**冲突面不变（仍 1 个文件），但 `origin/main` 明显前进了**：
待并入提交从 4 颗变 5 颗（新增 `a257a922` 品牌授权 16.4.2.1），
`origin/main` tip 从 `3bedc528` 推进到 `a257a922`。
⇒ **但内容上 main 依然没有任何集成头没有的东西**（见下「模块级重算」）。

## 决策 / 结论

1. **冲突只报不解**（协议 `SYNC-MAIN.md` §0 硬规定）。本轮 `git merge --abort`，
   `MERGE_HEAD` 已清，工作区干净，HEAD 停在 `a10ec0ec`，**未 push**
   （`integration/module-dev` 与 `origin/integration/module-dev` 逐哈希一致）。

2. **闸：本轮等闸 62 分钟才拿到**（13:15 → 14:17:02），比第 9 轮的 2h25m 短。
   期间观测到 **3 次「零宽度交接」**（闸 ageSeconds 被重置为 5 / 28 / 1），
   持闸 tick 始终活着（`stale` 全程 `false`）⇒ TTL 接管路径一直不可用。
   取闸靠**密集竞态**（第二轮 1s 密度没抓到，第三轮降到 0.2s 一次命中）。

3. **本轮唯一冲突文件 `apps/web/src/lib/api-proxy.ts` 仍 ours 是严格超集**（独立重算，未采信工具结论）：

   | 口径 | base (`1bb270a0`) | ours（集成头 `a10ec0ec`） | theirs（`origin/main` `a257a922`） |
   | --- | --- | --- | --- |
   | `pattern:` 条目数 | 44 | **106** | 104 |
   | ours 独有（main 没有） | — | `brand-credentials`（16.4.2.2）、`pending-inspections`（8.2.2.2） | — |

   逐条复核累积型三件套（自写脚本 `/tmp/verify_union.mjs`，不依赖仓库工具）：
   - ① **集合包含**：main 侧 104 条 regex **全部**在集成头 106 条中 ⇒ 缺 0
   - ② **有序子序列**：main 侧 104 条在集成头中保持相对顺序 ⇒ 匹配 104/104
   - ③ **零删除**：base 的 44 条全部保留

   ⇒ **取集成头侧在结果上等同于完整求并**，与 `merge-resolve.mjs` 给出的
   「[取集成分支侧] / 三件套全成立」结论一致（本轮**独立复核后再采信**，
   未直接信工具输出）。

4. **仓库自带判定工具 `merge-resolve.mjs` 在本轮是可用的**，退出码 0
   （= 所有冲突都有可机械执行的解法）。⚠️ **但本任务协议 `SYNC-MAIN.md` 是硬规定「冲突只报不解」**，
   且该工具的用法写在**反方向**任务 `MAIN_MERGE_CRON_PROMPT.md` §4.2/§4.3
   （main ← 集成头，走 PR 流程）——**不是本方向的授权通道**。
   ⇒ 本轮**只把工具结论当作取证，不据此解冲突**。
   ⏱ 时序证据：工具 `23511277`（10-21 08:35）晚于 `SYNC-MAIN.md`
   （`11f19237`，10-20 03:39）——**协议写于工具之前**，本方向的协议没跟上工具。

5. **模块级重算（独立于工具）**：台账两边各 465 行，逐模块 ID + 状态 join：

   | 口径 | 集成头 `a10ec0ec` | `origin/main` `a257a922` |
   | --- | --- | --- |
   | 已交付 | **276** | 274 |
   | 进行中 | 0 | 0 |
   | 未开发 | 189 | 191 |
   | main 已交付但集成头未交付 | — | **0** |

   ⇒ **main 在任何模块上都不领先集成头**；集成头反而多出 2 个已交付模块
   （`8.2.2.2` 待检处理、`16.4.2.2` 授权凭证）。
   ⇒ 本轮要并入的 5 颗提交（`d28188d6` / `ec225641` / `a9f3b4d0` / `3bedc528` / `a257a922`）
   **内容上集成头全部已覆盖**。

6. **根因未变**：`git merge-base` 仍停在 **`1bb270a0`**（与 11:45 / 12:45 两轮**完全相同**）。
   `origin/main` tip `a257a922` 的父提交是 `3bedc528`（单父）、
   `ec225641` 的父提交是 `d28188d6`（单父）、`d28188d6` 的父提交是 `1bb270a0`（单父）
   ⇒ **main 上 5 颗提交全是 GitHub squash 产物**，祖先关系被抹掉，
   所以「main → 集成头」永远从 `1bb270a0` 重新分叉，撞同一个数组锚点。
   ⚠️ 逐颗核实**没有一颗是真实 merge**（全部单父）—— 与 11:45 轮更正后的措辞一致。

7. **协议一致性核对**：`git diff integration/module-dev origin/main -- tools/module-progress/SYNC-MAIN.md tools/module-progress/PIPELINE.md`
   为**空** ⇒ **main 侧没有偷偷改过本任务的协议**，`SYNC-MAIN.md` 的
   「冲突只报不解 / 下一轮再试」仍然是当前有效规则。

8. **`merge-union` 驱动按预期工作**（只覆盖契约/台账两类文件）：
   台账 `module-progress.md` 按模块 ID 求并集折叠 465 行同 ID 记录（**无冲突**）；
   `packages/api-contracts/**` 8 个文件全部干净合并；
   1 个文件走「行级兜底并集」并打印人工确认提示。

9. **⚠️ 发现并发竞态循环（P0 陷阱，本轮实测）**：
   等闸期间在同 worktree 发现**另一个 MCode 会话**（PID 77627，父进程 = MCode helper）
   正在跑 `gate acquire` 竞态循环（900 次上限、1s 间隔）。
   ⇒ 与 12:45 轮记录的「至少 4 个并发竞态循环」一致，**调度层至今没做单例约束**。
   本轮处理：**自己只保留一个竞态循环**（第二轮 1s 密度失败后收成第三轮 0.2s 单一循环，
   旧的随即结束），**release 前确认无其它竞态循环存活**。
   ⚠️ 本轮 release 后闸为 `free`，但 77627 尚有约 500s 循环窗口 ——
   **它随时可能秒抢**，这是**闸泄漏**的现成向量。

10. **台账校验**：`node tools-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**
    （merge 已 abort，台账未被改动）。

11. **⚠️⚠️ 本轮收尾时撞到「另一个并发同步轮次」在同一个 worktree 上 merge**（首次实测到完整时序）：
    - **14:22:35** 本轮 `gate release`（闸 `free`）→ **14:22:56** 另一个轮次**抢到闸**（owner 换成 88965），
      **14:23:42** 发起 `git merge --no-ff origin/main`（MERGE_HEAD = `a257a922`，
      MERGE_MSG 是**它自己写的** merge 正文，措辞与我本轮写的不同 ⇒ 可确认是另一 agent）。
    - **14:23:42–14:24:28** 它的 index 持续在推进；**14:24:27** 它自己 `reset` 掉了 merge
      （reflog 第二条 `reset: moving to HEAD`），**14:24:28** 释放闸。
    - ⇒ **最终工作区干净、无 `MERGE_HEAD` 残留、HEAD 仍 `a10ec0ec`、集成头与 origin 逐哈希一致**。
    - ⇒ **教训**：`git merge --abort` 之后**必须复查 `git status --porcelain`** ——
      本轮第一次复查（14:22:35）恰好撞上它 merge 的中间态，一度误判成「自己的残留」。
      **判别方法**：看 `$(git rev-parse --git-dir)/MERGE_MSG` 的正文措辞是不是自己写的那份 ——
      是自己的 ⇒ 自己的残留；不是 ⇒ 别人的，**不要动**（动了就是破坏别人的工作）。

## 涉及文件

- 冲突文件（未解决，已 abort）：`apps/web/src/lib/api-proxy.ts`（1 个文件，2 个 hunk）
- 本轮临时文件（未入库）：`/tmp/starcon-gate-race.sh`、`/tmp/starcon-gate-race3.sh`、
  `/tmp/verify_union.mjs`、`/tmp/cmp_ledger.mjs`
- 本文档落在**主工作区**（`main` 分支，**不 commit**）——
  遵循 12:45 轮结论「同步任务不要把会话文档提交进集成头，否则每轮同步都给自己多加一个冲突面」。

## 关键 trade-off / 讨论

- **为什么有工具给出的机械解法仍然 abort**：`merge-resolve.mjs` 的结论**技术上是对的**
  （本轮独立复核已确认三件套成立，取集成头侧不丢内容），
  但**协议授权**与**技术可行**是两件事。`SYNC-MAIN.md` §0 写的是
  「❌ 自行解冲突（`merge-union` 只覆盖契约/台账两类文件）」—— 括号里的限定语说明
  写协议时作者只知道 `merge-union`，**不知道后来有了 `merge-resolve.mjs`**。
  本任务 prompt 也逐字要求「**冲突不自行解决** —— `git merge --abort` 后把冲突文件名列进汇报」。
  ⇒ 本轮按协议 abort，**把工具结论作为证据留给人工决策**。
  ✅ 本轮因此**没有污染集成头**，也没让「语法上能过、语义上悄悄删路由」的
  `api-proxy.ts` 白名单带着未审查的改动进主干。

- **为什么不改协议来放行**：`SYNC-MAIN.md` 是**版本控制里的唯一协议**，
  改它属于架构决策，不该由一个 cron 轮次顺手改。**根治点见「后续待办」第 1 条。**

## 后续待办

1. **（根治，需人工决策）把 `merge-resolve.mjs` 正式接进本方向协议**：
   两条路任选其一 ——
   (a) 在 `SYNC-MAIN.md` §0 增加一条：判定工具退出码 0 且结论为
   「取集成分支侧 + 累积三件套成立」时，**允许机械解冲突**（并强制跑
   `pnpm vitest run apps/web/src/app/api/trade` 全量 allowlist 回归）；
   (b) 或在**发布侧**改用**真提交**替代 squash —— 根治项，
   merge-base 能自然前移，冲突随之消失（12:45 / 11:45 轮已连续记录三轮）。
   ⚠️ (a) 与 (b) 都有前提：解冲突前必须**独立复核三件套**，不能只信工具退出码。

2. **（闸机制，仍是 P0）并发竞态循环 + keepalive 续命 + release 后被秒抢**：
   本轮实测另一 MCode 会话的竞态循环存活 ≥6 分钟且与本轮同抢一个闸；
   本轮 release 后 **21 秒**就被另一轮次抢走并发起 merge（见「决策」第 11 条）。
   建议**调度层保证同一时刻只有一个 main 同步竞态循环**，
   并让 `keepalive.sh` 跟着持闸者生命周期走。
   ⏱ **另需修**：`git merge --abort` 之后必须复查工作区，并按 MERGE_MSG 措辞判别残留归属。

3. **（发布侧）main 侧 5 颗提交全是 squash** ⇒ 冲突每轮复现。
   在发布侧改用真提交前，本方向每轮都会在同一处（`api-proxy.ts` 数组锚点）撞同一堵墙。

4. **（本轮新增观察）冲突面已稳定在 1 个文件**：
   12:45 轮的 3 个冲突文件里，后 2 个已被 batch-49 车道消解；
   本轮只剩 `api-proxy.ts`，且**只有 2 个 hunk**（12:45 轮是 1 个 hunk ——
   本轮因 `a257a922` 新增 `brand-authorizations` 路由而多 1 个）。
   ⇒ 冲突面在**收敛**，与「main 无新交付、集成头持续领先」的判断一致。

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`（本任务）、`tools/module-progress/PIPELINE.md`（总协议）
- 反方向任务协议（工具授权出处，**非本方向**）：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` §4.2 / §4.3
- 判定工具：`tools/module-progress/merge-resolve.mjs`（`23511277`）
- backlog：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`
- 上一轮同题：`docs/sessions/2026-10/2026-10-21-1245-main-sync-conflict-api-proxy-only.md`（主工作区）
- 更早同题：`docs/sessions/2026-10/2026-10-21-1145-main-sync-conflict-3-files.md`（集成头）
