# batch-43：六车道并入集成头 —— 5.2.3.2 / 5.3.1.2 / 5.3.2.1 / 6.1.2.1 / 6.2.1.1 / 6.3.2.2

> 本轮（2026-10-21 04:00 tick）：回收并入 **6 条车道**，静态预检零命中，
> **L2 批量验证六阶段全绿**，随后派发 4 条新车道（5.3.2.2 / 5.4.1.2 / 6.2.1.2 / 6.1.2.3）。
> 本文件是**批次级说明**；每个模块的完整设计推理在各自车道随附的会话日志里（见文末「关联」）。

## 本轮背景

上一轮（batch-42）是**零派发只读轮**：03:12 触发时调度闸被 02:15 那个被截断的
`main → module-dev` 同步任务泄漏占用，按协议零改动退出。
本轮 04:02:33 取得闸后，发现**已有 6 条车道落地但一直无人回收**（landed 时间在
2026-10-21 02:10–03:55 之间，全部 state=ready）—— 也就是说 batch-40/41/42 连续三轮
的产出被积压在本轮一次性回收。

这 6 条车道覆盖 catalog 3 条 + trade 3 条，分属两个不同的 L3 组，
互相之间无依赖关系，因此一次性并入后由**一次** L2 批量验证覆盖（组批协议的核心收益）。

| # | 模块 | 名称 | 服务 | 迁移 | L3 锚点 |
| --- | --- | --- | --- | --- | --- |
| 1 | 5.2.3.2 | 物流与头程费用 | catalog | `V232__logistics_freight_rule.sql` | 5.2.3 |
| 2 | 5.3.1.2 | 批量测算 | catalog | `V230__batch_calculation.sql` | 5.3.1 |
| 3 | 5.3.2.1 | 定价模板 | catalog | `V231__pricing_template.sql` | 5.3.2 |
| 4 | 6.1.2.1 | 档口与货源池 | trade | `V233__supplier_source_pool.sql` | 6.1.2 |
| 5 | 6.2.1.1 | 缺货与断货风险 | trade | `V234__stockout_risk.sql` | 6.2.1 |
| 6 | 6.3.2.2 | 到货与逾期 | trade | `V235__purchase_arrival_tracking.sql` | 6.3.2 |

---

## 模块 5.2.3.2 物流与头程费用

**这个功能做什么**：把「这个渠道怎么收费」变成可维护的规则表 —— 按重量/体积/件数计费、
燃油与旺季与偏远附加费、头程费用结构（成本项 + 各自币种与计价基数），
并把「规则算出来的运费」与「实际发生的运费」并排呈现，差异实时算出给人看。

