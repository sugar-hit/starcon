# 2026-10-28 · batch-107：卷 13 利润分析两条车道（13.4.1.1 已交付 · 13.4.2.1 在途）

## 批次说明

本批是**降档模式**（`MODE=reduced`）下派发的两条车道，L3 锚点不同、互不依赖：

| 模块 ID   | 名称           | 阶段 | L3 锚点 | 分支             | 迁移版本 | 状态              |
| --------- | -------------- | ---- | ------- | ---------------- | -------- | ----------------- |
| 13.4.1.1  | 多模式利润报表 | D7   | 13.4.1  | `feat/13-4-1-1` | **V398** | ✅ **已交付并入**  |
| 13.4.2.1  | 成本结构       | D7   | 13.4.2  | `feat/13-4-2-1` | **V399** | ⏳ **在途**（本节待落地后追加） |

并入记录：`merge-ready` ⇒ `{"lane": "13.4.1.1", "from": "80834214", "to": "a3a2e829"}`，无冲突。

⚠️ **本批的 L2 批次闸尚未跑** —— 13.4.2.1 仍在开发中，在途车道的 L1 与 L2 并发是协议明令禁止
（2026-10-19 实测 PG 峰值 16 ⇒ 后端 1364 例里 3 failures + 24 errors 全是假红）。
⇒ 批次 L2 归下一轮，本文件随那一轮的 L2 结论更新。

---

## 模块 13.4.1.1 多模式利润报表

**这个功能做什么**：运营按**经营模式**（自发货 / 全托管 / 半托管 / 平台仓）分别看利润，
并能沿 **MSKU / SKU / SPU / 店铺 / 订单** 任一维度下钻，看清每一笔收入扣掉哪些成本后还剩多少毛利；
报表顶部固定挂着**口径说明**与**数据更新时间**，算不出来的格子显示「—（不可比）」而不是 0。

> 蓝图的硬要求：**「不同模式的利润含义完全不同，混在一张表里会得出错误结论 —— 必须在报表维度上区分。」**
> 本模块把经营模式做成**报表的必选维度**，并按模式分行出报表。

### 技术落地

- **迁移**：`backend/services/reporting/src/main/resources/db/migration/V398__profit_report.sql`（238 行）
  - 两张**纯 CREATE TABLE**（**零 ALTER 既有表** ⇒ 协议点名的「加了列没给默认值 ⇒ 别人老 INSERT 违约」
    与「SET NOT NULL 无触发器兜底」两种互相破坏形状**不可能发生**）：
    - `reporting_profit_shop_profile` —— 店铺档案 + **经营模式**
    - `reporting_profit_fact` —— 利润事实**长表**：一行 = 一个成本项 × 归属维度 × 期间 × 币种
  - 两表 `tenant_id uuid NOT NULL` + **ENABLE 与 FORCE RLS** + `CREATE POLICY isolation`
    + `GRANT reporting_app / starcon_app` + `REVOKE ALL ON flyway_schema_history FROM reporting_app`
    （与 `V379__expense_variance.sql` 完全同构）
  - 金额 `numeric(24,4)` + 数量 `numeric(19,6)` + 币种 `char(3)` CHECK `^[A-Z]{3}$`
    ⇒ **全仓无一处浮点、绝不跨币种相加**
  - **归属维度列全部 `NOT NULL DEFAULT ''`** —— 用空串而不是 NULL，因为 PostgreSQL 的 UNIQUE 把 NULL
    视为互不相等，「平台级」行会插出第二条同样的行，幂等键就不成立
  - **实际侧允许负数** —— 退款 / 冲回是真实发生的费用变化，截成 0 等于把「平台退了这笔钱」
    写成「平台没收这笔钱」

