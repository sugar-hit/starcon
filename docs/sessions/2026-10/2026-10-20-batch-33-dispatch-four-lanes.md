# 批次 33 · 派发记录（4 条车道）

> 时间：2026-10-20 06:00 tick → 07:10 取闸 → 07:36 回收完 batch-32 → 07:37 派发 batch-33 → 07:41 补派剩余 2 条
> 集成头：`integration/module-dev`（基线 `03785467`）
> 模式：`MODE=full`（TOKEN_HORIZON=2026-10-25 前），LANES=4，THROTTLE_MIN=50，SILENT_WINDOW=0

---

## 本轮定位：一轮之内**回收 batch-32 + 派发 batch-33**

06:00 tick 的现场是「上一批 4 条车道在途」，不是空转轮：

- batch-32 的 4 条车道（4.4.1.2 / 4.5.1.2 / 4.5.2.1 / 9.1.2.1）在 04:xx–06:0x 之间陆续落地。
- 集成头 06:00 时停在 `89dd3148`，尚无一条 batch-32 车道被并入。
- 另有并发会话在跑「main → 集成头同步」，07:08:47 抢过一次调度闸，07:10 释放后本轮接管。

⇒ 本轮做两件事：**把 batch-32 收干净（三件套 + push）**，再**派发 batch-33**。

## 节流闸的两次判定

| 判定时点 | 距上一批 | 阈值 | 结果 |
| --- | --- | --- | --- |
| 06:00:19（本 tick 起点） | 42 分 08 秒 | 50 分钟 | **未达阈值** ⇒ 起点不派新批次，只做回收 |
| 07:37:08（batch-32 三件套落地后） | **139 分 16 秒** | 50 分钟 | **已达阈值** ⇒ 派发 batch 33 |

- 闸的**目的**是「两批之间不小于 50 分钟」，不是「tick 起点那一瞬间的快照」。
  42 分钟 < 50 时本轮不派新批次；等本轮把上一批收干净，间隔自然涨到 139 分钟，节奏没有被压成两小时一次。
- `last-batch-at` **在通过闸门之后才写**（`1792453028` = 07:37:08），
  没有出现「空转轮把节奏无限往后推」这个闸唯一的严重故障。
- 沿用 batch-32 派发轮（05:00 tick）已确立的同一先例：节流闸管的是**两批之间**的间隔，
  不是「tick 起点快照」。

## 容量闸判定（派发前，只读）

| 闸 | 读数 | 阈值 | 结论 |
| --- | --- | --- | --- |
| 温度 | `pmset -g therm` → No thermal warning level has been recorded | 有降频项即 STOP | ✓ 通过 |
| 配额 | running 0/6（回收时）→ 4/6（batch-33 派发后） | ≥7 命中 | ✓ 通过 |
| PG | 带 `org.testcontainers.sessionId` 标签的容器 **0** 个 | ≥6 命中 | ✓ 通过 |
| load | 3.94 / 3.44 / 3.83 → 派发时 2.04 / 2.82 / 3.10（10 核） | >12 命中 | ✓ 通过 |
| 孤儿容器 | 无标签的 `postgres:16-alpine`：**0** 个 | — | ✓ 无需清理 |

⇒ **GO**。

> 说明：跑着一个 `postgres:15-alpine` 容器，但**不带** `org.testcontainers.sessionId` 标签，
> 按协议不计入 PG 闸（那是常驻业务容器，不是 Testcontainers 会话残留）。

## batch-32 的回收结果（本轮前半段，详见交付说明）

- 4 条车道零冲突并入集成头：`36a1ccdf`(4.4.1.2) / `252bea73`(4.5.1.2) / `aa1b954b`(4.5.2.1) / `abd8b21a`(9.1.2.1)。
- 1 条修复车道：`6b7f2039` → 并入 `0a9a82f7`（4.5.2.1 allowlist 负向断言）。
- L2 **第三次跑全绿**：后端 5600 例 0 failures / 0 errors，前端 293 文件 / 4724 例全绿，
  typecheck / lint / Next production build / `sync --check` 全部 exit 0。
- 三件套完成：说明 + 台账 + backlog **同一个 commit** `03785467 docs(batch-32): …`，已 push，
  远端 ref 核对 = `03785467`，`origin/main` 仍停在 `1bb270a0`（未动）。
- 台账：465 行 / 0 重复模块 ID，已交付 **226 → 230**，未开发 **239 → 235**，完成率 **49.5%**。

## 本批派发：batch 33（4 条车道）

基线 `03785467`，L3 锚点互异（4.4.1 / 9.2.1 / 9.2.2 / 4.5.2），`serviceBlocked` 为空。

| # | 模块 ID | 名称 | 阶段 | L3 | 落地服务 | 迁移 | 分支 | 页面路由 | API 前缀 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 4.4.1.3 | 采集箱 | D5 | 4.4.1 | channel | V208 | `feat/4-4-1-3` | `/listings/collect` | `listing-collect-box` |
| 2 | 9.2.1.1 | 待审队列 | D4 | 9.2.1 | trade | V209 | `feat/9-2-1-1` | `/orders/review` | `order-pending-review` |
| 3 | 9.2.2.1 | 地址与支付校验 | D4 | 9.2.2 | trade | V210 | `feat/9-2-2-1` | `/orders/address-payment-check` | `order-address-payment-check` |
| 4 | 4.5.2.2 | 上新与变更监控 | D5 | 4.5.2 | channel | V211 | `feat/4-5-2-2` | `/listings/changes` | `listing-changes` |

- 4 个 worktree 全部从 `03785467` 切出，`node_modules` 已就位。
- 迁移版本号 **V208–V211 预分配**，任务书里写死「不许自己找最大版本号」。

### 派发时序

