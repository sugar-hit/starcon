-- 10.5.1.1 报关单管理。
--
-- 蓝图 10-logistics-and-freight.md 10.5.1.1 原文：
--   职责：管理报关单：按发货单生成申报资料（报关单、装箱单、发票），提交报关行/IOR，
--         接收放行、退单、改单结果。
--   输入：发货明细；3.7.1.1 HS 编码与申报要素；货值与汇率快照；贸易方式与收发货人信息。
--   输出：报关单 + 三单据 + 报关行提交结果 + 放行/异常状态。
--   关系：上游 = 10.4.1.1、3.7 报关属性；下游 = 10.5.1.3 关税、12 财务（成本）、16 合规。
--         **报关数据错误导致的扣货与罚款由卖家承担**，因此必须有人工复核节点。
--
-- ===========================================================================
-- 一、本迁移**只新建表，不 ALTER 任何既有表** —— 并行车道的零回归面
-- ===========================================================================
-- 同服务里已落地的 10.4.1.1（V80 四张表）与 8.2.1.1（V70）都由别的车道交付，
-- 本迁移对它们**只读**：不 ADD COLUMN、不 SET NOT NULL、不加 CHECK、不碰触发器。
-- 因此本迁移落到集成头时不可能把 10.4.1.1 / 8.2.1.1 的既有行或既有回归测试带崩。
-- 跨车道回归面 = 0。
--
-- ===========================================================================
-- 二、人工复核闸是**硬约束**，钉在 DB 层而不是只靠服务层 if
-- ===========================================================================
-- 蓝图关系段原文：「**报关数据错误导致的扣货与罚款由卖家承担**，因此必须有人工复核节点」。
-- 所以 guard_customs_submit_review 这条 BEFORE UPDATE 触发器钉死：
--   status 想变 SUBMITTED → 旧状态必须已经是 REVIEWED，且 reviewed_at/reviewed_by 都非空。
-- 绕过服务层直接 UPDATE 状态同样被挡下（集成测试里有对应用例）。
-- 少了这一条，「自动提交报关行」就是一个能把罚款写进账的 bug，而不是功能。
--
-- 复核不通过（REJECTED）后必须退回草稿重新改，**改完要重新走复核才能再次提交** ——
-- 这正是蓝图那句话的推论：改完的数据没人看过就不该再出去一次。
--
-- ===========================================================================
-- 三、货值与汇率是**快照**，不是运行时去取的当前汇率
-- ===========================================================================
-- 汇率会变，申报资料必须可复现、可举证：海关事后复核 12 个月前的报关单时，
-- 要能回答「当时按哪个汇率申报的」。所以 exchange_rate + exchange_rate_scale +
-- exchange_rate_date + declaration_value_base 四列一起落库并**冻结**
-- （guard_customs_rate_snapshot 用延迟约束触发器钉死
--   declaration_value_base = declaration_value × exchange_rate，
-- 头与明细在同一个事务里先后写，即时触发器会对着半成品报错，必须 DEFERRABLE）。
--
-- 汇率本身用 numeric(20,10) 并**单独保留精度刻度**（exchange_rate_scale）：
-- 不同币对的报价精度不同（USD/CNY 6 位、JPY/USD 4 位），把刻度抹平会让
-- 「当时按哪个精度报的」答不出来，而这正是举证时要被问的那一句。
--
-- ===========================================================================
-- 四、3.7.1.1 HS 编码与申报要素**上游未交付** —— 如实建模，不编造
-- ===========================================================================
-- 本车道对集成头实查：backend/services/ 下只有 catalog / channel / identity / warehouse，
-- **没有 product_compliance**，3.7.1.1 的台账状态是阻塞。因此本迁移：
--   * **不建**任何 HS 编码字典表（建了就是假的上游，且会成为双 writer）；
--   * hs_code 接受自由码，并带 hs_code_source（'MANUAL' / 'MODULE_3_7_1_1'）
--     与 hs_code_verified，把「这个码是人工填的还是字典来的」**如实记下来**；
--   * 申报要素留成**可后补的结构**：declaration_elements jsonb（键值对象，字段在）
--     + declaration_element_status（齐备性状态机）
--     + declaration_elements_source / declaration_elements_ref（来源与定位键在）。
--     3.7.1.1 交付后由它回填 jsonb 并把来源改成 MODULE_3_7_1_1，**不需要改结构**；
--   * **不写死**任何申报要素键名数组 —— 申报要素随类目与目的国变化，
--     写死等于把上游字典抄成第二份且必然漂移。
--
-- ===========================================================================
-- 五、一张头程发运单只允许一张报关单
-- ===========================================================================
-- UNIQUE(tenant_id, source_shipment_id) 钉死。重复报关是真实法律风险（可能被判申报不实），
-- 而且「一票货报了两次」在账上完全看不出来。改单走状态机（AMENDMENT 事件 + 单据版本链），
-- 不靠再造一张单来解决。
--
-- 数量一律 numeric(19,6)（沿用 8.2.1.1 / 10.4.1.1 的口径），
-- 货值与金额 numeric(19,4)，汇率 numeric(20,10)，全程禁浮点。

