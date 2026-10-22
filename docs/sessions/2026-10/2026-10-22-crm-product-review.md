# 15.2.1.1 评价列表（阶段 D7，L3 锚点 15.2.1）

日期：2026-10-22 ｜ 分支 `feat/15-2-1-1` ｜ 基线 `integration/module-dev` @ `da1f96f9`
迁移版本：**V294**（本车道预分配；同批另三条车道用 V293 消息收件箱 / V295 客户标签 / V296 购物车挽回）

## 决策摘要

### 1. 加粗句「评分趋势比单条评价更重要」落成「不建汇总表 + 现算」

蓝图加粗句要的不是「多存一个平均分字段」，而是一条**可执行的读路径**：
评价必须能**按时间窗口聚合**（趋势）、能**按维度分组**（定位到店铺 / 商品 / 变体）。

因此本模块**刻意不建「店铺评分汇总表」**：汇总一律由明细**现算**（服务层显式 SQL 聚合）。
理由：落库的汇总会变成**第二份事实** —— 明细改了汇总没改时，「平均分」与「趋势」互相矛盾，
而没人说得清哪一份是真的。单一口径（明细）才可能自洽。

统计产出四组，**全部由 SQL 现算**（`avg` 走 numeric，禁浮点）：

- `ratingDistribution`：星级分布（1..5 五档，**空档也列出来** ——「0 条」本身是一条信息）
- `trend`：按时间桶的均分序列（**空桶也补出来**，count=0、均分为 null ——
  只列有评价的桶会让「中间那段没评价」看起来像「数据丢了」）
- `byShop` / `byProduct` / `byVariant`：三个维度的汇总
- `declining`：**在下滑的维度**清单

### 2. 趋势的时间轴是「平台评价时刻」，不是「登记时刻」

明细里有两个时刻：`platform_reviewed_at`（平台上的评价时刻）与 `registered_at`
（登记进本库的时刻）。**分桶用前者**。

用登记时刻分桶会把「今天集中补录了一批上个月的老评价」显示成「今天的评分突然崩了」——
那是**假下滑**，会把客服引到错误的方向上。测试专门钉了这一条
（两条 60 天前的评价刚刚登记 ⇒ 30 天窗口内**一条都不算数**）。

`registered_at` 由 DB 触发器 `guard_crm_product_review_provenance` 钉成建后不可改，
`platform_reviewed_at` 同样不可改（改了它，历史趋势就能被事后改写）。

### 3. 「连续下滑」= 三窗单调，且能定位到维度

作用域三等分成**远窗 / 中窗 / 近窗**三段，每段各算一次均分：

- `delta = 近窗均分 - 中窗均分`，负数即下滑；
- `continuous = true` 表示**近窗 < 中窗 < 远窗**（三窗单调）——
  只有两窗可比时**不敢**说「连续」，页面因此有「连续下滑（三窗单调）」与
  「下滑（尚未确认连续）」两种说法。

维度分三个：**店铺 / 商品 / 变体**。商品与变体按**跨店铺**聚合
（同一个变体在几家店都跌，才是变体本身的问题；店铺维���则带出 shop_id 与平台）。
光有「评分在跌」没有维度，客服无从下手 —— 那正是加粗句要解决的。

### 4. 关联订单上下文走**两个**授权 HTTP 只读端口，读不到就明说

订单事实在 **trade**（9.1.2.2），履约签收事实在 **channel**（10.3.2.2）——
本库**一条 SQL 都不碰对方的库**，不建跨库 FK / 跨库视图。
新增 `ReviewOrderContextPort`（照抄 `SignoffDeliveredOrderPort` 的形状：
RestClient + 转发**调用方自己的 Authorization bearer**、**不传租户号**、
超时显式设置、读不到就显式失败）。

两个上游**分别读**：一个挂掉**不抹掉**另一个的结论（trade 挂了但 channel 正常时，
客服至少还能看到履约事实）。

🔴 **订单上下文读不到时明确显示「订单上下文不可用」并说明原因，绝不显示空白** ——
客服看到空白会以为这条评价没有关联订单，而真相可能是上游挂了。
三种状态各有各的话：「本来就没有关联订单号」/「上游没有这张单的签收记录」/
「订单事实读不到：trade 服务返回 503」。

