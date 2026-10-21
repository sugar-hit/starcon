# 批次 60 —— 广告与增长三模块交付（投放定位 / 优惠券与秒杀 / 达人库）

- **日期**：2026-10-22（05:00 tick）
- **集成头**：`integration/module-dev`，本批 3 个 merge commit：`1590fba4` / `6d480c40` / `7c1e688f`
- **本批口径**：L2 批次闸验证并随本批 push 的模块 = 3 个（下节）。另有 5 条车道本轮已 `land`，
  按协议在**下一轮 tick** 走 `merge-ready` 并由下一轮 L2 验证，不在本批 push 范围内（见文末「已落地待并入」）。

## 本轮 L2 批量验证结果（唯一一次全量）

| 层 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && ./mvnw verify` | ✅ `MVN_EXIT=0` — tenancy / identity / catalog / channel / warehouse / trade / crm **全部 SUCCESS**，**7841 例 0 失败 0 错误** |
| 类型 | `pnpm typecheck` | ✅ `TYPECHECK_EXIT=0` |
| Lint | `pnpm lint` | ✅ `LINT_EXIT=0` |
| 前端全量 | `pnpm test` | ✅ `TEST_EXIT=0` — **446 文件 / 6650 例全绿** |
| Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ `BUILD_EXIT=0` |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | ✅ `SYNCCHECK_EXIT=0` |

静态预检（本批 3 个新迁移 `V285` / `V286` / `V288`）：`ADD COLUMN` 无 `DEFAULT` **0 命中**、
`SET NOT NULL` **0 命中**、`ADD CONSTRAINT ... CHECK` **0 命中** ⇒ 无「已知互相破坏形状」。

⚠️ **本批的 L2 不是一次就绿**：第一轮后端红（`trade` 模块），经取证确证是**环境假红**而非本批回归，
在干净窗口重跑整批 L2 才绿。完整取证与处置见
[`docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` §八](../backlog/2026-10-13-flaky-integration-tests-l2-false-red.md)
与 [`docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`](../backlog/2026-10-22-testcontainers-pg-container-leak.md)。
**如实记录：本批的 push 建立在「假红排查 + 干净窗口整批重跑全绿」之上，不是首次全量绿。**

---

## 模块 14.1.3.2 投放定位

**这个功能做什么**：把广告活动的「投给谁」从平台原文变成可经营的数据 —— 运营能录入归一后的定位设置
（地理 / 人群 / 设备 / 时段 / 匹配类型）、看到每个定位维度的真实效果对比、拿到**带依据数值**的调整建议，
并在证据充分时收窄投放。省掉的是「花了钱零转化还不知道该停哪个定向」。

**技术落地**：

- **迁移**：`backend/services/channel/src/main/resources/db/migration/V285__ad_targeting.sql`
  —— **纯新建三张表、零 ALTER 任何既有表**（跨车道 DDL 回归面 = 0）：
  - `ad_targeting_setting`（定位设置：归一后的维度 GEO/AUDIENCE/DEVICE/DAYPART/MATCH_TYPE/UNMAPPED，
    活动级与组级皆可挂；**时段窗口三列要么全空要么全有**；`bid_factor` / `daypart_hours` 均 `NUMERIC(19,6)`；
    同归属同维度同取值用**唯一索引 + coalesce** 钉死）
  - `ad_targeting_metric`（定位效果指标：展示/点击/转化为**整数计数**，花费/销售额 `NUMERIC(19,4)`，
    `synced_at` 显式，批次幂等键，CHECK 点击数 ≤ 展示数）
  - `ad_targeting_operation_log`（操作留痕：六种写动作，APPLIED/REJECTED/SKIPPED 三种结果都留痕）
  —— 三表 `tenant_id NOT NULL` + **ENABLE 与 FORCE** RLS `isolation` + `data_scope`（RESTRICTIVE）按活动 → 店铺收敛
  + GRANT（`starcon_app` 零权限）。
- **端点**：12 个挂在 `/channel/ad-targetings/**`（jOOQ 显式 SQL，不用 JPA 懒加载）：
  `POST /interpret`（解读归一）· `GET/POST /`（设置增删查）· `GET /summary` · `GET /{uuid}` ·
  `GET /{uuid}/operations` · `PATCH /{uuid}` · `DELETE /{uuid}`（软删除，历史指标与留痕仍在）·
  `GET /effects`（效果对比表）· `GET /suggestions`（调整建议）· `POST /{uuid}/narrow`（收窄投放）·
  `GET /daypart-profile`（供下游 14.2.1.2 分时策略复用）· `POST /metrics`（定位维度效果数据同步，真实写入口）。
- **页面**：`/ads/targetings` 真实工作区（**非占位页**）—— 多维筛选栏 + 真分页清单 + 设置编辑 +
  效果对比表 + 建议面板 + 操作留痕视图；**加载态 / 空态（区分「筛选无结果」与「还没有设置」）/ 错误态齐全**；
  金额与数量在 JS 侧只原样展示，**不做任何前端算术**。
- **测试**：`AdTargetingIntegrationTest` **12 例** + `AdTargetingTenancyIntegrationTest` **3 例**
  （Testcontainers 真实 PostgreSQL 16，含租户隔离三断言）—— 本轮 L2 全量跑**实测 15 例 0 失败**。

**关键设计决策**：

1. **不改写上游 `V281` 的列语义**。上游 `ad_campaign_group.targeting_settings` / `match_type` 继续存**平台原文**
   （其注释写明「解读归 14.1.3.2」），解读/归一化结果落在本模块自己的表上并**保留原文** ——
   覆盖上游原文会让「平台侧真实配置」不可回溯。
2. **认不出来的键落 `UNMAPPED` 而不是丢弃**；`UNMAPPED` 是**显式合法值**不是兜底；
   时段取值解析不出小时窗口时**窗口留白，不编窗口**。
3. **派生比率分母为 0 时给 `null`（不可比）而不是 `0`**；汇总**金额按币种分列、不跨币种相加**，
   只有计数才相加 —— 混币相加出来的 ACOS 是不可审阅的账。
4. **建议必须带「依据数值 + 口径 + 可执行性」**：`WASTED_TARGETING` / `ACOS_OVER_TARGET` /
   `UNDERPERFORMING` / `SCALE_UP` / `DIMENSION_SPREAD` 五条规则现算；
   **样本不足（花费/展示/点击未达门槛）时 `actionable=false`** —— 宁可少给一条，
   也不给一条基于噪音的建议。
5. **收窄投放有证据门槛**：必须「花费 ≥ 门槛且零转化零销售」才放行，否则 409；
   人工覆盖要显式 `override=true` + 理由并留痕；只落本地并标「待回写平台」。
6. **本模块不执行出价调整**。改竞价是钱，走 14.2.1.1 的护栏入口；本模块只给**系数建议**与**系数记录** ——
   两个端点改同一笔钱，审计就会分叉。能力声明里 `platform_writeback` / `bid_adjustment_execution` /
   `platform_targeting_sync` 三段显式 `available=false` + `ownerModule`。
7. **`ad_targeting_operation_log.setting_id` 故意不建 FK** —— 这样才能记下「目标压根不存在」这件事。

---

## 模块 14.4.1.2 优惠券与秒杀

**这个功能做什么**：运营能建优惠券（满减 / 折扣 / 买赠）、批量发券码、记录领取与核销，
以及给单个商品开秒杀活动 —— 每一张券的让利额、每一次核销的订单、每一笔补贴成本都落在数据库里可查可审。

**技术落地**：

- **迁移**：`backend/services/channel/src/main/resources/db/migration/V286__ad_coupons.sql`
  —— **纯新建、零 ALTER 任何既有表**，只**只读**引用 V1 `platform` / V1 `shop`（复合 FK 带 `tenant_id`
  ⇒ 跨租户挂载物理上不可能）、V171 `listing`（同库同服务 4.1.1.1，秒杀针对单个商品）、
  V284 `ad_promotion`（同库同服务 14.4.1.1，券可挂促销下）。
  **七张表** `tenant_id NOT NULL` + ENABLE/FORCE RLS `isolation` + `data_scope`（RESTRICTIVE）按**店铺**收敛
  + GRANT（`starcon_app` 零权限）：
  `ad_coupon`（券定义 + 库存/计数三列 + 按人限领 + 券码生成规则 + 本地状态机 + 平台同步状态）·
  `ad_coupon_code`（券码，`UNIQUE(tenant_id, code)` 带租户维度，状态机 GENERATED→CLAIMED→USED）·
  `ad_coupon_channel`（站内/站外/社媒投放计划）· `ad_coupon_claim`（领取记录，PULL/PUSH/CHANNEL 三种方式）·
  `ad_coupon_redemption`（核销记录）· `ad_flash_sale`（秒杀活动）· `ad_flash_sale_claim`（秒杀领取与核销）。
- **端点**：`/channel/ad-coupons/**`（10 path / 15 端点：`capabilities` `options` `coupons` 增查改查 /
  `coupons/{uuid}/status` / `codes` / `claims` / `redemptions` / `statistics`）与
  `/channel/ad-flash-sales/**`（8 path / 10 端点，同构）。
- **页面**：`/ads/coupons` 与 `/ads/flash-sales` 两个真实工作区（**非占位页**）——
  券列表真分页真筛选 + 券码批量生成 + 领取/核销登记 + 统计面板 + 秒杀场次与库存面板；
  加载态 / 空态 / 错误态齐全；金额只原样展示，**JS 侧不做算术**。
- **测试**：`AdCouponIntegrationTest` **18 例** + `AdCouponTenancyIntegrationTest` **3 例** ——
  本轮 L2 全量跑**实测 21 例 0 失败**。

**关键设计决策**：

1. **定价在 catalog 服务**（V231 `pricing_template` / V236 `pricing_rules`）⇒ **禁止跨服务 SQL**
   （不 join、不建跨库 FK、不建共享视图）⇒ 券的门槛 / 面额 / 折扣率**只能是建券时登记的快照**
   （`threshold_amount` / `discount_value` / `discount_rate` / `max_discount_amount` 全是 `NUMERIC` 列），
   核销时用这份快照算让利额。**快照会与后来改的价目不一致，但那是可审计的业务事实，不是 bug。**
2. **折扣口径三选一由 DB CHECK 钉死**（折扣码给折扣率**或**面额互斥 / 满减给门槛 + 减免额 /
   买赠给买 N 送 M 且不许给任何金额口径）—— 两个都填会让「让利多少」有两个答案，那是不可审阅的账。
3. **计数列自洽由 DB CHECK 兜底**（`generated_count <= total_stock`、`claimed_count <= generated_count`、
   `redeemed_count <= claimed_count`），**库存扣减走显式 SQL + 行锁**（`select ... for update`）——
   不用懒加载掩盖竞态。
4. **核销必须带订单标识**：没有订单标识的核销**写不进来**（`NOT NULL`），
   `UNIQUE(tenant_id, coupon_id, order_ref)` 防重复核销。
5. **补贴成本买赠券写 `null` = 不可测算，不是 0**（⛔ 不写 0）—— 0 会被下游当成「没花钱」。
6. **平台营销接口未交付** ⇒ 平台同步状态只有「本地已建、平台侧还没有」
   （`LOCAL_ONLY` + `pending_platform_writeback=true`，DB CHECK 保证「已同步」与「待回写」不能同时成立）——
   **不 mock 成已回写**。
7. **15 客户未交付** ⇒ 定向发放**自包含**地做完（发放记录、渠道投放计划都落在自己的表里，
   收件人以标识字符串记录），不假装能发站内信。

---

## 模块 14.5.1.1 达人库

**这个功能做什么**：运营能建达人档案、按类目/粉丝量/标签/评级筛选达人、打标与拉黑、
配一套评分口径让「达人评级」由效果数据算出来，并沉淀每一条合作历史。

**技术落地**：

- **迁移**：`backend/services/channel/src/main/resources/db/migration/V288__influencer_library.sql`
  —— **八张新表、零 ALTER 零 DROP 既有表**（跨车道 DDL 回归面 = 0）：
  `influencer`（达人档案：平台 + 店铺复合 FK、粉丝数、互动率 / 带货 GMV / 退款率 / 评分 / 评级档位、
  数据来源与平台同步状态两列）· `influencer_tag_group` · `influencer_tag` · `influencer_tag_assignment` ·
  `influencer_rating_tier`（评级档位：分数区间 + 排序位）· `influencer_rating_rule`（评分口径）·
  `influencer_blacklist`（拉黑原因封闭词表 + 生效时刻 + 解除留痕）· `influencer_cooperation`（合作历史）。
  全部 `tenant_id uuid NOT NULL` + **ENABLE 与 FORCE** RLS `isolation` +
  `GRANT SELECT,INSERT,UPDATE,DELETE TO channel_app` + `REVOKE ALL FROM starcon_app`；
  金额与数量一律 `NUMERIC`（GMV / 佣金 `NUMERIC(19,4)`、互动率 / 退款率 / 评分 `NUMERIC(19,6)`）、
  粉丝数 `BIGINT`、时间列 `timestamptz`（服务层 `OffsetDateTime` 绑定 + values 位置写 `?::timestamptz`）。
- **端点**：**20 条路径 / 30 个动作**挂在 `/channel/influencers/**`：
  `capabilities` · `options` · `sync-status` · `sync` · `profiles`（真分页 + 多维筛选 + 真排序）·
  `profiles/{uuid}`（详情：档案 + 标签 + 合作历史 + 黑名单）· `profiles/{uuid}/tags`（批量打标）·
  `profiles/{uuid}/tags/{uuid}` · `profiles/{uuid}/rating`（重算评级）· `tags` / `tag-groups` /
  `rating-tiers` / `rating-rules` · `blacklist` + `blacklist/{uuid}/release` · `cooperations`。
- **页面**：`/ads/influencers` 真实工作区（**非占位页**，五个页签：达人库 / 黑名单 / 标签与分组 / 评级 /
  合作历史）—— 多维筛选栏 + 真分页清单 + 录入/编辑/删除 + 详情面板（打标 / 拉黑 / 解除 / 重算评级 /
  合作历史）+ 标签分组与标签维护 + 评分口径与档位维护 + 平台同步能力表（逐平台标注「未接入」与原因）+
  **加载态 / 空态（区分「筛选无结果」与「达人库还是空的」）/ 错误态齐全**。
- **测试**：`InfluencerRulesTest` **8 例** + `InfluencerIntegrationTest` **16 例** +
  `InfluencerTenancyIntegrationTest` **3 例** —— 本轮 L2 全量跑**实测 27 例 0 失败**。

**关键设计决策**：

1. **粉丝层级由 DB 触发器从粉丝数推导**（服务层写什么都不作数，照抄 V273 `ad_campaign` 的同一手法）——
   层级是一个派生量，存两份就迟早不一致。
2. **评分由效果数据算出来**：评分 = GMV 权重 × min(GMV/上限,1) + 互动率权重 × min(互动率/上限,1)
   + 退款率权重 × (1 − min(退款率/上限,1))；权重和由 DB CHECK 钉死 = 1，
   partial unique index 保证一个租户同时只有一套生效口径。
   **必需效果数据缺任何一项 ⇒ 评分与档位都是 `null`（不是 0 分）**；口径未配置 ⇒ 全库暂无评分。
3. **黑名单拉黑后从正常检索里排除**（默认排除生效中的黑名单，`includeBlacklisted=true` 才可见），
   解除留痕（解除时刻 + 理由）；**有黑名单或合作历史的达人是举证凭据，不许物理删除**（返回 409）。
4. **两个 GMV 口径分开**：档案 GMV（入库值）与合作历史合计（本地按合作行求和）——
   **归因口径归 14.5.2.1 带货归因**，本模块不越界。三者不能混为一谈。
5. **平台达人数据适配器未交付**（`channel/vendor` 只有 13 个 OAuth 适配器）⇒ 新增端口 `InfluencerDataPort`
   + 当前实现 `UnavailableInfluencerDataPort`（对所有平台答「未接入」，**不返回任何达人**，
   不编造平台接口也不返回硬编码列表）；**手动录入完整做完**（新建 / 编辑 / 批量打标 / 删除，真落库）。
6. **权限复用 identity 已有的 `channel.operation.*`（identity 服务零改动）。**

---

## 已落地待并入（下一轮 tick 走 merge-ready + 下一轮 L2）

本轮另有 **5 条车道**在后台跑完并 `land`，它们的 commit **不在本批 push 的集成头里**，
由下一轮 tick 的 `merge-ready` 并入、并由那一轮的 L2 批次闸验证：

| 模块 | 名称 | 服务 | 迁移 | worker 验证结果 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 14.4.2.1 | 促销效果分析 | channel | `V287` | 后端 22 例全绿（含租户三断言、毛利下滑负向场景） | `ready` |
| 14.5.1.2 | 建联与样品 | channel | `V289` | 后端 L1 41 例全绿；前端 308 例全绿 | `ready` |
| 14.5.2.1 | 带货归因 | channel | `V290` | 后端 L1 38 例全绿；全量 allowlist 回归 281 例全绿 | `ready` |
| 15.1.1.1 | 消息收件箱 | **crm** | `V291` | 后端 L1 23 例全绿；全量 allowlist 回归 956 例全绿 | `ready` |
| 15.1.2.1 | 邮件收发 | **crm** | `V292` | 后端 L1 25 例全绿；全量 allowlist 回归 956 例全绿 | `ready` |

两处需要下一轮注意的**契约口径**（本轮已由 worker 主动记录）：

1. **14.5.2.1 的 DTO 改名**：codegen 的跨服务同名检测发现 trade 已有 `AttributionRow` / `AttributionPage` /
   `AttributionDetail`（品质归因），故 14.5.2.1 的 DTO 全部加 `InfluencerAttribution` 前缀，
   **而不是写进 collision allowlist** —— 通道契约相对基线是**纯插入**（+11 path / +25 DTO）。
   但 `channel.openapi.json` 的**文本 diff 很大**（SpringDoc 的 path 顺序随新增 controller 变了；
   实测两次连续导出逐字节一致，是确定性导出）。若并入驱动器按文本 merge，这处需人工确认。
2. **15.1.2.1 的权限码**：任务书原写「复用 identity 已有的 `crm.operation.*`」，
   但该前缀**在全仓任何文件里都不存在**（identity 的 permission 表里也没有任何 `crm.` 前缀码）。
   worker 按**兄弟模块实际约定**取 `crm.email.read` / `crm.email.update`
   （对齐 `crm.knowledge.*` / `crm.review.*` / `crm.rule.*`），identity 零改动。

---

## 本轮流水线的两条基础设施发现

1. **Testcontainers PG 容器在单次 `mvnw verify` 内累积不回收** ——
   343 个用 `PostgreSQLContainer` 的集成测试类里有 **97 个从不 `.stop()`**，Ryuk 只在 JVM 退出时按 session 回收，
   于是单个 maven session 内可累积 **62+ 个** PG 容器（容器内存合计约 5.4 GiB）。
   ⇒ `docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`
2. **并发车道手动删容器导致 L2 假红**（本批第一轮 L2 红就是这么来的）——
   另一条车道收尾时手动 `docker rm` 掉了自己的 12 个容器，**连带删掉了正在跑 L2 的 trade 容器**。
   ⇒ `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` §八
   ⇒ **新增纪律**：worker **不得手动 rm/stop 任何 Testcontainers 容器**，交给 Ryuk 按 session 回收。

## 关键文件清单

| 类别 | 路径 |
| --- | --- |
| 迁移 | `backend/services/channel/src/main/resources/db/migration/V285__ad_targeting.sql` · `V286__ad_coupons.sql` · `V288__influencer_library.sql` |
| 后端 | `backend/services/channel/src/main/java/com/starcon/channel/AdTargeting*.java` · `AdCoupon*.java` · `Influencer*.java` |
| 前端 | `apps/web/src/app/ads/targetings/` · `apps/web/src/app/ads/coupons/` · `apps/web/src/app/ads/flash-sales/` · `apps/web/src/app/ads/influencers/` · `apps/web/src/components/ads/*` · `apps/web/src/lib/api-proxy.ts`（新增独立 `services[]` 元素） |
| 契约 | `packages/api-contracts/src/contracts/channel.ts` · `packages/api-contracts/src/channel.openapi.json`（codegen 产物，**只读**，由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成） |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（3 行状态 + 证据） |
| 本说明 | `docs/sessions/2026-10/2026-10-22-batch-60-ads-targeting-coupons-influencer.md` |
| backlog | `docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`（新建） · `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`（§八 追加） |
