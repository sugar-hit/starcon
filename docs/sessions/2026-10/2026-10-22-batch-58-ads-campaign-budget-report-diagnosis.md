# batch-58 · 卷 14 广告与增长四条车道交付说明（2026-10-22）

> 本批由并行车道流水线的**调度者**在集成头 `integration/module-dev` 上并入并做**一次** L2 批量验证。
> 4 条车道全部落在 channel 服务，互不依赖，L3 锚点各不相同。
> 台账：`docs/product/capability-blueprint/module-progress.md`（465 行，每个模块一格）。

## 本批总览

| 模块 ID | 名称 | L3 锚点 | 迁移 | 后端测试 | 前端测试 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 14.1.1.2 | 活动详情 | 14.1.1 | `V277__ad_campaign_details.sql` | 17 例 | 20 + 5 例 | 已交付 |
| 14.2.2.1 | 预算分配 | 14.2.2 | `V278__ad_budget_allocation.sql` | 14 例 | 23 + 5 例 | 已交付 |
| 14.3.1.1 | 活动报表 | 14.3.1 | `V279__ad_campaign_reports.sql` | 18 例 | 22 + 5 例 | 已交付 |
| 14.3.2.2 | 效率诊断 | 14.3.2 | `V280__ad_efficiency_diagnostics.sql` | 14 例 | 24 + 5 例 | 已交付 |

合计：**4 条车道 / 63 例后端集成测试 / 104 例前端测试 / 21 张新表 / 0 处 ALTER 既有表**。

---

## 模块 14.1.1.2 活动详情

**这个功能做什么**：运营打开一个广告活动的详情页，一屏看到它的全部：基本设置、预算与消耗曲线、
每日表现明细、广告组、投放商品、创意、关键词，并且能直接在这页做四个动作——调预算、暂停、
改竞价、**复制结构**（把跑得好的活动结构复制到新店铺/新 ASIN）。

**技术落地**：

- 迁移：`V277__ad_campaign_details.sql` — 新建 5 张表：`ad_campaign_group`（广告组）、
  `ad_campaign_group_product`（投放商品，复合 FK 带 tenant_id ⇒ 跨租户挂载物理上不可能）、
  `ad_campaign_creative`（创意）、`ad_campaign_group_bid_log`（改竞价审计）、
  `ad_campaign_structure_copy`（复制结构审计）。
  **零 ALTER 任何既有表**；全部 tenant_id NOT NULL + ENABLE/FORCE RLS isolation +
  data_scope（RESTRICTIVE，按活动 → 店铺收敛）+ GRANT（`starcon_app` 零权限）。
- 端点：`/channel/ad-campaign-details/**` 共 **10 个** —
  `GET /capabilities`（能力声明，未交付段 available=false + ownerModule）、
  `GET /campaigns/{id}`（全景）、
  `GET /campaigns/{id}/groups|products|creatives|keywords|change-history`、
  `POST /campaigns/{id}/structure|bids|copy`。
- 页面：`/ads/campaigns/[id]` — `apps/web/src/app/ads/campaigns/[id]/page.tsx` +
  `components/ads/ad-campaign-detail.tsx` + `ad-campaign-detail-model.ts`；
  筛选维度：活动 × 广告组 × 投放商品 × 创意 × 关键词（标注「来自 14.1.3.1」）；
  错误态/空态/加载态齐全；修改历史页签显式标「平台侧改历史当前不可得」并与本地审计分开说。
- 测试：`AdCampaignDetailIntegrationTest` **17 例**（Testcontainers 真实 PostgreSQL 16）+
  `ad-campaign-detail-model.test.ts` **20 例** + `ad-campaign-detail-allowlist.test.ts` **5 例**。
  覆盖：租户隔离**三断言**（两租户互不可见 / 伪造 X-Tenant-Id 头无效 / 连接池 A→B→A 三轮不串租户）、
  平台侧改历史显式不可用且本地审计分得开、复制结构副本真落库 + 待回写 + 关键词恒 0 + 预算没跟着复制、
  改竞价三种结果（APPLIED / REJECTED / SKIPPED）都留审计、结构登记的替换语义（没出现的组真被删）、
  没有日预算时预算使用率不可比而不是 0、timestamptz 往返**断言写回值**逐字相等、
  跨租户与不存在返回**逐字相同**的 404。

