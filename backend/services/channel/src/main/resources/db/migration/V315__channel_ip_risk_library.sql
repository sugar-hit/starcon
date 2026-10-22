-- ===========================================================================
-- 16.4.1.1 侵权库 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 175-180 行）：
--   ### 16.4.1.1 侵权库
--   - 职责：维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
--   - 输入：侵权条目登记；批量导入。
--   - 输出：侵权库记录。
--   - 关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。**侵权检测是刊登前拦截而非事后申诉**，
--           成本差一个数量级。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，**零 ALTER 任何既有表**：
--   * `platform`（V1，02 渠道与店铺）—— **全局码表**（不带 tenant_id），
--     「适用平台」直接读它，**不复制目录表、不造平行平台表**；
--   * **3.3.2 品牌（catalog 服务）** —— **跨服务**：只经**授权 HTTP API** 读
--     （`GET /catalog/brands`，见 `IpRiskLibrarySourcePort`），
--     落库时只存**逻辑 id**（`brand_id`），
--     **零跨库 FOREIGN KEY、零跨库视图、零跨服务 SQL**。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.4.1.1，知识产权域）⇒ `ip_risk_*`（本迁移）。
--   * 16.4.2.1 ⇒ `brand_authz_*`（V248，既有）；16.4.2.2 ⇒ `brand_cred_*`（V255，既有）。
--   * 16.2.1.1 ⇒ `compliance_rule_*`（V311，既有）；16.2.2.2 ⇒ `product_certification_*`（V247，既有）。
--   * 16.3.2.2 ⇒ `epr_*`（既有）；4.2.3.2 ⇒ `listing_compliance_*`（V198，既有）。
--   ⇒ 六个命名空间彼此隔离；本迁移**零 ALTER 任何既有表**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴🔴 权利类型 / 风险等级 / 生命周期 —— **DB 层 CHECK 钉死**
-- ---------------------------------------------------------------------------
--   * 权利类型（蓝图点名的四种）= 品牌商标 / 专利 / 版权 / 肖像权
--     ⇒ `ip_risk_entry.right_type` 的 CHECK。
--     🔴 权利类型是**条目的身份**：一条侵权条目永远是同一类权利，
--       改类型 = 另一条条目（不是「改一下类型字段」）。
--   * 风险等级（受控等级集）= 高 / 中 / 低 ⇒ `ip_risk_entry.risk_level` 的 CHECK。
--     🔴 **不用 0..100 的裸数字**：裸数字的分级没有口径，
--       下游 16.4.1.2 检测拿到的「72 分」没法解释成「高风险」还是「中风险」。
--   * 生命周期 = ACTIVE / DISABLED。**停用 = 不再参与未来的检测，但历史记录保留**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴🔴 适用平台是**多对多**，不是单列
-- ---------------------------------------------------------------------------
-- 一条侵权条目常常适用多个平台（Amazon 上不能卖、eBay 上也不能卖）。
-- 用单列 `platform` 存只能存一个 ⇒ 平台维度会静默丢信息，
-- 「这个平台上有哪些高风险条目」这类反查也就答不上来。
-- ⇒ 独立表 `ip_risk_entry_platform`，一行 = 一个适用平台。
--
-- 🔴 平台编码**读本服务自己的 `platform` 目录**（全局码表：AMAZON / SHOPIFY /
--   EBAY / TIKTOK_SHOP / TEMU / WALMART …），
--   **不造平行平台表、不写死平台常量**；存在性由触发器校验（**零外键**，
--   与 V300 / V304 / V308 / V311 同一纪律：FK 会打破同服务既有测试的 TRUNCATE 纪律）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴🔴 匹配特征 —— 本模块**最关键**的一组字段
-- ---------------------------------------------------------------------------
-- 下游 16.4.1.2 检测要拿本表去比对商品**标题 / 描述 / 图片 / 品牌**字段，
-- 所以条目必须带**可被匹配的字段**：
--   * `brand_name` —— 品牌名（**可空**：有些专利 / 版权没有品牌名）；
--   * `ip_risk_match_term` —— 关键词 / 短语集合（**多值**：一条条目常含多个关键词）；
--   * `match_pattern` —— 可选的标识符 / 外观特征描述（如商标注册号、版权作品名）。
--
-- 🔴 **本模块只登记这些特征，不执行任何比对**：比对逻辑属于 16.4.1.2，
--   本模块**不产出任何检测结论**。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 批量导入：**真入库 + 行级结果 + 幂等 + 部分失败不回滚**
-- ---------------------------------------------------------------------------
--   * `ip_risk_import_batch` —— 一次导入的汇总（成功 / 跳过 / 失败行数）；
--   * `ip_risk_import_row` —— **逐行**结果（哪一行、哪个字段、为什么）。
--     「整个文件成功 / 失败」二选一**不够用**：一份 500 行的词库，
--     错 3 行不该让另外 497 行白导。
--   * **幂等**：`ip_risk_entry` 上的 `UNIQUE(tenant_id, entry_code)` 是锚点，
--     重复导入同一批**不产生重复条目**（**不靠「先查后插」的竞态写法**）。
--   * **部分失败不回滚已成功的行**：服务层**逐行 savepoint** 提交。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 多租户隔离：tenant_id NOT NULL + ENABLE **与 FORCE** ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 本模块的表全部是**租户级**的侵权台账资产（条目 / 匹配特征 / 适用平台 /
--   导入批次 / 导入行）⇒ `isolation` 策略即可，**不引入 data_scope**：
--   「哪些条目归哪个部门管」不是本模块的口径（那属于 4 刊登 / 16.2.1.2 拦截那一层）。
-- ===========================================================================

