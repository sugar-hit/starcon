# 2026-10-24 batch-81：财务四格并入 —— 付款单与审批 / 资金分析 / 交易明细 / 供应商对账

> 本批 4 条车道全部并入集成头 `integration/module-dev`，迁移版本 V346 / V347 / V348 / V349。
> 批次号 81（接在 batch-80 之后；同分支上可能有别的 agent 并发写，故按 `ls docs/sessions/$(date +%Y-%m)/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` 现算）。
> 并入 commit：`80a25b22`（12.2.2.1）→ `65027337`（12.7.2.1）→ `3734f79b`（12.3.1.2）→ `2874248d`（12.1.2.3）。
> 逐模块的完整设计决策见各车道自带的会话日志：
> `2026-10-24-12-2-2-1-payment-order-approval.md` / `2026-10-24-12-7-2-1-fund-analysis.md` /
> `2026-10-24-12-3-1-2-settlement-transaction.md` / `2026-10-24-12-1-2-3-supplier-reconciliation.md`。
> 本文件是**批次级汇总**（一个模块一节：这个功能做什么 / 迁移 / 端点 / 页面 / 测试 / 关键设计决策）。

---

## 模块 12.2.2.1 付款单与审批

**这个功能做什么**：发起一张付款申请——选应付明细、付款账户、币种与汇率、付款方式、分批安排——然后走分级审批，产出付款单与审批记录。

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V346__finance_payment_order.sql` —— **五张新表、零 ALTER 零 DROP 既有表**：`finance_payment_order`（单头）/ `_line`（明细行）/ `_installment`（分批安排）/ `_event`（事件流，append-only）/ `_approval_record`（identity 链路的本地镜像）。五表 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + GRANT finance_app/starcon_app。
- 端点：`/finance/payment-orders` × 9 —— `GET /capabilities`（能力声明 + 归属模块）、`GET /options`（状态/审批状态/币种/付款方式/付款对象/付款计划筛选项）、`GET /{id}`（单头 + 明细 + 分批 + 事件流 + 审批记录）、`PUT /{id}`（**仅草稿态**改单）、`POST /{id}/submit`（提交审批）、`POST /{id}/sync-approval`（同步审批结果）、`GET /payable-lines/{payableId}`（某张应付单下可被选中的明细行，只读 12.1.2.1）、`GET /exchange-rate`（汇率快照，读 catalog 5.2.2.1）、`GET` 列表（真分页 + 真筛选 + 真排序 + 按币种分开汇总）。
- 页面：`/finance/payment-orders` —— `apps/web/src/components/finance/payment-order-model.ts` + `-workspace.tsx` + `apps/web/src/app/finance/payment-orders/page.tsx`，**真实工作区**：按币种分开的汇总卡 + 筛选 + 真分页列表 + 详情（明细 / 分批 / 事件流 / 审批记录）+ 建单 + 改单 + 提交 + 同步审批对话框，**加载·空·错误三态齐全**，无占位数据。
- 测试：`PaymentOrderIntegrationTest` + `PaymentOrderTenancyIntegrationTest`（后端，Testcontainers PostgreSQL 16，三条租户隔离断言跑在本模块自己的五张表上）；`payment-order-model.test.ts` + `payment-orders-allowlist.test.ts`（前端）。

**关键设计决策**：

1. **审批引擎不归本模块**——对接已交付的 17 审批（identity），不另造一套。提交走 `POST /identity/approval-instances`，金额阈值分支由 identity 的 `approval_flow_amount_branch` 真的选出来，本模块只如实报出路由依据（`AMOUNT_BRANCH` / `FLOW_ORDER`）。双人复核 / 多级审批由 identity 审批流的**节点结构**承担（`PARALLEL` = 双人复核，`SERIAL` = 多级审批），本模块只展示不自己判定。**刻意不提供本地「审批通过/驳回」端点**——两套审批引擎会让同一单据有两个状态源。
2. **「已付」不是一段没写的代码路径，而是走不通的路径**：`execution_available` 由表级 CHECK 钉死为 false、`execution_owner_module` 恒为 `'12.2.2.2'`，触发器 `guard_finance_payment_order_state` 拒绝任何写入 `EXECUTING / PAID / FAILED`。12.2.2.2 交付时接管这两个守卫即可，状态机无需改动。
3. **「已驳回」不是一个状态**：被驳回的付款单退回草稿（可改可再提交），驳回记在审批记录与事件流里——单据状态只回答「这笔钱现在走到哪一步」。
4. **提交后钱的那些事实一律冻结**（金额/币种/汇率/付款方式/付款账户/付款对象）——改了等于「审批批的是 A、付出去的是 B」。⚠️ 守卫的 BEFORE UPDATE 触发器**必须返回 NEW**：返回 OLD 等于把该行改回旧值，会让每次更新静默失效。
5. **付款必须精确到应付明细行**：`payable_line_id` NOT NULL 外键指向 12.1.2.1 的 `finance_payable_line`，且部分唯一索引保证同一条应付明细行不会被两张在册付款单同时占用。

---

## 模块 12.7.2.1 资金分析

**这个功能做什么**：算清楚钱在哪儿——现金流入流出趋势、各币种资金分布与汇率敞口、资金周转天数、付款集中度与流动性预警。

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V347__finance_fund_analysis.sql` —— **两张新表、零 ALTER 零 DROP**：`finance_liquidity_threshold`（流动性预警阈值配置，租户自维护，**表里不预置任何一行**——没配过时生效的是蓝图默认档，响应里如实标出 source）+ `finance_fund_exposure_snapshot`（汇率敞口测算快照，把「用的哪天的汇率、汇率多少、两端金额多少」一起钉住⇒事后可复盘；只能追加不能改，运行角色的 UPDATE/DELETE 已收回）。两表 RLS + FORCE + GRANT。
- 端点：`/finance/fund-analysis` × 12 —— `GET /capabilities`、`GET /options`、`GET /cash-flow-trend`（趋势）、`GET /currency-distribution`（币种分布）、`GET /exposure` + `POST /exposure/measure`（汇率敞口与测算）、`GET /exposure/snapshots`（敞口快照）、`GET /turnover-days`（周转天数）、`GET /payment-concentration`（付款集中度）、`GET /liquidity-alerts`（流动性预警）、`GET /thresholds` + `PUT /thresholds`（阈值配置）。
- 页面：`/finance/fund-analysis` —— `apps/web/src/components/finance/fund-analysis-model.ts` + `-workspace.tsx` + `apps/web/src/app/finance/fund-analysis/page.tsx`，**真实工作区**：趋势图 / 币种分布 / 敞口 / 周转 / 集中度 / 预警六块 + 阈值配置对话框，**加载·空·错误三态齐全**。
- 测试：`FundAnalysisIntegrationTest` + `FundAnalysisTenancyIntegrationTest`（后端）；`fund-analysis-model.test.ts` + `fund-analysis-allowlist.test.ts`（前端）。

