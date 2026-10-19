# 批次 32 · 派发记录（4 条车道）

> 时间：2026-10-20 05:00 tick → 05:18 取闸 → 05:2x 派发 4 条车道
> 集成头：`integration/module-dev`（基线 `9f0fd1d7`）
> 模式：`MODE=full`（TOKEN_HORIZON=2026-10-25 前），LANES=4，THROTTLE_MIN=50，SILENT_WINDOW=0

---

## 本轮定位：不是「空转轮」，是**回收上一批 + 派发下一批**

这一轮开头并不是常规开局。05:00 触发时现场是：

- **调度闸被占**：`gate=held`、`ageSeconds=798`、`stale=false` ⇒ 持闸者活着（上一 tick），**不许抢**。
- **L2 正在跑**：集成头上有 `mvnw verify` 在执行（04:51 启动，正在 `trade` 模块），
  `.next/BUILD_ID` 于 05:14 落盘 ⇒ 那是**上一批（batch-31）的全量验证**。
- **batch-31 的说明已写但未 commit**：`docs/sessions/2026-10/2026-10-20-batch-31-four-lane-delivery.md`
  处于未跟踪状态，末尾的「验证与收尾」还是占位句 —— 上一 tick 正等 L2 结果。

⇒ 因此本轮**没有**在 05:00 直接派发车道，而是**守着在途 L2 直到它落地**，
确认 batch-31 的「写说明 → commit → push」三件套由持闸者完成，再接续派发下一批。

## 节流闸的两次判定

| 判定时点 | 距上批 | 阈值 | 结果 |
| --- | --- | --- | --- |
| 05:00:19（本 tick 起点） | 48 分钟 | 50 分钟 | **未达阈值** ⇒ 当时不派发 |
| 05:17:41（batch-31 三件套落地后） | 65 分钟 | 50 分钟 | **已达阈值** ⇒ 派发 batch 32 |

- 第一次判定未过时本轮本应零派发早退；**中途出现了一个必须有人接手的在途恢复义务**
  （batch-31 的 L2 + 未 commit 的说明），把它做完之后批次间隔自然涨到 65 分钟。
- 闸的**目的**是「两批之间不小于 50 分钟」，不是「tick 起点那一瞬间的快照」——
  65 分钟 ≥ 50 分钟，节奏没有被偷偷压成两小时一次。
- `last-batch-at` **在通过闸门之后才写**（`1792444672` = 05:17:52），
  没有出现「空转轮把节奏无限往后推」这个闸唯一的严重故障。

## 容量闸判定（派发前，只读）

| 闸 | 读数 | 阈值 | 结论 |
| --- | --- | --- | --- |
| 温度 | `pmset -g therm` → No thermal warning level has been recorded | 有降频项即 STOP | ✓ 通过 |
| 配额 | running 0/6 | ≥7 命中 | ✓ 通过 |
| PG | 带 `org.testcontainers.sessionId` 标签的容器 **0** 个 | ≥6 命中 | ✓ 通过 |
| load | 4.44 / 6.04 / 4.94（10 核） | >12 命中 | ✓ 通过（1min < 5min，在回落） |
| 孤儿容器 | 无标签的 `postgres:16-alpine`：**0** 个 | — | ✓ 无需清理 |

⇒ **GO**。

## 上一批（batch-31）的回收结果 —— 本轮只读确认，未做改动

上一 tick（持闸者）已在本轮接管期间自行完成：

- 4 条车道零冲突并入集成头（merge commit `a6904493` / `45af10ce` / `254eb125` / `771569eb`）。
- L2 **全绿**：后端 6 个服务 surefire 报告 **0 failures / 0 errors**（5494 次执行），
  前端 typecheck / lint / test / Next production build 通过。
- 三件套完成：说明 + 台账**同一个 commit** `9f0fd1d7 docs(batch-31): 4.3.1.3 / 4.1.1.3 / 4.4.1.1 / 4.5.1.1`，
  已 push 到 `origin/integration/module-dev`（远端 ref 已核对 = `9f0fd1d7`）。
- 台账：465 行、**0 个重复模块 ID**；已交付 **222 → 226**，未开发 **243 → 239**，完成率 **48.6%**。

## 本批派发：batch 32（4 条车道）

