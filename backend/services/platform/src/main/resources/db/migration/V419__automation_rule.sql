-- ===========================================================================
-- V419 · automation_rule —— 18.3.1.1「规则列表」
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.3「业务规则引擎」→ 18.3.1「规则管理」→ 18.3.1.1：
--
--   「**职责**：管理自动化规则：规则名称、触发事件、条件（过滤）、动作（多步）、优先级、
--      启用范围（租户/店铺/仓库）、试运行开关、执行统计。
--    **输入**：规则增删改；启用停用；排序。
--    **输出**：规则档案 + 执行统计。
--    **关系**：上游 = 各业务事件；下游 = 18.3.1.2 编辑器、18.3.2.1 执行日志。
--    **规则要能看出最后一次执行结果**，否则坏规则静默失败无人察觉。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个迁移**刻意不做**什么（先说清楚边界，后面每张表都按这条边界设计）
-- ---------------------------------------------------------------------------
--
--   * **不执行规则**。18.3.2「执行」是**下游**（18.3.2.1 执行日志 / 18.3.2.2 规则效果
--     尚未交付），本模块是**规则管理**（档案 + 统计），不冒充执行引擎。
--     ⇒ 执行台账 `platform_automation_rule_execution` 是**执行结果的登记台账**，
--       `source` 字段如实区分「谁写的这一行」，不伪造「引擎跑过了」。
--
--   * **不替业务域建事件**。上游「各业务事件」住在平台事件注册表
--     （`platform_webhook_event_type`，18.1.2.1 事件订阅交付）——
--     本模块的 `trigger_event_type` **必须**命中注册表里 ACTIVE 的那一行，
--     否则「配了一条永远不会被触发的规则」这种坏配置在数据层就被禁掉。
--
--   * **不抄 channel / warehouse 的表**。启用范围里的店铺 / 仓库是**只读引用**
--     （AGENTS.md「模块边界纪律」：禁止跨服务 SQL / 跨库 FK / 共享视图）——
--     本模块只经授权 HTTP 端点只读上游目录，范围表里只存 uuid 这一个引用。
--
--   * **不删规则的执行历史**。执行台账是**只追加**的（触发器钉死），
--     规则档案上的统计指针由服务层显式更新。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单（五张）
-- ---------------------------------------------------------------------------
--
--   platform_automation_rule         **规则档案**（含执行统计快照 + 坏规则标记）
--   platform_automation_rule_condition **条件（过滤）** —— 逐条
--   platform_automation_rule_action   **动作（多步）** —— 逐条，有 seq
--   platform_automation_rule_scope    **启用范围**（租户 / 店铺 / 仓库）—— 逐条
--   platform_automation_rule_execution **执行统计台账**（只追加）
--
-- 五张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 本模块最核心的正确性要求：坏规则不许静默失败
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句：「**规则要能看出最后一次执行结果**，否则坏规则静默失败无人察觉。」
--
-- 这条要求在本迁移里被钉成**四个物理事实**，而不是一句注释：
--
--   (a) **最后一次执行结果存在规则自己这一行上**：
--       `last_run_at` / `last_run_status` / `last_run_failure_reason` /
--       `last_run_execution_id` 是**规则档案的列**，不是「有没有执行过」的布尔位。
--       ⇒ 打开规则列表就能看到「上次什么时候跑的、成了还是败了、为什么败」。
--
--   (b) **坏规则有反面判据**：`consecutive_failure_count` + `consecutive_failure_threshold`
--       + `failing` 三件套。
--       `failing` 与 `consecutive_failure_count >= consecutive_failure_threshold`
--       由 **CHECK** 钉死**永远一致** —— 统计口径和标记不可能对不上。
--       ⇒ 一条连续失败的规则**必然**能被筛出来（列表可按 `failing` 过滤），
--         而不是「悄悄地把规则状态改回正常」。
--       ⚠️ 「改回正常」在本模块里**物理上不成立**：登记执行结果的 SQL
--         只更新统计列，**不碰** `enabled` / `status` —— 停用的规则不会因为一次
--         执行失败或成功而自己变回启用。
--
--   (c) **试运行开关是独立状态**：`dry_run` 是**自己的列**，与 `enabled` / `status`
--       互不影响（一条规则可以「启用 + 试运行」「停用 + 试运行」）。
--       试运行 ⇒ 该规则的执行行**不许声称产生过真实副作用**，
--       且 `executed_actions` 必须为 0（**一个动作都没真跑**）。
--       这条由**表级 CHECK** + **触发器**双重钉死（见第 5 节）。
--
--   (d) **启用范围是真约束**：`platform_automation_rule_scope` 的行**参与过滤**
--       （服务层的「哪些规则对这家店 / 这个仓生效」查询真的 JOIN 这张表），
--       不是只存一个展示用的字段。范围行还有 **CHECK**：租户级范围不许带引用，
--       店铺 / 仓库级范围**必须**带引用 —— 「范围是店铺」却没写是哪家店，
--       等于没有范围。
--
-- ---------------------------------------------------------------------------
-- 3. 优先级与排序
-- ---------------------------------------------------------------------------
--
--   `priority` 越小越先执行（1..1000）。
--   列表排序 **必须** 是全序：`ORDER BY priority, created_at, id` ——
--   `id` 是全局唯一主键 ⇒ 相同优先级的规则也有**确定的**次序，
--   不靠 DB 返回顺序（那是未定义行为，两次查询可能不同）。
--
-- ---------------------------------------------------------------------------
-- 4. 金额与数量
-- ---------------------------------------------------------------------------
--
--   * 本模块**不碰金额**（它管的是规则配置与执行统计，不是账）。
--   * 数量（执行次数 / 命中条件数 / 计划动作数 / 已执行动作数）一律 `numeric(19,6)`
--     （AGENTS.md 明文口径，禁浮点）。
--   * 时间列一律 `timestamptz`；服务层用 `OffsetDateTime` 绑定并在 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_automation_rule —— 规则档案
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条自动化规则在本租户下的一份档案。
--
-- 🔴 **系统零预置行**：不替用户默认一条「订单超库存就通知」的规则 ——
--   规则由租户自己登记（与 18.2.3.1 的连接配置同一纪律）。
CREATE TABLE platform_automation_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则名称（租户内唯一：两个同名规则会让运营在列表里分不清是哪条）。
  name varchar(120) NOT NULL,

  -- 触发事件（上游 = 各业务事件）。
  -- ⚠️ **不建外键**到 `platform_webhook_event_type`（那是本服务的另一张表，
  --   但规则与事件定义之间是**引用**关系：事件定义可能被停用）——
  --   真正的约束由**服务层**保证「必须是注册表里 ACTIVE 的那一行」。
  trigger_event_type varchar(64) NOT NULL,
  trigger_schema_version int NOT NULL DEFAULT 1 CHECK(trigger_schema_version >= 1),

  description varchar(500) NOT NULL DEFAULT '',

  -- 优先级：数字越小越先执行（1..1000）。
  priority int NOT NULL DEFAULT 100 CHECK(priority >= 1 AND priority <= 1000),

  -- 启用状态（启用 / 停用）。
  -- ⚠️ `enabled` 与 `status` 是**两个维度**：`status` 是档案的生命周期
  --   （ACTIVE / DISABLED），`enabled` 是「当前是否参与匹配」。
  --   与 `dry_run` **三者互相独立**（见第 2 节 (c)）。
  enabled boolean NOT NULL DEFAULT true,
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),

  -- 🔴 **试运行开关（独立状态）**：开启后这条规则**不产生真实副作用**。
  --   与 `enabled` / `status` 互不影响。
  dry_run boolean NOT NULL DEFAULT false,

  -- 连续失败阈值：连续失败达到这个数就判定为「坏规则」（`failing = true`）。
  consecutive_failure_threshold int NOT NULL DEFAULT 3
    CHECK(consecutive_failure_threshold >= 1 AND consecutive_failure_threshold <= 100),

  -- =========================================================================
  -- 🔴 执行统计（蓝图加粗句的落点）—— 存在**规则自己这一行**上
  -- =========================================================================
  -- 最后一次执行：**时间 / 结果 / 失败原因**三件套，不是「有没有执行过」的布尔位。
  last_run_at timestamptz,
  last_run_status text NOT NULL DEFAULT ''
    CHECK(last_run_status IN ('','SUCCEEDED','FAILED','SKIPPED','TIMEOUT')),
  last_run_failure_reason varchar(500) NOT NULL DEFAULT '',
  last_run_execution_id uuid,

  -- 累计计数（数量，NUMERIC(19,6)，禁浮点）。
  run_count numeric(19,6) NOT NULL DEFAULT 0 CHECK(run_count >= 0),
  success_count numeric(19,6) NOT NULL DEFAULT 0 CHECK(success_count >= 0),
  failure_count numeric(19,6) NOT NULL DEFAULT 0 CHECK(failure_count >= 0),

  -- 🔴 连续失败计数 + 坏规则标记。
  --   CHECK 把 `failing` 与 `consecutive_failure_count >= 阈值` 钉成**永远一致**：
  --   统计口径和标记不可能对不上（否则运营会看到「标记是坏的但计数是 0」）。
  consecutive_failure_count int NOT NULL DEFAULT 0 CHECK(consecutive_failure_count >= 0),
  failing boolean NOT NULL DEFAULT false,
  -- 第一次进入「坏规则」状态的时刻（用于「这条从什么时候开始一直坏」）。
  failing_since timestamptz,

  remark varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, name),

  CHECK(btrim(name) <> '' AND length(name) <= 120),
  CHECK(btrim(trigger_event_type) <> '' AND length(trigger_event_type) <= 64),
  -- 🔴 坏规则标记与连续失败计数**必须一致**（见上）。
  CHECK(failing = (consecutive_failure_count >= consecutive_failure_threshold)),
  -- 「坏规则」必须有「从什么时候开始坏」的时刻；不是坏规则就不许有。
  CHECK((failing AND failing_since IS NOT NULL) OR (NOT failing AND failing_since IS NULL)),
  -- 累计成功 + 累计失败不得超过累计执行数。
  CHECK(success_count + failure_count <= run_count)
);

