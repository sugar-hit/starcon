-- ===========================================================================
-- 16.3.2.2 EPR / 包装法 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 161-166 行）：
--   ### 16.3.2.2 EPR/包装法
--   - 职责：管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、
--           适用国家与品类、申报周期与关联费用。
--   - 输入：注册信息；适用规则；申报数据。
--   - 输出：EPR 档案 + 合规状态 + 费用记录。
--   - 关系：上游 = 16.3.1.1；下游 = 12 财务（费用）、4 刊登（是否允许在相关国家销售）。
--           **EPR 未注册的国家应直接限制刊登**，这是避免下架与罚款最有效的做法。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块存在的理由：**「未注册 ⇒ 限制刊登」必须真的落地**
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗是硬要求，不是「页面上显示一个红字」。本迁移把它落成**三道**：
--
--   * **参考层**：`epr_country_regime` 钉死「哪些国家要求哪些制度」——「某国根本不需要 EPR」
--     （NOT_APPLICABLE）与「需要但没注册」（NOT_REGISTERED）是**两件事**，混为一谈会把
--     不该拦的全拦了。
--   * **判定层**：SQL 函数 `epr_gate_findings(tenant, country, category)` 是**唯一的判定源**。
--     服务层的 `publish-gate` 端点与 DB 层的刊登触发器**调同一个函数** ⇒ 页面上的结论
--     与真正拦人的那道闸门**不可能各说各话**。
--   * **拦截层**：BEFORE 触发器挂在 4 刊登的**两个真实放行口**上：
--       - `listing_publish_job_target`（4.3.1.1 一键刊登，state → 'SUBMITTED' = 真的发给平台）
--       - `publish_task`（4.3.1.2 批量发布，state → 'DISPATCHED' = 真的派发）
--     触发器**只加函数、零 ALTER 既有表的任何列**，与 V198 的
--     `guard_listing_editor_submission_compliance` 同一手法。
--
-- 🔴 **为什么「制度」是本模块的粒度，而不是沿用 16.3.1.1 的辖区级注册号**：
--   上游 `tax_registration_record.epr_registration_no` 是**辖区级粗粒度**登记（一个辖区一个号）；
--   而同一个国家下 LUCID / 包装注册 / 电池法 / WEEE **各有各的注册号**、各有各的申报周期与费用。
--   ⇒ 两者是「粗 / 细」**两层**，不是同一份事实的两个副本：
--     本模块**独立拥有「制度级」注册号这一层事实**，**不写** `tax_registration_record`
--     （上游写所有权不变），也**不把上游那个号复制一份当自己的权威源**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 表命名空间（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.3.2.2）⇒ `epr_*`。
--   与既有 `tax_registration_*`（16.3.1.1）、`compliance_rule_*`（16.2.1.1）、
--   `compliance_intercept_*`（16.2.1.2）、`compliance_check_*`（16.2.2.1）、
--   `product_certification_*`（16.2.2.2）、`brand_authz_*` / `brand_cred_*`（16.4.2.x）
--   **彼此隔离**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 制度类型用受控枚举钉死（不许运行时塞字符串）
-- ---------------------------------------------------------------------------
-- `epr_regime` 是**全局码表**（不带 tenant_id，与 V1 的 platform / marketplace 同口径）：
-- 制度是业务事实（德国 LUCID / 包装注册 / 电池法 / WEEE），不是租户数据。
-- 登记表上的 `regime_code` 由**触发器**校验必须真实落在这张码表里 —— 只用 CHECK 是钉不住的
-- （CHECK 看不到别的表），所以用触发器，这是「不要留自由文本」这条要求的物理落点。
-- **新增制度走 DB 迁移加值域**（改 CHECK + 改码表 + 加引用数据），不许运行时塞字符串。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V197 / V248 / V300 / V308 / V312 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **数据范围（RESTRICTIVE）本模块刻意不套**：EPR 登记是**租户级合规事实**
--   （和法人维度的税务登记同理，V312 的注释已写明理由：法人的数据范围归 identity 所有，
--   本服务读不到，硬套店铺收敛会让「这个租户在德国到底注册了没有」凭空消失）。
--   本模块的档案**不挂在任何一家店下面**，所以只有 isolation 策略。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 零外键（跨表引用一律用触发器）
-- ---------------------------------------------------------------------------
-- 与同卷 V300 account_health / V308 platform_violation / V312 tax_registration 同一纪律：
-- 外键会打破同服务既有测试的 TRUNCATE。跨表引用校验改成 BEFORE 触发器。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 金额与数量纪律
-- ---------------------------------------------------------------------------
-- 费用金额 `numeric(19,4)` + **币种** `char(3)`，**禁浮点**。
-- 汇总时**按币种分组**，**不许把不同币种直接相加**（EUR + USD 不是钱）。
-- 数量类列只有条数计数（integer）。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 时间字段纪律
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 用 `Timestamp.valueOf(LocalDateTime...)` 会**静默偏 −28800s**，页面上看不出异常。
-- ===========================================================================

-- ===========================================================================
-- 一、epr_regime · EPR 制度码表（全局码表，不带 tenant_id）
-- ===========================================================================
-- 🔴 制度是业务事实，发布时随代码更新，不随租户变化。
--   * LUCID                 德国 LUCID 包装登记（ZSVR）
--   * PACKAGING_REGISTRATION 包装注册（法国 ADEME/SYDEREP、意大利 CONAI …）
--   * BATTERY               电池法（德国 BattG / Stiftung EAR …）
--   * WEEE                  电子废弃物（德国 ElektroG / EAR …）
CREATE TABLE epr_regime (
  id                  uuid PRIMARY KEY,
  code                varchar(32) NOT NULL UNIQUE,
  name                varchar(100) NOT NULL,
  -- 主管机构（登记与申报的对接方；是业务事实，不是备注）
  authority           varchar(200) NOT NULL DEFAULT '',
  -- 默认申报周期（登记时可留空，留空就是「没记」）
  default_filing_cycle varchar(16) NOT NULL
                      CHECK(default_filing_cycle IN ('MONTHLY','QUARTERLY','ANNUAL','ONE_TIME')),
  position            int NOT NULL DEFAULT 0,
  CHECK(btrim(code)<>'' AND btrim(name)<>''),
  -- 🔴 制度值域钉死在 CHECK 里：新增制度走 DB 迁移改这里，不许运行时塞字符串
  CHECK(code IN ('LUCID','PACKAGING_REGISTRATION','BATTERY','WEEE'))
);
COMMENT ON TABLE epr_regime IS
  '16.3.2.2 · EPR 制度码表（全局码表，不带 tenant_id）。'
  '制度是业务事实（德国 LUCID / 包装注册 / 电池法 / WEEE），值域由 CHECK 钉死；'
  '登记档案上的 regime_code 由触发器校验必须落在这张表里，不留自由文本。';

