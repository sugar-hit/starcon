-- ===========================================================================
-- 11.3.2.1 换货授权
-- ===========================================================================
--
-- 蓝图原文（11-aftersales-and-returns.md 11.3.2.1）：
--   职责：批准换货：确认可换数量与库存（**要真实预占替换货**）、生成新出库安排，
--         保留原单追溯关系。
--   输入：换货申请；替换商品选择；库存可用性；expected_version。
--   输出：换货授权 + 新发货需求 + 替换货预占。
--   关系：上游 = 11.1.1.2；下游 = 9.3.3.1 新出库、11.2 退回旧货。
--   **换货必须同时预占替换货并等待旧货回寄**，否则会出现只发不收的损失。
--
-- ===========================================================================
-- 一、五张表的分工（输出句的三个产物各归一张，加两张把它撑起来）
-- ===========================================================================
--
--   exchange_authorization              换货授权单（头）
--   exchange_authorization_line         逐行：原 SKU 退什么、换成哪个 SKU、换几件
--   exchange_reservation                替换货预占记录（**指向 warehouse 的真实预占**）
--   exchange_outbound_demand            新发货需求（输出句第二项）
--   exchange_return_requirement         等待旧货回寄（加粗句的后半段）
--
-- 「保留原单追溯关系」落在两处：头上的 order_id / case_id / case_no / order_no，
-- 以及行上的 original_sku_* —— 换货完成后「这件是替哪一件换的」必须能一眼查出来。
--
-- ===========================================================================
-- 二、预占为什么落在 warehouse、这里只落「记录」
-- ===========================================================================
--
-- ⚠️ 蓝图那句「**要真实预占替换货**」是最容易被做成假的一条：只查一次可用量、
--   在本服务写个 is_reserved = true 的标志位，就宣称预占完成 —— 那不是预占，
--   那是把「查到什么」记成「占住了什么」，并发两单会把同一份库存预占两次。
--
-- 实查集成头：**预占事实的写入所有者是 warehouse**（7.2.1.1 inventory_reservation，
--   迁移 V102；物理圈货到 bucket 由 7.2.1.2 reservation_allocation 落 V107）。
--   AGENTS.md 模块边界纪律禁止跨服务 SQL / 外键 / 共享视图，所以本模块：
--     * 通过 warehouse 的**授权写端点** POST /warehouse/inventory/reservations 登记预占；
--     * 把返回的 reservation_id / reservation_no **如实记进 exchange_reservation**；
--     * 绝不自己往 warehouse 的表插一行，也绝不凭空造一个 reservation_id。
--
-- DB 层把这件事钉死（guard_exchange_reservation + 迟到约束触发器）：
--   * reservation_id NOT NULL —— 没有真实预占 id 的行根本写不进来，
--     所以「只读检查 + 标志位」那种写法在本服务的 schema 里**不可表达**；
--   * 迟到约束触发器 trg_exchange_line_reserved 要求
--     **该行预占量之和 == 授权量 == reserved_quantity**，差额在提交时就报错。
--
-- ⚠️ 取消换货要真把货还回去：trade 调 warehouse 的
--   POST /warehouse/inventory/reservations/{id}/release（7.2.1.3，V112 已交付），
--   本服务把 exchange_reservation.status 置 RELEASED。**本地改状态不等于货还了**，
--   所以服务层在本地落 RELEASED 之前必须先拿到 warehouse 的应答，
--   拿不到就整笔回滚并如实报错 —— 那正是「本地说还了、仓库还占着」这种
--   幽灵占用最常见的成因。
--
-- ===========================================================================
-- 三、加粗句的落点：预占与回寄要求**同时**存在，缺一不可
-- ===========================================================================
--
-- 「换货必须同时预占替换货并等待旧货回寄，否则会出现只发不收的损失。」
-- 只发不收 = 替换货发出去、旧货再也没回来 = 白送。
--
-- 这条不能只靠服务层自觉，所以做成**两个 DEFERRABLE INITIALLY DEFERRED 约束触发器**
-- （检查在 COMMIT 时跑，此时本事务的行全都写完了）：
--   trg_exchange_line_reserved    每一行：恰好一条预占 + 恰好一条回寄要求 + 数量对齐
--   trg_exchange_authorization_complete  头：至少一行，且各行数量之和 == 头上数量
-- 于是「有授权行但没预占」「预占了却没等回寄」「预占量与授权量对不上」这三种
-- 只发不收的入口，**绕过服务层直接写 SQL 也一样在提交时被数据库拒掉**。
--
-- ===========================================================================
-- 四、防重复换货（蓝图 11.3.2.2 那句「防止重复换货」的前置条件）
-- ===========================================================================
--
--   UNIQUE(tenant_id, case_id)          一张换货售后单只能有一张换货授权单
--   UNIQUE(tenant_id, case_line_id)     一条售后商品行只能被换一次
--
-- 两条都在 DB 层。⚠️ 它们是**并发下真正起作用**的那道防线：warehouse 的
-- 7.2.1.1 登记端点在 (source_system, source_ref_id) 上**没有**唯一约束
-- （V102 只 UNIQUE(tenant_id, reservation_no)，而单号是 warehouse 自己发的），
-- 所以同一个售后单被并发提交两次时，两次请求都会真的打到 warehouse 并各拿到一个
-- reservation_no —— 只有 trade 侧先锁住 aftersales_case（SELECT ... FOR UPDATE）
-- 再让唯一约束判第二次，那第二次才会在**发出远程调用之前**被拒。
-- 服务层因此把「锁单 + 查重」放在调用 warehouse **之前**，顺序反了这个防线就废了。
--
-- ===========================================================================
-- 五、多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT（与 V159 同一条）
-- ===========================================================================
--
-- 五张表同属一条换货授权链路，同一套 RLS 表达式：租户由 backend/libraries/tenancy
-- 在事务内 set_config('app.current_tenant') 写入，客户端的 X-Tenant-Id 头进不来也改不动它。
-- 服务层的 tenant_id 谓词是第二道防线。
--
-- 本迁移**不 ALTER 任何既有表**：sales_order / sales_order_line / aftersales_case /
-- aftersales_case_line 全部只读，跨车道 DDL 回归面 = 0。

