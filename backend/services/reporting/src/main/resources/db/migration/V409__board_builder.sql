-- ===========================================================================
-- V409 · board_builder —— 13.6.1.2 看板搭建
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:233）：
--
--   ### 13.6.1.2 看板搭建
--   - **职责**：搭建自定义经营看板：拖拽组件、选择指标与筛选、布局保存、按角色共享、
--     设置默认值。
--   - **输入**：组件配置；看板模板；可见范围设置。
--   - **输出**：看板视图 + 共享配置。
--   - **关系**：上游 = 13.6.1.1；下游 = 1 工作台（可置为个人看板）。
--     **看板需权限过滤**，不同角色看到的组件不同，不能一个看板通吃。
--
-- ---------------------------------------------------------------------------
-- 0. 本模块的五张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_board                  —— 看板（归属 / 可见范围 / 默认筛选 / 默认时间窗口）
--   reporting_board_component        —— 组件（指标 + 维度 + 图表类型 + 布局 + 筛选 + 所需权限）
--   reporting_board_share            —— 按角色共享（角色 或 权限码 + 可见范围）
--   reporting_board_template         —— 看板模板（模板头）
--   reporting_board_template_component —— 模板里的组件（形状与真组件同构）
--
-- ⚠️ **本文件不建任何「事实表」**：看板上的数字**不落库**。
--   看板 = 组件配置（落库）+ 语义层指标口径表（V376 `reporting_metric_definition`）
--   + 投影事实（V376 `reporting_metric_fact`）按组件配置**实时算出来**的读模型。
--   落一份结果就是第二个真相 —— 口径改了、事实刷新了，落库的那份不会跟着变。
--
-- ⚠️ **零 ALTER 既有表**：reporting 自己的纪律是「零 ALTER 既有表」
--   （V376 的七张表纯 CREATE TABLE）⇒ 本文件纯新建五张表 + 一个视图，
--   与 13.1.1.1 的七张表、13.2.x / 13.3.x / 13.4.x / 13.5.x 的各组表互不干扰。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 「口径唯一」落到数据层：组件的指标码必须**已登记**
-- ---------------------------------------------------------------------------
--
--   与 13.6.1.1（V408 `guard_reporting_custom_report_measure_known`）**同一条不变量**：
--   看板组件引用的 `metric_code` 必须在**本租户**的 `reporting_metric_definition` 里登记过，
--   否则触发器直接拒绝写入。
--   ⇒ 看板**不能**绕过登记表自己发明一个指标（那会让同一口径分裂成两个真相）。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 维度白名单与 13.6.1.1 **完全一致**
-- ---------------------------------------------------------------------------
--
--   `dimension_code` 只有 5 个码：SCOPE_TYPE / LEGAL_ENTITY / SHOP / BUCKET_DATE / CURRENCY。
--   看板组件的维度**不能**超出这张白名单（CHECK 钉死）。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 布局是「形状受约束的 jsonb」，非法形状写不进去
-- ---------------------------------------------------------------------------
--
--   拖拽的结果是栅格位置与尺寸：`{"x":0,"y":0,"w":3,"h":2}`。
--   * 位置 x / y 是**非负整数**；
--   * 尺寸 w / h 是**正整数**；
--   * 栅格合法：x + w ≤ 12（看板宽 12 栅格）、y + h ≤ 64（一屏最多 64 行）。
--   非法布局**直接报错**（服务层 400 + DB 层 CHECK 兜底），**不静默丢弃**。
--
-- ---------------------------------------------------------------------------
-- 4. 🔴 「不同角色看到的组件不同」是**库层**不变量
-- ---------------------------------------------------------------------------
--
--   组件上的 `required_permission` 写明「谁才看得到这个组件」。
--   视图 `reporting_board_component_visible`（security_invoker = true）把这条规则
--   **写在 SQL 里**：
--     * `required_permission = ''` ⇒ 谁都能看；
--     * 否则需要当前会话的 `app.grants` 里带这个权限码；
--     * 看板的**创建者**永远看得到自己摆的组件（不然组件会「凭空消失」）。
--   ⇒ 视图 + 服务层过滤**同一条规则**（服务层那道是保险，不是唯一防线）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0.5 栅格布局的形状检查（必须**先于**建表：CHECK 会引用它）
-- ---------------------------------------------------------------------------
--
--   用函数而不是内联 CHECK：同一段形状规则要同时用在
--   `reporting_board_component` 与 `reporting_board_template_component` 上，
--   内联写两遍 = 两份会漂移的真相。
--
--   ⚠️ 数值比较前先判「是数字」再判「是整数文本」：
--     jsonb 里可以塞 `{"x": "abc"}`，直接 ::int 会抛异常而不是返回 false。
CREATE OR REPLACE FUNCTION reporting_board_layout_ok(layout jsonb) RETURNS boolean AS $$
BEGIN
  IF jsonb_typeof(layout) <> 'object' THEN RETURN false; END IF;
  IF NOT (layout ? 'x' AND layout ? 'y' AND layout ? 'w' AND layout ? 'h') THEN RETURN false; END IF;
  IF jsonb_typeof(layout->'x') <> 'number' OR jsonb_typeof(layout->'y') <> 'number'
     OR jsonb_typeof(layout->'w') <> 'number' OR jsonb_typeof(layout->'h') <> 'number' THEN
    RETURN false;
  END IF;
  IF (layout->>'x') !~ '^[0-9]{1,9}$' OR (layout->>'y') !~ '^[0-9]{1,9}$'
     OR (layout->>'w') !~ '^[0-9]{1,9}$' OR (layout->>'h') !~ '^[0-9]{1,9}$' THEN
    RETURN false;
  END IF;
  RETURN (layout->>'x')::int >= 0
     AND (layout->>'y')::int >= 0
     AND (layout->>'w')::int >= 1
     AND (layout->>'h')::int >= 1
     -- 看板宽 12 栅格；一屏最多 64 行。
     AND (layout->>'x')::int + (layout->>'w')::int <= 12
     AND (layout->>'y')::int + (layout->>'h')::int <= 64;
