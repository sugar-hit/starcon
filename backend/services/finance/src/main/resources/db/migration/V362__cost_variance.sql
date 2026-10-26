-- ===========================================================================
-- V362 · 12.4.1.3 成本差异分析
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.4.1.3 成本差异分析）原文：
--
--   职责：分析成本差异：实际成本 vs 预算/标准成本、采购价波动、头程成本波动、
--         损耗差异、汇率影响，定位利润波动主因。
--   输入：成本计算结果；预算或历史基线；价格与汇率历史。
--   输出：差异分析报表 + 主因归因 + 异常提示。
--   关系：上游 = 12.4.1.2；下游 = 13 分析、6 采购（价格谈判依据）。
--         **利润下滑时先看成本差异**，比看销售额更能定位问题。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移做什么 / 不做什么（先把边界钉死，再写 DDL）
-- ---------------------------------------------------------------------------
--
--   本模块写**五张表**（全部新建，零 ALTER 零 ADD COLUMN 到别人的表）：
--     finance_cost_variance_baseline    标准成本 / 预算基线（**版本链**）
--     finance_cost_variance_report      差异分析报表（表头：范围 / 期间 / 口径 / 汇总）
--     finance_cost_variance_item        报表明细（逐 SKU × 驱动因素）
--     finance_cost_variance_driver      按币种分组的驱动因素汇总 + **主因归因**
--     finance_cost_variance_alert       异常提示
--
--   **本模块不写的**：
--     · **不改 12.4.1.2 的表**（`finance_cost_calculation_run` / `_layer` / `_entry`
--       一律**只读**）—— 差异分析的**基数必须是 12.4.1.2 的真实结转金额**，
--       本模块自己再算一遍成本就变成第二个真相来源；
--     · **不写库存**（warehouse 的 inventory_transaction / inventory_entry 是数量账，
--       本模块只**读**，一条都不改）；
--     · **不写采购价 / 头程成本**（trade / warehouse 的表不在本库，走授权只读端口）；
--     · **不写凭证**（12.5.2 已交付，由下游取）；
--     · **不建利润**（13 未交付）；
--     · **零 ALTER 别人的表**：本文件只有 CREATE。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 基线本仓没有 ⇒ 本模块必须自建
-- ---------------------------------------------------------------------------
--
--   12.4.1.1（V355）只维护「计价方法」与「指定单价」，**没有**「标准成本 / 预算」
--   这个概念；仓库里也没有任何一张预算表 ⇒ 「实际成本 vs 预算/标准成本」这一类差异
--   **在本模块之前根本算不出来**。
--
--   ⇒ 本模块建 `finance_cost_variance_baseline`：按 **SKU 或类目**维度 + **币种**
--     + **生效区间** + **版本链**（手法照 12.6.1.1 税率的版本链）：
--       · 改基线 = **追加新版本**（触发器钉死版本号递增 + 生效起点严格递进
--         + 新版本必须接在上一版之后 + 生效区间不许重叠）；
--       · 已存在的版本行**不许**改基线值 / 维度 / 币种 / 版本号 / 生效起点；
--       · 同一版本链同时只有一个生效版本（部分唯一索引）。
--
--   🔴 **基线缺失 ≠ 差异为 0**：某 SKU 某期间没有基线时，该 SKU 的差异
--     **不可计算** —— 报表里如实显示「无基线，不可比」，
--     **绝不**默默按 0 差异算、**绝不**把缺失当 0。
--     `finance_cost_variance_item.comparability = 'NO_BASELINE'` 把这件事钉在数据层。
--
-- ---------------------------------------------------------------------------
-- 2. 为什么报表要落库（而不是每次现算）
-- ---------------------------------------------------------------------------
--
--   12.4.1.2 的「差异对比」**不落表**（那是两次批次之间逐 SKU 的纯 Σ，存一份就是
--   第二个真相来源）。**本模块不一样**，理由有三：
--
--     (a) 差异分析的输入**不止** 12.4.1.2 的分录：还含**基线版本**（用户维护的配置）
--         与**汇率快照**（catalog 的外部事实）。不记录「当时用的是哪一版基线、
--         哪一档汇率」，半年后没人说得清「这个结论是怎么来的」；
--     (b) 「主因归因」是一个**结论**，结论必须有留痕（当时主因是采购价还是头程）；
--     (c) 「异常提示」是**一次性事件**（「这次分析发现 3 个 SKU 无基线」），
--         现算会把「上次的异常」和「这次的异常」混成一团。
--
--   ⇒ 报表是一次**分析事实**：它引用（不复制）12.4.1.2 的批次与基线版本，
--     成本真相仍在 12.4.1.2 那里，本表只记录「那次分析的结论」。
--
--   报表**不可改写**（触发器钉死 UPDATE / DELETE）：历史即证据。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 多币种绝不相加
-- ---------------------------------------------------------------------------
--
--   成本差异**按币种分组**：`finance_cost_variance_driver` 与
--   `finance_cost_variance_item` 都带 `currency`，唯一键里也带它。
--   ⇒ 不同币种的金额**永远不会被加到一起**（DB 层做不到）。
--   折算只发生在**汇率影响**那一类（且必须真的查到汇率；查不到就是「汇率不可得」，
--   **不许按 1:1 兜底**）。
--
-- ---------------------------------------------------------------------------
-- 4. 五类差异：各自独立可看
-- ---------------------------------------------------------------------------
--
--   driver 的取值（表级 CHECK 钉死）：
--     STANDARD_COST  实际成本 vs 预算 / 标准成本（**总差异**）
--     PURCHASE_PRICE  采购价波动（trade 6 采购，只读端口）
--     FIRST_MILE      头程成本波动（warehouse 10.4.1.1，只读端口；**单据级**，
--                     只进 driver 表，不进 item 表 —— 它不是某个 SKU 的差异）
--     SHRINKAGE       损耗差异（warehouse 7.1.2.1 的调整流水，只读）
--     FX              汇率影响（catalog 5.2.2.1，只读）
--
--   🔴 STANDARD_COST 与 FX 是**构成关系**（汇率影响是总差异的一个组成部分），
--     PURCHASE_PRICE / FIRST_MILE / SHRINKAGE 是**独立观察到的驱动因素**
--     （它们解释「单位成本为什么动了」，各自有自己的口径）。
--     这两类**不可相加** ⇒ 表里不提供「五类之和」，driver 表只给**贡献排名**。
--
-- ---------------------------------------------------------------------------
-- 5. 金额与数量
-- ---------------------------------------------------------------------------
--
--   金额 NUMERIC(19,4)（与同服务 V351 / V355 / V358 一致）；数量 NUMERIC(19,6)。
--   全程 BigDecimal，无浮点。时间列一律 timestamptz；服务层用 OffsetDateTime 绑定，
--   values 位置写 `?::timestamptz`。
--
-- ===========================================================================

