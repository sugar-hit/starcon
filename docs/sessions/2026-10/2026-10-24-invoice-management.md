# 2026-10-24 · 12.6.2.1 发票管理（阶段 D6）

## 决策摘要

| 项   | 结论                                                                                                                                                                    |
| ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 迁移 | `backend/services/finance/src/main/resources/db/migration/V366__finance_invoice_management.sql`（六张新表，纯 CREATE TABLE，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表）      |
| 表   | `finance_invoice_template` · `finance_invoice_sequence` · `finance_invoice` · `finance_invoice_line` · `finance_invoice_event` · `finance_invoice_platform_submission`           |
| 端点 | 18 个，挂 `/finance/invoices`；权限 `finance.invoice.read / .create / .update`                                                                                             |
| 页面 | `/finance/invoices`                                                                                                                                                      |
| 测试 | 后端 39（32 + 7）· 前端 25（20 + 5）· 全量 allowlist 回归 234 文件 / 1320 例 · finance 前端组件回归 24 文件 / 322 例                                                   |
| 契约 | `packages/api-contracts/src/contracts/finance.ts` 新增 33 个 `FinanceInvoice*` 类型（+33/-0）                                                                               |

蓝图原文（`docs/product/capability-blueprint/12-finance.md` 第 296-301 行）：「管理销售发票与贷项通知单：按平台/国家模板生成、维护税码税率税额、编号与状态（草稿/开具/作废/红冲），支持批量与平台开票对接。」上游 = 9 订单、12.3.1；下游 = 12.5.2.1 凭证、11.4 售后（退货开票调整）。「**各国发票要求差异大**，模板必须按国家配置；本系统不承诺覆盖所有国家税务规则。」

## 职责切分

- **归本模块**：发票与贷项通知单的事实、编号序列、状态留痕、模板与编号规则、平台开票报文与递交编排。
- **归 12.6.1.1 税率配置（V361）**：税率 / 税码 / 辖区码表的**唯一真值源**。本模块**不复制**税率表、不建指向它的外键；每一行落库前经 `TaxRateService.resolve` **实时校验**，命中不到一律报错（**不退回最新一条、不按 0% 处理**）。命中的**税率版本 id** 与税率快照一起落库 ⇒ 发票自带当时的税率口径，之后政策改税率不影响这张发票。
- **归 12.5.2.1 凭证（V357）**：本模块**不写凭证**。发票只产出发票事实，过账走凭证模块自己的接口。
- **归 11.4 售后**：退货开票调整 = **红冲**。红冲金额**从原发票逐行带出**，DB 层镜像守卫逐项比对，手填一个「差不多」的数**写不进去**。
- **归 9 订单 / 12.3.1 结算**：**只读**。订单侧经 trade 的授权只读 API 读「开票需求」（`b2b_receivable.invoice_state='REQUESTED'`），**不回写** `invoice_state`（那张表的写入所有权在 trade）；结算侧同库只读。跨服务只走授权 API，不跨库 SQL / FK / 视图。
- **归 channel 16.3.2.x**：平台开票的**实际递交**。本模块负责报文生成、批量递交编排与回执登记；递交本身失败时**如实留痕并写上游原话**，不假装提交成功。

## 关键 trade-off

### 1. 税率与税额**不**是建单入参

建单只收 SKU / 数量 / 单价。税率由服务端按（发票日 × 辖区 × 税码）实时取；行金额 `numeric(19,4)` 与税额 `numeric(19,4)` 是 DB **生成列**（数量 × 单价 × 税率），单头合计由触发器从明细求和派生。

代价：调用方**填不出**一个假的税额 —— 这是本模块最想要的那条性质。行级允许覆盖税种 / 税码，但**照样**要过实时校验（填了也校验不过的，照样写不进去）。

### 2. 行金额 / 税额是**生成列**而不是应用层算的

