-- 6.1.1.2 供应商详情：**单供应商全景**里本模块自己拥有的那部分。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：单供应商全景：基础信息、账期与开票信息、供货 SKU 与报价、采购历史与金额、
--             交期与质量表现、联系人、附件资质。
--   **输入**：supplier_id；采购历史聚合；报价列表（03）。
--   **输出**：详情视图 + 操作入口（新建采购单、发起对账、更新报价）。
--   **关系**：上游 = 6.1.1.1；下游 = 6.3.1.2、6.5.1、3.6.2 报价。
--             **账期与开票信息是付款审批的输入**，缺失时 12 财务无法自动生成付款申请。
--
-- ============================================================ 第一节：本模块在哪个服务里，以及为什么不放 trade
--
-- 任务书把主责服务写作 trade，但那条指定建立在一个**已被证伪的前提**上：
-- 「6.1.1.1 在 trade，跨服务读 catalog」。实际集成头里 6.1.1.1 整个落在 **catalog**
-- （V137__vendor_supply_perspective.sql，`/catalog/supply/vendors/**`），
-- 并且**零跨服务读**。本模块照它落在 catalog，理由按重要性排：
--
--   1. **本模块的每一个输入都在 catalog**。基础信息在 party(V27)，合作等级与默认账期在
--      party_supplier_profile(V30)，报价在 supplier_quote(V32)，采购历史在
--      vendor_delivery_record(V137)，交期与质量表现在 vendor_delivery_record +
--      vendor_quality_record(V137)。放到 trade 意味着为一个纯读模块**新建五条跨服务调用**，
--      每一条都是一个新的失效点，而详情页会在 catalog 抖动时整页报错。
--
--   2. **聚合 SQL 只能在原料所在的库里写**。「采购历史与金额」「交期与质量表现」是
--      聚合值（硬要求：必须由聚合 SQL 从事实表算出来，不能在前端加总假数据）。
--      把 catalog 的事实表复制一份到 trade 就能在 trade 里写聚合 SQL ——
--      而那正是 AGENTS.md「每张事实表只有一个写入所有者」禁止的第二个写入所有者，
--      且 V137 第三节已经论证过为什么到货 / 质检事实归 catalog：不复制。
--
--   3. V137 第三节第 1 条已经写死了归属：「指标的**定义**与它的**原料**应该同属一个写入
--      所有者……本服务负责留档与聚合」。6.1.1.2 的准时率与合格率就是那两个指标，
--      在另一个服务里再算一遍必然会在某次口径变更后与列表不一致，而不一致**不会报错**。
--
-- 所以本迁移在 catalog，路由沿用 `/catalog/supply/vendors/**`（前端只挂一个服务前缀）。
--
-- ============================================================ 第二节：字段归属 —— 全景里每一段归谁
--
-- 蓝图「职责」列了七段，逐段落实（**不重建第二份供应商主档**，理由同 V137 第一节）：
--
--   | 全景里的段落        | 存在哪                          | 写入所有者 | 本模块       |
--   |---------------------|---------------------------------|-----------|-------------|
--   | 编码/名称/联系方式   | party (V27)                     | 3.6.1.1   | 只读        |
--   | 合作等级/默认账期    | party_supplier_profile (V30)    | 3.6.1.2   | 只读        |
--   | 采购侧合作状态      | vendor_supply_profile (V137)    | 6.1.1.1   | 只读        |
--   | **账期与开票信息**  | **vendor_settlement_terms（本表）** | **本模块** | **可写** |
--   | **附件资质**        | **vendor_qualification_attachment（本表）** | **本模块** | **可写** |
--   | 供货 SKU 与报价     | supplier_quote (V32)            | 3.6.2.1   | 只读        |
--   | 采购历史与金额      | vendor_delivery_record (V137)   | 6.1.1.1   | 只读 + 聚合 |
--   | 交期与质量表现      | 同上两张事实表 (V137)           | 6.1.1.1   | 只读 + 聚合 |
--   | 联系人              | party_contact (V31)             | 3.6.x     | 只读        |
--
-- **本迁移不 ALTER 任何既有表** —— 对并行开发的其它车道零 DDL 回归面。
--
-- ============================================================ 第三节：为什么「账期与开票」必须自己有一份
--
-- 蓝图加粗句：**账期与开票信息是付款审批的输入，缺失时 12 财务无法自动生成付款申请。**
-- 硬要求：它是**真实可维护的数据**，不是只读展示。
--
-- 那为什么不直接用 party_supplier_profile.payment_terms_days（V30 已有，且 3.6.1.2 可维护）？
-- 三个理由，按重要性排：
--
--   1. **两者回答的不是同一个问题。** psp.payment_terms_days 是**主体级默认账期**：
--      「跟这家往来单位做业务，默认放几天」。而付款审批要读的是**这条采购线上的约定**：
--      「这个币种、这个结算方式、预付多少、什么时候生效」。
--      用前者去付款，拿到的是别人的默认值 —— 一个主体可以同时有 USD 预付与 EUR 月结两条线。
--   2. **开票信息在 V30 里根本不存在**（没有发票类型 / 抬头 / 税号 / 税率）。
--      「只读展示」的账期 + 空白的开票信息，对 12 财务等于零输入。
--   3. **责任归属不同**。psp 的默认账期随主体档案一起维护；采购线的账期随**采购谈判**
--      一起改（改一次 MOQ 通常一起改账期），写入口在 06 这一侧。
--
-- 但**基础账期仍然只读展示**：全景里同时返回 psp.payment_terms_days（标注来源）
-- 与本表的 payment_terms_days（采购线约定），两者不同**是正常的**，
-- 界面上把「默认」与「本采购线」分两行标出来，而不是悄悄用其中一个覆盖另一个。
--
-- 收款账户（银行账号 / 开户行）刻意**不在本表**：它是收款方主数据，
-- 由 12 财务与往来单位档案那一侧维护，在本表复制一份就造出了第二个写入所有者。
--
-- ============================================================ 第四节：vendor_settlement_terms —— 账期与开票（1:1 挂 SUPPLIER 角色）

