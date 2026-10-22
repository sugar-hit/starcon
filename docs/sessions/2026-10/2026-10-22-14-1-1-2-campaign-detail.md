# 2026-10-22 14.1.1.2 活动详情（channel 服务 · 卷 14 广告与增长 · L3 锚点 14.1.1）

## 摘要

交付能力蓝图 **14.1.1.2 活动详情**（阶段 D7，L3 锚点 14.1.1）。

蓝图职责句：「单活动全景：基本设置、预算与消耗曲线、广告组列表、投放商品、关键词、创意（视频/图片）、
每日表现明细、平台侧修改历史。输入：campaign_id；聚合子资源。输出：详情视图 + 操作入口（调预算、改竞价、
暂停、复制结构）。」加粗句：「**"复制结构"是高价值操作**：把跑得好的活动结构复制到新店铺/新 ASIN，
是广告提效的核心动作。」

本车道在 `feat/14-1-1-2` 分支上从集成头 `integration/module-dev`（fa73a14）切出，
迁移版本号 **V277**（本批预分配：14.1.1.2=V277 / 14.2.2.1=V278 / 14.3.1.1=V279 / 14.3.2.2=V280）。
**未改集成头任何文件、未执行 `git worktree add/remove`、未 merge、未 push。**

## 决策摘要

1. **广告组 / 投放商品 / 创意这三类结构化数据在 V277 里自建表**。
   理由：14.1.2.1 广告组管理**未交付**，14.1.3.1 关键词管理**只管关键词自己的报表** ⇒
   本批这三类结构**没有别的落点**。照抄 V273 的建表与策略写法（tenant_id NOT NULL + ENABLE/FORCE RLS
   isolation + data_scope RESTRICTIVE 按活动→店铺收敛 + GRANT / `starcon_app` 零权限）。
   同时**自带结构登记写入口** `POST /campaigns/{uuid}/structure`（语义是**替换**）——
   平台广告 API 同步器尚未交付，结构必须有真的落点，否则详情页只有一张空表，
   而空表会被界面渲染成「这个活动没有广告组」。

2. **🔴 平台侧修改历史：显式不可用，不建表**。
   上游**没有平台广告 API 同步器 ⇒ 没有同步源**。`ad_campaign_operation_log`（V273）是**本地**操作审计，
   不是平台侧改历史。做法：`changeHistory.available=false` + `ownerModule` + `impact` 做成**结构里的字段**，
   当前展示的是**本地**操作审计，字段名与文案都分得开。
   **不建**「平台改历史」表 —— 没有同步源的空表会被界面渲染成「没有改过」，那比明说「拿不到」严重得多。

3. **🔴 关键词不建表，读上游 14.1.3.1 的 `ad_keyword`**（V274）。
   V274 的表按**平台侧真实关键词 id** 建（改价 / 否定 / 报表都指着它），且刻意用
   (platform_id, shop_id, external_campaign_id) 外部引用、不跨车道建 FK。
   本模块**照它的口径读**，不建第二张表 —— 同一个词在两处各有一份，改价就会改到不同的地方。

4. **🔴 复制结构：结构副本是真的，平台侧还不是**。
   - 结构**真的落库**：目标活动 + 广告组 + 投放商品 + 创意全部写进库，记 `ad_campaign_structure_copy`。
   - **平台侧还没有**：平台回写队列未交付 ⇒ 目标活动 `pending_platform_writeback=true` + 状态 `PAUSED`
     （复制出来不投放）+ 平台侧 id 落成 `LOCAL-COPY-{uuid}` —— **本地标记，不是像平台 id 的字符串**
     （编一个像平台 id 的串，会让「同步回读」把这条草稿认成平台活动）。
   - **关键词不复制**：平台侧还没建这个活动，关键词 id 无从分配；往「平台原文」表里塞本地编的 id
     比少复制一段严重得多。`ad_campaign_structure_copy.keyword_count` 由 **CHECK 钉死为 0**，
     让「关键词没被复制」成为**可查的事实**而不是一句说明。
   - **复制的是结构不是钱**：目标活动的预算与投放期**不跟着复制**（预算要用「调预算」单独设）。

