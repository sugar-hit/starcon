-- 6.6.2.1 试产单：管理试产 —— 小批量试产（数量、BOM 版本、组件需求、产线/加工厂、
-- 质检标准、试产成本与良率），试产结果记录并对比量产目标，输出转量产建议。
--
-- 蓝图 06-purchase-and-supply.md 6.6.2.1 原文：
--   职责：管理试产：小批量试产（数量、BOM 版本、组件需求、产线/加工厂、质检标准、试产成本与良率），
--         试产结果记录并对比量产目标，输出转量产建议。
--   输入：试产申请（BOM 版本、目标数量）；质检方案；成本数据。
--   输出：试产单 + 组件领用需求（→ 7 库存）；良率与成本结果；转量产建议。
--   关系：上游 = 6.6.1.2、3.5.2.1 BOM；下游 = 6.6.2.2、8.4.1 组装/加工。
--         **试产消耗真实组件库存，需走 8 加工单而非直接改库存**（实体目录 §6）。
--
-- 归属：**trade 服务**（卷 06 后端主责服务 = trade + catalog（party），取列表第一项 = trade）。
-- 后端命名空间 /trade/trial-productions；前端路由 /purchase/development/trials。
--
-- ===========================================================================
-- 一、模块边界：本文件**不碰任何既有表**，也不碰 catalog / warehouse 的库
-- ===========================================================================
--   * **3.5.2.1 BOM 版本（catalog 独立库，已交付 V25）**：
--     `bom_version` / `bom_component` 在 **catalog 服务的库**里 ⇒ 本模块**零跨库 FOREIGN KEY、
--     零跨库视图、零跨服务直连查询**。BOM 版本引用一律 **逻辑引用（uuid）+ 展示快照**
--     （`bom_version_no` / `bom_code` / `output_sku_code`），跨服务只经 catalog 的授权 HTTP 读
--     （trade 侧既有 `BomVersionPort`，catalog 侧 `GET /catalog/bom-versions/{id}` 与
--     `GET /catalog/bom-versions/{id}/requirement`）。
--   * **8.4.1 组装单（warehouse 独立库，已交付 V103，`assembly_order` / `assembly_order_component`）**：
--     🔴🔴 蓝图加粗句「**试产消耗真实组件库存，需走 8 加工单而非直接改库存**」。
--     ⇒ 本模块**只产出组件领用需求**（组件清单 + 需求量 + 关联试产单），
--     **绝对不建「库存扣减表」**、**绝对不改 warehouse 的库存**。
--     本表 `production_trial_component_requirement` 是**需求**（一个需求量字段），
--     **不是库存台账**：它没有期初 / 入库 / 出库 / 结存任何一列，也没有任何库存数量被增减。
--     真正领用由 warehouse 侧 8.4.1 组装单承接；跨服务衔接留待**授权 API / event**。
--     本模块对 8.4.1 的引用是**逻辑引用（uuid）+ 展示快照**（`assembly_order_no`），
--     **不建跨库 FK、不跨库查、不跨库写**。
--   * **6.6.1.2 打样与样品管理（trade 服务，同一个库，已交付 V261）**：
--     `development_sample_order` 是**它的**事实 ⇒ 本模块**只读引用 + 快照**，
--     **不建指向它的外键、不加约束、不 ALTER**。
--   * **6.6.2.2 转量产与建档（未开发）** ⇒ 本模块产出的是**转量产建议事实**
--     （建议结论 + 建议文本 + 生成条件 + 生成时的实测快照），
--     **不是**真的去建 SKU（SKU 建档归 3.1.2.1 与 6.6.2.2），
--     **不建 SKU 表、不调商品服务写接口**。
--   * **7 库存**：库存归 7 卷（warehouse），本模块**只向下游出需求**，不持有库存事实。
--
-- ===========================================================================
-- 二、八张业务表的职责划分
-- ===========================================================================
--   1. `production_trial_order` —— **试产单（一次试产活动）**：编号、BOM 版本引用与快照、
--      目标数量、产线/加工厂、质检标准引用、状态、币种、申请人、计划起止时间。
--   2. `production_trial_order_transition` —— 试产单**状态迁移事件**（不可改写的留痕）。
--   3. `production_trial_component_requirement` —— **组件领用需求**（组件清单 + 需求量 +
--      关联试产单）。🔴 **这是需求，不是库存**：不扣减、不结存。
--   4. `production_trial_inspection_item` —— **质检标准项**（质检方案）+ 该项的判定结果。
--   5. `production_trial_result` —— **试产结果**（产出 / 合格 / 不良数量 + **良率**）。
--   6. `production_trial_cost` —— **试产成本头**（一个试产单一张，**一个币种**）+
--      **量产目标成本**（同一币种，可为空 = 目标成本取不到）。
--   7. `production_trial_cost_line` —— **试产成本明细行**。
--   8. `production_trial_mass_production_recommendation` —— **转量产建议**（建议结论 +
--      建议文本 + 生成条件 + 生成时的实测快照）。
--
-- ===========================================================================
-- 三、🔴 基数与「一单一币种」纪律
-- ===========================================================================
--   * **1 张试产单 : N 条组件领用需求 : N 条质检标准项 : 1 条试产结果 : 1 张成本头**。
--     结果与成本头都是**一单一份**（UNIQUE(tenant_id, trial_order_id)）——
--     「一次试产有两个良率」是说不通的。
--   * **一张试产单一个币种**：成本明细行的币种必须与成本头一致（触发器钉死）。
--     **不同币种的数绝不相加。**
--
-- ===========================================================================
-- 四、状态机（服务层 + 数据库层**两处**都钉死）
-- ===========================================================================
--   试产单：REQUESTED 已申请 → SCHEDULED 已排产 → IN_PROGRESS 试产中
--           → COMPLETED 已完成 → CLOSED 已关闭；
--           任意非终态可 CANCELLED 已取消。**CLOSED / CANCELLED 是终态**（不提供「恢复」）。
--
--   🔴 **进入「试产中」前，组件领用需求必须已经提交**（DRAFT 不许开产）——
--      蓝图加粗句「试产消耗真实组件库存，需走 8 加工单而非直接改库存」的落点：
--      没有领用需求就开产，等于凭空消耗了一批组件而没人知道该领什么。
--   🔴 **标记为「已完成」前必须有试产结果**（良率），且**已登记的质检标准项必须全部判定完毕**。
--   🔴 **每次状态迁移都要落一条不可改写的事件**（谁、什么时候、从哪到哪、为什么）。
--
-- ===========================================================================
-- 五、单位与时间纪律
-- ===========================================================================
--   * 金额一律 `numeric(19,4)`（**禁浮点**：Java 侧 BigDecimal）。
--   * 数量与良率一律 `numeric(19,6)`。**良率是比例不是百分数**（0.950000 = 95%）。
--   * 时间列一律 `timestamptz`；Java 侧用 `OffsetDateTime` 绑定，
--     SQL 的 values 位置写 `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime...)`
--     会静默偏 −28800s）。
--
-- ===========================================================================
-- 1. 试产单（一次试产活动）
-- ===========================================================================
CREATE TABLE production_trial_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- **试产单号**：租户内唯一、可读（TP-20261021-a1b2c3d4）。
  trial_no varchar(64) NOT NULL CHECK(btrim(trial_no) <> ''),
  -- ---------------------------------------------------------------------
  -- 上游 3.5.2.1 BOM 版本（**catalog 独立库**）：逻辑引用 + 展示快照，零跨库 FK
  -- ---------------------------------------------------------------------
  bom_version_id uuid NOT NULL,
  -- 快照：catalog 改名 / 重新发布后仍能显示「当时用的是哪一版」。
  bom_version_no varchar(32) NOT NULL CHECK(btrim(bom_version_no) <> ''),
  bom_code varchar(64) NOT NULL CHECK(btrim(bom_code) <> ''),
  output_sku_code varchar(64) NOT NULL DEFAULT '',
  output_sku_name varchar(200) NOT NULL DEFAULT '',
  -- ---------------------------------------------------------------------
  -- 上游 6.6.1.2 打样与样品管理（**同一个 trade 库**，V261）：只读引用 + 快照
  -- ---------------------------------------------------------------------
  development_sample_order_id uuid,
  development_sample_order_no varchar(64) NOT NULL DEFAULT '',
  -- ---------------------------------------------------------------------
  -- 试产规模与目标
  -- ---------------------------------------------------------------------
  -- **目标数量**（蓝图输入「试产申请（BOM 版本、目标数量）」）。
  target_quantity numeric(19,6) NOT NULL CHECK(target_quantity > 0),
  -- 产线 / 加工厂（蓝图职责句点名的「产线/加工厂」）。
  production_line_code varchar(64) NOT NULL DEFAULT '',
  production_line_name varchar(200) NOT NULL DEFAULT '',
  factory_code varchar(64) NOT NULL DEFAULT '',
  factory_name varchar(200) NOT NULL DEFAULT '',
  -- 质检标准摘要（蓝图职责句点名的「质检标准」；逐项在 production_trial_inspection_item）。
  quality_standard_code varchar(64) NOT NULL DEFAULT '',
  quality_standard_note text NOT NULL DEFAULT '',
  -- ---------------------------------------------------------------------
  -- 状态：五个，单向推进（服务层 + DB 守卫两处钉死）
  -- ---------------------------------------------------------------------
  status varchar(24) NOT NULL DEFAULT 'REQUESTED'
    CHECK(status IN ('REQUESTED','SCHEDULED','IN_PROGRESS','COMPLETED','CLOSED','CANCELLED')),
  -- ---------------------------------------------------------------------
  -- 币种：一张单一个币种
  -- ---------------------------------------------------------------------
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  requester_id uuid NOT NULL,
  requester_name varchar(100) NOT NULL DEFAULT '',
  requested_at timestamptz NOT NULL,
  planned_start_at timestamptz,
  planned_end_at timestamptz,
  -- 试产目的说明（自由文本：这一轮要验证什么）。
  purpose_note text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, trial_no),
  -- 🔴 挂了打样单就得有打样单编号的快照（只挂一个裸 id，界面上显示什么？）。
  CONSTRAINT production_trial_order_sample_snapshot CHECK(
    development_sample_order_id IS NULL OR btrim(development_sample_order_no) <> ''
  )
);

