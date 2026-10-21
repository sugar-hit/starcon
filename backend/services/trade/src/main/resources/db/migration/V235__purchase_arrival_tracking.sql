-- 6.3.2.2 到货与逾期（卷 06 采购与供应 / L3 6.3.2 采购跟踪的第二格）。
--
-- 蓝图 06-purchase-and-supply.md 原文：
--   职责：跟踪采购单的到货状态：已发货（在途）、部分到货、全部到货、逾期未到、取消。
--         逾期自动预警并给出处置建议（催货/改供应商/调库存/延期售卖）。
--   输入：8 仓储的收货事件；跟单记录；物流在途状态（10）。
--   输出：到货状态 + 逾期预警 + 处置建议。
--   关系：上游 = 8 仓储作业、10 物流；下游 = 6.2.1.1 断货风险、1 工作台待办。
--   **部分到货必须立即更新已收数量并触发应付部分确认**，否则采购与财务三方数据对不上。
--
-- 归属：**trade 服务**（与 6.3.1.x 采购单、6.3.2.1 跟单同属卷 06 采购执行；
-- 蓝图 README 系统地图 06 行写死「后端主责服务：trade + catalog（party）」，本仓不存在 purchase 服务）。
--
-- 本迁移**只新建五张表与触发器、零 ALTER 零 DROP 既有表** ⇒ 跨车道 DDL 回归面 = 0
-- （同批另有三条车道也在往 trade 落迁移）。
--
-- ===========================================================================
-- 一、这个模块拥有什么、不拥有什么
-- ===========================================================================
--
-- ⚠️ **本模块拥有「到货跟踪」这份事实**。蓝图输入句里的三个上游本车道逐一查证过：
--
--   * **8 仓储的收货事件（8.2.1.1 收货单 / 8.2.1.2 收货差异）** —— 已交付，
--     但在 **warehouse 服务的独立库**里 ⇒ 本表只存**逻辑引用 + 快照**
--     （warehouse_receipt_id / warehouse_receipt_code / 实收量快照），
--     写入前经**授权只读端口**核对，**不建跨库 FK、不建跨库视图、不写跨库 SQL**。
--   * **物流在途状态（10.4.1.2 在途跟踪）** —— 已交付，同样在 **warehouse 服务的独立库**
--     （头程发运单 10.4.1.1 与在途跟踪 10.4.1.2 都归 warehouse）⇒ 同上：
--     授权端口读 + 本地快照（in_transit_* 七列）。
--   * **跟单记录（6.3.2.1）** —— **未交付** ⇒ 用**逻辑引用**表示
--     （promise_source 跟单号 / source_doc_no 来源单据号），**不建指向不存在的表的 FK**。
--   * **采购单本身（6.3.1.x）** —— **未交付** ⇒ 用**逻辑引用**定位
--     （purchase_order_no 采购单号 + purchase_order_ref_id + source_doc_no + 供应商标识），
--     **不建指向不存在的采购单表的 FK**。
--
-- ⚠️ **12 财务（finance 服务）当前不存在**（backend/services/ 下只有
--   identity / catalog / channel / warehouse / trade / crm）。
--   所以蓝图那句「触发应付部分确认」在本模块落成**一份 trade 侧自有事实**
--   （purchase_arrival_payable_confirmation），**不是**一次跨服务调用，
--   更**不是**一个「已触发财务确认」的假回执：它的状态恒为 PENDING_FINANCE_CONFIRM、
--   生效金额恒为 0，直到财务（未来卷 12）经授权 API / event 回来确认。
--
-- ===========================================================================
-- 二、蓝图加粗句「部分到货必须立即更新已收数量并触发应付部分确认」
-- ===========================================================================
--
-- 这句话如果只落在应用层约定上，就只是一句口号：有人写新入口时忘了那句约定，
-- 三方数据就对不上，而 CI 一路绿。**本迁移把它落成结构**：
--
--   ① **已收数量永远由 DB 从到货流水重算**（守卫①）。
--      表级 CHECK 再兜一道：received_qty + outstanding_qty = receivable_qty。
--      ⇒ 「可收 / 已收 / 未收」三者在**任何**写入路径下都自洽。
--   ② **插入一条到货流水 ⇒ DB 触发器在同一事务里**
--      （守卫③ apply_purchase_arrival_receipt）做三件事：
--        - 更新头的已收 / 未收数量与状态（部分到货 / 全部到货）；
--        - **落一笔应付部分确认**（金额 = 单价快照 × 本次到货数量，**由 DB 算**）；
--        - 追加一条留痕。
--      ⇒ **不存在「登记了到货却没有应付部分确认」的路径**，因为那不是代码路径，是数据库不变量。
--
-- ===========================================================================
-- 三、状态机
-- ===========================================================================
--
--   PENDING_SHIPMENT   待发货：采购单已下，供应商还没发
--   IN_TRANSIT         已发货（在途）
--   PARTIALLY_RECEIVED 部分到货
--   FULLY_RECEIVED     全部到货（终态）
--   OVERDUE            逾期未到
--   CANCELLED          取消（终态）
--
-- ⚠️ **合法流转**（守卫① 逐条钉死，非法跃迁直接拒绝）：
--   PENDING_SHIPMENT   → IN_TRANSIT / PARTIALLY_RECEIVED / FULLY_RECEIVED / OVERDUE / CANCELLED
--   IN_TRANSIT         → PARTIALLY_RECEIVED / FULLY_RECEIVED / CANCELLED
--   PARTIALLY_RECEIVED → PARTIALLY_RECEIVED / FULLY_RECEIVED / OVERDUE / CANCELLED
--   OVERDUE            → IN_TRANSIT / PARTIALLY_RECEIVED / FULLY_RECEIVED / CANCELLED
--   FULLY_RECEIVED     → （终态，整行冻结）
--   CANCELLED          → （终态，整行冻结）
--
-- ⚠️ **「已发货但在途」不算逾期**是本状态机的一条硬规则，不是文案：
--   IN_TRANSIT → OVERDUE 在守卫①里被**显式拒绝**，并给出专门的理由
--   （还在路上，判逾期会误伤 —— 车在路上的货天天都在逾期的路上）。
--   逾期只从 PENDING_SHIPMENT（没发货就拖期）与 PARTIALLY_RECEIVED（收了部分、剩下的拖期）进入。
--   ⚠️ 反过来 OVERDUE → IN_TRANSIT **是允许的**：逾期之后供应商才发货是常事，
--   把这条堵死等于逼着运营去建一张新单 —— 那样「这单原来逾期过」就查不到了。
--
-- ⚠️ **逾期 = 当前时间 > 承诺到货日，且尚未全部到货**。
--   承诺到货日是跟单 / 采购单带来的**输入事实**，本模块只引用不拥有
--   ⇒ 用**逻辑引用 + 承诺日期快照**（promised_arrival_date + promise_source）。
--   判定时刻一律取**数据库时钟**（DB 里 now()），跨时钟域偏差会把没逾期的单子判成逾期。
--   日历日口径：**承诺日当天不算逾期，次日 00:00 起算**（写进能力声明，界面不自造解释）。
--
-- ===========================================================================
-- 四、处置建议的依据必须来自事实
-- ===========================================================================
--
-- 催货 / 改供应商 / 调库存 / 延期售卖四类建议，**每一条都带 basis_note（推导依据）
-- 与 basis_facts（依据里的数字，jsonb）**。依据由服务层按确定性的规则从事实拼出来
-- （逾期天数、缺口数量、已收比例、在途滞留天数、同一供应商在本租户下的逾期单数），
-- 不是一段写死的话：同样的事实得出同样的依据，换了事实依据就跟着变。
-- 建议本身是**派生结果**，因此表里允许它被刷新覆盖 —— 但动作类型与跟踪单绑定不可改。
--
-- ===========================================================================
-- 五、类目与命名
-- ===========================================================================
CREATE TABLE purchase_arrival_tracking (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  tracking_no varchar(32) NOT NULL,

  -- ---- 被跟踪的采购单：逻辑引用（6.3.1.x 未交付 ⇒ 不建 FK）----
  purchase_order_no varchar(64) NOT NULL,
  purchase_order_ref_id uuid,
  source_doc_no varchar(64) NOT NULL DEFAULT '',
  source_doc_type varchar(24) NOT NULL DEFAULT 'PURCHASE_ORDER'
      CHECK(source_doc_type IN ('PURCHASE_ORDER','PURCHASE_REQUEST','FOLLOW_UP')),

  -- ---- 供应商：catalog 独立库 ⇒ 逻辑引用 + 快照 ----
  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 数量三件套：可收 / 已收 / 未收（守卫① 重算 + 表级 CHECK 兜底）----
  receivable_qty numeric(19,6) NOT NULL CHECK(receivable_qty > 0),
  received_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(received_qty >= 0),
  outstanding_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(outstanding_qty >= 0),

  -- ---- 单价与币种：应付部分确认的依据（输入事实快照）----
  unit_price numeric(19,6) NOT NULL DEFAULT 0 CHECK(unit_price >= 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ---- 承诺到货日：跟单 / 采购单带来的输入事实快照（本模块只引用不拥有）----
  promised_arrival_date date NOT NULL,
  promise_source varchar(64) NOT NULL DEFAULT '',
  promise_updated_at timestamptz,

  -- ---- 状态机（第三节）----
  arrival_status varchar(20) NOT NULL CHECK(arrival_status IN (
      'PENDING_SHIPMENT','IN_TRANSIT','PARTIALLY_RECEIVED','FULLY_RECEIVED','OVERDUE','CANCELLED'
  )),
  shipped_at timestamptz,
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 1000),

  -- ---- 在途快照（10.4.1.2 授权只读，写时冻结；滞留天数不在本模块算）----
  in_transit_tracking_id uuid,
  in_transit_shipment_code varchar(64) NOT NULL DEFAULT '',
  in_transit_waybill_no varchar(64) NOT NULL DEFAULT '',
  in_transit_dwell_days int NOT NULL DEFAULT 0 CHECK(in_transit_dwell_days >= 0),
  in_transit_eta timestamptz,
  in_transit_last_node_at timestamptz,
  in_transit_synced_at timestamptz,

  -- ---- 应付部分确认的累计口径（由 DB 从确认分录重算，调用方传不进来）----
  pending_payable_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK(pending_payable_amount >= 0),
  confirmed_payable_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK(confirmed_payable_amount >= 0),

  -- ---- 动作留痕 ----
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  -- 乐观锁：改状态都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,tracking_no),
  -- 一张采购单一条跟踪（已收比例是采购员判断是否催货的第一指标，必须唯一口径）。
  UNIQUE(tenant_id,purchase_order_no),

  -- **数量三件套自洽**（守卫① 之外再兜一道；NUMERIC 是精确十进制，无浮点误差）。
  CHECK(received_qty + outstanding_qty = receivable_qty),
  -- 全部到货 ⇒ 已收 = 可收。
  CHECK(arrival_status <> 'FULLY_RECEIVED' OR received_qty = receivable_qty),
  -- 取消必须留理由与时刻。
  CHECK(arrival_status <> 'CANCELLED'
        OR (cancelled_at IS NOT NULL AND length(btrim(cancel_reason)) > 0)),
  -- 在途快照要么没有，要么有同步时刻（没有同步时刻的快照等于来路不明）。
  CHECK(in_transit_tracking_id IS NULL OR in_transit_synced_at IS NOT NULL)
);

