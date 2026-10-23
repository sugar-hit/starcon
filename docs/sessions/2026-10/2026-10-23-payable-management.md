# 2026-10-23 12.1.2.1 应付单管理（阶段 D6，卷 12 L3 12.1.2）

分支 `feat/12-1-2-1`，基线 `integration/module-dev`（8a5b91be merge(12.1.1.1)）。两个 atomic commit。

## 摘要

卷 12「财务核算」L3 12.1.2「应付」下的第一格。蓝图 12-finance.md 原文：

> 职责：管理应付账款：来源（采购收货、供应商对账、物流费用、平台费用、其他）、应付对象、
> 金额与币种、到期日、已付金额与未付余额。输入：采购收货确认（8.2.1.1）；服务费用（物流、平台、
> 广告）；供应商对账单（6.5.1）；人工录入。输出：应付单 + 明细 + 未付清单。
> 关系：上游 = 6 采购、10 物流、14 广告；下游 = 12.2.2.1 付款。
> **应付、预付款与采购承诺分开建模**；确认依据由业务合同和已批准政策决定，不能仅凭下单或收货统一推导。

本车道把这两句加粗句变成**数据层做不到的事**，并把「上游事实」接了三个**真实**上游。

## 上游自检结论（开工前 10 分钟）与任务书的一处出入

任务书给的台账事实是「6.5.1 供应商对账未交付（卷 06 采购交付数 = 0）」。本车道在集成头里
grep 到的实际状态与之不同：**6.5.1.1 采购对账已交付**（trade `V250__purchase_reconciliation.sql`，
端点 `/trade/purchase-reconciliations`），卷 06 台账也是 26/26 已交付。

但**结论仍按任务书的口径执行**，理由是蓝图的职责分离那一句：

> 12.1.2.3 供应商对账：**财务是最终确认方，采购不能自行确认应付** —— 这是职责分离。

也就是说：6.5.1 的对账单是**采购侧**的结论，把它变成应付需要**财务侧**的核对与确认，
而那一格（12.1.2.3）**未交付**。⇒ `SUPPLIER_STATEMENT` 来源当前**不可产生**，
归属模块写 **12.1.2.3**。这与任务书要求一致，只是理由比任务书写的更硬。

另外两处与任务书的出入（已在台账与会话日志里如实记录）：

- **8.2.1.1 收货单不作为应付来源**。warehouse 的 `goods_receipt` 只有 SKU × 数量 × 批次 × 库位，
  **没有供应商、没有单价、没有币种**（收货单自己的能力声明里明写「采购来源当前不接受」）。
  拿它推应付就是蓝图禁止的「仅凭收货统一推导」。真正的接缝在 **trade 6.3.2.2 到货跟踪**：
  它有「单价快照 × 本次到货数量」算出来的应付金额、供应商、币种，并且**已经有一张
  `purchase_arrival_payable_confirmation`（应付部分确认）**，状态恒为「待财务确认」、
  生效金额恒为 0，且明写「财务确认走独立动作 + 独立权限（trade.purchase.finance）」。
- **广告费单列**。蓝图来源词表是「采购收货、供应商对账、物流费用、平台费用、其他」五项，
  而输入写的是「服务费用（物流、平台、**广告**）」。卷 14 广告已交付 18 格且有花费事实
  （`ad_campaign_daily_metric.spend`），所以本模块加一个 `ADVERTISING_FEE`（广告费）来源，
  归属 14.3.1.1 —— 这是对蓝图输入句的直接落地，不是自造词表。

## 关键决策

### 1. 应付、预付款、采购承诺分开建模 = 两张表 + 一个词表 + 不建模

- 应付 → `finance_payable`（+ `_line` / `_event`）
- 预付款 → `finance_prepayment`（+ `_allocation`）：**独立聚合**，有自己的 URL 前缀
  （`/finance/prepayments`）、自己的权限码（`finance.prepayment.*`）、自己的状态机。
- 采购承诺 → **本服务不建模**。它的事实所有者是 6 采购（trade 的采购单）；
  另建一张 finance 表等于把同一份事实写两遍（AGENTS.md：每张事实表只有一个写入所有者）。
  义务类型词表 `PAYABLE / PREPAYMENT / COMMITMENT` 三类分开，能力声明里写明
  COMMITMENT 归属 6.3.1.1 且**不可产生**，前端**不给它开关**。

**预付款刻意没有「已付金额」字段**：付款是 12.2.2.1 的职责，本模块只登记预付事实与抵扣，
**不产生任何「已付款」回执**。抵扣也**不改**应付单的已付金额 —— 它只是预付款的账，
给付款模块当输入用。

