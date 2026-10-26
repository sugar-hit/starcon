# 2026-10-24 · 12.6.1.2 VAT/IOSS 申报数据（阶段 D6）

## 决策摘要

| 项   | 结论                                                                                                                                    |
| ---- | --------------------------------------------------------------------------------------------------------------------------------------- |
| 迁移 | `backend/services/finance/src/main/resources/db/migration/V368__filing_draft.sql`（三张新表，纯 CREATE TABLE，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表；**版本号第二轮由 V364 改为 V368**，见文末「第二轮修订」） |
| 表   | `finance_filing_draft`（申报底稿头）· `finance_filing_draft_line`（汇总行，**按币种分组**）· `finance_filing_draft_export`（导出留痕）              |
| 端点 | 8 个，挂 `/finance/filing-draft`；权限 `finance.filing_draft.read / .create`                                                             |
| 页面 | `/finance/filing-draft`                                                                                                                 |
| 测试 | 后端 25（19 + 6）· 前端 22（17 + 5）· 全量 allowlist 回归 233 文件 / 1315 例                                                              |
| 契约 | `packages/api-contracts/src/contracts/finance.ts` 新增 15 个 `FinanceFiling*` 类型（+15/-0）                                            |

### 蓝图原文

> ### 12.6.1.2 VAT/IOSS 申报数据
>
> - **职责**：按辖区与周期汇总申报所需数据：taxable 销售额、已收 VAT、IOSS 订单与代收金额、可抵扣进项，导出申报文件或对接申报接口。
> - **输入**：订单税务数据；结算与收付款数据；汇率快照；辖区规则。
> - **输出**：申报底稿 + 汇总报表 + 可导出文件。
> - **关系**：上游 = 12.6.1.1、9 订单、12.2.1.1；下游 = 16 合规（申报日历提醒）。**本系统提供申报底稿，不替代税务申报动作**——申报主体与责任在用户，系统保证数据完整可核。

## 关键 trade-off

### 1. 「不替代税务申报动作」落成结构而不是文案

蓝图那句加粗句**不是**靠页面上一句提示词落实的，而是靠**三张表里根本没有「申报状态 / 受理号 / 已提交」这种列**落实的：

- 本模块**不做**「向税务机构提交申报」这个动作，也不假装提交成功（api-proxy 也刻意不放行 `filing-draft/submit`）。
- 底稿一旦生成就是**不可改写的快照**（DB 触发器钉死：辖区 / 期间 / 口径 / 覆盖情况 / 税率版本一律不许改写；状态只允许 `GENERATED → SUPERSEDED` 一次；汇总行只读；导出留痕 append-only）。重新取数 = 旧底稿作废 + 新起一版（部分唯一索引保证同时只有一份生效底稿）。
- 底稿自带**可核信息**：生成时间、覆盖期间、口径说明（写进库里，不只写在页面上）、命中的税率版本（税率 id + 税码 + 生效区间）、币种、汇率快照。

### 2. 多币种绝不相加：底稿头**没有任何金额列**

汇总行按（底稿 × 币种）唯一；底稿头因此**不能**有金额列 —— 一有，页面上就一定会出现一个「跨币种总额」这种没有意义的数。折算（`converted_net_payable`）是**附加**列，只在汇率真的取到时才有值；汇率取不到时它是 NULL 且 `exchange_rate_available = false`，**绝不按 1:1 兜底**（1:1 兜底 = 编一个汇率出来）。

### 3. 可抵扣进项：改口径，不造表

仓库里**没有**「进项发票」这个概念。第一版设计想建一张 `finance_filing_draft_input` 表 —— 那是**假装有数据**（建一张空表再从里面读出 0）。改为从**已有的实际数据**取：

```
finance_voucher（已过账 POSTED）
  → finance_voucher_line（分录行）
    → finance_account_subject（科目属性 category / tax_treatment）
```

口径 = 应交税费类科目（`category = 'LIABILITY'`）且税务属性为应税口径（`tax_treatment ∈ TAXABLE / REVERSE_CHARGE`）的**借方**发生额 —— 借方冲减应交税费，就是已抵扣 / 已缴纳的税额。

