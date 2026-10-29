# 2026-10-26 · batch-105 · 四车道交付（1.5.2.1 / 13.1.2.1 / 13.2.1.1 / 13.2.2.2）

> 本批由 2026-10-26 21:00 与 21:50 两段调度组成：21:00 那轮派发 13.2.1.1 / 13.2.2.2，
> 上一轮（19:00）派发的 1.5.2.1 / 13.1.2.1 在本批内落地，**四条车道一次并入、一次 L2**。
> 台账：**410 → 414 已交付**（465 总数，未开发 51，完成率 89.0%）。

---

## 模块 1.5.2.1 服务与任务状态

**这个功能做什么**：把分散在各系统的同步任务、失败任务、采集/迁移任务汇总到一页，
让运营一眼看出「哪些任务卡住了、哪些失败了、失败是系统故障还是业务数据问题」，
并能直接跳到对应任务。它是**只读看板**，不产生任何业务事实。

**技术落地**：

- 迁移：**零迁移** —— V386 预分配但**主动不占用**。蓝图卷 01 红线写死「不产生新的业务事实，只聚合各系统的投影」
  「工作台只读 + 跳转」，五类任务来源的事实全部落在 channel 既有表上
  （`sync_run` / `sync_failure` / `backfill_task` / `listing_migration_batch` / `publish_task` / `listing_collect_task`），
  落一张派生表只会比它描述的事实**更早过期**（沿 1.5.1.1 不占 V338、1.5.1.2 不占 V375 的先例）。
- 端点：`GET /channel/service-task-status/` 下 **6 个** ——
  `capabilities`（能力声明：逐条列出接通与未接通的来源）· `options`（筛选器取值）·
  `summary`（总览计数）· `tasks`（列表，真分页）· `tasks/{id}`（单任务详情）· `alerts`（停滞与失败告警）。
  权限码复用 `channel.sync.read`，identity 零改动。
- 页面：`/system-health/tasks`（`apps/web/src/app/system-health/tasks/page.tsx` +
  `components/system-health/service-task-status-{model.ts,model.test.ts,workspace.tsx}`）——
  真实工作区，含加载态/错误态/空态三态；严重度筛选留在前端（与 1.5.1.1 一致），后端只筛来源/状态/触发源/停滞/失败/关键字。
- 测试：`ServiceTaskStatusRulesTest` 16 + `ServiceTaskStatusIntegrationTest` 20 +
  `ServiceTaskStatusTenancyIntegrationTest` 3 = **39 后端例**（三租户断言齐全）；
  前端 model 19 + allowlist 5 = **24 例**；因改 `api-proxy.ts`，全量 allowlist 回归 **257 文件 / 1450 例全绿**。

**关键设计决策**：

1. **停滞判定用 SQL 里的 `now()`**，不用应用层墙钟 —— 时钟漂移会让「卡住」变成假象；
   阈值 30 分钟（黄）/ 120 分钟（红）进代码常量并由能力声明公布，可用 Spring 属性覆盖。
2. **「判不了」是一等状态**：在跑但来源表没记开始时刻 ⇒ `STALL_UNDETERMINED`，
   **不**折成「没卡住」。`listing_migration_batch` / `listing_collect_task` 的状态取值域里**没有「执行中」**
   ⇒ 它们判不了停滞；`listing_migration_batch` **没有耗时列** ⇒ 耗时一律 null
   （拿 `created_at` 冒充执行时长是错的）。
3. **五来源注册表同时生成 SQL 与能力声明**（`ServiceTaskStatusSources`）⇒ SQL 与文档不可能对不上。
4. **「系统故障 vs 业务数据问题」落在字段上**：`sync_failure.reason` 六值词表 ⇒ `failureClass` 六档；
   只有 `sync_run` 能分类，其余来源**没有词表** ⇒ 一律 `UNCLASSIFIED`，**不替它们编一套分类**。
5. **跨服务边界一条都不越**：finance/crm/warehouse/trade/platform 的任务表不是本模块的数据，
   没有一条跨库 SQL；卷 18 未开发 ⇒ 能力声明里如实列「未接通」，**不造表顶替**。

