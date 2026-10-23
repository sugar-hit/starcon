# 12.3.1.3 延迟结算（V353 · 阶段 D6 · 车道 12.3.1.3）

> 蓝图原文：`docs/product/capability-blueprint/12-finance.md` 的 **12.3.1.3 延迟结算**。
> 车道分支 `feat/12-3-1-3`，基线 `2874248d`（`merge(12.1.2.3)`，前序 `3734f79b merge(12.3.1.2)`）。
>
> 蓝图四行原文：
> 「职责：管理平台延迟结算（部分平台按周期后结算或滚动结算）：单列在途未结算金额、结算条件、预计到账时间，
> 支持查询「订单已发货但钱未到账」的明细。输入：结算规则；订单与结算状态。
> 输出：延迟结算明细 + 在途资金统计 + 预计到账时间。关系：上游 = 12.3.1.1、9 订单；
> 下游 = 13 利润（按已结算/未结算区分口径）。」

## 决策摘要

### 1. 与 12.3.1.1（V345）/ 12.3.1.2（V348）的关系：**不另起结算体系**

延迟结算是**新的一层**（在途未结算金额 / 结算条件 / 预计到账时间 / 「已发货未到账」的明细），
不是一张新单据 ⇒ 结算单、结算明细行、交易明细行仍以 12.3.1.1 / 12.3.1.2 为准，
**V353 只新建三张表、零 ALTER 零 DROP 既有表**。

「已结算口径」的钱**不另存一份**：由 DB 触发器从 `finance_settlement_line` +
`finance_settlement.status` **派生**出来。理由与 12.3.1.2 复用 `finance_settlement_line`
是同一条纪律：**一份事实一个写入口** —— 两个地方各存一份到账金额，
「在途未结算金额」立刻变成两个来源对不上的账。

### 2. 两套口径的定义（本模块对下游 13 利润的硬承诺）

蓝图关系栏写的是「下游 = 13 利润（按已结算/未结算区分口径）」。
本模块**同时**给出两套口径、**分币种**分组、**绝不相加**（USD 与 EUR 加起来不是任何一个金额）：

| 口径                            | 取数                                                                                | 含义                                       |
| ------------------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------ |
| **订单口径（ORDER_BASIS）**     | 平台订单总额（毛额，含税含运），来自 trade 的平台订单                               | 按「订单已发生」确认收入 —— 钱还没到账也算 |
| **已结算口径（SETTLED_BASIS）** | 该订单在**已到账**结算单（`status ∈ PARTIAL/SETTLED`）里的**销售额（SALES）行**合计 | 按「钱已到账」确认收入                     |
| **在途未结算（IN_TRANSIT）**    | 逐行 `max(订单口径 − 已结算口径, 0)`                                                | 钱还没到的部分                             |

**为什么已结算口径只认 SALES 行**：佣金 / 退款 / FBA 费用 / 代扣税是平台**从货款里扣走**的钱，
不是「货款没到账」。把它们算进已结算口径会让在途未结算金额虚高（明明已到账却还显示在途）。

**两套口径基数不同**（毛额 vs 结算销售额行），差额含税与运费等尚未单独结算的部分 ⇒
汇总里以 `basisGapAmount`（口径基数差）**如实报出**，不藏；**本模块不替下游做口径取舍**，
只把两套口径同时给出。**口径定义随每个汇总响应一起返回**（`basis` 字段）
—— 数字脱离口径说明，半年后没人说得清「订单口径」是毛额还是净额。

### 3. 「订单已发货但钱未到账」的判据是**事实**，不是状态位

台账行落在**已到账**结算单里 ⇒ 钱已到账（`SETTLED`），否则 `PENDING`。
判据由 DB 触发器从 12.3.1.1 的结算单状态推出，**服务层写不进一个假的「已到账」**。

### 4. 预计到账时间与结算周期区间：**由 DB 触发器派生**

结算周期与滚动窗口是**日历**概念 ⇒ 一律按 **UTC 日历日**算（用本地时区会让同一批订单
在不同机器上算出不同区间）：

- `PERIODIC`（周期后结算）：周期开始 = 发货日 00:00Z，周期结束 = 发货日 + `cycle_days`；
- `ROLLING`（滚动结算）：窗口以 1970-01-01 对齐，每 `cycle_days` 天滚一次，
  周期结束 = 发货日之后的**第一个**窗口边界（发货日正好落在边界上算进**下一个**窗口 ——
  边界当天的货已经错过这一轮），周期开始 = 周期结束 − `cycle_days`；
- 预计到账时间 = 周期结束 + `payout_lag_days`（`timestamptz`）。

