-- ===========================================================================
-- 16.2.1.1 规则库 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 112-117 行）：
--   ### 16.2.1.1 规则库
--   - 职责：管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
--           严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。
--   - 输入：规则定义与词库上传；版本更新；适用范围配置。
--   - 输出：规则集 + 生效版本。
--   - 关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
--           **规则版本化是关键**：政策变化时历史商品的判定结果要可解释，
--           不能因为规则库更新就说过去的商品当时违规。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，**零 ALTER 任何既有表**：
--   * `platform` / `marketplace`（V1，02 渠道与店铺）—— **全局码表**（不带 tenant_id），
--     「适用平台与站点」直接读它们，**不复制目录表、不造平行平台表**；
--   * `shop` / `shop_marketplace`（V1）—— 店铺目录的事实来源。规则与店铺的关系
--     **收敛在适用范围上**，**不写死店铺常量**。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.2.1.1，合规域）⇒ `compliance_rule_*`（本迁移）。
--   * 16.1.2.1 ⇒ `platform_violation_*` / `platform_penalty_*`（V308，既有）。
--   * 16.1.1.1 ⇒ `account_health_*`（V300，既有）；16.1.1.2 ⇒ `account_health_performance_*`（V304，既有）。
--   * 16.2.2.1 ⇒ `compliance_check_*`（V197，既有）；4.2.3.2 ⇒ `listing_compliance_*`（V198，既有）。
--   * 16.2.2.2 ⇒ `product_certification_*`（V247，既有）；16.4.2.1 ⇒ `brand_authz_*`（V248，既有）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴🔴 规则版本化 —— 蓝图加粗句落成**物理事实**
-- ---------------------------------------------------------------------------
-- 「规则改动 = 新增一个版本（带生效时间窗），**不是原地改词**」：
--
--   1. **版本内容不可改写**：`compliance_rule_version` 的 severity / title / description /
--      effective_from / version_no 一律不可 UPDATE（触发器 `..._immutable` 钉死）。
--      唯一的例外是 `effective_to`：**只允许向前关闭**（NULL → 某个时刻），
--      且关闭时刻**不得早于当前时刻** —— 回溯关闭等于改写历史。
--   2. **不许回填生效时刻**：新版本的 `effective_from` **不得早于 `now()`**
--      （触发器 `..._window` 钉死）。回填一个过去的生效时刻，会让「当时生效的那一版」
--      变成今天这一版 ⇒ 历史商品的判定结果**再也解释不清**。
--   3. **同一时刻只有一个版本生效**：新版本落库时，触发器把**上一版**的生效窗口
--      自动关闭到新版的生效时刻，并拒绝任何**重叠**的时间窗。
--      「当时生效的是哪一版」因此是**可查询的事实**，不是靠约定。
--   4. **词条与适用范围属于版本**：一个版本生效之后，它的词条与适用范围**锁死**
--      （触发器 `..._editable`）。改一个词 = 新增一个版本。
--      例外：**创建该版本的那一笔事务内**允许补齐（服务层一次事务里建版本 + 落词条）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 值域必须由 **DB 层 CHECK 钉死**，不能只靠应用层 enum
-- ---------------------------------------------------------------------------
--   * 规则类型（蓝图点名的七种）= 违禁词 / 极限词 / 侵权词 / 认证要求 / 禁限售 /
--     标签要求 / 材质要求 ⇒ `compliance_rule.rule_type` 的 CHECK。
--     🔴 类型是**规则的身份**（一条规则永远是同一类规则）；改类型 = 另一条规则。
--   * 严重度（蓝图点名的三种）= 阻断 / 警告 / 提示 ⇒ `compliance_rule_version.severity`
--     的 CHECK。🔴 严重度**落在版本上**，不落在规则上 —— 否则政策改了严重度，
--     旧版本绑定的判定结果会读到新严重度，历史就解释不清了。
--   * 规则生命周期 = ACTIVE / RETIRED；词条分类 = GENERAL / BRAND / CATEGORY / SPELLING。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 词库是多词条，且**词条属于版本**
-- ---------------------------------------------------------------------------
-- 违禁词 / 极限词 / 侵权词三类规则带词条集合（一条规则多个词条）。
-- 词条挂在**版本**上（`compliance_rule_term.rule_version_id`），**不能脱离版本存在**：
-- 触发器校验版本真实存在、且该版本的规则类型确实是「带词条」的那三类。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 适用平台与站点（**从本服务自己的目录读**）
-- ---------------------------------------------------------------------------
-- `compliance_rule_scope` 一行 = 一个适用范围：
--   * `platform_id` —— 适用平台（必填，读 `platform` 目录）；
--   * `marketplace_id` —— 适用站点（可空 = 该平台**全部**站点）；
--   * `shop_id` —— 适用店铺（可空 = 该平台/站点下的**全部**店铺）。
-- 🔴 店铺维度**显式**：规则与店铺的关系落在这张表上，并按「适用平台与站点」收敛。
-- 🔴 **零外键**（与 V300 / V304 / V308 同一纪律）：平台 / 站点 / 店铺的存在性与
--   自洽性一律由触发器校验（FK 会打破同服务既有测试的 TRUNCATE 纪律）。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 多租户隔离：tenant_id NOT NULL + ENABLE **与 FORCE** ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **数据范围（data_scope）落在适用范围表上**：
--   * `compliance_rule` / `compliance_rule_version` / `compliance_rule_term` 是
--     **租户级**的规则资产（规则身份 / 版本 / 词库是租户自己的政策）⇒ isolation 即可；
--   * `compliance_rule_scope` 是**店铺维度**的资产 ⇒ 带 RESTRICTIVE 的 data_scope 策略
--     （参照 16.1.2.1 V308 的做法）：显式指定店铺时按那家店收敛；
--     未指定店铺时按「适用平台与站点」收敛 —— 平台上**至少有一家**当前可见的店铺即可见。
--   ⇒ 「谁能看哪条规则适用于哪家店」这件事，**由 DB 层决定**，不由应用层拼。
--
-- ===========================================================================

