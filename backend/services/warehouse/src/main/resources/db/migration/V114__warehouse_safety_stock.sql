-- ===========================================================================
-- 7.2.2.2 安全库存：SKU/分类/仓库维度的阈值配置、预警级别、预警生成与补货建议
-- ===========================================================================
-- 蓝图原文：「按 SKU/分类/仓库配置安全库存阈值与预警级别，低于阈值时触发预警并可关联自动
-- 生成补货建议。输入：阈值配置；当前可用量投影；日均销量。输出：安全库存预警 + 可选自动
-- 触发 6.2.1.2 补货建议。关系：上游 = 7.1.1.3、13 销量；下游 = 6.2.1.1、1 工作台预警。
-- **安全库存要区分"平台断货风险"和"自有仓耗尽"：FBA 断货造成的损失远大于自有仓。」
--
-- ---------------------------------------------------------------------------
-- 那句加粗红线在本迁移里落成三件**可计算**的东西，而不是一句注释
-- ---------------------------------------------------------------------------
-- 1. warehouse_kind 挂在**阈值档自己**上（safety_stock_policy_warehouse），不去改 warehouse
--    表 —— warehouse 没有 type 列，FBA 分类属 7.1.3.1 那条车道（与 V56 同一纪律）。
-- 2. §2.4 的延迟约束触发器钉死「一档阈值挂的仓必须同 kind」：FBA 与自有仓永远是两个
--    独立的池子。把两者混在同一档里求和，就会得到一个「还剩 30 件」而实际上自有仓已经
--    归零、FBA 早就断货的数 —— 那正是这条红线要防的误判。
-- 3. 预警类型由 kind 派生（PLATFORM_FBA → PLATFORM_STOCKOUT_RISK / OWN →
--    OWN_WAREHOUSE_DEPLETION），且平台断货在展示与排序上一律按**上一级严重度**处理
--    （SafetyStockService.escalated）。损失更大的那一边排前面，这件事因此可排序、可断言。
--
-- ---------------------------------------------------------------------------
-- 为什么日均销量是「登记进来的事实」而不是从订单算出来的
-- ---------------------------------------------------------------------------
-- 蓝图上游写的是「13 销量」，而 13 尚未交付；库存流水（inventory_transaction）没有店铺
-- 维度，一个仓发出去的货拆不到「哪个渠道卖掉的」。照 7.8.1.1 的 allocation_shop_sales
-- 先例：sales 登记进来，带 source_system 标明来源。**不取代码常量、不建跨服务外键或视图**
-- （AGENTS.md 模块边界纪律）。/capabilities 会把这件事如实报出（salesSourceDerived=false）。
--
-- ---------------------------------------------------------------------------
-- 为什么补货建议先落库而不直接推 6.2.1.2
-- ---------------------------------------------------------------------------
-- 6.2.1.2 补货建议未交付，且它属 06 采购域、跨服务独立数据库。这里生成建议并落库
-- （safety_stock_replenishment_suggestion），把 external_purchase_reference 留空，
-- 等它交付后由它回写单号。**外键、视图、跨库写入一律不做**。
-- 未登记销量的 SKU 不给建议量，而是留空并报出原因 —— 拿 0 当「不需要补货」是最贵的错误。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. safety_stock_policy · 阈值分档
-- ---------------------------------------------------------------------------
-- 一档 = 一个 (范围 × 阈值 × 预警级别 × 生效区间)。范围解析顺序 SKU > CATEGORY > DEFAULT，
-- 与 7.2.2.1 的 SHOP > CHANNEL > DEFAULT、7.8.1.1 的 SKU > SHOP > DEFAULT 同一套优先级。
CREATE TABLE safety_stock_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 范围三档。DEFAULT 是全租户兜底（没有它就没有任何 SKU 受安全库存保护，
  -- 这是一个必须如实报出来的状态而不是按 0 处理）。
  scope_type varchar(16) NOT NULL
    CONSTRAINT chk_scope_type CHECK(scope_type IN ('DEFAULT','CATEGORY','SKU')),

  -- SKU 与分类都属 catalog 服务：只存逻辑 ID + 写入时快照的可读名，不建外键、不查它的表
  -- （与 V1 sku_ref / V56 reference_id 同一纪律）。
  category_id uuid,
  sku_id uuid,
  sku_label varchar(150) NOT NULL DEFAULT '',

  -- 安全库存阈值（件）。0 是合法取值：它表达的是「这个 SKU 允许卖到零」，
  -- 与「没配阈值」不是一回事，所以阈值 NOT NULL 而 DEFAULT 档的存在与否靠 scope_type 表达。
  threshold_quantity numeric(19,6) NOT NULL
    CONSTRAINT chk_threshold_non_negative CHECK(threshold_quantity >= 0),

  -- 预警级别：阈值配置里的一部分（蓝图原文「配置安全库存阈值与**预警级别**」）。
  -- CRITICAL / WARNING / INFO 是三档受控词表，不接受自由文本。
  alert_severity varchar(10) NOT NULL
    CONSTRAINT chk_alert_severity CHECK(alert_severity IN ('CRITICAL','WARNING','INFO')),

  -- 补货建议的目标覆盖天数。给 NULL = 不推算建议量（可以是没登记销量，也可以是运营不想自动建议）。
  -- 刻意给「天数」而不是给「倍数」：覆盖天数是运营能直接答得上来的问题（"备 30 天"），
  -- 而倍数在低销量 SKU 上会被放大成天文数字。
  reorder_coverage_days numeric(9,2)
    CONSTRAINT chk_coverage_days_non_negative CHECK(reorder_coverage_days >= 0),

  -- 是否在预警生成时自动关联补货建议。默认 false：自动建建议是一条会产生采购动作的边，
  -- 必须由租户显式打开。
  auto_suggest_replenishment boolean NOT NULL DEFAULT false,

  -- 生效区间（左闭右开），effective_to 为 NULL = 至今仍在生效。
  -- 与 17.5.1.1 / 7.2.2.1 同一套分档约定：口径写定后不可改，改 = 收口旧档 + 开新档。
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,

  note varchar(500) NOT NULL DEFAULT '',
  -- 操作人：已认证成员 id（不接受请求参数传入）。
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 范围与定位键必须配套：少一条就会出现一档永远命中不了的配置
  -- （「SKU 档却没有 sku_id」在评估时无从回答"它到底管谁"）。
  CONSTRAINT chk_scope_key CHECK(
    (scope_type='DEFAULT'   AND category_id IS NULL AND sku_id IS NULL) OR
    (scope_type='CATEGORY'  AND category_id IS NOT NULL AND sku_id IS NULL) OR
    (scope_type='SKU'       AND sku_id IS NOT NULL AND category_id IS NULL)
  ),

  CONSTRAINT chk_effective_interval CHECK(effective_to IS NULL OR effective_to > effective_from),

  -- 开了自动建议就必须给出覆盖天数：否则「自动生成补货建议」这句承诺没有可计算的量，
  -- 界面上会得到一条永远算不出数量的建议。
  CONSTRAINT chk_auto_suggest_needs_coverage
    CHECK(NOT auto_suggest_replenishment OR reorder_coverage_days IS NOT NULL),

  -- 快照了可读名就必须有对应的定位键，避免留下一条指向空对象的标签。
  CONSTRAINT chk_label_needs_sku CHECK(btrim(sku_label) = '' OR sku_id IS NOT NULL)
);

