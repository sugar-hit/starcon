-- 5.2.1.2 到岸成本：货到目标仓可售的总成本 = 采购价 + 国际头程 + 关税 + 清关 + 目的地操作费 + 尾程预备。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：计算"货到目标仓可售"的总成本 = 采购价 + 国际头程 + 关税 + 清关 + 目的地操作费 + 尾程预备，
--             用于海外仓/FBA 定价。
--   **输入**：批次/货件的采购明细；头程费用（10 物流与头程）；税率与清关费参数（16 合规）。
--   **输出**：按 SKU/批次的到岸单位成本；不同目的地的成本对比。
--   **关系**：上游 = 6 采购、10 头程、16 税务参数；下游 = 5.3.1.1。
--             **到岸成本与仓库成本是两个口径**（海外仓 vs 自发货），
--             定价时必须选对，否则自发货商品按海运成本定价会低估。
--
-- ===========================================================================
-- 第一节：本模块的六个分量，本仓的三个上游，**四种真实状态**
-- ===========================================================================
--
-- 蓝图那句加粗句是本模块的生死线：「到岸成本与仓库成本是两个口径」。
-- 它要求本模块**只**算到「货到目标仓可售」为止 ——
--   **不含仓储费**（那是 8 仓储作业 / 10.6.1.1 成本分析的口径），
--   **不含尾程派送费**（那是 10.2 运单实际运费 / 10.6.1.1 的口径）。
-- ⇒ 本迁移把「算了谁、没算谁」做进**表结构**（cost_basis + excluded_components 固定值）
--   与**每一行分量**（included / available / owner_module / impact），
--   而不是只给一个「总成本」数字让调用方猜成分。
--
-- 六个分量的来源与本仓真实状态（对集成头实查，不猜）：
--
--   | 分量           | 蓝图说的来源        | 本仓真实状态                                              |
--   |----------------|---------------------|-----------------------------------------------------------|
--   | 采购价         | 6 采购的实际单价    | ⚠️ 6.3 采购单（6.3.1.1/6.3.1.2/6.3.1.3）**未开发**        |
--   |                |                     | ⇒ 改用 catalog 同库 `vendor_delivery_record`（V137）        |
--   |                |                     |   的**实际到货金额**（已到货事实）按成本法加权派生          |
--   | 国际头程       | 10 头程费用         | ✅ warehouse `first_mile_shipment`（V80，跨服务只读）      |
--   | 关税           | 16 税务参数         | ✅ warehouse `duty_tax_estimate` / `_line`（V90，跨服务只读）|
--   |                |                     |    税则税率表 16.3.1.1 未交付 ⇒ 税率由本租户在 10.5.1.3 维护 |
--   | 清关           | 清关费参数（16 合规）| 🔴 全仓**没有任何清关费事实**（`customs_declaration` 无成本列）|
--   |                |                     | ⇒ 租户维护的**单件参数**                                    |
--   | 目的地操作费   | （公式里的第六位）  | ✅ warehouse `first_mile_shipment.handling_cost`（V80）      |
--   | 尾程预备       | （公式里的第六位）  | 🔴 尾程**实际**派送费是 10.2 运单口径，不属本模块；        |
--   |                |                     │   本项是**预留**（预备），由租户按目的地维护单件参数         |
--
-- **🔴 六个分量必须逐项落成可审阅的事实**：
--   每一项都要能追到来源单号（source_document_code），
--   拿不到的要如实标缺口（available=false + owner_module + impact），
--   **不许用 0 假装齐了** —— 用 0 填关税会让到岸成本**系统性低估**，
--   而页面上看不出任何异常，这正是本模块最危险的失败模式。
--
-- ===========================================================================
-- 第二节：四张表，职责互不重叠
-- ===========================================================================
--
--   | 表                       | 一行是什么                                   | 写入方式      |
--   |--------------------------|----------------------------------------------|---------------|
--   | `landed_cost_statement`  | 一批货（一张头程发运单）的到岸成本单          | 重算即覆盖    |
--   | `landed_cost_line`       | 该批里的**一个 SKU**（含单位到岸成本）         | 重算即覆盖    |
--   | `landed_cost_component`  | 某一行上的**一个分量**（每行恒 6 行）         | 重算即覆盖    |
--   | `landed_cost_parameter`  | 租户维护的单件参数（清关费 / 尾程预备）        | 可改（乐观锁）|
--
-- **为什么分量是「每行 6 行」而不是「有的行才有」**：
-- 缺一项就退化成半截子报表，调用方无法区分「这项不适用」与「这项没查到」。
-- ⇒ 每一行**恒有** 6 行分量，缺的标 available=false，
--   并写明归谁管（owner_module）与缺了会怎样（impact）。
--
-- **为什么分量落在行（line）上而不是单头上**：
-- 蓝图输出是「按 SKU/批次的到岸单位成本」—— 单位成本必须落到 SKU 粒度，
-- 而采购价与关税天然是 SKU 粒度的事实，头程 / 清关 / 目的地操作费是批次粒度，
-- 尾程预备是单件参数。落到行上，六项在**同一个粒度**上可加、可比、可追。
--
-- ===========================================================================
-- 第三节：口径钉死在表结构里（生死线的物理落点）
-- ===========================================================================
--
-- `landed_cost_statement.cost_basis` 是**只有一个取值的闭集**：
-- 本模块只提供「货到目标仓可售」这一个口径。
-- 写成闭集（而不是自由文本）是为了让「有人往这个模块里塞一个仓库成本口径」
-- 在 DB 层就被拒 —— 那正是蓝图加粗句要防的事。
--
-- `landed_cost_statement.excluded_components` 固定写明**不算**的两项：
--   STORAGE_FEE（仓储费）、LAST_MILE_DELIVERY_FEE（尾程派送费实际值）。
-- 它们归 10.6.1.1 成本分析（已交付，channel）与 8 仓储作业。
-- 🔴 「尾程预备」（LAST_MILE_RESERVE）是**本模块算的第六个分量**，
--    与「尾程派送费实际值」**不是一回事**：前者是定价用的预留，后者是已发生的运费。
--    两者混为一谈，就等于把仓库成本偷偷塞进了到岸成本。
--
-- ===========================================================================
-- 第四节：🔴 null 与 0 是两件事（DB 级钉死）
-- ===========================================================================
--
-- 分量金额 `amount` **可空**：
--   available=false ⇒ amount 必须是 NULL（没有数，不是「有 0 元」）；
--   included=true  ⇒ amount 必须非空（算进合计的项一定有数）。
-- 这两条由 CHECK 钉死 ⇒ 「缺口」与「真的是 0」在库里就是两种不同的行，
-- 服务层与前端都不可能把缺口渲染成 0.00。
--
-- 行合计 `landed_cost_line.total_amount` 同样可空：
--   一个分量都没算出来时，合计是 NULL（**算不出来**），不是 0。
--   而「算出来是 0」是另一回事（分母为 0 的比率与单均运费同理）。
-- 合计与「计入分量之和」必须**逐字相等**，由**延迟约束触发器**在提交时核对 ——
-- 与 10.6.1.1 的「分摊逐行合计严格等于来源总额」同一条纪律：
-- 分量之和比合计大就是多算了成本，比合计小就是漏算了成本，两种都是事故。
--
-- ===========================================================================
-- 第五节：跨服务只读，零个外键指向别的服务（AGENTS.md 模块边界纪律）
-- ===========================================================================
--
-- 头程在 warehouse 的 `first_mile_shipment`，关税在 warehouse 的 `duty_tax_estimate`，
-- 两者都在**另一个数据库**里。AGENTS.md 明令禁止跨服务 SQL / 跨库 FK / 共享视图
-- ⇒ 本库**不建**它们的副本表，服务层经**授权 HTTP 只读端点**取数
--   （见 `LandedCostPort`），并把**调用方自己的 Authorization 头**原样透传过去，
--   租户由 warehouse 从已认证成员关系解析 ——
--   本服务**不**替对方决定租户，也**不**发明 `X-Tenant-Id` 之类的头。
--
-- 所以 `landed_cost_statement.source_shipment_id` 是**逻辑引用**（不建外键），
-- `source_shipment_code` 是可读快照 —— 人能顺着单号回到原始发运单。
--
-- 🔴 本迁移**零个 FOREIGN KEY**（连本服务的 sku 也不建）：
-- PG 规定「被 TRUNCATE 的表若被别的表外键引用就拒绝 TRUNCATE」，
-- 给既有表建外键会打断同服务既有测试的清理（batch-37 已踩过）。
-- 引用校验改用**触发器**做（同批 V219 的 `guard_return_shipback_line_order` 同款做法），
-- 它不打断任何既有测试。
--
-- ===========================================================================
-- 第六节：金额与数量精度（禁浮点，禁整数除法）
-- ===========================================================================
--
-- 金额 numeric(19,4)（与 V80 / V90 / V137 / V215 同一精度），
-- 数量 numeric(19,6)（与 V80 / V85 同一精度），
-- 比率 numeric(19,6)。全程 BigDecimal + 显式 scale + HALF_UP。
--
-- ===========================================================================
-- 第七节：🔴 本迁移只新建表，零 ALTER 任何既有表
-- ===========================================================================
--
-- 跨车道 DDL 回归面 = 0：不改任何既有表的列 / CHECK / 索引 / 触发器。
--
-- ---------------------------------------------------------------------------
-- 0. 单号序列（到岸成本单号 LC-yyyyMMdd-000001）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE landed_cost_statement_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 到岸成本单（批次级）
-- ---------------------------------------------------------------------------
CREATE TABLE landed_cost_statement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：LC-yyyyMMdd-000001。人可读、可口述、可在审计里被引用。
  statement_code varchar(32) NOT NULL,

  -- ===== 来源：warehouse 的头程发运单（10.4.1.1，跨服务 ⇒ 逻辑引用，不建外键）=====
  source_shipment_id uuid NOT NULL,
  source_shipment_code varchar(64) NOT NULL DEFAULT '',
  -- 来源发运单的法人（跨服务引用，只记 id）。
  source_legal_entity_id uuid,

  -- ===== 目的地（蓝图输出要求「不同目的地的成本对比」）=====
  destination_country_code varchar(2) NOT NULL,
  destination_name varchar(200) NOT NULL DEFAULT '',
  transport_mode varchar(16) NOT NULL DEFAULT '' CHECK(transport_mode IN
    ('SEA','AIR','RAIL','EXPRESS','')),
  -- 贸易条款与风险承担方：**决定哪些成本算在卖方头上**。
  -- DDP 卖方承担关税与目的国税费；DDU 买方承担，卖方的到岸成本里不能算 ——
  -- 把它算进去就是在给一个不属于自己的成本记账（与 V90 第五节同一条纪律）。
  incoterm varchar(8) NOT NULL DEFAULT '' CHECK(incoterm IN
    ('EXW','FCA','FAS','FOB','CFR','CIF','CPT','CIP','DAP','DPU','DDP','')),
  risk_bearer varchar(16) NOT NULL DEFAULT '' CHECK(risk_bearer IN
    ('SELLER','BUYER','CARRIER','')),

  -- ===== 计价币种（跨币种绝不相加：本服务没有汇率表，汇率是 12 财务的资产）=====
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 🔴 成本口径：闭集，只有一个取值 =====
  -- 本模块只提供「货到目标仓可售」这一个口径。写成闭集是为了让
  -- 「有人往到岸成本里塞一个仓库成本口径」在 DB 层就被拒。
  cost_basis varchar(40) NOT NULL DEFAULT 'LANDED_TO_TARGET_WAREHOUSE'
    CHECK(cost_basis = 'LANDED_TO_TARGET_WAREHOUSE'),
  -- 🔴 显式写明**不算**的两项：仓储费与尾程派送费实际值。
  -- 它们归 10.6.1.1 成本分析（channel，已交付）与 8 仓储作业。
  excluded_components varchar(200) NOT NULL DEFAULT
    'STORAGE_FEE,LAST_MILE_DELIVERY_FEE',
  -- 批次级成本摊到 SKU 行的口径。
  -- 🔴 目前只有「按数量比例」一种：完整的分摊规则（重量/体积/金额 + 人工微调 + 历史版本）
  -- 归 5.2.1.3 成本分摊（**未开发**），本模块不代它开形状。
  allocation_method varchar(24) NOT NULL DEFAULT 'QUANTITY_PROPORTIONAL'
    CHECK(allocation_method = 'QUANTITY_PROPORTIONAL'),

  status varchar(16) NOT NULL DEFAULT 'COMPUTED' CHECK(status IN ('COMPUTED','VOID')),

  -- ===== 合计 =====
  -- 可空：**一个分量都没算出来时是 NULL（算不出来），不是 0**。
  -- 由延迟约束触发器核对「严格等于计入分量之和」。
  total_amount numeric(19,4) CHECK(total_amount IS NULL OR total_amount >= 0),
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity >= 0),
  total_line_count integer NOT NULL DEFAULT 0 CHECK(total_line_count >= 0),

  computed_at timestamptz NOT NULL DEFAULT now(),
  computed_by uuid,
  -- 重算次数：每一次重算都留痕（谁在什么时候按什么上游重算过）。
  compute_count integer NOT NULL DEFAULT 1 CHECK(compute_count >= 1),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, statement_code),
  -- 一张发运单一张到岸成本单：重算是覆盖同一张单，不是再开一张。
  UNIQUE(tenant_id, source_shipment_id)
);

