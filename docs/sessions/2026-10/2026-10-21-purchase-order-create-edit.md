# 2026-10-21: 6.3.1.2 采购单创建与编辑

## 摘要

交付能力蓝图 **6.3.1.2 采购单创建与编辑**（trade 服务，阶段 D6，L3 锚点 6.3.1）。

蓝图职责：「创建/编辑采购单：选择供应商、添加 SKU 行（带出报价与库存快照）、填写数量/采购单位/
单价/币种、约定交期与发货地址、备注与附件。支持草稿与提交。」
输入「表单数据；`expected_version`；SKU 与供应商报价快照。」
输出「采购单（草稿/已提交）；金额与税额计算结果。」
关系：上游 = 6.2.2.2、3.6.2 报价；下游 = 6.3.1.3 审批、8 收货、12 应付。
蓝图加粗句：「**单价、汇率、单位换算在提交时冻结快照**，之后改报价不影响已提交采购单的成本核算。」

本车道交付：Flyway 迁移 `V258`（五张新表 + 3 个函数 + 1 个触发器）、trade 服务 8 个端点
`/trade/purchase-order-editor/**`（jOOQ 显式 SQL）、两个前端页面
（`/purchase/orders/new` 与 `/purchase/orders/{id}/edit`）、契约重生成、台账与本会话日志。

**开工前的依赖自检（10 分钟）全部通过**：V254 三张表、V253 合并草稿、
`GET /catalog/supplier-quotes/effective{,/resolve}`、`GET /catalog/skus/{id}`、
`GET /catalog/exchange-rates{,/latest,/functional-currencies}`、
`GET /catalog/supply/vendors{,/{roleId}}`、`GET /catalog/media/{id}`、
`GET /warehouse/warehouses`、`GET /warehouse/legal-entities`、
`GET /warehouse/inventory/sellable/skus` 在集成头里都真实存在。

## 决策 / 结论

1. **🔴 三个口径（任务书点名要写清的）**

   ① **草稿转换时来源记在哪一列**：
   `purchase_order_line.source_conversion_id` **绝不**用来记 6.2.2.2 的合并草稿 id ——
   那一列指向的是 **6.2.2.1 的转单事实**（`purchase_request_conversion`），而且带
   `UNIQUE(tenant_id, source_conversion_id)`；塞草稿 id 会把两种语义混成一列，还可能误撞唯一键。
   ⇒ 草稿来源记在**本模块自己的** `purchase_order_draft_source`（`source_draft_id` /
   `source_draft_no` / `source_batch_id` / `source_batch_no` / `source_request_ids`），
   转换时这些行的 `source_conversion_id` 保持 **NULL**。
   头行的 `source_type` 用 **`'PURCHASE_REQUEST_CONVERSION'`**（合并草稿背后就是 6.2.2.1 的
   申请行，语义上确实是「从采购申请转来的单」），手工建单用 **`'MANUAL'`**
   （V254 明写「留给 6.3.1.2 手工建单」）。

   ② **汇率快照取不到时的处理**：**就是没有**（NULL），**绝不**按 1:1 或固定汇率填一个数进去。
   提交那一刻取 catalog 汇率台账的「发生日当天或之前最新的一条」；取不到时
   `fx_status='UNAVAILABLE'`、汇率与本位币折算金额都是 null，原币种总额照报。
   理由：一个假汇率会让「跨币种合计」变成一个**看起来能对账、其实对不上的数**。
   另外**取不到汇率不拒绝提交** —— 采购单是对供应商的承诺，不该被一个「算合计用」的辅助能力卡住。
   顺带钉死：汇率快照在**提交**那一刻冻结（蓝图加粗句），草稿期间一律 NULL。

   ③ **税额为什么不能算**：税率的**权威在 12 财务**，而本仓 `backend/services/` 下只有
   identity / catalog / channel / crm / trade / warehouse，**没有 finance 服务**
   ⇒ 本模块**不造税率表**、不猜税率、不跨库读 finance。
   ⇒ `tax_rate` / `tax_amount` 恒为 **NULL**，`tax_status='NOT_AVAILABLE'`，
   能力声明里写清归属模块（12 财务）。本模块只算**本位币折算快照**（有汇率就算折算，不算税）。

2. **🔴 本模块与 6.3.1.1（锚点）的边界**：采购单这个聚合（头 + 行 + 留痕）归 6.3.1.1 所有
   （V254 的三张表）。V258 **零 ALTER、零 DROP、零改既有触发器**，
   只新建本模块自己的五张表：行级快照 / 单级提交快照 / 发货地址 / 附件 / 草稿来源。
   **可编辑的行集合直接用 6.3.1.1 的 `purchase_order_line`** ——
   这样草稿在 6.3.1.1 的列表上立刻就有行数与金额，不会显示成一张空单。

