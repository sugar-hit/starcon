# 12.5.1.3 关账（finance / 阶段 D6）

> 会话日期：2026-10-24 · 车道：`feat/12-5-1-3` · 蓝网点：`docs/product/capability-blueprint/12-finance.md` §12.5.1.3
> 迁移：`backend/services/finance/src/main/resources/db/migration/V363__period_close.sql`

## 决策摘要

实现 12.5.1.3「关账」：期末关账流程 —— 检查未处理事项（未对账差异 / 未结转成本 / 未核销款项 /
未过账凭证）→ 按已批准清单区分阻断项与可结转未清项 → 关闭期间 → 期间锁定。五条硬约束全部
**落成结构而不是文案**：

1. **职责边界切干净：12.5.1.2 拥有期间记录与状态机，12.5.1.3 拥有关账流程。**
   本模块**只 CREATE 六张自己的新表**、**读** `finance_voucher_period`；
   关账动作**直接调 12.5.1.2 已有的 `AccountingPeriodService.close(...)`** ——
   它的守卫（关闭人 / 时刻 / 原因必填）与关账留痕（`guard_finance_voucher_period_trail`）
   照旧生效，本模块**一个字都没改**别人的表。
   ⇒ **没有新建平行的期间表**、没有重做期间状态机、没有实现重开。
   「已关闭期间禁止过账」那道闸门（V357 的 `guard_finance_voucher_post_period`）
   **不重复实现、不改语义**，只在关账结果里如实反映「该期间已锁定、禁止过账」。
2. **检查清单真查数据，不是四个勾选框。**
   四类检查项各自查各自的真实数据源，并落**具体条数**与**可钻取明细**
   （`finance_period_close_check_item`）：
   未结转成本 → 12.4.1.2 `finance_cost_calculation_run`；
   未核销款项 → 12.1.1.1 `finance_receivable` + 12.2.1.1 `finance_receipt` + 12.3.1.1 `finance_settlement`；
   未过账凭证 → 12.5.2.1 `finance_voucher` 的 `status='DRAFT'`。
3. **未对账差异如实报「上游未交付 / 不可判定」。**
   12.3.2.1 对账任务 / 12.3.2.2 差异处理**未开发** ⇒ 该项
   `result='NOT_DETERMINABLE'`、`classification='CARRYWARD'`
   （表级 CHECK 钉死「不可判定必归可结转未清项」）。
   **既不假装通过，也不把关账卡死在一个永远无法满足的条件上。**
4. **阻断项 vs 可结转未清项：分类与依据都落库。**
   规则判定 `rule_classification` 与最终分类 `classification` 分两列存，规则判定**不可改**
   （DB 触发器逐字比对）；人工把阻断项下调为可结转未清项**必须**写例外依据
   （服务层 400 + DB 守卫各拒一次）。关账执行时**有结转项就必须写结转依据**（表级 CHECK 钉死）。
   **阻断项未解决 ⇒ 拒绝关账**（HTTP 409 + 结构化阻断原因清单，逐条带检查项 / 条数 / 依据）。
5. **多币种绝不相加。**
   金额按币种分组落库（`finance_period_close_check_amount` 唯一键含 `currency`）⇒
   **结构上不存在「跨币种求和」这一格**；汇率取不到就只分组呈现、不瞎折算；
   **刻意不产出跨币种的「合计」**。

## 关键 trade-off

### ① 清单上不存汇总数字，汇总一律现算

第一版把「几项阻断 / 几项结转」存在清单行上。问题来了：人工下调例外会改检查项行，
而清单是**只追加**的（举证凭据）⇒ 清单上的汇总立刻变成假数字。
**选定**：清单只存「这一次检查的元信息」（期间、run_no、时刻、备注），
汇总**一律从检查项行现算**；真正需要快照的是「关账那一刻」，它记在
`finance_period_close_execution` 上（那三列关账后不可改写）。

### ② 例外下调是**唯一**能绕过阻断项的口子，且必须留痕

蓝图只说「例外与结转须保留依据」，没说依据由谁写、怎么改。
**选定**：人工**只能把阻断项下调为可结转项**（不能反向升级、不能重复下调），
且必须写例外依据 —— 服务层 400、`guard_finance_period_close_check_classification`
触发器各拒一次 ⇒ 直连 SQL 也造不出「没有依据的例外」。
规则判定那一列**永远保留** ⇒ 事后仍能看出「它本来被判成阻断项」。

### ③ 「本期一次成本计算都没跑」判成阻断项（而不是不可判定）

成本批次表是**已交付**的 ⇒ 「没有运行记录」是**可判定的事实**，不是读不到。
若本期确实无存货业务，走例外下调 + 写依据即可。
（对比：未对账差异的上游**根本没交付**，那才是真正的「不可判定」。）

