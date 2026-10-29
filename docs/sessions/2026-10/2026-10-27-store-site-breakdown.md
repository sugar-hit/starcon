# 13.2.2.1 店铺与站点（阶段 D7，L3 锚点 13.2.2）

- **车道**：13.2.2.1 · 分支 `feat/13-2-2-1` · worktree `.worktrees/13.2.2.1`
- **蓝图**：`docs/product/capability-blueprint/13-profit-and-analytics.md:128`
- **服务**：reporting（13 卷唯一归属服务，端口 8088）· **迁移**：`V391__store_site_breakdown.sql`

## 决策摘要

1. **三个维度是同一棵树的三层**（平台 → 站点 → 店铺），**不是三套独立事实**。
   事实只落**店铺层**（`reporting_store_site_metric_fact`），站点 / 平台维度在**查询期**按
   `reporting_store_site_profile` 重分组。理由：把同一批订单投影三遍 ⇒ 一次刷新写三倍行，
   且三份行之间可能不一致（写了一半）—— 那是自找的「一个数三个真相」。
   ⇒ 站点维度键必须是 **`平台码:站点码`**（channel 的 `marketplace` 只在平台内唯一）。
2. **不消费 13.2.1.1 的 `reporting_sales_trend_fact` 作为金额来源**，改为自投影完整事实集
   （金额 / 销量 / 订单数），口径码一律 `STORE_SITE_` 前缀。理由：13.2.1.1 无站点维度、无店铺级销量，
   硬接会让占比 / 贡献度的口径分裂成两套。两边读的是**同一批上游订单**、**同源同口径**
   （9 订单的订单总额 + 平台单回挂店铺），差别只在维度树与增长贡献。
3. **站点维度有一条硬边界：订单不带站点**。站点归属只来自 channel 的 `shop_marketplace`
   ⇒ 店铺启用 1 个站点 = UNIQUE（可唯一归属）、≥2 个 = AMBIGUOUS、0 个 = NONE。
   归不了的部分**单列**成「无法唯一归属到站点」，**不摊给任何站点**、**不计入站点维度的占比分母**
   （摊给谁是造假）。归属状态用 `attribution_status` **钉进数据层**（DB 触发器校验站点码形状）。
4. **新店 / 老店按成交事实判定**：当前窗口有成交、**基期没有任何成交事实** ⇒ 新店。
   `site_enabled_at`（channel 的 `shop_marketplace.enabled_at`）只作**旁证** ——
   「站点开了但一直没出单」在经营上仍然是新店。
5. **增长贡献度 = 该维度的增量 ÷ 全部维度的增量之和**。总增量为**负**时贡献度**照样是负数**
   （它回答的是「这次掉的量里各家各掉多少」）；总增量为 0 时**算不出来**（null，**不是 0%**）。
   ⇒ 一次刷新必须投影「当前窗口 + 上一等长窗口」两个窗口，否则贡献度永远算不出来。
6. **币种缺失的订单不计入任何币种**（不默认 USD）：算进某个币种是编造；条数在刷新结果里如实报出。
7. **销量只存在于订单明细** ⇒ 必须逐单取（有上限 200 张/窗口）。触到上限**如实报 TRUNCATED**，
   并在报表上给出**明细覆盖率**（有明细的订单数 ÷ 订单数）；成交金额与订单数不受影响。
8. **绝不跨币种相加**：金额按币种分别报；只有与货币无关的量（件数 / 单数）给跨币种合计，
   币种显示为空串 —— **空串 = 与币种无关，不是「币种未知」**。
9. **「算不出来」与「等于 0」严格分开**：基期缺失 / 基期为 0 / 事实不存在，一律
   `null` + `NO_DATA` / `NOT_COMPARABLE`，页面显示「—」或「不可比」+ 原因。
10. **事实存精确时刻（timestamptz），分桶在查询期做** —— 与 13.2.1.1（V388）逐字同一纪律：
    半小时偏移的时区（+05:30 / +05:45）也能精确落到本地日，换时区不必重新投影。
    取数窗口两侧多留 1 天（`MARGIN_DAYS`）作时区余量。
11. **补上 reporting 的 Next 路由出口**（`apps/web/src/app/api/reporting/[...path]/route.ts`）：
    白名单（9 条 `reporting/` 元素）与前端工作区**早已就位**，但 `app/api/` 下**只有**
    catalog / channel / crm / finance / iam / platform / trade / warehouse 八个服务有
    `[...path]/route.ts` —— **reporting 一直没有** ⇒ 浏览器里 `/api/reporting/...` 在
    **Next 路由层**就 404，页面永远停在「读不到」的错误态；而白名单的双向断言是
    **直接调用 `proxy()`**（绕开 Next 路由）⇒ 两边都绿、页面却打不开。
    ⇒ 本车道补上出口（与 catalog / channel / finance / platform **同构**：GET + POST、
    `force-dynamic`、刻意不导出 DELETE / PUT —— reporting 是投影读模型，唯一写动作是 refresh），
    并在白名单测试里加一条**「Next 路由层有出口」**的断言把这个洞钉住。