### 2. 已付金额与未付余额：派生值，库里没有可手改的标记

行级 `paid_amount` 是付款的唯一入口（12.2.2.1 未交付 ⇒ 本模块**不提供**任何改已付金额的
端点，也**不提供**任何「手工标记已付」的旁路）。单头 `paid_amount` 由触发器从明细求和派生，
单头 `unpaid_amount` 是**生成列**（`amount - paid_amount`），单头付款状态同样由明细派生
（`OPEN / PARTIALLY_PAID / PAID`）⇒「单头说已付清、明细说未付」在数据层写不进去。
当前已付恒为 0 ⇒ 单头恒为「未付」—— 这是**事实**（钱确实还没付），不是缺口，
能力声明里如实写成「付款写入口未交付」。

### 3. 确认依据 = 已批准政策 + 合同依据，缺一不可

- `finance_payable_policy` 按「来源 × 义务类型」一行，**不预置任何一行**
  （预置 `enabled=true` 等于替用户把会计政策定了）。没有启用的政策 ⇒ 该来源一张单都产生不了。
- **采购收货来源必须带单价快照与数量**（合同依据）。上游给不出 ⇒ 跳过 + 报警告，
  **绝不填 0、也绝不编一条合同**。
- 人工录入**只能** MANUAL / OTHER；采购收货 / 物流费用 / 广告费**只能**由上游事实产生。

### 4. 三个真实上游（跨服务只经授权 HTTP API）

| 来源 | 上游 | 端点 | 取什么 |
| --- | --- | --- | --- |
| PURCHASE_RECEIPT | trade 6.3.2.2 到货跟踪 | `GET /trade/purchase-arrivals` + 详情；`POST .../payable-confirmations/{id}/confirm` | 待财务确认的应付部分确认（金额 / 币种 / 数量 / 单价快照） |
| LOGISTICS_FEE | warehouse 10.4.1.1 头程发运单 | `GET /warehouse/first-mile-shipments` | 承运商 + 币种 + 运费 / 保险费 / 处理费 |
| ADVERTISING_FEE | channel 14.3.1.1 活动报表 | `GET /channel/ad-reports/rows` | 花费（spend）+ 币种 + 平台 |

**采购收货这条带写回**：建完本服务的应付单后，把**本服务的应付单号**写回上游
（上游把这一动作单列为 `trade.purchase.finance` 权限）。写回失败**不吞掉** ——
告警里写明是哪一笔，重新导入时上游仍是「待财务确认」就会补写。

**10.6.1.1 物流成本分析刻意不接**：它记的是成本估算与分配口径（每票包材成本参数、
分配规则、按订单/按渠道的分摊视图），算的是「这批货的成本大概多少」，
**不是「欠承运商多少钱」的账单事实** —— 拿估算值当应付等于把预估当结算。

**关税 estimate 不进应付**：`duty_estimate` 是估算（估算 != 账单），真关税属 10.5.1.3 税则口径。

## 涉及文件

**新增**：

- `backend/services/finance/src/main/resources/db/migration/V340__finance_payable.sql`（六张表 + 触发器 + RLS）
- `backend/services/finance/src/main/java/com/starcon/finance/PayableModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/PayableService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/PayableController.java`
- `backend/services/finance/src/main/java/com/starcon/finance/PayableSourceFact.java`
- `backend/services/finance/src/main/java/com/starcon/finance/PurchaseArrivalPort.java`
- `backend/services/finance/src/main/java/com/starcon/finance/FirstMilePort.java`
- `backend/services/finance/src/main/java/com/starcon/finance/AdSpendPort.java`
- `backend/services/finance/src/test/java/com/starcon/finance/PayableIntegrationTest.java`（38 例）
- `backend/services/finance/src/test/java/com/starcon/finance/PayableTenancyIntegrationTest.java`（5 例）
- `apps/web/src/components/finance/payable-model.ts` / `payable-model.test.ts` / `payable-workspace.tsx`
- `apps/web/src/app/finance/payables/page.tsx`
- `apps/web/src/app/api/finance/payables-allowlist.test.ts`

**修改**：

- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，既有条目一字未改、也不重排）
- `apps/web/src/app/finance/page.tsx`（加一行「应收单管理 / 应付单管理」并列入口）
- `packages/api-contracts/src/contracts/finance.ts` + `packages/api-contracts/src/finance.openapi.json`（codegen 重生成）
- `docs/product/capability-blueprint/module-progress.md`（**只改 12.1.2.1 一行的「状态」「证据」两格**；
  其余变化是 `sync.mjs` 刷新出来的计数器与清掉的 47 行历史残留汇总块）

