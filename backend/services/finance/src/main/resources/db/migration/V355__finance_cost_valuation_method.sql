-- ===========================================================================
-- V355 · 12.4.1.1 计价方法配置
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.4.1.1 计价方法配置）原文：
--
--   职责：配置成本计算方法（先进先出/移动加权平均/批次成本/指定成本法），
--         按法人或品类设置，确定生效时间。
--   输入：计价方法选择；生效时间；适用范围。
--   输出：生效的成本方法配置。
--   关系：上游 = 用户会计政策决定；下游 = 12.4.1.2、13 利润。
--         **系统提供选项与试算，不替用户决定政策**；切换方法需重算并留版本对比。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移做什么 / 不做什么（先把边界钉死，再写 DDL）
-- ---------------------------------------------------------------------------
--
--   本模块是**成本计算的配置面**：计价方法（先进先出 / 移动加权平均 / 批次成本 /
--   指定成本法）按**法人**或**品类**设置，带**生效时间**，切换留**版本历史**，
--   并支持**试算**（用真实成本层算出各方法下的结果供对比）。
--
--   本模块**不做重算执行**：蓝图「切换方法需重算」里的「重算」是 12.4.1.2
--   「成本计算执行」的职责（它产出成本层与结转金额），本模块**未交付**。
--   ⇒ 边界在**数据层**表达，不靠注释：
--       · 方法配置行上有 `recalculation_status`（NOT_REQUIRED / PENDING）与
--         `recalculation_owner_module`（恒为 '12.4.1.2'）；
--       · 本表**没有**任何一列存放「重算结果 / 结转金额 / 成本层」——
--         「已重算」这个状态在结构上就写不出来（集成测试直接断言这一点）。
--
--   ⇒ 12.4.1.3「成本差异分析」同理**未交付**，本模块**不产出任何差异分析报表**。
--
-- ---------------------------------------------------------------------------
-- 1. 「系统提供选项与试算，不替用户决定政策」—— 数据层怎么落地这句话
-- ---------------------------------------------------------------------------
--
--   **系统不预置任何一行配置**（与 12.1.2.2 账期规则、12.4.2.1 归集规则同一纪律）：
--   计价方法是**租户自己的会计政策**（上游 = 用户会计政策决定），
--   系统替用户默认一个方法就是替用户记账 —— 同一个 SKU 在两套账里的成本能差 30%，
--   而这个差额是谁定的，必须可追溯到**某个人在某天**的选择。
--   ⇒ 四张表**零预置行**；方法选项由服务层以**只读**方式提供（MethodOption：
--     值 + 名称 + 口径说明），供界面把四个方法摆出来，但**落库的每一行都是租户自己选的**。
--
--   同样的纪律也写进「试算」：试算只**算给人看**，不写任何成本事实，
--   也不改任何配置 —— 试算结果落在自己的表里（可留痕、可对比），
--   但**不产生**任何结转金额，12.4.1.2 的成本层表在本库**一张都不建**。
--
-- ---------------------------------------------------------------------------
-- 2. 四张表：配置（版本链） → 指定单价 → 试算批次 → 试算结果
-- ---------------------------------------------------------------------------
--
--   finance_cost_valuation_method         计价方法配置（**版本链**：切换 = 追加新版本）
--   finance_cost_valuation_specified_cost 指定成本法下的**指定单价**（按 SKU + 币种）
--   finance_cost_valuation_trial          试算批次（表头：谁、对哪个范围、试多少）
--   finance_cost_valuation_trial_result   试算结果（**每个方法一行**，供并排对比）
--
--   为什么「指定单价」要单独一张表而不是方法行上的一个数：
--   计价方法是**按法人/品类**设的，而指定单价是**按 SKU** 的（同一个品类里
--   不同 SKU 的指定成本不同）。把单价挂在方法行上，就只能全法人一个价 ——
--   那不叫「指定成本法」，那叫「整个法人一个标准成本」。
--
--   为什么试算要拆表头 + 结果两张：
--   一次试算产出**四个方法**的结果。放一行里就得把四组数字塞成列
--   （fifo_cost / moving_average_cost / batch_cost / specified_cost）——
--   那是最容易算错的一行（少一列就静默变成 0）。拆开之后：
--   结果表的每一行都是「方法 + 一组数字」，**加一种方法就多一行**，不用改结构。
--
-- ---------------------------------------------------------------------------
-- 3. 范围（法人 / 品类）：跨服务**逻辑引用 + 名称快照**，不建 FK
-- ---------------------------------------------------------------------------
--
--   AGENTS.md 明令禁止跨服务 SQL / 跨库 FK / 共享视图：
--     · 法人来自 identity 的 17 法人档案（`legal_entity`）—— 服务层经授权只读端点
--       `/identity/legal-entities` 取，**不建影子表、不复制身份证明字段**；
--     · 品类来自 catalog 的品类树 —— 服务层经授权只读端点取。
--   ⇒ 本表只存 `scope_ref_id`（逻辑 ID）+ `scope_code` + `scope_name`（**名称快照**）。
--   名称快照是刻意的：配置页要显示「这个范围当时叫什么」，
--   而 identity/catalog 改名后历史配置不该跟着变（审计要看到当时的口径）。
--
-- ---------------------------------------------------------------------------
-- 4. 版本链与生效区间：历史成本不能被新配置追溯改写
-- ---------------------------------------------------------------------------
--
--   切换方法 = **追加一个新版本**，不是就地 UPDATE：
--     · `version` 在同一范围内从 1 递增（DB 触发器钉死 = 上一个版本 + 1）；
--     · 新版本的 `effective_from` 必须**严格晚于**上一版本（DB 触发器钉死）；
--     · 上一版本被置为 `status='SUPERSEDED'` 且 `effective_to = 新版本 effective_from`
--       ⇒ 任意时刻「当时生效的是哪一版」可由区间唯一定位；
--     · 已存在的版本行**不许改**方法 / 范围 / 版本号 / 生效起点（DB 触发器钉死），
--       只允许改 `status` / `effective_to` / `note` —— 历史即证据。
--
--   「同一范围同时只有一个生效版本」由**部分唯一索引**钉死：
--     `unique (tenant_id, scope_type, scope_ref_id) where status='ACTIVE'`。
--
-- ---------------------------------------------------------------------------
-- 5. 币种：绝不跨币种相加
-- ---------------------------------------------------------------------------
--
--   计价方法行上的 `currency` 是**计价币种**（成本以哪个币种计量）；
--   指定单价行上的 `currency` 是该单价的币种；
--   试算结果的金额按币种分组给出。
--   ⇒ 试算结果表上的 `currency` 与金额同源，**不跨币种相加**（AGENTS.md 财务纪律）。
--
-- ---------------------------------------------------------------------------
-- 6. 金额与数量：BigDecimal + NUMERIC，禁浮点（AGENTS.md）
-- ---------------------------------------------------------------------------
--
--   金额 NUMERIC(19,4)（与同服务 V351 一致）；数量 NUMERIC(19,6)（AGENTS.md 明文要求）。
--   时间列一律 timestamptz + OffsetDateTime 绑定 + `?::timestamptz`。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 计价方法配置（版本链）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_valuation_method (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 适用范围：按法人 / 按品类设置（蓝图「按法人或品类设置」）。
  scope_type varchar(16) NOT NULL,
  -- 跨服务逻辑引用（identity 法人 / catalog 品类），**不建 FK**（AGENTS.md）。
  scope_ref_id uuid NOT NULL,
  scope_code varchar(64) NOT NULL,
  -- 名称快照：配置当时的范围名称（改名不改历史）。
  scope_name varchar(160) NOT NULL DEFAULT '',

  -- 计价方法：先进先出 / 移动加权平均 / 批次成本 / 指定成本法。
  valuation_method varchar(24) NOT NULL,
  -- 计价币种（成本以哪个币种计量）。
  currency char(3) NOT NULL,

  -- 生效区间（timestamptz：生效是一个瞬间，不是一个日历日）。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  -- 同一范围内的版本号，从 1 递增。
  version integer NOT NULL,
  -- 上一版本（逻辑引用，同表）：第一个版本为空。
  supersedes_id uuid,

  -- 🔴 **切换方法需重算，但重算属 12.4.1.2**：本模块只**标记**「需要重算」，
  --   **不执行**重算、**不写**任何重算结果。
  recalculation_status varchar(16) NOT NULL DEFAULT 'NOT_REQUIRED',
  recalculation_owner_module varchar(16) NOT NULL DEFAULT '12.4.1.2',

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一范围的版本号唯一。
  UNIQUE(tenant_id, scope_type, scope_ref_id, version),

  FOREIGN KEY (tenant_id, supersedes_id) REFERENCES finance_cost_valuation_method(tenant_id, id),

  CHECK(scope_type IN ('LEGAL_ENTITY','CATEGORY')),
  CHECK(btrim(scope_code) <> ''),
  CHECK(valuation_method IN ('FIFO','MOVING_AVERAGE','BATCH','SPECIFIED')),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  CHECK(status IN ('ACTIVE','SUPERSEDED','CANCELLED')),
  CHECK(version >= 1),
  CHECK(recalculation_status IN ('NOT_REQUIRED','PENDING')),
  -- 🔴 重算的执行方永远是 12.4.1.2，本模块改不了。
  CHECK(recalculation_owner_module = '12.4.1.2')
);

