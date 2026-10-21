# Batch 46 —— 调价单 / 价格历史 / 采购计划 / 备货预报

> 并行车道流水线 · 批次 46 · 2026-10-21
> 集成头 `integration/module-dev` · 4 条车道并行开发，L2 批量验证一次通过

## 本批概览

| 模块 ID | 名称 | 阶段 | 归属服务 | 迁移版本 | 车道 commit |
| --- | --- | --- | --- | --- | --- |
| 5.4.1.1 | 调价单 | D6 | catalog | `V240__price_adjustment_order.sql` | `f1b0c421` |
| 5.5.1.1 | 价格历史 | D6 | catalog | `V241__price_change_history.sql` | `64afd819` |
| 6.2.1.3 | 采购计划 | D6 | trade | `V242__purchase_plan.sql` | `a7c9d862` |
| 10.4.2.2 | 备货预报 | D7 | channel | `V243__stock_forecast.sql` | `8386dad0` |

4 条车道落在 3 个服务、4 个互不相同的 L3 锚点（`/pricing/adjust`、`/pricing/history`、
`/purchase/demand/plans`、`/logistics/stock-forecast`），彼此无依赖、无共享迁移版本号。

---

## 模块 5.4.1.1 调价单

**这个功能做什么**：运营在「调价」工作区里对某个 SKU 在某个店铺的一次改价建一张单，
走「草稿 → 待审 → 已批 → 已执行」的审批闭环，执行后产出「待回写平台」的事实，
由下游同步模块去落平台价格。单子建好后可以看到改价前后的价格与毛利对比。

