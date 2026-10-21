-- 6.3.1.1 采购单列表（卷 06 采购与供应 / L3 6.3.1 采购单的第一格）。
--
-- 蓝图 06-purchase-and-supply.md 原文：
--   职责：采购单主列表：单号、供应商、SKU 行数、总金额、币种、状态、承诺到货日、
--         已收数量/比例、逾期标记，支持多维筛选与导出。
--   输入：筛选条件（供应商、状态、日期区间、是否逾期、目标仓）；用户数据范围。
--   输出：分页列表 + 批量操作（催单、关闭、导出）。
--   关系：上游 = 6.2.2.1；下游 = 6.3.1.2 详情、6.3.2 跟踪。
--         **已收比例需实时反映 08 的收货结果**，这是采购员判断是否催货的第一指标。
--
-- 归属：**trade 服务**（卷 06 后端主责服务 = trade + catalog（party），本仓不存在 purchase 服务）。
--
-- ===========================================================================
-- 一、这个模块拥有什么、不拥有什么（先讲清楚，避免兄弟模块各造一套单号）
-- ===========================================================================
--
-- ⚠️ **本迁移拥有「采购单」这个聚合本身**（头 + 行 + 留痕三张表）。理由：
--
--   * 6.3.1.2 采购单创建与编辑 / 6.3.1.3 采购单审批 **均未交付** ⇒ 本仓此前
--     **不存在任何采购单实体**（`grep -rn "create table purchase_order" backend/` 无命中）。
--   * 6.2.2.1 采购申请（V246）交付时**刻意不造占位的假采购单表**，只把
--     「转成采购单时的供应商 / 数量 / 交期 / 目标仓」记在 `purchase_request_conversion` 里，
--     并写明「**6.3.1.2 交付后，由它读这份转单事实去建真正的采购单**」。
--   * 6.3.2.2 到货与逾期（V235）交付时同样**刻意不建采购单表**，只用
--     `purchase_order_no`（采购单号）+ `purchase_order_ref_id` 做**逻辑引用**。
--
-- ⇒ **采购单号的产出方是本模块**（6.3.1.1 是 L3 6.3.1 的锚点模块），
--   口径钉死为：`purchase_order.order_no` ↔ 6.3.2.2 的 `purchase_arrival_tracking.purchase_order_no`。
--   6.3.2.2 已交付且**零 ALTER 既有表**，所以本迁移**不去改它的表**，
--   只保证同一张采购单在两边用的是**同一个单号**（两边都按单号串起来）。
--
-- 🔴 **已收数量 / 比例的口径（蓝图加粗句「实时反映 08 的收货结果」）**：
--
--   * 08 仓储的收货单在 **warehouse 服务的独立库**里 ⇒ AGENTS.md 禁止跨服务 SQL / FK / 视图，
--     trade 侧只能经**授权只读端口**读（`WarehouseReceiptQueryPort` 已存在，本模块复用它，
--     不再造第二个）。
--   * 而 **6.3.2.2 已经在 trade 本库落了到货流水**（`purchase_arrival_tracking.received_qty`
--     由 DB 触发器从 `purchase_arrival_receipt` 重算，行里带 warehouse 收货单号快照）。
--   * ⇒ **本模块的「已收数量 / 比例」唯一口径 = 本库 6.3.2.2 的到货跟踪**
--     （`purchase_arrival_tracking`），warehouse 收货单号只作**来源快照**透出。
--     **两个口径不混成一个数**：列表里的已收数就是到货跟踪的已收数，不另算一遍。
--
-- 🔴 **总金额跨币种不相加**：
--   * 一张采购单**只有一个币种**（`purchase_order.currency`），行金额与单币种对齐；
--   * 列表汇总**按币种分组**返回（`sum(total_amount) group by currency`），
--     **绝不**把 USD 与 EUR 加成一个数，也**绝不**按 1:1 或固定汇率折算；
--   * 需要折算时**必须**用 catalog 的汇率台账（5.2.2.2）取「发生日当天或之前最新的一条」
--     （先例：6.5.2.1 采购分析 `PurchaseAnalyticsCatalogPort`），取不到就**只报原币种分组**。
--
-- ===========================================================================
-- 二、数据从哪来（上游 = 6.2.2.1 采购申请）
-- ===========================================================================
--
-- 本模块的采购单由 **6.2.2.1 的转单事实**（`purchase_request_conversion`）生成：
-- 一条转单事实 ⇒ 一张采购单的一行（同一供应商 + 同一目标仓 + 同一币种合并成一张单）。
-- 与 6.2.2.2「合并只在同一供应商内进行」同一口径 —— **跨供应商绝不合单**，
-- 否则对账与付款会错。
--
-- ⚠️ 转单事实里**没有单价与币种**（那是申请行 / 申请单上的东西）⇒ 生成时从
--   `purchase_request_line.unit_price`（预估单价）与 `purchase_request.currency_code` 取，
--   **取不到就拒收并写明原因**（UNIT_PRICE_MISSING / CURRENCY_MISSING），
--   绝不按 0 元下单、也不静默丢弃 —— 丢掉的行等于少订的货。
--
-- ===========================================================================
-- 三、状态机（DB 层钉死，非法跃迁直接拒绝）
-- ===========================================================================
--
--   DRAFT           草稿
--   PENDING_APPROVAL 待审批
--   APPROVED        已审批
--   REJECTED        已驳回
--   IN_FULFILLMENT  执行中（已对供应商下单，等发货）
--   CLOSED          已关闭（终态）
--
--   DRAFT           → PENDING_APPROVAL / APPROVED / CLOSED
--   PENDING_APPROVAL→ APPROVED / REJECTED / CLOSED
--   APPROVED        → IN_FULFILLMENT / CLOSED
--   REJECTED        → CLOSED
--   IN_FULFILLMENT  → CLOSED
--   CLOSED          → （终态，整行冻结）
--
-- ⚠️ 本模块只实现**关闭**（蓝图 6.3.1.1 输出句里的批量操作），
--   其余跃迁留给 6.3.1.2 / 6.3.1.3 —— 但**枚举与状态机现在就写进 DB**，
--   兄弟模块交付时不必 ALTER 本表（跨车道 DDL 回归面 = 0）。
--
-- ===========================================================================
-- 四、本迁移的跨车道回归面
-- ===========================================================================
--
-- **只新建 3 张表 + 1 个序列 + 触发器，零 ALTER 零 DROP 既有表** ⇒ 跨车道 DDL 回归面 = 0。
-- （同批另有车道往 trade 落 V252 / V253 / V255，版本号互不撞号。）
--
-- ⚠️ **不建指向 `purchase_arrival_tracking` 的外键**：6.3.2.2 已交付，
--   它的表**归它所有**；本模块只按 `purchase_order_no` 做**逻辑关联**（同库同租户，RLS 覆盖）。