-- 生效值解析的主查询：取某档在某一时刻的行。effective_from DESC 让"最近开的一档"排在最前。
CREATE INDEX safety_stock_policy_resolve
  ON safety_stock_policy(tenant_id, scope_type, sku_id, category_id, effective_from DESC);
-- 当前在生效的全部档（列表默认视图 + 评估扫描范围）。
CREATE INDEX safety_stock_policy_open
  ON safety_stock_policy(tenant_id, effective_from DESC) WHERE effective_to IS NULL;
-- 预警反查"这条预警是哪一档生成的"（配置被换版后历史预警仍指回原档）。
CREATE INDEX safety_stock_policy_reference
  ON safety_stock_policy(tenant_id, sku_id) WHERE sku_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 1b. 槽位唯一性是「(范围键 × 仓)」而不是「范围键」
-- ---------------------------------------------------------------------------
-- 这里**刻意不用** 7.2.2.1 那样的部分唯一索引（V56 的 sellable_policy_open_reference
-- 建在 (tenant_id, scope_type, reference_id) 上）。原因就在蓝图那句红线：
-- 同一个 SKU 必须能同时对**自有仓**和**FBA 仓**各配一档阈值 —— 那正是「区分平台断货风险与
-- 自有仓耗尽」的物理前提。把槽位只按范围键去重的话，这两档会在插入第二档时就被拒，
-- 模块最核心的那个能力直接配不出来。
--
-- 所以唯一性下沉到「(租户 × 范围 × 范围键 × 仓) 至多一档仍在生效」，由 §7.5 的延迟约束
-- 触发器跨两张表守住（阈值键在 p 表、仓在挂仓表，PG 的部分唯一索引跨不了表）。


-- ---------------------------------------------------------------------------
-- 2. safety_stock_policy_warehouse · 阈值挂仓 + 这个仓算自有还是平台
-- ---------------------------------------------------------------------------
-- warehouse_kind 是「平台断货风险 vs 自有仓耗尽」的分类来源，标注在**阈值档侧**而
-- 不改 warehouse 表：warehouse 没有 type 列，FBA 分类归 7.1.3.1 那条车道。
--   OWN         自有仓耗尽
--   PLATFORM_FBA 平台仓（FBA 观测值），断货损失更大
CREATE TABLE safety_stock_policy_warehouse (
  tenant_id uuid NOT NULL,
  policy_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  warehouse_kind varchar(16) NOT NULL CHECK(warehouse_kind IN ('OWN','PLATFORM_FBA')),
  created_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id,policy_id,warehouse_id),
  -- 复合外键钉住"这个仓确实属于本租户"（warehouse 上有 UNIQUE(tenant_id,id)）。两个都指向
  -- 本服务的表，不是跨服务引用。
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,policy_id) REFERENCES safety_stock_policy(tenant_id,id) ON DELETE CASCADE
);

-- 评估扫描的主查询：按仓取它挂在哪些在效阈值档上。
CREATE INDEX safety_stock_policy_warehouse_scan
  ON safety_stock_policy_warehouse(tenant_id,warehouse_id,warehouse_kind);
-- 详情页"这一档吃哪几个仓"。
CREATE INDEX safety_stock_policy_warehouse_policy
  ON safety_stock_policy_warehouse(tenant_id,policy_id);

-- 2.4 同 kind 约束（延迟触发器在 §4 定义，这里先声明它的必要性）：
-- 一档阈值挂的仓必须同 kind —— FBA 与自有仓是两个独立的池子，混在一档里求和就会得到
-- 一个「还剩 30 件」而实际上其中一个仓早就归零的数。

