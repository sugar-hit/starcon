# 7.6.1.1 库龄与呆滞（阶段 D3，L3 锚点 7.6.1）

分支 `feat/7-6-1-1`，基线为集成头（648ce11）。Flyway 版本按调度器预分配取 **V65**
（7.5.1.2 占 V63、7.5.2.1 占 V64、7.6.2.1 占 V66，均未触碰）。

## 决策摘要

### 1. 库龄不落表，一律查询时算

库龄 = 今天 − 入库日，是随日历每天变化的派生量。落表意味着要么每天重算全量，
要么承认表里存的是**昨天**的库龄——而呆滞处置要求这个数字是今天的。
所以本模块不建「库龄结果表」，全部在查询时从 `stock_bucket`（批次粒度事实）

- `inventory_lot` 实时算出。

**Trade-off**：无法对历史某天的库龄做回溯对比。若将来要「上月 vs 本月呆滞增量」，
需要在 finance/catalog 侧先有快照或时序事实，那是另一个模块的决策。

### 2. 库龄口径 = `coalesce(received_on, stock_bucket.created_at::date)`，逐行标口径

挂了批次的取真实收货业务日（V2 定义），非批次管理品才退回库位建档日。
每一行都带 `agingBasis`，报表汇总另外报出「其中多少行是代理值」。

**刻意不用 `last_movement_at`**：它是最近一次数量变动的业务时间，每出一票就前移。
拿它算库龄等于把「最近被碰过多��」当成「躺了多久」——天天出货的滞销品会永远库龄 0 天。
7.3.1.2 排 FIFO 时同样刻意不用它，理由一致。

**Trade-off**：非批次管理品的库龄是代理值，少算或多数都可能。
已在响应、页面与测试三处标注，不掩盖。

### 3. 「90+」的下界是 91 而不是 90

蓝图写「0-30/31-60/61-90/90+ 天」。若让 90 同时落进 61-90 与 90+ 两桶，
**各桶相加会大于真实库存**，而「各桶之和 == 总量」是呆滞处置唯一能被信任的前提。
所以 61-90 桶收 61~90，90+ 桶从 91 起算，标签仍显示「90+ 天」。
真实下界在枚举 javadoc、页面说明与集成测试三处钉死。

**Trade-off**：与蓝图字面表述有偏差，但保住了报表的自洽性；已在台账里写明。

### 4. 占用资金：如实报不可用，**不编成本价**

已 grep 全仓确认 warehouse 服务库存事实层（V1/V2/V57）**没有任何成本列**：
`stock_bucket` 只有 `on_hand/reserved/version`；`inventory_lot` 有
`produced_on/received_on/expires_on` 等业务日但无单价；`inventory_entry` 是数量流水不含金额；
全服务不存在 `unit_cost/avg_cost/cost_amount/unit_price/moving_average` 任何一列。
成本口径属 finance 卷，且跨服务禁止读对方表。

所以 `AgingCapitalTie` 恒返回 `available=false` + `occupiedAmount=null` + `reason` + `blockedBy`。
**返回 0 元会被读成「这些呆滞货不占资金」**，而蓝图自己说
「呆滞占用的资金是最容易被忽略的利润黑洞」——假成本比缺成本危险得多，
一个猜出来的金额会被当真去定清仓价。finance 成本接口落地后，
只需在本服务注入一个成本 Port，本表结构无需改动。

### 5. 销售速度未交付 → 判定如实退化到四个维度

13 销量卷未交付，且跨服务禁止读对方表。滞销/呆滞判定只用
**库龄 + 可用量 + 质量状态 + 效期**四个已落地维度，
响应 `salesVelocityNote` 与每行 `basis` 都写明未纳入速度因子，
**不编「周转天数」**（那需要一个本服务查不到的表）。

### 6. 阈值逐 SKU 配置（表而不是常量）

蓝图把「品类特性」列为输入，而品类特性是租户自己的经营事实：快消 60 天不动就该清仓，
家具三年不动才算呆滞。写死全局常量等于对所有品类说同一句话，那句话必然对大部分品类是错的。
与 7.3.1.2 的 `inventory_expiry_policy` 同一套写法：逐 SKU 一行 + 兜底默认值 60/90，
未配时响应标 `source=DEFAULT`。刻意不建租户级默认行，否则「这个数字是谁定的」会变成三态。

