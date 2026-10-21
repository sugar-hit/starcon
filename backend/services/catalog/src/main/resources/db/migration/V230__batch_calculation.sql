-- 5.3.1.2 批量测算：对一批 SKU 批量跑利润测算，输出利润表 + 亏损品/低毛利品标记，支持筛选与导出。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：对一批 SKU 批量测算，按当前成本与规则输出利润表，标记亏损品与低毛利品，
--             支持导出并筛选（毛利率 < X%、售价 < Y）。
--   **输入**：SKU 范围（按分类/品牌/店铺）；测算参数。
--   **输出**：批量测算结果表 + 异常标记。
--   **关系**：上游 = 5.3.1.1；下游 = 5.4.1.2。
--             **批量测算是选品与清库存决策的依据**：亏损品要么涨价要么清仓，人工逐个测算不可行。
--
-- ============================================================ 第一节：本模块与 5.3.1.1 的分工
--
-- 5.3.1.1 利润测算器（V227）交付的是「**一次**测算 + 可复算的方案」：
--   它的利润拆解引擎（ProfitCalculatorRules）、瀑布图、决策就绪闸都在那里。
--
-- 本模块**不重写**那套算术，也**不新建**第二套扣减结构：
--   批量测算 = 把 5.3.1.1 的 {@code ProfitCalculatorService.calculate} 跑 N 次，
--   每次换一个 SKU 与它的售价，把结果**落成一张可筛选、可导出的表**。
--
-- ⇒ **本迁移零个测算算法**：所有金额都由服务层复用 5.3.1.1 的引擎算出来，
-- 本迁移只负责「范围 → 行 → 标记 → 筛选 → 导出」这条数据链。
--
-- ============================================================ 第二节：售价从哪里来（本模块最贵的一个坑）
--
-- 蓝图输入句只写了「SKU 范围（按分类/品牌/店铺）；测算参数」，没写售价从哪来。
-- 5.3.1.1 的售价是**人填的**（一次一个 SKU），批量测算不能让人填 200 个 ——
-- 必须有一个**按 SKU 取价**的真实来源。
--
-- 本仓当前的真实售价事实有且只有一处：
--
--   **5.5.2.1 异常告警的价格观测台账**（V222 {@code price_anomaly_observation}）：
--     sku_id + platform_code + site_code + currency + internal_price + platform_price
--     + observed_at。它是**只追加**的价格观测台账，同一 SKU 同一站点同一时刻只允许一条。
--
-- 🔴 **5.1 价目表未交付** ⇒ 本仓**没有**「我方价目表」这个事实来源。
-- 所以这里的售价口径是：
--   * 优先取该 SKU 在该店铺**最新一条**观测的 {@code internal_price}（我方价）；
--   * 没有 internal_price 时退到 {@code platform_price}（平台在售价），并**标出是哪一种**；
--   * 两者都没有 ⇒ 该 SKU **跳过**（status='SKIPPED_NO_PRICE'），
--     **绝不用 0 或任何估算值代替** —— 0 会被算成「亏损品」，那是凭空造出来的决策依据。
--
-- 观测台账是**只追加**的，所以「最新一条」是按 observed_at 取的，不是「当前价」。
-- 这一点写在结果的 price_observed_at 列里，导出时也带出去 ——
-- 拿三个月前的价去清库存，运营必须看得见这一点。
--
-- ============================================================ 第三节：跨币种绝不相加（沿用 V227 的纪律）
--
-- 批量测算的币种由**店铺**决定（站点自身币种），成本币种由成本构成决定。
-- 两者不同时**必须**给汇率（方向显式：1 cost_currency = fx_rate currency），
-- 否则服务层拒绝测算（不是折算，是拒绝）。跨币种的行**整行跳过**，
-- 不做「按 1:1 折算」那种看起来对、实际错的加法。
--
-- ============================================================ 第四节：表结构（两张表，职责不重叠）
--
--   | 表                          | 一行是什么                              | 写入 |
--   |-----------------------------|-----------------------------------------|------|
--   | batch_calculation_run      | 一次批量测算（范围 + 参数 + 口径）        | 追加 |
--   | batch_calculation_result   | 范围内一个 SKU 的测算结果 + 异常标记      | 追加 |
--
-- 🔴 **汇总数字不落库**：亏损品数、低毛利品数、平均毛利率等全部**读时聚合**。
-- 落库就会漂移 —— 结果行可以被后续的读时筛选影响，而汇总数字不会跟着变，
-- 于是「列表里 3 个亏损品，汇总写着 5 个」这种矛盾会长期存在。
-- 读时聚合还有一个好处：**汇总天然受当前租户的数据可见范围约束**。
--
-- ============================================================ 第五节表：batch_calculation_run —— 一次批量测算
--
-- 范围按「分类 / 品牌 / 店铺」三个维度。分类与品牌取 spu 表上的**文本**列
-- （spu.category / spu.brand —— 本仓的分类树 V14 与 spu 上的自由文本不是一回事，
-- 本模块只按 spu 上的实际文本精确匹配，不替分类树做归并）。
-- 店铺必填：没有店铺就没有售价（第二节）。
CREATE TABLE batch_calculation_run (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- ===== 店铺范围（平台 + 站点）=====
  -- 平台码与站点码沿用 V222 price_anomaly_observation 的同一套约定：
  -- **字符串码 + 不建码表副本**（平台目录是 channel 服务的全局码表，catalog 不复制它）。
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,

  -- ===== 币种（第三节）=====
  -- 售价币种：店铺的计价币种。成本币种：成本构成的币种。
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  cost_currency varchar(3) NOT NULL CHECK(cost_currency ~ '^[A-Z]{3}$'),

  -- ===== SKU 范围（分类 / 品牌）=====
  -- 空串 = 不限定。**精确匹配**（不是 ilike）：分类名里的空格与大小写在
  -- 不同来源的导入里经常不一致，模糊匹配会静默多算一批 SKU。
  scope_category varchar(100) NOT NULL DEFAULT '',
  scope_brand    varchar(100) NOT NULL DEFAULT '',

  -- ===== 售价来源 =====
  -- 唯一取值是 LATEST_OBSERVATION（第二节）。写成 CHECK 而不是自由文本，
  -- 是为了让「将来接上 5.1 价目表」这件事必须**改这张表的定义**，
  -- 而不是往里塞一个没人认识的值。
  price_source text NOT NULL DEFAULT 'LATEST_OBSERVATION'
    CHECK(price_source IN ('LATEST_OBSERVATION')),

  -- ===== 测算参数（与 5.3.1.1 同构，语义逐字相同）=====
  -- 数量：用于阶梯与运费。
  quantity numeric(19,6) NOT NULL DEFAULT 1 CHECK(quantity > 0),

  -- 汇率：1 cost_currency = fx_rate currency。跨币种时必填；可空 = 没有汇率（不是 1:1）。
  fx_rate numeric(19,8) CHECK(fx_rate IS NULL OR fx_rate > 0),

  -- 平台费率（售价的小数比例，0.15 = 15%）。🔴 5.2.3.1 未交付 ⇒ 手工假设。
  platform_fee_rate numeric(9,6)
    CHECK(platform_fee_rate IS NULL OR (platform_fee_rate >= 0 AND platform_fee_rate < 1)),

  -- 平台固定费（每单固定金额，订阅费等）。🔴 5.2.3.1 未交付 ⇒ 手工假设。
  platform_fee_fixed numeric(19,4)
    CHECK(platform_fee_fixed IS NULL OR platform_fee_fixed >= 0),

  -- 物流每单位运费。🔴 5.2.3.2 未开发 ⇒ 手工假设。
  logistics_cost_per_unit numeric(19,4)
    CHECK(logistics_cost_per_unit IS NULL OR logistics_cost_per_unit >= 0),

  -- 月度固定成本（盈亏平衡销量的固定费用基数）。🔴 5.2.3.1 未交付 ⇒ 手工假设。
  monthly_fixed_cost numeric(19,4)
    CHECK(monthly_fixed_cost IS NULL OR monthly_fixed_cost >= 0),

  -- 成本口径窗口：采购成本由窗口内的实际到货事实按成本法加权派生。
  -- **窗口是结果的一部分** —— 不记下来，同一个批次换个窗口复算就得到不同的采购成本。
  -- 可空 = 用服务层的默认窗口（最近 90 天）。
  cost_window_from date,
  cost_window_to date,

  -- ===== 低毛利阈值（小数口径：0.15 = 15%）=====
  -- 毛利率低于它 ⇒ 标记为低毛利品。**必须是小数口径**：
  -- 写成 15 会被读成 1500%，于是「低毛利品」变成「所有正常品」。
  low_margin_threshold numeric(9,6) NOT NULL DEFAULT 0.150000
    CHECK(low_margin_threshold >= 0 AND low_margin_threshold < 1),

  -- 备注：这一批测算是为了什么（选品 / 清库存 / 调价前评估）。
  note text NOT NULL DEFAULT '',

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 跨币种必须要汇率：没有汇率时**拒绝**而不是默默按 1:1 折算（第三节）。
  CHECK(currency = cost_currency OR fx_rate IS NOT NULL),
  -- 成本窗口必须自洽。
  CHECK(cost_window_from IS NULL OR cost_window_to IS NULL OR cost_window_to >= cost_window_from)
);

