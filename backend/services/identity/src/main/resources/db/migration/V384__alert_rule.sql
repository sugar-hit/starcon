-- ===========================================================================
-- V384 · alert_rule —— 1.3.2.1「预警规则」（卷 01 · L3 1.3.2 预警 → 1.3.2.1）
-- ===========================================================================
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：用户自定义阈值预警（某 SKU 库存低于 N 天、某店铺销售额环比跌超 X%、
--            应收超期 Y 天）。系统预置跨境常用规则集，用户可改参数或关闭。
--   **输入**：规则定义（对象类型、度量、阈值、时间窗、比较方向）；各系统指标投影。
--   **输出**：规则命中记录 → 生成通知 + 工作台待办项；规则命中历史用于趋势观察。
--   **关系**：上游 = 13 指标、07 库存、12 应收；下游 = 1.1 待办、1.3 通知。
--            共享数据：投影指标。**规则引擎本体在 18 系统**，此处只做配置界面。
--
-- ===========================================================================
-- 一、为什么这两张表在 identity
-- ===========================================================================
--
-- 卷 01 §1.3「消息与预警」整域都在 identity：1.3.1.1 通知列表（notification_read_state
-- / notification_type_subscription，V373）与 1.3.1.2 通知偏好（user_preference，V336）
-- 都在 identity 的库里。预警规则是**同一域的第三块**，落同一处。
--
-- 刻意**不**复用 `user_preference`（V336）承载规则定义，理由与 V373 拒收已读状态
-- 同构：
--   1. **形状不匹配**：规则是「多条带结构的记录」，条数随时间无上界；
--      `user_preference` 的载荷上限是 16 KB / 单个数组 64 项
--      （UserPreferencePayloadRules），规则一多就会**静默写不进去**。
--   2. **写入语义不匹配**：`user_preference` 是**整份覆盖**（upsert 同一个 page_key），
--      而规则是**逐条增删改**（改一条阈值不该重写整份）。
--   3. **词表钉不住**：规则的五项定义（对象类型 / 度量 / 阈值 / 时间窗 / 比较方向）
--      必须是受控词表 + CHECK 约束，JSONB 载荷做不到在**数据层**钉住。
--
-- ===========================================================================
-- 二、预置规则集是**代码里的声明**，不落库（关键设计决定）
-- ===========================================================================
--
-- 蓝图：「系统预置跨境常用规则集，用户可改参数或关闭。」
--
-- 本迁移**不建**「预置规则定义表」。预置规则集写在代码里
-- （`AlertRuleCatalog`，照抄 `ReportingMetricCatalog` 的「口径唯一、租户改了就跟着改」
-- 做法）：改一次对所有租户生效。
--
-- **落库的只有三样东西**：
--   1. 租户对**某条预置规则的参数覆盖**（origin='PRESET'，rule_code 指向预置码）；
--   2. 租户**自建的规则**（origin='CUSTOM'，rule_code='CUSTOM-<12位十六进制>'）；
--   3. 租户的**关闭动作**（status='DISABLED'，同样只是一行覆盖）。
--
-- **生效值 = 预置默认值 ⨯ 租户覆盖（覆盖优先）**，在服务层合并；
-- 读生效值时 SQL 里**不许硬编码任何阈值** —— 阈值只能来自预置定义或该租户的覆盖行。
--
-- 代价与收益：预置集改了（新增一条 / 调整默认值），租户**没动过**的那条自动跟着变；
-- 租户**改过**的那条保留自己的覆盖（这是用户显式表达过的意图，不该被系统默认值悄悄改掉）。
-- 「恢复默认」= 删掉那行覆盖（POST /identity/alert-rules/{id}/reset）。
--
-- ===========================================================================
-- 三、alert_rule 的形状：覆盖语义（覆盖行）vs 自定义语义（整条）
-- ===========================================================================
--
-- 同一张表承载两种语义，**靠 origin 区分**：
--
--   origin='PRESET'（**覆盖行**）
--     · 定义列**可空**：NULL = 「本租户没改这一项，用预置默认值」。
--     · 唯一作用是「改参数」或「关闭」。
--     · CHECK 保证它**不是空壳**：要么至少覆盖了一项，要么 status='DISABLED'
--       （只关不改也是一种有意义的覆盖）。
--
--   origin='CUSTOM'（**整条**）
--     · 定义列**全部必填**（CHECK 钉死）：缺一项就是一条不完整的规则，
--       界面上没法渲染、引擎也算不出来。
--
-- 覆盖语义带来的能力：改阈值时**只写 threshold 这一列**，
-- 预置的度量 / 时间窗 / 比较方向继续生效（不用把整套定义抄一遍，
-- 也就不会出现「改了阈值顺手把度量改了」的副作用）。
--
-- ===========================================================================
-- 四、五项定义在数据层被钉死（不是自由字符串）
-- ===========================================================================
--
-- 蓝图标名的五项 —— 对象类型 / 度量 / 阈值 / 时间窗 / 比较方向 —— 全部受控：
--
--   object_type  : SKU / SHOP / LEGAL_ENTITY / SUPPLIER
--   metric_code  : 17 个标准指标码（与 13 指标 ReportingMetricCatalog 同一套命名口径，
--                  跨服务引用同一个码；另含 07 库存 / 12 财务的指标）
--   comparison   : LT / LE / GT / GE
--   time_window  : INSTANT / ROLLING_7D / ROLLING_30D / CALENDAR_MONTH
--   threshold    : NUMERIC(19,6)，**禁浮点**
--
-- CHECK 约束让**绕过服务层直连 SQL** 也一样写不进非法值；服务端另外再拒一次（400）。
--
-- 「对象类型与对象标识的关系」单独两列说清：
--   object_scope='ALL'      —— 该类型的所有对象（object_ref 必须为空）
--   object_scope='SPECIFIC' —— 具体某个对象（object_ref 必填）
-- 两者由一个 CHECK 表达，**不允许**「SPECIFIC 但没填对象」或「ALL 却填了对象」。
--
-- ===========================================================================
-- 五、时间窗的边界按**时区**算，不写死 UTC
-- ===========================================================================
--
-- `timezone` 是规则自己的属性（IANA 区域 id），不是全局常量：
-- 规则是**租户级共享**的，而 identity 侧**没有**全局业务时区
-- （1.4.2.1 的 `AccountLocaleRules` 明确写了「不提供全局业务时区」，
-- 它那个是**个人**展示时区，用在租户共享规则上会让规则随写它的人变）。
--
-- 所以时区**显式存在规则上**：
--   · 预置规则带一个默认时区（代码常量）；
--   · 自定义规则可以显式给，不给就继承**当前操作人的生效展示时区**
--     （AccountLocaleService 的读值），并把来源写进响应里 —— 来源不透明的话，
--     用户会以为时间窗按某个时区算，结果按另一个时区算。
--   · 校验走 `AccountLocaleRules.normalizeTimezone`（受控 IANA 词表）。
--
-- ===========================================================================
-- 六、alert_rule_hit：命中历史（**只建表 + 只读端点，不在本模块写入**）
-- ===========================================================================
--
-- 蓝图：「规则命中记录 → 生成通知 + 工作台待办项；**规则命中历史用于趋势观察**。」
--
-- ⚠️ **写入不在本模块**：规则求值与命中判定是**引擎**的职责，而引擎本体在
-- **18 系统**（18.3 业务规则引擎 → 18.3.1.1 规则列表 / 18.3.1.2 规则编辑器 /
-- 18.3.2.1 执行日志，platform 服务）。
-- 18.3 那一格**尚未交付**（台账 module-progress.md 里三条都是「未开发」），
-- 所以本模块**只建表 + 只提供读端点**，**绝不伪造命中记录**。
--
-- 写入口的归属：引擎通过**授权 API / event** 写进 identity（跨服务不直连对方库，
-- AGENTS.md 模块边界纪律）。那条写入口由 18.3 车道交付 —— 本表因此保留
-- `INSERT` 授权给 identity_app（引擎的写路径落在 identity 侧），但
-- **本模块不暴露任何写端点**，今天没有人能写进假命中。
--
-- `rule_id` **不建外键**：规则被删之后命中历史仍要留着（趋势观察要看历史），
-- 建了 FK 就会让「删规则」被历史挡住。参照 14.1.3.2 的
-- `ad_targeting_operation_log.setting_id`（「不建 FK 以便记下『目标压根不存在』」）。
--
-- `resolution` 区分两种结果（这是「不可判定 ≠ 没有」的落点）：
--   HIT        —— 比较成立，**真的命中**（observed_value 必填）
--   UNDECIDABLE —— **算不出来**（度量没数据 / 环比没有基期），**不是**命中
--                  （reason 必填，写清是哪一种不可判定）
-- 一条 UNDECIDABLE **不是**「0 次命中」，它是一条**结论**：这条规则这次没法判。
--
-- `base_value` 是环比类规则的基期值：**基期没数据**时引擎写 UNDECIDABLE
-- （不是 0%、不是「持平」）。
--
-- ===========================================================================
-- 七、多租户隔离（RLS + 触发器双防线）
-- ===========================================================================
--
-- 两表 `tenant_id NOT NULL` + ENABLE / **FORCE** ROW LEVEL SECURITY，
-- policy 只认 `app.current_tenant`（与 identity V160 / V326 / V330 / V336 / V373 同构）。
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务
-- 用 owner 身份一连就绕过了隔离。
--
-- 另加 **BEFORE INSERT 触发器兜底**：`app.current_tenant` 没设置 ⇒ 直接拒写；
-- `tenant_id` 与租户上下文不符 ⇒ 直接拒写（42501）。
-- RLS 的 WITH CHECK 已经能挡住，触发器是**第二道**防线：
-- 一旦有人把 policy 改松了，这里仍然拦得住。
--
-- 应用连接**不得**持 bypassrls（集成测试里有对应断言）。
--
-- ===========================================================================
-- 八、权限码登记
-- ===========================================================================
--
--   identity.alert_rule.read  —— 看预置规则集、词表、生效规则清单、命中历史
--   identity.alert_rule.write —— 建/改/关/开/删/恢复默认规则
--
-- **必须登记**：role_permission.permission_code 有指向 permission(code) 的外键 ——
-- 没登记的码**没有任何角色可能持有**，端点在真实环境里恒 403
-- （V382 补 platform.finance.* 就是同一个坑的补丁）。
--
-- 与 V3 / V8 / V104 / V335 / V382 同纪律：存量 OWNER 角色也要补上这两个码，
-- 否则新接入的动作对既有租户不可见（新建子租户由 create_child 自动全授，不受影响）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 一、预警规则定义
-- ---------------------------------------------------------------------------
CREATE TABLE alert_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则码：预置规则 = 预置目录里的码（如 SKU_STOCK_COVER_DAYS_LOW）；
  -- 自定义规则 = 'CUSTOM-' + 12 位十六进制。租户内唯一。
  rule_code varchar(64) NOT NULL,

  -- 来源：PRESET = 对某条预置规则的覆盖行；CUSTOM = 租户自建的整条规则。
  origin varchar(16) NOT NULL,

  -- 规则名（自定义规则必填；预置覆盖行可空 ⇒ 用预置名）。
  name varchar(100),

  -- ===== 五项定义（蓝图标名）=====
  -- 对象类型：SKU / SHOP / LEGAL_ENTITY / SUPPLIER。
  object_type varchar(24),
  -- 度量：受控指标码。
  metric_code varchar(48),
  -- 阈值：NUMERIC(19,6)，**禁浮点**。
  threshold numeric(19,6),
  -- 时间窗：INSTANT / ROLLING_7D / ROLLING_30D / CALENDAR_MONTH。
  time_window varchar(24),
  -- 比较方向：LT / LE / GT / GE。
  comparison varchar(8),

  -- ===== 对象范围与对象标识 =====
  -- ALL = 该类型的所有对象；SPECIFIC = 具体某个对象。
  object_scope varchar(16),
  -- 具体对象标识（ALL 时必须为空）。
  object_ref varchar(120),

  -- ===== 时间窗边界所按时区（IANA 区域 id）=====
  timezone varchar(64),

  -- ENABLED 生效 / DISABLED 已关闭（蓝图明说「用户可改参数或关闭」）。
  status varchar(16) NOT NULL DEFAULT 'ENABLED',

  -- 维护人（租户内成员）。**不接受请求参数传入**，取自已认证会话。
  created_by uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一个租户内一个规则码只有一行（预置覆盖行与自定义行不冲突）。
  UNIQUE (tenant_id, rule_code),

  CONSTRAINT alert_rule_created_by_fk
    FOREIGN KEY (tenant_id, created_by) REFERENCES membership(tenant_id, id),

  CONSTRAINT alert_rule_code_chk
    CHECK (rule_code ~ '^[A-Z][A-Z0-9_-]{1,63}$'),

  CONSTRAINT alert_rule_origin_chk
    CHECK (origin IN ('PRESET','CUSTOM')),

  CONSTRAINT alert_rule_status_chk
    CHECK (status IN ('ENABLED','DISABLED')),

  CONSTRAINT alert_rule_name_chk
    CHECK (name IS NULL OR (btrim(name) <> '' AND length(name) <= 100)),

  -- 受控词表：对象类型。
  CONSTRAINT alert_rule_object_type_chk
    CHECK (object_type IS NULL OR object_type IN ('SKU','SHOP','LEGAL_ENTITY','SUPPLIER')),

  -- 受控词表：度量码。
  CONSTRAINT alert_rule_metric_code_chk
    CHECK (metric_code IS NULL OR metric_code IN (
      'SALES_AMOUNT','ORDER_COUNT','UNIT_SOLD','COGS_AMOUNT','GROSS_PROFIT','GROSS_MARGIN',
      'AD_SPEND','NET_PROFIT','RETURNED_AMOUNT','RETURN_RATE','INVENTORY_VALUE','RECEIVABLE_BALANCE',
      'RECEIVABLE_OVERDUE_DAYS','RECEIVABLE_OVERDUE_AMOUNT',
      'PAYABLE_OVERDUE_DAYS','PAYABLE_OVERDUE_AMOUNT',
      'STOCK_COVER_DAYS'
    )),

  -- 受控词表：比较方向。
  CONSTRAINT alert_rule_comparison_chk
    CHECK (comparison IS NULL OR comparison IN ('LT','LE','GT','GE')),

  -- 受控词表：时间窗。
  CONSTRAINT alert_rule_time_window_chk
    CHECK (time_window IS NULL OR time_window IN ('INSTANT','ROLLING_7D','ROLLING_30D','CALENDAR_MONTH')),

  -- 阈值不能为负（负阈值会让「低于阈值」恒真，规则一建就疯狂误报）。
  CONSTRAINT alert_rule_threshold_chk
    CHECK (threshold IS NULL OR threshold >= 0),

  -- 时区形状（真实合法性由服务层的 IANA 词表再挡一道）。
  CONSTRAINT alert_rule_timezone_chk
    CHECK (timezone IS NULL OR timezone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*$'),

  -- 对象范围与对象标识的关系：三者必须自洽。
  CONSTRAINT alert_rule_object_scope_chk
    CHECK (
      (object_scope IS NULL AND object_ref IS NULL)
      OR (object_scope = 'ALL' AND object_ref IS NULL)
      OR (object_scope = 'SPECIFIC' AND object_ref IS NOT NULL
          AND btrim(object_ref) <> '' AND length(object_ref) <= 120)
    ),

  -- 自定义规则：五项定义 + 名称 + 范围 + 时区**全部必填**（缺一项就是残缺规则）。
  CONSTRAINT alert_rule_custom_complete_chk
    CHECK (origin = 'PRESET' OR (
      name IS NOT NULL
      AND object_type IS NOT NULL
      AND metric_code IS NOT NULL
      AND threshold IS NOT NULL
      AND time_window IS NOT NULL
      AND comparison IS NOT NULL
      AND object_scope IS NOT NULL
      AND timezone IS NOT NULL
    )),

  -- 预置覆盖行：要么至少覆盖了一项，要么就是「只关闭」—— 两者都不是都不许。
  CONSTRAINT alert_rule_preset_meaningful_chk
    CHECK (origin = 'CUSTOM' OR status = 'DISABLED'
      OR name IS NOT NULL OR object_type IS NOT NULL OR metric_code IS NOT NULL
      OR threshold IS NOT NULL OR time_window IS NOT NULL OR comparison IS NOT NULL
      OR object_scope IS NOT NULL OR object_ref IS NOT NULL OR timezone IS NOT NULL)
);