-- ===========================================================================
-- 一、finance_cost_variance_baseline · 标准成本 / 预算基线（版本链）
-- ===========================================================================
--
-- 🔴 一个租户在同一个（基线类型 × 维度 × 币种）下可以有多版基线；生效区间**左闭右开**
--   [effective_from, effective_to)，effective_to 为 NULL 表示「这一版还没被换掉」。
--
-- 🔴 基线是**租户级事实**：同一个 SKU，不同租户的标准成本可能不同 ⇒ 必须带 tenant_id。
--
-- 🔴 基线**零预置行**：标准成本是用户自己的会计政策，系统只提供结构，
--   **不替用户决定**（与 12.4.1.1「系统提供选项与试算，不替用户决定政策」同一纪律）。
CREATE TABLE finance_cost_variance_baseline (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,

  -- 基线类型：标准成本 / 预算。
  baseline_type     varchar(16) NOT NULL
                    CHECK(baseline_type IN ('STANDARD_COST','BUDGET')),
  -- 维度：按 SKU 或按类目。
  dimension         varchar(16) NOT NULL
                    CHECK(dimension IN ('SKU','CATEGORY')),
  -- 🔴 **链键**（版本链的键）：SKU 维度 = SKU 的 uuid；类目维度 = 'CATEGORY:<类目编码>'。
  --   为什么不用一个列装两种：类目在 catalog 的 SKU 主数据里只有**编码**没有 uuid
  --   （CatalogSkuPort 的 category 字段是编码），拿不到 uuid 就没有唯一键。
  --   显式落一列链键 ⇒ 唯一约束与触发器都用同一个口径，不依赖 COALESCE 表达式索引。
  dimension_key     varchar(200) NOT NULL CHECK(btrim(dimension_key) <> ''),
  -- 跨服务逻辑引用（catalog 的 SKU），**不建 FK**（AGENTS.md：禁止跨库 FK）。
  --   类目维度时为空（类目按编码索引）。
  dimension_ref_id  uuid,
  -- 名称快照（改名不改历史）：SKU 编码 / 类目编码 + 名称。
  dimension_code    varchar(64) NOT NULL DEFAULT '',
  dimension_name    varchar(200) NOT NULL DEFAULT '',

  -- 基线币种（**绝不跨币种相加**）。
  currency          char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 标准单位成本 / 预算单位成本。
  -- 🔴 必须 > 0：基线写成 0 等于「这个 SKU 白送」，那是参数没维护，
  --   不是成本为 0 ⇒ 用「未维护」（本行不存在）表达，不许写 0。
  unit_cost         numeric(19,4) NOT NULL CHECK(unit_cost > 0),

  -- 生效区间（左闭右开）。
  effective_from    timestamptz NOT NULL,
  effective_to      timestamptz,
  -- ACTIVE = 当前这版；SUPERSEDED = 已被新版本接替；ARCHIVED = 撤下（不接替）。
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','SUPERSEDED','ARCHIVED')),
  -- 版本号（同一 版本链 内递增，从 1 开始）。
  version           int NOT NULL CHECK(version >= 1),
  -- 接续的上一版（版本链）。
  supersedes_id     uuid,

  note              text NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, baseline_type, dimension, dimension_key, currency, version),
  -- 同一版本链内生效起点唯一（两版不能从同一刻开始）。
  UNIQUE(tenant_id, baseline_type, dimension, dimension_key, currency, effective_from),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- ACTIVE ⇔ 未收口；SUPERSEDED / ARCHIVED ⇔ 已收口（生效区间必须有终点）。
  CHECK((status = 'ACTIVE') = (effective_to IS NULL)),
  -- SKU 维度必须有 uuid 引用；类目维度按编码索引（uuid 为空）。
  CHECK((dimension = 'SKU' AND dimension_ref_id IS NOT NULL)
     OR (dimension = 'CATEGORY' AND dimension_ref_id IS NULL))
);
COMMENT ON TABLE finance_cost_variance_baseline IS
  '12.4.1.3 · 标准成本 / 预算基线（租户级，按 基线类型 × 维度 × 币种 × 生效区间 版本化）。'
  '本仓此前**没有**标准成本 / 预算表 ⇒ 「实际成本 vs 预算/标准成本」这一类差异在 12.4.1.3 之前算不出来，'
  '本表是它的唯一真相源。基线**零预置行** —— 系统只提供结构，不替用户决定政策。'
  '生效区间左闭右开且不许重叠；历史版本不可改写；改基线 = 追加新版本。'
  '🔴 基线缺失 ≠ 差异为 0：查不到基线时差异**不可计算**，报表如实写「无基线，不可比」。';

