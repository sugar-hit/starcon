-- 5.3.2.2 价格规则：把「什么时候该改价、改成什么价」沉淀成**可执行的规则**，
-- 并产出**命中记录**与**建议调价列表**。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：定义自动调价规则：触发条件（库存低于阈值/竞品降价/成本上涨/毛利低于下限/库龄超期）
--             + 动作（降价/涨价/暂停促销/下架），支持优先级与生效时段。
--   **输入**：规则条件与动作；作用范围；生效时段。
--   **输出**：规则记录 + 命中记录 + 建议调价列表。
--   **关系**：上游 = 7 库存、5.3.2.1；下游 = 5.4.1.1。
--             **自动调价必须先"建议"再"执行"**，高风险规则（降价超过 X%）需人工确认，
--             避免错误规则批量改价造成损失。
--
-- ============================================================ 第一节：本表与相邻模块的写入所有权
--
-- 本迁移落四张表，职责不重叠：
--
--   | 表                        | 一行是什么                              | 写入          |
--   |---------------------------|-----------------------------------------|---------------|
--   | pricing_rule              | 一条价格规则（条件 + 动作 + 优先级 + 时段）| 可改          |
--   | pricing_rule_scope        | 一条作用范围（分类 / 品牌 / 店铺 / SKU）   | 可改          |
--   | pricing_rule_hit          | 一次「规则 × SKU」的判定留痕（命中与否）   | 只追加        |
--   | pricing_rule_suggestion   | 一条建议调价（旧价 / 新价 / 是否需人工确认）| 只追加（状态可推进）|
--
-- 🔴 **本模块不改价**：改价是 5.4.1.1 调价单的写入所有权。
--   本模块产出的是**建议调价**（suggestion）。蓝图那句「自动调价必须先"建议"再"执行"」
--   落在本表的状态机上：`pricing_rule_suggestion.status` 走
--   PENDING_CONFIRMATION → CONFIRMED / REJECTED；低风险建议直接是 READY。
--   「执行」= 放行给 5.4.1.1，**本表永远不写任何价格**。
--
-- 🔴 **本模块不建价目表**：价目表（5.1.1.2）未交付，本仓没有 price_list 表。
--   规则评估时用到的「当前价」来自 5.5.2.1 的**价格观测台账**
--   （V222 `price_anomaly_observation`），与 5.3.1.2 批量测算同一口径（只读，不写）。
--
-- ============================================================ 第二节：触发条件（五类，各有专属阈值列）
--
-- 蓝图点名的五类触发条件阈值形状不同，所以用**专属列 + CHECK**而不是一个 JSON 列：
--
--   | trigger_type              | 专属列                       | 判定方向            | 数据来源（跨服务只读）        |
--   |---------------------------|------------------------------|---------------------|-------------------------------|
--   | INVENTORY_BELOW_THRESHOLD | threshold_quantity           | 可用 < 阈值         | 7 库存（warehouse 授权端点）  |
--   | COMPETITOR_PRICE_DROP     | threshold_rate               | 降幅 ≥ 阈值         | 5.5.2.1 价格观测台账（V222）   |
--   | COST_INCREASE             | threshold_rate               | 涨幅 ≥ 阈值         | 5.2.1.1 成本版本历史（V215）    |
--   | MARGIN_BELOW_FLOOR        | margin_floor_rate            | 毛利率 < 下限       | 5.3.1.1 利润测算（同服务）      |
--   | AGING_OVER_THRESHOLD      | aging_days_threshold         | 库龄 > 阈值         | 7.6.1.1 库龄呆滞（warehouse）  |
--
-- 🔴 **threshold_rate / margin_floor_rate 是小数口径**（0.1 = 10%），不是 10。
--
-- 🔴 **「竞品降价」的可测口径要写清楚**：本仓**没有竞品价采集模块**
--   （2.x 卷里没有采集竞品价的模块，5.3.2.1 同样把竞品价定成「人工登记」）。
--   本模块**不建竞品价表**来假装上游已交付；它用**平台在售价（platform_price）的观测下降幅度**
--   作为可测口径 —— 那是我们这条 listing 在平台上的在售价的下降，
--   **不是竞品价**。能力声明里会把这条口径与它的 ownerModule 写明，
--   竞品价模块落地后应改为经授权 API 供数。
--
-- ============================================================ 第三节：动作（四类）
--
--   | action_type      | action_rate  | 语义                                   | 是否改价 |
--   |------------------|--------------|----------------------------------------|----------|
--   | LOWER_PRICE      | 必填         | 降价（建议价 = 当前价 × (1 − 幅度)）      | 是（建议）|
--   | RAISE_PRICE      | 必填         | 涨价（建议价 = 当前价 × (1 + 幅度)）      | 是（建议）|
--   | PAUSE_PROMOTION  | 必须为空     | 暂停促销                               | 否        |
--   | DELIST           | 必须为空     | 下架                                   | 否        |
--
-- 🔴 **非价格动作同样要人工确认**：暂停促销与下架改的是 listing 状态，
--   规则写错一次就是一批商品被误伤 —— 与降价超阈值同属「批量改价造成损失」那一类。
--   本表用 `pricing_rule_suggestion.requires_confirmation` 表达这条纪律。
--
-- ============================================================ 第四节：优先级与生效时段
--
-- 🔴 **优先级数字小者优先**（与本仓既有约定一致：supplier_quote 的 1 = 首选，
--   exchange_rate_source 按 priority 升序取第一条）。
--   同一 SKU 上**先命中者优先**：只有优先级最高的那条命中规则产出建议调价，
--   其余命中记录标注「已被更高优先级的规则覆盖」。
--   ⇒ 优先级不是装饰，是「同一 SKU 上到底听谁的」的唯一答案。
--
-- 生效时段 = `effective_from`（含）~ `effective_to`（不含，空 = 长期有效）。
-- 评估时**只跑** enabled 且落在生效时段内的规则。
--
-- ============================================================ 第五节：高风险阈值（降价超过 X% 需人工确认）
--
-- `max_auto_price_drop_rate` = 这条规则允许**自动放行**的最大降价幅度（小数口径）。
-- 建议价的降价幅度超过它 ⇒ `requires_confirmation = true` ⇒ 必须人工确认。
--
-- 🔴 **自动放行还有另外两个前提**（否则不放行）：
--   1. 成本不完整（5.3.1.1 的 decisionReady = false）⇒ 复核不了毛利 ⇒ 人工确认；
--   2. 非价格动作（暂停促销 / 下架）⇒ 人工确认。
-- 这两条落在**服务层**（PricingRuleService），不是页面上的一句提示 ——
-- 下游 5.4.1.1 会程序化消费这份建议列表。
--
-- ============================================================ 第六节：表结构
--
-- 6.1 规则
CREATE TABLE pricing_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 规则编码：租户内唯一。运营按编码找规则（「美国站断货清仓」这种约定俗成的名字）。
  code varchar(64) NOT NULL,
  name varchar(200) NOT NULL,
  description text NOT NULL DEFAULT '',

  -- ===== 触发条件（第二节）=====
  trigger_type text NOT NULL CHECK(trigger_type IN (
    'INVENTORY_BELOW_THRESHOLD','COMPETITOR_PRICE_DROP','COST_INCREASE',
    'MARGIN_BELOW_FLOOR','AGING_OVER_THRESHOLD')),
  -- 库存阈值（件）：可售量低于它 ⇒ 命中。
  threshold_quantity numeric(19,6),
  -- 涨跌幅阈值（**小数口径**：0.1 = 10%）：竞品降价 / 成本上涨共用。
  threshold_rate numeric(9,6),
  -- 毛利下限（**小数口径**：0.2 = 20%）。
  margin_floor_rate numeric(9,6),
  -- 库龄阈值（天）。
  aging_days_threshold int,

  -- ===== 动作（第三节）=====
  action_type text NOT NULL CHECK(action_type IN (
    'LOWER_PRICE','RAISE_PRICE','PAUSE_PROMOTION','DELIST')),
  -- 调价幅度（**小数口径**：0.05 = 5%）。降价 / 涨价必填；其余动作必须为空。
  action_rate numeric(9,6),

  -- ===== 优先级与生效时段（第四节）=====
  -- 数字小者优先（第五节）。
  priority int NOT NULL DEFAULT 100,
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  -- 建议价币种（ISO 4217 三位大写码）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 高风险阈值（第五节）=====
  -- 自动放行的最大降价幅度（小数口径）。降价幅度超过它 ⇒ 需人工确认。
  max_auto_price_drop_rate numeric(9,6) NOT NULL DEFAULT 0
    CHECK(max_auto_price_drop_rate >= 0 AND max_auto_price_drop_rate < 1),

  enabled boolean NOT NULL DEFAULT true,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),

  CHECK(btrim(code) <> '' AND btrim(name) <> ''),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(effective_to IS NULL OR effective_to > effective_from),

  -- 触发条件的专属列互斥（第二节）。用 CASE 而不是四个 OR 分支：
  -- OR 会让「某个分支的其它列没被约束」变成可能。
  CHECK(CASE
    WHEN trigger_type = 'INVENTORY_BELOW_THRESHOLD'
      THEN threshold_quantity IS NOT NULL AND threshold_quantity >= 0
       AND threshold_rate IS NULL AND margin_floor_rate IS NULL AND aging_days_threshold IS NULL
    WHEN trigger_type IN ('COMPETITOR_PRICE_DROP','COST_INCREASE')
      THEN threshold_rate IS NOT NULL AND threshold_rate > 0 AND threshold_rate < 1
       AND threshold_quantity IS NULL AND margin_floor_rate IS NULL AND aging_days_threshold IS NULL
    WHEN trigger_type = 'MARGIN_BELOW_FLOOR'
      THEN margin_floor_rate IS NOT NULL AND margin_floor_rate >= 0 AND margin_floor_rate < 1
       AND threshold_quantity IS NULL AND threshold_rate IS NULL AND aging_days_threshold IS NULL
    WHEN trigger_type = 'AGING_OVER_THRESHOLD'
      THEN aging_days_threshold IS NOT NULL AND aging_days_threshold >= 0
       AND threshold_quantity IS NULL AND threshold_rate IS NULL AND margin_floor_rate IS NULL
    ELSE TRUE
  END),

  -- 动作的专属列互斥（第三节）。
  CHECK(CASE
    WHEN action_type IN ('LOWER_PRICE','RAISE_PRICE')
      THEN action_rate IS NOT NULL AND action_rate > 0 AND action_rate < 1
    WHEN action_type IN ('PAUSE_PROMOTION','DELIST')
      THEN action_rate IS NULL
    ELSE TRUE
  END)
);