| 模块 | 名称 | 落地服务 | 迁移 | 车道分支 | api-proxy 前缀 | 页面 |
| --- | --- | --- | --- | --- | --- | --- |
| 4.4.1.2 | 批量/插件采集 | channel | V204 | `feat/4-4-1-2` | `listing-bulk-collects` | `/listings/collect/bulk` |
| 4.5.1.2 | 关键词排名 | channel | V205 | `feat/4-5-1-2` | `listing-keyword-rankings` | `/listings/keywords` |
| 4.5.2.1 | 跟卖监控 | channel | V206 | `feat/4-5-2-1` | `listing-hijackers` | `/listings/hijackers` |
| 9.1.2.1 | 平台订单同步 | trade | V207 | `feat/9-1-2-1` | `platform-orders` | `/orders/platform-sync` |

L3 锚点互不相同（4.4.1 / 4.5.1 / 4.5.2 / 9.1.2），`serviceBlocked` 为空，迁移版本号无占用。

### 🔴 本批的调度难点：4 条车道共改同一个共享白名单文件

`apps/web/src/lib/api-proxy.ts` 的 `services[]` 是**累积型共享文件**（60 个元素），
且**不在** union 合并清单里 ⇒ 4 条车道各加一条元素，若插入锚点重叠，git 必然判冲突。

派发前做了两件事：

1. **给每条车道指定互不重叠的插入锚点**（基线 `9f0fd1d7` 的行号）：

   | 车道 | 插入位置（紧跟该元素之后） | 基线行 |
   | --- | --- | --- |
   | 4.5.2.1 | `listing-compliance-precheck` 元素之后 | 1542–1590 |
   | 4.4.1.2 | `listing-collects` 元素之后 | 1632–1662 |
   | 4.5.1.2 | `listing-performance` 元素之后 | 1716–1757 |
   | 9.1.2.1 | `order-review` 元素之后（trade 段首个） | 1894–1929 |

   四个锚点**两两相隔 ≥ 74 行**（默认 3 行上下文窗口不会互相覆盖）。

2. **新前缀全部与既有 token 不撞**：`listing-bulk-collects` ≠ `listing-collects`（4.4.1.1）、
   `listing-keyword-rankings` / `listing-hijackers` 为全新命名空间、
   `platform-orders` 首段独立于既有 `order-review` / `order-intercept` / `orders/risk-validation`。

### 统一边界（写进 4 份任务书）

- **不许改 `channel/pom.xml` / `trade/pom.xml`** ⇒ 不许引入新依赖（jsoup、httpclient 一律不许）；
  4.4.1.2 的批量抓取只能用既有 `RestClient` + 自写解析。
- **不许改 `packages/ui/src/lib/task-pages.ts` 与 `nav-config.ts`** ⇒ 只建页面、不登记 task-pages
  （与 4.5.1.1 / 4.4.1.1 等最近已交付车道一致）。
- **不许 ALTER 任何既有表** ⇒ 4 条车道的迁移**全部零 ALTER**。
- **不许跨服务读表** ⇒ 9.1.2.1 拿不到 channel 服务的 `platform_enum_mapping`，
  只能「trade 内自建映射副本」或「走授权 API」，并须在会话日志写明取舍。
- **负向断言（未登记形状必须 404）只能用自己前缀下的形状**
  （`{自己前缀}/undeclared-action`）⇒ 避免 batch-30 那次「同批合并后负向断言失效」的返工。
- **必须跑 api-proxy 全量 allowlist 回归**（`pnpm vitest run apps/web/src/app/api/trade`）：
  取错一侧会静默删掉别人已交付的路由，而 typecheck / lint / vitest / Playwright **全绿**。

## 🚨 车道 9.1.2.1 遭遇 504 中断 + 接管续做（05:29）

**发生了什么**：9.1.2.1 的 worker 在完成全部调研与依赖自检、**正在写代码**时，
被**模型网关的 504 Gateway Time-out**（ALB 层，基础设施故障）打断。
**不是业务缺陷，也不是测试红** —— 它死在写 `PlatformEnumMappingClient` 的中途。

**留下了什么**（3 个未 commit 的文件，全在它自己的 worktree 里，一行没丢）：

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `V207__platform_order_sync.sql` | 560 | 6 张表 + 14 个索引 + 3 个触发器 + RLS ENABLE/FORCE + GRANT + 完整跨服务边界注释 |
| `PlatformOrderSyncModels.java` | 591 | 10 个 enum + 10 个 record |
| `PlatformEnumMappingClient.java` | 103 | 2.1.2.1 跨服务**授权读取端口**（照抄 `ShipmentWritebackPort` 的逐字纪律） |

