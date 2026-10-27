-- ===========================================================================
-- V393 · ad_daypart_strategy —— 14.2.1.2 分时策略
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md:136）：
--
--   ### 14.2.1.2 分时策略
--   - **职责**：按小时设置不同竞价与预算（高峰提价提预算、低谷降价停投），
--     支持按历史出单时段自动推荐分时曲线。
--   - **输入**：分时规则；13.2.1.2 时段分布数据。
--   - **输出**：分时策略配置 + 定时调整任务。
--   - **关系**：上游 = 13.2.1.2；下游 = 14.2.2.2 自动规则。
--     **分时策略需按店铺时区配置**，跨时区店铺用错时区会完全失效。
--
-- ---------------------------------------------------------------------------
-- 0. 这七张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   ad_daypart_strategy              —— 分时策略主表（一个店铺一条策略的形状）
--   ad_daypart_strategy_hour         —— 分时曲线（**店铺本地时区**的 24 个小时）
--   ad_daypart_baseline              —— 基准快照（**防复利**：目标值相对基准算，不相对当前值）
--   ad_daypart_recommendation        —— 推荐批次（按店铺：口径窗口 + 判定参数快照 + 时区归属结论）
--   ad_daypart_recommendation_hour   —— 推荐批次的小时明细（可复算的判定依据）
--   ad_daypart_task                  —— 定时调整任务（跑一轮 = 一条任务）
--   ad_daypart_task_line             —— 任务的逐条调整明细（成了 / 没成 / 为什么没成）
--
-- ⚠️ **纯新建、零 ALTER 任何既有表**：
--   只对 V1 `platform` / V1 `shop` / V273 `ad_campaign` / V274 `ad_keyword`
--   （**已交付的上游**，同服务同库，复合 FK 带 tenant_id ⇒ 跨租户挂载物理上不可能）
--   **只读**引用；14.2.2.2 自动规则（V275，已交付）本模块**不改它的表**，
--   只把「调整上限」以**读取形状**交给它（见文件头第 5 节）。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴🔴 时区是本模块的命门（蓝图关系句的加粗句）
-- ---------------------------------------------------------------------------
--
--   「**分时策略需按店铺时区配置**，跨时区店铺用错时区会完全失效。」
--
--   ⇒ **「按小时」= 店铺本地时区的小时**，不是 UTC 小时，也不是服务器时区小时。
--   ⇒ 时区用 **IANA 标识**（如 `America/Los_Angeles`），**不用固定 UTC 偏移** ——
--     固定偏移会错过夏令时（冬夏整整差一小时），分时策略会**整体错位一小时**。
--     本文件用**触发器**钉死「写进来的时区必须是 `pg_timezone_names` 里真实存在的 IANA 标识」。
--
--   ⇒ **店铺时区从哪来**：channel 的 `shop` 表**没有**时区列；时区在
--     `marketplace.default_timezone`（站点参数，2.1.1.3），店铺与站点的绑定在
--     `shop_marketplace`（V1）。⇒ 店铺时区 = **该店铺启用的站点的 default_timezone**：
--       启用 1 个站点 ⇒ 唯一可定（RESOLVED）
--       启用多个站点但**时区相同** ⇒ 仍可定（RESOLVED）
--       启用多个站点且**时区不同** ⇒ **定不了**（UNRESOLVED，**不能挑一个凑数**）
--       启用 0 个站点 ⇒ **定不了**（UNRESOLVED）
--     定不了的店铺**不进任何小时桶**，在推荐批次里**单列**成
--     「未能按店铺本地时区归属」（`timezone_status='UNRESOLVED'` + 原因）——
--     摊给某个默认时区是造假。
--
--   ⚠️ **启用中的站点才算**（`shop_marketplace.status='ACTIVE'`）：
--     停用站点不再代表「这个店铺在卖这个站点的东西」。
--
--   ⚠️ **两侧时区必须一致**：本模块（channel）按站点参数解析店铺时区，
--     上游 13.2.1.2（reporting）也按同一来源解析。两侧不一致 ⇒ 上游给出的
--     「本地小时桶」在本模块这边会被**读成错误的小时** ⇒ 推荐曲线直接失效。
--     ⇒ 这种店铺**单列**成 `timezone_status='CONFLICT'`，**不产出推荐曲线**。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 分时曲线怎么落到「钱」上（口径写清楚，别让人以为在调时段）
-- ---------------------------------------------------------------------------
--
--   蓝图说的是「**按小时设置不同竞价与预算**（高峰提价提预算、低谷降价停投）」。
--   本模块的数据形状是**一条曲线 × 24 个店铺本地小时**，每小时两个**系数**：
--     bid_multiplier    —— 竞价系数（相对基准竞价；1 = 不动）
--     budget_multiplier —— 预算系数（相对基准日预算；0 = 停投）
--   动作 action 只有四档：KEEP（不动）/ BOOST（提价提预算）/ REDUCE（降价）/ PAUSE（停投）。
--
--   ⚠️ **「停投」在本模块里 = 日预算系数置 0**，**不是**把活动状态改成 PAUSED：
--     活动状态是**活动级**的，一个状态表达不了「只在 15:00 停投」；
--     而且把活动置 PAUSED 会**覆盖人工暂停**（人工暂停是人的决定，机器不该替他撤销）。
--     ⇒ 本模块**只改预算与竞价两个数字**，**不碰活动状态**。
--     这个取舍在能力声明里也写明（`campaign_state_toggle` 维度 available=false）。
--
--   ⚠️ **目标值相对「基准」算，不相对「当前值」算**（`ad_daypart_baseline`）：
--     如果每小时拿当前值再乘一次系数，17:00 乘 1.5、18:00 再乘 1.5 ⇒ 变成 2.25 倍，
--     那是**复利**，一天下来能把预算烧穿。
--     ⇒ 基准在策略首次生效时**快照一次**（当时活动的日预算 / 关键词的竞价），
--       之后每个小时的目标 = 基准 × 该小时系数 ⇒ **幂等**（同一小时跑两次结果相同），
--       也不会复利。
--
--   ⚠️ **跨币种绝不相加**：策略一个币种；活动日预算币种或关键词竞价币种与策略币种
--     不一致 ⇒ **逐条拒绝**并写明原因，不按同一个数把不同币种一起调。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 定时调整任务：外部调度触发，**本服务不装 @Scheduled**
-- ---------------------------------------------------------------------------
--
--   与本仓既有纪律一致（V212 物流轨迹拉取、V317 侵权巡检、identity 外部推送）：
--   **多副本下自带定时器要做主节点选举与分布式锁**，那是平台能力不是领域能力；
--   **外部调度**（K8s CronJob）按 `run_interval_minutes` 调一次运行入口，
--   天然单次、可重试、可审计。
--   ⇒ 「定时」= 策略上的 `schedule_enabled` + `run_interval_minutes`（调度器照它编排）
--     + **一个运行入口**（本模块的 POST .../tasks/run）。
--
--   ⚠️ **「当前是几个小时」由 DB 的 now() 换算**，不是 JVM 墙钟：
--     `extract(hour from now() at time zone '<IANA>')` —— 这样夏令时由 PostgreSQL 的
--     tzdata 负责，**固定 UTC 偏移会在冬夏差一小时**。
--   ⚠️ **落库时刻一律取 PG 的 now()**（DB 钟域）。
--
-- ---------------------------------------------------------------------------
-- 4. 🔴 推荐曲线：数据来自 13.2.1.2，判定参数是**租户自己的配置**
-- ---------------------------------------------------------------------------
--
--   上游 = **13.2.1.2 分时与分周模式**（reporting 服务，V392，**已交付**）：
--   本模块**只经授权只读 HTTP API**读它（**零跨库 SQL / 零 FK / 零影子表**），
--   转发的是**调用方自己的** bearer，**不传租户号**（租户仍由上游按已认证成员关系解析）。
--   🔴 **上游读不到一律 503**，不许返回空集合冒充「上游没有数据」。
--
--   判定口径（参数在策略上，可改；**每次生成把参数快照进推荐批次**）：
--     高峰 BOOST：小时订单占比 ≥ 峰值倍数 × 均值占比（1/24），**或**上游判定的 P90 高峰刻度命中
--     停投 PAUSE：小时订单占比 ≤ 停投倍数 × 均值占比（比「低谷」更深的那一档）
--     低谷 REDUCE：小时订单占比 ≤ 低谷倍数 × 均值占比
--     其余 KEEP
--   ⚠️ **占比的分母为 0 时不可比（NULL），不是 0** —— 「不可比」与「真的是零」含义相反。
--   ⚠️ 判定参数**不是硬编码常量**：它们是策略上的可配置列（租户数据），
--     生成推荐时快照进 `ad_daypart_recommendation.profile`（jsonb）⇒ **可复算**。
--
-- ---------------------------------------------------------------------------
-- 5. 🔴 与下游 14.2.2.2 自动规则（V275，已交付）的关系
-- ---------------------------------------------------------------------------
--
--   蓝图的「下游 = 14.2.2.2 自动规则」意味着**自动规则要消费分时策略的调整上限**。
--   14.2.2.2 是**别的车道交付的**，本模块**不改它的表、不 import 它的类**。
--   ⇒ 本模块提供**读取形状**（GET /channel/ad-dayparts/caps）：某店铺当前生效的
--     「单次调整上限 / 总额上限 / 次数上限 / 窗口」+ 适用策略清单，
--     由 14.2.2.2 在执行调整时取用（**两边取更严的那个**）。
--   ⇒ 本模块自己执行调整时也**用同一套上限**（`ad_daypart_strategy` 上的五列），
--     所以「上限」在两侧是同一份数字，不是两份各说各话。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. ad_daypart_strategy —— 分时策略主表
-- ---------------------------------------------------------------------------
--
-- ⚠️ **策略与店铺一对一地绑定**（一个店铺一条策略）：分时策略的每一小时都落在
--   店铺本地时区上，跨店铺的策略要么时区相同（那应该拆成两条，各自可独立调），
--   要么时区不同（那合并成一条就必然有一半小时错位）。
--   ⇒ `UNIQUE(tenant_id, shop_code)`。
CREATE TABLE ad_daypart_strategy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 标识 ===
  strategy_code varchar(64) NOT NULL CHECK(btrim(strategy_code) <> ''),
  strategy_name varchar(128) NOT NULL CHECK(btrim(strategy_name) <> ''),

  -- === 上游 2.3.1.1 店铺（复合 FK 带 tenant_id ⇒ 跨租户挂载不可能）===
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 🔴🔴 **店铺本地时区**（IANA 标识，如 `America/Los_Angeles`）。
  --   **空串 = 定不了时区**（启用多个站点且时区不同 / 没启用站点）。
  shop_timezone varchar(64) NOT NULL DEFAULT '',
  -- 归属状态：RESOLVED 定得了 / UNRESOLVED 定不了 / CONFLICT 与上游解析不一致。
  timezone_status varchar(16) NOT NULL DEFAULT 'UNRESOLVED'
    CHECK(timezone_status IN ('RESOLVED','UNRESOLVED','CONFLICT')),
  -- 定不了 / 冲突的原因（页面上原样显示）。
  timezone_reason varchar(300) NOT NULL DEFAULT '',

  -- === 作用范围 ===
  -- 一个店铺一条策略 ⇒ 作用对象只能是「该店铺全部在投活动」或「该店铺某一个活动」。
  scope varchar(16) NOT NULL DEFAULT 'ALL_CAMPAIGNS'
    CHECK(scope IN ('ALL_CAMPAIGNS','CAMPAIGN')),
  target_campaign_id uuid,
  FOREIGN KEY (tenant_id, target_campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 币种：策略一个币种；跨币种的活动 / 关键词**逐条拒绝**（绝不跨币种相加）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- === 状态 ===
  -- DRAFT 草稿（可编辑）/ ACTIVE 生效 / PAUSED 暂停 / ARCHIVED 归档
  status varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),

  -- 🔴🔴 调整上限（**与 14.2.2.2 自动规则同一套语义**：两个上限取更严的）。
  --   单次调整上限：金额 + 百分比（相对基准）。
  single_adjust_cap_amount numeric(19,4) NOT NULL CHECK(single_adjust_cap_amount > 0),
  single_adjust_cap_percent numeric(19,6) NOT NULL DEFAULT 0 CHECK(single_adjust_cap_percent >= 0),
  -- 总上限：窗口内的累计上调金额 + 次数。
  total_cap_amount numeric(19,4) NOT NULL CHECK(total_cap_amount > 0),
  total_cap_count int NOT NULL CHECK(total_cap_count > 0),
  total_cap_window_hours int NOT NULL CHECK(total_cap_window_hours > 0),

  -- 🔴 上下限保护：调整后的结果值不得越界。
  min_budget numeric(19,4) CHECK(min_budget IS NULL OR min_budget >= 0),
  max_budget numeric(19,4) CHECK(max_budget IS NULL OR max_budget >= 0),
  min_bid numeric(19,4) CHECK(min_bid IS NULL OR min_bid >= 0),
  max_bid numeric(19,4) CHECK(max_bid IS NULL OR max_bid >= 0),

  -- 🔴 试运行：默认 true —— 新建策略一律先试运行，要真执行必须显式关掉。
  --   试运行时**只算不调**，产出「本来会变成多少」。
  dry_run boolean NOT NULL DEFAULT true,

  -- === 定时（外部调度照它编排；本服务不装 @Scheduled）===
  schedule_enabled boolean NOT NULL DEFAULT false,
  run_interval_minutes int NOT NULL DEFAULT 60 CHECK(run_interval_minutes > 0),

  -- === 推荐曲线的判定参数（**租户自己的配置**，不是硬编码常量）===
  -- 高峰倍数：小时占比 ≥ 倍数 × 均值占比 ⇒ BOOST
  recommend_peak_share_multiple numeric(19,6) NOT NULL DEFAULT 2.000000
    CHECK(recommend_peak_share_multiple > 0),
  -- 低谷倍数：小时占比 ≤ 倍数 × 均值占比 ⇒ REDUCE
  recommend_trough_share_multiple numeric(19,6) NOT NULL DEFAULT 0.500000
    CHECK(recommend_trough_share_multiple >= 0),
  -- 停投倍数：小时占比 ≤ 倍数 × 均值占比 ⇒ PAUSE（比 REDUCE 更深的一档）
  recommend_pause_share_multiple numeric(19,6) NOT NULL DEFAULT 0.250000
    CHECK(recommend_pause_share_multiple >= 0),
  -- 各档的系数（相对基准）
  recommend_boost_bid_multiplier numeric(19,6) NOT NULL DEFAULT 1.500000
    CHECK(recommend_boost_bid_multiplier > 0),
  recommend_boost_budget_multiplier numeric(19,6) NOT NULL DEFAULT 1.500000
    CHECK(recommend_boost_budget_multiplier >= 0),
  recommend_reduce_bid_multiplier numeric(19,6) NOT NULL DEFAULT 0.700000
    CHECK(recommend_reduce_bid_multiplier > 0),
  recommend_reduce_budget_multiplier numeric(19,6) NOT NULL DEFAULT 0.700000
    CHECK(recommend_reduce_budget_multiplier >= 0),
  recommend_pause_bid_multiplier numeric(19,6) NOT NULL DEFAULT 0.500000
    CHECK(recommend_pause_bid_multiplier > 0),
  recommend_pause_budget_multiplier numeric(19,6) NOT NULL DEFAULT 0.000000
    CHECK(recommend_pause_budget_multiplier >= 0),

  -- 🔴 **停投倍数必须比低谷倍数更严**（否则「停投」这一档永远命中不了，
  --   写一个永远走不到的配置比不写更糟）。
  CHECK(recommend_pause_share_multiple <= recommend_trough_share_multiple),

  -- === 运行水位 ===
  last_run_at timestamptz,
  last_applied_at timestamptz,

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, strategy_code),
  -- 🔴 一个店铺一条策略（时区语义决定的，不是随手加的约束）。
  UNIQUE(tenant_id, shop_code),

  -- scope 与 target_campaign_id 必须自洽。
  CHECK((scope = 'CAMPAIGN' AND target_campaign_id IS NOT NULL)
     OR (scope = 'ALL_CAMPAIGNS' AND target_campaign_id IS NULL)),

  -- 🔴🔴 **生效的策略必须定得了时区**：
  --   「跨时区店铺用错时区会完全失效」⇒ 时区定不了的策略**不许激活**。
  --   用 DB CHECK 钉死 ⇒ 绕过服务层直接写 SQL 也激活不了。
  CHECK(status <> 'ACTIVE'
     OR (timezone_status = 'RESOLVED' AND btrim(shop_timezone) <> '')),

  -- 上下限必须自洽（上限低于下限 ⇒ 一执行就被拒，白建）。
  CHECK(max_budget IS NULL OR min_budget IS NULL OR max_budget >= min_budget),
  CHECK(max_bid IS NULL OR min_bid IS NULL OR max_bid >= min_bid)
);

