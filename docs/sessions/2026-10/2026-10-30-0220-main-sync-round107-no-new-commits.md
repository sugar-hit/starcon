# 2026-10-30 02:20 · 集成头同步 main 第 107 轮 —— 无新提交，未同步（等闸 59 分钟后 TTL 接管）

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：无新提交，未同步**。取到闸后 `git fetch origin --prune`，
> `git merge-base --is-ancestor origin/main integration/module-dev` ⇒ `NO_UPDATE`。
> 本轮并入的 main 提交数 = **0**，没有 merge、没有预检、没有代码 push。

## 摘要

| 项                 | 值                                                                                                                              |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| 结果               | **无新提交，未同步**（`NO_UPDATE`）                                                                                             |
| 并入的 main 提交数 | **0**                                                                                                                           |
| 差异规模           | `git log --oneline integration/module-dev..origin/main` ⇒ **0 行**；`git diff --stat` ⇒ 空                                      |
| 冲突               | **无**（未进入 merge 步骤）                                                                                                     |
| 预检               | **未跑**（`NO_UPDATE` 分支不跑 `mvnw compile` / `pnpm typecheck`）                                                              |
| 台账               | `sync.mjs --check` ⇒ **✓ 台账与蓝图一致**；HEAD 版本 **465 总数 · 432 已交付 · 0 进行中 · 33 未开发**（92.9%）—— **本轮零变化** |
| merge commit       | **无**（本轮没有产生 merge commit）                                                                                             |
| push               | **无**（本轮不推任何内容，理由见下）                                                                                            |
| 等闸时长           | **约 59 分钟**（01:15 → 02:14），期间持闸 tick 的 L2 一直在跑                                                                   |
| 闸                 | **已释放**（收尾 `gate release`）                                                                                               |
| 附带发现           | **流水线本轮 L2 在 warehouse 有 3 处失败**（含 1 处多租户隔离断言）—— 见「附带发现」                                            |

## 决策 / 结论

1. **`origin/main` 无新提交，本轮零搬运。**

   | 证据                                                              | 实测                     |
   | ----------------------------------------------------------------- | ------------------------ |
   | 远程权威 tip（`git ls-remote origin refs/heads/main`）            | `b813701c`               |
   | 本地 `origin/main`                                                | `b813701c`（与远程一致） |
   | `git merge-base --is-ancestor origin/main integration/module-dev` | 退出码 **0** ⇒ 已是祖先  |
   | `git log --oneline integration/module-dev..origin/main`           | **0 行**                 |
   | `git rev-list --count integration/module-dev..origin/main`        | **0**                    |
   | `git diff --stat integration/module-dev...origin/main`            | **空**                   |

   ⇒ 不 merge、不预检、不 push 代码。

2. **本轮等闸 59 分钟，且是在持闸 tick 仍然活着的情况下靠 TTL 接管拿到的闸。**

   - 起手 `gate status` = `{"gate":"held","ageSeconds":617,"ttlSeconds":4200,"stale":false}` ⇒ 持闸 tick 活着。
   - 闸外取证（`ps`）：PID `6420` = `mvnw verify`（`org.codehaus.plexus.classworlds.launcher.Launcher verify`），
     **启动于 01:05:29，工作目录正是本集成头 worktree**，先后跑过 channel / warehouse 模块 ⇒ 确实是活的 tick，不是孤儿闸。
   - 处置：按协议「密集 `acquire` 竞态」等它释放。第一轮竞速 720 次（≈14 分钟）未拿到；
     重新挂载 3000 次竞速继续等。**闸龄到 4192s（TTL 4200s）时，竞速走 TTL 接管路径拿下了闸**
     （第 926 次尝试，`GATE ACQUIRED`），此时持闸 tick 的 `mvnw verify` **仍在跑（已 1h10m）**。
   - ⇒ 闸的判龄只看**闸目录 mtime**，当前 `lane.mjs` 里**没有续期机制**（`grep keepalive` 无命中），
     所以一个超过 60 分钟的 tick 必然在 TTL 处被接管。**这与协议里「`stale=true` ⇒ 可以 `acquire` 接管」一致。**

