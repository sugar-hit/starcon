# 2026-10-23 12.1.1.2 应收账龄

分支 `feat/12-1-1-2`，基线 `integration/module-dev`（8a5b91be）。两个 atomic commit。

## 摘要

卷 12「财务核算」L3 12.1.1「应收」下的第二格 **12.1.1.2 应收账龄**。

蓝图原文：「按账龄区间（未到期/1-30/31-60/61-90/90+）统计应收分布，支持按店铺、客户、币种维度查看，识别逾期集中风险。」
输入：「应收单集合；账龄区间配置。」输出：「账龄分布报表 + 逾期清单。」
加粗句：「**跨境应收的最大风险是平台争议冻结**，账龄报表需单独标识"争议中"金额。」

上游 12.1.1.1 应收单管理已交付（`finance_receivable` 五张表 + 15 个端点），本模块**只读**它，
自建一张表（账龄区间配置）。

## 决策摘要

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 命名空间 | `/finance/receivable-aging`（**不**复用 `/finance/receivables`） | 12.1.1.1 的端点、契约、页面都是它的交付物；同服务的不同模块各走各的前缀（warehouse/ 下 12+ 个页面皆如此）。两条路由两两不相交，不会互相覆盖。 |
| 账龄区间配置 | **① 新增租户可维护的配置表** `finance_aging_bucket`，未配置时回落到**蓝图给定的五档** | 蓝图的「输入」明确列了「账龄区间配置」。写死 = 替用户定了口径（违反卷 12「不自己定会计政策」）；完全不提供 = 蓝图的输入是空的。① + 回落两头都不落：默认开箱可用，口径归租户，响应里如实标 `source=BLUEPRINT_DEFAULT` / `TENANT_CONFIG`。 |
| 配置写入方式 | **整体替换**（`PUT /buckets`），不是逐档增删改 | 档位必须**连续覆盖 1 天起**，这条约束只有对着整份配置才判得出来。逐档写会出现「改到一半的中间态」，那种状态下落在缝里的应收不属于任何一档 —— 报表会**凭空少钱**。整体替换 ⇒ 配置要么合法要么不落库，不存在中间态。 |
| 核销口径 | **不新建**「已核销」的判断口径 | 读 V338 的派生规则：`status in ('OPEN','PARTIALLY_SETTLED')` 且金额取 `amount - settled_amount`（未核销余额）。核销金额与核销状态**只有明细行一个出处**（`finance_receivable_line.settled_amount` ⇒ 触发器派生单头）。DRAFT（未确认）/ VOID（已作废）/ SETTLED（未核销余额为 0）都不进报表。核销**写路径**属 12.2.1.2（未交付）。 |
| 「90+」档的边界 | 从**第 91 天**起算 | 蓝图写「61-90 / 90+」两档在 90 天上重叠。12.1.1.1 的 `GET /finance/receivables/aging` 已经是 `<= 90 → 61-90 档、否则 90+ 档`，本模块**沿用同一条边界** —— 否则同一租户在两个模块会看到两套账龄。标签沿用 12.1.1.1 的「逾期 90 天以上」。 |
| 争议金额 | 每行、每个币种合计里**单列**（`dispute_amount` / `dispute_overdue_amount`） | 蓝图的硬要求。争议**在**未核销余额里（不是另一笔钱），但单独一列可查。争议事实来自 11.4.1.1 纠纷（trade），**当前只支持人工标注、未自动联动** —— 如实写进能力声明。 |
| 币种 | 一律分开汇总 | USD 与 EUR 加起来不是任何一个金额。 |
| 汇总范围 | **整个筛选结果集**，不是当前页 | 分页一变汇总就变的话，「翻到第二页看到总逾期变小了」会被读成「数据丢了」。 |
| 逾期集中风险 | 服务端算好 `overdue_share_top` / `top_dimension` | 页面不做业务算术（只做比例 → 百分数的单位换算）。 |

## 与 12.1.1.1 已交付端点的职责边界（**没有改它的任何一行**）

调度者复核时特别要求写清这条边界，结论：

1. **12.1.1.1 的 `GET /finance/receivables/aging` 是「单据维度的账龄摘要」** —— 随应收单管理一起交付，
   形状是「五档 × 币种」的扁平分布，没有维度下钻、没有逾期清单、没有区间配置。
2. **12.1.1.2 的 `/finance/receivable-aging/*` 是「账龄分布报表 + 逾期清单」** —— 按**店铺 / 客户 / 币种**
   三个维度下钻、逾期清单逐单可列、争议金额单列、账龄区间可配置、逾期集中度由服务端算好。
   两者是**不同深度的东西**，不是同一个端点的两个实现。
3. 蓝图对 12.1.1.2 的**四条硬要求**与覆盖它的端点：

   | 蓝图硬要求 | 覆盖它的端点 |
   | --- | --- |
   | 五档账龄区间（未到期 / 1-30 / 31-60 / 61-90 / 90+） | `GET /finance/receivable-aging/distribution`（按生效区间分档，区间来源 `GET /buckets` 明示）+ `GET /buckets`（区间配置读） |
   | 按店铺、客户、币种**三个维度**查看 | `GET /finance/receivable-aging/distribution?dimension=SHOP\|COUNTERPARTY\|CURRENCY` |
   | **逾期清单**（能列出逾期单，不只是汇总数） | `GET /finance/receivable-aging/overdue`（真分页 + 真筛选 + 真排序，逐单带逾期天数与所属档） |
   | **「争议中」金额单独标识** | 分布的每一行与每个币种合计都有 `dispute_amount` / `dispute_overdue_amount`；逾期清单逐单带 `dispute_flag` 与争议说明；`?disputeOnly=true` 只看争议 |