代价：口径依赖**用户自己维护的科目属性**（科目归 12.5.1.1），租户没建「进项税额」科目就取不到数。好处：取不到时如实报「**无可抵扣进项数据**」并**不产出应缴**（而不是产出「应缴 = 已收 VAT」的假数）—— 一个假的 0 会直接变成一份错的申报底稿。

### 4. 辖区归属靠**收货国**，不靠订单上的辖区字段

trade 的 `sales_order` **没有**辖区字段，也没有 `shop_id`；唯一能判「这笔税属于哪个辖区」的事实是 `order_address_snapshot.country_code`（**收货**地址，不是账单地址）。⇒ 取数路径是：先按期间拉订单列表，再逐单拉明细拿收货国，按 channel 16.3.1.1 受控码表里**国家一级**的辖区做映射。

代价：逐单拉明细是 N+1 调用，因此设了单次取数上限（500 单）。**上限截断必须让人看见** ⇒ 底稿上记 `order_truncated = true`，口径说明里写明「本次取数被上限截断，**这不是该期间的全部销售**」。

### 5. 订单列表读不到 ⇒ 整个取数失败，而不是「0 张订单的底稿」

与「单张订单明细读不到」（如实计数进 `unmapped_count`）区别对待：明细读不到只影响一张单；**列表**读不到意味着整个期间的销售事实都没有，这时生成一份 0 张订单的底稿比不生成更危险（会被当成「这个期间没卖过东西」）。⇒ 列表读不到时整个取数 **503**，**不留半份底稿**。

### 6. IOSS 清单读不到 ⇒ 留空而不是 0

16.3.2.1 与本模块**同批开发中**（迁移 V365），集成头里还没有。蓝图写 16.3.2.1 的**下游 = 12.6.1.2** ⇒ 它是本模块的**上游输入来源**，本模块是它的**下游消费者**。

- 本端口（`ChannelIossPort`）**刻意不把上游当硬依赖**：404 / 不可达一律显式失败，服务层接住 ⇒ 底稿里 IOSS 两列**留成 NULL**（不是 0）并写明「读不到 IOSS 订单清单」。
- **绝不因为上游没交付就编一份 IOSS 清单出来**（编出来的清单会被拿去申报）。
- 上游交付后要改的只有 `ChannelIossPort.ORDERS_PATH` 一行。

### 7. IOSS 代收金额**不并进**已收 VAT

汇总行上「已收 VAT」（订单口径）与「IOSS 代收金额」（IOSS 口径）是**两套来源**，相加会重复计数。口径里写明两者不相加；`net_payable = 已收 VAT − 可抵扣进项` 用的是前者。

### 8. 跨服务只读端口各建各的，不并进别人的

12.4.2.2 的 `SalesOrderLinePort` 读的是「分摊基数」（数量 + 行小计），本模块要的是「申报口径的订单税务事实」（小计 / 折扣 / 税额 / 币种 / 收货国）。**不并进那个端口** —— 它是别的模块的文件，改它会与别的车道冲突。新建 `FilingOrderTaxPort`（同样的写法：转发调用方自己的 bearer、跨服务只走授权 HTTP API、读不到就显式失败）。

### 9. 汇总报表与底稿行是**同一份数据**的两种呈现

不建第二张汇总表（两个真值源会漂移）：`GET /drafts/{id}` 与 `GET /drafts/{id}/summary` 返回的是同一份汇总行。

### 10. 导出 = 取证动作，所以留痕

`GET /drafts/{id}/export` 返回**真文件**（CSV / JSON 的真实字节）。CSV 的元数据（辖区 / 期间 / 生成时间 / 口径）以 `#` 注释行开头，随后是表头行与数据行 —— 人能读、`pandas(comment='#')` 也能读。每次导出写一条留痕：行数 / 字节数 / 内容 sha-256。

## 文件清单

**新增**