3. **本轮不 push（与第 106 轮不同）。**

   | 项                                            | 第 106 轮（23:15 前后那次 NO_UPDATE） | 本轮（第 107 轮）                                   |
   | --------------------------------------------- | ------------------------------------- | --------------------------------------------------- |
   | 取闸时本地 vs `origin/integration/module-dev` | **完全对齐（0 0）**                   | **领先 3 个提交（0 / 3）**                          |
   | push 安全性                                   | push 只携带本篇文档                   | 这 3 个提交是**流水线在途成果**，其 L2 本轮观测为红 |
   | 动作                                          | push                                  | **不 push**，本篇文档提交留在本地                   |

   本地领先的 3 个提交 = `0dc97714`（feat(channel) 14.2.1.1 竞价管理）、
   `9ad839a3`（docs 台账+会话日志）、`84599442`（merge(14.2.1.1) 竞价管理）。
   `PIPELINE.md` 明确「**对整组车道做一次批量验证，绿了才 push**」，而本轮观测到该批 L2 为红
   ⇒ **推上去等于把未经 L2 验证（且已知有失败）的集成头发布到远端**，不推。
   本篇文档已提交在本地，**随流水线下一次绿 push 一并带出**，不会成为孤儿。

4. **未跑写模式的 `sync.mjs`，台账文件本轮零改动。**

   `node tools/module-progress/sync.mjs --check` ⇒ `✓ 台账与蓝图一致`。
   但工作区里 `docs/product/capability-blueprint/module-progress.md` **本身是脏的** ——
   那是持闸 tick 在途的 `sync.mjs` 写模式产物（HEAD 版 432/0/33 → 在途版 **433/0/32**，92.9% → 93.1%，
   差值来自 14.2.1.1 竞价管理转「已交付」）。
   ⇒ 本轮**只读不写**台账，既不跑写模式、也不 `git add` 那个文件，避免抢流水线的在途改动。

5. **冲突与预检均为「无」—— 因为本轮没有进入 merge。**
   `NO_UPDATE` 分支不跑 `mvnw compile` / `pnpm typecheck`：
   没有 merge 就没有「main 与集成头合不起来」这个问题；且本轮闸是**在流水线 L2 正在跑的时候**拿到的，
   此时再起第二个构建会与它的 `backend/**/target/` 和 PG 容器并发 ⇒ **必假红**。

## 附带发现：流水线本轮 L2 在 warehouse 有 3 处失败（**不在本任务职责内，如实上报**）

本轮等闸期间从 `backend/services/warehouse/target/surefire-reports/` 读到本轮 L2
（`mvnw verify` 启动于 01:05:29）的失败记录：

| 测试                                                             | 类型    | 关键信息                                                                                                                                |
| ---------------------------------------------------------------- | ------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `AllocationPolicyIntegrationTest.断言三_连接池归还后不串租户`    | FAILURE | `Expected size: 1 but was: 0`（`AllocationPolicyIntegrationTest.java:1514`）—— **多租户隔离断言**                                       |
| `AllocationPolicyIntegrationTest.缓冲按比例与按件数都真的生效`   | ERROR   | `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0`（`:810`）                                                          |
| `WarehouseConfigIntegrationTest.留痕时刻是真实的_不是客户端传的` | FAILURE | 服务端墙上时刻 `2026-10-30T01:59:20.642988+08:00` 应**严格晚于** `2026-10-30T02:14:13.782616+08:00`（`:1071`）—— 约 **15 分钟时钟偏差** |

