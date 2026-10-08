-- 10.5.2.1 贸易单证。
--
-- 蓝图 10-logistics-and-freight.md 10.5.2.1 原文：
--   职责：管理与生成贸易相关单证：商业发票、装箱单、原产地证（RCEP/CO/FTA 优惠）、提单/空运单、
--         进口许可与认证文件，按目的地国家规则校验完整性。
--   输入：发货明细；目的地国家单证要求；16 合规的认证状态。
--   输出：单证文件 + 完整性校验结果 + 缺失提醒。
--   关系：上游 = 10.5.1.1、16 合规；下游 = 报关行/目的仓提交。**FTA 优惠原产地证能显著降低关税**，
--         其适用条件（尤其是"实质性改变"）需要业务确认，系统只负责按规则校验与留档。
--
-- ===========================================================================
-- 一、本迁移**只新建表，不 ALTER 任何既有表** —— 并行车道的零回归面
-- ===========================================================================
-- 上游 10.5.1.1 报关单（V85）与下游 10.5.1.3 关税（V90）都在本服务，本迁移对它们**只读**：
-- 不 ADD COLUMN、不 SET NOT NULL、不加 CHECK、不碰触发器、不加外键指向它们的**被改列**。
-- 唯一的外键是 customs_declaration(tenant_id,id) —— 建在**复合唯一键**上的只读引用，
-- 与 V90 对 V85 的做法同一条纪律。跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 二、「实质性改变」是**业务判断**，系统不代做结论 —— 落成可执行的四件事
-- ===========================================================================
-- 蓝图那句加粗是**硬边界**：「其适用条件（尤其是"实质性改变"）需要业务确认，
-- 系统只负责按规则校验与留档」。所以本迁移**刻意不实现任何实质性改变判定逻辑**——
-- 任何 attempt_to_conclude / auto_judge 之类的判定都是越界：判错了会直接变成多缴/少缴关税。
--
-- 系统只做这四件，每件都落库：
--   ① 规则可配置：trade_origin_preference_rule 存方案的适用条件口径
--      （substantive_change_criterion 是**条件描述**，不是判定式）与 requires_business_confirmation。
--   ② 校验：校验的是**流程**而不是法律事实 ——
--      「这份优惠原产地证有没有留下业务确认痕迹（confirmed_at/confirmed_by）」
--      「有没有待确认项」；DB 触发器 guard_trade_3_submit 钉死「有优惠主张但没业务确认 → 不许提交」。
--   ③ 留档：trade_document.substantive_change_* 全部落库并进事件时间线，谁在何时确认、依据什么写清楚。
--   ④ 显式标出待确认项：trade_document_reminder 用 reminder_type='UNCONFIRMED_SUBSTANTIVE_CHANGE'
--      且 severity='BLOCKER' 把「待业务确认」显式摆到界面上，而不是让界面显示「校验通过」。
--
-- 换句话说：本模块能回答「这份优惠有没有人确认过、依据是什么、什么时候确认的」，
-- **不能**回答「这份优惠在法律上成不成立」。后者是业务/法务的活，系统不冒充。
--
-- ===========================================================================
-- 三、16 合规的认证状态**上游未交付** —— 租户可维护的替代来源 + 来源字段如实标注
-- ===========================================================================
-- 本车道对集成头实查：backend/services/ 下只有 catalog / channel / identity / trade / warehouse，
-- **没有 product_compliance**，16 合规与 3.7.1.1 一样未交付。处理方式与 10.5.1.1 / 10.5.1.3 一致：
--   * **不建**任何「16 合规模块的镜像表」（建了就是假的上游，且会成为双 writer）；
--   * 建 trade_certification_status —— 它是**本租户自己维护的认证状态登记**，
--     每行都带 certification_source（'TENANT_MAINTAINED' / 'MODULE_16'）与 source_reference，
--     把「这条认证状态是人登记的还是 16 合规推来的」如实记下来；
--   * 完整性校验读它，并把「认证过期 / 认证被撤销」报成缺失提醒（不静默当成齐备）；
--   * 16 合规交付后由它回填并把来源改成 MODULE_16，**不需要改结构**。
--
-- ===========================================================================
-- 四、目的地国家单证要求与单证模板**真实落库**，不是代码里的常量
-- ===========================================================================
-- 「按目的地国家规则校验完整性」是本模块的核心动作，规则必须是**租户可维护的数据**：
-- 写死成 Java 常量或 CHECK 词表就等于把海关要求抄成第二份且必然漂移
-- （同一句「目的国要求哪些单证」在代码与运营手里会各说各话）。
-- trade_document_rule 存国家 × 单证类型 × 必备程度；
-- trade_document_template 存「这类单证长什么样」的区块与字段定义。
--
-- ===========================================================================
-- 五、金额与数量
-- ===========================================================================
-- 金额 numeric(19,4)、数量 numeric(19,6)、汇率 numeric(20,10)，全程禁浮点。
-- 结算口径统一 HALF_UP 到分（Java setScale(2,HALF_UP) 与 DB round(x,2) 两处一致）。
--
-- 事件时间线与校验留痕是 append-only 的举证材料：trade_document_check / trade_document_event
-- 都不给 DELETE；trade_document 的历史版本走 SUPERSEDED 而不是删。

CREATE SEQUENCE trade_document_set_code_seq START 1;

-- ===========================================================================
-- 1. 目的地国家单证要求规则（租户可维护的真实数据）
-- ===========================================================================
-- requirement_level 三档：
--   MANDATORY   —— 必备，缺了不许提交
--   CONDITIONAL —— 有条件必备，condition_expression 写清触发条件（文本口径，不是可执行表达式：
--                  把它做成可执行表达式等于让运营在无评审的情况下定义报关规则）
--   OPTIONAL    —— 有则更好，不阻断
CREATE TABLE trade_document_rule (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 目的国/地区（ISO 3166-1 alpha-2，varchar(2) 与 V85 的 destination_country_code 同口径）
  country_code varchar(2) NOT NULL,
  document_kind varchar(32) NOT NULL CHECK(document_kind IN (
    'COMMERCIAL_INVOICE','PACKING_LIST','CERTIFICATE_OF_ORIGIN','RCEP_CERTIFICATE_OF_ORIGIN',
    'BILL_OF_LADING','AIR_WAYBELL','IMPORT_LICENSE','CERTIFICATION_DOC','OTHER')),
  requirement_level varchar(16) NOT NULL CHECK(requirement_level IN ('MANDATORY','CONDITIONAL','OPTIONAL')),
  -- CONDITIONAL 时的适用条件描述。人读口径，不执行。
  condition_expression text NOT NULL DEFAULT '',
  -- 是否必须附带上游 16 合规的认证状态才算齐备（进口许可/认证文件类常见）
  requires_certification boolean NOT NULL DEFAULT false,
  -- 规则来源：人维护 / 16 合规推来 / 海关数据源。默认人维护，不假装有海关直连。
  rule_source varchar(32) NOT NULL DEFAULT 'TENANT_MAINTAINED'
    CHECK(rule_source IN ('TENANT_MAINTAINED','MODULE_16','CUSTOMS_FEED')),
  source_reference varchar(64) NOT NULL DEFAULT '',
  -- 有效期：NULL 表示长期有效
  valid_from date NOT NULL DEFAULT CURRENT_DATE,
  valid_to date,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,country_code,document_kind),
  CHECK(length(btrim(country_code))=2),
  CHECK(valid_to IS NULL OR valid_to>=valid_from)
);