-- 「最近跑过的批次」—— 批次台账的默认排序。
CREATE INDEX batch_calculation_run_by_created ON batch_calculation_run(tenant_id, created_at DESC, id);
-- 「某个店铺下的批次」—— 店铺维度的复盘。
CREATE INDEX batch_calculation_run_by_site ON batch_calculation_run(tenant_id, platform_code, site_code, created_at DESC);

-- ============================================================ 第六节表：batch_calculation_result —— 批量测算结果表
--
-- 一行 = 范围内的一个 SKU。**没有价格的 SKU 也在表里**（status='SKIPPED_NO_PRICE'）——
-- 「这个分类下有 40 个 SKU 没有价格观测」是运营必须看见的事实，
-- 把它从表里删掉，范围命中数与实际测算数就会对不上，而且看不出差在哪。
CREATE TABLE batch_calculation_result (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 所属批次。**不建外键**（见第八节）：触发器校验。
  run_id uuid NOT NULL,

  -- 被测的 SKU。**不建外键**（见第八节）：触发器校验。
  sku_id uuid NOT NULL,

  -- ===== 这一行测出来没有 =====
  -- CALCULATED     测出来了（下面的金额列有值）
  -- SKIPPED_NO_PRICE  没有可用售价 ⇒ 跳过（见第二节）
  status text NOT NULL CHECK(status IN ('CALCULATED','SKIPPED_NO_PRICE')),
  -- 跳过的原因（跳过时写明，不是空串）。测出来时为空串。
  skip_reason text NOT NULL DEFAULT '',

  -- ===== 售价（第二节）=====
  -- 这次测算用的**每单位**售价。可空 = 没测（跳过）。
  unit_price numeric(19,4) CHECK(unit_price IS NULL OR unit_price > 0),
  -- 售价取自哪一种：INTERNAL_PRICE（我方价）/ PLATFORM_PRICE（平台在售价）。
  price_source text CHECK(price_source IS NULL OR price_source IN ('INTERNAL_PRICE','PLATFORM_PRICE')),
  -- 这条价是什么时候观测到的（台账是只追加的，"最新一条" 是个时间点，不是"当前价"）。
  price_observed_at timestamptz,
  currency varchar(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),

  -- ===== 利润表（每单位口径，与 5.3.1.1 的瀑布图逐字对应）=====
  -- 全部可空 = **成本不完整时不给数字**（沿用 5.3.1.1 的决策就绪闸）：
  -- 缺项算出来的毛利率是系统性虚高的，而下游 5.4.1.2 批量调价会拿它直接改价。
  known_cost_total    numeric(19,4),
  platform_fee_amount numeric(19,4),
  logistics_amount    numeric(19,4),
  -- 🔴 扣减合计 = 已知成本 + 平台费 + 物流。**决策未就绪时它只是「已计入的扣减合计」** ——
  -- 缺的那几项（平台费 / 物流）没算进来，所以它**小于**真实扣减合计。
  -- 那一行同时有 gross_profit = null 与 decision_ready = false，不必靠这一列猜。
  total_deductions    numeric(19,4),
  gross_profit        numeric(19,4),
  gross_margin_rate   numeric(9,6),
  break_even_price    numeric(19,4),

  -- ===== 决策就绪度（保存时的快照）=====
  -- COMPLETE_FROM_RULES / COMPLETE_FROM_MANUAL_ASSUMPTIONS / INCOMPLETE
  decision_basis text NOT NULL
    CHECK(decision_basis IN ('COMPLETE_FROM_RULES','COMPLETE_FROM_MANUAL_ASSUMPTIONS','INCOMPLETE')),
  decision_ready boolean NOT NULL,

  -- ===== 异常标记（蓝图的「标记亏损品与低毛利品」）=====
  -- 🔴 两个标记**互斥**：亏损品（毛利额 < 0）单列，低毛利品只标「还没亏、但毛利率低于阈值」。
  -- 两者重叠的话，一个 SKU 会被计两次，「问题品总数」就多算了。
  is_loss       boolean NOT NULL DEFAULT false,
  is_low_margin boolean NOT NULL DEFAULT false,

  -- 缺口清单：这一行缺哪几项扣减（逗号分隔的扣减项码，如 'PLATFORM_FEE,LOGISTICS'）。
  -- 空串 = 扣减齐全。用于在结果表上直接看出「为什么这一行没有毛利率」。
  blocking_gap_codes text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个批次里一个 SKU 只能有一行（重跑批次是**新建**批次，不是覆盖）。
  UNIQUE(tenant_id, run_id, sku_id),
  -- 标记与状态必须自洽：没测出来的行不可能是亏损品。
  CHECK(status = 'CALCULATED' OR (NOT is_loss AND NOT is_low_margin)),
  -- 有毛利额才算得出亏损标记；毛利率同理。
  CHECK(NOT is_loss OR gross_profit IS NOT NULL),
  CHECK(NOT is_low_margin OR gross_margin_rate IS NOT NULL)
);

