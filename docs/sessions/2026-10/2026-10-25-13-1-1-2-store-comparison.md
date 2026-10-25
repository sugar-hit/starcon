# 2026-10-25: 13.1.1.2 多店对比（reporting 服务，V381）

## 摘要

交付 **13.1.1.2 多店对比**：多店铺横向对比（规模、增长、利润率、广告效率、退货率），
找出掉队店铺与增长店铺，支持按平台分组。

这一车道**连续两次**被环境故障（`net::ERR_HTTP2_PING_FAILED` / 500）打断在
「代码写完、还没提交」的时刻，前一位 worker 留下的 7 个文件全部未提交。
接手时先复现他的诊断：干净重建 `./mvnw -pl services/reporting -am clean compile`
结果 **BUILD SUCCESS** —— 他诊断的「3 个编译错误」在最后一刻已经自己改完了，
两个增长指标的 `baseMetricCode` 也已经填上了真实基准指标。
⇒ **不是「修半成品」，而是「先把没提交的成果安全落库，再补剩下的交付物」**。
本车道据此把纪律改成：**每写完一个文件立刻提交**，全程 21 个 commit。

主责服务 `reporting`（卷 13 的唯一归属服务），迁移 **V381**，
路由 `/analytics/dashboard/store-comparison`。

## 接手情况：读懂了什么 / 补完了什么 / 改了什么

**继承的 7 个文件（全部未提交）**：V381 迁移、指标口径目录、出入参模型、跨服务只读端口、
投影刷新服务、对比主服务、HTTP 控制器。逐个读过后确认实现是完整的
（口径来自口径表不重算公式、递归求值派生指标、算不出来返回 null 而非 0、
不跨币种相加且排名在币种内做、跨平台口径差异同时落平台侧与还原口径两份金额）。

**本车道补完的**：

1. **修 `project()` 的 timezone 写死**（`5a6c3273`）—— 事实行的 `timezone` 列写死 `'UTC'`，
   而 `bucket_date` 是按传入时区分桶出来的 ⇒ 行会声称自己按 UTC 分桶，实际按的是别的东西，
   「跨时区店铺汇总必须显示所用时区」这条口径失真。
2. **两个后端测试类**（`d824b45a` / `ef017413`）—— 继承的代码**一条测试都没有**。
3. **DTO 改名避开契约生成器的同名闸**（`f020a70c`）—— 见下面 trade-off 1。
4. **重新生成契约**（`582a1349`）—— 继承的代码没有任何端点进契约。
5. **前端四件**（`93983ae1` / `609fb82a` / `ffa696b7` / `985a1fb1`）—— 模型层 + 单测 + 工作区 + 页面。
6. **api-proxy 白名单 + 回归测试**（`33cc493b` / `4f36f271`）。
7. **把「算不出来」的 null 写进契约**（`fd5d58c9`）—— 见下面 trade-off 2。
8. **台账 + 本会话日志**（`91f7c0dc` + 本文件）。

## 决策 / 结论

1. **🔴 跨平台口径差异做成数据层的事实，不是页面上的一句提醒。**
   蓝图关系句加粗要求「跨平台对比要处理口径差异（亚马逊的 GMV 与 TikTok 的口径不同）」。
   做法是**同时落两份成交金额**：`STORE_GMV_PLATFORM`（平台自己报的口径）与
   `STORE_GMV_RESTORED`（我方从平台订单还原出来的口径），并派生
   `STORE_CALIBER_GAP`（DERIVED_DIFF）＝两者之差。
   ⇒ 「口径差异额」是一个可比较、可排名、可归因的数字，而不是一段免责文字。

2. **🔴 口径唯一表 + DB 触发器把「口径不成立」挡在数据层。**
   `reporting_store_metric_definition` 是店铺对比的指标口径唯一表：
   CHECK 钉死「派生型必须写全分子分母 / 窗口环比型必须写全被比较指标」，
   触发器 `guard_reporting_store_metric_definition_ref` 拒绝引用未登记的分子分母与
   `base_metric_code`；触发器 `guard_reporting_store_metric_fact_known` 拒绝事实行自己发明指标码。
   ⇒ 口径改了查询就跟着改（查询 SQL 里无任何硬编码公式），而口径改坏了**写不进来**。

3. **🔴 窗口环比必须声明「比的是哪个指标」，不能靠约定。**
   `base_metric_code` 是为此专设的列：成交金额环比比 `STORE_GMV_PLATFORM`，
   成交订单量环比比 `STORE_ORDER_COUNT`。
   base 为空 ⇒ `definitions.get(null)` 得到 null ⇒ **增长恒为空**，
   且违反 CHECK（WINDOW_GROWTH 要求 base 非空）⇒ 播种阶段就炸。
   前一位 worker 诊断的第 2、3 条正是这一处，他已在中断前修好。