-- ---------------------------------------------------------------------------
-- 3. safety_stock_sales · 日均销量的登记事实（跨服务边界）
-- ---------------------------------------------------------------------------
-- 与 allocation_shop_sales（7.8.1.1）同一套写法，但**维度不同**：本表的粒度是
-- (SKU × 期间)，不按店铺拆 —— 安全库存关心的是「这个 SKU 还能卖几天」，不是「哪个店在卖」。
-- 两张表各自是事实所有者，本表不读也不写 7.8.1.1 那张（每张事实表只有一个写入所有者）。
CREATE TABLE safety_stock_sales (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 来源系统标记。销量是**登记**进来的事实（13 销量域未交付，库存流水没有渠道维度），
  -- 这一列让调用方能看出这批数是从哪个系统登记的，也让它日后被 13 回写时不必改表结构。
  source_system varchar(32) NOT NULL DEFAULT '',

  -- SKU 属 catalog：逻辑 ID，不建外键（与 V1/V56/V68 同一纪律）。
  sku_id uuid NOT NULL,
  sku_label varchar(150) NOT NULL DEFAULT '',

  -- 业务日期闭区间（不是系统时间）：销量本来就是业务事实。
  period_start date NOT NULL,
  period_end date NOT NULL,
  CHECK(period_end >= period_start),

  -- 允许 0：某天确实没卖出去是真实结果。数量一律 numeric + BigDecimal，禁浮点（AGENTS.md）。
  units_sold numeric(19,6) NOT NULL DEFAULT 0 CHECK(units_sold >= 0),

  recorded_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一来源同一 SKU 同一区间只有一条：重复登记会把这段时间的销量算两遍，
  -- 日均销量随之偏掉，可售天数与建议量一起偏，而界面上看不出任何异常。
  UNIQUE(tenant_id,source_system,sku_id,period_start,period_end),
  CHECK(btrim(source_system) <> '')
);

-- 日均销量的主查询：按 SKU 取最近一段登记。
CREATE INDEX safety_stock_sales_window
  ON safety_stock_sales(tenant_id,sku_id,period_end DESC,period_start DESC);

-- 2b. 同来源同 SKU 的登记区间不得重叠。
--
-- 唯一约束只能挡住「完全相同的区间」（UNIQUE 里有 period_start 与 period_end），
-- 挡不住 [1,31] 与 [15,20] 这种**部分重叠**：两段都算进去，日均销量会虚高，
-- 可售天数会算短，补货建议量随之偏大 —— 这条链路最终会变成一张采购单，
-- 而界面上看不出任何异常。V68 的 allocation_shop_sales 有同样的形状，那里的偏差体现在
-- 分摊比例上（可事后对账），这里会直接变成花钱，所以这里显式挡住。
CREATE FUNCTION guard_safety_stock_sales_window() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE other_id uuid; other_from date; other_to date;
BEGIN
  SELECT s.id, s.period_start, s.period_end INTO other_id, other_from, other_to
    FROM safety_stock_sales s
   WHERE s.tenant_id = NEW.tenant_id
     AND s.source_system = NEW.source_system
     AND s.sku_id = NEW.sku_id
     AND s.id <> NEW.id
     AND daterange(s.period_start, s.period_end, '[]')
       && daterange(NEW.period_start, NEW.period_end, '[]')
   ORDER BY s.period_start
   LIMIT 1;
  IF other_id IS NOT NULL THEN
    RAISE EXCEPTION '该 SKU 在此来源下已有一条区间 % ~ % 的登记（id=%），与本次 % ~ % 重叠：'
      '重叠区间会被两段都算进去，日均销量随之虚高、补货建议量偏大',
      other_from, other_to, other_id, NEW.period_start, NEW.period_end
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER safety_stock_sales_window
  BEFORE INSERT OR UPDATE ON safety_stock_sales
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_sales_window();

-- ---------------------------------------------------------------------------
-- 4. safety_stock_evaluation · 评估批次（append-only）
-- ---------------------------------------------------------------------------
-- 为什么需要这张表而不是只留预警行：一次评估可能**生成 0 条预警**，而"扫了 12 档配置、
-- 结果 0 条预警"本身是一条必须可回答的事实（运营要据此判断"是真没风险，还是压根没扫"）。
-- 只留预警行会让零结果的评估彻底消失。
CREATE TABLE safety_stock_evaluation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  triggered_by_membership_id uuid NOT NULL,
  -- 评估时刻。这是唯一一处用 ?::timestamptz 落库的地方（cast 写在 values 位置）。
  evaluated_at timestamptz NOT NULL DEFAULT now(),

  -- 本次评估的口径统计：三张表各自扫到多少、缺了什么。
  policies_evaluated bigint NOT NULL DEFAULT 0,
  -- 扫到但**无法展开成具体 (仓, SKU)** 的阈值档数（分类档/兜底档且调用方没带 SKU 清单）。
  -- 刻意单列一列：没有它，一次漏扫会显示成「扫了 3 档、0 条预警」，而真实情况是
  -- 另外 2 档压根没被评估 —— 「没扫」和「扫了没风险」必须长得不一样。
  policies_not_scannable bigint NOT NULL DEFAULT 0,
  warehouses_evaluated bigint NOT NULL DEFAULT 0,
  skus_evaluated bigint NOT NULL DEFAULT 0,
  -- 扫到但**没有销量登记**的 SKU 数。把它单列出来而不是混进 0，
  -- 是为了让"可售天数算不出来"这件事与"算出来是 0"分得开。
  skus_without_sales bigint NOT NULL DEFAULT 0,

  alerts_opened bigint NOT NULL DEFAULT 0,
  alerts_suppressed bigint NOT NULL DEFAULT 0,
  suggestions_created bigint NOT NULL DEFAULT 0,

  -- 扫描范围（可为空 = 全租户在效档）。
  scope_warehouse_id uuid,
  scope_sku_id uuid,

  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 预警批次反查（"这批预警是哪次评估产生的"）。
  FOREIGN KEY(tenant_id,scope_warehouse_id) REFERENCES warehouse(tenant_id,id)
);

CREATE INDEX safety_stock_evaluation_recent
  ON safety_stock_evaluation(tenant_id,evaluated_at DESC);
CREATE INDEX safety_stock_evaluation_warehouse
  ON safety_stock_evaluation(tenant_id,scope_warehouse_id);

