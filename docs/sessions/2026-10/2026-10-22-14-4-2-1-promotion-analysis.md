# 2026-10-22 —— 14.4.2.1 促销效果分析（卷 14 广告与增长 / D7）

> 车道 `14.4.2.1`，分支 `feat/14-4-2-1`，基线 `integration/module-dev`（c4f28a45）。
> 迁移版本号 **V287**（本批预分配：另三条车道用 V285 / V286 / V288）。

---

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/14-ads-and-growth.md` 的 `### 14.4.2.1`）：

> **职责**：促销效果分析：促销期间销量/销售额/毛利变化、增量判断、补贴成本、ROI，
> 与活动前的基期对比。
> **输入**：促销数据；历史基线；利润数据。
> **输出**：效果报表 + 增量估算 + ROI。
> **关系**：上游 = 14.4.1；下游 = 13.4 利润、14.2.2.1 预算。
> **促销期间毛利可能大幅下滑**，评估必须看毛利而不是销售额。

这句话里真正决定设计的是**加粗的那句**。本车道把整句拆成五个可验证的落点：

| 职责句 | 事实源在不在本仓 | 本车道怎么做 |
| --- | --- | --- |
| 促销期间销量 / 销售额 | **在**（同库 `listing_performance_snapshot`，平台回报口径） | **只读不复制**，同一天多来源取最近一条 |
| 促销期间毛利变化 | **不在**（成本在 catalog；13.4 利润未交付） | **自算**：销售额 − 单位成本 × 销量；成本**复制**进 channel 自己的表 |
| 增量判断 | 在（基期由本模块落库） | 基期 = 促销首日之前**等长且紧邻**的一段；判据是**增量毛利** |
| 补贴成本 | 部分在（促销定义有折扣幅度；基准价要登记） | 单件让利 × **实际促销期销量**（不是 14.4.1.1 的「最坏情况」） |
| ROI | 在 | **分子分母各自落库**，比率用 GENERATED 列 |

**「评估必须看毛利而不是销售额」被钉死在三处**：判定列的判据是增量毛利；
「放量不赚」单独一列；前端报表里**销售额与毛利并排出现在同一张表**。

---

## 关键 trade-off

### 1. 成本与售价：复制进来，而不是跨服务读

catalog 里有成本与售价（V215 / V223 / V231 / V236），但**跨服务 SQL 被 AGENTS.md 禁止**
（不 join、不建跨库 FK、不建共享视图、不写 `catalog.` 限定名）。
本车道新建 `ad_promotion_cost_snapshot` 作为**成本进入 channel 的唯一通道**，
带 `source_ref` + `captured_at` + `version` ——
事后能回答「这个成本是**什么时候**、按**哪条口径**登记的」，而不是「现在 catalog 里是多少」。

**为什么不直接去 catalog 拉一次**：跨服务读一次就多一次口径漂移的来源，
而毛利是**促销决策**要用的数字，必须能被复盘。代价是要有人登记快照（前端提供了登记入口），
这个代价是**显式**的；跨服务读的代价是**隐形**的。

### 2. 销量事实源：只读 `listing_performance_snapshot`，不建「促销销量」表

该表的 UNIQUE 是 `(tenant_id, listing_id, stat_date, source)` ⇒ **同一天可能有 PLATFORM 与 API 两条来源**。
直接 `sum()` 会把同一天的成交算两遍 ⇒ 取 `received_at desc, id desc` 的最近一条，**不相加**。

同时**不新建「促销销量」表** —— 那会造出第二份与平台口径打架的事实，
而且打架的那份没人会发现。

### 3. 基期：等长且紧邻 ⇒ 增量是总量口径

基期起 = 促销首日 − 促销天数、基期止 = 促销首日 − 1。
两个窗口等长 ⇒ 「增量 = 当期 − 基期」可以直接相减，评审时不必再换算；
同时把日均值也落库（`baseline_daily_*`），方便按天口径复核。

### 4. 自然日按站点时区切分

平台自然日由 `marketplace.default_timezone` 决定，不是 UTC 日。
时区名是租户可维护的码表，拼错会让 `AT TIME ZONE` 直接抛错
⇒ **只认 `pg_timezone_names` 里真实存在的时区**，认不到退回 UTC，
不让一条脏码表把整次分析打挂。

