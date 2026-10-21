-- ===========================================================================
-- 10.3.1.2 异常件（channel 服务）
-- ===========================================================================
-- 蓝图原文（10-logistics-and-freight.md）：
--   职责：识别与处理异常件：轨迹停滞、妥投失败、地址问题、清关滞留、破损丢件、买家投诉；
--         支持联系承运商、发起理赔、改址、补发。
--   输入：轨迹异常规则；被动异常标记；客服反馈。
--   输出：异常件清单 + 处置记录 + 理赔/补发单。
--   关系：上游 = 10.3.1.1；下游 = 11 售后（补发/退款）、15 客服、7 库存（补发出库）。
--         **异常件必须有人负责到底**，否则变成买家差评与平台纠纷。
--
-- ---------------------------------------------------------------------------
-- 一、与 10.3.1.1 的边界：只标记 vs 建件处置
-- ---------------------------------------------------------------------------
-- V212（10.3.1.1 轨迹查询）自己写明了边界：它只**标记**异常节点
-- （logistics_tracking_event.exception_code / logistics_tracking_subject.exception_flag），
-- 处置（联系承运商 / 理赔 / 补发 / 改址）归本模块。
--
-- 所以本模块与 10.3.1.1 的关系是**同库同服务的上游 → 下游**：
--   * 复合外键 (tenant_id, subject_id) → logistics_tracking_subject(tenant_id, id)
--   * 复合外键 (tenant_id, source_event_id) → logistics_tracking_event(tenant_id, id)
-- 两者在 V212 里都带 UNIQUE (tenant_id, id)，所以复合外键成立。
-- 🔴 复合外键带 tenant_id ⇒ **结构上不可能**把 A 租户的异常件挂到 B 租户的运单上：
--   这不是「服务层记得过滤」，是数据库层的约束。
--
-- ⚠️ 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器 / 策略**：
--   * 零 ALTER 任何既有表（logistics_tracking_subject / logistics_tracking_event 一列不动）；
--   * 零触发器挂载到别模块的表上；
--   * 跨车道 DDL 回归面 = 0。
--
-- ---------------------------------------------------------------------------
-- 二、下游全部未交付 ⇒ 处置结果落在本服务，**显式标注待通知**
-- ---------------------------------------------------------------------------
-- 蓝图写明的下游：11 售后（补发/退款）、15 客服、7 库存（补发出库）。
-- 这三个模块**当前都不存在**（11 在 trade、15 在 crm、7 在 warehouse，
-- 台账里对应的 L3 全部「未开发」）。
--
-- ⇒ 本模块**一条跨服务 SQL 都没有**，也**不假装下游已存在**：
--   * 处置结果落在本服务自己的 logistics_exception_disposition（处置记录）
--     与 logistics_exception_claim（理赔 / 补发单）；
--   * 需要下游接手的事，逐条落到 logistics_exception_downstream_task，
--     **状态恒为 PENDING_NOTIFY**，不写「已通知」「已受理」这种不存在的凭据。
--
-- 改址（10.2.1.3，trade V135）虽然已交付，但它在**另一个服务**里，
-- 而本模块的处置是「提一个改址请求」而不是「去改别人的运单文档」：
--   改址单的写入所有者永远是 10.2.1.3（它自己改了运单上的地址）。
-- 本模块只建一条 downstream_task（downstream_module='10.2.1.3'），
-- 状态 PENDING_NOTIFY —— 等 10.2.1.3 消费。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 「异常件必须有人负责到底」是**结构**不是文案
-- ---------------------------------------------------------------------------
-- 这句话的落地是三件事同时做，缺一件就退化成「异常池」那种假活：
--   1. owner_membership_id **NOT NULL** —— 建件即有责任人（默认是建件人）。
--      无人认领的异常件在库里**不存在**，不用「待分配」这种状态糊过去。
--   2. due_at NOT NULL —— 每个异常件都有责任时限（来自规则的 resolve_sla_minutes）。
--      逾期是**可统计的事实**（overdue 只在未结案时成立），不是界面上的一句话。
--   3. 状态机到终态必须有结案凭据：CHECK (status <> 'RESOLVED' OR (closed_at IS NOT NULL
--      AND owner_membership_id IS NOT NULL)) —— 结案时刻必须落，责任人必须还在。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 时长一律用**整数分钟**，不用浮点小时
-- ---------------------------------------------------------------------------
-- 异常件天然带「停滞多久 / 滞留多久」这类时长。本表的 stalled_duration_minutes 是
-- **bigint 整数分钟**，CHECK >= 0：
--   * 禁浮点：0.5 小时写成 0.5 会有二进制误差，累加几票就对不上；
--   * 禁负值：负时长是「负了也悄悄进库」，那是最难查的一种错 ——
--     负时长说明锚点时刻算错了（事件时刻晚于判定时刻），会让 SLA 判定整个反转。
-- 服务层用 java.time.Duration.toMinutes()（向零截断，符号由 Duration 保证），
-- DB 侧 CHECK 再兜一道；**不引入任何 hours 浮点列**。
--
-- ---------------------------------------------------------------------------
-- 五、金额与数量
-- ---------------------------------------------------------------------------
--   * amount / claim_amount / qty 一律 NUMERIC(19,6) + BigDecimal，**禁浮点**；
--   * 币种 varchar(3) + CHECK ~ '^[A-Z]{3}$'（空串 = 尚未确定币种，不是「0 元」）。
--   * 跨币种**绝不相加**（本模块没有汇率表，折算归 12 财务）。
-- ---------------------------------------------------------------------------
-- 六、防重复建件
-- ---------------------------------------------------------------------------
-- 部分唯一索引 logistics_exception_case_open_guard：
--   同一票（subject）+ 同一异常类型，**同时最多一件未结异常件**。
--   这让「规则扫描」天然幂等 —— 扫第二遍不会插出第二张一样的异常件。
--   已结案（RESOLVED）/ 已作废（VOID）不占位，所以同一票之后还能再开一件。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：EXC-yyyyMMdd-000001（异常件号）· CLM-yyyyMMdd-000001（理赔/补发单号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE logistics_exception_case_no_seq START 1;
CREATE SEQUENCE logistics_exception_claim_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. logistics_exception_rule · 异常识别规则（租户级）
-- ---------------------------------------------------------------------------
-- 蓝图输入句第一项「轨迹异常规则」。它是**租户可维护的表**不是代码常量：
-- 各承运商的停滞口径本来就不一样，写死等于宣称「所有承运商一个阈值」，那是假的。
CREATE TABLE logistics_exception_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_code varchar(64) NOT NULL CHECK (btrim(rule_code) <> ''),
  -- 规则命中的异常类型（产出哪种异常件）
  rule_type varchar(24) NOT NULL CHECK (rule_type IN
    ('TRACKING_STALLED','DELIVERY_FAILED','ADDRESS_ISSUE','CUSTOMS_HOLD',
     'DAMAGED_OR_LOST','BUYER_COMPLAINT')),

  -- === 命中条件（三者可组合，全空 = 「所有在途票都命中」）===
  -- ① 轨迹节点：主体当前节点等于它（清关滞留 = latest_node='CUSTOMS'）
  match_node varchar(20) NOT NULL DEFAULT '' CHECK (match_node = '' OR match_node IN
    ('NO_TRACE','PICKED_UP','IN_TRANSIT','CUSTOMS','TRANSFER','OUT_FOR_DELIVERY',
     'DELIVERED','EXCEPTION','RETURNED')),
  -- ② 承运商异常码：主体的 last_exception_code 等于它（妥投失败 / 地址问题 / 破损丢件）
  --    **精确等值**，不做模糊匹配：模糊匹配会把 "ADDRESS_INVALID" 和
  --    "ADDRESS_INVALID_2" 混成一条规则命中，那不是规则，是运气。
  match_exception_code varchar(40) NOT NULL DEFAULT '',
  -- ③ 时长阈值（分钟）：锚点（最后一次轨迹 / 发货时刻）距今 ≥ 该分钟数才算命中。
  --    停滞类 / 滞留类规则用；命中即异常不需要阈值的规则留空。
  threshold_minutes int CHECK (threshold_minutes IS NULL OR threshold_minutes > 0),

  -- 命中后产出的严重度
  severity varchar(16) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  -- 责任时限（分钟）：从 detected_at 起多久必须处置完（= due_at 的来源）
  resolve_sla_minutes int NOT NULL DEFAULT 1440 CHECK (resolve_sla_minutes > 0),

  enabled boolean NOT NULL DEFAULT true,
  note varchar(400) NOT NULL DEFAULT '',
  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, rule_code)
);

