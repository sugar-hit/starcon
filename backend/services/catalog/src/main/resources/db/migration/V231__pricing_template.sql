-- 5.3.2.1 定价模板：把定价策略（成本加成 / 固定毛利 / 竞品跟随 + 站点差异化 / 库存周转调整）
-- 沉淀成**可复用的模板**，并「一键应用到商品生成建议价」。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：沉淀定价策略模板：成本加成、固定毛利、竞品跟随（若允许）、按站点差异化、
--             按库存周转调整，新品与老品用不同模板。
--   **输入**：模板定义（基准 + 调整项）；适用对象（分类/品牌/店铺）。
--   **输出**：模板记录；一键应用到商品生成建议价。
--   **关系**：上游 = 5.3.1.1；下游 = 5.4.1.1。
--             **模板与价目表的关系**：模板是"怎么算"，价目表是"算完是多少"。
--
-- ============================================================ 第一节：本模块与 5.3.1.1 的边界（最要紧的一条）
--
-- 蓝图关系句：「上游 = 5.3.1.1」。而 5.3.1.1 的加粗句是：
--   「这是定价决策的核心工具，必须显式列出每一项扣减并允许展开看来源，不能只给一个毛利率数字。」
--
-- ⇒ 本模块**不复刻**任何一套扣减公式。本模块只做三件事：
--   1. 存「怎么算」（基准 + 调整项 + 适用对象 + 商品阶段）；
--   2. 把「怎么算」翻译成一次 **5.3.1.1 的测算请求**（同一个服务内的 ProfitCalculatorService，
--      它是 5.3.1.1 的能力实现，不是本模块的私有副本）；
--   3. 拿 5.3.1.1 算出来的结果当**建议价**（���是「怎么算」的结果）。
--
-- 🔴 **建议价必须来自 5.3.1.1 的真实测算**，不是本模块自己编一个数：
--   * 固定毛利（FIXED_MARGIN）⇒ 走 5.3.1.1 的 TARGET_MARGIN 方向，解出来的
--     `targetMarginPrice` 就是建议价（那个方程解一次，不在本模块重写）；
--   * 成本加成（COST_PLUS）⇒ 加成基数取 5.3.1.1 瀑布图里的**单位成本合计**
--     （5 项成本分项 + 物流每单位），再乘 (1 + 加成率)；
--     平台费是**售价的比例**，不���加成基数里（它在 5.3.1.1 的瀑布图上单独一行，
--     会体现在建议价算出来的毛利里）；
--   * 竞品跟随（COMPETITOR_FOLLOW）⇒ 建议价 = 竞品基准价 × (1 + 跟随率)，
--     竞品基准价是**人工登记**的输入（见第三节）。
--
-- 🔴 **成本不完整 ⇒ 不给建议价**（不是给一个虚高的价）：
--   5.3.1.1 在扣减不全时把 grossProfit / grossMarginRate / targetMarginPrice 全置 null，
--   本模块照抄这条纪律：加成基数缺项时**拒绝**产出建议价，并把 5.3.1.1 的缺口清单原样带回去。
--   这条纪律落在**服务层**（PricingTemplateService），不是页面上的一句提示 ——
--   下游 5.4.1.1 调价单会程序化消费本模块的建议价。
--
-- ============================================================ 第二节：四张表，职责不重叠
--
--   | 表                              | 一行是什么                          | 写入 |
--   |---------------------------------|-------------------------------------|------|
--   | pricing_template                | 一个定价模板（基准 + 商品阶段 + 口径）| 可改 |
--   | pricing_template_adjustment     | 一个调整项（站点差异化 / 库存周转）  | 可改 |
--   | pricing_template_scope          | 一条适用对象（分类 / 品牌 / 店铺）    | 可改 |
--   | pricing_template_application     | 一次「一键应用」的留痕（建议价）      | 只追加 |
--
-- 「模板」与「价目表」的关系（蓝图加粗句）：模板是**怎么算**，价目表是**算完是多少**。
-- 本模块只落前者 + 一次算完的留痕（application）；**不建价目表**（那是 5.1.1.2 的写入所有权），
-- 也**不改价**（那是 5.4.1.1 调价单的写入所有权）。本模块产出的永远是**建议价**，
-- 不是生效价。
--
-- ============================================================ 第三节：基准三选一，与「竞品跟随（若允许）」
--
-- 基准方法互斥（DB CHECK 钉死，两个都填时「这个模板到底怎么算」没有答案）：
--
--   | base_method        | base_rate 的含义            | 额外的必填列                  |
--   |--------------------|-----------------------------|--------------------------------|
--   | COST_PLUS          | 成本加成率（0.3 = 加 30%）   | —                              |
--   | FIXED_MARGIN       | 目标毛利率（0.25 = 25%）     | —                              |
--   | COMPETITOR_FOLLOW  | 跟随率（−0.02 = 比竞品低 2%）| competitor_reference_price 等  |
--
-- 🔴 **竞品跟随的「若允许」在这里是什么意思**：蓝图写的是「竞品跟随（若允许）」——
-- 允许的前提是**有可信的竞品价来源**。本仓**没有**竞品价模块（2.x 卷里没有采集竞品价的模块），
-- 所以竞品价只能由人登记（人工登记 = 显式标注的口径，不是系统取数）。
-- 本迁移因此：
--   * 不建任何「竞品价采集表」或「竞品价历史表」（那是别的模块的写入所有权）；
--   * 竞品基准价存在模板上（可改）+ 应用时可临时覆盖；
--   * 能力声明里写明它的来源与 ownerModule。
-- ⇒ 「若允许」在本模块的落点是：**模板可以配竞品跟随，但价格必须有人给**，
--   系统不会凭空生成一个竞品价。
--
-- 🔴 **base_rate 是小数口径**（0.25 = 25%，不是 25）：写成 25 会被读成 2500%。
--   FIXED_MARGIN 的 base_rate 必须 < 1（目标毛利率 ≥ 100% 时 5.3.1.1 的方程无解）；
--   COMPETITOR_FOLLOW 的跟随率允许为负（比竞品便宜），但 > −1（不能是负价格）。
--
-- ============================================================ 第四节：商品阶段（新品与老品用不同模板）
--
-- 蓝图：「新品与老品用不同模板」。本迁移用 `product_stage` 表达，**取值只有两个**：
--
--   | product_stage | 含义       | 判定依据（catalog 本地事实）                 |
--   |---------------|------------|--------------------------------------------|
--   | NEW           | 新品       | 所属 SPU 还没上架（DRAFT / PENDING_LISTING）  |
--   | EXISTING      | 老品       | 所属 SPU 已上架 / 暂停 / 停产（ON_SALE 等）  |
--
-- 🔴 判定依据是 **3.1.1.3 SPU 生命周期状态机**（V4 的 spu.status 五态），
-- 不是一个「上架天数」字段 —— 全仓没有「首次上架时间」这个事实，
-- 编一个出来就是假数据。所以口径写在模板与能力声明里：**新品 = 尚未上架的 SPU**。
-- 应用模板时若商品阶段与模板不符 ⇒ 409 拒绝（不静默套用）。
--
-- ============================================================ 第五节：调整项（两类，各有专属列）
--
-- 蓝图点名的两类调整项各有各的形状，所以用**专属列 + CHECK**而不是一个 JSON 列：
--
--   | adjustment_type     | 专属列                                        | 语义                                   |
--   |---------------------|-----------------------------------------------|----------------------------------------|
--   | SITE_DIFFERENTIAL   | site_code + delta_rate                        | 该站点的基准率增量（+0.02 = 该站点加 2 个百分点）|
--   | INVENTORY_TURNOVER  | days_cover_threshold + discount_rate          | 可售天数超过阈值 ⇒ 建议价打折            |
--
-- 🔴 **delta_rate 加在「基准率」上，不是乘在价格上**：三个基准方法的基准率含义不同
-- （加成率 / 目标毛利率 / 跟随率），加法是唯一能让「同一套站点差异化规则」在三种基准下
-- 都保持同一语义的方式（都是「这个站点的定价松紧比基准松/紧多少」）。
--
-- 🔴 **库存周转的输入从哪来**：可售天数依赖库存事实（7 库存）与销量（9 订单），
-- 这两个上游**没有**给本服务可读端点 ⇒ 应用时由人填当前可售天数，
-- 并在响应里标成**手工假设**（ownerModule 写明 7 库存 / 09 订单）。
-- 本迁移**不建**任何库存表或销量表来假装上游已交付。
--
-- ============================================================ 第六节：适用对象（分类 / 品牌 / 店铺）
--
-- 蓝图输入句：「适用对象（分类/品牌/店铺）」。三类的取值形状不同，所以用
-- `scope_type` + `scope_ref`（+ SHOP 专用的 platform_code）：
--
--   | scope_type | scope_ref 的含义 | 匹配对象                                    |
--   |------------|------------------|--------------------------------------------|
--   | CATEGORY   | 内部分类码       | SPU 的主分类（product_category.code）       |
--   | BRAND      | 品牌码           | SPU 的品牌字段（spu.brand，存的是品牌编码）  |
--   | SHOP       | 站点码           | 目标店铺（2.1.1.1 平台目录里的 marketplace code）|
--
-- 🔴 全部用**编码**而不是外键：分类与品牌是 catalog 本地表（编码在租户内唯一），
-- 而站点在 channel 服务（跨服务外键被 AGENTS.md 禁止）。编码也是业务上真正认的东西。
-- 一个模板可以挂多条适用对象；**没有任何适用对象的模板 = 适用于全租户**（显式语义，不是漏填）。
--
-- ============================================================ 第七节：表结构
--
-- 7.1 模板
CREATE TABLE pricing_template (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 模板编码：租户内唯一。运营按编码找模板（「老品加价 30%」这种约定俗成的名字）。
  code varchar(64) NOT NULL,
  name varchar(200) NOT NULL,
  description text NOT NULL DEFAULT '',

  -- ===== 基准（第三节）=====
  base_method text NOT NULL
    CHECK(base_method IN ('COST_PLUS','FIXED_MARGIN','COMPETITOR_FOLLOW')),
  -- 基准率（**小数口径**：0.25 = 25%）。取值域随基准方法而变，见下方 CHECK。
  base_rate numeric(9,6),
  -- 竞品基准价（仅 COMPETITOR_FOLLOW 必填）：人工登记的竞品售价。
  competitor_reference_price numeric(19,4),
  -- 竞品价的币种（与本模板 currency 不同时需要换算 ⇒ 由应用时给汇率）。
  competitor_currency varchar(3),

  -- ===== 商品阶段（第四节）=====
  product_stage text NOT NULL CHECK(product_stage IN ('NEW','EXISTING')),

  -- 建议价币种（ISO 4217 三位大写码）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 口径假设（5.3.1.1 的手工假设机制在本模板上的固化）=====
  -- 这些不是模板的「定价策略」，而是**测算一次需要的口径**：5.3.1.1 现在只接受
  -- 手工假设（5.2.3.1 / 5.2.3.2 未并入它的测算路径），所以模板把它们固定下来，
  -- 让「同一个模板算出来的建议价可比」。为空 = 没维护 ⇒ 该项按「取不到」处理，
  -- **不是 0**。
  platform_fee_rate numeric(9,6)
    CHECK(platform_fee_rate IS NULL OR (platform_fee_rate >= 0 AND platform_fee_rate < 1)),
  platform_fee_fixed numeric(19,4)
    CHECK(platform_fee_fixed IS NULL OR platform_fee_fixed >= 0),
  logistics_cost_per_unit numeric(19,4)
    CHECK(logistics_cost_per_unit IS NULL OR logistics_cost_per_unit >= 0),
  -- 成本币种与汇率：售价币种 ≠ 成本币种时**必须**有汇率（方向显式：1 成本币种 = X 售价币种）。
  -- 可空 = 与售价同币种。**绝不按 1:1 折算**（仓库铁律）。
  cost_currency varchar(3),
  fx_rate numeric(19,8) CHECK(fx_rate IS NULL OR fx_rate > 0),

  enabled boolean NOT NULL DEFAULT true,
  -- 生效区间：timestamptz（模板常在某个具体时刻切换）。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),

  CHECK(btrim(code) <> '' AND btrim(name) <> ''),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(cost_currency IS NULL OR cost_currency ~ '^[A-Z]{3}$'),
  CHECK(competitor_currency IS NULL OR competitor_currency ~ '^[A-Z]{3}$'),
  CHECK(effective_to IS NULL OR effective_to > effective_from),

  -- 基准三选一：每种基准方法的必填列与取值域（第三节）。
  -- 用 CASE 而不是四个 OR 分支：OR 会让「某个分支的其它列没被约束」变成可能。
  CHECK(CASE
    WHEN base_method = 'FIXED_MARGIN'
      THEN base_rate IS NOT NULL AND base_rate >= 0 AND base_rate < 1
    WHEN base_method = 'COST_PLUS'
      THEN base_rate IS NOT NULL AND base_rate >= 0 AND base_rate < 10
    WHEN base_method = 'COMPETITOR_FOLLOW'
      THEN base_rate IS NOT NULL AND base_rate > -1 AND base_rate < 1
    ELSE TRUE
  END),

  -- 竞品列只在竞品跟随时存在（互斥）。
  CHECK(CASE
    WHEN base_method = 'COMPETITOR_FOLLOW'
      THEN competitor_reference_price IS NOT NULL AND competitor_reference_price > 0
       AND competitor_currency IS NOT NULL
    ELSE competitor_reference_price IS NULL AND competitor_currency IS NULL
  END),

  -- 跨币种必须要汇率：没有汇率就**拒绝**，绝不按 1:1 折算。
  CHECK(
    cost_currency IS NULL
    OR cost_currency = currency
    OR fx_rate IS NOT NULL
  )
);

