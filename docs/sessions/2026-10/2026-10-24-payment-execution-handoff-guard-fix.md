# 2026-10-24: 付款执行交接守卫修复（12.2.2.2 handoff guard fix）

> 车道：`fix/12-2-2-2-handoff`（worktree `.worktrees/12.2.2.2-fix`），基线 `integration/module-dev` @ `6a1b2fc0`。
> 触发：上一批并入集成头的 12.2.2.2「付款执行」让 finance 服务 L2 红了 —— 444 例里 **4 红**，
> 全部集中在 `PaymentOrderIntegrationTest` 一个测试类（其余 440 例绿）。
> 本车道只做**修复**，不改功能面：修的是 12.2.2.2 交接面里 12.2.2.1 那一侧被带坏的三处。

## 摘要

12.2.2.2 把「付款执行归属」的写入守卫从 12.2.2.1 接管过去（迁移 `V350__finance_payment_execution.sql`），
但接管方式有一个**真 bug**：V350 用 `CREATE OR REPLACE FUNCTION` 换掉了 V346 的**表无关**共享触发器函数
`guard_finance_payment_order_child_frozen()`，把函数体改成带**表专属字段**比对的版本
（分批分支引用 `NEW.seq`、明细分支引用 `NEW.active`），**却没有重建 V346 挂上去的两个触发器** ——
两个触发器仍然指向这个共享函数体 ⇒ 触发器挂在哪张表上，哪张表就缺那个字段：

| 触发器所在表 | 第一个被规划的 IF 分支 | 实际报错 |
| --- | --- | --- |
| `finance_payment_order_line`（明细行） | 分批分支 | `record "new" has no field "seq"` |
| `finance_payment_order_installment`（分批） | 明细分支 | `record "new" has no field "active"` |

**两向都炸**（不是只有明细行那向）。后果：付款单提交后（父单 status ≠ DRAFT）修改子表任何
非放行字段，抛出来的是一条看不懂的错，而不是预期的「明细与分批不可再改」。
另外，12.2.2.1 有三处断言与「未交付」声明还停留在「12.2.2.2 未交付」的世界里，也一并收口。

## 决策 / 结论

### 1. 触发器函数**拆成两个表专属的**，而不是继续共用一个（正解）

- `guard_finance_payment_order_line_frozen()` —— 只引用明细行表上真实存在的字段
  （`tenant_id` / `id` / `order_id` / `line_no` / `payable_line_id` / `description` /
  `currency` / `amount` / `payable_amount_snapshot` / `payable_paid_snapshot` / `active`）；
- `guard_finance_payment_order_installment_frozen()` —— 只引用分批表字段
  （`tenant_id` / `id` / `order_id` / `seq` / `planned_paid_date` / `currency` / `amount`）。

V346 建的**两个触发器重建**并各自指向对应函数；旧的表无关共享函数 `DROP FUNCTION` ——
留着它是给下一个人埋雷（谁再把它挂到某一张表上，就重蹈同一个覆辙）。

### 2. 两条放行路径**逐条保留**，一条都没丢

- **明细行放行**：只改 `active`（true→false = 付款单转终态时释放明细占用；
  false→true = 失败后显式重发把明细重新锁回本付款单），其余字段逐字不变 ⇒ 放行。
  `updated_at` / `created_at` **不参与比对** —— 释放与重发路径都会改 `updated_at`。
- **分批放行**：推进执行态（`status` / `execution_available` / `failure_reason` / `updated_at` 可变），
  但**金额 / 币种 / 计划付款日仍然冻结**（这三个一变就拒绝）。

其余语义一字未改：父单 `status <> 'DRAFT'` 时除上述两条路径外一律抛错，文案仍是
`付款单已提交（%），明细与分批不可再改：需要改请被驳回后退回草稿再改`，
`USING ERRCODE = 'check_violation'`；父单不存在（级联中）⇒ 放行；
BEFORE 触发器 **UPDATE 必须 `RETURN NEW`**、**DELETE 必须 `RETURN OLD`**
（返回 OLD = 把这一行改回旧值 = 静默失效，比报错更难查）。

### 3. 就地改 V350，**不新增补偿迁移**

V350 **从未 push 到远端、也从未被任何持久库应用过**（本机没有持久 dev 库，CI 每次都是全新
Testcontainers 库）⇒ 直接就地改最干净。**不新增 V354 之类的补偿迁移** ——
V354–V357 已预分配给本批另外 4 条车道，撞号会让别人的 Flyway 直接炸。

### 4. 12.2.2.2 已交付 ⇒ 12.2.2.1 的「未交付」声明全部收口（**未走安全退路**）

前端侧核查过：前端**不消费**付款单能力声明里 12.2.2.2 那条的内容（`unavailable` 列表与
`downstreamGaps` 都是按接口返回动态渲染，且都带 `length > 0` 守卫），前端测试也没有断言
能力声明的内容 ⇒ 改动面可控，不必退让。于是：

- 能力声明 12.2.2.2 那条 `available` 由 `false` 改 **`true`**，文案改成如实描述
  「写路径已开放 + 状态守卫的更强规则」；
- `downstreamGaps` 里 12.2.2.2 那条**移除**（已交付 ⇒ 不再是缺口），交接面要点折进能力声明的 detail；
- Javadoc 与前端静态文案里的「12.2.2.2（未交付）」全部更新。

