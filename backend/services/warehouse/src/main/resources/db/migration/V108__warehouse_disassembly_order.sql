-- ===========================================================================
-- V108 8.4.2.1 拆解单（成品拆解回组件）
--
-- 蓝图 08 卷 8.4.2.1：
--   职责：成品拆解回组件：消耗成品库存、产生组件库存（用于退换货拆解、样品回收）。
--   输入：成品 SKU 与数量；拆解去向（可用/待检/残次）。
--   输出：拆解单 + 反向库存分录。
--   关系：上游 = 8.4.1.1；下游 = 7 库存、11 售后（退件拆解）。
--   **拆解结果的组件质量状态由接收方决定，不能默认全部回可售。**
--
-- ---------------------------------------------------------------------------
-- 一、与 V103 组装单的关系：逆操作，但不是镜像
--
-- 8.4.1.1 组装单（V103，assembly_order / assembly_order_component）产出成品、消耗组件；
-- 本模块消耗成品、产生组件。两者在库存分录上必须**成对且可对账**：
--   组装：N 条组件负向分录 + 1 条成品正向分录
--   拆解：1 条成品负向分录 + M 条组件正向分录
--
-- 所以命名空间与表名全部另起（disassembly_*，**不是** assembly_order 的子表）：
-- 「组装」与「拆解」在契约与数据上必须是两个可分别统计的模块 —— 混在一张单表里
-- 就无法回答「这个月拆了多少件退回样品」。
--
-- **reference_id 刻意不建外键到 assembly_order**：两者同属 warehouse，但状态约束
-- 外键表达不了（拆解单要求上游组装单是 COMPLETED，否则「拆一件没组装过的成品」无从分辨）。
-- 引用关系由服务层用显式 SQL 校验并留在 reference_id + reference_no 两个字段上，
-- 跨单据的可追溯性与 V103 对 trade 加工单的同一纪律。
--
-- ---------------------------------------------------------------------------
-- 二、蓝图加粗句的落点：**去向必填，且不给默认值**
--
-- 蓝图原话：「拆解结果的组件质量状态由接收方决定，不能默认全部回可售。」
--
-- 这不是一句提示，而是一条设计约束，落在三个地方：
--   1) disassembly_order_component.destination_quality_status_id **NOT NULL**（DB 层）；
--   2) 服务层**不提供默认值**：不传就是 400，不是「替你选一个可售」（与 8.4.1.1 的
--      resolveQuality() 默认回 SELLABLE 刻意相反 —— 组装的成品入库默认可售是对的，
--      拆解的组件去向默认可售是错的：退回的货凭什么能直接卖？）；
--   3) **去向是逐行**的，不在单头上给一个「整单默认去向」。同一张拆解单里
--      面料回可售、螺丝回残次是常态，单头一个默认值会把这个常态压平。
--
-- 去向取值落在 V1 既有 inventory_quality_status.role 词表上：
--   SELLABLE(可用) / PENDING_INSPECTION(待检) / DEFECTIVE(残次) —— 正是蓝图说的三种。
-- 同一句约束决定了「可售库存」的口径：只有接收方明确判为可售的组件才进 SELLABLE 行。
--
-- ---------------------------------------------------------------------------
-- 三、核心不变量（回读真正的账本分录，不是单据上的自报数字）
--
--   D1：状态 COMPLETED 时，成品出库**分录**绝对值之和 = 拆解数量。
--   D2：状态 COMPLETED 时，组件入库**分录**之和 = 按 BOM 反推的应回量之和。
--   D3：状态 COMPLETED 时，成品 SKU 不得出现在自己的组件明细里
--       （否则「拆」出来的是自己，账上凭空多出同款成品）。
--
-- 三层强制，缺一不可：
--   1) 行级 CHECK：recovered_quantity 要么 0（未拆），要么**恰好等于** expected_quantity。
--      不允许部分拆回 —— 部分拆回等于「拆了一半」，组件去哪了说不清，而蓝图要求同一事务完成。
--   2) refresh_disassembly_order_totals()：明细 SUM 回写单头合计，
--      **单头三个合计列只有这一个写入者**，应用层写不进去。
--   3) guard_disassembly_order()：BEFORE UPDATE 到 COMPLETED 时回读 inventory_entry /
--      inventory_balance 核对 D1/D2，并检查 D3 —— 绕过服务层直连 SQL 也绕不过。
--
-- 与 V103 同一道「不静默取整」纪律：inventory_balance / stock_bucket 是 numeric(19,4)，
-- 应回量是 numeric(19,6)。向下取整 = 凭空吞掉组件，向上取整 = 凭空多产组件，
-- 两种都恰好能让 D2「看起来成立」而物料对不上。服务层显式拒绝。
--
-- ---------------------------------------------------------------------------
-- 四、多租户：tenant_id NOT NULL + FORCE RLS（与 V1 第 9 节、V103 第 5 节同一纪律）
-- 本迁移**不 ALTER 任何既有表**，跨车道 DDL 回归面 = 0。