COMMENT ON TABLE ad_daypart_strategy IS
  '14.2.1.2 分时策略：一个店铺一条策略（店铺本地时区 + 24 小时曲线 + 调整上限）';

-- ---------------------------------------------------------------------------
-- 2. ad_daypart_strategy_hour —— 分时曲线（24 个店铺本地小时）
-- ---------------------------------------------------------------------------
--
-- ⚠️ **小时是店铺本地时区的小时**（0..23），不是 UTC 小时。
--   `start_hour` 语义与 13.2.1.2 的时段配置一致（**左闭右开**），但这里是
--   **逐小时一行**（不是区间），所以没有区间重叠问题。
-- ⚠️ **系数是比例**（numeric(19,6) + BigDecimal，禁浮点）。
CREATE TABLE ad_daypart_strategy_hour (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  strategy_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, strategy_id)
    REFERENCES ad_daypart_strategy(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 **店铺本地小时**（0..23）。
  local_hour smallint NOT NULL CHECK(local_hour BETWEEN 0 AND 23),

  -- 动作四档：KEEP 不动 / BOOST 提价提预算 / REDUCE 降价 / PAUSE 停投。
  action varchar(16) NOT NULL DEFAULT 'KEEP'
    CHECK(action IN ('KEEP','BOOST','REDUCE','PAUSE')),

  -- 🔴 竞价系数（相对基准竞价；1 = 不动；> 0）。
  bid_multiplier numeric(19,6) NOT NULL DEFAULT 1 CHECK(bid_multiplier > 0),
  -- 🔴 预算系数（相对基准日预算；1 = 不动；0 = 停投）。
  budget_multiplier numeric(19,6) NOT NULL DEFAULT 1 CHECK(budget_multiplier >= 0),

  -- 来源：MANUAL 人工配置 / RECOMMENDED 由 13.2.1.2 的分布推荐生成。
  source varchar(16) NOT NULL DEFAULT 'MANUAL'
    CHECK(source IN ('MANUAL','RECOMMENDED')),
  -- 判定依据快照（页面上原样显示 —— 用户要看得懂「为什么这个小时是高峰」）。
  basis_note varchar(300) NOT NULL DEFAULT '',

  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个策略每个小时只有一行。
  UNIQUE(tenant_id, strategy_id, local_hour),

  -- 🔴 **动作与系数必须自洽**：PAUSE（停投）必须把预算系数压到 0 ——
  --   预算系数还大于 0 却标成「停投」是自相矛盾的配置。
  CHECK(action <> 'PAUSE' OR budget_multiplier = 0)
);