-- 规则台账：按最近改过的排序（默认列表）。
CREATE INDEX pricing_rule_by_updated ON pricing_rule(tenant_id, updated_at DESC, id);
-- 「当前生效的规则」（评估的主查询路径：enabled + 时段 + 优先级）。
CREATE INDEX pricing_rule_by_active ON pricing_rule(tenant_id, enabled, priority, effective_from);
-- 「这种触发条件的规则」。
CREATE INDEX pricing_rule_by_trigger ON pricing_rule(tenant_id, trigger_type, enabled);

-- 6.2 作用范围
CREATE TABLE pricing_rule_scope (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 所属规则。**不建外键**（第七节），引用完整性由触发器 guard 保证。
  rule_id uuid NOT NULL,
  -- 作用范围的类型：分类 / 品牌 / 店铺 / SKU。
  scope_type text NOT NULL CHECK(scope_type IN ('CATEGORY','BRAND','SHOP','SKU')),
  -- 取值：分类码 / 品牌码 / 站点码 / SKU 编码。
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

-- 同一规则下同一对象不重复登记。
-- 🔴 用**唯一索引**而不是 UNIQUE 约束：PG 的表级 UNIQUE 约束只接受列名，
-- 不接受表达式（COALESCE(platform_code,'') 这种「可空列参与唯一性」必须写成索引）。
CREATE UNIQUE INDEX pricing_rule_scope_unique
  ON pricing_rule_scope(tenant_id, rule_id, scope_type, scope_ref, COALESCE(platform_code, ''));

-- 按作用范围反查规则。
CREATE INDEX pricing_rule_scope_by_ref
  ON pricing_rule_scope(tenant_id, scope_type, scope_ref, rule_id);
CREATE INDEX pricing_rule_scope_by_rule
  ON pricing_rule_scope(tenant_id, rule_id, scope_type);

-- 6.3 命中记录（只追加）
CREATE TABLE pricing_rule_hit (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 命中的规则。
  rule_id uuid NOT NULL,
  -- 被判定的 SKU。
  sku_id uuid NOT NULL,
  -- 所属 SPU（规则的分类 / 品牌范围都挂在 SPU 上，留一份便于复盘）。
  spu_id uuid NOT NULL,
  -- 判定时的触发条件类型（规则可能被改过，留当时的快照）。
  trigger_type text NOT NULL CHECK(trigger_type IN (
    'INVENTORY_BELOW_THRESHOLD','COMPETITOR_PRICE_DROP','COST_INCREASE',
    'MARGIN_BELOW_FLOOR','AGING_OVER_THRESHOLD')),
  -- 命中了吗。false = 「判定过，但没命中」（连同 reason 一起留痕）——
  -- 运营要回答「为什么这条规则没动我的商品」，靠的就是这一列。
  matched boolean NOT NULL,
  -- 判定依据（JSONB）：当时的观测值、阈值、来源模块。
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 人话理由：命中/未命中的原因。页面上逐条显示。
  reason text NOT NULL DEFAULT '',
  -- 被更高优先级的规则覆盖时为 true（这条命中没有产出建议调价）。
  superseded boolean NOT NULL DEFAULT false,
  -- 被哪条规则覆盖（superseded_by_rule_id）。
  superseded_by_rule_id uuid,
  evaluated_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid NOT NULL,

  UNIQUE(tenant_id, id),

  -- 覆盖关系自洽：被覆盖 ⇒ 必须点名覆盖者；没被覆盖 ⇒ 不能点名。
  CHECK(CASE WHEN superseded THEN superseded_by_rule_id IS NOT NULL
              ELSE superseded_by_rule_id IS NULL END)
);

-- 「这条规则判过哪些 SKU」—— 台账与复盘的主查询路径。
CREATE INDEX pricing_rule_hit_by_rule ON pricing_rule_hit(tenant_id, rule_id, evaluated_at DESC);
-- 「这个 SKU 被哪些规则判过」。
CREATE INDEX pricing_rule_hit_by_sku ON pricing_rule_hit(tenant_id, sku_id, evaluated_at DESC);
-- 「命中记录清单」按时间倒序。
CREATE INDEX pricing_rule_hit_by_time ON pricing_rule_hit(tenant_id, evaluated_at DESC, id);

-- 6.4 建议调价（只追加 + 状态推进）
CREATE TABLE pricing_rule_suggestion (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 产出这条建议的规则。
  rule_id uuid NOT NULL,
  -- 对应的命中记录。
  hit_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  spu_id uuid NOT NULL,
  -- 目标店铺 / 平台（建议价的作用面）。
  site_code varchar(32) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 动作（规则可能被改过，留当时的快照）。
  action_type text NOT NULL CHECK(action_type IN (
    'LOWER_PRICE','RAISE_PRICE','PAUSE_PROMOTION','DELIST')),
  -- 当前价（来自 5.5.2.1 价格观测台账；非价格动作也有，因为要留「按哪个价判的」）。
  current_price numeric(19,4) CHECK(current_price IS NULL OR current_price > 0),
  -- 🔴 **建议价**：不是生效价 —— 改价是 5.4.1.1 调价单的写入所有权。
  --    非价格动作（暂停促销 / 下架）没有建议价，为 null。
  suggested_price numeric(19,4) CHECK(suggested_price IS NULL OR suggested_price > 0),
  -- 相对当前价的变动幅度（**小数口径**：0.05 = 降 5%）。非价格动作为 null。
  price_change_rate numeric(9,6),

  -- 🔴 需不需要人工确认（第五节）。
  requires_confirmation boolean NOT NULL,
  -- 状态机（第一节）：PENDING_CONFIRMATION → CONFIRMED / REJECTED；低风险直接 READY。
  status text NOT NULL CHECK(status IN (
    'PENDING_CONFIRMATION','READY','CONFIRMED','REJECTED')),

  -- 决策就绪度（来自 5.3.1.1 对**建议价**的复核）：false ⇒ 下面的毛利率**必为 null**。
  decision_ready boolean NOT NULL,
  -- 🔴 毛利率**可以为负**（建议价跌破成本线 ⇒ 负毛利）：下限是 −100% 而不是 0 ——
  --   一条「负毛利」的建议恰恰是最需要人看见的那一条，把它挡在库外等于把警报删了。
  gross_margin_rate numeric(9,6)
    CHECK(gross_margin_rate IS NULL OR (gross_margin_rate > -1 AND gross_margin_rate < 1)),
  gross_profit numeric(19,4),

  -- 人话理由：为什么命中、为什么建议成这个价。
  reason text NOT NULL DEFAULT '',
  -- 人工确认 / 驳回的留痕。
  decided_by uuid,
  decided_at timestamptz,
  decision_note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 🔴 不可信就不给毛利率数字（不是 0、不是估算值）。这条是 5.3.1.1 决策就绪闸的延续。
  CHECK(decision_ready OR gross_margin_rate IS NULL),
  CHECK(decision_ready OR gross_profit IS NULL),

  -- 价格动作 ⇒ 必须有当前价与建议价；非价格动作 ⇒ 没有建议价。
  CHECK(CASE
    WHEN action_type IN ('LOWER_PRICE','RAISE_PRICE')
      THEN current_price IS NOT NULL AND suggested_price IS NOT NULL
    WHEN action_type IN ('PAUSE_PROMOTION','DELIST')
      THEN suggested_price IS NULL
    ELSE TRUE
  END),

  -- 状态机自洽：已决策 ⇒ 必须有人与时间；未决策 ⇒ 不能有。
  CHECK(CASE
    WHEN status IN ('CONFIRMED','REJECTED') THEN decided_by IS NOT NULL AND decided_at IS NOT NULL
    ELSE decided_by IS NULL AND decided_at IS NULL
  END)
);

-- 「这个 SKU 上有哪些建议调价」。
CREATE INDEX pricing_rule_suggestion_by_sku ON pricing_rule_suggestion(tenant_id, sku_id, created_at DESC);
-- 「这条规则产出了哪些建议」。
CREATE INDEX pricing_rule_suggestion_by_rule ON pricing_rule_suggestion(tenant_id, rule_id, created_at DESC);
-- 「待人工确认的建议」清单。
CREATE INDEX pricing_rule_suggestion_by_status
  ON pricing_rule_suggestion(tenant_id, status, created_at DESC);

-- ============================================================ 第七节：引用校验用触发器，不用外键
--
-- 🔴 上一批（batch-37）踩过的坑：给**既有表**加 FOREIGN KEY 会打破同服务既有测试的
-- TRUNCATE ⇒ 整批用例 Errors ⇒ L2 直接红。本迁移因此**零个外键**：
-- 作用范围 → 规则、命中记录 → 规则 / SKU、建议调价 → 规则 / 命中记录 / SKU，
-- 全部用触发器校验（与 V231 的 guard_pricing_template_* 同一手法）：
-- 触发器的判据与外键等价（引用不存在 ⇒ 报 23514），但**不参与 TRUNCATE 的依赖图**。
--
-- (a) 作用范围：必须挂在真实存在的规则上。
CREATE FUNCTION guard_pricing_rule_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pricing_rule r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.rule_id) THEN
    RAISE EXCEPTION 'Pricing rule scope % must reference an existing rule',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pricing_rule_scope_guard
  BEFORE INSERT OR UPDATE ON pricing_rule_scope
  FOR EACH ROW EXECUTE FUNCTION guard_pricing_rule_scope();