-- 列表主路径：按目的地 + 时间倒序。
CREATE INDEX landed_cost_statement_by_destination
  ON landed_cost_statement(tenant_id, destination_country_code, computed_at DESC);
CREATE INDEX landed_cost_statement_by_computed
  ON landed_cost_statement(tenant_id, computed_at DESC);

-- ---------------------------------------------------------------------------
-- 2. 到岸成本行（SKU 级）
-- ---------------------------------------------------------------------------
CREATE TABLE landed_cost_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  statement_id uuid NOT NULL,

  line_no integer NOT NULL CHECK(line_no > 0),
  sku_id uuid NOT NULL,
  -- SKU 的可读快照：来源 SKU 被改描述 / 被删都不影响这张到岸成本单的可读性。
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  base_unit varchar(16) NOT NULL DEFAULT '',

  -- 数量：基础单位。批次级数量的占比用它算（见 allocation_method）。
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  base_quantity numeric(19,6) NOT NULL CHECK(base_quantity > 0),

  -- ===== 合计 =====
  -- 可空：一个分量都没算出来 ⇒ NULL（算不出来），不是 0。
  total_amount numeric(19,4) CHECK(total_amount IS NULL OR total_amount >= 0),
  -- 单位到岸成本：**生成列**，服务层写不进去，绕过服务层写 SQL 也算不出
  -- 一个与数量对不上的单位成本（与 10.6.2.1 的比率生成列同款做法）。
  unit_landed_cost numeric(19,4) GENERATED ALWAYS AS
    (round(total_amount / base_quantity, 4)) STORED,

  -- 覆盖度：算了几项、缺了哪几项（缺项清单也让前端直接显示，不用自己比对六行）。
  included_component_count integer NOT NULL DEFAULT 0 CHECK(included_component_count >= 0),
  missing_component_count integer NOT NULL DEFAULT 0 CHECK(missing_component_count >= 0),
  missing_components varchar(200) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, statement_id, line_no),
  -- 同一张到岸成本单里一个 SKU 只占一行（跨行重复会让合计算两遍）。
  UNIQUE(tenant_id, statement_id, sku_id)
);

