-- ===========================================================================
-- 16.4.2.2 授权凭证 —— channel 服务（阶段 D7，L3 锚点 16.4.2）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 196-201 行）：
--   ### 16.4.2.2 授权凭证
--   - 职责：集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。
--   - 输入：凭证上传；核验操作。
--   - 输出：凭证档案 + 核验记录。
--   - 关系：上游 = 16.4.2.1；下游 = 16.4.1.2。**凭证需保留原始文件与元数据**，争议时是唯一证据。
--
-- 归属：**channel 服务**（卷 16 合规与风控后端主责服务 = channel + identity，取第一项）。
-- 前端路由 /compliance/brand-credential，后端命名空间 /channel/brand-credentials。
--
-- ===========================================================================
-- 一、🔴 命名空间隔离（本批并行开发，前缀必须彼此隔离）
-- ===========================================================================
--   * 本模块（16.4.2.2）⇒ `brand_cred_*`（本迁移）。
--   * 16.4.2.1 品牌授权 ⇒ `brand_authz_*`（V248）—— **本模块不碰它的任何一张表**。
--   * 16.2.2.2 认证与资质 ⇒ `product_certification_*`（V247）—— 本模块不碰。
--   * 16.2.2.1 合规预检 ⇒ `compliance_check_*`（V197）—— 本模块不碰。
--   * 4.2.3.2 刊登预检 ⇒ `listing_compliance_*`（V198）—— 本模块不碰。
--   * catalog 服务自己库里的 3.2.1.2 品牌授权 ⇒ `brand_authorization`（**在 catalog 库里**）。
-- 🔴 **不要用 `brand_authz_` 前缀，也不要用裸名 `brand_authorization`**：
--    前者属于 16.4.2.1，后者属于 catalog。复用会让两个模块的表混在一个语义空间里，
--    而 16.4.2.1 当时正是为了避开这个坑才特意选了 `brand_authz_*`。
--
-- ===========================================================================
-- 二、🔴 与上游 16.4.2.1 的交接面（这一节决定本模块的表设计）
-- ===========================================================================
-- 16.4.2.1 交付时明确留下三样东西（原话见 docs/sessions/2026-10/2026-10-21-brand-authorization.md）：
--
--   (1) `brand_authz_document` **只存引用与元数据**（文件名 / 内容类型 / 字节数 / 存储句柄 /
--       上传时刻 / 上传人），**文件本体不入库**。它当时的理由是「把 PDF / 扫描件塞进业务库
--       会让这张表变成垃圾桶」。
--   (2) 「真伪验证记录、文件版本、签署方信息、一键核验」四样**明确没做**。
--   (3) 「`brand_authz_document` 的写入所有权可以移交过来」—— 那是**可以**，不是**必须**。
--
-- 🔴 本模块的决策（三条都写在这里，免得半年后没人说得清）：
--
--   (A) **不移交 `brand_authz_document` 的写入所有权** —— 两边保持**各自独立**。
--       理由有三条，缺一不可：
--         * 交接意味着要 ALTER 16.4.2.1 的既有表（加 version_no / sha256 / 签署方列），
--           而**加 NOT NULL 列会直接打挂 16.4.2.1 已交付的 INSERT 路径**（老 INSERT 不写新列
--           ⇒ 必然违反 not-null 约束）。本仓纪律是「加列必须带 DEFAULT 或可空」。
--         * 16.4.2.1 的 `brand_authz_document` 是「授权档案下的一个文件**引用**」，
--           本模块的 `brand_cred_document` 是「凭证文件的**本体 + 版本 + 摘要**」。
--           两者回答的问题不同：前者是「这份授权带了个文件」，后者是「这个凭证的第几版文件、
--           内容是不是原件」。合并成一张表反而会让「凭证版本」这种概念挂在授权下面。
--         * **凭证必须活得比它引用的东西更久**：授权被撤销 / 归档时，凭证作为争议时的
--           唯一证据必须留下来。所以凭证侧不建指向 16.4.2.1 表的外键。
--       ⇒ 两边**不互相写**，也没有任何一处同时往同一张表写。
--
--   (B) **文件本体的存储是本模块的责任**（蓝图加粗句：「凭证需保留原始文件与元数据」）。
--       本迁移落 `brand_cred_document`：文件本体真写进**受管存储**
--       （`LocalCredentialDocumentStorage`，与 16.2.2.2 的 `LocalCertificationDocumentStorage`
--       同一手法：本地文件系统 + 路径穿越防护 + 键里带租户段），
--       库里只存**存储句柄 + 文件名 + MIME + 字节数 + sha256 摘要 + 上传时刻**。
--       🔴 16.4.2.1 的「文件本体不入库」这个判断**本模块沿用**（二进制进业务库会让备份、
--       迁移与 RLS 审计都变成搬文件的工作），但**「谁负责把文件放进去」由本模块承担** ——
--       不是「什么都不做」，也不是「16.4.2.1 已经存好了」。
--
--   (C) **上游读取是同库只读**：16.4.2.1 与本模块**同属 channel 服务、同一个库**，
--       所以 `brand_authz_authorization` 是**同库读**（不是跨服务 SQL）。
--       本模块**只读**它（凭证挂在哪条授权下），**不写**它。
--       引用方式是**逻辑 id**（`authorization_id uuid`，**不建 FK**）——
--       授权被删 / 撤销时凭证与核验记录必须还在。
--
-- ===========================================================================
-- 三、🔴 本迁移**零 ALTER 任何既有表**（跨车道纪律）
-- ===========================================================================
--   * 不 ALTER 16.4.2.1 的四张表（`brand_authz_authorization` / `_scope` / `_document` / `_check`）。
--   * 不 ALTER 16.2.2.2 的 `product_certification*` 任何一张表。
--   * 不 ALTER channel V1..V243 的任何一张表。
--   * 不建任何 `brand_authz_*` / `listing_compliance_*` / `compliance_check_*` / `compliance_rule*` 对象。
--   * 新增函数全部带 `brand_cred_` 前缀，与既有函数名不冲突。
--   * 版本号 V255 由调度者**预分配**，本迁移不自己找最大版本号（并行车道会同时提交迁移）。
--
-- ===========================================================================
-- 四、🔴 真伪核验的「锚点」是文件摘要，不是文件名
-- ===========================================================================
-- 「真伪验证」在企业场景里能做到的是：**把受管存储里的文件本体读回来，重算 sha256，
-- 与登记时记下的摘要逐字比对**。摘要不符 ⇒ 文件在登记之后被改过（或换了内容）⇒ 判定失败。
-- 这不是「连上品牌方的服务器验签」（那需要对方的接口，不在本模块范围内），
-- 但它是**本地可复算、可审计、争议时拿得出来**的证据链。
--
--   * `sha256` 存 **小写十六进制 64 位**，DB 层 CHECK 钉死形态。
--   * 核验**真的把文件读回来重算**（不是比对数据库里存的两列）—— 服务层实现，
--     集成测试会真的改掉磁盘上的文件再核验一次，断言判定变成 DIGEST_MISMATCH。
--   * 文件不在受管存储（被删 / 卷掉了）⇒ FILE_MISSING，**不是**「通过」。
--
-- ===========================================================================
-- 五、🔴 核验记录是**追加式凭据**，规则有版本
-- ===========================================================================
-- 卷首约束第二条：「规则要有生效时间与版本，规则变更不能追溯改变历史判定」。
-- 落到本模块是三件事：
--
--   (a) `brand_cred_verification` 一行一次核验，**只追加**：DB 触发器
--       `guard_brand_cred_verification_immutable` 钉住 conclusion / severity / passed /
--       rule_version / evaluated_at / document_id / credential_id 事后改写不了。
--   (b) `rule_version` 记在核验记录上：规则改了就是**新版本 + 新行**，
--       **旧行原样留着**（「上次为什么判成那样」永远答得出来）。
--   (c) 核验记录**不建指向凭证与文件的外键** —— 判定凭据必须活得比它引用的东西更久
--       （同 16.4.2.1 的 `brand_authz_check` 纪律）。
--
-- 🔴 **没有原因的不合格判定不允许出现**：DB 层 CHECK
--   `conclusion <> 'VERIFIED' OR ...` 与 `conclusion = 'VERIFIED' OR jsonb_array_length(reasons) > 0`
--   一起把住 —— 判成不通过就必须给出逐条原因。
--
-- ===========================================================================
-- 六、多租户隔离：tenant_id NOT NULL + ENABLE **与 FORCE** RLS
-- ===========================================================================
-- 与 channel V1 / V247 / V248 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 这四张表**只有 isolation 策略、没有 data_scope（RESTRICTIVE）策略**：
-- 凭证档案是**租户级合规资产**（同 16.2.2.2 的认证档案、同 16.4.2.1 的授权档案）：
-- 它不属于某一个店铺，也不属于某一个部门。硬套「按店铺负责人 / 部门收敛」的 data_scope
-- 策略只会造出两种坏结果 —— 要么凭证在部分人眼里凭空消失（合规台账不可信），
-- 要么 NULL 店铺的行被策略吞掉。部门级收敛归 17 的权限体系，不在本模块硬造。
--
-- ===========================================================================
-- 1. brand_cred_credential · 凭证档案主表
-- ===========================================================================
CREATE TABLE brand_cred_credential (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 上游 16.4.2.1 的授权档案 id（**同库逻辑引用，不建 FK**：
  --    凭证是争议时的唯一证据，必须活得比它引用的授权更久）
  authorization_id  uuid,
  -- 授权方（品牌方 / 签发方）
  grantor           varchar(200) NOT NULL,
  -- 凭证编号（与授权方一起唯一：不同授权方可能各有一个 "BR-001"）
  credential_no     varchar(120) NOT NULL,
  -- 凭证类型（受控词表，见本节末的 CHECK）
  credential_type   varchar(24) NOT NULL
                    CHECK(credential_type IN ('BRAND_LICENSE','DISTRIBUTOR_AGREEMENT',
                                              'TRADEMARK_CERTIFICATE','CERTIFICATE_OF_ORIGIN',
                                              'AUTHORIZATION_LETTER','OTHER')),
  -- 被授权主体
  subject           varchar(200) NOT NULL DEFAULT '',
  -- 关联品牌（catalog 的跨服务逻辑 id，可空）+ 名称快照（读不到时界面仍有名字）
  brand_id          uuid,
  brand_name        varchar(200) NOT NULL DEFAULT '',
  -- 🔴 有效期是**区间**（同 16.4.2.1 的纪律）：授权凭证的「有效」是一段时间
  valid_from        timestamptz NOT NULL,
  valid_until       timestamptz NOT NULL,
  -- 签发日（凭证上写的日期；可空 —— 有的凭证不写签发日）
  issued_on         date,
  -- 状态：ACTIVE 有效 / REVOKED 已撤销（被授权方撤销或作废）
  --   🔴 与「已过期」是**两件事**：过期是时间到了，撤销是这张凭证不再被承认。
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
  -- 撤销留痕（status=REVOKED 时必须齐）
  revoked_at        timestamptz,
  revoked_reason    text NOT NULL DEFAULT '',
  note              text NOT NULL DEFAULT '',
  -- 行级乐观锁（修改凭证档案时比对）
  version           bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, grantor, credential_no),
  CHECK(btrim(grantor) <> ''),
  CHECK(btrim(credential_no) <> ''),
  -- 有效期窗口必须自洽：起止反了写进来会得到「永远不过期」或「一出生就过期」的档案
  CHECK(valid_until > valid_from),
  -- 撤销必须留痕，否则「这张凭证为什么不能用」永远答不出来
  CHECK(status <> 'REVOKED' OR (revoked_at IS NOT NULL AND btrim(revoked_reason) <> ''))
);
COMMENT ON TABLE brand_cred_credential IS
  '16.4.2.2 · 授权凭证档案主表（授权方 / 凭证编号 / 类型 / 有效期 / 状态）。'
  '🔴 当前文件版本与最近核验结论**不落库**，由读出口从 brand_cred_document / '
  'brand_cred_verification **现算**（存一列就要一个任务去维护它，维护出来那天与算出来那天差一天时'
  '「这张凭证到底核验过没有」会答错）。';
