-- ===========================================================================
-- 11.3.2.2 换货新单
-- ===========================================================================
--
-- 蓝图原文（11-aftersales-and-returns.md 11.3.2.2）：
--   职责：建立换货关联（原售后 ↔ 新销售单/发货单），跟踪换货闭环状态，防止重复换货。
--   输入：换货授权；新单创建请求。
--   输出：换货关联记录（exchange_order_link）+ 新单状态跟踪。
--   关系：上游 = 11.3.2.1；下游 = 9 订单、8 出库。
--   **换货新单不修改原出库事实，原单保持完整可追溯。**
--
-- ===========================================================================
-- 一、三张表的分工
-- ===========================================================================
--
--   exchange_order_link         换货关联（头）：原售后 ↔ 换货授权 ↔ 新销售单 + 闭环状态
--   exchange_order_link_line    逐行：哪条授权行 → 新单的哪一行、换几件
--   exchange_order_link_event   闭环事件流（append-only 时间线）
--
-- 「原单保持完整可追溯」落在头部的 original_order_id / original_order_no 与
-- 行上的 original_sku_id / original_sku_code：**换货只新建单，绝不回写原单**。
-- 本迁移对 sales_order 的写入路径只有 INSERT（新建换货新单）与一次
-- DRAFT → SUBMITTED 的状态推进，两条都不碰原单 —— 集成测试逐字节断言原单未变。
--
-- ===========================================================================
-- 二、新销售单必须是**真单**：这是本模块与「假交付」的分界线
-- ===========================================================================
--
-- 蓝图输出句点名的是「新单状态跟踪」。最容易做成假的写法是：
-- 在自己库里记一行 new_order_no = 'EX-2026-0001'、new_order_status = 'SHIPPED'，
-- 界面上「换货已发货」—— 而 sales_order 里根本没有这张单，仓库也没收到任何需求。
-- 那不是新单，那是把「打算发一单」记成「已经发了」。
--
-- 所以本模块**真的在 sales_order 建单**（trade 自己拥有这张表，V99），
-- 并真的写 sales_order_line / order_address_snapshot / sales_order_party_snapshot ——
-- V99 的延迟约束触发器（提交时复核「行 ≥ 1 + 有收货地址 + 有客户 + 汇总相等」）
-- 会在 COMMIT 时把这四样一起验收，缺一样提交就失败。
-- 快照三件套**照抄原订单**：换货发给同一个买家，收货地址与客户就是原单那套快照
-- （V99 硬要求 4：地址是快照不是引用活地址，事后买家改地址改不动历史单）。
--
-- 新单金额一律 0.0000（charge_mode = 'NO_CHARGE'）：换货是把已付过款的商品换一件，
-- 对买家二次收费就是重复收款。V99 也明确「不内置任何默认单价」，单价由人按成交价填，
-- 而换货新单这里根本不发生一笔新交易。
--
-- ===========================================================================
-- 三、闭环状态只承认**查得到的事实**，不承认「应该已经」的状态
-- ===========================================================================
--
-- 职责句要「跟踪换货闭环状态」。闭环要真判，就只能问真实表：
--
--   CREATED     新单已建立、尚未提交履约。（本服务写入即确立）
--   DISPATCHED  替换货已发出。判据是**读** shipment_record（9.4.1.1，V127）里
--               sales_order_id 指向这张新单的真实发货记录 —— 发货事实归 8 出库，
--               本服务只读不写；读不到就停在 CREATED，**绝不**凭空置已发货。
--   CLOSED      换货闭环：已发出 **且** 旧货回寄要求已结清。
--   CANCELLED   换货新单被撤销。
--
-- ⚠️ **CLOSED 现在只能由「已发出 + 旧货免回」达成**，这是刻意如实，不是漏做：
--   旧货的**实收**归 11.2 退回旧货，而实查 V161 的 rma_authorization 词表
--   （DRAFT / AUTHORIZED / PARTIALLY_AUTHORIZED / REJECTED / EXPIRED / CANCELLED）
--   **没有 RECEIVED** —— 实收与质检状态机尚未交付。
--   本模块若自造一个 RETURNED 去凑闭环，就会与将来的实收状态机打架，
--   与 11.3.2.1 当初不落 RETURNED 是同一条理由。
--   capabilities 里 oldGoodsReturnReceivedDelivered = false 如实报出这一点，
--   页面上「已闭环」旁标注它当前只可能来自经批准的免回。
--
-- ===========================================================================
-- 四、防重复换货（职责句第三项）
-- ===========================================================================
--
--   UNIQUE(tenant_id, authorization_id)      一张换货授权只能有一张换货新单
--   UNIQUE(tenant_id, authorization_line_id) 一条授权行只能进一张换货新单
--   UNIQUE(tenant_id, new_order_id)          一张新单只能被一条关联认领
--
-- 三条都在 DB 层，且是**并发下真正起作用**的那道防线：11.3.2.1 的授权入口
-- 已经先锁过 aftersales_case，但那是「同一张售后单并发授权两次」；
-- 这里防的是「同一张授权单并发建两张换货新单」，两次请求会各建一张
-- **真的** sales_order（各拿到一个真单号），所以唯一约束必须落在
-- exchange_order_link 上。服务层因此**先锁 exchange_authorization 再建单**，
-- 顺序反了第二次请求就会先在 sales_order 里留下一张没人认领的新单。
--
-- ===========================================================================
-- 五、多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT（与 V159 / V162 同一条）
-- ===========================================================================
--
-- 三张表同一套 RLS 表达式：租户由 backend/libraries/tenancy 在事务内
-- set_config('app.current_tenant') 写入，客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 本表含替换 SKU、原单号、**新单单号**与仓库侧发货单号：漏掉租户谓词
-- 就是跨租户泄露 —— A 家会看到 B 家「哪些买家在换货、换成了什么、发到哪个运单」。
--
-- ===========================================================================
-- 六、唯一一处 ALTER 既有表：manual_kind 加值 'EXCHANGE'（R6 加值纪律）
-- ===========================================================================
--
-- 换货新单要写进 sales_order，而 V99 的两条约束合起来逼出一个岔路：
--   sales_order_manual_kind_check        manual_kind IN ('','OFFLINE_STORE','B2B','DROPSHIP','SAMPLE')
--   sales_order_manual_kind_matches_source  (order_source = 'MANUAL') = (manual_kind <> '')
-- 也就是说手工单**必须**从那四个子类里挑一个。四个里没有任何一个表示「换货新单」：
-- 塞 SAMPLE 会让订单列表把这张换货单显示成「样机单」，塞 OFFLINE_STORE 会显示成
-- 「独立站线下」—— 那是往事实表里写一个假的业务分类，比不建单更糟。
--
-- 所以按 V99 文件头自己写的规矩加值：「词表刻意已经包含将来的平台来源……
-- 而『加值』这条纪律（R6，V30 第七节）在这里同样适用」。
-- 这是**加值**不是改语义：既有四类含义一个字没动。
--
-- ⚠️ 写法刻意做成幂等（DROP IF EXISTS → ADD），这样本迁移重跑、与其他车道
-- 各自的加值迁移先后执行都不会互相踩坏。
-- ⚠️ 遗留缺口（如实报出，不假装不存在）：9.3.2.2 的 SalesOrderManualKind 枚举
-- 里还没有 EXCHANGE，所以手工单列表按子类筛选时这张换货单不会被筛出来
-- （不筛选时正常出现）。补这个枚举属于 9.3.2.2 车道，本模块不越界改它的 Java。

