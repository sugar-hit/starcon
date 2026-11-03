# round-141 · 11:00 tick —— 节流闸命中（距上批 54 分钟 < 100 分钟）⇒ 零派发零并入；并入预演复验（0 冲突 + 合并树格式闸 exit 0）

> 本轮（2026-10-31 11:00 tick）是**纯只读轮次**：批次节流闸在台账同步之后立刻命中
> （`last-batch-at` = 10:05:24，tick 起点距上批 **54 分钟** < 降档后阈值 **100 分钟**），
> 按协议「未达阈值 → 本轮直接结束。**不取闸、不 plan、不派发、不跑 L2**」执行。
>
> ⚠️ **本轮零并入、零派发、零 L2**：两条已落地的车道（**18.1.2.2 投递记录** / **18.1.1.3 调用监控**）
> 仍是 `state=ready`、**未并入集成头**，集成头台账**本轮零 diff**。
>
> ✅ 本轮唯一有价值的动作：**用 `git merge-tree` + `git archive`（不碰工作区、不建克隆）复验并入预演** ——
> **0 冲突 + 合并树增量格式闸 exit 0 + 台账 465 行/439 已交付** ⇒ **12:00 tick 可直接并入 + 跑全量 L2 + push**，
> 且这次复验是在**并发同步 agent 改写历史之后**的集成头上重做的（上一轮的预演基线是改写前的）。

---

## 一、进度（`sync.mjs` 实时值，本轮自己跑的第一件事）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **437**（**94.0%**） |
| 进行中 | **0**（2 条车道已落地待并入，不计入「进行中」） |
| 未开发 | **28** |
| 离全部交付还差 | **28 个** |

分阶段：D7 130 已交付 / 25 未开发；D6 75 / 3；D1–D2 80 / 0；D5 52 / 0；D3 40 / 0；D4 60 / 0。

台账完整性复核：`node tools/module-progress/sync.mjs --check` → **✓ 台账与蓝图一致**（exit 0）；台账 **465 行**、无重复 ID。

> 台账**本轮无 diff**（零并入）⇒ commit 里只有本份文档，「说明文件与台账同一 commit」的约束天然满足（台账路径为 no-op）。

## 二、档位与闸门判定

