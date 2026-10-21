-- ===========================================================================
-- 14.3.2.2 效率诊断（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.3.2.2 效率诊断
--   - 职责：诊断投放效率问题：高花费零转化、ACOS 长期超标、预算受限、竞价过低曝光不足、
--           创意衰退，给出可执行建议与一键调整。
--   - 输入：14.3 报表指标；诊断规则。
--   - 输出：诊断清单 + 建议动作。
--   - 关系：上游 = 14.3.1；下游 = 14.2.1.1、14.1.3.1。
--           **诊断要给动作而不是只报警**，否则用户看完还是要自己想怎么办。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，零 ALTER 任何既有表：
--   * V273 `ad_campaign` + `ad_campaign_daily_metric`（**已交付的上游 14.1.1.1**）——
--     本模块的诊断指标**全部**从这两张已交付的事实表算，**不复述、不另造**一份指标表。
--   * V1 `shop`（复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能）。
--   * 14.3.1.1 活动报表**在本批并行开发**（迁移 V279，不在本车道）⇒ 本模块**不引用**它的任何表；
--     蓝图写的「上游 = 14.3.1」是**业务上下游**，不是代码依赖。
--   * 14.2.1.1 竞价管理**未交付** ⇒ 竞价类动作在 `ad_diagnosis_action` 上显式标
--     `action_mode='UNAVAILABLE'` + `owner_module`，**绝不**在本模块里直接写平台。
--   * 14.1.1.2 活动详情（创意子资源）**本批并行开发** ⇒ 「创意衰退」规则的
--     `data_source_ready` 为 false，该规则**不产出任何诊断结论**。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这五张表存在的理由：诊断结论必须是「可复算的事实」，不是「一段结论」
-- ---------------------------------------------------------------------------
-- 蓝图的输出是「诊断清单 + 建议动作」。但如果诊断结论只以一段文字存在，
-- 那么：谁也答不出「上周说这条活动 ACOS 超标，为什么这周没报了」，
-- 也答不出「这条建议动作到底执行了没有」。
-- 于是诊断的每一条结论都必须带**当时的观察量快照**（Σspend / Σsales / 曝光 / 订单 /
-- 天数 / 预算利用率 / 曝光相对倍数），任何人都能拿它复算。
--
-- 五张表：
--
--   ad_diagnosis_rule      诊断规则（**阈值可配**）：规则类型 + 观察窗口 + 各项阈值 + 建议动作
--   ad_diagnosis_run       诊断运行（跑了一次、扫了多少活动、产出多少结论、数据截至 X）
--   ad_diagnosis_run_rule  运行里**每一条规则**的评估结论（评估了 / 跳过了 + 为什么）
--   ad_diagnosis           诊断结论（活动 × 规则）：观察量快照 + 判定依据 + 建议
--   ad_diagnosis_action    建议动作 + **一键调整的执行结果**（含被护栏拒绝的）
--
-- 🔴 `ad_diagnosis_run_rule` 的存在理由是「**没产出结论也要留痕**」：
--   「创意衰退规则没跑」与「创意衰退规则跑了但没命中」是两件完全不同的事，
--   渲染成同一种空态就答不出「为什么这条规则一直没报」。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 诊断规则 = 代码里的判定口径 + 库里的阈值
-- ---------------------------------------------------------------------------
-- 职责句点名了五种问题：���花费零转化 / ACOS 长期超标 / 预算受限 / 竞价过低曝光不足 / 创意衰退。
-- 这五种**判定口径**是本模块的词汇表，写死在 `rule_type` 的 CHECK 里（照抄 14.2.2.2
-- 把动作类型写成 CHECK 的做法）；而**阈值是租户可配的输入**（蓝图的输入写着「诊断规则」），
-- 落在 `ad_diagnosis_rule` 上，租户可以按自己的毛利结构改 ACOS 目标线。
--
-- 🔴 阈值是**钱**，所以一律 `NUMERIC(19,4)` / `NUMERIC(19,6)` + `BigDecimal`，
-- **没有一列 float/double**。「花多少算高花费」「ACOS 超到多少算超标」都是钱。
--
-- 🔴 比率的 null 语义沿用 14.1.1.1 的 `AdRatio` 约定：
--   **null = 不可比（分母为 0）**，0 = 可比但真的是零，两者含义相反。
--   销售额为 0 时 ACOS **不可比**，绝不拿 0 冒充「效率极高」。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 一键调整的护栏：上限 / 试运行 / 幂等 / 审计
-- ---------------------------------------------------------------------------
-- 14 章关键约束：「AI/规则自动调整必须有上限保护」。本模块的调整**不自己另写一套
-- 绕过护栏的写路径**，而是走 **14.1.1.1 已交付的写路径**
-- （`ad_campaign` 变更 + `ad_campaign_operation_log` 审计 + 单次/总额/单活动预算上限）。
-- 本表只负责：
--   * 记录**建议动作**（含建议幅度与「本来会变成多少」）；
--   * 记录**执行结论**：APPLIED / REJECTED / SKIPPED / DRY_RUN / UNAVAILABLE /
--     NOT_APPLIED —— **被护栏拒绝的也要留痕**，静默截断会让人以为按预期工作了。
--   * 🔴 **幂等**：已执行过的动作再次提交 ⇒ SKIPPED，不会把同一笔调整做两遍。
--   * 🔴 **试运行**：规则自带 `dry_run`（默认 true），与本次请求的 `dry_run`
--     **取更严的那个** ⇒ 开着试运行的规则不能被一次「真执行」推成真的调整。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 「创意衰退」与「竞价」两处不许假装
-- ---------------------------------------------------------------------------
-- ① 创意衰退：已交付的上游里**没有创意数据**（14.1.1.2 创意子资源是同批并行车道，
--    此刻不存在）⇒ 对应规则的 `data_source_ready=false`，
--    引擎**不产出任何诊断结论**，运行记录里写明「数据源未就绪」。
--    **不许**用随机数或假曲线假装有创意数据。
-- ② 竞价调整：下游 **14.2.1.1 竞价管理未交付** ⇒ 对应动作 `available=false` +
--    `action_mode='UNAVAILABLE'` + `owner_module='14.2.1.1 竞价管理'` + 影响说明。
--    **不许**跳过 14.2.1.1 直接写平台。
--
-- ---------------------------------------------------------------------------
-- 五、多租户隔离与数据范围
-- ---------------------------------------------------------------------------
-- 五张表 tenant_id NOT NULL + ENABLE/FORCE RLS isolation（照抄 V273 / V275 的写法），
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 另加 data_scope（RESTRICTIVE）策略（与 V273 同构）：诊断结论随**活动 → 店铺**收敛，
-- 不允许绕过店铺直接枚举投放效率结论。
--
-- ===========================================================================
-- 1. ad_diagnosis_rule · 诊断规则（阈值可配）
-- ===========================================================================
CREATE TABLE ad_diagnosis_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 规则编码（租户内唯一）：人可读、可引用
  rule_code varchar(64) NOT NULL CHECK (btrim(rule_code) <> ''),
  rule_name varchar(200) NOT NULL DEFAULT '',

  -- 🔴 判定口径 = 本模块的词汇表（职责句点名的五种问题）
  rule_type varchar(32) NOT NULL CHECK (rule_type IN (
    'HIGH_SPEND_NO_CONVERSION',  -- 高花费零转化
    'ACOS_OVER_TARGET',         -- ACOS 长期超标
    'BUDGET_CAPPED',            -- 预算受限
    'LOW_DELIVERY',             -- 竞价过低曝光不足
    'CREATIVE_DECAY'            -- 创意衰退（🔴 数据源未就绪 ⇒ 不产出结论）
  )),

  -- 严重度
  severity varchar(16) NOT NULL CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW')),

  enabled boolean NOT NULL DEFAULT true,

  -- 🔴 观察窗口（天）：诊断看最近多少天的数据
  lookback_days int NOT NULL DEFAULT 14 CHECK (lookback_days > 0 AND lookback_days <= 365),
  -- 🔴 最少需要几天的数据才下结论：数据不足 ⇒ **不下结论**（而不是硬判）
  min_metric_days int NOT NULL DEFAULT 3
    CHECK (min_metric_days > 0 AND min_metric_days <= lookback_days),

  -- 🔴 金额阈值（钱）：高花费零转化的「高花费」门槛
  threshold_amount NUMERIC(19,4) CHECK (threshold_amount IS NULL OR threshold_amount >= 0),
  -- 🔴 比率阈值：ACOS 目标线（ACOS_OVER_TARGET 用）
  threshold_ratio NUMERIC(19,6) CHECK (threshold_ratio IS NULL OR threshold_ratio >= 0),
  -- 🔴 「超标天数」下限：窗口内至少几天超标才算「长期」（ACOS_OVER_TARGET 用）
  min_over_target_days int CHECK (min_over_target_days IS NULL OR min_over_target_days > 0),
  -- 🔴 预算利用率下限：日花费 / 日预算 ≥ 此值 ⇒ 判定「预算受限」（BUDGET_CAPPED 用）
  budget_utilization_ratio NUMERIC(19,6)
    CHECK (budget_utilization_ratio IS NULL OR budget_utilization_ratio > 0),
  -- 🔴 曝光相对下限：日均曝光 / 同平台在投活动中位数 ≥ 此值 ⇒ 不判「曝光不足」
  --   （LOW_DELIVERY 用）
  delivery_ratio NUMERIC(19,6) CHECK (delivery_ratio IS NULL OR delivery_ratio > 0),

  -- 🔴 建议动作（主）
  action_type varchar(32) NOT NULL CHECK (action_type IN (
    'PAUSE_CAMPAIGN',   -- 暂停活动（本模块可写）
    'INCREASE_BUDGET',  -- 提高预算（本模块可写）
    'DECREASE_BUDGET',  -- 降低预算（本模块可写）
    'INCREASE_BID',     -- 提高竞价（🔴 14.2.1.1 未交付 ⇒ 不可用）
    'OPTIMIZE_KEYWORDS',-- 关键词层优化（14.1.3.1 已交付 ⇒ 去那边处理）
    'NONE'              -- 不给动作
  )),
  action_percent NUMERIC(19,6) CHECK (action_percent IS NULL OR action_percent >= 0),
  action_amount NUMERIC(19,4) CHECK (action_amount IS NULL OR action_amount >= 0),

  -- 🔴 次要建议动作（可空）：LOW_DELIVERY 的主动作是「提高竞价」（不可用），
  --   次动作「关键词层优化」把运营导向 14.1.3.1 关键词管理（已交付）
  secondary_action_type varchar(32) CHECK (secondary_action_type IS NULL OR secondary_action_type IN (
    'PAUSE_CAMPAIGN','INCREASE_BUDGET','DECREASE_BUDGET','INCREASE_BID','OPTIMIZE_KEYWORDS')),
  secondary_action_percent NUMERIC(19,6)
    CHECK (secondary_action_percent IS NULL OR secondary_action_percent >= 0),
  secondary_action_amount NUMERIC(19,4)
    CHECK (secondary_action_amount IS NULL OR secondary_action_amount >= 0),

  -- 🔴 数据源是否就绪：创意衰退规则为 false ⇒ 引擎**不产出结论**，只在运行记录里说明
  data_source_ready boolean NOT NULL DEFAULT true,

  -- 🔴 试运行开关（默认 true）：规则级试运行，与本次请求的 dry_run **取更严的那个**
  dry_run boolean NOT NULL DEFAULT true,

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code)
);

