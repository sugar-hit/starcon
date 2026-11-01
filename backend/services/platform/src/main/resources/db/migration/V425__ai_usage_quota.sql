-- ===========================================================================
-- V425 · platform_ai_* —— 18.4.3.1「使用与配额」
-- ===========================================================================
--
-- **卷 18「集成开放与智能自动化」L3 18.4.3 AI 治理 → 18.4.3.1 使用与配额**
-- （蓝图 18-integration-and-automation.md 18.4.3.1 原文）：
--
--   「**职责**：管理 AI 使用：按租户与用户的调用量与成本、配额限制、模型选择、
--     敏感数据处理策略开关。**输入**：调用记录；配额配置。
--     **输出**：用量报表 + 配额执行。**关系**：上游 = 18.4 全部；下游 = 17.6.1 计费。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意**做什么 / 不做什么
-- ---------------------------------------------------------------------------
--
--   * **用量台账是只追加的事实表**（platform_ai_usage_record）：
--     能改的用量记录等于没有用量记录 ⇒ REVOKE UPDATE/DELETE **与** 触发器**两个**闸门都留着
--     （少授一个权限码会被「迁移脚本用属主身份跑」绕开，触发器不会）。
--
--   * **成本绝不硬编码**：单价来自**租户可维护的模型目录**（platform_ai_model），
--     计算时把当时的单价**快照**进台账（unit_cost / cost 两列）——
--     目录里后来改了价，历史台账里的成本**不动**（回看历史账单看到的是当初的价）。
--
--   * **配额计数在 DB 侧原子完成**（platform_ai_quota_usage）：
--     递增是一条 `INSERT ... ON CONFLICT DO UPDATE SET used = used + EXCLUDED.used`，
--     由数据库的行锁串行化 —— **禁止**「Java 先 SELECT 再 UPDATE」那种读后写，
--     那种写法在并发下必然丢计数。
--
--   * **不直连 finance**：本模块只提供**可计费口径**的用量汇总（GET /platform/ai/usage/summary），
--     17.6.1 计费（finance 服务）自行来取或由下游消费；
--     本迁移**不建跨库外键、不写 finance 的任何表**（AGENTS.md：跨服务走授权 API / event）。
--
--   * **敏感数据策略只是「策略 + 查询」**：执行点在**各个 AI 能力自己**（调用前查本表）。
--     本仓**没有**全局 AI 拦截点 —— 迁移与能力声明都如实这么写，不谎称已全局拦截。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_ai_model             —— 模型目录（模型名 / 单位成本 / 上下文上限 / 是否可用）
--   platform_ai_usage_record      —— 用量台账（一次 AI 调用一行；**只追加**）
--   platform_ai_quota             —— 配额配置（租户级 / 用户级 × 次数上限 / 成本上限 × 日 / 月）
--   platform_ai_quota_usage       —— 配额用量计数（**DB 侧原子递增**的落点）
--   platform_ai_sensitive_policy  —— 敏感数据处理策略（禁传 / 脱敏 / 允许）
--
-- 五张表全部 tenant_id NOT NULL + ENABLE **与** FORCE RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「落库时间戳一律取 DB 时钟」
-- ---------------------------------------------------------------------------
--
--   `occurred_at` / `created_at` / `updated_at` 的默认值是 `clock_timestamp()`（**不是** `now()`）：
--   `now()` 在一个事务里是**冻结**的（事务开始时刻），而一次调用会在同一个事务里写多行；
--   用 now() 会让「调用时刻」与「配额周期切分」偏到事务开始那一刻。
--
--   ⚠️ 服务层仍然是「先 `select clock_timestamp()` 读出来、再用 `?::timestamptz` 绑定」：
--     读出后用 OffsetDateTime 绑定，避免 `Timestamp.valueOf(LocalDateTime)` 那种
--     静默偏 −28800s 的写法（跨时区偏移在夏令时切换那天会差 1 小时）。
--
-- ---------------------------------------------------------------------------
-- 3. 「配额周期边界」的口径
-- ---------------------------------------------------------------------------
--
--   DAY  周期 = **UTC 自然日**（00:00:00.000000Z 起，次日 00:00:00.000000Z 止）
--   MONTH 周期 = **UTC 自然月**（每月 1 日 00:00:00.000000Z 起，次月 1 日 00:00:00.000000Z 止）
--
--   ⚠️ 为什么固定 UTC：周期边界是**跨租户对齐**的口径（下游 17.6.1 计费按同一口径切账期），
--     用本地时区会让「同一个配额」在不同时区算出不同的窗口，且夏令时切换那天窗口长度会变。
--     「什么时候重置」因此永远是一个**确定的 UTC 时刻**，页面直接显示它。
--
--   计数行的唯一键是 `(quota_id, period_start)` —— 周期一切换就是**新的一行**，
--   上一周期的计数**原样留着**（历史可查），不会被清零。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_ai_model —— 模型目录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个「模型 × 单价」的登记项，**租户数据**（平台不预置任何一行）。
--
-- 🔴 **单价必须落库**：成本绝不硬编码在代码里。平台代码改一次，所有租户的账单口径就变了；
--     单价是**商业条款**，归租户维护。
--
-- 🔴 **`unit` 是量纲，不是装饰**：unit_cost 的分母就是「1 个 unit」。
--     同样是 0.002，REQUEST 口径是「每次调用 0.002」，TOKEN 口径是「每个 Token 0.002」——
--     这两件事差着好几个数量级。量纲不写清楚，账单就是一笔糊涂账。
--
-- ⚠️ **不提供删除**：模型单价被台账以**外键**引用（platform_ai_usage_record.model_id），
--     删掉目录行会让历史台账悬空。停用请走 `enabled = false`。
--
CREATE TABLE platform_ai_model (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 模型标识：租户内部唯一（同一个模型在不同租户可以有不同的单价）。
  model_code varchar(64) NOT NULL,
  model_name varchar(200) NOT NULL,
  -- 供应商（OpenAI / 通义 / 自建 …）：只作登记与报表维度，不用来挑线路。
  provider varchar(64) NOT NULL DEFAULT '',

  -- 🔴 量纲：调用量以什么计量。
  --   REQUEST       —— 次（按调用次数计量）
  --   TOKEN         —— Token
  --   THOUSAND_TOKEN—— 千 Token
  --   CHARACTER     —— 字符
  unit varchar(16) NOT NULL
    CHECK(unit IN ('REQUEST','TOKEN','THOUSAND_TOKEN','CHARACTER')),

  -- 🔴 单价：**每 1 个 unit 的成本**。台账里会快照这一列。
  unit_cost numeric(19,6) NOT NULL CHECK(unit_cost >= 0),
  currency char(3) NOT NULL DEFAULT 'USD',

  -- 上下文上限（Token 数）：0 = 不登记。用于「模型选择」时的前置校验。
  context_limit int NOT NULL DEFAULT 0 CHECK(context_limit >= 0),

  -- 是否可用：停用后不能**新**记用量（历史台账不受影响）。
  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, model_code),

  CHECK(btrim(model_code) <> ''),
  CHECK(btrim(model_name) <> ''),
  CHECK(btrim(currency) <> '')
);