CREATE INDEX finance_cost_variance_baseline_by_dimension
  ON finance_cost_variance_baseline(tenant_id, dimension, dimension_key, currency, effective_from DESC);
CREATE INDEX finance_cost_variance_baseline_by_status
  ON finance_cost_variance_baseline(tenant_id, status, effective_from);

-- 同一版本链同时只能有一个生效版本
CREATE UNIQUE INDEX finance_cost_variance_baseline_one_active
  ON finance_cost_variance_baseline(tenant_id, baseline_type, dimension, dimension_key, currency)
  WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- (a) 版本链守卫：版本号递增 + 生效起点严格递进 + 必须接在上一版之后
-- ---------------------------------------------------------------------------
--   ⚠️ 守卫比对的是**版本号最大的那一版**（而不是 status='ACTIVE' 的那一行）：
--   写库顺序是「先把上一版置为 SUPERSEDED 并收口生效区间，再插入新的一版」，
--   插入时库里已经不存在 ACTIVE 行 —— 若守卫拿 ACTIVE 行来比，
--   正常的版本切换反而会被挡住。
CREATE OR REPLACE FUNCTION finance_cost_variance_baseline_guard() RETURNS trigger AS $$
DECLARE
  v_last finance_cost_variance_baseline%ROWTYPE;