INSERT INTO epr_regime (id,code,name,authority,default_filing_cycle,position)
SELECT (substr(md5('eprregime:'||v.code),1,8)||'-'||substr(md5('eprregime:'||v.code),9,4)||'-4'
        ||substr(md5('eprregime:'||v.code),14,3)||'-a'||substr(md5('eprregime:'||v.code),18,3)||'-'
        ||substr(md5('eprregime:'||v.code),21,12))::uuid,
       v.code::varchar(32), v.name::varchar(100), v.authority::varchar(200),
       v.cycle::varchar(16), v.pos::int
FROM (VALUES
 ('LUCID','德国 LUCID 包装登记','Zentrale Stelle Verpackungsregister (ZSVR)','ANNUAL',10),
 ('PACKAGING_REGISTRATION','包装注册','各国包装登记机构（如法国 ADEME / 意大利 CONAI）','ANNUAL',20),
 ('BATTERY','电池法','各国电池登记机构（如德国 Stiftung EAR）','QUARTERLY',30),
 ('WEEE','电子废弃物','各国 WEEE 登记机构（如德国 EAR）','QUARTERLY',40)
) AS v(code,name,authority,cycle,pos);

-- ===========================================================================
-- 二、epr_category · EPR 适用品类码表（全局码表，不带 tenant_id）
-- ===========================================================================
-- 🔴 「适用品类」是 EPR 制度的适用维度（包装 / 电子 / 电池 / 纺织 …），
--   与 03 商品类目（平台侧原文 category_path）**不是同一个东西**，别混。
CREATE TABLE epr_category (
  code      varchar(32) PRIMARY KEY,
  name      varchar(100) NOT NULL,
  position  int NOT NULL DEFAULT 0,
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
COMMENT ON TABLE epr_category IS
  '16.3.2.2 · EPR 适用品类码表（全局码表，不带 tenant_id）。'
  '这是 EPR 制度的适用维度（包装 / 电子 / 电池 …），**不是**平台侧商品类目。';

INSERT INTO epr_category (code,name,position) VALUES
 ('PACKAGING','包装',10),
 ('ELECTRONICS','电子电器',20),
 ('BATTERIES','电池',30),
 ('TEXTILES','纺织品',40),
 ('FURNITURE','家具',50),
 ('MEDICAL_DEVICES','医疗器械',60),
 ('PHARMACEUTICALS','药品',70),
 ('OIL','矿物油与润滑油',80),
 ('TIRES','轮胎',90),
 ('PAPER','纸制品',100);

-- ===========================================================================
-- 三、epr_country_regime · 哪些国家要求哪些制度（全局码表，不带 tenant_id）
-- ===========================================================================
-- 🔴 **这张表就是「不适用」与「未注册」的分界线**：
--   (country_code, regime_code) 在这张表里 ⇒ 该国**要求**该制度 ⇒ 没注册就是**阻断**；
--   不在这张表里 ⇒ 该国**不要求**该制度 ⇒ NOT_APPLICABLE，**放行**。
--   把 NOT_APPLICABLE 当成未注册，会把不该拦的全拦了。
CREATE TABLE epr_country_regime (
  id            uuid PRIMARY KEY,
  country_code  char(2) NOT NULL,
  regime_code   varchar(32) NOT NULL,
  country_name  varchar(100) NOT NULL,
  authority     varchar(200) NOT NULL DEFAULT '',
  position      int NOT NULL DEFAULT 0,
  UNIQUE(country_code, regime_code),
  CHECK(length(btrim(country_code))=2),
  CHECK(btrim(country_name)<>'')
);
COMMENT ON TABLE epr_country_regime IS
  '16.3.2.2 · 哪些国家要求哪些 EPR 制度（全局码表，不带 tenant_id）。'
  '这是「NOT_APPLICABLE（该国不要求该制度）」与「NOT_REGISTERED（要求了但没注册）」的分界线。';

CREATE INDEX epr_country_regime_country ON epr_country_regime(country_code, position);
CREATE INDEX epr_country_regime_regime  ON epr_country_regime(regime_code, position);

-- ---------------------------------------------------------------------------
-- 码表内容：只列**确知**的 EPR 制度要求（欧盟成员国 + 英国）。
-- 🔴 **本表推不出的不列**：没列出来的 (国家, 制度) 一律是 NOT_APPLICABLE —— 放行。
--   这是刻意选的保守方向：**宁可放过，不可把不该拦的全拦了**。
-- ---------------------------------------------------------------------------
INSERT INTO epr_country_regime (id,country_code,regime_code,country_name,authority,position)
SELECT (substr(md5('eprcr:'||v.cc||':'||v.regime),1,8)||'-'||substr(md5('eprcr:'||v.cc||':'||v.regime),9,4)||'-4'
        ||substr(md5('eprcr:'||v.cc||':'||v.regime),14,3)||'-a'||substr(md5('eprcr:'||v.cc||':'||v.regime),18,3)||'-'
        ||substr(md5('eprcr:'||v.cc||':'||v.regime),21,12))::uuid,
       v.cc::char(2), v.regime::varchar(32), v.ccname::varchar(100), v.auth::varchar(200), v.pos::int
FROM (VALUES
 -- ---- 德国：包装法（LUCID）+ 电子废弃物（ElektroG）+ 电池法（BattG）----
 ('DE','LUCID','德国','Zentrale Stelle Verpackungsregister (ZSVR)',10),
 ('DE','WEEE','德国','ElektroG 登记机构 EAR',20),
 ('DE','BATTERY','德国','Batteriegesetz 登记机构 Stiftung EAR',30),
 -- ---- 法国：包装注册（唯一取码表里认得的制度；其余制度本表**不臆测**）----
 ('FR','PACKAGING_REGISTRATION','法国','ADEME / SYDEREP',40),
 -- ---- 意大利：包装注册（CONAI / 环境标签体系）----
 ('IT','PACKAGING_REGISTRATION','意大利','CONAI / 环境标签体系',50),
 -- ---- 西班牙：包装注册（生产者延伸责任登记）----
 ('ES','PACKAGING_REGISTRATION','西班牙','包装生产者延伸责任登记',60),
 -- ---- 荷兰：包装登记（Verpakkingenregister）----
 ('NL','PACKAGING_REGISTRATION','荷兰','Verpakkingenregister（Raad voor Milieubeheer）',70),
 -- ---- 奥地利：包装注册（Verpackungskoordinierungsstelle）----
 ('AT','PACKAGING_REGISTRATION','奥地利','Verpackungskoordinierungsstelle',80),
 -- ---- 波兰：包装注册（BDO）----
 ('PL','PACKAGING_REGISTRATION','波兰','BDO（Baza Danych Ochrony Środowiska）',90),
 -- ---- 英国：包装注册（环境机构包装登记）----
 ('GB','PACKAGING_REGISTRATION','英国','英国环境机构包装登记（Packaging register）',100)
) AS v(cc,regime,ccname,auth,pos);

-- ===========================================================================
-- 四、epr_country_regime_category · 该国该制度适用哪些品类（全局码表）
-- ===========================================================================
-- 🔴 「这个国家这个品类有没有覆盖」的反查依赖这张表：
--   只有当某制度在该国**适用某个品类**时，它才构成该 (国家, 品类) 的登记义务。
CREATE TABLE epr_country_regime_category (
  country_regime_id uuid NOT NULL,
  category_code     varchar(32) NOT NULL,
  PRIMARY KEY(country_regime_id, category_code),
  FOREIGN KEY(country_regime_id) REFERENCES epr_country_regime(id) ON DELETE CASCADE
);
COMMENT ON TABLE epr_country_regime_category IS
  '16.3.2.2 · 某国某项 EPR 制度适用哪些品类（全局码表）。'
  '它是「这个国家这个品类有没有覆盖」反查链的第一段。';

INSERT INTO epr_country_regime_category (country_regime_id, category_code)
SELECT cr.id, v.cat::varchar(32)
FROM (VALUES
 ('DE','LUCID','PACKAGING'),
 ('DE','WEEE','ELECTRONICS'),
 ('DE','BATTERY','BATTERIES'),
 ('FR','PACKAGING_REGISTRATION','PACKAGING'),
 ('IT','PACKAGING_REGISTRATION','PACKAGING'),
 ('ES','PACKAGING_REGISTRATION','PACKAGING'),
 ('NL','PACKAGING_REGISTRATION','PACKAGING'),
 ('AT','PACKAGING_REGISTRATION','PACKAGING'),
 ('PL','PACKAGING_REGISTRATION','PACKAGING'),
 ('GB','PACKAGING_REGISTRATION','PACKAGING')
) AS v(cc,regime,cat)
JOIN epr_country_regime cr ON cr.country_code = v.cc AND cr.regime_code = v.regime;

-- ===========================================================================
-- 五、epr_registration · EPR 制度级注册档案（租户业务事实，**版本化**）
-- ===========================================================================
-- 🔴 **版本化**：一行的生效事实改�� ⇒ **不改这一行**，而是
--   「把当前版本关掉（effective_to = 生效时刻）+ 插入新版本」。
--   于是「历史判定按当时那版算」是**物理成立**的，不是靠应用层自觉。
--   🔴 **不许回填**：新版本的 effective_from 必须 ≥ 生效时刻（服务层 + 触发器两道）。
--
-- 🔴 粒度：**制度 × 适用国家 × 适用品类**（见 epr_registration_country / _category）。
--   同一个国家下 LUCID / 包装注册 / 电池法 / WEEE 各有自己的注册号，
--   **不共用**上游 16.3.1.1 那个辖区级的 epr_registration_no。
CREATE TABLE epr_registration (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  -- 🔴 逻辑登记 ID：同一份登记的各个生效版本共享它（费用挂在它上面，跨版本稳定）
  registration_key uuid NOT NULL,
  -- 版本号（同一逻辑登记内递增）
  version_no       int NOT NULL CHECK(version_no >= 1),
  -- 上一版本行 ID（版本链）
  supersedes_id    uuid,
  -- 🔴 制度码：必须真实落在 epr_regime 码表里（触发器钉死，不留自由文本）
  regime_code      varchar(32) NOT NULL,
  -- 制度级注册号（德国 LUCID 号 / 包装注册号 / 电池登记号 / WEEE 登记号）
  registration_no  varchar(64) NOT NULL,
  -- 启用 / 停用（停用 = 这条登记不再作为覆盖依据）
  status           varchar(16) NOT NULL DEFAULT 'ACTIVE'
                   CHECK(status IN ('ACTIVE','DISABLED')),
  -- 🔴 有效期形态（三态，第三态是「判不了」）：
  --   FIXED      —— 有效期到某一天（valid_from + valid_until 都有）
  --   OPEN_ENDED —— 长期有效 / 未设失效日（valid_from 有，valid_until 无）
  --   UNKNOWN    —— **没记有效期** ⇒ 判不了有没有过期 ⇒ **不许默认放行**
  validity_kind    varchar(16) NOT NULL DEFAULT 'UNKNOWN'
                   CHECK(validity_kind IN ('FIXED','OPEN_ENDED','UNKNOWN')),
  valid_from       timestamptz,
  valid_until      timestamptz,
  -- 申报周期（受控枚举：月度 / 季度 / 年度 / 一次性）
  filing_cycle     varchar(16) NOT NULL
                   CHECK(filing_cycle IN ('MONTHLY','QUARTERLY','ANNUAL','ONE_TIME')),
  -- 申报期的起止月份（1..12）。周期 + 起止月份 ⇒ 能算出「当前是否在申报期内」。
  filing_start_month int CHECK(filing_start_month IS NULL OR filing_start_month BETWEEN 1 AND 12),
  filing_end_month   int CHECK(filing_end_month   IS NULL OR filing_end_month   BETWEEN 1 AND 12),
  -- 🔴 生效窗口（版本化核心）。effective_from NOT NULL；effective_to 为空 = 当前版本。
  effective_from   timestamptz NOT NULL,
  effective_to     timestamptz,
  note             varchar(500) NOT NULL DEFAULT '',
  recorded_by      uuid,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, registration_key, version_no),
  CHECK(btrim(registration_no)<>''),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- 🔴 有效期区间必须自洽
  CHECK(valid_until IS NULL OR valid_from IS NULL OR valid_until >= valid_from),
  -- 🔴 有效期形态与两列必须自洽（CHECK 挡不住「形态说 FIXED 却没有失效日」）
  CHECK(
    (validity_kind = 'FIXED'      AND valid_from IS NOT NULL AND valid_until IS NOT NULL)
    OR (validity_kind = 'OPEN_ENDED' AND valid_from IS NOT NULL AND valid_until IS NULL)
    OR (validity_kind = 'UNKNOWN'   AND valid_until IS NULL)
  ),
  -- 🔴 申报期起止月份要么都不记、要么都记（不能只记一半 ⇒ 判不了申报期）
  CHECK((filing_start_month IS NULL) = (filing_end_month IS NULL))
);
COMMENT ON TABLE epr_registration IS
  '16.3.2.2 · EPR 制度级注册档案（制度 × 适用国家 × 适用品类），**版本化**。'
  '改一条登记 = 关掉当前版本 + 插入新版本（effective_from/effective_to 划窗口），'
  '所以「历史判定按当时那版算」物理成立。'
  '本表的 regime_code 是**制度级**的号，与 16.3.1.1 辖区级 epr_registration_no 是粗/细两层，'
  '**不是同一份事实的两个副本**。';

