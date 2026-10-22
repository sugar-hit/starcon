# 2026-10-22 13:05 集成头同步 main（第 26 轮 · 冲突未并入 · 3 文件 11 区 · 与第 24/25 轮同墙）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 26 轮。

**结果：冲突未并入，未推送任何合并内容。** 四选一里的「**冲突未并入**」。
集成头 HEAD 保持 `bc575ca8` 不变（合并已 `git merge --abort` 回退，`MERGE_HEAD` 已清，工作区干净）。

与第 24/25 轮相比，`origin/main` tip 未推进（仍是 `89395bc8`），`merge-base` 未变（仍是 `1bb270a0`），
领先集成头的提交数未变（32），**冲突面逐区同墙**：

| 维度                | 第 24 轮（12:47） | 第 25 轮（13:02） | 本轮（13:05） | 结论         |
| ------------------- | ----------------- | ----------------- | ------------- | ------------ |
| `origin/main` tip   | `89395bc8`        | `89395bc8`        | `89395bc8`    | 未推进       |
| `merge-base`        | `1bb270a0`        | `1bb270a0`        | `1bb270a0`    | 未变         |
| 领先集成头的提交数  | 32                | 32                | 32            | 未变         |
| 冲突文件数 / 冲突区 | 3 文件 / 11 区    | 3 文件 / 11 区    | 3 文件 / 11 区 | **逐区同墙** |
| 冲突区分布          | api-proxy 6 / contract-drift 1 / MAIN_MERGE_CRON_PROMPT 4 | 同左 | 同左 | 未变         |

⇒ **在 `origin/main` 不推进的前提下，冲突面是稳定的**（与第 25 轮结论一致）。
本轮集成头侧的改动只有 `docs/sessions/` 下的会话记录，与 main 侧无交集，故冲突区一行未变。

## 决策 / 结论

1. **闸：等了 1 小时 49 分（11:15 → 13:04:30）才拿到，一次拿到，没抢、没清、没 force。**
   - 起手 `gate status` ⇒ `held / stale:false` ⇒ 按协议走「密集 acquire 竞态」，**不抢、不清、不 force**。
   - 等待期间只做只读取证（`gate status` / `cat owner` / `ps` / `git log` / `find -newermt`），
     **未跑 `git fetch`、未跑任何构建**，避免与持闸 tick 的 L2 争抢 `backend/**/target/` 与 PG 容器。
   - 前两轮 1 秒密度的竞态循环连输两次（12:46、12:57 的交接窗口被并发轮次秒抢）；
     改为**单进程 0.2 秒密度**的竞态后，在 13:04:30 抓到交接窗口（attempt 1256）。
   - 判活只认 `gate status` 的 `stale` 字段；`owner` 里的 PID 不是存活证据（持闸者 PID 13808 全程查无此进程，
     但持闸 tick 确实在跑 L2）。
2. **冲突不自行解决。** 3 个文件全部 `git merge --abort` 回退，冲突文件名列在本轮记录里交给下一轮：
   - `apps/web/src/lib/api-proxy.ts`（6 处冲突块）
   - `docs/backlog/2026-10-20-contract-drift-ci-gate.md`（1 处，add/add）
   - `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（4 处）
3. **未跑预检、未推合并内容。** 预检（`mvnw compile` + `pnpm typecheck`）只对成功合并有意义；
   合并已回退 ⇒ 不推。
4. **台账未变**：已交付 316 / 进行中 0 / 未开发 149（总 465），`sync.mjs --check` ⇒ 台账与蓝图一致。
   本轮没有任何交付进入集成头，基线与第 25 轮相同。
5. **`merge-union` 覆盖范围**（本轮实跑确认）：契约文件（`packages/api-contracts/**` 12 个文件）
   与台账（`docs/product/capability-blueprint/module-progress.md`，按模块 ID 求并集、折叠 465 行同 ID 记录）
   走行级兜底并集并自动合上；**冲突只落在 3 个非契约/非台账文件上**。

## 等待期间观察到的并发现状（供下一轮判断）

1. **持闸 tick（10:00 那轮 batch-62）**：11:04 的 L2 重跑红在 `trade` 既有缺陷（`docs/backlog/2026-10-22-trade-replenishment-upstream-404-flake.md`），
   11:37 合并车道 `6.2.1.2` 后于 11:57 起跑第二次 L2，**12:41:25 backend `mvnw verify` BUILD SUCCESS
   （43 分 35 秒、7 模块全 SUCCESS，含 trade）**，`pnpm typecheck/lint/test` 与 `sync --check` 全 exit 0 ⇒ **L2 全绿**，
   12:43:17 结束，12:46 前后完成 push + 释放闸。
2. **同一 cron 的多轮同步并发在跑**：闸的交接窗口被连续三轮（12:46、12:57）秒抢，
   每一轮都是「acquire → merge → 3 文件冲突 → abort → 写 `docs/sessions/` 记录 → push → release」。
   本轮是第 26 轮，同墙第 3 次复现（仓库里现存 23 份 `main-sync-conflict-*` 会话记录）。
   ⇒ **根因仍在两侧对同一批文件各自改写**：`api-proxy.ts`（接口放行规则）、
   `contract-drift-ci-gate.md`（待办）、`MAIN_MERGE_CRON_PROMPT.md`（协议文件）三处，
   main 与集成头在 main 不推进时稳定同墙；**只要 main 继续推这三处，冲突面不会自行收敛**。
3. **建议（不属本轮动作）**：这三处冲突的收敛点不在同步任务侧，而在两侧内容侧；
   同步任务按协议只能 abort + 上报。

## 本轮涉及文件

- 合并消息草稿（未入库，`.tmp/` 已 gitignore）：`.tmp/sync-merge-msg.txt`
  （已列全 32 个并入提交：hash + 模块 ID + 名称 + 一句话说明，供解冲突后直接复用）
- 会话记录：`docs/sessions/2026-10/2026-10-22-1305-main-sync-conflict-11-regions-round26.md`（本文件）
- 冲突涉及但**未改动**（已 abort）：`apps/web/src/lib/api-proxy.ts`、
  `docs/backlog/2026-10-20-contract-drift-ci-gate.md`、`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`

## 验证状态

- `git status --porcelain` 为空；`MERGE_HEAD` 已清（`git merge --abort` 后复核）。
- `node tools/module-progress/sync.mjs --check` ⇒ 台账与蓝图一致。
- `node tools/module-progress/lane.mjs status` ⇒ 已交付 316 / 进行中 0 / 未开发 149。
- 未运行 `mvnw verify`（属流水线 L2）、未跑快速预检（合并已回退，无合并内容可检）。

## 后续待办

1. 下一轮同步照旧先取闸（**预期仍会撞同一个 3 文件 11 区**，`origin/main` 不推进时冲突面稳定）。
2. 冲突的真正收敛点在内容侧：接口放行规则（`api-proxy.ts`）、契约漂移待办、协议文件三处需两侧对齐。
3. 若下一轮 `origin/main` 仍停在 `89395bc8`，建议先让主分支侧处理这三处的同源改写，再重跑同步，
   否则每一轮都只会得到同一份「3 文件 11 区」结论。