-- 「这个租户的自定义规则清单」与「哪些预置规则被改过/关过」的主查询口径。
CREATE INDEX alert_rule_by_origin
  ON alert_rule(tenant_id, origin, status);

-- ---------------------------------------------------------------------------
-- 二、命中历史
-- ---------------------------------------------------------------------------
CREATE TABLE alert_rule_hit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 命中时的规则。**不建外键**：规则删了历史还要留着（趋势观察）。
  rule_id uuid,
  rule_code varchar(64) NOT NULL,

  -- 命中时的对象与度量快照（规则改了也不能改写历史）。
  object_type varchar(24) NOT NULL,
  object_ref varchar(120),
  metric_code varchar(48) NOT NULL,

  -- 本次度量值（HIT 时必填；UNDECIDABLE 时为空 —— **不可判定不是 0**）。
  observed_value numeric(19,6),
  -- 环比类规则的基期值（没有基期 ⇒ 引擎写 UNDECIDABLE，不是 0%）。
  base_value numeric(19,6),
  -- 命中时生效的阈值（留快照，便于事后解释「为什么命中了」）。
  threshold numeric(19,6) NOT NULL,
  comparison varchar(8) NOT NULL,
  time_window varchar(24) NOT NULL,
  timezone varchar(64) NOT NULL,

  -- HIT = 真命中；UNDECIDABLE = 算不出来（reason 写清原因）。
  resolution varchar(16) NOT NULL,
  reason varchar(300),

  -- 命中时刻（timestamptz）。**不用** Timestamp.valueOf(LocalDateTime)，
  -- 那会按 JVM 默认时区静默偏 −28800s。
  hit_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),

  CONSTRAINT alert_rule_hit_resolution_chk
    CHECK (resolution IN ('HIT','UNDECIDABLE')),

  -- 真命中必须有一个观测值。
  CONSTRAINT alert_rule_hit_observed_chk
    CHECK (resolution <> 'HIT' OR observed_value IS NOT NULL),

  -- 不可判定必须写清原因（否则「算不出来」与「没记录」分不开）。
  CONSTRAINT alert_rule_hit_reason_chk
    CHECK (resolution <> 'UNDECIDABLE' OR (reason IS NOT NULL AND btrim(reason) <> '')),

  CONSTRAINT alert_rule_hit_object_type_chk
    CHECK (object_type IN ('SKU','SHOP','LEGAL_ENTITY','SUPPLIER')),

  CONSTRAINT alert_rule_hit_metric_code_chk
    CHECK (metric_code IN (
      'SALES_AMOUNT','ORDER_COUNT','UNIT_SOLD','COGS_AMOUNT','GROSS_PROFIT','GROSS_MARGIN',
      'AD_SPEND','NET_PROFIT','RETURNED_AMOUNT','RETURN_RATE','INVENTORY_VALUE','RECEIVABLE_BALANCE',
      'RECEIVABLE_OVERDUE_DAYS','RECEIVABLE_OVERDUE_AMOUNT',
      'PAYABLE_OVERDUE_DAYS','PAYABLE_OVERDUE_AMOUNT',
      'STOCK_COVER_DAYS'
    )),

  CONSTRAINT alert_rule_hit_comparison_chk
    CHECK (comparison IN ('LT','LE','GT','GE')),

  CONSTRAINT alert_rule_hit_time_window_chk
    CHECK (time_window IN ('INSTANT','ROLLING_7D','ROLLING_30D','CALENDAR_MONTH')),

  CONSTRAINT alert_rule_hit_timezone_chk
    CHECK (timezone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*$')
);