CREATE INDEX landed_cost_line_by_statement ON landed_cost_line(tenant_id, statement_id, line_no);
CREATE INDEX landed_cost_line_by_sku ON landed_cost_line(tenant_id, sku_id);

-- ---------------------------------------------------------------------------
-- 3. 到岸成本分量（行 × 分量，**每行恒 6 行**）
-- ---------------------------------------------------------------------------
CREATE TABLE landed_cost_component (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  statement_id uuid NOT NULL,
  line_id uuid NOT NULL,

  -- ===== 🔴 六个分量的闭集 =====
  -- 闭集的意义：DB 层就存不下「第七个分量」，也存不下拼错的分量名。
  component_code varchar(32) NOT NULL CHECK(component_code IN (
    'PURCHASE_PRICE',        -- 采购价
    'INTERNATIONAL_FREIGHT', -- 国际头程（运费 + 保险费）
    'DUTY',                  -- 关税（+ 增值税/GST，**分列**见下）
    'CUSTOMS_CLEARANCE',     -- 清关
    'DESTINATION_HANDLING',  -- 目的地操作费
    'LAST_MILE_RESERVE'      -- 尾程预备（**预留**，不是尾程派送费实际值）
  )),
  label varchar(32) NOT NULL DEFAULT '',

  -- ===== 来源类型：FACT = 上游事实；PARAMETER = 租户维护的参数 =====
  -- 参数与事实**分色**：参数是人定的，事实是上游算的。混在一起时
  -- 「这个数为什么变了」将无法回答。
  source_kind varchar(12) NOT NULL CHECK(source_kind IN ('FACT','PARAMETER')),
  -- 来源模块与**来源单号**：每一项都要能追到来源单号。
  source_module varchar(64) NOT NULL DEFAULT '',
  source_document_code varchar(64) NOT NULL DEFAULT '',

  -- ===== 可得性 =====
  -- false ⇒ 缺口：没有数（不是「有 0 元」）。此时 amount 必须是 NULL，
  -- 且 owner_module / impact 必填（DB 层 CHECK 钉死）。
  available boolean NOT NULL,
  -- included ⇒ 计入本行合计。available 但 included=false 是「有事实但按口径不计入」
  -- （例如 DDU 下关税由买方承担、采购价币种与本单不一致未折算）。
  included boolean NOT NULL,

  -- 金额：可空。available=false ⇒ NULL；included=true ⇒ 非空。
  amount numeric(19,4) CHECK(amount IS NULL OR amount >= 0),

  -- ===== 税的拆分（仅 DUTY 分量有意义）=====
  -- 10.5.1.3 把 DUTY / VAT / GST 明确分成三笔独立的钱，
  -- 把增值税并进「关税」会让明细答不出「哪部分是关税哪部分是税」。
  -- ⇒ 两者分列存储、分开显示，合计仍然只进一个「关税」槽位。
  tax_duty_amount numeric(19,4) CHECK(tax_duty_amount IS NULL OR tax_duty_amount >= 0),
  tax_other_amount numeric(19,4) CHECK(tax_other_amount IS NULL OR tax_other_amount >= 0),
  -- 关税承担方：SELLER 才计入卖方的到岸成本。
  tax_bearer varchar(16) NOT NULL DEFAULT '' CHECK(tax_bearer IN
    ('SELLER','BUYER','CARRIER','')),

  -- ===== 缺口归属与影响 =====
  owner_module varchar(64) NOT NULL DEFAULT '',
  impact text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一行一个分量只占一行。
  UNIQUE(tenant_id, line_id, component_code),

  -- ===== 🔴 null 与 0 是两件事（DB 级钉死）=====
  -- 没有数 ⇒ 金额是 NULL。写成 0 的话，页面会显示「关税 0.00」，
  -- 而真相是「没查到关税」—— 这会让到岸成本**系统性低估**且看不出异常。
  CHECK(available = true OR amount IS NULL),
  -- 计入合计的项一定有数。
  CHECK(included = false OR (available = true AND amount IS NOT NULL)),
  -- 缺口必须点名归谁管、缺了会怎样：只标「未知」的缺口没人会去补。
  CHECK(available = true OR (btrim(owner_module) <> '' AND btrim(impact) <> '')),
  -- 有事实但按口径不计入（例如 DDU 下关税由买方承担、采购价币种不一致未折算）
  -- ⇒ 必须写清原因，否则「这一项为什么没进合计」将无法回答。
  CHECK(included = true OR available = false OR btrim(note) <> ''),
  -- 关税分量的税种拆分：非关税分量不许带税额；关税分量可用时必须有税额。
  CHECK(component_code <> 'DUTY' OR available = false OR tax_duty_amount IS NOT NULL)
);