-- ===========================================================================
-- 一、compliance_rule · 规则身份
-- ===========================================================================
-- 🔴 规则身份 = 「哪一条规则」（稳定的业务键 + 类型 + 生命周期）。
--   规则的**内容**（严重度 / 词条 / 适用范围 / 生效时间）全在版本上。
CREATE TABLE compliance_rule (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 规则编码：租户内稳定的业务键（人可读）。**版本化改动不改编码**。
  rule_code     varchar(64) NOT NULL,
  -- 🔴 规则类型（受控值域，DB 层钉死）。
  rule_type     varchar(32) NOT NULL
                CHECK(rule_type IN (
                  'PROHIBITED_WORD',         -- 违禁词
                  'SUPERLATIVE_WORD',        -- 极限词
                  'INFRINGEMENT_WORD',       -- 侵权词
                  'CERTIFICATION_REQUIRED',  -- 认证要求
                  'RESTRICTED_SALE',         -- 禁限售
                  'LABEL_REQUIREMENT',       -- 标签要求
                  'MATERIAL_REQUIREMENT')),  -- 材质要求
  -- 🔴 规则生命周期（**不是版本内容**）：整条规则停用（不再产生新的生效版本）。
  status        varchar(16) NOT NULL DEFAULT 'ACTIVE'
                CHECK(status IN ('ACTIVE','RETIRED')),
  created_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一租户内规则编码唯一（幂等锚点：重复提交同一个编码 ⇒ 命中唯一键，不产生第二条）。
  UNIQUE(tenant_id, rule_code),
  CHECK(btrim(rule_code) <> '')
);
COMMENT ON TABLE compliance_rule IS
  '16.2.1.1 · 规则身份。rule_code 是租户内稳定业务键，版本化改动**不改编码**；'
  'rule_type 是**受控值域**（七种，由 DB 层 CHECK 钉死）；status 是规则生命周期，不是版本内容。';
CREATE INDEX compliance_rule_tenant_type
  ON compliance_rule(tenant_id, rule_type, created_at DESC, id);

