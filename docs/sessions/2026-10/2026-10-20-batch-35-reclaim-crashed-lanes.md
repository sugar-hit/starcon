# batch-35 派发轮 —— 四条崩掉的车道原位重派（10.3.1.1 / 10.6.1.1 / 10.7.1.1 / 5.2.1.1）

> **本文件是「派发轮」记录，不是「交付」记录。**
> 本轮派发的 4 条车道仍在各自 worktree 中开发，**尚未落地、尚未并入集成头、尚未跑 L2**。
> 每模块的技术说明（迁移 / 端点 / 页面 / 测试）要在车道落地 + L2 绿之后另写一份 `batch-35` 说明文件，
> 届时以那份为准。本文件只记录**本轮做了什么、查到了什么、以及下一轮必须知道的事**。

## 一、本轮动作

| 项 | 结果 |
| --- | --- |
| 同步台账 | 465 个 L4 模块：已交付 **234** / 进行中 0 / 未开发 **231**（完成率 50.3%） |
| 强度档位 | `full`（当前 2026-10-20 < `TOKEN_HORIZON` 2026-10-25）⇒ LANES=4，节流闸 50 分钟 |
| 节流闸 | 距上批 **60 分钟** ≥ 阈值 50 分钟 ⇒ 放行（通过后才写 `last-batch-at`） |
| 调度闸 | `gate status` = free → `gate acquire` 成功 |
| 回收 | `merge-ready` 返回空（无待并入车道）；`gc` 无可回收 worktree |
| 取证 | **4 条车道的 worker 会话全部 `error: net::ERR_CONNECTION_CLOSED`** —— 全崩，不是「在跑」 |
| 派发 | **4 条车道原位重派**（`redispatched: 1`），全部续写各自 worktree 里已存在的未提交代码 |
| L2 | **未跑**（见第六节） |
| 收尾三件套 | 说明 ✅ / commit ✅ / push ✅（**仅文档**，无代码） |

### 容量闸判定：GO

| 闸 | 阈值 | 实测 | 判定 |
| --- | --- | --- | --- |
| 温度闸（最高优先级） | 有降频项 < 100 即 STOP | `No thermal warning level has been recorded` | ✅ 通过 |
| 配额闸 | running ≥ 7 即 STOP | 派发前 running = **4**（配额 6） | ✅ 通过 |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的 ≥ 6 即 STOP | **0** | ✅ 通过 |
| load 闸 | 1min > 核数（10）即 STOP | 3.06 / 2.40 / 2.20（1min 略升但在阈值下） | ✅ 通过 |

孤儿容器：按 `ancestor=postgres:16-alpine` 过滤后**带标签与不带标签的均为 0** —— 无历史遗留孤儿容器需要点名清理。
机器上另有一批 `crewhub-*` 容器（属另一项目，非本仓资源），不计入 PG 闸口径。

## 二、🔴 本轮最重要的取证：4 条车道的 worker 全部崩了

上一轮（batch-34，11:00 派发）的 4 个 worker **没有一条跑到落地**，而且**不是「还在跑」——是会话直接崩了**：

| 车道 | worker 会话 | 状态 | 最后活动 |
| --- | --- | --- | --- |
| 10.3.1.1 轨迹查询 | `mvs_be1a8093…` | `error: net::ERR_CONNECTION_CLOSED` (50113) | 11:20:40 |
| 10.6.1.1 成本分析 | `mvs_f46edac4…` | `error: net::ERR_CONNECTION_CLOSED` (50113) | 11:16:05 |
| 10.7.1.1 退货处置 | `mvs_9c8c1703…` | `error: net::ERR_CONNECTION_CLOSED` (50113) | 11:16:08 |
| 5.2.1.1 成本构成 | `mvs_b503f3b8…` | `error: net::ERR_CONNECTION_CLOSED` (50113) | 11:16:06 |

父会话 `mvs_6eca7dc4…`（batch-34 的调度者）本身也是 `error: net::ERR_CONNECTION_CLOSED`。

旁证三条，互相独立：
1. **无心跳** —— 四条车道最后一次心跳在 11:00–11:10，心跳周期 25 分钟 ⇒ 已超期 40+ 分钟；
2. **无文件写入** —— 四个 worktree 里最后一个源文件写于 11:17，此后无任何改动；
3. **无进程** —— `ps aux` 里没有 java / maven / surefire 进程，4 个 worker 都没在跑测试。

⇒ 结论：**这 4 条车道是僵尸车道，不是运行中的车道**。它们在 `lanes.json` 里仍是 `state=running`，
但背后已经没有人了。

## 三、回收与重派的决策：等 60 分钟认领宽限期，而不是立刻强派

`lane.mjs` 的孤儿判据是两层（`laneIsStale` 90 分钟 / `laneIsUnclaimed` 60 分钟且 `sessionId` 为空）。
本轮开工时四条车道的无心跳时长分别是 **63.1 / 59.6 / 55.8 / 52.9 分钟** ——
**只有 10.3.1.1 越过了 60 分钟的认领宽限期**，另外三条还要再等 3～6 分钟。

本轮的处理：**在集成头原地等 6 分钟让宽限期自然到期，然后一次性让 `plan` 用脚本自己的逻辑回收全部 4 条**。
没有手改 `lanes.json`、没有 `abandon`、没有手动调时间戳。

为什么不不等满 6 分钟就派新模块（那样 `plan` 会只回收 1 条、新排 3 条）：

- **会留下 3 条僵尸车道**：`state=running` 且 `sessionId` 为空的车道会被 `pickModules` 的 `taken` 集合排除，
  它们占着 10.6.1.1 / 10.7.1.1 / 5.2.1.1 三个模块的坑位，直到下一轮（13:00）才被回收；
