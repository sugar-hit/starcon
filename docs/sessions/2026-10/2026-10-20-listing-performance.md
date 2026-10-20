# 4.5.1.1 Listing 表现（D5 阶段，L3 锚点 4.5.1）

> 车道 `feat/4-5-1-1`，worktree `.worktrees/4.5.1.1`，基线 `integration/module-dev` 的 `11f19237`。
> 迁移 `V203__listing_performance.sql`（channel 服务）。

## 决策摘要

蓝图 §4.5.1.1 只有四行，成立的与否全在一句加粗上：

> **「表现数据来自平台而非内部订单」**，与内部订单口径不同，**展示时需标注来源**。

所以本模块的第一件事不是写指标，而是**先把「平台口径」变成结构**：

1. **表里存的每一个数都是平台回报的口径**，不是内部订单聚合出来的 ——
   曝光 / 点击 / Session / 销量 / 销售额 / 退货件数 / 退货金额 / 评分 / 评价数。
   列名与表注释都写明「平台口径，非内部订单」。
2. **本模块不去 trade 的订单表里聚合**。AGENTS.md 明令禁止跨服务查表 ⇒ 表现数据
   只能由 **ingest 端点推进来**（`POST /channel/listing-performance`），
   迁移 **零 ALTER 任何既有表**、零跨服务 FK、零跨库 SELECT。
3. **展示层必须标注来源** ⇒ 后端每个响应都带 `dataSource='PLATFORM_REPORTED'`
   与来源说明（**契约级**的硬要求，不是页面上的装饰）；前端把它固定显示在页面顶部，
   且**每个指标卡**都带「平台口径」角标。

**平台销量与内部订单行数天然对不上**（平台按平台自然日归集、含取消与部分退款；
内部订单按本系统单据归集）。这两个数一旦被当成一回事，选品结论直接就是错的。
蓝图没给「口径换算」的定义，本模块也**不伪造一个换算系数**把两套数硬凑成一个 ——
那只是把一个错误藏得更深。

