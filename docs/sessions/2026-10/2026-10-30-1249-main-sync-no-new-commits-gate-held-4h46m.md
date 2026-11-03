# 2026-10-30 12:49: 集成头同步 main（无新提交 · 调度闸被 08:00 tick 连续持有 4h46m · 竞速零命中再次复现）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 同步。

**本轮结果：无新提交，未同步** —— `origin/main` 已被集成头完整包含（待并入 **0** 个提交），
无需 merge / 预检 / push / 刷新台账。

同时如实记录：**本轮全程未取得调度闸**（闸被活着的 08:00 tick 连续持有 4h46m），
因此未执行协议第 2 步的 `git fetch`，改用 `git ls-remote`（只读网络查询，不改任何本地 ref）取证。

四个值得下轮注意的事实：

1. **闸被连续持有 4h46m，且 owner 从未换手**：`owner` 文件自 `08:01:48` 起再未被写过
   （`gate acquire` 成功必重写 owner），而 keepalive 持续续期（12:47 仍在刷新）⇒
   **一个长驻调度者跨批次连续持闸**，不是每轮重新取闸。与上一轮（10-25）的同一结论相比，
   本轮把连续时长从「近 3 小时」推到 **4h46m**，且给出更硬的证据（owner 未被重写）。
   ⇒ **闸的空闲窗口远比协议假设的「一轮 40–60 分钟」难得**，本轮整轮没出现过一次可插入窗口。

2. **竞速零命中再次复现**：本轮按协议现有写法跑了「1 秒 × 16 分钟」+「0.4 秒 × 9 分钟」两轮，
   **合计约 25 分钟零命中**（累计本轮实际等待约 35 分钟）。上一轮纪要（10-25）已记录
   2h51m + 3h46m 零命中，并给出解法：**单进程内 5ms 间隔直接 `mkdirSync`**（≈170 次/秒）一轮命中。
   ⚠️ **本轮仍未采用该写法** —— 协议文件未改（改策略是人的决定），本轮按协议现有写法执行。
   ⇒ **下一轮建议直接照办 5ms 单进程竞速**（见「后续待办」）。
   （客观说明：即便命中，本轮也只会得到 NO_UPDATE —— 结论不会变，但能省 25 分钟墙钟。）

3. **`main` 与集成头的拓扑已用远端实测核实**（不依赖本地 ref 是否滞后）：
   远端 main tip = 本地 `origin/main` ref = `b813701c` ⇒ 本地 ref **新鲜**，不是 06:21 的滞后值
   （本 worktree 上次 fetch 是 06:21:27，但期间远端 main 根本没动过）。

4. **本地集成头比远端领先 18 个 commit，堵在流水线 L2 后面**：本地 `5e6a9cb8` vs 远端 `e93fc3d3`。
   这 18 个是流水线自己的 round-130/131 会话纪要提交（不是本同步任务的产物），
   按 11:25 轮 backlog 纪要（`docs/backlog/2026-10-30-l2-red-stale-capability-assertions-after-13-6-2-1.md`），
   它们推不上去的根因是 **08:00 tick 的整批 L2 判红**（`13.6.1.1` 的 6 处过期断言），
   修复方式应是派修复车道，**不是**在集成头上直接改。本轮不介入。

## 决策 / 结论

1. **不抢闸、不清闸、不 force、不 release**：闸 `held` + `stale=false`（持闸者活着：owner 未换手 +
   keepalive 持续续期 + 已知其 L2 在跑）⇒ 按协议「等它释放」。本轮**从未 acquire 成功**，因此**没有 release** ——
   `gate release` 的实现是无条件 `rmSync(GATE_DIR)`，会把别人活着的闸删掉（= 清闸，破坏互斥）。
   闸最终状态：`held` / `ageSeconds≈4`（12:47 keepalive 刚刷新）/ `stale=false` /
   `owner` 仍是 `08:01:48`（**不是本轮留下的**）。
   ⚠️ `gate status` 的 `ageSeconds` 会被 keepalive 刷新（keepalive 文件写在 `GATE_DIR` 内 ⇒ 目录 mtime 变新），
   所以它**不是**「持闸起点」，只是一次心跳新鲜度 —— 判活仍只认 `stale` 字段。

2. **判定「无新提交」的方式**（未取闸 ⇒ 不 fetch）：协议第 2 步要求「取闸 → fetch → 判祖先」。
   本轮改用只读取证，结论等价且零副作用：

   | 取证 | 结果 |
   | --- | --- |
   | `git ls-remote origin refs/heads/main` | `b813701c` |
   | 本地 `origin/main` ref | `b813701c`（**与远端完全一致 ⇒ 本地 ref 新鲜**） |
   | `git merge-base --is-ancestor b813701c integration/module-dev` | **成立 ⇒ NO_UPDATE** |
   | `git log integration/module-dev..b813701c \| wc -l` | **0** |
   | 更强一侧：远端集成头 tip `e93fc3d3` 是否含 `b813701c` | **是** ⇒ 即便按最新集成头算，main 也无新内容 |

   ⇒ 结论**不依赖**「本地 ref 是否滞后」，也**不依赖**本轮能否取闸。