**关键设计决策**：

1. **上游缺口如实标注、绝不造数**：12.7.1.1 账户管理 / 12.7.1.2 银行对账均未开发 ⇒ 全仓 grep 过 finance 服务没有任何资金账户表 / 银行流水表（`fund_account` / `bank_statement` 零命中），且 V341 的 `finance_receipt.fund_account_ref` 由 CHECK 钉死恒为 NULL。处置：**不产出账户级余额与账户级分布**（只到币种级；「累计已到账现金」是历史累计数，≠ 在手余额，响应里逐处标注）；**不做银行对账、不产对账差异、不产未达账项**（实际流入取自 12.2.1.1 收款单，不是银行流水）；DB 层把归属**钉在数据上**——`fund_account_ref` / `bank_statement_ref` 由 CHECK 钉死恒为 NULL，owner_module 恒为 `'12.7.1.1'` / `'12.7.1.2'`，`account_level_available` 恒为 false。
2. **「实际 ≠ 计划」的口径纪律**：12.1.2.2 付款计划的 `execution_available` 被 CHECK 钉死为 false ⇒ 计划表里写不出「已付款」⇒ 流出侧只有计划口径（按计划付款日）与累计已付口径。**趋势报表不给「实际净额」**——实际流出没有日期维度，硬凑一个「实际流入 − 0」叫净额，那是把缺失当零。只给「预期流入 − 计划流出」这一种同口径预测净额。
3. **日历日一律 UTC**（`LocalDate.now(ZoneOffset.UTC)` / SQL 侧 `(col at time zone 'UTC')::date`）；周转天数分子分母同币种同日历日口径；**分母为 0 时给 null**（「算不出」≠「0 天」）。
4. **汇率敞口口径**：资产端 = 未核销应收，负债端 = 未付应付，净敞口 = 资产端 − 负债端。**已到账现金不计入敞口净额**（一笔收款与其对应应收可能是同一笔钱，并起来会重复计算），已到账现金单列在币种分布里。**存货不算进敞口**（不在本服务，缺的那一半如实标注归属，不用 0 冒充）。