- `backend/services/finance/src/main/resources/db/migration/V368__filing_draft.sql`
- `backend/services/finance/src/main/java/com/starcon/finance/FilingDraftModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/FilingDraftService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/FilingDraftController.java`
- `backend/services/finance/src/main/java/com/starcon/finance/FilingOrderTaxPort.java`（trade 只读端口）
- `backend/services/finance/src/main/java/com/starcon/finance/ChannelIossPort.java`（channel 16.3.2.1 只读端口）
- `backend/services/finance/src/test/java/com/starcon/finance/FilingDraftIntegrationTest.java`
- `backend/services/finance/src/test/java/com/starcon/finance/FilingDraftTenancyIntegrationTest.java`
- `apps/web/src/components/finance/filing-draft-model.ts`
- `apps/web/src/components/finance/filing-draft-model.test.ts`
- `apps/web/src/components/finance/filing-draft-workspace.tsx`
- `apps/web/src/app/finance/filing-draft/page.tsx`
- `apps/web/src/app/api/finance/filing-draft-allowlist.test.ts`
- `docs/sessions/2026-10/2026-10-24-filing-draft.md`

**改动**

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（prefix `finance/`、首 token `filing-draft`；**插入锚点第二轮改为集成分支当前文件第 4071 行那条「2.4.2.3 映射缺失队列（channel 服务）」元素之前**，见文末「第二轮修订」；既有条目**一字未改、也不重排**）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 **12.6.1.2 那一行**的「状态」「证据」两格
- `packages/api-contracts/src/contracts/finance.ts` · `packages/api-contracts/src/finance.openapi.json` —— codegen 重生成（**未手改**）

## 验证

- L0 编译闸：`./mvnw -q -pl services/finance -am compile` 退出 0；`pnpm typecheck` 3/3 通过；`pnpm lint` 通过。
- L1 模块闸：`./mvnw -pl services/finance -am test -Dtest='FilingDraft*'` —— **25 例 0 失败 0 错误**（FilingDraftIntegrationTest 19 + FilingDraftTenancyIntegrationTest 6，实跑 Testcontainers PostgreSQL 16，租户隔离测试把连接池压到 1）。
- 前端：`pnpm vitest run apps/web/src/components/finance/filing-draft-model.test.ts` —— 17 例通过；`pnpm vitest run apps/web/src/app/api/finance/filing-draft-allowlist.test.ts` —— 5 例通过。
- **全量 allowlist 回归**（改了 api-proxy.ts 必跑）：`pnpm vitest run apps/web/src/app/api` —— **233 文件 / 1315 例全绿**。

## 后续待办

1. **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**（四条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
2. **16.3.2.1 VAT/IOSS（channel）交付后**：对齐 `ChannelIossPort.ORDERS_PATH` 的真实端点形状，并确认返回字段（`taxableAmount` / `vatAmount` / `currency` / `orderDate`）；对齐后 IOSS 两列才会从「读不到」变成真值。
3. **税率版本快照**：底稿行上记的是 12.6.1.1 的税率版本 id + 生效区间；若 12.6.1.1 的传输模型将来补 `effectiveFrom` / `effectiveTo` 字段，本模块可以直接改用（当前是按 id 查表取，不动 12.6.1.1 的模型文件）。
4. **可抵扣进项口径**：若 12.5.1.1 后续给出更明确的「进项税」科目口径（当前靠 `category` + `tax_treatment` 推导），本模块的 SQL 口径要跟着复核。
5. **16.3.1.2 申报日历**（下游）：本模块**不建**任何日历表，提醒归它。
6. **⚠️ 别人元素里的「12.6.1.2 未交付」注释已经过时**（第二轮发现，未改）：api-proxy 里有两处
   **属于别的车道**的元素注释仍写着本模块「未交付」——
   `period-close`（卷 01 车道）那条里的 `period-close/filings / period-close/declarations` 段，
   以及 `tax-rates`（12.6.1.1 车道）那条里的 `tax-rates/filings` 段。
   本轮**刻意没改**（硬要求：既有 225 条元素「一字未改」；且那是别人的车道文件，改了会撞车），
   **建议由调度者在并入后统一收口**——否则白名单注释会自相矛盾。

## 第二轮修订（重派车道 · 只做 4 件事，实现未重写）

上一轮并入时 `apps/web/src/lib/api-proxy.ts` 撞车被判 conflict，本车道被回收重派。
**领域实现（迁移 / 服务 / 端点 / 前端工作区 / 契约 / 测试）一字未改**，本轮只做 4 件事：

