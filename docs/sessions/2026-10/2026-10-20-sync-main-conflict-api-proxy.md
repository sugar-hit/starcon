# 2026-10-20: 集成头同步 main（冲突未并入 · api-proxy.ts）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 的同步。
本轮**结果：冲突未并入** —— 合并在 `apps/web/src/lib/api-proxy.ts` 上真冲突，按协议
`git merge --abort` 后如实上报，**未自行解冲突、未 push**。

两个关键发现改变了本轮的执行方式：

1. **`origin/main` 新增的 1 个提交对集成头的内容净增为 0**。
   `d28188d6`（PR #11，20:39）是集成分支在 `d22bc1e1` 上的 **squash 合并**，
   它的 tree 与 `origin/integration/module-dev` 完全一致 —— 也就是说集成头早就
   包含了 main 的全部内容，这次 merge 只是补一段祖先关系。
2. **20:00 那个 tick 其实没死**。本轮 21:16 看到闸 `stale: true`（age 5657s > TTL 4200s）
   便按协议接管了闸；但该 tick 一直在活着干活：22:10 提交了 `docs(batch-38)`，
   22:09 起正在**同一 worktree** 跑全量 L2（`./mvnw verify`，至今未结束）。
   ⇒ 本轮**没有**在集成头 worktree 上落合并、**没有**跑任何构建（并发构建/写工作区会污染
   它的 `backend/**/target/`、PG 容器与后续前端 typecheck），冲突改用
   `git merge-tree --write-tree` 只读预演 + 临时 detached worktree 实合并复现（探针已删除）。

## 决策 / 结论

- **冲突只在一个文件，且成因是 squash 合并丢了祖先**：
  merge base 停在旧 main `1bb270a0`，diff3 于是看到两侧在 `api-proxy.ts` 的
  `services[]` 同一位置各加了一段 ⇒ 判冲突。实际 ours（集成头）= theirs（main）+ 一条
  10.7.1.3 的 `return-disposals` 白名单元素，**ours 是 theirs 的严格超集**，
  正确解法是保留 ours / 把该元素并到 main 侧（不是二选一）。
  按 SYNC-MAIN.md「❌ 自行解冲突」，本轮只报不改。
- **合并驱动只覆盖契约/台账两类文件**，其余源码没有驱动 —— `api-proxy.ts` 正是
  MAIN_MERGE_CRON_PROMPT §4.2 点名的「必然真冲突、必须手工求并」文件。
- **`channel.openapi.json` 走了行级兜底并集**（驱动自报
  `走了行级兜底并集，请人工确认这个文件改对了`）⇒ 人工解冲突时必须逐行核对，
  不能默认它是对的。
- **闸的 `stale` 判据会把活着的 tick 判成崩的**。该 tick 19:42 取闸，
  之后 94 分钟闸 mtime 没刷新（PIPELINE.md 写的是每 25 分钟心跳、90 分钟失联判崩），
  但它实际在跑 L2 + 写文档。活 tick 不复查闸 ⇒ **互斥在这一轮实际被破坏**。
  TTL 4200s（70 分钟）小于「一次 L2 + 写文档」的真实时长是根因之一。
- **拿到过闸才 release**：本轮确实 acquire 成功过（owner pid `73739`，21:44:33），
  22:21:14 正常 release → `gate: free`。
- **本轮不 push**：合并没落地，且集成头上那 2 个未推提交
  （`4983a9cc` merge 10.7.1.3 / `c8ee4345` docs(batch-38)）正由活 tick 的 L2 背书，
  该由流水线 L2 出结果后统一 push，本任务不代劳。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | **冲突未并入**（`apps/web/src/lib/api-proxy.ts`） |
| `origin/main` HEAD | `d28188d6` Integration/module dev (#11)（20:39:44） |
| 待并入 main 提交数 | 1（内容净增 0 —— tree 与 `origin/integration/module-dev` 一致） |
| merge base | `1bb270a0`（PR #11 是 squash 合并，共同祖先停在旧 main） |
| 冲突文件 | `apps/web/src/lib/api-proxy.ts`（唯一 1 个，第 3765–3831 行，67 行冲突区） |
| 自动合并成功 | `module-progress.md`（台账求并）、`channel.openapi.json`（⚠ 行级兜底并集）、`contracts/channel.ts` |
| 合并 commit | 无（abort 后未落） |
| 预检 | 未跑（无合并结果可检；且并发构建与活 tick 的 L2 必假红） |
| push | 未 push（合并未落地） |
| 集成头 HEAD | `c8ee4345` docs(batch-38): 10.7.1.3（活 tick 22:10 提交，本轮未推） |
| 台账（同步前后无变化） | 已交付 243 / 进行中 0 / 未开发 222（共 465，完成率 52.3%） |
| 台账校验 | 465 行、无重号、`sync.mjs --check` ✓ 一致 |
| 闸 | 21:16 判 stale → 21:44 acquire → 22:21:14 release（`free`） |
| 活 tick | 20:00 tick 仍活着，22:09 起在同 worktree 跑 `mvnw verify`（L2 未结束） |

## 下一轮解法（给人工 / 00:00 的 main 并入任务）

1. `apps/web/src/lib/api-proxy.ts` 的冲突按 MAIN_MERGE_CRON_PROMPT §4.2 求并：
   两层 alternation 都要并（不是取一边），并跑 `apps/web/src/app/api/[...path]/route.test.ts`
   的真 proxy 断言（含反向用例）—— 白名单错一条就是静默 404。
2. `packages/api-contracts/src/channel.openapi.json` 是行级兜底并集，解完逐行核对。
3. main 侧含 10.7.1.3 后，本任务下一轮的 merge base 变成 main 的新提交 ⇒ 冲突消失，
   合并会变干净，预检 + push 即可正常走完。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` — §4.2 api-proxy.ts 求并纪律（供下一轮参照）
- `tools/module-progress/PIPELINE.md` — 闸心跳 / L2 顺序 / 台账完整性要求
- `tools/module-progress/lane.mjs` — `gate acquire` / `status` / `release` 语义；`merge-ready` 要求工作区全干净
- `apps/web/src/lib/api-proxy.ts` — 本轮唯一冲突文件（累积型白名单）
- `docs/product/capability-blueprint/module-progress.md` — 台账（243 / 0 / 222，`sync --check` 绿）
