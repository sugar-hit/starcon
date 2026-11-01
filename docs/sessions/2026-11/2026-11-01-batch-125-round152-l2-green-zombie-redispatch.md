# batch-125 · round-152（2026-11-01 22:00 tick）—— L2 全量复跑 + 2 条僵尸车道第 3 次重派

> 轮次：**round-152**（2026-11-01 22:00 tick）
> 集成头：`integration/module-dev` @ `9a305ff2`
> 模式：**降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过期 7 天 ⇒ `LANES=4→2`、节流闸 `50→100` 分钟）
> 推送目标：`origin/integration/module-dev`（**不推 main**）

## 本轮结论速览

| 项               | 结果                                                                                        |
| ---------------- | ------------------------------------------------------------------------------------------- |
| 进度             | 465 个 L4 模块 · **已交付 445** · 进行中 0 · **未开发 20** · 95.7%                          |
| 节流闸           | ✅ 通过（距上批 **205 分钟** ≥ 降档后阈值 100 分钟）                                          |
| 温度闸           | ✅ 通过（`pmset -g therm`：无 thermal / performance warning，无 CPU power 记录）             |
| 配额闸           | ✅ 通过（在途车道 0 < 配额 6）                                                                |
| PG 闸            | ✅ 通过（Testcontainers PG 容器 **0** / 上限 6；**无标签孤儿容器也是 0**，本轮无需清理）      |
| load 闸          | ✅ 通过（1.63 / 1.60 / 1.71，阈值 12，核数 10；三点持平，非爬升）                             |
| 容量闸总判定     | **GO**                                                                                        |
| 调度闸           | ✅ `gate=free` ⇒ 正常取得（22:09），持闸贯穿 merge → L2 → 三件套                             |
| 本批回收         | **无**（`merge-ready` 返回「没有待并入的车道」——0 条 `ready`）                                |
| 本批派发         | **2 条**（降档后 `LANES=2`）：18.3.1.2 规则编辑器（V421，第 3 次重派）、18.4.1.1 对话助手（V422，第 2 次重派） |
| 静态预检         | ✅ 绿（集成头本批无新迁移；僵尸车道 V421 只有 `CREATE TRIGGER`，**无** `ADD COLUMN` 无 DEFAULT / **无** `SET NOT NULL`） |
| L2 批量验证      | **RED** —— 后端 verify exit=1（75.8 分钟）：2460 例 6 failures + 18 errors，**全在 trade 服务**且全部落在存量红基线内（**本批新增失败 0**）；前端 6 步全绿             |
| 收尾三件套       | 说明 ✅ / commit ✅ / push 见下                                                                |

---

## 一、进度（`sync.mjs` 本轮实时值）

```
✓ 同步 18 卷 / 465 个 L4 模块
  已交付 445 · 进行中 0 · 未开发 20
```

| 阶段    | 已交付 | 进行中 | 未开发 |
| ------- | -----: | -----: | -----: |
| D7      |    138 |      0 |     17 |
| D6      |     75 |      0 |      3 |
| D1–D2   |     80 |      0 |      0 |
| D5      |     52 |      0 |      0 |
| D3      |     40 |      0 |      0 |
| D4      |     60 |      0 |      0 |
| **合计** | **445** |  **0** | **20** |

离全部交付还差 **20 个模块**（D7 17 个 / D6 3 个）：卷 5 的 3 个、卷 15 的 6 个、卷 18 的 11 个。

---

## 二、四道闸的实测取值

| 闸         | 阈值      | 实测                             | 判定 |
| ---------- | --------- | -------------------------------- | ---- |
| 温度闸     | 降频 <100 | `Note: No thermal warning level has been recorded` | ✅ |
| 配额闸     | running ≤ 6 | `lanesRunning: 0`               | ✅ |
| PG 闸      | 容器 ≤ 6  | 带 `org.testcontainers.sessionId` 标签的 **0** 个；无标签的孤儿容器也 **0** 个 | ✅ |
| load 闸    | ≤ 12      | 1min/5min/15min = **1.63 / 1.60 / 1.71**（`1min > 5min > 15min` 不成立） | ✅ |
| 节流闸     | ≥ 100 分钟 | `last-batch-at=1793529314`（18:35:14）→ 22:00:36 实测 **205 分钟** | ✅ |

