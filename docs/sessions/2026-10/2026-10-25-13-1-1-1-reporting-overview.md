# 2026-10-25: 13.1.1.1 经营总览（卷 13 第一格 · 第二任接手）

## 摘要

蓝图 13 卷「利润与经营分析」L3 13.1.1 下的第一格 **13.1.1.1 经营总览**落地：新建 `reporting` 服务
（端口 8088 / 库 `starcon_reporting`），用**七张投影表**承载「指标卡 + 趋势图 + 异常提示 + 下钻入口 +
数据更新时间戳」，8 个端点 + 真实前端工作区 `/analytics`，集成测试实跑 Testcontainers PostgreSQL 16。

本条车道是**第二任**：第一任在 2026-10-25 04:50 因 `net::ERR_CONNECTION_CLOSED` 掉线，成果全在
（`8c8f7c47` 已提交服务引导；22 个文件的工作区成果未提交）。第二任**没有重写**任何一块 —— 逐个复核后
只改了 5 处真实缺陷，并补跑全部验证闸。

## 决策 / 结论

### 1. reporting 是投影服务，不是写模型服务 ⇒ 「清空重建」靠**换纪元**而不是删账本

卷 13 卷首写死「不产生业务事实、不做业务写入。报表是投影，可从事件重建」。但事件账本
`reporting_projection_event` 在 DB 层被 `REVOKE UPDATE,DELETE`（它是举证凭据，只能追加），
⇒ 账本删不动 ⇒ 「这批事件投过了」这件事挡不住重投。

**决策**：加第七张表 `reporting_projection_epoch`（重建纪元）。唯一键
`UNIQUE(tenant_id, source_service, source_event_id, epoch)` 含 epoch ⇒ 同一个 `source_event_id`
在纪元 N 与 N+1 是两条不同的账本行。清空 = 清事实 + 清构成明细 + 清游标 + **纪元 +1**，
**旧账本原样留着**当「上一轮投过什么」的证据。

**代价**：账本表随重建轮次单调增长（每轮每事件一行）。可接受 —— 举证凭据本就不该消失。

### 2. 口径只存在一份 ⇒ 派生型指标**只写分子分母引用**，不写公式 SQL

`reporting_metric_definition` 是运行时唯一的真相（标准份只在第一次投影时按租户播种，
`on conflict do nothing` ⇒ 租户改了自己的口径后播种**不会**覆盖它）。毛利率写成
`GROSS_PROFIT ÷ SALES_AMOUNT`，而不是 `(sales − cost) ÷ sales` —— 后者在 finance 改了科目归类时
会出现**第二个真相**。

查询侧只按定义聚合：派生型**递归求值**（分子分母本身可能是派生型），深度上限 4，超了直接报错
（口径表被改坏时要炸，不能无限递归）。

**决策**：派生指标只写引用、由 DB 触发器 `guard_reporting_metric_definition_ref` 钉死「分子分母必须
已定义」，另外两个触发器钉死「事实行 / 构成明细的指标码必须已在口径表登记」⇒ 口径唯一这条红线
落成**数据层做不到的事**，不靠应用层自觉。

### 3. 「算不出来」与「等于 0」严格分开

分母为 0、事实不存在、指标未登记 —— 三种情况一律返回 `null` / `NO_DATA`，**不用 0 冒充**。
销售额 0 与「这一格没算出来」在页面上长得一模一样，含义却相反。

同理：不可比的环比/同比报 `comparable=false` + 原因，**不显示成 0%**；趋势图里没有事实的那天
**没有点**，不是 0；下钻是**真分页 + 真计数**。

### 4. 不跨币种相加；折算必须带汇率与时点

涉币指标按币种分别报。只有调用方显式指定本位币、且 `reporting_currency_rate_snapshot` 里有
生效日不晚于窗口末的汇率时，才额外给出本位币合计，并把**汇率与时点**一起返回。

**决策**：**刻意不读**上游的 `/catalog/exchange-rates/latest`。那个端点给的是「**当前**汇率」，
用它会让今天的汇率变化改写上月的报表（卷 13 卷首「今天的汇率变了不能让上月的报表变」）。
只走列表端点 `/catalog/exchange-rates`，由服务层按 `effectiveDate` 挑出每个币种对最新的一条落成快照。

### 5. 净利口径归 12.5.2.2 财务报表所有

`refreshProfit` 只读 finance 利润表里 `lineCode = "TOTAL:PROFIT"`（**稳定键**：租户改模板行名时
行码不变）的「本期损益」行，**不自己**把收入行加成本行 —— 后者会在 finance 改了科目归类时变成第二个真相。

**分桶规则（设计决策）**：finance 的利润表按**会计期间**组织，不是按天。本服务把「本窗口的净利」落在
**窗口所覆盖的最后一个会计期间的期末日**上 ⇒ 刷新窗口变了落点也变，所以**刷新窗口 = 报表口径**，
这一点在端点返回体与口径说明里原样报出来。

### 6. 上游归属缺口是**事实缺口**，不是本模块偷懒