CREATE INDEX platform_ai_model_by_code
  ON platform_ai_model(tenant_id, model_code);
CREATE INDEX platform_ai_model_by_enabled
  ON platform_ai_model(tenant_id, enabled);

-- ---------------------------------------------------------------------------
-- 2. platform_ai_usage_record —— 用量台账（只追加）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次**已归属租户与成员**的 AI 调用。
--
-- 🔴 **归属口径**：tenant_id 与 membership_id **都来自已认证会话**，
--    请求体里**不接受** tenantId / userId / membershipId ——
--    客户端自称的归属一律 400（Jackson fail-on-unknown-properties 全局开启）。
--
-- 🔴 **只追加**：用量台账是事实表，不许 UPDATE / DELETE 改写历史
--     （与 platform_api_call_log、platform_app_audit_event 同一纪律）。
--
-- 🔴 **单价快照**：unit_cost 是「记这一行那一刻」的目录单价，cost = quantity × unit_cost
--     由服务层用 BigDecimal 算完写死。目录里后来改价，历史行的成本**不动**。
--
-- ⚠️ **外键用默认的 NO ACTION（不 CASCADE）**：有调用记录的模型**不能被删除**（目录表也没有删除端点）。
--
CREATE TABLE platform_ai_usage_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 🔴 **谁用的**：已认证会话解析出的成员（客户端不能指定）。
  membership_id uuid NOT NULL,

  -- 能力维度：哪个 AI 能力（TEXT_TRANSLATION / TITLE_GENERATION / …），
  -- 以及该能力下的哪个子功能（feature_code，可空串 = 不细分）。
  -- ⚠️ 取值域是**开放的**：本仓的 AI 能力分布在 18.4 多个兄弟模块里，
  --    由**各能力自己**在调用后回报；本模块不替它们维护一张能力码表。
  capability_code varchar(64) NOT NULL,
  feature_code varchar(64) NOT NULL DEFAULT '',

  -- 模型维度：目录里的哪一行 + **冗余快照**的模型码
  -- （目录里改了 model_name 也不影响历史行的可读性）。
  model_id uuid NOT NULL,
  model_code varchar(64) NOT NULL,

  -- 🔴 量纲与调用量：quantity 的单位由 unit 决定（Token / 千 Token / 次 / 字符）。
  unit varchar(16) NOT NULL
    CHECK(unit IN ('REQUEST','TOKEN','THOUSAND_TOKEN','CHARACTER')),
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),

  -- 🔴 **单价快照 + 成本**：两列都是**当时的**口径，不是「现在的目录价」。
  unit_cost numeric(19,6) NOT NULL CHECK(unit_cost >= 0),
  cost numeric(19,6) NOT NULL CHECK(cost >= 0),
  currency char(3) NOT NULL,

  -- 请求关联 id：把这一次调用和上游的请求号 / 业务单据对上（排查用）。
  request_ref varchar(64) NOT NULL DEFAULT '',

  -- 发生时刻：取 DB 时钟（见本文件第二节）。**客户端不可指定** ——
  -- 允许客户端回填时刻就等于允许它把用量挪到上一个周期里躲开配额。
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, model_id) REFERENCES platform_ai_model(tenant_id, id),

  CHECK(btrim(capability_code) <> ''),
  CHECK(btrim(currency) <> '')
);

