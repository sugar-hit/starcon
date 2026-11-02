# 2026-11-03: 18.4.1.2 智能查询（受控语义层 + 跨服务委托执行）

## 摘要

交付能力蓝图卷 18 L3 18.4.1「智能问答」下的第二格 **18.4.1.2 智能查询**：
把一句中文变成**受控的查询条件**（筛选 / 聚合 / 排序），生成报表配置草稿，
支持把一次查询固化成常用查询。后端落在 **platform 服务**（V441 四表 + jOOQ 显式 SQL），
执行面**委托**下游 13.6.1.1 自定义报表的查询引擎（跨服务只走授权 HTTP API），
前端是真实工作区页 `/platform/smart-query`（四个页签 + 加载 / 空 / 错误三态）。

🔴 蓝图加粗句「**自然语言只映射到受控语义层指标，不生成任意 SQL**」是本模块的
物理边界，落法见下面「加粗句怎么落的」一节。

> ⚠️ 本条车道**上一任 worker 会话被网络错误掐断**（`net::ERR_CONNECTION_CLOSED`），
> 产出全部留在 worktree 里未提交。本会话接手的是**半成品**：后端 5 个 Java 文件 +
> 2 份迁移 + 3 个测试类已存在且能编译，但**测试夹具有三处 bug** 导致 L1 六红。
> 本会话**没有重写已有代码**，只做了三处夹具修复 + 前端 + 契约 + 台账 + 会话日志。

## 加粗句怎么落的：「自然语言只映射到受控语义层指标，不生成任意 SQL」

这句话要成立，得同时堵住四个口子。逐个说清落点：

### 1. 封闭词表长什么样 —— `SmartQuerySemanticCatalog`

一个 `final class`，全部内容是**静态常量**，没有任何查询、没有任何 IO：

| 词表 | 条数 | 内容 |
| --- | --- | --- |
| 指标 `METRICS` | 12 | `SALES_AMOUNT` / `ORDER_COUNT` / `UNIT_SOLD` / `COGS_AMOUNT` / `GROSS_PROFIT` / `GROSS_MARGIN` / `AD_SPEND` / `RETURNED_AMOUNT` / `RETURN_RATE` / `NET_PROFIT` / `INVENTORY_VALUE` / `RECEIVABLE_BALANCE` |
| 维度 `DIMENSIONS` | 5 | `SCOPE_TYPE` / `LEGAL_ENTITY` / `SHOP` / `BUCKET_DATE` / `CURRENCY`（与 13.6.1.1 的白名单**逐字一致**） |
| 运算符 | 5 | `EQ` / `IN` / `GTE` / `LTE` / `BETWEEN`（13.6.1.1 同一套） |
| 币种码 | 5 | `USD` / `EUR` / `CNY` / `GBP` / `JPY` |
| 时间窗口 | 4 | `LAST_7_DAYS` / `LAST_30_DAYS` / `LAST_WEEK` / `THIS_MONTH` |

每个指标带：码 / 名 / 单位 / **聚合方式（照抄 13.1.1.1 口径表登记的那个，本模块不自己定）**/
是否涉币 / **强关键词** / **弱关键词** / 取数服务 / 取数端点 / **口径说明** / 明细路由 / **归属模块**。

指标码、单位、聚合方式与 `ReportingMetricCatalog`（13.1.1.1）**逐字一致** ——
这不是巧合，是刻意抄的：**语义层不重写一份口径**，否则「智能查询里的销售额」与
「自定义报表里的销售额」会长成两个数。

### 2. 识别是确定性的，不是模型猜的

`matchMetric` / `matchDimension` 是**关键词 + 确定性打分**：强词权重 3、弱词权重 1，
**只命中弱词不算数**（只命中一个「店」就判成店铺维度是错的）；
同分时按「命中的最长关键词长度」裁决（`毛利率` 比 `毛利` 长 ⇒ 毛利率胜出），再同长按目录顺序。

🔴 **本仓没有任何真实的 LLM 适配器**（18.4.1.1 的 `AiTextGenerationPort` 背后没有供应商），
所以本模块**刻意不用**模型去猜指标 —— 那样会「猜」出一个词表外的指标，
而词表外的指标在 reporting 的口径表里根本没登记，取数必然是空或错。
这一条在能力声明里如实写成了缺口（归 **18.4 卷**）。

### 3. 解析不到就如实答「未接通」，不返回任何数字

三条路径都在 `SmartQueryService.query` 里，返回 `resolved=false` +
`unavailableReason`（缺什么）+ `ownerModule`（归谁），且
**`rows` 空、`evidence` 空、没有报表草稿**：