5. **调预算 / 暂停复用上游 14.1.1.1 的 `bulk-operations`**（单条 id）。
   护栏、审计、「本地已改、尚未回写平台」的标记都在那边，本模块不另起一套。
   改竞价是本模块自己的动作（广告组默认竞价）—— 14.1.2.1 拥有「默认竞价」这个字段但**未交付**，
   而 14.1.1.2 的职责句明确点了「改竞价」这个操作入口，两者不冲突。

6. **只画「有每日指标的天」**。
   没有指标行的一天是「没数据」，不是「花了 0」—— 画成 0 会让人以为「那天没花钱」，
   而真相是「那天没同步到数据」。日预算为空（平台侧没有预算，如 DSP 固定价）⇒
   **预算使用率不可比（null）而不是 0**。

7. **api-proxy 白名单新增一条独立元素，首 token `ad-campaign-details` 零命中**。
   插入锚点固定在 `compliance-precheck`（16.2.2.1）那条**之前**（与同批另外三条车道的插入点相距数百行 ⇒
   git 能干净求并）。既有 `ad-campaigns` / `ad-keywords` / `ad-rules` / `ad-type-diffs` 一字未动、也不重排。

## 关键 trade-off

| 取舍 | 选择 | 放弃的东西 / 理由 |
| --- | --- | --- |
| 平台侧改历史 | 显式 `available=false` + 本地审计 | 少一个「看起来很全」的 Tab。理由：没有同步源，表建出来也是空的，而空表 = 界面说「没改过」 |
| 关键词落点 | 读上游 V274 | 放弃「自己有一张完整表」的整齐。理由：重复表 ⇒ 改价改到不同的地方 |
| 复制结构的目标活动 | 落成 `ad_campaign` 里的一条本地草稿 | 放弃「复制计划」这种更轻的形态。理由：蓝图说这是核心动作，副本真落库才谈得上「提效」；代价是目标活动进 14.1.1.1 的列表（用 `LOCAL-COPY-` 前缀 + 暂停 + 备注标明） |
| 复制是否带预算 | **不带** | 放弃「一次复制到位」。理由：结构是结构，钱是钱；新活动带预算 + 一次点击就是新活动开始烧钱 |
| 改竞价的审计留痕 | APPLIED / REJECTED / SKIPPED **三种都留** | 多写两行 INSERT。理由：只留成功的那几条，事后查不出「为什么这条没生效」 |
| `ad_campaign_group_bid_log.group_id` 的外键 | **不建 FK** | 放弃一条引用完整性。理由：审计要能记下「我想改的那个组压根不存在」—— 有 FK 这条审计自己写不进去 |
| 契约 diff 体量 | 接受 codegen 的整体重排 | 放弃「只改我那 10 个 path 的最小 diff」。理由：契约是 codegen 产物，禁止手改；**语义**增量实测为 +10 path / +29 schema / 0 删除 / 0 既有类型被改 |

## 文件清单

**迁移（新增）**
- `backend/services/channel/src/main/resources/db/migration/V277__ad_campaign_details.sql`
  —— 5 张表（`ad_campaign_group` / `ad_campaign_group_product` / `ad_campaign_creative` /
  `ad_campaign_group_bid_log` / `ad_campaign_structure_copy`）+ 索引 + RLS + data_scope + GRANT。

**后端（新增）**
- `backend/services/channel/src/main/java/com/starcon/channel/AdCampaignDetailModels.java`
  —— 传输模型（能力声明 / 全景 / 预算曲线 / 各子资源 / 改竞价 / 复制结构 / 结构登记）。
- `backend/services/channel/src/main/java/com/starcon/channel/AdCampaignDetailService.java`
  —— jOOQ 显式 SQL 全部落点（无 JPA 懒加载）。
- `backend/services/channel/src/main/java/com/starcon/channel/AdCampaignDetailController.java`
  —— 10 个端点，挂在 `/channel/ad-campaign-details/**`。
