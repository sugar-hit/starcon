# 13.3.1.1 产品表现分析（阶段 D7）

> 车道：13.3.1.1 · 分支 `feat/13-3-1-1` · 基线 `integration/module-dev`（f8b9383a）
> 蓝图：`docs/product/capability-blueprint/13-profit-and-analytics.md` 的 13.3.1.1。

## 这个功能做什么

单产品的 360 度分析：**销量、销售额、毛利、流量、转化、退货、库存、库龄、广告表现、竞品对比**，
**集成在同一页面滚动查看**（蓝图原话：避免页面来回跳）。输入产品标识（SKU / SPU / Listing）+ 时间范围 +
对比基线，输出多指标分析页 + 趋势 + 排名分位。

蓝图把它称为「**运营每天用得最多的页面**」⇒ 性能与信息密度优先，指标要可对比（环比 / 同层级排名）。
本模块是**投影**：业务事实仍在各自业务域（订单在 trade、成本在 finance、库存在 warehouse、
流量与广告在 channel），任何数字与业务系统冲突**以业务系统为准**。

## 关键 trade-off

1. **另起一组表，不复用 13.2.2.2 的 `reporting_product_metric_fact`**
   —— 兄弟模块刷新时会 `delete ... where tenant_id = ? and bucket_date between ? and ?`
   **删掉整张表在窗口内的全部行**。本模块的事实若写进那张表，兄弟模块刷一次就把本模块的
   毛利 / 退货 / 流量**静默变空**。口径归属也不同（毛利 / 退货 / 流量 / 库龄归 12.4 / 9 / 4.5.1.1 / 7，
   销量 / 销售额 / 订单数归 9.1.1.1）。加上 reporting 的纪律是**零 ALTER 既有表** ⇒ 另起三张表。

2. **口径唯一，且跨口径表引用**
   —— 本模块的「毛利额 = 销售额 − 出库成本」里，「销售额」的口径登记在 **13.2.2.2 的
   `reporting_product_metric_definition`**（商品维度口径的唯一登记处，重复登记就是第二个真相）。
   ⇒ DB 触发器**两张口径表都查**：口径在服务内只定义一次，跨口径引用合法，重复登记会被发现。
   查询 SQL 里**没有任何硬编码的指标公式** —— 派生型只写分子 / 分母引用。

3. **「算不出来」与「等于 0」严格分开**
   —— 分母为 0 / 没有事实 / 上游未交付分别是 `null` / `NO_DATA` / `NOT_COMPARABLE` / `NOT_DELIVERED`，
   **绝不用 0 冒充**。理由：假 0 会被读成「这个商品卖不出去」，运营会去追一个不存在的问题。
   不可用的指标在口径表里**显式登记** `available=false` + `ownerModule` + 原因，页面显示
   「本轮不可用 + 归谁管」，而不是显示 0 或干脆不出现。

4. **绝不跨币种相加**
   —— 金额按币种分别报；只有件数与次数（与货币无关的量）给一个跨币种合计，它的币种是**空串**
   （空串 = 与币种无关，**不是「币种未知」**）。DB 层用 CHECK + 触发器钉死「涉币指标必须带币种、
   与币种无关的指标必须不带」，前端与查询侧都不可能再把「曝光」当成「USD 的曝光」。
   排名同理：**按销量（件数）排**，金额跨币种不可比。

5. **平台口径与内部订单口径并列展示，不混算**
   —— 曝光 / 点击 / Session / 平台口径销量 / 平台口径销售额来自 channel 的 4.5.1.1；
   销量 / 销售额 / 订单数来自内部订单明细经 13.2.2.2 的投影。两者**不是同一个数**，
   页面上标注来源。转化率用平台口径销量 ÷ Session（与它的分子分母同源）。

6. **维度不重复计数**
   —— 订单侧指标（销量 / 成本 / 退货）在 SPU 层 = 名下 SKU 之和；在 Listing 层 =
   **可唯一归属**的那些 SKU 之和（trade 的订单明细**只带 SKU 码**、不带平台商品 id ⇒ 同一款内部
   SKU 挂在多个 Listing 上时**无法唯一归属**，**不摊给任何 Listing** —— 摊给谁都是造假）。
   平台侧指标（流量 / 转化 / 广告 / 跟卖）原生是 Listing 粒度，在 SKU / SPU 层按**去重后**的
   Listing 求和（同一个 Listing 挂多个 SKU 时**只算一次**）。落库时**只存上游原生粒度**，
   「不重复计数」在查询侧算 —— 落库时硬摊会把一个 Listing 的流量算两遍。

7. **成本按「同一出库流水取最新批次那一版」去重**
   —— 成本分录是按批次落的，同一条出库流水被**重算**过时会产生新批次的新分录，旧分录仍在表里
   ⇒ 直接把分录加总会**把成本算两遍**。本服务用批次的先后顺序挑出最新那一版，多算的那份不进来。

