# 14.5.2.1 带货归因（2026-10-22）

> 车道 `14.5.2.1`，分支 `feat/14-5-2-1`，基线 `integration/module-dev`（7c1e688f）。
> 蓝图原文：`docs/product/capability-blueprint/14-ads-and-growth.md` → `### 14.5.2.1 带货归因`。
> 归属：**channel 服务**（L2 表 14 广告与增长 → channel，与 14.5.1.1 达人库同族）。

## 决策摘要

1. **归因是「第三个口径」，不是合作口径的改名。**
   14.5.1.1 已经留了两个口径：档案 GMV（`influencer.gmv` 入库值）与合作历史合计
   （本地按 `influencer_cooperation` 行求和）。本模块是**第三个**：按**归因规则**分摊到人
   之后的净额。三者在报表里**分列三栏**，谁也不覆盖谁 ——
   本模块**不改写 14.5.1.1 的任何一列**。口径混成一个数就会高估达人带货的净收益。

2. **归因规则是租户级配置事实，且必须固定并公示。**
   `influencer_attribution_rule` 一个租户**同时只有一套生效口径**
   （partial unique index 钉死，两套口径同时生效时「归因 GMV 是多少」没有答案）。
   规则改了历史报表不可比 ⇒ **每条归因记录自带规则快照**（归因模型 / 分摊基准 / 归因窗口 /
   退款口径 / 佣金口径与比例）与**基数快照**，所以「归因 GMV = 基数 × 权重」永远可复核。
   报表按生效规则汇总，旧规则下的归因仍可按 `ruleCode` 查到。

3. **两个归因模型：合作全额归因 / 同活动内分摊。**
   `COOPERATION_FULL`（一条合作的效果全额归到该达人，权重恒为 1）与
   `CAMPAIGN_POOL_SPLIT`（活动池子按 `FOLLOWER_SHARE` 触达口径或
   `COOPERATION_GMV_SHARE` 业绩口径分给该活动下的各位达人）。
   分摊基准**不可用**时（例如粉丝数合计为 0）返回 null 并**跳过**这条合作，
   **不悄悄退回「全额归因」** —— 那会让「按触达分摊」的规则变成「谁合作多谁拿走」。

4. **🔴 佣金成本只在本服务内真实留痕，没有进利润表。**
   蓝图加粗句「达人佣金是真实成本，必须进利润表」，但 **13.4 利润（reporting 服务）未交付**，
   集成头里**根本没有 reporting 服务** ⇒ 本模块只做到「成本在本服务内留痕」
   （归因行上的金额 / 计提基数 / 成本口径 / 计提状态与时刻），
   能力声明里 `profitIntegration.available = false`（ownerModule 13.4），
   **全模块不写「已同步到利润分析」**。报表里的「扣佣金后净额」**只扣佣金**
   （样品 / 履约 / 平台费不在里面），所以它**不是净收益**。

5. **🔴 佣金不按归因权重缩放；已计提的佣金不自动改写。**
   佣金是**已经发生的成本**，成本不会因为归因口径变了就变；按权重缩放等于用口径粉饰成本。
   同理，**计提是记账事实**：重跑只更新差额 `commission_variance`，
   要改金额得人显式冲销再计提。

6. **归因窗口只挡「新增」，不抹掉已发生的事实。**
   窗口从**合作结束时刻**起算：超窗的合作**不允许新增归因**（账期过了不能补记），
   但**已经归因过的记录不会被后续批次抹掉** —— 已归因是事实，佣金按它计提过。

7. **🔴 平台带货数据适配器未交付 ⇒ 归因数据只来自本地已有事实。**
   `channel/vendor` 只有 13 个 OAuth 适配器，没有任何达人带货数据适配器；
   订单事实又在 trade 服务（跨服务不可读）。所以新增端口 `InfluencerAttributionDataPort`
   + 当前实现 `UnavailableInfluencerAttributionDataPort`（逐平台答「未接入」，
   **不返回任何带货数据**）。`POST /channel/attributions/sync` 未接入时明确 409 且
   **不写任何数据**；适配器接上后落到**本地合作记录的效果数据**上（平台没给的数不覆盖本地已录的值）。

8. **评级重算走既有服务方法，不跨服务 HTTP 自调。**
   归因批次写完后直接调用 14.5.1.1 的 `InfluencerService.applyRating`（同包可见）。
   **评级口径的增删改仍归 14.5.1.1**，本模块不改它的表结构与规则表。