-- ===========================================================================
-- 2. 试产单状态迁移事件（不可改写的留痕）
-- ===========================================================================
CREATE TABLE production_trial_order_transition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  trial_order_id uuid NOT NULL,
  from_status varchar(24) NOT NULL
    CHECK(from_status IN ('REQUESTED','SCHEDULED','IN_PROGRESS','COMPLETED','CLOSED','CANCELLED')),
  to_status varchar(24) NOT NULL
    CHECK(from_status IN ('REQUESTED','SCHEDULED','IN_PROGRESS','COMPLETED','CLOSED','CANCELLED')),
  -- **为什么**（必填）：一句「推进一下」不是留痕。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  actor_id uuid NOT NULL,
  actor_name varchar(100) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,
  -- 由状态守卫触发器写入：这条事件已经被哪次状态变更用掉了。
  -- 插入时恒为 NULL。
  applied_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, trial_order_id)
    REFERENCES production_trial_order(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT production_trial_order_transition_distinct CHECK(from_status <> to_status)
);

-- ===========================================================================
-- 3. 组件领用需求（🔴 **需求**，不是库存 —— 不扣减、不结存）
-- ===========================================================================
CREATE TABLE production_trial_component_requirement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  trial_order_id uuid NOT NULL,
  -- 行号（组件清单内的序号，租户内单据内唯一）。
  line_no integer NOT NULL CHECK(line_no > 0),
  -- ---------------------------------------------------------------------
  -- 组件（catalog 独立库）：逻辑引用 + 展示快照，零跨库 FK
  -- ---------------------------------------------------------------------
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  spec_snapshot varchar(200) NOT NULL DEFAULT '',
  -- ---------------------------------------------------------------------
  -- 需求量（口径沿用 catalog 规则 R6/R7：单位用量 × 产量 × (1 + 损耗率/100)）
  -- ---------------------------------------------------------------------
  unit_quantity numeric(19,6) NOT NULL CHECK(unit_quantity >= 0),
  -- 损耗率是**百分数**（5 = 5%），与 catalog 口径一致。
  scrap_rate numeric(7,4) NOT NULL DEFAULT 0 CHECK(scrap_rate >= 0),
  required_quantity numeric(19,6) NOT NULL CHECK(required_quantity >= 0),
  unit varchar(16) NOT NULL DEFAULT '',
  -- ---------------------------------------------------------------------
  -- 领用需求状态
  -- ---------------------------------------------------------------------
  -- DRAFT 草稿（需求已算出，尚未提交领用）
  -- SUBMITTED 已提交领用需求（🔴 本模块的产出**到此为止**：真实领用由 8.4.1 组装单承接）
  -- RELEASED 已由加工单承接（记录 warehouse 组装单的逻辑引用 + 快照）
  -- CANCELLED 已取消
  requirement_status varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(requirement_status IN ('DRAFT','SUBMITTED','RELEASED','CANCELLED')),
  -- ---------------------------------------------------------------------
  -- 8.4.1 组装单（warehouse 独立库）：**逻辑引用 + 展示快照**，零跨库 FK、零跨库写
  -- ---------------------------------------------------------------------
  assembly_order_ref_id uuid,
  assembly_order_no varchar(64) NOT NULL DEFAULT '',
  -- 领用需求备注。
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, trial_order_id, line_no),
  FOREIGN KEY(tenant_id, trial_order_id)
    REFERENCES production_trial_order(tenant_id, id) ON DELETE RESTRICT,
  -- 🔴 「已由加工单承接」就必须给出加工单的逻辑引用 + 展示快照，
  --    否则这条需求的下游是谁就答不出来。
  CONSTRAINT production_trial_component_requirement_release_snapshot CHECK(
    requirement_status <> 'RELEASED'
    OR (assembly_order_ref_id IS NOT NULL AND btrim(assembly_order_no) <> '')
  )
);

