-- 6.1.2.3 供应商资质（trade 服务）。
--
-- 蓝图 06-purchase-and-supply.md 6.1.2.3 原文：
--   职责：登记与跟踪供应商资质：营业执照、质检报告、认证证书（CE/ROHS/ISO 等）、授权书，
--         明确有效期与即将过期提醒。品牌授权需关联 03 品牌。
--   输入：资质文件上传；有效期；审核状态。
--   输出：资质档案 + 过期提醒。
--   关系：上游 = 3.3.2 品牌；下游 = 16 合规（认证缺失预警）、8 收货（质检依据）。
--         **资质过期必须阻断相关商品的刊登与发货**，不能只做提醒。
--
-- 归属：**trade 服务**（蓝图 README 系统地图 06 行「后端主责服务：trade + catalog（party）」）。
-- 前端路由 /supply/qualifications，后端命名空间 /trade/supplier-qualifications ——
-- 与 6.1.2.1（前端 /supply/sources、后端 /trade/sources）同一纪律，各占一个独立分区。
-- 本迁移**不 ALTER 任何既有表**，跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、模块边界：供应商主数据与品牌都在 **catalog**，本库只存逻辑 ID + 名称快照
-- ===========================================================================
-- 卷 06 的主责是「trade + catalog（party）」，供应商主数据（party / party_role）在
-- **catalog 服务的库**里，03 品牌（brand / brand_authorization）同样在 catalog。
-- AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，于是：
--   * supplier_qualification.supplier_role_id 存 catalog 的 **SUPPLIER 角色 id**
--     （逻辑引用，跨库不建约束，与 trade V92 的 sku_ref、V233 的 supplier_party_id 同一纪律）；
--   * supplier_qualification.supplier_name_snapshot 存**登记那一刻的供应商名快照**：
--     catalog 改名后历史资质仍能显示当时的供应商名；
--   * supplier_qualification.brand_id 存 **03 品牌 id**（品牌授权必须关联品牌），
--     brand_name_snapshot 同理留快照；
--   * 「这个供应商 / 这个品牌到底存不存在、叫什么」由服务层经 catalog 的**授权只读接口**
--     核对（见 SupplierPartyQueryPort 与 BrandQueryPort），核对不到就显式失败，
--     **不凭空造一个供应商或品牌出来**。
--
-- ===========================================================================
-- 二、**词表与 6.1.1.2 的附件资质同源**（两套词汇会让同一个词在同一个界面上对不上号）
-- ===========================================================================
-- catalog 的 6.1.1.2 供应商档案里已有一份「附件资质」（vendor_qualification_attachment），
-- 它的类型闭集是 BUSINESS_LICENSE / ISO9001 / ISO14001 / CE / FDA / COC / OTHER。
-- 蓝图 6.1.2.3 点名的是 营业执照 / 质检报告 / 认证证书（CE/ROHS/ISO 等）/ 授权书，
-- 与那份闭集**不完全相同**（多出 ROHS 与授权书，多出「质检报告」这一类）。
--
-- 本表的闭集 = 6.1.1.2 的闭集 **并上** 蓝图点名的三个新值（ROHS / 质检报告 / 授权书），
-- **重名的取值沿用 6.1.1.2 的原码**（BUSINESS_LICENSE / ISO9001 / ISO14001 / CE / FDA /
-- COC / OTHER 一字不改）。理由：同一个词在两处必须能对上号；另造一套
-- 「本模块专属码」会让「这张 CE 是不是 6.1.1.2 里那张 CE」永远答不出来。
-- 枚举只增不改 —— 加类型要改这条 CHECK 与 Java 枚举，不是插一行数据。
--
-- ===========================================================================
-- 三、**审核状态是一个显式状态机**，不是一列自由文本
-- ===========================================================================
-- 蓝图输入句里有「审核状态」，而「审核状态」如果只是一列可随便改的字符串，
-- 「这张证现在算不算数」就答不出来 —— 而这个答案直接决定能不能刊登、能不能发货。
-- 于是这里用「枚举 + 允许的转换」把它表达成状态机：
--
--   DRAFT ──SUBMIT──▶ PENDING ──APPROVE──▶ APPROVED ──REVOKE──▶ REVOKED
--     ▲                   │                    │                    │
--     │                   └──REJECT──▶ REJECTED ◀──REJECT───────────┘
--     │                                    │                         │
--     └──────────────RESUBMIT──────────────┴───────RESUBMIT───────────┘
--
-- 允许的转换**在 DB 层用触发器钉死**（guard_supplier_qualification_review_transition）：
-- 直连数据库改状态也会被拒 —— 服务层只是第一道闸，不是唯一一道。
-- 每次流转都往 supplier_qualification_review 追加一行（append-only，触发器钉死
-- UPDATE / DELETE），于是「这张证上个月为什么被驳回」永远答得出来。
--
-- ===========================================================================
-- 四、**到期状态不落库，由读出口按真实日期现算**（与 6.1.1.2 同一纪律）
-- ===========================================================================
-- 存一列「是否过期」就要一个每日任务去维护它，而维护出来的那天与读出口算出的那天
-- 差一天时，「这张证到底过期没有」会答错 —— 而这个答案直接决定能不能下单、能不能发货。
-- 所以本表**没有** expiry_state 列：到期状态由 SQL 按 valid_until / valid_from 与
-- **数据库时钟 now()** 现算，落在「即将过期」窗口内的判据来自
-- supplier_qualification_policy.warning_days（租户级阈值，默认 30 天，与
-- 6.1.1.2 的 EXPIRING_SOON_DAYS=30 同口径）。
--
-- ===========================================================================
-- 五、**「阻断」是一等公民，不是提醒**
-- ===========================================================================
-- 蓝图加粗句：「**资质过期必须阻断相关商品的刊登与发货，不能只做提醒**」。
-- 因此本迁移把两件事分开落位：
--   * 提醒（supplier_qualification_reminder）—— 即将过期 / 已过期时生成一条可确认的记录；
--   * 阻断（服务层的 blocking-decision 端点）—— 过期 / 撤销 / 必需资质缺失时，
--     直接给出 blocked=true + 原因码 + 人话，供 04 刊登与发货链路调用。
--     「相关商品」= 该供应商供货的商品：经 6.1.2.1 已交付的货源关联
--     （supplier_source_sku_link → item → stall → supplier_party_id，**同库同服务**，
--     只读不写）解析出供应商，再逐个判资质。
--
-- ===========================================================================
-- 1. supplier_qualification_policy · 租户级策略（阈值窗口 + 必需资质类型）
-- ===========================================================================
-- 「即将过期」是**多长**的窗口、以及**哪些资质是必需的**，是租户级策略而不是代码常量：
-- 欧盟站与国内站的窗口不一样，不同品类要的证件也不一样。
-- 默认值：30 天窗口（与 6.1.1.2 同口径）+ 必需「营业执照」。
-- 该行**按需创建**：第一次用到时由服务层写入（首次登记资质时），所以表可以是空的。
CREATE TABLE supplier_qualification_policy (
  tenant_id uuid PRIMARY KEY,
  -- 即将过期窗口（天）。1..365。
  warning_days integer NOT NULL DEFAULT 30 CHECK(warning_days BETWEEN 1 AND 365),
  -- 必需资质类型（闭集子集）。**不建自由文本**：每一种都必须是上面那条受控词表里的值，
  -- 否则「必需的资质」就能被写成乱码，阻断判定会跟着失去意义。
  required_types text[] NOT NULL DEFAULT ARRAY['BUSINESS_LICENSE']::text[],
  note text NOT NULL DEFAULT '',
  updated_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT supplier_qualification_policy_required_types_check
    CHECK (required_types <@ ARRAY[
      'BUSINESS_LICENSE','QUALITY_INSPECTION_REPORT','CE','ROHS','ISO9001','ISO14001',
      'FDA','COC','AUTHORIZATION_LETTER','OTHER'])
);

