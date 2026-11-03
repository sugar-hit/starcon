# 2026-11-03: PR#73 合入 main，交付看板台账滞后清零

## 摘要

每日「合 main + 刷看板」18:30 轮次：main 有 1 个新提交（上一轮 PR#72 的合并提交）⇒ 走完整同步流程，
并入集成头并推送；随后把 `integration/module-dev` 经 **PR #73** 合入 `main`（合并提交 `75ccb79e`，双父），
CI 与 `main` 基线**完全持平**（上一轮阻塞项 `Catalog Browser & Contract Tests` 已随 platform 存储根修复转为通过）。
交付进度看板已原地刷新发布：**465/465 = 100%**，`LEDGER_LAG_STATE` 由 `main-behind` 转为 `in-sync` ——
此前持续多日的「看板显示过期数字」状态在本轮**清零**。

## 决策 / 结论

- **上一轮是否崩溃：无**。10:30 轮次会话（`mvs_551770bc…`）终态 `idle`，属正常收工。
  会话列表里的 `error` 会话均属「并行车道功能开发流水线」任务，不是本任务。
- **PR #72 已在他方合并**（14:18 CST，合并提交 `0d661df5`）：platform 存储根缺陷的修复已落到集成头，
  故 10:30 轮次点名的阻塞项不再成立。本轮据此重开 PR #73 走合并。
- **合并形态 = 真合并无冲突**：落地树 `d9dbd5b8…` ≠ main 树 `839a3fdf…` ⇒ 确有新内容（1 个文件）⇒ 开 PR；
  `merge-tree` 报 0 冲突；1.5 放行判据 PASS（main 上驱动已是修复版，本次不带契约产物，无需拆 PR#1/PR#2）。
- **第 2 步用合并锁而非调度闸**（协议 §2.5）：调度闸只保护集成头 worktree 的本地状态，
  而推分支 / 建 PR / 合并 PR 只写远端 ⇒ 用错锁会让合 main 被流水线饿死。
- **CI 用基线相对判据放行**：闸工具（**PR 头那份** `ci-gate.mjs`，`unreachedJobs`=6，确认修复版）
  结论「可以合并」；基线 run `#41589030859` 已 completed，排除「基线在跑 ⇒ 假阻塞」。
  上一轮的阻塞作业 `Catalog Browser & Contract Tests` **本轮通过**，失败集合与基线完全相同：
  `{Lint, Security Scan, Java 21 & PostgreSQL Integration}`。
- **两个「明细不可比」作业补了更强的证据**（协议 §7.3 要求，不能只看作业粒度）：
  - `Lint`：两次 run 的失败**文件集合**各 583 个，双向差集**均为空** ⇒ 零新增格式债。
  - `Security Scan`：审计条目与严重度分布**完全一致**（12 项；1 low / 8 moderate / 2 high / 1 critical），
    且 `pnpm-lock.yaml` 两侧 sha1 逐字节相同 ⇒ 差异不来自本次改动。
- **合并后形态四问全过**：双父 = 2；main 树与集成分支树**逐字节相同**；集成分支是 main 的祖先；
  `merge-tree` 只输出一个树哈希。

## 涉及文件

- `docs/sessions/2026-11/2026-11-03-dashboard-stale-merge-flow-repair.md`（本次同步进 main 的唯一文件）
- `tools/module-progress/ci-gate.mjs`（PR 头那份是修复版，结论可信）
- `tools/module-progress/merge-lock.mjs`（本轮合 main 的互斥锁）
- `tools/module-progress/lane.mjs`（第 1 步的调度闸）
- `tools/progress-site/`（看板构建与发布）
- `deploy/docker/docker-compose.catalog.yml`（上一轮阻塞项的修复落点，已含 `PLATFORM_IO_STORAGE_ROOT`）

## 关键 trade-off / 讨论

- **没跑本地全量 L2**：按任务书 §5.1，本次无冲突、解冲突未触及后端业务代码，不触发必须跑全量的例外；
  且全量 L2 是发热高峰，CI 才是真闸。改跑定向验证 + 后端 `mvnw -q compile` 兜底。
- **typecheck 命中 turbo 缓存**：本轮合并未改动任何 TS/包代码（仅 1 个 `.md`），缓存命中有效；
  静态预检三项（`ADD COLUMN` / `SET NOT NULL` / `ADD CONSTRAINT CHECK`）相对 main 基线**增量均为 0**。
- **`gh pr merge` 报「failed to delete local branch」不是合并失败**：本地分支正被本轮 worktree 占用，
  合并本身已成功 —— 以 `gh pr view` 的 `MERGED` 与 `origin/main` 指针前进为准，不重跑合并。
- **worktree 硬校验必须用绝对路径**：首次校验用相对路径（进入 worktree 后再 `cd .worktrees/…`）必然失败，
  险些误判「不是真 worktree」。正确做法是 `git rev-parse --show-toplevel` 与 `pwd -P` 的**绝对路径**比对。
- **发布后首读可能命中 CDN 旧缓存**：本轮首次按标准 URL 取 `data.json` 拿到的是发布前的旧文件
  （`generatedAt` 差 4 小时），加缓存绕过参数后确认已是新文件，稍后标准 URL 也已刷新。
  ⇒ 后续轮次若首读数字「看着没变」，先用带参 URL 复核，再判定发布失败。
- **没动主工作区的本地 `main`**：本地 `main` 落后 origin/main 一个提交且可快进，工作区无 tracked 改动。
  本任务不负责推进人类分支，未执行快进，留待人工处理。

## 后续待办

- [ ] 人工把主工作区本地 `main` 快进到 `origin/main`（当前落后 1 个提交，可快进）。
- [ ] 上一轮的 backlog `docs/backlog/2026-11-03-platform-io-storage-root-blocks-catalog-browser-ci.md`
      所述缺陷**已修复并合入**，可关闭该 backlog 条目。
- [ ] 集成头本地 L2 覆盖不到 compose 全栈启动 ⇒ 「容器内起不来」类缺陷仍需在流水线侧加一道冒烟。

## 关联

- 台账：`docs/product/capability-blueprint/module-progress.md`
- 看板：https://338k302h0q2ii.space.mcode.cn
- PR：https://github.com/sugar-hit/starcon/pull/73
- 上一轮：https://github.com/sugar-hit/starcon/pull/72