CREATE INDEX ad_daypart_strategy_hour_by_strategy
  ON ad_daypart_strategy_hour(tenant_id, strategy_id, local_hour);

-- ---------------------------------------------------------------------------
-- 3. ad_daypart_baseline —— 基准快照（**防复利**）
-- ---------------------------------------------------------------------------
--
-- ⚠️ 目标值 = **基准 × 系数**，不是 **当前值 × 系数**。
--   后者会在每个小时复利（1.5 × 1.5 = 2.25），一天下来把预算烧穿。
--   基准在**首次用到该对象时快照一次**，之后不再变 ⇒ 同一小时重复跑结果相同（幂等）。
CREATE TABLE ad_daypart_baseline (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  strategy_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, strategy_id)
    REFERENCES ad_daypart_strategy(tenant_id, id) ON DELETE CASCADE,

  -- === 基准对象 ===
  target_type varchar(16) NOT NULL CHECK(target_type IN ('CAMPAIGN','KEYWORD')),
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,
  keyword_id uuid,
  FOREIGN KEY (tenant_id, keyword_id)
    REFERENCES ad_keyword(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 基准值（金额 numeric(19,4)；数量/系数一律 numeric(19,6)）。
  --   CAMPAIGN ⇒ baseline_budget 是基准日预算、baseline_bid 恒为 0；
  --   KEYWORD  ⇒ baseline_bid 是基准竞价、baseline_budget 恒为 0。
  baseline_budget numeric(19,4) NOT NULL DEFAULT 0 CHECK(baseline_budget >= 0),
  baseline_bid numeric(19,4) NOT NULL DEFAULT 0 CHECK(baseline_bid >= 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  captured_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 一个策略对一个对象只有一条基准（keyword_id 为空 = 活动级基准）。
  --    用 coalesce 避开 NULL 在 UNIQUE 里互不相等的问题（活动级与关键词级要分开占位）。
  CHECK((target_type = 'CAMPAIGN' AND keyword_id IS NULL AND baseline_bid = 0)
     OR (target_type = 'KEYWORD' AND keyword_id IS NOT NULL AND baseline_budget = 0))
);

CREATE UNIQUE INDEX ad_daypart_baseline_identity
  ON ad_daypart_baseline(
    tenant_id, strategy_id, campaign_id,
    coalesce(keyword_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- ---------------------------------------------------------------------------
-- 4. ad_daypart_recommendation —— 推荐批次（按店铺）
-- ---------------------------------------------------------------------------
--
-- ⚠️ **定不了时区的店铺也是一条批次**（`timezone_status='UNRESOLVED'` + 原因）：
--   删掉它的话，页面只能说「这个店铺不在批次里」，那会让人以为数据缺失；
--   有一行 UNRESOLVED 才把「它确实定不了时区」这件事说出来了。
CREATE TABLE ad_daypart_recommendation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  strategy_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, strategy_id)
    REFERENCES ad_daypart_strategy(tenant_id, id) ON DELETE CASCADE,

  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 🔴 本批次用的店铺时区（与上游解析一致才产出曲线）。
  shop_timezone varchar(64) NOT NULL DEFAULT '',
  timezone_status varchar(16) NOT NULL DEFAULT 'UNRESOLVED'
    CHECK(timezone_status IN ('RESOLVED','UNRESOLVED','CONFLICT')),
  timezone_reason varchar(300) NOT NULL DEFAULT '',

  -- === 口径窗口（上游 13.2.1.2 的窗口，按店铺本地日期）===
  window_from date NOT NULL,
  window_to date NOT NULL,
  CHECK(window_to >= window_from),
  -- 🔴 汇总口径：本模块**只支持** LOCAL_TIME（店铺本地时间对齐）。
  --   UTC_SLOT 对「买家什么时候下单」这个问题是错的答案（见 V392 第 2 节）。
  alignment varchar(16) NOT NULL DEFAULT 'LOCAL_TIME'
    CHECK(alignment = 'LOCAL_TIME'),

  -- 🔴 判定参数快照（jsonb）：生成这一批时用的**每一个**参数。
  --   ⇒ 事后改策略上的参数，这一批仍然是可复算的。
  profile jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- === 上游结论 ===
  -- OK 产出曲线 / SHOP_UNRESOLVED 店铺定不了时区 / CONFLICT 两侧时区不一致 /
  -- NO_FACT 上游有档案但窗口内没有事实
  upstream_status varchar(16) NOT NULL
    CHECK(upstream_status IN ('OK','SHOP_UNRESOLVED','CONFLICT','NO_FACT')),
  upstream_data_updated_at timestamptz,
  upstream_message varchar(500) NOT NULL DEFAULT '',

  -- === 覆盖度（投影不满时「0 里有多少是没投影」说不清 —— 看覆盖度）===
  projected_order_count numeric(19,6) NOT NULL DEFAULT 0 CHECK(projected_order_count >= 0),
  projected_days int NOT NULL DEFAULT 0 CHECK(projected_days >= 0),
  expected_days int NOT NULL DEFAULT 0 CHECK(expected_days >= 0),

  generated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个策略同一个窗口只有一批（重复生成走 upsert）。
  UNIQUE(tenant_id, strategy_id, window_from, window_to)
);

CREATE INDEX ad_daypart_recommendation_by_shop
  ON ad_daypart_recommendation(tenant_id, shop_code, generated_at DESC);

-- ---------------------------------------------------------------------------
-- 5. ad_daypart_recommendation_hour —— 推荐批次的小时明细
-- ---------------------------------------------------------------------------
CREATE TABLE ad_daypart_recommendation_hour (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  recommendation_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, recommendation_id)
    REFERENCES ad_daypart_recommendation(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 **店铺本地小时**（0..23）。
  local_hour smallint NOT NULL CHECK(local_hour BETWEEN 0 AND 23),

  -- 观测值（上游 13.2.1.2 的本地小时桶）：笔数是数量 ⇒ numeric(19,6)。
  order_count numeric(19,6) NOT NULL DEFAULT 0 CHECK(order_count >= 0),
  -- 🔴 订单占比：**分母为 0 时不可比（NULL），不是 0**。
  order_share numeric(19,6) CHECK(order_share IS NULL OR order_share >= 0),
  -- 上游的 P90 高峰刻度命中（数据驱动的刻度，与租户自己标的时段层级是两回事）。
  p90_peak boolean NOT NULL DEFAULT false,

  -- 建议动作与系数（**这一行本身就是结论快照** ⇒ 可复算）。
  suggested_action varchar(16) NOT NULL
    CHECK(suggested_action IN ('KEEP','BOOST','REDUCE','PAUSE')),
  suggested_bid_multiplier numeric(19,6) NOT NULL CHECK(suggested_bid_multiplier > 0),
  suggested_budget_multiplier numeric(19,6) NOT NULL CHECK(suggested_budget_multiplier >= 0),

  -- 判定依据（页面上原样显示）。
  basis_note varchar(300) NOT NULL DEFAULT '',

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, recommendation_id, local_hour)
);

