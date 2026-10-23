# 12.5.1.1 会计科目（V356 · 阶段 D6 · 车道 12.5.1.1）

> 蓝图原文：`docs/product/capability-blueprint/12-finance.md` 的 **12.5.1.1 会计科目**。
> 车道分支 `feat/12-5-1-1`，基线 `6a1b2fc0`（`merge(12.3.1.3)`，前序 `235a5d28 merge(12.4.2.1)`）。
>
> 蓝图四行原文：
> 「职责：维护法人账簿的会计科目表：科目编码、名称、类别（资产/负债/权益/成本/损益）、
> 余额方向、是否明细科目、关联税务属性；支持科目映射到业务事件。输入：科目定义；
> 业务事件映射（如发货 → 收入 + 结转成本）。输出：科目表 + 映射规则。关系：上游 = 用户会计体系；
> 下游 = 12.5.2.1 凭证、12.5.2.2 报表。**科目体系是用户自定的**，系统提供模板但必须允许完全自定义。」

## 决策摘要

### 1. 科目编码是**稳定引用键**：租户内唯一 + 一经入账不可改

下游 12.5.2.1（凭证，**同批并行开发**）按**科目编码软引用**本表 ⇒ 软引用要成立，
编码必须**唯一定位**且**不会指错**。三个前提都钉在数据层：

- `UNIQUE(tenant_id, subject_code)` ⇒ 只带 `subject_code` 就能唯一定位科目；
- BEFORE UPDATE 触发器拒绝改编码 ⇒ 已过账凭证里的编码不会指向另一个科目；
- 停用（`status='DISABLED'`）代替删除 ⇒ 历史凭证引用的科目不会消失。

⇒ 本模块因此给下游提供集成点：`GET /finance/account-subjects/by-code?subjectCode=xxx`
（命中时至多一条；没命中如实返回 `found=false`，**不返回「默认科目」顶替** ——
那会让凭证记到一个用户从没建过的科目上）。

### 2. 「科目体系是用户自定的」：模板只是**导入源**，不是约束

蓝图加粗句是最容易做歪的一句：做成「标准科目必须存在」的枚举，就等于替用户把账定死。
本模块的做法：

- 模板落在 `finance_account_subject_template`（**系统参考数据**，迁移播种，运行期**只读**），
  32 个企业会计准则通用科目，含一个**汇总科目 1122 应收账款** + 两个明细子科目
  （1122.01 境内 / 1122.02 境外）—— 让「是否明细科目」这条字段在模板里就有真实含义；
- 导入走 `POST /finance/account-subjects/templates/apply`，**已存在的编码被跳过而不是覆盖**
  （用户改过的科目不能被模板冲掉），被跳过的编码**逐个报出来**；
- 导入后**每个字段都能改**（名称 / 类别 / 余额方向 / 税务属性），也能停用和另建 ——
  服务层**没有**任何「标准科目必须存在」的校验。

### 3. 类别与余额方向**不联动**

「累计折旧」是资产类却记贷方余额。把两者钉死（CHECK 或服务层校验）会堵死真实账 ⇒
两个字段彼此独立，用户可自由改。

### 4. 业务事件映射：事件编码**由用户自定义**，不是枚举

蓝图输入写的是「业务事件映射（如发货 → 收入 + 结转成本）」。把事件类型写成枚举，
等于把用户的业务事件体系也写死 —— 与上一条同一种错误 ⇒ `event_type` 只校验格式
（`^[A-Z][A-Z0-9_]{0,63}$`），取值由用户定。

一条规则 = 事件 + 若干**分录行**（科目 + 借贷方向 + 金额口径）。模板**不固定金额**
（金额相等由下游 12.5.2.1 按业务事件实算），所以「平衡」按**借贷双向齐备**判定：
启用前至少一条借方 + 一条贷方；启用后增删改行仍要保持齐备。

⚠️ 「启用后仍要保持齐备」这条守卫必须是 **AFTER** 触发器：BEFORE 触发器里
「本次变更」还没落到表上，数出来的还是旧账 —— 守卫会形同虚设。

### 5. 作用域是**法人账簿**；账簿与法人主数据在 identity（跨服务不建 FK）

`accounting_book_id` + `legal_entity_id` 是 identity 17.1.2.2 / 17.1.2.1 的逻辑 ID，
本模块**只存逻辑 ID + 快照**，不建跨库 FK、不直连对方库、不建共享视图（AGENTS.md）。

identity 的 V43 注释原文：「期间设置……只定义**规则**，不生成期间行：
12.5 会计科目与期间才是按这套规则生成并封账的地方，identity 不越界替 finance 建账。」
⇒ 本模块**只管科目**，**不生成会计期间行**（那是 12.5.1.2，未交付）。

前端页面的**法人账簿下拉走 identity 的授权 API**（`identity/accounting-books`，
web 代理已放行）读真实账簿；读不到时**如实说读不到**并允许手填账簿 ID，
不拿假账簿顶替。

### 6. 九条守卫钉在 DB 层（服务层与 DB 各判一次）