应用层算错时生成列不会跟着错 —— **没有第二个写入口**。数量 `numeric(19,6)`（贷项行为负，表示红冲方向，于是两种单据的合计同号可加），单价与金额 `numeric(19,4)`，税率 `numeric(9,6)`。全模块没有一个 `double`。

### 3. 编号只在**开具**时分配，取号是**一条原子语句**

取号 = `INSERT ... ON CONFLICT (tenant_id, sequence_key, period_code) DO UPDATE SET next_value = ... + 1 RETURNING next_value`。绝不「先查 max 再 +1」—— 后者在并发下会让两张发票拿到同一个号，那不是「号段用乱」，那是**两张发票同号**，税务上直接是事故。

编号**只在开具时**分配（草稿没有正式编号，`invoice_number` 为 NULL）：草稿不是对外凭据，提前占号会让作废的草稿在号段里留下空洞。

号段键 = 编号范围（租户 / 国家 / 平台）× 期间口径（NONE / YYYY / YYYY-MM，由模板决定）。同一编号前缀只允许对应一个号段（DB 唯一约束），否则两张模板会各自取号、两边都拿到「INV-000001」。

### 4. 状态机由 **DB 触发器**钉死，而不是服务层 if

草稿 → 开具 → 作废 / 红冲，草稿 → 作废；`VOID` / `REVERSED` 是终态。转换合法性由触发器判定 —— 服务层绕过去了，DB 也拦得住。

**作废与红冲必须留痕**：谁（`voided_by` / `reversed_by`）、何时（`voided_at` / `reversed_at`）、为什么（`reason`，作废时**必填**）。每次状态变化写一条 `finance_invoice_event`（append-only，运行角色的 UPDATE / DELETE 已收回 —— 留痕表能改就等于没有留痕）。

### 5. 红冲**成对**，且金额**逐行带出**

贷项通知单**必须**指向一张已开具的原发票（`original_invoice_id` NOT NULL + 触发器校验 + 部分唯一索引），**不允许孤立红冲**；同一张原发票只允许一张未终结的贷项单。

红冲行由服务层从原发票**逐行镜像**（数量取负表示红冲方向，其余逐项带出），再由 `finance_invoice_line_guard` 在 DB 层逐项比对 —— 手填的数写不进去。

配套的一致性：贷项单**开具**时原发票收口为「已红冲」；贷项单**作废**时原发票回到「已开具」—— 否则「原发票说自己被红冲了、贷项单说自己作废了」，这一对自相矛盾。

### 6. 多币种**绝不相加**

一张发票只有一个币种（`currency` 钉在单据上）。汇总**按币种分组**呈现，页面上**不出现**跨币种的合计数字；本模块**不做本位币折算**，请求折算时经 catalog 的汇率快照取汇率，**汇率不可得就如实报「汇率不可得」，绝不按 1:1 兜底** —— 按 1:1 加出来的「总额」是假的。

### 7. 模板**零预置行**，辖区码**实时校验**

模板是租户自己的开票事实（抬头、税档、号段规则），系统只提供**结构**与受控辖区选项，**不替用户决定**任何一个国家的发票长什么样。辖区码经 12.6.1.1 的辖区选项实时校验 ⇒ 不是自由文本，且**美国辖区（联邦与 50 州）录不进去**（州级销售税归 channel 16.3.2.3）。

同一（国家 × 平台 × 单据类型）同一时刻只能有一张生效模板（生效区间不许重叠 + 部分唯一索引）⇒「按模板生成」永远有唯一答案。模板必须带生效时间：没有生效时间的模板等于没有 —— 改模板会把历史发票的抬头变成另一个口径，而那张发票没人能解释。

### 8. 「不承诺覆盖所有国家税务规则」落成**逐个辖区的事实**

不是一句免责声明，而是能力声明里的 `countryCoverage`：逐个辖区给出「模板配了没 / 税率取得到没 / 缺了会怎样」。缺模板与缺税率是两种不同的缺口，处置动作也不同。