-- 模板台账：按最近改过的排序（默认列表）。
CREATE INDEX pricing_template_by_updated ON pricing_template(tenant_id, updated_at DESC, id);
-- 「这个商品阶段的模板」—— 新品与老品用不同模板，列表按阶段筛。
CREATE INDEX pricing_template_by_stage ON pricing_template(tenant_id, product_stage, enabled);
-- 「这种基准方法的模板」。
CREATE INDEX pricing_template_by_method ON pricing_template(tenant_id, base_method, enabled);

-- 7.2 调整项
CREATE TABLE pricing_template_adjustment (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 所属模板。**不建外键**（第八节），引用完整性由触发器 guard 保证。
  template_id uuid NOT NULL,
  -- 段序：从 1 开始。
  seq int NOT NULL CHECK(seq > 0),
  adjustment_type text NOT NULL
    CHECK(adjustment_type IN ('SITE_DIFFERENTIAL','INVENTORY_TURNOVER')),

  -- ===== SITE_DIFFERENTIAL 专属 =====
  -- 站点码（2.1.1.1 平台目录里的 marketplace code）。
  site_code varchar(32),
  -- 加到基准率上的增量（+0.02 = 该站点比基准松/紧 2 个百分点）。可为负。
  delta_rate numeric(9,6),

  -- ===== INVENTORY_TURNOVER 专属 =====
  -- 可售天数阈值（超过它判定为滞销）。数量口径 numeric(19,6)。
  days_cover_threshold numeric(19,6),
  -- 滞销折扣率（0.1 = 建议价打九折）。
  discount_rate numeric(9,6),

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, template_id, seq),

  -- 两类调整项的专属列互斥（第五节）。
  CHECK(CASE
    WHEN adjustment_type = 'SITE_DIFFERENTIAL'
      THEN site_code IS NOT NULL AND btrim(site_code) <> ''
       AND delta_rate IS NOT NULL
       AND days_cover_threshold IS NULL AND discount_rate IS NULL
    WHEN adjustment_type = 'INVENTORY_TURNOVER'
      THEN site_code IS NULL AND delta_rate IS NULL
       AND days_cover_threshold IS NOT NULL AND days_cover_threshold >= 0
       AND discount_rate IS NOT NULL AND discount_rate >= 0 AND discount_rate <= 1
    ELSE TRUE
  END)
);