-- ===========================================================================
-- 一、采购单头
-- ===========================================================================
CREATE TABLE purchase_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- **采购单号**：6.3.2.2 的 `purchase_arrival_tracking.purchase_order_no` 就是这一列的值。
  order_no varchar(64) NOT NULL CHECK(btrim(order_no) <> ''),

  -- ---- 来源：本模块由 6.2.2.1 的转单事实生成；MANUAL 留给 6.3.1.2 手工建单 ----
  source_type varchar(32) NOT NULL DEFAULT 'PURCHASE_REQUEST_CONVERSION'
      CHECK(source_type IN ('PURCHASE_REQUEST_CONVERSION','MANUAL')),
  source_request_id uuid,
  source_request_no varchar(64) NOT NULL DEFAULT '',

  -- ---- 供应商（catalog 独立库 ⇒ 逻辑引用 + 展示快照，不建跨库 FK）----
  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 法人（决定本位币；折算基准的唯一来源）----
  legal_entity_id uuid,
  legal_entity_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 币种与金额：**一张单一个币种**，行金额与它对齐 ----
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ---- 本位币与汇率快照（**建单那一刻**从 catalog 冻结）----
  -- ⚠️ 取不到汇率就是**没有**（NULL），**绝不**按 1:1 或固定汇率填一个数进去 ——
  --    一个假汇率会让「跨币种合计」变成一个看起来能对账、其实对不上的数。
  functional_currency char(3)
      CHECK(functional_currency IS NULL OR functional_currency ~ '^[A-Z]{3}$'),
  fx_snapshot_at timestamptz,
  -- ⚠️ **金额与行数由 DB 从行表重算**（触发器 refresh_purchase_order_totals），
  --    调用方传不进来 ⇒ 「头金额与行金额对不上」不是代码路径，是不可能发生的事。
  total_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_amount >= 0),
  line_count int NOT NULL DEFAULT 0 CHECK(line_count >= 0),

  -- ---- 状态机（第三节）----
  status varchar(20) NOT NULL DEFAULT 'DRAFT'
      CHECK(status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','IN_FULFILLMENT','CLOSED')),

  -- ---- 承诺到货日（对供应商的正式承诺；6.3.2.1 跟单改的是它的历史）----
  promised_arrival_date date NOT NULL,

  -- ---- 目标仓（蓝图筛选条件之一，也是数据范围之一）----
  target_warehouse_id uuid NOT NULL,
  target_warehouse_code varchar(64) NOT NULL DEFAULT '',
  target_warehouse_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 负责人与部门：**数据范围收敛用的两列**（ADR-0005：范围绑角色分配）----
  owner_user_id uuid NOT NULL,
  owner_department_id uuid,

  -- ---- 催单（6.3.1.1 输出句里的批量操作之一）----
  urge_count int NOT NULL DEFAULT 0 CHECK(urge_count >= 0),
  last_urged_at timestamptz,

  -- ---- 关闭 ----
  closed_at timestamptz,
  close_reason text NOT NULL DEFAULT '' CHECK(length(close_reason) <= 1000),

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  -- 乐观锁：改状态都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_no),

  -- 关闭必须留理由与时刻：**「关掉了但不知道为什么」等于没关**。
  CONSTRAINT purchase_order_closed_consistent CHECK(
    status <> 'CLOSED'
    OR (closed_at IS NOT NULL AND length(btrim(close_reason)) > 0)
  )
);