- **端点**：`/reporting/profit-report`（**新增独立命名空间**，未并进既有 `reporting/` 正则）
  | 方法 | 路径 | 负责什么 |
  |------|------|----------|
  | GET | `/reporting/profit-report/capabilities` | 能力声明：哪些上游真接通、哪些确实算不出来、归谁管 |
  | GET | `/reporting/profit-report/report` | 多模式利润报表（按模式分行 + 多维核算） |
  | GET | `/reporting/profit-report/drilldown` | 多维下钻构成明细（真分页 + 真计数） |
  | GET | `/reporting/profit-report/modes` | 经营模式清单（模式取自 2.3.2.1） |
  | POST | `/reporting/profit-report/refresh` | 刷新利润事实（投影摄取，写的是投影不是业务事实） |

  权限码两个：`reporting.profit-report.read`（本模块只读）与 `reporting.projection.write`（投影摄取）——
  本模块**没有任何「创建业务事实」的权限**，reporting 是投影服务。
  租户**不来自任何请求头**：先向 identity 确认已认证成员关系与权限，再在一个事务里绑定
  `app.current_tenant`，由 RLS 在 DB 层强制隔离。

- **页面**：`apps/web/src/app/analytics/profit/profit-report/page.tsx` +
  `apps/web/src/components/analytics/profit-report-model.ts` + `profit-report-workspace.tsx`
  —— **真实工作区**（不是占位页）：模式行利润报表 + 多维下钻 + 能力声明，
  带**错误态 / 空态 / 加载态**，`dataUpdatedAt` 常驻顶部（卷 13 卷首加粗句「数据延迟要显示」）。
  路由白名单 `apps/web/src/lib/api-proxy.ts` **新增一条独立 `services[]` 元素**
  （既有 12 条 `reporting/` 正则**一字未改、不重排、不删除**），并附**首 token「profit-report」的
  零命中核对结论**（实测，非目测）；显式放行「未实现的子路径仍 404」，
  并点名 **`profit-report/sku-profit` 不放行 —— 那是兄弟模块 13.4.1.2 的命名空间，尚未开发**。

- **测试**：
  | 测试类 | 用例 | 覆盖 |
  |--------|------|------|
  | `ProfitReportIntegrationTest` | 10 | 报表口径、模式分行、多维下钻、分页与计数 |
  | `ProfitReportTenancyIntegrationTest` | 3 | 多租户三条断言**实跑**（两租户互不可见、伪造 `X-Tenant-Id` 头无效、连接池归还后不串租户），真实 Testcontainers PostgreSQL 16 + FORCE RLS |
  | `ProfitReportRefreshIntegrationTest` | 4 | 投影摄取全链路（真实上游桩，含类别过滤） |
  | `profit-report-model.test.ts` | 20 | 前端模型层（含「算不出来 ≠ 0」的渲染口径） |

  ⇒ **合计 17 个后端用例 + 20 个前端用例**。

### 关键设计决策

1. **经营模式是 channel 2.3.2.1 `shop.operating_mode` 的只读投影，不是本模块的枚举** ——
   模式是**上游事实**，在本模块硬编码一份枚举等于制造「第二个真相」；
   挂不上店铺归「未归属」单列，不静默丢弃。
2. **两种收入口径并存且不相加** —— **订单总额口径**（模式 / 店铺 / 订单维度）vs
   **订单行金额口径**（SKU / SPU / MSKU 维度）。两者口径不同，**相加即错**，
   所以报表按维度选择口径而不是混算。
3. **一笔钱只有一个写入所有者** —— 平台费 / 物流费 / 广告费归 **12.3.1.2 结算明细**；
   12.4 的同名费用池**不重复计入**。否则同一笔费用会被扣两次，毛利虚低。
4. **「算不出来」按租户在该维度上的事实判定** ⇒ 各模式的判定口径一致；
   缺项时毛利给 **`null`**（「—（不可比）」），**绝不显示 0.00** —— 假 0 会被读成「完全一致」。
5. **报表是投影，不重算费率、不重算分摊** —— 只读上游算好的结果（与 13.4.2.2 费用差异同一纪律）。
6. **只做本模块职责范围** —— 兄弟模块 13.4.1.2「SKU 利润」未触碰，路由也显式不放行它的路径。

### 已知缺口（如实记录，未半成品）

1. **SKU / SPU / MSKU 维度毛利常不可比** —— 上游 12.4.2.2 把费用池分摊到**店铺 / 订单**维度，
   SKU 维度无成本 ⇒ 毛利 `null` + 原因（属**上游配置问题**，非代码问题）；
   SKU 级利润口径归 **13.4.1.2**。
2. **租户压根没有某类成本时**，该成本项不参与毛利计算 ⇒ 若上游有而未投影，毛利会偏高；
   已在**报表级警告**里逐项点名，**未用假 0 冒充**。
