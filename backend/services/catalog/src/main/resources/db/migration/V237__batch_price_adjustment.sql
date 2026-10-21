-- 5.4.1.2 批量调价：对选中的一批 listing 统一改价（固定金额 / 百分比 / 按模板），
-- **必须先看预览再执行**，预览要给出影响范围（涉及多少店铺、多少商品、预计利润变化、
-- 改完后预计亏损的商品数）。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：对选中的一批 listing 统一改价（固定金额/百分比/按模板），支持预览影响范围
--             （涉及多少店铺、多少商品、预计利润变化）。
--   **输入**：目标集合；调价参数；预览确认。
--   **输出**：批量调价单 + 影响预览报告。
--   **关系**：上游 = 5.3.1.2、4.1.1.1；下游 = 5.4.2.1。
--             **必须先看预览再执行**，预览要包含"改完后预计亏损的商品数"这类反向指标。
--
-- ===========================================================================
-- 第一节：本模块与相邻三个已交付模块的分工（先把边界钉死，再建表）
-- ===========================================================================
--
-- 本模块**不重写**任何一套已有算术，三个上游各司其职：
--
--   * **5.3.1.2 批量测算**（V230，本服务内）：提供「一批 SKU 在某个店铺下的利润表」的口径。
--     本模块**只读**它落下的结果口径（售价来源 / 决策就绪闸 / 亏损与低毛利标记），
--     **不改**它的任何表、任何端点。
--   * **5.3.1.1 利润测算器**（V227，本服务内）：**改价前后���利润额与毛利率**全部由它的引擎
--     （ProfitCalculatorService.calculate）算出来 —— 本模块**零个测算算法**。
--   * **5.3.2.1 定价模板**（V231，本服务内）：「按模板」方式的建议价由它的
--     PricingTemplateService.apply() 产出 —— 本模块**不自己实现模板怎么算**。
--
-- 🔴 **上游 = 4.1.1.1（listing）的落地口径**：本仓的 listing 事实属 channel 服务
--   （channel 的 listing 表 V171 在表注释里就写着「无价格列：售价归 05 定价」）。
--   catalog **没有** listing 的本地投影，而 AGENTS.md 禁止跨服务直接查对方的表。
--   ⇒ 本模块**不新建任何 listing 影子表**去假装上游已交付。
--   本仓在 05 定价卷里唯一真实存在的「listing 级价格事实」是
--   **5.5.2.1 异常告警的价格观测台账**（V222 {@code price_anomaly_observation}）：
--   一行 = 一个 SKU 在一个站点（平台 + 站点）上的一次价格观测。
--   ⇒ 本模块把「一个 listing」定义为 **(SKU × 店铺)**，它的当前价取自那张台账的**最新一条**观测
--   （与 5.3.1.2 逐字相同的口径：优先我方价 internal_price，退到平台在售价 platform_price，
--   两者都没有 ⇒ 整行跳过，**绝不用 0 顶**）。
--
-- ===========================================================================
-- 第二节：🔴 本模块**不改**任何既有表（一条 ALTER 都没有）
-- ===========================================================================
--
-- 🔴 上一批（batch-37）踩过的坑：某条车道在迁移里给**既有表**加了 FOREIGN KEY，
--   结果打破了同服务既有测试的 TRUNCATE ⇒ 39 例 Errors ⇒ L2 直接红。
--   本迁移因此：
--   * **零个 FOREIGN KEY**（引用校验一律用触发器，与 V230 / V231 同一手法）；
--   * **零个 ALTER / UPDATE 既有表**（连索引都不加到别人的表上）；
--   * **不写** 5.5.2.1 的价格观测台账（那张表有唯一的写入所有者：5.5.2.1 的
--     PriceAnomalyService —— 每张事实表只有一个写入所有者）。
--     本模块只**读**它取当前价；「改完之后的新价」记在本模块自己的调价单里
--     （蓝图 5.4.1.1 对调价单的要求就是「记录变更前后值」）。
--
-- ===========================================================================
-- 第三节：两张表，职责不重叠
-- ===========================================================================
--
--   | 表                          | 一行是什么                                   | 写入 |
--   |-----------------------------|----------------------------------------------|------|
--   | batch_price_adjustment      | 一次批量调价（参数 + 目标集合 + 预览报告快照）| 追加 |
--   | batch_price_adjustment_item | 目标集合里的一个 listing（改价前后逐项对比）  | 追加 |
--
-- 🔴 **「影响预览报告」是蓝图的输出之一**（「输出：批量调价单 + 影响预览报告」），
--   所以它**必须落库**：预览本身**无副作用**（不落库），确认执行时才把报告与调价单一起写入。
--   预览报告里的汇总数字与明细行**在同一次写入里产生**，所以两者不可能漂移
--   （明细行不可变：没有 UPDATE 端点，也没有 UPDATE 授权）。
--
-- ===========================================================================
-- 第四节：调价方式三选一（DB CHECK 钉死互斥）
-- ===========================================================================
--
--   | adjust_mode   | 语义                                          | 必填列            |
--   |---------------|-----------------------------------------------|-------------------|
--   | FIXED_AMOUNT  | 每个 listing 加/减**同一个固定金额**（可负=降价）| adjust_amount     |
--   | PERCENT       | 每个 listing 乘 (1 + rate)（rate 小数口径）    | adjust_rate       |
--   | TEMPLATE      | 用 5.3.2.1 的定价模板算建议价当新价            | template_id       |
--
-- 🔴 **adjust_rate 是小数口径**（0.1 = +10%，不是 10）：写成 10 会被读成 +1000%。
--   adjust_amount **可负**（负数 = 降价）—— 「固定金额」的调价方向由正负号表达。
--
-- ===========================================================================
-- 第五节表：batch_price_adjustment —— 一次批量调价（单头）
-- ===========================================================================
CREATE TABLE batch_price_adjustment (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- ===== 调价方式（第四节）=====
  adjust_mode text NOT NULL
    CHECK(adjust_mode IN ('FIXED_AMOUNT','PERCENT','TEMPLATE')),
  -- FIXED_AMOUNT：每个 listing 加/减同一个固定金额（可负 = 降价）。
  adjust_amount numeric(19,4),
  -- PERCENT：小数口径的涨跌幅（0.1 = +10%）。
  adjust_rate numeric(9,6),
  -- TEMPLATE：定价模板 id（5.3.2.1）。**不建外键**（第二节）：触发器校验。
  template_id uuid,
  -- 模板的**快照**：模板后来被改了，这张单子也要能看出它当时用的是哪一条模板。
  template_code varchar(64) NOT NULL DEFAULT '',
  template_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 币种与汇率（第三节：跨币种绝不相加）=====
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  cost_currency varchar(3) NOT NULL CHECK(cost_currency ~ '^[A-Z]{3}$'),
  -- 汇率：1 cost_currency = fx_rate currency。跨币种时必填。
  fx_rate numeric(19,8) CHECK(fx_rate IS NULL OR fx_rate > 0),

  -- ===== 目标集合（第五节）=====
  -- 分类 / 品牌：spu 上的**文本**列，**精确匹配**（与 5.3.1.2 同一口径）。
  -- 空串 = 不限定。
  scope_category varchar(100) NOT NULL DEFAULT '',
  scope_brand    varchar(100) NOT NULL DEFAULT '',
  -- 显式选中的 SKU（uuid 逗号分隔）。空 = 不限定（此时靠分类/品牌或全量）。
  scope_sku_ids text NOT NULL DEFAULT '',
  -- 目标店铺：编码成 "平台码:站点码"，逗号分隔。**顺序稳定**（按平台码、站点码升序），
  -- 这样同一批目标两次预览算出来的预览令牌一致。
  scope_shops text NOT NULL DEFAULT '',

  -- ===== 利润口径（改价前后的利润由 5.3.1.1 的引擎算，这些是它的输入）=====
  quantity numeric(19,6) NOT NULL DEFAULT 1 CHECK(quantity > 0),
  platform_fee_rate numeric(9,6)
    CHECK(platform_fee_rate IS NULL OR (platform_fee_rate >= 0 AND platform_fee_rate < 1)),
  platform_fee_fixed numeric(19,4)
    CHECK(platform_fee_fixed IS NULL OR platform_fee_fixed >= 0),
  logistics_cost_per_unit numeric(19,4)
    CHECK(logistics_cost_per_unit IS NULL OR logistics_cost_per_unit >= 0),
  monthly_fixed_cost numeric(19,4)
    CHECK(monthly_fixed_cost IS NULL OR monthly_fixed_cost >= 0),
  cost_window_from date,
  cost_window_to date,

  -- ===== 影响预览报告（蓝图的输出之一，第五节）=====
  -- 涉及多少店铺（目标集合里的店铺数）。
  shop_count int NOT NULL CHECK(shop_count >= 0),
  -- 涉及多少商品（目标集合里的**去重 SKU 数**）。
  sku_count int NOT NULL CHECK(sku_count >= 0),
  -- 目标 listing 数（SKU × 店铺）。
  listing_count int NOT NULL CHECK(listing_count >= 0),
  -- 其中会被改价的 / 被跳过的。
  will_adjust_count int NOT NULL CHECK(will_adjust_count >= 0),
  skipped_count int NOT NULL CHECK(skipped_count >= 0),
  -- 🔴 **反向指标**：改价前 / 改价后的亏损 listing 数（蓝图的加粗句要求）。
  -- 「改完后预计亏损的商品数」= 改完后至少有一个 listing 亏损的**去重 SKU 数**。
  loss_before_count int NOT NULL CHECK(loss_before_count >= 0),
  loss_after_count  int NOT NULL CHECK(loss_after_count >= 0),
  loss_after_sku_count int NOT NULL CHECK(loss_after_sku_count >= 0),
  -- 预计利润变化（改价后合计 − 改价前合计）。
  profit_before_total numeric(19,4),
  profit_after_total  numeric(19,4),
  profit_delta_total  numeric(19,4),
  avg_margin_before numeric(9,6),
  avg_margin_after  numeric(9,6),

  -- 预览令牌：预览内容的内容哈希。执行时服务端**重算**一次并逐字比对 ——
  -- 对不上就是「预览之后目标集合或价格变了」，此时**拒绝执行**而不是照着过期的数字改价。
  preview_token varchar(64) NOT NULL CHECK(btrim(preview_token) <> ''),

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 三种方式三选一（第四节）：两种都填时「这批到底怎么调」没有答案。
  CHECK(
    (adjust_mode = 'FIXED_AMOUNT' AND adjust_amount IS NOT NULL
                       AND adjust_rate IS NULL AND template_id IS NULL)
    OR (adjust_mode = 'PERCENT' AND adjust_rate IS NOT NULL
                       AND adjust_amount IS NULL AND template_id IS NULL)
    OR (adjust_mode = 'TEMPLATE' AND template_id IS NOT NULL
                       AND adjust_amount IS NULL AND adjust_rate IS NULL)
  ),
  -- 跨币种必须要汇率：没有汇率时**拒绝**而不是默默按 1:1 折算。
  CHECK(currency = cost_currency OR fx_rate IS NOT NULL),
  -- 成本窗口必须自洽。
  CHECK(cost_window_from IS NULL OR cost_window_to IS NULL OR cost_window_to >= cost_window_from)
);

