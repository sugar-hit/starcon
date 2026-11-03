# 2026-10-26: 集成头同步 main 第 76 轮 —— 无新提交，未同步；闸被「已崩 tick 的孤儿 keeper」永久锁住

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → 集成头 `integration/module-dev` 同步。

**本轮结果：无新提交，未同步**（待并入 **0** 个提交）。同时如实记录一个**系统性阻塞**：

> **调度闸被 batch-99 流水线 tick 的「孤儿 keepalive keeper」永久持有 —— 持闸者本身已经崩了，
> 但 keeper 仍在每 240 秒刷新闸目录 mtime，导致协议的「持闸者已崩 ⇒ `stale=true` ⇒ 接管」路径完全不可达。**

本轮**从未 `gate acquire` 成功**（密集竞态约 67 分钟零命中）⇒ 按协议**没有 release**，
**没有 fetch / 没有 merge / 没有预检 / 没有 push / 没有改集成头**。

## 决策 / 结论

1. **NO_UPDATE 判定（只读取证，未 fetch）**
   - `git ls-remote origin refs/heads/main` = `6522c4976ed8959f20ab3037419d7074d596297a`
   - 本地 `origin/main` ref = `6522c497` ⇒ **与远端完全一致 ⇒ 本地 ref 新鲜，不是滞后值**
   - `git merge-base --is-ancestor origin/main integration/module-dev` = **成立（exit 0）**
   - `git rev-list --count integration/module-dev..origin/main` = **0**
   - ⇒ **main 无新提交**，本轮即使拿到闸也没有 merge 目标、无需预检。
   - 补充：`main` 自 2026-10-23 09:18 起静止（已连续 10 轮同一 tip），本轮是第 10 次 NO_UPDATE。

2. **闸的真实状态：持闸者已崩，但闸被孤儿 keeper 锁死**
   - `gate status` = `held` / `stale=false` / `ageSeconds` 随 keepalive 不断归零。
   - **持闸者（batch-99 流水线 tick）已崩**：
     - 流水线会话 `mvs_4d671d43ac894059997be16ae3ba0e70`（`starcon 并行车道功能开发流水线`，
       2026-10-26 00:00:00 +0800 启动）状态 = **error：`net::ERR_HTTP2_PING_FAILED`（50001, agent-runtime）**，
       崩于 **00:48:46**；
     - `owner` 里的 pid `31958` 查无此进程；
     - `ps` 无任何 `mvnw` / `surefirebooter` 存活；
     - `.dev-agent/lanes.json` 里 **210 条车道全部 `merged`，无 running/dispatched**；
     - batch-99 会话纪要 `2026-10-26-batch-99-merge-two-lanes-l2-and-dispatch.md` 最后写于 **00:15**，
       里面「L2 批次闸 ⏳ 见第六节」仍是待办 ⇒ **它死在 L2 中途**。
   - **但闸没释放**：`bash .dev-agent/keepalive-r99.sh`（pid `32549`）**PPID=1（被 launchd 收养 = 孤儿）**，
     脚本循环体是 `while [ ! -f .dev-agent/STOP-KEEPALIVE-R99 ]; do touch <新文件>; sleep 240; done`
     ⇒ 它每 4 分钟新建一个文件，**持续刷新闸目录 mtime**。
   - **为什么这会锁死接管路径**（读 `lane.mjs:568-588` 取证）：
     `gateAcquire()` 走 `mkdirSync(GATE_DIR)` 失败分支后，用 `age = (now - statSync(GATE_DIR).mtimeMs)/1000`
     判陈旧，**只有 `age > ttlSeconds`(4200s=70min) 才 `rmSync`+`mkdirSync` 接管**；
     `gate status` 用的是同一个 mtime 指标。孤儿 keeper 让 mtime 永远新鲜
     ⇒ **任何密度（含协议建议的 1 秒竞态、乃至 5ms 单进程竞态）都不可能 acquire 成功**。
   - ⇒ 这是协议「持闸者已崩 ⇒ 可接管」判据的一个**未覆盖的失效模式**：**崩溃的 tick 留下孤儿 keeper，
     把 TTL 判据永久压制成「永远不陈旧」**。