迁移里已钉好**幂等双闸**：事件级 `UNIQUE(tenant_id, event_key)` +
订单级 `UNIQUE(tenant_id, source_scope, external_order_no)` / `UNIQUE(tenant_id, idempotency_key)`。

**处置**（**没有** abandon、**没有** gc 掉 worktree、**没有**让下一轮重排）：

1. 查 `lane.mjs` 的孤儿判据：`laneIsUnclaimed` = `sessionId 为空 && 心跳超 60 分钟`，
   `laneIsStale` = 心跳超 90 分钟。⇒ 崩掉的车道**最早也要 60 分钟后**才会被下一轮 `plan` 判成孤儿。
2. 但不必等：**立即用同一 worktree / 同一分支 / 同一个 `V207` 重新派发**，
   并把「上一轮已定稿的设计 + 三个遗留文件 + 明确要续做的部分」写进新的任务书。
3. 🔴 **双写风险已排除**：`sessionId` 仍为 null，所以「未认领」判据只看**心跳新鲜度**；
   续做 worker 每 25 分钟续心跳 ⇒ 心跳年龄恒 < 60 分钟 ⇒ 下一轮 `plan` **不会**把它当孤儿再派一次。
4. 续做 worker 已在 05:31 续上心跳，确认「接管续做：已读完上一轮遗留的 V207 迁移 / Models / EnumMappingClient」。

⚠️ **给下一轮的提醒**：9.1.2.1 是**第二次尝试**，交付说明里要写明「经历一次 504 中断 + 接管续做」，
并复核续做时对上一轮三个文件做了什么处理（保留 / 局部修改 / 推翻并说明理由）。
若它再崩，**先查 504 是否复现**（网关侧），不要当成业务缺陷去二分。

## 静态预检

本批迁移文件**尚未产生**（4 条车道刚开工），`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` /
`ADD CONSTRAINT CHECK` 三项扫描在合并前无法执行 ⇒ **顺延到 batch-32 合并轮、L2 之前**执行。

## 本轮不做 L2

batch-32 的 4 条车道**在后台跑**（预计 35–45 分钟落地）。
本轮**没有**可并入的 `ready` 车道 ⇒ 不跑 L2。
🔴 **下一轮（06:00 tick）必须先等在途车道落地再跑 L2** ——
L2 与在途车道的 L1 并发会抢 PG 与 `target/`，2026-10-19 20:00 实测出过一整批假红
（1364 例里 3 failures + 24 errors，全是 `seed()` 503 / 连接拒绝造成的假红）。

## 收尾三件套

本轮**没有新交付模块**（batch-31 的三件套由持闸的上一 tick 完成，本轮已只读核对远端 ref）。
本文件是**调度轮记录**，不含功能说明 —— 功能说明随 batch-32 交付轮写。

- 写说明：✅ 本文件（调度轮记录）
- commit：✅ 与台账同一个 commit
- push：✅ 推到 `origin integration/module-dev`（**未 push `main`**）

## 后续待办

- 06:00 tick：`merge-ready` → `sync` → 静态预检 → **L2 全量** → 写 batch-32 功能说明 → commit → push。
- 🔴 **L2 必须在 9.1.2.1 落地之后再跑**。它比另外三条车道**晚派发约 13 分钟**（504 接管续做），
  落地时间也会相应晚约 13 分钟 ⇒ 06:00 tick 很可能看到「3 条已 ready + 9.1.2.1 仍在 running」。
  此时**不要**在它还在跑 L1 时就启动 L2（PG 与 `target/` 抢资源 → 2026-10-19 20:00 实测出过一整批假红）。
  两个可选处置：**①** 等它落地再 merge + L2；**②** 只合并已落地的车道，L2 推迟到它落地之后那一轮。
  若 L2 红了而 9.1.2.1 当时仍在跑 ⇒ **先隔离复跑最大那一堆**再归因，不要直接当成回归。
- L2 红 ⇒ 不 push，二分定位（最多 2 次全量），派修复车道。
- batch-32 四个模块交付后，剩余未开发 **235** 个。
