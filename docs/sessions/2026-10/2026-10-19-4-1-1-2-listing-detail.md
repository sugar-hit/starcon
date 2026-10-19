# 2026-10-19 4.1.1.2 Listing 详情

能力蓝图模块 **4.1.1.2「Listing 详情」**（阶段 D5，L3 锚点 4.1.1）。车道 `.worktrees/4.1.1.2`，分支 `feat/4.1.1.2`。

## 决策摘要

### 1. 差异对照做得出来，但**只有一半双边**

蓝图原句是「平台上卖的价格/库存**和我系统里设的不一样**」—— 这是一句双边断言。实测集成头：

| 侧 | 有没有 | 来源 |
| --- | --- | --- |
| 平台侧价格 | ❌ | 卷 05 定价 0/19 未交付；V171 刻意没建价格列 |
| 平台侧可售状态 / 量 | ✅ | `listing.platform_stock_state` / `platform_stock_qty`（02 同步 / 4.3.2.2 回流） |
| 系统侧价格 | ❌ | 05 未交付 |
| 系统侧可售量 | ❌ | 07 库存未交付 |
| 内部草稿内容 | ❌ | 4.2.1.1 未交付 |

⇒ **售价差异与内部可售量差异本批次不可计算**。做法沿用 4.1.1.1 的同一套诚实纪律：

- **不建影子价格 / 库存列**。理由与 V171 第二节逐字一致：影子列 = 第二个事实来源，05 上线后两个数字迟早对不上，而没人说得清信哪个 —— 比「没有价格」危险得多。
- 差异表把结局分成 **六种**：`DIFFERENT / SAME / PLATFORM_ONLY / INTERNAL_ONLY / UNAVAILABLE / UNKNOWN`。核心是**「只有一侧有值」绝不写成 `SAME`** —— 那会让运营以为「平台上卖 0 块、系统里也是 0，一致」。
- 未接入的行**照样出现在表里**（`available=false` + `ownerModule` + `impact`），不隐藏。隐藏未接入的行等于隐藏缺口。

### 2. 三条真能算出来的差异（这是本模块的实际交付价值）

1. **平台回报可售量 vs 系统回写登记簿快照** —— 两个真实数字。`writeback_target.current_stock/current_price`（V169 · 2.5.2.1 已交付）的语义在 V169 第三节写死：改价 payload 的 `previousPrice` 取的就是它 ⇒ 它是**平台上当前在售的值**的快照。测试里把登记值从 50 改成 3，状态真的从 `DIFFERENT` 翻成 `SAME`，证明是真比较不是写死的结论。
2. **平台状态 vs 平台可售状态** —— `ON_SALE` + `OUT_OF_STOCK` = 平台自己前后不一致，漏单最常见的来源。
3. **平台变体 vs 内部 SKU 映射** —— 变体条数 ≠ 已映射条数即 `DIFFERENT`；**未登记变体判 `UNKNOWN` 而不是 `SAME`**（没有变体不是「全部已映射」）。

🔴 但第 1 条**不等于**蓝图说的内部可售量 —— 那一侧归 07 库存，未接入。diff 行的 `note` 里写明了这条边界。

### 3. 平台操作记录直读 V2，不复制不加索引

`platform_operation`（2.5.1.1）自带 `payload`，2.5.2.1 写的时候把 `targetType` / `externalRef` 原文放进了 payload，于是「本商品的操作」= `shop_id` 相等 + payload 两键命中，而 `UNIQUE(tenant_id, shop_id, external_item_id)`（V171）保证了它不会撞到别的商品。

**直接单条派发（不经 writeback 目标）的操作不带这两个键，无法归属到具体商品** —— 那是店铺级事实，本模块不猜也不硬塞，`capabilities.platformOperationNote` 里写明了这个完整性边界。

对 V2 / V171 / V169 **零 ALTER、零索引**（加索引也是替别的车道改 DDL 面，V169 第二节同纪律）。

### 4. 内容对照：只做左列，右列明写未接入

V171 的 `listing` 只有 title / category_path / platform_category_id，撑不起一张逐字段对照表 ⇒ 本模块建 `listing_content_snapshot` 存**平台侧**内容（要点 / 描述 / 图片 / 视频 / A+ / 类目属性 / 合规声明）。

- **右列（内部草稿）恒为 `internalKnown=false` + `internalValue=null`**，不预建草稿表 —— 一张没有写入方的空表会被当成「草稿系统已交付」，比明写未接入危险得多。
- 快照**追加写**而不是就地覆盖：`listing_publish_record` 要引用「那次发布用的是哪份内容」，覆盖会让发布历史指向一份已被改掉的快照。「当前」那一份由 `is_current` + 唯一部分索引钉住，DB 触发器自动降级旧快照。

### 5. 路径前缀：`/channel/listing-details`，不碰 `listings/{id}`

4.1.1.1 交付时把 `listings/{uuid}/diff` 显式登记成**不放行**（那是本模块的形状）、把 `listings/{id}` 定义成「列表行点进去的最小载荷」。在同一棵路径树上开子资源 ⇒ 要同时改它的白名单与它的拒绝清单，而那份清单是已交付车道的成果。

另起独立前缀后：4.1.1.1 的 `listing-allowlist.test.ts` **一字未动且仍全绿**，本车道另立 `listing-detail-allowlist.test.ts` 双向断言，且新增一条用例钉住「4.1.1.1 显式拒绝的形状仍然被拒」。

