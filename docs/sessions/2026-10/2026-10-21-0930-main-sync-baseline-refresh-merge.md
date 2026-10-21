# 2026-10-21: 集成分支同步 PR #12 合并（刷新过期 CI 基线后放行）

## 摘要

把 `integration/module-dev` 经 PR #12 合入 `main`（24 项能力交付）。上一轮判阻塞，根因是
`main` 的 CI 基线记录于公告发布之前 7 小时 9 分，属于**基线过期**而不是回归。本轮没有把
解除条件推给开发流水线，而是**在 `main` 自己的提交上重跑那一项 `Security Scan` 作业**，
取得当前真实基线；闸随即判「可以合并」，PR 已 squash 合入 `main`。

## 决策 / 结论

1. **`Security Scan` 的新增失败与本次合入无关**，判据是三条互相独立的证据：
   - **依赖图逐字节相同**：`pnpm-lock.yaml`（blob `24a4b1edefacd1c344fe764fba6d952ef66277c5`）
     与 `package.json`（blob `0cc326692cb26e38bf50941be571db668544fca6`）在 `upstream/main`
     与 PR head 上 **hash 完全相同**。`pnpm audit` 的输出由锁文件决定 ⇒ 两侧结果必然相同。
   - **公告晚于基线**：GHSA-587h-7wmj-h5pm 的 `published_at` 是 `2026-10-20T19:48:25Z`，
     而基线 run #39423999973 的 `createdAt` 是 `2026-10-20T12:39:47Z` ——
     基线那次跑的时候这条公告**还不存在**，所以它当时通过。
   - **`main` 自己的树今天同样红**：在 `main` 的提交上重跑 `Security Scan`，输出与 PR 侧逐字相同
     （`6 vulnerabilities found` / `Severity: 4 moderate | 2 high` /
     `vite >=7.0.0 <=7.3.6` → patched `>=7.3.7`）。
2. **解除卡点的正确动作是刷新基线，不是放宽判据**。基线过期时，闸工具必然判阻塞 ——
   这不是闸坏了，是闸忠实地报告了「基线已过期」。把阈值放宽会让闸失去意义；
   正确的做法是让基线重新代表 `main` 的当前状态，然后照常让闸自己判。
3. **基线可以按需手动刷新**，不必等 `main` 自然产生新 run：
   `gh run rerun <run-id> --job <job-id>`。这补上了上一轮 backlog 里
   「`main` 的基线不会自己刷新 ⇒ 每轮撞同一道墙」这个结论的另一半 ——
   基线不会**自动**刷新，但**可以手动**刷新。
4. **只重跑那一个作业，不重跑整个 run**。整轮重跑会顺带重跑 Java 集成测试与 Catalog 端到端，
   而这两项本身存在偶发；一旦基线那一侧偶发成别的形态，闸就会把 PR 判成「新增失败」。
   只重跑 `Security Scan`（job `118631807667`）能以最小代价取得缺口那一格数据，
   并且其余作业沿用原结论，使「新增失败用例」的逐条比对维持不变。

## 涉及文件

- `docs/backlog/2026-10/2026-10-21-ci-baseline-vite-advisory-blocker.md`（补充进展与刷新方式）
- `docs/sessions/2026-10/2026-10-21-0900-main-sync-vite-baseline-block.md`（上一轮记录）
- `tools/module-progress/ci-gate.mjs`（判据工具，本轮只读使用）
- `.github/workflows/ci.yml`（`Security Scan` 作业定义）

## 关键 trade-off / 讨论

- **为什么不直接在同步 PR 分支上把 vite 升掉**：那会让 PR 分支偏离集成分支的实际内容，
  下一轮同步还要再合一次、可能再冲突；而且同步任务只负责「合入」，不负责开发。
  升 vite 是真实的安全债，但它该由开发流水线落到集成分支，不该由同步任务夹带。
- **为什么刷新基线不算「绕过闸」**：闸工具在刷新后的基线上**照常运行、照常输出结论**，
  结论是「可以合并」。本轮没有替换判据、没有改阈值、没有用 `--admin` 强合 ——
  只是把比较对象从一份 7 小时前的快照换成了 `main` 今天的真实状态。
- **为什么不跑本地全量 L2**：本次合入的增量是「合并动作本身」与「冲突解法」，
  冲突解法取的是集成分支侧，合并结果与集成分支逐字节相同
  （`api-proxy.ts` / `module-progress.md` / `api-contracts` 的 blob hash 三项全等），
  内容已由流水线推分支前的全量 L2 覆盖；PR 自己的 CI 又在合并后的树上跑过一遍完整作业矩阵。
  再在本机重跑一遍 40–60 分钟的全量 L2 是发热高峰，没有信息增量。

## 后续待办

- **vite 升到 `>= 7.3.7` 仍然欠着**（`main` 今天真的红 Security Scan）。
  它不再是合并的前置条件，但它是真实的安全债，按 backlog 文档第 1 条由开发流水线落到集成分支。
- `main` 上 2 条 CodeQL error 级告警（`js/redos` / `js/unnecessary-use-of-cat`）仍未修。
- `main` 没有 CodeQL 基线，PR 侧的「新增告警」比对目前对所有 PR 都失真。

## 关联

- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（本任务的执行协议）
- `docs/backlog/2026-10/2026-10-21-ci-baseline-vite-advisory-blocker.md`
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`
- PR：<https://github.com/sugar-hit/starcon/pull/12>
