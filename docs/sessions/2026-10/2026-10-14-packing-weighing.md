# 8.3.3.2 打包与称重（阶段 D4）

- 日期：2026-10-14
- 车道：`.worktrees/8.3.3.2`，分支 `feat/8-3-3-2`，基线 `integration/module-dev`（d2568f11）
- 迁移版本：V121（调度器预分配）

## 决策摘要

**上游只接 8.3.3.1，不造第二个复核数据源。** 开打包单的唯一入口是一张 `status='PASSED'`
的 `recount_session`，且该约束写在 V121 的 BEFORE INSERT 触发器里（绕开服务层直连 SQL 也拒）。
复核基线在开单瞬间**冻结**到 `packing_order` 上，不每次去 join `recount_line` —— 打包是凭据的
一半，半年后要能答出「这一箱当时是按哪一版基线装的」。

**蓝图那句「包裹必须含明细项」是硬约束，不是提示。** 三层落地：
`parcel_item.recount_line_id` NOT NULL + 复合外键（没有来源行的明细就是凭空装进去的东西）；
PACKED 触发器要求每个包裹至少一条明细；每行基线的实装件数**恰好等于**应发件数。
只有重量和运单号无法追溯与对账 —— 这一句是本模块存在的理由，所以没有留成软提示。

**实测重量体积全部由触发器派生。** 净重 = 毛重 − 包材自重、体积 cbm = 长×宽×高/10⁶、
打包单合计从包裹与明细汇总。服务层只读回来，不自己算 —— 服务层算的数与账上的数可能差一位。
口径写死在列名里：重量克 `numeric(19,3)`、尺寸厘米 `numeric(14,3)`、体积立方米 `numeric(19,6)`，
全程禁浮点。

**三个「不替别人做」的决定：**

| 决定                                   | 归属方                | 理由                                                           |
| -------------------------------------- | --------------------- | -------------------------------------------------------------- |
| 体积重（除数 6000/5000）               | 10 物流               | 除数是承运商渠道口径，在这里先乘就是替渠道定价                 |
| 面单内容与打印次数                     | 8.3.4.2（已交付 V73） | 只挂 `print_job` 引用 + 计数，自造渲染会出现两份「印的是什么」 |
| SKU 物理档案（3.7.2 长宽高/净重/毛重） | catalog（V6/V28）     | 跨服务读表被模块边界纪律禁止，且未交付读取端口                 |

四段相邻能力在 `capabilities` 与详情里逐条 `available=false` + ownerModule + href + 原因，
照 8.3.1.2 的灰块约定 —— 空的包裹列表会被读成「这单没有包裹」，而不是「这段能力还没接入」。

**打包对库存什么都不做。** 不写 `inventory_entry`、不释放预占也不实扣（实发扣减与预占消费在
8.3.4.1 的同一事务）。出库单只被推 `PENDING_PACKING → PENDING_SHIPMENT`（完成）或退回
`PENDING_RECHECK`（失败），**从不碰 SHIPPED**。

## 关键 trade-off

1. **包材做成真实目录而不是自由文本。** 多一张 `packing_material` 表、多四个端点，换来
   「选错包材」与超载能在 DB 层当场拒（实测三边超包材外形 5mm 公差、毛重超承重、毛重低于自重）。
   代价是现场要先建包材档案。判断：真实仓库的箱型是有限的目录，蓝图「选择包材」也是选择而非填写。
2. **序列号品强制一行一号，非序列号品允许一行多件。** 中途我一度给非序列号品也加了
   「件数恒为 1」的规则，集成测试立刻证明它是错的：同型号两件装一箱是常态，而蓝图要的正是
   `parcel_item` 记「多少件」。已改回：只有 `requires_serial` 的基线行受逐件约束。
3. **`exists()` 用 `fetch(...).isEmpty()` 而不是 `fetchOne(...)`。** jOOQ 的 `fetchOne`
   在多于一行时抛 `TooManyRows`，而「这个基线行在不在」本来就允许命中多行（一条复核单两行基线是常态）。
   兄弟模块的同名 helper 有同样的隐患，本车道未越界修改。

## 文件清单

**新增**

- `backend/services/warehouse/src/main/resources/db/migration/V121__warehouse_packing_weighing.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/PackingModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/PackingService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/PackingController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/PackingIntegrationTest.java`
- `apps/web/src/components/warehouse/packing-model.ts`
- `apps/web/src/components/warehouse/packing-model.test.ts`
- `apps/web/src/components/warehouse/packing-workspace.tsx`
- `apps/web/src/app/warehouse/packing-orders/page.tsx`

**改动（codegen 产物与共用文件，只碰自己那部分）**

- `packages/api-contracts/src/contracts/warehouse.ts`（+25 行纯增量）与 `warehouse.openapi.json`
- `apps/web/src/lib/api-proxy.ts`（warehouse 分支内层 alternation 追加 `packing-orders` 一支，未重写整段正则、未清理重复分支）
- `docs/product/capability-blueprint/module-progress.md`（**只改 8.3.3.2 那一行的状态与证据两格**）

## 验证

- L0：`cd backend && ./mvnw -q -pl services/warehouse -am compile` 通过；`pnpm typecheck` 通过
- L1：`./mvnw -pl services/warehouse -am test -Dtest=PackingIntegrationTest` → **21/21 通过**
  （真 PostgreSQL 16 + Testcontainers，池容量 1，含租户隔离三断言）
- 前端：`pnpm vitest run apps/web/src/components/warehouse/packing-model.test.ts apps/web/src/lib/api-proxy.test.ts`
  → 30 例全绿
- 迁移另在一次性 PG16 容器上把全部 48 个迁移顺序灌过一遍（`.tmp/check_migration.py`），确认 SQL/plpgsql 语法无误

## 后续待办

1. **正向 CUSTOMS_DECLARATION_LINE 快照分支未覆盖**（如实记下的缺口）：造一条真报关明细要求它的
   来源发运行已有**在途腿**（7.5.1.2 的发货分录守卫），属本车道之外的链路。集成测试只钉住了
   NOT_FOUND 与「不编 HS 编码」这一侧；正向分支与它走的是同一个 SELECT。
2. **8.3.4.1 发货交接**接手时，`parcel` 已有实测三边与毛重可直接取用；届时需确认运单号落在
   `parcel` 还是发货单上（本模块刻意没放，避免两个写入所有者）。
3. **`packing_material` 的录入体验**目前只有建/查/停用四个端点，批量导入与按箱型自动选箱未做
   （蓝图未要求，属 8.3 之外的运营工具）。
4. 台账里 8.3.3.1 的 `capabilities.packingHandoffLive` 仍是 `false`，等本模块并入后可在该车道
   或后续统一翻正（未在本车道越界改它的文件）。