-- 调价单台账：默认排序是「最近执行的」。
CREATE INDEX batch_price_adjustment_by_created
  ON batch_price_adjustment(tenant_id, created_at DESC, id);
-- 「这个店铺的调价单」—— 店铺维度的复盘。
CREATE INDEX batch_price_adjustment_by_shops
  ON batch_price_adjustment(tenant_id, scope_shops, created_at DESC);
-- 「按方式筛」—— 台账页的筛选维度。
CREATE INDEX batch_price_adjustment_by_mode
  ON batch_price_adjustment(tenant_id, adjust_mode, created_at DESC);

-- ===========================================================================
-- 第六节表：batch_price_adjustment_item —— 目标集合里的一个 listing
-- ===========================================================================
--
-- 一行 = 一个 (SKU × 店铺)。**被跳过的行也在表里**（status='SKIPPED'）：
-- 「这个范围里有 40 个 listing 没有价格观测」是运营必须看见的事实，
-- 把它从表里删掉，调价数量与范围命中数就会对不上，而且看不出差在哪。
CREATE TABLE batch_price_adjustment_item (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 所属批量调价单。**不建外键**（第二节）：触发器校验。
  adjustment_id uuid NOT NULL,

  -- 被调价的 listing = 一个 SKU 在一个店铺。
  sku_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 这一行改没改 =====
  -- WILL_ADJUST 会被改价（下面的新旧价与利润都有值）
  -- SKIPPED      被跳过（skip_reason 写明为什么，不是空串）
  status text NOT NULL CHECK(status IN ('WILL_ADJUST','SKIPPED')),
  skip_reason text NOT NULL DEFAULT '',

  -- ===== 旧价（当前价）与新价 =====
  -- 当前价：取自 5.5.2.1 的价格观测台账（最新一条观测）。可空 = 没取到（那这行就是 SKIPPED）。
  old_price numeric(19,4) CHECK(old_price IS NULL OR old_price >= 0),
  -- 新价。可空 = 没算出来（那这行就是 SKIPPED）。
  new_price numeric(19,4) CHECK(new_price IS NULL OR new_price > 0),
  -- 价差（新价 − 旧价）。正 = 涨价，负 = 降价。
  price_delta numeric(19,4),
  -- 当前价取自哪一种：INTERNAL_PRICE（我方价）/ PLATFORM_PRICE（平台在售价）。
  price_source text
    CHECK(price_source IS NULL OR price_source IN ('INTERNAL_PRICE','PLATFORM_PRICE')),
  -- 当前价是**哪一刻**观测到的（台账只追加，「最新一条」是一个时间点而不是「当前价」）。
  price_observed_at timestamptz,

  -- ===== 改价前后的利润（全部由 5.3.1.1 的引擎算，本模块零算法）=====
  -- 🔴 **成本不完整时这些是 null，不是 0**：null = 「没测准」，0 = 「毛利刚好 0」。
  gross_profit_before numeric(19,4),
  gross_margin_before numeric(9,6),
  gross_profit_after  numeric(19,4),
  gross_margin_after  numeric(9,6),
  -- 决策就绪度（保存时的快照）。false 时上面的利润与毛利率必为 null。
  decision_ready boolean NOT NULL,
  -- 🔴 亏损标记（毛利额 < 0）。成本不完整时为 **null**（不可信），不是 false。
  loss_before boolean,
  loss_after  boolean,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一次调价里同一个 listing 只能有一行。
  UNIQUE(tenant_id, adjustment_id, sku_id, platform_code, site_code),
  -- 状态与金额必须自洽：会改的行必须有旧价与新价；跳过的行必须写明原因。
  CHECK(
    (status = 'WILL_ADJUST' AND old_price IS NOT NULL AND new_price IS NOT NULL
                AND price_delta IS NOT NULL AND skip_reason = '')
    OR (status = 'SKIPPED' AND btrim(skip_reason) <> '')
  ),
  -- 没测准的行不许带利润数字（避免把「没测准」渲染成 0）。
  CHECK(decision_ready OR (gross_profit_before IS NULL AND gross_margin_before IS NULL
                           AND gross_profit_after IS NULL AND gross_margin_after IS NULL)),
  -- 亏损标记必须有毛利额支撑。
  CHECK(loss_before IS NULL OR (decision_ready AND gross_profit_before IS NOT NULL)),
  CHECK(loss_after IS NULL OR (decision_ready AND gross_profit_after IS NOT NULL)),
  -- 平台码 / 站点码不为空，形状与 V222 的同一套约定一致。
  CHECK(btrim(platform_code) <> '' AND btrim(site_code) <> ''),
  CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(site_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$')
);

-- 调价单明细：按单取明细（默认按旧价升序）。
CREATE INDEX batch_price_adjustment_item_by_adjustment
  ON batch_price_adjustment_item(tenant_id, adjustment_id, sku_id);
-- 「改完之后哪些会亏」—— 反向指标在明细上的真筛选。
CREATE INDEX batch_price_adjustment_item_by_loss
  ON batch_price_adjustment_item(tenant_id, adjustment_id, loss_after);
-- 「这一行会涨多少 / 跌多少」。
CREATE INDEX batch_price_adjustment_item_by_delta
  ON batch_price_adjustment_item(tenant_id, adjustment_id, price_delta);

-- ===========================================================================
-- 第七节：引用校验用触发器，不用外键（第二节）
-- ===========================================================================
--
-- 触发器的判据与外键等价（引用不存在 ⇒ 报 23514），但**不参与 TRUNCATE 的依赖图**。
-- 同批 V230 就是这么做的，它没打断任何既有测试。

-- 明细必须挂在本租户真实存在的调价单与 SKU 上。
CREATE FUNCTION guard_batch_price_adjustment_item() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM batch_price_adjustment a
     WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.adjustment_id) THEN
    RAISE EXCEPTION 'Batch price adjustment item % must reference an existing adjustment',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION 'Batch price adjustment item % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER batch_price_adjustment_item_guard
  BEFORE INSERT OR UPDATE ON batch_price_adjustment_item
  FOR EACH ROW EXECUTE FUNCTION guard_batch_price_adjustment_item();