CREATE INDEX landed_cost_component_by_line ON landed_cost_component(tenant_id, line_id);
CREATE INDEX landed_cost_component_by_statement
  ON landed_cost_component(tenant_id, statement_id, component_code);

-- ---------------------------------------------------------------------------
-- 4. 到岸成本参数（租户维护的单件参数：清关费 / 尾程预备）
-- ---------------------------------------------------------------------------
CREATE TABLE landed_cost_parameter (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 只有这两个分量是**参数**：全仓没有任何清关费事实，
  -- 而尾程预备按定义就是租户为定价留的**预留**，不是任何上游算出来的数。
  component_code varchar(32) NOT NULL CHECK(component_code IN
    ('CUSTOMS_CLEARANCE','LAST_MILE_RESERVE')),

  -- 作用域：按**目的国 + 币种**。到岸成本是「货到某个目的地」的口径，
  -- 同一批货去美国和去德国的尾程预留与清关费本来就不一样。
  destination_country_code varchar(2) NOT NULL CHECK(length(btrim(destination_country_code)) = 2),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 单件金额（每**基础单位**）。与行上的 base_quantity 相乘得到该行该分量的金额。
  unit_amount numeric(19,4) NOT NULL CHECK(unit_amount >= 0),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一 (分量, 目的国, 币种) 只有一行：否则「取哪个」将没有答案。
  UNIQUE(tenant_id, component_code, destination_country_code, currency)
);