COMMENT ON TABLE purchase_order IS
  '6.3.1.1 采购单（头）：单号是 6.3.2.2 到货跟踪的逻辑关联键；金额与行数由 DB 从行表重算';

-- ===========================================================================
-- 二、采购单行
-- ===========================================================================
CREATE TABLE purchase_order_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL
      REFERENCES purchase_order(id) ON DELETE CASCADE,

  line_no int NOT NULL CHECK(line_no > 0),

  -- ---- SKU（catalog 独立库 ⇒ 逻辑引用 + 展示快照）----
  sku_id uuid,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 来源：6.2.2.1 的转单事实（可追溯；一条转单事实只进一张采购单的一行）----
  source_conversion_id uuid,
  source_request_line_id uuid,

  -- ---- 数量（基础单位，NUMERIC 精确十进制，禁浮点）----
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  unit_code varchar(32) NOT NULL DEFAULT '',

  -- ---- 单价与行金额：**行金额由 DB 算**（触发器），调用方传不进来 ----
  unit_price numeric(19,6) NOT NULL CHECK(unit_price >= 0),
  line_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK(line_amount >= 0),

  -- ---- 汇率快照（提交时冻结；**没有就是没有，绝不 1:1**）----
  fx_rate_to_functional numeric(19,8)
      CHECK(fx_rate_to_functional IS NULL OR fx_rate_to_functional > 0),
  fx_rate_base_currency char(3) CHECK(fx_rate_base_currency IS NULL OR fx_rate_base_currency ~ '^[A-Z]{3}$'),
  fx_rate_effective_date date,

  -- 单价来源（申请预估 / 报价 / 手工）：来源不同，对账时追法不同。
  price_source varchar(24) NOT NULL DEFAULT 'REQUEST_ESTIMATE'
      CHECK(price_source IN ('REQUEST_ESTIMATE','QUOTE','MANUAL')),

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id,line_no),
  -- 一条转单事实只能进一张采购单的一行（重复转单 = 对供应商承诺两次）。
  UNIQUE(tenant_id,source_conversion_id)
);

COMMENT ON TABLE purchase_order_line IS
  '6.3.1.1 采购单行：行金额由 DB 算；source_conversion_id 指向 6.2.2.1 的转单事实';

