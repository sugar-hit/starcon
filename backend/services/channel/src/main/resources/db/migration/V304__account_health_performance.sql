-- ===========================================================================
-- 16.1.1.2 绩效指标监控 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 91-97 行）：
--   ### 16.1.1.2 绩效指标监控
--   - 职责：监控各平台核心绩效指标的趋势与阈值：订单缺陷率、迟发率、取消率、有效追踪率、
--           响应时间、账户评级，支持自定义阈值预警与趋势预测（是否会跌破阈值）。
--   - 输入：指标时间序列；阈值配置；预测模型参数。
--   - 输出：指标趋势图 + 阈值预警 + 跌破预测。
--   - 关系：上游 = 16.1.1.1；下游 = 1 工作台预警、9 订单履约（迟发预警前置）。
--           **预测跌破比已跌破更有价值**——提前几天知道就能提前改。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块最关键的一条设计决策：**六项指标分属两个来源，不假装是同一个来源**
-- ---------------------------------------------------------------------------
-- 蓝图列了六项指标：ODR / 迟发率 / 取消率 / 有效追踪率 / **响应时间** / **账户评级**。
-- 但上游 16.1.1.1（V300）的 `account_health_metric.metric_code` 的 CHECK **只钉死了四个码**
-- （ODR / LATE_SHIPMENT_RATE / CANCELLATION_RATE / VALID_TRACKING_RATE）。
--
-- 而本批的既有约定是 **纯新建、零 ALTER 任何既有表** —— 改那个 CHECK 就等于
-- 破坏 16.1.1.1 已交付的契约与它的既有测试。
--
-- ⇒ 处置：**按指标划分归属，不硬塞、不 ALTER**。
--   * 四个码      ⇒ 数据在 16.1.1.1 的 `account_health_metric`（本模块**只读**它）。
--   * 响应时间 /
--     账户评级   ⇒ 数据在本模块自有的 `account_health_performance_metric`。
--
-- 🔴 两张表的 `metric_code` 值域**互补且不重叠**（CHECK 钉死）——
--   这样既不会出现「同一个指标两个来源、互相打架」，也不会出现「表里能写 ODR
--   但没人读它」的第二个真相来源。
-- 🔴 这个「双来源」事实在 **API 与页面上如实表达**（每条序列标注来源模块），
--   **不假装**六项指标都来自同一张表。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 单位（unit）不是可有可无的字段：六项指标根本不是同一种量纲
-- ---------------------------------------------------------------------------
--   * ODR / 迟发率 / 取消率 / 有效追踪率 ⇒ `RATIO`，**0..1 比值**（0.028 = 2.8%），
--     与 16.1.1.1 同一口径（**NULL = 量不到，不是 0**）。
--   * 响应时间 ⇒ `HOURS`，0..8760（小时）。
--   * 账户评级 ⇒ `SCORE`，0..5（星）。
--
-- 🔴 `SCORE` 的 0..5 星制是**本模块的默认口径，不是平台事实**（各平台评级体系不同），
--   与 16.1.1.1 的内置默认阈值同一纪律：**写明是默认值，可按租户覆盖**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 趋势是**区间口径**，不是瞬时点
-- ---------------------------------------------------------------------------
-- `account_health_metric` 的 UNIQUE 键是 (shop, period_start, period_end, metric_code)，
-- **一行 = 一个区间的一个指标**，不是日粒度点。
-- ⇒ 趋势序列按 `period_end` 排序聚成序列，响应里明确写「这是区间口径」，
--   **不把区间值伪装成每日值**。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 NULL = 量不到，必须与 0 区分
-- ---------------------------------------------------------------------------
-- 某店铺某周期某指标为 NULL ⇒ 该点**不进已知数、不算通过**、产出一条 UNKNOWN 预警依据。
-- 均值 / 环比 / 预测遇到 NULL 必须**显式说明分母**，**不许把 NULL 当 0 拉低均值**。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 阈值预警用哪条阈值，必须写清楚
-- ---------------------------------------------------------------------------
-- 本模块的**预警阈值**解析顺序（服务端算，前端只显示结论）：
--   1. `account_health_performance_alert_rule`（本模块自有，租户自定义）⇒ TENANT_CUSTOM
--   2. `account_health_threshold`（16.1.1.1 的租户覆盖，仅四个码有） ⇒ UPSTREAM_TENANT_CUSTOM
--   3. 本模块内置默认 ⇒ BUILTIN_DEFAULT
-- 🔴 **绝不改 `account_health_threshold`**：那是 16.1.1.1 的规则表，
--   改它会破坏它的 rule_version 触发器语义与既有测试。
-- 🔴 预警凭据里**冻结当时用到的阈值快照** ⇒ 改阈值不追溯改变历史预警
--   （与 16.1.1.1「规则变更不追溯改变历史判定」同一纪律）。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 预测跌破要有明确、可解释的口径；数据不足如实说「无法预测」
-- ---------------------------------------------------------------------------
-- 本模块的口径：**最近 N 个周期做最小二乘线性回归**，从**最后一个量到的周期**起
-- 向后推 k 个周期，看第几个周期沿危险方向越线。
-- 🔴 用到的周期、点数、斜率、是否线性**全部写进凭据**（可解释）。
-- 🔴 已知点数不足（< 3）⇒ **CANNOT_PREDICT + 原因**，**绝不给一个假预测**。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.1.1.2，合规域）⇒ `account_health_performance_*`（本迁移）。
--   * 16.1.1.1 ⇒ `account_health_*`（V300，既有，**本迁移零 ALTER**）。
--   * 同卷兄弟 ⇒ `compliance_check_*` / `product_certification_*` / `brand_authz_*` /
--     `brand_cred_*`。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 零外键 + 多租户隔离
-- ---------------------------------------------------------------------------
-- 与 V300 同一套写法：零外键（跨表引用用触发器校验 shop_id 落在本租户店铺目录里），
-- tenant_id NOT NULL + ENABLE **与 FORCE** ROW LEVEL SECURITY + POLICY isolation。
--
-- ===========================================================================
-- 一、account_health_performance_metric · 本模块自有的绩效指标快照
-- ===========================================================================
-- 🔴 只收**本模块自有**的两个码（RESPONSE_TIME / ACCOUNT_RATING）。
--   四个码的数据在 16.1.1.1 的 `account_health_metric` 里，本表**不重复收**。
-- 🔴 metric_value **NULL = 该指标量不到，不是 0**。
-- 🔴 source 必须显式区分：PLATFORM_SYNC（平台同步来的）/ MANUAL（人工录入的），
--   手工录入的**绝不能冒充平台同步来的**。
CREATE TABLE account_health_performance_metric (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  -- 🔴 店铺（本服务 channel 自家库里的 `shop.id`）。零外键，存在性由触发器校验。
  shop_id        uuid NOT NULL,
  -- 🔴 指标所属周期（**区间**，与上游同一形状）：一个周期一个口径。
  period_start   date NOT NULL,
  period_end     date NOT NULL,
  -- 🔴 自建值域：**只含本模块自有的两个码**（与上游四码互补，不重叠）。
  metric_code    varchar(32) NOT NULL
                 CHECK(metric_code IN ('RESPONSE_TIME','ACCOUNT_RATING')),
  -- 🔴 NULL = 该指标量不到（**不是 0 分**）。量纲随 metric_code 走（见下 unit）。
  metric_value   numeric(19,6)
                 CHECK(metric_value IS NULL
                    OR (metric_code = 'RESPONSE_TIME'
                        AND metric_value >= 0 AND metric_value <= 8760)
                    OR (metric_code = 'ACCOUNT_RATING'
                        AND metric_value >= 0 AND metric_value <= 5)),
  -- 🔴 量纲。必须与 metric_code 相符（CHECK 钉死）——
  --   0..5 星制是**本模块的默认口径，不是平台事实**，可按租户覆盖。
  unit           varchar(16) NOT NULL
                 CHECK(unit IN ('HOURS','SCORE')),
  source         varchar(16) NOT NULL
                 CHECK(source IN ('PLATFORM_SYNC','MANUAL')),
  captured_at    timestamptz NOT NULL,
  recorded_by    uuid,
  note           varchar(500) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个店铺的一个周期的一个指标**只有一行**：重录是**改这一行**（订正笔误）。
  UNIQUE(tenant_id, shop_id, period_start, period_end, metric_code),
  CHECK(period_end >= period_start),
  CHECK((metric_code = 'RESPONSE_TIME' AND unit = 'HOURS')
     OR (metric_code = 'ACCOUNT_RATING' AND unit = 'SCORE'))
);
COMMENT ON TABLE account_health_performance_metric IS
  '16.1.1.2 · 本模块自有的绩效指标快照（**响应时间 / 账户评级**）。'
  '上游 16.1.1.1 的四个码在 account_health_metric 里，本表**不重复收**。'
  'metric_value 是 NULL = 量不到（**不是 0**）；量纲随 metric_code 走（HOURS / SCORE）。';