**技术落地**：

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V240__price_adjustment_order.sql`
  — **三张新表**：`price_adjustment_order`（单头：状态机 + 乐观锁 `version` + 审批镜像 + 利润口径）、
  `price_adjustment_order_item`（一条单子的一条价格变更：SKU × 店铺，**记录变更前后值**）、
  `price_adjustment_writeback`（生效后**待回写**平台的事实，给下游 5.4.2.1）。
  三表均 `tenant_id uuid NOT NULL` + `ENABLE/FORCE ROW LEVEL SECURITY isolation` +
  `data_scope`（RESTRICTIVE，跟随 SKU 可见性）+ `GRANT SELECT/INSERT/UPDATE`
  （**刻意不授 DELETE** —— 已发生过的调价不许删，要再调就再发一张单）。
  **零个 FOREIGN KEY**（引用校验一律用 BEFORE INSERT/UPDATE 触发器，避开 batch-37 的 TRUNCATE 依赖图坑）、
  **零个 ALTER 既有表**。金额 `numeric(19,4)`、数量 `numeric(19,6)`、费率/毛利率 `numeric(9,6)`、
  `timestamptz` + `OffsetDateTime` + `?::timestamptz`。`UNIQUE(tenant_id, order_id)` 钉死
  「一张单 = 一个 SKU 在一个店铺的一次变更」。
- 端点：**11 个** `/catalog/price-adjustments` — `GET /capabilities`（能力与边界声明）、
  `GET /current-price`（当前价来源解析）、`GET /`（台账：真分页 + 真筛选 + 真排序）、
  `POST /`（建单）、`GET /{id}`（详情）、`POST /{id}/submit`（提交审批）、
  `POST /{id}/execute`（执行，产出待回写事实）、`POST /{id}/reject`（驳回）、
  `POST /{id}/approval/refresh`（拉取审批结论）、`GET /writebacks`（待回写清单）、
  `POST /writebacks/{id}/ack`（下游回写完成确认）。
- 页面：`/pricing/adjust` — 能力声明区块、建单表单、查当前价、台账（真筛选真分页真排序）、
  单据详情（变更前后值 + 改价前后利润 + 审批镜像）、状态流转按钮（提交 / 执行 / 刷新审批 / 驳回）；
  **加载态 / 空态 / 错误态三态齐全**。
- 测试：**38 例** — `PriceAdjustmentIntegrationTest` 24 例、
  `PriceAdjustmentTenancyIntegrationTest` 4 例、`PriceAdjustmentRulesTest` 10 例、
  `price-adjustment-model.test.ts` 7 例、`price-adjustment-allowlist.test.ts` 5 例。

**关键设计决策**：

1. **审批不自造，调用 identity 的 `POST /identity/approval-rules/evaluate`**（单据类型
   `PRICE_CHANGE`，V83 出厂注册的 12 种单据类型之一）。命中规则 ⇒ 提交审批实例
   （`sourceReferenceId` = 调价单 id ⇒ **天然幂等**，重复提交不产生第二条待办）并停在**待审**；
   `decision=EXEMPT` 或本租户未配规则 ⇒ **免审直通**进已批。
   **本模块没有本地批准端点** —— 那会把审批流本地化，identity 那边什么都不知道还留一条没人管的待办。
   **审批服务不可达 ⇒ 停在待审并把失败原因写进 `approval_sync_error`，绝不自动跳到已批**。
2. **审批状态同步是拉取式**（本仓还没有事件总线）：`POST /{id}/approval/refresh` 与读详情时顺带刷新，
   经 `GET /identity/approval-records/{id}` 取回结论。
3. **当前价取不到 ⇒ 整单拒绝（400）**，绝不用 0 顶 —— 0 会被当成「免费」；
   **币种不同 ⇒ 整单拒绝，绝不按 1:1 折算**；客户填的旧价与服务端解析到的当前价对不上 ⇒ **409**（表单已过期）。
4. **利润一律来自 5.3.1.1 的 `ProfitCalculatorService.calculate`**，本模块**零个测算算法**。
   平台费率与物流单价仍是手工假设（5.2.3.1 / 5.2.3.2 未交付）。
5. **与 5.4.1.2 批量调价的边界**：V237 的 `batch_price_adjustment*` 两张表写入所有者是 5.4.1.2，
   本模块**一列都没改**也没往里写 —— 调价单另起自己的表。
6. **下游 5.4.2.1 平台价格同步未交付**：本模块执行时只**产出待回写事实**
   （幂等键 `price-adjustment:{调价单 id}`），**绝不自己调平台 API**。

---

## 模块 5.5.1.1 价格历史

**这个功能做什么**：把每一次价格变更记成一条不可变的事实，形成单个 listing 的价格时间轴，
并提供「降价到底有没有用」的价格 / 利润 / 销量对齐视图，以及租户自己的变更原因字典。

**技术落地**：

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V241__price_change_history.sql`
  — **两张新表、零 ALTER 任何既有表、零外键**：
  `price_change_history`（价格变更事实台账：一行 = 一个 SKU 在一个店铺上的一次价格变更，
  含旧价 / 新价 / 价差 / 幅度 / 方向 / 变更来源 / 原因快照 / 操作人 / 投递方模块 / 幂等键 /
  生效时刻 / 入账时刻 + **改价前后的利润快照**；**只追加**，GRANT 只有 `SELECT/INSERT` ——
  改价要改就再登记一条，不在原地改写历史；`UNIQUE(tenant_id,event_key)` 钉死幂等）、
  `price_change_reason`（租户级「变更原因」字典：编码 / 名称 / 粗分类 / 启用 / 排序；
  历史事实存的是**当时的快照**（`reason_code` + `reason_text`，不建外键）⇒ 改字典不改写历史）。
  两表 `tenant_id uuid NOT NULL` + `ENABLE/FORCE ROW LEVEL SECURITY isolation`，
  变更事实额外 `data_scope` 跟随 sku，引用校验用触发器不用外键。
- 端点：**7 个** `/catalog/price-history` — `GET /capabilities`（价格 / 销量 / 利润三个维度齐没齐）、
  `GET /changes`（时间轴集合读，真筛选 + 真分页 + 真排序）、`POST /changes`（人工登记一次变更）、
  `GET /changes/{id}`（单条详情 + 前后相邻的变更）、`GET /timeline`（单个 listing 的时间轴：
  链条接不接得上 + 累计价差 + 待执行建议只读引用）、`GET /comparison`（「降价到底有没有用」对齐视图）、
  `GET /sales`（一段区间的销量事实）、`GET|POST /reasons`（原因字典）。
