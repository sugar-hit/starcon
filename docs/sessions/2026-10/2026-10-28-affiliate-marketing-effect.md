# 2026-10-28: 13.3.2.2 关联营销效果 —— 归因规则必须钉在数据上（阶段 D7，L3 13.3.2 的第二格）

> 车道 `feat/13-3-2-2`（从集成头 `integration/module-dev` @ `2fc29b57` 切出）。
> 蓝图：`docs/product/capability-blueprint/13-profit-and-analytics.md` → 13.3.2.2「关联营销效果」。
> 兄弟：13.3.2.1 流量转化（V395 / `/reporting/traffic-conversion`）、13.3.1.1 产品表现分析（V394 / `/reporting/product-performance`）。

## 摘要

| 项 | 值 |
| --- | --- |
| 模块 | 13.3.2.2 关联营销效果（卷 13 L3 13.3.2 流量与转化 → 第二格） |
| 迁移 | `V397__affiliate_marketing_effect.sql`（四张新表，零 ALTER 既有表） |
| 端点 | 5 个，挂 `/reporting/affiliate-marketing` |
| 页面 | `/analytics/products/affiliate-marketing` |
| 测试 | 后端 26 例（22 功能 + 4 租户隔离）· 前端 23 例 · api-proxy allowlist 回归 71 文件 / 394 例 |
| L0 | `./mvnw -q compile` exit 0；`pnpm typecheck` 3/3 successful |
| L1 | `AffiliateMarketing*` ⇒ **26 例全绿**（Failures 0 / Errors 0）BUILD SUCCESS |
| 契约 | codegen 产物，**纯增量**（reporting.ts +12 / reporting.openapi.json +904，removed = 0） |

## 决策摘要

### ① 「归因规则必须明确」落成**行键**，不是注释

蓝图的关系句是加粗的：「**归因需要明确规则**（末次点击 vs 首次点击），否则各人看到的数不一样」。
只写一行注释，半年后一定会有人把两条规则下的钱先加起来再除 ROI。

⇒ 落成三个层次：

- `reporting_affiliate_marketing_attribution_fact` **逐字保存上游归因记录自带的规则快照**
  （`rule_code` / `attribution_model` / `pool_weight_basis` / `attribution_window_days` /
  `refund_treatment` / `commission_basis`）—— **不用「当前生效规则」回填历史**；
- `reporting_affiliate_marketing_fact` 的每一行都带 `attribution_rule_code`
  ⇒ **报表的行键是 (营销位, 归因规则)**；
- DB 触发器拒绝给「依赖归因规则的指标」落**没有**规则码的事实行
  ⇒ 说不清用的是哪条规则的行**根本进不了库**。

**关键 trade-off**：同一对达人 × 活动如果历史上用过两条规则，报表就是**两行**而不是一行带两个数。
代价是行数变多；收益是 **ROI 只在同一条规则内部相除**（测试断言「混算得到的第三个数」绝不出现）。

### ② 口径词表说实话：**上游没有**「末次点击 / 首次点击」

蓝图拿「末次点击 vs 首次点击」举例，但上游 channel 14.5.2.1 的归因模型词表是
`COOPERATION_FULL` / `CAMPAIGN_POOL_SPLIT`（合作全额归因 / 活动池分摊）。
**广告点击归因归 14 广告所有**，本模块既不提供也不假装提供 —— 能力声明与页面口径说明里写明这一点。

⇒ 宁可词表看起来"不如蓝图漂亮"，也不把达人带货归因说成末次点击归因。

### ③ 四类营销位里只有两类有数据 ⇒ 逐类登记「本轮不可用 + 归谁管 + 替代口径」

开工前 10 分钟依赖自检的结论（**非 BLOCKED**，属"部分能力未交付但有替代口径"）：

| 营销位 | 本轮 | 依据 | 归谁管 |
| --- | --- | --- | --- |
| 社交媒体 | ✅ 达人归因口径 | channel `/channel/attributions/records`（带规则快照） | 14.5.2.1 |
| A+ | ✅ 落地商品口径 | channel `/channel/listing-content-writeback/.../content-history` 里有**真的 `A_PLUS` 字段变更** | 4.3.2.1 + 4.5.1.1 |
| 关联视频 | ❌ 本轮不可用 | channel 内容回写只有 TITLE / BULLETS / IMAGES / DESCRIPTION / A_PLUS，**没有 VIDEO**；也没有营销位配置表 | 4.2.1.1 |
| Store 导流 | ❌ 本轮不可用 | 上游没有导流位配置与导流位数据 | 2.1.2.1 |

