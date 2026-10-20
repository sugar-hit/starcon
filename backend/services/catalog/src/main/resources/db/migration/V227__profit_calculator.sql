-- 5.3.1.1 利润测算器：把「售价 → 每一项扣减 → 毛利」的完整拆解落成可保存、可复算的测算方案。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：输入 SKU + 目标店铺 + 售价，输出完整利润拆解：售价 → 平台费用 → 物流成本 → 采购成本
--             → 头程 → 其他 → 毛利额与毛利率；反向支持输入目标毛利率倒推可承受售价。
--   **输入**：SKU、店铺、币种、售价或目标毛利率、数量（用于阶梯与运费）；成本、费用规则、汇率快照。
--   **输出**：利润瀑布图 + 保本价 + 目标毛利价 + 盈亏平衡销量；参数可分享为方案。
--   **关系**：上游 = 5.2 全部、6 采购成本、2 平台参数；下游 = 5.3.2.1 模板、5.4 调价。
--             **这是定价决策的核心工具**，必须显式列出每一项扣减并允许展开查看来源，
--             不能只给一个毛利率数字。
--
-- ============================================================ 第一节：本模块最贵的一个坑
--
-- 蓝图说本模块上游是「5.2 全部」。**5.2 是一个卷级引用**（成本与汇率），
-- 而 5.2 下面六个模块在本仓的真实状态**互不相同**（截至本迁移落地时）：
--
--   | 模块       | 名称         | 状态                                        |
--   |------------|--------------|---------------------------------------------|
--   | 5.2.1.1    | 成本构成     | ✅ 已交付（V215，catalog 本服务）            |
--   | 5.2.1.2    | 到岸成本     | ❌ 未交付（在途车道 V223）                   |
--   | 5.2.1.3    | 成本分摊     | ❌ 未交付（在途车道 V224）                   |
--   | 5.2.2.1    | 汇率查询     | ❌ 未交付（在途车道 V225）                   |
--   | 5.2.2.2    | 汇率快照     | ❌ 未开发                                   |
--   | 5.2.3.1    | 平台费用     | ❌ 未交付（在途车道 V226）                   |
--   | 5.2.3.2    | 物流与头程费 | ❌ 未开发                                   |
--
-- **缺的两项恰恰是毛利里最大的两块**：
--   * 平台费用（5.2.3.1）：佣金比例通常是**售价的 8%~15%**；
--   * 物流成本（5.2.3.2）：尾程配送通常是**每单几美元**量级。
--
-- ⇒ 「不扣这两项算出来的毛利率」是**系统性虚高**的，而不是「差一点点」。
-- 本迁移因此**不建任何假装上游已交付的表**：不建平台费用规则表（那是 5.2.3.1 的写入所有权）、
-- 不建汇率表（5.2.2.1）、不建物流计费规则表（5.2.3.2）、不建 landed cost 表（5.2.1.2）、
-- 不建分摊规则表（5.2.1.3）。
--
-- **本表存的是「测算方案」**：用户填的那些参数（SKU / 店铺 / 币种 / 售价或目标毛利率 / 数量）
-- 加上**显式标注为手工假设**的缺失规则（平台费率、物流单价、汇率、固定成本），
-- 以及物流阶梯（数量用于阶梯与运费）。
--
-- ============================================================ 第二节：手工假设为什么允许存在
--
-- 蓝图的**输入句**本来就写着「费用规则、汇率快照」是输入。上游规则模块没交付时，
-- 这两项输入唯一的来源就是**人**。所以本表允许存**手工假设**，
-- 但有一条硬纪律（服务层 + 前端都照它执行）：
--
--   * 手工假设**必须**标 sourceKind=MANUAL_ASSUMPTION，并写明 ownerModule（谁将来会接管它）；
--   * 手工假设**不冒充**系统规则：能力声明里对应扣减项的 available 仍然是 **false**；
--   * 一个扣减项要么来自上游规则、要么来自手工假设，**绝不允许**「没有来源却有个数字」。
--
-- ⇒ 没有手工假设时，本模块**不输出**毛利率数字（而不是输出一个虚高的），
-- 只输出「售价减已知扣减后还剩多少可用于覆盖缺失扣减」。
-- 这条纪律落在服务层（ProfitCalculatorService）而不是页面上 ——
-- 因为 5.3.2.1 模板与 5.4 调价会**程序化**地消费本模块的输出，
-- 页面上的一句提示拦不住一个自动改价的规则。
--
-- ============================================================ 第三节：跨币种绝不相加
--
-- 与 V215 同一条纪律：跨币种金额绝不相加（100 USD + 90 EUR = 190 是一个看起来对、实际错的数）。
-- 本服务**没有汇率表**（汇率是 12 财务 / 5.2.2.1 的资产），所以：
--
--   * 方案里同时存 `currency`（售价币种）与 `cost_currency`（成本币种），
--     两者不同时**必须**给 `fx_rate`（方向显式：1 cost_currency = fx_rate currency），
--     否则服务层拒绝测算（不是折算，是拒绝）；
--   * `fx_rate` 可空 = 「没有汇率」，**不是 1:1**。
--
-- ============================================================ 第四节：表结构
--
-- 两张表，职责不重叠：
--
--   | 表                                | 一行是什么                                | 写入 |
--   |-----------------------------------|-------------------------------------------|------|
--   | profit_calculator_scenario       | 一个测算方案（参数 + 手工假设 + 口径）      | 可改 |
--   | profit_calculator_logistics_tier  | 物流阶梯的一段（数量 → 每单位运费）         | 可改 |
--
-- 「方案」是蓝图输出句「参数可分享为方案」的落点：把一次测算的全部参数存下来，
-- 别人拿同一个方案能**复算出同一个结果**（口径、汇率、假设全在方案里）。
-- 方案是**租户内**的：分享链接仍然要求登录并落在分享者自己的租户上下文里 ——
-- 定价参数（成本、费率、汇率）跨租户可见是越权，不是协作。
--
-- ============================================================ 第五节表：profit_calculator_scenario —— 测算方案