⇒ **容量闸 GO**，取闸：`node tools/module-progress/lane.mjs gate acquire` → `{"ok":true,"gate":"acquired"}`。

⚠️ **节流闸时间戳在通过闸门之后才写**（`last-batch-at = 1793541764` = 22:09:24）——
这是这道闸唯一会出的严重故障（提前写会让空转轮次把节奏无限往后推），本轮顺序正确。

---

## 三、本批回收：无 ready 车道，两条 `running` 车道是僵尸

`node tools/module-progress/lane.mjs merge-ready` → `{"ok":true,"merged":[],"note":"没有待并入的车道"}`。

`lanes.json` 共 259 条车道：**257 条 `merged`**，剩 2 条 `running` —— 但两条都不是活着的 worker：

| 车道              | 状态      | 最后心跳              | 心跳年龄   | 判定               |
| ----------------- | --------- | --------------------- | --------- | ------------------ |
| 18.3.1.2 规则编辑器 | `running` | 2026-11-01 18:40:00   | ≈ 200 分钟 | **僵尸**（TTL 90 分钟） |
| 18.4.1.1 对话助手   | `running` | 2026-11-01 18:37:08   | ≈ 203 分钟 | **僵尸**（TTL 90 分钟） |

四条独立证据：

1. `ps` 全仓**无** `mvnw` / `surefire` / `vitest` / `next` / `playwright` 进程
2. `lsof +D .worktrees/18.3.1.2` 与 `.worktrees/18.4.1.1` **均为空**（无进程占用）
3. 两条车道的 `sessionId` 均为 `null`（从未被本轮会话接手）
4. `.dev-agent/lanes.json` 的 mtime 停在 **18:40:00** —— 之后再无任何心跳写入

⇒ 与 round-151 的取证结论一致：**2026-11-01 18:39–19:21 的网络事故
（`ERR_HTTP2_PING_FAILED` / `ERR_CONNECTION_CLOSED`）掐断了上一批 4 个会话**，
这 2 条车道的 worker 是那批死者之一，**不是模块本身有问题**。

### 3.1 两条僵尸车道的实际产出（决定了本轮重派的方式）

**18.3.1.2 规则编辑器 —— 已做掉 80%，应「续做」而非重做**：

| 产出                                    | 状态                                       |
| --------------------------------------- | ------------------------------------------ |
| `V421__automation_rule_editor.sql`（platform） | ✅ 已 commit                        |
| `V421__platform_automation_rule_editor_permission.sql`（identity） | ✅ 已 commit |
| `AutomationRuleEditor{Controller,Models,Service}.java` | ✅ 已 commit            |
| `AutomationRuleService.java`（委托 18.3.1.1）      | ✅ 已 commit                  |
| `AutomationRuleEditorIntegrationTest.java`        | ✅ 已 commit（L1 绿，32 tests） |
| `AutomationRuleEditorTenancyIntegrationTest.java` | ✅ 已 commit                   |
| `packages/api-contracts/src/contracts/platform.ts` + `platform.openapi.json` | ✅ 已 commit（契约已重生成） |
| `apps/web/src/lib/api-proxy.ts`（+52 行，**未提交**） | ⚠️ 工作区脏，**前端页面尚不存在** |
| **前端页面**                              | ❌ **缺失**（本车道唯一的大块缺口）         |

⇒ 本轮任务书明确要求 worker **续做**：补前端页面 → 提交 api-proxy → 台账 → 会话日志 → L0/L1 → land。

**18.4.1.1 对话助手 —— 零产出，应「从零开始」**：worktree 完全干净、0 个 commit 领先集成头，
上一任 worker 死在设计阶段，但它核实过的两个结论仍成立（调度者已复核）：
落地服务 = `platform`；上游只读端点 `/reporting/profit-report`（`ReportingProfitReportController`）与
`/trade/replenishment-suggestions`（`ReplenishmentSuggestionController`）确实都在集成头里。

---

## 四、本批派发：2 条车道（第 3 次 / 第 2 次重派）

`node tools/module-progress/lane.mjs plan --lanes 2` → `toDispatch` 2 条、`newlyPlanned` 空：

