# 14.5.1.2 建联与样品（2026-10-22）

> 车道 `14.5.1.2`，分支 `feat/14-5-1-2`，基线 `integration/module-dev`（7c1e688f）。
> 蓝图原文：`docs/product/capability-blueprint/14-ads-and-growth.md` → `### 14.5.1.2 建联与样品`。
> 归属：**channel 服务**（L2 表 14 广告与增长 → channel，与 14.5.1.1 达人库同族）。

## 决策摘要

1. **SENT 边只由「登记平台回执」产生，通用状态端点不得凭空把草稿推成已发送。**
   建联状态机 `DRAFT → SENT` 这条边**故意不存在**：`SENT` 只能由
   `POST /outreachs/{uuid}/receipt` 产生，且必须同时写 `platform_message_id` /
   `platform_receipt` / `sent_at`。理由：把「我打算发」和「平台真的收到了」混成同一状态，
   就再也说不清哪几条私信真的发出去过 —— 而已发出的私信是**举证凭据**。
   终态 REJECTED / NO_REPLY / COMPLETED / CANCELLED 一律无出边。

2. **样品签收由包裹推导，人工不能直接推进。**
   `DELIVERED` / `SIGNED` 只能由包裹状态推出（`deriveSampleOrderStatus`），
   人工只能 REQUESTED → OUTBOUNDED / CANCELLED。理由：签收是**物流事实**，
   让人工点一下就能签收，等于给「样品其实没寄到」开一个后门。
   `CANCELLED` 优先于任何包裹状态 —— 取消后不能让迟到的包裹把单复活。

3. **批量只生成草稿，没有任何批量群发通道。**
   蓝图红线是「建联必须走官方接口，批量私信是平台风控重点」⇒
   `POST /outreachs/batch` 按筛选条件批量创建 **DRAFT**，幂等键 `batch:{batchKey}:{influencerId}`，
   逐条返回 CREATED / SKIPPED_OPEN / SKIPPED_DUPLICATE / SKIPPED_TEMPLATE；
   **服务端不存在批量发送端点，api-proxy 白名单也刻意不放行 `batch-send` 形状**。
   每一条仍要逐条登记回执才发出。

4. **频率上限做成「可查的本地事实」，不是口头承诺。**
   新表 `influencer_outreach_policy`（按平台 `daily_send_limit` + `min_interval_seconds`，
   默认 20 条 / 60 秒），登记回执时过**三道闸**：幂等键 → 每日上限 → 最小间隔。
   理由：风控要能被审计（这条为什么没发出去），不能只存在于某个人的经验里。

5. **平台达人私信适配器未交付 ⇒ 端口 + 「未接入」实现，不编造平台接口。**
   新增 `InfluencerOutreachPort` + `UnavailableInfluencerOutreachPort`（对所有平台答「未接入」，
   **不返回任何达人、不编造平台接口、不返回硬编码列表**）。
   `POST /outreachs/{uuid}/send` 在未接入时**明确失败且不写任何数据**；
   手动登记「已发送 + 平台回执」完整做完（新建 / 编辑 / 状态流转 / 批量操作 / 真分页真筛选）。

6. **三个下游缺口写进库，而不只是写进注释。**
   8 仓库 / 10 物流 / 12 财务**均无可调用的授权 API** ⇒
   `outbound_status` 只有 LOCAL_PENDING / LOCAL_OUTBOUNDED、`warehouse_outbound_ref` 恒空串待回填、
   `tracking_available` 与 `finance_posted` 各有 **DB CHECK 恒 false**（绕服务层直接写 SQL 也写不进 true），
   各带 `owner_module`；capabilities 端点逐维度 `available=false` + `ownerModule` + 影响说明。
   理由：「没接入」必须扛得住绕过服务层的直接 SQL，否则迟早有人写一条脚本把轨迹标成可查。

7. **DB 层的其它护栏**：`contact_channel` CHECK 钉死 `OFFICIAL_API`（建联别无他途）、
   `idempotency_key` UNIQUE（重放同一条不会发第二遍）、
   `influencer_outreach_open_unique` partial unique index（**同一达人同时只能有一条未走完的邀约**）、
   佣金形态 CHECK（RATE 与 FIXED **互斥**）。

8. **佣金规则与计提分表，计提行快照规则。**
   计提行存规则快照（type / rate / fixed），规则后改**不影响已计提行**；
   规则优先级 INFLUENCER > SEGMENT > GLOBAL。

9. **筛选条件语义**：条件之间 AND（交集），标签编码之间 OR（并集），
   黑名单默认排除（可显式打开），停用达人（INACTIVE）不进建联池。

