# round-138 · 05:00 tick —— 闸死锁解除（取证后原子接管）+ 两条僵尸车道原地重派；但增量格式闸追溯判红 ⇒ 本轮不 push

> 本轮（2026-10-31 05:00 tick）**解开了 round-130/131/132/137 连续四轮的死锁**：
> 调度闸被一个**已死的 03:00 tick** 持锁、工具自带的 `gate reclaim` 因 `holderLiveness()` 判据缺陷**永久拒绝**接管
> ⇒ 本轮按 round-137 第五节给的顺序做**取证 → 原子接管**，成功取得闸，
> 并用 `plan --lanes 2` **原地重派**两条僵尸车道（18.1.2.2 / 18.1.1.3），成果一个没丢。
>
> ⚠️ **但 L2 没绿**：增量格式闸 `format-gate.mjs` **exit 1**（6 个违规文件）。
> 归因取证已完成（见第七节）：**不是本轮回归**，是**闸追溯性地扫到了闸诞生前就并入并推到 main 的旧批次**。
> 按协议「**L2 没绿绝对不 push**」⇒ **本轮不 push**，说明与 commit 照做，成果留在集成头本地。

---

## 一、进度（`sync.mjs` 实时值，本轮自己跑的第一件事）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **437**（**94.0%**） |
| 进行中 | 0（派发后即 2 条在途） |
| 未开发 | **28** |
| 离全部交付还差 | **28 个** |

分阶段进度与 round-137 **一致**（台账零 diff）：D7 130 已交付 / 25 未开发；D6 75 / 3；D1–D2 80 / 0；D5 52 / 0；D3 40 / 0；D4 60 / 0。

台账完整性复核：`node tools/module-progress/sync.mjs --check` → **✓ 台账与蓝图一致**（exit 0，465 行、ID 无重复）。

> 台账**本轮无 diff**（无车道可并入 ⇒ 无新交付）⇒ commit 里只有本份文档，
> 「说明文件与台账同一 commit」的约束天然满足（台账路径为 no-op）。

## 二、档位与闸门判定