CREATE INDEX account_health_performance_metric_shop
  ON account_health_performance_metric(tenant_id, shop_id, metric_code, period_end DESC);
CREATE INDEX account_health_performance_metric_period
  ON account_health_performance_metric(tenant_id, period_end DESC, shop_id);

-- ===========================================================================
-- 二、account_health_performance_alert_rule · 本模块自有的预警阈值配置
-- ===========================================================================
-- 🔴 这是**本模块自己的**阈值表，**不改** 16.1.1.1 的 `account_health_threshold`。
--   解析顺序：本表（租户自定义）> 上游阈值表（租户覆盖）> 内置默认。
-- 🔴 改阈值**必然推进 rule_version**（触发器钉住）⇒ 历史预警仍然是旧预警。
-- 🔴 阈值量纲随 metric_code 走：四个码是 RATIO（0..1），响应时间是 HOURS，评级是 SCORE。
CREATE TABLE account_health_performance_alert_rule (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  -- 🔴 六个码都**可以**自定义预警阈值（自定义阈值预警是本模块的核心职责）。
  metric_code    varchar(32) NOT NULL
                 CHECK(metric_code IN ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE',
                                       'VALID_TRACKING_RATE','RESPONSE_TIME','ACCOUNT_RATING')),
  -- ABOVE_IS_BAD = 越大越危险（ODR / 迟发率 / 取消率 / 响应时间）
  -- BELOW_IS_BAD = 越小越危险（有效追踪率 / 账户评级）
  direction      varchar(16) NOT NULL
                 CHECK(direction IN ('ABOVE_IS_BAD','BELOW_IS_BAD')),
  unit           varchar(16) NOT NULL
                 CHECK(unit IN ('RATIO','HOURS','SCORE')),
  warn_value     numeric(19,6) NOT NULL,
  critical_value numeric(19,6) NOT NULL,
  -- 🔴 规则版本：改阈值就是新版本（触发器钉住），旧预警仍然是旧预警。
  rule_version   bigint NOT NULL DEFAULT 1 CHECK(rule_version >= 1),
  effective_from timestamptz NOT NULL,
  enabled        boolean NOT NULL DEFAULT true,
  note           varchar(500) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个指标一条有效规则。改规则是 UPDATE 同一行，不是插第二条。
  UNIQUE(tenant_id, metric_code),
  -- 🔴 阈值与方向必须自洽：越大越危险 ⇒ critical ≥ warn；越小越危险 ⇒ critical ≤ warn。
  CHECK((direction = 'ABOVE_IS_BAD' AND critical_value >= warn_value)
     OR (direction = 'BELOW_IS_BAD' AND critical_value <= warn_value)),
  -- 🔴 量纲必须与指标相符（钉死，避免「用比值的阈值去比小时」这种错位）。
  CHECK((metric_code IN ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE','VALID_TRACKING_RATE')
         AND unit = 'RATIO')
     OR (metric_code = 'RESPONSE_TIME' AND unit = 'HOURS')
     OR (metric_code = 'ACCOUNT_RATING' AND unit = 'SCORE')),
  -- 🔴 取值范围随量纲走：比值 0..1、小时 0..8760、星级 0..5。
  CHECK((metric_code IN ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE','VALID_TRACKING_RATE')
         AND warn_value >= 0 AND warn_value <= 1
         AND critical_value >= 0 AND critical_value <= 1)
     OR (metric_code = 'RESPONSE_TIME'
         AND warn_value >= 0 AND warn_value <= 8760
         AND critical_value >= 0 AND critical_value <= 8760)
     OR (metric_code = 'ACCOUNT_RATING'
         AND warn_value >= 0 AND warn_value <= 5
         AND critical_value >= 0 AND critical_value <= 5))
);
COMMENT ON TABLE account_health_performance_alert_rule IS
  '16.1.1.2 · 本模块自有的预警阈值配置（租户级，可配置）。'
  '一个指标一条规则；改阈值必须推进 rule_version（触发器钉住），历史预警不受影响。'
  '**不改** 16.1.1.1 的 account_health_threshold。';