- **指标没落到词表** ⇒ 「这句问法没有落到受控语义层的任何一个指标上」+ 词表全清单（归 18.4.1.2）；
- **词表里但本租户口径表没登记** ⇒ 「未在本租户的口径表（`reporting_metric_definition`）里登记」
  ⇒ **未接通**（归该指标自己的归属模块，如 `SALES_AMOUNT` 归 9.1.1.1）——
  🔴 **词表里有 ≠ 现在能用**，这是两件事；
- **要了词表没有的聚合口径** ⇒ 「平均销售额」如实答不支持（口径表只有
  `SUM` / `LAST` / `DERIVED_DIFF` / `DERIVED_RATIO`，归 13.1.1.1）；
  「库存总额的合计」如实答不支持（`LAST` 型余额不能累加，归 13.1.1.1）。

🔴 数据层也钉住了：`platform_smart_query` 的 `metric_code` / `dimension_code`
**外键到同租户的词表行** ⇒ 表层面就不可能记下一个词表外的指标；
DB 层 CHECK 钉死「`resolved=true` ⇒ 有指标码且有报表草稿；`resolved=false` ⇒ 两者都没有」。
**「不出结论」在数据层是可查的，不是靠注释声明的。**

### 4. 为什么没有自由 SQL 入口

**三处同时没有，且互相印证**：

- **后端**：问句只在「词表 → 码」这一步被消化掉，之后全程是**码 + 绑定参数**；
  `SmartQueryService` 里的 jOOQ SQL **没有一处拼接问句**。
- **数据模型**：筛选条件是 `{dimensionCode, operator, valueText, valueText2}` ——
  **码 + 绑定值**，不是 SQL 片段（存进 `filters` JSONB 的也是这个形状）。
- **前端 + 代理**：页面上**没有输入 SQL 的地方**（只能问一句话，或从受控词表里挑指标 / 维度）；
  `apps/web/src/lib/api-proxy.ts` 里**刻意不放行** `platform/smart-query/sql` ——
  放行一条「自己下 SQL」的路径，等于把这条加粗句从后端搬到前端再拆掉。

🔴 另外两条配套纪律（与 18.4.1.1 同一套）：
**不给依据的数字不许出现**（结果表每一行都带明细依据：真表名 `reporting_metric_fact` /
真字段名 `value` / 事实引用 / 可点的明细路由 / 口径说明 / 快照时间）；
**算不出来 ≠ 0**（分母为 0 时取值是 `null`，页面上显示「—」）。

## 决策 / 结论

1. **执行面委托 13.6.1.1，不自己写聚合 SQL**。
   13.6.1.1 已经在 `reporting_metric_fact` 上有一套**口径唯一**的查询引擎（维度白名单 5 个、
   运算符白名单 5 个、值只作绑定参数）。本模块**委托**它执行 ⇒ 同一个指标在
   「智能查询」与「自定义报表」里给出**同一个数**；自己再写一套聚合逻辑就会长出**第二个真相**
   （13.6.1.1 的蓝图原话：「报表口径会分裂成几十个版本」）。
   ⇒ `SmartQueryReportingPort`：只转发调用方自己的 bearer（租户由上游按已认证成员关系解析并强制 RLS），
   **刻意不复用** `PlatformUpstreamClient`（那个客户端没有「带 JSON 体的 POST / PUT」，
   改它会动到 18.4.1.1 的文件 —— 并行车道的冲突面）。
   ⇒ **排序**是本模块自己做的（13.6.1.1 的报表配置里没有排序这一项），
   且**涉币指标不跨币种比较**：结果先按「这一行的币种集合」分组，组内才按指标值排；
   一个组里有多币种 ⇒ **无法比较** ⇒ 保持原序并在口径说明里点名。
   ⇒ **算不出来的行排在最后**，不是当成 0 参与排序。

2. **上游读不到就显式失败，不返回空集合**。
   空集合在结果表里会长成「这段时间没有销售额」，而真相是「这一格没读出来」
   ⇒ `SmartQueryReportingPort` 把上游的 401/403/404 分别翻译成**说人话的 503**
   （403 特别说清「你在自定义报表里没有建草稿的权限」，而不是「查询失败」）。

3. **常用查询是同一张表上的一个标志**（`saved` + `display_name`），不是第二张表。
   常用查询 = **固化下来的一条查询条件**，内容与当初那次查询逐字一致；
   存两份就会分叉，而分叉出来的常用查询永远解释不了「它当初查的是什么」。
   重跑**沿用**当初解析出来的受控条件（**不重新猜**），且**不新建**报表草稿
   （更新同一份草稿）⇒ 常用查询不会在自定义报表清单里堆出一堆同名草稿。

4. **相对时间用 DB 的 `now()` 算**。
   「上周」= 上一自然周、「本月」= 自然月至今；窗口起止日由一条
   `now() at time zone 'UTC'` 取出来再推导 ⇒ 不拿 JVM 墙钟和 DB 时钟比大小
   （跨时钟域比较会产出**恒定的假红**）。