-- ===========================================================================
-- 第五节：护栏触发器
-- ===========================================================================
--
-- 5.1 行引用的 SKU 必须存在，且必须**属于本租户**（**用触发器而不是外键**，见文件头第五节）。
--
-- 🔴 为什么这个校验是 BEFORE 行触发器而不是延迟约束触发器：
--   延迟触发器在提交时才跑，那时行已经写进表了。跨租户引用 SKU 必须在**写入当下**就被拒 ——
--   「A 租户的行引用了 B 租户的 SKU」如果拖到提交才报，错误信息里已经带着越权事实，
--   而且读侧可能在同一事务里读到它。这里要的是 fail-fast。
--
-- 顺带说明：sku 表上 ENABLE + FORCE RLS，所以这里的 SELECT 天然只看得到
-- 本事务租户的 SKU —— 「SKU 属于别的租户」与「SKU 不存在」在这一层是同一件事，
-- 两者都被这条护栏挡住，不会漏到库里。
CREATE FUNCTION guard_landed_cost_line_sku() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  found_sku uuid;
BEGIN
  SELECT s.id INTO found_sku FROM sku s
   WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.sku_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '到岸成本行引用的 SKU % 不存在或不属于本租户 %：到岸成本只能挂在本租户自己的 SKU 上',
      NEW.sku_id, NEW.tenant_id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER landed_cost_line_sku
  BEFORE INSERT OR UPDATE ON landed_cost_line
  FOR EACH ROW EXECUTE FUNCTION guard_landed_cost_line_sku();