---

## 模块 12.3.1.2 交易明细

**这个功能做什么**：把一笔笔平台交易落成可核对的明细——交易类型归类、关联订单行、幂等去重，并与结算单汇总实时对账。

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V348__finance_settlement_transaction.sql` —— **新建 1 张表 + 在 12.3.1.1 的明细行表上补 5 列，不改 V345 的一个字**（V345 已进过集成头并被应用过，改内容会让 Flyway checksum 校验炸掉）。新建 `finance_settlement_transaction_source_file`（交易报告来源文件）；补列 `transaction_type`（10 类词表）/ `order_reference`（可空）/ `order_line_reference`（可空）/ `idempotency_key` / `source`。
- 端点：`/finance/transactions` × 7 —— `GET /capabilities`、`GET /options`、`GET /{id}`（明细详情）、`GET /reconciliation`（**实时算出来的核对**：明细合计 vs 结算单汇总）、`POST /import`（平台交易报告上传解析入库）、`POST /batch`（批量登记）、`GET` 列表（真分页 + 真筛选 + 真排序）。
- 页面：`/finance/transactions` —— `apps/web/src/components/finance/transaction-model.ts` + `-workspace.tsx` + `apps/web/src/app/finance/transactions/page.tsx`，**真实工作区**：汇总卡 + 筛选 + 真分页列表 + 详情 + 导入对话框 + 核对视图，**加载·空·错误三态齐全**。
- 测试：`TransactionIntegrationTest` + `TransactionTenancyIntegrationTest`（后端）；`transaction-model.test.ts` + `transactions-allowlist.test.ts`（前端）。

**关键设计决策**：

1. **复用 `finance_settlement_line`，不另建平行的明细表**：那张表**就是**「结算单下的逐笔明细」（按结算单挂载、append-only、tenant_id NOT NULL + FORCE RLS）。两张明细表各写一份金额 ⇒ 结算金额有两个来源 ⇒「明细合计 = 结算单汇总」立刻变成两条对不上的账，而且是金额平、来源乱的最难查的一类错账。本模块**只写明细行**，V345 的汇总项与结算金额派生链**一个字节都不碰**。
2. **「代扣税」落成独立汇总桶 'TAX'**：V345 的七个类别里没有它的位置——塞进 `ADJUSTMENT` 会让「其他调整」不再是「其他」，塞进 `COMMISSION` 是金额口径错。⇒ V348 **同时**放宽 `finance_settlement_line` 与 `finance_settlement_summary` 两张表的类别 CHECK（V345 的派生链按 category 聚合，两边词表必须一致，只改一边会让派生链一插就炸）。
3. **「不强制每行都有订单是硬约束」在数据层同时三件事成立**：`order_reference` / `order_line_reference` **可空**且**没有**指向订单表的跨库 FK（订单在 trade 服务）；**没有任何触发器/CHECK 要求某类明细必须带订单号**⇒「费用类明细缺订单」这个虚假异常来源结构上不存在；唯一一条弱约束是「给了订单行号就必须给订单号」（那是数据错误，不是「缺订单」）。汇总校验里「无订单行的明细笔数」是**正常状态**，不进 `issues`。
4. **幂等键两道闸**：同单内由部分唯一索引挡；跨单由 BEFORE INSERT 守卫 `guard_finance_settlement_line_idempotency` 挡（同一笔平台交易不许记两次）。⚠️ 守卫**刻意不是 SECURITY DEFINER**：以调用方身份执行 ⇒ RLS 生效 ⇒ 只看得到本租户的行（否则 A 租户会被 B 租户的键挡住，错误消息里还会带出别家的键）。
5. **作废（VOID）与被取代（SUPERSEDED）的单不持有幂等键** ⇒ 12.3.1.1 的「同一份账单重解析 → 新版本 → 新单」这条路不会被幂等键挡住。

---

## 模块 12.1.2.3 供应商对账

**这个功能做什么**：把采购侧对账单与仓库收货、质检逐条核对，算出差异、裁决差异、登记说明，最后由财务确认——确认是**唯一**能产生应付的入口。

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V349__finance_supplier_reconciliation.sql` —— **七张新表、零 ALTER 零 DROP 既有表**（不碰 V338 应收族 / V340 应付族 / V343 付款计划族 / V345 结算族）：`finance_supplier_reconciliation`（头）/ `_line` / `_inspection`（质检证据）/ `_difference` / `_explanation`（差异说明）/ `_event`（事件流）/ `_payable`（应付依据）。七表 RLS + FORCE + GRANT，说明与事件流 REVOKE UPDATE/DELETE（append-only）。
- 端点：`/finance/supplier-reconciliation` × 12 —— `GET /capabilities` / `GET /options` / `GET` 列表（真分页 + 真筛选 + 按币种分开的汇总）/ `GET /{id}`（头 + 明细行 + 质检证据 + 差异 + 说明 + 事件流 + 应付依据）/ `GET /{id}/lines` / `GET /{id}/differences`（均真分页）/ `POST /import`（幂等导入）/ `POST /{id}/match`（核对收货与质检，产出差异）/ `POST /{id}/differences/{did}/explanations`（登记说明，**不裁决金额**）/ `POST /{id}/differences/{did}/resolve`（裁决）/ `POST /{id}/confirm`（**本模块唯一产生应付的入口**）/ `POST /{id}/reject`（终态冻结，理由必填）。权限码三分：`finance.suppliereconciliation.read` / `.update` / `.confirm`。
- 页面：`/finance/supplier-reconciliation` —— `apps/web/src/components/finance/supplier-reconciliation-model.ts` + `-workspace.tsx` + `apps/web/src/app/finance/supplier-reconciliation/page.tsx`，**真实工作区**：按币种分开的汇总卡 + 筛选 + 真分页列表 + 详情（明细行 / 质检证据 / 差异清单 / 说明 / 事件流 / 应付依据）+ 导入 + 核对 + 登记说明 + 裁决对话框 + 确认对话框（**不能确认时明说为什么**，不让财务对着一个不能点的按钮猜）+ 驳回对话框，**加载·空·错误三态齐全**。
- 测试：`SupplierReconciliationIntegrationTest`（19）+ `SupplierReconciliationTenancyIntegrationTest`（5，后端，Testcontainers PostgreSQL 16.15、连接池压到 1，三条隔离断言跑在本模块自己的七张表上）；`supplier-reconciliation-model.test.ts`（17）+ `supplier-reconciliation-allowlist.test.ts`（6，前端）；另跑**全量 allowlist 回归 218 文件 / 1239 例**通过。

