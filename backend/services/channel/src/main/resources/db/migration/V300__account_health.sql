-- ===========================================================================
-- 16.1.1.1 账号健康总览 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 84-89 行）：
--   ### 16.1.1.1 账号健康总览
--   - 职责：按店铺展示账号健康状态：绩效指标（ODR、迟发率、取消率、有效追踪率）、政策违规计数、
--           申诉状态、风险等级，支持跨店铺对比与风险排序。
--   - 输入：平台绩效数据同步；风险等级计算规则；用户数据范围（店铺维度）。
--   - 输出：店铺健康卡片 + 风险排序 + 需处理项跳转。
--   - 关系：上游 = 2 同步；下游 = 16.1.1.2 指标明细、16.1.2.1 违规。
--           **风险排序比明细更重要**：先处理最可能掉号的店铺。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块最关键的一条缺口：平台绩效指标同步**未接入**
-- ---------------------------------------------------------------------------
-- 蓝图输入句写的是「平台绩效数据同步」，而**本仓没有任何平台账号绩效（ODR / 迟发率 /
-- 取消率 / 有效追踪率）的拉取适配器** —— channel 服务的 vendor 包下只有 OAuth / listing /
-- 广告 / 达人等适配器，**没有账号绩效适配器**。
--
-- 因此本迁移的处置是：
--   * 立一个**平台绩效端口**（vendor/PlatformPerformancePort +
--     vendor/UnavailablePlatformPerformancePort），当前实现逐店铺如实答「未接入」；
--   * 同步端点**明确 409 且一条数据都不写** —— 一条返回「ODR = 3.2%」的假同步会让运营
--     以为账号真的在掉号，而现场一个真实指标都没有；
--   * **手工录入是正当路径**（同 15.1.1.1 的「消息只能人工登记」），但录入的数据
--     **必须显式标 MANUAL**，不能冒充平台同步来的；
--   * 这个缺口在能力声明里是一条**可查的事实**（哪一维不可用、归谁），不是文档里的一句话。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.1.1.1，合规域）⇒ `account_health_*`（本迁移）。
--   * 同卷兄弟 16.2.2.1 ⇒ `compliance_check_*`；16.2.2.2 ⇒ `product_certification_*`；
--     16.4.2.1 ⇒ `brand_authz_*`；16.4.2.2 ⇒ `brand_cred_*`。
--   * 卷 14 广告域 ⇒ `ad_*`；卷 15 客服域在 crm 服务自己的库里。
-- **不要用 `account_health` 裸名**：裸名留给表前缀族本身。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 三张表各答一个问题，缺一不可
-- ---------------------------------------------------------------------------
--   (a) `account_health_metric`      —— 绩效指标快照的真实存储（按店铺 + 周期 + 指标）。
--   (b) `account_health_threshold`   —— 风险等级的阈值规则（服务端算、可审阅、可配置）。
--   (c) `account_health_assessment`  —— 一次判定的**追加式凭据**（等级 + 逐条依据 + 规则版本）。
--
-- (a) 与 (b) 是「当前状态」，(c) 是「那一次判定」。(c) 存在的理由是卷首约束第二条：
-- **规则要有生效时间与版本，规则变更不能追溯改变历史判定** —— 如果等级是现算的，
-- 运营改了阈值，昨天那次「CRITICAL」就悄悄变成了「HEALTHY」，「上次为什么这么判」
-- 永远答不出来。所以 (c) 把用到的阈值**快照**进自己的行里，事后不可改写。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 零外键（跨表引用一律用触发器）
-- ---------------------------------------------------------------------------
-- 外键会打破同服务既有测试的 TRUNCATE，这是本仓既有约定（同卷 V197 / V248 也有 FK，
-- 但那是它们的取舍；本迁移按任务书纪律走零外键）。跨表引用校验改成 BEFORE 触发器：
--   * 指标行 / 判定行的 shop_id 必须在本租户的 `shop` 目录里真实存在；
--   * 判定的阈值快照必须自洽（见 CHECK）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V197 / V248 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **哪些表带 data_scope（RESTRICTIVE）策略、哪些不带**：
--   * `account_health_metric` / `account_health_assessment` 是**店铺维度**的资产
--     （蓝图输入句明写「用户数据范围（店铺维度）」）⇒ 与 V197 的合规预检报告同构，
--     可见性随店铺负责人 / 部门收敛。
--   * `account_health_threshold` 是**租户级规则**（它不属于某一个店铺）⇒ 与 V248 的
--     「授权档案是租户级合规资产」同一判断，只带 isolation 策略。硬套店铺收敛会让
--     规则表在部分人眼里凭空消失，改阈值的人看不到自己改过什么。
--
-- ===========================================================================
-- 一、account_health_metric · 绩效指标快照
-- ===========================================================================
-- 🔴 metric_value 是 **0..1 比值**（不是百分数），**NULL = 该指标未知，不是 0**。
--   0.028 = 2.8%。判读时永远记着这一层，否则「ODR 0.028」会被读成「0.028%」。
-- 🔴 source 必须显式区分：PLATFORM_SYNC（平台同步来的）/ MANUAL（人工录入的）。
--   手工录入的那部分**绝不能冒充平台同步来的** —— 界面上要能看出这条是手工来的。
CREATE TABLE account_health_metric (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  -- 🔴 店铺（**本服务 channel 自家库**里的 `shop.id`）。不建 FK（零外键纪律），
  --    存在性由触发器 guard_account_health_shop_ref 校验。
  shop_id        uuid NOT NULL,
  -- 🔴 指标所属周期（**区间**，与 16.4.2.1 的授权期限同一形状）：一个周期一个口径。
  period_start   date NOT NULL,
  period_end     date NOT NULL,
  metric_code    varchar(32) NOT NULL
                 CHECK(metric_code IN ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE','VALID_TRACKING_RATE')),
  -- 🔴 0..1 比值；**NULL = 该指标未知**（平台没给 / 还没录入），**不是 0 分**。
  metric_value   numeric(5,4)
                 CHECK(metric_value IS NULL OR (metric_value >= 0 AND metric_value <= 1)),
  source         varchar(16) NOT NULL
                 CHECK(source IN ('PLATFORM_SYNC','MANUAL')),
  -- 这一条是什么时候被采集/录入的（**同源 DB 时钟**，不用应用机时钟）
  captured_at    timestamptz NOT NULL,
  recorded_by    uuid,
  note           varchar(500) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个店铺的一个周期的一个指标**只有一行**：重录是**改这一行**（订正笔误），
  -- 历史不被推翻 —— 被推翻的是「现在这个周期的口径」，而「当时怎么判的」由
  -- account_health_assessment 的追加式凭据保住。
  UNIQUE(tenant_id, shop_id, period_start, period_end, metric_code),
  CHECK(period_end >= period_start)
);
COMMENT ON TABLE account_health_metric IS
  '16.1.1.1 · 店铺绩效指标快照（ODR / 迟发率 / 取消率 / 有效追踪率）。'
  'metric_value 是 0..1 比值，NULL = 该指标未知（**不是 0**）。'
  'source 区分平台同步与人工录入，人工录入的条目**不冒充**平台同步。';
CREATE INDEX account_health_metric_shop
  ON account_health_metric(tenant_id, shop_id, metric_code, period_end DESC);
CREATE INDEX account_health_metric_period
  ON account_health_metric(tenant_id, period_end DESC, shop_id);

-- ===========================================================================
-- 二、account_health_threshold · 风险等级阈值规则
-- ===========================================================================
-- 🔴 阈值**不在前端**：前端只显示后端算出来的等级与依据。改阈值是**服务端行为**，
--   改完产生**新版本**，历史判定仍然是旧判定。
-- 🔴 内置默认阈值（AccountHealthRules）只是**本模块的默认政策**，不是平台事实 ——
--   平台各自的绩效口径并不通用，所以默认阈值必须**可按租户覆盖**，并且在能力声明里
--   说清「这是默认值、不是平台下发的口径」。
CREATE TABLE account_health_threshold (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  metric_code    varchar(32) NOT NULL
                 CHECK(metric_code IN ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE','VALID_TRACKING_RATE')),
  -- ABOVE_IS_BAD = 越大越危险（ODR / 迟发率 / 取消率）
  -- BELOW_IS_BAD = 越小越危险（有效追踪率）
  direction      varchar(16) NOT NULL
                 CHECK(direction IN ('ABOVE_IS_BAD','BELOW_IS_BAD')),
  -- 🔴 同样是 **0..1 比值**（0.028 = 2.8%），与指标列同一口径。
  warn_value     numeric(5,4) NOT NULL
                 CHECK(warn_value >= 0 AND warn_value <= 1),
  critical_value numeric(5,4) NOT NULL
                 CHECK(critical_value >= 0 AND critical_value <= 1),
  -- 🔴 规则版本：改阈值就是新版本（触发器钉住），旧判定仍然是旧判定。
  rule_version   bigint NOT NULL DEFAULT 1 CHECK(rule_version >= 1),
  effective_from timestamptz NOT NULL,
  enabled        boolean NOT NULL DEFAULT true,
  note           varchar(500) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个指标一条有效规则（覆盖内置默认）。改规则是 UPDATE 同一行，不是插第二条。
  UNIQUE(tenant_id, metric_code),
  -- 🔴 阈值与方向必须自洽：越大越危险 ⇒ critical ≥ warn；越小越危险 ⇒ critical ≤ warn。
  CHECK((direction = 'ABOVE_IS_BAD' AND critical_value >= warn_value)
     OR (direction = 'BELOW_IS_BAD' AND critical_value <= warn_value))
);
COMMENT ON TABLE account_health_threshold IS
  '16.1.1.1 · 风险等级阈值规则（租户级，可配置）。一条指标一条规则；'
  '改阈值必须推进 rule_version（触发器钉住），历史判定不受影响。';
CREATE INDEX account_health_threshold_enabled
  ON account_health_threshold(tenant_id, metric_code) WHERE enabled;

-- ===========================================================================
-- 三、account_health_assessment · 一次判定的追加式凭据
-- ===========================================================================
-- 🔴 这张表是「规则变更不能追溯改变历史判定」这条约束的物理落点：
--   它把**当时用到的阈值快照**与**逐条依据**一起冻在行里。
-- 🔴 reasons 是逐条依据（哪一项指标、什么值、命中哪条阈值），**不是只给一个孤零零的等级**。
CREATE TABLE account_health_assessment (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  shop_id        uuid NOT NULL,
  -- 判定时刻（**同源 DB 时钟**）
  assessed_at    timestamptz NOT NULL,
  -- 本次判定覆盖的指标周期区间（各指标取各自最新周期，所以区间取包络）
  period_start   date NOT NULL,
  period_end     date NOT NULL,
  -- CRITICAL / WARNING / HEALTHY / UNKNOWN
  risk_level     varchar(16) NOT NULL
                 CHECK(risk_level IN ('CRITICAL','WARNING','HEALTHY','UNKNOWN')),
  -- 本次判定里**量到了几项**指标（0..4）。量不到的那几项**不算通过**。
  known_metric_count integer NOT NULL
                 CHECK(known_metric_count >= 0 AND known_metric_count <= 4),
  -- 命中阈值（warn 或 critical）的指标数
  breach_count   integer NOT NULL DEFAULT 0 CHECK(breach_count >= 0),
  -- 🔴 判定用的规则版本与来源。BUILTIN_DEFAULT = 用的是模块内置默认阈值；
  --   TENANT_CUSTOM = 全部用租户自定义阈值；MIXED = 一部分自定义、一部分默认。
  rule_version   bigint NOT NULL CHECK(rule_version >= 1),
  rule_source    varchar(24) NOT NULL
                 CHECK(rule_source IN ('BUILTIN_DEFAULT','TENANT_CUSTOM','MIXED')),
  -- 🔴 本次判定用到的阈值**快照**（metric_code → {warn, critical, direction, source, version}）。
  --   阈值改了之后，这一行仍然说得出「当时按什么判的」。
  thresholds     jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 逐条依据（数组）。每条至少带：指标、实际值、命中哪档、说明。
  reasons        jsonb NOT NULL DEFAULT '[]'::jsonb,
  summary        varchar(500) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(jsonb_typeof(thresholds) = 'object'),
  CHECK(jsonb_typeof(reasons) = 'array'),
  CHECK(period_end >= period_start),
  -- 🔴 **量不到不是低风险**：要断言 HEALTHY（四项全量到且无一命中）才算 HEALTHY。
  --   少一项就只能是 UNKNOWN —— 把「没量到」写成「健康」是最危险的一种撒谎。
  CHECK(risk_level <> 'HEALTHY' OR (known_metric_count = 4 AND breach_count = 0)),
  -- 🔴 CRITICAL / WARNING 必须**真的命中了阈值**（不是凭空给的等级）
  CHECK(risk_level NOT IN ('CRITICAL','WARNING') OR breach_count > 0),
  -- 🔴 UNKNOWN ⇒ 没有任何可断言的命中
  CHECK(risk_level <> 'UNKNOWN' OR breach_count = 0)
);
COMMENT ON TABLE account_health_assessment IS
  '16.1.1.1 · 一次账号健康判定的追加式凭据（触发器钉住不可改写）。'
  'thresholds 是当时用到的阈值快照，reasons 是逐条依据 —— 规则变更不追溯改变历史判定。';
CREATE INDEX account_health_assessment_shop
  ON account_health_assessment(tenant_id, shop_id, assessed_at DESC, id);
CREATE INDEX account_health_assessment_level
  ON account_health_assessment(tenant_id, risk_level, assessed_at DESC);

-- ===========================================================================
-- 四、触发器
-- ===========================================================================
-- 🔴 (a) 判定凭据**不可追溯改写**：结论 / 依据 / 阈值快照 / 规则版本 / 判定时刻都不许改。
--     规则改了 ⇒ 重判一次 ⇒ 新的版本、新的行；旧行原样留着。
CREATE FUNCTION guard_account_health_assessment_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.risk_level IS DISTINCT FROM OLD.risk_level
     OR NEW.known_metric_count IS DISTINCT FROM OLD.known_metric_count
     OR NEW.breach_count IS DISTINCT FROM OLD.breach_count
     OR NEW.rule_version IS DISTINCT FROM OLD.rule_version
     OR NEW.rule_source IS DISTINCT FROM OLD.rule_source
     OR NEW.thresholds IS DISTINCT FROM OLD.thresholds
     OR NEW.reasons IS DISTINCT FROM OLD.reasons
     OR NEW.assessed_at IS DISTINCT FROM OLD.assessed_at
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
     OR NEW.period_start IS DISTINCT FROM OLD.period_start
     OR NEW.period_end IS DISTINCT FROM OLD.period_end THEN
    RAISE EXCEPTION
      'Account health assessment % is an append-only verdict; it cannot be rewritten', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_health_assessment_immutable BEFORE UPDATE ON account_health_assessment
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_assessment_immutable();

-- 🔴 (b) 判定凭据**不可删除**：它是「当时为什么这么判」的证据。
CREATE FUNCTION guard_account_health_assessment_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    'Account health assessment % is append-only; it cannot be deleted', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER account_health_assessment_no_delete BEFORE DELETE ON account_health_assessment
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_assessment_no_delete();

-- 🔴 (c) 阈值规则版本**只能前进**，且**改值必须推进版本**：
--     规则版本回退 = 有人想把历史判定改回旧口径，那是篡改不是修订。
CREATE FUNCTION guard_account_health_threshold_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_version < OLD.rule_version THEN
    RAISE EXCEPTION
      'Account health threshold % rule_version cannot go backwards (% -> %)',
      OLD.id, OLD.rule_version, NEW.rule_version
      USING ERRCODE='23514';
  END IF;
  IF (NEW.warn_value IS DISTINCT FROM OLD.warn_value
      OR NEW.critical_value IS DISTINCT FROM OLD.critical_value
      OR NEW.direction IS DISTINCT FROM OLD.direction)
     AND NEW.rule_version <= OLD.rule_version THEN
    RAISE EXCEPTION
      'Account health threshold % was changed without a new rule_version (% -> %)',
      OLD.id, OLD.rule_version, NEW.rule_version
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_health_threshold_version BEFORE UPDATE ON account_health_threshold
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_threshold_version();

-- 🔴 (d) 跨表引用校验（**零外键**）：指标行 / 判定行的 shop_id 必须在本租户的店铺目录里
--     真实存在。不存在（或当前权限范围看不到）一律拒写。
CREATE FUNCTION guard_account_health_shop_ref() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  ref_shop uuid;
BEGIN
  ref_shop := NEW.shop_id;
  IF NOT EXISTS (
       SELECT 1 FROM shop s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = ref_shop) THEN
    RAISE EXCEPTION
      'Account health row references a shop that does not exist in this tenant (or is outside the current permission scope): %',
      ref_shop
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_health_metric_shop_ref BEFORE INSERT OR UPDATE ON account_health_metric
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_shop_ref();
CREATE TRIGGER account_health_assessment_shop_ref BEFORE INSERT ON account_health_assessment
  FOR EACH ROW EXECUTE FUNCTION guard_account_health_shop_ref();

-- ===========================================================================
-- 五、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'account_health_metric','account_health_threshold','account_health_assessment' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V197 的合规预检报告同构）：账号健康是**店铺维度**的资产，
-- 可见性随店铺负责人 / 部门收敛（蓝图输入句：「用户数据范围（店铺维度）」）。
CREATE POLICY data_scope ON account_health_metric AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_metric.tenant_id
             AND s.id=account_health_metric.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_metric.tenant_id
             AND s.id=account_health_metric.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON account_health_assessment AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_assessment.tenant_id
             AND s.id=account_health_assessment.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=account_health_assessment.tenant_id
             AND s.id=account_health_assessment.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 🔴 `account_health_threshold` 是**租户级规则**，刻意**只有 isolation 策略**：
--   它不属于某一个店铺，硬套店铺收敛会让规则表在部分人眼里凭空消失。

-- ===========================================================================
-- 六、账号与授权
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
  account_health_metric, account_health_threshold, account_health_assessment TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V290 同口径）。
REVOKE ALL ON
  account_health_metric, account_health_threshold, account_health_assessment FROM starcon_app;

-- 🔴 **序列授权**：本迁移的三张表**全部用应用侧生成的 uuid 作主键**，没有任何
--   serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。
--   （`GRANT ... ON ALL TABLES` 不覆盖序列；将来有人给这三张表加 identity 列时，
--    必须同时补 `GRANT USAGE, SELECT ON SEQUENCE ...`，否则第一条记录就报权限不足。）

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 账号健康属于「平台操作 / 合规台账」语义，
-- 与 4.1.1.1 listing 列表、16.2.2.1 合规预检、16.4.2.1 品牌授权同族，
-- 复用 identity 已注册的 channel.operation.*（读 / 录 / 改）与 channel.sync.trigger（触发同步），
-- identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 七、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **2.4 同步运行（平台绩效适配器）**：未交付 ⇒ 本模块**不拉**任何平台绩效数据，
--     同步端点恒 409 且一条数据都不写；绩效数据只能**人工录入**（source=MANUAL）。
--   * **16.1.1.2 绩效指标监控**：未交付 ⇒ 本模块只给**当前**状态与排序，
--     不做趋势图、不做阈值预警、不做「是否会跌破阈值」的预测。
--   * **16.1.2.1 违规与处罚**：未交付 ⇒ 本模块**不编造**政策违规计数。
--   * **16.1.2.2 申诉管理**：未交付 ⇒ 本模块**不编造**申诉状态，
--     也不提供指向不存在的申诉页面的跳转。
--   * 「需处理项」只给**本模块自己真实存在的**待处理项（命中阈值的店铺 /
--     指标缺失的店铺 / 平台同步未接入），跳转目标是本模块自己的页面。
-- ---------------------------------------------------------------------------