**关键设计决策**：

1. **平台侧修改历史显式不可用，不建表**。上游没有平台广告 API 同步器 ⇒ 没有同步源。
   `changeHistory.available=false` + `ownerModule` + `impact` 做成结构里的字段，当前展示本地操作审计
   （`ad_campaign_operation_log`），字段名与文案分得开。
   **否决方案**：建一张空的「平台改历史」表 —— 空表会被界面渲染成「没有改过」，
   比明说「拿不到」严重得多。
2. **关键词读上游 14.1.3.1 的 `ad_keyword`（V274），不建第二张表**。
   **否决方案**：自己建一张关键词表 —— 同一个词在两处各有一份，改价就会改到不同的地方。
3. **复制结构：结构副本真落库，平台侧还不是**。目标活动 + 广告组 + 投放商品 + 创意全部写进库，
   记 `ad_campaign_structure_copy`；平台回写队列未交付 ⇒ 目标活动 `pending_platform_writeback=true` +
   状态 `PAUSED`（复制出来不投放）+ 平台侧 id 落成 `LOCAL-COPY-{uuid}`
   （**本地标记，不是像平台 id 的字符串** —— 编一个像平台 id 的串会让「同步回读」把草稿认成平台活动）。
4. **关键词不复制**，且 `ad_campaign_structure_copy.keyword_count` 由 **CHECK 钉死为 0**，
   让「关键词没被复制」成为**可查的事实**而不是一句说明。
   **否决方案**：往「平台原文」表里塞本地编的 id —— 平台侧还没建这个活动，关键词 id 无从分配。
5. **复制的是结构不是钱**：目标活动的预算与投放期不跟着复制。**否决方案**：一次复制到位 ——
   新活动带预算 + 一次点击就是新活动开始烧钱。
6. **只画「有每日指标的天」**：没有指标行的一天是「没数据」，不是「花了 0」；
   日预算为空 ⇒ 预算使用率**不可比（null）**而不是 0。
7. **改竞价审计三种结果都留**（APPLIED / REJECTED / SKIPPED），且 `ad_campaign_group_bid_log.group_id`
   **不建 FK** —— 审计要能记下「我想改的那个组压根不存在」，有 FK 这条审计自己写不进去。
8. **api-proxy 白名单新增一条独立 `ad-campaign-details/` 元素**，首 token 在既有 100+ 条正则里零命中，
   插入锚点固定在 `compliance-precheck` 之前；既有条目一字未动、也不重排。

---

## 模块 14.2.2.1 预算分配

**这个功能做什么**：运营有一笔跨店铺的推广预算，要决定**怎么分给各个广告活动**。
系统按销售目标、历史 ROAS（以及未来的利润）给每个活动打分，按分数分配预算总额，
指出哪些活动**预算受限**（想花没预算）、哪些活动**闲置预算**（有钱花不出去），
还能生成再平衡建议（钱该从谁挪给谁）并一键执行。

**技术落地**：

- 迁移：`V278__ad_budget_allocation.sql` — 新建 10 张表：`ad_budget_rule`（分配规则）、
  `ad_budget_rule_weight`（权重位：销售目标/ROAS/利润）、`ad_budget_rule_target`（规则目标）、
  `ad_budget_pool`（预算池）、`ad_budget_plan`（分配方案）、`ad_budget_plan_line`（方案明细）、
  `ad_budget_plan_skipped`（未纳入清单）、`ad_budget_rebalance`（再平衡建议）、
  `ad_budget_task`（执行任务）、`ad_budget_task_line`（逐条结果）。
  **零 ALTER 任何既有表**；tenant_id NOT NULL + RLS + data_scope + GRANT 同口径。