-- ===========================================================================
-- 二、compliance_rule_version · 规则的**版本**（内容 + 生效时间窗）
-- ===========================================================================
-- 🔴 判定结果要能绑定到「当时生效的那个版本」⇒ 版本的**内容不可改写**，
--   且**生效时间窗不得重叠**、**不许回填**。见文件头第二、三节。
CREATE TABLE compliance_rule_version (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  rule_id         uuid NOT NULL,
  -- 🔴 版本号（同一规则内从 1 连续递增；唯一键 + 触发器双重钉住）。
  version_no      int NOT NULL CHECK(version_no >= 1),
  -- 🔴 严重度（受控值域，DB 层钉死）：阻断 / 警告 / 提示。
  --   🔴 严重度**落在版本上**：政策改了严重度 = 新增一个版本，旧版本原样留着。
  severity        varchar(16) NOT NULL
                  CHECK(severity IN ('BLOCKING','WARNING','INFO')),
  title           varchar(200) NOT NULL,
  description     text NOT NULL DEFAULT '',
  -- 🔴 生效时间窗 [effective_from, effective_to)。
  --   effective_to 为 NULL = 「一直有效，直到下一个版本接替」。
  effective_from  timestamptz NOT NULL,
  effective_to    timestamptz,
  change_note     varchar(1000) NOT NULL DEFAULT '',
  created_by      uuid,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_id, version_no),
  CHECK(btrim(title) <> ''),
  -- 🔴 真区间：结束时刻必须晚于开始时刻。
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);
COMMENT ON TABLE compliance_rule_version IS
  '16.2.1.1 · 规则版本。**内容不可改写**（改规则 = 新增一个版本），'
  '生效时间窗**不得重叠**且**不许回填**；严重度落在版本上 ⇒ '
  '「当时生效的是哪一版、那一版有多严重」永远答得出来。';
CREATE INDEX compliance_rule_version_rule
  ON compliance_rule_version(tenant_id, rule_id, version_no DESC);
CREATE INDEX compliance_rule_version_window
  ON compliance_rule_version(tenant_id, effective_from DESC, effective_to);

-- ===========================================================================
-- 三、compliance_rule_term · 词条（**属于某个版本**，不能脱离版本存在）
-- ===========================================================================
CREATE TABLE compliance_rule_term (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  rule_version_id uuid NOT NULL,
  -- 🔴 词条本身（违禁词 / 极限词 / 侵权词）。原文照抄，不折叠大小写。
  term            varchar(200) NOT NULL,
  term_kind       varchar(16) NOT NULL DEFAULT 'GENERAL'
                  CHECK(term_kind IN ('GENERAL','BRAND','CATEGORY','SPELLING')),
  note            varchar(500) NOT NULL DEFAULT '',
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个版本里同一个词只登记一次（重复上传同一份词库 ⇒ 跳过，不产生第二条）。
  UNIQUE(tenant_id, rule_version_id, term),
  CHECK(btrim(term) <> '')
);
COMMENT ON TABLE compliance_rule_term IS
  '16.2.1.1 · 规则词条（一条规则多个词条）。词条**属于某个版本**：'
  '版本生效后词条锁死（改一个词 = 新增一个版本）。';
CREATE INDEX compliance_rule_term_version
  ON compliance_rule_term(tenant_id, rule_version_id, term);
CREATE INDEX compliance_rule_term_lookup
  ON compliance_rule_term(tenant_id, term);

-- ===========================================================================
-- 四、compliance_rule_scope · 适用范围（平台 + 站点 + 可选店铺）
-- ===========================================================================
CREATE TABLE compliance_rule_scope (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  -- 🔴 冗余的规则 id：让「这条规则适用到哪儿」不必绕版本 join，
  --   也让适用范围表本身就能独立承载数据范围策略。
  rule_id         uuid NOT NULL,
  rule_version_id uuid NOT NULL,
  -- 🔴 适用平台（读本服务自己的 `platform` 目录；全局码表，不带 tenant_id）。
  platform_id     uuid NOT NULL,
  -- 🔴 适用站点（NULL = 该平台**全部**站点）。
  marketplace_id  uuid,
  -- 🔴 适用店铺（NULL = 该平台/站点下的**全部**店铺）。
  shop_id         uuid,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一版本里同一「平台 + 站点 + 店铺」只登记一次。
  --   NULLS NOT DISTINCT：NULL 也算同一个值 —— 否则「平台级范围」能重复登记一堆。
  UNIQUE NULLS NOT DISTINCT (tenant_id, rule_version_id, platform_id, marketplace_id, shop_id)
);
COMMENT ON TABLE compliance_rule_scope IS
  '16.2.1.1 · 规则的适用范围（适用平台 + 站点，可选收窄到具体店铺）。'
  '适用范围**属于某个版本**；店铺维度在这里**显式**落地，并按「适用平台与站点」收敛。';
CREATE INDEX compliance_rule_scope_version
  ON compliance_rule_scope(tenant_id, rule_version_id);
CREATE INDEX compliance_rule_scope_rule
  ON compliance_rule_scope(tenant_id, rule_id);
CREATE INDEX compliance_rule_scope_platform
  ON compliance_rule_scope(tenant_id, platform_id, marketplace_id, shop_id);

-- ===========================================================================
-- 五、触发器
-- ===========================================================================