COMMENT ON TABLE purchase_arrival_tracking IS
  '6.3.2.2 到货跟踪单：跟踪采购单的到货状态与数量三件套；逾期由 DB 时钟判定';

-- ===========================================================================
-- 二、到货流水（一行 = 一次到货登记；追加式）
-- ===========================================================================
--
-- ⚠️ **只留累计值是不够的**：「这单收了几次、每次收了多少、累计多少」是货损追责、
--   批次效期核对与供应商对质的起点 ⇒ 每次到货都单独留一行，序号由 DB 排。
CREATE TABLE purchase_arrival_receipt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  tracking_id uuid NOT NULL
      REFERENCES purchase_arrival_tracking(id) ON DELETE CASCADE,

  -- 序号由 DB 排（守卫②）：到货的**先后顺序**是证据链的一部分。
  arrival_seq int NOT NULL CHECK(arrival_seq > 0),
  arrived_qty numeric(19,6) NOT NULL CHECK(arrived_qty > 0),
  -- 类型由 DB 定（守卫②）：本次到货补齐了缺口就是全部到货，否则是部分到货。
  arrival_type varchar(16) NOT NULL CHECK(arrival_type IN ('PARTIAL','FULL')),

  -- 到货时刻（可能早于登记时刻 —— 补录是常态）。
  arrived_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),

  -- ---- 上游 8.2.1.1 收货单：逻辑引用 + 快照（跨库 ⇒ 不建 FK）----
  warehouse_receipt_id uuid,
  warehouse_receipt_code varchar(64) NOT NULL DEFAULT '',
  warehouse_receipt_received_qty numeric(19,6),
  warehouse_receipt_synced_at timestamptz,

  -- ---- 上游 10.4.1.2 在途跟踪：逻辑引用 ----
  waybill_no varchar(64) NOT NULL DEFAULT '',
  in_transit_tracking_id uuid,

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),
  recorded_by uuid NOT NULL,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,tracking_id,arrival_seq),
  UNIQUE(tenant_id,tracking_id,id),

  -- 收货单快照要么没有，要么有同步时刻。
  CHECK(warehouse_receipt_id IS NULL OR warehouse_receipt_synced_at IS NOT NULL)
);