### 6. 发布历史 vs 状态轨迹：两张表

`listing_status_event`（V171 触发器派生）记**状态怎么变的**；`listing_publish_record`（V175）记**发布动作发生了什么**。状态变了不等于发过一次布 —— 合成一张表，「点了发布但平台拒收」这条事实在本模块就存不下来了。

## 关键 trade-off

| 取舍 | 选择 | 代价 |
| --- | --- | --- |
| 价格缺口 | 显式 `UNAVAILABLE`，**顺带把 V169 的登记簿快照如实放进平台侧那一列**并带来源与局限标注 | 多一次 LEFT 查询；但这是全库唯一一处真实的平台价数字，藏起来反而更少信息 |
| 内部草稿 | 只做平台侧，右列整列标未接入 | 对照表右列空着，界面难看 —— 但「假装有草稿」的代价更高 |
| 写 `writeback_target` | **不写**，只读 | 少了「一键登记为回写目标」，但那张表的唯一写入所有者是 2.5.2.1（每张事实表一个写入所有者） |
| 详情前缀 | 另起 `listing-details` | 与列表不是同一棵资源树，前端两处定义；换来的是不动已交付文件 |
| 列表行下钻链接 | **不加** | 它在 4.1.1.1 的 `listing-workspace.tsx` 里（本次禁止改已交付车道文件）；本页自带「返回列表」，已在台账里记为已知后续 |

## 文件清单

**迁移**
- `backend/services/channel/src/main/resources/db/migration/V175__listing_detail.sql`

**后端**
- `backend/services/channel/src/main/java/com/starcon/channel/ListingDetailModels.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingDetailService.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingDetailController.java`（新）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingDetailIntegrationTest.java`（新，15 例）

**前端**
- `apps/web/src/components/listing-detail/listing-detail-model.ts`（新）
- `apps/web/src/components/listing-detail/listing-detail-model.test.ts`（新，14 例）
- `apps/web/src/components/listing-detail/listing-detail-workspace.tsx`（新）
- `apps/web/src/app/listings/[id]/page.tsx`（新路由）
- `apps/web/src/app/api/channel/listing-detail-allowlist.test.ts`（新）
- `apps/web/src/lib/api-proxy.ts`（**纯增量**一条 `listing-details` 元素）

**契约 / 台账 / 文档**
- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（**仅本模块那一行**）
- `docs/sessions/2026-10/2026-10-19-4-1-1-2-listing-detail.md`（本文件）

## 开发中实测修掉的真缺陷

1. `capabilities()` 的 `List.of(...)` 维度清单**漏一个右括号** —— 编译期报「需要 `')'` 或 `','`」，一次报错带出整处。
2. `SNAPSHOT_SQL` 只 SELECT 了元信息列，`platformContentOf` 取 content 字段时 jOOQ 报 `Field "title" is not contained in row type`。
3. **集成测试原本用 Testcontainers 超级用户连接去断言「读不到对方数据」** —— 超级用户绕过 RLS，那条断言**永远是失效断言**（会一直得到「读得到」）。改连 `channel_app` 后才真正验到隔离，并补了一条「同一机制在本租户自己的数据上必须看得见行」，防止那个 0 只是查询一直返回 0。
4. 平台码表是 V1 的既有种子（该 UUID 上是 `ETSY`），测试硬编码 `AMAZON` 是假断言 —— 改为从码表反查比对。
5. `numeric(19,6)` 的 3 在差异表里显示成 `3.000000`，与登记簿的 `50.000` 并排看着像两个不同格式的值。改成**无损**尾随零归一（`stripTrailingZeros`），null 仍是「没有值」。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 后端 | `./mvnw -q compile` | BUILD SUCCESS |
| L1 后端 | `./mvnw -pl services/channel -am test -Dtest='ListingDetailIntegrationTest*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **15 例全绿** |
| L0 前端 | `pnpm typecheck` | 3 包成功 |
| Lint | `pnpm lint` | 3 包成功 |
| 单测 | `pnpm vitest run apps/web/src/components/listing-detail` | 14 例全绿 |
| 白名单（改了 api-proxy 的必跑回归） | `pnpm vitest run apps/web/src/app/api/trade` | **38 文件 / 203 例全绿** |
| 白名单（channel + 上游） | `pnpm vitest run apps/web/src/app/api/channel apps/web/src/components/listing-detail apps/web/src/components/listings` | 12 文件 / 85 例全绿（含 4.1.1.1 原有的两份，一字未改仍绿） |

未跑全量 `verify`（按本批次协议由调度器在集成头统一跑）。

## 后续待办

1. **列表行下钻链接**在 4.1.1.1 的 `listing-workspace.tsx` 里 —— 本次刻意未改已交付车道文件，需要并入后在列表行加一个到 `/listings/{id}` 的入口。
2. 05 定价接入后：`price` 维度 `available` 翻 true，diff 表的 `price` 行从 `UNAVAILABLE` 变成真比较。**届时必须同时决定登记簿快照的去留** —— 它与定价系统的值迟早会对不上。
3. 07 库存接入后：`internal_stock` 行才有右列，**那条才是蓝图原意里的库存差异**。
4. 4.2.1.1 接入后：内容对照右列与 8 个 CONTENT 行的 `available` 一起翻 true。
5. 4.3.2.1 接入后：回写按钮从 disabled 变成可点。