3. **🔴 唯一的两处跨模块补充（已写进迁移文件头，必须让评审看到）**
   V254 收回了运行角色对 `purchase_order` / `purchase_order_line` 的 DELETE 权限，
   那条保护针对的是「**删掉一张采购单**」（会级联抹掉留痕）；本模块要的是另一种删除：
   **草稿阶段删掉一行刚加错的 SKU**。⇒ 只做两件事，都不改 6.3.1.1 的表结构：
   (1) `GRANT DELETE ON purchase_order_line`（**只放行行表，单头仍不可删** ⇒
   6.3.1.1 的「采购单不提供 DELETE」保护原封不动）；
   (2) 新增 `guard_purchase_order_line_delete_guard` 触发器，钉死**只有草稿能删行**
   （V254 现有的 `guard_purchase_order_line_amount` 只挂 INSERT OR UPDATE，管不到 DELETE）。
   两处都是纯增量，6.3.1.1 的行为与测试零变化。

4. **🔴 命名空间刻意分开**：6.3.1.1 已占 `/trade/purchase-orders`（列表形状），
   本模块走**自有首段字面量** `/trade/purchase-order-editor`。
   理由：`apps/web/src/lib/api-proxy.ts` 是多车道共用的累积文件，
   每条车道往同一支超长正则里并分支会让 git 必然判冲突（结构性，retry 不会自愈）；
   分成两个命名空间之后，**兄弟模块交付时不必去改对方已登记的分支**（跨车道回归面 = 0），
   而且 6.3.1.1 的白名单守卫测试**一行都不用改**（它断言 `purchase-orders/{uuid}` 是 404，
   本模块走的是 `purchase-order-editor/**`，两段前缀在第 14 个字符上就分开了）。
   前端路由同样分开：列表 `/purchase/orders`，建单 `/purchase/orders/new`，
   编辑 `/purchase/orders/{id}/edit`。

5. **🔴 歧义报价不替用户挑**：带出报价用 catalog 的 `/effective`（非严格），
   并列候选**逐条列出来**且 `selected=null`；提交那一刻用
   `/effective/resolve`（严格取价），歧义直接 **409**。
   理由（catalog 自己的注释）：「报错让用户选」必须发生在建单的那一刻 ——
   采购单不能带着一条「系统随便选的价」发出去，而一个 200 + `selected=null`
   很容易被前端误读成「没报价」。

6. **🔴 单位换算不猜成 1**：采购单位的箱规（1 个采购单位 = 多少基础单位）来自 catalog 的
   3.1.2.4；取不到就是 `conversion_status='UNKNOWN'` + NULL。
   猜成 1 会让订量差一个数量级（1 箱 = 12 件时差 12 倍）。

7. **编辑是整体替换语义**：PUT 提交的是这一版的**完整表单**，少传一行 = 删掉那一行
   （不是「保持原样」）。**所有更新都带 `expected_version`**，不匹配 409。
   已提交的采购单不可再改（6.3.1.3 口径：改数量与单价要走变更单或红冲重下）。

8. **可编辑性由服务端从已认证身份与角色授权推导**（ALL / DEPARTMENT / SELF），
   **不接受请求参数传入**；租户来自已认证成员关系，**不信任 `X-Tenant-Id` 头**。

9. **复用既有端口，不造第二个 catalog 端口**：`ReplenishmentCatalogPort` 只**新增**
   `effective` / `resolve` 两个方法与三个 record（既有 record 一字未动，
   以免 6.2.1.2 的调用点与测试编译不过）；汇率与法人本位币复用 `PurchaseAnalyticsCatalogPort`；
   库存复用 `StockoutInventoryPort`（新增单 SKU 读）；目标仓与法人复用
   `PurchasePlanWarehousePort`；供应商复用 `SupplierPartyQueryPort`
   （**新增 roleId → partyId 换算**：trade 全线用 SUPPLIER **角色 id** 寻址供应商，
   而报价台账用**主体 id**）；附件元数据新建 `PurchaseOrderMediaPort`（catalog 媒体库）。

## 关键 trade-off

- **「删行」要不要放行**：放行（方案 A）需要在 6.3.1.1 的表上补一个 GRANT + 一个触发器；
  不放行（方案 B）则草稿里加错的行删不掉，「编辑」就不完整。
  **选 A**，因为 B 会让模块职责范围内的标准能力缺一块（任务书禁止半成品），
  而 A 的两处补充都是**纯增量**、不碰任何既有列 / 约束 / 触发器 / 端点，
  且把 V254 原本只覆盖 INSERT/UPDATE 的防线补全到 DELETE。
