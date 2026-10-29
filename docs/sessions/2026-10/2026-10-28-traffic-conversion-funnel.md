# 2026-10-28: 13.3.2.1 流量转化 —— 两套口径分列的转化漏斗（阶段 D7，L3 13.3.2 的第一个模块）

> 车道 `feat/13-3-2-1`（从集成头 `integration/module-dev` @ `f8b9383a` 切出）。
> 蓝图：`docs/product/capability-blueprint/13-profit-and-analytics.md` → 13.3.2.1「流量转化」。
> **本车道是 L3 13.3.2 的第一个模块** ⇒ 写法由本车道定（照抄 13.2.2.1 投影表那套）。

## 摘要

| 项 | 值 |
| --- | --- |
| 模块 | 13.3.2.1 流量转化（卷 13 L3 13.3.2 流量与转化 → 第一格） |
| 迁移 | `V395__traffic_conversion_funnel.sql`（三张新表，零 ALTER 既有表） |
| 端点 | 5 个，挂 `/reporting/traffic-conversion` |
| 页面 | `/analytics/products/traffic-conversion` |
| 测试 | 后端 26 例（22 功能 + 4 租户隔离）· 前端 19 例 · api-proxy allowlist 回归 71 文件 / 394 例 |
| L0 | `./mvnw -q compile` exit 0；`pnpm typecheck` 3/3 successful |
| L1 | `TrafficConversion*` ⇒ **26 例全绿**（Failures 0 / Errors 0）BUILD SUCCESS |
| 契约 | codegen 产物，**纯增量**（reporting.ts +12 / reporting.openapi.json +760，removed = 0） |

## 决策摘要

### ① 「两套口径不许混算」落成**数据层的三个标签**，不是注释

蓝图的关系句是加粗的：「**平台流量数据与内部订单数据口径不同**，展示时必须标注来源与统计范围
（页面 vs 订单）」。这条如果只写在文档里，半年后一定会有人把两个口径的转化率加在一起。

⇒ 落成每条事实行上的三个标签：

- `traffic_source`（LISTING / AD / ORDER）—— **谁家的口径**；
- `bucket_basis`（PLATFORM_DAY / UTC_DAY）—— **统计日是按谁的日历切的**；
- `currency`（空串 = 与币种无关）—— **量的形状**。

再加两个 DB 触发器把纪律钉死：**派生指标不许有事实行**（派生值只能在查询期算，落库就成了第二个真相）；
**涉币指标的事实行必须带 3 位大写币种码** ⇒ 不同币种的钱永远不可能被加到一起。

### ② 自然流量是**推导值**，且相减为负时**报口径冲突而不是 0**

上游**没有**「按流量来源拆分」的流量口径：channel 的 listing 表现（listing 级）与广告报表（活动级）
是**两套独立报表**，统计范围不同。把它们相减当「自然流量」是**推导**，不是平台给的数。

⇒ 口径表里用 `DERIVED_DIFF`（平台商品口径 − 广告口径）显式声明这个推导，描述里写明推导方式。
⇒ 相减为负 ⇒ `availability=CALIBER_CONFLICT`（前端 danger 色 + 「口径冲突」徽标），
**绝不截成 0** —— 截成 0 会把口径问题藏起来，让用户以为「这段时间没广告也没自然流量」。
⇒ **自然 Session 算不出来**（广告侧没有 Session 口径）⇒ 自然漏斗只有曝光与点击两节。

### ③ 件数**按币种分行存储**，查询期**跨币种合计**（客单价因此是精确的）

关键 trade-off：如果把件数一律存成「与币种无关」（币种空串），客单价的分母就是**全部币种的件数** ——
多币种租户的客单价会被算歪。

⇒ 照抄 13.2.2.2（商品维度）的做法：**事实仍按币种分行存储**，查询期分两种取法：
- 涉币的比率（客单价）：分子分母取**同一个币种桶** ⇒ 精确；
- 与货币无关的比率（点击率 / 转化率）与件数类指标：**跨币种合计**（件数相加不会把两种货币的钱加起来）。

代价：口径表的 `currency_aware=false` 不再意味着「事实行币种必须为空串」，触发器相应放宽
（涉币指标仍**必须**带币种码 ⇒ 钱不会混算）。

### ④ 诊断的环比基线是**窗口内的上一个桶**，第一个桶**不诊断**