-- ---------------------------------------------------------------------------
-- 6. ad_daypart_task —— 定时调整任务（跑一轮 = 一条任务）
-- ---------------------------------------------------------------------------
CREATE TABLE ad_daypart_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  strategy_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, strategy_id)
    REFERENCES ad_daypart_strategy(tenant_id, id) ON DELETE CASCADE,

  shop_code varchar(64) NOT NULL,
  shop_timezone varchar(64) NOT NULL DEFAULT '',
  timezone_status varchar(16) NOT NULL DEFAULT 'UNRESOLVED'
    CHECK(timezone_status IN ('RESOLVED','UNRESOLVED','CONFLICT')),

  -- 🔴 **本轮跑的是店铺本地的第几个小时**（由 DB 的 now() 按 IANA 时区换算）。
  local_hour smallint NOT NULL CHECK(local_hour BETWEEN 0 AND 23),

  -- 运行口径：DRY_RUN 只算不调 / APPLY 真调。
  run_mode varchar(16) NOT NULL DEFAULT 'DRY_RUN' CHECK(run_mode IN ('DRY_RUN','APPLY')),
  -- 触发来源：MANUAL 人工点 / SCHEDULED 外部调度调运行入口。
  trigger_source varchar(16) NOT NULL DEFAULT 'MANUAL'
    CHECK(trigger_source IN ('MANUAL','SCHEDULED')),

  status varchar(24) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','APPLIED','PARTIALLY_APPLIED','FAILED','CANCELLED','SKIPPED')),

  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- === 护栏快照（本轮生效的上限，事后可复算为什么被拒）===
  single_adjust_cap_amount numeric(19,4) NOT NULL CHECK(single_adjust_cap_amount > 0),
  single_adjust_cap_percent numeric(19,6) NOT NULL DEFAULT 0 CHECK(single_adjust_cap_percent >= 0),
  total_cap_amount numeric(19,4) NOT NULL CHECK(total_cap_amount > 0),
  total_cap_count int NOT NULL CHECK(total_cap_count > 0),
  total_cap_window_hours int NOT NULL CHECK(total_cap_window_hours > 0),
  -- 🔴 总上限的累计窗口（[window_from, window_to)）。
  window_from timestamptz NOT NULL,
  window_to timestamptz NOT NULL,
  CHECK(window_to > window_from),

  -- === 本轮统计 ===
  applied_count int NOT NULL DEFAULT 0 CHECK(applied_count >= 0),
  rejected_count int NOT NULL DEFAULT 0 CHECK(rejected_count >= 0),
  skipped_count int NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),
  -- 🔴 本轮**上调**金额合计（总上限只管「多花了多少」；下调不占额度）。
  total_increase numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_increase >= 0),

  executed_at timestamptz,
  message varchar(500) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **幂等键**：同一策略 + 同一本地小时 + 同一运行口径，只有一条任务。
  --   外部调度重试（同一小时调两次）不会调两次钱。
  UNIQUE(tenant_id, strategy_id, local_hour, run_mode)
);