-- ===========================================================================
-- 2. supplier_qualification · 资质档案（本模块的主表）
-- ===========================================================================
CREATE TABLE supplier_qualification (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 编码：租户内唯一、建后不可改（服务层强制）。它是档案上的稳定引用。
  code varchar(64) NOT NULL CHECK(btrim(code)<>''),
  -- **供应商（catalog SUPPLIER 角色）的逻辑 ID** —— 跨服务引用，不建外键。
  supplier_role_id uuid NOT NULL,
  -- 登记那一刻的供应商名快照（catalog 改名后历史资质仍能显示当时的供应商名）。
  supplier_name_snapshot varchar(200) NOT NULL DEFAULT '',
  -- 资质类型（封闭词表，见第二节：与 6.1.1.2 的附件资质同源）。
  qualification_type varchar(32) NOT NULL CHECK(qualification_type IN (
    'BUSINESS_LICENSE','QUALITY_INSPECTION_REPORT','CE','ROHS','ISO9001','ISO14001',
    'FDA','COC','AUTHORIZATION_LETTER','OTHER')),
  -- 证件名称：文件上的正式名称（「营业执照」「ISO9001:2015 质量管理体系证书」）。
  title varchar(200) NOT NULL CHECK(btrim(title)<>''),
  -- 证书编号：核验与对账时用。空串表示「该证件没有编号」（如产地证）。
  cert_no varchar(64) NOT NULL DEFAULT '',
  -- 发证机构。
  issuer varchar(120) NOT NULL DEFAULT '',
  -- **品牌授权关联 03 品牌**（catalog，跨服务引用，不建外键；未关联为 null）。
  brand_id uuid,
  -- 关联那一刻的品牌名快照。
  brand_name_snapshot varchar(200) NOT NULL DEFAULT '',
  -- 审核状态（受控枚举，转换由第三节的触发器钉死）。
  review_status varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(review_status IN ('DRAFT','PENDING','APPROVED','REJECTED','REVOKED')),
  -- 有效期窗口。**到期状态不落库**，由读出口按 now() 现算（第四节）。
  valid_from timestamptz NOT NULL,
  valid_until timestamptz NOT NULL,
  -- 提交审核的时刻（进入 PENDING 时由触发器补齐）。
  submitted_at timestamptz,
  -- 最近一次审核（通过 / 驳回 / 撤销）的人与时刻。
  reviewed_by uuid,
  reviewed_at timestamptz,
  review_note text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  -- 有效期窗口必须自洽：起止反了写进来会得到「永远不过期」或「一出生就过期」的档案。
  CHECK(valid_until > valid_from),
  -- 授权书就是品牌授权：**必须关联 03 品牌**（蓝图原文「品牌授权需关联 03 品牌」）。
  -- 不关联就等于「授权范围不明」，而授权范围不明时阻断判定没法回答「这个供应商能不能卖这个牌子」。
  CHECK(qualification_type <> 'AUTHORIZATION_LETTER' OR brand_id IS NOT NULL),
  -- 有品牌就必须留品牌名快照（没有快照时历史档案会显示不出品牌）。
  CHECK(brand_id IS NULL OR btrim(brand_name_snapshot) <> '')
);

