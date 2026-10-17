-- 11.1.1.2 售后单详情（卷 11 售后与退货的第**二**张单据视图）。
--
-- 蓝图 11-aftersales-and-returns.md 第 87-92 行原文：
--   职责：单售后全景：原订单信息、申请内容与买家诉求、商品行与数量、沟通记录、附件与凭证、
--         处置流程状态、退款与退货关联、平台侧状态、责任判定。
--   输入：aftersales_id；聚合子资源。
--   输出：详情 + 操作入口 + 时间线。
--   关系：上游 = 11.1.1.1；下游 = 11.2 退货、11.3 退款、11.4 纠纷。
--         **详情页要把"买家说了什么"和"我们做了什么"按时间线对齐**，这是纠纷举证的基础。
--
-- 归属：**trade 服务**（同 L3 11.1.1 锚点，同一 V156 簇）。
--
-- 本迁移**只新建表与触发器、零 ALTER 零 DROP 既有表**：
--   * aftersales_case 簇（V156，11.1.1.1 车道的写入所有者）只**读**；
--   * sales_order / sales_order_line / sales_order_party_snapshot /
--     order_address_snapshot（V99/V100/V101，9.1.1.1 车道的写入所有者）只**读**，
--     且刻意**不加外键**（加 FK 会让本迁移对别的车道的表产生 DDL 依赖，
--     而同批另有五条车道也在往 trade 落迁移）。跨表一致性由触发器在**写入时刻**真查一次钉死。
--
-- ===========================================================================
-- 一、职责句里九个区块各自落在哪 / 为什么这么落
-- ===========================================================================
-- 「原订单信息」    → 直接 JOIN sales_order 簇（V99，同库只读）。**不复制一份订单字段到
--                    本簇**：复制出来的订单号与订单金额会和真表漂移，而纠纷举证里
--                    「截图上的订单号」与「系统里的订单号」不一致是最致命的破绽。
-- 「申请内容与买家诉求」→ aftersales_case 已有列（reason_code / reason_note /
--                    buyer_request / requested_amount / requested_quantity），本模块只读不重复存。
-- 「商品行与数量」  → **本迁移新建** aftersales_case_line。V156 只在单头存了**合计**
--                    数量，没有行级明细；一张三行订单退其中一行，用合计数无法回答
--                    「退的是哪一行、退几件」。行只存 order_line_id，SKU 与单价
--                    **读 sales_order_line**（快照真值在那儿），不复制。
-- 「沟通记录」      → **本迁移新建** aftersales_message（追加式）。
-- 「附件与凭证」    → **本迁移新建** aftersales_attachment（追加式）。**只存对象存储引用，
--                    不存文件二进制**：本服务没有文件服务，存 base64 会让一行凭证把
--                    列表页的响应体积顶到几十 MB。
-- 「处置流程状态」  → aftersales_case.status + 时效函数 + 授权 / 工单（V156，只读）。
-- 「退款与退货关联」→ **11.2 / 11.3 未交付**，本迁移**不建影子表**（见第五节），
--                    由服务层如实报 Delivered=false 与归属模块。
-- 「平台侧状态」    → aftersales_case 的 platform_case_no / platform_case_url /
--                    platform_received_at + 两个时限（V156，只读）。
-- 「责任判定」      → 由**真实**的授权决定 + 原因分类 + 责任人流转在服务层合成；
--                    环节级归因（批次 / 生产 / 运输 / 仓储）属 **11.1.2.2 未交付**，如实报 false。
--
-- ===========================================================================
-- 二、沟通记录：追加式，且**刻意不假装已对接卷 2 平台消息**
-- ===========================================================================
-- 照抄 V150 order_risk_signal 与 V156 aftersales_owner_assignment 的同一条解法：
--   message_source ∈ ( TENANT_MAINTAINED / MODULE_2_PLATFORM_MESSAGE )
-- 卷 2 平台消息在 channel 服务独立库且**当前无任何平台消息表**（11.1.1.1 已实查），
-- 按 AGENTS.md 模块边界纪律禁止跨服务 SQL ⇒ 本服务读不到真实对话流。
-- 正式接口到位后由同步器按**同一形状**写入并把来源改成 MODULE_2_PLATFORM_MESSAGE，
-- **表结构与详情查询都不用改**（就像 V99 把平台来源提前写进 CHECK 的做法）。
--
-- ⚠️ `sent_at`（对话里的那一刻）与 `captured_at`（采集时刻）是**两件事**：
--   填 now() 会让一条三个月前的平台消息在纠纷举证里显示成「今天买家说的」，
--   而举证的时间线错位是会被平台直接采信不下去的。
CREATE TABLE aftersales_message (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,

  -- 方向：谁对谁说的。这是时间线「买家说了什么 vs 我们做了什么」的分类轴。
  direction varchar(16) NOT NULL CHECK(direction IN ('BUYER_TO_US','US_TO_BUYER')),

  channel varchar(16) NOT NULL CHECK(channel IN (
      'PLATFORM','EMAIL','CHAT','PHONE','INTERNAL_NOTE'
  )),

  -- 正文不许为空串：一条没有内容的对话记录在时间线上等于噪声，
  -- 而噪声会让真正那句话被埋掉。
  message_body text NOT NULL CHECK(btrim(message_body) <> '' AND length(message_body) <= 4000),

  -- 平台侧消息号（PLATFORM 渠道用）：与平台对账的钥匙。
  external_message_id varchar(128) NOT NULL DEFAULT '',

  message_source varchar(32) NOT NULL CHECK(message_source IN (
      'TENANT_MAINTAINED','MODULE_2_PLATFORM_MESSAGE'
  )),
  -- 写成 MODULE_* 时必须指明是哪份来源的哪一段，否则「这行是同步器写的」只是口头声明。
  source_reference varchar(128) NOT NULL DEFAULT '',

  -- 发生时刻（对话里的那一刻）：时间线**按它**排序。
  sent_at timestamptz NOT NULL,
  -- 采集时刻（照抄 V156 的 captured_at 纪律：填 now() 会让老消息被当成今天的）。
  captured_at timestamptz NOT NULL,

  -- 我们发出的消息必须有作者；买家发来的没有「我方用户」。
  author_id uuid,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(direction <> 'US_TO_BUYER' OR author_id IS NOT NULL),
  CHECK(message_source = 'TENANT_MAINTAINED' OR btrim(source_reference) <> '')
);