-- ===========================================================================
-- 1. 换货授权单头
-- ===========================================================================

CREATE SEQUENCE exchange_authorization_no_seq;

CREATE TABLE exchange_authorization (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 换货授权号：EX-YYYYMMDD-六位，由本序列生成（不采信调用方传入的单号，理由同 V159）。
  authorization_no varchar(32) NOT NULL,

  -- ---- 原单追溯（职责句「保留原单追溯关系」的落点）----
  -- 关联售后单（11.1.1.1/11.1.1.2 已交付，V156/V157）。刻意不加外键，见第五节说明。
  case_id uuid NOT NULL,
  case_no varchar(32) NOT NULL,
  -- 原销售订单：换货不修改原出库事实（11.3.2.2 原文），所以原单号在这里被钉成快照。
  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL DEFAULT '',

  -- 生命周期：**只有成立与撤销两态**。
  -- 「拒绝」刻意不落成一行：库存不足而授权不了，就是没有授权可立，
  -- 服务层返回 409 并写明缺多少；落一条 REJECTED 记录只会让人在列表里
  -- 反复看到一张永远不会被处理的单。
  status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','CANCELLED')),

  -- 授权结论：整量批准 / 因库存不足部分批准。
  -- 部分批准必须与整量批准在数据上分开，否则「批了 2 件、申请 3 件」会被读成全额批准。
  decision varchar(24) NOT NULL CHECK(decision IN ('APPROVED','PARTIALLY_APPROVED')),

  -- 数量：单头三个数与各行之和严格相等（迟到约束触发器 trg_exchange_authorization_complete）。
  requested_quantity numeric(19,6) NOT NULL CHECK(requested_quantity > 0),
  authorized_quantity numeric(19,6) NOT NULL CHECK(authorized_quantity > 0),
  -- 替换 SKU 去重后的个数：运营一眼看出「这次换涉及几个新品」。
  replacement_sku_count integer NOT NULL CHECK(replacement_sku_count > 0),

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 1000),

  -- 乐观锁：蓝图输入句点名的 expected_version。取消换货必须带它，
  -- 两个人同时点「撤销」时后到的那个必须被 409 拒掉，而不是两个人各撤一次。
  version integer NOT NULL DEFAULT 1 CHECK(version > 0),

  authorized_by uuid NOT NULL,
  authorized_at timestamptz NOT NULL,
  cancelled_by uuid,
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 1000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,authorization_no),
  -- 一张换货售后单只能有一张授权单（防重复换货，并发下真正起作用的那道防线，见第四节）。
  UNIQUE(tenant_id,case_id),

  -- 取消痕迹与状态严格等价：进 CANCELLED 必带人 + 时刻，出 CANCELLED 必清空。
  -- 只改状态不落时刻的话，「这单是谁撤的、什么时候撤的」永远查不到。
  CHECK((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
  CHECK((status = 'CANCELLED') = (cancelled_by IS NOT NULL)),
  -- 整量批准 ⟺ 授权量等于申请量。部分批准必须真的少批了。
  CHECK(decision <> 'APPROVED' OR authorized_quantity = requested_quantity),
  CHECK(decision <> 'PARTIALLY_APPROVED' OR authorized_quantity < requested_quantity),
  -- 授权量不许超过申请量：超发就是凭空多发货。
  CHECK(authorized_quantity <= requested_quantity)
);

CREATE INDEX exchange_authorization_list
  ON exchange_authorization(tenant_id,status,authorized_at DESC,id);
CREATE INDEX exchange_authorization_order
  ON exchange_authorization(tenant_id,order_id,authorized_at DESC);

-- ===========================================================================
-- 2. 换货授权行（逐条售后商品行：退什么 → 换成什么 → 换几件）
-- ===========================================================================

CREATE TABLE exchange_authorization_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  -- 被换的那条售后商品行（aftersales_case_line，V157）。
  case_line_id uuid NOT NULL,

  -- ---- 原商品快照（追溯用，不因后续商品改名而变）----
  original_sku_id uuid NOT NULL,
  original_sku_code varchar(64) NOT NULL DEFAULT '',
  original_sku_name varchar(200) NOT NULL DEFAULT '',
  original_quantity numeric(19,6) NOT NULL CHECK(original_quantity > 0),

  -- ---- 替换商品（输入句「替换商品选择」的落点）----
  replacement_sku_id uuid NOT NULL,
  replacement_sku_code varchar(64) NOT NULL DEFAULT '',
  replacement_sku_name varchar(200) NOT NULL DEFAULT '',
  -- 同一个替换 SKU 被选两次没有意义（两行合起来才是「换两件」），
  -- 所以服务层会合并，这里只保证合并后数量守恒。
  replacement_reason varchar(200) NOT NULL DEFAULT '' CHECK(length(replacement_reason) <= 200),

  -- 履约仓与法人：warehouse 登记预占时必填（V102 的 legal_entity_id NOT NULL）。
  warehouse_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,

  -- ---- 数量三级链条（DB 层 CHECK + 迟到触发器双重保证）----
  requested_quantity numeric(19,6) NOT NULL CHECK(requested_quantity > 0),
  authorized_quantity numeric(19,6) NOT NULL CHECK(authorized_quantity > 0),
  -- 已预占量：恒等于授权量。差额只允许出现在「还没提交」的中间态，
  -- 提交时由 trg_exchange_line_reserved 强制相等。
  reserved_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(reserved_quantity >= 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,authorization_id,case_line_id),
  -- 一条售后商品行只能被换一次（防重复换货）。
  UNIQUE(tenant_id,case_line_id),

  -- 数量链：预占 ≤ 授权 ≤ 申请 ≤ 原数量。四级一次写清，
  -- 拆成四条 CHECK 是为了让报错信息直接指出是哪一级被越过。
  CHECK(reserved_quantity <= authorized_quantity),
  CHECK(authorized_quantity <= requested_quantity),
  CHECK(requested_quantity <= original_quantity),
  -- 换货成同一个 SKU 等于没换。
  CHECK(replacement_sku_id <> original_sku_id),
  -- 「已预占 0 件」与「已预占 0.000000」必须同一个语义，CHECK 不给绕。
  CHECK(authorized_quantity = reserved_quantity OR reserved_quantity = 0)
);