-- 🔴 列表排序的**全序**索引：priority → created_at → id。
--   `id` 保证全序 ⇒ 相同优先级的规则次序**确定**（不靠 DB 返回顺序）。
CREATE INDEX platform_automation_rule_by_order
  ON platform_automation_rule(tenant_id, priority, created_at, id);
CREATE INDEX platform_automation_rule_by_event
  ON platform_automation_rule(tenant_id, trigger_event_type, priority, created_at, id);
CREATE INDEX platform_automation_rule_by_failing
  ON platform_automation_rule(tenant_id, failing, priority, created_at, id);
CREATE INDEX platform_automation_rule_by_status
  ON platform_automation_rule(tenant_id, status, enabled, priority, created_at, id);

-- ---------------------------------------------------------------------------
-- 2. platform_automation_rule_condition —— 条件（过滤）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个条件项。规则命中 = **全部** enabled 条件都成立（AND 语义）。
--
-- 值的存储刻意拆成三列（value_text / value_number / value_bool）：
--   · 与操作符无关的值一律走 value_text；
--   · 需要数值比较的走 value_number（NUMERIC(19,6)，禁浮点）；
--   · 需要真假比较的走 value_bool。
-- 拆开的理由：把「3.14」塞进 text 再拿去比大小，早晚会踩到字符串比较的坑。
CREATE TABLE platform_automation_rule_condition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_id uuid NOT NULL,

  -- 条件在规则内的序号（从 1 递增）。
  seq int NOT NULL CHECK(seq >= 1),

  -- 事件载荷里的字段路径（如 `order.totalAmount`、`shopId`）。
  field_path varchar(200) NOT NULL,

  -- 比较操作符（受控词表）。
  operator text NOT NULL
    CHECK(operator IN (
      'EQ','NE','GT','GE','LT','LE',            -- 相等 / 不等 / 大小比较
      'CONTAINS','STARTS_WITH','ENDS_WITH',      -- 文本包含
      'IN',                                      -- 属于（逗号分隔的候选集）
      'BETWEEN',                                 -- 区间（两个值用 `~` 分隔）
      'IS_NULL','NOT_NULL','EXISTS'              -- 空值 / 存在
    )),

  value_text varchar(500) NOT NULL DEFAULT '',
  value_number numeric(19,6),
  value_bool boolean,

  enabled boolean NOT NULL DEFAULT true,
  remark varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一规则内序号唯一 ⇒ 条件顺序确定。
  UNIQUE(tenant_id, rule_id, seq),

  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(field_path) <> '' AND length(field_path) <= 200),
  -- 需要数值比较的操作符必须给数值；不需要的不许给（避免两套值打架）。
  CHECK(
    (operator IN ('GT','GE','LT','LE') AND value_number IS NOT NULL)
    OR (operator NOT IN ('GT','GE','LT','LE'))
  ),
  -- BETWEEN 要两个值；IN 至少一个候选；IS_NULL / NOT_NULL / EXISTS 不要值。
  CHECK(operator <> 'BETWEEN' OR (btrim(value_text) <> '' AND position('~' in value_text) > 0)),
  CHECK(operator <> 'IN' OR btrim(value_text) <> ''),
  CHECK(operator NOT IN ('IS_NULL','NOT_NULL','EXISTS') OR
        (value_text = '' AND value_number IS NULL AND value_bool IS NULL))
);