-- ---------------------------------------------------------------------------
-- 5. safety_stock_alert · 安全库存预警
-- ---------------------------------------------------------------------------
-- 预警是**事实**：一旦某档在某仓某 SKU 上判定低于阈值，那一刻的 available/threshold/
-- 日均销量/可售天数就必须原样留档。库存后来补上了也不会改写这条 —— 改了就没法回答
-- "当时为什么报警"，而那正是复盘时要看的。
CREATE TABLE safety_stock_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  evaluation_id uuid NOT NULL,
  policy_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- SKU 可读名在生成预警时快照一份（SKU 主数据在 catalog 服务，跨库读不到，
  -- 本地 sku_ref 也可能尚未同步）。留档后即使 SKU 改名或改名失败，
  -- 三月前那条预警仍然读得懂自己报的是哪个商品。
  sku_label varchar(150) NOT NULL DEFAULT '',

  -- 预警类型：由 warehouse_kind 派生，是蓝图那句红线的落点。
  -- PLATFORM_STOCKOUT_RISK = 平台断货风险（FBA，损失远大于自有仓）
  -- OWN_WAREHOUSE_DEPLETION = 自有仓耗尽
  alert_type varchar(24) NOT NULL
    CHECK(alert_type IN ('PLATFORM_STOCKOUT_RISK','OWN_WAREHOUSE_DEPLETION')),

  -- 配置里那一档的预警级别（CRITICAL/WARNING/INFO）。
  severity varchar(10) NOT NULL CHECK(severity IN ('CRITICAL','WARNING','INFO')),

  -- 读数快照。available 来自 inventory_balance 的 SELLABLE 口径聚合（与 7.2.2.1 池容量同源），
  -- shortfall 恒为 threshold − available 且只在低于阈值时生成，因此恒 >= 0。
  available_quantity numeric(19,6) NOT NULL,
  threshold_quantity numeric(19,6) NOT NULL,
  shortfall_quantity numeric(19,6) NOT NULL CHECK(shortfall_quantity >= 0),

  -- 日均销量与可售天数。**可以是 NULL**：没登记销量时算不出来，
  -- 此时留空并由服务报出原因，而不是拿 0 冒充（0 会被读成"永远不会断货"）。
  daily_average_sales numeric(19,6),
  days_of_cover numeric(19,4),
  CHECK(daily_average_sales IS NULL OR daily_average_sales >= 0),
  CHECK(days_of_cover IS NULL OR days_of_cover >= 0),
  -- 可售天数必须有销量才算得出来：有天数必然有销量，反之不成立（销量为 0 时天数为无穷）。
  CHECK(days_of_cover IS NULL OR daily_average_sales IS NOT NULL),

  -- 预警生成时刻 = 本次评估时刻（不是创建时间戳）。
  detected_at timestamptz NOT NULL,

  -- 状态机：OPEN → ACKNOWLEDGED → RESOLVED，单向（§6.2 触发器钉死）。
  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
  acknowledged_at timestamptz,
  acknowledged_by_membership_id uuid,
  resolved_at timestamptz,

  -- 该档的口径留痕：换版/作废后仍能答出这条预警是按哪一档判的。
  policy_scope_type varchar(16) NOT NULL CHECK(policy_scope_type IN ('DEFAULT','CATEGORY','SKU')),
  effective_from timestamptz NOT NULL,

  -- 复核提示（"没有销量登记，可售天数算不出来"这类），前端直接显示，不在前端拼文案。
  review_note varchar(300) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  FOREIGN KEY(tenant_id,evaluation_id) REFERENCES safety_stock_evaluation(tenant_id,id),
  FOREIGN KEY(tenant_id,policy_id) REFERENCES safety_stock_policy(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  -- 同一批评估里，同一档 × 同一仓 × 同一 SKU 只出一条预警。
  -- 没有这条，一次评估重复触发就会刷出 N 条一模一样的预警。
  UNIQUE(tenant_id,evaluation_id,policy_id,warehouse_id,sku_id),

  -- 低于阈值才生成预警：available 必须**真的**比 threshold 低。
  CHECK(available_quantity < threshold_quantity),

  -- 已认领/已处置必须同时留痕，否则"什么时候处理的"永远答不出来。
  CHECK(status='OPEN' OR (acknowledged_at IS NOT NULL AND acknowledged_by_membership_id IS NOT NULL)),
  CHECK(status<>'RESOLVED' OR resolved_at IS NOT NULL)
);

-- 预警工作台的主查询：按租户取未处置的、最危险的排最前。
CREATE INDEX safety_stock_alert_open
  ON safety_stock_alert(tenant_id,status,alert_type,detected_at DESC) WHERE status<>'RESOLVED';
-- 列表默认视图 + 分页排序键。
CREATE INDEX safety_stock_alert_recent
  ON safety_stock_alert(tenant_id,detected_at DESC,id);
-- 按仓 / 按 SKU 过滤（仓盘点时逐仓看预警）。
CREATE INDEX safety_stock_alert_warehouse
  ON safety_stock_alert(tenant_id,warehouse_id,detected_at DESC);
CREATE INDEX safety_stock_alert_sku
  ON safety_stock_alert(tenant_id,sku_id,detected_at DESC);
-- 配置档案页"这条阈值触发过哪些预警"。
CREATE INDEX safety_stock_alert_policy
  ON safety_stock_alert(tenant_id,policy_id,detected_at DESC);