COMMENT ON TABLE purchase_arrival_receipt IS
  '6.3.2.2 到货流水：每次到货一行，追加式；已收数量由 DB 从本表重算';

-- ===========================================================================
-- 三、应付部分确认（**trade 侧自有事实记录**，不是跨服务调用）
-- ===========================================================================
--
-- ⚠️ 下游 12 财务（finance 服务）**当前不存在** ⇒ 本表是 trade 侧的事实记录，
--   财务确认动作在 trade 侧以「财务确认」这一独立动作 + 独立权限落地，
--   finance 服务落地后经**授权 API / event** 衔接，本模块不写跨库分录。
--
-- ⚠️ **绝不返回「已触发财务确认」这种假回执**：
--   登记时 confirmation_status 恒为 PENDING_FINANCE_CONFIRM、effective_amount 恒为 0。
CREATE TABLE purchase_arrival_payable_confirmation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  tracking_id uuid NOT NULL
      REFERENCES purchase_arrival_tracking(id) ON DELETE CASCADE,
  receipt_id uuid NOT NULL
      REFERENCES purchase_arrival_receipt(id) ON DELETE CASCADE,

  confirmation_no varchar(32) NOT NULL,

  -- **金额、币种、单价、数量全部由 DB 取**（守卫④）：调用方传不进来，
  -- 「应付部分确认」就不会因为前端算错而对不上账。
  confirmation_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK(confirmation_amount >= 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  arrived_qty numeric(19,6) NOT NULL CHECK(arrived_qty > 0),
  unit_price numeric(19,6) NOT NULL CHECK(unit_price >= 0),
  -- 依据（可读口径）：单价 × 本次到货数量 = 确认金额。
  basis_note text NOT NULL DEFAULT '' CHECK(length(basis_note) <= 2000),

  confirmation_status varchar(24) NOT NULL CHECK(confirmation_status IN (
      'PENDING_FINANCE_CONFIRM','CONFIRMED','REJECTED'
  )),
  -- **已生效金额**：财务确认之前恒为 0（第三节）。
  effective_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK(effective_amount >= 0),

  finance_reference varchar(128) NOT NULL DEFAULT '',
  confirmed_by uuid,
  confirmed_at timestamptz,
  rejected_by uuid,
  rejected_at timestamptz,
  reject_reason text NOT NULL DEFAULT '' CHECK(length(reject_reason) <= 1000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,confirmation_no),
  -- 一次到货一笔确认（守卫③ 建的就是这一对）。
  UNIQUE(tenant_id,tracking_id,receipt_id),

  -- **「没有财务确认就没有生效」的三条 CHECK**（第三节）：
  --   未确认 ⇒ 生效金额恒为 0；
  --   已确认 ⇒ 生效金额 = 确认金额，且确认人 / 确认时刻 / 财务单据号齐全；
  --   已驳回 ⇒ 生效金额为 0，且留驳回人与理由。
  CHECK(confirmation_status <> 'CONFIRMED'
        OR (effective_amount = confirmation_amount
            AND confirmed_by IS NOT NULL
            AND confirmed_at IS NOT NULL
            AND length(btrim(finance_reference)) > 0)),
  CHECK(confirmation_status <> 'PENDING_FINANCE_CONFIRM' OR effective_amount = 0),
  CHECK(confirmation_status <> 'REJECTED'
        OR (effective_amount = 0
            AND rejected_by IS NOT NULL
            AND rejected_at IS NOT NULL
            AND length(btrim(reject_reason)) > 0))
);