## 关键 trade-off

- **另起一套事实表 vs 复用 13.2.1.1 的趋势事实**：复用要 ALTER（加维度列、加站点列、加店铺级销量口径），
  违反「既有表零 ALTER」纪律，且 13.2.1.1 的口径表里没有本模块的派生口径 ⇒ 另起一张表，
  但**口径码不重名**（`STORE_SITE_` 前缀），同一个数不会有两个真相。代价是同一批订单被投影两遍
  （两套口径各服务一个视角），换来的是**维度树完整**与**口径自洽**。
- **站点归属「单列」而不是「摊派」或「丢弃」**：摊派是造假，丢弃会让用户以为「这个站点就是没卖出去」。
  ⇒ 单列 + 常驻警告 + 明确写清「这一部分不在站点维度的占比分母里」。
- **销量逐单取（N+1）**：上游没有批量明细端点 ⇒ 只能逐单取，代价是慢；
  用**上限 + 如实报截断 + 覆盖率**兜住，**不悄悄少算**。
- **排序按销量倒序，不按金额倒序**：金额按币种分别报，跨币种比大小**不可比**；
  件数与货币无关 ⇒ 唯一可跨币种排序的量。
- **口径播种放在写事实的路径里**（而不是只在查询路径里）：事实表有「指标码必须已登记」的 DB 触发器，
  只在查询时播种的话，**第一次刷新**就会被触发器拒（新租户完全用不了）。
  ⇒ `writeFacts` 开头先 `seedDefinitions`（与 13.1.1.2 / 13.2.1.1 / 13.2.2.2 同纪律）。
- **前端不做业务算术**：占比 / 贡献度 / 汇总全部由服务端 `BigDecimal` 求值（定标 4 位），
  前端只做展示格式化 —— 前端算一遍就会引入二进制浮点误差，且让界面和服务端的数对不上。
- **`api-proxy.ts` 纯新增一条 `services[]` 元素**（不改既有条目、不重排）：本文件是多车道共用的累积型文件，
  插入锚点选在数组靠前段（`targets` 之后），与同批其它车道的插入点相隔很远，并入时 git 能干净求并。

## 文件清单

**后端（新增）**

