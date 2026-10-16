-- ===========================================================================
-- 10.1.2.2 渠道比价（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.1.2.2 渠道比价
--   - 职责：在满足时效与限制的渠道中按成本/时效/成功率多目标排序，支持按店铺预设优先级
--           自动选择。
--   - 输入：比价条件；渠道评分（历史成功率、时效达成率、投诉率）。
--   - 输出：排序后的推荐渠道 + 评分明细。
--   - 关系：上游 = 10.1.2.1、10.6 时效分析；下游 = 9.3.2.1 分仓、18 自动化规则。
--           **只看价格选渠道是常见错误**：便宜但高丢件的渠道会推高售后与差评成本。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 函数 / 策略**，零 ALTER 任何既有表：
--   * V136 `logistics_channel_config`（10.1.1.2 渠道配置，已交付）—— 同库复合 FK，
--     带 tenant_id ⇒ 跨租户挂载在物理上不可能
--   * V139 的时效与限制表**一张都不引用**：比价用的时效来自上游 10.1.2.1 试算单行
--     （freight_quote_line.transit_min/max_days，即「这一票预计几天到」），而 V139 描述的是
--     「渠道对某国某重量段的承诺时效」—— 两者语义不同，混用会让比价按一个没算出来的
--     量去筛渠道。V139 已交付，本车道仍刻意不引它：引用它等于把「承诺时效」当成
--     「本次报价时效」，而后者才是比价该用的口径。
--   * V140 `freight_quote`（10.1.2.1 运费计算器，已交付）—— 比价的成本/时效/可承运性
--     全部来自它，本模块**不重算钱**、不复制费率规则
--   * 10.6.2.1 时效分析（**未交付**）是渠道评分的**理想生产者**。它还没上线，所以评分
--     的来源列 `score_source` 显式区分 MANUAL_ENTERED（人工登记）与将来的
--     TRANSIT_ANALYTICS —— 评分从哪来必须可追溯，不能出现「不知道这个 97% 是谁给的」
--   * 不碰 trade / warehouse 的任何一张表（下游 9.3.2.1 分仓、18 自动化规则在别的车道）
--   * 复用 identity 已注册的 channel.operation.* 权限码，identity 零改动
--
-- 表名一律走 `channel_comparison_` / `channel_rating_` / `channel_shop_priority_`
-- 命名空间，与 V136 的 `logistics_channel_*`、V140 的 `freight_*` 并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这张表存在的理由：渠道评分必须是**数据**，不是代码里的常量数组
-- ---------------------------------------------------------------------------
-- 蓝图输入写的是「渠道评分（历史成功率、时效达成率、投诉率）」。把它做成硬编码的
-- `[0.97, 0.91, 0.02]` 数组意味着：换一家承运商要发一次版、评分的来源无从追溯、
-- 而「这家渠道最近变差了」这件事在系统里根本不存在。
-- 所以成功率 / 时效达成率 / 投诉率三个比率**由计数列 GENERATED ALWAYS 派生**：
--   * 计数（已发运 / 已妥投 / 按时妥投 / 投诉）是**人可核对的原始事实**
--   * 比率是**派生量**，服务层写不进去 ⇒ 绕过服务层写 SQL 也编不出一个好看的比率
--   * 窗口 window_from / window_to + observed_at + source_ref 一起存 ⇒ 「这 97% 是
--     哪段时间、多少单、谁给的」四问都有答案，10.6.2.1 上线后可以直接换成它的口径
--
-- ---------------------------------------------------------------------------
-- 二、🔴 蓝图加粗句「只看价格选渠道是常见错误」的**物理落点**
-- ---------------------------------------------------------------------------
-- 这句话如果只写在文档里，实现出来的一定是「按运费升序取第一个」。本迁移让它落在
-- **三个独立的闸**上，任何一个都足以拦住「便宜但高丢件」：
--
--   闸 (1) `max_complaint_rate_pct`（投诉率红线）
--          评分行自带一条红线（租户可维护）。比价时超过红线 ⇒ risk_flagged=true，
--          **不许自动选中**，无论它综合分多高、运费多便宜。
--   闸 (2) `min_sample_threshold`（最小样本量）
--          只发过 3 单、3 单全到 ⇒ 成功率 100%。这个数字不该和发过 3000 单的 98% 同等
--          参与自动选择。样本不足 ⇒ sample_sufficient=false ⇒ 不许自动选中。
--   闸 (3) `max_rating_age_days`（评分新鲜度）
--          三个月前的评分不代表今天这条渠道。过期 ⇒ rating_stale=true ⇒ 不自动选中。
--
-- 🔴 三个闸都**只挡自动选择，不挡评分**：被标记的渠道照样出现在对照表里、照样给出
--   成本分 / 时效分 / 可靠性分与综合分 —— 界面必须让人看见「它为什么便宜却没被选」，
--   而不是把它从表里删掉让人以为它不存在。
--
-- ---------------------------------------------------------------------------
-- 三、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 金额（运费快照）一律 **NUMERIC(19,4)**（与 V140 同口径），
-- 数量（重量）一律 **NUMERIC(19,6)**，
-- 比率（成功率 / 时效达成率 / 投诉率 / 权重）一律 **NUMERIC(9,4)~(9,6)**，
-- 得分（0~100 的三维分与综合分）一律 **NUMERIC(9,4)**。
-- **没有一列是 float/double** —— 得分参与自动选择，它就是决策依据，浮点不可接受。
--
-- ---------------------------------------------------------------------------
-- 四、四张表
-- ---------------------------------------------------------------------------
--   channel_rating_score     渠道评分（原始计数 + 派生比率 + 红线 + 样本门槛 + 来源）
--   channel_shop_priority    店铺预设优先级（自动选择的顺序预设 + 每条的最低可接受分）
--   channel_comparison       比价单头（比价条件 + 权重 + 推荐结果）
--   channel_comparison_line  比价单行（每渠道的评分明细 + 排名 / 落选原因 / 三个闸）