COMMENT ON TABLE purchase_arrival_payable_confirmation IS
  '6.3.2.2 应付部分确认：trade 侧自有事实记录；effective_amount 在财务确认前恒为 0';

-- ===========================================================================
-- 四、处置建议（催货 / 改供应商 / 调库存 / 延期售卖）
-- ===========================================================================
CREATE TABLE purchase_arrival_disposition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  tracking_id uuid NOT NULL
      REFERENCES purchase_arrival_tracking(id) ON DELETE CASCADE,

  action_type varchar(24) NOT NULL CHECK(action_type IN (
      'URGE_SUPPLIER','SWITCH_SUPPLIER','ADJUST_STOCK','DELAY_SALE'
  )),
  severity varchar(8) NOT NULL CHECK(severity IN ('INFO','WARN','CRITICAL')),

  -- **推导依据**：由服务层按确定性规则从事实拼出来（不是写死的一段话）。
  basis_note text NOT NULL CHECK(length(basis_note) > 0 AND length(basis_note) <= 2000),
  -- 依据里的数字（jsonb）：界面不必去解析中文就能把数字摆出来。
  basis_facts jsonb NOT NULL DEFAULT '{}'::jsonb,

  disposition_status varchar(16) NOT NULL DEFAULT 'OPEN'
      CHECK(disposition_status IN ('OPEN','ADOPTED','DISMISSED')),
  resolve_note text NOT NULL DEFAULT '' CHECK(length(resolve_note) <= 1000),
  resolved_by uuid,
  resolved_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  -- 一单一类建议一条：刷新覆盖依据，不堆重复行。
  UNIQUE(tenant_id,tracking_id,action_type)
);

COMMENT ON TABLE purchase_arrival_disposition IS
  '6.3.2.2 处置建议：依据由事实推导（逾期天数 / 缺口 / 在途滞留天数 / 供应商逾期单数）';

