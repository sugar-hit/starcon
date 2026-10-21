# 2026-10-21: 价格历史（5.5.1.1）

## 摘要

在 `catalog` 服务交付蓝图 L3 锚点 5.5.1「价格历史」下的 **5.5.1.1 价格历史**：
记录 SKU × 店铺的**价格变更事实**（时间、旧价、新价、操作人 / 来源、原因），
支持按时间轴回溯，并把**改价前后的利润变化**对齐到同一条变更事实上。
迁移 `V241__price_change_history.sql`（两张新表，零 ALTER 既有表、零外键）·
7 个端点 `/catalog/price-history/**` · 页面 `/pricing/history`（真实工作区）·
写端口 `PriceChangeHistoryPort`（供上游投递变更事件）· 跨服务只读端口 `SalesFactPort`（销量）。

**两条必须写清的事实**：

- **5.4.1.1 调价单尚未接上**（见下节「5.4.1.1 的接入点」）；
- **销量维度当前是「未接线」状态，不是已完成**。

## 决策 / 结论

1. **服务归属 = catalog（确认调度者的判断）**。蓝图 05 卷 L2 表「5.5 价格监控 → catalog」，
   同 L2 的 5.5.2.1 异常告警已落在 catalog（`PriceAnomaly*` / `price_anomaly_observation`），
   本模块与它共享「SKU × 店铺」这一维度口径；5.5.2.2 价格规则（V236）、5.3.1.1 利润测算器（V227）
   也都在 catalog。**没有反驳意见**。

2. **listing 的口径 = (SKU × 店铺)**（`sku_id` + `platform_code` + `site_code`）。
   本仓 catalog 没有 listing 本地投影（listing 事实属 channel 的 4.1.1.1，
   channel 的 listing 表注释就写着「无价格列：售价归 05 定价」），
   AGENTS.md 禁止跨服务查表 / 跨库 FK / 共享视图 ⇒ **不新建任何 listing 影子表**。
   口径与 5.4.1.2、5.5.2.1 逐字相同。

3. **观测 ≠ 变更**。5.5.2.1 的 `price_anomaly_observation` 是「某时刻看到的价」，
   本模块的 `price_change_history` 是「价被改了」。
   观测台账**只读**（绝不写、绝不 ALTER），**不**把观测复制成变更事实 ——
   复制会凭空造出一堆没发生过的调价。

4. **变更事实只追加，不可变**。`price_change_history` 的 GRANT **只有 SELECT / INSERT**。
   改价要改就**再登记一条**，不在原地改写已经发生过的历史 ——
   「这次到底改成了多少、什么时候改的、为什么改」是这本账的全部价值。

5. **本表是唯一写入所有者，上游经写端口投递**。`PriceChangeHistoryPort` 是对外**唯一**入口：
   上游**不直接写表** ⇒ 一次改价只留下一条变更事实，而不是三张表各一行。

6. **利润一律由 5.3.1.1 的引擎算**（`ProfitCalculatorService.calculate`），本模块**零个测算算法**；
   沿用它的**决策就绪闸** —— 成本不完整时利润四列是 **null（不是 0）** 并写明「没测准」的原因。

7. **首次定价 ≠ 「从 0 涨到新价」**。首次定价 `old_price` 为 NULL，方向为 `INITIAL`，
   价差与幅度为 NULL。否则时间轴上会凭空多出一跳 100% 的涨幅。

8. **时间轴的「链条」是一条真实信号**：每一格的旧价必须接得上上一格的新价；
   接不上就**明说断在哪**（旧价 X vs 上一格新价 Y），而不是默默跳过。

9. **全部 DTO 带 `PriceHistory` 前缀**。理由：codegen 对**跨服务同名 DTO 直接中止**，
   而 channel 已有 `ComparisonRow` / `ComparisonPage`、warehouse 已有 `ChangeQuery`；
   catalog 内部 `PriceAnomalyModels` 还有 `InputCapability` ——
   同名会被 springdoc 改名成 `InputCapability1` 这种**顺序相关**的名字（改一个类就可能翻转）。
   前缀化把这类风险一次性消掉。

## 5.4.1.1 调价单的接入点（**下一批接上**）

5.4.1.1 调价单与本模块**同批并行开发**，本仓此刻**不存在**它的代码 ——
   本模块**没有**假装它已接入。下面是下一批要接的**准确契约**。

### 谁调

**5.4.1.1 调价单**（`PriceAdjustment*`，catalog 服务）在自己调价成功落库**之后**，
调本模块的写端口。**不是**本模块去调它，也不是它直接写 `price_change_history` 表。

### 调什么

