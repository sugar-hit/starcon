-- 17.7.2.1 平台参数与开关（阶段 D7 · L3 锚点 17.7.2）。
--
-- 蓝图原文（docs/product/capability-blueprint/17-org-permission-and-settings.md 第 285-290 行）：
--   ### 17.7.2.1 平台参数与开关
--   - 职责：平台级功能开关与参数（灰度放量、按租户禁用某模块、限流阈值、公告），
--           用于平台侧运营而非租户配置。
--   - 输入：开关与参数配置；灰度策略。
--   - 输出：全局配置；功能可用性判定。
--   - 关系：上游 = 平台运营；下游 = 全系统功能门控。
--           **平台开关与租户参数（17.5.1）分层**，平台开关不可被租户覆盖。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块的两层分层（蓝图点名的硬约束，落在下面五张表上）
-- ---------------------------------------------------------------------------
-- 「平台开关不可被租户覆盖」这句话落成数据模型是**两张性质完全不同的表**：
--
--   平台全局层（**不带 tenant_id**、不加租户 RLS）
--     platform_flag_definition · 出厂码表（随代码发布，只读）
--     platform_flag_setting     · 平台全局当前值（唯一一份，全平台共用）
--     platform_flag_history     · 平台全局层变更历史（append-only）
--
--   租户例外层（**tenant_id NOT NULL** + RLS FORCE isolation）
--     platform_flag_tenant_exception      · 「某租户被禁用某模块」
--     platform_flag_exception_history     · 租户例外层变更历史（append-only）
--
-- 为什么全局层不带 tenant_id：它回答的是「平台现在把某个功能开到多大、限流阈值是多少」，
-- 与具体租户无关 —— 和 identity 的 page_resource / permission、V48 的
-- business_parameter_definition、V53 的 dict_catalog 是同一类全局码表。
-- 真要按租户存一份平台开关，平台改一次就要改 N 行，而且每个租户手里都有一份可以自己改的副本，
-- 那正是蓝图禁止的「租户覆盖平台开关」。
--
-- 为什么例外层必须带 tenant_id：它本质上就是**租户数据**——「这个租户被平台禁用了某个模块」。
-- 按 AGENTS.md，它必须 tenant_id NOT NULL + DB 层 RLS，并参与多租户三断言。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 「租户不可覆盖平台开关」的四道独立防线
-- ---------------------------------------------------------------------------
--   1. **全局层没有租户副本**：platform_flag_setting 不带 tenant_id，全平台只有一份。
--      租户手里根本不存在「一份可以自己改的平台开关」这个对象。
--   2. **DB 授权层**：platform_flag_setting 只 GRANT SELECT 给 identity_app，
--      **不授予 INSERT / UPDATE / DELETE**。应用角色物理上写不了全局层；
--      唯一的写入口是下面的 SECURITY DEFINER 函数，由它做权限与形态校验。
--      （与 V53 的 dict_catalog「只 GRANT SELECT」同一先例。）
--   3. **例外层只能收紧，不能放宽**：platform_flag_tenant_exception.effect 的取值域被
--      CHECK 钉死在 'DISABLED' —— 租户例外**永远只能禁用**，永远不能把平台关掉的东西打开。
--      平台禁用的功能不可能被租户例外打开。
--   4. **限流 / 公告不参与按租户禁用**：platform_flag_exception_kind_guard() 触发器
--      拒绝给非 MODULE 类开关建例外。禁用一个限流阈值等于「放宽」，那不是收紧。
--
-- ---------------------------------------------------------------------------
-- 三、灰度放量必须是确定性的
-- ---------------------------------------------------------------------------
-- rollout_percent（0.00 ~ 100.00，NUMERIC(5,2)，**禁浮点**）+ rollout_salt 决定分桶：
-- 分桶 = floorMod(sha256(salt || '|' || tenant_id) 的前 8 字节, 10000)，
-- 命中条件是 bucket < rollout_percent × 100（万分比，整数比较）。
-- 同一租户 + 同一盐 ⇒ 同一分桶 ⇒ 反复请求恒定；用随机数会让用户看到开关时开时关。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 限流阈值不给默认值（照 16.5.2.1「阈值必须由配置方显式选」的先例）
-- ---------------------------------------------------------------------------
-- platform_flag_definition 里**没有**限流阈值的出厂默认值，platform_flag_setting 上
-- RATE_LIMIT 类的行**必须**带 rate_limit_per_minute（表级 CHECK 钉死）。
-- 没配就没有这一行 —— 判定时返回「未配置」，而不是替平台悄悄选一个数字。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 平台运营写权限：只授「读」，写权限一律不默认授予
-- ---------------------------------------------------------------------------
-- 页面授权沿用 V48 / V53 的口径，但**只**把 identity.platform_flag.read 授给 OWNER：
-- 平台参数写权限（update / create / delete）不默认授给任何角色，必须由 17.3.1 角色管理
-- 显式分配 —— 这与蓝图「17.7 平台运营不对租户开放」一致。
--
-- 另外，**平台全局层的写入还要求调用方所在租户是根租户**（parent_tenant_id IS NULL）：
-- 本仓的租户层级里，子租户由 create_child 创建，而 create_child 会把 permission 表里的
-- **全部**权限码授给子租户的 OWNER 角色。若只看权限码，一个子租户的 OWNER 就能改全平台配置。
-- 「根租户才写得了平台全局层」这一条把那个口子堵住：根租户是平台本身，子租户不是。
--
-- ---------------------------------------------------------------------------
-- 六、边界
-- ---------------------------------------------------------------------------
-- 下游「全系统功能门控」在本仓**尚未接入**（没有统一的 feature-flag 客户端）。
-- 本模块**产出**判定能力（GET /identity/platform-flags/evaluate 返回三层裁决结果），
-- 不去改其它服务的代码去接它 —— 接入是后续待办。
-- 17.7.1.1「套餐与权益」的超限降级是同批并行车道，此刻同样不存在，本模块不实现它。