BEGIN
  SELECT * INTO v_last
    FROM finance_cost_variance_baseline x
   WHERE x.tenant_id = NEW.tenant_id
     AND x.baseline_type = NEW.baseline_type
     AND x.dimension = NEW.dimension
     AND x.dimension_key = NEW.dimension_key
     AND x.currency = NEW.currency
   ORDER BY x.version DESC
   LIMIT 1;

  IF NOT FOUND THEN
    IF NEW.version <> 1 THEN
      RAISE EXCEPTION '基线的第一个版本必须是 1，实际是 %', NEW.version
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.supersedes_id IS NOT NULL THEN
      RAISE EXCEPTION '基线的第一个版本没有可接续的版本，supersedes_id 必须为空'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.version <> v_last.version + 1 THEN
    RAISE EXCEPTION '基线版本号必须递增：上一个版本是 %，本次是 %',
      v_last.version, NEW.version USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.supersedes_id IS DISTINCT FROM v_last.id THEN
    RAISE EXCEPTION '新版本必须接在上一版本之后（supersedes_id = %）', v_last.id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 历史差异分析用当时基线，不能被新政策追溯修改 ⇒ 生效起点必须严格递进。
  IF NEW.effective_from <= v_last.effective_from THEN
    RAISE EXCEPTION '新版本的生效时间必须晚于上一版本（%），否则历史差异分析会被追溯改写',
      v_last.effective_from USING ERRCODE = 'check_violation';
  END IF;

  -- 新版本必须接在上一版**之后**（不许与上一版的生效区间重叠）。
  IF v_last.effective_to IS NOT NULL AND NEW.effective_from < v_last.effective_to THEN
    RAISE EXCEPTION '新版本的生效时间（%）必须不早于上一版的失效时间（%）',
      NEW.effective_from, v_last.effective_to USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_variance_baseline_guard
  BEFORE INSERT ON finance_cost_variance_baseline
  FOR EACH ROW EXECUTE FUNCTION finance_cost_variance_baseline_guard();

-- ---------------------------------------------------------------------------
-- (b) 生效区间不许重叠：任意两条版本的区间不得有交集
-- ---------------------------------------------------------------------------
--   「某一时点命中哪一版」必须**唯一** —— 否则同一期间会命中两条基线，
--   而「命中不到就报「无基线」、命中两条也必须报错」都不该靠服务层兜底。
--   `tstzrange(from, NULL, '[)')` 的上界是**无穷** ⇒ 语义正好是「还没被换掉」。
CREATE OR REPLACE FUNCTION finance_cost_variance_baseline_no_overlap() RETURNS trigger AS $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM finance_cost_variance_baseline x
     WHERE x.tenant_id = NEW.tenant_id
       AND x.baseline_type = NEW.baseline_type
       AND x.dimension = NEW.dimension
       AND x.dimension_key = NEW.dimension_key
       AND x.currency = NEW.currency
       AND x.id <> NEW.id
       AND tstzrange(x.effective_from, x.effective_to, '[)')
          && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ) THEN
    RAISE EXCEPTION '同一基线版本链的生效区间不许重叠：与已有版本的生效区间有交集'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_variance_baseline_no_overlap
  BEFORE INSERT OR UPDATE ON finance_cost_variance_baseline
  FOR EACH ROW EXECUTE FUNCTION finance_cost_variance_baseline_no_overlap();

-- ---------------------------------------------------------------------------
-- (c) 历史不可改写：已存在的版本行不许改基线值 / 维度 / 币种 / 版本号 / 生效起点
-- ---------------------------------------------------------------------------
--   只允许改 status / effective_to（且只能「从空变成有值」这一次）/ note / updated_at。
CREATE OR REPLACE FUNCTION finance_cost_variance_baseline_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.baseline_type IS DISTINCT FROM OLD.baseline_type
     OR NEW.dimension IS DISTINCT FROM OLD.dimension
     OR NEW.dimension_key IS DISTINCT FROM OLD.dimension_key
     OR NEW.dimension_ref_id IS DISTINCT FROM OLD.dimension_ref_id
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.unit_cost IS DISTINCT FROM OLD.unit_cost
     OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
     OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id THEN
    RAISE EXCEPTION '已存在的基线版本不可改写（改基线请追加新版本）'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 生效区间只允许「从空变成有值」这一次动作（收口之后不许再改）。
  IF OLD.effective_to IS NOT NULL AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
    RAISE EXCEPTION '基线版本的失效时间一经写入不可改写' USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_variance_baseline_immutable
  BEFORE UPDATE ON finance_cost_variance_baseline
  FOR EACH ROW EXECUTE FUNCTION finance_cost_variance_baseline_immutable();

