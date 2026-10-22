-- ===========================================================================
-- 14.2.2.1 预算分配（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.2.2.1 预算分配
--   - 职责：跨店铺/跨活动的预算分配与再平衡：按销售目标、利润、历史 ROAS 分配预算，
--           识别预算受限的活动与闲置预算。
--   - 输入：预算总额；分配规则；各活动历史表现。
--   - 输出：分配方案 + 再平衡建议 + 执行任务。
--   - 关系：上游 = 14.1.1.1、13.4 利润；下游 = 14.2.2.2。**预算应向利润高的活动倾斜**，
--           按销售额平均分配是常见浪费。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，零 ALTER 任何既有表：
--   * V1 `platform`（全局平台码表）+ V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能。
--   * **V273 `ad_campaign`（已交付的上游 14.1.1.1）**——本模块的活动清单**只读引用**它：
--     复合 FK `FOREIGN KEY (tenant_id, campaign_id) REFERENCES ad_campaign(tenant_id, id)`
--     ⇒ 引用别的租户的活动在物理上不可能，也不必在服务层再判一次租户。
--     V273 已交付且不会被本批并行车道改动 ⇒ 跨车道 DDL 回归面 = 0。
--   * **不引用 13.4 利润**：卷 13 的主责服务 reporting **不在集成头**，利润维度**没有数据源**。
--     本迁移为它留好**权重位**（`ad_budget_rule_weight.weight_source = 'PROFIT'`），
--     但用 **CHECK 钉死权重为 0** ⇒ 13.4 交付前**不可能**算出带假利润的分配额。
--     13.4 交付后放开那条 CHECK 即可接上，**算法本身不用改**（权重来源是可插拔的）。
--   * **不引用 14.2.2.2 自动规则的表**：它按外部 id 定位对象，与本模块零耦合。
--   * 不碰 trade / listing / catalog 的表。
--   * 复用 identity 已注册的 channel.operation.* 权限码，identity 零改动。
--
-- 表名一律走 `ad_budget_` 命名空间，与 V273 `ad_campaign*`、V274 `ad_keyword*`、
-- V275 `ad_rule*` 并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这张模块存在的理由：预算是**钱**，而且「分错了」不会立刻报错
-- ---------------------------------------------------------------------------
-- 按销售额平均分配预算看起来很公平，实际是常见浪费：销售额高的活动本来就在赚钱，
-- 再给它更多预算只会把边际收益递减的活动养肥；而 ROAS 高、预算却打满的活动
-- （**预算受限**）连下一块钱都投不出去。
--
-- 所以本模块必须把三件事落到**能被审阅、能被 DB 拒绝**的粒度：
--
--   1. **分配方案是冻结快照**（`ad_budget_plan_line` 存的是算出来那一刻的量），
--      不是活视图：广告表现每天在变，历史方案必须能复盘「当时为什么这么分」。
--   2. **分配额之和严格等于预算总额**（最大余数法，见 5.2.1.3 同款做法）：
--      少分一分钱是**预算凭空消失**，多分一分钱是**超发预算**。
--   3. **闲置预算与预算受限是两种相反的事实**，必须能一眼分开：
--      闲置 = 有预算但花不出去（钱在睡觉）；受限 = 想花但没预算（钱不够）。
--      把两者混成一个「预算利用率」数字，就没法回答「该给谁加钱」。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 蓝图加粗句「预算应向利润高的活动倾斜」的**物理落点 = 可插拔的权重位**
-- ---------------------------------------------------------------------------
-- 蓝图说「按销售目标、利润、历史 ROAS 分配预算」——三个来源。本迁移把它们建模成
-- **三行权重**（`ad_budget_rule_weight`），而不是把某个来源写死成常量：
--
--   (1) ad_budget_rule_weight.weight_source = 'SALES_TARGET'  销售目标权重
--       分值来自 `ad_budget_rule_target.sales_target`（**人工登记**的销售目标）。
--   (2) ad_budget_rule_weight.weight_source = 'ROAS'          历史 ROAS 权重
--       分值来自 `ad_campaign_daily_metric` 聚合出来的历史 ROAS。
--   (3) ad_budget_rule_weight.weight_source = 'PROFIT'         利润权重
--       🔴 **13.4 利润未交付 ⇒ 没有数据源**。权重位在（可插拔），但：
--         * DB CHECK 钉死 `weight_source = 'PROFIT' ⇒ weight = 0`；
--         * 服务层拒绝任何 PROFIT 权重 > 0 的请求，并说清「等 13.4 交付」。
--       13.4 到位后：放开 CHECK、把利润分值接进评分，**算法不用改**。
--
-- 归一化口径：每个来源先在活动之间归一成分额（share ∈ [0,1]），再按权重加权 ——
-- 这样「销售目标（钱）」与「ROAS（比率）」**单位不同也不会互相污染**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 币种：预算池一个币种，**不折算**
-- ---------------------------------------------------------------------------
-- 5.2.2.1 汇率查询**未交付**（本仓没有可用的汇率来源），而广告活动可以带不同币种。
-- 跨币种分配只有两条路：用假汇率折算（错得离谱且无人察觉），或者**不参与分配**。
-- 本模块选后者：
--   * `ad_budget_pool.currency` 是预算池的币种；
--   * 币种与预算池不一致的活动**不参与分配**，逐条列进 `ad_budget_plan_skipped`
--     并写明原因 `CURRENCY_MISMATCH`（不是静默丢掉）。
-- 绝不写一个 1.0 的假汇率（9.6.2.1 已踩过：假汇率会让下游折算出错的应付）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 上游「各活动历史表现」的取数口径必须写在库里，不是写在脑子里
-- ---------------------------------------------------------------------------
--   * 窗口：方案显式给出 `window_start` / `window_end`（**不���墙上时钟推导**——
--     「上个月」这种键在月初与月末会圈出不同范围，既没法复算也没法断言）。
--   * 日均消耗 = 窗口内总消耗 / **窗口自然日数**（对所有活动一致，口径可复算）。
--     日预算与日均消耗同量纲，才能算利用率。
--   * 利用率 = 日均消耗 / 日预算；**日预算为空或 0 ⇒ 利用率不可比（NULL）**，
--     与 0（可比但真的是零）含义相反。
--   * 预算受限：利用率 >= `constrained_utilization`（规则参数，默认 0.95）。
--   * 闲置预算：利用率 < `idle_utilization`（规则参数，默认 0.30）。
--   * 窗口内**没有每日指标** ⇒ 利用率不可比（**不知道是「没花钱」还是「没同步到」**），
--     既不算闲置也不算受限。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 执行任务改的是 **14.1.1.1 的写入口**，不是另开一条写路径
-- ---------------------------------------------------------------------------
-- `ad_campaign` 是 14.1.1.1 的事实表（**每张事实表只有一个写入所有者**）。
-- 本模块的 `ad_budget_task` 不直接 UPDATE `ad_campaign`，而是由服务层调用
-- **14.1.1.1 的 `AdCampaignService.bulkOperation`**（同服务内的模块间调用，
-- 与 14.1.3.1 → 14.1.2.2 的既有写法同构）执行预算变更：
--   * 护栏（单次调整上限 / 单活动预算上限 / 总额上限）沿用上游那三道闸，
--     **逐条**给出 APPLIED / REJECTED / SKIPPED 与原因，**绝不静默截断**；
--   * 上游会把改动标成「本地已生效、**待回写平台**」（平台回写队列未交付），
--     本模块如实透传，界面上不把「本地已改」说成「平台侧已改」。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 预算 / 分配额 / 闲置预算 / 建议调整额 / 销售目标 一律 **NUMERIC(19,4)**（与 V273 / V275 同口径）；
-- 权重 / 比率（ROAS / ACOS / 利用率 / 权重分额）一律 **NUMERIC(19,6)**。
-- **没有一列是 float/double** —— 这些数直接决定「改多少预算」，浮点不可接受。
-- ROAS / ACOS 的分母为 0 时给 NULL（不可比）而不是 0。
--
-- ---------------------------------------------------------------------------
-- 七、六张表
-- ---------------------------------------------------------------------------
--   ad_budget_rule            分配规则（范围 + 币种 + 上下限 + 利用率阈值）
--   ad_budget_rule_weight     权重位（**可插拔**：销售目标 / ROAS / 利润）
--   ad_budget_rule_target     规则下的活动销售目标（人工登记；含逐活动预算上下限）
--   ad_budget_pool            预算池（**预算总额** + 币种 + 周期）
--   ad_budget_plan            分配方案（一次计算的结果：总额、已分配、闲置、受限）
--   ad_budget_plan_line       方案明细（逐活动冻结快照：当前预算 / 历史表现 / 权重 / 分配额）
--   ad_budget_plan_skipped    本次未纳入分配的活动与原因（**不静默丢掉**）
--   ad_budget_rebalance       再平衡建议（从闲置的活动挪给受限的活动）
--   ad_budget_task            执行任务（从方案生成）
--   ad_budget_task_line       执行任务逐条结果（含被护栏拒绝的那几条）