-- 🔴 (a) 版本窗口：**不许回填** + 版本号连续 + 接替上一版 + 窗口不得重叠。
CREATE FUNCTION guard_compliance_rule_version_window() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  expected_no int;
  prev_id     uuid;
  -- 🔴 变量名**不能叫 `overlaps`**：那是 PostgreSQL 的区间重叠运算符关键字，
  --   `IF overlaps > 0 THEN` 会被解析成「OVERLAPS 运算符后面跟一个 >」⇒ 语法错误。
  overlap_count int;
BEGIN
  -- 🔴 (1) **不许回填生效时刻**。
  --   回填一个过去的生效时刻 ⇒ 「当时生效的那一版」变成今天这一版 ⇒
  --   历史商品的判定结果再也解释不清（这正是蓝图加粗句要防的事）。
  IF NEW.effective_from < now() THEN
    RAISE EXCEPTION
      '规则版本的生效时刻不能早于当前时刻（不许回填：回填会改写「当时生效的那一版」，历史判定就再也解释不清）'
      USING ERRCODE='23514';
  END IF;

  -- 🔴 (2) 版本号必须连续（同一规则内从 1 递增，不能跳号）。
  SELECT coalesce(max(v.version_no), 0) + 1 INTO expected_no
    FROM compliance_rule_version v
   WHERE v.tenant_id = NEW.tenant_id AND v.rule_id = NEW.rule_id;
  IF NEW.version_no <> expected_no THEN
    RAISE EXCEPTION
      '规则 % 的新版本号必须是 %（版本号必须连续，不能跳号）', NEW.rule_id, expected_no
      USING ERRCODE='23514';
  END IF;

  -- 🔴 (3) 接替上一版：把**上一版**的生效窗口向前关闭到新版的生效时刻。
  --   「同一时刻只有一个版本生效」因此是物理事实，不靠应用层自觉。
  SELECT v.id INTO prev_id
    FROM compliance_rule_version v
   WHERE v.tenant_id = NEW.tenant_id AND v.rule_id = NEW.rule_id
     AND v.effective_from < NEW.effective_from
     AND v.effective_to IS NULL
   ORDER BY v.effective_from DESC
   LIMIT 1;
  IF FOUND THEN
    UPDATE compliance_rule_version
       SET effective_to = NEW.effective_from
     WHERE tenant_id = NEW.tenant_id AND id = prev_id;
  END IF;

  -- 🔴 (4) 生效时间窗**不得重叠**（把新版本插到旧版本之前会被这里挡住）。
  SELECT count(*) INTO overlap_count
    FROM compliance_rule_version v
   WHERE v.tenant_id = NEW.tenant_id AND v.rule_id = NEW.rule_id
     AND v.id <> NEW.id
     AND v.effective_from < coalesce(NEW.effective_to, 'infinity'::timestamptz)
     AND coalesce(v.effective_to, 'infinity'::timestamptz) > NEW.effective_from;
  IF overlap_count > 0 THEN
    RAISE EXCEPTION
      '规则 % 的版本生效时间窗与既有版本重叠（同一时刻只能有一个版本生效）', NEW.rule_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_rule_version_window BEFORE INSERT ON compliance_rule_version
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_rule_version_window();

-- 🔴 (b) 版本内容**不可改写**：改规则 = 新增一个版本。
--   唯一的例外是 `effective_to`：**只允许向前关闭**（NULL → 某个时刻），
--   且关闭时刻不得早于当前时刻（回溯关闭 = 改写历史）。
CREATE FUNCTION guard_compliance_rule_version_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_id IS DISTINCT FROM OLD.rule_id
     OR NEW.version_no IS DISTINCT FROM OLD.version_no
     OR NEW.severity IS DISTINCT FROM OLD.severity
     OR NEW.title IS DISTINCT FROM OLD.title
     OR NEW.description IS DISTINCT FROM OLD.description
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
     OR NEW.change_note IS DISTINCT FROM OLD.change_note
     OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
    RAISE EXCEPTION
      '规则版本 % 的内容不可改写（改规则 = 新增一个版本）', OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
    -- 已经关闭过的窗口不可再动。
    IF OLD.effective_to IS NOT NULL THEN
      RAISE EXCEPTION
        '规则版本 % 的生效窗口已经关闭，不可再改', OLD.id
        USING ERRCODE='23514';
    END IF;
    -- 只允许向前关闭（now() 是事务时刻，与服务层 select now() 同源，不会误判）。
    IF NEW.effective_to IS NULL OR NEW.effective_to < now() THEN
      RAISE EXCEPTION
        '规则版本 % 只能向前关闭生效窗口（不许回溯改写历史）', OLD.id
        USING ERRCODE='23514';
    END IF;
    -- 关闭时刻必须晚于本版本的生效时刻。
    IF NEW.effective_to <= OLD.effective_from THEN
      RAISE EXCEPTION
        '规则版本 % 的生效窗口结束时刻必须晚于开始时刻', OLD.id
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_rule_version_immutable BEFORE UPDATE ON compliance_rule_version
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_rule_version_immutable();

