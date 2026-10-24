# 12.5.2.2 财务报表（finance / 阶段 D6）

> 会话日期：2026-10-24 · 车道：`feat/12-5-2-2` · 蓝网点：`docs/product/capability-blueprint/12-finance.md` §12.5.2.2

## 摘要

12.5.2.2「财务报表」**整模块交付**：五张报表（资产负债表 / 利润表 / 现金流量表 / 科目余额表 / 明细账）
全部做完，各有独立口径；期间对比、导出（CSV / JSON 真内容 + 留痕）、**钻取到凭证**三件事都落地；
数据真落 PostgreSQL 16、取数走 jOOQ 显式 SQL，多租户三条隔离断言实跑通过。
蓝图加粗句「报表数字必须能钻到凭证」由**同源重算**保证 —— 钻取端点与报表端点用同一套取数代码，
所以钻出来的净额必等于报表上的数字。

## 决策摘要

实现 12.5.2.2「财务报表」：生成**资产负债表、利润表、现金流量表、科目余额表、明细账**，
支持期间对比、导出、钻取到凭证。五张报表**全部做完**（不是半成品），蓝图的三条硬口径
**落成代码与数据，而不是文案**：

1. **草稿 / 作废凭证绝不进报表** —— 取数只认 `status IN ('POSTED','REVERSED')`；
   报表响应里同时给出 `draftExcludedCount`（有多少张草稿被排除）与
   `reversedVoucherCount`（有多少张被红冲），**排除量是可见的，不是沉默的**。
2. **红冲按净额抵消** —— 原凭证与反向凭证一起参与计算（DB 层 `finance_voucher_reversed_ck`
   钉死 REVERSED 必指向那张红冲凭证、FK 保证成对）⇒ 红冲后的期末余额是 **0**，
   而不是 700 / 700 各挂一边。**余额列按净额落一侧**，本期发生额列保持毛额。
3. **多币种绝不相加** —— 按 `finance_voucher.currency` 分组返回，**不做本位币折算**：
   USD 与 EUR 摆在不同分组，不同分组之间没有「合计」。
4. **报表数字必须能钻到凭证**（蓝图加粗句）—— 每一行 / 每个合计项都带真实 `drillPath`
   （后端绝对路径 `/finance/financial-reports/drill?...`），钻取端点**重算同一套取数**
   ⇒ 钻出来的净额**必等于**报表上的数字（测试逐行断言，不是抽样）。
5. **机器自检** —— 每张报表都带「借贷平衡（本期）」；资产负债表另有**会计恒等式**
   （期末：资产 = 负债 + 权益 + 累计未结转损益）；科目余额表另有**试算平衡**（期末）。
   差额非 0 **如实报出**并说明原因，**不静默归零**。
6. **科目是软引用** —— 分类取自 `finance_account_subject` 的 LEFT JOIN，
   **不建 FK、不假装核对科目存在**：未登记的科目归「未分类」段落、**不并入合计**、
   如实报出差额。

## 五张报表各自的口径

| 报表 | 口径 | 符号规则 | 期间划分 |
| --- | --- | --- | --- |
| 资产负债表 | **余额口径**（含期初） | 资产段借−贷；负债 / 权益 / 损益结转段贷−借 | 本期 = 期间完全落在范围内的期间；期初 = 起始点之前的全部（无缝无重叠） |
| 利润表 | **本期发生额**（不含期初） | 收入段贷−借；成本费用段借−贷 | 同上（只用本期那一段） |
| 现金流量表 | **本期发生额**，只统计含现金科目的凭证 | 按**对方（非现金）分录行**归类：对方贷方=流入、对方借方=流出；全现金凭证视为内部转账不进表 | 同上 |
| 科目余额表 | **余额口径**（期初 / 本期 / 期末） | 按**科目余额方向**取正负 | 同上 |
| 明细账 | **逐笔流水**（必须给 `subjectCode`） | 同科目余额方向，逐笔滚动余额 | 同上（期初余额 + 逐笔） |

- **草稿**：一律不进报表（`DRAFT` / `VOID` 直接在 SQL 里排除）。
- **红冲**：净额抵消，余额列落一侧，本期发生额列保持毛额。
- **多币种**：**绝不相加、不做本位币折算** —— 一律按币种分组。

**符号规则按「段落」而不是按「科目」定**是刻意的：按科目定符号会让「红冲后借贷各 700」
的科目在资产段出现两个正数，恒等式随即不成立；按段落定符号则每张表的左右两侧
在结构上互为反号，**会计恒等式是构造出来的，不是碰巧成立的**。

## 关键 trade-off

### ① 现金流量表必须先配置「现金科目」

