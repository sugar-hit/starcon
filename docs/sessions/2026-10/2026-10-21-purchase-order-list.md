# 2026-10-21: 6.3.1.1 采购单列表

## 摘要

交付能力蓝图 **6.3.1.1 采购单列表**（trade 服务，阶段 D6，L3 锚点 6.3.1）。

蓝图职责：「采购单主列表：单号、供应商、SKU 行数、总金额、币种、状态、承诺到货日、
已收数量/比例、逾期标记，支持多维筛选与导出。」输入「筛选条件（供应商、状态、日期区间、
是否逾期、目标仓）；用户数据范围。」输出「分页列表 + 批量操作（催单、关闭、导出）。」
关系：上游 = 6.2.2.1，下游 = 6.3.1.2 详情、6.3.2 跟踪。
蓝图加粗句：「**已收比例需实时反映 08 的收货结果**，这是采购员判断是否催货的第一指标。」

本车道交付：Flyway 迁移 `V254`（三张新表 + 序列 + 4 个触发器）、trade 服务 7 个端点
`/trade/purchase-orders/**`（jOOQ 显式 SQL）、`/purchase/orders` 真实工作区、
契约重生成、台账与本会话日志。

> **本车道是续做**：前一个 worker 在写到一半时被 `net::ERR_NETWORK_CHANGED` 打断。
> 本次接手时后端（迁移 / 模型 / 服务 / 控制器 / 三个测试类）已全部写完且**一次跑绿**，
> 故本次实际完成的是：契约重生成、前端工作区、api-proxy 白名单、台账、会话日志与提交。
> 后端只做了「读一遍确认写完」与「复跑验证」，**没有重写**。

## 决策 / 结论

1. **🔴 本模块拥有「采购单」这个聚合本身（头 + 行 + 留痕三张表）**。
   开工前 grep 确认：6.3.1.2 / 6.3.1.3 **均未交付** ⇒ 本仓此前**不存在任何采购单实体**
   （`grep -rn "create table purchase_order" backend/` 无命中）；
   6.2.2.1（V246）交付时刻意**不造占位的假采购单表**，只把「转成采购单时的供应商 / 数量 /
   交期 / 目标仓」记在 `purchase_request_conversion` 里并写明「6.3.1.2 交付后由它建真正的采购单」；
   6.3.2.2（V235）同样刻意**不建采购单表**，只用 `purchase_order_no` 做逻辑引用。
   ⇒ **采购单号的产出方是本模块**（6.3.1.1 是 L3 6.3.1 的锚点模块），口径钉死为
   `purchase_order.order_no` ↔ 6.3.2.2 的 `purchase_arrival_tracking.purchase_order_no`。
   两边都按**单号**串起来，6.3.2.2 已交付且零 ALTER 既有表 ⇒ 本迁移**不改它的表**，
   兄弟模块（6.3.1.2 / 6.3.1.3）交付时**不必 ALTER 本表**（跨车道 DDL 回归面 = 0）。

2. **🔴 已收数量 / 比例的唯一口径 = 本库 6.3.2.2 的到货跟踪，不是 warehouse 的收货单**。
   08 仓储的收货在 **warehouse 服务的独立库**里，AGENTS.md 禁止跨服务 SQL / FK / 视图
   ⇒ trade 侧只能经**授权只读端口**读（`WarehouseReceiptQueryPort` 已存在，本模块复用，不造第二个）。
   而 6.3.2.2 **已经在 trade 本库落了到货流水**（`purchase_arrival_tracking.received_qty`
   由 DB 触发器从 `purchase_arrival_receipt` 重算，行里带 warehouse 收货单号快照）。
   ⇒ **两个口径不混成一个数**：列表里的已收数就是到货跟踪的已收数；
   warehouse 收货单号只作**来源快照**透出（`warehouseReceiptCode`）。
   **没进到货跟踪的单，已收是 `null` 不是 0** —— 「还没登记到货」与「一件都没到」
   对采购员的动作含义完全相反（一个该去登记，一个该催货）。前端与后端都守住这条。

3. **🔴 总金额跨币种不相加**。
   一张采购单**只有一个币种**（`purchase_order.currency`）；列表汇总**按币种分组**返回
   （`sum(total_amount) group by currency`），**绝不**把 USD 与 EUR 加成一个数，
   也**绝不**按 1:1 或固定汇率折算。本位币合计用**建单那一刻冻结的汇率快照**
   （catalog 5.2.2.1 / 5.2.2.2，经既有 `PurchaseAnalyticsCatalogPort` 端口）；
   冻结不到的订单**不参与**合计并计入 `excludedOrderCount`，理由写进 note ——
   宁可少报一个合计，也不报一个用固定汇率凑出来的「看起来能对账」的数。
   取不到汇率**不拒绝建单**：采购单本身是对供应商的承诺，不该被一个「算合计用」的辅助能力卡住。