-- 🔴 (c) 规则身份存在性校验（**零外键**）。
CREATE FUNCTION guard_compliance_rule_version_rule_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM compliance_rule r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.rule_id) THEN
    RAISE EXCEPTION
      '规则版本引用了一条在本租户不存在的规则：%', NEW.rule_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_rule_version_rule_ref BEFORE INSERT ON compliance_rule_version
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_rule_version_rule_ref();

-- 🔴 (d) 版本内容（词条 / 适用范围）**可编辑性**：
--   版本生效之后锁死；**创建该版本的那一笔事务内**允许补齐。
--   （created_at = now() 命中「本事务内创建」—— now() 是事务时刻，同一事务内相等。）
CREATE FUNCTION compliance_rule_version_editable(p_tenant uuid, p_version uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT EXISTS (
    SELECT 1 FROM compliance_rule_version v
     WHERE v.tenant_id = p_tenant AND v.id = p_version
       AND (v.effective_from > now() OR v.created_at = now())
  )
$$;

-- 🔴 (e) 词条校验：版本真实存在 + 该版本确实是「带词条」的三类规则 + 版本尚未生效。
CREATE FUNCTION guard_compliance_rule_term_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_rule_type varchar(32);
BEGIN
  SELECT r.rule_type INTO v_rule_type
    FROM compliance_rule_version v
    JOIN compliance_rule r ON r.tenant_id = v.tenant_id AND r.id = v.rule_id
   WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.rule_version_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION
      '词条引用了一个在本租户不存在的规则版本：%', NEW.rule_version_id
      USING ERRCODE='23514';
  END IF;
  IF v_rule_type NOT IN ('PROHIBITED_WORD','SUPERLATIVE_WORD','INFRINGEMENT_WORD') THEN
    RAISE EXCEPTION
      '规则类型 % 不带词条（只有违禁词 / 极限词 / 侵权词三类规则有词条集合）', v_rule_type
      USING ERRCODE='23514';
  END IF;
  IF NOT compliance_rule_version_editable(NEW.tenant_id, NEW.rule_version_id) THEN
    RAISE EXCEPTION
      '规则版本 % 已经生效，词条不可再改（改规则 = 新增一个版本）', NEW.rule_version_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_rule_term_ref BEFORE INSERT OR UPDATE ON compliance_rule_term
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_rule_term_ref();

-- 🔴 (f) 词条**不可删除**：它是「当时那一版按什么词判」的证据。
CREATE FUNCTION guard_compliance_rule_term_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '规则词条 % 不可删除（改规则 = 新增一个版本）', OLD.id USING ERRCODE='23514';
END $$;
CREATE TRIGGER compliance_rule_term_no_delete BEFORE DELETE ON compliance_rule_term
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_rule_term_no_delete();

-- 🔴 (g) 适用范围校验（**零外键**）：
--   版本真实存在 + 规则 id 与版本自洽 + 站点属于该平台 + 店铺属于该平台且在本租户目录里。
CREATE FUNCTION guard_compliance_rule_scope_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_rule_id uuid;
BEGIN
  SELECT v.rule_id INTO v_rule_id
    FROM compliance_rule_version v
   WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.rule_version_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION
      '适用范围引用了一个在本租户不存在的规则版本：%', NEW.rule_version_id
      USING ERRCODE='23514';
  END IF;
  IF v_rule_id <> NEW.rule_id THEN
    RAISE EXCEPTION
      '适用范围 % 的规则 id 与它所属的规则版本不一致', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.marketplace_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM marketplace m
        WHERE m.id = NEW.marketplace_id AND m.platform_id = NEW.platform_id) THEN
    RAISE EXCEPTION
      '适用站点 % 不属于所选平台（站点与平台必须自洽）', NEW.marketplace_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.shop_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM shop s
        WHERE s.tenant_id = NEW.tenant_id
          AND s.id = NEW.shop_id
          AND s.platform_id = NEW.platform_id) THEN
    RAISE EXCEPTION
      '适用店铺 % 不在本租户店铺目录里（或不属于所选平台）', NEW.shop_id
      USING ERRCODE='23514';
  END IF;
  IF NOT compliance_rule_version_editable(NEW.tenant_id, NEW.rule_version_id) THEN
    RAISE EXCEPTION
      '规则版本 % 已经生效，适用范围不可再改（改规则 = 新增一个版本）', NEW.rule_version_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_rule_scope_ref BEFORE INSERT OR UPDATE ON compliance_rule_scope
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_rule_scope_ref();

