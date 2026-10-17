-- 11.1.1.1 售后单列表（卷 11 售后与退货的第一张单据）。
--
-- 蓝图 11-aftersales-and-returns.md 第 80-84 行原文：
--   职责：售后单主列表：售后号、关联订单、店铺、类型（退货退款/仅退款/换货/补发/维修）、金额、
--         原因、状态、时效（平台要求回复/处理时限）、责任人，支持多维筛选与批量处理。
--   输入：筛选条件（类型、状态、原因、店铺、时效、金额）；用户数据范围。
--   输出：列表 + 时效预警标记；批量操作（授权、备注、转工单）。
--   关系：上游 = 9 订单、15 客服、平台消息；下游 = 11.1.1.2 详情、11.2/11.3。
--         **平台时效倒计时必须显眼**：超时会直接影响账号绩效与资金冻结。
--
-- 归属：**trade 服务**（蓝图 11 卷 L2 表 11.1 售后受理 → 主责服务 trade；卷 11 的三服务
-- 列表是 trade + finance + warehouse，取第一项 trade）。
--
-- 本迁移**只新建表与函数、零 ALTER 零 DROP 既有表** —— sales_order 簇（V99/V100/V101）
-- 是 9.1.1.1 车道的写入所有者，本模块只**读**它，跨车道 DDL 回归面 = 0。
-- 刻意**不加外键**指向 sales_order：加 FK 会让本迁移对别的车道的表产生 DDL 依赖，
-- 而同批另有三条车道（9.2.1.3 / 9.3.3.2 / 9.5.2.1）也在往 trade 落迁移。
-- 订单存在性由服务层在写入口真查一次 sales_order 校验（见 AftersalesCaseService）。
--
-- ===========================================================================
-- 一、蓝图加粗句「平台时效倒计时必须显眼」的落地方式
-- ===========================================================================
-- 这句话有两种读法，本迁移把两种都当真：
--   (1) **它是数据要求**：时效必须是可排序、可筛选的**真字段**，不是页面上算给人看的临时值。
--       ⇒ reply_due_at / resolve_due_at 是真列；effective_due_at 是真列（由触发器算出来，
--         不是视图里的表达式 —— 表达式没法建索引，「按最紧急的时限排」就会退化成全表排序）；
--       时效状态由 IMMUTABLE 函数 aftersales_timeliness_state() 现算，**能进 WHERE 和 ORDER BY**，
--         筛选与排序都在数据库层完成，不在 Java 里过滤。
--   (2) **它是 UX 要求**：见前端 apps/web/src/components/aftersales/。后端负责保证
--       「状态标记是独立枚举、且和 due_at 同时可读」，配色与倒计时呈现是前端的事。
--
-- ⚠️ **effective_due_at 用触发器维护而不是 GENERATED ALWAYS AS (...least(...))**：
--   最紧的那个时限要在**写入时**就定下来并被索引到；用生成列虽然少一个触发器，
--   但一旦 PG 对 LEAST 的不可变性判定与预期不同，整个迁移会在别的车道跑测试时炸掉。
--   触发器是显式的、可审阅的（AGENTS.md：关键 SQL 必须可审阅），代价只是一处重复。
--
-- ⚠️ **「now」由调用方传入而不是函数内部取 now()**：一次列表查询里的所有行必须用
--   **同一个**参考时刻判时效，否则翻页时同一张单会在「临期」和「正常」之间跳。
--   传参还让「某个时刻的时效」可测（测试直接断言写回的时刻值）。
--
-- 时效状态四值 + 一个终态：
--   OVERDUE     已超时 —— 平台已经在计绩效 / 可能冻结资金
--   DUE_SOON    临期 —— 距时限 <= 24 小时（阈值写死在函数里，语义在注释里）
--   ON_TRACK    正常
--   RESOLVED    已处理（resolved_at 非空）—— **倒计时停止**，不再报警
--   NO_DEADLINE 平台未给时限 —— 刻意与 ON_TRACK 分开：前者是「没有人给时限」，
--               后者是「有时限且还早」。合成一个值会让「平台没给时限」被读成「不着急」。
-- ===========================================================================
CREATE FUNCTION aftersales_timeliness_state(due_at timestamptz, done_at timestamptz, at timestamptz)
RETURNS varchar
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE
           WHEN due_at IS NULL THEN 'NO_DEADLINE'
           WHEN done_at IS NOT NULL THEN 'RESOLVED'
           WHEN due_at < at THEN 'OVERDUE'
           WHEN due_at <= at + interval '24 hours' THEN 'DUE_SOON'
           ELSE 'ON_TRACK'
         END
