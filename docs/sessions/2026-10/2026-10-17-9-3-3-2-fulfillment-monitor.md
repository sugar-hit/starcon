# 9.3.3.2 履约流程监控（D4，L3 锚点 9.3.3，trade 服务）

- **日期**：2026-10-17
- **车道**：`feat/9-3-3-2`（worktree `.worktrees/9.3.3.2`，基线 `integration/module-dev` @ `a052a025`）
- **迁移**：`V154__trade_fulfillment_monitor.sql`

## 决策摘要

### 1. 流程状态复用 V152，不另起一套状态机

实查集成头确认 9.3.3.1 已交付且在位（`V152` 七张表 + `FulfillmentRequestService`）。
蓝图 9.3.3.1 的加粗句「流程状态必须持久化」已经由 V152 落地，**流程状态就是
`fulfillment_request` 头行的 `status + current_step + process_version` 三件套**。

所以本模块**零 ALTER、零 UPDATE 任何既有表**：只读 V152 的流程状态检出异常，
对 `(tenant_id, request_id)` 建复合外键把案例挂在真实流程上，并把判定证据
（`rule_evidence` / `threshold_seconds` / `overdue_seconds`）**快照**进案例行。

**为什么快照而不 JOIN**：JOIN 出来的是**当前**状态而不是决策时状态。一单多仓多批次场景下，
本模块要为每个履约单元留一份「当时按哪个仓判的」凭据；上游事后改一列就静默改掉本模块的判定。
照 V126 第 4 节同一纪律。

### 2. 加粗句落成 DB 层三道闸门（不是提示语）

蓝图：「超时不是失败：流程卡住时先查证再决定重试或释放预占，否则会出现"重试造成重复发货"。」

| 闸门 | 落点 | 挡住的错 |
| --- | --- | --- |
| 查证前置 | `guard_fulfillment_monitor_retry_verified`（BEFORE INSERT 触发器） | RETRY 引用非 CONFIRMED 的查证；**或引用别的案例的查证**（「拿 A 的查证结论重试 B，等于没查证」） |
| 并发互斥 | 部分唯一索引 `fulfillment_monitor_retry_once_uq(tenant_id, request_id, attempt) WHERE action_kind='RETRY' AND status<>'CANCELLED'` | 两个并发重试都成立 |
| 乐观并发 | `monitor_version` + 窄函数里 `WHERE monitor_version=? AND status=?` 的 CAS | 只在 Java 里 `if` 一下就算数 |

**关键 trade-off：互斥做在 DB 而不是服务层的 in-flight 集合。**
进程重启即失效，而重复发货是**钱和货**的事。代价是并发冲突表现为 23505 而不是业务异常码 ——
由集成测试断言「4 条并发只成功 1 条」把这个形状钉住。
另一侧的选择：索引带 `status<>'CANCELLED'`，所以撤回之后允许重新发起 ——
否则一次误操作会永久锁死这条流程。

### 3. 异常四类判据各自独立，不合并

蓝图点名的四类异常**处置动作完全不同**（缺货要补货/换仓，卡住要查那一步，超时要催），
合并成一类就会选错处置。

- `RESERVATION_FAILED` ← `reservation.status='REJECTED'`（被拒，**不是**超时）
- `INSUFFICIENT_STOCK` ← `step.failure_code='INSUFFICIENT_STOCK'`（V152 留痕里**真实发生过的失败码**）
- `STEP_STUCK` / `SHIPMENT_TIMEOUT` ← 是否超过当前步 `deadline`（TRACK 步超时叫「超时未发货」）

**一处与 V152 的边界摩擦（已记录）**：`INSUFFICIENT_STOCK` 的联接**刻意不按 attempt 等值**。
V152 同一 `(tenant, request, step_kind, attempt)` 只允许一行（它的去重不变式），
而 `requestReservation` 已经占了 `RESERVE/attempt=1`，所以测试夹具把缺货失败留痕落在 `WORK` 步上。
语义上也更对：当前这次尝试可能还没写下自己的 step 行，而「这条流程出过一次缺货失败」
是**跨尝试成立的事实**。

### 4. 超时判定用数据库时钟

扫描 SQL 里 `extract(epoch from (clock_timestamp() - deadline_at))`。
**用 `clock_timestamp()` 而不是 `now()`**：一个事务里 `now()` 是冻结的，扫描多条会把上条结果当成本条的。

阈值三档解析 **仓库覆盖 > 租户默认 > 建单快照 > 内置兜底** 全部在 SQL 里做完 ——
放 Java 里会出现「列表页一个阈值、扫描页另一个阈值」。每个被解析出的阈值都带 `threshold_source`，
未配的步骤显式标 `BUILTIN`（不让界面以为有人配过）。照 8.5.3.1（V148）三档解析同一纪律。

### 5. 两个下游都未交付：登记，不假装

照 V150 既有解法（租户可维护表 + 来源枚举 + 采集时刻）：

- **7.2.1.3 释放预占**在 warehouse 独立库 ⇒ `fulfillment_monitor_release`，
  **缺省 `REQUESTED`**（指令已登记待回执）。没有 7.2 的回执就不算释放 ——
  与 V152 预占缺省 REQUESTED 是同一条纪律，两边都守着「没有下游回执就不算锁定/不算释放」。