- 端点：`/channel/ad-budgets` 共 **16 个** — `GET /capabilities|options`、
  `GET|POST /rules`、`GET /rules/{id}`、`GET|POST /pools`、`GET /pools/{id}`、
  `GET|POST /plans`、`GET /plans/{id}`、`GET /rebalances`、`GET|POST /tasks`、
  `GET /tasks/{id}`、`POST /tasks/{id}/apply`。
- 页面：`/ads/budgets` — `apps/web/src/app/ads/budgets/page.tsx` +
  `components/ads/ad-budgets.tsx` + `ad-budget-model.ts`；
  筛选维度：预算池 × 规则 × 活动；操作：建规则 / 建池 / 计算方案 / 看再平衡 / 生成任务 / 执行；
  页面常驻一条说明「当前只按销售目标与历史 ROAS 分配」；错误态/空态/加载态齐全。
- 测试：`AdBudgetAllocationIntegrationTest` **14 例**（真实 PostgreSQL 16，含租户隔离三断言）+
  `ad-budget-model.test.ts` **23 例** + `ad-budget-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. **「预算应向利润高的活动倾斜」落地成可插拔的权重位，不是常量**。三个来源建模成
   `ad_budget_rule_weight` 的三行权重；新增来源 = 新增一行 + 一段分值计算，
   **评分与分摊算法不用改**。**否决方案**：把某个来源写死进算法。
2. **归一化先换分额再乘权重相加**：先在活动之间把每个来源换成分额（share ∈ [0,1]）再乘权重。
   **否决方案**：直接相加 —— 销售目标是钱（金额量纲）、ROAS 是比率，量纲不同，
   直接相加会让「目标大的活动」和「效率高的活动」互相污染，算出来的分配额谁也解释不清。
3. **13.4 利润未交付 ⇒ 不用假利润顶替，但把位子留好**：DB 层
   `CHECK (weight_source <> 'PROFIT' OR weight = 0)`（绕服务层写 SQL 也写不进非零利润权重）、
   服务层非零利润权重返回 409 且理由说成「13.4 尚未交付」、
   界面层 capabilities 显式 available=false + ownerModule。13.4 到位后放开 CHECK 即可，**算法一行不用改**。
4. **币种不一致的活动不参与分配，不折算**：5.2.2.1 汇率查询未交付 ⇒ 本仓没有可用汇率来源；
   跨币种分配逐条列进 `ad_budget_plan_skipped` 并写明 `CURRENCY_MISMATCH`。
   **否决方案**：用假汇率折算 —— 错得离谱且无人察觉。
5. **分配额之和严格等于池总额**：最大余数法（先向下取整到 0.0001，再按小数部分从大到小补回，
   并列按活动 id ⇒ 可复现），DB 层 `CHECK (allocated_amount + unallocated_amount = total_amount)` 兜底。
   上下限是软约束、**池总额是硬约束**（许诺超出手里的钱就是超发预算）。
6. **闲置预算与预算受限是两种相反的事实，分开落库**：`idle_budget` / `budget_constrained` 两列 +
   汇总两个数分开放。**否决方案**：混成一个「预算利用率」数字 —— 没人能回答「该给谁加钱」。
   利用率口径 = 日均消耗 ÷ 日预算；日预算为空/0 **或窗口内没有任何每日指标** ⇒ **不可比（NULL）**而不是 0。
7. **执行任务走 14.1.1.1 的写入口**（`AdCampaignService.bulkOperation`），不直接 UPDATE `ad_campaign`
   —— **每张事实表只有一个写入所有者**。超限的**逐条 REJECTED 并留痕**（绝不静默截断）；
   改动只落本地并标记 `pending_platform_writeback`。
8. **再平衡建议与分配方案是两个独立视角**：方案回答「这笔池怎么摊」（改总额），
   再平衡回答「当前分布里钱该从谁挪给谁」（不改总额）。

---

## 模块 14.3.1.1 活动报表

**这个功能做什么**：运营要看广告到底花钱换回了什么 —— 一张按日/周/月分桶的活动维度报表，
带曝光、点击、CTR、CPC、订单、转化率、花费、销售额、ACOS/ROAS/TACOS，支持多维对比与 CSV 导出，
并且**表头写明每个口径的定义**（ACOS 到底是哪个除哪个），跨部门沟通才不各说各话。

**技术落地**：

- 迁移：`V279__ad_campaign_reports.sql` — 只新建 1 张表 `ad_campaign_report_export`（导出留痕）。
  **零 ALTER 任何既有表**；事实表**直接用上游的** `ad_campaign_daily_metric` 与 `ad_campaign_sync_run`（V273），
  **不复制任何一列指标、不建第二套事实表、不建视图**。
- 端点：`GET /channel/ad-reports/{capabilities,options,rows,trend,export,exports}` 共 **6 个** —
  `rows` 真分页报表表、`trend` 按日/周/月分桶趋势（按币种拆成多条线）、
  `export` 导出 CSV（text/csv 由代理原样透传）、`exports` 导出留痕历史。
- 页面：`/ads/reports` — `apps/web/src/app/ads/reports/page.tsx` +
  `components/ads/ad-campaign-reports.tsx` + `ad-campaign-report-model.ts`；
  筛选维度：平台 × 店铺 × 内部广告类型 × 时间范围 × 分桶粒度；操作：多维对比 / 导出；
  **表头带口径定义**；TACOS 位置按「未接入（原因）」渲染而不是留一个恒 null 的字段；
  错误态/空态/加载态齐全。
- 测试：`AdCampaignReportIntegrationTest` **18 例**（真实 PostgreSQL 16，含租户隔离三断言）+
  `ad-campaign-report-model.test.ts` **22 例** + `ad-report-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. **TACOS：没有分母就不出数值，而不是造一个**。TACOS = 广告花费 ÷ 总销售额，
   总销售额来自 13.4 利润模块，而卷 13 的主责服务 reporting 不在集成头 ⇒ **TACOS 没有分母**。
   **被排除的三条**：用 0 当分母（得无穷大）；用广告销售额顶替总销售额（那算出来的是 ACOS 不是 TACOS，
   还会被当成事实传下去）；在 V279 里建一张「总销售额影子表」凑分母（把假口径固化成事实）。
   落地：capabilities 显式 available=false + ownerModule 指到 13.4 + impact 说明；
   **报表行不给 TACOS 字段** —— 留一个恒 null 的字段会让调用方误以为「取到了，只是恰好是空」。