$$;

-- 单号序列。售后号与工单号都是**每租户唯一**（UNIQUE(tenant_id, case_no)），
-- 序列本身是全局的 —— 两个租户的号段不重叠属于额外好处，不是要求。
CREATE SEQUENCE aftersales_case_no_seq;
CREATE SEQUENCE aftersales_ticket_no_seq;

-- ===========================================================================
-- 二、售后单主表
-- ===========================================================================
-- ⚠️ **没有 platform_shop_code 列**，是刻意的：
--   sales_order 实查**没有 shop_code 列**（V99 的 sales_order 只有 order_no /
--   order_source / currency / payment_method / status / 金额，没有店铺），
--   卷 2 的店铺在 channel 服务独立库，按 AGENTS.md 模块边界纪律禁止跨服务 SQL。
--   ⇒ 店铺维度当前只能落到**平台级** order_source（V99 的 CHECK 已含
--     AMAZON / SHOPIFY / EBAY / TIKTOK_SHOP / ALIBABA_1688）。
--   **不建一个恒为空的 shop 列**：空列会被读成「这家店没有售后单」，
--   而真相是「系统还不知道有哪些店」。D5 平台单同步落地后由同步器补列，
--   本表结构与查询都不用改（就像 V99 把平台来源提前写进 CHECK 的做法）。
CREATE TABLE aftersales_case (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 售后号：租户内唯一。
  case_no varchar(32) NOT NULL,

  -- 关联订单（卷 9）。**刻意不加外键**，见文件头。
  order_id uuid NOT NULL,

  -- 五种类型：蓝图职责句点名的五种，一个都不能少。
  case_type varchar(24) NOT NULL CHECK(case_type IN (
      'RETURN_REFUND','REFUND_ONLY','EXCHANGE','REISSUE','REPAIR'
  )),

  status varchar(20) NOT NULL CHECK(status IN (
      'RECEIVED','AUTHORIZING','IN_PROCESS','WAITING_BUYER','RESOLVED','REJECTED','CLOSED'
  )),

  -- 原因分类：照蓝图 11.1.2.1 点名的七类。
  -- ⚠️ 11.1.2.1 未交付，这里用的是**内联词表**而不是外键到原因分类树 ——
  --   建一张只有自己写的分类树表，等于替 11.1.2.1 定义了它没定义的语义。
  --   七类词表与 11.1.2.1 蓝图原文逐字对齐，将来它落地时直接建树并把本列改成 FK。
  reason_code varchar(32) NOT NULL CHECK(reason_code IN (
      'QUALITY_DEFECT','DESCRIPTION_MISMATCH','SIZE_MISMATCH','LOGISTICS_DAMAGE',
      'BUYER_REASON','MISTAKE_BUY','FRAUD_RETURN'
  )),
  -- 自由文本只做补充：**不替代** reason_code（蓝图 11.1.2.1 加粗句：
  -- 「原因口径不统一会让退货率分析失真 —— 必须强制选择分类而不是留自由文本」）。
  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 1000),

  -- 金额与数量：一律 numeric，禁浮点（AGENTS.md）。
  -- 金额口径 = **买家申请退 / 换的金额**，不是原单金额，也不是平台实际退款额
  -- （后者是 11.3 + 12 财务的账，本服务算不出来）。
  requested_amount numeric(19,4) NOT NULL CHECK(requested_amount >= 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 换货 / 补发 / 维修类也有数量；纯金额诉求（仅退款）记 1.000000 而不是 0，
  -- 因为「数量 = 0 的售后单」在 UI 上会被读成数据错误。
  requested_quantity numeric(19,6) NOT NULL CHECK(requested_quantity > 0),

  -- 平台侧单号：平台受理后回传的号，是与平台对账的钥匙。
  platform_case_no varchar(128) NOT NULL DEFAULT '',
  platform_case_url varchar(512) NOT NULL DEFAULT '',
  -- 平台受理时刻：时效倒计时的**起点**通常是它而不是 created_at
  -- （内部补录的单不能因为晚录入就多算时限），故单列并允许为空。
  platform_received_at timestamptz,

  -- 买家诉求原文：详情页（11.1.1.2 未交付）与纠纷举证要用。
  buyer_request text NOT NULL DEFAULT '' CHECK(length(buyer_request) <= 4000),

  -- ---- 平台时效（蓝图加粗句的落点，见文件头第一节）----
  -- 平台要求**回复**时限：平台问「同不同意退」，你不答就判平台败诉。
  reply_due_at timestamptz,
  -- 平台要求**处理**时限（完成退款 / 完成换货发货）。
  resolve_due_at timestamptz,
  -- **最紧的那个**：列表默认按它升序排，超时的排在最前面。
  -- 由触发器维护 ⇒ 可建索引、可 ORDER BY、可比较大小。
  effective_due_at timestamptz,

  -- 受理时刻（本服务收到这条售后请求的时刻）：列表默认按它倒序。
  received_at timestamptz NOT NULL,
  -- 终态时刻：非空即停止倒计时（RESOLVED / REJECTED / CLOSED 三态都有）。
  resolved_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,case_no),

  -- 终态与 resolved_at **严格等价**：进终态必须带时刻，出终态必须清空。
  -- 两条合起来挡的是同一个方向的两种漏：只改状态不落时刻（时效倒计时永远不停止，
  -- 一张三个月前结案的单会一直被报成超时），以及带时刻却不进终态（倒计时莫名停掉，
  -- 一张正在被平台计时的单被误报成安全）。
  CHECK((status IN ('RESOLVED','REJECTED','CLOSED')) = (resolved_at IS NOT NULL)),
  -- 时效两列至少要给一个：一张**永远没有时限**的售后单既不会提醒也不会被排进超时队列，
  -- 等于从列表里消失。确实由平台不管的（内部录入、无平台约束）也要给一个内部时限。
  CHECK(reply_due_at IS NOT NULL OR resolve_due_at IS NOT NULL),
  -- 受理时刻不许晚于回复时限的判定基准 —— 不对本模块没意义，只会让「已超时」的语义更绕。
  CHECK(reply_due_at IS NULL OR resolve_due_at IS NULL OR reply_due_at <= resolve_due_at)
);