-- ===========================================================================
-- 一、ip_risk_entry · 侵权条目（**条目身份 + 匹配特征 + 风险登记**）
-- ===========================================================================
CREATE TABLE ip_risk_entry (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 条目编码：租户内稳定的业务键（人可读）。
  --   **批量导入的幂等锚点**：同一批重复导入不产生重复条目。
  entry_code    varchar(64) NOT NULL,
  -- 🔴 权利类型（受控值域，DB 层钉死）：品牌商标 / 专利 / 版权 / 肖像权。
  right_type    varchar(16) NOT NULL
                CHECK(right_type IN (
                  'TRADEMARK',     -- 品牌商标
                  'PATENT',        -- 专利
                  'COPYRIGHT',     -- 版权
                  'IMAGE_RIGHT')), -- 肖像权
  -- 🔴 风险等级（受控等级集，DB 层钉死）：高 / 中 / 低。
  risk_level    varchar(8) NOT NULL
                CHECK(risk_level IN ('HIGH','MEDIUM','LOW')),
  -- 🔴 条目标题（权利人 / 权利名称）：人可读。
  title         varchar(200) NOT NULL,
  -- 权利人（可选）。
  rights_holder varchar(200) NOT NULL DEFAULT '',
  -- 🔴 品牌名（**可被匹配的字段**；可空 —— 有些专利 / 版权没有品牌名）。
  brand_name    varchar(200),
  -- 🔴 关联到 3.3.2 品牌的**逻辑 id**（catalog 服务，跨服务 ⇒ **零 FK**）。
  --   本模块**不建** catalog 品牌的影子表，也不跨库校验。
  brand_id      uuid,
  -- 🔴 标识符 / 外观特征描述（可选）：商标注册号、版权作品名、肖像特征……
  match_pattern varchar(500),
  -- 备注（登记时的补充说明）。
  note          text NOT NULL DEFAULT '',
  -- 🔴 生命周期：启用 / 停用。
  status        varchar(16) NOT NULL DEFAULT 'ACTIVE'
                CHECK(status IN ('ACTIVE','DISABLED')),
  -- 🔴 停用理由（停用时必填，DB 层 CHECK 钉死）。
  disable_reason varchar(500) NOT NULL DEFAULT '',
  -- 最近一次停用的时刻（**重新启用时不抹掉** —— 那是历史）。
  disabled_at   timestamptz,
  -- 来源：人工登记 / 批量导入。
  source        varchar(16) NOT NULL DEFAULT 'MANUAL'
                CHECK(source IN ('MANUAL','IMPORT')),
  created_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一租户内条目编码唯一 ⇒ 批量导入的**幂等锚点**。
  UNIQUE(tenant_id, entry_code),
  CHECK(btrim(entry_code) <> ''),
  CHECK(btrim(title) <> ''),
  -- 🔴 **停用必须写理由**：没有理由的停用等于「随手关掉」，事后没人说得清为什么。
  CHECK(status <> 'DISABLED' OR btrim(disable_reason) <> ''),
  -- 🔴 **停用必须留下时刻**：历史要能查。
  CHECK(status <> 'DISABLED' OR disabled_at IS NOT NULL)
);
COMMENT ON TABLE ip_risk_entry IS
  '16.4.1.1 · 侵权条目。right_type / risk_level / status 都是**受控值域**（DB 层 CHECK 钉死）；'
  'entry_code 是租户内稳定业务键，也是**批量导入的幂等锚点**；'
  'brand_id 是指向 3.3.2 品牌的**逻辑 id**（跨服务 ⇒ 零 FK）；'
  '停用必须写理由且**不抹掉历史**。';