-- ---------------------------------------------------------------------------
-- ad_budget_rule · 分配规则
-- ---------------------------------------------------------------------------
CREATE TABLE ad_budget_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  rule_code varchar(64) NOT NULL CHECK (btrim(rule_code) <> ''),
  rule_name varchar(128) NOT NULL CHECK (btrim(rule_name) <> ''),

  -- 🔴 一个规则一个币种：预算池与活动币种不一致时不折算（5.2.2.1 汇率未交付）
  currency char(3) NOT NULL CHECK (btrim(currency) <> ''),

  -- === 范围（跨店铺 / 跨活动；都为空 = 本租户全范围）===
  scope_platform_id uuid REFERENCES platform(id),
  scope_shop_code varchar(64),
  FOREIGN KEY (tenant_id, scope_shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- === 逐活动上下限的默认值（规则级；逐活动可覆盖）===
  min_campaign_budget NUMERIC(19,4) CHECK (min_campaign_budget IS NULL OR min_campaign_budget >= 0),
  max_campaign_budget NUMERIC(19,4) CHECK (max_campaign_budget IS NULL OR max_campaign_budget >= 0),

  -- === 🔴 利用率阈值（识别「预算受限」与「闲置预算」）===
  --    利用率 = 日均消耗 / 日预算；阈值是可调的，不是写死的常量。
  constrained_utilization NUMERIC(19,6) NOT NULL DEFAULT 0.950000
    CHECK (constrained_utilization > 0 AND constrained_utilization <= 10),
  idle_utilization NUMERIC(19,6) NOT NULL DEFAULT 0.300000
    CHECK (idle_utilization >= 0 AND idle_utilization < 1),

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','ARCHIVED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code),

  -- 🔴 上下限必须自洽：上限低于下限 ⇒ 算出来的分配额必然越界，白建
  CHECK (max_campaign_budget IS NULL OR min_campaign_budget IS NULL
         OR max_campaign_budget >= min_campaign_budget),
  -- 🔴 受限阈值必须高于闲置阈值，否则一条活动会同时「受限」又「闲置」
  CHECK (constrained_utilization > idle_utilization)
);