-- ---------------------------------------------------------------------------
-- channel_rating_score · 渠道评分
-- ---------------------------------------------------------------------------
CREATE TABLE channel_rating_score (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 10.1.1.2：被评分的渠道（同库复合 FK，带 tenant_id）===
  channel_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 按**渠道 × 国家**交叉存，不存「这条渠道总体成功率」。
  -- 蓝图 10.6.2.1 的加粗句是「时效数据要按渠道和国家交叉分析，总体平均会掩盖某些渠道
  -- 在某些国家的系统性延误」——比价要筛的恰好是「某渠道寄到某国」，用总体均值等于
  -- 拿被其它国家拉高的平均数去筛这个国家，筛出来的结论必然是错的。
  country_code varchar(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),

  -- === 原始计数（人可核对的事实；比率由这三个数派生，不接受任何直接写入）===
  dispatched integer NOT NULL CHECK (dispatched > 0),
  delivered integer NOT NULL CHECK (delivered >= 0),
  on_time integer NOT NULL CHECK (on_time >= 0),
  complaint integer NOT NULL CHECK (complaint >= 0),
  CHECK (delivered <= dispatched),
  CHECK (on_time <= delivered),
  CHECK (complaint <= dispatched),

  -- 🔴 三个比率是 GENERATED 列：服务层**写不进去**，绕过服务层写 SQL 也写不出
  --   一个与计数对不上的比率。分母为 0 时给 NULL 而不是 0（0 会被读成「一单没发」）。
  delivery_success_rate numeric(9,4) GENERATED ALWAYS AS (
    CASE WHEN dispatched > 0
         THEN round(delivered::numeric / dispatched::numeric, 4)
         ELSE NULL END
  ) STORED,
  on_time_rate numeric(9,4) GENERATED ALWAYS AS (
    CASE WHEN delivered > 0
         THEN round(on_time::numeric / delivered::numeric, 4)
         ELSE NULL END
  ) STORED,
  complaint_rate numeric(9,4) GENERATED ALWAYS AS (
    CASE WHEN dispatched > 0
         THEN round(complaint::numeric / dispatched::numeric, 4)
         ELSE NULL END
  ) STORED,
  -- 三列都落在 [0,1]：生成表达式已保证，这里兜住将来改表达式时的越界
  CHECK (delivery_success_rate IS NULL OR delivery_success_rate BETWEEN 0 AND 1),
  CHECK (on_time_rate IS NULL OR on_time_rate BETWEEN 0 AND 1),
  CHECK (complaint_rate IS NULL OR complaint_rate BETWEEN 0 AND 1),

  -- === 统计窗口与观测时刻：不存这三样就答不出「这 97% 是哪段时间的」===
  window_from timestamptz NOT NULL,
  window_to timestamptz NOT NULL,
  CHECK (window_to > window_from),
  observed_at timestamptz NOT NULL,

  -- 🔴 评分来源必须可追溯（文件头第一节）。
  -- MANUAL_ENTERED     人工登记（10.6.2.1 未交付，这是当前唯一能拿到数据的来源）
  -- TRACKING_ANALYSIS  10.6.2.1 时效分析回灌（未交付，枚举先留位）
  -- HANDOVER_RECEIPT   10.3.2.1 交运回执汇总
  -- CARRIER_REPORT     承运商提供的考核报告
  score_source text NOT NULL
    CHECK (score_source IN ('MANUAL_ENTERED','TRACKING_ANALYSIS','HANDOVER_RECEIPT','CARRIER_REPORT')),
  -- 指向来源凭据（报表 id / 批次号 / 外部报告编号）。人工登记时允许空串，但不能是 NULL
  -- 与「没登记」在语义上混同 —— 一律是 text NOT NULL。
  source_ref text NOT NULL DEFAULT '',

  -- 🔴 闸 (1)：投诉率红线（百分比 0~100）。超过它 risk_flagged，不许自动选中。
  max_complaint_rate_pct numeric(9,4) NOT NULL DEFAULT 5.0000
    CHECK (max_complaint_rate_pct >= 0 AND max_complaint_rate_pct <= 100),
  -- 🔴 闸 (2)：最小样本量。低于它 sample_sufficient=false，不许自动选中。
  min_sample_threshold integer NOT NULL DEFAULT 30 CHECK (min_sample_threshold >= 1),

  -- ACTIVE 当前生效（同一渠道 + 国家只允许一条）· SUPERSEDED 已被新一轮统计取代
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUPERSEDED')),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 🔴 部分唯一索引：同一渠道 + 同一国家只能有一条 ACTIVE。
-- 两条都生效时「比价该用哪一条」无法回答，而比价单里已经存下了当时用的那一组比率。
CREATE UNIQUE INDEX channel_rating_score_one_active
  ON channel_rating_score(tenant_id, channel_id, country_code) WHERE status = 'ACTIVE';

-- 比价时按 (目的国, 候选渠道集合) 一次性取回评分，避免逐渠道查（N+1）
CREATE INDEX channel_rating_score_by_country
  ON channel_rating_score(tenant_id, country_code, status);

-- ---------------------------------------------------------------------------
-- channel_shop_priority · 店铺预设优先级
-- ---------------------------------------------------------------------------
-- 蓝图职责后半句：「支持按店铺预设优先级自动选择」。这条「预设」必须是租户可维护的
-- 数据：运营在某个店铺上把某条渠道排到第一位，换人接手时这套顺序还在。
CREATE TABLE channel_shop_priority (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  shop_code varchar(64) NOT NULL CHECK (shop_code ~ '^[A-Z0-9_-]{1,64}$'),
  channel_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 1 = 最优先。生效中的同一店铺必须**连续覆盖 1..N**（见 trigger c）：
  -- 中间跳过一位（1 和 3 之间没有 2）说明有人删过一行，而「第 2 位是谁」从此无人知晓。
  priority_no smallint NOT NULL CHECK (priority_no > 0),

  -- 这条渠道是否**允许被自动选中**。列进预设但禁自动 = 兜底候选（有人工兜底通道时，
  -- 系统不会把它当首选推给下游）。
  auto_select_allowed boolean NOT NULL DEFAULT true,

  -- 🔴 该渠道的最低可接受综合分（0~100）。低于它即便排在预设第一位也不自动选中 ——
  --   「预设」是运营的偏好，不是对差渠道的豁免。
  min_composite_score numeric(9,4) NOT NULL DEFAULT 0.0000
    CHECK (min_composite_score >= 0 AND min_composite_score <= 100),

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RETIRED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 同店铺同渠道只能有一条预设（重复会让「第几位」有两个答案）
  UNIQUE (tenant_id, shop_code, channel_id)
);

-- 比价时按店铺取整条优先级链
CREATE INDEX channel_shop_priority_by_shop
  ON channel_shop_priority(tenant_id, shop_code, priority_no) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- channel_comparison · 比价单头
-- ---------------------------------------------------------------------------
CREATE TABLE channel_comparison (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 沿用 10.1.2.1 的口径纪律：比价的成本是**试算**来的，不是承运商账单。
  --   DB 层只允许 RULE_ESTIMATE 一个取值 ⇒ 比价结果在物理上不可能被当成实际成本。
  cost_basis text NOT NULL DEFAULT 'RULE_ESTIMATE'
    CHECK (cost_basis = 'RULE_ESTIMATE'),

  -- === 上游 10.1.2.1 的试算单：比价的成本/时效/可承运性全部来自它，本模块不重算钱。
  --   可空：允许直接给包裹参数走一次无副作用试算（POST /compare 不落库时也会用到）。
  --   非空时记录是哪一张，「当时按哪张对照表比的价格」必须答得出来。
  --   ON DELETE RESTRICT：比价单是给下游 9.3.2.1 / 18 自动化规则引用的决策依据，
  --   删掉它会让已发生的推荐无法复盘。
  source_quote_id uuid,
  FOREIGN KEY (tenant_id, source_quote_id)
    REFERENCES freight_quote(tenant_id, id) ON DELETE RESTRICT,

  -- === 比价条件回显 ===
  dest_country varchar(2) NOT NULL CHECK (dest_country ~ '^[A-Z]{2}$'),
  shop_code varchar(64) NOT NULL DEFAULT '',
  weight_kg numeric(19,6) NOT NULL CHECK (weight_kg >= 0),
  pieces integer NOT NULL CHECK (pieces > 0),
  declared_value numeric(19,4) CHECK (declared_value IS NULL OR declared_value >= 0),
  declared_currency varchar(3) CHECK (declared_currency IS NULL OR declared_currency ~ '^[A-Z]{3}$'),
  category_codes text NOT NULL DEFAULT '',

  -- 🔴 比价时刻：评分新鲜度（闸 3）按它判定，且**写进库里**。
  --   不存这个时刻，事后没人能复算「当时用的是不是一份过期的评分」。
  compared_at timestamptz NOT NULL,

  -- === 多目标权重（成本 / 时效 / 可靠性），归一化后落库 ===
  cost_weight numeric(9,6) NOT NULL CHECK (cost_weight >= 0),
  transit_weight numeric(9,6) NOT NULL CHECK (transit_weight >= 0),
  reliability_weight numeric(9,6) NOT NULL CHECK (reliability_weight >= 0),
  CHECK (cost_weight + transit_weight + reliability_weight BETWEEN 0.999999 AND 1.000001),

  -- === 可靠性维度的三个子权重（成功率 / 时效达成率 / 投诉率倒数），同样归一化落库。
  --   为什么是数据不是代码常量：「成功率与投诉率哪个更重要」是**每个租户自己的取舍**，
  --   硬编码成 0.4/0.4/0.2 就等于替所有租户做了这个决定。落库后可复算。
  success_weight numeric(9,6) NOT NULL CHECK (success_weight >= 0),
  on_time_weight numeric(9,6) NOT NULL CHECK (on_time_weight >= 0),
  complaint_weight numeric(9,6) NOT NULL CHECK (complaint_weight >= 0),
  CHECK (success_weight + on_time_weight + complaint_weight BETWEEN 0.999999 AND 1.000001),

  -- === 约束回显（比价条件的一部分，事后要能看出当时按什么筛的）===
  max_transit_days integer CHECK (max_transit_days IS NULL OR max_transit_days >= 0),
  max_rating_age_days integer NOT NULL DEFAULT 90 CHECK (max_rating_age_days >= 0),
  require_pass_only boolean NOT NULL DEFAULT false,
  apply_shop_priority boolean NOT NULL DEFAULT false,

  -- === 结果汇总 ===
  candidate_count integer NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
  ranked_count integer NOT NULL DEFAULT 0 CHECK (ranked_count >= 0),
  selected_count integer NOT NULL DEFAULT 0 CHECK (selected_count IN (0,1)),
  risk_flagged_count integer NOT NULL DEFAULT 0 CHECK (risk_flagged_count >= 0),
  insufficient_sample_count integer NOT NULL DEFAULT 0 CHECK (insufficient_sample_count >= 0),
  stale_rating_count integer NOT NULL DEFAULT 0 CHECK (stale_rating_count >= 0),

  currency varchar(3) NOT NULL DEFAULT ''
    CHECK (currency = '' OR currency ~ '^[A-Z]{3}$'),
  mixed_currency boolean NOT NULL DEFAULT false,
  cheapest_total numeric(19,4),
  dearest_total numeric(19,4),
  CHECK ((cheapest_total IS NULL) = (dearest_total IS NULL)),
  CHECK (cheapest_total IS NULL OR dearest_total IS NULL OR cheapest_total <= dearest_total),

  -- === 推荐结果 ===
  selected_channel_id uuid,
  FOREIGN KEY (tenant_id, selected_channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE RESTRICT,
  -- SCORE_RANKED             按综合分排名自动选第一条合格渠道
  -- SHOP_PRESET_PRIORITY     按店铺预设优先级选中的
  -- NO_ELIGIBLE_CHANNEL      一条都不合格（如实说，绝不静默挑一条）
  selection_basis text NOT NULL
    CHECK (selection_basis IN ('SCORE_RANKED','SHOP_PRESET_PRIORITY','NO_ELIGIBLE_CHANNEL')),
  -- 🔴 没选中时必须**逐条**说清为什么（下方各行的 exclusion_reason），而不是给一个
  --   看起来合理的兜底渠道。照抄 9.3.2.2 「无路径时逐条列出排除原因，绝不悄悄回落」。
  selection_note text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  CHECK ((selected_channel_id IS NULL) = (selection_basis = 'NO_ELIGIBLE_CHANNEL')),
  CHECK ((selected_channel_id IS NULL) = (selected_count = 0)),
  CHECK ((selected_channel_id IS NOT NULL) = (selected_count = 1)),
  -- 选了却没启用店铺预设优先级 ⇒ 只能是按综合分选的
  CHECK (selected_channel_id IS NULL OR apply_shop_priority
         OR selection_basis = 'SCORE_RANKED'),
  CHECK (category_codes = ''
         OR category_codes ~ '^[A-Z0-9_]{1,64}(,[A-Z0-9_]{1,64})*$')
);

-- 比价单列表：按比价时刻倒序
CREATE INDEX channel_comparison_by_time
  ON channel_comparison(tenant_id, compared_at DESC);

-- 「寄到德国的历史比价」/ 按目的国筛选
CREATE INDEX channel_comparison_by_country
  ON channel_comparison(tenant_id, dest_country, compared_at DESC);

-- 「这个店铺的自动选择历史」/ 按店铺筛选
CREATE INDEX channel_comparison_by_shop
  ON channel_comparison(tenant_id, shop_code, compared_at DESC);

-- ---------------------------------------------------------------------------
-- channel_comparison_line · 比价单行（评分明细）
-- ---------------------------------------------------------------------------
CREATE TABLE channel_comparison_line (
  tenant_id uuid NOT NULL,
  comparison_id uuid NOT NULL,
  channel_id uuid NOT NULL,

  -- === 渠道身份快照：比价单要能独立读懂（渠道改名后旧单据仍说得清是哪条渠道）===
  channel_code varchar(64) NOT NULL,
  channel_name varchar(200) NOT NULL,
  carrier_name varchar(200) NOT NULL,

  -- === 成本与时效快照（来自上游 10.1.2.1，本模块不重算）===
  eligibility text NOT NULL CHECK (eligibility IN ('PASS','REVIEW','BLOCK')),
  total_charge numeric(19,4) CHECK (total_charge IS NULL OR total_charge >= 0),
  currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  transit_min_days integer,
  transit_max_days integer,
  transit_basis_days integer CHECK (transit_basis_days IS NULL OR transit_basis_days >= 0),

  -- === 三维得分（0~100，越高越好；不可比时为 NULL 而不是 0 ——
  --     0 分与「没数据」在这一页是**完全相反**的含义）===
  cost_score numeric(9,4) CHECK (cost_score IS NULL OR (cost_score >= 0 AND cost_score <= 100)),
  transit_score numeric(9,4) CHECK (transit_score IS NULL OR (transit_score >= 0 AND transit_score <= 100)),
  reliability_score numeric(9,4)
    CHECK (reliability_score IS NULL OR (reliability_score >= 0 AND reliability_score <= 100)),
  -- 🔴 综合分由 trigger d 在**写入路径上**按单头权重重算（见文件头第五节），
  --   服务层传什么都不作数 —— 界面上「分项加起来」必须等于「综合分」。
  composite_score numeric(9,4)
    CHECK (composite_score IS NULL OR (composite_score >= 0 AND composite_score <= 100)),

  -- === 评分来源快照（这一行用的是哪份评分）===
  rating_id uuid,
  rating_window_from timestamptz,
  rating_window_to timestamptz,
  rating_observed_at timestamptz,
  rating_dispatched integer CHECK (rating_dispatched IS NULL OR rating_dispatched > 0),
  rating_delivered integer,
  rating_on_time integer,
  rating_complaint integer,
  rating_success_rate numeric(9,4),
  rating_on_time_rate numeric(9,4),
  rating_complaint_rate numeric(9,4),
  rating_source text
    CHECK (rating_source IS NULL
           OR rating_source IN ('MANUAL_ENTERED','TRACKING_ANALYSIS','HANDOVER_RECEIPT','CARRIER_REPORT')),

  -- === 三个闸（文件头第二节）：只挡自动选择，**不挡评分** ===
  risk_flagged boolean NOT NULL DEFAULT false,
  risk_note text NOT NULL DEFAULT '',
  sample_sufficient boolean NOT NULL DEFAULT false,
  sample_note text NOT NULL DEFAULT '',
  rating_stale boolean NOT NULL DEFAULT false,
  stale_note text NOT NULL DEFAULT '',
  -- 店铺预设优先级（可空 = 这个店铺没有给它预设）
  priority_no smallint CHECK (priority_no IS NULL OR priority_no > 0),
  auto_select_allowed boolean NOT NULL DEFAULT true,
  min_composite_score numeric(9,4)
    CHECK (min_composite_score IS NULL OR (min_composite_score >= 0 AND min_composite_score <= 100)),

  -- === 排名 / 落选 ===
  rank_basis text NOT NULL CHECK (rank_basis IN (
    'RANKED',
    'EXCLUDED_INELIGIBLE',
    'EXCLUDED_TRANSIT',
    'EXCLUDED_CURRENCY',
    'EXCLUDED_NO_PRICE',
    'EXCLUDED_NO_TRANSIT',
    'EXCLUDED_NO_SCORE')),
  -- rank_basis=RANKED ⇔ rank_no 有值：排上了名次就一定有编号，没排上就一定没有
  CHECK ((rank_basis = 'RANKED') = (rank_no IS NOT NULL)),
  rank_no integer CHECK (rank_no IS NULL OR rank_no > 0),
  -- 没排上名的必须逐条说明原因（空串只允许在 RANKED 行）
  CHECK (rank_basis = 'RANKED' OR exclusion_reason <> ''),
  exclusion_reason text NOT NULL DEFAULT '',

  selected boolean NOT NULL DEFAULT false,
  -- 评分明细（算式 + 逐条理由）：界面上要能读出「这 87.4 分是怎么来的」
  score_breakdown text NOT NULL DEFAULT '',

  PRIMARY KEY (tenant_id, comparison_id, channel_id),
  FOREIGN KEY (tenant_id, comparison_id)
    REFERENCES channel_comparison(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE RESTRICT
);

-- 单据取行
CREATE INDEX channel_comparison_line_by_channel
  ON channel_comparison_line(tenant_id, channel_id);

-- 「这条渠道被哪些比价单排过第几」
CREATE INDEX channel_comparison_line_by_rank
  ON channel_comparison_line(tenant_id, comparison_id, rank_no);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------

-- (a) 评分的国家必须落在渠道的适用国家里（防「配了但永远用不上」的死配置）。
--     与 V140 trigger (d) 同一道闸、同一套理由。
CREATE OR REPLACE FUNCTION guard_rating_score_country_in_scope() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM logistics_channel_scope s
       WHERE s.tenant_id = NEW.tenant_id AND s.channel_id = NEW.channel_id
         AND s.scope_kind = 'COUNTRY' AND s.scope_value = NEW.country_code
  ) THEN
    RAISE EXCEPTION
      '国家 % 不在这条渠道的适用国家里（上游 10.1.1.2 logistics_channel_scope）—— 给它登记评分只会让人以为比价时会用到这条渠道',
      NEW.country_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_rating_score_country_in_scope_trg
  BEFORE INSERT OR UPDATE ON channel_rating_score
  FOR EACH ROW EXECUTE FUNCTION guard_rating_score_country_in_scope();

-- (b) 🔴 一条生效中的评分必须真的「算得出可靠性」。
--     已发运 0 单的评分三个比率全是 NULL，比价时这条渠道会以「没有评分」落选 ——
--     与其让它静悄悄地在比价表里消失，不如在写入时就拒掉。
CREATE OR REPLACE FUNCTION guard_rating_score_activate() RETURNS trigger AS $$
BEGIN
  IF NEW.status = 'ACTIVE' AND (TG_OP = 'INSERT' OR OLD.status <> 'ACTIVE') THEN
    IF NEW.dispatched <= 0 THEN
      RAISE EXCEPTION
        '生效中的评分必须至少有一单已发运样本 —— 0 单算不出成功率/时效达成率/投诉率，比价时这条渠道会以「没有评分」落选' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.on_time > NEW.delivered THEN
      RAISE EXCEPTION
        '按时妥投数 % 不能大于已妥投数 % —— 时效达成率的分母是已妥投单，不是已发运单',
        NEW.on_time, NEW.delivered USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.complaint > NEW.dispatched THEN
      RAISE EXCEPTION
        '投诉数 % 不能大于已发运数 %（投诉只可能发生在已发出的货上）',
        NEW.complaint, NEW.dispatched USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  -- 生效中的评分不允许改原始计数：要改就 SUPERSEDED 旧行、再写一行新的。
  -- 比价单里存了当时那一组比率，就地改计数会让那些单据再也复算不出来。
  IF TG_OP = 'UPDATE' AND OLD.status = 'ACTIVE' AND (
      NEW.dispatched <> OLD.dispatched OR NEW.delivered <> OLD.delivered
      OR NEW.on_time <> OLD.on_time OR NEW.complaint <> OLD.complaint
      OR NEW.window_from <> OLD.window_from OR NEW.window_to <> OLD.window_to) THEN
    RAISE EXCEPTION
      '生效中的评分不能改原始计数或统计窗口（已发出的比价单是按这组数算的，改了它们就再也复算不出来）；要改请先置为 SUPERSEDED 再登记一条新的' 
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_rating_score_activate_trg
  BEFORE INSERT OR UPDATE ON channel_rating_score
  FOR EACH ROW EXECUTE FUNCTION guard_rating_score_activate();

-- (c1) 生效中的预设**不允许改店铺或渠道**：改了就等于把一条渠道从 A 店铺的预设里
--      悄悄搬到 B 店铺，而 A 店铺的「第 2 位是谁」从此无人知晓。要改请先置 RETIRED
--      再登记一条新的。
--      钉死这一条，(c2) 的延迟触发器才可以只用**当前行**读状态（OLD 与 NEW 的
--      shop_code 必然相同），否则延迟触发器拿不到旧店铺编码，A 店铺的断号就检查不到。
CREATE OR REPLACE FUNCTION guard_shop_priority_identity() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'ACTIVE' AND (
      NEW.shop_code <> OLD.shop_code OR NEW.channel_id <> OLD.channel_id) THEN
    RAISE EXCEPTION
      '生效中的店铺预设不能改店铺编码或渠道（%/% → %/%）—— 那会把这一条从旧店铺的优先级链里悄悄搬走，而旧店铺的序号链会断；请先置为 RETIRED 再在目标店铺登记一条新的',
      OLD.shop_code, OLD.channel_id, NEW.shop_code, NEW.channel_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_shop_priority_identity_trg
  BEFORE UPDATE ON channel_shop_priority
  FOR EACH ROW EXECUTE FUNCTION guard_shop_priority_identity();

-- (c2) 🔴 生效中的店铺预设优先级必须**连续覆盖 1..N**。
--     跳过一位（只有 1 和 3）说明中间那位被删了或没登记，而「第 2 位是谁」从此没人知道，
--     自动选择会在一个说不清的顺序上进行。
--
--     🔴 刻意做成 **DEFERRABLE INITIALLY DEFERRED 的约束触发器**，不是行级 BEFORE：
--     连续性是**整个店铺的集合性质**，只有当这一批写入全部落定后才谈得上「连续」。
--     行级 BEFORE 会在多行语句里看到中间态（先插 3 再插 2、1 时，第一行就误判断号），
--     而延迟到提交时看到的是最终态。照抄 trade V85/V90 的同一解法。
CREATE OR REPLACE FUNCTION guard_shop_priority_contiguous() RETURNS trigger AS $$
DECLARE
  row_tenant uuid;
  row_shop varchar(64);
  lowest smallint;
  highest smallint;
  total integer;
  distinct_total integer;
BEGIN
  IF TG_OP = 'DELETE' THEN
    row_tenant := OLD.tenant_id;
    row_shop := OLD.shop_code;
  ELSE
    row_tenant := NEW.tenant_id;
    row_shop := NEW.shop_code;
  END IF;

  SELECT min(priority_no), max(priority_no), count(*), count(DISTINCT priority_no)
    INTO lowest, highest, total, distinct_total
    FROM channel_shop_priority
   WHERE tenant_id = row_tenant AND shop_code = row_shop AND status = 'ACTIVE';

  -- 该店铺已没有生效中的行（全部退役或被删）：没有顺序可言，也就没有断号
  IF total IS NULL OR total = 0 THEN
    RETURN NULL;
  END IF;

  IF total <> distinct_total THEN
    RAISE EXCEPTION
      '店铺 % 的生效优先级有重复序号（共 % 行、% 个不同序号）—— 同一位次有两条渠道时，「第几位选谁」没有答案',
      row_shop, total, distinct_total USING ERRCODE = 'check_violation';
  END IF;

  IF lowest <> 1 OR highest <> total THEN
    RAISE EXCEPTION
      '店铺 % 的生效优先级必须连续覆盖 1..%（当前最小 %、最大 %）—— 序号跳空说明中间那一位被删了，而「第 2 位是谁」从此无人知晓',
      row_shop, total, lowest, highest USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER guard_shop_priority_contiguous_trg
  AFTER INSERT OR UPDATE OR DELETE ON channel_shop_priority
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_shop_priority_contiguous();

-- (d) 🔴 综合分在**写入路径上**由单头的权重重算，服务层传什么都不作数。
--     界面上「成本分 × 权重 + 时效分 × 权重 + 可靠性分 × 权重」必须等于综合分；
--     差 0.01 的分项与合计在人工复核时就会被当成对账纠纷。
--     顺带把 rank_no / composite_score 与 rank_basis 的关系钉死：
--     没排上名的行**不可能**带着综合分（否则「排不上」与「没算出来」混成一种状态）。
CREATE OR REPLACE FUNCTION guard_comparison_line_score() RETURNS trigger AS $$
DECLARE
  cw numeric;
  tw numeric;
  rw numeric;
BEGIN
  SELECT cost_weight, transit_weight, reliability_weight
    INTO cw, tw, rw
    FROM channel_comparison
   WHERE tenant_id = NEW.tenant_id AND id = NEW.comparison_id;

  IF cw IS NULL THEN
    RAISE EXCEPTION '比价单不存在（tenant=% comparison=%）', NEW.tenant_id, NEW.comparison_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF NEW.rank_basis = 'RANKED'
     AND NEW.cost_score IS NOT NULL
     AND NEW.transit_score IS NOT NULL
     AND NEW.reliability_score IS NOT NULL THEN
    NEW.composite_score := round(cw * NEW.cost_score + tw * NEW.transit_score
                                 + rw * NEW.reliability_score, 4);
  ELSE
    NEW.composite_score := NULL;
    NEW.rank_no := NULL;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_comparison_line_score_trg
  BEFORE INSERT OR UPDATE ON channel_comparison_line
  FOR EACH ROW EXECUTE FUNCTION guard_comparison_line_score();

-- (e) 🔴 单头的推荐结果必须与行一致：选中恰好一条行，且行上的 channel_id 与单头一致。
--     两边各写各的就会出现「单头说选了 A、没有任何一行标记 selected」这种
--     下游 9.3.2.1 / 18 自动化规则读不懂的结果。
--
--     🔴 刻意做成 **DEFERRABLE INITIALLY DEFERRED 的约束触发器，且挂在明细表上**：
--     服务层先写单头、再逐行写明细，行级即时触发器在第一条明细插入时只能看到「还没写
--     的后面几条」，必然误报；而挂在单头上又会让「单头先于明细落库」这件事本身成为
--     非法。延迟到提交 + 挂在明细上，两个写入顺序的毛病一起消失，不变量检查的仍
--     是同一个：提交时单头与**全部**明细必须自洽。
CREATE OR REPLACE FUNCTION guard_comparison_selection_consistency() RETURNS trigger AS $$
DECLARE
  head_channel uuid;
  head_basis text;
  selected_rows integer;
  selected_channels integer;
BEGIN
  SELECT selected_channel_id, selection_basis INTO head_channel, head_basis
    FROM channel_comparison
   WHERE tenant_id = NEW.tenant_id AND id = NEW.comparison_id;

  -- 单头已被删除：级联删掉了明细，无需判定
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;

  SELECT count(*) INTO selected_rows
    FROM channel_comparison_line
   WHERE tenant_id = NEW.tenant_id AND comparison_id = NEW.comparison_id
     AND selected;

  IF head_basis = 'NO_ELIGIBLE_CHANNEL' THEN
    IF selected_rows <> 0 THEN
      RAISE EXCEPTION
        '比价单标记为「无可选渠道」，却存在 % 条 selected=true 的行 —— 推荐结果自相矛盾，下游无法判断该用哪条',
        selected_rows USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
  END IF;

  IF selected_rows <> 1 THEN
    RAISE EXCEPTION
      '比价单选中了渠道 %，明细里标记 selected 的行有 % 条（必须恰好 1 条）—— 推荐结果与评分明细必须一致',
      head_channel, selected_rows USING ERRCODE = 'check_violation';
  END IF;

  SELECT count(*) INTO selected_channels
    FROM channel_comparison_line
   WHERE tenant_id = NEW.tenant_id AND comparison_id = NEW.comparison_id
     AND selected AND channel_id = head_channel;

  IF selected_channels <> 1 THEN
    RAISE EXCEPTION
      '比价单单头选中渠道 %，但明细里被选中的是 % 条别的渠道 —— 选中的渠道与推荐的渠道必须同一条，否则下游 9.3.2.1 / 18 自动化规则拿到的推荐指向不明',
      head_channel, selected_rows - selected_channels USING ERRCODE = 'check_violation';
  END IF;

  -- 选中的行必须是**排上了名次**的行：一个被硬拦截或被判为高投诉的渠道不允许出现在推荐里
  IF NOT EXISTS (
      SELECT 1 FROM channel_comparison_line
       WHERE tenant_id = NEW.tenant_id AND comparison_id = NEW.comparison_id
         AND channel_id = head_channel AND rank_basis = 'RANKED') THEN
    RAISE EXCEPTION
      '比价单选中的渠道 % 未参与排名（rank_basis <> RANKED）—— 被硬拦截、超时效或被判高投诉的渠道不允许被推荐',
      head_channel USING ERRCODE = 'check_violation';
  END IF;

  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER guard_comparison_selection_consistency_trg
  AFTER INSERT OR UPDATE ON channel_comparison_line
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_comparison_selection_consistency();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V140 / V141 同一套写法。租户由
-- backend/libraries/tenancy 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头
-- 进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'channel_rating_score', 'channel_shop_priority',
    'channel_comparison', 'channel_comparison_line' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V93/V129/V134/V136/V140/V141 的条件建角色，保证迁移可重复执行）
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
  channel_rating_score, channel_shop_priority,
  channel_comparison, channel_comparison_line TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V136 / V140 同口径）。
REVOKE ALL ON
  channel_rating_score, channel_shop_priority,
  channel_comparison, channel_comparison_line FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：渠道比价属于「平台/承运商操作」，与 10.1.1.1 承运商、
-- 10.1.1.2 渠道配置、10.1.2.1 运费计算器同族，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
