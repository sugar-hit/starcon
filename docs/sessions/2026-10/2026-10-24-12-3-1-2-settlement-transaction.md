# 12.3.1.2 交易明细（V348 · 阶段 D6 · 车道 12.3.1.2）

> 蓝图原文：`docs/product/capability-blueprint/12-finance.md` 的 **12.3.1.2 交易明细**。
> 车道分支 `feat/12-3-1-2`，基线 `integration/module-dev` 的 `ccc62410`。

## 决策摘要

### 1. 与 12.3.1.1（V345）的关系：**复用明细行表，不另建平行表**

读 V345 + `SettlementService` / `SettlementModels` 之后的判定：

`finance_settlement_line`（结算明细行）**已经就是**「结算单下的逐笔明细」这张表 ——
按结算单挂载、`append-only`、`tenant_id NOT NULL` + FORCE RLS、金额四位小数、数量六位小数。
⇒ 本模块**在它上面补缺口**（V348 只做 ADD COLUMN），**不新建平行的明细表**。

**为什么不新建**：两张明细表各写一份金额 ⇒ 结算金额有两个来源 ⇒
蓝图点名的输出「明细合计 = 结算单汇总」立刻变成两条对不上的账，
而且是**金额平、来源乱**的最难查的一类错账。

**为什么敢复用**：V345 的三段派生链（明细行 → 汇总项 → 结算金额）只有**一个**写入口，
本模块**只写明细行**，汇总项与结算金额**一个字节都不碰** ⇒ 派生链不打架。

### 2. 补齐的五个缺口

| 缺口 | 落法 |
| --- | --- |
| 交易类型分类 | `transaction_type`（10 类：订单销售 / 退款 / 佣金 / FBA 费用 / **仓储费** / **配送费** / 广告费 / 赔付 / 其他调整 / **代扣税**），留空时由触发器按 `category` 补齐（⇒ V345 原样的插入语句继续工作） |
| 订单行可空 | `order_reference` / `order_line_reference` **可空**；**没有**任何约束要求某类明细必须带订单号 |
| 外部流水号 | V345 已有 `external_reference`，直接复用 |
| `idempotency_key` | 部分唯一索引（同单内）+ BEFORE INSERT 守卫（跨单） |
| 与汇总项的校验 | `GET /finance/transactions/reconciliation` —— 实时算出来的核对 |

### 3. 「代扣税」为什么要动 V345 的类别词表

V345 的类别词表只有七个（SALES / REFUND / COMMISSION / FBA_FEE / ADVERTISING / COMPENSATION / ADJUSTMENT），
蓝图 12.3.1.2 点名的「代扣税」**没有位置**：

- 塞进 `ADJUSTMENT` ⇒ 汇总项里「其他调整」不再是「其他」，口径被污染；
- 塞进 `COMMISSION` ⇒ 代扣税不是佣金，金额口径错。

⇒ 加独立类别 `TAX`，**V348 同时放宽 `finance_settlement_line` 与 `finance_settlement_summary` 两张表的 CHECK**
（V345 的派生链按 `category` 聚合，两边词表必须一致；只改一边 ⇒ 插入一行代扣税明细会让派生链在 DB 层炸掉）。
**V345 的文件内容一个字都没改**（它已进过集成头并被应用过，改内容会让 Flyway checksum 校验炸掉）。

### 4. 幂等键的两道闸与「重新解析建新单」

- 同一张结算单内：`UNIQUE(tenant_id, settlement_id, idempotency_key) WHERE idempotency_key IS NOT NULL`；
- 跨单：`guard_finance_settlement_line_idempotency`（BEFORE INSERT）—— 同一笔平台交易不许记两次。

**守卫刻意不是 SECURITY DEFINER**：以调用方身份执行 ⇒ RLS 生效 ⇒ 只看得到本租户的行。
否则 A 租户会被 B 租户的键挡住，错误消息里还会带出别家的键（跨租户串扰）。

**作废（VOID）与被取代（SUPERSEDED）的单不持有幂等键** ⇒ 12.3.1.1 的
「同一份账单重解析 → 新版本 → 新单」这条路不会被幂等键挡住。

## 关键 trade-off

| 选择 | 代价 | 为什么这么选 |
| --- | --- | --- |
| 复用 `finance_settlement_line` 而不是新建表 | 要 ALTER 别人的表（V348 里 ADD COLUMN + 放宽 CHECK） | 两张明细表 ⇒ 金额两个来源 ⇒ 「明细合计 = 结算单汇总」不再成立 |
| 放宽类别词表加 `TAX` | 动了 12.3.1.1 的语义面（虽然只是加一个值） | 「代扣税」在七个类别里真的没有位置；且两张表必须同时改，否则派生链炸 |
| 幂等键跨单用**触发器**而不是表级 UNIQUE | 每次插入多一次查询 | 表级 UNIQUE 会让「重新解析建新单」永远做不成 —— 而那是上游必须支持的流程 |
| 汇总校验**实时算**而不是存快照 | 每次读要多算几个聚合 | 存下来的快照会与明细漂移；而蓝图要的就是「明细合计 = 结算单汇总」这个核对本身 |
| 「销售额没有订单号」**不进** `issues` | 少一个提醒 | 蓝图加粗句警告的正是这种**虚假异常**；它作为计数 `salesWithoutOrderLineCount` 报出 |
| 前端**不登记**导航（`task-pages.ts`） | 页面暂时没有导航入口 | 四条车道并发改它必然冲突；由调度者统一处理 |