⇒ 「手填一个对不上的预计到账时间」在结构上做不到：守卫用 `pg_trigger_depth() < 2`
（与 V345 同一手法），**服务层与直连 SQL 都一样被拒**。

### 5. 订单事实走 **trade 的授权 HTTP 只读端口**

「订单已发货但钱未到账」必须同时有**订单事实**（trade 9.1.2.1 平台订单）与
**发货事实**（trade 9.4.1.2 发货记录）⇒ 本端口读两个上游端点
（`GET /trade/platform-orders/orders` + `GET /trade/fulfillment/shipment-records/records`），
**不直连对方库、不建跨库 FK / 视图**（AGENTS.md 模块边界纪律）。
**租户不由本端口传递**：转发的是调用方自己的 bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。

### 6. 「没读到」与「没有」必须分清

上游读不到一律 **503** 并写明「读不到」；扫不进来的订单**逐条说明原因**
（已取消 / 没有发货记录 / 没有生效的结算规则 / 币种与规则不符 / 缺订单总额）。
**绝不返回空集合冒充「平台没有订单」** —— 那样现场会照着空名单以为这批货根本没发。

## 关键 trade-off

| 选择                                                           | 代价                                                                 | 为什么这么选                                                                                     |
| -------------------------------------------------------------- | -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| 已结算口径由触发器从结算明细行**派生**，不建关联表也不手工登记 | 依赖 `order_reference` 的**文本匹配**（V348 明确它不指向任何订单表） | 避免第二份到账金额；文本匹配是全仓既有约定，不是本模块新造的松散                                 |
| 只认**销售额行**                                               | 结算单按含税口径记销售额时，在途未结算会被截到 0                     | 佣金等是平台扣走的钱，算进已结算会让「钱没到账」虚高；截到 0 的部分以 `basisGapAmount` 如实报出  |
| 预计到账时间**只做派生、不提供输入**                           | 页面没有「预计到账时间」输入框                                       | 提供了一个也只能填假的 —— 规则一改，历史行就该保留当时的规则快照                                 |
| 规则**只影响之后扫进来的行**                                   | 改规则不会回算历史台账                                               | 规则快照是「当时按什么条件结算」的凭据；回算等于改写历史                                         |
| 扫描**主动触发**而不是后台同步                                 | 需要人点「扫描」                                                     | 上游「卷 02 同步」没有本模块的端点；与其造一条同步链路，不如把扫描做成一个**显式、可审计**的动作 |
| 上限 `200 × 25` 页                                             | 大租户可能被截断                                                     | 翻页无界会把请求线程占满；触到上限时**如实报 `truncated`** 并提示缩小范围                        |

## 上游缺口（如实标注，不造数）

| 缺口                         | 归属                                        | 本模块的处理                                                                        |
| ---------------------------- | ------------------------------------------- | ----------------------------------------------------------------------------------- |
| 平台结算报告自动同步         | 卷 02 同步                                  | **不提供**入口；已结算口径来自 12.3.1.1 的人工上传账单 / 人工登记（那条路径是通的） |
| 自动对账任务                 | 12.3.2.1（本服务的后续模块）                | **不实现**匹配、不产出差异分类；只给两套口径与在途金额                              |
| 利润与经营分析               | 13（别的服务）                              | **不产出任何利润数字**；但把两套口径备好了，下游取数不必重算                        |
| 交易侧「店铺」维度只到平台级 | trade（9.4.1.2 `shipment_record.shop_ref`） | 店铺 code 取自平台订单的 `shop_code`；**不跨服务建 FK**                             |

## 文件清单

**新建**

- `backend/services/finance/src/main/resources/db/migration/V353__finance_deferred_settlement.sql`
- `backend/services/finance/src/main/java/com/starcon/finance/DeferredSettlementModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/DeferredSettlementService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/DeferredSettlementController.java`
- `backend/services/finance/src/main/java/com/starcon/finance/TradeOrderShipmentPort.java`
- `backend/services/finance/src/test/java/com/starcon/finance/DeferredSettlementIntegrationTest.java`
- `backend/services/finance/src/test/java/com/starcon/finance/DeferredSettlementTenancyIntegrationTest.java`
- `apps/web/src/components/finance/deferred-settlement-model.ts`
- `apps/web/src/components/finance/deferred-settlement-model.test.ts`
- `apps/web/src/components/finance/deferred-settlement-workspace.tsx`
- `apps/web/src/app/finance/deferred-settlement/page.tsx`
- `apps/web/src/app/api/finance/deferred-settlement-allowlist.test.ts`
- `docs/sessions/2026-10/2026-10-24-deferred-settlement.md`