10. **模板变量是闭合词表（7 个），未知变量在保存模板时即被拒。**
    display_name / handle / category / shop_name / platform_name / segment_name / commission_rate，
    **没有「其它」桶**。理由：`{{xxx}}` 原样发给达人是最难被发现的一类事故
    （后台看着一切正常，达人收到的是带花括号的话），所以必须在保存时就拒。

## 关键 trade-off

- **手动登记回执 vs 假装有官方接口。**
  平台私信接口未交付，手工登记回执意味着「已发送」这件事的真伪取决于登记人。
  *代价*：这条链路上没有平台侧交叉验证；*收益*：不编造平台接口、不返回硬编码列表，
  且登记动作本身带幂等键 + 频率上限 + 平台消息 id 与回执原文，事后能查。
- **DB CHECK 钉死缺口 vs 留布尔列自由填。**
  `tracking_available` / `finance_posted` 用 CHECK 恒 false。
  *代价*：下游交付时需要一条 ALTER 迁移来放开；*收益*：今天**不可能**出现「轨迹可查」的假事实。
- **样品单状态由包裹推导 vs 人工可直推。**
  *代价*：多一次状态同步；*收益*：签收不能被人工点出来。
- **api-proxy 新增独立 `services[]` 元素 vs 并进既有 `^influencers…` alternation。**
  同批多车道共享这一个文件，而每个服务在 `services[]` 里是一段**超长单行正则** ——
  只要两条车道都往同一服务的 alternation 里并分支，git 必然判冲突（结构性的，retry 不会自愈）。
  所以本车道走「新加一条元素」这条路：锚点固定在 **`identity/` 元素之后**
  （本仓历史上用过的锚点是「数组最开头」「`auth/` 前后」「`trade/` 之前」「`catalog/` 之前」
  「`warehouse/` 前后」「数组末尾」与「`purchase-followups` 之后」，`identity/` 之后**本仓还没有车道用过**，
  与同批其它车道常见锚点相距 2000 行以上 ⇒ git 能干净求并）。
  **首 token「outreach」零命中核对**：本文件既有 100+ 条 `services[]` 正则里没有任何一条含 `outreach` 串
  （全文唯一出现是 14.5.1.1 那条条目的**注释** `influencers/outreach`，非正则）；
  本条目**之前**的三条 `channel/` 元素（`ad-campaigns` / `ad-coupons` / `ad-flash-sales`）正则均 `^ad\-…` 锚定，
  **零命中** `outreach/…` ⇒ 不会被遮蔽。
  id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
  （不是 `[0-9a-f-]+`：后者会把 `batch` 这种非 UUID 形状也当成 id 放行）；
  末尾**刻意不带 `?`**（带了就等于放行裸路径 `outreach`）。
- **契约只加自己的部分。**
  集成头里 `packages/api-contracts/src/contracts/channel.ts` 相对 `channel.openapi.json`
  **本来就有别的车道留下的漂移**（且两个文件的小数写法不一致）。
  本车道跑 codegen，但只提交自己的增量：`channel.openapi.json` 相对集成头是**纯插入**
  （**+28 path / +48 schema / 0 删除**，非 outreach 路径零变化），
  `channel.ts` 是 codegen 的权威输出（因此顺带带上了它相对集成头的一处头部注释差异），
  trade 与其余五个服务的契约文件**逐字还原**为集成头版本。
- **佣金只到「计提登记」，不做订单归因。**
  14.5.2.1 带货归因未交付 ⇒ 归因口径没有；本模块不自己造一个。

## 文件清单

### 迁移（1）
- `backend/services/channel/src/main/resources/db/migration/V289__influencer_outreach.sql`
  — 九张新表：`influencer_outreach_segment` / `influencer_outreach_template` /
  `influencer_outreach_policy` / `influencer_commission_rule` / `influencer_outreach` /
  `influencer_outreach_followup` / `influencer_sample_order` / `influencer_sample_shipment` /
  `influencer_commission_accrual`。
  零 ALTER 零 DROP 既有表；全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS +
  `isolation` 策略 + 店铺收敛 `data_scope`（RESTRICTIVE）+ GRANT channel_app / REVOKE starcon_app；
  金额 NUMERIC(19,4)、数量 NUMERIC(19,6)、时间列 timestamptz；
  上游只读引用 **V288 `influencer`（14.5.1.1 已交付，同库同服务）** 与 V1 `platform` / `shop`，
  **零跨库 FK / 零跨库视图 / 零跨服务 SQL**。