CREATE TABLE profit_calculator_scenario (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 测算对象：SKU。**不用外键**（见第六节），用触发器校验引用。
  sku_id uuid NOT NULL,

  -- 目标店铺 / 平台：来自 2.1.1.1 平台目录（channel 服务，跨服务只读）。
  -- 存 code 字符串而不是 id：平台目录是**全局码表**（跨租户共享），
  -- 用 uuid 外键会绑死一个不属于本库的实体，而 code 才是业务上认的东西。
  platform_code text NOT NULL DEFAULT '',
  site_code text NOT NULL DEFAULT '',

  -- 售价币种 / 成本币种。**不相加**（第三节）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  cost_currency varchar(3) NOT NULL CHECK(cost_currency ~ '^[A-Z]{3}$'),

  -- 测算方向：给定售价算利润（PRICE）/ 给定目标毛利率倒推售价（TARGET_MARGIN）。
  -- 两种方向**共用同一套扣减结构**（服务层 ProfitCalculatorRules），
  -- 不是两套近似公式 —— 蓝图加粗句要求反向测算走同一套结构。
  mode text NOT NULL CHECK(mode IN ('PRICE','TARGET_MARGIN')),

  -- 售价（PRICE 模式必填）。
  input_price numeric(19,4) CHECK(input_price IS NULL OR input_price > 0),

  -- 目标毛利率（TARGET_MARGIN 模式必填；PRICE 模式下可选，填了才输出「目标毛利价」）。
  -- **口径是小数**：0.25 = 25%。写成 25 会被读成 2500%，所以 CHECK 限定在 [0,1)。
  target_margin_rate numeric(9,6)
    CHECK(target_margin_rate IS NULL OR (target_margin_rate >= 0 AND target_margin_rate < 1)),

  -- 数量：用于阶梯与运费（蓝图输入句），并用于把单位口径放大成总量口径。
  -- numeric(19,6)（与仓库的 6 位数量口径一致）。
  quantity numeric(19,6) NOT NULL DEFAULT 1 CHECK(quantity > 0),

  -- 成本口径窗口：采购成本由窗口内的实际到货事实按成本法加权派生，
  -- **窗口是结果的一部分** —— 不记下来，方案在别的一天复算就会得到不同的采购成本。
  -- 可空 = 用服务层的默认窗口（最近 90 天）。
  cost_window_from date,
  cost_window_to date,

  -- ===== 手工假设（上游规则模块未交付时的唯一合法来源，见第二节）=====

  -- 汇率：1 cost_currency = fx_rate currency。**方向显式**（5.2.2.1 的加粗句）。
  -- 可空 = 没有汇率（不是 1:1）。
  fx_rate numeric(19,8) CHECK(fx_rate IS NULL OR fx_rate > 0),

  -- 平台费率：售价的百分比（小数口径，0.15 = 15%）。5.2.3.1 未交付 ⇒ 手工假设。
  platform_fee_rate numeric(9,6)
    CHECK(platform_fee_rate IS NULL OR (platform_fee_rate >= 0 AND platform_fee_rate < 1)),

  -- 平台固定费：每单固定金额（订阅费等）。5.2.3.1 的「订阅费分摊」未交付 ⇒ 手工假设。
  platform_fee_fixed numeric(19,4)
    CHECK(platform_fee_fixed IS NULL OR platform_fee_fixed >= 0),

  -- 物流单价：每单位尾程运费。5.2.3.2 未交付 ⇒ 手工假设。
  -- 与阶梯表二选一：填了阶梯表就用阶梯（阶梯优先，服务层判定）。
  logistics_cost_per_unit numeric(19,4)
    CHECK(logistics_cost_per_unit IS NULL OR logistics_cost_per_unit >= 0),

  -- 月度固定成本：算「盈亏平衡销量」用的固定费用基数（平台订阅、仓储等）。
  -- 5.2.3.1 的订阅费分摊未交付 ⇒ 手工假设。可空 = 不知道 ⇒ 不输出盈亏平衡销量。
  monthly_fixed_cost numeric(19,4)
    CHECK(monthly_fixed_cost IS NULL OR monthly_fixed_cost >= 0),

  -- 保存时的**决策就绪度**：COMPLETE_FROM_RULES / COMPLETE_FROM_MANUAL_ASSUMPTIONS / INCOMPLETE。
  -- 存下来是为了让方案列表一眼看出「这个方案当时是不是可用的」——
  -- 成本是活的上游事实，就绪度会变，所以复算时以**当前**结果为准，
  -- 列表里显示的是**保存时**的快照。
  decision_basis text NOT NULL
    CHECK(decision_basis IN ('COMPLETE_FROM_RULES','COMPLETE_FROM_MANUAL_ASSUMPTIONS','INCOMPLETE')),

  -- 备注：方案说明（为什么这样假设）。可空。
  note text NOT NULL DEFAULT '',

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 模式与输入必须自洽：PRICE 必须有售价、TARGET_MARGIN 必须有目标毛利率。
  -- 反过来（PRICE 模式下也允许填目标毛利率）是有意义的：填了才输出「目标毛利价」。
  CHECK(
    (mode = 'PRICE' AND input_price IS NOT NULL)
    OR (mode = 'TARGET_MARGIN' AND target_margin_rate IS NOT NULL)
  )
);