-- ===========================================================================
-- 一、platform_flag_definition · 平台开关与参数的出厂码表（全局，不带 tenant_id）
-- ===========================================================================
-- 为什么不是租户表：这一张回答的是「平台定义了哪些开关与参数、它们的出厂默认值是什么」，
-- 与具体租户无关，与 identity 的 page_resource / permission、V48 的
-- business_parameter_definition、V53 的 dict_catalog 是同一类全局码表。
-- 平台要改出厂值，走的是 platform_flag_setting（平台全局层当前值），不是改这张码表。
CREATE TABLE platform_flag_definition (
  -- 稳定的 UUID：审计留痕按 resource_id 归组，用 code 当 id 会让「同一个开关的历次变更」
  -- 散成互不相干的记录。code 仍是业务主键。
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code                    varchar(64) NOT NULL UNIQUE,
  name                    varchar(100) NOT NULL,
  -- 三类取值域，对应蓝图职责点名的三类平台配置：
  --   MODULE       功能开关（可带灰度放量比例）
  --   RATE_LIMIT   限流阈值（每分钟请求数上限，整数计数）
  --   ANNOUNCEMENT 公告（标题 + 正文 + 生效期）
  flag_kind               varchar(24) NOT NULL
                          CHECK(flag_kind IN ('MODULE','RATE_LIMIT','ANNOUNCEMENT')),
  -- 出厂默认值一律 fail-closed：功能开关默认关、灰度默认 0.00。
  -- 「忘了配」不等于「对所有租户开放」。
  default_enabled         boolean NOT NULL DEFAULT false,
  -- 灰度放量比例（百分比）。NUMERIC(5,2)，禁浮点；判定时换算成万分比做整数比较。
  default_rollout_percent numeric(5,2) NOT NULL DEFAULT 0.00
                          CHECK(default_rollout_percent >= 0 AND default_rollout_percent <= 100),
  description             varchar(300) NOT NULL DEFAULT '',
  sort_order              int NOT NULL DEFAULT 0,
  status                  text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  created_at              timestamptz NOT NULL DEFAULT now(),
  CHECK(btrim(name)<>''),
  -- 码只允许大写字母开头的标识符：前端路由与 api-proxy 白名单都按这个形状收敛
  CHECK(code ~ '^[A-Z][A-Z0-9_]{1,63}$')
);

COMMENT ON TABLE platform_flag_definition IS
  '17.7.2.1 · 平台开关与参数的出厂码表（全局，不带 tenant_id，不加租户 RLS，只 GRANT SELECT）。'
  '出厂默认一律 fail-closed：功能开关默认关、灰度默认 0%。';

CREATE INDEX platform_flag_definition_list ON platform_flag_definition(sort_order, code);