-- 结果列表主查询：按批次取结果（默认排序是毛利率升序，最差的排前面）。
CREATE INDEX batch_calculation_result_by_run ON batch_calculation_result(tenant_id, run_id, sku_id);
-- 「毛利率 < X」筛选：结果表上最常用的一条真筛选，必须走索引而不是全表扫。
CREATE INDEX batch_calculation_result_by_margin ON batch_calculation_result(tenant_id, run_id, gross_margin_rate);
-- 「售价 < Y」筛选。
CREATE INDEX batch_calculation_result_by_price ON batch_calculation_result(tenant_id, run_id, unit_price);
-- 异常标记筛选（亏损品 / 低毛利品）。
CREATE INDEX batch_calculation_result_by_flags ON batch_calculation_result(tenant_id, run_id, is_loss, is_low_margin);

-- ============================================================ 第七节：引用校验用触发器，不用外键
--
-- 🔴 上一批（batch-37）踩过的坑：某条车道在迁移里给**既有表**加了 FOREIGN KEY，
-- 结果打破了同服务既有测试的 TRUNCATE ⇒ 39 例 Errors ⇒ L2 直接红。
-- 本迁移因此**零个外键**：
--
--   * 结果 → 批次：触发器校验；
--   * 结果 → SKU：触发器校验。
--
-- 触发器的判据与外键等价（引用不存在 ⇒ 报 23514），但**不参与 TRUNCATE 的依赖图**。
-- 同批 V227 就是这么做的，它没打断任何既有测试。