CREATE INDEX logistics_exception_rule_by_type
  ON logistics_exception_rule(tenant_id, rule_type, enabled);

-- ---------------------------------------------------------------------------
-- 2. logistics_exception_case · 异常件（清单主表）
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_exception_case (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- EXC-yyyyMMdd-000001
  case_no varchar(32) NOT NULL DEFAULT '',

  -- === 来源是**服务端派生的事实**，不是调用方可以自己填的字段 ===
  -- TRACKING_FLAG      被动异常标记：来自 10.3.1.1 的异常节点
  -- RULE_SCAN          轨迹异常规则命中
  -- CUSTOMER_FEEDBACK  客服反馈
  -- MANUAL             人工登记
  source varchar(24) NOT NULL CHECK (source IN
    ('TRACKING_FLAG','RULE_SCAN','CUSTOMER_FEEDBACK','MANUAL')),

  exception_type varchar(24) NOT NULL CHECK (exception_type IN
    ('TRACKING_STALLED','DELIVERY_FAILED','ADDRESS_ISSUE','CUSTOMS_HOLD',
     'DAMAGED_OR_LOST','BUYER_COMPLAINT')),
  severity varchar(16) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),

  -- === 责任状态机 ===
  -- OPEN 待处理 · INVESTIGATING 处置中 · ACTIONED 已出处置动作 ·
  -- RESOLVED 已结案（终态）· VOID 已作废（终态）
  status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN
    ('OPEN','INVESTIGATING','ACTIONED','RESOLVED','VOID')),

  -- === 上游 10.3.1.1（同库复合外键 ⇒ 不可能跨租户挂载）===
  subject_id uuid,
  FOREIGN KEY (tenant_id, subject_id)
    REFERENCES logistics_tracking_subject(tenant_id, id) ON DELETE RESTRICT,
  -- 触发本件的那个异常节点（被动异常标记时给出）
  source_event_id uuid,
  FOREIGN KEY (tenant_id, source_event_id)
    REFERENCES logistics_tracking_event(tenant_id, id) ON DELETE RESTRICT,

  -- === 检索快照（真值仍在上游；这里只是把运单号 / 承运商 / 订单号抄一份便于筛选）===
  waybill_no varchar(64) NOT NULL DEFAULT '',
  carrier_code varchar(64) NOT NULL DEFAULT '',
  sales_order_no varchar(64) NOT NULL DEFAULT '',

  -- 🔴 时长：整数分钟，禁浮点、禁负值（第四节）
  stalled_duration_minutes bigint NOT NULL DEFAULT 0 CHECK (stalled_duration_minutes >= 0),

  -- 🔴 责任人与责任时限（第三节：NOT NULL，不允许「无人认领」）
  owner_membership_id uuid NOT NULL,
  detected_at timestamptz NOT NULL,
  due_at timestamptz NOT NULL,

  -- 客服反馈原文（source='CUSTOMER_FEEDBACK' 时必填，服务层校验）
  customer_feedback varchar(1000) NOT NULL DEFAULT '',

  -- 结案凭据
  closed_at timestamptz,
  close_reason varchar(400) NOT NULL DEFAULT '',

  -- 🔴 **本表刻意不存金额、也不存数量**。
  --    钱只住在 logistics_exception_claim（理赔 / 补发单）上，且按币种分行统计：
  --    在异常件上再抄一份金额就会出现两个真值（单据改了、件上的没改），
  --    而「跨币种绝不相加」这件事一旦在异常件上被破坏就再也说不清了。
  --    数量同理：受影响件数是上游轨迹主体的 parcel_count（只读快照），
  --    补发数量是补发单的 qty —— 两个不同的量，不该挤在一列里互相覆盖。

  note varchar(1000) NOT NULL DEFAULT '',
  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1,

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, case_no),

  -- 🔴 结案必须有凭据：时刻 + 责任人（第三节）
  CHECK (status <> 'RESOLVED' OR (closed_at IS NOT NULL AND owner_membership_id IS NOT NULL)),
  -- 作废同理
  CHECK (status <> 'VOID' OR closed_at IS NOT NULL),
  -- 结案时刻不得早于发现时刻
  CHECK (closed_at IS NULL OR closed_at >= detected_at),
  -- 责任时限不得早于发现时刻
  CHECK (due_at >= detected_at)
);