-- 🔴 一个逻辑登记**同时只能有一个当前版本**（effective_to 为空的那条只能有一条）
CREATE UNIQUE INDEX epr_registration_current_version
  ON epr_registration(tenant_id, registration_key) WHERE effective_to IS NULL;

CREATE INDEX epr_registration_regime
  ON epr_registration(tenant_id, regime_code, effective_from DESC);
CREATE INDEX epr_registration_expiry
  ON epr_registration(tenant_id, valid_until)
  WHERE validity_kind = 'FIXED' AND effective_to IS NULL;

-- ===========================================================================
-- 六、epr_registration_country · 登记的适用国家（版本行的子表）
-- ===========================================================================
-- 🔴 **多对多**：一条登记适用多个国家（一个 LUCID 号可以覆盖多个国家的包装义务，
--   也可以一份登记只覆盖一个国家）。
CREATE TABLE epr_registration_country (
  tenant_id     uuid NOT NULL,
  registration_id uuid NOT NULL,
  country_code  char(2) NOT NULL,
  PRIMARY KEY(tenant_id, registration_id, country_code),
  CHECK(length(btrim(country_code))=2)
);
COMMENT ON TABLE epr_registration_country IS
  '16.3.2.2 · EPR 登记的适用国家（版本行的子表）。版本换了，子行跟着换 ⇒ 历史判定按当时那版算。';