**技术落地**：

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V232__logistics_freight_rule.sql` — 六张新表
  （`logistics_freight_rule` / `logistics_freight_rule_tier` / `logistics_freight_surcharge` /
  `logistics_head_freight_structure` / `logistics_head_freight_component` / `logistics_freight_actual`），
  全部 `tenant_id uuid NOT NULL` + ENABLE/FORCE RLS + GRANT；
  **零 FOREIGN KEY、零跨库视图、零跨服务 SQL** —— 渠道/承运商/运输方式只存逻辑引用码 + 名称快照
- 端点：`/catalog/logistics-freight/**` 共 **16 条路径 / 19 个操作** —
  `capabilities` / `rules`（集合 + 详情 + `disable` + `supersede`）/ `surcharges`（集合 + `disable`）/
  `head-freight/structures`（集合 + 详情 + `disable`）/ `estimate` / `head-freight/estimate` /
  `actuals`（集合 + `{actualId}/void`）/ `variance`（差异）
- 页面：`/pricing/fees` 的物流与头程费用分区 —— `apps/web/src/components/pricing/logistics-freight-workspace.tsx`
  + `logistics-freight-model.ts`（**规则区**与**实际区**两块独立区域，各带来源标注）
- 测试：`LogisticsFreightRuleIntegrationTest`、`LogisticsFreightRulesTest`（catalog）

**关键设计决策**：

- **规则与实际是两套独立数据，差异现算不落库。** 渠道计费规则是**事前测算**、实际运费是**事后核算**，
  两者在页面上是两块独立区域。运费差异由服务层现算，**不落库** ——
  落库就会多出第三份可能与前两份不一致的副本，反而制造第三个真相。
- **版本链用 `version_no / supersedes_id / superseded_by_id` 三字段表达**，
  不用「改了就覆盖」的历史表 —— 规则改价改口径必须能回溯「当时用的是哪一版」。
- **阶梯有两种数学形态**：`FLAT_PER_TIER`（整档一口价）与 `FIRST_PLUS_ADDITIONAL`（首段加续段），
  两者不能压成一个「单价 × 数量」。
- 附加费三种计价基数（`PERCENT_OF_BASE_FREIGHT` / `FIXED_PER_ORDER` / `FIXED_PER_UNIT`）分开建模，
  避免「按单」与「按件」被算错。
- **零外键**：渠道/承运商属于别的服务，只存逻辑 ID + 名称快照，
  避免跨库 FK 把两个服务的迁移顺序绑死（这是本批 6 条迁移的共同纪律）。

---

## 模块 5.3.1.2 批量测算

**这个功能做什么**：选一批 SKU，一键算出每个品的完整利润表，自动标出亏损品与低毛利品，
支持按毛利率/售价筛选与导出 —— 清库存、选品不用再人工一个个测算。

**技术落地**：

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V230__batch_calculation.sql` —
  `batch_calculation_run`（批次：范围 + 参数 + 口径）/ `batch_calculation_result`（逐 SKU 利润表 + 异常标记）；
  `tenant_id NOT NULL` + ENABLE/FORCE RLS isolation + 结果表 `data_scope` 跟随 sku；
  **零外键、用触发器做引用校验**（避开 batch-37 的 TRUNCATE 坑）
- 端点：`/catalog/batch-calculations/**` 共 6 条路径 / 7 个操作 —
  `capabilities` / `sites` / `runs`（GET 列表 + POST 新建批次）/ `runs/{id}` / `runs/{id}/results` / `runs/{id}/export`
- 页面：`/pricing/calculator` — `apps/web/src/components/pricing/batch-calculation-section.tsx`
  + `batch-calculation-model.ts`
- 测试：`BatchCalculationIntegrationTest`、`BatchCalculationTenancyIntegrationTest`、
  `BatchCalculationRulesTest` + `BatchCalcFixture`（catalog）

**关键设计决策**：

- **复用 5.3.1.1 利润测算器的引擎，而不是另写一套批量算术。**
  批量测算 = 把 `ProfitCalculatorService.calculate` 跑 N 次（每次换一个 SKU 与它的售价）；
  金额 4 位小数、费率 6 位小数、HALF_UP、成本口径与决策就绪闸**逐字沿用**。
  理由：下游 **5.4.1.2 批量调价会程序化消费本模块的输出并直接改价** ——
  两套口径必然改错价。集成测试直接断言「批量结果与 5.3.1.1 单次测算逐字一致」。
- **售价的唯一真实来源是 5.5.2.1 的价格观测台账**（`price_anomaly_observation`，V222）。
  5.1 价目表未交付 ⇒ catalog 库里没有任何「我方价目表」，批量测算不能让人手填 200 个价。
  口径：取该 SKU 在该店铺**最新一条**观测，优先 `internal_price`、退到 `platform_price`，
  **来源与观测时刻逐行落库** —— 台账是只追加的，「最新一条」是一个**时间点**而不是「当前价」，
  拿三个月前的价去清库存，运营必须看得见这一点。
- **取不到售价就整行跳过，绝不拿 0 顶。** 售价缺失会让利润表出现一个假的「高毛利」，
  比缺一行更危险。

---

## 模块 5.3.2.1 定价模板

**这个功能做什么**：把「怎么算这个价」沉淀成可复用的模板 —— 成本加成、固定毛利、
竞品跟随（人工登记）、按站点差异化、按库存周转调整，新品与老品用不同模板；
一键把模板应用到商品上生成**建议价**，并留下应用留痕。

**技术落地**：

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V231__pricing_template.sql` — 四张新表
  （`pricing_template` 模板 / `pricing_template_adjustment` 调整项 / `pricing_template_scope` 适用对象 /
  `pricing_template_application` 应用留痕），`tenant_id NOT NULL` + ENABLE/FORCE RLS isolation +
  GRANT `catalog_app` / `starcon_app`；**零外键**，引用校验一律用触发器
  （`guard_pricing_template_adjustment` / `_scope` / `_application`）
- 端点：`/catalog/pricing-templates/**` 共 8 条路径 / 9 个操作 —
  `capabilities` / `directory` / `sites` / `templates`（GET 列表 + POST 新建）/
  `templates/{templateId}`（GET 详情 + PUT 修改（乐观锁）+ DELETE）/
  `templates/{templateId}/apply`（一键应用生成建议价）/ `applications`（应用留痕）
- 页面：`/pricing/templates` — `apps/web/src/app/pricing/templates/page.tsx`
  + `apps/web/src/components/pricing/pricing-template-workspace.tsx` + `pricing-template-model.ts`
- 测试：`PricingTemplateIntegrationTest`、`PricingTemplateTenancyIntegrationTest`、
  `PricingTemplateRulesTest`（catalog）

**关键设计决策**：

- **建议价必须来自 5.3.1.1 的真实测算，本模块不复刻任何扣减公式。**
  蓝图关系句写的是「上游 = 5.3.1.1」⇒ 模板只存「怎么算」，算的时候调
  `ProfitCalculatorService`：固定毛利走它的 TARGET_MARGIN 求解；
  成本加成的加成基数取它瀑布图里的**单位成本合计**（平台费是售价的比例，**不进加成基数**）；
  竞品跟随以人工登记的竞品价为起点。最后统一用建议价再跑一次 PRICE 方向拿**真实**毛利率。
- **决策就绪闸落在服务层，不在页面上。** 5.3.1.1 在扣减不全时把毛利率/目标毛利价置 null；
  本模块照抄并把它变成 **409**，缺口清单原样带回（点名归谁管、缺了会怎样），
  **绝不给一个虚高的建议价** —— 下游 5.4.1.1 调价单会拿它直接改价，页面上一句提示拦不住一个自动改价的规则。
- **两类调整项的数学形态不同，且都是有意选的**：
  按站点差异化 → **加在基准率上**（三个基准方法的基准率含义不同，只有加法能让同一套站点规则在三种基准下保持同一语义）；
  按库存周转调整 → **乘在建议价上**（滞销处理的是「这个价太高卖不动」，
  加在基准率上会让固定毛利模板在折扣很小时反而涨价）。
- **「新品与老品用不同模板」的判定依据用真实事实** —— 用 3.1.1.3 的 SPU 生命周期状态机（未上架 = 新品）；
  全仓没有「首次上架时间」这个事实，编一个出来就是假数据。阶段不符 ⇒ 409，不静默套用。
- **「竞品跟随（若允许）」的「若允许」在本仓的落点**：本仓**没有**竞品价模块 ⇒
  竞品价只能人工登记（显式标注的手工假设），**不建**任何竞品价表来假装上游已交付；
  没登记竞品价就保存模板 ⇒ 400。

---

## 模块 6.1.2.1 档口与货源池

**这个功能做什么**：把 1688 / 义乌购等货源渠道的店铺档口接进来，抓取并同步在售货源
（标题、起订量、价格区间、规格、图片），支持收藏、比价，并把货源与内部 SKU 关联起来。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V233__supplier_source_pool.sql` —
  **七张新表、零 ALTER 任何既有表**（跨车道 DDL 回归面 = 0）：
  `supplier_source_stall`（档口 = 店铺：平台 + 平台档口 ID + 名称 + URL + 采集方式 +
  **catalog SUPPLIER 角色逻辑 ID + 名称快照**）·
  `supplier_source_collection`（采集批次：一次同步 = 一行，成功/失败 + 条数 +
  **跳过数与跳过原因** + 失败原因）·
  `supplier_source_item`（货源**身份**，一个平台货源一行，**一个价格列都没有**）·
  `supplier_source_snapshot`（**只追加**的采集快照）· 其余为收藏 / SKU 关联等
- 端点：`/trade/sources/**` 共 15 条路径 — `capabilities` / `overview` / `options` /
  `stalls`（集合 + `{stallId}` 详情 + `{stallId}/collections` + `{stallId}/sync`）/
  `items`（集合 + `{itemId}` + `{itemId}/favorite` + `{itemId}/sku-links` + `{itemId}/sku-links/{linkId}`）/
  `sku/{skuId}/sources` / `suppliers` / `collector-endpoint/{platform}`
- 页面：`/supply/sources`（`apps/web/src/app/supply/sources/page.tsx` +
  `apps/web/src/app/supply/sources/[stallId]/page.tsx`）
  + `apps/web/src/components/supply/sources.tsx` + `sources-model.ts`
- 测试：`SupplierSourceIntegrationTest`（trade）

**关键设计决策**：

- 蓝图加粗句「**货源数据是外部快照，会变**」是本模块的立身之本，它决定三条结构决策：
  1. **「当前价」与「快照价」不混成一列。** 价格区间/起订量/标题/采集时刻**只**存在
     `supplier_source_snapshot`（只追加，一列都不可改写）；`supplier_source_item` 是货源**身份**，
     **一个价格列都没有**，只带一个 `current_snapshot_id` 指针。
     表结构上不存在「当前价」这个概念，因此不存在两列打架。
  2. **旧快照永不改写。** 每次同步**新增一版快照**并前移指针，旧版原地保留；
     `guard_supplier_source_snapshot_immutable` 触发器钉死 snapshot 表的 UPDATE 与 DELETE。
     于是「上周三看到的那个价是什么」永远答得出来。
  3. **区间永远是区间。** `price_min` 与 `price_max` 同时存在、同时返回（DB CHECK + 触发器双闸）；
     比价时区间重叠就返回区间，不压成一个「精确价」。
- **上游是外部平台（主动抓取，不是读库）**：统一接口 `SupplierSourceGateway` +
  两个独立 vendor 实现（`Alibaba1688SourceGateway` / `YiwuGouSourceGateway`），
  真实 HTTP 客户端，**默认未接线** —— 采集入口 `/trade/sources/collector-endpoint/{platform}` 走外部回推。
- **跳过的货源要能说清为什么**：采集批次同时记「成功条数」与「跳过条数 + 跳过原因」，
  否则一次同步的结果无法解释。

---

## 模块 6.2.1.1 缺货与断货风险

**这个功能做什么**：按日均销量、可用库存、在途量、备货周期算出每个 SKU 的**预计断货日**，
按紧急度排序输出断货风险清单（SKU、可用量、日均销、可支撑天数、预计断货日、缺口量、涉及店铺）——
 让人在断货之前而不是之后看到风险。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V234__stockout_risk.sql` —
  **五张新表、零 ALTER 既有表、零跨库 FK**：
  `stockout_sales_velocity`（**销售速度登记** —— 日均销量 + 统计窗口 + 生效期区间 + 来源，append-only：
  **统计口径（SKU / 日均销量 / 统计窗口 / 生效起点 / 来源）由触发器冻结**，
  唯一可动的 `effective_to` **只能设定一次**；部分唯一索引 `stockout_sales_velocity_open_uq`
  钉死「同一 SKU 同时只允许一条未收口的登记」⇒ **「当前日均销」永远只有一个答案**）·
  `stockout_risk_snapshot`（**一次计算 = 一份快照**）· `stockout_risk_item` · `stockout_risk_item_shop` ·
  `stockout_risk_task`
- 端点：`/trade/stockout-risk/**` 共 5 条路径 — `capabilities` / `items`（集合 + `{id}` 详情）/
  `snapshots` / `velocities`
- 页面：`/purchase/demand` — `apps/web/src/app/purchase/demand/page.tsx`
  + `apps/web/src/components/purchase/stockout-risk-workspace.tsx` + `stockout-risk-model.ts`
- 测试：`StockoutRiskIntegrationTest`、`StockoutRiskTenancyIntegrationTest`（trade）

**关键设计决策**：

- **蓝图输出句点名七项，一个不能少** —— 七项在代码里有**七个物理落点**
  （SKU / 可用量 / 日均销 / 可支撑天数 / 预计断货日 / 缺口量 / 涉及店铺）。
- **可用量直接取 warehouse 7.1.1.3 的可售量（sellable），本模块不重算** ——
  跨服务只存逻辑 ID + 快照，走授权只读端口（`StockoutInventoryPort` / `StockoutChannelPort`），
  **不跨库查表、不建跨库 FK**。
- **可支撑天数是整数天、向下取整**；预计断货日 = 评估日 + 可支撑天数；
  缺口量 = max(0, 日均销 × 备货周期 − 有效可用量 − 备货周期内可到达在途) ——
  蓝图加粗句「**必须扣除预占和在途**，只看现有库存会严重高估健康度」。
- **「当前日均销」必须唯一**，否则风险清单同一个 SKU 会出现两个互相矛盾的口径 ——
  这由「append-only 登记 + 触发器冻结口径 + `effective_to` 只能设一次 + 部分唯一索引」四道锁共同保证。

---

## 模块 6.3.2.2 到货与逾期

**这个功能做什么**：跟踪采购单的到货状态 —— 已发货（在途）、部分到货、全部到货、逾期未到、取消；
逾期自动预警并给出处置建议（催货 / 改供应商 / 调库存 / 延期售卖），
**部分到货立即更新已收数量并落一笔应付部分确认**。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V235__purchase_arrival_tracking.sql` —
  **五张新表，零 ALTER 任何既有表**（跨车道 DDL 回归面 = 0）：
  `purchase_arrival_tracking`（到货跟踪单：可收/已收/未收三件套 + 承诺到货日快照 + **六态状态机** + 在途快照）·
  `purchase_arrival_receipt`（到货流水，追加式，序号由 DB 排）·
  `purchase_arrival_payable_confirmation`（应付部分确认，**trade 侧自有事实记录**）·
  `purchase_arrival_disposition`（处置建议，依据由事实推导）·
  `purchase_arrival_event`（留痕，追加式，UPDATE/DELETE 被拒）
- 端点：`/trade/purchase-arrivals/**` 共 12 条路径 — `capabilities` / `overdue-board` /
  集合读 + `POST` 建单 / `{id}` 详情 / `{id}/ship` / `{id}/arrivals`（登记到货）/
  `{id}/sync-in-transit` / `{id}/cancel` / `{id}/payable-confirmations/{confirmationId}/confirm` /
  `{id}/payable-confirmations/{confirmationId}/reject` / `{id}/dispositions/{dispositionId}/resolve`
- 页面：`/purchase/arrivals` — `apps/web/src/app/purchase/arrivals/page.tsx`
  + `apps/web/src/components/purchase/purchase-arrival-workspace.tsx` + `purchase-arrival-model.ts`
- 测试：`PurchaseArrivalIntegrationTest`、`PurchaseArrivalTenancyIntegrationTest`（trade）

**关键设计决策**：

- **本模块拥有「到货跟踪」这份事实**，蓝图输入句的三个上游逐一查证过：
  - 8.2.1.1 收货单 / 8.2.1.2 收货差异 —— 已交付，但在 **warehouse 独立库** ⇒ 授权只读端口 + 本地逻辑引用与快照；
  - 10.4.1.2 在途跟踪 —— 已交付，**也在 warehouse 独立库** ⇒ 同上；
  - 6.3.2.1 跟单 / 6.3.1.x 采购单 —— **未交付** ⇒ 逻辑引用，**不建指向不存在的表的 FK**；
  - 12 财务 —— **finance 服务不存在** ⇒ 应付部分确认落成 **trade 侧自有事实记录**。
- **蓝图加粗句落成数据库不变量，不是应用层约定**（本模块最关键的一条）：
  - 守卫①：已收数量**永远**由 DB 从到货流水重算，表级 CHECK 再兜一道（已收 + 未收 = 可收）
    ⇒ 本模块**没有**「直接写已收数量」的入口；
  - 守卫③：插入一条到货流水 ⇒ **同一事务内** DB 更新头的数量与状态、
    **按「单价快照 × 本次到货数量」落一笔应付部分确认**、追加留痕
    ⇒ 「登记了到货却没有应付部分确认」在本模块**没有代码路径**；
  - 守卫④：应付确认的金额/币种/单价/数量全部由 DB 取，调用方传不进来。
- **「已发货但在途」不算逾期是硬规则**，两处同时表达：DB 守卫① 显式拒绝 `IN_TRANSIT → OVERDUE`
  并写明理由（还在路上，判逾期会误伤）；Java 枚举 `canTransitionTo` 的白名单同样不包含它。
- **逾期用数据库时钟判** —— 判定时刻取 DB 的 `now()` 并逐字返回（`evaluatedAt`），
  跨时钟域偏差会把没逾期的单子判成逾期。日历日口径：**承诺日当天不算逾期，次日 00:00 起算**（UTC 日历日）。
  逾期落库是幂等扫描，**刻意不 bump `version`**（逾期是时间推动的系统判定，不是用户编辑）。

---

## 本批共同的设计纪律（跨 6 条车道一致执行）

1. **全部新表、零 ALTER 既有表。** 本批 6 个迁移（V230–V235）创建 **29 张新表**，
   **没有任何一个迁移 ALTER 既有表** —— 跨车道 DDL 回归面 = 0。
   这也意味着本批静态预检（`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / `ADD CONSTRAINT CHECK`）**零命中**。
2. **零跨库 FOREIGN KEY、零跨库视图、零跨服务 SQL。** 跨服务一律走授权只读端口 +
   逻辑引用 + 名称快照（catalog 侧 `CostBasisPort` / `StockoutInventoryPort` / `StockoutChannelPort` 等）。
3. **引用校验用触发器，不用外键。** 这同时避开了 batch-37 那个 TRUNCATE 级联删除的坑。
4. **多租户三断言实跑**（Testcontainers 真实 PostgreSQL 16）：
   两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户 ——
   每个模块都有独立的 `*TenancyIntegrationTest`。
5. **`tenant_id uuid NOT NULL` + ENABLE/FORCE RLS isolation + GRANT** 是每张业务表的出厂配置。

## L2 批量验证结果（本批次唯一一次全量）

| 阶段 | 命令 | 结果 | 实测 |
| --- | --- | --- | --- |
| 1 | `cd backend && ./mvnw verify` | ✅ **BUILD SUCCESS** | 32:45；reactor 7 模块全 SUCCESS（tenancy / identity / catalog / channel / warehouse / trade / crm）；**6366 例，0 失败 0 错误 0 跳过** |
| 2 | `pnpm typecheck` | ✅ | EXIT=0 |
| 3 | `pnpm lint` | ✅ | EXIT=0 |
| 4 | `pnpm test` | ✅ | **349 个测试文件 / 5420 例全绿**（8.26s） |
| 5 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ | EXIT=0，Next production build 通过 |
| 6 | `node tools/module-progress/sync.mjs --check` | ✅ | 「✓ 台账与蓝图一致」 |

**L2 期间的资源观测（供下一轮参考）**：

- 后端 verify 单跑就会把 Testcontainers PG 容器推到 **30–42 个**（channel 一套就有 55 个测试类，
  每个测试类起一个 PG 容器，ryuk 到会话结束才回收）。
  ⇒ **本地配置里的 `PG_CONTAINER_LIMIT=6` 与当前测试规模已经脱节**（它更像「稳态期望值」而不是峰值）。
  本轮按协议**没有改这个阈值**（调度器不得自行改闸），但事实记在这里：**这不是异常，是配置过期**。
- 内存峰值：Pages occupied by compressor ≈ 6.5 GB、swap 用到 3.1 GB / 4 GB；
  load 1min 峰值仅 3.5 / 10 核 —— **CPU 没满，瓶颈在内存与容器数，不在算力**。
  ⇒ 若用户报「机器卡」，第一顺位是 **PG 容器上限与 swap**，不是并发车道数。
- 本轮 4 条新车道在 L2 期间**没有跑测试**（全部 surefire 报告为 0），
  PG 容器峰值完全由 L2 自身产生 —— 证实了组批协议「发热峰值 = 1 次全量」的设计仍然成立。

## 本轮派发（后台跑，下一轮回收）

| 模块 | 名称 | 服务 | 迁移版本 | 车道分支 |
| --- | --- | --- | --- | --- |
| 5.3.2.2 | 价格规则 | catalog | V236 | `feat/5-3-2-2` |
| 5.4.1.2 | 批量调价 | catalog | V237 | `feat/5-4-1-2` |
| 6.2.1.2 | 补货建议 | trade | V238 | `feat/6-2-1-2` |
| 6.1.2.3 | 供应商资质 | trade | V239 | `feat/6-1-2-3` |

⚠️ **这 4 条车道全部要改 `apps/web/src/lib/api-proxy.ts`** ——
那是共享白名单累积文件，多车道同时改会必然冲突。
本轮已在任务书里为 4 条车道**指定了互不重叠的插入锚点**
（5.3.2.2 → `pricing-templates` 元素之前；5.4.1.2 → 数组末尾；
6.2.1.2 → `order-address-payment-check` 与 `fulfillment/split` 之间；
6.1.2.3 → `fulfillment/split` 与 `fulfillment/dropship-tracking` 之间），
并各自指定了独立正则根路径（`pricing-rules` / `batch-price-adjustments` /
`replenishment-suggestions` / `supplier-qualifications`），
已用脚本核对基线里**全部既有正则对这四个根路径零命中**。
⇒ 下一轮回收时这 4 条车道**应当能干净并入**，不需要再派修复车道。

## 涉及文件

- 本批 6 条车道共 **95 个文件**（后端 Java 31 / 测试 14 / 迁移 6 / 前端 33 / 契约 4 / 文档 7）
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）
- `docs/sessions/2026-10/2026-10-21-batch-43-six-lane-merge.md` —— 本文件

## 关联

- `tools/module-progress/PIPELINE.md` —— 流水线协议（组批协议 / 三层验证 / 收尾三件套）
- `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md` ——
  api-proxy 共享文件冲突的结构性根因与本轮采用的「锚点分离」对策
- `docs/sessions/2026-10/2026-10-21-batch-42-gate-collision-readonly.md` —— 上一轮（零派发只读轮）
- `docs/sessions/2026-10/2026-10-21-0315-main-sync-conflict-squash-divergence.md` ——
  同一时段 `main → module-dev` 同步任务的冲突未并入记录（squash 分叉 6 文件）

## 各模块完整设计文档（车道随附）

- `docs/sessions/2026-10/2026-10-21-logistics-freight-rule.md` —— 5.2.3.2
- `docs/sessions/2026-10/2026-10-21-batch-calculation.md` —— 5.3.1.2
- `docs/sessions/2026-10/2026-10-21-pricing-template.md` —— 5.3.2.1
- `docs/sessions/2026-10/2026-10-21-supplier-source-pool.md` —— 6.1.2.1
- `docs/sessions/2026-10/2026-10-21-stockout-risk.md` —— 6.2.1.1
- `docs/sessions/2026-10/2026-10-21-purchase-arrival-tracking.md` —— 6.3.2.2
