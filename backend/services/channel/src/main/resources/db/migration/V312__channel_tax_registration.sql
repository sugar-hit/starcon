-- ===========================================================================
-- 16.3.1.1 税号与辖区 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 140-145 行）：
--   ### 16.3.1.1 税号与辖区
--   - 职责：维护各店铺/法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、
--           注册有效期与申报周期。
--   - 输入：税号录入；辖区选择；有效期。
--   - 输出：税务登记档案 + 缺失项提示。
--   - 关系：上游 = 17 法人、2 店铺；下游 = 16.3.2.1、12.6 税务。
--           **未注册辖区的销售额是合规炸弹**：欧洲没有 VAT 号还在欧盟销售，是稽查重点。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块存在的理由：**缺失项提示必须真的算出来**
-- ---------------------------------------------------------------------------
-- 蓝图输出句里的「缺失项提示」是加粗句：欧洲没有 VAT 号还在欧盟销售，是稽查重点。
-- 因此本迁移必须让「未注册辖区 / 缺 VAT 号 / 缺 EPR 注册号 / 登记已过有效期」
-- **成为可查的数据库事实**，而不是前端的一句提醒文案。
--
-- 缺失项的推导链（三段都是本仓**真实存在**的上游）：
--   shop_marketplace（店铺启用了哪些站点）
--     → marketplace.country_code + marketplace.vat_required（站点在哪个国家、该站点是否要求 VAT）
--       → tax_registration_jurisdiction（该国家是不是一个「国家级税务辖区」）
--         → tax_registration_record（这家店 / 这个法人在那个辖区**有没有**登记）
--
-- 🔴 **推导不出要求时不等于「没缺口」**：派生口径写在 `derivation_scope` 列上，
--   美国这类「登记在州一级」的辖区 `derivation_scope='SUBNATIONAL'` ⇒ 系统**推不出**
--   该登记哪些州 ⇒ 不产���缺口。这条「推不出」在能力声明里如实写明，**不拿空集合冒充「都齐了」**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 表命名空间（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.3.1.1）⇒ `tax_registration_*`。
--   同卷兄弟 16.3.1.2 申报日历 / 16.3.2.1 VAT-IOSS / 16.3.2.2 EPR 都**还没开工**，
--   将来它们要用别的表名前缀；本迁移**不预留**它们的名字。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 辖区值域必须钉死（不能留自由文本）
-- ---------------------------------------------------------------------------
-- `tax_registration_jurisdiction` 是**全局码表**（不带 tenant_id，与 V1 的 platform /
-- marketplace 同一口径）：辖区是业务事实，不是租户数据。
-- 登记表上的 `jurisdiction_code` 由**触发器**校验必须真实存在于这张码表里 ——
-- 只用 CHECK 是钉不住的（CHECK 看不到别的表），所以用触发器，
-- 这是「不要留自由文本」这条要求的物理落点。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 零外键（跨表引用一律用触发器）
-- ---------------------------------------------------------------------------
-- 与同卷 V300 account_health / V308 platform_violation 同一纪律：外键会打破同服务既有
-- 测试的 TRUNCATE。跨表引用校验改成 BEFORE 触发器：
--   * 登记的 shop 主体必须在本租户的 `shop` 目录里真实存在；
--   * 登记的辖区码必须真实存在；
--   * 法人主体是**跨服务的逻辑 ID**（identity 拥有），本库**不建 FK、不复制对方任何一列**。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V197 / V248 / V300 / V308 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **数据范围（RESTRICTIVE）只套在「店铺主体」的行上**：
--   * `subject_type='SHOP'` 的登记是**店铺维度**的资产 ⇒ 随店铺负责人 / 部门收敛
--     （与 V300 account_health_metric 同构）。
--   * `subject_type='LEGAL_ENTITY'` 的登记是**法人维度**的资产，而法人的数据范围
--     归 identity（17）所有、本服务**读不到**（跨服务只读端口只拿编码与名称）。
--     硬套店铺收敛会让法人登记在「没有绑定店铺」的法人上凭空消失 ——
--     而「这个法人在德国到底注册了没有」恰恰是合规负责人最需要看见的一条。
--     所以法人主体行**只带 isolation 策略**。
-- ===========================================================================

