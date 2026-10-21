# 2026-10-21: 6.3.1.3 采购单审批

## 摘要

交付能力蓝图 **6.3.1.3 采购单审批**（trade 服务，阶段 D6，L3 锚点 6.3.1）。

蓝图原文：「按金额阈值与采购员分级审批，支持批量审批、驳回并附意见，审批通过后锁定单据生成应付。」
输入「待审批采购单；17 组织的审批规则（金额阈值、层级）」，
输出「审批结果 + 审批链路记录；通过后触发 12 生成应付、8 允许收货」。
关系：上游 = 17 审批、6.3.1.2；下游 = 12 应付、8 收货。
蓝图加粗句：「**审批通过的采购单不可直接改数量与单价**——如需变更，走变更单或红冲重下，
避免已生成应付与实际收货对不上。」

本车道交付：Flyway 迁移 `V262`（七张新表 + 2 个序列 + 3 个函数 + 3 个触发器）、
trade 服务 16 个端点 `/trade/purchase-order-approvals/**`（jOOQ 显式 SQL）、
一个真实工作区页面 `/purchase/approvals`、契约重生成、台账与本会话日志。

**开工前的依赖自检（10 分钟）全部通过**：6.3.1.1 的 `purchase_order` / `purchase_order_line` /
`purchase_order_event`（V254）、6.3.1.2 的五张快照表（V258）、
以及 6.3.1.2 的 `submit()` 只推状态不动行表（逐行核对过源码）——
这三条是本模块能安全「只在行表上**加**一个触发器」的前提。

## 决策 / 结论

1. **🔴 审批规则放在 trade 侧，而不是委托给 identity 的通用审批流。**

   任务书说「17 组织的审批规则服务不存在」，实查发现 **identity 侧确实有**一套通用审批
   （`/identity/approval-rules/evaluate`、`/identity/my-approvals/batch` 等），6.2.2.1 采购申请
   正是委托它。但**蓝图那句「按金额阈值与**采购员分级**审批」在 identity 的规则模型里表达不出来**：
   identity 的规则维度是单据类型 / 金额区间 / 跨法人 / 跨部门 / 新供应商 / 高风险商品，
   **没有「采购员分级」，也没有「审批人的金额授权区间」**。要表达就得改 identity 的规则模型 ——
   那是另一个服务的写入所有权（AGENTS.md 明令禁止）。

   ⇒ 本模块自持三张配置表（规则 / 采购员分级 / 审批人），并在能力声明里把这条写清
   （`identityApproval` 标 `available=false` 并点名原因），而不是假装「17 没这个能力」。

2. **🔴 命中判定只有一处实现：DB 函数 `purchase_order_approval_match(tenant, order)`。**

   服务端与 DB 触发器**共用同一个 SQL 函数**算「(采购员分级, 币种, 金额区间) ⇒ 层级与人数」。
   任务书要求「命中判定放服务端 + DB」——如果两侧各写一份，规则改一次就会漂移，
   而漂移的表现是「审批人批的和自己看到的不是同一档」。共用一个函数是唯一能保证不漂移的做法。
   测试断言「提交之后改采购员分级不改已冻结的审批链」，把冻结语义钉死。

3. **🔴 审批链在提交那一刻冻结（DB 触发器 `purchase_order_approval_case_after_submit`）。**

   DRAFT → PENDING_APPROVAL 的 UPDATE 触发 AFTER UPDATE 触发器落一条审批单。
   理由：「这一单要几级审批」是**审计事实**，事后规则一改它不能跟着变。
   副作用（已核过）：6.3.1.2 的 `submit()` 只 UPDATE 单头、**不动行表** ⇒ 不打断它的任何行为
   （上游 16 例回归全绿）。

4. **🔴 「审批通过后不可改数量与单价」落成 DB 触发器，不落成服务层校验。**

   新增 BEFORE UPDATE 触发器 `guard_purchase_order_line_approved_lock` 挂在 6.3.1.1 的
   `purchase_order_line` 上（**纯增量：零 ALTER、零改既有列/约束/触发器**，与 V258 的
   `guard_purchase_order_line_delete_guard` 同一写法）。
   判定口径与那条删除守卫**同款**（`head_status IS DISTINCT FROM 'DRAFT'` ⇒ 不许改）——
   即提交之后（PENDING_APPROVAL 及以后）一律锁死，其中 APPROVED 之后是蓝图点名的那一段。
   为什么不用服务层校验：服务层能被别的写入口、脚本、直接 SQL 绕过，而「已批的单子改数量」
   是**会算错钱**的那一类错误。测试**直连 SQL** 断言被拒（绕过服务层也不行）。