END $$ LANGUAGE plpgsql IMMUTABLE;

-- ---------------------------------------------------------------------------
-- 1. reporting_board —— 看板
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：看板是租户自己的业务人员搭的。
--   服务层不播种「示例看板」—— 播种出来的看板在生产里是没人用的僵尸数据。
--
-- ⚠️ **owner_user_id 来自已认证会话**（identity 按成员关系解析出来的 user_id），
--   **不在入参里** ⇒ 客户端不能把看板「送给」别人当自己的。
--
-- ⚠️ **default_filters 是与组件 filter 同构的数组**（维度码 + 运算符 + 值）。
--   值**只作为绑定参数**传给预编译 SQL，绝不拼进 SQL 文本（同 13.6.1.1 的做法）。
CREATE TABLE reporting_board (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 看板编码：租户内唯一。
  board_code varchar(64) NOT NULL CHECK (btrim(board_code) <> ''),
  display_name varchar(120) NOT NULL CHECK (btrim(display_name) <> ''),
  -- 口径 / 用途说明：页面上原样显示。
  description varchar(500) NOT NULL DEFAULT '',
  -- 创建者（已认证会话的 user_id）。
  owner_user_id uuid NOT NULL,
  -- 可见范围：PRIVATE 个人（要共享给别人得显式开共享）/ TEAM 团队内成员。
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','TEAM')),
  -- 默认筛选（与组件 filter 同构的数组；空数组 = 没有默认筛选）。
  default_filters jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(default_filters) = 'array'),
  -- 默认时间窗口（空 = 用运行时的默认窗口「最近 30 天」）。
  default_window_from date,
  default_window_to date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个租户内看板编码唯一。
  UNIQUE(tenant_id, board_code),
  -- 窗口不能倒着。
  CHECK (default_window_from IS NULL OR default_window_to IS NULL OR default_window_from <= default_window_to)
);

-- 看板清单按「创建者 / 更新时间」筛。
CREATE INDEX reporting_board_by_owner
  ON reporting_board(tenant_id, owner_user_id, updated_at DESC);
-- 团队可见的看板按更新时间排。
CREATE INDEX reporting_board_by_visibility
  ON reporting_board(tenant_id, visibility, updated_at DESC);

