-- ===========================================================================
-- 14.2.2.2 自动规则（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.2.2.2 自动规则
--   - 职责：自动化规则：按条件自动调整预算/竞价/启停（ACOS 超标降价、ROAS 达标加预算、
--           消耗完毕暂停、花费无转化停投），支持规则优先级、单次调整上限与总上限、试运行模式。
--   - 输入：规则定义；实时广告表现；上下限保护参数。
--   - 输出：规则命中记录 + 调整执行记录 + 效果回溯。
--   - 关系：上游 = 14.3 报表；下游 = 平台广告 API。**自动规则必须有上限与试运行**——
--           没有上限的自动调价一次异常就能烧掉大量预算。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，零 ALTER 任何既有表：
--   * V1 `platform`（全局平台码表）+ V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
--   * **不引用 14.1.1.1 活动列表的任何表**（同批并行车道，本车道看不到它的迁移）。
--     本模块的规则通过**外部引用**（平台 + 店铺 + 外部活动 id `platform_campaign_id`）
--     定位广告对象，广告表现数据由**本模块自己的 `ad_rule_metric` 表**承载，
--     通过一个显式的写入口（指标登记）落库 ⇒ 与 14.1.1.1 零耦合、互不依赖。
--   * **不引用 14.2.2.1 预算分配**（未开发模块）、**不实现** 14.2.1.1 竞价管理 /
--     14.1.3.1 关键词管理 / 14.1.1.1 活动列表的职责。
--   * 不碰 trade / listing 的表。
--   * 复用 identity 已注册的 channel.operation.* 权限码，identity 零改动。
--
-- 表名一律走 `ad_rule_` 命名空间，与 V146 `ad_type_*`、V136 `logistics_channel_*`、
-- V140 `freight_*` 并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这张表存在的理由：自动调价是**会自己花钱**的逻辑
-- ---------------------------------------------------------------------------
-- 「按条件自动调预算」听起来只是几个 if-else，但它在生产里会**自己动钱**。
-- 一条写错的规则（阈值写反、指标口径错、平台返回了异常值）可以在几分钟内
-- 把一个日预算 5000 的活动调成 50000，或者把出价调到 0 让活动彻底停投。
-- 所以本模块的表不是「存规则」这么简单，它必须把三件事**落到能被审阅、能被 DB 拒绝**的粒度：
--
--   1. **上限**（单次 + 总）不是应用层的 if，而是**列 + 执行路径上的强制校验**：
--      超限的调整**被拒绝并留痕**（ad_rule_execution.outcome='REJECTED'），
--      **不是被静默截断**。截断会让人以为「规则已按预期工作」，
--      而实际上调了另一个数 —— 这是自动调预算最危险的一种失败。
--   2. **试运行**是**一等的模式**（ad_rule.dry_run + ad_rule_run.run_mode），
--      试运行与真实执行的记录**必须可区分**（ad_rule_execution.outcome =
--      'DRY_RUN' / 'APPLIED'），否则事后没人说得清「上周那次调价到底执行了没有」。
--   3. **上下限保护**（预算/竞价不能低于 0、不能高于某值）同样落库并**在执行路径上生效**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 蓝图加粗句「自动规则必须有上限与试运行」的**物理落点**
-- ---------------------------------------------------------------------------
--   (1) ad_rule.single_adjust_cap_amount  单次调整的**金额**上限（必填，> 0）
--   (2) ad_rule.single_adjust_cap_percent 单次调整的**比例**上限（相对当前值，0 = 不启用）
--       —— 两个上限**取更严的那个**生效（min），而不是二选一
--   (3) ad_rule.total_cap_amount          窗口内累计调整的**金额**上限（必填，> 0）
--   (4) ad_rule.total_cap_count            窗口内累计调整的**次数**上限（必填，> 0）
--   (5) ad_rule.total_cap_window_hours     累计窗口长度（小时，必填，> 0）
--   (6) ad_rule.dry_run                    试运行开关（**默认 true**：新建规则一律先试运行）
--   (7) ad_rule.min_budget / max_budget / min_bid / max_bid  上下限保护参数
--
-- 上限是**必填且必须为正**：CHECK 约束让「一条没有上限的规则」在 DB 层就建不出来。
-- 这是本模块的生死线 —— 规则可以先不启用，但不能没有上限。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 优先级的语义：数字小 = 优先高；同目标多条命中只执行一条
-- ---------------------------------------------------------------------------
-- 同一时刻常常有多条规则命中同一个广告对象（例如「ACOS 超标降价」与
-- 「花费无转化停投」同时命中）。本模块的口径是：
--   * 目标键 = (平台, 店铺, 作用对象层级, 外部活动 id, 外部关键词 id)
--   * 同一目标键上**按 priority 升序取一条**（数字小的优先高；并列时按创建时间、再按 id）
--   * **落选的那些规则也要留痕**（ad_rule_hit.matched=true 但 selected=false，
--     带 selection_note 说明「为什么没选它」）
-- 不做「多条规则的调整叠加」：叠加会让上限判定失去意义（每条都以为自己只动了一点）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 命中 / 执行 / 回溯三样分开落库，**不合并成一张表**
-- ---------------------------------------------------------------------------
--   ad_rule_hit       命中记录：规则 + 条件 + **当时的指标快照**。
--                     每一次评估都留一行（命中与未命中都留），未命中时 hit_reason
--                     写清「哪条条件不满足 / 为什么不��比」，这是调规则时唯一的依据。
--   ad_rule_execution 调整执行记录：动作 + 幅度 + 前后值 + **逐项闸门判定结果**
--                     （单次上限 / 总上限 / 上下限各一行结论），被拒绝的调整也留痕。
--   ad_rule_effect    效果回溯：执行后指标变化，能看出这条规则**是否真的有效**。
--                     没有回溯就只有「规则命中过」这个事实，无法回答「它管用吗」。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 数据截至时间必须显式：区分「没有数据」与「没同步到」
-- ---------------------------------------------------------------------------
-- 上游 14.3 报表**未开发**，本模块的广告表现数据由本模块自己的表承载
-- （ad_rule_metric），通过显式的写入口登记。每一行都带：
--   * metrics_as_of  —— **数据截至时间**（必填）
--   * window_start / window_end —— 指标统计窗口
-- 没有数据（表里没有这一行）与有数据但没同步到（最新一行早于规则评估窗口）
-- 是**两件不同的事**：前者是「还没开始投」，后者是「同步断了」。
-- 界面必须分开显示，所以 metrics_as_of 是必填列而不是可选备注。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 预算 / 花费 / 销售额 / 出价 / 上限金额一律 **NUMERIC(19,4)**（与 V140 / V146 同口径）；
-- 曝光 / 点击 / 转化是**计数**，用 **NUMERIC(19,6)**（整数也存得下，将来出现比率不必换型）；
-- 比率（ACOS / ROAS）一律 **NUMERIC(19,6)**，且由 **GENERATED 列**从原始量算出。
-- **没有一列是 float/double** —— 这些数直接决定改多少预算，浮点不可接受。
--
-- ACOS = 花费 / 归因销售额；ROAS = 归因销售额 / 花费。
-- **分母为 0（或归因销售额为空）时给 NULL 而不是 0**：
-- 「压根算不出来」与「算出来是零」在自动调价里是相反的两件事 ——
-- 0 会被「ACOS > 0.35」判成不命中（看起来很安全），
-- 而 NULL 会被显式判成「不可比」，让人知道数据还缺一块。
--
-- ---------------------------------------------------------------------------
-- 七、八张表
-- ---------------------------------------------------------------------------
--   ad_rule            规则定义（含上限、上下限保护、优先级、试运行开关）
--   ad_rule_condition  规则条件（AND 语义：多条条件全部成立才算命中）
--   ad_rule_metric     广告表现数据（本模块自持；指标登记写入口的落点）
--   ad_rule_run        评估运行（一次评估 = 一行；试运行与真实执行在这里也分得开）
--   ad_rule_hit        规则命中记录（规则 + 条件 + 当时指标快照 + 是否被选中）
--   ad_rule_execution  调整执行记录（动作 + 幅度 + 前后值 + 逐项闸门结论）
--   ad_rule_dispatch   待下发动作（**下游 = 平台广告 API，本模块不实现真实下发**）
--   ad_rule_effect     效果回溯（执行前后的指标变化与结论）