**关键设计决策**：

1. **「财务是最终确认方，采购不能自行确认应付——这是职责分离」钉在 schema + 权限 + 端点三层**：(1) **schema** —— 头表 `status`（IMPORTED → MATCHED/DIFFERENCES → CONFIRMED/REJECTED）+ `confirmed_by/confirmed_at/payable_id/finance_confirmed_amount`，表级 CHECK `finance_supplier_reconciliation_state_consistent` 规定「**CONFIRMED 之外这三个字段一律为空**」⇒ 采购侧数据在财务确认前结构上无法变成已确认应付；(2) **权限** —— 采购侧角色只给 read+update ⇒ 调裁决 / 确认端点**直接 403**；(3) **端点** —— 导入与核对属采购侧，确认 / 驳回 / 裁决属财务侧。
2. **跨服务读一律走授权 HTTP API Port**（转发调用方 bearer，零跨库 SQL / FK / 视图，关键值快照列落库）：`SupplierStatementPort` → `GET /trade/purchase-reconciliations`（6.5.1.1）· `ArrivalEvidencePort` → `GET /trade/purchase-arrivals/{id}`（6.3.2.2）· `QualityInspectionPort` → `GET /trade/quality-inspections`（**6.4.2.1 质检单**）。
3. **质检真来源不是 `/trade/return-quality`**（那是 11.x 退货质检）。关联链是收货单号：对账行 `sourceLineRefCode` → 质检单 `receiptCode`。扣量口径直接用上游的 `grossDeductedQuantity`（= 不合格量 + 让步接收量），**不自己发明规则**（上游自身质检扣量未接线、`qualityDeductionStatus='UNAVAILABLE'` ⇒「质检扣量未扣除」正是财务侧要核的事，不是 bug）。
4. **只导入上游状态 CONFIRMED / CLOSED 的对账单**（DRAFT/SENT/DISPUTED 跳过并在导入结果逐条说明，不静默丢弃，也不假装导入成功）。
5. **差异按行分解成互不重叠的分量**，类型 5 种；裁决词表只有 `APPLIED` / `KEPT` 两个值，`adjustment_amount` 是**生成列**（裁决了改不动金额）；差异说明 append-only；**已裁决的差异不可改判、不可重算**。**8 个 DB 触发器**把关键规则钉在数据层（确认必须有且仅有一条应付依据且金额一致且**无未裁决差异**、终态冻结、应付依据必须指向 `source='SUPPLIER_STATEMENT'` 的未作废应付单、单条差异金额不得越过整行差额、差异计数派生）——绕过服务层直连 SQL 也确认不出「没有应付依据的已确认对账单」。
6. **落到 12.1.2.1 的路径没有绕过 V340**：应付依据表 `UNIQUE(tenant_id, recon_id)` 叠加 V340 的 `UNIQUE(tenant_id, source, source_reference)` ⇒ **重复确认不长第二张应付**。应付单**按净额一行**（V340 明细行要求 `amount>0`，表达不了负贡献——质检扣减与金额残差可以是负数 ⇒ 分项留在对账行表，应付单只写净额；净额 ≤ 0 时**拒绝确认**并引导走「财务驳回」）。
7. **过程中修掉的真实缺陷**：① **白名单首 token 与后端 `@RequestMapping` 差一个字母**（`supplier-reconciliation` vs `supplier-reconciliations`）⇒ 浏览器里每个请求静默 404，而 typecheck / lint / vitest / Playwright 全绿 ⇒ 新增一条断言把后端控制器源码与白名单钉在一起，并反向断言复数写法必须 404；② `BEFORE DELETE` 触发器里 `RETURN NEW` 会让 DELETE **静默失效**（DELETE 场景 NEW 为 NULL，返回 NULL = 跳过删除）⇒ 三个冻结触发器改成 `TG_OP='DELETE'` 分支取 `OLD` 并 `RETURN OLD`；③ 约束名超 63 字符被 PG 截断；④ jOOQ 占位符与目标列数必须逐个数清；⑤ timestamptz 列必须写 `?::timestamptz`；⑥ Java 不能把 `Long` 直接强转 `int`（要用 `.intValue()`）。