-- 取模板的调整项（按段序）。
CREATE INDEX pricing_template_adjustment_by_template
  ON pricing_template_adjustment(tenant_id, template_id, seq);
-- 「这个站点上有哪些差异化规则」。
CREATE INDEX pricing_template_adjustment_by_site
  ON pricing_template_adjustment(tenant_id, site_code, template_id);

-- 7.3 适用对象
CREATE TABLE pricing_template_scope (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  template_id uuid NOT NULL,
  -- 适用对象的类型：分类 / 品牌 / 店铺（第六节）。
  scope_type text NOT NULL CHECK(scope_type IN ('CATEGORY','BRAND','SHOP')),
  -- 取值：分类码 / 品牌码 / 站点码。
  scope_ref varchar(64) NOT NULL,
  -- 仅 SHOP：平台码（可空 = 不限定平台）。
  platform_code varchar(32),
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(btrim(scope_ref) <> ''),
  CHECK(platform_code IS NULL OR btrim(platform_code) <> ''),
  -- 只有店铺才带平台码。
  CHECK(CASE WHEN scope_type = 'SHOP' THEN TRUE ELSE platform_code IS NULL END)
);

-- 同一模板下同一对象不重复登记。
-- 🔴 用**唯一索引**而不是 UNIQUE 约束：PG 的表级 UNIQUE 约束只接受列名，
-- 不接受表达式（COALESCE(platform_code,'') 这种「可空列参与唯一性」必须写成索引）。
CREATE UNIQUE INDEX pricing_template_scope_unique
  ON pricing_template_scope(tenant_id, template_id, scope_type, scope_ref, COALESCE(platform_code, ''));

