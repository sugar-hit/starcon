# 2026-10-29: 13.4.1.2 SKU 利润 —— SKU 级净利、构成瀑布与订单级钻取（阶段 D7，L3 13.4.1 的第二个模块）

> 车道 `feat/13-4-1-2`（从集成头 `integration/module-dev` 切出）。
> 蓝图：`docs/product/capability-blueprint/13-profit-and-analytics.md` → 13.4.1.2「SKU 利润」。
> **本车道是 13.4.1.1 多模式利润报表的兄弟模块**：那边按**经营模式**给利润报表，这边给 **SKU 级**利润明细。
> ⚠️ 本车道前两个 worker 均因**传输层错误**被中途打死（`ERR_CONNECTION_CLOSED` / `ERR_HTTP2_PING_FAILED`），
> **代码本身无缺陷**；本轮为纯机械收尾（跑闸 + 台账 + 会话日志 + commit + land），**未改任何业务代码**。

## 摘要

| 项 | 值 |
| --- | --- |
| 模块 | 13.4.1.2 SKU 利润（卷 13 L3 13.4.1 利润报表 → 第二格） |
| 迁移 | `V400__sku_profit.sql`（**一张**新表，零 ALTER 既有表） |
| 端点 | 5 个，挂 `/reporting/sku-profit` |
| 页面 | `/analytics/profit/sku-profit` |
| 测试 | 后端 12 例（9 口径 + 3 租户隔离）· 前端 28 例（23 model + 5 allowlist）· allowlist 全量回归 71 文件 / 394 例 |
| L0 | `./mvnw -q compile` exit 0；`pnpm typecheck --force` 3/3 successful（cache bypassed） |
| L1 | `SkuProfit*` ⇒ **12 例全绿**（Failures 0 / Errors 0）BUILD SUCCESS；前端本模块 28 例全绿 |
| 契约 | codegen 产物，**纯增量**（reporting.ts + reporting.openapi.json，+10 `SkuProfit*` schema / +5 path / 丢失 0） |

## 决策摘要

### ① 六项成本**只读** 13.4.1.1 的投影表 —— 不复制、不改那张表

单品收入 / 采购成本 / 头程 / 平台费 / 物流费 / 广告费这六项事实**已经在** V398 的
`reporting_profit_fact` 里（那张表带 `sku_code / spu_code / msku_code / order_ref` 四个 SKU 级维度列）。

⇒ 本模块**不新建第二张存同一笔钱的表**，也**不改**那张表：SKU 利润就是同一张表**按 SKU 维度再读一次**。
**口径归本模块，事实归 13.4.1.1** —— 这是 13.4.1.1 会话日志「后续待办 1」约定好的分工。

**取舍**：不复制省掉了「两张表漂移」的长期风险，代价是本模块的读模型被上游表的形状绑住
（上游不落 SKU 维度的成本 ⇒ 本模块那一格只能是「不可归属」）。

### ② 退款损失**单独一张表**，而不是扩 `reporting_profit_fact.component` 的值域

看上去最省事的是 `ALTER TABLE ... DROP CONSTRAINT ... ADD CONSTRAINT` 把值域放宽
（新值域是旧值域的超集，既有行仍满足，确实不破坏别人的 INSERT）。**刻意不做**，两条理由：

1. **扩值域会让 13.4.1.1 的毛利口径静默漏掉退款** —— 13.4.1.1 的成本项词表里没有
   `REFUND_LOSS`，它按自己的词表逐项累加成本合计 ⇒ 退款落进那张表后，13.4.1.1 的毛利会
   **原样不算这笔钱**（**不是报错，是算错**），而它的毛利是 5 定价的下游输入。两套口径不一致，
   正是蓝图关系句警告的「三方都不认账」。
2. **一笔钱只有一个写入所有者** —— 退款损失这笔钱在 `reporting_profit_fact` 里**压根不存在**
   （13.4.1.1 的职责句没有「退款损失」），本模块是它在**本仓的唯一写入所有者**。

⚠️ **代价必须写清楚**：退款损失与其它六项成本**不在同一张表** ⇒ 净利要**跨两张表读**，
报表的口径说明里已写明这一点（「读一张表会漏算退款」是最隐蔽的一类错账）。