CREATE INDEX ip_risk_entry_tenant_risk
  ON ip_risk_entry(tenant_id, risk_level, status, created_at DESC, id);
CREATE INDEX ip_risk_entry_tenant_type
  ON ip_risk_entry(tenant_id, right_type, created_at DESC, id);
CREATE INDEX ip_risk_entry_tenant_brand
  ON ip_risk_entry(tenant_id, brand_id);
CREATE INDEX ip_risk_entry_tenant_brand_name
  ON ip_risk_entry(tenant_id, brand_name);

-- ===========================================================================
-- 二、ip_risk_entry_platform · 适用平台（**多对多**，一行一个平台）
-- ===========================================================================
CREATE TABLE ip_risk_entry_platform (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  entry_id      uuid NOT NULL,
  -- 🔴 适用平台编码，读本服务自己的 `platform` 目录（AMAZON / SHOPIFY / EBAY / …）。
  platform_code varchar(32) NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一条目同一平台只登记一次（重复导入不产生第二条）。
  UNIQUE(tenant_id, entry_id, platform_code),
  CHECK(btrim(platform_code) <> '')
);
COMMENT ON TABLE ip_risk_entry_platform IS
  '16.4.1.1 · 侵权条目的**适用平台**（多对多）。平台编码来自本服务的 `platform` 目录，'
  '**零外键**（存在性由触发器校验）⇒ 「这个平台上有哪些高风险条目」可直接反查。';
CREATE INDEX ip_risk_entry_platform_code
  ON ip_risk_entry_platform(tenant_id, platform_code, entry_id);
CREATE INDEX ip_risk_entry_platform_entry
  ON ip_risk_entry_platform(tenant_id, entry_id);