CREATE INDEX ad_diagnosis_rule_list ON ad_diagnosis_rule(tenant_id, enabled, rule_type, id);
CREATE INDEX ad_diagnosis_rule_code ON ad_diagnosis_rule(tenant_id, rule_code);

-- ===========================================================================
-- 2. ad_diagnosis_run · 诊断运行
-- ===========================================================================
CREATE TABLE ad_diagnosis_run (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 观察窗口（天）与实际统计到的日期区间
  lookback_days int NOT NULL CHECK (lookback_days > 0 AND lookback_days <= 365),
  window_from date NOT NULL,
  window_to date NOT NULL,
  CHECK (window_to >= window_from),

  -- 规则口径：本次评估了几条、其中几条启用、几条因数据源未就绪被跳过
  rule_count int NOT NULL DEFAULT 0 CHECK (rule_count >= 0),
  enabled_rule_count int NOT NULL DEFAULT 0 CHECK (enabled_rule_count >= 0),
  skipped_rule_count int NOT NULL DEFAULT 0 CHECK (skipped_rule_count >= 0),

  -- 扫描到的活动数 / 产出诊断结论数
  scanned_campaign_count int NOT NULL DEFAULT 0 CHECK (scanned_campaign_count >= 0),
  diagnosis_count int NOT NULL DEFAULT 0 CHECK (diagnosis_count >= 0),

  -- 🔴 数据截至时刻：本次诊断用的是截至什么时候的数据（来自 ad_campaign 的水位）
  data_as_of timestamptz,

  -- SUCCESS 跑完了 / FAILED 跑挂了（message 必填）
  status varchar(16) NOT NULL CHECK (status IN ('SUCCESS','FAILED')),
  message text NOT NULL DEFAULT '',

  started_at timestamptz NOT NULL,
  finished_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK (status <> 'FAILED' OR btrim(message) <> '')
);

