# 2026-10-20: 4.3.1.1 一键刊登

## 摘要

在 channel 服务落地能力蓝图模块 4.3.1.1「一键刊登」（阶段 D5，L3 锚点 4.3.1）：
一个源 SKU 一次性发布到多个目标店铺/站点，产出 N 个发布任务（每个目标一个）与各任务的
进度与结果。跨平台发布**不是一个事务**（某个平台失败不影响其他平台，各自独立重试）。

## 决策 / 结论

- **落地服务**：channel（刊登域既有模块全在 channel，照抄既有写法与命名）。
- **迁移**：`V196__listing_publish_job.sql`，**三张新表、零 ALTER 任何既有表**。
  命名空间 `listing_publish_job` / `listing_publish_job_target` / `listing_publish_job_step` ——
  刻意不用 `publish_*` / `job` / `task` 裸名（4.3.1.2 批量发布已占用
  `listing_publish_batch` / `listing_publish_task` 与 `/channel/listing-publish` 前缀）。
- **端点**：`/channel/one-click-publish/**`（11 个 path）。权限码复用 identity 既有
  `channel.operation.*`，identity 零改动。
- **前端**：`/listings/publish` **加页签**与 4.3.1.2 批量发布工作区并存，不新建同级页、
  不改别人已交付的 `components/listing-publish/`。api-proxy 新增**一条独立** `one-click-publish/`
  元素，插入锚点夹在既有 `listing-migrations/` 之后、`trade/` 之前。

## 蓝图加粗句的三条落法

1. **跨平台发布不是一个事务** —— 三处结构：① 粒度（1 作业 + N 目标行，各有各的
   state/attempts/failure_reason/platform_operation_id）；② 不变量（触发器钉死目标状态机，
   作业表上**没有**「整体成功/失败」列，总体结论每次从目标行聚合）；③ 回执（每目标一条
   `platform_operation`，幂等键按目标拆开）。派发时**每目标一个 SAVEPOINT**：一个目标失败 →
   回滚到它自己的 savepoint → 只改那一行，其余照旧。
2. **幂等** —— `UNIQUE(tenant_id, idempotency_key)` + 先查后写 + `ON CONFLICT DO NOTHING` 兜底并发。
3. **内容快照** —— 目标行 `content_snapshot`（按平台+站点+语言各自的标题/描述/卖点/图片/类目路径）
   + 作业行的价格/可售量快照；触发器钉死**写入后不可改**（重试也不许改快照）。

## 关键 trade-off / 讨论

- **内容来源只有两条真实路径**：① `EDITOR_SUBMISSION` 读 4.2.1.1 入队时冻结的快照（**不重新
  求值** —— 重新求值得到的是「现在长什么样」而不是「当时发的是什么」）；② `TEMPLATE` 读 4.2.2.1
  的**取值规则**对源 SKU 实时求值（模板存规则不存值 ⇒ 换图换属性后求出新值）。
  4.2.2.1 的 `apply` 端点要求 `listingId`（套用线上商品），一键刊登的目标**还没有**线上商品，
  所以本模块只读它的规则表做发布时求值，**不写**它的 `listing_template_apply` / `listing_content_draft`
  （那是 4.2.2.1 的写入所有权）。
- **取价（5.1.1.1 未交付）**：不拒绝整个请求，也不编价。`PRICING_SERVICE` 落成
  `price_state='UNAVAILABLE'` + 归属模块 + 原因，`price_amount IS NULL`（**不是 0**），
  步骤 `PRICE` 标 `BLOCKED`；`MANUAL` 走运营手填，金额 `NUMERIC(19,4)` 冻结在作业行。
  取价是**作业级**的：一次发布给所有目标用同一个价（各目标分别定价需要 05）。
- **库存读不到 = 报错**：warehouse 不可达时建作业直接失败，**绝不**把可售量折成 0
  （`SellableQuantityQueryPort` 已有的纪律，本模块照抄）。