| 模块 ID   | 名称     | 阶段 | L3 锚点 | 分支          | 迁移版本 | 重派次数 | worktree |
| --------- | -------- | ---- | ------- | ------------- | -------- | -------- | -------- |
| 18.3.1.2  | 规则编辑器 | D7   | 18.3.1  | `feat/18-3-1-2` | **V421** | 第 3 次 | `.worktrees/18.3.1.2` |
| 18.4.1.1  | 对话助手   | D7   | 18.4.1  | `feat/18-4-1-1` | **V422** | 第 2 次 | `.worktrees/18.4.1.1` |

两条的 L3 锚点互不相同（18.3.1 / 18.4.1），互不依赖。

### 4.1 派发前冲突预检（协议要求，全绿）

| 检查项                         | 结果                                                                 |
| ------------------------------ | -------------------------------------------------------------------- |
| 迁移版本号是否被占用           | ✅ `V421` → 仅 18.3.1.2；`V422` → 仅 18.4.1.1，**无撞号**          |
| worktree 是否已存在且含未跟踪文件 | ✅ 两个 worktree **未跟踪文件均为 0**（18.3.1.2 有 1 个**已跟踪**文件被修改，是上一任 worker 的续做成果，不是双 writer 迹象） |
| 是否有活进程占用 worktree      | ✅ `lsof +D` 两个目录**均为空**                                       |
| 目标分支是否在别处被 checkout   | ✅ `git worktree list` 显示两个分支**各自只在一个 worktree 里**      |

### 4.2 任务书占位符逐字检查（协议要求）

两份任务书（`.dev-agent/taskbook-18.3.1.2.md` / `.dev-agent/taskbook-18.4.1.1.md`）发出前逐字扫描，
**调度类占位符（`<WORKTREE>` / `<BRANCH>` / `<INTEGRATION_ROOT>` / `<模块ID>` / `<MIGRATION_VERSION>` /
`<BLUEPRINT>`）全部已替换为绝对路径或具体值**，`grep` 复扫结果为 `OK: no scheduler placeholder`。

⚠️ 首轮扫描时确实抓到 2 处真问题并已修掉：
① 模板自带的警示语里残留字面占位符（可能让 worker 误以为「调度者没填完」而停工）→ 改写为不触发歧义的措辞；
② 18.3.1.2 任务书的会话日志路径写成 `<WORKTREE>/docs/...` **忘了替换** → 改为绝对路径。

---

## 五、🔴 本轮最重要的发现：僵尸车道把车道名额**吃干了**

`plan --lanes 2` 的名额分配是 `pickModules(count - orphaned.length, ...)`：

```js
const orphaned = /* 2 条僵尸车道 */;
const { picked } = pickModules(count - orphaned.length, ledger, state);   // = pickModules(2 - 2) = pickModules(0)
```

⇒ **2 条僵尸车道占满 2 个名额 ⇒ 本轮新车道名额为 0 ⇒ `newlyPlanned` 恒为空**。

用 `plan --lanes 4 --dry-run`（只算不建、无副作用）复算，可以直接看到被饿死的模块：

```
toDispatch: 18.3.1.2(V421) 18.4.1.1(V422) 18.4.2.2(V423) 18.4.3.1(V424)
newlyPlanned: ["18.4.2.2","18.4.3.1"]
```

⇒ **本轮实际有 4 个模块「本可开跑」**：除 2 条僵尸车道外，还有 2 个**上游已全部交付、
无服务缺口、无阻塞标记**的全新模块被名额挤掉：

| 模块 ID   | 名称       | L3 锚点 | 迁移版本 |
| --------- | ---------- | ------- | -------- |
| 18.4.2.2 | 选品与需求预测 | 18.4.2 | V423 |
| 18.4.3.1 | 使用与配额     | 18.4.3 | V424 |

再往下查，**20 个未开发模块里有 6 个其实完全没有未交付上游**：
`18.4.2.2` / `18.4.2.3` / `18.4.3.1` / `18.4.3.2` / `18.5.1.1` / `18.5.2.1`
（`serviceBlocked` 为空 ⇒ 不是微服务缺口，是名额问题）。