-- ===========================================================================
-- 0. manual_kind 加值（详见第六节）
-- ===========================================================================

ALTER TABLE sales_order DROP CONSTRAINT IF EXISTS sales_order_manual_kind_matches_source;
ALTER TABLE sales_order DROP CONSTRAINT IF EXISTS sales_order_manual_kind_check;
ALTER TABLE sales_order ADD CONSTRAINT sales_order_manual_kind_check
  CHECK (manual_kind IN ('','OFFLINE_STORE','B2B','DROPSHIP','SAMPLE','EXCHANGE'));
ALTER TABLE sales_order ADD CONSTRAINT sales_order_manual_kind_matches_source
  CHECK ((order_source = 'MANUAL') = (manual_kind <> ''));

-- ===========================================================================
-- 1. exchange_order_link · 换货关联（头）
-- ===========================================================================

CREATE SEQUENCE exchange_order_link_no_seq;

CREATE TABLE exchange_order_link (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 换货新单号：EXL-YYYYMMDD-六位，由本序列生成（不采信调用方传入的单号，理由同 V159）。
  link_no varchar(32) NOT NULL,

  -- ---- 原售后追溯（职责句「原售后 ↔ 新销售单」的左半边）----
  -- 上游 11.3.2.1（V162 已交付）：这两个 id 是本模块的存在理由。
  authorization_id uuid NOT NULL,
  authorization_no varchar(32) NOT NULL DEFAULT '',
  case_id uuid NOT NULL,
  case_no varchar(32) NOT NULL DEFAULT '',

  -- ---- 原销售订单（**只读引用 + 快照，绝不回写**）----
  original_order_id uuid NOT NULL,
  original_order_no varchar(64) NOT NULL DEFAULT '',

  -- ---- 新销售单（真单，见第二节）----
  new_order_id uuid NOT NULL,
  new_order_no varchar(64) NOT NULL DEFAULT '',
  -- 镜像新单的单据状态（DRAFT / SUBMITTED），对齐 sales_order.status 的 CHECK。
  -- 刻意只镜像这两个值：sales_order 将来加值时这里是「少一个值」而不是「写进非法值」。
  new_order_status varchar(12) NOT NULL DEFAULT 'DRAFT'
    CHECK(new_order_status IN ('DRAFT','SUBMITTED')),

  -- ---- 闭环状态（第三节）----
  closure_status varchar(16) NOT NULL
    CHECK(closure_status IN ('CREATED','DISPATCHED','CLOSED','CANCELLED')),

  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  total_quantity numeric(19,6) NOT NULL CHECK(total_quantity > 0),
  replacement_sku_count integer NOT NULL CHECK(replacement_sku_count > 0),
  line_count integer NOT NULL CHECK(line_count > 0),

  -- 换货不二次收费：金额恒为 0，写死成 CHECK 而不是留给服务层自觉。
  charge_mode varchar(16) NOT NULL DEFAULT 'NO_CHARGE' CHECK(charge_mode = 'NO_CHARGE'),
  new_order_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(new_order_amount = 0),

  -- ---- 发货快照：读 9.4.1.1 shipment_record 的真实记录，本服务只读不写 ----
  shipment_count integer NOT NULL DEFAULT 0 CHECK(shipment_count >= 0),
  first_shipped_at timestamptz,
  shipment_record_no varchar(32) NOT NULL DEFAULT '',
  -- 最近一次观测到的轨迹状态；UNSET = 从来没有观测过，刻意不与「观测过但无轨迹」合并。
  last_tracking_status varchar(20) NOT NULL DEFAULT 'UNSET'
    CHECK(last_tracking_status IN ('UNSET','NO_TRACE','PICKED_UP','IN_TRANSIT','CUSTOMS',
        'OUT_FOR_DELIVERY','DELIVERED','EXCEPTION','RETURNED')),

  -- ---- 旧货回寄（读 11.3.2.1 exchange_return_requirement）----
  -- WAITING：仍在等买家寄回；WAIVED：经批准免回。刻意没有 RETURNED，理由见第三节。
  old_goods_return_status varchar(16) NOT NULL DEFAULT 'WAITING'
    CHECK(old_goods_return_status IN ('WAITING','WAIVED')),

  -- 乐观锁：撤销 / 推进闭环都要带 expectedVersion，两个人同时点后到的那个必须被 409 拒掉。
  version integer NOT NULL DEFAULT 1 CHECK(version > 0),

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 1000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  submitted_at timestamptz,
  dispatched_at timestamptz,
  closed_at timestamptz,
  cancelled_by uuid,
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 1000),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,link_no),

  -- 防重复换货三道（第四节）
  UNIQUE(tenant_id,authorization_id),
  UNIQUE(tenant_id,new_order_id),

  -- 痕迹与状态严格等价：只改状态不落时刻的话，「谁撤的、什么时候发的」永远查不到。
  CHECK((closure_status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
  CHECK((closure_status = 'CANCELLED') = (cancelled_by IS NOT NULL)),
  -- 已发出 / 已闭环必带发货时刻；未发出不许凭空带一个时刻。
  CHECK((closure_status IN ('DISPATCHED','CLOSED')) = (dispatched_at IS NOT NULL)),
  CHECK(closure_status <> 'CLOSED' OR closed_at IS NOT NULL),
  CHECK(closure_status <> 'CLOSED' OR closed_at >= dispatched_at),
  -- 新单状态与提交时刻同进同出。
  CHECK((new_order_status = 'SUBMITTED') = (submitted_at IS NOT NULL)),
  -- 闭环必须真的发出过：CLOSED 而没有发货记录数 = 凭空闭环。
  CHECK(closure_status NOT IN ('DISPATCHED','CLOSED') OR shipment_count > 0),
  -- 闭环必须真的已发出：新单还没提交就不可能已发货。
  CHECK(closure_status NOT IN ('DISPATCHED','CLOSED') OR new_order_status = 'SUBMITTED')
);

CREATE INDEX exchange_order_link_list
  ON exchange_order_link(tenant_id,closure_status,created_at DESC,id);
CREATE INDEX exchange_order_link_case
  ON exchange_order_link(tenant_id,case_id,created_at DESC);
CREATE INDEX exchange_order_link_new_order
  ON exchange_order_link(tenant_id,new_order_id);
-- 「哪些换货还没发出」是履约视角最高频的一问，单独建部分索引。
CREATE INDEX exchange_order_link_pending_dispatch
  ON exchange_order_link(tenant_id,created_at DESC)
  WHERE closure_status = 'CREATED';

-- ===========================================================================
-- 2. exchange_order_link_line · 逐行（哪条授权行 → 新单哪一行）
-- ===========================================================================

CREATE TABLE exchange_order_link_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  link_id uuid NOT NULL,
  -- 被换的那条授权行（exchange_authorization_line，V162）。本模块不新增换货语义，
  -- 只把已经授权过的量搬进新单。
  authorization_line_id uuid NOT NULL,

  -- 新单上真实存在的那一行（sales_order_line，V99）。
  new_order_line_id uuid NOT NULL,
  new_order_line_no integer NOT NULL CHECK(new_order_line_no > 0),

  -- ---- 替换商品快照（11.3.2.1 已经过 catalog 在售校验，这里沿用它的快照）----
  -- 刻意不回查 catalog：V99 硬要求 3「不回查实时 SPU 改写历史单据」。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 原商品（追溯用：这件新货是替哪一件换的）----
  original_sku_id uuid NOT NULL,
  original_sku_code varchar(64) NOT NULL DEFAULT '',

  -- 数量必须恒等于该授权行的授权量，由 guard_exchange_order_link_line 钉死。
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,link_id,authorization_line_id),
  -- 一条授权行只能进一张换货新单（防重复换货）。
  UNIQUE(tenant_id,authorization_line_id),
  -- 新单的同一行不能被两条关联认领。
  UNIQUE(tenant_id,new_order_line_id),

  -- 换货成同一个 SKU 等于没换（与 V162 exchange_authorization_line 同一条纪律）。
  CHECK(sku_id <> original_sku_id)
);

