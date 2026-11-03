# round-140 · 10:00 tick —— 闸死锁二次解除（7 项取证后原子接管）+ 两条僵尸车道第 5 次重派；增量格式闸仍判红 ⇒ 本轮不 push

> 本轮（2026-10-31 10:00 tick）是 **round-139（07:00 tick）猝死后的接管轮**。
> 07:00 tick 取得闸后派出两条车道，**与它的两个 worker 在 08:00–08:08 同一时段被基础设施掐断**
> （Token Plan 用量上限 2056 / `ERR_HTTP2_PING_FAILED`），闸成孤儿、成果全部滞留在工作区未提交状态。
>
> 本轮做完了三件事：
> ① **7 项独立取证坐实持闸者与全部 worker 已死** → **原子接管**调度闸（`gate reclaim` 因 `holderLiveness()` 的 P0 缺陷仍拒绝，改走 round-137/138 确立的取证式接管）；
> ② **原地重派两条僵尸车道**（复用分支 / worktree / 迁移版本号 ⇒ 4 任 worker 的 41 项未提交成果全部原地保留），并把**分阶段 commit** 写进任务书；
> ③ **增量格式闸判红 ⇒ 本轮不 push**，但把修复**派给本批活着的车道**（含一个「无主违规文件」的代修），下一批即可解红。
>
> ⚠️ **本轮零并入**：两条车道在 10:18 与 10:45 **先后落地（`land` 成功）**，但**尚未并入集成头** ⇒ 集成头台账本轮**无 diff**；
> 并入与全量 L2 整组留给下一轮 tick（节流闸 100 分钟未到，且本批代码量必须过一次真正的全量 L2）。
> ✅ 调度者已用一次性克隆**实跑并入预演**：**0 冲突 + 增量格式闸 exit 0 + allowlist 277/1566 全绿** ⇒ **推不出去的根因已解除**（见 7.5）。

---

## 一、进度（`sync.mjs` 实时值，本轮自己跑的第一件事）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **437**（**94.0%**） |
| 进行中 | 0（派发后 2 条在途） |
| 未开发 | **28** |
| 离全部交付还差 | **28 个** |

分阶段：D7 130 已交付 / 25 未开发；D6 75 / 3；D1–D2 80 / 0；D5 52 / 0；D3 40 / 0；D4 60 / 0。

台账完整性复核：`node tools/module-progress/sync.mjs --check` → **✓ 台账与蓝图一致**（exit 0）；台账 **465 行**、`uniq -d` 为空（无重复 ID）。

> 台账**本轮无 diff**（`merge-ready` 零并入）⇒ commit 里只有本份文档与一份 backlog，「说明文件与台账同一 commit」的约束天然满足（台账路径为 no-op）。

## 二、档位与闸门判定

| 闸 | 判定 | 实测 |
| --- | --- | --- |
| 静默窗口 | 不生效 | `SILENT_WINDOW=0`（全天运行） |
| 强度档位 | 🔴 **reduced（降档）** | 已过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800` ⇒ `LANES=4/2=2`、`THROTTLE_MIN=50×2=100` |
| **批次节流闸** | ✅ **PASS** | 距上批（07:11:57 批次）**168 分钟** ≥ 降档后阈值 **100 分钟** |
| 温度闸 | ✅ GO | `pmset -g therm`：**无** thermal / performance / CPU-power 记录 |
| 配额闸 | ✅ GO | 在途车道 **2** / 配额 6 |
| PG 闸 | ✅ GO | 带 `org.testcontainers.sessionId` 标签的容器 **0** 个 / 上限 6；无标签孤儿容器 **0** 个 |
| load 闸 | ✅ GO | 1min **4.00** / 5min 5.45 / 15min 4.92（10 核，阈值 12）；**未爬升**（1min < 5min） |
| **调度闸** | 🔴 撞闸 → ✅ **本轮原子接管** | 见第三节 |

⇒ **四道机器闸全绿**，唯一阻塞是调度闸 ⇒ 解除后本轮正常走「plan → 派发 → L2 → 三件套」。

### 静态预检（5.1）—— 本批两条车道待并入的 V413 / V414

| 检查项 | V413（18.1.2.2 投递记录） | V414（18.1.1.3 调用监控） |
| --- | --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | **0 命中** | **0 命中** |
| `SET NOT NULL` | **0 命中** | **0 命中** |
| `ADD CONSTRAINT ... CHECK` | **0 命中** | **0 命中** |
| RLS `ENABLE` + `FORCE` 双开 | ✅ 4 张表（`platform_delivery_record` / `_attempt` / `_dead_letter` / `_replay_task`，DO 块循环施加） | ✅ 2 张表（`platform_api_call_log` / `_alert`，逐条显式施加） |
| `tenant_id NOT NULL` | 5 处 | 4 处 |
| 隔离策略 | `tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid` | 同上 |

⇒ 「已知互相破坏形状」三条**全部干净**；两个版本号都是**纯新建表**，并入时不必再预检。
（identity 侧的两个 `V4xx__*_permission.sql` 是权限码 INSERT，不涉 RLS，符合预期。）

## 三、🔓 调度闸死锁：取证 → 原子接管（本轮核心动作）

`gate status` 初始状态：`gate=held`、`ageSeconds=8801`（> TTL 4200，已陈旧）、**`stale=true`**、
但 **`reclaimable=false`**、`inFlightLanes=2`、`buildProcesses=[]`。

### 3.1 协议自带 `gate reclaim` 仍拒绝（已知 P0 缺陷，非本轮引入）

```
node tools/module-progress/lane.mjs gate reclaim --wait 5   →  exit 3
{ "ok": false, "reclaimed": false, "ageSeconds": 9043, "ttlSeconds": 4200,
  "inFlightLanes": 2, "buildProcesses": [], "holderAlive": true,
  "reason": "闸龄 9043s > TTL 4200s，但持闸者仍活着（在途车道 2 条 / 构建进程 0 个）⇒ 不接管，等持闸者自己释放" }
