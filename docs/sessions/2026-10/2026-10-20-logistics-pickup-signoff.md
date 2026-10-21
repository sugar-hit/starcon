# 10.3.2.2 揽收与签收确认（channel）

- **日期**：2026-10-20
- **车道**：`feat/10-3-2-2`（worktree `.worktrees/10.3.2.2`）
- **蓝图锚点**：10-logistics-and-freight.md §10.3.2.2，L3 = 10.3.2 交运管理，D5

## 决策摘要

1. **落 channel 而不是 trade**：蓝图卷 10 卷首「channel 拥有物流商连接与轨迹」，
   L2 表 10.3 物流跟踪 = channel。揽收/签收是「与物流商交互的那一跳」的确认侧，
   与 10.3.2.1 交运批次（V129）同属一条线，同库只读上游数据。
2. **🔴「批次交接是责任分界点」的物理落点 = 确认单以交运件为粒度**。
   `logistics_pickup_signoff_case.source_manifest_item_id` 对
   `handover_manifest_item(tenant_id, id)` 同库复合外键 +
   `UNIQUE(tenant_id, source_manifest_item_id)`（一个交运件只对应一个确认单），
   并快照 `source_batch_status`（**批次作废 ⇒ 责任从未转移**）。
   逐件粒度才有追责落点：纠纷问的是「哪一箱没被揽收」。
3. **🔴「揽收状态直接影响平台履约指标，需主动监控而非等轨迹同步」的物理落点 =
   `POST /channel/logistics-pickup-signoff/reconcile` 巡检端点**。
   确认单**不能**等 10.3.1.1 的轨迹同步顺带产生 —— 等同步建单 ⇒
   「没人同步轨迹就没人盯揽收」，而这正是平台判我们未发货的那条路。
4. **超时是读时算出来的，时限是建单时的快照**。`pickup_state='OVERDUE'` 由巡检按
   `now()` 与时限快照比对得出（不落静态结论，否则「现在超时了吗」会答错）；
   `pickup_deadline_at` 建单算好即定住，事后改 SLA 规则**不会**把已经延误的货
   变成准点。
5. **下游 9.4.2.1 回传 / 15 客服未接线 ⇒ 不假装下游存在**。平台履约回传 /
   评价邀请 / 售后窗口关闭只落本服务内 `logistics_pickup_signoff_notification`
   待通知记录，状态**停在 READY，不写「已回传」**。
6. **承运商出网通道未交付 ⇒ 刻意不放假 vendor 实现**。没有通道时揽收确认只能来自
   承运商推送或人工登记（`MANUAL_ATTESTATION` 带凭据号与时刻，**不覆盖**承运商
   事件的来源标记）。一个「返回成功回执」的假适配器会让「已揽收」在数据上看起来
   发生过 —— 履约指标一旦被伪造就没人再盯。
7. **本模块不涉及任何金额**。自造一个运费列会造出第二套成本口径，成本归 12 财务。
8. **复用 identity 已有的 `channel.operation.*` 权限码，identity 零改动**。

## 关键 trade-off

- **主动巡检 vs 同步顺带建单**：为什么不把「建单 + 判超时」挂进 10.3.1.1 的
  `POST /subjects/sync`？挂进去代码更省，但语义就变成「同步轨迹的人才盯揽收」——
  同步是稀疏动作，而履约指标要求的是**持续**监控。独立 `reconcile` 端点让
  「盯不盯」成为可被调度器显式调用的事，而不是某个同步流程的副作用。
- **时限存 `numeric(19,6)` 小时而不是整数分钟**：数据库侧存的是
  `numeric(19,6)` + BigDecimal（**禁浮点**，与仓库金额口径同款），运算统一走
  `Duration` 微秒精度；只在读回时折算成 `overdue_hours`（巡检那一刻的读数，
  会随巡检继续增长 —— 这正是它的用途）。分页页数里的 `Math.ceil(total/(double)s)`
  是唯一的 double，不涉及计时与金额。
- **`?::timestamptz` 必须写**：UPDATE 里的 timestamptz 绑定一律显式转型，
  漏写会静默偏 8 小时 —— 这类错不会报错，只会让截止时刻整体错位。
- **运单号对不上时如实报缺口而不是猜**：`NO_WAYBILL` / `TRACKING_SUBJECT_MISSING` /
  `SUBJECT_AMBIGUOUS` 三个缺口码；命中多个轨迹主体（运单号不唯一）时
  **不自动确认**（`subject_match_count > 1`）。9.4.1.1 已踩过「出库单号冒充运单号」，
  这里不重复。
- **异常只标记不处置**：`exception_flag` / `exception_code` 只记承运商报的异常节点，
  处置入口归 10.3.1.2（未交付）。本模块**刻意不提供**任何异常处置按钮 ——
  放行一个后端不存在的映射比 404 更糟。
- **api-proxy 走「新加一条 `services[]` 元素」而不是往 channel 既有 alternation 里并**：
  同批多车道共享 `apps/web/src/lib/api-proxy.ts`，而每个服务在 `services[]` 里是
  **一段超长单行正则**，两条车道都往同一 alternation 并分支时 git 必然冲突
  （结构性的，retry 永远不会自愈）。插入锚点固定在 10.1.1.3 `logistics-transit-limits`
  之后、10.7.1.1 退货接收之前，与另外三条车道（10.3.1.2 / 10.6.2.1 / 10.7.1.2）
  的锚点互不重叠。既有条目**一字未动、不重排、不格式化整文件**。