-- ===========================================================================
-- 4. 质检标准项（质检方案）+ 该项的判定结果
-- ===========================================================================
CREATE TABLE production_trial_inspection_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  trial_order_id uuid NOT NULL,
  item_code varchar(64) NOT NULL CHECK(btrim(item_code) <> ''),
  item_name varchar(200) NOT NULL CHECK(btrim(item_name) <> ''),
  -- 标准值 / 检验方法 / 单位（质检方案的三个要素）。
  standard_value varchar(200) NOT NULL DEFAULT '',
  inspection_method varchar(200) NOT NULL DEFAULT '',
  unit varchar(16) NOT NULL DEFAULT '',
  -- 是否关键项（关键项不合格 ⇒ 试产不通过）。
  critical_item boolean NOT NULL DEFAULT false,
  sort_order integer NOT NULL DEFAULT 0,
  -- ---------------------------------------------------------------------
  -- 判定结果
  -- ---------------------------------------------------------------------
  -- PENDING 待判定 / PASS 合格 / FAIL 不合格
  judgment varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(judgment IN ('PENDING','PASS','FAIL')),
  measured_value varchar(200) NOT NULL DEFAULT '',
  judged_at timestamptz,
  judged_by uuid,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, trial_order_id, item_code),
  FOREIGN KEY(tenant_id, trial_order_id)
    REFERENCES production_trial_order(tenant_id, id) ON DELETE RESTRICT,
  -- 🔴 判定过的项必须留下判定时刻与判定人（谁判的、什么时候判的）。
  CONSTRAINT production_trial_inspection_item_judged_trace CHECK(
    judgment = 'PENDING' OR (judged_at IS NOT NULL AND judged_by IS NOT NULL)
  )
);

