# 10.4.2.2 备货预报（阶段 D7，L3 锚点 10.4.2）

- **日期**：2026-10-21
- **车道**：`feat/10-4-2-2`（worktree `integration/.worktrees/10.4.2.2`）
- **蓝图原文**（`docs/product/capability-blueprint/10-logistics-and-freight.md` 210-215 行）：
  > 职责：向海外仓/平台仓提交备货计划：SKU 与数量、目的仓、期望到货时间、平台货件编号，接收仓端接收与拒收反馈。
  > 输入：FBA 发货计划或海外仓备货需求；库存与在途数据；平台货件要求。
  > 输出：预报单 + 平台货件创建任务 + 仓端回执。
  > 关系：上游 = 6.2.1.2 补货建议、7 在途；下游 = 2 平台接口（创建货件）、10.4.2.1。**平台仓备货有严格规格**（箱型、贴标、托盘、混装限制），规格不符会被拒收造成额外成本与延误。

## 决策摘要

### 1. 服务归属 = channel（**确认调度者的判断**）

调度者把本模块定在 channel，我逐条复核后**确认**，理由三条同时成立：

- 蓝图卷 10 卷首「后端归属：channel 拥有物流商连接与轨迹」；
- AGENTS.md「外部适配器（Shopify / Amazon / … 形态的第三方系统对接）放在 `backend/services/channel/`，每个 vendor 独立包」—— 海外仓/平台仓正是这种外部系统适配；
- **同 L3 的 10.4.2.1 海外仓连接已落在 channel（`V93__overseas_warehouse_connection.sql`）**，本模块的「目的仓」就是它的 `warehouse_account`。跨服务 FK / 跨库视图 / 跨服务查表被 AGENTS.md 明令禁止，所以「目的仓」与「预报单」**必须同服务同库**。

落地时遵守的边界：

- **纯新建 6 张表，零 ALTER 任何既有表**（含 10.4.2.1 的表）⇒ 跨车道 DDL 回归面 = 0；
- `external_stock_observation`（观测值快照表，DB 层拒 UPDATE/DELETE）**只读不写不 ALTER**；
- `warehouse_provider` 目录**不新增行**：`PLATFORM_FBA`（平台自建仓 FBA）在 V93 种子里已存在且 `supports_forecast=true`、`supports_status_callback=true` —— 平台仓正是「严格规格」那条加粗句说的对象。

### 2. 平台货件创建任务**不做假 HTTP**

本仓库**没有真实的平台账号与网络出口**。写一个指向 `http://localhost` 或真实域名的 HTTP 客户端，只会造成一个「看起来能连、实际永远失败」的假能力；而蓝图要的是「预报单 + 平台货件创建任务 + 仓端回执」这一组**可真实驱动**的事实。

照抄 10.4.2.1 的既有形状（`warehouse_inbound_stock_line` 承接外部边界）：

- `warehouse_shipment_report` = **租户/通道登记的平台货件报送行**（来源 `PLATFORM_CALLBACK` 或 `MANUAL_REGISTRATION`）；
- 适配器 `submitForecast` 读这张表 → 返回 `ShipmentAck`（带 `platform_shipment_id`）；
- **平台货件编号是被登记进来的，不是本服务生成的**。没登记时任务进入 `FAILED`（可重试），**绝不凭空造一个编号冒充「货件已创建」**；
- 任务状态可推进（`PENDING→SUBMITTED→SUCCEEDED/FAILED`）、可查、可重试（`FAILED→PENDING`，`attempts` 累加）。

**适配器复用 `OverseasWarehouseAdapter`**（调度者明确要求）：新增 `default ShipmentAck submitForecast(ConnectionSettings, ForecastPlan)`，**既有方法签名一个没动**；默认实现抛接口自带的 `WarehouseProviderException`（「该服务商未开通预报提交能力」）—— 这是**能力协商**，不是占位符。新增实现 `warehouse.platform.PlatformShipmentWarehouseAdapter`（`PLATFORM_FBA`），vendor 独立包、同一统一接口，将来接真实平台只需再加一个实现，上层一行不改。

> ⚠️ 副作用（如实记录）：`PLATFORM_FBA` 进了适配器注册表，因此 10.4.2.1 的 `triggerSync` 对平台仓账户**不再**返回「尚未接入适配器」，而是走同一张租户报送行做库存同步。10.4.2.1 的 9 个用例**零回归**（已跑）。

### 3. 规格校验落成**结构化数据** + 提交前的**硬闸**

蓝图加粗句是本模块最重要的一条。三重落地：