-- 清单主筛选：类型 + 状态（现场最常用的两刀）
CREATE INDEX logistics_exception_case_by_type
  ON logistics_exception_case(tenant_id, exception_type, detected_at DESC);

CREATE INDEX logistics_exception_case_by_status
  ON logistics_exception_case(tenant_id, status, detected_at DESC);

-- 「我负责的」筛选（异常件必须有人负责到底 ⇒ 责任人是主检索维度）
CREATE INDEX logistics_exception_case_by_owner
  ON logistics_exception_case(tenant_id, owner_membership_id, detected_at DESC);

-- 承运商筛选
CREATE INDEX logistics_exception_case_by_carrier
  ON logistics_exception_case(tenant_id, carrier_code, detected_at DESC);

-- 运单号检索（客服按单号找异常件）
CREATE INDEX logistics_exception_case_by_waybill
  ON logistics_exception_case(tenant_id, waybill_no);

-- 🔴 防重复建件：同一票 + 同一类型，同时最多一件未结异常件。
--    ⇒ 规则扫描天然幂等（第二遍扫不出第二张），已结案 / 已作废不占位。
CREATE UNIQUE INDEX logistics_exception_case_open_guard
  ON logistics_exception_case(tenant_id, subject_id, exception_type)
  WHERE status IN ('OPEN','INVESTIGATING','ACTIONED') AND subject_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. logistics_exception_disposition · 处置记录（异常件的处置留痕）
