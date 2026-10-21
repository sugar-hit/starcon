-- ===========================================================================
-- 16.4.2.1 品牌授权 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 189-195 行）：
--   ### 16.4.2.1 品牌授权
--   - 职责：管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
--           校验刊登时的品牌使用是否在授权范围内。
--   - 输入：授权录入；文件上传；有效期。
--   - 输出：授权档案 + 校验结果。
--   - 关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。
--           **授权过期是「平时没事、上新时出事」的典型**，需到期提醒与刊登阻断。
--
-- ---------------------------------------------------------------------------
-- 零、与 catalog 3.2.1.2「品牌授权」的边界（这一条必须写进代码里，否则半年后没人说得清）
-- ---------------------------------------------------------------------------
-- catalog 侧有一套 3.2.1.2 品牌授权（catalog 服务 V16__brand_management.sql 的 brand_authorization 表）：
-- 那是**品牌档案自带的授权凭证**（证书号 / 授权方 / 有效期 / 凭证 URI），
-- 回答的是「这个品牌有没有被授权过」这**一个问题**。
-- 本模块做的是**合规口径的授权档案**：授权方 + 授权范围（店铺 / 国家 / 类目 / 期限）
-- + 授权文件 + 被授权主体，并且**校验刊登时的品牌使用是否落在授权范围内**。
-- 两者的关系与纪律：
--   * 本模块**只经授权只读端口读** catalog 的品牌与品牌档案（BrandAuthorizationSourcePort），
--     **不建 catalog 的影子表**、不跨库 SQL、不跨库 FK；
--   * 本模块的授权档案是**本租户自己的合规台账**（写入所有权在本模块），
--     catalog 那套的写入所有权仍在 catalog 手里 —— 两边不互相写；
--   * catalog 那套**不算作本模块的交付物**，也不因为它存在就把本模块说成「已交付」。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.4.2.1，合规域）⇒ `brand_authz_*`（本迁移）。
--   * 同批 4.2.3.2 合规预检（刊登与内容域）⇒ `listing_compliance_*`。
--   * 同批 16.2.2.1 合规预检（合规域，体检报告）⇒ `compliance_check_*`。
--   * 未来 16.2.1.1 合规规则库 ⇒ `compliance_rule*`。
--   * catalog 服务自己的 3.2.1.2 品牌授权 ⇒ `brand_authorization`（**在 catalog 库里**，与本迁移无关）。
-- **不要用 `brand_authorization` 裸名**：那是 catalog 的命名空间。
--
-- ---------------------------------------------------------------------------
-- 二、依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本服务内已交付并被**真实读取**的上游：
--   * channel V1 `shop` / `shop_marketplace` / `marketplace` —— 2.1.1.1 店铺、2.1.1.3 站点与国家。
--     **授权范围里的「店铺 / 国家」两维就是从这里还原的**：店铺 → 启用站点 → 国家码。
--   * channel V1 `channel_scope` / `channel_operation` —— 2.1.x 数据范围（见第六节）。
--
-- 跨服务（**只经授权 HTTP API，不跨库 SQL / FK / 视图**）：
--   * 03 品牌（catalog `GET /catalog/brands`、`GET /catalog/brands/{id}`）——
--     品牌档案与关联类目。本模块**只读**，不建影子表。
--   * 06.1.2.3 供应商资质（**trade 服务** `GET /trade/supplier-qualifications/suppliers`、
--     `/brands`、`/qualifications`）—— 被授权主体（资质持有方）的候选来源。
--     🔴 任务书把它写成 catalog，实测它在 **trade** 服务
--     （backend/services/trade/.../V239__supplier_qualification.sql），本迁移按实测路径走。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 授权期限是**区间**，到期判定用**同源 DB 时钟**
-- ---------------------------------------------------------------------------
-- 授权期限是一个**区间**（effective_from / effective_to），不是一个「到期日」单值：
-- 蓝图写的是「授权范围（店铺/国家/类目/**期限**）」，而区间才能表达「这段时间内授权有效」。
-- 因此：
--   * `effective_from` / `effective_to` 都是 timestamptz，CHECK 住 `effective_to > effective_from`；
--   * 过期判定**一律用 DB 的 now()**（服务层 `select now()` 一次，
--     同一事务里 Java 侧比较与 SQL 侧比较用同一个时刻），
--     **禁用 LocalDate.now()** —— 应用机与数据库机的时钟差会让「授权还剩 0 天」变成差半天。
--   * 「剩余天数」也是 DB 算的（`extract(epoch from (effective_to - now()))/86400`）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 授权文件：文件本体**不入库**
-- ---------------------------------------------------------------------------
-- 蓝图输入写的是「文件上传」，但把 PDF / 扫描件塞进业务库会让这张表变成垃圾桶：
-- 备份、迁移、RLS 策略全部要为二进制付出代价，而且**租户隔离的漏洞面更大**。
-- 所以 `brand_authz_document` **只存文件引用与元数据**：
-- 文件名 / 内容类型 / 字节数 / 存储句柄 / 上传时刻 / 上传人。
-- 文件本体由文件服务 / 对象存储持有，本模块只持有句柄。
-- 🔴 真正的**凭证核验**（真伪验证记录、文件版本、签署方信息、一键核验）归 **16.4.2.2 授权凭证**，
-- 本模块**未交付**它 —— 见第八节。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 阻断类规则必须在**服务端**强制，前端提示不构成防线
-- ---------------------------------------------------------------------------
-- 卷首约束第一条。落到本模块上是三件事：
--
--   (a) **服务端算结论**：`POST /channel/brand-authorizations/publish-gate` 在服务端真算一遍
--       授权范围覆盖，��回 `allowed=false` + 逐条原因（哪条授权不覆盖、为什么不覆盖）。
--       4.2.3.2 预检与 4.3.1.1 一键刊登（均未交付 / 不归本模块）将来消费的是这个结论，
--       **不是**页面上的一个红字。
--   (b) **DB 层：结论不可追溯改写**。`brand_authz_check` 是**追加式**的判定凭据：
--       触发器 `guard_brand_authz_check_immutable` 钉住 in_scope / blocking / severity /
--       conclusion / rule_version / evaluated_at —— 事后改写不了，
--       「昨天那次为什么拦住」永远答得出来。
--   (c) **规则有版本**：`rule_version` 记在授权与判定两处。规则改了就是新版本，
--       **旧判定仍然是旧判定**（追加式，不 UPDATE）。
--
-- ⚠️ 本模块**不代** 4.3.1.1 执行刊登动作：刊登入口不在本服务。
-- 本模块交付的是**服务端算出来的阻断结论**与**不可篡改的判定凭据**。
--
-- ---------------------------------------------------------------------------
-- 六、多租户隔离：租户业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V194 / V195 / V197 / V198 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **为什么这四张表只有 isolation 策略、没有 data_scope（RESTRICTIVE）策略**：
-- 授权档案是**租户级合规资产**（同 V198 的 `listing_compliance_word` 词表、同 V195 的
-- `listing_template` 模板）：它不属于某一个店铺，也不属于某一个部门。
-- 硬套「按店铺负责人 / 部门收敛」的 data_scope 策略只会造出两种坏结果 ——
-- 要么授权档案在部分人眼里凭空消失（合规台账不可信），要么 NULL 店铺的行被策略吞掉。
-- 部门级收敛归 17 的权限体系，不在本模块硬造。
--
-- ===========================================================================
-- 一、授权档案（本模块的写入所有权）
-- ===========================================================================
CREATE TABLE brand_authz_authorization (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 03 品牌（catalog 服务，**跨服务逻辑 ID**，不建 FK；catalog 侧会变但本台账要留住）
  brand_id          uuid NOT NULL,
  -- 品牌名快照：catalog 读不到时界面仍有名字可显示（快照不是权威，权威在 catalog）
  brand_name        varchar(200) NOT NULL DEFAULT '',
  -- 🔴 授权方（品牌方 / 授权人）
  grantor           varchar(200) NOT NULL,
  -- 🔴 被授权主体（本租户这一侧）
  licensee_name     varchar(200) NOT NULL,
  licensee_type     text NOT NULL
                    CHECK(licensee_type IN ('SUPPLIER','DISTRIBUTOR','AGENT','SELF')),
  -- 6.1.2.3 供应商资质（**trade 服务**的跨服务逻辑 ID，可空）
  qualification_id  uuid,
  -- 🔴 授权期限：**区间**（开始 / 结束）。CHECK 保证它是个真区间。
  effective_from    timestamptz NOT NULL,
  effective_to      timestamptz NOT NULL,
  -- 人工停用（与「到期」是两件事：停用是立即生效的，撤销也是）
  enabled           boolean NOT NULL DEFAULT true,
  -- 判定规则版本：规则改了记新版本，**不追溯改变历史判定**
  rule_version      bigint NOT NULL DEFAULT 1,
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  version           bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, id),
  CHECK(btrim(grantor) <> ''),
  CHECK(btrim(licensee_name) <> ''),
  CHECK(effective_to > effective_from),
  CHECK(rule_version >= 1)
);
COMMENT ON TABLE brand_authz_authorization IS
  '16.4.2.1 · 合规口径的品牌授权档案（授权方 / 授权范围 / 授权文件 / 被授权主体）。'
  '**不是** catalog 3.2.1.2 的品牌授权凭证 —— 那套回答「这个品牌有没有被授权过」，'
  '本表回答「哪一段范围、哪一段时间、谁可以用这个品牌」。';
CREATE INDEX brand_authz_authorization_brand
  ON brand_authz_authorization(tenant_id, brand_id, effective_to);
CREATE INDEX brand_authz_authorization_expiry
  ON brand_authz_authorization(tenant_id, effective_to) WHERE enabled;
CREATE INDEX brand_authz_authorization_grantor
  ON brand_authz_authorization(tenant_id, grantor);

-- ===========================================================================
-- 二、授权范围（店铺 / 国家 / 类目）
-- ===========================================================================
-- 三种范围各一行。**同一维度上没登记任何范围 = 该维度不限制**
-- （授权覆盖全部店铺 / 全部国家 / 全部类目）。
-- scope_value 的口径：
--   SHOP     ⇒ 本服务 `shop.id`（uuid 小写）
--   COUNTRY  ⇒ ISO 3166-1 alpha-2 国家码（大写两位；CHECK 钉住大小写与长度）
--   CATEGORY ⇒ 03 类目 id（catalog 的跨服务逻辑 uuid）
CREATE TABLE brand_authz_scope (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  authorization_id uuid NOT NULL,
  scope_kind       text NOT NULL
                   CHECK(scope_kind IN ('SHOP','COUNTRY','CATEGORY')),
  scope_value      varchar(200) NOT NULL,
  -- 人话名字（店铺名 / 国家名 / 类目名），界面直接显示，不去反查上游
  scope_label      varchar(200) NOT NULL DEFAULT '',
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, authorization_id, scope_kind, scope_value),
  CHECK(btrim(scope_value) <> ''),
  -- 国家码必须是大写两位：大小写不一致会让「覆盖德国」判定静默失效
  CHECK(scope_kind <> 'COUNTRY' OR (scope_value = upper(scope_value) AND length(scope_value) = 2)),
  FOREIGN KEY(tenant_id, authorization_id)
    REFERENCES brand_authz_authorization(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE brand_authz_scope IS
  '16.4.2.1 · 一条授权的范围行。同一维度上没有任何行 = 该维度不限制。';
CREATE INDEX brand_authz_scope_lookup
  ON brand_authz_scope(tenant_id, authorization_id, scope_kind);

-- ===========================================================================
-- 三、授权文件（只存引用与元数据，文件本体不入库）
-- ===========================================================================
CREATE TABLE brand_authz_document (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  authorization_id uuid NOT NULL,
  -- 🔴 文件元数据（文件本体在对象存储里，本表只持有句柄）
  file_name        varchar(255) NOT NULL,
  content_type     varchar(120) NOT NULL,
  size_bytes       bigint NOT NULL CHECK(size_bytes >= 0),
  storage_key      varchar(400) NOT NULL,
  uploaded_at      timestamptz NOT NULL DEFAULT now(),
  uploaded_by      uuid,
  note             text NOT NULL DEFAULT '',
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(file_name) <> ''),
  CHECK(btrim(content_type) <> ''),
  CHECK(btrim(storage_key) <> ''),
  FOREIGN KEY(tenant_id, authorization_id)
    REFERENCES brand_authz_authorization(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE brand_authz_document IS
  '16.4.2.1 · 授权文件的引用与元数据（文件名 / 类型 / 大小 / 存储句柄 / 上传时刻）。'
  '**文件本体不入库**。凭证真伪核验归 16.4.2.2（未交付）。';
CREATE INDEX brand_authz_document_authorization
  ON brand_authz_document(tenant_id, authorization_id, uploaded_at DESC);

-- ===========================================================================
-- 四、校验结果（一次校验一条，追加式凭据）
-- ===========================================================================
-- 🔴 这一张表是「授权过期是平时没事、上新时出事」这件事的**证据**：
-- 上新那一刻到底为什么被拦、命中了哪条授权、当时用的哪一版规则 —— 全在这里。
CREATE TABLE brand_authz_check (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  -- 被校验的品牌（catalog 逻辑 ID）
  brand_id           uuid NOT NULL,
  brand_name         varchar(200) NOT NULL DEFAULT '',
  -- 刊登上下文（校验的是「这次刊登的这次品牌使用」）
  -- ⚠️ 这三列是**历史指针**，故意**不建 FK**：校验凭据必须活得比它引用的店铺 / 授权更久，
  --    店铺被归档、授权被撤销时，判定记录不能跟着消失（否则「当时为什么拦」就答不出来了）。
  shop_id            uuid,
  country_code       char(2),
  category_id        uuid,
  -- 判定时刻（**DB 时钟**）+ 判定用的规则版本
  evaluated_at       timestamptz NOT NULL,
  rule_version       bigint NOT NULL,
  -- 结论
  in_scope           boolean NOT NULL,
  blocking           boolean NOT NULL,
  -- 分级：BLOCKING 阻断刊登 / REVIEW 结论未知需人工复核 / NONE 通过
  severity           text NOT NULL CHECK(severity IN ('BLOCKING','REVIEW','NONE')),
  conclusion         text NOT NULL
                     CHECK(conclusion IN ('IN_SCOPE','OUT_OF_SCOPE','EXPIRED','NO_AUTHORIZATION','UNKNOWN')),
  -- 命中了哪条授权（IN_SCOPE 时非空）
  matched_authorization_id uuid,
  -- 🔴 逐条原因（**绝不返回没有原因的 false**）
  reasons            jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 判定时的输入快照（授权范围原文 / 上下文原文）
  snapshot           jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(jsonb_typeof(reasons) = 'array'),
  CHECK(jsonb_typeof(snapshot) = 'object'),
  CHECK(rule_version >= 1),
  CHECK(country_code IS NULL OR btrim(country_code) <> ''),
  -- 🔴 结论与标记必须自洽（应用层算错也在 DB 层被拒）
  --   severity='BLOCKING' ⟺ blocking
  --   IN_SCOPE ⟹ in_scope 且不阻断
  --   非 IN_SCOPE ⟹ 不在范围内
  CHECK((severity = 'BLOCKING') = blocking),
  CHECK(conclusion <> 'IN_SCOPE' OR (in_scope AND NOT blocking)),
  CHECK(conclusion = 'IN_SCOPE' OR NOT in_scope),
  CHECK(conclusion <> 'IN_SCOPE' OR matched_authorization_id IS NOT NULL),
  CHECK(conclusion = 'IN_SCOPE' OR jsonb_array_length(reasons) > 0)
);
COMMENT ON TABLE brand_authz_check IS
  '16.4.2.1 · 一次授权范围校验的结论（追加式凭据，触发器钉住不可改写）。'
  'reasons 是逐条原因：**没有原因的 false 是不允许出现的**。';
CREATE INDEX brand_authz_check_brand
  ON brand_authz_check(tenant_id, brand_id, evaluated_at DESC, id);
CREATE INDEX brand_authz_check_blocking
  ON brand_authz_check(tenant_id, blocking, evaluated_at DESC) WHERE blocking;

-- ===========================================================================
-- 五、触发器
-- ===========================================================================
-- 🔴 (a) 判定凭据**不可追溯改写**。
--     卷首约束第二条：「规则要有生效时间与版本，规则变更不能追溯改变历史判定」。
--     落到 DB 层就是：一条判定一旦写下，结论 / 阻断标记 / 规则版本 / 判定时刻都不许改。
--     规则改了 ⇒ 重跑一次 ⇒ 新的版本、新的行；旧行原样留着。
CREATE FUNCTION guard_brand_authz_check_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.in_scope IS DISTINCT FROM OLD.in_scope
     OR NEW.blocking IS DISTINCT FROM OLD.blocking
     OR NEW.severity IS DISTINCT FROM OLD.severity
     OR NEW.conclusion IS DISTINCT FROM OLD.conclusion
     OR NEW.rule_version IS DISTINCT FROM OLD.rule_version
     OR NEW.evaluated_at IS DISTINCT FROM OLD.evaluated_at
     OR NEW.matched_authorization_id IS DISTINCT FROM OLD.matched_authorization_id THEN
    RAISE EXCEPTION
      'Brand authorization check % is an append-only verdict; it cannot be rewritten', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER brand_authz_check_immutable BEFORE UPDATE ON brand_authz_check
  FOR EACH ROW EXECUTE FUNCTION guard_brand_authz_check_immutable();

-- 🔴 (b) **授权期限被改短时，历史判定不许跟着变** —— 这是 (a) 的另一种表现：
--     改授权的有效期是允许的（续约 / 撤销 / 提前终止都是业务动作），
--     但**已经写下的判定凭据不动**（(a) 已经钉住）。这里额外钉住一条：
--     授权的 rule_version 只能**前进**。规则版本回退 = 有人想把历史判定改回旧口径，
--     那不是修订，是篡改。
CREATE FUNCTION guard_brand_authz_authorization_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_version < OLD.rule_version THEN
    RAISE EXCEPTION
      'Brand authorization % rule_version cannot go backwards (% -> %)',
      OLD.id, OLD.rule_version, NEW.rule_version
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER brand_authz_authorization_version BEFORE UPDATE ON brand_authz_authorization
  FOR EACH ROW EXECUTE FUNCTION guard_brand_authz_authorization_version();

-- ===========================================================================
-- 六、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'brand_authz_authorization','brand_authz_scope','brand_authz_document','brand_authz_check' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

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
  brand_authz_authorization, brand_authz_scope, brand_authz_document, brand_authz_check TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V243 同口径）。
REVOKE ALL ON
  brand_authz_authorization, brand_authz_scope, brand_authz_document, brand_authz_check FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 品牌授权属于「平台操作 / 合规台账」语义，
-- 与 4.1.1.1 listing 列表、4.2.1.1 刊登编辑器、4.2.3.2 合规预检同族，
-- 复用 identity 已注册的 channel.operation.*，identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 八、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **16.4.2.2 授权凭证**：未交付 ⇒ 本模块只存文件的**引用与元数据**，
--     不做真伪验证记录 / 文件版本 / 签署方信息 / 一键核验。
--   * **16.4.1.1 侵权库 / 16.4.1.2 检测与预警**：未交付 ⇒ 本模块不做侵权检测，
--     只做**授权范围**这一件事。
--   * **16.2.1.1 合规规则库 / 16.2.1.2 拦截执行**：未交付 ⇒ 本模块不建规则库，
--     也不执行拦截；本模块只把**服务端算出来的阻断结论**给出去。
--   * **3.3.2 品牌状态**：已交付（catalog 的 brand / brand_authorization / brand_category），
--     本模块**只经只读端口读**，不写它的表。
--   * **4.2.3.2 预检**：已交付，它将来消费本模块的 publish-gate 结论；
--     本模块**不改它一个字节**（它是别的车道交付的）。
--   * **4.3.1.1 一键刊登**：未交付 ⇒ 本模块交付的是**服务端算出来的阻断结论**，
--     不代它执行刊登动作。
-- ---------------------------------------------------------------------------