-- ===========================================================================
-- 三、ip_risk_match_term · 匹配特征（**关键词 / 短语集合**，多值）
-- ===========================================================================
CREATE TABLE ip_risk_match_term (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  entry_id      uuid NOT NULL,
  -- 🔴 关键词 / 短语原文（不折叠大小写、不做归一 —— 检测那一侧自己决定怎么匹配）。
  term          varchar(200) NOT NULL,
  -- 特征分类：关键词 / 短语。
  term_kind     varchar(16) NOT NULL DEFAULT 'KEYWORD'
                CHECK(term_kind IN ('KEYWORD','PHRASE')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一条目里同一个词只登记一次（重复导入跳过，不产生第二条）。
  UNIQUE(tenant_id, entry_id, term),
  CHECK(btrim(term) <> '')
);
COMMENT ON TABLE ip_risk_match_term IS
  '16.4.1.1 · 侵权条目的**匹配特征**（关键词 / 短语，多值）。'
  '16.4.1.2 检测拿它去比对商品标题 / 描述 / 图片 / 品牌字段；'
  '**本模块只登记，不执行任何比对**。';
CREATE INDEX ip_risk_match_term_entry
  ON ip_risk_match_term(tenant_id, entry_id);
CREATE INDEX ip_risk_match_term_lookup
  ON ip_risk_match_term(tenant_id, term);

-- ===========================================================================
-- 四、ip_risk_import_batch · 批量导入批次（汇总）
-- ===========================================================================
CREATE TABLE ip_risk_import_batch (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 导入来源名（文件名 / 来源系统，人可读）。
  source_name   varchar(200) NOT NULL DEFAULT '',
  total_rows    int NOT NULL CHECK(total_rows >= 0),
  inserted_rows int NOT NULL CHECK(inserted_rows >= 0),
  skipped_rows  int NOT NULL CHECK(skipped_rows >= 0),
  failed_rows   int NOT NULL CHECK(failed_rows >= 0),
  -- COMPLETED = 全部成功（含幂等跳过）；PARTIAL = 有行失败。
  status        varchar(16) NOT NULL CHECK(status IN ('COMPLETED','PARTIAL')),
  created_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 行数必须自洽：成功 + 跳过 + 失败 = 总行数。
  CHECK(total_rows = inserted_rows + skipped_rows + failed_rows)
);
COMMENT ON TABLE ip_risk_import_batch IS
  '16.4.1.1 · 批量导入批次汇总。**逐行结果在 ip_risk_import_row** —— '
  '「整个文件成功 / 失败」二选一不够用。';
CREATE INDEX ip_risk_import_batch_tenant
  ON ip_risk_import_batch(tenant_id, created_at DESC, id);

-- ===========================================================================
-- 五、ip_risk_import_row · 批量导入**逐行**结果（哪一行、哪个字段、为什么）
-- ===========================================================================
CREATE TABLE ip_risk_import_row (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  batch_id      uuid NOT NULL,
  row_no        int NOT NULL CHECK(row_no >= 1),
  entry_code    varchar(64) NOT NULL DEFAULT '',
  -- 落库后的条目 id（INSERTED / SKIPPED 时有值）。
  entry_id      uuid,
  outcome       varchar(16) NOT NULL CHECK(outcome IN ('INSERTED','SKIPPED','FAILED')),
  -- 🔴 失败时：**哪个字段** + **为什么**。
  error_field   varchar(64),
  error_message varchar(500),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, batch_id, row_no)
);
COMMENT ON TABLE ip_risk_import_row IS
  '16.4.1.1 · 批量导入的**逐行结果**（append-only）。'
  '失败行给出字段与原因；成功的行**不回滚**。';
CREATE INDEX ip_risk_import_row_batch
  ON ip_risk_import_row(tenant_id, batch_id, row_no);

-- ===========================================================================
-- 六、触发器
-- ===========================================================================

-- 🔴 (a) 适用平台：**平台必须在本服务的 `platform` 目录里**（零外键），
--   且条目必须存在、且条目处于启用态（停用条目的匹配特征**冻结**）。
CREATE FUNCTION guard_ip_risk_entry_platform_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  entry_status varchar(16);
BEGIN
  IF NOT EXISTS (SELECT 1 FROM platform p WHERE p.code = NEW.platform_code) THEN
    RAISE EXCEPTION
      '适用平台 % 不在平台目录里（请先在 02 渠道与店铺登记该平台）', NEW.platform_code
      USING ERRCODE='23514';
  END IF;
  SELECT e.status INTO entry_status
    FROM ip_risk_entry e
   WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entry_id;
  IF entry_status IS NULL THEN
    RAISE EXCEPTION
      '适用平台引用了一条在本租户不存在的侵权条目：%', NEW.entry_id
      USING ERRCODE='23514';
  END IF;
  IF entry_status <> 'ACTIVE' THEN
    RAISE EXCEPTION
      '侵权条目 % 已停用，适用平台已冻结（重新启用后才能改）', NEW.entry_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_risk_entry_platform_ref BEFORE INSERT OR UPDATE ON ip_risk_entry_platform
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_entry_platform_ref();