## 上游缺口（如实标注，不造数）

- **平台交易报告自动同步不可用，归属模块「卷 02 同步」**：channel 服务没有交易报告端点 ⇒
  DB 词表保留 `PLATFORM_SYNC`，服务层**拒绝**用它登记明细并写明归属模块；
  **人工上传交易报告**这条路径**真的能用**（CSV / JSON 真解析入库）。
- **12.3.2.1 对账任务未交付** ⇒ 只做「明细 + 汇总校验」，不实现匹配、不产出差异分类。
- **13 利润未交付** ⇒ 不产出任何利润数字。
- **12.3.1.3 延迟结算未交付** ⇒ 不碰在途未结算金额口径。

## 文件清单

**后端（新增）**

- `backend/services/finance/src/main/resources/db/migration/V348__finance_settlement_transaction.sql`
- `backend/services/finance/src/main/java/com/starcon/finance/TransactionModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/TransactionService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/TransactionController.java`
- `backend/services/finance/src/main/java/com/starcon/finance/TransactionImportParser.java`
- `backend/services/finance/src/test/java/com/starcon/finance/TransactionIntegrationTest.java`（25 例）
- `backend/services/finance/src/test/java/com/starcon/finance/TransactionTenancyIntegrationTest.java`（8 例）

**后端（既有文件的增量改动，共 3 处，全部是加法）**

- `SettlementModels.java`：`SettlementCategory` 加 `TAX`（标签 + 符号 −1 + 别名）——
  **必须加**：V348 放宽了汇总项的类别词表，而 `SettlementService.detail()` 用
  `SettlementCategory.valueOf(category)` 读汇总项 ⇒ 不加的话读到 `TAX` 会 500。
  同时给 `parse()` 补了代扣税别名。
- `SettlementImportParser.java`：行级错误提示里的类别清单补上 `TAX`（一个字）。
- `SettlementModels.java` 的枚举 javadoc：注明 `TAX` 由 12.3.1.2 引入。

**前端（新增）**

- `apps/web/src/components/finance/transaction-model.ts`
- `apps/web/src/components/finance/transaction-model.test.ts`（8 例）
- `apps/web/src/components/finance/transaction-workspace.tsx`
- `apps/web/src/app/finance/transactions/page.tsx`
- `apps/web/src/app/api/finance/transactions-allowlist.test.ts`（5 例）

**共享文件**

- `apps/web/src/lib/api-proxy.ts`：**新增一条独立的 `services[]` 元素**（`prefix: 'finance/'`，
  锚在**第一个 `prefix: 'identity/'` 那条元素之前**），既有条目一字未改、也不重排。
- `packages/api-contracts/src/contracts/finance.ts` + `packages/api-contracts/src/finance.openapi.json`：
  codegen 产物（**不是手改**）。同批 codegen 顺带带出其余六个服务的**存量漂移**（属性排序），**已 revert**。
- `docs/product/capability-blueprint/module-progress.md`：**只改 12.3.1.2 那一行**的「状态」「证据」两格。

## 验证

- L0 编译闸：`./mvnw -q compile`（exit 0）+ `pnpm typecheck`（3 packages 全绿）+ `pnpm lint`（3 packages 全绿，只有既有文件的既有 warning）。
- L1 模块闸：`./mvnw -pl services/finance -am test -Dtest='Transaction*'` ⇒ **33 例全绿**（25 + 8）。
- 契约：`OpenApiContractTest` 导出 spec → `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`。
- 前端：`pnpm vitest run apps/web/src/components/finance/transaction-model.test.ts apps/web/src/app/api/finance/transactions-allowlist.test.ts` ⇒ 13 例全绿；
  **全量 allowlist 回归** `pnpm vitest run apps/web/src/app/api` ⇒ **218 文件 / 1238 例全绿**。
- api-proxy 逐条实测：`.tmp/verify-api-proxy-transactions.mjs` ⇒ 199 条正则对 7 个正向 shape 零命中、
  13 个负向 shape 零命中、既有正则无一条提到 `transactions`。

## 后续待办

1. **导航注册缺口**：`/finance/transactions` 的页面路由已建，但 `packages/ui/src/lib/task-pages.ts`
   本车道**刻意没碰**（四条车道并发改它必然冲突）⇒ 待调度者统一登记。
2. **12.2.1.1 收款单接上**：12.3.1.1 已铺好「按 `finance_settlement.settlement_no` 关联收款」的读路径，
   等 12.2.1.1 放开那条 CHECK 后自动有数据 —— 本模块不碰它的表。
3. **卷 02 同步交付后**：`PLATFORM_SYNC` 这个来源在 DB 词表与枚举里都已就位，
   上游端点交付后只需放开服务层那一处拒绝。
4. **12.3.2.1 对账任务交付后**：匹配要用的键（订单号 + 外部流水号 + 交易类型 + 日期 + 金额）
   本模块**已经落库**，直接读 `finance_settlement_line` 即可，不必再抄一份。