- **会把在途数顶到 7**，越过 `RUNNING_QUOTA=6` 的配额线；
- 下一轮就变成「4 条活 + 3 条僵尸 = 7 在途」，两轮后必然顶到 4 条并发上限之外。

等满宽限期后 `plan --lanes 4` 的结果正是想要的：**4 条全部 `redispatched: 1`，`newlyPlanned: []`，
在途数保持 4**（≤ 配额 6，≤ runtime 并发上限 4），四条车道全部续写自己 worktree 里的半成品。

### 🔧 顺手修掉根因：本轮 `plan` 带上了 `--session`

batch-34 派发时执行的是 `plan --lanes 4`，**没有带 `--session`** ⇒ 四条车道的 `sessionId` 恒为 `null`。
`laneIsUnclaimed` 的判据是「`sessionId` 为空 **且** 距上次心跳超过 60 分钟」——
`sessionId` 为空时，**一条活着的车道也会在 60 分钟后被误判成孤儿**（这正是 `lane.mjs` 里
2026-10-07 那段注释描述的老坑：每轮 `plan` 都会把这 4 条当成孤儿重派，双 writer）。

本轮改成 `plan --lanes 4 --session mvs_4e3d50b9e7dc4a1599d89199173ef7c8`，
四条车道的 `sessionId` 全部落上本会话 id ⇒
- 活着的车道**不会**再被 60 分钟误判规则当成孤儿；
- worker 真崩了，则由 90 分钟的 `laneIsStale` 兜底回收 —— 这才是想要的两层语义。

## 四、本批派发的 4 条车道（原位重派，worktree 与迁移版本号都不变）

| 模块 ID | 名称 | 阶段 | L3 锚点 | 后端服务 | 迁移版本 | 分支 | 页面路由 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 10.3.1.1 | 轨迹查询 | D5 | 10.3.1 | channel | **V212** | `feat/10-3-1-1` | `/logistics/tracking` |
| 10.6.1.1 | 成本分析 | D5 | 10.6.1 | channel | **V213** | `feat/10-6-1-1` | `/logistics/analytics` |
| 10.7.1.1 | 退货接收与处置决策 | D5 | 10.7.1 | channel | **V214** | `feat/10-7-1-1` | `/logistics/returns` |
| 5.2.1.1 | 成本构成 | D6 | 5.2.1 | catalog | **V215** | `feat/5-2-1-1` | `/pricing/cost` |

迁移版本号**不变**（V212–V215 是 batch-34 预分配的，4 个迁移文件都还在原 worktree 里），
**没有新烧版本号**（batch-34 预留给 5.2.1.2 / 5.2.2.1 / 5.2.3.1 的 V216–V218 本轮未使用）。

### 上一轮留下的半成品（本轮各车道的实际起点）

| 车道 | 已存在的文件 | 完成度 | 还缺什么 |
| --- | --- | --- | --- |
| 10.3.1.1 | 8 个（迁移 419 + Models 290 + Service 1476 + Controller 177 + Port 120 + 2 个承运商适配器 + 测试 1062） | **最高**，后端基本写完 | 前端页面、api-proxy 白名单、契约、台账、会话日志；测试文件**未经编译验证** |
| 10.6.1.1 | 5 个（迁移 578 + Models 427 + Service 1432 + 2 个 Port） | 后端服务层写完 | **Controller**、集成测试、前端、api-proxy、契约、台账 |
| 10.7.1.1 | 5 个（迁移 912 + Models 605 + Service 1870 + Controller 286 + Port 207） | 后端基本写完 | 集成测试、前端、api-proxy、契约、台账 |
| 5.2.1.1 | 3 个（迁移 407 + Models 356 + Port 467） | **最低**，只到模型与端口 | **Service、Controller**、集成测试、前端、api-proxy、契约、台账 |

⚠️ **四条车道上一轮都还没碰过 `apps/web/src/lib/api-proxy.ts`，也都没建前端页面** ——
batch-34 记录里「全部改了 api-proxy.ts」的说法**不成立**（那只是当时计划，本轮实测四条车道的
`git status` 里都没有该文件）。⇒ 四个插入锚点**全部还空着**，本轮四条车道会去抢同一批锚点，
锚点隔离比上一轮更关键。

## 五、静态预检（互相破坏形状扫描）—— **全部干净**

本批 4 个迁移文件虽然还没并入集成头，但文件就在 worktree 里，可以提前扫：

| 迁移 | `ADD COLUMN` 无 DEFAULT | `SET NOT NULL` | 新增 `CHECK` | CREATE TABLE | RLS 覆盖 |
| --- | --- | --- | --- | --- | --- |
| V212 `logistics_tracking_query` | 0 ✅ | 0 ✅ | 0 ✅ | 4 | 4/4 ✅ |
| V213 `logistics_cost_analysis` | 0 ✅ | 0 ✅ | 0 ✅ | 4 | 4/4 ✅ |
| V214 `return_receiving_disposition` | 0 ✅ | 0 ✅ | 0 ✅ | 7 | 7/7 ✅ |
| V215 `sku_cost_composition` | 0 ✅ | 0 ✅ | 0 ✅ | 3 | 3/3 ✅ |

四个迁移**全部只建新表，没有一条 ALTER 既有表** ⇒ 协议里「加了列但没给 DEFAULT」与
「改成必填且目标表无 BEFORE INSERT 触发器兜底」这两种**必然违约**的形状一条都不存在。

RLS 覆盖是逐表核过的：V212/V213/V215 走 `DO $$ … FOREACH tbl IN ARRAY ARRAY[…] LOOP` 批量
`ENABLE + FORCE ROW LEVEL SECURITY` + `CREATE POLICY isolation`（沿用 V1/V93/V129/V134/V136/V141 的既有写法）；
V214 的 7 张表是**两段策略** —— 6 张走 isolation + data_scope，
`return_resale_restriction` **刻意只挂 isolation 不挂 data_scope**（迁移第 756 行的注释写明理由）。
⇒ 19 张表全部 `tenant_id NOT NULL` + RLS 到位。