CREATE SEQUENCE customs_declaration_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 报关单头（九态：草稿 → 待复核 ⇄ 已复核/复核退回 → 已提交 → 放行/退单；草稿可取消）
-- ---------------------------------------------------------------------------
-- 状态与时间戳由触发器钉死自洽，没有「已提交却没复核人」这种账外状态。
--
-- 终态：CLEARED（已放行）与 CANCELLED（已取消）。放行是终态：
-- 货已清关放行，回退状态会让「这票货什么时候放行的」凭空消失；
-- 要修正已放行的申报资料必须走 4.5 改单，那是一条独立的审计事件而不是改状态。
CREATE TABLE customs_declaration (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  declaration_code varchar(32) NOT NULL,
  -- ===== 来源：10.4.1.1 头程发运单（已交付，迁移 V80，同服务 → 建复合外键）=====
  -- 对 first_mile_shipment 建**复合外键**是允许的：那是**本服务**的表，
  -- 与 V63 对 inventory_lot、V80 对 goods_receipt_event_line 同一条纪律。
  -- 它保证「引用的那张发运单真的存在、且与本租户一致」—— 不是只记一个说明文字。
  source_shipment_id uuid NOT NULL,
  -- 冗余固化的来源单号快照：来源单被读侧改描述时不影响本单可读性。
  source_shipment_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_id uuid NOT NULL,
  -- ===== 贸易方式与收发货人（蓝图输入：贸易方式与收发货人信息）=====
  -- 贸易方式是海关监管方式代码，决定退税率与监管条件，NOT NULL。
  trade_mode varchar(32) NOT NULL CHECK(trade_mode IN (
    'GENERAL_TRADE','PROCESSING_TRADE','PROCESSING_SUPPLIED','TRANSIT_TRADE',
    'CROSS_BORDER_B2B','CROSS_BORDER_B2C','SAMPLES','GIFT','REPAIR','PERSONAL')),
  -- 出口方（收发货人的卖方侧）
  exporter_name varchar(200) NOT NULL,
  exporter_country_code varchar(2) NOT NULL DEFAULT 'CN',
  -- 进口方（收发货人的买方侧）
  consignee_name varchar(200) NOT NULL,
  consignee_country_code varchar(2) NOT NULL,
  -- IOR（进口商记录人）：DDP 下由卖方承担登记申报的义务，
  -- 由 IOR 申报时收发货人是同一个法人，不一致则必须说明。
  ior_name varchar(200) NOT NULL DEFAULT '',
  ior_country_code varchar(2) NOT NULL DEFAULT '',
  -- 报关行 / 代理申报单位（提交对象）
  broker_name varchar(200) NOT NULL DEFAULT '',
  broker_code varchar(64) NOT NULL DEFAULT '',
  broker_contact varchar(100) NOT NULL DEFAULT '',
  -- ===== 运输与口岸 =====
  destination_country_code varchar(2) NOT NULL,
  destination_customs_district varchar(100) NOT NULL DEFAULT '',
  departure_port varchar(100) NOT NULL DEFAULT '',
  destination_port varchar(100) NOT NULL DEFAULT '',
  transport_mode varchar(16) NOT NULL CHECK(transport_mode IN ('SEA','AIR','RAIL','EXPRESS')),
  bill_of_lading_no varchar(64) NOT NULL DEFAULT '',
  -- ===== 货值与汇率快照（见文件头第三节：必须可复现、可举证）=====
  declaration_currency char(3) NOT NULL DEFAULT 'USD',
  declaration_value numeric(19,4) NOT NULL DEFAULT 0,
  -- 汇率与基准币：本位币口径固定为 base_currency（申报资料可举证的核心）。
  base_currency char(3) NOT NULL DEFAULT 'CNY',
  exchange_rate numeric(20,10) NOT NULL DEFAULT 1,
  -- 精度刻度单独保留：不同币对报价精度不同，抹平就答不出「当时按哪个精度报的」。
  exchange_rate_scale integer NOT NULL DEFAULT 6 CHECK(exchange_rate_scale>=0 AND exchange_rate_scale<=10),
  exchange_rate_date date NOT NULL DEFAULT CURRENT_DATE,
  -- 折算本位币金额 = declaration_value × exchange_rate（延迟约束触发器钉死）
  declaration_value_base numeric(19,4) NOT NULL DEFAULT 0,
  -- ===== 汇总（由明细聚合，与明细并发写）=====
  total_line_count integer NOT NULL DEFAULT 0,
  total_quantity numeric(19,6) NOT NULL DEFAULT 0,
  total_package_count integer NOT NULL DEFAULT 0,
  total_gross_weight_kg numeric(19,4) NOT NULL DEFAULT 0,
  total_net_weight_kg numeric(19,4) NOT NULL DEFAULT 0,
  total_volume_cbm numeric(19,6) NOT NULL DEFAULT 0,
  -- ===== 状态机（见文件头第二节）=====
  status varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
    'DRAFT','PENDING_REVIEW','REVIEWED','REJECTED','SUBMITTED',
    'CLEARED','CUSTOMS_REJECTED','AMENDED','CANCELLED')),
  -- 人工复核痕迹：**复核前不允许提交报关行**（文件头第二节的硬约束）
  review_round integer NOT NULL DEFAULT 0 CHECK(review_round>=0),
  reviewed_at timestamptz,
  reviewed_by uuid,
  review_notes text NOT NULL DEFAULT '',
  -- 提交与回执
  submitted_at timestamptz,
  submitted_by uuid,
  -- 报关行受理回执号 / 提交批次号（外部报关行接口未交付，这两列由人工回填）
  submission_reference varchar(64) NOT NULL DEFAULT '',
  customs_release_no varchar(64) NOT NULL DEFAULT '',
  cleared_at timestamptz,
  customs_rejected_at timestamptz,
  customs_rejection_reason text NOT NULL DEFAULT '',
  -- 改单：次数与最近一次时间（明细在 customs_result 里逐条留痕）
  amendment_count integer NOT NULL DEFAULT 0 CHECK(amendment_count>=0),
  last_amended_at timestamptz,
  cancelled_at timestamptz,
  cancelled_by uuid,
  cancel_reason varchar(200) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,declaration_code),
  -- 一票货只报一次关（文件头第五节）
  UNIQUE(tenant_id,source_shipment_id),
  FOREIGN KEY(tenant_id,source_shipment_id) REFERENCES first_mile_shipment(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(declaration_code)<>''),
  CHECK(length(btrim(exporter_country_code))=2),
  CHECK(length(btrim(consignee_country_code))=2),
  CHECK(length(btrim(destination_country_code))=2),
  CHECK(btrim(exporter_name)<>''),
  CHECK(btrim(consignee_name)<>''),
  -- 给了 IOR 国家就必须给了 IOR 名称：只给一半会让「谁作为 IOR 申报」答不出来。
  CHECK(btrim(ior_country_code)='' OR (length(btrim(ior_country_code))=2 AND btrim(ior_name)<>'')),
  -- 币种三位大写字母
  CHECK(declaration_currency ~ '^[A-Z]{3}$'),
  CHECK(base_currency ~ '^[A-Z]{3}$'),
  CHECK(exchange_rate>0),
  CHECK(declaration_value>=0),
  CHECK(total_line_count>=0 AND total_package_count>=0),
  CHECK(total_quantity>=0),
  CHECK(total_gross_weight_kg>=0 AND total_net_weight_kg>=0 AND total_net_weight_kg<=total_gross_weight_kg),
  CHECK(total_volume_cbm>=0)
);