## 关键 trade-off

| 选项                                             | 选哪个                                   | 代价                                                                 |
| ------------------------------------------------ | ---------------------------------------- | -------------------------------------------------------------------- |
| 应付依据从哪来：收货单 vs 采购单+到货事实        | **trade 到货应付部分确认**               | 多一个跨服务端口 + 写回动作；收货单那侧则完全没有金额可用           |
| 三个义务类型：一张表 + type 字段 vs 两张表        | **两张表（应付 / 预付款）+ 词表**        | 预付款要另写一套模型与端点；换来的是三者不可能被混着算               |
| 采购承诺：也建一张表 vs 不建模                   | **不建模**（归属 6 采购）                | finance 侧暂时答不了「承诺额」；另建表等于同一事实写两遍             |
| 供应商对账单：任务书说未交付 vs 6.5.1.1 已交付  | **按未交付处理**（财务侧确认属 12.1.2.3）| 要用 6.5.1.1 的对账单就得跳过 12.1.2.3 的职责分离                   |
| 已付金额：加「手工标记已付」端点 vs 派生          | **派生**（12.2.2.1 交付后写行级列）      | 当前页面看不到任何「已付」动作；那个动作一旦存在就是假的付款路径     |
| 广告费：并进 PLATFORM_FEE vs 单列来源            | **单列 ADVERTISING_FEE**                 | 词表比蓝图五项多一项；蓝图输入句明确点了「广告」                     |
| api-proxy：并进既有 finance/ 长正则 vs 独立条    | **独立 services[] 元素**                 | 每车道多一条正则，但多车道并行时 git 不冲突                           |

## 后续待办

- **12.2.2.1 付款单与审批**：付款的唯一写入口是行级 `paid_amount`；交付后写它即可，
  单头已付金额、未付余额（生成列）与付款状态由触发器自动跟上，**不需要改本模块的表结构**。
  权限码另立 `finance.payment.*`，不要复用。
- **12.1.2.2 付款计划**：本模块已给出到期日与按币种分开的未付余额，读同一批表即可。
- **12.1.2.3 供应商对账（财务侧）**：交付后放开 `SUPPLIER_STATEMENT` 来源，
  并把能力声明里 `available=false` 改成真实可用。
- **12.3.1.1 结算单管理**：交付后放开 `PLATFORM_FEE` 来源。
- **预付款撤销抵扣**：本模块**不提供**（归属 12.2.2.1 域），已有抵扣的预付款因此不可作废。
- **采购承诺**：若将来要在 finance 侧看到承诺额，正确做法是**读** 6 采购的采购单，
  而不是在本服务建第二份。

## 验证

- L0：`cd backend && ./mvnw -q compile` ⇒ EXIT=0。
- L1（只跑本车道新增的测试类）：
  `./mvnw -pl services/finance -am test -Dtest='Payable*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ **Tests run: 43, Failures: 0, Errors: 0** BUILD SUCCESS
  （PayableIntegrationTest 38 + PayableTenancyIntegrationTest 5）。
- 前端：`pnpm vitest run apps/web/src/components/finance/payable-model.test.ts
  apps/web/src/app/api/finance/payables-allowlist.test.ts` ⇒ **2 文件 / 23 例全绿**。
- **改了共享白名单 `api-proxy.ts` ⇒ 必须跑全量 allowlist 回归**：
  `pnpm vitest run apps/web/src/app/api` ⇒ **211 文件 / 1202 例全过**。
- `pnpm typecheck` ⇒ 3/3 successful；`pnpm lint` ⇒ 3/3 successful（仅既有文件 warning）。
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  重生成（**codegen 产物未手改**）⇒ finance 契约新增 27 个 `FinancePayable*` / `FinancePrepayment*`
  类型，**其余六个服务契约零变化**。

**Testcontainers 容器观察**：L1 期间本车道同时存活 **2 个** `postgres:16-alpine`
（本车道两个测试类各起一个），跑完后 `docker ps --filter label=org.testcontainers=true` 为空
⇒ ryuk 已正常回收。

## 关联

- [ADR-0004 Java Spring Boot 微服务](../../decisions/0004-java-spring-boot-microservices.md)
- [ADR-0002 多租户策略](../../decisions/0002-multi-tenancy-strategy.md)
- [多租户架构说明](../../architecture/multi-tenancy.md)
- [台账：12.1.2.1 应付单管理](../../product/capability-blueprint/module-progress.md)
- [上一车道：12.1.1.1 应收单管理](./2026-10-23-finance-service-bootstrap-receivable.md)