4. **没有顺手改 12.1.1.1 的控制器或端点** —— 那是别的模块的交付物。
   已知的一处**口径差异**（记录在「后续待办」，交由调度者裁决，本车道**不动**）：
   12.1.1.1 的 `/receivables/aging` 恒用**蓝图五档**（它不读租户配置），
   而本模块读租户配置的区间 ⇒ 租户一旦自定义区间，两处的分档就会不同。
   是否让 12.1.1.1 也读同一份配置，属 12.1.1.1 的改动范围，本车道不越界。

## 关键 trade-off

- **配置表 vs 写死**：选 ①（配置表 + 蓝图回落）。代价是「多一张表、多一套校验、多一个 PUT 端点」，
  换来的是蓝图的输入真实存在、且口径归租户。回落的实现让默认路径一行配置都不写也能跑。
- **整体替换 vs 逐档改**：选整体替换。代价是改一档要提交整份配置（页面已按整份提交实现），
  换来的是**不存在**「改到一半导致分布凭空少钱」的中间态。
- **区间边界的唯一出处**：DB 用两个**部分唯一索引**兜住最要命的两种错配（两个未到期档 / 两个无上界档），
  「连续覆盖 1 天起」这条跨行约束由**服务层整体校验**把守（写入时拒绝，读到非法配置时直接报错，
  绝不算出一张错的报表）。
- **不登记进 `packages/ui/src/lib/task-pages.ts`**：该文件**不在** merge-union 驱动器的覆盖范围内 ⇒
  走普通 git merge；同批三条 finance 车道都改它必然冲突。仓库现状也是「有页面不一定登记」
  （`apps/web/src/app/warehouse/` 下 22 个页面，只有 7 个登记在 task-pages）⇒ 本模块页面按 URL 可达即可。
  同时**没有动** `apps/web/src/app/finance/page.tsx`（12.1.1.1 的页面）。

## 文件清单

后端（`backend/services/finance/`）：

- `src/main/resources/db/migration/V339__finance_receivable_aging.sql` —— 新表 `finance_aging_bucket`
  （纯新增，零 ALTER 零 DROP 既有表）+ RLS（ENABLE 与 FORCE）+ GRANT + 三个索引。
- `src/main/java/com/starcon/finance/ReceivableAgingModels.java` —— 传输模型（16 个 `Finance*` 契约类型）。
- `src/main/java/com/starcon/finance/ReceivableAgingService.java` —— jOOQ 显式 SQL 实现。
- `src/main/java/com/starcon/finance/ReceivableAgingController.java` —— 6 个端点。
- `src/test/java/com/starcon/finance/ReceivableAgingIntegrationTest.java` —— 20 例。
- `src/test/java/com/starcon/finance/ReceivableAgingTenancyIntegrationTest.java` —— 6 例（租户三断言）。

前端（`apps/web/`）：

- `src/components/finance/receivable-aging-model.ts` —— 端点封装 + 可读形态。
- `src/components/finance/receivable-aging-model.test.ts` —— 8 例。
- `src/components/finance/receivable-aging-workspace.tsx` —— 真实工作区。
- `src/app/finance/aging/page.tsx` —— 路由页（`/finance/aging`）。
- `src/app/api/finance/receivable-aging-allowlist.test.ts` —— 白名单双向断言 5 例。
- `src/lib/api-proxy.ts` —— 新增一条**独立** `services[]` 元素（既有条目一字未改、不重排）。

契约（codegen 产物，未手改）：`packages/api-contracts/src/contracts/finance.ts`、`packages/api-contracts/src/finance.openapi.json`。

台账：`docs/product/capability-blueprint/module-progress.md` 的 12.1.1.2 行（只改状态与证据两格）。

## 验证

- L0：`backend/ ./mvnw -q compile` ⇒ EXIT=0；`pnpm typecheck` ⇒ 3/3 successful；`pnpm lint` ⇒ 3/3 successful。
- L1：`./mvnw -pl services/finance -am test -Dtest='ReceivableAging*'` ⇒ **Tests run: 26, Failures: 0, Errors: 0**
  （20 + 6，Testcontainers PostgreSQL 16 实跑，迁移实跑到 V339）。
- 回归：整个 finance 服务 **Tests run: 63, Failures: 0, Errors: 0**（12.1.1.1 的 31+5 与本模块 20+6 与契约 1，
  V339 未打破既有测试）。
- 前端：新增 2 文件 13 例全绿；改了共享白名单 ⇒ 全量 allowlist 回归 `pnpm vitest run apps/web/src/app/api`
  ⇒ **211 文件 / 1202 例全绿**；`apps/web/src/lib/api-proxy*.test.ts` ⇒ 2 文件 / 262 例全绿。

## 后续待办

1. **12.1.1.1 的 `/receivables/aging` 是否也读租户配置的区间**（口径差异，见上）—— 属 12.1.1.1 的改动范围，
   本车道未动，交调度者裁决。
2. **12.2.1.2 核销**（未交付）：核销写路径落地后，账龄分布会**自动**跟着变小
   （本模块统计的是 `amount - settled_amount`），不需要改本模块的任何一列或一条 SQL。
3. **11.4.1.1 纠纷 → 争议标记自动联动**（未交付）：当前只有人工标注（`POST /finance/receivables/{id}/dispute`）。
4. **1.3.2.1 预警规则（催收预警）**（未交付）：本模块的账龄分布与逾期清单就是它要的输入；
   规则引擎本体在 18 系统。
5. **12.1.1.3 坏账与核销**（未交付）：争议金额 ≠ 坏账 —— 本模块**不**把争议金额当已核销或已坏账处理。