### 9. 时间字段

`timestamptz` 列一律用 `OffsetDateTime` 绑定、values 位置写 `?::timestamptz`（jOOQ 3.19 会把 `OffsetDateTime` 绑成 varchar，PG 直接拒）。**不用** `Timestamp.valueOf(LocalDateTime...)` —— 那会静默偏 −28800s，页面上看不出异常。测试里**断言写回的值**，不是只断言没抛异常。

发票业务日期 `issue_date` 是 **date**（日历日）：发票日是开票日历上的那一天，没有「几点几分」的意思。

## 文件清单

**新增**

- `backend/services/finance/src/main/resources/db/migration/V366__finance_invoice_management.sql`
- `backend/services/finance/src/main/java/com/starcon/finance/InvoiceModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/InvoiceService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/InvoiceController.java`
- `backend/services/finance/src/main/java/com/starcon/finance/InvoiceRequestPort.java`（上游 9 订单「开票需求」只读端口）
- `backend/services/finance/src/main/java/com/starcon/finance/PlatformInvoicePort.java`（channel 平台开票投递端口）
- `backend/services/finance/src/test/java/com/starcon/finance/FinanceInvoiceIntegrationTest.java`（32 例）
- `backend/services/finance/src/test/java/com/starcon/finance/FinanceInvoiceTenancyIntegrationTest.java`（7 例）
- `apps/web/src/components/finance/invoice-model.ts`
- `apps/web/src/components/finance/invoice-model.test.ts`（20 例）
- `apps/web/src/components/finance/invoice-workspace.tsx`
- `apps/web/src/app/finance/invoices/page.tsx`
- `apps/web/src/app/api/finance/invoice-allowlist.test.ts`（5 例）
- `docs/sessions/2026-10/2026-10-24-invoice-management.md`（本文件）

**改动**

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立的 services[] 元素**（prefix `finance/`、首 token `invoices`）。既有 225 条元素**一字未改、也不重排**；基线先换成集成分支当前版本（225 条），本车道只加 1 条 ⇒ 226 条。
- `packages/api-contracts/src/contracts/finance.ts` · `packages/api-contracts/src/finance.openapi.json` —— codegen 产物，**未手改**（重跑 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 零漂移）。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 12.6.2.1 那一行的「状态」「证据」两格。

## 验证

| 闸   | 命令                                                                                                            | 结果                                     |
| ---- | --------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| L0   | `cd backend && ./mvnw -q compile`                                                                              | 通过                                     |
| L0   | `pnpm typecheck`                                                                                               | 3 包全绿                                 |
| L0   | `pnpm lint`                                                                                                    | 3 包全绿（warning 都在别的模块的既有文件） |
| L1   | `./mvnw -pl services/finance -am test -Dtest='FinanceInvoice*'`                                                | **39 例通过**（32 + 7），BUILD SUCCESS   |
| 回归  | `pnpm vitest run apps/web/src/app/api`                                                                        | **234 文件 / 1320 例通过**               |
| 回归  | `pnpm vitest run apps/web/src/components/finance`                                                              | **24 文件 / 322 例通过**                 |

后端集成测试实跑 **Testcontainers PostgreSQL 16**，连接池压到 1；三条多租户断言（两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户）跑在**本模块自己的六张表**上。

## 后续待办

- ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记** —— 那是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
- ⚠️ **台账总览计数**：本车道只改了自己那一行（与兄弟车道同一约定）；总览 / 分阶段 / 分系统的计数由 `node tools/module-progress/sync.mjs` 在并入时重算。
- **12.6.1.2 VAT/IOSS 申报数据**（未交付）：本模块只产出**发票事实**，不产出任何申报底稿 / 汇总数字 / 申报文件。
- **平台开票的实际递交**归 channel 16.3.2.x：报文生成、批量递交编排与回执登记都已实现，channel 尚未提供开票接口时提交**失败并如实留痕**（写上游原话），缺口归 channel。