- 页面：`/pricing/history` — 能力声明区块、价格变更时间轴（SKU / 店铺 / 时间范围 / 变更来源 /
  原因 / 方向 真筛选真分页真排序）、单条变更详情、单个 listing 的时间轴（**链条断链时明说断在哪**）、
  对比视图、人工登记一次变更、租户自己的原因字典；加载态 / 空态 / 错误态三态齐全。
- 测试：**22 例后端** — `PriceHistoryIntegrationTest` 16 例、
  `PriceHistoryTenancyIntegrationTest` 6 例；前端 `price-history-model.test.ts` 31 例 +
  `price-history-allowlist.test.ts`。

**关键设计决策**：

1. **listing 的口径 = (SKU × 店铺)**。本仓 catalog 没有 listing 本地投影（listing 事实属 channel 的
   4.1.1.1），AGENTS.md 禁止跨服务查表 ⇒ **不建 listing 影子表**，按 `sku_id + platform_code + site_code`
   记，与 5.4.1.2 / 5.5.2.1 逐字同口径。
2. **观测 ≠ 变更**：5.5.2.1 的 `price_anomaly_observation` 是「某时刻看到的价」，**只读**
   （绝不写、绝不 ALTER）；变更是「价被改了」，二者不混。
3. **写端口 `PriceChangeHistoryPort`**（`PriceHistoryService` 是唯一实现，本表唯一写入所有者）：
   上游**不直接写表**，经端口投递 ⇒ 一次改价只留一条变更事实；幂等键 = 投递方给的 `eventKey`。
4. **5.4.1.2 批量调价已真的接上**：执行成功后逐条投递（只投 `WILL_ADJUST` 的行）；
   **利润快照原样取 5.4.1.2 自己算好的那一行**（同一个 5.3.1.1 引擎，不重算 —— 重算会让同一批
   listing 多翻一倍 warehouse 成本，且 warehouse 抖一下会把已算准的利润降级成「没测准」）；
   **投递失败被吞掉并留痕，绝不把已经改成功的价回滚**。
5. **5.4.1.1 调价单同批并行开发，本仓此刻不存在 ⇒ 没有假装它已接入**；
   接入点写在 `PriceChangeHistoryPort` 的 javadoc 与 `docs/sessions/2026-10/2026-10-21-price-history.md`。
6. **销量维度当前是「未接线」状态，不是已完成**：销量事实属 trade 服务，走跨服务只读端口
   `SalesFactPort`（`UnwiredSalesFactPort` 降级 ⇒ 显式抛错翻成 **503**，绝不返回 0 或空数组冒充销量）；
   端口约定的 `GET /trade/sales-facts/sku-summary` **当前未交付** ⇒ 对比视图销量三列是 `null`
   并标 `salesAvailable=false`。写成 0 会让每次降价都显示成「降价后一件没卖出去」，那是凭空造出来的结论。
7. **变更事实不可变**：没有「撤销一次改价」的端点 —— 要撤销就再登记一条反向变更。

---

## 模块 6.2.1.3 采购计划