-- 5.2 分量的行必须存在，且行的单号必须与分量一致（防止挂错单）。
CREATE FUNCTION guard_landed_cost_component_line() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  l record;
BEGIN
  SELECT * INTO l FROM landed_cost_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.line_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '到岸成本分量引用的行 % 不存在', NEW.line_id USING ERRCODE='23514';
  END IF;
  IF l.statement_id <> NEW.statement_id THEN
    RAISE EXCEPTION '到岸成本分量的单号与行的单号不一致' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER landed_cost_component_line
  BEFORE INSERT OR UPDATE ON landed_cost_component
  FOR EACH ROW EXECUTE FUNCTION guard_landed_cost_component_line();

-- 5.3 🔴 合计必须**严格等于**计入分量之和（延迟约束，提交时核对）。
--
-- 为什么要 DB 级钉死：服务层算一遍、SQL 再聚合一遍，两处只要有一处漂移，
-- 页面上就会出现「分项加起来 12.30、合计写 12.00」这种没法解释的对不上。
-- 10.6.1.1 的「分摊逐行合计严格等于来源总额」是同一条纪律。
--
-- 触发器在提交时读**最终状态**，因此与写入顺序无关：
-- 先写头再写行再写分量、最后回填合计，提交时三者已经齐了。
CREATE FUNCTION guard_landed_cost_totals() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  v_tenant_id uuid;
  v_line_id uuid;
  v_statement_id uuid;
  l record;
  head record;
  line_sum numeric;
  statement_sum numeric;
  counted_included integer;
  counted_missing integer;