4. **🔴 「算不出来」与「等于 0」全程分开。**
   服务端把「分母为 0 / 没有事实 / 指标未登记」一律返回 `null` /
   `NO_DATA` / `NOT_COMPARABLE`；前端渲染成「—」/「不可比」+ 原因。
   假 0 会被读成「这个店铺销售为零」。

5. **🔴 排名在币种内做。**
   一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。排名榜按币种分桶呈现，
   `RankingEntry` 的币种跟着条目走。

6. **只读跨服务调用**：转发调用方自己的 bearer、**不传租户号**，
   上游按已认证成员关系解析租户并强制 RLS（隔离两道）。
   上游读不到一律 503（`ReportingUpstreamClient` 把故障翻成「读不到」而不是「没有数据」）。

7. **另起三张表，不 ALTER 13.1.1.1 的任何一张表。**
   理由同 `V376` 的纪律：13.1.1.1 的维度键是 uuid 而本模块用 `shop_code`（跨服务 API 暴露的是编码）、
   它的 aggregation 枚举没有「窗口环比」这一档、reporting 零 ALTER 既有表。

## 关键 trade-off / 讨论

- **🔴 DTO 改名（`MetricDefinitionView` → `StoreMetricDefinitionView`，
  `PlatformOption` → `StorePlatformOption`）不是洁癖，是生成器的硬约束。**
  SpringDoc 用**简单类名**当 schema 名：同服务内同名只能加序号消歧，
  而序号取决于**发现顺序**（换个服务先启动就换一个名，生成出来的契约跟着漂）；
  跨服务同名且字段不同则被 codegen 的碰撞闸**直接中止**（allowlist 是空的）——
  channel 里已经有一个 `PlatformOption = {id, code, name}`。
  ⇒ 起名时必须避开「同名 + 不同字段」这个组合，否则整个契约生成流程被一条类型名卡住。

- **🔴 可空字段必须显式 `@Schema(nullable = true)`，否则契约在骗人。**
  SpringDoc 默认不给可空字段打 `nullable` ⇒ 生成的 TS 里 `value: number`，
  而服务端**真的会返回 null**。前端要么被迫写死类型，要么把 null 当合法值，两种都错。
  补齐后契约如实变成 `value: number | null`。
  这条是 `pnpm typecheck` 逼出来的 —— 契约里的 `number` 与测试夹具里的 `null` 直接冲突。

- **假上游起的是真的 HTTP 服务**（channel + trade 两条路径各自一个 `HttpServer`），
  驱动**真实的拉取与投影路径**。这样端口写错路径或字段名会在这一层就暴露
  （写错字段名 ⇒ 那一类指标恒为 0）。渠道广告指标**按 from/to 过滤**，
  因为端口是**逐日**拉的（channel 的店铺分组没有日期桶）—— 不按日过滤就会把 N 天算成 N 倍。

- **多租户测试的假上游按 bearer 分租户**（A 只给 shop-a、B 只给 shop-b）。
  否则「B 看不见 A」只能靠「B 什么都没有」证明，证明不了「B 只看得见自己的」。
  连接池固定 **1 条连接** —— 池大于 1 时「没串」可能只是碰巧换了条干净连接。

- **写权限必须在同一个 grant 里同时命中对应的 `.read`**，否则刷新端点直接 403
  （服务端判据是「能写不能读」的组合不放行）。测试夹具漏了
  `reporting.projection.read` 就撞上了这条。

- **契约生成走 `--spec-dir` 而不是起服务**：9 个服务的 `OpenApiContractTest`
  各自导出 `backend/target/openapi/<service>.openapi.json`，再由
  `tools/codegen/catalog-contracts.py --spec-dir` 生成 —— 与 CI 跑在活端口上的结果同构，
  但不需要起整套 docker compose。

- **api-proxy 只做纯新增**（37 行、零删除），插入锚点选在 13.4.2.2 那条 `reporting/` 之后
  （数组开头那一段），与其它车道的「数组末尾」插入点相距 10000 行以上，并入时 git 能干净求并。

- **台账只手工改 13.1.1.2 那一行**，其余由 `tools/module-progress/sync.mjs` 重新生成
  （该文件在 `.prettierignore` 里，排版必须与生成器输出一致）。
  顺带修正：生成器重算后总览从 402/63 变成 405/60 —— 其中 +2 是**既有行早就改了但表头没同步的
  存量偏差**，本次一并对齐（已逐行核对：465 个模块行里只有 13.1.1.2 一行变了）。

## 文件清单

**后端（`backend/services/reporting`）**