CREATE INDEX exchange_order_link_line_link
  ON exchange_order_link_line(tenant_id,link_id,id);
CREATE INDEX exchange_order_link_line_order
  ON exchange_order_link_line(tenant_id,new_order_line_id);

-- ===========================================================================
-- 3. exchange_order_link_event · 闭环事件流（append-only 时间线）
-- ===========================================================================
--
-- 「现在是什么状态」与「什么时候变成这样的、谁改的」必须都能回答。
-- append-only 意味着已发生的事实不可改写：REVOKE 之外再加触发器，
-- 绕过服务层直连 SQL 也改不动（与 V99 状态事件流同一条纪律）。
CREATE TABLE exchange_order_link_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  link_id uuid NOT NULL,

  event_kind varchar(40) NOT NULL CHECK(btrim(event_kind) <> ''),
  from_closure_status varchar(16) NOT NULL DEFAULT '',
  to_closure_status varchar(16) NOT NULL DEFAULT '',
  detail varchar(800) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,link_id) REFERENCES exchange_order_link(tenant_id,id)
    ON DELETE CASCADE
);

CREATE INDEX exchange_order_link_event_timeline
  ON exchange_order_link_event(tenant_id,link_id,occurred_at,id);

CREATE FUNCTION guard_exchange_order_link_event_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：换货闭环的每一次变化都必须能原样复现',
    TG_TABLE_NAME, TG_OP USING ERRCODE = 'check_violation';