-- ===========================================================================
-- 5. 试产结果（**一单一份**：产出 / 合格 / 不良数量 + 良率）
-- ===========================================================================
CREATE TABLE production_trial_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  trial_order_id uuid NOT NULL,
  -- 产出数量（试产出来的总数）。
  output_quantity numeric(19,6) NOT NULL CHECK(output_quantity > 0),
  -- 合格数量。
  qualified_quantity numeric(19,6) NOT NULL CHECK(qualified_quantity >= 0),
  -- 不良数量：**由触发器按 产出 − 合格 推导**，客户端报不上来。
  defective_quantity numeric(19,6) NOT NULL CHECK(defective_quantity >= 0),
  -- 🔴 **良率是比例，不是百分数**（0.950000 = 95%），**由触发器按 合格 ÷ 产出 推导**。
  yield_rate numeric(19,6) NOT NULL CHECK(yield_rate >= 0 AND yield_rate <= 1),
  -- 试产结论（自由文本：这轮试产说明了什么）。
  conclusion text NOT NULL DEFAULT '',
  recorded_at timestamptz NOT NULL,
  recorded_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, trial_order_id),
  FOREIGN KEY(tenant_id, trial_order_id)
    REFERENCES production_trial_order(tenant_id, id) ON DELETE RESTRICT
);