-- 🔴 (b) 适用平台随条目一起冻结：条目停用后**不可再删改**适用平台。
CREATE FUNCTION guard_ip_risk_entry_platform_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  entry_status varchar(16);
BEGIN
  IF TG_OP = 'DELETE' THEN
    SELECT e.status INTO entry_status
      FROM ip_risk_entry e
     WHERE e.tenant_id = OLD.tenant_id AND e.id = OLD.entry_id;
  ELSE
    SELECT e.status INTO entry_status
      FROM ip_risk_entry e
     WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entry_id;
  END IF;
  IF entry_status IS NOT NULL AND entry_status <> 'ACTIVE' THEN
    RAISE EXCEPTION
      '侵权条目 % 已停用，适用平台已冻结（重新启用后才能改）', OLD.entry_id
      USING ERRCODE='23514';
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$;
CREATE TRIGGER ip_risk_entry_platform_frozen BEFORE DELETE ON ip_risk_entry_platform
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_entry_platform_frozen();

-- 🔴 (c) 匹配特征：条目必须存在、且处于启用态（停用条目的匹配特征**冻结**）。
CREATE FUNCTION guard_ip_risk_match_term_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  entry_status varchar(16);
BEGIN
  SELECT e.status INTO entry_status
    FROM ip_risk_entry e
   WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entry_id;
  IF entry_status IS NULL THEN
    RAISE EXCEPTION
      '匹配特征引用了一条在本租户不存在的侵权条目：%', NEW.entry_id
      USING ERRCODE='23514';
  END IF;
  IF entry_status <> 'ACTIVE' THEN
    RAISE EXCEPTION
      '侵权条目 % 已停用，匹配特征已冻结（重新启用后才能改）', NEW.entry_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_risk_match_term_ref BEFORE INSERT OR UPDATE ON ip_risk_match_term
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_match_term_ref();

-- 🔴 (d) 匹配特征随条目一起冻结：条目停用后**不可再删改**匹配特征。
CREATE FUNCTION guard_ip_risk_match_term_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  entry_status varchar(16);
BEGIN
  IF TG_OP = 'DELETE' THEN
    SELECT e.status INTO entry_status
      FROM ip_risk_entry e
     WHERE e.tenant_id = OLD.tenant_id AND e.id = OLD.entry_id;
  ELSE
    SELECT e.status INTO entry_status
      FROM ip_risk_entry e
     WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entry_id;
  END IF;
  IF entry_status IS NOT NULL AND entry_status <> 'ACTIVE' THEN
    RAISE EXCEPTION
      '侵权条目 % 已停用，匹配特征已冻结（重新启用后才能改）', OLD.entry_id
      USING ERRCODE='23514';
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$;
CREATE TRIGGER ip_risk_match_term_frozen BEFORE DELETE ON ip_risk_match_term
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_match_term_frozen();

-- 🔴 (e) **不做物理删除**：条目只停用。
--   引用它的其他模块（未来的 16.4.1.2 检测结论）可能已经引用过这一行，
--   物理删除会让那些历史记录悬空。
CREATE FUNCTION guard_ip_risk_entry_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '侵权条目 % 不可物理删除（只能停用；停用后历史记录保留）', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER ip_risk_entry_no_delete BEFORE DELETE ON ip_risk_entry
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_entry_no_delete();