**订单号必须精确匹配**：上游 keyword 是 `ilike '%关键字%'`，查 `SO-1` 会把 `SO-10` 一并捞回来，
挂错单的履约事实**比查不到更糟** ⇒ 端口在返回行里做精确匹配。

### 5. 平台评价同步未接入 ⇒ 端口 + 显式失败，人工登记是主路

`channel/vendor` 只有 19 个 OAuth 适配器，**没有买家评价适配器**。照
15.1.2.1 买家邮件的同构先例处理：`PlatformReviewPort`（接缝）+
`UnavailablePlatformReviewPort`（当前实现）—— 逐平台答「未接入」，
**不返回任何评价、不编造平台接口、不返回硬编码列表、不抛异常**。
同步端点据此返回 **409 且一行都不写**（测试断言「行数不变」）。

**人工登记是唯一能进评价的主路**：真落库、真分页、真筛选、真导出、真参与评分统计。

### 6. 下游 15.2.1.2 / 15.2.1.3 未交付 ⇒ capabilities 标 false + ownerModule

「是否已回复」**在** 15.2.1.1 的职责里（它是列表的一列，也是「未回复」这个筛选面），
所以本表有 `replied` 列；但**模块内的回复不归本模块**：
15.2.1.2 未交付 ⇒ 本模块**不记录回复正文、不记录平台回执、不编造下游 id**，
`replied` 的语义被明确限定为「**平台侧**是否已有卖家公开回复」（登记时由运营核对填写）。
capabilities 里 `review_reply` / `negative_review_handling` 两个维度 available=false + ownerModule。

低星评价在本模块里**只是星级分布里的一档**，不会被自动贴上「产品问题 / 物流问题」的标签 ——
那些标签要有证据，而证据属于 15.2.1.3。

15.2.2.1 索评 / 15.2.2.2 索评规则**已交付** ⇒ 本模块**只读**引用其既有事实，
不改它们的表结构、规则表与端点。

### 7. 一张表，且外键一律不用

只建 `crm_product_review`：
「是否已回复」是评价的**一个属性**（一列）；统计 / 趋势 / 分布是**派生读**；
导出是同一批明细的另一种呈现；回复记录归 15.2.1.2；同步日志在同步真正接通前**没有任何行**
（建一张永远空的表只会让人以为同步跑过）。

**一律不用外键**（照 V219 / V220 / V228 的手法）：batch-37 有车道在迁移里给既有表建
FOREIGN KEY，结果打破了同服务既有测试的 TRUNCATE。本文件**不给任何既有表建外键**，
本文件自己也不建 —— 引用校验用触发器（`guard_crm_product_review_provenance`）。

`productRef` 与 `variantRef` **必填**：没有商品 / 变体维度，
「下滑定位到产品还是履约问题」这个能力就是空的。

### 8. 权限码 `crm.product_review.*`，identity 服务零改动

同服务既有族是 `crm.knowledge.*` / `crm.review.*` / `crm.rule.*` / `crm.email.*`。
`crm.review.*` **归 15.2.2.1 索评** —— 本模块取 `crm.product_review.read` /
`crm.product_review.update`（与 `identity.accounting_book.*` 那一族同形：对象名可多词）。
identity 的 permission 表**零改动**。

### 9. api-proxy 新增一条独立元素，零命中已用脚本核对

本车道**只新增**一条 `prefix: 'crm/'` 的 services[] 元素（首 token `reviews`），
锚点 = `crm/review-requests` 那条**之后**（既有条目一字未动、也不重排）。

**零命中核对结论**：用脚本把本文件既有**全部 144 条** services[] 正则逐条跑一遍，
本模块的 7 个形状（`reviews` / `capabilities` / `shops` / `stats` / `export` / `sync` / `{uuid}`）
**命中数为 0**。原因：既有 crm 分支的首 token 是 `buyer-emails` / `knowledge` /
`review-requests` / `review-rules`，与 `reviews` **首 token 即不同**；
`resolve()` 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。

⚠️ **裸路径 `reviews` 是放行的**（与本仓多数模块相反）：本模块的列表入口就是集合根路径
（`GET /crm/reviews` = 评价列表），后端确实有这个映射 —— 不放行它页面会 404。

## 关键 trade-off