4. **🔴 金额与行数由 DB 从行表重算**（触发器 `refresh_purchase_order_totals`）。
   调用方传不进来 ⇒「头金额与行金额对不上」不是代码路径，是**不可能发生的事**。
   行金额同理（`guard_purchase_order_line_amount`：`line_amount = quantity × unit_price`）。
   状态机由 DB 触发器逐条钉死合法跃迁（`guard_purchase_order_status`），
   已关闭整单冻结（数量 / 单价 / 供应商 / 承诺日都不可再改）；
   留痕表 `purchase_order_event` 追加式，UPDATE/DELETE 由**触发器 + GRANT 双重收回**。

5. **🔴 数据范围由服务端从已认证身份与角色授权推导（ALL / DEPARTMENT / SELF）**，
   **不接受请求参数传入** —— 否则它会变成一个能越权的开关。
   判定只���**含有本权限**的授权（ADR-0005：操作与范围必须在同一角色分配中满足）：
   读按 `trade.purchase.read` 判、写按 `trade.purchase.update` 判 ——
   「能读全量但只能改自己的单」是常见且合理的授权组合。
   租户来自已认证成员关系，**不信任 `X-Tenant-Id` 头**（测试实跑断言）。

6. **🔴 批量操作逐单成败，不是整批一个布尔**。
   批量里有的单能关、有的不能关是常事（已关闭的、不是你的、状态不允许）。
   返回逐单结论 —— 整批一个布尔会让人重试整批，于是把已经关成功的单又关一遍。
   关闭**必须填理由**（DB CHECK + 服务层双保险）：「关掉了但不知道为什么」等于没关。

7. **建单路径先落地（`from-conversions`）**。
   6.3.1.2 未交付 ⇒ 本仓**没有任何采购单产生路径**，列表会永远是空的
   （那不是「本模块做完了」，那是「本模块没通」）。6.2.2.1 的转单事实正是
   「转成采购单时的参数」，本模块把这条路径先落地，6.3.1.2 交付后接手**手工建单与编辑**。
   **跨供应商绝不合单**（与 6.2.2.2 同一口径 —— 跨供应商合并会导致对账与付款错误），
   目标仓不同同理；被拒的转单事实**逐条带原因码返回，不静默丢弃**
   （丢掉的行等于少订的货）：`UNIT_PRICE_MISSING` / `CURRENCY_MISSING` /
   `SUPPLIER_MISMATCH` / `WAREHOUSE_MISMATCH` / `CURRENCY_MISMATCH` /
   `CONVERSION_ALREADY_USED` / `CONVERSION_NOT_FOUND`。

8. **契约只重生成 trade 那一份**。
   六份 spec 全部从**本车道自己的代码**导出（`OpenApiContractTest`），不借用集成头的快照 ——
   集成头已并入 8.2.2.2 / 16.4.2.2 两条车道，借用它们的快照会把别人的未合并产物写进本车道。
   生成后发现 `catalog.ts` / `channel.ts` 有**纯重排**差异（已提交产物与当前 codegen 脚本漂移：
   头部注释位置 + 类型排序），**类型集合零增零减** ⇒ 已 `git checkout HEAD --` 还原，
   只提交 `trade.ts`（+20 个 schema）与 `trade.openapi.json`（+7 条路径）。

9. **api-proxy 白名单走「新增一条独立 `services[]` 元素」**。
   既有 trade 分支是一段超长单行正则，多车道同时并进去 git 必然判冲突
   （结构性的，retry 不会自愈）。插入锚点固定在 `purchase-arrivals`（6.3.2.2）
   那条**之后**、`fulfillment/consolidation` 那条**之前**。
   **本条目没有 uuid 分支**：按 id 取单条的「6.3.1.2 采购单详情」是同 L3 下的兄弟模块且未交付，
   后端此刻压根没有这个映射 —— 放行一个不存在的形状比 404 更糟。
   正因为没有 uuid 分支，`capabilities` / `filters` / `export` 三个首字母落在
   [0-9a-f] 里（c / f / e）**不构成任何歧义**。

## 关键 trade-off

- **「已收」用本库到货跟踪 vs 跨服务读 warehouse 收货结果**：选本库。
  理由是后者的口径在 warehouse 库里、跨服务要额外一次往返与授权，而 6.3.2.2 已经在
  **同一个库、同一个租户、同一套 RLS** 里落了到货流水且由 DB 触发器保证一致性。
  代价是 08 侧刚录完、还没同步到 6.3.2.2 的那段时间差 —— 这个缺口由
  `receivedQuantitySource`（`ARRIVAL_TRACKING` / `NOT_TRACKED`）与能力声明里的
  `receivedQuantityNote` 如实说破，不靠猜。