-- ===========================================================================
-- 二、finance_cost_variance_report · 差异分析报表（表头）
-- ===========================================================================
--
--   一行 = **一次差异分析**。
--
--   🔴 口径快照写在这一行上：
--     · `cost_run_ids` —— 基数来自哪几个 12.4.1.2 批次（跨服务逻辑引用 + 快照，
--       **不建 FK**：12.4.1.2 的表可以被重算另起批次，但历史批次不被改写）；
--     · `valuation_method` / `valuation_method_version` —— **批次行上的快照**，
--       不是「当前生效版本」（拿当前版本去套历史批次 = 把口径算错）；
--     · `reporting_currency` —— 报表币种（汇率影响要折算到它）。
CREATE TABLE finance_cost_variance_report (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,

  -- 计价范围：按法人 / 按品类（与 12.4.1.1 / 12.4.1.2 的范围口径完全一致）。
  scope_type        varchar(16) NOT NULL CHECK(scope_type IN ('LEGAL_ENTITY','CATEGORY')),
  scope_ref_id      uuid NOT NULL,
  scope_code        varchar(64) NOT NULL DEFAULT '',
  scope_name        varchar(160) NOT NULL DEFAULT '',

  -- 分析期间（timestamptz，左闭右开：from 含、to 不含）。
  period_from       timestamptz NOT NULL,
  period_to         timestamptz NOT NULL,

  -- 报表币种（汇率影响折算的目标币种）。**不同币种绝不相加**。
  reporting_currency char(3) NOT NULL CHECK(reporting_currency ~ '^[A-Z]{3}$'),

  -- 状态：COMPLETED（全部可算）/ PARTIAL（有不可算的类别，如缺基线或上游不可得）。
  status            varchar(16) NOT NULL DEFAULT 'COMPLETED'
                    CHECK(status IN ('COMPLETED','PARTIAL')),

  -- 基数来源：12.4.1.2 的批次（逗号分隔的 uuid 文本快照）。
  cost_run_ids      text NOT NULL DEFAULT '',
  -- 计价方法**快照**（取自批次行，不是「当前生效版本」）。
  valuation_method  varchar(24) NOT NULL DEFAULT '',
  valuation_method_version int NOT NULL DEFAULT 0,

  -- 口径说明（随结果一起返回，避免半年后没人说得清口径）。
  basis_note        text NOT NULL DEFAULT '',
  -- 来源完整性说明（如实写：哪个上游读不到、为什么不可比）。
  source_note       text NOT NULL DEFAULT '',

  -- 汇总（算完写回）。
  run_count         integer NOT NULL DEFAULT 0,
  sku_count         integer NOT NULL DEFAULT 0,
  item_count        integer NOT NULL DEFAULT 0,
  comparable_sku_count integer NOT NULL DEFAULT 0,
  no_baseline_sku_count integer NOT NULL DEFAULT 0,
  alert_count       integer NOT NULL DEFAULT 0,
  -- 出现过的币种（逗号分隔的 ISO 4217 文本；分组呈现的依据）。
  currencies        text NOT NULL DEFAULT '',

  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(period_to > period_from),
  CHECK(run_count >= 0 AND sku_count >= 0 AND item_count >= 0 AND alert_count >= 0),
  CHECK(comparable_sku_count >= 0 AND no_baseline_sku_count >= 0),
  CHECK(valuation_method_version >= 0)
);
COMMENT ON TABLE finance_cost_variance_report IS
  '12.4.1.3 · 差异分析报表（一次分析一行）。'
  '基数 = 12.4.1.2 的真实结转金额（只读，本模块不重算成本）。'
  '计价方法取**批次行上的快照**，不是「当前生效版本」。'
  '🔴 报表一经生成不可改写（触发器钉死）—— 历史即证据。';

CREATE INDEX finance_cost_variance_report_by_period
  ON finance_cost_variance_report(tenant_id, period_from DESC, period_to);
CREATE INDEX finance_cost_variance_report_by_scope
  ON finance_cost_variance_report(tenant_id, scope_type, scope_ref_id, period_from DESC);
CREATE INDEX finance_cost_variance_report_by_created
  ON finance_cost_variance_report(tenant_id, created_at DESC, id);

