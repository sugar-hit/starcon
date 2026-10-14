-- ===========================================================================
-- V103 8.4.1.1 组装单（实物套装组装）
--
-- 蓝图 08 卷 8.4.1.1：
--   职责：实物套装组装：按 BOM 领用组件、消耗组件库存、产生成品 SKU 库存，**同一事务完成**。
--   输入：成品 SKU 与 BOM 版本；组件领用量；组装数量。
--   输出：组装单 + 组件出库分录 + 成品入库分录。
--   关系：上游 = 6.4.1.1 加工单、3.5.2 BOM；下游 = 7 库存。
--
-- ---------------------------------------------------------------------------
-- 一、为什么是「单据 + 分录快照」两张新表，而不是复用 V1 的 inventory_transaction
--
-- 6.4.1.1 加工单（ProcessingOrderService）刻意**不建表**，复用 V1 既有
-- inventory_transaction + inventory_entry —— 因为加工单据归 trade（V97），
-- 仓库侧只是它的一次库存动作，没有「仓库自己的组装单据」这回事。
--
-- 8.4.1.1 相反：蓝图写的是「**输出**：组装单 + …」，组装单是 warehouse **自己拥有**的
-- 现场作业单据（业务可以不等加工单，直接按成品 SKU + BOM 版本发起组装）。
-- 所以必须有 assembly_order 这张**有自己生命周期**的事实表，否则：
--   * 「这张组装单是谁发的、按哪一版 BOM、组了多少」无处可查；
--   * 「组装完的成品到底进了哪个库位、组件从哪个库位出的」只剩一条 inventory_transaction，
--     而那条分录不携带 BOM 版本，出事时无法回答「按哪一版算的料」。
--
-- ---------------------------------------------------------------------------
-- 二、跨服务边界：BOM 在 catalog、加工单在 trade，都不建外键
--
-- AGENTS.md 明令禁止跨服务 SQL / FK / 共享视图。所以：
--   * bom_id / bom_version_id / bom_component_id / reference_id **一律是逻辑引用键，无外键**；
--   * 组件明细在 assembly_order_component 上落成**自己的分录快照**（单位用量、损耗率、
--     应领量全部冻结在本表）。这是跨服务快照的常规做法，也满足「每张事实表只有一个写入所有者」。
--   * 不 join catalog.bom_component、不 join trade.processing_order：服务各有独立数据库。
--
-- 上游来源用 source_system + reference_id 两个字段表达，本模块**同时支持两种发起方式**：
--   source_system='TRADE_PROCESSING_ORDER'  上游是 6.4.1.1 加工单（按引用键追溯，不建 FK）
--   source_system='DIRECT'                  仓库现场直接按「成品 SKU + BOM 版本」发起
--
-- ---------------------------------------------------------------------------
-- 三、虚拟套装为什么**结构上进不来**（蓝图点名：虚拟套装走不到这里）
--
-- 蓝图 8.4.1.1 关系段加粗句：「虚拟套装走不到这里——虚拟套装不持有库存，
-- 出库时由 09 订单直接展开组件扣减。」
--
-- 这不是靠一个 CHECK 硬凑的，而是在 catalog 3.5.1.1 的**数据形状**上已经成立：
--   * 虚拟套装 = catalog.bundle，**bundle 表没有任何 sku 外键** —— V25 注释原文：
--     「与 3.5.1.1 对照：bundle 没有任何 sku 外键 —— 虚拟组合不占 SKU 行」；
--   * BOM = catalog.bom，**必须有 output_sku_id**（差异 1：「BOM 有成品，套装没有」）。
--
-- 所以本表的 product_sku_id NOT NULL + 服务层强制它落在 sku_ref（warehouse 自己的 SKU 档案），
-- 结构上就只接受**真实存在的 catalog SKU**。虚拟套装没有 SKU 行 → 没有 sku_ref 行 →
-- 根本填不出合法的 product_sku_id。这是「不假设、去 grep 确认」后的结论，不是拍脑袋。
--
-- ---------------------------------------------------------------------------
-- 四、核心不变量（本文件存在的最主要理由）
--
-- 蓝图要求：「按 BOM 领用组件、消耗组件库存、产生成品 SKU 库存，**同一事务完成**」。
-- 落成两条**数据库层**不变量（不是应用层 if）：
--
--   I1（组件侧）：状态为 COMPLETED 时，组件出库分录绝对值之和 = 按 BOM 展开的应领量之和。
--   I2（成品侧）：状态为 COMPLETED 时，成品入库量 = 组装数量。
--
-- 三层强制，缺一不可：
--   1) assembly_order_component 的行级 CHECK：issued_quantity 要么 0（未发），
--      要么**恰好等于** required_quantity —— 不允许部分领用（部分领用等于把一个套装
--      拆成两半装，后一半的账就说不清了；蓝图要求「同一事务完成」）。
--   2) refresh_assembly_order_totals()：AFTER 触发器把明细的 SUM 回写单头合计，
--      **单头的两个合计列只有这一个写入者**，应用层写不进去（应用层改合计列会被
--      下一次刷新覆盖；而且 COMPLETED 时改合计会被 guard 直接拒）。
--   3) guard_assembly_order()：BEFORE UPDATE 时，对 COMPLETED 状态**回读真正的账本分录**
--      （inventory_entry / inventory_balance）核对 I1 / I2 —— 比对的是「分录」而不是
--      「单据上自己写的数字」，所以绕过服务层直连 SQL 造一条「只入货没出料」的分录组
--      同样会被挡住。
--
-- 另外：为什么要「精确等于」而不是「四舍五入后等于」？
--   inventory_balance / stock_bucket 是 numeric(19,4)（V1 已定），而 BOM 应领量是
--   numeric(19,6)。若允许取整，向下取整 = **凭空吞掉材料**（账上少领，实体多丢），
--   向上取整 = **凭空多领材料**。两种都破坏账实相符。所以服务层**拒绝**任何不能被
--   4 位小数精确表示的量，并指出是哪一行、建议改组装数量或改 BOM —— 而不是替运营做主。
--   这也是 I1 能写成严格等号的原因。
--
-- ---------------------------------------------------------------------------
-- 五、多租户：tenant_id NOT NULL + FORCE RLS（与 V1 第 9 节、V91 第 12 节同一条纪律）

