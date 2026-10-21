# 2026-10-19 2.4.2.3 映射缺失队列

能力蓝图模块 **2.4.2.3「映射缺失队列」**（阶段 D5，L3 锚点 2.4.2「异常处理」）。车道 `.worktrees/2.4.2.3`，分支 `feat/2-4-2-3`，Flyway 版本 **V191**（预分配），落地微服务 = **channel**（系统 02「渠道与店铺」主责 = channel；同 L3 的 2.4.2.1 / 2.4.2.2 已交付在 channel）。

## 决策摘要

### 1. 依赖自检：四条上游全部在基线里，模块可开工

开工前 10 分钟自检（`listing_variant` V171 / `listing_mapping` V176 / `listing_mapping_version_scope` V182 / `sync_failure` V2）全部命中，**没有 BLOCKED**。确认到的关键事实：

- 「未映射」的精确定义写在 V171 第 64 行注释里：= `listing_variant.internal_sku_id` 是否为空；该列真实存在，且 DB 层用 `CHECK((mapping_status='UNMAPPED') = (internal_sku_id IS NULL))` 钉住。
- `listing_mapping_version` 的**版本作用域**（V182）带半开区间 `[effective_from, effective_to)` 与**冻结触发器**（已关闭的版本行不可改写、不可删除）—— 这正是「历史订单冻结当时映射版本」在 channel 侧已有的物理基础。

### 2. 🔴 队列由 **DB 触发器**维护，不靠服务层同步

「变体已同步但未映射」是一个**持续条件**，不是一次性事件。若由服务层负责入队 / 出队，必然出现「映射已建立但队列还挂着」「变体已同步但没人入队」这类漂移 —— 而且它极难发现（列表看着没毛病，运营却漏了待办）。

处置：把队列完全交给 V191 的三个触发器（纪律照抄 V176 的现状位投影）：

| 触发器 | 时机 | 作用 |
| --- | --- | --- |
| `listing_variant_enqueue_gap` | `AFTER INSERT OR UPDATE OF mapping_status ON listing_variant` | 变体一进 `UNMAPPED` 就入队（含平台侧身份快照冻结） |
| `listing_mapping_gap_resolve_on_version` | `AFTER INSERT ON listing_mapping_version` | 映射版本落库 ⇒ 缺口标 `MAPPED` 并**回填到那一版** |
| `listing_mapping_gap_backfill_frozen` | `BEFORE UPDATE ON listing_mapping_gap` | **回填目标一旦写下就不可改写**（改写历史 ⇒ DB 直接拒） |

代价：服务层不能「手工把一条缺口改成已解决」—— 这正是我们要的。

### 3. 🔴「不按名称猜 SKU」的落点是**模型的缺席**，不是一句提示语

蓝图加粗句「不按名称猜 SKU」有三种落法，本车道全用上了：

1. **表层缺席**：`listing_mapping_gap` **没有** `suggested_sku_id` / `score` / 候选列表这类列。一个"疑似匹配"字段的存在本身就会让赶订单的人去选它。
2. **写入路径缺席**：「映射到已有 SKU」**直接复用 4.1.2.1 的 `ListingMappingService.save`** ⇒ `internalSkuId` 必须是**人给的**完整 UUID；模型层 `validateMapForm` 先挡一道（名字 / SKU 编码都会被拒）。
3. **职责边界**：候选与相似度仍归 4.1.2.1 的 `listing_mapping_candidate`（恒带 `requires_confirmation`），本队列不复制第二份匹配事实。

集成测试里对应的一条断言是**双向**的：未决策前 `internal_sku_id` 恒为 null，且清单行的 JSON 里**不得出现** `suggest` / `candidate` / `confidence` / `score` / `similarity` 任何一个键。

### 4. 🔴「历史订单冻结当时映射版本」= 三层，缺一层就等于没做

| 层 | 落点 | 断言 |
| --- | --- | --- |
| ① 记哪一版 | 缺口记 `mapping_version_id` = **当时解决它的那一版** | 改映射生成新版本后，缺口仍指向 v1 |
| ② 不可改写 | V191 的 `guard_listing_mapping_gap_backfill_frozen` + V176 的 `guard_listing_mapping_version_frozen` | 直接 UPDATE 回填目标 / 已关闭版本 ⇒ DB 拒 |
| ③ 历史语义 | 按历史时刻 `resolve(at)` 仍拿到当时的 SKU | `resolve(at=T0+60s)` → `RESOLVED` + sku1 + `versionNo=1` |

### 5. 🔴 跨服务边界：**订单来源的接入点已建，但没有生产者**

