# round-137 · 04:00 tick —— 调度闸被**已死的 03:00 tick** 持锁（两个 worker 同期死亡）⇒ 零派发 + 只读

> 本轮（2026-10-31 04:00 tick）**没有派发任何车道、没有取闸、没有回收、没有跑 L2**。
> 阻塞点**不是机器容量**（温度 / 配额 / PG / load 四闸全绿），
> 而是**调度闸被一个已经崩掉的 tick 合法持有** —— 并且它的两个 worker 也同期死亡，
> 留下两条 `state=running` 的**僵尸车道**（工作区里还有大量未提交成果）。
>
> ⚠️ 本轮**没有**做 round-133 那种「取证后原子接管」：两个硬门都没到
> （见「三、为什么本轮不接管」）。

---

## 一、进度（`sync.mjs` 实时值，本轮自己跑的第一件事）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **437**（**94.0%**） |
| 进行中 | 0 |
| 未开发 | **28** |
| 离全部交付还差 | **28 个** |

分阶段：D7 **130** 已交付 / 25 未开发；D6 75 / 3；D1–D2 80 / 0；D5 52 / 0；D3 40 / 0；D4 60 / 0。

台账完整性复核：`grep -c` = **465**，模块 ID **无重复**；`node tools/module-progress/sync.mjs --check` → **✓ 台账与蓝图一致**。

> 与 round-134/135 相比台账**无变化**：本轮零落地（无车道可并入），
> 汇总块已是 437/28 ⇒ **台账本轮无 diff**（commit 里只有两份新文档）。

## 二、档位与闸门判定

| 闸 | 判定 | 实测 |
| --- | --- | --- |
| 静默窗口 | 不生效 | `SILENT_WINDOW=0`（全天运行） |
| 强度档位 | 🔴 **reduced（降档）** | 已过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800` ⇒ `LANES=4/2=2`、`THROTTLE_MIN=50×2=100` |
| **批次节流闸** | 🔴 **STOP（命中）** | 距上批 **60 分钟** < 阈值 **100 分钟**（`.dev-agent/last-batch-at` = 2026-10-31 03:01:10） |
| 温度闸 | ✅ GO | `pmset -g therm`：**无** thermal / performance / CPU-power 记录，无降频项 |
| 配额闸 | ✅ GO | `lanesRunning=2` / 配额 6 |
| PG 闸 | ✅ GO | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` 容器 **0** 个 / 上限 6；**无标签孤儿容器 0 个** |
| load 闸 | ✅ GO | 1min **1.60** / 5min 1.62 / 15min 2.05（10 核，阈值 12）—— **1min < 5min < 15min ⇒ 负载在降**，不是爬升 |
| **调度闸** | 🔴 **撞闸（held / stale=false）** | `gate status`：`gate=held, ageSeconds=3155, ttlSeconds=4200, stale=false, reclaimable=false` |

⇒ **两道闸命中 STOP（节流闸 + 调度闸），本轮零派发。**

### 静态预检（5.1）—— 本轮无新迁移可查，但把僵尸车道的待并入迁移提前扫了

两条僵尸车道的 4 个待并入迁移（`V413__webhook_delivery_record.sql` /
`V413__webhook_delivery_record_permission.sql` / `V414__api_call_monitor.sql` /
`V414__platform_call_monitor_permission.sql`）：

- `ADD COLUMN` 无 `DEFAULT`：**0 命中**
- `SET NOT NULL`：**0 命中**
- `ADD CONSTRAINT ... CHECK`：**0 命中**

⇒ **「已知互相破坏形状」三条全部干净**。四个文件都是**纯新建表**（`CREATE TABLE`）
+ `ENABLE ROW LEVEL SECURITY` 与 **`FORCE ROW LEVEL SECURITY`** 两侧同开，
外加 identity 侧纯权限码登记。下一轮并入时不必再做这条预检。

## 三、撞闸取证：持闸者**已经死了**（逐条取证，非推测）

| # | 证据 | 取值 |
| --- | --- | --- |
| 1 | 持闸 tick 会话状态 | `mvs_43a6ca53e2b946f49723e8934ac281dd` = **`error`**（`net::ERR_CONNECTION_CLOSED`），最后活动 **03:33:39** |
| 2 | worker 18.1.2.2 会话 | `mvs_fde852cf961844629cc29cb0b2c06074` = **`error`**（`ERR_CONNECTION_CLOSED`），最后活动 **03:33:37** |
| 3 | worker 18.1.1.3 会话 | `mvs_3d377aaeee5f4564beb810a4d9f7eb02` = **`error`**（`ERR_HTTP2_PING_FAILED`），最后活动 **03:18:32** |
| 4 | `dispatch.gate/owner` 里的 PID | **22876 不存在**（辅助线索，按 round-135 结论不单独作证） |
| 5 | 构建进程 | 全仓**无** maven / surefire / vitest / next / playwright 进程 |
| 6 | 闸目录 mtime 写入者 | **无续期守护在跑**（`keepalive-r133.sh` 已随 wall-clock 上界自终止；`ps` 无 keepalive 进程）；闸最后一次被写是 **03:08:04**（03:00 tick 自己的 `gate touch`，PID 24176） |
| 7 | 在途车道 | **2 条**（18.1.2.2 / 18.1.1.3）—— 但它们的 worker 已死（证据 2、3） |
| 8 | 该 tick 是否留下过提交 | **没有**：集成头 HEAD 仍是 02:24 的 `d656a781`（main 同步任务），03:00 tick 的任何产出都没进版本库 |