-- ===========================================================================
-- 三、留痕（追加式）
-- ===========================================================================
-- ⚠️ 「这张单被催过几次、什么时候关的、为什么关」是采购员与供应商对质的凭据 ⇒ 追加式。
CREATE TABLE purchase_order_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL
      REFERENCES purchase_order(id) ON DELETE CASCADE,

  event_type varchar(24) NOT NULL
      CHECK(event_type IN ('CREATED','URGED','CLOSED','STATUS_CHANGED')),
  from_status varchar(20),
  to_status varchar(20),

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),

  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id,id)
);

COMMENT ON TABLE purchase_order_event IS
  '6.3.1.1 采购单留痕：追加式，运行角色 UPDATE/DELETE 已收回';

-- ===========================================================================
-- 四、单号序列
-- ===========================================================================
-- 采购单号 = 'PO-' || 序列值（单调递增 ⇒ 排序稳定、可读、可回溯）。
-- ⚠️ **与 6.3.2.2 的 `PA-` 序列同构**，两边的单号前缀不同 ⇒ 不会互相认错。
CREATE SEQUENCE purchase_order_no_seq START 1;

-- ===========================================================================
-- 五、触发器
-- ===========================================================================
-- 5.1 行金额由 DB 算：line_amount = quantity × unit_price（调用方传不进来）。
--     同时：**已关闭的采购单不许再改行**（与头表冻结同一口径）。
CREATE FUNCTION guard_purchase_order_line_amount() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  head_status varchar(20);
BEGIN
  SELECT o.status INTO head_status
    FROM purchase_order o
   WHERE o.id = NEW.order_id AND o.tenant_id = NEW.tenant_id;

  IF head_status = 'CLOSED' THEN
    RAISE EXCEPTION '采购单已关闭，不允许再改它的行（数量 / 单价 / SKU 都不可改）'
      USING ERRCODE='23514';
  END IF;

  NEW.line_amount := round(NEW.quantity * NEW.unit_price, 6);
  RETURN NEW;
END $$;

CREATE TRIGGER purchase_order_line_amount
  BEFORE INSERT OR UPDATE ON purchase_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_line_amount();

-- 5.2 头的金额与行数由 DB 从行表重算。
--     ⇒ 「列表上的总金额」与「详情里各行金额之和」永远是同一个数。
--
-- ⚠️ **不能用 COALESCE(NEW.x, OLD.x)**：DELETE 触发器里 NEW 是**未赋值记录**，
--    引用它的字段会直接抛 `record "new" is not assigned yet` ⇒ 这里按 TG_OP 分流。
CREATE FUNCTION refresh_purchase_order_totals() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  target uuid;
  owner_tenant uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN
    target := OLD.order_id;
    owner_tenant := OLD.tenant_id;
  ELSE
    target := NEW.order_id;
    owner_tenant := NEW.tenant_id;
  END IF;

  UPDATE purchase_order o
     SET line_count = (SELECT count(*) FROM purchase_order_line l
                        WHERE l.tenant_id = o.tenant_id AND l.order_id = o.id),
         total_amount = COALESCE((SELECT sum(l.line_amount) FROM purchase_order_line l
                                   WHERE l.tenant_id = o.tenant_id AND l.order_id = o.id), 0),
         updated_at = now()
   WHERE o.tenant_id = owner_tenant
     AND o.id = target;
  RETURN NULL;
END $$;

CREATE TRIGGER purchase_order_line_totals
  AFTER INSERT OR UPDATE OR DELETE ON purchase_order_line
  FOR EACH ROW EXECUTE FUNCTION refresh_purchase_order_totals();