-- ===========================================================================
-- 一、tax_registration_jurisdiction · 销售辖区码表（全局码表，不带 tenant_id）
-- ===========================================================================
-- 🔴 这张表是**业务事实的码表**，发布时随代码更新，不随租户变化。
--   * `tax_regime`            税制：VAT / GST / 销售税 / 消费税 / 无
--   * `vat_required`          该辖区是否要求**登记号**（VAT 号 / 税号）
--   * `requires_epr`          该辖区是否要求 **EPR 注册号**（包装法 / WEEE / 电池法）
--   * `default_filing_cycle`  该辖区的默认申报周期（登记时可留空，留空就是「没记」）
--   * `derivation_scope`       「店铺启用的站点国家」能否推导出该辖区的登记要求：
--       COUNTRY      —— 国家本身就是税务辖区（欧盟成员国、英国）⇒ **能**推导
--       SUBNATIONAL —— 登记在州 / 省一级（美国各州）⇒ **推不出**要求
--       NONE        —— 本模块不跟踪该辖区的登记要求
CREATE TABLE tax_registration_jurisdiction (
  id                 uuid PRIMARY KEY,
  code               varchar(16) NOT NULL UNIQUE,
  name               varchar(100) NOT NULL,
  region             varchar(32) NOT NULL
                     CHECK(region IN ('EUROPEAN_UNION','UNITED_KINGDOM','UNITED_STATES','CANADA',
                                      'AUSTRALIA','JAPAN','MIDDLE_EAST','SOUTHEAST_ASIA',
                                      'LATIN_AMERICA','OTHER')),
  -- ISO 3166-1 alpha-2
  country_code       varchar(2) NOT NULL,
  -- ISO 3166-2 分区码（'US-CA'）；国家级辖区为空串
  subdivision_code   varchar(16) NOT NULL DEFAULT '',
  tax_regime         varchar(20) NOT NULL
                     CHECK(tax_regime IN ('VAT','GST','SALES_TAX','CONSUMPTION_TAX','NONE')),
  vat_required       boolean NOT NULL DEFAULT false,
  requires_epr       boolean NOT NULL DEFAULT false,
  default_filing_cycle varchar(16)
                     CHECK(default_filing_cycle IS NULL OR default_filing_cycle IN
                           ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
  derivation_scope   varchar(16) NOT NULL DEFAULT 'NONE'
                     CHECK(derivation_scope IN ('COUNTRY','SUBNATIONAL','NONE')),
  position           int NOT NULL DEFAULT 0,
  -- 一个国家 + 一个分区码只能有一条
  UNIQUE(country_code, subdivision_code),
  CHECK(btrim(code)<>'' AND btrim(name)<>'' AND length(btrim(country_code))=2)
);
COMMENT ON TABLE tax_registration_jurisdiction IS
  '16.3.1.1 · 销售辖区码表（全局码表，不带 tenant_id）。'
  '辖区是业务事实：登记表的 jurisdiction_code 由触发器钉死必须落在这张表里，不留自由文本。'
  'derivation_scope 说明「店铺启用的站点国家」能不能推导出该辖区的登记要求 —— '
  '推不出就说推不出，不拿空集合冒充「都齐了」。';

-- ---------------------------------------------------------------------------
-- 码表内容：欧盟 27 个成员国 + 英国 + 美国（联邦与 50 州 + DC）+
--           站点目录（V1 的 marketplace）里出现过且 vat_required=true 的国家。
-- ---------------------------------------------------------------------------
-- 🔴 欧盟成员国 + 英国的 `derivation_scope='COUNTRY'`：站点国家落到这里就能推导出要求。
-- 🔴 美国**联邦**一行 `derivation_scope='SUBNATIONAL'`：销售税登记在州一级，
--    系统推不出「你要在哪几个州注册」⇒ 不产出美国联邦的缺口。
--    各州行照样在值域里（卖家确实可以在某州拿到销售税账号并登记）。
-- 🔴 税制不是 VAT 的辖区（美国 / 加拿大 / 澳大利亚 / 新加坡 / 马来西亚 / 日本），
--    `vat_required=false` ⇒ **不要求 VAT 号**，缺号不报缺口（报「未注册」）。
-- 🔴 `requires_epr=true` 只给欧盟成员国与英国：EPR / 包装法是这两地的登记义务，
--    其余辖区的包装法口径本模块**不臆测**。
INSERT INTO tax_registration_jurisdiction
  (id,code,name,region,country_code,subdivision_code,tax_regime,vat_required,requires_epr,
   default_filing_cycle,derivation_scope,position)
SELECT (substr(md5('taxjur:'||v.code),1,8)||'-'||substr(md5('taxjur:'||v.code),9,4)||'-4'
        ||substr(md5('taxjur:'||v.code),14,3)||'-a'||substr(md5('taxjur:'||v.code),18,3)||'-'
        ||substr(md5('taxjur:'||v.code),21,12))::uuid,
       v.code::varchar(16), v.name::varchar(100), v.region::varchar(32),
       v.cc::varchar(2), v.sub::varchar(16), v.regime::varchar(20),
       v.vat::boolean, v.epr::boolean, v.cycle::varchar(16), v.scope::varchar(16), v.pos::int
FROM (VALUES
 -- ---- 欧盟 27 个成员国：税制 VAT、要求 VAT 号、要求 EPR、默认按月申报 ----
 ('EU-AT','奥地利','EUROPEAN_UNION','AT','','VAT',true,true,'MONTHLY','COUNTRY',10),
 ('EU-BE','比利时','EUROPEAN_UNION','BE','','VAT',true,true,'MONTHLY','COUNTRY',11),
 ('EU-BG','保加利亚','EUROPEAN_UNION','BG','','VAT',true,true,'MONTHLY','COUNTRY',12),
 ('EU-HR','克罗地亚','EUROPEAN_UNION','HR','','VAT',true,true,'MONTHLY','COUNTRY',13),
 ('EU-CY','塞浦路斯','EUROPEAN_UNION','CY','','VAT',true,true,'MONTHLY','COUNTRY',14),
 ('EU-CZ','捷克','EUROPEAN_UNION','CZ','','VAT',true,true,'MONTHLY','COUNTRY',15),
 ('EU-DK','丹麦','EUROPEAN_UNION','DK','','VAT',true,true,'MONTHLY','COUNTRY',16),
 ('EU-EE','爱沙尼亚','EUROPEAN_UNION','EE','','VAT',true,true,'MONTHLY','COUNTRY',17),
 ('EU-FI','芬兰','EUROPEAN_UNION','FI','','VAT',true,true,'MONTHLY','COUNTRY',18),
 ('EU-FR','法国','EUROPEAN_UNION','FR','','VAT',true,true,'MONTHLY','COUNTRY',19),
 ('EU-DE','德国','EUROPEAN_UNION','DE','','VAT',true,true,'MONTHLY','COUNTRY',20),
 ('EU-GR','希腊','EUROPEAN_UNION','GR','','VAT',true,true,'MONTHLY','COUNTRY',21),
 ('EU-HU','匈牙利','EUROPEAN_UNION','HU','','VAT',true,true,'MONTHLY','COUNTRY',22),
 ('EU-IE','爱尔兰','EUROPEAN_UNION','IE','','VAT',true,true,'MONTHLY','COUNTRY',23),
 ('EU-IT','意大利','EUROPEAN_UNION','IT','','VAT',true,true,'MONTHLY','COUNTRY',24),
 ('EU-LV','拉脱维亚','EUROPEAN_UNION','LV','','VAT',true,true,'MONTHLY','COUNTRY',25),
 ('EU-LT','立陶宛','EUROPEAN_UNION','LT','','VAT',true,true,'MONTHLY','COUNTRY',26),
 ('EU-LU','卢森堡','EUROPEAN_UNION','LU','','VAT',true,true,'MONTHLY','COUNTRY',27),
 ('EU-MT','马耳他','EUROPEAN_UNION','MT','','VAT',true,true,'MONTHLY','COUNTRY',28),
 ('EU-NL','荷兰','EUROPEAN_UNION','NL','','VAT',true,true,'MONTHLY','COUNTRY',29),
 ('EU-PL','波兰','EUROPEAN_UNION','PL','','VAT',true,true,'MONTHLY','COUNTRY',30),
 ('EU-PT','葡萄牙','EUROPEAN_UNION','PT','','VAT',true,true,'MONTHLY','COUNTRY',31),
 ('EU-RO','罗马尼亚','EUROPEAN_UNION','RO','','VAT',true,true,'MONTHLY','COUNTRY',32),
 ('EU-SK','斯洛伐克','EUROPEAN_UNION','SK','','VAT',true,true,'MONTHLY','COUNTRY',33),
 ('EU-SI','斯洛文尼亚','EUROPEAN_UNION','SI','','VAT',true,true,'MONTHLY','COUNTRY',34),
 ('EU-ES','西班牙','EUROPEAN_UNION','ES','','VAT',true,true,'MONTHLY','COUNTRY',35),
 ('EU-SE','瑞典','EUROPEAN_UNION','SE','','VAT',true,true,'MONTHLY','COUNTRY',36),
 -- ---- 英国：脱欧后 VAT 仍适用，包装法 EPR 仍适用 ----
 ('GB','英国','UNITED_KINGDOM','GB','','VAT',true,true,'MONTHLY','COUNTRY',40),
 -- ---- 美国：联邦一行推不出要求；各州是真实的登记辖区（销售税账号） ----
 ('US','美国（联邦）','UNITED_STATES','US','','SALES_TAX',false,false,'QUARTERLY','SUBNATIONAL',50),
 ('US-AL','美国 阿拉巴马州','UNITED_STATES','US','US-AL','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5101),
 ('US-AK','美国 阿拉斯加州','UNITED_STATES','US','US-AK','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5102),
 ('US-AZ','美国 亚利桑那州','UNITED_STATES','US','US-AZ','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5103),
 ('US-AR','美国 阿肯色州','UNITED_STATES','US','US-AR','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5104),
 ('US-CA','美国 加利福尼亚州','UNITED_STATES','US','US-CA','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5105),
 ('US-CO','美国 科罗拉多州','UNITED_STATES','US','US-CO','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5106),
 ('US-CT','美国 康涅狄格州','UNITED_STATES','US','US-CT','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5107),
 ('US-DE','美国 特拉华州','UNITED_STATES','US','US-DE','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5108),
 ('US-DC','美国 华盛顿哥伦比亚特区','UNITED_STATES','US','US-DC','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5109),
 ('US-FL','美国 佛罗里达州','UNITED_STATES','US','US-FL','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5110),
 ('US-GA','美国 佐治亚州','UNITED_STATES','US','US-GA','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5111),
 ('US-HI','美国 夏威夷州','UNITED_STATES','US','US-HI','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5112),
 ('US-ID','美国 爱达荷州','UNITED_STATES','US','US-ID','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5113),
 ('US-IL','美国 伊利诺伊州','UNITED_STATES','US','US-IL','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5114),
 ('US-IN','美国 印第安纳州','UNITED_STATES','US','US-IN','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5115),
 ('US-IA','美国 爱荷华州','UNITED_STATES','US','US-IA','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5116),
 ('US-KS','美国 堪萨斯州','UNITED_STATES','US','US-KS','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5117),
 ('US-KY','美国 肯塔基州','UNITED_STATES','US','US-KY','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5118),
 ('US-LA','美国 路易斯安那州','UNITED_STATES','US','US-LA','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5119),
 ('US-ME','美国 缅因州','UNITED_STATES','US','US-ME','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5120),
 ('US-MD','美国 马里兰州','UNITED_STATES','US','US-MD','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5121),
 ('US-MA','美国 马萨诸塞州','UNITED_STATES','US','US-MA','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5122),
 ('US-MI','美国 密歇根州','UNITED_STATES','US','US-MI','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5123),
 ('US-MN','美国 明尼苏达州','UNITED_STATES','US','US-MN','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5124),
 ('US-MS','美国 密西西比州','UNITED_STATES','US','US-MS','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5125),
 ('US-MO','美国 密苏里州','UNITED_STATES','US','US-MO','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5126),
 ('US-MT','美国 蒙大拿州','UNITED_STATES','US','US-MT','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5127),
 ('US-NE','美国 内布拉斯加州','UNITED_STATES','US','US-NE','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5128),
 ('US-NV','美国 内华达州','UNITED_STATES','US','US-NV','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5129),
 ('US-NH','美国 新罕布什尔州','UNITED_STATES','US','US-NH','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5130),
 ('US-NJ','美国 新泽西州','UNITED_STATES','US','US-NJ','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5131),
 ('US-NM','美国 新墨西哥州','UNITED_STATES','US','US-NM','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5132),
 ('US-NY','美国 纽约州','UNITED_STATES','US','US-NY','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5133),
 ('US-NC','美国 北卡罗来纳州','UNITED_STATES','US','US-NC','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5134),
 ('US-ND','美国 北达科他州','UNITED_STATES','US','US-ND','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5135),
 ('US-OH','美国 俄亥俄州','UNITED_STATES','US','US-OH','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5136),
 ('US-OK','美国 俄克拉荷马州','UNITED_STATES','US','US-OK','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5137),
 ('US-OR','美国 俄勒冈州','UNITED_STATES','US','US-OR','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5138),
 ('US-PA','美国 宾夕法尼亚州','UNITED_STATES','US','US-PA','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5139),
 ('US-RI','美国 罗得岛州','UNITED_STATES','US','US-RI','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5140),
 ('US-SC','美国 南卡罗来纳州','UNITED_STATES','US','US-SC','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5141),
 ('US-SD','美国 南达科他州','UNITED_STATES','US','US-SD','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5142),
 ('US-TN','美国 田纳西州','UNITED_STATES','US','US-TN','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5143),
 ('US-TX','美国 得克萨斯州','UNITED_STATES','US','US-TX','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5144),
 ('US-UT','美国 犹他州','UNITED_STATES','US','US-UT','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5145),
 ('US-VT','美国 佛蒙特州','UNITED_STATES','US','US-VT','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5146),
 ('US-VA','美国 弗吉尼亚州','UNITED_STATES','US','US-VA','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5147),
 ('US-WA','美国 华盛顿州','UNITED_STATES','US','US-WA','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5148),
 ('US-WV','美国 西弗吉尼亚州','UNITED_STATES','US','US-WV','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5149),
 ('US-WI','美国 威斯康星州','UNITED_STATES','US','US-WI','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5150),
 ('US-WY','美国 怀俄明州','UNITED_STATES','US','US-WY','SALES_TAX',true,false,'QUARTERLY','SUBNATIONAL',5151),
 -- ---- 站点目录里出现过、且 vat_required=true 的其余国家 ----
 --     税制不是 VAT ⇒ 不要求 VAT 号（缺号不报缺口），但**未注册照样报**。
 ('JP','日本','JAPAN','JP','','CONSUMPTION_TAX',false,false,'QUARTERLY','COUNTRY',60),
 ('CA','加拿大','CANADA','CA','','GST',false,false,'QUARTERLY','COUNTRY',61),
 ('AU','澳大利亚','AUSTRALIA','AU','','GST',false,false,'QUARTERLY','COUNTRY',62),
 ('AE','阿联酋','MIDDLE_EAST','AE','','VAT',true,false,'MONTHLY','COUNTRY',63),
 ('BR','巴西','LATIN_AMERICA','BR','','NONE',false,false,NULL,'COUNTRY',64),
 ('SG','新加坡','SOUTHEAST_ASIA','SG','','GST',false,false,'QUARTERLY','COUNTRY',65),
 ('MY','马来西亚','SOUTHEAST_ASIA','MY','','SALES_TAX',false,false,'MONTHLY','COUNTRY',66),
 ('TH','泰国','SOUTHEAST_ASIA','TH','','VAT',true,false,'MONTHLY','COUNTRY',67)
) AS v(code,name,region,cc,sub,regime,vat,epr,cycle,scope,pos);

CREATE INDEX tax_registration_jurisdiction_country
  ON tax_registration_jurisdiction(country_code, subdivision_code);
CREATE INDEX tax_registration_jurisdiction_scope
  ON tax_registration_jurisdiction(derivation_scope, position);

-- ===========================================================================
-- 二、tax_registration_record · 税务登记档案（租户业务事实）
-- ===========================================================================
-- 🔴 **登记是「店铺/法人 × 辖区」多对多**：一个法人一个辖区一条登记，一个店铺多个辖区。
--   税号、VAT 状态、是否注册、EPR 注册号、注册有效期、申报周期**全部挂在登记上**。
--
-- 🔴 `subject_type='LEGAL_ENTITY'` 时 `subject_id` 是 **identity 的逻辑 ID**
--   （identity 拥有 `legal_entity` 表）。AGENTS.md 禁止跨库 FK / 共享视图 / 跨服务 SQL，
--   所以本表**不建指向 identity 的外键、也不复制 identity 的任何一列**
--   （法人名称 / 税号 / 注册国一律不复制过来；显示时经授权只读端口现读）。
--   存在性与租户归属由 identity 的授权 API 确认。
--
-- 🔴 `status='ARCHIVED'` 的登记**不参与**缺失项判定（它是「撤下」而不是「还在」）。
--   登记是事实，**不做物理删除**。
CREATE TABLE tax_registration_record (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- SHOP = 本服务 channel 自家库里的 shop.id；LEGAL_ENTITY = identity 的逻辑 ID
  subject_type      varchar(16) NOT NULL CHECK(subject_type IN ('SHOP','LEGAL_ENTITY')),
  subject_id        uuid NOT NULL,
  -- 🔴 辖区码：必须真实落在 tax_registration_jurisdiction 里（触发器钉死，不留自由文本）
  jurisdiction_code varchar(16) NOT NULL,
  -- 税号（一般税务登记号 / 法人税号）
  tax_number        varchar(64) NOT NULL DEFAULT '',
  -- VAT 状态
  vat_status        varchar(20) NOT NULL DEFAULT 'UNKNOWN'
                    CHECK(vat_status IN ('REGISTERED','NOT_REGISTERED','PENDING','EXEMPT','UNKNOWN')),
  -- 是否注册：登记本身在不在（false = 明确知道「没注册」）
  is_registered     boolean NOT NULL DEFAULT false,
  -- VAT 号（注册号）
  vat_number        varchar(64) NOT NULL DEFAULT '',
  -- EPR 注册号（包装法 / WEEE / 电池法）
  epr_registration_no varchar(64) NOT NULL DEFAULT '',
  -- 申报周期（16.3.1.2 申报日历的下游输入；本模块只存，不做日历）
  filing_cycle      varchar(16) NOT NULL DEFAULT ''
                    CHECK(filing_cycle IN ('','MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
  -- 注册有效期：🔴 timestamptz（不是 timestamp）——
  --   用 Timestamp.valueOf(LocalDateTime) 绑值会静默偏 −28800s，页面上看不出异常。
  registration_valid_from  timestamptz,
  registration_valid_until timestamptz,
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','ARCHIVED')),
  note              varchar(500) NOT NULL DEFAULT '',
  recorded_by       uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个主体在一个辖区**只有一条**登记：重录是**改这一行**（订正笔误），不是插第二条。
  UNIQUE(tenant_id, subject_type, subject_id, jurisdiction_code),
  -- 🔴 有效期区间必须自洽
  CHECK(registration_valid_until IS NULL OR registration_valid_from IS NULL
        OR registration_valid_until >= registration_valid_from),
  -- 🔴 「已注册」与 VAT 状态必须自洽：is_registered=true 时 vat_status 不能是
  --   NOT_REGISTERED / UNKNOWN（那是两件互相打架的事）。
  CHECK(NOT is_registered OR vat_status IN ('REGISTERED','PENDING','EXEMPT'))
);
COMMENT ON TABLE tax_registration_record IS
  '16.3.1.1 · 税务登记档案（主体 × 辖区）。主体 = 店铺（本库 shop.id）或法人（identity 的逻辑 ID）。'
  'jurisdiction_code 由触发器钉死落在 tax_registration_jurisdiction 码表里。'
  '登记是事实：只做 status=ARCHIVED 的撤下，不做物理删除。';

CREATE INDEX tax_registration_record_subject
  ON tax_registration_record(tenant_id, subject_type, subject_id, jurisdiction_code);
CREATE INDEX tax_registration_record_jurisdiction
  ON tax_registration_record(tenant_id, jurisdiction_code, status);
CREATE INDEX tax_registration_record_expiry
  ON tax_registration_record(tenant_id, registration_valid_until)
  WHERE registration_valid_until IS NOT NULL AND status = 'ACTIVE';

-- ===========================================================================
-- 三、触发器
-- ===========================================================================
-- 🔴 (a) 辖区码必须真实存在（**不留自由文本**的物理落点）
CREATE FUNCTION guard_tax_registration_jurisdiction_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM tax_registration_jurisdiction j WHERE j.code = NEW.jurisdiction_code) THEN
    RAISE EXCEPTION
      'Tax registration jurisdiction % is not a known jurisdiction code', NEW.jurisdiction_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tax_registration_jurisdiction_ref
  BEFORE INSERT OR UPDATE ON tax_registration_record
  FOR EACH ROW EXECUTE FUNCTION guard_tax_registration_jurisdiction_ref();

-- 🔴 (b) 店铺主体必须在本租户的店铺目录里真实存在（**零外键**的跨表引用校验）。
--     法人主体是**跨服务的逻辑 ID**，本库查不到也**不应该**查 ⇒ 刻意不校验，
--     由 identity 的授权 API 负责存在性与租户归属。
CREATE FUNCTION guard_tax_registration_shop_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.subject_type = 'SHOP' AND NOT EXISTS (
       SELECT 1 FROM shop s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.subject_id) THEN
    RAISE EXCEPTION
      'Tax registration references a shop that does not exist in this tenant (or is outside the current permission scope): %',
      NEW.subject_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tax_registration_shop_ref
  BEFORE INSERT OR UPDATE ON tax_registration_record
  FOR EACH ROW EXECUTE FUNCTION guard_tax_registration_shop_ref();

-- 🔴 (c) 撤下（ARCHIVED）是**唯一**的「不参与判定」方式，且**不可再改内容** ——
--     已撤下的登记如果又被改回 ACTIVE，历史上的「这个主体当时没有登记」就说不清了。
CREATE FUNCTION guard_tax_registration_archived_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'ARCHIVED' AND (
       NEW.status IS DISTINCT FROM OLD.status
    OR NEW.subject_type IS DISTINCT FROM OLD.subject_type
    OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
    OR NEW.jurisdiction_code IS DISTINCT FROM OLD.jurisdiction_code
    OR NEW.tax_number IS DISTINCT FROM OLD.tax_number
    OR NEW.vat_status IS DISTINCT FROM OLD.vat_status
    OR NEW.is_registered IS DISTINCT FROM OLD.is_registered
    OR NEW.vat_number IS DISTINCT FROM OLD.vat_number
    OR NEW.epr_registration_no IS DISTINCT FROM OLD.epr_registration_no
    OR NEW.filing_cycle IS DISTINCT FROM OLD.filing_cycle
    OR NEW.registration_valid_from IS DISTINCT FROM OLD.registration_valid_from
    OR NEW.registration_valid_until IS DISTINCT FROM OLD.registration_valid_until) THEN
    RAISE EXCEPTION
      'Tax registration % is archived; its registration facts cannot be rewritten', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tax_registration_archived_immutable
  BEFORE UPDATE ON tax_registration_record
  FOR EACH ROW EXECUTE FUNCTION guard_tax_registration_archived_immutable();

-- ===========================================================================
-- 四、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['tax_registration_record'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 🔴 数据范围（RESTRICTIVE）：**只套在店铺主体行上**（与 V300 account_health_metric 同构）。
--   法人主体行**刻意只有 isolation 策略** —— 法人的数据范围归 identity（17）所有，
--   本服务读不到；硬套店铺收敛会让「这个法人在德国注册了没有」凭空消失。
CREATE POLICY data_scope ON tax_registration_record AS RESTRICTIVE
  USING (subject_type <> 'SHOP'
         OR EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=tax_registration_record.tenant_id
                   AND s.id=tax_registration_record.subject_id
                   AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (subject_type <> 'SHOP'
         OR EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=tax_registration_record.tenant_id
                   AND s.id=tax_registration_record.subject_id
                   AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 🔴 `tax_registration_jurisdiction` 是**全局码表**（与 platform / marketplace 同口径），
--   不带 tenant_id、**不参与**租户隔离。

-- ===========================================================================
-- 五、账号与授权
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
-- 辖区码表是全局只读码表（与 V1 的 platform / marketplace 同一口径）。
GRANT SELECT ON tax_registration_jurisdiction TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON tax_registration_record TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V308 同口径）。
REVOKE ALL ON tax_registration_record FROM starcon_app;
REVOKE ALL ON tax_registration_jurisdiction FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- 🔴 **序列授权**：本迁移的两张表**全部用应用侧生成的 uuid 作主键**，
--   没有任何 serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 税务登记属于「平台操作 / 合规台账」语义，
-- 与 16.1.1.1 账号健康、16.2.2.1 合规预检、16.4.2.1 品牌授权同族，
-- 复用 identity 已注册的 channel.operation.*（读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 七、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **17.1.2.1 法人档案（identity）**：跨服务只读，**只拿编码与名称**，
--     不复制任何一列到本库；读不到时如实报「读不到」（available=false + 原因），
--     **绝不**把法人显示成空名字或「未注册」。
--   * **16.3.1.2 申报日历**：未交付 ⇒ 本模块**只存**申报周期，不做日历、不做提醒。
--   * **16.3.2.1 VAT/IOSS**：未交付 ⇒ 本模块**不编造** IOSS 订单清单与代收代缴统计。
--   * **16.3.2.2 EPR/包装法**：未交付 ⇒ 本模块**只存** EPR 注册号，
--     不做包装法费用与刊登限制（那要 4 刊登与 12 财务）。
--   * **12.6 税务**：finance 服务在本仓**尚未落地** ⇒ 本模块**不建任何财务表**，
--     不做税额计算、不做申报底稿。
--   * **美国各州销售税**：本仓**没有销售事实表**，推不出「该在哪个州注册」，
--     因此美国各州只登记、不自动派生要求（`derivation_scope='SUBNATIONAL'`），
--     这条「推不出」在能力声明里写明。
-- ---------------------------------------------------------------------------