- **草稿的行放哪儿**：放 6.3.1.1 的 `purchase_order_line`（而不是本模块另建一张行表）
  ⇒ 6.3.1.1 的列表上草稿立刻有行数与金额；代价就是上面的两处补充。
- **编辑中的总额在页面上算不算**：算，但**必须标成「本页试算」** ——
  真正入账的金额由数据库触发器算，页面不能拿试算冒充入账数。
  缺数量或单价时试算给 null，不给一个看起来完整的数。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V258__purchase_order_create_edit.sql`（新）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderEditModels.java`（新）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderEditService.java`（新）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderEditController.java`（新）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseOrderMediaPort.java`（新）
- `backend/services/trade/src/main/java/com/starcon/trade/ReplenishmentCatalogPort.java`（**只新增**三个 record 与两个方法）
- `backend/services/trade/src/main/java/com/starcon/trade/StockoutInventoryPort.java`（**只新增** `sellableForSku`）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierPartyQueryPort.java`（**只新增** `partyId`）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderEditFixture.java`（新）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderEditIntegrationTest.java`（新，16 例）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseOrderEditTenancyIntegrationTest.java`（新，4 例）

**前端**

- `apps/web/src/components/purchase/purchase-order-edit-model.ts`（新）
- `apps/web/src/components/purchase/purchase-order-edit-model.test.ts`（新）
- `apps/web/src/components/purchase/purchase-order-edit-workspace.tsx`（新）
- `apps/web/src/app/purchase/orders/new/page.tsx`（新）
- `apps/web/src/app/purchase/orders/[id]/edit/page.tsx`（新）
- `apps/web/src/app/api/trade/purchase-order-edit-allowlist.test.ts`（新）
- `apps/web/src/lib/api-proxy.ts`（**只新增**一条 services[] 元素，既有条目一字未动）

**契约 / 台账 / 日志**

- `packages/api-contracts/src/contracts/trade.ts`（codegen 产物，27 个 PurchaseOrderEdit* 类型）
- `packages/api-contracts/src/trade.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（**只改 6.3.1.2 那一行**的状态与证据两格）
- `docs/sessions/2026-10/2026-10-21-purchase-order-create-edit.md`（本文件）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` ✅（0 error）
- L1 模块闸：`./mvnw -pl services/trade -am test -Dtest='PurchaseOrderEdit*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ✅
  **20 例全绿**（16 行为 + 4 租户），实跑 Testcontainers PostgreSQL 16，连接池压到 1
- 前端：`pnpm vitest run apps/web/src/app/api/trade apps/web/src/components/purchase/purchase-order-edit-model.test.ts`
  ✅ **334 例全绿**（含 6.3.1.1 与其它车道的**全量 allowlist 回归** ——
  改了 api-proxy.ts 所以跑了整组）
- 前端 lint：`pnpm --filter @starcon/web lint` ✅ 0 error；`prettier --check` ✅

## 后续待办

1. **6.3.1.3 采购单审批**（未交付）：`purchase_order.status` 已有 PENDING_APPROVAL / APPROVED /
   REJECTED 三个值与合法的状态机跃迁，本模块只把单推到 PENDING_APPROVAL，
   **没有审批留痕**（谁审的、依据哪条规则、什么时候审的）—— 能力声明里如实标
   `available=false` + `ownerModule=6.3.1.3`。
2. **12 财务**（finance 服务不存在）：应付生成与税额计算。交付后本模块的
   `tax_status` 与 `tax_amount` 才能从 `NOT_AVAILABLE` 变成真值。
3. **6.2.2.2 的合并草稿 UI**：本模块的 `/from-drafts` 端点已就绪，但 6.2.2.2 的页面上
   还没有「转成采购单」的入口（那是 6.2.2.2 自己的页面改动，不在本车道范围）。
4. **发货地址**：本仓的地址台账（3.6.3.1）在 catalog 独立库，而 warehouse 的仓档案里
   没有街道地址字段 ⇒ 本模块把地址当**表单内容**处理。将来接入地址台账时，
   按「同一形状」加一个来源列即可，表结构与查询都不用改。
5. **台账同步**：`node tools/module-progress/sync.mjs --check` 在集成头里**本来就不通过**
   （与本车道无关：蓝图与台账在集成头里已经不一致）。本车道按纪律**只改了自己那一行**，
   没有重排整表。