CREATE INDEX ad_daypart_task_by_strategy
  ON ad_daypart_task(tenant_id, strategy_id, executed_at DESC);

-- ---------------------------------------------------------------------------
-- 7. ad_daypart_task_line —— 任务的逐条调整明细
-- ---------------------------------------------------------------------------
CREATE TABLE ad_daypart_task_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, task_id)
    REFERENCES ad_daypart_task(tenant_id, id) ON DELETE CASCADE,

  -- === 被调整的对象 ===
  target_type varchar(16) NOT NULL CHECK(target_type IN ('CAMPAIGN_BUDGET','KEYWORD_BID')),
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,
  keyword_id uuid,
  FOREIGN KEY (tenant_id, keyword_id)
    REFERENCES ad_keyword(tenant_id, id) ON DELETE CASCADE,
  -- 快照（对象后来改名/删除，历史任务仍解释得清）。
  campaign_name varchar(200) NOT NULL DEFAULT '',
  target_ref varchar(200) NOT NULL DEFAULT '',

  -- 🔴 本条对应的小时（店铺本地小时）。
  local_hour smallint NOT NULL CHECK(local_hour BETWEEN 0 AND 23),
  action varchar(16) NOT NULL CHECK(action IN ('KEEP','BOOST','REDUCE','PAUSE')),

  -- 🔴 调整前后的金额（金额一律 numeric(19,4)，禁浮点）。
  before_value numeric(19,4) CHECK(before_value IS NULL OR before_value >= 0),
  after_value numeric(19,4) CHECK(after_value IS NULL OR after_value >= 0),
  delta numeric(19,4) NOT NULL DEFAULT 0,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 🔴 逐条结果：
  --   APPLIED  成了
  --   REJECTED 被拒（越限 / 币种不一致 / 写路径拒绝）
  --   SKIPPED  跳过（本来就是目标状态 / 不在投放 / 不适用）
  --   DRY_RUN  试运行：只算出「本来会变成多少」，**没有真调**
  --   PENDING  任务已建、明细还没落
  -- ⚠️ 「试运行」与「跳过」是**两件事**：前者算出了数字，后者压根没动。
  result varchar(16) NOT NULL
    CHECK(result IN ('PENDING','APPLIED','REJECTED','SKIPPED','DRY_RUN')),
  -- 🔴 **被哪一道闸拦下的**（可空 = 没过闸，是业务原因）。
  guardrail_code varchar(32) NOT NULL DEFAULT '',
  -- 逐条原因（没成必须写清为什么；**不静默丢弃**）。
  message varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 关键词级行必须点名关键词；活动级行不得带关键词。
  CHECK((target_type = 'CAMPAIGN_BUDGET' AND keyword_id IS NULL)
     OR (target_type = 'KEYWORD_BID' AND keyword_id IS NOT NULL))
);