-- ===========================================================================
-- 五、留痕（追加式）
-- ===========================================================================
CREATE TABLE purchase_arrival_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  tracking_id uuid NOT NULL,

  event_type varchar(32) NOT NULL CHECK(event_type IN (
      'CREATED','SHIPPED','ARRIVAL_RECORDED','FULLY_RECEIVED','OVERDUE_MARKED',
      'IN_TRANSIT_SYNCED','CANCELLED',
      'PAYABLE_REGISTERED','PAYABLE_CONFIRMED','PAYABLE_REJECTED',
      'DISPOSITION_REFRESHED','DISPOSITION_RESOLVED'
  )),
  from_status varchar(20),
  to_status varchar(20),

  amount numeric(19,6),
  quantity numeric(19,6),
  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- **留痕里的状态对必须是合法跃迁**（与守卫① 的白名单逐条对齐）。
  -- ⚠️ 两条细节：
  --   ① from_status 为 NULL = 这条事件**不记状态跃迁**（建单、应付确认、处置建议等）；
  --   ② from_status = to_status 是**合法的**（部分到货 → 部分到货：又收了一批，数量变了，
  --      状态没变）—— 这正是「部分到货必须立即更新已收数量」在留痕上的样子。
  CHECK(
    from_status IS NULL
    OR (
      to_status IS NOT NULL
      AND (
        (from_status = 'PENDING_SHIPMENT'
            AND to_status IN ('IN_TRANSIT','PARTIALLY_RECEIVED','FULLY_RECEIVED','OVERDUE','CANCELLED'))
        OR (from_status = 'IN_TRANSIT'
            AND to_status IN ('PARTIALLY_RECEIVED','FULLY_RECEIVED','CANCELLED'))
        OR (from_status = 'PARTIALLY_RECEIVED'
            AND to_status IN ('PARTIALLY_RECEIVED','FULLY_RECEIVED','OVERDUE','CANCELLED'))
        OR (from_status = 'OVERDUE'
            AND to_status IN ('IN_TRANSIT','PARTIALLY_RECEIVED','FULLY_RECEIVED','CANCELLED'))
      )
    )
  )
);

COMMENT ON TABLE purchase_arrival_event IS
  '6.3.2.2 到货跟踪留痕：追加式，运行角色 UPDATE/DELETE 已收回';

-- ===========================================================================
-- 六、DB 守卫（服务层校验可以绕开，触发器绕不开）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 守卫① guard_purchase_arrival_tracking_head：数量三件套 + 状态机跃迁
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_purchase_arrival_tracking_head() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_received numeric(19,6);
BEGIN
  -- ---- 已收数量：永远从到货流水重算，调用方传不进来 ----
  -- 「可收 / 已收 / 未收」三者在任何写入路径下都自洽（第二节 ①）。
  IF TG_OP = 'UPDATE' THEN
    SELECT coalesce(sum(r.arrived_qty), 0) INTO t_received
      FROM purchase_arrival_receipt r
     WHERE r.tenant_id = NEW.tenant_id
       AND r.tracking_id = NEW.id;
  ELSE
    t_received := 0;
  END IF;

  NEW.received_qty    := t_received;
  NEW.outstanding_qty := NEW.receivable_qty - t_received;

  IF NEW.outstanding_qty < 0 THEN
    RAISE EXCEPTION '可收数量（%）不得小于已收数量（%）：跟踪单 %',
      NEW.receivable_qty, t_received, NEW.id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 状态机跃迁（第三节）----
  IF TG_OP = 'UPDATE' AND NEW.arrival_status IS DISTINCT FROM OLD.arrival_status THEN
    -- ⚠️ **在途不判逾期**单独一条（理由要看得懂，不能只靠「不在白名单里」）。
    IF OLD.arrival_status = 'IN_TRANSIT' AND NEW.arrival_status = 'OVERDUE' THEN
      RAISE EXCEPTION '已发货在途的跟踪单不判逾期（还在路上，判逾期会误伤）：跟踪单 %', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;

    IF NOT (
        (OLD.arrival_status = 'PENDING_SHIPMENT'
            AND NEW.arrival_status IN ('IN_TRANSIT','PARTIALLY_RECEIVED','FULLY_RECEIVED','OVERDUE','CANCELLED'))
        OR (OLD.arrival_status = 'IN_TRANSIT'
            AND NEW.arrival_status IN ('PARTIALLY_RECEIVED','FULLY_RECEIVED','CANCELLED'))
        OR (OLD.arrival_status = 'PARTIALLY_RECEIVED'
            AND NEW.arrival_status IN ('PARTIALLY_RECEIVED','FULLY_RECEIVED','OVERDUE','CANCELLED'))
        OR (OLD.arrival_status = 'OVERDUE'
            AND NEW.arrival_status IN ('IN_TRANSIT','PARTIALLY_RECEIVED','FULLY_RECEIVED','CANCELLED'))
      ) THEN
      RAISE EXCEPTION '非法的到货状态跃迁：% → %（合法流转见 V235 第三节；全部到货 / 取消是终态，不可再流转）',
        OLD.arrival_status, NEW.arrival_status USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_purchase_arrival_tracking_head
  BEFORE INSERT OR UPDATE ON purchase_arrival_tracking
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_arrival_tracking_head();