3. **不跑构建**：无并入内容 ⇒ 无需预检（`./mvnw -q compile` + `pnpm typecheck`）。
   且等闸期间跑构建会与持闸 tick 共用 `backend/**/target/` 与 PG 容器 ⇒ **必然假红**
   （协议原文：并发必假红）。

4. **不写协议文件**：上一轮已把「竞态写法改为单进程 5ms `mkdirSync`」列为「给人工裁决」，
   本轮只复现证据、**仍不改协议**（协议属策略，只存一份在版本控制里，改策略是人的决定）。

5. **不动集成头工作区**：本轮结束时集成头 worktree `git status --porcelain` **为空**（干净）。
   上一轮曾撞上持闸 tick 自己的未提交改动（`module-progress.md` 已被刷脏），本轮没有。

## 关键 trade-off

- **等约 35 分钟不抢闸**：持闸 tick 是活着的（owner 4h46m 未换手、keepalive 续期、已知 L2 在跑）。
  抢闸 ⇒ 两个构建并发跑 `backend/**/target/` 与 PG 容器 ⇒ **成片假红**。
  「宁可等，不可抢」本轮再次成立；代价是墙钟时间。
- **等闸期间只做只读取证**：未 fetch（协议明令「等闸期间连 `git fetch` 都先别跑」）、未 merge、
  未动工作区、未跑任何构建、未 push。
- **用 `ls-remote` 替代 `fetch` 做判定**：`ls-remote` 只读远端、不动本地 ref，
  等价回答「远端 main 现在是什么、集成头是否已包含它」，且在**没有闸**的前提下
  也不会给持闸 tick 制造 ref 竞态。
- **主动停掉竞速 racer**：确认 NO_UPDATE 后立刻终止竞速（避免持续抢 CPU 干扰持闸 tick 的 L2），
  并核查**无残留 racer 进程**（`ps` 复查为空）。

## 文件清单

- **未新增/未修改任何业务文件**；集成头 `integration/module-dev` 本地停在 `5e6a9cb8`，
  远端 tip `e93fc3d3`（本地领先 18 个流水线自己的提交，堵在 L2 红后面）。
  **本轮没有 merge、没有 commit、没有 push。**
- 集成头 worktree 本轮结束时**干净**（`git status --porcelain` 空），本轮未触碰。
- 主工作区仍在 `main` 分支（`e57e1497`，落后 origin/main 1265）；本轮**未 checkout 任何集成分支**、未提交任何东西。
- 本篇会话纪要落**主工作区** `docs/sessions/2026-10/`（未提交：同步任务不在 main 上提交任何东西）。
- 台账基线（本轮读取，未刷新）：模块总数 **465** / 已交付 **435** / 进行中 **0** / 未开发 **30** / 完成率 **93.5%**。
  `node tools/module-progress/sync.mjs --check` 结果「✓ 台账与蓝图一致」。

## 后续待办

1. **竞速写法（给人工裁决，建议下轮直接照办）**：`SYNC-MAIN.md` 第 1 步的等闸竞态，
   从「起 node 子进程 + `sleep 1`」改成「**单进程内 5ms 间隔直接 `mkdirSync`**」（≈170 次/秒）。
   证据链：三次会话累计 6h+ 零命中（2h51m / 3h46m / 本轮 25min+）vs 单进程一轮命中。
   ⚠️ 本轮**未**采用（协议未改，按现有写法执行）；但结论不依赖它 —— 本轮即便取到闸也是 NO_UPDATE。
2. **闸的持有粒度值得复核**（复现上一轮结论）：持闸者跨批次连续持闸 4h46m，
   导致同步任务几乎总是撞闸。可考虑让流水线在**批次之间**放闸，
   或让 `owner` 文件带「任务 kind + 批次号」，使同步任务能识别「可插入窗口」。
3. **流水线 L2 归因**：本轮**没有并入任何东西**（未 merge、未 push、未改代码），
   下一轮 L2 若红了**与本轮无关**。
   已知待办：08:00 tick 的 L2 红点 = `13.6.1.1` 的 6 处过期断言
   （`backend/services/reporting/src/test/java/com/starcon/reporting/CustomReportIntegrationTest.java`），
   须**派修复车道**（`fix/13-6-1-1-capability-assertion`），**不要**在集成头上直接改。
   该修复是本地 18 个 commit 推不上去的直接原因。
4. **本轮无需补救动作**：main 无新内容。下轮同步若 main 出现新提交，按协议正常走
   merge（正文逐条列出并入的 main 提交）→ 快速预检 → 绿则刷新台账并 push 集成头。