3. **不抢闸、不清闸、不 force、不 release**
   - 竞态取证：`sleep 1` 间隔的 `gate acquire` 竞态跑满 **3600 次 / 约 61 分钟**（后台任务被 3700s 上限截断），
     随后又起一轮并在取证完成后**主动取消**（避免残留 racer 持续抢 CPU 干扰持闸者的 L2、
     以及避免本轮退出后留下「无人管理的持闸者」）⇒ 两轮**零命中**。
   - `ps` 复核：无残留 `gate acquire` / `lane.mjs` 进程。
   - **没有 release**：本轮从未 acquire 成功，而 `gateRelease()` 是无条件 `rmSync(GATE_DIR)`
     ⇒ 会把（崩溃 tick 的）闸目录删掉 = 清闸，破坏互斥。
   - 闸最终状态：`held` / `stale=false` / `owner` 仍是 `31958` / `2026-10-25T16:00:48.193Z`（**不是本轮留下的**）。

4. **不跑预检、不跑 L2**：无并入内容 ⇒ 无预检对象；且构建会与持闸者的 L2 共用
   `backend/**/target/` 与 Testcontainers PG ⇒ **并发必假红**。全程未执行任何 `mvnw` / `pnpm` 构建命令。

5. **不在脏工作区动手**：集成头 worktree 有**崩溃 tick 自己的未提交改动**，本轮未触碰：
   - `M docs/product/capability-blueprint/module-progress.md` —— 已刷到 **已交付 408 / 进行中 0 / 未开发 57（87.7%）**，
     HEAD 基线是 **407 / 0 / 58（87.5%）**；
   - `?? docs/sessions/2026-10/2026-10-26-batch-99-merge-two-lanes-l2-and-dispatch.md`（写到一半的纪要）。
   - ⚠️ 协议第 3 步要求 `git status --porcelain` 为空才能 merge ⇒ **即便闸空出来，也应先由人/下一轮 tick
     收尾这批改动**，再动集成头。

6. **不 push（关键）**：本地集成头 `6b8747e9` 比远端 `1e3228b4` **领先 15 个提交**
   （1.1.2.1 核心指标卡、1.3.2.1 预警规则两条车道的全部落地提交 + 两个 merge commit），
   **但这批代码的 L2 从未跑完**（tick 崩在 L2 中途）⇒
   **推上去等于把「没过 L2 的集成头」发布出去**，违反协议「不要把编译不过的集成头推上去」。
   ⇒ 本轮**没有 push**。

## 关键 trade-off

- **等 67 分钟零命中 vs 直接接管**：拿不到闸 ⇒ 不能 fetch / 不能改集成头 / 不能 push。
  代价只是墙钟时间；抢闸则会造成两个构建并发 + 破坏互斥。**「宁可等，不可抢」本轮再次成立**。
  ⚠️ 但本轮与 2026-10-25 那轮**性质不同**：那轮持闸者是活着的（keepalive 续期 + L2 在跑 + 仍在提交），
  本轮持闸者**已崩**、keeper 是孤儿 ⇒ 继续等下去**永远等不到释放**，等的是一把永远不会开的锁。
- **用 `ls-remote` 替代 `fetch` 做判定**：`ls-remote` 只读远端、不动本地 ref，
  等价回答「远端 main 现在是什么、集成头是否已包含它」，且在**没有闸**的前提下
  也不会给持闸 tick 制造 ref 竞态。
- **主动取消竞态 racer**：确认 NO_UPDATE + 取证完成后立刻终止，并 `ps` 复核无残留 ——
  否则本轮退出后 racer 仍可能在无人管理的状态下抢到闸并永久锁住流水线。