CREATE INDEX epr_registration_country_code
  ON epr_registration_country(tenant_id, country_code);

-- ===========================================================================
-- 七、epr_registration_category · 登记的适用品类（版本行的子表）
-- ===========================================================================
-- 🔴 **多对多**：一条登记适用多个品类。
CREATE TABLE epr_registration_category (
  tenant_id       uuid NOT NULL,
  registration_id uuid NOT NULL,
  category_code   varchar(32) NOT NULL,
  PRIMARY KEY(tenant_id, registration_id, category_code)
);
COMMENT ON TABLE epr_registration_category IS
  '16.3.2.2 · EPR 登记的适用品类（版本行的子表）。'
  '「这个国家这个品类有没有覆盖」的反查链：epr_country_regime_category → 本表 → epr_registration。';

CREATE INDEX epr_registration_category_code
  ON epr_registration_category(tenant_id, category_code);

-- ===========================================================================
-- 八、epr_exemption · 租户级的「本制度不适用」声明
-- ===========================================================================
-- 🔴 **为什么需要它**：制度是「国家维度」的义务，而「这条义务适不适用我这家店」是**业务事实**。
--   一个只卖服装的卖家在德国不需要 WEEE 登记 —— 但系统在刊登入口**看不到**他卖什么
--   （平台类目路径无法可靠地映射到 EPR 品类）。没有这张表，国��级闸门会把这类卖家全拦了。
--   ⇒ 租户显式声明「该国该制度不适用我的业务」+ 理由 + 生效时间，闸门才放行。
--
-- 🔴 声明**不是事实**（事实是「有没有登记」），所以它带 status 与生效窗口：
--   撤销 = 关掉当前窗口（effective_to = 撤销时刻），历史保留，不做物理删除。
CREATE TABLE epr_exemption (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  country_code   char(2) NOT NULL,
  regime_code    varchar(32) NOT NULL,
  status         varchar(16) NOT NULL DEFAULT 'ACTIVE'
                 CHECK(status IN ('ACTIVE','REVOKED')),
  effective_from timestamptz NOT NULL,
  effective_to   timestamptz,
  -- 理由（**必填**：没有理由的「不适用」就是漏登记的遮羞布）
  reason         varchar(500) NOT NULL,
  note           varchar(500) NOT NULL DEFAULT '',
  recorded_by    uuid,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(length(btrim(country_code))=2),
  CHECK(btrim(reason)<>''),
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);
COMMENT ON TABLE epr_exemption IS
  '16.3.2.2 · 租户级的「本制度在本国不适用我的业务」声明。'
  '理由必填；撤销只关窗口不删行。';

CREATE INDEX epr_exemption_scope
  ON epr_exemption(tenant_id, country_code, regime_code, effective_from DESC);

