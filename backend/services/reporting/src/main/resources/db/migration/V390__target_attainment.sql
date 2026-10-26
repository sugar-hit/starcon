-- ===========================================================================
-- V390 · target_attainment —— 13.1.2.2 达成追踪
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:100）：
--
--   ### 13.1.2.2 达成追踪
--   - **职责**：追踪目标达成：当前值、达成率、预测达成（按当前速度推算期末值）、未达标项高亮、
--     人员/产品维度排名。
--   - **输入**：13.1.2.1 目标；实际值投影；时间进度。
--   - **输出**：达成率表 + 预测 + 预警。
--   - **关系**：上游 = 13.1.2.1；下游 = 17 绩效核算、1 工作台预警。
--     **预测达成值比当前值更有管理价值**——月初就知道会不会达标，才有时间干预。
--
-- ---------------------------------------------------------------------------
-- 0. 这张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_target_attainment —— **达成追踪快照**（一次评估的落库形态）
--
-- ⚠️ **本表不是业务事实，是「派生读模型」**：它记的是「在某个评估日、按当时已投影的事实，
--   这个目标的达成率与预测达成是多少」。目标本身（人定的计划）在 13.1.2.1 的
--   `reporting_target` 里；实际值来自已投影的事实表；本表只把「两者 + 时间进度」算出来的
--   结果**冻住**，供下游读取与复盘。
--
--   ⇒ 下游（17 绩效核算、1 工作台预警）**读这张表**，而不是自己去 join 目标表与事实表 ——
--     「月初就知道会不会达标」这句话要求的是**一个可回看的时间点**，不是一个随时会变的实时值。
--
-- ⚠️ **零 ALTER 既有表**：本迁移只 CREATE，不碰 `reporting_target` / `reporting_target_bucket`
--   （V387）与任何事实表（V376 / V381 / V389）。与 13.1.2.1 / 13.2.2.2 同纪律。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 「算不出来」与「等于 0」必须分开（本表的第一条纪律）
-- ---------------------------------------------------------------------------
--
--   蓝图要把「当前值 / 达成率 / 预测达成」摆到管理者面前，而 **0% 达成率是有业务含义的断言**
--   （「确实一件都没做成」），**「不可得」不是**（「我根本拿不到这个维度的实际值」）。
--   把后者写成前者 ⇒ 管理者会去追一个根本不存在的问题。
--
--   ⇒ 用**两个正交的受控词表**把这两件事彻底分开：
--
--     actual_status（实际值取到了吗）
--       AVAILABLE      —— 取到了（窗口里有事实行）。current_value **必有值**（可能是 0：
--                        事实行互相抵消时合计为 0，那是**真的** 0）。
--       NO_DATA        —— 来源可用，但**窗口里一行事实都没有** ⇒ current_value 为 NULL
--                        （**不是 0**）。reason 见 actual_note。
--       NOT_AVAILABLE  —— 该维度 / 该指标**根本没有实际值来源**（结构性）⇒ NULL（**不是 0**）。
--
--     status（达成状态）
--       ACHIEVED       —— 达成率 ≥ 100%。
--       ON_TRACK       —— 达成率 < 100%，但按当前速度（预测达成率）能达标。
--       AT_RISK        —— 预测达成率 < 100% 但 ≥ 80%（差一点，大概率还能救）。
--       BEHIND         —— 预测达成率 < 80%（明显跟不上）。
--       NOT_STARTED    —— 评估日早于周期起 ⇒ 周期一天都没开始 ⇒ 算不出达成率（**不是 0%**）。
--       NOT_COMPARABLE —— 目标值不是正数 ⇒ 达成率的分母为 0 ⇒ **达成率没有定义**（不是 0%）。
--       UNAVAILABLE    —— 实际值不可得（NO_DATA / NOT_AVAILABLE）⇒ 算不出达成率（**不是 0%**）。
--
--   ⚠️ 这三个「算不出来」的状态（NOT_STARTED / NOT_COMPARABLE / UNAVAILABLE）**都不得**出现在
--     「未达标项」名单里 —— 它们不是「没达标」，是「还没法判定」。把它们混进未达标名单，
--     等于告诉管理者「你落后了」，而真相是「我没数据」。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 时间进度与预测达成（按当前速度推算期末值）
-- ---------------------------------------------------------------------------
--
--   elapsed_ratio = 已过天数 ÷ 周期总天数（**日历日**，按本租户时区算）
--   预测期末值   = 当前值 ÷ elapsed_ratio（elapsed_ratio = 0 时**算不出来**，不是 0）
--
--   ⇒ 「预测达成率」= 预测期末值 ÷ 目标值。
--   ⇒ 直线速度下，「预测达成率 ≥ 100%」等价于「达成率 ≥ 时间进度」——
--     所以 ON_TRACK / AT_RISK / BEHIND 的分档就是拿预测达成率与 100% / 80% 比。
--
--   ⚠️ **elapsed_ratio 是 0..1 的实数，不是整数**（半个周期就是 0.5）。
--   ⚠️ **elapsed_ratio = 1** 表示周期已走完（评估日晚于周期止）⇒ 预测值 = 当前值（已经落定）。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 排名（人员 / 产品维度）
-- ---------------------------------------------------------------------------
--
--   rank_position —— 在**同一个排名分组**内的位次（1 起）。
--   rank_group    —— 分组键：归属维度 + 口径码 + 币种 + 周期。
--     ⚠️ **不跨币种排名**（USD 目标与 EUR 目标的达成率本来就不可比 —— 目标值本身都不可加）。
--     ⚠️ **不跨指标排名**（销售额达成率与毛利率达成率不是一回事）。
--   rank_total    —— 该分组内的目标总数（页面照着显示「第 3 / 10 名」）。
--
-- ---------------------------------------------------------------------------
-- 4. 多租户
-- ---------------------------------------------------------------------------
--
--   tenant_id NOT NULL + ENABLE **且 FORCE** ROW LEVEL SECURITY
--   （FORCE 必须：owner 身份一连就会绕过隔离）+ GRANT reporting_app / starcon_app。
--   `reporting_target_attainment` 与 `reporting_target` 之间是**同库**外键，且是
--   **复合外键 (tenant_id, target_id)** ⇒ 快照不可能挂到别的租户的目标上，
--   也不可能在目标被删后留下孤儿快照。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_target_attainment —— 达成追踪快照
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个目标 × 某个评估日」的一次达成评估。
CREATE TABLE reporting_target_attainment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 所属目标（**同库复合外键**：快照不可能挂到别的租户的目标上）。
  target_id uuid NOT NULL,
  -- 评估日（**按本租户时区的日历日**）。同一目标同一天只留一条（重复评估是覆盖，不是堆积）。
  evaluated_on date NOT NULL,
  -- ---- 目标侧的快照（组织改名后仍认得出是谁）----
  metric_code varchar(48) NOT NULL,
  scope_type text NOT NULL CHECK(scope_type IN ('DEPARTMENT','MEMBER','SHOP','PRODUCT')),
  owner_id varchar(160) NOT NULL CHECK(btrim(owner_id) <> ''),
  owner_code varchar(64) NOT NULL DEFAULT '',
  owner_name varchar(150) NOT NULL DEFAULT '',
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  period_start date NOT NULL,
  period_end date NOT NULL,
  target_value numeric(24,4) NOT NULL,
  -- ---- 实际值侧 ----
  -- 当前值（评估日之前（含）该周期内的实际值合计）。
  -- 🔴 **只有 actual_status = 'AVAILABLE' 时才有值**；其余一律 NULL（**不是 0**）。
  current_value numeric(24,4),
  -- 达成率 = 当前值 ÷ 目标值。分母不是正数 ⇒ NULL（**不是 0**）。
  attainment_rate numeric(19,6),
  -- 预测期末值（按当前速度推算）。elapsed_ratio = 0 ⇒ NULL。
  forecast_value numeric(24,4),
  -- 预测达成率 = 预测期末值 ÷ 目标值。
  forecast_attainment_rate numeric(19,6),
  -- 时间进度（已过日历日 ÷ 周期总日历日，0..1）。
  elapsed_ratio numeric(19,6) NOT NULL CHECK(elapsed_ratio >= 0 AND elapsed_ratio <= 1),
  -- 实际值状态（受控词表，见第一节）。
  actual_status text NOT NULL CHECK(actual_status IN ('AVAILABLE','NO_DATA','NOT_AVAILABLE')),
  -- 达成状态（受控词表，见第一节）。
  status text NOT NULL CHECK(
    status IN ('ACHIEVED','ON_TRACK','AT_RISK','BEHIND','NOT_STARTED','NOT_COMPARABLE','UNAVAILABLE')),
  -- 「为什么是这个状态」（取不到实际值 / 取得到但算不出率，各写各的）。
  actual_note varchar(300) NOT NULL DEFAULT '',
  forecast_note varchar(300) NOT NULL DEFAULT '',
  -- ---- 排名（见第三节）----
  -- 🔴 **rank_position = 0 ⇒ 未参与排名**：算不出达成率的行（NOT_STARTED / NOT_COMPARABLE /
  --   UNAVAILABLE）**不进排名** —— 拿「不可得」去排名等于给它一个名次，那是在编造。
  rank_position int NOT NULL CHECK(rank_position >= 0),
  -- 本排名分组里**参与排名**的目标数（可比较的那几个）。
  rank_total int NOT NULL CHECK(rank_total >= 0),
  rank_group varchar(200) NOT NULL DEFAULT '',
  -- ---- 评估时刻 ----
  -- ⚠️ **timestamptz**：评估时刻是「墙钟上的一个瞬间」，用 LocalDateTime 存会丢掉时区。
  --   写入时用 `?::timestamptz` 绑定 OffsetDateTime（**不要**用 Timestamp.valueOf(LocalDateTime)，
  --   那会静默偏 −28800s）。
  evaluated_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一目标的同一个评估日只有一条快照（重复评估是覆盖）。
  UNIQUE(tenant_id, target_id, evaluated_on),
  -- 🔴 **同库复合外键**：跨租户挂靠在数据层就不可能；目标被删 ⇒ 快照级联删除（不留孤儿）。
  FOREIGN KEY (tenant_id, target_id)
    REFERENCES reporting_target(tenant_id, id) ON DELETE CASCADE,
  CHECK(period_start <= period_end),
  -- 🔴 **说「取到了」就必须有当前值；说「没取到」就不能挂一个 0 上去**（假 0 会被读成「当前值是 0」）。
  CHECK((actual_status = 'AVAILABLE') = (current_value IS NOT NULL)),
  -- 🔴 **算出达成率 ⇒ 必须真的取到了实际值**（达成率不可能从「没取到」里算出来）。
  CHECK(attainment_rate IS NULL OR actual_status = 'AVAILABLE'),
  -- 🔴 **预测值与预测达成率同生共死**（一个算出来另一个一定算得出来）。
  CHECK((forecast_value IS NULL) = (forecast_attainment_rate IS NULL)),
  -- 🔴 **预测达成率 ⇒ 必须取到了实际值**（没有当前值就没有速度，也就没有预测）。
  CHECK(forecast_attainment_rate IS NULL OR actual_status = 'AVAILABLE'),
  -- 🔴 **四个「算得出」的达成状态 ⇒ 必须真的有达成率**。
  CHECK(
    (status IN ('ACHIEVED','ON_TRACK','AT_RISK','BEHIND'))
      = (actual_status = 'AVAILABLE' AND attainment_rate IS NOT NULL))
);