蓝图写着「09 订单（缺映射订单也进此队列）」，而 **09 订单在 trade 服务**。AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ 订单来源只能经 event / 授权 API 进入。

**当前 trade 侧没有推这条事件。** 处置（不写假数据、不假装已接）：

- 表上留了接入点：`source='ORDER'` + `order_ref` + `gap_reason='ORDER_UNMAPPED'`；
- 端点建了：`POST /channel/mapping-gaps/orders`（字段与校验都是真的，事件来了就能直接进队列）；
- 但能力声明里 `orderSourceWired=false` + ownerModule（09 订单履约 · trade）+ 影响，页面顶部明账列出；
- **受影响订单回填因此不实现**，且**不返回任何订单条数** —— 返回 0 会被读成「没有订单受影响」，而真相是「订单还没接进来」。

本批次以**已接入的本地来源**建模：`listing_variant` 无映射 + `listing_mapping_version` 解除态。

### 6. 🔴「新建 SKU」= 只登记意图，**没有创建任何 SKU**

03 建档需要 SPU 归属 + 编码规则 + 属性值（catalog 的 `POST /catalog/products/{id}/skus` 要 productId + code + name + barcode + baseUnit + 属性值），channel 一个都没有，跨服务也不该复制。

处置：新增 `listing_mapping_gap_sku_request` 表把意图**落库**（可查、可撤、审计留痕），派发到 03 标 `PENDING`。端点与页面都明写「**没有创建任何 SKU**」，且该缺口**仍留在队列里**（等 SKU 建好 + 建立映射后才离队）。测试断言 `created_sku_id` 为 null、`dispatched_at` 为 null、库里没有 SKU 映射行。

### 7. 🔴 价格：channel 侧**没有**平台售价事实 —— 沿用兄弟模块的「不画假数据」纪律

实测集成头：全库不存在任何「平台 listing 售价」事实表（`supplier_quote_price_tier` 是 06 采购报价、`b2b_price_book` 是 trade 的 B2B 协议价，都不是 listing 售价）。V171 第二节已经论证过：建一个 `price` 影子列 = 制造第二个价格事实来源。

处置（与 4.1.1.1 / 4.1.1.2 同一手法）：

- `listing_mapping_gap.price numeric(19,4)` + `currency char(3)`，语义是**平台侧售价快照**（与 05 定价的「系统价」是**两个事实**），价格与币种**同生共死**（DB CHECK）；禁浮点，服务层一律 BigDecimal；
- 平台商品来源**没有**价格快照（只有订单来源的事件载荷能带价）⇒ 能力声明 `priceAvailable=false` + 归属 05 定价 + 影响，前端价格位渲染「未接入（归 05 定价）」而**不是 0、不是 "--"**；
- 价格区间筛选是**真 SQL**（`price >= ?` / `price <= ?`），只覆盖带快照的行 —— 页面上写明该范围，否则运营会以为「筛完没结果 = 没有缺口」。

### 8. 「映射到已有 SKU」复用 4.1.2.1 的写入路径 ⇒ 版本历史是同一条

没有另起一套建映射逻辑，而是直接调 `ListingMappingService.save`。收益：映射行与版本历史与映射管理页面**完全一致**（不会出现「队列里说映射到 A、映射管理里说是 B」），且「改映射生成新版本、不原地改」的纪律一并继承。代价：本模块与 4.1.2.1 有了运行时依赖（`ListingMappingGapService` 注入 `ListingMappingService`），这是有意的。

## 关键 trade-off

| 取舍 | 选择 | 代价 / 理由 |
| --- | --- | --- |
| 队列是**物化表**还是**实时查询** | 物化表 + DB 触发器 | 实时查询没法承载「标记忽略」这个决策（决策必须落库才能持久）。代价是多一张表与三个触发器。 |
| 同一变体多次缺映射 | **部分唯一索引**（`WHERE status='PENDING'`）而不是表级 UNIQUE | 映射解除后变体要能重新入队，**旧行必须留作历史**（它记着"当时用的是哪一版"）。表级 UNIQUE 会让第二次入队永远写不进去 —— 这正是 4.1.2.1 踩过的坑（`UNIQUE(tenant_id, listing_variant_id)` 曾让「解除后重新建立映射」不可写）。 |
| 「新建 SKU」派发 | **只登记意图** | 03 侧没接线。宁可停在 `PENDING`，也不伪造"已创建成功"。 |
| 回填受影响订单 | **不实现**，只回填当前待映射记录 | 09 订单不在本仓。宁可少做，不做假。 |
| 价格列 | 建列但**平台商品来源恒为 null**，能力声明 available=false | 建在 `listing` 上是第二个价格事实来源（05 上线后两个数字对不上且没人说得清该信哪个）。建在队列表上、语义限定为"平台侧快照"则安全。 |
| 前端类型 | **自己声明**而不是复用 `packages/api-contracts` 的同名类型 | codegen 把 Java record 的可空字段压成了非空（`price: number` 而不是 `number | null`），照抄契约会让「没有价格快照」在**类型级**变成"有价格" —— 那正是画假数据的类型级来源。（与 `listing-mapping-model` 同一手法。） |

