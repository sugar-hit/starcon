# 14.5.1.1 达人库（2026-10-22）

> 车道 `14.5.1.1`，分支 `feat/14-5-1-1`，基线 `integration/module-dev`（c4f28a45）。
> 蓝图原文：`docs/product/capability-blueprint/14-ads-and-growth.md` → `### 14.5.1.1 达人库`。
> 归属：**channel 服务**（L2 表 14 广告与增长 → channel，与 14.1 ~ 14.4 各模块同族）。

## 决策摘要

1. **手动录入完整做完，平台同步只做「端口 + 未接入状态」。**
   蓝图的输入写的是「达人数据同步（平台接口**或**手动录入）」；channel 的适配层
   （`channel/vendor`）目前**只有 13 个 OAuth 适配器**，没有任何达人数据适配器。
   所以新增端口 `InfluencerDataPort`（取数，不落库）+ 当前实现
   `UnavailableInfluencerDataPort`（对所有平台答「未接入」，**不返回任何达人**）。
   `POST /channel/influencers/sync` 在适配器未接入时明确返回 409 且**不写任何数据** ——
   编一个假平台接口、或返回硬编码达人列表，都是假事实。

2. **粉丝层级由 DB 触发器推导，服务层写什么都不作数。**
   粉丝层级是粉丝数的函数；写成普通列的话「改粉丝数忘了改层级」就会造出
   「10 万粉标成 MACRO」的行，而筛选按层级走 ⇒ 错标会直接把优质达人筛掉。
   照抄 V273 `ad_campaign`「归一类型那三列由档案说了算」的同一手法。

3. **评级是「算出来的」，不是手填的标签。**
   蓝图同时要求「评级」与「效果数据必须入库，否则无法筛选优质达人」⇒ 评级必须由效果数据算出来。
   评分 = GMV 权重 × min(GMV/上限,1) + 互动率权重 × min(互动率/上限,1)
   + 退款率权重 × (1 − min(退款率/上限,1))；三个权重之和被 DB CHECK 钉死为 1。
   **必需的效果数据缺任何一项 ⇒ 评分与档位都是 null（不是 0 分）**；
   口径未配置 ⇒ 全库暂无评分。0 分会被读成「这个达人很差」，真相是「你还没录」。

4. **「没录」一律 null，「不可比」不是 0。**
   GMV / 互动率 / 退款率 / 评分在迁移、模型、界面三层都保持 null 语义。
   0 是「录了，而且真的是零」；null 是「压根没录」。

5. **黑名单是独立事实，且从正常检索里排除。**
   `influencer_blacklist` 单独成表（拉黑原因封闭词表 + 生效时刻 + 解除记录留痕），
   partial unique index 保证同一达人同时只有一条生效中的黑名单。
   达人列表**默认排除**生效中的黑名单（`includeBlacklisted=true` 才可见）。
   解除是留痕（解除时刻 + 理由），不是删行；有黑名单 / 合作历史的达人是**举证凭据**，
   不许物理删除（返回 409，请改用停用）。

6. **合作历史挂真实活动。**
   `influencer_cooperation.campaign_id` → `ad_campaign(tenant_id,id)` 复合 FK（同库同服务）。
   自由文本的活动名答不出「这个达人跟哪几个活动合作过」；且**达人与活动必须在同一店铺**
   （达人挂店铺下，跨店铺挂合作会让「按店铺收敛的可见性」与事实对不上）。

## 关键 trade-off

- **档案 GMV vs 合作历史合计 vs 归因口径，三者分开。**
  14.5.2.1 带货归因未交付 ⇒ 归因口径（订单归因 + 佣金成本）没有。
  所以只给两个口径：`influencer.gmv`（档案入库值）与 `gmvFromCooperations`
  （本地按合作行求和），界面上分列两列并写明归因口径归 14.5.2.1。
  *代价*：运营看到的不是一个「总 GMV」；*收益*：不会把两个不同口径的数加成一个假的总数。
- **评级由服务层算并落库，而不是每次查询现算。**
  档案写库时即重算评分与档位（改效果数据 → 评分跟着变）。
  口径或档位改动 ⇒ 全库重算。*代价*：口径改动是 O(租户达人数) 的写操作；
  *收益*：筛选按 `rating_score` 走索引，且「界面上这个达人是几分」永远与库里一致。
- **标签分组与评级档位是「租户级配置事实」，不按店铺收敛。**
  与 V284 `ad_promotion_guardrail`、V278 `ad_budget_rule` 同一纪律；
  达人档案 / 标签关联 / 黑名单 / 合作历史则按店铺收敛（data_scope RESTRICTIVE 策略）。
- **契约只加自己的部分。**
  集成头里 `packages/api-contracts/src/contracts/channel.ts` 相对 `channel.openapi.json`
  **本来就有别的车道留下的漂移**（缺 14.1.3.1 批量操作 / 14.3.1.2 组词报表 / 14.4.1.1 促销列表的类型），
  且两个文件的小数写法不一致（`0.000001` vs `1e-06`）。
  本车道的处理：**跑 codegen，但只提交自己的增量** ——
  `channel.openapi.json` 相对集成头是**纯插入**（+20 path +32 schema，difflib 验证只有 insert opcode），
  `channel.ts` 是 codegen 的权威输出（因此顺带补上了那三块缺失的类型），
  trade 与其余五个服务的契约文件**逐字还原**为集成头版本。

