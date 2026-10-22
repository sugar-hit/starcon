# 2026-10-22 batch-61 —— 广告与增长（促销效果 / 建联样品 / 带货归因）+ 客户与客服（邮件收发）

> 本轮流水线：调度者回收 5 条已落地车道 → 4 条并入集成头、1 条冲突 → L2 批量验证 → 说明 / commit / push。
> 覆盖模块：**14.4.2.1 / 14.5.1.2 / 14.5.2.1 / 15.1.2.1**（迁移 V287 / V289 / V290 / V292）。
> 各模块的完整设计决策与 trade-off 见各自车道会话日志，本文件是批次级索引 + 用户视角说明。

---

## 模块 14.4.2.1 促销效果分析

**这个功能做什么**：运营能回答「这次促销到底赚没赚」。选一个促销活动，拿到促销期的销量、销售额、
**毛利**、增量（对比活动前等长基期）、补贴成本与 ROI，并导出报表。
判断依据是**增量毛利**而不是销售额 —— 促销期销售额涨了但毛利跌了，这个模块会明确报「放量不赚」。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V287__ad_promotion_analysis.sql`
  —— 5 张表：`ad_promotion_cost_snapshot`（成本快照，**成本进入 channel 的唯一通道**）/
  `ad_promotion_effect_baseline`（基期窗口与日均）/ `ad_promotion_effect`（商品级效果事实）/
  `ad_promotion_effect_export`（导出留痕）/ `ad_promotion_analysis_run`（分析批次）。
  `tenant_id NOT NULL` + ENABLE/FORCE RLS `isolation` + GRANT `channel_app`；
  金额 `NUMERIC(19,4)`，时间列 `timestamptz`；**零跨服务 join / FK / 视图**。
- 端点：`AdPromotionAnalysisController` —— 能力声明 / 选项 / 分析运行 / 结果列表与详情 /
  活动维度聚合 / 成本快照登记 / 导出 等，jOOQ 显式 SQL。
- 页面：`/ads/promotion-analysis` —— 筛选维度（活动、店铺、时间窗、是否放量不赚）、
  操作（跑分析、登记成本快照、导出）、错误态 / 空态 / 加载态齐全；
  报表里**销售额与毛利并排出现在同一张表**。
- 测试：`AdPromotionAnalysisIntegrationTest` 22 例（含多租户三断言）+
  前端 27 例 + 全量 allowlist 回归 972 例。

**关键设计决策**：

1. **成本复制进 channel，不跨服务读 catalog。** 跨服务读一次就多一次口径漂移的来源，
   而毛利是促销决策要用的数字，必须能被复盘 ⇒ 成本快照带 `source_ref` / `captured_at` / `version`。
   代价是「要有人登记快照」这个**显式**成本，换掉「口径漂移」这个**隐形**成本。
2. **销量只读 `listing_performance_snapshot`，不新建「促销销量」表。** 该表 UNIQUE 含 `source`，
   同一天可能有 PLATFORM 与 API 两条来源 ⇒ 取 `received_at desc, id desc` 的最近一条、**不相加**。
3. **不可比一律 NULL，绝不写 0。** 缺成本 ⇒ 毛利 null；BOGO 类促销无价格系数 ⇒ 补贴 null。
   0 会被渲染成「毛利是 0」/「这次没花钱」，与「拿不到」在运营决策上完全相反。
4. **毛利/增量/ROI 的 `>= 0` CHECK 被去掉** —— 促销期毛利下滑正是本模块要报的场景。
   而 `roi_value` / 毛利率仍是 GENERATED 列（绕服务层也编不出与原始量对不上的比率）。
5. **活动维度是真聚合**（Σ毛利 / Σ销售额，**不是**各商品毛利率的平均），
   且**只要有一个商品不可比，该活动指标就是空值而不是部分和**。

---

## 模块 14.5.1.2 建联与样品

**这个功能做什么**：达人运营能对达人**建联**（邀约、跟进、登记平台回执、寄样品与跟踪物流），
所有状态都有据可查：哪些真的发出去了、为什么某条没发出去、样品有没有签收。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V289__influencer_outreach.sql`
  —— 9 张新表：`influencer_outreach_segment`（筛选条件）/ `influencer_outreach_template`（邀约模板）/
  `influencer_outreach_policy`（频率策略）/ `influencer_commission_rule`（佣金规则）/
  `influencer_outreach`（建联主记录）/ `influencer_outreach_followup`（跟进）/
  `influencer_sample_order`（样品单）/ `influencer_sample_shipment`（样品包裹）/
  `influencer_commission_accrual`（佣金计提）。
  零 ALTER 零 DROP；全部 `tenant_id NOT NULL` + ENABLE/FORCE RLS `isolation` +
  店铺收敛 `data_scope`（RESTRICTIVE）+ GRANT `channel_app` / REVOKE `starcon_app`；
  金额 `NUMERIC(19,4)`、数量 `NUMERIC(19,6)`；上游只读引用 V288 `influencer` 与 V1 `platform`/`shop`。
