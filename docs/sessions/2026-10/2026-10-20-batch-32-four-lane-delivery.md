# 批次 32 · 四条车道交付说明（4.4.1.2 / 4.5.1.2 / 4.5.2.1 / 9.1.2.1）

> 交付轮：2026-10-20 06:00 tick · 集成头 `integration/module-dev`
> 派发轮记录见 `2026-10-20-batch-32-dispatch-four-lanes.md`
> 模式：`MODE=full`（TOKEN_HORIZON=2026-10-25 前），LANES=4，THROTTLE_MIN=50，SILENT_WINDOW=0
> 迁移版本：V204 / V205 / V206（channel）+ V207（trade）

---

## 模块 4.4.1.2 批量/插件采集

**这个功能做什么**：运营在 Listing 工作台里给一个分类页 URL 或一组关键词加页数范围，系统就批量把这个
页面上挂着的商品一个个抓下来，落成**采集草稿**；用户浏览商品页时，浏览器插件把当前页商品上报回服务端，
也落成同一批采集草稿。两种入口共用同一份去重事实 —— 同一个规范化 URL 不会被抓第二遍。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V204__listing_bulk_collect.sql`
  — 四张新表（批量任务 / 抓取结果 / 搜索模板 / 码表种子）+ 索引 + RLS（ENABLE 与 FORCE）+ GRANT；
  **零 ALTER 任何既有表**。`listing_bulk_collect_result_fetched` 用部分唯一索引从 DB 层钉死去重。
- 端点（`/channel/listing-bulk-collects`）：
  | 端点 | 作用 |
  | --- | --- |
  | `GET /capabilities` | 逐维度能力声明 + 当前白名单 + 限频阈值 |
  | `GET /` | 批量任务列表（真分页 + 模式/状态/来源筛选 + 时间倒序） |
  | `POST /` | 发起批量采集（**只建任务**，限频在此 429） |
  | `POST /{id}/run` | 执行批量采集（真发 HTTP、真落草稿，可断点续跑） |
  | `POST /plugin-reports` | 浏览器插件上报当前页商品 |
  | `GET /{id}` | 批量任务详情 |
  | `GET /{id}/results` | 结果清单（真分页 + 状态筛选） |
- 页面：`apps/web/src/app/listings/collect/bulk/page.tsx`（新建，未改动 4.4.1.1 的 `/listings/collect`），
  工作区在 `apps/web/src/components/listing-bulk-collect/`，加载 / 空 / 错误三态俱全。
- 测试：`ListingBulkCollectIntegrationTest` 21 例（真实本地 HTTP 服务）+
  `ListingBulkCollectTenancyIntegrationTest` 3 例（**多租户三断言**）= **后端 24 例**；
  前端 `listing-bulk-collect-model.test.ts` 21 例 + allowlist 4 例 = **前端 26 例**；
  全量 allowlist 回归 **91 文件 / 554 例**全绿。

**关键设计决策**：

1. **复用 4.4.1.1 的采集模型，不另起一套** —— 草稿仍落 `listing_collect_draft`（下游 4.4.1.3 采集箱认领的
   就是它），URL 规范化与商品页解析直接复用 4.4.1.1 的纯函数与解析器 ⇒ 本模块**一行判定逻辑都没重写**。
2. **「建任务」与「执行」拆成两个端点** —— 限频的拒绝必须发生在**发起时**（明确 429 + 还要等多少秒），
   而不是让调用方在一次长请求里等半天最后拿到一句「太频繁了」；被限频打断时任务停在 `RATE_LIMITED`，
   **已采到的结果全部保留**，再调一次 `/run` 从 `next_page` 续跑。
3. **批量采集有自己更快的同 host 间隔**（按平台配在 `listing_bulk_collect_search`，默认 2 秒）——
   单条采集是人点的、间隔大没代价；批量是机器跑的，沿用 30–60 秒等于把功能做成不可用。
   两条路径**并存**，都受「同租户同 host 最小间隔」约束，只是速率不同。
4. **插件上报不触发服务端抓取** —— 插件已经在用户浏览器里把数据取到了，服务端再抓一遍等于把外部站点
   压力翻倍。服务端只做白名单校验 + 去重 + 落草稿，插件任务记 `http_status=0`。
5. **列表页解析只认结构信号**（带图或带 title 属性的链接），不认平台私有类名也不认路径前缀；
   channel 依赖树没有 jsoup 且不得改 pom ⇒ 解析器用 JDK 正则手写，**零新增依赖**。

**边界（如实标注，不假装有）**：浏览器扩展本体（取数/安装/升级）不在本仓，只交付服务端上报入口；
媒体库下载归 03 catalog（跨服务写表被 AGENTS.md 禁止）、采集箱归 4.4.1.3、认领 SPU 归 3.1.1.1、
我方售价归 05、内部可售量归 07 ⇒ 逐条 `available=false`，页面不画这些入口。

---

## 模块 4.5.1.2 关键词排名

**这个功能做什么**：给自有商品维护一组关键词，平台排名抓回来之后能看到「这个词今天排第几、
比上次退了几位」，并在名次跌破阈值或退步过大时留下预警记录和关键词维度报表。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V205__listing_keyword_ranking.sql`
  — 四张新表（关键词集合 / 抓取任务 / 排名历史 / 预警）+ 索引 + RLS + GRANT + 三个触发器；**零 ALTER**。
