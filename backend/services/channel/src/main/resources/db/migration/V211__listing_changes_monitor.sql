-- ===========================================================================
-- 4.5.2.2 上新与变更监控（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.5.2.2 上新与变更监控
--   - 职责：监控目标 ASIN/竞品的上下架、价格、排名、评论数变化，识别市场机会（如竞品断货涨价）。
--   - 输入：监控对象列表；抓取频率。
--   - 输出：变更事件流 + 历史对比。
--   - 关系：上游 = 抓取任务；下游 = 13 分析、1 工作台。**监控对象与自有商品分离**，
--           竞品数据不进自有商品模型，只做参考指标。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 本迁移最重要的一件事：**监控对象与自有商品分离**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「**监控对象与自有商品分离**，竞品数据不进自有商品模型，只做参考指标」
-- 落到本迁移上是**三件事，缺一不可**：
--
--   ① **监控对象表里没有 listing_id，也没有到 `listing` 的外键。**
--      4.5.2.1 跟卖监控的 `listing_hijacker_watch` 挂在自家 listing 上 ——
--      那是「监控我自己的商品有没有被跟卖」，本模块**不一样**。
--      本模块盯的是**别人家的 ASIN**（竞品），它与本租户的任何商品**没有**引用关系。
--      一旦挂上 listing_id，就会出现两个后果：
--        · 竞品的观测值可能被下游当成「我方商品的价格」读走；
--        · 删掉一个我方 listing 会连带删掉竞品的历史（FK CASCADE），
--          把别人的市场数据绑死在我方商品主数据的生命周期上。
--      ⇒ 监控对象只认**平台 + 平台商品 ID（ASIN）**这一对身份，
--        身份是**平台侧原文**，不是内部商品 id。
--
--   ② **表名与列名不带「我方商品」语义。** 观测快照里没有 sku_id / internal_sku_id /
--      listing_id 任何一列；目标只以 `external_item_id`（ASIN 原文）+ `platform_id` 表达。
--
--   ③ **响应里带「参考数据」标识**：服务层每个响应都带
--      `data_source='PLATFORM_SCRAPED'` 与 `source_note`
--      （「平台侧抓取观测值，仅供参考，不构成市场结论」）。
--      竞品的价格/排名/评论数是**观测**，不是市场事实 ——
--      抓取会滞后、会漏抓、也会抓到已下架的商品。
--
-- 🔴 与兄弟模块 4.5.2.1 的边界（会话日志里留的待办）：
--   两者语义相近（都是「盯一个 ASIN」），4.5.2.1 的会话日志写着
--   「届时可能需要复核两者的配置是否该合并到一张表」。
--   **本迁移不合并**：自己建自己的表，**零 ALTER 任何既有表**。
--   「两者的配置是否该合并到一张表」**留给后续复核**，不在本迁移里替后人做决定。
--   （4.5.2.1 的监控对象挂在自家 listing 上、本模块挂在竞品 ASIN 上，
--     身份模型不同 —— 合表要先回答「一条监控对象能不能既没有 listing 又有 listing」。）
--
-- ---------------------------------------------------------------------------
-- 二、🔴 比率 / 变化量**一律不落表**，SQL 现算（照抄 4.5.1.1 / 4.5.2.1 纪律）
-- ---------------------------------------------------------------------------
-- 「价格变化幅度」「价格变化比例」「排名退步多少位」这些量**在本迁移里没有一个落点**：
--   * 落了表就要在每次写入时去查上一次并比较，逻辑散在两处（写入 + 比较），迟早漂移；
--   * 而且「上一次」是**按观测时刻**定的，不是按行号定的，落列天然会错；
--   * 分母（上一个价格）为 0 时必须返回 **null** —— 不是 0、也不是 100。
--     报 0 会被读成「价格没变」，那是结论不是缺口。
-- 所以：变化**事件**本身落表（`listing_change_event`），它带的是**当时那一对观测值**
-- （previous_value / current_value 的**快照**），而**幅度与比例是查询时现算的列**。
-- 本迁移**零个变化率列**。
--
-- ⚠️ 唯一落表的「比例」是 `listing_change_watch.price_increase_ratio_threshold`
-- —— 那是**配置阈值**（运营自己定的门槛），不是由事实算出来的量，落表是对的。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 上下架 / 价格 / 排名 / 评论数：四个维度各自独立判定
-- ---------------------------------------------------------------------------
-- 蓝图职责句列了四类变化：上下架、价格、排名、评论数。本模块**四类都要能被识别出来
-- 并落成事件**，不是只存「当前值」。
--
-- 判定的共同口径（**平台没报 ≠ 变成 0**）：
--   * 上下架状态报 UNKNOWN（平台没报）⇒ **不产生上下架事件**，
--     而且比较的基准要回退到**上一个已知状态**（见服务层的取数 SQL）——
--     「没报」不是「变了」。
--   * 价格 / 排名 / 评论数报 null（平台没报这个维度）⇒ **不产生该维度的事件**，
--     基准同样回退到**上一个已知值**。
--   * 只有「已知值 → 已知值，且两者不等」才算一次变化。
--
-- 首次观测（没有上一次）的特殊口径：
--   首次观测**不是**一次「变化」，但**上下架**维度要留一条事件
--   （previous_state='UNKNOWN' → current_state='LISTED'），
--   因为模块名里的「**上新**」正是这件事：
--   「这个监控目标当前是在售的」是一条必须能被看到的状态迁移。
--   是否把它升格为**市场机会**，由监控对象上的 `listed_opportunity` 配置决定
--   （默认关 —— 每个新建监控对象都报一次「上新」是噪声）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 排名是**位置**，不是数量；且「名次变大 = 变差」
-- ---------------------------------------------------------------------------
-- 排名用 integer 存（名次），**没有浮点**。
-- 口径必须写死，否则前端会读反：
--   * 名次数字**越小越好**（第 1 名最好）；
--   * 因此 `direction='INCREASED'`（名次数字变大）表示**竞品表现变差**，
--     那才是对我方有利的机会；`DECREASED` 表示竞品表现变好。
--   * 排名的口径（`rank_basis`：BSR / CATEGORY / KEYWORD）必须一起存 ——
--     「类目第 12 名」与「关键词第 120 名」不是同一个量，混在一起比是错的。
-- 排名没有就写 null，**不写 0**（写 0 会被读成「排第一」）。
--
-- ---------------------------------------------------------------------------
-- 五、五张表：监控对象 / 抓取批次 / 观测快照 / 变更事件 / 市场机会
-- ---------------------------------------------------------------------------
--   listing_change_watch        监控对象（配置）：目标身份 + 抓取频率 + 机会判定阈值
--   listing_change_crawl        抓取批次（蓝图输入项「抓取频率」的落地形态 + 批次级幂等）
--   listing_change_snapshot     观测快照（事实）：某时刻观察到的上下架/价格/排名/评论数
--   listing_change_event        变更事件（蓝图输出项「变更事件流」）
--   listing_change_opportunity  市场机会（职责句「识别市场机会」的判定结论）
--
-- 为什么是五张而不是一张：
--   * **监控对象是配置**，不是事实。它可以被暂停、改抓取频率、改阈值；它与某次抓取无关。
--   * **抓取批次**是一个**有范围、有通道、有成败**的整体（覆盖了哪个监控对象、
--     走了哪条通道、抓取时刻是几点、哪些没顾上）。挂在快照行上会出现
--     「同一批数据被拆成 N 行、每行各说各话」的局面。
--   * **观测快照是事实**（某时刻看到的值）。它只追加，历史对比以它为唯一事实源。
--   * **变更事件是判定结论**（这次快照与上一次相比变了什么）。
--     把结论挂在快照行上会出现「一行既是被观察到的数据、又是结论」。
--   * **市场机会**是**结论的结论**（这次变化对我方意味着机会）。
--     「事实 / 变化 / 机会」三层混在一张表里，想回答「哪些是机会」就得在代码里写 if。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 幂等与覆盖的两级语义（照抄 4.5.1.2 关键词排名）
-- ---------------------------------------------------------------------------
--   * **批次级幂等**：`listing_change_crawl.idempotency_key` 在租户内唯一。
--     同一个 key 再次投递 ⇒ **整批不再落库**（连事件与机会都不会重复产生），
--     直接返回既有那条抓取记录并把 `replayed` 置 true。
--     key 由抓取方给（平台 + 任务批次 id 之类的稳定值），服务端**不替调用方编**。
--   * **同刻覆盖**：同一个 (监控对象, 观测时刻, 来源) 只有一行。
--     抓取方**修正**同一次抓取的结果时用**新的**批次 key 推进来 ⇒ 该行被覆盖，
--     `revision` 递增。��一次抓取只能有一行」是 UNIQUE 约束的结构事实。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 机会判定：阈值是**配置**，不给 = 该维度**不判定**
-- ---------------------------------------------------------------------------
-- 四个判定开关全部在监控对象上，全部是**配置项**（输入），不是结论：
--   out_of_stock_opportunity      竞品断货 ⇒ 机会（默认开；断货是蓝图的示例场景）
--   price_increase_ratio_threshold 涨价幅度超过该比例 ⇒ 机会（**不给 = 不判定**这一条）
--   rank_worsen_threshold          排名名次数字变大超过该位数 ⇒ 机会（**不给 = 不判定**）
--   listed_opportunity             上新 / 重新上架 ⇒ 机会（默认关；噪声大）
-- 「没配阈值」与「阈值是 0」是两件事：0 意味着「涨 0% 就算涨价」，
-- 那是一个**永远命中**的配置，不是「没配」。比例阈值 ∈ [0,1)，1.0 意味着永不命中。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 抓取调度器归 02：本模块只做**入库端点**
-- ---------------------------------------------------------------------------
-- 蓝图「输入：监控对象列表；抓取频率」里的**抓取频率**是**配置**（本表
-- `crawl_interval_minutes`），**那个真正去平台定时拉取的调度器归 02 渠道与店铺**
-- （平台限流与店铺配额由 02 统一裁决，本模块不自建轮询）。
-- 服务层 `capabilities` 里 `real_crawl_scheduler` 维度 available=false + ownerModule。
--
-- ---------------------------------------------------------------------------
-- 九、🔴 金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
--   价格（观测价 / 机会快照价）    numeric(19,4)
--   评论数                        numeric(19,6)   （与 V171 listing.rating_count 同型）
--   排名                          integer         （名次，不是数量）
--   比例阈值（配置项）             numeric(9,6)
--   时间（观测时刻 / 判定时刻）    timestamptz
--   币种                          char(3)
-- 无 float / double 一列。金额一律 BigDecimal。
--
-- ---------------------------------------------------------------------------
-- 十、🔴 依赖面：只引用同服务已交付的表，零跨车道 DDL 回归
-- ---------------------------------------------------------------------------
--   * V1 `platform` / `marketplace`（平台与站点码表，只读；**不是**自有商品模型）
--   * V1 `channel_scope(...)` / `channel_operation()`（数据范围策略）
--
-- 本迁移**零 ALTER 任何既有表**、零跨服务 FK、零跨库 SELECT、
-- **零对 `listing` / 商品主数据任何表的引用**。
--
-- ===========================================================================
-- 1. listing_change_watch · 监控对象（配置）
-- ===========================================================================
CREATE TABLE listing_change_watch (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 目标身份 = 平台 + 平台商品 ID（ASIN）。**没有 listing_id**，
  --    本表与本租户的自有商品模型**零引用关系**（见第一节）。
  platform_id       uuid NOT NULL REFERENCES platform(id),
  -- 🔴 站点（可空）：站点码只在平台内唯一，平台才是父级（V1 marketplace）
  marketplace_id    uuid REFERENCES marketplace(id),
  -- 🔴 平台商品 ID 原文（ASIN）—— 竞品身份的原样保留，不做任何归一化
  external_item_id  varchar(128) NOT NULL,
  -- 🔴 目标性质：COMPETITOR 竞品 / OWN_ASIN 自有 ASIN（自家商品在平台上的**平台侧观测**，
  --    **仍然不写进自有商品模型** —— 这里存的永远是平台看到的值，不是我们的商品记录）
  target_kind       text NOT NULL DEFAULT 'COMPETITOR'
                    CHECK(target_kind IN ('COMPETITOR','OWN_ASIN')),
  -- 🔴 目标标题 / 品牌（平台侧原文，仅供展示与人工核对）
  title             varchar(300) NOT NULL DEFAULT '',
  brand             varchar(120) NOT NULL DEFAULT '',
  -- 🔴 抓取频率（分钟）—— 蓝图输入项「抓取频率」。**本模块不实现调度器**（归 02），
  --    这一列是给 02 的抓取器读的配置。
  crawl_interval_minutes int NOT NULL DEFAULT 720
                    CHECK(crawl_interval_minutes > 0),
  -- 🔴 状态：ACTIVE 监控中 / PAUSED 暂停。暂停只停「接收与判定」，
  --    已有快照 / 事件 / 机会**不删**（历史事实不因为配置变了就消失）
  status            text NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','PAUSED')),
  -- 🔴 机会判定开关（**配置阈值**，不是结论）
  --    竞品断货 ⇒ 市场机会
  out_of_stock_opportunity boolean NOT NULL DEFAULT true,
  --    上新 / 重新上架 ⇒ 市场机会（默认关：每个新建监控对象都报一次「上新」是噪声）
  listed_opportunity boolean NOT NULL DEFAULT false,
  --    涨价幅度超过该比例 ⇒ 市场机会（**不给 = 不判定**这一条）
  price_increase_ratio_threshold numeric(9,6),
  --    排名名次数字变大超过该位数 ⇒ 市场机会（**不给 = 不判定**这一条）
  rank_worsen_threshold integer,
  -- 🔴 负责人归属快照（数据范围策略的输入）：由已认证操作者在事务内设置
  owner_id          uuid,
  department_id     uuid,
  note              varchar(500) NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一个平台的同一个 ASIN 只能配一个监控对象 ——
  --    「同一个竞品配了两套抓取频率」是配置错误
  UNIQUE(tenant_id, platform_id, external_item_id),
  CHECK(btrim(external_item_id) <> ''),
  CHECK(price_increase_ratio_threshold IS NULL
        OR (price_increase_ratio_threshold >= 0 AND price_increase_ratio_threshold < 1)),
  CHECK(rank_worsen_threshold IS NULL OR rank_worsen_threshold > 0)
);
COMMENT ON TABLE listing_change_watch IS
  '4.5.2.2 · **监控对象**（配置）：要盯的目标 ASIN / 竞品 + 抓取频率 + 市场机会判定阈值。'
  '🔴 **本表与自有商品模型零引用关系**（没有 listing_id，没有到 listing 的外键）—— '
  '蓝图加粗句「监控对象与自有商品分离，竞品数据不进自有商品模型，只做参考指标」的物理兑现。'
  '目标身份只有「平台 + 平台商品 ID 原文」这一对。'
  '**本表不与 4.5.2.1 的监控配置合并**（是否该合并留给后续复核，见迁移头注释）。';
