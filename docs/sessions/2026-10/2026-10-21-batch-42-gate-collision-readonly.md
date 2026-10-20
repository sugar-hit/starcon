# batch-42 —— 撞调度闸：零派发只读轮（闸被 02:15 `main → module-dev` 同步任务遗留占用）

> 批次号接在 batch-41 之后（`docs/sessions/2026-10/` 下现有最大为 batch-41）。
> 集成头 `integration/module-dev`，本轮基线 `608146c6`（与 `origin/integration/module-dev` **完全一致**，0/0）。
> 本轮为 **2026-10-21 03:00 tick**，`SILENT_WINDOW=0`，`MODE=full`（`LANES=4` / `THROTTLE_MIN=50`，
> 当前 03:0x < `TOKEN_HORIZON=2026-10-25T00:00:00+0800`，未进入降档）。
>
> ⚠️ **本轮模块级交付 = 0。** 调度闸被另一个 cron 占用，按协议**零派发 + 只读检查**结束本轮。
> 本文件记录的是**本轮的结论与交接清单**，不是功能交付说明。

## 本轮摘要

本轮在**第一步同步台账后**即撞上调度闸：`gate acquire` 返回 `held-by-other`（退出码 3），
持闸者是 **2026-10-21 02:15 触发的 `main → module-dev` 同步任务**
（另一个 cron，与本流水线共用同一把闸，协议见 `tools/module-progress/SYNC-MAIN.md`）。

该同步任务的会话在 **02:47:10 结束回合且没有释放闸**（详见下文取证），闸一直挂到
**03:55:29 超 TTL（4200s）**才会被判定为陈旧。按协议本轮**不取闸、不清闸、不 force**，
因此：**未并入任何车道、未派发新车道、未跑 L2**。

好消息是本轮的**只读取证**把「闸被谁占、为什么没释放、什么时候能接管」查清楚了，
并留下一份交接清单给 04:00 tick（4 条 ready 车道 + 2 条在途车道）。

---

## 进度快照（`node tools/module-progress/sync.mjs` 实测输出）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **252**（54.2%） |
| 进行中 | 0 |
| 未开发 | **213** |
| 距 465 全交付 | **还差 213 个** |

分阶段（已交付 / 未开发）：

| 阶段 | 已交付 | 未开发 |
| --- | --- | --- |
| D1–D2 | 80 | 0 |
| D3 | 36 | 4 |
| D4 | 58 | 2 |
| D5 | 52 | 0 |
| D6 | 13 | 65 |
| D7 | 13 | 142 |

台账完整性复核（只读 grep）：**465 行、无重复模块 ID**。

## 容量闸判定（只读四连 + 温度闸）→ **GO**

| 闸 | 实测 | 阈值 | 结论 |
| --- | --- | --- | --- |
| 温度闸 `pmset -g therm` | `No thermal warning level has been recorded` / `No performance warning level` / `No CPU power status` | 出现降频项 < 100 即 STOP | **正常**，无降频项 |
| 配额闸（在途车道） | `lanesRunning = 2` | `RUNNING_QUOTA = 6` | **通过** |
| PG 闸（Testcontainers 标签容器） | **0** | `PG_CONTAINER_LIMIT = 6` | **通过** |
| load 闸 | `1.10 / 2.25 / 3.07`（1/5/15 min，**下降趋势**） | `LOAD_LIMIT = 12`（10 核） | **通过** |
| 孤儿 PG 容器（无 testcontainers 标签） | **0** | — | 无需清理 |

> load 是**下降趋势**（1min < 5min < 15min），不是爬升形态，不触发 STOP。

**批次节流闸**：`last-batch-at = 02:05:21`，本轮距上批 **55 分钟 ≥ 50 分钟** ⇒ **放行**。
（本轮未写 `last-batch-at` —— 本轮没有派发批次，写它会把节奏往前推而不产生任何产出；
04:00 tick 的 elapsed 将是 115 分钟，同样过闸。）

## ⛔ 调度闸撞闸取证（本轮核心结论）

### 现象

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 1524, "ttlSeconds": 4200, "stale": false }

