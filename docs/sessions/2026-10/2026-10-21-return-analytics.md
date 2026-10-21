# 11.5.1.1 退货分析（阶段 D7，L3 锚点 11.5）

- **日期**：2026-10-21
- **车道**：`feat/11-5-1-1`（worktree `integration/.worktrees/11.5.1.1`）
- **蓝图原文**（`docs/product/capability-blueprint/11-aftersales-and-returns.md` 220-226 行）：
  > 职责：统计退货情况：退货率（按店铺/SKU/类目/原因/批次）、退货金额趋势、拒退率、处理时效、平均退款金额与原因分布。
  > 输入：售后与退货数据；销售数据。
  > 输出：退货分析报表 + 维度下钻 + 异常识别。
  > 关系：上游 = 11.1、11.2、11.3；下游 = 13 分析、6 采购（质量改进）。**退货率必须与销售额同口径比较**，只看绝对数会误判（退货率从 3% 升到 5% 严重，但绝对数可能没变）。

## 决策摘要

### 1. 加粗句落在四个层次，不只落在「一个比率字段」

蓝图那句「退货率必须与销售额同口径比较」如果只做成「界面上显示一个百分比」，等于没做。本模块在四个层次上落实：

1. **币种是报表的第一维**：分子（退货金额）与分母（销售额）必须落在同一个币种里才允许相除。`overview` **按币种分组**返回，响应里**没有**跨币种的合计金额与合计退货率（`totals` 只保留与币种无关的计数：售后单数 / 退货件数 / 订单数）。跨币种只有两种做法 —— 按币种分组，或者拒绝；本模块只做前者。
2. **每个维度用自己口径的总体率做基准**：拿全店销售额当每个 SKU 的分母，报出来的是「占比」不是「退货率」。所以五个维度的分母分别是：店铺 = 该店铺同期订单总额；SKU / 类目 = 该 SKU 同期行金额；原因 = **全量**同期销售额（含义是「这个原因占销售额的多少」，不是「这个原因内部退了多少」）；批次 = 该批次关联 SKU 同期销量件数。
3. **分子分母取自真表真列**：分母的唯一来源是 `sales_order.grand_total_amount`（`status='SUBMITTED'`、时间轴 `submitted_at`），不自拼、不用第二套口径。
4. **算不出来返回 `null` 而不是 `0`**：没有销售额就没有退货率，报 0 会被读成「这个维度很健康」。前端一律渲染成「—」/「无样本」。

### 2. 口径三分法，每一行都带标记

| 口径 | 分子 | 分母 |
| --- | --- | --- |
| 单头口径 CASE_LEVEL | `aftersales_case.requested_amount` | `sales_order.grand_total_amount` |
| 行级口径 LINE_LEVEL | `aftersales_case_line.requested_amount` | `sales_order_line.line_amount` |
| 件数口径 QTY_LEVEL | `return_receipt_line.received_qty` | 关联 SKU 同期 `sales_order_line.quantity` |

三者**不可比**。不标出来的话，读的人会把 3% 和 30% 直接比大小。所以每一行都带 `caliber`，前端每一行都渲染口径徽标。

### 3. 上游事实表的逐条核对（10 分钟自检的结论）

开写代码之前先把每个分子分母落到**真实存在的表与列**上核了一遍，全部命中，因此**不构成 BLOCKED**：

- 拒退率的唯一来源是 `rma_authorization.rma_status`（V161；`DRAFT/AUTHORIZED/PARTIALLY_AUTHORIZED/REJECTED/EXPIRED/CANCELLED`）；
- 店铺维度来自 `platform_order.platform_code/shop_code`（V207）—— **`sales_order` 本身没有 shop 列**，必须经平台单投影关联；
- 退款金额来自 `refund_application.refund_amount/currency`（V166）；
- 销售额分母唯一来自 `sales_order.grand_total_amount/currency/status/submitted_at`（V99）+ `sales_order_line`（V99）；
- 批次来自 `return_receipt_line.batch_no/received_qty`（V172）；
- 类目映射来自 `return_quality_category_sku`（V179）—— catalog 的商品类目在**独立库**，跨库 join 被 AGENTS.md 禁止，所以走 trade 侧质检类目映射，未映射 SKU 落「未归类」桶；
- 原因归因来自 `aftersales_reason_attribution_set/_item` + `aftersales_reason_category`（V158）。

### 4. 拒退率与处理时效的定义（两个容易被算错的地方）