| 选择 | 放弃了什么 | 换来了什么 |
| --- | --- | --- |
| 不建汇总表，统计现算 | 每次统计都跑聚合（数据量大时变慢） | 单一事实源，不会出现「平均分与趋势互相矛盾」 |
| `productRef` / `variantRef` 必填 | 少量「没有明确变体」的评价登记不进来 | 「定位到产品还是履约」这个能力是真的 |
| 订单上下文两个上游分别读 | 部分失败时上下文不完整 | 一个上游挂掉不抹掉另一个的结论，且**永远说清缺了什么** |
| 端口做订单号精确匹配 | 上游返回的候选行要自己筛一遍 | 不会把别的单的履约事实挂上来 |
| 导出上限 10000 行并如实截断 | 超过上限要缩小筛选再导 | 不会悄悄少导一部分却让人以为拿到了全部 |

## 文件清单

**迁移**
- `backend/services/crm/src/main/resources/db/migration/V294__crm_product_review.sql`

**后端（crm 服务）**
- `.../main/java/com/starcon/crm/ProductReviewModels.java`
- `.../main/java/com/starcon/crm/ProductReviewService.java`
- `.../main/java/com/starcon/crm/ProductReviewController.java`
- `.../main/java/com/starcon/crm/PlatformReviewPort.java`
- `.../main/java/com/starcon/crm/UnavailablePlatformReviewPort.java`
- `.../main/java/com/starcon/crm/ReviewOrderContextPort.java`

**测试**
- `.../test/java/com/starcon/crm/ProductReviewIntegrationTest.java`（21 例）
- `.../test/java/com/starcon/crm/ProductReviewTenancyIntegrationTest.java`（4 例）

**前端**
- `apps/web/src/app/crm/reviews/page.tsx`
- `apps/web/src/components/crm/product-review-model.ts`
- `apps/web/src/components/crm/product-review-model.test.ts`
- `apps/web/src/components/crm/product-review-workspace.tsx`
- `apps/web/src/app/api/crm/reviews-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条** services[] 元素，既有条目未动）

**契约与台账**
- `packages/api-contracts/src/crm.openapi.json`、`packages/api-contracts/src/contracts/crm.ts`
  （由 `tools/codegen/catalog-contracts.py` 生成，**未手改**；
  同批跑 codegen 会把 channel / trade 的**过期**契约一并刷新 —— 本车道把那部分还原了，
  保持 diff 只落在 crm 上）
- `docs/product/capability-blueprint/module-progress.md`（**只改 15.2.1.1 那一行的状态与证据两格**；
  其余是 `sync.mjs` 重算的汇总数字）

## 验证

- L0 编译闸：`./mvnw -q compile`（backend 根）→ 通过
- L1 模块闸：`./mvnw -pl services/crm -am test -Dtest='ProductReview*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **25 例全绿**（21 + 4）
- 前端：`pnpm vitest run apps/web/src/components/crm/product-review-model.test.ts apps/web/src/app/api/crm/reviews-allowlist.test.ts`
  → 16 例全绿
- **api-proxy 全量 allowlist 回归**（改了共享白名单，必须跑）：
  `pnpm vitest run apps/web/src/app/api` → **169 个文件 / 978 例全绿**
- `pnpm typecheck` → 通过；`pnpm lint` → 通过（仅既有模块的 warning）

## 后续待办

- **15.2.1.2 评价回复**：交付后「是否已回复」应由它写回，本模块的 `replied` 语义要从
  「登记时核对的平台侧状态」升级为「模块内回复的事实」；capabilities 的 `review_reply`
  维度随之转 true，并需要一条把回复事实并回列表的读路径。
- **15.2.1.3 差评处理**：低星评价的原因标记 / 申诉判定 / 解决率。本模块刻意**没有**
  给低星评价贴任何标签（没有证据）；差评处理落地后，「下滑维度」才能进一步区分
  「产品问题」与「履约问题」—— 目前能定位到**店铺 / 商品 / 变体**，但不能自动归因。
- **平台评价适配器**（卷 2 同步）：接上之后 `UnavailablePlatformReviewPort` 被替换，
  `POST /crm/reviews/sync` 自动放行；`source='PLATFORM_SYNC'` 与
  `external_review_id` 的唯一索引（幂等键）已先钉好，语义不用再改。
- **买家档案**（15.3.1.1 未交付）：`buyer_ref` 现在只作展示与去重键；档案落地后可接上。