⚠️ `paymentExecutionLabel(executionAvailable, ownerModule)` 的 **`false` 分支保留**
（它是 V343 的 `finance_payment_plan` / `finance_payment_batch` 那些恒为 false 的表要的读法），
`payment-order-model.test.ts` 里对 `false` 分支的断言**继续有效**。

## 关键 trade-off / 讨论

- **为什么不用 `to_jsonb(NEW)->>'seq'` 绕过**：那会把金额 / 日期的比较**退化成文本比较**
  （`'100.0000'` vs `'100.0000'`、日期格式差异）⇒ 「金额到底变没变」的语义坏掉。
  拆函数是正解，绕过是假解。
- **为什么多写一条回归测试**：分批的「执行态放行 / 钱的事实冻结」这条路径，
  12.2.2.2 自己的测试**根本没覆盖**（它 seed 的付款单**不建分批**）⇒ 改守卫函数属于「改了一个没有测试保护的行为」。
  按 AGENTS.md「每个新行为必须有测试」，补一条
  `提交之后分批的金额币种计划日冻结但执行态放行`：分批的金额 / 币种 / 计划付款日一变就拒、
  执行态推进被放行（并交给更强的状态机守卫）、明细的 `active` 可释放可重新占用。
- **取证方式**：把 V350 单独 `git stash` 回旧版复跑同一批断言，稳定复现
  `PSQLException: ERROR: record "new" has no field "seq"` 与 `record "new" has no field "active"`
  ⇒ 根因与修复都实证过，不是「读代码觉得对」。
- **连带跑的测试**：V350 的触发器改动**直接命中** 12.2.2.2 的「付款单转终态释放明细占用 /
  失败后重发重新锁回」两条路径 ⇒ 除任务书指定的 `PaymentOrderIntegrationTest` 外，
  另跑了 `PaymentExecutionIntegrationTest` + `PaymentExecutionTenancyIntegrationTest`（20 例）。

## 涉及文件

**迁移（真 bug 的落点）**
- `backend/services/finance/src/main/resources/db/migration/V350__finance_payment_execution.sql`
  —— 拆两个表专属触发器函数 + 重建两个触发器 + `DROP` 旧共享函数。

**测试**
- `backend/services/finance/src/test/java/com/starcon/finance/PaymentOrderIntegrationTest.java`
  —— 3 处过时断言 + 能力声明断言 + 新增 1 条回归测试。

**后端声明（12.2.2.1 自己的文件）**
- `backend/services/finance/src/main/java/com/starcon/finance/PaymentOrderService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/PaymentOrderController.java`
- `backend/services/finance/src/main/java/com/starcon/finance/PaymentOrderModels.java`

**前端静态文案（12.2.2.1 自己的页面）**
- `apps/web/src/components/finance/payment-order-model.ts`
- `apps/web/src/components/finance/payment-order-workspace.tsx`
- `apps/web/src/app/finance/payment-orders/page.tsx`

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（只改 12.2.2.2 那一行的证据格，未重排整表）
- `docs/sessions/2026-10/2026-10-24-payment-execution-handoff-guard-fix.md`（本篇）

**未动**：V343 的 `finance_payment_plan` / `finance_payment_batch`（它们的 `execution_available`
恒为 false 是对的）、别的模块的迁移 / 端点 / 页面、`apps/web/src/lib/api-proxy.ts`。

## 验证

- L0 编译闸：`backend/ && ./mvnw -q compile` → **EXIT=0**
- L0 类型闸：`pnpm typecheck` → **3/3 successful**（`@starcon/web` cache miss 实跑 `tsc --noEmit`）
- L0 lint：`pnpm lint` → **3/3 successful**（仅既有文件的既有 warning）
- L1 模块闸：`./mvnw -pl services/finance -am test -Dtest='PaymentOrderIntegrationTest' …`
  → **Tests run: 28, Failures: 0, Errors: 0** / BUILD SUCCESS
- 连带：`-Dtest='PaymentExecutionIntegrationTest,PaymentExecutionTenancyIntegrationTest'`
  → **Tests run: 20, Failures: 0, Errors: 0** / BUILD SUCCESS
- 前端：`pnpm vitest run apps/web/src/components/finance/payment-order-model.test.ts`
  → **19 passed (19)**

## 后续待办

- **12.2.2.2 的 `PaymentExecutionService` 里 `finance_payment_order_installment` 的 UPDATE 带了
  `version=version+1`，但该表**没有 `version` 列**（V346 的建表语句里没有；`version` 只加在
  V350 的两张执行表上）** —— 分批推进执行态那条分支一旦真的跑到就会炸。
  本车道**没动它**（不是本车道职责、也不是本车道文件），但这是 12.2.2.2 自己的一条真隐患，
  建议下一轮由 12.2.2.2 的车道单独处理（或给分批表补 `version` 列）。
- 12.2.2.1 的能力声明现在报 12.2.2.2 `available=true`、缺口列表已无 12.2.2.2 ——
  **12.2.2.2 的页面**（`/finance/payment-executions`）已交付，若 12.2.2.1 页面未来要加
  「去执行」跳转，需另开一轮。
- `apps/web/src/components/finance/payment-order-workspace.tsx` 在**基线上就不是 prettier-clean**
  （第 176 / 210 / 326 行，基线 `git show HEAD:` 版本同样报 warn）——
  本车道只改了注释与文案，**刻意没有**顺手格式化无关行（避免与并行车道产生无谓冲突）。