- **平台字段要求只校长度/条数**：必填缺失由编辑器（入队前校验）与模板的必填规则负责，
  本模块不重复判定，免得两个口径打架。
- **映射的写入所有权**：`listing_mapping`（4.1.2.1）由它自己写；本模块只记录发布任务级的
  「内部 SKU ↔ 目标店铺/站点」绑定 + 平台商品 id 回填，能力声明里写明这条边界。
- **合规预检（4.2.3.2，与本车道并行、此刻还不存在）**：不读它的表、不调它的端点、不写死它的
  表名/端点名；只在能力声明里 `available=false` + `ownerModule=4.2.3.2` + 影响说明。

## 涉及文件

**后端**

- `backend/services/channel/src/main/resources/db/migration/V196__listing_publish_job.sql`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPublishJobModels.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPublishJobService.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPublishContentResolver.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPublishJobController.java`（新建）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java`（新建，18 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobTenancyIntegrationTest.java`（新建，3 例）

**前端**

- `apps/web/src/components/one-click-publish/one-click-publish-model.ts`（新建）
- `apps/web/src/components/one-click-publish/one-click-publish-workspace.tsx`（新建）
- `apps/web/src/components/one-click-publish/one-click-publish-model.test.ts`（新建，23 例）
- `apps/web/src/app/listings/publish/page.tsx`（改：加页签 + 更新「4.3.1.1 尚未开发」的注释）
- `apps/web/src/app/api/channel/one-click-publish-allowlist.test.ts`（新建，白名单双向断言）
- `apps/web/src/lib/api-proxy.ts`（改：新增**一条独立** `one-click-publish/` 元素）

**契约 / 台账**

- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts`
  （codegen 产物：channel 纯新增 11 path / 17 schema / 0 删除）
- `docs/product/capability-blueprint/module-progress.md`（只改 4.3.1.1 那一行）

## 验证

- L0：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` ✅（3/3 包）
- L1：`./mvnw -pl services/channel -am test -Dtest='ListingPublishJob*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ✅ **21 例全绿**
- 前端：`pnpm vitest run apps/web/src/components/one-click-publish/one-click-publish-model.test.ts
  apps/web/src/app/api/channel/one-click-publish-allowlist.test.ts` ✅ 23 例
- **api-proxy 全量 allowlist 回归**（改了共享白名单必跑）：`pnpm vitest run
  apps/web/src/app/api/trade apps/web/src/app/api/channel` ✅ **64 文件 / 327 例全绿**
- `pnpm --filter @starcon/web lint` ✅（只有仓库既有的其它文件告警）
- 契约由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成，
  **未手改任何生成物**；其余五个服务的契约文件逐字节不变。

## 后续待办

- 5.1.1.1 交付后，`PRICING_SERVICE` 才能真正取价；在此之前发布任务不带平台售价。
- 4.2.3.2 交付后，阻断项校验可从「字段级」升级到「内容合规级」；本模块已预留能力维度。
- 4.3.1.3 交付后，单次发布任务的步骤时间线页面可由本模块落的 `listing_publish_job_step` 支撑。
- 4.1.2.1 在平台商品 id 回填后建档 `listing_mapping`；本模块只提供绑定关系。
- 平台适配器就位后，`POST /targets/{uuid}/report` 由适配器（或 2.5.1.2 核验通道）调用回报结果。

## 关联

- 蓝图原文：`docs/product/capability-blueprint/04-listing-and-content.md` §4.3.1.1
- 台账：`docs/product/capability-blueprint/module-progress.md` 的 4.3.1.1 行
- 上游：4.2.1.1 刊登编辑器（`listing_editor_submission`，V194）、4.2.2.1 刊登模板（V195）、
  7.1.1.3 库存可售量（warehouse，只读）、2.1.1.2 平台能力矩阵
- 下游：2.5.1.1 回写任务（`platform_operation`，`operation='PUBLISH'`）
- 邻居模块：4.3.1.2 批量发布（`/channel/listing-publish`，与本模块**两条独立通道**）
