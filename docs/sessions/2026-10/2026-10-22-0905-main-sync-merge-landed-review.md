# 2026-10-22: 集成分支并入 main —— 同步 PR 落地复核与零变更确认

## 摘要

本轮接管上一轮留下的同步 PR（广告与增长系统、客户与客服系统共 15 项能力交付）。
闸工具判定「可以合并」之后，该 PR 在本轮进行中被并发会话合入 `main`，
落地为单条 squash 提交 `9da98852`（父提交 `a105acf0`，合并时刻 2026-10-22T01:03:47Z）。

本轮的工作因此从「发起合并」转为「复核落地结果 + 判定是否还有增量」：
逐项核对了落地内容（契约语义等价、台账 465 行且编号无重复、
接口放行规则的 170 个测试文件 1235 个用例全绿、白名单按工具证据取集成分支侧、
协议文件按行级证据取 main 侧），
最后用真实合并加 `git write-tree` 证明**再合一次是零变更** ⇒ 本轮不开新的同步 PR。

## 决策 / 结论

1. **并发会话可以在本轮进行中完成合并 —— 远端跟踪 ref「消失」是成功信号，不是故障。**
   PR 合并后远端分支被删除，`git rev-parse upstream/<pr-branch>` 立刻报 `bad revision`，
   症状看起来像 ref 损坏，实际是合并已经成功。
   判据：`gh pr view` 显示 state=MERGED，且 `upstream/main` 已指向合并提交。
   ⇒ 中途遇到「ref 解析失败」时，**先查 PR 状态，再查 ref**。

2. **静态预检要读成「相对 main 基线的增量」，不是绝对命中数。**
   仓库级绝对命中（`ADD COLUMN` 160 / `SET NOT NULL` 43 / `ADD CONSTRAINT CHECK` 21）全是存量债。
   本轮 PR 分支的命中是 164 / 43 / 21，+4 来自新增迁移文件。
   逐条核对唯一一条无 DEFAULT 的新增列（广告组管理的 `local_changed_at timestamptz`）：
   **可空、无 NOT NULL、无回填、无后续 SET NOT NULL**，加在既有表上 ⇒ 不属于危险形状。

3. **协议文件（合并任务书）的解法固定为「取 main 侧」。**
   判据工具给出的是保守警报 `[并入（人工）]`，需要用行级证据裁决：
   集成分支侧独有行只有 5–6 行，且**全部是 main 侧新版措辞替换掉的旧行**
   （旧的「无新内容」判定命令 + 旧表格三行）；main 侧独有 36 行（落地树哈希判据章节）。
   ⇒ main 侧是更新的快照，取它零丢失。

4. **契约文件的判据仍是语义等价。**
   5 个 TS 契约的导出名集合双向差集为 0（863 / 1348 / 76 / 1717 / 1077），
   5 个 OpenAPI 的递归叶子键集合双向差集为 0（18481 / 33661 / 1558 / 34270 / 20052）
   ⇒ 两侧语义等价，字节差异只是排版。

5. **闸工具「明细不可比」的三个作业补了更强的证据层。**
   `Catalog Browser & Contract Tests` / `Lint` / `Security Scan` 只比对了作业粒度。
   本轮补查：PR 分支与 main 的 `pnpm-lock.yaml` blob **逐字节相同**，且本次改动不碰任何依赖清单
   ⇒ 审计条目数的差异只能来自 registry 在两次 run 之间新增 advisory，不是本次改动引入。

6. **落地树判据在本轮再次成立。**
   合并落地后重跑真实合并并解完唯一冲突：
   `git write-tree` 与 `git rev-parse upstream/main^{tree}` 完全相同（`47fb47a0`），
   `git diff --cached upstream/main --stat` 为空 ⇒ 零变更，本轮不开新的同步 PR。

## 关键 trade-off / 讨论

- **为什么不因为「本轮有实质产出」就再开一个同步 PR？**
  因为落地树证明再合一次 `main` 一个字节都不会变 ⇒ 那会是一个空 PR，
  只会给 `main` 添一条无内容提交。本轮的产出放在会话记录上。

- **为什么明知合并由并发会话完成，仍要把复核做完？**
  squash 合并的落地内容需要独立验证：合并提交是单父提交（squash），
  它的树哈希必须与 PR 头逐字节相同，才能证明合并没有丢内容。
  本轮核对：`9da98852^{tree}` == `59a84c98^{tree}` == `47fb47a0` ⇒ 未丢内容。

- **闸工具报「可以合并」但合并其实已被别人完成时，以什么为准？**
  以 PR 状态与 `upstream/main` 指针为准，而不是以本地 ref 是否可解析为准。

- **新增迁移里那条无 DEFAULT 的列要不要拦？**
  拦的依据是「加在既有表上的列会破坏既有写入路径」这一类**危险形状**。
  可空列加在既有表上，既有行取到 NULL，不破坏任何写入路径 ⇒ 放行。
  判据要看**列的可空性与后续约束**，不能只看「有没有 DEFAULT」这一条 grep。

## 涉及文件

- `apps/web/src/lib/api-proxy.ts` — 接口放行名单按工具证据取集成分支侧（条目 125 → 139）
- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` — 协议文件按行级证据取 main 侧；
  本轮另把本轮验证出的判定依据补进该文件（并发合并的识别、静态预检读成相对基线的增量、
  白名单真实测试面的路径与跑法、判定工具保守警报的裁决配方、基线未跑完导致的假阻塞）
- `packages/api-contracts/src/contracts/*.ts` 与 `packages/api-contracts/src/*.openapi.json` — 契约求并后语义等价
- `docs/product/capability-blueprint/module-progress.md` — 台账 465 行、模块编号无重复
- `docs/sessions/2026-10/2026-10-22-0905-main-sync-merge-landed-review.md` — 本记录

## 后续待办

- 合并后 `main` 的 CI 基线 run（`39703470948`）本轮观察时仍在跑，
  随后已跑完，结论为失败（失败作业集合与合并前基线一致）。
  下一轮做基线比对时仍要先确认基线 run 已经 completed ——
  还在跑的基线会被闸工具读成「无失败」，从而把同样的失败误判成「新增失败」。
  这条陷阱已写进协议第 7.3 节。
- 集成分支若继续前进，下一轮照常走「落地树判据 → 有增量才开同步 PR」。

## 关联

- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` — 第 3 节落地树哈希判据、第 4 节冲突处理、第 7 节合并执行
- `docs/sessions/2026-10/2026-10-22-main-sync-zero-change-merge.md` — 上一轮对零变更判据的修正
- `docs/product/capability-blueprint/module-progress.md` — 功能进度台账