-- ===========================================================================
-- 九、epr_fee · EPR 关联费用记录（租户业务事实）
-- ===========================================================================
-- 🔴 **本表的费用是「本服务自己的费用记录」**（谁付的、哪个周期、多少钱、什么币种），
--   **不是**财务凭证。12 财务服务在本仓**尚未落地** ⇒
--   **不建财务凭证表、不声称费用已入账**（能力声明里 available=false + ownerModule）。
--
-- 🔴 金额 `numeric(19,4)` + 币种 `char(3)`，**禁浮点**；
--   汇总时**按币种分组**，**不许把不同币种直接相加**。
CREATE TABLE epr_fee (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  -- 挂在哪份登记上（**逻辑登记 ID**，跨版本稳定 —— 换版本不会把历史费用弄丢）
  registration_key uuid NOT NULL,
  -- 费用类型
  fee_type        varchar(32) NOT NULL
                  CHECK(fee_type IN ('REGISTRATION_FEE','FILING_FEE','PENALTY','OTHER')),
  -- 🔴 金额：numeric，不是浮点
  amount          numeric(19,4) NOT NULL CHECK(amount >= 0),
  -- 🔴 币种（**必带**；不同币种不许直接相加）
  currency        char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 计费周期
  billing_cycle   varchar(16) NOT NULL
                  CHECK(billing_cycle IN ('MONTHLY','QUARTERLY','ANNUAL','ONE_TIME')),
  -- 归属周期区间
  period_start    timestamptz,
  period_end      timestamptz,
  -- 记录状态
  status          varchar(16) NOT NULL DEFAULT 'RECORDED'
                  CHECK(status IN ('RECORDED','PAID','WAIVED')),
  -- 谁付的
  paid_by         varchar(32) NOT NULL DEFAULT 'SELLER'
                  CHECK(paid_by IN ('SELLER','PLATFORM','SERVICE_PROVIDER')),
  note            varchar(500) NOT NULL DEFAULT '',
  recorded_by     uuid,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(period_end IS NULL OR period_start IS NULL OR period_end >= period_start)
);
COMMENT ON TABLE epr_fee IS
  '16.3.2.2 · EPR 关联费用记录。金额 numeric + 币种必带；汇总按币种分组。'
  '这是**本服务自己的费用记录**，**不是**财务凭证（12 财务在本仓尚未落地）。';

CREATE INDEX epr_fee_registration ON epr_fee(tenant_id, registration_key, period_start DESC);
CREATE INDEX epr_fee_currency     ON epr_fee(tenant_id, currency, created_at DESC);

-- ===========================================================================
-- 十、触发器
-- ===========================================================================

-- 🔴 (a) 制度码必须真实存在（**不留自由文本**的物理落点）
CREATE FUNCTION guard_epr_regime_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM epr_regime r WHERE r.code = NEW.regime_code) THEN
    RAISE EXCEPTION 'EPR regime % is not a known regime code', NEW.regime_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER epr_registration_regime_ref
  BEFORE INSERT OR UPDATE ON epr_registration
  FOR EACH ROW EXECUTE FUNCTION guard_epr_regime_ref();
CREATE TRIGGER epr_exemption_regime_ref
  BEFORE INSERT OR UPDATE ON epr_exemption
  FOR EACH ROW EXECUTE FUNCTION guard_epr_regime_ref();

-- 🔴 (b) 适用品类码必须真实存在
CREATE FUNCTION guard_epr_category_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM epr_category c WHERE c.code = NEW.category_code) THEN
    RAISE EXCEPTION 'EPR category % is not a known category code', NEW.category_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER epr_registration_category_ref
  BEFORE INSERT OR UPDATE ON epr_registration_category
  FOR EACH ROW EXECUTE FUNCTION guard_epr_category_ref();

-- 🔴 (c) 🔴 **版本不可回改**：一行的登记事实一旦写下就**不许再改**
--     （只允许关闭当前版本时写 effective_to / updated_at）。
--     「改了就是新的生效版本」这条要求在这里物理成立：
--     想改注册号 / 有效期 / 申报周期 ⇒ 必须插入新版本行。
CREATE FUNCTION guard_epr_registration_version_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.effective_to IS NOT NULL THEN
    -- 已关闭的版本连窗口都不许再动
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       OR NEW.registration_no IS DISTINCT FROM OLD.registration_no
       OR NEW.regime_code IS DISTINCT FROM OLD.regime_code
       OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
       OR NEW.status IS DISTINCT FROM OLD.status
       OR NEW.validity_kind IS DISTINCT FROM OLD.validity_kind
       OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
       OR NEW.filing_cycle IS DISTINCT FROM OLD.filing_cycle
       OR NEW.filing_start_month IS DISTINCT FROM OLD.filing_start_month
       OR NEW.filing_end_month IS DISTINCT FROM OLD.filing_end_month
       OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
      RAISE EXCEPTION
        'EPR registration version % is closed; its facts cannot be rewritten (insert a new version instead)',
        OLD.id USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;
  -- 当前版本：只许关窗口，不许改事实
  IF NEW.registration_key IS DISTINCT FROM OLD.registration_key
     OR NEW.version_no IS DISTINCT FROM OLD.version_no
     OR NEW.registration_no IS DISTINCT FROM OLD.registration_no
     OR NEW.regime_code IS DISTINCT FROM OLD.regime_code
     OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
     OR NEW.status IS DISTINCT FROM OLD.status
     OR NEW.validity_kind IS DISTINCT FROM OLD.validity_kind
     OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
     OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
     OR NEW.filing_cycle IS DISTINCT FROM OLD.filing_cycle
     OR NEW.filing_start_month IS DISTINCT FROM OLD.filing_start_month
     OR NEW.filing_end_month IS DISTINCT FROM OLD.filing_end_month
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
    RAISE EXCEPTION
      'EPR registration % facts are immutable while it is the current version (close it and insert a new version instead)',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER epr_registration_version_immutable
  BEFORE UPDATE ON epr_registration
  FOR EACH ROW EXECUTE FUNCTION guard_epr_registration_version_immutable();