CREATE FUNCTION aftersales_case_set_effective_due() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- LEAST 忽略 NULL：只给了一个时限就取那一个，两个都给取更紧的，两个都没给留 NULL。
  NEW.effective_due_at := least(NEW.reply_due_at, NEW.resolve_due_at);
  RETURN NEW;
END
$$;

-- effective_due_at 由触发器算，不接受调用方直接写（运行角色也 REVOKE 了这个列，见第九节）。
CREATE TRIGGER trg_aftersales_case_effective_due
  BEFORE INSERT OR UPDATE OF reply_due_at, resolve_due_at, resolved_at
  ON aftersales_case
  FOR EACH ROW
  EXECUTE FUNCTION aftersales_case_set_effective_due();

-- ===========================================================================
-- 三、责任人（上游 15 客服未交付 ⇒ 租户可维护的登记表，照抄 V150 order_risk_signal）
-- ===========================================================================
-- 实查集成头 a052a025：卷 15 客户与客服 24 个 L4 里**只交付 1 个**
-- （15.4.1.1，在 backend/services/crm 的独立库 starcon_crm），卷 2 平台消息**未交付**。
-- 二者都是别的服务的独立库，按 AGENTS.md 模块边界纪律禁止跨服务 SQL / 视图 / FK
-- ⇒ 本服务读不到「责任人是谁」与「平台说了什么」。
-- ⇒ 照抄 V150 的既有解法：落成**租户可维护的真实表**，每行强制
--     * owner_source ∈ ( TENANT_MAINTAINED / MODULE_15_SERVICE_STAFF )
--     * captured_at（**采集时刻**，不是登记时刻）
--   正式接口到位后由同步器按**同一形状**写入并把来源改成 MODULE_*，
--   **表结构与列表查询都不用改**。
-- ⚠️ owner_source 刻意**不伪造 L4 模块号**（照抄 V150 的同一条纪律）：
--   卷 15 里目前没有任何一个 L4 是「售后责任人分配」的交付方，
--   编一个 15.3.2.9 之类的号会让后来的人去查一个不存在的模块。
--   capabilities 里如实报 upstreamDelivered=false。
--
-- 追加式（append-only）：当前责任人 = captured_at 最大的那一行。
-- 「改派」不是 UPDATE 这一行，而是**追加一行** —— 责任人流转是纠纷举证的一部分，
-- 抹掉前一任就等于毁掉证据。
CREATE TABLE aftersales_owner_assignment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,

  -- 责任人标识：来源方给的键不一定一样，所以是文本而不是外键。
  owner_ref varchar(128) NOT NULL CHECK(btrim(owner_ref) <> ''),
  owner_display_name varchar(128) NOT NULL DEFAULT '',
  owner_role_label varchar(64) NOT NULL DEFAULT '',

  owner_source varchar(32) NOT NULL CHECK(owner_source IN (
      'TENANT_MAINTAINED','MODULE_15_SERVICE_STAFF'
  )),
  -- 写成 MODULE_* 时必须指明是哪份来源的哪一段，否则「这行是同步器写的」只是口头声明。
  source_reference varchar(128) NOT NULL DEFAULT '',

  -- 采集时刻（照抄 V150 的 captured_at 纪律：填 now() 会让三个月前的分配被当成今天的）。
  captured_at timestamptz NOT NULL,

  assigned_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,case_id,captured_at),
  CHECK(owner_source = 'TENANT_MAINTAINED' OR btrim(source_reference) <> '')
);

