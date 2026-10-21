-- ===========================================================================
-- 14.1.3.1 关键词管理（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.1.3.1 关键词管理
--   - 职责：关键词管理与分析：搜索词报告、关键词表现（展示、点击、转化、CPC、ACOS）、
--           否定词建议、词频与排名、批量调价与否定。
--   - 输入：搜索词与关键词数据同步；批量操作参数。
--   - 输出：关键词报表 + 否定建议 + 批量调整任务。
--   - 关系：上游 = 平台搜索词报告；下游 = 14.2.1.1 竞价、4.5.1.2 排名。
--           **搜索词报告是发现浪费的入口**：高曝光零转化词应尽快否定，否则预算持续流失。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 六张表：关键词 / 表现 / 搜索词 / 否定 / 批量任务 / 批量结果
-- ---------------------------------------------------------------------------
--   ad_keyword                   投放关键词（关键词当前竞价的读取形状 + 归一类型与原始类型并存）
--   ad_keyword_metric            关键词表现（展示 / 点击 / 转化 / 花费 / 销售额，按 UTC 日）
--   ad_search_term               搜索词报告条目（🔴 发现浪费的入口）
--   ad_negative_keyword          否定词（🔴 必须带依据快照，可追溯）
--   ad_keyword_bulk_action       批量调整任务（批量调价与否定）
--   ad_keyword_bulk_action_item  批量调整的**逐条执行结果**
--
-- 为什么「批量调整任务」与「逐条结果」是两张表：蓝图输出写的是「**批量调整任务**」，
-- 而蓝图加粗句（14.1.1.3 同样口径）要求「**逐条返回执行结果**」。
-- 任务头（一条 = 一次批量操作，含护栏快照与幂等键）与逐条结果（哪一条成了、哪一条没成、
-- 为什么没成）必须分开：混在一张表里就会出现「同一批数据被拆成 N 行、各行各说各话」。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 归一类型与原始类型**并存**（沿用 14.1.2.2，V146）
-- ---------------------------------------------------------------------------
-- `internal_ad_type`（归一后内部类型）与 `platform_ad_type`（原始平台类型标记）
-- **同表并存**：归一只**多一列**，不改原列、不折叠大小写与别名。
-- `UNMAPPED` 是**显式合法值**（照抄 2.1.2.1「映射缺失必须显式报未映射，不能默认落到某个兜底状态」），
-- 不是兜底 —— 它表示「这个广告类型上游没给归一口径」，不是「没有这种广告」。
-- 归一口径的真相在 2.1.2.1（`platform_enum_override` 租户覆盖 > `platform_enum_mapping` 平台默认），
-- 不在本模块的请求里；服务层调 14.1.2.2 的解析拿结果并与声明比对。
--
-- ---------------------------------------------------------------------------
-- 三、🔴🔴 外部引用，不跨车道建 FK
-- ---------------------------------------------------------------------------
-- 14.1.1.1 活动列表是**同批并行**的另一条车道（它建广告活动与活动表现表）。
-- 本模块的 worktree 里看不到它的迁移 ⇒ **不能**建指向它的外键（并入时才会炸）。
-- 活动一律用**外部引用**表达：`platform_id` + `shop_id` + `external_campaign_id`。
-- 同样地，**不引用 14.1.1.1 的任何表**；等它落地后按 `external_campaign_id` 对接。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 排名口径**引用 4.5.1.2，不造第二套**
-- ---------------------------------------------------------------------------
-- `ad_keyword.listing_keyword_id` 可空 FK → `listing_keyword(tenant_id, id)`。
-- 「词频与排名」里的**名次**直接读 4.5.1.2 的 `listing_keyword_rank_snapshot`
-- （同一张表、同样的 `natural_rank` / `ad_rank` 列、同样的「没报就是 null 而不是 0」语义）。
-- 本模块**不新建排名表、不自算名次** —— 两套互不相干的排名口径会让运营无所适从。
-- 「词频」同样引用 4.5.1.2 的关键词集合（`listing_keyword`）：同一个词被多少个商品监控着。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 金额与数量纪律
-- ---------------------------------------------------------------------------
--   * 金额（`spend` / `sales` / `bid`）一律 `numeric(19,4)` + BigDecimal，**无 float/double**。
--   * 计数（`impressions` / `clicks` / `conversions`）用整数（`bigint` / `integer`），
--     **不用 `numeric(19,6)` 冒充计数、绝不用 double**。
--   * **ACOS 不落表**：ACOS = 花费 / 销售额，是**派生比率**，由 SQL 现算；
--     **销售额为 0 时给 NULL（不可比），不是 0** —— 「可比但真的是零」与「压根算不出来」
--     在界面上必须分开（14.1.2.2 已踩过这个坑）。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 时间口径
-- ---------------------------------------------------------------------------
--   * 所有**时刻**列（`synced_at` / `created_at` / `updated_at` / `applied_at`）都是 `timestamptz`，
--     服务层绑 `Timestamp.from(offsetDateTime.toInstant())` + values 位置写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s。
--   * `bucket_date` 是 **UTC 日**（`date`）：指标按日归集，跨时区店铺看的也是同一份 UTC 日历。
--     蓝图要求的「数据截至时间」由 `synced_at` 承担（**显式**，不是猜的）。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 多租户
-- ---------------------------------------------------------------------------
-- 每张表 `tenant_id uuid NOT NULL` + `ENABLE` + **`FORCE`** ROW LEVEL SECURITY
-- （只 ENABLE 的话表 owner 能绕过策略，隔离测试就成了自欺欺人）。
-- 所有复合外键都带 `tenant_id` ⇒ 跨租户挂引用在物理上不可能。
-- 可见性随**店铺**收敛（与 V205 同口径）：`channel_scope(channel_operation(), shop.owner_id, shop.department_id)`。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. ad_keyword · 投放关键词
-- ---------------------------------------------------------------------------
CREATE TABLE ad_keyword (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  -- 🔴 平台（平台目录归 2.1.1.1，同库同服务，只读引用）
  platform_id         uuid NOT NULL REFERENCES platform(id),
  -- 🔴 店铺（可见性随店铺收敛）
  shop_id             uuid NOT NULL,
  -- 🔴 外部活动 id：14.1.1.1 未交付 ⇒ **不建跨车道 FK**，只存外部引用
  external_campaign_id varchar(128) NOT NULL,
  -- 🔴 外部关键词 id（平台侧的投放对象 id）
  external_keyword_id varchar(128) NOT NULL,
  -- 🔴 关键词原文（照抄平台侧的词，不做大小写折叠）
  keyword             varchar(200) NOT NULL,
  -- 🔴 归一后内部类型（14.1.2.2 口径；UNMAPPED 是显式合法值，不是兜底）
  internal_ad_type    varchar(64) NOT NULL,
  -- 🔴 原始平台类型标记（与归一类型**并存**；原样存，不折叠大小写与别名）
  platform_ad_type    varchar(64) NOT NULL,
  -- 🔴 关键词当前竞价（14.2.1.1 未交付 ⇒ 本模块只提供**读取形状**与「批量调价」这个**动作**；
  --    不实现任何竞价策略引擎、动态竞价、目标 ACOS 反推竞价 —— 那是 14.2.1.1 的职责）
  bid                 numeric(19,4) NOT NULL DEFAULT 0 CHECK(bid >= 0),
  -- 🔴 竞价币种：批量调价按币种计价，**币种不一致的条目逐条失败**而不是按同一个数调
  bid_currency        varchar(8) NOT NULL DEFAULT '',
  -- 🔴 指标币种：��个关键词的指标**只能是同一个币种**（首次写入时定下，之后不一致的行被拒）。
  --    理由：ACOS = 花费/销售额 是**同币种内**的比值，跨币种相加再相除是假的。
  --    这一列让「一个关键词的指标只有一个币种」成为**结构事实**，而不是服务层的自觉。
  metric_currency     varchar(8) NOT NULL DEFAULT '',
  -- 🔴 状态：ACTIVE 在投 / PAUSED 暂停 / ARCHIVED 归档（软删除）
  status              text NOT NULL DEFAULT 'ACTIVE'
                      CHECK(status IN ('ACTIVE','PAUSED','ARCHIVED')),
  -- 🔴 关联到 4.5.1.2 的关键词集合（可空 = 还没关联）；**名次口径直接引用它**
  listing_keyword_id  uuid,
  note                varchar(500) NOT NULL DEFAULT '',
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一店铺下同一活动的同一外部关键词只有一条（改价 / 改状态走更新，不新建）
  UNIQUE(tenant_id, shop_id, external_campaign_id, external_keyword_id),
  CHECK(btrim(keyword) <> ''),
  CHECK(btrim(external_campaign_id) <> ''),
  CHECK(btrim(external_keyword_id) <> ''),
  CHECK(btrim(internal_ad_type) <> ''),
  CHECK(btrim(platform_ad_type) <> ''),
  -- 🔴 复合外键带 tenant_id ⇒ 跨租户挂引用在物理上不可能
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id),
  -- 🔴 引用 4.5.1.2 的关键词集合。ON DELETE SET NULL **只清 listing_keyword_id 一列**
  --    （PostgreSQL 15+ 的列列表语法；不清 tenant_id，否则 RLS 的租户锚点就没了）
  FOREIGN KEY(tenant_id, listing_keyword_id) REFERENCES listing_keyword(tenant_id, id)
    ON DELETE SET NULL (listing_keyword_id)
);
COMMENT ON TABLE ad_keyword IS
  '14.1.3.1 投放关键词：关键词当前竞价的读取形状 + 归一类型与原始类型并存 + 外部活动引用（不跨车道建 FK）';