8. **日粒度事实与时点快照分表**
   —— 库存（在手 / 预留 / 可用）、库龄、跟卖价格**没有「日粒度」语义**：它们是某一时点的读数。
   塞进日事实表会让人以为「每天都有一个库存快照」，而真相是「只有刷新那一刻有」。
   广告活动报表是**窗口聚合**（上游按「活动 × 币种」聚合整个区间）⇒ 快照表带 `window_from` / `window_to`，
   否则当期窗口与基线窗口的行会互相覆盖。

9. **时钟域只认数据库时钟 + `?::timestamptz`**
   —— 投影时刻一律取 PG 的 `now()`（DB 钟域），不绑 JVM 墙钟；时间字段用 `OffsetDateTime` 绑定、
   values 位置写 `?::timestamptz`，**不用** `Timestamp.valueOf(LocalDateTime)`（那会静默偏 −28800s）。
   本仓已有多次同类事故（identity 审批待办超时、warehouse 货权档漂移判定、catalog 时间戳往返断言）。

10. **一次性拉齐**
    —— 蓝图要求「避免页面来回跳」⇒ 后端一次调用返回全部区块 + 趋势 + 排名分位 + 环比基线，
    页面**不做瀑布式串行请求**。刷新时**基线窗口与当期窗口一起刷** —— 只刷当期的话，
    环比那一列永远是「没有数据」。

## 因上游未交付而没做的指标（及原因）

以下指标**在口径表里显式登记**（`available=false` + `ownerModule` + 原因），
页面显示「本轮不可用 + 归谁管」，**不是 0**，也不是不出现：

| 指标            | 归属模块     | 为什么没做                                                                                                                                                                               |
| --------------- | ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 商品维度 ACOS   | **14.3.1.3** | channel 的广告报表只有活动 / 平台 / 店铺 / 广告类型 / 币种**五种分组维度**，**没有 SKU / SPU / Listing 维度**。本模块给的是**活动级**花费，**不换算**成商品级 ACOS —— 换算就是编造归因。 |
| 广告归因 ROAS   | **14.3.2.1** | 广告**归因**（末次点击 / 首次点击）上游未交付。本模块的广告销售额是**广告平台自己报的口径**，不是归因结果，两者不可混用。                                                                |
| 库存金额        | —            | warehouse 的库存事实层**没有任何成本列**（已 grep 全仓确认）⇒「库存占用了多少钱」是**没有数据**，**不是 0 元**。                                                                         |
| 13.4 多模式利润 | **13.4.1.1** | 本模块的毛利 = 销售额 − **成本法出库成本**（12.4.1.2），**不含**平台费 / 头程 / 物流费 / 广告费。要按经营模式（自发货 / 全托管 / 半托管 / 平台仓）拆利润，得等 13.4.1.1。                |

另有两处**边界**（不是缺失指标，是口径本身的边界，已写进能力声明）：

- 竞品对比**只是参考指标**（跟卖价格来自 4.5.2.1；自家参考售价是**人工维护**的非平台权威价），
  **竞品数据不进自有商品模型**。
- 广告活动的投放商品与售后单明细都是**逐个取**（N+1），有张数上限，触到上限**如实报「已截断」**，
  没取到绑定的活动其花费**不摊给任何商品**。

## 文件清单

### 后端（reporting 服务）

- `backend/services/reporting/src/main/resources/db/migration/V394__product_performance_analysis.sql` —— **新增**。
  三张表（口径唯一表 / 日粒度事实 / 时点快照）+ 三个 DB 层不变量触发器 + RLS（ENABLE 且 FORCE）
  - GRANT + REVOKE flyway_schema_history。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProductPerformanceCatalog.java` —— **新增**。
  指标口径目录（标准份 + 区块顺序）。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProductPerformanceModels.java` —— **新增**。
  出入参模型（名字一律带 `Performance` / `ProductPerformance` 前缀 —— 兄弟模块已有同名记录，
  SpringDoc 用**简单类名**当 schema 名，同名时只能加序号消歧，序号取决于发现顺序 ⇒ 契约会漂）。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProductPerformancePort.java` —— **新增**。
  8 条跨服务**只读**端口（转发调用方自己的 bearer、**不传租户号**；上游读不到一律 503，
  绝不返回空集合冒充「上游没有数据」）。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProductPerformanceRefreshService.java` —— **新增**。
  刷新投影（写的是**投影**，不是业务事实）。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProductPerformanceService.java` —— **新增**。
  360 度分析（全部算术在服务端 `BigDecimal`）。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProductPerformanceController.java` —— **新增**。
  4 个端点，租户**不**来自任何请求头。