-- ===========================================================================
-- 二、platform_flag_setting · 平台全局层当前值（全局，不带 tenant_id）
-- ===========================================================================
-- 全平台唯一一份。**不带 tenant_id** 是「租户不可覆盖平台开关」最直接的落法：
-- 库里根本不存在「按租户的那一份平台开关」。
--
-- 🔴 授权：只 GRANT SELECT。写入一律走 platform_flag_apply_setting()（SECURITY DEFINER）。
CREATE TABLE platform_flag_setting (
  flag_code        varchar(64) PRIMARY KEY
                   REFERENCES platform_flag_definition(code) ON DELETE RESTRICT,
  -- 与 definition.flag_kind 同步：跨表一致性 CHECK 做不到，用触发器钉死。
  flag_kind        varchar(24) NOT NULL
                   CHECK(flag_kind IN ('MODULE','RATE_LIMIT','ANNOUNCEMENT')),
  -- MODULE：功能开关总闸（平台侧）。
  -- ANNOUNCEMENT：公告是否已发布。
  enabled          boolean NOT NULL DEFAULT false,
  -- 灰度放量比例（百分比，禁浮点）。
  rollout_percent  numeric(5,2) NOT NULL DEFAULT 0.00
                   CHECK(rollout_percent >= 0 AND rollout_percent <= 100),
  -- 灰度哈希盐：同一租户 + 同一盐 ⇒ 同一分桶。改盐等于重新洗牌（重新分桶）。
  rollout_salt     varchar(64) NOT NULL DEFAULT 'starcon-platform-rollout-v1',
  -- 限流阈值：每分钟请求数上限，整数计数。**不给默认值**，必须由平台显式配置。
  rate_limit_per_minute int CHECK(rate_limit_per_minute > 0),
  -- 公告：标题 / 正文 / 生效期。
  announcement_title varchar(200),
  announcement_body  text,
  announcement_from  timestamptz,
  announcement_to    timestamptz,
  note            varchar(300) NOT NULL DEFAULT '',
  version         bigint NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by_membership_id uuid,
  CHECK(updated_at >= created_at)
);

COMMENT ON TABLE platform_flag_setting IS
  '17.7.2.1 · 平台全局层当前值（全局唯一，不带 tenant_id）。'
  '只 GRANT SELECT 给 identity_app —— 写入必须走 SECURITY DEFINER 函数 platform_flag_apply_setting()，'
  '应用角色物理上写不了它，这就是「租户不可写平台全局层」的 DB 级事实。';

-- 形态自洽：每类开关只能带属于它自己的字段。
-- RATE_LIMIT 必须带阈值（不给默认值 ⇒ 没配就没有这一行）；
-- ANNOUNCEMENT 必须带标题与一个非空生效期，且 to > from；
-- MODULE 一律不带限流与公告字段。
ALTER TABLE platform_flag_setting ADD CONSTRAINT platform_flag_setting_shape CHECK(
  (flag_kind='MODULE'
    AND rate_limit_per_minute IS NULL
    AND announcement_title IS NULL AND announcement_body IS NULL
    AND announcement_from IS NULL AND announcement_to IS NULL)
  OR
  (flag_kind='RATE_LIMIT'
    AND rate_limit_per_minute IS NOT NULL
    AND announcement_title IS NULL AND announcement_body IS NULL
    AND announcement_from IS NULL AND announcement_to IS NULL)
  OR
  (flag_kind='ANNOUNCEMENT'
    AND rate_limit_per_minute IS NULL
    AND announcement_title IS NOT NULL
    AND announcement_from IS NOT NULL AND announcement_to IS NOT NULL
    AND announcement_to > announcement_from)
);

-- 码表与当前值的类别必须一致：防止把一个 MODULE 的行改成 RATE_LIMIT 绕开形态约束。
CREATE FUNCTION platform_flag_setting_kind_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE kind text;
BEGIN
  SELECT flag_kind INTO kind FROM platform_flag_definition WHERE code=NEW.flag_code;
  IF kind IS NULL THEN
    RAISE EXCEPTION '平台开关 % 不存在', NEW.flag_code USING ERRCODE='23503';
  END IF;
  IF kind <> NEW.flag_kind THEN
    RAISE EXCEPTION '平台开关 % 的类别是 %，与当前值的类别 % 不一致', NEW.flag_code, kind, NEW.flag_kind
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER platform_flag_setting_kind_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_flag_setting
  FOR EACH ROW EXECUTE FUNCTION platform_flag_setting_kind_guard();