CREATE INDEX finance_cost_valuation_method_by_scope
  ON finance_cost_valuation_method(tenant_id, scope_type, scope_ref_id, version DESC);
CREATE INDEX finance_cost_valuation_method_by_status
  ON finance_cost_valuation_method(tenant_id, status, effective_from);
CREATE INDEX finance_cost_valuation_method_by_method
  ON finance_cost_valuation_method(tenant_id, valuation_method, effective_from);

-- 同一范围同时只有一个生效版本（部分唯一索引）。
CREATE UNIQUE INDEX finance_cost_valuation_method_one_active
  ON finance_cost_valuation_method(tenant_id, scope_type, scope_ref_id)
  WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 2. 指定成本法下的指定单价（按 SKU + 币种）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_valuation_specified_cost (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  currency char(3) NOT NULL,
  -- 指定单价（**必须 > 0**：指定成本法下单价为 0 等于「这个 SKU 白送」，
  -- 那是参数没维护，不是成本为 0 ⇒ 用「未维护」（本行不存在）表达，不许写 0）。
  unit_cost numeric(19,4) NOT NULL,

  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  version integer NOT NULL,

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, sku_id, currency, version),

  CHECK(btrim(currency) ~ '^[A-Z]{3}$'),
  CHECK(unit_cost > 0),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  CHECK(status IN ('ACTIVE','SUPERSEDED','CANCELLED')),
  CHECK(version >= 1)
);

