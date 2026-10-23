# 2026-10-24: 12.1.2.3 供应商对账

## 摘要

交付蓝图 **12.1.2.3 供应商对账**（卷 12 财务核算，D6，L3 锚点 12.1.2 应付）。迁移
`V349__finance_supplier_reconciliation.sql` 在 finance 服务里新建**七张新表**（零 ALTER 零 DROP 既有表），
后端 **12 个端点**挂在 `/finance/supplier-reconciliation`（jOOQ 显式 SQL，无 JPA），前端是**真实工作区**
`/finance/supplier-reconciliation`（模型 + 工作区 + 页面 + 两个测试文件）。

蓝图那句加粗句「**财务是最终确认方，采购不能自行确认应付——这是职责分离**」的落法不是靠文档提醒，而是**钉在
schema + 权限 + 端点三层**：

- **schema**：对账单头有 `status`（IMPORTED → MATCHED/DIFFERENCES → CONFIRMED/REJECTED）与
  `confirmed_by / confirmed_at / payable_id / finance_confirmed_amount`；表级 CHECK
  `finance_supplier_reconciliation_state_consistent` 规定「**CONFIRMED 之外这三个字段一律为空**」⇒
  采购侧数据在财务确认前**结构上无法**变成已确认应付。
- **权限**：三个权限码分开 —— `finance.suppliereconciliation.read`（读）/ `.update`（导入、核对、登记说明）/
  `.confirm`（裁决差异、确认、驳回）。采购侧角色只给 read+update ⇒ 调裁决 / 确认端点**直接 403**。
- **端点**：导入（`POST /import`）与核对（`POST /{id}/match`）归采购侧，确认（`POST /{id}/confirm`）与驳回
  （`POST /{id}/reject`）归财务侧；裁决（`POST /{id}/differences/{did}/resolve`）也是财务侧动作。

## 决策 / 结论

### 1. 质检数据的真实来源是 6.4.2.1 质检单，不是 `/trade/return-quality`

调度者给的线索是 `/trade/return-quality`，实查该端点属 11.x **退货质检**（售后域），不是采购对账要用的东西。
本模块实际读的是 **6.4.2.1 质检单 `GET /trade/quality-inspections`**（trade 服务 `QualityInspectionController`，
迁移 `V249__quality_inspection_order.sql`）。关联链是**收货单号**：`trade_purchase_reconciliation_line.
sourceLineRefCode`（仓库收货单号）→ 质检单 `receiptCode`。

- 扣量口径直接用上游的 `grossDeductedQuantity`（= 不合格量 + 让步接收量），**不自己发明规则**。
- 上游 6.5.1.1 自身的质检扣量未接线（`qualityDeductionStatus='UNAVAILABLE'`）⇒ 「质检扣量未扣除」这条差异在
  实践中是常态，这正是财务侧要核的事，不是 bug。

### 2. 跨服务读一律走授权 HTTP API Port，不跨库

三个只读端口（转发调用方自己的 bearer）：

| 端口                    | 上游端点                                         | 用途                                              |
| ----------------------- | ------------------------------------------------ | ------------------------------------------------- |
| `SupplierStatementPort` | `GET /trade/purchase-reconciliations`（6.5.1.1） | 采购侧对账单                                      |
| `ArrivalEvidencePort`   | `GET /trade/purchase-arrivals/{id}`（6.3.2.2）   | 到货流水（`arrivedQty` + `warehouseReceiptCode`） |
| `QualityInspectionPort` | `GET /trade/quality-inspections`（6.4.2.1）      | 质检单与扣量                                      |

零跨库 SQL / 零跨库 FK / 零共享视图；关键值（单价、汇率、数量、收货单号、质检结论）**快照列落库**，
上游后改了不影响历史对账。

### 3. 只导入上游状态 CONFIRMED / CLOSED 的对账单

DRAFT / SENT / DISPUTED 的采购侧对账单**不导入**（供应商还没认这笔账），并在导入结果里逐条说明跳过原因 ——
不静默丢弃，也不假装导入成功。

### 4. 财务确认 = 唯一产生应付的入口，且幂等

- 应付依据表 `finance_supplier_reconciliation_payable` 有 `UNIQUE(tenant_id, recon_id)`；
- 落到 12.1.2.1 的应付单用 V340 的幂等键 `UNIQUE(tenant_id, source, source_reference)`，
  `source='SUPPLIER_STATEMENT'`、`source_reference=对账单号`；