⇒ 持闸者与其两个 worker **在同一时段被网络层掐断**（03:18–03:33），
和 round-123「额度统一熔断」同形：**不是代码问题，是会话侧熔断**。

### 两条僵尸车道的成果**没丢**（都在 worktree 里，未提交）

| 车道 | worktree 未提交内容 |
| --- | --- |
| **18.1.2.2** 投递记录（V413） | **14 个路径**：platform 迁移 `V413__webhook_delivery_record.sql`（4 表）+ identity 权限码迁移 + `DeliveryRecordController/Service/Models` + `WebhookDeliveryExecutor` + `DeliveryRecordIntegrationTest` / `DeliveryRecordReceiver` / `DeliveryRecordTenancyIntegrationTest` + 前端 `app/integrations/deliveries/` 与 `components/integrations/deliveries/` + **改动了共享白名单 `apps/web/src/lib/api-proxy.ts`** + 契约 `platform.ts` / `platform.openapi.json`。其 note 自述：**「后端 V413+执行器+11 端点+27 例测试全绿（实测）」** |
| **18.1.1.3** 调用监控（V414） | **4 个路径**：platform 迁移 `V414__api_call_monitor.sql`（2 表）+ identity 权限码迁移 + `ApiCallMonitorModels` + `ApiGatewayEndpoints`（note 仅到「开工，依赖自检中」，**进度明显落后**） |

## 四、为什么本轮**不**接管（两个硬门都没到）

1. **节流闸没过**：`last-batch-at` = 03:01:10，距今 **60 分钟** < 阈值 **100 分钟**
   ⇒ 协议 §1.5 明写「**未达阈值 → 本轮直接结束。不取闸、不 plan、不派发、不跑 L2**」。
2. **闸龄还没到 TTL 窗口**：`ageSeconds=3155` < `ttlSeconds=4200` ⇒ `stale=false`。
   协议「最高优先级禁令」：*只有 `gate status` 的 `stale=true`（超过 TTL 70 分钟）才允许接管*。
   工具自带的受管入口 `lane.mjs gate reclaim --wait 600` 也**会拒绝**：
   闸龄要到 **04:18** 才越过 TTL，且越过之后还会撞第二道门（见下）——
   **该窗口已于 04:11:08 越过，但仍未解锁**（见本节末尾的复核实证）。

⇒ 本轮严格照办：**不清闸、不 force、不抢占**。取证到此为止，**处置留给下一轮**。

### ✅ 收尾复核时闸龄已越过 TTL —— 死锁被实证确认

写文档期间（04:17 复核）闸龄自然越过了 TTL 窗口，`gate status` 实测：

```json
{ "gate": "held", "ageSeconds": 4605, "ttlSeconds": 4200,
  "stale": true,          ← 已越过 TTL（越过时刻 ≈ 04:11:08）
  "reclaimable": false,   ← ★ 但仍然不可接管 ★
  "inFlightLanes": 2, "buildProcesses": [], "holderAlive": true }
```

⇒ **第二节预测的机制性根因得到实证**：
**「闸龄过 TTL」并不等于「可以接管」** —— `holderAlive` 仍因两条僵尸车道（`state=running`）而为 true，
`gate reclaim` 仍会拒绝。**下一轮不需要再等 TTL 窗口（已过）**，
但**必须**走第五节第 3 步的原子接管，否则 `reclaim` 一律拒绝。

⚠️ 本轮**没有**趁 `stale=true` 去抢闸 —— 协议要求接管必须同时满足
「闸龄超 TTL」**与**「持闸者已死」两条，而工具的第二条判据此刻是**误判为真**。
强行接管 = 从「活体」手里抢闸 = 双派发 = 版本号撞号 + L2 成片假红。

## 五、🔴 给下一轮（05:00 tick）的解死锁路径 —— 有一道工具门会挡住，必须知道

**好消息**：两个 worker 都死了，但 `lanes.json` 里它们的 `sessionId` 仍是 `null`，
心跳也已过 60 分钟宽限期 ⇒ `plan` 会把它们当**孤儿正常重派**，
且 `plan` **复用既有分支与 worktree**（`feat/18-1-2-2` / `.worktrees/18.1.2.2` 等）
⇒ **上一轮的成果原地保留，新 worker 接着写**（round-123 同款处置）。

`plan --lanes 2 --dry-run` 实测（**零副作用**：`git status` 干净、`lanes.json` mtime 未变）：