-- 台账主查询路径（按时间倒序翻页）。
CREATE INDEX platform_ai_usage_record_by_time
  ON platform_ai_usage_record(tenant_id, occurred_at DESC);
-- 按成员维度（用户级用量报表）。
CREATE INDEX platform_ai_usage_record_by_member
  ON platform_ai_usage_record(tenant_id, membership_id, occurred_at DESC);
-- 按能力维度（能力级用量报表）。
CREATE INDEX platform_ai_usage_record_by_capability
  ON platform_ai_usage_record(tenant_id, capability_code, occurred_at DESC);
-- 按模型维度（模型级用量报表 + 成本归集）。
CREATE INDEX platform_ai_usage_record_by_model
  ON platform_ai_usage_record(tenant_id, model_code, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_ai_quota —— 配额配置
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条配额规则：
--   主体（租户级 / 用户级）× 能力（capability_code，'*' = 全部能力）× 周期（日 / 月）
--   × 阈值（次数上限 / 成本上限，两者可只配一个）
--
-- 🔴 **主体成员不来自请求体**：用户级配额的主体是
--    「服务端解析的成员」（自助）或「路径里指定的成员」（管理员代配）。
--    请求体里**没有** membershipId 字段 —— 客户端不能自称归属。
--
-- 🔴 **两个上限可以只配一个**：只配次数 = 「今天最多调 N 次」；
--    只配成本 = 「这个月最多花 M 元」；两个都配 = **任一越线即超限**。
--
-- 🔴 **成本上限必须声明币种**（currency）：不同币种的钱**不可比** ——
--    「本月最多花 100 USD」与「本月最多花 100 EUR」是两个不同的额度，
--    把它们加在一个数上就是错账。因此本条配额的成本上限**只**按本条声明的币种计量：
--    币种不符的调用**照计次数、不计成本**（跨币种请分别配置多条配额）。
--    计数上限与币种无关，任何币种的调用都计。
--
-- ⚠️ **不做跨库外键**：membership_id 指向 identity 的成员，但**禁止跨服务 FK**
--    （AGENTS.md）。它只是一个标识符；用不上的配额行是**惰性的**（永远匹配不到用量），
--    不会造成越权 —— 用量行的 membership_id 永远来自已认证会话。
--
CREATE TABLE platform_ai_quota (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 主体范围：TENANT = 覆盖全体成员；USER = 只覆盖某一个成员。
  scope varchar(16) NOT NULL CHECK(scope IN ('TENANT','USER')),
  -- 🔴 USER 级必填、TENANT 级必空 —— 一条 CHECK 钉死，不靠服务层记得判断。
  membership_id uuid,

  -- 能力维度：'*' = 全部 AI 能力（租户级的兜底额度）。
  capability_code varchar(64) NOT NULL DEFAULT '*',

  -- 阈值：次数上限（整数次）与成本上限（金额）。两者至少配一个。
  limit_count bigint CHECK(limit_count > 0),
  limit_cost numeric(19,6) CHECK(limit_cost > 0),
  -- 🔴 成本上限的币种（3 位字母）。不同币种**不混算**。
  currency char(3) NOT NULL DEFAULT 'USD',

  -- 周期：DAY（日）/ MONTH（月）。
  period varchar(16) NOT NULL CHECK(period IN ('DAY','MONTH')),

  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),

  CHECK((scope = 'USER') = (membership_id IS NOT NULL)),
  CHECK(limit_count IS NOT NULL OR limit_cost IS NOT NULL),
  CHECK(btrim(capability_code) <> ''),
  CHECK(btrim(currency) <> '')
);

