-- ===========================================================================
-- 16.2.2.2 认证与资质（阶段 D7，L3 锚点 16.2.2，后端主责服务 channel）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md）：
--   「管理产品认证与资质（CE、FCC、RoHS、PSE、FDA、CCC 等）：认证编号、有效期、适用 SKU、
--     对应类目要求，支持上传扫描件与到期提醒。
--     输入：认证录入；文件上传；有效期。
--     输出：认证档案 + 到期提醒 + 覆盖情况统计（哪些 SKU 缺认证）。
--     关系：上游 = 6.1.2.3 供应商资质；下游 = 16.2.2.1 预检、4 刊登。
--     **认证缺失是 listing 被抑制与下架的高频原因**，且属于"上架时不管、出事才补"的典型滞后项。」
--
-- 归属：**channel 服务**（卷 16 合规与风控；同 L3 的 16.2.2.1 合规预检已落在 channel V197）。
-- 前端路由 /compliance/certifications，后端命名空间 /channel/product-certifications ——
--   与 16.2.2.1 的 /channel/compliance-precheck **不共用前缀**（并行车道的冲突高发点）。
-- 本迁移**不 ALTER 任何既有表**，跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、命名空间隔离（与同批 / 相邻车道互不撞）
-- ===========================================================================
--   * 本模块一律 `product_certification_*` 前缀。
--   * 16.2.2.1 合规预检占 `compliance_check_*`（V197）—— 本模块不碰。
--   * 16.2.1.1 规则库未来要占 `compliance_*`（规则 / 词库 / 版本）—— 本模块不建规则库。
--   * 4.2.3.2（卷 04 的合规预检 违禁词/侵权）占 `listing_compliance_*` —— 本模块不碰。
--   * catalog 的 `sku_certification`（3.7.x 完整度评分的「认证**声明**」）在**另一个库**，
--     本模块不跨库读写、不建跨库 FK；两者的边界写在第九节。
--   * 内置认证类型词表 `certification_type` 是**全局参考数据**（无 tenant_id），
--     与 channel V1 的 `platform` / `marketplace` 同族：平台码表本来就不带租户。
--     租户级字典是 `tenant_certification_type`（带 tenant_id + RLS）。
--
-- ===========================================================================
-- 二、认证类型是**受控词表**，不是代码里的常量数组
-- ===========================================================================
-- 蓝图点名的 CE / FCC / RoHS / PSE / FDA / CCC 等，落在两张表里：
--   * `certification_type`（全局内置词表，迁移里 INSERT 种子数据）——
--     「哪些认证类型存在」是**数据库里的数据**，不是 Java 里的常量数组。
--   * `tenant_certification_type`（租户字典，tenant_id NOT NULL + RLS）——
--     租户可以**新增**自己要的认证类型，也可以**改名 / 停用**内置类型。
--     生效词表 = 内置 ∪ 租户，**租户行优先**（租户行存在就以租户行为准）。
--
-- 🔴 为什么不是「一张表 + 内置类型按租户复制一遍」：那要一个租户开通钩子，
--   新租户在钩子跑之前词表是空的，运营录不进第一张证 —— 而「认证类型下拉框是空的」
--   会被读成「这个品类不需要认证」。内置词表做成全局参考数据就没有这个空窗。
--
-- 🔴 词表由**DB 触发器**兜底（guard_product_certification_type_exists）：
--   直连数据库插一行「认证类型 = X」时，X 必须在生效词表里，否则直接拒绝。
--   服务层只是第一道闸，不是唯一一道。
--
-- ===========================================================================
-- 三、**到期状态不落库，由读出口按数据库时钟现算**（与 6.1.2.3 同一纪律）
-- ===========================================================================
-- 表里**没有** expiry_state 列。存一列就要一个每日任务去维护它，而维护出来的那天
-- 与读出口算出的那天差一天时，「这张证到底过期没有」会答错 —— 而这个答案直接决定
-- 能不能刊登。「即将过期」的窗口来自**租户级策略** `product_certification_policy.warning_days`
-- （默认 30 天），不是代码常量。
--
-- ===========================================================================
-- 四、**扫描件真存**：文件本体进受管目录，元数据入库
-- ===========================================================================
-- `product_certification_document` 存的是**存储 key + 文件名 + MIME + 字节数 + 上传时刻**，
-- 文件本体写到服务端的受管目录（LocalCertificationDocumentStorage，路径穿越防护），
-- 不进业务库（二进制进业务库会让备份、迁移与 RLS 审计都变成搬文件的工作）。
-- 文件行**只追加**：续期是「再上传一份」，不是「覆盖掉原来那份」——
-- 原来交的是什么，是合规追溯要用的凭据。
--
-- ===========================================================================
-- 五、**覆盖情况统计的来源**（「哪些 SKU 缺认证」必须真的算得出来）
-- ===========================================================================
-- 只登记「有哪些认证」是答不出「谁缺认证」的：缺的那一行本来就不存在。
-- 所以本模块自己维护**要求侧**的登记：
--   * `product_certification_requirement`（tenant_id, sku_id, certification_type_code [, category_id]）
--     = 「这个 SKU 需要这个认证类型」（可注明来自哪个类目要求）。
--   * `product_certification_sku` = 某张认证覆盖哪些 SKU。
--   * `product_certification_category` = 某张认证对应哪些类目要求。
-- 覆盖判定 = 对每条 (sku, type) 要求，存在一张**当前有效**且**适用该 SKU** 的同类型认证。
-- 按 SKU / 按类目 / 按认证类型三个口径都是**现算**，不是留一个空字段。
--
-- 🔴 SKU 与类目主数据在 **catalog**，本库只存**逻辑 id + 名称快照**，跨库不建 FK
--   （AGENTS.md 禁止跨服务数据库访问）：「这个 SKU 存不存在、叫什么」由服务层经 catalog 的
--   **授权只读接口**核对，读不到就**显式报读不到**，绝不拿空列表冒充。
--
-- ===========================================================================
-- 1. certification_type · 内置认证类型词表（全局参考数据，无 tenant_id）
-- ===========================================================================
CREATE TABLE certification_type (
  code varchar(32) PRIMARY KEY,
  name varchar(64) NOT NULL,
  -- GLOBAL 全球通用（CE / FCC） / MARKETPLACE 平台或市场特定（PSE / FDA / CCC）
  scope varchar(16) NOT NULL CHECK(scope IN ('GLOBAL','MARKETPLACE')),
  description text NOT NULL DEFAULT '',
  sort_order integer NOT NULL DEFAULT 100,
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
COMMENT ON TABLE certification_type IS
  '16.2.2.2 · 内置认证类型词表（全局参考数据，与 platform / marketplace 同族，不带 tenant_id）。'
  '内置类型 = 本表的种子数据；租户可新增 / 改名 / 停用，改动落在 tenant_certification_type。';

-- 🔴 种子数据写在**迁移里**（数据库里的数据），不是 Java 里的常量数组。
INSERT INTO certification_type (code, name, scope, description, sort_order) VALUES
  ('CE',       'CE 认证（欧盟符合性）',        'GLOBAL',      '欧盟市场产品安全与健康环保的强制合格评定标志',        10),
  ('FCC',      'FCC 认证（美国联邦通信）',      'GLOBAL',      '美国电磁兼容与射频设备合规认证',                       20),
  ('ROHS',     'RoHS 符合性（有害物质限制）',  'GLOBAL',      '欧盟对电子电气产品有害物质限量的符合性声明',            30),
  ('REACH',    'REACH 符合性（化学品法规）',   'GLOBAL',      '欧盟化学品注册评估许可法规符合性声明',                  40),
  ('PSE',      'PSE 认证（日本电器用品）',      'MARKETPLACE', '日本《电气用品安全法》要求的产品安全义务表示',          50),
  ('FDA',      'FDA 符合性（美国食品药品）',    'MARKETPLACE', '美国食品药品监督管理局相关产品的注册或备案要求',        60),
  ('CCC',      'CCC 认证（中国强制）',          'MARKETPLACE', '中国强制性产品认证',                                  70),
  ('UKCA',     'UKCA 认证（英国）',            'GLOBAL',      '英国脱欧后大不列颠市场的产品符合性标志',                80),
  ('EMC',      'EMC 电磁兼容（EN 电磁兼容指令）','GLOBAL',     '欧盟电磁兼容指令要求的电磁兼容符合性声明',              90),
  ('LVD',      'LVD 低电压指令',                'GLOBAL',      '欧盟低电压指令要求的安全与电气要求符合性声明',         100),
  ('CPSIA',    'CPSIA（美国消费品安全）',       'MARKETPLACE', '美国消费品安全改进法对儿童用品的铅与邻苯要求',         110),
  ('EN71',     'EN71（欧盟玩具安全）',          'MARKETPLACE', '欧盟玩具安全指令下的迁移元素与机械物理化学测试要求',   120),
  ('MSDS',     'MSDS / SDS（安全数据表）',      'GLOBAL',      '化学品安全技术说明书，属于危险品运输与仓储的必备资料',  130),
  ('OTHER',    '其它认证',                     'GLOBAL',      '本词表未覆盖的认证类型；租户可在字典里自建更精确的类型', 900);

-- ===========================================================================
-- 2. tenant_certification_type · 租户级认证类型字典（新增 / 改名 / 停用内置类型）
-- ===========================================================================
CREATE TABLE tenant_certification_type (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  name varchar(64) NOT NULL CHECK(btrim(name)<>''),
  scope varchar(16) NOT NULL CHECK(scope IN ('GLOBAL','MARKETPLACE')),
  description text NOT NULL DEFAULT '',
  sort_order integer NOT NULL DEFAULT 100,
  -- 停用后：内置同码类型**也不再生效**（租户行优先于内置行）。
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个租户内一个 code 只有一行 ⇒ 改名 / 停用是 UPDATE，不是插第二条。
  UNIQUE(tenant_id, code)
);
COMMENT ON TABLE tenant_certification_type IS
  '16.2.2.2 · 租户级认证类型字典。生效词表 = 内置 certification_type ∪ 本表，**租户行优先**'
  '（租户行存在即以租户行为准：可改名、可停用）。';

CREATE INDEX tenant_certification_type_list
  ON tenant_certification_type(tenant_id, enabled, sort_order, code);

-- ===========================================================================
-- 2b. product_certification_type_view · 生效词表视图（内置 ∪ 租户，租户行优先）
-- ===========================================================================
-- 🔴 `WITH (security_invoker = true)`：默认的视图是**以视图拥有者的权限**执行的，
--   那会让查询者绕过 tenant_certification_type 的 RLS —— 换个租户就能看见别人的字典。
--   security_invoker=true 让 RLS 按**查询者**判定，这正是本视图要的语义。
-- 显式再带一次 tenant_id 过滤：超级用户会话绕过 RLS 时也不该看到别人的字典行。
CREATE VIEW product_certification_type_view WITH (security_invoker = true) AS
select coalesce(t.code, g.code) as code,
       coalesce(t.name, g.name) as name,
       coalesce(t.scope, g.scope) as scope,
       coalesce(t.description, g.description) as description,
       coalesce(t.enabled, g.enabled) as enabled,
       coalesce(t.sort_order, g.sort_order) as sort_order,
       (t.id is not null) as tenant_defined
  from certification_type g
  full outer join tenant_certification_type t
    on t.code = g.code
   and t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid;
COMMENT ON VIEW product_certification_type_view IS
  '16.2.2.2 · 生效认证类型词表：内置 certification_type ∪ 租户 tenant_certification_type，'
  '租户行优先（可改名、可停用）。security_invoker=true 让 RLS 按查询者判定。';

-- ===========================================================================
-- 3. product_certification · 认证档案主表
-- ===========================================================================
CREATE TABLE product_certification (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 编码：租户内唯一、建后不可改（服务层强制）。它是档案上的稳定引用。
  code varchar(64) NOT NULL CHECK(btrim(code)<>''),
  -- 认证类型（受控词表，见第二节）。DB 触发器兜底。
  certification_type_code varchar(32) NOT NULL CHECK(btrim(certification_type_code)<>''),
  -- 证书名称：文件上的正式名称（「CE 证书（低电压指令）」）。
  title varchar(200) NOT NULL CHECK(btrim(title)<>''),
  -- 认证编号：核验与对账时用。空串表示该认证没有编号（如自我声明）。
  cert_no varchar(64) NOT NULL DEFAULT '',
  -- 发证机构。
  issuer varchar(120) NOT NULL DEFAULT '',
  -- 签发地（国家/地区码，两字母或空）。
  issuing_country char(2) NOT NULL DEFAULT '',
  -- 状态：ACTIVE 有效 / REVOKED 已撤销（被发证机构撤销或作废）。
  --   🔴 与「已过期」是**两件事**：过期是时间到了，撤销是这张证不再被承认；
  --   覆盖统计里两者都不算「有有效认证」，但原因不同，界面上必须分得清。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
  -- 有效期窗口。**到期状态不落库**，由读出口按数据库时钟 now() 现算（第三节）。
  valid_from timestamptz NOT NULL,
  valid_until timestamptz NOT NULL,
  -- 撤销留痕（status=REVOKED 时必须齐）。
  revoked_at timestamptz,
  revoked_reason text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),
  -- 有效期窗口必须自洽：起止反了写进来会得到「永远不过期」或「一出生就过期」的档案。
  CHECK(valid_until > valid_from),
  -- 撤销必须留痕，否则「这张证为什么不能用」永远答不出来。
  CHECK(status <> 'REVOKED' OR (revoked_at IS NOT NULL AND btrim(revoked_reason)<>''))
);
COMMENT ON TABLE product_certification IS
  '16.2.2.2 · 产品认证档案主表。到期状态不落库（读出口按 now() 现算）；撤销与过期是两件事。';