- 07:37 派发 #1（4.4.1.3）与 #2（9.2.1.1）。
- 07:41 补派 #3（9.2.2.1）与 #4（4.5.2.2）—— 这两条的任务书在 07:37 就已生成完毕并通过自检，
  本轮在收尾三件套之后补齐 `task` 调用。**两批的节流间隔不受影响**（同一批的 4 条车道，
  不是新批次；`last-batch-at` 仍是 07:37:08）。

### 共享白名单锚点：4 条车道的插入点两两相隔 ≥ 74 行

`apps/web/src/lib/api-proxy.ts` 的 `services[]` 是**累积型共享文件**，且**不在** union 合并清单里
⇒ 每条车道只新增一条元素，插入锚点重叠就会 git 判冲突。

| 模块 | 锚点（基线 `03785467`） | 插入位置 |
| --- | --- | --- |
| 4.4.1.3 | `listing-bulk-collects` 元素之后 | 1749 |
| 4.5.2.2 | `listing-keyword-rankings` 元素之后 | 1893 |
| 9.2.1.1 | `order-intercept` 元素之后 | 2789 |
| 9.2.2.1 | `fulfillment/consolidation` 元素之后 | 2946 |

两两间距 **144 / 1040 / 1197 / 896 / 1053 / 157** 行 —— 最小 144，远大于默认 3 行上下文窗口。

### API 前缀不撞车

既有 token 与新前缀**首 token 互不相同**，且既有正则都是锚定的（`/^token(...)?$`）：

- channel 侧既有：`listing-collects` / `listing-bulk-collects` / `listing-keyword-rankings` /
  `listing-hijackers` / `listing-claims` / `listing-compliance-precheck` / `listing-performance`
- trade 侧既有：`order-review` / `order-intercept` / `orders` / `fulfillment` / `aftersales` / `platform-orders`
- 本批新增：`listing-collect-box` / `order-pending-review` / `order-address-payment-check` / `listing-changes`

## 任务书纪律（这一批的防复发措施）

batch-32 的 L2 第二次红，是**真实跨车道缺陷**：4.5.2.1 拿兄弟车道 4.5.1.2 的前缀
`channel/listing-keyword-rankings` 当「未交付形状」要求 404，而 4.5.1.2 **在同一批里已经交付**
⇒ `expected 503 to be 404`。⇒ 本批任务书加了两条硬约束：

1. **🔴 负向断言纪律**：allowlist 的负向样本**必须**以自己模块的前缀开头，
   不许拿兄弟车道的前缀当负向样本。理由写进任务书原文 —— 断言方向搞反时会**替错误背书**
   （兄弟车道那条白名单若被取错一侧静默删掉，这条负向断言反而会绿，把「已交付路由被误删」盖住）。
2. **落盘位置纪律**：临时产物只能写在自己 worktree 的 `.tmp/`，不许写家目录或 `/tmp/`。

生成器 `.tmp/gen-taskbooks.mjs` 自检结论：

- 4 份任务书里形如 `<大写标识符>` 的**残留占位符 = 0**（逐字自检，不是抽样）。
- 每份任务书**只含一个** `V2xx` 迁移版本号（无双 V）。
- 迁移示例、页面路由、API 前缀、测试类前缀按车道各自填好。

## 遗留风险（交给下一轮，不阻塞本轮）

1. **既有模块 9.3.3.1 的 1ms 跨时钟竞态**：`FulfillmentRequestService.completeStep` 用 JVM
   `OffsetDateTime.now()` 写 `started_at`，DB 函数用 PG `clock_timestamp()` 写 `finished_at`，
   违反 `CHECK(finished_at IS NULL OR finished_at >= started_at)`。
   本轮 L2 第一次红就是它（batch-26 出现过同一条约束的同款现象，**第 2 次**）。
   已记入 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` §六，
   建议同族修法：收敛时钟域，或 DB 侧 `GREATEST(clock_timestamp(), started_at)` 兜底。
   **调度者不代改业务代码**，等派修复车道。
   （2026-10-20 追加：05:00 轮调度者在旁路做了独立只读取证，结论一致 —— 约束编号
   `fulfillment_request_step_check3` 经源码复核精确对应 `V152` L434 的第 4 条表级 CHECK；
   详见 backlog §六之二。**该模块 ID 是 `9.3.3.1` 履约请求，不是 `9.3.1.1` 拆单**。）
2. **`api-proxy.ts` 里 4.5.1.2 条目的注释过期**：仍写着「4.5.1.2 关键词排名，未交付」，
   而该模块已交付。属 4.5.1.2 的文件所有权范围，本轮未改。
3. **9.1.2.1 曾遇模型网关 504 中断**，遗留 3 个文件（`V207` / `PlatformOrderSyncModels.java` /
   `PlatformEnumMappingClient.java`）经原地接管续做，遗留文件全部保留并局部修补。已并入，无残留。
4. **并发会话的 main → 集成头同步**共用同一个调度闸，下一轮若撞闸按协议零派发 + 只读检查 + 汇报。

## 下一轮（08:00 tick）预期

- 节流闸：`last-batch-at` = 07:37:08，08:00 时距上一批 23 分钟 < 50 ⇒ **08:00 tick 大概率零派发**，
  只做只读检查 + 汇报；等 batch-33 四条车道陆续落地后，间隔涨过 50 分钟再派下一批。
- 若 4 条车道 ready：`merge-ready` → `sync` → 静态预检 → **L2 全量** →
  写 `docs/sessions/2026-10/2026-10-20-batch-33-four-lane-delivery.md` →
  commit（说明 + 台账 + backlog 同 commit）→ push。
- `merge-ready` 要求工作区**完全干净**（含无未跟踪文件）⇒ 需先把说明/台账移走或 `git stash`，
  merge 完再恢复。