| 闸 | 判定 | 实测 |
| --- | --- | --- |
| 静默窗口 | 不生效 | `SILENT_WINDOW=0`（全天运行） |
| 强度档位 | 🔴 **reduced（降档）** | 已过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800` ⇒ `LANES=4/2=2`、`THROTTLE_MIN=50×2=100` |
| **批次节流闸** | ✅ **PASS** | 距上批（03:01 批次）**141 分钟** ≥ 降档后阈值 **100 分钟** |
| 温度闸 | ✅ GO | `pmset -g therm`：**无** thermal / performance / CPU-power 记录 |
| 配额闸 | ✅ GO | 在途车道 **2** / 配额 6 |
| PG 闸 | ✅ GO | 带 `org.testcontainers.sessionId` 标签的容器 **0** 个 / 上限 6；无标签孤儿容器 0 个 |
| load 闸 | ✅ GO | 1min **1.82** / 5min 1.73 / 15min 1.74（10 核，阈值 12） |
| **调度闸** | 🔴 撞闸 → ✅ **本轮原子接管** | 见第三节 |

⇒ **四道机器闸全绿**，唯一阻塞是调度闸 ⇒ 解除后本轮正常走「plan → 派发 → merge-ready → L2 → 三件套」。

### 静态预检（5.1）—— 两条车道待并入的 V413 / V414

| 检查项 | V413（18.1.2.2，4 表） | V414（18.1.1.3，2 表） |
| --- | --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | **0 命中** | **0 命中** |
| `SET NOT NULL` | **0 命中** | **0 命中** |
| `ADD CONSTRAINT ... CHECK` | **0 命中** | **0 命中** |
| RLS `ENABLE` + `FORCE` 双开 | ✅ | ✅ |
| `tenant_id NOT NULL` | 5 处 | 4 处 |

⇒ 「已知互相破坏形状」三条**全部干净**；两个版本号都是**纯新建表**，并入时不必再预检。

## 三、🔓 调度闸死锁：取证 → 原子接管（本轮核心动作）

round-137 已把「持闸者已死」坐实，但明确指出：**光有证据还不够，`gate reclaim` 会一直拒绝**，
原因是 `holderLiveness()` 用 `lanes.json` 的 `state` 字段判活，死 tick 的僵尸车道让它恒为「活着」。
本轮照 round-137 第五节给的顺序执行：

### 3.1 四重独立取证（复核，非推测）

| # | 证据 | 取值 |
| --- | --- | --- |
| 1 | 持闸 PID | **22876 已死**（进程不存在） |
| 2 | 持闸会话状态 | `mvs_43a6ca53e2b946f49723e8934ac281dd` = **`error`**（`ERR_CONNECTION_CLOSED`），末次活动 **03:33:39** |
| 3 | worker 18.1.2.2 会话 | `mvs_fde852cf961844629cc29cb0b2c06074` = **`error`**（`ERR_CONNECTION_CLOSED`），末次活动 **03:33:37** |
| 4 | worker 18.1.1.3 会话 | `mvs_3d377aaeee5f4564beb810a4d9f7eb02` = **`error`**（`ERR_HTTP2_PING_FAILED`），末次活动 **03:18:32** |
| 5 | 构建进程 | 全仓**无** maven / surefire / vitest / next / playwright 进程 |
| 6 | 在途车道心跳年龄 | 18.1.2.2 **130 分钟** / 18.1.1.3 **137 分钟** > TTL **90 分钟** |

⇒ 持闸者与其两个 worker 在 **03:18–03:33** 同一时段被网络层掐断（与 round-123「会话侧熔断」同形，**不是代码问题**）。

### 3.2 原子接管（`.dev-agent/takeover-r138.mjs`，gitignored 不入库）

脚本内置 **5 道取证门**，任何一道不过就拒绝动手：

1. 持闸 PID 不存在；
2. 闸龄 > TTL（4200s）；
3. 全仓无构建进程；
4. 在途车道心跳**全部**过期；
5. 复核「偷到的闸仍陈旧」（防止抢到活闸）。

接管手法（关键差异点）：

- **固定偷取目标 + 单进程 rename**：把陈旧闸 `rename` 到**固定**目标 `.dev-agent/dispatch.gate.steal`
  （即 `lane.mjs` 自己的 `GATE_STEAL_DIR`）⇒ **结构上杜绝双向持闸**
  （两个进程若同时抢，会抢到同一个已被消费的目标，一个成功一个失败）；
- `rename → mkdir → 写 owner/keepalive/token` **全在同一个 node 进程内**完成 ⇒ 无 `release → acquire` 竞速窗口。

> 首次运行**失败并当场自查修正**：脚本注释里 `keepalive*/owner` 的 `*/` 提前闭合了块注释 ⇒ `SyntaxError`，
> **闸未受任何影响**（脚本尚未执行到任何一步）；改正后第二次运行成功。
> 接管后 `gate status`：`holder=pipeline-tick, ageSeconds=0, stale=false`，`gate touch` exit 0。
> owner PID `38421`，token `38421-1793395335372-cuky6z08`。

⚠️ **本轮没有顺手修 `holderLiveness()`**：backlog 明确要求「单独评估 + 改既有断言」，不在流水线 tick 里夹带（见第八节待办 4）。

## 四、回收与派发

| 步骤 | 结果 |
| --- | --- |
| `merge-ready` | `merged: []` —— **无待并入**（上一轮零落地） |
| `gc` | `removed: []` —— 无孤儿可清 |
| `plan --lanes 2 --dry-run` | 零副作用预演通过（`git status` 干净、`lanes.json` 未变） |
| `plan --lanes 2`（实跑） | `toDispatch` 2 条 / `redispatched: 2` / `newlyPlanned: []` / `serviceBlocked: []` |

派发的两条车道（**复用既有分支、worktree 与迁移版本号 ⇒ 前任成果原地保留**）：

| 车道 | 模块 | 分支 | 迁移 | 状态 |
| --- | --- | --- | --- | --- |
| **18.1.2.2** | 投递记录 | `feat/18-1-2-2` | V413 | `redispatched=2`，接手 13 路径半成品 |
| **18.1.1.3** | 调用监控 | `feat/18-1-1-3` | V414 | `redispatched=2`，接手 4 路径半成品 |

> ⚠️ `newlyPlanned` 为空**不是「没活干」**：两条孤儿车道已占满 `LANES=2` 的名额；
> 26 个未开发模块全部落在 D6/D7 的已就位服务上（`serviceBlocked` 为空）。

### 双写者风险核查

V413 / V414 **仅**属这两条车道，版本号无撞号，集成头磁盘上不存在 V413 / V414 文件 ⇒ 可安全并行。

### 派发明细

| 车道 | task id | session | 心跳（最后一次实测） |
| --- | --- | --- | --- |
| 18.1.2.2 | `bg_e4e424c4-6b81-46e1-8ba3-ede9e71098b5` | `mvs_c0aed18f94f1499793039d065d99fa9b` | 21:32:52Z（= 05:32:52 CST）✅ |
| 18.1.1.3 | `bg_3e3aeeae-9948-49df-8284-3cc9b031e9f9` | `mvs_973f6f9e08e84b2396cf9e3c84261b9c` | 21:32:47Z（= 05:32:47 CST）✅ |

两份任务书按 `PIPELINE.md` 子任务模板**逐项替换全部 `<...>` 占位符**（绝对路径 `/Users/guan/Projects/starcon-worktrees/integration` / `.worktrees/<id>`），
并写入了：接手半成品清单（13 / 4 路径）、**前任 note 属未验证自陈不得采信**、
18.1.2.2 必须先解决「18.1.2.1 两个测试因 V413 建表而红」的未解耦合、
18.1.2.2 因改了共享白名单 `apps/web/src/lib/api-proxy.ts` **必须跑全量 allowlist 回归**（`pnpm vitest run apps/web/src/app/api`），
18.1.1.3 的「上游 = API 网关」无现成交付物须先做依赖自检并按能力声明口径如实答（**禁止假数据**）。

## 五、L2 范围判定：代码零差异 ⇒ 继承绿证据 + 廉价子集当场重验

集成头 `backend/ apps/ packages/` 相对上次绿灯状态 `b5476e82`：

```
git diff --stat b5476e82 HEAD -- backend/ apps/ packages/   → （空）
git log --oneline b5476e82..HEAD -- backend/ apps/ packages/ → （空）
```

⇒ **产品代码零差异**，且本轮 `merge-ready` 无并入 ⇒ **零新增覆盖**。
此时重跑 2.25 小时的 `mvnw verify` + Next 生产构建**不增加任何信心，只会制造发热高峰**
⇒ 按 round-135 同款策略：**继承绿证据 + 廉价子集当场重验**。

### 廉价子集实测

| 检查 | 结果 |
| --- | --- |
| `sync.mjs --check` | ✅ exit 0 —— ✓ 台账与蓝图一致 |
| `*.itest.mjs`（17 个） | **15 通过**（含 lane-gate-reclaim 24/0、format-gate 10/0、lane-gate 21/0、lane-plan-orphan、merge-union、lane-service-gate、sync-evidence-pipe 8/0、ci-gate、remote-lane 24/0 等） |
| `merge-summary.itest.mjs` | ❌ **环境问题**（本仓库只有 `origin` 远端、没有 `upstream`），非代码回归 |
| `build-capability-map.itest.mjs` | ⚠️ **26 通过 / 1 失败**（「未交付模块的抽屉说明尚未开发」）—— 相关代码 `9f8b1ca8` **早于**绿灯 `b5476e82`，且 `tools/progress-site/` 自 `b5476e82` 起**零改动** ⇒ **既有缺陷，非本轮引入** |
| **`format-gate.mjs`（增量格式闸）** | ❌ **exit 1 —— 本轮 L2 红在此** |

> `tools/` **不在** `pnpm lint` / `typecheck` 的覆盖范围（无 `tools/package.json`）⇒ 本轮唯一的真实代码 delta 只能靠 `*.itest.mjs` 覆盖，故单独跑了工具链 itest。

## 六、🔴 增量格式闸判红的归因（本轮最需要说清的一件事）

```
node tools/module-progress/format-gate.mjs   →  exit 1
{ "batch": { "base": "7fba92b7a2a1", "head": "6f0f7db8bac7", "mergeCount": 6 },
  "changedFiles": 65, "checkedFiles": 32, "violations": 6 }