CREATE INDEX product_certification_list
  ON product_certification(tenant_id, certification_type_code, status, valid_until, id);
CREATE INDEX product_certification_expiry
  ON product_certification(tenant_id, status, valid_until);
CREATE INDEX product_certification_no
  ON product_certification(tenant_id, cert_no);

-- ===========================================================================
-- 4. product_certification_sku · 适用 SKU
-- ===========================================================================
CREATE TABLE product_certification_sku (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  certification_id uuid NOT NULL,
  -- **catalog 的 SKU 逻辑 id**（跨服务引用，不建 FK）。
  sku_id uuid NOT NULL,
  -- 登记那一刻的 SKU 编码与名称快照（catalog 改名后历史档案仍能显示当时的 SKU）。
  sku_code_snapshot varchar(64) NOT NULL DEFAULT '',
  sku_name_snapshot varchar(200) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, certification_id, sku_id)
);
COMMENT ON TABLE product_certification_sku IS
  '16.2.2.2 · 认证适用的 SKU（catalog 逻辑 id + 名称快照，跨服务不建 FK）。';

CREATE INDEX product_certification_sku_cert
  ON product_certification_sku(tenant_id, certification_id, id);
CREATE INDEX product_certification_sku_sku
  ON product_certification_sku(tenant_id, sku_id, certification_id);