| 闸 | 判定 | 实测 |
| --- | --- | --- |
| 静默窗口 | 不生效 | `SILENT_WINDOW=0`（全天运行） |
| 强度档位 | 🔴 **reduced（降档）** | 已过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800` ⇒ `LANES=4/2=2`、`THROTTLE_MIN=50×2=100` |
| **批次节流闸** | 🔴 **STOP（命中）** | `last-batch-at` = **10:05:24** ⇒ tick 起点距上批 **54 分钟** < 阈值 **100 分钟**（收尾时 92 分钟，仍未到） |
| 温度闸 | ✅ GO | `pmset -g therm`：**无** thermal / performance / CPU-power 记录 |
| 配额闸 | ✅ GO | 在途车道 **0** / 配额 6 |
| PG 闸 | ✅ GO | `postgres:16-alpine` 容器 **0** 个 / 上限 6；`org.testcontainers.sessionId` 标签容器 **0** 个（另有 1 个 `postgres:15-alpine` 属本机 langfuse 项目，非本流水线） |
| load 闸 | ✅ GO | 1min **1.75** / 5min 2.10 / 15min 2.25（10 核，阈值 12）；**未爬升** |
| **调度闸** | ➖ **未取闸** | `gate=free`、`stale=false`、`holder=null`、`reclaimable=false`、`inFlightLanes=2`、`buildProcesses=[]` |

⇒ **四道机器闸全绿**，唯一阻塞是**节流闸** ⇒ 本轮按协议零成本早退（不碰 git 写入、不起容器、不派子任务、不跑测试）。

### 2.1 为什么「机器都绿了」也要停

节流闸不是安全闸，是**节奏闸**：它保证相邻两批之间至少隔 `THROTTLE_MIN`（降档 100 分钟）。
协议对未达阈值的处置是硬规定（`PIPELINE_CRON_PROMPT.md` §1.5）：「**过不了批次节流闸就直接结束 —— 不要为了「有事做」而硬派**」。
本轮距上批 54 分钟时判定，**即使机器全闲也不抢跑** ⇒ 放行时刻 **11:45:24**，落到 **12:00 tick**（距上批 115 分钟）。

## 三、两条车道仍未并入（状态复核）

| 车道 | 模块 | 分支 | 迁移 | 状态 | head | 与集成头 | 体量 | 工作区 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **18.1.2.2** | 投递记录 | `feat/18-1-2-2` | **V413** | `ready` | `b8bf981d` | 26 behind / **6 ahead** | 26 文件 / +8,655 −1,015 | 干净 |
| **18.1.1.3** | 调用监控 | `feat/18-1-1-3` | **V414** | `ready` | `5484e69e` | 26 behind / **6 ahead** | 22 文件 / +11,021 −2,458 | 干净 |

- 双写者核查：V413 / V414 **仅**属这两条车道，集成头磁盘上 `git ls-tree -r HEAD | grep V41[34]` **为空** ⇒ **无迁移号撞号**。
- 两条车道相对 `origin/integration/module-dev` 均为 **26 behind / 6 ahead** ⇒ 分支上只有自己的 6 个 commit，**不存在双写者**。

## 四、✅ 并入预演复验（本轮唯一动作，全部只读，不碰工作区）

> 方法比上一轮更轻：**不建一次性克隆**，直接用 `git merge-tree --write-tree` 在对象库里算出合并树，
> 再用 `git archive` 把合并树导出到 `.tmp/` 跑 prettier。**工作区、分支、闸、`lanes.json` 全程零改动。**

### 4.1 合并本身

| 步骤 | 实测 |
| --- | --- |
| 合并 `feat/18-1-2-2` → 集成头 | `git merge-tree --write-tree` **rc=0**（**0 冲突**），契约/台账由 `merge.contract-union` 驱动处理 |
| 合并 `feat/18-1-1-3` → 上一步结果 | **rc=0**（**0 冲突**） |
| 合并树台账 | **465 行**、ID 无重复；`18.1.2.2` / `18.1.1.3` 均 **已交付**；全表分布 **439 已交付 / 26 未开发**（= 当前 437 + 2） |
| 合并树 `api-proxy.ts`（共享白名单） | **同时保留两条车道路由**：`deliveries` **44** 处 / `call-monitor` **19** 处 |

### 4.2 合并树上的增量格式闸（纯自动识别，与 12:00 tick 同一算法）

窗口由闸自己算：合并后新增 2 个双父 merge commit，把 40 提交窗口里**最老的那个 merge 挤出去**
⇒ 批次边界从当前的 `base=b5eb2372c928 / mergeCount=5` **前移**到 `base=25bea9d2 / mergeCount=4`，
受管文件 **38** 个（`changedFiles` 67）。

| 检查 | 实测 |
| --- | --- |
| 合并树受管文件 prettier `--list-different` | **exit 0，零违规** ✅ |
| **对照实验**（往合并树副本的 `api-proxy.ts` 注入畸形缩进后重跑） | **exit 1，精确报出该文件** ✅ ⇒ 证明上一行不是「静默通过」 |
| 当前集成头 HEAD（**未合并**）跑同一个闸 | **exit 1**，6 个违规文件（`webhook-allowlist.test.ts` / `api-catalog-workspace.tsx` / `webhooks-model.test.ts` / `webhooks-model.ts` / `webhooks-workspace.tsx` / `lib/api-proxy.ts`） |

⇒ **推不出去的根因确认可解**：这 6 个违规文件由两条车道各修一半，**合并后才合齐**；
只要 12:00 tick 真的并入，增量格式闸**预期转绿**，L2 才有可能全绿并 push。

## 五、L2：本轮**未跑**

- 本轮**零并入、零产品代码变更**（`git log cc351215..HEAD -- backend/ apps/ packages/ tools/` **为空**）
  ⇒ 按协议 §5.1「范围判定」，无新增覆盖时**沿用上一轮绿证据**，不制造发热高峰。
- 上一轮（round-140 10:00）已跑过廉价子集：`sync.mjs --check` ✅、17 个 itest 中 15 个通过
  （2 个失败分别是**环境问题**（仓库无 `upstream` 远端）与**既有缺陷**（能力地图未交付抽屉），
  与本轮无关；相关代码自基线以来**零提交**）。
- 本轮**唯一实测**的 L2 闸门（增量格式闸）结果见 4.2：**当前 HEAD 仍红（6 文件）**，根因与上轮同源，**合并后转绿**。

## 六、收尾三件套（本轮结果）

| 件 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件（`docs/sessions/2026-10/2026-10-31-batch-118-pipeline-round141-throttle-gate-hit-merge-rehearsal.md`） |
| ② commit | ✅ `docs(batch-118): …`（**与台账同一 commit**；台账本轮零 diff ⇒ 台账路径为 no-op） |
| ③ push | ❌ **本轮不 push** —— ①节流闸命中，协议明写本轮不跑 L2；②增量格式闸在当前 HEAD 仍红（exit 1）⇒ 「**L2 没绿不 push**」；③两条车道的 12 个 commit 尚未并入、未过 L2 ⇒ 推送前必须先并入 + 跑绿 |

> **远端落后 5 个 commit**，全部 **docs-only**（`e7342d42` / `70bc3d62` / `cc351215` / `fc2a2e7f` / `28663243`），
> **不含任何后端 / 前端 / 契约代码**。按协议「L2 没绿绝对不 push」⇒ 下一批并入两条车道、L2 绿后一并推送。
>
> ⚠️ **两条车道的代码仍未进主线**（后端 2,700+ 行、契约 39 个类型、两个前端工作区），
> 只在车道分支上；集成头与远端都没有。

## 七、吞吐预期（按当前档位现算）

- `LANES=2`（reduced，配置 4 减半） × cron `0 * * * *`（24 次/天）配节流闸 **100 分钟**
  ⇒ 每 2 小时放行一批 ⇒ **约 12 批/天**
- 名义吞吐 = 2 × 12 = **24 个/天**；综合折损系数 0.70（BLOCKED/冲突 0.96 × 上游未就绪 0.95 × L2 返工 0.90 × 长尾占位 0.90）
  ⇒ **理论约 17 个/天**
- 剩余 **28** 个（2 条待并入 + 26 待排）⇒ **理论 1.5–2 天**；**诚实区间 2–4 天**，不给单点日期。

## 八、风险提示

- ✅ **并入本身无风险**（本轮实测）：0 冲突 + 合并树格式闸 exit 0 + 台账 465 行合齐 + 白名单两条车道路由都在。
- 🔴 **基础设施掐断仍是最大产出瓶颈**：过去数小时多轮 worker 会话死于 Token Plan 用量上限 / HTTP2 ping 失败
  （backlog `2026-10-31-worker-session-network-death-loses-uncommitted-work.md`）。本轮零派发 ⇒ 未新增风险面，
  但 12:00 tick 派发时须继续要求**分阶段 commit**。
- ⚠️ **增量格式闸的批次边界追溯问题仍在**：闸的 base 取「本批最早 merge commit 的第一父」，
  仍会把闸诞生前的旧 merge 算进窗口。本轮实测到**并入 2 个新 merge 会把窗口前移**（`mergeCount` 5→4），
  恰好把红窗口甩在身后 ⇒ 本批能解红，但**机制性问题没解**：今后窗口一旦再被旧 merge 撑大，仍会复现。
  若下一批又红且定位不到 ⇒ 走 round-138 待办 5（base 锚定到「闸落地之后」的首个 merge），而不是重复派格式修复车道。
- ⚠️ **`holderLiveness()` 的 P0 缺陷未修**（backlog `2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`）
  ⇒ 12:00 tick 若再撞闸，仍需走取证式接管（或等专门修复落地）。
- ⚠️ **两条车道都改了共享白名单 `apps/web/src/lib/api-proxy.ts`** ⇒ 并入后**必须跑全量 allowlist 回归**
  （`pnpm vitest run apps/web/src/app/api`），否则可能静默吃掉别人已交付的路由
  （表现为页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**）。

## 九、后续待办（给 12:00 tick）

1. **节流闸放行**（12:00 距上批 115 分钟 ≥ 100）⇒ `gate acquire` → `merge-ready`（一次收 18.1.2.2 与 18.1.1.3）
   → `sync` → **一次全量 L2**（`mvnw verify` + typecheck/lint/test + Next build）→ **绿则 push**。
   本轮已复验：并入 **0 冲突**、合并树增量格式闸 **exit 0**（§4）。
2. **并入后必须跑全量 allowlist 回归**（`pnpm vitest run apps/web/src/app/api`）—— 两条车道都改了 `api-proxy.ts`。
3. **若增量格式闸仍红** ⇒ 说明出现了**新的**「无主违规文件」（已交付车道的 worktree 被 gc 后无人可修），
   再不行 ⇒ 派一条**纯格式修复车道**（从 `integration/module-dev` 切新分支，只跑
   `pnpm exec prettier --write <该文件>`，单独提交。**不要全仓 `--write`**）。
4. **`holderLiveness()` P0 缺陷**：单独评估 + 改 `lane-gate-reclaim.itest.mjs` 既有断言，**不在流水线 tick 里顺手改**。
5. `merge-summary.itest.mjs` 失败是**环境问题**（仓库只有 `origin`、没有 `upstream` 远端），非代码回归；
   `build-capability-map.itest.mjs` 的 1 例失败是**既有缺陷**（相关代码自 `b5476e82` 起零提交）。

## 十、涉及文件

| 路径 | 说明 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-31-batch-118-pipeline-round141-throttle-gate-hit-merge-rehearsal.md` | 本文件（本轮说明） |
| `docs/sessions/2026-10/2026-10-31-batch-117-pipeline-round140-gate-takeover-zombie-lanes-redispatched.md` | 上一轮（闸死锁二次解除 + 两条车道重派 + 并入预演的第一版） |
| `docs/sessions/2026-10/2026-10-31-1117-main-sync-round118-no-new-commits.md` | 并发同步 agent 本轮的集成头同步说明（改写了历史，本轮预演已在其上重做） |
| `docs/product/capability-blueprint/module-progress.md` | 台账（465 行，本轮零 diff） |
| `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md` | `holderLiveness()` P0 缺陷 |
| `docs/backlog/2026-10/2026-10-31-worker-session-network-death-loses-uncommitted-work.md` | worker 会话被掐断丢失未提交成果 |
| `tools/module-progress/PIPELINE.md` | 落库协议（最高权威） |
| `tools/module-progress/format-gate.mjs` | 增量格式闸（本轮复验对象） |
| `tools/module-progress/lane.mjs` | 车道与调度闸工具 |
| `.worktrees/18.1.2.2` / `.worktrees/18.1.1.3` | 两条在途车道的 worktree（复用，成果原地保留） |

## 十一、关联

- `docs/decisions/0004-java-spring-boot-microservices.md`（后端形态）、`docs/decisions/0002-multi-tenancy-strategy.md`（RLS 与租户隔离）
- `docs/product/capability-blueprint/18-integration-and-automation.md`（18.1.2.2 投递记录 / 18.1.1.3 调用监控原文）
- `docs/product/capability-blueprint/module-progress.md`（台账，465 行）