-- ---------------------------------------------------------------------------
-- 6. safety_stock_replenishment_suggestion · 补货建议（关联 6.2.1.2）
-- ---------------------------------------------------------------------------
-- 蓝图：「低于阈值时触发预警并可关联**自动生成**补货建议」。
-- 6.2.1.2 未交付且属 06 采购域（跨服务、独立库），所以本表先落库本服务的建议，
-- external_purchase_reference 留空等它回写单号。**不建外键、不建视图、不跨库写。**
CREATE TABLE safety_stock_replenishment_suggestion (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  alert_id uuid NOT NULL,
  policy_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_label varchar(150) NOT NULL DEFAULT '',

  -- 建议补货量。数量列允许 NULL 而不是拿 0 兜底：没登记销量就算不出量，
  -- 0 会被读成"不用补"，那是最贵的错误。
  suggested_quantity numeric(19,6) CHECK(suggested_quantity IS NULL OR suggested_quantity >= 0),

  -- 推算依据，随建议一起留档。suggested_quantity 与这些依据必须同时有值或同时为空。
  daily_average_sales numeric(19,6),
  coverage_days numeric(9,2),
  target_stock_quantity numeric(19,6),
  CHECK(suggested_quantity IS NULL
        OR (daily_average_sales IS NOT NULL AND coverage_days IS NOT NULL AND target_stock_quantity IS NOT NULL)),

  -- 算不出来时的原因（如"该 SKU 尚未登记销量，无法推算可售天数"），前端直接显示。
  unquantified_reason varchar(200) NOT NULL DEFAULT '',

  -- 6.2.1.2 交付后由它回写的采购单号。未交付前恒为空 —— /capabilities 会报出来。
  external_purchase_reference varchar(64),

  status varchar(16) NOT NULL DEFAULT 'PROPOSED'
    CHECK(status IN ('PROPOSED','ACKNOWLEDGED','DISMISSED')),
  dismissed_at timestamptz,
  dismissed_by_membership_id uuid,
  dismissal_reason varchar(200),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  FOREIGN KEY(tenant_id,alert_id) REFERENCES safety_stock_alert(tenant_id,id),
  FOREIGN KEY(tenant_id,policy_id) REFERENCES safety_stock_policy(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  -- 一条预警最多一条建议：重复评估同一档预警不应产生第二条建议，
  -- 由 (alert_id) 的唯一约束挡住而不是靠应用层"先查再插"。
  UNIQUE(tenant_id,alert_id),

  -- 忽略必须留痕：被忽略的建议如果不写理由，下次评估还会再生成同一条，
  -- 运营会看到一条永远消不掉的幽灵建议。
  CHECK(status<>'DISMISSED' OR (dismissed_at IS NOT NULL AND btrim(dismissal_reason) <> ''))
);

CREATE INDEX safety_stock_suggestion_status
  ON safety_stock_replenishment_suggestion(tenant_id,status,created_at DESC);
CREATE INDEX safety_stock_suggestion_warehouse
  ON safety_stock_replenishment_suggestion(tenant_id,warehouse_id,created_at DESC);
CREATE INDEX safety_stock_suggestion_sku
  ON safety_stock_replenishment_suggestion(tenant_id,sku_id,created_at DESC);
-- 等 6.2.1.2 交付后回写单号时按 external_purchase_reference 回查。
CREATE INDEX safety_stock_suggestion_external
  ON safety_stock_replenishment_suggestion(tenant_id,external_purchase_reference)
  WHERE external_purchase_reference IS NOT NULL;

-- ===========================================================================
-- 7. DB 级行为约束（三道 + 两道 append-only）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 7.1 阈值内容写定后不许改，只允许把 effective_to 从 NULL 收到一个值。
--
-- 为什么不放在应用层：应用层"不写 UPDATE"不是保证，拿到连接就能直接改。
-- 改阈值 = 开新档，这条不变式把「改阈值」这件事变成数据库不变量：安全库存阈值配错
-- 的代价是断货或积压，而能悄悄覆盖一条正在生效的阈值，等于让预警等级形同虚设。
-- 与 V56 的 guard_sellable_policy_immutable 同一套写法（逐列比对防止绕开特判）。
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_safety_stock_policy_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.effective_to IS NULL THEN
    RAISE EXCEPTION '阈值内容写定后不可修改，effective_to 只能从空收到一个时刻（换版或作废）：'
      '改阈值请开新的一档，否则历史那一档的阈值会被悄悄改掉，而它可能正是当时真正生效的那道防线'
      USING ERRCODE='23514';
  END IF;
  IF OLD.effective_to IS NOT NULL THEN
    RAISE EXCEPTION '这一档的生效区间已经被收口（effective_to=%），不允许再次修改或复活', OLD.effective_to
      USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to <= OLD.effective_from THEN
    RAISE EXCEPTION '收口时刻必须晚于该档的生效起点 %，否则这一档从未真正生效过', OLD.effective_from
      USING ERRCODE='23514';
  END IF;
  IF (NEW.scope_type,NEW.category_id,NEW.sku_id,NEW.sku_label,
      NEW.threshold_quantity,NEW.alert_severity,NEW.reorder_coverage_days,
      NEW.auto_suggest_replenishment,NEW.effective_from,NEW.note,NEW.created_by_membership_id)
     IS DISTINCT FROM
     (OLD.scope_type,OLD.category_id,OLD.sku_id,OLD.sku_label,
      OLD.threshold_quantity,OLD.alert_severity,OLD.reorder_coverage_days,
      OLD.auto_suggest_replenishment,OLD.effective_from,OLD.note,OLD.created_by_membership_id) THEN
    RAISE EXCEPTION '阈值内容（范围/阈值量/预警级别/覆盖天数/是否自动建议）写定后不可修改，请开新的一档'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER safety_stock_policy_immutable
  BEFORE UPDATE ON safety_stock_policy
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_policy_immutable();

-- ---------------------------------------------------------------------------
-- 7.2 同一 (范围 × 仓) 槽位的生效区间不得重叠。
--
-- 「至多一档 open」由 §7.5 的延迟约束挡住，但**关掉的两档之间**仍可能重叠 ——
-- 而重叠的区间在解析时没有唯一答案：那个时刻到底听谁的？
--
-- **为什么重叠判定必须带上仓**：同一个 SKU 对自有仓与 FBA 各配一档是本模块的正常配置
-- （§1b），两档的区间可以都开到至今而**不算冲突** —— 它们管的是两个不同的池子。
-- 只按 (范围 × 范围键) 判重叠会把这两档也拒掉，模块最核心的能力直接配不出来。
--
-- 本触发器是**延迟**的（即时触发看不到挂仓：插入这一档时它自己的挂仓行还不存在，
-- 「是否与对方管同一个仓」无从回答）。延迟到提交时挂仓已就位，才问得出真正要问的那件事。
-- 两张表上各挂一条（阈值表覆盖 retire/换版的 UPDATE，挂仓表覆盖分批挂仓），合起来才是完整的。
--
-- 三种范围的比较键不同，所以按 scope_type 分别比；reference 键用 coalesce 到全零 uuid
-- 才能让同一键的多行互相比较（GiST/等值比较里 NULL 与 NULL 不相等，V56 同一理由）。
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- 7.2 同一 (范围 × 仓) 槽位的生效区间不得重叠。
--
-- 「至多一档 open」由 §7.5 的延迟约束挡住，但**关掉的两档之间**仍可能重叠 ——
-- 而重叠的区间在解析时没有唯一答案：那个时刻到底听谁的？
--
-- **为什么重叠判定必须带上仓**：同一个 SKU 对自有仓与 FBA 各配一档是本模块的正常配置
-- （§1b），两档的区间可以都开到至今而**不算冲突** —— 它们管的是两个不同的池子。
-- 只按 (范围 × 范围键) 判重叠会把这两档也拒掉，模块最核心的能力直接配不出来。
--
-- 本触发器是**延迟**的（即时触发看不到挂仓：插入这一档时它自己的挂仓行还不存在，
-- 「是否与对方管同一个仓」无从回答）。延迟到提交时挂仓已就位，才问得出真正要问的那件事。
--
-- 本触发器**只挂在阈值表上**：它比的是「这一档的时间区间」与「那一档的时间区间」，
-- 用到的全是 safety_stock_policy 自己的列。挂到挂仓表上会让 NEW 变成挂仓行的类型
-- （没有 scope_type / effective_from），报 "record new has no field ..."。
-- 挂仓那一侧由 §7.5 的 safety_stock_policy_warehouse_slot 覆盖。
--
-- 三种范围的比较键不同，所以按 scope_type 分别比；reference 键用 coalesce 到全零 uuid
-- 才能让同一键的多行互相比较（GiST/等值比较里 NULL 与 NULL 不相等，V56 同一理由）。
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_safety_stock_policy_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE other_id uuid; other_from timestamptz; other_to timestamptz;
BEGIN
  SELECT p.id, p.effective_from, p.effective_to INTO other_id, other_from, other_to
    FROM safety_stock_policy p
   WHERE p.tenant_id = NEW.tenant_id
     AND p.scope_type = NEW.scope_type
     AND CASE NEW.scope_type
           WHEN 'SKU'      THEN p.sku_id = NEW.sku_id
           WHEN 'CATEGORY' THEN p.category_id = NEW.category_id
           ELSE true   -- DEFAULT 档按仓判（§1b：同一 SKU 的自有仓档与 FBA 档可以并存）
         END
     AND p.id <> NEW.id
     -- 只与**挂着同一个仓**的那一档比：管的不是同一个池子就没有冲突。
     -- 延迟到提交时执行，所以新插入的这一档此刻已经有挂仓行了。
     AND EXISTS (SELECT 1
                   FROM safety_stock_policy_warehouse a
                   JOIN safety_stock_policy_warehouse b
                     ON b.tenant_id = a.tenant_id AND b.warehouse_id = a.warehouse_id
                  WHERE a.tenant_id = NEW.tenant_id
                    AND a.policy_id = p.id
                    AND b.policy_id = NEW.id)
     AND tstzrange(p.effective_from, p.effective_to, '[)')
       && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
   ORDER BY p.effective_from
   LIMIT 1;
  IF other_id IS NOT NULL THEN
    RAISE EXCEPTION '该阈值在 % ~ % 区间与另一档重叠（id=%）：同一范围、同一仓库、同一时刻只能有一个生效值，'
      '否则评估时那个 (仓, SKU) 没有唯一答案', NEW.effective_from,
      coalesce(NEW.effective_to::text,'至今'), other_id
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER safety_stock_policy_interval
  AFTER INSERT OR UPDATE ON safety_stock_policy
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_policy_interval();

-- ---------------------------------------------------------------------------
-- 7.3 预警状态只能单向推进，且认领/处置时间不得倒流。
--
-- 预警是事实：读数快照（available / threshold / shortfall / 日均销量 / 可售天数）
-- 一个字节都不许改 —— 能悄悄改掉"当时为什么报警"的模块，复盘时就只剩一个数字。
-- 唯一允许的改动是状态与对应的操作留痕。开放 → 已认领 → 已处置是唯一的推进方向，
-- 处置完的预警不允许"复活"，否则工作台会反复把已闭环的事推回待办。
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_safety_stock_alert_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.sku_label,NEW.available_quantity,NEW.threshold_quantity,NEW.shortfall_quantity,
      NEW.daily_average_sales,NEW.days_of_cover,NEW.alert_type,NEW.severity,
      NEW.detected_at,NEW.evaluation_id,NEW.policy_id,NEW.warehouse_id,NEW.sku_id,
      NEW.policy_scope_type,NEW.effective_from,NEW.review_note)
     IS DISTINCT FROM
     (OLD.sku_label,OLD.available_quantity,OLD.threshold_quantity,OLD.shortfall_quantity,
      OLD.daily_average_sales,OLD.days_of_cover,OLD.alert_type,OLD.severity,
      OLD.detected_at,OLD.evaluation_id,OLD.policy_id,OLD.warehouse_id,OLD.sku_id,
      OLD.policy_scope_type,OLD.effective_from,OLD.review_note) THEN
    RAISE EXCEPTION '预警读数快照不可修改：它记录的是检测那一刻的事实，改掉之后就答不出'
      '"当时为什么报警"，而那正是复盘要看的东西（要重算请重新评估，会生成新的预警）'
      USING ERRCODE='23514';
  END IF;
  IF OLD.status = 'OPEN' AND NEW.status <> 'ACKNOWLEDGED' THEN
    RAISE EXCEPTION '预警状态只能 OPEN → ACKNOWLEDGED → RESOLVED 单向推进，不允许从 OPEN 直接变成 %',
      NEW.status USING ERRCODE='23514';
  END IF;
  IF OLD.status = 'ACKNOWLEDGED' AND NEW.status <> 'RESOLVED' THEN
    RAISE EXCEPTION '预警状态只能 OPEN → ACKNOWLEDGED → RESOLVED 单向推进，'
      '已认领的预警只能处置完成，不能退回待办（否则工作台会反复把已闭环的事推回来）'
      USING ERRCODE='23514';
  END IF;
  IF OLD.status = NEW.status THEN
    RETURN NEW;   -- 同状态不动（幂等重放），读数快照上面已经逐列比对过了
  END IF;
  IF NEW.acknowledged_at IS NOT NULL AND OLD.acknowledged_at IS NOT NULL
     AND NEW.acknowledged_at < OLD.acknowledged_at THEN
    RAISE EXCEPTION '认领时间不允许被改早（已有 %）', OLD.acknowledged_at USING ERRCODE='23514';
  END IF;
  IF NEW.resolved_at IS NOT NULL AND NEW.resolved_at < NEW.detected_at THEN
    RAISE EXCEPTION '处置时间 % 早于预警检测时刻 %', NEW.resolved_at, NEW.detected_at
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER safety_stock_alert_status
  BEFORE UPDATE ON safety_stock_alert
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_alert_status();