-- ===========================================================================
-- 5. product_certification_category · 认证对应的类目要求
-- ===========================================================================
CREATE TABLE product_certification_category (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  certification_id uuid NOT NULL,
  -- **catalog 的类目逻辑 id**（内部类目树节点，跨服务引用，不建 FK）。
  category_id uuid NOT NULL,
  category_code_snapshot varchar(64) NOT NULL DEFAULT '',
  category_name_snapshot varchar(100) NOT NULL DEFAULT '',
  -- 这张认证满足的是该类目的哪一条要求（如「低压电器须持 CE 证书」）。
  requirement_note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, certification_id, category_id)
);
COMMENT ON TABLE product_certification_category IS
  '16.2.2.2 · 认证对应的类目要求（catalog 类目逻辑 id + 名称快照，跨服务不建 FK）。';

CREATE INDEX product_certification_category_cert
  ON product_certification_category(tenant_id, certification_id, id);
CREATE INDEX product_certification_category_category
  ON product_certification_category(tenant_id, category_id, certification_id);

-- ===========================================================================
-- 6. product_certification_requirement · 要求侧登记（覆盖统计的另一半）
-- ===========================================================================
-- 「哪些 SKU 缺认证」必须有**要求侧**才答得出来：缺的那一行本来就不存在。
CREATE TABLE product_certification_requirement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 需要认证的 SKU（catalog 逻辑 id）。
  sku_id uuid NOT NULL,
  sku_code_snapshot varchar(64) NOT NULL DEFAULT '',
  sku_name_snapshot varchar(200) NOT NULL DEFAULT '',
  -- 该 SKU 需要的认证类型（受控词表，DB 触发器兜底）。
  certification_type_code varchar(32) NOT NULL CHECK(btrim(certification_type_code)<>''),
  -- 该要求来自哪个类目（可空：有的要求是 SKU 级的，不挂在类目上）。
  category_id uuid,
  category_code_snapshot varchar(64) NOT NULL DEFAULT '',
  category_name_snapshot varchar(100) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个 SKU 的同一个认证类型只登记一条（重复登记会让人以为要求了两遍）。
  UNIQUE(tenant_id, sku_id, certification_type_code)
);
COMMENT ON TABLE product_certification_requirement IS
  '16.2.2.2 · SKU 的认证要求登记（要求侧）。覆盖统计 = 要求 vs 当前有效的适用认证。';