5. **🔴 三个下游的口径（12 / 17 / 08 都不在本模块内代做）。**

   - **12 财务不存在** ⇒ 应付是 **trade 侧自有事实记录**，照抄 11.2.3.3 的
     `supplier_claim_settlement` 两列写法：登记额与生效额分列，登记时生效恒为 0，
     表级 CHECK 显式命名（`purchase_order_payable_pending_zero` 等 ——
     PostgreSQL 自动生成的 `check1` 在报错里没法读）。
   - **08 仓储在独立库** ⇒ 只落 `purchase_order_receipt_release` 这条**放行事实**，
     不改 warehouse 表、不建跨库 FK、不代写收货单。
   - **17 组织** ⇒ 见决策 1。

6. **🔴 四条硬纪律（写在服务层 + DB 层）。**

   - **不能自审**：采购员不能审批自己提交的采购单。少了这一条，「按采购员分级审批」整套管控等于没有。
   - **一个人在一张单上只批一次**：不能自己批两次凑够人数（`UNIQUE(tenant_id,case_id,approver_user_id)` 兜底）。
   - **「没配规则」走默认档（L1、1 人）**，而不是「当作已批」—— 审批这一环不能被悄悄跳过。
   - **驳回必须附意见**（「驳回而不说为什么」等于没驳回」，DB CHECK 与服务层都钉）。

7. **🔴 批量逐条给结论，且失败的那张不留半截写入。**

   `POST /orders/batch` 对每张单开一个 **JDBC 保存点**（jOOQ 的 `savepoint()` /
   `rollback().toSavepoint()`），失败那张回滚到保存点 ⇒ 不会出现「审批留痕已写、审批单没推进」
   这种不一致被提交进库。测试断言：金额为 0 的那张批不过，且它的审批留痕**仍是 0 条**。

8. **🔴 权限码分四档。**

   `trade.purchase.read` / `trade.purchase.update`（规则与审批人配置）/
   **`trade.purchase.approve`（审批是独立的一条）** / `trade.purchase.finance`（财务确认生效）。
   照 11.2.3.3 的 `trade.aftersales.finance` 同款：谁能把单批成已批，不该由「能改这张单」顺带获得。
   数据范围由服务端从角色授权推导（ALL / DEPARTMENT / SELF），不接受请求参数传入。

9. **🔴 「还没发生」用状态表达，不用空对象。**

   全仓契约里**没有任何**对象字段是 nullable（springdoc 不给 `$ref` 传播 `nullable`，
   codegen 只在 schema 自身带 `nullable` 时才出 `| null`）⇒ 跟着既有形状走：
   应付登记与收货放行**永远返回一个对象**，`status='NONE'` 表示「还没登记 / 还没放行」，
   金额是 null（**不是 0** —— 0 会被读成「这笔应付是 0 元」）。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V262__purchase_order_approval.sql`（新建）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderApprovalModels.java`（新建）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderApprovalService.java`（新建）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderApprovalController.java`（新建）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderApprovalFixture.java`（新建）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderApprovalIntegrationTest.java`（新建，26 例）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderApprovalTenancyIntegrationTest.java`（新建，3 例）

**契约**：`packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json`
（codegen 产物；**顺带漂移的 channel 契约已 revert**，那是另一条车道的未合并工作）

**前端**

- `apps/web/src/components/purchase/purchase-order-approval-model.ts`（新建）
- `apps/web/src/components/purchase/purchase-order-approval-workspace.tsx`（新建）
- `apps/web/src/components/purchase/purchase-order-approval-model.test.ts`（新建）
- `apps/web/src/app/purchase/approvals/page.tsx`（新建）
- `apps/web/src/app/api/trade/purchase-order-approval-allowlist.test.ts`（新建）
- `apps/web/src/lib/api-proxy.ts`（**新增一条 services[] 元素**，锚在 6.4.2.2 之后、5.3.2.2 之前）

**文档**：`docs/product/capability-blueprint/module-progress.md`（只改 6.3.1.3 那一行的「状态」「证据」两格）

## 后续待办

- **12 财务落地后**：应付登记与 12 的应付单据经**授权 API / event** 对齐；
  `effective_amount` 的确认动作届时由 12 驱动（当前由 trade 侧的显式确认端点承担，
  需要独立权限码 `trade.purchase.finance`）。
- **08 仓储落地后**：`purchase_order_receipt_release` 的放行事实要经**授权 API / event**
  交给 warehouse 侧执行实际收货；本模块只落事实。
- **变更单 / 红冲重下**：蓝图说「审批通过的采购单要改数量与单价走变更单」——
  变更单不在本模块内（本模块只锁不改），需要时由 6.3.1 或独立模块承载。
- **6.3.1.2 的能力声明**里「6.3.1.3 未交付」那句已过时 —— 那是 6.3.1.2 自己的文件，
  本车道**没有动**，由它自己更新。
- **本模块未改 6.3.1.1 / 6.3.1.2 的任何表结构**：唯一跨表增量是**新增**一个触发器。