-- ---------------------------------------------------------------------------
-- ad_rule · 规则定义
-- ---------------------------------------------------------------------------
CREATE TABLE ad_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  rule_code varchar(64) NOT NULL CHECK (btrim(rule_code) <> ''),
  rule_name varchar(128) NOT NULL CHECK (btrim(rule_name) <> ''),

  -- === 作用对象：平台 + 店铺 + 外部引用 ===
  platform_id uuid NOT NULL REFERENCES platform(id),
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 🔴 作用对象层级。规则按**外部 id** 作用，不假设 14.1.1.1 的表存在。
  target_type varchar(16) NOT NULL CHECK (target_type IN ('CAMPAIGN','KEYWORD')),
  external_campaign_id varchar(128) NOT NULL DEFAULT '',
  external_keyword_id varchar(128) NOT NULL DEFAULT '',

  -- === 动作 ===
  -- 蓝图职责句：「按条件自动调整**预算/竞价/启停**」—— 三个维度各两个方向，六个动作全列：
  --   ACOS 超标降价    → DECREASE_BID
  --   ROAS 达标加预算  → INCREASE_BUDGET
  --   消耗完毕暂停    → PAUSE
  --   花费无转化停投  → PAUSE
  --   其余三个方向（INCREASE_BID / DECREASE_BUDGET / RESUME）是同一套机制的另外两面，
  --   不做「只支持蓝图里写到的四个例子」这种半成品。
  action_type varchar(24) NOT NULL
    CHECK (action_type IN ('DECREASE_BID','INCREASE_BID','DECREASE_BUDGET','INCREASE_BUDGET','PAUSE','RESUME')),
  adjustment_mode varchar(16) NOT NULL CHECK (adjustment_mode IN ('PERCENT','ABSOLUTE')),
  adjustment_value NUMERIC(19,6) NOT NULL CHECK (adjustment_value >= 0),

  -- 🔴🔴 单次调整上限：两个上限**取更严的**生效。缺一个都不许建规则。
  single_adjust_cap_amount NUMERIC(19,4) NOT NULL CHECK (single_adjust_cap_amount > 0),
  single_adjust_cap_percent NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (single_adjust_cap_percent >= 0),

  -- 🔴🔴 总上限：窗口内累计的金额上限 + 次数上限。
  total_cap_amount NUMERIC(19,4) NOT NULL CHECK (total_cap_amount > 0),
  total_cap_count int NOT NULL CHECK (total_cap_count > 0),
  total_cap_window_hours int NOT NULL CHECK (total_cap_window_hours > 0),

  -- 🔴 上下限保护参数
  min_budget NUMERIC(19,4) CHECK (min_budget IS NULL OR min_budget >= 0),
  max_budget NUMERIC(19,4) CHECK (max_budget IS NULL OR max_budget >= 0),
  min_bid NUMERIC(19,4) CHECK (min_bid IS NULL OR min_bid >= 0),
  max_bid NUMERIC(19,4) CHECK (max_bid IS NULL OR max_bid >= 0),

  -- 🔴 优先级：数字小 = 优先高
  priority int NOT NULL DEFAULT 100,
  -- 🔴 试运行：默认 true —— 新建规则一律先试运行，要真执行必须显式关掉。
  dry_run boolean NOT NULL DEFAULT true,
  evaluation_window_hours int NOT NULL DEFAULT 24 CHECK (evaluation_window_hours > 0),

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','ARCHIVED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code),

  -- 🔴 上下限必须自洽：上限低于下限 ⇒ 这条规则一执行就会被拒，白建
  CHECK (max_budget IS NULL OR min_budget IS NULL OR max_budget >= min_budget),
  CHECK (max_bid IS NULL OR min_bid IS NULL OR max_bid >= min_bid),

  -- 🔴 作用对象必须真的指到某个外部对象（空 id = 「该店铺该层级全部」也是允许的，
  --    但那必须靠 external_campaign_id 明确给出活动范围）
  CHECK (btrim(external_campaign_id) <> '' OR btrim(external_keyword_id) <> ''),
  -- 🔴 关键词级规则必须点名关键词
  CHECK (target_type <> 'KEYWORD' OR btrim(external_keyword_id) <> ''),

  -- 🔴 启停动作没有幅度；改价改预算必须有幅度
  CHECK (
    (action_type IN ('PAUSE','RESUME') AND adjustment_value = 0)
    OR (action_type IN ('DECREASE_BID','INCREASE_BID','DECREASE_BUDGET','INCREASE_BUDGET')
        AND adjustment_value > 0)
  )
);