CREATE INDEX ad_budget_rule_list ON ad_budget_rule(tenant_id, status, created_at);
CREATE INDEX ad_budget_rule_scope ON ad_budget_rule(tenant_id, scope_platform_id, scope_shop_code);

-- ---------------------------------------------------------------------------
-- ad_budget_rule_weight · 权重位（可插拔的分配来源）
-- ---------------------------------------------------------------------------
-- 🔴 这一张表就是「预算应向利润高的活动倾斜」能被真正接上的地方：
--   利润来源没交付 ⇒ **位子在、权重为 0**；13.4 到位后放开 CHECK 即可，
--   **评分与分摊算法一行都不用改**（新来源 = 新增一行权重）。
-- 绝不像「按销售额平均分」那样把某个来源写死成常量。
CREATE TABLE ad_budget_rule_weight (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_budget_rule(tenant_id, id) ON DELETE CASCADE,

  weight_source varchar(24) NOT NULL
    CHECK (weight_source IN ('SALES_TARGET','ROAS','PROFIT')),
  weight NUMERIC(19,6) NOT NULL CHECK (weight >= 0),
  note text NOT NULL DEFAULT '',
  position int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_id, weight_source),

  -- 🔴🔴 13.4 利润**未交付** ⇒ 利润权重必须为 0。
  --    没有这条 CHECK，服务层一旦漏判就会算出「按假利润分配」的预算额 ——
  --    而假利润比假汇率更危险：它看起来像业务结论。
  --    13.4.1.1 多模式利润报表交付后，**放开这一条**并把利润分值接进评分即可。
  CHECK (weight_source <> 'PROFIT' OR weight = 0)
);

