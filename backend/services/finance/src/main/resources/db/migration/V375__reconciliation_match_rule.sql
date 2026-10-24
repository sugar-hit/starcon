-- ===========================================================================
-- V375 · finance_reconciliation_match_rule* —— 12.3.2.3 自动匹配规则
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.3.2.3 自动匹配规则）原文：
--
--   - **职责**：配置对账自动匹配规则：匹配键（订单号 + 金额 + 日期 + 类型）、
--     容差（汇率差、手续费）、优先级、模糊匹配兜底，配置匹配率目标与监控。
--   - **输入**：规则配置；历史对账结果用于规则调优。
--   - **输出**：规则集 + 匹配率统计 + 未匹配原因分布。
--   - **关系**：上游 = 12.3.2.1；下游 = 匹配引擎。
--     **匹配率是财务自动化的核心指标**，持续低于目标时应排查上游数据质量而非调规则。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么（上游/下游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **不改 12.3.2.1 的四张表。** `finance_reconciliation_task` /
--     `finance_reconciliation_match` / `finance_reconciliation_difference` /
--     `finance_reconciliation_event` 是 12.3.2.1 的写入所有权 ⇒ 本迁移
--     **零 ALTER、零 DROP、零新触发器挂上游表、零改上游 GRANT**。
--     本模块**只读**它们（匹配率统计与未匹配原因分布都从它们算出来），
--     读它们是**同服务同库**的合法读路径。
--
--   * **不给上游任务表加 rule_id 列。** 规则集**按租户 + 适用范围**解析：
--     规则自己声明 currency / account_id / shop_id（可空 = 全部），
--     引擎按任务的口径挑出适用规则并按 priority 排序 ⇒ 上游任务表不用动。
--
--   * **不预置任何一行规则。** 规则是**租户自己维护的配置**，
--     表里预置行等于替用户做财务政策决定（卷 12 纪律）。
--
--   * **不折算汇率。** 12.7.1.1 硬约束「账户单一币种」⇒ 一次对账只有一个币种；
--     `exchange_rate_tolerance` 是**同币种下两侧折算汇率不同**留下的残差带，
--     **不是**跨币种折算开关（跨币种的差额恒为 NULL，汇率不可得）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_reconciliation_match_rule          —— 匹配规则（可配置 + 可持久化）
--   finance_reconciliation_match_rule_event    —— 规则事件流（append-only 举证）
--   finance_reconciliation_match_rate          —— 匹配率快照（监控历史）
--   finance_reconciliation_match_rate_reason   —— 未匹配原因分布（每个快照 N 行）
--
--   四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT。
--
-- ---------------------------------------------------------------------------
-- 2. 三条设计主线
-- ---------------------------------------------------------------------------
--
--   1. 🔴 **「不允许『金额相同即匹配』」钉在 DB 层（本模块的核心纪律）**
--      蓝图把匹配键写成「订单号 + 金额 + 日期 + 类型」**四项**，但卷 12 的
--      加粗硬约束是「金额相同只是候选，必须结合订单号、日期、类型」——
--      ⇒ 本表把**金额从匹配键里剔出去**：
--        · `match_keys` 是**候选维度池**，只能是 {REFERENCE, DATE, TYPE}；
--        · 池子**至少两个维度**（CHECK）；
--        · `min_dimension_count`（至少对上几个维度才算候选）**≥ 2**（CHECK），
--          且 ≤ 池子大小（CHECK）。
--      ⇒ 「只按金额」与「金额 + 单个维度」这两类开关在**结构上写不出来**。
--      金额只以**容差**（amount / fee / exchange_rate）出现在**配平与残差归因**上，
--      **不计入维度个数**。
--
--   2. **容差分三种，各有各的口径**（蓝图点名「汇率差、手续费」）
--      · `amount_tolerance`         金额容差：候选配对带（|Δ| ≤ 它 ⇒ 可配对）
--      · `fee_tolerance`            手续费容差：残差归因带（平台扣佣金 ⇒ 残差解释为手续费）
--      · `exchange_rate_tolerance`  汇率差容差：同币种折算差 ⇒ 残差解释为汇率差
--      ⚠️ **容差不放宽「配平」**：12.3.2.1 的
--      `guard_finance_reconciliation_match_group_amount` 要求组内两侧合计
--      **逐位相等** ⇒ 本模块**不去 ALTER 那个触发器**（那是上游的写入所有权），
--      规则容差作用在①候选配对带 ②模糊兜底带 ③残差归因带。
--
--   3. **优先级可判定、不靠 id 排序**
--      激活中的规则在**同一租户内优先级唯一**（partial UNIQUE index）⇒
--      「哪条规则先试」是**确定的**，不依赖 id / created_at 的偶然次序。
--
-- ---------------------------------------------------------------------------
-- 3. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)（与 12.1.x / 12.2.x / 12.3.x / 12.7.x 一致）；**全链路禁浮点**。
--   * 匹配率 numeric(7,4)（0..1 的比率）；匹配率目标 numeric(5,2)（0..100 的百分数）。
--   * 原因占比 numeric(7,4)（0..1）。**跨币种绝不相加** ⇒ 快照**按币种分组**。
--   * 时间字段一律 timestamptz（OffsetDateTime + `?::timestamptz`）。
--
-- ===========================================================================