-- 按目标查（复核某个目标的历次评估：月初就知道会不会达标 ⇒ 要能回看）。
CREATE INDEX reporting_target_attainment_by_target
  ON reporting_target_attainment(tenant_id, target_id, evaluated_on DESC);
-- 按评估日扫（某一轮全体评估的快照）。
CREATE INDEX reporting_target_attainment_by_day
  ON reporting_target_attainment(tenant_id, evaluated_on DESC);
-- 按归属对象查（下游 17 绩效核算按部门 / 人员汇总目标达成的主路径）。
CREATE INDEX reporting_target_attainment_by_owner
  ON reporting_target_attainment(tenant_id, scope_type, owner_id, evaluated_on DESC);
-- 按排名分组查（人员 / 产品维度排名）。
CREATE INDEX reporting_target_attainment_by_rank
  ON reporting_target_attainment(tenant_id, rank_group, rank_position);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_target_attainment'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_target_attainment TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_target_attainment TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **17 绩效核算（整卷未开发）**：它要的「按部门 / 人员汇总的目标达成」就是这张表 ——
--     `scope_type` + `owner_id` + `currency` + `evaluated_on` + `attainment_rate`。
--     本模块**只算达成**，不算绩效、不改人事。
--   * **1 工作台预警（卷 01）**：预警清单由本模块的 `board` 端点给出
--     （`alerts`），工作台读它即可；**本模块不订阅、不推送**（那是 1.2 预警订阅那一格的事）。
--
-- ===========================================================================
