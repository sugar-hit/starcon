# 12.3.2.1 对账任务（阶段 D6，L3 锚点 12.3.2 对账）

> 车道 `feat/12-3-2-1` · 工作树 `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/12.3.2.1`
> · 基线 `integration/module-dev` @ `56d7e98c`

## 蓝图原文（卷 12 财务核算 · 12.3.2 对账 · 第一格）

> 职责：发起与执行对账任务：选择对账范围（店铺 + 周期 + 账户）、运行自动匹配、输出匹配结果与差异。
> 输入：对账范围；我方账数据（结算、应收、应付、银行流水）；平台账单。
> 输出：匹配结果（一对一、一对多、多对一）+ 差异清单。
> 关系：上游 = 12.3.1.1、12.7.1.2；下游 = 12.3.2.2。
> **不允许「金额相同即匹配」**——金额相同只是候选，必须结合订单号、日期、类型。

本车道交付 **4 张新表 + 10 个端点 + 1 个真实工作区页面**（jOOQ 显式 SQL + 真实 PostgreSQL 16，
无常量、无 mock、无占位页），并把「不允许金额相同即匹配」这条卷 12 硬约束**钉在 DB 层**。

## 关键决策

### 1. 🔴「不允许『金额相同即匹配』」钉在**三层**（卷 12 硬约束）

| 层 | 做法 |
| --- | --- |
| 候选生成 | `ReconciliationMatcher` 的候选生成**完全不看金额**：订单号 / 日期 / 类型中**至少两个维度对上** + 同币种同方向。金额只在**成组**这一步作为**必要条件**参与（精确金额优先 → 再按差额贪心补齐）。 |
| DB 约束 | `finance_reconciliation_match.matched_dimensions` 是 `{REFERENCE, DATE, TYPE}` 的子集，CHECK 要求**至少两个维度**且 `dimension_count >= 2`。⚠️ PG **没有** `text[] & text[]` 运算符 ⇒ 用 IMMUTABLE 函数 `finance_reconciliation_dimension_count(dims text[])` 判交集个数，且辅助函数必须**建在表之前**（否则建表时 CHECK 引用不到）。 |
| 可观测证据 | 金额相同但**只对上 1 个维度**的两行 ⇒ 落成 `DIMENSION_MISMATCH` 差异（不是匹配）。页面上这类差异单独一个徽章，人能直接看到「差一点点」为什么没配上。 |

**取舍**：不引入「金额容差 + 宽窗口」这种看起来更灵活的方案 —— 它会把「金额相同即匹配」换个马甲。
容差字段保留（`amount_tolerance`）但**默认 0**，且成组时**必须**同时满足两个以上维度；
`date_window_days` 由服务层钉死 **1 ~ 31**（窗口放到几百天等于用「金额相同」冒充匹配）。

### 2. 🔴 两侧划分：**结算归平台侧，不归我方侧**（本模块最关键的一处取舍）

蓝图输入句写的是「我方账数据（结算、应收、应付、银行流水）」，但：

- 12.3.1.1 的结算单 / 12.3.1.2 的结算明细行**就是平台账单**（平台结算报告与平台交易报告的落地）；
- 把它们当「我方账」，等于让**同一份平台文件的两份投影互相对撞** ⇒ 凭空多出一堆假匹配 + 假差异。

⇒ 本模块的划分是：

| 侧 | 来源 |
| --- | --- |
| 平台账单侧（EXTERNAL） | 12.3.1.1 结算单头 + 12.3.1.2 结算明细行 |
| 我方账侧（BOOK） | 12.1.1.1 应收明细行 / 12.2.2.1 付款单明细行 / 12.7.1.2 银行流水行 |

这样「结算单到账 ⇄ 银行流水」这条核对线才成立（结算在平台侧、到账流水在我方侧）。
受控词表 `FinanceReconBookSource` 因此**只有三个值**：`RECEIVABLE` / `PAYMENT_ORDER` / `BANK_STATEMENT`。

### 3. 🔴 读 12.7.1.2 的银行流水走**授权只读 API**，不走跨库 SQL

`BankReconciliationPort` 是 12.7.1.2 的**授权只读 HTTP 端口**（`GET /finance/bank-reconciliation/sessions`
+ `.../statement-lines`），转发调用方的 bearer。**零跨库 SQL / FK / 视图**，
且 12.7.1.2 的九张 `finance_bank_recon_*` 表**一个字都没动**。

⚠️ 两个实测踩到的坑：

1. `UriComponentsBuilder.path()` 会把 `http://` 压成 `http:/` ⇒ 改用 URI 模板拼 URL；
2. 「自己 HTTP 调自己」会在本模块事务里**再占一条连接** ⇒ 连接池只有 1 条时**直接死锁**
   ⇒ `match` 端点拆成三步：**① 短事务读范围 → ② 事务外**取银行流水 → ③ 事务内落库**。
   运行时从 `WebServerApplicationContext` 取真实端口（支持 RANDOM_PORT 测试）。