现金流量表的「现金」是**会计政策**，不是系统常量：把 `1001` / `1002` 硬编码成现金科目，
换一个租户（或换一个国家的准则）就是错的。

- 因此**必须**在报表模板里登记 `cashRole=CASH` 的科目才能出这张表。
- 未配置时返回 **0 行 + 一句说明**，能力声明里该条 `available=false` **如实标注**
  （缺口归谁写清楚），**不虚构一个「经营活动现金流 0」糊弄界面**。
- 现金科目的**对方科目**用来归类活动（经营 / 投资 / 筹资）——
  归类规则同样由模板承载（`cashFlowGroup`），系统**不预置任何一行**。

### ② 报表模板**可停用、不可删**

报表模板是**口径配置**。删掉它，「当时的报表是按什么口径出的」就再也复现不了 ——
而报表是要对外报送、要被审计追溯的东西。所以：停用（`enabled=false`）保留历史，
删除由 DB 触发器挡住。**没有 DELETE 端点**，前端也不提供删除按钮。

### ③ 模板可选：不配模板也能出四张表

资产负债表 / 利润表 / 科目余额表 / 明细账**不配模板也能出数**（按科目表的 `category` 归类）；
模板的价值在于**把多个科目归并成一条报表行**（如「营业收入」合并若干收入科目）
并**指定段落**。现金流量表是唯一的例外（见 ①）。

### ④ 期间是「期间序列」而不是日期区间

`periodFromId` / `periodToId` 是**会计期间的 id 序列**，不是两个日期 ——
「本月」「本季」「年初至今」都是期间序列；用日期区间会把跨期凭证算错。
对比期用同一套口径**重算**（不是减法），所以两段期间的口径永远一致。

### ⑤ 导出是文件，不是 JSON 结构

`/export` 返回 **CSV（带 UTF-8 BOM，Excel 打开中文不乱码）/ JSON 真内容**，
并**同时写一条 append-only 留痕**（`finance_report_export_event`，含 SHA-256）⇒
「导出了什么、什么时候、谁导的、内容哈希是多少」事后可核对。
前端**不能**用通用 `api()`（它会把响应当 JSON 解析），所以走 raw fetch + blob 下载。

## 踩过的坑

1. **MockMvc 不会解码 `%3A`** ⇒ 测试里用 `drillRequest(path)` 把 `drillPath`
   拆成 path + params（`URLDecoder.decode`）再请求；真实浏览器会正常解码。
2. **前端端点路径必须写成 `` `${base}/xxx${query({...})}` `` 的字面量形状** ——
   allowlist 测试要**从模型源码反向抽取真实调用点**再逐条断言放行；
   一旦写成 `${base}/xxx${query(reportQuery(params))}`（helper 调用），
   抽取正则匹配不到那段插值，测试会**把路径抽成 `…balance-sheet<uuid>`** ⇒ 假红。
   现在模型统一用对象字面量形状，与 12.5.2.1 的 `voucher-model.ts` 一致。
3. **白名单是累积型共享文件** —— 本车道只**新增**一条 `finance/` 元素，
   既有条目一字未改、也不重排；插入锚点固定在**第一个 `prefix: 'warehouse/'` 那条元素之前**
   （它的注释块很长，按元素的 `{` 定位而不是按 `prefix:` 行定位）。
   零命中核对：`grep -c "financial-reports" apps/web/src/lib/api-proxy.ts` ⇒ **0**。
4. **`Badge` 的属性是 `tone` 不是 `variant`，`SectionCard` 的右上角插槽是 `extra` 不是 `actions`**
   —— 照抄兄弟模块的写法会直接 typecheck 失败。

## ⚠️ 上游缺陷（本车道只上报、不修）

**12.5.2.1 凭证管理**的 `VoucherService.nextVoucherNo()` 用 `substring(voucher_no from 10)`
取序号，取到的是**带连字符的 `-0001`**，PostgreSQL 解析为 **−1** ⇒
**同租户同月第 3 张凭证的凭证号重复** ⇒ 创建返回 409「凭证号 VC-202610-0001 已存在」。
上游测试从未在同一个用例里建 2 张以上凭证，所以一直没暴露。

- **一行修复**：改成 `from 11`（`VoucherService.java` 是**他车道文件**，本车道**刻意未改**）。
- 本车道测试因此改用**直连 SQL 播种凭证**（仍先建草稿再过账 ⇒ 12.5.2.1 的
  触发器照样把关），并在测试 javadoc 写明原因。

## 文件清单

**迁移**

- `backend/services/finance/src/main/resources/db/migration/V360__finance_financial_report.sql`
  —— **两张新表、纯 CREATE TABLE、零 ALTER 零 DROP 既有表**：
  `finance_report_template`（报表模板，UNIQUE(tenant_id, report_type, subject_code)，
  **不可删除触发器**）+ `finance_report_export_event`（导出留痕，append-only + SHA-256）。
  两表 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + GRANT finance_app/starcon_app。

