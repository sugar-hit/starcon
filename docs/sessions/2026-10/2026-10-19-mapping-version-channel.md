# 4.1.2.2 映射版本（channel）交付记录

- **模块**：4.1.2.2 映射版本（阶段 D5，L3 锚点 4.1.2）
- **车道**：`feat/4-1-2-2`，worktree `.worktrees/4.1.2.2`，基线 `0c2d4f97`
- **Flyway 版本号**：`V182__listing_mapping_version_scope.sql`（预分配，未自行找最大版本号）

## 决策摘要

### 1. 归属 channel，照 4.1.2.1 的先例

上游 4.1.2.1 已把「映射挂在**平台变体**上，变体是 channel 的事实」定为归属理由。
本模块的查询面全部落在同一批表上，沿用同一归属，**不另起风格**。

### 2. 上游已交付，本模块只做查询面（不重复建写入路径）

开工自检确认 `V176__listing_mapping.sql` 与 `ListingMapping{Controller,Models,Service}`
在集成头里。进一步读完发现：`listing_mapping_version` 表、半开区间语义
（`effective_from` / `effective_to`）、`change_reason`、`SaveMappingRequest.effectiveFrom`
（支持预约生效）**全部已由 4.1.2.1 交付**，连「改映射生成新版本」的写路径也在那边。

因此本模块**不写任何版本行**，职责收敛为蓝图的**输出句**：「版本列表 + 受影响订单范围查询」，
外加职责句里的「保留每个版本对应的商品范围」与「按版本回溯」。
控制器**全是 GET** —— API 层没有一个能改写历史版本的入口，这就是「改映射不影响已产生的订单」的形状之一。

### 3. 🔴 关键 trade-off：商品范围要「保留」，就不能只靠现查

蓝图用词是「**保留**每个版本对应的商品范围」。落地时有两个选择：

- **(a) 不建表**，靠 `version → mapping → listing` 现查商品范围；
- **(b) 建一张冻结快照表**（本车道选择）。

选 (b) 的理由是两个真实故障面：

1. `listing.title` / `external_item_id` 是平台侧事实，02 每次同步都改写它 —— 现查会让
   **版本 1 的历史被后来的同步改写**，这正是「保留」要防的事；
2. `listing_mapping.listing_id` 带 `ON DELETE CASCADE`，删 listing 会级联删掉 mapping 与版本行。
   V176 的触发器只挡**已关闭**版本的删除，**当前生效**的版本会跟着消失，届时
   「这笔订单当时用的是哪条映射、对应哪个平台商品」再也答不出来。

代价是快照与平台现状会有差异，所以快照列一律带 `_snapshot` 后缀，响应里
`titleSnapshot` 与 `currentTitle` **并排给出**，并给 `driftedFromPlatform` 标记 ——
「当时叫什么」与「现在叫什么」分开说，不合成一个字段。

**填充交给 DB 触发器**（沿用 V176 第五节已立的纪律）。漏写一行 scope 的症状是
「版本详情里商品范围是空的」而版本本身看起来完全正常 —— 最难发现的一种丢数据。
`UNIQUE(tenant_id, version_id)` 兜底，一条都没抓到直接 `RAISE 23503` 而不是静默跳过。
快照 `BEFORE UPDATE` 无条件拒改（含超级用户连接）。

### 4. 🔴 「受影响订单范围」按 `available=false` 归模块处理

实查集成头：trade 侧**没有任何**可供 channel 查询订单的已交付端点，
`order_line_component` 冻结也未落地。AGENTS.md 禁止跨服务 SQL / 外键 / 共享视图 / 直连对方库。

处理方式（按仓库既有惯例，见 `ReservationController` / `WarehouseFacilityController`）：

- 响应字段：`VersionAffectedOrders{ versionId, versionNo, available=false, ownerModule,
  note, prerequisite }`，写在 `GET /channel/mapping-versions/{id}/affected-orders`
  以及**详情内嵌的同一个对象**；
- `ownerModule` = `09 订单履约（trade 服务 · order_line_component 冻结）`；
- `note` 说明为什么答不出来，`prerequisite` 说明下游要先交付什么；
- capabilities 里 `affected_orders` / `order_to_version` 两个维度显式 `available=false`；
- **迁移里一张订单引用表都不建**，且响应当初设计里有个 `items` 字段。

### 5. 🔴 trade-off：删掉 `items`，而不是让它恒为 null