```java
// backend/services/catalog/src/main/java/com/starcon/catalog/PriceChangeHistoryPort.java
public interface PriceChangeHistoryPort {
  UUID record(AccessService.Access actor, String bearer, PriceChangeEvent event);

  record PriceChangeEvent(
      UUID skuId,            // 被改的 SKU
      String platformCode,   // 店铺的平台码（大写）
      String siteCode,       // 店铺的站点码（大写）
      String currency,       // 新旧价的币种（**两价同币**；跨币种请先折算再投递）
      BigDecimal oldPrice,   // 改价前的价；null = 首次定价
      BigDecimal newPrice,   // 改价后的价（**已生效**的价），必须 > 0
      String changeSource,   // 变更来源枚举名：MANUAL_ADJUSTMENT / BATCH_ADJUSTMENT / PRICE_RULE / …
      String reasonCode,     // 原因字典编码（可空）
      String reasonText,     // 原因的人话快照（**必填**，蓝图要求记「原因」）
      UUID operatorId,       // 操作人；自动规则触发的可空
      String sourceModule,   // **投递方模块编号**（5.4.1.1 填 "5.4.1.1"），必填
      String sourceReference,// 投递方的单据号 / id（可空）
      String eventKey,       // **幂等键**，租户内唯一；建议 "<单据号>:<行号>"
      OffsetDateTime effectiveAt, // 变更**生效**的时刻
      ProfitBasis profitBasis,    // 5.3.1.1 测算器的输入参数（可空 ⇒ 不测算利润）
      ProfitSnapshot profitSnapshot); // 上游**已算好**的利润快照（可空 ⇒ 本模块自己算）
}
```

### 字段怎么映射

| 端口字段 | 5.4.1.1 填什么 | 说明 |
| --- | --- | --- |
| `skuId` | 被调价行的 SKU id | |
| `platformCode` / `siteCode` | 该行所属店铺的平台码 / 站点码 | 与调价单明细逐字一致 |
| `currency` | 调价单上的售价币种 | **新旧价同币**；跨币种请先折算再投递（本模块不折算） |
| `oldPrice` | 调价前的价 | 首次定价填 `null`（**不是 0**） |
| `newPrice` | 调价后的**已生效**价 | 必须 > 0 |
| `changeSource` | `"MANUAL_ADJUSTMENT"` | 单张调价单；规则自动执行可填 `"PRICE_RULE"` |
| `reasonCode` / `reasonText` | 调价单上的原因字典编码 + 人话 | `reasonText` 必填 |
| `operatorId` | 执行人 | 规则自动执行且无人确认时可空 |
| `sourceModule` | `"5.4.1.1"` | **必填**，来路不明的变更不许进账 |
| `sourceReference` | 调价单号 | |
| `eventKey` | `"<调价单号>:<行号>"` | 幂等：重试 / 重复执行不堆行 |
| `effectiveAt` | 该行调价的**生效时刻** | `OffsetDateTime`；**不要**用 `Timestamp.valueOf(LocalDateTime)`（会静默偏 −28800s） |
| `profitBasis` | 5.3.1.1 的输入参数（数量 / 成本币种 / 汇率 / 平台费率 / 物流单价 / 成本窗口） | 5.2.3.1 / 5.2.3.2 未交付 ⇒ 费率与运费是**手工假设** |
| `profitSnapshot` | 若调价单**已经**用 5.3.1.1 的引擎算过改价前后的毛利，就原样传进来 | 传了就**不重算**（重算 = 同一批 listing 多翻一倍 warehouse 成本，且上游抖一下会把已算准的利润降级成「没测准」） |

### 三条纪律

1. **`bearer` 要透传**：利润快照要跨服务读 warehouse 的头程与关税，没有它就算不出完整利润。
   传 null / 空 ⇒ 本模块**不测算**利润（利润四列留 null，「没测准」不是「毛利为 0」）。
2. **投递失败不许把已经改成功的价回滚**：写历史是**记账**不是改价。
   价已经改了，回滚也回不到「没改」的世界里去，只会留下一批改了价、账上查不到的 listing。
3. **不要直接写 `price_change_history` 表**：那张表的写入所有者只有 5.5.1.1。

## 销量维度：**当前未接线**（不是已完成）

蓝图加粗句：「**价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"**」。
本模块的现状：

- 销量事实属 **9 订单履约 / trade 服务**（订单行数量），本仓 catalog 里**一个销量数字都没有**
  （`backend/services/catalog/src/main/java/com/starcon/catalog/` 下无 Sales / Order 类）。
  AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ **不新建销量影子表**。
- 跨服务只读端口 `SalesFactPort` 已定义（`HttpSalesFactPort` + `UnwiredSalesFactPort` 降级 +
  `SalesFactConfiguration` 按 `TRADE_URL` 装配）。端口约定
  `GET /trade/sales-facts/sku-summary?skuId=&platformCode=&siteCode=&from=&to=` ——
  **该端点当前未交付** ⇒ 接线之前一律 503。
- **未接线时的表现**：`GET /catalog/price-history/sales` 返回 **503**（不是 200 + 0）；
  对比视图 `salesAvailable=false`、销量三列为 **null** 并附一句说明；页面上写「未接线」。
- 🔴 **绝不用 0 或空数组冒充销量**：那会让每次降价都显示成「降价后一件没卖出去」，
  是一个**凭空造出来的结论**，比「读不到」危险得多。
