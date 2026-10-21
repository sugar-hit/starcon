# 2026-10-21 6.5.1.1 采购对账：按已交付事实建对账基数，采购侧不确认应付

## 决策摘要

交付 6.5.1.1「采购对账」（trade 服务，阶段 D6，L3 锚点 `/purchase/reconciliation`）：
按期间与供应商汇总应收数量、单价、金额、代扣代缴费用与索赔，生成对账单发给供应商确认，
差异**逐条**挂起处理。

四条决定整个模块形状的判断：

1. **对账基数只取自已交付事实。**
   应收 = **6.3.2.2 到货流水**（`purchase_arrival_receipt` ⋈ `purchase_arrival_tracking`，同库只读；
   数量取本次到货量，单价与币种取到货跟踪单的采购单价快照）；
   索赔 = **11.2.3.3 供应商索赔明细**（`supplier_claim_item`，同库只读，**不建影子表**）；
   代扣代缴费用 = 采购登记。
   **不建收货影子表、不建采购单影子表** —— 每张事实表只有一个写入所有者，跨服务只走授权 API / event。
2. **采购侧不确认应付。**
   模块里**没有**「应付已确认 / 已付款」的状态、字段与端点；汇总里唯一的应付数叫
   `suggestedPayableAmount`（**建议**数）；`finance_handoff_status` 由**表级 CHECK 钉死**
   只能取 `PENDING_FINANCE_SERVICE` ⇒ **没有任何路径**能把交接状态写成「财务已确认」。
   12 财务服务在本仓**不存在**（backend/services/ 下只有 identity / catalog / channel / warehouse / trade / crm）。
3. **未接线的事实要显式，不是 0。**
   6.4.2.1 质检单与本模块**同批并行开发、此刻在集成头不存在**（既无 `/purchase/quality` 端点也无事实表）
   ⇒ 走只读端口 `QualityDeductionPort`（照抄 `PurchasePlanLockPort` / `UnwiredPurchasePlanLockPort` 的现成范式），
   未接线时明细行标 `quality_deduction_status='UNAVAILABLE'`、
   **`quality_deduction_quantity=NULL`（不是 0）**，并写明影响「本行应收数量尚未扣除质检不合格量」。
   写 0 会被读成「这批没有扣量」，据此付全款是本模块要挡住的最贵的一种错账。
4. **币种 + 汇率快照 + 单位换算必录，跨币种绝不按 1:1 折算。**
   每行带 `currency` + `fx_rate` + `fx_rate_source` + `fx_rate_at` + `quantity` / `unit_code` / `unit_conversion`；
   折算金额与基础数量由 **DB 触发器**算（`statement_amount = amount × fx_rate`、
   `base_quantity = quantity × unit_conversion`），调用方传不进来。
   服务层缺汇率 ⇒ 400；DB 触发器另行拒收「币种与对账单币种不同却没记汇率来源」的行。

## 关键 trade-off

1. **⚠️ 依赖自检推翻了任务书的一处前提：8 收货不能作为对账基数的来源。**
   任务书说「8 收货 → 8.2.1.1 收货单已交付 ⇒ 对账明细的收货来源是真实可读的」。
   实查集成头发现 **warehouse 的收货单不接受 PURCHASE 来源**：
   `GoodsReceiptService.requireSourceAvailable` 只放行 `TRANSFER`
   （因为 6.3.1 采购单未交付，warehouse 建不出指向采购单的收货单）。
   而且收货行**只有数量、没有单价与币种** —— 即便能建，拿它也算不出「应收金额」。
   ⇒ 真正的「期间内收货记录 + 单价 + 币种」是 **6.3.2.2 到货流水**（trade 本库、已交付），
   本模块改用它作基数，并把 warehouse 收货单号当**来源快照**带在明细行上；
   同时在 capabilities 的 related 里如实写明 8.2.1.1 的 PURCHASE 来源未开放。
   **没有因为「任务书说它可用」就照抄一条读不通的链路，也没有为它建影子表。**
2. **对账基数只取已到货事实，不取计划量。**
   采购计划（6.2.1.3）给的是**计划量**，蓝图说的是「应收」——
   拿计划量当应收会把没到的货算成要付的钱。
   计划行没有供应商字段（供应商只挂在 6.2.1.2 的补货建议上），且单价是**每采购单位**的价、
   与到货量（基础单位）不同单位 —— 硬接会引入一条要靠换算比例兜的隐性口径。
   ⇒ 只用已到货事实做基数；单位换算因子在明细行上**显式记 1** 并注明
   「到货量与到货跟踪单的单价同单位（V235 的金额算式为证）」——
   记 1 是**口径**，不是把两个不同单位凑成一样。
3. **端口只做未接线实现，不写指向不存在端点的 HTTP 客户端。**
   6.4.2.1 的**端点形状与字段都还没定**。照着猜写一个客户端 = 把一份**不存在的契约**固化进本仓，
   将来 6.4.2.1 落地时形状多半不同，那个「已接线」实现就会**静默返回空扣量** ——
   比明摆着未接线更危险。⇒ 端口保留装配点（`QualityDeductionConfiguration`），
   当前只有 `UnwiredQualityDeductionPort`，并在 javadoc 里写清「6.4.2.1 落地、契约定稿后在此补 HTTP 实现」。
4. **差异是「行」，不是一个差额总数。**
   供应商回执带**逐行**供应商值（数量 / 单价 / 金额）⇒ 差异是**比出来的**：
   `discrepancy_type` + `field_name` + 我方值 + 供应商值 + 差额 + 行号。
   「我方有对方没列 / 对方有我方没列」是**两个方向**的差异类型，合成一个「数量对不上」
   就答不出「到底谁多算了」。差异可**挂起**（HELD）后再处理；解决必须写方式与说明、人与时刻（表级 CHECK）；
   全部解决后自动推进到「已确认」。
