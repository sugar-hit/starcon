# 2026-10-21 6.1.1.3 供应商评级：五维客观打分 + 人工加权分单独标记

## 决策摘要

交付能力蓝图 06 卷 6.1.1.3「供应商评级」（阶段 D6，L3 锚点 6.1.1），落在 **trade** 服务。

蓝图原文：「**职责**：按周期（默认月度）自动统计供应商表现：交期达成率、质量合格率、响应时长、
价格竞争力、售后配合度，综合打分并支持人工调整权重。**输入**：收货事件时间与质检结果；采购单沟通记录；
权重配置。**输出**：评级结果与排名；评级变动历史；影响采购建议的默认供应商优先级。
**关系**：上游 = 6.4.2 质检、6.3.2.1 跟单；下游 = 3.6.2 报价优先级、6.2.1.2 补货建议。
**评级必须由客观数据算出**，人工加权分需单独标记，否则会变成纯主观打分。」

- **归属服务 = trade**：卷 06 在蓝图 README 里的「后端主责服务」是 `trade + catalog（party）`，
  列表第一项 = trade（`tools/module-progress/lane.mjs` 的微服务盲区闸读的就是这张表）。
  且本模块的五个维度事实**全在 trade 库内**，只有市场报价需要跨服务读。
- **迁移** `V266__supplier_rating.sql`：四张新表、**零 ALTER 零 DROP 任何既有表**
  （跨车道 DDL 回归面 = 0）：
  `supplier_rating_weight_policy`（租户级权重配置）/ `supplier_rating_result`（每供应商每周期一行）/
  `supplier_rating_adjustment`（人工加权分明细，追加式）/ `supplier_rating_change`（评级变动历史，追加式）。
  四表 `tenant_id NOT NULL` + ENABLE/**FORCE** RLS isolation + GRANT；两张留痕表的运行角色
  UPDATE/DELETE 已收回。
- **五个维度的口径**（分子 / 分母 / 数据源，逐条写进迁移文件第二节与台账证据）：
  1. **交期达成率** = 期内到货次数中「到货时刻生效的承诺日」未逾期的占比。
     数据源 6.3.2.2 的到货流水 + 6.3.2.1 的交期变更；承诺日口径与**跟单延迟统计逐字同源**
     （到货时刻之前最后一次交期变更的 `to_promised_date`，没有变更取采购单的 `promised_arrival_date`）。
  2. **质量合格率** = Σ合格量 ÷ Σ覆盖批量（**按数量比，不按批次数比**），数据源 6.4.2.1 已判定完成的来料质检单。
  3. **响应时长** = 「上一次未答复 → 本次答复」的等待窗口的平均小时数，数据源 6.3.2.1 的跟单记录。
  4. **价格竞争力** = 期内采购过的 SKU 上「同币种最优价 ÷ 本供应商价」的按采购量加权平均，
     数据源 catalog 3.6.2 有效报价台账（**跨服务只读**）+ 6.3.1.1 采购单行。
  5. **售后配合度** = 期内提交的索赔里「供应商侧参与过协商」的笔数占比，数据源 11.2.3.3 的供应商索赔与协商轮次。
- **「不可用」≠「0 分」**：分母为 0 的维度**不参与合成**（得分与原始指标一律可空），
  合成按**可用维度归一**（Σ权重×得分 ÷ Σ可用权重，仍是 0~100 的可比值），
  结果行同时报出 `available_weight` / `unavailable_dimensions` / `unavailable_reason`。
- **人工加权分单独标记**：`objective_score`（纯客观）/ `manual_adjustment`（人工调整量 ±50，理由必填）/
  `final_score`（最终分）三者**并存**，DB 层 CHECK 钉死
  `final_score = greatest(0, least(100, objective_score + manual_adjustment))`，
  再加 `manually_adjusted` 布尔标记 + 追加式调整明细 + 变动历史三处留痕。
  人工调整**不重算**客观分；重算也**不抹掉**人工调整。
- **端点 11 个**挂 `/trade/supplier-ratings`：capabilities / options / overview / policy（GET+PUT）/
  compute（POST）/ ratings（列表+详情）/ ratings/{id}/adjustment（POST）/ changes / priorities。
  权限码 `trade.ratings.read` / `trade.ratings.update`；租户只来自已认证成员关系，**不读 X-Tenant-Id 头**。
- **默认供应商优先级** = `GET /priorities`，供 6.2.1.2 补货建议消费，
  **刻意不替下游选供应商**（补货建议还要看 MOQ / 箱规 / 起订额 / 交期）。
- **页面** `/supply/ratings`（`supplier-rating.tsx` + `supplier-rating-model.ts`），
  四个工作区：评级结果 / 权重配置 / 变动历史 / 默认供应商优先级。
  台账「页面」列记的是 L3 入口 `/supply/vendors`（那一列由 sync.mjs 从蓝图生成），
  评级是同 L3 下的第三格，单开 `/supply/ratings` 与兄弟模块 6.1.1.1 / 6.1.1.2 互不覆盖。
- **api-proxy 新增一条独立 `services[]` 元素**（插在**数组最开头**，与其它车道
  「`prefix: 'trade/'` 之前」或「数组末尾」的插入点不重叠）；首 token `supplier-ratings`
  对既有 100+ 条正则**逐条核对过零命中**（既有的是 `supplier-claims` / `supplier-qualifications` / `supplier-sources`）。
- **测试**：`SupplierRatingRulesTest` 35 例（纯规则）+ `SupplierRatingIntegrationTest` 28 例 +
  `SupplierRatingTenancyIntegrationTest` 3 例（真实 PostgreSQL 16 + Testcontainers，连接池压到 1，
  多租户三断言实跑）。契约由 codegen 重生成（`trade.ts` 新增 15 个 `SupplierRating*` 类型、零删除，
  其它五个服务零漂移）。

## 关键 trade-off

1. **交期的「承诺日」取「到货时刻生效的那个承诺日」，不是单据上的承诺日。**
   蓝图把上游点名成 6.3.2.1 跟单与交期，而**跟单与交期**这一模块的延迟统计就是按
   「到货时刻之前最后一次交期变更」取承诺日的。本模块逐字复用同一口径（同一套子查询形状），
   而不是自己另取一个「单据承诺日」—— 同一件事在两个模块里用两个口径，运营对着两个数字做决策时
   没人知道该信哪个。代价是本模块的口径要跟着 6.3.2.1 走：那边改了承诺日口径，这边要跟着改。
2. **「响应时长」的可测窗口是「上一次未答复 → 本次答复」。**
   上游 `purchase_followup_record` 把「我方说了什么」与「供应商答了什么」记在**同一行**上，
   **没有独立的答复时刻列**。所以可测的等待窗口只有「上一次没答 → 这一次答了」这一段；
   第一次沟通就答了的记录**不构成样本**（没有可测的等待窗口），宁可少算也不要编一个响应时长出来。
   响应得分的映射是线性的（目标小时数 = 一半分，两倍目标 = 0 分），曲线的拐点运营答不出
   「60 分对应几小时」。
3. **价格竞争力只比同币种，且报价未达起订量的样本不进比价。**
   本服务**没有汇率表**（汇率是 12 财务的资产），100 USD 与 90 EUR 直接比大小是错的；
   拿「别人 MOQ 1000 的入场价」去比「我们买 10 件的价格」条件也不同。
   两类样本都**排除在分子分母之外**，并在结果行报出样本数与可比量 ——
   一个不说清「是按哪一段、几个样本算的」的数字在供应商评审里没有意义。
4. **价格维度的原料走 catalog 的授权只读 API（`GET /catalog/supplier-quotes` 的 `effectiveOnly` 视图），
   不跨库读表。** AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以
   `SupplierRatingQuotePort` 逐字照抄本仓既有的跨服务端口写法（转发调用方自己的
   `Authorization` bearer ⇒ 隔离是 trade RLS + catalog RLS 两道）。
   翻页上限 5 页（1000 条有效报价）**如实报出截断**（`truncated`），不静默少算。
   ⚠️ catalog 不可达时**只把价格这一维标成不可用**，其余四个维度照算 ——
   商品主数据服务挂掉不该让采购的交期与质量评分也一起消失。
5. **合成按可用维度归一（Σ权重×得分 ÷ Σ可用权重），而不是除以 1。**
   除以 1 的话，五个维度里只算出三个的供应商会被硬生生扣掉两成权重，
   看起来比同口径下算出五个维度的供应商差 —— 而那个差距是**没有依据**造成的，不是表现差。
   代价是分数与「五维齐全」的供应商不完全可比，所以 `available_weight` 必须跟着分数一起报出来，
   界面上也要显示「覆盖权重 60%」。
6. **最终分的夹逼写进 DB 的 CHECK，而不是只在应用层做。**
   `final_score = greatest(0, least(100, objective_score + manual_adjustment))`。
   只在应用层夹逼的话，重算路径（客观分变了、人工调整保留）与人工调整路径会走两套不同的等式，
   迟早对不上。写成 CHECK 之后两条路径共用同一条等式，DB 层一次钉住。
   人工调整量**保留请求值**（它是「谁提的要求」的凭据），实际增量 = 最终分 − 客观分，
   页面上两个数并排显示，看得出来。
7. **重算不抹掉人工调整。**
   抹掉它等于「谁改过就白改了」，而调整明细表里还留着那一行。代价是客观分涨上去之后
   人工调整可能被夹逼吃掉（最终分封顶 100），这一点在台账与页面上都写明了。
8. **不替下游选供应商。**
   蓝图输出句是「影响采购建议的**默认供应商优先级**」—— 本模块产出优先级清单与依据
   （名次 / 客观分 / 最终分 / 等级 / 有没有人工调整 / 哪些维度没依据），
   6.2.1.2 补货建议还要看 MOQ / 箱规 / 起订额 / 交期，替它选完就是越界。
   同样地，3.6.2 报价优先级是**下游**，所以价格竞争力取的是**报价台账里的市场价比**，
   而不是「我们付过的价 vs 别人的价」—— 后者会把「评级 → 报价优先级 → 评级」变成自指回路。
9. **周期与权重是租户自己的经营决定，所以「计算评级」是显式写动作，不做自动定时任务。**
   同周期重算**覆盖**同一行（唯一键 = 租户 + 周期类型 + 周期起日 + 供应商），不是堆出第二份事实；
   分数或等级真的变了才留一行变动历史。
10. **一个维度都算不出来 ⇒ 不写评级行。**
    写一行分数为 0 的评级，等于把「没有依据」说成「表现极差」。
    这类供应商本期不出现在评级清单里（计算结果里报出「覆盖 N 家」）。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V266__supplier_rating.sql`（新建）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierRatingModels.java`（新建）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierRatingRules.java`（新建）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierRatingService.java`（新建）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierRatingQuotePort.java`（新建）
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierRatingController.java`（新建）
- `backend/services/trade/src/test/java/com/starcon/trade/SupplierRatingFixture.java`（新建）
- `backend/services/trade/src/test/java/com/starcon/trade/SupplierRatingRulesTest.java`（新建，35 例）
- `backend/services/trade/src/test/java/com/starcon/trade/SupplierRatingIntegrationTest.java`（新建，28 例）
- `backend/services/trade/src/test/java/com/starcon/trade/SupplierRatingTenancyIntegrationTest.java`（新建，3 例）

**前端**

- `apps/web/src/components/supply/supplier-rating-model.ts`（新建）
- `apps/web/src/components/supply/supplier-rating-model.test.ts`（新建，22 例）
- `apps/web/src/components/supply/supplier-rating.tsx`（新建）
- `apps/web/src/app/supply/ratings/page.tsx`（新建）
- `apps/web/src/app/api/trade/supplier-rating-allowlist.test.ts`（新建，6 例）
- `apps/web/src/lib/api-proxy.ts`（改：**新增一条独立 `services[]` 元素**，不并既有 trade 长正则）

**契约与台账**

- `packages/api-contracts/src/contracts/trade.ts`（codegen 产物：新增 15 个 `SupplierRating*` 类型）
- `packages/api-contracts/src/trade.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（改：**只改 6.1.1.3 那一行**的状态与证据两格）

## 后续待办

1. **6.2.1.2 补货建议消费优先级清单**：本模块已给出 `GET /trade/supplier-ratings/priorities`，
   但补货建议侧**尚未**接（那是 6.2.1.2 自己的模块，本车道不改它的表与端点）。
2. **3.6.2 报价优先级消费评级**：蓝图把 3.6.2 列为下游，报价优先级要按评级排序需要 3.6.2 侧改。
3. **6.1.1.2 供应商详情里挂评级区块**：详情页（catalog 服务）目前看不到评级结果，
   跨服务读 trade 需要一个只读端口，属 6.1.1.2 的范围。
4. **响应时长的口径要跟上游一起演进**：若 6.3.2.1 后续给跟单记录补上「答复时刻」独立列，
   本模块的响应窗口应改成「跟单发出 → 答复登记」的完整窗口（现在的口径是它的一个下界）。
5. **评级周期目前只支持月度与季度**，蓝图原文只要求「默认月度」，更细的周期（周 / 旬）未做。
