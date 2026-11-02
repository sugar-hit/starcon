-- ===========================================================================
-- V428 · automation_rule_effect —— 18.3.2.2「规则效果」
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.3「业务规则引擎」→ 18.3.2「执行」→ 18.3.2.2：
--
--   「**职责**：统计规则效果：触发次数、成功率、平均耗时、节省工时预估、误触发反馈
--      与规则优化建议。
--    **输入**：执行日志聚合。
--    **输出**：规则效果报表。
--    **关系**：上游 = 18.3.2.1；下游 = 18.3.1.1（优化依据）。
--    **误触发率高的规则要能快速定位并关闭**，自动化失控比没有自动化更危险。」
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移的边界（先说清楚，后面每张表都按这条边界设计）
-- ---------------------------------------------------------------------------
--
--   * **只读 18.3.2.1 的两张表**：
--       platform_automation_rule_run      （一次执行的明细台账）
--       platform_automation_rule_run_step （逐步明细）
--     本模块**一个字都不改**它们的结构 —— 每张事实表只有一个写入所有者
--     （那两张表的写入所有者是 18.3.2.1）。本模块只**聚合**它们。
--     ⚠️ 逐步明细（_step）在本模块**不参与聚合**：逐步明细是「一次执行内部」的
--       粒度，规则效果是「跨执行」的粒度；两者混在一起算出来的成功率是假的
--       （一次执行有 5 个步骤就会把执行次数放大 5 倍）。
--       本模块只在**下钻**时用 run 的 id 指回 18.3.2.1 的详情端点。
--
--   * **不 ALTER、不碰 18.3.1.1（V419）的五张表**（platform_automation_rule 等）：
--     只读规则档案取规则名与启用状态 ⇒ 两边各有各的写入所有者。
--
--   * **不做关闭动作**：蓝图说「误触发率高的规则要能快速定位并关闭」——
--     「**关闭**」这个动作归**下游 18.3.1.1 规则档案**（那里有 enable / disable
--     端点）。本模块只负责「**快速定位**」：给出规则 id + 规则名快照 +
--     指向规则档案的引用，并**明确写清**「关闭动作归 18.3.1.1，本模块不做」。
--     🔴 越界去改 18.3.1.1 的表 = 两张事实表抢同一个写入所有者。
--
--   * **统计的是「记录」，不是「真实执行」**：18.3.2「执行引擎」**尚未交付**
--     ⇒ 现阶段 run 行绝大多数是人工登记或「未执行」的重跑请求。
--     报表必须如实标注这一点（服务层的能力声明 + 前端的提示条都写明）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单（三张，全部是**本模块自己拥有**的表）
-- ---------------------------------------------------------------------------
--
--   platform_automation_rule_effect_baseline **节省工时预估口径**（人工基线）
--   platform_automation_rule_effect_feedback  **误触发反馈**（人工登记）
--   platform_automation_rule_effect_advice    **规则优化建议**（系统生成 + 人工登记）
--
-- 🔴 **节省工时预估为什么必须落一张「口径表」**：
--   蓝图要「节省工时预估」，但**预估不能是常量**。硬编码「一次执行省 3 分钟」
--   是 mock：数字好看，但没有任何依据、换条规则就是错的。
--   ⇒ 口径 = 「**这条规则**一次成功执行替代多少分钟人工」，由租户自己登记，
--     落在 `manual_minutes_per_run` 上，并且**必须写清来源**（`baseline_source`）
--     与**怎么算出来的**（`baseline_note`）。
--   ⇒ 服务层把这个口径（表、字段、公式、排除项）**一起返回**给前端，
--     前端显示「节省工时预估」时同时显示这行口径说明 ——
--     只给一个数字不给口径，那个数字就不能用于决策。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 五档结论不许压扁（蓝图加粗句的落点）
-- ---------------------------------------------------------------------------
--
-- 18.3.2.1 的 outcome 是五档：SUCCEEDED / FAILED / SKIPPED / TIMEOUT /
-- **NOT_ATTEMPTED（未执行）**。
--
-- 🔴 **「未执行」既不是成功也不是失败**。它必须**单列一档**：
--   * 压进「失败」⇒ 报表说「这条规则在失败」，其实它根本没跑（执行器未接通）；
--   * 压进「成功」⇒ 更糟：报表说「成功率 100%」，其实一次都没执行过。
--
-- 本模块的口径（服务层实现，列在这里以便评审）：
--
--   有效执行次数 rated_runs = SUCCEEDED + FAILED + TIMEOUT
--     （**跳过**是条件未命中 ⇒ 本来就不该执行；**未执行**是根本没跑 ⇒
--       两者都**不进分母**，否则成功率会失真）
--
--   成功率 success_rate = SUCCEEDED ÷ rated_runs
--     （分母为 0 时是 **NULL**，不是 0% ——「没有样本」与「成功率是零」是两件事）
--
--   平均耗时 avg_duration_ms = rated_runs 上的 duration_ms 平均值（整数毫秒）
--     （只在**真正跑过**的那些 run 上平均：未执行 / 跳过的 duration 是 0，
--       把它们算进去会把平均耗时拉到 0，报表会说「这条规则跑得飞快」）
--
--   误触发率 false_trigger_rate = 指向**有效执行**的误触发反馈 run 数 ÷ rated_runs
--     （分子分母同量纲；没有指向具体 run 的反馈单独统计，不混进分子）
--
--   节省工时预估 saved_minutes = 成功执行次数 × 该规则的 `manual_minutes_per_run`
--     （失败 / 跳过 / 超时 / 未执行**都不计**——没跑成的活没省下时间）
--
-- ---------------------------------------------------------------------------
-- 3. 数字与时间
-- ---------------------------------------------------------------------------
--
--   * 数量 / 分钟数 / 比率一律 `numeric(19,6)`（**禁浮点**）；比率由 SQL 的
--     **精确 numeric 除法 + round(...,6)** 得出，不是 double 算出来的。
--   * 耗时一律**整数毫秒**（`bigint`）。
--   * 时间列一律 `timestamptz`；服务层用 `OffsetDateTime` 绑定并在 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_automation_rule_effect_baseline —— 节省工时预估口径（人工基线）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「**这条规则**一次成功执行替代多少分钟人工」这条**口径**。
--
-- 🔴 **一条规则一条当前口径**（UNIQUE(tenant_id, rule_id)）：口径是「当前值」，
--   改口径 = 更新这一行（服务层 upsert），不是堆一串历史 ——
--   历史口径归「建议的依据」那一栏（evidence），不进这张表。
CREATE TABLE platform_automation_rule_effect_baseline (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 属于哪条规则（🔴 真 FK，复合键含 tenant_id ⇒ 跨租户挂不上）。
  rule_id uuid NOT NULL,

  -- 规则名快照：规则后来改名了，历史口径里仍看得到「当时说的是哪条规则」。
  rule_name varchar(120) NOT NULL DEFAULT '',

  -- 🔴 **人工基线**：一次**成功**执行替代多少分钟人工（数量，NUMERIC(19,6)，禁浮点）。
  --   口径的**唯一**来源 —— 没有这一行，节省工时预估就是空的（不是 0）。
  manual_minutes_per_run numeric(19,6) NOT NULL CHECK(manual_minutes_per_run >= 0),

  -- 🔴 **口径来源**（受控词表）：
  --   MANUAL_ESTIMATE  人工估算（运营按经验估的）
  --   OBSERVED_AVERAGE 实测均值（按历史人工处理时长算出来的）
  --   POLICY           公司口径（统一规定的人工基线）
  --   ⚠️ 不允许留空 —— 「不知道这个数哪来的」比没有这个数更糟。
  baseline_source text NOT NULL
    CHECK(baseline_source IN ('MANUAL_ESTIMATE','OBSERVED_AVERAGE','POLICY')),

  -- 🔴 **口径说明**：这个分钟数**怎么算出来的**（人可读）。
  --   例：「客服手工改一次价格约 4 分钟，按近 30 天 200 单平均得出」。
  baseline_note varchar(500) NOT NULL DEFAULT '',

  -- 口径生效时间（报表上标「这条口径从什么时候开始算」）。
  effective_from timestamptz NOT NULL,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 一条规则一条**当前**口径。
  UNIQUE(tenant_id, rule_id),

  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id),

  -- 口径说明必须写清「怎么算出来的」——只给一个分钟数不说来源，这个数不可用。
  CHECK(btrim(baseline_note) <> '' OR baseline_source = 'POLICY')
);