- 端点（`/channel/listing-keyword-rankings`，13 个）：
  `GET /capabilities`（逐维度能力声明 + 排名字段口径）、`GET /filter-options`、
  `GET /`（关键词集合列表，真分页 + 商品/店铺/平台/站点/关键词/启用状态筛选）、`POST /`（新建关键词挂商品）、
  `PUT /{keywordId}`、`DELETE /{keywordId}`（🔴 **软删除**，历史与预警留着）、
  `GET /{keywordId}`（排名明细）、`GET /{keywordId}/history`、`GET /{keywordId}/trend`（日/周/月，UTC 归集）、
  `POST /crawls`（🔴 接收排名抓取任务，**批次级幂等**）、`GET /alerts`、
  `PUT /alerts/{alertId}/acknowledge`（人工确认留痕）、`GET /report`（🔴 关键词维度报表，真 SQL 聚合）。
- 页面：`apps/web/src/app/listings/keywords/page.tsx`（新建；未改动 4.5.1.1 的 `/listings/monitor`），
  工作区在 `apps/web/src/components/listing-keyword-ranking/`。
- 测试：`ListingKeywordRankingIntegrationTest` 25 例 + `ListingKeywordRankingTenancyIntegrationTest` 4 例
  = **后端 29 例**；前端 model 24 例 + allowlist 4 例 = **前端 28 例**；
  allowlist 全量回归 **91 文件 / 553 例**全绿（另有 trade 段 42 文件 / 224 例全绿）。

**关键设计决策**：

1. **蓝图加粗句是契约级要求 ⇒ 抓取时间与不准确窗口都落表**：
   `crawled_at` NOT NULL（排名是「某一次抓取看到的名次」，不是「现在的名次」）、
   `accuracy_window_minutes` NOT NULL（窗口多大**由抓取方给**，服务端不替它编一个看起来很专业的错数字）；
   三个字段 `crawledAt` / `accuracyWindowMinutes` / `accuracyNote` 出现在**每个**返回排名数据的响应里，
   不只在一个详情页里。
2. **名次没报到就写 null**，不写 0、不写一个很大的数 —— 0 会被读成「排第一」或「完全没排名」，
   那两件事都不是事实。
3. **「较上次的变化」现算不落表** ——「上一次」是按**抓取时间**定的，不是按行号定的；落了列改了抓取顺序就会算错。
   变化量在没有上一次时也是 null（不是 0）。
4. **三个阈值全部可空 = 该维度不预警**；「没配阈值」与「阈值是 0」是两件事（0 意味着永不触发）。
   判定方向是**名次数字 > 阈值**（名次是位置，数字越大越差）。
5. **预警落表而非现算** —— 预警是**事件**（某个时刻判定了一次），历史必须留痕，否则「当时判过没有」查无对证；
   修正产生的新判定用带 `crawl_id` 的唯一键，旧判定作为历史留着。
6. **关键词集合软删除 + 平台/站点从商品推导** —— 排名历史与预警必须留着；商品是平台/站点的唯一事实来源，
   独立存一份就可能与商品矛盾，而矛盾的平台归属会让排名报表按错误的维度分组。
7. **API 走独立前缀 `/channel/listing-keyword-rankings`** —— 4.1.1.1 已把 `/channel/listings/{uuid}/...`
   登记成「不放行」，在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单。