### 4. 🔴 执行结果是一次性事实 ⇒ **重跑 = 新建一个任务**

- 任务范围（账户 / 店铺 / 周期 / 我方账来源 / 匹配参数）由触发器**冻结**；
- 匹配结果 `finance_reconciliation_match` **append-only**（触发器 + 运行角色只给 INSERT）；
- 差异行**只许推进 `status`**（其余字段冻结）—— 这个 `status` 是给 12.3.2.2 预留的；
- 已 MATCHED 的任务再跑匹配 ⇒ **409**，并写清「要重来请新建一个任务」。

**取舍**：牺牲「一键重算」的便利，换来「历史对账证据不被改写」。财务场景里后者更重要。

### 5. 🔴 跨币种绝不相加；汇率不可得就**不给数字**

- 账户单币种 ⇒ 汇总**按币种分组**，差异清单逐条带币种；
- `exchange_rate_available = false ⇒ difference_amount IS NULL`（DB CHECK 钉死）；
- 页面显示「—（汇率不可得，不按 1:1 兜底）」而**不是 0** —— 0 会被读成「账是平的」，那是骗人。

### 6. 差异清单的 5 类（受控词表）

`EXTERNAL_ONLY`（平台账单多出）/ `BOOK_ONLY`（我方账多出）/ `AMOUNT_MISMATCH`（金额对不齐）/
`CURRENCY_MISMATCH`（币种不一致，差额恒为 null）/ `DIMENSION_MISMATCH`（维度对不上 —— 金额相同只 1 维）。

**差异处理（认领 / 裁决 / 核销）属 12.3.2.2，未交付** ⇒ 本模块**只输出清单，不提供任何处理动作**，
api-proxy 也**刻意不放行** `{id}/differences/{id}` / `resolve` / `acknowledge` / `write-off`。

### 7. 不建规则管理表（归 12.3.2.3）

匹配参数 = **代码内受控词表 + 接口入参**（`date_window_days` 1~31、`amount_tolerance` 默认 0），
随任务快照。**不建**规则管理表（那是 12.3.2.3 的域，且建了就得有它的写入所有权）。

### 8. 匹配按来源独立进行；日期统一按 UTC 日历日归一

- **不同我方账来源之间不互配**（各自与平台账单比）⇒ `EXTERNAL_ONLY` 差异跨来源**去重**，
  否则同一笔平台账单会按来源数重复出现；
- PG `timestamptz` **不保留原始偏移** ⇒ 日期统一按 **UTC 日历日**归一，否则不同部署会算出两个日期；
- **银行流水没有业务类型** ⇒ 类型维度不参与银行行的匹配（银行行只靠订单号 + 日期两维）。

### 9. 权限码**沿用** 12.7.1.2 的 `finance.receivable.read / .update`，不新造词表

新造一档要改 identity 的迁移（那是**别的服务的写入所有权**，且迁移版本号由调度者统一分配）；
且本模块要经授权只读 API 读 12.7.1.2，那条路径本来就要求 `finance.receivable.read`
⇒ 同一套词表，跨模块读才走得通。

### 10. 契约修正：`FinanceReconTaskRow.shopId` 必须是**可空**

建任务时允许不指定店铺（= 该账户下全部授权店铺）⇒ DB 列可空。DTO 原本没标 `@Schema(nullable = true)`，
契约会说「非空」而实际给 `null` ⇒ 前端把它当字符串用就会在页面上显示出 `null`。
已修正并重新生成契约（**这是我自己的新 schema**，改动是纯增量）。

### 11. api-proxy：新增**一条独立**元素，首 token `reconciliation-tasks`