CREATE INDEX ad_diagnosis_run_recent ON ad_diagnosis_run(tenant_id, created_at DESC);

-- ===========================================================================
-- 3. ad_diagnosis_run_rule · 运行里每条规则的评估结论
-- ===========================================================================
-- 🔴 「没产出结论也要留痕」：被跳过的规则（数据源未就绪）与评估过但没命中的规则
--   是两件不同的事，渲染成同一种空态就答不出「为什么这条规则一直没报」。
CREATE TABLE ad_diagnosis_run_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  run_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, run_id) REFERENCES ad_diagnosis_run(tenant_id, id) ON DELETE CASCADE,
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_diagnosis_rule(tenant_id, id) ON DELETE RESTRICT,

  rule_code varchar(64) NOT NULL,
  rule_type varchar(32) NOT NULL,
  severity varchar(16) NOT NULL,

  -- 🔴 是否真的评估了：false = 被跳过（数据源未就绪 / 规则停用）
  evaluated boolean NOT NULL,
  -- 🔴 跳过原因（evaluated=false 时必填）：写清「差在哪」
  skip_reason text NOT NULL DEFAULT '',
  -- 评估过但没命中时的说明（未命中的原因）
  no_match_reason text NOT NULL DEFAULT '',

  -- 命中的活动数
  matched_campaign_count int NOT NULL DEFAULT 0 CHECK (matched_campaign_count >= 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, run_id, rule_id),
  CHECK (evaluated OR btrim(skip_reason) <> '')
);