END $$;

CREATE TRIGGER exchange_order_link_event_append_only
  BEFORE UPDATE OR DELETE ON exchange_order_link_event
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_order_link_event_append_only();

-- ===========================================================================
-- 四、护栏触发器（与 V156 / V157 / V162 同一套写法）
-- ===========================================================================

-- ---- ① 关联头必须挂在真实、生效中的换货授权上，单号一律以授权单为准 -------------
CREATE FUNCTION guard_exchange_order_link_authorization() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(16);
  t_auth_no varchar(32);
  t_case uuid;
  t_case_no varchar(32);
  t_order uuid;
  t_order_no varchar(64);
  t_new_no varchar(64);
  t_new_status varchar(12);
BEGIN
  SELECT status, authorization_no, case_id, case_no, order_id
    INTO t_status, t_auth_no, t_case, t_case_no, t_order
    FROM exchange_authorization
   WHERE tenant_id = NEW.tenant_id AND id = NEW.authorization_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '换货授权单不存在或不属于当前租户：authorization_id=%', NEW.authorization_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 已撤销的授权不能换出新单：撤销的语义是「这笔换货结束了」。
  IF t_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '换货授权单已撤销（%），不能再建换货新单', t_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 原单必须与授权单一致，否则「原售后 ↔ 新销售单」这条追溯链可以被指到另一张单。
  IF NEW.original_order_id <> t_order THEN
    RAISE EXCEPTION '原订单与换货授权不一致：授权挂的是 %，这里写的是 %', t_order, NEW.original_order_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 新单必须真实存在于本租户，且建关联时还是草稿。
  SELECT order_no, status INTO t_new_no, t_new_status
    FROM sales_order
   WHERE tenant_id = NEW.tenant_id AND id = NEW.new_order_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '换货新单不存在或不属于当前租户：new_order_id=% —— 没有真单就没有换货新单',
      NEW.new_order_id USING ERRCODE = 'no_data_found';
  END IF;

  IF t_new_status <> 'DRAFT' THEN
    RAISE EXCEPTION '换货新单 % 已经不是草稿态（%），不能建立换货关联', t_new_no, t_new_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 单号快照一律以真实记录为准：调用方传来的 case_no / order_no / new_order_no 不可信。
  SELECT order_no INTO t_order_no
    FROM sales_order
   WHERE tenant_id = NEW.tenant_id AND id = t_order;

  NEW.authorization_no := t_auth_no;
  NEW.case_id := t_case;
  NEW.case_no := t_case_no;
  NEW.original_order_no := coalesce(t_order_no, '');
  NEW.new_order_no := t_new_no;
  NEW.new_order_status := 'DRAFT';
  -- 闭环起点由 DB 定死：任何一条关联诞生时都只能是「已建立」。
  NEW.closure_status := 'CREATED';
  NEW.old_goods_return_status := 'WAITING';
  RETURN NEW;
