-- 1.2.2.2 快捷入口配置 —— 岗位/角色快捷入口模板 + 个人覆盖项。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：按角色/团队预置快捷入口模板，新成员加入时自动获得岗位常用入口，减少培训成本。
--   **输入**：17 系统配置的岗位模板；用户所属部门/角色；用户个人覆盖项。
--   **输出**：最终生效的快捷入口集合（模板 + 个人覆盖的合并结果）。
--   **关系**：上游 = 17 组织权限与系统设置；下游 = 1.2.2.1 收藏列表。共享数据：`role`、`org_unit`。
--
-- ===========================================================================
-- 一、为什么这两张表在 identity
-- ===========================================================================
-- 「岗位快捷入口模板」是**组织配置**（17 组织权限与系统设置拥有），
-- 「个人覆盖项」是**纯个人偏好**（1.4 个人设置同域）。
-- 两者都属于 identity：
--   * 模板的 scope 指向 `role.id` / `department.id` —— 这两张表都在 identity 的库里，
--     同库同服务 ⇒ 模板与岗位的对应关系可以用**真正的外键**兜住，
--     不需要跨服务调用，也不需要「只存 id 不校验」的弱引用。
--   * 覆盖项挂在 `membership_id`（租户内成员）上，不是 `app_user.id`。
--     app_user 是跨租户全局账号；「这个租户里的这个人」的唯一身份是 membership。
--
-- 刻意**不**建 `user_preference` 表 —— 那是同批 1.4.1.2 车道的表，本车道既不建也不 ALTER。
-- 刻意**不**建收藏表 —— 1.2.2.1「常用页面收藏」未交付，本车道只负责「最终生效的快捷入口集合」，
-- 收藏列表的渲染与拖拽排序留给 1.2.2.1。
--
-- ===========================================================================
-- 二、模板表的形状
-- ===========================================================================
-- **scope_type 三值**：
--   TENANT     —— 全员模板（scope_ref 为空）
--   ROLE       —— 岗位模板（绑定某个 role.id）
--   DEPARTMENT —— 团队模板（绑定某个 department.id）
-- 蓝图写的是「按角色/团队」，`role` 与 `org_unit` 都是它的共享数据 —— 本仓的
-- 「团队」落点是 `department`（17.2.1.1 部门树），所以两种 scope 都有真实落点。
--
-- **为什么用 role_id / department_id 两个可空列，而不是一个多态 scope_ref**：
-- 多态列（scope_type + scope_ref uuid）没法加外键 —— ROLE 行的 scope_ref 指向 role.id，
-- DEPARTMENT 行指向 department.id，一列同时挂两张表的外键会让其中一种 scope 永远插不进去。
-- 两个可空列 + 一个 CHECK 表达「恰好一个非空」就能把**真正的外键**加回来，
-- 于是「模板指向一个不存在的岗位」在数据库层就被拒，不靠服务层记得检查。
--
-- **entries 用 JSONB 而不是子表**：
-- 模板的入口清单是「一个模板下的若干条有序配置」，整体读、整体改（新增/删除/调序都改同一个数组），
-- 从不按「某一条入口」单独查询或关联。与 1.4.1.2 的 `user_preference.preference jsonb`
-- 同一口径（按 key 存一袋结构化配置）。
-- ⚠️ 但**形状不许自由**：下面的 `shortcut_entries_shape_ok` 把「每条入口必须有 key/label/
-- pageCode/path、key 必须在模板内唯一、path 必须是安全相对路径」钉在**数据库层** ——
-- 绕过服务层直连 SQL 也一样被拒。
--
-- ===========================================================================
-- 三、覆盖项表的形状
-- ===========================================================================
-- **覆盖的合并单元是 target_key（入口标识），不是模板 id**：
-- 蓝图写「个人覆盖项覆盖同 target 的模板项」—— 覆盖的是**入口**，不是「某张模板的第几行」。
-- 于是同一个入口出现在多张模板里时，个人只覆盖一次，不必知道自己命中的是哪张模板。
--
-- **action 只有 UPSERT / REMOVE 两态**：
--   UPSERT —— 覆盖（label / pageCode / path / icon / position 逐字段覆盖，
--              未给的字段沿用模板项的值；整条 UPSERT 至少要给一个可覆盖字段）
--   REMOVE —— 移除（个人移除优先于模板存在）
-- 这就是蓝图要求的「确定、可测」的合并规则：不依赖插入顺序，不依赖查询顺序。
--
-- **覆盖项只能覆盖「已经存在的模板入口」**：
-- 本车道**不实现 1.2.2.1 收藏**。如果允许覆盖项凭空新增一个模板里没有的入口，
-- 那就是「常用页面收藏」的另一个实现 —— 同一个能力两套真相。
-- 所以写入覆盖项时必须命中生效集合里真实存在的 target_key，否则 400；
-- 模板后来删掉了那个入口，覆盖项就变成孤儿（不再产生任何入口），不报错、不复活。
--
-- ===========================================================================
-- 四、租户隔离（RLS）
-- ===========================================================================
-- 与 identity V1 / V3 / V160 / V326 / V330 同构：
--   ENABLE + **FORCE** RLS，policy 只认 `app.current_tenant`。
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，
-- 否则迁移脚本或后台任务用 owner 身份一连就绕过了隔离。
--
-- ⚠️ identity 侧**没有** `app.actor` 这个 GUC ⇒ 「只看自己的」不在 RLS 层，
-- 由服务层的 `membership_id = ?` 条件保证（membership_id 来自已认证会话，
-- 不来自请求参数，更不来自 `X-Tenant-Id` 头）。
--
-- ===========================================================================
-- 五、权限码登记
-- ===========================================================================
-- identity.shortcut.read    —— 看模板与「最终生效的快捷入口集合」
-- identity.shortcut.manage —— 建/改/删模板（岗位模板是组织配置，属管理员动作）
--
-- 与 V3 / V4 / V8 / V104 同规则：租户所有者始终拥有全部页面权限
-- （存量 OWNER 角色也要补上），否则新接入的动作对既有租户不可见。
--
-- ⚠️ 「模板里的入口若指向用户无权访问的页面，该入口不得生效」——
-- 这条不是权限码能表达的：它取决于**每个用户**实际持有的页面权限，
-- 由服务层在合并时逐条过滤（入口的 pageCode 对应的 `<pageCode>.read` 不在用户的权限集合里
-- ⇒ 该入口不生效）。权限变更后自动失效并回落，不需要额外的同步任务。