CREATE INDEX platform_automation_rule_condition_by_rule
  ON platform_automation_rule_condition(tenant_id, rule_id, seq);

-- ---------------------------------------------------------------------------
-- 3. platform_automation_rule_action —— 动作（多步）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个动作步骤。`seq` 决定**执行顺序**（多步 ⇒ 有序，不是集合）。
CREATE TABLE platform_automation_rule_action (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_id uuid NOT NULL,

  seq int NOT NULL CHECK(seq >= 1),

  -- 动作类型（蓝图 18.3.1.2 点名的五类：调接口 / 改状态 / 建任务 / 发通知 / 生成单据）。
  action_type text NOT NULL
    CHECK(action_type IN (
      'CALL_API',            -- 调接口
      'UPDATE_STATUS',       -- 改状态
      'CREATE_TASK',         -- 建任务
      'SEND_NOTIFICATION',   -- 发通知
      'CREATE_DOCUMENT'      -- 生成单据
    )),

  -- 动作目标（接口路径 / 对象类型 / 通知模板码 / 单据类型…）。
  target_ref varchar(200) NOT NULL DEFAULT '',

  -- 动作参数（JSON 对象）。本模块**不解释**它 —— 参数语义归 18.3.1.2 编辑器与
  --   18.3.2 执行；但它必须存在，否则「动作」只是个没有内容的标签。
  params jsonb NOT NULL DEFAULT '{}'::jsonb,

  enabled boolean NOT NULL DEFAULT true,
  remark varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_id, seq),

  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id) ON DELETE CASCADE,

  -- 参数必须是 JSON **对象**（不是数组、不是标量）——动作参数是一组键值。
  CHECK(jsonb_typeof(params) = 'object')
);