CREATE TABLE vendor_settlement_terms (
  tenant_id uuid NOT NULL,
  role_id uuid NOT NULL,

  -- 采购结算币种：本条约定适用于哪个币种。与 vendor_delivery_record.currency 同口径，
  -- **仍然没有汇率表**，所以跨币种的账期不可比（V137 第二节 R4 同一个理由）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 本采购线约定账期（天）。整数天，不是月也不是字符串：12 应付的到期日 = 单据日 + N 天。
  -- 0 = 不给账期（下单现付 / 货到付款），而不是「没填」—— 与 psp.payment_terms_days
  -- 同一语义约定。上限 365 挡住把年填进天。
  payment_terms_days int NOT NULL CHECK(payment_terms_days >= 0 AND payment_terms_days <= 365),

  -- 结算方式：
  -- **取值逐字沿用 3.6.1.2 的 party_supplier_profile.settlement_method 词表**
  -- （PREPAID / ON_DELIVERY / CREDIT / LETTER_OF_CREDIT），**刻意不另造一套**：
  -- 详情页上「本采购线结算方式」与「主体默认结算方式」并排显示，
  -- 两套词汇会让同一个词在同一个界面上对不上号，而枚举只增不改这条承诺也要求它们同源。
  --   PREPAID           预付：先款后货；deposit_ratio 决定首付比例。
  --   ON_DELIVERY       货到付款。
  --   CREDIT            月结 / 账期：按 payment_terms_days 算到期日。
  --   LETTER_OF_CREDIT  信用证：走银行单据流程，不进普通付款审批。
  -- 寄售（CONSIGNMENT）不进这张闭集：3.6.1.2 没有它，而这里造一个它就造出了第二套词汇；
  -- 真有寄售安排的写进 note —— 那是谈判事实，不需要一个枚举值。
  settlement_method text NOT NULL
    CHECK(settlement_method IN ('PREPAID','ON_DELIVERY','CREDIT','LETTER_OF_CREDIT')),

  -- 发票类型。开票方式在跨境场景里不是可选项（VAT 专票 / 普票 / 形式发票 /
  -- 出口报关单 / 无票），12 财务按它决定能不能自动生成进项抵扣。
  invoice_type text NOT NULL
    CHECK(invoice_type IN ('VAT_SPECIAL','VAT_NORMAL','PROFORMA','EXPORT_DECLARATION','NONE')),

  -- 发票抬头：供应商开给我方的名称。**刻意允许与 party.name 不同**：
  -- 收款主体与开票主体不一致在跨境里是常态（尤其走平台或关联公司结算），
  -- 用 party.name 顶替会让 12 财务拿错抬头开票，事后只能红冲重开。
  invoice_title varchar(200) NOT NULL CHECK(btrim(invoice_title)<>''),

  -- 纳税人识别号（跨境场景常为税号 / VAT EORI / 平台商户号）。可空：很多个人与小规模
  -- 供应商确实没有，但为空时 12 财务不能自动生成付款申请 —— 由服务层给警告而不是假装齐备。
  tax_id varchar(64) NOT NULL DEFAULT '',

  -- 税率：0~1 的比例（0.13 = 13%），**不是 13**。存比例是因为下游按比例算进项，
  -- 而把 13 存成 13 的那条记录会在算税额时放大 100 倍且不报错。
  -- 精度 numeric(9,6)：0.000001 级别的税率（某些国家按千分比征税）在两位小数下全部归零。
  tax_rate numeric(9,6) NOT NULL DEFAULT 0 CHECK(tax_rate >= 0 AND tax_rate <= 1),

  -- 采购信用额度：12 财务授信审批的输入。null = 未设额度（**与 0 是两件事**：
  -- 前者不拦截，后者额度已用尽）。理由与 DealerPartyQueryPort 的
  -- defaultCreditLimit 同源：折叠成 0 会让「没谈额度」被当成「额度为 0」而全量拒付。
  credit_limit numeric(19,4) CHECK(credit_limit IS NULL OR credit_limit >= 0),

  -- 预付比例：只对 PREPAY 有意义。存 0~1 比例，与 tax_rate 同一口径。
  deposit_ratio numeric(9,6) NOT NULL DEFAULT 0 CHECK(deposit_ratio >= 0 AND deposit_ratio <= 1),

  -- 约定生效日：到期日 = 单据日 + payment_terms_days，而单据日必须落在生效日之后。
  -- 没有生效日的账期无法回答「3 月那笔单该按几天算」—— 而这正是付款审批最常问的问题。
  effective_from date NOT NULL,

  -- 采购侧备注：谈定的条件（预付比例可退、罚则例外、需对方提供形式发票等）。
  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id, role_id),
  FOREIGN KEY(tenant_id, role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE,
  -- PREPAID 必须有预付比例，且不能是 0（0 会让 12 财务算出「不用预付」的预付约定，
  -- 那是一个自相矛盾的约定，界面显示不出问题但付款申请会退回来）。
  CHECK(settlement_method <> 'PREPAID' OR deposit_ratio > 0),
  -- 需要发票的结算方式必须留税号：缺了它 12 财务无法自动生成付款申请，
  -- 而「开票方式=专票却没有税号」是最常见的一处不完整录入。
  CHECK(invoice_type IN ('NONE','PROFORMA') OR btrim(tax_id) <> '')
);