CREATE INDEX ad_rule_list ON ad_rule(tenant_id, status, priority, created_at);
CREATE INDEX ad_rule_target ON ad_rule(tenant_id, platform_id, shop_code, target_type);

-- ---------------------------------------------------------------------------
-- ad_rule_condition · 规则条件
-- ---------------------------------------------------------------------------
-- 一条规则可以有多个条件，语义是 **AND**：全部成立才算命中。
-- 这样「花费无转化停投」= SPEND > 50 且 CONVERSIONS <= 0，
-- 「消耗完毕暂停」= SPEND >= 1000，都是同一套结构，不用在代码里开特例。
--
-- 🔴 阈值分两列而不是一列，因为**单位不同**：比率用 threshold_value（NUMERIC(19,6)），
-- 金额用 threshold_amount（NUMERIC(19,4)）。混在一列里就会出现「0.35 到底是 35% 还是 0.35 元」
-- 这种无法分辨的记录 —— 而这正是自动调预算最容易出的错。
CREATE TABLE ad_rule_condition (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_rule(tenant_id, id) ON DELETE CASCADE,

  metric varchar(24) NOT NULL
    CHECK (metric IN ('ACOS','ROAS','SPEND','REVENUE','CONVERSIONS','CLICKS','IMPRESSIONS')),
  operator varchar(8) NOT NULL CHECK (operator IN ('GT','GTE','LT','LTE','EQ')),

  -- 比率与计数阈值（ACOS / ROAS / CONVERSIONS / CLICKS / IMPRESSIONS）
  threshold_value NUMERIC(19,6) CHECK (threshold_value IS NULL OR threshold_value >= 0),
  -- 金额阈值（SPEND / REVENUE）
  threshold_amount NUMERIC(19,4) CHECK (threshold_amount IS NULL OR threshold_amount >= 0),

  note text NOT NULL DEFAULT '',
  position int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_id, metric),

  -- 🔴 阈值列必须与指标口径**严格对应**：比率/计数指标只准用 threshold_value，
  --    金额指标只准用 threshold_amount。留空或两个都给 ⇒ 写入被拒。
  CHECK (
    (metric IN ('ACOS','ROAS','CONVERSIONS','CLICKS','IMPRESSIONS')
      AND threshold_value IS NOT NULL AND threshold_amount IS NULL)
    OR
    (metric IN ('SPEND','REVENUE')
      AND threshold_amount IS NOT NULL AND threshold_value IS NULL)
  )
);

