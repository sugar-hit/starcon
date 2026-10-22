# 14.1.2.1 广告组管理（阶段 D7，L3 锚点 14.1.2 广告组）

- **日期**：2026-10-22
- **车道**：`feat/14-1-2-1`（worktree `integration/.worktrees/14.1.2.1`）
- **蓝图原文**（`docs/product/capability-blueprint/14-ads-and-growth.md` 101-106 行）：
  > 职责：管理广告组：关联投放商品、匹配类型、定向设置、默认竞价，查看组级表现。
  > 输入：广告组设置；关联商品选择（从 4 listing 中选）。
  > 输出：广告组记录 + 组级报表入口。
  > 关系：上游 = 14.1.1.2；下游 = 14.1.3.1 关键词、14.2.1.1 竞价。**广告组与 SKU 的映射是利润归因的基础**——投放的是 listing，归因要落到内部 SKU。

## 决策摘要

### 1. 🔴 不重复建广告组表 ⇒ 在上游 V277 的事实表上**补列 + 加一条审计表**

14.1.1.2 的台账已经写明「广告组的完整管理归 14.1.2.1」，而 `ad_campaign_group` 是上游已交付的
**唯一**广告组事实表。同一个广告组在两处各有一份，改一边不改另一边，最后谁也说不清
「这个组现在是哪个状态」——所以本模块**不建第二张广告组表**，而是：

- 在 `ad_campaign_group` 上补齐蓝图点名、而 V277 还没有的列：
  `match_type`（匹配类型，平台原文）、`targeting_settings`（定向设置原文串）；
- 补齐**组级**的「本地已改、尚未回写平台」标记：`pending_platform_writeback` + `local_changed_at`；
- 另建**一张** `ad_group_operation_log`（新建组 / 改组设置 / 设投放商品三种动作）。
  V277 的 `ad_campaign_group_bid_log` 只记**改竞价**，其余三种写动作无处留痕。

🔴 三列新增都是 `NOT NULL DEFAULT` —— V277 **已交付**的结构登记与复制结构 INSERT 不带这些列，
缺默认值会让那些老 INSERT 直接违约。

两条写路径的分工写进了迁移注释：`POST /structure` 是**平台侧结构同步登记**（整活动替换语义），
本模块是**运营对单组的日常管理**（单组粒度）。写在同一张事实表上，因为「这个广告组」只有一份真相。

### 2. 🔴 「查看组级表现」没有事实来源 ⇒ **available=false + ownerModule**，不建空表、不冒充

现有事实表里**没有组级指标表** —— `ad_campaign_daily_metric`（V273）是 **campaign_id 维度**的，
**没有 group_id**。三条路里选了最诚实的一条：

- ❌ 把活动级指标冒充组级指标 —— 那是假事实，比缺功能严重得多；
- ❌ 建一张组级指标表 —— 没人写它，空表会被界面渲染成「没有数据」，那比明说「拿不到」严重得多
  （与 14.1.1.2 处理「平台侧修改历史」同一款做法）；
- ✅ 能力声明里 `group_performance_metrics` 维度 **available=false + ownerModule=14.3.1.2 + 影响说明**，
  页面显式写「拿不到 + 责任模块」。

同时按职责句「输出：广告组记录 + **组级报表入口**」把入口交付为 **available=true**，
给出 14.3.1.2 的**路由标识**（前端入口 `/ads/reports` + 稳定标识 `14.3.1.2`）。
🔴 14.3.1.2 同批并行开发 ⇒ 本模块**不 import 它的类、不建它的表、不猜它的 URL 形状**。

### 3. 🔴 改竞价**复用**上游 14.1.1.2 的入口，**不另起同义端点**

蓝图把「默认竞价」列进本模块职责，但 14.1.1.2 已有
`POST /channel/ad-campaign-details/campaigns/{id}/bids`（单次上限 200 / 绝对上限 1000 /
超限逐条拒绝并留审计）。两个端点改同一笔钱，审计就会分叉 ⇒ 本模块**复用**它，
能力声明里 `default_bid` 维度的 `source` 写明「复用上游，不另起同义端点」。

代价：本模块的 `PATCH /groups/{id}` 不接受 `defaultBid`。为避免「静默忽略让调用方以为改成功了」，
请求模型里保留了这个字段并**明确拒绝（400）**并指向正确入口。

### 4. 🔴 平台广告 API 未交付 ⇒ 所有本地写只落本地 + 组级「待回写平台」标记

新建组 / 改组设置 / 设投放商品都把 `pending_platform_writeback` 置 true 并记 `local_changed_at`，
界面显式告诉运营「本地已改、还没回写平台」——否则界面上的「已暂停」会被当成平台侧已暂停，
那是在烧钱的假事实。

新建的组**状态一律 PAUSED**（平台侧还没建这个组，谈不上投放），平台侧 id 落成 `LOCAL-{uuid}` ——
**本地生成的标记，不是平台 id**（编一个像平台 id 的字符串会让同步回读把这条本地草稿认成平台广告组）。
与 14.1.1.2 复制结构时用 `LOCAL-COPY-{uuid}` 同一款纪律。

