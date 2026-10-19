# 2026-10-20 · 4.2.1.1 刊登编辑器

> 本次交付蓝图 L3「4.2.1 商品内容」下的 4.2.1.1：单商品的平台内容编辑表单。
> 车道 `feat/4-2-1-1`，基线 `integration/module-dev`（1bb270a0），Flyway 版本 **V194**（预分配）。

## 摘要

在 channel 服务交付刊登编辑器：**表单字段集由平台要求推导**（不是固定一套表单套所有平台）、
**草稿与线上物理分离**、内容按**平台 + 站点 + 语言**分别保存、**提交审核只是入队**、
多语言 Tab 真存多语言版本、校验结果真算并指到具体字段；「不知道」是一个状态（UNKNOWN /
DELEGATED + ownerModule），不回退成「没有约束」。4.2.2.1 刊登模板本批未交付，按既有约定
`available=false` + `ownerModule=4.2.2.1`，本模块不读它的表也不调它的端点。

## 决策摘要

### 1. 字段集来自**注册表 + 能力矩阵**，代码里没有平台分支

- 新建 `platform_listing_field_requirement`（**全局只读码表**，与 V1 的 `platform` /
  `marketplace` 同类）：逐行登记「哪个平台（+ 站点 / 类目）有哪些栏、必填与否、长度与条数上限」，
  随迁移播种 12 个平台 + Amazon 站点级差异（美站 200 字 / 日站 100 字 / 欧站 150 字）。
- 推导在 **SQL 里**做（`distinct on + order by` 的「就近覆盖」：站点+类目 > 站点 > 类目 > 平台），
  纯函数 `ListingEditorFieldRules.resolve` 只做状态合成。**两处各做一次就会漂**，所以覆盖顺序只在 SQL 里。
- 能力门控读 **2.1.1.2 已交付的 `platform.capabilities`**：true ⇒ SUPPORTED、false ⇒ UNSUPPORTED、
  **键缺失 ⇒ UNKNOWN**（不是 false、也不是「没有约束」）。集成测试直接改能力矩阵证明
  「平台能力矩阵变化时表单自动变化」。
- **反面做法（已避免）**：服务层按平台写 `if (platform == AMAZON)` 分支。那样「平台改版表单自动变」
  就是一句空话 —— 平台要求一变就得改代码。

### 2. 必填只在**约束已知**时才算阻断项

细则归别的模块（DELEGATED，如类目属性归 4.2.3.1）时，我们并不知道「到底要填什么」。
拿一条答不出来的必填去阻断提交，等于把用户挡在一扇打不开的门前 —— 那种情况只出**提醒**并写明归属模块。
集成测试据此断言：Amazon 草稿缺 `CATEGORY_ATTRIBUTES` 不阻断提交，缺 `DESCRIPTION`（约束已知、必填）阻断。

### 3. 草稿隔离沿用 4.2.1.2 的三层手法

① API 形状（唯一写路径是保存草稿，**没有改线上的端点**）② 事务标记
（`set_config('app.draft_only','true',true)`，事务局部）③ 物理分表（`listing_editor_draft`）。
DB 层新增触发器 `guard_listing_editor_live_content` 管 `listing.bullets` / `listing.images`，
**V177 的 `guard_listing_live_content` 一字未改** —— 两条触发器合起来覆盖全部四个线上内容列。

### 4. 对 `listing` 的 ALTER 压到最小：只加两列，且一律给 DEFAULT

`ADD COLUMN bullets text[] NOT NULL DEFAULT '{}'` 与 `ADD COLUMN images jsonb NOT NULL DEFAULT '[]'`：
- **DEFAULT 兜底** ⇒ 4.2.1.2 已交付的批量编辑 INSERT 路径与本批其它车道的 INSERT 都不会违约；
- **不 SET NOT NULL、不加 BEFORE INSERT 触发器**（DEFAULT 已在 INSERT 路径上兜底）；
- 新增 CHECK 只有结构性的 `jsonb_typeof(images)='array'`（新列上恒成立，不放宽也不收紧旧值域）。
- **只加这两列**：4.1.1.2 的 `listing_content_snapshot`（V175）已完整承载**逐语言**的线上内容，
  再加九个影子列 = 第二个真相来源。`listing` 这一层是**列表级、语言无关**的（与既有 `listing.title` 同口径）。

### 5. 提交审核 = 入队；内容一改，旧入队自动撤销

4.3.1.1 一键刊登未交付 ⇒ `listing_editor_submission` 冻结「入队那一刻的内容 + 校验证据」，
**页面上没有「直接上线」**。幂等键唯一（重复提交返回同一条），
「同一草稿只允许一条在队记录」用部分唯一索引表达。
**内容一改，之前入队的那份自动 CANCELLED** —— 它冻结的是旧内容，派发出去就是没人要的版本。

### 6. 4.2.2.1 刊登模板缺口按既有口径报出，不假装有

能力声明 `listing_template_apply.available=false` + `ownerModule=4.2.2.1` + 影响说明。
本模块**不读它的表也不调它的端点**（此刻都不存在）。另如实报出 4.3.1.1 派发、4.2.3.2 合规预检、
4.2.3.1 类目属性细则（在 catalog 侧 ⇒ DELEGATED）、3.4.2.2 文案库套用（未接入填充接口）四段缺口。