-- 列表主查询：按 SKU 找它的方案。
CREATE INDEX profit_calculator_scenario_by_sku ON profit_calculator_scenario(tenant_id, sku_id);
-- 「最近改过的方案」—— 测算台账的默认排序。
CREATE INDEX profit_calculator_scenario_by_updated ON profit_calculator_scenario(tenant_id, updated_at DESC);
-- 「某个店铺下的方案」—— 站点维度的复盘。
CREATE INDEX profit_calculator_scenario_by_site ON profit_calculator_scenario(tenant_id, site_code, sku_id);

-- ============================================================ 第六节表：profit_calculator_logistics_tier —— 物流阶梯

-- 蓝图输入句「数量（用于阶梯与运费）」的落点：运费按数量分段（如首 100 单每单 4.00，
-- 之后每单 3.00）。**不建「头程费用规则表」** —— 那是 5.2.3.2 的写入所有权；
-- 本表只是**本次测算的假设阶梯**，挂在方案上，不是全局规则。
CREATE TABLE profit_calculator_logistics_tier (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 所属方案。**不用外键**（见第七节）。
  scenario_id uuid NOT NULL,

  -- 段序：从 1 开始，**升序**表示数量从小到大。
  seq int NOT NULL CHECK(seq > 0),

  -- 这一段覆盖到第几单（**含**）。NULL = 之后一直到无限。
  up_to_quantity numeric(19,6) CHECK(up_to_quantity IS NULL OR up_to_quantity > 0),

  -- 这一段的每单位运费。
  per_unit_cost numeric(19,4) NOT NULL CHECK(per_unit_cost >= 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个方案里段序唯一。
  UNIQUE(tenant_id, scenario_id, seq)
);

-- 阶梯读取主路径：按方案取全部段（按段序）。
CREATE INDEX profit_calculator_logistics_tier_by_scenario ON profit_calculator_logistics_tier(tenant_id, scenario_id, seq);

-- ============================================================ 第七节：引用校验用触发器，不用外键
--
-- 🔴 上一批（batch-37）踩过的坑：某条车道在迁移里给**既有表**加了 FOREIGN KEY，
-- 结果打破了同服务既有测试的 TRUNCATE ⇒ 39 例 Errors ⇒ L2 直接红。
-- 本迁移因此**零个外键**：
--
--   * 方案 → sku：触发器校验（见下）；
--   * 阶梯 → 方案：触发器校验（见下）。
--
-- 触发器的判据与外键等价（引用不存在 ⇒ 报 23514），但**不参与 TRUNCATE 的依赖图**：
-- 既有测试 TRUNCATE 既有表时不会被本表卡住。
-- 同批 V221 就是这么做的，它没打断任何既有测试。

-- (a) 方案：必须挂在本租户真实存在的 SKU 上。
CREATE FUNCTION guard_profit_calculator_scenario() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION 'Profit calculator scenario % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 模式与输入的自洽（与表级 CHECK 同一判据，触发器里再兜一次是为了让报错信息可读）。
  IF NEW.mode='PRICE' AND NEW.input_price IS NULL THEN
    RAISE EXCEPTION 'Profit calculator scenario % in PRICE mode requires input_price',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.mode='TARGET_MARGIN' AND NEW.target_margin_rate IS NULL THEN
    RAISE EXCEPTION 'Profit calculator scenario % in TARGET_MARGIN mode requires target_margin_rate',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 跨币种必须给汇率：没有汇率时**拒绝**而不是默默按 1:1 折算。
  IF NEW.currency <> NEW.cost_currency AND NEW.fx_rate IS NULL THEN
    RAISE EXCEPTION 'Profit calculator scenario % converts % to % but has no fx_rate',
      NEW.id, NEW.cost_currency, NEW.currency USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER profit_calculator_scenario_guard
  BEFORE INSERT OR UPDATE ON profit_calculator_scenario
  FOR EACH ROW EXECUTE FUNCTION guard_profit_calculator_scenario();

-- (b) 阶梯：必须挂在真实存在的方案上，且段序递增。
CREATE FUNCTION guard_profit_calculator_logistics_tier() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM profit_calculator_scenario s
     WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.scenario_id) THEN
    RAISE EXCEPTION 'Profit calculator logistics tier % must reference an existing scenario',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 段序必须大于上一段（阶梯的「从小到大」是语义，不是排序偏好）。
  IF EXISTS (
    SELECT 1 FROM profit_calculator_logistics_tier t
     WHERE t.tenant_id=NEW.tenant_id AND t.scenario_id=NEW.scenario_id
       AND t.seq > NEW.seq AND t.id <> NEW.id) THEN
    RAISE EXCEPTION 'Logistics tier seq % is not the highest tier of scenario %',
      NEW.seq, NEW.scenario_id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER profit_calculator_logistics_tier_guard
  BEFORE INSERT OR UPDATE ON profit_calculator_logistics_tier
  FOR EACH ROW EXECUTE FUNCTION guard_profit_calculator_logistics_tier();

-- ============================================================ 第八节：RLS 与授权
--
-- 两张表都是租户级业务表（定价方案是租户自己的经营参数），
-- 只需要租户隔离，可见性由端点层权限把关。
-- 照抄 V215 第八节的 DO $$ FOREACH 写法：三张表的策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的方案 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['profit_calculator_scenario','profit_calculator_logistics_tier'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性跟随 SKU：看不到 SKU 就看不到它的测算方案
-- （与 V215 的 data_scope 策略同一条纪律 —— 方案里有这个 SKU 的成本口径）。
-- 这条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
-- 阶梯通过方案间接跟随 SKU（方案不可见 ⇒ 其阶梯也不可见）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['profit_calculator_scenario'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['profit_calculator_logistics_tier'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM profit_calculator_scenario s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.scenario_id)) WITH CHECK (EXISTS(SELECT 1 FROM profit_calculator_scenario s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.scenario_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON profit_calculator_scenario TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON profit_calculator_scenario TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON profit_calculator_logistics_tier TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON profit_calculator_logistics_tier TO starcon_app;