---

## 模块 13.1.2.1 目标设定

**这个功能做什么**：给部门、人员、产品、店铺设定销售额、毛利额、毛利率、广告 ROI、库存周转等
经营目标与周期，并把它分解到月/周；设定时能看到历史基线参考。

**技术落地**：

- 迁移：`backend/services/reporting/src/main/resources/db/migration/V387__target_setting.sql`
  —— **两张表、纯 CREATE TABLE、零 ALTER 既有表、零跨库外键**：
  `reporting_target`（目标记录：引用**已登记的口径码** + 目标值 + 币种 + 周期 + 归属对象 + 分解方式 +
  归属对象**快照**）与 `reporting_target_bucket`（分解项）；
  `tenant_id NOT NULL` + ENABLE **且** FORCE RLS + GRANT；5 个触发器（口径码引用校验、
  分解合计校验、分解项非空校验、维度与口径表匹配校验等）。
- 端点：`/reporting/targets` 下 **11 个端点 / 8 条 path**（列表、详情、创建、修改、删除、分解预览、
  口径目录、能力声明、历史基线、归属对象选项等），权限码 `reporting.targets.read` / `reporting.targets.write`。
- 页面：`/analytics/dashboard/targets`（`apps/web/src/app/analytics/dashboard/targets/page.tsx` +
  `components/analytics/target-setting-{model.ts,model.test.ts,workspace.tsx}`）——
  真实工作区（列表 / 设定 / 预览 / 详情 / 能力声明），三态齐备。
- 测试：`TargetSettingIntegrationTest` 38 + `TargetSettingTenancyIntegrationTest` 5 = **43 后端例**；
  前端 model 37 + allowlist 6 = **43 例**。

**关键设计决策**：

1. **目标可写，而 reporting 其它表只写投影** —— 「这个部门这季度要做到 100 万」不是事件，
   没有任何上游会推它 ⇒ 允许写，但写进去的只是一张**计划**，不产生业务事实（不产生订单、不改库存、不动钱）。
   迁移注释里写明这条与本服务其它表的纪律**刻意不同**的理由。
2. **组织对象走授权只读 API**（identity 部门/人员、catalog 产品），**不造一张组织表顶替**；
   服务端回查权威来源并抄 `owner_code` / `owner_name` 快照 ⇒ 组织改名后历史目标仍认得出是谁。
3. **分解保证落在 DB 触发器**：提交时核对「分解项合计 = 目标值」且「至少一个分解项」——
   「均分后合计 ≠ 目标值」是浮点加法最常见的坑，不靠服务层自觉。
4. **余数归属有口径**：EVEN → 差额归最后一个分解项；WEIGHTED → 最大余额法（小数部分相同时归后面的分解项），
   归属写进响应 `remainderNote`。
5. **没有口径码就如实报「未登记」**：广告 ROI 在店铺维度用已登记的 `STORE_AD_ROAS`，
   组织维度**如实报未登记**并归属 14.3.1.1；库存周转任何维度**如实报未登记**（拿 `INVENTORY_VALUE` 冒充周转率是换一个指标冒充另一个）。
6. **店铺维度销售额给三个并列口径** `STORE_GMV_PLATFORM` / `STORE_GMV_RESTORED` / `STORE_CALIBER_GAP`，由用户自选 ——
   取其一改名叫「销售额」等于抹掉口径差异。
7. **产品维度做不做**：任务书说「蓝图未点名」，但蓝图**职责句**明写「按部门/人员/**产品**/店铺设定」
   ⇒ 做。已知缺口：产品维度实际值尚未投影 ⇒ 下游 13.1.2.2 会报「不可得」（本模块如实列出）。

---

## 模块 13.2.1.1 销量趋势

**这个功能做什么**：按日/周/月与小时维度查看销量与销售额趋势，支持同比/环比/店铺间/产品间多维对比，
并能把异常日期（大促、秒杀、缺货、断货）标注在曲线上 —— 让「涨了/跌了」变成「为什么涨了/跌了」。