## 六、L2 本轮未跑 —— 理由（不是偷懒）

1. **无可验证内容**：`merge-ready` 返回空，本批 4 条车道**一条都没落地**，集成头相对 `c011f12e` 零变更。
   L2 的定义是「对**并入之后**的集成头做全量验证」；没有并入就没有验证对象。
   集成头现有代码本身在 batch-33 已通过 L2 全绿，且**已经在 `origin/integration/module-dev` 上**。
2. **跑了反而会制造假红**：协议明确禁止「L2 与在途车道的 L1 并发跑」——
   多套 Testcontainers PG 互相抢资源，2026-10-19 20:00 那轮实测 PG 峰值 16，后端 1364 例里 3 failures + 24 errors
   **全是假红**（`seed()` 503 / 连接拒绝），机器空闲后隔离复跑全绿。
   本轮有 **4 条车道的 L1 正在跑**，此刻启动 L2 恰好命中这个已知故障形状。

⇒ L2 留到下一轮：4 条车道落地 → `merge-ready` → 静态预检复扫 → **L2 全量跑一次** → 绿了才写逐模块说明、才 push。

## 七、收尾三件套状态

| 件 | 状态 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ | 本文件 `docs/sessions/2026-10/2026-10-20-batch-35-reclaim-crashed-lanes.md`（**派发轮记录**）。逐模块技术说明待 L2 绿后另写 `batch-35` 说明文件。 |
| ② commit | ✅ | 说明文件与台账**同一个 commit**，未拆开。 |
| ③ push | ✅ | 只 push `integration/module-dev`，**绝不 push main**。 |

⚠️ **本轮 push 的内容是纯文档，零代码** —— 4 条车道的代码此刻仍在各自 worktree 里，
**既没有并入集成头，也没有经过 L2**。L2 没绿不 push 的红线**没有被触碰**：
被推上远端的集成头代码，与 batch-33 已全绿并已在远端的代码**逐字节相同**。

## 八、后续待办（下一轮 tick）

