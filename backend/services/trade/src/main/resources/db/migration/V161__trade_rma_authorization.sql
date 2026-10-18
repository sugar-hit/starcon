-- 11.2.1.1 退货授权单（卷 11 售后与退货的退货侧第一张单据）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：审批退货申请：确认可退数量（原订单行已退/已发数量约束）、退货地址、买家回寄物流、
--         目标处理方式；部分退货需说明理由。
--   输入：退货申请；原订单行可退量校验；审批决定。
--   输出：退货授权单（授权/部分授权/拒绝）+ 拒绝理由。
--   关系：上游 = 11.1.1.2（售后单详情，**已交付**）；下游 = 11.2.1.2 退货指令。
--         **授权不等于已收到**——授权后货可能在路上或根本没寄回，需要超时未寄自动关闭。
--
-- 归属：**trade 服务**（同 L3 11.2.1 锚点）。
--
-- 本迁移**只新建三张表与触发器、零 ALTER 零 DROP 既有表**：
--   * aftersales_case 簇（V156）与 aftersales_case_line（V157，11.1.1.2 车道的写入所有者）
--     只**读**；跨表一致性由触发器在**写入时刻**真查一次钉死（刻意不建 FK ——
--     加 FK 会让本迁移对别的车道的表产生 DDL 依赖，而同批另有五条车道也在往 trade 落迁移）；
--   * sales_order_line（V99，9 章车道）与 order_fulfillment_line_progress（V119，
--     9.1.2.2 车道）只**读**：前者给订购数量，后者给**已发数量**；
--   * aftersales_authorization（V156）**完全不碰**：它是 11.1.1.1 批量授权的写入所有者，
--     本模块的退货授权单是**另一份单据**（行级数量 + 退货地址 + 回寄物流 + 时限），
--     塞进那张表会把「同意退 3 件中的 2 件」压成一个金额，谁都答不出退的是哪几件。
--
-- ===========================================================================
-- 一、职责句里五个区块各自落在哪 / 为什么这么落
-- ===========================================================================
-- 「确认可退数量（原订单行已退/已发数量约束）」
--      → **本迁移的核心**，落成三层：
--         ① rma_authorization_line 上的四个**闸门基数快照**
--            （ordered / shipped / prior_authorized / requested）；
--         ② 表级 CHECK：本行授权量 ≤ min(订购, 已发) − 其他在途授权累计；
--         ③ 触发器 guard_rma_authorization_line：**真查一次** sales_order_line 与
--            order_fulfillment_line_progress 再算一遍，绕过服务层直连 SQL 也绕不过。
-- 「退货地址」   → rma_authorization 的 return_address_* 列（见第三节）。
-- 「买家回寄物流」→ return_carrier / return_tracking_no / return_shipped_at（见第四节）。
-- 「目标处理方式」→ target_disposition（见第二节，**刻意**不等于 11.2.3.2 的最终处置决定）。
-- 「部分退货需说明理由」→ CHECK 钉死：PARTIALLY_AUTHORIZED 与 REJECTED 必须带 decision_reason。
--
-- ===========================================================================
-- 二、目标处理方式：**意图**，不是处置结论
-- ===========================================================================
-- ⚠️ 六类里 SUPPLIER_CLAIM / SCRAP / RESTOCK_FOR_RESALE 的**实际执行**分别属 11.2.3.3 索赔、
--   11.2.3.2 报废、11.2.3.2 转可售，三个都**未交付**。
--   本列记的是**审批当时我们打算怎么处置**，不是已经处置完；
--   把它命名成 disposition 并允许后续直接改写，会让「已报废」和「打算报废」在库里有同一个形状 ——
--   而退货实物还没到手（授权 ≠ 收到，见第六节），此刻任何「已处置」都是假的。
--   ⇒ 列名 target_disposition，能力声明里显式报处置结论未交付。
--
-- ===========================================================================
-- 三、退货地址：跨服务读不到 3.6.3，只能租户维护 + 来源枚举
-- ===========================================================================
-- 蓝图输入句写「退货地址（3.6.3 或平台 RMA 地址）」。**实查集成头 f760ba37**：
-- 3.6.3.1 地址与联系人确已交付，但迁移在 **catalog 服务**（V31__party_addresses_contacts.sql），
-- 是**另一个数据库** ⇒ 按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / 视图 / FK**，本服务读不到它。
-- ⇒ 照抄 V150 signal_source / V156 owner_source / V157 message_source 的同一条解法：
--   return_address_source ∈ ( PLATFORM_RMA / MODULE_3_6_3_ADDRESS_BOOK / TENANT_MAINTAINED )，
--   写成 MODULE_* 时必须给出 source_reference（3.6.3 交付后由同步器按**同一形状**写入，
--   **表结构与查询都不用改**）。
-- ⚠️ PLATFORM_RMA 不代表「这个地址我们真的发过」：卷 2 平台消息与平台地址在 channel 独立库，
--   本服务当前无任何平台地址表，平台 RMA 地址当前同样只能由租户登记 —— 与 V157 的 message_source
--   同一条纪律（渠道说「在哪说的」，来源说「谁写进来的」）。
CREATE TABLE rma_authorization (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 授权单号：租户内唯一（序列发号器，末尾 GRANT USAGE）。
  rma_no varchar(32) NOT NULL,

  -- 退货申请 = 一张售后单（V156）。**刻意不加外键**，见文件头。
  -- ⚠️ 只有 case_type='RETURN_REFUND' 才可能有退货授权单（服务层 + 触发器双拦）：
  --   REPAIR 的货根本没出门、REFUND_ONLY 没有货要寄回、EXCHANGE 的换货授权属 11.3.2.1。
  case_id uuid NOT NULL,

  -- ---- 状态机：一个状态列到底，decision 由它派生（不设第二份真相）----
  -- DRAFT                  草稿：行与退货地址已登记，尚未审批（授权量恒为 0）
  -- AUTHORIZED             全部授权：授权量合计 = 申请量合计
  -- PARTIALLY_AUTHORIZED   部分授权：授权量合计 < 申请量合计，**必须说明理由**
  -- REJECTED               拒绝：**必须说明理由**
  -- EXPIRED                超时未寄自动关闭（蓝图加粗句的直接落点）
  -- CANCELLED              人工撤销（货还没回来，操作员发现批错了）
  rma_status varchar(24) NOT NULL CHECK(rma_status IN (
      'DRAFT','AUTHORIZED','PARTIALLY_AUTHORIZED','REJECTED','EXPIRED','CANCELLED'
  )),

  -- 目标处理方式（**意图**，见第二节）：授权当时打算怎么处置退回的货。
  target_disposition varchar(32) NOT NULL CHECK(target_disposition IN (
      'REFUND_TO_BUYER','RESTOCK_FOR_RESALE','SUPPLIER_CLAIM','REPAIR','SCRAP','INSPECTION_HOLD'
  )),

  -- ---- 退货地址（第三节）----
  return_address_source varchar(32) NOT NULL CHECK(return_address_source IN (
      'PLATFORM_RMA','MODULE_3_6_3_ADDRESS_BOOK','TENANT_MAINTAINED'
  )),
  -- 写成 MODULE_* 时必须指明是哪份来源的哪一段，否则「这行是同步器写的」只是口头声明。
  source_reference varchar(128) NOT NULL DEFAULT '',
  return_recipient_name varchar(200) NOT NULL DEFAULT '',
  return_recipient_phone varchar(64) NOT NULL DEFAULT '',
  return_contact_email varchar(254) NOT NULL DEFAULT '',
  return_country_code char(2) NOT NULL DEFAULT '' CHECK(return_country_code ~ '^([A-Z]{2})?$'),
  return_province varchar(120) NOT NULL DEFAULT '',
  return_city varchar(120) NOT NULL DEFAULT '',
  return_district varchar(120) NOT NULL DEFAULT '',
  return_postcode varchar(32) NOT NULL DEFAULT '',
  return_address_line1 varchar(300) NOT NULL DEFAULT '',
  return_address_line2 varchar(300) NOT NULL DEFAULT '',

  -- ---- 买家回寄物流（第四节）----
  return_logistics_source varchar(32) NOT NULL CHECK(return_logistics_source IN (
      'TENANT_MAINTAINED','MODULE_11_2_1_2_RMA_INSTRUCTION'
  )),
  return_carrier varchar(64) NOT NULL DEFAULT '',
  return_tracking_no varchar(128) NOT NULL DEFAULT '',
  return_shipped_at timestamptz,
  -- 谁在什么时候登记的这笔回寄：蓝图说「授权后货可能在路上**或根本没寄回**」，
  -- 「没寄回」是超时的**默认情形**，所以登记动作必须留人（超时申诉时要问「谁说寄了」）。
  return_logistics_recorded_by uuid,
  return_logistics_recorded_at timestamptz,

  -- ---- 时限与终态 ----
  -- 买家须寄回时限：授权 / 部分授权时**必填**（蓝图加粗句的计时基准）。
  return_by_at timestamptz,
  -- 超时自动关闭时刻：非空即本单是超时关掉的。
  expired_at timestamptz,

  -- ---- 审批决定 ----
  -- 部分授权与拒绝的理由（职责句：「部分退货需说明理由」+ 输出句「+ 拒绝理由」）。
  decision_reason text NOT NULL DEFAULT '' CHECK(length(decision_reason) <= 1000),
  decided_by uuid,
  decided_at timestamptz,
  cancelled_by uuid,
  cancelled_at timestamptz,

  -- 乐观锁：审批 / 改地址 / 撤销都要带 expected_version。
  -- 没有它，两个人同时点「授权」会各写一次决定，而表里只留得下后一个 ——
  -- 少的那一次授权要么被静默覆盖，要么让已退数量对不上。
  version bigint NOT NULL DEFAULT 0,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rma_no),

  -- 审批过的三种状态必须有审批人与审批时刻。
  CHECK(rma_status NOT IN ('AUTHORIZED','PARTIALLY_AUTHORIZED','REJECTED')
        OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),

  -- 授权 / 部分授权必须有寄回时限：没有时限的授权**永远不会超时关闭**，
  -- 而蓝图加粗句要的正是「没寄回就自动关掉」。缺时限等于把这条硬规则关掉了。
  CHECK(rma_status NOT IN ('AUTHORIZED','PARTIALLY_AUTHORIZED') OR return_by_at IS NOT NULL),

  -- **部分授权必须说明理由**（职责句原文）：部分退是买家只同意退一部分，
  -- 不写理由的话事后无法解释「为什么这 3 件里只退了 2 件」。
  CHECK(rma_status <> 'PARTIALLY_AUTHORIZED' OR btrim(decision_reason) <> ''),

  -- **拒绝必须说明理由**（输出句原文「+ 拒绝理由」）。
  CHECK(rma_status <> 'REJECTED' OR btrim(decision_reason) <> ''),

  -- EXPIRED 必须既有过期时刻、又真实有过期时限（否则「超时」无从谈起）。
  CHECK(rma_status <> 'EXPIRED' OR (expired_at IS NOT NULL AND return_by_at IS NOT NULL)),

  -- 撤销时刻与状态严格等价，理由同上（防「状态已撤但时刻没落」导致倒计时不停）。
  CHECK((rma_status = 'CANCELLED') = (cancelled_at IS NOT NULL)),

  -- 回寄物流三件套同进同出：给了一个承运商却没单号（或者反过来）在实收核对时无法定位包裹。
  CHECK((btrim(return_carrier) = '' AND btrim(return_tracking_no) = '' AND return_shipped_at IS NULL)
        OR (btrim(return_carrier) <> '' AND btrim(return_tracking_no) <> '' AND return_shipped_at IS NOT NULL)),

  -- 登记了回寄就必须留人（见 return_logistics_recorded_by 的注释）。
  CHECK(return_logistics_recorded_at IS NULL OR return_logistics_recorded_by IS NOT NULL),

  -- 写成 MODULE_* 来源时必须指明来源段（V157 同一条纪律）。
  CHECK(return_address_source <> 'MODULE_3_6_3_ADDRESS_BOOK' OR btrim(source_reference) <> ''),

  CHECK(updated_at >= created_at)
);

