# 14.3.2.1 归因分析（阶段 D7，L3 锚点 14.3.2）

- 日期：2026-10-29
- 车道：`feat/14-3-2-1`（worktree `.worktrees/14.3.2.1`）
- 服务归属：**channel**（卷 14 主责服务；广告事实由 channel 自己拥有）

## 决策摘要

### 1. 上一轮遗留的 `V404__ad_attribution.sql` —— **复用并补全**（不是重写）

上一轮（两次崩掉的 worker）留下了 341 行的迁移草稿。逐条核对后确认**方向是对的**：
四张表（规则 / 订单级归因候选 / 点击触点 / 归因结果）、复合 FK 带 `tenant_id`、
`FORCE ROW LEVEL SECURITY` + `data_scope` 收敛、零 ALTER 任何既有表、金额 `NUMERIC(19,4)` /
数量 `NUMERIC(19,6)` / 币种 `char(3)` 不跨币种相加 —— 全部与已交付的兄弟模块
（V273 / V277 / V290 / V402）同构，且依赖面全部指向**已交付**的上游。

补全了三处：

| 补什么 | 为什么 |
| --- | --- |
| `attributed_units`（订单行 + 事实行各一列） | 「增量效果估计」需要**件数**口径（13.4.1.2 给的是件数，**不是订单数**）⇒ 订单数侧没有分母就不编 |
| `attributed` **生成列**（`attribution_status='ATTRIBUTED' AND internal_sku_id IS NOT NULL`） | 「这一行进不进报表」是**派生**的 ⇒ 用 GENERATED 列钉死，服务层写不进去、绕过服务层也编不出来（照抄 V273 的派生比率纪律） |
| `ad_attribution_export` 第五张表 | 导出留痕必须带**规则快照**，否则事后翻出一张 CSV 答不出「当时用的是哪套口径」 |

**关键改动**：事实表的唯一键从 `(tenant_id, rule_id, order_id)` 改成
`(tenant_id, rule_id, **rule_version**, order_id)` —— 见下条。

### 2. 🔴 规则版本进事实行（蓝图加粗句的落点）

蓝图原文：「**归因规则必须固定并公示**，规则变了历史报表不可比」。

- 规则是**租户级配置事实**，partial unique index 钉死「一个租户同时只有一套生效口径」。
- 规则**可以改**，改完 `version + 1`。
- **归因结果按 (规则, 规则版本, 订单) 落库** ⇒ 改规则后重跑产生的是**新版本的事实**，
  旧版本的事实**原样保留** —— 「历史报表不可比」这条纪律因此不会被自己破坏
  （不会出现「旧报表的行被新口径悄悄改写」）。
- 每条归因结果与每条导出留痕都自带规则快照（编码 / 版本 / 模型 / 窗口）。
- 报表默认看**当前版本**；显式传 `ruleId + ruleVersion` 可以翻历史口径做对比。

### 3. 归因状态拆成「点击口径」与「SKU 绑定」两个正交维度

- `attribution_status` 只描述**点击 / 模型口径**的结论：
  `ATTRIBUTED` / `NO_ELIGIBLE_CLICK` / `NO_PLATFORM_ATTRIBUTED_CLICK`。
- 「归不到唯一内部 SKU」**不是**这一列 —— 它是 `internal_sku_id` 为空。
- 两者都成立时，`attributed` 生成列为 false ⇒ 不进 SKU 报表，金额进「归不上」桶。

这样「归因不成立」有两个**可区分**的原因，不会互相掩盖。

### 4. 增量效果估计 = **反事实单件均价外推法**（推导值，不是平台原值）

蓝图要求输出「增量效果估计」，但**平台只给归因结果、不给增量** ⇒ 它必须是推导值。

口径：`S_cf = Q_ad × (S_org / Q_org)`，`增量销售额估计 = S_ad − S_cf`
（`S_total/Q_total` 取 13.4.1.2；`S_ad/Q_ad` 取本模块归因事实）。
**口径边界写进 `incrementalityNote` 随响应返回**：这个估计衡量的是
「广告单与自然单的**单件均价结构差**」，**不是因果增量** —— 真正的增量需要留出组 /
地域对照实验，平台不提供 ⇒ 能力声明里 `causal_incrementality.available=false`，不假装有因果结论。

### 5. 与 14.3.1.3 的分工（两者的「广告花费」不是同一个数）

- 14.3.1.3：投放关系分摊（活动 → Listing → SKU 两级等分）—— 答「这个商品上分摊了多少广告费」。
- 14.3.2.1：订单级归因（哪一笔订单归到了哪个 SKU）—— 答「这些订单是广告带来的吗」。