**这个功能做什么**：把补货建议汇总成采购计划（按日常补货或大促备货分别算），
可对计划行做调整与锁库（真去 warehouse 登记预占），并导出 CSV 给采购员；
租户还能维护自己的大促档期日历与锁库策略。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V242__purchase_plan.sql`
  — **六张新表、零 ALTER 既有表、零跨库 FK / 视图**：`purchase_plan_campaign`（**大促日历，本模块自带** ——
  实查后端无任何 Campaign / Promo 类，大促日历在本仓没有任何已交付来源 ⇒ 由本模块自带、
  租户自己维护档期与预期销量 / 备货系数，是真实业务输入而不是 mock）、
  `purchase_plan`（计划头：类型 / 周期 / 目标仓 / 法人 / 状态）、
  `purchase_plan_line`（计划行：SKU、数量、目标仓、期望到货日、优先级）、
  `purchase_plan_adjustment`（**行调整留痕，append-only**）、
  `purchase_plan_lock_log`（**锁定请求留痕，append-only**）、
  `purchase_plan_lock_policy`（**锁库策略** —— 蓝图输入句点名的第四项输入，按日常 / 大促各一条、版本化收口）。
  六表均 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE` ROW LEVEL SECURITY + `POLICY isolation`
  （`tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid`）+ `GRANT trade_app / starcon_app`；
  **锁定 / 调整两张留痕表收回 UPDATE/DELETE**，计划与计划行**不提供 DELETE**。
- 端点：**19 个** `/trade/purchase-plans` — `GET /` + `POST /`（计划清单：真分页 + 真筛选 + 真排序）、
  `GET /{id}`（头 + 计划行）、`POST /{id}/confirm`、`POST /{id}/aggregate`（从补货建议汇总）、
  `GET /{id}/lines`、`GET /{id}/lines/{lineId}`（行 + 调整留痕 + 锁定留痕）、
  `POST /{id}/lines/{lineId}/adjust`、`POST /{id}/lines/{lineId}/lock`、
  `GET /{id}/export`（**真导出 CSV**，含计划类型与大促档期列）、`GET /summary`（**两段分开**）、
  `GET /capabilities`（含「锁库端口是否接线」）、`GET /options`（目标仓 / 法人 / 大促下拉）、
  `GET|POST /campaigns` + `PUT|DELETE /campaigns/{id}`（大促日历增删改查 + 真分页，被计划引用时删不掉）、
  `GET|POST /lock-policies`。
- 页面：`/purchase/demand/plans` — 新建计划 / 从补货建议汇总 / 行调整 / 锁定 / 导出 CSV /
  大促档期维护 / 两段汇总；加载 / 空 / 错误三态齐全。
- 测试：**25 例** — `PurchasePlanIntegrationTest` 20 例、
  `PurchasePlanTenancyIntegrationTest` 5 例；前端 `purchase-plan-model.test.ts` +
  `purchase-plan-allowlist.test.ts`。

**关键设计决策**：

1. **「大促备货与日常补货要分开」落成结构而不是标签**：`plan_type ∈ ('ROUTINE','CAMPAIGN')` 是列，
   DB 层 `CHECK` 钉死「CAMPAIGN 必须带 `campaign_id`、ROUTINE 必须不带」；
   **备货量分开算**（大促 = 建议量 × 销量系数 × 备货系数；日常 = 建议量本身，算式原文写进 `quantity_basis`）；
   **期望到货日分开算**（大促 = 大促开始日 − 档期登记的提前天数；日常 = 6.2.1.2 的预计到货日）；
   **汇总视图分开看**（`GET /summary` 返回日常段与大促段**两个独立块**，刻意不给两者相加的总数量 ——
   相加后的总数会让日常补货失焦）。
2. **锁定走 warehouse 的授权写端口**：预占事实的写入所有者是 warehouse（7.2.1.1 `inventory_reservation`，V102），
   AGENTS.md 禁跨服务 SQL / FK / 视图 ⇒ `PurchasePlanLockPort`（`source_system='TRADE'` +
   `source_type='OTHER_HOLD'`，受 warehouse V102 受控词表约束）经 `POST /warehouse/inventory/reservations`
   真登记预占；**未接线时走 `UnwiredPurchasePlanLockPort` 显式报「未接线」（`WAREHOUSE_URL` 为空即判定），
   绝不降级成「假装锁住了」**。本库只记「已提交请求 / 已确认 / 失败」这些属于采购计划自己的事实，**不建影子表**。