-- ===========================================================================
-- 2. 单证模板（单证「长什么样」的字段/区块定义，租户可维护）
-- ===========================================================================
-- country_code 为空 = 通用模板；给了国家 = 该国专用模板覆盖通用模板。
-- template_body 是区块与字段定义（jsonb 对象）：**刻意不写死字段名数组** ——
-- 各目的国要求的抬头与栏目不一样，写死等于把各国的单证版式抄进代码且必然漂移。
-- 有效期内取「国家专用 > 通用」的优先级，同层级取版本号最大的。
CREATE TABLE trade_document_template (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  template_code varchar(64) NOT NULL,
  document_kind varchar(32) NOT NULL CHECK(document_kind IN (
    'COMMERCIAL_INVOICE','PACKING_LIST','CERTIFICATE_OF_ORIGIN','RCEP_CERTIFICATE_OF_ORIGIN',
    'BILL_OF_LADING','AIR_WAYBELL','IMPORT_LICENSE','CERTIFICATION_DOC','OTHER')),
  -- 空串 = 通用模板（不是 NULL：NULL 与空串两态会让「这模板属于哪个国家」永远有歧义）
  country_code varchar(2) NOT NULL DEFAULT '',
  template_version integer NOT NULL DEFAULT 1 CHECK(template_version>0),
  currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency ~ '^[A-Z]{3}$'),
  -- 区块与字段定义（生成单证内容时的骨架）
  template_body jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','RETIRED')),
  supersedes_template_id uuid,
  effective_from date NOT NULL DEFAULT CURRENT_DATE,
  effective_to date,
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,template_code,template_version),
  FOREIGN KEY(tenant_id,supersedes_template_id) REFERENCES trade_document_template(tenant_id,id),
  CHECK(btrim(template_code)<>''),
  -- 通用模板的 country_code 必须是空串；有国家的必须正好两位
  CHECK((btrim(country_code)='' AND length(btrim(country_code))=0)
        OR length(btrim(country_code))=2),
  CHECK(jsonb_typeof(template_body)='object'),
  CHECK(effective_to IS NULL OR effective_to>=effective_from)
);
CREATE INDEX trade_template_lookup
  ON trade_document_template(tenant_id,document_kind,status,effective_from DESC);
CREATE INDEX trade_template_country
  ON trade_document_template(tenant_id,country_code) WHERE btrim(country_code)<>'';

-- ===========================================================================
-- 3. 认证状态登记（16 合规未交付 → 租户可维护的替代来源，见文件头第三节）
-- ===========================================================================
CREATE TABLE trade_certification_status (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 认证/许可证类型（CE / FDA / FCC / UKCA / 危包证 …），自由码 + 描述
  certification_code varchar(64) NOT NULL,
  certification_name varchar(200) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'VALID' CHECK(status IN (
    'VALID','PENDING','EXPIRED','REVOKED','NOT_REQUIRED')),
  certificate_no varchar(64) NOT NULL DEFAULT '',
  issuing_body varchar(200) NOT NULL DEFAULT '',
  issued_on date,
  expires_on date,
  -- 认证文件本身的存放定位（不是文件内容：单证生成时只引用定位键）
  document_ref varchar(200) NOT NULL DEFAULT '',
  -- 来源标记：人登记 vs 16 合规推来。**这是本表最重要的一列** ——
  -- 没有它就分不清「这条认证状态是系统知道的」还是「运营自己写上去的」。
  certification_source varchar(32) NOT NULL DEFAULT 'TENANT_MAINTAINED'
    CHECK(certification_source IN ('TENANT_MAINTAINED','MODULE_16')),
  source_reference varchar(64) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,sku_id,certification_code),
  CHECK(btrim(certification_code)<>''),
  -- VALID 必须有有效期与签发日：一张没有有效期的认证在目的国海关眼里等于没有
  CHECK(status<>'VALID' OR (issued_on IS NOT NULL AND expires_on IS NOT NULL)),
  CHECK(expires_on IS NULL OR issued_on IS NULL OR expires_on>=issued_on)
);
CREATE INDEX trade_certification_sku ON trade_certification_status(tenant_id,sku_id);
CREATE INDEX trade_certification_expiring
  ON trade_certification_status(tenant_id,expires_on) WHERE status='VALID';
CREATE INDEX trade_certification_blocking
  ON trade_certification_status(tenant_id,status) WHERE status IN ('EXPIRED','REVOKED','PENDING');

-- ===========================================================================
-- 4. FTA 优惠原产地证规则配置（见文件头第二节：只配置口径，不做判定）
-- ===========================================================================
-- substantive_change_criterion 存的是**条件描述**（人读口径，如「非原产材料税则号改变」、
-- 「税则号四位改变且增值达到一定比例」），**不是**可执行判定式，也不写死成任何自动结论。
-- requires_business_confirmation 默认 true：优惠主张默认必须有人签字确认，
-- 运营可以显式关掉，但那一笔操作本身会被事件时间线记下来（谁在什么时候关掉了这道闸）。
CREATE TABLE trade_origin_preference_rule (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 优惠方案（RCEP / CHINA_AUS / CHINA_NZ / CHINA_CHILE / CHINA_ICE / CHINA_SWIS / CHINA_KAZ / ASEAN_ATC）
  scheme varchar(24) NOT NULL,
  destination_country_code varchar(2) NOT NULL,
  -- 适用产品范围：ALL 全部，或 HS 前缀（最多 4 位，与 10.5.1.3 的税率表前缀口径一致）
  product_scope varchar(16) NOT NULL DEFAULT 'ALL' CHECK(product_scope IN ('ALL','HS_PREFIX')),
  hs_prefix varchar(8) NOT NULL DEFAULT '',
  -- 方案的适用条件口径描述（**描述**，不是判定式）
  preference_criteria text NOT NULL DEFAULT '',
  -- 实质性改变的适用条件口径描述（同上）
  substantive_change_criterion text NOT NULL DEFAULT '',
  -- 默认必须业务确认。关掉它需要显式写 false 并留痕。
  requires_business_confirmation boolean NOT NULL DEFAULT true,
  valid_from date NOT NULL DEFAULT CURRENT_DATE,
  valid_to date,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  rule_source varchar(32) NOT NULL DEFAULT 'TENANT_MAINTAINED'
    CHECK(rule_source IN ('TENANT_MAINTAINED','MODULE_16','CUSTOMS_FEED')),
  source_reference varchar(64) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,scheme,destination_country_code,product_scope,hs_prefix),
  CHECK(length(btrim(destination_country_code))=2),
  CHECK(product_scope='ALL' OR length(btrim(hs_prefix))>0),
  CHECK(product_scope='ALL' OR length(btrim(hs_prefix))<=4),
  CHECK(valid_to IS NULL OR valid_to>=valid_from)
);
CREATE INDEX trade_origin_rule_lookup
  ON trade_origin_preference_rule(tenant_id,destination_country_code,status,scheme);