-- ---------------------------------------------------------------------------
-- 2. reporting_board_component —— 组件
-- ---------------------------------------------------------------------------
--
-- 一行 = 看板上的一个卡片。
--
-- ⚠️ **一个看板里「同一指标 + 同一维度」只能出现一次**
--   （UNIQUE(tenant_id, board_id, metric_code, dimension_code)）：
--   同一张卡片摆两遍是配置错误，不是风格问题。
CREATE TABLE reporting_board_component (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  board_id uuid NOT NULL,
  -- 指标码：必须**已在本租户的口径表登记**（见第一节的触发器）。
  metric_code varchar(48) NOT NULL CHECK (metric_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
  -- 维度码：白名单里的 5 个（与 13.6.1.1 完全一致）。
  dimension_code varchar(32) NOT NULL
    CHECK (dimension_code IN ('SCOPE_TYPE','LEGAL_ENTITY','SHOP','BUCKET_DATE','CURRENCY')),
  -- 图表类型：TABLE 表格 / BAR 柱状 / LINE 折线 / PIE 饼图。
  chart_type text NOT NULL DEFAULT 'BAR' CHECK (chart_type IN ('TABLE','BAR','LINE','PIE')),
  -- 栅格布局：{"x":0,"y":0,"w":3,"h":2}（形状由 reporting_board_layout_ok 钉死）。
  layout jsonb NOT NULL DEFAULT '{"x":0,"y":0,"w":3,"h":2}'::jsonb
    CHECK (reporting_board_layout_ok(layout)),
  -- 组件自己的筛选（与 default_filters 同构的数组；值只作为绑定参数）。
  filter jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(filter) = 'array'),
  -- 看得懂这个组件所需的权限码（空串 = 谁都能看）。
  --   🔴 这条是「不同角色看到的组件不同」的载体，视图按它过滤。
  required_permission varchar(64) NOT NULL DEFAULT ''
    CHECK (required_permission = '' OR required_permission ~ '^[a-z][a-z0-9]*(\.[a-z0-9-]+)+$'),
  -- 展示顺序（拖拽后重排就改它）。
  sort_order int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, board_id, metric_code, dimension_code)
);

CREATE INDEX reporting_board_component_by_board
  ON reporting_board_component(tenant_id, board_id, sort_order, id);

-- ---------------------------------------------------------------------------
-- 3. reporting_board_share —— 按角色共享
-- ---------------------------------------------------------------------------
--
-- 一行 = 「谁能看到这张看板」。
--
-- ⚠️ **role_id 与 permission_code 二选一**（CHECK 钉死）：
--   角色 id 来自 identity 的成员关系角色；权限码是权限本身。
--   两者都可以单独用来圈定「哪些人」。
--
-- ⚠️ **scope 两档**：
--   * ROLE  —— 只有命中 role_id / permission_code 的成员能打开这张看板；
--   * TENANT —— 租户内成员都能打开（组件**仍然**按 required_permission 过滤）。
CREATE TABLE reporting_board_share (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  board_id uuid NOT NULL,
  -- 角色 id（identity 侧的角色）。与 permission_code 二选一。
  role_id uuid,
  -- 权限码（与 role_id 二选一）。
  permission_code varchar(64) NOT NULL DEFAULT ''
    CHECK (permission_code = '' OR permission_code ~ '^[a-z][a-z0-9]*(\.[a-z0-9-]+)+$'),
  -- 可见范围：ROLE 仅命中角色 / 权限码的人 / TENANT 租户内成员。
  scope text NOT NULL DEFAULT 'ROLE' CHECK (scope IN ('ROLE','TENANT')),
  -- 谁建的这条共享（已认证会话的 user_id）。
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  -- 撤销：撤销后这条共享立即失效（行留着当凭据）。
  revoked_at timestamptz,
  UNIQUE(tenant_id, id),
  -- 角色与权限码必须**恰好给一个**。
  CHECK ((role_id IS NOT NULL AND permission_code = '')
      OR (role_id IS NULL AND permission_code <> ''))
);

CREATE INDEX reporting_board_share_by_board
  ON reporting_board_share(tenant_id, board_id, created_at DESC);
-- 同一张看板上「同一角色 / 同一权限码」只能有一条**有效**共享。
CREATE UNIQUE INDEX reporting_board_share_unique_active
  ON reporting_board_share(tenant_id, board_id, coalesce(role_id::text, ''), permission_code)
  WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- 4. reporting_board_template —— 看板模板
-- ---------------------------------------------------------------------------
--
-- 一行 = 一份可复用的看板骨架。**模板不是看板**：它是「还没绑定具体组件数据源
-- 的那套摆法」，实例化之后才变成一张真看板。
--
-- ⚠️ **不预置任何一行**：模板也是租户自己的东西（同 V408 的纪律）。
CREATE TABLE reporting_board_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  template_code varchar(64) NOT NULL CHECK (btrim(template_code) <> ''),
  display_name varchar(120) NOT NULL CHECK (btrim(display_name) <> ''),
  description varchar(500) NOT NULL DEFAULT '',
  -- 谁建的这份模板（已认证会话的 user_id）。
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, template_code)
);