-- ===========================================================================
-- 6. 试产成本头（**一个试产单一张、一个币种**）+ 量产目标成本
-- ===========================================================================
CREATE TABLE production_trial_cost (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  trial_order_id uuid NOT NULL,
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 试产成本合计：**由触发器按明细行自动维护**，客户端报不上来。
  total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_amount >= 0),
  -- ---------------------------------------------------------------------
  -- 量产目标成本（**与本行同一币种**）
  -- ---------------------------------------------------------------------
  -- 🔴 NULL = 「量产目标成本**取不到**」（不是 0）。**跨币种绝不做固定汇率折算**：
  --    「试产成本 vs 量产目标成本」只在**同一币种**内可比，跨币种按币种分组。
  mass_target_cost numeric(19,4) CHECK(mass_target_cost >= 0),
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, trial_order_id),
  FOREIGN KEY(tenant_id, trial_order_id)
    REFERENCES production_trial_order(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT production_trial_cost_amount_scale CHECK(total_amount = round(total_amount, 4))
);

-- ===========================================================================
-- 7. 试产成本明细行
-- ===========================================================================
CREATE TABLE production_trial_cost_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  cost_id uuid NOT NULL,
  trial_order_id uuid NOT NULL,
  -- 成本类型（封闭五值，**无「其它」**：「其它」桶会让「钱花在哪」永远答不出来）。
  cost_type varchar(24) NOT NULL
    CHECK(cost_type IN ('MATERIAL','PROCESSING','MOLD','INSPECTION','OVERHEAD')),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  -- 🔴 明细行的币种必须与成本头一致（**一张单一个币种**）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  description varchar(500) NOT NULL DEFAULT '',
  incurred_at timestamptz NOT NULL,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, cost_id) REFERENCES production_trial_cost(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY(tenant_id, trial_order_id)
    REFERENCES production_trial_order(tenant_id, id) ON DELETE RESTRICT
);

-- ===========================================================================
-- 8. 转量产建议（🔴 6.6.2.2 未开发 ⇒ 本表只是**建议事实**，不是 SKU 建档）
-- ===========================================================================
CREATE TABLE production_trial_mass_production_recommendation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  trial_order_id uuid NOT NULL,
  -- 建议结论：GO 建议转量产 / NO_GO 暂不建议转量产。
  decision varchar(16) NOT NULL CHECK(decision IN ('GO','NO_GO')),
  -- 建议文本（必填）：一句「转」不是建议。
  rationale text NOT NULL CHECK(btrim(rationale) <> ''),
  -- 生成条件（必填）：这次结论是**在什么门槛下**得出的。
  condition_note text NOT NULL CHECK(btrim(condition_note) <> ''),
  -- 生成条件里的两个门槛（比例，不是百分数）。
  min_yield_threshold numeric(19,6) NOT NULL CHECK(min_yield_threshold >= 0 AND min_yield_threshold <= 1),
  max_cost_variance_rate numeric(19,6) CHECK(max_cost_variance_rate IS NULL OR max_cost_variance_rate >= 0),
  -- 生成时的**实测快照**（事后 BOM / 成本变了也不改这条记录的观测值）。
  observed_yield_rate numeric(19,6) NOT NULL CHECK(observed_yield_rate >= 0 AND observed_yield_rate <= 1),
  observed_cost_variance_rate numeric(19,6),
  observed_trial_cost numeric(19,4),
  observed_mass_target_cost numeric(19,4),
  observed_currency varchar(3) NOT NULL CHECK(observed_currency ~ '^[A-Z]{3}$'),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, trial_order_id),
  FOREIGN KEY(tenant_id, trial_order_id)
    REFERENCES production_trial_order(tenant_id, id) ON DELETE RESTRICT,
  -- 🔴 成本偏差只在**同一币种**内可比：观察到成本偏差 ⇒ 必须同时留下试产成本与量产目标成本。
  CONSTRAINT production_trial_recommendation_cost_snapshot CHECK(
    observed_cost_variance_rate IS NULL
    OR (observed_trial_cost IS NOT NULL AND observed_mass_target_cost IS NOT NULL)
  )
);