**边界（如实标注）**：真实排名抓取调度器归 02 通道（本模块只做抓取结果入库）、
1 工作台预警推送与 14 广告整卷未交付 ⇒ 四段 `available=false` + `ownerModule` + `impact`，
**不发假数据、不假装有推送能力**。「从商品库批量挑核心词建关键词」的批量入口未做 ——
蓝图只写「关键词集合（按商品维护）」，逐条维护已覆盖职责范围。

---

## 模块 4.5.2.1 跟卖监控

**这个功能做什么**：盯住自己的一个 listing，把平台上跟卖自己同一商品的卖家抓回来，
系统按规则判定「是不是恶意压价」，留下预警记录和「谁压得最狠」的对比视图。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V206__listing_hijacker_monitor.sql`
  — 三张新表（监控对象 / 跟卖记录 / 预警记录）+ 索引 + 4 个触发器 + RLS（ENABLE **与** FORCE）+ GRANT；
  **零 ALTER 任何既有表**。
- 端点（`/channel/listing-hijackers`，8 组）：`GET /capabilities`、`GET /` 与 `POST /`（监控对象 CRUD，
  `PUT` 为全量替换）、`GET /{id}`、`DELETE /{id}`（连带删记录与预警，回执写明删了几条）、
  `GET /records`（跟卖记录，真分页）、`POST /records`（平台上报入库，批次级幂等）、
  `GET /alerts`（预警列表，真分页）、`GET /comparison`（🔴 跟卖者维度对比视图）。
- 页面：`apps/web/src/app/listings/hijackers/page.tsx`（新建，未登记 task-pages），
  工作区在 `apps/web/src/components/listing-hijacker/`（监控对象 / 跟卖记录 / 预警 / 对比视图 + 三态）。
- 测试：`ListingHijackerIntegrationTest` 23 例 + `ListingHijackerTenancyIntegrationTest` 4 例
  （**多租户三断言**）= **后端 27 例**；前端 model 18 例 + allowlist 4 例 = **前端 22 例**；
  allowlist 全量回归 **91 文件 / 553 例**全绿。

**关键设计决策**：

1. **蓝图加粗句「只能作为参考」落成结构** —— 表里**没有任何处置状态**（`status` 只有 `OPEN` / `ACKNOWLEDGED`），
   服务层**没有任何投诉/举报/申诉端点**，api-proxy 白名单里刻意不放行这类形状
   （放行一个后端不存在的映射比 404 更糟：界面会以为「已交付」）；
   每个响应都带 `dataSource='PLATFORM_SCRAPED'` 与 `sourceNote` / `actionsNote`。
2. **跟卖者的「所在平台」不落列**，由 `listing → shop → platform` 派生 —— 冗余一列就会出现
   「记录说在 A 平台、被监控商品在 B 平台」的自相矛盾数据，而跟卖记录的正确性恰恰驱动预警与对比视图。
3. **自家参考售价是租户人工维护的 `reference_price`** —— 实测卷 05 定价 0/19 未交付，全库不存在任何
   平台售价事实表；建一个价格影子列 = 假装有第二个价格事实来源，05 上线后两个数字迟早对不上。
   参考价不给 ⇒ 价格差判定**不生效**（只有恶意压价阈值那条还能命中）。
4. **价格差比例一律 SQL 现算，分母为 0 返回 null** —— 落了表就会与事实漂移；报 0 会被读成
   「跟卖价和自家价一样」，那是结论不是缺口。预警里的参考价存**判定时刻的快照**。
5. **阈值不给就不判定**（不猜一个默认门槛）—— 猜出来的门槛会凭空造出预警。
   两条规则同时命中时类型取**更严重的恶意压价**，判定依据写明全部命中的规则。
6. **对比视图一个跟卖者一行**、按价格差比例降序（压得最狠的排最前）——
   「跟卖者」是运营的思考单位；前端拼表自己算比例 = 两套口径。

**边界（如实标注）**：16 合规品牌保护的处置动作、1 工作台预警通知、02 抓取调度器、05 平台权威售价
四段均未交付 ⇒ `capabilities` 里逐段 `available=false` + `ownerModule` + `impact`。

---

## 模块 9.1.2.1 平台订单同步

**这个功能做什么**：把各平台上产生的订单接进统一订单模型 —— webhook 实时接入与轮询补拉
**共用同一条 `ingest()` 落库路径**，逐条事件做「幂等去重 → 平台状态归一 → 金额与税费还原 → 建单/推进/拒收」，
平台单在统一订单模型里就是一张普通销售单，现有订单列表与查询**一行都不用改**就能读到平台单。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V207__platform_order_sync.sql`
  — **6 张新表 + 索引 + 触发器 + RLS ENABLE/FORCE + GRANT**，**零 ALTER 任何既有表**。
  幂等**双闸都落在 DB 层**（不靠服务层先查后写，那有竞态）：
  事件级 `platform_order_sync_event.UNIQUE(tenant_id, event_key)`（事件键 =
  `sha256(平台 | 外部单号 | 事件类型 | 内容哈希)`）+
  订单级 `platform_order.UNIQUE(tenant_id, platform_code, external_order_no)` /
  `UNIQUE(tenant_id, idempotency_key)`，两者都用 `ON CONFLICT DO NOTHING` 挡住。