-- 🔴 一次任务里同一个对象同一个小时只有一条。
-- ⚠️ **必须建成唯一索引，不能写成表级 UNIQUE 约束**：
--    表级 UNIQUE 约束里**不允许出现表达式**（`coalesce(...)` 会报语法错），
--    而 keyword_id 可空（活动级行）—— NULL 在 UNIQUE 里互不相等，
--    所以要把 NULL 归一化成零 UUID 才能真正去重。
CREATE UNIQUE INDEX ad_daypart_task_line_identity
  ON ad_daypart_task_line(
    tenant_id, task_id, target_type, campaign_id, local_hour,
    coalesce(keyword_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX ad_daypart_task_line_by_task
  ON ad_daypart_task_line(tenant_id, task_id);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 🔴 **时区标识必须是真实存在的 IANA 标识**（触发器钉死）
--     —— 固定 UTC 偏移会在冬夏差一小时，分时策略整体错位。
--     —— 且「定不了时区」不许带时区标识，「定得了」不许没有。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_ad_daypart_strategy_timezone() RETURNS trigger AS $$
BEGIN
  IF NEW.timezone_status = 'RESOLVED' THEN
    IF btrim(NEW.shop_timezone) = '' THEN
      RAISE EXCEPTION '策略 % 标记为时区已定，但时区标识为空', NEW.strategy_code
        USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.shop_timezone) THEN
      RAISE EXCEPTION '时区 % 不是 pg_timezone_names 里真实存在的 IANA 标识（策略 %）',
        NEW.shop_timezone, NEW.strategy_code
        USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    IF btrim(NEW.shop_timezone) <> '' THEN
      RAISE EXCEPTION '策略 % 标记为时区定不了（%），但带了时区标识 %',
        NEW.strategy_code, NEW.timezone_status, NEW.shop_timezone
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER ad_daypart_strategy_timezone
  BEFORE INSERT OR UPDATE ON ad_daypart_strategy
  FOR EACH ROW EXECUTE FUNCTION guard_ad_daypart_strategy_timezone();

-- 推荐批次上的时区同样钉死（它是**结论**，不是自由文本）。
CREATE OR REPLACE FUNCTION guard_ad_daypart_recommendation_timezone() RETURNS trigger AS $$
BEGIN
  IF NEW.timezone_status = 'RESOLVED' THEN
    IF btrim(NEW.shop_timezone) = '' THEN
      RAISE EXCEPTION '推荐批次 % 标记为时区已定，但时区标识为空', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.shop_timezone) THEN
      RAISE EXCEPTION '时区 % 不是 pg_timezone_names 里真实存在的 IANA 标识（推荐批次 %）',
        NEW.shop_timezone, NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    IF btrim(NEW.shop_timezone) <> '' THEN
      RAISE EXCEPTION '推荐批次 % 标记为时区定不了（%），但带了时区标识 %',
        NEW.id, NEW.timezone_status, NEW.shop_timezone
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER ad_daypart_recommendation_timezone
  BEFORE INSERT OR UPDATE ON ad_daypart_recommendation
  FOR EACH ROW EXECUTE FUNCTION guard_ad_daypart_recommendation_timezone();