2. **跨币种按币种拆开，不合并**：本仓没有汇率源（无汇率表、无汇率同步器）。
   分组键里始终带币种，汇总按币种拆成多份 ⇒ 「Amazon 的 USD 消耗」与「Amazon 的 EUR 消耗」永远是两行，
   不会被加成一个假总数。跨币种横向比较在 capabilities 里显式 available=false。
3. **聚合口径：先汇总再相除**。上游 `ad_campaign_daily_metric` 的 `ctr`/`acos`/`roas` 是
   **GENERATED 单行**列，报表是**多行聚合**列。**否决方案：对生成列求平均** ——
   那是数学错误（两条 ACOS 100% 与 50% 平均成 75%，整体其实是 27.5%），
   会让运营以为整体在盈亏线上、它其实在亏。集成测试用这两组数把差钉死。
4. **性能纪律**：时间范围**必填**（不给区间直接 400，不给「默认区间」）、桶数封顶、
   真分页、汇总单独一条查询。**否决方案**：「默认最近 30 天」这种省事选项 ——
   省事的是默认值，代价是悄悄的全表扫。
5. **不建视图**：建视图会让「先汇总再相除」变成第二套要维护的口径。
6. **数据截至时间显式**：每次响应都要能说清「截至什么时候」（`synced_at`）。

---

## 模块 14.3.2.2 效率诊断

**这个功能做什么**：系统主动扫出投放效率问题 —— 高花费零转化、ACOS 长期超标、预算受限、
竞价过低曝光不足、创意衰退 —— 每条给出**判定依据**和**可执行建议**，
预算与暂停能一键调整，竞价调整因数据源未就绪而显式标不可用。