**技术落地**：

- 迁移：`backend/services/reporting/src/main/resources/db/migration/V388__sales_trend.sql`
  —— **三张表、纯 CREATE TABLE、零 ALTER 既有表、零跨库外键**：
  `reporting_sales_metric_definition`（销量趋势的**指标口径唯一表**：UNIQUE(tenant_id, metric_code) +
  `dimension_type` 列 —— **维度本身是口径的一部分**；触发器拒绝未登记的 `base_metric_code`）·
  `reporting_sales_trend_fact`（时序投影事实；触发器拒绝未登记指标码**与维度不匹配**）·
  `reporting_sales_event`（异常事件标注：PROMOTION / FLASH_SALE / STOCKOUT / SHORTAGE，可选精确 `start_at`/`end_at`）；
  `tenant_id NOT NULL` + ENABLE **且** FORCE RLS + GRANT；金额 `numeric(24,4)`、数量 `numeric(19,6)`。
- 端点：`GET /reporting/sales-trend/` 下 **6 个** ——
  `sales-trend`（趋势点列 + 同比/环比）· `comparison`（多维对比）· `events`（异常标注）·
  `capabilities`（能力声明）· `metrics`（口径目录）· `POST /refresh`（投影摄取）。
- 页面：`/analytics/sales`（`apps/web/src/app/analytics/sales/page.tsx` +
  `components/analytics/sales-trend-{model.ts,model.test.ts,workspace.tsx}`）—— 真实工作区，三态齐备，
  数据更新时间常驻。
- 测试：`SalesTrendIntegrationTest` 23 + `SalesTrendTenancyIntegrationTest` 5 = **28 后端例**；
  前端 **35 例**；全量 allowlist 回归 **257 文件 / 1451 例全绿**。

**关键设计决策**：

1. **`bucket_start` 存精确时刻**，分桶在查询期做（`date_trunc(... AT TIME ZONE tz)`）——
   存桶就锁死了时区口径，跨时区店铺无法按各自时区统计。
2. **公司维度的口径引用 13.1.1.1 的 `reporting_metric_definition`，不重定义**；本表只登记 13.1.1.1 没有的
   （STORE/PRODUCT 维度口径 + PERIOD_OVER_PERIOD / YEAR_OVER_YEAR 对比口径，携带引用而非公式）。
3. **投影同时覆盖三个窗口**（本期 / 上期**等桶数** / 去年同期）：没有基期就无法算增长；
   上期按**桶数**对齐而不是按天数对齐（月粒度下必须比较相同数量的月份桶）。
4. **三个维度不可相加**（产品维度用订单**行**金额）⇒ 这一点写进口径说明、能力声明与页面提示，
   而不是让人自己发现数字对不上。
5. **如实声明未交付项**：按店铺时区分桶未实现（属 13.2.1.2，店铺时区在 channel 店铺档案里，本模块不读）；
   「价格调整」异常事件无上游来源（价格变更属定价服务）；店铺维度**没有数量指标**
   （平台订单不带 SKU/数量）⇒ 不登记指标码，**不编公式、不编分母**。
6. **订单明细每窗口上限 50 张**（上游无批量端点）⇒ 超限**报 `TRUNCATED`**，不悄悄少算。

---

## 模块 13.2.2.2 SKU/SPU/Listing

**这个功能做什么**：按商品维度拆解销量 —— SKU/SPU/Listing 三层看，支持把同一 SPU 在多平台的多个 SKU
合并看（SPU 汇总）与变体销量分布，回答「这个商品整体卖得怎么样、哪个变体最好卖」。

**技术落地**：

- 迁移：`backend/services/reporting/src/main/resources/db/migration/V389__product_sales.sql`
  —— **五张表、纯 CREATE TABLE、零 ALTER 既有表、零跨库外键**：
  `reporting_product_metric_definition`（商品维度**指标口径唯一表**）·
  `reporting_product_sales_fact`（商品维度销量事实）·
  `reporting_product_variant_fact`（变体分布）·
  `reporting_product_drilldown_fact`（层级钻取明细，含订单时刻 `timestamptz`）·
  以及配套口径/事件表；`tenant_id NOT NULL` + ENABLE **且** FORCE RLS + GRANT。