CREATE INDEX ad_diagnosis_run_rule_run ON ad_diagnosis_run_rule(tenant_id, run_id);

-- ===========================================================================
-- 4. ad_diagnosis · 诊断结论（活动 × 规则）
-- ===========================================================================
CREATE TABLE ad_diagnosis (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  run_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, run_id) REFERENCES ad_diagnosis_run(tenant_id, id) ON DELETE CASCADE,
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_diagnosis_rule(tenant_id, id) ON DELETE RESTRICT,
  -- 🔴 上游 14.1.1.1 活动（**同服务同库**，复合 FK 带 tenant_id ⇒ 跨租户挂载不可能）
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id) REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  rule_code varchar(64) NOT NULL,
  rule_type varchar(32) NOT NULL,
  severity varchar(16) NOT NULL CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW')),

  -- === 观察量快照（🔴 必须可复算：任何人都能拿它复算这条结论）===
  window_from date NOT NULL,
  window_to date NOT NULL,
  metric_days int NOT NULL CHECK (metric_days >= 0),
  impressions NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (impressions >= 0),
  clicks NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (clicks >= 0),
  orders NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (orders >= 0),
  spend NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (spend >= 0),
  sales NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (sales >= 0),
  -- 🔴 分母为 0 ⇒ NULL（不可比），不是 0
  acos NUMERIC(19,6) CHECK (acos IS NULL OR acos >= 0),
  roas NUMERIC(19,6) CHECK (roas IS NULL OR roas >= 0),
  ctr NUMERIC(19,6) CHECK (ctr IS NULL OR ctr >= 0),
  -- 当时的日预算（建议动作的基数）
  daily_budget NUMERIC(19,4) CHECK (daily_budget IS NULL OR daily_budget >= 0),

  -- === 派生观察量 ===
  -- 日均花费 / 日预算：≥ 1 ⇒ 预算花满（预算受限的判据）
  budget_utilization NUMERIC(19,6) CHECK (budget_utilization IS NULL OR budget_utilization >= 0),
  -- 日均曝光 / 同平台在投活动中位数：< 1 ⇒ 曝光不足
  delivery_ratio NUMERIC(19,6) CHECK (delivery_ratio IS NULL OR delivery_ratio >= 0),
  -- 窗口内超标天数（ACOS 长期超标的判据）
  over_target_days int NOT NULL DEFAULT 0 CHECK (over_target_days >= 0),

  -- === 结论 ===
  title varchar(200) NOT NULL,
  -- 🔴 判定依据：写清「为什么判定为这个问题」（数字 + 口径）
  evidence text NOT NULL,
  -- 🔴 可执行建议（人话）：诊断要给动作而不是只报警
  recommendation text NOT NULL,

  -- OPEN 待处理 / ACTIONED 已按建议动作处理 / DISMISSED 已忽略 / STALE 数据已过期
  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK (status IN ('OPEN','ACTIONED','DISMISSED','STALE')),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, run_id, rule_id, campaign_id)
);

