# 2026-10-21 6.5.2.1 采购分析（卷 06 / L3 锚点 6.5.2）

## 决策摘要

交付蓝图 06 卷 6.5.2.1「采购分析」（trade 服务，阶段 D6，L3 锚点 `/purchase/analytics`）：
采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、采购成本占销售额比、
按类目/店铺维度的采购效率 —— 六条指标全部由**真实数据**算出，每条都能下钻到具体单据行，
并给出可流式导出的明细 CSV。

- **迁移**：`trade V251__purchase_analytics.sql`，三张新表（事实台账 / 同步留痕 / SKU 维度快照），
  零 ALTER 既有表、零跨库 FK 与视图 ⇒ 跨车道 DDL 回归面 = 0。
- **后端**：`PurchaseAnalyticsController` / `Service` / `Models` + 两个授权只读端口
  （catalog 的类目与汇率、warehouse 的收货单），`/trade/purchase-analytics` 下 14 个端点。
- **前端**：`/purchase/analytics` 真实工作区 + `purchase-analytics-model.ts`（9 例 vitest）。
- **测试**：`PurchaseAnalyticsIntegrationTest` 14 例 + `PurchaseAnalyticsTenancyIntegrationTest` 5 例，
  全部实跑 Testcontainers PostgreSQL 16。

### 依赖自检结论（开工前 10 分钟）

逐条复核了任务书给的事实，**全部成立**，没有 BLOCKED：

| 上游 | 状态 | 本模块怎么用 |
| --- | --- | --- |
| 6.3.2.2 到货与逾期（trade，V235） | 已交付 | 采购金额 / 供应商 / 单价 / 币种 / 承诺到货日 / 准时与否 |
| 6.2.1.3 采购计划（trade，V242） | 已交付 | SKU / 计划量 / 期望到货日 / 目标仓 / 大促店铺（**无金额**） |
| 8.2.1.1 收货单（warehouse） | 已交付 | 实收量（授权只读 HTTP 端口，两道 RLS） |
| 11.2.3.3 供应商索赔（trade，V188） | 已交付 | 已生效的采购成本冲减 |
| 5.2.2.2 汇率台账 + 3.1.2.1 SKU 主数据（catalog） | 已交付 | 汇率快照 + 类目 + 单位换算（授权只读端口） |
| 6.4.1.1 加工单（trade，V97） | 已交付但**没有金额字段** | 委外加工费不计入采购金额（见下） |
| **6.3.1.x 采购单原单** | **未交付** | 不建影子表；下钻终点是到货跟踪单 / 收货单 / 计划行 |
| **6.4.2.1 质检单** | **未交付**（同批并行） | 质检扣量与不良成本不进金额口径 |
| **13 利润与经营分析** | **未交付**（无对应服务） | 下游显式标未交付 |

## 关键 trade-off

1. **采购成本占销售额比：显式标「算不出」，而不是造一个假比率。**
   分子（采购成本）照算并按币种返回；分母 `available=false` + `ownerModule=13 利润与经营分析`。
   理由：本仓**没有区间销售额的可用来源** ——
   ① 13 没有对应服务；② 5.3.1.1 利润测算器是**单 SKU 情景测算器**（入参是手工假设的售价与费率），
   拿它当区间销售额就是把假设当实绩；③ trade 本库 `sales_order` 只有 DRAFT / SUBMITTED 的
   手工销售单，未确认收入，汇总它就是伪造销售额。
   ⇒ 比值是「无数据」，前端显式显示归谁管与影响，13 落地后端点直接亮起、前端不用改。

2. **汇率快照在物化那一刻冻结，而不是读时现查。**
   `fx_rate` / `fx_rate_id` / `fx_rate_effective_date` 随事实行一起落库 ⇒ catalog 之后改汇率，
   历史报表的数字不跟着漂。取不到汇率 ⇒ `fx_status='UNSET'` 且 `base_amount` 为 **NULL**
   （排除在本位币合计之外），**绝不按 1:1 折算**（DB 层 CHECK 钉死）。

3. **跨币种不合并，多币种时汇总不给总数。**
   金额按币种分列；`purchaseAmountTotal` 只在单一币种时给出，多币种时是 null 而不是加出来的假数。
   集中度、HHI、价格波动全部**同币种内**比较。

4. **准时率的分母是「承诺到货行」，不是「收货行」。**
   分母 = 承诺到货日落在窗口内、**且已发生过到货事实**的到货跟踪行；行内任一次到货晚于承诺日
   ⇒ 该行判 LATE；承诺日当天不算晚（与 6.3.2.2 的 UTC 日历日口径同源）；
   承诺在窗口内但至今未到货的行**单列**（PENDING），不进分母。
   口径原文写在响应的 `denominatorRule` / `calendarRule` 里。

5. **价格波动是环比，不是同比**：本期 = 窗口末月，上期 = 它的上一个自然月；
   上期无数据时状态 `NO_PREVIOUS_DATA`、变动率给「无数据」而不是 0。
   维度默认按**供应商** —— 到货跟踪是**单级**事实（一张采购单一行，不分 SKU），
   采购单原单未交付 ⇒ SKU 维度的价格波动在当前交付状态下不可得（这一点写进了能力声明）。

6. **店铺维度只给计划侧口径，不给达成率。**
   收货单不带店铺 ⇒ 实收量无法按店铺归属 ⇒ 分子按店铺归属不了时给比率就是编数，
   所以店铺维度状态是 `PLAN_ONLY`，只有计划量 / 计划行数。
   类目维度才有达成率（分子 8.2.1.1 实收量 ÷ 分母 6.2.1.3 计划量），
   且口径写明这是 **SKU × 窗口的履约近似**（采购单原单未交付 ⇒ 无单据级关联）。

