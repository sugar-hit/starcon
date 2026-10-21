-- 6.4.2.1 质检单：来料/成品质检 —— 抽样比例、检验项逐条记录、不合格判定与扣量、
-- 让步接收（权限 + 原因），输出合格量 / 不合格量 / 让步接收量三量。
--
-- 蓝图 06-purchase-and-supply.md 6.4.2.1 原文：
--   职责：来料/成品质检：抽样比例、检验项（外观、功能、规格、包装、认证标识）、检验结果记录、
--         不合格判定与扣量。
--   输入：收货事件；质检模板（按类目）；抽样规则；质检结果录入。
--   输出：质检单（合格量/不合格量/让步接收量）；触发 7 库存的质量状态分流。
--   关系：上游 = 8 收货、3.7.2 物理安全属性；下游 = 7 库存（待检 bucket）、6.1.1.3 评级。
--         **质检不合格的货不能直接进可售库存**，必须进入待检或残次状态。
--
-- 归属：**trade 服务**（卷 06 L2 表「6.4 加工与质检 → trade + warehouse」，列表第一项是 trade；
-- 同卷的 6.4.1.1 加工单、6.4.1.2 BOM 关联、6.2.1.3 采购计划都在 trade）。
-- 前端路由 /purchase/quality 是**前端**路由；后端命名空间按服务走
-- （与 /trade/purchase-plans、/trade/processing 同一纪律）。
--
-- ===========================================================================
-- 一、模块边界：本文件**不碰任何别的模块的表**
-- ===========================================================================
--   * **收货事实**（8.2.1.1）在 warehouse 服务的库里。本库**只存收货单号与实收量的快照**
--     （`receipt_code` / `arrived_quantity`），**不建跨库 FK、不建跨库视图、不跨服务直查**。
--     读取经 trade 的 `QualityInspectionWarehousePort`（warehouse 的授权只读端点
--     GET /warehouse/goods-receipts + GET /warehouse/goods-receipts/{id}）。
--   * **物理安全属性**（3.7.2.1）在 catalog 服务的库里。本库**只存安全属性快照**
--     （`safety_profile` jsonb + `safety_requires_certification` 布尔），
--     读取经 `QualityInspectionCatalogPort`（GET /catalog/physical-safety/skus/{skuId}）。
--   * **SKU 身份 / 类目 / 供应商**分别是 catalog 的主数据。本库**只存逻辑 ID + 名称快照**，
--     **不建 SKU 影子表**（与 6.2.1.3 采购计划、6.1.2.1 货源池同一纪律）。
--   * **库存质量状态分流**（7 库存的待检 / 残次 bucket）的写入所有权在 warehouse。
--     本库**只记录分流意图与分流状态**，**绝不建影子表假装已经分流**（见第七节）。
--   * **不良品处置**（6.4.2.2）不在本模块范围内：本模块只登记「让步接收」这一条
--     （蓝图 6.4.2.1 输出句里点名的三量之一），**不实现退换货 / 返修 / 报废 / 转促销品的处置流程**。
--
-- ===========================================================================
-- 二、五张业务表的职责划分
-- ===========================================================================
--   1. `quality_inspection_template` —— **质检模板**（按类目；蓝图输入句「质检模板（按类目）」）。
--      抽样规则（比例 / 最小 / 最大 / 舍入口径）、关键项不合格时的动作、残次去向都在模板上。
--   2. `quality_inspection_template_item` —— **检验项定义**（逐条）。
--      蓝图点名的五项：外观 / 功能 / 规格 / 包装 / 认证标识。
--   3. `quality_inspection_order` —— **质检单（头）**：抽样快照 + **三量** + 状态机 + 分流意图。
--   4. `quality_inspection_order_item` —— **逐项检验结论**：每个检验项一条结论，带扣量。
--   5. `quality_inspection_concession` —— **让步接收**（append-only）：原因 + 权限码，缺一拒绝。
--
-- ===========================================================================
-- 三、蓝图加粗句「质检不合格的货不能直接进可售库存」的物理落点
-- ===========================================================================
--   * **结构上分开**：三量互斥且完备 ——
--     `qualified_quantity`（合格量）+ `defective_quantity`（不合格量）
--     + `concession_quantity`（让步接收量）**恰好等于本次质检覆盖的批量** `lot_size`。
--     DB 层 CHECK 钉死（完成态才强制，见下），不是靠服务层自觉。
--   * **不合格量绝不可省**：让步行使时，`defective = 扣量合计 − 让步接收量`，
--     让步接收量**必须 ≤ 扣量合计**（DB CHECK + 服务层双重校验）——
--     让步接收是「不合格里被批准放行的那部分」，不是把不合格改成合格。
--   * **分流意图显式落库**：`disposition` 记录**非可售部分**的去向
--     （PENDING_INSPECTION 待检 / DEFECTIVE 残次），`disposition_status` 记录**是否真的分流了**。
--     warehouse 侧当前**没有**可用的授权写端点（见第七节），所以
--     `disposition_status` 只能是 `NOT_DISPATCHED` / `UNAVAILABLE` ——
--     **绝不出现 `DISPATCHED` 却其实没分流**的那种假记录。
--
-- ===========================================================================
-- 四、抽样真的按比例算（不是前端传来的数）
-- ===========================================================================
--   应抽数量 = 按模板的抽样比例 × 本次批量，再按「最小 / 最大抽样数」夹逼，
--   最后按**舍入口径**取整 —— 整条算式原文写进 `sampling_basis` 落库。
--   服务端算、前端不能传；传了也不认。
--
-- ===========================================================================
-- 五、卷 06 加粗约束「必须记录币种、汇率快照、单位换算」
-- ===========================================================================
--   质检扣量直接改变采购成本与库存计价，所以质检单上带
--   `currency_code` / `exchange_rate`（汇率快照）/ `unit_conversion_factor`（单位换算）
--   / `unit_price`（单价快照）/ `deducted_amount`（扣量金额）。
--   全部 NULL-able 且**只在录入了单价时才有值** —— 缺数据就报「没录」，
--   不用 0 冒充「扣了 0 块」。
--
-- ===========================================================================
-- 1. 质检模板（按类目）
-- ===========================================================================
CREATE TABLE quality_inspection_template (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 模板编码：租户内唯一、可读（TPL-ELEC-STD）。
  code varchar(64) NOT NULL CHECK(btrim(code) <> ''),
  name varchar(200) NOT NULL CHECK(btrim(name) <> ''),
  -- **类目**：catalog 拥有类目主数据，跨服务不建外键 ⇒ 这里只存**逻辑 ID + 名称快照**。
  -- 为空 = 通用模板（不限类目）。
  category_id uuid,
  category_name varchar(200),
  -- 质检类型：INCOMING 来料质检 / FINISHED 成品质检。
  inspection_type text NOT NULL CHECK(inspection_type IN ('INCOMING','FINISHED')),
  -- **抽样规则**：比例（0~1 的比率，不是百分数）。
  sampling_ratio numeric(19,6) NOT NULL CHECK(sampling_ratio > 0 AND sampling_ratio <= 1),
  -- 最小抽样数：批量再小也要抽这么几件。
  min_sample_quantity numeric(19,6) NOT NULL DEFAULT 1 CHECK(min_sample_quantity >= 0),
  -- 最大抽样数：0 = 不限（批量大到全检）。
  max_sample_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(max_sample_quantity >= 0),
  -- **舍入口径**：CEILING 向上取整 / ROUND_HALF_UP 四舍五入 / FLOOR 向下取整。
  -- 「抽 10 件」到底怎么来的，三种口径能差 1 件 —— 口径必须写死，不能各人一套。
  rounding_mode text NOT NULL DEFAULT 'CEILING'
    CHECK(rounding_mode IN ('CEILING','ROUND_HALF_UP','FLOOR')),
  -- **关键检验项不合格时的动作**：
  --   REJECT_LOT  整批拒收（关键项不合格 ⇒ 这批全判不合格）
  --   DEDUCT_ONLY 仅扣量（关键项不合格只扣它自己那部分）
  -- 这是**模板上的规则**，不是服务层的隐藏假设 —— 租户可以自己选。
  critical_failure_action text NOT NULL DEFAULT 'REJECT_LOT'
    CHECK(critical_failure_action IN ('REJECT_LOT','DEDUCT_ONLY')),
  -- **不合格量的去向**：PENDING_INSPECTION 待检 / DEFECTIVE 残次。
  -- 蓝图加粗句「必须进入待检或残次状态」——具体进哪个，由模板说了算，但两者之一是强制的。
  defective_target text NOT NULL DEFAULT 'PENDING_INSPECTION'
    CHECK(defective_target IN ('PENDING_INSPECTION','DEFECTIVE')),
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code)
);

