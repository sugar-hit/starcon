# 2026-10-20: 10.6.2.1 时效分析落地（channel 服务）

## 摘要

在 channel 服务落地能力蓝图模块 **10.6.2.1「时效分析」**（卷 10 L3 锚点 10.6.2，阶段 D5）。
产出**渠道时效报表 + 渠道时效评分 + 异常集中分析**三件事，事实源是同库上游 10.3.1.1 的
`logistics_tracking_subject`（V212），本模块**不新建任何事实表**，只把算出来的时效评分
落一份可审计的台账（V218 `logistics_timeliness_score`）。

蓝图加粗句「**时效数据要按渠道和国家交叉分析，总体平均会掩盖某些渠道在某些国家的系统性延误**」
是本模块唯一不可妥协的约束，它在三个地方同时落地（见下）。全程**不依赖**尚未并入的
10.6.1.1 成本分析：成本侧结论在 capabilities 里显式报 `available=false` + `ownerModule` + 影响。

## 决策 / 结论

1. **交叉维度进 SQL、进存储形状、进异常分析**（三处同时做，缺一处就退化成「一个总体平均」）：
   - 查询层：矩阵一律 `group by channel_id, country_code`；**没有**一条只按渠道或只按国家的查询，
     总体口径只出现在 `totals` 里。
   - 存储层：`logistics_timeliness_score` 的唯一键是
     `(tenant_id, channel_id, country_code, window_start, window_end)` ——
     **物理上就存不下「这条渠道总体表现」那一行**。
   - 分析层：异常集中分析的贡献率 / 累计贡献率在「渠道 × 国家」的格上算，
     所以「哪个渠道在哪个国家拖后腿」能被直接读出来，而不是只剩一句「某渠道表现差」。
   - 同一口径在 10.1.2.2 比价（V143 的 `channel_rating_score`）里已先行落地一次，
     两处一致 ⇒ 比价将来换数据来源不需要改表结构。

2. **比率的分母是「可比数」而不是「签收数」**。已签收但**没有承诺时效可比**的那批票单列
   `no_promise_count`：把它们算成准点或超时都是编造；塞进分母会让「没登记承诺」显示成
   「100% 不准点」，把运营的注意力引到根本不是问题的地方。这是仓库里第二次用
   「不可比不是 0 分」这条口径（第一次在 10.1.2.2 比价）。

3. **样本不足 ⇒ 评分是 null（不可比）而不是 0 分**。门槛（默认 10，可按请求调）随快照落库 ——
   事后改门槛不能让已经落库的那一格看起来是「够样本」算出来的。DB 用 CHECK 把
   「等级 ↔ 分数 ↔ 样本充分性」三者钉死。

4. **三个比率用 GENERATED 列**（`GENERATED ALWAYS AS … STORED`）：服务层写不进去，
   绕过服务层直接写 SQL 也编不出一个与计数对不上的比率 —— 比率与计数打架是「假报表」的头号来源。
   实时报表的比率在 Java 侧按同一口径算（先乘 10⁴ 再整数除 + HALF_UP），
   保证与 PG 的 `round(numeric, 4)` **逐位相同**（二次舍入会差最后一位）。

5. **时效差走 timestamptz 相减**，不做 LocalDateTime 差值。差值在 SQL 里算
   （`extract(epoch from (latest_occurred_at − promised_eta_at)) / 3600`）——
   timestamptz 相减是绝对时刻差，与会话时区无关；跨时区渠道的时效不会因为数据库跑在
   哪个时区而变。

6. **承诺时效取发货当时的快照**（V212 主体表上的 `promised_eta_at`），
   **不 join `logistics_transit_rule` 重算**：事后改档期不能让一笔已经延误的货变成准点。

7. **月份分桶固定用 UTC**：换台数据库换时区，报表上的月份不会整个错位。