- 两个唯一键叠在一起 ⇒ **重复确认不会长出第二张应付单**。
- 写入路径**没有绕过 V340**：先写 DRAFT 头（**不手填单头金额**，由 `guard_finance_payable_amount` 派生）→
  写明细行 → 转 OPEN；确认后由 V340 的冻结守卫冻结。集成测试直接断言 `finance_payable` 里的行。

### 5. 应付单按**净额一行**，分项明细留在对账行表

V340 的明细行要求 `amount > 0`，**表达不了负贡献**（质检扣减、金额残差可以是负）。所以应付单只写**净额一行**
（`amount` = 采购侧口径 + 已裁决调整额，> 0），分项留在 `finance_supplier_reconciliation_line`。
净额 ≤ 0 时**拒绝确认**并引导走「财务驳回」—— 应付单表达不了这个事实，硬写一行负数会被 V340 的守卫打回。

### 6. 差异裁决的词表与不可改判

- 裁决方式只有两个：`APPLIED`（按财务口径调整）/ `KEPT`（沿用采购侧口径）。
- `adjustment_amount` 是**生成列**（由裁决方式与两端值派生），不是手填 ⇒ 裁决了却改不动金额。
- 差异说明 **append-only**（`finance_supplier_reconciliation_explanation`，运行角色 UPDATE/DELETE 已收回）。
- **已裁决的差异不可改判、不可重算**：有裁决时拒绝重新核对（重算会把已裁决的结论抹掉）。
- 差异类型 5 种：`RECEIPT_QUANTITY_MISMATCH` / `RECEIPT_EVIDENCE_MISSING` / `QUALITY_DEDUCTION_NOT_APPLIED` /
  `QUALITY_EVIDENCE_MISSING` / `AMOUNT_RESIDUAL`。差异按行分解为**互不重叠**的分量，残差单独成条
  （避免「一个大差额」把多种原因糊在一起）。

### 7. 刻意**不改** 12.1.2.1 的 `PayableModels` / `PayableService`

12.1.2.1 的测试断言 `SUPPLIER_STATEMENT` 仍 `producible=false`、文案含「未交付」—— 那是它的车道自己的断言，
跨车道改它会踩到别人的测试。本模块按仓库既有先例（12.1.1.3 `WriteOffService` 直接写 `finance_receivable_line`
/ `finance_receipt`）在自己服务内写 `finance_payable`，严格走 DRAFT → 明细 → OPEN 守卫。

> ⚠️ **副作用（需 12.1.2.1 owner 处理）**：12.1.2.1 的能力声明里「12.1.2.3 未交付」这句文案**已经过期**，
> 本模块**没有**去改它（那是别人的车道）。

### 8. 迁移里的 8 个触发器（关键的几条）

- `确认必须有且仅有一条应付依据、金额一致、且没有未裁决的差异` ⇒ 绕过服务层直连 SQL 也确认不出一个
  「没有应付依据的已确认对账单」。
- `终态冻结`（CONFIRMED / REJECTED 后头与子表冻结，含 `note` 等快照字段）。
- `应付依据必须指向 source='SUPPLIER_STATEMENT' + source_reference=对账单号 的未作废应付单`。
- `单条差异金额不得越过整行差额`。
- `差异计数派生`（open difference count 不手填）。

> 🔴 **踩过的坑**：`BEFORE DELETE` 触发器里 `RETURN NEW` 会让 DELETE **静默失效**（DELETE 场景 NEW 为 NULL，
> 返回 NULL = 跳过删除）⇒ 三个冻结触发器改成 `TG_OP='DELETE'` 分支取 `OLD` 并 `RETURN OLD`。

### 9. 白名单首 token 与后端 `@RequestMapping` 必须逐字一致

api-proxy 只**纯追加**一条独立 `services[]` 元素（prefix `finance/`、首 token `supplier-reconciliation`、
env `FINANCE_URL`、fallback `http://127.0.0.1:8087`），**既有条目一字未改、不重排**。

本模块**真的踩了这个坑**：后端与前端模型先写成复数 `supplier-reconciliations`，白名单正则却是单数
`supplier-reconciliation` ⇒ 代理剥掉 `finance/` 后拿到的路径永远匹配不上，**浏览器里每个请求静默 404**，
而 typecheck / lint / vitest / Playwright 全绿。修法是把后端 `@RequestMapping` 与前端 base 统一到单数
（与任务约束给定的首 token 一致），并**新增一条断言把后端控制器源码与白名单钉在一起**：

```
白名单首 token 与后端 @RequestMapping 逐字一致（单复数写错 = 浏览器静默 404）
```