-- 🔴 (d) 🔴 **不许回填**：新版本的生效时刻不得早于当前时刻。
CREATE FUNCTION guard_epr_registration_no_backdating() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.effective_from < now() - interval '1 minute' THEN
    RAISE EXCEPTION
      'EPR registration effective_from % is in the past; backdating is not allowed', NEW.effective_from
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER epr_registration_no_backdating
  BEFORE INSERT ON epr_registration
  FOR EACH ROW EXECUTE FUNCTION guard_epr_registration_no_backdating();

-- 🔴 (e) 费用必须挂在真实存在的登记上（**零外键**的跨表引用校验）
CREATE FUNCTION guard_epr_fee_registration_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM epr_registration r
        WHERE r.tenant_id = NEW.tenant_id AND r.registration_key = NEW.registration_key) THEN
    RAISE EXCEPTION
      'EPR fee references a registration that does not exist in this tenant: %', NEW.registration_key
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER epr_fee_registration_ref
  BEFORE INSERT OR UPDATE ON epr_fee
  FOR EACH ROW EXECUTE FUNCTION guard_epr_fee_registration_ref();

-- 🔴 (f) 申报期起止月份必须成对（登记行上的 CHECK 已钉；跨年申报期 11..2 允许）
CREATE FUNCTION guard_epr_filing_months() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.filing_start_month IS NOT NULL AND NEW.filing_end_month IS NOT NULL
     AND NEW.filing_start_month = NEW.filing_end_month
     AND NEW.filing_cycle <> 'MONTHLY' THEN
    RAISE EXCEPTION
      'EPR filing start month and end month are identical (%); use a single month for MONTHLY cycles',
      NEW.filing_start_month USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER epr_registration_filing_months
  BEFORE INSERT OR UPDATE ON epr_registration
  FOR EACH ROW EXECUTE FUNCTION guard_epr_filing_months();

-- ===========================================================================
-- 十一、🔴 唯一的判定源：epr_gate_findings(tenant, country, category)
-- ===========================================================================
-- 服务层的 `publish-gate` 端点与 DB 层的刊登触发器**调同一个函数** ⇒
-- 「页面上的结论」与「真正拦人的那道闸门」不可能各说各话。
--
-- 返回：每个「该国要求的制度」一条判定。reason_code 取值：
--   OK                    覆盖且有效
--   NOT_REGISTERED        该国要求该制度，但既没有登记、也没有「不适用」声明
--   EXEMPTED              租户显式声明该制度在本国不适用其业务（NOT_APPLICABLE）
--   EXPIRED               有登记，但有效期已过
--   VALIDITY_UNKNOWN      有登记，但**没记有效期** ⇒ 判不了 ⇒ 不许默认放行
--   NOT_EFFECTIVE_YET     有登记，但当前没有生效版本（尚未生效 / 版本窗口空档）
--   DISABLED              有登记，但该登记已被停用
--   CATEGORY_NOT_COVERED  有登记，但它的适用品类不覆盖该制度在本国适用的品类
--
-- p_category 为空 ⇒ 按国家维度判（刊登闸门用的就是这个口径）；
-- p_category 非空 ⇒ 只判该国**适用于这个品类**的制度。
CREATE FUNCTION epr_gate_findings(
  p_tenant   uuid,
  p_country  varchar,
  p_category varchar DEFAULT NULL
) RETURNS TABLE (
  regime_code   varchar(32),
  regime_name   varchar(100),
  country_code  varchar(2),
  country_name  varchar(100),
  category_code varchar(32),
  reason_code   varchar(32),
  detail        text
) LANGUAGE plpgsql STABLE AS $$
DECLARE
  cr        record;
  reg       record;
  cats      text[];
  reg_total int;
  found     int;