-- ===========================================================================
-- 1. 拆解单单头
-- ===========================================================================
CREATE SEQUENCE disassembly_order_code_seq START 1;

CREATE TABLE disassembly_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 单号。租户内唯一：两张拆解单号相同则「按单号找拆解单」有两个答案。
  order_code varchar(32) NOT NULL,
  -- **被拆的成品 SKU**。跨服务逻辑 ID，指向本服务 sku_ref（服务层校验），不建跨服务外键。
  product_sku_id uuid NOT NULL,
  product_sku_code varchar(64) NOT NULL,
  -- **按哪一版 BOM 反推组件**（3.5.2 交付在 catalog V25/V26）。逻辑引用键，无外键：
  -- 本服务不 join、不建外键，组件明细在自己的明细表上落成冻结快照。
  bom_id uuid NOT NULL,
  bom_code varchar(64) NOT NULL,
  bom_version_id uuid NOT NULL,
  bom_version_no varchar(32) NOT NULL,
  -- 上游来源。三种发起方式都在这里显式表达，不用「有 reference_id 就当作上游」去猜。
  source_system varchar(32) NOT NULL
    CHECK(source_system IN ('ASSEMBLY_ORDER','AFTERSALES_RETURN','DIRECT')),
  -- 上游单据引用键。DIRECT 发起时为 NULL；ASSEMBLY_ORDER 时指向 8.4.1.1 组装单的逻辑 ID
  -- （同服务但刻意不建外键：外键表达不了「上游必须已完成」）；AFTERSALES_RETURN 时指向
  -- 11 售后退件单（另一服务，无外键，仅作追溯）。
  reference_id uuid,
  reference_no varchar(64) NOT NULL DEFAULT '',
  warehouse_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  -- **拆解数量**：也是 D1 的右端。scale 必须 ≤ 4（与 V1 库存刻度一致），否则服务层拒绝。
  disassembly_quantity numeric(19,6) NOT NULL
    CHECK(disassembly_quantity > 0 AND disassembly_quantity = round(disassembly_quantity, 4)),
  -- 生命周期。DRAFT → COMPLETED / CANCELLED，两个终态都不可再改。
  status varchar(20) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','COMPLETED','CANCELLED')),
  -- **成品出库的落点**（快照到单头，执行时不必再问一次「成品从哪出」）。
  -- 库位 NOT NULL：V2 守恒触发器要求 bucket 与 balance 同动，而 bucket 必须有库位。
  outbound_location_id uuid NOT NULL,
  outbound_quality_status_id uuid NOT NULL,
  outbound_lot_id uuid,
  -- **组件去向刻意不在单头**：见第二节，去向是逐行的，给单头一个默认值等于允许
  -- 「整单默认回可售」，而蓝图点名禁止这件事。
  -- 三个合计列的唯一写入者是 refresh_disassembly_order_totals() 触发器。
  -- 应回组件量合计（按 BOM 反推 + 回收率冻结）。
  expected_component_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 实回组件量合计（执行时的入库分录之和，D2 的左端）。
  recovered_component_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 成品实际出库量（D1 的左端）。
  outbound_product_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 库存分录凭据：一次拆解 = 一条 inventory_transaction + M+1 条带符号 inventory_entry。
  inventory_transaction_id uuid,
  executed_at timestamptz,
  executed_by uuid,
  cancelled_at timestamptz,
  cancelled_by uuid,
  cancel_reason varchar(200) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_code),
  -- 带上游的必须给引用键，直接发起的必须没有 —— 两者不能对调，
  -- 否则「这张拆解单的上游是谁」会有两个答案（其中一个是空气）。
  CHECK((source_system = 'DIRECT') = (reference_id IS NULL)),
  -- **只有 COMPLETED 必须带分录凭据**：执行会动库存，所以必须有 inventory_transaction；
  -- CANCELLED 恰恰不动库存（取消一张草稿不会产生任何分录），带一个 null 才是对的。
  CHECK(status <> 'COMPLETED' OR inventory_transaction_id IS NOT NULL),
  CHECK(status <> 'COMPLETED' OR (executed_at IS NOT NULL AND executed_by IS NOT NULL)),
  CHECK(status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND btrim(cancel_reason)<>'')),
  -- D1 在单头级。D2 由行级 CHECK + 触发器回写合计 + guard 回读分录三重保证。
  CHECK(status <> 'COMPLETED' OR outbound_product_quantity = disassembly_quantity),
  CHECK(status <> 'COMPLETED' OR recovered_component_quantity = expected_component_quantity),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_location_id) REFERENCES warehouse_location(tenant_id,id),
  CHECK(btrim(order_code)<>'' AND btrim(product_sku_code)<>'')
);