CREATE INDEX ad_budget_rule_weight_rule ON ad_budget_rule_weight(tenant_id, rule_id, position);

-- ---------------------------------------------------------------------------
-- ad_budget_rule_target · 规则下的活动销售目标
-- ---------------------------------------------------------------------------
-- 蓝图的「按销售目标分配」：销售目标是**人工登记**的（上游没有销售目标模块），
-- 逐活动登记，**不跨租户、不引用别的租户的活动**。
CREATE TABLE ad_budget_rule_target (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_budget_rule(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 复合 FK 带 tenant_id ⇒ 引用别的租户的活动在物理上不可能
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id) REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- 销售目标（金额，与规则同币种；服务层校验与规则币种一致）
  sales_target NUMERIC(19,4) NOT NULL CHECK (sales_target > 0),

  -- 逐活动覆盖规则级上下限（为空 = 用规则级的）
  min_budget NUMERIC(19,4) CHECK (min_budget IS NULL OR min_budget >= 0),
  max_budget NUMERIC(19,4) CHECK (max_budget IS NULL OR max_budget >= 0),

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_id, campaign_id),

  CHECK (max_budget IS NULL OR min_budget IS NULL OR max_budget >= min_budget)
);

CREATE INDEX ad_budget_rule_target_rule ON ad_budget_rule_target(tenant_id, rule_id);
CREATE INDEX ad_budget_rule_target_campaign ON ad_budget_rule_target(tenant_id, campaign_id);

-- ---------------------------------------------------------------------------
-- ad_budget_pool · 预算池（蓝图的「预算总额」）
-- ---------------------------------------------------------------------------
CREATE TABLE ad_budget_pool (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  pool_code varchar(64) NOT NULL CHECK (btrim(pool_code) <> ''),
  pool_name varchar(128) NOT NULL CHECK (btrim(pool_name) <> ''),
  currency char(3) NOT NULL CHECK (btrim(currency) <> ''),

  -- 🔴 预算总额：分配额之和必须**严格等于**这个数（服务层用最大余数法保证）
  total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount > 0),

  -- 周期（显式给出，不从墙上时钟推导）
  period_start date NOT NULL,
  period_end date NOT NULL,
  CHECK (period_end >= period_start),

  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_budget_rule(tenant_id, id),

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, pool_code)
);

CREATE INDEX ad_budget_pool_list ON ad_budget_pool(tenant_id, status, created_at);
CREATE INDEX ad_budget_pool_rule ON ad_budget_pool(tenant_id, rule_id);