CREATE INDEX platform_automation_rule_effect_baseline_by_rule
  ON platform_automation_rule_effect_baseline(tenant_id, rule_id);
CREATE INDEX platform_automation_rule_effect_baseline_by_created
  ON platform_automation_rule_effect_baseline(tenant_id, created_at DESC, id);

-- ---------------------------------------------------------------------------
-- 2. platform_automation_rule_effect_feedback —— 误触发反馈（人工登记）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「有人指出**这条规则**（**这一次**）不该触发」这条反馈。
--
-- 🔴 蓝图加粗句：「误触发率高的规则要能快速定位并关闭」——
--   要「定位」就必须有「反馈指向哪条规则 / 哪一次执行」，
--   所以这里**必须**带 rule_id，run_id 可空（反馈可能只是「这条规则老乱触发」，
--   没有具体某一次）。
--   ⚠️ **没有指向具体 run 的反馈不计入误触发率的分子**（分子分母必须同量纲），
--   但它**照样计数并显示**（`unlinkedFeedbacks`）—— 少算的那部分必须让人看见。
CREATE TABLE platform_automation_rule_effect_feedback (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 反馈的是哪条规则（🔴 真 FK，复合键含 tenant_id ⇒ 跨租户挂不上）。
  rule_id uuid NOT NULL,

  -- 规则名快照。
  rule_name varchar(120) NOT NULL DEFAULT '',

  -- 指向**哪一次执行**（可空：反馈不一定能定位到某一次）。
  --   ⚠️ 有 FK ⇒ 反馈不可能指向一条不存在的执行记录。
  --   ⚠️ 另有一个**触发器**钉死「run_id 指向的 run 必须属于同一条 rule_id」
  --     （复合 FK 做不到：run 表上没有 (tenant_id, id, rule_id) 唯一约束，
  --      而那两张表都是 18.3.2.1 拥有的，**不能 ALTER**）。
  run_id uuid,

  -- 🔴 **误触发分类**（受控词表）—— 分类不同，建议方向不同：
  --   WRONG_CONDITION   条件写错了（该拦的没拦 / 不该拦的拦了）
  --   WRONG_SCOPE       生效范围不对（这条规则管到了不该管的店铺 / 仓库）
  --   TOO_FREQUENT      触发太频繁（每次事件都跑，运营受不了）
  --   UNEXPECTED_ACTION 执行了不该执行的动作
  --   OTHER             其它（**必须写清说明**）
  false_trigger_type text NOT NULL
    CHECK(false_trigger_type IN ('WRONG_CONDITION','WRONG_SCOPE','TOO_FREQUENT',
                                  'UNEXPECTED_ACTION','OTHER')),

  -- 反馈说明（人可读：「当时是什么情况」）。
  note varchar(500) NOT NULL DEFAULT '',

  -- 处理状态（**可改**：反馈会被人处理掉，所以这张表**不是**只追加台账）。
  status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ACCEPTED','DISMISSED')),

  reported_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id),
  FOREIGN KEY(tenant_id, run_id) REFERENCES platform_automation_rule_run(tenant_id, id),

  -- 🔴 「其它」类反馈**必须写清说明** —— 否则这条反馈没有可执行的信息。
  CHECK(false_trigger_type <> 'OTHER' OR btrim(note) <> '')
);

