# 8.3.1.1 出库单列表

- **日期**：2026-10-07
- **车道**：`feat/8-3-1-1`（worktree `.worktrees/8.3.1.1`，基线 `integration/module-dev`）
- **结论**：已交付。8.3 出库作业的第一块地基。

## 做了什么

在 `backend/services/warehouse` 落地 8.3.1.1「出库单列表」，并把 `outbound_order` /
`outbound_order_line` 建成 8.3 整条链路的载体表。8.3 此前是空的（12 个迁移、33 张表，
无任何出库单/拣货/打包/发货表），下游 8.3.2.1 拣货、8.3.3.x 复核打包、8.3.4.1 发货交接
都要引用这两张表。

- 迁移：`V72__outbound_order_list.sql`（4 张表 + 5 个触发器 + RLS/GRANT）
- 后端：`OutboundOrderModels` / `OutboundOrderService` / `OutboundOrderController`（7 个端点）
- 测试：`OutboundOrderIntegrationTest`（32 项，真实 PostgreSQL 16 + Testcontainers，池容量 1）
- 前端：`/warehouse/outbound` 工作区 + `outbound-order-model.ts` + 模型单测（13 项）
- 契约：`packages/api-contracts` 由后端 spec 重生成（800 DTOs）

## 关键 trade-off

### 1. 建单端点不在蓝图职责清单里，但它必须有

蓝图给 8.3.1.1 的职责只有列表与三项批量操作。但不建单，列表永远是空的，空列表撑不起
任何一条能力。上游 9 订单履约尚未交付 —— 与其等一个不会来的上游，不如把「履约动作的
载体」这件事本身落地，并在 `/capabilities` 里如实声明：**由本模块建单不代表 9 订单已交付**，
销售来源仍只存外部单号。

### 2. 「出库单 ≠ 销售订单」必须落在数据形状上，不能只写在注释里

蓝图点名「一订单可多出库单（分仓/分批）」。做法：外部单号存 `varchar(64)` 而不是指向订单表
的 UUID 外键，并且**刻意不加** `(tenant_id, source_external_ref)` 唯一索引 —— 加了这条索引
就等于把分仓/分批在数据上禁掉了，那正是这条关系要表达的相反面。集成测试实跑同一外部单号下
并排挂两张单。

### 3. 四类来源按可信度分三种表达

| 来源                                 | 上游                                    | 表达                              |
| ------------------------------------ | --------------------------------------- | --------------------------------- |
| SALES / PURCHASE_RETURN / PRODUCTION | 9 订单 / 6 采购 / 10 加工，**均未交付** | 只存外部单号字符串，跨服务不建 FK |
| TRANSFER                             | 7.5.1.1，**已交付且同服务**             | 真复合外键到 `transfer_order`     |

TRANSFER 建单时还校验发货仓 = 调拨单 `from_warehouse_id`，且每个明细 SKU 都在该调拨单明细里 ——
发一个调拨单上没有的 SKU 在业务上不可能成立。CHECK 钉死两种表达互斥。

### 4. 七态状态机：Java 枚举与 DB 触发器是同一张白名单的两处实现

合法跃迁：

| from                    | → to                                               |
| ----------------------- | -------------------------------------------------- |
| PENDING_PICKING 待拣    | PICKING / CANCELLED                                |
| PICKING 拣货中          | PENDING_RECHECK / CANCELLED                        |
| PENDING_RECHECK 待复核  | PENDING_PACKING / **PICKING** / CANCELLED          |
| PENDING_PACKING 待打包  | PENDING_SHIPMENT / **PENDING_RECHECK** / CANCELLED |
| PENDING_SHIPMENT 待发货 | SHIPPED / CANCELLED                                |
| SHIPPED 已发货          | （终态，无去向）                                   |
| CANCELLED 取消          | （终态，无去向）                                   |

两条返工边（加粗）不是随手加的。**复核不通过为什么不退回「待拣」**：待拣的含义是「一件都没拣」，
而复核不通过时货已离开库位、正处在返工循环里。退回待拣会让待拣同时表达「没开始」与「拣错重来」，
作业台按状态分组时这两种单子必须分开处理。这个选择同时让「一张出库单只生成一次拣货任务」
（`UNIQUE(tenant_id, order_id)`）站得住 —— 拣货任务本来就是一次性交接，不是反复重发的工单。

集成测试把 7 态 × 6 目标 = 42 个组合全部实测，逐条比对 Java 矩阵与触发器白名单没有分叉。

### 5. 「生成拣货任务」只做到交接为止，不假装拣货任务存在

蓝图把 8.3.2.1 列为下游，而它拥有拣货任务表、拣货明细（实际库位/批次/序列号）、路径排序与
异常上报。本模块**不建那张表**，真实做三件事：校验待拣状态 → 落一行 `outbound_pick_request`
（含行数与件数快照）→ 状态推进到拣货中并留审计。8.3.2.1 交付后按 `(tenant_id, order_id)`
读这张表接手。界面上「生成拣货任务」之后显示的是**拣货中**，不是「已拣货」。