**技术落地**：

- 迁移：`V280__ad_efficiency_diagnostics.sql` — 新建 5 张表：`ad_diagnosis_rule`（诊断规则）、
  `ad_diagnosis_run`（诊断运行）、`ad_diagnosis_run_rule`（逐规则执行留痕）、
  `ad_diagnosis`（诊断清单）、`ad_diagnosis_action`（建议动作）；
  **零 ALTER 任何既有表**；tenant_id NOT NULL + RLS + data_scope + GRANT 同口径。
- 端点：`/channel/ad-diagnostics` 共 **14 个** —
  `GET /capabilities`、`GET|POST /rules`、`GET /rules/{id}`、`POST /rules/{id}/status`、
  `POST /rules/defaults`、`GET|POST /runs`、`GET /runs/{id}`、
  `GET /diagnoses`、`GET /diagnoses/{id}`、`GET /actions`、`GET /actions/{id}`、
  `POST /actions/{id}/apply`。
- 页面：`/ads/diagnostics` — `apps/web/src/app/ads/diagnostics/page.tsx` +
  `components/ads/ad-efficiency-diagnosis.tsx` + `ad-efficiency-diagnosis-model.ts`；
  筛选维度：问题类型 × 活动 × 时间范围；操作：跑诊断 / 配规则 / 看判定依据 / 一键调整；
  创意衰退条目标「数据源未就绪」而不是给个假结论；错误态/空态/加载态齐全。