**修改**

- `apps/web/src/lib/api-proxy.ts` —— **只新增一条独立 `services[]` 元素**（既有条目一字未改、不重排），
  锚在数组中段 `logistics-channels` 那条之后（与同批其它车道插入点相距 8000 行以上）。
- `packages/api-contracts/src/contracts/finance.ts` + `packages/api-contracts/src/finance.openapi.json`
  —— **codegen 产物，禁止手改**（`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`）。
- `docs/product/capability-blueprint/module-progress.md` —— **只改 12.3.1.3 一行的「状态」「证据」两格**
  （总览统计由 `node tools/module-progress/sync.mjs` 重算）。

## 验证

| 层                                        | 命令                                                                                                                                                     | 结果                                         |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| L0 后端编译                               | `cd backend && ./mvnw -q compile`                                                                                                                        | ✅ BUILD SUCCESS                             |
| L0 类型                                   | `cd worktree && pnpm typecheck`                                                                                                                          | ✅ 3/3 successful                            |
| L1 模块测试                               | `cd backend && ./mvnw -pl services/finance -am test -Dtest='DeferredSettlement*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ **25 例全绿**（业务断言 19 + 租户隔离 6） |
| 前端本模块测试                            | `pnpm vitest run apps/web/src/components/finance/deferred-settlement-model.test.ts apps/web/src/app/api/finance/deferred-settlement-allowlist.test.ts`   | ✅ 15 例                                     |
| 前端共享白名单回归（改了 `api-proxy.ts`） | `pnpm vitest run apps/web/src/app/api/finance`                                                                                                           | ✅ **13 文件 / 67 例**全绿                   |

**L1 的 25 例覆盖**：迁移落地与 FORCE RLS · 规则登记与店铺级/平台级并存 · 扫描入台账 ·
**没有规则时扫描不进货并说明原因** · **上游读不到返回 503 而不是空集合** ·
**PERIODIC 与 ROLLING 的发货时间 / 结算周期区间 / 预计到账时间逐位相等**
（发货时间带时分秒 `08:30Z`，偏 8 小时就是错账）· **服务层与直连 SQL 都写不进预计到账时间与已结算口径金额** ·
**未到账时在途未结算金额 = 订单口径金额** · **结算单到账后已结算口径与在途未结算金额由触发器自动跟着变**
（`PENDING → SETTLED`）· 两套口径随响应返回且不跨币种相加 · 真分页 / 真筛选 / 真关键词 ·
只查在途未结算能看到「已发货未到账」明细 · 作废必须写明理由且作废后不进任何口径 ·
能力声明把未交付的下游说清楚。

**租户三断言**（跑在**真实 PostgreSQL 16** Testcontainers 上，连接池压到 **1**）：
两租户互不可见（含**按主键直查**与**用 `finance_app` 直连数据库反查** ——
不能用超管绕过 FORCE RLS 让断言空转）· 伪造租户上下文无效（直连 SQL 写入被 RLS 挡住）·
伪造 `X-Tenant-Id` 头无效且**行确实落在会话租户**名下（规则行与台账行都断言）·
连接池归还后不串租户。

## 后续待办

1. **13 利润（未交付）落地时**：直接取 `GET /finance/deferred-settlement/summary` 的
   `summaryByCurrency`，**按口径自选**（订单口径 / 已结算口径），**不要在本模块重算一遍**
   「哪些订单的钱还没到账」—— 那正是本模块存在的理由。
2. **12.3.2.1 对账任务落地时**：本模块只给两套口径与在途金额；自动匹配与差异分类归它。
3. **卷 02 同步落地平台结算报告同步后**：本模块的「已结算口径」会多一条同步来源，
   但**不改表结构**（已结算口径本来就是从结算明细行派生的）。
4. **台账里另两条「未读到的上游」若将来落地**，本模块的 `capabilities()` 需要同步更新
   （`available=false` → `true`），**不涉及迁移**。
5. **`packages/ui/src/lib/task-pages.ts` 未登记 `/finance/deferred-settlement`** ——
   按本流水线约定**刻意不碰**那个共享文件（多车道并发改必然冲突），登记缺口由调度者统一处理
   （12.3.1.1 的 `/finance/settlement`、12.1.2.2 的 `/finance/payment-plans` 同理）。
6. **`order_reference` 的文本匹配是已知弱环**：若将来结算单的订单号格式规范化，
   需要回头复核 `finance_deferred_settlement_settled_facts` 的匹配键。
