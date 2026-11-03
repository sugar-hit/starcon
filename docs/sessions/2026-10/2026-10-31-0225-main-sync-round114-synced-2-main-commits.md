# 集成头同步 main 第 114 轮 —— 已同步（并入 2 个 main 提交）+ 调度闸接管

> **结果**：已同步 · 合并 commit `6f0f7db8` · 并入 origin/main 的 2 个提交（`e10007ad` / `bc54cce1`）·
> 快速预检绿（backend compile exit 0 + pnpm typecheck 3/3）· 台账 **437 已交付 / 0 进行中 / 28 未开发（94.0%）**（与同步前一致）
>
> **本轮的主线工作不是「合并」，而是「把卡了 3 小时 17 分的调度闸接管回来」。**

---

## 一、决策摘要

### 1. 闸：stale=true 但工具拒绝接管 —— 决定原子接管，而不是「零派发 + 汇报」

取闸时 `gate status` 给出矛盾信号：

| 字段 | 值 | 含义 |
| --- | --- | --- |
| `gate` / `stale` | `held` / **true** | 闸龄 11627s > TTL 4200s ⇒ 已陈旧 |
| `reclaimable` | **false** | 工具自判「持闸者还活着」 |
| `inFlightLanes` | 2 | 它把 2 条车道算成在途 |
| `buildProcesses` | 0 | 没有构建在跑 |

**根因**：`lane.mjs` 的 `holderLiveness()` 把 `state=running/ready` 的车道**无条件**计为在途，
**不看心跳**；而 `lane.mjs status` 自己的 `lanesRunning` 口径**是**看心跳的（90 分钟失联阈值）。
⇒ 同一个工具两套口径：崩掉 7 小时的车道永远挡着闸。

**取证（6 条独立证据，逐条复核，不引用旧结论）**：

| # | 证据 | 取值 |
| --- | --- | --- |
| 1 | 闸龄 / keepalive 最后续期 | 11861s 未续期（23:01:22 起），TTL 4200s ⇒ stale |
| 2 | owner PID 75187 | **不存在**（辅助线索，round-135 已降级，不单独定案） |
| 3 | 持闸会话状态 | 23:00 轮 `mvs_e36bafd73…` = **error**（ERR_NETWORK_IO_SUSPENDED），最后活动 23:01:52 —— **拿闸 30 秒后就死了** |
| 4 | 构建进程 | 全仓 0 个（mvnw / java / surefire / vitest / next build / playwright / gradle） |
| 5 | tick 进程 | 0 个（lane.mjs / sync.mjs / takeover / 续期守护全无） |
| 6 | 在途车道（心跳口径） | `lane.mjs status` = **lanesRunning 0**；两条「在途」车道心跳 345/349 分钟前、`sessionId: null`、工作区文件自 19:08 起零改动 ⇒ 按工具自己的 `laneIsStale` / `laneIsUnclaimed` 定义就是孤儿 |

⇒ 持闸者已死，**四重以上证据成立**。按 `2026-10-30-orphan-keepalive-daemon-gate-deadlock-round133.md` §6
「撞闸 ⇒ 零派发」的前提是「持闸者是活体 tick」—— 前提不成立时，一味照搬零派发就是**把可自愈的故障固化成长期停机**。

**接管方式**（`.dev-agent/takeover-sync-r114.mjs`，gitignored，临时脚本）：
与 `lane.mjs` 的 `gateAcquire` 陈旧闸分支**逐行等价**（rename 到固定偷取目标 `dispatch.gate.steal` → 重判 → mkdir → 写 owner/keepalive/token），
只改**一处**判据：在途车道改用**心跳口径**（与 `lane.mjs status` 一致），并加**持闸者指纹校验**（pid + acquiredAt + token 三者全等才接管）。
⇒ **绝不抢新主人的闸**，也**没有 rm + mkdir 的两步窗口**（round-133 版脚本的老毛病）。

### 2. 拒绝「清闸硬闯」，也拒绝动车道状态

- **不调 `gate release`**（它是无条件 `rm -rf` 整个闸目录 ⇒ 会删掉别人活着的闸）。
- **不 `abandon` 那 2 条孤儿车道**：那会释放它们的 Flyway 版本号（`lane.mjs` 把 `abandoned` 排除在已占用版本之外），
  且下一次流水线 `gc` 会删掉 `.worktrees/18.1.2.2` —— 里面还留着 V413 迁移 + 11 个端点 + 27 个绿测的**未提交**工作。
  **车道生命周期是流水线的职责，不是同步任务的职责**；本轮只接管闸，不碰车道。

### 3. 以远端为准：本地头先快进对齐