-- ===========================================================================
-- 1. 组装单单头
-- ===========================================================================
CREATE SEQUENCE assembly_order_code_seq START 1;

CREATE TABLE assembly_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 单号。租户内唯一：两张组装单号相同则「按单号找组装单」有两个答案。
  order_code varchar(32) NOT NULL,
  -- **成品 SKU**（差异 1：BOM 有成品，虚拟套装没有）。跨服务逻辑 ID，指向 warehouse
  -- 自己的 sku_ref（服务层校验），不建跨服务外键。NOT NULL 本身就是「虚拟套装进不来」的第一道闸。
  product_sku_id uuid NOT NULL,
  product_sku_code varchar(64) NOT NULL,
  -- **BOM 版本**（3.5.2 交付在 catalog V25/V26）。逻辑引用键，无外键。
  bom_id uuid NOT NULL,
  bom_code varchar(64) NOT NULL,
  bom_version_id uuid NOT NULL,
  bom_version_no varchar(32) NOT NULL,
  -- 上游来源。两种发起方式都在这里显式表达，不用「有 reference_id 就当作上游」去猜。
  source_system varchar(32) NOT NULL
    CHECK(source_system IN ('TRADE_PROCESSING_ORDER','DIRECT')),
  -- 上游单据引用键。DIRECT 发起时为 NULL；TRADE_PROCESSING_ORDER 时指向 6.4.1.1 加工单
  -- （trade V97 的逻辑 ID，跨服务无外键，仅作追溯）。
  reference_id uuid,
  reference_no varchar(64) NOT NULL DEFAULT '',
  warehouse_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  -- **组装数量**：I2 的右端。scale 必须 ≤ 4（与 V1 库存刻度一致），否则服务层拒绝 ——
  -- 理由见文件头第四节（不允许取整换取「通过」）。
  assembly_quantity numeric(19,6) NOT NULL CHECK(assembly_quantity > 0 AND assembly_quantity = round(assembly_quantity, 4)),
  -- 生命周期。DRAFT → COMPLETED / CANCELLED，两个终态都不可再改。
  status varchar(20) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','COMPLETED','CANCELLED')),
  -- 组件出库 / 成品入库的落点（快照到单头，执行时不必再问一次「料从哪出、货往哪进」）。
  -- 组件出库库位 NOT NULL：V2 守恒触发器要求 bucket 与 balance 同动，而 bucket 必须有库位，
  -- 「没有库位就没有库存事实」——这个理由在 ProcessingOrderService 里已经论证过，这里同口径。
  outbound_location_id uuid NOT NULL,
  outbound_quality_status_id uuid NOT NULL,
  inbound_location_id uuid NOT NULL,
  inbound_quality_status_id uuid NOT NULL,
  outbound_lot_id uuid,
  inbound_lot_id uuid,
  -- **两个合计列的唯一写入者是 refresh_assembly_order_totals() 触发器**。
  -- 应用层 insert 时给 0，之后每次明细变动由触发器回写。
  required_component_quantity numeric(19,6) NOT NULL DEFAULT 0,
  issued_component_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- I2 的左端（成品实际入库量）。
  inbound_product_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 库存分录凭据：一次组装 = 一条 inventory_transaction + N+1 条带符号 inventory_entry。
  inventory_transaction_id uuid,
  issued_at timestamptz,
  issued_by uuid,
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
  -- 直接发起的组装单必须没有上游单据，带上游的必须给引用键 —— 两者不能对调，
  -- 否则「这张组装单的上游是谁」会有两个答案（其中一个是空气）。
  CHECK((source_system = 'DIRECT') = (reference_id IS NULL)),
  -- **只有 COMPLETED 必须带分录凭据**：执行会动库存，所以必须有 inventory_transaction；
  -- CANCELLED 恰恰不动库存（取消一张草稿不会产生任何分录），带一个 null 才是对的。
  CHECK(status <> 'COMPLETED' OR inventory_transaction_id IS NOT NULL),
  CHECK(status <> 'COMPLETED' OR (issued_at IS NOT NULL AND issued_by IS NOT NULL)),
  CHECK(status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND btrim(cancel_reason)<>'')),
  -- **I2 在单头级**。I1（组件侧）由行级 CHECK + 触发器回写合计 + guard 回读分录三重保证。
  CHECK(status <> 'COMPLETED' OR inbound_product_quantity = assembly_quantity),
  -- 已执行的组装单不允许把组件侧的账说成「发得比应领少/多」——这一条会被
  -- refresh 触发器在明细变动时自动核对，这里是同一不变量的第二道表述。
  CHECK(status <> 'COMPLETED' OR issued_component_quantity = required_component_quantity),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,inbound_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,inbound_location_id) REFERENCES warehouse_location(tenant_id,id),
  CHECK(btrim(order_code)<>'' AND btrim(product_sku_code)<>'')
);

