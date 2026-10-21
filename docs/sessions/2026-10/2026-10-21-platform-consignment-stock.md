# 2026-10-21 7.7.2.2 平台托管与全托管备货（D3 卷 07）

> 车道 `7.7.2.2`，分支 `feat/7-7-2-2`，集成头基线 `integration/module-dev`。
> 后端主责服务：**warehouse**。上游：2.3.2.1 经营模式（channel）、10.4.2.2 备货预报（channel）。
> 下游（未交付，只出数据不代做）：12.3.1 结算、13.4.1.1 多模式利润。

## 决策摘要

**「备货 ≠ 自有库存」写成三道数据库不变量，而不是一句注释。**

蓝图 07-inventory.md:294 把这句写成加粗句（:5 的「不做」里也点名「不在寄售/代销下虚构自有库存」）。
本模块的落地是三件事，任何一件被绕开都写不进数据：

1. `consignment_stock_order_line.counts_as_owned_asset` 恒 false 且 CHECK 写死；
2. **在 `inventory_entry` 上挂触发器 `guard_consignment_stock_entry_not_added`** ——
   凡 `ref_type='CONSIGNMENT_STOCK'` 的库存分录，`on_hand_delta` 只许 ≤ 0（货从我方仓出库是负数，
   本就该有）、`reserved_delta` 必须 = 0（平台在库不占我方预占）。
   「把平台在库并进自有库存余额」这条加粗约束在**写入那一刻**就被数据库挡住，
   绕过服务层直连 SQL 也一样被拒；
3. 明细表里**没有第二个数量来源**：平台在库 / 平台已售 / 结算未结全部由平台回执推导、不落库 ——
   落了就会漂移，而漂移的方向一定是「台账说有货、平台说没货」。

本模块**只读** `inventory_balance` 做分账对账，**从不写** `stock_bucket` / `inventory_balance`
（那是 7.1.1.3 那条线的唯一写入所有者）。

其余关键决策：

- **四个口径是四个字段，不是一个「库存量」换着名字用**：备货量（我方发出）/ 平台在库
  （入库回执 − 出库回执）/ 平台已售（出库回执）/ 结算未结（已售未结）。
  「备货量 − 平台入库」是**在途未确认**，不是差异损失；差异另有**有符号的量**与四种口径之一
  （完全对平 / 在途未确认 / 平台多收 / 平台出库超过入库）。
- **结算按平台规则（可能按件或按日）而非我方发货确认**做成**两套真的算出不同结果的算法**：
  按件 = 已售件数 − 已结算件数；按日 = 落在**尚未结算的日期区间**内的售出件数。
  集成测试用同一组回执在两种口径下断言未结量 **25 ≠ 20** —— 口径是装饰的话两者会相等。
  口径离开草稿态即冻结（触发器 `guard_consignment_stock_basis_frozen`）：
  中途改口径会让未结量出现两个答案，而未结量是要交给 12 结算的数。
- **经营模式（2.3.2.1）只读、不建影子表，但真的起作用**：经只读端口读 channel 的
  `GET /channel/shops/{id}`，建单时快照 `operating_mode`（是「建单当时的事实」，不是第二个权威）。
  自发货模式下不存在「发给平台仓备货」这回事 → 建单直接 400 并说明去哪儿；
  只有全托管 / 半托管允许。channel 不可达 → **503 报「读不到」**（绝不猜一个模式）；
  channel 正常但没有这家店 → `known=false`（**无记录 ≠ 模式已知**）。
- **备货预报（10.4.2.2）在 channel，禁止跨服务 SQL**：经只读端口读
  `GET /channel/stock-forecasts/{id}` 预填备货行，读到的 `forecast_id` 只是定位键，
  本库不建外键不建影子表。上游不可达 → 503（**绝不拿空单冒充「没有备货需求」**）；
  上游正常但没有这张预报 → `known=false`。
- **台账是推导出来的，不落第二份真相**：备货量在明细行，平台收发在回执表，结算在结算回执表；
  台账 = 一条 SQL 聚合出来的读模型，落成表就多一个会漂移的地方。
  对账里的「与自有库存对账」也因此能给出**真实结论**：差异量 = 平台入库回执累计 − 备货量，
  配四种口径之一。
- **平台在库数只有平台回执能证明**：确认平台收货**必须先有入库回执**（409）——
  我方发货确认不了平台收没收到；登记出库回执与结算回执同理，货没发出去就不许登记。
- **未结不为 0 不许关单**（409）：结清之前关掉单据，这笔未结就没有单据可追。
- **契约撞名避让**：记录名统一 `PlatformConsignment*` 前缀 —— 7.7.1.2 已占用 `Consignment*`，
  而 OpenAPI 契约按简单名拍平，同名 record 会互相覆盖且**不报冲突**
  （仓库里已踩过 warehouse `ScopeOption`、11.2.3.1 与 9.x `QualityEventRow` 两次）。

## 关键 trade-off