这条断言读 `SupplierReconciliationController.java` 的 `@RequestMapping`，并反向断言**复数写法必须 404**。

## 涉及文件

**后端（新增 8 个文件）**

- `backend/services/finance/src/main/resources/db/migration/V349__finance_supplier_reconciliation.sql` —— 七张表：
  `finance_supplier_reconciliation`（头）/ `_line`（明细行）/ `_inspection`（质检证据）/ `_difference`（差异）/
  `_explanation`（差异说明）/ `_event`（事件流）/ `_payable`（应付依据）；全部 tenant_id NOT NULL + ENABLE **与**
  FORCE RLS + GRANT finance_app/starcon_app；说明与事件流 REVOKE UPDATE/DELETE。
- `backend/services/finance/src/main/java/com/starcon/finance/SupplierReconciliationController.java` —— 12 个端点。
- `.../SupplierReconciliationService.java` —— 导入 / 核对 / 裁决 / 确认 / 驳回。
- `.../SupplierReconciliationModels.java` —— 请求响应 DTO。
- `.../SupplierStatementPort.java`、`.../ArrivalEvidencePort.java`、`.../QualityInspectionPort.java` —— 三个跨服务只读端口。

**后端测试（新增 2 个文件，24 例）**

- `backend/services/finance/src/test/java/com/starcon/finance/SupplierReconciliationIntegrationTest.java`（19）
- `backend/services/finance/src/test/java/com/starcon/finance/SupplierReconciliationTenancyIntegrationTest.java`（5）

**前端（新增 5 个文件）**

- `apps/web/src/components/finance/supplier-reconciliation-model.ts` —— 数据模型与展示口径。
- `apps/web/src/components/finance/supplier-reconciliation-model.test.ts`（17 例）
- `apps/web/src/components/finance/supplier-reconciliation-workspace.tsx` —— 真实工作区。
- `apps/web/src/app/finance/supplier-reconciliation/page.tsx` —— 页面路由。
- `apps/web/src/app/api/finance/supplier-reconciliation-allowlist.test.ts`（6 例）—— 白名单双向断言。

**修改**

- `apps/web/src/lib/api-proxy.ts` —— **纯追加**一条 `services[]` 元素。
- `packages/api-contracts/src/contracts/finance.ts` + `packages/api-contracts/src/finance.openapi.json` —— codegen 产物
  （新增 `FinanceSupplierReconciliation*` 类型；其余六个服务逐字节未变）。

## 关键 trade-off / 讨论

- **只做「算差额 + 裁决」，不做「自动匹配」**：12.3.2.1 对账任务、12.3.2.2 差异处理、12.3.2.3 自动匹配规则**未交付**
  ⇒ 本模块只产出**差异清单**，不实现规则引擎式的自动匹配，也不宣称「已自动对账」。
- **不碰 `packages/ui/src/lib/task-pages.ts`**：导航注册**刻意留给导航车道**，本车道不跨车道改共享注册表；
  缺口已在本模块交付报告点名。
- **应付单净额一行 vs 分项多行**：分项多行表达不了负贡献（V340 要求 `amount > 0`），所以宁可应付单信息少一点，
  也不能塞一行会被守卫打回的负数明细。

## 后续待办

- **导航注册**：`packages/ui/src/lib/task-pages.ts` 需要登记 `/finance/supplier-reconciliation`（不在本车道范围）。
- **12.1.2.1 能力声明的过期文案**：仍写着「12.1.2.3 未交付」，归 12.1.2.1 车道。
- **12.1.2.1 的 `SUPPLIER_STATEMENT` 政策**：应付确认政策**不预置任何一行** ⇒ 使用方需自行启用
  `(SUPPLIER_STATEMENT, PAYABLE)`，否则确认会被政策闸门拒掉（本模块在能力声明里写明）。
- **12.3.2.x 未交付**：自动对账任务 / 差异处理 / 自动匹配规则。

## 关联

- 蓝图：`docs/product/capability-blueprint/`（12 财务核算 / 6 采购与供应 6.5.1.1 采购对账）
- 台账：`docs/product/capability-blueprint/module-progress.md` 第 388 行（12.1.2.3）
- 上游已交付：6.5.1.1 采购对账（trade `V250__purchase_reconciliation.sql`）、6.4.2.1 质检单（trade `V249`）、
  6.3.2.2 采购到货
- 下游：12.1.2.1 应付单管理（`V340__finance_payable.sql`）、12.1.1.3 坏账核销（`WriteOffService` 跨模块写先例）