---

## 本批共性纪律

| 纪律 | 四格的共同落法 |
| --- | --- |
| 不造数 | 上游未交付的能力**在 DB 层钉死归属**（CHECK 钉死 NULL / owner_module 恒定 / `available=false`），不用 0、不用常量数组冒充 |
| 跨服务不跨库 | 一律授权 HTTP API Port（转发调用方 bearer），零跨库 SQL / FK / 视图，关键值快照落库 |
| 多租户 | 业务表 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS；三条隔离断言（互不可见 / 伪造 header 无效 / 连接池归还不串租户）跑在**本模块自己的表**上 |
| 共享白名单 | api-proxy 只**纯追加**独立 `services[]` 元素，既有条目一字未改、不重排；并跑**全量 allowlist 回归** |
| 金额 | `BigDecimal` + `NUMERIC`，禁浮点；守卫触发器以调用方身份执行（**非** SECURITY DEFINER）⇒ RLS 生效 |

## 静态预检（互相破坏形状扫描）

对本批四个新迁移跑 `ADD COLUMN` / `SET NOT NULL` / `ADD CONSTRAINT ... CHECK` 扫描：

- **V346 / V347 / V349**：零命中（纯新建表）。
- **V348**：`ALTER TABLE finance_settlement_line ADD COLUMN` × 5，**无 DEFAULT**，但目标表 `finance_settlement_line` **有 BEFORE INSERT 触发器** `finance_settlement_line_default_fields`（补 `transaction_type` / `source`）⇒ 不构成违约。
- 全批**零 `SET NOT NULL`**、零新增 CHECK 约束命中跨车道风险。

## 本轮未完成 / 边界

- **12.3.2.1 对账任务 / 12.3.2.2 差异处理 / 12.3.2.3 自动匹配规则未交付** ⇒ 12.1.2.3 只**产出差异清单**，不实现规则引擎式自动匹配、也不宣称「已自动对账」。
- **导航注册**（`packages/ui/src/lib/task-pages.ts` 登记四个新路由）**刻意不在本批车道**，归导航车道。
- 12.1.2.1 能力声明里「12.1.2.3 未交付」**已过期**，归 12.1.2.1 车道改。
- 应付确认政策**不预置任何一行** ⇒ 使用方需自行启用 `(SUPPLIER_STATEMENT, PAYABLE)`，否则确认被政策闸门拒（12.1.2.3 在能力声明里写明）。