### ③ 退款损失口径：什么算损失 + 挂到哪个 SKU

- **什么算损失**：只有 `application_status = 'SUCCEEDED'` 的退款申请单才算
  （DRAFT / PENDING_APPROVAL / EXECUTING 都不算 —— **申请了不等于退了**）。
- **挂到哪个 SKU**：退款申请单挂在**订单**上不挂 SKU ⇒ 需售后单的**涉及行**
  （`aftersales_case_line`）才有 SKU。**只在「退款金额与该售后单涉及行的申请金额之和
  逐字相等」（scale 归一后 `compareTo == 0`）时才按行分摊** —— 那不是分摊，是同一笔钱的行级拆分。
  **对不上就不挂到任何 SKU**（部分退款 / 上游改了金额）⇒ 摊下去就是编造，宁可让那一格显示
  「—（不可归属）」并如实报数量。
- **币种**：一律 `char(3)` + `^[A-Z]{3}$`，**绝不跨币种相加**。

### ④ 「算不出来」与「等于 0」分开 —— 且分**四种**取值状态

| 状态 | 含义 | 页面 |
| --- | --- | --- |
| `AVAILABLE` | 有 SKU 级事实，取得到值 | 金额 |
| `MISSING` | 上游在 SKU 维度上有这类成本（别的 SKU 有），**这个 SKU 没有** ⇒ **算不出来**（不是 0） | 「—」+ 原因 |
| `NOT_ATTRIBUTABLE` | 上游有这笔成本，但只分摊到**店铺 / 订单**，**SKU 维度拿不到** ⇒ **本模块不重算分摊** | 「不可归属到 SKU」+ 原因 |
| `NOT_APPLICABLE` | 本租户在这个窗口内**压根没有**这类成本 ⇒ 不参与净利计算 | 「—」+ 说明 |

净利缺任何一项成本 ⇒ `null` + 一句原因（页面「**—（不可比）**」），**不是 0.00** ——
假 0 会被读成「完全没花钱」。

**为什么 `MISSING` 与 `NOT_ATTRIBUTABLE` 必须分开**：两者都让净利不可比，但**原因不同** ——
前者是「这一项本来就没有」，后者是「这一项在别处，要上游改分摊维度」。

### ⑤ 多币种绝不相加 + 跨模式口径要标出来

- 一行 = 一个 SKU × 一个币种，页面**不提供**跨币种净利合计（`sku-profit/summary` 在
  api-proxy 里**刻意不放行** —— 后端没有这个映射）。
- **跨模式口径**：一个 SKU 在**多个经营模式**下有销售、而 SKU 级成本不落经营模式 ⇒ 那些行的净利是
  **跨模式口径**（逐模式收入构成见每行的「经营模式构成」），页面**显式标出**而不是混成一行。
  混成一行会让人以为成本也分到了模式上。

### ⑥ 数据更新时间取**两张表**较晚者，没投影过是空

`reporting_profit_fact` 与 `reporting_sku_profit_refund_fact` 都有 `updated_at` ⇒ 取较晚者。
**没投影过时是空**，**不是哨兵值**（`1970-01-01` 会被读成「很久没更新了」）。
卷 13 卷首加粗句「数据延迟要显示」⇒ 该字段在页面**常驻**。

### ⑦ 命名空间与白名单：新增**独立**条目，既有条目一字未动

- 端点挂**新增的** `/reporting/sku-profit`（**不**并进兄弟模块 13.4.1.1 的 `profit-report/`）。
- api-proxy **新增一条独立 `services[]` 元素**（`prefix: 'reporting/'` + 自己的正则），
  锚在 13.4.1.1 `profit-report` 那条**正下方**；既有条目**一字未改、不重排、不删除**。
- **首 token「sku-profit」零命中核对是实测的**（不是目测）：本文件 17 条既有 `reporting/` 正则的
  首 token 里**没有一条能吃下 `sku-profit`** ⇒ 两两不相交，不会挤掉兄弟车道已交付的路由。
- **刻意不放行**：裸路径 `sku-profit`（后端没有这个汇总接口）/ `sku-profit/{uuid}`（顶层无 id 段，
  SKU 利润按查询参数定位）/ `sku-profit/summary`（跨币种合计不存在）/ `sku-profit/profit-report`
  （兄弟模块是另一条命名空间）—— 放行后端不存在的映射**比 404 更糟**（界面会以为功能已交付）。