**「对比」是硬需求**（蓝图原文「支持时间范围对比」），落成：
本期 vs 上期逐指标给出**变化量 + 变化率**；上期不给时默认取**紧邻的等长区间**
（`[from − N 天, from − 1 天]`），显式给 `compareFrom` / `compareTo` 也生效。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 比率（点击率 / 转化率 / 退货率）**落表**还是**现算** | **不落表，SQL 现算** | 照抄 9.5.2.1 履约质量：「落了表就会与事实漂移」（改了分母那一行，比率列不会跟着变）。所以本迁移**没有**任何比率列 |
| 比率取**日均值的平均**还是**分子合计÷分母合计** | **分子合计 ÷ 分母合计** | (50/1000 + 100/2000)/2 = 0.0375 ≠ 150/3000 = 0.05。前者把「小流量日」和「大流量日」等权，是错的口径 |
| 分母为 0 时返回什么 | **null**（不是 0、不是 100） | 报 0 会被读成「转化率 0%」—— 那是**结论**不是缺口；报 100 更荒谬。上期为 0 时变化率同理（不是 0、不是 +∞） |
| 一张表还是两张 | **两张**（`listing_performance_snapshot` 事实 + `listing_performance_sync` 同步结果） | 蓝图输入写的是「平台表现数据**同步结果**」，不是「平台表现数据」。一次同步是一个**有范围、有通道、有成败**的整体；挂在快照行上会出现「同一批数据被拆成 N 行、每行各说各话」。分开之后「还没同步过」与「同步过但区间没数据」才分得开 |
| 同一天平台改了数据怎么办 | **覆盖**（`UNIQUE(tenant_id,listing_id,stat_date,source)` + `revision` 递增 + `last_sync_id` 指向最后写入它的批次） | 日表现报表**不是事件**（不像 4.3.2.2 的状态事件那样 append-only）。平台修正同一天的报表是常态，最后一次是当前事实 —— 但「改了几次」要留痕 |
| 重复投递同一批数据怎么办 | **整批不落库**（`listing_performance_sync.idempotency_key` 租户内唯一，回执 `replayed=true`） | 与「同一天覆盖」是两件不同的事：**重投**用同一个键（跳过），**修正**用新键（覆盖）。把两者混成一个机制，就分不清「平台又发了一遍」与「平台改了数据」 |
| 幂等键由谁给 | **调用方必填**，服务端不替它编 | 服务端自己编的键要么按内容哈希（那平台重投时哈希变了，去重失效），要么按 listing+区间（那平台的修正会被当成重投而静默丢弃）。**没有它就分不清「重投」与「修正」** |
| 统计日落在声明区间外的快照 | **跳过 + 逐条写明原因**（进 `detail.skipped`） | 静默丢弃会让调用方以为「平台报了 7 天，我们收下了 5 天」却不知道为什么少了 2 天 |
| 负数 / 缺统计日 | **整批拒绝**（422），不落半批 | 半批落库会留下「半个区间有数据」的现场，比全批失败难查得多 |
| 统计日用 `date` 还是 `timestamptz` | **`date`**（平台自然日） | 平台按**平台自然日**归集，用 timestamptz 会因为时区把一整天劈成两半。同步批次的**口径区间**仍然是 timestamptz（平台给的绝对时刻） |
| 趋势按什么归集 | **`stat_date`（平台统计日）**，日 / 周 / 月三种粒度 | 与内部下单日不是一回事。`date_trunc` 用在 `date` 上不涉及时区平移 |
| 页面路由 | **新建 `/listings/monitor`** | 4.5.1 表现监控下有 4.5.1.1（本模块）与 4.5.1.2 关键词排名（未交付）。`monitor` 是表现监控的通名，4.5.1.2 交付时可以直接挂在同一条路径树下 |
| API 前缀 | **`/channel/listing-performance`（独立前缀）** | 4.1.1.1 已把 `/channel/listings/{uuid}/...` 登记成「不放行」，在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单。而且「平台表现」与「平台状态回流」是**两条独立通道**（一个把状态收回来、一个把表现收回来），口径、限流与重试互不干涉 |
| 权限码 | 复用 `channel.operation.read` / `.create` | 新增要动 identity 的权限真相源，是别的车道的事。与 4.1.1.1 / 4.3.2.2 / 4.4.2.x 同族 |
| 契约的 schema 名 | 全部 `ListingPerformance*` / `PerformanceSnapshotInput` / `IngestPerformanceRequest` | springdoc 用 record 的**简单类名**做 `components.schemas` 的键，重名会**静默覆盖**别人的契约（7.8.2.1 已踩过 `AllocationRunPage` 撞名） |
| 白名单怎么改 | **新增一条 `services[]` 元素**，插在 4.3.2.2 `listing-status-events` 那一条**之后** | 既有 channel 分支是**单行超长正则**，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。本批另外三条车道的锚点（`listings` / `listing-claims` / `one-click-publish` 之后）互不重叠，git 能干净求并 |

## 未交付的上游 / 下游（如实标注，不假装有）

`GET /channel/listing-performance/capabilities` 逐维度声明，四段 `available=false`：

| 维度 | 归属模块 | 本模块怎么表达 |
| --- | --- | --- |
| 13 产品表现（跨商品汇总 + 利润口径） | 13 产品表现与利润分析（整卷未交付） | 本模块只看**单个 listing**；页面上写「未接入」并点名归属，**不假装**给出这个下钻 |
| 14 广告（关联投放） | 14 广告与增长（整卷未交付） | 广告带来的流量与花费不在平台回报的日表现里；**不**用「曝光里有多少来自广告」这种猜出来的口径替代 |
| 真实平台流量抓取调度器 | 02 渠道与店铺（同步运行时） | 本模块提供**入库端点**（通道 WEBHOOK / POLL / API 都记下来），但**没有**那个定时调度器 —— 平台限流与店铺配额由 02 统一裁决 |
| 与内部订单口径的对账 | 09 订单与履约系统 | 平台销量与内部订单行数**天然对不上**；本模块跨服务不读 09 的表，也**不**伪造一个「换算系数」把两套数硬凑成一个 |

## 前端这个页面上最容易做丢的五件事

1. **数据来源标在界面上** —— 页面顶部固定横幅 + 每个指标卡「平台口径」角标。
2. **前端不重算比率** —— `ctr` / `conversionRate` / `returnRate` 是服务端按固定分子分母算好的；
   **分母为 0 时是 null**，页面显示「—」，**绝不显示成 0%**（「没有样本」与「真的是 0」是两件事）。
3. **变化率同理** —— 上期为 0 时 `changeRate` 是 null，显示「—（上期为 0）」，
   不显示 0% 也不显示 +∞。
