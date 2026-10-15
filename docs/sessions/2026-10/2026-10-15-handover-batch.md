# 10.3.2.1 交运批次（channel）

- **日期**：2026-10-15
- **车道**：`feat/10-3-2-1`（worktree `.worktrees/10.3.2.1`）
- **蓝图锚点**：10-logistics-and-freight.md §10.3.2.1，L3 = 10.3.2 交运管理，D5

## 决策摘要

1. **落 channel 而不是 trade**：蓝图卷 10 卷首「channel 拥有物流商连接与轨迹」，
   L2 表 10.3 物流跟踪 = channel。交运批次是「与物流商交互的那一跳」。
   与 10.4.2.1 海外仓连接（V93）同款理由。
2. **候选池 = 上游 9.4.1.1 的 `HANDED_OVER` 单元**，经 `PendingShipmentQueryPort`
   走 trade 的**授权 HTTP API** 取数，转发调用方自己的 bearer。
   **一条跨服务 SQL 都没有**，无跨库 FK / 视图（AGENTS.md 模块边界纪律）。
   租户隔离是 channel 侧 RLS + trade 侧 RLS **两道**。
3. **交接清单（`handover_manifest_item`）是法律凭据**，不是展示列表：
   append-only **双保险** —— `REVOKE UPDATE,DELETE` + `BEFORE UPDATE OR DELETE`
   触发器，测试各试一次（应用账号 / 迁移超级账号）。
4. **冻结点取 `HANDED_OVER` 而非 `DISPATCH_PENDING`**（关键 trade-off，见下）。
5. **承运商那一跳没有真实对手方**（10.1 全部未开发）⇒ 只定义
   `CarrierHandoverAdapter` 端口，**刻意不放假 vendor 实现**；批次状态机保留
   `DISPATCH_PENDING` 如实表达「等待通道接通」；回执由人工登记带回。
6. **运单号如实留空并写明原因**，缺口码结构化（`waybill_gap_code`），
   **绝不出库单号冒充**（9.4.1.1 已踩过并明确禁止）。

## 关键 trade-off

- **冻结点为什么是 `HANDED_OVER` 而不是 `DISPATCH_PENDING`**：
  初版把「批次一旦交接，成员不可静默改写」钉在 `DISPATCH_PENDING`，
  结果**作废功能被自己的触发器锁死** —— 组错承运商只能硬着头皮交出去。
  业务事实是货权/责任真正转移在**承运商确认接收**那一刻；`DISPATCH_PENDING`
  时包裹还在仓库现场。改成 `HANDED_OVER` 后，「登记过交接又作废」这件事
  **仍留在 append-only 的 `handover_event` 时间线上**，追责要查的正是那条。
- **`BEFORE UPDATE` 触发器必须 `RETURN NEW`**：初版冻结触发器返回 `OLD`，
  触发器**静默丢弃整行更新**（看起来在拦、实际什么都不做）。测试当场抓到。
  触发器检查顺序也调过：先判「退回候选池」再判「换批次」，否则一次退回
  会报成更泛的「换到别的批次」，把真正的原因藏起来。
- **上游假服务器按 bearer 分租户**：夹具一开始不分租户，导致「B 的候选池为空」
  这类断言假失败 —— 那不是代码的错，是夹具比真实上游更宽（真实 trade 侧也有 RLS）。
- **契约 codegen 撞名**：`HandoverEventRow` 与 warehouse 已有 DTO 同名但字段不同，
  改为 `HandoverBatchEventRow`（不加 allowlist，避免日后被误当成「已接受冲突」）。
- **codegen 顺带重排了 trade/warehouse 的生成物**（纯 key 顺序变化，零内容差异，
  已用 `git show` + 排序比对确认）。为压低跨车道合并面**已 revert**，只留 channel。

## 文件清单

**迁移**

- `backend/services/channel/src/main/resources/db/migration/V129__logistics_handover_batch.sql`
  四张表：`handover_candidate` / `handover_batch` / `handover_manifest_item` / `handover_event`；
  7 个触发器；ENABLE + FORCE RLS；**零 ALTER 既有表**（跨车道 DDL 回归面 = 0）。

**后端**

- `HandoverBatchModels.java` — 传输模型（用具名 record，不用 `Map<String,Object>`）
- `PendingShipmentQueryPort.java` — 上游 9.4.1.1 跨服务只读端口
- `carrier/CarrierHandoverAdapter.java` — 承运商适配器端口（无 vendor 实现）
- `HandoverBatchService.java` — jOOQ 显式 SQL + 真 GROUP BY + 状态机
- `HandoverBatchController.java` — `/channel/handover-batches/**`
- `HandoverBatchIntegrationTest.java` — 14 例真实 PostgreSQL 16 + Testcontainers

**前端**

- `apps/web/src/components/logistics/handover-batch-model.ts` + `.test.ts`（25 例）
- `apps/web/src/components/logistics/handover-batch.tsx`
- `apps/web/src/app/logistics/handover-batch/page.tsx`（`/logistics/handover-batch`）

**共用文件（外科式最小改动）**

- `apps/web/src/lib/api-proxy.ts` — channel 那一段 alternation **追加** `handover-batches`
- `apps/web/src/lib/api-proxy-union.test.ts` — 12 条放行 + 13 条反向断言
- `packages/api-contracts/src/contracts/channel.ts` + `channel.openapi.json`（codegen 产物）

## 验证

- `./mvnw -pl services/channel -am test` → **89 例全绿，零回归**（本模块 14 例）
- `pnpm typecheck` → 3 包全绿；`pnpm lint` → 无新增告警
- `pnpm vitest run`（本模块两个文件）→ **154 例全绿**

## 后续待办

- **10.1.1.1 承运商列表**交付后：在 `carrier/` 下按 vendor 建独立子包实现
  `CarrierHandoverAdapter`，交接/回执从 `DISPATCH_PENDING` 推进到 `HANDED_OVER`
  由同一入口拿到 `DispatchReceipt` 后完成。capabilities 两项随之转 `available=true`。
- **10.2.1.2 运单申请**交付后：`waybill_gap_code=UPSTREAM_NOT_ISSUED` 的候选减少，
  运单号维度转 `available=true`。
- **10.3.2.2 揽收确认**交付后：批次「已交接」与「已揽收」要分开 ——
  当前两者不是一回事，页面已按此措辞，避免平台履约指标被算在从未发生的时刻上。
- 航线条目未登记：按 10.2.2.1 的先例走 `TASK_PAGES` 约定，不直改 nav-config。