CREATE INDEX product_certification_requirement_sku
  ON product_certification_requirement(tenant_id, sku_id, certification_type_code);
CREATE INDEX product_certification_requirement_type
  ON product_certification_requirement(tenant_id, certification_type_code, sku_id);
CREATE INDEX product_certification_requirement_category
  ON product_certification_requirement(tenant_id, category_id, certification_type_code);

-- ===========================================================================
-- 7. product_certification_document · 扫描件（**只追加**）
-- ===========================================================================
CREATE TABLE product_certification_document (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  certification_id uuid NOT NULL,
  -- 文件类别：
  --   ORIGINAL    首次登记时上传的原件
  --   RENEWAL     续期上传的新件
  --   ATTACHMENT  补充材料（检测报告、说明书）
  document_kind varchar(16) NOT NULL
    CHECK(document_kind IN ('ORIGINAL','RENEWAL','ATTACHMENT')),
  -- 受管存储里的 key（不是 URL，也不是文件本体）。
  document_key varchar(500) NOT NULL CHECK(btrim(document_key)<>''),
  document_name varchar(200) NOT NULL DEFAULT '',
  mime_type varchar(120) NOT NULL DEFAULT '',
  -- 文件大小（字节）。数量刻度与全仓一致：numeric(19,6)，禁浮点。
  size_bytes numeric(19,6) NOT NULL DEFAULT 0 CHECK(size_bytes >= 0),
  uploaded_by uuid,
  uploaded_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, certification_id, document_key)
);
COMMENT ON TABLE product_certification_document IS
  '16.2.2.2 · 认证扫描件元数据。文件本体在受管存储里（不进业务库），本表**只追加**。';