END $$;

CREATE TRIGGER trg_exchange_order_link_authorization
  BEFORE INSERT OR UPDATE OF authorization_id, original_order_id, new_order_id, tenant_id
  ON exchange_order_link
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_order_link_authorization();

-- ---- ② 逐行必须同时挂在真授权行与真新单行上，且数量恒等于该行授权量 -------------
CREATE FUNCTION guard_exchange_order_link_line() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_auth uuid;
  t_sku uuid;
  t_authorized numeric(19,6);
  t_line_auth uuid;
  t_line_order uuid;
  t_line_sku uuid;
  t_line_qty numeric(19,6);
  t_line_no int;
BEGIN
  SELECT authorization_id, replacement_sku_id, authorized_quantity
    INTO t_auth, t_sku, t_authorized
    FROM exchange_authorization_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.authorization_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '换货授权行不存在或不属于当前租户：authorization_line_id=%', NEW.authorization_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_auth <> (SELECT authorization_id FROM exchange_order_link
                 WHERE tenant_id = NEW.tenant_id AND id = NEW.link_id) THEN
    RAISE EXCEPTION '明细与授权行不属于同一张换货授权单'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 新单行必须真的是这张新单的行，且 SKU 与授权的替换品一致。
  SELECT order_id, sku_id, quantity, line_no
    INTO t_line_order, t_line_sku, t_line_qty, t_line_no
    FROM sales_order_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.new_order_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '换货新单的商品行不存在或不属于当前租户：new_order_line_id=%', NEW.new_order_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_line_order <> (SELECT new_order_id FROM exchange_order_link
        WHERE tenant_id = NEW.tenant_id AND id = NEW.link_id) THEN
    RAISE EXCEPTION '明细指向的商品行不属于这张换货新单'
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_line_sku <> t_sku THEN
    RAISE EXCEPTION '新单行的商品与授权的替换品不一致：授权换的是 %，新单建的是 %',
      t_sku, t_line_sku USING ERRCODE = 'check_violation';
  END IF;

  -- 数量守恒：新单行的量必须与授权量一致，少发是只发不收的一部分，多发是凭空多发货。
  IF t_line_qty <> t_authorized THEN
    RAISE EXCEPTION '新单行数量 % 与授权量 % 不一致', t_line_qty, t_authorized
      USING ERRCODE = 'check_violation';
  END IF;

  NEW.quantity := t_authorized;
  NEW.new_order_line_no := t_line_no;
  NEW.sku_id := t_sku;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_exchange_order_link_line
  BEFORE INSERT OR UPDATE OF tenant_id, link_id, authorization_line_id, new_order_line_id, quantity
  ON exchange_order_link_line
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_order_link_line();