-- 🔴 (f) **停用后内容冻结**：停用条目的权利类型 / 风险等级 / 标题 / 品牌 /
--   匹配特征**不可再改**（那会改写「停用那一刻它是什么」）。
--   只允许**重新启用**（status → ACTIVE）；要改内容先启用再改。
CREATE FUNCTION guard_ip_risk_entry_content_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'DISABLED' THEN
    IF NEW.status <> 'ACTIVE' THEN
      RAISE EXCEPTION
        '侵权条目 % 已停用，停用后不可再改（只允许重新启用）', OLD.id
        USING ERRCODE='23514';
    END IF;
    IF NEW.right_type IS DISTINCT FROM OLD.right_type
       OR NEW.risk_level IS DISTINCT FROM OLD.risk_level
       OR NEW.title IS DISTINCT FROM OLD.title
       OR NEW.rights_holder IS DISTINCT FROM OLD.rights_holder
       OR NEW.brand_name IS DISTINCT FROM OLD.brand_name
       OR NEW.brand_id IS DISTINCT FROM OLD.brand_id
       OR NEW.match_pattern IS DISTINCT FROM OLD.match_pattern THEN
      RAISE EXCEPTION
        '侵权条目 % 已停用，匹配特征已冻结（重新启用后才能改）', OLD.id
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_risk_entry_content_frozen BEFORE UPDATE ON ip_risk_entry
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_entry_content_frozen();

-- 🔴 (g) 导入批次 / 导入行 **append-only**：导入这件事本身就是证据，
--   事后改写行级结果等于「把导入记录做成假的」。
CREATE FUNCTION guard_ip_risk_import_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '导入记录 % 不可改写（append-only）', OLD.id USING ERRCODE='23514';
END $$;
CREATE TRIGGER ip_risk_import_batch_append_only BEFORE UPDATE OR DELETE ON ip_risk_import_batch
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_import_append_only();
CREATE TRIGGER ip_risk_import_row_append_only BEFORE UPDATE OR DELETE ON ip_risk_import_row
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_import_append_only();

-- 🔴 (h) 导入行必须挂在本租户真实存在的一批上。
CREATE FUNCTION guard_ip_risk_import_row_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM ip_risk_import_batch b
        WHERE b.tenant_id = NEW.tenant_id AND b.id = NEW.batch_id) THEN
    RAISE EXCEPTION
      '导入行引用了一批在本租户不存在的导入批次：%', NEW.batch_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_risk_import_row_ref BEFORE INSERT ON ip_risk_import_row
  FOR EACH ROW EXECUTE FUNCTION guard_ip_risk_import_row_ref();

-- ===========================================================================
-- 七、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ip_risk_entry','ip_risk_entry_platform','ip_risk_match_term',
    'ip_risk_import_batch','ip_risk_import_row' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 八、账号与授权
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
  ip_risk_entry, ip_risk_entry_platform, ip_risk_match_term,
  ip_risk_import_batch, ip_risk_import_row TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V311 同口径）。
REVOKE ALL ON
  ip_risk_entry, ip_risk_entry_platform, ip_risk_match_term,
  ip_risk_import_batch, ip_risk_import_row FROM starcon_app;

-- 🔴 **序列授权**：本迁移的五张表**全部用应用侧生成的 uuid 作主键**，
--   没有任何 serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 / V300 / V311 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 侵权库属于「平台操作 / 合规台账」语义，
-- 与 16.2.1.1 / 16.4.2.1 同族，复用 identity 已注册的 channel.operation.*
-- （读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 九、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **上游 3.3.2 品牌（catalog 服务）**：**已交付** ⇒ 本模块经
--     `IpRiskLibrarySourcePort` 只读 `GET /catalog/brands`（转发调用方自己的 bearer）；
--     落库只存**逻辑 id**（`brand_id`），**不建影子表、不跨库 SQL**。
--     读不到时**显式说读不到**（available=false + 原因），
--     **绝不返回空集合冒充「上游一个数据都没有」**。
--   * **下游 16.4.1.2 检测与预警**：**未交付** ⇒ 本模块
--     **不实现检测逻辑、不产出任何检测结论、不建检测结果表**；
--     能力声明里如实标 available=false + ownerModule + 影响说明。
--   * **16.4.2.1 品牌授权 / 16.4.2.2 授权凭证**：**已交付但与本模块是两件事**
--     （那两个管「授权」，本模块管「风险条目」）⇒ **零 FK**，互不改对方的表。
--   * **4 刊登**：刊登前的检测归 16.4.1.2，本模块**不越界**做拦截。
-- ---------------------------------------------------------------------------