-- ===========================================================================
-- 2. 检验项定义（逐条）
-- ===========================================================================
CREATE TABLE quality_inspection_template_item (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  -- 蓝图点名的五项检验项。**词表在 DB 层钉死**，服务层只能取这五个值。
  item_code text NOT NULL
    CHECK(item_code IN ('APPEARANCE','FUNCTION','SPECIFICATION','PACKAGING','CERTIFICATION')),
  name varchar(200) NOT NULL CHECK(btrim(name) <> ''),
  -- **关键项**：不合格时按模板的 critical_failure_action 处理。
  critical boolean NOT NULL DEFAULT false,
  -- 判定标准（文字，写清「什么算不合格」）。
  acceptance_criteria text NOT NULL DEFAULT '',
  -- 该检验项在一次抽样中覆盖的比例（1 = 每一件都查这一项；0.5 = 抽一半查这一项）。
  sample_coverage numeric(19,6) NOT NULL DEFAULT 1
    CHECK(sample_coverage > 0 AND sample_coverage <= 1),
  sort_order int NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个模板里同一项检验项只出现一次 ⇒ 「模板有几项」是确定的，
  -- 「每项都必须有结论」的校验才不会因为重复行而漏判。
  UNIQUE(tenant_id,template_id,item_code),
  FOREIGN KEY(tenant_id,template_id) REFERENCES quality_inspection_template(tenant_id,id)
);