同一个 SKU 的「广告后净利」在两张报表上不相等是**正常的** ⇒ 口径说明随响应返回。

### 6. 「订单数据」输入的取舍（如实记录）

蓝图输入句点名的「订单数据」在本模块落成**订单级平台广告归因数据**
（`ad_attribution_order` + `ad_attribution_click`）—— 那是归因真正需要的订单数据。
**没有**接 trade 的 `/trade/platform-orders/orders`：该端点**没有日期区间筛选**
（只有 `platformCode / shopCode / normalizedStatus / keyword`），要算「区间内总单数」
只能全量翻页再客户端过滤并截断 —— 把这种截断当财务口径的分母不诚实。
**代价**：增量效果估计走**件数**口径（13.4.1.2 给件数不给订单数），
**不做增量订单数估计**（能力声明 `order_count_incrementality.available=false` + 写明原因）。

## 关键 trade-off

| 取舍 | 选了什么 | 代价 |
| --- | --- | --- |
| 规则改动 vs 历史事实 | 规则版本进唯一键 ⇒ 改规则不重算 | 同一规则会留下多版本事实，报表默认只显示当前版本 |
| 归因结论的粒度 | 点击口径与 SKU 绑定拆成两维 | 状态字段变多，界面要同时看 `attributionStatus` 与 `internalSkuId` |
| 增量效果的分母 | 件数（13.4.1.2 给的）而不是订单数 | 没有「广告单数 ÷ 总单数」的增量口径（缺分母，不编） |
| 跨服务依赖 | 只读 reporting（13.4.1.2）+ catalog（03），复用 14.3.1.3 已有的两个端口 | 利润侧读不到时降级 + 如实说原因（广告侧不受影响） |

## 文件清单

### 迁移（channel 服务）

| 文件 | 内容 |
| --- | --- |
| `backend/services/channel/src/main/resources/db/migration/V404__ad_attribution.sql` | 五张表 + RLS + 索引 + GRANT；零 ALTER 既有表 |

### 后端（channel 服务）

| 文件 | 内容 |
| --- | --- |
| `.../channel/AdAttributionModels.java` | 传输模型（能力声明 / 规则 / 导入 / 归因计算 / 明细 / 报表 / 导出） |
| `.../channel/AdAttributionService.java` | jOOQ 显式 SQL：规则 CRUD、导入（幂等）、归因计算、明细、报表、导出 |
| `.../channel/AdAttributionController.java` | 端点 `/channel/ad-attributions/**`（11 个） |

### 测试

| 文件 | 内容 |
| --- | --- |
| `.../channel/AdAttributionFixture.java` | Testcontainers PG16 + identity / reporting / catalog 三个桩（按 bearer 分租户） |
| `.../channel/AdAttributionIntegrationTest.java` | 21 例业务口径 |
| `.../channel/AdAttributionTenancyIntegrationTest.java` | 3 例多租户（三断言） |

### 前端

| 文件 | 内容 |
| --- | --- |
| `apps/web/src/components/ads/ad-attribution-model.ts` | 前端模型 + 展示辅助 + 校验 |
| `apps/web/src/components/ads/ad-attribution-model.test.ts` | 前端模型测试 |
| `apps/web/src/components/ads/ad-attributions.tsx` | 真实工作区（规则区 / 导入区 / 筛选 / 明细 / 报表 / 导出留痕 / 能力声明） |
| `apps/web/src/app/ads/ad-attributions/page.tsx` | 路由 `/ads/ad-attributions` |
| `apps/web/src/app/api/channel/ad-attribution-allowlist.test.ts` | api-proxy 白名单双向断言 |
| `apps/web/src/lib/api-proxy.ts` | **纯新增一条** `ad-attributions` 元素（插在 `ad-product-reports` 的 `pattern:` 行之后） |

## 后续待办

1. **平台广告归因数据的适配器未接入** —— 本模块的导入入口是 `POST /import`（运营/适配器写入），
   没有 vendor 侧自动拉取。能力声明里没有把它写成「已交付」。
2. **增量订单数口径** —— 等 13.4.1.2 的利润事实补上 SKU 级订单数后可补（当前缺分母）。
3. **因果增量** —— 需要广告主自己跑留出组 / 地域对照实验，平台不提供 ⇒ 本模块不假装有。
4. **跨币种统一** —— 需要汇率源（本仓没有汇率表也没有汇率同步器）。
5. `identity` 未登记 `reporting.*` 权限码的既有缺口（**不是本车道要修的**）：
   本模块的利润侧按「上游读不到 ⇒ 降级 + 明确原因」处理，广告侧不受影响。