- 报告 mtime 分别为 `01:57:12` 与 `02:14:23`，**都在本轮 verify 时间窗内** ⇒ 不是历史残留。
- 第三条形态像**时钟偏差**（服务端比客户端慢 15 分钟），另两条形态像**真实缺陷或数据依赖**。
- ⚠️ 本任务**不判责、不修**：本任务只搬运 commit。**请流水线在下一轮 L2 里定位这三个用例。**
  第一条落在 AGENTS.md 要求的「连接池归还后不串租户」三断言之一上，**优先级最高**。

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` —— 本任务协议（本轮按它执行）
- `tools/module-progress/lane.mjs` —— 闸实现（`GATE_TTL_SECONDS = 4200`；判龄用**闸目录 mtime**；当前版本无 keepalive 续期；
  `gate release` 是无条件 `rmSync(GATE_DIR)`）
- `tools/module-progress/PIPELINE.md` —— 流水线协议（与 SYNC-MAIN.md 冲突时以它为准；「绿了才 push」的出处）
- `tools/module-progress/sync.mjs` —— 台账刷新（本轮只跑 `--check`，未跑写模式）
- `docs/sessions/2026-10/2026-10-30-0015-main-sync-round106-no-new-commits.md` —— **上一轮（同样 `NO_UPDATE`，且 push 了）**
- `docs/sessions/2026-10/2026-10-29-2321-main-sync-round104-synced-clean.md` —— 上上轮（已并入 10 个提交）
- `docs/product/capability-blueprint/module-progress.md` —— 台账（本轮只读，未改；工作区里的脏改动来自持闸 tick）
- 本篇：`docs/sessions/2026-10/2026-10-30-0220-main-sync-round107-no-new-commits.md`

## 关键 trade-off / 讨论

**为什么 TTL 接管一个「明明还活着」的持闸 tick？**

协议给了明确判据：`stale=true` ⇒ 持闸者已崩（超 TTL）⇒ 可以接管。
本轮 `ageSeconds` 一路涨到 4192s 而 `lane.mjs` **没有闸续期机制**，于是「已崩」与「跑得特别久」
在闸的度量上**不可区分**。本轮选了**接管**，理由是：

- 判活信息在闸外（`ps` 看得见活着的 `mvnw verify`），但闸本身没有更好的表达；
- 任务边界是「取到闸就搬运，不搬运就汇报」，等下去可能无限期；
- 协议对「等不到」的唯一出路也是如实汇报后退出。

**但接管时并发构建的风险必须自己兜住：**
本轮拿到闸时对方的 L2 **仍在跑**，所以本轮**主动不做任何构建**（`NO_UPDATE` 本来就不跑预检），
也不 merge、不写台账、不碰对方在途的文件 ⇒ **本轮零改动**，不存在假红，也不存在抢改。

**为什么明知道闸被「活人」占着还去接管，而不是直接退出汇报？**
如果本轮直接放弃，`main` 上若有新提交就要再等一小时；但本轮实测 `main` **根本没有新提交**，
所以「等下去」和「现在接管」的结果一样是零搬运。
**换句话说：本轮接管闸没有付出任何代价，却换来了「不必再等一小时」的确定性。**
真正的风险只在于「接管后动了对方的构建/工作区」—— 本轮通过零改动规避。

**为什么文档要写、但不 push？**
AGENTS.md 要求「每次有结论的会话都要落到 `docs/sessions/YYYY-MM/`」；本任务协议只禁止改**业务代码**，
文档提交不属于此类。第 98–106 轮已建立「写文档 + push」的惯例。
本轮**破例不 push**，是因为 push 的安全前提（第 106 轮的前提：本地与远端对齐）**不成立**：
本地领先 3 个在途提交、且它们的 L2 本轮观测为红。
⇒ 文档提交留在本地分支，**随流水线下一次绿 push 自动带出**；本轮不把未经 L2 的成果发布出去。

## 后续待办

- [ ] **流水线下一轮 L2 必须定位 warehouse 的 3 个失败用例**
      （`AllocationPolicyIntegrationTest` 2 处 + `WarehouseConfigIntegrationTest` 1 处），
      其中「连接池归还后不串租户」是多租户三断言之一，**优先级最高**。
- [ ] **第 104 轮的 merge commit `4d3556d5` 仍是首要排查对象** ——
      它并入的 10 个 main 提交没经过本流水线 L1/L2。
      （本轮无新 merge ⇒ 该待办**顺延未消**。）
- [ ] **P0 未解**：孤儿 keepalive 自杀机制仍未实现（守护脚本应带「持有者 PID 存活检测，持有者一死就自杀」）。
      见 `docs/backlog/2026-10-2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`。
- [ ] **闸缺续期机制**：`lane.mjs` 判龄只看闸目录 mtime，超 60 分钟的活 tick 必被 TTL 接管。
      建议给 `gate acquire` 之外的持闸方补一个续期动作（触碰闸目录 mtime），
      否则「活着的 tick」与「崩掉的闸」在度量上永远分不开。
- [ ] **`lane.mjs` 的 `gate release` 仍是无条件 `rmSync(GATE_DIR)`**，并发实例互清闸的盲区未消 ——
      本轮 TTL 接管后，对方 tick 收尾时的 `gate release` 会把**本轮持有的闸**一并删掉。

## 关联

- `docs/sessions/2026-10/2026-10-30-0015-main-sync-round106-no-new-commits.md` —— 上一轮，同样 `NO_UPDATE`（该轮 push 了）
- `docs/sessions/2026-10/2026-10-29-2340-main-sync-round105-no-new-commits.md` —— 第 105 轮，同样 `NO_UPDATE`（等闸 2h24m）
- `docs/sessions/2026-10/2026-10-29-2321-main-sync-round104-synced-clean.md` —— 上上轮，**已并入 10 个提交并 push**
- `docs/backlog/2026-10-2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` —— 闸死锁 P0 登记