-- ===========================================================================
-- 5. 单证包头（一票报关单备一套单证）
-- ===========================================================================
-- 状态机九态。DRAFT → 待复核 ⇄ 已复核/复核退回 → 已提交 → 已接收/被退回；草稿可取消。
-- 与 10.5.1.1 同一条纪律：**提交前必须人工复核通过**，而且必须完整性校验 PASS。
CREATE TABLE trade_document_set (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  set_code varchar(32) NOT NULL,
  -- ===== 来源：10.5.1.1 报关单（V85，本服务 → 复合外键）=====
  source_declaration_id uuid NOT NULL,
  source_declaration_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_id uuid NOT NULL,
  -- ===== 收发货与运输（从报关单固化下来的快照，备证资料要能自证是谁卖给谁）=====
  exporter_name varchar(200) NOT NULL,
  exporter_country_code varchar(2) NOT NULL DEFAULT 'CN',
  consignee_name varchar(200) NOT NULL,
  consignee_country_code varchar(2) NOT NULL,
  destination_country_code varchar(2) NOT NULL,
  transport_mode varchar(16) NOT NULL CHECK(transport_mode IN ('SEA','AIR','RAIL','EXPRESS')),
  bill_of_lading_no varchar(64) NOT NULL DEFAULT '',
  -- ===== 金额口径 =====
  document_currency char(3) NOT NULL DEFAULT 'USD' CHECK(document_currency ~ '^[A-Z]{3}$'),
  total_declared_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_declared_value>=0),
  -- ===== 汇总（由单证与校验聚合，与它们并发写 → 延迟约束在提交时核对）=====
  total_document_count integer NOT NULL DEFAULT 0,
  required_document_count integer NOT NULL DEFAULT 0,
  missing_document_count integer NOT NULL DEFAULT 0,
  pending_business_count integer NOT NULL DEFAULT 0,
  -- 最近一次完整性校验的结论（提交闸直接读它）
  latest_check_id uuid,
  -- varchar(32)：'PENDING_BUSINESS_CONFIRMATION' 是 28 个字符，24 装不下。
  -- 刻意留 32 而不是刚好 28：CHECK 词表以后加值不该先撞到列宽。
  latest_check_result varchar(32) NOT NULL DEFAULT 'NOT_CHECKED' CHECK(latest_check_result IN (
    'NOT_CHECKED','PASS','FAIL','PENDING_BUSINESS_CONFIRMATION')),
  latest_check_at timestamptz,
  -- ===== 状态机 =====
  status varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
    'DRAFT','UNDER_REVIEW','APPROVED','REJECTED','SUBMITTED',
    'SENT_BACK','ACKNOWLEDGED','CANCELLED')),
  -- 人工复核痕迹：提交前必须有人看过（与 10.5.1.1 同一条硬约束的同一条理由）
  review_round integer NOT NULL DEFAULT 0 CHECK(review_round>=0),
  reviewed_at timestamptz,
  reviewed_by uuid,
  review_notes text NOT NULL DEFAULT '',
  -- 提交与接收
  submitted_at timestamptz,
  submitted_by uuid,
  -- 下游接收方：报关行 / 目的仓
  submitted_to varchar(200) NOT NULL DEFAULT '',
  submission_reference varchar(64) NOT NULL DEFAULT '',
  acknowledged_at timestamptz,
  acknowledgement_reference varchar(64) NOT NULL DEFAULT '',
  sent_back_at timestamptz,
  sent_back_reason text NOT NULL DEFAULT '',
  -- 复核退回原因必填：没有原因就无从改起
  rejected_reason text NOT NULL DEFAULT '',
  cancelled_at timestamptz,
  cancelled_by uuid,
  cancel_reason varchar(200) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,set_code),
  -- 一票报关单只备一套单证（单证版本走版本链，不靠再造一套解决）
  UNIQUE(tenant_id,source_declaration_id),
  FOREIGN KEY(tenant_id,source_declaration_id) REFERENCES customs_declaration(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(set_code)<>''),
  CHECK(btrim(exporter_name)<>''),
  CHECK(btrim(consignee_name)<>''),
  CHECK(length(btrim(exporter_country_code))=2),
  CHECK(length(btrim(consignee_country_code))=2),
  CHECK(length(btrim(destination_country_code))=2),
  -- 目的国与收货方国家必须一致：单证抬头上的收货国与备证目的国对不上，海关直接退
  CHECK(consignee_country_code=destination_country_code),
  CHECK(total_document_count>=0 AND required_document_count>=0
        AND missing_document_count>=0 AND pending_business_count>=0),
  -- 「待业务确认」不可能多于「单证总数」，否则汇总口径本身就错了
  CHECK(pending_business_count<=total_document_count)
);
CREATE INDEX trade_set_list ON trade_document_set(tenant_id,status,updated_at DESC);
CREATE INDEX trade_set_country ON trade_document_set(tenant_id,destination_country_code,created_at DESC);
CREATE INDEX trade_set_declaration ON trade_document_set(tenant_id,source_declaration_id);
CREATE INDEX trade_set_pending ON trade_document_set(tenant_id,created_at)
  WHERE status IN ('UNDER_REVIEW','SUBMITTED');