-- ---------------------------------------------------------------------------
-- 2. 报关单明细行（HS 编码 + 申报要素的可后补结构，见文件头第四节）
-- ---------------------------------------------------------------------------
-- 按 10.4.1.1 的明细行建**复合外键**：source_line_id 指向 first_mile_shipment_line，
-- 那是**本服务**的表。它保证「申报的这一行真的来自那张发运单、且属于本租户」。
--
-- sku_id 属 catalog 服务 → **不建外键**（AGENTS.md 禁止跨服务 FK），
-- 只记逻辑引用 + sku_code / sku_name 快照。
CREATE TABLE customs_declaration_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  line_no integer NOT NULL CHECK(line_no>0),
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 来源明细行（10.4.1.1，本服务 → 复合外键）
  source_line_id uuid NOT NULL,
  source_line_no integer NOT NULL DEFAULT 0,
  -- ===== 申报口径（不照搬销售标题：10.5.1.2 原文点名混用是常见被退单原因）=====
  declared_item_name varchar(200) NOT NULL,
  -- HS 编码：3.7.1.1 未交付 → 自由码 + 来源标记 + 未验证标记，**没有字典可校验**
  hs_code varchar(32) NOT NULL DEFAULT '',
  hs_code_source varchar(24) NOT NULL DEFAULT 'MANUAL' CHECK(hs_code_source IN ('MANUAL','MODULE_3_7_1_1')),
  hs_code_verified boolean NOT NULL DEFAULT false,
  -- 申报要素：**可后补的结构**，字段在、来源标记在，不写死键名
  declaration_elements jsonb NOT NULL DEFAULT '{}'::jsonb,
  declaration_element_status varchar(24) NOT NULL DEFAULT 'MANUAL_PENDING' CHECK(declaration_element_status IN (
    'MANUAL_PENDING','MANUAL_COMPLETE','MODULE_3_7_1_1_PENDING','MODULE_3_7_1_1_COMPLETE')),
  declaration_elements_source varchar(32) NOT NULL DEFAULT 'MANUAL'
    CHECK(declaration_elements_source IN ('MANUAL','MODULE_3_7_1_1')),
  declaration_elements_ref varchar(64) NOT NULL DEFAULT '',
  -- 申报要素的规格/材质/用途/品牌：同样按报关口径单独存，不从销售标题推导
  spec_material varchar(200) NOT NULL DEFAULT '',
  usage varchar(100) NOT NULL DEFAULT '',
  brand varchar(100) NOT NULL DEFAULT '',
  model_no varchar(100) NOT NULL DEFAULT '',
  origin_country_code varchar(2) NOT NULL,
  -- ===== 数量与货值 =====
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),
  uom varchar(8) NOT NULL DEFAULT 'PCS',
  declared_unit_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(declared_unit_value>=0),
  declared_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(declared_value>=0),
  gross_weight_kg numeric(19,4) NOT NULL DEFAULT 0,
  net_weight_kg numeric(19,4) NOT NULL DEFAULT 0,
  volume_cbm numeric(19,6) NOT NULL DEFAULT 0,
  package_count integer NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,declaration_id,line_no),
  FOREIGN KEY(tenant_id,declaration_id) REFERENCES customs_declaration(tenant_id,id),
  FOREIGN KEY(tenant_id,source_line_id) REFERENCES first_mile_shipment_line(tenant_id,id),
  CHECK(btrim(declared_item_name)<>''),
  CHECK(length(btrim(origin_country_code))=2),
  -- 申报要素必须是对象而不是数组/标量：结构留给上游字典回填，写死键名会漂移
  CHECK(jsonb_typeof(declaration_elements)='object'),
  CHECK(gross_weight_kg>=0 AND net_weight_kg>=0 AND net_weight_kg<=gross_weight_kg),
  CHECK(volume_cbm>=0 AND package_count>=0)
);
CREATE INDEX customs_line_declaration ON customs_declaration_line(tenant_id,declaration_id,line_no);
CREATE INDEX customs_line_source ON customs_declaration_line(tenant_id,source_line_id);
CREATE INDEX customs_line_sku ON customs_declaration_line(tenant_id,sku_id);
CREATE INDEX customs_line_hs ON customs_declaration_line(tenant_id,hs_code) WHERE btrim(hs_code)<>'';

-- ---------------------------------------------------------------------------
-- 3. 三单据（报关单 / 装箱单 / 发票）—— 按版本链保存，**改单不覆盖旧版本**
-- ---------------------------------------------------------------------------
-- 改单后单据必须能取回「上一版报的是什么」，否则被退单时无法举证当时提交的内容。
-- 所以 status 允许 SUPERSEDED：旧版本保留，新版本递增 document_version。
--
-- content 是**生成时落库的单据内容快照**（jsonb 对象）：申报资料必须可复现，
-- 不能在读取时按当前明细重新拼一遍 —— 那样明细一改，历史单据就跟着变了。
CREATE TABLE customs_document (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  document_type varchar(24) NOT NULL CHECK(document_type IN (
    'CUSTOMS_DECLARATION','PACKING_LIST','COMMERCIAL_INVOICE')),
  document_no varchar(64) NOT NULL DEFAULT '',
  -- 版本：同一张报关单下同一类单据从 1 递增；UNIQUE(tenant_id,declaration_id,document_type,document_version)
  document_version integer NOT NULL DEFAULT 1 CHECK(document_version>0),
  currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency ~ '^[A-Z]{3}$'),
  total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_amount>=0),
  -- 单据内容快照（生成那一刻的字段，读取时不再重算）
  content jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','FINAL','SUPERSEDED')),
  supersedes_document_id uuid,
  issued_at timestamptz,
  prepared_by uuid,
  source_system varchar(32) NOT NULL DEFAULT 'CUSTOMS_DECLARATION',
  source_reference varchar(64) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,declaration_id,document_type,document_version),
  FOREIGN KEY(tenant_id,declaration_id) REFERENCES customs_declaration(tenant_id,id),
  FOREIGN KEY(tenant_id,supersedes_document_id) REFERENCES customs_document(tenant_id,id),
  CHECK(jsonb_typeof(content)='object'),
  CHECK(btrim(source_reference)<>'')
);
CREATE INDEX customs_doc_declaration ON customs_document(tenant_id,declaration_id,document_type,document_version);
CREATE INDEX customs_doc_status ON customs_document(tenant_id,status);