- 端点：`/trade/platform-orders` 下的平台订单同步入口（接收平台侧推送 / 拉取结果入库，
  webhook 与轮询同一条路径，差异只有「事件类型默认不同」和「时间窗」）+
  `GET /channel/platforms/{code}/enums?kind={kind}` 的**授权读取**（见下）。
- 页面：`apps/web/src/app/orders/platform-sync/page.tsx`（新建），
  工作区在 `apps/web/src/components/platform-order-sync/`（写入入口是**可编辑 JSON 模板重放**）。
- 测试：`PlatformOrderSyncIntegrationTest` 20 例 + `PlatformOrderSyncTenancyIntegrationTest` 3 例
  （**多租户三断言**：两租户互不可见、伪造 tenant header 无效、连接池归还不串租户）
  + `PlatformOrderSyncMappingSyncIntegrationTest` 3 例 = **后端 26 例全绿**；
  前端 model 20 例 + allowlist；trade 段全量 allowlist 回归 **43 文件 / 229 例**全绿。

**关键设计决策**：

1. **不新建平台订单主表，写进 V99 的 `sales_order` 簇** —— 蓝图要的是「形成统一订单模型」；
   另建一张平台单表就得双写，两张表的订单数迟早对不上，而且 9.1.1.1 / 9.1.1.2 都要改查询才能看到平台单
   —— 那是对已交付车道的回归。平台单即普通销售单（`order_source` 取平台来源、`manual_kind` 留空）。
2. **状态归一不猜** —— 平台状态没有登记映射 ⇒ **整单拒收**（`UNMAPPED_PLATFORM_STATUS`），
   绝不猜一个内部状态码落库；只有组件类型允许兜底 `OTHER` 且额外存 `external_type`，
   界面才分得出「平台真报的 OTHER」与「我们兜底的 OTHER」。
3. **订单内容写一次** —— V99 已用触发器收回运行角色对行/地址/费用/客户快照的 DELETE 权，
   「整体替换订单内容」没有合法路径。平台侧改了内容时：单据还是草稿 ⇒ `ORDER_CONTENT_DIVERGED`，
   已提交进审单队列 ⇒ `ORDER_SNAPSHOT_FROZEN`，两种都**交 9.1.1.3 订单变更与取消**处理，
   本模块只把事实记成异常明细，不改订单快照。
4. **金额口径与 V99 同式，超一分钱就拒收** —— 行小计 `round(单价 × 数量, 4)`、行折扣恒负、头折扣取正、
   合计 `= 小计 − 折扣 + 税 + 运 + 其他`；平台应付总额与本侧还原结果差 > 0.01 即 `AMOUNT_MISMATCH` 拒收
   —— 超过一分钱就不是舍入而是口径不一致，不静默吸收。
5. **映射只能经 channel 授权 API 取** —— `GET /channel/platforms/{code}/enums?kind={kind}`，
   转发调用方自己的 Authorization bearer，**租户不由端口传递**。
   本车道**一次跨服务 SQL / FK / 共享视图都没碰**（AGENTS.md 的硬边界）。
6. **组件唯一键用序号不用码** —— `(tenant, order_id, line_no, component_source, component_seq)`；
   同款件在一个套装里出现两次是合法的，码入键会让整单事务回滚。
7. **时间用 ISO-8601 带偏移量文本 + `?::timestamptz` + `OffsetDateTime`** ——
   `Timestamp.valueOf(LocalDateTime)` 按 JVM 本地时区静默偏 −28800s；测试**断言写回的值**。
8. **「重复」单列一格，不混进「失败」** —— 把重复算成失败会让运营以为平台侧出了问题；
   归一状态与单据状态也分两列，不合成一列。