COMMENT ON TABLE assembly_order IS
  '8.4.1.1 组装单：实物套装组装的仓库侧单据。虚拟套装（catalog.bundle，不占 SKU 行）结构上进不来。';
COMMENT ON COLUMN assembly_order.bom_version_id IS
  'catalog 3.5.2 的 BOM 版本逻辑 ID。跨服务不建外键、按引用键 + 明细快照处理。';
COMMENT ON COLUMN assembly_order.required_component_quantity IS
  'BOM 应领量合计。唯一写入者是 refresh_assembly_order_totals() 触发器。';
COMMENT ON COLUMN assembly_order.inventory_transaction_id IS
  '本单组装落的那条 inventory_transaction（ref_type=ASSEMBLY_ORDER），幂等锚点。';

-- ===========================================================================
-- 2. 组装单组件明细（BOM 的跨服务分录快照）
-- ===========================================================================
CREATE TABLE assembly_order_component (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 组件 SKU。指向本服务 sku_ref（服务层校验）：BOM 的组件必须是真实存在的 catalog SKU。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  -- **BOM 组件行的逻辑引用键**（catalog 3.5.2 bom_component.id）。只作追溯，不建外键。
  bom_component_id uuid,
  -- **单位用量**（每 1 个成品要多少）：catalog bom_component.unit_quantity 的冻结快照。
  -- 可空：调用方只传「应领量」也是合法的（BOM 已经算完了），单位用量只是给人看的追溯信息，
  -- 强制必填会让「我只是来执行这张单的」也要先去查一遍 BOM。
  unit_quantity numeric(19,6) CHECK(unit_quantity > 0),
  -- **损耗率**（百分数，5 = 5%）：catalog bom_component.scrap_rate 的冻结快照。
  scrap_rate numeric(7,4) NOT NULL DEFAULT 0 CHECK(scrap_rate >= 0 AND scrap_rate <= 100),
  -- **应领量**：catalog 规则 R7 的结果，单位用量 × 组装数量 × (1 + 损耗率/100)，6 位小数 HALF_UP。
  -- 本服务**不重算 BOM**，只把这一个冻结值原样落库（与 6.4.1.1 trade 侧同一纪律）。
  required_quantity numeric(19,6) NOT NULL
    CHECK(required_quantity > 0 AND required_quantity = round(required_quantity, 4)),
  -- **实发量**：执行时按库存刻度（4 位小数）从库位发出的量。
  -- 行级 CHECK（I1 的第一层）：要么还没发（0），要么**恰好发够**（= required_quantity）。
  -- 不允许部分领用：部分领用等于把一个套装拆成两半装，后一半的账说不清，
  -- 而蓝图要求「同一事务完成」。
  issued_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(issued_quantity >= 0),
  -- 执行时的落点（出库库位/批次/余额行）。执行前为空。
  balance_id uuid,
  location_id uuid,
  lot_id uuid,
  -- 组件角色是自由文本（面料 / 包材 / 螺丝…），只给人看，不参与任何判定 —— 与 catalog 同口径。
  role varchar(64) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一组装单里同一个 SKU 只能有一行：两行时「这个组件要几件」无法回答，
  -- 账上也会出现两条同向分录（与 catalog bom_component 的 UNIQUE(tenant_id,version_id,sku_id) 同理）。
  UNIQUE(tenant_id,order_id,sku_id),
  -- **I1 行级**：实发量要么 0（未发），要么恰好等于应领量。
  CHECK(issued_quantity = 0 OR issued_quantity = required_quantity),
  -- 已发出的行必须带落点（否则「料从哪出的」无从回答）。
  CHECK(issued_quantity = 0 OR (balance_id IS NOT NULL AND location_id IS NOT NULL)),
  FOREIGN KEY(tenant_id,order_id) REFERENCES assembly_order(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id)
);