CREATE INDEX ad_rule_condition_rule ON ad_rule_condition(tenant_id, rule_id, position);

-- ---------------------------------------------------------------------------
-- ad_rule_metric · 广告表现数据（本模块自持）
-- ---------------------------------------------------------------------------
-- 🔴 上游 14.3 报表**未开发**，所以广告表现数据落在**本模块自己的表**里，
--   通过一个显式的指标登记写入口落库。这与 14.1.1.1 活动列表**零耦合**：
--   本表只用外部引用（platform + shop + external_campaign_id / external_keyword_id）定位对象，
--   不引用 14.1.1.1 的任何表、也不建跨车道 FK —— 合并时不会互相破坏。
CREATE TABLE ad_rule_metric (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  platform_id uuid NOT NULL REFERENCES platform(id),
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  metric_scope varchar(16) NOT NULL CHECK (metric_scope IN ('CAMPAIGN','KEYWORD')),
  external_campaign_id varchar(128) NOT NULL CHECK (btrim(external_campaign_id) <> ''),
  external_keyword_id varchar(128) NOT NULL DEFAULT '',
  currency char(3) NOT NULL DEFAULT '',

  -- 🔴 当前投放状态与当前预算 / 出价：规则要改的就是这三个值。
  --    没有它们，「调预算」就无从计算前后值。
  delivery_status varchar(16) NOT NULL DEFAULT 'ENABLED'
    CHECK (delivery_status IN ('ENABLED','PAUSED','ENDED')),
  current_budget NUMERIC(19,4) CHECK (current_budget IS NULL OR current_budget >= 0),
  current_bid NUMERIC(19,4) CHECK (current_bid IS NULL OR current_bid >= 0),

  -- === 效果量 ===
  impressions NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (impressions >= 0),
  clicks NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (clicks >= 0),
  conversions NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (conversions >= 0),
  spend NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (spend >= 0),
  attributed_revenue NUMERIC(19,4) CHECK (attributed_revenue IS NULL OR attributed_revenue >= 0),

  -- 🔴 可比比率是 GENERATED 列：服务层写不进去，绕过服务层也编不出与原始量对不上的比率。
  --    **分母为 0（或归因销售额为空 / 为 0）时一律给 NULL（不可比），绝不�� 0**：
  --      * ACOS 的分母是归因销售额 ⇒ 销售额为 0 时 ACOS 不可比；
  --      * ROAS 的分母是花费 ⇒ 花费为 0 时 ROAS 不可比；
  --      * 销售额为 0 时 **ROAS 也给 NULL** —— 数值上它是 0，但那说明这一轮根本没有
  --        可比的投放，用 0 冒充会让「暂停中 / 没同步到」看起来像「效率极高」。
  --    「压根算不出来」与「算出来是零」在自动调价里是相反的两件事。
  acos NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN attributed_revenue IS NOT NULL AND attributed_revenue > 0
         THEN round(spend / attributed_revenue, 6) ELSE NULL END
  ) STORED,
  roas NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN attributed_revenue IS NOT NULL AND attributed_revenue > 0 AND spend > 0
         THEN round(attributed_revenue / spend, 6) ELSE NULL END
  ) STORED,

  -- 🔴 数据截至时间（必填）+ 统计窗口
  metrics_as_of timestamptz NOT NULL,
  window_start timestamptz NOT NULL,
  window_end timestamptz NOT NULL,
  CHECK (window_end > window_start),

  source_ref text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一对象 + 同一统计窗口只留一行（重复登记走 upsert，不会堆出两条互相矛盾的指标）
  UNIQUE(tenant_id, platform_id, shop_code, metric_scope, external_campaign_id, external_keyword_id, window_start)
);