CREATE INDEX account_health_performance_alert_rule_enabled
  ON account_health_performance_alert_rule(tenant_id, metric_code) WHERE enabled;

-- ===========================================================================
-- 三、account_health_performance_alert · 一次预警评估的追加式凭据
-- ===========================================================================
-- 🔴 severity 的三种取值各有确切含义，**不能混**：
--   CRITICAL / WARNING = **真的命中了阈值**（metric_value 与 threshold_value 都必须有值）；
--   UNKNOWN            = 该指标**量不到**（NULL）⇒ **不算通过**，本身就是需要关注的事。
--   已知且未命中      = **不落行**（没有预警就没有凭据）。
-- 🔴 thresholds 是**当时用到的阈值快照** ⇒ 改阈值不追溯改变历史预警。
CREATE TABLE account_health_performance_alert (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  shop_id        uuid NOT NULL,
  -- 同一次评估的多个指标共用一个 evaluation_id（便于回溯「那一次评估看到了什么」）。
  evaluation_id  uuid NOT NULL,
  evaluated_at   timestamptz NOT NULL,
  metric_code    varchar(32) NOT NULL
                 CHECK(metric_code IN ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE',
                                       'VALID_TRACKING_RATE','RESPONSE_TIME','ACCOUNT_RATING')),
  -- 🔴 见上方说明：UNKNOWN ≠ 通过。
  severity       varchar(16) NOT NULL
                 CHECK(severity IN ('CRITICAL','WARNING','UNKNOWN')),
  unit           varchar(16) NOT NULL
                 CHECK(unit IN ('RATIO','HOURS','SCORE')),
  metric_value   numeric(19,6),
  period_start   date NOT NULL,
  period_end     date NOT NULL,
  threshold_kind varchar(16) NOT NULL
                 CHECK(threshold_kind IN ('WARN','CRITICAL','UNKNOWN')),
  threshold_value numeric(19,6),
  direction      varchar(16) NOT NULL
                 CHECK(direction IN ('ABOVE_IS_BAD','BELOW_IS_BAD')),
  rule_version   bigint NOT NULL CHECK(rule_version >= 1),
  rule_source    varchar(24) NOT NULL
                 CHECK(rule_source IN ('BUILTIN_DEFAULT','TENANT_CUSTOM','UPSTREAM_TENANT_CUSTOM')),
  -- 🔴 当时用到的阈值快照（metric_code → {warn, critical, direction, unit, source, version}）。
  thresholds     jsonb NOT NULL DEFAULT '{}'::jsonb,
  message        varchar(500) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(jsonb_typeof(thresholds) = 'object'),
  CHECK(period_end >= period_start),
  -- 🔴 UNKNOWN 行必须真的**量不到**（有值就不叫量不到）。
  CHECK(severity <> 'UNKNOWN' OR metric_value IS NULL),
  -- 🔴 CRITICAL / WARNING 必须**真的命中了阈值**（不是凭空给的等级）。
  CHECK(severity NOT IN ('CRITICAL','WARNING')
        OR (metric_value IS NOT NULL AND threshold_value IS NOT NULL)),
  -- 🔴 UNKNOWN 行没有「命中的阈值」，只有「本来会用的那条规则」。
  CHECK(threshold_kind <> 'UNKNOWN' OR threshold_value IS NULL)
);
COMMENT ON TABLE account_health_performance_alert IS
  '16.1.1.2 · 一次预警评估的**追加式凭据**（触发器钉住不可改写不可删）。'
  'severity=UNKNOWN 表示该指标量不到（**不算通过**）；'
  'thresholds 是当时用到的阈值快照 ⇒ 改阈值不追溯改变历史预警。';
