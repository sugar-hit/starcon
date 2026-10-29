# 13.1.2.2 达成追踪（阶段 D7）

> 车道：13.1.2.2 · 分支 `feat/13-1-2-2` · 基线 `integration/module-dev`（f06fe104）
> 蓝图：`docs/product/capability-blueprint/13-profit-and-analytics.md` 的 13.1.2.2。

## 这个功能做什么

把 13.1.2.1 设好的目标**盯起来**：当前值、达成率、**按当前速度推算的预测达成**、未达标项高亮、
人员 / 产品维度排名、预警清单。

蓝图关系句点明「**预测达成值比当前值更有管理价值**——月初就知道会不会达标，才有时间干预」，
所以「预测达成」是与「当前值」并列的一等输出，不是附注。

## 关键 trade-off

1. **「算不出来」与「等于 0」用两个正交的受控词表 + DB CHECK 钉死**
   （`actual_status` AVAILABLE / NO_DATA / NOT_AVAILABLE × `status` ACHIEVED / ON_TRACK / AT_RISK /
   BEHIND / NOT_STARTED / NOT_COMPARABLE / UNAVAILABLE）。
   理由：**0% 达成率是有业务含义的断言**（确实一件都没做成），**「不可得」不是**
   （我根本拿不到这个维度的实际值）。前端读反一处，管理者就会去追一个不存在的问题。
   「**没达标**」与「**还没法判定**」也分开：未达标高亮只认 AT_RISK / BEHIND，
   NOT_STARTED / NOT_COMPARABLE / UNAVAILABLE 单列。

2. **实际值来源按归属维度分三种，结果不一样 —— 如实报，不糊**
   - 店铺维度 ⇒ `reporting_store_metric_fact`（owner 级事实**真的存在**）；
   - 产品维度 ⇒ `reporting_product_metric_fact` + **三条口径等价映射**
     （成交金额 / 订单数 / 件数）；毛利额 / 毛利率在商品维度**没有等价口径 ⇒ 报「不可得」**，
     **不拿成交金额冒充毛利额**；
   - **部门 / 人员维度 ⇒ 实际值尚未投影到 reporting**（上游只到公司 / 法人 / 店铺维度）
     ⇒ 报「不可得」，**绝不拿租户级数字冒充某个部门的实际值**（那是把公司的成绩算到部门头上）。
   代价：人员维度的排名在数据补齐前是空的 —— 但那是**诚实的空**，不是假 0。

3. **「当前值是 0」与「当前值取不到」也要分开** —— 用**归属对象 + 口径**的「生命周期」判据：
   该组合**从来没有过事实行** ⇒ 取不到（null）；**有过事实但本窗口内一行没有** ⇒ **真的是 0**
   （窗口内确实没发生）。判据来自一条「按 (归属对象, 口径) 聚合的全历史计数」查询。

4. **时钟域只认数据库时钟**：评估日与评估时刻同源于 PG 的 `now()`
   （`select (now() at time zone ?::text)::date, now()`），**不是 JVM 墙钟**。
   理由：本仓已有两次「断言两端分属两个钟域」的间歇性事故（identity 审批待办超时、warehouse
   货权档漂移判定），而**一天在预测达成率上就是几个百分点**。测试夹具也因此用 DB 时钟造数据。

5. **落快照（派生读模型）而不是只算实时值**：下游是 **17 绩效核算**与 **1 工作台预警**，
   「月初就知道会不会达标」要求的是**一个可回看的时间点**，不是一个随时会变的实时值。
   ⇒ 表 `reporting_target_attainment`（一个目标 × 一个评估日），`POST /evaluate` 写它，
   `GET /snapshots` 读它。**写的是派生读模型，不是业务事实**（reporting 的纪律不破）。

6. **排名不跨币种、不跨指标**（销售额达成率与毛利率达成率不是一回事，USD 与 EUR 的大小本来就不可比）；
   **算得出达成率的目标才进排名**（给「不可得」一个名次等于替它编了一个成绩）。

## 文件清单

### 后端（reporting 服务）