① 编码不可改；② 不可跨租户 / 跨账簿搬家；③ 下级类别必须与上级一致（改本级时还要查下级）；
④ 层级不得成环（沿上级链回溯，深度上限 50）；⑤ `level` 由触发器派生；
⑥ ACTIVE 科目不可物理删除；⑦ 映射行只能指向 **ACTIVE 明细科目**；⑧ 规则与科目必须同一本账簿；
⑨ 启用前借贷齐备、启用后改行仍须齐备。

## 关键 trade-off

| 选择                                             | 取舍                                                                                          |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| 科目编码**不可改**（停用 + 另建）                 | 用户改不了编码会别扭，但下游凭证按编码软引用 —— 编码可改等于让历史凭证指错科目                  |
| 模板表**无 tenant_id**（系统参考数据）            | AGENTS.md 要求业务表带 tenant_id；模板是**系统提供**的，各租户共享同一份（与 identity 的 permission/page_resource 同性质），运行期只读 |
| 模板导入**跳过**已存在编码而非覆盖                 | 「一键重置成标准模板」很方便，但会冲掉用户改过的科目 —— 错账比不方便贵                        |
| 映射规则**不固定金额**，只给「取哪个量」           | 少了一个可直接过账的模板；但金额口径属于业务事实，猜错就是错账                                |
| 借贷齐备按**双向齐备**判定而非金额相等             | 模板没有金额可比；金额相等是下游凭证按业务事件实算的事                                        |
| 科目**不提供 DELETE**（停用代替）                  | finance 的 API 出口刻意不导出 DELETE；删除会让历史凭证引用的科目凭空消失                        |

## 文件清单

**后端**

- `backend/services/finance/src/main/resources/db/migration/V356__finance_account_subject.sql`
  （五张表 + 九条守卫 + RLS/GRANT + 模板播种）
- `backend/services/finance/src/main/java/com/starcon/finance/AccountSubjectModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/AccountSubjectService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/AccountSubjectController.java`
- `backend/services/finance/src/test/java/com/starcon/finance/AccountSubjectIntegrationTest.java`（24 例）
- `backend/services/finance/src/test/java/com/starcon/finance/AccountSubjectTenancyIntegrationTest.java`（5 例）

**前端**

- `apps/web/src/components/finance/account-subject-model.ts`
- `apps/web/src/components/finance/account-subject-model.test.ts`（12 例）
- `apps/web/src/components/finance/account-subject-workspace.tsx`
- `apps/web/src/app/finance/account-subjects/page.tsx`
- `apps/web/src/app/api/finance/account-subjects-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，既有条目一字未改）

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/contracts/finance.ts`（+24 个 `FinanceAccountSubject*` 类型）
- `packages/api-contracts/src/finance.openapi.json`（+13 路径 / +24 schema / 删 0 / 既有零变化）

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md`（只改 12.5.1.1 那一行的「状态」「证据」两格）
- `docs/sessions/2026-10/2026-10-24-account-subject.md`（本文件）

## 验证

- L0：`cd backend && ./mvnw -q compile` → EXIT=0；`pnpm typecheck` → 3/3 successful；
  `pnpm lint` → 3/3 successful。
- L1：`./mvnw -pl services/finance -am test -Dtest='AccountSubject*,OpenApiContractTest'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → `Tests run: 30, Failures: 0, Errors: 0` BUILD SUCCESS
  （`AccountSubjectIntegrationTest` 24 + `AccountSubjectTenancyIntegrationTest` 5 + `OpenApiContractTest` 1）。
- 前端本车道：`pnpm vitest run apps/web/src/components/finance/account-subject-model.test.ts
  apps/web/src/app/api/finance/account-subjects-allowlist.test.ts` → 2 文件 / 17 例全绿。
- 全量 allowlist 回归（本车道改了共享白名单 `api-proxy.ts`，**必须**跑全量）：
  `pnpm vitest run apps/web/src/app/api` → 226 文件 / 1280 例全绿。

## 后续待办

- **12.5.2.1 凭证（同批并行）**：凭证行按 `subject_code` 软引用本表；需要「类别 / 余额方向 /
  是否明细」时走 `GET /finance/account-subjects/by-code` 或 `/{id}`。
  本模块**不造凭证、不造分录金额**，两边都不重复造科目表。
- **12.5.1.2 会计期间 / 12.5.1.3 关账**：未交付。本模块不生成期间行，也不参与关账检查。
  期间规则（`period_type` / `fiscal_year_start_month` / `first_period_start`）在 identity 的
  `accounting_book` 上，只定义规则。
- **12.5.2.2 报表**：未交付。本模块按类别把科目分好了，但不产出任何报表数字。
- **账簿归属校验**：本模块对 `accounting_book_id` 只做**软引用**，没有向 identity 反查
  「这本账是不是本租户的」—— identity 的 `GET /identity/accounting-books` 需要
  `identity.accounting_book.read` 权限（默认只有 OWNER 有），finance 侧强行反查会让
  财务专员的写操作被权限卡死。真要强校验，需要 identity 侧提供一个 finance 可用的
  **内部只读端点**（当前 identity 只有 `/internal/access`）。