CREATE INDEX ad_rule_metric_target
  ON ad_rule_metric(tenant_id, platform_id, shop_code, metric_scope, external_campaign_id, external_keyword_id);
CREATE INDEX ad_rule_metric_fresh ON ad_rule_metric(tenant_id, metrics_as_of DESC);
-- 取「每个对象的最新一行」用这条部分索引
CREATE INDEX ad_rule_metric_latest
  ON ad_rule_metric(tenant_id, platform_id, shop_code, metric_scope, external_campaign_id, external_keyword_id, metrics_as_of DESC);

-- ---------------------------------------------------------------------------
-- ad_rule_run · 评估运行
-- ---------------------------------------------------------------------------
-- 一次评估 = 一行。run_mode 记**这次运行的口径**（DRY_RUN / APPLY），
-- 但**规则自己的 dry_run 仍然优先**：规则开了试运行，这次运行就是试运行。
CREATE TABLE ad_rule_run (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  run_mode varchar(16) NOT NULL CHECK (run_mode IN ('DRY_RUN','APPLY')),
  trigger_source varchar(16) NOT NULL DEFAULT 'MANUAL' CHECK (trigger_source IN ('MANUAL')),
  status varchar(16) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),

  evaluated_rules int NOT NULL DEFAULT 0,
  hit_count int NOT NULL DEFAULT 0,
  selected_count int NOT NULL DEFAULT 0,
  execution_count int NOT NULL DEFAULT 0,
  applied_count int NOT NULL DEFAULT 0,
  dry_run_count int NOT NULL DEFAULT 0,
  rejected_count int NOT NULL DEFAULT 0,

  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_rule_run_list ON ad_rule_run(tenant_id, started_at DESC, id);