**边界（如实标注）**：真正的 Amazon / Shopify 适配器属 02 渠道与店铺系统，本模块只吃「已归一过的平台报文」；
`ORDER_CONTENT_DIVERGED` / `ORDER_SNAPSHOT_FROZEN` 交 9.1.1.3；
平台单与手工单的 `order_source` 词表要在 9.2 审单队列侧确认。

**本车道额外发现（非本车道缺陷）**：重生成契约时发现
`packages/api-contracts/src/channel.openapi.json` 与 channel 服务的真实端点已对不上
（channel 代码已合、契约未重生成）。本车道只提交 trade 侧契约变更，channel 的重生成归 channel 车道。

---

## 车道经过与调度者独立复核

### 四条车道的复核（调度者独立取证，不是转述 worker 自报）

| 模块 | commit | api-proxy 增删 | 契约 path / schema（相对基线） | L1 自报 |
| --- | --- | --- | --- | --- |
| 4.5.1.2 关键词排名 | `3042d119` | +49 / −0 | +10 path / +19 schema，**删 0** | 后端 29 例 + 前端 28 例 + allowlist 553 例绿 |
| 4.5.2.1 跟卖监控 | `5ea3ad8e` | +50 / −0 | +8 path / +19 schema，**删 0** | 后端 27 例 + 前端 22 例 + allowlist 553 例绿 |
| 4.4.1.2 批量/插件采集 | `146c3ec6` | +39 / −0 | +6 path / +12 schema，**删 0** | 后端 24 例 + 前端 26 例 + allowlist 554 例绿 |
| 9.1.2.1 平台订单同步 | `23732725` | +41 / −0 | +10 path / +26 schema，**删 0** | 后端 26 例 + 前端 20 例 + trade allowlist 229 例绿 |

### 🔍 并入后的集合级核验（调度者写脚本做的，不是看 numstat）

`git diff --numstat` 对契约给出的巨大增删（channel 6514/2198）是本仓 codegen 的**既有重排特征**
（早先批次同样是 7216/6627、9360/8159、3423/2397），**不能据此判断有没有丢东西**。
所以本轮改用**集合差**重新取证（`.tmp/verify-merge.mjs`）：

| 核验项 | 基线 | 并入后 | 结论 |
| --- | --- | --- | --- |
| `api-proxy.ts` 路由正则**集合** | 56 条 | 60 条 | **丢失 0 条** ✅，新增恰好 4 条（本批四个前缀） |
| channel 契约 path / schema | 302 / 522 | 326 / 572 | +24 / +50，**丢失 0** ✅ |
| trade 契约 path / schema | 449 / 1030 | 459 / 1056 | +10 / +26，**丢失 0** ✅ |
| catalog / identity / warehouse / crm 契约 | — | — | **path 与 schema 集合完全未变** ✅ |

⚠️ 期间曾出现一条「`listing-collects` 被删除」的告警（numstat 显示 +179 / −1）——
**是误报**：那 1 行删除是 Prettier 的换行归一化（`pattern:` 单独一行 → 与正则同行），
正则**内容逐字未变**，路由仍在。⇒ 这也说明**只看 numstat 会误判**，必须比集合。

### 静态预检（本批新迁移，L2 之前）

| 扫描 | V204 | V205 | V206 | V207 |
| --- | --- | --- | --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | 0 命中 | 0 命中 | 0 命中 | 0 命中 |
| `SET NOT NULL` | 0 命中 | 0 命中 | 0 命中 | 0 命中 |
| `ADD CONSTRAINT ... CHECK` | 0 命中 | 0 命中 | 0 命中 | 0 命中 |
| `ALTER TABLE` 既有表 | 0 | 0 | 0 | **0** |

⇒ **本批零 ALTER，四条车道全部是纯新增表**，不存在「加了必填列破坏早先车道 INSERT」的形状。

### 车道经过