-- ⚠️ **「同一外部消息号不重复」用部分唯一索引，而不是表级 UNIQUE + 哨兵值生成列**：
--   唯一性**只对有外部号的消息有意义**（没有外部号的消息本来就无从判重）。
--   若把空号统一 COALESCE 成一个哨兵常量，那反而让**所有**无外部号的消息互相冲突
--   （一张单上第二条手写备注就插不进去）—— 哨兵方案比不做还糟。
--   PG 的 UNIQUE 把 NULL 视为互不相同，却把 '' 当**普通值**，所以这里用
--   {@code WHERE btrim(external_message_id) <> ''} 的部分索引精确圈定要约束的行。
--   顺带把空白号（' '）也排除：btrim 后为空同样属于「没有外部号」。
CREATE UNIQUE INDEX aftersales_message_by_external_id
  ON aftersales_message(tenant_id, case_id, external_message_id)
  WHERE btrim(external_message_id) <> '';

-- ===========================================================================
-- 三、附件与凭证：追加式，只存引用不存字节
-- ===========================================================================
-- ⚠️ **没有 content 字段是刻意的**：本服务没有文件服务（16 合规与对象存储都未交付），
--   把图片 base64 塞进数据库会让一行凭证把详情响应顶到几十 MB，
--   而纠纷举证要打包的是**引用**（对象存储 key + 文件名 + 类别 + 提供方）。
--   storage_ref 是对象存储定位符，将来文件服务交付后由它按同一形状签发。
--
-- `attached_by_side` 单独一列而不是从 evidence_kind 推：同一张照片既可能是
-- 买家上传的也可能是我们从平台转存下来的，两者在举证里的分量不同，
--   靠类别反推会丢信息（BUYER_EVIDENCE 完全可能是平台转存的买家证据）。
CREATE TABLE aftersales_attachment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,

  -- 凭证类别：纠纷举证要按类目组织材料。
  evidence_kind varchar(24) NOT NULL CHECK(evidence_kind IN (
      'BUYER_EVIDENCE','OUR_EVIDENCE','PRODUCT_PHOTO','DELIVERY_PROOF',
      'PAYMENT_PROOF','INSPECTION_REPORT','OTHER'
  )),

  file_name varchar(255) NOT NULL CHECK(btrim(file_name) <> ''),
  content_type varchar(128) NOT NULL DEFAULT '',
  -- 对象存储定位符（**不是** URL 全文：将来由文件服务签发，形如 key）。
  storage_ref varchar(512) NOT NULL CHECK(btrim(storage_ref) <> ''),
  file_size_bytes bigint CHECK(file_size_bytes IS NULL OR file_size_bytes >= 0),
  description text NOT NULL DEFAULT '' CHECK(length(description) <= 1000),

  -- 谁提供的这份凭证（买家 / 我方）：举证时「平台判责看谁的证据」是第一等问题。
  attached_by_side varchar(8) NOT NULL CHECK(attached_by_side IN ('BUYER','US')),

  evidence_source varchar(32) NOT NULL CHECK(evidence_source IN (
      'TENANT_MAINTAINED','MODULE_2_PLATFORM_MESSAGE'
  )),
  source_reference varchar(128) NOT NULL DEFAULT '',
  -- 采集时刻（照抄 V150 / V156 的同一条纪律）。
  captured_at timestamptz NOT NULL,

  uploaded_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张单里同一个引用只登记一次（同步器重复投递的兜底）。
  UNIQUE(tenant_id,case_id,storage_ref),
  CHECK(evidence_source = 'TENANT_MAINTAINED' OR btrim(source_reference) <> '')
);