-- ---- ③ 闭环推进只承认真实发货记录：已发出必须真的有 shipment_record ----------
--
-- 这一条是本模块的**核心防线**：绕过服务层直接 UPDATE 状态成 DISPATCHED，
-- 只要没有真实发货记录，提交时就被数据库拒掉。
CREATE FUNCTION guard_exchange_order_link_dispatch() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_shipments bigint;
  t_new_status varchar(12);
BEGIN
  IF NEW.closure_status NOT IN ('DISPATCHED','CLOSED') THEN
    RETURN NEW;
  END IF;

  SELECT count(*) INTO t_shipments
    FROM shipment_record
   WHERE tenant_id = NEW.tenant_id
     AND sales_order_id = NEW.new_order_id
     AND status <> 'VOID';

  IF t_shipments = 0 THEN
    RAISE EXCEPTION '换货新单 % 没有任何真实发货记录，不能标记为已发出 —— 发货事实归 8 出库，本服务只读不编',
      NEW.new_order_no USING ERRCODE = 'check_violation';
  END IF;

  -- 已发出必然先提交：新单还是草稿时不可能有发货记录，出现这种组合说明数据被写坏了。
  SELECT status INTO t_new_status
    FROM sales_order
   WHERE tenant_id = NEW.tenant_id AND id = NEW.new_order_id;

  IF t_new_status IS DISTINCT FROM 'SUBMITTED' THEN
    RAISE EXCEPTION '换货新单 % 尚未提交进审单队列（当前 %），不可能已发出',
      NEW.new_order_no, t_new_status USING ERRCODE = 'check_violation';
  END IF;

  NEW.shipment_count := t_shipments;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_exchange_order_link_dispatch
  BEFORE UPDATE OF closure_status ON exchange_order_link
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_order_link_dispatch();

-- ---- ④ 闭环必须真的结清旧货回寄要求：只发不收的最后一道 ------------------------
CREATE FUNCTION guard_exchange_order_link_closure() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_waiting bigint;
BEGIN
  IF NEW.closure_status <> 'CLOSED' THEN
    RETURN NEW;
  END IF;

  -- 换货闭环的前提是「发的收到了」：只要还有一行在等旧货回寄，就不算闭环。
  SELECT count(*) INTO t_waiting
    FROM exchange_return_requirement x
    JOIN exchange_order_link l ON l.tenant_id = x.tenant_id
   WHERE l.id = NEW.id
     AND l.authorization_id = (SELECT authorization_id FROM exchange_order_link
                                 WHERE tenant_id = NEW.tenant_id AND id = NEW.id)
     AND x.status = 'WAITING';

  IF t_waiting > 0 THEN
    RAISE EXCEPTION '还有 % 行旧货未回寄（或未获准免回），不能标记为换货已闭环 —— 只发不收就是白送',
      t_waiting USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER trg_exchange_order_link_closure
  BEFORE UPDATE OF closure_status ON exchange_order_link
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_order_link_closure();