本地头 `af2a773c` 是远端 `origin/integration/module-dev`（`461c3b17`）的**祖先**（不是分叉）——
23:00 轮拿闸后 push 了（`docs: 补记 PR #71 的合并核验与回归守护证据`），然后死在闸里没释放。
⇒ 先 `git merge --ff-only origin/integration/module-dev` 对齐（纯快进，不丢任何东西），再合并 main。

---

## 二、本轮并入的内容

| 项 | 值 |
| --- | --- |
| 合并 commit | `6f0f7db8`（双父：父1 `461c3b17` = 集成头原头 / 父2 `bc54cce1` = origin/main） |
| 并入的 main 提交 | **2 个**：`e10007ad`（PR #70）· `bc54cce1`（PR #71） |
| 改动文件数 | **0**（内容零变化） |
| 冲突 | **无** |

**为什么内容零变化却仍然要合并**：自 `2785f6c8` 起 main 的树一个字节都没变，
9 个内容提交（`f18219a3` / `79455b2b` / `8ce5ab3a` / `f1387035` / `47619bc6` / `18cf7a7e` / `2785f6c8`）
**已由 23:00 轮并入**集成头。main 上多出来的只是两个「把集成分支合回 main」的 PR 合并提交。
本轮合并的作用是**补上祖先关系** —— 合并前 `git merge-base --is-ancestor origin/main integration/module-dev` 为假，
合并后为真 ⇒ 后续轮次不会再反复看到「HAS_UPDATE」。

唯一有差异的文件是 `docs/sessions/2026-10/2026-10-30-2300-…-worktree-cleanup.md`：
main 侧是旧版，集成头侧多了「七、协议更新合入 main（PR #71）」一节。
main 侧相对合并基**未改动** ⇒ 三方合并自动取集成头的新版，**不丢内容、不需人工解冲突**。

---

## 三、预检与台账

| 项 | 结果 |
| --- | --- |
| `backend/./mvnw -q compile` | **exit 0**（仅 JVM restricted-method 警告） |
| `pnpm typecheck` | **3/3 successful**（turbo FULL TURBO 缓存命中；合并树与合并前逐字节相同 ⇒ 缓存键有效） |
| `node tools/module-progress/sync.mjs` | 18 卷 / 465 模块 · 已交付 437 · 进行中 0 · 未开发 28 |
| 台账变化 | **无**（437/0/28 → 437/0/28，完成率 94.0%）—— 本轮不含模块交付 |

---

## 四、文件清单

**新建（仓库内）**

- `docs/sessions/2026-10/2026-10-31-0225-main-sync-round114-synced-2-main-commits.md` —— 本文件

**提交**

- `6f0f7db8` —— `merge(main): sync from origin/main`（双父，正文列清并入的 2 个 main 提交）
- 本文件的 commit：`docs(sessions): 集成头同步 main 第 114 轮 —— 已同步，并入 2 个 main 提交`

**临时（`.dev-agent/`，gitignored，不进仓库）**

- `.dev-agent/takeover-sync-r114.mjs` —— 调度闸原子接管脚本（指纹校验 + 心跳口径）
- `.dev-agent/merge-msg-r114.txt` —— 合并 commit 正文

**未改动**：任何业务代码、任何车道状态、任何 cron 任务。

---

## 五、后续待办（交人工 / 后续车道）

- [ ] **`lane.mjs` 的 `holderLiveness()` 应改用心跳口径**（与同文件 `status` 的 `lanesRunning` 一致）。
      本轮已用临时脚本绕过，但**根因在工具里**：任何用 `gate acquire` / `gate reclaim` 的任务
      （流水线 tick、看板刷新）仍会被同一条孤儿车道挡住。**这是第 5 次复发**（round-130/131/132/133 → 本轮）。
- [ ] **`lane.mjs` 应提供「心跳口径接管」的受管入口**，让 `gate reclaim` 自己认得陈旧车道，
      而不是每轮各写一个临时接管脚本（round-133 backlog 的待办 §5 第 3 条，至今未做）。
- [ ] **孤儿车道的正确回收路径**：18.1.1.3 / 18.1.2.2 的工作区里还有未提交成果（V413 迁移 + 11 端点 + 27 绿测），
      `abandon` + `gc` 会连带删掉；需要一条「先取回成果再回收工作区」的路径。
- [ ] **本轮合并 commit `6f0f7db8` 仍是流水线 L2 的首要排查对象**（预检只覆盖编译与类型，不覆盖迁移违约与跨车道互相破坏）。
- [ ] **会话存活性**：23:00 / 00:00 / 01:00 轮 tick 与 01:15 轮同步会话全部死于
      `ERR_CONNECTION_CLOSED` / `ERR_NETWORK_IO_SUSPENDED` / `ERR_NETWORK_IO_SUSPENDED` ——
      **拿闸后 30 秒就死、push 完就死**的形态已出现多轮，闸 TTL 与会话存活是同一个根问题的两面。