- 测试：`AdEfficiencyDiagnosisIntegrationTest` **14 例**（真实 PostgreSQL 16，含租户隔离三断言）+
  `ad-efficiency-diagnosis-model.test.ts` **24 例** + `ad-diagnostics-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. **五种判定口径写死在 DB 的 CHECK 里，阈值是租户可配的库行**（`ad_diagnosis_rule`），
   不是硬编码常量；默认阈值经 `POST /rules/defaults` 落成**真实库行**，可逐条改。
2. **上游 14.3 报表是「业务上下游」，不是代码依赖**。14.3.1.1 是同批另一条车道，
   此刻不在本车道 ⇒ 诊断指标全部从 14.1.1.1 已交付的事实表算（`ad_campaign` + `ad_campaign_daily_metric`），
   **不 import 14.3.1.1 的任何类**。代价：与 14.3.1.1 各算各的比率口径；
   收益：两条车道零耦合、可独立交付。
3. **「一键调整」复用 14.1.1.1 的写路径**（`AdCampaignService.bulkOperation`）：
   实测 14.2.2.2 自动规则（`ad_rules`）**不写 `ad_campaign`**（只落自己的执行与下发队列），
   真正能改活动且带上限/审计/幂等的已交付资产是 14.1.1.1 的 bulkOperation。
   上限（单次 5000 / 总额 20000 / 单活动 100000）、审计（`ad_campaign_operation_log`）、
   幂等（已是目标状态 ⇒ SKIPPED）全部复用，**不另写一套绕过护栏的写路径**。
4. **「竞价过低曝光不足」用可观测的替代量**：活动与每日指标表**都没有出价列**（出价在 14.1.3.1 的
   `ad_keyword.bid`，那是词级、不同口径）⇒ 用日均曝光 ÷ **同平台在投活动中位数**
   （`percentile_disc` 离散中位数，全程 numeric 无浮点），且**与「预算受限」互斥**。
   结论写「曝光不足，常见原因是竞价过低」，而**建议动作（提高竞价）标为不可用**。
   **否决方案**：编一个出价列或用假数据凑出确诊结论。
5. **创意衰退：显式标为数据源未就绪，不产出结论**。对应规则 `data_source_ready=false`，
   引擎**直接跳过**并在 `ad_diagnosis_run_rule` 写明「数据源未就绪」——
   不用随机数或假曲线假装有创意数据。连带效果：`ad_diagnosis_run_rule` 的存在理由就是
   「**没产出结论也要留痕**」（「没跑」与「跑了没命中」是两件事）。
6. **试运行两侧开关取更严的那个**：规则级 `dry_run` 与请求级 `dry_run` 取更严；
   默认规则**规则级关、请求级开** ⇒ 点一下默认只算出「本来会变成多少」，真实执行要显式确认；
   手工新建的规则**规则级默认开**（阈值还没验证过，不该动钱）。
7. **api-proxy 白名单仅新增一条 `ad-diagnostics/` 元素**，既有 100+ 条一字未动、不重排；
   首 token 在既有正则里零命中。

---

## L2 批量验证结果（**绿**）

在集成头 `integration/module-dev`（HEAD `6bd7b1d3`）上跑本批**唯一一次**全量验证：

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `cd backend && ./mvnw verify` | **BUILD SUCCESS** · **2450 例 / 0 失败 / 0 错误 / 0 跳过** · 43:13 min |
| 前端 | `pnpm typecheck` | exit 0（3/3 包） |
| 前端 | `pnpm lint` | exit 0（3/3 包） |
| 前端 | `pnpm test` | **432 文件 / 6488 例全绿** |
| 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **BUILD_EXIT=0** |
| 台账 | `node tools/module-progress/sync.mjs --check` | ✓ 台账与蓝图一致 |

后端 reactor 全绿：`tenancy / identity / catalog / channel / warehouse / trade / crm` 全部 SUCCESS。

**L2 总耗时 45 分 09 秒**（02:12:55 → 02:58:04），是本轮的发热高峰。

---

## 本批共同的纪律与验证

- **静态预检（合并前）**：本批 4 个迁移全部是**纯 CREATE TABLE**，
  `ADD COLUMN` 无 DEFAULT = 0 命中、`SET NOT NULL` = 0 命中、`ADD CONSTRAINT ... CHECK` = 0 命中；
  唯一的 `ALTER TABLE` 是对新表执行 `ENABLE/FORCE ROW LEVEL SECURITY`（安全形状）。
  ⇒ **零互相破坏形状命中**，L2 前未发现静态风险。
- **每条迁移的 RLS 与 GRANT**：tenant_id NOT NULL + ENABLE/FORCE RLS isolation +
  data_scope（RESTRICTIVE，按业务边界收敛）+ GRANT 给 `starcon_app` 且 `starcon_app` 零权限。
- **租户隔离三断言**：4 条车道的集成测试都实跑（两租户互不可见 / 伪造 `X-Tenant-Id` 头无效 /
  连接池压到 1 的 A→B→A 三轮不串租户），跑在 Testcontainers 真实 PostgreSQL 16 上。
- **金额与数量**：一律 `BigDecimal` + `NUMERIC(19,4)`（金额）/ `NUMERIC(19,6)`（数量），**零浮点**；
  比率用 `numeric` 与 `percentile_disc` 离散中位数，不做浮点平均。
- **时间字段**：`timestamptz` 一律用 `OffsetDateTime` 绑定并在 values 位置写 `?::timestamptz`
  （`Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s），写时间的测试**断言写回的值**。
- **api-proxy 白名单**：4 条车道各插一条**独立首 token** 的元素
  （`ad-campaign-details` / `ad-budgets` / `ad-reports` / `ad-diagnostics`），
  插入锚点互不相邻 ⇒ git 求并干净；既有 100+ 条元素一字未动、也不重排。
  每条车道都跑了全量 allowlist 回归（`apps/web/src/app/api/channel` + `apps/web/src/app/api/trade`，
  **116 文件 608 例全绿**）。
- **未交付能力的表达纪律**（本批四车道共同遵守）：没有事实源的维度一律
  **available=false + ownerModule + impact**，不建空表、不用假零、
  不把上一个维度的指标冒充本维度。

## 已知缺口（如实记录）