## 文件清单

**迁移**

- `backend/services/channel/src/main/resources/db/migration/V217__logistics_pickup_signoff.sql`
  五张表：`logistics_pickup_signoff_sla_rule` / `logistics_pickup_signoff_case` /
  `logistics_pickup_signoff_event` / `logistics_pickup_signoff_reminder` /
  `logistics_pickup_signoff_notification`；ENABLE + FORCE RLS；1 个 append-only 触发器
  （事件流）；**零 ALTER 既有表**（跨车道 DDL 回归面 = 0）。

**后端**

- `LogisticsPickupSignoffModels.java` — 传输模型（用具名 record，不用 `Map<String,Object>`）
- `LogisticsPickupSignoffService.java` — jOOQ 显式 SQL + 状态机 + 巡检 + 提醒 + 待通知
- `LogisticsPickupSignoffController.java` — `/channel/logistics-pickup-signoff/**`（14 个端点）
- `LogisticsPickupSignoffIntegrationTest.java` — 22 例真实 PostgreSQL 16 + Testcontainers
- `LogisticsPickupSignoffTenancyIntegrationTest.java` — 3 例 RLS 隔离断言

**前端**

- `apps/web/src/components/logistics/pickup-signoff-model.ts` + `.test.ts`（15 例）
- `apps/web/src/components/logistics/pickup-signoff-workspace.tsx`
- `apps/web/src/app/logistics/pickup-signoff/page.tsx`（`/logistics/pickup-signoff`）
- `apps/web/src/app/api/channel/pickup-signoff-allowlist.test.ts`（放行 / 反向断言）

**共用文件（外科式最小改动）**

- `apps/web/src/lib/api-proxy.ts` — **新增一条独立 `services[]` 元素**（既有条目一字未动）
- `packages/api-contracts/src/contracts/channel.ts` + `channel.openapi.json`（codegen 产物）
  —— 本车道只保留 channel 侧，其余服务的生成物已 revert

**文档**

- `docs/product/capability-blueprint/module-progress.md` — 10.3.2.2 行状态改「已交付」

## 验证

- `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  → `Generated 3578 DTOs in 6 per-service files, 6 OpenAPI snapshots and one barrel`，
  **无碰撞中止**（`contract-collision-allowlist.json` 保持 `{"allowed": []}` 未动）。
  逐路径/逐 schema 比对确认：channel 侧只新增本模块 14 条 path 与 24 个 schema，
  既有 path 内容零变化，两个既有 schema 仅 `0` → `0.0` 的序列化噪音。
- `cd backend && ./mvnw -q compile` → 通过（Flyway 迁移到 `v217 - logistics pickup signoff`）
- `./mvnw -pl services/channel -am test -Dtest='LogisticsPickupSignoff*'` →
  **Tests run: 25, Failures: 0, Errors: 0, Skipped: 0**（BUILD SUCCESS）
- `pnpm typecheck --force` → **3 successful, 3 total**（0 cached，强制真跑）
- `pnpm vitest run apps/web/src/lib/api-proxy.test.ts apps/web/src/lib/api-proxy-union.test.ts`
  → **2 files / 262 tests passed**
- `pnpm vitest run apps/web/src/app/api/channel` → **37 files / 172 tests passed**
- `pnpm vitest run apps/web/src/components/logistics/pickup-signoff-model.test.ts`
  → **1 file / 15 tests passed**

## 后续待办

- **10.1.1.x 承运商连接与凭据**交付后：在 `carrier/` 下按 vendor 建独立子包实现
  承运商出网通道，揽收/签收从人工登记转为主动拉取，capabilities 的
  `carrier_adapter` 维度随之转 `available=true`。
- **9.4.2.1 回传**（已交付在 trade，但 channel 与它之间**没有接线**）接线后：
  `logistics_pickup_signoff_notification` 的 `PLATFORM_FULFILLMENT_WRITEBACK`
  记录由下游消费，状态从 READY 推进到 CONSUMED。
- **15.2.2.x 索评 / 15 客服**交付后：`REVIEW_INVITATION` / `AFTER_SALES_WINDOW_CLOSE`
  两类待通知才有真实消费者；本模块已算好时机（签收 + 延迟 / 窗口）。
- **10.3.1.2 异常件**交付后：本模块的 `exception_flag` / `exception_code` 只标记不处置，
  处置入口由该模块提供。
- **10.2.1.2 运单申请**交付后：`NO_WAYBILL` 缺口减少，运单号维度转 `available=true`。
- 巡检调度（`reconcile` 的 cron / 定时任务）**不在本车道范围**，需由调度侧接线。

## 关联

- `docs/product/capability-blueprint/10-logistics-and-freight.md` §10.3.2.2
- `docs/product/capability-blueprint/module-progress.md` — 10.3.2.1 交运批次（上游）
- `docs/sessions/2026-10/2026-10-15-handover-batch.md` — 10.3.2.1 交运批次会话记录
- `docs/decisions/0002-multi-tenancy-strategy.md` — 多租户与 RLS 策略
- `docs/architecture/multi-tenancy.md`