-- ===========================================================================
-- 四、备注（批量操作 B 的产物）
-- ===========================================================================
CREATE TABLE aftersales_case_note (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,

  -- 备注不许为空串：「我批量点了备注但没写内容」不该被当成一次成功备注。
  note_body text NOT NULL CHECK(btrim(note_body) <> '' AND length(note_body) <= 2000),

  note_source varchar(20) NOT NULL CHECK(note_source IN ('TENANT_BATCH','TENANT_SINGLE')),
  -- 批量来的备注必须能追回是哪一次批量：批量备注是逐条结果回报的凭据，
  -- 事后只看到「有一条备注」而不知道它属于哪次操作，等于没法复盘。
  batch_id uuid,
  author_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK((note_source = 'TENANT_BATCH') = (batch_id IS NOT NULL))
);

-- ===========================================================================
-- 五、授权（批量操作 A 的产物）
-- ===========================================================================
-- ⚠️ authorization_kind 与 case_type 是**一一对应**的，由触发器 guard_aftersales_authorization_kind
--   钉死：
--     RETURN_REFUND → RETURN_APPROVED    REFUND_ONLY → REFUND_APPROVED
--     EXCHANGE     → EXCHANGE_APPROVED   REISSUE     → REISSUE_APPROVED
--     REPAIR       → REPAIR_APPROVED
--   允许自由组合的话，「给一张换货单批了一笔退款授权」会变成一条完全合法、且再也没人能
--   解释清楚的记录。
CREATE TABLE aftersales_authorization (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,

  authorization_kind varchar(24) NOT NULL CHECK(authorization_kind IN (
      'RETURN_APPROVED','REFUND_APPROVED','EXCHANGE_APPROVED','REISSUE_APPROVED','REPAIR_APPROVED'
  )),
  decision varchar(20) NOT NULL CHECK(decision IN ('APPROVED','PARTIALLY_APPROVED','REJECTED')),

  -- 授权金额：**只有退款类带**（退货退款 / 仅退款）。换货、补发、维修授权的是「同意办」
  -- 不是一个金额，硬塞一个 0 或一个等于申请额的数字进去会让财务侧对不上账。
  authorized_amount numeric(19,4) CHECK(authorized_amount IS NULL OR authorized_amount >= 0),
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 1000),
  batch_id uuid,
  decided_by uuid NOT NULL,
  decided_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 拒绝就没有授权金额（有金额的「拒绝」在财务侧会被读成已批的额度）。
  CHECK(decision <> 'REJECTED' OR authorized_amount IS NULL),
  -- 币种与金额同进同出。
  CHECK((authorized_amount IS NULL) = (currency IS NULL))
);