**后端**

- `backend/services/finance/src/main/java/com/starcon/finance/FinancialReportModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/FinancialReportService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/FinancialReportController.java`
  —— 端点 12 个挂 `/finance/financial-reports`，权限码 `finance.report.read / .create / .update`。

**后端测试**

- `backend/services/finance/src/test/java/com/starcon/finance/FinancialReportIntegrationTest.java`（24 例）
- `backend/services/finance/src/test/java/com/starcon/finance/FinancialReportTenancyIntegrationTest.java`（6 例）

**契约（codegen 产物，勿手改）**

- `packages/api-contracts/src/contracts/finance.ts` —— **+17 / −0**（17 个 `FinanceReport*` 类型）。
- `packages/api-contracts/src/finance.openapi.json` —— **+17 个 schema、+12 条 path，
  既有条目零改动**（结构化 diff 验证：`changed=0 removed=0`）。

**前端**

- `apps/web/src/components/finance/financial-report-model.ts`（端点函数 + 可读形态 helper）
- `apps/web/src/components/finance/financial-report-model.test.ts`（9 例）
- `apps/web/src/components/finance/financial-report-workspace.tsx`（真实工作区）
- `apps/web/src/app/finance/financial-reports/page.tsx`（路由页）
- `apps/web/src/app/api/finance/financial-reports-allowlist.test.ts`（5 例，双向断言）
- `apps/web/src/lib/api-proxy.ts` —— **纯新增一条 services[] 元素**（+59 行、零删除）。

**台账 / 日志**

- `docs/product/capability-blueprint/module-progress.md` —— **只改 12.5.2.2 那一行**的状态与证据。
- 本文件。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | **EXIT=0** |
| L0 类型 | `pnpm typecheck` | **3/3 successful** |
| L1 模块 | `cd backend && ./mvnw -pl services/finance -am test -Dtest='FinancialReport*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **Tests run: 30, Failures: 0, Errors: 0**（24 业务 + 6 租户）· BUILD SUCCESS |
| 前端（本车道） | `pnpm vitest run apps/web/src/components/finance/financial-report-model.test.ts` | **1 文件 / 9 例全绿** |
| 前端（白名单） | `pnpm vitest run apps/web/src/app/api/finance/financial-reports-allowlist.test.ts` | **1 文件 / 5 例全绿** |
| 前端（**全量 allowlist 回归**） | `pnpm vitest run apps/web/src/app/api` | **230 文件 / 1300 例全绿**（本车道改了共享白名单 `api-proxy.ts`，必须跑全量） |
| Lint | `pnpm lint` | **3/3 successful**（本车道文件零 warning） |

**Testcontainers / PG 容器**（本车道单独一次带计量的运行）：

- 运行前：PG16 容器 **7** 个、ryuk **1** 个（**多数是同批其它车道并发起的**）。
- 运行中峰值：PG16 **14** 个、ryuk **2** 个 ⇒ 本车道**新增 1 个 PG16 + 1 个 ryuk**。
- 运行结束后 +4s：**本车道那个数据库端口（54587）上存活容器 = 0**
  ⇒ **ryuk 已回收本车道的 PG 容器**（无泄漏）。

## 后续待办

- **⚠️ 上游 12.5.2.1**：`VoucherService.nextVoucherNo()` 的 `substring(voucher_no from 10)`
  改成 `from 11`（**一行**，他车道文件，请调度者或 12.5.2.1 车道处理）。
- **12.5.2.2 现金流量表**：需要在界面上登记「现金科目」才会出数 ——
  这是**会计政策**，不是缺陷；能力声明里已标 `available=false`。
- **导航登记**：`packages/ui/src/lib/task-pages.ts` 是四条车道并发修改的共享文件，
  本车道**刻意未动**，留给调度者统一处理。
- **下游**：17 审计、对外报送（增值税申报等）消费本模块的报表 / 导出文件时，
  口径以本模块随报表返回的 `bases`（草稿 / 红冲 / 多币种三条政策说明）为准。

## 关联

- 蓝图：`docs/product/capability-blueprint/12-finance.md` §12.5.2.2（12.5.2 锚点）
- 上游：12.5.2.1 凭证管理 `docs/sessions/2026-10/2026-10-24-voucher-management.md`
  · 12.5.1.1 会计科目 `V356__finance_account_subject.sql`
- 本车道：迁移 `V360__finance_financial_report.sql`（承 V356 / V357）
- 台账：`docs/product/capability-blueprint/module-progress.md` §12.5.2.2