CREATE INDEX platform_automation_rule_effect_feedback_by_rule
  ON platform_automation_rule_effect_feedback(tenant_id, rule_id, created_at DESC, id);
CREATE INDEX platform_automation_rule_effect_feedback_by_run
  ON platform_automation_rule_effect_feedback(tenant_id, run_id);
CREATE INDEX platform_automation_rule_effect_feedback_by_status
  ON platform_automation_rule_effect_feedback(tenant_id, status, created_at DESC, id);

-- 🔴 **反馈指向的 run 必须属于同一条规则**（复合 FK 做不到，用触发器补一道）。
CREATE FUNCTION platform_automation_rule_effect_feedback_run_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  owner_rule uuid;
BEGIN
  IF NEW.run_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT r.rule_id INTO owner_rule
    FROM platform_automation_rule_run r
   WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.run_id;
  IF owner_rule IS NULL THEN
    RAISE EXCEPTION '误触发反馈指向的执行记录不存在或不属于本租户'
      USING ERRCODE = '23503';
  END IF;
  IF owner_rule IS DISTINCT FROM NEW.rule_id THEN
    RAISE EXCEPTION '误触发反馈指向的执行记录不属于该规则（run.rule_id = %，反馈.rule_id = %）',
      owner_rule, NEW.rule_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_automation_rule_effect_feedback_run_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_automation_rule_effect_feedback_run_guard() TO platform_app;