CREATE INDEX platform_automation_rule_action_by_rule
  ON platform_automation_rule_action(tenant_id, rule_id, seq);

-- ---------------------------------------------------------------------------
-- 4. platform_automation_rule_scope —— 启用范围（租户 / 店铺 / 仓库）
-- ---------------------------------------------------------------------------
--
-- 一行 = 规则生效范围里的一个对象。
--
-- 🔴 **范围是真约束**：服务层的「哪些规则对这家店 / 这个仓生效」查询真的
--    JOIN 这张表；一条没有 TENANT 范围行的规则**不会**对任何店铺 / 仓库生效。
--
-- ⚠️ 店铺 / 仓库是**只读引用**（上游 channel / warehouse 服务）：
--    不建跨库外键、不抄上游档案（AGENTS.md「模块边界纪律」）。
CREATE TABLE platform_automation_rule_scope (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_id uuid NOT NULL,

  -- 范围类别：
  --   TENANT    整个租户（**不带引用**）
  --   SHOP      某家店铺（带 channel 店铺 id）
  --   WAREHOUSE 某个仓库（带 warehouse 仓库 id）
  scope_type text NOT NULL CHECK(scope_type IN ('TENANT','SHOP','WAREHOUSE')),

  -- 引用（店铺 id / 仓库 id）。TENANT 范围**必须**为 NULL。
  scope_ref uuid,

  -- 人可读标签（上游对象的编码 / 名称快照，方便列表直接显示）。
  scope_label varchar(120) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 「范围是店铺」却没写是哪家店 = **没有范围** ⇒ 在数据层禁掉。
  CHECK(
    (scope_type = 'TENANT' AND scope_ref IS NULL)
    OR (scope_type IN ('SHOP','WAREHOUSE') AND scope_ref IS NOT NULL)
  )
);