-- 🔴 **同一主体 × 能力 × 周期只有一条**：没有这条唯一约束，
--    就能给同一个对象配两条互相矛盾的额度，判定时取哪条全看实现 ⇒ 账单不可预期。
--    TENANT 级的 membership_id 是 NULL —— 而 Postgres 的 UNIQUE 把 NULL 视为互不相等，
--    所以这里用 COALESCE 把 NULL 折成一个哨兵值（成员 id 不可能是全零 UUID）。
CREATE UNIQUE INDEX platform_ai_quota_subject_unique
  ON platform_ai_quota(
    tenant_id,
    capability_code,
    period,
    (COALESCE(membership_id, '00000000-0000-0000-0000-000000000000'::uuid))
  );

CREATE INDEX platform_ai_quota_by_scope
  ON platform_ai_quota(tenant_id, scope, capability_code, period);

-- ---------------------------------------------------------------------------
-- 4. platform_ai_quota_usage —— 配额用量计数（**DB 侧原子递增**的落点）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条配额在一个周期窗口里的累计用量。
--
-- 🔴 **这一张表就是「并发正确性」的落点**：递增由
--    `INSERT ... ON CONFLICT (quota_id, period_start) DO UPDATE
--       SET used_count = platform_ai_quota_usage.used_count + EXCLUDED.used_count`
--    完成 —— 数据库的行锁把并发递增**串行化**，返回值就是递增**之后**的累计值。
--    绝不允许「Java 先 SELECT 读出 used 再 UPDATE 写 used + n」：
--    那种读后写在并发下会**丢计数**（两个事务读到同一个 used，各自写 used + 1）。
--
-- 🔴 **周期切换 = 新的一行**：`(quota_id, period_start)` 唯一，
--    上一周期的计数**原样留着**（历史可查），不会被清零。
--
-- ⚠️ **为什么单独一张表而不是在配额行上直接加列**：
--    配额行的粒度是「配置」，计数的粒度是「配置 × 周期」。
--    混在一张表里，改一次配置就会把历史周期的计数搅乱。
--
CREATE TABLE platform_ai_quota_usage (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  quota_id uuid NOT NULL,
  -- 窗口边界：start 是**含**，end 是**不含**（左闭右开）。
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,

  -- 累计量：次数（整数）与成本（金额）。两者都是「到此刻为止」的累计值。
  used_count bigint NOT NULL DEFAULT 0 CHECK(used_count >= 0),
  used_cost numeric(19,6) NOT NULL DEFAULT 0 CHECK(used_cost >= 0),

  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),
  UNIQUE(quota_id, period_start),

  FOREIGN KEY(tenant_id, quota_id) REFERENCES platform_ai_quota(tenant_id, id),

  CHECK(period_end > period_start)
);