CREATE INDEX account_health_performance_alert_shop
  ON account_health_performance_alert(tenant_id, shop_id, evaluated_at DESC, id);
CREATE INDEX account_health_performance_alert_severity
  ON account_health_performance_alert(tenant_id, severity, evaluated_at DESC);
CREATE INDEX account_health_performance_alert_evaluation
  ON account_health_performance_alert(tenant_id, evaluation_id);

-- ===========================================================================
-- 四、account_health_performance_forecast · 一次跌破预测的追加式凭据
-- ===========================================================================
-- 🔴 预测口径**写进凭据**：method（模型）/ window_points（窗口）/ known_points（已知点数）
--   / used_periods（用到的周期明细）/ slope（斜率）—— 可解释、可复核。
-- 🔴 result 的四种取值：
--   WILL_BREACH      = 按当前趋势会在第 k 个周期沿危险方向越线（**预测跌破**）；
--   ALREADY_BREACHED = **已经**越线了（那不是预测，是现状）；
--   NO_BREACH        = 按当前趋势在预测窗口内不会越线；
--   CANNOT_PREDICT   = **无法预测**（点数不足 / 全是 NULL）—— **绝不给一个假预测**。
CREATE TABLE account_health_performance_forecast (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  shop_id        uuid NOT NULL,
  metric_code    varchar(32) NOT NULL
                 CHECK(metric_code IN ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE',
                                       'VALID_TRACKING_RATE','RESPONSE_TIME','ACCOUNT_RATING')),
  unit           varchar(16) NOT NULL
                 CHECK(unit IN ('RATIO','HOURS','SCORE')),
  forecasted_at  timestamptz NOT NULL,
  -- 🔴 预测模型参数（蓝图输入句里的「预测模型参数」）。
  method         varchar(32) NOT NULL
                 CHECK(method IN ('LINEAR_LEAST_SQUARES')),
  method_note    varchar(500) NOT NULL DEFAULT '',
  -- 窗口里一共看了几个周期 / 其中几个量到了 / 几个量不到（**分母写清楚**）。
  window_points  integer NOT NULL CHECK(window_points >= 0),
  known_points   integer NOT NULL CHECK(known_points >= 0),
  unknown_points integer NOT NULL CHECK(unknown_points >= 0),
  -- 🔴 用到的周期明细（周期 + 值 + 在窗口里的序号）。
  used_periods   jsonb NOT NULL DEFAULT '[]'::jsonb,
  slope          numeric(19,6),
  intercept      numeric(19,6),
  -- 🔴 结论。
  result         varchar(24) NOT NULL
                 CHECK(result IN ('WILL_BREACH','NO_BREACH','ALREADY_BREACHED','CANNOT_PREDICT')),
  result_reason  varchar(500) NOT NULL DEFAULT '',
  breach_level   varchar(16) CHECK(breach_level IN ('WARN','CRITICAL')),
  -- 预测会在**第几个周期**越线（从最后一个量到的周期起算，1 = 下一个周期）。
  breach_in_periods integer,
  predicted_value numeric(19,6),
  threshold_value numeric(19,6),
  threshold_kind varchar(16) CHECK(threshold_kind IN ('WARN','CRITICAL')),
  direction      varchar(16) NOT NULL
                 CHECK(direction IN ('ABOVE_IS_BAD','BELOW_IS_BAD')),
  rule_version   bigint NOT NULL CHECK(rule_version >= 1),
  rule_source    varchar(24) NOT NULL
                 CHECK(rule_source IN ('BUILTIN_DEFAULT','TENANT_CUSTOM','UPSTREAM_TENANT_CUSTOM')),
  thresholds     jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(jsonb_typeof(used_periods) = 'array'),
  CHECK(jsonb_typeof(thresholds) = 'object'),
  -- 🔴 WILL_BREACH 必须真的给出「第几个周期」与预测值与阈值。
  CHECK(result <> 'WILL_BREACH'
        OR (breach_in_periods IS NOT NULL AND breach_in_periods >= 1
            AND predicted_value IS NOT NULL AND threshold_value IS NOT NULL)),
  -- 🔴 已知点数不足 3 个 ⇒ 只能是 CANNOT_PREDICT（**绝不给一个假预测**）。
  CHECK(result <> 'CANNOT_PREDICT' OR known_points < 3),
  CHECK(breach_in_periods IS NULL OR breach_in_periods >= 1)
);
COMMENT ON TABLE account_health_performance_forecast IS
  '16.1.1.2 · 一次跌破预测的**追加式凭据**（触发器钉住不可改写不可删）。'
  'method / window_points / used_periods / slope 写明预测口径，可复核；'
  '点数不足时 result=CANNOT_PREDICT 并给出原因，**绝不给一个假预测**。';