5. **契约只提交 platform 两个文件**。
   codegen 会重写 9 个服务的契约文件；本轮只重建了 platform 的**活体** spec，
   其余 8 个用的是已提交的键名排序快照 ⇒ 跑完逐个 `git checkout` 还原
   （实测其余 8 个会产生**纯键序抖动**的数千行噪声 diff）。
   逐键比对：新增 9 路径 / 22 schema，删除 0、既有 schema 改动 0。

6. **顺带把可空字段的口径对齐**。
   SpringDoc 不会给 `$ref` 属性标 `nullable`，但普通字段标了就会生效 ⇒
   `SmartQueryAskInput.metricCode` / `dimensionCode`、`SmartQueryFilter.valueText2`、
   `SmartQueryPage.saved`、`SmartQueryResult.conditions` 补上 `@Schema(nullable = true)`。
   不标的话，生成的契约会把「后端真的会返回 null」的字段说成必填。

## 关键 trade-off

- **词表 12 条指标 vs 「什么都支持」**：宁可少支持，不可乱支持。
  词表外一律如实答「未接通 + 归谁」—— 那条回复看着不「有用」，但它是真的；
  一个编出来的数字会被人拿去决策。**未接通的四类**（平均口径、库存周转率、
  按法人 / 店铺名称筛选、AI 供应商适配器）都在能力声明里写明归谁。

- **委托 13.6.1.1 vs 自建查询引擎**：自建能少一次跨服务调用，但会分裂口径。
  口径分裂的代价远大于一次 HTTP 调用 ⇒ 选委托。

- **确定性与 LLM**：模型能听懂更多说法，但也会**猜**。
  本仓没有 LLM 适配器，且加粗句要求「只映射到受控语义层指标」⇒ 选确定性词表 + 确定性打分。
  代价是「上周各店铺的销售额排名」这种问法要写成词表认得的说法 —— 这一点在页面上写明了。

- **台账只追加 vs 可删**：台账是「当时为什么没查出来」的审计线索 ⇒ 只追加、
  保留 `resolved=false` 的行（否则「当时没查出来」这件事就没人能回看了）。
  常用查询是台账上的一个标志，取消常用**不删行**。

## 涉及文件

**迁移（新建）**
- `backend/services/platform/src/main/resources/db/migration/V441__platform_smart_query.sql`
  （四表：`platform_smart_query_metric` / `platform_smart_query_dimension` /
  `platform_smart_query` / `platform_smart_query_evidence`）
- `backend/services/identity/src/main/resources/db/migration/V441__platform_smart_query_permission.sql`
  （权限码 `platform.smart_query.read / .create / .update`）

**后端（新建）**
- `backend/services/platform/src/main/java/com/starcon/platform/SmartQueryController.java`
- `backend/services/platform/src/main/java/com/starcon/platform/SmartQueryModels.java`
- `backend/services/platform/src/main/java/com/starcon/platform/SmartQueryReportingPort.java`
- `backend/services/platform/src/main/java/com/starcon/platform/SmartQuerySemanticCatalog.java`
- `backend/services/platform/src/main/java/com/starcon/platform/SmartQueryService.java`

**测试（新建）**
- `backend/services/platform/src/test/java/com/starcon/platform/SmartQueryIntegrationTest.java`（22 例）
- `backend/services/platform/src/test/java/com/starcon/platform/SmartQueryTenancyIntegrationTest.java`（7 例）
- `backend/services/platform/src/test/java/com/starcon/platform/SmartQueryTestStub.java`

**前端（新建）**
- `apps/web/src/app/platform/smart-query/page.tsx`
- `apps/web/src/components/platform/smart-query-workspace.tsx`
- `apps/web/src/components/platform/smart-query-model.ts`（纯函数口径层）
- `apps/web/src/components/platform/smart-query-model.test.ts`
- `apps/web/src/app/api/platform/smart-query-allowlist.test.ts`