-- ===========================================================================
-- 3. supplier_qualification_document · 资质文件（**只追加**）
-- ===========================================================================
-- 蓝图输入句之一是「资质文件上传」。文件本体**不入库**（只存对象存储的 key ——
-- 二进制进业务库会让备份、迁移与 RLS 审计都变成搬文件的工作），与 6.1.1.2 同一纪律。
-- 为什么是表而不是一列：续期时要上传新的扫描件，旧扫描件是「当时交的是什么」的凭据，
-- 覆盖掉就再也答不出来。
CREATE TABLE supplier_qualification_document (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  qualification_id uuid NOT NULL,
  -- 文件类别：
  --   ORIGINAL    首次登记时上传的原件
  --   RENEWAL     续期上传的新件
  --   ATTACHMENT  补充材料
  document_kind varchar(16) NOT NULL
    CHECK(document_kind IN ('ORIGINAL','RENEWAL','ATTACHMENT')),
  -- 对象存储的 key（不是 URL，也不是文件本体）。
  document_key varchar(500) NOT NULL CHECK(btrim(document_key)<>''),
  document_name varchar(200) NOT NULL DEFAULT '',
  mime_type varchar(120) NOT NULL DEFAULT '',
  -- 文件大小（字节）。数量刻度与全仓一致：numeric(19,6)，禁浮点。
  size_bytes numeric(19,6) NOT NULL DEFAULT 0 CHECK(size_bytes >= 0),
  uploaded_by uuid,
  uploaded_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,qualification_id,document_key)
);

-- ===========================================================================
-- 4. supplier_qualification_review · 审核流转事件（**只追加**）
-- ===========================================================================
CREATE TABLE supplier_qualification_review (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  qualification_id uuid NOT NULL,
  -- 该次流转在这只资质上的序号（从 1 开始递增）—— 界面按它排序，不靠随机 UUID 的行序。
  seq integer NOT NULL CHECK(seq >= 1),
  -- 动作词表（封闭）。
  action varchar(16) NOT NULL
    CHECK(action IN ('SUBMIT','APPROVE','REJECT','REVOKE','RESUBMIT')),
  from_status varchar(16) NOT NULL
    CHECK(from_status IN ('DRAFT','PENDING','APPROVED','REJECTED','REVOKED')),
  to_status varchar(16) NOT NULL
    CHECK(to_status IN ('DRAFT','PENDING','APPROVED','REJECTED','REVOKED')),
  -- 理由：驳回与撤销**必须**写明（服务层强制），否则「为什么被驳回」无从追。
  reason text NOT NULL DEFAULT '',
  operated_by uuid,
  operated_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,qualification_id,seq)
);

