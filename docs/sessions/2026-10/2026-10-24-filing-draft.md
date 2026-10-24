# 2026-10-24 · 12.6.1.2 VAT/IOSS 申报数据（阶段 D6）

## 决策摘要

| 项   | 结论                                                                                                                                    |
| ---- | --------------------------------------------------------------------------------------------------------------------------------------- |
| 迁移 | `backend/services/finance/src/main/resources/db/migration/V364__filing_draft.sql`（三张新表，纯 CREATE TABLE，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表） |
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

- `backend/services/finance/src/main/resources/db/migration/V364__filing_draft.sql`
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

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（prefix `finance/`、首 token `filing-draft`、锚在第一个 `prefix: 'warehouse/'` 元素之前；既有条目**一字未改、也不重排**）
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