-- ===========================================================================
-- 6. 单证本体（版本链，改版不覆盖历史）
-- ===========================================================================
-- content 是**生成时落库的单证内容快照**：备证资料必须可复现、可举证，
-- 不能在读取时按当前报关明细重拼 —— 那样明细一改，历史单证就跟着变了。
--
-- 6.1 FTA 优惠字段的语义（见文件头第二节 —— 这是本表最重要的一段注释）：
--   fta_scheme / preference_claimed：这张原产地证是否主张了 FTA 优惠。
--   substantive_change_status：业务对「实质性改变」的确认状态。
--     NOT_APPLICABLE                —— 这张证没主张优惠，或不是原产地证
--     PENDING_BUSINESS_CONFIRMATION —— 主张了优惠但**还没有人确认**（阻断提交）
--     CONFIRMED                     —— 已留痕确认（confirmed_at/by 非空）
--     REJECTED                      —— 业务确认不成立（不主张该优惠）
--   本表**不判断** CONFIRMED 背后的事实是否成立，只保存「谁在什么时候按什么依据确认了」。
CREATE TABLE trade_document (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  set_id uuid NOT NULL,
  line_no integer NOT NULL DEFAULT 1 CHECK(line_no>0),
  document_kind varchar(32) NOT NULL CHECK(document_kind IN (
    'COMMERCIAL_INVOICE','PACKING_LIST','CERTIFICATE_OF_ORIGIN','RCEP_CERTIFICATE_OF_ORIGIN',
    'BILL_OF_LADING','AIR_WAYBELL','IMPORT_LICENSE','CERTIFICATION_DOC','OTHER')),
  document_no varchar(64) NOT NULL DEFAULT '',
  -- 版本：同一套单证下同一类单证从 1 递增；UNIQUE(tenant_id,set_id,document_kind,document_version)
  document_version integer NOT NULL DEFAULT 1 CHECK(document_version>0),
  -- 用的哪份模板（模板可空：没配模板也能按报关明细生成，只是版式是默认的）
  template_id uuid,
  currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency ~ '^[A-Z]{3}$'),
  total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_amount>=0),
  quantity_total numeric(19,6) NOT NULL DEFAULT 0 CHECK(quantity_total>=0),
  package_count integer NOT NULL DEFAULT 0 CHECK(package_count>=0),
  -- 单证内容快照（生成那一刻的字段，读取时不再重算）
  content jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- ===== FTA 优惠原产地证专有字段（其余类型必须留空）=====
  fta_scheme varchar(24) NOT NULL DEFAULT '',
  preference_claimed boolean NOT NULL DEFAULT false,
  origin_country_code varchar(2) NOT NULL DEFAULT '',
  preference_rule_id uuid,
  substantive_change_status varchar(32) NOT NULL DEFAULT 'NOT_APPLICABLE' CHECK(substantive_change_status IN (
    'NOT_APPLICABLE','PENDING_BUSINESS_CONFIRMATION','CONFIRMED','REJECTED')),
  -- 确认依据（人写的口径说明，不是系统算出来的结论）
  substantive_change_note text NOT NULL DEFAULT '',
  business_confirmed_at timestamptz,
  business_confirmed_by uuid,
  -- 本次确认对应的规则版本（事后能回答「当时按哪条规则确认的」）
  preference_rule_version bigint,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','FINAL','SUPERSEDED')),
  supersedes_document_id uuid,
  issued_at timestamptz,
  prepared_by uuid,
  source_system varchar(32) NOT NULL DEFAULT 'TRADE_DOCUMENT'
    CHECK(source_system IN ('TRADE_DOCUMENT','MANUAL_ENTRY','ERP_IMPORT')),
  source_reference varchar(64) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,set_id,document_kind,document_version),
  FOREIGN KEY(tenant_id,set_id) REFERENCES trade_document_set(tenant_id,id),
  FOREIGN KEY(tenant_id,template_id) REFERENCES trade_document_template(tenant_id,id),
  FOREIGN KEY(tenant_id,supersedes_document_id) REFERENCES trade_document(tenant_id,id),
  FOREIGN KEY(tenant_id,preference_rule_id) REFERENCES trade_origin_preference_rule(tenant_id,id),
  CHECK(jsonb_typeof(content)='object'),
  -- source_reference 刻意**不**加全局 CHECK：它是「这份单证文件放在哪 / 出自哪个系统」的定位键，
  -- 草稿阶段还没有（生成时只落内容快照），定稿时才写。
  -- 「定稿必须有定位」由 guard_trade_document_amount 按状态钉死 —— 加一条全局 CHECK
  -- 会让「先生成草稿、再定稿」这条正常路径永远走不通。
  -- ===== 优惠主张与确认痕迹必须自洽（这是「只校验留痕、不代替业务下结论」的 DB 落点）=====
  -- ① 没主张优惠就不许挂确认痕迹：否则「已确认」会指向一个不存在的优惠主张，
  --    事后查「这份证享受了多少优惠」时答不出来。
  CHECK(NOT preference_claimed
        OR (btrim(fta_scheme)<>'' AND length(btrim(origin_country_code))=2)),
  -- ② CONFIRMED 必须留下确认人与确认时间 —— 「有人确认过」这件事必须有痕迹，
  --    否则系统就成了「无痕自动通过优惠」，那正是文件头第二节禁止的行为。
  CHECK(substantive_change_status<>'CONFIRMED'
        OR (business_confirmed_at IS NOT NULL AND business_confirmed_by IS NOT NULL)),
  -- ③ 反向：有确认痕迹就必须标成 CONFIRMED 或 REJECTED，不能停在待确认。
  CHECK((business_confirmed_at IS NULL) = (business_confirmed_by IS NULL)),
  CHECK(business_confirmed_at IS NULL
        OR substantive_change_status IN ('CONFIRMED','REJECTED')),
  -- ④ 主张优惠时状态不允许停在 NOT_APPLICABLE
  CHECK(NOT preference_claimed
        OR substantive_change_status IN ('PENDING_BUSINESS_CONFIRMATION','CONFIRMED','REJECTED')),
  -- ⑤ 非原产地证不许挂优惠字段
  CHECK(document_kind IN ('CERTIFICATE_OF_ORIGIN','RCEP_CERTIFICATE_OF_ORIGIN')
        OR (NOT preference_claimed AND btrim(fta_scheme)='' AND btrim(origin_country_code)=''))
);

-- ===========================================================================
-- 7. 完整性校验结果（蓝图输出之二：完整性校验结果）
-- ===========================================================================
-- 一次校验 = 一行，**append-only**：校验结论是举证材料，改结论要重新跑一次而不是改旧的。
-- items 存逐条结果（jsonb 数组）：每条 = {documentKind, requirementLevel, state, source, detail}。
-- 逐条展开存而不是只存一个结论，是因为「缺哪几份」正是运营要照着补的东西。
CREATE TABLE trade_document_check (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  set_id uuid NOT NULL,
  -- 轮次：同一套单证第几次校验，从 1 递增
  check_round integer NOT NULL CHECK(check_round>0),
  result varchar(32) NOT NULL CHECK(result IN ('PASS','FAIL','PENDING_BUSINESS_CONFIRMATION')),
  -- 结论口径：
  --   PASS                             规则要求的单证齐备，且没有待业务确认项
  --   FAIL                             有必备单证缺失 / 认证状态不合格 / 单证未定稿
  --   PENDING_BUSINESS_CONFIRMATION    单证齐备但有优惠待业务确认（**不是通过**）
  required_count integer NOT NULL DEFAULT 0,
  satisfied_count integer NOT NULL DEFAULT 0,
  missing_count integer NOT NULL DEFAULT 0,
  pending_business_count integer NOT NULL DEFAULT 0,
  -- 本次校验命中的规则条数（规则会变，这个数让「当时按哪一版规则校的」可追溯）
  rule_count integer NOT NULL DEFAULT 0,
  items jsonb NOT NULL DEFAULT '[]'::jsonb,
  missing_summary text NOT NULL DEFAULT '',
  checked_at timestamptz NOT NULL,
  checked_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,set_id,check_round),
  FOREIGN KEY(tenant_id,set_id) REFERENCES trade_document_set(tenant_id,id),
  CHECK(jsonb_typeof(items)='array'),
  CHECK(required_count>=0 AND satisfied_count>=0 AND missing_count>=0 AND pending_business_count>=0),
  -- 计数必须与结论自洽：PASS 不允许有缺项，也不允许有待确认项
  CHECK(result<>'PASS' OR (missing_count=0 AND pending_business_count=0)),
  -- FAIL 一定有缺项；PENDING 一定有待确认项
  CHECK(result<>'FAIL' OR missing_count>0),
  CHECK(result<>'PENDING_BUSINESS_CONFIRMATION' OR pending_business_count>0),
  -- 齐备数 + 缺项数 = 必备数
  CHECK(satisfied_count+missing_count<=required_count)
);
CREATE INDEX trade_check_set ON trade_document_check(tenant_id,set_id,check_round DESC);

