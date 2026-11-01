-- ===========================================================================
-- V422 · automation_rule_run —— 18.3.2.1「执行日志」
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.3「业务规则引擎」→ 18.3.2「执行」→ 18.3.2.1：
--
--   「**职责**：记录每次规则执行：触发事件、条件命中情况、执行的动作、每步结果、耗时、错误；
--      支持查看上下文与手动重跑。
--    **输入**：执行记录；重跑请求。
--    **输出**：执行明细 + 失败原因定位。
--    **关系**：上游 = 18.3.1.2；下游 = 1 工作台告警。
--    **规则必须幂等**：同一事件重复触发不能重复执行副作用（如重复改价、重复建单）。」
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**只建两张表**（边界先说清楚）
-- ---------------------------------------------------------------------------
--
--   platform_automation_rule_run      **一次执行的明细台账**（run）
--   platform_automation_rule_run_step **逐步明细**（step）
--
-- ⚠️ **绝不 ALTER、不碰 18.3.1.1（V419）的五张表**
--   （platform_automation_rule / _condition / _action / _scope / _execution）。
--   两件事必须分清楚：
--
--   * 18.3.1.1 的 `platform_automation_rule_execution` 是**执行统计台账** ——
--     只存聚合计数（命中条件数 / 计划动作数 / 已执行动作数 / 起止时间），
--     **不含逐步明细** ⇒ 「失败到底卡在第几步、命中的是哪条条件」在那边查不到。
--   * 本模块要的是**逐步明细** ⇒ 另建自己的表。
--
--   表名与 18.3.1.1 **不重名**（`platform_automation_rule_run` vs
--   `platform_automation_rule_execution`），本迁移**只读**规则档案（FK + 名字快照），
--   **一个字都不写**进 18.3.1.1 的表 ⇒ 两边各自是独立事实表的唯一写入所有者。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 本模块最核心的正确性要求：幂等（蓝图加粗句）
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句：「**规则必须幂等**：同一事件重复触发不能重复执行副作用
-- （如重复改价、重复建单）。」
--
-- 这条要求在本迁移里被钉成**三个物理事实**，而不是一句注释：
--
--   (a) **事件维度的唯一约束**：
--       `UNIQUE (tenant_id, rule_id, event_id) WHERE rerun_of_run_id IS NULL`
--       ⇒ 同一条规则、同一个事件 id，**至多**落成一条「事件触发的执行记录」。
--       重复投递在数据层就撞唯一索引 ⇒ **不可能凭空多出一条 run 并宣称已执行**。
--
--   (b) **幂等键的唯一索引**：
--       `UNIQUE (tenant_id, rule_id, idempotency_key)`
--       事件触发的执行 ⇒ `idempotency_key = 'evt:' || event_id`；
--       手动重跑 ⇒ `idempotency_key = 'rerun:' || <原 run id> || ':' || <序号>`
--       （或由调用方自带幂等键，见第 2 节 (c)）。
--       ⇒ **重跑请求重复提交**也拿不到第二条记录（同一个幂等键 ⇒ 同一条结果）。
--
--   (c) **重跑是**新的一次执行**，且与原 run 显式关联**：
--       `rerun_of_run_id` 指向原 run，`rerun_seq` 标明第几次重跑。
--       ⚠️ **绝不把原 run 改写成重跑结果** —— 原 run 是**只追加**台账
--       （第 4 节的触发器钉死 UPDATE / DELETE），物理上改不了。
--
--   ⇒ 「同一事件重复触发」与「手动重跑」这两件事因此**不会互相打架**：
--     前者被 (a) 拒绝，后者被 (c) 放行成一条**新的、显式挂在原 run 下**的记录。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 手动重跑**只做记录与编排，不擅自执行副作用**
-- ---------------------------------------------------------------------------
--
-- 本仓**不存在**规则执行引擎（backend / services 下没有求值器与动作执行器）⇒
-- 重跑请求落成一条**待执行**记录，并如实标注：
--
--   outcome            = 'NOT_ATTEMPTED'（**未执行**，不是「已重跑成功」）
--   not_attempted_reason = 写清「执行器未接通、归谁、现在怎么办」
--   error_code         = 'EXECUTOR_NOT_CONNECTED'
--   side_effects_applied = false
--   executed_actions   = 0
--
-- ⚠️ **表级 CHECK 钉死**：`outcome = 'NOT_ATTEMPTED'` ⇒ 必须写得出未接通原因
--   （否则「没执行」却不解释 = 又一种静默失败）。
--
-- 这与本仓既有做法同源：`UnavailableAiTextGenerationPort` /
-- `UnavailableEventSubscriptionDeliveryPort` 那种「无上游 ⇒ 如实返回 Unavailable」。
--
-- ---------------------------------------------------------------------------
-- 3. 每一步都要可定位（蓝图「每步结果、耗时、错误」）
-- ---------------------------------------------------------------------------
--
-- 逐步明细表逐条记：
--   · `step_no`      —— 第几步（**全序**，不是集合）；
--   · `step_kind`    —— CONDITION（条件判定）/ ACTION（动作执行）；
--   · `matched`      —— **条件命中情况**（命中与否）；
--   · `condition_id` / `condition_seq` / `field_path` / `operator` /
--     `expected_value` / `actual_value` —— **命中的是哪条 condition**；
--   · `action_type` / `target_ref` / `params` —— **执行的动作**；
--   · `result`       —— 每步结果；
--   · `duration_ms`  —— **每步耗时，整数毫秒**（禁浮点）；
--   · `error_code` / `error_message` —— 错误码与错误信息。
--
-- ⇒ 失败原因定位可以**从 run 直接下钻到 step**（run 详情带 steps）。
--
-- ⚠️ `condition_id` **不建 FK**：18.3.1.1 保存规则时是「条件整体替换」，
--   条件行会被删掉 ⇒ 建了 FK 就会把**历史执行日志**一起锁死
--   （改一次规则，历史就没法看了）。所以这里只存**引用 + 快照**
--   （condition_id / condition_seq / field_path 一起存下）。
--
-- ---------------------------------------------------------------------------
-- 4. 时间、耗时、数量与金额
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 `timestamptz`；服务层用 `OffsetDateTime` 绑定并在 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s）。
--   * 耗时一律**整数毫秒**（`bigint`）—— 禁浮点。
--   * 数量（命中条件数 / 计划动作数 / 已执行动作数）一律 `numeric(19,6)`（禁浮点）。
--   * 本模块**不碰金额**（它管的是执行过程，不是账）。
--
-- ---------------------------------------------------------------------------
-- 5. 只追加台账 + 租户触发器兜底 + RLS
-- ---------------------------------------------------------------------------
--
--   * run / step 两张表都是**只追加**的（触发器钉死 UPDATE / DELETE）：
--     能改的执行历史等于没有执行历史（与 V419 / V421 同一纪律）。
--   * 租户触发器兜底：直连 SQL 也写不出跨租户的行（与 identity V384 同一纪律）。
--   * RLS：tenant_id NOT NULL + ENABLE **与 FORCE** isolation。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_automation_rule_run —— 一次执行的明细台账（只追加）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某条规则因为某个事件跑了一次」这个事实。
CREATE TABLE platform_automation_rule_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 指明这次执行属于**哪条规则**（🔴 只读引用 18.3.1.1 的规则档案）。
  --   有真 FK ⇒ 执行日志不可能挂在一条不存在的规则上。
  --   ⚠️ **不建 ON DELETE CASCADE**：规则被删时历史不该跟着消失 ——
  --   「历史要能复现」与「能改能删的历史」只能选一个，这里选前者。
  rule_id uuid NOT NULL,

  -- 规则名快照：规则后来改名了，历史里仍看得到「当时跑的是哪条规则」。
  rule_name varchar(120) NOT NULL DEFAULT '',

  -- 🔴 **触发事件 id**（幂等键的一半，见第 1 节）。
  --   同一事件被重复投递 ⇒ 撞唯一约束 ⇒ 拒绝，不产生第二次副作用。
  event_id varchar(200) NOT NULL CHECK(btrim(event_id) <> ''),

  -- 🔴 **幂等键**（第 1 节 (b)）：
  --   事件触发 ⇒ 'evt:' || event_id；手动重跑 ⇒ 'rerun:' || 原 run id || ':' || 序号。
  idempotency_key varchar(320) NOT NULL CHECK(btrim(idempotency_key) <> ''),

  -- 触发事件类型（与规则档案一致，方便按事件筛）。
  trigger_event_type varchar(64) NOT NULL DEFAULT '',

  -- 谁触发的（人 / 服务 / 外部系统）。
  triggered_by varchar(120) NOT NULL DEFAULT '',

  -- 🔴 **触发上下文**（JSON 对象）：当时那份事件载荷的快照。
  --   「支持查看上下文」落点：失败之后要能还原「当时到底发生了什么」。
  event_payload jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- =========================================================================
  -- 结论
  -- =========================================================================
  --   SUCCEEDED     执行成功
  --   FAILED        执行失败
  --   SKIPPED       跳过（条件未命中 / 规则被停用 / 试运行不落副作用）
  --   TIMEOUT       超时
  --   NOT_ATTEMPTED 未执行（**执行器未接通**，见第 2 节）
  outcome text NOT NULL CHECK(outcome IN ('SUCCEEDED','FAILED','SKIPPED','TIMEOUT','NOT_ATTEMPTED')),

  -- 🔴 **来源**（如实说明这一行是谁写的）：
  --   ENGINE  由规则执行引擎写入（18.3.2 尚未交付 ⇒ 现阶段的行不会是这个来源）
  --   MANUAL  人工登记
  --   RERUN   手动重跑请求
  source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('ENGINE','MANUAL','RERUN')),

  -- 🔴 **试运行标记**：由**规则档案**推导（服务层读规则，不接受客户端自称）。
  --   试运行的执行**不产生真实副作用**，且**一个动作都没真跑**。
  dry_run boolean NOT NULL DEFAULT false,

  -- 🔴 本次执行**是否真的产生了副作用**（试运行时恒为 false）。
  side_effects_applied boolean NOT NULL DEFAULT false,

  -- 命中条件数 / 计划动作数 / 真正执行的动作数（数量，NUMERIC(19,6)，禁浮点）。
  matched_conditions numeric(19,6) NOT NULL DEFAULT 0 CHECK(matched_conditions >= 0),
  planned_actions numeric(19,6) NOT NULL DEFAULT 0 CHECK(planned_actions >= 0),
  executed_actions numeric(19,6) NOT NULL DEFAULT 0 CHECK(executed_actions >= 0),

  -- 🔴 **总耗时，整数毫秒**（禁浮点）。
  duration_ms bigint NOT NULL DEFAULT 0 CHECK(duration_ms >= 0),

  -- 失败 / 跳过的理由（**人可读**，写清「为什么、归谁」）。
  failure_reason varchar(500) NOT NULL DEFAULT '',
  failure_class text NOT NULL DEFAULT ''
    CHECK(failure_class IN ('','CONDITION_NOT_MATCHED','ACTION_FAILED','TIMEOUT','RULE_DISABLED',
                           'ENGINE_ERROR','EXECUTOR_UNAVAILABLE')),

  -- 错误码与错误信息（与逐步明细同一套口径）。
  error_code varchar(64) NOT NULL DEFAULT '',
  error_message varchar(1000) NOT NULL DEFAULT '',

  -- 🔴 **未接通原因**（outcome = 'NOT_ATTEMPTED' 时必填，见第 2 节）。
  not_attempted_reason varchar(500) NOT NULL DEFAULT '',

  -- =========================================================================
  -- 🔴 手动重跑：与原 run 的显式关联
  -- =========================================================================
  -- 指向被重跑的那条 run（事件触发的执行为空）。
  rerun_of_run_id uuid,

  -- 第几次重跑（同一原 run 内从 1 递增）。
  rerun_seq int CHECK(rerun_seq IS NULL OR rerun_seq >= 1),

  -- 谁要求重跑 / 什么时候要求的。
  rerun_requested_by uuid,
  rerun_requested_at timestamptz,

  -- 重跑理由（人可读：「为什么再跑一次」）。
  rerun_reason varchar(500) NOT NULL DEFAULT '',

  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 🔴 幂等键唯一（第 1 节 (b)）：事件触发与手动重跑共用一条约束。
  UNIQUE(tenant_id, rule_id, idempotency_key),

  -- 🔴 真 FK：执行日志挂在 18.3.1.1 的规则档案上（复合键含 tenant_id ⇒ 跨租户挂不上）。
  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id),

  CHECK(jsonb_typeof(event_payload) = 'object'),
  CHECK(finished_at >= started_at),
  -- 真正执行的动作数不得超过计划动作数。
  CHECK(executed_actions <= planned_actions),
  -- 🔴 **试运行 ⇒ 没有真实副作用、也没有动作真跑过**（与 V419 同一纪律）。
  CHECK(NOT dry_run OR (NOT side_effects_applied AND executed_actions = 0)),
  -- 🔴 声称产生了副作用 ⇒ 至少真的执行了一个动作。
  CHECK(NOT side_effects_applied OR executed_actions > 0),
  -- 失败必须写清原因（否则「失败了但不知道为什么」又是一种静默失败）。
  CHECK(outcome <> 'FAILED' OR btrim(failure_reason) <> ''),
  -- 失败分类与失败原因配套：分类非空必须有原因，反之亦然。
  CHECK((failure_class = '') = (btrim(failure_reason) = '')),
  -- 🔴 **未执行 ⇒ 必须写得出未接通原因**（第 2 节）。
  CHECK(outcome <> 'NOT_ATTEMPTED' OR btrim(not_attempted_reason) <> ''),
  -- 未接通原因只允许出现在「未执行」这一种结论上（否则「未执行」与「已执行」会同时成立）。
  CHECK(outcome = 'NOT_ATTEMPTED' OR btrim(not_attempted_reason) = ''),
  -- 🔴 **重跑关系自洽**：要么是事件触发（无重跑关联），要么是重跑（有原 run + 序号）。
  --   反过来也一样：带重跑关联的行必须是重跑来源。
  CHECK((rerun_of_run_id IS NULL) = (rerun_seq IS NULL)),
  CHECK((source = 'RERUN') = (rerun_of_run_id IS NOT NULL))
);