- `backend/services/reporting/src/main/resources/db/migration/V391__store_site_breakdown.sql`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingStoreSiteCatalog.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingStoreSiteModels.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingStoreSitePort.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingStoreSiteRefreshService.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingStoreSiteService.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingStoreSiteController.java`
- `backend/services/reporting/src/test/java/com/starcon/reporting/StoreSiteBreakdownIntegrationTest.java`
- `backend/services/reporting/src/test/java/com/starcon/reporting/StoreSiteBreakdownTenancyIntegrationTest.java`

**前端（新增）**

- `apps/web/src/components/analytics/store-site-model.ts`
- `apps/web/src/components/analytics/store-site-model.test.ts`
- `apps/web/src/components/analytics/store-site-workspace.tsx`
- `apps/web/src/app/analytics/sales/store-site/page.tsx`
- `apps/web/src/app/api/reporting/store-site-allowlist.test.ts`
- `apps/web/src/app/api/reporting/[...path]/route.ts` —— **reporting 服务的浏览器出口**
  （此前**整个仓库从未有过**这个文件：白名单与工作区都就位，但浏览器里 `/api/reporting/*`
  在 Next 路由层就 404 ⇒ 13.1.1.1 / 13.2.1.1 / 13.2.2.2 等 reporting 页面此前都打不开数据）。
  与 catalog / channel / finance / platform 出口**同构**，严格只增不减。

**共用文件（纯新增 / 局部改动）**

- `apps/web/src/lib/api-proxy.ts` —— **纯新增一条** `reporting/`（store-site）元素，
  插在 `targets` 那条之后；既有条目**一字未改、未重排、未删除**。
- `packages/api-contracts/src/contracts/reporting.ts` + `packages/api-contracts/src/reporting.openapi.json`
  —— **codegen 产物**，由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成，
  **未手改**（added paths = 5，added schemas = 13，removed paths = 0，removed schemas = 0）。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 13.2.2.1 那一行的「状态」「证据」两格。

## 验证

- L0 编译闸：`./mvnw -q -o compile -pl services/reporting -am` + `./mvnw -o test-compile -pl services/reporting`
  —— 通过；`pnpm typecheck` —— **3 successful / 3 total**（@starcon/web 真实跑 `tsc --noEmit`）；
  `npx eslint`（本车道 **6 个**前端文件，含新增的 reporting 路由出口）—— 零告警。
- 契约闸：`./mvnw -o test -pl services/reporting -am -Dtest='OpenApiContractTest'` ——
  **Tests run: 1, Failures: 0, Errors: 0**，产出 `backend/target/openapi/reporting.openapi.json`
  （该测试不连库，**未新增 PG 容器**）。
- api-proxy 核对（实测，非目测）：`.tmp/api-proxy-zero-hit.mjs` 按 `resolve()` 同构口径实测全部既有元素 ——
  本模块 5 条路径对既有元素**全部零命中**，别的车道 42 条已交付形状**仍然全部命中**；
  `.tmp/api-proxy-element-count.mjs` 用 TypeScript AST 真实解析数组字面量：改前 248 元素 / 8 条 reporting，
  改后 **249 元素 / 9 条 reporting**，每元素均为对象字面量（结构完好）。
- L1 模块闸（后端 `StoreSiteBreakdown*` + 前端 vitest）：**本轮实跑，见下节「本轮补了什么」**。

## 本轮补了什么（第二轮：接手续做，主体已存在、但一个 commit 都没有）

上一轮写完主体实现后**会话被网络中断**（`ERR_CONNECTION_CLOSED`），改动全在工作区里、
**零 commit**。本轮把这份已有实现**补完、验证、提交、落地**，没有推倒重写。

### 🔴 本轮抓出并修掉的真问题：夹具的字符串形状伪装成「上游不可达」

**症状**：L1 第一次实跑 —— **15 例里 9 例红**（`StoreSiteBreakdownIntegrationTest` 全军覆没），
报的是 `503 ... trade-orders:current：trade 服务 上游不可达（是「读不到」，不是「没有数据」）`。
假上游明明好好地起着，channel 的调用也成功了（只有 trade 的 `/sales-orders/list` 挂）。

**根因**（`.tmp/Repro.java` 复现 + 逐层剥离后定位）：夹具用
`day.atTime(10, 0).atOffset(ZoneOffset.UTC).toString()` 造 `submittedAt`，
而 `OffsetDateTime.toString()` **在秒为 0 时省略秒字段** ⇒ 产出 `2026-10-26T10:00Z`
（**这正是上游 Jackson 实际会产出的形状**）。夹具自己的 `parseInstant` 却用 `Instant.parse`，
而 `Instant.parse` **要求秒字段** ⇒ `DateTimeParseException` 抛在 **HTTP handler 里**
⇒ JDK HttpServer **掐断连接、不回任何响应** ⇒ 客户端只看到「响应被截断」
（`ResourceAccessException`）⇒ 端口把它翻成 503「**上游不可达**」。
**真实原因藏在网络层下面**，症状与「上游真的挂了」一模一样 —— 这也是它值得单开一节的原因。

**修法**：让 `parseInstant` **两种形状都收**（先 `Instant.parse`，失败再
`OffsetDateTime.parse(...).toInstant()`）。**刻意没有**把夹具改成另一种形状 ——
夹具必须保持与上游 Jackson 实际产出的形状一致，否则这个洞下次换个夹具又会开一次。

> 教训写进代码注释了：这个异常发生在 handler 里 ⇒ 服务端掐断连接 ⇒ 客户端报「网络错误」
> ⇒ 端口报「上游不可达」⇒ **端点级错误信息永远看不到 handler 里的真实异常**。

### 🟡 本轮修掉的第二处：端点测试没钉窗口 ⇒ 新老店判定读到了错误的基期

`端点返回真实报表` 原来**不传 from / to** ⇒ 端点默认「最近 30 天」⇒ 那个窗口把**基期**
也圈了进去 ⇒ 基期不再是「没有任何成交事实」⇒ 三家店全被判成新店
（`newShopCount` = 3 而不是 1）。基期是**上一等长窗口**，只有把当前窗口钉成 `[day1, day2]`，
基期才落到 `[day1-2, day1-1]` —— 夹具里 shop-a / shop-b 的成交事实正好在那里。
现已钉死窗口，并补上 `requestedFrom` / `requestedTo` / `existingShopCount` 三条断言。

### 本轮实跑的闸（真实输出）

- **L0 编译闸**：`cd backend && ./mvnw -q compile` —— **EXIT=0**（无错误输出）；
  `pnpm typecheck` —— **3 successful / 3 total**（@starcon/web 真实跑 `tsc --noEmit`）；
  `npx eslint`（本车道 **7 个**前端文件 + `api-proxy.ts`）—— 零告警。
- **L1 后端模块闸**：
  `./mvnw -pl services/reporting -am test -Dtest='StoreSiteBreakdown*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  —— **Tests run: 15, Failures: 0, Errors: 0, Skipped: 0** / BUILD SUCCESS
  （`StoreSiteBreakdownIntegrationTest` 11 + `StoreSiteBreakdownTenancyIntegrationTest` 4）。
- **L1 前端模块闸**：`pnpm vitest run store-site-model.test.ts store-site-allowlist.test.ts`
  —— **Test Files 2 passed / Tests 31 passed**（model 24 + allowlist 7）。
- **全量 api allowlist 回归**（改了 `api-proxy.ts` 必须跑）：`pnpm vitest run apps/web/src/app/api`
  —— **Test Files 261 passed / Tests 1475 passed**，未误伤任何别的车道的 reporting 路由。
- **契约闸**：`./mvnw -pl services/reporting -am test -Dtest='OpenApiContractTest'`
  —— **Tests run: 1, Failures: 0, Errors: 0**（该测试不连库，**未新增 PG 容器**）；
  随后 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  重生成 —— **连跑两次输出逐字节一致**（md5 相同 ⇒ codegen 幂等），
  与工作区里已提交的契约**完全一致** ⇒ 证明它是 codegen 产物、**未手改**。
- **api-proxy 核对**（实测，非目测）：`.tmp/api-proxy-element-count.mjs` 用 **TypeScript 编译器
  真实求值数组字面量**（AST，不是文本计数）：改前 **248** 元素 / 8 条 `reporting/`，
  改后 **249** 元素 / 9 条 `reporting/` ⇒ **只 +1**；每元素均为对象字面量；
  8 条既有 `reporting/` 正则**逐字未动**（含 `targets` 与 `sales-trend`）。
  插入锚点在 `targets` 那条之后；同批另一条车道（13.1.2.2 达成追踪）插在 `sales-trend` 之后
  —— 两个锚点**相隔 3 个条目**，并入时 git 能干净求并（本轮实测：该车道的
  `reporting/target-attainment` **尚未并入集成头**，两个工作区都查不到 ⇒ 本车道无需保留它，
  只保证自己这条是**纯新增**、不删不改任何既有条目）。
- **PG 容器**：L1 期间**同时存活的 `postgres:16-alpine` 容器数 = 1**
  （两个测试类在同一个 surefire fork 里**顺序**跑，各自在 `@AfterAll` 里 `pg.stop()`）；
  跑完后 `docker ps -a` 里**只剩 ryuk 自身**，数秒后 ryuk 退出 ⇒ **无残留**。
- **台账**：`docs/product/capability-blueprint/module-progress.md` 第 421 行
  由「未开发」改为「**已交付**」并补齐证据格（**只改这一行**，diff = 1 insertion / 1 deletion）。

### 本轮 commit 粒度（4 个 atomic commit，不 push）

1. `feat(reporting)` —— 迁移 + 6 个 Java 主源 + 2 个集成测试；
2. `feat(web)` —— 页面 + 工作区 + 前端模型 + 前端测试 + reporting 的 Next 路由出口 + `api-proxy.ts`；
3. `feat(contracts)` —— codegen 重生成的契约 + 台账那一行；
4. `docs(sessions)` —— 本会话日志。


## 后续待办

- **13.1.1.2 多店对比**：本模块的 `reporting_store_site_profile` 是店铺 / 站点 / 平台的维度树，
  多店对比要按站点分组时可直接引用，**不需要**改它的表。
- **13.2.1.2 分时与分周模式**：本模块与 13.2.1.1 一样把**精确时刻**存事实（`bucket_start`），
  分时热力图直接在本表上按小时分桶即可，**不需要**改结构。
- **6.2.1 补货（销量预测输入）**：本模块的店铺级销量时间序列就是销量预测的输入，下游按 `bucket_start` 读即可。
- **上架入口**：页面在 `/analytics/sales/store-site`，**没有**挂进 13.2 的导航菜单
  （导航是跨车道共用文件，避免并入冲突）；13.2.2.2 的 `products` 页同样未挂。
- **站点级币种/时区是全局码表参数**，不是这家店实际成交用的币种（实际成交币种以订单为准）——
  两者可能不同，本模块已在能力声明里写明。
