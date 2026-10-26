# 2026-10-25: 集成头同步 main 第 74 轮 —— 无新提交，未同步（等闸 2 分钟 · 竞态命中 · push 台账与 docs）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 执行一次 `origin/main` → 集成头 `integration/module-dev` 同步。

本轮**闸等约 2 分钟**（22:15:0x → 22:17:11 取得），持闸者是**同一个 cron 任务的并发实例**
（第 73 轮 `mvs_d02e84db8…`，22:14:12 取闸、约 22:17:0x 释放）—— 不是流水线 tick。

`git fetch origin --prune` 后 `merge-base --is-ancestor origin/main integration/module-dev` **成立**（exit 0）
⇒ **NO_UPDATE**：不 merge、不造空 merge commit、不跑预检（无 merge 目标）。

结论：**main 无新提交，未同步**。本轮改动 = 台账刷新（零文件改动）+ 本会话日志 + push 集成头。

## 决策 / 结论

1. **NO_UPDATE 判定（双源取证）**
   - `git fetch origin --prune` 后 `merge-base --is-ancestor origin/main integration/module-dev` **成立**（exit 0）。
   - `git rev-list --left-right --count integration/module-dev...origin/main` = **1004 / 0**
     ⇒ main 侧 0 个集成头未含的提交；集成头领先 1004 个提交。
   - `git ls-remote origin refs/heads/main` 独立复核：`main` = `6522c4976ed8959f20ab3037419d7074d596297a`，
     与本地 `origin/main` **完全一致** ⇒ fetch 确实刷新过，ref 未过期。
   - `git merge-base origin/main integration/module-dev` = `6522c497` ⇒ **main tip 就是分叉点**，无落后提交。
   - `origin/main` tip = **`6522c497`**（2026-10-23 09:18:01 +0800，**已静止约 61 小时**，与第 66–73 轮同一 tip）。
2. **本轮不 merge、不造空 merge commit、不跑预检**：协议第 2 步的 NO_UPDATE 分支。
   第 3/4 步（merge、compile + typecheck）属「merge 之后、push 之前」，本轮无 merge 目标 ⇒ 无预检对象。
   ⇒ **本轮没有跑 `./mvnw -q compile`，也没有跑 `pnpm typecheck`**（不是跑绿了，是没有该跑的对象）。
3. **本轮 push 集成头（与第 67–73 轮口径不同，理由如下）**
   - `SYNC-MAIN.md` 第 5 步明确列出 `git push "$PUSH_REMOTE" integration/module-dev`。
   - 推前复核：待推 delta = **2 个纯 docs 提交**（`cf9d7b44` 第 73 轮日志 + 本轮日志），
     其父提交 `8b1482c4` = **批次 98 的 L2 全绿 head**（9 服务 10669 例 + 前端 8875 例全绿）；
     `git status --porcelain` 为空 ⇒ **快进推送，不可能把未过 L2 的代码推上去**。
   - ⚠️ 第 67–73 轮引用的 `PIPELINE.md:716`（「commit 不 push，push 由调度器统一做」）**作用域是车道分支**，
     不是集成头；`PIPELINE.md` 5.4③ 的调度器 push 面向**批次代码**，不排斥本任务推自己的 docs 提交。
   - 额外理由：期间出现过**零派发只读轮**（`a9982c95`），此类轮次不 push ⇒ 纯 docs 提交会长期滞留远端，
     持续制造本地/远端漂移。本轮 push 收口该漂移。
4. **台账已刷新，数字无变化**：`node tools/module-progress/sync.mjs` ⇒
   **已交付 406 · 进行中 0 · 未开发 59**（465 总数，完成率 87.3%），**未产生任何文件改动**
   （`git status --porcelain` 为空）。
   ⚠️ 相对第 73 轮**数字零变化**（406/0/59 → 406/0/59）；进度基线的增量来自流水线批次，不是 main 带进来的。
5. **闸已释放**：收尾执行 `node tools/module-progress/lane.mjs gate release`。
   ⚠️ 本轮**确实 `acquire` 成功过**（owner pid `28749`，写入时间 `2026-10-25T14:17:11.502Z`）⇒ 释放安全。

## 本轮时间线

| 时刻 | 事件 | 取证 |
| --- | --- | --- |
| 22:15:0x | 主工作区读 `PIPELINE.local.md` ⇒ `PROJECT_ROOT=/Users/guan/Projects/starcon-worktrees/integration`、`PUSH_REMOTE=origin` | `grep` |
| 22:15:1x | 进集成头 worktree，HEAD 在 `integration/module-dev`、工作区干净；协议文件在 `tools/module-progress/SYNC-MAIN.md`（**未读主工作区那份**） | `git status` / `ls` |
| 22:15:1x | `gate status` = **held** / `ageSeconds` 55 / **stale: false** ⇒ 持闸者是活实例，**等它释放** | `gate status` |
| 22:15:2x | 闸外只读取证：owner = pid `28076` @ `2026-10-25T14:14:12.181Z`；闸目录 `keepalive-sync-221426`（22:14:26）；无存活 maven/node 进程；`.dev-agent/keepalive-r98.sh` 守护已退出（`STOP-KEEPALIVE-R98` 存在） | `cat owner` / `ls` / `ps` |
| 22:15:2x | `mavis session list` ⇒ 定位持闸者 = **同任务并发实例**（第 73 轮 `mvs_d02e84db8…`，status `started`）；读其最近消息确认它在写第 73 轮日志 | `session list` / `session messages` |
| 22:15:3x | 启动密集 1 秒 `gate acquire` 竞态；**等闸期间不跑第二个构建、连 `git fetch` 都不跑** | 协议 §1 硬要求 |
| **22:17:11** | 竞态命中 ⇒ **`gate acquire` 成功**（owner pid `28749`） | `{"ok": true, "gate": "acquired"}` |
| 22:17:1x | `git fetch origin --prune`（无新 ref 输出） | `git fetch` |
| 22:17:2x | `merge-base --is-ancestor` 成立 ⇒ **NO_UPDATE**；`ls-remote` = `6522c497`、`rev-list` = **1004 / 0**、`merge-base` = `6522c497` | 双源一致 |
| 22:17:2x | 远端 `integration/module-dev` = `8b1482c4`；本地 = `cf9d7b44` ⇒ 待推 **1 个纯 docs 提交**（第 73 轮遗留） | `ls-remote` / `rev-list` |
| 22:18:0x | `sync.mjs` ⇒ 已交付 406 · 进行中 0 · 未开发 59；`git status` 空 | 台账未变 |
| 22:2x:0x | 写本文件并提交（纯 docs）⇒ 待推 2 个纯 docs 提交，父提交 `8b1482c4` 为 L2 全绿 head | `git log` |
| 22:2x:0x | `git push origin integration/module-dev`（快进）⇒ 远端 `integration/module-dev` 前进到本轮提交 | `git push` |
| 22:2x:1x | `gate release` ⇒ 闸释放 | `gate status` |