COMMENT ON TABLE disassembly_order IS
  '8.4.2.1 拆解单：成品拆解回组件的仓库侧单据（退换货拆解、样品回收）。8.4.1.1 组装单的逆操作。';
COMMENT ON COLUMN disassembly_order.bom_version_id IS
  'catalog 3.5.2 的 BOM 版本逻辑 ID。跨服务不建外键、按引用键 + 明细快照处理。';
COMMENT ON COLUMN disassembly_order.expected_component_quantity IS
  '按 BOM 反推的应回组件量合计。唯一写入者是 refresh_disassembly_order_totals() 触发器。';
COMMENT ON COLUMN disassembly_order.inventory_transaction_id IS
  '本单拆解落的那条 inventory_transaction（ref_type=DISASSEMBLY_ORDER），幂等锚点。';

-- ===========================================================================
-- 2. 拆解单组件明细（BOM 的跨服务反向快照 + 逐行去向）
-- ===========================================================================
CREATE TABLE disassembly_order_component (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 组件 SKU。指向本服务 sku_ref（服务层校验）。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  -- **BOM 组件行的逻辑引用键**（catalog 3.5.2 bom_component.id）。只作追溯，不建外键。
  bom_component_id uuid,
  -- **单位用量**（每 1 个成品能拆出多少）：与组装单同名字段，方向相同（都是「每个成品要多少」），
  -- 只是用途相反 —— 组装按它算应领，拆解按它算应回。可空：只传应回量也是合法的。
  unit_quantity numeric(19,6) CHECK(unit_quantity > 0),
  -- **回收率**（百分数，95 = 95%）：退件拆解与样品回收的实际损耗 —— 包装可能缺件、
  -- 电子件可能已损坏。这是拆解相对组装多出来的一维，没有它就无法解释「BOM 上有 10 个
  -- 螺丝，拆出来只有 8 个」。取值 [0,100]，默认 100（无损）。
  recovery_rate numeric(7,4) NOT NULL DEFAULT 100
    CHECK(recovery_rate >= 0 AND recovery_rate <= 100),
  -- **应回量**：调用方按「单位用量 × 拆解数量 × 回收率/100」算好（6 位小数 HALF_UP）冻结传入，
  -- 本服务**不重算 BOM**、不重算回收率（与 6.4.1.1 trade、8.4.1.1 warehouse 同一纪律）。
  expected_quantity numeric(19,6) NOT NULL
    CHECK(expected_quantity > 0 AND expected_quantity = round(expected_quantity, 4)),
  -- **实回量**：执行时按库存刻度（4 位小数）入库的量。
  -- 行级 CHECK（D2 的第一层）：要么还没拆（0），要么**恰好拆够**（= expected_quantity）。
  recovered_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(recovered_quantity >= 0),
  -- ============================================================
  -- 去向三件套（蓝图「拆解去向」的落点，**全部 NOT NULL，无默认值**）
  -- ============================================================
  -- 组件入库的质量状态。NOT NULL 是蓝图加粗句的第一层强制：
  -- 「拆解结果的组件质量状态由接收方决定，不能默认全部回可售」。
  destination_quality_status_id uuid NOT NULL,
  -- 组件入库库位。同样 NOT NULL：不同组件可能进不同的库位（良品区 / 待检区 / 残次区）。
  destination_location_id uuid NOT NULL,
  destination_lot_id uuid,
  -- 执行时的落点余额行。执行前为空。
  balance_id uuid,
  -- 组件角色是自由文本（面料 / 包材 / 螺丝…），只给人看，不参与任何判定 —— 与 catalog 同口径。
  role varchar(64) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一拆解单里同一个 SKU 只能有一行：两行时「这个组件能拆出几件」无法回答，
  -- 账上也会出现两条同向分录（与 catalog bom_component 的 UNIQUE(tenant_id,version_id,sku_id) 同理）。
  UNIQUE(tenant_id,order_id,sku_id),
  -- **D2 行级**：实回量要么 0（未拆），要么恰好等于应回量。
  CHECK(recovered_quantity = 0 OR recovered_quantity = expected_quantity),
  -- 已拆回的行必须带落点余额行（否则「组件进了哪条余额」无从回答）。
  CHECK(recovered_quantity = 0 OR balance_id IS NOT NULL),
  FOREIGN KEY(tenant_id,order_id) REFERENCES disassembly_order(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,destination_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,destination_location_id) REFERENCES warehouse_location(tenant_id,id)
);