CREATE INDEX exchange_authorization_line_auth
  ON exchange_authorization_line(tenant_id,authorization_id,id);

-- ===========================================================================
-- 3. 替换货预占记录（本服务记的是「我预占了哪一笔」，预占本体在 warehouse）
-- ===========================================================================

CREATE TABLE exchange_reservation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  authorization_line_id uuid NOT NULL,

  -- warehouse 7.2.1.1 返回的预占 id 与单号。
  -- ⚠️ NOT NULL 且无默认值：写不出「没有真实预占」的记录，
  --   所以「只读检查 + 一个标志位」在这种 schema 里不可表达（见第二节）。
  reservation_id uuid NOT NULL,
  reservation_no varchar(32) NOT NULL,

  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),

  -- HELD：已向 warehouse 登记且尚未释放；RELEASED：撤销换货时已真释放。
  status varchar(16) NOT NULL CHECK(status IN ('HELD','RELEASED')),

  released_at timestamptz,
  release_reason text NOT NULL DEFAULT '' CHECK(length(release_reason) <= 1000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一条授权行恰好一笔预占（trg_exchange_line_reserved 会在提交时复核）。
  UNIQUE(tenant_id,authorization_line_id),
  -- 同一笔 warehouse 预占不允许被两行引用：否则「同一个 reservation 被占了两次」
  -- 在本服务的数据上就已经是自相矛盾的。
  UNIQUE(tenant_id,reservation_id),
  -- 释放痕迹与状态严格等价。
  CHECK((status = 'RELEASED') = (released_at IS NOT NULL))
);

