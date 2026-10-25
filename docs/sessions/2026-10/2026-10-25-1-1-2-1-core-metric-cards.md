# 2026-10-25: 1.1.2.1 核心指标卡（卷 01 工作台与门户 L3 1.1.2 关键指标第一格）

## 摘要

蓝图的「一屏展示今日/昨日/本月的销售额、订单量、销量、库存总额、毛利、回款、在途金额，每张卡带环比与同比，点击下钻到对应报表」—— 落成 **reporting 服务**上的一个**只读投影读模型**（不是新起服务、不是新起表）。

**迁移：无（V383 特意留空未占用）。** 卷 01 卷首写死「不产生新的业务事实，只聚合各系统的投影」「工作台只读 + 跳转」；派生值落库只会比它描述的经营数字更早过期 ⇒ 不新建任何表、不占 Flyway 版本号（reporting 服务本车道只有既有的 V376 / V379 / V381）。

本车道分两轮跑完：第一轮（`56d60ce4` / `920ab9be`）交后端骨架 + 读模型 + 集成测试与多租户三断言；第二轮因**会话传输层故障**（`ERR_CONNECTION_CLOSED`，非代码问题）被杀，成果全部留在 worktree 里，第二轮从「钉住存量成果 → 补 api-proxy → 前端收尾 → 验证 → 台账 → 落地」续跑完成。

## 决策 / 结论

1. **🔴 七张卡的口径来源逐条不同，不能一把尺子量完。**
   5 张走本服务自己的投影（`reporting_metric_fact`，口径取 `reporting_metric_definition`）：销售额 / 订单量 / 销量 / **毛利** / 库存总额；1 张走**跨服务授权只读 API**（回款取 `GET /finance/receipts`，12.2.1.1 收款单 —— **只引用上游算好的按币种合计，不把收款单拉回来自己加**）；1 张**未接通**（在途金额）。

2. **🔴 未接通的指标「不给公式也不给分母」。**
   在途金额在 `capabilities` 里报 `available=false` + `ownerModule` =「06 采购在途（trade）/ 07 库存在途（warehouse）」。两个理由：① 口径表里没有这个指标码（口径唯一，没登记就没有这个指标）；② 上游在途事实只给**数量**不给金额 —— 把数量乘一个成本价，就是**在代码里新写一条指标公式**，那会出现第二个真相。给了分母就会让界面上的数字变成「看起来有依据的假数」。

3. **🔴 权限码沿用 `reporting.overview.read`，不另起一个。**
   本模块读的就是 13.1.1.1 那套投影读模型（同一批表、同一套口径、同一份 RLS）；另起一个码会让「谁能看经营指标」出现两个答案，而新码还要去 identity 登记（需要另一个服务的迁移版本号，本车道没有分配）。

4. **🔴 「算不出来」与「等于 0」在三个层面分开。**
   ① 基期没数据 ⇒ 变化率 `null` ⇒ 页面显示「**无同期基期**」，不是 0% / -100% / 「持平」；② 未接通 ⇒ 灰格「**未接通**」，不是 0；③ 多币种折不出本位币合计 ⇒ 显示「**按币种分列**」，界面上永远没有跨币种的「总计」。

5. **🔴 窗口边界按本租户时区算，不写死 UTC。**
   「今日/昨日/本月」的边界取自本租户投影事实的**分桶时区**。集成测试把固定时钟放在 UTC 的 10-25 与 Asia/Shanghai 的 10-26 的**不同日期**上 ⇒ 任何写死 `timezone='UTC'` 的实现会直接红（13.1.1.2 就栽在这条上）。

6. **🔴 前端不重算任何比率。**
   变化率与趋势箭头**全部由后端算好**（口径在 13 的 `reporting_metric_definition`），前端一个减法都不做；口径 tooltip **原样取后端**（前端自己编一句就是第二个真相）。

7. **🔴 展示层走十进制字面串 + BigInt 缩放整数，不走浮点运算。**
   金额是 `numeric(19,4)`、数量是 `numeric(19,6)`，而 JS 的 `number` 是二进制浮点。所以本模块**不做任何算术**，展示时把 number 还原成**十进制字面串**，用 BigInt 在缩放整数上做 HALF_UP 四舍五入与千分位分组。实测：`1.005` ⇒ `1.01`（按 double 四舍五入会得到 `1.00`）。精度天花板在**契约**上（`value: number`）—— 那是 OpenAPI 的既有形状，不在本车道改。