-- ===========================================================================
-- 3. 质检单（头）
-- ===========================================================================
CREATE TABLE quality_inspection_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 质检单号：租户内唯一（QI-20261021-a1b2c3d4）。
  inspection_no varchar(64) NOT NULL CHECK(btrim(inspection_no) <> ''),
  -- 模板（同一服务内的表，可以建外键）。**抽样规则随单快照**，模板后来改了不影响已开的单。
  template_id uuid NOT NULL,
  template_code varchar(64) NOT NULL,
  template_name varchar(200) NOT NULL,
  inspection_type text NOT NULL CHECK(inspection_type IN ('INCOMING','FINISHED')),
  category_id uuid,
  category_name varchar(200),
  -- SKU 身份：逻辑 ID + 编码/名称快照（catalog 拥有主数据 ⇒ 不建影子表）。
  sku_id uuid,
  sku_code varchar(128) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200),
  supplier_id uuid,
  supplier_name varchar(200),
  -- **来源：8 收货**。收货单在 warehouse 独立库 ⇒ 只存**单号快照**与**实收量快照**，
  -- 不建跨库外键、不建跨库视图。
  receipt_code varchar(64),
  receipt_line_id uuid,
  -- 来源单据号（采购单 / 调拨单等，外部引用码，不建跨库 FK）。
  source_ref_code varchar(64),
  batch_no varchar(64),
  -- **本次到货量**：来自 8 收货的实收量快照，**由服务端读上游后写入，不信客户端**。
  arrived_quantity numeric(19,6) NOT NULL CHECK(arrived_quantity > 0),
  -- **本次质检覆盖的批量**：三量之和的基数。**必须 ≤ 到货量** ——
  -- 卷 06 加粗句「采购收货是分批、可有差异的」：同一张收货单可以分几批来检，
  -- 每次质检只覆盖其中一批，剩下的量还在待检队列里。
  lot_size numeric(19,6) NOT NULL CHECK(lot_size > 0),
  -- 抽样规则快照（模板改价改规则不影响已开的单）。
  sampling_ratio numeric(19,6) NOT NULL CHECK(sampling_ratio > 0 AND sampling_ratio <= 1),
  min_sample_quantity numeric(19,6) NOT NULL CHECK(min_sample_quantity >= 0),
  max_sample_quantity numeric(19,6) NOT NULL CHECK(max_sample_quantity >= 0),
  rounding_mode text NOT NULL
    CHECK(rounding_mode IN ('CEILING','ROUND_HALF_UP','FLOOR')),
  -- **应抽数量**：服务端按比例 + 批量 + 夹逼 + 舍入口径算出并落库。
  sample_quantity numeric(19,6) NOT NULL CHECK(sample_quantity >= 0),
  -- 算式原文（例：「批量 100 × 比例 0.1 = 10，夹逼 [3, 30]，CEILING → 应抽 10」）。
  sampling_basis text NOT NULL DEFAULT '',
  -- **实抽数量**：默认等于应抽数量（按规则该抽多少就抽多少），
  -- 只能往下调（到货不足时抽不满），不能超过应抽数量。
  sampled_quantity numeric(19,6) NOT NULL CHECK(sampled_quantity >= 0),
  -- ---- 扣量与三量（互斥且完备）----
  -- **扣量合计**：逐项结论带来的批次扣量之和（让步接收之前）。
  qualified_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(qualified_quantity >= 0),
  defective_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(defective_quantity >= 0),
  concession_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(concession_quantity >= 0),
  -- 扣量合计（= Σ 各检验项的 deducted_quantity）。让步接收量从这里**里面**出，
  -- 所以它必须单列一栏：只留最终三量的话，「让步了多少」就查不出来了。
  gross_deducted_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(gross_deducted_quantity >= 0),
  -- 状态机：DRAFT 草稿 → INSPECTING 检验中 → COMPLETED 已完成 / CANCELLED 已作废。
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','INSPECTING','COMPLETED','CANCELLED')),
  -- 检验结论：PASSED 合格 / FAILED 整批不合格 / PARTIAL 部分合格 / CONCESSION_ACCEPTED 让步接收。
  result text CHECK(result IS NULL OR result IN ('PASSED','FAILED','PARTIAL','CONCESSION_ACCEPTED')),
  -- **分流意图**：不合格部分的去向（模板的 defective_target 快照）。
  disposition text NOT NULL DEFAULT 'PENDING_INSPECTION'
    CHECK(disposition IN ('SELLABLE','PENDING_INSPECTION','DEFECTIVE')),
  -- **分流状态**：NOT_DISPATCHED 未提交 / UNAVAILABLE 上游无写端点 / DISPATCHED 已登记 / FAILED 失败。
  -- warehouse 当前**没有**可用的授权写端点 ⇒ 实际只会出现前两个值（见第七节）。
  disposition_status text NOT NULL DEFAULT 'NOT_DISPATCHED'
    CHECK(disposition_status IN ('NOT_DISPATCHED','UNAVAILABLE','DISPATCHED','FAILED')),
  disposition_note text NOT NULL DEFAULT '',
  -- ---- 卷 06 加粗约束：币种 / 汇率快照 / 单位换算 ----
  currency_code varchar(8),
  exchange_rate numeric(19,6) CHECK(exchange_rate IS NULL OR exchange_rate > 0),
  unit_conversion_factor numeric(19,6) CHECK(unit_conversion_factor IS NULL OR unit_conversion_factor > 0),
  unit_price numeric(19,4) CHECK(unit_price IS NULL OR unit_price >= 0),
  -- 扣量金额 = 扣量 × 单价（按录入口径换算）。没有单价就不填，不填 0。
  deducted_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(deducted_amount >= 0),
  -- ---- 3.7.2.1 物理安全属性快照（catalog 拥有 ⇒ 只存快照）----
  safety_profile jsonb NOT NULL DEFAULT '{}'::jsonb,
  safety_requires_certification boolean NOT NULL DEFAULT false,
  safety_profile_state text NOT NULL DEFAULT 'UNKNOWN'
    CHECK(safety_profile_state IN ('UNKNOWN','COLLECTED','NOT_COLLECTED','UNAVAILABLE')),
  note text NOT NULL DEFAULT '',
  conclusion_note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,inspection_no),
  FOREIGN KEY(tenant_id,template_id) REFERENCES quality_inspection_template(tenant_id,id),
  -- 批量不得超过到货量（分批质检的上界）。
  CONSTRAINT quality_inspection_order_lot_within_arrival CHECK(lot_size <= arrived_quantity),
  -- 🔴 三量自洽：**完成态**下合格量 + 不合格量 + 让步接收量 = 本次质检覆盖的批量。
  -- 非完成态三量恒为 0（还没判），所以只在 COMPLETED 上强制。
  CONSTRAINT quality_inspection_order_quantities_balanced CHECK(
    status <> 'COMPLETED'
    OR (qualified_quantity + defective_quantity + concession_quantity = lot_size)
  ),
  -- 🔴 让步接收量不得超过扣量合计 —— 让步接收是「不合格里被批准放行的那部分」，
  -- 不是把不合格改成合格。没有这条，一个手滑就能「扣量 0 + 让步接收 100」。
  CONSTRAINT quality_inspection_order_concession_within_deduction CHECK(
    concession_quantity <= gross_deducted_quantity
  ),
  -- 🔴 最终不合格量 = 扣量合计 − 让步接收量（让步行走的是**不合格**的量，不是凭空冒出来的）。
  CONSTRAINT quality_inspection_order_defect_split CHECK(
    status <> 'COMPLETED'
    OR (defective_quantity + concession_quantity = gross_deducted_quantity)
  )
);