-- 同一规则内**同一个对象**不许重复登记（`coalesce` 处理 scope_ref 可空的 TENANT 行：
--   PG 里 NULL != NULL，普通 UNIQUE 约束管不住它）。
CREATE UNIQUE INDEX platform_automation_rule_scope_unique
  ON platform_automation_rule_scope(
    tenant_id, rule_id, scope_type,
    coalesce(scope_ref, '00000000-0000-0000-0000-000000000000'::uuid)
  );
CREATE INDEX platform_automation_rule_scope_by_rule
  ON platform_automation_rule_scope(tenant_id, rule_id, scope_type, scope_ref);
CREATE INDEX platform_automation_rule_scope_by_ref
  ON platform_automation_rule_scope(tenant_id, scope_type, scope_ref);

-- ---------------------------------------------------------------------------
-- 5. platform_automation_rule_execution —— 执行统计台账（只追加）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次规则执行尝试的结论。
--
-- 🔴 **只追加**：已结束的执行记录不可修改（与 18.2.3.1 的同步台账同一纪律）——
--   能改的执行历史等于没有执行历史。由**触发器**钉死，不是靠少授一个权限码。
--
-- 🔴 **试运行 ⇒ 不产生真实副作用**（本表级 CHECK + 第 6 节的触发器）：
--   · `dry_run = true` ⇒ `side_effects_applied` 必须是 false；
--   · `dry_run = true` ⇒ `executed_actions` 必须是 0（**一个动作都没真跑**）。
--   ⇒ 「试运行的规则跑过 12 次」与「试运行的规则一个动作都没真跑」这两件事
--     **不可能**同时被误读。
CREATE TABLE platform_automation_rule_execution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 指明这次执行属于**哪条规则**（统计口径的唯一来源）。
  rule_id uuid NOT NULL,

  -- 同一规则内的运行序号（从 1 递增）。
  run_no int NOT NULL CHECK(run_no >= 1),

  -- 结论：
  --   SUCCEEDED 执行成功
  --   FAILED    执行失败
  --   SKIPPED   跳过（条件未命中 / 规则被停用 / 试运行不落副作用）
  --   TIMEOUT   超时
  status text NOT NULL CHECK(status IN ('SUCCEEDED','FAILED','SKIPPED','TIMEOUT')),

  -- 来源（**如实**说明这一行是谁写的）：
  --   ENGINE  由规则执行引擎写入
  --   MANUAL  人工登记（**没有**经过本系统的执行引擎）
  -- ⚠️ 18.3.2 执行引擎**尚未交付** ⇒ 现阶段的行只能是 MANUAL；
  --   `source` 这一列就是「有没有引擎在跑」的**可查事实**，不是注释。
  source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('ENGINE','MANUAL')),

  -- 🔴 试运行标记（必须与规则档案的 dry_run 一致，由触发器钉死）。
  dry_run boolean NOT NULL DEFAULT false,

  -- 🔴 本次执行**是否真的产生了副作用**（试运行时恒为 false）。
  side_effects_applied boolean NOT NULL DEFAULT false,

  triggered_by varchar(120) NOT NULL DEFAULT '',
  trigger_event_type varchar(64) NOT NULL DEFAULT '',

  -- 命中条件数 / 计划动作数 / 真正执行的动作数（数量，NUMERIC(19,6)，禁浮点）。
  matched_conditions numeric(19,6) NOT NULL DEFAULT 0 CHECK(matched_conditions >= 0),
  planned_actions numeric(19,6) NOT NULL DEFAULT 0 CHECK(planned_actions >= 0),
  executed_actions numeric(19,6) NOT NULL DEFAULT 0 CHECK(executed_actions >= 0),

  -- 失败 / 跳过的理由（**人可读**，写清「为什么、归谁」）。
  failure_reason varchar(500) NOT NULL DEFAULT '',
  failure_class text NOT NULL DEFAULT ''
    CHECK(failure_class IN ('','CONDITION_NOT_MATCHED','ACTION_FAILED','TIMEOUT','RULE_DISABLED','ENGINE_ERROR')),

  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一规则内的运行序号唯一。
  UNIQUE(tenant_id, rule_id, run_no),

  FOREIGN KEY(tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id) ON DELETE CASCADE,

  CHECK(finished_at >= started_at),
  -- 真正执行的动作数不得超过计划动作数。
  CHECK(executed_actions <= planned_actions),
  -- 🔴 **试运行 ⇒ 没有真实副作用、也没有动作真跑过**。
  CHECK(NOT dry_run OR (NOT side_effects_applied AND executed_actions = 0)),
  -- 🔴 声称产生了副作用 ⇒ 至少真的执行了一个动作。
  CHECK(NOT side_effects_applied OR executed_actions > 0),
  -- 失败必须写清原因（否则「失败了但不知道为什么」又是一种静默失败）。
  CHECK(status <> 'FAILED' OR btrim(failure_reason) <> ''),
  -- 失败分类与失败原因配套：分类非空必须有原因，反之亦然。
  CHECK((failure_class = '') = (btrim(failure_reason) = ''))
);