上游 `sales_order` 表**没有** `shop_id` / `legal_entity_id`（已核 V99），成本 / 退货 / 广告的上游事实
同样不带归属 ⇒ **销售额、订单量、销量、成本、退货额、广告花费只落在 COMPANY 层**。

只有真正带归属的两类事实做双层立方：应收余额（SHOP 层 + COMPANY 层，来自 `finance_receivable.shop_id`）、
库存总额（LEGAL_ENTITY 层 + COMPANY 层，来自 `warehouse_inventory_balance.legal_entity_id`）。

**决策**：这一缺口写进**能力声明的 `notDelivered`**（逐条带原因），而不是让前端把空卡片画成「0」。
能力声明里 `upstreamModules` 也从空列表补成**逐条真读过的上游模块**（模块号 + 模块名）——
空列表会被读成「上游没问题」。

### 7. 推 / 拉两条入口共用一份幂等键、一份口径表、一套重建逻辑

- **推**（`POST /reporting/projection/events`）是蓝图设计的**主路径**（上游投递事件）。
- **拉**（`POST /reporting/projection/refresh`）是同一条管道的另一种入口：本服务自己去上游的
  授权只读 API 取事实，归一化成**同一套 envelope**，交给**同一个投影器**。

拉取端点先落地，是因为上游的事实型 outbox 事件尚未产出 —— 但这**不改变**「投影可重建」的性质。
上游读不到时**那一路失败（503）**，不用空集合冒充「上游没有数据」（空集合在报表里长成「销售额 0」）。

### 8. 幂等键用**内容寻址**

拉取适配器给每条上游事实生成 `sourceEventId = 服务:流:上游行标识:内容摘要`（SHA-256 前 16 字节）。
同一行内容没变 ⇒ id 不变 ⇒ 账本判重 ⇒ **跳过**（重复拉取不会翻倍）；同一行内容变了 ⇒ id 变 ⇒
重新投影 ⇒ 事实行按幂等唯一键**覆盖**（不是追加）。「重复拉取」与「上游改了」用同一个键表达，
不需要两套机制。

## 关键 trade-off

| 取舍 | 选择 | 放弃的东西 |
| --- | --- | --- |
| 清空投影 vs 保留举证 | 换纪元 +1，账本只追加 | 账本随轮次增长 |
| 口径写公式 vs 写引用 | 只写分子分母引用，递归求值 | 递归求值多一层复杂度（深度上限 4） |
| 汇率取 latest vs 取窗口当时 | 取列表端点 + 服务层挑最新 | 每个币种对要多读一次列表 |
| 净利自己算 vs 读 finance | 读 `TOTAL:PROFIT` 行 | 窗口必须落在会计期间上 |
| 上游缺归属时 | 只落 COMPANY 层 + 能力声明写明 | 店铺/法人维度的销售类指标暂时是空的 |
| 拉取 vs 等推事件 | 两条入口同管道共用 | 上游 outbox 未产出前多一层适配代码 |

## 第二任复核后修掉的 5 处真实缺陷

上一任的心跳称已修好三处（账本 REVOKE 冲突、epoch 重建机制、DELETE fetch 恒 0）—— **复核确认三处都在**
（`EVENT_INSERT` 插入时写 `fact_count` 且此后不再 UPDATE 账本；`reporting_projection_epoch` 表 +
`currentEpoch()` + `clearProjection` 推进纪元；`clearProjection` 用 `sql.execute` 而不是 `fetch`）。
在其之上，第二任修了：

1. **三个源码文件里嵌着 U+FFFD 替换字符**（上一任会话在网络事故中打死时，中文字符被逐字节替换）：
   `ReportingOverviewModels.java:15`（3 个）、`overview-model.ts:13`（2 个）、`page.tsx:22`（2 个）——
   全部是「销售额 0」里的「额」字。定点还原后全仓这三个文件 U+FFFD 计数归零。
2. **能力声明返回空列表**（`upstreamModules` / `notDelivered` 都是 `List.of()`）⇒ 补成逐条真读过的
   上游模块与逐条带原因的 `notDelivered`，并加测试锁住（不许再用空列表冒充「都接通了」）。
3. **`/reporting/projection/rebuild` 的端点说明与实现不符**：说明写「清事件账本」，实现**不清**
   （账本 append-only，删不动）⇒ 改成说明「账本不清，靠纪元 +1 抹掉『已投过』」。
4. **两处死代码**：`ReportingCatalogPort.latestRate`（0 调用点，且语义上与「今天的汇率变了不能让
   上月的报表变」相悖 ⇒ 删掉并写清理由）、`ReportingUpstreamClient.Ignore`（从未被引用 ⇒ 删）。
   删掉后本服务实际读的上游端点从 12 个降为 **11 个**。