-- 命中历史的幂等键：引擎重投同一次判定不许翻倍。
-- 用 coalesce(object_ref,'') 而不是裸列 —— NULL 在唯一约束里互不相等，
-- 「所有对象」的那类命中（object_ref 为空）会被重投成多行。
CREATE UNIQUE INDEX alert_rule_hit_dedupe
  ON alert_rule_hit(tenant_id, rule_code, coalesce(object_ref,''), hit_at);

-- 「按规则看命中趋势」的主查询口径。
CREATE INDEX alert_rule_hit_by_rule
  ON alert_rule_hit(tenant_id, rule_code, hit_at DESC);

-- ---------------------------------------------------------------------------
-- 三、多租户隔离：RLS
-- ---------------------------------------------------------------------------
ALTER TABLE alert_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON alert_rule
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE alert_rule_hit ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_rule_hit FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON alert_rule_hit
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 四、BEFORE INSERT 触发器兜底（租户上下文 + 规则码前缀）
-- ---------------------------------------------------------------------------
CREATE FUNCTION alert_rule_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入预警规则'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '预警规则的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  -- 自定义规则码必须带 CUSTOM- 前缀；预置覆盖行不得占用这个前缀
  -- （否则一条自定义规则可以「冒充」预置规则码，把预置规则挤掉）。
  IF NEW.origin = 'CUSTOM' AND NEW.rule_code NOT LIKE 'CUSTOM-%' THEN
    RAISE EXCEPTION '自定义规则的规则码必须以 CUSTOM- 开头'
      USING ERRCODE = '23514';
  END IF;
  IF NEW.origin = 'PRESET' AND NEW.rule_code LIKE 'CUSTOM-%' THEN
    RAISE EXCEPTION '预置规则覆盖行的规则码不得使用 CUSTOM- 前缀'
      USING ERRCODE = '23514';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION alert_rule_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION alert_rule_tenant_guard() TO identity_app;