4. **趋势没有数据时是空态，不是平线** —— 一条水平的假曲线会让人以为「这段时间表现平稳」，
   而真相是「平台没报数据」。
5. **两种空态文案不同** —— 「还没同步过」与「同步过但这个区间没数据」是**两回事**，
   不能共用一句话，也不能共用一个假图表。

## 文件清单

**后端（channel 服务）**

- `backend/services/channel/src/main/resources/db/migration/V203__listing_performance.sql`（新建，两表 + 索引 + RLS + GRANT + 两个触发器）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPerformanceModels.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPerformanceService.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPerformanceController.java`（新建）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingPerformanceIntegrationTest.java`（新建，22 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingPerformanceTenancyIntegrationTest.java`（新建，4 例）

**前端**

- `apps/web/src/app/listings/monitor/page.tsx`（新建）
- `apps/web/src/components/listing-performance/listing-performance-model.ts`（新建）
- `apps/web/src/components/listing-performance/listing-performance-model.test.ts`（新建，25 例）
- `apps/web/src/components/listing-performance/listing-performance-workspace.tsx`（新建）
- `apps/web/src/app/api/channel/listing-performance-allowlist.test.ts`（新建，4 例）

**共享白名单 / 契约 / 台账（改动最小）**

- `apps/web/src/lib/api-proxy.ts`（**只新增一条 `services[]` 元素**，42 行全为新增，零删除）
- `packages/api-contracts/src/channel.openapi.json` + `contracts/channel.ts`（codegen 产物，**禁止手改**；
  纯新增 4 条 path + 13 个 schema，**零删除**，其余 5 个服务的契约逐字节不变）
- `docs/product/capability-blueprint/module-progress.md`（**只改 4.5.1.1 那一行**的状态与证据两格）

**刻意没动**：`packages/ui/src/lib/task-pages.ts` / `nav-config.ts`（最近交付的车道都是只建页面不登记）、
`backend/services/channel/pom.xml`（四条车道共用）、别的模块的迁移 / 端点 / 页面 / 测试。

## 端点清单

| 端点 | 作用 |
| --- | --- |
| `GET /channel/listing-performance/capabilities` | 逐维度能力声明 + 指标口径 + 数据来源标注 |
| `POST /channel/listing-performance` | 🔴 接收平台表现数据同步结果（批次级幂等） |
| `GET /channel/listing-performance` | 表现快照列表（真分页 + listing/店铺/平台/统计日范围筛选） |
| `GET /channel/listing-performance/{listingId}` | 单个 listing 的表现明细 + 本期 vs 上期对比 |
| `GET /channel/listing-performance/{listingId}/trend` | 趋势序列（日 / 周 / 月） |

## 验证

- `./mvnw -q compile` → 退出 0
- `./mvnw -pl services/channel -am test -Dtest='ListingPerformance*'` → **26 例 0 失败 0 错误 BUILD SUCCESS**
- `TURBO_FORCE=true pnpm typecheck` → 3/3 通过
- `TURBO_FORCE=true pnpm lint` → 通过（告警全部落在**既有**其它文件，本车道零告警）
- `pnpm vitest run apps/web/src/app/api/channel/listing-performance-allowlist.test.ts apps/web/src/components/listing-performance` → **29 例全绿**
- `pnpm vitest run apps/web/src/app/api/channel apps/web/src/app/api/trade` → **68 文件 348 例全绿**（全量 allowlist 回归）
- 契约：codegen 连续跑两次 shasum 一致（**幂等**）；`channel.ts` 类型集合 **零删除、13 新增**

## 后续待办

1. **13 产品表现**（整卷未交付）—— 交付后由它读本模块的表现数据做跨商品汇总；
   本模块的表结构与口径不用改。
2. **14 广告（关联投放）**（整卷未交付）—— 交付后由它关联本模块的表现数据；
   本模块**不会**自己去猜广告带来的流量占比。
3. **02 真实平台流量抓取调度器**（归 02 同步运行时）—— 本模块的入库端点已就绪，
   适配器把平台报表推进来即可（带稳定的幂等键）。
4. **09 内部订单口径对账**（归 09）—— 平台口径与内部订单口径的差异解释归它；
   本模块保持只读平台口径，**不**做换算。
5. **4.5.1.2 关键词排名**（同 L3，未开工）—— 页面路由 `/listings/monitor` 已占，
   它交付时可能需要另开一条路径或同树下的子路径，**先与调度者确认**再动。