-- 🔴 **事件维度唯一**（第 1 节 (a)）：同一规则 + 同一事件 ⇒ 至多一条执行记录。
--   ⚠️ **部分唯一索引**（`WHERE rerun_of_run_id IS NULL`）：手动重跑是**新的一次执行**，
--   它复用原事件 id —— 若不排除，重跑会被这条约束挡住（那就成了「重跑不了」）。
CREATE UNIQUE INDEX platform_automation_rule_run_event_unique
  ON platform_automation_rule_run(tenant_id, rule_id, event_id)
  WHERE rerun_of_run_id IS NULL;

CREATE INDEX platform_automation_rule_run_by_rule
  ON platform_automation_rule_run(tenant_id, rule_id, created_at DESC, id);
CREATE INDEX platform_automation_rule_run_by_outcome
  ON platform_automation_rule_run(tenant_id, outcome, created_at DESC, id);
CREATE INDEX platform_automation_rule_run_by_event
  ON platform_automation_rule_run(tenant_id, trigger_event_type, created_at DESC, id);
CREATE INDEX platform_automation_rule_run_by_event_id
  ON platform_automation_rule_run(tenant_id, event_id);
CREATE INDEX platform_automation_rule_run_by_created
  ON platform_automation_rule_run(tenant_id, created_at DESC, id);