-- 按适用对象反查模板（「这个分类上有哪些模板」—— 列表筛选的主查询路径）。
CREATE INDEX pricing_template_scope_by_ref
  ON pricing_template_scope(tenant_id, scope_type, scope_ref, template_id);
-- 取模板的适用对象。
CREATE INDEX pricing_template_scope_by_template
  ON pricing_template_scope(tenant_id, template_id, scope_type);

-- 7.4 应用留痕（只追加）
CREATE TABLE pricing_template_application (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 用的哪个模板。
  template_id uuid NOT NULL,
  -- 应用到哪个 SKU。
  sku_id uuid NOT NULL,
  site_code varchar(32) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  quantity numeric(19,6) NOT NULL DEFAULT 1 CHECK(quantity > 0),

  -- 用的什么基准、生效的基准率是多少（含站点差异化后的值）。
  base_method text NOT NULL
    CHECK(base_method IN ('COST_PLUS','FIXED_MARGIN','COMPETITOR_FOLLOW')),
  effective_rate numeric(9,6),

  -- 建议价。🔴 **这是建议价，不是生效价** —— 改价是 5.4.1.1 调价单的写入所有权。
  suggested_price numeric(19,4) CHECK(suggested_price IS NULL OR suggested_price > 0),

  -- 决策就绪度（来自 5.3.1.1）：false ⇒ 成本不完整 ⇒ 下面的毛利率**必为 null**。
  decision_ready boolean NOT NULL,
  gross_margin_rate numeric(9,6)
    CHECK(gross_margin_rate IS NULL OR (gross_margin_rate >= 0 AND gross_margin_rate < 1)),
  gross_profit numeric(19,4),

  -- 应用时的说明：命中了哪些调整项、用了什么口径。
  note text NOT NULL DEFAULT '',
  applied_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 🔴 不可信就不给毛利率数字（不是 0、不是估算值）。这条是 5.3.1.1 决策就绪闸的延续。
  CHECK(decision_ready OR gross_margin_rate IS NULL),
  CHECK(decision_ready OR gross_profit IS NULL)
);