-- ===========================================================================
-- 8. 缺失项提醒（蓝图输出之三：缺失提醒）
-- ===========================================================================
-- 与 trade_document_check 分开是有意的：check 是**一次运行的结论**（append-only 留档），
-- reminder 是**可逐条处理的工作项**（会被解决/忽略，但解决记录同样留痕）。
-- 把两者塞进一张表会导致「结论不可变」与「工作项可处理」互相打架。
CREATE TABLE trade_document_reminder (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  set_id uuid NOT NULL,
  check_id uuid NOT NULL,
  document_kind varchar(32) NOT NULL,
  reminder_type varchar(32) NOT NULL CHECK(reminder_type IN (
    'MISSING_DOCUMENT','UNDECLARED_DOCUMENT','UNCONFIRMED_SUBSTANTIVE_CHANGE',
    'CERTIFICATION_EXPIRED','CERTIFICATION_REVOKED','CERTIFICATION_MISSING','DOCUMENT_NOT_FINAL')),
  -- BLOCKER 阻断提交（必备单证缺失、优惠待确认、认证不合格）；WARNING 只提示
  severity varchar(16) NOT NULL CHECK(severity IN ('BLOCKER','WARNING')),
  message text NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED','DISMISSED')),
  resolved_at timestamptz,
  resolved_by uuid,
  resolution_note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一次校验里同类问题只报一条：重复跑校验不会把提醒堆成几十行
  UNIQUE(tenant_id,set_id,check_id,reminder_type,document_kind),
  FOREIGN KEY(tenant_id,set_id) REFERENCES trade_document_set(tenant_id,id),
  FOREIGN KEY(tenant_id,check_id) REFERENCES trade_document_check(tenant_id,id),
  CHECK(btrim(message)<>''),
  -- 处理痕迹必须成对：有处理时间就必须有处理人
  CHECK((resolved_at IS NULL)=(resolved_by IS NULL)),
  -- 非 OPEN 状态必须给处理说明：静默消失的提醒等于没提醒过
  CHECK(status='OPEN' OR btrim(resolution_note)<>'')
);
CREATE INDEX trade_reminder_set ON trade_document_reminder(tenant_id,set_id,status,severity);
CREATE INDEX trade_reminder_blocking
  ON trade_document_reminder(tenant_id,created_at DESC) WHERE status='OPEN' AND severity='BLOCKER';

-- ===========================================================================
-- 9. 事件时间线（一次业务动作 = 一行，逐笔留痕）
-- ===========================================================================
CREATE TABLE trade_document_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  set_id uuid NOT NULL,
  kind varchar(24) NOT NULL CHECK(kind IN (
    'CREATED','UPDATED','UNDER_REVIEW','APPROVED','REVIEW_REJECTED','REVIEW_WITHDRAWN',
    'CHECK_RUN','DOCUMENT_GENERATED','DOCUMENT_FINALIZED','PREFERENCE_CLAIMED',
    'BUSINESS_CONFIRMED','BUSINESS_REJECTED','REMINDER_RESOLVED','REMINDER_DISMISSED',
    'SUBMITTED','ACKNOWLEDGED','SENT_BACK','CANCELLED')),
  occurred_at timestamptz NOT NULL,
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  -- 事件定位：指向本次动作的对象（单证 / 规则 / 提醒 / 校验）
  target_kind varchar(24) NOT NULL DEFAULT '',
  target_id uuid,
  reason text NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,set_id) REFERENCES trade_document_set(tenant_id,id)
);
CREATE INDEX trade_event_set ON trade_document_event(tenant_id,set_id,occurred_at DESC);

-- ===========================================================================
-- 10. DB 级不变量
-- ===========================================================================
-- 10.1 状态与时间戳自洽 + 终态不可离开 + **人工复核闸**
-- 三条 BEFORE UPDATE 触发器**按名字字母序执行**（PostgreSQL 对同一时机的触发器按名称排序），
-- 名字刻意带 1/2/3 序号，把「复核闸 → 校验结论闸 → 汇总自洽」钉死成这个顺序：
-- 没复核就提交必须报「先复核」，而不是被后面的「校验没跑」抢答。