-- ===========================================================================
-- 四、商品行与数量
-- ===========================================================================
-- ⚠️ **只存 order_line_id，不复制 sku_code / sku_name / 单价**：那些字段的真值在
--   sales_order_line（V99 的下单快照）。复制一份就会与订单行漂移，而「详情页显示的
--   SKU 和订单页显示的不一样」在纠纷举证里是直接被质疑的。
--   行级金额 optional：**只有退款类**（退货退款 / 仅退款）带金额，
--   换货 / 补发 / 维修的行是「同意换几件」不是一个金额（与 V156 的授权同一条纪律）。
CREATE TABLE aftersales_case_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,

  -- 指向 sales_order_line（**刻意不加外键**，见文件头）。
  order_line_id uuid NOT NULL,

  -- 涉及数量：numeric(19,6)，与 V156 的 requested_quantity 同一刻度，禁浮点。
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),

  -- 行级申请金额（**只有退款类带**）：null 表示「这类售后不谈钱」。
  requested_amount numeric(19,4) CHECK(requested_amount IS NULL OR requested_amount >= 0),
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),

  -- 买家对这一行的原话（「第 2 那个蓝色的掉漆了」）：举证时要能对上具体是哪一件。
  buyer_note text NOT NULL DEFAULT '' CHECK(length(buyer_note) <= 1000),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张售后单里同一订单行只登记一次：重复登记会让「这个 SKU 退了几件」有两个答案。
  UNIQUE(tenant_id,case_id,order_line_id),
  -- 币种与金额同进同出。
  CHECK((requested_amount IS NULL) = (currency IS NULL))
);

-- ===========================================================================
-- 五、护栏触发器：把三条硬规则钉在 DB 层（与 V156 的 guard_* 同一套写法）
-- ===========================================================================
-- ① 附件与沟通记录挂在**真实的、还没结案**的售后单上；
-- ② 商品行必须挂在**真实的、属于本单所属订单的**订单行上（替代刻意不建的外键）；
-- ③ 商品行涉及数量**累计不得超过**单头的申请数量。

-- ---- ①② 沟通记录 / 附件：单必须存在且未结案 --------------------------------
CREATE FUNCTION guard_aftersales_case_child() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(20);
BEGIN
  SELECT status INTO t_status FROM aftersales_case
   WHERE tenant_id = NEW.tenant_id AND id = NEW.case_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '售后单不存在或不属于当前租户：case_id=%', NEW.case_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 结案后不再收新证据：终态的语义是「这张单办完了」。
  -- 纠纷举证要补的材料属 11.4 的地盘，不该回填进已结案的单里 ——
  -- 回填会让「结案时到底有什么证据」这个问题永远没有答案。
  IF t_status IN ('RESOLVED','REJECTED','CLOSED') THEN
    RAISE EXCEPTION '售后单已结案（status=%），不能再追加沟通记录或凭证', t_status
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_message
  BEFORE INSERT ON aftersales_message
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_case_child();

CREATE TRIGGER trg_guard_aftersales_attachment
  BEFORE INSERT ON aftersales_attachment
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_case_child();

-- ---- ②③ 商品行：订单行归属 + 数量累计 --------------------------------------
-- ⚠️ 这个触发器**真的去 sales_order_line 查一次**，这正是本迁移刻意不加外键的代价与收益：
--   加 FK 会让本迁移在别的车道 ALTER 这些表时炸掉（跨车道 DDL 回归面），
--   触发器则只读一张永远存在的表，把归属校验放在**写入时刻**做掉 ——
--   「行指向一个别的订单的行」这种脏数据压根进不来。
CREATE FUNCTION guard_aftersales_case_line() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(20);
  t_case_type varchar(24);
  t_case_quantity numeric(19,6);
  t_case_currency char(3);
  t_line_order_id uuid;
  t_sum numeric(19,6);