-- ===========================================================================
-- 四、买家回寄物流：**本模块只登记**，轨迹跟踪归 11.2.1.2
-- ===========================================================================
-- ⚠️ 三件套（承运商 / 运单号 / 寄出时刻）刻意**挂在单头而不是行上**：
--   买家寄回的是**一个包裹**（可能含多个 SKU 的多行），按行记会让「这个包裹里有几件」
--   变成多行各自一份、互相矛盾的运单号。11.2.2.1 实收按包裹拆到行是那一步的事。
-- ⚠️ **不建包裹 / 轨迹表**：11.2.1.2 退货指令下发（标签文件 + 回寄物流轨迹跟踪）**未交付**，
--   本服务自造一张 rma_parcel 表会被当成已对接，而轨迹是 10 物流的真实事。
--   本列只承「买家报了个运单号」，来源可枚举、可换成 MODULE_11_2_1_2_RMA_INSTRUCTION。
--
-- ===========================================================================
-- 五、行：授权单行 + 四个闸门基数快照
-- ===========================================================================
-- ⚠️ **只存 case_line_id，不复制 sku_code / sku_name / 单价**：那些真值在
--   sales_order_line（V99 的下单快照）与 aftersales_case_line（V157），
--   复制一份就会漂移，而「授权单上的 SKU 和订单上的不一样」在纠纷举证里是直接被质疑的。
--
-- ⚠️ **四个基数快照是闸门基数，不是订单字段的副本**（照抄 V119 order_fulfillment_line_progress
--   把 ordered_qty 抄下来的同一纪律）：它们记的是**审批那一刻**看到的可退量依据，
--   供事后审计「当时凭什么批了这个数」。订单真值仍然实时读 sales_order_line。
CREATE TABLE rma_authorization_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rma_id uuid NOT NULL,

  -- 指向 aftersales_case_line（V157，**刻意不加外键**，见文件头）。
  case_line_id uuid NOT NULL,

  -- 申请量快照：建单那一刻从 aftersales_case_line 抄的（审批依据，不与售后单漂移）。
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty > 0),

  -- 本行授权数量：草稿恒为 0，审批时写入。
  authorized_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(authorized_qty >= 0),

  -- ---- 四个闸门基数快照（审批那一刻的真值，见上文）----
  -- 订购数量（sales_order_line.quantity，V99）
  ordered_qty_snapshot numeric(19,6) CHECK(ordered_qty_snapshot IS NULL OR ordered_qty_snapshot > 0),
  -- 已发数量（order_fulfillment_line_progress.shipped_qty，V119）
  shipped_qty_snapshot numeric(19,6) CHECK(shipped_qty_snapshot IS NULL OR shipped_qty_snapshot >= 0),
  -- 同一订单行在**其他在途授权单**上已被授权的累计（建本行时算，见第六节）
  prior_authorized_qty_snapshot numeric(19,6) NOT NULL DEFAULT 0 CHECK(prior_authorized_qty_snapshot >= 0),

  -- 对这一行的说明：部分授权时逐行写「这件为什么不批」（只退 2 件的常见原因在这里）。
  line_note text NOT NULL DEFAULT '' CHECK(length(line_note) <= 1000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张授权单里同一申请行只登记一次：重复登记会让「这一行批了几件」有两个答案。
  UNIQUE(tenant_id,rma_id,case_line_id),

  -- 授权量不得超过申请量（职责句：「确认可退数量」的第一层）。
  CHECK(authorized_qty <= requested_qty),

  -- **可退量上限钉在 CHECK 上**（职责句点名的「已发数量约束」）：
  --   授权量 ≤ min(订购量, 已发量) − 其他在途授权累计。
  --   没发出去的货不能退（已发量 = 0 时上限为 0），已被别张单批过的量不能再批一次。
  --   两个基数都给出才判（草稿行授权量为 0，两个基数为 NULL）。
  CHECK(authorized_qty = 0
        OR (ordered_qty_snapshot IS NOT NULL
            AND shipped_qty_snapshot IS NOT NULL
            AND authorized_qty
                <= least(ordered_qty_snapshot, shipped_qty_snapshot)
                   - prior_authorized_qty_snapshot)),

  -- 快照之间不许自相矛盾：已发量不得超过订购量（在真表里有 CHECK，这里在快照上也钉一份，
  -- 否则一条手工 INSERT 能带着「已发 9 / 订购 3」的快照把 9 件批出去）。
  CHECK(shipped_qty_snapshot IS NULL OR ordered_qty_snapshot IS NULL
        OR shipped_qty_snapshot <= ordered_qty_snapshot),

  CHECK(updated_at >= created_at)
);