-- 单头必须挂在本租户真实存在的模板上（只在按模板调价时校验）。
CREATE FUNCTION guard_batch_price_adjustment_template() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.adjust_mode = 'TEMPLATE' AND NEW.template_id IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1 FROM pricing_template t
       WHERE t.tenant_id=NEW.tenant_id AND t.id=NEW.template_id) THEN
      RAISE EXCEPTION 'Batch price adjustment % must reference an existing pricing template',
        NEW.id USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER batch_price_adjustment_template_guard
  BEFORE INSERT OR UPDATE ON batch_price_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_batch_price_adjustment_template();

-- ===========================================================================
-- 第八节：RLS 与授权
-- ===========================================================================
--
-- 两张表都是租户级业务表（批量调价是租户自己的定价动作）。
-- 照抄 V230 第八节的 DO $$ FOREACH 写法：两张表的 isolation 策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的调价单 —— 那是一条看不见的越权面。
--
-- 🔴 单头表**只有** isolation 策略，**没有** data_scope 策略：
--   data_scope 的 WITH CHECK 会在 INSERT 时也生效，而单头是在明细行写入**之前**建的，
--   一条「单头里至少有一个可见 SKU」的策略会让单头根本插不进去。
--   单头表里存的是参数与**汇总数字**（不含任何逐 listing 的明细金额），汇总数字一律
--   随明细行读时可见范围变化 —— 所以单头表本身没有可泄漏的明细。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['batch_price_adjustment','batch_price_adjustment_item'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 明细行可见性跟随 SKU：看不到 SKU 就看不到它的调价明细
-- （明细行里有这个 SKU 的售价与改价前后利润）。
-- 这条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['batch_price_adjustment_item'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
--
-- 🔴 **只授 SELECT / INSERT，不授 UPDATE / DELETE**：批量调价单与它的明细是**不可变事实**
--   （蓝图 5.4.1.1 对调价单的要求就是「记录变更前后值」）。改价要改，就**再发一张单**，
--   不能就地改写已经执行过的调价记录 —— 否则「这次到底改成了多少」事后无从追溯。
GRANT SELECT,INSERT ON batch_price_adjustment TO catalog_app;
GRANT SELECT,INSERT ON batch_price_adjustment TO starcon_app;
GRANT SELECT,INSERT ON batch_price_adjustment_item TO catalog_app;
GRANT SELECT,INSERT ON batch_price_adjustment_item TO starcon_app;