CREATE TRIGGER alert_rule_tenant_guard_trigger
  BEFORE INSERT OR UPDATE ON alert_rule
  FOR EACH ROW EXECUTE FUNCTION alert_rule_tenant_guard();

-- 命中历史同一道兜底（写入方是引擎的授权 API，落库时同样钉住租户）。
CREATE FUNCTION alert_rule_hit_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入预警命中记录'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '预警命中记录的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION alert_rule_hit_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION alert_rule_hit_tenant_guard() TO identity_app;

CREATE TRIGGER alert_rule_hit_tenant_guard_trigger
  BEFORE INSERT ON alert_rule_hit
  FOR EACH ROW EXECUTE FUNCTION alert_rule_hit_tenant_guard();

-- ---------------------------------------------------------------------------
-- 五、授权
-- ---------------------------------------------------------------------------
-- 运行角色是 identity_app：
--   · alert_rule      —— 读（生效清单）+ 写（建/改/关/开/删/恢复默认）。
--   · alert_rule_hit  —— 读（命中历史）；INSERT 留给引擎的授权写路径（18.3 交付），
--                        本模块**不暴露写端点**，今天没有任何入口能写进假命中。
GRANT SELECT, INSERT, UPDATE, DELETE ON alert_rule TO identity_app;
GRANT SELECT, INSERT ON alert_rule_hit TO identity_app;

-- ---------------------------------------------------------------------------
-- 六、权限码登记
-- ---------------------------------------------------------------------------
INSERT INTO page_resource(code, name) VALUES ('identity.alert_rule', '预警规则')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permission VALUES
  ('identity.alert_rule.read', 'identity.alert_rule', 'read'),
  ('identity.alert_rule.write', 'identity.alert_rule', 'write')
ON CONFLICT (code) DO NOTHING;

-- 存量 OWNER 补权（与 V3 / V8 / V104 / V335 / V382 同规则）：
-- 新建子租户由 create_child（V47）把 permission 表里的全部权限码授给 OWNER，
-- 所以新租户自动就有这两个码 —— 但**存量租户**的 OWNER 角色是在这两个码登记之前建的，
-- 不会自动获得它们 ⇒ 迁移上线后这些租户会突然「什么都点不动」。
INSERT INTO role_permission SELECT r.tenant_id, r.id, p.code FROM role r CROSS JOIN permission p
 WHERE r.code = 'OWNER'
   AND p.code IN ('identity.alert_rule.read', 'identity.alert_rule.write')
ON CONFLICT DO NOTHING;