## 文件清单

### 迁移（1）
- `backend/services/channel/src/main/resources/db/migration/V288__influencer_library.sql`
  — 八张新表：`influencer` / `influencer_tag_group` / `influencer_tag` /
  `influencer_tag_assignment` / `influencer_rating_tier` / `influencer_rating_rule` /
  `influencer_blacklist` / `influencer_cooperation`。
  零 ALTER 零 DROP 既有表；全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS +
  `isolation` 策略 + `data_scope`（RESTRICTIVE，按店铺收敛）+ GRANT/REVOKE；
  金额 NUMERIC(19,4)、比率 NUMERIC(19,6)、粉丝数 BIGINT、时间列 timestamptz；
  索引按「租户 + 类目 + 粉丝层级 + GMV」「租户 + 标签」「租户 + 黑名单」建。

### 后端（5）
- `backend/services/channel/src/main/java/com/starcon/channel/InfluencerModels.java` — 传输模型
- `backend/services/channel/src/main/java/com/starcon/channel/InfluencerRules.java` — 纯计算规则（评分 / 粉丝层级 / 封闭词表）
- `backend/services/channel/src/main/java/com/starcon/channel/InfluencerService.java` — jOOQ 显式 SQL
- `backend/services/channel/src/main/java/com/starcon/channel/InfluencerController.java` — 20 条路径 / 30 个动作
- `backend/services/channel/src/main/java/com/starcon/channel/vendor/InfluencerDataPort.java` + `UnavailableInfluencerDataPort.java` — 平台达人数据端口与「未接入」实现

### 测试（3 + 2 前端）
- `backend/services/channel/src/test/java/com/starcon/channel/InfluencerRulesTest.java` — 8 例
- `backend/services/channel/src/test/java/com/starcon/channel/InfluencerIntegrationTest.java` — 16 例
- `backend/services/channel/src/test/java/com/starcon/channel/InfluencerTenancyIntegrationTest.java` — 3 例（多租户三断言）
- `apps/web/src/components/ads/influencer-model.test.ts` — 15 例
- `apps/web/src/app/api/channel/influencer-allowlist.test.ts` — 5 例

### 前端（4）
- `apps/web/src/components/ads/influencer-model.ts` — 模型 + 接口 + 展示辅助 + 表单校验
- `apps/web/src/components/ads/influencers.tsx` — 真实工作区（五个页签）
- `apps/web/src/app/ads/influencers/page.tsx` — 页面
- `apps/web/src/lib/api-proxy.ts` — **新增一条 `influencers` services[] 元素**（追加在 `purchase-followups` 那条之后）

### 契约 / 台账 / 会话（3）
- `packages/api-contracts/src/channel.openapi.json`、`packages/api-contracts/src/contracts/channel.ts`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md` — **只改 14.5.1.1 这一行**
- 本文件

## 验证

- `./mvnw -q compile` → 退出 0
- `./mvnw -pl services/channel -am test -Dtest='Influencer*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **27 例 0 失败 0 错误**（8 + 16 + 3）
- `pnpm typecheck` → 3/3 通过
- `pnpm --filter @starcon/web lint` → 通过（本车道零告警）
- `pnpm vitest run apps/web/src/components/ads/influencer-model.test.ts apps/web/src/app/api/channel/influencer-allowlist.test.ts`
  → 20 例全绿
- `pnpm vitest run apps/web/src/app/api` → **163 文件 946 例全绿**（全量 allowlist 回归：
  白名单是累积型正则，取错一侧会静默删掉别人已交付的路由，症状是页面 404 且所有静态检查全绿）

## 后续待办

1. **平台达人数据适配器未交付** ⇒ 达人数据目前只能手动录入。
   `InfluencerDataPort` 是那条接缝：某个平台接上适配器后，同一个端点自动放行，前端不用改。
2. **14.5.1.2 建联与样品未交付** ⇒ 本模块只留档案 + 标签 + 评级 + 黑名单事实待其消费。
3. **14.5.2.1 带货归因未交付** ⇒ GMV / 佣金只有本地口径（档案值 + 合作记录合计），**没有归因口径**；
   归因结果到位后由它接管（届时档案 GMV 的口径归属要重新确认一次）。
4. ⚠️ **集成头既有问题（非本车道引入，需调度者处理）**：
   `docs/product/capability-blueprint/module-progress.md` 里 **14.1.2.1 广告组管理**那一行
   **只有 6 根竖线**（缺结尾竖线），`node tools/module-progress/sync.mjs` 解析不到它
   ⇒ 会把它的「已交付」回落成「未开发」并把证据抹平成 `—`。
   本车道因此**没有运行 sync.mjs**（只手工改了自己那一行）；
   集成头上建议先补上那一根竖线再跑一次 sync.mjs，让总览计数与明细重新对齐。