窗口外的事实没有投影 ⇒ 窗口的第一个桶没有基线。**不诊断 ≠ 没有异常**，页面上写明这一点。
（另一种做法是刷新时多投一段历史，但那会让上游取数窗口翻倍，且窗口多长取决于粒度，得不偿失。）

### ⑤ 刷新是**窗口重建**而不是增量叠加

平台会修正同一天的报表 ⇒ 旧行必须**消失**而不是留着两份。刷新先删该窗口（按 `traffic_source`）的旧事实
与明细，再整段重写；删与写在同一个租户事务里 ⇒ 不会留下半张表。

## 依赖自检（发车前 10 分钟）

三条上游逐个在集成头 grep 得到，**非 BLOCKED**：

| 上游 | 端点 | 取什么 |
| --- | --- | --- |
| 2 同步（channel） | `GET /channel/listing-performance` | 曝光 / 点击 / Session / 平台销量 / 平台销售额（**平台自然日**） |
| 14 广告（channel） | `GET /channel/ad-reports/rows?dimension=CAMPAIGN&granularity=DAY` | 广告曝光 / 点击 / 订单 / 花费 / 销售额 |
| 9 订单（trade） | `GET /trade/sales-orders/list` | 订单笔数 / 订单金额 / 币种 / 下单时刻（**UTC 自然日**） |

⚠️ 响应字段形状逐条核对过（写错字段名不会报错、只会静默变 null ⇒ 整类指标恒为 0）：
listing 与 ad-reports 的列表字段是 `items`，trade 的是 `rows`。

## 文件清单

**迁移**
- `backend/services/reporting/src/main/resources/db/migration/V395__traffic_conversion_funnel.sql`

**后端**（`backend/services/reporting/src/main/java/com/starcon/reporting/`）
- `ReportingTrafficConversionCatalog.java` —— 口径唯一表的标准份 + 漏斗分组 + 诊断阈值 + 口径说明
- `ReportingTrafficConversionPort.java` —— channel 的跨服务只读端口（平台表现 + 广告报表）
- `ReportingTrafficConversionRefreshService.java` —— 拉取适配器（上游 → 投影事实 + 构成明细，窗口重建）
- `ReportingTrafficConversionService.java` —— 查询侧（漏斗 / 关键指标 / 趋势 / 诊断 / 下钻）
- `ReportingTrafficConversionModels.java` —— 出入参模型
- `ReportingTrafficConversionController.java` —— HTTP 端点 + 能力声明的静态事实

**测试**（`backend/services/reporting/src/test/java/com/starcon/reporting/`）
- `TrafficConversionIntegrationTest.java`（22 例）
- `TrafficConversionTenancyIntegrationTest.java`（4 例，三条隔离断言）

**前端**
- `apps/web/src/components/analytics/traffic-conversion-model.ts` + `.test.ts`（19 例）
- `apps/web/src/components/analytics/traffic-conversion-workspace.tsx`
- `apps/web/src/app/analytics/products/traffic-conversion/page.tsx`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**（纯增量 50 行，零删改）

**契约 / 台账 / 日志**
- `packages/api-contracts/src/contracts/reporting.ts` · `packages/api-contracts/src/reporting.openapi.json`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 13.3.2.1 那一行的「状态」「证据」两格
- 本文件

## 后续待办

- **13.3.2.2 关联营销效果**（本 L3 的第二格）：营销位曝光点击 + 成交归因。
  ⚠️ **归因规则（末次点击 vs 首次点击）本模块没有** —— 本模块的「广告成交订单」是**平台侧归因到广告活动**的订单，
  不做跨活动归因。13.3.2.2 要另起归因事实，别复用本模块的 AD 口径行。
- **13.3.1.1 产品表现分析**：它的「流量与转化」那一段可以直接读本模块的
  `reporting_traffic_conversion_drilldown_fact`（`source_kind='LISTING'` 的行 = 哪个 listing 的曝光/点击/Session），
  **不需要**改本模块的结构。
- **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**（多车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
- **广告口径拿不到店铺码**：上游按活动分组时只给店铺名 ⇒ 广告口径的事实行不带 `shop_code`。
  若后续要按店铺拆广告流量，需要上游提供 `shopCode`（本车道**不擅自改别人的端点**）。
