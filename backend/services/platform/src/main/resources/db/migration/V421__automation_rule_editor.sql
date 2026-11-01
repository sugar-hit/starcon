-- ===========================================================================
-- V421 · automation_rule_editor —— 18.3.1.2「规则编辑器」
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.3「业务规则引擎」→ 18.3.1「规则管理」→ 18.3.1.2：
--
--   「**职责**：可视化编辑规则：选择触发事件 → 配置条件（字段/操作符/值）→
--      编排动作（调接口/改状态/建任务/发通知/生成单据）→ 测试与保存。
--    **输入**：规则配置；测试样本数据。
--    **输出**：规则定义；测试运行结果。
--    **关系**：上游 = 18.3.1.1；下游 = 18.3.2 执行。
--    **规则必须支持用样本测试**，否则只能在生产上试错。」
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**只建一张表**（理由在第 1 节，先说清楚）
-- ---------------------------------------------------------------------------
--
--   platform_automation_rule_test_run —— 样本测试运行台账（**只追加**）
--
-- ⚠️ **规则档案本身不落在这个迁移里**：规则、条件、动作、启用范围四张表由
--   18.3.1.1（V419）拥有，**每张事实表只有一个写入所有者**（AGENTS.md「模块边界纪律」）。
--   本模块**不另建一套规则表**，编辑器保存时**委托**给 18.3.1.1 的登记入口 ——
--   两套规则表意味着「编辑器的规则」与「规则列表的规则」会分叉，
--   而分叉出来的规则永远不会出现在执行引擎的视野里。
--
--   本迁移新增的**唯一**新事实是「**用样本测过一遍**」这件事：
--   测的什么配置、拿什么样本测的、逐条条件命中与否、动作计划是什么。
--
-- ---------------------------------------------------------------------------
-- 1. 为什么测试运行结果必须落库（不是「返回一下就行」）
-- ---------------------------------------------------------------------------
--
-- 蓝图把「测试运行结果」列为本模块的**输出**。落库有四个具体好处：
--
--   * **可重放**：台账里存着**被测配置的快照**与**样本载荷**，
--     同一份快照 + 同一个样本 ⇒ 同一个结论（卷 18 关键约束：
--     「规则引擎必须**幂等、可重放、可追溯**，同一触发条件重复执行结果一致」）。
--     没有快照，就只剩一句「当时好像是过的」—— 那不是可追溯。
--   * **可追责**：测试是**用真实租户会话**发起的，出了问题要能查到是谁测的。
--   * **可核对**：求值明细是**真求值**出来的（复用 18.3.1.1 的预演求值），
--     不是前端算的、也不是写死的。
--   * **坏配置要留痕**：一条规则反复测不过（字段路径写错、样本缺字段），
--     台账里那串「未命中 + 原因」就是证据。
--
-- ⚠️ **测试运行不是执行**：18.3.2「执行」是**下游**、尚未交付 ⇒
--   本表**不记录任何副作用**。表里没有 side_effects_applied 列 ——
--   **没有这个列，就不可能声称产生过副作用**（对比 18.3.1.1 的执行台账有这一列，
--   因为那边登记的是真实执行结论；这边登记的是**测试**，测试一律不落副作用）。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 本模块最核心的正确性要求：测试必须真的跑条件判定
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句：「**规则必须支持用样本测试**，否则只能在生产上试错。」
--
-- 这条要求在本迁移里被钉成**三个物理事实**：
--
--   (a) **样本载荷与配置快照都落库**（`sample_payload` / `config_snapshot`）——
--       没有它们，「这次测试」就不可重放，也不可核对。
--   (b) **逐条求值明细落库**（`evaluation`，JSON 对象）——
--       命中 / 未命中、期望值、实际值、原因，逐条都在。
--       「命中 / 未命中」不是算出来的布尔，是**求值结果**。
--   (c) **命中计数与布尔标记由 DB 层 CHECK 钉成永远一致**：
--       `matched = (matched_conditions = total_conditions)`
--       ⇒ 「标记命中但一条都没命中」在数据层**不可能**出现。
--
-- ---------------------------------------------------------------------------
-- 3. 为什么 rule_id 允许为空（草稿测试）
-- ---------------------------------------------------------------------------
--
-- 蓝图把「测试与保存」并列为编排流水线的**最后一步** —— 也就是说：
-- **要能测一条还没保存的规则**，否则「先测后存」这个用法根本走不通。
--
--   * `rule_id` 有值 ⇒ 测的是**已保存**规则（编辑既有规则时的回归测试）。
--   * `rule_id` 为空 ⇒ 测的是**草稿**（新建规则时还没落档的那份配置）。
--
-- 之所以敢让它是空的，是因为「这次测的是哪一版配置」由
-- `config_snapshot` 承担 —— 草稿测试同样**可重放、可核对**。
--
-- ⚠️ 有 `rule_id` 时仍然建**真 FK**（指向 18.3.1.1 的规则表，复合键含 tenant_id）：
--   测试永远挂在一**条真实存在的规则**上，挂不上规则的测试是查无实据的。
--   FK 用 MATCH SIMPLE 语义 ⇒ rule_id 为空时该约束自然跳过。
--
-- ⚠️ **不用 ON DELETE CASCADE**：本表是只追加台账，规则被删时测试历史不该跟着消失 ——
--   「历史要能复现」与「能改能删的历史」只能选一个，这里选前者。
--   删除一条有测试历史的规则会被 FK 挡住（这正是我们要的）。
--
-- ---------------------------------------------------------------------------
-- 4. 数量与时间
-- ---------------------------------------------------------------------------
--
--   * 数量（条件总数 / 命中数 / 计划动作数）一律 `numeric(19,6)`（禁浮点）。
--   * 时间列一律 `timestamptz`；服务层用 `OffsetDateTime` 绑定并在 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_automation_rule_test_run —— 样本测试运行台账（只追加）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「用样本把某份规则配置测了一遍」这个事实。
CREATE TABLE platform_automation_rule_test_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 被测的规则。🔴 **可空**：为空 = 测的是**草稿**（规则还没保存，见第 3 节）；
  --   有值时必须指向本租户名下**真实存在**的一条规则（复合 FK 兜底）。
  rule_id uuid,

  -- 被测规则的名称（草稿测试时取草稿里的名字）—— 列表页要显示「测的是哪条规则」。
  rule_name varchar(120) NOT NULL DEFAULT '',

  -- 同一被测对象内的测试序号（从 1 递增）。
  run_no int NOT NULL CHECK(run_no >= 1),

  -- 被测配置触发的事件（与规则档案一致，方便按事件筛测试历史）。
  trigger_event_type varchar(64) NOT NULL,
  trigger_schema_version int NOT NULL DEFAULT 1 CHECK(trigger_schema_version >= 1),

  -- 🔴 **被测配置的快照**（JSON 对象：条件 / 动作 / 启用范围）。
  --   有了它，「这次测的是哪一版配置」才说得清 ⇒ 可重放。
  config_snapshot jsonb NOT NULL,

  -- 🔴 **样本载荷**（JSON 对象）：当时喂进去的那份测试数据。
  sample_payload jsonb NOT NULL,

  -- 🔴 条件是否**全部**命中（与下面的计数由 CHECK 钉成永远一致）。
  matched boolean NOT NULL,

  -- 参与判定的条件数 / 命中数 / 计划动作数（数量，NUMERIC(19,6)，禁浮点）。
  total_conditions numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_conditions >= 0),
  matched_conditions numeric(19,6) NOT NULL DEFAULT 0 CHECK(matched_conditions >= 0),
  planned_actions numeric(19,6) NOT NULL DEFAULT 0 CHECK(planned_actions >= 0),

  -- 🔴 **逐条求值明细**（JSON 对象）：
  --   conditions —— 逐条条件：字段路径 / 操作符 / 期望值 / 实际值 / 命中与否 / 原因；
  --   actions    —— 动作计划：序号 / 类型 / 目标 / 参数；
  --   scope      —— 启用范围是否命中（含原因）；
  --   sideEffectMode —— 副作用模式（试运行 = 不产生真实副作用）。
  evaluation jsonb NOT NULL,

  -- 失败 / 未命中的理由（**人可读**，写清「为什么」）。
  failure_reason varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一被测对象内的测试序号唯一。
  -- ⚠️ **NULLS NOT DISTINCT**（PG 15+）：把 NULL 当成一个值参与唯一性判定 ——
  --   否则「草稿测试」（rule_id 为空）的 run_no 可以无限重复，序号就失去意义了。
  UNIQUE NULLS NOT DISTINCT (tenant_id, rule_id, run_no),

  -- 🔴 真 FK：测试挂不上真实规则的 ⇒ 不可能落库。
  --   MATCH SIMPLE：rule_id 为空时本约束自然跳过（草稿测试）。
  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id),

  CHECK(jsonb_typeof(config_snapshot) = 'object'),
  CHECK(jsonb_typeof(sample_payload) = 'object'),
  CHECK(jsonb_typeof(evaluation) = 'object'),
  -- 命中数不得超过参与判定的条件数。
  CHECK(matched_conditions <= total_conditions),
  -- 🔴 「标记命中」与「全部条件命中」**必须一致**（见第 2 节 (c)）。
  CHECK(matched = (matched_conditions = total_conditions))
);

