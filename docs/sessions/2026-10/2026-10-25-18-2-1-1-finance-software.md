# 2026-10-25: 18.2.1.1 财务软件（崩溃恢复重派 · 补完轮）

## 摘要

交付蓝图 18「集成开放与智能自动化」→ 18.2「外部系统对接」→ **18.2.1.1「财务软件」**。

**本车道是崩溃恢复重派（第二轮）。** 上一位 worker 已把**后端主体代码**安全提交
（`9f59e22d`：迁移 V382 ×2 + 5 个 Java 主源码），但在**开始写测试**时被环境故障（500）打断。
本轮的职责是**把剩下的测试、前端、契约、台账、会话日志补完**。

接手时工作区是**干净的**（`git status` 无改动），所以本轮没有「未提交的半成品」要认领 ——
这与上一位 worker 的处境完全不同，他照「先提交再干活」的纪律保住了主体代码。

## 接手情况：读懂了什么 / 补完了什么 / 改了什么

### 上一轮已提交（`9f59e22d`，本轮**不动结构**，只修三处映射错位）

- `V382__finance_software.sql`（platform，七表 + FORCE RLS + GRANT + 两个发号序列）
- `V382__platform_finance_permission.sql`（identity，六个权限码）
- `FinanceSoftwareModels` / `FinanceSoftwareService` / `FinanceSoftwareController`
- `FinanceVoucherPort`（finance 8087 只读端口）/ `FinanceSoftwarePushPort` /
  `UnavailableFinanceSoftwarePushPort`

### 本轮补上的

- **后端测试**：两个测试类，39 例（原样缺两件）
- **前端整块**：模型层 + 模型单测 + 工作区 + 页面（原样缺四件）
- **api-proxy 白名单**一条 + 白名单回归测试（原样缺两件）
- **契约重生成**（codegen 产物，只读）
- **台账**那一行 + **本会话日志**

### 就地修掉的三个实质缺陷（**上一轮的主体代码在此之前整模块不可用**）

这三处都是「照着列清单数占位符」就能发现的错位，不改任何职责边界。
前两处让**整个模块 100% 不可用**（任何一笔写入都撞 409），第三处让**余额核对在有未映射科目时必炸**。

1. 🔴 **`platform_finance_account` 的 INSERT 给 `created_by` 套了 `::timestamptz`**。
   `created_by` 是 uuid，PG 直接拒：`cannot cast type uuid to timestamp with time zone`
   ⇒ **「登记连接档案」这一格必然 409**，后面科目映射 / 导出 / 核对全部无从谈起。
   修法：13 列对 13 个值，cast 只落在 `created_at` / `updated_at` 上。

2. 🔴 **`platform_finance_reconcile_task` 的 INSERT 只有 11 个值对 12 列**。
   少一个占位符（`created_by`），PG 报 `INSERT has more target columns than expressions`
   ⇒ **「建核对任务」这一格必然 409**。
   修法：`'DRAFT'` 之后依次是 remark / created_by / created_at / updated_at。

3. 🔴 **余额核对的 ONLY_LOCAL 差异行把 `external_subject_code` 写成空串**。
   迁移里 `CHECK(btrim(external_subject_code) <> '')` 会拒，
   且 `UNIQUE(tenant_id, task_id, external_subject_code)` 也要求它非空
   ⇒ 只要本地试算平衡表里有一个**没配科目映射**的科目，整次核对就在 DB 层炸掉。
   修法：外部科目列放**带前缀的占位符** `LOCAL:<本地科目编码>`。

   **为什么不用裸的本地科目编码**（迁移文件头的注释写的是「外部没有的用本地编码占位」）：
   对方系统里**恰好用同一个编码**、而我们**没配映射**时，外部那一行（ONLY_EXTERNAL）
   与本地那一行（ONLY_LOCAL）会撞上 `UNIQUE(tenant_id, task_id, external_subject_code)`
   ⇒ 整个核对事务回滚。带前缀 ⇒ 不可能与真实外部编码相撞。
   代价是差异表里多一个 `LOCAL:` 前缀 —— 前端把它显示成「无外部科目（本地独有）」，
   **不**把占位符原样甩给财务（那看着像个真实的外部科目编码）。

另修**两处本轮自己写错的地方**（已记在各自的 commit 里）：
夹具 SQL 里同样数错了占位符；以及模型里 `exportSummary` 的局部变量**与模块顶层的路径前缀
`base` 同名遮蔽** —— 那条「从源码反查调用点」的守卫会把它抽成一条假路径。

## 决策 / 结论

### 「本系统管业务账、外部软件管法定账」在代码里的落点

蓝图加粗句是**硬约束**，落到三件事上：

- **衔接方式就是「凭证文件 + 科目映射」**：导出时本地科目被换成外部科目编码，
  核对时外部科目被换回本地科目编码 —— 映射表是这个模块的中枢。
- **职责边界到「导出标准凭证文件 + 推送 + 核对差异」为止**：本模块**不**接管法定账，
  **不**建凭证表、**不**抄凭证（凭证是会计事实，抄一份就会漂移）。