CREATE INDEX product_certification_document_cert
  ON product_certification_document(tenant_id, certification_id, uploaded_at DESC, id);

-- ===========================================================================
-- 8. product_certification_policy · 租户级策略（即将过期窗口）
-- ===========================================================================
-- 「多长算即将过期」是租户级策略而不是代码常量：欧盟站与国内站的窗口不一样。
-- 该行**按需创建**：第一次用到时由服务层写入（首次登记认证时），所以表可以是空的。
CREATE TABLE product_certification_policy (
  tenant_id uuid PRIMARY KEY,
  warning_days integer NOT NULL DEFAULT 30 CHECK(warning_days BETWEEN 1 AND 365),
  note text NOT NULL DEFAULT '',
  updated_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE product_certification_policy IS
  '16.2.2.2 · 租户级认证策略：即将过期窗口天数（默认 30 天）。';

-- ===========================================================================
-- 9. 约束（只在本迁移自建的表之间建；跨库一律不建）
-- ===========================================================================
ALTER TABLE product_certification_sku
  ADD CONSTRAINT product_certification_sku_certification_fk
  FOREIGN KEY (tenant_id, certification_id) REFERENCES product_certification(tenant_id, id) ON DELETE CASCADE;
ALTER TABLE product_certification_category
  ADD CONSTRAINT product_certification_category_certification_fk
  FOREIGN KEY (tenant_id, certification_id) REFERENCES product_certification(tenant_id, id) ON DELETE CASCADE;
ALTER TABLE product_certification_document
  ADD CONSTRAINT product_certification_document_certification_fk
  FOREIGN KEY (tenant_id, certification_id) REFERENCES product_certification(tenant_id, id) ON DELETE CASCADE;

-- ===========================================================================
-- 10. 守卫触发器
-- ===========================================================================
-- 10.1 认证类型必须在**生效词表**里（内置 ∪ 租户，租户行优先）。
--      直连数据库写库同样被拒 —— 服务层只是第一道闸。
CREATE FUNCTION guard_product_certification_type_exists() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  -- 🔴 变量名必须与列名错开：PL/pgSQL 里同名变量与同名列在一条 SQL 里会报
  --    「column reference is ambiguous」，而这个报错只在真跑迁移时才看得见。
  v_tenant uuid := NEW.tenant_id;
  v_code text := NEW.certification_type_code;
BEGIN
  -- 租户字典里有这一行 ⇒ 以租户行为准（可改名、可停用）
  IF EXISTS (SELECT 1 FROM tenant_certification_type t
              WHERE t.tenant_id = v_tenant AND t.code = v_code) THEN
    IF EXISTS (SELECT 1 FROM tenant_certification_type t
                WHERE t.tenant_id = v_tenant AND t.code = v_code AND t.enabled) THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION '认证类型 % 在本租户已被停用，不能再挂到认证档案上', v_code
      USING ERRCODE = 'restrict_violation';
  END IF;
  -- 否则看内置词表
  IF EXISTS (SELECT 1 FROM certification_type g WHERE g.code = v_code AND g.enabled) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION '认证类型 % 不在受控词表内（内置词表与本租户字典都没有启用它）', v_code
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER product_certification_type_exists
  BEFORE INSERT OR UPDATE OF certification_type_code ON product_certification
  FOR EACH ROW EXECUTE FUNCTION guard_product_certification_type_exists();

CREATE TRIGGER product_certification_requirement_type_exists
  BEFORE INSERT OR UPDATE OF certification_type_code ON product_certification_requirement
  FOR EACH ROW EXECUTE FUNCTION guard_product_certification_type_exists();

-- 10.2 撤销必须留痕（时刻 + 理由）。
CREATE FUNCTION guard_product_certification_revoked() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = 'REVOKED' THEN
    NEW.revoked_at := COALESCE(NEW.revoked_at, now());
    IF btrim(NEW.revoked_reason) = '' THEN
      RAISE EXCEPTION '撤销认证必须写明理由（认证 %）', NEW.code
        USING ERRCODE = 'restrict_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER product_certification_revoked
  BEFORE UPDATE OF status ON product_certification
  FOR EACH ROW EXECUTE FUNCTION guard_product_certification_revoked();

-- 10.3 扫描件只追加：续期是「再上传一份」，不是「覆盖掉原来那份」。
--      原来交的是什么，是合规追溯要用的凭据。
CREATE FUNCTION guard_product_certification_document_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '认证扫描件不可改写（表 product_certification_document）：'
    '续期请上传新件，certification_id=%', OLD.certification_id
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER product_certification_document_no_update
  BEFORE UPDATE ON product_certification_document
  FOR EACH ROW EXECUTE FUNCTION guard_product_certification_document_append_only();
CREATE TRIGGER product_certification_document_no_delete
  BEFORE DELETE ON product_certification_document
  FOR EACH ROW EXECUTE FUNCTION guard_product_certification_document_append_only();

-- 10.4 删租户字典行前先看有没有人用它：
--      * 内置类型被删 ⇒ 只是回到内置默认，放行；
--      * 租户自建类型被删 ⇒ 若还有认证 / 要求引用它，直接拒 ——
--        删掉之后那些认证就变成「挂在不存在的类型上」，而界面上完全看不出异常。
CREATE FUNCTION guard_tenant_certification_type_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM certification_type g WHERE g.code = OLD.code) THEN
    RETURN OLD;
  END IF;
  IF EXISTS (SELECT 1 FROM product_certification c
              WHERE c.tenant_id = OLD.tenant_id AND c.certification_type_code = OLD.code) THEN
    RAISE EXCEPTION '认证类型 % 已被认证档案引用，不能删除（请改档案或改为停用该类型）', OLD.code
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF EXISTS (SELECT 1 FROM product_certification_requirement r
              WHERE r.tenant_id = OLD.tenant_id AND r.certification_type_code = OLD.code) THEN
    RAISE EXCEPTION '认证类型 % 已被认证要求引用，不能删除（请改要求或改为停用该类型）', OLD.code
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER tenant_certification_type_no_delete
  BEFORE DELETE ON tenant_certification_type
  FOR EACH ROW EXECUTE FUNCTION guard_tenant_certification_type_delete();