- 端点：`InfluencerOutreachController` —— **28 条路径**：筛选条件、模板（含预览）、
  策略、建联记录（列表 / 详情 / 改 / 状态 / 批量 / 回执 / 发送）、跟进、样品单与包裹、佣金计提。
- 页面：`/ads/outreach` —— 六个页签（建联 / 筛选条件 / 模板 / 样品 / 策略 / 佣金），
  筛选维度与操作齐全，错误态 / 空态 / 加载态齐全。
- 测试：`InfluencerOutreachRulesTest` 16 例 + `InfluencerOutreachIntegrationTest` 22 例 +
  `InfluencerOutreachTenancyIntegrationTest` 3 例（三租户断言）+ 前端 28 例 + allowlist 回归 280 例。

**关键设计决策**：

1. **`SENT` 边只由「登记平台回执」产生**，通用状态端点不得凭空把草稿推成已发送。
   把「我打算发」和「平台真的收到了」混成同一状态，就再也说不清哪几条真的发出去过。
2. **样品签收由包裹推导，人工不能直接推进** —— 签收是物流事实，
   让人工点一下就能签收，等于给「样品其实没寄到」开一个后门。
3. **批量只生成草稿，没有任何批量群发通道** —— 蓝图红线是「建联必须走官方接口，批量私信是平台风控重点」。
4. **频率上限做成「可查的本地事实」**（按平台 `daily_send_limit` + `min_interval_seconds`），
   登记回执时过**三道闸**：幂等键 → 每日上限 → 最小间隔。风控要能被审计。
5. **三个下游缺口写进库，而不只是写进注释** —— `tracking_available` / `finance_posted`
   各有 **DB CHECK 恒 false**（绕服务层直接写 SQL 也写不进 true）。
   代价是下游交付时要一条 ALTER 迁移；收益是今天不可能出现「轨迹可查」的假事实。
6. **api-proxy 新增独立 `services[]` 元素**（首 token `outreach`，零命中核对过），
   而不是并进既有 `^influencers…` alternation —— 那必冲突。

---

## 模块 14.5.2.1 带货归因

