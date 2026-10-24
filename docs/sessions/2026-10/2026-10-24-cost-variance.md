# 12.4.1.3 成本差异分析（V362 · 阶段 D6 · 车道 12.4.1.3）

> 蓝图原文：`docs/product/capability-blueprint/12-finance.md` 的 **12.4.1.3 成本差异分析**
> （「分析成本差异：实际成本 vs 预算/标准成本、采购价波动、头程成本波动、损耗差异、汇率影响，
> 定位利润波动主因。输入：成本计算结果；预算或历史基线；价格与汇率历史。
> 输出：差异分析报表 + 主因归因 + 异常提示。关系：上游 = 12.4.1.2；下游 = 13 分析、6 采购。」）。
> 车道分支 `feat/12-4-1-3`，基线 `1c73d1e8`（`merge(12.5.1.2): 会计期间`）。
> 迁移版本 **V362**（调度者预分配）。

## 决策摘要

### 1. 最重要的一条：**报表落库，且一经生成不可改写**

12.4.1.2 的「差异对比」是纯 Σ，不落表；本模块**落库**（五张表）。理由是本模块的输入里除了
12.4.1.2 的结转金额，还多了一样**会随时间改变**的东西 —— **基线版本**与**汇率快照**。

不记录，就答不上「这张报表当时用的是哪一版基线、哪一档汇率」⇒ 差异分析会退化成
「现在重算一遍的数字」，而利润复盘要的恰恰是「当时算出来是多少」。

⇒ 落库 + **报表一经生成不可改写**（DB 触发器钉死；运行角色对报表四表**只授 SELECT / INSERT**）。

### 2. 「基线缺失 ≠ 差异为 0」—— 这条写进了数据层

本仓此前**没有**标准成本 / 预算表 ⇒ 基线**必须自建**（SKU 或类目 × 币种 × 生效区间 × 版本链，
手法照 12.6.1.1 的税率版本链）。

但**自建基线**不等于「所有 SKU 都有基线」。没有基线的 SKU，其差异是**不可计算**，
不是 0 —— 写 0 会被界面读成「这个 SKU 成本没有偏差」，而真相是「没有可比的东西」。

⇒ 五值可比性词表落库：`COMPARABLE` / `NO_BASELINE` / `CURRENCY_MISMATCH` /
`UPSTREAM_UNAVAILABLE` / `NOT_APPLICABLE`；`NO_BASELINE` 时金额**留空**（CHECK 钉死
`COMPARABLE ⇒ amount NOT NULL`）。

### 3. 五类差异**不可相加** —— 报表里刻意没有「五类之和」

| 类别 | 性质 |
| --- | --- |
| 标准成本差异 | **总差异**（实际 − 标准，同币种） |
| 汇率影响 | 总差异的**构成部分** |
| 采购价波动 / 头程成本波动 / 损耗差异 | **独立观察到的驱动因素** |

把汇率影响和采购价波动加在一起会得到一个**没有财务含义**的数字。
⇒ 表里只给**贡献排名**与**主因**（该币种内按 |金额| 排名第一**且可计算**的那一类），
**不提供「五类差异合计」这一格**；全部为 0 或全都不可算时**没有主因**，不硬凑。

### 4. 多币种绝不相加；汇率取不到就报「汇率不可得」

报表**按币种分组**，组内金额只在同币种内相加；页面**不提供「全部差异合计」**。

汇率经 `ExchangeRatePort` / `CatalogExchangeRatePort`（`GET /catalog/exchange-rates/latest`，带 `asOf`）
真实读取期初 / 期末两档。**取不到一律报「汇率不可得」，绝不按 1:1 兜底** ——
1:1 兜底会把汇率影响算成 0，而 0 会被读成「汇率没有影响」。
（同币种时恒为 0 那是**恒等式**，不是兜底。）

### 5. 上游不可得**只降级那一类差异**，不拖垮整张报表

采购价 / 头程 / 库存数量走**授权 HTTP 只读端口**（`GET /trade/purchase-analytics/facts`、
`GET /warehouse/first-mile-shipments`、`GET /warehouse/inventory/ledger`），
**零跨库 SQL / FK / 视图**，**一条别人的表都不改**。

读不到 ⇒ 该类差异标 `UPSTREAM_UNAVAILABLE` + 生成告警，其余四类照常出数。
只有**基数**（12.4.1.2 的已完成批次）缺失才整体拒绝（400「没有已完成的成本计算批次」）——
没有基数就没有差异可言；没有某类上游数据只是那一类不可算。

## 关键 trade-off

| 选择 | 放弃了什么 | 为什么 |
| --- | --- | --- |
| 报表落库（5 张表） | 「纯现算」的简单 | 输入含基线版本与汇率快照，不落库就无法复现「当时算的是多少」；蓝图也明确要「报表」这一输出 |
| 报表一经生成不可改写 | 「刷新报表」的方便 | 差异分析是复盘证据，被 UPDATE 覆盖后就不再是证据；重算 = 另起一张 |
| 五类差异不相加、不给合计 | 「一个总数」的好看 | 汇率是总差异的构成部分，其余三类是独立驱动因素，相加得到的数没有财务含义 |
| 基线缺失标「不可比」而不是给 0 | 「表格填满」 | 0 会被读成「没有偏差」；不可比 + 原因才是不撒谎 |
| 汇率不可得不兜底 1:1 | 「总有数字可看」 | 兜底 1:1 会把汇率影响算成 0，等于说「汇率没影响」——最危险的一种撒谎 |
| 上游不可得只降级一类 | 「一次全算不出来就不出报表」 | 采购价读不到不影响标准成本差异；整张不出等于什么都没交付 |
| 头程成本波动只进 driver 表不进 item 表 | 「逐 SKU 看清头程」 | 头程是**单据级**（按头程发运单），强行摊到 SKU 上就是编一个不存在的分摊 |
| 采购价用 trade 的 `purchase-analytics/facts`（非 `/price-variance`） | 直接用现成的价差接口 | 那个接口是**自然月环比**口径，与本模块的**任意等长期间**口径不一致，混用会得出无法解释的数 |
| 类目维度基线的链键用 `CATEGORY:<编码>` 字符串 | 直接用类目 uuid | catalog 的 SKU 主数据只给类目**编码**没有 uuid；NULL 参与唯一键不可靠 |

