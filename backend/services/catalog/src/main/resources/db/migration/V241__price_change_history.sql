-- 5.5.1.1 价格历史：SKU × 店铺的**价格变更时间轴**（旧价 / 新价 / 谁动的 / 为什么），
-- 并把「改价前后��利润变化」按 5.3.1.1 的口径钉在同一条变更事实上。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：记录 SKU/listing 的价格变更历史（时间、旧价、新价、操作人/来源、原因），
--             支持按时间轴回溯并对比同期销量利润变化。
--   **输入**：价格变更事件（人工与自动规则都记）；查询条件。
--   **输出**：价格时间轴 + 关联的销售/利润变化。
--   **关系**：上游 = 5.4.1、5.3.2.2；下游 = 13 分析。
--             **价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"**。
--
-- ===========================================================================
-- 第一节：本模块与相邻四个已交付模块的分工（先把边界钉死，再建表）
-- ===========================================================================
--
-- 本模块**不重写**任何一套已有算术，也不新建任何别人的表：
--
--   * **5.5.2.1 异常告警**（V222，本服务内）：拥有 {@code price_anomaly_observation}
--     **价格观测台账**（一行 = 一个 SKU 在一个站点上的一次价格观测）。
--     🔴 **观测 ≠ 变更**：观测是「某时刻看到的价」，变更是「价被改了」。
--     把观测复制成变更事实，等于凭空造出一堆没有发生过的调价。
--     ⇒ 本模块**只读**它（作为「当前价」的来源），**绝不写、绝不 ALTER**。
--   * **5.4.1.2 批量调价**（V237，本服务内）：拥有 {@code batch_price_adjustment} /
--     {@code batch_price_adjustment_item}（明细里存了改价前后的新旧价）。
--     🔴 那两张表的写入所有者是 5.4.1.2。⇒ 本模块**只读**，
--     并通过**写端口**（{@code PriceChangeHistoryPort}）接收它投递过来的变更事件。
--   * **5.3.1.1 利润测算器**（V227，本服务内）：**改价前后的利润额与毛利率全部由它的引擎
--     （ProfitCalculatorService.calculate）算出来** —— 本模块**零个测算算法**。
--   * **5.3.2.2 价格规则**（V236，本服务内）：产出的是**建议**（{@code pricing_rule_suggestion}），
--     **建议不是生效价**（改价的写入所有权在 5.4.1.1）。⇒ 本模块**只读**它，
--     在时间轴上作为「待执行的建议」**只读引用**出现，**不复制成变更事实**。
--   * **5.4.1.1 调价单**：**与本模块同批并行开发**（不同车道，本仓此刻尚不存在）。
--     ⇒ 本模块对外暴露**写端口** {@code PriceChangeHistoryPort}，由它来投递变更事件。
--     端口的签名与字段映射写在 {@code docs/sessions/2026-10/2026-10-21-price-history.md}，
--     **本迁移不假装它已经接上了**。
--
-- ===========================================================================
-- 第二节：🔴 本模块**不改**任何既有表（一条 ALTER 都没有）
-- ===========================================================================
--
-- 与 V237（5.4.1.2）、V236（5.3.2.2）同一手法：
--   * **零个 FOREIGN KEY**（引用校验一律用触发器 —— 上一批给既有表加 FK 打破了
--     同服务既有测试的 TRUNCATE ⇒ 整批用例 Errors）；
--   * **零个 ALTER / UPDATE 既有表**（连索引都不加到别人的表上）；
--   * **不写** 5.5.2.1 的价格观测台账、5.4.1.2 的调价单、5.3.2.2 的建议表。
--
-- ===========================================================================
-- 第三节：两张表，职责不重叠
-- ===========================================================================
--
--   | 表                    | 一行是什么                                        | 写入 |
--   |-----------------------|---------------------------------------------------|------|
--   | price_change_history  | 一次价格变更（SKU × 店铺 × 时刻 × 旧价→新价）      | 追加 |
--   | price_change_reason   | 租户级「变更原因」字典（可维护的维度表）            | 可改 |
--
-- 🔴 **「变更事实」不可变**：价格改过一次就再改一次，**不在原地修改** ——
--   就地改写之后「这次到底改成了多少、什么时候改的、为什么改」全部无从追溯，
--   而这本账的全部价值就是回答这三个问题。
--   ⇒ {@code price_change_history} **只授 SELECT / INSERT**，不授 UPDATE / DELETE。
--
-- 🔴 **「原因」字典是可改的**：租户要增删自己的原因分类（竞品 / 成本 / 清库存 / 促销 …）。
--   ⇒ {@code price_change_reason} 授 SELECT / INSERT / UPDATE / DELETE。
--   🔴 但**字典改了不影响历史**：变更事实上存的是**当时的���因快照**
--   （{@code reason_code} + {@code reason_text}），不是外键指向字典。
--   字典改名 / 停用一条，历史上的那次变更仍然读得出「当时写的是什么」。
--
-- ===========================================================================
-- 第四节：🔴 listing 的口径 = (SKU × 店铺)，不新建 listing 影子表
-- ===========================================================================
--
-- 本仓的 listing 事实属 channel 服务（4.1.1.1），catalog **没有** listing 的本地投影，
-- 而 AGENTS.md 禁止跨服务直接查对方的表 / 跨库 FK / 共享视图。
-- ⇒ 本模块**不新建任何 listing 影子表**去假装上游已交付。
-- 一个「listing」在本模块里就是 **(SKU × 店铺)**：{@code sku_id + platform_code + site_code}，
-- 与 5.4.1.2、5.5.2.1 的口径**逐字相同**。
--
-- ===========================================================================
-- 第五节表：price_change_history —— 一次价格变更
-- ===========================================================================
CREATE TABLE price_change_history (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- ===== 被改的那个 listing = 一个 SKU 在一个店铺（第四节）=====
  sku_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 旧价 / 新价 / 价差 =====
  -- 旧价**可空** = 这是该 listing 的**首次定价**（此前没有价）。
  -- 🔴 首次定价**不是**「从 0 涨到新价」—— 那会让时间轴上凭空多出一跳 100% 的涨幅。
  old_price numeric(19,4) CHECK(old_price IS NULL OR old_price >= 0),
  -- 新价必须为正：0 价不是价（是免费），而免费会污染后面每一次毛利率。
  new_price numeric(19,4) NOT NULL CHECK(new_price > 0),
  -- 价差（新价 − 旧价）。正 = 涨价，负 = 降价。首次定价时为 null。
  price_delta numeric(19,4),
  -- 相对旧价的变动幅度（**小数口径**：0.05 = 降 5%）。首次定价时为 null。
  change_ratio numeric(9,6),

  -- ===== 方向（把「涨 / 跌 / 首次」从价差里显式提出来，查询与统计不必自己判符号）=====
  direction text NOT NULL
    CHECK(direction IN ('INITIAL','UP','DOWN','UNCHANGED')),

  -- ===== 谁动的 / 为什么动（蓝图的「操作人/来源、原因」）=====
  -- 变更来源（词表**只增不改**：它进事件表与契约，改语义会让历史读起来是错的）。
  change_source text NOT NULL CHECK(change_source IN (
    'MANUAL_ADJUSTMENT',   -- 人工调价（5.4.1.1 调价单 / 人工登记）
    'BATCH_ADJUSTMENT',    -- 批量调价（5.4.1.2）
    'PRICE_RULE',          -- 价格规则自动调价（5.3.2.2 → 5.4.1.1 执行后）
    'PRICE_LIST',          -- 价目表改价（5.1，**未交付**）
    'PLATFORM_FEEDBACK',   -- 平台侧价格回流（4.3.2.2 只回流状态，不回流价格）
    'IMPORT',              -- 批量导入
    'OTHER'
  )),
  -- 原因字典的编码（**快照**，不建外键：字典改了也不改写历史）。
  reason_code varchar(64) NOT NULL DEFAULT '',
  -- 原因的人话快照。**不建外键**，历史不依赖字典的存亡。
  reason_text text NOT NULL DEFAULT '',
  -- 操作人。可空 = 自动规则触发的（没人按这个按钮）。
  operator_id uuid,
  -- **投递方模块编号**（5.4.1.2 / 5.4.1.1 / 5.3.2.2 / 人工登记 …）。
  -- 「这条变更是谁写的」与「这次变更是谁做的」是两件事：规则触发的变更操作人可空，
  -- 但投递方永远有值 —— 否则一条来路不明的变更进了账，无人追得出它从哪来。
  source_module varchar(32) NOT NULL,
  -- 投递方给的引用（如批量调价单 id、调价单号）。留空 = 投递方没有引用。
  source_reference varchar(160) NOT NULL DEFAULT '',
  -- 幂等键：同一投递方重复投递同一次变更**不会**堆出两行。
  event_key varchar(200) NOT NULL CHECK(btrim(event_key) <> ''),

  -- ===== 生效时刻（变更发生的时间轴坐标）=====
  -- 🔴 timestamptz：跨时区店铺必须按绝对时刻排序，
  --   用 LocalDateTime 存会在跨时区比较时静默错 8 小时。
  effective_at timestamptz NOT NULL,
  -- 入账时刻（事件被本服务收下的时刻）。与生效时刻不同：
  -- 一条补录的调价单，生效在上周、入账在今天。
  recorded_at timestamptz NOT NULL DEFAULT now(),

  -- ===== 改价前后的利润（**全部由 5.3.1.1 的引擎算**，本模块零算法）=====
  -- 🔴 **成本不完整时这四列是 null，不是 0**：null = 「没测准」，0 = 「毛利刚好 0」。
  gross_profit_before numeric(19,4),
  gross_margin_before numeric(9,6)
    CHECK(gross_margin_before IS NULL OR (gross_margin_before > -1 AND gross_margin_before < 1)),
  gross_profit_after  numeric(19,4),
  gross_margin_after  numeric(9,6)
    CHECK(gross_margin_after IS NULL OR (gross_margin_after > -1 AND gross_margin_after < 1)),
  -- 决策就绪度（入账时的快照）。false 时上面四列必为 null。
  decision_ready boolean NOT NULL DEFAULT false,
  -- 为什么没测准（人话）。decision_ready=true 时也允许有备注。
  profit_note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 幂等：同一租户内 event_key 唯一 ⇒ 重复投递**不堆行**。
  UNIQUE(tenant_id, event_key),

  -- 平台码 / 站点码不为空，形状与 V222 / V237 的同一套约定一致。
  CHECK(btrim(platform_code) <> '' AND btrim(site_code) <> ''),
  CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(site_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  -- 投递方必须点名：来路不明的变更不许进账。
  CHECK(btrim(source_module) <> ''),
  -- 方向与价差必须自洽（方向是**派生**的，不能与数字打架）。
  CHECK(
    (direction = 'INITIAL'  AND old_price IS NULL     AND price_delta IS NULL AND change_ratio IS NULL)
    OR (direction = 'UP'       AND old_price IS NOT NULL AND price_delta > 0)
    OR (direction = 'DOWN'     AND old_price IS NOT NULL AND price_delta < 0)
    OR (direction = 'UNCHANGED' AND old_price IS NOT NULL AND price_delta = 0)
  ),
  -- 没测准就不许带利润数字（避免把「没测准」渲染成 0）。
  CHECK(decision_ready OR (gross_profit_before IS NULL AND gross_margin_before IS NULL
                           AND gross_profit_after IS NULL AND gross_margin_after IS NULL)),
  -- 首次定价 ⇒ 没有旧价，也就没有价差与幅度。
  CHECK(direction = 'INITIAL' OR old_price IS NOT NULL)
);

-- 时间轴默认顺序：**生效时刻倒序**（最近改的在前）。
CREATE INDEX price_change_history_by_effective
  ON price_change_history(tenant_id, effective_at DESC, id);
-- 「这个 listing 的时间轴」—— (SKU × 店铺) 维度的回溯。
CREATE INDEX price_change_history_by_listing
  ON price_change_history(tenant_id, sku_id, platform_code, site_code, effective_at DESC);
-- 「这个 SKU 在所有店铺上的改动」—— 跨店铺复盘。
CREATE INDEX price_change_history_by_sku
  ON price_change_history(tenant_id, sku_id, effective_at DESC);
-- 「按来源筛」—— 台账页的筛选维度。
CREATE INDEX price_change_history_by_source
  ON price_change_history(tenant_id, change_source, effective_at DESC);
-- 「这个店铺的改价历史」—— 店铺维度的复盘。
CREATE INDEX price_change_history_by_shop
  ON price_change_history(tenant_id, platform_code, site_code, effective_at DESC);
-- 「按原因筛」。
CREATE INDEX price_change_history_by_reason
  ON price_change_history(tenant_id, reason_code, effective_at DESC);

-- ===========================================================================
-- 第六节表：price_change_reason —— 租户级「变更原因」字典
-- ===========================================================================
--
-- 🔴 **为什么要有字典而不是自由文本**：蓝图要求记「原因」，而时间轴要能**按原因筛**。
--   自由文本写出来的「跟竞品」「竞品」是两个字符串，筛不出来；
--   字典给出稳定编码，筛选与统计才有意义。
--
-- 🔴 **字典是租户级的**：不同卖家对「原因」的分类不一样（有人分「清库存 / 竞品 / 成本」，
--   有人分「大促 / 断货 / 汇率」）。所以它是租户自己维护的维度表，不是系统内置枚举。
CREATE TABLE price_change_reason (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 编码（租户内唯一）。筛选与统计用它。
  code varchar(64) NOT NULL CHECK(btrim(code) <> ''),
  -- 人话标签。
  label varchar(100) NOT NULL CHECK(btrim(label) <> ''),
  -- 分类（**系统内置的粗分类**，保证跨租户也能做粗口径统计）。
  category text NOT NULL CHECK(category IN (
    'COMPETITION',  -- 竞品
    'COST',         -- 成本变动
    'STOCK',        -- 库存（清库存 / 断货）
    'LIFECYCLE',    -- 生命周期（新品 / 老品退市）
    'PROMOTION',    -- 促销
    'SEASONAL',     -- 季节
    'MANUAL',       -- 其它人工原因
    'OTHER'
  )),
  -- 停用后不再出现在新登记的下拉框里，但**历史事实照旧**（历史存的是快照）。
  enabled boolean NOT NULL DEFAULT true,
  -- 排序（小的在前）。
  sort_order int NOT NULL DEFAULT 0,
  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个租户内编码唯一 —— 否则按编码筛会命中两行。
  UNIQUE(tenant_id, code)
);

-- 字典按排序取（下拉框顺序稳定）。
CREATE INDEX price_change_reason_by_order
  ON price_change_reason(tenant_id, sort_order, code);
-- 「只看启用中的」。
CREATE INDEX price_change_reason_by_enabled
  ON price_change_reason(tenant_id, enabled, sort_order);

-- ===========================================================================
-- 第七节：引用校验用触发器，不用外键（第二节）
-- ===========================================================================
--
-- 变更事实必须挂在本租户真实存在的 SKU 上。
-- 判据与外键等价（引用不存在 ⇒ 报 23514），但**不参与 TRUNCATE 的依赖图**。
CREATE FUNCTION guard_price_change_history_sku() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION 'Price change history % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER price_change_history_sku_guard
  BEFORE INSERT OR UPDATE ON price_change_history
  FOR EACH ROW EXECUTE FUNCTION guard_price_change_history_sku();

-- ===========================================================================
-- 第八节：RLS 与授权
-- ===========================================================================
--
-- 两张表都是租户级业务表。
-- 照抄 V237 第八节的 DO $$ FOREACH 写法：两张表的 isolation 策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的调价历史 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['price_change_history','price_change_reason'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 变更事实的可见性跟随 SKU：看不到 SKU 就看不到它的改价历史
-- （事实行里有这个 SKU 的旧价、新价与利润结构）。
-- 这条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['price_change_history'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
--
-- 🔴 **变更事实只授 SELECT / INSERT**：价格改过一次就再改一次，**不在原地修改**。
--   「这次到底改成了多少、什么时候改的、为什么改」是这本账的全部价值，
--   就地改写之后这三个问题全部无从追溯。
GRANT SELECT,INSERT ON price_change_history TO catalog_app;
GRANT SELECT,INSERT ON price_change_history TO starcon_app;
-- 原因字典是配置，可改可删。
GRANT SELECT,INSERT,UPDATE,DELETE ON price_change_reason TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_change_reason TO starcon_app;