- 端点：`GET /reporting/product-sales/` 下 **7 个** ——
  `/`（三层报表 + 真分页真计数 + `mergeSpu`）· `/spu-summary`（SPU 汇总）· `/variants`（变体分布）·
  `/drilldown`（层级钻取）· `/capabilities`（能力声明）· `/metrics`（口径目录）· `POST /refresh`（投影摄取）。
- 页面：`/analytics/sales/products`（`apps/web/src/app/analytics/sales/products/page.tsx` +
  `components/analytics/product-sales-{model.ts,model.test.ts,workspace.tsx}`）—— 真实工作区，三态齐备。
- 测试：`ProductSalesIntegrationTest` 20 + `ProductSalesTenancyIntegrationTest` 5 = **25 后端例**；
  前端 model 29 + allowlist 6 = **35 例**；额外跑了整个 reporting 服务 **108 例全绿**（确认新增 bean 未打破既有模块）。

**关键设计决策**：

1. **Listing 层的销量归属有边界**：trade 订单明细只带 SKU 码、**不带平台商品 id**
   ⇒ 同一 SKU 挂多个 Listing 时销量**无法唯一归属** ⇒ **不摊给任何 Listing**，
   单列「无法唯一归属」，并在能力声明写清原因（摊派会造出一个看起来精确的假数）。
2. **变体销量占比是唯一的跨层口径**（分子 SKU 层 / 分母 SPU 层）：
   靠 `numerator/denominator_dimension_type` 显式声明，否则引擎会算出「SPU 销量 ÷ SPU 销量 = 100%」
   这种看起来对、其实没意义的数。
3. **绝不跨币种相加**：DB CHECK 钉死「涉币指标 ⇒ `currency_scope='SAME'`」；
   只有件数/单数给跨币种合计（币种空串表示**与币种无关**，不是「币种未知」）。
4. **另起 5 张表 + 另起 Port**：13.1.1.1 的 `reporting_drilldown_fact` 无商品维度，
   且该文件「零 ALTER」纪律在身；**不修改共享端口**以避开与并行车道的合并冲突。

---

## 本批并入记录

### 台账

`sync.mjs`：**410 → 414 已交付**（465 总数、进行中 0、未开发 **51**、完成率 **89.0%**）；
`module-progress.md` 465 行、无重号（并入时由 merge-union 驱动器按模块 ID 求并集折叠）。

### 静态预检（互相破坏形状扫描，L2 之前）

对本批 3 个新迁移（V387 / V388 / V389）逐条扫：

| 形状                        | 结果                                                                                             |
| --------------------------- | ------------------------------------------------------------------------------------------------ |
| ① `ADD COLUMN` 无 `DEFAULT` | **无命中**                                                                                       |
| ② `SET NOT NULL`            | **无命中**                                                                                       |
| ③ 新增 CHECK 约束           | 无命中（口径/币种/维度约束走**表级 CHECK + 触发器**，不 ALTER 既有表）                           |
| ④ ALTER 既有表              | 每文件 2 处，均为 `ALTER TABLE %I ENABLE/FORCE ROW LEVEL SECURITY`（**RLS 开关，非表结构变更**） |
| ⑤ 跨库外键                  | 仅 1 处 `REFERENCES reporting_target(tenant_id, id)` —— **同库同服务内**，非跨服务 FK            |
| ⑥ Flyway 版本号             | 每个服务内部**无重号**；V386 主动不占用                                                          |

### 并入冲突：唯一一处，已机械求解

`apps/web/src/lib/api-proxy.ts` 报 add/add 冲突 —— **1.5.2.1**（`channel/` 元素，49 行）与
**13.2.2.2**（`reporting/` 元素，38 行）**都插在基线的同一行**（base 12565 之后，即数组末尾）。