COMMENT ON COLUMN listing_change_watch.crawl_interval_minutes IS
  '抓取频率（分钟）—— 蓝图输入项。**真正的抓取调度器归 02 渠道与店铺**，本模块只提供入库端点。';
COMMENT ON COLUMN listing_change_watch.price_increase_ratio_threshold IS
  '涨价幅度阈值（**配置项**，不是结论）：涨价比例超过它 ⇒ 市场机会。**不给 = 不判定**这一条。';

-- ===========================================================================
-- 2. listing_change_crawl · 抓取批次（幂等）
-- ===========================================================================
CREATE TABLE listing_change_crawl (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 本批覆盖哪个监控对象（复合外键 ⇒ 同租户）
  watch_id          uuid NOT NULL,
  -- 🔴 来源：PLATFORM 平台接口 / SCRAPE 定时抓取 / API 人工补录
  source            text NOT NULL DEFAULT 'PLATFORM'
                    CHECK(source IN ('PLATFORM','SCRAPE','API')),
  -- 🔴 进来的通道：WEBHOOK 平台推送 / POLL 轮询补捞 / API 显式录入
  ingest_channel    text NOT NULL DEFAULT 'API'
                    CHECK(ingest_channel IN ('WEBHOOK','POLL','API')),
  -- 🔴 抓取时刻（**这一批**是什么时候跑的；timestamptz）。
  --    与快照的 `observed_at`（**每条观测**是什么时候看到的）是两个时间轴：
  --    一次抓取任务可以带回同一监控对象的**多条历史观测**（回补），
  --    那时「批次时刻」与「观测时刻」本来就不同，合成一列会把回补写错。
  crawled_at        timestamptz NOT NULL,
  -- 🔴 可能存在的不准确窗口（分钟）—— 竞品数据同样是抓来的，
  --    照抄 4.5.1.2 的纪律：窗口大小由抓取方给，服务端**不替它编**
  accuracy_window_minutes int NOT NULL DEFAULT 0
                    CHECK(accuracy_window_minutes >= 0),
  -- 🔴 批次级幂等键：租户内唯一。同一个 key 再次投递 ⇒ 整批不再落库
  idempotency_key   varchar(160) NOT NULL,
  -- 🔴 抓取状态：RECEIVED 已接收 / APPLIED 已落库 / PARTIAL 部分落库
  status            text NOT NULL DEFAULT 'RECEIVED'
                    CHECK(status IN ('RECEIVED','APPLIED','PARTIAL')),
  received_count    integer NOT NULL DEFAULT 0,
  applied_count     integer NOT NULL DEFAULT 0,
  skipped_count     integer NOT NULL DEFAULT 0,
  event_count       integer NOT NULL DEFAULT 0,
  opportunity_count integer NOT NULL DEFAULT 0,
  note              varchar(500) NOT NULL DEFAULT '',
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),
  FOREIGN KEY(tenant_id, watch_id) REFERENCES listing_change_watch(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_change_crawl IS
  '4.5.2.2 · **抓取批次**。蓝图输入项「抓取频率」的落地形态：一次抓取是一个有范围、'
  '有通道、有成败的整体。UNIQUE(tenant_id, idempotency_key) ⇒ 平台重投同一批时整批跳过。';

-- ===========================================================================
-- 3. listing_change_snapshot · 观测快照（事实；历史对比的唯一事实源）
-- ===========================================================================
CREATE TABLE listing_change_snapshot (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  watch_id          uuid NOT NULL,
  crawl_id          uuid NOT NULL,
  -- 🔴 观测时刻（这次看到这些值是什么时候；timestamptz）
  observed_at       timestamptz NOT NULL,
  -- 🔴 可能存在的不准确窗口（分钟）
  accuracy_window_minutes int NOT NULL DEFAULT 0
                    CHECK(accuracy_window_minutes >= 0),
  -- 🔴 上下架状态：LISTED 在售 / OUT_OF_STOCK 断货 / DELISTED 已下架 / UNKNOWN 平台没报
  --    **UNKNOWN ≠ 下架** —— 它是「这次没报」，不产生上下架事件
  listing_status    text NOT NULL DEFAULT 'UNKNOWN'
                    CHECK(listing_status IN ('LISTED','OUT_OF_STOCK','DELISTED','UNKNOWN')),
  -- 🔴 观测价格（**平台侧观测值**，不是内部定价；null = 平台没报价格，不是 0）
  price             numeric(19,4),
  currency          char(3) NOT NULL DEFAULT '',
  -- 🔴 排名（名次；null = 平台没报，不是 0）。名次数字越小越好
  rank_position     integer,
  -- 🔴 排名口径：BSR 平台销量榜 / CATEGORY 类目榜 / KEYWORD 关键词下
  rank_basis        text NOT NULL DEFAULT 'BSR'
                    CHECK(rank_basis IN ('BSR','CATEGORY','KEYWORD')),
  -- 🔴 排名口径的键（关键词原文 / 类目路径）；BSR 时为空串
  rank_basis_key    varchar(200) NOT NULL DEFAULT '',
  -- 🔴 评论数（numeric(19,6)，与 V171 listing.rating_count 同型；null = 平台没报）
  review_count      numeric(19,6),
  source            text NOT NULL DEFAULT 'PLATFORM'
                    CHECK(source IN ('PLATFORM','SCRAPE','API')),
  ingest_channel    text NOT NULL DEFAULT 'API'
                    CHECK(ingest_channel IN ('WEBHOOK','POLL','API')),
  -- 🔴 覆盖次数（抓取方修正同一次抓取时递增）
  revision          integer NOT NULL DEFAULT 1,
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  received_at       timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一次抓取（同一监控对象 × 同一观测时刻 × 同一来源）只有一行
  UNIQUE(tenant_id, watch_id, observed_at, source),
  CHECK(price IS NULL OR price >= 0),
  CHECK(rank_position IS NULL OR rank_position > 0),
  CHECK(review_count IS NULL OR review_count >= 0),
  FOREIGN KEY(tenant_id, watch_id) REFERENCES listing_change_watch(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, crawl_id) REFERENCES listing_change_crawl(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_change_snapshot IS
  '4.5.2.2 · **观测快照**（事实）。一行 = 某时刻观察到的上下架 / 价格 / 排名 / 评论数。'
  '**历史对比以本表为唯一事实源**（对比视图是 SQL 聚合，不落表）。'
  '🔴 本表**没有任何自有商品列**（无 sku_id / internal_sku_id / listing_id）—— '
  '竞品数据不进自有商品模型。';

-- ===========================================================================
-- 4. listing_change_event · 变更事件（蓝图输出项「变更事件流」）
-- ===========================================================================
CREATE TABLE listing_change_event (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  watch_id          uuid NOT NULL,
  -- 🔴 触发本事件的那次观测
  snapshot_id       uuid NOT NULL,
  -- 🔴 作为比较基准的那次观测（null = 首次观测，没有基准）
  previous_snapshot_id uuid,
  -- 🔴 产生本事件的那次抓取批次
  crawl_id          uuid NOT NULL,
  -- 🔴 变化维度：LISTING_STATUS 上下架 / PRICE 价格 / RANK 排名 / REVIEW_COUNT 评论数
  change_type       text NOT NULL
                    CHECK(change_type IN ('LISTING_STATUS','PRICE','RANK','REVIEW_COUNT')),
  -- 🔴 方向：**一律指该维度数值/状态本身的变化，不含好坏判断**
  --    （PRICE 涨价 = INCREASED；RANK 名次数字变大 = INCREASED = 竞品表现变差；
  --      LISTING_STATUS 状态迁移 = CHANGED。好坏由市场机会判定决定）
  direction         text NOT NULL
                    CHECK(direction IN ('INCREASED','DECREASED','CHANGED')),
  -- 🔴 数值维度的旧值 / 新值（PRICE / RANK / REVIEW_COUNT）。
  --    这是**当时那一对观测值的快照**，不是「变化量」——
  --    变化幅度与比例是查询时现算的列，本表**零个变化率列**。
  previous_value    numeric(19,6),
  current_value     numeric(19,6),
  -- 🔴 状态维度的旧值 / 新值（LISTING_STATUS）
  previous_state    varchar(64),
  current_state     varchar(64),
  -- 🔴 判定时刻的价格快照（防后续改价让事件含义漂移；PRICE 事件才有）
  observed_price    numeric(19,4),
  currency          char(3) NOT NULL DEFAULT '',
  -- 🔴 判定时刻
  detected_at       timestamptz NOT NULL,
  -- 🔴 判定依据（写明为什么报这一条，半年后还看得懂）
  reason            varchar(500) NOT NULL DEFAULT '',
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一次观测 × 一个变化维度最多一条事件
  UNIQUE(tenant_id, watch_id, change_type, snapshot_id),
  FOREIGN KEY(tenant_id, watch_id) REFERENCES listing_change_watch(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, snapshot_id) REFERENCES listing_change_snapshot(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, previous_snapshot_id) REFERENCES listing_change_snapshot(tenant_id, id),
  FOREIGN KEY(tenant_id, crawl_id) REFERENCES listing_change_crawl(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_change_event IS
  '4.5.2.2 · **变更事件**（蓝图输出项「变更事件流」）。'
  '一行 = 「某次观测相对基准观测，某个维度变了」。'
  '🔴 **本表零个变化率 / 变化幅度列** —— 幅度与比例是查询时现算的（分母为 0 时是 null）。'
  '🔴 previous_value / current_value 是**当时那一对观测值的快照**，不是结论。';
COMMENT ON COLUMN listing_change_event.direction IS
  '方向，**只指数值/状态本身的变化，不含好坏判断**：'
  'PRICE INCREASED=涨价；RANK INCREASED=名次数字变大（=竞品表现变差）；'
  'LISTING_STATUS CHANGED=状态迁移。好坏由市场机会判定决定。';

-- ===========================================================================
-- 5. listing_change_opportunity · 市场机会（判定结论）
-- ===========================================================================
CREATE TABLE listing_change_opportunity (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  watch_id          uuid NOT NULL,
  -- 🔴 触发本机会的变更事件（一条事件最多一条机会）
  event_id          uuid NOT NULL,
  -- 🔴 机会类型：
  --    COMPETITOR_OUT_OF_STOCK  竞品断货
  --    COMPETITOR_PRICE_INCREASE 竞品涨价
  --    COMPETITOR_RANK_WORSENED 竞品排名变差
  --    COMPETITOR_LISTED        竞品上新 / 重新上架
  opportunity_type  text NOT NULL
                    CHECK(opportunity_type IN ('COMPETITOR_OUT_OF_STOCK','COMPETITOR_PRICE_INCREASE',
                                               'COMPETITOR_RANK_WORSENED','COMPETITOR_LISTED')),
  -- 🔴 严重度：HIGH / MEDIUM / LOW
  severity          text NOT NULL CHECK(severity IN ('HIGH','MEDIUM','LOW')),
  -- 🔴 判定时刻的价格 / 排名 / 评论数快照
  observed_price    numeric(19,4),
  observed_rank     integer,
  observed_review_count numeric(19,6),
  currency          char(3) NOT NULL DEFAULT '',
  -- 🔴 状态：OPEN 未处理 / ACKNOWLEDGED 已阅。
  --     **这是内部的记录状态**，不是任何外部动作 —— 本模块不提供「抢占」「调价」入口。
  status            text NOT NULL DEFAULT 'OPEN'
                    CHECK(status IN ('OPEN','ACKNOWLEDGED')),
  -- 🔴 判定依据
  reason            varchar(500) NOT NULL DEFAULT '',
  acknowledged_at   timestamptz,
  acknowledged_by   uuid,
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  detected_at       timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一条变更事件最多一条市场机会
  UNIQUE(tenant_id, event_id),
  FOREIGN KEY(tenant_id, watch_id) REFERENCES listing_change_watch(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, event_id) REFERENCES listing_change_event(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_change_opportunity IS
  '4.5.2.2 · **市场机会**（职责句「识别市场机会（如竞品断货涨价）」的判定结论）。'
  '🔴 **这是记录，不是通知**：本模块不主动发通知（1 工作台预警未交付）。'
  'status=ACKNOWLEDGED 只是内部「已阅」。'
  '🔴 **本模块不提供任何抢占 / 调价动作入口** —— 竞品数据只做参考指标，'
  '动作由运营在平台侧或自有定价流程里完成。';

-- ===========================================================================
-- 6. 索引
-- ===========================================================================
-- 监控对象侧：按状态筛「在盯哪些目标」
CREATE INDEX listing_change_watch_status
  ON listing_change_watch(tenant_id, status, created_at DESC);
-- 监控对象侧：按平台取
CREATE INDEX listing_change_watch_platform
  ON listing_change_watch(tenant_id, platform_id, external_item_id);
-- 抓取批次侧：按监控对象 + 抓取时刻（最热路径）
CREATE INDEX listing_change_crawl_watch_time
  ON listing_change_crawl(tenant_id, watch_id, crawled_at DESC);
-- 观测快照侧：某监控对象的时间线（历史对比的事实源）
CREATE INDEX listing_change_snapshot_watch_time
  ON listing_change_snapshot(tenant_id, watch_id, observed_at DESC, received_at DESC);
-- 变更事件侧：变更事件流（按时间倒序）
CREATE INDEX listing_change_event_watch_time
  ON listing_change_event(tenant_id, watch_id, detected_at DESC);
-- 变更事件侧：按维度筛（「只看价格变化」）
CREATE INDEX listing_change_event_type_time
  ON listing_change_event(tenant_id, change_type, detected_at DESC);
-- 市场机会侧：按状态 + 时间（工作台式的「未处理机会」列表）
CREATE INDEX listing_change_opportunity_status_time
  ON listing_change_opportunity(tenant_id, status, detected_at DESC);
-- 市场机会侧：按监控对象 + 时间
CREATE INDEX listing_change_opportunity_watch_time
  ON listing_change_opportunity(tenant_id, watch_id, detected_at DESC);

-- ===========================================================================
-- 7. 触发器：结构事实不许靠服务层自觉
-- ===========================================================================
-- (a) 🔴 负责人归属快照：owner_id / department_id 由已认证操作者在事务内设置，
--     客户端的 X-Tenant-Id 头进不来也改不动它。改归属必须走显式转让端点
--     （set_config('app.transfer', ...)），纪律与 V171 stamp_listing_owner 完全一致。
--     本表**自带** owner/department（不是从 listing 派生 —— 竞品与自有商品没有关系）。
CREATE FUNCTION stamp_listing_change_watch_owner() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
    NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
    IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
  ELSE
    IF (NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.department_id IS DISTINCT FROM OLD.department_id)
       AND coalesce(nullif(current_setting('app.transfer',true),'')::boolean,false) IS NOT TRUE
    THEN RAISE EXCEPTION 'Listing change watch ownership transfer must use the explicit transfer flag'
      USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_change_watch_owner BEFORE INSERT OR UPDATE ON listing_change_watch
  FOR EACH ROW EXECUTE FUNCTION stamp_listing_change_watch_owner();

-- (b) 🔴 事件引用的观测必须属于同一个监控对象。
--     「这条价格变化是哪个竞品的」与「它挂在哪个监控对象下」必须答得上来。
CREATE FUNCTION guard_listing_change_event_watch() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  s listing_change_snapshot%ROWTYPE;
BEGIN
  SELECT * INTO s FROM listing_change_snapshot
   WHERE tenant_id=NEW.tenant_id AND id=NEW.snapshot_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing change event % must reference an existing snapshot', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF s.watch_id <> NEW.watch_id THEN
    RAISE EXCEPTION 'Listing change event % must reference a snapshot of the same watch', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_change_event_watch BEFORE INSERT OR UPDATE ON listing_change_event
  FOR EACH ROW EXECUTE FUNCTION guard_listing_change_event_watch();

-- (c) 🔴 事件引用的基准观测必须是**更早**的观测。
--     「变化」的方向性由它保证：基准不比当前早 ⇒ 那不是变化，是顺序错了。
CREATE FUNCTION guard_listing_change_event_previous() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  s listing_change_snapshot%ROWTYPE;
  cur listing_change_snapshot%ROWTYPE;
BEGIN
  IF NEW.previous_snapshot_id IS NULL THEN RETURN NEW; END IF;
  SELECT * INTO s FROM listing_change_snapshot
   WHERE tenant_id=NEW.tenant_id AND id=NEW.previous_snapshot_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing change event % must reference an existing previous snapshot', NEW.id
      USING ERRCODE='23514';
  END IF;
  SELECT * INTO cur FROM listing_change_snapshot
   WHERE tenant_id=NEW.tenant_id AND id=NEW.snapshot_id;
  IF s.watch_id <> NEW.watch_id THEN
    RAISE EXCEPTION 'Listing change event % previous snapshot must belong to the same watch', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF s.observed_at >= cur.observed_at THEN
    RAISE EXCEPTION 'Listing change event % baseline snapshot is not older than the current one', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_change_event_previous BEFORE INSERT OR UPDATE ON listing_change_event
  FOR EACH ROW EXECUTE FUNCTION guard_listing_change_event_previous();

-- (d) 🔴 市场机会引用的事件必须与它是同一个监控对象。
CREATE FUNCTION guard_listing_change_opportunity_consistency() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  e listing_change_event%ROWTYPE;
BEGIN
  SELECT * INTO e FROM listing_change_event
   WHERE tenant_id=NEW.tenant_id AND id=NEW.event_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing change opportunity % must reference an existing event', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF e.watch_id <> NEW.watch_id THEN
    RAISE EXCEPTION 'Listing change opportunity % must reference an event of the same watch', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_change_opportunity_consistency BEFORE INSERT OR UPDATE ON listing_change_opportunity
  FOR EACH ROW EXECUTE FUNCTION guard_listing_change_opportunity_consistency();

-- (e) 🔴 市场机会一旦被「已阅」，它的判定结论与快照**不可再改**。
--     机会是「当时为什么报这一条」的凭据；改它等于改历史。
CREATE FUNCTION guard_listing_change_opportunity_final() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status = 'ACKNOWLEDGED' AND (
       NEW.opportunity_type IS DISTINCT FROM OLD.opportunity_type
    OR NEW.severity IS DISTINCT FROM OLD.severity
    OR NEW.observed_price IS DISTINCT FROM OLD.observed_price
    OR NEW.observed_rank IS DISTINCT FROM OLD.observed_rank
    OR NEW.reason IS DISTINCT FROM OLD.reason
    OR NEW.event_id IS DISTINCT FROM OLD.event_id) THEN
    RAISE EXCEPTION 'Listing change opportunity % is acknowledged; its conclusion is immutable',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_change_opportunity_final BEFORE UPDATE ON listing_change_opportunity
  FOR EACH ROW EXECUTE FUNCTION guard_listing_change_opportunity_final();

-- ===========================================================================
-- 8. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过策略，
-- 隔离测试就成了自欺欺人（9.5.2.1 实测踩过）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_change_watch','listing_change_crawl','listing_change_snapshot',
                             'listing_change_event','listing_change_opportunity'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性随**监控对象自身的归属**收敛（与 V1 shop / V171 listing 同构）。
-- 🔴 本模块的可见性**不能**从 `listing` 派生 —— 竞品与自有商品零引用关系；
--    监控对象自带 owner_id / department_id 归属快照，就是这里的数据范围依据。
CREATE POLICY data_scope ON listing_change_watch AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

CREATE POLICY data_scope ON listing_change_crawl AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_change_watch w
             WHERE w.tenant_id=listing_change_crawl.tenant_id AND w.id=listing_change_crawl.watch_id
             AND channel_scope(channel_operation(),w.owner_id,w.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_change_watch w
             WHERE w.tenant_id=listing_change_crawl.tenant_id AND w.id=listing_change_crawl.watch_id
             AND channel_scope(channel_operation(),w.owner_id,w.department_id)));

CREATE POLICY data_scope ON listing_change_snapshot AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_change_watch w
             WHERE w.tenant_id=listing_change_snapshot.tenant_id AND w.id=listing_change_snapshot.watch_id
             AND channel_scope(channel_operation(),w.owner_id,w.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_change_watch w
             WHERE w.tenant_id=listing_change_snapshot.tenant_id AND w.id=listing_change_snapshot.watch_id
             AND channel_scope(channel_operation(),w.owner_id,w.department_id)));

CREATE POLICY data_scope ON listing_change_event AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_change_watch w
             WHERE w.tenant_id=listing_change_event.tenant_id AND w.id=listing_change_event.watch_id
             AND channel_scope(channel_operation(),w.owner_id,w.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_change_watch w
             WHERE w.tenant_id=listing_change_event.tenant_id AND w.id=listing_change_event.watch_id
             AND channel_scope(channel_operation(),w.owner_id,w.department_id)));

CREATE POLICY data_scope ON listing_change_opportunity AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_change_watch w
             WHERE w.tenant_id=listing_change_opportunity.tenant_id AND w.id=listing_change_opportunity.watch_id
             AND channel_scope(channel_operation(),w.owner_id,w.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_change_watch w
             WHERE w.tenant_id=listing_change_opportunity.tenant_id AND w.id=listing_change_opportunity.watch_id
             AND channel_scope(channel_operation(),w.owner_id,w.department_id)));

-- ===========================================================================
-- 9. 账号与授权（沿用 V1..V206 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_change_watch TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_change_crawl TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_change_snapshot TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_change_event TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_change_opportunity TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V206 同口径）。
REVOKE ALL ON listing_change_watch FROM starcon_app;
REVOKE ALL ON listing_change_crawl FROM starcon_app;
REVOKE ALL ON listing_change_snapshot FROM starcon_app;
REVOKE ALL ON listing_change_event FROM starcon_app;
REVOKE ALL ON listing_change_opportunity FROM starcon_app;

-- ===========================================================================
-- 10. 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：上新与变更监控属于「平台操作/查看」语义，
-- 与 4.1.1.1 列表、4.3.2.2 平台状态回流、4.4.2.x 认领与搬迁、4.5.x 监控系列同族，
-- identity 服务零改动。