CREATE INDEX ad_diagnosis_list ON ad_diagnosis(tenant_id, status, severity, rule_type, created_at DESC);
CREATE INDEX ad_diagnosis_campaign ON ad_diagnosis(tenant_id, campaign_id, created_at DESC);
CREATE INDEX ad_diagnosis_by_run ON ad_diagnosis(tenant_id, run_id);

-- ===========================================================================
-- 5. ad_diagnosis_action · 建议动作 + 一键调整的执行结果
-- ===========================================================================
CREATE TABLE ad_diagnosis_action (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  diagnosis_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, diagnosis_id) REFERENCES ad_diagnosis(tenant_id, id) ON DELETE CASCADE,
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id) REFERENCES ad_diagnosis_rule(tenant_id, id) ON DELETE RESTRICT,
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id) REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  action_type varchar(32) NOT NULL CHECK (action_type IN (
    'PAUSE_CAMPAIGN','INCREASE_BUDGET','DECREASE_BUDGET','INCREASE_BID','OPTIMIZE_KEYWORDS')),

  -- 🔴 动作模式：
  --   WRITE       本模块可写（预算 / 暂停 ⇒ 走 14.1.1.1 已交付的写路径与护栏）
  --   NAVIGATE    去**已交付**的模块处理（14.1.3.1 关键词管理）
  --   UNAVAILABLE 依赖**未交付**的模块（14.2.1.1 竞价管理）⇒ 显式不可用
  action_mode varchar(16) NOT NULL CHECK (action_mode IN ('WRITE','NAVIGATE','UNAVAILABLE')),
  available boolean NOT NULL,

  -- 🔴 未交付段：责任模块 + 不交付的影响（照抄 AdCampaignCapabilityDimension 的口径）
  owner_module varchar(64) NOT NULL DEFAULT '',
  impact text NOT NULL DEFAULT '',
  -- NAVIGATE 段：去哪个模块的哪个页面
  target_module varchar(64) NOT NULL DEFAULT '',
  target_path varchar(200) NOT NULL DEFAULT '',

  -- 建议幅度（比例 / 绝对额，二选一由规则决定）
  action_percent NUMERIC(19,6) CHECK (action_percent IS NULL OR action_percent >= 0),
  action_amount NUMERIC(19,4) CHECK (action_amount IS NULL OR action_amount >= 0),
  -- 调整前的日预算（执行时刻的当前值）
  before_value NUMERIC(19,4) CHECK (before_value IS NULL OR before_value >= 0),
  -- 🔴 「本来会变成多少」：试运行里也是这个值，被护栏拒绝时也保留（不静默截断）
  after_value NUMERIC(19,4) CHECK (after_value IS NULL OR after_value >= 0),

  -- 🔴 执行结论：六种分开，事后能说清「到底执行了没有」
  --   NOT_APPLIED 还没执行 / DRY_RUN 试运行（只算不调）
  --   APPLIED 已生效（本地） / REJECTED 被护栏拒绝 / SKIPPED 跳过（已执行过 / 已是目标状态）
  --   UNAVAILABLE 动作不可用（依赖未交付模块）
  outcome varchar(16) NOT NULL DEFAULT 'NOT_APPLIED'
    CHECK (outcome IN ('NOT_APPLIED','DRY_RUN','APPLIED','REJECTED','SKIPPED','UNAVAILABLE')),

  -- 结果说明（被拒时给出原因）
  message text NOT NULL DEFAULT '',
  -- 🔴 护栏结论（引用被哪一道闸拦下）
  guardrail_note text NOT NULL DEFAULT '',

  -- 🔴 试运行标记（本次执行口径）
  dry_run boolean NOT NULL DEFAULT true,
  -- 谁发起的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid,
  applied_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 一个诊断结论下同一种动作只留一条（幂等：重复诊断不产生重复动作）
  UNIQUE(tenant_id, diagnosis_id, action_type),

  -- 🔴 三种模式的自洽性：不可用 ⇒ 必须写清责任模块与影响；
  --   可导航 ⇒ 必须写清去哪个模块的哪个页面。
  CHECK (action_mode <> 'UNAVAILABLE' OR (NOT available AND btrim(owner_module) <> '')),
  CHECK (action_mode <> 'NAVIGATE' OR (available AND btrim(target_module) <> '' AND btrim(target_path) <> '')),
  CHECK (action_mode <> 'WRITE' OR available),
  -- 不可用的动作不许有「已生效」的结论
  CHECK (available OR outcome IN ('NOT_APPLIED','UNAVAILABLE'))
);