-- 🔴 (h) 适用范围**不可删除**：它是「当时那一版适用于哪儿」的证据。
CREATE FUNCTION guard_compliance_rule_scope_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '适用范围 % 不可删除（改规则 = 新增一个版本）', OLD.id USING ERRCODE='23514';
END $$;
CREATE TRIGGER compliance_rule_scope_no_delete BEFORE DELETE ON compliance_rule_scope
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_rule_scope_no_delete();

-- ===========================================================================
-- 六、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'compliance_rule','compliance_rule_version','compliance_rule_term','compliance_rule_scope' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 🔴 数据范围（RESTRICTIVE，与 V300 / V304 / V308 同构）：
--   规则身份 / 版本 / 词库是**租户级**的规则资产（租户自己的政策）⇒ isolation 即可；
--   **适用范围是店铺维度的资产** ⇒ 可见性随店铺负责人 / 部门收敛：
--     * 显式点名店铺时 ⇒ 那家店必须在当前数据范围内（`channel_scope` 照 16.1.2.1 的做法）；
--     * 平台 / 站点级范围（没点名店铺）时 ⇒ **只有租户级（ALL）角色**持有它
--       （`channel_scope(..., NULL, NULL)`，与 V1 `credential_scope` 同一手法）：
--       「适用于整个 Amazon 平台」是一条**宽口径**设置，不该由一个只管自己店的人来定。
--   ⇒ 「谁能看哪条规则适用于哪家店」这件事，**由 DB 层决定**，不由应用层拼。
CREATE POLICY data_scope ON compliance_rule_scope AS RESTRICTIVE
  USING (
    CASE
      WHEN compliance_rule_scope.shop_id IS NOT NULL THEN EXISTS (
        SELECT 1 FROM shop s
         WHERE s.tenant_id = compliance_rule_scope.tenant_id
           AND s.id = compliance_rule_scope.shop_id
           AND channel_scope(channel_operation(), s.owner_id, s.department_id))
      ELSE channel_scope(channel_operation(), NULL, NULL)
    END)
  WITH CHECK (
    CASE
      WHEN compliance_rule_scope.shop_id IS NOT NULL THEN EXISTS (
        SELECT 1 FROM shop s
         WHERE s.tenant_id = compliance_rule_scope.tenant_id
           AND s.id = compliance_rule_scope.shop_id
           AND channel_scope(channel_operation(), s.owner_id, s.department_id))
      ELSE channel_scope(channel_operation(), NULL, NULL)
    END);

-- ===========================================================================
-- 七、账号与授权
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
  compliance_rule, compliance_rule_version, compliance_rule_term, compliance_rule_scope TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V308 同口径）。
REVOKE ALL ON
  compliance_rule, compliance_rule_version, compliance_rule_term, compliance_rule_scope FROM starcon_app;

-- 🔴 **序列授权**：本迁移的四张表**全部用应用侧生成的 uuid 作主键**，没有任何
--   serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 / V300 / V304 / V308 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 规则库属于「平台操作 / 合规台账」语义，
-- 与 16.1.2.1 同族，复用 identity 已注册的 channel.operation.*（读 / 录 / 改），
-- identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 八、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **上游 平台政策同步**：平台政策适配器**未交付** ⇒ 本模块的输入只有
--     「人工维护」这一条真实路径（建规则 / 上传词库 / 发新版本 / 配适用范围）。
--     本模块**不编造**一份「平台政策自动同步」的假入口。
--   * **下游 16.2.1.2 拦截执行**：**未交付** ⇒ 本模块**不建拦截表**、
--     **不造指向不存在入口的放行形状**；能力声明里如实标 available=false。
--   * **下游 4.2.3.2 合规预检**：**已交付**（同服务，`listing_compliance_*`），
--     但它用的是**自己的租户级词表**，**不是**本模块的受版本治理规则库 ——
--     本模块**不改动它的任何一张表**，边界写在两侧的迁移文件头。
-- ---------------------------------------------------------------------------