### 1. 迁移改名 V364 → V368（Flyway 硬约束）

集成分支上已存在 V362 / V363 / V365 / V367，而本仓 Flyway **未开 `out-of-order`**（默认 false）：
V364 低于已应用的 V367 ⇒ 启动直接报
「Detected resolved migration not applied to database: 364」。

⇒ `git mv V364__filing_draft.sql → V368__filing_draft.sql`（本车道本轮预分配版本号）。
**迁移文件内部内容未改**（版本号只体现在文件名上）。
**刻意没有新建第二个迁移文件** —— V364 与 V368 同时存在 = 同一批表被建两次 = 迁移炸。

连带把两处源码里的版本号字面量同步过来（都是纯文案，不影响行为）：

- `FilingDraftService.java` 的 capabilities 描述串：`（V364，纯 CREATE TABLE）` → `（V368，…）`
- `FilingDraftTenancyIntegrationTest.java` 的断言说明串：`"V364 必须真的建出 "` → `"V368 必须真的建出 "`

### 2. api-proxy.ts 白名单条目按集成锚点重新对齐

`apps/web/src/lib/api-proxy.ts` 是**多车道共用的累积型白名单**（`services[]` 数组）。
本批 4 条车道并发改它 ⇒ 插入位置重叠就会在并入时冲突；
而**漏掉任何一条已交付的路由 = 页面静默 404，而 typecheck / lint / vitest / Playwright 全部全绿**。

⇒ 基线换成**集成分支当前版本**（本车道分支基线是旧的 `1c73d1e8`，集成头是 `1e9ab421`），
再把本车道那 1 条元素（首 token `filing-draft`）搬到**调度者分配的锚点**：

- 锚点 = 集成分支当前文件**第 4071 行**那条元素的 `{` 起始行**之前**
  （该元素注释标题 =「2.4.2.3 映射缺失队列（channel 服务）」）
- 同批另外 3 条车道的锚点分别在第 314 行、第 1505 行、第 9040 行 —— 四处互不相邻，git 能干净求并。
- 插入点是**元素 `{` 起始行之前**，不是 `prefix:` 行之前（插错位置会破坏对象字面量）。

核对口径与实测：

- 元素计数（`sed -n '/^const services/,/^];/p' … | grep -cE "^[[:space:]]+prefix: '"`）：
  基线 **225** → 本车道 **226**（不用 `grep -c "prefix: '"`，那个会把注释里的 `prefix:` 也数进去）。
- 首 token 零命中：对基线实测 `grep -c "filing-draft"` = **0**。
- 与既有元素两两不相交：既有 finance 那几支首 token 是 `receivables` / `receivable-aging` /
  `payables` / `receipts` / `bad-debt` / `payment-plans` / `payment-orders` /
  `payment-executions` / `write-offs` / `settlement` / `transactions` / `fund-analysis` /
  `cost-collections` / `cost-valuation` / `deferred-settlement` / `cost-calculation` /
  `cost-variance` / `tax-rates` 等，**全不相同**。
- `diff` 基线 vs 本车道文件：**59 行新增、0 行删除** ⇒ 既有 225 条元素**一字未改、也不重排**。

### 3. 契约与台账

- `packages/api-contracts` **未重新生成**（本轮未改端点 / 模型；契约是 codegen 产物，禁止手改）。
  并入时由 git merge 按「类型名 / OpenAPI 路径」自动求并。
- 台账 `docs/product/capability-blueprint/module-progress.md`：**12.6.1.2 那一行保持「已交付」**，
  只把证据列里的迁移版本号 `V364__filing_draft.sql` 改成 `V368__filing_draft.sql`；
  **未重排整表、未动别的行**。

### 4. 验证与并入预演

见文末「第二轮验证结果」。

## 第二轮验证结果（实测）

### L0 编译闸