-- ---------------------------------------------------------------------------
-- 4. 报关行提交回执（一次提交 = 一行）
-- ---------------------------------------------------------------------------
-- **外部报关行接口未交付**（蓝图提到的「提交报关行/IOR」在本项目里没有可调用的
-- 外部系统与凭证），所以 submission_result 由人工回填并**如实标注录入方式**：
-- source_system='MANUAL_ENTRY' 表示这条回执是人抄进来的，不是机器对接回来的。
-- 将来接了真实报关行接口，由它写 source_system='BROKER_API'，结构不变。
--
-- UNIQUE(tenant_id,declaration_id,submission_round) 保证同一轮复核只提交一次。
CREATE TABLE customs_submission (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  -- 提交轮次 = review_round：改单后重新复核会进位，于是提交轮次天然区分
  submission_round integer NOT NULL CHECK(submission_round>0),
  document_id uuid,
  submission_reference varchar(64) NOT NULL DEFAULT '',
  broker_name varchar(200) NOT NULL DEFAULT '',
  -- 提交结果：受理 / 待定 / 已放行 / 被退单（放行与退单另有结果行逐条留痕）
  submission_result varchar(16) NOT NULL DEFAULT 'ACCEPTED' CHECK(submission_result IN (
    'ACCEPTED','PENDING','CLEARED','REJECTED')),
  submitted_at timestamptz NOT NULL,
  submitted_by uuid NOT NULL,
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL_ENTRY'
    CHECK(source_system IN ('MANUAL_ENTRY','BROKER_API')),
  source_reference varchar(64) NOT NULL,
  result_reason text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,declaration_id,submission_round),
  UNIQUE(tenant_id,source_system,source_reference),
  FOREIGN KEY(tenant_id,declaration_id) REFERENCES customs_declaration(tenant_id,id),
  FOREIGN KEY(tenant_id,document_id) REFERENCES customs_document(tenant_id,id),
  CHECK(btrim(source_reference)<>'')
);
CREATE INDEX customs_submission_declaration ON customs_submission(tenant_id,declaration_id,submitted_at DESC);

-- ---------------------------------------------------------------------------
-- 5. 报关结果回执（放行 / 退单 / 改单）—— 蓝图「接收放行、退单、改单结果」
-- ---------------------------------------------------------------------------
-- 三种结果都必须落审计（谁、何时、原结果、新结果、原因），所以每种结果一行，
-- 且带 from_result / to_result 与 actor_id / reason。
--
-- 结果只能挂在**已经提交过**的那一轮上（submission_id 指向 customs_submission），
-- 否则会出现「没提交过却有海关回执」这种不可能的记录。
CREATE TABLE customs_result (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  submission_id uuid NOT NULL,
  -- 结果类型：放行 / 退单 / 改单（蓝图点名的三种）
  result_type varchar(24) NOT NULL CHECK(result_type IN ('CLEARED','REJECTED','AMENDED')),
  from_result varchar(24) NOT NULL DEFAULT '' CHECK(from_result IN ('','PENDING','ACCEPTED','CLEARED','REJECTED','AMENDED')),
  to_result varchar(24) NOT NULL CHECK(to_result IN ('PENDING','ACCEPTED','CLEARED','REJECTED','AMENDED')),
  -- 海关/报关行给出的单号（放行通知书号、退单通知号等）
  result_reference varchar(64) NOT NULL DEFAULT '',
  -- **原因必填**：退单与改单没有原因事后无法追责；放行也要求给一句依据
  reason text NOT NULL,
  result_at timestamptz NOT NULL,
  actor_id uuid NOT NULL,
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL_ENTRY'
    CHECK(source_system IN ('MANUAL_ENTRY','BROKER_API','CUSTOMS_FEED')),
  source_reference varchar(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,declaration_id) REFERENCES customs_declaration(tenant_id,id),
  FOREIGN KEY(tenant_id,submission_id) REFERENCES customs_submission(tenant_id,id),
  CHECK(btrim(reason)<>''),
  CHECK(btrim(source_reference)<>'')
);
CREATE INDEX customs_result_declaration ON customs_result(tenant_id,declaration_id,result_at DESC);
CREATE INDEX customs_result_submission ON customs_result(tenant_id,submission_id);