CREATE INDEX exchange_reservation_status_list
  ON exchange_reservation(tenant_id,status,created_at DESC,id);

-- ===========================================================================
-- 4. 新发货需求（输出句「新发货需求」）
-- ===========================================================================

CREATE TABLE exchange_outbound_demand (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  authorization_line_id uuid NOT NULL,

  demand_no varchar(32) NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  warehouse_id uuid NOT NULL,

  -- PENDING：需求已成立，等 9.3.3.1 新出库接手；CANCELLED：换货被撤销。
  -- ⚠️ 刻意**不建** outbound_order_ref / dispatched_at 这类「下游回填」列：
  --   9.3.3.1 尚未从本模块接手，建一列永远为空的引用字段只会在页面上显示成
  --   「已下发」而实际没有任何出库单 —— 那正是「空时间线被读成没发生过迁移」同款假交付。
  status varchar(16) NOT NULL CHECK(status IN ('PENDING','CANCELLED')),

  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 1000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,authorization_line_id),
  UNIQUE(tenant_id,demand_no)
);

CREATE INDEX exchange_outbound_demand_list
  ON exchange_outbound_demand(tenant_id,status,created_at DESC,id);

-- ===========================================================================
-- 5. 等待旧货回寄（加粗句的后半段）
-- ===========================================================================

CREATE TABLE exchange_return_requirement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  authorization_line_id uuid NOT NULL,

  -- 要收回来的旧货是哪几件。
  original_sku_id uuid NOT NULL,
  original_sku_code varchar(64) NOT NULL DEFAULT '',
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),

  -- WAITING：等买家寄回；WAIVED：换货被撤销或经批准免回。
  -- ⚠️ 没有 RETURNED：实收与质检归 11.2 退回旧货（V161 车道，尚未并入），
  --   本模块自造一个 RETURNED 就会与将来的实收状态机打架。
  status varchar(16) NOT NULL CHECK(status IN ('WAITING','WAIVED')),

  note varchar(500) NOT NULL DEFAULT '' CHECK(length(note) <= 500),
  waived_by uuid,
  waived_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一条授权行恰好一条回寄要求（加粗句：预占与回寄同时存在，缺一不可）。
  UNIQUE(tenant_id,authorization_line_id),
  -- 免回痕迹与状态严格等价。
  CHECK((status = 'WAIVED') = (waived_at IS NOT NULL)),
  CHECK((status = 'WAIVED') = (waived_by IS NOT NULL))
);

CREATE INDEX exchange_return_requirement_list
  ON exchange_return_requirement(tenant_id,status,created_at DESC,id);

-- ===========================================================================
-- 六、护栏触发器（与 V156/V157 同一套写法）
-- ===========================================================================