-- ---------------------------------------------------------------------------
-- ad_rule_hit · 规则命中记录
-- ---------------------------------------------------------------------------
-- 每一次「规则 × 指标」评估都留一行 —— **未命中也留**。
-- 未命中时 hit_reason 写清「哪条条件不满足」或「为什么不���比」，
-- 这是运营调规则时唯一的依据（界面上「没生效」必须能立刻看出差在哪）。
CREATE TABLE ad_rule_hit (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  run_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  metric_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, run_id) REFERENCES ad_rule_run(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_rule(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, metric_id) REFERENCES ad_rule_metric(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 命中与「被选中执行」是两件事：同目标多条命中时，落选的那几条也要留痕。
  matched boolean NOT NULL,
  selected boolean NOT NULL DEFAULT false,
  selection_note text NOT NULL DEFAULT '',
  hit_reason text NOT NULL DEFAULT '',

  priority int NOT NULL,

  -- 🔴 当时的指标快照：规则命中过之后指标会变，事后回看必须知道「当时是多少」
  spend_snapshot NUMERIC(19,4) NOT NULL DEFAULT 0,
  revenue_snapshot NUMERIC(19,4),
  conversions_snapshot NUMERIC(19,6) NOT NULL DEFAULT 0,
  clicks_snapshot NUMERIC(19,6) NOT NULL DEFAULT 0,
  impressions_snapshot NUMERIC(19,6) NOT NULL DEFAULT 0,
  acos_snapshot NUMERIC(19,6),
  roas_snapshot NUMERIC(19,6),

  metrics_as_of timestamptz NOT NULL,
  evaluated_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_rule_hit_rule ON ad_rule_hit(tenant_id, rule_id, evaluated_at DESC);
CREATE INDEX ad_rule_hit_run ON ad_rule_hit(tenant_id, run_id);
CREATE INDEX ad_rule_hit_matched ON ad_rule_hit(tenant_id, matched, selected, evaluated_at DESC);

-- ---------------------------------------------------------------------------
-- ad_rule_execution · 调整执行记录
-- ---------------------------------------------------------------------------
-- 🔴 一次调整尝试 = 一行，**被拒绝的也留**。
--   outcome = 'DRY_RUN'  试运行：算出了会怎么调，但没有真的调
--   outcome = 'APPLIED'  真实执行：闸门全过，产生了待下发动作
--   outcome = 'REJECTED' 被闸门拒绝：超上限或越界，**不截断、不执行**
-- 逐项闸门结论（cap_check / bound_check）分别存列，界面上要能一眼看出
-- 「被哪一道闸拦下来的」。
CREATE TABLE ad_rule_execution (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  run_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  hit_id uuid NOT NULL,
  metric_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, run_id) REFERENCES ad_rule_run(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_rule(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, hit_id) REFERENCES ad_rule_hit(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, metric_id) REFERENCES ad_rule_metric(tenant_id, id) ON DELETE CASCADE,

  action_type varchar(24) NOT NULL
    CHECK (action_type IN ('DECREASE_BID','INCREASE_BID','DECREASE_BUDGET','INCREASE_BUDGET','PAUSE','RESUME')),
  adjustment_mode varchar(16) NOT NULL CHECK (adjustment_mode IN ('PERCENT','ABSOLUTE')),
  requested_value NUMERIC(19,6) NOT NULL DEFAULT 0,

  outcome varchar(16) NOT NULL CHECK (outcome IN ('DRY_RUN','APPLIED','REJECTED')),
  reject_reason varchar(32) NOT NULL DEFAULT ''
    CHECK (reject_reason IN ('','SINGLE_CAP_EXCEEDED','TOTAL_CAP_EXCEEDED','BOUND_VIOLATION',
                            'DUPLICATE_PENDING_DISPATCH','NOT_APPLICABLE')),
  reject_detail text NOT NULL DEFAULT '',

  -- 🔴 前后值与实际幅度（试运行里是「本来会变成多少」，真实执行里是「变成了多少」）
  before_value NUMERIC(19,4),
  after_value NUMERIC(19,4),
  applied_delta NUMERIC(19,4),
  -- 🔴 启停动作没有数值前后值，改用状态前后值（ENABLED → PAUSED）
  before_status varchar(16) CHECK (before_status IS NULL OR before_status IN ('ENABLED','PAUSED','ENDED')),
  after_status varchar(16) CHECK (after_status IS NULL OR after_status IN ('ENABLED','PAUSED','ENDED')),

  -- 🔴 逐项闸门结论
  cap_check varchar(24) NOT NULL CHECK (cap_check IN ('PASSED','SINGLE_CAP_EXCEEDED','TOTAL_CAP_EXCEEDED')),
  bound_check varchar(24) NOT NULL
    CHECK (bound_check IN ('PASSED','BOUND_VIOLATION','NOT_APPLICABLE')),
  single_cap_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  single_cap_percent NUMERIC(19,6) NOT NULL DEFAULT 0,
  total_cap_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  total_cap_count int NOT NULL DEFAULT 0,
  total_cap_window_hours int NOT NULL DEFAULT 0,
  -- 🔴 窗口内**已用**额度（判定总上限时的实际用量），要能在记录上复算
  window_used_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  window_used_count int NOT NULL DEFAULT 0,

  priority_selection_note text NOT NULL DEFAULT '',
  executed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_rule_execution_rule ON ad_rule_execution(tenant_id, rule_id, executed_at DESC);
CREATE INDEX ad_rule_execution_run ON ad_rule_execution(tenant_id, run_id);
CREATE INDEX ad_rule_execution_outcome ON ad_rule_execution(tenant_id, outcome, executed_at DESC);
-- 总上限统计用：只看真实执行过的、且落在窗口内的
CREATE INDEX ad_rule_execution_window
  ON ad_rule_execution(tenant_id, rule_id, executed_at DESC) WHERE outcome = 'APPLIED';

-- ---------------------------------------------------------------------------
-- ad_rule_dispatch · 待下发动作（下游 = 平台广告 API）
-- ---------------------------------------------------------------------------
-- 🔴 本模块**不实现**平台侧真实下发（那是平台广告适配器的事，尚未交付）。
--   本表只把「要下发什么」**明确落库**：动作类型 + 完整载荷 + 目标外部引用 +
--   下发状态。真实下发通道接上以后，从这张表取 PENDING 的行发出去即可。
--   界面必须显示「待下发」而不是「已生效」—— 这两件事不是一回事。
CREATE TABLE ad_rule_dispatch (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  execution_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, execution_id) REFERENCES ad_rule_execution(tenant_id, id) ON DELETE CASCADE,

  platform_id uuid NOT NULL REFERENCES platform(id),
  shop_code varchar(64) NOT NULL,
  external_campaign_id varchar(128) NOT NULL DEFAULT '',
  external_keyword_id varchar(128) NOT NULL DEFAULT '',

  action_type varchar(24) NOT NULL
    CHECK (action_type IN ('DECREASE_BID','INCREASE_BID','DECREASE_BUDGET','INCREASE_BUDGET','PAUSE','RESUME')),
  -- 🔴 下发载荷：平台适配器要什么就放什么，**不猜平台的具体字段名**
  action_payload jsonb NOT NULL DEFAULT '{}'::jsonb,

  dispatch_status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (dispatch_status IN ('PENDING','DISPATCHED','FAILED','CANCELLED')),
  dispatched_at timestamptz,
  dispatch_ref text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个执行最多一条下发动作
  UNIQUE(tenant_id, execution_id)
);

CREATE INDEX ad_rule_dispatch_pending ON ad_rule_dispatch(tenant_id, dispatch_status, created_at);
CREATE INDEX ad_rule_dispatch_target
  ON ad_rule_dispatch(tenant_id, platform_id, shop_code, external_campaign_id, external_keyword_id);

-- ---------------------------------------------------------------------------
-- ad_rule_effect · 效果回溯
-- ---------------------------------------------------------------------------
-- 「这条规则到底管不管用」只有回溯能回答：
-- 执行前后的 ACOS / ROAS / 花费 / 销售额摆在一起，并给出结论。
CREATE TABLE ad_rule_effect (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  execution_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  metric_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, execution_id) REFERENCES ad_rule_execution(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_rule(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, metric_id) REFERENCES ad_rule_metric(tenant_id, id) ON DELETE CASCADE,

  before_acos NUMERIC(19,6),
  after_acos NUMERIC(19,6),
  acos_delta NUMERIC(19,6),
  before_roas NUMERIC(19,6),
  after_roas NUMERIC(19,6),
  roas_delta NUMERIC(19,6),
  before_spend NUMERIC(19,4) NOT NULL DEFAULT 0,
  after_spend NUMERIC(19,4) NOT NULL DEFAULT 0,
  before_revenue NUMERIC(19,4),
  after_revenue NUMERIC(19,4),
  revenue_delta NUMERIC(19,4),

  -- 🔴 结论：INSUFFICIENT_DATA 是**合法且必须被看见**的答案
  --    （执行后还没有更新的指标数据），不能拿 0 冒充「没变化」。
  verdict varchar(24) NOT NULL
    CHECK (verdict IN ('IMPROVED','WORSENED','UNCHANGED','INSUFFICIENT_DATA')),
  verdict_note text NOT NULL DEFAULT '',

  observed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个执行最多一条回溯
  UNIQUE(tenant_id, execution_id)
);

CREATE INDEX ad_rule_effect_rule ON ad_rule_effect(tenant_id, rule_id, observed_at DESC);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------

-- (a) 🔴 规则的平台必须与店铺的平台一致。
--     规则作用在 Amazon 的店铺上却挂着 TikTok 的平台，指标会永远匹配不上，
--     而界面上看不出任何异常 —— 这类「静默不生效」是最难查的一类问题。
CREATE OR REPLACE FUNCTION guard_ad_rule_shop_platform() RETURNS trigger AS $$
DECLARE
  shop_platform uuid;
BEGIN
  SELECT platform_id INTO shop_platform
    FROM shop
   WHERE tenant_id = NEW.tenant_id AND code = NEW.shop_code;

  IF shop_platform IS NULL THEN
    RAISE EXCEPTION '店铺不存在（tenant=% shop=%）', NEW.tenant_id, NEW.shop_code
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF shop_platform <> NEW.platform_id THEN
    RAISE EXCEPTION
      '规则/指标的平台与店铺的平台不一致（platform=% shop_platform=%）—— 指标永远匹配不上，规则会静默不生效',
      NEW.platform_id, shop_platform USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_ad_rule_shop_platform_trg
  BEFORE INSERT OR UPDATE ON ad_rule
  FOR EACH ROW EXECUTE FUNCTION guard_ad_rule_shop_platform();

CREATE TRIGGER guard_ad_rule_metric_shop_platform_trg
  BEFORE INSERT OR UPDATE ON ad_rule_metric
  FOR EACH ROW EXECUTE FUNCTION guard_ad_rule_shop_platform();

-- (b) 🔴 启用的规则必须至少有一个条件。
--     没有条件的规则永远不会命中，留着只会让人以为它在生效。
--     用**延迟约束触发器**（提交时判定）：服务层先写规则再写条件，
--     两步在同一个事务里，提交时条件已经在了 ⇒ 不误伤。
CREATE OR REPLACE FUNCTION guard_ad_rule_has_condition() RETURNS trigger AS $$
DECLARE
  n integer;
BEGIN
  IF NEW.status <> 'ACTIVE' THEN
    RETURN NEW;
  END IF;

  SELECT count(*) INTO n
    FROM ad_rule_condition c
   WHERE c.tenant_id = NEW.tenant_id AND c.rule_id = NEW.id;

  IF n = 0 THEN
    RAISE EXCEPTION
      '启用的规则必须至少有一个条件（rule=%）—— 没有条件的规则永远不会命中，留着只会让人以为它在生效',
      NEW.id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER guard_ad_rule_has_condition_trg
  AFTER INSERT OR UPDATE ON ad_rule
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_ad_rule_has_condition();

-- (c) 🔴 指标的口径必须自洽：关键词级指标必须点名关键词；
--     活动级指标不许带关键词 id（带了说明登记串了层级，规则会匹配到错的对象）。
CREATE OR REPLACE FUNCTION guard_ad_rule_metric_scope() RETURNS trigger AS $$
BEGIN
  IF NEW.metric_scope = 'KEYWORD' AND btrim(NEW.external_keyword_id) = '' THEN
    RAISE EXCEPTION '关键词级指标必须给出外部关键词 id（campaign=%）', NEW.external_campaign_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.metric_scope = 'CAMPAIGN' AND btrim(NEW.external_keyword_id) <> '' THEN
    RAISE EXCEPTION '活动级指标不得带外部关键词 id（keyword=%）—— 层级串了会让规则作用到错的对象',
      NEW.external_keyword_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_ad_rule_metric_scope_trg
  BEFORE INSERT OR UPDATE ON ad_rule_metric
  FOR EACH ROW EXECUTE FUNCTION guard_ad_rule_metric_scope();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V140 / V141 / V143 / V146 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_rule', 'ad_rule_condition', 'ad_rule_metric', 'ad_rule_run',
    'ad_rule_hit', 'ad_rule_execution', 'ad_rule_dispatch', 'ad_rule_effect' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V93/V129/V134/V136/V140/V141/V143/V146 的条件建角色，
-- 保证迁移可重复执行）
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
  ad_rule, ad_rule_condition, ad_rule_metric, ad_rule_run,
  ad_rule_hit, ad_rule_execution, ad_rule_dispatch, ad_rule_effect TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V140 / V146 同口径）。
REVOKE ALL ON
  ad_rule, ad_rule_condition, ad_rule_metric, ad_rule_run,
  ad_rule_hit, ad_rule_execution, ad_rule_dispatch, ad_rule_effect FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：自动规则属于「平台操作」语义，与 14.1.2.2 广告类型差异、
-- 10.1.1.2 渠道配置、2.1.2.1 平台枚举映射同族，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