COMMENT ON TABLE disassembly_order_component IS
  '拆解单的组件快照：catalog 3.5.2 BOM 组件行的反向冻结副本（跨服务不 join、不建外键） + 逐行的入库去向（蓝图：质量状态由接收方决定，不默认回可售）。';
COMMENT ON COLUMN disassembly_order_component.expected_quantity IS
  '按「单位用量 × 拆解数量 × 回收率/100」冻结的应回量（6 位小数 HALF_UP）。本服务不重算。';
COMMENT ON COLUMN disassembly_order_component.destination_quality_status_id IS
  '组件入库去向（inventory_quality_status.role ∈ SELLABLE/PENDING_INSPECTION/DEFECTIVE）。NOT NULL 且服务层不提供默认值 —— 蓝图点名禁止「默认全部回可售」。';

CREATE INDEX disassembly_order_list ON disassembly_order(tenant_id,status,created_at DESC);
CREATE INDEX disassembly_order_product ON disassembly_order(tenant_id,product_sku_id,created_at DESC);
CREATE INDEX disassembly_order_source ON disassembly_order(tenant_id,source_system,reference_id)
  WHERE reference_id IS NOT NULL;
CREATE INDEX disassembly_order_tx ON disassembly_order(tenant_id,inventory_transaction_id)
  WHERE inventory_transaction_id IS NOT NULL;
CREATE INDEX disassembly_order_component_order ON disassembly_order_component(tenant_id,order_id,sku_id);
CREATE INDEX disassembly_order_component_sku ON disassembly_order_component(tenant_id,sku_id);
CREATE INDEX disassembly_order_component_destination
  ON disassembly_order_component(tenant_id,destination_quality_status_id);

-- ===========================================================================
-- 3. 合计回写：单头的三个合计列**只有这一个写入者**
-- ===========================================================================
-- 为什么必须用触发器而不是让应用层算：
--   D1/D2 要能防住「绕过服务层直连 SQL 写分录」。只要合计列可以由应用层随意赋值，
--   就存在「单头写着拆够了、账上其实少拆」这种自相矛盾的记录。
--   触发器让合计成为明细的**派生量**，应用层写不进去，也就不会漂。
CREATE FUNCTION refresh_disassembly_order_totals() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_order     uuid;
  v_tenant    uuid;