-- ===========================================================================
-- 六、护栏触发器：把「可退量」这条硬规则钉在 DB 层（与 V157 / V119 同一套写法）
-- ===========================================================================
-- ① 行必须挂在**真实的、属于本单所关联售后单的商品行**上（替代刻意不建的外键）；
-- ② 授权量三件事一起真查一次：申请量不超过售后行、已发量取自履约进度、
--    累计在途授权量由**其他**授权单现算（表级 CHECK 只看快照，绕不过实时口径）。

CREATE FUNCTION guard_rma_authorization_line() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(24);
  t_case_type varchar(24);
  t_case_id uuid;
  t_case_line_case uuid;
  t_case_line_qty numeric(19,6);
  t_line_order_id uuid;
  t_order_qty numeric(19,6);
  t_shipped_qty numeric(19,6);
  t_inflight numeric(19,6);
  t_cap numeric(19,6);
BEGIN
  -- 单头状态与售后单类型一次查齐：rma_authorization 上**没有** case_type（它在 aftersales_case 上），
  -- 且本表的状态列叫 rma_status 不叫 status —— 这两个名字写错会直接让触发器报
  -- 「column does not exist」，而那是在**第一条写入**时才炸，看起来像迁移没跑对。
  SELECT r.rma_status, c.case_type, c.id INTO t_status, t_case_type, t_case_id
    FROM rma_authorization r
    join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id
   WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.rma_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '退货授权单不存在或不属于当前租户：rma_id=%', NEW.rma_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 结案后不能改行：拒绝 / 撤销 / 超时关闭的单，它的行是**证据**不是草稿。
  IF t_status IN ('REJECTED','CANCELLED','EXPIRED') THEN
    RAISE EXCEPTION '退货授权单已结案（status=%），不能再改动授权行', t_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 只有退货退款类才有退货这回事（见文件头）。
  IF t_case_type <> 'RETURN_REFUND' THEN
    RAISE EXCEPTION '只有 RETURN_REFUND 类的售后单才能发起退货授权，当前类型 %', t_case_type
      USING ERRCODE = 'check_violation';
  END IF;

  -- 申请行必须真实存在，且属于**本单所关联的那张售后单**。
  -- 少了这一段，「A 单的授权行指向 B 单的商品行」会完全合法。
  SELECT cl.case_id, cl.quantity, l.id, l.quantity
    INTO t_case_line_case, t_case_line_qty, t_line_order_id, t_order_qty
    FROM aftersales_case_line cl
    join sales_order_line l
      on l.tenant_id = cl.tenant_id and l.id = cl.order_line_id
   WHERE cl.tenant_id = NEW.tenant_id and cl.id = NEW.case_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '售后商品行不存在或不属于当前租户：case_line_id=%', NEW.case_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_case_line_case <> t_case_id THEN
    RAISE EXCEPTION '售后商品行不属于本授权单所关联的售后单：case_line_id=%', NEW.case_line_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.requested_qty > t_case_line_qty THEN
    RAISE EXCEPTION '申请量 % 超过售后商品行数量 %', NEW.requested_qty, t_case_line_qty
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.authorized_qty = 0 THEN
    RETURN NEW;
  END IF;

  -- ---- 以下只在真的授权了数量时才真查两处（草稿行不必付这个代价）----

  -- 已发数量取自履约进度投影（V119）。**没有投影行就是已发 0**：
  --   没被记录发出过的货不能退 —— 这是宁可拦住也不能放过的方向
  --   （放行的代价是把从没到过买家手上的货按已收货处理）。
  SELECT p.shipped_qty INTO t_shipped_qty
    FROM order_fulfillment_line_progress p
   WHERE p.tenant_id = NEW.tenant_id and p.order_line_id = t_line_order_id;

  IF NOT FOUND THEN
    t_shipped_qty := 0;
  END IF;

  -- 其他**在途**授权单（本单之外）在这条订单行上已授权的累计。
  -- 只有 AUTHORIZED / PARTIALLY_AUTHORIZED 占用可退量：草稿还没批，
  -- 拒绝 / 撤销 / 超时关闭的单不会有货回来（过期正是「没寄回」），量要还给订单行。
  SELECT coalesce(sum(rl.authorized_qty), 0) INTO t_inflight
    FROM rma_authorization_line rl
    join aftersales_case_line cl
      on cl.tenant_id = rl.tenant_id and cl.id = rl.case_line_id
    join rma_authorization h on h.tenant_id = rl.tenant_id and h.id = rl.rma_id
   WHERE rl.tenant_id = NEW.tenant_id
     and rl.rma_id <> NEW.rma_id
     and cl.order_line_id = t_line_order_id
     and h.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED');

  t_cap := least(t_order_qty, coalesce(t_shipped_qty, 0)) - t_inflight;

  IF NEW.authorized_qty > t_cap THEN
    RAISE EXCEPTION '授权数量 % 超过可退上限 %（订购 %、已发 %、其他在途授权 %）',
      NEW.authorized_qty, t_cap, t_order_qty, coalesce(t_shipped_qty, 0), t_inflight
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_rma_authorization_line
  BEFORE INSERT OR UPDATE OF requested_qty, authorized_qty ON rma_authorization_line
  FOR EACH ROW EXECUTE FUNCTION guard_rma_authorization_line();