BEGIN
  IF p_country IS NULL OR btrim(p_country) = '' THEN
    RETURN;
  END IF;

  FOR cr IN
    SELECT r.code, r.name, c.id AS crid, c.country_code, c.country_name
      FROM epr_country_regime c
      JOIN epr_regime r ON r.code = c.regime_code
     WHERE c.country_code = p_country
       AND (p_category IS NULL OR EXISTS (
              SELECT 1 FROM epr_country_regime_category cc
               WHERE cc.country_regime_id = c.id AND cc.category_code = p_category))
  LOOP
    -- 该制度在本国适用的品类（按 p_category 过滤后）
    SELECT array_agg(cc.category_code ORDER BY cc.category_code) INTO cats
      FROM epr_country_regime_category cc
     WHERE cc.country_regime_id = cr.crid
       AND (p_category IS NULL OR cc.category_code = p_category);

    -- 🔴 该制度在本国**没有任何适用品类** ⇒ 不构成任何 (国家, 品类) 的登记义务
    --   （参考数据不完整时不该凭空生成一条阻断）。
    IF cats IS NULL OR cardinality(cats) = 0 THEN
      CONTINUE;
    END IF;

    -- 🔴 租户显式声明「该制度在本国不适用我的业务」⇒ NOT_APPLICABLE，放行
    IF EXISTS (
         SELECT 1 FROM epr_exemption x
          WHERE x.tenant_id = p_tenant
            AND x.country_code = cr.country_code
            AND x.regime_code = cr.code
            AND x.status = 'ACTIVE'
            AND x.effective_from <= now()
            AND (x.effective_to IS NULL OR x.effective_to > now())) THEN
      regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
      country_name := cr.country_name;
      category_code := COALESCE(p_category, '');
      reason_code := 'EXEMPTED';
      detail := '租户已声明「' || cr.name || '」在本国不适用其业务（有理由、有生效时间）⇒ 不构成刊登阻断。';
      RETURN NEXT;
      CONTINUE;
    END IF;

    -- 这个租户在这个制度下**一条登记都没有** ⇒ 未注册
    SELECT count(*) INTO reg_total
      FROM epr_registration r
     WHERE r.tenant_id = p_tenant AND r.regime_code = cr.code;
    IF reg_total = 0 THEN
      regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
      country_name := cr.country_name; category_code := COALESCE(p_category, '');
      reason_code := 'NOT_REGISTERED';
      detail := '该国要求「' || cr.name || '」登记，但本租户**没有任何一条该制度的登记** —— '
                || '在相关国家销售会被下架并面临罚款。';
      RETURN NEXT;
      CONTINUE;
    END IF;

    -- 有登记，但**没有覆盖这个国家**的
    SELECT count(*) INTO found
      FROM epr_registration r
      JOIN epr_registration_country rc
        ON rc.registration_id = r.id AND rc.tenant_id = r.tenant_id
     WHERE r.tenant_id = p_tenant AND r.regime_code = cr.code
       AND rc.country_code = cr.country_code;
    IF found = 0 THEN
      regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
      country_name := cr.country_name; category_code := COALESCE(p_category, '');
      reason_code := 'NOT_REGISTERED';
      detail := '本租户登记了「' || cr.name || '」，但**没有一条覆盖 ' || cr.country_name
                || '** —— 该国仍然要求这条登记。';
      RETURN NEXT;
      CONTINUE;
    END IF;

    -- 当前生效的版本（effective_from <= now() < effective_to 或 effective_to 为空）
    SELECT r.id, r.status, r.validity_kind, r.valid_from, r.valid_until
      INTO reg
      FROM epr_registration r
      JOIN epr_registration_country rc
        ON rc.registration_id = r.id AND rc.tenant_id = r.tenant_id
     WHERE r.tenant_id = p_tenant AND r.regime_code = cr.code
       AND rc.country_code = cr.country_code
       AND r.effective_from <= now()
       AND (r.effective_to IS NULL OR r.effective_to > now())
     ORDER BY r.effective_from DESC, r.version_no DESC
     LIMIT 1;

    IF reg.id IS NULL THEN
      regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
      country_name := cr.country_name; category_code := COALESCE(p_category, '');
      reason_code := 'NOT_EFFECTIVE_YET';
      detail := '有「' || cr.name || '」的登记，但**当前没有生效版本**（尚未生效，或版本之间有空档）。';
      RETURN NEXT;
      CONTINUE;
    END IF;

    IF reg.status <> 'ACTIVE' THEN
      regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
      country_name := cr.country_name; category_code := COALESCE(p_category, '');
      reason_code := 'DISABLED';
      detail := '「' || cr.name || '」的登记已被**停用** —— 停用的登记不作为覆盖依据。';
      RETURN NEXT;
      CONTINUE;
    END IF;

    -- 适用品类必须覆盖该制度在本国适用的品类
    IF NOT EXISTS (
         SELECT 1 FROM epr_registration_category rg
          WHERE rg.tenant_id = p_tenant AND rg.registration_id = reg.id
            AND rg.category_code = ANY(cats)) THEN
      regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
      country_name := cr.country_name; category_code := COALESCE(p_category, '');
      reason_code := 'CATEGORY_NOT_COVERED';
      detail := '「' || cr.name || '」的登记**不适用品类** ' || array_to_string(cats, '、')
                || ' —— 该制度在本国适用的品类没被覆盖。';
      RETURN NEXT;
      CONTINUE;
    END IF;

    -- 有效期判定（三态，第三态是「判不了」）
    IF reg.validity_kind = 'UNKNOWN' THEN
      regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
      country_name := cr.country_name; category_code := COALESCE(p_category, '');
      reason_code := 'VALIDITY_UNKNOWN';
      detail := '「' || cr.name || '」的登记**没记有效期** ⇒ 判不了有没有过期 ⇒ 不许默认放行。';
      RETURN NEXT;
      CONTINUE;
    END IF;

    IF reg.validity_kind = 'FIXED' AND reg.valid_until < now() THEN
      regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
      country_name := cr.country_name; category_code := COALESCE(p_category, '');
      reason_code := 'EXPIRED';
      detail := '「' || cr.name || '」的登记**已过有效期** —— 过期登记和没有登记一样经不起稽查。';
      RETURN NEXT;
      CONTINUE;
    END IF;

    -- OK：覆盖且有效
    regime_code := cr.code; regime_name := cr.name; country_code := cr.country_code;
    country_name := cr.country_name; category_code := COALESCE(p_category, '');
    reason_code := 'OK';
    detail := '「' || cr.name || '」在 ' || cr.country_name || ' 已有覆盖且有效的登记。';
    RETURN NEXT;
  END LOOP;
END $$;
COMMENT ON FUNCTION epr_gate_findings(uuid, varchar, varchar) IS
  '16.3.2.2 · EPR 刊登闸门的**唯一判定源**：服务层 publish-gate 端点与 DB 层刊登触发器调同一个函数。';

-- ===========================================================================
-- 十二、🔴 刊登闸门的 DB 层强制（4 刊登的两个真实放行口）
-- ===========================================================================
-- 为什么落在 `listing_publish_job_target`（4.3.1.1 一键刊登，state → 'SUBMITTED' = 真发给平台）
-- 与 `publish_task`（4.3.1.2 批量发布，state → 'DISPATCHED' = 真派发）：
--   「允许发布」这件事的闸门在**发布入口**上。本迁移**只加触发器、零 ALTER 既有表的任何列**，
--   也不改既有触发器 —— 与 V198 的 `guard_listing_editor_submission_compliance` 同一手法。
--
-- 🔴 **口径**：DB 层按**国家维度**判（p_category 传空）——
--   平台类目路径（category_path）是平台侧原文，**无法可靠地**映射到 EPR 品类，
--   在 DB 层猜一个映射就是编造。品类维度的精确判定在服务层
--   `publish-gate` 端点与 `GET /coverage` 里给出（那里有显式的品类输入）。
--
-- 🔴 **判不了 ⇒ 不许默认放行**：判定函数返回 EXPIRED / VALIDITY_UNKNOWN / NOT_EFFECTIVE_YET /
--   DISABLED / CATEGORY_NOT_COVERED / NOT_REGISTERED 时**一律拒发**（SQLSTATE 23514）。
CREATE FUNCTION guard_epr_publish_gate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  country    varchar;
  bad        record;
  blocker_count int := 0;
  first_reason varchar(32);
  first_regime varchar(32);
  first_detail text;