- **建单入口放在本模块 vs 等 6.3.1.2**：放在本模块。
  代价是本模块多了一个写入口（看起来越界），收益是列表**真的能出数据**而不是永远空着。
  边界写进了能力声明与 `capabilities`，6.3.1.2 交付后接手手工建单与编辑。
- **汇总给「按币种分组」而不是给一个 totalAmount**：牺牲「一个数」的方便，
  换来「这个数是对的」。跨币种合计只在**能拿到建单时冻结的汇率快照**时才给。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V254__purchase_order_list.sql`
  —— 三张新表（`purchase_order` / `purchase_order_line` / `purchase_order_event`）
  + 序列 `purchase_order_no_seq` + 4 个触发器（行金额 / 头合计 / 状态机与终态冻结 / 留痕追加式）
  + 9 个索引 + RLS（ENABLE + FORCE）+ GRANT（并收回留痕的 UPDATE/DELETE 与采购单的 DELETE）。
  **零 ALTER 任何既有表、零跨服务外键。**
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderListModels.java`
  —— 传输模型（22 个 record/enum，`PurchaseOrderList` 前缀避免与全仓扁平契约重名）。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderListService.java`
  —— jOOQ 显式 SQL 的服务层（列表 / 汇总 / 筛选 / 导出 / 催单 / 关闭 / 建单 / 能力声明）。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderListController.java`
  —— 7 个端点 `/trade/purchase-orders/**`，权限码复用 trade 既有 `trade.purchase.read` / `.update`。

**后端测试**

- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderListFixture.java`
  —— 共享底座：Testcontainers 真实 PostgreSQL 16（连接池压到 1）+ 真的本地 HTTP 替身
  （identity `/internal/access`、catalog 汇率台账）+ 上游事实表的造数夹具。
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderListIntegrationTest.java`（18 例）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderListTenancyIntegrationTest.java`（3 例，
  **实跑租户隔离三断言**）

**契约**

- `packages/api-contracts/src/contracts/trade.ts`（+20 个 schema）
- `packages/api-contracts/src/trade.openapi.json`（+7 条路径）

**前端**

- `apps/web/src/components/purchase/purchase-order-list-model.ts` —— 前端模型与展示口径。
- `apps/web/src/components/purchase/purchase-order-list-model.test.ts`（24 例）。
- `apps/web/src/components/purchase/purchase-order-list-workspace.tsx` —— **真实工作区**
  （加载态 / 空态 / 错误态齐全，null≠0 的呈现纪律，批量逐单成败，数据范围常驻）。
- `apps/web/src/app/purchase/orders/page.tsx` —— 路由页。
- `apps/web/src/app/api/trade/purchase-order-list-allowlist.test.ts`（6 例）——
  真跑 proxy 断言放行 / 拒绝**双向**，并正向钉住其他车道已登记的 trade 分支。
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（纯增量）。

**台账与日志**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.3.1.1 那一行的「状态」「证据」两格。
- `docs/sessions/2026-10/2026-10-21-purchase-order-list.md` —— 本文件。

## 验证

- `./mvnw -q compile` → 退出 0。
- `./mvnw -pl services/trade -am test -Dtest='PurchaseOrderList*' -Dsurefire.failIfNoSpecifiedTests=false`
  → **Tests run: 21, Failures: 0, Errors: 0, Skipped: 0**（18 + 3），BUILD SUCCESS。
  迁移日志确认 `Successfully applied 62 migrations to schema "public", now at version v254`。
- `pnpm typecheck` → 3/3 通过（web 实跑，ui / config-tailwind 命中缓存）。
- `pnpm vitest run apps/web/src/app/api/trade apps/web/src/components/purchase`
  → **65 文件 471 例全绿**（含既有 64 个文件的回归，因为改了共享的 api-proxy）。
- `pnpm --filter @starcon/web lint` → 退出 0，本车道文件零告警。

## 后续待办

- **6.3.1.2 采购单创建与编辑**（同 L3，未交付）：接手**手工建单与编辑**；
  状态机枚举与留痕已在 V254 就位，**不必 ALTER 本表**。
- **6.3.1.3 采购单审批**（同 L3，未交付）：审批留痕尚无 ⇒ 本模块的 `capabilities`
  如实把「审批状态」标为不可用，不冒充。
- **6.3.2.1 跟单与交期**（未交付）：本模块的催单**不改承诺到货日**，
  交期变更历史归它；`purchase_order` 暂无交期变更历史表。
- **12 财务服务不存在** ⇒ 无应付状态；本模块的 `capabilities` 如实标为不可用。
- **汇率快照的补齐路径**：建单时汇率服务不可达的单不参与本位币合计，
  且**没有**「事后补冻结」的入口（那会改历史数字）—— 是否需要，取决于 6.3.1.2 的建单流程。