- **成因**：上一轮派发的 1.5.2.1 与本轮派发的 13.2.2.2 各自独立选了「数组末尾」这个约定锚点；
  本轮给 13.2.1.1 / 13.2.2.2 指派锚点时**无法预知上一轮车道的选择**。
- **解法**：按仓库既有惯例「累积型文件求并」—— **两块都保留**（顺序 1.5.2.1 → 13.2.2.2）。
  两条元素前缀互不相交（`channel/` vs `reporting/`），`resolve()` 先按 prefix 闸门筛再测正则，**先后顺序不影响匹配结果**。
- **校验**：相对 merge-base **零删除行**（只增不删）、`services[]` 元素数 238 → 239（+1）、
  `(prefix, pattern)` 组合**零重复**、无残留冲突标记、`prettier` 解析通过。

> 🔴 **⚠️ 上面这条校验结论已被 L2 第 1 跑推翻，2026-10-27 更正 —— 保留原文以便追溯：**
> 「`(prefix, pattern)` 组合零重复」**查的是正则内容**，而实际重复的是**对象键本身**
> （`prefix` / `env` / `fallback` / `pattern` 各两份）⇒ 那条校验查不到它。
> 「元素数 238 → 239（+1）」也印证了损坏形态 —— 但**连这个数字本身也是错的**：
> 修复车道用 **TypeScript 编译器真实求值数组**实测（不是文本计数）：
> **修复前 247 个元素**（两条并成一条）**→ 修复后 248 个元素**，每个元素恰好一份
> `prefix`/`env`/`fallback`/`pattern`、重复 `(prefix, pattern)` 组合 **0** 条。
> 上一轮那个「238 → 239」大概是**按行数文本猜的**，与实际不符。
> 教训：**校验元素数要靠编译器求值，不要用文本计数猜**；并且校验要覆盖**对象键**而不只是正则内容。
> 详见下面「L2 批次闸 → 缺陷 ①」。

- 合并后 `merge-ready` 复跑一次，把 13.2.2.2 补记为 `merged`（脚本自带的「解决并提交后自动补记」路径）。

> **给下一轮的提醒**：`api-proxy.ts` 的「数组末尾」这个锚点**不是独占的** ——
> 多条车道各自选它就会撞。建议后续派发时**显式按车道指派互不相邻的锚点**，
> 或干脆统一改用「按服务前缀分组的锚点」。

## L2 批次闸

### 第 1 跑（2026-10-27 02:02）——🔴 **红**，且是**真实缺陷**（不是假红）

这一跑是本批 4 条车道并入后**第一次**真正的全量验证 —— 上一轮（2026-10-26 21:00 / 21:50 两段调度）
写完这份说明的「并入记录」与「静态预检」之后**会话中断**，`<!--L2_SECTION-->` 占位符没被填上，
**L2 从未真正跑完，本批也从未被 commit / push**（这一轮补上）。

命令与真实结果：

| 步骤                  | 命令                                                         | 结果                                                                                                                              |
| --------------------- | ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| 后端全量              | `cd backend && ./mvnw verify`                                | 🔴 **exit 1** —— identity 服务 639 例中 1 失败，**Maven fail-fast，模块 3/11 就停**                                               |
| 前端类型检查          | `pnpm typecheck`                                             | 🔴 **exit 1** —— `api-proxy.ts(12746,5): error TS1117`                                                                            |
| 前端 lint             | `pnpm lint`                                                  | ✅ **exit 0**                                                                                                                     |
| 前端单测              | `pnpm test`                                                  | 🔴 **exit 1** —— `Test Files 1 failed \| 653 passed (654)`，`Tests 1 failed \| 9140 passed (9141)`                                |
| Next production build | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 🔴 **exit 1** —— `✓ Compiled successfully` 后 `Type error: An object literal cannot have multiple properties with the same name.` |
| 台账校验              | `node tools/module-progress/sync.mjs --check`                | ✅ **exit 0**（465 行、0 重号）                                                                                                   |

**两个独立问题，一个批次造成、一个早于本批存在。**