- **4.4.1.2 / 4.5.1.2 / 4.5.2.1**：一次通过，无中断。
- **9.1.2.1**：**第一次尝试被模型网关 504 Gateway Time-out 打断**（基础设施故障，不是业务缺陷），
  死在写 `PlatformEnumMappingClient` 的中途，留下三个未 commit 的文件（一行没丢）：
  `V207__platform_order_sync.sql`（560 行）/`PlatformOrderSyncModels.java`（591 行）/
  `PlatformEnumMappingClient.java`（103 行）。
  处置：**不 abandon、不 gc worktree、不让下一轮重排** —— 用同一 worktree / 同一分支 / 同一个 `V207`
  重新派发，把「上一轮已定稿的设计 + 三个遗留文件 + 明确要续做的部分」写进新任务书。
  双写风险已排除：`sessionId` 仍为 null，未认领判据只看心跳新鲜度，续做 worker 每 25 分钟续心跳。
  **接管原则 = 读懂后局部修补，不推倒重写**（这三个文件承载的取舍是对的，推倒重来只会把上一轮
  想清楚的东西再想一遍）。续做时具体改了什么：
  - `PlatformEnumMappingClient.java` —— 补上缺失的 `JdkClientHttpRequestFactory` import
    （**原文件根本编译不过**，上一轮没跑到编译就断了）—— 这是接管后第一个编译错误。
  - `V207` —— 局部改：删掉三张表上的 `source_scope` 列（V99 的外部单号唯一口径是
    `(tenant, order_source, external_no)`，没有店铺维度）；组件唯一键由 `component_code`
    改为 `component_seq`（同款件在一个套装里出现两次是合法的，码入键会让整单事务回滚）；
    新增 `content_fingerprint` / `external_type` / `mapping_version` + 部分唯一索引。
  - `PlatformOrderSyncModels.java` —— 局部改：新增错误码 `ORDER_CONTENT_DIVERGED` /
    `EXTERNAL_ORDER_NO_COLLISION`，出参 `sourceScope` 换成 `platformCode` / `shopCode`。
  ⇒ 交付说明里已写明「经历一次 504 中断 + 接管续做」，供后续回看。
- **接管后修掉的 7 个 bug**（都是实跑出来的，不是推演）：两处 INSERT 占位符错位
  （21 列给 20 个表达式、25 列给 24 个）、折后价符号反了、重复事件用例每次生成新随机 skuId
  导致指纹变了、状态推进用例同上、显式组件挂错行、组件用例 join 写错外键列。

### 三处留给后续车道的坑（4.5.1.2 的 worker 自己踩出来并修好的）

1. **Java 可变参数不展开尾部 `Object[]`** —— `sql.fetch(sql, a, b, c, d, arr)` 里 `arr` 被当成**一个**绑定
   （jOOQ 渲染成 `cast(? as any[])`），PG 报「参数 N 没有值」⇒ 全部绑定进同一个 `List` 再 `toArray()`。
2. **SQL 的 WHERE 必须在所有 JOIN 之后** —— 筛选条件拼在 `join ... on ...` 之后、`left join` 之前
   会 `syntax error at or near "left"`。
3. **PG 不允许 DISTINCT 的 ORDER BY 表达式不在 select list**（42P10）⇒ 改用 `exists`。

### 一处范围边界裁决（调度者已裁决，不是遗留缺口）

蓝图 4.5.1.2 的输入写「关键词集合（**按商品维护**）」。车道交付了租户内**逐条**增删改查 +
每条关键词绑定商品/平台/站点 + 抓取结果入库、排名历史、变化预警、关键词维度报表，
但**没有**做「从商品库批量挑核心词建关键词」的批量入口。
⇒ **裁决：接受当前范围，不算缺口。**「按商品维护」由「关键词记录绑定商品 + 逐条维护」满足；
「从商品库批量挑词」是效率增强，蓝图四问未点名。**后续 tick 不要再把它当缺口重开。**

---

## 验证与收尾

| 闸 | 范围 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 各车道自报通过（exit 0） |
| L0 类型 | `pnpm typecheck` | 各车道自报 3/3 通过 |
| L1 模块闸 | 只跑本模块测试类（`-Dtest='Xxx*'` + 两个 failIfNoSpecifiedTests 开关） | 各车道自报全绿（见上表） |
| **L2 批次闸** | 集成头全量：`mvnw verify` + typecheck / lint / test + Next production build + `sync --check` | **✅ 全绿**（第三次跑，07:10:45 → 07:36:09） |
| 静态预检 | 本批新迁移三项扫描 | **零命中** |
| 台账完整性 | 465 行 / 0 重复模块 ID | **通过** |

### L2 三次跑的实测结果（如实记录，不做「一次就绿」的表述）