-- ---------------------------------------------------------------------------
-- ad_budget_plan · 分配方案
-- ---------------------------------------------------------------------------
-- 一次计算 = 一行。**方案是冻结快照**：明细行存的是算出来那一刻的量，
-- 广告表现后来变了也不回写历史方案（否则没法复盘「当时为什么这么分」）。
CREATE TABLE ad_budget_plan (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  pool_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, pool_id) REFERENCES ad_budget_pool(tenant_id, id),
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_budget_rule(tenant_id, id),

  status varchar(16) NOT NULL DEFAULT 'COMPUTED'
    CHECK (status IN ('COMPUTED','APPROVED','APPLIED','SUPERSEDED')),

  currency char(3) NOT NULL CHECK (btrim(currency) <> ''),
  -- 预算总额（本方案针对的池总额）
  total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount > 0),
  -- 已分配额（**必须等于 total_amount**，服务层用最大余数法保证）
  allocated_amount NUMERIC(19,4) NOT NULL CHECK (allocated_amount >= 0),
  -- 未分配额（正常恒为 0；不为 0 说明分配过程有洞，留着可查）
  unallocated_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (unallocated_amount >= 0),
  -- 释放额：Σ max(0, 当前预算 − 分配额)（本方案要收回去的钱）
  released_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (released_amount >= 0),
  -- 🔴 闲置预算：窗口内有预算却花不出去的钱（**与释放额不是一回事**）
  idle_budget NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (idle_budget >= 0),
  -- 🔴 预算受限的缺口：想花但没预算的钱
  constrained_shortfall NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (constrained_shortfall >= 0),

  campaign_count int NOT NULL DEFAULT 0 CHECK (campaign_count >= 0),
  skipped_count int NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),

  -- 🔴 统计窗口（显式给出，可复算）
  window_start date NOT NULL,
  window_end date NOT NULL,
  CHECK (window_end >= window_start),

  computed_at timestamptz NOT NULL DEFAULT now(),
  computed_by uuid,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 🔴 分配额必须严格等于总额（DB 层兜底：少分是预算消失，超发是超预算）
  CHECK (allocated_amount + unallocated_amount = total_amount)
);

CREATE INDEX ad_budget_plan_list ON ad_budget_plan(tenant_id, created_at DESC);
CREATE INDEX ad_budget_plan_pool ON ad_budget_plan(tenant_id, pool_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- ad_budget_plan_line · 方案明细（逐活动冻结快照）
-- ---------------------------------------------------------------------------
CREATE TABLE ad_budget_plan_line (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  plan_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, plan_id) REFERENCES ad_budget_plan(tenant_id, id) ON DELETE CASCADE,

  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id) REFERENCES ad_campaign(tenant_id, id),
  campaign_name text NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  campaign_status varchar(16) NOT NULL DEFAULT '',
  currency char(3) NOT NULL DEFAULT '',

  -- === 快照：算出来那一刻的量 ===
  current_budget NUMERIC(19,4) CHECK (current_budget IS NULL OR current_budget >= 0),
  historical_spend NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (historical_spend >= 0),
  historical_sales NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (historical_sales >= 0),
  historical_orders NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (historical_orders >= 0),
  -- 🔴 ROAS / ACOS：分母为 0 ⇒ NULL（不可比），与 0 含义相反
  roas NUMERIC(19,6),
  acos NUMERIC(19,6),
  -- 🔴 日均消耗 = 窗口内总消耗 / 窗口自然日数
  daily_avg_spend NUMERIC(19,4),
  -- 🔴 利用率 = 日均消耗 / 日预算；日预算为空或 0 ⇒ NULL（不可比）
  utilization NUMERIC(19,6),

  -- === 评分与分配 ===
  sales_target NUMERIC(19,4) CHECK (sales_target IS NULL OR sales_target > 0),
  score NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (score >= 0),
  weight NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (weight >= 0),
  weight_share NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (weight_share >= 0),
  allocated_budget NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (allocated_budget >= 0),
  budget_delta NUMERIC(19,4) NOT NULL DEFAULT 0,

  -- === 判定 ===
  budget_constrained boolean NOT NULL DEFAULT false,
  idle_budget boolean NOT NULL DEFAULT false,
  recommendation varchar(24) NOT NULL DEFAULT 'HOLD'
    CHECK (recommendation IN ('HOLD','INCREASE','DECREASE','PAUSE_CANDIDATE','START_CANDIDATE')),
  reason text NOT NULL DEFAULT '',
  position int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, plan_id, campaign_id)
);