-- ---- ① 授权头必须挂在真实、未结案、类型为 EXCHANGE 的售后单上 ----------------
CREATE FUNCTION guard_exchange_authorization_case() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_type varchar(24);
  t_status varchar(20);
  t_order uuid;
  t_no varchar(32);
BEGIN
  SELECT case_type, status, order_id, case_no INTO t_type, t_status, t_order, t_no
    FROM aftersales_case
   WHERE tenant_id = NEW.tenant_id AND id = NEW.case_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '售后单不存在或不属于当前租户：case_id=%', NEW.case_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_type <> 'EXCHANGE' THEN
    RAISE EXCEPTION '只有换货类售后单能换货授权，本单类型是 %', t_type
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_status IN ('RESOLVED','REJECTED','CLOSED') THEN
    RAISE EXCEPTION '售后单已结案（%），不能再授权换货', t_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 原单号必须真的是这张售后单挂的那张订单，否则「保留原单追溯关系」就成了可随便填的字段。
  IF NEW.order_id <> t_order THEN
    RAISE EXCEPTION '原订单与售后单不一致：case 挂的是 %，这里写的是 %', t_order, NEW.order_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 单号快照以售后单上的为准：调用方传来的 case_no 不可信。
  NEW.case_no := t_no;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_exchange_authorization_case
  BEFORE INSERT OR UPDATE OF case_id, order_id, tenant_id
  ON exchange_authorization
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_authorization_case();

-- ---- ② 授权行必须挂在真实授权单 + 真实售后商品行上，且原数量不得虚报 -----------
CREATE FUNCTION guard_exchange_authorization_line() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_case uuid;
  t_status varchar(16);
  t_qty numeric(19,6);
  t_order_line uuid;
BEGIN
  SELECT case_id, status INTO t_case, t_status
    FROM exchange_authorization
   WHERE tenant_id = NEW.tenant_id AND id = NEW.authorization_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '换货授权单不存在或不属于当前租户：authorization_id=%', NEW.authorization_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 已撤销的单不再挂新行：撤销的语义是「这笔换货结束了」，
  -- 之后还往上加行等于在一条已终止的链上继续发货。
  IF t_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '换货授权单已撤销（%），不能再加明细', t_status
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT case_id, quantity, order_line_id INTO t_case, t_qty, t_order_line
    FROM aftersales_case_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.case_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '售后商品行不存在或不属于当前租户：case_line_id=%', NEW.case_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 授权行必须属于同一张售后单：跨单换货会让「这张单换了几件」有两个答案。
  IF t_case <> (SELECT case_id FROM exchange_authorization
                  WHERE tenant_id = NEW.tenant_id AND id = NEW.authorization_id) THEN
    RAISE EXCEPTION '售后商品行不属于这张换货授权对应的售后单'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 申请量不得虚报超过售后单上登记的涉及数量。
  IF NEW.requested_quantity > t_qty THEN
    RAISE EXCEPTION '本行申请换 % 超过售后单登记的涉及数量 %', NEW.requested_quantity, t_qty
      USING ERRCODE = 'check_violation';
  END IF;

  -- 原商品与原数量以售后行指向的订单行为准（sku 事实取自 sales_order_line）。
  NEW.original_quantity := t_qty;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_exchange_authorization_line
  BEFORE INSERT OR UPDATE OF tenant_id, authorization_id, case_line_id, requested_quantity
  ON exchange_authorization_line
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_authorization_line();

-- ---- ③ 预占记录：必须挂在真实授权行上，且预占量不得超过该行授权量 ------------
CREATE FUNCTION guard_exchange_reservation() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_auth uuid;
  t_qty numeric(19,6);
BEGIN
  SELECT authorization_id, authorized_quantity INTO t_auth, t_qty
    FROM exchange_authorization_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.authorization_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '换货授权行不存在或不属于当前租户：line_id=%', NEW.authorization_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_auth <> NEW.authorization_id THEN
    RAISE EXCEPTION '预占记录与授权行不属于同一张换货授权单'
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.quantity > t_qty THEN
    RAISE EXCEPTION '本行预占 % 超过授权数量 %', NEW.quantity, t_qty
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER trg_exchange_reservation
  BEFORE INSERT OR UPDATE ON exchange_reservation
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_reservation();