-- ---------------------------------------------------------------------------
-- 6. 事件时间线（一次业务动作 = 一行，逐笔留痕）
-- ---------------------------------------------------------------------------
-- 复核、提交、放行、退单、改单、取消都要能逐笔回放。
-- 尤其「谁在什么时候复核了这张报关单」是蓝图那句「罚款由卖家承担」的责任落点。
CREATE TABLE customs_declaration_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  kind varchar(20) NOT NULL CHECK(kind IN (
    'CREATED','UPDATED','PENDING_REVIEW','REVIEWED','REVIEW_REJECTED','REVIEW_WITHDRAWN',
    'SUBMITTED','CLEARED','CUSTOMS_REJECTED','AMENDED','CANCELLED','DOCUMENT_GENERATED')),
  occurred_at timestamptz NOT NULL,
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  reason text NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,declaration_id) REFERENCES customs_declaration(tenant_id,id)
);
CREATE INDEX customs_event_declaration ON customs_declaration_event(tenant_id,declaration_id,occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 7. DB 级不变量
-- ---------------------------------------------------------------------------
-- 三条 BEFORE UPDATE 触发器**按名字字母序执行**（PostgreSQL 对同一时机的触发器按名称排序，
-- 没有声明顺序这种概念）。所以名字刻意带 1/2/3 序号，把「复核闸 → 复核齐备 → 三单据齐备」
-- 钉死成这个顺序：**没复核就提交**必须报「先复核」而不是被后面的「三单据不齐备」抢答 ——
-- 蓝图那句「报关数据错误导致的扣货与罚款由卖家承担」对应的正是第一个闸。
-- 7.1 状态与时间戳自洽 + 终态不可离开 + **人工复核闸（文件头第二节的硬约束）**
CREATE FUNCTION guard_customs_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior_status varchar(24); prior_round integer;
BEGIN
 prior_status := OLD.status; prior_round := OLD.review_round;

 -- ===== 人工复核闸：提交报关行之前必须有人工复核过 =====
 -- 判据是「本次要变 SUBMITTED」，而不是「当前状态是什么」：
 -- 用后者写成 (NEW.status='SUBMITTED' AND OLD.status<>'REVIEWED') 会在
 -- REVIEWED → SUBMITTED 的合法提交上误判，必须允许 REVIEWED → SUBMITTED。
 IF NEW.status = 'SUBMITTED' AND NEW.status <> prior_status THEN
  IF prior_status <> 'REVIEWED' THEN
   RAISE EXCEPTION '提交报关行前必须人工复核：当前状态「%」，只允许「已复核」提交。%',
    prior_status,
    CASE prior_status
      WHEN 'DRAFT' THEN '蓝图的硬约束：报关数据错误导致的扣货与罚款由卖家承担，所以必须先走复核'
      WHEN 'PENDING_REVIEW' THEN '还在待复核，复核通过后才能提交'
      WHEN 'CUSTOMS_REJECTED' THEN '被退单后必须退回草稿改，改完重新复核才能再次提交'
      ELSE '该状态不允许直接提交报关行'
    END USING ERRCODE='23514';
  END IF;
  IF NEW.reviewed_at IS NULL OR NEW.reviewed_by IS NULL THEN
   RAISE EXCEPTION '提交报关行前必须有人工复核痕迹（reviewed_at / reviewed_by）'
    USING ERRCODE='23514';
  END IF;
  -- 复核之后又改了内容（复核轮次被清空 = 改单回草稿了）也不许提交
  IF NEW.review_round = 0 THEN
   RAISE EXCEPTION '复核轮次为 0：不许把一张没复核过的报关单提交给报关行'
    USING ERRCODE='23514';
  END IF;
 END IF;

 -- 状态与时间戳自洽
 IF (NEW.status = 'SUBMITTED') <> (NEW.submitted_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与提交时间不自洽：只有已提交才可能已经送到报关行', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF (NEW.status = 'CLEARED') <> (NEW.cleared_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与放行时间不自洽', NEW.status USING ERRCODE='23514';
 END IF;
 IF (NEW.status = 'CUSTOMS_REJECTED') <> (NEW.customs_rejected_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与退单时间不自洽', NEW.status USING ERRCODE='23514';
 END IF;
 IF (NEW.status = 'CANCELLED') <> (NEW.cancelled_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与取消时间不自洽', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.status = 'CANCELLED' AND btrim(NEW.cancel_reason)='' THEN
  RAISE EXCEPTION '取消必须给原因：报关资料已生成过，取消后无法追责' USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'CANCELLED'
    AND (NEW.cancelled_at IS NOT NULL OR NEW.cancelled_by IS NOT NULL OR btrim(NEW.cancel_reason)>'') THEN
  RAISE EXCEPTION '状态「%」不是已取消，不允许带取消痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 -- 退单必须给原因：没有退单原因就无法改单
 IF NEW.status = 'CUSTOMS_REJECTED' AND btrim(NEW.customs_rejection_reason)='' THEN
  RAISE EXCEPTION '退单必须给原因：改单要按退单原因改，没有原因就无从改起' USING ERRCODE='23514';
 END IF;
 -- 已提交过就不该没有提交时间
 IF NEW.status IN ('CLEARED','CUSTOMS_REJECTED') AND NEW.submitted_at IS NULL THEN
  RAISE EXCEPTION '状态「%」必须有提交时间：没有提交过就不可能有海关回执', NEW.status
   USING ERRCODE='23514';
 END IF;
 -- 终态不许离开（判据是「旧状态是终态且新状态不同」，不能写成「新状态是 X」——
 -- 那会把合法的进入动作一起挡掉）
 IF TG_OP = 'UPDATE' AND prior_status IN ('CLEARED','CANCELLED') AND NEW.status <> prior_status THEN
  RAISE EXCEPTION '「%」是终态：% ，不允许改到「%」', prior_status,
   CASE prior_status
     WHEN 'CLEARED' THEN '货已清关放行，要修正已放行的申报资料请走改单（登记结果为改单）'
     ELSE '已取消的单不再接受任何动作'
   END,
   NEW.status USING ERRCODE='23514';
 END IF;
 -- 复核轮次与复核痕迹必须同存同灭：
 -- review_round 记的是**已完成**的复核轮次，每一轮都留下 reviewed_at/reviewed_by。
 -- 反向不成立就会留下「轮次 1 却没人复核过」的凭据，那是罚款追责时要被问的那一句。
 IF (NEW.review_round > 0) <> (NEW.reviewed_at IS NOT NULL) THEN
  RAISE EXCEPTION '复核轮次 % 与复核时间不自洽：有复核轮次就必须有复核人与复核时间',
   NEW.review_round USING ERRCODE='23514';
 END IF;
 IF (NEW.reviewed_at IS NOT NULL) <> (NEW.reviewed_by IS NOT NULL) THEN
  RAISE EXCEPTION '复核时间与复核人不成对：两者必须同时有或同时无' USING ERRCODE='23514';
 END IF;
 -- 「已通过复核」的四种状态（已复核 / 已提交 / 已放行 / 已改单）必须真的复核过。
 -- 复核退回(REJECTED)同样走完过一轮复核，所以它也要求轮次 > 0。
 -- CANCELLED **不在**这个列表里：一张还没送审的草稿也必须能被取消，
 -- 把取消绑到「已复核过」会让「草稿作废」这条最常见的路径直接不可用。
 IF NEW.status IN ('REVIEWED','SUBMITTED','CLEARED','AMENDED','REJECTED')
    AND NEW.review_round <= 0 THEN
  RAISE EXCEPTION '状态「%」必须已完成至少一轮人工复核', NEW.status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customs_1_state BEFORE INSERT OR UPDATE ON customs_declaration
 FOR EACH ROW EXECUTE FUNCTION guard_customs_state();

-- 7.2 复核通过时**逐行检查申报要素齐备**（HS 编码 / 申报要素 / 货值 / 重量 / 箱数）
--     这是「报关数据错误导致的扣货与罚款由卖家承担」的第二道闸：
--     第一道是状态机（没复核不许提交），这一道是复核本身有没有认真做。
CREATE FUNCTION guard_customs_review_completeness() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bad record;
BEGIN
 IF NEW.status <> 'REVIEWED' OR NEW.status = OLD.status THEN
  RETURN NEW;
 END IF;
 -- 至少要有一行明细
 IF NOT EXISTS (SELECT 1 FROM customs_declaration_line l
   WHERE l.tenant_id = NEW.tenant_id AND l.declaration_id = NEW.id) THEN
  RAISE EXCEPTION '报关单没有任何明细行，不允许复核通过：没有货物就没有申报'
   USING ERRCODE='23514';
 END IF;
 -- 用 = '{}'::jsonb 精确比较空对象（不是 jsonb_object_length：PG 没有这个函数，
-- 而 jsonb_object_keys 是集合返回函数、不能直接用在标量表达式里）。
-- 逐项缺失要**逐项报出来**，一次报一条会让运营反复提交试错
 SELECT l.line_no,
   CASE WHEN btrim(l.hs_code)='' THEN 'HS 编码' END AS miss_hs,
   CASE WHEN l.declaration_element_status NOT IN ('MANUAL_COMPLETE','MODULE_3_7_1_1_COMPLETE')
        THEN '申报要素未标齐备' END AS miss_elem,
   CASE WHEN l.declaration_elements = '{}'::jsonb THEN '申报要素内容为空' END AS miss_elem_body,
   CASE WHEN l.declared_value <= 0 THEN '申报货值未填或为 0' END AS miss_value,
   CASE WHEN l.gross_weight_kg <= 0 THEN '毛重未填或为 0' END AS miss_gross,
   CASE WHEN l.package_count <= 0 THEN '箱数未填或为 0' END AS miss_pkg,
   CASE WHEN btrim(l.uom)='' THEN '计量单位为空' END AS miss_uom
  INTO bad
 FROM customs_declaration_line l
 WHERE l.tenant_id = NEW.tenant_id AND l.declaration_id = NEW.id
   AND (btrim(l.hs_code)=''
     OR l.declaration_element_status NOT IN ('MANUAL_COMPLETE','MODULE_3_7_1_1_COMPLETE')
     OR l.declaration_elements = '{}'::jsonb
     OR l.declared_value <= 0
     OR l.gross_weight_kg <= 0
     OR l.package_count <= 0
     OR btrim(l.uom)='')
 ORDER BY l.line_no LIMIT 1;
 -- 判据用 **FOUND**（SELECT INTO 必定会设置它），与本文件其余触发器以及
 -- V80 的 `IF NOT FOUND THEN` 同一条写法。两种判据在 PG 16 上等价，
 -- 统一用 FOUND 是为了读代码时不必回想 record 变量的 IS NULL 语义。
 IF FOUND THEN
  RAISE EXCEPTION '第 % 行申报资料不齐备，不允许复核通过：缺 % % % % % %',
   bad.line_no,
   coalesce(bad.miss_hs,''), coalesce(bad.miss_elem,''), coalesce(bad.miss_elem_body,''),
   coalesce(bad.miss_value,''), coalesce(bad.miss_gross,''), coalesce(bad.miss_pkg,'')
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customs_2_review_completeness BEFORE UPDATE ON customs_declaration
 FOR EACH ROW EXECUTE FUNCTION guard_customs_review_completeness();

-- 7.3 提交前**三单据必须齐备且定稿**（蓝图输出：报关单 + 装箱单 + 发票）
CREATE FUNCTION guard_customs_documents() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE missing text; finalized integer; want integer;
BEGIN
 IF NEW.status <> 'SUBMITTED' OR NEW.status = OLD.status THEN
  RETURN NEW;
 END IF;
 -- 三类单据都必须存在
 SELECT string_agg(v.kind, '、') INTO missing FROM unnest(
   ARRAY['CUSTOMS_DECLARATION','PACKING_LIST','COMMERCIAL_INVOICE']::varchar[]) AS v(kind)
  WHERE NOT EXISTS (SELECT 1 FROM customs_document d
    WHERE d.tenant_id = NEW.tenant_id AND d.declaration_id = NEW.id AND d.document_type = v.kind);
 IF missing IS NOT NULL THEN
  RAISE EXCEPTION '提交报关行前三单据不齐备，缺：%。缺哪份就不能提交 —— 少提交一份等于申报不完整',
   missing USING ERRCODE='23514';
 END IF;
 -- 且都必须是最新版本的 FINAL（旧版本被 SUPERSEDED 后不能拿来提交）
 SELECT count(*) INTO want FROM unnest(
   ARRAY['CUSTOMS_DECLARATION','PACKING_LIST','COMMERCIAL_INVOICE']::varchar[]) AS v(kind)
  WHERE EXISTS (SELECT 1 FROM customs_document d
    WHERE d.tenant_id = NEW.tenant_id AND d.declaration_id = NEW.id AND d.document_type = v.kind
      AND d.document_version = (SELECT max(d2.document_version) FROM customs_document d2
        WHERE d2.tenant_id = NEW.tenant_id AND d2.declaration_id = NEW.id AND d2.document_type = d.document_type));
 SELECT count(*) INTO finalized FROM unnest(
   ARRAY['CUSTOMS_DECLARATION','PACKING_LIST','COMMERCIAL_INVOICE']::varchar[]) AS v(kind)
  WHERE EXISTS (SELECT 1 FROM customs_document d
    WHERE d.tenant_id = NEW.tenant_id AND d.declaration_id = NEW.id AND d.document_type = v.kind
      AND d.status = 'FINAL'
      AND d.document_version = (SELECT max(d2.document_version) FROM customs_document d2
        WHERE d2.tenant_id = NEW.tenant_id AND d2.declaration_id = NEW.id AND d2.document_type = d.document_type));
 IF finalized <> want THEN
  RAISE EXCEPTION '提交报关行前三单据必须全部定稿（FINAL）：%/% 类最新版本已定稿。定稿前的单据不是申报资料',
   finalized, want USING ERRCODE='23514';
 END IF;
 -- 三类单据的币种必须与单头申报币种一致：币种不一致的发票提交上去是申报不实
 IF EXISTS (SELECT 1 FROM customs_document d
   WHERE d.tenant_id = NEW.tenant_id AND d.declaration_id = NEW.id
     AND d.document_type = 'COMMERCIAL_INVOICE' AND d.currency <> NEW.declaration_currency) THEN
  RAISE EXCEPTION '商业发票币种「%」与报关单申报币种「%」不一致',
   (SELECT d.currency FROM customs_document d WHERE d.tenant_id = NEW.tenant_id
      AND d.declaration_id = NEW.id AND d.document_type = 'COMMERCIAL_INVOICE'
      AND d.document_version = (SELECT max(d2.document_version) FROM customs_document d2
        WHERE d2.tenant_id = NEW.tenant_id AND d2.declaration_id = NEW.id
        AND d2.document_type = 'COMMERCIAL_INVOICE')),
   NEW.declaration_currency USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customs_3_documents BEFORE UPDATE ON customs_declaration
 FOR EACH ROW EXECUTE FUNCTION guard_customs_documents();

-- 7.4 货值与汇率快照自洽（文件头第三节）：折算额必须等于原币额 × 汇率
--     延迟约束触发器：头与明细在同一个事务里先后写，即时触发器会对着半成品报错。
CREATE FUNCTION guard_customs_rate_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE line_sum numeric; expected_base numeric;
BEGIN
 -- 只在明细可能已齐备之后校验（草稿态允许汇总尚未回填）
 IF NEW.status IN ('DRAFT','PENDING_REVIEW','REJECTED') THEN
  RETURN NULL;
 END IF;
 SELECT coalesce(sum(l.declared_value),0) INTO line_sum FROM customs_declaration_line l
  WHERE l.tenant_id = NEW.tenant_id AND l.declaration_id = NEW.id;
 -- 单头申报货值必须等于明细货值合计：分头申报与分行货值对不上就是申报不实
 IF NEW.declaration_value <> line_sum THEN
  RAISE EXCEPTION '单头申报货值 % 与明细货值合计 % 不一致：申报资料必须与明细逐项对得上',
   NEW.declaration_value, line_sum USING ERRCODE='23514';
 END IF;
 expected_base := round(line_sum * NEW.exchange_rate, 4);
 IF NEW.declaration_value_base <> expected_base THEN
  RAISE EXCEPTION '折算本位币金额 % ≠ 明细货值合计 % × 汇率 % = %：汇率快照必须自洽可举证',
   NEW.declaration_value_base, line_sum, NEW.exchange_rate, expected_base USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER customs_rate_snapshot
 AFTER INSERT OR UPDATE ON customs_declaration
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_customs_rate_snapshot();

-- 7.5 明细行的来源必须与单头一致，且属于该发运单
CREATE FUNCTION guard_customs_line_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; src record;
BEGIN
 SELECT source_shipment_id INTO head FROM customs_declaration
  WHERE tenant_id = NEW.tenant_id AND id = NEW.declaration_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '明细行指向的报关单 % 不存在', NEW.declaration_id USING ERRCODE='23503';
 END IF;
 SELECT l.shipment_id, l.line_no, l.sku_id, l.planned_quantity, l.shipped_quantity, l.declared_item_name
   INTO src FROM first_mile_shipment_line l
  WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.source_line_id;
 IF src IS NULL THEN
  RAISE EXCEPTION '明细行引用的发运明细行 % 不存在或不属于本租户', NEW.source_line_id
   USING ERRCODE='23503';
 END IF;
 IF src.shipment_id <> head.source_shipment_id THEN
  RAISE EXCEPTION '明细行引用的发运明细属于发运单 %，与本单声明的发运单 % 不是同一张',
   src.shipment_id, head.source_shipment_id USING ERRCODE='23514';
 END IF;
 IF src.sku_id <> NEW.sku_id THEN
  RAISE EXCEPTION '明细行 SKU % 与发运明细 SKU % 不是同一个', NEW.sku_id, src.sku_id
   USING ERRCODE='23514';
 END IF;
 -- 申报数量不得超过发运明细的已发出量：未发出的货不该出现在报关单上
 IF NEW.quantity > coalesce(src.shipped_quantity, src.planned_quantity) THEN
  RAISE EXCEPTION '申报数量 % 超过该发运明细的发出量 %：未发出的货不能报关',
   NEW.quantity, coalesce(src.shipped_quantity, src.planned_quantity) USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customs_line_source BEFORE INSERT OR UPDATE ON customs_declaration_line
 FOR EACH ROW EXECUTE FUNCTION guard_customs_line_source();

-- 7.6 明细行数不超过发运单明细行数（同一批货不能拆成超出实发的申报行数）
CREATE FUNCTION guard_customs_line_count() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE src_lines integer; decl_lines integer;
BEGIN
 SELECT count(*) INTO src_lines FROM first_mile_shipment_line l
  WHERE l.tenant_id = NEW.tenant_id AND l.shipment_id = NEW.source_shipment_id;
 SELECT count(*) INTO decl_lines FROM customs_declaration_line l
  WHERE l.tenant_id = NEW.tenant_id AND l.declaration_id = NEW.id;
 IF decl_lines > src_lines THEN
  RAISE EXCEPTION '报关单明细 % 行超过发运单明细 % 行：同一批货不能拆出比实发更多的申报行',
   decl_lines, src_lines USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER customs_line_count
 AFTER INSERT OR UPDATE ON customs_declaration_line
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_customs_line_count();

-- 7.7 单据改版链自洽：supersedes 必须指向**同类型的前一版本**，不许跨类型挂
CREATE FUNCTION guard_customs_document_chain() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prev record;
BEGIN
 IF NEW.supersedes_document_id IS NULL THEN
  -- 首版必须是版本 1
  IF NEW.document_version <> 1 THEN
   RAISE EXCEPTION '首版单据版本号必须是 1，收到 %', NEW.document_version USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF NEW.document_version = 1 THEN
  RAISE EXCEPTION '单据版本号为 1 时不允许带 supersedes：首版没有前一版' USING ERRCODE='23514';
 END IF;
 SELECT d.document_type, d.document_version, d.declaration_id INTO prev
   FROM customs_document d WHERE d.tenant_id = NEW.tenant_id AND d.id = NEW.supersedes_document_id;
 IF prev IS NULL THEN
  RAISE EXCEPTION '单据要取代的单据 % 不存在或不属于本租户', NEW.supersedes_document_id
   USING ERRCODE='23503';
 END IF;
 IF prev.declaration_id <> NEW.declaration_id THEN
  RAISE EXCEPTION '单据要取代的单据属于另一张报关单' USING ERRCODE='23514';
 END IF;
 IF prev.document_type <> NEW.document_type THEN
  RAISE EXCEPTION '单据要取代的前一版类型是「%」，与本版「%」不同：改版链不许跨类型',
   prev.document_type, NEW.document_type USING ERRCODE='23514';
 END IF;
 IF prev.document_version <> NEW.document_version - 1 THEN
  RAISE EXCEPTION '单据版本 % 必须接在版本 % 之后：改版链不能跳号',
   NEW.document_version, prev.document_version USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customs_document_chain BEFORE INSERT OR UPDATE ON customs_document
 FOR EACH ROW EXECUTE FUNCTION guard_customs_document_chain();

-- 7.8 结果回执必须挂在**已提交**的那一轮上
CREATE FUNCTION guard_customs_result_submission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s record;
BEGIN
 SELECT sub.declaration_id, sub.submission_round, sub.submitted_at INTO s
   FROM customs_submission sub WHERE sub.tenant_id = NEW.tenant_id AND sub.id = NEW.submission_id;
 IF s IS NULL THEN
  RAISE EXCEPTION '结果回执指向的提交记录 % 不存在或不属于本租户', NEW.submission_id
   USING ERRCODE='23503';
 END IF;
 IF s.declaration_id <> NEW.declaration_id THEN
  RAISE EXCEPTION '结果回执指向的提交属于另一张报关单' USING ERRCODE='23514';
 END IF;
 -- 结果时间不得早于提交时间：先收到结果再提交在物理上不可能
 IF NEW.result_at < s.submitted_at THEN
  RAISE EXCEPTION '结果时间 % 早于提交时间 %：报关行不可能在提交之前就有回执',
   NEW.result_at, s.submitted_at USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customs_result_submission BEFORE INSERT ON customs_result
 FOR EACH ROW EXECUTE FUNCTION guard_customs_result_submission();

-- 7.9 改单计数必须与结果回执一致（改单次数不是页面上手填的数字）
CREATE FUNCTION guard_customs_amendment_count() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE counted integer;
BEGIN
 IF NEW.status = 'DRAFT' THEN
  RETURN NULL; -- 退单后回草稿时计数保持不变
 END IF;
 SELECT count(*) INTO counted FROM customs_result r
  WHERE r.tenant_id = NEW.tenant_id AND r.declaration_id = NEW.id AND r.result_type = 'AMENDED';
 IF NEW.amendment_count <> counted THEN
  RAISE EXCEPTION '改单计数 % 与改单结果回执条数 % 不一致：改单次数必须来自结果记录而不是手填',
   NEW.amendment_count, counted USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER customs_amendment_count
 AFTER INSERT OR UPDATE ON customs_declaration
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_customs_amendment_count();

-- 7.10 已复核之后不允许改明细（否则「复核」就变成走过场）
--     撤回复核（REVIEWED → PENDING_REVIEW）或退单回草稿后才允许再改。
--     CUSTOMS_REJECTED 也在封禁列表里：被退单后必须**显式退回草稿**才能改，
--     否则「退单原因还没看就先把内容改了」会成为真实且常见的操作事故。
CREATE FUNCTION guard_customs_lock_lines() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM customs_declaration d
   WHERE d.tenant_id = NEW.tenant_id AND d.id = NEW.declaration_id
     AND d.status IN ('REVIEWED','SUBMITTED','CLEARED','AMENDED','CUSTOMS_REJECTED')) THEN
  RAISE EXCEPTION '报关单处于「%」状态，不允许直接改明细：要让改动被看到，请先撤回复核或退回草稿再改',
   (SELECT d2.status FROM customs_declaration d2
     WHERE d2.tenant_id = NEW.tenant_id AND d2.id = NEW.declaration_id) USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customs_lock_lines BEFORE INSERT OR UPDATE ON customs_declaration_line
 FOR EACH ROW EXECUTE FUNCTION guard_customs_lock_lines();

-- ---------------------------------------------------------------------------
-- 8. 热读路径索引（围绕「报关单清单」与「按发运单查报关单」两条真实查询）
-- ---------------------------------------------------------------------------
CREATE INDEX customs_declaration_list ON customs_declaration(tenant_id,status,updated_at DESC);
CREATE INDEX customs_declaration_source ON customs_declaration(tenant_id,source_shipment_id);
CREATE INDEX customs_declaration_country ON customs_declaration(tenant_id,destination_country_code,created_at DESC);
CREATE INDEX customs_declaration_trade_mode ON customs_declaration(tenant_id,trade_mode);
CREATE INDEX customs_declaration_review ON customs_declaration(tenant_id,reviewed_at) WHERE reviewed_at IS NOT NULL;
CREATE INDEX customs_declaration_pending ON customs_declaration(tenant_id,created_at)
  WHERE status IN ('PENDING_REVIEW','SUBMITTED');

-- ---------------------------------------------------------------------------
-- 9. 多租户：RLS + 授权（与 V1 第 9 节、V63 第 5 节、V70 第 10 节、V80 第 7 节同一条纪律）
-- ---------------------------------------------------------------------------
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'customs_declaration','customs_declaration_line','customs_document',
   'customs_submission','customs_result','customs_declaration_event'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON customs_declaration, customs_declaration_line,
  customs_document, customs_submission, customs_result, customs_declaration_event TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建单会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE customs_declaration_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V63、V70、V80 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;