CREATE INDEX account_health_performance_forecast_shop
  ON account_health_performance_forecast(tenant_id, shop_id, forecasted_at DESC, id);
CREATE INDEX account_health_performance_forecast_metric
  ON account_health_performance_forecast(tenant_id, metric_code, forecasted_at DESC);

-- ===========================================================================
-- 五、触发器
-- ===========================================================================
-- 🔴 (a) 预警凭据**不可追溯改写**：结论 / 依据 / 阈值快照 / 规则版本 / 判定时刻都不许改。
CREATE FUNCTION guard_account_health_performance_alert_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.severity IS DISTINCT FROM OLD.severity
     OR NEW.metric_code IS DISTINCT FROM OLD.metric_code
     OR NEW.metric_value IS DISTINCT FROM OLD.metric_value
     OR NEW.threshold_kind IS DISTINCT FROM OLD.threshold_kind
     OR NEW.threshold_value IS DISTINCT FROM OLD.threshold_value
     OR NEW.direction IS DISTINCT FROM OLD.direction
     OR NEW.rule_version IS DISTINCT FROM OLD.rule_version
     OR NEW.rule_source IS DISTINCT FROM OLD.rule_source
     OR NEW.thresholds IS DISTINCT FROM OLD.thresholds
     OR NEW.evaluated_at IS DISTINCT FROM OLD.evaluated_at
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
     OR NEW.period_start IS DISTINCT FROM OLD.period_start
     OR NEW.period_end IS DISTINCT FROM OLD.period_end THEN
    RAISE EXCEPTION
      'Account health performance alert % is an append-only verdict; it cannot be rewritten', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_health_performance_alert_immutable BEFORE UPDATE ON account_health_performance_alert
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_performance_alert_immutable();