-- 「这个 SKU 上用过哪些模板」—— 台账与复盘。
CREATE INDEX pricing_template_application_by_sku
  ON pricing_template_application(tenant_id, sku_id, created_at DESC);
-- 「这个模板被应用过多少次」。
CREATE INDEX pricing_template_application_by_template
  ON pricing_template_application(tenant_id, template_id, created_at DESC);

-- ============================================================ 第八节：引用校验用触发器，不用外键
--
-- 🔴 上一批（batch-37）踩过的坑：给**既有表**加 FOREIGN KEY 会打破同服务既有测试的
-- TRUNCATE ⇒ 整批用例 Errors ⇒ L2 直接红。本迁移因此**零个外键**：
-- 调整项 / 适用对象 → 模板、应用 → 模板 / SKU，全部用触发器校验
-- （与 V227 的 guard_profit_calculator_scenario 同一手法）：
-- 触发器的判据与外键等价（引用不存在 ⇒ 报 23514），但**不参与 TRUNCATE 的依赖图**。
--
-- (a) 调整项：必须挂在真实存在的模板上。
CREATE FUNCTION guard_pricing_template_adjustment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pricing_template t
     WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.template_id) THEN
    RAISE EXCEPTION 'Pricing template adjustment % must reference an existing template',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pricing_template_adjustment_guard
  BEFORE INSERT OR UPDATE ON pricing_template_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_pricing_template_adjustment();