-- ===========================================================================
-- 9. 守卫触发器（数据库层钉死「不变量」，服务层之外再挡一道）
-- ===========================================================================
-- 9.1 🔴 **试产结果守卫**：良率与不良数量**由数据库推导**。
--     客户端报上来的良率一律被覆盖 —— 「良率是算出来的，不是报出来的」。
--     产出必须 > 0；合格数量必须落在 [0, 产出]。
CREATE FUNCTION guard_production_trial_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.output_quantity IS NULL OR NEW.output_quantity <= 0 THEN
    RAISE EXCEPTION '试产产出数量必须大于 0（没有产出就没有良率可言）'
      USING ERRCODE='23514';
  END IF;
  IF NEW.qualified_quantity IS NULL OR NEW.qualified_quantity < 0
     OR NEW.qualified_quantity > NEW.output_quantity THEN
    RAISE EXCEPTION '试产合格数量必须落在 0 与产出数量之间（产出 %，合格 %）',
      NEW.output_quantity, NEW.qualified_quantity
      USING ERRCODE='23514';
  END IF;
  NEW.defective_quantity := NEW.output_quantity - NEW.qualified_quantity;
  -- 🔴 良率是**比例**（0.950000 = 95%），单次 HALF_UP 到 6 位小数。
  NEW.yield_rate := round(NEW.qualified_quantity / NEW.output_quantity, 6);
  RETURN NEW;
END $$;
CREATE TRIGGER production_trial_result_guard BEFORE INSERT OR UPDATE ON production_trial_result
  FOR EACH ROW EXECUTE FUNCTION guard_production_trial_result();

-- 9.2 🔴 **成本合计守卫**：合计金额由明细行自动维护，客户端报不上来。
CREATE FUNCTION guard_production_trial_cost_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_cost_id uuid;
  v_total numeric(19,4);
BEGIN
  v_cost_id := COALESCE(NEW.cost_id, OLD.cost_id);
  SELECT COALESCE(SUM(l.amount), 0) INTO v_total
    FROM production_trial_cost_line l
   WHERE l.tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
     AND l.cost_id = v_cost_id;
  UPDATE production_trial_cost
     SET total_amount = v_total, updated_at = now()
   WHERE tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
     AND id = v_cost_id;
  RETURN NULL;
END $$;
CREATE TRIGGER production_trial_cost_total_after
  AFTER INSERT OR UPDATE OR DELETE ON production_trial_cost_line
  FOR EACH ROW EXECUTE FUNCTION guard_production_trial_cost_total();

-- 9.3 🔴 **明细行币种一致性守卫**：明细行的币种必须与成本头一致
--     （**一张单一个币种** —— 不同币种的数绝不相加）。
CREATE FUNCTION guard_production_trial_cost_line_currency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_currency varchar(3);
BEGIN
  SELECT c.currency INTO v_currency
    FROM production_trial_cost c
   WHERE c.tenant_id = NEW.tenant_id
     AND c.id = NEW.cost_id;
  IF v_currency IS NULL THEN
    RAISE EXCEPTION '成本明细行找不到对应的成本头（cost_id=%）', NEW.cost_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.currency <> v_currency THEN
    RAISE EXCEPTION '成本明细行的币种（%）与成本头（%）不一致：一张试产单一个币种，不同币种的数绝不相加',
      NEW.currency, v_currency
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER production_trial_cost_line_currency_guard
  BEFORE INSERT OR UPDATE ON production_trial_cost_line
  FOR EACH ROW EXECUTE FUNCTION guard_production_trial_cost_line_currency();