5. **期间口径由服务端裁决 + DB 兜底，不悄悄改写。**
   `purchase_reconciliation_period_ok()` 是唯一口径（月度 = 某月 1 号到月末；季度 = 季首月 1 号到季末月末）；
   调用方给的起始日不对、或给了对不上的结束日 ⇒ **400**；同一口径由表级 CHECK 再兜一道。
   期间边界显式钉在 **UTC** —— 本仓尚无「租户时区」这个事实，留隐式本地时区会让同一张对账单
   在不同机器上算出不同期间。
6. **合同只重生成 trade 的，并按集合比对而非看 diff。**
   codegen 会把 `packages/api-contracts/src/catalog.*` 一并重排（既有产物与当前脚本存在**排序漂移**，
   与本模块无关）；本车道**只保留 trade 的改动**，catalog 的漂移已 `git checkout` 还原 ——
   不去碰别的模块的产物。trade 的差异按**集合**核过：schemas 1228 → 1261（**新增 33 个全部本模块**）、
   **删除集为空**、共有类型定义**零变化**；paths 544 → 557（新增 13 条 = 15 个操作）。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V250__purchase_reconciliation.sql`
  —— 五张新表 + RLS（ENABLE **与 FORCE**）+ GRANT + 期间口径函数 + 折算/汇总触发器。
  零 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseReconciliationModels.java`
  —— 传输模型（枚举 / 入参 / 出参 / 能力声明）。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseReconciliationService.java`
  —— jOOQ 显式 SQL 的服务层（采集基数、逐行比对、状态机、CSV 导出）。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseReconciliationController.java`
  —— 15 个端点，挂 `/trade/purchase-reconciliations`。
- `backend/services/trade/src/main/java/com/starcon/trade/QualityDeductionPort.java`
  —— 6.4.2.1 质检扣量的只读端口（当前只有未接线实现）。
- `backend/services/trade/src/main/java/com/starcon/trade/QualityDeductionConfiguration.java`
  —— 端口装配点。

**测试**

- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseReconciliationIntegrationTest.java`（13 例）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseReconciliationTenancyIntegrationTest.java`（4 例）

**前端**

- `apps/web/src/components/purchase/purchase-reconciliation-model.ts` + `-model.test.ts`（20 例）
- `apps/web/src/components/purchase/purchase-reconciliation-workspace.tsx`
- `apps/web/src/app/purchase/reconciliation/page.tsx`
- `apps/web/src/app/api/trade/purchase-reconciliation-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**，
  插入锚点固定在既有 `prefix: 'trade/'`（9.2.1.2 审单规则）**之前**；根路径用独立字面量
  `purchase-reconciliations`；动手前逐条核对本文件**全部 100 条**既有正则对
  `purchase-reconciliations` 的命中数 = **0**（7 个探针形状，Node 正则引擎判定）。

**契约（codegen 产物，只改 trade）**

- `packages/api-contracts/src/trade.openapi.json`、`packages/api-contracts/src/contracts/trade.ts`

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.5.1.1 那一行的「状态」「证据」两格
  （`node tools/module-progress/sync.mjs` 顺带把总览 / D6 阶段 / 系统 06 的计数从 266 更新到 267）。
- `docs/sessions/2026-10/2026-10-21-purchase-reconciliation.md`（本文件）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` ⇒ EXIT=0。
- L1 模块闸：`./mvnw -pl services/trade -am test -Dtest='PurchaseReconciliation*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ `Tests run: 17, Failures: 0, Errors: 0` BUILD SUCCESS（真实 PostgreSQL 16 + FORCE RLS，连接池压到 1）。
- 前端全量 allowlist 回归（改了共享 `api-proxy.ts`）：`pnpm vitest run apps/web/src/app/api`
  ⇒ **126 文件 / 729 例全绿**；另跑 `apps/web/src/components/purchase` + `apps/web/src/app/purchase`
  ⇒ 7 文件 / 118 例全绿。
- `pnpm typecheck` ⇒ 3/3 successful；`pnpm lint` ⇒ 3/3 successful。
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重生成。

## 后续待办

1. **6.4.2.1 质检单落地后**：在 `QualityDeductionConfiguration` 补 HTTP 实现，
   把 `QualityDeductionPort` 从「永远未接线」改成真读，并把明细行的
   `quality_deduction_status` 从 `UNAVAILABLE` 改成 `APPLIED`（**扣量数量必须真的从质检结论取**）。
2. **12 财务服务落地后**：对账单以**授权 API / event** 交接给 finance，
   `finance_handoff_status` 的表级 CHECK 需要从「只允许 `PENDING_FINANCE_SERVICE`」放宽，
   并补一个**属于 12 财务**的确认应付入口 —— 仍然**不在 trade 里**替财务确认应付。
3. **6.3.1 采购单落地后**：warehouse 的 8.2.1.1 才会开放 PURCHASE 来源；
   那时可以在明细行上补一层「仓库收货单 ↔ 到货流水」的双向核对，
   但**基数仍以 6.3.2.2 到货流水为准**（收货单没有单价与币种）。
4. **本仓缺「租户时区」这个事实**：对账期间边界当前钉在 UTC。
   等 catalog / identity 登记了租户时区，期间口径要跟着改（DB 里的
   `purchase_reconciliation_period_ok` 与服务层的裁决要同步）。
5. **6.5.2.1 采购分析**（同卷兄弟）：可以复用本模块的「按币种分桶」口径，
   但**不要**把跨币种的数直接相加。