### 缺陷 ①：`api-proxy.ts` 服务元素字面量被并成一块 —— **本批造成**，前端三条全红

`apps/web/src/lib/api-proxy.ts` 的 `services[]` 里，**两条服务元素被并进了同一个对象字面量**：

| 元素                          | 归属模块                 | 行号        | 属性                                                                            |
| ----------------------------- | ------------------------ | ----------- | ------------------------------------------------------------------------------- |
| channel `service-task-status` | 1.5.2.1 服务与任务状态   | 12704–12709 | `prefix:'channel/'` / `env:'CHANNEL_URL'` / `pattern:/^service\-task\-status…/` |
| reporting `product-sales`     | 13.2.2.2 SKU/SPU/Listing | 12743–12747 | `prefix:'reporting/'` / `env:'REPORTING_URL'` / `pattern:/^product\-sales…/`    |

两者之间**少了 `},` + `{` 的分隔**（第 12709 行之后、第 12710 行 `// 13.2.2.2 SKU/SPU/Listing…` 注释之前）。

**成因**：本批并入时 `api-proxy.ts` 报了 add/add 冲突 —— 1.5.2.1 与 13.2.2.2 各自独立选了
「数组末尾」这个约定锚点，于是都插在基线同一行之后。解冲突时按「累积型文件求并、两块都保留」处理，
**保留了两块的注释与属性，却漏掉了分隔它们的对象边界**。

**后果**（两条都已实测复现）：

1. 一个对象字面量里有**两份** `prefix` / `env` / `fallback` / `pattern` ⇒
   `TS1117: An object literal cannot have multiple properties with the same name` ⇒ **typecheck 红 + Next build 红**。
2. JS 运行时重复键**取最后一个** ⇒ **channel 那条 `service-task-status` 白名单被静默吞掉** ⇒
   `channel/service-task-status/alerts` 实际 404 ⇒
   `apps/web/src/app/api/channel/service-task-status-allowlist.test.ts` 失败：
   `AssertionError: channel/service-task-status/alerts 未被放行（白名单漏登记 = 浏览器里静默 404）: expected 404 to be 200`。

⚠️ **这正是 PIPELINE.md 里点名的那条事故形状**：「取错一侧会静默删掉别人已交付的路由」。
本次它**同时**被 typecheck 与 allowlist 测试抓到 —— 因为丢的是**分隔符**（语法级），
不是像「取错一侧」那样语法合法而语义丢失。若丢的是整段（语法也合法），typecheck 就会**全绿**而路由静默消失。

**上一轮那份并入记录里的校验结论要更正**：「`services[]` 元素数 238 → 239（+1）」、
「零删除行（只增不删）」这两条**在坏掉的状态上就是错的** —— 两条元素并成一条后元素数是 **247**
（修复车道用 TS 编译器求值实测），补回分隔符后是 **248**。

### 缺陷 ②：审批待办超时断言跨时钟取整抖动 —— **早于本批存在**，与本批无关

```
[ERROR] com.starcon.identity.ApprovalTodoIntegrationTest.超时判定用节点上配的时限且没配时限的单据判不了超时
java.lang.AssertionError: Expecting actual: 7199L to be greater than or equal to: 7200L
  at ApprovalTodoIntegrationTest.超时判定用节点上配的时限且没配时限的单据判不了超时(ApprovalTodoIntegrationTest.java:208)
```

**与本批无关的取证**：本批 4 条车道的改动面是 `backend/services/reporting`(29) + `backend/services/channel`(8)

- `apps/web/src`(21) + `packages/api-contracts/src`(4) + 文档(5)，
  **`backend/services/identity` 零改动**（`git diff --name-only` 过滤 `services/identity` 命中数 = 0）；
  该测试文件最后一次改动是 `b2ec6b97 feat(identity): 1.1.1.3 审批待办只读端点`，早于本批。

**隔离复跑 3 次全绿**（`Tests run: 12, Failures: 0` × 3）⇒ 只在全量验证的高负载下偶发 ⇒ **抖动，不是回归**。