**前端（改：共享白名单，既有条目一字未改）**
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立的 `platform/` 条目**，首 token `smart-query`）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/contracts/platform.ts`（新增 22 条 `SmartQuery*`）
- `packages/api-contracts/src/platform.openapi.json`（新增 9 条路径）

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（**只改 18.4.1.2 那一行**的状态与证据两格）
- `docs/sessions/2026-11/2026-11-03-platform-smart-query.md`（本文件）

## 本会话修掉的三处夹具 bug（上一任会话遗留）

L1 第一次跑：**29 例、6 红**。根因全在测试夹具 `SmartQueryTestStub`，不在被测代码：

1. **从 run URL 里取 `reportId` 的下标算错** ——
   `path.substring(path.lastIndexOf('/') - 3, path.lastIndexOf('/'))` 只取到**后 3 个字符**
   （`"ae8"` 而不是整个 uuid）⇒ 夹具找不到自己那份报表配置 ⇒ 退化成「公司口径单行」
   ⇒ 排序 / 依据条数 / 租户隔离断言全红。
   ⇒ 改成取「倒数第二个 `/` 与最后一个 `/` 之间**整段**」。
   （真实契约 `POST /reporting/custom-report/reports/{reportId}/run` 本身是对的，
   与 reporting 的 `ReportingCustomReportController` 逐字一致。）
2. **拼多行时漏了行间逗号** ⇒ 上游响应**不是合法 JSON** ⇒ 解析失败被包装成
   `RestClientException`，看起来像模块的问题，其实是夹具的问题。
3. **「算不出来」那一行用了与租户无关的固定店铺 id** ⇒ 两个租户会拿到**一模一样**的事实引用
   ⇒ 「A 与 B 的依据没有任何一条相同」这条租户隔离断言失去意义。
   ⇒ 改成**租户自己的**第三个店铺 id。

🔴 三处都**没有改断言去迁就实现** —— 断言是对的，错的是夹具。

## 验证（真实输出）

```
cd backend && ./mvnw -q compile
→ BUILD SUCCESS

cd backend && ./mvnw -pl services/platform -am test -Dtest='SmartQuery*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
→ Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
  [INFO] Tests run: 22, Failures: 0, Errors: 0 -- in com.starcon.platform.SmartQueryIntegrationTest
  [INFO] Tests run: 7,  Failures: 0, Errors: 0 -- in com.starcon.platform.SmartQueryTenancyIntegrationTest

pnpm typecheck
→ Tasks: 3 successful, 3 total

pnpm exec prettier --check <本车道 6 个前端文件>
→ All matched files use Prettier code style!

pnpm vitest run apps/web/src/components/platform/smart-query-model.test.ts \
    apps/web/src/app/api/platform/smart-query-allowlist.test.ts
→ Test Files 2 passed (2) / Tests 29 passed (29)

pnpm vitest run apps/web/src/app/api/platform
→ Test Files 22 passed (22) / Tests 116 passed (116)
  （改了共享的 api-proxy.ts ⇒ 必须跑全量 allowlist 回归）

python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
→ Generated 7996 DTOs in 9 per-service files, 9 OpenAPI snapshots and one barrel
```

**租户隔离三条断言**（`SmartQueryTenancyIntegrationTest`，真实 PostgreSQL 16 + FORCE RLS，
连接池压到 1 条连接）：
① 两租户互不可见（含**按主键直查**也拿不到）；② 伪造 `X-Tenant-Id` 头无效
（**且要断言行确实落在会话租户名下**，不是只断言 A 看不见）；③ 连接池归还后不串租户。

## 后续待办

1. **语义层词表要能扩**：现在是**代码里的常量**（`SmartQuerySemanticCatalog`）。
   指标多了以后，12 条常量会越来越难维护。**但当前形态是对的** ——
   词表要能被审阅、能被钉住，「随便加个指标」必须走一次 code review。
   下一步若要开放给运营自助配置，得先解决「新增指标的口径由谁认」这个问题。
2. **按法人 / 店铺**名称**筛选**没接通（语义层只有 uuid，没有名称档案）⇒ 归 13.6.1.1。
   名称档案一旦在别的服务里建好，本模块的词表要能接上。
3. **平均 / 均值口径**没接通 ⇒ 归 13.1.1.1（口径表要新增 `AVG` 聚合方式）。
4. **库存周转率等库存类衍生指标**没接通 ⇒ 归 13.1.1.1 / 13.5.1.1
   （口径表要新增指标，且要有真实的取数来源）。
5. **AI 供应商适配器**没接通 ⇒ 归 18.4 卷。接上之后本模块的解析**仍然**保持确定性词表
   —— 模型可以用来**改写问句**（把口语变成词表认得的说法），但**不可以**用来决定「查哪个指标」。
6. **本模块的下游 13.6.1.1 已交付** ⇒ 报表草稿真的建在 reporting 那边、
   `report_id` / `report_code` 真的存进了台账。**不是**「把草稿存在自己服务的表里」的降级方案。
7. **前端没接 `packages/api-contracts`**：本模块前端模型是**自己声明**的类型
   （与 18.4.1.1 同一做法）。等全仓前端统一改用生成契约时再收口。

## 关联

- 蓝图：`docs/product/capability-blueprint/18-integration-and-automation.md`（18.4.1.2 智能查询）
- 台账：`docs/product/capability-blueprint/module-progress.md`（18.4.1.2 行）
- 上游兄弟：`docs/sessions/2026-11/2026-11-02-platform-assistant.md`（18.4.1.1 对话助手）
- 下游：13.6.1.1 自定义报表（reporting 服务，V408）