- `backend/services/reporting/src/test/java/com/starcon/reporting/ProductPerformanceIntegrationTest.java` —— **新增**。18 条业务口径。
- `backend/services/reporting/src/test/java/com/starcon/reporting/ProductPerformanceTenancyIntegrationTest.java` —— **新增**。3 条多租户。

### 契约（生成物，只读）

- `packages/api-contracts/src/reporting.openapi.json` —— 重新生成（4 path + 12 schema，**纯增量**）。
- `packages/api-contracts/src/contracts/reporting.ts` —— 重新生成（+12 行，**零删除**）。

### 前端

- `apps/web/src/app/analytics/products/performance/page.tsx` —— **新增**。路由 `/analytics/products/performance`。
- `apps/web/src/components/analytics/product-performance-model.ts` —— **新增**。纯函数展示层。
- `apps/web/src/components/analytics/product-performance-workspace.tsx` —— **新增**。真实工作区。
- `apps/web/src/components/analytics/product-performance-model.test.ts` —— **新增**。41 条。
- `apps/web/src/lib/api-proxy.ts` —— **修改**。**纯新增一条独立的 `services[]` 元素**（+42 / −0），
  既有条目一字未改、不重排、不删除。

### 台账

- `docs/product/capability-blueprint/module-progress.md` —— 第 423 行状态改「已交付」+ 补齐证据格。

## 验证

- L0 编译闸：`./mvnw -q compile` 全绿；`pnpm typecheck` 全绿；`pnpm --filter @starcon/web lint` 本模块无告警。
- L1 模块闸：`./mvnw -pl services/reporting -am test -Dtest='ProductPerformance*'
-Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` → **21 条全绿**。
  （**两个属性名都要带**，否则 `-am` 连带构建依赖模块时会报「No tests matching pattern ... were executed!」。）
- 前端单测：`pnpm vitest run apps/web/src/components/analytics/product-performance-model.test.ts` → **41 条全绿**。
- **共享白名单全量回归**：`pnpm vitest run apps/web/src/app/api/trade` → **71 文件 / 394 条全绿**。
- 契约核验：codegen 到临时目录 + `diff -r` 与仓库产物**逐字节一致**。

## 给下一轮的两条操作性提醒

1. **`api-proxy.ts` 是多车道共用的白名单累积文件** —— 取错一侧会**静默删掉别人已交付的路由**，
   症状是**页面 404**，而 typecheck / lint / vitest **全部全绿**。
   ⇒ 动了它就**必须**跑 `pnpm vitest run apps/web/src/app/api/trade`；提交前用
   `git diff --numstat -- apps/web/src/lib/api-proxy.ts` 确认**只增不减**。
   本车道实测 **+42 / −0**。

2. **`node tools/module-progress/sync.mjs` 的 ROOT 按脚本自身位置解析**
   （`ROOT = join(HERE, '..', '..')`）⇒ **必须在车道 worktree 内执行**。
   用集成头的脚本路径会作用到**集成头**，对车道台账无效（实测：在集成头跑过一次，
   因两侧台账本就一致而表现为无改动，`git status` 干净，未污染集成头）。
   改完台账那一行后重跑 sync，确认 `git diff` **只有统计格 + 你那一行**。
   ⚠️ 台账证据格里**不能出现 `|`** —— sync 的行正则用 `([^|\n]*)` 抓证据格，
   出现竖线会**静默截断**证据。

## 后续待办

- **14.3.1.3 商品维度广告效果**：本模块已备好 Listing 层广告花费与曝光 / 点击的事实，
  该模块可直接引用，不必再拉一遍上游；届时把 `PRODUCT_AD_ACOS` 的 `available` 翻成 true 即可。
- **14.3.2.1 广告归因**：交付后 `PRODUCT_AD_ROAS` 同理。
- **13.3.1.2 生命周期与分层**：输入是销量与毛利历史 ⇒ 本模块的日事实正好是它的输入。
- **13.3.2.1 流量转化**：需要区分自然与广告流量 ⇒ 本模块的广告活动 ↔ Listing 关联
  落在快照的 `text_value` 里，可被它引用。
- **13.4.1.1 多模式利润**：按经营模式拆利润时，本模块的成本法出库成本是其中一环，
  但**不等于**多模式利润（缺平台费 / 头程 / 物流费 / 广告费）。
- 若后续要让本模块支持**更多上游分页深度**，可调 `ReportingProductPerformancePort` 的
  `MAX_PAGES` / `MAX_AFTERSALE_DETAIL_FETCHES` / `MAX_CAMPAIGN_PRODUCT_FETCHES` 三个上限；
  触到上限时本模块**如实报「已截断」**，不会悄悄少算。