-- ---------------------------------------------------------------------------
-- (b) 🔴 **一条策略的 24 个小时必须齐全**（延迟约束触发器）
--
--     分时曲线要覆盖**一整天**才谈得上「按小时设置」：缺一个小时，
--     那个小时就落进「没有配置」的空洞 ⇒ 执行时按什么算说不清。
--
--     ⚠️ 用**延迟约束触发器**（DEFERRABLE INITIALLY DEFERRED）：
--        它在**事务提交时**才检查，那时表的最终状态已经定型 ——
--        「新建策略 + 一次性写入 24 行」因此能通过（中间态允许缺行）。
--     ⚠️ 用**逐小时**判覆盖（不用 EXCLUDE USING gist —— 本仓 V226 / V232
--        已明确不引 `btree_gist` 扩展，与 V392 同一写法）。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_ad_daypart_strategy_hour_coverage() RETURNS trigger AS $$
DECLARE
  missing_hours integer;
  t_tenant uuid;
  t_strategy uuid;
BEGIN
  -- 🔴 **DELETE 时用 OLD、其余用 NEW**：被删掉的那一行已经不在表里了，
  --    但它属于哪个租户 / 哪条策略仍然要用来圈定「校验哪张表」。
  IF TG_OP = 'DELETE' THEN
    t_tenant := OLD.tenant_id;
    t_strategy := OLD.strategy_id;
  ELSE
    t_tenant := NEW.tenant_id;
    t_strategy := NEW.strategy_id;
  END IF;

  SELECT count(*) INTO missing_hours
    FROM generate_series(0, 23) h
   WHERE NOT EXISTS (
     SELECT 1 FROM ad_daypart_strategy_hour s
      WHERE s.tenant_id = t_tenant AND s.strategy_id = t_strategy AND s.local_hour = h);

  IF missing_hours > 0 THEN
    RAISE EXCEPTION '策略 % 的分时曲线不完整：24 个小时里缺 % 个（提交时会校验）',
      t_strategy, missing_hours
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ad_daypart_strategy_hour_coverage
  AFTER INSERT OR UPDATE OR DELETE ON ad_daypart_strategy_hour
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_ad_daypart_strategy_hour_coverage();

-- ---------------------------------------------------------------------------
-- (c) 🔴 **生效的策略必须有曲线、且曲线必须真的在动**
--
--     一条 ACTIVE 但 24 行全是 KEEP×1 的策略 = 「什么都没干」——
--     它会占着「这个店铺已配置分时策略」的位置，让运营以为分时已经生效。
--     ⇒ 至少一个小时不是 KEEP×1。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_ad_daypart_strategy_active() RETURNS trigger AS $$
DECLARE
  effective_hours integer;
BEGIN
  IF NEW.status <> 'ACTIVE' THEN
    RETURN NEW;
  END IF;
  SELECT count(*) INTO effective_hours
    FROM ad_daypart_strategy_hour s
   WHERE s.tenant_id = NEW.tenant_id AND s.strategy_id = NEW.id
     AND NOT (s.action = 'KEEP' AND s.bid_multiplier = 1 AND s.budget_multiplier = 1);
  IF effective_hours = 0 THEN
    RAISE EXCEPTION '策略 % 的曲线 24 个小时全是「不动」，激活它等于什么都没配', NEW.strategy_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER ad_daypart_strategy_active
  BEFORE INSERT OR UPDATE ON ad_daypart_strategy
  FOR EACH ROW EXECUTE FUNCTION guard_ad_daypart_strategy_active();

-- ---------------------------------------------------------------------------
-- (d) 🔴 **推荐批次与它的小时明细必须自洽**
--
--     upstream_status = 'OK' ⇒ 必须有 24 行小时明细；
--     upstream_status <> 'OK' ⇒ **不许**有小时明细
--     （定不了时区的店铺硬凑 24 行就是造假）。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_ad_daypart_recommendation_hours() RETURNS trigger AS $$
DECLARE
  hour_count integer;