| 命令                                        | 结果                                                                             |
| ------------------------------------------- | -------------------------------------------------------------------------------- |
| `cd backend && ./mvnw -q compile`            | **退出 0**（只有 JVM 的 `Restricted method` / `sun.misc.Unsafe` WARNING，与本模块无关） |
| `pnpm typecheck`                            | **退出 0**，turbo **3/3 successful**（`@starcon/web` cache miss 实跑 `tsc --noEmit`）    |
| `pnpm lint`                                 | **退出 0**，turbo **3/3 successful**（仅 catalog / orders / warehouse 的既有 `react-hooks/exhaustive-deps` warning，不在本模块） |

### L1 模块闸（只跑本模块测试类）

```
cd backend && ./mvnw -pl services/finance -am test \
  -Dtest='FilingDraft*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
```

⇒ **Tests run: 25, Failures: 0, Errors: 0, Skipped: 0**，**BUILD SUCCESS**（Reactor：backend / tenancy / finance 全 SUCCESS）。

- `FilingDraftIntegrationTest` —— **19 例**（真分页真筛选、多币种不相加、导出 CSV/JSON 真文件 + 留痕、
  读不到 IOSS 留空而不是零、汇率不可得不按 1:1 兜底、辖区码不在受控码表直接被拒、
  命中不到税率直接报错不退回最新一条、订单列表读不到整个取数失败、底稿不可改写 …）
- `FilingDraftTenancyIntegrationTest` —— **6 例**（实跑 Testcontainers PostgreSQL 16）

**多租户三条硬断言全部在跑并通过**：

1. `断言1_两租户互不可见`
2. `断言2_伪造租户上下文无效`（另含 `断言2_伪造租户头无效且行落在会话租户名下`）
3. `断言3_连接池归还后不串租户`（把连接池压到 1）

### 前端

| 命令                                                                       | 结果                    |
| -------------------------------------------------------------------------- | ----------------------- |
| `pnpm vitest run apps/web/src/app/api`                                     | **233 文件 / 1315 例全绿**（改了 api-proxy.ts 必跑的全量 allowlist 回归） |
| `pnpm vitest run apps/web/src/components/finance apps/web/src/app/finance/filing-draft` | **23 文件 / 310 例全绿** |

本模块两个测试文件单跑：**22 例全绿**（`filing-draft-model.test.ts` 17 + `filing-draft-allowlist.test.ts` 5）。
其中 allowlist 有一条 **`不误伤其余服务已放行的分支（求并，不是二选一）`** ——
它正是 api-proxy 重新对齐后必须复验的那条：新增的 `filing-draft` 元素不得吃掉别的服务已放行的分支。

### 并入预演（真实 merge）

```
git merge --no-commit --no-ff integration/module-dev   # 退出 0，零冲突
git merge --abort && git status --porcelain            # 工作区干净，未留任何改动或合并提交
```

`git diff --name-only --diff-filter=U` 为空 ⇒ **零冲突**。
合并结果核对：api-proxy 元素数 **226**、本模块那 1 条元素在位（pattern 行唯一命中）、
台账里 `12.6.1.2` 行**唯一**且状态仍为「已交付」、证据列迁移版本号为 `V368__filing_draft.sql`。

### PG 容器

- 本车道 verify 期间并发存活的 `postgres:16-alpine` 最多 **7 个**（本批 4 条车道同时在跑）；
  其中**本车道自己的 3 个** Testcontainers 容器。
- 结束前**本车道的 3 个容器已被 ryuk 全部回收**（`docker inspect` → NOT FOUND），
  `testcontainers/ryuk:0.12.0` 容器运行中；`docker ps` 里剩下的是其它车道仍在用的。

### ⚠️ 一个会咬人的坑（已记录）

改名后**第一次**跑 L1 模块闸时 25 例全部 `Failed to load ApplicationContext`，
根因是 `relation "finance_filing_draft" already exists` —— **不是源码问题，是构建产物残留**：
上一轮的 `target/classes/db/migration/V364__filing_draft.sql` 还在，
`git mv` 只动了源码树、没动 `target/`，于是 Flyway 先跑 V364 建了表、再跑 V368 建同一批表 ⇒ 炸。

⇒ `./mvnw -pl services/finance -am clean` 之后重跑即全绿。
**教训：改迁移文件名后必须 clean 再验**，否则 stale `target/classes` 里的旧迁移会与新迁移同场建表。