### 后端（6）
- `backend/services/channel/src/main/java/com/starcon/channel/InfluencerOutreachModels.java` — 传输模型
- `backend/services/channel/src/main/java/com/starcon/channel/InfluencerOutreachRules.java` — 纯计算规则
  （建联状态机 / 包裹状态机 / 样品单状态推导 / 模板渲染 / 佣金计提 / 频率上限判定）
- `backend/services/channel/src/main/java/com/starcon/channel/InfluencerOutreachService.java` — jOOQ 显式 SQL
- `backend/services/channel/src/main/java/com/starcon/channel/InfluencerOutreachController.java` — 28 条路径
- `backend/services/channel/src/main/java/com/starcon/channel/vendor/InfluencerOutreachPort.java` +
  `UnavailableInfluencerOutreachPort.java` — 平台达人私信端口与「未接入」实现

### 测试（3 + 2 前端）
- `backend/services/channel/src/test/java/com/starcon/channel/InfluencerOutreachRulesTest.java` — 16 例
- `backend/services/channel/src/test/java/com/starcon/channel/InfluencerOutreachIntegrationTest.java` — 22 例
- `backend/services/channel/src/test/java/com/starcon/channel/InfluencerOutreachTenancyIntegrationTest.java` — 3 例（多租户三断言）
- `apps/web/src/components/ads/influencer-outreach-model.test.ts` — 28 例
- `apps/web/src/app/api/channel/influencer-outreach-allowlist.test.ts` — 5 例

### 前端（3 + 1 改）
- `apps/web/src/components/ads/influencer-outreach-model.ts` — 模型 + 接口 + 展示辅助 + 表单校验
- `apps/web/src/components/ads/influencer-outreach.tsx` — 真实工作区（六个页签）
- `apps/web/src/app/ads/outreach/page.tsx` — 页面（与 `/ads/influencers` 同级）
- `apps/web/src/lib/api-proxy.ts` — **新增一条 `outreach` services[] 元素**（插在 `identity/` 那条之后）

### 契约 / 台账 / 会话（3）
- `packages/api-contracts/src/channel.openapi.json`、`packages/api-contracts/src/contracts/channel.ts`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md` — **只改 14.5.1.2 这一行**
- 本文件

## 验证

- `./mvnw -q compile` → 退出 0（backend 全量）
- `./mvnw -pl services/channel -am test -Dtest='InfluencerOutreach*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **41 例 0 失败 0 错误 BUILD SUCCESS**（16 + 22 + 3）
- `pnpm typecheck` → 3/3 通过
- `pnpm exec eslint`（本车道 5 个前端文件）→ 零告警
- `pnpm vitest run apps/web/src/components/ads/influencer-outreach-model.test.ts` → 28 例全绿
- `pnpm vitest run apps/web/src/app/api/channel` → **58 文件 280 例全绿**
  （**因改了 api-proxy.ts，这是唯一必须全量跑的回归**：白名单是累积型正则，
  取错一侧会静默删掉别人已交付的路由，症状是页面 404 且所有静态检查全绿）
- 契约核对：channel 相对基线 **+28 path / +48 schema / 0 删除**，非 outreach 路径零变化
- 台账行核对：用 `sync.mjs` 的行正则实测该行可正常解析并继承「状态 = 已交付」，
  邻行（14.5.1.1 / 14.5.2.1）不受影响

## 后续待办

1. **平台达人私信官方接口未交付** ⇒ 只能手动登记「已发送 + 平台回执」；
   `POST /outreachs/{uuid}/send` 走的是未接入适配器（明确失败且不写数据）。
   `InfluencerOutreachPort` 是那条接缝：某个平台接上适配器后，同一个端点自动放行，前端不用改。
2. **8 仓库 / 10 物流 / 12 财务三个下游缺口** ⇒ 样品出库只落本地事实（待仓库回填单号）、
   轨迹查不到、佣金计提未入账；三者都在 capabilities 里显式 `available=false` + `ownerModule`。
   各自交付后需要一条迁移放开对应的 DB CHECK。
3. **14.5.2.1 带货归因未交付** ⇒ 本模块的佣金只到「计提登记」，不做订单归因。
4. **平台达人数据适配器仍未交付**（14.5.1.1 同款）⇒ 达人只能手动录入，本模块不做达人档案写入。
5. ⚠️ **集成头既有问题（非本车道引入，需调度者处理）**：
   `module-progress.md` 里 **14.1.2.1** 那行**只有 6 根竖线**（缺结尾竖线），
   `node tools/module-progress/sync.mjs` 解析不到它 ⇒ 会把它的「已交付」回落成「未开发」并把证据抹平。
   本车道因此**没有运行 sync.mjs**（只手工改了自己那一行）；
   集成头上建议先补上那一根竖线再跑一次 sync.mjs，让总览计数与明细重新对齐。