- **1 工作台待办**未交付 ⇒ `fulfillment_monitor_todo`，本服务内是权威待办。

capabilities 里 `reservationReleaseDispatched=false` / `workbenchTodoPushed=false` /
`processStateMutated=false` / `retryRequiresVerification=true` 显式报出。
**`captured_at`（采集时刻）与登记时刻刻意分开**：登记时刻会把「十分钟前就该看的单」
说成刚出现，SLA 排序跟着算错。

## 关键 trade-off 汇总

| 选择 | 放弃了什么 | 为什么值得 |
| --- | --- | --- |
| 证据快照而非 JOIN V152 | 实时性 | JOIN 出的是**当前**状态；快照才是举证凭据，且 V152 改列不静默改掉本模块的判定 |
| 重试互斥做在 DB | 更「干净」的业务异常码 | 并发冲突只有 DB 拦得住；重复发货是钱和货的事 |
| 阈值表保留 UPDATE 权 | 「所有当前状态表都收权」的口号整齐 | 它是**配置**，upsert 本来就需要 UPDATE；收权会让「改阈值」无路可走（照 V150 `order_risk_rule`） |
| 前端不自己推断能不能重试 | 一点「本地即时反馈」 | 后端 `retryAllowed` 才是权威；推断迟早与服务层漂移，而漂移的代价是重复发货 |
| 每个测试用例开自己的租户 | 共享夹具的简洁 | 共享租户下「A 用例调阈值」立刻改掉「B 用例」的判定，测试变成看谁先跑 |

## 文件清单

**新增**

- `backend/services/trade/src/main/resources/db/migration/V154__trade_fulfillment_monitor.sql`
  （七张表 + 三道闸门 + 五个 SECURITY DEFINER 窄函数 + RLS/授权）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentMonitorModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentMonitorService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentMonitorController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/FulfillmentMonitorIntegrationTest.java`（26 例）
- `apps/web/src/components/orders/fulfillment-monitor-model.ts`
- `apps/web/src/components/orders/fulfillment-monitor-model.test.ts`
- `apps/web/src/components/orders/fulfillment-monitor-workspace.tsx`
- `apps/web/src/app/orders/fulfillment/monitor/page.tsx`
- `apps/web/src/app/api/trade/fulfillment-monitor-allowlist.test.ts`

**改动**

- `apps/web/src/lib/api-proxy.ts`（**新增一条 services[] 元素**，+44 行；未重排既有内容 ——
  该文件在 HEAD 上本就不满足 prettier（超长单行正则），整体格式化会碰到别的车道的行）
- `packages/api-contracts/src/contracts/trade.ts` + `trade.openapi.json`（codegen 产物，禁止手改）
- `docs/product/capability-blueprint/module-progress.md`（**只改 9.3.3.2 那一行**的状态与证据两格）

## 验证

- L0：`cd backend && ./mvnw -q -o compile` → 无输出（exit 0）
- L0：`pnpm typecheck` → `Tasks: 3 successful, 3 total`；
  另跑 `cd apps/web && pnpm exec tsc --noEmit` 直出（排除 turbo 缓存命中）→ exit 0
- L1：`cd backend && ./mvnw -pl services/trade -am test` → **Tests run: 818, Failures: 0, Errors: 0**
- 前端：`pnpm vitest run`（本模块两个测试文件 + 9.3.3.1 白名单测试）→ **37 passed**
- Lint：`pnpm exec eslint`（六个改动文件）→ 无输出；`prettier --check`（自有五个文件）→ 全通过

## 后续待办

1. **9.3.3.1 的 step 失败路径还没法写 `INSUFFICIENT_STOCK`**：该失败码在 V152 的 CHECK 词表里
   预留了，但 V152 没有对外暴露写失败步的端点（`requestReservation` 等内部步骤行已占满
   `(RESERVE, attempt=1)`）。这条车道落地后，`INSUFFICIENT_STOCK` 检出才有真实上游可依赖 ——
   本模块的检出 SQL 已经就位，届时**不需要改结构**。
2. **7.2.1.3 正式接口到位后**：由同步器按同一形状往 `fulfillment_monitor_release` 写入并把
   `source_kind` 改成 `MODULE_7_2_1_3_RELEASE`，`captured_at` 用真实采集时刻。
   表结构与逻辑都不用改；capabilities 里 `reservationReleaseDispatched` 才可以翻成 true。
3. **1 工作台待办对接后**：同上，`source_kind` 改 `MODULE_1_WORKBENCH_TODO`。
4. **要把「重试」真正推回流程**，需经 9.3.3.1 的 `retry_fulfillment_request` 窄函数 ——
   本模块刻意不代它改状态（capabilities `processStateMutated=false`）。接线时由调用方串联。
5. `severity` 的 CRITICAL 判据目前是「被拒 / 缺货 = CRITICAL，超时超过 2 倍阈值 = CRITICAL」。
   阈值倍数写死在服务层，若要租户可配需要另开一个配置项（本车道未做，因为蓝图没点名）。