-- ===========================================================================
-- 五、迟到约束触发器：这一笔换货新单在提交时必须已经完整
-- ===========================================================================
--
-- 建关联是「先建头 → 再逐行建明细」的多步写，BEFORE 触发器跑在建行那一刻
-- 什么也看不到，所以完整性复核必须推迟到 COMMIT（与 V162 第七节同一理由）。

CREATE FUNCTION guard_exchange_order_link_complete() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_lines bigint;
  t_sum numeric(19,6);
  t_skus bigint;
BEGIN
  -- 只有仍然成立的关联受完整约束；撤销后不再要求。
  IF NEW.closure_status = 'CANCELLED' THEN
    RETURN NULL;
  END IF;

  SELECT count(*), coalesce(sum(quantity),0), count(DISTINCT sku_id)
    INTO t_lines, t_sum, t_skus
    FROM exchange_order_link_line
   WHERE tenant_id = NEW.tenant_id AND link_id = NEW.id;

  IF t_lines = 0 THEN
    RAISE EXCEPTION '换货关联 % 至少要有一行明细', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_lines <> NEW.line_count THEN
    RAISE EXCEPTION '换货关联 % 登记的行数 % 与实际 % 不一致', NEW.id, NEW.line_count, t_lines
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_sum <> NEW.total_quantity THEN
    RAISE EXCEPTION '换货关联 % 登记的换货总数量 % 与各行之和 % 不一致',
      NEW.id, NEW.total_quantity, t_sum USING ERRCODE = 'check_violation';
  END IF;

  IF t_skus <> NEW.replacement_sku_count THEN
    RAISE EXCEPTION '换货关联 % 登记的替换 SKU 数 % 与实际 % 不一致',
      NEW.id, NEW.replacement_sku_count, t_skus USING ERRCODE = 'check_violation';
  END IF;

  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_exchange_order_link_complete
  AFTER INSERT OR UPDATE ON exchange_order_link
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_exchange_order_link_complete();

-- ===========================================================================
-- 六、多租户：ENABLE + FORCE RLS + GRANT
-- ===========================================================================
--
-- 刻意排在建表与触发器之后：ALTER TABLE ... ENABLE ROW LEVEL SECURITY 与
-- CREATE POLICY 都要求目标表已存在，写在建表之前 Flyway 会以
-- 「relation does not exist」失败。

DO $$
DECLARE
  tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'exchange_order_link','exchange_order_link_line','exchange_order_link_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  exchange_order_link,exchange_order_link_line,exchange_order_link_event TO trade_app;

-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查），**不授 UPDATE** ——
-- 运行角色不该能把计数器往回拨（拨回去就会与已有换货新单号撞 UNIQUE）。
GRANT USAGE,SELECT ON SEQUENCE exchange_order_link_no_seq TO trade_app;

-- ---- 关联头：只允许状态流转与真实事实快照相关的列 ----
-- 刻意不授回 authorization_id / case_id / original_order_id / new_order_id /
-- total_quantity / replacement_sku_count / line_count / currency / created_by：
-- 改得了 authorization_id = 换货追溯指向另一张授权单；
-- 改得了 new_order_id = 让「换货发的是哪张单」出现两个答案；
-- 改得了 total_quantity = 换出去的量与授权的量对不上（那正是重复换货）。
REVOKE UPDATE ON exchange_order_link FROM trade_app;
GRANT UPDATE(new_order_status,submitted_at,closure_status,shipment_count,first_shipped_at,
    shipment_record_no,last_tracking_status,old_goods_return_status,dispatched_at,closed_at,
    version,cancelled_by,cancelled_at,cancel_reason,reason_note,updated_at)
  ON exchange_order_link TO trade_app;

-- ---- 逐行：写完即冻结 ----
-- 数量必须恒等于授权量，改它等于让「授权换 1 件、实际发出 3 件」在数据上成立。
REVOKE UPDATE,DELETE ON exchange_order_link_line FROM trade_app;

-- ---- 事件流：append-only，收回改写权 ----
REVOKE UPDATE,DELETE ON exchange_order_link_event FROM trade_app;
