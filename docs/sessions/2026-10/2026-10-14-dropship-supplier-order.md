# 9.6.1.2 代发单与供应商下单（阶段 D4）

车道：`feat/9-6-1-2`（worktree `.worktrees/9.6.1.2`，基线 `integration/module-dev` @ d2568f11）

## 决策摘要

1. **告警是表 + DB 不变量，不是服务层的一句提示。**
   蓝图把「供应商涨价或无货**必须显式告警**，否则差价转负的订单会静默亏损」加粗写在关系里。
   四类差异（PRICE_INCREASE / NO_STOCK / ORDER_FAILED / NEGATIVE_SPREAD）**互不合并**成一��「有异常」，
   因为处置动作完全不同（改售价 / 换供应商 / 重下 / 止损）。

2. **差价按「实际成本」重算，不按「冻结价」算。**（最关键的一条）
   冻结价下差价还是正的 → 按冻结价算就永远报不出来，那正是静默亏损。
   涨价比的是**下单时冻结的** `purchase_amount`（供应商回执是「这一单贵了 3 块」，
   不是「这个 SKU 现在贵了 3 块」；后者归 9.6.1.1 可售判定管）。

3. **「已付款」：不伪造支付状态，改成有主体的人工确认。**
   `sales_order`（V99）没有支付状态列：`status` 的 CHECK 只有 `('DRAFT','SUBMITTED')`（审单队列状态），
   `payment_method` 是付款**方式**（预付/货到付）不是实收。V100 已把支付状态归给未交付的 12 财务。
   本模块改为要求四件套人工确认凭据（时刻 + 确认人 + `MANUAL_ATTESTATION` + 依据），
   DB CHECK 两个方向互斥，并由触发器在「提交供应商」那一刻强制复查。
   `capabilities` 里 PAYMENT 维度**显式 `available=false`** 并点名 ownerModule。

4. **供应商侧失败刻意不抛异常。**
   `dispatch()` 带 `@Transactional`，抛异常会把刚写下的 `FAILED` 与告警**一起回滚** ——
   而那正是蓝图要求「必须显式」的东西。改为返回 `status=FAILED` + 告警（与 9.6.1.1 的 `evaluate` 同一纪律）。

5. **未接线的 vendor 显式失败，绝不伪造响应。**
   `Alibaba1688SupplierAdapter` 是**默认未接线**的：仓库里没有它的 `base_url` 与凭证引用，
   未登记时显式失败并置 `FAILED`，**不会**返回编造的 `supplierOrderNo`。
   `ManualEntrySupplierAdapter`（人工录入）是**已接线**的那条路径。

6. **命名空间与 9.6.1.1 并列而非嵌套。**
   `/trade/fulfillment/dropship-orders` vs `/trade/fulfillment/dropship`：
   配置说「这个 SKU 走不走代发」，代发单说「这一票具体怎么下的」。

## 关键 trade-off

- **差价允许为负、不拦。** 拦了就录不进真实亏损的单，而亏损单恰恰最该被看见。
  唯一要求是 `NOT NULL`：「没算」与「算了是负的」是两件事，前者才是静默亏损。
- **头金额 = 行金额之和用 DEFERRABLE 约束触发器**，不是 BEFORE 触发器：
  建单先插头再插行，BEFORE 触发器在头插入那一刻行还不存在，`sum` 恒为 0，必然对不上。
- **告警表保留 UPDATE、只收回 DELETE**：处置必须能改 `status`/`acknowledged_*`，
  而「判定证据」（`alert_kind`/`expected`/`actual`/`occurred_at`）由触发器逐字段挡住。
  「能改处置」与「不能改事实」分开授权。
- **告警处置只改处置字段、不删历史行** —— 告警是「已经贵过 3 块」这件事的凭据。

## 文件清单

**迁移**

- `backend/services/trade/src/main/resources/db/migration/V122__trade_dropship_order.sql`（五张表 + 索引 + 5 个触发器 + RLS + GRANT；零 ALTER 既有表）

**后端**

- `SupplierOrderGateway.java`（统一端口）、`SupplierOrderException.java`
- `ManualEntrySupplierAdapter.java`（已接线）、`Alibaba1688SupplierAdapter.java`（默认未接线）
- `DropshipOrderModels.java`、`DropshipOrderService.java`、`DropshipOrderController.java`
- `DropshipOrderIntegrationTest.java`（28 例，Testcontainers PostgreSQL 16，连接池压到 1）

**前端**

- `apps/web/src/components/orders/dropship-order-model.ts` + `.test.ts`（31 例）
- `apps/web/src/components/orders/dropship-order-workspace.tsx`
- `apps/web/src/app/orders/fulfillment/dropship-orders/page.tsx`
- `apps/web/src/app/api/trade/dropship-order-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（外科式最小追加 trade 段 `fulfillment/dropship-orders` 一组）

**契约 / 台账**

- `packages/api-contracts/src/contracts/trade.ts`（codegen 重生成）
- `docs/product/capability-blueprint/module-progress.md`（仅 `| 9.6.1.2 |` 一行）

## 验证

- L0：`./mvnw -q -o compile` 通过；`pnpm typecheck` 三包全绿
- L1：`./mvnw -o -pl services/trade -am test` → **247 例全通过**（含既有全部 trade 模块）
- 前端：`pnpm vitest run`（model 31 例 + allowlist 5 例 + 9.6.1.1 allowlist 6 例）全绿
- `pnpm lint` 全绿

## 后续待办

- **9.6.1.3** 跟踪供应商发货与妥投：本模块只记录运单号，`LOGISTICS_TRACK` 维度已显式 `available=false`
- **9.6.2.1** 差价对账：消费本模块的 `spread_amount` / `dropship_cost` 生成列，不在下游重算
- **12 财务**：实收流水交付后，把 `payment_confirmed_*` 四件套换成授权 API 读取，表结构不变
- **1688 接线**：需登记 `base_url` 与凭证引用（secret manager 键），并做一次真实联调；
  联调前 `capabilities` 会一直报 `available=false`
- **10 物流 / 2.5.1.1**：运单轨迹与平台回传均未交付，相关维度已显式报不可用