8. **🔴 api-proxy 必须新增一条独立元素。**
   发车前用脚本把基线**全部 240 条**既有 `services[]` 元素逐条按 `resolve()` 的**同构口径**（`path.startsWith(prefix)` 收窄 → 再测 `pattern.test(path.slice(prefix.length))`）实测：本模块 5 个形状**全部零命中**；既有 `prefix === 'reporting/'` 恰好 **3 条**，正则都是**按命名空间精确匹配**的（`overview|projection` / `expense-variance` / `store-comparison`），**不覆盖 `metric-cards`** ⇒ 必须新增独立条目，不能并进既有分支。实测改动量：**纯新增 44 行、零删除、零重排**，插入锚点选在那三条之后（与同批 1.3.2.1 插的 `identity/` 那条相距很远，并入时 git 能干净求并）。

9. **🔴 刻意不放行的形状**：`metric-cards/{uuid}`（顶层没有 id 段）、`metric-cards/refresh`（**本模块没有写端点**，卷 01 只读）、`metric-cards/unknown-sub`。放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付。

10. **工作台卡片注册表：登记自己那一条。**
    `metric.core-metric-cards` → `/dashboard/metric-cards`，只新增这一条，既有条目一字不动、不重排。连带同步 1.4.1.1 那个**累积型固件**里两处硬编码的卡片 key 清单（注册表是累积型的，加一张卡必然让那两处清单变长 —— 不改它 CI 直接红）。注册表自己的纪律是「只登记**已交付、真的能打开**的页面」，本模块现在满足。

## 涉及文件

**后端（reporting 服务，本车道新增）**

- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingMetricCardController.java` —— 2 个 GET 端点挂在 `/reporting/metric-cards`
- `.../ReportingMetricCardService.java` —— 卡片求值、对比、口径折算、下钻
- `.../ReportingMetricCardCatalog.java` —— 七张卡的清单（系统事实）
- `.../ReportingMetricCardModels.java` —— 契约形状
- `backend/services/reporting/src/test/java/com/starcon/reporting/ReportingMetricCardIntegrationTest.java`（14 例）
- `.../ReportingMetricCardTenancyIntegrationTest.java`（3 例，多租户三断言）
- **迁移：无（V383 留空未占用）**

**前端**

- `apps/web/src/app/dashboard/metric-cards/page.tsx` —— 页面路由
- `apps/web/src/components/dashboard/metric-card-model.ts` —— 前端模型（取数 / 格式化 / 口径文案）
- `apps/web/src/components/dashboard/metric-card-model.test.ts`（30 例）
- `apps/web/src/components/dashboard/metric-card-workspace.tsx` —— 工作区组件（加载 / 错误 / 空三态）
- `apps/web/src/app/api/reporting/metric-cards-allowlist.test.ts`（7 例，白名单双向断言）
- `apps/web/src/lib/api-proxy.ts` —— **纯新增一条** `reporting/metric-cards` 元素（+44 行，−0 行）
- `apps/web/src/components/dashboard/workbench-card-registry.ts` —— **只新增一条** `metric.core-metric-cards`
- `apps/web/src/components/dashboard/workbench-layout-model.test.ts` —— 累积型固件的两处硬编码清单同步

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/contracts/reporting.ts`
- `packages/api-contracts/src/reporting.openapi.json`

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 1.1.2.1 那一行的「状态」「证据」两格（+ `sync.mjs` 自动刷的派生计数）
- `docs/sessions/2026-10/2026-10-25-1-1-2-1-core-metric-cards.md` —— 本文件

## 关键 trade-off / 讨论

1. **后端建服务还是前端聚合？** 选**后端建端点 + 前端只读展示**。理由：七张卡里有一张（回款）要跨服务授权调 12 财务、一张要在途金额要如实报「未接通」，这些判断放在前端聚合层会让「谁能看经营指标」的判定散到多个页面；而且工作台是**聚合层**，但**口径归属仍然要指向各系统的真源**。

2. **迁移版本号用不用？** 不用。卷 01 是只读投影层，**不产生新的业务事实**；派生值落库只会比它描述的经营数字更早过期 ⇒ V383 留空。这与 1.1.1.1 / 1.1.3.1 / 1.2.2.1 / 1.4.2.1 是同一条纪律。