最初 `items` 设计成 `List<Object>` 恒为 `null`，理由是「空数组会被读成确实没有受影响订单」。
实际跑 codegen 时复现了 9.6.1.3 那个坑 —— springdoc 把 `List<Object>` 推成
`Array<{  }>` 空对象。改成**整个字段不要**，与 `UnmappedPage.backflowDelivered` 同一手法：
已交付的清单才配列表，未交付的维度只给标志 + 说明。判别「查不到」的是 `available` 这一个布尔。

### 6. 🔴 api-proxy 白名单：用独立前缀，不动上游那一行

4.1.2.1 已把 `listing-mappings/{uuid}/affected-orders` 显式登记成「不放行」并在自己的
白名单测试里断言 404（理由：归本模块）。若本模块往那条分支加子路径，就是两个车道改同一行
超长单行正则，合并必冲突。因此用独立前缀 `mapping-versions`，插入锚点固定在
4.1.1.2 `listing-details` 元素**之后**、`prefix: 'trade/'` **之前**。
另加一条单测专门钉「上游那条路径仍然 404」。

## 文件清单

**迁移（1）**
- `backend/services/channel/src/main/resources/db/migration/V182__listing_mapping_version_scope.sql`

**后端（3 新增，0 改动既有）**
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMappingVersionModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMappingVersionService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMappingVersionController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ListingMappingVersionIntegrationTest.java`（11 例）

**契约（codegen 产物，非手改）**
- `packages/api-contracts/src/channel.openapi.json`（纯新增 6 path / 8 schema / 0 删除）
- `packages/api-contracts/src/contracts/channel.ts`

**前端（4 新增 + 1 共享白名单新增一条元素）**
- `apps/web/src/components/mapping-version/mapping-version-model.ts`
- `apps/web/src/components/mapping-version/mapping-version-model.test.ts`（15 例）
- `apps/web/src/components/mapping-version/mapping-version-workspace.tsx`
- `apps/web/src/app/listings/mapping-versions/page.tsx`
- `apps/web/src/app/api/channel/mapping-version-allowlist.test.ts`（6 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条 `mapping-versions` 元素，既有 39 条一字未动**）

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（**只改 4.1.2.2 那一行**）
- 本文件

## 验证

- L0：`cd backend && ./mvnw -q compile` 绿；`pnpm typecheck` 三包绿；`pnpm lint` 绿
- L1：`./mvnw -pl services/channel -am test -Dtest='ListingMappingVersion*' ...`
  → **Tests run: 11, Failures: 0, Errors: 0**（真实 PostgreSQL 16 + Testcontainers）
- 前端：`mapping-version-model.test.ts` 15 例绿；`mapping-version-allowlist.test.ts` 6 例绿
- 白名单全量回归：`apps/web/src/app/api/trade` **213 例**绿；`apps/web/src/app/api/channel` **66 例**绿
- **共享白名单三方校验**（以 merge base `0c2d4f97` 为裁判）：base 39 / head 39 / now 40；
  `addedByMe` 恰好 1 条（`mapping-versions`）、`removedByMe` 0、`collateralDamage_vs_base` 0、
  重复项 0、集成头 7 个服务前缀 7/7 保留 → **verdict OK**

## 后续待办

1. **09 订单（trade）侧**：在 `order_line_component` 冻结 `listing_mapping_version.id`，
   并提供「按映射版本查询受影响订单」的授权端点。落地后本模块的
   `GET /{id}/affected-orders` 可直接换成订单清单，版本列表与商品范围口径不需要改
   （`available` 翻 true 即可）。**并入前不要在 channel 侧建订单引用表**。
2. **4.1.2.3 套装组件映射**：组件明细与可售性校验，不要在本模块的表里抢写入所有权。
3. 上游 4.1.2.1 台账里「已知缺口 ①：按版本回溯受影响订单（4.1.2.2）未交付」一句已过时，
   本车道交付后应由调度器在并入时统一刷新（本车道未改他人行）。

## 踩坑记录（留给下一条车道）

- `sql.fetchOne(query, Long.class, binds)` 这个重载不存在；走 `fetchOne(sql, binds)` 再
  `row.get(0, Long.class)`。
- `Record.get("x")` 在本仓 jOOQ 版本返回 `Object`，**一律**写 `get("x", String.class)`。
  用正则批量补类型时要小心 `previous.get("internal_sku_id")` 被补成 `String.class`。
- 上游 `ListingMappingService.save` 对「改既有映射」**同样要求** `listingVariantId`（`@NotNull`），
  传 null 会 422。
- 上游**拒绝预约生效**（未来 `effectiveFrom`）：因为那会让当前版本失去「当前生效」身份、
  订单解析出现没有版本可用的时间窗。测试里的版本时间轴必须**整体落在过去**。