-- ===========================================================================
-- 三、platform_flag_tenant_exception · 按租户禁用（租户数据，tenant_id NOT NULL）
-- ===========================================================================
-- 蓝图点名的「按租户禁用某模块」落在这一张：它回答的是「这个租户被平台关掉了某个模块」。
--
-- 🔴 effect 的取值域被 CHECK 钉死在 'DISABLED'：租户例外**只能在平台允许的前提下进一步收紧**，
--    永远不可能把平台禁用的功能打开。这不是靠代码自觉，是 DB 层的物理事实。
CREATE TABLE platform_flag_tenant_exception (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  flag_code   varchar(64) NOT NULL REFERENCES platform_flag_definition(code) ON DELETE RESTRICT,
  effect      varchar(16) NOT NULL DEFAULT 'DISABLED' CHECK(effect='DISABLED'),
  -- 禁用必须写明原因：事后没人说得清「为什么这个租户没有这个功能」。
  reason      varchar(300) NOT NULL CHECK(btrim(reason)<>''),
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by_membership_id uuid,
  UNIQUE(tenant_id, id),
  -- 一个租户对一个开关至多一条例外（撤销就是删掉这一行，历史留在 exception_history）。
  UNIQUE(tenant_id, flag_code)
);

COMMENT ON TABLE platform_flag_tenant_exception IS
  '17.7.2.1 · 按租户禁用（租户数据：tenant_id NOT NULL + RLS FORCE isolation）。'
  'effect 只能取 DISABLED —— 租户例外只能收紧、不能放宽，平台禁用的功能租户打不开。';

CREATE INDEX platform_flag_tenant_exception_list
  ON platform_flag_tenant_exception(tenant_id, flag_code);
CREATE INDEX platform_flag_tenant_exception_code
  ON platform_flag_tenant_exception(flag_code);

-- 🔴 只有功能开关（MODULE）可以按租户禁用：
--    禁用一个限流阈值 = 放宽限制（不是收紧），禁用一条公告 = 没有意义。
CREATE FUNCTION platform_flag_exception_kind_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE kind text;
BEGIN
  SELECT flag_kind INTO kind FROM platform_flag_definition WHERE code=NEW.flag_code;
  IF kind IS NULL THEN
    RAISE EXCEPTION '平台开关 % 不存在', NEW.flag_code USING ERRCODE='23503';
  END IF;
  IF kind <> 'MODULE' THEN
    RAISE EXCEPTION '只有功能开关可以按租户禁用；% 是 % 类，禁用它等于放宽限制而不是收紧',
      NEW.flag_code, kind USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER platform_flag_exception_kind_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_flag_tenant_exception
  FOR EACH ROW EXECUTE FUNCTION platform_flag_exception_kind_guard();