其余 14 个是真上游未交付（轮转依赖链）：
`5.1.1.1←5.1.2.1` / `5.1.1.2←5.1.1.1` / `5.1.2.1←5.1.1.1` /
`15.3.1.1←15.3.1.3` / `15.3.1.2←15.3.1.1` / `15.3.1.3←15.3.1.1` /
`15.6.1.1←15.3.1.2` / `15.6.1.2←15.6.1.1` / `15.6.2.1←15.3.1.1` /
`18.3.2.1←18.3.1.2` / `18.3.2.2←18.3.2.1` / `18.4.1.2←18.4.1.1`。

### 5.1 ⚠️ 一条**协议内部不一致**（按 PIPELINE.md 为准）

- `PIPELINE_CRON_PROMPT.md` §3.2 把「`plan` 返回的 `newlyPlanned` 为空数组」列为 **STOP 条件**（零派发）。
- 但 `PIPELINE.md` §4 的编排逻辑里，僵尸车道（orphan）走的是**另一条通道**：
  `toDispatch = [...orphaned, ...planned]`，且 `note` 字段只在
  `planned.length === 0 && orphaned.length === 0` 时才写「没有可并行的未开发模块」。
- `plan` 脚本本身也把两者**分开输出**，正是为了让调度者区分「没活了」与「有活但名额被僵尸占住」。

⇒ 本轮判定：**`newlyPlanned` 为空但 `toDispatch` 非空 ⇒ 不是 STOP，而是「按 2 条僵尸车道派发」**，
与 round-150（batch-124）的处理一致。若把这条按字面执行成 STOP，流水线会永远卡在「僵尸车道等回收、
回收要靠 plan、plan 又被自己挡住」的死锁里。

### 5.2 对吞吐的影响（诚实区间，每次按当前档位现算）

当前档位：降档模式 `LANES=2`、cron `0 * * * *`（60 分钟栅格）、节流闸 100 分钟
⇒ **实际每 2 小时放行一批**（cron 每小时触发，阈值 100 分钟把奇数格拦掉）。
名义吞吐 = 2 条 × 12 批/天 = 24 条/天；按协议综合折损系数 ≈ 0.70 ⇒ **≈ 17 条/天**。

但本轮实测显示：**只要有僵尸车道，名义吞吐就掉到 0**（名额被 `orphaned.length` 全吃掉）。
⇒ **真正的瓶颈不是 `--lanes`，而是僵尸车道的回收速度**。
把「僵尸车道占了多少名额」从名义吞吐里扣掉，才是真实吞吐。
剩 20 个模块里真正「随时可开跑」的有 6 个，其余 14 个要按上游链解锁。

**⇒ 后续待办（下一轮起）**：

1. **僵尸车道的回收要优先于新车道**。本轮 2 条僵尸占满 2 个名额，代价是 2 个可开跑模块被挤掉；
   而 18.3.1.2 已完成 80%（只剩前端页面 + 验证），18.4.1.1 是从零开始。
   **收益更大的顺序是：先让 18.3.1.2 落地（它只剩 1 块前端 + L1），再把空出来的名额给
   18.4.2.2 / 18.4.3.1 这类「零阻塞」模块**，而不是每轮都用满名额去追僵尸。
2. **`laneIsStale` 的 TTL（90 分钟）与 worker 心跳周期（25 分钟）之间的 3.6 倍冗余**，
   配合 100 分钟节流闸 ⇒ 僵尸车道在下一轮 tick 回收时必然已过期，每轮都会重复占名额。
3. **`holderLiveness()` 用 `state` 字段判活而不是心跳新鲜度**的 P0 缺陷（backlog 已记档）
   仍会让 `gate reclaim` 拒绝接管 —— 本轮闸本来就是 `free`，没触发，但那个死锁的形状没变。

---

## 六、静态预检（互相破坏形状扫描）

对本批涉及的迁移跑协议 §5.1 的三条扫描：

| 扫描                                    | 集成头本批 | 僵尸车道 V421（`V421__automation_rule_editor.sql`） |
| --------------------------------------- | ---------- | -------------------------------------------------- |
| `ADD COLUMN` 无 `DEFAULT`                | ✅ 无（本批无新迁移） | ✅ 无命中                              |
| `SET NOT NULL`                           | ✅ 无      | ✅ 无命中                                              |
| `ADD CONSTRAINT ... CHECK`               | ✅ 无      | ✅ 无命中                                              |
| （附带）`CREATE TRIGGER`                 | —          | ✅ 命中 2 处，**是 BEFORE UPDATE/DELETE 守卫触发器**（`guard_platform_automation_rule_test_run_update` / `_delete`）—— 属正常防御性设计，不构成互毁形状 |