3. **订单明细逐单取上限 200 张/次**（N+1），触顶时如实报 `TRUNCATED`
   （订单总额口径的收入仍完整）。
4. **MSKU** 取 channel `external_item_id`；一个内部 SKU 挂多个 Listing 时无法唯一归属 ⇒
   **留空不摊**（不猜、不按比例硬摊）。

### 验证（车道侧真实输出）

- L0：`backend && ./mvnw -q compile` → exit 0；`pnpm typecheck` → 3/3 successful；`pnpm lint` → 3/3 successful
- L1：`./mvnw -pl services/reporting -am test -Dtest='ProfitReport*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` → **Tests run: 17, Failures: 0, Errors: 0**
- 前端：`pnpm vitest run apps/web/src/components/analytics/profit-report-model.test.ts` → 20 条全绿
- 因改了共享白名单 `apps/web/src/lib/api-proxy.ts`，按规矩跑了**全量 allowlist 回归**
  `pnpm vitest run apps/web/src/app/api` → **264 文件 / 1492 条全绿**
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重生成，
  仅 `reporting.ts` + `reporting.openapi.json` 变化，其余 8 个服务逐字节未变 ⇒ 确认是生成物不是手改
- PG 容器：每个测试类一个 `postgres:16-alpine` Testcontainers 容器，surefire 顺序执行 ⇒
  同一时刻存活 1 个；`@AfterAll` 停库 + ryuk 回收，结束前 `docker ps -a | grep postgres:16` **为空**

### commit

分支 `feat/13-4-1-1`，4 个原子 commit（**未 push**，push 由调度器在 L2 绿后统一做）：
`bef6cd86` 后端 → `d41399ca` 契约 → `2c937cb6` 前端 + 白名单 → `a5e1f083` 台账 + 会话日志。
`lane.mjs land --lane 13.4.1.1` → `{"ok": true, "lane": "13.4.1.1", "head": "a5e1f08"}`。
`git merge-tree --write-tree integration/module-dev feat/13-4-1-1` → 无 CONFLICT。

### 静态预检（并入后在集成头上复核）

对 `V398__profit_report.sql`：① `ADD COLUMN` 无 `DEFAULT` → **零命中**；
② `SET NOT NULL` → **零命中**；③ `ADD CONSTRAINT…CHECK` → **零命中**。
⇒ 本批新迁移**新增表、零 ALTER 既有表**，互相破坏形状不可能发生。

---

## 待补：模块 13.4.2.1 成本结构（⏳ 在途）

车道 `feat/13-4-2-1`、迁移 **V399**、服务 `reporting`，派发时上游已核对为已交付
（`12.4.2` 成本分摊、`5.2.1.1` 成本构成 FIRST_MILE、`5.2.3.1/5.2.3.2` 平台费与头程费、
`12.3.1.2` 结算明细、`13.4.2.2` 费用差异）。
落地后本文件追加该模块的「功能 / 迁移 / 端点 / 页面 / 测试 / 关键设计决策」一节，
并由下一轮统一跑**批次 L2**。

---

## 涉及文件

- `backend/services/reporting/src/main/resources/db/migration/V398__profit_report.sql`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProfitReportController.java`（249 行）
- `.../ReportingProfitReportModels.java`（341 行）· `.../ReportingProfitReportPort.java`（201 行）
- `.../ReportingProfitReportService.java`（684 行）· `.../ReportingProfitReportRefreshService.java`（798 行）
- `backend/services/reporting/src/test/java/com/starcon/reporting/ProfitReportIntegrationTest.java`（465 行）
- `.../ProfitReportTenancyIntegrationTest.java`（350 行）· `.../ProfitReportRefreshIntegrationTest.java`（435 行）
- `apps/web/src/app/analytics/profit/profit-report/page.tsx`
- `apps/web/src/components/analytics/profit-report-model.ts` · `profit-report-workspace.tsx` · `profit-report-model.test.ts`
- `apps/web/src/lib/api-proxy.ts`（新增独立 `services[]` 元素，+35 行）
- `packages/api-contracts/src/contracts/reporting.ts` · `packages/api-contracts/src/reporting.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（13.4.1.1 行：状态 → 已交付 + 证据）