-- 「哪些供应商还没建账期与开票信息」—— 12 财务批量补录的主查询口径，
-- 也是本模块治理警告的来源。前导列 tenant_id, role_id 是主查询路径。
CREATE INDEX vendor_settlement_terms_by_currency
  ON vendor_settlement_terms(tenant_id, currency, role_id);

-- ============================================================ 第五节：vendor_qualification_attachment —— 附件资质
--
-- 资质是**附件**，不是字段：营业执照、ISO9001、CE、FDA、产地证各有各的编号、
-- 发证机构与有效期，所以每份附件一行。**文件本身不入库**（只存对象存储的 key），
-- 二进制进业务库会让备份、迁移与 RLS 审计都变成搬文件的工作。
--
-- 有效期状态（VALID / EXPIRING_SOON / EXPIRED）**不落库**，由 SQL 现算：
-- 存一列就要一个每日任务去维护它，而维护出来的那天与读出口算出的那天差一天时，
-- 「这张证到底过期没有」会答错 —— 而这个答案直接决定能不能下单。
CREATE TABLE vendor_qualification_attachment (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  role_id uuid NOT NULL,

  -- 资质类型。闭集，枚举只增不改（V137 第九节 R6 同源）：
  --   BUSINESS_LICENSE  营业执照
  --   ISO9001 / ISO14001 体系认证
  --   CE                CE 认证（欧盟）
  --   FDA               FDA 注册（美国）
  --   COC               产地证
  --   OTHER             其它（有 title 兜底，不建自由文本类型）
  qualification_type text NOT NULL
    CHECK(qualification_type IN ('BUSINESS_LICENSE','ISO9001','ISO14001','CE','FDA','COC','OTHER')),

  -- 证件名称：文件上的正式名称（「营业执照」「ISO9001:2015 质量管理体系证书」）。
  title varchar(200) NOT NULL CHECK(btrim(title)<>''),
  -- 证书编号：12 财务与 6.5 对账核验时用。空串表示「该证件没有编号」（如产地证）。
  cert_no varchar(64) NOT NULL DEFAULT '',
  -- 发证机构。
  issuer varchar(120) NOT NULL DEFAULT '',

  issued_on date,
  -- 到期日。**可空且是常态**：营业执照与体系认证通常有到期日，
  -- 而产地证是单次文件。null = 长期有效，不是「没填」。
  expires_on date,

  -- 文件：对象存储 key（**不是 URL** —— 换存储服务商时不必改数据），
  -- 连同展示名、类型与字节数一起存，列表页不用去对象存储取一次元数据。
  file_key varchar(256) NOT NULL CHECK(btrim(file_key)<>''),
  file_name varchar(160) NOT NULL DEFAULT '',
  mime_type varchar(80) NOT NULL DEFAULT '',
  file_size_bytes bigint CHECK(file_size_bytes IS NULL OR file_size_bytes >= 0),

  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE,
  -- 有效期区间自洽：结束日不能早于签发日。DB 层兜一道，理由与 V32 的 valid_to 相同。
  CHECK(expires_on IS NULL OR issued_on IS NULL OR expires_on >= issued_on),
  -- 与 party_contact(V31) 同一口径：留空白串是脏数据。
  CHECK(cert_no = btrim(cert_no)),
  CHECK(issuer = btrim(issuer))
);