-- 10.1.1 人工复核闸 + 状态机自洽
CREATE FUNCTION guard_trade_1_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior_status varchar(24); prior_round integer;
BEGIN
 prior_status := OLD.status; prior_round := OLD.review_round;

 -- ===== 人工复核闸：提交下游之前必须有人工复核过 =====
 IF NEW.status = 'SUBMITTED' AND NEW.status <> prior_status THEN
  IF prior_status <> 'APPROVED' THEN
   RAISE EXCEPTION '提交报关行/目的仓前必须人工复核通过：当前状态「%」，只允许「已复核通过」提交。%',
    prior_status,
    CASE prior_status
      WHEN 'DRAFT' THEN '单证还在草稿：备证资料没人看过，不该直接发出去'
      WHEN 'UNDER_REVIEW' THEN '还在待复核，复核通过后才能提交'
      WHEN 'REJECTED' THEN '复核退回后必须改完重新走复核'
      WHEN 'SENT_BACK' THEN '被退回后必须改完重新走复核才能再次提交'
      ELSE '该状态不允许直接提交'
    END USING ERRCODE='23514';
  END IF;
  IF NEW.reviewed_at IS NULL OR NEW.reviewed_by IS NULL THEN
   RAISE EXCEPTION '提交前必须有人工复核痕迹（reviewed_at / reviewed_by）' USING ERRCODE='23514';
  END IF;
  IF NEW.review_round = 0 THEN
   RAISE EXCEPTION '复核轮次为 0：不许把一套没复核过的单证提交给下游' USING ERRCODE='23514';
  END IF;
 END IF;

 -- 状态与时间戳自洽。这些时间戳是**历史痕迹**，一旦打上就不因为状态往前走而消失
 -- （被退回后 submitted_at 必须留着：「这套单证曾经发出去过没有」是举证材料）。
 IF NEW.submitted_at IS NULL AND NEW.status IN ('SUBMITTED','ACKNOWLEDGED','SENT_BACK') THEN
  RAISE EXCEPTION '状态「%」与提交时间不自洽：只有已提交才可能已经送到报关行/目的仓', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF NEW.submitted_at IS NOT NULL
    AND NEW.status NOT IN ('SUBMITTED','ACKNOWLEDGED','SENT_BACK')
    AND NOT (TG_OP='UPDATE' AND OLD.submitted_at IS NOT NULL
             AND NEW.status IN ('DRAFT','UNDER_REVIEW','APPROVED','REJECTED')) THEN
  RAISE EXCEPTION '状态「%」的提交痕迹来路不明：这单从未提交过，不允许带提交痕迹', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF NEW.acknowledged_at IS NULL AND NEW.status = 'ACKNOWLEDGED' THEN
  RAISE EXCEPTION '状态「%」与接收时间不自洽', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.acknowledged_at IS NOT NULL AND NEW.status NOT IN ('ACKNOWLEDGED','SENT_BACK') THEN
  RAISE EXCEPTION '状态「%」没有被接收过，不允许带接收痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.sent_back_at IS NULL AND NEW.status = 'SENT_BACK' THEN
  RAISE EXCEPTION '状态「%」与退回时间不自洽', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.sent_back_at IS NOT NULL AND NEW.status NOT IN ('SENT_BACK') THEN
  RAISE EXCEPTION '状态「%」没有被退回过，不允许带退回痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 -- 退回必须给原因：没有原因就无法改
 IF NEW.status='SENT_BACK' AND btrim(NEW.sent_back_reason)='' THEN
  RAISE EXCEPTION '被退回必须给原因：改单证要按退回原因改，没有原因就无从改起' USING ERRCODE='23514';
 END IF;
 -- 复核退回必须给原因
 IF NEW.status='REJECTED' AND btrim(NEW.rejected_reason)='' THEN
  RAISE EXCEPTION '复核退回必须给原因：改完要按退回原因改，没有原因就无从改起' USING ERRCODE='23514';
 END IF;
 IF (NEW.status='CANCELLED') <> (NEW.cancelled_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与取消时间不自洽', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.status='CANCELLED' AND btrim(NEW.cancel_reason)='' THEN
  RAISE EXCEPTION '取消必须给原因：备证资料已生成过，取消后无法追责' USING ERRCODE='23514';
 END IF;
 IF NEW.status<>'CANCELLED'
    AND (NEW.cancelled_at IS NOT NULL OR NEW.cancelled_by IS NOT NULL OR btrim(NEW.cancel_reason)>'') THEN
  RAISE EXCEPTION '状态「%」不是已取消，不允许带取消痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 -- 复核轮次与复核痕迹必须同存同灭
 IF (NEW.review_round > 0) <> (NEW.reviewed_at IS NOT NULL) THEN
  RAISE EXCEPTION '复核轮次 % 与复核时间不自洽：有复核轮次就必须有复核人与复核时间',
   NEW.review_round USING ERRCODE='23514';
 END IF;
 IF (NEW.reviewed_at IS NOT NULL) <> (NEW.reviewed_by IS NOT NULL) THEN
  RAISE EXCEPTION '复核时间与复核人不成对：两者必须同时有或同时无' USING ERRCODE='23514';
 END IF;
 -- 「已通过复核」的五种状态必须真的复核过。复核退回(REJECTED)同样走完一轮。
 -- CANCELLED 不在列表里：草稿也必须能被取消。
 IF NEW.status IN ('APPROVED','SUBMITTED','ACKNOWLEDGED','REJECTED') AND NEW.review_round <= 0 THEN
  RAISE EXCEPTION '状态「%」必须已完成至少一轮人工复核', NEW.status USING ERRCODE='23514';
 END IF;
 -- 最新校验结论与校验时间必须同存同灭
 IF (NEW.latest_check_id IS NOT NULL) <> (NEW.latest_check_at IS NOT NULL) THEN
  RAISE EXCEPTION '最新校验结论与校验时间不成对：有结论就必须有校验时间' USING ERRCODE='23514';
 END IF;
 -- 终态不许离开（判据是「旧状态是终态且新状态不同」，不能写成「新状态是 X」）
 IF TG_OP='UPDATE' AND prior_status IN ('ACKNOWLEDGED','CANCELLED') AND NEW.status <> prior_status THEN
  RAISE EXCEPTION '「%」是终态：% ，不允许改到「%」', prior_status,
   CASE prior_status
     WHEN 'ACKNOWLEDGED' THEN '下游已接收，备证留档完成'
     ELSE '已取消的单证包不再接受任何动作'
   END,
   NEW.status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trade_1_state BEFORE INSERT OR UPDATE ON trade_document_set
 FOR EACH ROW EXECUTE FUNCTION guard_trade_1_state();

-- 10.1.2 **提交闸：完整性校验必须 PASS 且没有 BLOCKER 提醒**
-- 蓝图输出明写「完整性校验结果 + 缺失提醒」，所以「齐了才发得出去」是硬闸而不是建议。
-- 判据直接读 trade_document_check / trade_document_reminder 的**当前**行 ——
-- 不用 NEW 快照（这两张表与单头不在同一行，NEW 拿不到它们）。
CREATE FUNCTION guard_trade_2_submit_gate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE last_result varchar(24); blocking integer; last_check_id uuid;
BEGIN
 IF NEW.status <> 'SUBMITTED' OR NEW.status = OLD.status THEN
  RETURN NEW;
 END IF;

 -- 最近一次校验必须存在且 PASS
 SELECT c.id, c.result INTO last_check_id, last_result FROM trade_document_check c
  WHERE c.tenant_id = NEW.tenant_id AND c.set_id = NEW.id
  ORDER BY c.check_round DESC LIMIT 1;
 IF last_check_id IS NULL THEN
  RAISE EXCEPTION '提交前必须先跑一次完整性校验：还没校验过就提交，等于没人确认这套单证齐不齐'
   USING ERRCODE='23514';
 END IF;
 -- 单头记录的最近结论必须就是最近那一次的结论（防止拿旧的 PASS 交新的单证）
 IF NEW.latest_check_id IS DISTINCT FROM last_check_id OR NEW.latest_check_result IS DISTINCT FROM last_result THEN
  RAISE EXCEPTION '单证包上记录的最近校验结论（%/%）不是实际最近一次校验（%/%）：必须重跑校验再提交',
   coalesce(NEW.latest_check_result,'—'), coalesce(NEW.latest_check_id::text,'—'),
   coalesce(last_result,'—'), coalesce(last_check_id::text,'—') USING ERRCODE='23514';
 END IF;
 IF last_result <> 'PASS' THEN
  RAISE EXCEPTION '提交前完整性校验必须通过，当前结论是「%」。%',
   last_result,
   CASE last_result
     WHEN 'FAIL' THEN '有必备单证缺失或认证状态不合格，补齐后重跑校验'
     WHEN 'PENDING_BUSINESS_CONFIRMATION' THEN
       '存在待业务确认的 FTA 优惠主张（实质性改变需业务确认，系统不代替业务下结论），确认后再提交'
     ELSE '未知结论'
   END USING ERRCODE='23514';
 END IF;

 -- 没有任何未处理的 BLOCKER 提醒
 SELECT count(*) INTO blocking FROM trade_document_reminder r
  WHERE r.tenant_id = NEW.tenant_id AND r.set_id = NEW.id
    AND r.status = 'OPEN' AND r.severity = 'BLOCKER';
 IF blocking > 0 THEN
  RAISE EXCEPTION '提交前有 % 条未处理的阻断级缺失提醒，先处理掉再提交', blocking
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trade_2_submit_gate BEFORE UPDATE ON trade_document_set
 FOR EACH ROW EXECUTE FUNCTION guard_trade_2_submit_gate();

-- 10.1.3 **FTA 优惠待确认不许提交**（文件头第二节的 DB 落点）
-- 这条与 10.1.2 是两道不同的闸：10.1.2 校验「缺件」，这一条校验「优惠主张有没有人签字」。
-- 分开写是因为它们答的是两个不同的问题，混在一起报错会让人以为「补份单证就行」。
CREATE FUNCTION guard_trade_3_preference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pending integer; unconfirmed integer;
BEGIN
 IF NEW.status NOT IN ('APPROVED','SUBMITTED','ACKNOWLEDGED') THEN
  RETURN NEW;
 END IF;
 -- 主张了优惠但还没有业务确认痕迹的单证数
 SELECT count(*) INTO pending FROM trade_document d
  WHERE d.tenant_id = NEW.tenant_id AND d.set_id = NEW.id
    AND d.preference_claimed
    AND d.substantive_change_status = 'PENDING_BUSINESS_CONFIRMATION'
    AND d.status <> 'SUPERSEDED';
 IF pending > 0 THEN
  RAISE EXCEPTION '有 % 份优惠原产地证主张了 FTA 优惠但还没有业务确认：实质性改变是否成立需要业务确认，系统只负责按规则校验与留档，不代替业务下结论。确认或撤回优惠主张后再继续', pending
   USING ERRCODE='23514';
 END IF;
 -- 有确认时间就必须有确认人 + 确认依据
 SELECT count(*) INTO unconfirmed FROM trade_document d
  WHERE d.tenant_id = NEW.tenant_id AND d.set_id = NEW.id
    AND d.status <> 'SUPERSEDED'
    AND d.substantive_change_status = 'CONFIRMED'
    AND (d.business_confirmed_by IS NULL OR btrim(d.substantive_change_note)='');
 IF unconfirmed > 0 THEN
  RAISE EXCEPTION '有 % 份优惠原产地证标成已确认但缺确认人或确认依据：留痕不全会让「这份优惠是谁批的、凭什么批的」答不出来', unconfirmed
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trade_3_preference BEFORE INSERT OR UPDATE ON trade_document_set
 FOR EACH ROW EXECUTE FUNCTION guard_trade_3_preference();

-- 10.1.4 汇总自洽：单证数 / 待确认数必须与实际一致
-- **延迟约束触发器**：建包路径是「先插单头(计数 0) → 写单证 → 跑校验 → 回填单头」，
-- 即时触发器会对着半成品报错，必须 DEFERRABLE。
-- 关键：**不信 NEW，回表读当前行**。延迟约束在提交时才跑，而 NEW 是事件发生那一刻的
-- 旧镜像（「先插头 → 写明细 → 回填头」会触发 INSERT 与 UPDATE 两个事件，
-- 提交时 INSERT 那次仍拿合计=0 去核对已算完的单证，每次必报「单头≠明细」）。
-- 与 V85 的 guard_customs_rate_snapshot 同一个已证明正确的做法。
CREATE FUNCTION guard_trade_set_totals() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cur_docs integer; cur_pending integer; cur_missing integer;
  head_docs integer; head_pending integer; head_missing integer;
BEGIN
 SELECT count(*) INTO cur_docs FROM trade_document d
  WHERE d.tenant_id = NEW.tenant_id AND d.set_id = NEW.id AND d.status <> 'SUPERSEDED';
 SELECT count(*) INTO cur_pending FROM trade_document d
  WHERE d.tenant_id = NEW.tenant_id AND d.set_id = NEW.id AND d.status <> 'SUPERSEDED'
    AND d.preference_claimed AND d.substantive_change_status = 'PENDING_BUSINESS_CONFIRMATION';
 SELECT coalesce(max(c.missing_count),0) INTO cur_missing FROM trade_document_check c
  WHERE c.tenant_id = NEW.tenant_id AND c.set_id = NEW.id;

 -- 回表读单头当前的**最终提交态**那三列（判据同 guard_customs_rate_snapshot）：
 -- 延迟约束在提交时才跑，而 NEW 是事件发生那一刻的旧镜像。
 SELECT s.total_document_count, s.pending_business_count, s.missing_document_count
  INTO head_docs, head_pending, head_missing
  FROM trade_document_set s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.id;

 IF head_docs <> cur_docs THEN
  RAISE EXCEPTION '单证包单证数 % 与实际单证数 % 不一致：汇总数不是页面上手填的数字',
   head_docs, cur_docs USING ERRCODE='23514';
 END IF;
 IF head_pending <> cur_pending THEN
  RAISE EXCEPTION '单证包待业务确认数 % 与实际待确认单证数 % 不一致', head_pending, cur_pending
   USING ERRCODE='23514';
 END IF;
 IF head_missing <> cur_missing THEN
  RAISE EXCEPTION '单证包缺失数 % 与最近一次校验的缺项数 % 不一致', head_missing, cur_missing
   USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trade_set_totals
 AFTER INSERT OR UPDATE ON trade_document_set
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_trade_set_totals();

-- 10.1.5 单证版本链自洽：supersedes 必须指向**同类型的前一版本**，不许跨类型挂
CREATE FUNCTION guard_trade_document_chain() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prev record;
BEGIN
 IF NEW.supersedes_document_id IS NULL THEN
  IF NEW.document_version <> 1 THEN
   RAISE EXCEPTION '首版单证版本号必须是 1，收到 %', NEW.document_version USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF NEW.document_version = 1 THEN
  RAISE EXCEPTION '单证版本号为 1 时不允许带 supersedes：首版没有前一版' USING ERRCODE='23514';
 END IF;
 SELECT d.document_kind, d.document_version, d.set_id INTO prev
   FROM trade_document d WHERE d.tenant_id = NEW.tenant_id AND d.id = NEW.supersedes_document_id;
 IF prev IS NULL THEN
  RAISE EXCEPTION '单证要取代的单证 % 不存在或不属于本租户', NEW.supersedes_document_id USING ERRCODE='23503';
 END IF;
 IF prev.set_id <> NEW.set_id THEN
  RAISE EXCEPTION '单证要取代的单证属于另一套单证包' USING ERRCODE='23514';
 END IF;
 IF prev.document_kind <> NEW.document_kind THEN
  RAISE EXCEPTION '单证要取代的前一版类型是「%」，与本版「%」不同：改版链不许跨类型',
   prev.document_kind, NEW.document_kind USING ERRCODE='23514';
 END IF;
 IF prev.document_version <> NEW.document_version - 1 THEN
  RAISE EXCEPTION '单证版本 % 必须接在版本 % 之后：改版链不能跳号',
   NEW.document_version, prev.document_version USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trade_document_chain BEFORE INSERT OR UPDATE ON trade_document
 FOR EACH ROW EXECUTE FUNCTION guard_trade_document_chain();

-- 10.1.6 单证的**币种与金额必须与单证包一致** + 定稿痕迹
-- 备证资料里发票币种与报关申报币种对不上是申报不实（10.5.1.1 在提交报关行时就有同一条闸），
-- 所以这里在单证包这一层先钉住，不等到下游报关行才发现。
CREATE FUNCTION guard_trade_document_amount() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; rule record;
BEGIN
 SELECT s.document_currency, s.destination_country_code, s.status INTO head
   FROM trade_document_set s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.set_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '单证指向的单证包 % 不存在', NEW.set_id USING ERRCODE='23503';
 END IF;
 IF NEW.currency <> head.document_currency THEN
  RAISE EXCEPTION '单证币种「%」与单证包币种「%」不一致：币种对不上的发票提交上去是申报不实',
   NEW.currency, head.document_currency USING ERRCODE='23514';
 END IF;
 -- 定稿必须留下签发痕迹
 IF NEW.status = 'FINAL' AND (NEW.issued_at IS NULL OR btrim(NEW.source_reference)='') THEN
  RAISE EXCEPTION '单证定稿必须留下签发时间与来源定位（issued_at / source_reference）：定稿前的单证不是备证资料' USING ERRCODE='23514';
 END IF;
 IF NEW.status = 'SUPERSEDED' AND NEW.issued_at IS NULL THEN
  RAISE EXCEPTION '被取代的单证必须已签发过（issued_at）：没有签发过的单证不能进入改版链' USING ERRCODE='23514';
 END IF;
 -- 优惠主张必须挂在一条真实存在的 FTA 规则上，且规则国家必须与单证包目的国一致
 IF NEW.preference_rule_id IS NOT NULL THEN
  SELECT r.destination_country_code, r.status, r.version INTO rule
    FROM trade_origin_preference_rule r WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.preference_rule_id;
  IF rule IS NULL THEN
   RAISE EXCEPTION '优惠主张引用的 FTA 规则 % 不存在或不属于本租户', NEW.preference_rule_id USING ERRCODE='23503';
  END IF;
  IF rule.status <> 'ACTIVE' THEN
   RAISE EXCEPTION '优惠主张引用的 FTA 规则已停用，不允许拿停用规则主张优惠' USING ERRCODE='23514';
  END IF;
  IF rule.destination_country_code <> head.destination_country_code THEN
   RAISE EXCEPTION 'FTA 规则适用国「%」与单证包目的国「%」不是同一个国：拿别国规则主张优惠是无效优惠',
    rule.destination_country_code, head.destination_country_code USING ERRCODE='23514';
  END IF;
  IF NEW.substantive_change_status = 'CONFIRMED' AND NEW.preference_rule_version IS NULL THEN
   RAISE EXCEPTION '优惠已确认但没记录当时按哪一版规则确认的：事后无法回答「按哪条规则批的优惠」' USING ERRCODE='23514';
  END IF;
 ELSIF NEW.preference_claimed THEN
  RAISE EXCEPTION '主张了 FTA 优惠却没有引用任何优惠规则：优惠主张必须挂一条真实存在的规则，否则无法留档' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trade_document_amount BEFORE INSERT OR UPDATE ON trade_document
 FOR EACH ROW EXECUTE FUNCTION guard_trade_document_amount();

-- 10.1.7 已复核之后不允许改单证（否则「复核」变成走过场）
CREATE FUNCTION guard_trade_lock_documents() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM trade_document_set s
   WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.set_id
     AND s.status IN ('APPROVED','SUBMITTED','ACKNOWLEDGED','SENT_BACK')) THEN
  RAISE EXCEPTION '单证包处于「%」状态，不允许直接改单证：要让改动被看到，请先撤回复核或等下游退回后再改',
   (SELECT s2.status FROM trade_document_set s2
     WHERE s2.tenant_id = NEW.tenant_id AND s2.id = NEW.set_id) USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trade_lock_documents BEFORE INSERT OR UPDATE ON trade_document
 FOR EACH ROW EXECUTE FUNCTION guard_trade_lock_documents();