CREATE INDEX ad_budget_plan_line_plan ON ad_budget_plan_line(tenant_id, plan_id, position);
CREATE INDEX ad_budget_plan_line_campaign ON ad_budget_plan_line(tenant_id, campaign_id);

-- ---------------------------------------------------------------------------
-- ad_budget_plan_skipped · 本次未纳入分配的活动与原因
-- ---------------------------------------------------------------------------
-- 🔴 不静默丢掉：币种不一致、既没有销售目标也没有可比的 ROAS、状态不该参与 ——
-- 每一条都要在界面上看得见，并写清**为什么没参与**。
CREATE TABLE ad_budget_plan_skipped (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  plan_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, plan_id) REFERENCES ad_budget_plan(tenant_id, id) ON DELETE CASCADE,

  campaign_id uuid NOT NULL,
  campaign_name text NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  campaign_status varchar(16) NOT NULL DEFAULT '',
  currency char(3) NOT NULL DEFAULT '',
  current_budget NUMERIC(19,4) CHECK (current_budget IS NULL OR current_budget >= 0),
  skip_reason varchar(32) NOT NULL
    CHECK (skip_reason IN ('CURRENCY_MISMATCH','NO_SCORE_BASIS','STATUS_NOT_ELIGIBLE','OUT_OF_SCOPE')),
  reason text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, plan_id, campaign_id)
);

CREATE INDEX ad_budget_plan_skipped_plan ON ad_budget_plan_skipped(tenant_id, plan_id);

-- ---------------------------------------------------------------------------
-- ad_budget_rebalance · 再平衡建议
-- ---------------------------------------------------------------------------
-- 🔴 再平衡**不改变预算总额**：把闲置活动用不上的钱挪给预算受限的活动。
-- 分配方案（改总额）与再平衡（改分布）是两件事，所以分成两张表。
CREATE TABLE ad_budget_rebalance (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  plan_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, plan_id) REFERENCES ad_budget_plan(tenant_id, id) ON DELETE CASCADE,

  from_campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, from_campaign_id) REFERENCES ad_campaign(tenant_id, id),
  to_campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, to_campaign_id) REFERENCES ad_campaign(tenant_id, id),
  -- 🔴 挪动金额：一律 NUMERIC(19,4)，**没有 float/double**
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),

  reason text NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'PROPOSED'
    CHECK (status IN ('PROPOSED','ACCEPTED','REJECTED','APPLIED')),
  decided_at timestamptz,
  decided_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, plan_id, from_campaign_id, to_campaign_id),

  -- 🔴 不能自己给自己挪钱
  CHECK (from_campaign_id <> to_campaign_id)
);

CREATE INDEX ad_budget_rebalance_plan ON ad_budget_rebalance(tenant_id, plan_id);
CREATE INDEX ad_budget_rebalance_status ON ad_budget_rebalance(tenant_id, status, created_at DESC);

-- ---------------------------------------------------------------------------
-- ad_budget_task · 执行任务
-- ---------------------------------------------------------------------------
-- 蓝图输出三件里的第三件：**执行任务**。任务本身是**事实**（谁、什么时候、
-- 按哪份方案、要改成多少），逐条结果另立一张表。
CREATE TABLE ad_budget_task (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  plan_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, plan_id) REFERENCES ad_budget_plan(tenant_id, id),

  task_type varchar(24) NOT NULL DEFAULT 'APPLY_PLAN'
    CHECK (task_type IN ('APPLY_PLAN')),
  status varchar(24) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPLIED','PARTIALLY_APPLIED','FAILED','CANCELLED')),

  currency char(3) NOT NULL DEFAULT '',
  requested_by uuid,
  reason text NOT NULL DEFAULT '',
  applied_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_budget_task_plan ON ad_budget_task(tenant_id, plan_id, created_at DESC);