-- ===========================================================================
-- 四、platform_flag_history · 平台全局层变更历史（全局，append-only）
-- ===========================================================================
-- 与 V48 business_parameter_history / V53 dict_history / 17.5.3.1 审计同一纪律：
-- 应用层「不写 UPDATE」不是保证，拿到连接就能直接改。这里用触发器在数据库层挡住
-- UPDATE/DELETE，权限层再 REVOKE 一次，双重防线。
--
-- 🔴 这一张**不带 tenant_id**：它记的是「平台全局配置被谁改了」，全平台一份，
--    所有平台运营都该看得见。操作人所在租户记在 operator_tenant_id 上（留痕，不做隔离）。
CREATE TABLE platform_flag_history (
  id             uuid PRIMARY KEY,
  flag_code      varchar(64) NOT NULL,
  action         text NOT NULL CHECK(action IN ('CREATED','UPDATED')),
  -- 变更前后值。公告只记标题与生效期；正文太长不进历史（历史是给排查看的）。
  before_enabled boolean,
  after_enabled  boolean,
  before_rollout_percent numeric(5,2),
  after_rollout_percent  numeric(5,2),
  before_rate_limit_per_minute int,
  after_rate_limit_per_minute  int,
  before_announcement_title varchar(200),
  after_announcement_title  varchar(200),
  before_announcement_from timestamptz,
  after_announcement_from  timestamptz,
  before_announcement_to timestamptz,
  after_announcement_to  timestamptz,
  note           varchar(300) NOT NULL DEFAULT '',
  operator_tenant_id uuid,
  operator_membership_id uuid,
  operator_name  varchar(100),
  -- changed_at 是系统时间，用 OffsetDateTime 绑定写入（见 AuditLog.utc 的说明）。
  changed_at     timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform_flag_history IS
  '17.7.2.1 · 平台全局层变更历史（全局，不带 tenant_id；append-only，只追加不可改）。';

CREATE INDEX platform_flag_history_code ON platform_flag_history(flag_code, changed_at DESC);
CREATE INDEX platform_flag_history_time ON platform_flag_history(changed_at DESC);

CREATE FUNCTION platform_flag_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '平台参数变更历史不可修改或删除（17.7.2.1 变更留痕）' USING ERRCODE='42501';
END $$;

CREATE TRIGGER platform_flag_history_immutable_guard
  BEFORE UPDATE OR DELETE ON platform_flag_history
  FOR EACH ROW EXECUTE FUNCTION platform_flag_history_immutable();

-- ===========================================================================
-- 五、platform_flag_exception_history · 租户例外层变更历史（租户数据，append-only）
-- ===========================================================================
-- 带 tenant_id + RLS：例外层是租户数据，它的变更历史同样是租户数据。
CREATE TABLE platform_flag_exception_history (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  flag_code   varchar(64) NOT NULL,
  action      text NOT NULL CHECK(action IN ('CREATED','RELEASED')),
  reason      varchar(300) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  actor_name  varchar(100),
  changed_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

COMMENT ON TABLE platform_flag_exception_history IS
  '17.7.2.1 · 按租户禁用的变更历史（租户数据：tenant_id NOT NULL + RLS FORCE isolation；append-only）。';

CREATE INDEX platform_flag_exception_history_code
  ON platform_flag_exception_history(tenant_id, flag_code, changed_at DESC);
CREATE INDEX platform_flag_exception_history_time
  ON platform_flag_exception_history(tenant_id, changed_at DESC);

CREATE FUNCTION platform_flag_exception_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '按租户禁用的变更历史不可修改或删除（17.7.2.1 变更留痕）' USING ERRCODE='42501';
END $$;

CREATE TRIGGER platform_flag_exception_history_immutable_guard
  BEFORE UPDATE OR DELETE ON platform_flag_exception_history
  FOR EACH ROW EXECUTE FUNCTION platform_flag_exception_history_immutable();

-- ===========================================================================
-- 六、租户隔离（RLS）
-- ===========================================================================
-- 平台全局层（platform_flag_definition / platform_flag_setting / platform_flag_history）
-- **不加 RLS**：它们不带 tenant_id，是全平台共读的一份（与 V53 的 dict_catalog 同口径）。
-- 真正必须隔离的是例外层及其变更历史。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_flag_tenant_exception',
    'platform_flag_exception_history'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 七、授权
-- ===========================================================================
-- 🔴 平台全局层**只读**：应用角色写不了它。唯一的写入口是下面第七节的 SECURITY DEFINER 函数。
--   （与 V53 的 dict_catalog「只 GRANT SELECT」同一先例。）
GRANT SELECT ON platform_flag_definition TO identity_app;
GRANT SELECT ON platform_flag_setting TO identity_app;
-- 历史只追加：变更由下面的函数写入，应用侧只读。
GRANT SELECT ON platform_flag_history TO identity_app;

-- 例外层：可新增、可撤销（撤销=删行，留痕在 exception_history）。
-- **不授予 UPDATE**：例外一旦写下就不可改，要改先撤销再新建 —— 否则「谁在什么时候禁用的」
-- 与「现在这条是不是当时那条」会分不清。
GRANT SELECT,INSERT,DELETE ON platform_flag_tenant_exception TO identity_app;
GRANT SELECT,INSERT ON platform_flag_exception_history TO identity_app;

-- ===========================================================================
-- 八、🔴 平台全局层的唯一写入口（SECURITY DEFINER）
-- ===========================================================================
-- 为什么放在数据库里而不是只在 Java 里判：
--   * 「应用角色写不了平台全局层」必须是一条 DB 级事实（直接 SQL 写会被 42501 挡下）；
--   * 类别形态（限流必须带阈值、公告必须带生效期）要与表级 CHECK 同口径，不能只在应用层校验；
--   * 变更历史必须与写入在同一个事务里落，否则会出现「改了但没留痕」。
CREATE FUNCTION platform_flag_apply_setting(
  hash text,
  p_flag_code text,
  p_enabled boolean,
  p_rollout_percent numeric,
  p_rollout_salt text,
  p_rate_limit_per_minute int,
  p_announcement_title text,
  p_announcement_body text,
  p_announcement_from timestamptz,
  p_announcement_to timestamptz,
  p_note text
) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; kind text; before record; sid uuid;
  v_enabled boolean; v_rollout numeric; v_salt text; v_rate int;
  v_title text; v_body text; v_from timestamptz; v_to timestamptz; v_note text;
  v_action text;
BEGIN
  -- 先取当前值：后面算「盐不变」与「变更前后值」都要用它。
  SELECT * INTO before FROM platform_flag_setting WHERE flag_code=p_flag_code;
  SELECT * INTO a FROM session_access(hash);
  IF a IS NULL THEN
    RAISE EXCEPTION '登录已失效，请重新登录' USING ERRCODE='42501';
  END IF;
  IF NOT ('identity.platform_flag.update' = ANY(a.permissions)) THEN
    RAISE EXCEPTION '平台参数写入需要平台运营权限（identity.platform_flag.update）' USING ERRCODE='42501';
  END IF;
  -- 🔴 平台全局层只允许根租户（parent_tenant_id IS NULL）写：
  --   本仓 create_child 会把 permission 表里的全部权限码授给子租户的 OWNER 角色，
  --   只看权限码的话，子租户的 OWNER 也能改全平台配置。根租户才是平台本身。
  IF EXISTS (SELECT 1 FROM tenant WHERE id=a.tenant_id AND parent_tenant_id IS NOT NULL) THEN
    RAISE EXCEPTION '平台全局参数只能由平台（根租户）写入；当前租户是子租户，请联系平台运营'
      USING ERRCODE='42501';
  END IF;

  SELECT flag_kind INTO kind FROM platform_flag_definition WHERE code=p_flag_code;
  IF kind IS NULL THEN
    RAISE EXCEPTION '平台开关 % 不存在', p_flag_code USING ERRCODE='23503';
  END IF;
  IF (SELECT status FROM platform_flag_definition WHERE code=p_flag_code) <> 'ACTIVE' THEN
    RAISE EXCEPTION '平台开关 % 已下线，不能再配置', p_flag_code USING ERRCODE='23514';
  END IF;

  v_enabled := coalesce(p_enabled, false);
  v_rollout := coalesce(p_rollout_percent, 0);
  -- 盐不传就沿用当前值：改盐等于重新洗牌（重新分桶），不该由一次「顺手保存」触发。
  v_salt := coalesce(nullif(btrim(coalesce(p_rollout_salt,'')),''),
                     nullif(btrim(coalesce(before.rollout_salt,'')),''),
                     'starcon-platform-rollout-v1');
  v_note := coalesce(p_note,'');

  IF v_rollout < 0 OR v_rollout > 100 THEN
    RAISE EXCEPTION '灰度放量比例必须在 0.00 ~ 100.00 之间，当前为 %', v_rollout USING ERRCODE='23514';
  END IF;

  -- 按类别塑形：不属于该类的字段一律置空，表级 CHECK 与这里同口径。
  IF kind='MODULE' THEN
    v_rate := NULL; v_title := NULL; v_body := NULL; v_from := NULL; v_to := NULL;
  ELSIF kind='RATE_LIMIT' THEN
    IF p_rate_limit_per_minute IS NULL OR p_rate_limit_per_minute <= 0 THEN
      RAISE EXCEPTION '限流阈值必须由平台显式给出（不给默认值），当前为 %', coalesce(p_rate_limit_per_minute::text,'空')
        USING ERRCODE='23514';
    END IF;
    v_rate := p_rate_limit_per_minute;
    v_title := NULL; v_body := NULL; v_from := NULL; v_to := NULL;
  ELSE
    IF p_announcement_title IS NULL OR btrim(p_announcement_title)='' THEN
      RAISE EXCEPTION '公告必须填写标题' USING ERRCODE='23514';
    END IF;
    IF p_announcement_from IS NULL OR p_announcement_to IS NULL THEN
      RAISE EXCEPTION '公告必须给出生效期（起 / 止）' USING ERRCODE='23514';
    END IF;
    IF p_announcement_to <= p_announcement_from THEN
      RAISE EXCEPTION '公告结束时刻必须晚于开始时刻' USING ERRCODE='23514';
    END IF;
    v_rate := NULL;
    v_title := btrim(p_announcement_title);
    v_body := coalesce(p_announcement_body,'');
    v_from := p_announcement_from;
    v_to := p_announcement_to;
  END IF;

  v_action := 'UPDATED';
  IF before.flag_code IS NULL THEN v_action := 'CREATED'; END IF;
  INSERT INTO platform_flag_setting
    (flag_code,flag_kind,enabled,rollout_percent,rollout_salt,rate_limit_per_minute,
     announcement_title,announcement_body,announcement_from,announcement_to,
     note,version,created_at,updated_at,updated_by_membership_id)
  VALUES (p_flag_code,kind,v_enabled,v_rollout,v_salt,v_rate,v_title,v_body,v_from,v_to,
          v_note,0,now(),now(),a.membership_id)
  ON CONFLICT (flag_code) DO UPDATE SET
    enabled=EXCLUDED.enabled,
    rollout_percent=EXCLUDED.rollout_percent,
    rollout_salt=EXCLUDED.rollout_salt,
    rate_limit_per_minute=EXCLUDED.rate_limit_per_minute,
    announcement_title=EXCLUDED.announcement_title,
    announcement_body=EXCLUDED.announcement_body,
    announcement_from=EXCLUDED.announcement_from,
    announcement_to=EXCLUDED.announcement_to,
    note=EXCLUDED.note,
    version=platform_flag_setting.version+1,
    updated_at=now(),
    updated_by_membership_id=a.membership_id;

  INSERT INTO platform_flag_history
    (id,flag_code,action,before_enabled,after_enabled,
     before_rollout_percent,after_rollout_percent,
     before_rate_limit_per_minute,after_rate_limit_per_minute,
     before_announcement_title,after_announcement_title,
     before_announcement_from,after_announcement_from,
     before_announcement_to,after_announcement_to,
     note,operator_tenant_id,operator_membership_id,operator_name,changed_at)
  VALUES (
    gen_random_uuid(),p_flag_code,v_action,
    before.enabled,v_enabled,
    before.rollout_percent,v_rollout,
    before.rate_limit_per_minute,v_rate,
    before.announcement_title,v_title,
    before.announcement_from,v_from,
    before.announcement_to,v_to,
    v_note,a.tenant_id,a.membership_id,
    (SELECT u.username FROM app_user u WHERE u.id=a.user_id),
    now());
END $$;

REVOKE EXECUTE ON FUNCTION platform_flag_apply_setting(text,text,boolean,numeric,text,int,text,text,timestamptz,timestamptz,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_flag_apply_setting(text,text,boolean,numeric,text,int,text,text,timestamptz,timestamptz,text) TO identity_app;

-- ===========================================================================
-- 九、🔴 灰度分桶函数（确定性）
-- ===========================================================================
-- 与 Java 侧 PlatformFlagController.rolloutBucket **逐位同口径**：
--   digest = sha256(utf8(salt || '|' || tenant_id))
--   v     = 前 4 字节按**大端无符号**解释的 32 位整数（0 .. 4294967295）
--   bucket = v mod 10000            ⇒ 0 .. 9999
--   命中条件：bucket < rollout_percent × 100（万分比，整数比较，无浮点）
--
-- 为什么用 sha256 而不是 PG 内置的 hashtextextended：hash 函数族的输出**不保证跨 PG 大版本稳定**，
-- 换个大版本就可能把已经放量的租户重新洗一遍牌。sha256 是标准算法，跨版本跨语言恒定。
-- 为什么取前 4 字节而不是全部 32 字节再折半：4 字节无符号正好落在 bigint 里，
-- 不存在有符号溢出，也就不会出现「取模结果随实现漂移」。
--
-- 这个函数存在的意义是：**判定口径可以被 SQL 直接复查**，不必相信应用层的算法。
-- 集成测试会断言它与 Java 实现逐个租户一致。
CREATE FUNCTION platform_flag_rollout_bucket(salt text, tenant_id uuid) RETURNS int
LANGUAGE sql IMMUTABLE AS $$
  WITH d AS (
    SELECT sha256(convert_to(coalesce(salt,'') || '|' || tenant_id::text, 'UTF8')) AS b
  )
  SELECT mod(
      get_byte(b,0)::bigint*16777216 + get_byte(b,1)::bigint*65536
      + get_byte(b,2)::bigint*256 + get_byte(b,3)::bigint,
      10000)::int
  FROM d;
$$;

COMMENT ON FUNCTION platform_flag_rollout_bucket(text,uuid) IS
  '17.7.2.1 · 灰度分桶（确定性）：同一 salt + 同一 tenant_id ⇒ 恒定分桶 0..9999。'
  'sha256 前 4 字节大端无符号 mod 10000，与 Java 侧 PlatformFlagController.rolloutBucket 逐位同口径。';

REVOKE EXECUTE ON FUNCTION platform_flag_rollout_bucket(text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_flag_rollout_bucket(text,uuid) TO identity_app;

-- ===========================================================================
-- 九之二、🔴 「这个租户是不是平台（根租户）」的判定
-- ===========================================================================
-- 为什么需要它：`create_child` 会把 permission 表里的**全部**权限码授给子租户的 OWNER 角色，
-- 只看权限码的话，一个子租户的 OWNER 也能改全平台配置。根租户（parent_tenant_id IS NULL）
-- 才是平台本身。
--
-- 为什么写成函数：应用角色（identity_app）对 `tenant` 表**没有任何权限**
-- （V1/V3 都没给它授 tenant 的 SELECT —— 租户信息只能经授权函数读）。
-- 所以判定必须放在 SECURITY DEFINER 里，不能让应用直接查 tenant。
CREATE FUNCTION platform_flag_tenant_is_root(target_tenant uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
  SELECT NOT EXISTS (
    SELECT 1 FROM tenant WHERE id=target_tenant AND parent_tenant_id IS NOT NULL
  );
$$;

REVOKE EXECUTE ON FUNCTION platform_flag_tenant_is_root(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_flag_tenant_is_root(uuid) TO identity_app;

COMMENT ON FUNCTION platform_flag_tenant_is_root(uuid) IS
  '17.7.2.1 · 该租户是否为平台（根租户，parent_tenant_id IS NULL）。'
  '平台全局层只允许根租户写 —— 子租户的 OWNER 会被 create_child 自动配上全部权限码，'
  '只看权限码挡不住它。';

-- ===========================================================================
-- 十、出厂开关与参数
-- ===========================================================================
-- 对应蓝图职责点名的四类：功能开关（含灰度放量）、限流阈值、公告。
INSERT INTO platform_flag_definition
  (code,name,flag_kind,default_enabled,default_rollout_percent,description,sort_order) VALUES
  ('MODULE_AI_ASSISTANT','AI 助手（灰度中）','MODULE',true,10.00,
   '面向 AI 辅助能力。默认开启但只放量 10%，按租户稳定哈希分桶——同一租户反复请求结果恒定，不会时开时关。',10),
  ('MODULE_BULK_PRICE_ADJUST','批量调价','MODULE',true,100.00,
   '批量改价能力。默认全量开启；平台运营仍可对个别租户单独禁用。',20),
  ('MODULE_ADS_AUTOMATION','广告自动化','MODULE',false,0.00,
   '广告自动投放。出厂关闭（fail-closed）：没显式打开就不对任何租户开放。',30),
  ('RATE_LIMIT_ORDER_API','订单接口限流','RATE_LIMIT',false,0.00,
   '下游订单接口的每分钟请求数上限。🔴 **阈值不给默认值**：平台必须显式配置后才生效，未配置时判定返回「未配置」。',40),
  ('ANNOUNCEMENT_MAINTENANCE','系统维护公告','ANNOUNCEMENT',true,0.00,
   '平台级公告。生效期之外的公告不展示；公告是平台对全体租户说话，不是租户自己的配置。',50);

-- 平台全局层的出厂当前值。
-- ⚠️ **RATE_LIMIT_ORDER_API 刻意不预置 platform_flag_setting 行**：
--   限流阈值不给默认值 —— 必须由平台显式配置后才有一行，判定时如实返回「未配置」。
INSERT INTO platform_flag_setting
  (flag_code,flag_kind,enabled,rollout_percent,rollout_salt,rate_limit_per_minute,
   announcement_title,announcement_body,announcement_from,announcement_to,note,version,created_at,updated_at)
VALUES
  ('MODULE_AI_ASSISTANT','MODULE',true,10.00,'starcon-platform-rollout-v1',NULL,NULL,NULL,NULL,NULL,
   '出厂配置：开启并放量 10%',0,now(),now()),
  ('MODULE_BULK_PRICE_ADJUST','MODULE',true,100.00,'starcon-platform-rollout-v1',NULL,NULL,NULL,NULL,NULL,
   '出厂配置：全量开启',0,now(),now()),
  ('MODULE_ADS_AUTOMATION','MODULE',false,0.00,'starcon-platform-rollout-v1',NULL,NULL,NULL,NULL,NULL,
   '出厂配置：关闭',0,now(),now()),
  ('ANNOUNCEMENT_MAINTENANCE','ANNOUNCEMENT',true,0.00,'starcon-platform-rollout-v1',NULL,
   '系统维护通知',
   '平台将于近期进行系统维护，维护期间部分功能可能短暂不可用，感谢理解。',
   timestamptz '2026-01-01 00:00:00+00', timestamptz '2027-12-31 23:59:59+00',
   '出厂配置：已发布的维护公告',0,now(),now());

-- ===========================================================================
-- 十一、页面授权
-- ===========================================================================
-- 与业务参数（V48）、数据字典（V53）同口径：按页面 CRUD 授权。
--
-- 🔴 **只把「读」授给 OWNER**：平台参数的写权限（update / create / delete）不默认授给任何角色，
--    必须由 17.3.1 角色管理显式分配 —— 蓝图里 17.7 是「平台运营，不对租户开放」。
INSERT INTO page_resource(code,name) VALUES ('identity.platform_flag','平台参数与开关')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.platform_flag.'||action,'identity.platform_flag',action
FROM unnest(ARRAY['read','update','create','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code='identity.platform_flag.read' ON CONFLICT DO NOTHING;
