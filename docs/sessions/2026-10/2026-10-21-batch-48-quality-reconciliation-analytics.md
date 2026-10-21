# Batch 48 —— 质检单 / 采购对账 / 采购分析（+ 5.5.1.1 时刻无关修复）

> 并行车道流水线 · 批次 48 · 2026-10-21（08:00 tick 的后半程）
> 集成头 `integration/module-dev` · 与 batch-47 同一 tick 的第二组车道
> 批次号接在 batch-47 之后（`docs/sessions/2026-10/` 下现有最大为 batch-47，已确认无重号）

## 本批概览

| 模块 ID | 名称 | 阶段 | 归属服务 | 迁移版本 | 车道分支 |
| --- | --- | --- | --- | --- | --- |
| 6.4.2.1 | 质检单 | D6 | trade | `V249__quality_inspection_order.sql` | `feat/6-4-2-1` |
| 6.5.1.1 | 采购对账 | D6 | trade | `V250__purchase_reconciliation.sql` | `feat/6-5-1-1` |
| 6.5.2.1 | 采购分析 | D6 | trade | `V251__purchase_analytics.sql` | `feat/6-5-2-1` |
| 5.5.1.1 | 价格历史（**修复车道**） | D6 | catalog | 无迁移 | `fix/5-5-1-1-price-history-window` |

3 条新车道全部落在 **trade** 服务、**3 个互不相同的 L3 锚点**（`6.4.2` / `6.5.1` / `6.5.2`），
彼此无依赖；第 4 条是 batch-47 的 L2 红了之后派出的**定点修复车道**（见文末「修复」一节）。

---

## 模块 6.4.2.1 质检单

**这个功能做什么**：来料/成品质检 —— 按类目模板算抽样比例、逐项记录检验结论
（外观 / 功能 / 规格 / 包装 / 认证标识）、判不合格并扣量，最后给出
**合格量 / 不合格量 / 让步接收量**三个数。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V249__quality_inspection_order.sql`
  — **5 张新表**（质检模板 / 模板检验项 / 质检单 / 质检明细 / 让步接收），
  `tenant_id NOT NULL` + **ENABLE 与 FORCE** RLS + 索引 + GRANT trade_app，
  **4 个护栏触发器**，**零 ALTER 任何既有表**。
- 端点：**17 个** `/trade/quality-inspections`（按 `@XxxMapping` 逐条统计）。
- 页面：`/purchase/quality`（工作区 + 纯函数模型）。
- 测试：**38 例**（`QualityInspection*`，含租户隔离三断言）+ 前端模型与 allowlist 断言；
  全量 allowlist 回归 **133 文件 / 838 例**全绿。

**关键设计决策**：

1. **三量的口径定成「本次质检覆盖的批量」而不是「到货量」**：批量 `lot_size ≤ arrived_quantity`，
   三量和的基数是 `lot_size`。这是卷 06 加粗句「采购收货是分批、可有差异的」的直接落点 ——
   一批到货可以分多次质检，每次质检只对自己的批量负责。
2. **三量自洽由 DB CHECK 钉死**：`COMPLETED` 态强制 `qualified + defective + concession = lot_size`。
   绕过服务层直连 SQL 也写不进不自洽的质检单。
3. **让步接收从扣量合计「里面」出**：`concession ≤ gross_deducted`、
   `defective + concession = gross_deducted`（两条 DB CHECK）。
   让步接收**必须带原因与权限码**（纪律来自 6.4.2.2：让步接收需要权限与原因，
   不能由质检员随手放行），但**不实现处置流程**（退换 / 返修 / 报废归 6.4.2.2，未交付）。
4. **抽样比例是 0~1 比率**：服务端算 `批量 × 比例 → 夹逼[min,max] → 批量封顶 → 舍入口径取整`，
   算式原文落 `sampling_basis` —— 不是前端传来的数。
5. **库存质量状态分流如实标 `available=false` + `ownerModule` + 影响说明，不假装已分流**：
   warehouse 侧没有任何「按质检结论把货登记到待检 / 残次 bucket」的授权写端点
   （唯一质量状态写入口是 8.2.1.2 的差异处置决定，且必须挂在真实收货差异上）。
   `disposition_status` 标 `UNAVAILABLE`。
6. **未交付项如实标注**：6.4.2.2 不良品处理、6.1.1.3 评级、6.5.1 采购对账。

---

## 模块 6.5.1.1 采购对账

**这个功能做什么**：采购侧按期间与供应商发起对账 —— 汇总应收数量、单价、金额、
代扣代缴费用与索赔，生成对账单发给供应商确认，差异逐条挂起。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V250__purchase_reconciliation.sql`
  — **5 张新表**，`tenant_id NOT NULL` + **ENABLE 与 FORCE** RLS + GRANT，
  **零 ALTER 既有表**。
- 端点：**15 个** `/trade/purchase-reconciliations`（capabilities / options / 集合读+建单 /
  {id} / lines / deductions / send / confirmations / discrepancies(GET+POST) / …/resolve /
  reopen / close / export）。
- 页面：`/purchase/reconciliation`（真实工作区 + 纯函数模型）。
- 测试：**17 例**（`PurchaseReconciliationIntegrationTest` 13 + `PurchaseReconciliationTenancyIntegrationTest` 4）；
  前端模型 20 例 + allowlist 5 例；全量 allowlist 回归 **126 文件 / 729 例**全绿。

**关键设计决策**：

1. **职责分离是硬约束**：对账由采购发起，**采购侧不确认应付**。
   `finance_handoffStatus` 恒为 `PENDING_FINANCE_SERVICE`，并由**表级 CHECK 钉死** ——
   **没有任何路径能把它写成「已确认应付」**。