```json
{ "toDispatch": [
    { "id": "18.1.2.2", "name": "投递记录",   "branch": "feat/18-1-2-2", "migrationVersion": "V413", "redispatched": 2 },
    { "id": "18.1.1.3", "name": "调用监控",   "branch": "feat/18-1-1-3", "migrationVersion": "V414", "redispatched": 2 } ],
  "newlyPlanned": [], "serviceBlocked": [] }
```

⚠️ **`newlyPlanned` 为空不是「没活干」**：两条孤儿车道已占满 `LANES=2` 的名额，
且 26 个未开发模块全部集中在 D6/D7 的 15/18 卷（服务已就位，`serviceBlocked` 为空）。

### ⚠️ 真正的拦路虎：`gate reclaim` 在 04:18 之后**仍会拒绝**

`holderLiveness()` 判定「持闸者活着」的依据是
**`lanes.json` 里 `state==='running' || 'ready'` 的条数 > 0**（或存在构建进程）。
僵尸车道的 `state` 仍是 `running` ⇒ 恒为 `holderAlive=true` ⇒
`gate reclaim` 会一直回「**持闸者仍活着（在途车道 2 条）**」而**拒绝接管**。
**闸龄过 TTL 并不能自动解死锁** —— 这是 round-130/131/132「连续三轮只取证不处置」的机制性根因。

⇒ **下一轮的正确处置顺序**（不要跳步）：

1. 先确认持闸者仍死（证据 1/2/3/5 复查一遍）；
2. **不要**用 `lane.mjs abandon` 清僵尸车道 —— 它的 worktree 里有 18.1.2.2 的
   **27 例全绿成果 + 4 张表 + 前端页面**（note 自述），
   而 `gc` 对 `abandoned` 车道会 `git worktree remove --force` + `rm -rf`
   ⇒ **一 abandon 一 gc 就把成果删了**；
3. 走 round-133 同款**原子接管**（先取证 ⇒ 再在**单个 node 进程**里
   `rename` 陈旧闸到固定偷取目标 + `mkdir` 重建 + 写 owner，全程无 release→acquire 竞速窗口）；
4. 接管后立刻 `plan --lanes 2`（会重派这两条孤儿车道、复用 worktree），
   并把「接手半成品」写进任务书第一节。

> 该「工具门」已单独记进 backlog：
> `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`
> （`holderLiveness` 应改用 `laneIsStale`/`laneIsUnclaimed` 这种「心跳宽限期」判据，
> 而不是「state 字段值」—— 后者让死 tick 永久伪装成活体；
> 该改动会碰 `lane-gate-reclaim.itest.mjs` 的既有断言，须单独评估，不在本轮动手）。

## 六、收尾三件套（本轮结果）

| 件 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件（`docs/sessions/2026-10/2026-10-31-0400-pipeline-round137-gate-held-dead-tick-zombie-lanes.md`）+ backlog 一份 |
| ② commit | ✅ 与台账同一 commit（台账本轮无 diff，故该路径为 no-op） |
| ③ push | ✅ `origin/integration/module-dev`（**docs-only**，集成头 push 前与远端一致，本轮无代码变更） |

> ⚠️ **本轮没有 L2 可言**：零派发 ⇒ 零并入 ⇒ 无批次可验证。
> push 的内容**只有两份 markdown 文档**，不含任何后端 / 前端代码。

## 七、吞吐预期（按当前档位现算，不引用任何历史文档里的数字）

- `LANES=2`（reduced）× 实际放行批次数：cron `0 * * * *`（24 次/天）配节流闸 100 分钟
  ⇒ 每 2 小时放行一批 ⇒ **约 12 批/天**
- 名义吞吐 = 2 × 12 = **24 个/天**；综合折损系数 0.70（BLOCKED/冲突 0.96 × 上游未就绪 0.95
  × L2 返工 0.90 × 长尾占位 0.90）⇒ **理论约 17 个/天**
- 剩余 **28** 个（其中 2 条在途、26 个待排）⇒ **理论 1.5–2 天**；
  但**当前闸死锁是主导风险**：解不开就归零，解得开则上沿可期。
  **诚实区间 2–4 天**，且**不给单点日期**。

## 八、风险提示

- 🔴 **调度闸死锁**（本轮核心发现）：死 tick + 僵尸车道 ⇒ `gate reclaim` 的持有者存活判据被绕过不了，
  05:00 tick 若不按第五节的顺序处置，会变成 round-130/131/132 式的连续停机。
- ⚠️ 18.1.2.2 改了**共享白名单** `apps/web/src/lib/api-proxy.ts` ⇒ 重派后的 worker
  **必须**跑全量 allowlist 回归（`pnpm vitest run apps/web/src/app/api`），
  否则可能静默吃掉别人已交付的路由。
- ⚠️ 18.1.2.2 的 note 自述「**27 例测试全绿**」属**未经验证的 worker 自陈**，
  重派后必须重跑 L0/L1 复核，**不可直接采信**。
- ⚠️ 18.1.1.3 进度明显落后（note 仅「开工，依赖自检中」，4 个路径）⇒ 预期它会先卡在依赖自检。