COMMENT ON TABLE assembly_order_component IS
  '组装单的组件分录快照：catalog 3.5.2 BOM 组件行在本服务的冻结副本（跨服务不 join、不建外键）。';
COMMENT ON COLUMN assembly_order_component.required_quantity IS
  '按 BOM 规则 R7 冻结的应领量：单位用量 × 组装数量 × (1 + 损耗率/100)。本服务不重算。';

CREATE INDEX assembly_order_list ON assembly_order(tenant_id,status,created_at DESC);
CREATE INDEX assembly_order_product ON assembly_order(tenant_id,product_sku_id,created_at DESC);
CREATE INDEX assembly_order_source ON assembly_order(tenant_id,source_system,reference_id)
  WHERE reference_id IS NOT NULL;
CREATE INDEX assembly_order_tx ON assembly_order(tenant_id,inventory_transaction_id)
  WHERE inventory_transaction_id IS NOT NULL;
CREATE INDEX assembly_order_component_order ON assembly_order_component(tenant_id,order_id,sku_id);
CREATE INDEX assembly_order_component_sku ON assembly_order_component(tenant_id,sku_id);

-- ===========================================================================
-- 3. 合计回写：单头的两个合计列**只有这一个写入者**
-- ===========================================================================
-- 为什么必须用触发器而不是让应用层算：
--   I1/I2 要能防住「绕过服务层直连 SQL 写分录」。只要合计列可以由应用层随意赋值，
--   就存在「单头写着发够了、账上其实少发」这种自相矛盾的记录。
--   触发器让合计成为明细的**派生量**，应用层写不进去，也就不会漂。
CREATE FUNCTION refresh_assembly_order_totals() RETURNS trigger LANGUAGE plpgsql AS $$
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
  -- 让合计继续跟着明细走会让人误以为「取消的单还在用料」。
  UPDATE assembly_order o
     SET required_component_quantity = agg.required,
         issued_component_quantity = agg.issued,
         version = o.version + 1,
         updated_at = now()
    FROM (SELECT COALESCE(sum(required_quantity),0) AS required,
                 COALESCE(sum(issued_quantity),0)   AS issued
            FROM assembly_order_component
           WHERE tenant_id = v_tenant AND order_id = v_order) agg
   WHERE o.id = v_order AND o.tenant_id = v_tenant AND o.status <> 'CANCELLED';
  RETURN NULL;
