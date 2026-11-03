# 2026-10-23 00:06 main→集成头同步：CRM 8 模块重复交付导致 31 文件冲突

> **本轮有并行实例。** 另一同步实例在同一时段独立执行了同一笔同步，结论一致（同样 31 个冲突、同样 abort、
> 同样未 push）。它的取证更细（行级 ours/theirs 差集、闸争用全时间线），
> 见同目录 `2026-10-23-0006-main-sync-conflict-31-files-gate-contention.md`。
> 两篇是**同一事件的两份独立记录**，不是两轮同步；集成头被 abort 回到 `f36ac759`，只被合并尝试过一次。

## 决策摘要

本轮 `main → integration/module-dev` 同步**未并入**（冲突后 `git merge --abort`），**未推送**，集成头保持 `f36ac759`。

- **结果**：冲突未并入。`origin/main` 有 5 个新提交待并入，`git merge --no-ff origin/main` 在 **31 个文件**上冲突
  （30 × `add/add` + 1 × `both-modified`），按 `SYNC-MAIN.md` 协议 abort + 上报，**不自行解冲突**。
- **根因不是「main 改了集成头也改了的同一文件」**，而是**同一批 8 个 CRM 模块被交付了两次**：
  `main` 侧经 batch-64/65/66「CRM 合规车道」并入，集成头侧另有车道独立交付了**同样的 8 个模块**。
  两侧产出**同名同路径**的模块文件，但内容不完全一致 ⇒ git 判 `add/add`。
  `apps/web/src/lib/api-proxy.ts` 则是两侧**在同一 `services[]` 数组的同一区域各自插入新元素** ⇒ `both-modified`。
- **冲突的 8 个模块**：`15.1.1.2` 消息发送 / `15.1.1.3` 消息分类 / `15.2.1.2` 评价回复 / `15.2.1.3` 差评处理 /
  `15.3.2.2` 接触记录 / `15.5.2.1` 关怀与催办 / `16.1.1.1` 账号健康总览 / `16.1.1.2` 绩效指标监控。
  这 8 个模块**正是 main 上 `d705c085`（4 个）+ `4ebb4b04`（4 个）两个提交交付的内容**。
  ⚠️ 注意 `15.5.1.1 自动回复` **不算共享模块** —— 实测 `15.5.1.1` 在集成头 `api-proxy.ts` 出现 3 次、
  在 `origin/main` 出现 **0** 次 ⇒ 它是**集成头独有**的模块（`api-proxy.ts` 冲突里那几行
  「15.5.1.1 自动回复（crm 服务，卷 15 L3 锚点 15.5.1 下的自动回复子域）」来自集成头侧）。
  **共享的是 8 个，不是 9 个。**
- **两侧实现高度相似但不相等**（不是随便撞车，是同一批需求的两次实现）：
  `crm/message-send-model.ts` 集成头 428 行 vs main 431 行；`crm/care-reminder-model.ts` 集成头 362 行 vs main 367 行。
  `api-proxy.ts`：merge-base 7662 行 → 集成头 8390 行（新增 15 条 `services[]` 元素）→ main 8168 行（新增 11 条），
  **插入区间重叠**。

## 关键 trade-off

1. **不自行解冲突，即使冲突文件看起来"就差几行"。**
   两侧实现只差 3~5 行，肉眼可辨，但**「保留哪一侧」是业务裁决不是机械裁决**：
   保留集成头 = 丢掉 main 侧那 4 个模块的 3~5 行修正；保留 main = 丢掉集成头车道已验收的 3~5 行。
   协议明令「冲突留给人工/下一轮处理」，`merge-union` 驱动也**只覆盖契约与台账两类文件**，
   覆盖不到 `apps/web/src/**` 的模块源码 ⇒ 这一类冲突**必须**有人裁决。