- **拒退率** = `REJECTED` ÷（`AUTHORIZED` + `PARTIALLY_AUTHORIZED` + `REJECTED`）。**草稿与 EXPIRED 不进分母** —— 它们还没有结论，把它们算进分母等于把「还没批」当成「批了不批」。部分授权单列，另报件数口径的未授权占比。
- **处理时效**是**四个互不混算**的时长：售后单结案、授权审批、买家回寄、退款出结果。各带样本数，无样本为 `null`（前端显示「无样本」而不是 0 小时）。混算成一个「平均处理时长」会得到一个谁也解释不了的数。

### 5. 异常识别：五条规则 + 阈值逐条报出 + 最小基数门槛

退货率倍数异常、拒退率异常、批次件数异常、原因集中、趋势恶化。三个设计要点：

- **阈值逐条报出来**（`anomalies.thresholds`）：说不清判据的异常清单，没人会当真去处理。阈值可由租户自己登记的口径覆盖；未登记时用文档化的默认口径并把 `source` 标成 `DEFAULT`。
- **各维度与「自己口径」的总体率比较**，禁止跨口径比较。
- **最小基数门槛**（维度销售额 ≥ 总体 1%）：小样本维度的退货率天然虚高，不设门槛会天天报警，报警多了就等于没报警。

### 6. 快照与口径：追加式 + 换版原子化

- **快照是追加式的**：一次保存 = 一个 `snapshot_no` + 一行总体行（OVERVIEW，带拒退率 / 退款额 / 时长）+ 每个维度值一行（拒退与退款列存 `null` 表示**该维度不适用**）。填 0 会被读成「这一行没有拒退」，而真相是「拒退率是总体口径，另有一行 OVERVIEW」。
- **口径换版是原子的**：请求体带的是**新口径的完整内容**，服务层在一个事务里「老版本置已换版（含 `superseded_by` 指向新版本）+ 新版本建成生效」。分两步做（先换版再新建）会在两步之间留下「该币种暂无生效口径」的窗口，而那段时间算出来的退货率用的是默认口径，却没有人知道。

### 7. 跨服务边界

本模块是 trade 服务**同库只读聚合**：所有上游事实表（11.1.1.x / 11.2.x / 11.3.1.1 / 9.x）都在 trade 库，**不经任何跨服务调用**。类目维度取 trade 侧的 `return_quality_category_sku` 映射而不是 catalog —— 跨库 SQL / FK / 视图被 AGENTS.md 禁止。批次真值在 warehouse 独立库，本模块只用 11.2.2.x 已落库的 `batch_no` 快照。

### 8. 刻意不做的四件事

- **退货率预测 / 最优解**：本仓没有历史建模与预测设施，画一条预测曲线就是冒充模型能力。`capabilities` 的 boundaries 里明写。
- **写入供应商评级 / 发起供应商索赔**：属 6.1.1.3 / 6.4.2.2，**均未交付**，capabilities 如实报 `available=false` 并写明归属模块。
- **跨系统经营分析**：13 未交付。
- **批次维度的金额**：一笔售后行分批到货时金额该算进哪个批次没有唯一答案，所以批次维度只报件数与关联 SKU 数，金额列显式为 `null`。

## 关键 trade-off

- **跨币种合计在服务层置 `null`，而不是在前端隐藏**：前端藏起来而后端仍然返回一个跨币种的合计数，下一个消费者（13 分析）就会误用。代价是 DTO 的三个合计字段要标可空、契约要重生成；收益是「跨币种不许合计」这条规则落在**数据层**而不是界面约定。
- **拒退率的分母不含草稿**：代价是「待审批的单」不在拒退率里，看起来分母偏小；收益是分母每一单都有结论，拒退率才答得出「批了不批的比例」。未授权的件数占比单列，把「还没批」的信息补回来。
- **原因维度用全量销售额作分母**：分母不再是「这个原因内部的销售额」，所以每一行的退货率不能横向比大小。代价是这一维的口径与其他维度不同；收益是「这个原因占销售额的多少」本身就是一个有用的数。界面上用口径徽标 + 文字说明标出来。
- **异常带最小基数门槛**：代价是「小体量但 100% 退货」的维度不会报警；收益是报警列表里每一条都值得人去处理。
- **期间按 UTC 日界切、`to` 含当天**：与兄弟模块 11.5.2.1 同款（用会话时区切分桶的话，换一条连接就换一套分桶，趋势图会凭空多出空桶）。`to` 的语义与 11.5.2.1 的 exclusive 不同，已在 DTO 描述、note 与本文档里显式写明。

## 过程中修掉的真实缺陷