### 6. SKU 行数与件数现算，不存列

存列就一定会与明细漂移，而这两个数恰恰是作业员最先会对着明细核对的。走
`outbound_order_line(tenant_id, order_id)` 索引聚合。

踩到的坑：**LATERAL 派生表会让 jOOQ 无法为列命名**，读 `get("sku_line_count")` 抛
`Field "sku_line_count" is not contained in row type`。改成相关标量子查询才拿到别名。

### 7. 打印返回真正要印的内容

不是「已提交打印」的空壳：返回单头 + 全部明细行，逐单留 PRINTED 审计。只支持出库单与拣货单
两种 —— 面单/箱唛/标签属 8.3.4.2 并行车道，放一种就是抢它的表与端点。

### 8. 边界刻意收紧

- **没有 `GET /{id}` 详情**：属 8.3.1.2「出库单详情」。放行一个还不存在的形状比 404 更糟。
- **没有 DELETE**：出库单是履约凭证，只能取消（留痕），不能删掉。
- 打印走 `read` 权限，其余写操作走 `update`，与 7.x 同一套。

## 踩过的坑（值得留给下一个人）

1. **PostgreSQL 里任何报错都会中止整个事务**。第一版测试把「探测非法跃迁」与「复位状态」
   放进同一个事务，结果复位语句只会拿到 `current transaction is aborted` —— 测试因为
   错误的原因永远绿。现在每个探测开独立事务。**一个会因为错误原因通过的测试比没有测试更糟。**
2. **`InventoryController.selfOnly` 必须传完整权限码** `warehouse.inventory.read`。
   传短形式 `inventory.read` 时它匹配不到任何 grant，`orElse("ALL")` 静默退化成全租户 ——
   数据范围看起来"生效了"，实际从来没生效过。
3. **绑 null 给 `timestamptz` 列必须写 `?::timestamptz`**，否则 PG 报
   `timestamp with time zone vs character varying`。非 NULL 时不炸，所以很容易漏。
4. **批量操作里的写顺序**：先推进状态（带旧状态条件的乐观更新）再落交接请求。反过来会留下
   「状态仍是待拣却已存在交接请求」的脏数据。
5. **jOOQ 绑定占位符与列数必须对齐**：明细行 insert 一度 8 个占位符对 9 列。

## 文件清单

**新增**

- `backend/services/warehouse/src/main/resources/db/migration/V72__outbound_order_list.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/OutboundOrderModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/OutboundOrderService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/OutboundOrderController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/OutboundOrderIntegrationTest.java`
- `apps/web/src/components/inventory/outbound-order-model.ts`
- `apps/web/src/components/inventory/outbound-order-model.test.ts`
- `apps/web/src/components/inventory/outbound-order-workspace.tsx`
- `apps/web/src/app/warehouse/outbound/page.tsx`

**修改**

- `apps/web/src/lib/api-proxy.ts`（只追加 `outbound-orders` 一支，未重写整段正则）
- `packages/api-contracts/src/catalog.ts` / `warehouse.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（只改 8.3.1.1 那一行）

## 影响面

只新建表，**不 ALTER 任何既有表**，因此没有跨车道回归面（其它 7 条车道的
`transfer_*` / 库存相关表一张都没碰）。新增 4 张表全部 ENABLE + FORCE RLS 并显式 GRANT
（V1 的 `GRANT ON ALL TABLES` 只覆盖当时已存在的表）。

## 验证

```
cd backend && ./mvnw verify                                  # 全绿
pnpm typecheck                                               # 3 successful
pnpm lint                                                    # 3 successful（0 warning 属于本模块）
pnpm test                                                    # 65 files / 1169 tests passed
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build    # 成功
node tools/module-progress/sync.mjs --check                  # 通过
```

`OutboundOrderIntegrationTest` 32 项，含租户隔离三断言（两租户互不可见 / 伪造 `X-Tenant-Id`
无效 / 连接池归还后不串租户，池容量固定为 1）、42 个状态跃迁组合的矩阵一致性、
`timestamptz` 写回断言与 `numeric(19,6)` 口径断言。

## 后续待办

- **8.3.1.2 出库单详情**：消费 `outbound_order_line` + `outbound_order_event`（作业时间线已在库里）。
- **8.3.2.1 拣货任务**：按 `(tenant_id, order_id)` 读 `outbound_pick_request` 接手，
  在完成拣货实体时调用同一套状态机推进到 `PENDING_RECHECK`。不要新建出库单表。
- **8.3.3.1 / 8.3.3.2 / 8.3.4.1**：状态机已就位，下游端点在完成各自实体时推进状态即可。
- **8.3.4.2 面单与标签打印**：本模块的 `OutboundPrintDocument` 刻意不含这几种。
