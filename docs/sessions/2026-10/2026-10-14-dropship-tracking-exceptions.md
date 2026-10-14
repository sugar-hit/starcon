# 2026-10-14 代发履约跟踪与异常（9.6.1.3）

## 决策摘要

| 项       | 内容                                                                                                                                |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 模块     | 9.6.1.3 代发履约跟踪与异常（卷 09 订单履约，L3 锚点 9.6.1，阶段 D4）                                                                |
| 落在服务 | **trade**（蓝图 09 卷「后端主责服务：trade + channel，取第一项」）                                                                  |
| 迁移     | `backend/services/trade/.../V126__trade_dropship_tracking_exceptions.sql`（版本号台账预分配）                                       |
| 命名空间 | 后端 `/trade/fulfillment/dropship-tracking`；前端 `/orders/fulfillment/dropship-tracking`；白名单组 `fulfillment/dropship-tracking` |
| 车道     | `feat/9-6-1-3`（worktree `.worktrees/9.6.1.3`）                                                                                     |

### 依赖自检结论（开工前 10 分钟）

全部通过，未 BLOCKED：

- V122 `fulfillment_dropship_order` 完整字段已读：状态机 `DRAFT→READY→DISPATCHED→ACCEPTED→SHIPPED/FAILED`、
  `supplier_tracking_no` / `supplier_shipped_at` 只**记录**回执、`sales_order_id` / `sales_order_no` / `supplier_code` 可同库引用。
- V118 `fulfillment_dropship_config` 已读：`fulfillment_path` / `ownership_mode` / `counts_against_own_inventory` 生成列。
- 9.6.1.1 / 9.6.1.2 的端点与前端路由风格已照抄（三件套命名 + 并列命名空间 + 外科式白名单追加）。

**`fulfillment_dropship_alert` 复用与否**：**另建**。它覆盖的是**商业差异**（涨价 / 无货 / 下单失败 / 差价转负），
`alert_kind` 的 CHECK 钉死这四个值，字段是 `expected_amount` / `actual_amount`（定价语义），
状态机只有 OPEN/ACKNOWLEDGED/RESOLVED。本模块三类异常是**履约**语义（延迟 / 丢件 / 跑单），
判据需要阈值快照、处置需要三种各自的状态流转，塞进同一张表会把两套不相干的判定与处置混成一条。
因此新建 `fulfillment_dropship_tracking_exception`，与 V122 的 alert 表**并列而不混用**。

## 关键 trade-off

### 1. 两段物流分列 vs 合成一个运单号

蓝图把「并把我方与供应商两段物流在订单上分开呈现」写进**职责**（不是「关系」里的补充）。

纯代发模式下合成一列看似能用 —— 供应商直发买家，本来就没有我方这一段。但蓝图紧接着要求
「异常必须可发现**可切换**」，而「改自营发货」一旦执行，同一张订单就同时存在两段：
供应商段（已发出 / 已废弃，是**凭据**）与我方段（交运→在途→妥投）。合成一列时第二段必然覆盖第一段，
而「供应商到底发没发过货」正是跑单纠纷要问的。

**决定**：建表即拆两组列，状态枚举也**刻意是两个词表**（供应商段有 SHIPPED/LOST/ABANDONED，
我方段以 HANDED_OVER 为起点且没有 ABANDONED）；DB 触发器 `guard_dropship_legs_exclusive`
拒绝两段同时在途，切段必须与「供应商段落 ABANDONED」在同一行里完成。

**放弃的捷径**：不去 ALTER V122 把 `fulfillment_path` 改成可切换 —— 那是**另一个车道的不变量**，
且「代发单」的定义就是「这一票是向供应商下的」。改的是这一票**接下来怎么走**，不是它当初是什么。

### 2. 异常检出放服务层 SQL 还是 DB 触发器

三条判据都带 `now()` 与租户可配阈值，因此放**服务层的显式 jOOQ SQL**（可读、可测、阈值从策略视图取），
DB 层只守「判定证据不可改写」与「同一 kind 不重复」两条不变式。

阈值散落成代码常量会与策略表形成两个真相源，所以用视图
`fulfillment_dropship_tracking_policy_effective`（`security_invoker`，LEFT JOIN LATERAL + coalesce）
给出唯一真相：未登记策略的租户也能检出，且永远返回恰好一行。

### 3. 下游未交付：落任务表而不是假实现