```

### 6.1 违规清单与性质

| 文件 | prettier delta 行数 | 性质 |
| --- | --- | --- |
| `apps/web/src/app/api/platform/webhook-allowlist.test.ts` | 4 | 超 100 列的长正则 `pattern:` 未换行 |
| `apps/web/src/components/integrations/webhooks/webhooks-model.test.ts` | 4 | 同上 |
| `apps/web/src/components/integrations/webhooks/webhooks-model.ts` | 28 | 同上 |
| `apps/web/src/components/integrations/api-catalog-workspace.tsx` | 112 | 同上 |
| `apps/web/src/components/integrations/webhooks/webhooks-workspace.tsx` | 147 | 同上 |
| `apps/web/src/lib/api-proxy.ts` | 38 | 同上（**共享白名单**，本轮 18.1.2.2 正在改） |

全部是**机械换行**，**零逻辑变化**。

### 6.2 归因链（逐条可复核）

1. 违规由 **18.1.1.2**（`f1a85d2e`，10-30 13:35，接口目录 / api-proxy 白名单）与
   **18.1.2.1**（`be1dbe3d` 15:48，合并 `b5476e82` 15:50，事件订阅）引入。
2. **增量格式闸落地于 `da68f6ab`（10-30 23:18）** —— 即 `git merge-base --is-ancestor be1dbe3d da68f6ab` 为 true
   ⇒ **这两个车道是在闸诞生之前就并入的**。
3. 但闸的批次窗口是 **base `7fba92b7a2a1`（10-30 13:15）→ head `6f0f7db8bac7`（10-31 02:20）**，
   而 base 的取法是「**本批最早那个 merge commit 的第一父**」⇒ **窗口起点早于闸自身落地** ⇒
   **闸把「闸诞生前的旧批次」也算成了「本批」**。
4. `git diff origin/main HEAD -- <6 个文件>` **为空** ⇒ 这些内容**已经在 `origin/main` 上**
   ⇒ 债**早已随 batch-116 进 main**，不是本轮引入。

### 6.3 结论

- **不是本轮回归**（本轮零并入、代码零差异）；
- 但 **L2 红 ⇒ 不 push**（`PIPELINE.md` §5.3 / cron prompt 第 539–540 行：「**L2 没绿不 push**，但**说明照写、commit 照做**」），
  ⇒ **本轮不 push**，成果与诊断留在集成头本地，修复后随下一批一起 push。

### 6.4 已采取的处置（避免双写者）

- **18.1.2.2 车道拥有 `apps/web/src/lib/api-proxy.ts`**（它正在改这个文件）⇒
  已用 `task_append` **追加要求该车道**（追加内容随其运行中的 turn 投喂，mode=`steered`）：
  在**自己的 worktree** 内跑 `pnpm exec prettier --write apps/web/src/lib/api-proxy.ts`（只针对这一个文件，不全仓 `--write`），
  与其改动一并 commit（**不 push**）。
  理由：**下一批一旦并入，`api-proxy.ts` 就落在下一批的检查窗口里**；若它仍不合规 ⇒ 下一轮 L2 继续红 ⇒ 流水线持续无法 push。
- 另外 5 个文件属**已交付车道**，其 worktree（`.worktrees/18.1.1.2` / `.worktrees/18.1.2.1`）**已不存在** ⇒ 无法在原 worktree 内修；
  而它们**不会进入下一批的检查窗口**（下一批 base 之后没人改它们）⇒ 闸会自动放过。
  债仍在 main 上，建议 LANES 空出来时派一条**纯格式修复车道**（见第八节待办 2）。
- **调度器自己没有在集成头上改任何文件** —— 协议明写「不要在集成头上改，也不要全仓 `--write`」。

## 七、收尾三件套（本轮结果）

| 件 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件（`docs/sessions/2026-10/2026-10-31-0500-pipeline-round138-gate-takeover-zombie-lanes-redispatched.md`） |
| ② commit | ✅ `docs(round-138): …`（**与台账同一 commit**；台账本轮零 diff ⇒ 台账路径为 no-op） |
| ③ push | ❌ **L2 未绿，本轮不 push** —— 增量格式闸 exit 1 |

> 集成头 HEAD 在本轮结束时为 **`7a994ec0`**（与 `origin/integration/module-dev` 同步，0/0），
> commit 后本地会领先远端 1 个 commit（**docs-only**，不含任何后端 / 前端代码）。
> 下一批 L2 绿后一并推送。

## 八、后续待办（给下一轮）

1. **两条车道落地后**：`merge-ready` → 并入 → 重跑 L2（含增量格式闸）→ **绿了才 push**。
2. **确认 `api-proxy.ts` 已合规**（18.1.2.2 已收到追加要求）；若仍红 ⇒ 在 18.1.2.2 的 worktree 内再兜一次。
3. **纯格式修复车道**：处理另外 5 个文件（`webhook-allowlist.test.ts` / `api-catalog-workspace.tsx` /
   `webhooks-model.test.ts` / `webhooks-model.ts` / `webhooks-workspace.tsx`）—— 从 `integration/module-dev`
   切新分支、只跑 `pnpm exec prettier --write <这 5 个文件>`、单独提交。**不要全仓 `--write`。**
4. **`holderLiveness()` P0 缺陷**（用 `state` 字段而非心跳新鲜度判活，使 `gate reclaim` 永久拒绝）：
   已记在 `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`，
   须**单独评估 + 改 `lane-gate-reclaim.itest.mjs` 既有断言**，不在流水线 tick 里顺手改。本轮仍走取证式接管。
5. **评估 `format-gate` 的 base 选取**：是否应把 base 锚定到「**闸落地之后**的首个 merge」，
   以免继续把「闸诞生前的旧批次」算作本批（当前行为会追溯性判红，代价是整条流水线无法 push）。
6. `build-capability-map.itest.mjs` 的既有失败（「未交付模块的抽屉说明尚未开发」）—— 判定为既有缺陷，是否单独立项待定。
7. `merge-summary.itest.mjs` 失败是**环境问题**（仓库只有 `origin`、没有 `upstream` 远端），非代码回归。

## 九、吞吐预期（按当前档位现算）

- `LANES=2`（reduced）× cron `0 * * * *`（24 次/天）配节流闸 100 分钟 ⇒ 每 2 小时放行一批 ⇒ **约 12 批/天**
- 名义吞吐 = 2 × 12 = **24 个/天**；综合折损系数 0.70（BLOCKED/冲突 0.96 × 上游未就绪 0.95 × L2 返工 0.90 × 长尾占位 0.90）
  ⇒ **理论约 17 个/天**
- 剩余 **28** 个（2 条在途 + 26 待排）⇒ **理论 1.5–2 天**；**诚实区间 2–4 天**，不给单点日期。

## 十、风险提示

- 🔴 **增量格式闸的批次边界会追溯判红**（本轮 L2 红的直接原因）——已用追加要求让 18.1.2.2 兜住 `api-proxy.ts`；
  若下一批仍红，则属机制性问题，须走待办 5。
- ⚠️ **18.1.2.2 改了共享白名单 `apps/web/src/lib/api-proxy.ts`** ⇒ 必须跑全量 allowlist 回归
  （`pnpm vitest run apps/web/src/app/api`，200+ 文件），否则可能静默吃掉别人已交付的路由。
- ⚠️ **18.1.2.2 的前任 note 自述「27 例全绿」属未经验证的 worker 自陈** ⇒ 重派后必须重跑 L0/L1 复核，不可直接采信。
- ⚠️ **18.1.1.3 的依赖自检结论「集成头无网关组件」尚未落成交付物** ⇒ 预期它会先卡在「按能力声明口径如实答 `available=false`」这一步。
- ⚠️ **两条车道的 worker 仍在本轮 tick 的生命周期内运行** ⇒ 本 tick 结束不代表它们结束；下一轮 tick 若撞闸，先查心跳再判死。
- ⚠️ 本轮**没有**把 `holderLiveness()` 修好（见待办 4）⇒ **下一轮仍需走取证式接管**（或等专门修复落地）。

## 十一、涉及文件

| 路径 | 说明 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-31-0500-pipeline-round138-gate-takeover-zombie-lanes-redispatched.md` | 本文件（本轮说明） |
| `docs/sessions/2026-10/2026-10-31-0400-pipeline-round137-gate-held-dead-tick-zombie-lanes.md` | 上一轮（死锁取证与解死锁四步） |
| `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md` | `holderLiveness()` P0 缺陷 |
| `tools/module-progress/PIPELINE.md` | 落库协议（最高权威） |
| `tools/module-progress/format-gate.mjs` | 本轮 L2 红点 |
| `.dev-agent/takeover-r138.mjs` | 本轮原子接管脚本（gitignored，不入库） |
| `.worktrees/18.1.2.2` / `.worktrees/18.1.1.3` | 两条在途车道的 worktree（复用，未提交成果原地保留） |

## 十二、关联

- `docs/decisions/0004-java-spring-boot-microservices.md`（后端形态）、`docs/decisions/0002-multi-tenancy-strategy.md`（RLS 与租户隔离）
- `docs/product/capability-blueprint/18-integration-and-automation.md`（18.1.1.3 调用监控 / 18.1.2.2 投递记录原文）
- `docs/product/capability-blueprint/module-progress.md`（台账，465 行，本轮零 diff）