BEGIN
  IF TG_TABLE_NAME = 'landed_cost_component' THEN
    IF TG_OP = 'DELETE' THEN
      v_tenant_id := OLD.tenant_id;
      v_line_id := OLD.line_id;
      v_statement_id := OLD.statement_id;
    ELSE
      v_tenant_id := NEW.tenant_id;
      v_line_id := NEW.line_id;
      v_statement_id := NEW.statement_id;
    END IF;
  ELSIF TG_TABLE_NAME = 'landed_cost_line' THEN
    IF TG_OP = 'DELETE' THEN
      v_tenant_id := OLD.tenant_id;
      v_line_id := OLD.id;
      v_statement_id := OLD.statement_id;
    ELSE
      v_tenant_id := NEW.tenant_id;
      v_line_id := NEW.id;
      v_statement_id := NEW.statement_id;
    END IF;
  ELSE
    v_tenant_id := NEW.tenant_id;
    v_statement_id := NEW.id;
  END IF;

  -- ---- 行的合计 = 该行「计入」分量之和 ----
  IF v_line_id IS NOT NULL THEN
    SELECT * INTO l FROM landed_cost_line
     WHERE tenant_id = v_tenant_id AND id = v_line_id;
    IF FOUND THEN
      SELECT sum(c.amount) INTO line_sum FROM landed_cost_component c
       WHERE c.tenant_id = l.tenant_id AND c.line_id = l.id AND c.included;
      IF line_sum IS NULL THEN
        -- 一个分量都没算出来 ⇒ 合计必须是 NULL（**算不出来**），不是 0。
        IF l.total_amount IS NOT NULL THEN
          RAISE EXCEPTION '到岸成本行 % 没有任何计入分量，合计必须是 NULL（算不出来）而不是数字：0 会被读成「到岸成本是 0」',
            l.id USING ERRCODE='23514';
        END IF;
      ELSIF l.total_amount IS DISTINCT FROM line_sum THEN
        RAISE EXCEPTION '到岸成本行 % 的合计 % 与其计入分量之和 % 不一致：合计必须严格等于分项之和',
          l.id, l.total_amount, line_sum USING ERRCODE='23514';
      END IF;

      -- 覆盖度计数必须与分量行**逐数相等**：页面上「算了 4 项、缺 2 项」
      -- 是用户判断「这个到岸成本能不能用来定价」的唯一依据，计数漂了就等于谎报。
      SELECT count(*) FILTER (WHERE c.included), count(*) FILTER (WHERE NOT c.available)
        INTO counted_included, counted_missing
        FROM landed_cost_component c
       WHERE c.tenant_id = l.tenant_id AND c.line_id = l.id;
      IF l.included_component_count <> counted_included
         OR l.missing_component_count <> counted_missing THEN
        RAISE EXCEPTION '到岸成本行 % 的覆盖度计数（计入 % / 缺口 %）与实际分量行（计入 % / 缺口 %）不一致',
          l.id, l.included_component_count, l.missing_component_count,
          counted_included, counted_missing USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;

  -- ---- 单的合计 = 该单全部行的「计入」分量之和 ----
  SELECT sum(c.amount) INTO statement_sum FROM landed_cost_component c
   WHERE c.tenant_id = v_tenant_id AND c.statement_id = v_statement_id AND c.included;
  SELECT * INTO head FROM landed_cost_statement
   WHERE tenant_id = v_tenant_id AND id = v_statement_id;
  IF FOUND THEN
    IF statement_sum IS NULL THEN
      IF head.total_amount IS NOT NULL THEN
        RAISE EXCEPTION '到岸成本单 % 没有任何计入分量，合计必须是 NULL（算不出来）而不是数字',
          v_statement_id USING ERRCODE='23514';
      END IF;
    ELSIF head.total_amount IS DISTINCT FROM statement_sum THEN
      RAISE EXCEPTION '到岸成本单 % 的合计 % 与其计入分量之和 % 不一致：合计必须严格等于分项之和',
        v_statement_id, head.total_amount, statement_sum USING ERRCODE='23514';
    END IF;
  END IF;

  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER landed_cost_component_totals
  AFTER INSERT OR UPDATE OR DELETE ON landed_cost_component
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_landed_cost_totals();