- `backend/services/reporting/src/main/resources/db/migration/V390__target_attainment.sql` —— **新增**。
  一张表 `reporting_target_attainment`；`tenant_id NOT NULL` + ENABLE **且** FORCE RLS + GRANT；
  与 `reporting_target` 的**同库复合外键 (tenant_id, target_id)**；**零 ALTER 既有表**。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingTargetAttainmentModels.java`
  —— **新增**。13 个 DTO（`Attainment*` 前缀，避开既有类型的同名闸）。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingTargetAttainmentService.java`
  —— **新增**。达成计算引擎（当前值 / 达成率 / 预测达成 / 状态分档 / 排名 / 预警 / 快照落库）。
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingTargetAttainmentController.java`
  —— **新增**。5 个端点挂 `/reporting/target-attainment`。
- `backend/services/reporting/src/test/java/com/starcon/reporting/TargetAttainmentIntegrationTest.java`
  —— **新增**。业务口径（真实 PostgreSQL 16）。
- `backend/services/reporting/src/test/java/com/starcon/reporting/TargetAttainmentTenancyIntegrationTest.java`
  —— **新增**。多租户三条断言（连接池固定 1 条连接）。

### 前端

- `apps/web/src/app/analytics/dashboard/attainment/page.tsx` —— **新增**。路由 `/analytics/dashboard/attainment`。
- `apps/web/src/components/analytics/target-attainment-model.ts` —— **新增**。纯函数展示层（不做业务算术）。
- `apps/web/src/components/analytics/target-attainment-model.test.ts` —— **新增**。
- `apps/web/src/components/analytics/target-attainment-workspace.tsx` —— **新增**。真实工作区。
- `apps/web/src/app/api/reporting/target-attainment-allowlist.test.ts` —— **新增**。白名单双向断言。
- `apps/web/src/lib/api-proxy.ts` —— **改**。**纯新增 1 条 `services[]` 元素**（`reporting/` +
  `target-attainment` 正则），既有条目一字未改、未重排。

### 契约与台账

- `packages/api-contracts/src/contracts/reporting.ts` / `packages/api-contracts/src/reporting.openapi.json`
  —— **改**（codegen 产物，**纯新增**：5 条 path + 13 个 DTO，零删除零改动）。
- `docs/product/capability-blueprint/module-progress.md` —— **改**。只改 13.1.2.2 那一行的「状态」「证据」。

## 验证

> ⚠️ **2026-10-27 续做轮（上一轮会话被网络中断，主体代码已提交但 L1 没跑完）**：
> 下面带「续做轮实跑」标记的条目是**本轮真跑出来的输出**，不是上一轮的计划。

- L0 编译闸：`./mvnw -q compile` → **exit 0**；`pnpm typecheck` → **exit 0**（3/3 成功）；
  `pnpm lint` → **exit 0**（只剩与本模块无关的既有 warning）。
- 契约导出：`OpenApiContractTest`（**不碰数据库**，不启容器）→ `backend/target/openapi/reporting.openapi.json`，
  再 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`。
- **续做轮实跑 · 白名单核对**：`.tmp/allowlist-verify.mjs`（**用 TypeScript 编译器真实求值数组**，不是文本计数）→
  元素数 **248（基线 f06fe10）→ 249**（只 +1）；**基线与 HEAD 逐条集合差：新增 1 条、被删 0 条**；
  本模块 5 条路径全部放行、3 条刻意不放行的形状全部 404；
  其它车道已登记的 reporting/ 分支
  （overview / projection / expense-variance / store-comparison / sales-trend / metric-cards /
  store-overview / product-sales / targets）**全部仍在**。
- **续做轮实跑 · 契约纯超集核对**：JSON 层面集合差 —— path **45 → 50**（只多本模块那 5 条）、
  schema **100 → 113**（只多本模块那 13 个），**被删的 path / schema 均为 0**。