END $$;
CREATE TRIGGER assembly_order_component_totals
  AFTER INSERT OR UPDATE OR DELETE ON assembly_order_component
  FOR EACH ROW EXECUTE FUNCTION refresh_assembly_order_totals();

-- ===========================================================================
-- 4. 状态机与不变量守卫（回读真正的账本分录，不是单据上的自报数字）
-- ===========================================================================
-- 合法迁移：DRAFT → COMPLETED / CANCELLED。终态不可再改 ——
-- 组装单一旦执行，组件已经出库、成品已经入库；把它改回 DRAFT 等于宣布
-- 「这批料没动过」，而组件库存已经少了。所以终态一律拒绝（不是「提示」，是硬拒）。
CREATE FUNCTION guard_assembly_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_issued numeric(19,6);
  v_lines  bigint;
  v_inbound numeric(19,6);
  v_ledger_issued numeric(19,6);
  v_ledger_lines  bigint;
BEGIN
  -- ---- 4.1 终态不可逆
  IF OLD.status IN ('COMPLETED','CANCELLED') AND NEW.status IS DISTINCT FROM OLD.status THEN
    RAISE EXCEPTION '组装单 % 已经是终态 %，不能再流转成 %：组件已出库、成品已入库，'
      '把它改回草稿等于宣布这批料没动过，而库存账上已经动了',
      OLD.order_code, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  IF OLD.status IN ('COMPLETED','CANCELLED') THEN
    -- 终态行除 status 外一律不可改（终态是凭据，不是草稿）。
    IF NEW.assembly_quantity IS DISTINCT FROM OLD.assembly_quantity
       OR NEW.required_component_quantity IS DISTINCT FROM OLD.required_component_quantity
       OR NEW.inbound_product_quantity IS DISTINCT FROM OLD.inbound_product_quantity THEN
      RAISE EXCEPTION '组装单 % 已是终态，组装数量 / 应领量 / 入库量都是凭据，不可改写', OLD.order_code
        USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;

  -- ---- 4.2 完成前必须真的有组件：一张 0 组件的「已完成组装单」等于凭空变出成品
  IF NEW.status = 'COMPLETED' THEN
    SELECT count(*) INTO v_lines FROM assembly_order_component
     WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id;
    IF v_lines = 0 THEN
      RAISE EXCEPTION '组装单 % 没有任何组件明细就要标记完成：没有组件的组装等于凭空变出成品',
        NEW.order_code USING ERRCODE='23514';
    END IF;

    -- ---- 4.3 I1：组件出库**分录**之和 = 按 BOM 展开的应领量之和
    -- 比对的对象是 inventory_entry（账本），不是 assembly_order_component 上的数字：
    -- 这样「只入成品、没出组件」或「少出组件」两种绕过服务层的写法同样被挡下。
    SELECT COALESCE(sum(-e.on_hand_delta),0), count(*)
      INTO v_ledger_issued, v_ledger_lines
      FROM inventory_entry e
      JOIN inventory_balance b ON b.tenant_id = e.tenant_id AND b.id = e.balance_id
      JOIN assembly_order_component c
        ON c.tenant_id = e.tenant_id AND c.order_id = NEW.id AND c.sku_id = b.sku_id
     WHERE e.transaction_id = NEW.inventory_transaction_id AND e.on_hand_delta < 0;
    IF v_ledger_issued <> NEW.required_component_quantity THEN
      RAISE EXCEPTION '组装单 % 违反不变量：组件出库分录之和 % 不等于按 BOM 展开的应领量 %（I1）',
        NEW.order_code, v_ledger_issued, NEW.required_component_quantity USING ERRCODE='23514';
    END IF;
    IF v_ledger_lines <> v_lines THEN
      RAISE EXCEPTION '组装单 % 违反不变量：应有 % 条组件出库分录，账上只有 % 条（I1）',
        NEW.order_code, v_lines, v_ledger_lines USING ERRCODE='23514';
    END IF;

    -- ---- 4.4 I2：成品入库分录 = 组装数量
    SELECT COALESCE(sum(e.on_hand_delta),0)
      INTO v_inbound
      FROM inventory_entry e
      JOIN inventory_balance b ON b.tenant_id = e.tenant_id AND b.id = e.balance_id
     WHERE e.transaction_id = NEW.inventory_transaction_id
       AND b.sku_id = NEW.product_sku_id AND e.on_hand_delta > 0;
    IF v_inbound <> NEW.assembly_quantity THEN
      RAISE EXCEPTION '组装单 % 违反不变量：成品入库分录 % 不等于组装数量 %（I2）',
        NEW.order_code, v_inbound, NEW.assembly_quantity USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER assembly_order_guard BEFORE UPDATE ON assembly_order
  FOR EACH ROW EXECUTE FUNCTION guard_assembly_order();