$ node tools/module-progress/lane.mjs gate acquire
{ "ok": false, "gate": "held-by-other" }     # 退出码 3，闸目录无任何副作用
```

### 持闸者身份（只读取证）

| 证据 | 内容 |
| --- | --- |
| 闸目录 mtime / `owner` | `2026-10-21 02:45:29`（owner 写入 `2026-10-20T18:45:29.650Z`） |
| `owner` PID | `13682` —— **不是活性证据**（`gate acquire` 是短命进程，写完即退出） |
| 闸外活性取证（`ps`） | 集成头 worktree 内**无** maven / surefire / vitest / next build / `node tools/module-progress` 进程 ⇒ **持闸者没有在跑 L2 或任何构建** |
| `docker ps` | 无 testcontainers 标签容器 ⇒ 没有并发 PG 争抢 |
| 会话列表 | `starcon main → module-dev 集成头同步` 会话 `mvs_d005a06b…`，**创建于 02:15:00，最后更新 02:47:10**，状态 `idle` |

### 根因

02:15 的同步任务在 **02:45:29 取得闸**（上一轮 pipeline tick 在 02:45:12 commit、02:45:2x push 后释放，
同步任务在交接窗口里抢到闸），随后：

1. `git merge --no-ff origin/main` → 与 `d28188d6`（PR #11 单亲 squash 分叉）冲突 **6 个文件**；
2. 按协议 `git merge --abort` **干净回退**（HEAD 回到 `608146c6`、`git status` 干净、**无 `MERGE_HEAD` 残骸**、与远端 0/0 偏差）；
3. 准备写本轮会话日志并 commit/push —— **回合在 02:47:10 被截断**（`finish_reason: stop`，usage 为 0），
   于是**既没写它自己的会话日志，也没 `gate release`**。

⇒ **闸泄漏**。同步任务在本仓的协议要求是「收尾必须 `gate release`」，但回合被截断时这条清理路径跑不到。
（`docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` 是上一轮同步的日志；
**02:15 这轮没有留下日志** —— 它没来得及写。）

### 为什么本轮不清闸

| 候选动作 | 判断 |
| --- | --- |
| `gate force` / 删闸目录 | **禁止**。协议两处明写「不要清闸、不要 force」「没拿到过闸就绝对不要 `gate release`」；清闸会破坏互斥，可能让两个 tick 同时派发、撞 Flyway 版本号、集成头留残骸。 |
| 直接接管（`acquire` 抢 TTL 未到的闸） | **禁止**。`gate acquire` 在 `age < TTL` 时会**无副作用**地失败（已实测：闸目录 mtime 不变）。协议判据一律以 `gate status` 的 `stale` 字段为准，不自己按 `ageSeconds` 猜。 |
| 有界等待到闸释放 | **无效**。batch-41 那轮等过 4 分钟就等到同步任务正常 `release`；本轮持闸者**回合已结束**，不会再释放，等不到。 |
| 等到 TTL 过期（03:55:29）再接管 | **不做**。本轮 04:00 tick 就能通过 TTL 自动接管（`age = 4400s > 4200s` ⇒ `stale = true` ⇒ 允许 acquire）。本轮等到 03:55 再抢，只比 04:00 tick 早 5 分钟，却要空耗 45 分钟 tick 时长，且多一次与 04:00 tick 的抢闸风险。**吞吐上零收益。** |

⇒ **结论：零派发 + 只读检查 + 收尾三件套（文档）→ 结束本轮。**

## 车道盘点（交接给 04:00 tick）

**READY（可并入，4 条）** —— 迁移版本号已预分配，`merge-ready` 应能干净合入：

| 模块 | 名称 | 阶段 | L3 | 分支 | 迁移 | 车道自报进展 |
| --- | --- | --- | --- | --- | --- | --- |
| 5.3.1.2 | 批量测算 | D6 | 5.3.1 | `feat/5-3-1-2` | `V230` | 后端完成（迁移 + Controller/Service/Rules/Models + 29 例绿），待契约 codegen + 前端 |
| 5.3.2.1 | 定价模板 | D6 | 5.3.2 | `feat/5-3-2-1` | `V231` | 后端完成，L1 37 例 0 失败（功能 15 / 租户三断言 4 / 纯算术 18），待契约 + 前端 |
| 6.1.2.1 | 档口与货源池 | D6 | 6.1.2 | `feat/6-1-2-1` | `V233` | 已提交 `ff1f5331`；L0 绿、L1 `SupplierSource*` 19 例绿、前端 313 例绿；api-proxy 自检 110 → 111 条（+43 / −0） |
| 6.3.2.2 | 到货与逾期 | D6 | 6.3.2 | `feat/6-3-2-2` | `V235` | 迁移 + 后端服务/端点/两端口完成；L1 `PurchaseArrivalIntegrationTest` 22 例 + `PurchaseArrivalTenancyIntegrationTest` 3 例（三条租户断言全过），待契约 + 前端 |

**RUNNING（在途，2 条）** —— 本轮**一律不杀**，让它们自己跑完，下一轮回收：

| 模块 | 名称 | 阶段 | L3 | 分支 | 迁移 | 最后心跳 | 车道自报进展 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5.2.3.2 | 物流与头程费用 | D6 | 5.2.3 | `feat/5-2-3-2` | `V232` | 02:58:42 | 范式读完（5.2.3.1 规则/实际两表 + 5.2.2.2 只追加 + 5.2.2.1 分区共用路由），开始写 `V232` 迁移 |
| 6.2.1.1 | 缺货与断货风险 | D6 | 6.2.1 | `feat/6-2-1-1` | `V234` | 02:54:30 | 契约/范式/上游端口已核实（warehouse 已交付 6.2.1.1 专用 netting 端点），开始写 `V234` 迁移与 trade 后端 |

两条 worker 会话（`Build 5.2.3.2 logistics freight rule` / `Build 6.2.1.1 stockout risk`）
状态均为 `started`，心跳在 10 分钟内 ⇒ **在途正常，不是僵尸**。

## 本轮明确**没有**做的事（点名 + 原因）

| 动作 | 是否执行 | 原因 |
| --- | --- | --- |
| `merge-ready`（并入 4 条 ready 车道） | ❌ **未做** | 闸被占。协议对「闸被占」的禁止项里明确列了 `merge-ready`（它会触发后续动作）。 |
| `plan --lanes 4` | ❌ **未做** | `plan` 有副作用（建分支 + worktree + 预占 Flyway 版本号），闸被占时禁止执行；且不得用 `plan` 试探。 |
| 派发 worker 子任务 | ❌ **未做** | 同上（撞闸 ⇒ 零派发）。 |
| 静态预检（`ADD COLUMN` / `SET NOT NULL` / `CHECK` 扫描） | ❌ **未做** | 静态预检的对象是**本批新并入的迁移**。本轮没有并入任何迁移 ⇒ 无新迁移可扫；不是跳过防护。 |
| L2 批量验证（`mvnw verify` + typecheck/lint/test + Next build） | ❌ **未做** | 本轮无新代码进入集成头，代码树与 batch-41 L2 全绿时的 `608146c6` **逐字节相同**；且闸被占时禁止跑 L2（发热高峰 + 抢 Testcontainers）。batch-41 的 L2 六阶段结论：后端 6197 例 / 前端 5267 例全绿（`.tmp-l2-batch41.log`：`PHASE6_EXIT=0`、`L2 DONE 02:43:35`）。 |
| `gc` | ❌ **未做** | 闸被占时禁止；且本轮未合并任何车道，无可回收 worktree。 |

## 收尾三件套（本轮）

| 环节 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件 `docs/sessions/2026-10/2026-10-21-batch-42-gate-collision-readonly.md` |
| ② commit | ✅ 与台账同一个 commit，消息前缀 `docs(batch-42):`（与仓库既有 batch-21 ~ batch-41 惯例一致） |
| ③ push | ✅ `git push origin integration/module-dev`（**只推集成头，绝不推 main**） |

**为什么本轮可以 push（审计要点）**：本 commit **只含 markdown 文档，零代码改动**。
集成头代码树与 `608146c6` 完全相同，而 `608146c6` 已在远端、且已通过 batch-41 的 L2 六阶段全绿
（后端 6197 + 前端 5267 例）。文档提交不改变任何被验证过的代码路径，
**不构成「L2 未绿就推代码」**。先例：01:15 同步轮同样在无代码合并的情况下提交并推送了
`docs(session)` 文档（`75646b73`）。

## 遗留与风险

1. **⚠️ 闸泄漏（已定位，需人工或下一轮接管）**：02:15 同步任务在回合被截断时没跑 `gate release`。
   闸将于 **03:55:29** 超 TTL；**04:00 tick 会自动接管**（`stale=true` ⇒ 允许 `acquire`）。
   ⇒ 本轮**故意不接管**，见上文「为什么本轮不清闸」。
2. **⚠️ 02:15 同步轮缺会话日志**：它的冲突取证（`d28188d6` 单亲 squash 分叉、6 文件冲突、
   `merge --abort` 干净回退、main 已交付集 242 ⊂ 集成头 252 ⇒ **main 带不进任何新模块交付**）
   随回合一起丢失。`docs/sessions/2026-10/` 下只有 01:15 那轮的同类日志。
   ⇒ **建议**：让同步任务在其协议里补一条「回合被截断也要 `gate release`」的收尾保障，
   否则每个被截断的同步轮都会白挡一轮流水线。
3. **main → 集成头同步已连续多轮冲突未并入**：冲突 6 个文件全在
   `apps/web/src/components/pricing/cost-composition-*`、`apps/web/src/lib/api-proxy.ts`、
   `backend/services/channel/.../ListingPublishJobIntegrationTest.java`。
   集成头在全部 6 个文件上都**严格更新**（集成头独有 8–435 行，main 独有 0–18 行）⇒
   **main 是旧快照，不含新交付**，这个冲突本质上是「squash 分叉 vs 线性历史」的形状，
   靠 merge 解决不了，需要人工决定「把 main 重置到集成头」或「接受 squash 作为新基线」。
4. **4 条 ready 车道积压**：5.3.1.2 / 5.3.2.1 / 6.1.2.1 / 6.3.2.2 自 02:09–02:53 起就绪，
   等 04:00 tick 回收；其中 6.1.2.1 已连前端都做完（只差并入）。
5. **未开发集中在 D7（142）与 D6（65）**：D6 有 4 条在途/待并入车道，D7 的 142 个模块
   需要服务缺口先补齐，否则 `plan` 会持续返回 `serviceBlocked`。