## 关键 trade-off

- **归因落在「合作记录粒度」，不是订单粒度。**
  订单事实在 trade 服务（跨服务不可读），本地只有 14.5.1.1 的合作记录
  （GMV / 订单量 / 佣金 / 退款率）。*代价*：看不到「逐单归因」；
  *收益*：归因数据真的落库了，且每一条都能指回一条真实存在的合作记录（复合 FK）。
- **归因记录是「可重算的事实」，但计提状态不可被重算抹掉。**
  重跑幂等刷新（`UNIQUE(tenant_id,influencer_id,cooperation_id,rule_id)`），
  佣金字段按计提状态分两种处理（PENDING 才刷新）。*代价*：多一个差额字段要人看；
  *收益*：账不会被一次重跑改掉。
- **DB CHECK / partial unique index 把口径纪律钉在库里，而不是只写在注释里。**
  「一个租户只能一套生效口径」「按比例计提必须给比例」「计提状态与时刻必须一致」
  三条都由 DB 约束兜底 —— 服务层漏了也会被拒。
- **契约 DTO 全部带 `InfluencerAttribution` 前缀。**
  codegen 的跨服务同名检测发现 trade 已有 `AttributionRow` / `AttributionPage` /
  `AttributionDetail`（品质归因），本模块改名而不是写进 allowlist ——
  改自己的名字不碰别人的成果。channel 契约相对基线是**纯插入**（+11 path、+25 DTO，
  既有 DTO 逐字未动），index.ts 逐字节不变。
- **api-proxy 新增一条独立的 `services[]` 元素，不并分支。**
  首 token `attributions` 在既有 140+ 条正则里零命中；既有含该串的两条
  （`product-attribution/attributions`、`aftersales/reasons/attributions`）首 token 分别是
  `product-attribution` 与 `aftersales`，且挂在 `trade/` 前缀下 ⇒ 互不吃掉。
  插入锚点选在 `logistics-channels`（10.1.1.2）那条**之后**（物流区），
  与本批其它三条车道的锚点全部不重叠。

## 文件清单

- 迁移：`backend/services/channel/src/main/resources/db/migration/V290__influencer_attribution.sql`
- 后端：`backend/services/channel/src/main/java/com/starcon/channel/InfluencerAttributionModels.java`、
  `InfluencerAttributionRules.java`、`InfluencerAttributionService.java`、
  `InfluencerAttributionController.java`；
  `.../channel/vendor/InfluencerAttributionDataPort.java`、
  `.../channel/vendor/UnavailableInfluencerAttributionDataPort.java`
- 测试：`backend/services/channel/src/test/java/com/starcon/channel/InfluencerAttributionRulesTest.java`、
  `InfluencerAttributionIntegrationTest.java`、`InfluencerAttributionTenancyIntegrationTest.java`
- 前端：`apps/web/src/components/ads/influencer-attribution-model.ts`、
  `influencer-attribution-model.test.ts`、`influencer-attributions.tsx`；
  `apps/web/src/app/ads/attributions/page.tsx`；
  `apps/web/src/app/api/channel/influencer-attribution-allowlist.test.ts`
- 白名单：`apps/web/src/lib/api-proxy.ts`（新增一条 `attributions` 元素，既有条目一字未动）
- 契约：`packages/api-contracts/src/channel.openapi.json`、`packages/api-contracts/src/contracts/channel.ts`
  （codegen 产物，+11 path / +25 DTO）
- 台账：`docs/product/capability-blueprint/module-progress.md`（**只改 14.5.2.1 那一行**）

## 后续待办

- **13.4 利润（reporting 服务）未交付** ⇒ 佣金成本**没有进利润表**。
  13.4 交付后应消费本模块的归因成本（口径：归因行的佣金金额 + 计提状态）。
- **平台带货数据适配器未交付** ⇒ 归因数据只来自本地已录的合作记录。
  适配器接上后 `POST /channel/attributions/sync` 自动放行，无需改前端。
- **订单事实仍在 trade 服务（跨服务不可读）** ⇒ 归因是合作记录粒度；
  要做订单粒度归因需要一条已授权的跨服务读通道。
- 14.5.1.2 建联与样品**未交付** ⇒ 样品 / 寄样成本不在本模块的「投入」里，
  「扣佣金后净额」**不是净收益**。