- **导出的文件是「法定账的输入」，不是「本系统已入账」**：前端文案按这个口径写。

### 为什么凭证文件要从**凭证列表 + 明细**导出，而不是总账

上一轮的设计结论（复核后采信）：总账把冲销轧差了，拿它出法定凭证会丢信息。
所以走 `GET /finance/vouchers`（只取 `POSTED` —— **草稿不是账**）逐张取分录行。

### 为什么直连推送恒 409 且一次数据都不写

照 platform `UnavailableAiTextGenerationPort` 与 crm `UnavailablePlatformMessageSendPort` 的先例：
`FinanceSoftwarePushPort` + `UnavailableFinanceSoftwarePushPort`。
理由是**一条返回成功结果的假通道会让「凭证已经推给金蝶了」在数据上看起来发生过，
而现场一个凭证都没推出去** —— 财务会拿着一张**不存在的**法定凭证去做账。

前端对应地**不提供**这个按钮（一个点了必然 409 的按钮会让运营以为「点一下就能推」），
改成按**能力声明**如实显示「未接入（归 18.2）」+ 现在怎么办的那条路。

### 金额与方向：两处最容易出错的地方

- **金额一律 `BigDecimal` + `numeric(20,6)`**：本模块一个 `double` 都没有，测试逐列断言
  「六张表里没有任何浮点列」。前端只做**显示**（`toFixed` 去尾零），不做任何记账运算。
- **余额核对的两边口径**：本地余额按**科目余额方向取正负**（与上游试算平衡表一致），
  外部余额按**导入时的方向列**归一化。两边方向口径不同 ⇒ 同一个科目凭空差出一倍。
  **不把外部余额翻到本地方向**：本地借 100 / 外部贷 100 ⇒ 事实就是差 200，
  翻一下就把差额抹成 0 —— 那是在**抹平**一个真实存在的差异，不是「归一化」。
- **两边都是 0 不是差异**：外部余额 0 且本地无对应科目 ⇒ 跳过；本地余额 0 且外部没这一行 ⇒ 跳过。
  把 0 报成差异只会让差异表被噪声淹没。
- **差异总额取绝对值口径**：净额口径会让正负相抵，看起来「对上了」。

### 契约是 codegen 产物

`packages/api-contracts` 是只读生成物，**没有手改**：改了后端 DTO 再重新生成。
本轮用九个服务各自的 `OpenApiContractTest` 导出 spec，再跑
`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`。
结果**只有 platform.ts 与 platform.openapi.json 变化**，其余 8 个服务逐字节不变。

### 台账那一行的 diff 比兄弟车道大

`module-progress.md` 是 `sync.mjs` 生成的（本文件头部就写着「请勿手工增删行」）。
本轮跑了一次 sync，除 18.2.1.1 那一行外，还**恢复**了若干被别的车道手改时丢掉的**空行**
（canonical 格式），并重算了统计（已交付 402 → 405，其中 3 格不是我改的 ——
18.1.1.1 / 18.4.2.1 / 13.4.2.2 早已是「已交付」但统计没跟上）。
**逐行核对过：465 行里只有 18.2.1.1 一行的状态/证据变了**，其余模块的交付状态一字未动。

## 上游依赖自检

| 上游 | 蓝图写的 | 实际存在 | 结论 |
| --- | --- | --- | --- |
| 12.5.2.1 凭证 | finance（8087） | `V357__finance_voucher.sql` + `GET /finance/vouchers` + `/vouchers/{id}` + `/vouchers/options` | ✅ 已交付；**用真形状的 JSON 桩**（options 的 `periods` 是 `{id, name}`，`name` 装的是 period_code） |
| 12.5.2.2 财务报表 | finance（8087） | `GET /finance/financial-reports/trial-balance` | ✅ 已交付；行形状 `subjectCode / subjectName / currency / balanceDirection / openingBalance / closingBalance / totalRow` 逐字段比对过 |
| 外部财务软件适配器 | — | **不存在**（backend / services / tools 下没有 kingdee / yonyou / sap / odoo 客户端） | ⚠️ **未接入** ⇒ 409 + 一次数据都不写 |

## 验证（真实跑出来的）

