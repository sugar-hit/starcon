-- ===========================================================================
-- V427 · notification_template —— 18.5.1.1「模板管理」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.5.1.1 原文：
--
--   「**模板管理**：管理通知与消息模板：模板内容、变量、适用场景、语言版本、启用状态；
--     修改后新产生的通知使用新版本，历史通知保留当时内容。
--     输入：模板编辑；变量定义。输出：模板记录 + 渲染预览。
--     关系：上游 = 3.4.2 文案资产；下游 = 1.3.1.1、15.1.1.2、18.5.2。
--     **模板必须版本化**，已发出的通知不能被新模板"篡改"。」
--
-- 落点：**platform 服务**（18 卷的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。本迁移是该服务 18.5 的第一批表。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不改 18.4.2.1 的 platform_text_template**。
--     那一张是「**客服回复草稿**」模板（AI 翻译链路的产物：术语保护 / 违禁词检查 /
--     置信度 / 人工确认后发布）；本模块是「**通知与消息模板**」：按**适用场景 × 语言**
--     组织、**版本化**、可被下游按**时刻**解析出「当时生效的那一版」。
--     两者的对象、生命周期与下游完全不同（客服话术是 AI 链路的产物，通知模板是
--     下游 1.3.1.1 / 15.1.1.2 / 18.5.2 取内容的**来源**），所以**各建各的表**，
--     一个字也不去 ALTER 对方的表。
--
--   * **不建「通知投递」表**。
--     投递记录归 18.5.2.1（尚未交付）。本模块只保证「下游按时刻能取到**当时那一版**，
--     并能引用版本 id + 内容快照」—— 那是 18.5.2.1 要接的那根线。
--
--   * **不物理删历史版本**。
--     蓝图加粗句「已发出的通知不能被新模板篡改」在数据层意味着：版本行一旦写下，
--     就**没有 UPDATE / DELETE 这条路**。删除模板主记录时只能「停用 + 归档」，
--     版本行原样留着。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单（三张表，一条职责链）
-- ---------------------------------------------------------------------------
--
--   platform_notify_template           —— 模板**主记录**（key / 场景 / 语言 / 名称 / 归档）
--   platform_notify_template_version   —— **不可变**版本行（版本号 + 标题 + 正文 + 变量定义）
--   platform_notify_template_activation —— **启用事件日志**（哪个版本在什么时段生效）
--
-- 三张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 为什么「编辑 = 新增版本行」在数据层成立
-- ---------------------------------------------------------------------------
--
--   版本行**只 INSERT**：GRANT 里没有 UPDATE / DELETE，另外再挂一个 BEFORE UPDATE OR DELETE
--   触发器兜底（直连 SQL 也改不动）。所以：
--
--     · 编辑模板正文 ⇒ **追加**一行 version_no = N+1；旧的 N 号行原样不动；
--     · 「新产生的通知用新版本、历史通知保留当时内容」因此是**结构性成立**的，
--       不是靠服务层记得「别去改老版本」。
--
--   🔴 **本表的授权就是不可篡改的第一道防线**：运行角色连 UPDATE 权限都没有，
--   第二个防线是触发器（万一将来有人给了权限），第三道是服务层只提供「新增版本」
--   这一个写入口。
--
-- ---------------------------------------------------------------------------
-- 3. 为什么要有「启用事件日志」这一层（而不是在主记录上挂一个指针）
-- ---------------------------------------------------------------------------
--
--   蓝图要求「**按时刻**解析当时生效版本」：
--     · 下游在 T 时刻取模板 ⇒ 必须拿到 **T 时刻生效的那一版**；
--     · 后来有人在 T+1 改了模板 ⇒ T 时刻那条通知引用的仍然是**老内容**。
--
--   如果只在主记录上挂一个「当前版本号」指针，这个能力**根本做不到** ——
--   指针只说得清「现在是谁」，说不清「昨天上午是谁」。
--
--   ⇒ 启用动作写成**事件行**（append-only）：
--       (template_id, version_id, activated_at, deactivated_at)
--     · 启用版本 V：把当前那条未结束的 activation 填上 deactivated_at，
--       再 INSERT 一条新的 activation；
--     · 「T 时刻生效的版本」= T 时刻**仍在有效期内**的那条 activation 的 version_id。
--
--   🔴 **同时只允许一个启用版本**由**部分唯一索引**在数据库层保证：
--
--       CREATE UNIQUE INDEX ... ON (tenant_id, template_id) WHERE deactivated_at IS NULL;
--
--   ⇒ 同一个 (模板 key × 场景 × 语言) 主记录下，**不可能**同时存在两个「生效中」的版本。
--      这不是服务层的「记得检查」，是数据库写不进去。
--
--   ⚠️ activation 行的 version_id / template_id / activated_at **不可改**
--      （触发器挡住）：只有 deactivated_at 允许被填上（区间收口）。
--      能改指向的日志**就是**一份可篡改的历史。
--
-- ---------------------------------------------------------------------------
-- 4. 多语言：同一 key × 不同 language 各有独立版本链
-- ---------------------------------------------------------------------------
--
--   · 主记录唯一键是 (tenant_id, template_key, scene, language) ——
--     同一 key 在**不同语言**下是**不同的主记录**，各有各的版本链与启用状态；
--   · language 用 **IANA 形状** CHECK（`^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$`）：
--     允许 en / zh-CN / pt-BR 这类形态，**不**把语言写成自由文本。
--     刻意**不**把语言做成一张带租户数据的表：语言是**语言层面的事实**，
--     与租户无关（照抄 crm ReplyWordingChecker「内置词表是规则不是数据源」的纪律），
--     做成租户表只会让每个租户都要先维护一遍同一份语言清单。
--
-- ---------------------------------------------------------------------------
-- 5. 场景词表
-- ---------------------------------------------------------------------------
--
--   scene 是**受控词表**（CHECK 约束），不是自由文本：下游 1.3.1.1 / 15.1.1.2 / 18.5.2
--   按场景取模板，自由文本会让「按场景取模板」退化成模糊匹配。
--
-- ---------------------------------------------------------------------------
-- 6. 时间与金额
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 **timestamptz**；服务层用 **OffsetDateTime** 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--   * 本模块**不落任何金额**：模板里出现的金额是**变量取值**（渲染时填进去的文本），
--     不是本模块记的账。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_notify_template —— 模板主记录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个「模板 key × 适用场景 × 语言」。
--
-- 🔴 主记录**不含**「当前版本号」也不含「当前启用版本」指针：
--   这两样都能从版本表 / 启用事件日志**推**出来。少一份冗余状态，就少一处能漂移的地方
--   （指针与日志不一致时，运营会看到「界面上说启用的是 v3，解析出来却是 v2」）。
CREATE TABLE platform_notify_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 模板 key（机器可读，下游按它取模板）。租户内唯一（连同场景与语言）。
  template_key varchar(64) NOT NULL,
  -- 适用场景（受控词表，见第五节）。
  scene varchar(32) NOT NULL,
  -- 语言（IANA 形状，见第四节）。
  language varchar(16) NOT NULL,

  -- 人可读名称与说明。
  name varchar(120) NOT NULL,
  description varchar(500) NOT NULL DEFAULT '',

  -- 🔴 **归档 = 软删除**：模板不物理删（它的版本行是历史通知的「当时内容」依据）。
  --   归档后不再出现在默认列表里，但版本行与启用历史原样保留。
  archived_at timestamptz,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一 key × 场景 × 语言只有一条；不同语言各有独立版本链（见第四节）。
  UNIQUE(tenant_id, template_key, scene, language),

  CHECK(btrim(template_key) <> '' AND length(template_key) <= 64),
  -- key 的字符集收紧到「字母 / 数字 / 点 / 下划线 / 连字符」：它是给下游拼 URL 与查表用的。
  CHECK(template_key ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
  -- 🔴 适用场景是**受控词表**（见第五节）：下游按场景取模板，自由文本会让
  --   「按场景取模板」退化成模糊匹配。
  CHECK(scene IN (
    'ORDER_STATUS',
    'STOCK',
    'PRICE',
    'LOGISTICS',
    'CUSTOMER_SERVICE',
    'MARKETING',
    'SYSTEM'
  )),
  CHECK(btrim(name) <> '' AND length(name) <= 120),
  CHECK(length(description) <= 500),
  -- 语言：IANA 形状（en / zh-CN / pt-BR）。
  CHECK(language ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
);

CREATE INDEX platform_notify_template_by_scene
  ON platform_notify_template(tenant_id, scene, language);

-- ---------------------------------------------------------------------------
-- 2. platform_notify_template_version —— 不可变版本行
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个模板的某一版内容。
--
-- 🔴 **只 INSERT**：GRANT 不给 UPDATE / DELETE，另有触发器兜底（见第九节）。
--
-- variables 是**变量定义**（jsonb 数组），元素形如：
--   {"name":"buyer_name","type":"TEXT","required":true,"defaultValue":null,"description":"买家昵称"}
-- 登记时服务端会**重算正文里的 {{占位符}}**并核对：
--   正文里出现但没声明的变量 ⇒ 拒收（400，变量名点名）；
--   声明了但正文里没用的变量 ⇒ 允许（可能是给下游取值用的约定）。
CREATE TABLE platform_notify_template_version (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,

  -- 版本号：同一模板内从 1 递增，**只增不改**。
  version_no int NOT NULL,

  -- 标题与正文（含 {{变量}} 占位符）。标题可以为空（有的通知没有标题）。
  subject text NOT NULL DEFAULT '',
  body text NOT NULL,

  -- 变量定义（jsonb 数组）。
  variables jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- 这一版改了什么（人话，留痕）。
  change_note varchar(500) NOT NULL DEFAULT '',

  -- 作者（来自已认证会话的 user_id，**不接受请求参数传入**）。
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一模板内版本号唯一。
  UNIQUE(tenant_id, template_id, version_no),

  -- 🔴 ON DELETE RESTRICT：主记录被物理删时，版本行会挡住它 ——
  --   「先处理版本行」这条纪律由外键在数据库层兜底，不靠服务层记得。
  FOREIGN KEY(tenant_id, template_id) REFERENCES platform_notify_template(tenant_id, id) ON DELETE RESTRICT,

  CHECK(version_no >= 1),
  CHECK(btrim(body) <> ''),
  -- variables 必须是 jsonb 数组（不是对象 / 标量）。
  CHECK(jsonb_typeof(variables) = 'array'),
  CHECK(length(change_note) <= 500)
);

CREATE INDEX platform_notify_template_version_by_template
  ON platform_notify_template_version(tenant_id, template_id, version_no DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_notify_template_activation —— 启用事件日志
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个版本从某时刻起生效（到某时刻止）」。
--
-- 🔴 **「同时只允许一个启用版本」由部分唯一索引保证**（见本节末尾）。
CREATE TABLE platform_notify_template_activation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  version_id uuid NOT NULL,

  -- 生效时刻（timestamptz）。
  activated_at timestamptz NOT NULL,
  -- 失效时刻。**为空 = 仍在生效**（当前生效的那一版）。
  deactivated_at timestamptz,
  -- 启用者（已认证会话的 user_id）。
  activated_by uuid,

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, template_id) REFERENCES platform_notify_template(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY(tenant_id, version_id) REFERENCES platform_notify_template_version(tenant_id, id) ON DELETE RESTRICT,

  CHECK(deactivated_at IS NULL OR deactivated_at >= activated_at)
);

CREATE INDEX platform_notify_template_activation_by_template
  ON platform_notify_template_activation(tenant_id, template_id, activated_at DESC);

-- 🔴 **同一模板同时只能有一个「生效中」的版本** —— 数据库层写不进去第二条。
CREATE UNIQUE INDEX platform_notify_template_activation_one_open
  ON platform_notify_template_activation(tenant_id, template_id)
  WHERE deactivated_at IS NULL;

-- ===========================================================================
-- 4. 租户隔离（RLS）
-- ===========================================================================
--
-- 三张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V380 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_notify_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_notify_template FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_notify_template
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_notify_template_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_notify_template_version FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_notify_template_version
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_notify_template_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_notify_template_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_notify_template_activation
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 5. 租户触发器兜底（直连 SQL 也写不出跨租户的行）
-- ---------------------------------------------------------------------------
--
-- RLS 已经在**读**与**写**两侧挡住跨租户；触发器再挡一道，是因为
-- 「owner 身份连进来」这类场景（后台任务、运维脚本）容易让人以为 RLS 也会跟着生效。
CREATE FUNCTION platform_notify_template_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入通知模板'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '通知模板的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION platform_notify_template_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_notify_template_tenant_guard() TO platform_app;

CREATE TRIGGER platform_notify_template_tenant_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_notify_template
  FOR EACH ROW EXECUTE FUNCTION platform_notify_template_tenant_guard();

CREATE FUNCTION platform_notify_template_version_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入通知模板版本'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '通知模板版本的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION platform_notify_template_version_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_notify_template_version_tenant_guard() TO platform_app;

CREATE TRIGGER platform_notify_template_version_tenant_guard_trigger
  BEFORE INSERT ON platform_notify_template_version
  FOR EACH ROW EXECUTE FUNCTION platform_notify_template_version_tenant_guard();

CREATE FUNCTION platform_notify_template_activation_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入通知模板启用记录'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '通知模板启用记录的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION platform_notify_template_activation_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_notify_template_activation_tenant_guard() TO platform_app;

CREATE TRIGGER platform_notify_template_activation_tenant_guard_trigger
  BEFORE INSERT OR UPDATE ON platform_notify_template_activation
  FOR EACH ROW EXECUTE FUNCTION platform_notify_template_activation_tenant_guard();

-- ---------------------------------------------------------------------------
-- 6. 版本行不可篡改（触发器兜底）
-- ---------------------------------------------------------------------------
--
-- GRANT 里已经不给 UPDATE / DELETE；这里再挂一道触发器：
-- 万一将来有人「顺手」给了权限，改版本行这一件事也会在数据库层直接失败。
--
-- 🔴 报错文案要写清正确做法：**编辑 = 新增一个版本**。
CREATE FUNCTION platform_notify_template_version_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '通知模板的版本行不可修改或删除（版本必须不可篡改）：编辑请新增一个版本'
    USING ERRCODE = '42501';
END $$;

CREATE TRIGGER platform_notify_template_version_immutable_trigger
  BEFORE UPDATE OR DELETE ON platform_notify_template_version
  FOR EACH ROW EXECUTE FUNCTION platform_notify_template_version_immutable();

-- ---------------------------------------------------------------------------
-- 7. 启用事件日志不可改指向
-- ---------------------------------------------------------------------------
--
-- 只允许把 deactivated_at 填上（区间收口）；template_id / version_id / activated_at
-- 一律不可改 —— 能改指向的日志**就是**一份可篡改的历史。
CREATE FUNCTION platform_notify_template_activation_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.template_id IS DISTINCT FROM OLD.template_id
     OR NEW.version_id IS DISTINCT FROM OLD.version_id
     OR NEW.activated_at IS DISTINCT FROM OLD.activated_at THEN
    RAISE EXCEPTION '通知模板启用记录的指向不可修改：只允许填写 deactivated_at'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER platform_notify_template_activation_immutable_trigger
  BEFORE UPDATE ON platform_notify_template_activation
  FOR EACH ROW EXECUTE FUNCTION platform_notify_template_activation_immutable();

-- ---------------------------------------------------------------------------
-- 8. 授权
-- ---------------------------------------------------------------------------
--
-- 运行角色是 platform_app：
--   · platform_notify_template           —— 读（清单 / 详情）+ 写（建模板 / 改名称 / 归档）。
--     🔴 **不给 DELETE**：删除模板 = 停用 + 归档（改 archived_at），**不物理删**主记录 ——
--        它的版本行是历史通知的「当时内容」依据。
--   · platform_notify_template_version   —— **只给 SELECT / INSERT**：
--     🔴 **不给 UPDATE / DELETE** —— 版本不可篡改由**权限层**兜底，
--        再加上第六节的触发器，双保险。
--   · platform_notify_template_activation —— 读 + INSERT + UPDATE（只用于收口区间），
--     **不给 DELETE** —— 启用历史是「当时谁生效」的证据，删掉它等于让那段历史说不清。
GRANT SELECT,INSERT,UPDATE ON platform_notify_template TO platform_app;
GRANT SELECT,INSERT ON platform_notify_template_version TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_notify_template_activation TO platform_app;