-- 🔴 (b) 预警凭据**不可删除**：它是「当时为什么这么判」的证据。
CREATE FUNCTION guard_account_health_performance_alert_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    'Account health performance alert % is append-only; it cannot be deleted', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER account_health_performance_alert_no_delete BEFORE DELETE ON account_health_performance_alert
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_performance_alert_no_delete();

-- 🔴 (c) 预测凭据**不可追溯改写**。
CREATE FUNCTION guard_account_health_performance_forecast_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.result IS DISTINCT FROM OLD.result
     OR NEW.result_reason IS DISTINCT FROM OLD.result_reason
     OR NEW.metric_code IS DISTINCT FROM OLD.metric_code
     OR NEW.method IS DISTINCT FROM OLD.method
     OR NEW.method_note IS DISTINCT FROM OLD.method_note
     OR NEW.window_points IS DISTINCT FROM OLD.window_points
     OR NEW.known_points IS DISTINCT FROM OLD.known_points
     OR NEW.unknown_points IS DISTINCT FROM OLD.unknown_points
     OR NEW.used_periods IS DISTINCT FROM OLD.used_periods
     OR NEW.slope IS DISTINCT FROM OLD.slope
     OR NEW.intercept IS DISTINCT FROM OLD.intercept
     OR NEW.breach_level IS DISTINCT FROM OLD.breach_level
     OR NEW.breach_in_periods IS DISTINCT FROM OLD.breach_in_periods
     OR NEW.predicted_value IS DISTINCT FROM OLD.predicted_value
     OR NEW.threshold_value IS DISTINCT FROM OLD.threshold_value
     OR NEW.threshold_kind IS DISTINCT FROM OLD.threshold_kind
     OR NEW.direction IS DISTINCT FROM OLD.direction
     OR NEW.rule_version IS DISTINCT FROM OLD.rule_version
     OR NEW.rule_source IS DISTINCT FROM OLD.rule_source
     OR NEW.thresholds IS DISTINCT FROM OLD.thresholds
     OR NEW.forecasted_at IS DISTINCT FROM OLD.forecasted_at
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION
      'Account health performance forecast % is an append-only verdict; it cannot be rewritten', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_health_performance_forecast_immutable BEFORE UPDATE ON account_health_performance_forecast
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_performance_forecast_immutable();

-- 🔴 (d) 预测凭据**不可删除**。
CREATE FUNCTION guard_account_health_performance_forecast_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    'Account health performance forecast % is append-only; it cannot be deleted', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER account_health_performance_forecast_no_delete BEFORE DELETE ON account_health_performance_forecast
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_performance_forecast_no_delete();