**这个功能做什么**：把达人带来的业绩按**归因规则**分摊到人，算出每个达人的归因 GMV、
归因成本与扣佣金后净额，并出**挽回率/毛利趋势**可复核的报表。
三种口径（档案 GMV / 合作历史合计 / 归因 GMV）在报表里**分列三栏**，谁也不覆盖谁。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V290__influencer_attribution.sql`
  —— 归因规则、归因记录、归因批次、规则快照等表；`tenant_id NOT NULL` + ENABLE/FORCE RLS `isolation` +
  店铺收敛 `data_scope` + GRANT `channel_app`；金额 `NUMERIC(19,4)`、数量 `NUMERIC(19,6)`；
  复合 FK 指向本地合作记录（**不跨服务**）。
- 端点：`InfluencerAttributionController` —— 规则 CRUD、归因批次执行、归因记录列表与详情、
  达人维度汇总、活动维度汇总、`POST /channel/attributions/sync`（未接入 ⇒ 409 且不写数据）。
- 页面：`/ads/attributions` —— 规则配置区、归因批次区、达人维度与活动维度报表、导出。
- 测试：`InfluencerAttributionRulesTest` + `InfluencerAttributionIntegrationTest` +
  `InfluencerAttributionTenancyIntegrationTest`（三租户断言）+ 前端模型/allowlist 测试。

**关键设计决策**：

1. **归因是「第三个口径」，不是合作口径的改名** —— 混成一个数就会高估达人带货的净收益。
2. **一个租户同时只有一套生效口径**（partial unique index 钉死），
   且**每条归因记录自带规则快照与基数快照** ⇒ 「归因 GMV = 基数 × 权重」永远可复核。
3. **分摊基准不可用时返回 null 并跳过该合作**，**不悄悄退回「全额归因」** ——
   那会让「按触达分摊」变成「谁合作多谁拿走」。
4. 🔴 **佣金成本只在本服务内真实留痕，没有进利润表** —— 13.4 利润（reporting 服务）未交付，
   集成头里根本没有 reporting 服务 ⇒ 能力声明里 `profitIntegration.available = false`，
   全模块**不写「已同步到利润分析」**；报表里的「扣佣金后净额」**只扣佣金**，所以它**不是净收益**。
5. 🔴 **佣金不按归因权重缩放；已计提的佣金不自动改写** —— 成本不会因为口径变了就变；
   按权重缩放等于用口径粉饰成本。重跑只更新差额 `commission_variance`。
6. **归因窗口只挡「新增」，不抹掉已发生的事实** —— 账期过了不能补记，但已归因过的记录不会被重跑抹掉。
7. **契约 DTO 全部带 `InfluencerAttribution` 前缀** —— codegen 的跨服务同名检测发现 trade 已有
   `AttributionRow` 等，本模块改名而不是写进 allowlist（改自己的名字不碰别人的成果）。

---

## 模块 15.1.2.1 邮件收发

**这个功能做什么**：客服在一个地方看到买家邮件（Amazon Buyer-Seller Messaging 等），
登记、回复、挂附件、用模板，并按**会话**（店铺 + 买家 + 订单）看到收与发，
从而**不重复回复同一个买家**。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V292__crm_buyer_email.sql`
  —— 4 张新表（`crm_buyer_email` / `crm_buyer_email_attachment` / `crm_buyer_email_template` /
  `crm_buyer_email_send_record`）+ 6 个守卫触发器 + RLS（ENABLE + FORCE，策略名 `isolation`）
  + GRANT（表与**序列分开**授权）+ `REVOKE ALL ON flyway_schema_history FROM crm_app`。
  **零 ALTER 任何既有表；零外键**。
- 端点：`BuyerEmailController` —— `/crm/buyer-emails/**` 共 **10 条路径 / 15 个方法映射**：
  列表 / 详情 / 会话 / 登记 / 发送 / 附件 / 模板 CRUD / 平台同步。
- 页面：`/crm/emails` —— 真实工作区（能力边界 / 列表 / 详情 + 会话 / 登记 / 发送 / 附件 / 模板 / 同步），
  错误态 / 空态 / 加载态齐全。
- 测试：`BuyerEmailIntegrationTest` 21 例 + `BuyerEmailTenancyIntegrationTest` 4 例（三租户断言）
  + 前端 6 例 + allowlist 回归 956 例。

**关键设计决策**：

