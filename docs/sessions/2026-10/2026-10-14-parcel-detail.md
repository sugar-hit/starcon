# 2026-10-14 10.2.2.1 包裹详情

车道 `feat/10-2-2-1`（worktree `.worktrees/10.2.2.1`，基线 `integration/module-dev` @ `f002cc8f`）。

## 决策摘要

### 1. 落在 warehouse，不落在 channel —— 卷 10 那三个字说的是「谁提供物流能力」

蓝图卷 10 写「主责服务 = channel + trade + warehouse」。本车道**刻意不据此把详情查询写进 channel**：

- `parcel` / `parcel_item` 由上游 **8.3.3.2 打包与称重（warehouse，V121）** 拥有，两张表都
  `ENABLE/FORCE ROW LEVEL SECURITY`；
- AGENTS.md「模块边界纪律」明令：跨服务禁止直读对方的表、跨库 FK 或视图。

所以「把 parcel 详情原样写进 channel、再连 warehouse 的库 SELECT」是明确违规。
**表所有者的读模型只能长在表所有者自己的服务里** ⇒ 迁移、端点、页面全部落 warehouse。

轨迹与交运信息**在本服务不存在**，由 `ParcelDetailUnavailable` 逐段标 `available=false` +
`ownerModule` + 原因，**不用空列表冒充「这一票没有轨迹」**（空列表在界面上与「确实没有记录」无法区分）。

### 2. 蓝图点名那句的落点：`parcel_item` → **销售订单行**，不是已有的 `outbound_line_id`

蓝图原话：「下游 = 10.3.1.1、12 财务（运费成本归集）。**parcel_item 与订单行的对应关系是
成本归集到行的基础，缺了就只能算到订单级。**」

开工会发现 `parcel_item` **已经有** `outbound_line_id` → `outbound_order_line`，看起来「订单行」已具备。
但那是**出库单行**（warehouse 自己的计划快照），而 12 财务要摊运费的那一行是 **trade 服务的
`sales_order_line`（V99）**。两者之间**没有**任何可走的路：

- 跨服务不建 FK（AGENTS.md）；
- warehouse 手上只有 `outbound_order.source_external_ref` 这个**订单号字符串**，没有 trade 的 uuid。

缺这一环，运费就只能回答「这一单运费多少」，回答不了「第 2 行这件货分摊了多少运费」——
**这正是蓝图说的「只能算到订单级」**。

⇒ V125 新建 `parcel_item_sales_order_line`，显式补上这一环。

### 3. 建成表而不是加一列（因为要多带两样东西）

加一列只能存一个 uuid，答不出**「按什么口径匹配上的」**与**「这一行分到几件」**，
而这两条正是 12 财务敢不敢拿它分摊的依据：

- `allocated_quantity` —— **归集分母**，本箱分给该销售订单行的件数，
  由触发器钉死**等于该明细项的实装件数**（分母错一分运费就错一分）；
- `match_basis` —— **匹配口径**，强关联（`SKU_UNIQUE_ALIGNED`）与弱关联（`SKU_NAME_FALLBACK`）
  分开，财务可据此决定要不要追加人工复核。把两者画成同一种绿色是最危险的简化。

### 4. 匹配口径靠 **SKU 唯一键**，不靠行号

`outbound_order_line`（V72）与 `sales_order_line`（V99）**两边都有** `UNIQUE(tenant_id, order_id, sku_id)`
——「一张单里同一个 SKU 只有一行」。于是

```
parcel_item.sku_id → outbound_order_line.sku_id → sales_order_line.sku_id
```

是一条**在两边都唯一**的路径。行号在两个服务里各自编号，**按行号对齐是纯猜**。

只有 `source_type='SALES'` 的出库单才有销售订单行：调拨/采购退货/加工三类**不写行**，
详情里如实报「本包裹所属出库单不是销售来源」，绝不硬造一个 uuid 填上去。

### 5. 恒等式是最要紧的不变量

```
matchedQuantity + unmatchedQuantity == totalQuantity
```

归集到行还是退回订单级完全取决于有没有这条等式。服务层读回后**断言它**，对不上宁可 500 也不返回
一个自相矛盾的归集结论。退回订单级时逐条列出没关联上的明细项（能追到具体是哪几件货），
而不是静默按订单摊。

## 文件清单

- `backend/services/warehouse/src/main/resources/db/migration/V125__parcel_detail.sql`
  （新建表 + 4 个索引 + 2 个触发器 + RLS；**刻意不给 warehouse_app UPDATE**）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ParcelDetailModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ParcelDetailService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ParcelDetailController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ParcelDetailIntegrationTest.java`
- `apps/web/src/components/warehouse/parcel-detail-model.ts` + `.test.ts`
- `apps/web/src/components/warehouse/parcel-detail-workspace.tsx`
- `apps/web/src/app/warehouse/parcels/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（warehouse 分组**内层** alternation 追加 `parcels` 一支）
- `packages/api-contracts/src/contracts/warehouse.ts` + `warehouse.openapi.json`（codegen 产物）

## 关键 trade-off

- **只读视图，不提供任何写操作端点**：包裹与明细项是 8.3.3.2 的凭据（V121 的守卫直接拒已结束的
  打包单再改），本模块一个字节都不改写那些表。`actions` 段只给跳转与前置条件。
- **不建第二个打印引擎**：面单读 8.3.4.2 已交付的 `print_job` / `print_target`，不自造 ZPL 渲染
  （会出现两份「印的是什么」）。
- **体积重与运费都不在本模块算**：除数（6000/5000/…）是承运商渠道口径，归 10 物流；
  金额归 12 财务。在这里先乘一个除数就是替渠道定价。
- **api-proxy 白名单**：只在自己的服务分组里**追加**一支，未重排/未格式化任何其他服务分组。
  `capabilities` 排在 `[0-9a-f-]+` 通配之前（alternation 首个命中即返回）；
  子路径**不带**可选量 —— 本模块没有列表端点，裸 `parcels` 必须 404。

## 后续待办

- `parcel_item_sales_order_line` 的**写入方**（对齐作业）尚未交付：目前只有 10.2.2.1 的读模型与
  DB 守卫，归集到行需要上游把关联写进来。trade 侧的 `GET /trade/sales-orders/detail/fulfillment-reference`
  已交付且**带 lines**，是对齐作业的现成授权 API（本模块未调用，跨服务调用留给对齐作业那条车道）。
- 轨迹（10.3.1.1）、交运信息（8.3.4.1）、买家地址（9.1.1.2）、异常件（10.3.1.2）交付后，
  本模块的 `unavailable` 段逐条去掉灰块即可，无需迁移。