CREATE TRIGGER platform_automation_rule_effect_feedback_run_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_automation_rule_effect_feedback
  FOR EACH ROW EXECUTE FUNCTION platform_automation_rule_effect_feedback_run_guard();

-- ---------------------------------------------------------------------------
-- 3. platform_automation_rule_effect_advice —— 规则优化建议
-- ---------------------------------------------------------------------------
--
-- 一行 = 「针对某条规则的**一条优化建议**」，可定位到具体规则。
--
-- 🔴 **本模块不做关闭动作**：`target_module` 恒为 **18.3.1.1**（规则档案），
--   `target_ref` 指向规则档案里那条规则 —— 界面拿它跳到规则档案去停用。
--   「建议关闭」是建议，「关闭」是 18.3.1.1 的动作。
--
-- 🔴 **建议带依据**（`evidence` JSON 对象）：算这条建议用的**数字**原样存下来。
--   没有依据的建议是空话（「建议优化一下」谁都会说）。
CREATE TABLE platform_automation_rule_effect_advice (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 针对哪条规则（🔴 真 FK，复合键含 tenant_id ⇒ 跨租户挂不上）。
  rule_id uuid NOT NULL,

  -- 规则名快照（规则改名了，历史建议里仍看得到当时说的是哪条）。
  rule_name varchar(120) NOT NULL DEFAULT '',

  -- 🔴 **建议类型**（受控词表）：
  --   FALSE_TRIGGER_HIGH  误触发率过高 ⇒ **建议关闭或收紧条件**（蓝图加粗句）
  --   HIGH_FAILURE_RATE   失败率过高 ⇒ 建议排查动作 / 条件
  --   TIMEOUT_HIGH        超时偏多 ⇒ 建议调阈值 / 拆分动作
  --   NOT_ATTEMPTED_HIGH  大量「未执行」⇒ 执行引擎未接通（**不是规则的错**）
  --   SKIPPED_HIGH        大量跳过 ⇒ 条件可能过窄
  --   SLOW                平均耗时过长
  --   NO_BASELINE         缺人工基线口径 ⇒ 节省工时预估算不出来
  --   INSUFFICIENT_DATA   样本太少，结论不可靠
  --   PERFORMING_WELL     表现良好（正向建议：值得推广 / 保留）
  advice_type text NOT NULL
    CHECK(advice_type IN ('FALSE_TRIGGER_HIGH','HIGH_FAILURE_RATE','TIMEOUT_HIGH',
                           'NOT_ATTEMPTED_HIGH','SKIPPED_HIGH','SLOW','NO_BASELINE',
                           'INSUFFICIENT_DATA','PERFORMING_WELL')),

  -- 严重度（CRITICAL 最急）。
  severity text NOT NULL CHECK(severity IN ('CRITICAL','WARNING','INFO')),

  -- 建议标题 / 正文（人可读）。
  title varchar(120) NOT NULL,
  detail varchar(1000) NOT NULL DEFAULT '',

  -- 🔴 **依据**：算这条建议用的数字（JSON 对象），例：
  --   {"falseTriggerRuns":3,"ratedRuns":10,"falseTriggerRate":0.3}
  --   没有依据的建议是空话。
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 🔴 **来源**：
  --   SYSTEM  由本模块按当前聚合**自动生成**（可重新生成，幂等 upsert）
  --   MANUAL  人工登记的建议（系统刷新**不动**它）
  source text NOT NULL DEFAULT 'SYSTEM' CHECK(source IN ('SYSTEM','MANUAL')),

  -- 处理状态（可改：建议会被人采纳或忽略）。
  status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ADOPTED','IGNORED')),

  -- 🔴 **当前是否仍然成立**：系统建议在重新生成时，若某条建议不再成立，
  --   它会被置成 active = false（**不删**，历史建议要能复现），
  --   但不会继续出现在「当前建议」里。
  active boolean NOT NULL DEFAULT true,

  -- 🔴 **动作归属**：恒为 18.3.1.1（规则档案）——
  --   「关闭这条规则」是**下游**的动作，本模块**只给定位入口**。
  target_module varchar(16) NOT NULL DEFAULT '18.3.1.1',

  -- 定位引用（指向规则档案里那条规则的路径）。
  target_ref varchar(300) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id),

  CHECK(jsonb_typeof(evidence) = 'object'),
  -- 🔴 **建议必须有依据**（空对象 = 没有依据 = 空话）。
  CHECK(evidence <> '{}'::jsonb OR source = 'MANUAL'),
  -- 🔴 **动作归属写死**：本模块不做关闭动作。
  CHECK(target_module = '18.3.1.1')
);