-- ---------------------------------------------------------------------------
-- (d) 报表不可改写：一次分析就是一个事实，不许事后修饰
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_cost_variance_report_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '差异分析报表不可改写或删除（重新分析请再生成一张）'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_variance_report_immutable
  BEFORE UPDATE OR DELETE ON finance_cost_variance_report
  FOR EACH ROW EXECUTE FUNCTION finance_cost_variance_report_immutable();

-- ===========================================================================
-- 三、finance_cost_variance_item · 报表明细（逐 SKU × 驱动因素）
-- ===========================================================================
--
--   一行 = 「这次分析里，这个 SKU、这个币种、这一类差异」。
--
--   🔴 **STANDARD_COST 行是「总差异」**（实际成本 − 标准成本，同币种）：
--     实际 / 标准 / 总量都写在它自己这一行上（其余驱动因素行不重复）。
--   🔴 FIRST_MILE（头程成本波动）是**单据级**的（按头程发运单，不按 SKU）
--     ⇒ 它**只进 driver 表**，不进本表。
--
--   🔴 **可比性写在行上**（`comparability` + `comparable_reason`）：
--     「无基线，不可比」不是一个 0，而是一个**状态**。
CREATE TABLE finance_cost_variance_item (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  report_id         uuid NOT NULL,

  sku_id            uuid NOT NULL,
  sku_code          varchar(64) NOT NULL DEFAULT '',
  currency          char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 驱动因素：标准成本差异 / 采购价波动 / 损耗差异 / 汇率影响。
  driver            varchar(24) NOT NULL
                    CHECK(driver IN ('STANDARD_COST','PURCHASE_PRICE','SHRINKAGE','FX')),

  -- 该驱动因素的金额（正数 = 推高成本，负数 = 拉低成本）。
  -- 🔴 不可计算时为 **NULL**，不是 0。
  amount            numeric(19,4),
  -- 数量口径（如损耗数量）。
  quantity          numeric(19,6),

  -- STANDARD_COST 行专有：实际 / 标准 / 总量。
  actual_quantity   numeric(19,6),
  actual_cost_amount numeric(19,4),
  standard_cost_amount numeric(19,4),
  -- 标准成本的单价与它那一版基线（版本链的哪一版）。
  baseline_unit_cost numeric(19,4),
  baseline_id       uuid,
  baseline_version  int,

  -- 可比性：COMPARABLE / NO_BASELINE / CURRENCY_MISMATCH /
  --         UPSTREAM_UNAVAILABLE / NOT_APPLICABLE。
  comparability     varchar(24) NOT NULL
                    CHECK(comparability IN
                      ('COMPARABLE','NO_BASELINE','CURRENCY_MISMATCH',
                       'UPSTREAM_UNAVAILABLE','NOT_APPLICABLE')),
  -- 为什么不可比 / 不可计算（如实写，不留给下游猜）。
  comparable_reason text NOT NULL DEFAULT '',
  -- 补充说明（口径、来源、注意事项）。
  note              text NOT NULL DEFAULT '',

  created_at        timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一张报表里，同一个 SKU、同一个币种、同一类差异只有一行。
  UNIQUE(tenant_id, report_id, sku_id, currency, driver),

  FOREIGN KEY (tenant_id, report_id) REFERENCES finance_cost_variance_report(tenant_id, id),

  -- 🔴 金额**允许负数**（负 = 拉低成本），所以这里不钉符号；只钉「可空」。
  CHECK(quantity IS NULL OR quantity >= 0),
  CHECK(actual_quantity IS NULL OR actual_quantity >= 0),
  CHECK(actual_cost_amount IS NULL OR actual_cost_amount >= 0),
  CHECK(standard_cost_amount IS NULL OR standard_cost_amount >= 0),
  CHECK(baseline_unit_cost IS NULL OR baseline_unit_cost > 0),
  CHECK(baseline_version IS NULL OR baseline_version >= 1),
  -- 🔴 不可计算 ⇒ 金额必须是 NULL（不许写 0 冒充「差异是 0」）。
  CHECK(comparability <> 'COMPARABLE' OR amount IS NOT NULL)
);
COMMENT ON TABLE finance_cost_variance_item IS
  '12.4.1.3 · 差异分析明细（逐 SKU × 币种 × 驱动因素）。'
  'STANDARD_COST 行是**总差异**（实际 − 标准，同币种）；'
  'PURCHASE_PRICE / SHRINKAGE / FX 是独立观察到的驱动因素（与总差异是构成关系，不可相加）。'
  '🔴 不可比 / 不可计算时 amount 为 NULL、comparability 写明原因 —— 无基线不是「差异为 0」。';