- 锚点：**「12.7.1.2 银行对账」那条元素之后**；既有条目**一字未改、也不重排**；
- 发车前实测 `grep -n "reconciliation-tasks" apps/web/src/lib/api-proxy.ts` ⇒ 既有正则**零命中**；
- **刻意不并进 `bank-reconciliation`**：那是一条封闭 alternation，共用首段会让两条正则纠缠、埋下互相吃掉的隐患；
- id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`；
  字面量子路径（`capabilities` / `options`）排在 `{uuid}` 段**之前**判别；
- ⚠️ 实现期真实踩到的坑：JS 正则**字面量**里每个 `/` 都必须转义（`\/`），否则字面量被提前截断。

## 文件清单

| 类别 | 路径 |
| --- | --- |
| 迁移 | `backend/services/finance/src/main/resources/db/migration/V370__finance_reconciliation_task.sql` |
| 后端模型 | `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationTaskModels.java` |
| 后端引擎 | `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationMatcher.java` |
| 后端端口 | `backend/services/finance/src/main/java/com/starcon/finance/BankReconciliationPort.java` |
| 后端服务 | `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationTaskService.java` |
| 后端控制器 | `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationTaskController.java` |
| 后端测试 | `backend/services/finance/src/test/java/com/starcon/finance/ReconciliationTaskIntegrationTest.java` |
| 后端测试 | `backend/services/finance/src/test/java/com/starcon/finance/ReconciliationTaskTenancyIntegrationTest.java` |
| 前端模型 | `apps/web/src/components/finance/reconciliation-task-model.ts` |
| 前端工作区 | `apps/web/src/components/finance/reconciliation-task-workspace.tsx` |
| 前端测试 | `apps/web/src/components/finance/reconciliation-task-model.test.ts` |
| 页面 | `apps/web/src/app/finance/reconciliation-tasks/page.tsx` |
| 白名单 | `apps/web/src/lib/api-proxy.ts`（**新增一条独立元素**，既有条目一字未改） |
| 白名单测试 | `apps/web/src/app/api/finance/reconciliation-tasks-allowlist.test.ts` |
| 契约（生成物） | `packages/api-contracts/src/contracts/finance.ts` + `packages/api-contracts/src/finance.openapi.json` |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（**只改 12.3.2.1 那一行**的两格 + `sync.mjs` 重算的 5 行统计） |

## 验证（真实输出）

- **L0 编译闸**：`cd backend && ./mvnw -o -q compile` ⇒ **BUILD SUCCESS**（EXIT=0）。
- **L1 模块闸**：`./mvnw -o -pl services/finance -am test -Dtest='ReconciliationTask*'` ⇒
  **Tests run: 27, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
  （ReconciliationTaskIntegrationTest 17 + ReconciliationTaskTenancyIntegrationTest 10），
  实跑 Testcontainers PostgreSQL 16、连接池压到 1。
- **租户隔离三断言**（跑在本模块自己的四张表上）：
  ① 两租户互不可见；② 伪造租户上下文 / 伪造 `X-Tenant-Id` 头无效（**并断言行确实落在会话租户名下**，
  不是只断言「看不见」）；③ 连接池归还后不串租户（池大小 = 1）。
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ⇒
  `Generated 6836 DTOs in 7 per-service files, 7 OpenAPI snapshots and one barrel`；
  `packages/api-contracts/src/contracts/finance.ts` 新增 **20 个 `FinanceRecon*` 类型**、**删除 0**；
  OpenAPI 快照按 schema 名集合比对：**新增 20 个 schema / 8 条 path，删除 0，既有 616 个 schema 内容变化 0**；
  **其余六个服务的契约文件零改动**。
- **前端**：`pnpm typecheck` ⇒ **3/3 successful**；`pnpm lint` ⇒ **3/3 successful**（仅他车道文件的既存 warning）；
  `pnpm vitest run apps/web/src/app/api/finance` ⇒ **29 文件 / 148 例全绿**
  （本车道改了共享白名单 `api-proxy.ts`，必须跑全量 allowlist 回归）；
  `pnpm vitest run`（本车道新增的两个文件）⇒ **2 文件 / 31 例全绿**（模型 25 + 白名单 6）。
- **台账**：`node tools/module-progress/sync.mjs` ⇒ `✓ 同步 18 卷 / 465 个 L4 模块 / 已交付 393 · 进行中 0 · 未开发 72`。

### ⚠️ 实测抓到的真 bug（都已修，且都有测试钉住）

1. **PG 无 `text[] & text[]` 运算符** ⇒ 维度交集改用 IMMUTABLE SQL 函数；辅助函数必须**建在表之前**。
2. **jOOQ 重复列名 `source_reference` 歧义** ⇒ 改别名 `line_source_reference` / `head_source_reference`。
3. **`UriComponentsBuilder.path()` 把 `http://` 压成 `http:/`** ⇒ 跨模块只读调用改用 URI 模板 +
   运行时取真实端口。
4. **组补齐时共享侧被重复计入**（一对多 / 多对一成组算错金额）⇒ 修成组算法。
5. **未配平的组误写进匹配表** ⇒ 由 DB 触发器兜底（409），不是只靠服务层。
6. **契约说 `shopId` 非空而实际可为 null** ⇒ DTO 补 `@Schema(nullable = true)`，重新生成契约。

## 后续待办 / 留给别人的动作

1. **12.3.2.2 差异处理**（下游未交付）：本模块的差异行**只预留了 `status` 字段**，
   认领 / 裁决 / 核销都还没有端点 ⇒ 12.3.2.2 落地时直接接这张表，**不需要改本模块的表结构**。
2. **12.3.2.3 自动匹配规则**（未交付）：本模块**刻意不建规则表**；规则管理落地时，
   本模块的匹配参数（日期窗口 / 容差）应改为从规则表取。
3. **12.3.1.2 结算明细行的 `transaction_type`**：本模块的「类型」维度取自它；
   若上游扩展类型词表，本模块的受控词表需要同步（当前按字符串透传，未硬编码枚举）。
4. **导航入口**：本页**刻意没有**在 `packages/ui/src/lib/task-pages.ts` 登记 ——
   那个文件是多车道并发修改的共享文件，留给调度者统一处理。
5. **银行流水的类型维度**：12.7.1.2 的流水行没有业务类型 ⇒ 银行行只靠订单号 + 日期两维匹配。
   若上游将来补上业务类型，本模块的银行行候选生成要相应放宽到三维。