-- ---- ④ 新发货需求与回寄要求同样必须挂在真实授权行上 --------------------------
CREATE FUNCTION guard_exchange_line_child() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_auth uuid;
  t_qty numeric(19,6);
BEGIN
  SELECT authorization_id, authorized_quantity INTO t_auth, t_qty
    FROM exchange_authorization_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.authorization_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '换货授权行不存在或不属于当前租户：line_id=%', NEW.authorization_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_auth <> NEW.authorization_id THEN
    RAISE EXCEPTION '明细与授权行不属于同一张换货授权单'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 需求量不得超过授权量：需求大于授权就等于凭空多要一箱货。
  IF NEW.quantity > t_qty THEN
    RAISE EXCEPTION '明细数量 % 超过授权数量 %', NEW.quantity, t_qty
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER trg_exchange_outbound_demand
  BEFORE INSERT OR UPDATE ON exchange_outbound_demand
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_line_child();

CREATE TRIGGER trg_exchange_return_requirement
  BEFORE INSERT OR UPDATE ON exchange_return_requirement
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_line_child();

-- ===========================================================================
-- 七、迟到约束触发器：加粗句「预占替换货 + 等待旧货回寄」缺一不可
-- ===========================================================================
--
-- 用 CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED 而不是 BEFORE 触发器：
-- 授权是「先建头 → 再逐行建预占 → 再建发货需求与回寄要求」的多步写，
-- BEFORE 触发器跑在建行那一刻，那时预占记录还不存在。
-- 推迟到 COMMIT 才检查，才能表达「这一笔授权在提交时必须已经完整」。

-- ---- ⑤ 每一行：恰好一笔预占 + 恰好一条回寄要求，且预占量 == 授权量 ----------
CREATE FUNCTION guard_exchange_line_reserved() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_reserved numeric(19,6);
  t_authorized numeric(19,6);
  t_res_count bigint;
  t_res_sum numeric(19,6);
  t_ret_count bigint;
  t_auth_status varchar(16);
BEGIN
  SELECT reserved_quantity, authorized_quantity INTO t_reserved, t_authorized
    FROM exchange_authorization_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.id;

  -- 只在仍然有效的授权行上要求完整：已撤销的单（头与预占都置终态）不再受这条约束。
  SELECT status INTO t_auth_status
    FROM exchange_authorization
   WHERE tenant_id = NEW.tenant_id AND id = NEW.authorization_id;

  IF t_auth_status IS NULL OR t_auth_status <> 'ACTIVE' THEN
    RETURN NULL;
  END IF;

  SELECT count(*), coalesce(sum(quantity),0) INTO t_res_count, t_res_sum
    FROM exchange_reservation
   WHERE tenant_id = NEW.tenant_id AND authorization_line_id = NEW.id;

  IF t_res_count <> 1 THEN
    RAISE EXCEPTION '换货授权行 % 必须恰好有一笔替换货预占，实际 % 笔 —— 没有预占就发替换货就是只发不收',
      NEW.id, t_res_count USING ERRCODE = 'check_violation';
  END IF;

  IF t_res_sum <> t_authorized THEN
    RAISE EXCEPTION '换货授权行 % 的预占量 % 与授权量 % 不一致', NEW.id, t_res_sum, t_authorized
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_reserved <> t_authorized THEN
    RAISE EXCEPTION '换货授权行 % 记的已预占量 % 与真实预占量 % 不一致', NEW.id, t_reserved, t_res_sum
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT count(*) INTO t_ret_count
    FROM exchange_return_requirement
   WHERE tenant_id = NEW.tenant_id AND authorization_line_id = NEW.id;

  IF t_ret_count <> 1 THEN
    RAISE EXCEPTION '换货授权行 % 必须恰好有一条等待旧货回寄的要求，实际 % 条 —— 发了替换货却不收旧货就是白送',
      NEW.id, t_ret_count USING ERRCODE = 'check_violation';
  END IF;

  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_exchange_line_reserved
  AFTER INSERT OR UPDATE ON exchange_authorization_line
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_line_reserved();

-- ---- ⑥ 头：至少一行，且各行数量之和与头上登记的数量严格相等 -----------------
CREATE FUNCTION guard_exchange_authorization_complete() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_lines bigint;
  t_req numeric(19,6);
  t_auth numeric(19,6);
  t_skus bigint;