| 轮次 | 时间 | 后端 `mvnw verify` | 前端 `pnpm test` | 结论 |
| --- | --- | --- | --- | --- |
| 第 1 次 | 06:10 → 06:36 | ❌ **1 error**（`FulfillmentRequestIntegrationTest`，`fulfillment_request_step_check3` 违约） | 未跑到 | **红** ⇒ 不 push，转入取证 |
| 第 2 次（干净窗口） | 06:39 → 07:07 | ✅ **0 failures / 0 errors** | ❌ **1 failed / 4724**（`listing-hijackers-allowlist` 负向断言） | **红** ⇒ 抓到真实跨车道缺陷，派修复车道 |
| 第 3 次（修复并入后） | 07:10:45 → 07:36:09 | ✅ **0 failures / 0 errors** | ✅ **293 文件 / 4724 例全绿** | **🟢 绿** ⇒ push |

第 3 次全量：后端 **5600 例 0 failures / 0 errors**；前端 **293 文件 / 4724 例全绿**；
`pnpm typecheck` / `pnpm lint` / Next production build / `sync --check` 全部 exit 0。

⚠️ **本批的 push 建立在第 3 次全量绿之上，不是「一次就绿」** ——
两次红的成因完全不同（一次是既有模块的 1ms 竞态，一次是本批真实的跨车道断言缺陷），
两次都已在上面逐条取证。

### 🚨 L2 第一次红了：1 个 error，**不在本批任何一条车道上**

首次 L2（06:10 起）跑到 `trade` 模块失败，**全量只有 1 个 error**：

```
FulfillmentRequestIntegrationTest · 1 error（33 例中 1 错）
DataIntegrityViolationException: new row for relation "fulfillment_request_step"
violates check constraint "fulfillment_request_step_check3"
```

**取证（先隔离复跑、再下结论，不直接归因到新车道）**：

| 证据 | 结论 |
| --- | --- |
| 隔离复跑 `FulfillmentRequestIntegrationTest` **连续 3 轮，每轮 33/33 全绿**（BUILD SUCCESS ×3） | 失败**不可稳定复现** ⇒ 不是本批引入的确定性回归 |
| 本批在 trade 服务的全部改动 = **8 个文件，全部是 `A`（新增）**：4 个 Java 主类 + V207 + 3 个测试类 | 纯新增 |
| `git diff 89dd3148..HEAD -- '*Fulfillment*' 'V152__trade_fulfillment_request.sql'` = **空** | 履约代码与定义该约束的 V152 迁移**逐字节未变** |
| V207 里 `fulfillment_request` 出现 **0** 次；本批**零 ALTER** 既有表 | DDL 回归面 = 0 |
| surefire 无 `parallel`/`threadCount` 配置 ⇒ 测试类**串行**执行 | 新增的 `PlatformOrderSync*` 不会与它并发跑，不存在逻辑干扰 |
| 失败行：`started_at=…50.327037` > `finished_at=…50.326031`（差 1.006ms），`elapsed_seconds=0.001` | 违反的是 `CHECK(finished_at IS NULL OR finished_at >= started_at)` |

**根因（跨时钟域竞态，与本批无关）**：
`FulfillmentRequestService.completeStep` 用 **Java 时钟** `OffsetDateTime.now()` 写 `started_at`，
而 DB 函数 `complete_fulfillment_step` 用 **PG 时钟** `clock_timestamp()` 写 `finished_at`。
两者不同源；当这一步在 **1 毫秒内**完成、且 Java 时钟比 PG 服务器时钟快哪怕 1ms，
`finished_at >= started_at` 就会翻负 ⇒ CHECK 违约。**这是 1ms 宽的窗口，不是本批的回归。**

⚠️ **这个抖动不是第一次出现，仓库里有两处独立记录**：
`2026-10-19-batch-26-recovered-lanes-and-ledger-fix.md` 记过**完全相同**的一例
（同一个 `fulfillment_request_step_check3`，同样「隔离复跑 33/33 绿」，同样「本批未碰该测试与该约束」）；
`2026-10-07-pipeline-merge-batch-whitelist.md` 也记过同族的毫秒级往返抖动，并写明
「那个毫秒级往返尤其值得注意 —— 它是**真会抖**的，不是纯粹的环境问题」。
⇒ **待办已记入本轮汇报的「风险提示」**：这条竞态应当单独修（把 `finished_at` 与 `started_at`
 收敛到同一时钟域，或在 DB 侧 `GREATEST(clock_timestamp(), started_at)` 兜底），
  **不要等它每次靠 L2 撞出来**。本轮**不代改** 9.3.3.1 的代码（调度者不写业务代码），
  也**不因为它阻断本批交付**。

### 🚨 L2 第二次红了：抓到一条**真实的跨车道缺陷**（这正是组批模式存在的理由）