-- (b) 命中记录：必须挂在真实存在的规则与同租户真实存在的 SKU 上。
CREATE FUNCTION guard_pricing_rule_hit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pricing_rule r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.rule_id) THEN
    RAISE EXCEPTION 'Pricing rule hit % must reference an existing rule',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.sku_id) THEN
    RAISE EXCEPTION 'Pricing rule hit % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.superseded_by_rule_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM pricing_rule r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.superseded_by_rule_id) THEN
    RAISE EXCEPTION 'Pricing rule hit % must reference an existing superseding rule',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pricing_rule_hit_guard
  BEFORE INSERT OR UPDATE ON pricing_rule_hit
  FOR EACH ROW EXECUTE FUNCTION guard_pricing_rule_hit();

-- (c) 建议调价：必须挂在真实存在的规则、命中记录与 SKU 上。
CREATE FUNCTION guard_pricing_rule_suggestion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pricing_rule r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.rule_id) THEN
    RAISE EXCEPTION 'Pricing rule suggestion % must reference an existing rule',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pricing_rule_hit h
     WHERE h.tenant_id = NEW.tenant_id AND h.id = NEW.hit_id) THEN
    RAISE EXCEPTION 'Pricing rule suggestion % must reference an existing hit',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.sku_id) THEN
    RAISE EXCEPTION 'Pricing rule suggestion % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pricing_rule_suggestion_guard
  BEFORE INSERT OR UPDATE ON pricing_rule_suggestion
  FOR EACH ROW EXECUTE FUNCTION guard_pricing_rule_suggestion();

-- ============================================================ 第八节：RLS 与授权
--
-- 四张表都是租户级业务表（定价规则是租户自己的经营参数）：
-- `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
-- + isolation 策略（照抄 V231 第九节的 `DO $$ FOREACH` 写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的规则，
-- 而规则里写着这个租户的降价幅度与毛利下限 —— 那是一条看不见的越权面。
--
-- 可见性：规则与作用范围是**租户级参数**（不是某个 SKU 的从属事实），
-- 所以只有 isolation 策略；命中记录与建议调价跟着 SKU 走（看不到 SKU 就看不到它们的记录）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'pricing_rule',
    'pricing_rule_scope',
    'pricing_rule_hit',
    'pricing_rule_suggestion'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 命中记录与建议调价的可见性跟随 SKU（与 V231 的 data_scope 同一纪律）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['pricing_rule_hit','pricing_rule_suggestion'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_rule TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_rule TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_rule_scope TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_rule_scope TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_rule_hit TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_rule_hit TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_rule_suggestion TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON pricing_rule_suggestion TO starcon_app;
