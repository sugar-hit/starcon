# 12.5.1.2 会计期间 · 接管 finance_voucher_period（finance / 阶段 D6）

> 会话日期：2026-10-24 · 车道：`feat/12-5-1-2` · 蓝网点：`docs/product/capability-blueprint/12-finance.md` §12.5.1.2

## 决策摘要

实现 12.5.1.2「会计期间」：会计日历（自然月 / 4-4-5 fiscal 周期）· 期间状态（开放/关闭）·
关账与重开 · 关账记录。四条硬约束全部**落成结构而不是文案**：

1. **接管 V357 的 `finance_voucher_period`，不新建平行期间表。**
   V357 第六节交接面原文：「期间表的**最终归属是 12.5.1.2**」「12.5.1.2 交付后**接管本表**
   （照 V350 接管 V346 守卫的同一手法）」⇒ V359 对该表 `ADD COLUMN` 补 fiscal 周期与重开留痕，
   **按 V350 的 `pg_constraint` 扫描法换掉** V357 的 `finance_voucher_period_closed_ck`
   （重开后期间回到 OPEN，但关账痕迹必须留着 —— 旧约束与重开并存不了），
   再 `CREATE OR REPLACE FUNCTION` + `DROP TRIGGER` + `CREATE TRIGGER` 重建
   `guard_finance_voucher_period_state`。12.5.2.1 凭证**结构一个字没改**。
2. **「重开已关期间是高风险操作，必须审批留痕」判两次。**
   服务层向 17 审批（identity，单据类型 `ACCOUNTING_PERIOD_REOPEN`）申请与读取审批状态；
   数据层 `guard_finance_voucher_period_state` 要求本地镜像里**已经有一条该审批实例的 APPROVE 动作**
   ⇒ **绕过服务层直连 SQL 也重开不了一个没审批的期间**。
3. **fiscal 周期由会计日历生成，不由人肉填。**
   自然月按财年起始月推 12 个日历月；4-4-5 按锚点日推 12 个 13 周周期（4-4-5 循环 × 4 轮 = 52 周）。
   周期长度是**生成列**（`period_end - period_start + 1`）⇒ 与日期不可能漂移。
4. **关账 / 重开留痕由数据层写。** `guard_finance_voucher_period_trail`（AFTER UPDATE 触发器）
   在状态迁移时写留痕 ⇒ **任何路径**关账都有关账记录（包括 12.5.2.1 的过渡端点）。

## 关键 trade-off

### ① 重开是**两阶段**的，不是一次点击

蓝图只说「重开已关期间是高风险操作，必须审批留痕」，没说审批是同步还是异步。

- 现实里审批是异步的（identity 侧有审批流定义 / 规则 / 待我审批 / 审批记录四个模块）⇒
  一次 `POST /reopen` 不可能既提交审批又立刻重开。
- **选定**：首次调用**提交审批申请、期间保持关闭**；审批通过后再调用一次才真正重开。
- **四种结局都如实报出来**（`outcome` 字段）：`REOPEN_REQUESTED` / `PENDING_APPROVAL` /
  `REJECTED` / `REOPENED`。前端**只有** `REOPENED` 才显示成「已重开」，
  其余三种明说「期间仍然关闭」。
- **被驳回时如实报「已驳回」**，不替用户悄悄换一次新申请（那会让「驳回」这件事消失）；
  下一次调用时本地镜像里已是 REJECTED ⇒ 那时才提交新申请。
- **没有注册生效审批流 ⇒ 拒绝重开**（不是默认放行）；**审批服务不可达 ⇒ 整个调用失败、
  期间保持关闭** —— 绝不「读不到就当批过了」。

### ② 关账留痕放在数据层而不是服务层

12.5.2.1 留了过渡端点 `POST /finance/vouchers/periods/{id}/close`，它也是 12.5.2.1 的文件，
本模块**不改**它。留痕若只写在服务层，走那条路径关掉的期间就**没有关账记录**
⇒ 蓝图输出里的「关账记录」会漏。**落成 AFTER UPDATE 触发器**，任何路径都留痕。