-- 9.4 🔴 **试产单状态守卫**：数据库层钉死状态机。
--     * 只放行 REQUESTED → SCHEDULED → IN_PROGRESS → COMPLETED → CLOSED
--       与任意非终态 → CANCELLED；
--     * CLOSED / CANCELLED 是终态（没有出边，不提供「恢复」）；
--     * **每次状态变更都必须有一条对应的迁移事件**（谁 / 何时 / 从哪到哪 / 为什么）；
--     * **进入「试产中」前，组件领用需求必须已提交**（DRAFT 不许开产）；
--     * **标记为「已完成」前必须有试产结果**，且已登记的质检项必须全部判定完毕。
CREATE FUNCTION guard_production_trial_order_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_allowed boolean := false;
  v_event_id uuid;
  v_draft_requirements int;
  v_results int;
  v_pending_items int;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;

  v_allowed := CASE OLD.status
    WHEN 'REQUESTED'   THEN NEW.status IN ('SCHEDULED','CANCELLED')
    WHEN 'SCHEDULED'   THEN NEW.status IN ('IN_PROGRESS','CANCELLED')
    WHEN 'IN_PROGRESS' THEN NEW.status IN ('COMPLETED','CANCELLED')
    WHEN 'COMPLETED'   THEN NEW.status IN ('CLOSED')
    ELSE false
  END;
  IF NOT v_allowed THEN
    RAISE EXCEPTION '试产单状态不允许从 % 迁移到 %（已关闭 / 已取消是终态，不提供恢复）', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  SELECT t.id INTO v_event_id
    FROM production_trial_order_transition t
   WHERE t.tenant_id = NEW.tenant_id
     AND t.trial_order_id = NEW.id
     AND t.from_status = OLD.status
     AND t.to_status = NEW.status
     AND t.applied_at IS NULL
   ORDER BY t.created_at DESC, t.id DESC
   LIMIT 1;
  IF NOT FOUND THEN
    RAISE EXCEPTION '试产单状态变更缺少对应迁移事件（% → %）：每次状态迁移都必须落一条不可改写的记录', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  -- 开产前：组件领用需求必须已经提交（草稿状态不许开产）。
  IF NEW.status = 'IN_PROGRESS' THEN
    SELECT count(*) INTO v_draft_requirements
      FROM production_trial_component_requirement r
     WHERE r.tenant_id = NEW.tenant_id
       AND r.trial_order_id = NEW.id
       AND r.requirement_status = 'DRAFT';
    IF v_draft_requirements > 0 THEN
      RAISE EXCEPTION '还有 % 条组件领用需求未提交，不能开产：试产消耗真实组件库存，领用需求必须先提交', v_draft_requirements
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- 完成前：必须有试产结果（良率），且质检项必须全部判定完毕。
  IF NEW.status = 'COMPLETED' THEN
    SELECT count(*) INTO v_results
      FROM production_trial_result r
     WHERE r.tenant_id = NEW.tenant_id
       AND r.trial_order_id = NEW.id;
    IF v_results = 0 THEN
      RAISE EXCEPTION '试产单还没有试产结果（良率与产出 / 合格数量），不能标记为已完成'
        USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO v_pending_items
      FROM production_trial_inspection_item i
     WHERE i.tenant_id = NEW.tenant_id
       AND i.trial_order_id = NEW.id
       AND i.judgment = 'PENDING';
    IF v_pending_items > 0 THEN
      RAISE EXCEPTION '还有 % 项质检标准未判定，不能标记为已完成', v_pending_items
        USING ERRCODE='23514';
    END IF;
  END IF;

  UPDATE production_trial_order_transition SET applied_at = now() WHERE id = v_event_id;
  RETURN NEW;
END $$;
CREATE TRIGGER production_trial_order_status_guard BEFORE UPDATE ON production_trial_order
  FOR EACH ROW EXECUTE FUNCTION guard_production_trial_order_status();

-- 9.5 🔴 **留痕不可改写**：迁移事件、试产结果、转量产建议一经写入就不可 UPDATE / DELETE。
--     「这个试产经历过什么」「当时凭什么建议转量产」不能被事后抹平。
CREATE FUNCTION guard_production_trial_transition_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '试产单状态迁移事件不可删除（%）', OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.trial_order_id IS DISTINCT FROM OLD.trial_order_id
     OR NEW.from_status IS DISTINCT FROM OLD.from_status
     OR NEW.to_status IS DISTINCT FROM OLD.to_status
     OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR (OLD.applied_at IS NOT NULL AND NEW.applied_at IS DISTINCT FROM OLD.applied_at) THEN
    RAISE EXCEPTION '试产单状态迁移事件不可改写（%）', OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER production_trial_transition_immutable BEFORE UPDATE OR DELETE ON production_trial_order_transition
  FOR EACH ROW EXECUTE FUNCTION guard_production_trial_transition_immutable();

CREATE FUNCTION guard_production_trial_recommendation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '转量产建议不可删除（%）', OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.trial_order_id IS DISTINCT FROM OLD.trial_order_id
     OR NEW.decision IS DISTINCT FROM OLD.decision
     OR NEW.rationale IS DISTINCT FROM OLD.rationale
     OR NEW.condition_note IS DISTINCT FROM OLD.condition_note
     OR NEW.min_yield_threshold IS DISTINCT FROM OLD.min_yield_threshold
     OR NEW.max_cost_variance_rate IS DISTINCT FROM OLD.max_cost_variance_rate
     OR NEW.observed_yield_rate IS DISTINCT FROM OLD.observed_yield_rate
     OR NEW.observed_cost_variance_rate IS DISTINCT FROM OLD.observed_cost_variance_rate
     OR NEW.observed_trial_cost IS DISTINCT FROM OLD.observed_trial_cost
     OR NEW.observed_mass_target_cost IS DISTINCT FROM OLD.observed_mass_target_cost
     OR NEW.observed_currency IS DISTINCT FROM OLD.observed_currency
     OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION '转量产建议不可改写（%）', OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER production_trial_recommendation_immutable
  BEFORE UPDATE OR DELETE ON production_trial_mass_production_recommendation
  FOR EACH ROW EXECUTE FUNCTION guard_production_trial_recommendation_immutable();