CREATE INDEX finance_cost_valuation_specified_cost_by_sku
  ON finance_cost_valuation_specified_cost(tenant_id, sku_id, currency, version DESC);
CREATE INDEX finance_cost_valuation_specified_cost_by_status
  ON finance_cost_valuation_specified_cost(tenant_id, status, effective_from);

CREATE UNIQUE INDEX finance_cost_valuation_specified_cost_one_active
  ON finance_cost_valuation_specified_cost(tenant_id, sku_id, currency)
  WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 3. 试算批次（表头）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_valuation_trial (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 试算所依据的配置范围（逻辑引用 + 快照）。
  scope_type varchar(16) NOT NULL,
  scope_ref_id uuid NOT NULL,
  scope_code varchar(64) NOT NULL DEFAULT '',
  scope_name varchar(160) NOT NULL DEFAULT '',

  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  currency char(3) NOT NULL,

  -- 本次试算的出库数量（要算多少的成本）。
  issue_quantity numeric(19,6) NOT NULL,
  -- 可用数量（上游真实成本层合计）—— 由服务层按上游事实写入。
  available_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 参与计算的成本层条数。
  layer_count integer NOT NULL DEFAULT 0,

  -- 成本层来源：catalog 的 5.2.1.1 成本构成（真实到货记录派生的批次清单）。
  source_service varchar(32) NOT NULL DEFAULT 'catalog',
  source_window_from date,
  source_window_to date,
  -- 来源读不到 / 不完整的说明（**如实写**，例如「上游不可达」）。
  source_note text NOT NULL DEFAULT '',
  -- 口径说明（随结果一起返回，避免半年后没人说得清口径）。
  basis_note text NOT NULL DEFAULT '',
  -- 试算时刻**生效的**计价方法（快照）：试算是「如果用这个方法会算出多少」，
  -- 对比时要有明确的基准，所以把它随试算一起存下来。
  effective_method varchar(24),
  effective_method_cost_amount numeric(19,4),

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(scope_type IN ('LEGAL_ENTITY','CATEGORY')),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(issue_quantity > 0),
  CHECK(available_quantity >= 0),
  CHECK(layer_count >= 0),
  CHECK(effective_method IS NULL OR effective_method IN ('FIFO','MOVING_AVERAGE','BATCH','SPECIFIED')),
  CHECK(effective_method_cost_amount IS NULL OR effective_method_cost_amount >= 0)
);

