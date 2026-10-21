# 6.1.2.3 供应商资质（阶段 D6，L3 锚点 6.1.2）

> 车道：`feat/6-1-2-3`（worktree `integration/.worktrees/6.1.2.3`）
> 基线：集成头 `integration/module-dev`（7eef1875，已含 6.1.2.1 档口与货源池）

## 决策摘要

1. **模块归属 trade 服务**，迁移 `V239__supplier_qualification.sql`（调度者预分配）。
   五张新表，**零 ALTER 任何既有表** ⇒ 跨车道 DDL 回归面 = 0。
2. **不另建供应商主数据**。供应商主档在 catalog（party / SUPPLIER 角色），本库只存
   `supplier_role_id` + 供应商名快照；品牌同理只存 `brand_id` + 品牌名快照。
   跨服务一律经**授权只读 API**（`SupplierPartyQueryPort` / 新增 `BrandQueryPort` /
   新增 `QualificationSkuQueryPort`），**零跨库 FK / SQL / 视图**。
3. **资质类型词表与 6.1.1.2 附件资质同源**：闭集 = 6.1.1.2 的
   `BUSINESS_LICENSE / ISO9001 / ISO14001 / CE / FDA / COC / OTHER`（**一字不改**）
   ∪ 蓝图点名的 `ROHS / QUALITY_INSPECTION_REPORT / AUTHORIZATION_LETTER`。
   重名取值共用同一个码 —— 另造一套「本模块专属码」会让「这张 CE 是不是那张 CE」永远答不出来。
4. **审核状态是显式状态机**，六条边（DRAFT→PENDING→APPROVED→REVOKED，
   PENDING→REJECTED→PENDING，REVOKED→PENDING），**DB 触发器钉死**（直连改状态也会被拒），
   每次流转追加一条**只追加**的审核事件。
5. **到期状态不落库**：表里没有 `expiry_state` 列，由 SQL 按**数据库时钟 `now()`** 现算。
   「即将过期」窗口来自租户级策略（`warning_days`，默认 30 天，与 6.1.1.2 同口径）。
6. **阻断与提醒分成两件事**。蓝图加粗句「资质过期必须阻断相关商品的刊登与发货，不能只做提醒」
   的落点是**阻断判定端点**（`blocked` + 原因码 + 人话），不是提醒条数。
   「相关商品」的供应商取自 6.1.2.1 已交付的货源关联（**同库同服务只读**），
   不另建第二套「商品 ↔ 供应商」关系。
7. **判定单位是「要求」不是「单份证件」**：同供应商 + 同类型（品牌授权再加同品牌）归一组，
   组内有任意一份**当前有效**即满足 ⇒ **续期不误伤**（旧件过期但新件有效时不算阻断）。
   「即将过期」**不阻断**（仍在有效期内，拦下来是误伤）。
8. 前端另起分区 `/supply/qualifications`（不覆盖 6.1.2.1 已占的 `/supply/sources`）。

## 关键 trade-off

- **阻断的「必备资质缺失」要不要在本侧做？**
  蓝图把「认证缺失预警」归 16 合规（16.2.2.2 未交付）。本侧**做了**，
  因为一个连营业执照都没有的供应商被判「不阻断」是安全默认值写反；
  但它做成**租户级策略**（必备类型闭集，默认「营业执照」）而不是硬编码，
  16 合规交付后直接消费同一个判定即可。
- **商品维度的品牌维度不自动判**：catalog 的 SKU 读出口只给品牌**名**
  （`SkuDetail.brand` 是 String），没有品牌 id，按名字反查品牌是编事实。
  品牌维度改由 `suppliers/{supplierRoleId}/brands/{brandId}/blocking-decision` 精确判定。
  （实现过程中把 `brandId` 从查询参数改成**路径段** —— 查询参数上的空串在 Spring 绑 UUID 时会 400，
  而「没传品牌」与「传了个空品牌」是两件事。）
