# 2026-10-21 14.1.1.1 广告活动列表：数据截至 X 时间 + 三种空态 + 批量护栏

## 决策摘要

交付能力蓝图 14 卷 14.1.1.1「活动列表」（阶段 D7，L3 锚点 14.1.1），落在 **channel** 服务。

蓝图原文：「**职责**：广告活动主列表：平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、销售额、
ACOS/ROAS，按维度筛选与排序，支持批量暂停/启用/调预算。**输入**：筛选条件（平台、店铺、状态、日期）；
广告数据同步结果；用户数据范围。**输出**：分页列表 + 汇总；批量操作任务。**关系**：上游 = 平台广告 API 同步；
下游 = 14.1.1.2 详情、14.2.2.1 预算。**广告数据同步是本系统的地基**：同步失败时页面要明确显示"数据截至 X 时间"，
不能用空数据假装没有投放。」

- **归属服务 = channel**：卷 14 的 L2 表「14.1 广告管理 → channel」；活动与店铺同库，只读引用 V1 `platform` / V1 `shop`。
- **迁移** `V273__ad_campaigns.sql`：四张新表、**零 ALTER 零 DROP 任何既有表**（跨车道 DDL 回归面 = 0）：
  `ad_campaign`（活动主表）/ `ad_campaign_daily_metric`（每日明细）/ `ad_campaign_sync_run`（同步运行 = 水位来源）/
  `ad_campaign_operation_log`（批量操作审计）。四表 `tenant_id NOT NULL` + ENABLE/**FORCE** RLS isolation +
  **data_scope（RESTRICTIVE）按店铺 owner/部门收敛**（蓝图的输入「用户数据范围」，与 V171 listing / V203 同构）+ GRANT。
- **🔴 平台同步器未交付 ⇒ 本模块自带真实写入口**：蓝图的输入写着「广告数据同步结果」，而上游同步器本批不存在。
  两种做法摆在面前：① 用空数组/硬编码常量假装有投放；② **给活动与表现数据一个真的落点**。
  选 ②：`POST /imports` 把同步运行 + 活动 + 每日指标真落 PostgreSQL 16，一行假数据都没有；
  且**失败的同步只登记失败**（不写活动、`data_as_of` 为空 = **不推进水位**、必须带失败原因，DB 层 CHECK 钉死）。
- **🔴「没有投放」与「还没同步到数据」拆成三态枚举**：服务端回 `emptyReason` =
  `NO_SYNCED_DATA`（从没成功同步过 ⇒ 只知道「还没同步到数据」，**不能**说「本租户没有投放」）/
  `NO_CAMPAIGNS`（同步成功过、这个平台+店铺范围内确实一条活动都没有）/ `FILTERED_EMPTY`（范围里有活动，被状态/类型/关键词/日期筛掉了）。
  判据用**范围口径**（只按平台+店铺数一次活动数），不是「本租户有没有活动」—— 否则两个范围会互相污染。
- **🔴 归一类型与原始类型并存（照抄同卷 14.1.2.2 的加粗句）**：活动上三列
  `platform_ad_type`（平台原文）/ `internal_ad_type`（归一后）/ `ad_type_mapping_source`（走 2.1.2.1 哪一层）
  **由 DB 触发器从 14.1.2.2 的 `ad_type_profile` 复制**，绕过服务层直接写 SQL 也改不掉（触发器挂在**所有** UPDATE 上，
  不是 `UPDATE OF 某列` —— 否则只改状态/预算的行会绕过它）。
  `UNMAPPED` 是**显式合法值**：这类活动照样在列表里、照样带完整原始标记，只是不参与按内部类型的横向比较。
  `PARTIAL_FIELD_COVERAGE` 是 14.1.2.2 **单条广告报文**粒度的状态，**不是活动级属性** —— 本模块刻意不把它伪装成活动级状态。
- **🔴 ACOS/ROAS 口径写进返回值**（`metricDefinitionNote` 随汇总返回，界面表头直接显示）：
  ACOS = 消耗/销售额、ROAS = 销售额/消耗，分母为 0 给**空值（不可比）而不是 0**。
  汇总**先汇总再相除**（总消耗/总销售额），**不是各活动比率的平均** —— 后者是数学错误：
  两条 ACOS 100% 的活动平均成 50% 会让人以为整体在盈亏线上，而它其实在亏。
- **🔴 批量护栏**（蓝图 14 章关键约束「AI/规则自动调整必须有上限保护」）：
  单活动单次调整上限 5000 / 一次批量总额上限 20000 / 单活动预算上限 100000 / 单次最多 200 条。
  超限**逐条拒绝**并写审计（`APPLIED`/`REJECTED`/`SKIPPED` 三种结果都留痕 —— 被拒的那几条也要能复盘）。
  **只接受显式选中的 id**：「按筛选结果全选 + 变更预览 + 异步批量任务」是 **14.1.1.3（未交付）**，
  capabilities 里三个维度显式 `available=false` + ownerModule + 影响，**不用空列表冒充已交付**。
- **本地改动不被平台回读静默覆盖**：平台回写队列未交付 ⇒ 本地暂停/启用/调预算会置 `pending_platform_writeback=true`；
  下一次平台回读（`ON CONFLICT DO UPDATE`）**不覆盖**处于待回写状态的 `status` / `daily_budget`，
  否则用户的本地改动会被一次同步悄悄抹掉。
- **给 14.2.2.2 自动规则的读取形状**：`GET /by-external?platformId=&shopCode=&externalCampaignId=`
  返回活动**当前状态 + 预算 + 消耗 + ACOS/ROAS + 数据截至时刻**，只读、只给当前值。
  规则引擎据此判断「要不要调」，本模块不实现规则引擎、不依赖它的表。
- **端点 8 个**挂 `/channel/ad-campaigns`：capabilities / options / campaigns（列表）/ campaigns/{uuid} /
  by-external / imports / bulk-operations / sync-runs。权限码复用 identity 已有的 `channel.operation.*`，**identity 零改动**。
- **api-proxy 新增一条独立 `services[]` 元素**，插入锚点固定在 `prefix: 'auth/'` 那条**之前**
  （同批另三条车道的插入点分别是「第一个 `channel/` 之前」「中段 trade 区」「数组末尾」，相距 400 行以上，git 能干净求并）。
  首 token `ad-campaigns` 对既有 100+ 条正则逐条核对**零命中**；id 段用完整 UUID 形状
  `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`；字面量子路径排在 `{uuid}` 段之前；裸路径 `ad-campaigns` 刻意不放行。

## 关键 trade-off

1. **「本模块自带写入口」 vs 「等平台同步器」**。等 ⇒ 本批交付一个空列表页，等于用空数据假装没有投放，
   正是蓝图加粗句点名要防的；自己写 ⇒ 多承担一块真实写路径，但数据从第一天起就是真的。
   代价：同步的**触发时机**仍靠外部调用（能力声明里 `platform_sync_scheduler` 显式 `available=false`）。
2. **「按筛选全选」没做**。蓝图 14.1.1.3 拥有它且未交付；做了会让界面以为 14.1.1.3 已交付，
   而且「按当前筛选条件全选 + 一键调预算」正是误操作烧预算的高发路径。宁可少做、显式标未交付。
3. **日预算的绝对上限 vs 相对调整上限**。绝对上限（目标预算 > 100000）判为**请求级 400**（它对任何一条都不可能成立）；
   相对上限（单次调整额 > 5000、总额 > 20000）判为**逐条 REJECTED**（是否超限取决于该活动当前预算）。
   分成两级的理由：请求级的错就别让用户在「部分生效」的中间态里困惑。
4. **每日明细 vs 累计值**。存每日而不是只存累计：蓝图的输入有「日期」筛选，累计值根本没法按区间筛；
   14.3.1.1 报表要按天出图，累计值也拆不回每天。代价是行数变多，靠 `(tenant_id, metric_date, campaign_id)` 索引兜住。
5. **契约文件的既有漂移不归本车道**。重新生成 `catalog-contracts.py` 时发现 `trade` 的快照相对 trade 服务代码是**陈旧的**
   （集成头 `merge(11.4.2.1)` 带了新端点但快照没重新生成）。本车道只提交 channel 的契约变化，
   trade 的漂移**原样还原**，留给 trade 车道或调度者处理。

## 文件清单

**迁移**

- `backend/services/channel/src/main/resources/db/migration/V273__ad_campaigns.sql`（新）

**后端（channel 服务）**

- `backend/services/channel/src/main/java/com/starcon/channel/AdCampaignModels.java`（新，传输模型 + 能力声明 + 护栏）
- `backend/services/channel/src/main/java/com/starcon/channel/AdCampaignService.java`（新，jOOQ 显式 SQL）
- `backend/services/channel/src/main/java/com/starcon/channel/AdCampaignController.java`（新，8 个端点）
- `backend/services/channel/src/test/java/com/starcon/channel/AdCampaignIntegrationTest.java`（新，16 例）

**契约（codegen 产物，只由脚本生成）**

- `packages/api-contracts/src/channel.openapi.json`（+8 path / +23 schema / **0 删除**）
- `packages/api-contracts/src/contracts/channel.ts`

**前端**

- `apps/web/src/components/ads/ad-campaign-model.ts`（新，类型 + 请求 + 展示辅助 + 表单校验）
- `apps/web/src/components/ads/ad-campaign-model.test.ts`（新，24 例）
- `apps/web/src/components/ads/ad-campaigns.tsx`（新，工作区组件）
- `apps/web/src/app/ads/campaigns/page.tsx`（新，页面）
- `apps/web/src/app/api/channel/ad-campaign-allowlist.test.ts`（新，5 例，白名单双向断言）
- `apps/web/src/lib/api-proxy.ts`（**新增一条** `services[]` 元素，既有条目一字未动、也不重排）

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md`（**只改 14.1.1.1 那一行**的状态与证据两格）
- `docs/sessions/2026-10/2026-10-21-ad-campaign-list.md`（本文件）

## 验证

- L0 编译闸：`backend && ./mvnw -q compile` → 通过。
- L1 模块闸：`./mvnw -pl services/channel -am test -Dtest='AdCampaign*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **Tests run: 16, Failures: 0, Errors: 0**（真实 PostgreSQL 16 + Testcontainers）。
- 前端：`pnpm vitest run apps/web/src/app/api/channel apps/web/src/components/ads` → **47 files / 266 tests 全绿**
  （含 api-proxy 全量 allowlist 回归）。
- `pnpm typecheck` → 三包绿。`eslint` + `prettier --check` → 改动文件全绿。

## 后续待办

- **14.1.1.2 活动详情**：本模块的 `GET /campaigns/{uuid}` 已给每日明细与操作审计，是它天然的底座。
- **14.1.1.3 批量操作**：按筛选全选 + 变更预览 + 异步任务；本模块的护栏与逐条结果语义可直接复用。
- **14.2.2.1 预算分配** / **14.2.2.2 自动规则**：调本模块的 `GET /by-external` 取当前状态与预算/消耗；
  规则引擎上线后本模块的 `pending_platform_writeback` 才能真正被回写队列消费。
- **平台适配层**：定时同步器（谁调 `POST /imports`）+ 平台回写队列（谁把 `pending_platform_writeback` 推回平台）。
- **14.3.1.1 活动报表**：跨平台单位统一（多币种换算）不在本模块，ACOS/ROAS 口径已按本模块的定义给出，
  报表侧要复用同一份定义而不是另算一套。
- **14.1.2.1 广告组管理**：广告 ↔ 内部 SKU 的归因落点，广告费归因到 SKU 是利润算得准的前提。