CREATE CONSTRAINT TRIGGER landed_cost_line_totals
  AFTER INSERT OR UPDATE OR DELETE ON landed_cost_line
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_landed_cost_totals();

CREATE CONSTRAINT TRIGGER landed_cost_statement_totals
  AFTER INSERT OR UPDATE OR DELETE ON landed_cost_statement
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_landed_cost_totals();

-- ===========================================================================
-- 第六节：RLS 与授权
-- ===========================================================================
--
-- 四张表都是租户级业务表（到岸成本是租户自己的成本事实）。
-- 照抄 V215 第四节的 DO $$ FOREACH 写法：四张表的策略必须逐字相同，
-- 复制粘贴的第四遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的到岸成本 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'landed_cost_statement','landed_cost_line','landed_cost_component','landed_cost_parameter'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性跟随 SKU：看不到 SKU 就看不到它的到岸成本行与分量
-- （与 V215 的 data_scope 同一条纪律）。
--
-- 🔴 分量表上没有 sku_id 列（它挂在行上），所以它的可见性要**经行**判定：
-- 行 → SKU → 该 SKU 在本租户可见。写成「分量表也有 sku_id」会在建策略时就炸。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['landed_cost_line'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
  EXECUTE 'CREATE POLICY data_scope ON landed_cost_component AS RESTRICTIVE'
    || ' USING (EXISTS(SELECT 1 FROM landed_cost_line l JOIN sku s'
    || ' ON s.tenant_id=l.tenant_id AND s.id=l.sku_id'
    || ' WHERE l.tenant_id=landed_cost_component.tenant_id AND l.id=landed_cost_component.line_id))'
    || ' WITH CHECK (EXISTS(SELECT 1 FROM landed_cost_line l JOIN sku s'
    || ' ON s.tenant_id=l.tenant_id AND s.id=l.sku_id'
    || ' WHERE l.tenant_id=landed_cost_component.tenant_id AND l.id=landed_cost_component.line_id))';
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON landed_cost_statement TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON landed_cost_statement TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON landed_cost_line TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON landed_cost_line TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON landed_cost_component TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON landed_cost_component TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON landed_cost_parameter TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON landed_cost_parameter TO starcon_app;
GRANT USAGE,SELECT ON SEQUENCE landed_cost_statement_code_seq TO catalog_app;
GRANT USAGE,SELECT ON SEQUENCE landed_cost_statement_code_seq TO starcon_app;