BEGIN
  SELECT count(*) INTO hour_count
    FROM ad_daypart_recommendation_hour h
   WHERE h.tenant_id = NEW.tenant_id AND h.recommendation_id = NEW.id;
  IF NEW.upstream_status = 'OK' AND hour_count <> 24 THEN
    RAISE EXCEPTION '推荐批次 % 标记为已产出曲线，但小时明细只有 % 行（应为 24 行）',
      NEW.id, hour_count
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.upstream_status <> 'OK' AND hour_count > 0 THEN
    RAISE EXCEPTION '推荐批次 % 的上游结论是 %（没有可用的分布），却带了 % 行小时明细',
      NEW.id, NEW.upstream_status, hour_count
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ad_daypart_recommendation_hours
  AFTER INSERT OR UPDATE ON ad_daypart_recommendation
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_ad_daypart_recommendation_hours();

-- ===========================================================================
-- 第三节：多租户（RLS isolation + 数据范围）与授权
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 多租户隔离：七张表 tenant_id NOT NULL + ENABLE/FORCE RLS isolation
-- 与 channel V1 / V146 / V273 / V274 / V277 / V285 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_daypart_strategy', 'ad_daypart_strategy_hour', 'ad_daypart_baseline',
    'ad_daypart_recommendation', 'ad_daypart_recommendation_hour',
    'ad_daypart_task', 'ad_daypart_task_line'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（照抄 V285 ad_targeting_setting 同构）
-- 策略、基准、推荐、任务都挂在店铺上 ⇒ 同一个收敛口径。
-- ---------------------------------------------------------------------------
CREATE POLICY data_scope ON ad_daypart_strategy AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_daypart_strategy.tenant_id
             AND s.code=ad_daypart_strategy.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_daypart_strategy.tenant_id
             AND s.code=ad_daypart_strategy.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_daypart_strategy_hour AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_daypart_strategy st JOIN shop s
               ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE st.tenant_id=ad_daypart_strategy_hour.tenant_id
               AND st.id=ad_daypart_strategy_hour.strategy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_daypart_strategy st JOIN shop s
               ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE st.tenant_id=ad_daypart_strategy_hour.tenant_id
               AND st.id=ad_daypart_strategy_hour.strategy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_daypart_baseline AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_daypart_strategy st JOIN shop s
               ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE st.tenant_id=ad_daypart_baseline.tenant_id
               AND st.id=ad_daypart_baseline.strategy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_daypart_strategy st JOIN shop s
               ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE st.tenant_id=ad_daypart_baseline.tenant_id
               AND st.id=ad_daypart_baseline.strategy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_daypart_recommendation AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_daypart_strategy st JOIN shop s
               ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE st.tenant_id=ad_daypart_recommendation.tenant_id
               AND st.id=ad_daypart_recommendation.strategy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_daypart_strategy st JOIN shop s
               ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE st.tenant_id=ad_daypart_recommendation.tenant_id
               AND st.id=ad_daypart_recommendation.strategy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 🔴 链路是 **recommendation_hour → recommendation → strategy → shop**
--    （小时明细挂的是**推荐批次**，不是策略 —— 接错一层就等于这条策略的可见性判错）
CREATE POLICY data_scope ON ad_daypart_recommendation_hour AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_daypart_recommendation r
               JOIN ad_daypart_strategy st ON st.tenant_id=r.tenant_id AND st.id=r.strategy_id
               JOIN shop s ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE r.tenant_id=ad_daypart_recommendation_hour.tenant_id
               AND r.id=ad_daypart_recommendation_hour.recommendation_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_daypart_recommendation r
               JOIN ad_daypart_strategy st ON st.tenant_id=r.tenant_id AND st.id=r.strategy_id
               JOIN shop s ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE r.tenant_id=ad_daypart_recommendation_hour.tenant_id
               AND r.id=ad_daypart_recommendation_hour.recommendation_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_daypart_task AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_daypart_strategy st JOIN shop s
               ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE st.tenant_id=ad_daypart_task.tenant_id
               AND st.id=ad_daypart_task.strategy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_daypart_strategy st JOIN shop s
               ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE st.tenant_id=ad_daypart_task.tenant_id
               AND st.id=ad_daypart_task.strategy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_daypart_task_line AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_daypart_task t JOIN ad_daypart_strategy st
               ON st.tenant_id=t.tenant_id AND st.id=t.strategy_id
               JOIN shop s ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE t.tenant_id=ad_daypart_task_line.tenant_id
               AND t.id=ad_daypart_task_line.task_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_daypart_task t JOIN ad_daypart_strategy st
               ON st.tenant_id=t.tenant_id AND st.id=t.strategy_id
               JOIN shop s ON s.tenant_id=st.tenant_id AND s.code=st.shop_code
             WHERE t.tenant_id=ad_daypart_task_line.tenant_id
               AND t.id=ad_daypart_task_line.task_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 第四节：账号与授权（沿用 V1/V273/V274/V285 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  ad_daypart_strategy, ad_daypart_strategy_hour, ad_daypart_baseline,
  ad_daypart_recommendation, ad_daypart_recommendation_hour,
  ad_daypart_task, ad_daypart_task_line TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V285 同口径）。
REVOKE ALL ON
  ad_daypart_strategy, ad_daypart_strategy_hour, ad_daypart_baseline,
  ad_daypart_recommendation, ad_daypart_recommendation_hour,
  ad_daypart_task, ad_daypart_task_line FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：分时策略属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.2.2.1 预算分配、14.2.2.2 自动规则同族，因此 identity 服务零改动。
