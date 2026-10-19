# 2026-10-20: 4.3.1.3 发布任务

## 摘要

在 channel 服务落地能力蓝图模块 4.3.1.3「发布任务」（阶段 D5，L3 锚点 4.3.1）：
**单次发布任务的进度视图** —— 步骤时间线、失败步骤与原因、可重试的步骤，
输出步骤时间线 + 错误明细 + 重试操作。

## 决策 / 结论

- **落地服务**：channel（刊登域既有模块全在 channel，照抄既有写法与命名）。
- **本模块是「进度视图」，不是另一个发布系统** —— 4.3.1.1 一键刊登（V196）已建
  `listing_publish_job` / `listing_publish_job_target`（= **一个发布任务**，4.3.1.1 输出句原话
  「N 个发布任务（每个目标一个）」）/ `listing_publish_job_step`（= 步骤时间线），
  4.3.1.2 批量发布（V183）建了 `publish_task`（= 队列里的一条发布任务）。
  本模块**只读**这四张表，**自己只写一张** `listing_publish_step_attempt`（每次重试的尝试留痕）。
  另建「发布任务」表会让「这个发布任务现在什么状态」有两个答案，而 4.3.1.1 / 4.3.1.2 读的是
  V196 / V183 那两张 ⇒ 分叉。
- **迁移**：`V200__listing_publish_task.sql`，**一张新表、零 ALTER 任何既有表**
  （跨车道 DDL 回归面 = 0）。命名空间 `listing_publish_step_attempt` —— 刻意避开
  `publish_task` / `listing_task` / `publish_job`（4.3.1.2 与 4.3.1.1 已有主，
  语义重复的表是最难查的 bug）。
- **端点**：`/channel/listing-publish-tasks/**`（6 个 path）。权限码复用 identity 既有
  `channel.operation.*`，identity 零改动。
- **前端**：`/listings/publish` **第三个页签**「发布任务（4.3.1.3）」，与 4.3.1.1 / 4.3.1.2
  并存，不新建同级页、不改别人已交付的 `components/listing-publish/`。api-proxy 新增
  **一条独立** `listing-publish-tasks/` 元素，插入锚点夹在 `one-click-publish/` 之后。

## 蓝图加粗句的落法

1. **「平台提交审核是异步的，任务在『审核中』结束，实际结果由 4.3.2.2 回流」** ——
   重试尝试的 `outcome` 只有 SUCCEEDED（**已重新提交平台**）/ FAILED（重试没成，带原因）
   两值，**没有「发布成功」这个取值**：提交成功 ≠ 平台审核通过。任务与步骤行的
   SUBMITTED / DISPATCHED 一律显示为「审核中（等平台回执）」，与「已成功」分开。
2. **「各步骤状态（内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核）」** ——
   蓝图六阶段与上游八个步骤键**不是一一对应**：4.3.1.1 没有为「平台创建」「变体创建」
   单独记步骤行。能力声明里**如实报出**（`stepKeys` 为空 + note 写明归属 4.3.1.1），
   **不凭空造两个步骤行**（那是假数据）；4.3.1.1 额外记的「建立映射」「平台结果」也如实标出。
   时间线排序按**蓝图阶段序**（同阶段内按 position）—— 纯按 position 排会把作业级的
   PRICE(position=0) 插到目标级的 TEMPLATE(position=0) 旁边，读起来像两步并行。
3. **「可重试的步骤」** —— 每个步骤都带 `retryable` + `retryBlockedReason`，
   **界面上不给点了没反应的按钮**。MEDIA / SUBMIT / RESULT 可重试（对应同一次平台发布请求，
   重试 = 把**同一份冻结内容**重新提交一次）；TEMPLATE / CONTENT / MAPPING 不可重试
   （内容快照写入后由触发器钉死不可变，重跑会改掉「当时发的是什么」）；
   PRICE / STOCK 不可重试（快照同样冻结在作业行上）；被平台驳回的任务不可重试
   （内容没改，重发只会再被驳回一次）。
4. **「失败步骤与原因」** —— 错误明细三来源分得开：任务级（目标行的失败 / 驳回原因）、
   步骤级（步骤的 `detail`）、尝试级（某一次重试没成的原因）。

## 关键 trade-off / 讨论

- **重试动作不另起炉灶（写入所有权）**：`listing_publish_job_target` / `listing_publish_job_step`
  的写入所有者是 4.3.1.1（状态机由 DB 触发器 `guard_publish_target_state` /
  `guard_publish_step_state` 钉死）。本模块**不写**这两张表 —— 重试**委托**给 4.3.1.1
  的既有重试路径执行（下游落成 2.5.1.1 的 `platform_operation`，`operation='PUBLISH'`），
  本模块只记「自己发起了第几次尝试、成没成」。这样「每张事实表只有一个写入所有者」成立，
  跨车道 DDL 回归面 = 0。
- **重试两档粒度**：**步骤级**（重试时间线上的某一步）+ **任务级**（整条任务重来）。
  任务级不是多余的：派发就失败时步骤全停在 PENDING、**一步都没失败**，步骤级重试无从谈起，
  任务级是唯一出路。
- **重试被拒也要留痕**：4.3.1.1 的重试失败时抛的是业务异常（不是 DB 错误），事务**没有**被
  abort，所以能安全补一条 `outcome=FAILED` 的留痕再返回 —— 「重试没成」本身就是要留痕的事实。
  而「你要求的东西不可能」（步骤不存在 / 步骤不可重试）是 422，不留痕（那是请求问题，不是事实）。