CREATE INDEX brand_cred_credential_list
  ON brand_cred_credential(tenant_id, credential_type, status, valid_until, id);
CREATE INDEX brand_cred_credential_authorization
  ON brand_cred_credential(tenant_id, authorization_id);
CREATE INDEX brand_cred_credential_grantor
  ON brand_cred_credential(tenant_id, grantor);
CREATE INDEX brand_cred_credential_no
  ON brand_cred_credential(tenant_id, credential_no);

-- ===========================================================================
-- 2. brand_cred_signatory · 签署方信息
-- ===========================================================================
-- 蓝图点名的四样里「签署方信息」落在这里。一个凭证可能有多个签署方
-- （品牌方的法务 + 区域代理），所以是子表而不是主表上的一列。
CREATE TABLE brand_cred_signatory (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  credential_id     uuid NOT NULL,
  -- 签署人姓名
  signatory_name    varchar(200) NOT NULL,
  -- 职务 / 头衔
  signatory_title   varchar(120) NOT NULL DEFAULT '',
  -- 所属组织
  signatory_org     varchar(200) NOT NULL DEFAULT '',
  -- 联系方式（邮箱 / 电话）
  contact           varchar(200) NOT NULL DEFAULT '',
  -- 签署方身份是否已人工核验过（核验时缺这一项会记一条 SIGNATORY_UNVERIFIED 原因）
  identity_verified boolean NOT NULL DEFAULT false,
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(signatory_name) <> ''),
  FOREIGN KEY(tenant_id, credential_id)
    REFERENCES brand_cred_credential(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE brand_cred_signatory IS
  '16.4.2.2 · 凭证的签署方信息（姓名 / 职务 / 组织 / 联系方式 / 身份是否已核验）。';
CREATE INDEX brand_cred_signatory_credential
  ON brand_cred_signatory(tenant_id, credential_id, id);

-- ===========================================================================
-- 3. brand_cred_document · 凭证文件版本（**只追加**）
-- ===========================================================================
-- 🔴 「文件版本」落在这里：凭证的每一个版本都是**一行**，version_no 从 1 递增，
-- **不覆盖**。续期 / 换件 = 上传新的一行（version_no +1），
-- 原来交的是什么，是合规追溯要用的凭据。
--
-- 🔴 文件本体在**受管存储**里，本表只持有句柄 + 元数据 + **sha256 摘要**
-- （摘要就是真伪核验的锚点，见第四节）。
CREATE TABLE brand_cred_document (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  credential_id     uuid NOT NULL,
  -- 🔴 版本号：同一凭证内从 1 递增，**只追加**
  version_no        integer NOT NULL CHECK(version_no >= 1),
  -- 文件元数据（文件本体在受管存储里，本表只持有句柄）
  file_name         varchar(255) NOT NULL,
  content_type      varchar(120) NOT NULL DEFAULT '',
  -- 文件大小（字节）。数量刻度与全仓一致：numeric(19,6)，禁浮点。
  size_bytes        numeric(19,6) NOT NULL DEFAULT 0 CHECK(size_bytes >= 0),
  storage_key       varchar(500) NOT NULL,
  -- 🔴 文件摘要（小写十六进制 sha256）—— 真伪核验的锚点
  sha256            char(64) NOT NULL,
  uploaded_by       uuid,
  uploaded_at       timestamptz NOT NULL,
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一凭证内版本号唯一 ⇒ 并发上传不会写出两个「第 1 版」
  UNIQUE(tenant_id, credential_id, version_no),
  CHECK(btrim(file_name) <> ''),
  CHECK(btrim(storage_key) <> ''),
  -- 摘要形态钉死（小写十六进制 64 位），否则「大写摘要」会让比对静默失效
  CHECK(sha256 ~ '^[0-9a-f]{64}$'),
  FOREIGN KEY(tenant_id, credential_id)
    REFERENCES brand_cred_credential(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE brand_cred_document IS
  '16.4.2.2 · 凭证文件版本（**只追加**）。文件本体在受管存储里（不进业务库），'
  '本表存句柄 + 元数据 + sha256 摘要。';
CREATE INDEX brand_cred_document_credential
  ON brand_cred_document(tenant_id, credential_id, version_no DESC);

-- ===========================================================================
-- 4. brand_cred_verification · 核验记录（**追加式凭据**）
-- ===========================================================================
-- 🔴 这一张表是「争议时是唯一证据」的落点：
-- 哪一天、谁、按哪一版规则、把第几版文件读回来重算 sha256、结论是什么、逐条原因是什么 ——
-- 全在这里，且事后改写不了。
CREATE TABLE brand_cred_verification (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  -- 🔴 逻辑引用，**不建 FK**：判定凭据必须活得比它引用的东西更久
  credential_id       uuid NOT NULL,
  -- 被核验的文件版本（逻辑 id，可空：凭证还没有任何文件时也要能记一条 NO_DOCUMENT）
  document_id         uuid,
  document_version_no integer NOT NULL DEFAULT 0,
  -- 判定时刻（**DB 时钟**）+ 判定用的规则版本
  evaluated_at        timestamptz NOT NULL,
  rule_version        bigint NOT NULL,
  -- 结论（八态）
  conclusion          varchar(24) NOT NULL
                      CHECK(conclusion IN ('VERIFIED','DIGEST_MISMATCH','FILE_MISSING',
                                           'EXPIRED','NOT_YET_EFFECTIVE',
                                           'SIGNATORY_INCOMPLETE','NO_DOCUMENT','REVOKED')),
  -- 分级：PASS 通过 / FAIL 不通过
  severity            varchar(16) NOT NULL CHECK(severity IN ('PASS','FAIL')),
  passed              boolean NOT NULL,
  -- 🔴 逐条原因（**绝不返回没有原因的不通过**）
  reasons             jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 判定时的输入快照（登记摘要 / 实算摘要 / 文件版本 / 签署方数 / 有效期）
  snapshot            jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 核验人
  checked_by          uuid,
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(jsonb_typeof(reasons) = 'array'),
  CHECK(jsonb_typeof(snapshot) = 'object'),
  CHECK(rule_version >= 1),
  CHECK(document_version_no >= 0),
  -- 🔴 结论与标记必须自洽（应用层算错也在 DB 层被拒）
  --   通过 ⟺ 结论是 VERIFIED；PASS ⟺ 通过
  CHECK(passed = (conclusion = 'VERIFIED')),
  CHECK((severity = 'PASS') = passed),
  -- 🔴 没有原因的不通过不允许出现
  CHECK(conclusion = 'VERIFIED' OR jsonb_array_length(reasons) > 0)
);
COMMENT ON TABLE brand_cred_verification IS
  '16.4.2.2 · 一次凭证核验的结论（追加式凭据，触发器钉住不可改写）。'
  'reasons 是逐条原因：**没有原因的不通过是不允许出现的**。';
CREATE INDEX brand_cred_verification_credential
  ON brand_cred_verification(tenant_id, credential_id, evaluated_at DESC, id);
CREATE INDEX brand_cred_verification_conclusion
  ON brand_cred_verification(tenant_id, conclusion, evaluated_at DESC);

-- ===========================================================================
-- 5. 守卫触发器
-- ===========================================================================
-- 🔴 (a) 核验记录**不可追溯改写**。
--     规则改了 ⇒ 重跑一次 ⇒ 新的版本、新的行；旧行原样留着。
CREATE FUNCTION guard_brand_cred_verification_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.conclusion IS DISTINCT FROM OLD.conclusion
     OR NEW.severity IS DISTINCT FROM OLD.severity
     OR NEW.passed IS DISTINCT FROM OLD.passed
     OR NEW.rule_version IS DISTINCT FROM OLD.rule_version
     OR NEW.evaluated_at IS DISTINCT FROM OLD.evaluated_at
     OR NEW.document_id IS DISTINCT FROM OLD.document_id
     OR NEW.document_version_no IS DISTINCT FROM OLD.document_version_no
     OR NEW.credential_id IS DISTINCT FROM OLD.credential_id THEN
    RAISE EXCEPTION
      'Brand credential verification % is an append-only verdict; it cannot be rewritten', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER brand_cred_verification_immutable BEFORE UPDATE ON brand_cred_verification
  FOR EACH ROW EXECUTE FUNCTION guard_brand_cred_verification_immutable();

-- 🔴 (b) 凭证文件版本**只追加**：续期 / 换件是「再上传一份」，不是「覆盖掉原来那份」。
--     原来交的是什么，是合规追溯要用的凭据。
CREATE FUNCTION guard_brand_cred_document_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '授权凭证文件不可改写（表 brand_cred_document）：'
    '请上传新版本，credential_id=%', OLD.credential_id
    USING ERRCODE='restrict_violation';
END $$;
CREATE TRIGGER brand_cred_document_no_update
  BEFORE UPDATE ON brand_cred_document
  FOR EACH ROW EXECUTE FUNCTION guard_brand_cred_document_append_only();
CREATE TRIGGER brand_cred_document_no_delete
  BEFORE DELETE ON brand_cred_document
  FOR EACH ROW EXECUTE FUNCTION guard_brand_cred_document_append_only();

-- 🔴 (c) 核验记录**不可删除**（它是「当时为什么这么判」的证据）。
CREATE FUNCTION guard_brand_cred_verification_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '授权凭证核验记录不可删除（表 brand_cred_verification）：'
    'credential_id=%', OLD.credential_id
    USING ERRCODE='restrict_violation';
END $$;
CREATE TRIGGER brand_cred_verification_no_delete
  BEFORE DELETE ON brand_cred_verification
  FOR EACH ROW EXECUTE FUNCTION guard_brand_cred_verification_no_delete();

-- 🔴 (d) 撤销凭证必须留痕（时刻 + 理由）—— 与 16.2.2.2 同一纪律。
CREATE FUNCTION guard_brand_cred_revoked() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = 'REVOKED' THEN
    NEW.revoked_at := COALESCE(NEW.revoked_at, now());
    IF btrim(NEW.revoked_reason) = '' THEN
      RAISE EXCEPTION '撤销授权凭证必须写明理由（凭证 %）', NEW.credential_no
        USING ERRCODE='restrict_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER brand_cred_revoked
  BEFORE UPDATE OF status ON brand_cred_credential
  FOR EACH ROW EXECUTE FUNCTION guard_brand_cred_revoked();

-- ===========================================================================
-- 6. 多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'brand_cred_credential','brand_cred_signatory','brand_cred_document','brand_cred_verification' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 7. 账号与授权
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
  brand_cred_credential, brand_cred_signatory, brand_cred_document, brand_cred_verification TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V248 同口径）。
REVOKE ALL ON
  brand_cred_credential, brand_cred_signatory, brand_cred_document, brand_cred_verification FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 授权凭证属于「平台操作 / 合规台账」语义，
-- 与 4.1.1.1 listing 列表、16.2.2.1 合规预检、16.2.2.2 认证与资质、16.4.2.1 品牌授权同族，
-- 复用 identity 已注册的 channel.operation.*，identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 8. 留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **16.4.2.1 品牌授权**（已交付，同库）：本模块**只读** `brand_authz_authorization`
--     拿「授权档案候选」，凭证通过 `authorization_id`（逻辑 id）挂在它下面。
--     本模块**不写**它的任何一张表，`brand_authz_document` 的写入所有权**不移交**。
--   * **16.4.1.2 检测与预警**（未交付）：本模块的核验结论目前只落在核验记录里，
--     不会自动推成预警工单。16.4.1.2 交付后接上。
--   * **16.2.1.1 合规规则库**（未交付）：当前核验规则的口径是硬编码的
--     （`CURRENT_RULE_VERSION = 1`），没有「哪一版规则、从何时生效」的来源。
--     16.2.1.1 交付后把规则搬进规则库并让版本号从库里取。
--   * **品牌方侧的验签接口**：本模块的「真伪核验」是**本地重算 sha256 + 比对登记摘要**
--     （可复算、可审计、争议时拿得出来）。真正的「连对方系统验签」需要品牌方提供接口，
--     不在本模块范围内，capabilities 里如实写明。
-- ---------------------------------------------------------------------------