## 口径备忘（后续模块要复用时看这里）

- **标准成本差异** = 实际成本 − 标准单价 × **出库量**（`issue_quantity`，不是结转量）
  ——「应该结转多少」。
- **采购价波动** = (本期加权均价 − 上期加权均价) × **结转量**（`covered_quantity`）——
  与标准成本口径**故意不同**（一个按应结转，一个按已结转）。
- **头程成本波动** = 本期头程发运总额 − 上期（等长期间）头程发运总额（单据级）。
- **损耗差异** = warehouse `ADJUSTMENT` 类流水的**负向** `onHandDelta` 合计 × 单位成本；
  「本期无损耗」是**事实 0**，「取不到单位成本」才是不可计算。
- **汇率影响** = 实际成本 × (1 − 期初汇率 ÷ 期末汇率)。
- **异常阈值**：`VARIANCE_ALERT_RATE = 0.20`（|差异| ÷ 标准成本 ≥ 20% 记 LARGE_VARIANCE）——
  **口径约定（可讨论）**，目前写死在服务层常量。

## 文件清单

**迁移**
- `backend/services/finance/src/main/resources/db/migration/V362__cost_variance.sql`

**后端**（`backend/services/finance/src/main/java/com/starcon/finance/`）
- `CostVarianceModels.java`（枚举 + 能力声明 + 基线 + 报表 + 明细 + 驱动因素 + 告警 + 输入）
- `CostVarianceService.java`（能力声明、选项、基线 CRUD + 版本链 + 版本对比、差异分析算法、报表读）
- `CostVarianceController.java`（12 个端点挂 `/finance/cost-variance`）
- `PurchasePricePort.java`（**新建**：trade 采购事实的跨服务只读端口，读不到即状态而非异常）

**测试**（`backend/services/finance/src/test/java/com/starcon/finance/`）
- `CostVarianceIntegrationTest.java`（29 条）
- `CostVarianceTenancyIntegrationTest.java`（7 条，三条隔离断言）

**契约**（codegen 产物，只由生成器改）
- `packages/api-contracts/src/contracts/finance.ts`
- `packages/api-contracts/src/finance.openapi.json`

**前端**
- `apps/web/src/components/finance/cost-variance-model.ts`
- `apps/web/src/components/finance/cost-variance-model.test.ts`
- `apps/web/src/components/finance/cost-variance-workspace.tsx`
- `apps/web/src/app/finance/cost-variance/page.tsx`
- `apps/web/src/app/api/finance/cost-variance-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，既有条目一字未改未重排）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（**只改 12.4.1.3 那一行的状态与证据两格**）

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q -o compile` ⇒ 绿；`pnpm typecheck` ⇒ 绿（3/3 任务成功）。
- **L1 模块闸**：
  `./mvnw -pl services/finance -am test -Dtest='CostVariance*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0`
  （CostVarianceIntegrationTest 31 + CostVarianceTenancyIntegrationTest 7）。
- **前端**：`pnpm vitest run apps/web/src/components/finance/cost-variance-model.test.ts`
  ⇒ **16 例通过**；因改了共享的 `api-proxy.ts`，另跑全量 allowlist 回归
  `pnpm vitest run apps/web/src/app/api` ⇒ **233 文件 / 1315 例通过**。
- **契约**：`./mvnw -pl services/finance -am test -Dtest='OpenApiContractTest' …` ⇒ 导出 1 份 spec
  （BUILD SUCCESS）；随后 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  ⇒ `Generated 6656 DTOs in 7 per-service files`（**未手改任何契约文件**）。
- **多租户**：集成测试实跑三条断言 —— 两租户互不可见 / 伪造 tenant header 无效 /
  连接池归还后不串租户（连接池压到 1）。

## 后续待办

- **13 利润**（**未开发**）：本模块只产出「差异在哪、谁是主因」，
  **不产出任何利润数字**（利润属 13）。
- **6 采购**（价格谈判依据）：采购价波动已可读，但**不做**「该不该换供应商」的推荐 ——
  那需要供应商报价与切换成本，不在本模块输入里。
- **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**（四条车道并发改同一文件必然冲突）
  ⇒ 待调度者统一处理。
- **identity 权限目录**：finance 服务的权限码（`finance.cost_variance.*`）尚未进 identity 的 `permission` 表 ——
  这是**全服务既有的缺口**（`finance.receivable.*` / `finance.transaction.*` 同样未进），
  不属本车道写入所有权，本车道沿用既有约定（模块自己的 `finance.<module>.<action>` 词表）。
- **异常阈值可配置**：`VARIANCE_ALERT_RATE = 0.20` 目前是服务层常量，
  若后续要按租户可配，需要落到「基线/策略表」上（不在本轮范围）。