-- ---------------------------------------------------------------------------
-- 守卫② guard_purchase_arrival_receipt：可收状态 + 不许超收 + 序号与类型由 DB 定
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_purchase_arrival_receipt() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_head record;
  t_seq int;
BEGIN
  SELECT * INTO t_head
    FROM purchase_arrival_tracking
   WHERE tenant_id = NEW.tenant_id AND id = NEW.tracking_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '到货跟踪单不存在或不属于当前租户：tracking_id=%', NEW.tracking_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 终态不再收货。
  IF t_head.arrival_status IN ('FULLY_RECEIVED','CANCELLED') THEN
    RAISE EXCEPTION '到货跟踪单已终结（当前 %），不能再登记到货：tracking_id=%',
      t_head.arrival_status, NEW.tracking_id USING ERRCODE = 'check_violation';
  END IF;

  -- **不许超收**：收货量不得超过未收量。
  IF NEW.arrived_qty > t_head.outstanding_qty THEN
    RAISE EXCEPTION '本次到货数量（%）不得超过未收数量（%）（可收 %，已收 %）：tracking_id=%',
      NEW.arrived_qty, t_head.outstanding_qty, t_head.receivable_qty, t_head.received_qty,
      NEW.tracking_id USING ERRCODE = 'check_violation';
  END IF;

  -- 序号由 DB 排：到货的先后顺序是证据链的一部分。
  SELECT coalesce(max(r.arrival_seq), 0) + 1 INTO t_seq
    FROM purchase_arrival_receipt r
   WHERE r.tenant_id = NEW.tenant_id AND r.tracking_id = NEW.tracking_id;
  NEW.arrival_seq := t_seq;

  -- 类型由 DB 定：补齐缺口 = 全部到货，否则 = 部分到货。
  NEW.arrival_type := CASE WHEN NEW.arrived_qty = t_head.outstanding_qty THEN 'FULL' ELSE 'PARTIAL' END;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_purchase_arrival_receipt
  BEFORE INSERT ON purchase_arrival_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_arrival_receipt();

-- ---------------------------------------------------------------------------
-- 守卫③ apply_purchase_arrival_receipt：**部分到货 → 更新已收数量 + 应付部分确认**
-- ---------------------------------------------------------------------------
--
-- 这一段是蓝图加粗句的落点。四件事在**同一个事务**里做完：
--   ① 头的已收 / 未收数量与状态；
--   ② 一笔应付部分确认（金额 = 单价快照 × 本次到货数量，**由 DB 算**）；
--   ③ 头部应付累计重算；
--   ④ 一条留痕。
CREATE FUNCTION apply_purchase_arrival_receipt() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_head record;
  t_received numeric(19,6);
  t_outstanding numeric(19,6);
  t_status varchar(20);
  t_amount numeric(19,6);
  t_conf_no bigint;