另外三个「本轮不可算」（**不是 0**）：**加购**（平台侧加购行为数据上游未接入；CRM 15.6.2.2 只覆盖
独立站购物车，**不是同一口径，不拿它顶替**）· **达人站外曝光 / 点击**（带货归因只给成交归因）·
**内容营销位（A+）的成交归因与投入**（上游归因只覆盖达人带货 ⇒ ROI 随之不可算）。

**关键 trade-off**：A+ 营销位的曝光 / 点击取的是**落地 listing 的平台表现** ⇒ 那是
「落地页收到的全部流量（含自然、广告与其它营销位）」，**不是 A+ 素材自身的曝光**。
⇒ 每行都带 `caliber` 标签 + 口径说明，**不假装**那是营销位级曝光。
A+ 营销位**存在的证据**必须是「内容回写里真的写过 A_PLUS 字段」，不是「有人发起过一次 A_PLUS 回写」
（那一次可能被跳过）⇒ 代价是 N+1，用**有界上限 30 个 listing** + 如实报 truncated 兜住。

### ④ 派生值只在查询期算，DB 触发器钉死

ROI / 点击率 / 归因客单价在口径表里**只写分子分母引用**；
`guard_reporting_affiliate_marketing_fact_known` 拒绝给派生指标落事实行 ⇒ 库里没有第二个真相。

### ⑤ 窗口是「重建」不是「叠加」，且**全部写入在同一个租户事务里**

刷新先删该窗口旧行再整段重写；上游三路数据**全都取到了**才落库 ⇒ 不会出现
「归因写进去了、落地流量没写进去」的半张表。上游读不到一律 503（写明「读不到」而不是「没有数据」）。

## 文件清单

**后端（`backend/services/reporting/`）**

- `src/main/resources/db/migration/V397__affiliate_marketing_effect.sql` —— 四张新表 + 三个触发器 + RLS
- `src/main/java/com/starcon/reporting/ReportingAffiliateMarketingCatalog.java` —— 营销位类型 × 指标的可算性矩阵 + 标准口径
- `src/main/java/com/starcon/reporting/ReportingAffiliateMarketingModels.java` —— 传输模型
- `src/main/java/com/starcon/reporting/ReportingAffiliateMarketingPort.java` —— channel 只读端口
- `src/main/java/com/starcon/reporting/ReportingAffiliateMarketingRefreshService.java` —— 拉取 + 投影
- `src/main/java/com/starcon/reporting/ReportingAffiliateMarketingService.java` —— 查询 + 能力声明
- `src/main/java/com/starcon/reporting/ReportingAffiliateMarketingController.java` —— 5 个端点
- `src/test/java/com/starcon/reporting/AffiliateMarketingIntegrationTest.java` —— 22 例
- `src/test/java/com/starcon/reporting/AffiliateMarketingTenancyIntegrationTest.java` —— 4 例（三条隔离断言）

**前端**

- `apps/web/src/components/analytics/affiliate-marketing-model.ts` + `-model.test.ts` —— 展示格式化与口径标签
- `apps/web/src/components/analytics/affiliate-marketing-workspace.tsx` —— 真实工作区
- `apps/web/src/app/analytics/products/affiliate-marketing/page.tsx` —— 页面
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**（数组末尾，既有 249 条一字未改）

**契约 / 台账 / 文档**

- `packages/api-contracts/src/contracts/reporting.ts`、`packages/api-contracts/src/reporting.openapi.json` —— codegen 产物（纯增量）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 13.3.2.2 那一行
- 本文件

## 后续待办

- **14 广告**：广告点击归因（末次点击 / 首次点击）**仍未交付** ⇒ 本模块的 ROI 与它**不是同一个数**，
  页面上已写明；两者将来同时出现时必须**分列**。
- **2.1.2.1 平台同步**：站外加购行为数据一旦接入，`AFFILIATE_ADD_TO_CART` 的 `available` 翻成 true 即可
  （口径表已登记，DB 触发器已放开该指标的落库路径）。
- **4.2.1.1 刊登编辑器**：关联视频营销位建模一旦交付，`RELATED_VIDEO` 的 `caliber` 与可算性矩阵要补。
- **12.4 / 13.4 利润**：归因成交金额与佣金成本是**真实成本**，但利润侧由 12.4 成本分摊与 13.4 报表负责，
  本模块**不订阅、不推送**。
- **上游归因列表没有时间范围筛选** ⇒ 本模块拉回后自己按归因时刻切窗口。窗口外与无归因时刻的记录
  在刷新结果里如实报出条数。若上游将来支持时间筛选，可省掉全量拉取。