-- ===========================================================================
-- 六、工单（批量操作 C 的产物）
-- ===========================================================================
-- ⚠️ 「转工单」**不假装已对接 11.4.2.1 客诉工单**（未开发）——它在本服务内落一条真实的
--   工单记录，工单号、单号、优先级、状态全是本服务自己管的事实。
--   11.4.2.1 交付后由它接管这张表，本模块的列表仍可读它。
--   owner_module 字段显式记下「这张工单归谁」，避免将来被误以为是本模块自造的影子表。
CREATE TABLE aftersales_service_ticket (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  ticket_no varchar(32) NOT NULL,
  case_id uuid NOT NULL,

  ticket_status varchar(16) NOT NULL CHECK(ticket_status IN ('OPEN','IN_PROGRESS','RESOLVED','CANCELLED')),
  priority varchar(8) NOT NULL CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 1000),

  -- 归属模块：11.4.2.1 客诉工单（**未交付**，见 capabilities）。
  owner_module varchar(16) NOT NULL DEFAULT '11.4.2.1'
    CHECK(owner_module = '11.4.2.1'),

  batch_id uuid,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,ticket_no),
  -- 一张售后单**只转一次**工单。重复转会产生两个号、两个负责人，
  -- 而「到底谁在跟这张单」从此没人说得清 —— 第二次转报失败并附上已有工单号。
  UNIQUE(tenant_id,case_id)
);

-- ===========================================================================
-- 七、护栏触发器（与 V150 的 guard_* 同一套写法：把蓝图硬规则钉在 DB 层）
-- ===========================================================================
-- 授权必须挂在**真实的、类型匹配的、还没结案**的售后单上。
CREATE FUNCTION guard_aftersales_authorization_kind() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_case_type varchar(24);
  t_status varchar(20);
  t_amount numeric(19,4);
  t_currency char(3);
  t_expected varchar(24);