1. **加粗句「分开展示会导致重复回复买家」落成会话键，不落成外键。**
   同批并行的 15.1.1.1「消息收件箱」**其表不在本车道基线里** ⇒ 本表**不建任何指向它的外键**
   （跨批次 FK 会让迁移在基线上直接失败）。改用 `conversation_key`
   （`<shopId>|<buyerRef>|<salesOrderNo>`，由服务端**唯一推导点**算出）+ nullable 的
   `source_message_id` 软引用。**同源 = 会话键相等**。
2. **收 / 发两个方向都在邮件表里，状态词表分开**（INBOUND: RECEIVED/REPLIED；
   OUTBOUND: QUEUED/SENT/FAILED，DB CHECK 强制自洽）——
   会话里只有收到的邮件、没有发出的邮件，客服照样会以为「我还没回过」。
3. **「发送失败要保留原消息状态」同样适用** —— 失败由发出去的那一行（OUTBOUND + FAILED）记着，
   收到的那封仍是 RECEIVED；界面三态文案分别是
   「已成功回复过」/「尝试过但没有成功发出」/「还没有回过」。
4. **平台买家邮件未接入 ⇒ 端口 + 显式失败，不放假实现**：
   `PlatformEmailPort` + `UnavailablePlatformEmailPort`；同步端点 409 且不写任何数据；
   `transport='PLATFORM_API'` 由**服务层拒绝**（409），只允许 `MANUAL_HANDOFF` ——
   一个「返回成功回执」的假通道会让「买家已经收到回复了」在数据上看起来发生过。
5. **附件只落元数据**（文件名 / 类型 / 字节数 / 存储键 / 校验和）——
   crm 服务没有对象存储，字节数是 `numeric(19,6)` + BigDecimal（禁浮点）。

---

## 本轮流水线侧记录

- **回收**：`merge-ready` 并入 4 条车道（14.4.2.1 / 14.5.1.2 / 14.5.2.1 / 15.1.2.1），
  退出码 2 —— **15.1.1.1 消息收件箱在 `apps/web/src/lib/api-proxy.ts` 判冲突**。
  根因是 14.5.1.2 与 15.1.1.1 **选了同一个插入锚点**（`identity/` 元素之后），
  属结构性冲突（retry 不会自愈）。该车道成果完好（3 个 commit），
  已按「修复任务」重派并指定了互不重叠的新锚点。
- **静态预检**：本批 4 条新迁移（V287 / V289 / V290 / V292）
  **均无** `ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / 新增 CHECK 形状 ⇒ 无跨车道互相破坏风险。
- **L2 批量验证**：见本文件末尾「L2 结果」。
- **台账**：`sync.mjs` 复核 465 行、0 重复；已交付 **312** / 进行中 0 / 未开发 **153**。

---

## L2 结果

**🟢 全绿**（L2 批次闸，集成头一次性全量验证，2026-10-22 07:06:20 → 07:51:32，耗时 45 分 12 秒）：

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `cd backend && ./mvnw verify` | `MVN_EXIT=0` —— **7967 例 / 469 个测试类，failures=0 errors=0**（catalog 1981 / trade 2380 / warehouse 1639 / channel 1602 / identity 270 / crm 95） |
| 类型 | `pnpm typecheck` | `TYPECHECK_EXIT=0` |
| 规范 | `pnpm lint` | `LINT_EXIT=0` |
| 前端 | `pnpm test` | `TEST_EXIT=0` —— **454 个测试文件 / 6743 例全过** |
| 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | `BUILD_EXIT=0` |
| 台账 | `node tools/module-progress/sync.mjs --check` | `SYNCCHECK_EXIT=0`（台账与蓝图一致） |

> 本轮 L2 是在**零在途车道**的状态下跑的（派发新车道放在 L2 之后），
> 因此没有出现「L2 与车道 L1 抢 Testcontainers ⇒ 成片假红」的已知形状。
> 静态预检同样干净：本批 4 条新迁移无 `ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / 新增 CHECK。