- 下游 13 分析（13.2.1.1 销量趋势 / 13.3 产品表现）会拿价格历史 + 销量 + 利润三者对齐去回答
  「降价到底有没有用」；本模块提供前两者（价格 + 利润），**销量那一半当前未接线**。

## 涉及文件

**后端（catalog 服务）**

- `backend/services/catalog/src/main/resources/db/migration/V241__price_change_history.sql`（新）
  —— `price_change_history` / `price_change_reason`；零 ALTER 既有表、零外键、
  ENABLE + FORCE RLS、变更事实额外 `data_scope` 跟随 sku、GRANT 只授 SELECT/INSERT。
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceHistoryModels.java`（新）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceHistoryService.java`（新）
  —— jOOQ 显式 SQL；**同时是** `PriceChangeHistoryPort` 的实现（本表唯一写入所有者）。
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceHistoryController.java`（新）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceChangeHistoryPort.java`（新）
  —— 写端口 + 字段映射表。
- `backend/services/catalog/src/main/java/com/starcon/catalog/SalesFactPort.java`（新）
- `backend/services/catalog/src/main/java/com/starcon/catalog/SalesFactConfiguration.java`（新）
- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchPriceAdjustmentService.java`（改）
  —— **最小增量**：执行成功后经写端口逐条投递（只投 `WILL_ADJUST` 的行）。

**测试（catalog 服务）**

- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java`（新，16 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryTenancyIntegrationTest.java`（新，6 例）

**前端**

- `apps/web/src/components/pricing/price-history-model.ts`（新）
- `apps/web/src/components/pricing/price-history-model.test.ts`（新，31 例）
- `apps/web/src/components/pricing/price-history-workspace.tsx`（新）
- `apps/web/src/app/pricing/history/page.tsx`（新）
- `apps/web/src/app/api/catalog/price-history-allowlist.test.ts`（新）
- `apps/web/src/lib/api-proxy.ts`（改）—— **纯增量**新增一条 `price-history` 正则元素，
  **未修改任何既有正则**。

**契约与台账**

- `packages/api-contracts/src/**`（codegen 产物，+7 path / +20 DTO）
- `docs/product/capability-blueprint/module-progress.md`（只改 `5.5.1.1` 那一行的「状态」「证据」两格）

## 关键 trade-off / 讨论

1. **为什么不直接把 5.4.1.2 的 `batch_price_adjustment_item` 读成变更事实？**
   那样不需要改 5.4.1.2 的代码，但那是**复制别人表里的事实**，
   且丢失「谁投递的」这一维。改成**写端口 + 幂等键**之后：一次改价只留一条事实，
   投递方可追溯，重试不堆行。代价是改了 5.4.1.2 的服务类（**未改它的端点 / 迁移 / 页面**）。
2. **利润快照：重算 vs 直接传？** 重算会让同一批 listing 多翻一倍 warehouse 成本，
   且 warehouse 抖一下会把**已经算准的**利润降级成「没测准」⇒ 端口同时支持
   `profitBasis`（自己算）与 `profitSnapshot`（上游已算好，**原样落库**），给了就以给的为准。
3. **变更事实要不要「撤销 / 作废」端点？** 没有。蓝图要的是「记录变更历史」，
   而**只追加**才是历史可信的前提；要撤销就再登记一条反向变更。
4. **原因用自由文本还是字典？** 字典。自由文本写出来的「跟竞品」「竞品」是两个字符串，
   筛不出来。字典给稳定编码，筛选与统计才有意义；且历史存**快照**，
   改字典不改写历史。
5. **契约 DTO 全部加前缀**，代价是名字变长；收益是彻底避开跨服务同名导致 codegen 中止、
   以及 springdoc 顺序相关的 `InputCapability1` 式改名。

## 后续待办

- **5.4.1.1 调价单接入写端口**（下一批）—— 契约见上节，接完把台账 5.4.1.1 那行补上。
- **销量端点交付**：`GET /trade/sales-facts/sku-summary`（9 订单履约侧）
  + 部署时配 `TRADE_URL` ⇒ 本模块的销量维度自动从「未接线」变成「已就绪」。
- **价目表（5.1）交付后**：5.1 价目表改价也是一个变更来源，届时经写端口投递
  （`changeSource = "PRICE_LIST"`）。
- **历史回填**：本模块交付前的批量调价（5.4.1.2 已交付但在本模块之前执行的那些）
  **不会**自动出现在时间轴上。要回填需要一次显式的补录动作（可作为后续小需求）。

## 关联

- 蓝图：`docs/product/capability-blueprint/05-pricing.md`（5.5.1 价格历史 / 5.5.1.1）
- 台账：`docs/product/capability-blueprint/module-progress.md`（`5.5.1.1` 行）
- 相邻已交付模块：`PriceAnomaly*`（5.5.2.1）、`BatchPriceAdjustment*`（5.4.1.2）、
  `ProfitCalculatorService`（5.3.1.1）、`PricingRuleService`（5.3.2.2）
- ADR：`docs/decisions/0002-multi-tenancy-strategy.md`、`0004-java-spring-boot-microservices.md`、
  `0005-tenant-hierarchy-and-page-rbac.md`