CREATE INDEX platform_ai_quota_usage_by_quota
  ON platform_ai_quota_usage(tenant_id, quota_id, period_start DESC);

-- ---------------------------------------------------------------------------
-- 5. platform_ai_sensitive_policy —— 敏感数据处理策略
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个能力 × 某个模型（或全部模型）」的敏感数据处理动作。
--
-- 🔴 **动作只有三档**：BLOCK（禁传）/ MASK（脱敏）/ ALLOW（允许）。
--    没有第四档「先问一问我」—— 那不是策略，是运行时。
--
-- 🔴 **执行点在各个 AI 能力自己**：本仓**没有**全局 AI 拦截点。
--    策略落库 + 可查询（GET /platform/ai/usage/policies），
--    由**各个 AI 能力在调用前**来查；本迁移不假装自己是拦截器。
--
-- 🔴 **模型维度可空串 = 全部模型**：先给能力定一条通用规则，
--    再对个别模型加严（更具体的那条生效）。
--
CREATE TABLE platform_ai_sensitive_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 能力维度：必填（策略总是挂在某个能力上）。
  capability_code varchar(64) NOT NULL,
  -- 模型维度：'' = 全部模型。
  model_code varchar(64) NOT NULL DEFAULT '',

  -- 处理动作：BLOCK / MASK / ALLOW。
  action varchar(16) NOT NULL CHECK(action IN ('BLOCK','MASK','ALLOW')),
  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, capability_code, model_code),

  CHECK(btrim(capability_code) <> '')
);

CREATE INDEX platform_ai_sensitive_policy_by_capability
  ON platform_ai_sensitive_policy(tenant_id, capability_code, model_code);

-- ---------------------------------------------------------------------------
-- 6. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
--
-- 五张表全部：tenant_id NOT NULL + ENABLE **与** FORCE RLS + policy isolation。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
ALTER TABLE platform_ai_model ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_model FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_model
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_ai_usage_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_usage_record FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_usage_record
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_ai_quota ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_quota FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_quota
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_ai_quota_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_quota_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_quota_usage
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_ai_sensitive_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_sensitive_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_sensitive_policy
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 7. 授权
-- ---------------------------------------------------------------------------
--
-- 运行角色是 platform_app。
--
-- ⚠️ **用量台账只授 SELECT + INSERT**（不给 UPDATE / DELETE）：
--    「不可改、不可删」由**数据层的触发器**钉死，而不是只靠少授一个权限码 ——
--    少授会被「迁移脚本用属主身份跑」绕开，触发器不会（属主也会被 FORCE RLS + 触发器管住）。
--    两个闸门都留着，权限码是「正常路径用不到」，触发器是「异常路径也拦得住」。
GRANT SELECT,INSERT ON platform_ai_usage_record TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_ai_model TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_ai_quota TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_ai_quota_usage TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_ai_sensitive_policy TO platform_app;

-- 用量台账只追加：拒绝 UPDATE（改写历史等于伪造历史）。
CREATE FUNCTION refuse_platform_ai_usage_record_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_ai_usage_record 是只追加的用量台账，不允许修改';
END $$;

CREATE TRIGGER guard_platform_ai_usage_record_update
  BEFORE UPDATE ON platform_ai_usage_record
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_ai_usage_record_update();

-- 用量台账只追加：拒绝 DELETE。
CREATE FUNCTION refuse_platform_ai_usage_record_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_ai_usage_record 是只追加的用量台账，不允许删除';
END $$;

CREATE TRIGGER guard_platform_ai_usage_record_delete
  BEFORE DELETE ON platform_ai_usage_record
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_ai_usage_record_delete();