```

根因：`holderLiveness()` 用 `lanes.json` 的 **`state` 字段**判活，而崩掉的 tick 留下的车道永远停在 `running`
⇒ `holderAlive` 恒为 true ⇒ 接管路径**结构性不可达**。
已记在 `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`（round-138 待办 4），
**须单独评估 + 改 `lane-gate-reclaim.itest.mjs` 既有断言，不在流水线 tick 里顺手改**。

### 3.2 七重独立取证（全部实测，非推测）

| # | 证据 | 取值 |
| --- | --- | --- |
| 1 | 持闸 PID | **62980 已死**（`ps -p 62980` → 进程不存在） |
| 2 | 持闸 tick 会话 | 07:00 tick `mvs_8fbe3752677a40a79d1b2ecd3eb12a40` = **`error`（Token Plan 2056）**，末次活动 **08:08:50** |
| 3 | worker 会话（该 tick 派出） | `mvs_f853db8790ba48db8305fa640728e921`（18.1.2.2）、`mvs_a5a5d27708ed4422b70c3a4f12d6dfc9`（18.1.1.3）、`mvs_4c3eedf801384fc2acde2e494df177ff`（18.1.1.3 补派）—— **全部 `error`**，末次活动 **≤ 08:00:40** |
| 4 | 构建进程 | 全仓**无** maven / surefire / vitest / next / playwright 进程 |
| 5 | 在途车道心跳年龄 | 18.1.2.2 **129 分钟** / 18.1.1.3 **131 分钟** > TTL **90 分钟** |
| 6 | 闸龄 | **9064s** > TTL 4200s；最后一次 keepalive 触达 **07:34** |
| 7 | 车 worktree 最后写入 | 18.1.2.2 **08:00:03**（2 小时前）/ 18.1.1.3 **06:11:00**（4 小时前） |

⇒ 持闸 tick 与其 3 个 worker 在 **08:00–08:09 同一时段**被基础设施掐断（Token Plan 用量上限 + HTTP2 ping 失败），
**不是代码问题**；闸成为孤儿。

### 3.3 原子接管（`.dev-agent/takeover-r140.mjs`，gitignored 不入库）

沿用 round-138 已验证的脚本（**逻辑体与 `takeover-r138.mjs` 逐字一致**，仅注释头改为本轮取证），
内置 **5 道取证门**，任何一道不过就拒绝动手：

1. 持闸 PID 不存在；2. 闸龄 > TTL；3. 全仓无构建进程；4. 在途车道心跳**全部**过期；5. 复核「偷到的闸仍陈旧」。

接管手法（关键差异点）：**固定偷取目标 + 单进程 rename** —— 把陈旧闸 `rename` 到固定目标
`.dev-agent/dispatch.gate.steal`（即 `lane.mjs` 自己的 `GATE_STEAL_DIR`）⇒ **结构上杜绝双向持闸**；
`rename → mkdir → 写 owner/keepalive/token` **全在同一个 node 进程内**完成 ⇒ 无 `release → acquire` 竞速窗口。

执行输出：闸龄 9064s ✓、构建进程 0 ✓、僵尸车道 2 条心跳过期 ✓ ⇒ 接管成功。
接管后 `gate status`：`holder=pipeline-tick`、`ageSeconds=0`、`stale=false`；`gate touch` exit 0（持闸者校验通过）。
本轮持闸 PID **93116**，token `93116-1793412312545-8yk5e066`。

## 四、回收与派发

| 步骤 | 结果 |
| --- | --- |
| `merge-ready` | `merged: []` —— **无待并入**（两条车道尚未落地） |
| `gc` | `removed: []` —— 无孤儿可清 |
| `plan --lanes 2 --dry-run` | 零副作用预演通过（`git status` 未变、`lanes.json` 未变） |
| `plan --lanes 2`（实跑） | `toDispatch` 2 条 / **两条 `redispatched: 4`** / `newlyPlanned: []` / `serviceBlocked: []` |

派发的两条车道（**复用既有分支、worktree 与迁移版本号 ⇒ 前任成果原地保留**）：

| 车道 | 模块 | 分支 | 迁移 | 承接的未提交成果 |
| --- | --- | --- | --- | --- |
| **18.1.2.2** | 投递记录 | `feat/18-1-2-2` | **V413** | **22 项**（后端 1,640 行 Service + 4 张表迁移 + 11 个端点 + 租户隔离测试 + 前端工作区） |
| **18.1.1.3** | 调用监控 | `feat/18-1-1-3` | **V414** | **19 项**（真实网关入口 `/open/v1/**` + 监控服务 1,111 行 + 12 个端点 + 2 张表迁移 + 租户隔离测试） |

> ⚠️ `newlyPlanned` 为空**不是「没活干」**：两条孤儿车道已占满 `LANES=2` 的名额；
> 26 个未开发模块全部落在 D6/D7 的已就位服务上（`serviceBlocked` 为空）。

### 双写者风险核查

V413 / V414 **仅**属这两条车道，集成头磁盘上不存在 V413 / V414 文件 ⇒ 可安全并行。
两条分支相对 `origin/integration/module-dev` 均为 **26 behind / 0 ahead** ⇒ 分支上没有任何已落地提交，不存在双写者。

### 派发明细

| 车道 | task id | session | 10:10 实测心跳 |
| --- | --- | --- | --- |
| 18.1.2.2 | `bg_8381d109-5d5c-46aa-aa43-2b07d5d36323` | `mvs_d24b98edd1014f888396505d1039db70` | 10:05:45（**5 分钟前**）✅ |
| 18.1.1.3 | `bg_9ac65c56-923e-4d3e-8cf2-fea63cd94200` | `mvs_bd81b8de954b4e22aa927feeda15aec1` | 10:08:14（**3 分钟前**）✅ |

两份任务书按 `PIPELINE.md` 子任务模板**逐项替换全部 `<...>` 占位符**（绝对路径
`/Users/guan/Projects/starcon-worktrees/integration` / `.worktrees/<id>`），并额外写入了：

- **前任成果清单逐项列出**，并标注「**前任自陈未经核实，必须自己重跑 L0 + L1 复核**」；
- **⛔ 分阶段 commit 硬性要求**（后端 → 契约 → 前端 → 格式修复，每阶段自绿即 commit）——
  这是本轮针对「掐断即全损」的**唯一零代价缓解**（backlog 第 1 条），前 4 任都因为「只在最后 commit」而全损；
- **18.1.2.2**：必须复核「18.1.2.1 ↔ 18.1.2.2 构造器循环依赖」与「18.1.2.1 的两个测试曾因 V413 建表而红」；
- **18.1.1.3**：必须逐项证明网关入口的**真实性**（凭证校验 / 授权范围 / IP 白名单 / 配额限流 / 埋点落库），
  并修掉前任定位到的那例 flaky 时间测试（改成确定性写法，不许用 sleep 掩盖）；
- 两条车道都改了共享白名单 `apps/web/src/lib/api-proxy.ts` ⇒ **必须跑全量 allowlist 回归**
  （`pnpm vitest run apps/web/src/app/api`），否则可能静默吃掉别人已交付的路由。

## 五、L2 结果：🔴 **红**（增量格式闸）

### 5.1 范围判定：产品代码零差异 ⇒ 继承绿证据 + 廉价子集当场重验

集成头 `backend/ apps/ packages/` 相对上次绿灯状态 `b5476e82`：

```
git diff --stat b5476e82 HEAD -- backend/ apps/ packages/   → （空）
git log --oneline b5476e82..HEAD -- backend/ apps/ packages/ → （空）
```

⇒ **产品代码零差异**，且本轮 `merge-ready` 零并入 ⇒ **零新增覆盖**。
此时重跑 2.25 小时的 `mvnw verify` + Next 生产构建**不增加任何信心，只会制造发热高峰**
⇒ 按 round-135 同款策略：**继承绿证据 + 廉价子集当场重验**。

### 5.2 廉价子集实测

| 检查 | 结果 |
| --- | --- |
| `sync.mjs --check` | ✅ exit 0 —— ✓ 台账与蓝图一致 |
| `*.itest.mjs`（17 个） | **15 通过**（含 lane-gate-reclaim、format-gate 10/0、lane-gate 21/0、lane-plan-orphan、merge-union、lane-service-gate、sync-evidence-pipe、ci-gate、remote-lane 等） |
| `merge-summary.itest.mjs` | ❌ **环境问题**：仓库只有 `origin` 远端（`git remote -v` 实测 0 个 `upstream`），itest 要读 `upstream/main` 的台账 ⇒ **非代码回归** |
| `build-capability-map.itest.mjs` | ⚠️ **26 通过 / 1 失败**（「未交付模块的抽屉说明尚未开发」）—— 相关代码 `tools/progress-site/` 自 `b5476e82` 起**零提交**（`git log` 实测为空）⇒ **既有缺陷，非本轮引入** |
| **`format-gate.mjs`（增量格式闸）** | ❌ **exit 1 —— 本轮 L2 红在此** |

### 5.3 增量格式闸判红的归因（与 round-138 同源，但**本轮已完成修复派发**）

```
node tools/module-progress/format-gate.mjs   →  exit 1
{ "batch": { "base": "7fba92b7a2a1", "head": "6f0f7db8bac7", "mergeCount": 6 },
  "changedFiles": 65, "checkedFiles": 32, "violations": 6 }
```

| 文件 | 归属 | 本轮处置 |
| --- | --- | --- |
| `apps/web/src/app/api/platform/webhook-allowlist.test.ts` | 18.1.2.2 正在改 | ✅ 已 `task_append` 派给 18.1.2.2 |
| `apps/web/src/components/integrations/webhooks/webhooks-model.test.ts` | 18.1.2.2 正在改 | ✅ 同上 |
| `apps/web/src/components/integrations/webhooks/webhooks-model.ts` | 18.1.2.2 正在改 | ✅ 同上 |
| `apps/web/src/components/integrations/webhooks/webhooks-workspace.tsx` | 18.1.2.2 正在改 | ✅ 同上 |
| `apps/web/src/lib/api-proxy.ts` | 18.1.1.3 正在改（共享白名单） | ✅ 已 `task_append` 派给 18.1.1.3 |
| `apps/web/src/components/integrations/api-catalog-workspace.tsx` | **18.1.1.2 已交付，worktree 已被 gc ⇒ 无主** | ✅ 已 `task_append` 派给 18.1.1.3 代修（纯格式化，禁止改逻辑） |

**归因链（逐条可复核）**：

1. 6 个文件在集成头 HEAD 上 `pnpm exec prettier --check` **全部报红**（实测）⇒ 不是误报，是真债；
2. `git diff --stat origin/main HEAD -- <6 个文件>` **为空** ⇒ 内容与 `origin/main` **逐字相同**
   ⇒ 债**早已随 batch-116 进 main**，**不是本轮引入**；
3. 闸的批次窗口仍是 `base 7fba92b7a2a1（10-30 13:15）→ head 6f0f7db8bac7（10-31 02:20）`，
   `mergeCount=6` —— **与 round-138 完全一致**，因为本轮**零并入**，窗口没有前进
   ⇒ 闸把「闸诞生前的旧批次」继续算作「本批」（round-138 待办 5 的机制性问题，本轮未变）。

### 5.4 本轮已采取的处置（避免下一批继续红）

- **5 个文件由本批活着的车道在自己的 worktree 内 prettier 化并单独提交**：
  `task_append`（mode=`steered`，随运行中的 turn 投喂）—— 18.1.2.2 负责 4 个 + `api-proxy.ts`，
  18.1.1.3 负责 `api-proxy.ts` + 代修无主的 `api-catalog-workspace.tsx`。
  理由：**下一批一旦并入，这些文件就落在下一批的检查窗口里**；若仍不合规 ⇒ 下一轮 L2 继续红 ⇒ 流水线持续无法 push。
- **调度器自己没有在集成头上改任何文件** —— 协议明写「不要在集成头上改，也不要全仓 `--write`」。

## 六、收尾三件套（本轮结果）

| 件 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件（`docs/sessions/2026-10/2026-10-31-batch-117-pipeline-round140-gate-takeover-zombie-lanes-redispatched.md`） |
| ② commit | ✅ `docs(batch-117): …`（**与台账同一 commit**；台账本轮零 diff ⇒ 台账路径为 no-op） |
| ③ push | ❌ **L2 未绿（增量格式闸 exit 1），本轮不 push**；且两条车道的 12 个 commit（含后端 / 前端 / 契约代码）**尚未并入、未过 L2** ⇒ 推送前必须先并入 + 跑绿 |

> **远端落后 3 个 commit**（本轮之前已落后 2 个：`e7342d42` round-138 说明、`70bc3d62` 集成头同步 main 第 116 轮；
> 本轮 commit 后为 3 个）。三个 commit **全部是 docs-only**，不含任何后端 / 前端代码。
> 按协议「L2 没绿绝对不 push」⇒ 下一批并入两条车道、L2 绿后一并推送。
>
> ⚠️ **两条车道的代码尚未进版本库主线**：`feat/18-1-2-2` 与 `feat/18-1-1-3` 分支上各有 **6 个 commit**
> （合计后端 2,700+ 行、契约 39 个类型、两个前端工作区），**只在车道分支上**，集成头与远端都没有。
> 下一轮 `merge-ready` 并入后才会进 L2 覆盖范围 ⇒ 推送前必须先并入。
> 调度者已用一次性克隆**实跑并入预演**：0 冲突 + 格式闸 exit 0 + allowlist 277/1566 全绿（见 7.5）⇒ 并入本身没有风险。

> 📌 **附带成果**：round-139（07:00 tick）猝死前写的 backlog
> `docs/backlog/2026-10/2026-10-31-worker-session-network-death-loses-uncommitted-work.md`
> （**连续 4 轮同一形态**：worker 被网络掐断 ⇒ 未提交成果全丢）此前一直是 untracked，
> 本轮随说明一并提交入库。

## 七、两条车道在本轮内全部落地（`land` 成功）；并入与 L2 留给下一轮

本轮 **10:18** 与 **10:45** 先后收到两个 worker 的完成通知：**18.1.1.3 与 18.1.2.2 均已 `land` 成功**，
工作区干净、各 6 个 commit 待在集成头并入。

| 车道 | 状态 | head | 与集成头 | 工作区 |
| --- | --- | --- | --- | --- |
| **18.1.1.3** 调用监控 | `ready` | `5484e69e` | 26 behind / **6 ahead** | 干净 |
| **18.1.2.2** 投递记录 | `ready` | `b8bf981d` | 26 behind / **6 ahead** | 干净 |

### 7.1 18.1.1.3「调用监控」交付内容

- **迁移**：`V414__api_call_monitor.sql`（384 行：埋点表 + 告警表 + 跨租户 SECURITY DEFINER 函数 + FORCE RLS + GRANT + 只追加触发器）
  与 `V414__platform_call_monitor_permission.sql`（97 行：2 个权限码 + 补存量 OWNER）
- **端点**：`/platform/call-monitor` 12 个 + `/open/v1` 12 个（真实网关入口）
- **页面**：`apps/web/src/app/integrations/call-monitor/page.tsx` + `call-monitor-workspace.tsx`（938 行，5 页签）
- **测试**：`ApiCallMonitorIntegrationTest` 15 例 + `ApiCallMonitorTenancyIntegrationTest` 11 例 = **26 例**（真实 PostgreSQL 16.15，**连跑 3 轮全绿**）；
  前端 12 例；**allowlist 全量回归 276 文件 / 1560 用例全绿**
- **网关真实性逐项自证**：凭证校验（SHA-256 定位 + 逐行 BCrypt 比对，歧义即拒）、授权范围、IP 白名单（CIDR）、
  配额限流（逐窗口）、埋点落库（只追加，UPDATE/DELETE 被数据层挡下）——
  每项都用「拒绝路径是否落行」的反向断言证明，不是正向 200 就算数

### 7.2 18.1.2.2「投递记录」交付内容 —— 蓝图链上**第一个真的往外发包**的模块

18.1.2.1 此前把「事件实际投递」如实答成未接入（端点恒 409、**一次包都不发**），归的就是这一格。本车道补齐：

- **迁移**：`V413__webhook_delivery_record.sql`（394 行：4 张表 `platform_delivery_record` / `_attempt` / `_dead_letter` / `_replay_task`，
  全部 `tenant_id NOT NULL` + ENABLE **与** FORCE RLS + `GRANT SELECT,INSERT,UPDATE`、**不给 DELETE**）
  与 `V413__webhook_delivery_record_permission.sql`（101 行：登记 `platform.webhook.delivery.read/.publish/.replay`）
- **端点**：`/platform/deliveries` 11 个（`DeliveryRecordController`）+ 真实 HTTP POST 投递
  （`WebhookDeliveryExecutor`：不跟随重定向、响应体读前 1KB）；`WebhookDeliveryPort`（`@Primary`）切断与 18.1.2.1 的构造器循环依赖
- **对 18.1.2.1 的唯一一处越界改动**：能力声明按实际实现改（事件实际投递 / 死信重放 → **true**，outbox relay **保持 false**）；
  `EventSubscriptionIntegrationTest` 三条「未接入⇒409」老断言改为「已接入⇒真发包」，全库子串枚举守卫改为显式表名 + schema 限定的正面断言
- **页面**：`apps/web/src/app/integrations/deliveries/page.tsx` + `delivery-record-workspace.tsx`（4 页签、真实分页、错误态/空态/加载态齐备）+ `delivery-allowlist.test.ts`
- **测试**：L1 **58 例 0 失败**（EventSubscription 25 + DeliveryRecordIntegration 21 + DeliveryRecordTenancy 6 + EventSubscriptionTenancy 6）；前端 25 例；
  **allowlist 全量回归 276 文件 / 1561 用例全绿**
- **抓出并修掉一处真实正则缺陷**：前任的 `api-proxy.ts` 白名单放行了后端**不存在**的 `deliveries/dead-letters/{uuid}`；
  本车道新增的白名单守卫测试把它逮住（变异测试确认：带缺陷的 regex ⇒ 1 例红；修好后 6 例全绿）

### 7.3 flaky 时间测试的根因与修法（值得记录）

根因**不是**时钟不准，而是**配额窗口是离散的**：窗口起点按窗口类型截断（MINUTE ⇒ 截断到分钟），
两次调用一旦跨过分钟边界就落进两个窗口、用量被清零 ⇒ 本该 429 的那次反而放行（概率 ≈ 调用间隔 ÷ 60 秒）。
修法是**注入固定 `Clock`**（照抄 trade 的 `ShipmentWritebackClockConfiguration` 纪律：生产 `Clock.systemUTC()`，
测试用 `@Primary` 的 `Clock.fixed(...)` 覆盖）—— **不是 sleep / 重试 / 放宽断言**；
顺带修掉三处「本来就不该那样」的判定（配额视图与执行必须同一窗口、建议配额读同一窗口、按事实列而非按行序断言）。

### 7.4 格式闸修复进展：6 个违规文件**全部解决**（两个车道各修一半，合并后合齐）

| 文件 | 由谁修 | 所在 commit |
| --- | --- | --- |
| `apps/web/src/lib/api-proxy.ts` | 18.1.1.3 | `6ebf95b3` |
| `apps/web/src/components/integrations/api-catalog-workspace.tsx`（**无主文件**） | 18.1.1.3 代修 | `6ebf95b3` |
| `apps/web/src/app/api/platform/webhook-allowlist.test.ts` | 18.1.2.2 | `0fccde62` |
| `apps/web/src/components/integrations/webhooks/webhooks-model.test.ts` | 18.1.2.2 | `0fccde62` |
| `apps/web/src/components/integrations/webhooks/webhooks-model.ts` | 18.1.2.2 | `0fccde62` |
| `apps/web/src/components/integrations/webhooks/webhooks-workspace.tsx` | 18.1.2.2 | `0fccde62` |

⇒ 每个分支只修了自己那半；**合并后才合齐**（见 7.5 的实测）。

### 7.5 ✅ 下一轮并入的**预演取证**（本轮调度者在一次性克隆里实跑，非推测）

`api-proxy.ts` **不在** `contract-union` 驱动器的覆盖范围内（`.gitattributes` 只覆盖
`packages/api-contracts/src/**` 与台账）⇒ 它走**普通 git merge**，是本批唯一的合并风险点。
调度者用一次性克隆（`.tmp/verify-merge/`，gitignored）按 `merge-ready` 的真实顺序
（`lanes.json` 里 `ready` 车道顺序：**18.1.2.2 先、18.1.1.3 后**）实跑了两次并入：

| 检查项 | 实测结果 |
| --- | --- |
| 并入 18.1.2.2 | **0 冲突** |
| 并入 18.1.1.3 | **0 冲突**（注册 `merge.contract-union.driver` 后，契约由按键求并驱动处理） |
| 合并后 6 个格式违规文件 | **全部 prettier 合规** ✅（含 `api-proxy.ts`） |
| **增量格式闸在合并树上（纯自动识别，与下一轮同一算法）** | **`ok: true`、`violations: []`、exit 0** ✅ |
| **合并树上的 allowlist 全量回归（跨车道互相破坏的经典形状）** | **277 文件 / 1566 用例全绿** ✅（并入前集成头为 275 / 1555；差额 = 两条车道各自的 1 个白名单守卫文件） |
| 合并后 `api-proxy.ts` | 同时含两条车道路由（`deliveries` 44 处 / `call-monitor` 19 处） |
| 合并后契约 | 19 个 `DeliveryRecord*` + 20 个 `ApiCallMonitor*`/`ApiGateway*` 类型**都在**，无丢失 |

⇒ **推不出去的根因（增量格式闸）已被本批解除**；下一轮并入后 L2 的格式闸**预期为绿**。
⇒ 且两条车道各自的白名单守卫测试**在合并后的共享白名单上同时通过** ⇒ 没有「互相吃掉对方路由」的静默破坏。

> 预演方式：一次性克隆（`.tmp/verify-merge/`，gitignored，不入库），
> 注册与集成头一致的 `merge.contract-union.driver` 后实跑 `git merge --no-ff` 两次；
> vitest 以 `--root <克隆>` 运行以确保测的是**合并树**而非集成头。

### 7.6 为什么本轮**不**并入、不跑 L2

1. **节流闸未达**：`last-batch-at` = 10:05，此刻 54 分钟 < 降档后阈值 **100 分钟**
   ⇒ 协议明写「未达阈值 → 本轮直接结束。不取闸、不 plan、不派发、**不跑 L2**」。
   下一格 **12:00**（115 分钟）放行 ⇒ 那一轮做「并入两条车道 → 一次全量 L2 → 绿则 push」。
2. **产品代码已变更**：本批两条车道合起来是**数千行新代码**（后端 2,700+ 行、契约 39 个类型、前端两个工作区），
   必须过一次**真正的全量 L2**（`mvnw verify` + typecheck/lint/test + Next build），不能沿用本轮的「继承绿证据」策略。
3. 两条车道**都已 land、工作区干净**，并入无风险，不需要本轮抢跑。

## 八、吞吐预期（按当前档位现算）

- `LANES=2`（reduced，配置 4 减半） × cron `0 * * * *`（24 次/天）配节流闸 **100 分钟**
  ⇒ 每 2 小时放行一批 ⇒ **约 12 批/天**
- 名义吞吐 = 2 × 12 = **24 个/天**；综合折损系数 0.70（BLOCKED/冲突 0.96 × 上游未就绪 0.95 × L2 返工 0.90 × 长尾占位 0.90）
  ⇒ **理论约 17 个/天**
- 剩余 **28** 个（2 条在途 + 26 待排）⇒ **理论 1.5–2 天**；**诚实区间 2–4 天**，不给单点日期。

## 九、风险提示

- 🔴 **基础设施连续掐断**：过去 3 小时内 **5 个会话**死于 Token Plan 用量上限（2056）/ HTTP2 ping 失败，
  08:00–08:09 一波、09:01 一波、09:20 一波。**这是当前最大的产出瓶颈**，不是代码问题。
  本轮已把**分阶段 commit** 写进任务书，使「掐断 ⇒ 全损」降级为「掐断 ⇒ 最多丢最后一个未 commit 的阶段」。
- ✅ **增量格式闸的批次边界追溯判红问题已缓解**：6 个违规文件已由两条车道各修一半、**合并后实测全部合规**
  （7.5：合并树纯自动识别 ⇒ `violations: []`、exit 0）⇒ **推不出去的根因解除**。
  但**结构性问题仍在**：闸的 base 取「本批最早 merge commit 的第一父」，仍会把闸诞生前的旧 merge 算进窗口
  ⇒ 今后**任何**落在该窗口里的新违规都会再次判红。下一批若又红 ⇒ 走 round-138 待办 5
  （base 锚定到「闸落地之后」的首个 merge），而不是重复派格式修复车道。
- ⚠️ **`holderLiveness()` 的 P0 缺陷未修**（backlog `2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`）
  ⇒ **下一轮若再撞闸，仍需走取证式接管**（或等专门修复落地）。
- ⚠️ **两条车道都改了共享白名单 `apps/web/src/lib/api-proxy.ts`** ⇒ 必须跑全量 allowlist 回归
  （`pnpm vitest run apps/web/src/app/api`），否则可能静默吃掉别人已交付的路由（表现为页面 404，
  而 typecheck / lint / vitest / Playwright **全部全绿**）。
- ⚠️ **18.1.2.2 的前任 note 自述「58 例全绿」属未经验证的 worker 自陈** ⇒ 重派后必须重跑 L0/L1 复核。
- ⚠️ **18.1.1.3 的网关入口是本车道自建的**（全仓原本没有对外网关）⇒ 「真实」只能由它自己逐项证明，
  已写进交付报告的必答项。
- ⚠️ 两条车道的 worker **仍在运行**（心跳 3–5 分钟前）⇒ 本 tick 结束不代表它们结束；
  下一轮 tick 若撞闸，先查心跳再判死。

## 十、后续待办（给下一轮）

1. **两条车道已 `land`（state=ready，各 6 个 commit，工作区干净）** ⇒ 下一轮（节流闸 12:00 放行）
   执行：`merge-ready`（一次收两条）→ `sync` → **一次全量 L2** → **绿则 push**（预计推出 2 个模块 + 12 个 commit）。
   调度者已做过并入预演（7.5）：0 冲突，**格式闸预期为绿**。
2. **格式闸 6 个违规文件已由两条车道各修一半、合并后合齐**（7.4 / 7.5 实测合并树全部 prettier 合规）。
   下一轮若仍红 ⇒ 说明出现了**新的**「无主违规文件」（已交付车道的 worktree 被 gc 后无人可修），
   再不行 ⇒ 派一条**纯格式修复车道**（从 `integration/module-dev` 切新分支，只跑
   `pnpm exec prettier --write <该文件>`，单独提交。**不要全仓 `--write`**）。
3. **若下一批 L2 仍红且定位不到** ⇒ 走 round-138 待办 5：评估 `format-gate` 的 base 选取，
   是否应把 base 锚定到「闸落地之后」的首个 merge。
4. **`holderLiveness()` P0 缺陷**：单独评估 + 改 `lane-gate-reclaim.itest.mjs` 既有断言，
   **不在流水线 tick 里顺手改**。本轮仍走取证式接管。
5. **backlog 第 2 条**（`lane.mjs heartbeat` 记录工作区指纹）与**第 4 条**（判崩依据从「心跳年龄」
   改为「心跳年龄 + 工作区指纹无变化」）与 `holderLiveness()` 修复**共用同一批取证**，可合并立项。
6. `build-capability-map.itest.mjs` 的既有失败（「未交付模块的抽屉说明尚未开发」）—— 判定为既有缺陷，是否单独立项待定。
7. `merge-summary.itest.mjs` 失败是**环境问题**（仓库只有 `origin`、没有 `upstream` 远端），非代码回归。

## 十一、涉及文件

| 路径 | 说明 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-31-batch-117-pipeline-round140-gate-takeover-zombie-lanes-redispatched.md` | 本文件（本轮说明） |
| `docs/backlog/2026-10/2026-10-31-worker-session-network-death-loses-uncommitted-work.md` | round-139 遗留的 backlog（本轮随说明提交入库） |
| `docs/sessions/2026-10/2026-10-31-0500-pipeline-round138-gate-takeover-zombie-lanes-redispatched.md` | 上一轮（同一死锁的第一次解除） |
| `docs/sessions/2026-10/2026-10-31-0400-pipeline-round137-gate-held-dead-tick-zombie-lanes.md` | 上上轮（死锁取证与解死锁四步） |
| `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md` | `holderLiveness()` P0 缺陷 |
| `tools/module-progress/PIPELINE.md` | 落库协议（最高权威） |
| `tools/module-progress/format-gate.mjs` | 本轮 L2 红点 |
| `.dev-agent/takeover-r140.mjs` | 本轮原子接管脚本（gitignored，不入库） |
| `.worktrees/18.1.2.2` / `.worktrees/18.1.1.3` | 两条在途车道的 worktree（复用，未提交成果原地保留） |

## 十二、关联

- `docs/decisions/0004-java-spring-boot-microservices.md`（后端形态）、`docs/decisions/0002-multi-tenancy-strategy.md`（RLS 与租户隔离）
- `docs/product/capability-blueprint/18-integration-and-automation.md`（18.1.2.2 投递记录 / 18.1.1.3 调用监控原文）
- `docs/product/capability-blueprint/module-progress.md`（台账，465 行，本轮零 diff）