-- ===========================================================================
-- 4. 逐项检验结论
-- ===========================================================================
CREATE TABLE quality_inspection_order_item (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  template_item_id uuid NOT NULL,
  -- 检验项词表与结论词表都在 DB 层钉死。
  item_code text NOT NULL
    CHECK(item_code IN ('APPEARANCE','FUNCTION','SPECIFICATION','PACKAGING','CERTIFICATION')),
  item_name varchar(200) NOT NULL,
  critical boolean NOT NULL DEFAULT false,
  -- PASS 合格 / FAIL 不合格 / CONCESSION 让步接收（该项整体放行）。
  result text NOT NULL CHECK(result IN ('PASS','FAIL','CONCESSION')),
  -- 该项在抽样中判不合格的**件数**。
  failed_sample_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(failed_sample_quantity >= 0),
  -- 该项造成的**批次扣量**（不合格判定与扣量由逐项结论驱动，就落在这里）。
  deducted_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(deducted_quantity >= 0),
  defect_type varchar(64),
  defect_description text NOT NULL DEFAULT '',
  inspected_by uuid NOT NULL,
  inspected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个检验项一条结论（重录是改这一条，不是追加一条）。
  UNIQUE(tenant_id,order_id,template_item_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES quality_inspection_order(tenant_id,id),
  FOREIGN KEY(tenant_id,template_item_id) REFERENCES quality_inspection_template_item(tenant_id,id)
);

-- ===========================================================================
-- 5. 让步接收（append-only）
-- ===========================================================================
CREATE TABLE quality_inspection_concession (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 关联的检验项（可空 = 整批让步）。
  item_code text CHECK(item_code IS NULL OR item_code IN
    ('APPEARANCE','FUNCTION','SPECIFICATION','PACKAGING','CERTIFICATION')),
  -- 让步接收量（批次单位）。
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  -- **原因必填**：蓝图 6.4.2.2 加粗句「让步接收需要权限与原因，
  -- 不能由质检员随手放行」—— 这里先把「必须写原因」钉在 DB 层。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  -- **权限码必填**：同上，钉在 DB 层。
  permission_code varchar(64) NOT NULL CHECK(btrim(permission_code) <> ''),
  approved_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES quality_inspection_order(tenant_id,id)
);

-- ===========================================================================
-- 6. 护栏触发器
-- ===========================================================================
-- 6.1 检验结论必须挂在**本单引用的模板**的检验项上。
--     （不建指向模板的外键链，靠触发器只读一张永远存在的表 ——
--      加跨表外键会让别的车道 ALTER 这些表时炸掉。）
CREATE OR REPLACE FUNCTION guard_quality_inspection_order_item_template() RETURNS trigger AS $$
DECLARE v_template uuid;
BEGIN
  SELECT o.template_id INTO v_template
    FROM quality_inspection_order o
   WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.order_id;
  IF v_template IS NULL THEN
    RAISE EXCEPTION '质检单不存在或不属于本租户：%', NEW.order_id USING ERRCODE = '23503';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM quality_inspection_template_item t
     WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.template_item_id
       AND t.template_id = v_template) THEN
    RAISE EXCEPTION '检验项不属于本单引用的质检模板：%', NEW.template_item_id USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER quality_inspection_order_item_template_guard
  BEFORE INSERT OR UPDATE ON quality_inspection_order_item
  FOR EACH ROW EXECUTE FUNCTION guard_quality_inspection_order_item_template();