-- ===========================================================================
-- 七、审批留痕：追加式（append-only）
-- ===========================================================================
-- 退货授权的每一次决定都要能回放：谁批的、批了多少、理由是什么、后来有没有撤销。
-- 改掉或删掉一行等于抹掉这段证据，所以运行角色连 UPDATE/DELETE 权限都没有（第九节）。
-- ⚠️ 本表**先于**写它的触发器创建：迁移文件从上往下执行，顺序颠倒虽然也能跑通
--   （PL/pgSQL 函数体在 CREATE 时不校验表是否存在），但读者会以为触发器悬空。
CREATE TABLE rma_decision_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rma_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'CREATED','AUTHORIZED','PARTIALLY_AUTHORIZED','REJECTED','EXPIRED','CANCELLED','UPDATED'
  )),
  -- 变更前后状态：建单那一条 from_status 为 NULL（此前不存在）。
  from_status varchar(24),
  to_status varchar(24) NOT NULL,

  -- 理由：部分授权 / 拒绝必填（由下面的触发器从 decision_reason 带下来）。
  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

-- ⚠️ **状态流转的单点真相**：每次状态真的变了，由触发器把「谁在什么时候把单子推到哪一步、
--   带着什么理由」追加进 rma_decision_event。
--   审批这件事本身就是纠纷举证的一部分（平台会问「你们为什么只退 2 件」），
--   而只留头表上的一个当前状态，事后没人能说清中间改过几次。
--   **只有真的变了才追加**（UPDATE 时 OLD.rma_status = NEW.rma_status 直接返回），
--   否则每次改地址都会在留痕里留下一条假状态变更。
CREATE FUNCTION rma_append_decision_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.rma_status = OLD.rma_status THEN
    RETURN NEW;
  END IF;

  INSERT INTO rma_decision_event(
    id, tenant_id, rma_id, event_type, from_status, to_status,
    reason_note, occurred_by, occurred_at)
  VALUES (
    gen_random_uuid(),
    NEW.tenant_id,
    NEW.id,
    CASE
      WHEN TG_OP = 'INSERT' THEN 'CREATED'
      WHEN NEW.rma_status = 'AUTHORIZED'          THEN 'AUTHORIZED'
      WHEN NEW.rma_status = 'PARTIALLY_AUTHORIZED' THEN 'PARTIALLY_AUTHORIZED'
      WHEN NEW.rma_status = 'REJECTED'            THEN 'REJECTED'
      WHEN NEW.rma_status = 'EXPIRED'             THEN 'EXPIRED'
      WHEN NEW.rma_status = 'CANCELLED'           THEN 'CANCELLED'
      ELSE 'UPDATED'
    END,
    CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.rma_status END,
    NEW.rma_status,
    CASE
      WHEN TG_OP = 'INSERT' THEN '登记退货授权单草稿'
      WHEN NEW.rma_status = 'AUTHORIZED'          THEN coalesce(nullif(btrim(NEW.decision_reason), ''), '全部授权')
      WHEN NEW.rma_status = 'PARTIALLY_AUTHORIZED' THEN NEW.decision_reason
      WHEN NEW.rma_status = 'REJECTED'            THEN NEW.decision_reason
      WHEN NEW.rma_status = 'EXPIRED'             THEN '超时未寄回，自动关闭'
      WHEN NEW.rma_status = 'CANCELLED'           THEN coalesce(nullif(btrim(NEW.decision_reason), ''), '人工撤销')
      ELSE '草稿要素变更'
    END,
    coalesce(NEW.decided_by, NEW.cancelled_by, NEW.created_by),
    -- ⚠️ **每类事件用它自己那一刻**，不是 coalesce 出一列通用值：
    --   早期版本写 coalesce(decided_at, cancelled_at, created_at, now())，
    --   于是**超时关闭事件的时刻被记成了当初审批的那一刻** ——
    --   两条事件 occurred_at 完全相同，排序退化成按随机 UUID 排，
    --   「建单 → 审批 → 超时关闭」这条时间线有时会读成「建单 → 超时关闭 → 审批」，
    --   而且是**时好时坏**的那种（跑一次绿、跑一次红）。
    CASE
      WHEN TG_OP = 'INSERT'                      THEN NEW.created_at
      WHEN NEW.rma_status = 'AUTHORIZED'          THEN NEW.decided_at
      WHEN NEW.rma_status = 'PARTIALLY_AUTHORIZED' THEN NEW.decided_at
      WHEN NEW.rma_status = 'REJECTED'            THEN NEW.decided_at
      WHEN NEW.rma_status = 'EXPIRED'             THEN NEW.expired_at
      WHEN NEW.rma_status = 'CANCELLED'           THEN NEW.cancelled_at
      ELSE NEW.updated_at
    END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_rma_decision_event
  AFTER INSERT OR UPDATE OF rma_status ON rma_authorization
  FOR EACH ROW EXECUTE FUNCTION rma_append_decision_event();

-- ===========================================================================
-- 八、索引与发号器
-- ===========================================================================
-- ① 详情页与审批页的主查询：按单取授权行。
CREATE INDEX rma_authorization_line_by_case_line
  ON rma_authorization_line(tenant_id, case_line_id);
-- ② 超时扫描（expire-overdue）：只看在途且带时限的行，EXPIRED 关掉后不再命中。
CREATE INDEX rma_authorization_inflight_due
  ON rma_authorization(tenant_id, return_by_at)
  WHERE rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED');
-- ③ 「这张售后单批过几张退货单」：详情页与 11.2.1.2 下游都要按 case_id 反查。
CREATE INDEX rma_authorization_by_case
  ON rma_authorization(tenant_id, case_id);
-- ④ 留痕回放：按发生时刻读一单的完整审批轨迹。
CREATE INDEX rma_decision_event_by_rma
  ON rma_decision_event(tenant_id, rma_id, occurred_at);

CREATE SEQUENCE rma_no_seq;
-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查），
-- 错误文案说的是「数据与既有记录冲突」，与「没给 nextval 权限」毫无关系（V156 同一条）。
GRANT USAGE,SELECT ON SEQUENCE rma_no_seq TO trade_app;

-- ===========================================================================
-- 九、多租户：RLS + 授权（与 V157 第七节同一套写法）
-- ===========================================================================
-- 三张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：授权单带着**退货地址**（收货人姓名、电话）与**买家回寄运单号**，
--   且审批入口按 id 直达 —— 漏掉租户谓词就是跨租户泄露。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'rma_authorization','rma_authorization_line','rma_decision_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  rma_authorization, rma_authorization_line, rma_decision_event TO trade_app;

-- 授权在前、收回在后（与 V156 / V159 同序）。
-- 留痕表**追加式**：审批轨迹是纠纷举证的原材料，UPDATE/DELETE 一律收回。
REVOKE UPDATE,DELETE ON rma_decision_event FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：aftersales_case 簇（V156/V157）、
-- sales_order_line（V99）与 order_fulfillment_line_progress（V119）全部只读。
-- 本模块对它们的全部依赖都是 SELECT / JOIN 与「写入时刻查一次」，跨车道 DDL 回归面 = 0。