-- (b) 适用对象：必须挂在真实存在的模板上。
CREATE FUNCTION guard_pricing_template_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pricing_template t
     WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.template_id) THEN
    RAISE EXCEPTION 'Pricing template scope % must reference an existing template',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pricing_template_scope_guard
  BEFORE INSERT OR UPDATE ON pricing_template_scope
  FOR EACH ROW EXECUTE FUNCTION guard_pricing_template_scope();

-- (c) 应用留痕：必须挂在真实存在的模板与同租户真实存在的 SKU 上。
CREATE FUNCTION guard_pricing_template_application() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pricing_template t
     WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.template_id) THEN
    RAISE EXCEPTION 'Pricing template application % must reference an existing template',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.sku_id) THEN
    RAISE EXCEPTION 'Pricing template application % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pricing_template_application_guard
  BEFORE INSERT OR UPDATE ON pricing_template_application
  FOR EACH ROW EXECUTE FUNCTION guard_pricing_template_application();

-- ============================================================ 第九节：RLS 与授权
--
-- 四张表都是租户级业务表（定价策略是租户自己的经营参数）：
-- `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
-- + isolation 策略（照抄 V227 第八节的 `DO $$ FOREACH` 写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的模板，
-- 而模板里写着这个租户的毛利率目标与成本加成率 —— 那是一条看不见的越权面。
--
-- 可见性：模板 / 调整项 / 适用对象是**租户级参数**（不是某个 SKU 的从属事实），
-- 所以只有 isolation 策略；应用留痕跟着 SKU 走（看不到 SKU 就看不到它的应用记录）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'pricing_template',
    'pricing_template_adjustment',
    'pricing_template_scope',
    'pricing_template_application'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 应用留痕的可见性跟随 SKU（与 V227 的 data_scope 同一纪律）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['pricing_template_application'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_template TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_template TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_template_adjustment TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_template_adjustment TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_template_scope TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_template_scope TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_template_application TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_template_application TO starcon_app;