CREATE INDEX ad_diagnosis_action_list ON ad_diagnosis_action(tenant_id, outcome, action_mode, created_at DESC);
CREATE INDEX ad_diagnosis_action_diagnosis ON ad_diagnosis_action(tenant_id, diagnosis_id);

-- ===========================================================================
-- 6. 触发器：规则的阈值必须与它的类型自洽
-- ===========================================================================
-- 🔴 「一条没有阈值的规则」永远判不出东西，留着只会让人以为它在生效。
--   这里用**延迟约束触发器**（DEFERRABLE INITIALLY DEFERRED）保证：
--   每种 rule_type 用到的阈值必须非空、不该用的阈值必须为空。
--   绕服务层直接写 SQL 同样被拒。
CREATE OR REPLACE FUNCTION guard_ad_diagnosis_rule_thresholds() RETURNS trigger AS $$
DECLARE
  v_type text := NEW.rule_type;
BEGIN
  -- 创意衰退：🔴 数据源未就绪 ⇒ 不允许带任何阈值（带了就说明有人在拿假数据凑）
  IF v_type = 'CREATIVE_DECAY' THEN
    IF NEW.data_source_ready THEN
      RAISE EXCEPTION '创意衰退的数据源尚未就绪（14.1.1.2 未交付），data_source_ready 只能是 false'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.threshold_amount IS NOT NULL OR NEW.threshold_ratio IS NOT NULL
       OR NEW.min_over_target_days IS NOT NULL OR NEW.budget_utilization_ratio IS NOT NULL
       OR NEW.delivery_ratio IS NOT NULL THEN
      RAISE EXCEPTION '创意衰退规则不得携带阈值（没有创意数据可比）'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.action_type <> 'NONE' THEN
      RAISE EXCEPTION '创意衰退规则不得给出动作（数据源未就绪 ⇒ 无从建议）'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF v_type = 'HIGH_SPEND_NO_CONVERSION' THEN
    IF NEW.threshold_amount IS NULL THEN
      RAISE EXCEPTION '高花费零转化规则必须给出「高花费」的金额门槛'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.threshold_ratio IS NOT NULL OR NEW.budget_utilization_ratio IS NOT NULL
       OR NEW.delivery_ratio IS NOT NULL OR NEW.min_over_target_days IS NOT NULL THEN
      RAISE EXCEPTION '高花费零转化规则只使用金额门槛，其余阈值必须为空'
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF v_type = 'ACOS_OVER_TARGET' THEN
    IF NEW.threshold_ratio IS NULL THEN
      RAISE EXCEPTION 'ACOS 超标规则必须给出目标 ACOS（比率阈值）'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.min_over_target_days IS NULL THEN
      RAISE EXCEPTION 'ACOS 超标规则必须给出「超标天数」下限（否则「长期」无从判断）'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.threshold_amount IS NOT NULL OR NEW.budget_utilization_ratio IS NOT NULL
       OR NEW.delivery_ratio IS NOT NULL THEN
      RAISE EXCEPTION 'ACOS 超标规则只使用比率阈值与超标天数下限，其余阈值必须为空'
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF v_type = 'BUDGET_CAPPED' THEN
    IF NEW.budget_utilization_ratio IS NULL THEN
      RAISE EXCEPTION '预算受限规则必须给出预算利用率下限'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.threshold_amount IS NOT NULL OR NEW.threshold_ratio IS NOT NULL
       OR NEW.delivery_ratio IS NOT NULL OR NEW.min_over_target_days IS NOT NULL THEN
      RAISE EXCEPTION '预算受限规则只使用预算利用率下限，其余阈值必须为空'
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF v_type = 'LOW_DELIVERY' THEN
    IF NEW.delivery_ratio IS NULL THEN
      RAISE EXCEPTION '曝光不足规则必须给出曝光相对下限'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.threshold_amount IS NOT NULL OR NEW.threshold_ratio IS NOT NULL
       OR NEW.budget_utilization_ratio IS NOT NULL OR NEW.min_over_target_days IS NOT NULL THEN
      RAISE EXCEPTION '曝光不足规则只使用曝光相对下限，其余阈值必须为空'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- 🔴 动作与幅度的自洽：
  --   改金额的动作（调预算）**必须**带幅度，否则「调多少」是空的；
  --   改状态 / 转向别的模块的动作（暂停、去关键词管理）**不得**带幅度 —— 暂停没有「幅度」这回事。
  IF NEW.action_type IN ('INCREASE_BUDGET','DECREASE_BUDGET') THEN
    IF NEW.action_percent IS NULL AND NEW.action_amount IS NULL THEN
      RAISE EXCEPTION '动作 %s 必须给出建议幅度（比例或绝对额）', NEW.action_type
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.action_type IN ('PAUSE_CAMPAIGN','OPTIMIZE_KEYWORDS','NONE') THEN
    IF NEW.action_percent IS NOT NULL OR NEW.action_amount IS NOT NULL THEN
      RAISE EXCEPTION '动作 %s 不携带建议幅度', NEW.action_type
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  IF NEW.secondary_action_type IS NOT NULL THEN
    IF NEW.secondary_action_type IN ('INCREASE_BUDGET','DECREASE_BUDGET') THEN
      IF NEW.secondary_action_percent IS NULL AND NEW.secondary_action_amount IS NULL THEN
        RAISE EXCEPTION '次要动作 %s 必须给出建议幅度', NEW.secondary_action_type
          USING ERRCODE = 'check_violation';
      END IF;
    ELSIF NEW.secondary_action_type IN ('PAUSE_CAMPAIGN','OPTIMIZE_KEYWORDS') THEN
      IF NEW.secondary_action_percent IS NOT NULL OR NEW.secondary_action_amount IS NOT NULL THEN
        RAISE EXCEPTION '次要动作 %s 不携带建议幅度', NEW.secondary_action_type
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER guard_ad_diagnosis_rule_thresholds_trg
  AFTER INSERT OR UPDATE ON ad_diagnosis_rule
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_ad_diagnosis_rule_thresholds();