-- ===========================================================================
-- 10. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 试产单清单页：按状态 + 创建时刻读（清单页主查询形状）。
CREATE INDEX production_trial_order_by_status
  ON production_trial_order(tenant_id, status, created_at desc, id);
-- 试产单清单页：按 BOM 版本反查。
CREATE INDEX production_trial_order_by_bom_version
  ON production_trial_order(tenant_id, bom_version_id, created_at desc, id);
-- 试产单清单页：按打样单反查。
CREATE INDEX production_trial_order_by_sample_order
  ON production_trial_order(tenant_id, development_sample_order_id, created_at desc, id);
-- 迁移事件：按试产单 + 发生时刻读（详情页时间线）。
CREATE INDEX production_trial_order_transition_by_order
  ON production_trial_order_transition(tenant_id, trial_order_id, occurred_at desc, id);
-- 组件领用需求：按试产单 + 行号读（详情页第三段）。
CREATE INDEX production_trial_component_requirement_by_order
  ON production_trial_component_requirement(tenant_id, trial_order_id, line_no, id);
-- 组件领用需求：按状态读（「还有多少需求没提交」）。
CREATE INDEX production_trial_component_requirement_by_status
  ON production_trial_component_requirement(tenant_id, requirement_status, created_at desc, id);
-- 质检标准项：按试产单 + 排序读。
CREATE INDEX production_trial_inspection_item_by_order
  ON production_trial_inspection_item(tenant_id, trial_order_id, sort_order, id);
-- 试产结果：按试产单读（唯一约束已建索引，这里补一条按时间的）。
CREATE INDEX production_trial_result_by_order
  ON production_trial_result(tenant_id, trial_order_id, recorded_at desc, id);
-- 成本明细：按成本头读。
CREATE INDEX production_trial_cost_line_by_cost
  ON production_trial_cost_line(tenant_id, cost_id, incurred_at, id);
-- 转量产建议：按试产单读。
CREATE INDEX production_trial_recommendation_by_order
  ON production_trial_mass_production_recommendation(tenant_id, trial_order_id, created_at desc, id);

-- ===========================================================================
-- 11. 多租户：RLS + 授权（与 trade V1 / V92 / V257 / V261 同一套写法）
-- ===========================================================================
-- 八张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'production_trial_order','production_trial_order_transition',
    'production_trial_component_requirement','production_trial_inspection_item',
    'production_trial_result','production_trial_cost','production_trial_cost_line',
    'production_trial_mass_production_recommendation'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  production_trial_order,production_trial_order_transition,
  production_trial_component_requirement,production_trial_inspection_item,
  production_trial_result,production_trial_cost,production_trial_cost_line,
  production_trial_mass_production_recommendation TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  production_trial_order,production_trial_order_transition,
  production_trial_component_requirement,production_trial_inspection_item,
  production_trial_result,production_trial_cost,production_trial_cost_line,
  production_trial_mass_production_recommendation TO starcon_app;
-- 留痕表**不可删**：删掉它就等于抹掉「当时为什么这么转 / 当时凭什么建议转量产」的证据。
REVOKE DELETE ON production_trial_order_transition FROM trade_app;
REVOKE DELETE ON production_trial_mass_production_recommendation FROM trade_app;
-- 试产单**不提供 DELETE**：它挂着组件需求、质检项、结果、成本与全部留痕，
-- 删除会抹掉「这个试产经历过什么」的证据。
REVOKE DELETE ON production_trial_order FROM trade_app;

-- ---------------------------------------------------------------------------
-- 12. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V233 / V257 / V261 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不写别的模块的表**：本文件只建自己的八张表；
--     6.6.1.2（development_sample_order*）只被**只读引用 + 快照**，一个字节都不碰；
--     catalog 的 bom_version / bom_component 与 warehouse 的 assembly_order
--     **不碰也不建跨库外键**（那是它们的写入所有权）。
--   * **不建任何库存表**：组件领用需求是**需求**，真实库存扣减归 8.4.1 组装单（warehouse 独立库）。