### 5. 补贴成本是「事后」口径，与 14.4.1.1 的「事前」口径不同

14.4.1.1 的 `BUDGET_CAP` 护栏算的是**最坏情况**让利额（销量未知 ⇒ 取上界），
那是**事前拦截**用的。本模块是**事后**分析，知道实际销量 ⇒ 补贴成本 = 单件让利 × **实际促销期销量**。

单件让利照抄 14.4.1.1 的折后价口径：`PERCENT_OFF` = 基准价 × 折扣率；
`FIXED_AMOUNT_OFF` = 固定减免额；`BOGO / BUNDLE / FREE_SHIPPING` **没有可折算的价格系数
⇒ 让利单价不可比（NULL）**。

### 6. 不可比一律 NULL，绝不写 0

缺成本快照 ⇒ 毛利为 null；缺基准价 ⇒ 百分比促销的补贴为 null；
促销类型不可折算 ⇒ 补贴为 null。0 会被渲染成「毛利是 0」/「这次没花钱」，
那与「拿不到」在运营决策上完全相反。

**配套**：毛利 / 增量 / ROI 的 `>= 0` CHECK 被**去掉** ——
促销期毛利下滑正是本模块要报的场景，加了这条约束反而写不进库；
而 `roi_value` / 毛利率仍是 GENERATED 列（绕服务层也编不出与原始量对不上的比率，分母 ≤ 0 ⇒ NULL）。

### 7. 活动维度是真聚合，不落库活动级汇总行

效果结果表只存**商品维度（促销 × listing）**事实；活动维度由报表端点用 SQL `GROUP BY` 真聚合出来。
**不在库里再存一份「活动级汇总行」** —— 两份数会漂移，而漂移的那份没人会发现。

聚合口径是「先汇总再相除」（Σ毛利 / Σ销售额），**不是**各商品毛利率的平均
（两条毛利率 0% 的商品平均成 0% 会让人以为整体在盈亏线上）。
且**只要有一个商品不可比，该活动对应指标就是空值而不是部分和**。

### 8. 权限码复用 `channel.operation.*`

与 14.1.1.1 活动列表、14.2.2.1 预算分配、14.4.1.1 促销列表同族 ⇒ identity 服务零改动。

---

## 本车道修掉的三个自己的真 bug

1. **jOOQ 时间绑定**：`values` 位置的 OffsetDateTime 必须写 `?::timestamptz`
   （jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒）。
   第一次写漏了，报 `column "window_from_at" is of type timestamp with time zone
   but expression is of type character varying`。
2. **占位符少了一个**：`?::timestamptz` 落到了 `margin_alert`（boolean）上 ⇒
   `cannot cast type boolean to timestamp with time zone`。数一遍占位符才发现。
3. 🔴 **自然日对齐写错（最隐蔽的一个）**：最初写成
   `(date AT TIME ZONE tz)::timestamptz`。但 PostgreSQL 的 **`date AT TIME ZONE zone`
   返回的是 `timestamp without time zone`**（当地午夜对应的 UTC 墙钟），
   再 `::timestamptz` 会被**会话时区二次解释** ⇒ 窗口整体偏移 8 小时，
   基期从 10-07 变成 10-06、促销期天数从 3 变成 4。
   改成 `timezone(tz, date::timestamp)`（直接返回 timestamptz，没有这一步歧义）。
   **这个坑是实测踩出来的**，不是看文档猜的 —— 文档里 `date` 的重载不显眼。

---

## 文件清单

**迁移**
- `backend/services/channel/src/main/resources/db/migration/V287__ad_promotion_analysis.sql`
  （5 张表：`ad_promotion_cost_snapshot` / `ad_promotion_effect_baseline` /
  `ad_promotion_effect` / `ad_promotion_effect_export` / `ad_promotion_analysis_run`）

**后端（channel 服务）**
- `backend/services/channel/src/main/java/com/starcon/channel/AdPromotionAnalysisModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdPromotionAnalysisService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdPromotionAnalysisController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AdPromotionAnalysisIntegrationTest.java`