-- ===========================================================================
-- 7. 触发器：动作的模式与结论必须自洽
-- ===========================================================================
CREATE OR REPLACE FUNCTION guard_ad_diagnosis_action_mode() RETURNS trigger AS $$
BEGIN
  IF NEW.action_mode = 'UNAVAILABLE' AND NEW.outcome = 'APPLIED' THEN
    RAISE EXCEPTION '不可用的动作不得有「已生效」的结论'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.action_mode = 'UNAVAILABLE' AND btrim(NEW.impact) = '' THEN
    RAISE EXCEPTION '不可用的动作必须写清「不交付的影响」'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_ad_diagnosis_action_mode_trg
  BEFORE INSERT OR UPDATE ON ad_diagnosis_action
  FOR EACH ROW EXECUTE FUNCTION guard_ad_diagnosis_action_mode();

-- ===========================================================================
-- 8. 多租户隔离：五张表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V203 / V273 / V275 同一套写法。租户由
-- backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_diagnosis_rule', 'ad_diagnosis_run', 'ad_diagnosis_run_rule',
    'ad_diagnosis', 'ad_diagnosis_action' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**活动 → 店铺**收敛（与 V273 同构）
-- ---------------------------------------------------------------------------
-- 诊断结论会直接说出「这条活动烧了多少钱、ACOS 多少」—— 不允许绕过店铺
-- 直接枚举投放效率结论。
CREATE POLICY data_scope ON ad_diagnosis AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_diagnosis.tenant_id AND c.id=ad_diagnosis.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_diagnosis.tenant_id AND c.id=ad_diagnosis.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_diagnosis_action AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_diagnosis_action.tenant_id AND c.id=ad_diagnosis_action.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_diagnosis_action.tenant_id AND c.id=ad_diagnosis_action.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 规则 / 运行 / 规则评估记录是**租户级**事实（不挂在店铺上）⇒ 随租户收敛即可。
-- 诊断结论与建议动作的可见性另由上面的 data_scope 策略按店铺收敛。

-- ===========================================================================
-- 9. 账号与授权（沿用 V273/V275 的条件建角色，保证迁移可重复执行）
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
  ad_diagnosis_rule, ad_diagnosis_run, ad_diagnosis_run_rule,
  ad_diagnosis, ad_diagnosis_action TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V273 / V275 同口径）。
REVOKE ALL ON
  ad_diagnosis_rule, ad_diagnosis_run, ad_diagnosis_run_rule,
  ad_diagnosis, ad_diagnosis_action FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：效率诊断属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.2.2.2 自动规则、14.1.3.1 关键词管理同族，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