-- ===========================================================================
-- 1. finance_reconciliation_match_rule —— 匹配规则
-- ===========================================================================
--
-- 一条规则 = 「用哪几个维度配对、窗口多宽、容差多少、模糊兜底怎么兜、优先级多高、
-- 匹配率目标是多少、适用于什么范围」。
CREATE TABLE finance_reconciliation_match_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则号（租户内唯一、可读）：MR-20261024-a1b2c3d4。
  rule_no varchar(48) NOT NULL CHECK(btrim(rule_no) <> ''),
  rule_name varchar(120) NOT NULL CHECK(btrim(rule_name) <> ''),

  -- 状态机：
  --   DRAFT     建了还没启用
  --   ACTIVE    启用中（引擎按 priority 依次试）
  --   DISABLED 停用
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','DISABLED')),

  -- 优先级：数字**越小越先试**（1..9999）。
  priority int NOT NULL DEFAULT 100 CHECK(priority BETWEEN 1 AND 9999),

  -- ---- 匹配键（候选维度池）----
  -- 🔴 卷 12 硬约束：**AMOUNT 不在这个池子里** —— 金额只做容差/配平判据，
  --    不计入维度个数。池子至少两个维度 ⇒ 「金额 + 单个维度」写不出来。
  match_keys text[] NOT NULL,
  CONSTRAINT finance_reconciliation_match_rule_keys_ck CHECK (
    match_keys <@ ARRAY['REFERENCE','DATE','TYPE']::text[]
    AND cardinality(match_keys) >= 2
  ),
  -- 至少对上几个维度才算候选（≥ 2，且不超过池子大小）。
  min_dimension_count int NOT NULL DEFAULT 2,
  CONSTRAINT finance_reconciliation_match_rule_min_count_ck CHECK (
    min_dimension_count >= 2
    AND min_dimension_count <= cardinality(match_keys)
  ),

  -- ---- 精确阶段 ----
  -- 日期窗口上限 31 天：窗口放到几百天等于用「金额相同」冒充匹配。
  date_window_days int NOT NULL DEFAULT 7 CHECK(date_window_days BETWEEN 1 AND 31),
  -- 金额容差（候选配对带）。默认 0：容差一大就变成「金额相同即匹配」的变体。
  amount_tolerance numeric(19,4) NOT NULL DEFAULT 0 CHECK(amount_tolerance >= 0),
  -- 手续费容差（残差归因带）：平台账单扣了佣金/手续费 ⇒ 我方账比平台多，
  --    多出来的部分落在这个带内 ⇒ 归因为「手续费差异」而不是笼统的「金额对不齐」。
  fee_tolerance numeric(19,4) NOT NULL DEFAULT 0 CHECK(fee_tolerance >= 0),
  -- 汇率差容差（残差归因带）：**同币种**下两侧折算汇率不同留下的残差。
  -- ⚠️ 不是跨币种折算开关 —— 跨币种差额恒为 NULL（汇率不可得）。
  exchange_rate_tolerance numeric(19,4) NOT NULL DEFAULT 0 CHECK(exchange_rate_tolerance >= 0),

  -- ---- 模糊匹配兜底 ----
  -- 兜底**放松「怎么比」，不放松「要比几个」**：模糊阶段仍然要求对上
  -- min_dimension_count 个维度（≥ 2）⇒ 兜底不会退化成「金额相同即匹配」。
  fuzzy_enabled boolean NOT NULL DEFAULT false,
  -- 订单号按**前缀**比（前缀太短形同没有单号 ⇒ 下限 4 个字符）。
  fuzzy_reference_prefix_length int NOT NULL DEFAULT 8 CHECK(fuzzy_reference_prefix_length BETWEEN 4 AND 32),
  -- 模糊阶段的日期窗口（必须 ≥ 精确阶段，且同样不超过 31 天）。
  fuzzy_date_window_days int NOT NULL DEFAULT 31 CHECK(fuzzy_date_window_days BETWEEN 1 AND 31),
  -- 模糊阶段的金额残差带。
  fuzzy_amount_tolerance numeric(19,4) NOT NULL DEFAULT 0 CHECK(fuzzy_amount_tolerance >= 0),
  CONSTRAINT finance_reconciliation_match_rule_fuzzy_window_ck CHECK (
    fuzzy_date_window_days >= date_window_days
  ),

  -- ---- 匹配率目标（百分数 0..100）与监控 ----
  match_rate_target numeric(5,2) NOT NULL DEFAULT 90 CHECK(match_rate_target BETWEEN 0 AND 100),

  -- ---- 适用范围（可空 = 全部）----
  -- 规则自己声明适用范围，引擎按任务口径挑规则（上游任务表不用动）。
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  account_id uuid,
  shop_id uuid,

  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  activated_at timestamptz,

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_no)
);