-- ---------------------------------------------------------------------------
-- 一、入口形状的数据库层约束
-- ---------------------------------------------------------------------------
-- 为什么要有这一层：模板的入口清单会被合并进「最终生效的快捷入口集合」，
-- 一条 key 重复或 path 越界的入口会让合并结果出现「同一个入口出现两次」或
-- 「跳到站外」这种没法在界面上解释的现象。
-- 服务层已经逐条校验（返回 400），这里再钉一层是为了**绕过服务层直连 SQL 也一样被拒**。
-- ⚠️ 必须先建函数再建表：CHECK 约束在建表时就要能解析到函数。
CREATE FUNCTION shortcut_entry_shape_ok(entry jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT jsonb_exists(entry, 'key')
     AND jsonb_exists(entry, 'label')
     AND jsonb_exists(entry, 'pageCode')
     AND jsonb_exists(entry, 'path')
     AND jsonb_typeof(entry -> 'key') = 'string'
     AND jsonb_typeof(entry -> 'label') = 'string'
     AND jsonb_typeof(entry -> 'pageCode') = 'string'
     AND jsonb_typeof(entry -> 'path') = 'string'
     -- 入口标识是受控标识符（小写开头，可含 . _ -），不是自由文本：
     -- 它是「覆盖项」与「模板项」的合并单元，必须能当稳定的键用。
     AND (entry ->> 'key') ~ '^[a-z0-9][a-z0-9._-]{0,99}$'
     AND length(entry ->> 'label') BETWEEN 1 AND 100
     AND length(entry ->> 'pageCode') BETWEEN 1 AND 64
     -- 跳转目标是站内相对路径：必须以 / 开头，不许带协议、不许带 ..（目录穿越）。
     AND (entry ->> 'path') LIKE '/%'
     AND (entry ->> 'path') NOT LIKE '%..%'
     AND length(entry ->> 'path') BETWEEN 1 AND 200
     AND (
       NOT jsonb_exists(entry, 'icon')
       OR (jsonb_typeof(entry -> 'icon') = 'string' AND length(entry ->> 'icon') <= 40)
     )
     AND (
       NOT jsonb_exists(entry, 'position')
       OR jsonb_typeof(entry -> 'position') = 'number'
     )
$$;

CREATE FUNCTION shortcut_entries_shape_ok(entries jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT jsonb_typeof(entries) = 'array'
     AND jsonb_array_length(entries) <= 100
     -- 同一个模板里入口标识必须唯一：重复的 key 会让合并结果出现同一个入口两次。
     AND (
       SELECT count(DISTINCT item ->> 'key')
       FROM jsonb_array_elements(entries) AS item(entry)
     ) = jsonb_array_length(entries)
     AND NOT EXISTS (
       SELECT 1
       FROM jsonb_array_elements(entries) AS item(entry)
       WHERE NOT shortcut_entry_shape_ok(item.entry)
     )
$$;

REVOKE EXECUTE ON FUNCTION shortcut_entry_shape_ok(jsonb) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION shortcut_entries_shape_ok(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shortcut_entry_shape_ok(jsonb) TO identity_app;
GRANT EXECUTE ON FUNCTION shortcut_entries_shape_ok(jsonb) TO identity_app;

-- ---------------------------------------------------------------------------
-- 二、岗位/角色快捷入口模板
-- ---------------------------------------------------------------------------
CREATE TABLE shortcut_entry_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 模板编码：租户内唯一（岗位模板会被引用与比对，不能有两个同名）。
  code varchar(64) NOT NULL,
  name varchar(100) NOT NULL,
  description varchar(200) NOT NULL DEFAULT '',

  -- 适用范围：TENANT 全员 / ROLE 岗位 / DEPARTMENT 团队。
  scope_type varchar(16) NOT NULL,

  -- 适用范围的具体指向（见上文「为什么用两个可空列」）。
  role_id uuid,
  department_id uuid,

  -- ACTIVE 生效 / INACTIVE 停用。停用不是删除：模板是岗位预设，
  -- 停用后新成员拿不到这批入口，但历史配置还留在库里可回滚。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE',

  -- 生效顺序：数字小的先合并（多条模板命中同一个入口时，先来的赢）。
  priority int NOT NULL DEFAULT 100,

  -- 入口清单。形状由 shortcut_entries_shape_ok 在数据库层钉死。
  entries jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- 维护人（租户内成员）。**不接受请求参数传入**，取自已认证会话。
  created_by uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, code),

  CONSTRAINT shortcut_entry_template_scope_fk_role
    FOREIGN KEY (tenant_id, role_id) REFERENCES role(tenant_id, id),
  CONSTRAINT shortcut_entry_template_scope_fk_department
    FOREIGN KEY (tenant_id, department_id) REFERENCES department(tenant_id, id),
  CONSTRAINT shortcut_entry_template_scope_fk_creator
    FOREIGN KEY (tenant_id, created_by) REFERENCES membership(tenant_id, id),

  CONSTRAINT shortcut_entry_template_code_chk
    CHECK (btrim(code) <> '' AND length(code) <= 64 AND code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
  CONSTRAINT shortcut_entry_template_name_chk
    CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT shortcut_entry_template_scope_type_chk
    CHECK (scope_type IN ('TENANT', 'ROLE', 'DEPARTMENT')),
  CONSTRAINT shortcut_entry_template_status_chk
    CHECK (status IN ('ACTIVE', 'INACTIVE')),
  -- 恰好一个指向：TENANT 两个都空，ROLE 只填 role_id，DEPARTMENT 只填 department_id。
  CONSTRAINT shortcut_entry_template_scope_shape_chk
    CHECK (
      (scope_type = 'TENANT' AND role_id IS NULL AND department_id IS NULL)
      OR (scope_type = 'ROLE' AND role_id IS NOT NULL AND department_id IS NULL)
      OR (scope_type = 'DEPARTMENT' AND role_id IS NULL AND department_id IS NOT NULL)
    ),
  CONSTRAINT shortcut_entry_template_entries_chk
    CHECK (shortcut_entries_shape_ok(entries))
);

-- 「这个用户的岗位/团队命中了哪些模板」是本模块唯一的主查询口径。
CREATE INDEX shortcut_entry_template_by_scope
  ON shortcut_entry_template(tenant_id, scope_type, role_id, department_id, priority);

-- ---------------------------------------------------------------------------
-- 三、个人覆盖项
-- ---------------------------------------------------------------------------
CREATE TABLE user_shortcut_override (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 覆盖项挂在**租户内成员**上（不是 app_user —— 那是跨租户全局账号）。
  membership_id uuid NOT NULL,

  -- 覆盖的入口标识：必须命中生效集合里真实存在的模板入口。
  target_key varchar(100) NOT NULL,

  -- UPSERT 覆盖 / REMOVE 移除。个人移除优先于模板存在。
  action varchar(16) NOT NULL,

  -- UPSERT 时逐字段覆盖；未给的字段沿用模板项的值。
  label varchar(100),
  page_code varchar(64),
  path varchar(200),
  icon varchar(40),
  position int,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一个人对一个入口只有一条覆盖项（重复提交是覆盖，不是叠加）。
  UNIQUE (tenant_id, membership_id, target_key),

  CONSTRAINT user_shortcut_override_membership_fk
    FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id),
  CONSTRAINT user_shortcut_override_target_key_chk
    CHECK (btrim(target_key) <> '' AND length(target_key) <= 100),
  CONSTRAINT user_shortcut_override_action_chk
    CHECK (action IN ('UPSERT', 'REMOVE')),
  -- UPSERT 至少要给一个可覆盖字段，否则这条覆盖项什么都不改（写进去等于没写）。
  CONSTRAINT user_shortcut_override_upsert_fields_chk
    CHECK (
      action = 'REMOVE'
      OR label IS NOT NULL
      OR page_code IS NOT NULL
      OR path IS NOT NULL
      OR icon IS NOT NULL
      OR position IS NOT NULL
    ),
  -- 给了的字段本身也要合法（覆盖不能绕过模板项的形状约束）。
  CONSTRAINT user_shortcut_override_label_chk
    CHECK (label IS NULL OR length(label) BETWEEN 1 AND 100),
  CONSTRAINT user_shortcut_override_page_code_chk
    CHECK (page_code IS NULL OR length(page_code) BETWEEN 1 AND 64),
  CONSTRAINT user_shortcut_override_path_chk
    CHECK (path IS NULL OR (path LIKE '/%' AND path NOT LIKE '%..%' AND length(path) BETWEEN 1 AND 200)),
  CONSTRAINT user_shortcut_override_icon_chk
    CHECK (icon IS NULL OR length(icon) <= 40)
);

CREATE INDEX user_shortcut_override_by_membership
  ON user_shortcut_override(tenant_id, membership_id);

-- ---------------------------------------------------------------------------
-- 四、租户隔离（RLS）
-- ---------------------------------------------------------------------------
ALTER TABLE shortcut_entry_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE shortcut_entry_template FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON shortcut_entry_template
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE user_shortcut_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_shortcut_override FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON user_shortcut_override
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 五、授权
-- ---------------------------------------------------------------------------
-- 运行角色是 identity_app：模板要能读能写（管理动作），覆盖项要能读能写能删。
GRANT SELECT, INSERT, UPDATE, DELETE ON shortcut_entry_template TO identity_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_shortcut_override TO identity_app;

-- ---------------------------------------------------------------------------
-- 六、权限码登记
-- ---------------------------------------------------------------------------
INSERT INTO page_resource(code, name) VALUES ('identity.shortcut', '快捷入口配置')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission VALUES
  ('identity.shortcut.read', 'identity.shortcut', 'read'),
  ('identity.shortcut.manage', 'identity.shortcut', 'manage')
ON CONFLICT (code) DO NOTHING;

-- 租户所有者始终拥有全部页面权限（与 V3 / V4 / V8 / V104 同规则）：
-- 存量 OWNER 角色也要补上，否则新接入的动作对既有租户不可见。
INSERT INTO role_permission SELECT r.tenant_id, r.id, p.code FROM role r CROSS JOIN permission p
 WHERE r.code = 'OWNER'
   AND p.code IN ('identity.shortcut.read', 'identity.shortcut.manage')
ON CONFLICT DO NOTHING;