-- 🔴 (e) 预警阈值规则版本**只能前进**，且**改值必须推进版本**。
CREATE FUNCTION guard_account_health_performance_alert_rule_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_version < OLD.rule_version THEN
    RAISE EXCEPTION
      'Account health performance alert rule % rule_version cannot go backwards (% -> %)',
      OLD.id, OLD.rule_version, NEW.rule_version
      USING ERRCODE='23514';
  END IF;
  IF (NEW.warn_value IS DISTINCT FROM OLD.warn_value
      OR NEW.critical_value IS DISTINCT FROM OLD.critical_value
      OR NEW.direction IS DISTINCT FROM OLD.direction)
     AND NEW.rule_version <= OLD.rule_version THEN
    RAISE EXCEPTION
      'Account health performance alert rule % was changed without a new rule_version (% -> %)',
      OLD.id, OLD.rule_version, NEW.rule_version
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_health_performance_alert_rule_version BEFORE UPDATE ON account_health_performance_alert_rule
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_performance_alert_rule_version();

-- 🔴 (f) 跨表引用校验（**零外键**）：本模块的行引用的 shop_id 必须在本租户的店铺目录里
--     真实存在。不存在（或当前权限范围看不到）一律拒写。
CREATE FUNCTION guard_account_health_performance_shop_ref() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  ref_shop uuid;
BEGIN
  ref_shop := NEW.shop_id;
  IF NOT EXISTS (
       SELECT 1 FROM shop s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = ref_shop) THEN
    RAISE EXCEPTION
      'Account health performance row references a shop that does not exist in this tenant (or is outside the current permission scope): %',
      ref_shop
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_health_performance_metric_shop_ref BEFORE INSERT OR UPDATE ON account_health_performance_metric
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_performance_shop_ref();
CREATE TRIGGER account_health_performance_alert_shop_ref BEFORE INSERT ON account_health_performance_alert
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_performance_shop_ref();
CREATE TRIGGER account_health_performance_forecast_shop_ref BEFORE INSERT ON account_health_performance_forecast
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_performance_shop_ref();

-- ===========================================================================
-- 六、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'account_health_performance_metric','account_health_performance_alert_rule',
    'account_health_performance_alert','account_health_performance_forecast' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V300 的 account_health_metric 同构）：绩效监控是**店铺维度**
-- 的资产，可见性随店铺负责人 / 部门收敛。
CREATE POLICY data_scope ON account_health_performance_metric AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_performance_metric.tenant_id
             AND s.id=account_health_performance_metric.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_performance_metric.tenant_id
             AND s.id=account_health_performance_metric.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON account_health_performance_alert AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_performance_alert.tenant_id
             AND s.id=account_health_performance_alert.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_performance_alert.tenant_id
             AND s.id=account_health_performance_alert.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON account_health_performance_forecast AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_performance_forecast.tenant_id
             AND s.id=account_health_performance_forecast.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_performance_forecast.tenant_id
             AND s.id=account_health_performance_forecast.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 🔴 `account_health_performance_alert_rule` 是**租户级规则**，刻意**只有 isolation 策略**：
--   它不属于某一个店铺，硬套店铺收敛会让规则表在部分人眼里凭空消失。

-- ===========================================================================
-- 七、账号与授权
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
  account_health_performance_metric, account_health_performance_alert_rule,
  account_health_performance_alert, account_health_performance_forecast TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V300 同口径）。
REVOKE ALL ON
  account_health_performance_metric, account_health_performance_alert_rule,
  account_health_performance_alert, account_health_performance_forecast FROM starcon_app;

-- 🔴 **序列授权**：本迁移的四张表**全部用应用侧生成的 uuid 作主键**，没有任何
--   serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。
--   （`GRANT ... ON ALL TABLES` 不覆盖序列；将来有人给这四张表加 identity 列时，
--    必须同时补 `GRANT USAGE, SELECT ON SEQUENCE ...`，否则第一条记录就报权限不足。）

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 / V300 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 绩效监控属于「平台操作 / 合规台账」语义，
-- 与 16.1.1.1 账号健康同族，复用 identity 已注册的 channel.operation.*
-- （读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 八、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **上游 16.1.1.1**：四项指标的时间序列来自 `account_health_metric`（**只读**）。
--   * **本模块自有**：响应时间 / 账户评级的时间序列来自
--     `account_health_performance_metric`。
--   * **下游 1 工作台预警**：未交付 ⇒ 本模块**不编造**工作台预警推送。
--   * **下游 9 订单履约（迟发预警前置）**：未交付 ⇒ 本模块**不编造**履约侧联动。
-- ---------------------------------------------------------------------------