1. **规格是可维护的数据**（`warehouse_forecast_spec`，按目的仓版本化），四类限制**各有自己的列**：
   - 箱型：`allowed_box_types` / `max_units_per_carton` / `max_weight_per_carton_kg` / `max_skus_per_carton`
   - 贴标：`requires_fnsku_label` / `requires_carton_label` / `allowed_label_formats`
   - 托盘：`pallet_required` / `max_cartons_per_pallet` / `max_pallet_weight_kg`
   - 混装：`mixed_loading_allowed`
2. **预报单必须先过规格闸才能提交**（状态机 `DRAFT → VALIDATED → SUBMITTED`）。校验不过返回 **422 + ProblemDetail 挂 `violations` 数组**，每条带规则码、规则说明、涉及 SKU、说明、**期望值与实际值**—— 逐条点名违反哪一条，而不是「提交成功然后被仓端拒收」。
3. **规格版本在建单时冻结**（预报单上的 `spec_id`）。换规格 = 建新版 + 停用旧版，**不就地改**；已建的单仍按冻结的那一版判定。不冻结的话，「按当时的规定验的」这句话没法追溯，拒收纠纷就没有依据。

## 关键 trade-off

- **规格不过 ⇒ 建单即被要求先配规格**：没有生效规格时 `createForecast` 直接 422（说清缺什么），而不是留一张注定过不了闸的单。代价是「先建单再慢慢配规格」这条路没有；收益是不会攒出一堆永远提交不了的死单。
- **明细离开草稿态即冻结**（DB 触发器 `guard_forecast_line_frozen`）：改内容 = 作废后另建一张单。代价是改一个错字要重开一单；收益是「已提交给平台仓的承诺」在数据上不可被悄悄改写。
- **货件任务失败时**服务层**不抛异常**，而是如实返回 `FAILED` 的任务体，由控制器用 **502** 告诉调用方。原因是租户事务在 RuntimeException 上会**回滚** —— 抛异常会把刚写下的 `FAILED` 一起回滚掉，现场看到的就是「任务还是 PENDING，什么都没发生过」。这是本轮实测踩到并修掉的一个真实坑。
- **预报单状态机与货件任务状态机在 DB 层各有一个触发器**（绕过服务层写 SQL 同样被拒），Java 侧同时用 `sealed interface ForecastState permits ForecastStatus` + enum 表达转换规则。两层各挡一次，不是靠服务层自觉。
- **仓端回执 append-only 双保险**：触发器 + `GRANT SELECT,INSERT`（**刻意不给 UPDATE/DELETE**）。测试用**应用账号**与**迁移超级账号各试一次**。
- **库存与在途走只读 Port，读不到就说读不到**：`ForecastInventoryQueryPort`（warehouse `GET /warehouse/inventory/sellable/skus`）与 `ForecastInTransitQueryPort`（`GET /warehouse/inventory/in-transit`，只取 `transitKind=FIRST_MILE`）。上游不可达 ⇒ **503 明确报「读不到」**，**绝不用 0 冒充**（0 会被读成「没有库存需求」）；上游正常但没有该 SKU 的行 ⇒ `null` + `*Known=false`（**无记录 ≠ 可售量为 0**）。
- **在途只能按 SKU 汇总，不分目的仓**：在途腿的 `toWarehouseId` 是 warehouse 服务的仓编码，与海外仓账户编码**不是同一套**，两者之间没有跨服务映射表。宁可如实标注口径，也不编一个「分目的仓的可售/在途拆分」。capabilities 里这一条 `available=false`。
- **可售量口径原样采用 warehouse 的，不自己重算**：两个地方各算一次必然漂移，而漂移的方向一定是「这里说能卖、扣的时候不够」。
- **契约不手改但也不越界**：`packages/api-contracts` 由 `tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成。生成后**只保留 channel 的两个文件**，把 catalog/trade 的漂移改动**还原**（那是另外三条车道的未再生物，不属于本车道）。另外发现并修掉一个真问题：我的 `CapabilityDimension` record 与 10.7.1.1 / 10.3.2.1 的**同名 record 冲突**，会把别人的 DTO 覆盖掉 —— 已改名 `ForecastCapabilityDimension`，重新生成后 channel.ts **纯增量 21 行、零删除**。

## 文件清单

### 迁移（channel 服务，V243）

- `backend/services/channel/src/main/resources/db/migration/V243__stock_forecast.sql` — 6 张新表 + 4 个守卫触发器 + 索引 + 2 个序列 + RLS(FORCE) + GRANT/REVOKE。**零 ALTER 任何既有表**。

### 后端（channel 服务）

- `backend/services/channel/src/main/java/com/starcon/channel/StockForecastModels.java` — 契约模型 + 状态机（`sealed interface ForecastState permits ForecastStatus` / `ForecastStatus` / `ShipmentTaskStatus`）+ `SpecRule`（15 条违规规则，每条带中文说明）。
- `backend/services/channel/src/main/java/com/starcon/channel/StockForecastService.java` — 服务层：建单 / 规格维护 / 列表（真分页 + 真筛选 + 真排序）/ 详情 / 规格校验 / 提交 / 作废 / 货件任务推进与重试 / 报送登记 / 回执登记与查询 / 可用性。
- `backend/services/channel/src/main/java/com/starcon/channel/StockForecastController.java` — 端点 `/channel/stock-forecasts/**`（**刻意没有 DELETE 段**）。
- `backend/services/channel/src/main/java/com/starcon/channel/StockForecastErrorHandler.java` — 把 `SpecValidationException` 渲染成 **422 + `violations` 数组**。
- `backend/services/channel/src/main/java/com/starcon/channel/warehouse/OverseasWarehouseAdapter.java` — **改动**：新增 `default submitForecast(...)` 与 3 个 record（`ForecastLine` / `ForecastPlan` / `ShipmentAck`）。既有方法签名未动。
- `backend/services/channel/src/main/java/com/starcon/channel/warehouse/platform/PlatformShipmentWarehouseAdapter.java` — 新增实现（`PLATFORM_FBA`）。
- `backend/services/channel/src/main/java/com/starcon/channel/ForecastInventoryQueryPort.java` — 库存只读端口。
- `backend/services/channel/src/main/java/com/starcon/channel/ForecastInTransitQueryPort.java` — 在途只读端口。

### 测试（channel 服务）

- `backend/services/channel/src/test/java/com/starcon/channel/StockForecastIntegrationTest.java` — 13 例。
- `backend/services/channel/src/test/java/com/starcon/channel/StockForecastTenancyIntegrationTest.java` — 3 例（多租户三断言）。
- `backend/services/channel/src/test/java/com/starcon/channel/StockForecastAvailabilityPortTest.java` — 6 例（两个只读 Port 的语义测试：对本地 stub HTTP server 跑，不需要数据库 —— 证明**模糊命中被挡掉**、**无记录是 `null` 不是 0**、**上游报错/不可达一律 503「读不到」**、**转发调用方自己的 bearer 且不传租户号**）。

### 前端

- `apps/web/src/components/logistics/stock-forecast-model.ts` — 契约类型 + API 出口 + 展示派生（含 `violationsFromError` 从 422 响应体取结构化违规）。
- `apps/web/src/components/logistics/stock-forecast-model.test.ts` — 8 例。
- `apps/web/src/components/logistics/stock-forecast.tsx` — 工作区（预报单 / 备货规格 / 能力与边界三个页签）。
- `apps/web/src/app/logistics/stock-forecast/page.tsx` — 页面路由。
- `apps/web/src/lib/stock-forecast-api-proxy.test.ts` — 白名单回归（真跑代理处理器，ALLOW 29 条 + BLOCK 12 条）。
- `apps/web/src/lib/api-proxy.ts` — **改动**：新增一条独立 `services[]` 元素 `stock-forecasts`（**既有 channel 超长 alternation 一字未动**）。

### 契约与台账

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts` — codegen 产物，纯增量。
- `docs/product/capability-blueprint/module-progress.md` — **只改 10.4.2.2 那一行**的「状态」「证据」两格。

## 后续待办

1. **平台货件接口直连**（蓝图下游「2 平台接口（创建货件）」）：拿到真实平台账号后新增一个 `OverseasWarehouseAdapter` 实现即可，上层服务与数据模型一行不用改。当前用租户/通道登记的报送行承接外部边界，capabilities 里 `platformShipmentApi` 恒为 `available=false`。
2. **6.2.1.2 补货建议自动带入**（蓝图上游）：6.2.1.2 已交付但尚未提供预报直连入口；本模块只登记 `source=REPLENISHMENT_SUGGESTION` 来源标记，capabilities 里 `replenishmentSuggestionFeed` 为 `available=false`。
3. **按目的仓的在途量**：需要 warehouse 与仓端账户之间有一张跨服务的编码映射（当前没有，且 AGENTS.md 禁止跨库视图），只能由上游补一条映射接口后才能给分目的仓口径。
4. **补货建议 → 预报单的自动流转**：等 6.2.1.3 采购计划交付后，可考虑用事件把补货建议转成预报单草稿（本模块已预留 `source` 标记与「DRAFT 可改明细」的生命周期）。