## 文件清单

**迁移**
- `backend/services/reporting/src/main/resources/db/migration/V400__sku_profit.sql`

**后端**（`backend/services/reporting/src/main/java/com/starcon/reporting/`）
- `ReportingSkuProfitModels.java` —— 读模型契约（`Component` 七项词表 + `Availability` 四种取值状态 + 各响应 record）
- `ReportingSkuProfitPort.java` —— 跨服务只读端口（9/11 售后域两路：退款申请单 + 售后单详情，**转发调用方 bearer、不传租户号**）
- `ReportingSkuProfitRefreshService.java` —— 上游拉取适配器 + 幂等 upsert（含「金额逐字相等才按行分摊」的判定）
- `ReportingSkuProfitService.java` —— 读模型（利润表 / 瀑布 / 订单钻取 / 能力声明 / 数据更新时间）
- `ReportingSkuProfitController.java` —— 5 个端点，命名空间 `/reporting/sku-profit`

**测试**（`backend/services/reporting/src/test/java/com/starcon/reporting/`）
- `SkuProfitIntegrationTest.java`（9 例：口径）
- `SkuProfitTenancyIntegrationTest.java`（3 例：租户隔离三断言）

**前端**
- `apps/web/src/components/analytics/sku-profit-model.ts`
- `apps/web/src/components/analytics/sku-profit-model.test.ts`（23 例）
- `apps/web/src/components/analytics/sku-profit-workspace.tsx`
- `apps/web/src/app/analytics/profit/sku-profit/page.tsx`
- `apps/web/src/app/api/reporting/sku-profit-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，锚在 13.4.1.1 `profit-report` 那条之后）

**契约 / 台账**
- `packages/api-contracts/src/contracts/reporting.ts`、`packages/api-contracts/src/reporting.openapi.json`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（只改 13.4.1.2 那一行的「状态」「证据」两格）

## 验证记录

- **L0 编译闸**：`./mvnw -q compile` exit 0；`pnpm typecheck --force` ⇒ `3 successful, 3 total`（cache bypassed）
- **L1 后端**：`./mvnw -pl services/reporting -am test -Dtest='SkuProfit*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS（Flyway 16 个迁移应用到 `v400`）
- **L1 前端**：`pnpm vitest run` 本模块两个文件 ⇒ `Test Files 2 passed / Tests 28 passed`
- **allowlist 全量回归**：`pnpm vitest run apps/web/src/app/api/trade` ⇒ `Test Files 71 passed / Tests 394 passed`
  （共享白名单累积正则，取错一侧会**静默删掉别人已交付的路由**，页面 404 而其它检查全绿）
- ⚠️ 未跑 `./mvnw verify` 全量、未跑 `pnpm --filter @starcon/web build`（**由调度器统一跑**）

## 后续待办

1. **SKU 维度的成本分摊仍是缺口**（13.4.1.1 会话日志的待办 2 同源）：上游 12.4.2.2 把费用池分摊到
   **店铺 / 订单**维度时，SKU 维度拿不到那一格 ⇒ 那些 SKU 的净利**不可比**。本模块**不重算分摊**
   （重算就会出现第二个真相）；要让 SKU 级净利成立，需要上游按 **SKU 维度**分摊
   （`target_dimension` 已支持 `SKU`，是**配置**问题不是代码问题）。
2. **OTHER_COST（其它成本）不在 13.4.1.2 职责句里** ⇒ 归 13.4.1.1，本模块不报；
   若后续要报，必须同时定「谁才是它的写入所有者」。
3. **刷新覆盖率常驻**：当前数据更新时间只有一个时刻，没有「每一路上游最近一次刷新的条数」。
   可考虑加一张刷新状态表（13.4.1.1 同款待办）。
4. **退款单逐单取售后明细是 N+1**：一次刷新逐条取 `aftersales/cases/{id}`。当前有批量上限保护，
   但触顶时**如实报 TRUNCATED** —— 超出部分**没有退款损失**（不是退款损失为 0）。
5. **12.3.1.2 结算明细等上游若未投影**，平台费 / 物流费 / 广告费会显示「算不出来」（不是 0），
   能力声明里已写明原因与去处。