CREATE INDEX finance_cost_variance_item_by_report
  ON finance_cost_variance_item(tenant_id, report_id);
CREATE INDEX finance_cost_variance_item_by_sku
  ON finance_cost_variance_item(tenant_id, sku_id, report_id);
CREATE INDEX finance_cost_variance_item_by_driver
  ON finance_cost_variance_item(tenant_id, report_id, driver, currency);

-- ===========================================================================
-- 四、finance_cost_variance_driver · 按币种分组的驱动因素汇总 + 主因归因
-- ===========================================================================
--
--   一行 = 「这次分析里，这个币种、这一类驱动因素」。
--
--   🔴 **这是「多币种分组呈现」与「主因归因」的落点**：
--     · `currency` 把不同币种的金额**物理隔开**（唯一键里带它 ⇒ DB 层加不到一起）；
--     · `rank` + `is_primary` + `contribution_share` 给出**主因归因**：
--       同一个币种里按 |金额| 排名，贡献最大的那一类就是该币种的主因。
--
--   🔴 `rank` 与 `is_primary` 只在**该币种内**有意义 ⇒ 唯一键是
--     (tenant_id, report_id, currency, driver)。
CREATE TABLE finance_cost_variance_driver (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  report_id         uuid NOT NULL,

  currency          char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  driver            varchar(24) NOT NULL
                    CHECK(driver IN ('STANDARD_COST','PURCHASE_PRICE','FIRST_MILE','SHRINKAGE','FX')),

  -- 该驱动因素在本报表币种下的金额（正 = 推高成本）。
  -- 🔴 不可计算时为 NULL（不是 0）。
  amount            numeric(19,4),
  quantity          numeric(19,6),

  -- STANDARD_COST 行专有：实际成本合计 / 标准成本合计 / 总差异。
  actual_cost_amount numeric(19,4),
  standard_cost_amount numeric(19,4),
  actual_quantity   numeric(19,6),
  standard_quantity numeric(19,6),

  -- 可比性（与 item 表同一套取值）。
  comparability     varchar(24) NOT NULL
                    CHECK(comparability IN
                      ('COMPARABLE','NO_BASELINE','CURRENCY_MISMATCH',
                       'UPSTREAM_UNAVAILABLE','NOT_APPLICABLE')),
  comparable_reason text NOT NULL DEFAULT '',

  -- 🔴 主因归因：该币种内按 |金额| 的贡献排名（1 = 最大贡献）。
  --   不可计算的类别不参与排名（rank 为 NULL）。
  rank              integer,
  is_primary        boolean NOT NULL DEFAULT false,
  -- 贡献占比（0~1；分母是该币种内可计算类别的 |金额| 之和）。
  contribution_share numeric(19,6),

  -- 口径说明（这个数怎么来的 —— 与 12.4.1.2 的「口径说明」同一纪律）。
  basis             text NOT NULL DEFAULT '',

  created_at        timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, report_id, currency, driver),

  FOREIGN KEY (tenant_id, report_id) REFERENCES finance_cost_variance_report(tenant_id, id),

  CHECK(amount IS NULL OR amount >= 0 OR amount < 0),
  CHECK(quantity IS NULL OR quantity >= 0),
  CHECK(rank IS NULL OR rank >= 1),
  CHECK(contribution_share IS NULL OR (contribution_share >= 0 AND contribution_share <= 1)),
  -- 🔴 不可计算 ⇒ 金额必须是 NULL（不许写 0 冒充「差异是 0」）。
  CHECK(comparability <> 'COMPARABLE' OR amount IS NOT NULL),
  -- 🔴 主因标记必须与排名一致：rank = 1 的那一行才是主因，且主因只能是可计算的。
  CHECK((is_primary AND rank = 1 AND comparability = 'COMPARABLE')
     OR (NOT is_primary))
);
COMMENT ON TABLE finance_cost_variance_driver IS
  '12.4.1.3 · 按币种分组的驱动因素汇总 + **主因归因**。'
  '🔴 多币种绝不相加：唯一键含 currency ⇒ 不同币种的金额在 DB 层就加不到一起。'
  '主因 = 该币种内按 |金额| 排名第一且**可计算**的驱动因素；不可计算不参与排名。';