**根因**：超时/停留秒数是**在 DB 侧算的**（`V160__my_approval_inbox.sql`）：

```sql
CREATE FUNCTION approval_dwell_seconds(clock timestamptz, entered timestamptz)
RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN entered IS NULL THEN 0 WHEN clock <= entered THEN 0
    ELSE floor(extract(epoch FROM (clock - entered)))::bigint END $$;
```

调用方传的 `clock` 是 **PG 的 `now()`**，而测试夹具把 `node_entered_at` 写成 **JVM 墙钟**的
`OffsetDateTime.now().minusHours(3)` ⇒ **两端分属两个钟域**。两个时钟只要有**不足 1 秒**的偏差
（Docker Desktop 的 VM 时钟与宿主不同步），断言就正好压在 `floor()` 的取整边界上：
真实时长 10800.0s 落成 **10799** ⇒ `overdueSeconds = 10799 - 3600 = 7199`。

**仓库既有先例**：commit `371246c6 fix(test): 异常订单队列的停留时长断言不再依赖「类加载那一刻」的基准时刻`
（模块 1.1.1.2，已并入集成头）修的是**同一族**问题（停留时长断言的时钟锚点漂移），
其要义是「**在服务端判定所用的那个钟域里比**」，并明确记了一条教训：
「两个钟域各管一段时，只换一端反而制造新的跨钟域依赖」。

### 处置：两条修复车道

| 车道                              | 修什么                                                      | 归属   |
| --------------------------------- | ----------------------------------------------------------- | ------ |
| `fix-batch-105-api-proxy-literal` | 补回 `},` + `{` 分隔 ⇒ 拆回两个独立元素（元素数 238 → 240） | 缺陷 ① |
| `fix-1.1.1.3-approval-todo-clock` | 夹具改用 **DB 时钟**（与生产同钟域），断言一条不删不弱化    | 缺陷 ② |

两条都是**修复车道**（合成 ID，不占台账模块号，不改台账），修复后**重跑 L2**，绿了才 commit + push。

> ⚠️ **Maven fail-fast 的连带影响**：后端 verify 在 identity（第 3/11 个模块）就停了，
> **catalog / warehouse / trade / channel / crm / reporting 六个模块的后端测试本轮根本没跑到** ——
> 也就是说，**本批 4 条车道自己的后端代码在这一跑里尚未被验证过**。
> 第 2 跑必须把这六个模块全部跑到，才是真正对 batch-105 的验收。

### 第 2 跑（2026-10-27 02:36）——🔴 **前端全绿，后端又冒出一个**早于本批的**抖动

两条修复车道落地并入后重跑（`7b8838a0` api-proxy + `0ac6dab9` identity）：

| 步骤                  | 命令                                                         | 结果                                                                                       |
| --------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------------------------ |
| 后端全量              | `cd backend && ./mvnw verify`                                | 🔴 **exit 1** —— **跑到了第 6/11 个模块**（warehouse），**越过了第 1 跑的失败点 identity** |
| 前端类型检查          | `pnpm typecheck`                                             | ✅ **exit 0** —— **TS1117 消失**                                                           |
| 前端 lint             | `pnpm lint`                                                  | ✅ **exit 0**                                                                              |
| 前端单测              | `pnpm test`                                                  | ✅ **exit 0** —— **654 文件 / 9141 例全过**（第 1 跑是 1 failed / 9140 passed）            |
| Next production build | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ **exit 0** —— `✓ Compiled successfully`                                                 |
| 台账校验              | `node tools/module-progress/sync.mjs --check`                | ✅ **exit 0**                                                                              |

**⇒ 缺陷 ①（api-proxy.ts）已完全修复并验证**：
typecheck 的 TS1117 消失、channel 的 `service-task-status` 白名单回来了（allowlist 全部通过）、
Next build 恢复、9141 个前端用例全绿。
修复车道自己的证据：`pnpm vitest run apps/web/src/app/api` → **260 文件 / 1468 例全绿**；
用 TypeScript 编译器真实求值数组复核 → 修复前 **247** 个元素（channel 那条被吞）→ 修复后 **248** 个元素，
每个元素恰好一份 `prefix`/`env`/`fallback`/`pattern`。