-- 重跑链：查「这条 run 被重跑过几次」。
CREATE INDEX platform_automation_rule_run_by_rerun_of
  ON platform_automation_rule_run(tenant_id, rerun_of_run_id, rerun_seq);

-- ---------------------------------------------------------------------------
-- 2. platform_automation_rule_run_step —— 逐步明细（只追加）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次执行里的**一步**（一次条件判定，或一次动作执行）。
CREATE TABLE platform_automation_rule_run_step (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 属于哪一次执行（🔴 真 FK，复合键含 tenant_id ⇒ 跨租户挂不上）。
  run_id uuid NOT NULL,

  -- 🔴 **第几步**（全序：同一 run 内不重复 ⇒ 步骤次序是确定的）。
  step_no int NOT NULL CHECK(step_no >= 1),

  -- 🔴 这一步是**条件判定**还是**动作执行**。
  step_kind text NOT NULL CHECK(step_kind IN ('CONDITION','ACTION')),

  -- ------------------------------------------------------------------------
  -- 条件判定（step_kind = 'CONDITION'）
  -- ------------------------------------------------------------------------
  -- 命中的是**哪条 condition**（18.3.1.1 的条件行）。
  -- ⚠️ **不建 FK**：条件行在规则被重新保存时会被整体替换删掉，
  --   建了 FK 就会把历史执行日志锁死（改一次规则，历史就看不了了）。
  --   ⇒ 只存**引用 + 快照**（seq / field_path 一起存下），历史永远读得到。
  condition_id uuid,
  condition_seq int,
  field_path varchar(200) NOT NULL DEFAULT '',
  operator varchar(32) NOT NULL DEFAULT '',
  expected_value varchar(500) NOT NULL DEFAULT '',
  actual_value varchar(500) NOT NULL DEFAULT '',

  -- 🔴 **条件命中情况**（命中与否）。
  --   条件步骤必须给出（NOT NULL 由 CHECK 钉死）；动作步骤没有「命中」这回事 ⇒ 允许为空。
  matched boolean,

  -- ------------------------------------------------------------------------
  -- 动作执行（step_kind = 'ACTION'）
  -- ------------------------------------------------------------------------
  -- 动作类型（与 18.3.1.1 的动作类型同一受控词表：调接口 / 改状态 / 建任务 / 发通知 / 生成单据）。
  action_type varchar(64) NOT NULL DEFAULT '',
  target_ref varchar(200) NOT NULL DEFAULT '',
  params jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- ------------------------------------------------------------------------
  -- 每步结果
  -- ------------------------------------------------------------------------
  --   SUCCEEDED     成功
  --   FAILED        失败
  --   SKIPPED       跳过
  --   NOT_MATCHED   条件未命中
  --   NOT_ATTEMPTED 未执行（执行器未接通）
  result text NOT NULL
    CHECK(result IN ('SUCCEEDED','FAILED','SKIPPED','NOT_MATCHED','NOT_ATTEMPTED')),

  -- 🔴 **每步耗时，整数毫秒**（禁浮点）。
  duration_ms bigint NOT NULL DEFAULT 0 CHECK(duration_ms >= 0),

  -- 错误码与错误信息。
  error_code varchar(64) NOT NULL DEFAULT '',
  error_message varchar(1000) NOT NULL DEFAULT '',

  -- 这一步**是否真的产生了副作用**。
  side_effect_applied boolean NOT NULL DEFAULT false,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一 run 内步号唯一 ⇒ 步骤次序确定（不靠 DB 返回顺序）。
  UNIQUE(tenant_id, run_id, step_no),

  FOREIGN KEY(tenant_id, run_id) REFERENCES platform_automation_rule_run(tenant_id, id),

  CHECK(jsonb_typeof(params) = 'object'),
  -- 🔴 条件步骤必须给出「命中与否」。
  CHECK(step_kind <> 'CONDITION' OR matched IS NOT NULL),
  -- 🔴 动作步骤必须给出动作类型（条件步骤没有动作类型）。
  CHECK(step_kind <> 'ACTION' OR btrim(action_type) <> ''),
  -- 🔴 失败步骤必须写清错误码与错误信息（否则「这一步失败了但不知道为什么」）。
  CHECK(result <> 'FAILED' OR (btrim(error_code) <> '' AND btrim(error_message) <> '')),
  -- 声称产生了副作用的步骤必须是成功的那一步。
  CHECK(NOT side_effect_applied OR result = 'SUCCEEDED')
);