-- ---------------------------------------------------------------------------
-- 5. reporting_board_template_component —— 模板里的组件
-- ---------------------------------------------------------------------------
--
-- 与 `reporting_board_component` **同构**（同样的列、同样的 CHECK、同样的触发器）。
-- ⚠️ 刻意**不**用 jsonb 存一坨模板组件：那样指标码与维度码就绕过了触发器与 CHECK，
--   「口径唯一」与「维度白名单」会在模板这条路上被绕过。
CREATE TABLE reporting_board_template_component (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL CHECK (metric_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
  dimension_code varchar(32) NOT NULL
    CHECK (dimension_code IN ('SCOPE_TYPE','LEGAL_ENTITY','SHOP','BUCKET_DATE','CURRENCY')),
  chart_type text NOT NULL DEFAULT 'BAR' CHECK (chart_type IN ('TABLE','BAR','LINE','PIE')),
  layout jsonb NOT NULL DEFAULT '{"x":0,"y":0,"w":3,"h":2}'::jsonb
    CHECK (reporting_board_layout_ok(layout)),
  filter jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(filter) = 'array'),
  required_permission varchar(64) NOT NULL DEFAULT ''
    CHECK (required_permission = '' OR required_permission ~ '^[a-z][a-z0-9]*(\.[a-z0-9-]+)+$'),
  sort_order int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, template_id, metric_code, dimension_code)
);

CREATE INDEX reporting_board_template_component_by_template
  ON reporting_board_template_component(tenant_id, template_id, sort_order, id);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 栅格布局的形状检查：**见第 0.5 节**（函数必须先于建表存在）。
--
-- (b) 组件的指标码**必须已在本租户的口径表登记**（口径唯一）。
--
--     ⚠️ 与 V408 的 `guard_reporting_custom_report_measure_known` 是**同一条不变量**：
--       看板不能绕过登记表自己发明指标。
--     ⚠️ 触发器要能在「定义行已被删掉」时安静退出（只挡写入，不挡删除）。
CREATE OR REPLACE FUNCTION guard_reporting_board_component_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在口径表中登记，不能作为看板组件', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_board_component_known
  BEFORE INSERT OR UPDATE ON reporting_board_component
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_board_component_known();

CREATE TRIGGER reporting_board_template_component_known
  BEFORE INSERT OR UPDATE ON reporting_board_template_component
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_board_component_known();

-- ===========================================================================
-- 第三节：🔴 「不同角色看到的组件不同」—— 库层的可见性视图
-- ===========================================================================
--
--   `reporting_board_component_visible` = 真组件表 ∩ 「当前会话有权看的组件」。
--
--   ⚠️ **security_invoker = true**：视图以**查询者**的身份跑
--     ⇒ 基表的 FORCE RLS 照常生效（租户隔离仍然是库层强制）。
--   ⚠️ 这条规则与**服务层**的过滤是同一条：
--     服务层按当前用户的权限 / 角色过滤可见组件；这里把同一条规则**写在 SQL 里**，
--     就算服务层哪天的过滤写漏了，库层也不会把不该看的组件吐出去。
CREATE VIEW reporting_board_component_visible WITH (security_invoker = true) AS
SELECT c.id,
       c.tenant_id,
       c.board_id,
       c.metric_code,
       c.dimension_code,
       c.chart_type,
       c.layout,
       c.filter,
       c.required_permission,
       c.sort_order,
       c.created_at,
       c.updated_at
  FROM reporting_board_component c
  JOIN reporting_board b
    ON b.tenant_id = c.tenant_id AND b.id = c.board_id
 WHERE c.tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid
   AND (
        -- 没写所需权限的组件：谁都能看。
        c.required_permission = ''
        -- 看板创建者看得到自己摆的全部组件（否则组件会「凭空消失」）。
     OR b.owner_user_id = nullif(current_setting('app.actor', true), '')::uuid
        -- 否则要当前会话的授权里带这个权限码。
     OR EXISTS (
          SELECT 1
            FROM jsonb_array_elements(
                   coalesce(nullif(current_setting('app.grants', true), '')::jsonb, '[]'::jsonb)) AS g
           WHERE (g -> 'permissions') ? c.required_permission
        )
   );

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_board','reporting_board_component','reporting_board_share',
    'reporting_board_template','reporting_board_template_component'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_board, reporting_board_component, reporting_board_share,
  reporting_board_template, reporting_board_template_component TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_board, reporting_board_component, reporting_board_share,
  reporting_board_template, reporting_board_template_component TO starcon_app;
GRANT SELECT ON reporting_board_component_visible TO reporting_app;
GRANT SELECT ON reporting_board_component_visible TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第五节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **1 工作台（下游）**：蓝图写明「下游 = 1 工作台（可置为个人看板）」——
--     那属 **1 工作台与门户系统**，本模块只提供看板与看板视图，
--     **不**在本服务里另起一套工作台。
--   * **13.6.2.1 导出与订阅（未交付）**：看板的导出 / 定时推送属那一格，
--     本模块**不建**导出任务表、**不建**推送队列。
--   * **口径与事实**：组件上的数字**不落库**（见第 0 节）。