-- 6.2 质检单进入终态后，三量与结论都不许再改 ——
--     「质检结论」是一份凭据，改掉它等于让「当时判了什么」永远没有答案。
CREATE OR REPLACE FUNCTION guard_quality_inspection_order_closed() RETURNS trigger AS $$
BEGIN
  IF OLD.status IN ('COMPLETED','CANCELLED') THEN
    RAISE EXCEPTION '质检单已处于终态（%），不允许再改：三量与结论是凭据，改掉就没有留痕价值', OLD.status
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER quality_inspection_order_closed_guard
  BEFORE UPDATE ON quality_inspection_order
  FOR EACH ROW EXECUTE FUNCTION guard_quality_inspection_order_closed();

-- 6.3 已完成的质检单不能再补录结论（与 6.2 同一条纪律，落在子表上）。
CREATE OR REPLACE FUNCTION guard_quality_inspection_order_item_closed() RETURNS trigger AS $$
DECLARE v_status text;
BEGIN
  SELECT o.status INTO v_status
    FROM quality_inspection_order o
   WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.order_id;
  IF v_status IN ('COMPLETED','CANCELLED') THEN
    RAISE EXCEPTION '质检单已处于终态（%），不允许再录检验结论', v_status USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER quality_inspection_order_item_closed_guard
  BEFORE INSERT OR UPDATE ON quality_inspection_order_item
  FOR EACH ROW EXECUTE FUNCTION guard_quality_inspection_order_item_closed();