## 文件清单

**新增 · 后端（channel）**

- `backend/services/channel/src/main/resources/db/migration/V191__listing_mapping_gap_queue.sql` —— 两张表 + 9 个索引 + 3 个触发器 + RLS（isolation + data_scope）+ GRANT + 存量回填
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMappingGapModels.java` —— 传输模型（具名 record，不用 `Map`）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMappingGapService.java` —— 清单 / 概览 / 词表 / 能力声明 / 三个操作入口 / 订单接入点
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMappingGapController.java` —— 9 个端点，挂在 `/channel/mapping-gaps/**`
- `backend/services/channel/src/test/java/com/starcon/channel/ListingMappingGapIntegrationTest.java` —— **13 例**

**新增 · 前端**

- `apps/web/src/components/channel/mapping-gap-model.ts` —— 模型层（类型自己声明；六个筛选维度；三个操作入口；价格位与未交付段口径）
- `apps/web/src/components/channel/mapping-gap-model.test.ts` —— **15 例**
- `apps/web/src/components/channel/mapping-gap-workspace.tsx` —— 真实工作区（筛选面板 + 清单表 + 三个操作面板 + 能力明账）
- `apps/web/src/app/channels/mapping-gaps/page.tsx` —— 路由 `/channels/mapping-gaps`
- `apps/web/src/app/api/channel/mapping-gap-allowlist.test.ts` —— **5 例**（白名单双向断言）

**修改**

- `apps/web/src/lib/api-proxy.ts` —— **纯增量**一条独立 `channel/` 元素（`mapping-gaps/`），既有条目一字未动、也不重排
- `packages/api-contracts/src/contracts/channel.ts` + `packages/api-contracts/src/channel.openapi.json` —— **codegen 产物**（`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成，channel 纯新增 9 path）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 2.4.2.3 那一行**的「状态」「证据」两格

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | ✅ 绿 |
| L0 类型 | `pnpm typecheck` | ✅ 三包绿 |
| L0 lint | `pnpm lint` | ✅ 绿（无新增告警） |
| L1 模块 | `./mvnw -pl services/channel -am test -Dtest='ListingMappingGap*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ **13 例**全绿（真实 PostgreSQL 16 + Testcontainers） |
| 前端新测 | `pnpm vitest run apps/web/src/components/channel/mapping-gap-model.test.ts apps/web/src/app/api/channel/mapping-gap-allowlist.test.ts` | ✅ **20 例**全绿 |
| 前端全量 allowlist 回归（改了 api-proxy.ts ⇒ 必跑） | `pnpm vitest run apps/web/src/app/api` | ✅ **75 文件 / 477 例**全绿（无既有车道损伤） |

## 后续待办

1. **🔴 遗留一条跨模块的一行事实**（不在本车道改动范围）：4.1.2.1 的 `GET /channel/listing-mappings/unmapped` 端点仍返回 `backflowDelivered=false` + 「2.4.2.3 未交付」措辞。2.4.2.3 交付后该措辞已过期（回流入队那一跳现在由 V191 的 DB 触发器完成）⇒ **集成时应把该标志翻成 true**。本车道按「不碰别的模块的端点」纪律未改。
2. **09 订单来源接线**（归 trade 车道）：trade 侧推事件 → channel 的 `POST /channel/mapping-gaps/orders` 接收 ⇒ 能力声明 `orderSourceWired` 翻 true，`order_source` / `order_backfill` 两个维度翻 true。
3. **受影响订单回填**：需要 09 订单的真实来源（`order_line_component` 冻结口径）⇒ 届时才能给出真实的受影响订单范围。
4. **价格接入**（归 05 定价）：05 上线后 `priceAvailable` 翻 true，平台商品来源才有价格快照，价格区间筛选才覆盖全部队列行。
5. **「新建 SKU」派发**（归 03 商品）：catalog 侧提供建档事件 / 授权 API 后，`listing_mapping_gap_sku_request` 的 `PENDING` 才能被派发。