- **续做轮实跑 · L1 模块闸**：
  `./mvnw -pl services/reporting -am test -Dtest='TargetAttainment*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ **19 例全绿**（`TargetAttainmentTenancyIntegrationTest` 4 + `TargetAttainmentIntegrationTest` 15，
  Failures 0 / Errors 0）BUILD SUCCESS。**首跑是红的**（1 例 Error），
  归因与修复见下面「踩坑记录」新增的一条。
- **续做轮实跑 · 前端模块闸**：
  `pnpm vitest run apps/web/src/components/analytics/target-attainment-model.test.ts
  apps/web/src/app/api/reporting/target-attainment-allowlist.test.ts` ⇒ **24 例全绿**（2 文件）。
- **续做轮实跑 · 全量 allowlist 回归**（改了共享白名单文件必须跑）：
  `pnpm vitest run apps/web/src/app/api` ⇒ **261 文件 / 1473 例全绿**。
- **续做轮实跑 · 容器**：verify 期间同时存活的 PG 容器 = **1**（用例自带的 Testcontainers PG16）；
  结束后由用例自己的 `@AfterAll` 停掉，复查 `docker ps` **无 `postgres:16-alpine` 残留**
  （当时在跑的都是无关的 `crewhub` / `langfuse` 容器），无泄漏。

### 续做轮补了什么

1. **修掉 L1 首跑的那 1 例 Error**（`TargetAttainmentIntegrationTest.产品维度_毛利额没有等价口径_如实报不可得`）：
   **是夹具缺口，不是实现缺口** —— 见下面「踩坑记录」新增条目。改动只落在
   `TargetAttainmentIntegrationTest.java`（新增 `seedCogsCaliber()` 夹具 + 在该用例里先播种分母口径），
   **生产代码一行未动**。
2. **会话日志补记 L1 实跑结果与本轮补的内容**（本节 + 「踩坑记录」新增一条）。
3. **台账 `module-progress.md` 未再改动** —— 上一轮已把 13.1.2.2 那一行改成「已交付」，
   本轮复核：整个文件的 diff 是 **1 行增 / 1 行删**（只有 13.1.2.2 那一行），没碰别的行。

## 后续待办

1. **部门 / 人员维度的实际值投影**（上游）：补齐后本模块**不需要改表、不需要改接口**就会自动算出达成率
   （`actual_status` 从 NOT_AVAILABLE 变成 AVAILABLE / NO_DATA）。
2. **产品维度的实际值**：取决于 13.2.2.2 的商品维度投影是否跑过；本模块已实现完整链路，
   数据没投影时如实报「不可得」。
3. **17 绩效核算（整卷未开发）**：读 `reporting_target_attainment` 即可；本模块只算达成，不算绩效、不改人事。
4. **1 工作台预警**：读 `board` 端点的 `alerts`；本模块不订阅、不推送（那是 1.2 预警订阅那一格）。
5. **卷 13 的导航入口**：`/analytics/dashboard/attainment` 与其它卷 13 子页面一样**尚未进导航**
   （多车道共用的目录文件，刻意不在本车道动；见 `docs/backlog/2026-10-2026-10-26-analytics-section-no-navigation-entry.md`）。

## 踩坑记录（供后来者）

- **同一个词，两种拼写，肉眼分不出来**：本轮 `forecastAttainmentRate`（`Atttainment` + `ment`）
  与 `forecastAttainmentRate`（`Attainment` 少一个 `t`）在终端里长得一模一样，
  javac 报「找不到符号 方法 forecastAttainmentRate()」而访问器明明存在（`javap` 可见）。
  **只有把两边的标识符按字符 dump 成码点才能看出来。**
  ⇒ 最终把该 record component 改名成 `forecastRate`（避开这个词根），并在
  `upsertSnapshot` 里把绑定值先取成局部变量 —— **SQL 的列顺序与绑定值顺序能逐行对读**。
- **不要用文本计数核对 `services[]` 元素数**：本轮用 **TypeScript 编译器**解析
  `services` 变量的真实数组字面量（`.tmp/api-proxy-zero-hit.mjs`），才敢说「248 → 249，只 +1」。
- **白名单要按后端的真实形状逐条对齐**：本模块 5 条端点**全在子路径下**（没有裸路径），
  所以正则写成 `/^target-attainment\/(?:capabilities|board|ranking|snapshots|evaluate)$/` ——
  刻意**不放行**裸路径 `reporting/target-attainment`（后端没有这条映射）。
  第一版写成了 `(?:\/...)?`（裸路径也放行），被前端的白名单双向断言当场抓住。
- **契约重新生成后要核对「是不是纯超集」**：本轮用集合差核过 ——
  path 45 → 50（只多我那 5 条）、schema 100 → 113（只多我那 13 个），
  **一个既有的 path / schema 都没少**（diff 里的 `-89` 行只是 codegen 重排路径顺序）。
- **「派生型口径」的分子 / 分母必须**先**登记 —— 续做轮 L1 首跑的红就在这儿（2026-10-27）：
  用例要验「毛利额在商品维度没有等价口径 ⇒ 如实报不可得」，就得先落一个 `GROSS_PROFIT` 口径码；
  但 V376 的触发器 `guard_reporting_metric_definition_ref` 会在 `DERIVED_DIFF` / `DERIVED_RATIO`
  落库前检查**分子与分母指标码是否已定义** ——「毛利额 = 成交金额 − 成本」里的「成本」必须先有自己的口径，
  否则报 `指标 GROSS_PROFIT 的分母指标 COGS_AMOUNT 尚未定义，口径不成立`。
  **看到这个错不要去改触发器，也不要改实现去绕开它** —— 它挡的正是「凭空捏出一个派生口径」。
  正确做法是**把夹具补齐**：先播种 `COGS_AMOUNT`（SUM 型，分子分母留空），再落 `GROSS_PROFIT`。
  ⇒ 本轮补了 `seedCogsCaliber()` 夹具，**生产代码一行未动**，改完 L1 19 例全绿。
- **白名单核对脚本要连基线一起跑**：只看 HEAD 的数组元素数（249）说不出「是不是只 +1」。
  续做轮把基线 `f06fe10` 的 `api-proxy.ts` 也抽出来逐条求值，做**集合差**：
  新增 1 条、**被删 0 条** —— 这一条才是「没删掉别人已交付的路由」的真凭据
  （被删一条的症状是页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**）。