-- 5.3 状态机 + 终态冻结（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）。
CREATE FUNCTION guard_purchase_order_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status = 'DRAFT'           AND NEW.status IN ('PENDING_APPROVAL','APPROVED','CLOSED'))
      OR (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('APPROVED','REJECTED','CLOSED'))
      OR (OLD.status = 'APPROVED'       AND NEW.status IN ('IN_FULFILLMENT','CLOSED'))
      OR (OLD.status = 'REJECTED'       AND NEW.status = 'CLOSED')
      OR (OLD.status = 'IN_FULFILLMENT' AND NEW.status = 'CLOSED')
    ) THEN
      RAISE EXCEPTION '采购单「%」的状态不能从 % 改成 %：合法流转只有 草稿→待审/已批/已关、待审→已批/已驳回/已关、已批→执行中/已关、已驳回→已关、执行中→已关',
        OLD.order_no, OLD.status, NEW.status
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- **关闭后整单冻结**：已关闭的采购单不能再改数量、单价、供应商、承诺日。
  -- 理由与 6.2.2.1 的「提交后冻结内容」同源：审批与留痕要能指回「当时那一版」。
  IF OLD.status = 'CLOSED' THEN
    IF NEW.order_no IS DISTINCT FROM OLD.order_no
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_request_id IS DISTINCT FROM OLD.source_request_id
       OR NEW.supplier_ref_id IS DISTINCT FROM OLD.supplier_ref_id
       OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.promised_arrival_date IS DISTINCT FROM OLD.promised_arrival_date
       OR NEW.target_warehouse_id IS DISTINCT FROM OLD.target_warehouse_id
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.owner_department_id IS DISTINCT FROM OLD.owner_department_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
      RAISE EXCEPTION '采购单「%」已关闭，内容字段不可再改：要改请另建一张采购单',
        OLD.order_no
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER purchase_order_status_guard
  BEFORE UPDATE ON purchase_order
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_status();

-- 5.4 留痕表追加式：UPDATE/DELETE 一律拒（业务凭据）。
CREATE FUNCTION guard_purchase_order_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：采购单的催单与关闭记录是「当时做了什么」的举证凭据',
    TG_TABLE_NAME, TG_OP
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER purchase_order_event_append_only BEFORE UPDATE OR DELETE ON purchase_order_event
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_append_only();

-- ===========================================================================
-- 六、索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 清单页主查询：按状态 + 创建时间倒序。
CREATE INDEX purchase_order_by_status_created ON purchase_order(tenant_id, status, created_at DESC, id);
-- 供应商筛选（蓝图点名的筛选条件之一）。
CREATE INDEX purchase_order_by_supplier ON purchase_order(tenant_id, supplier_ref_id, created_at DESC, id);
-- 目标仓筛选（蓝图点名的筛选条件之一）+ 数据范围收敛。
CREATE INDEX purchase_order_by_warehouse ON purchase_order(tenant_id, target_warehouse_id, created_at DESC, id);
-- 数据范围：负责人（SELF）/ 部门（DEPARTMENT）。
CREATE INDEX purchase_order_by_owner ON purchase_order(tenant_id, owner_user_id, created_at DESC, id);
CREATE INDEX purchase_order_by_department ON purchase_order(tenant_id, owner_department_id, created_at DESC, id);
-- 承诺到货日区间筛选 + 逾期判定。
CREATE INDEX purchase_order_by_promised ON purchase_order(tenant_id, promised_arrival_date, id);
-- 行：按单读（详情/导出）。
CREATE INDEX purchase_order_line_by_order ON purchase_order_line(tenant_id, order_id, line_no);
-- 行：按 SKU 反查（「这个 SKU 在哪些采购单里」）。
CREATE INDEX purchase_order_line_by_sku ON purchase_order_line(tenant_id, sku_id, order_id);
-- 留痕：按单读。
CREATE INDEX purchase_order_event_by_order ON purchase_order_event(tenant_id, order_id, occurred_at, id);

-- ===========================================================================
-- 七、多租户：RLS + 授权（与 trade V1 / V92 / V234 / V238 / V242 / V246 同一套写法）
-- ===========================================================================
-- 三张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_order','purchase_order_line','purchase_order_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_order,purchase_order_line,purchase_order_event TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_order,purchase_order_line,purchase_order_event TO starcon_app;
-- 收回留痕表的改写权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON purchase_order_event FROM trade_app;
-- 采购单**不提供 DELETE**：采购单是对供应商的正式承诺，是业务凭据；
-- 删除留痕的级联会抹掉「这张单当时被催过、被关掉过」的证据。
REVOKE DELETE ON purchase_order,purchase_order_line FROM trade_app;

GRANT USAGE, SELECT ON SEQUENCE purchase_order_no_seq TO trade_app;
GRANT USAGE, SELECT ON SEQUENCE purchase_order_no_seq TO starcon_app;