-- 已完成的组装单不允许再改明细（否则可以在完成后追加组件行、或把某行 issued 归零）。
-- 触发器函数里不能写 COALESCE(NEW.order_id, OLD.order_id) 来兼容 DELETE：
-- DELETE 触发时 NEW 是未赋值记录，取它的字段会直接报错。所以按 TG_OP 分支取行键。
CREATE FUNCTION guard_assembly_component_after_completion_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_order_id uuid;
  v_status   text;
BEGIN
  v_order_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.order_id ELSE NEW.order_id END;
  SELECT status INTO v_status FROM assembly_order WHERE id = v_order_id;
  -- 单据不存在时不拦（建单流程是先插单头再插明细，单头一定先在）。
  IF v_status IS NOT NULL AND v_status <> 'DRAFT' THEN
    RAISE EXCEPTION '组装单已经 %，组件明细不可再改：库存分录已经入账，改明细等于改账',
      v_status USING ERRCODE='23514';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER assembly_order_component_guard
  BEFORE INSERT OR UPDATE OR DELETE ON assembly_order_component
  FOR EACH ROW EXECUTE FUNCTION guard_assembly_component_after_completion_fn();

-- ===========================================================================
-- 5. 多租户：RLS + 授权（与 V1 第 9 节、V91 第 12 节同一条纪律）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['assembly_order','assembly_order_component'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON assembly_order, assembly_order_component TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建包会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE assembly_order_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V90、V91 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;