-- 评估批次与销量登记是 append-only 事实：没有"修改一次评估"这个业务动作。
CREATE FUNCTION guard_safety_stock_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是 append-only 事实，没有修改它的业务动作（如需重算请重新评估）', TG_TABLE_NAME
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER safety_stock_evaluation_append_only
  BEFORE UPDATE OR DELETE ON safety_stock_evaluation
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_append_only();

-- 补货建议：数量与推算依据不可改（改了建议就不再是"当时建议的那个量"），
-- 状态只能 PROPOSED → ACKNOWLEDGED/DISMISSED，且 external_purchase_reference
-- 只允许从空变成有值（6.2.1.2 回写单号），不允许被清空或改写。
CREATE FUNCTION guard_safety_stock_suggestion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.sku_label,NEW.suggested_quantity,NEW.daily_average_sales,NEW.coverage_days,
      NEW.target_stock_quantity,NEW.alert_id,NEW.policy_id,NEW.warehouse_id,NEW.sku_id,
      NEW.unquantified_reason,NEW.created_at)
     IS DISTINCT FROM
     (OLD.sku_label,OLD.suggested_quantity,OLD.daily_average_sales,OLD.coverage_days,
      OLD.target_stock_quantity,OLD.alert_id,OLD.policy_id,OLD.warehouse_id,OLD.sku_id,
      OLD.unquantified_reason,OLD.created_at) THEN
    RAISE EXCEPTION '补货建议的数量与推算依据不可修改：它记录的是生成那一刻的算法输出'
      USING ERRCODE='23514';
  END IF;
  IF OLD.external_purchase_reference IS NOT NULL
     AND NEW.external_purchase_reference IS DISTINCT FROM OLD.external_purchase_reference THEN
    RAISE EXCEPTION '采购单号已回写（%），不允许改写或清空', OLD.external_purchase_reference
      USING ERRCODE='23514';
  END IF;
  IF OLD.status <> NEW.status
     AND NOT (OLD.status = 'PROPOSED'
              AND NEW.status IN ('ACKNOWLEDGED','DISMISSED')) THEN
    RAISE EXCEPTION '补货建议状态只能 PROPOSED → ACKNOWLEDGED/DISMISSED 单向推进（当前 % → %）',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER safety_stock_suggestion_guard
  BEFORE UPDATE ON safety_stock_replenishment_suggestion
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_suggestion();