3. **锁定失败必须留痕**：整条调用链跑在一个事务里，抛异常会把「已提交请求」与失败原因一起回滚
   ⇒ 服务层**先记失败再返回**，由控制器在事务提交后翻成对应 HTTP 状态码。
4. **目标仓与法人经 `PurchasePlanWarehousePort` 读 warehouse 的授权只读接口**，**不建仓库 / 法人影子表**。
5. **补货建议只读 6.2.1.2**（那张表的写入所有权是 6.2.1.2）：汇总时**只有 `advice='REPLENISH'` 且未被忽略的
   建议**才变成计划行（「答不出来」与「无需补货」不能变成要下单的量）；上游已调整过的建议取**处置后的量**作基数；
   **重新汇总不覆盖已调整的行**（跳过并计数）。
6. **周期边界由服务端裁决**（月度 = 某月 1 号到月末、季度 = 季首月 1 号到季末月末），对不上就 400，不悄悄改写。

---

## 模块 10.4.2.2 备货预报

**这个功能做什么**：平台仓备货有严格规格（箱型、装箱数、重量、贴标、托盘、混装限制）。
运营在「备货预报」工作区维护各目的仓的规格版本，填预报单 → 过规格闸 → 提交 → 生成货件创建任务，
仓端接收或拒收都要登记回执（拒收也是真实业务结果，必须留痕）。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V243__stock_forecast.sql`
  — **纯新建 6 张表、零 ALTER 既有表**（跨车道 DDL 回归面 = 0）：
  `warehouse_forecast_spec`（🔴 **蓝图加粗句「平台仓备货有严格规格」的物理落点**：箱型
  `allowed_box_types` / `max_units_per_carton` / `max_weight_per_carton_kg` / `max_skus_per_carton`、
  贴标 `requires_fnsku_label` / `requires_carton_label` / `allowed_label_formats`、
  托盘 `pallet_required` / `max_cartons_per_pallet` / `max_pallet_weight_kg`、
  混装 `mixed_loading_allowed` **四组限制各有自己的列**，按目的仓版本化，同一目的仓同时只有一版 ACTIVE）、
  `warehouse_forecast`（预报单头；**`spec_id` 冻结建单时的规格版本** —— 改规格不影响已建单）、
  `warehouse_forecast_line`（明细：SKU 与数量 + 箱型/箱数/托盘/贴标/箱唛/单重**声明**）、
  `warehouse_shipment_task`（平台货件创建任务：`PENDING→SUBMITTED→SUCCEEDED/FAILED`，
  `FAILED→PENDING` 可重试且 `attempts` 累加）、
  `warehouse_shipment_report`（🔴 **货件任务的真实输入**：本仓库无真实平台账号与网络出口
  ⇒ **不写假 HTTP 客户端打真实域名**，照抄 10.4.2.1 `warehouse_inbound_stock_line` 的形状，
  用租户/通道登记的报送表承接外部边界，**平台货件编号是被登记进来的而不是本服务生成的**，
  没登记时任务 FAILED 且可重试，绝不凭空造编号）、
  `warehouse_forecast_receipt`（仓端回执：**接收与拒收两条都落库并可查**）。
  `external_stock_observation`（外部仓库存观测表）**只读不写不 ALTER**。
- 端点：**19 个** `/channel/stock-forecasts` — `GET /capabilities`、`GET /accounts`、
  `GET|POST /specs` + `POST /specs/{id}/retire`、`GET /{id}`、`PUT /{id}/lines`、
  `GET /{id}/spec-check`、`POST /{id}/validate`、`POST /{id}/submit`、`POST /{id}/cancel`、
  `GET /{id}/availability`、`GET /{id}/shipment-task` + `POST /{id}/shipment-task/dispatch` +
  `POST /{id}/shipment-task/retry`、`GET|POST /{id}/shipment-reports`、`GET|POST /{id}/receipts`。
  **刻意没有 DELETE 段**：预报单靠状态机收口、回执是凭据。
- 页面：`/logistics/stock-forecast` — 预报单列表（真分页 + 真筛选 + 真排序）/ 规格维护页 /
  规格违规**逐条**显示 / 货件任务推进与重试 / 仓端接收与拒收回执登记 /
  备货可用性（**读不到就显示「不可用」**）/ 能力与边界页逐条标 `available=false` + ownerModule + impact。
- 测试：**22 例后端** — `StockForecastIntegrationTest` 13 例、
  `StockForecastTenancyIntegrationTest` 3 例、`StockForecastAvailabilityPortTest` 6 例
  （对本地 stub HTTP server 跑，不需要数据库）；前端 `stock-forecast-model.test.ts` +
  `stock-forecast-api-proxy.test.ts`。

**关键设计决策**：

1. **归属 channel**：蓝图 10 卷卷首「后端归属：channel 拥有物流商连接与轨迹」＋ AGENTS.md
   「外部适配器放在 channel」＋ **同 L3 的 10.4.2.1 海外仓连接已落在 channel（V93）** ——
   「目的仓」就是 10.4.2.1 的 `warehouse_account`，必须同服务同库才不违反「禁止跨服务数据库访问」。
2. **四条不变量在 DB 层钉死（绕过服务层写 SQL 同样被拒）**：
   `guard_forecast_status_transition`（`DRAFT→VALIDATED→SUBMITTED→SHIPMENT_CREATED→ACCEPTED/REJECTED`，
   `DRAFT/VALIDATED/SUBMITTED→CANCELLED`）、`guard_shipment_task_transition`、
   `guard_forecast_line_frozen`（**明细只在草稿态可改** —— 离开草稿态明细就是「已提交给平台仓的承诺」）、
   `guard_forecast_receipt_append_only`（触发器 + `GRANT SELECT,INSERT` **刻意不给 UPDATE/DELETE**，
   测试用应用账号与迁移超级账号**各试一次**）。
3. **规格闸真的拦得住**：预报单**必须先过规格闸才能提交**，违规返回 **422 + ProblemDetail 挂
   `violations` 数组**，逐条点名违反哪一条（`BOX_TYPE_NOT_ALLOWED` / `MAX_UNITS_PER_CARTON_EXCEEDED` /
   `MAX_WEIGHT_PER_CARTON_EXCEEDED` / `LABEL_FORMAT_MISSING` / `LABEL_FORMAT_NOT_ALLOWED` /
   `CARTON_LABEL_MISSING` / `PALLET_REQUIRED_MISSING` / `MAX_CARTONS_PER_PALLET_EXCEEDED` /
   `PALLET_WEIGHT_EXCEEDED` / `MIXED_LOADING_FORBIDDEN` / `MAX_SKUS_PER_CARTON_EXCEEDED` /
   `EXPECTED_ARRIVAL_TOO_SOON` / `DUPLICATE_SKU` / `CARTON_COUNT_MISSING` / `UNIT_WEIGHT_MISSING`），
   每条带**期望值与实际值** —— 不是「提交成功然后被仓端拒收」。
4. **适配层复用 `OverseasWarehouseAdapter`**（**不另起第二套适配器接口、不改既有方法签名**）：
   新增 `default ShipmentAck submitForecast(...)`，默认实现抛接口自带的 `WarehouseProviderException`
   表示「该服务商未开通预报提交能力」，属能力协商不是占位符；
   新增实现 `warehouse.platform.PlatformShipmentWarehouseAdapter`（`PLATFORM_FBA`，
   V93 种子里已存在且 `supports_forecast=true`，**不新增服务商目录行**）。
5. **库存与在途走只读 Port**（禁跨服务 SQL / FK / 视图）：`ForecastInventoryQueryPort`
   （读 `GET /warehouse/inventory/sellable/skus`，7.2.2.1 口径原样采用**不自己重算**）与
   `ForecastInTransitQueryPort`（读 `GET /warehouse/inventory/in-transit`，只取 `transitKind=FIRST_MILE` 头程）。
   两者**上游不可达一律 503 明确报「读不到」，绝不用 0 冒充**（0 会被读成「没有库存需求」）；
   上游正常但没有该 SKU 的行时返回 `null` + `known=false`（**无记录 ≠ 可售量为 0**）。
6. **复用 identity 已有的 `channel.operation.*` 权限码，不改 identity 服务，跨车道回归面 = 0**。

---

## 本批共性观察

1. **四条迁移全部是「纯新建表 + 零 ALTER 既有表」** —— 静态预检（`ADD COLUMN` 无 `DEFAULT` /
   `SET NOT NULL` / `ADD CONSTRAINT CHECK`）**零命中**，跨车道 DDL 回归面为 0，
   L2 一次全量验证未出现任何跨车道互相破坏。
2. **四张台账之外的事实表全部带 `tenant_id NOT NULL` + `ENABLE`/`FORCE ROW LEVEL SECURITY`**，
   并配了多租户三断言集成测试（两租户互不可见 / 伪造 `X-Tenant-Id` 无效 / 连接池压到 1 后不串租户）。
   10.4.2.2 的直查断言**用 `channel_app` 应用账号而非超级用户** —— 用超级用户会绕过 RLS，断言本身失效。
3. **「取不到」与「确实是 0」被系统性地区分**：取不到当前价 ⇒ 整单拒绝；上游读不到 ⇒ 503；
   无记录 ⇒ `null` + `known=false`；销量未接线 ⇒ 显式 unavailable。全部不用 0 顶。
4. **api-proxy 白名单一律走「纯增量新增独立元素」**，不往既有超长 alternation 里并
   （并进去必然 git 冲突），并用反向断言锁住「刻意不放行」的形状与「兄弟车道已交付形状未被挤掉」。
5. **api-proxy 是本仓已知的静默失效点**（改错只表现为页面 404，typecheck / lint / vitest 全绿），
   因此四条车道各自都跑了**全量 allowlist 回归**并新增 `*-allowlist.test.ts` 双向断言。

## 已知缺口（如实记录，未隐瞒）

- **5.4.1.1**：5.4.2.1 平台价格同步未交付，本模块只产出待回写事实；平台费率与物流单价仍是手工假设
  （5.2.3.1 / 5.2.3.2 未交付）；调价单的撤销 / 重开与 CSV 导出未做（已执行与已驳回是终态）。
- **5.5.1.1**：销量事实来源未接线（属 trade 服务，本模块只提供端口与显式 unavailable）；
  5.4.1.1 的接入点留待下一批；价目表（5.1）与平台价格自动回流（4.3.2.2）未交付。
- **6.2.1.3**：大促日历为本模块自带（仓内无其他已交付来源）；锁库端口在 `WAREHOUSE_URL` 未配时显式报未接线。
- **10.4.2.2**：**平台货件接口直连未接通**（无真实平台账号与网络出口，改用租户 / 通道登记的报送行承接，
  `capabilities` 逐项 `available=false` + ownerModule + 影响）；6.2.1.2 补货建议**自动带入**未接通
  （只登记来源标记）；按目的仓的在途量口径不存在（在途腿目的仓编码与仓端账户编码不是同一套，只给 SKU 汇总）。

## 文件清单

| 类别 | 文件 |
| --- | --- |
| 迁移 | `backend/services/catalog/src/main/resources/db/migration/V240__price_adjustment_order.sql`<br>`backend/services/catalog/src/main/resources/db/migration/V241__price_change_history.sql`<br>`backend/services/trade/src/main/resources/db/migration/V242__purchase_plan.sql`<br>`backend/services/channel/src/main/resources/db/migration/V243__stock_forecast.sql` |
| 端点 | `catalog/PriceAdjustmentController.java` · `catalog/PriceHistoryController.java` · `trade/PurchasePlanController.java` · `channel/StockForecastController.java` |
| 页面 | `apps/web/src/app/pricing/adjust/page.tsx` · `pricing/history/page.tsx` · `purchase/demand/plans/page.tsx` · `logistics/stock-forecast/page.tsx` |
| 组件 | `components/pricing/price-adjustment-{model.ts,section.tsx}` · `components/pricing/price-history-{model.ts,workspace.tsx}` · `components/purchase/purchase-plan-{model.ts,workspace.tsx}` · `components/logistics/stock-forecast-{model.ts,tsx}` |
| 白名单 | `apps/web/src/lib/api-proxy.ts`（**纯增量 +214 行，未改既有正则**） |
| 契约 | `packages/api-contracts`（由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成，只读） |
| 测试 | 11 个后端测试类 + 8 个前端测试文件（见各模块节） |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（4 行状态 → 已交付） |

## L2 批量验证记录（2026-10-21 07:02 → 07:36）

在集成头 `integration/module-dev`（合并后 HEAD `07490637`）上跑**本批次唯一一次全量验证**，
逐阶段退出码全部为 0：

| 阶段 | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 后端全量 | `cd backend && ./mvnw verify` | `0` | **BUILD SUCCESS** · 32:24 min · 8/8 模块 SUCCESS<br>identity 270 · catalog 1945 · channel 1156 · warehouse 1535 · trade 1663 · crm 70 —— **共 6639 例，Failures 0 / Errors 0 / Skipped 0** |
| 类型 | `pnpm typecheck` | `0` | turbo 3/3 成功（ui / config-tailwind 命中缓存，web 实际执行 `tsc --noEmit`） |
| Lint | `pnpm lint` | `0` | 3/3 成功；**仅既有 warning**（`variant-combination-workspace` / `pending-shipment-workspace` / `goods-receipt` / `putaway-task-workspace` 的 `react-hooks/exhaustive-deps`），**本批 4 个模块无新增告警** |
| 前端全量 | `pnpm test` | `0` | vitest v4.1.11 —— **364 个测试文件 / 5627 例全过** |
| Next 产物 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | `0` | Compiled successfully · 路由清单正常产出 |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | `0` | 465 行、无重复模块 ID |

**本批 L2 未出现任何跨车道互相破坏**。四条迁移全部是「纯新建表 + 零 ALTER 既有表」，
静态预检（`ADD COLUMN` 无 `DEFAULT` / `SET NOT NULL` / `ADD CONSTRAINT CHECK`）零命中，
跨车道 DDL 回归面为 0，因此一次全量验证即通过，无需二分定位。

**机器状态观测（验证期间）**：

- `pmset -g therm`：`No thermal warning level has been recorded` / `No performance warning level has been recorded`
  —— **无降频项**，温度闸全程未命中。
- load 1min 在 1.34 – 4.50 之间（10 核），远低于阈值。
- Testcontainers PG 容器峰值 **44 个**（channel 阶段的 surefire 并行 fork 会瞬时拉高），
  单容器仅约 50 MiB，Docker 内存峰值 4534 MiB / 11.67 GiB（39%）。
  `testcontainers-ryuk` 容器全程在跑，验证结束后带标签的容器数已回落至 **0**，孤儿容器 **0**。

## 后续待办

1. **5.4.1.1 ↔ 5.5.1.1 的接入**：调价单执行成功后经 `PriceChangeHistoryPort` 投递变更事实
   （幂等键 + 利润快照直接取调价单那一行，不重算）。接线契约见
   `PriceChangeHistoryPort` javadoc 与 `docs/sessions/2026-10/2026-10-21-price-history.md`。
2. **5.5.1.1 的销量维度接线**：`SalesFactPort` 对应的 `GET /trade/sales-facts/sku-summary` 尚未交付，
   交付后对比视图的销量三列可从 `null` 变为实数。
3. **10.4.2.2 的平台货件直连**：接入真实平台账号与网络出口后，把 `warehouse_shipment_report`
   的登记式边界换成真实 HTTP 调用，并把 `capabilities` 的 `available=false` 逐条改为 true。
4. **6.2.1.3 的大促日历**若后续出现别的已交付来源，需重新确认唯一写入所有者，避免双写。