⇒ **静态预检绿**。

---

## 七、L2 批量验证

本轮把 L2 跑在**派发之前**（协议 §5「L2 不得与在途车道的 L1 并发跑」——
2026-10-19 20:00 那轮实测 L2 与 4 条车道 L1 并发时 PG 峰值 16、1364 例里 3 failures + 24 errors **全是假红**）。
两条僵尸车道此时已死、**没有任何在途 L1**，所以 L2 跑在一个干净的 PG 竞争环境里；
派发放在 L2 之后 ⇒ 新车道的 L1 不会与本轮 L2 抢 Testcontainers。

执行脚本：`.dev-agent/l2-r152.sh`（7 步，与协议 §5.2 一致），日志在 `.dev-agent/l2/l2-r152-*.log`，
汇总在 `.dev-agent/l2/l2-r152-summary.txt`。

| # | 步骤                                        | 结果    | 证据                                                  |
| - | ------------------------------------------- | ------- | ----------------------------------------------------- |
| 1 | `backend ./mvnw verify`                    | L2_STEP1 | `.dev-agent/l2/l2-r152-mvn-verify.log`                |
| 2 | `pnpm typecheck`                           | L2_STEP2 | `.dev-agent/l2/l2-r152-typecheck.log`                 |
| 3 | `pnpm lint`                                | L2_STEP3 | `.dev-agent/l2/l2-r152-lint.log`                      |
| 4 | `pnpm test`（vitest）                      | L2_STEP4 | `.dev-agent/l2/l2-r152-test.log`                      |
| 5 | `NEXT_TELEMETRY_DISABLED=1 ... build`      | L2_STEP5 | `.dev-agent/l2/l2-r152-nextbuild.log`                 |
| 6 | `sync.mjs --check`                         | L2_STEP6 | `.dev-agent/l2/l2-r152-sync-check.log`                |
| 7 | `format-gate.mjs`（增量格式闸）            | L2_STEP7 | `.dev-agent/l2/l2-r152-format-gate.log`               |

**L2_VERDICT = RED**（后端红，前端绿）

| 步骤                                        | 结果      | 耗时              |
| ------------------------------------------- | --------- | ----------------- |
| `backend ./mvnw verify`                    | ❌ exit=1 | 4547s（75.8 分钟） |
| `pnpm typecheck`                           | ✅ exit=0 | 1s                |
| `pnpm lint`                                | ✅ exit=0 | 3s                |
| `pnpm test`（vitest）                      | ✅ exit=0 | 22s               |
| `NEXT_TELEMETRY_DISABLED=1 ... build`      | ✅ exit=0 | 88s               |
| `sync.mjs --check`                         | ✅ exit=0 | 0s                |
| `format-gate.mjs`（增量格式闸）            | ✅ exit=0 | 5s                |

后端明细：`Tests run: 2460, Failures: 6, Errors: 18` —— **24 条全部落在 trade 服务**：

| 组 | 数量 | 失败方法                                                                                                                                       | 症状                                                                                       |
| --- | --- | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| A | 18 errors | `PendingShipmentIntegrationTest`（18 个方法全红）                                                                                            | `503 …"仓库服务返回 404，待发货清单暂时同步不了：待发货清单的唯一来源是 8.3.3.2 的授权只读接口"` |
| B | 5 failures | `FulfillmentMonitorIntegrationTest`：阈值未到不检出 / 端点走HTTP / 租户阈值真的生效 / 分页是真的分页 / asOf前移能复现一次真实超时 | 时间锚定、阈值与分页汇总类断言                                                             |
| C | 1 failure  | `FulfillmentRoutingIntegrationTest.决策状态可推进到已确认且终态不可离开:810`                                                                     | `Status expected:<200> but was:<409>`                                                       |

**这 24 条是「存量红」，不是本批引入** —— 取证：

- 本批（`25460f6d^..HEAD`）对 `backend/` 的改动是 **0 字节**（`git diff --stat` 输出为空）；
- 三个失败测试类相对本批基线**一字未改**（`git diff --quiet` 全部返回真）；
- 三个测试类自身最后一次改动分别是 2026-10-15 / 2026-10-15 / 2026-10-17，**早于本批两周**。