- **L0 编译闸**：`cd backend && ./mvnw -q compile` → **绿**（exit 0，无输出）
- **L0 类型闸**：`pnpm typecheck` → **绿**（3/3 tasks successful）
- **Lint**：`pnpm lint` → **绿**（3/3 tasks successful；输出里的 warning 是别的模块的既有文件上的 Next.js `outputFileTracingRoot` 提示）
- **L1 模块闸**：
  `./mvnw -pl services/platform -am test -Dtest='FinanceSoftware*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **Tests run: 39, Failures: 0, Errors: 0, Skipped: 0**
  （`FinanceSoftwareIntegrationTest` 32 + `FinanceSoftwareTenancyIntegrationTest` 7；
  真 Testcontainers PostgreSQL 16，两个测试类都在 `@AfterAll` 里 `.stop()`）
- **前端本模块测试**：
  `pnpm vitest run apps/web/src/components/integrations/finance-software-model.test.ts apps/web/src/app/api/platform/finance-software-allowlist.test.ts`
  → **32 例全过**（模型 27 + 白名单 5）
- **api-proxy 全量 allowlist 回归**（改了共享白名单，必须跑）：`pnpm vitest run apps/web/src/app/api`
  → **251 文件 / 1416 例全过**（未误伤任何已交付车道的路由）
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  → 7011 个 DTO / 9 个服务文件 / 9 份 OpenAPI 快照；**只有 platform.ts 与 platform.openapi.json 变化**，
  其余 8 个服务逐字节不变；新增 **24 个 `FinanceSoftware*` 类型 + 18 条路径**（旧新 path 集合逐条对过：零路径丢失）
- **未跑**（按任务书由调度器在集成头统一跑）：`./mvnw verify` 全量、整个 platform 服务的测试、
  `pnpm --filter @starcon/web build`

### 多租户三条断言（AGENTS.md 明文要求，缺任何一条都不算交付）

1. **两租户互不可见** —— 科目映射 / 主数据映射 / **凭证导出记录**逐张列表隔离，
   再加按主键直查对方行也拿不到，事务外零行。
2. **伪造租户上下文无效** —— 无租户上下文时写入被 RLS 挡住（断言栈里有 `row-level security`）；
   伪造 `X-Tenant-Id` / `X-Internal-Key` 头的请求**必须仍然写入成功**，
   且行落在**会话租户**名下 —— 只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它。
3. **连接池归还后不串租户** —— 连接池**压到 1 条连接**（池大于 1 时「没串」可能只是碰巧换了条干净连接），
   交替 5 轮各自只看见自己的行，归还后事务外仍零行。

另加两条本模块特有的：七张表逐张验 ENABLE + **FORCE** RLS / `tenant_id NOT NULL` /
`platform_app` 真的有 SELECT；**凭证文件正文也不串租户**（列表不串不够，正文串了才是法定凭证泄露）。

## 文件清单

**后端主源码（本轮只改了一处：`FinanceSoftwareService.java` 的三处占位符）**

- `backend/services/platform/src/main/java/com/starcon/platform/FinanceSoftwareService.java`

**后端测试**

- `backend/services/platform/src/test/java/com/starcon/platform/FinanceSoftwareIntegrationTest.java`（32 例）
- `backend/services/platform/src/test/java/com/starcon/platform/FinanceSoftwareTenancyIntegrationTest.java`（7 例）

**前端**

- `apps/web/src/components/integrations/finance-software-model.ts`（模型 + 展示口径）
- `apps/web/src/components/integrations/finance-software-model.test.ts`（27 例）
- `apps/web/src/components/integrations/finance-software-workspace.tsx`（六页签工作区）
- `apps/web/src/app/integrations/connectors/finance-software/page.tsx`（页面）
- `apps/web/src/app/api/platform/finance-software-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条** services[] 元素，62 行插入 / **零删除**）

**契约 / 台账 / 日志**

- `packages/api-contracts/src/contracts/platform.ts`（codegen，+24 个 `FinanceSoftware*` 类型）
- `packages/api-contracts/src/platform.openapi.json`（codegen 快照，+18 条路径）
- `docs/product/capability-blueprint/module-progress.md`（**只有 18.2.1.1 那一行** + 脚本自动重算的统计）
- `docs/sessions/2026-10/2026-10-25-18-2-1-1-finance-software.md`（本文件）

## 后续待办

1. **外部财务软件适配器（金蝶 / 用友 / SAP / Odoo）仍未交付** —— 归 **18.2 卷级**
   （外部财务软件适配器是 18.2 各模块共用的外部通道，不属于某一格 L4）。
   本模块的「直连推送凭证」「直连同步主数据」在页面上如实显示为「未接入」。
   适配器交付后，只要换掉 `UnavailableFinanceSoftwarePushPort` 的实现，`POST /platform/finance-software/push`
   立刻变成真的，页面上的「直连推送」按钮也会按能力声明自动出现
   （`canPush()` 只在后端声明 `available=true` 时才渲染那个按钮）。
   ⚠️ 适配器落地时必须**真的**把凭证推过去并落回执 —— 一条假的成功回执比 409 危险得多。
2. **18.2.2.1 支付与资金 / 18.2.3.1 其他对接**：同卷兄弟，本车道**不依赖**它们，
   也**没有**顺手实现它们（它们是另一套数据与口径）。
3. **18.1.2.1 事件订阅**：方向相反 —— 那一格管「外部系统推事件进来」，
   本模块是「本系统把凭证交出去」，不复用那条通道。
4. **下游 = 外部财务软件**：本模块只把文件交出去（导出 + 人工导入对方系统），
   **不**回写对方 —— 法定账那一侧不在本系统的职责范围内。