### ④ 提交关账被拒时用 **409 + 结构化阻断原因清单**，不是 400 也不是 200

前端 `api()` 在非 2xx 时抛 `ApiError` 并**保留响应体**（3.1.3.2 组合唯一性校验就靠它）⇒
前端把 409 的响应体翻译成一种**结局**（`BLOCKED`）而不是异常，阻断原因一条不少地带回界面。
用 400 会把「业务上被拦住」和「参数写错了」混成一种；用 200 则「拒绝关账」在协议上看不出来。

### ⑤ 提交结果里只给 `executionId`，不给整行

契约 codegen 的既有行为：一个「可空的对象引用」会把**被引用的类型本身**变成可空
（`FinancePeriodCloseExecutionRow` 会变成 `… | null`），那样整页代码都要到处判空。
**选定**：提交结果只带 `executionId`（`string | null`），调用方自己去取详情。

### ⑥ 关账动作复用 12.5.1.2 的关闭能力，不自己写状态机

蓝图说「输出：关账完成记录；期间锁定」，而期间状态机归 12.5.1.2。
**选定**：调用 `AccountingPeriodService.close(...)` ⇒ 关账留痕、期间冻结、版本乐观锁
全部复用，**本模块没有第二套期间状态机**。
代价：关账要带 `expectedVersion`（12.5.1.2 的乐观并发约定），
本模块在执行前读一次期间版本再传进去。

### ⑦ 期间锁定**不重复实现**

「禁止向已关闭期间录入业务凭证」由 V357 的 `guard_finance_voucher_post_period` 生效。
本模块**不碰那道守卫**，只在 `finance_period_close_execution.period_locked` /
`lock_note` 里**如实反映**它（并在页面上把这条说清楚）。
集成测试直接断言：关账后再 UPDATE 凭证状态为 POSTED 会被 DB 拒绝。

## 文件清单

**迁移（1）**
- `backend/services/finance/src/main/resources/db/migration/V363__period_close.sql`
  —— 六张新表 + RLS + 索引 + 授权 + 三道 DB 守卫。

**后端（3）**
- `backend/services/finance/src/main/java/com/starcon/finance/PeriodCloseModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/PeriodCloseService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/PeriodCloseController.java`

**后端测试（2）**
- `backend/services/finance/src/test/java/com/starcon/finance/PeriodCloseIntegrationTest.java`（17 例）
- `backend/services/finance/src/test/java/com/starcon/finance/PeriodCloseTenancyIntegrationTest.java`（4 例）

**前端（4）**
- `apps/web/src/components/finance/period-close-model.ts`
- `apps/web/src/components/finance/period-close-model.test.ts`（14 例）
- `apps/web/src/components/finance/period-close-workspace.tsx`
- `apps/web/src/app/finance/period-close/page.tsx`

**前端白名单（2）**
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，既有条目一字未改、也不重排）
- `apps/web/src/app/api/finance/period-close-allowlist.test.ts`（5 例）

**契约与台账（2，codegen / 人工维护）**
- `packages/api-contracts/src/contracts/finance.ts`（`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成，**未手改**）
- `packages/api-contracts/src/finance.openapi.json`（同上）
- `docs/product/capability-blueprint/module-progress.md`（**只改 12.5.1.3 那一行的「状态」「证据」两格**）

## 后续待办

1. **12.5.2.2 财务报表（本批同批在途，迁移 V360）**：它是本模块的**下游**
   （关账完成后才出报表）。本模块**不依赖**它、**不读**它的表，也**不因为它没到而阻断关账**。
   它落地后需要：① 报表按 `finance_period_close_execution` 的关账快照取数；
   ② 本模块的能力声明里那条 `12.5.2.2` 缺口要改成「已交付」。
2. **12.3.2.1 对账任务 / 12.3.2.2 差异处理（未开发）**：交付后要把
   「未对账差异」这一项从「不可判定」改成**真查数据**（查它们的差异表），
   并把 `finance_period_close_check.check_code='UNRECONCILED_DIFFERENCE'` 的判定规则接上。
   在那之前，本模块**如实报不可判定**，不假装通过。
3. **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未改**
   （四车道并发改同一文件必然冲突）⇒ 页面路由 `/finance/period-close` 已可用，
   但导航里的入口留给调度者统一注册。
4. **台账汇总数字**：本车道**只改了自己那一行**；`module-progress.md` 顶部的
   总览 / 分阶段 / 分系统统计在基线上就已经与其它车道不一致（`sync.mjs --check` 在
   干净基线上也报不一致）⇒ 由调度者在并入后统一跑一次 `node tools/module-progress/sync.mjs`。