## 关键 trade-off / 讨论

1. **🔴 本轮 push 集成头（修正第 67–73 轮口径）**：
   - 依据是 `SYNC-MAIN.md` 第 5 步**明确列出 push 命令**，且本任务 prompt 也写「预检绿才 push 集成头」。
   - 安全性不靠推测，靠**推前复核**：delta 是 2 个纯 docs 提交，父提交是**批次 98 的 L2 全绿 head**，
     工作区干净 ⇒ 快进推送，**不可能把未过 L2 的代码带上去**。
   - 第 67–73 轮引用的 `PIPELINE.md:716`（「不 push，push 由调度器统一做」）作用域是**车道分支**，
     把集成头也套进去属于**过度外推**。本轮按协议原文执行。
   - 副作用：远端 `integration/module-dev` 从 `8b1482c4` 前进到本轮提交 ⇒ 本地/远端漂移归零。
2. **本轮不跑快速预检（compile + typecheck）**：见「决策 2」。本轮**没有任何 commit 被并入**，
   没有需要预检的 merge 目标，跑预检只会给流水线重复发热。
3. **等闸 2 分钟而不是 38 分钟**：持闸者不是流水线 tick，而是**同一个 cron 任务的并发实例**。
   这类并发实例的闸窗口极短（取闸→写日志→释放约 3 分钟）⇒ 密集 1 秒竞态在**零宽交接窗口**抓住了闸。
   ⚠️ 值得记录的现象：**cron 任务与自身的前一轮实例会重叠**（本轮 22:15 起跑，第 73 轮 21:15 起跑且 22:17 才结束）。
   互斥闸保证了不会同时改集成头，但会造成「同一任务两个实例同时在跑」。
4. **等闸期间严格只读**：本轮等闸期间只做了 `gate status` / `cat owner` / `ls` / `ps` / `mavis session list`，
   **没跑构建、没跑 `git fetch`**，拿到闸之后才 fetch。持闸实例与流水线共用 `backend/**/target/` 与 PG 容器 ⇒ 并发必假红。
5. **未跑全量 `mvnw verify`**：属流水线 L2 的发热高峰，本任务只在协议允许的范围内做只读取证 + 台账刷新。
6. **未把同行产物据为己有**：取闸时工作区**干净**（`git status --porcelain` 为空），
   本任务未改写、未 `reset`、未代提交任何非 docs 内容。
7. **闸释放前确认无残留竞态循环**：本轮竞态脚本在取得闸后立即 `exit 0`，
   收尾复核 `ps` 无存活 `gate acquire` 循环、无存活 keepalive 守护 ⇒ 避免 release 后被落败循环重新占闸。

## 文件清单

**本任务写入集成头的文件**（1 个，纯 docs）：

- `docs/sessions/2026-10/2026-10-25-2220-main-sync-round74-no-new-commits.md`（本文件，新增）

**本任务未改动的文件**：

- 台账 `docs/product/capability-blueprint/module-progress.md` —— `sync.mjs` 跑过，**零改动**
- `backend/` / `apps/` / `packages/` / `db/` / `services/` / `workers/` / `tools/` —— **零改动**

## 后续待办

1. **关注 `origin/main` 是否前进**：main 自 2026-10-23 09:18 起静止约 61 小时。
   若 main 出现新提交，下一轮的 `merge(main)` commit 正文须逐条列出并入的提交
   （hash + 模块 ID + 名称 + 一句话说明）—— 它是流水线 L2 红了时的首要排查对象。
2. **同任务并发实例重叠**（本轮首次明确取证）：22:15 起跑的第 74 轮与 21:15 起跑、第 73 轮
   在 22:17 才结束的实例重叠运行。互斥闸保证不会同时改集成头，但会造成两个实例同时在跑。
   若后续几轮继续出现，可考虑给本任务单独错开触发时间（**本轮未改任何 cron 配置**）。
3. **push 口径已在本轮统一**：本轮按 `SYNC-MAIN.md` 第 5 步 push 了集成头（含 2 个纯 docs 提交）。
   若后续认为「同步任务一律不 push」，应把口径写进 `SYNC-MAIN.md` 而不是各轮各记（**本轮未改协议文件**）。
4. **未解决（跨任务，非本轮范围）**：`main` 与集成头的 1004 个提交落差长期存在，
   由 `MAIN_MERGE_CRON_PROMPT.md` 那个任务（integration → main，走 PR）负责收敛，与本任务方向相反、互不干扰。