CREATE INDEX ad_budget_task_list ON ad_budget_task(tenant_id, status, created_at DESC);

-- ---------------------------------------------------------------------------
-- ad_budget_task_line · 执行任务逐条结果
-- ---------------------------------------------------------------------------
-- 🔴 被护栏拒绝的那几条**照样留痕**（result='REJECTED' + 原因）：
-- 静默截断会让人以为「按预期工作」，而实际上调了另一个数。
CREATE TABLE ad_budget_task_line (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  task_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, task_id) REFERENCES ad_budget_task(tenant_id, id) ON DELETE CASCADE,

  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id) REFERENCES ad_campaign(tenant_id, id),
  campaign_name text NOT NULL DEFAULT '',
  -- 🔴 店铺编码：数据范围（data_scope）按店铺收敛时要用
  shop_code varchar(64) NOT NULL DEFAULT '',
  before_budget NUMERIC(19,4) CHECK (before_budget IS NULL OR before_budget >= 0),
  target_budget NUMERIC(19,4) NOT NULL CHECK (target_budget >= 0),
  applied_budget NUMERIC(19,4) CHECK (applied_budget IS NULL OR applied_budget >= 0),
  budget_delta NUMERIC(19,4) NOT NULL DEFAULT 0,
  -- 🔴 PENDING = 已生成任务但还没执行；被护栏拒绝的那几条是 REJECTED + 原因
  result varchar(16) NOT NULL CHECK (result IN ('PENDING','APPLIED','REJECTED','SKIPPED')),
  message text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, task_id, campaign_id)
);

CREATE INDEX ad_budget_task_line_task ON ad_budget_task_line(tenant_id, task_id);

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V140 / V141 / V143 / V146 / V273 / V275 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_budget_rule', 'ad_budget_rule_weight', 'ad_budget_rule_target',
    'ad_budget_pool', 'ad_budget_plan', 'ad_budget_plan_line', 'ad_budget_plan_skipped',
    'ad_budget_rebalance', 'ad_budget_task', 'ad_budget_task_line' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（蓝图的输入「用户数据范围」）
-- ---------------------------------------------------------------------------
-- 与 V273 `ad_campaign` 的 data_scope 同构：广告活动挂在店铺下，
-- 运营不能通过预算分配结果看到无权店铺的投放情况。
--
-- 🔴 只有**逐活动的那三张表**（方案明细 / 未纳入清单 / 任务明细）带 shop_code，
--   所以 data_scope 落在它们上面；规则、预算池、方案头、任务是**租户级配置事实**，
--   不按店铺收敛（一个租户一套预算规则，不存在「跨店铺的私有规则」）。
CREATE POLICY data_scope ON ad_budget_plan_line AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_budget_plan_line.tenant_id AND s.code=ad_budget_plan_line.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_budget_plan_line.tenant_id AND s.code=ad_budget_plan_line.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_budget_plan_skipped AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_budget_plan_skipped.tenant_id AND s.code=ad_budget_plan_skipped.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_budget_plan_skipped.tenant_id AND s.code=ad_budget_plan_skipped.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_budget_task_line AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_budget_task_line.tenant_id AND s.code=ad_budget_task_line.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_budget_task_line.tenant_id AND s.code=ad_budget_task_line.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V93/V273/V275 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  ad_budget_rule, ad_budget_rule_weight, ad_budget_rule_target,
  ad_budget_pool, ad_budget_plan, ad_budget_plan_line, ad_budget_plan_skipped,
  ad_budget_rebalance, ad_budget_task, ad_budget_task_line TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V273 / V275 同口径）。
REVOKE ALL ON
  ad_budget_rule, ad_budget_rule_weight, ad_budget_rule_target,
  ad_budget_pool, ad_budget_plan, ad_budget_plan_line, ad_budget_plan_skipped,
  ad_budget_rebalance, ad_budget_task, ad_budget_task_line FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：预算分配属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.2.2.2 自动规则同族，因此 identity 服务零改动，跨车道回归面 = 0。