- **触发放进既有表（`inventory_entry`）vs 只在应用层判断**：选了 DB 触发器。
  应用层判断挡不住「绕过服务层直连 SQL」，而那正是这类红线最常见的失效方式。
  代价是给 7.1.2.1 的表挂了一个触发器 —— 但它只对 `ref_type='CONSIGNMENT_STOCK'` 的分录生效
  （既有模块的 ref_type 词表与之不重叠），且不改任何列 / 约束 / 索引，回归面 = 0。
- **台账推导 vs 台账落表**：选了推导。四口径全部来自回执的实时聚合，
  落表等于多一个必须与回执同步的第二真相。
- **结算金额可空 vs 记 0**：选了可空。平台没给金额就如实留空（null = 平台没给），
  填 0 会被读成「平台结了 0 元」，那是谎报。金额归 12 财务，本模块不替它记账。
- **api-proxy 新增独立元素 vs 并进既有 warehouse 长 alternation**：选了新增独立元素。
  既有那支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
  前缀用 `consignment-stock` 与 7.7.1.2 的 `consignments` 明确分开 —— 两个名字只差一个词，
  挨太近会让人把寄售协议与平台托管备货单当成同一件事。

## 文件清单

后端（warehouse 服务）：

- `backend/services/warehouse/src/main/resources/db/migration/V244__consignment_stock_ledger.sql` ——
  五张表 + RLS + 授权 + 九个触发器（分账红线 / 状态机 / 口径冻结 / 明细冻结 / 守恒两条 /
  回执与审计 append-only）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ConsignmentStockModels.java` ——
  传输模型（27 个 `PlatformConsignment*` 类型）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ConsignmentStockService.java` ——
  jOOQ 显式 SQL + 口径计算
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ConsignmentStockController.java` ——
  16 个端点，挂 `/warehouse/inventory/consignment-stock`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ChannelShopModePort.java` ——
  2.3.2.1 经营模式只读端口
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ChannelForecastPort.java` ——
  10.4.2.2 备货预报只读端口

后端测试：

- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ConsignmentStockTestBase.java` ——
  PostgreSQL 16 容器 + identity 桩 + **channel 桩**（分别模拟「读到 / 404 / 500」三种情况）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ConsignmentStockIntegrationTest.java` ——
  31 例
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ConsignmentStockTenancyIntegrationTest.java` ——
  3 例（租户隔离三断言）

契约（codegen 产物，只由脚本改）：

- `packages/api-contracts/src/contracts/warehouse.ts` —— 新增 27 个 `PlatformConsignment*` 类型
- `packages/api-contracts/src/warehouse.openapi.json` —— 新增 16 条路径

前端：

- `apps/web/src/app/inventory/consignment-stock/page.tsx`
- `apps/web/src/components/inventory/consignment-stock-model.ts`
- `apps/web/src/components/inventory/consignment-stock-workspace.tsx`
- `apps/web/src/components/inventory/consignment-stock-model.test.ts`
- `apps/web/src/app/api/warehouse/consignment-stock-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**（锚在 7.8.2.2 元素之后）

台账与日志：

- `docs/product/capability-blueprint/module-progress.md` —— 只改 7.7.2.2 那一行的「状态」「证据」两格
- `docs/sessions/2026-10/2026-10-21-platform-consignment-stock.md` —— 本文件

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` 通过；`pnpm typecheck` 通过
- L1 模块闸：
  `./mvnw -pl services/warehouse -am test -Dtest='ConsignmentStock*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **34 例全绿**（ConsignmentStockIntegrationTest 31 + ConsignmentStockTenancyIntegrationTest 3）
- 前端：`pnpm vitest run apps/web/src/components/inventory/consignment-stock-model.test.ts
apps/web/src/app/api/warehouse/consignment-stock-allowlist.test.ts` → 17 例全绿
- **api-proxy 全量 allowlist 回归**（因为改了 `apps/web/src/lib/api-proxy.ts`）：
  `pnpm vitest run apps/web/src/app/api` → **126 个文件 / 728 例全绿**，
  含兄弟车道已交付的 `inventory/vmi/*`、`inventory/consignments/*`、
  `inventory/stockout-alerts/*` 形状（回归基线写进本车道的 allowlist 测试最后一组断言）

## 后续待办

- **12.3.1 结算**：本模块只出「结算口径标记 + 未结量」，12 交付后按这两个读回逐笔对账。
  本模块不代 12 记应付、不建跨服务 FK、不查对方的表。
- **13.4.1.1 多模式利润**：本模块按经营模式（2.3.2.1）分组给出台账口径，不出利润数字。
- **平台仓自动采集**：平台回执目前靠人工 / 运营登记（`source_system=MANUAL`），
  平台侧采集交付后改由平台推送（`PLATFORM_FEED`），本表即为观测留痕。
- **按日口径的时区**：未结量按 **UTC 日历日**归期（平台仓时区差异由平台给的结算区间体现）。
  若平台仓时区需要独立建模，是后续的口径扩展点。
- **nav / task-pages 注册**：`/inventory/consignment-stock` 未登记进
  `packages/ui/src/lib/task-pages.ts` —— 7.7.1.1 / 7.7.1.2 / 7.7.2.1 三个兄弟模块同样未登记，
  本车道按既有惯例保持一致（该注册表多车道共用，单独追加一行反而与兄弟不齐）。