BEGIN
  -- 🔴 只在「进入发布通道」的那一刻检查
  IF TG_OP = 'INSERT' THEN
    IF NEW.state <> TG_ARGV[0] THEN
      RETURN NEW;
    END IF;
  ELSE
    IF NEW.state IS NOT DISTINCT FROM OLD.state THEN
      RETURN NEW;
    END IF;
    IF NEW.state <> TG_ARGV[0] THEN
      RETURN NEW;
    END IF;
  END IF;

  -- 目标站点 → 国家（listing_publish_job_target 自带 marketplace_id；
  --                 publish_task 经 listing.marketplace_id 拿）
  IF TG_ARGV[1] = 'TARGET' THEN
    SELECT m.country_code INTO country
      FROM marketplace m WHERE m.id = NEW.marketplace_id;
  ELSE
    SELECT m.country_code INTO country
      FROM listing l JOIN marketplace m ON m.id = l.marketplace_id
     WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.listing_id;
  END IF;

  -- 🔴 国家都取不到 ⇒ 没有可判的国家义务 ⇒ 不在这里拦（服务层会报「判不了」）
  IF country IS NULL OR btrim(country) = '' THEN
    RETURN NEW;
  END IF;

  FOR bad IN
    SELECT f.regime_code, f.reason_code, f.detail
      FROM epr_gate_findings(NEW.tenant_id, country, NULL) f
     WHERE f.reason_code <> 'OK'
     ORDER BY f.regime_code
  LOOP
    blocker_count := blocker_count + 1;
    IF blocker_count = 1 THEN
      first_reason := bad.reason_code;
      first_regime := bad.regime_code;
      first_detail := bad.detail;
    END IF;
  END LOOP;

  -- 🔴 判不了（过期 / 没记有效期 / 没有生效版本 / 停用 / 品类没覆盖 / 没注册）
  --   **一律拒发** —— 不许默认放行。
  IF blocker_count > 0 THEN
    RAISE EXCEPTION
      'EPR publish gate blocked: country % regime % % (%); % blocked obligation(s) in total',
      country, first_regime, first_reason, first_detail, blocker_count
      USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER epr_publish_gate_target
  BEFORE INSERT OR UPDATE ON listing_publish_job_target
  FOR EACH ROW EXECUTE FUNCTION guard_epr_publish_gate('SUBMITTED', 'TARGET');

CREATE TRIGGER epr_publish_gate_task
  BEFORE INSERT OR UPDATE ON publish_task
  FOR EACH ROW EXECUTE FUNCTION guard_epr_publish_gate('DISPATCHED', 'TASK');

COMMENT ON FUNCTION guard_epr_publish_gate() IS
  '16.3.2.2 · EPR 刊登闸门的 DB 层强制：拦在 4 刊登的两个真实放行口上'
  '(listing_publish_job_target → SUBMITTED、publish_task → DISPATCHED)。'
  '零 ALTER 既有表；判定与服务层 publish-gate 端点共用 epr_gate_findings。';

-- ===========================================================================
-- 十三、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['epr_registration','epr_registration_country',
                             'epr_registration_category','epr_exemption','epr_fee'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 🔴 `epr_regime` / `epr_category` / `epr_country_regime` / `epr_country_regime_category`
--   是**全局码表**（与 platform / marketplace / tax_registration_jurisdiction 同口径），
--   不带 tenant_id、**不参与**租户隔离。

-- ===========================================================================
-- 十四、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO channel_app;
-- 全局码表：只读
GRANT SELECT ON epr_regime TO channel_app;
GRANT SELECT ON epr_category TO channel_app;
GRANT SELECT ON epr_country_regime TO channel_app;
GRANT SELECT ON epr_country_regime_category TO channel_app;
-- 租户业务事实：可读写
GRANT SELECT,INSERT,UPDATE,DELETE ON epr_registration TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON epr_registration_country TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON epr_registration_category TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON epr_exemption TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON epr_fee TO channel_app;
-- 判定函数：服务层与触发器都要能调
GRANT EXECUTE ON FUNCTION epr_gate_findings(uuid, varchar, varchar) TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V312 同口径）。
REVOKE ALL ON epr_registration FROM starcon_app;
REVOKE ALL ON epr_registration_country FROM starcon_app;
REVOKE ALL ON epr_registration_category FROM starcon_app;
REVOKE ALL ON epr_exemption FROM starcon_app;
REVOKE ALL ON epr_fee FROM starcon_app;
REVOKE ALL ON epr_regime FROM starcon_app;
REVOKE ALL ON epr_category FROM starcon_app;
REVOKE ALL ON epr_country_regime FROM starcon_app;
REVOKE ALL ON epr_country_regime_category FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- 🔴 **序列授权**：本迁移的九张表**全部用应用侧生成的 uuid 作主键**，
--   没有任何 serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— EPR 合规台账语义与 16.1.1.1 账号健康、
-- 16.2.2.1 合规预检、16.3.1.1 税务登记同族，复用 identity 已注册的
-- channel.operation.*（读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 十五、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **16.3.1.1 税号与辖区（上游，同库只读）**：本模块**不写** `tax_registration_record`
--     （上游写所有权不变），也**不把**上游那个辖区级 `epr_registration_no` 复制一份当自己的
--     权威源。两者是「粗 / 细」两层：上游一个辖区一个号，本模块一个**制度 × 国家 × 品类**
--     一个号。能力声明里写清这层关系。
--   * **16.3.2.1 VAT/IOSS**：未交付 ⇒ 本模块**不编造** IOSS 订单清单与代收代缴统计。
--   * **16.3.2.3 销售税**：未交付 ⇒ 本模块不碰销售税。
--   * **12 财务（下游）**：finance 服务在本仓**尚未落地** ⇒ 本模块**不建财务凭证表**、
--     **不声称费用已入账**；`epr_fee` 只是**本服务自己的费用记录**。
--   * **16.3.1.2 申报日历**：未交付 ⇒ 本模块**只算**「当前是否在申报期内」，
--     不做申报日历、不做截止日提醒、不做提交状态跟踪。
--   * **03 商品类目**：EPR 品类是**另一套维度**（`epr_category`），不是平台类目路径。
--     刊登入口的平台类目路径无法可靠映射到 EPR 品类 ⇒ DB 层按国家维度拦，
--     品类维度的精确判定在服务层给出（那里有显式的品类输入）。
-- ---------------------------------------------------------------------------