1. `merge-ready` 回收 10.3.1.1 / 10.6.1.1 / 10.7.1.1 / 5.2.1.1 四条车道（若已 `land`）。
2. **静态预检复扫**本批 4 个迁移（V212–V215）并入后的形态。
3. **L2 全量跑一次**：`backend && ./mvnw verify` → `pnpm typecheck && pnpm lint && pnpm test` →
   `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `node tools/module-progress/sync.mjs --check`。
4. L2 绿 → 写**逐模块**说明（`batch-35`）→ `git add` 说明 + 台账 → 单个 `docs(batch-35):` commit → push。
   L2 红 → 不 push，按协议二分定位（本批 4 条车道，最多 2 次全量），定位后派修复任务。
5. **`plan` 必须继续带 `--session`**（本轮已修，见第三节）。否则活着的车道会在 60 分钟后被误判成孤儿重派。
6. 注意 **RUNNING_QUOTA=6 与 LANES=4 的组合**：本轮在途 4 + 配额 6 ⇒ 若下一轮还想满配 4 条，
   必须先确认上一批已回收；在途 4 + 派 4 = 8 会越配额线。**这是配置本身的结构，需要用户决定是否调 `RUNNING_QUOTA`**（调度器不得自行改）。
7. `gc` 回收已合并车道的 worktree。

## 九、吞吐预期（按当前配置现算，不引用历史文档里的过期数字）

- 当前档位：`CRON=0 * * * *`（每小时整点，24 触发点/天）+ `THROTTLE_MIN=50`（60 分钟栅格留 10 分钟落盘余量）⇒ **实际约 1 批/小时**
- 名义吞吐 = 4 车道 × 24 批/天 = **96 车道/天**
- 折损系数 ≈ 0.70（BLOCKED 0.96 × 不可调度 0.95 × L2 返工 0.90 × 长尾占位 0.90）⇒ 理论 ≈ **67 模块/天**
- **实测更可信**：近 7 天 `merge(` 车道并入数 = **12 ~ 23 条/天**（10-14: 23、10-15: 15、10-16: 12、10-17: 12、10-18: 12、10-19: 19、10-20: 23）
- 按实测区间 **12–23 模块/天**、剩余 **231** 个 ⇒ 约 **10–19 天**（诚实区间，不给单点日期）
- 若 L2 连续红、或长尾车道占位加剧，实际会落到区间下沿

## 十、风险提示

1. **🔴 worker 会话被网络错误整体打掉**（本轮 4/4 全崩，`net::ERR_CONNECTION_CLOSED`）。
   这不是单次偶发：会话列表里近几轮还有 `net::ERR_NETWORK_CHANGED`、`net::ERR_HTTP2_PING_FAILED`
   两种同类错误。**4 条车道 4 个 worker 同时死**，说明是**上游模型服务/网络侧的问题**，不是代码问题。
   本轮已用「原位重派 + 不丢半成品 + `--session` 修根因」把损失压到最小，
   但**下一轮仍有可能整批再崩一次** —— 若再次发生，重复本轮的取证与回收即可，不要当成回归。
2. **四条车道的上一轮成果都在未提交状态**。本轮任务书已明确要求「能用的留下，不要 reset / clean」，
   但**若 worker 再次崩溃**，半成品会再次留在 worktree 里 —— 下一轮继续原位重派即可，**不要 abandon 重建**。
3. **本批 4 条车道全部要改 `api-proxy.ts`，且锚点全部还空着**（上一轮没改过）——
   本轮四条车道的插入点两两被既有条目隔开（10.1.1.2 / 10.1.1.3 / 10.1.2.1 / 10.2.2.2 / 2.5.2.1），
   git 应能干净求并。但**L2 前的 allowlist 回归**（`pnpm vitest run apps/web/src/app/api/trade`）
   是唯一能挡住「取错一侧静默删路由」的闸 —— 四条车道的 L1 必须真跑它。
4. **三条车道的上游真值都在别的服务**（trade / warehouse），跨服务端口在集成测试里需要 WireMock 替身；
   替身逻辑若被误写进生产代码，L2 抓不到但会长期污染跨服务边界纪律。
5. **finance 服务缺位**影响面已达 5 个模块（10.6.1.1 / 10.7.1.1 / 5.2.1.1 + 后续 12 财务系列）。
   若 finance 长期不落地，跨服务对账会成为累积缺口 —— 建议提上日程。
6. **6.3 采购单全部未开发** —— 5.2.1.1 的「采购成本来自 06 采购的**实际单价**」当前**取不到**。
   已在任务书里点名「不得把报价当实际价」。

## 十一、🚨 补记（12:29–12:35）：5.2.1.1 车道二次崩溃并原位重派

本文件 12:14 提交、12:15 推送之后，**5.2.1.1 车道的 worker 在 12:29 再次崩溃**：
`net::ERR_HTTP2_PING_FAILED`（与本轮 4/4 全崩的 `net::ERR_CONNECTION_CLOSED` 同族）。

**崩溃时它的成果（已全部留在 worktree，未提交）**：

| 文件 | 行数 |
| --- | --- |
| `SkuCostCompositionService.java` | 1397 |
| `CostBasisPort.java` | 476（它修过：`nz()` 补 String 重载、duty 行补 `uom`、修分页截断漏洞） |
| `SkuCostCompositionModels.java` | 396 |
| `SkuCostCompositionRules.java` | 235（成本法加权引擎） |
| `SkuCostCompositionController.java` | 168 |
| `SkuCostCompositionRulesTest.java` | 149（**死在写这个文件的过程中，很可能是半截**） |
| `CostBasisConfiguration.java` | 38 |
| `V215__sku_cost_composition.sql` | 407 |

**L0 编译闸在崩溃前是绿的** —— 它已经把继承代码里的编译错（字符串续行缺 `+`、`nz()` 无 String 重载）修完，
并把 `deliveries` 方法改成正式实现、修了 list / detail / snapshot 三处调用点。

### 为什么不等下一轮 tick 回收，而是立刻重派

本轮 `plan` 带了 `--session` ⇒ 5.2.1.1 的 `sessionId` 非空 ⇒ `laneIsUnclaimed` 判 false
⇒ 它**只会在 90 分钟无心跳后**（约 13:45）被 `laneIsStale` 回收。
而下一轮 tick 在 **13:00**：那一刻 4 条车道在 `lanes.json` 里都是 `running`（3 活 + 1 僵尸），
`plan --lanes 4` 会认为「没有孤儿」而**新排 4 个模块** ⇒ 在途变成 **8 条，越过 `RUNNING_QUOTA=6`**，
且 5.2.1.1 的半成品（后端已编译通过、只剩测试与前端）**白等 30+ 分钟**。

⇒ 本轮在集成头**重新取闸 → 原位重派 5.2.1.1 → 立刻 release 闸**（不占住 13:00 那格）。
在途保持 4（3 活 + 5.2.1.1 重派），零超额。

### 🔴 顺带纠正上一轮任务书里的一处**错误说法**

第二个 worker 在核对 schema 时发现调度者的任务书写错了，**本轮已核到 V137 迁移原文并纠正**：

| 表 | 实际是什么 | 上一轮任务书错写成 |
| --- | --- | --- |
| `vendor_delivery_record`（V137） | **到货事实表**：`delivery_ref`（采购单号/到货单号，业务引用键，非外键）、`sku_id`、`quantity`、`amount numeric(19,4) NOT NULL`、`currency`。`amount` 注释原文写的是「**采购金额**」 | ❌「那是报价，不是实际成交价」 |
| `supplier_quote` / `supplier_quote_price_tier`（V32） | 供应商报价（分档） | （未点名） |

⇒ 纠正后的口径（已写进重派任务书）：**报价是 `supplier_quote`，到货金额是 `vendor_delivery_record`；**
但**到货金额 ≠ 采购单下单金额**（6.3 采购单仍未开发，没有权威下单金额可对账），
所以页面仍必须**明示每个分项的来源**（实际到货金额 / 报价 / 参数 / 不可用），
**既不把报价当实际价，也不把到货金额说成采购单金额**。

### 补记后的车道状态（12:35）

| 模块 | 状态 | 进度 |
| --- | --- | --- |
| 10.3.1.1 轨迹查询 | 运行中 | 继承的 6 个 Java + V212 编译通过，测试文件 test-compile 通过；正在补多租户三断言 |
| 10.6.1.1 成本分析 | 运行中 | V213 迁移修好（PL/pgSQL RAISE 语法），服务层与端口编译通过，Controller 已写，正在跑集成测试 |
| 10.7.1.1 退货处置 | 运行中 | 后端 + 迁移编译通过，**集成测试 26/26 绿** |
| 5.2.1.1 成本构成 | **已重派**（第三个 worker） | 继承 8 个文件，规则引擎与规则单测完整，正在复核 L0 编译闸 |

⚠️ **本补记同样不改变 L2 的结论**：4 条车道一条都没落地，集成头代码零变更，
推上远端的仍是 batch-33 已 L2 全绿的那份代码。

## 十二、✅ 补记二（12:36）：**10.3.1.1 已 land** —— 回收路径产出了真实交付

10.3.1.1 轨迹查询的 worker 在 12:36 **跑完并 `land` 成功**，成为本批第一条 ready 车道：

```
land → {"ok": true, "lane": "10.3.1.1", "head": "de7e98d", "branch": "feat/10-3-1-1"}
commit → bfaa3255 feat(channel) + de7e98d8 docs
```

⇒ **本轮「等宽限期 → 脚本自身回收 → 原位重派」这条路走通了**：一条被崩掉的僵尸车道，
靠续写前一 worker 的半成品，**在 22 分钟内产出了一条完整的 ready 车道**（继承 8 个文件 → 补完测试、
前端、契约、台账、会话日志 → L0+L1 全绿 → commit → land）。这直接回答了本轮开头那个问题：
**回收机制不是「把车道的坑填回去」，是真能出交付。**

### 它接手时的基线：17 例 / 1 失败 / 10 报错 —— 三个真根因

| 根因 | 性质 | 修法 |
| --- | --- | --- |
| `push` 的 INSERT 占位符错位（18 个 `?` 只传 17 个绑定，`?::jsonb` 挪到了 `registered_by_membership_id` 上） | **真 bug**，卡死承运商推送这条唯一真实写入口 | 重排绑定参数 |
| 4 处 `timestamptz` 列漏写 `?::timestamptz` | **真 bug**（AGENTS.md 明令的静默 −28800s 陷阱） | 补 cast |
| 测试把 `"DE"` 传给了 `nodeType` 位置，且断言写 2 票而夹具实际 3 票 | 测试写错 + 缺一个筛选维度 | 补 `destinationCountry` 筛选维度并改计数 |

外加**补完上一 worker 只声明了从未使用的 `actorB`** —— 三条多租户断言原封未写，
按同仓 10.1.1.3 的 tenancy 模式补齐（两租户互不可见 / 伪造 tenant header 无效 / 池归还后不串租户）。

### 两个值得下一轮知道的判断

1. **`TrackingEventRow` 与 warehouse 撞名** —— codegen 以「跨服务同名 DTO：字段级覆盖」中止。
   worker 改名 `CarrierTrackingEventRow`，理由是同名会让 warehouse 独有的 `fromStage/toStage`
   被本模块字段**悄悄顶掉且编译期不报错**。⇒ **下一批若有模块也叫 `TrackingEventRow`，直接改自己那个，不要接受覆盖。**
2. **契约 codegen 会重写全部 6 个服务的文件**（springdoc 注册顺序变化导致路径重排，
   实测对 channel/trade 均为**纯重排、零内容变化**）。worker 只提交 channel 的两份、其余 5 个还原，
   与上一条车道提交（`367f0147`）形状一致。⇒ **多车道同时改契约时，并入大概率撞 diff，
   但只要是纯重排就不是内容冲突** —— 下一轮 merge 时别把它当回归。

### 落地后的静态预检复扫（V212 最终形态）

| 项 | 结果 |
| --- | --- |
| `ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / 新增 `CHECK` | **0 / 0 / 0** ✅ |
| ALTER 既有表 | **0** —— 只建 4 张新表，跨车道破坏面为零 ✅ |
| FK 动作 | 4 处全 `ON DELETE RESTRICT`（worker 把 `CASCADE` 改掉了：撞 append-only 触发器本就是死代码） |
| 去重硬约束 | `UNIQUE (tenant_id, carrier_code, carrier_event_id)` ✅ 蓝图加粗句落在 DB 层 |
| RLS | 4 张表全 `ENABLE + FORCE` + `isolation` policy ✅ |

### 下一轮 tick 的实际入口

- `merge-ready` 将会拿到 **10.3.1.1 这 1 条**（其余 3 条仍在跑）⇒
  **L2 的验证对象是「1 条车道并入后的集成头」**，不是 4 条；
- L2 仍**不得**与在途车道的 L1 并发跑 —— 10.6.1.1 / 10.7.1.1 / 5.2.1.1 的 L1 在跑，
  **要么等它们落地再跑 L2，要么等它们空闲**；
- 10.3.1.1 的**逐模块技术说明**在 L2 绿后写进 `batch-35` 说明文件（与另外三条一起）。

## 十三、✅ 补记三（12:40）：**10.7.1.1 也已 land** + 🔴 两条车道并入冲突预探

10.7.1.1 退货接收与处置决策的 worker 在 12:40 跑完并 `land` 成功
（`22a55b38` backend → `0b4e0188` 契约 → `9dde2289` 前端 → `d3b45462` 台账+日志，4 个 atomic commit）。
⇒ **本批已有 2 条 ready 车道**（10.3.1.1 / 10.7.1.1），另 2 条在跑。

| 件 | 落地位置 |
| --- | --- |
| 迁移 | `V214__return_receiving_disposition.sql` — 7 表 + 9 触发器 + RLS + GRANT/REVOKE |
| 端点 | `/channel/return-dispositions/**` — 14 个 |
| 页面 | `apps/web/src/app/logistics/returns/`（page + model + model.test + workspace） |
| 测试 | `ReturnDispositionIntegrationTest` 26 例 + `ReturnDispositionTenancyIntegrationTest` 3 例 = **29 例 0 失败** |
| 验证 | L0 compile exit 0 ｜ typecheck 3/3 ｜ lint 3/3 ｜ 前端 2 文件 20 例绿 ｜ **api-proxy 回归 45 文件 238 例 → 扩到 105 文件 634 例全绿** |

**它接手时基线也是红的**（与 10.3.1.1 同款）：上一 worker 的 `cancelOrder` UPDATE 有 **5 个占位符只绑 4 个参数**
（取消任何处置单必然失败）、`registrations()` 计数 SQL **漏了 lateral join**（`dispositionState` 筛选直接语法错）。
两处都是**编译期看不见、运行期才炸**的真 bug。worker 还从零补了全部测试、前端、api-proxy、契约、台账。

### 「处置方式必须显式」的落地：五道结构闸（不是一句提示语）

1. **列上不写 DEFAULT** —— 三张表的 `disposition_method` 都是 `varchar(24) NOT NULL CHECK(... IN ('RESALE','RETURN_TO_COUNTRY','DISCARD'))`，
   少传一个 `dispositionMethod` ⇒ **23502 not-null violation**，不是悄悄落成「转销售」；
2. **决策 ↔ 处置单两段分离** —— 处置方式落在决策上（谁/何时/依据），触发器钉死方法一致、数量一致、只能消费一次；
3. **决策终局性** —— 已消费或被取代 ⇒ 方法/数量/依据/时间戳不可变；
4. **可再售否决 ×2 并冻结到决策行** —— 上游结论必须 `RESALABLE`（**完全没有结论也判拒**）且无生效中的品牌/平台限制；
5. **拿不准 ⇒ 显式落 `PENDING_DECISION`（待人工决策）**，绝不默认成可再售。

静态预检复扫 V214 最终形态：`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / 新增 `CHECK` **全为 0**，
**零 ALTER 既有表**（只建 7 张新表，跨车道破坏面为零），
`DEFAULT 'RESALE'` 全文件**只出现 1 次 —— 在第 24 行的注释里**，那段注释正是解释「写一个
`DEFAULT 'RESALE'` 就正好是蓝图点名禁止的那种默认」。

### 🔴 两条车道并入冲突预探：**已证无冲突，但发现一个结构性盲区**

`.gitattributes` 给并行车道注册了 `contract-union` 合并驱动器的路径**只有三类**：
`packages/api-contracts/src/**/*.ts`、`packages/api-contracts/src/*.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`。

⚠️ **共享白名单文件 `apps/web/src/lib/api-proxy.ts` 不在其中** —— 它走**普通文本合并**。
而本批 **10.3.1.1 与 10.7.1.1 两条车道都改了它**，且插入锚点**相邻**：

| 车道 | 插入位置 |
| --- | --- |
| 10.3.1.1 | 10.1.1.2 渠道配置（`logistics-channels`）之后、10.1.1.3（`logistics-transit-limits`）之前 |
| 10.7.1.1 | 10.1.1.3（`logistics-transit-limits`）之后、10.1.2.1（`freight`）之前 |

⇒ 相邻 hunk 有可能撞上。**本轮用一次性 probe worktree 实测了真实的顺序并入**
（`git merge --no-ff` 逐条并，**不碰集成头**，事后已删除 probe worktree）：

```
merge 10.3.1.1 → Automatic merge went well，冲突 0
merge 10.7.1.1 → Automatic merge went well，冲突 0
api-proxy.ts 最终形态：相对集成头 **88 insertions / 0 deletions**
  两条车道的条目都在（logistics-tracking 18 处、return-dispositions 17 处引用）
台账：merge-union 按模块 ID 折叠 465 行同 ID 记录（设计行为）
```

⇒ **并入是干净的，但这是「测出来的」，不是「结构保证的」。**
**下一轮及以后：凡是多车道同批改 `api-proxy.ts`，并入前都该做一次同样的 probe**，
否则撞冲突会让 `merge-ready` 退出码 2、整批白等一轮（协议明写：不要自己强行解冲突）。

### 下一轮 tick 的实际入口（更新）

- `merge-ready` 将会拿到 **2 条**（10.3.1.1 / 10.7.1.1）⇒
  **L2 的验证对象是「2 条车道并入后的集成头」**（不是 1 条，也不是 4 条）；
- **L2 仍不得与在途车道的 L1 并发跑** —— 10.6.1.1 / 5.2.1.1 的 L1 在跑
  （10.6.1.1 上一轮已报「Controller 已写，正在跑集成测试」），
  **要么等它们落地再跑 L2，要么等它们空闲**；
- 10.3.1.1 与 10.7.1.1 的**逐模块技术说明**在 L2 绿后一起写进 `batch-35` 说明文件。

## 十四、✅ 补记四（12:52）：**5.2.1.1 已 land**（第三个 worker）+ 三车道并入预探

5.2.1.1 成本构成的**第三个 worker**（12:29 那次崩溃后重派的）跑完并 `land` 成功：
`c218dbda` feat(catalog) → `6ba35172` test(catalog) → `6e3b5e7e` feat(web) →
`33667346` chore(contracts) → `fa68e115` docs，5 个 atomic commit，工作区干净。

⇒ **本批已有 3 条 ready 车道**（10.3.1.1 / 10.7.1.1 / 5.2.1.1），1 条在跑（10.6.1.1）。

| 件 | 落地位置 |
| --- | --- |
| 迁移 | `V215__sku_cost_composition.sql` — `sku_cost_profile` / `sku_cost_adjustment` / `sku_cost_revision`，`tenant_id NOT NULL` + `ENABLE/FORCE RLS` + isolation/data_scope 双策略 |
| 端点 | `/catalog/sku-cost-compositions/**` — 7 个（能力声明/列表/明细/保存/追加调整/作废/历史） |
| 页面 | `apps/web/src/app/pricing/cost/page.tsx` + `apps/web/src/components/pricing/cost-composition-{model.ts,cost-composition-workspace.tsx}` |
| 测试 | `SkuCostCompositionRulesTest` 9 例 + `SkuCostCompositionIntegrationTest` 16 例 = **25 例 0 失败** |

### 它接手时基线也是红的 —— 4 个真 bug，且两个直接打在蓝图加粗句上

1. **`CostBasisPort` 累加器 NPE** —— `new BigDecimal[2]` 的元素是 `null`，`acc[0].add()` 在**第一张单据上就炸**
   ⇒ 头程与关税分项永远取不到（上一轮修的分页截断漏洞正好把它掩盖住了）；
2. **🔴 快照窗口只有 1 天**（明细页是回溯 90 天）⇒ 历史里 `purchase_cost` 恒为 `null`、
   `snapshot_complete` 恒 false ⇒ **「同一 SKU 今天显示毛利 30% 明天 25% 无法解释」这条蓝图加粗句
   在历史里根本不可见** —— 成本法反例查不出来；
3. `UPDATE ... SET effective_from=?` 缺 `?::timestamptz` ⇒ 保存构成整条 500（INSERT 侧有转换才没暴露）；
4. **乐观锁没校验客户端回传的 version** ⇒ 过期客户端能静默覆盖别人的成本。

另补：调整原因/作废理由的**服务层校验**（返回 400 而不是撞 DB CHECK 拿 500）。

### 「成本法」与「采购成本来源」的落地

- **成本法**：`sku_cost_profile.cost_method` 枚举（`MOVING_AVERAGE` / `FIFO` / `LOT`，**只增不改**，
  且会进历史快照 `sku_cost_revision.cost_method`）+ `SkuCostCompositionRules` 三法加权
  （`BigDecimal`、scale 4、`HALF_UP` 钉死），**列表/明细/历史三处共用同一份实现**；
  历史留痕 = 每次变更 append 一条 `sku_cost_revision`（当时成本法/币种/五个分项当时值/合计/生效时间/原因），
  **无 UPDATE 端点**；页面明示位置 = `/pricing/cost` 顶部「成本口径（必看）」块 + 明细头部。
- **采购成本来源**：四类来源在页面上**分开标**（实际到货金额 / 供应商报价（未成交）/ 头程发运单 / 关税估算单 /
  人工参数 / 手工调整）。采购成本 = `vendor_delivery_record`（V137）**实际到货金额**按成本法加权 ——
  **不是** 6.3 采购单成交价（未开发）；`supplier_quote` 只作只读参考**单列、永不计入合计**。
  取不到时包装成本显示「未维护」、上游不可达显示「取不到」+ 503，**绝不显示 0.00**。

### 静态预检复扫 V215 最终形态

| 项 | 结果 |
| --- | --- |
| `ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / 新增 `CHECK` | **0 / 0 / 0** ✅ |
| ALTER 既有表 | **0** —— 只建 3 张新表 ✅ |
| **跨服务外键** | **0** ✅ —— 没有一个 FK 指向 warehouse/trade/channel/identity，模块边界纪律守住 |
| 成本法枚举 | profile 与 revision 两处都钉死三值白名单 ✅（成本法会进历史快照） |

### 🔴 三车道顺序并入预探：**无冲突**

5.2.1.1 也改了 `apps/web/src/lib/api-proxy.ts`（`catalog/` 前缀）⇒ 再次用一次性 probe worktree
实测真实顺序并入（**不碰集成头**，事后已删除）：

```
merge 10.3.1.1 → 冲突 0
merge 10.7.1.1 → 冲突 0
merge 5.2.1.1  → 冲突 0
api-proxy.ts 最终形态：相对集成头 **115 insertions / 0 deletions**
  三条车道的条目都在（logistics-tracking 18 处、return-dispositions 17 处、sku-cost-compositions 8 处）
```

⇒ 三车道同批改共享白名单，**实测仍不撞**。但这再次说明上一节那条结论：
**api-proxy.ts 不受 `contract-union` 保护，并入干净与否只能靠实测，不能靠结构保证。**

### 下一轮 tick 的实际入口（再更新）

- `merge-ready` 将会拿到 **3 条**（10.3.1.1 / 10.7.1.1 / 5.2.1.1）⇒
  **L2 的验证对象是「3 条车道并入后的集成头」**；
- **L2 不得与在途 10.6.1.1 的 L1 并发跑** —— 它上一轮已报「后端 22 例绿、前端 22 例绿、
  typecheck/lint 绿、契约已重生成、台账与会话日志已写，正在跑 channel 全量回归」，**离落地只差最后一步**；
- 三条车道的**逐模块技术说明**在 L2 绿后一起写进 `batch-35` 说明文件。

## 十五、✅ 补记五（13:04–13:09）：**10.6.1.1 已 land** + 🚨 挖出一个 `lane.mjs` 真缺陷

10.6.1.1 成本分析的 worker 在 13:04:31 跑完并 `land` 成功：
`e7a7cd83` feat(logistics) —— 「全口径物流成本报表 + 头程分摊明细」。

| 件 | 落地位置 |
| --- | --- |
| 迁移 | `V213__logistics_cost_analysis.sql` — 5 表 + 6 个护栏触发器 + RLS + GRANT，纯新建、零 ALTER 既有表 |
| 端点 | `/channel/logistics-cost-analysis/**` — 10 条（capabilities/overview/by-channel/over-cost-orders/allocation-rules/allocations/…/parameters/…） |
| 页面 | `/logistics/analytics` → `app/logistics/analytics/page.tsx` + `components/logistics/logistics-cost-analysis-{model,workspace}.ts(x)` |
| 测试 | `LogisticsCostAnalysisIntegrationTest` 22 例（真实 PG16 + Testcontainers）＋ 前端 model 17 例 ＋ allowlist 5 例 |

**验证**：L0 compile exit 0 ｜ typecheck 3/3 ｜ lint 3/3 ｜ **L1 22 例 0 失败** ｜
api-proxy 回归 99 文件 589 例 ｜ **channel 全量回归 959 例 0 失败**（验证 V213 迁移无副作用）｜
PG 容器：verify 期间同时存活 1 个，跑完归零（已被 ryuk 回收）。

### 它接手时基线是「迁移根本跑不起来」

上一 worker 的 V213 里有 **4 处 PL/pgSQL `RAISE …, USING ERRCODE=` 语法错误**
⇒ 迁移直接语法错，**整个模块无法落库**；另有触发器变量名与列名同名导致
`column reference "rule_code" is ambiguous`；两个 Port 的 `ObjectMapper` **缺 `JavaTimeModule`**
（`OffsetDateTime` 反序列化直接炸）；`TRADE_URL` 默认端口写成 8082（那是 catalog 的端口，trade 是 8085）。
服务层还踩了蓝图加粗句正中间的那个坑：**未登记运费的票会造出 0 元币种行** —— 把缺项当 0，
正是「物流成本常被低估」的成因。worker 把它改成：合计永远带 `includedComponents`/`missingComponents`，
**一个分量都没读到时合计是 `null` 而不是 0**，`coverage.complete` **恒为 false**，
这份报表从不自称全口径。仓储费本仓无任何计费事实（8 仓储作业未交付）⇒ 显式标「暂无数据 + 归属模块」。

### 🚨 挖出一个 `lane.mjs` 真缺陷：`plan` 用陈旧快照回写，**静默撤销并发发生的 land**

10.6.1.1 的 `land` **返回 `{"ok": true}`，但落地标记根本没进 `lanes.json`** ——
13:08 读状态时它仍是 `state=running / headSha=null`，而同批另外 3 条都是 `merged`。
**13:00 tick 的 `merge-ready` 因此只并入了 3 条，把 10.6.1.1 漏掉了。**

根因（精确时序）：

| 时刻 | 事件 | 对 `lanes.json` 的影响 |
| --- | --- | --- |
| 13:02:14 | 13:00 tick 的 `plan` **读取**状态 | 内存快照里 10.6.1.1 = `running`（worker 还没落地） |
| 13:03:44 | worker **commit** `e7a7cd83` | — |
| 13:03:46–13:03:59 | plan 建 4 个 worktree（每条跑一次 `pnpm install`，各约 4–5 秒） | — |
| ~13:03:5x | worker 调 `land` → 读、改成 `ready`、**写盘成功** | 文件此刻**确实是** `ready` ✅ |
| **13:03:59** | plan 跑完循环，**用 13:02:14 的陈旧快照回写整个文件** | 💥 **`ready` 被静默还原成 `running`** |

`plan` 的 `readState()` 与 `writeState()` **隔着整个 plan 的执行时间**（本轮 105 秒），
而它是「读整份 → 改 → 写整份」。**写盘比 `land` 晚 1–15 秒，但写的是旧内容，于是把新内容覆盖掉了。**

**为什么严重**：
1. **完全静默** —— `land` 返回 `ok: true`、无日志、退出码 0，没有任何地方报错；
2. **命中窗口随 batch 规模增长** —— 车道越多、`pnpm install` 越慢，窗口越宽；
3. **不止 `land`，`heartbeat` 同样会被吞** —— 心跳被覆盖 ⇒ 活着的车道被判失联 ⇒ 被回收重派 ⇒
   **双 writer 写同一个 worktree**（这正是 `lane.mjs` 里 2026-10-07 那段注释描述的老坑的**另一条触发路径**）；
4. **可量化** —— 不补回的话，10.6.1.1 要到 **14:19 之后**才会被回收重派，重派后还要再跑一遍 L0/L1。

**本轮怎么止血**：确认三道闸都过（worktree dirty=0、HEAD `e7a7cd83` ≠ 基线 `969a983`、分支正确）后，
在集成头重跑 `land --lane 10.6.1.1` → 标记恢复 `ready`。
此时 13:00 tick 的 `plan` 已跑完、不再有并发写入，且 `gc` 不写 `lanes.json`、`merge-ready` 也不会再跑 ⇒ 安全。

⚠️ **本轮没有改 `lane.mjs`** —— 13:00 tick 正在集成头上跑 L2，改 `lane.mjs` 会让工作区变脏；
而 13:00 tick 的 commit 只 `git add` 说明文件与台账、**不会带上 `lane.mjs`**，
改动会留成脏文件 ⇒ **14:00 tick 的 `merge-ready` 会在「主工作区不干净，拒绝切分支」处直接退出**，
整批并入被堵死。⇒ 缺陷留给下一轮**专门**修。

完整根因分析、三个候选修法（推荐：`plan` 写盘前重新读一次状态、只叠加自己新建的车道）、
以及必须补的并发集成测试，写在
[`docs/backlog/2026-10-20-lane-plan-stale-state-clobbers-land.md`](../../backlog/2026-10-20-lane-plan-stale-state-clobbers-land.md)。

### 🔴 顺带发现：13:00 tick 的 `plan` **又漏了 `--session`**

13:00 tick 新建的 4 条车道（10.3.1.2 / 10.3.2.2 / 10.6.2.1 / 10.7.1.2）`sessionId` **全是 `null`** ——
batch-35 明确写过「`plan` 必须继续带 `--session`」，13:00 tick 没照做。
`laneIsUnclaimed` 的判据是「`sessionId` 为空 **且** 距上次心跳 > 60 分钟」，
`sessionId` 为空时**活着的车道也会在 60 分钟后被误判成孤儿**并被重复重派。
⇒ backlog 里建议把 `--session` 做成 `plan` 的默认行为（从已有状态继承），而不是靠每个 tick 记得手工传。

### 下一轮 tick 的实际入口（最终）

- `merge-ready` 将会拿到 **4 条**（10.3.1.1 / 10.7.1.1 / 5.2.1.1 / 10.6.1.1）⇒
  **L2 的验证对象是「4 条车道并入后的集成头」** —— 本批 4 条全部交付；
- 13:00 tick 已经在跑 L2（3 条版本），14:00 tick 会再跑一次（4 条版本）；
  **L2 不得与在途 4 条新车道的 L1 并发跑** —— 等它们落地或空闲；
- **先修 `lane.mjs` 的 plan 写盘缺陷**（或至少确认 14:00 tick 不会踩到同一个坑：
  14:00 tick 的 `plan` 若在 4 条新车道的 `land` 落盘窗口内跑，同样会吞掉标记）；
- 4 条车道的**逐模块技术说明**在 L2 绿后一起写进 `batch-35` 说明文件。