### 5. 🔴 定向与匹配类型**只存原文**；SKU 只**只读带出映射现状**，不做归因

- 14.1.3.2 投放定位**未交付** ⇒ 定向与匹配类型**只存平台原文、不解读**。
  `match_type` / `targeting_type` / `targeting_settings` 三列在字段名与文案上分得开，
  免得「哪个是匹配类型、哪个是定向设置」在半年后没人分得清。
- 蓝图加粗句「广告组与 SKU 的映射是利润归因的基础」⇒ 投放商品**只读**带出
  `listing_variant.internal_sku_id` 的**映射现状**（跨服务逻辑 ID，不跨库校验）。
  但**花费归因到 SKU 属 14.3.1.3**（未交付）⇒ `sku_attribution` 维度 available=false +
  ownerModule=14.3.1.3。**映射现状 ≠ 归因结果**，页面文案分开说。

### 6. 🔴 金额语义两处「null ≠ 0」

- 组级预算为空 = **继承活动预算**（不是 0）⇒ 页面显示「继承活动预算」，不是 `0.00`；
- 默认竞价为空 = **平台侧没给**（不是 0）。
- 护栏：组级日预算绝对上限 100000（与 14.1.1.1 活动预算同口径）、默认竞价绝对上限 1000、
  单组最多关联 100 个投放商品 —— 上限随 capabilities 返回给界面，运营点下去之前就知道天花板。

## 文件清单

| 类别 | 路径 |
| --- | --- |
| 迁移 | `backend/services/channel/src/main/resources/db/migration/V281__ad_group_management.sql` |
| 后端 | `backend/services/channel/src/main/java/com/starcon/channel/AdGroupManagementModels.java` |
| 后端 | `backend/services/channel/src/main/java/com/starcon/channel/AdGroupManagementService.java` |
| 后端 | `backend/services/channel/src/main/java/com/starcon/channel/AdGroupManagementController.java` |
| 测试 | `backend/services/channel/src/test/java/com/starcon/channel/AdGroupManagementIntegrationTest.java`（15 例） |
| 契约 | `packages/api-contracts/src/channel.openapi.json` · `packages/api-contracts/src/contracts/channel.ts`（codegen 产物，**+8 path / +23 schema / 0 删除**） |
| 前端 | `apps/web/src/components/ads/ad-group-model.ts` · `ad-groups.tsx` · `ad-group-model.test.ts`（19 例） |
| 前端 | `apps/web/src/app/ads/groups/page.tsx` |
| 前端 | `apps/web/src/app/api/channel/ad-group-allowlist.test.ts`（6 例） |
| 白名单 | `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `ad-groups/` 元素**，既有条目一字未动） |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（只改 14.1.2.1 一行的「状态」「证据」两格） |

## 关键 trade-off

- **在别人的事实表上补列**（而不是自己建表）：牺牲了「一张表一个写入所有者」的纯粹性，
  换来「同一个广告组只有一份真相」。代价是两条写路径（平台结构登记 / 单组管理）写在同一张表上，
  已写进迁移注释与能力声明。
- **组级表现选 available=false 而不是建表**：牺牲了「组级报表入口」看起来更完整，
  换来「界面不会把活动级指标说成组级指标」。14.3.1.2 落地后把组级指标事实表接上即可，
  本模块的形状不用改。
- **改竞价不上自己的端点**：牺牲了「本模块的写入口自洽」，换来「改一笔钱只有一条写路径」。

## 后续待办

1. **14.3.1.2 并入集成头后**，把组级报表入口的路由标识与实际路由对齐（当前给的是蓝图 L2 入口
   `/ads/reports` + 稳定标识 `14.3.1.2`）。
2. **14.3.1.3 商品维度报表落地后**，用组级指标事实表替换当前的 available=false 声明。
3. **14.2.1.1 竞价管理落地后**，竞价策略维度与「跳转到竞价设置」入口才可打开。
4. **14.1.3.2 投放定位落地后**，定向与匹配类型才可以解读（当前只存原文）。
5. **平台回写队列交付后**，`pending_platform_writeback` 的置位与清零逻辑要接到真实回写结果上。
6. **侧栏 / 搜索导航未登记 `/ads/groups`** —— 与 14.1.1.2 / 14.2.2.1 等相邻车道现状一致，
   刻意不重排 `packages/ui` 的共享导航表。
7. ⚠️ **台账顶部概览计数未同步**（我这一行改了状态，但概览里的「已交付 297 / 未开发 168 / 完成率」
   属自动生成区域）。集成头在改我这一行**之前** `node tools/module-progress/sync.mjs --check`
   **就已经失败**（脚本重算得「已交付 301」，且会重排整表），属既有漂移；
   按任务书「只改自己那一行的两格，不重排整表」的要求**刻意没跑 sync**，
   概览计数的刷新留给并入时的同步轮。