- **尝试序号用「任务级」而不是「任务+步骤级」**：任务级序号在唯一索引里不含 NULL 列，
  不会出现「NULL 与 NULL 不相等」导致唯一约束形同虚设的坑（任务级重试的 `step_key` 为 NULL）。
- **并发重试的序号竞争**：用 `ON CONFLICT DO NOTHING` + 重算重试，**不能**用「捕获唯一冲突再重试」——
  PG 里唯一冲突会把整个事务置为 aborted，救不回来。
- **两条上游聚成一张列表，但状态不揉**：每行带 `taskKind` 与**原始** `state`；
  归一化后的 `stage`（DRAFT→PENDING、SUBMITTED→IN_REVIEW、DISPATCHED→IN_REVIEW）
  只用于筛选与展示 —— 4.3.1.1 的 DRAFT 与 4.3.1.2 的 PENDING 不是一回事。
- **4.3.1.2 的队列任务没有步骤行**：如实报「本任务没有步骤时间线 + 归 4.3.1.2」，
  **不拿空数组冒充「已完成」**；它的重试也归 4.3.1.2 自己的批次重试入口。
- **平台侧发布留痕**：读 4.1.1.2 的 `listing_publish_record`（按店铺 + 平台商品 id 对齐），
  对不上就显示为空，**不拿别的商品的留痕凑数**。
- **步骤级重试为什么不能真的「重跑那一步」**：TEMPLATE / CONTENT / MAPPING 的工作结果就是
  **内容快照**，而快照写入后由 DB 触发器钉死不可变 —— 重跑会改掉「当时发的是什么」。
  MEDIA / SUBMIT / RESULT 则是同一次平台发布请求的三个侧面，重试其中任何一个都是
  「重发同一份冻结内容」。

## 涉及文件

**后端**

- `backend/services/channel/src/main/resources/db/migration/V200__listing_publish_task.sql`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPublishTaskModels.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPublishTaskService.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPublishTaskController.java`（新建）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishTaskIntegrationTest.java`（新建，14 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishTaskTenancyIntegrationTest.java`（新建，3 例）

**前端**

- `apps/web/src/components/listing-publish-task/listing-publish-task-model.ts`（新建）
- `apps/web/src/components/listing-publish-task/listing-publish-task-workspace.tsx`（新建）
- `apps/web/src/components/listing-publish-task/listing-publish-task-model.test.ts`（新建，25 例）
- `apps/web/src/app/listings/publish/page.tsx`（改：加第三个页签 + 把 4.3.1.3 从「尚未开发」改成「已交付」）
- `apps/web/src/app/api/channel/listing-publish-task-allowlist.test.ts`（新建，白名单双向断言）
- `apps/web/src/lib/api-proxy.ts`（改：新增**一条独立** `listing-publish-tasks/` 元素）

**契约 / 台账**

- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts`
  （codegen 产物：channel 纯新增 6 path / 11 schema / 0 删除）
- `docs/product/capability-blueprint/module-progress.md`（只改 4.3.1.3 那一行）

## 验证

- L0：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` ✅（3/3 包）
- L1：`./mvnw -pl services/channel -am test -Dtest='ListingPublishTask*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ✅ **17 例全绿**
  （Testcontainers 真实 PG16 + 强制 RLS；含**以 `channel_app` 角色直查**的多租户三断言）
- 回归：`-Dtest='ListingPublishJob*'` ✅ **21 例全绿**（确认 V200 未打破 V196）
- 前端：`pnpm vitest run apps/web/src/app/api/channel/listing-publish-task-allowlist.test.ts
  apps/web/src/components/listing-publish-task` ✅ **29 例**
- **api-proxy 全量 allowlist 回归**（改了共享白名单必跑）：`pnpm vitest run
  apps/web/src/app/api/channel apps/web/src/app/api/trade` ✅ **68 文件 / 348 例全绿**
- `pnpm lint` ✅（只有仓库既有的其它文件告警）
- 契约由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成，
  **未手改任何生成物**；其余五个服务的契约文件逐字节不变。

## 后续待办

- 4.3.1.1 的内容快照步骤（TEMPLATE / CONTENT / MAPPING）永远不可重试；要改内容后重发需要
  4.2.1.1 那边出新的入队记录 —— 本模块已在界面上写明「请改内容后重新发起一次发布」。
- 5.1.1.1（05 定价）交付后，`PRICE` 步骤的 BLOCKED 才能解开 —— 价格快照仍冻结在作业行上，
  改价要重新发起一次发布。
- 4.3.1.2 的队列任务没有步骤行，其进度与重试仍归 4.3.1.2 自己的批次队列。
- 4.3.2.2 平台状态回流把平台结果写回后，本模块的 `RESULT` 步骤与目标状态随之前移；
  本模块只读，不需要额外对接。

## 关联

- 蓝图原文：`docs/product/capability-blueprint/04-listing-and-content.md` §4.3.1.3
- 台账：`docs/product/capability-blueprint/module-progress.md` 的 4.3.1.3 行
- 上游：4.3.1.1 一键刊登（`listing_publish_job_target` / `listing_publish_job_step`，V196，只读）、
  4.3.1.2 批量发布（`publish_task`，V183，只读）、4.1.1.2 详情（`listing_publish_record`，V175，只读）
- 下游：2.5.1.1 回写任务（`platform_operation`，`operation='PUBLISH'`，经 4.3.1.1 的既有路径）
- 邻居模块：4.3.1.1 一键刊登（`/channel/one-click-publish`）、4.3.1.2 批量发布
  （`/channel/listing-publish`）—— 与本模块是**三条独立通道**，绝不揉进一个端点或一张表