CREATE INDEX finance_cost_valuation_trial_by_sku
  ON finance_cost_valuation_trial(tenant_id, sku_id, created_at DESC);
CREATE INDEX finance_cost_valuation_trial_by_scope
  ON finance_cost_valuation_trial(tenant_id, scope_type, scope_ref_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. 试算结果（每个方法一行）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_cost_valuation_trial_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  trial_id uuid NOT NULL,

  valuation_method varchar(24) NOT NULL,
  currency char(3) NOT NULL,

  -- 该方法下能覆盖的数量 / 覆盖不了的数量（覆盖不了 = 库存不足）。
  covered_quantity numeric(19,6) NOT NULL DEFAULT 0,
  shortfall_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 单位成本与成本金额：**不可比时一律 NULL**（不留 0 冒充「算出来是 0」）。
  unit_cost numeric(19,4),
  cost_amount numeric(19,4),
  -- 与**当前生效方法**的成本差额（正 = 这个方法更贵）。
  -- 库存不足导致覆盖不全时**不给差额** —— 半截成本与全成本相减是假的。
  difference_to_effective_method numeric(19,4),

  -- 可比 = 这个方法在**这批真实数据上**算得出结果。
  comparable boolean NOT NULL,
  -- 不可比的原因（读不到 / 未维护 / 未指定批次 …）。
  gap_note text NOT NULL DEFAULT '',
  -- 这个方法的口径说明。
  basis_note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个试算批次里，一个方法只有一行结果。
  UNIQUE(tenant_id, trial_id, valuation_method),

  FOREIGN KEY (tenant_id, trial_id) REFERENCES finance_cost_valuation_trial(tenant_id, id),

  CHECK(valuation_method IN ('FIFO','MOVING_AVERAGE','BATCH','SPECIFIED')),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(covered_quantity >= 0),
  CHECK(shortfall_quantity >= 0),
  CHECK(unit_cost IS NULL OR unit_cost >= 0),
  CHECK(cost_amount IS NULL OR cost_amount >= 0),
  -- 可比 ⇒ 有数；不可比 ⇒ **不许有数**（否则「算不出来」会被显示成「成本 0」）。
  CHECK((comparable = true AND unit_cost IS NOT NULL AND cost_amount IS NOT NULL)
     OR (comparable = false AND unit_cost IS NULL AND cost_amount IS NULL))
);

CREATE INDEX finance_cost_valuation_trial_result_by_trial
  ON finance_cost_valuation_trial_result(tenant_id, trial_id);
CREATE INDEX finance_cost_valuation_trial_result_by_method
  ON finance_cost_valuation_trial_result(tenant_id, valuation_method);

-- ===========================================================================
-- 5. 数据层不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 方法配置守卫：版本递增 + 生效起点递进 + 必须接在当前版本之后
-- ---------------------------------------------------------------------------
--   「版本号跳号」「新版本的生效时间早于旧版本」「没有接在上一版之后就插入」
--   —— 这三类错在应用层都写得进去（只是没人会手滑而已），
--   而它们一旦写进去，「当时生效的是哪一版」就再也答不出来了。
--
--   ⚠️ 守卫比对的是**版本号最大的那一版**（而不是 status='ACTIVE' 的那一行）：
--   写库顺序是「先把上一版置为 SUPERSEDED 并收口生效区间，再插入新的一版」，
--   插入时库里**已经不存在** ACTIVE 行 —— 若守卫拿 ACTIVE 行来比，
--   就会要求 supersedes_id 为空，正常的版本切换反而会被挡住。
--   「同一范围同时只有一个生效版本」由**部分唯一索引**钉死（不是靠这里的守卫）。
CREATE OR REPLACE FUNCTION finance_cost_valuation_method_guard() RETURNS trigger AS $$
DECLARE
  v_last finance_cost_valuation_method%ROWTYPE;