干净窗口复跑 L2（后端全绿：`mvnw verify` **MVN_RC=0**，6 个服务 0 failures / 0 errors）之后，
**前端全量**红了：**1 个测试文件 / 4724 例中 1 错**。

```
FAIL apps/web/src/app/api/channel/listing-hijackers-allowlist.test.ts
  × 未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟
AssertionError: channel/listing-keyword-rankings 被放行了，但后端没有这个形状: expected 503 to be 404
```

**根因（调度者独立取证）**：`listing-hijackers-allowlist.test.ts` 第 145–146 行把
**兄弟车道 4.5.1.2 的前缀** `channel/listing-keyword-rankings` 放进了
「未交付形状必须 404」的**负向断言**清单，注释还写着「4.5.1.2 关键词排名是**别的模块**的前缀，**且未交付**」——
而 **4.5.1.2 恰恰在同一批里交付了**，该前缀现在是**已登记的合法前缀**。
⇒ 并入后必然假红（503 = 被转发到未启动的 channel 后端；404 才是白名单拒绝）。

**白名单本身是对的，错的是测试断言** —— `api-proxy.ts` 里 `listing-keyword-rankings/`
是 4.5.1.2 合法新增的一条 `services[]` 元素，**不该动**。

⚠️ 这条缺陷有**两重**危害，第二重更危险：
1. **假红**：本批已交付 ⇒ 断言与白名单互相矛盾 ⇒ 合并闸红（本次）。
2. **假绿**：若 4.5.1.2 那条白名单被取错一侧而**静默删掉**，这条负向断言反而会**绿** ——
   「已交付的路由被误删」这个真事故被测试**盖住**。断言方向搞反时，测试会替错误背书。

**违反的协议条款**：负向断言（未登记形状必须 404）**只能用自己前缀下的形状**。
拿兄弟车道的前缀当负向样本，等于把「那条车道还没交付」写死在断言里 ——
一旦那条车道在同批或后续批次交付，断言就**必然失效**，而且是在最贵的时刻（批次合并闸）失效。

**处置**：派**修复车道** `fix/4-5-2-1-allowlist-negative`（worktree `.worktrees/4.5.2.1-fix`），
只改一个文件的一处，**零后端改动 / 零迁移 / 零契约 / 零台账**：

| 动作 | 内容 |
| --- | --- |
| **删** | `'channel/listing-keyword-rankings',` 及其上一行「🔴 4.5.1.2 关键词排名是别的模块的前缀，**且未交付**」注释（4.5.1.2 已交付 ⇒ 那句已成**假注释**，不能留一句假话在代码里） |
| **补** | `${base}/brand-protection-enforcement` —— **自己前缀下**确实不存在的形状（契约里 `/channel/listing-hijackers/*` 只有 8 条 path、后端 `ListingHijackerController` 只有对应 8 组端点，都没有它；且它与服务层能力维度代码 `brand_protection_enforcement` 字面对齐） |
| **补** | 清单首条注释追加协议提醒：负向断言只能用自己前缀下的形状，兄弟车道前缀在同批并入后就是合法前缀 |

修复车道自报验证（调度者已核对 diff）：`listing-hijackers-allowlist` **4/4 绿** ·
`pnpm vitest run apps/web/src/app/api` **94 文件 / 567 例全绿** · `pnpm typecheck` **3/3**。
修复 commit `6b7f2039`，并入 commit `0a9a82f7`。

**同一批的另三条车道已自查**：4.5.1.2 / 4.4.1.2 / 9.1.2.1 的负向清单**全部只用自己前缀下的形状**，
未发现同类问题 ⇒ 本批的负向断言违规**只有 4.5.2.1 这一处**。

### 本批的处置

- 第一次 L2 红 ⇒ **不 push**（协议硬规定）。
- 定位到「既有模块的 1ms 竞态、不是本批任何一条车道」⇒ **不做 `git bisect`**
  （失败不可稳定复现，二分对非确定性失败没有意义，只会烧两次全量），
  改为**在干净窗口复跑全量 L2**（0 在途车道 / 0 带标签 PG 容器 / 无 surefire 进程）。
- 第二次 L2 红 ⇒ 抓到**真实跨车道缺陷**（4.5.2.1 的负向断言）⇒ 派修复车道 → 并入 → 第三次 L2。

- 写说明：✅ 本文件
- commit：✅ 与台账同一个 commit
- push：见本轮汇报（**未 push `main`**）