CREATE INDEX platform_automation_rule_run_step_by_run
  ON platform_automation_rule_run_step(tenant_id, run_id, step_no);
CREATE INDEX platform_automation_rule_run_step_by_result
  ON platform_automation_rule_run_step(tenant_id, result, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. 只追加台账的触发器
-- ---------------------------------------------------------------------------
--
-- 🔴 用**触发器**而不是「少授一个权限码」：少授会被「迁移脚本用 owner 身份跑」绕开，
--    触发器不会（与 V419 的执行台账 / V421 的测试台账同一纪律）。
--
--    已结束的执行记录不可修改 —— 能改的执行历史等于没有执行历史。
--    ⇒ 这也是「重跑**不能**把原 run 改写成重跑结果」的物理保证。
CREATE FUNCTION refuse_platform_automation_rule_run_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_automation_rule_run 是只追加台账：已结束的执行记录不可修改（%）',
    TG_OP;
END;
$$;

CREATE FUNCTION refuse_platform_automation_rule_run_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_automation_rule_run 是只追加台账：已结束的执行记录不可删除（%）',
    TG_OP;
END;
$$;

CREATE TRIGGER guard_platform_automation_rule_run_update
  BEFORE UPDATE ON platform_automation_rule_run
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_automation_rule_run_update();

CREATE TRIGGER guard_platform_automation_rule_run_delete
  BEFORE DELETE ON platform_automation_rule_run
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_automation_rule_run_delete();

CREATE FUNCTION refuse_platform_automation_rule_run_step_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_automation_rule_run_step 是只追加台账：逐步明细不可修改（%）',
    TG_OP;
END;
$$;

CREATE FUNCTION refuse_platform_automation_rule_run_step_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_automation_rule_run_step 是只追加台账：逐步明细不可删除（%）',
    TG_OP;
END;
$$;

CREATE TRIGGER guard_platform_automation_rule_run_step_update
  BEFORE UPDATE ON platform_automation_rule_run_step
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_automation_rule_run_step_update();

CREATE TRIGGER guard_platform_automation_rule_run_step_delete
  BEFORE DELETE ON platform_automation_rule_run_step
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_automation_rule_run_step_delete();

-- ---------------------------------------------------------------------------
-- 4. 租户触发器兜底
-- ---------------------------------------------------------------------------
--
-- 🔴 RLS 挡的是「策略不匹配的行」，但**直连 SQL 绕过策略写入**这一类问题
--    仍需要一道独立兜底：写入方的 tenant_id 必须等于当前租户上下文。
--    （与 identity V384 的 alert_rule_tenant_guard 同一纪律。）
CREATE FUNCTION platform_automation_rule_run_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入规则执行记录'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '规则执行记录的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_automation_rule_run_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_automation_rule_run_tenant_guard() TO platform_app;

CREATE TRIGGER platform_automation_rule_run_tenant_guard_trigger
  BEFORE INSERT ON platform_automation_rule_run
  FOR EACH ROW EXECUTE FUNCTION platform_automation_rule_run_tenant_guard();

CREATE FUNCTION platform_automation_rule_run_step_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入规则执行逐步明细'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '规则执行逐步明细的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_automation_rule_run_step_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_automation_rule_run_step_tenant_guard() TO platform_app;

CREATE TRIGGER platform_automation_rule_run_step_tenant_guard_trigger
  BEFORE INSERT ON platform_automation_rule_run_step
  FOR EACH ROW EXECUTE FUNCTION platform_automation_rule_run_step_tenant_guard();

-- ===========================================================================
-- 5. 租户隔离（RLS）
-- ===========================================================================
--
-- 两张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V416 /
-- V418 / V419 / V421 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_automation_rule_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_run FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_run
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_automation_rule_run_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_run_step FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_run_step
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 6. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的执行日志与逐步明细、
-- 能写（登记一次执行 / 登记一条重跑请求）。
--
-- ⚠️ 两张表**不给 UPDATE / DELETE 授权**（与 V421 的测试台账同一纪律）：
--    「不可改」这件事在**授权层面**就已经成立，触发器是第二道防线。
--    少授一个权限码比「授了再靠触发器兜」更省事：漏授 UPDATE 时，
--    触发器也不会被误触发（触发器只在语句真的到达表上时才跑）。
GRANT SELECT,INSERT ON platform_automation_rule_run TO platform_app;
GRANT SELECT,INSERT ON platform_automation_rule_run_step TO platform_app;