5. **AGENTS.md 硬要求 5 缺一格**：要求「写时间的测试要**断言写回的值**，不能只断言没抛异常」，
   原测试没有。补 `事件时刻写回数据库后是同一瞬间_不偏28800秒` —— 用**非 UTC（+08:00）**的时刻
   （拿 UTC 时刻去测，那条 bug 根本不会触发，测试会假绿），断言 `occurred_at` 写回后 `toInstant()`
   与写进去的**逐秒相等**，并断言 `applied_at` / 事实行 `updated_at` 有值、分桶时区落库。

## 涉及文件

**迁移（1）**
- `backend/services/reporting/src/main/resources/db/migration/V376__reporting_projection.sql`（七张表）

**后端主源码（10）**
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingOverviewController.java`
- `.../ReportingOverviewModels.java`
- `.../ReportingOverviewService.java`
- `.../ReportingProjectionController.java`
- `.../ReportingProjectionModels.java`
- `.../ReportingProjectionService.java`
- `.../ReportingRefreshService.java`
- `.../ReportingMetricCatalog.java`
- `.../ReportingUpstreamClient.java`
- 五个端口：`.../ReportingCatalogPort.java` / `ReportingChannelPort.java` / `ReportingFinancePort.java` /
  `ReportingTradePort.java` / `ReportingWarehousePort.java`

**后端测试（1）**
- `backend/services/reporting/src/test/java/com/starcon/reporting/ReportingOverviewIntegrationTest.java`（14 例）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/contracts/reporting.ts`（16 个 export type）
- `packages/api-contracts/src/reporting.openapi.json`（8 条路径 / 16 个 schema）

**前端（5）**
- `apps/web/src/app/analytics/page.tsx`
- `apps/web/src/components/analytics/overview-model.ts`
- `apps/web/src/components/analytics/overview-model.test.ts`（25 例）
- `apps/web/src/components/analytics/overview-workspace.tsx`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，既有 234 条一字未改）
- `apps/web/src/lib/api.ts`（**新增一条 backends 条目**）

**台账 / 会话日志（2）**
- `docs/product/capability-blueprint/module-progress.md`（**只改 13.1.1.1 那一行**）
- `docs/sessions/2026-10/2026-10-25-13-1-1-1-reporting-overview.md`（本文件）

## 验证（本车道自跑）

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `./mvnw -q compile`（backend） | exit 0 |
| L1 模块闸 | `./mvnw -pl services/reporting -am test -Dtest='Reporting*,OpenApiContractTest' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **15 例全绿**（14 + 1），Failures 0 / Errors 0，BUILD SUCCESS |
| 类型 | `pnpm exec turbo run typecheck --force`（**强制绕过缓存**、0 cached） | 3/3 成功 |
| 前端单测 | `pnpm vitest run apps/web/src/components/analytics/overview-model.test.ts` | 25 例 |
| 白名单回归 | `pnpm vitest run apps/web/src/app/api/trade` | 71 文件 / 394 例全绿 |
| 白名单回归 | `pnpm vitest run apps/web/src/lib/api-proxy.test.ts` | 16 例 |
| 契约 | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | 6926 DTO / 8 个服务文件；**其余 7 个服务的契约逐字节未变** |

**PG 容器**：测试期间同时存活 **1** 个（用例自带的 Testcontainers `postgres:16-alpine`），
结束后由用例自己的 `@AfterAll` 停掉；复查 `docker ps --filter ancestor=postgres:16-alpine` 为 **0**，无泄漏。

## 后续待办

- **13.1.1.2 多店对比**（同 L3 13.1.1，尚未开工）：读同一张 `reporting_metric_fact`（按 `shop_id`
  分组），**不需要改结构**。但**前置条件是上游补上销售类事实的店铺/法人归属** —— 在那之前
  多店对比会拿到空的店铺维度。
- **上游事实型 outbox 事件**尚未产出 ⇒ 推路径（`POST /reporting/projection/events`）目前无投递方。
- **上游销售类事实缺店铺/法人归属** ⇒ 见决策 6。
- 13.2.x（销量趋势 / 分时分周）、13.4.x（利润分析 / SKU 利润）、13.5.x（库存 / 采购 / 物流分析）
  是本模块的下游专题分析，读的都是本服务已建的投影表。
- `reporting_metric_fact.bucket_date` 是 `date` ⇒ 按周 / 按小时要另加时间粒度列（本期刻意不加：
  「一个指标只允许一处定义」优先于「一次把所有粒度都建出来」）。

## 关联

- 蓝图：`docs/product/capability-blueprint/13-profit-and-analytics.md` 的 `### 13.1.1.1 经营总览`
- 服务引导 commit：`8c8f7c47 feat(reporting): 服务引导 —— 13 利润与经营分析服务骨架 + 8088 端口 + V376 投影表结构 + 契约导出`
- 同批并行车道：18.1.1.1「应用与凭证」（platform，V377）—— **不共用本车道的任何文件**；
  本车道的契约文件（`packages/api-contracts/src/contracts/reporting.ts`）已含在 `8c8f7c47` 之后的
  本车道 commit 里，对方需要它来解掉自己那条 TS2306（`8c8f7c47` 里那份是 85 字节的空壳）。