-- ---------------------------------------------------------------------------
-- 蓝图输出第二项。**append-only 语义**：处置记录是「当时做了什么」的凭据，
-- 事后改写等于伪造处置史。要更正就追加新的一条。
CREATE TABLE logistics_exception_disposition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, case_id)
    REFERENCES logistics_exception_case(tenant_id, id) ON DELETE RESTRICT,

  -- CONTACT_CARRIER 联系承运商 · CONTACT_BUYER 联系买家 · CONTACT_CS 联系客服
  -- FILE_CLAIM 发起理赔 · REQUEST_ADDRESS_CHANGE 请求改址 · REQUEST_RESHIP 请求补发
  -- ESCALATE 升级 · ASSIGN 转派责任人 · RESOLVE 结案 · VOID 作废
  action_type varchar(24) NOT NULL CHECK (action_type IN
    ('CONTACT_CARRIER','CONTACT_BUYER','CONTACT_CS','FILE_CLAIM',
     'REQUEST_ADDRESS_CHANGE','REQUEST_RESHIP','ESCALATE','ASSIGN','RESOLVE','VOID')),

  -- PENDING 待办 · IN_PROGRESS 进行中 · SUBMITTED 已提交 · CONFIRMED 已确认
  -- FAILED 失败 · CANCELLED 已取消
  status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN
    ('PENDING','IN_PROGRESS','SUBMITTED','CONFIRMED','FAILED','CANCELLED')),

  -- 转派责任人时的新责任人（action_type='ASSIGN' 时必填）
  target_owner_membership_id uuid,

  -- 本次动作涉及的金额（联系承运商谈的赔付额 / 理赔额）
  amount numeric(19,6) NOT NULL DEFAULT 0 CHECK (amount >= 0),
  currency varchar(3) NOT NULL DEFAULT ''
    CHECK (currency = '' OR currency ~ '^[A-Z]{3}$'),

  detail varchar(1000) NOT NULL DEFAULT '',
  result_note varchar(1000) NOT NULL DEFAULT '',
  operator_membership_id uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),

  -- 转派必须给出目标责任人：没有目标人的「转派」等于没转派
  CHECK (action_type <> 'ASSIGN' OR target_owner_membership_id IS NOT NULL)
);

-- 某件的处置时间线
CREATE INDEX logistics_exception_disposition_by_case
  ON logistics_exception_disposition(tenant_id, case_id, occurred_at DESC);