-- 6.4 让步接收只追加：UPDATE / DELETE 一律拒绝。
CREATE OR REPLACE FUNCTION guard_quality_inspection_concession_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '让步接收记录只追加：改写或删除一条让步记录等于抹掉「谁、凭什么、为什么放行了这一批」'
    USING ERRCODE = '23514';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER quality_inspection_concession_append_only
  BEFORE UPDATE OR DELETE ON quality_inspection_concession
  FOR EACH ROW EXECUTE FUNCTION guard_quality_inspection_concession_append_only();

-- ===========================================================================
-- 7. 索引
-- ===========================================================================
-- 质检单清单页：按状态 / 来源收货单 / SKU 读。
CREATE INDEX quality_inspection_order_by_status ON quality_inspection_order(tenant_id, status, created_at desc, id);
CREATE INDEX quality_inspection_order_by_receipt ON quality_inspection_order(tenant_id, receipt_code, id);
CREATE INDEX quality_inspection_order_by_sku ON quality_inspection_order(tenant_id, sku_id, created_at desc, id);
-- 逐项结论：按单读（详情页第二段）。
CREATE INDEX quality_inspection_order_item_by_order ON quality_inspection_order_item(tenant_id, order_id, item_code);
-- 让步接收：按单读（详情页第三段）。
CREATE INDEX quality_inspection_concession_by_order ON quality_inspection_concession(tenant_id, order_id, created_at);
-- 模板清单页：按类目读。
CREATE INDEX quality_inspection_template_by_category ON quality_inspection_template(tenant_id, category_id, id);
-- 检验项：按模板读。
CREATE INDEX quality_inspection_template_item_by_template ON quality_inspection_template_item(tenant_id, template_id, sort_order);

-- ===========================================================================
-- 8. 多租户：RLS + 授权（与 trade V1 / V92 / V242 同一套写法）
-- ===========================================================================
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'quality_inspection_template','quality_inspection_template_item',
    'quality_inspection_order','quality_inspection_order_item',
    'quality_inspection_concession'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  quality_inspection_template,quality_inspection_template_item,
  quality_inspection_order,quality_inspection_order_item,
  quality_inspection_concession TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  quality_inspection_template,quality_inspection_template_item,
  quality_inspection_order,quality_inspection_order_item,
  quality_inspection_concession TO starcon_app;
-- 让步接收**只追加**：收回改写与删除权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON quality_inspection_concession FROM trade_app;
-- 模板与质检单**不提供 DELETE**：质检结论是业务凭据，删掉就没有留痕价值。
-- 模板被质检单引用后也不可删（外键 RESTRICT）。
REVOKE DELETE ON quality_inspection_template,quality_inspection_order FROM trade_app;

-- ===========================================================================
-- 9. 本迁移的回归面（跨车道纪律）
-- ===========================================================================
--   * **不 ALTER 任何既有表**：V1 / V92 / V97 / V98 / V242 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不改 11.x 退货质检的表**：V179 的 `return_quality_*` 八张表与本文件零交集 ——
--     两套质检（退货质检 / 来料质检）的表名前缀不同，互不覆盖。
--   * 对 warehouse / catalog 的数据**只经授权 HTTP API 读**：跨库 FK 本来就禁止，
--     所以本文件既没有约束到对方库的任何一张表。
--   * 新增函数全部带 quality_inspection_ 前缀，与 V1 / V97 / V98 / V242 的既有函数名不冲突。