-- ===========================================================================
-- 5. supplier_qualification_reminder · 过期提醒（可流转，不是只追加）
-- ===========================================================================
-- 「即将过期提醒」是蓝图点名的输出（「输出：资质档案 + 过期提醒」）。
-- 它是一条**可确认的事实**：谁在什么时候被提醒了、后来有没有处理。
-- 提醒本身**不是**阻断 —— 阻断由阻断判定端点给（第五节），两件事分开。
CREATE TABLE supplier_qualification_reminder (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  qualification_id uuid NOT NULL,
  -- 提醒类别：
  --   EXPIRING 即将过期（仍在有效期内，但落在阈值窗口内）
  --   EXPIRED  已过期
  reminder_kind varchar(16) NOT NULL CHECK(reminder_kind IN ('EXPIRING','EXPIRED')),
  -- 提醒针对的到期日（qualification.valid_until 的日期部分）。
  due_date date NOT NULL,
  -- 生成这条提醒时的阈值窗口（天）—— 留痕，免得半年后有人问「当时按几天算的」。
  warning_days integer NOT NULL CHECK(warning_days >= 0),
  -- OPEN 未处理 / ACKNOWLEDGED 已确认 / RESOLVED 已处理（续期 / 撤销 / 关闭）
  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
  generated_at timestamptz NOT NULL,
  acknowledged_by uuid,
  acknowledged_at timestamptz,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 幂等：同一只资质、同一类提醒、同一到期日只生成一条（重复扫描不刷屏）。
  UNIQUE(tenant_id,qualification_id,reminder_kind,due_date)
);

-- ===========================================================================
-- 6. 约束（只在本迁移自建的表之间建；跨库一律不建）
-- ===========================================================================
ALTER TABLE supplier_qualification_document
  ADD CONSTRAINT supplier_qualification_document_qualification_fk
  FOREIGN KEY (tenant_id, qualification_id) REFERENCES supplier_qualification(tenant_id, id);
ALTER TABLE supplier_qualification_review
  ADD CONSTRAINT supplier_qualification_review_qualification_fk
  FOREIGN KEY (tenant_id, qualification_id) REFERENCES supplier_qualification(tenant_id, id);
ALTER TABLE supplier_qualification_reminder
  ADD CONSTRAINT supplier_qualification_reminder_qualification_fk
  FOREIGN KEY (tenant_id, qualification_id) REFERENCES supplier_qualification(tenant_id, id);

-- ===========================================================================
-- 7. 守卫触发器
-- ===========================================================================
-- 7.1 审核状态机：状态只能沿第三节那张图走。直连数据库改状态也被拒 ——
--     服务层是第一道闸，不是唯一一道。
CREATE FUNCTION guard_supplier_qualification_review_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  allowed boolean := false;
BEGIN
  IF NEW.review_status = OLD.review_status THEN
    RETURN NEW;
  END IF;
  allowed :=
    (OLD.review_status = 'DRAFT'    AND NEW.review_status = 'PENDING') OR
    (OLD.review_status = 'PENDING'  AND NEW.review_status = 'APPROVED') OR
    (OLD.review_status = 'PENDING'  AND NEW.review_status = 'REJECTED') OR
    (OLD.review_status = 'REJECTED' AND NEW.review_status = 'PENDING') OR
    (OLD.review_status = 'APPROVED' AND NEW.review_status = 'REVOKED') OR
    (OLD.review_status = 'REVOKED'  AND NEW.review_status = 'PENDING');
  IF NOT allowed THEN
    RAISE EXCEPTION '供应商资质审核状态不可从 % 变更为 %（允许的流转：DRAFT→PENDING、'
      'PENDING→APPROVED、PENDING→REJECTED、REJECTED→PENDING、APPROVED→REVOKED、REVOKED→PENDING）',
      OLD.review_status, NEW.review_status
      USING ERRCODE = 'restrict_violation';
  END IF;
  -- 进入 PENDING 必须留下提交时刻（界面与审计都靠它）。
  IF NEW.review_status = 'PENDING' THEN
    NEW.submitted_at := COALESCE(NEW.submitted_at, now());
  END IF;
  -- 进入审核结论态（通过 / 驳回 / 撤销）必须留下审核时刻。
  IF NEW.review_status IN ('APPROVED','REJECTED','REVOKED') THEN
    NEW.reviewed_at := COALESCE(NEW.reviewed_at, now());
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER supplier_qualification_review_transition
  BEFORE UPDATE OF review_status ON supplier_qualification
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_qualification_review_transition();