### ③ 期间身份字段不可改

凭证按 `period_id` 引用期间 ⇒ 改期间编码 / 区间 / 本位币 / 日历 / fiscal 周期 = 改账期。
DB 触发器逐字比对，**直连 SQL 也改不了**。与 12.5.1.1「科目编码一经入账不可改」同一条纪律。

### ④ 关账检查清单**不做**

蓝图 12.5.1.3「关账」明写「关账检查清单是防错最后一道关」，属**另一个模块**（未交付）。
本模块只提供「关账这个动作」与「关账记录」，**不假装**跑过清单 ——
能力声明报 `available=false` 并写明归属模块，页面上也如实标出。

## 文件清单

**迁移**

- `backend/services/finance/src/main/resources/db/migration/V359__finance_accounting_period.sql`
  —— 接管 `finance_voucher_period` + 三张新表
  （`finance_accounting_calendar` / `finance_accounting_period_event` /
  `finance_accounting_period_approval`）+ 五条守卫 + 索引 + RLS + GRANT。

**后端**

- `backend/services/finance/src/main/java/com/starcon/finance/AccountingPeriodModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/AccountingPeriodService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/AccountingPeriodController.java`
  —— 端点 15 个挂 `/finance/accounting-periods`，权限 `finance.accounting_period.read/.create/.update`。

**测试**

- `backend/services/finance/src/test/java/com/starcon/finance/AccountingPeriodIntegrationTest.java`（35 例）
- `backend/services/finance/src/test/java/com/starcon/finance/AccountingPeriodTenancyIntegrationTest.java`（4 例）

**前端**

- `apps/web/src/components/finance/accounting-period-model.ts`
- `apps/web/src/components/finance/accounting-period-model.test.ts`
- `apps/web/src/components/finance/accounting-period-workspace.tsx`
- `apps/web/src/app/finance/accounting-periods/page.tsx`
- `apps/web/src/app/api/finance/accounting-periods-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**
  （首 token `accounting-periods`，插入锚点：第一个 `prefix: 'identity/'` 那条元素之前）。

**契约 / 台账**

- `packages/api-contracts/src/contracts/finance.ts` —— codegen 产物，新增 21 个 `FinanceAccounting*` 类型。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 12.5.1.2 那一行的「状态」「证据」两格。

## 验证

- L0：`./mvnw -q compile` EXIT=0；`pnpm typecheck` 3/3 successful；`pnpm lint` 3/3 successful。
- L1：`Tests run: 39, Failures: 0, Errors: 0`（Testcontainers PostgreSQL 16，迁移实跑到 V359）。
- **回归**：既有 12.5.2.1 凭证测试 `Tests run: 39, Failures: 0, Errors: 0`
  —— 本模块接管了凭证侧的期间表 ⇒ 必须证明没打断它。
- 前端本车道 2 文件 / 16 例全绿；**全量 allowlist 回归** `pnpm vitest run apps/web/src/app/api`
  230 文件 / 1300 例全绿。

## 后续待办

- **12.5.1.3 关账（未交付）** —— 关账检查清单（阻断项 / 可结转未清项）归它。
  交付后本模块的关账应与清单联动（当前**刻意不跑**清单）。
- **12.5.2.2 报表（未交付）** —— 期末结转 / 结转损益 / 资产负债表取数都要按**财年**汇总；
  本模块已把 `fiscal_year` / `fiscal_period_no` / 期间类型备好。
- **12.5.2.1 凭证的过渡端点** —— `POST /finance/vouchers/periods/{id}/close` 与
  `POST /finance/vouchers/periods` 仍在；本模块已有自己的 `/finance/accounting-periods/*`。
  后续可让 12.5.2.1 端点收敛到本模块（**当前不动**，避免跨模块改文件）。
- **导航注册缺口** —— `packages/ui/src/lib/task-pages.ts` **刻意未登记**
  （同批四条车道并发改同一文件必然冲突），留给调度者统一处理。