-- ---------------------------------------------------------------------------
-- 7.4 【延迟约束】一档阈值挂的仓必须同 kind（FBA 与自有仓不能混在一档里求和）。
--
-- 为什么用 CONSTRAINT TRIGGER DEFERRABLE：挂仓是逐行 INSERT 的，逐行触发时前一行还没
-- 进来，看不到本档的其它仓。延迟到提交时统一检查，才能问出"这一档挂的仓 kind 是否一致"。
--
-- 这就是蓝图那句「安全库存要区分平台断货风险和自有仓耗尽」的落点：
-- 混挂会让"还剩 30 件"这个数同时掩盖掉自有仓已归零与 FBA 早断货两种完全不同的事实。
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_safety_stock_policy_single_kind() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE kinds text; n bigint;
BEGIN
  SELECT count(DISTINCT warehouse_kind), string_agg(DISTINCT warehouse_kind, ',')
    INTO n, kinds
    FROM safety_stock_policy_warehouse
   WHERE tenant_id = NEW.tenant_id AND policy_id = NEW.policy_id;
  IF n > 1 THEN
    RAISE EXCEPTION '同一档阈值不能同时挂自有仓与平台仓（读到 %）：FBA 与自有仓是两个独立的池子，'
      '混在一档里求和会得到一个「还剩 % 件」而实际上其中一个仓早已归零的数，'
      '那样就分不出「平台断货风险」与「自有仓耗尽」——请拆成两档', kinds, n
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER safety_stock_policy_single_kind
  AFTER INSERT OR UPDATE ON safety_stock_policy_warehouse
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_policy_single_kind();