CREATE INDEX platform_automation_rule_test_run_by_rule
  ON platform_automation_rule_test_run(tenant_id, rule_id, run_no DESC);
CREATE INDEX platform_automation_rule_test_run_by_event
  ON platform_automation_rule_test_run(tenant_id, trigger_event_type, created_at DESC);
CREATE INDEX platform_automation_rule_test_run_by_created
  ON platform_automation_rule_test_run(tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. 只追加台账的触发器
-- ---------------------------------------------------------------------------
--
-- 🔴 用**触发器**而不是「少授一个权限码」：少授会被「迁移脚本用 owner 身份跑」绕开，
--   触发器不会（与 V419 的执行台账、V418 的同步台账同一纪律）。
--
--   已结束的测试记录不可修改 —— 能改的测试历史等于没有测试历史。
CREATE FUNCTION refuse_platform_automation_rule_test_run_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_automation_rule_test_run 是只追加台账：已结束的测试记录不可修改（%）',
    TG_OP;
END;
$$;

CREATE FUNCTION refuse_platform_automation_rule_test_run_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_automation_rule_test_run 是只追加台账：已结束的测试记录不可删除（%）',
    TG_OP;
END;
$$;

CREATE TRIGGER guard_platform_automation_rule_test_run_update
  BEFORE UPDATE ON platform_automation_rule_test_run
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_automation_rule_test_run_update();

CREATE TRIGGER guard_platform_automation_rule_test_run_delete
  BEFORE DELETE ON platform_automation_rule_test_run
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_automation_rule_test_run_delete();

-- ===========================================================================
-- 3. 租户隔离（RLS）
-- ===========================================================================
--
-- tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V416 /
-- V418 / V419 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_automation_rule_test_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_test_run FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_test_run
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 4. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的测试台账、能写（登记一次测试）。
--
-- ⚠️ 只追加台账**不给 UPDATE / DELETE 授权** —— 与 V419 的执行台账不同（那边给全了
--    再用触发器兜底），这里**从授权层面就不给**，触发器是第二道防线。
--    两种做法都能钉死「不可改」，但少授一个权限码更省事：漏授 UPDATE 时，
--    触发器也不会被误触发（触发器只在语句真的到达表上时才跑）。
GRANT SELECT,INSERT ON platform_automation_rule_test_run TO platform_app;