BEGIN
  SELECT case_type, status, requested_amount, currency
    INTO t_case_type, t_status, t_amount, t_currency
    FROM aftersales_case
   WHERE tenant_id = NEW.tenant_id AND id = NEW.case_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '售后单不存在或不属于当前租户：case_id=%', NEW.case_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status IN ('RESOLVED','REJECTED','CLOSED') THEN
    RAISE EXCEPTION '售后单已结案（status=%），不能再授权', t_status USING ERRCODE = 'check_violation';
  END IF;

  t_expected := CASE t_case_type
      WHEN 'RETURN_REFUND' THEN 'RETURN_APPROVED'
      WHEN 'REFUND_ONLY'   THEN 'REFUND_APPROVED'
      WHEN 'EXCHANGE'      THEN 'EXCHANGE_APPROVED'
      WHEN 'REISSUE'       THEN 'REISSUE_APPROVED'
      WHEN 'REPAIR'        THEN 'REPAIR_APPROVED'
    END;
  IF NEW.authorization_kind <> t_expected THEN
    RAISE EXCEPTION '授权类型与售后单类型不匹配：case_type=% 需要 %，收到 %',
      t_case_type, t_expected, NEW.authorization_kind USING ERRCODE = 'check_violation';
  END IF;

  -- 授权额不得超过申请额（部分授权是允许的，**超**授权不是）。
  IF NEW.authorized_amount IS NOT NULL THEN
    IF NEW.authorized_amount > t_amount THEN
      RAISE EXCEPTION '授权金额 % 超过申请金额 %', NEW.authorized_amount, t_amount
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.currency <> t_currency THEN
      RAISE EXCEPTION '授权币种 % 与售后单币种 % 不一致', NEW.currency, t_currency
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- 换货 / 补发 / 维修类**不许带金额**（表级 CHECK 管不到 type，这里按类型钉死）。
  IF t_case_type IN ('EXCHANGE','REISSUE','REPAIR') AND NEW.authorized_amount IS NOT NULL THEN
    RAISE EXCEPTION '类型 % 的授权不带金额', t_case_type USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_authorization_kind
  BEFORE INSERT ON aftersales_authorization
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_authorization_kind();

-- ===========================================================================
-- 八、索引：每一条都对应一个真实会被跑的查询形状
-- ===========================================================================
-- ① **列表默认排序**：命中筛选且未结案的单，按最紧的时限升序（超时排在最前）。
--    部分索引 effective_due_at IS NOT NULL：已结案的没有时限，不该占索引空间。
CREATE INDEX aftersales_case_open_by_due
  ON aftersales_case(tenant_id, effective_due_at, id)
  WHERE resolved_at IS NULL;
-- ② 按状态筛 + 按受理时刻倒序（「待处理」页签的默认序）。
CREATE INDEX aftersales_case_by_status_received
  ON aftersales_case(tenant_id, status, received_at DESC);
-- ③ 按类型筛。
CREATE INDEX aftersales_case_by_type
  ON aftersales_case(tenant_id, case_type, received_at DESC);
-- ④ 按原因筛（11.1.5 退货率分析的口径入口）。
CREATE INDEX aftersales_case_by_reason
  ON aftersales_case(tenant_id, reason_code, received_at DESC);
-- ⑤ 「这张订单上有哪些售后」（9 订单 → 11 售后的反向查询）。
CREATE INDEX aftersales_case_by_order
  ON aftersales_case(tenant_id, order_id);
-- ⚠️ 刻意**没有**「按平台筛」的索引：aftersales_case 上**没有** platform_source 列。
--   平台维度来自 sales_order.order_source（V99，同库只读），它的索引由 V99 自己管。
--   在本表上建一个平台索引只会给一个不存在的列建索引。
-- ⑥ 金额区间筛选（**闭区间**，见服务层注释）。
CREATE INDEX aftersales_case_by_amount
  ON aftersales_case(tenant_id, requested_amount);
-- ⑦ 责任人按单取当前值（captured_at 倒序 ⇒ 走索引拿第一行）。
CREATE INDEX aftersales_owner_assignment_by_case
  ON aftersales_owner_assignment(tenant_id, case_id, captured_at DESC);
-- ⑧ 备注 / 授权 / 工单按单反查（详情页 11.1.1.2 与列表留痕展开）。
CREATE INDEX aftersales_case_note_by_case
  ON aftersales_case_note(tenant_id, case_id, created_at DESC);