## 关键 trade-off

- **平台字段要求注册表做「全局码表」而不是租户业务表**：平台公布的刊登要求是**平台侧事实**
  （与 V1 `platform` / `marketplace` 同类），不是某个租户的资产。租户级的「批量编辑模板上限」在
  V177 `listing_bulk_template`（租户表）里，两者不是一回事，不合并。代价：本表不带 tenant_id、
  不参与 RLS（按 V1 同口径），内容更新靠发版。
- **草稿值用 jsonb + 具名 record 传输，而不是九列 typed 列**：字段集是**动态的**
  （平台要求变，字段就变）。传输层用具名 record（`EditorFieldValue` 四种值形状）避开
  `Map<String,Object>` 被 springdoc 推成空对象的老坑（9.6.1.3 已踩过）。
- **`listing` 上只补 bullets / images，不补其余七栏**：宁可让编辑器把其余内容放在草稿的 jsonb 里，
  也不在 `listing` 上造第二个真相来源。
- **变体维度在表单里只读**（`value_source=LISTING_VARIANT`）：变体到内部 SKU 的映射是 4.1.2.1 的域，
  编辑器改它会越界。

## 涉及文件

**后端（channel）**
- `backend/services/channel/src/main/resources/db/migration/V194__listing_editor.sql`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingEditorModels.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingEditorFieldRules.java`（新 · 纯规则）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingEditorService.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingEditorController.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/EditorSourceQueryPort.java`（新 · 03 只读端口）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingEditorIntegrationTest.java`（新 · 16 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingEditorTenancyIntegrationTest.java`（新 · 3 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingEditorFieldRulesTest.java`（新 · 6 例）

**前端**
- `apps/web/src/components/listing-editor/listing-editor-model.ts`（新）
- `apps/web/src/components/listing-editor/listing-editor-model.test.ts`（新 · 10 例）
- `apps/web/src/components/listing-editor/listing-editor-workspace.tsx`（新）
- `apps/web/src/app/listings/editor/page.tsx`（新 · `/listings/editor`）
- `apps/web/src/app/api/channel/listing-editor-allowlist.test.ts`（新 · 6 例）
- `apps/web/src/lib/api-proxy.ts`（**只新增一条 `listing-editor` 元素**，锚点：4.4.2.1 `listing-claims` 之前）

**契约与台账**
- `packages/api-contracts/src/contracts/channel.ts` + `src/channel.openapi.json`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（**只改 4.2.1.1 那一行**）

## 验证

- L0：`./mvnw -q compile` 绿；`TURBO_FORCE=true pnpm typecheck` 3/3 绿；`pnpm lint` 绿。
- L1：`./mvnw -pl services/channel -am test -Dtest='ListingEditor*'` → **42 例全绿**（25 例本模块 + 17 例 4.2.1.2 回归同批跑）。
- 回归：`-Dtest='ListingBulkEdit*'` **17 例全绿**；`-Dtest='Listing*'` 全族 **140 例全绿**（含 4.1.1.1 / 4.1.1.2 / 4.1.2.1 / 4.1.2.2 / 4.3.2.1 / 4.4.2.1）——
  确认对 `listing` 的 ALTER 没有打破任何已交付行为。
- 前端：`pnpm vitest run apps/web/src/components/listing-editor apps/web/src/app/api` → **80 文件 508 例全绿**
  （含 api-proxy **全量 allowlist 回归** 79 文件 497 例）。
- 契约：6 个服务 spec 全部由 `OpenApiContractTest` 导出后跑 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`，
  **其余五个服务的契约逐字节不变**（只 channel 变了）。

## 后续待办

- **4.1.1.2（Listing 详情）** 的内容对照右列目前仍声明「内部草稿归 4.2.1.1 未交付」，
  并入后可由该模块自己接上 `listing_editor_draft`（本车道**没有**改它的文件）。
- **4.2.1.2（批量编辑）** 的能力声明里「卖点 / 图片线上基线未接入」是因 `listing` 缺这两列而写的；
  本迁移补列后可由该模块自己复核（本车道**没有**改它的文件）。
- **4.2.1.2 的前端**尚未交付；它与本模块共用 `/listings/editor`，落地时按 `/listings/templates`
  的先例加成页签。
- **4.2.2.1 刊登模板**交付后，`listing_template_apply` 维度可从 `available=false` 翻成 true
  （本模块的 capability 声明里已写明归属与影响）。
- **4.2.3.2 合规预检**交付后，字段级校验可与违禁词 / 侵权扫描合并为一次发布前预检。

## 关联

- 蓝图：`docs/product/capability-blueprint/04-listing-and-content.md` §4.2.1.1（及卷首「边界」段）
- 相邻交付：4.2.1.2 批量编辑（V177）、4.1.1.2 Listing 详情（V175）、4.1.2.1 映射管理（V176）
- 上游：2.1.1.2 平台能力矩阵（`platform.capabilities`，channel V1）、3.4.1.1 媒体库（catalog V18）、
  3.1.2.2 SKU 详情（catalog V6）