- `backend/services/channel/src/test/java/com/starcon/channel/AdCampaignDetailIntegrationTest.java`
  —— 17 例（Testcontainers PostgreSQL 16）。

**前端（新增）**
- `apps/web/src/components/ads/ad-campaign-detail-model.ts`
- `apps/web/src/components/ads/ad-campaign-detail-model.test.ts`（23 例）
- `apps/web/src/components/ads/ad-campaign-detail.tsx`
- `apps/web/src/app/ads/campaigns/[id]/page.tsx`
- `apps/web/src/app/api/channel/ad-campaign-detail-allowlist.test.ts`（5 例）

**共享文件（按多车道规则各自改）**
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**，既有条目一字未动。
- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts`
  —— codegen 产物（+10 path / +29 schema / 0 删除）。

**台账**
- `docs/product/capability-blueprint/module-progress.md` —— **只改 14.1.1.2 那一行**的「状态」「证据」两格。

## 首 token 零命中核对结论

`ad-campaign-details` 在 `apps/web/src/lib/api-proxy.ts` 既有 100+ 条 `services[]` 正则里
**一次都没出现**（插入前 `grep -c "ad-campaign-details"` = 0；既有的是 `ad-campaigns` /
`ad-keywords` / `ad-rules` / `ad-type-diffs` 一类）。插入后该串只出现在**本车道新增的这一条元素**里。
⇒ 首 token 既是独立字面量，也不会被既有分支的前缀吃掉。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | ✅ 通过 |
| L0 类型 | `pnpm typecheck` | ✅ 3 包绿 |
| L1 模块 | `./mvnw -pl services/channel -am test -Dtest='AdCampaignDetail*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ **17 例绿**（Failures 0 / Errors 0） |
| 前端模型 | `pnpm vitest run apps/web/src/components/ads` | ✅ **115 例绿**（5 文件） |
| 前端白名单全量回归 | `pnpm vitest run apps/web/src/app/api/channel apps/web/src/app/api/trade` | ✅ **608 例绿**（116 文件） |
| Lint | `pnpm lint` | ✅ 绿（仅既有文件的历史 warning，本车道文件零告警） |
| 契约 | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | ✅ channel **+10 path / +29 schema / 0 删除** |

**未跑**（按任务书要求交给调度者在集成头上每批跑一次）：`./mvnw verify` 全量、整个 channel 服务的测试套件。

## 后续待办

1. **平台侧修改历史** —— 等平台广告 API 同步器（channel/vendor）交付后补同步源，
   届时本模块的 `changeHistory.available` 才能翻成 `true`。
2. **平台回写** —— 队列交付后，暂停 / 启用 / 调预算 / 改竞价 / 复制结构都要能真正下发平台；
   在那之前界面上一直显式标「待回写平台」。
3. **14.1.2.1 广告组管理** —— 接管广告组的日常管理（定向设置、组级报表入口、组级增删改）。
   本模块已把结构表与「复制结构」铺好，14.1.2.1 可以在 `ad_campaign_group*` 上直接建。
4. **14.2.1.1 竞价管理** —— 竞价策略（自动 / 动态 / 目标 ACOS 反推）；本模块只做「把默认竞价改成多少」。
5. **14.1.3.2 投放定位** —— 本模块只存平台原文的定向字段，不解读也不给建议。
6. **14.3.1.1 活动报表** —— 可下钻的报表归它；本模块只给活动自身的每日明细。
7. **复制结构里的关键词** —— 等平台侧真创建活动、分配关键词 id 之后才能补上。
8. **契约重排噪声** —— 本车道重新生成契约时 `channel.openapi.json` 的 `paths` 键序整体重排
   （SpringDoc 的 map 迭代序不稳定），**语义**增量实测为 +10 path / +29 schema / 0 删除 / 0 既有类型被改。
   合并驱动按「类型名 / OpenAPI 路径」求并时应能干净处理；若求并报冲突，按语义增量手工保留 10 条新 path 即可。