CREATE INDEX aftersales_authorization_by_case
  ON aftersales_authorization(tenant_id, case_id, decided_at DESC);
CREATE INDEX aftersales_ticket_by_case
  ON aftersales_service_ticket(tenant_id, case_id);
-- ⑨ 按批号反查一次批量操作的全部产物（「这批 30 条都发生了什么」）。
CREATE INDEX aftersales_case_note_by_batch
  ON aftersales_case_note(tenant_id, batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX aftersales_authorization_by_batch
  ON aftersales_authorization(tenant_id, batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX aftersales_ticket_by_batch
  ON aftersales_service_ticket(tenant_id, batch_id) WHERE batch_id IS NOT NULL;

-- ===========================================================================
-- 九、多租户：RLS + 授权（与 trade V92 / V144 / V150 第八节同一套写法）
-- ===========================================================================
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：列表是**多维筛选 + 分页 + 聚合**，
--   漏掉租户谓词就是**跨租户泄露** —— A 家会看到 B 家的买家姓名与退款金额。
-- FORCE 的原因：迁移/owner 角色在别的表上靠 RLS 豁免跑迁移，本服务统一 FORCE，
--   保证连 owner 连接也过策略（V150 第 451 行同一结论）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'aftersales_case','aftersales_owner_assignment','aftersales_case_note',
    'aftersales_authorization','aftersales_service_ticket'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  aftersales_case,aftersales_owner_assignment,aftersales_case_note,
  aftersales_authorization,aftersales_service_ticket TO trade_app;
-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查），
-- **不授 UPDATE**：运行角色不该能把计数器往回拨 —— 拨回去就会与已有的售后号撞主键。
-- ⚠️ 漏了这一行的话，登记端点会以 BadSqlGrammarException → 409 挂掉，
--   而错误文案说的是「数据与既有记录冲突」，与「没给 nextval 权限」毫无关系。
GRANT USAGE,SELECT ON SEQUENCE aftersales_case_no_seq, aftersales_ticket_no_seq TO trade_app;

-- 授权在前、收回在后（V144 第 9/10 节的顺序）。
-- 主表可改（状态流转、终态落时刻）。四张留痕表追加式：责任人流转、备注、授权决策、
-- 转工单都是纠纷举证的原材料，改掉或删掉一行等于毁掉证据。
REVOKE UPDATE,DELETE ON aftersales_owner_assignment FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_case_note FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_authorization FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_service_ticket FROM trade_app;

-- ⚠️ **UPDATE 必须做成列级白名单，不能只 REVOKE 列**：
--   PostgreSQL 里表级 UPDATE 蕴含该表**每一列**的 UPDATE 权限，
--   在表级 GRANT 之后写 `REVOKE UPDATE(effective_due_at)` 是**空操作**
--   —— has_column_privilege 仍返回 true，等于「以为收紧了其实没收紧」。
--   要真的收住，必须先收回表级 UPDATE，再逐列授回。
--   授权结果（V150 的合计列用了同样的写法但那里同样没收紧，这里不照抄那一处）：
--   收回表级 UPDATE（顺带把 effective_due_at 真的关掉）
REVOKE UPDATE ON aftersales_case FROM trade_app;
-- 只授回真正需要改的列：状态流转、终态落时刻、维护时刻。
-- effective_due_at **不在**这个名单里 ⇒ 运行角色写不出一个假的「不紧急」来掩盖超时。
GRANT UPDATE(status,resolved_at,updated_at) ON aftersales_case TO trade_app;

-- 本迁移**不 ALTER 任何既有表**：sales_order / sales_order_line /
-- sales_order_party_snapshot / order_address_snapshot 全部只读（9.1.1.1 车道的写入所有者）。
-- 本模块对它们的全部依赖都是 SELECT/JOIN，跨车道 DDL 回归面 = 0。