- `src/main/resources/db/migration/V381__store_comparison.sql` — 三表 + RLS + 口径触发器
- `src/main/java/com/starcon/reporting/ReportingStoreComparisonModels.java` — 出入参模型（含 `@Schema(nullable = true)`）
- `src/main/java/com/starcon/reporting/ReportingStoreComparisonCatalog.java` — 指标口径目录（口径唯一）
- `src/main/java/com/starcon/reporting/ReportingStoreComparisonPort.java` — 跨服务只读端口
- `src/main/java/com/starcon/reporting/ReportingStoreComparisonRefreshService.java` — 投影刷新
- `src/main/java/com/starcon/reporting/ReportingStoreComparisonService.java` — 对比主服务
- `src/main/java/com/starcon/reporting/ReportingStoreComparisonController.java` — HTTP 端点
- `src/test/java/com/starcon/reporting/StoreComparisonIntegrationTest.java` — 15 条业务口径断言
- `src/test/java/com/starcon/reporting/StoreComparisonTenancyIntegrationTest.java` — 5 条多租户断言

**前端（`apps/web`）**

- `src/components/analytics/store-comparison-model.ts` — 纯模型层
- `src/components/analytics/store-comparison-model.test.ts` — 34 条单测
- `src/components/analytics/store-comparison-workspace.tsx` — 工作区组件
- `src/app/analytics/dashboard/store-comparison/page.tsx` — 页面
- `src/lib/api-proxy.ts` — **纯新增 37 行、零删除**
- `src/app/api/reporting/store-comparison-allowlist.test.ts` — 6 条白名单断言

**契约（生成物，未手改）**

- `packages/api-contracts/src/contracts/reporting.ts` — 新增 15 个 DTO
- `packages/api-contracts/src/reporting.openapi.json` — 新增 5 条路径

**文档**

- `docs/product/capability-blueprint/module-progress.md` — 13.1.1.2 → 已交付
- `docs/sessions/2026-10/2026-10-25-13-1-1-2-store-comparison.md` — 本文件

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -o -pl services/reporting -am clean compile` → **BUILD SUCCESS**
  （接手时的干净重建同样 BUILD SUCCESS，说明继承代码本身可编译）
- **L0 类型闸**：`pnpm typecheck` → **3/3 tasks successful**
- **L1 后端**：`./mvnw -pl services/reporting -am test -Dtest='StoreComparison*'` →
  **Tests run: 20, Failures: 0, Errors: 0**
  （`StoreComparisonIntegrationTest` 15 + `StoreComparisonTenancyIntegrationTest` 5）
- **前端本模块**：`pnpm vitest run apps/web/src/components/analytics/store-comparison-model.test.ts
  apps/web/src/app/api/reporting/store-comparison-allowlist.test.ts` → **40 例全过**
- **api-proxy 全量回归**（改了共享白名单，必须跑）：`pnpm vitest run apps/web/src/app/api` →
  **251 文件 / 1417 例全过**
- **前端 lint**：`npx eslint` 覆盖本模块 5 个新文件 → **0 问题**
- **契约**：9 个服务全部导出 spec → `catalog-contracts.py --spec-dir` → **7002 个 DTO**，
  只有 `reporting.ts` 与 `reporting.openapi.json` 变化；新增 5 条路径 + 15 个 schema
- **未跑**：`./mvnw verify` 全量、整个 reporting 服务的测试、`pnpm --filter @starcon/web build`
  （按任务书由调度器在集成头统一跑）

## 后续待办

1. **店铺维度的毛利率与利润率仍然拿不到** —— 上游的成本分录（12.4.1.2）与利润表
   （12.5.2.2）只有法人/公司维度，没有店铺维度。已在 `capabilities` 里**如实报出**，
   矩阵里没有毛利率这一列 —— 写一个公司维度的毛利率挂到店铺上就是造假。
   上游补齐店铺维度后，同一张事实表就能直接承接，**不需要改结构**。
2. **店铺维度的库存周转同样不可得** —— 在手库存（7.1.1.1）按 SKU × 法人 × 仓，没有店铺维度。
   蓝图职责句里列了「库存周转」，当前只能如实报为不可得；上游补齐后承接即可。
3. **店铺的平台归属依赖 channel 的广告活动** —— 没有任何广告活动的店铺拿不到平台，
   被归到「未标注平台」组并标注口径说明（绝不猜一个平台）。
   若 channel 后续提供「店铺 → 平台」的直接映射，刷新服务改一处即可。
4. **手工销售订单不在店铺维度内** —— 店铺维度的成交金额来自**平台单**（9.1.1.1），
   手工录入的销售订单没有店铺归属，不计入店铺对比。
   店铺对比口径 = 平台单口径，与公司口径（订单列表）不是同一个数。
5. **广告指标逐日拉取有请求数代价** —— channel 的店铺分组没有日期桶，
   所以 N 天 = N 次 HTTP 调用，窗口长度有硬上限（`MAX_WINDOW_DAYS = 46`，
   即对比窗口最多 23 天，含环比所需的上一等长窗口），超了直接 400 不悄悄截断。
   若 channel 后续支持按日分组的店铺维度接口，这个上限可以放宽。
6. **下游（各店铺的专题分析）未交付** —— 蓝图关系句的下游归各专题模块，
   本模块只输出对比矩阵 + 排名 + 差异归因提示。