1. **`List<Object>` 聚合盒类型退化** —— jOOQ 取出的数值被装箱成 `Integer` / `BigDecimal` 混合，累加时 ClassCastException。改成可变的 `Agg` 类。
2. **复合键分隔符用竖线** —— 会被维度值里的竖线撞上。改用 U+0001（控制字符，维度值里不可能出现）。
3. **overview 误把复合键当币种** —— 分组键是「平台码 + 店铺码 + 币种」的复合体，被当成币种返回给前端。
4. **原因维度总销售额按行累加** —— 每一行的分母是**同一笔**全量销售额，按行加会把销售额**乘上「原因条数」**。改成按币种只加一次。
5. **SKU / 类目 case 查询漏 `case_type` 分组键** —— 分子分母的过滤条件不一致。拆成 `caseGroupBy` / `salesGroupBy` 两套分组。
6. **批次查询把聚合列放进 GROUP BY**。
7. **归因分布未按 `case_type` 过滤** —— 与其它口径的覆盖率定义不一致。
8. **无口径币种的阈值取默认口径会 NPE**。
9. **快照行拒退 / 退款列 NOT NULL 与「不适用 = 0」冲突** —— 改成可空 + 快照增加 OVERVIEW 总体行。
10. **口径换版非原子** —— 改成单事务内「老版本置已换版 + 新版本建成生效」。
11. **多币种时下钻合计是跨币种相加**（100 美元 + 80 欧元 = 180 在对账时是错的）—— 合计三件改可空，多币种置 `null` 并在 note 里写明原因。

## 文件清单

**新增（后端）**

- `backend/services/trade/src/main/resources/db/migration/V271__trade_return_analytics.sql` —— 三张新表（`return_analysis_basis` / `return_analysis_snapshot` / `return_analysis_event`）、RLS ENABLE+FORCE+GRANT、两个序列、两条 DB 守卫。**零 ALTER 既有表、零外键**。
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnAnalyticsModels.java` —— DTO 全集。
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnAnalyticsService.java` —— jOOQ 显式 SQL 聚合。
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnAnalyticsController.java` —— 8 组端点，权限码 `trade.aftersales.read` / `trade.aftersales.write`，租户经 `AccessService` 绑定 `app.current_tenant`。
- `backend/services/trade/src/test/java/com/starcon/trade/ReturnAnalyticsIntegrationTest.java` —— 23 例。
- `backend/services/trade/src/test/java/com/starcon/trade/ReturnAnalyticsTenancyIntegrationTest.java` —— 3 例（租户隔离三断言，连接池压到 1）。

**新增（前端）**

- `apps/web/src/components/aftersales/return-analysis-model.ts` —— 数据层 + 纯派生。
- `apps/web/src/components/aftersales/return-analysis-workspace.tsx` —— 真实工作区。
- `apps/web/src/components/aftersales/return-analysis-model.test.ts` —— 15 例。
- `apps/web/src/app/aftersales/return-analysis/page.tsx` —— 路由 `/aftersales/return-analysis`（与 11.5.2.1 `/aftersales/quality-analysis` 并列；`/aftersales/returns` 已被 11.2.1.1 退货授权单占用）。

**修改**

- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json` —— **codegen 产物**，纯新增 8 条路径 + 25 个 schema，0 删除；其余 5 个服务契约逐字节不变。
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**（首 token `return-analysis`），插入锚点固定在 11.5.2.1 `quality-analysis` 那条**之前**；既有条目一字未动。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 11.5.1.1 一行的「状态」「证据」两格。

## 验证

- **L0**：`./mvnw -q compile`（backend）✅；`pnpm typecheck` ✅（3 packages successful）。
- **L1**：`./mvnw -pl services/trade -am test -Dtest='ReturnAnalytics*' -Dsurefire.failIfNoSpecifiedTests=false` ✅ **29 例全绿**（23 功能 + 3 租户 + 3 租户隔离，实跑 Testcontainers PostgreSQL 16）。
- **前端**：`pnpm vitest run apps/web/src/components/aftersales/return-analysis-model.test.ts` ✅ 15 例；`pnpm vitest run apps/web/src/app/api/trade` ✅ 63 文件 / 351 例（改了 api-proxy 的全量 allowlist 回归）。

## 后续待办

- 13 分析（跨系统经营分析）未交付 —— 本模块的快照表是它的天然输入，但**不写**它的口径。
- 6.1.1.3 供应商评级 / 6.4.2.2 供应商索赔未交付 —— 本模块的批次与原因分布是它们的输入，本模块**不写**评级。
- 退货率预测未做（无建模设施），若将来要做，**必须**建立在 `return_analysis_snapshot` 这张追加式快照表之上，而不是重新扫一遍事实表。