-- 系统建议的幂等键：同一条规则 + 同一个建议类型 ⇒ 至多一条（重新生成是 upsert）。
CREATE UNIQUE INDEX platform_automation_rule_effect_advice_system_unique
  ON platform_automation_rule_effect_advice(tenant_id, rule_id, advice_type)
  WHERE source = 'SYSTEM';

CREATE INDEX platform_automation_rule_effect_advice_by_rule
  ON platform_automation_rule_effect_advice(tenant_id, rule_id, created_at DESC, id);
CREATE INDEX platform_automation_rule_effect_advice_by_status
  ON platform_automation_rule_effect_advice(tenant_id, status, severity, created_at DESC, id);
CREATE INDEX platform_automation_rule_effect_advice_by_type
  ON platform_automation_rule_effect_advice(tenant_id, advice_type, created_at DESC, id);

-- ---------------------------------------------------------------------------
-- 4. 租户触发器兜底（三张表各一个）
-- ---------------------------------------------------------------------------
--
-- 🔴 RLS 挡的是「策略不匹配的行」，但**直连 SQL 绕过策略写入**这一类问题
--    仍需要一道独立兜底：写入方的 tenant_id 必须等于当前租户上下文。
--    （与 identity V384 / platform V422 同一纪律。）
CREATE FUNCTION platform_automation_rule_effect_baseline_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入规则效果口径'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '规则效果口径的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_automation_rule_effect_baseline_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_automation_rule_effect_baseline_tenant_guard() TO platform_app;

CREATE TRIGGER platform_automation_rule_effect_baseline_tenant_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_automation_rule_effect_baseline
  FOR EACH ROW EXECUTE FUNCTION platform_automation_rule_effect_baseline_tenant_guard();

CREATE FUNCTION platform_automation_rule_effect_feedback_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入误触发反馈'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '误触发反馈的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_automation_rule_effect_feedback_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_automation_rule_effect_feedback_tenant_guard() TO platform_app;

CREATE TRIGGER platform_automation_rule_effect_feedback_tenant_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_automation_rule_effect_feedback
  FOR EACH ROW EXECUTE FUNCTION platform_automation_rule_effect_feedback_tenant_guard();

CREATE FUNCTION platform_automation_rule_effect_advice_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入规则优化建议'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '规则优化建议的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_automation_rule_effect_advice_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_automation_rule_effect_advice_tenant_guard() TO platform_app;

CREATE TRIGGER platform_automation_rule_effect_advice_tenant_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_automation_rule_effect_advice
  FOR EACH ROW EXECUTE FUNCTION platform_automation_rule_effect_advice_tenant_guard();

-- ===========================================================================
-- 5. 租户隔离（RLS）
-- ===========================================================================
--
-- 三张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 platform V377 / V419 / V422 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_automation_rule_effect_baseline ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_effect_baseline FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_effect_baseline
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_automation_rule_effect_feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_effect_feedback FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_effect_feedback
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_automation_rule_effect_advice ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_effect_advice FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_effect_advice
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 6. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的三张表、能写口径 / 反馈 / 建议。
--
-- ⚠️ **口径表与建议表给 UPDATE**（口径要能改、建议状态要能改）；
--    **反馈表也给 UPDATE**（反馈会被人处理成「已采纳 / 已忽略」）。
--    这三张表**不是**只追加台账（只追加的是 18.3.2.1 的两张 run 表）。
GRANT SELECT,INSERT,UPDATE ON platform_automation_rule_effect_baseline TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_automation_rule_effect_feedback TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_automation_rule_effect_advice TO platform_app;