BEGIN
  SELECT * INTO t_head
    FROM purchase_arrival_tracking
   WHERE tenant_id = NEW.tenant_id AND id = NEW.tracking_id;

  SELECT coalesce(sum(r.arrived_qty), 0) INTO t_received
    FROM purchase_arrival_receipt r
   WHERE r.tenant_id = NEW.tenant_id AND r.tracking_id = NEW.tracking_id;

  t_outstanding := t_head.receivable_qty - t_received;
  t_status := CASE WHEN t_outstanding = 0 THEN 'FULLY_RECEIVED' ELSE 'PARTIALLY_RECEIVED' END;

  -- ① 更新头的数量与状态（守卫① 会把数量再按流水重算一遍 —— 两条路径必然同值）。
  UPDATE purchase_arrival_tracking
     SET received_qty    = t_received,
         outstanding_qty = t_outstanding,
         arrival_status  = t_status,
         updated_at      = now(),
         version         = version + 1
   WHERE tenant_id = NEW.tenant_id AND id = NEW.tracking_id;

  -- ② 落一笔应付部分确认（金额由 DB 按单价快照 × 本次到货数量算）。
  t_amount  := round(t_head.unit_price * NEW.arrived_qty, 6);
  t_conf_no := nextval('purchase_arrival_confirmation_no_seq');

  INSERT INTO purchase_arrival_payable_confirmation(
      id, tenant_id, tracking_id, receipt_id, confirmation_no,
      confirmation_amount, currency, arrived_qty, unit_price, basis_note,
      confirmation_status, effective_amount,
      created_by, created_at, updated_at, version)
  VALUES (
      gen_random_uuid(), NEW.tenant_id, NEW.tracking_id, NEW.id,
      'PC-' || t_conf_no,
      t_amount, t_head.currency, NEW.arrived_qty, t_head.unit_price,
      '部分到货确认：单价 ' || t_head.unit_price || ' × 本次到货 ' || NEW.arrived_qty
        || ' = ' || t_amount || ' ' || t_head.currency
        || '（12 财务服务尚未交付，本条是 trade 侧自有事实记录，尚未经财务确认）',
      'PENDING_FINANCE_CONFIRM', 0,
      NEW.recorded_by, now(), now(), 0);

  -- ③ 头部应付累计重算（守卫④ 已把确认金额按真值重算过，这里取的就是真值）。
  UPDATE purchase_arrival_tracking t
     SET pending_payable_amount = (
           SELECT coalesce(sum(c.confirmation_amount), 0)
             FROM purchase_arrival_payable_confirmation c
            WHERE c.tenant_id = t.tenant_id AND c.tracking_id = t.id
              AND c.confirmation_status = 'PENDING_FINANCE_CONFIRM'),
         confirmed_payable_amount = (
           SELECT coalesce(sum(c.effective_amount), 0)
             FROM purchase_arrival_payable_confirmation c
            WHERE c.tenant_id = t.tenant_id AND c.tracking_id = t.id
              AND c.confirmation_status = 'CONFIRMED')
   WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.tracking_id;

  -- ④ 留痕。
  INSERT INTO purchase_arrival_event(
      id, tenant_id, tracking_id, event_type, from_status, to_status,
      amount, quantity, reason_note, occurred_by, occurred_at, created_at)
  VALUES (
      gen_random_uuid(), NEW.tenant_id, NEW.tracking_id, 'ARRIVAL_RECORDED',
      t_head.arrival_status, t_status,
      t_amount, NEW.arrived_qty, NEW.note, NEW.recorded_by, NEW.arrived_at, now());

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_apply_purchase_arrival_receipt
  AFTER INSERT ON purchase_arrival_receipt
  FOR EACH ROW EXECUTE FUNCTION apply_purchase_arrival_receipt();

-- ---------------------------------------------------------------------------
-- 守卫④ guard_purchase_arrival_payable_confirmation：金额由 DB 取 + 确认状态机
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_purchase_arrival_payable_confirmation() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_head record;
  t_receipt record;
BEGIN
  SELECT * INTO t_head
    FROM purchase_arrival_tracking
   WHERE tenant_id = NEW.tenant_id AND id = NEW.tracking_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '到货跟踪单不存在或不属于当前租户：tracking_id=%', NEW.tracking_id
      USING ERRCODE = 'no_data_found';
  END IF;

  SELECT * INTO t_receipt
    FROM purchase_arrival_receipt
   WHERE tenant_id = NEW.tenant_id AND id = NEW.receipt_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '到货流水不存在或不属于当前租户：receipt_id=%', NEW.receipt_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- ---- 金额 / 币种 / 单价 / 数量：全部由 DB 取，调用方传不进来 ----
  NEW.confirmation_amount := round(t_head.unit_price * t_receipt.arrived_qty, 6);
  NEW.currency       := t_head.currency;
  NEW.unit_price     := t_head.unit_price;
  NEW.arrived_qty    := t_receipt.arrived_qty;
  NEW.basis_note     := '部分到货确认：单价 ' || t_head.unit_price
                        || ' × 本次到货 ' || t_receipt.arrived_qty
                        || ' = ' || NEW.confirmation_amount || ' ' || t_head.currency
                        || '（12 财务服务尚未交付，本条是 trade 侧自有事实记录，尚未经财务确认）';

  -- ---- 状态机：待确认 → 已确认 / 已驳回（终态不可再流转）----
  IF TG_OP = 'UPDATE' THEN
    IF OLD.confirmation_status IN ('CONFIRMED','REJECTED') THEN
      RAISE EXCEPTION '应付部分确认已终结（当前 %），整行冻结：confirmation_id=%',
        OLD.confirmation_status, OLD.id USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.confirmation_status IS DISTINCT FROM OLD.confirmation_status
       AND NEW.confirmation_status NOT IN ('CONFIRMED','REJECTED') THEN
      RAISE EXCEPTION '应付部分确认只能从待确认改为已确认或已驳回：confirmation_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- ---- 生效金额由状态决定（第三节）----
  IF NEW.confirmation_status = 'CONFIRMED' THEN
    NEW.effective_amount := NEW.confirmation_amount;
  ELSE
    NEW.effective_amount := 0;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_purchase_arrival_payable_confirmation
  BEFORE INSERT OR UPDATE ON purchase_arrival_payable_confirmation
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_arrival_payable_confirmation();