**前端**
- `apps/web/src/components/ads/ad-promotion-analysis-model.ts`
- `apps/web/src/components/ads/ad-promotion-analysis-model.test.ts`
- `apps/web/src/components/ads/ad-promotion-analysis.tsx`
- `apps/web/src/app/ads/promotion-analysis/page.tsx`
- `apps/web/src/app/api/channel/ad-promotion-analysis-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**仅新增一条独立 `ad-promotion-analysis/` 元素**，
  插入锚点 = 既有 `aftersales` 那条之后；既有 133 条一字未动、不重排；
  裸路径 `ad-promotion-analysis` 刻意不放行，末尾**刻意不带 `?`**）

**契约（codegen 产物，只增不改）**
- `packages/api-contracts/src/channel.openapi.json`（**+7 path / +17 schema / 0 删除**）
- `packages/api-contracts/src/contracts/channel.ts`

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（只改 14.4.2.1 那一行的「状态」「证据」两格）
- 本文件

---

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q -pl services/channel -am compile        # 绿
cd .. && pnpm typecheck                                          # 绿（3 包）
cd .. && pnpm lint                                               # 绿

# L1 模块闸
cd backend && ./mvnw -pl services/channel -am test \
    -Dtest='AdPromotionAnalysis*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# → Tests run: 22, Failures: 0, Errors: 0, Skipped: 0

# 前端
pnpm vitest run apps/web/src/components/ads/ad-promotion-analysis-model.test.ts \
               apps/web/src/app/api/channel/ad-promotion-analysis-allowlist.test.ts
# → 27 例绿

# 改了 api-proxy ⇒ 必须全量 allowlist 回归
pnpm vitest run apps/web/src/app/api
# → 164 文件 / 972 例绿
```

**集成测试的硬断言**（`AdPromotionAnalysisIntegrationTest` 22 例）：
多租户三断言（两租户互不可见 / 伪造 `X-Tenant-Id` 无效 / 连接池压到 1 的 A→B→A 三轮不串租户）、
**毛利下滑负向场景**（销售额 300 → 450 但毛利 +60 → **−30** ⇒ 判定必须 `DECREMENTAL` +
`marginAlert=true`，两侧都从库里读回断言）、
**ROI 分子分母各自落库**（`-90.0000` / `240.0000` / `-0.375000`，**负 ROI 落得下**）、
**不可比不是 0**（没成本 ⇒ 毛利 null；BOGO ⇒ 补贴 null）、
**基期等长紧邻**（2026-10-07 ~ 2026-10-09，逐字读回）、
**同一天多来源不相加**（60 件而不是 120 件）、
**活动维度是真聚合**（Σ口径 1350.0000 / 毛利率 0.288889，不是平均 0.333333；
一个商品不可比 ⇒ 活动级毛利 null 而非部分和）、
真分页真筛选真排序（默认按毛利）、导出留痕 + CSV、跳过不许静默消失、
DB 层兜底（跨租户挂效果结果被外键拒 / 基期窗口倒挂被具名 CHECK 拒）。

---

## 后续待办

1. **13.4 利润交付后**：把 `profit_report` 维度的 `available=false` 放开，
   与本模块的口径对齐（当前口径已写死并随响应返回，对齐时**以本模块为准**）。
2. **平台促销 API 交付后**：本模块的增量是「同商品活动前 vs 活动期」的对比，
   **不是**平台口径的促销归因 ⇒ `platform_attribution` 维度可放开。
3. **12 财务进仓后**：汇率源到位 ⇒ `fx` 维度可放开，届时销售额可跨币种换算而不只是「只统计同币种」。
4. **成本自动同步**：当前成本快照靠人工登记。可以考虑由 catalog 侧提供
   「成本快照批量拉取」的**授权 API**（仍然是 channel 复制、不是跨服务 SQL）。
5. **活动维度的「个别商品放量不赚」**：活动维度只报整体口径，
   整体毛利会把个别商品的放量不赚掩盖 ⇒ 需要定位到商品请切商品维度。
   若要在活动维度也表达，改法是加一列「放量不赚商品数」（而不是改判定口径）。