-- 7.2 审核流转事件只追加：任何 UPDATE / DELETE 一律拒绝。
--     「这张证上个月为什么被驳回」是审计事实，抹掉它等于没有审核留痕。
CREATE FUNCTION guard_supplier_qualification_review_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '供应商资质审核流转记录不可改写（表 supplier_qualification_review）：'
    '审核留痕只能追加，qualification_id=% , seq=%',OLD.qualification_id,OLD.seq
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER supplier_qualification_review_no_update
  BEFORE UPDATE ON supplier_qualification_review
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_qualification_review_append_only();
CREATE TRIGGER supplier_qualification_review_no_delete
  BEFORE DELETE ON supplier_qualification_review
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_qualification_review_append_only();

-- 7.3 资质文件只追加：续期是「再上传一份」，不是「覆盖掉原来那份」。
--     原来交的是什么，是合规追溯要用的凭据。
CREATE FUNCTION guard_supplier_qualification_document_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '供应商资质文件不可改写（表 supplier_qualification_document）：'
    '续期请上传新件，qualification_id=%',OLD.qualification_id
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER supplier_qualification_document_no_update
  BEFORE UPDATE ON supplier_qualification_document
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_qualification_document_append_only();
CREATE TRIGGER supplier_qualification_document_no_delete
  BEFORE DELETE ON supplier_qualification_document
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_qualification_document_append_only();

-- ===========================================================================
-- 8. 索引
-- ===========================================================================
-- 资质列表：按供应商 / 类型 / 审核状态筛，按到期日读（最先过期的排前面）。
CREATE INDEX supplier_qualification_list
  ON supplier_qualification(tenant_id, supplier_role_id, qualification_type, review_status, valid_until, id);
-- 到期扫描（生成提醒的主查询）：只看已通过审核的资质。
CREATE INDEX supplier_qualification_expiry_scan
  ON supplier_qualification(tenant_id, review_status, valid_until);
-- 品牌授权：按品牌反查授权书（阻断判定的主查询）。
CREATE INDEX supplier_qualification_brand
  ON supplier_qualification(tenant_id, brand_id, review_status, valid_until);
-- 文件：按资质读（详情页）。
CREATE INDEX supplier_qualification_document_qualification
  ON supplier_qualification_document(tenant_id, qualification_id, uploaded_at DESC, id);
-- 审核流转：按资质读历史。
CREATE INDEX supplier_qualification_review_qualification
  ON supplier_qualification_review(tenant_id, qualification_id, seq);
-- 提醒：清单按生成时刻倒序；按资质反查。
CREATE INDEX supplier_qualification_reminder_list
  ON supplier_qualification_reminder(tenant_id, status, generated_at DESC, id);
CREATE INDEX supplier_qualification_reminder_qualification
  ON supplier_qualification_reminder(tenant_id, qualification_id, due_date DESC);

-- ===========================================================================
-- 9. 多租户：RLS + 授权（与 trade V1 / V92 / V233 同一套写法）
-- ===========================================================================
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'supplier_qualification','supplier_qualification_document','supplier_qualification_review',
    'supplier_qualification_reminder','supplier_qualification_policy'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  supplier_qualification,supplier_qualification_document,supplier_qualification_review,
  supplier_qualification_reminder,supplier_qualification_policy TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  supplier_qualification,supplier_qualification_document,supplier_qualification_review,
  supplier_qualification_reminder,supplier_qualification_policy TO starcon_app;

-- ===========================================================================
-- 10. 本迁移的回归面（跨车道纪律）
-- ===========================================================================
--   * **不 ALTER 任何既有表**：V1 的 trade_tenancy_probe、V92 的七张履约表、
--     V233 的七张货源表都不碰 ⇒ 跨车道 DDL 回归面 = 0。
--   * 对 catalog 的 party / brand **只读逻辑 ID，不加任何约束**：跨库 FK 本来就禁止。
--   * 读取 6.1.2.1 的 supplier_source_* 三张表是**同库同服务的只读**，不 ALTER 它们。
--   * 新增函数全部带 supplier_qualification_ 前缀，与既有函数名不冲突。
--   * 版本号 V239 由调度者预分配，**不自己找最大版本号**（并行车道会同时提交迁移）。