-- ---------------------------------------------------------------------------
-- 守卫⑤ guard_purchase_arrival_disposition：动作类型与绑定不可改，状态机受限
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_purchase_arrival_disposition() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.tracking_id IS DISTINCT FROM OLD.tracking_id
       OR NEW.action_type IS DISTINCT FROM OLD.action_type THEN
      RAISE EXCEPTION '处置建议的动作类型与跟踪单绑定不可改（建议是派生结果，刷新只覆盖依据与档位）：disposition_id=%',
        NEW.id USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.disposition_status IS DISTINCT FROM OLD.disposition_status
       AND NOT (OLD.disposition_status = 'OPEN'
                AND NEW.disposition_status IN ('ADOPTED','DISMISSED')) THEN
      RAISE EXCEPTION '处置建议只能从待处理改为已采纳或已忽略：disposition_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_purchase_arrival_disposition
  BEFORE UPDATE ON purchase_arrival_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_arrival_disposition();

-- ===========================================================================
-- 七、索引
-- ===========================================================================
-- ① 列表主路径：按状态读。
CREATE INDEX purchase_arrival_tracking_by_status
  ON purchase_arrival_tracking(tenant_id, arrival_status, created_at desc);
-- ② 逾期扫描：自动预警按「承诺到货日 + 可逾期状态」扫。
CREATE INDEX purchase_arrival_tracking_by_promise
  ON purchase_arrival_tracking(tenant_id, promised_arrival_date)
  WHERE arrival_status IN ('PENDING_SHIPMENT','PARTIALLY_RECEIVED');
-- ③ 供应商维度：按供应商读 + 统计该供应商的逾期单数。
CREATE INDEX purchase_arrival_tracking_by_supplier
  ON purchase_arrival_tracking(tenant_id, supplier_ref_id, created_at desc);
-- ④ 到货流水按单读（逐笔流水）。
CREATE INDEX purchase_arrival_receipt_by_tracking
  ON purchase_arrival_receipt(tenant_id, tracking_id, arrival_seq);
-- ⑤ 应付部分确认按单读。
CREATE INDEX purchase_arrival_payable_by_tracking
  ON purchase_arrival_payable_confirmation(tenant_id, tracking_id, created_at);
-- ⑥ **一条到货只允许一笔应付部分确认**（DB 不变量而不是应用层先查一次 ——
--    并发下两个请求都能查到「还没有」，靠应用层判会双双通过）。
CREATE UNIQUE INDEX purchase_arrival_one_confirmation_per_receipt
  ON purchase_arrival_payable_confirmation(tenant_id, receipt_id);
-- ⑦ 处置建议按单读。
CREATE INDEX purchase_arrival_disposition_by_tracking
  ON purchase_arrival_disposition(tenant_id, tracking_id, action_type);
-- ⑧ 留痕回放。
CREATE INDEX purchase_arrival_event_by_tracking
  ON purchase_arrival_event(tenant_id, tracking_id, occurred_at);

CREATE SEQUENCE purchase_arrival_no_seq;
GRANT USAGE,SELECT ON SEQUENCE purchase_arrival_no_seq TO trade_app;
CREATE SEQUENCE purchase_arrival_confirmation_no_seq;
GRANT USAGE,SELECT ON SEQUENCE purchase_arrival_confirmation_no_seq TO trade_app;

-- ===========================================================================
-- 八、多租户：RLS + 授权
-- ===========================================================================
-- 五张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：跟踪单里同时有**供应商名、采购单号、单价、应付金额与逾期天数**，
--   是采购侧最密的一张单据；端点又大多是**按 id 直达**的 URL ——
--   猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_arrival_tracking','purchase_arrival_receipt',
    'purchase_arrival_payable_confirmation','purchase_arrival_disposition',
    'purchase_arrival_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_arrival_tracking, purchase_arrival_receipt,
  purchase_arrival_payable_confirmation, purchase_arrival_disposition,
  purchase_arrival_event TO trade_app;

-- 授权在前、收回在后。
-- ① **到货流水是证据链，追加式**：能被悄悄改掉的「这批货什么时候到的」等于没有证据。
--    （应付确认与流水的关联键在 purchase_arrival_payable_confirmation.receipt_id 上，
--      不需要回头改流水行 —— 所以这里可以放心收回 UPDATE。）
REVOKE UPDATE,DELETE ON purchase_arrival_receipt FROM trade_app;
-- ② **留痕追加式**：同理。
REVOKE UPDATE,DELETE ON purchase_arrival_event FROM trade_app;
-- ③ **应付部分确认的金额列改不动**：守卫④ 会在每次写入时按「跟踪单单价 × 本次到货数量」
--    重算金额 / 币种 / 单价 / 数量 / 依据，调用方传不进来；已确认 / 已驳回的行整行冻结。
--    改金额的唯一路径是重新登记一笔到货。

-- 本迁移**不 ALTER 任何既有表**：warehouse 的 8.2.1.1 / 10.4.1.2 与 catalog 的
-- 供应商主数据全部只经**授权只读端口**访问，跨车道 DDL 回归面 = 0。