-- 全量处置流（跨件）
CREATE INDEX logistics_exception_disposition_by_occurred
  ON logistics_exception_disposition(tenant_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 4. logistics_exception_claim · 理赔 / 补发单
-- ---------------------------------------------------------------------------
-- 蓝图输出第三项「理赔/补发单」。
CREATE TABLE logistics_exception_claim (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, case_id)
    REFERENCES logistics_exception_case(tenant_id, id) ON DELETE RESTRICT,
  -- 触发本单的那条处置记录（可选，但给了就能追溯「谁提的」）
  disposition_id uuid,
  FOREIGN KEY (tenant_id, disposition_id)
    REFERENCES logistics_exception_disposition(tenant_id, id) ON DELETE RESTRICT,

  -- CLM-yyyyMMdd-000001
  claim_no varchar(32) NOT NULL DEFAULT '',

  -- CARRIER_CLAIM 向承运商理赔 · RESHIP 补发
  claim_type varchar(16) NOT NULL CHECK (claim_type IN ('CARRIER_CLAIM','RESHIP')),

  -- DRAFT 草稿 · SUBMITTED 已提交 · APPROVED 已受理 · REJECTED 已拒绝
  -- PAID 已赔付 · CANCELLED 已撤销
  status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN
    ('DRAFT','SUBMITTED','APPROVED','REJECTED','PAID','CANCELLED')),

  -- 🔴 金额：NUMERIC + BigDecimal，禁浮点
  amount numeric(19,6) NOT NULL DEFAULT 0 CHECK (amount >= 0),
  currency varchar(3) NOT NULL DEFAULT ''
    CHECK (currency = '' OR currency ~ '^[A-Z]{3}$'),
  -- 补发数量：NUMERIC(19,6)
  qty numeric(19,6) NOT NULL DEFAULT 0 CHECK (qty >= 0),

  -- 凭据（承运商回话 / 破损照片说明等）
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,

  submitted_at timestamptz,
  decided_at timestamptz,
  decision_note varchar(400) NOT NULL DEFAULT '',

  note varchar(1000) NOT NULL DEFAULT '',
  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1,

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, claim_no),

  -- 理赔单必须有金额与币种（0 元理赔单是假单）
  CHECK (claim_type <> 'CARRIER_CLAIM' OR (amount > 0 AND btrim(currency) <> '')),
  -- 补发单必须有数量
  CHECK (claim_type <> 'RESHIP' OR qty > 0),
  -- 已提交 / 已受理的单必须有提交时刻
  CHECK (status NOT IN ('SUBMITTED','APPROVED','REJECTED','PAID') OR submitted_at IS NOT NULL)
);

CREATE INDEX logistics_exception_claim_by_case
  ON logistics_exception_claim(tenant_id, case_id, created_at DESC);

CREATE INDEX logistics_exception_claim_by_status
  ON logistics_exception_claim(tenant_id, status, created_at DESC);

-- ---------------------------------------------------------------------------
-- 5. logistics_exception_downstream_task · 下游待通知任务
-- ---------------------------------------------------------------------------
-- 🔴 蓝图下游（11 售后 / 15 客服 / 7 库存）**当前全部未交付**。
-- 本表把「这件事要下游接手」记成一条**显式任务**，状态恒从 PENDING_NOTIFY 起步：
-- 绝不写「已通知」「已受理」这种不存在的凭据 —— 那会让客服以为买家已收到而不再跟进。
--
-- 改址（10.2.1.3）虽已交付，但在 trade 服务里，本模块**不跨服务改别人的运单文档**，
-- 只提一条 PENDING_NOTIFY 任务等它消费。
CREATE TABLE logistics_exception_downstream_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, case_id)
    REFERENCES logistics_exception_case(tenant_id, id) ON DELETE RESTRICT,
  disposition_id uuid,
  FOREIGN KEY (tenant_id, disposition_id)
    REFERENCES logistics_exception_disposition(tenant_id, id) ON DELETE RESTRICT,
  claim_id uuid,
  FOREIGN KEY (tenant_id, claim_id)
    REFERENCES logistics_exception_claim(tenant_id, id) ON DELETE RESTRICT,

  -- 7 库存（补发出库）· 11 售后（补发/退款）· 15 客服 · 10.2.1.3 改址与取消
  downstream_module varchar(16) NOT NULL CHECK (downstream_module IN
    ('7','11','15','10.2.1.3')),
  -- PENDING_NOTIFY 待通知 · NOTIFIED 已通知 · ACKNOWLEDGED 已受理 · CANCELLED 已取消
  downstream_status varchar(20) NOT NULL DEFAULT 'PENDING_NOTIFY' CHECK (downstream_status IN
    ('PENDING_NOTIFY','NOTIFIED','ACKNOWLEDGED','CANCELLED')),

  reference_no varchar(64) NOT NULL DEFAULT '',
  note varchar(400) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  notified_at timestamptz,
  acknowledged_at timestamptz,

  UNIQUE (tenant_id, id),

  -- 任务必须挂在某条处置或单据上：孤零零一条「要通知下游」没有指向
  CHECK (disposition_id IS NOT NULL OR claim_id IS NOT NULL),
  -- 已受理必须有过通知时刻
  CHECK (downstream_status <> 'ACKNOWLEDGED' OR notified_at IS NOT NULL)
);