-- 列表默认排序：优先级升序（先试哪条一眼可见）→ 规则号。
CREATE INDEX finance_reconciliation_match_rule_by_priority
  ON finance_reconciliation_match_rule(tenant_id, priority, rule_no);
-- 按状态筛（引擎只取 ACTIVE；页面按状态筛）。
CREATE INDEX finance_reconciliation_match_rule_by_status
  ON finance_reconciliation_match_rule(tenant_id, status, priority);
-- 按适用范围筛（引擎挑规则时用）。
CREATE INDEX finance_reconciliation_match_rule_by_scope
  ON finance_reconciliation_match_rule(tenant_id, currency, account_id, shop_id);

-- 🔴 **激活中的规则在同一租户内优先级唯一** ⇒ 「哪条规则先试」是确定的，
--   不依赖 id / created_at 的偶然次序。停用的规则不参与唯一性。
CREATE UNIQUE INDEX finance_reconciliation_match_rule_active_priority_uk
  ON finance_reconciliation_match_rule(tenant_id, priority)
  WHERE status = 'ACTIVE';

-- ===========================================================================
-- 2. finance_reconciliation_match_rule_event —— 规则事件流（append-only）
-- ===========================================================================
CREATE TABLE finance_reconciliation_match_rule_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_id uuid NOT NULL,

  event_type text NOT NULL CHECK(event_type IN (
    'RULE_CREATED','RULE_UPDATED','RULE_ACTIVATED','RULE_DISABLED','RATE_SNAPSHOT')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  summary varchar(500) NOT NULL DEFAULT '',
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, rule_id)
    REFERENCES finance_reconciliation_match_rule(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_reconciliation_match_rule_event_by_rule
  ON finance_reconciliation_match_rule_event(tenant_id, rule_id, occurred_at, id);

-- ===========================================================================
-- 3. finance_reconciliation_match_rate —— 匹配率快照（监控历史）
-- ===========================================================================
--
-- 一行 = 「在某个时刻、用某条规则的口径、针对某个对账任务（可空 = 租户级）
-- 算出来的匹配率」。**规则后来被改了，历史快照仍是当时的口径**
-- （规则口径随快照一起存下来）。
CREATE TABLE finance_reconciliation_match_rate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  -- 对账任务（12.3.2.1）。可空 = 租户级汇总。
  task_id uuid,
  -- 一次对账只有一个币种 ⇒ 快照**按币种分组**，绝不跨币种合计。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ---- 计数（全部来自 12.3.2.1 的真实结果行）----
  -- 匹配行数：finance_reconciliation_match 的行数（一行 = 一个配成对的 pair）。
  matched_line_count int NOT NULL CHECK(matched_line_count >= 0),
  -- 匹配组数：按 group_key 去重（一行一对，直接求和会重复计）。
  matched_group_count int NOT NULL CHECK(matched_group_count >= 0),
  -- 未匹配条数：finance_reconciliation_difference 的行数。
  unmatched_line_count int NOT NULL CHECK(unmatched_line_count >= 0),
  total_line_count int NOT NULL CHECK(total_line_count >= 0),
  CONSTRAINT finance_reconciliation_match_rate_total_ck CHECK (
    total_line_count = matched_line_count + unmatched_line_count
  ),

  -- 匹配率 = 匹配行数 ÷ 总行数（0..1 的比率）。分母为 0 时**恒为 NULL**
  -- （**不写 0** —— 0 会被读成「一条都没配上」）。
  match_rate numeric(7,4) CHECK(match_rate IS NULL OR (match_rate >= 0 AND match_rate <= 1)),
  -- 当时的匹配率目标（百分数）。
  match_rate_target numeric(5,2) NOT NULL CHECK(match_rate_target BETWEEN 0 AND 100),
  -- 是否达标。match_rate 为 NULL（分母为 0）时**判不了**，恒为 false。
  target_met boolean NOT NULL DEFAULT false,

  -- ---- 规则口径快照（规则后来被改了也不影响历史）----
  rule_no varchar(48) NOT NULL,
  rule_priority int NOT NULL,
  match_keys text[] NOT NULL,
  min_dimension_count int NOT NULL,
  date_window_days int NOT NULL,
  amount_tolerance numeric(19,4) NOT NULL,
  fee_tolerance numeric(19,4) NOT NULL,
  exchange_rate_tolerance numeric(19,4) NOT NULL,
  fuzzy_enabled boolean NOT NULL,

  computed_at timestamptz NOT NULL,
  computed_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, rule_id)
    REFERENCES finance_reconciliation_match_rule(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_reconciliation_match_rate_by_rule
  ON finance_reconciliation_match_rate(tenant_id, rule_id, computed_at DESC);
CREATE INDEX finance_reconciliation_match_rate_by_task
  ON finance_reconciliation_match_rate(tenant_id, task_id, computed_at DESC);
CREATE INDEX finance_reconciliation_match_rate_by_currency
  ON finance_reconciliation_match_rate(tenant_id, currency, computed_at DESC);

-- ===========================================================================
-- 4. finance_reconciliation_match_rate_reason —— 未匹配原因分布
-- ===========================================================================
--
-- 一行 = 「某个快照里，某一类未匹配原因出现了多少条、占比多少」。
--
-- ⚠️ 原因词表**扩充**了 12.3.2.1 的五种差异类型：
--   FEE_DIFFERENCE / EXCHANGE_RATE_DIFFERENCE 是**残差归因**出来的
--   （残差落在规则的手续费 / 汇率差容差带内）⇒ 运营能一眼看出
--   「这批没对上的主要是手续费，不是账错了」。
CREATE TABLE finance_reconciliation_match_rate_reason (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  snapshot_id uuid NOT NULL,

  reason_type text NOT NULL CHECK(reason_type IN (
    'EXTERNAL_ONLY','BOOK_ONLY','AMOUNT_MISMATCH','CURRENCY_MISMATCH',
    'DIMENSION_MISMATCH','FEE_DIFFERENCE','EXCHANGE_RATE_DIFFERENCE')),
  line_count int NOT NULL CHECK(line_count >= 0),
  -- 占未匹配总条数的比例（0..1）。分母为 0 时恒为 0。
  ratio numeric(7,4) NOT NULL DEFAULT 0 CHECK(ratio >= 0 AND ratio <= 1),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, snapshot_id, reason_type),
  FOREIGN KEY(tenant_id, snapshot_id)
    REFERENCES finance_reconciliation_match_rate(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_reconciliation_match_rate_reason_by_snapshot
  ON finance_reconciliation_match_rate_reason(tenant_id, snapshot_id, line_count DESC);

-- ===========================================================================
-- 多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_reconciliation_match_rule','finance_reconciliation_match_rule_event',
    'finance_reconciliation_match_rate','finance_reconciliation_match_rate_reason'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_reconciliation_match_rule TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_reconciliation_match_rule TO starcon_app;
-- 规则事件流是**追加式**举证：运行角色只给 SELECT + INSERT。
GRANT SELECT,INSERT ON finance_reconciliation_match_rule_event TO finance_app;
GRANT SELECT,INSERT ON finance_reconciliation_match_rule_event TO starcon_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_match_rule_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_match_rule_event FROM starcon_app;
-- 匹配率快照是**一次监控事实** ⇒ append-only。
GRANT SELECT,INSERT ON finance_reconciliation_match_rate TO finance_app;
GRANT SELECT,INSERT ON finance_reconciliation_match_rate TO starcon_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_match_rate FROM finance_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_match_rate FROM starcon_app;
GRANT SELECT,INSERT ON finance_reconciliation_match_rate_reason TO finance_app;
GRANT SELECT,INSERT ON finance_reconciliation_match_rate_reason TO starcon_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_match_rate_reason FROM finance_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_match_rate_reason FROM starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- ===========================================================================
-- 数据层守卫（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 规则状态机：只能 DRAFT → ACTIVE → DISABLED（DISABLED 可以回到 DRAFT 重编辑）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_rule_status() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
         (OLD.status = 'DRAFT'    AND NEW.status IN ('ACTIVE','DISABLED'))
      OR (OLD.status = 'ACTIVE'   AND NEW.status = 'DISABLED')
      OR (OLD.status = 'DISABLED' AND NEW.status IN ('ACTIVE','DRAFT'))
    ) THEN
      RAISE EXCEPTION '规则状态只能 DRAFT → ACTIVE → DISABLED（或 DISABLED → DRAFT），不能 % → %',
        OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_rule_status_guard
  BEFORE UPDATE ON finance_reconciliation_match_rule
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_rule_status();

-- (b) 规则事件流 append-only。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_rule_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '规则事件流是追加式举证，不可改写' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_rule_event_append_only
  BEFORE UPDATE OR DELETE ON finance_reconciliation_match_rule_event
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_rule_event_append_only();

-- (c) 匹配率快照 append-only（监控事实不可改写）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_rate_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '匹配率快照是一次监控事实，不可改写' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_rate_append_only
  BEFORE UPDATE OR DELETE ON finance_reconciliation_match_rate
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_rate_append_only();

-- (d) 未匹配原因分布 append-only。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_rate_reason_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '未匹配原因分布不可改写' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_rate_reason_append_only
  BEFORE UPDATE OR DELETE ON finance_reconciliation_match_rate_reason
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_rate_reason_append_only();

-- (e) 快照的原因行必须与快照的计数自洽：原因条数之和 = 未匹配条数。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_rate_reason_sum() RETURNS trigger AS $$
DECLARE
  expected int;
  actual int;
BEGIN
  -- ⚠️ 变量名**不能**与列名同名（`snapshot_id` 是列名 ⇒ 同名变量会让 PG 报
  --   「column reference is ambiguous」）⇒ 这里一律用 NEW.snapshot_id。
  SELECT unmatched_line_count INTO expected
    FROM finance_reconciliation_match_rate
   WHERE id = NEW.snapshot_id AND tenant_id = NEW.tenant_id;
  IF expected IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT coalesce(sum(line_count),0)::int INTO actual
    FROM finance_reconciliation_match_rate_reason
   WHERE snapshot_id = NEW.snapshot_id AND tenant_id = NEW.tenant_id;
  IF actual > expected THEN
    RAISE EXCEPTION '未匹配原因条数之和（%）不得超过快照的未匹配条数（%）',
      actual, expected USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_rate_reason_sum_guard
  BEFORE INSERT ON finance_reconciliation_match_rate_reason
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_rate_reason_sum();

-- (f) 🔴 **「金额相同即匹配」在结构上写不出来**（本模块最要紧的一道闸）。
--     规则里的匹配键只能是 {REFERENCE, DATE, TYPE}，且至少两个维度；
--     至少对上几个维度 ≥ 2。金额只以容差出现，**不计入维度个数**。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_rule_keys() RETURNS trigger AS $$
DECLARE
  counted int;
BEGIN
  SELECT count(*)::int INTO counted
    FROM unnest(coalesce(NEW.match_keys, ARRAY[]::text[])) AS d
   WHERE d IN ('REFERENCE','DATE','TYPE');
  IF counted < 2 THEN
    RAISE EXCEPTION
      '匹配键必须至少包含两个 {订单号, 日期, 类型} 维度（金额相同只是候选，不得计入维度个数）'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.min_dimension_count < 2 THEN
    RAISE EXCEPTION '至少对上的维度数不得小于 2（金额 + 单个维度不构成匹配）'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.min_dimension_count > counted THEN
    RAISE EXCEPTION '至少对上的维度数（%）不得超过匹配键的维度数（%）',
      NEW.min_dimension_count, counted USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_rule_keys_guard
  BEFORE INSERT OR UPDATE ON finance_reconciliation_match_rule
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_rule_keys();