2. **abort 而不是留冲突树。**
   `MERGE_HEAD` 残留 + 主工作区脏 ⇒ 下一个 tick 的 `merge-ready` 命中「工作区不干净，拒绝切分支」直接 `exit(1)`，
   **并入通道彻底堵死**；且本循环里下一条车道会在同一棵冲突树上再 merge，报出与它自身无关的级联冲突
   （`lane-merge-abort.itest.mjs` 记录 2026-10-07 一晚因此被撂倒两次）。
   实测本轮 abort 后：无 `MERGE_HEAD`、无冲突标记、工作区干净、`HEAD` 与 `PRE` 一致。
3. **等闸等了 ~50 分钟，而不是轮询 `status` 或清闸。**
   闸被 batch-67 tick 持着跑 L2（`mvnw verify`），`stale: false` ⇒ 持闸者活着。
   协议要求**直接竞态 `gate acquire`**（`mkdirSync` 原子互斥，闸的交接窗口可窄到零宽度），
   轮询「先观测到 free 再 acquire」会整段错过它。等闸期间**只做只读取证**，不跑第二个构建、不跑 `git fetch`
   （与持闸 tick 的 L2 共用 `backend/**/target/` 与 PG 容器 ⇒ 并发必假红）。
4. **闸在 00:25:12 会因 TTL 到期变 stale，但没有等它到期。**
   tick 的 L2 在 00:00:51 跑完并正常释放闸，00:05:28 由 `gate acquire` 竞态取得，00:06 收尾释放，
   下一 tick 随即接管（`ageSeconds: 0`）—— **TTL 接管路径本轮没走到**，不需要冒险与活着的 tick 并发构建。
5. **这是 `docs/sessions/2026-10/` 里第 9+ 次 `main-sync-conflict` 记录**（2026-10-21 当天就有 9 份）。
   冲突不是偶发噪声，而是**结构性重复**：main 与集成头在**没有互斥机制**的前提下各自推进同一批模块。
   `merge-union` 解决的是「同文件按键求并集」，解决不了「同模块被写了两遍」。
6. **行级取证：只有 `api-proxy.ts` 可以无脑取一侧，其余 30 个文件不行。**
   （取证由本轮并行实例完成，见同目录 `2026-10-23-0006-main-sync-conflict-31-files-gate-contention.md`）
   `api-proxy.ts` ours 独有 140 行 / theirs 独有 **0** 行 ⇒ **集成头是严格超集，取 ours 无损失**。
   但其余 30 个文件**两侧都有对方没有的内容**（如 `care-reminder-workspace.tsx` ours 25 / theirs **52**，
   `review-reply-workspace.tsx` 33 / **43**，`message-send-workspace.tsx` 23 / **35**）⇒ **盲目取一侧必丢内容**。
7. **本轮与另一个同步实例正面相撞**（新现象）。
   00:03:18 另一实例取闸 → 在**同一 worktree** 发起同一笔 merge（撞同样 31 个冲突）→ 00:05:2x 自行 abort 放闸
   → 本轮 00:05:28 取得闸后**复现了同一组 31 个冲突**（逐文件一致）⇒ **冲突是确定性的，不是偶发并发**。
   ⚠️ 两个实例在同一 worktree 先后 merge 同一笔提交时，`MERGE_HEAD` / `MERGE_MSG` 会互相复用
   （git dir 里留的合并信息被下一个实例读到）⇒ 建议 owner 文件带**任务 kind 标识**，让同 kind 实例互相让位。
8. **有界的竞态窗口可能「合法地」耗尽而不该放弃**。
   本轮第一个 40 分钟竞态窗口在持闸 tick 连续跑 L2 的整段时间里**一次都没命中**（窗口到期退出），
   第二个窗口在 tick 00:00:51 跑完 L2、00:06 放闸后**数秒内命中**。
   ⇒ 协议的**无界竞态循环**才是正确形状；有界窗口只在「确认持闸者已死」时才该退出。
   另：1 秒间隔 `node` 子进程竞态**实测够用**（放闸后数秒内命中），不必改成 5ms 密集竞态。

## 文件清单

**本轮未产生任何仓库改动**（merge 已 abort，集成头未动，`HEAD` 仍为 `f36ac759`）。

**待并入 `origin/main` 的 5 个提交**