CREATE INDEX finance_cost_variance_driver_by_report
  ON finance_cost_variance_driver(tenant_id, report_id, currency, rank);

-- ===========================================================================
-- 五、finance_cost_variance_alert · 异常提示
-- ===========================================================================
--
--   蓝图输出明写「**异常提示**」—— 它不是报表里的一句备注，
--   而是一条条**能被单独看、单独筛、单独定位到 SKU** 的记录。
CREATE TABLE finance_cost_variance_alert (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  report_id         uuid NOT NULL,

  -- 告警码（机器可读）。
  code              varchar(48) NOT NULL,
  -- 严重度：INFO / WARNING / CRITICAL。
  severity          varchar(16) NOT NULL
                    CHECK(severity IN ('INFO','WARNING','CRITICAL')),
  -- 相关币种 / SKU（可为空：与具体 SKU 无关的告警，如「上游不可得」）。
  currency          char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  sku_id            uuid,
  sku_code          varchar(64) NOT NULL DEFAULT '',
  -- 金额（告警涉及的金额，可为空）。
  amount            numeric(19,4),
  -- 人话说明（写清「哪里出了问题、为什么、该做什么」）。
  message           text NOT NULL DEFAULT '',

  created_at        timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY (tenant_id, report_id) REFERENCES finance_cost_variance_report(tenant_id, id),

  CHECK(btrim(code) <> '')
);
COMMENT ON TABLE finance_cost_variance_alert IS
  '12.4.1.3 · 异常提示（一次分析里逐条留痕）。'
  '典型告警：无基线（差异不可计算）/ 基线币种不一致 / 上游不可得 / 汇率不可得 / '
  '成本分录有缺口 / 差异超阈值。';

CREATE INDEX finance_cost_variance_alert_by_report
  ON finance_cost_variance_alert(tenant_id, report_id, severity);
CREATE INDEX finance_cost_variance_alert_by_code
  ON finance_cost_variance_alert(tenant_id, code, created_at DESC);

-- ===========================================================================
-- 六、多租户（RLS isolation）+ 授权
-- ===========================================================================
--
--   与 finance 既有迁移（V338 ~ V361）同一套写法。租户由 backend/libraries/tenancy
--   在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
--   ⚠️ 报表与其明细是**历史即证据**：报表表只授予 SELECT / INSERT（不给 UPDATE / DELETE），
--     明细 / 驱动因素 / 告警同理 —— 触发器之外再加一道权限防线。
ALTER TABLE finance_cost_variance_baseline ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_variance_baseline FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_variance_baseline
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_variance_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_variance_report FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_variance_report
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_variance_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_variance_item FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_variance_item
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_variance_driver ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_variance_driver FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_variance_driver
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_variance_alert ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_variance_alert FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_variance_alert
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_variance_baseline TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_variance_baseline TO starcon_app;
GRANT SELECT,INSERT ON finance_cost_variance_report TO finance_app;
GRANT SELECT,INSERT ON finance_cost_variance_report TO starcon_app;
GRANT SELECT,INSERT ON finance_cost_variance_item TO finance_app;
GRANT SELECT,INSERT ON finance_cost_variance_item TO starcon_app;
GRANT SELECT,INSERT ON finance_cost_variance_driver TO finance_app;
GRANT SELECT,INSERT ON finance_cost_variance_driver TO starcon_app;
GRANT SELECT,INSERT ON finance_cost_variance_alert TO finance_app;
GRANT SELECT,INSERT ON finance_cost_variance_alert TO starcon_app;

-- ===========================================================================
-- 七、留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13 利润与经营分析（未交付，reporting 服务）**：本模块产出**成本差异**，
--     利润自己去取 —— 本模块**不产出任何利润数字**。
--   * **6 采购（已交付，trade 服务）**：本模块的「采购价波动」是**价格谈判的依据**
--     （读 6 的采购分析事实，只读）—— 本模块**不回写**采购侧任何数据。
--   * **12.4.1.2 成本计算执行（已交付）**：本模块**只读**它的批次与成本分录，
--     基数永远是它算出来的应结转金额。