7. **同步幂等靠 `fact_key` + `ON CONFLICT … DO UPDATE … RETURNING (xmax = 0)`。**
   PG 里新插入的行 `xmax` 为 0、被 upsert 更新的行不为 0 ⇒ 一次往返就能分清「新增」与「更新」，
   同步计数是真的（重跑一次不会把「更新」说成「新增」，金额也不会翻倍）。

8. **不建 SKU 影子表，只冻结两个维度。**
   `purchase_analytics_sku_dimension` 只存**本模块做报表需要的**类目 + 采购单位箱规，
   且只在解析成功时落库；解析不到就标 NOT_FOUND / UNAVAILABLE，**不猜类目**。

9. **委外加工费不进采购金额**：6.4.1.1 加工单已交付但 `processing_order` **没有单价 / 币种字段** ——
   不是漏了，是上游没有金额事实。能力声明里写明这一条。

10. **api-proxy 走「新增一条 services[] 元素」这条路**，不往既有 trade 巨型 alternation 里并分支
    （结构上必然撞 git 合并冲突）。插入锚点固定在 `// 5.2.3.2 物流与头程费用（catalog 服务…）`
    之前（基线行号 4519）。动手前用脚本逐条核对本文件里**全部 100 条既有正则**：
    `purchase-analytics/**` **零命中**；并入后仅本条新增元素命中 15 个真实形状。

## 文件清单

### 后端（新增）

- `backend/services/trade/src/main/resources/db/migration/V251__purchase_analytics.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseAnalyticsModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseAnalyticsService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseAnalyticsController.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseAnalyticsCatalogPort.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseAnalyticsWarehousePort.java`

### 后端（测试，新增）

- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseAnalyticsIntegrationTest.java`
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseAnalyticsTenancyIntegrationTest.java`

### 前端（新增）

- `apps/web/src/app/purchase/analytics/page.tsx`
- `apps/web/src/components/purchase/purchase-analytics-workspace.tsx`
- `apps/web/src/components/purchase/purchase-analytics-model.ts`
- `apps/web/src/components/purchase/purchase-analytics-model.test.ts`

### 共享文件（按车道纪律只改自己那一处）

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**
  （`prefix: 'trade/'` + 自己的 `purchase-analytics` 正则，插入锚点 = 5.2.3.2 那条之前）；
  既有 100 条元素一字未动。
- `packages/api-contracts/src/contracts/trade.ts` / `trade.openapi.json` / `index.ts` ——
  codegen 重生成（新增 26 个 `PurchaseAnalytics*` 类型 + `StreamingResponseBody` 桶文件消歧义）。
  `contracts/catalog.ts` 与 `catalog.openapi.json` 的**纯排序漂移已回退**（不是本车道的事实）。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.5.2.1 那行的「状态」「证据」两格，
  统计列由 `node tools/module-progress/sync.mjs` 重算（已交付 266 → 267）。

## 验证

- L0 编译闸：`./mvnw -q -pl services/trade -am compile` ✅；`pnpm typecheck` ✅（3/3 任务绿）。
- L1 模块闸：`./mvnw -pl services/trade -am test -Dtest='PurchaseAnalytics*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ **Tests run: 19, Failures: 0, Errors: 0, Skipped: 0**（集成 14 + 租户隔离 5）。
  Testcontainers 侧 Flyway 日志确认 `Successfully applied 58 migrations ... now at version v251`（V251 真实落库）。
- 前端：`pnpm vitest run apps/web/src/components/purchase/purchase-analytics-model.test.ts` ⇒ 9 例全绿；
  `pnpm vitest run apps/web/src/app/purchase apps/web/src/components/purchase` ⇒ **7 文件 / 107 例全绿**；
  `pnpm vitest run apps/web/src/app/api` ⇒ **125 文件 / 724 例全绿**（改了 api-proxy.ts 必跑的全量 allowlist 回归）。
- api-proxy 锚点核对：既有 100 条正则对 `purchase-analytics` 命中数 = **0**（脚本 `.tmp/api-proxy-zero-hit.mjs`，
  照抄 `api-proxy.test.ts` 的正则抽取与 fallthrough 判定方式）。

## 后续待办

1. **13 利润与经营分析交付后**：`GET /trade/purchase-analytics/cost-to-sales-ratio` 需要接上真实销售额分母
   （当前是 `available=false`）。本模块的分子、币种分列与汇率快照已经就位，届时只需补分母并把
   `available` 翻成 true，前端不用改。
2. **6.3.1.x 采购单原单交付后**：① 下钻可从事实行跳到采购单原单；
   ② 价格波动可增加 SKU 维度（当前到货跟踪是单级事实，做不到）；
   ③ 类目维度的履约近似可升级成单据级达成率。
3. **6.4.2.1 质检单交付后**：质检扣量与不良成本要进金额口径（事实表已留 `source_type` 位），
   届时需重算一次「毛额 → 净额」口径并同步调整 `basis` 文案。
4. **6.5.1.1 采购对账交付后**：对账单与本模块的采购成本口径应当共用同一套汇率快照与币种分列规则，
   否则两边对不上。当前本模块的 `capabilities` 已把 6.5.1.1 标成未交付，页面不代它开形状。
5. **口径治理**：本模块的 `unit_factor` 对到货流水恒为 1（6.3.2.2 不记单位），
   采购单原单落地后应把单位与箱规补录进事实行。