BEGIN
  -- AFTER 触发器：INSERT/UPDATE 走 NEW，DELETE 走 OLD —— DELETE 时 NEW 是未赋值记录，
  -- 直接写 COALESCE(NEW.order_id, OLD.order_id) 会在取字段时报错，所以按 TG_OP 分支。
  IF TG_OP = 'DELETE' THEN
    v_order := OLD.order_id;
    v_tenant := OLD.tenant_id;
  ELSE
    v_order := NEW.order_id;
    v_tenant := NEW.tenant_id;
  END IF;
  -- 把明细的 SUM 回写单头。CANCELLED 的单不再回写：它的账已经不在库存里，
  -- 让合计继续跟着明细走会让人误以为「取消的单还在拆出组件」。
  UPDATE disassembly_order o
     SET expected_component_quantity = agg.expected,
         recovered_component_quantity = agg.recovered,
         version = o.version + 1,
         updated_at = now()
    FROM (SELECT COALESCE(sum(expected_quantity),0) AS expected,
                 COALESCE(sum(recovered_quantity),0) AS recovered
            FROM disassembly_order_component
           WHERE tenant_id = v_tenant AND order_id = v_order) agg
   WHERE o.id = v_order AND o.tenant_id = v_tenant AND o.status <> 'CANCELLED';
  RETURN NULL;
END $$;
CREATE TRIGGER disassembly_order_component_totals
  AFTER INSERT OR UPDATE OR DELETE ON disassembly_order_component
  FOR EACH ROW EXECUTE FUNCTION refresh_disassembly_order_totals();

-- ===========================================================================
-- 4. 状态机与不变量守卫（回读真正的账本分录，不是单据上的自报数字）
-- ===========================================================================
-- 合法迁移：DRAFT → COMPLETED / CANCELLED。终态不可再改 ——
-- 拆解单一旦执行，成品已经出库、组件已经入库；把它改回 DRAFT 等于宣布
-- 「这批货没动过」，而成品库存已经少了。所以终态一律拒绝（不是「提示」，是硬拒）。
CREATE FUNCTION guard_disassembly_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_outbound   numeric(19,6);
  v_out_lines  bigint;
  v_recovered  numeric(19,6);
  v_rec_lines  bigint;
  v_expected   numeric(19,6);
  v_lines      bigint;
  v_self_line  bigint;