BEGIN
  -- 上一版本（版本号最大的那一条）。
  SELECT * INTO v_last
    FROM finance_cost_valuation_method x
   WHERE x.tenant_id = NEW.tenant_id
     AND x.scope_type = NEW.scope_type
     AND x.scope_ref_id = NEW.scope_ref_id
   ORDER BY x.version DESC
   LIMIT 1;

  IF NOT FOUND THEN
    IF NEW.version <> 1 THEN
      RAISE EXCEPTION '计价方法配置的第一个版本必须是 1，实际是 %', NEW.version
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.supersedes_id IS NOT NULL THEN
      RAISE EXCEPTION '计价方法配置的第一个版本没有可接续的版本，supersedes_id 必须为空'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.version <> v_last.version + 1 THEN
    RAISE EXCEPTION '计价方法配置版本号必须递增：上一个版本是 %，本次是 %',
      v_last.version, NEW.version USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.supersedes_id IS DISTINCT FROM v_last.id THEN
    RAISE EXCEPTION '新版本必须接在上一版本之后（supersedes_id = %）', v_last.id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 历史成本不能被新配置追溯改写 ⇒ 生效起点必须严格递进。
  IF NEW.effective_from <= v_last.effective_from THEN
    RAISE EXCEPTION '新版本的生效时间必须晚于上一版本（%），否则历史成本会被追溯改写',
      v_last.effective_from USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_valuation_method_guard
  BEFORE INSERT ON finance_cost_valuation_method
  FOR EACH ROW EXECUTE FUNCTION finance_cost_valuation_method_guard();

-- ---------------------------------------------------------------------------
-- (b) 历史不可改写：已存在的版本行不许改方法 / 范围 / 版本号 / 生效起点
-- ---------------------------------------------------------------------------
--   「切换方法需重算并留版本对比」的前提是**版本对比真的能对比出差异**。
--   如果旧版本的方法可以就地改掉，那版本对比就变成「同一个方法跟自己比」，
--   历史也就不再是证据。
--   ⇒ 只允许改 status / effective_to / note / updated_at。
CREATE OR REPLACE FUNCTION finance_cost_valuation_method_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.valuation_method IS DISTINCT FROM OLD.valuation_method
     OR NEW.scope_type IS DISTINCT FROM OLD.scope_type
     OR NEW.scope_ref_id IS DISTINCT FROM OLD.scope_ref_id
     OR NEW.scope_code IS DISTINCT FROM OLD.scope_code
     OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id THEN
    RAISE EXCEPTION '计价方法配置的历史版本不可改写（只能置为 SUPERSEDED / CANCELLED 并收口生效区间）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_valuation_method_immutable
  BEFORE UPDATE ON finance_cost_valuation_method
  FOR EACH ROW EXECUTE FUNCTION finance_cost_valuation_method_immutable();

-- ---------------------------------------------------------------------------
-- (c) 指定单价守卫：版本递增 + 生效起点递进 + 同时只有一个生效值
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_cost_valuation_specified_cost_guard() RETURNS trigger AS $$
DECLARE
  v_max_version integer;
  v_last finance_cost_valuation_specified_cost%ROWTYPE;
BEGIN
  SELECT max(x.version) INTO v_max_version
    FROM finance_cost_valuation_specified_cost x
   WHERE x.tenant_id = NEW.tenant_id
     AND x.sku_id = NEW.sku_id
     AND x.currency = NEW.currency;

  IF v_max_version IS NULL THEN
    IF NEW.version <> 1 THEN
      RAISE EXCEPTION '指定成本单价的第一个版本必须是 1，实际是 %', NEW.version
        USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    IF NEW.version <> v_max_version + 1 THEN
      RAISE EXCEPTION '指定成本单价版本号必须递增：上一个版本是 %，本次是 %',
        v_max_version, NEW.version USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  SELECT * INTO v_last
    FROM finance_cost_valuation_specified_cost x
   WHERE x.tenant_id = NEW.tenant_id
     AND x.sku_id = NEW.sku_id
     AND x.currency = NEW.currency
   ORDER BY x.version DESC
   LIMIT 1;

  IF FOUND AND NEW.effective_from <= v_last.effective_from THEN
    RAISE EXCEPTION '新版本的生效时间必须晚于上一版本（%）', v_last.effective_from
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_valuation_specified_cost_guard
  BEFORE INSERT ON finance_cost_valuation_specified_cost
  FOR EACH ROW EXECUTE FUNCTION finance_cost_valuation_specified_cost_guard();