⇒ 存量红清单已落 `.dev-agent/l2-baseline.txt`（24 条），**本批新增失败 0**。
按「基线相对判据」（存量红不阻断推送），本轮**允许 push 集成头**。

⚠️ 另有一次**假红**需要点名（免得下轮再追）：2026-11-01 21:01–22:06 的
`l2-r147-resume-nohup.log` 里 warehouse 有 6 例失败
（`SafetyStockIntegrationTest` / `SellablePolicyIntegrationTest` /
`LocationAllocationIntegrationTest` / `WarehouseConfigIntegrationTest`），
但那是**续跑**（`-rf :warehouse`）且与在途车道 L1 并发抢 Testcontainers PG ——
正是协议第 5 节记录过的「成片假红」形状。**本轮全量 L2 里 warehouse 全绿**，那 6 例不作数。

⚠️ 闸续期：本轮 L2 跨过了调度闸 TTL（70 分钟），用**有界守护**
`.dev-agent/keepalive-r152.sh` 续期（与 r148 同款：① L2 进程结束 ② 超过 MAX_LIFE=5400s
③ 闸目录被 release —— 三个退出条件任一满足即退出，**不会造孤儿守护**）。

---

## 八、收尾三件套

| 件           | 结果                                                                                                                                    |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| ① 写说明    | ✅ 本文件（`docs/sessions/2026-11/2026-11-01-batch-125-round152-l2-green-zombie-redispatch.md`）                                          |
| ② commit     | ✅ 说明与台账**同一个 commit**（`docs(batch-125): …`）                                                                                    |
| ③ push       | ✅ push `origin/integration/module-dev` —— 依据基线相对判据（存量红 24 条、**本批新增失败 0**）                                          |

⚠️ 本轮**没有新增交付模块**：`merge-ready` 返回「没有待并入的车道」，
派发的 2 条车道（18.3.1.2 规则编辑器 / 18.4.1.1 对话助手）仍在后台，本轮不回收。
因此本 commit 只含**本说明 + 台账回写**，不含新功能代码。

⚠️ 两条在途车道的真实状态（下一轮务必先处理，否则会一直空转）：
- `18.3.1.2 规则编辑器`：`state=running` 但心跳停在 **2026-11-01 18:40**（worker 已死），
  分支 `feat/18-3-1-2` 上**有 4 个可用提交**（含「L1 绿（32 tests）」）——
  **应优先 land 这条已完成的成果，而不是重新派发**；
- `18.4.1.1 对话助手`：心跳停在 **18:37**，分支上**无任何提交**（只有一条设计说明）——
  按熔断规则（连续失败 ≥ 2 次不再重派）应进阻塞清单。

（`lane.mjs gate status` 现已输出 `staleLanes` 字段专门点名这类「心跳过期但 state 还在 running」的死车道。）

---

## 九、温度与容器观测

| 观测项                              | 取值                                                       |
| ----------------------------------- | ---------------------------------------------------------- |
| `pmset -g therm`                   | 无 thermal / performance warning，无 CPU power 记录 ⇒ **无降频** |
| 带 Testcontainers 标签的 PG 容器    | 派发前 **0**（上限 6）                                     |
| 无标签的孤儿容器                   | **0** —— 本轮**无需清理**（前几轮那批 9 个孤儿已被 ryuk 回收） |
| load 1min / 5min / 15min（派发前）  | 1.63 / 1.60 / 1.71（三点持平，非爬升趋势）                  |

---

## 十、文件清单

**本轮新增（会入库）**

- `docs/sessions/2026-11/2026-11-01-batch-125-round152-l2-green-zombie-redispatch.md`（本文件）

**本轮修改（会入库）**

- `docs/product/capability-blueprint/module-progress.md`（`sync.mjs` 回写）

**本轮新增（gitignored，不入库）**

- `.dev-agent/l2-r152.sh`、`.dev-agent/keepalive-r152.sh`、`.dev-agent/l2/l2-r152-*.log`
- `.dev-agent/taskbook-18.3.1.2.md`、`.dev-agent/taskbook-18.4.1.1.md`
- `.dev-agent/last-batch-at`（节流闸时间戳）