**⇒ 缺陷 ②（identity 超时断言）已修复**：L2 第 2 跑**越过了 identity**（第 1 跑就是在这里 fail-fast 停的）。

**⇒ 第 1 跑没验到的模块，这跑验到了**：identity ✓、catalog ✓、channel ✓、warehouse ✓（1672 例，1 失败）。
**但 trade / crm / reporting 仍然没跑到** —— Maven fail-fast 在 warehouse 又停了。

**第 2 跑的唯一红点：warehouse `StockOwnershipIntegrationTest.货权漂移能被检出且不自动改存量`**

```
[ERROR] com.starcon.warehouse.StockOwnershipIntegrationTest.货权漂移能被检出且不自动改存量
java.lang.AssertionError: Expected size: 1 but was: 0 in: []
```

- **与本批无关**：本批只动 `reporting` / `channel` / `apps/web`，**`backend/services/warehouse` 零改动**；
  该测试与生产代码最后一次改动都是 `c23cc3bc feat(warehouse): 7.7.1.1 货主与所有权`（远早于本批）。
- **隔离复跑 3 次全绿**（`Tests run: 40, Failures: 0` × 3）⇒ **抖动，不是逻辑回归**。
- **它之所以第 1 跑没暴露**：第 1 跑在 identity 就 fail-fast 停了，**warehouse 根本没跑到** ——
  这是组批协议的又一处价值：**第一次真正跑到这一层，才把潜伏的抖动照出来**。

**两条抖动其实是同一族**：一个（identity）断言跨了「JVM 钟域 vs PG 钟域」，一个（warehouse）
货权档的 `effective_from` 写在 JVM 钟域、而解析判定用 PG 的 `now()`。

**宿主钟与容器钟的偏差（两种测法，结论不同 —— 值得记一笔）**：

| 测法                                                       | 测得偏差                                                          | 能否解释本次红                                           |
| ---------------------------------------------------------- | ----------------------------------------------------------------- | -------------------------------------------------------- |
| 调度者：另起一个**全新**容器与宿主对拍                     | 容器钟**领先**宿主 **+48 ~ +67 ms**（15 次 / 45 秒）              | ❌ 方向相反，解释不了                                    |
| 修复车道：在**真正的 Testcontainers 容器内**、并在加载下测 | 容器钟**落后**宿主 **0.35~1.0 ms**（空载）/**1.9~4.2 ms**（加载） | ✅ 且**在加载下复现了失败**（resolve 返回 NULL 3/60 次） |

⇒ **偏移量与容器「活多久」有关** —— 刚起的容器时钟刚被同步过，偏差看起来很大；
跑了几十分钟的容器偏差缩到**毫秒级**，而毫秒级偏差正好够把一个 `<=` 边界翻掉。
⇒ **教训：测时钟偏差必须在「真实的那个容器、真实的时间点」上测**，另起一个容器测出来的数不能拿来解释既有失败。

### 第 3 跑

见下方「第 3 跑」小节（warehouse 修复落地后补记）。

## 后续待办

1. **13.2.2.2 的产品维度实际值**尚未投影（见 13.1.2.1 同款缺口）⇒ 13.1.2.2 达成追踪在产品维度会报「不可得」。
2. **卷 13 子页面没有导航入口** —— 四个已交付的分析页在界面上互相不可达，
   已登记 backlog：`docs/backlog/2026-10-2026-10-26-analytics-section-no-navigation-entry.md`。
3. **1.5.2.1 的规则任务来源**待卷 18 交付后接入（只需在 `ServiceTaskStatusSources` 里加一条来源描述，
   SQL 与能力声明都由注册表生成，不用改查询层）。
4. **13.2.1.1 的按店铺时区分桶**属 13.2.1.2；**Listing 层归属**在 trade 提供平台商品 id 前无法唯一归属。