### 7. 临期口径复用 7.3.1.2，不另起一套

直接复用 `inventory_expiry_policy.lead_days` 与其档位判定
（`expires_on < current_date` 过期；`expires_on - current_date <= lead_days` 临期），
兜底同为 30 天。两个模块各配各的会让同一批货在一处显示临期、另一处显示正常。

### 8. 呆滞清单默认只看「需要处理」

默认筛选 = 滞销/呆滞 + 临期/过期 + 残次 任一命中（`ACTIONABLE_SQL`），
与 7.3.1.2 效期预警清单默认只看待办两档同一纪律。
刻意**包含临期/过期与残次而不只看库龄**：蓝图写的是「识别滞销、临期、残次、呆滞」四类，
一票只躺 5 天但已过期的货若因「库龄不够 60 天」而不出现在清单上，
处置的人就永远看不到它——那正是最不该被漏掉的一批。看全量需显式传 `slowMovingStatus=NORMAL`。

### 9. 处置建议只读，不声称能自动触发

优先级：**过期 > 残次 > 临期 > 呆滞 > 滞销**。一票已过期的货若被归成「建议促销」，
促销只会让它在货架上再烂 30 天。残次品分两种：批次上有 `supplier_ref_id` 的建议退仓
（退货比报废少损失），没有供应商信息的只能报废——不管有没有 supplier 都写「建议退仓」
等于给一个查不到对手方的建议。`automatable` 恒 `false`（调价属 5.4.1、退仓报废属 6.4.2.2，均未交付）。

## 文件清单

新增

- `backend/services/warehouse/src/main/resources/db/migration/V65__warehouse_inventory_aging_policy.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAgingModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAgingService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAgingController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryAgingIntegrationTest.java`
- `apps/web/src/app/inventory/aging/page.tsx`
- `apps/web/src/components/inventory/inventory-aging.tsx`
- `apps/web/src/components/inventory/inventory-aging-model.ts`
- `apps/web/src/components/inventory/inventory-aging-model.test.ts`

改动

- `apps/web/src/lib/api-proxy.ts`（白名单登记 aging 分支）
- `packages/ui/src/lib/task-pages.ts`（`/inventory/aging` 的 `implemented` 置 true）
- `packages/api-contracts/src/catalog.ts`、`warehouse.openapi.json`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（仅 7.6.1.1 一行）

## 踩坑记录（值得留给下一条车道）

1. **jOOQ 纯文本 SQL：`fetch(String, List)` 不存在。**
   `fetch(String sql, Object... bindings)` 是 varargs，传 `List<Object>` 会被当成**一个**绑定值，
   报错是 `Error while writing value at bind index: 1`，完全看不出是哪个绑定。
   必须 `.toArray()`。
2. **CTE 里的 `?` 默认值必须在筛选绑定之前**。三个 `coalesce` 默认值出现在 SELECT 里
   （比 WHERE 早），漏拼时 PG 报「未设定参数值 1 的内容」，同样看不出是哪个。
   收口成一个 `agingBinds()` 拼接。
3. **筛选条件只能拼一次**。同一批 `where` 既拼进 CTE 的 WHERE 又拼进外层 WHERE，
   占位符出现两次而只绑定一次 → 「未设定参数值 5 的内容」。列表路径的筛选应只作用于外层 CTE。
4. **`whereSql` 为空时不能拼 `" and ..."`**，会得到裸 `and` 语法错误。
5. **外层 CTE 只能用 CTE 的输出列名**，沿用内层的 `b.xxx`/`s.code` 别名会报列不存在。
6. **整数列在纯文本 SQL 里拿不到类型**：`r.get(field, Integer.class)` 会返回 null，
   于是每行库龄静默变空而报表看起来仍有四行。沿用 7.3.1.2 的 `intOrNull` 收口读法。

## 后续待办

1. **占用资金**：等 finance 卷的库存成本接口（移动加权或批次计价）落地后，
   在 `InventoryAgingService` 注入一个成本 Port 补齐 `capitalTie`；本表结构无需改动。
2. **销售速度因子**：等 13 销量卷交付后，在滞销判定里加入周转率交叉验证，
   并把 `salesVelocityNote` 改成说明「已纳入速度因子」。
3. **历史对比**：若需要「本月比上月呆滞增量」，需另立时序事实，不能靠现有派生查询。