- **catalog 侧已有一份「附件资质」列表**（6.1.1.2，无审核状态、无品牌关联、不参与阻断）。
  本模块**不删不改它**，在 `capabilities` 里显式声明「本模块是参与阻断判定的唯一资质来源」，
  两份列表不自动同步，重复登记时以本模块的审核状态为准。
- **契约撞车**：codegen 在跨服务同名 DTO 上会中止（`ReviewInput` 与 catalog 撞名）。
  改名 `QualificationReviewInput`；其余 20 个通用名（`ReviewAction` / `ReviewSaved` /
  `ReminderRow` / `PolicyRow` / `DocumentInput` …）一律加 `Qualification` 前缀，
  避开 trade 与 catalog 的同名 DTO。
- **契约里的 catalog 漂移是基线既有问题**：重新生成后 `catalog.openapi.json` 有大面积 diff，
  但逐条比对后**类型集合完全一致**，只有排序 / 生成头 / 一处 `1` 与 `1.0` 的浮点渲染差异，
  与本模块无关（基线自带）。按协议提交 codegen 的原始输出，交给合并驱动器按类型名求并。

## 文件清单

**后端（trade）**

- `backend/services/trade/src/main/resources/db/migration/V239__supplier_qualification.sql`（迁移）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierQualificationModels.java`（传输模型 + 词表 + 状态机）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierQualificationService.java`（jOOQ 显式 SQL + 阻断判定）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierQualificationController.java`（17 个端点）
- `backend/services/trade/src/main/java/com/starcon/trade/BrandQueryPort.java`（**新增**，读 03 品牌）
- `backend/services/trade/src/main/java/com/starcon/trade/QualificationSkuQueryPort.java`（**新增**，读内部 SKU）
- `backend/services/trade/src/test/java/com/starcon/trade/SupplierQualificationIntegrationTest.java`（31 例）
- `backend/services/trade/src/test/java/com/starcon/trade/SupplierQualificationTenancyIntegrationTest.java`（3 例）

**前端**

- `apps/web/src/components/supply/qualifications-model.ts` + `.test.ts`（模型 + 口径单测 30 例）
- `apps/web/src/components/supply/qualifications.tsx`（工作区）
- `apps/web/src/app/supply/qualifications/page.tsx`（页面）
- `apps/web/src/app/api/trade/supplier-qualification-allowlist.test.ts`（白名单双向断言）
- `apps/web/src/lib/api-proxy.ts`（**新增一条** `services[]` 元素，既有条目一字未动）

**契约与台账**

- `packages/api-contracts/src/{trade,catalog}.openapi.json` + `src/contracts/{trade,catalog}.ts`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（**只改 6.1.2.3 那一行的状态与证据两格**）

## 验证

- L0：`backend && ./mvnw -q compile` 绿；`pnpm typecheck` 绿
- L1：`./mvnw -pl services/trade -am test -Dtest='SupplierQualification*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **34 例全绿**（31 + 3）
- 前端：`pnpm vitest run apps/web/src/components/supply apps/web/src/app/api/trade`
  → **348 例全绿**（48 文件 255 例 api-proxy 全量 allowlist 回归 + 本模块模型口径 30 例）

## 后续待办

- **16.2.2.2 / 16.2.1.2（16 合规）交付后**：把认证缺失预警与拦截执行接到本模块的阻断判定上
  （本模块只给 `blocked` + 原因码，**不执行拦截**，也不替它决定豁免）。
- **8.2.2.2（8 收货）交付后**：取用「质检报告」类资质作为质检依据。
- **04 刊登 / 发货链路**：接入 `blocking-decision` 端点后才真正「阻断」——
  在那之前本模块只是把判定算出来并暴露出来。
- **catalog 的 SKU 读出口若补上品牌 id**：商品维度可再自动带上品牌维度判定
  （现在只能按 (供应商, 品牌) 精确判定）。
- **trade 的页面权限码未在 identity 注册 page_resource**：trade 全服务既有缺口，本车道沿用兄弟模块做法未改 identity。