-- 历史不可改写（与上面同一纪律）。
CREATE OR REPLACE FUNCTION finance_cost_valuation_specified_cost_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.unit_cost IS DISTINCT FROM OLD.unit_cost
     OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
    RAISE EXCEPTION '指定成本单价的历史版本不可改写'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_valuation_specified_cost_immutable
  BEFORE UPDATE ON finance_cost_valuation_specified_cost
  FOR EACH ROW EXECUTE FUNCTION finance_cost_valuation_specified_cost_immutable();

-- ---------------------------------------------------------------------------
-- (d) 试算守卫：覆盖量 + 缺口量 = 试算量
-- ---------------------------------------------------------------------------
--   「这批货够不够」是试算里最容易被糊弄过去的一格：
--   缺口量被写成 0，界面就会显示「按 FIFO 成本 12.00」，
--   而实际上库存只够一半 ⇒ 那个数字是假的。
--   ⇒ 等式由 DB 层钉死。
CREATE OR REPLACE FUNCTION finance_cost_valuation_trial_result_guard() RETURNS trigger AS $$
DECLARE
  v_issue numeric(19,6);
BEGIN
  SELECT t.issue_quantity INTO v_issue
    FROM finance_cost_valuation_trial t
   WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.trial_id;

  IF v_issue IS NULL THEN
    RAISE EXCEPTION '试算批次不存在或不属于本租户' USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF round(NEW.covered_quantity + NEW.shortfall_quantity, 6) <> round(v_issue, 6) THEN
    RAISE EXCEPTION '覆盖数量（%）+ 缺口数量（%）必须等于试算数量（%）',
      NEW.covered_quantity, NEW.shortfall_quantity, v_issue USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_valuation_trial_result_guard
  BEFORE INSERT OR UPDATE ON finance_cost_valuation_trial_result
  FOR EACH ROW EXECUTE FUNCTION finance_cost_valuation_trial_result_guard();

-- ===========================================================================
-- 6. 多租户（RLS isolation）+ 授权
-- ===========================================================================

ALTER TABLE finance_cost_valuation_method ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_valuation_method FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_valuation_method
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_valuation_specified_cost ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_valuation_specified_cost FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_valuation_specified_cost
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_valuation_trial ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_valuation_trial FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_valuation_trial
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_valuation_trial_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_valuation_trial_result FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_valuation_trial_result
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_valuation_method TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_valuation_method TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_valuation_specified_cost TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_valuation_specified_cost TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_valuation_trial TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_valuation_trial TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_valuation_trial_result TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_valuation_trial_result TO starcon_app;

-- ===========================================================================
-- 7. 留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.4.1.2 成本计算执行（未交付）**：本模块产出的是**配置面** ——
--     方法、生效区间、版本链。它下游要读的是 `finance_cost_valuation_method` 里
--     `status='ACTIVE'` 且落在期间内的那一行（唯一真相），**不必再抄一份方法**。
--     重算执行由 12.4.1.2 在**自己的**表里落成本层与结转金额；
--     本表的 `recalculation_status='PENDING'` 是「需要重算」的**标记**，
--     12.4.1.2 落地后由它自己把标记改掉（那是它那张表的语义，不是本表的）。
--   * **12.4.1.3 成本差异分析（未交付）**：它的输入是 12.4.1.2 的成本计算结果，
--     本模块**不产出**任何差异数字。
--   * **13 利润（未交付）**：计价方法会影响成本，进而影响利润 ——
--     但本模块**不产出任何利润数字**，只产出「用的是哪一版方法」。
--   * **5.2.1.1 成本构成（catalog，已交付）**：它按成本法算 SKU 单位成本，
--     本模块的**试算**读它的真实成本层（到货记录派生的批次清单），
--     **不复制**它的成本分项 —— 跨服务只读，不建影子表。