-- ---------------------------------------------------------------------------
-- 7.5 【延迟约束】同一 (租户 × 范围 × 范围键 × 仓) 至多一档仍在生效。
--
-- 为什么不用部分唯一索引：阈值键在 safety_stock_policy、仓在挂仓表，PG 的部分唯一索引
-- 跨不了两张表（见 §1b 的理由 —— 槽位必须含仓，否则同一个 SKU 无法同时给自有仓与 FBA 各配一档）。
--
-- 为什么用延迟约束触发器而不是即时触发器：换版是「先插新档、再收口旧档」，顺序固定；
-- 即时触发会在插新档的瞬间看到旧档还开着而误报，必须延迟到提交时统一裁决。
--
-- 这道约束挡的是「同一个 SKU 在同一个仓上同时有两档在效」：评估时那个 (仓, SKU) 没有唯一答案，
-- 报出来的预警依据连「当时到底按哪档判的」都答不上。
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_safety_stock_policy_slot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  me       record;      -- 本档的 (id, scope_type, category_id, sku_id, effective_to)
  link     record;      -- 本档挂的每一个仓
  conflict record;      -- 在同一个仓上仍然开着的另一档
  target   uuid;        -- 本次触发的那一档 id
BEGIN
  -- 归约：无论触发源是阈值表还是挂仓表，本函数都只看「本档 × 它挂的每个仓」。
  -- **必须用 PL/pgSQL 分支而不是写进 SQL**：NEW 是按触发表定型的记录类型，
  -- 在 safety_stock_policy 上它没有 policy_id 字段，把 `CASE WHEN TG_TABLE_NAME=...`
  -- 写进 SQL 表达式会让两个分支都被定型，运行时报 "record new has no field policy_id"。
  IF TG_TABLE_NAME = 'safety_stock_policy' THEN
    target := NEW.id;
  ELSE
    target := NEW.policy_id;
  END IF;

  SELECT p.id, p.scope_type, p.category_id, p.sku_id, p.effective_to INTO me
    FROM safety_stock_policy p
   WHERE p.tenant_id = NEW.tenant_id AND p.id = target;
  IF NOT FOUND THEN
    RETURN NULL;   -- 本档已被删掉（级联删挂仓），无事可判
  END IF;
  -- **已经收口（换版/作废）的那一档不再参与裁决**：换版的标准动作是「收口旧档 + 开新档」，
  -- 提交时新档已 open、旧档已 closed。若这里不跳过旧档，它会把同仓库的新档当成冲突，
  -- 于是**每一次换版都会被自己的旧档挡下**。
  IF me.effective_to IS NOT NULL THEN
    RETURN NULL;
  END IF;

  -- 挂仓可能晚于本行插入（服务是「先插档再挂仓」），所以每次现查而不是读 NEW 上的快照。
  FOR link IN
    SELECT lw.warehouse_id
      FROM safety_stock_policy_warehouse lw
     WHERE lw.tenant_id = NEW.tenant_id AND lw.policy_id = me.id
  LOOP
    SELECT p.id, p.threshold_quantity INTO conflict
      FROM safety_stock_policy p
      JOIN safety_stock_policy_warehouse lw2
        ON lw2.tenant_id = p.tenant_id AND lw2.policy_id = p.id
     WHERE p.tenant_id = NEW.tenant_id
       AND lw2.warehouse_id = link.warehouse_id
       AND p.effective_to IS NULL
       AND p.id <> me.id
       AND p.scope_type = me.scope_type
       AND CASE me.scope_type
             WHEN 'SKU'      THEN p.sku_id = me.sku_id
             WHEN 'CATEGORY' THEN p.category_id = me.category_id
             ELSE true
           END
     LIMIT 1;
    IF FOUND THEN
      RAISE EXCEPTION '同一范围 × 同一个仓（id=%）上已经有另一档阈值仍在生效（冲突档 id=%，阈值 %）：'
        '同一时刻只能有一个生效值，否则评估时那个 (仓, SKU) 没有唯一答案，'
        '报出来的预警连「当时按哪档判的」都答不上。改这一档请走换版（收口旧档 + 开新档）',
        link.warehouse_id, conflict.id, conflict.threshold_quantity
        USING ERRCODE='23514';
    END IF;
  END LOOP;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER safety_stock_policy_slot
  AFTER INSERT OR UPDATE ON safety_stock_policy
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_policy_slot();

CREATE CONSTRAINT TRIGGER safety_stock_policy_warehouse_slot
  AFTER INSERT OR UPDATE ON safety_stock_policy_warehouse
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_safety_stock_policy_slot();

-- ===========================================================================
-- 8. 多租户与授权（与 V1/V2/V54/V56 同一套写法）
-- ===========================================================================
-- 五张表都是租户业务表，一视同仁 FORCE。current_setting('app.current_tenant') 由
-- backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['safety_stock_policy','safety_stock_policy_warehouse','safety_stock_sales',
                             'safety_stock_evaluation','safety_stock_alert',
                             'safety_stock_replenishment_suggestion'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON safety_stock_policy, safety_stock_policy_warehouse,
  safety_stock_sales, safety_stock_evaluation, safety_stock_alert,
  safety_stock_replenishment_suggestion TO warehouse_app;

-- 阈值分档不删除：作废走 retire（关区间），档位行永久保留 —— 它是"当时那道预警依据长什么样"
-- 的唯一凭据。权限层先收一道，7.1 的触发器再收一道。
REVOKE DELETE ON safety_stock_policy FROM warehouse_app;
-- 销量登记与评估批次是 append-only 事实：没有"改一次登记 / 改一次评估"这个业务动作。
REVOKE UPDATE, DELETE ON safety_stock_sales FROM warehouse_app;
REVOKE UPDATE, DELETE ON safety_stock_evaluation FROM warehouse_app;
-- 预警的读数快照与建议的推算依据不可改（见 7.3 / 7.4 的触发器），权限层先收一道。
REVOKE DELETE ON safety_stock_alert FROM warehouse_app;
REVOKE DELETE ON safety_stock_replenishment_suggestion FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54/V56 同口径）。
REVOKE ALL ON safety_stock_policy, safety_stock_policy_warehouse, safety_stock_sales,
  safety_stock_evaluation, safety_stock_alert, safety_stock_replenishment_suggestion FROM starcon_app;