BEGIN
  SELECT status, case_type, requested_quantity, currency
    INTO t_status, t_case_type, t_case_quantity, t_case_currency
    FROM aftersales_case
   WHERE tenant_id = NEW.tenant_id AND id = NEW.case_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '售后单不存在或不属于当前租户：case_id=%', NEW.case_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status IN ('RESOLVED','REJECTED','CLOSED') THEN
    RAISE EXCEPTION '售后单已结案（status=%），不能再登记商品行', t_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 订单行必须真实存在，且属于**本单所关联的那张订单**。
  -- 少了 order_id 这一段，「A 单的售后行指向 B 单的商品行」会完全合法。
  SELECT order_id INTO t_line_order_id
    FROM sales_order_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.order_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '订单行不存在或不属于当前租户：order_line_id=%', NEW.order_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_line_order_id <> (SELECT order_id FROM aftersales_case
                          WHERE tenant_id = NEW.tenant_id AND id = NEW.case_id) THEN
    RAISE EXCEPTION '订单行不属于本售后单所关联的订单：order_line_id=%', NEW.order_line_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 换货 / 补发 / 维修**不许带金额**（与 V156 的授权同一条纪律）。
  IF t_case_type IN ('EXCHANGE','REISSUE','REPAIR') AND NEW.requested_amount IS NOT NULL THEN
    RAISE EXCEPTION '类型 % 的商品行不带金额', t_case_type USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.currency IS NOT NULL AND NEW.currency <> t_case_currency THEN
    RAISE EXCEPTION '行金额币种 % 与售后单币种 % 不一致', NEW.currency, t_case_currency
      USING ERRCODE = 'check_violation';
  END IF;

  -- 涉及数量累计不得超过单头的申请数量。
  -- ⚠️ 累计值含本次这一行；本表是 append-only（运行角色没有 UPDATE/DELETE 权限），
  --   所以「读—加—写」之间不会被别的写插队改掉同一行的数量。
  SELECT coalesce(sum(quantity), 0) INTO t_sum
    FROM aftersales_case_line
   WHERE tenant_id = NEW.tenant_id AND case_id = NEW.case_id;

  IF t_sum + NEW.quantity > t_case_quantity THEN
    RAISE EXCEPTION '涉及数量累计 % 超过售后单申请数量 %',
      t_sum + NEW.quantity, t_case_quantity USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_case_line
  BEFORE INSERT ON aftersales_case_line
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_case_line();

-- ===========================================================================
-- 六、索引：每一条都对应一个真实会被跑的查询形状
-- ===========================================================================
-- ① 详情页的主查询：按单取商品行（照抄 V156 的按单反查形状）。
CREATE INDEX aftersales_case_line_by_case
  ON aftersales_case_line(tenant_id, case_id);
-- ② 时间线读沟通记录（详情页最常翻的一段）与按单回查凭证。
CREATE INDEX aftersales_message_by_case_sent
  ON aftersales_message(tenant_id, case_id, sent_at);
CREATE INDEX aftersales_attachment_by_case_captured
  ON aftersales_attachment(tenant_id, case_id, captured_at DESC);

-- ===========================================================================
-- 七、多租户：RLS + 授权（与 V156 第九节同一套写法）
-- ===========================================================================
-- 三张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：详情页一次聚合会读订单号、买家姓名、收货地址、买家诉求原文、
--   责任人与授权金额。漏掉租户谓词就是**跨租户泄露**（A 家能看到 B 家的买家与退款额），
--   而详情页是**按 id 直达**的 URL —— 猜到一个 UUID 就能看，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'aftersales_case_line','aftersales_message','aftersales_attachment'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  aftersales_case_line, aftersales_message, aftersales_attachment TO trade_app;

-- 授权在前、收回在后（与 V156 同序）。
-- 三张表全部**追加式**：商品行、沟通记录与凭证都是纠纷举证的原材料，
-- 改掉或删掉一行等于毁掉证据（与 V156 收回四张留痕表 UPDATE/DELETE 同一条纪律）。
-- ⚠️ 表级 REVOKE 之后 has_table_privilege('trade_app',t,'UPDATE') 必须为 false ——
--   这是可验证的；而「逐列 REVOKE」在表级 GRANT 之后是空操作，测不出来，
--   所以这里走表级收回这条已经被验证过的路（见 V156 第一节的同一条结论）。
REVOKE UPDATE,DELETE ON aftersales_case_line FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_message FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_attachment FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：aftersales_case 簇（V156）与 sales_order 簇
-- （V99/V100/V101）全部只读。本模块对它们的全部依赖都是 SELECT / JOIN，
-- 跨车道 DDL 回归面 = 0。