BEGIN
  -- 只有仍然成立的授权单受完整约束；撤销后（ACTIVE → CANCELLED）不再要求。
  IF NEW.status <> 'ACTIVE' THEN
    RETURN NULL;
  END IF;

  SELECT count(*), coalesce(sum(requested_quantity),0), coalesce(sum(authorized_quantity),0),
         count(DISTINCT replacement_sku_id)
    INTO t_lines, t_req, t_auth, t_skus
    FROM exchange_authorization_line
   WHERE tenant_id = NEW.tenant_id AND authorization_id = NEW.id;

  IF t_lines = 0 THEN
    RAISE EXCEPTION '换货授权单 % 至少要有一行明细', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_req <> NEW.requested_quantity THEN
    RAISE EXCEPTION '换货授权单 % 登记的申请数量 % 与各行之和 % 不一致', NEW.id, NEW.requested_quantity, t_req
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_auth <> NEW.authorized_quantity THEN
    RAISE EXCEPTION '换货授权单 % 登记的授权数量 % 与各行之和 % 不一致', NEW.id, NEW.authorized_quantity, t_auth
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_skus <> NEW.replacement_sku_count THEN
    RAISE EXCEPTION '换货授权单 % 登记的替换 SKU 数 % 与实际 % 不一致', NEW.id, NEW.replacement_sku_count, t_skus
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_exchange_authorization_complete
  AFTER INSERT OR UPDATE ON exchange_authorization
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_authorization_complete();

-- ===========================================================================
-- 八、多租户：ENABLE + FORCE RLS + GRANT
-- ===========================================================================
--
-- 刻意排在建表与触发器之后：ALTER TABLE ... ENABLE ROW LEVEL SECURITY 与 CREATE POLICY
-- 都要求目标表已存在，写在建表之前 Flyway 会以「relation does not exist」失败。

DO $$
DECLARE
  tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'exchange_authorization','exchange_authorization_line','exchange_reservation',
    'exchange_outbound_demand','exchange_return_requirement'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  exchange_authorization,exchange_authorization_line,exchange_reservation,
  exchange_outbound_demand,exchange_return_requirement TO trade_app;

-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查），**不授 UPDATE** ——
-- 运行角色不该能把计数器往回拨（拨回去就会与已有授权号撞 UNIQUE）。
GRANT USAGE,SELECT ON SEQUENCE exchange_authorization_no_seq TO trade_app;

-- ---- 授权头：只允许状态流转相关的列 ----
-- 刻意不授回 case_id / order_id / requested_quantity / authorized_quantity /
-- replacement_sku_count / authorized_by / authorized_at：
-- 改得了授权数量 = 让「已经圈走的替换货」事后被改成另一个数（那正是重复换货）；
-- 改得了 case_id = 让换货追溯指向另一张售后单（V159 退款模块同一条纪律）。
REVOKE UPDATE ON exchange_authorization FROM trade_app;
GRANT UPDATE(status,decision,version,cancelled_by,cancelled_at,cancel_reason,reason_note,updated_at)
  ON exchange_authorization TO trade_app;

-- ---- 明细行：写完即冻结 ----
-- 一行授权明细在提交时必须已经「预占足额 + 挂着回寄要求」，此后任何改动
-- 都会让当时的预占与现在的记录对不上（V156 留痕表同一纪律）。
REVOKE UPDATE,DELETE ON exchange_authorization_line FROM trade_app;

-- ---- 预占记录：只允许「已释放」这一个动作 ----
REVOKE UPDATE ON exchange_reservation FROM trade_app;
GRANT UPDATE(status,released_at,release_reason,updated_at) ON exchange_reservation TO trade_app;

-- ---- 新发货需求：只允许取消 ----
REVOKE UPDATE ON exchange_outbound_demand FROM trade_app;
GRANT UPDATE(status,cancel_reason,updated_at) ON exchange_outbound_demand TO trade_app;

-- ---- 回寄要求：只允许免回（撤销换货时 WAIVED）----
REVOKE UPDATE ON exchange_return_requirement FROM trade_app;
GRANT UPDATE(status,waived_by,waived_at,note,updated_at) ON exchange_return_requirement TO trade_app;