3. **在途金额给不给个「大概数」？** 不给。两个「大概」选项都被否：数量 × 成本价 = 在代码里新写一条公式（第二个真相）；用库存总额减去可用量 = 跨系统口径混算。宁可显示「未接通」并写明归谁。

4. **多币种要不要给个合计？** 不给，除非后端能按**汇率快照**折算到本位币。缺任一汇率就是不可判定，**不是按 1:1 算**（1:1 会把一个错数显示得很像真的）。

5. **契约里的金额用 number 还是 string？** 沿用 `number`（与既有 `CurrencyValue` / `ExpenseSkuVarianceRow` 等全部兄弟端点一致）。改成 string 会波及整个 reporting 契约与所有既有前端；本模块的补偿是**前端不做任何算术** + 展示走十进制字面串。

6. **台账 1.4.1.1 那行里「1.1.2.1 核心指标卡……尚未交付 ⇒ 不登记」这句话现在过时了。** 本车道**没有**改它 —— 台账规则是「只改自己那一行」，1.4.1.1 那一行归它的主人。留给 1.4.1.1 的车道顺手刷新（见「后续待办」）。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `backend/./mvnw -q compile` | **exit 0**，BUILD SUCCESS |
| L1 模块闸 | `./mvnw -pl services/reporting -am test -Dtest='ReportingMetricCard*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **Tests run: 17, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS（`ReportingMetricCardIntegrationTest` 14 + `ReportingMetricCardTenancyIntegrationTest` 3，实跑 Testcontainers PostgreSQL 16） |
| 类型 | `pnpm exec turbo run typecheck --force` | **3 successful / 3 total**（绕过缓存实跑） |
| Lint | `pnpm lint` | **3 successful / 3 total**（只有别的模块的既有 warning，本车道零告警） |
| 前端单测 | `pnpm vitest run apps/web/src/components/dashboard/metric-card-model.test.ts` | **30 passed / 30** |
| 前端组件 | `pnpm vitest run apps/web/src/components/dashboard/` | **13 files / 329 tests passed** |
| **白名单全量回归** | `pnpm vitest run apps/web/src/app/api` | **253 files / 1429 tests passed** —— 未误伤 13.1.1.1 / 13.4.2.2 / 13.1.1.2 的 reporting 路由 |

**Testcontainers 资源竞争**：本车道 verify 期间**未出现**任何 `Connection refused` / `ApplicationContext` 加载失败。跑完后 `docker ps -a --filter ancestor=postgres:16-alpine` 只剩 **1 个**容器，创建于 **22:04:47**（本车道 22:04:39 已结束）—— 那是**调度者并发的 L2 verify**（`l2-verify/backend -pl services/identity -am verify`）的容器，**本车道自己的容器已被 ryuk 回收**。

## 后续待办

1. **1.4.1.1 台账那行有一句过时的话**：「（1.1.2.1 核心指标卡、1.1.3.2 资金与回款风险尚未交付 ⇒ **不登记**）」。本车道已交付 1.1.2.1 且已登记 `metric.core-metric-cards` ⇒ 这句话该由 1.4.1.1 的车道刷新（本车道按规则没碰它那一行）。
2. **1.1.3.2 资金与回款风险**仍未交付 —— 同一句话里的另一半仍然成立，1.4.1.1 刷新时别把它一起删掉。
3. **在途金额**的口径仍然未接通。要接它，得由 06 采购在途 / 07 库存在途**先提供带金额的在途事实**，并在 13 的口径表里登记指标码 —— **不是**在本模块里写一条公式。归属：06 / 07 的主人。
4. **回款的构成明细**未交付（本服务没有回款的构成明细行）—— 下钻指向 12.2.1.1 收款单报表页。归属：12 财务。

## 关联

- 蓝图：`docs/product/capability-blueprint/01-dashboard-and-portal.md`（1.1.2 关键指标 → 1.1.2.1 核心指标卡）
- 上游口径单点定义：13 的 `reporting_metric_definition`（`reporting_metric_fact` / `reporting_drilldown_fact`）
- 复用的既有能力：13.1.1.1 经营总览的构成明细端点 `GET /reporting/overview/drilldown`；12.2.1.1 收款单的 `GET /finance/receipts`
- 台账：`docs/product/capability-blueprint/module-progress.md` 的 1.1.2.1 行