CREATE INDEX logistics_exception_downstream_task_by_case
  ON logistics_exception_downstream_task(tenant_id, case_id, created_at DESC);

CREATE INDEX logistics_exception_downstream_task_by_module
  ON logistics_exception_downstream_task(tenant_id, downstream_module, downstream_status);

-- ---------------------------------------------------------------------------
-- 6. 触发器：状态机 + 版本号 + 结案凭据
-- ---------------------------------------------------------------------------
-- 🔴 状态机在 **DB 层**钉死：绕过服务层直接写 SQL 也跳不过去。
--    终态（RESOLVED / VOID）不许再改判；「结案 → 回到处置中」这种回退是最容易
--    掩盖问题的操作（件看起来在处理，其实已经结案没人管了）。
CREATE OR REPLACE FUNCTION guard_logistics_exception_case_transition() RETURNS trigger AS $$
DECLARE
  allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN
    allowed := true;
  ELSIF OLD.status = 'OPEN' THEN
    allowed := NEW.status IN ('INVESTIGATING','ACTIONED','RESOLVED','VOID');
  ELSIF OLD.status = 'INVESTIGATING' THEN
    allowed := NEW.status IN ('ACTIONED','RESOLVED','VOID');
  ELSIF OLD.status = 'ACTIONED' THEN
    allowed := NEW.status IN ('RESOLVED','VOID');
  END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION
      '异常件状态不可从 % 变为 %（终态不可回退）', OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  NEW.version := OLD.version + 1;
  NEW.updated_at := now();
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER logistics_exception_case_transition_trg
  BEFORE UPDATE ON logistics_exception_case
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_exception_case_transition();

-- 异常件单据同样有状态机：DRAFT → SUBMITTED → 受理/拒绝/赔付，撤销只从非终态走。
CREATE OR REPLACE FUNCTION guard_logistics_exception_claim_transition() RETURNS trigger AS $$
DECLARE
  allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN
    allowed := true;
  ELSIF OLD.status = 'DRAFT' THEN
    allowed := NEW.status IN ('SUBMITTED','CANCELLED');
  ELSIF OLD.status = 'SUBMITTED' THEN
    allowed := NEW.status IN ('APPROVED','REJECTED','PAID','CANCELLED');
  END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION
      '理赔/补发单状态不可从 % 变为 %', OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  NEW.version := OLD.version + 1;
  NEW.updated_at := now();
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER logistics_exception_claim_transition_trg
  BEFORE UPDATE ON logistics_exception_claim
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_exception_claim_transition();

-- 处置记录 append-only：处置史不可改写，要更正就追加新的一条。
CREATE OR REPLACE FUNCTION reject_logistics_exception_disposition_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION
    'logistics_exception_disposition 是处置留痕（当时做了什么），不可改写；'
    '要更正请追加一条新的处置记录' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER logistics_exception_disposition_append_only_trg
  BEFORE UPDATE OR DELETE ON logistics_exception_disposition
  FOR EACH ROW EXECUTE FUNCTION reject_logistics_exception_disposition_mutation();

-- ---------------------------------------------------------------------------
-- 7. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V212 / V214 同一套写法。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_exception_rule',
    'logistics_exception_case',
    'logistics_exception_disposition',
    'logistics_exception_claim',
    'logistics_exception_downstream_task' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 8. 账号与授权（沿用 V1/V93/V129/V212/V214 的条件建角色，保证可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  logistics_exception_rule, logistics_exception_case,
  logistics_exception_disposition, logistics_exception_claim,
  logistics_exception_downstream_task TO channel_app;

-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE
  logistics_exception_case_no_seq, logistics_exception_claim_no_seq TO channel_app;

-- 处置留痕只许增，不许改 / 删（权限层，与触发器双保险）。
REVOKE UPDATE,DELETE ON logistics_exception_disposition FROM channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V212 / V214 同口径）。
REVOKE ALL ON
  logistics_exception_rule, logistics_exception_case,
  logistics_exception_disposition, logistics_exception_claim,
  logistics_exception_downstream_task FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 9. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：异常件属「平台/承运商操作」族，与 10.1.1.1 承运商、
-- 10.1.1.3 时效与限制、10.2.2.2 面单模板、10.3.1.1 轨迹查询、10.3.2.1 交运批次、
-- 10.7.1.1 退货接收同族，因此 identity 服务零改动，跨车道回归面 = 0。