BEGIN
  -- ---- 4.1 终态不可逆
  IF OLD.status IN ('COMPLETED','CANCELLED') AND NEW.status IS DISTINCT FROM OLD.status THEN
    RAISE EXCEPTION '拆解单 % 已经是终态 %，不能再流转成 %：成品已出库、组件已入库，'
      '把它改回草稿等于宣布这批货没动过，而库存账上已经动了',
      OLD.order_code, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  IF OLD.status IN ('COMPLETED','CANCELLED') THEN
    -- 终态行除 status 外一律不可改（终态是凭据，不是草稿）。
    IF NEW.disassembly_quantity IS DISTINCT FROM OLD.disassembly_quantity
       OR NEW.expected_component_quantity IS DISTINCT FROM OLD.expected_component_quantity
       OR NEW.recovered_component_quantity IS DISTINCT FROM OLD.recovered_component_quantity
       OR NEW.outbound_product_quantity IS DISTINCT FROM OLD.outbound_product_quantity THEN
      RAISE EXCEPTION '拆解单 % 已是终态，拆解数量 / 应回量 / 实回量 / 出库量都是凭据，不可改写', OLD.order_code
        USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;

  -- ---- 4.2 完成前必须真的有组件：一张 0 组件的「已完成拆解单」等于凭空变出组件
  IF NEW.status = 'COMPLETED' THEN
    SELECT count(*), COALESCE(sum(expected_quantity),0)
      INTO v_lines, v_expected
      FROM disassembly_order_component
     WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id;
    IF v_lines = 0 THEN
      RAISE EXCEPTION '拆解单 % 没有任何组件明细就要标记完成：没有组件的拆解等于凭空变出组件',
        NEW.order_code USING ERRCODE='23514';
    END IF;

    -- ---- 4.3 D3：成品不得出现在自己的组件明细里
    SELECT count(*) INTO v_self_line
      FROM disassembly_order_component
     WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id AND sku_id = NEW.product_sku_id;
    IF v_self_line > 0 THEN
      RAISE EXCEPTION '拆解单 % 把成品 SKU 自己也列进了组件明细：这叫「复制」不叫「拆解」，'
        '账上会凭空多出同款成品（D3）', NEW.order_code USING ERRCODE='23514';
    END IF;

    -- ---- 4.4 D1：成品出库**分录**绝对值 = 拆解数量，且必须恰好一条
    -- 比对的对象是 inventory_entry（账本），不是 disassembly_order_component 上的数字：
    -- 这样「只拆组件、没出成品」这种绕过服务层的写法同样被挡下。
    SELECT COALESCE(sum(-e.on_hand_delta),0), count(*)
      INTO v_outbound, v_out_lines
      FROM inventory_entry e
      JOIN inventory_balance b ON b.tenant_id = e.tenant_id AND b.id = e.balance_id
     WHERE e.transaction_id = NEW.inventory_transaction_id
       AND b.sku_id = NEW.product_sku_id AND e.on_hand_delta < 0;
    IF v_outbound <> NEW.disassembly_quantity THEN
      RAISE EXCEPTION '拆解单 % 违反不变量：成品出库分录 % 不等于拆解数量 %（D1）',
        NEW.order_code, v_outbound, NEW.disassembly_quantity USING ERRCODE='23514';
    END IF;
    IF v_out_lines <> 1 THEN
      RAISE EXCEPTION '拆解单 % 违反不变量：成品出库分录应有 1 条，账上只有 % 条（D1）',
        NEW.order_code, v_out_lines USING ERRCODE='23514';
    END IF;

    -- ---- 4.5 D2：组件入库**分录**之和 = 按 BOM 反推的应回量之和
    SELECT COALESCE(sum(e.on_hand_delta),0), count(*)
      INTO v_recovered, v_rec_lines
      FROM inventory_entry e
      JOIN inventory_balance b ON b.tenant_id = e.tenant_id AND b.id = e.balance_id
      JOIN disassembly_order_component c
        ON c.tenant_id = e.tenant_id AND c.order_id = NEW.id AND c.sku_id = b.sku_id
     WHERE e.transaction_id = NEW.inventory_transaction_id AND e.on_hand_delta > 0;
    IF v_recovered <> v_expected THEN
      RAISE EXCEPTION '拆解单 % 违反不变量：组件入库分录之和 % 不等于应回量之和 %（D2）',
        NEW.order_code, v_recovered, v_expected USING ERRCODE='23514';
    END IF;
    IF v_rec_lines <> v_lines THEN
      RAISE EXCEPTION '拆解单 % 违反不变量：应有 % 条组件入库分录，账上只有 % 条（D2）',
        NEW.order_code, v_lines, v_rec_lines USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER disassembly_order_guard BEFORE UPDATE ON disassembly_order
  FOR EACH ROW EXECUTE FUNCTION guard_disassembly_order();

-- 已完成的拆解单不允许再改明细（否则可以在完成后追加组件行、或把某行 recovered 归零，
-- 甚至事后把去向改成可售 —— 那正是蓝图点名禁止的事）。
-- 触发器函数里不能写 COALESCE(NEW.order_id, OLD.order_id) 来兼容 DELETE：
-- DELETE 触发时 NEW 是未赋值记录，取它的字段会直接报错。所以按 TG_OP 分支取行键。
CREATE FUNCTION guard_disassembly_component_after_completion_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_order_id uuid;
  v_status   text;
BEGIN
  v_order_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.order_id ELSE NEW.order_id END;
  SELECT status INTO v_status FROM disassembly_order WHERE id = v_order_id;
  -- 单据不存在时不拦（建单流程是先插单头再插明细，单头一定先在）。
  IF v_status IS NOT NULL AND v_status <> 'DRAFT' THEN
    RAISE EXCEPTION '拆解单已经 %，组件明细不可再改：库存分录已经入账，改明细等于改账；'
      '更要紧的是「去向」是接收方判定的，事后改成可售就是让退回的货直接可卖',
      v_status USING ERRCODE='23514';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER disassembly_order_component_guard
  BEFORE INSERT OR UPDATE OR DELETE ON disassembly_order_component
  FOR EACH ROW EXECUTE FUNCTION guard_disassembly_component_after_completion_fn();

-- ===========================================================================
-- 5. 多租户：RLS + 授权（与 V1 第 9 节、V103 第 5 节同一条纪律）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['disassembly_order','disassembly_order_component'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON disassembly_order, disassembly_order_component TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建包会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE disassembly_order_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V90、V91、V103 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;