| 模块 | 缺口 | 责任模块 |
| --- | --- | --- |
| 14.1.1.2 | 平台侧修改历史无同步源；平台回写队列未交付（改竞价/复制结构只落本地） | 平台广告 API 同步器 |
| 14.1.1.2 | 广告组的完整管理（定向设置 / 组级报表入口） | 14.1.2.1 |
| 14.1.1.2 | 竞价策略（自动/动态/目标 ACOS）、投放定位解读 | 14.2.1.1 / 14.1.3.2 |
| 14.2.2.1 | 利润权重（13.4 利润未交付）；跨币种分配（5.2.2.1 汇率未交付） | 13.4 / 5.2.2.1 |
| 14.3.1.1 | TACOS（13.4 总销售额未接入）；跨币种横向比较（汇率源未交付） | 13.4 / 汇率源 |
| 14.3.2.2 | 创意衰退（创意数据源未就绪）；竞价一键调整（活动级无出价列） | 14.1.1.2 创意 / 14.2.1.1 |

---

## 本轮新派发的 4 条车道（**尚未并入，下一轮回收**）

本轮在容量闸全绿的前提下派发了 4 条互不依赖的车道，worker 全部完成并 `land`（state=`ready`），
**但它们不在本批 L2 的验证范围内** —— L2 验证的是本轮**并入**的 4 条车道。
下一轮 `merge-ready` 时并入并做那一批的 L2。

| 模块 ID | 名称 | 迁移 | 分支 | HEAD | worker 报告要点 |
| --- | --- | --- | --- | --- | --- |
| 14.1.2.1 | 广告组管理 | `V281__ad_group_management.sql` | `feat/14-1-2-1` | `c075e2c9` | 9 端点 `/channel/ad-groups/**`；在 V277 `ad_campaign_group` 上**补列**（`match_type` / `targeting_settings` / `pending_platform_writeback`，**均 NOT NULL DEFAULT**）+ 新建 `ad_group_operation_log`；15 后端例 + 19 + 6 前端例 |
| 14.1.1.3 | 批量操作 | `V282__ad_campaign_bulk_operations.sql` | `feat/14-1-1-3` | `2c704847` | 7 path `/channel/ad-bulk-operations/**`；**服务端**筛选解析 id 集合（keyset + 上限 1000）+ 预览 + 异步任务；8 个护栏上限，**超限整批拒绝**（与 14.1.1.1 的逐条拒绝刻意不同）；22 后端例 + 17 + 5 前端例 |
| 14.3.1.2 | 广告组与词报表 | `V283__ad_group_keyword_reports.sql` | `feat/14-3-1-2` | `3d92801b` | 6 path `/channel/ad-group-keyword-reports/**`；三维度（组/词/搜索词）真分页 + **DB 级缓存**（无数据水位显式 BYPASS，不返回「不知何时过期」的结果）；19 后端例 + 23 + 6 前端例 |
| 14.4.1.1 | 促销列表 | `V284__ad_promotion_lists.sql` | `feat/14-4-1-1` | `02291a2b` | 7 path / 10 端点 `/channel/ad-promotions/**`；四条真规则（`DISCOUNT_DEPTH` / `PRICE_FLOOR` / `PROMOTION_STACKING` / `BUDGET_CAP`）+ 拦截；毛利维度 available=false + ownerModule='5 定价'；14 后端例 + 15 + 5 前端例 |

### ⚠️ 下一轮并入时必须处理的 3 件事

1. 🔴 **`channel.openapi.json` 跨 build 排序不稳定** —— 14.3.1.2 车道的 codegen diff 达 36703 行
   （多为重排噪声，非语义变化）。**并入后应按 codegen 再生成一次，不要按文本求并。**
2. 🔴 **14.1.1.1 的 capabilities 快照仍把 14.1.1.3 的三项能力标为 `available=false`**
   （按筛选全选 / 预览 / 异步任务）。14.1.1.3 已交付，但 14.1.1.1 的页面文案目前是**过期**的 ——
   需要一次跨模块收口，否则界面对用户说谎。
3. **既有 `trade.*` 契约漂移**（MassProduction* DTOs，另一车道的 Python 序列化产物）
   在集成头上仍然存在。两条车道都刻意没有越界改它，**该由 trade 责任车道重新生成一次**。