- **不代崩溃 tick 提交**：那 15 个未推送提交、脏台账、写到一半的纪要都属于流水线批次，
  本任务「只搬运 commit，不改业务代码」，**不代其收尾、不代其 push**。
- **不改协议文件**：本轮发现的是协议判据的**失效模式**（孤儿 keeper 压制 TTL），
  修协议属策略决定，**留给人工裁决**（见「后续待办」）。

## 文件清单

- **本任务未新增/未修改任何集成头文件**；集成头 `integration/module-dev` 本地停在 `6b8747e9`、
  远端 `1e3228b4`（差 15 个提交，属流水线批次）。**本轮没有 merge、没有 commit、没有 push、没有 fetch。**
- 集成头 worktree 内的脏状态（`M module-progress.md` + 未跟踪 batch-99 纪要）**属于崩溃的 tick**，本轮未触碰。
- 主工作区仍在 `main` 分支；本轮**未 checkout 任何集成分支**、未提交任何东西。
- 本篇会话纪要落**主工作区** `docs/sessions/2026-10/`（未提交：同步任务不在 main 上提交任何东西）。

## 后续待办（需人工裁决 / 人工执行）

1. **解锁调度闸（阻塞全流水线，最高优先）** —— 让孤儿 keeper 自己优雅退出，再等 TTL 自然到期：
   ```bash
   touch /Users/guan/Projects/starcon-worktrees/integration/.dev-agent/STOP-KEEPALIVE-R99
   ```
   keeper 在下一个 240s 循环退出 ⇒ 闸目录 mtime 冻结 ⇒ **70 分钟后 `gate status` 报 `stale=true`**
   ⇒ 下一个实例的 `gate acquire` 走**协议既有**的接管路径（`lane.mjs` 的 age>TTL 分支），
   **无需手工 `rm -rf`**。
   ⚠️ 更快的替代：`rm -rf .dev-agent/dispatch.gate`（立即清闸），但那是**手工清闸**，
   需人工确认 tick 已崩后再做（本轮已取证 tick 已崩）。
2. **协议修订建议（给人工裁决，本轮未改）**：`SYNC-MAIN.md` 第 1 步的判据补一条
   **「持有者会话已崩但 keeper 仍在续期 ⇒ 闸实为僵尸锁」**的分支，并给出处置口径
   （建议：keeper 自带 `STOP-KEEPALIVE-R99` 标记应作为 tick 崩溃时的兜底，
   或让 keeper 检测父进程存活（`kill -0 $PPID`）后自行退出，避免孤儿续期）。
   另：等闸竞态写法建议改为**单进程内 5ms 间隔 `mkdirSync`**（历史纪要已有同样建议）。
3. **下一轮流水线需收尾 batch-99 的残留**（闸解锁后）：
   - 本地 15 个未推送提交（1.1.2.1 核心指标卡 / 1.3.2.1 预警规则）需要**重跑 L2** 后再 push；
   - `trade` 的 `BomBindingIntegrationTest` 22 例报错已定性为**环境问题**
     （`PSQLException: 尝试连线已失败` → `SocketTimeoutException: Read timed out`，
     即 Testcontainers PG 未就绪/端口争用；trade 其余 2458 例通过，
     catalog 1998 / channel 2322 / crm 491 / finance 970 / identity 619 / platform 97 /
     reporting 69 / warehouse 1672 全绿）⇒ **重跑一次即可定性，不属代码缺陷**；
   - 脏台账（408/0/57）与写到一半的 batch-99 纪要需要一并提交收口。
4. **本轮无需补救动作**：main 无新内容。下一轮同步若 main 出现新提交，按协议正常走
   merge（正文逐条列出并入的 main 提交）→ 快速预检 → 绿则刷新台账并 push 集成头。
5. **L2 归因**：本轮**没有并入任何东西**（未 merge、未 push、未改代码），
   后续 L2 若红了，**首要排查对象是 batch-99 那 15 个未推送提交**，不是本轮。