CREATE INDEX ad_keyword_shop ON ad_keyword(tenant_id, shop_id);
CREATE INDEX ad_keyword_campaign ON ad_keyword(tenant_id, external_campaign_id);
CREATE INDEX ad_keyword_type ON ad_keyword(tenant_id, internal_ad_type);
CREATE INDEX ad_keyword_status ON ad_keyword(tenant_id, status);
CREATE INDEX ad_keyword_listing_keyword ON ad_keyword(tenant_id, listing_keyword_id);
CREATE INDEX ad_keyword_text ON ad_keyword(tenant_id, lower(keyword));

-- ---------------------------------------------------------------------------
-- 2. ad_keyword_metric · 关键词表现（按 UTC 日）
-- ---------------------------------------------------------------------------
-- 🔴 蓝图输入「搜索词与关键词数据同步」的**关键词侧**落点：平台回报（或人工补录）的
-- 展示 / 点击 / 转化 / 花费 / 销售额，按 UTC 日一行。
CREATE TABLE ad_keyword_metric (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 关键词（复合外键 ⇒ 跨租户挂引用在物理上不可能）
  keyword_id    uuid NOT NULL,
  -- 🔴 指标日（UTC 日历日；跨时区店铺看到的也是同一份 UTC 日）
  bucket_date   date NOT NULL,
  -- 🔴 展示次数（**计数**：整数，不是浮点）
  impressions   bigint NOT NULL DEFAULT 0 CHECK(impressions >= 0),
  -- 🔴 点击次数（计数）
  clicks        bigint NOT NULL DEFAULT 0 CHECK(clicks >= 0),
  -- 🔴 转化次数（计数）
  conversions   integer NOT NULL DEFAULT 0 CHECK(conversions >= 0),
  -- 🔴 花费（金额：numeric(19,4) + BigDecimal，无浮点）
  spend         numeric(19,4) NOT NULL DEFAULT 0 CHECK(spend >= 0),
  -- 🔴 销售额（金额：numeric(19,4)）
  sales         numeric(19,4) NOT NULL DEFAULT 0 CHECK(sales >= 0),
  -- 🔴 指标币种（ACOS / CPC 是同币种内的比值，跨币种相加没有意义）
  currency      varchar(8) NOT NULL,
  -- 🔴🔴 **数据截至时间**（NOT NULL）：这批指标是截至什么时候的
  --    蓝图加粗句「数据截至时间要显式」的物理落点：它是一列，不是页面上猜的。
  synced_at     timestamptz NOT NULL,
  -- 🔴 来源：PLATFORM 平台回报 / API 人工补录 / IMPORT 批量导入
  source        text NOT NULL DEFAULT 'PLATFORM'
                CHECK(source IN ('PLATFORM','API','IMPORT')),
  -- 🔴 来源引用（平台侧批次 id 之类；用于把「哪一次同步带来的」追回去）
  source_ref    varchar(160) NOT NULL DEFAULT '',
  -- 🔴 批次幂等键：同一批重投不重复累加（指标落两遍会让 ACOS 与 CPC 变成两倍）。
  --    「这一批已经落过」的判定就是「本租户里有没有行的 batch_key 等于它」。
  batch_key     varchar(160) NOT NULL DEFAULT '',
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一关键词同一天只有一行（重复同步 ⇒ 覆盖，不是叠加两遍）
  UNIQUE(tenant_id, keyword_id, bucket_date),
  FOREIGN KEY(tenant_id, keyword_id) REFERENCES ad_keyword(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE ad_keyword_metric IS
  '14.1.3.1 关键词表现：展示/点击/转化（计数，整数）+ 花费/销售额（金额 numeric(19,4)）+ 数据截至时间 synced_at';

CREATE INDEX ad_keyword_metric_bucket ON ad_keyword_metric(tenant_id, keyword_id, bucket_date);
CREATE INDEX ad_keyword_metric_synced ON ad_keyword_metric(tenant_id, synced_at DESC);
-- 🔴 批次幂等判定走这条索引（「这一批落过没有」= 有没有行的 batch_key 等于它）
CREATE INDEX ad_keyword_metric_batch ON ad_keyword_metric(tenant_id, batch_key);

-- ---------------------------------------------------------------------------
-- 3. ad_search_term · 搜索词报告（🔴 发现浪费的入口）
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「**搜索词报告是发现浪费的入口**：高曝光零转化词应尽快否定，
-- 否则预算持续流失」—— 落在这张表上：它是**用户实际搜出来的词**，
-- 与「我们投了哪个词」（ad_keyword）是两件事：投的词带来点击，
-- 搜出来的词带来浪费。把两者混在一张表里，「高曝光零转化」就永远算不出来。
CREATE TABLE ad_search_term (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 挂在哪个投放关键词下（复合外键 ⇒ 跨租户挂引用在物理上不可能）
  keyword_id    uuid NOT NULL,
  -- 🔴 指标日（UTC 日）
  bucket_date   date NOT NULL,
  -- 🔴 搜索词原文（用户实际搜出来的词，与投放词不一定字面相同）
  search_term   varchar(200) NOT NULL,
  -- 🔴 外部活动 id（冗余一份：不跨车道建 FK，只存外部引用）
  external_campaign_id varchar(128) NOT NULL,
  -- 🔴 展示次数（计数）
  impressions   bigint NOT NULL DEFAULT 0 CHECK(impressions >= 0),
  -- 🔴 点击次数（计数）
  clicks        bigint NOT NULL DEFAULT 0 CHECK(clicks >= 0),
  -- 🔴 转化次数（计数）
  conversions   integer NOT NULL DEFAULT 0 CHECK(conversions >= 0),
  -- 🔴 花费（金额 numeric(19,4)）
  spend         numeric(19,4) NOT NULL DEFAULT 0 CHECK(spend >= 0),
  -- 🔴 销售额（金额 numeric(19,4)）
  sales         numeric(19,4) NOT NULL DEFAULT 0 CHECK(sales >= 0),
  -- 🔴 币种
  currency      varchar(8) NOT NULL,
  -- 🔴🔴 数据截至时间（NOT NULL）
  synced_at     timestamptz NOT NULL,
  -- 🔴 来源：PLATFORM / API / IMPORT
  source        text NOT NULL DEFAULT 'PLATFORM'
                CHECK(source IN ('PLATFORM','API','IMPORT')),
  source_ref    varchar(160) NOT NULL DEFAULT '',
  -- 🔴 批次幂等键（与关键词表现同一套口径）
  batch_key     varchar(160) NOT NULL DEFAULT '',
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一关键词、同一天、同一个搜索词只有一行
  UNIQUE(tenant_id, keyword_id, bucket_date, search_term),
  CHECK(btrim(search_term) <> ''),
  FOREIGN KEY(tenant_id, keyword_id) REFERENCES ad_keyword(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE ad_search_term IS
  '14.1.3.1 搜索词报告：用户实际搜出来的词 —— 高曝光零转化的浪费在这里被发现';

CREATE INDEX ad_search_term_bucket ON ad_search_term(tenant_id, bucket_date);
CREATE INDEX ad_search_term_keyword ON ad_search_term(tenant_id, keyword_id, bucket_date);
CREATE INDEX ad_search_term_text ON ad_search_term(tenant_id, lower(search_term));
CREATE INDEX ad_search_term_synced ON ad_search_term(tenant_id, synced_at DESC);
-- 🔴 批次幂等判定走这条索引
CREATE INDEX ad_search_term_batch ON ad_search_term(tenant_id, batch_key);

-- ---------------------------------------------------------------------------
-- 4. ad_negative_keyword · 否定词（🔴 必须带依据快照）
-- ---------------------------------------------------------------------------
-- 🔴 蓝图加粗句「否定要能追溯到依据」的物理落点：
-- `evidence`（jsonb）里存的是**做决定那一刻的真实数值**
-- （曝光 / 点击 / 转化 / 花费 / 销售额 / ACOS / 数据截至时间），
-- 不是一个「建议词表」。事后平台数据变了，也改不了当时为什么否定。
CREATE TABLE ad_negative_keyword (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 否定的对象是投放关键词时可空（否定的可能是**搜索词**而不是投放词）
  keyword_id        uuid,
  -- 🔴 店铺（可见性随店铺收敛）
  shop_id           uuid NOT NULL,
  -- 🔴 外部活动 id（否定词是活动级的；只存外部引用，不跨车道建 FK）
  external_campaign_id varchar(128) NOT NULL,
  -- 🔴 被否定的词原文
  negation_text     varchar(200) NOT NULL,
  -- 🔴 否定类型：NEGATIVE_EXACT 完全不匹配 / NEGATIVE_PHRASE 不含该词组
  negation_type     text NOT NULL DEFAULT 'NEGATIVE_EXACT'
                    CHECK(negation_type IN ('NEGATIVE_EXACT','NEGATIVE_PHRASE')),
  -- 🔴 生命周期：DECIDED 已在系统内决定（**待推平台**）/ PUSHED 平台已确认 /
  --   REJECTED 被平台拒绝
  status            text NOT NULL DEFAULT 'DECIDED'
                    CHECK(status IN ('DECIDED','PUSHED','REJECTED')),
  -- 🔴 原因（进留痕；批量否定时逐条写清为什么）
  reason            varchar(500) NOT NULL DEFAULT '',
  -- 🔴🔴 依据快照（jsonb）：曝光/点击/转化/花费/销售额/ACOS 的**实际数值** + 截至时间
  evidence          jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 依据的统计区间（起止，UTC 日）
  evidence_from     date,
  evidence_to       date,
  -- 🔴 依据是手工给的还是系统建议采纳的
  suggested_by      text NOT NULL DEFAULT 'MANUAL'
                    CHECK(suggested_by IN ('MANUAL','SYSTEM_SUGGESTION')),
  -- 🔴 平台回执时间
  applied_at        timestamptz,
  -- 🔴 平台回执原文
  platform_note     varchar(500) NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(negation_text) <> ''),
  CHECK(btrim(external_campaign_id) <> ''),
  FOREIGN KEY(tenant_id, keyword_id) REFERENCES ad_keyword(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id)
);
COMMENT ON TABLE ad_negative_keyword IS
  '14.1.3.1 否定词：必须带依据快照（曝光/点击/转化/花费/ACOS 的实际数值），可追溯到为什么否定';

CREATE INDEX ad_negative_keyword_keyword ON ad_negative_keyword(tenant_id, keyword_id);
CREATE INDEX ad_negative_keyword_shop ON ad_negative_keyword(tenant_id, shop_id);
CREATE INDEX ad_negative_keyword_status ON ad_negative_keyword(tenant_id, status);
CREATE INDEX ad_negative_keyword_text ON ad_negative_keyword(tenant_id, lower(negation_text));

-- ---------------------------------------------------------------------------
-- 5. ad_keyword_bulk_action · 批量调整任务（批量调价与否定）
-- ---------------------------------------------------------------------------
-- 🔴 护栏（单次上限与总调整上限）写在**表**上：`guardrail` 存的是**这次实际用的上限快照**，
-- 事后能回答「当时按什么上限判的」—— 上限以后可能调了，但那次任务的口径不能被改写。
CREATE TABLE ad_keyword_bulk_action (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  -- 🔴 店铺：一次批量操作**限定在同一店铺内**（跨店铺批量改价容易误伤，且护栏按店铺计价更清楚）
  shop_id         uuid NOT NULL,
  -- 🔴 动作类型：BID_ADJUST 批量调价 / NEGATE 批量否定
  action_type     text NOT NULL CHECK(action_type IN ('BID_ADJUST','NEGATE')),
  -- 🔴 任务状态：RECEIVED 已受理 / APPLIED 全部成功 / PARTIAL 部分成功 / REJECTED 整批拒绝
  status          text NOT NULL DEFAULT 'RECEIVED'
                  CHECK(status IN ('RECEIVED','APPLIED','PARTIAL','REJECTED')),
  -- 🔴 调价币种（批量调价时必填；逐条按币种核对，币种不一致的条目失败）
  currency        varchar(8) NOT NULL DEFAULT '',
  requested_count integer NOT NULL DEFAULT 0 CHECK(requested_count >= 0),
  applied_count   integer NOT NULL DEFAULT 0 CHECK(applied_count >= 0),
  failed_count    integer NOT NULL DEFAULT 0 CHECK(failed_count >= 0),
  -- 🔴 护栏快照（jsonb）：单次条目上限 / 幅度上限 / 绝对上限 / 总上调上限 / 实际总上调
  guardrail       jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 幂等键（租户内唯一）：重投同一批不会重复调价（调价是**花钱**的动作，重复执行是事故）
  idempotency_key varchar(160) NOT NULL,
  -- 🔴 整批被拒的原因（护栏不过时写清是哪一条上限）
  reason          varchar(500) NOT NULL DEFAULT '',
  note            varchar(500) NOT NULL DEFAULT '',
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key)
);
COMMENT ON TABLE ad_keyword_bulk_action IS
  '14.1.3.1 批量调整任务：批量调价与否定；护栏快照（单次上限 / 总上调上限）随任务留痕';

CREATE INDEX ad_keyword_bulk_action_shop ON ad_keyword_bulk_action(tenant_id, shop_id);
CREATE INDEX ad_keyword_bulk_action_created ON ad_keyword_bulk_action(tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 6. ad_keyword_bulk_action_item · 批量调整的逐条执行结果
-- ---------------------------------------------------------------------------
CREATE TABLE ad_keyword_bulk_action_item (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  -- 🔴 所属批量任务（复合外键 ⇒ 跨租户挂引用在物理上不可能）
  action_id    uuid NOT NULL,
  -- 🔴 目标关键词
  keyword_id   uuid NOT NULL,
  -- 🔴 目标原文（关键词文本 / 搜索词文本）
  target_ref   varchar(200) NOT NULL DEFAULT '',
  -- 🔴 调价前后的竞价（金额 numeric(19,4)；否定动作时为空）
  before_bid   numeric(19,4),
  after_bid    numeric(19,4),
  -- 🔴 逐条结果：APPLIED 成功 / FAILED 失败 / SKIPPED 被跳过（整批被拒时逐条标跳过）
  status       text NOT NULL CHECK(status IN ('APPLIED','FAILED','SKIPPED')),
  -- 🔴 逐条原因（不静默丢弃；失败必须写清为什么）
  reason       varchar(500) NOT NULL DEFAULT '',
  -- 🔴 逐条附加信息（否定时是依据快照）
  result       jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一任务里同一关键词只有一条
  UNIQUE(tenant_id, action_id, keyword_id),
  FOREIGN KEY(tenant_id, action_id) REFERENCES ad_keyword_bulk_action(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, keyword_id) REFERENCES ad_keyword(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE ad_keyword_bulk_action_item IS
  '14.1.3.1 批量调整的逐条执行结果：成了 / 没成 / 为什么没成（不静默丢弃）';

CREATE INDEX ad_keyword_bulk_action_item_action ON ad_keyword_bulk_action_item(tenant_id, action_id);
CREATE INDEX ad_keyword_bulk_action_item_keyword ON ad_keyword_bulk_action_item(tenant_id, keyword_id);

-- ---------------------------------------------------------------------------
-- 7. 🔴 触发器：指标行的币种与关键词一致、否定必须带依据
-- ---------------------------------------------------------------------------
-- 🔴 触发器 (a)：**指标币种必须与关键词的指标币种一致**。
--    同一个关键词下的花费与销售额混了两个币种，ACOS 就是假的（加法本身就没意义）。
--    关键词的 metric_currency 为空（还没写过指标）时，首行写入会由服务层把它定下来。
CREATE FUNCTION guard_ad_keyword_metric_currency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE kw ad_keyword%ROWTYPE;
BEGIN
  SELECT * INTO kw FROM ad_keyword
   WHERE tenant_id=NEW.tenant_id AND id=NEW.keyword_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ad keyword metric % must reference an existing keyword', NEW.id
      USING ERRCODE='23503';
  END IF;
  IF kw.metric_currency <> '' AND NEW.currency <> kw.metric_currency THEN
    RAISE EXCEPTION 'Ad keyword metric % currency % does not match keyword metric currency %',
      NEW.id, NEW.currency, kw.metric_currency USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_ad_keyword_metric_currency_trg BEFORE INSERT OR UPDATE ON ad_keyword_metric
  FOR EACH ROW EXECUTE FUNCTION guard_ad_keyword_metric_currency();

-- 🔴 触发器 (b)：**否定必须有依据**（evidence 非空对象）。
--    「否定要能追溯到依据」不能只靠服务层自觉 —— 直接打 SQL 也必须被拒。
CREATE FUNCTION guard_ad_negative_keyword_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.evidence IS NULL OR NEW.evidence = '{}'::jsonb THEN
    RAISE EXCEPTION 'Ad negative keyword % must carry its evidence (曝光/点击/转化/花费/ACOS 的实际数值)',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_ad_negative_keyword_evidence_trg BEFORE INSERT OR UPDATE ON ad_negative_keyword
  FOR EACH ROW EXECUTE FUNCTION guard_ad_negative_keyword_evidence();

-- 🔴 触发器 (c)：否定行的店铺与关键词必须一致（否定的是「这个活动下的这个词」）。
CREATE FUNCTION guard_ad_negative_keyword_shop() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE kw ad_keyword%ROWTYPE;
BEGIN
  IF NEW.keyword_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT * INTO kw FROM ad_keyword
   WHERE tenant_id=NEW.tenant_id AND id=NEW.keyword_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ad negative keyword % must reference an existing keyword', NEW.id
      USING ERRCODE='23503';
  END IF;
  IF kw.shop_id <> NEW.shop_id THEN
    RAISE EXCEPTION 'Ad negative keyword % must reference a keyword of the same shop', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_ad_negative_keyword_shop_trg BEFORE INSERT OR UPDATE ON ad_negative_keyword
  FOR EACH ROW EXECUTE FUNCTION guard_ad_negative_keyword_shop();

-- ===========================================================================
-- 8. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过策略，
-- 隔离测试就成了自欺欺人（4.5.1.1 实测踩过）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['ad_keyword','ad_keyword_metric','ad_search_term',
                             'ad_negative_keyword','ad_keyword_bulk_action',
                             'ad_keyword_bulk_action_item'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性随**店铺**收敛（与 V205 listing_keyword 同口径）：
-- 运营不能通过广告关键词数据看到无权店铺的投放表现。
-- 🔴 子表通过**父表**收敛（指标/搜索词/否定 → 关键词 → 店铺；
--    批量结果 → 批量任务 → 店铺），不在子表上再存一份归属，避免两份归属打架。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['ad_keyword','ad_keyword_bulk_action'] LOOP
    EXECUTE format(
      'CREATE POLICY data_scope ON %I AS RESTRICTIVE
         USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=%1$s.tenant_id
                    AND s.id=%1$s.shop_id
                    AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
         WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=%1$s.tenant_id
                    AND s.id=%1$s.shop_id
                    AND channel_scope(channel_operation(),s.owner_id,s.department_id)))', tbl);
  END LOOP;

  FOREACH tbl IN ARRAY ARRAY['ad_keyword_metric','ad_search_term','ad_negative_keyword'] LOOP
    EXECUTE format(
      'CREATE POLICY data_scope ON %I AS RESTRICTIVE
         USING (EXISTS(SELECT 1 FROM ad_keyword k WHERE k.tenant_id=%1$s.tenant_id
                    AND k.id=%1$s.keyword_id
                    AND channel_scope(channel_operation(),
                         (SELECT s.owner_id FROM shop s WHERE s.tenant_id=k.tenant_id AND s.id=k.shop_id),
                         (SELECT s.department_id FROM shop s WHERE s.tenant_id=k.tenant_id AND s.id=k.shop_id))))
         WITH CHECK (EXISTS(SELECT 1 FROM ad_keyword k WHERE k.tenant_id=%1$s.tenant_id
                    AND k.id=%1$s.keyword_id
                    AND channel_scope(channel_operation(),
                         (SELECT s.owner_id FROM shop s WHERE s.tenant_id=k.tenant_id AND s.id=k.shop_id),
                         (SELECT s.department_id FROM shop s WHERE s.tenant_id=k.tenant_id AND s.id=k.shop_id))))', tbl);
  END LOOP;

  FOREACH tbl IN ARRAY ARRAY['ad_keyword_bulk_action_item'] LOOP
    EXECUTE format(
      'CREATE POLICY data_scope ON %I AS RESTRICTIVE
         USING (EXISTS(SELECT 1 FROM ad_keyword_bulk_action a WHERE a.tenant_id=%1$s.tenant_id
                    AND a.id=%1$s.action_id
                    AND channel_scope(channel_operation(),
                         (SELECT s.owner_id FROM shop s WHERE s.tenant_id=a.tenant_id AND s.id=a.shop_id),
                         (SELECT s.department_id FROM shop s WHERE s.tenant_id=a.tenant_id AND s.id=a.shop_id))))
         WITH CHECK (EXISTS(SELECT 1 FROM ad_keyword_bulk_action a WHERE a.tenant_id=%1$s.tenant_id
                    AND a.id=%1$s.action_id
                    AND channel_scope(channel_operation(),
                         (SELECT s.owner_id FROM shop s WHERE s.tenant_id=a.tenant_id AND s.id=a.shop_id),
                         (SELECT s.department_id FROM shop s WHERE s.tenant_id=a.tenant_id AND s.id=a.shop_id))))', tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 9. 账号与授权（沿用 V1..V271 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON ad_keyword TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ad_keyword_metric TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ad_search_term TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ad_negative_keyword TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ad_keyword_bulk_action TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ad_keyword_bulk_action_item TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V271 同口径）。
REVOKE ALL ON ad_keyword FROM starcon_app;
REVOKE ALL ON ad_keyword_metric FROM starcon_app;
REVOKE ALL ON ad_search_term FROM starcon_app;
REVOKE ALL ON ad_negative_keyword FROM starcon_app;
REVOKE ALL ON ad_keyword_bulk_action FROM starcon_app;
REVOKE ALL ON ad_keyword_bulk_action_item FROM starcon_app;