- `4ebb4b04` feat: 消息分类、差评处理等 4 项能力交付（15.1.1.3 / 15.2.1.3 / 15.5.2.1 / 16.1.1.2）
- `d705c085` feat: 消息发送、评价回复等 4 项能力交付（15.1.1.2 / 15.2.1.2 / 15.3.2.2 / 16.1.1.1）
- `36b2c51e` docs: 记录集成分支并入主分支第 32 轮的取证与裁决
- `08a2947c` chore: 同步集成分支最新进度
- `148ef51b` chore: 同步集成分支进度纪要与车道派发记录

**冲突文件 31 个（`git diff --name-only --diff-filter=U`）**

源码 18 个：

- `apps/web/src/lib/api-proxy.ts`（`both-modified`）
- `apps/web/src/components/crm/message-send-model.ts` / `-model.test.ts` / `-workspace.tsx`
- `apps/web/src/components/crm/review-reply-model.ts` / `-workspace.tsx`
- `apps/web/src/components/crm/negative-review-model.ts` / `-workspace.tsx`
- `apps/web/src/components/crm/customer-contact-model.ts` / `-workspace.tsx`
- `apps/web/src/components/crm/care-reminder-model.ts` / `-model.test.ts` / `-workspace.tsx`
- `apps/web/src/components/account-health/account-health-model.ts` / `-workspace.tsx`
- `apps/web/src/app/crm/care-reminders/page.tsx`
- `apps/web/src/app/crm/review-replies/page.tsx`
- `apps/web/src/app/api/crm/review-replies-allowlist.test.ts`

会话纪要 13 个（`docs/sessions/2026-10/`，两侧各自新增同名文件）：
`15-1-1-3-message-classification` / `15-2-1-3-negative-review` / `15-5-2-1-care-reminder` /
`16-1-1-2-performance-metrics` / `2035-main-sync-round35-parallel-instance` / `2040-main-sync-round36` /
`batch-64-dispatch-crm-compliance-lanes` / `batch-65-crm-compliance-delivery` /
`batch-66-crm-review-care-and-performance` / `channel-account-health` / `crm-customer-contact` /
`crm-message-send` / `crm-review-reply`（均带 `2026-10-22-` 前缀与 `.md` 后缀）。

**台账**：`docs/product/capability-blueprint/module-progress.md` 本轮**未改动**
（同步前后一致：已交付 328 / 进行中 0 / 未开发 137，合计 465 条）。

## 后续待办

1. **裁决 8 个重复交付模块的归属**（人工）。两侧实现只差 3~5 行，需要定「以哪一侧为准」，
   或是否逐模块取并集 —— 这是业务裁决，不能由同步任务代劳。
   ⚠️ 其中 **`api-proxy.ts` 可以直接取集成头侧**（行级取证：集成头严格超集，取 ours 无损失）；
   **其余 30 个文件两侧都有对方没有的内容**，必须逐个语义合并，不能机械取一侧。
2. **13 个同名会话纪要取并集**（人工或人工授权后批量处理）—— 这类纯文档冲突比源码冲突好解决。
3. **给「同一模块被两侧各写一遍」加前置闸**（架构级）。
   `merge-union` 只覆盖契约与台账；建议在 `lane.mjs plan` 阶段增加
   「目标模块是否已在 `origin/main` 出现」的查重，避免派发已交付的模块。
4. **本轮合并失败，下一轮同步需重新执行**：`git merge --no-ff origin/main` 在裁决前会**确定性地**
   重复冲突（并行实例已独立复现同一组 31 文件）⇒ **不建议盲目重试**，
   先按 1~3 裁决掉重复模块，再重跑同步。
5. **闸与并行实例的协议修订**（给人工裁决）：
   - owner 文件加**任务 kind 标识**，让同 kind 的同步实例互相让位（避免同 worktree 先后 merge 同一笔提交，
     `MERGE_HEAD` / `MERGE_MSG` 互相复用）；
   - 等闸竞态**保持无界**，不要加超时上限（持闸 tick 跑 L2 40~60 分钟是常态，有界窗口会合法耗尽）。