-- ===========================================================================
-- 11. 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V171 / V177 / V194 / V197 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
-- `certification_type` 是全局参考数据（与 platform / marketplace 同族），**不带** tenant_id，
-- 因此不参与 RLS —— 它没有租户维度。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'tenant_certification_type','product_certification','product_certification_sku',
    'product_certification_category','product_certification_requirement',
    'product_certification_document','product_certification_policy'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 12. 账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  tenant_certification_type, product_certification, product_certification_sku,
  product_certification_category, product_certification_requirement,
  product_certification_document, product_certification_policy TO channel_app;

-- 全局词表只读给 channel_app。
GRANT SELECT ON certification_type TO channel_app;
GRANT SELECT ON product_certification_type_view TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 channel V1..V197 同口径）：
-- 跨服务只经授权 API 读，不给对方库直连。
REVOKE ALL ON
  tenant_certification_type, product_certification, product_certification_sku,
  product_certification_category, product_certification_requirement,
  product_certification_document, product_certification_policy FROM starcon_app;
REVOKE ALL ON certification_type FROM starcon_app;
REVOKE ALL ON product_certification_type_view FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 认证与资质属于「平台操作」语义，
-- 与 4.1.1.1 listing 列表、4.2.1.1 刊登编辑器、16.2.2.1 合规预检同族，
-- 复用 identity 已注册的 channel.operation.*，identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 13. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：channel V1..V243（含同批 16.2.2.1 的 V197）都不碰
--     ⇒ 跨车道 DDL 回归面 = 0。
--   * 对 catalog 的 SKU / 类目**只读逻辑 ID + 名称快照，不加任何约束**：跨库 FK 本来就禁止。
--   * 不建 `listing_compliance_*` / `compliance_check_*` / `compliance_rule*` 任何对象。
--   * 新增函数全部带 product_certification_ 前缀，与既有函数名不冲突。
--   * 版本号 V247 由调度者预分配，**不自己找最大版本号**（并行车道会同时提交迁移）。
-- ---------------------------------------------------------------------------