2. **质检扣量端口未接线 ⇒ 数量是 `NULL` 而不是 0**：`QualityDeductionPort` 未接线时，
   明细行 `qualityDeductionStatus='UNAVAILABLE'`、扣量数量 **NULL**（不是 0），
   capabilities `sections` 与详情 `basis.unavailableSources` 都点名。
   **写成 0 会被读成「没有质检扣量」，那是凭空造出来的结论。**
3. **一处与任务书前提不同的实测结论（重要）**：任务书说「8.2.1.1 收货单已交付 ⇒ 收货来源真实可读」。
   **实查不成立** —— warehouse 的 `requireSourceAvailable` 只放行 `TRANSFER`
   （6.3.1 采购单未交付 ⇒ PURCHASE 来源建不出单），且收货行**没有单价与币种**，即便能建也算不出应收金额。
   ⇒ 改用 **6.3.2.2 到货流水**（trade 本库、已交付、数量 + 单价 + 币种齐全）作基数，
   warehouse 收货单号只作来源快照，并在 capabilities 的 `related` 里如实写明。**未建任何影子表。**
4. **差异逐条可查**：差异不是聚合成一个差额总数，每条带差在哪个数量 / 金额 / 哪一行，可挂起可解决。
5. **币种与汇率**：跨币种汇总带汇率快照，**不按 1:1 折算**。

---

## 模块 6.5.2.1 采购分析

**这个功能做什么**：采购侧报表 —— 采购金额趋势、供应商集中度与贡献、采购价格波动、
到货准时率、采购成本占销售额比，以及按类目 / 店铺维度的采购效率；每条指标都能下钻到具体单据。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V251__purchase_analytics.sql`
  — **3 张新表**，`tenant_id NOT NULL` + **ENABLE 与 FORCE** RLS + GRANT，**零 ALTER 既有表**。
- 端点：**14 个** `/trade/purchase-analytics`。
- 页面：`/purchase/analytics`（真实工作区 + 纯函数模型，9 例单测）。
- 测试：**19 例**（`PurchaseAnalyticsIntegrationTest` 14 + `PurchaseAnalyticsTenancyIntegrationTest` 5）；
  全量 allowlist 回归 **125 文件 / 724 例**全绿。

**关键设计决策**：

1. **采购成本占销售额比：分母拿不到就「算不出」，不造假比率**。
   分子（采购成本）照算并按币种返回；分母（销售额）来自尚未落地的 13 利润与经营分析
   ⇒ `available=false` + `ownerModule=13 利润与经营分析`，
   前端显式显示归谁管与影响，13 落地后端点直接亮起、前端不用改。
2. **口径写进返回结构**：集中度是 top-N 占比还是 HHI、准时率的分母是「承诺到货行」还是「收货行」、
   价格波动是环比还是同比 —— 口径写在响应里，不让读的人猜。
3. **多源读取都走授权只读端口**（catalog 的类目与汇率、warehouse 的收货单），
   **不跨服务 SQL / FK / 视图**（AGENTS.md 硬规则）；读不到显式标「不可用」，
   **绝不拿 0 或空集合冒充**。
4. **未接线项逐条点名**：13 利润与经营分析（分母）、6.4.2.1 质检单、6.3.1.x 采购单原单。

---

## 修复 —— 5.5.1.1 价格历史：把「依赖墙上时钟时刻」的断言改成时刻无关

**为什么有这条修复**：batch-47 的 L2 红了 1 例，失败用例是
`PriceHistoryIntegrationTest.时间轴按维度真筛选且真分页真排序`。
调度者已取证该失败**在本批合并前的提交 `dd607c5b` 上同样复现**，
且失败与否是**本机墙上时钟时刻**的函数 ⇒ 与本批车道无关，是 5.5.1.1 的潜伏缺陷。
完整取证链见 `2026-10-21-batch-47-consignment-pricing-request-certification.md` 的 L2 一节。

**根因一句话**：测试用 `LocalDate.now().minusDays(9)` 当查询窗口，服务端把 `LocalDate`
换算成 **UTC 零点**（半开区间 `[from, to+1)`），而被断言的记录落在 `now − 8 天` ——
「A2 属于（now−9）那一日」这个期望**只在 JVM 本地时刻 < 08:00 时成立**
（本地 07:0x 时 now 的 UTC 值落在前一日 23:0x，A2 才落进 UTC 日界）。

**修法**：**只改测试，不改服务端的 UTC 日界语义** ——
后者是已交付模块的确定性的 API 口径，改它等于改已交付契约；错的是测试的期望。
把种子与查询窗口统一到**同一套显式 UTC 口径**（种子落在目标日的正午 UTC），
使断言在任何墙上时刻都成立，并补一条**多时区全绿**的证明
（UTC / Asia/Shanghai / America/New_York / Pacific/Kiritimati）。

**归属**：模块 5.5.1.1（catalog），分支 `fix/5-5-1-1-price-history-window`，**无迁移**。

---

## 涉及文件

- 本批 3 条车道 + 1 条修复车道（以实际合并 diff 为准）
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）
- `docs/sessions/2026-10/2026-10-21-batch-48-*.md` —— 本文件

## 关联

- `tools/module-progress/PIPELINE.md` —— 流水线协议（组批协议 / 三层验证 / 收尾三件套）
- `docs/sessions/2026-10/2026-10-21-batch-47-consignment-pricing-request-certification.md` —— 同一 tick 的前半程（batch-47）
- `docs/sessions/2026-10/2026-10-21-quality-inspection-order.md` —— 6.4.2.1 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-purchase-reconciliation.md` —— 6.5.1.1 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-purchase-analytics.md` —— 6.5.2.1 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-price-history-clock-window-fix.md` —— 5.5.1.1 修复车道会话日志
