-- ===========================================================================
-- 4.5.1.2 关键词排名（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.5.1.2 关键词排名
--   - 职责：监控商品在核心关键词下的自然排名与广告排名，记录历史变化，低于阈值时预警。
--   - 输入：关键词集合（按商品维护）；排名抓取任务；阈值设置。
--   - 输出：排名历史表 + 变化预警 + 关键词维度报表。
--   - 关系：上游 = 抓取任务（02 通道）；下游 = 1 工作台预警、14 广告。**排名数据依赖抓取频率与
--           平台限制**，必须明确标注抓取时间与可能的不准确窗口。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 本迁移最重要的一件事：**每个排名数据点都必须带抓取时间与不准确窗口**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口」
-- 落到本迁移上是**四件缺一不可**的事：
--
--   ① **抓取时间是一列，不是猜的**：`listing_keyword_rank_snapshot.crawled_at` NOT NULL。
--      排名是「某一次抓取看到的名次」，不是「现在的名次」—— 这两件事差着一个抓取周期。
--      谁把排名当成实时值用，结论就是错的。
--
--   ② **不准确窗口也是一列**：`accuracy_window_minutes` NOT NULL。
--      平台的名次本身有波动（个性化、库存、地域、A/B 实验），抓回来的那一刻就已经
--      不是最终值了。窗口多大由抓取方给（平台限制 + 抓取频率），服务端**不替它编**。
--      把窗口写进数据行，而不是只写进页面文案 —— 否则导出的报表里那列就没了。
--
--   ③ **接口契约里必须显式给出**「数据截至何时 / 可能存在多大延迟窗口」。
--      `crawled_at` + `accuracyWindowMinutes` + `accuracyNote` 三个字段在**每个**返回
--      排名数据的响应里都有，不是只在一个详情页里。
--
--   ④ **排名没有就写 null，不写 0、不写一个很大的数**。
--      平台没报自然排名 = 「这次没进监测范围」，这与「排名第 0」是两件完全不同的事，
--      写 0 会被下游当成「排第一」或「完全没排名」随便解读。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 排名是**位置**，不是数量
-- ---------------------------------------------------------------------------
-- 自然排名 / 广告排名是**名次**（第几名），用 integer 存；**没有浮点**。
-- 数量类（若将来需要展示抓取条数等）一律 numeric(19,6)，本模块没有这种列。
-- 金额：本模块**没有金额**（排名不涉及钱）—— 不硬塞一列 0 金额占位。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 比率 / 变化量**一律不落表**，SQL 现算
-- ---------------------------------------------------------------------------
-- 「排名较上次的变化」＝ 本次名次 − 上次名次，**不落列**：
--   * 落列就要在每次写入时去查上一次并比较，逻辑散在两处（写入 + 比较），
--     迟早会漂移；
--   * 而且「上一次」是**按抓取时间**定的，不是按行号定的，落列天然会错。
-- 同理「平均排名 / 最佳排名 / 最差排名」都是报表时现算（关键词维度报表走 SQL 聚合）。
--
-- ---------------------------------------------------------------------------
-- 四、四张表：关键词集合 / 抓取任务 / 排名历史 / 变化预警
-- ---------------------------------------------------------------------------
--   listing_keyword               关键词集合（按商品维护）+ 阈值设置
--   listing_keyword_crawl         排名抓取任务（蓝图输入项，一次抓取 = 一个有范围、有通道、有成败的整体）
--   listing_keyword_rank_snapshot 排名历史（蓝图输出项「排名历史表」）
--   listing_keyword_rank_alert    变化预警（蓝图输出项「变化预警」）
--
-- 为什么「抓取任务」要单独一张表：蓝图输入写的是「**排名抓取任务**」。
-- 一次抓取是一个**有范围、有通道、有成败**的整体（覆盖了哪个商品的哪些关键词、
-- 走了哪条通道、哪些关键词没顾上），这些元信息挂在快照行上会出现
-- 「同一批数据被拆成 N 行、每行各说各话」的局面。分开之后，
-- 「还没抓过」与「抓过但这个关键词没数据」是**两回事**（前端空态必须分开渲染），
-- 而这个区分靠的就是这张表。
--
-- 关键词维度报表**不建表** —— 它是排名历史的**聚合视图**，落表就会与事实漂移。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 幂等与覆盖的两级语义
-- ---------------------------------------------------------------------------
--   * **批次级幂等**：`listing_keyword_crawl.idempotency_key` 在租户内唯一。
--     同一个 key 再次投递 ⇒ **整批不再落库**（连预警都不会重复产生），
--     直接返回既有那条抓取记录并把 `replayed` 置 true。
--     key 由抓取方提供（平台 + 任务批次 id 之类），服务端**不替调用方编**。
--   * **同刻覆盖**：同一个 (keyword, crawled_at, source) 只有一行。
--     抓取方**修正**同一次抓取的结果时用**新的**批次 key 推进来 ⇒ 该行被覆盖，
--     `revision` 递增、`last_crawl_id` 指向最后一次写入它的批次。
--     「同一次抓取只能有一行」是 UNIQUE 约束的结构事实，不靠服务层自觉。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 阈值设置：可空 = 不预警
-- ---------------------------------------------------------------------------
-- 三个阈值全部可空，**空 = 该维度不产生预警**：
--   natural_rank_threshold  自然排名掉到第几名以外算预警（排名数字变大 = 变差）
--   ad_rank_threshold       广告排名掉到第几名以外算预警
--   rank_drop_threshold     相对上一次抓取退步多少位算一次「变化预警」
-- 写死一个默认阈值会制造大量运营不想要的预警，而**「没配阈值」与「阈值是 0」是两件事**：
-- 0 意味着「掉出第 0 名就预警」（等于永不触发），那是配置，不是「没配」。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 预警是**记录**，不发通知
-- ---------------------------------------------------------------------------
-- 下游「1 工作台预警」与「14 广告」**都未交付**。
-- 本表只**记录**预警事实（谁、哪个关键词、什么类型、什么名次、什么时候判定），
-- **不去发通知** —— 通知归 1 工作台，广告联动归 14 广告。
-- 预警带 `acknowledged_at`：人工确认的时刻。没有通知通道时，
-- 「有没有人看过」这个事实仍然需要留痕。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 依赖面：只引用同服务已交付的表，零跨车道 DDL 回归
-- ---------------------------------------------------------------------------
--   * V171 `listing`（商品本体；UNIQUE(tenant_id, id) 是本模块复合外键的目标）
--   * V1   `shop` / `platform` / `marketplace`（平台与站点码表，只读）
--   * V1   `channel_scope(...)`（数据范围策略，与 V171/V203 同一套）
--
-- 下游 = 1 工作台预警、14 广告 —— **两段都未交付**，
-- 都在 `GET /capabilities` 里显式声明 available=false + ownerModule + impact，
-- **不假装具备**，前端据此显示「未接入」而不是显示假数据。
--
-- ===========================================================================
-- 1. listing_keyword · 关键词集合（按商品维护）+ 阈值设置
-- ===========================================================================
CREATE TABLE listing_keyword (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 关键词挂在**哪个商品**上（蓝图：关键词集合按商品维护）
  listing_id        uuid NOT NULL,
  -- 🔴 关键词原文（照抄平台侧的词，不做大小写折叠 —— 平台排名对大小写敏感）
  keyword           varchar(200) NOT NULL,
  -- 🔴 平台编码：🔴 **由商品所属店铺推导**，不独立维护
  --    （商品归属店铺 → 店铺归属平台，是唯一事实来源；独立存一份就可能与商品矛盾）
  platform_code     varchar(32) NOT NULL,
  -- 🔴 站点编码（listing.marketplace_id 为空时为 ''）
  --    站点码只在平台内唯一（每个平台都有 US/DE 这类同名站点），平台才是父级
  site_code         varchar(32) NOT NULL DEFAULT '',
  -- 🔴 阈值设置：可空 = 该维度不产生预警（见上文第六节）
  natural_rank_threshold integer CHECK(natural_rank_threshold IS NULL OR natural_rank_threshold > 0),
  ad_rank_threshold      integer CHECK(ad_rank_threshold IS NULL OR ad_rank_threshold > 0),
  rank_drop_threshold    integer CHECK(rank_drop_threshold IS NULL OR rank_drop_threshold > 0),
  -- 🔴 停用标记（软删除）。**排名历史必须留着** —— 关键词停了不等于当初抓到的名次不算数。
  --    真正的物理删除会级联抹掉历史与预警记录，那会让「当时发生了什么」查无对证。
  active           boolean NOT NULL DEFAULT true,
  note              varchar(500) NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一商品下同一关键词在同一平台站点只有一条（改阈值走更新，不新建）
  UNIQUE(tenant_id, listing_id, keyword, platform_code, site_code),
  CHECK(btrim(keyword) <> ''),
  -- 🔴 复合外键带 tenant_id ⇒ 跨租户挂引用在物理上不可能
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_keyword IS
  '4.5.1.2 · **按商品维护的关键词集合**（蓝图输入项）+ 阈值设置。'
  '平台与站点由商品所属店铺/站点推导，唯一事实来源是商品本身。'
  '三个阈值可空 = 该维度不预警（「没配阈值」与「阈值是 0」是两件事）。'
  '停用是软删除：排名历史与预警记录必须留着。';

-- ===========================================================================
-- 2. listing_keyword_crawl · 排名抓取任务（蓝图输入项）
-- ===========================================================================
CREATE TABLE listing_keyword_crawl (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 抓取挂在**哪个商品**上（一次抓取 = 一个商品的一批关键词）
  listing_id        uuid NOT NULL,
  -- 🔴 来源：PLATFORM 平台回报（唯一真实口径）/ API 人工补录
  source            text NOT NULL DEFAULT 'PLATFORM'
                    CHECK(source IN ('PLATFORM','API')),
  -- 🔴 进来的通道：WEBHOOK 平台推送 / POLL 轮询补捞 / API 显式录入
  ingest_channel    text NOT NULL DEFAULT 'API'
                    CHECK(ingest_channel IN ('WEBHOOK','POLL','API')),
  -- 🔴🔴 抓取时间（NOT NULL）。排名是「这一���抓取看到的名次」，不是「现在的名次」。
  crawled_at        timestamptz NOT NULL,
  -- 🔴🔴 可能存在的不准确窗口（分钟）。平台名次本身有波动
  --    （个性化 / 库存 / 地域 / A/B 实验），抓回来的那一刻就不是最终值。
  --    窗口多大由抓取方给（平台限制 + 抓取频率），服务端**不替它编**。
  accuracy_window_minutes integer NOT NULL DEFAULT 0 CHECK(accuracy_window_minutes >= 0),
  -- 本批收到多少条排名（事实：payload 里带了多少条）
  received_count    integer NOT NULL DEFAULT 0,
  -- 其中多少条被跳过（关键词已停用等 —— 记在 detail.skipped 里，**不静默丢弃**）
  skipped_count     integer NOT NULL DEFAULT 0,
  -- 🔴 状态机：RECEIVED 已接收（写入瞬间）→ APPLIED 全部落库 / PARTIAL 部分落库。
  --    终态不可再改（DB 触发器钉死）—— 抓取任务是**只追加**的证据。
  status            text NOT NULL DEFAULT 'RECEIVED'
                    CHECK(status IN ('RECEIVED','APPLIED','PARTIAL')),
  -- 🔴 幂等键：租户内唯一。重复投递同一个 key ⇒ 整批不再落库（连预警都不会重复产生）。
  idempotency_key   varchar(160) NOT NULL,
  -- 抓取方给的原文结构（jsonb，含 skipped 明细），给什么存什么，不解释不加工
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  note              varchar(500) NOT NULL DEFAULT '',
  received_at       timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),
  CHECK(received_count >= 0),
  CHECK(skipped_count >= 0),
  -- 🔴 跳过数不许超过收到数（否则「全部落库」与「部分落库」就分不清了）
  CHECK(skipped_count <= received_count),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_keyword_crawl IS
  '4.5.1.2 · **排名抓取任务**（蓝图输入项）。一次抓取 = 一个有范围、有通道、有成败的整体。'
  'idempotency_key 租户内唯一 ⇒ 重复投递整批不再落库。'
  'crawled_at 与 accuracy_window_minutes 是**契约级**字段：'
  '排名依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口。';

-- ===========================================================================
-- 3. listing_keyword_rank_snapshot · 排名历史（蓝图输出项「排名历史表」）
-- ===========================================================================
CREATE TABLE listing_keyword_rank_snapshot (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 关键词（复合外键 ⇒ 跨租户挂引用在物理上不可能）
  keyword_id        uuid NOT NULL,
  -- 🔴 商品（冗余一份，便于按商品扫历史；复合外键保证与关键词同一个商品）
  listing_id        uuid NOT NULL,
  -- 🔴🔴 自然排名（名次）。**没找到就是 null** —— 不是 0、不是一个很大的数。
  --    「平台这次没报这个关键词」与「排名第 0」是两件完全不同的事。
  natural_rank      integer CHECK(natural_rank IS NULL OR natural_rank > 0),
  -- 🔴🔴 广告排名（名次）。同上，没找到就是 null。
  ad_rank           integer CHECK(ad_rank IS NULL OR ad_rank > 0),
  -- 🔴🔴 抓取时间（NOT NULL）。每个排名数据点都必须带。
  crawled_at        timestamptz NOT NULL,
  -- 🔴🔴 可能存在的不准确窗口（分钟）。跟本批抓取任务一致。
  accuracy_window_minutes integer NOT NULL DEFAULT 0 CHECK(accuracy_window_minutes >= 0),
  -- 🔴 来源与通道（冗余一份，便于按通道筛「哪些是轮询补捞的」）
  source            text NOT NULL DEFAULT 'PLATFORM'
                    CHECK(source IN ('PLATFORM','API')),
  ingest_channel    text NOT NULL DEFAULT 'API'
                    CHECK(ingest_channel IN ('WEBHOOK','POLL','API')),
  -- 🔴 最后一次写入这行的抓取批次（复合外键 ⇒ 同租户且必须同商品，见下方触发器）
  last_crawl_id     uuid NOT NULL,
  -- 🔴 覆盖次数：抓取方修正同一次抓取的结果时递增（1 = 首次抓取）
  revision          integer NOT NULL DEFAULT 1 CHECK(revision >= 1),
  -- 抓取方给的原文结构（jsonb）。平台若自报了别的口径，原样存这里，
  -- **不**另开一列 —— 一个指标两个答案比少一个答案更糟。
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  received_at       timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一次抓取、同一个关键词只有一个数 —— UNIQUE 约束的结构事实，不靠服务层自觉
  UNIQUE(tenant_id, keyword_id, crawled_at, source),
  FOREIGN KEY(tenant_id, keyword_id) REFERENCES listing_keyword(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, last_crawl_id) REFERENCES listing_keyword_crawl(tenant_id, id)
    ON DELETE CASCADE
);
COMMENT ON TABLE listing_keyword_rank_snapshot IS
  '4.5.1.2 · **排名历史**（蓝图输出项）。一行 = 一个关键词的一次抓取结果。'
  'natural_rank / ad_rank 是**名次**；没找到就是 null（不是 0）。'
  '每个数据点都带 crawled_at（抓取时间）与 accuracy_window_minutes（可能的不准确窗口）—— '
  '排名依赖抓取频率与平台限制，这两列是**契约级**的。'
  '「较上次的变化」**不落表**（按抓取时间定的上一次，落列天然会错），一律 SQL 现算。';

-- ===========================================================================
-- 4. listing_keyword_rank_alert · 变化预警（蓝图输出项「变化预警」）
-- ===========================================================================
CREATE TABLE listing_keyword_rank_alert (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  keyword_id        uuid NOT NULL,
  listing_id        uuid NOT NULL,
  -- 🔴 预警类型：
  --    NATURAL_BELOW_THRESHOLD 自然排名掉到阈值之外（名次数字变大 = 变差）
  --    AD_BELOW_THRESHOLD      广告排名掉到阈值之外
  --    NATURAL_RANK_DROPPED    自然排名相对上一次抓取退步超过阈值
  --    AD_RANK_DROPPED         广告排名相对上一次抓取退步超过阈值
  alert_type        text NOT NULL
                    CHECK(alert_type IN ('NATURAL_BELOW_THRESHOLD','AD_BELOW_THRESHOLD',
                                         'NATURAL_RANK_DROPPED','AD_RANK_DROPPED')),
  -- 🔴 判定时看到的名次（null = 这次平台没报这个维度的排名）
  rank_value        integer,
  -- 🔴 上一次抓取的名次（变化预警用；只有第一次抓取时为 null）
  previous_rank     integer,
  -- 🔴 触发时用的阈值（null = 该维度没配阈值 —— 那种情况根本不会有这条预警）
  threshold_value   integer,
  -- 🔴 判定时刻
  detected_at       timestamptz NOT NULL,
  -- 🔴 来源抓取批次与快照（复合外键 ⇒ 同租户）
  crawl_id          uuid NOT NULL,
  snapshot_id       uuid,
  -- 🔴 人工确认的时刻。预警**只是记录**（下游 1 工作台预警未交付，本模块不发通知），
  --    但「有没有人看过」这个事实仍然要留痕。
  acknowledged_at   timestamptz,
  acknowledged_by   uuid,
  -- 判定依据（jsonb）：当时的阈值、上一次名次、窗口大小等
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一次抓取、同一个关键词、同一类型只预警一次（重投不会刷出一堆重复预警）
  --    🔴 键里带 crawl_id 而**不是** snapshot_id：抓取方**修正**同一次抓取的结果时用**新**的
  --    批次 key 推进来，那会覆盖同一行快照（revision+1）—— 若键里只有 snapshot_id，
  --    修正产生的新判定会撞上旧判定而被静默丢掉，修正就等于「没生效」。
  --    带上 crawl_id 之后，修正是一条**新的判定记录**，旧判定作为历史留着。
  UNIQUE(tenant_id, crawl_id, keyword_id, alert_type),
  FOREIGN KEY(tenant_id, keyword_id) REFERENCES listing_keyword(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, crawl_id) REFERENCES listing_keyword_crawl(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_keyword_rank_alert IS
  '4.5.1.2 · **变化预警**（蓝图输出项）。预警是**记录**，不是通知 —— '
  '下游 1 工作台预警与 14 广告都未交付，本模块只把「谁、哪个关键词、什么类型、什么名次、'
  '什么时候判定」写下来，不发通知、不假装已送达。';

-- ===========================================================================
-- 5. 索引
-- ===========================================================================
-- 关键词侧：按商品扫关键词集合（列表页的主查询）
CREATE INDEX listing_keyword_listing
  ON listing_keyword(tenant_id, listing_id, active);
-- 关键词侧：按平台/站点筛（站点码只在平台内唯一，所以平台在前）
CREATE INDEX listing_keyword_platform_site
  ON listing_keyword(tenant_id, platform_code, site_code);
-- 抓取侧：某个商品的抓取历史（回答「这个商品什么时候抓过排名」）
CREATE INDEX listing_keyword_crawl_listing
  ON listing_keyword_crawl(tenant_id, listing_id, crawled_at DESC);
-- 快照侧：单个关键词的排名历史（最热路径：按抓取时间倒序取区间）
CREATE INDEX listing_keyword_rank_snapshot_keyword_time
  ON listing_keyword_rank_snapshot(tenant_id, keyword_id, crawled_at DESC);
-- 快照侧：全租户按抓取时间扫
CREATE INDEX listing_keyword_rank_snapshot_time
  ON listing_keyword_rank_snapshot(tenant_id, crawled_at DESC);
-- 快照侧：按商品扫
CREATE INDEX listing_keyword_rank_snapshot_listing
  ON listing_keyword_rank_snapshot(tenant_id, listing_id, crawled_at DESC);
-- 预警侧：某个关键词的预警历史
CREATE INDEX listing_keyword_rank_alert_keyword
  ON listing_keyword_rank_alert(tenant_id, keyword_id, detected_at DESC);
-- 预警侧：未确认的预警优先看（时间倒序）
CREATE INDEX listing_keyword_rank_alert_open
  ON listing_keyword_rank_alert(tenant_id, acknowledged_at, detected_at DESC);

-- ===========================================================================
-- 6. 触发器：结构事实不许靠服务层自觉
-- ===========================================================================
-- (a) 🔴 快照引用的抓取批次必须**存在**且**是同一个商品**的。
--     「这条排名是从哪次抓取来的」与「这次抓取说的是不是同一个商品」
--     —— 两件事都必须答得上来。
CREATE FUNCTION guard_listing_keyword_rank_snapshot_crawl() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  cy listing_keyword_crawl%ROWTYPE;
BEGIN
  SELECT * INTO cy FROM listing_keyword_crawl
   WHERE tenant_id=NEW.tenant_id AND id=NEW.last_crawl_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing keyword rank snapshot % must reference an existing crawl batch', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF cy.listing_id <> NEW.listing_id THEN
    RAISE EXCEPTION 'Listing keyword rank snapshot % must reference a crawl batch of the same listing',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF cy.crawled_at <> NEW.crawled_at THEN
    RAISE EXCEPTION 'Listing keyword rank snapshot % must carry the crawl batch crawled_at', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_keyword_rank_snapshot_crawl BEFORE INSERT OR UPDATE ON listing_keyword_rank_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_listing_keyword_rank_snapshot_crawl();

-- (b) 🔴 抓取任务一旦落到终态就**不可再改** —— 它是「当时抓了什么」的凭据。
--     抓取方后来补报了同一次抓取的数据，那是**新的一条抓取记录**（新 key），
--     不是把旧的那条 UPDATE 掉。
--     状态机只允许一步：RECEIVED → APPLIED / PARTIAL。
CREATE FUNCTION guard_listing_keyword_crawl_final() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status <> 'RECEIVED' THEN
    RAISE EXCEPTION 'Listing keyword crawl % is already final; a later report is a NEW crawl row, not an update',
      OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.status NOT IN ('APPLIED','PARTIAL') THEN
    RAISE EXCEPTION 'Listing keyword crawl % must move from RECEIVED to APPLIED or PARTIAL', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_keyword_crawl_final BEFORE UPDATE ON listing_keyword_crawl
  FOR EACH ROW EXECUTE FUNCTION guard_listing_keyword_crawl_final();

-- (c) 🔴 预警引用的抓取批次必须**存在**且**是同一个商品**的。
CREATE FUNCTION guard_listing_keyword_rank_alert_crawl() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  cy listing_keyword_crawl%ROWTYPE;
BEGIN
  SELECT * INTO cy FROM listing_keyword_crawl
   WHERE tenant_id=NEW.tenant_id AND id=NEW.crawl_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing keyword rank alert % must reference an existing crawl batch', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF cy.listing_id <> NEW.listing_id THEN
    RAISE EXCEPTION 'Listing keyword rank alert % must reference a crawl batch of the same listing',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_keyword_rank_alert_crawl BEFORE INSERT OR UPDATE ON listing_keyword_rank_alert
  FOR EACH ROW EXECUTE FUNCTION guard_listing_keyword_rank_alert_crawl();

-- ===========================================================================
-- 7. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过策略，
-- 隔离测试就成了自欺欺人（4.5.1.1 实测踩过）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_keyword','listing_keyword_crawl',
                             'listing_keyword_rank_snapshot','listing_keyword_rank_alert'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性随**商品所属店铺**收敛（与 V171 listing_status_event / V203 同一套）：
-- 运营不能通过排名数据看到无权店铺的商品排名。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_keyword','listing_keyword_crawl',
                             'listing_keyword_rank_snapshot','listing_keyword_rank_alert'] LOOP
    EXECUTE format(
      'CREATE POLICY data_scope ON %I AS RESTRICTIVE
         USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=%1$s.tenant_id
                    AND l.id=%1$s.listing_id
                    AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
         WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=%1$s.tenant_id
                    AND l.id=%1$s.listing_id
                    AND channel_scope(channel_operation(),l.owner_id,l.department_id)))', tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 8. 账号与授权（沿用 V1..V203 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_keyword TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_keyword_crawl TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_keyword_rank_snapshot TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_keyword_rank_alert TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V203 同口径）。
REVOKE ALL ON listing_keyword FROM starcon_app;
REVOKE ALL ON listing_keyword_crawl FROM starcon_app;
REVOKE ALL ON listing_keyword_rank_snapshot FROM starcon_app;
REVOKE ALL ON listing_keyword_rank_alert FROM starcon_app;

-- ===========================================================================
-- 9. 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：关键词排名属于「平台操作/查看」语义，
-- 与 4.1.1.1 列表、4.3.2.2 平台状态回流、4.4.2.x 认领与搬迁、4.5.1.1 表现同族，
-- identity 服务零改动。