8. **窗口右边界固定到 UTC 自然日**（没给 `to` 时取明天 00:00，而不是「此刻」）：
   窗口键必须**在同一天里保持稳定**，否则「重算并落库」每次都开出一个新窗口键，
   幂等重算就变成了堆历史快照。落库用「同一事务里先删后插」实现幂等。

9. **本模块不写 `channel_rating_score`（V143）**：那张表的写入所有者是 10.1.2.2。
   比价换数据来源是比价那条车道的事（V143 的 `score_source` 已预留位），
   本模块只产出自己的台账 + 在 capabilities 里说清这个缺口。

10. **api-proxy 白名单走「新加一条 services[] 元素」**，插入锚点固定在
    10.7.1.1 `return-dispositions/` 之后、10.1.2.1 `freight/` 之前（与另外三条车道的锚点
    两两被既有条目隔开，git 能干净求并）。**本条目一段 uuid 都没有** ——
    本模块的端点全是「集合读 + 明细读」，没有按 uuid 定位单个资源的形状；
    少一段正则胜过写一段防错（也就不用操心「单字符 e 被当 uuid 放行」）。

## 涉及文件

**迁移**

- `backend/services/channel/src/main/resources/db/migration/V218__logistics_timeliness_analysis.sql`

**后端**

- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsTimelinessModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsTimelinessService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsTimelinessController.java`

**测试**

- `backend/services/channel/src/test/java/com/starcon/channel/LogisticsTimelinessIntegrationTest.java`（16 例）
- `backend/services/channel/src/test/java/com/starcon/channel/LogisticsTimelinessTenancyIntegrationTest.java`（3 例，多租户三断言）

**前端**

- `apps/web/src/app/logistics/timeliness/page.tsx`
- `apps/web/src/app/logistics/timeliness/timeliness-model.ts`
- `apps/web/src/app/logistics/timeliness/timeliness-model.test.ts`（20 例）
- `apps/web/src/app/logistics/timeliness/timeliness-workspace.tsx`
- `apps/web/src/app/logistics/timeliness/timeliness-proxy.test.ts`（10 例，api-proxy 白名单回归）
- `apps/web/src/lib/api-proxy.ts`（**只新增一条 `logistics-timeliness/` 元素**，既有条目一字未动）

**契约 / 台账**

- `packages/api-contracts/src/contracts/channel.ts`（codegen 产物，含 18 个 `Timeliness*` 类型）
- `packages/api-contracts/src/channel.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（只改 `10.6.2.1` 那一行的状态与证据两格）

## 端点

```
GET  /channel/logistics-timeliness/capabilities
GET  /channel/logistics-timeliness/overview
GET  /channel/logistics-timeliness/reports/matrix          🔴 渠道 × 国家交叉报表
GET  /channel/logistics-timeliness/reports/monthly
GET  /channel/logistics-timeliness/reports/anomalies
GET  /channel/logistics-timeliness/reports/scores
GET  /channel/logistics-timeliness/reports/score-snapshots
POST /channel/logistics-timeliness/reports/score-snapshots  唯一写入口（幂等）
GET  /channel/logistics-timeliness/subjects
```

## 后续待办

- **10.1.2.2 比价换评分来源**（那条车道的活）：比价的 `channel_rating_score` 当前仍是
  `score_source=MANUAL_ENTERED` 人工登记；本模块的 `logistics_timeliness_score` 台账可作为
  回灌来源，表结构与排序逻辑不需要改。**本模块刻意不写那张表**（每张事实表只有一个写入所有者）。
- **10.6.1.1 成本分析**（未交付）：并入后可以把「时效 × 成本」的交叉结论接上；
  本模块目前**只做时效维度**，不含任何成本。
- **9.5.1.1 订单时效**（trade 服务）：本模块只到**渠道时效**，
  订单级 SLA 达成在 trade 侧，两者未联动。
- **10.1.1.3 时效承诺设置**：本模块**只读**发货当时的承诺快照，
  不提供改承诺的入口（改承诺会改变「已延误」的判定口径，那属于 10.1.1.3）。
