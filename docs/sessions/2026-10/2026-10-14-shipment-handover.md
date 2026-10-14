# 8.3.4.1 发货交接（阶段 D4，L3 锚点 8.3.4）

车道 `feat/8-3-4-1` · 基线 `integration/module-dev` @ `f002cc8f` · 2026-10-14

## 决策摘要

蓝图原文只有一句关键约束：**「实发扣减与预占消费在同一事务，避免预占悬空或重复扣减」**。
本模块的全部设计重量都压在这一句上，其余取舍都由它推导出来。

1. **「同一事务」做成 DB 级硬约束，而不是服务层的纪律。**
   `shipment_handover` 的状态守卫在置 `HANDED_OVER` 时逐行核对：实扣量**恰好**等于应发量、
   每一行都落到具体库存行、挂着出库台账流水。应用层写不出更小的实扣量 —— 明细行的扣减守卫
   在 UPDATE 时把 `deducted_quantity` 强制改写成 `quantity`。
   「恰好」而不是「≥」：多了是超卖，少了是「已交接但货还在仓里」，两者都是事故。

2. **「预占」在本服务是**两本账**，交接时两本一起动。**
   这是本次最容易做错、也最值得写下来的一处：
   - `stock_bucket.reserved` / `inventory_balance.reserved` 是 **7.1.1.1 的实物预占**，
     由 V2 的 `available >= 0` 守着；
   - `reservation_allocation`（V107）是 **7.2.1.2 的承诺账**，V112 文件头明写它
     **不碰** balance / bucket。

   所以「预占转实扣」= 实物侧（on_hand 与 reserved 一起减 + 写带符号 `inventory_entry`）
   ＋ 承诺侧（allocation `COMMITTED→CONSUMED` + `reservation_entry` + 父预占回写）。
   `reserved` 减 `min(该行当前 reserved, 本次出库量)`：拣 6 件只预占了 4 件就减 4 ——
   硬减 6 是在编账。**不减 reserved 的话 `available` 立刻为负被 DB 拒，那正是「预占悬空」在 DB 层的形态。**

3. **重复扣减在 DB 层被堵三次**：`UNIQUE(tenant_id, parcel_id)`（一箱只能进一次交接）、
   `UNIQUE(tenant_id, outbound_order_id)`（一张出库单一张交接）、台账的
   `UNIQUE(tenant_id, ref_type, ref_id)`（重复投递撞唯一而不是第二次扣库存）。

4. **刻意不建的东西**（每条都有理由，不是省事）：
   - 不建「出库分录表」—— 权威是 V1 已有的 `inventory_transaction`/`inventory_entry`，
     本模块是写入方之一；再开一本就会出现两份「这批货扣了多少」。
   - 不建「承运商目录表」—— 主数据归 10 物流，建一份就会有两个「有哪些承运商」的答案。
   - 不建第二套轨迹 / 不自己回写平台 —— 那是 10 与 2 的下游动作，跨服务调用被模块边界纪律禁止。
     本模块只产出 append-only 流水与冻结重量体积给它们消费，并在 capabilities 里显式报
     `available=false + ownerModule`。

5. **命名空间 `/warehouse/shipment-handovers`，不挂 `outbound-orders` 之下。**
   出库单是履约单据，交接是现场作业凭据（自带冻结的包裹、实测重量体积、交接人与时刻）。
   挂成子资源会让契约以为交接过从属于某一张出库单，而「这一箱有没有被重复扣过」是包裹级问题。
   权限码复用 `warehouse.inventory.read/update`，不新起没注册的码。

## 关键 trade-off

| 取舍 | 选择 | 代价 |
| --- | --- | --- |
| 扣减落点怎么定 | 优先打包区（SHIPPING）库位的 bucket，打包区不够再顺延；**取不足就整单失败** | 不做「能扣多少扣多少」，现场遇到差异要先去查账 |
| 一个明细行可能消耗多条预占分配 | 另开 `shipment_handover_reservation` 行表 | 多一张表；换来「哪几条预占被这次吃掉」完整可查 |
| 交接人何时登记 | 放在**确认**那一步，与交接时刻成对落库 | 登记运单号时还不能填人（先填人会被 `(handover_user_id IS NULL) = (handover_at IS NULL)` 当场拒） |
| 承运商与运单号 | 两个字段，缺一不可 | 比一个「物流信息」字段啰嗦，但「有渠道没号」和「有号没渠道」两种事故都被堵住 |

## 文件清单

**新增**
- `backend/services/warehouse/src/main/resources/db/migration/V123__warehouse_shipment_handover.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ShipmentHandoverModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ShipmentHandoverService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ShipmentHandoverController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ShipmentHandoverIntegrationTest.java`（21 例）
- `apps/web/src/components/warehouse/handover-model.ts` / `.test.ts`（21 例）
- `apps/web/src/components/warehouse/handover-workspace.tsx`
- `apps/web/src/app/warehouse/shipment-handovers/page.tsx`
- `apps/web/src/app/api/warehouse/shipment-handover-allowlist.test.ts`（8 例）
- 本文件

**改动**
- `apps/web/src/lib/api-proxy.ts` —— **只在 warehouse 分支的 alternation 里追加一个分支**，
  外层括号与 `^…$` 锚点原样不动，另加一段说明新形状的注释。
- `packages/api-contracts/src/contracts/warehouse.ts`（+19 类型）与
  `packages/api-contracts/src/warehouse.openapi.json`（codegen 产物，**未手改**）。
- `docs/product/capability-blueprint/module-progress.md` —— **只改 8.3.4.1 那一行的状态与证据两格**。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| 迁移 | 自建 PG16 全量灌迁移（`.tmp/check-migration.sh`） | `ALL MIGRATIONS OK` / 5 表 / 5 张 RLS / 9 个触发器 |
| L0 后端 | `./mvnw -pl services/warehouse -am compile` | 通过 |
| L0 前端 | `pnpm typecheck` | 3 successful |
| L1 模块 | `./mvnw -pl services/warehouse -am test` | **1443 例 / 0 失败 / 1 错误** |
| L1 前端 | `pnpm vitest run <本模块两个文件>` | 29 例通过 |
| 白名单回归 | `pnpm vitest run api-proxy*.test.ts app/api/warehouse` | **241 例通过**（含 106 例 union 求并测试） |
| lint | `pnpm lint` | 通过（仅既有模块的 warning） |

**那 1 个错误与本模块无关**，是 8.3.2.2 的既有挂钟依赖：
`PickWaveIntegrationTest.plannedPickAtIsDerivedFromTheRuleSlotInWarehouseLocalTime` 把波次规则时段
写死成 `[9,18)`，而候选出库单的仓本地小时取自**当前时刻**；本次运行在 19:28（Asia/Shanghai），
错误信息里候选样本明写 `时区小时=19`，19 点跑必然落空。已单独复现确认。**本车道不修 8.3.2.2 的测试（越界）**。

## 后续待办

- 10 物流交付后：`shipment_handover_event` 与 `master_waybill_no` 已有稳定契约，
  10 侧可直接订阅；本模块**不需要**改动，届时只需把 capabilities 里那条 `unavailable` 翻成可用。
- 2 渠道的平台回写同上。
- 9 订单的「已发货」推进需要一条**授权事件或订阅**，本模块已具备 `outbound_order.status='SHIPPED'`
  这一事实；跨服务推送契约属 9.x 车道的范围。
- 8.3.2.2 的 `PickWaveIntegrationTest` 建议把规则时段改成相对当前时刻派生（或注入固定时钟），
  否则每天 18:00–09:00 都会红。本车道不越界修改。