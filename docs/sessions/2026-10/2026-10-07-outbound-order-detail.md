# 2026-10-07 · 8.3.1.2 出库单详情

车道 `feat/8-3-1-2`，worktree `.worktrees/8.3.1.2`，基线 `integration/module-dev`（`5afda65`）。

## 决策摘要

**本模块是纯读投影，不新建任何表。** V79 只建一条索引，零 ALTER 既有表 —— 跨车道回归面为零。

理由：蓝图给 8.3.1.2 列的六段内容，逐段核对后**全部落在 8.3.1.1 的 V72 四张表里**：

| 蓝图段落 | 真实来源 | 状态 |
| --- | --- | --- |
| 明细行 | `outbound_order_line` | 已有 |
| 作业时间线 | `outbound_order_event`（append-only） | 已有 |
| 已拣量 | `outbound_pick_request` 快照 + event 留痕 | 部分，**只是请求量不是实拣量** |
| 已发量 | `outbound_order.status = SHIPPED` | 部分，SHIPPED 时可给真数 |
| 关联销售订单 / 买家地址 | 只有 `source_external_ref` 字符串 | **不可得**（9 未交付） |
| 预占引用 | 7.2.1 预占需求未交付 | **不可得** |
| 包裹列表 | 8.3.4.1 发货交接未交付 | **不可得** |

预占引用归 7.2.1、包裹归 8.3.4.1、买家地址归 9 订单履约 —— 在这里建引用表就是给别人的事实抢第二个写入所有者（AGENTS.md）。上游交付后它们自己写入，本端点立即读到，**无需迁移**。

## 关键 trade-off

**1. 已拣量逐行返回 `null`，不是 0。**
8.3.1.1 刻意没建 `picking_task`（归 8.3.2.1），只做到 `outbound_pick_request` 交接为止 —— 那是**请求量快照**。填 0 会让作业员以为「一件都没拣」，然后重复拣一遍。所以逐行 `pickedQuantity` 恒 null，另由 `OutboundQuantityAvailability` 给出 `ownerModule=8.3.2.1` 与原因。**绝不允许假数字，也绝不编一个进度百分比。**

**2. 已发量只在 `SHIPPED` 时是真数。**
`SHIPPED` 是终态且 DB 层冻结（V72 §4.2），明细行是不可改的计划快照（V72 §4.4）—— 所以在这张单上「已发量 = 计划件数」**成立**。其余状态一律 null：不按拣货进度推算发货进度。

**3. 四段不可得数据一律 `available=false`。**
照 8.2.1.1 既有约定给 `ownerModule` + `href` + 原因。**刻意不猜 `ref_type` 去 join**：
- `inventory_transaction` 有 `kind='RESERVE'` 预占流水，但引用键由预占侧自己定，当前没有任何指向出库单的形态；
- `print_target`（V73 已交付）有 `carton_code` / `package_count`，但按 `external_ref` 字符串索引、与出库单无 FK。

猜一个键去 join 一张没人写的表，只是把「没数据」伪装成「查过了，确实没有」。

**4. 作业操作入口复用同一个转换矩阵。**
`actions` 直接取 `OutboundOrderStatus.transitionsFrom()`，与 8.3.1.1 共用，不另写一份。集成测试逐状态比对两者没有分叉 —— 这是并行开发最常见的分叉点。

**5. 没有写端点。**
状态推进 / 取消 / 打印 / 生成拣货任务由 8.3.1.1 已有的四个端点执行。本模块只负责讲清「现在能做什么、去哪个模块做、前置条件是什么」。再开一套写端点就是让同一个状态跃迁有两个入口。

## 文件清单

**迁移**
- `backend/services/warehouse/src/main/resources/db/migration/V79__outbound_order_detail.sql` —— 只建一条索引 `(tenant_id, order_id, event_type)`

**后端**
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/OutboundOrderDetailModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/OutboundOrderDetailService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/OutboundOrderDetailController.java`

**测试**
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/OutboundOrderDetailIntegrationTest.java` —— 20 项

**前端**
- `apps/web/src/app/warehouse/outbound/[id]/page.tsx`
- `apps/web/src/components/inventory/outbound-order-detail.tsx`
- `apps/web/src/components/inventory/outbound-order-detail-model.ts`
- `apps/web/src/components/inventory/outbound-order-detail-model.test.ts`

**共享文件（联合编辑，未替换他人分支）**
- `apps/web/src/lib/api-proxy.ts` —— 对既有 `outbound-orders` 分支做**联合**：追加 `detail-capabilities` 与 `/{id}`，8.3.1.1 已登记的 `capabilities` / `pick-requests` / `print` / `cancel` / `{id}/transition` 五支**原样保留**
- `apps/web/src/lib/api-proxy.test.ts` —— 既验新增两支放行，也**反验 8.3.1.1 已交付的分支没被改掉**
- `apps/web/src/components/inventory/outbound-order-workspace.tsx` —— 单号可点进详情（8.3.1.1 的文件，只加链接不改行为）
- `packages/api-contracts/` / `packages/sdk/` —— codegen 产物，未手改

**台账**
- `docs/product/capability-blueprint/module-progress.md` —— 只改 8.3.1.2 那一行的「状态」「证据」两格

## 验证

- `cd backend && ./mvnw verify` —— 全绿
- `pnpm typecheck` / `pnpm lint` —— 全绿（lint 的 4 条 warning 是既有的 `axes`/`sourceOptions` hook 依赖，不在本车道文件）
- `pnpm test` —— 1417 项全绿（新增 12 项）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` —— 成功，`/warehouse/outbound/[id]` 注册为动态路由
- `node tools/module-progress/sync.mjs --check` —— 通过

## 后续待办

- **8.3.2.1 拣货任务交付后**：`picking_task` 表出现，`pickedQuantity` 改为按 `(tenant_id, order_id)` 读那张表并填真值；`OutboundQuantityAvailability(PICKED)` 的 `available` 翻 true、`ownerModule` 置空。**无需迁移。**
- **8.3.4.1 发货交接交付后**：`已发量` 可从「SHIPPED 时等于计划件数」升级为按实际发货单统计。
- **9 订单履约交付后**：`linkedOrder` / `shipToAddress` 两段可改为走订单侧的授权 API，**仍然不建本地占位表**。
- **7.2.1 预占需求交付后**：`reservationRefs` 需确认预占侧引用键的最终形态再接，不要先猜。