-- 详情页资质清单：`tenant_id, role_id, qualification_type, id`，按到期日排。
CREATE INDEX vendor_qualification_by_role
  ON vendor_qualification_attachment(tenant_id, role_id, qualification_type, id);
-- 「这个供应商有哪些资质将在 N 天内到期」—— 治理警告与到期提醒的查询口径。
CREATE INDEX vendor_qualification_by_expiry
  ON vendor_qualification_attachment(tenant_id, role_id, expires_on);

-- ============================================================ 第六节：RLS 与授权
--
-- 两张表都是租户级数据（供应商采购侧档案），沿用 V137 第八节那一段 DO $$ FOREACH：
-- 只需要租户隔离，不套 owner_id / department_id 数据范围。
--
-- **读 party / party_contact / supplier_quote 时 RLS 是第二道闸**：
-- 详情查询同时带显式 `tenant_id=?` 绑定与对方表自己的 FORCE RLS，任何一道单独失效都不放行跨租户。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['vendor_settlement_terms','vendor_qualification_attachment'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON vendor_settlement_terms, vendor_qualification_attachment TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON vendor_settlement_terms, vendor_qualification_attachment TO starcon_app;

-- ============================================================ 第七节：本迁移钉死的语义（服务层 VendorDetailRules 的对应实现）
--
--   **D1 账期与开票是可写数据。** 蓝图加粗句要求它是付款审批的输入，
--       所以它必须能被维护（PUT），而不是从别处只读展示一个字段。
--       缺失时全景返回 termsFound=false + 治理警告「12 财务无法自动生成付款申请」。
--   **D2 账期是整数天。** 到期日 = 单据日 + N 天，任何一层换成「30 天」再解析回数字
--       都会在某个月差一天（与 V30 第 63-72 行同一个理由）。
--   **D3 跨币种不相加。** 采购金额、报价金额一律按币种分组返回，本库没有汇率表。
--   **D4 比率的分母为 0 是 null，不是 0**（沿用 V137 R3）。
--       「还没交货过」与「交货全部延期」是相反的两件事。
--   **D5 附件资质可改可删，但账期与开票只有一份。** 资质是行政记录（换证就换一行），
--       账期与开票是付款依据（一份，改了留 version + 审计）。
--   **D6 不写 party / psp / vsp。** 本模块对 party、party_supplier_profile、
--       vendor_supply_profile 一个字节都不写（理由见第一节与第二节的归属表）。
--   **D7 资质有效期状态现算。** 见第五节，不落库。