CREATE INDEX platform_automation_rule_execution_by_rule
  ON platform_automation_rule_execution(tenant_id, rule_id, run_no DESC);
CREATE INDEX platform_automation_rule_execution_by_status
  ON platform_automation_rule_execution(tenant_id, status, finished_at DESC);

-- ---------------------------------------------------------------------------
-- 6. 只追加台账 + 试运行一致性的触发器
-- ---------------------------------------------------------------------------
--
-- 🔴 用**触发器**而不是「少授一个权限码」：少授会被「迁移脚本用 owner 身份跑」绕开，
--    触发器不会（与 V418 的同步台账同一纪律）。
CREATE FUNCTION refuse_platform_automation_rule_execution_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_automation_rule_execution 是只追加台账：已结束的执行记录不可修改（%）',
    TG_OP;
END;
$$;

CREATE FUNCTION refuse_platform_automation_rule_execution_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_automation_rule_execution 是只追加台账：已结束的执行记录不可删除（%）',
    TG_OP;
END;
$$;

CREATE TRIGGER guard_platform_automation_rule_execution_update
  BEFORE UPDATE ON platform_automation_rule_execution
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_automation_rule_execution_update();

CREATE TRIGGER guard_platform_automation_rule_execution_delete
  BEFORE DELETE ON platform_automation_rule_execution
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_automation_rule_execution_delete();

-- 🔴 试运行标记必须与**规则档案**一致：
--    规则是试运行 ⇒ 这一行必须标 dry_run 且不许声称产生副作用；
--    规则不是试运行 ⇒ 这一行不许标 dry_run（否则「试运行」就成了可以随手标上的标签）。
CREATE FUNCTION guard_platform_automation_rule_execution_dry_run() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  rule_dry_run boolean;
BEGIN
  SELECT r.dry_run INTO rule_dry_run
    FROM platform_automation_rule r
   WHERE r.id = NEW.rule_id AND r.tenant_id = NEW.tenant_id;
  IF rule_dry_run IS NULL THEN
    RAISE EXCEPTION '规则不存在或不属于当前租户（rule_id = %）', NEW.rule_id;
  END IF;
  IF rule_dry_run AND NOT NEW.dry_run THEN
    RAISE EXCEPTION '规则 % 处于试运行状态：执行记录必须标 dry_run = true', NEW.rule_id;
  END IF;
  IF NOT rule_dry_run AND NEW.dry_run THEN
    RAISE EXCEPTION '规则 % 不是试运行状态：执行记录不许标 dry_run = true', NEW.rule_id;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER guard_platform_automation_rule_execution_dry_run
  BEFORE INSERT ON platform_automation_rule_execution
  FOR EACH ROW EXECUTE FUNCTION guard_platform_automation_rule_execution_dry_run();

-- ===========================================================================
-- 7. 租户隔离（RLS）
-- ===========================================================================
--
-- 五张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V416 / V418
-- 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_automation_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_automation_rule_condition ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_condition FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_condition
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_automation_rule_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_action FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_action
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_automation_rule_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_scope
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_automation_rule_execution ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_automation_rule_execution FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_automation_rule_execution
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 8. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的规则档案与执行统计、能写
-- （登记规则 / 改规则 / 启停 / 排序 / 登记执行结果）。
--
-- ⚠️ 只追加台账也给了 UPDATE / DELETE 授权，但**触发器**会拒绝它们 ——
--    「不可改」这件事由数据层的触发器钉死。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_automation_rule TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_automation_rule_condition TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_automation_rule_action TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_automation_rule_scope TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_automation_rule_execution TO platform_app;