-- 结果必须挂在本租户真实存在的批次与 SKU 上。
CREATE FUNCTION guard_batch_calculation_result() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM batch_calculation_run r
     WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.run_id) THEN
    RAISE EXCEPTION 'Batch calculation result % must reference an existing run',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION 'Batch calculation result % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 跳过态的行不许带售价（否则「跳过」就变成了「有价但没算」）。
  IF NEW.status='SKIPPED_NO_PRICE' AND NEW.unit_price IS NOT NULL THEN
    RAISE EXCEPTION 'Batch calculation result % is skipped and must not carry a price',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 跨币种的行必须有汇率（与批次一致）。
  IF NEW.status='CALCULATED' AND NEW.currency IS NOT NULL THEN
    IF EXISTS (
      SELECT 1 FROM batch_calculation_run r
       WHERE r.id=NEW.run_id AND r.tenant_id=NEW.tenant_id
         AND r.currency <> r.cost_currency AND r.fx_rate IS NULL) THEN
      RAISE EXCEPTION 'Batch calculation result % converts currencies without an fx_rate',
        NEW.id USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER batch_calculation_result_guard
  BEFORE INSERT OR UPDATE ON batch_calculation_result
  FOR EACH ROW EXECUTE FUNCTION guard_batch_calculation_result();

-- ============================================================ 第八节：RLS 与授权
--
-- 两张表都是租户级业务表（定价测算是租户自己的经营参数）。
-- 照抄 V227 第八节的 DO $$ FOREACH 写法：两张表的策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的批次 —— 那是一条看不见的越权面。
--
-- 🔴 批次表**只有** isolation 策略，**没有** data_scope 策略：
-- data_scope 的 WITH CHECK 会在 INSERT 时也生效，而批次是在结果行写入**之前**建的，
-- 一条「批次里至少有一个可见 SKU」的策略会让批次根本插不进去。
-- 批次表里存的是范围定义与参数（不含任何逐 SKU 的金额），汇总数字一律读时聚合 ——
-- 所以批次表本身没有可泄漏的明细。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['batch_calculation_run','batch_calculation_result'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 结果行可见性跟随 SKU：看不到 SKU 就看不到它的测算结果
-- （与 V227 的 data_scope 策略同一条纪律 —— 结果行里有这个 SKU 的成本口径）。
-- 这条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['batch_calculation_result'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON batch_calculation_run TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON batch_calculation_run TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON batch_calculation_result TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON batch_calculation_result TO starcon_app;