2.5.1.1（channel 回传）与 11（售后退款）都没交付。按模块边界纪律**一条 SQL 都不碰对方的表**，
改为落 `fulfillment_dropship_task` 作为跨服务那一跳的**权威待办**：
状态机 + jsonb 载荷 + 幂等键 + 重试计数。目标系统与任务种类由 CHECK 一一钉死（防止把回传任务发给售后仓库）。

**下游怎么接**：消费这张表（或由本服务调它的授权 API），把 `task_status` 从 OPEN 推进到
CLAIMED→ACKNOWLEDGED；只有走到这里 `callback_status` 才允许从 PENDING 变成 SENT/ACKNOWLEDGED。
界面上「待回传」不会画成「已回传」。

### 4. 任务载荷用 record 而不是 Map

踩过一次坑：`Map<String,Object>` 的值类型被 springdoc 推成 `object`，codegen 出**空对象 `{}`**，
前端拿不到运单号且过不了类型检查。改成具名 `DropshipTaskPayload` record 后契约里字段名齐全。

## 文件清单

**迁移**

- `backend/services/trade/src/main/resources/db/migration/V126__trade_dropship_tracking_exceptions.sql`

**后端**

- `backend/services/trade/src/main/java/com/starcon/trade/DropshipTrackingModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/DropshipTrackingService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/DropshipTrackingController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/DropshipTrackingIntegrationTest.java`

**契约（codegen 产物，只读）**

- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`
  （其余五个服务**零改动**）

**前端**

- `apps/web/src/components/orders/dropship-tracking-model.ts`
- `apps/web/src/components/orders/dropship-tracking-model.test.ts`
- `apps/web/src/components/orders/dropship-tracking-workspace.tsx`
- `apps/web/src/app/orders/fulfillment/dropship-tracking/page.tsx`
- `apps/web/src/app/api/trade/dropship-tracking-allowlist.test.ts`

**共用文件（最小追加）**

- `apps/web/src/lib/api-proxy.ts`（只在 trade 段追加 `fulfillment/dropship-tracking` 一组，未重排任何既有分支）
- `docs/product/capability-blueprint/module-progress.md`（只改 9.6.1.3 那一行的「状态」「证据」两格）

## 验证方式与结果

| 闸      | 命令                                            | 结果                                                                                          |
| ------- | ----------------------------------------------- | --------------------------------------------------------------------------------------------- |
| L0 编译 | `backend && ./mvnw -q compile`                  | 通过，无错误                                                                                  |
| L0 类型 | `pnpm typecheck`                                | 通过（3 包全绿）                                                                              |
| L1 模块 | `backend && ./mvnw -pl services/trade -am test` | **Tests run: 276, Failures: 0, Errors: 0**（含本模块 29 例 + 上游 9.6.1.1/9.6.1.2 全量）      |
| 前端    | `pnpm vitest run <三个相关文件>`                | model 21 例 + allowlist 5 例全绿；**同时复跑 9.6.1.1/9.6.1.2 的白名单测试确认兄弟组未被污染** |
| Lint    | `pnpm lint`                                     | 通过（仅仓库既有的其他模块 warning）                                                          |

集成测试实跑 Testcontainers PostgreSQL 16，连接池压到 1 条连接，多租户三断言齐全：
两租户互不可见、伪造 `X-Tenant-Id` 头无效、连接池归还后不串租户。

## 后续待办

- **10.3 轨迹未交付**：`supplier_last_event_at` 目前靠接口或人工录入。本模块只拥有两段物流的**状态**，
  轨迹点明细归 10.3；接入后把 `coalesce(supplier_last_event_at, supplier_shipped_at)` 换成真实观测即可，判据不变。
- **2.5.1.1 回传未交付**：消费 `PLATFORM_CALLBACK` 任务并回写结果。
- **11 售后未交付**：消费 `REFUND_REQUEST` 任务（金额已在处置上，BigDecimal/NUMERIC，无浮点）。
- **9.4.1.2 自营发货记录未交付**：`SELF_FULFILL_SWITCH` 任务交给我方仓，真正建单发货属它。
- **9.6.2.1 差价对账**未交付：本模块不碰差价口径，只做履约侧异常。
- 检出目前是**手动触发**（POST /exceptions/evaluate）。定时化归 workers/ 的调度车道，不在本模块范围。