-- 10.1.8 提醒必须挂在**真实存在**的校验结果上，且校验与提醒必须属于同一套单证
CREATE FUNCTION guard_trade_reminder_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c record;
BEGIN
 SELECT ch.set_id, ch.result INTO c FROM trade_document_check ch
  WHERE ch.tenant_id = NEW.tenant_id AND ch.id = NEW.check_id;
 IF c IS NULL THEN
  RAISE EXCEPTION '缺失提醒指向的校验结果 % 不存在或不属于本租户', NEW.check_id USING ERRCODE='23503';
 END IF;
 IF c.set_id <> NEW.set_id THEN
  RAISE EXCEPTION '缺失提醒指向的校验结果属于另一套单证包' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trade_reminder_check BEFORE INSERT OR UPDATE ON trade_document_reminder
 FOR EACH ROW EXECUTE FUNCTION guard_trade_reminder_check();

-- 10.1.9 认证状态：已过期/被撤销的行不许被改成 VALID 而不更新有效期
-- 「把一张过期的证书改成 VALID」在业务上就是造证，所以过期与撤销必须靠有效期与
-- 显式状态来表达，不能靠手改一个状态字段绕过日期。
CREATE FUNCTION guard_trade_certification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status = 'VALID' AND NEW.expires_on IS NOT NULL
    AND NEW.issued_on IS NOT NULL AND NEW.expires_on < NEW.issued_on THEN
  RAISE EXCEPTION '认证有效期（%）早于签发日（%）：有效期与签发日必须自洽',
   NEW.expires_on, NEW.issued_on USING ERRCODE='23514';
 END IF;
 IF NEW.status = 'VALID' AND btrim(NEW.certificate_no)='' THEN
  RAISE EXCEPTION '认证标成有效但没有证书编号：一张没有编号的证书在目的国海关眼里等于没有' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trade_certification BEFORE INSERT OR UPDATE ON trade_certification_status
 FOR EACH ROW EXECUTE FUNCTION guard_trade_certification();

-- ===========================================================================
-- 11. 热读路径索引
-- ===========================================================================
CREATE INDEX trade_set_review ON trade_document_set(tenant_id,reviewed_at) WHERE reviewed_at IS NOT NULL;
CREATE INDEX trade_set_result ON trade_document_set(tenant_id,latest_check_result);
CREATE INDEX trade_document_set_idx ON trade_document(tenant_id,set_id,document_kind,document_version);
CREATE INDEX trade_document_preference
  ON trade_document(tenant_id,set_id) WHERE preference_claimed;
CREATE INDEX trade_document_kind ON trade_document(tenant_id,document_kind);

-- ===========================================================================
-- 12. 多租户：RLS + 授权（与 V1 第 9 节、V85 第 9 节同一条纪律）
-- ===========================================================================
-- 九张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'trade_document_rule','trade_document_template','trade_certification_status',
   'trade_origin_preference_rule','trade_document_set','trade_document',
   'trade_document_check','trade_document_reminder','trade_document_event'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON trade_document_rule, trade_document_template,
  trade_certification_status, trade_origin_preference_rule, trade_document_set,
  trade_document, trade_document_check, trade_document_reminder, trade_document_event
  TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建包会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE trade_document_set_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V90 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
