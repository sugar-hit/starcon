-- ===========================================================================
-- 5.5.2.1 异常告警（catalog 服务 · 05 定价与价目）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/05-pricing.md）：
--   ### 5.5.2.1 异常告警
--   - 职责：监测价格异常：平台价与内部价长期不一致、售价低于成本线、平台自动降价、
--           多站点价格倒挂（同商品不同站点价差导致窜货）。
--   - 输入：平台价回流；内部价；成本线；站点价差规则。
--   - 输出：异常清单 + 预警通知。
--   - 关系：上游 = 4.3.2.2、5.2.1.1；下游 = 1 工作台预警、16 合规（窜货风控）。
--           **站点价差倒挂是跨境特有的风险**：德国站定价低于美国站会引发跨区窜货与合规问题。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 本模块的四类异常、四份事实、以及它们各自的上游真实状态
-- ---------------------------------------------------------------------------
-- 蓝图把四类异常写在同一句里，但它们的**数据来源完全不同**，混在一起做会得出
-- 一个「看起来都有、其实全靠猜」的模块。本迁移先把四类的来源钉死：
--
--   | 异常类型                       | 判定需要什么                     | 本仓真实状态                                        |
--   |--------------------------------|----------------------------------|-----------------------------------------------------|
--   | ① 平台价与内部价**长期**不一致 | 同一站点上的一串价格观测         | 🔴 平台价与内部价**都没有自动来源**（见第一节）        |
--   | ② 售价低于成本线               | 售价 + 成本线                    | ✅ 成本线 = 5.2.1.1（catalog 同服务，V215）           |
--   | ③ 平台自动降价                 | 同一站点上相邻两次价格观测       | 🔴 需知道「这次降价是平台降的还是我们改的」            |
--   | ④ 多站点价格倒挂               | **同一 SKU 的多个站点**的价格    | ✅ 站点维度进表（platform_code + site_code）          |
--
-- 一、🔴 ①与③的输入：价格观测台账（本表自持）
-- ---------------------------------------------------------------------------
-- 蓝图输入句是「平台价回流；内部价」。实测集成头：
--
--   * **平台价**（我们自己那条 listing 在平台上的售价）：**全仓没有这个事实**。
--     channel 的 `listing` 表（V171）自己在表注释里写着「**无价格列：售价归 05 定价**」，
--     而 05 定价整卷在本模块之前只交付了 5.2.1.1 成本构成 —— 也就是说
--     **售价这件事在本仓此前根本没有承载表**。
--   * **内部价**（我方价目表里的价）：5.1.1.1 价目表列表 / 5.1.1.2 价目项批量维护**未交付**，
--     `price_list` / `price_list_item` 两张表在本仓**不存在**。
--
-- ⇒ 照仓库先例（5.2.1.1 对「06 采购单未交付」的处理：改用同库真实到货事实派生，
--   并在能力声明里写明口径），本模块**自持一张价格观测台账**
--   `price_anomaly_observation`：一行 = 一个 SKU 在一个站点上的一次价格观测，
--   记下**内部价**、**平台价**与**这次价格是谁动的**。
--
-- 这张表**不是**「当前值表」：它是**时间序列**（一个 (sku, 站点) 可以有很多行），
-- 因为蓝图第①类异常要判定的是「**长期**不一致」——
-- 没有时间序列就只剩「一次就对不上」，而那恰恰是蓝图要求**不要**报的那种。
--
-- 🔴 与 5.5.1.1 价格历史（**未交付**）的边界：5.5.1.1 交付后是「价格变更历史」
--   （旧价 / 新价 / 操作人 / 原因），本表是**异常检测用的观测点**（站点维度 + 平台价 vs 内部价 + 变动来源）。
--   两者**不合并**：历史讲「改过什么」，观测讲「现在每个站点各是多少、是谁动的」。
--   5.5.1.1 落地后应经**授权 API** 供数，本表结构不必变。
--
-- 二、🔴 ②的成本线：直接读 5.2.1.1 的成本事实，不拍脑袋
-- ---------------------------------------------------------------------------
-- 成本线 = `sku_cost_profile.current_revision_id` → `sku_cost_revision.total_unit_cost`
-- （V215，**同一服务、同一口径**）。选它而不是「随手取一个数字」有三个理由：
--   1. 它是 5.2.1.1 真实写下的成本快照，不是本模块另起炉灶的一条成本；
--   2. 它带 `snapshot_complete` —— 成本分项读不到时它是**已知部分合计**，
--      本模块据此把事件标成「成本口径不完整」，而不是拿一个偏低的成本去断言「亏损」；
--   3. 它带 `effective_from` —— 事件上写明成本快照的时刻，
--      事后能回答「当时按哪一版成本判的」。
-- 🔴 本模块**不改** V215 的任何表、任何列、任何 CHECK（零 ALTER 既有表）。
--
-- 三、🔴 ④的站点维度：站点两两比对，不是「全平台平均价差」
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「德国站定价低于美国站会引发跨区窜货」是硬约束。
-- 「德国站比美国站便宜」是**两个站点之间**的判断，不是「相对某个平均值」的判断 ——
-- 平均价差会把「德国站比美国站便宜、但比法国站贵」这种**真实倒挂**洗掉。
-- ⇒ 站点维度（`platform_code` + `site_code`）**进表、进索引、进 SQL 的自连接**，
--   事件上同时存**低的那一边**与**高的那一边**（`site_code` / `related_site_code`），
--   所以「谁比谁便宜」在清单上一眼可读，不需要回放计算。
--
-- 四、🔴 跨币种站点之间怎么比：显式的比价口径，**绝不编汇率**
-- ---------------------------------------------------------------------------
-- 德国站是 EUR、美国站是 USD —— **没有汇率就没有可比的价格**。
-- 本仓的汇率表属 5.2.2.1 汇率查询 / 5.2.2.2 汇率快照（**均未交付**），
-- 且 V215 第九节 R6 已把纪律钉死：「跨币种绝不相加」「**绝不编一个汇率**」。
--
-- ⇒ 本模块的处理是**显式三段式**：
--   * 观测行自带 `comparison_currency` + `fx_rate` + `fx_rate_date` + `fx_rate_source`；
--     给了汇率 ⇒ 该观测可与同 `comparison_currency` 的其它站点比价；
--     **汇率必须连同日期与来源一起记在观测行上**（蓝图「汇率必须记录在单据上」的同一纪律）；
--   * 没给汇率 ⇒ 该观测**只能**与同币种的站点比；
--   * 两种币种之间**不比**，并在响应里如实报出「不可比的站点对 + 原因 + ownerModule」，
--     **不静默丢弃**，也**不编一个汇率**把 EUR 乘 1.1 当美元。
--
-- 五、🔴 下游缺口按仓库先例显式声明、不读写不存在的服务
-- ---------------------------------------------------------------------------
--   * **1 工作台预警（1.3.1.1 通知列表 / 1.3.2.1 预警规则）当前未交付**
--     ⇒ 预警通知只落**本服务内**的通知事实 `price_anomaly_notification`，
--     `owner_module` 恒为 '1.3.1.1'，`delivered` 恒为 false ——
--     「标记为已送达」这件事在本模块里**既没有代码路径也没有 SQL 权限**。
--   * **16 合规（窜货风控）**：16.2.2.1 合规预检已交付（在 channel），
--     但**没有「跨区窜货」这个判定模块**（16.4.2.x 是品牌授权、16.3.x 是税务）。
--     ⇒ 本模块**不跨服务写**任何东西，只在事件上留 `compliance_hint` 证据，
--     真正的窜货风控待 16 落地后经**授权 API / event** 衔接。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 外键纪律：本迁移**零个外键**指向既有表
-- ---------------------------------------------------------------------------
-- 上一批（batch-37）有车道在迁移里给既有表建了 FOREIGN KEY，
-- 结果打破了同服务既有测试的 `TRUNCATE`（PG：被 TRUNCATE 的表若被别的表外键引用，
-- 且引用方不在同一条 TRUNCATE 语句里，就直接拒绝）——
-- 于是那些测试在清理阶段整体报错，L2 直接红。
--
-- ⇒ 本迁移**不建任何 FOREIGN KEY**：对 `sku` 的引用校验用**触发器**做
--   （`guard_price_anomaly_observation_sku` / `guard_price_anomaly_event_sku`，
--   与同批 channel `V219__return_shipback_to_origin.sql` 同一手法），
--   对本模块自己四张表之间的引用**也不建外键**（事件 → 通知用触发器校验）。
--   这样本迁移对既有测试的 `TRUNCATE` **零回归面**。
--
-- 七、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
--   价格 / 成本线 / 价差金额   numeric(19,4)
--   比例阈值 / 价差比例        numeric(9,6)
--   汇率                      numeric(19,8)
--   时间（观测时刻 / 事件时刻）timestamptz
--   币种                      varchar(3)
-- 无 float / double 一列。金额一律 BigDecimal。**禁浮点记账。**
--
-- ===========================================================================
-- 第一节表：price_anomaly_observation · 价格观测台账（事实，只追加）
-- ===========================================================================
CREATE TABLE price_anomaly_observation (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 被观测的 SKU。**不建外键**（见第六节）：用触发器校验同租户下真实存在。
  sku_id uuid NOT NULL,

  -- ===== 站点维度（④ 多站点倒挂的骨架）=====
  -- 平台码与站点码沿用 5.2.1.1/3.6 体系里 `platform_category_mapping`
  -- （V145）的同一套约定：**字符串码 + 不建码表副本**
  -- （channel 的 platform / marketplace 是全局码表，catalog 不复制它）。
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,

  -- 计价币种：**站点自身**的币种（德国站 EUR / 美国站 USD）。
  currency varchar(3) NOT NULL,

  -- 内部价（我方价目表里的价）：**可空 = 尚未知**（不是 0）。
  internal_price numeric(19,4),
  -- 平台价（平台上实际在售的价）：**可空 = 尚未知**。
  platform_price numeric(19,4),

  -- 🔴 这次价格是**谁动的**（③ 平台自动降价的判定依据）：
  --   AUTOMATIC_PLATFORM 平台自动降的（平台侧促销 / 自动比价规则）
  --   MANUAL_INTERNAL     我们自己改的（内部调价）
  --   PLATFORM_FEEDBACK   平台回流带来的价，来源未进一步区分
  --   UNKNOWN             不知道（登记时没标）
  price_change_source text NOT NULL DEFAULT 'UNKNOWN'
    CHECK(price_change_source IN ('AUTOMATIC_PLATFORM','MANUAL_INTERNAL','PLATFORM_FEEDBACK','UNKNOWN')),

  -- 这条观测**从哪来**（与「谁动的」是两个维度：来源是通道，变动方是责任方）：
  --   PLATFORM_FEEDBACK   平台价回流
  --   PRICE_LIST          内部价目（5.1 未交付 ⇒ 现阶段只能由调用方带价登记）
  --   MANUAL_ATTESTATION  人工登记 / 补录
  source text NOT NULL
    CHECK(source IN ('PLATFORM_FEEDBACK','PRICE_LIST','MANUAL_ATTESTATION')),

  -- ===== 比价口径（跨币种站点之间怎么比，见第四节）=====
  -- 折算到的比价币种；**可空 = 不折算，只与同币种站点比**。
  comparison_currency varchar(3),
  -- 汇率。**必须与 comparison_currency 同时给或同时不给**（CHECK 钉死）。
  fx_rate numeric(19,8),
  -- 用的是哪天的汇率、哪来的汇率（蓝图：「汇率必须记录在单据上」）。
  fx_rate_date date,
  fx_rate_source varchar(64) NOT NULL DEFAULT '',

  -- 观测时刻：这一行说的是「**这一刻**这个站点是这个价」。
  observed_at timestamptz NOT NULL,

  note text NOT NULL DEFAULT '',

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一 SKU、同一站点、同一时刻**只允许一条**观测 ⇒ 重复登记会撞唯一键而不是悄悄多一行。
  UNIQUE(tenant_id, sku_id, platform_code, site_code, observed_at),

  -- 至少要给一个价：两个都没有的观测没有任何信息量。
  CHECK(internal_price IS NOT NULL OR platform_price IS NOT NULL),
  -- 价格不为负（0 是「免费」，是一个**事实**，不是「没维护」；没维护是可空）。
  CHECK(internal_price IS NULL OR internal_price >= 0),
  CHECK(platform_price IS NULL OR platform_price >= 0),
  -- 币种形状：ISO 4217 三位大写码。varchar(3) 而不是 char(3)：char 会补空格。
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(comparison_currency IS NULL OR comparison_currency ~ '^[A-Z]{3}$'),
  -- 🔴 汇率与比价币种**同生共死**：只给其中一个，比价口径就是残缺的，
  -- 而残缺的口径会静默地把站点对判成「不可比」。
  CHECK((fx_rate IS NULL) = (comparison_currency IS NULL)),
  CHECK(fx_rate IS NULL OR fx_rate > 0),
  -- 给了汇率就必须说清是哪天的、哪来的 —— 否则事后无法复算。
  CHECK(fx_rate IS NULL OR (fx_rate_date IS NOT NULL AND btrim(fx_rate_source) <> '')),
  -- 平台码 / 站点码不为空，且形状与 V145 的 site_code 一致。
  CHECK(btrim(platform_code) <> '' AND btrim(site_code) <> ''),
  CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(site_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$')
);
COMMENT ON TABLE price_anomaly_observation IS
  '5.5.2.1 · **价格观测台账**（一行 = 一个 SKU 在一个站点上的一次价格观测，含内部价 / 平台价 / 变动来源）。'
  '这是 ①「长期不一致」与 ③「平台自动降价」得以判定的**时间序列**来源 —— '
  '本仓此前**没有**任何表承载售价（channel 的 listing 明确「无价格列：售价归 05 定价」，'
  '5.1 价目表未交付）。只追加，不改历史观测。';

-- 主查询路径：某 SKU 某站点的时间序列（①③ 的判定窗口）。
CREATE INDEX price_anomaly_observation_by_sku_site
  ON price_anomaly_observation(tenant_id, sku_id, platform_code, site_code, observed_at);
-- 「最近观测到的价格」—— ④ 站点两两比对取每个站点的最新一条。
CREATE INDEX price_anomaly_observation_by_site
  ON price_anomaly_observation(tenant_id, platform_code, site_code, observed_at DESC);
-- 台账列表的默认排序（最近观测在前）。
CREATE INDEX price_anomaly_observation_by_created
  ON price_anomaly_observation(tenant_id, created_at DESC);

-- ===========================================================================
-- 第二节表：price_anomaly_rule · 判定规则（站点价差规则 / 阈值）
-- ===========================================================================
-- 蓝图输入句第四个输入是「**站点价差规则**」。规则是**配置**，不是结论 ——
-- 阈值必须由人定（本仓惯例：阈值是配置项，判定结果才是事实）。
--
-- 🔴 一条规则覆盖**一种**异常类型（UNIQUE(tenant_id, anomaly_type)）：
--   四种异常的阈值口径互不相同（比例 / 天数 / 是否要站点对），
--   混成一条规则就会出现「改一个阈值顺带改了另一种异常的判定」这种查不出来的坑。
--
-- 🔴 **可空 = 用内置默认值**：内置默认值写在服务层（配置默认值，不是数据）。
--   显式给 0 是合法的（0 是一个真实阈值：任何价差都算异常），与「不给」是两件事。
CREATE TABLE price_anomaly_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  anomaly_type text NOT NULL
    CHECK(anomaly_type IN ('PLATFORM_INTERNAL_DIVERGENCE','BELOW_COST','PLATFORM_AUTO_DISCOUNT','CROSS_SITE_INVERSION')),
  name varchar(100) NOT NULL,

  enabled boolean NOT NULL DEFAULT true,

  -- 价差比例阈值（相对价，例如 0.05 = 差 5%）。**可空 = 用内置默认值**。
  ratio_threshold numeric(9,6),
  -- 「持续多久」的门槛（天）。**可空 = 用内置默认值**。
  -- 对 ① 平台价与内部价不一致，这是「长期」二字的落点。
  min_duration_days int,

  severity text NOT NULL DEFAULT 'MEDIUM'
    CHECK(severity IN ('HIGH','MEDIUM','LOW')),

  note text NOT NULL DEFAULT '',

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一种异常类型一条规则。
  UNIQUE(tenant_id, anomaly_type),
  CHECK(btrim(name) <> ''),
  -- 比例 ∈ [0,1)：1.0 意味着「比另一个价低 100% 才算异常」= 永远不命中。
  CHECK(ratio_threshold IS NULL OR (ratio_threshold >= 0 AND ratio_threshold < 1)),
  CHECK(min_duration_days IS NULL OR min_duration_days >= 0)
);
COMMENT ON TABLE price_anomaly_rule IS
  '5.5.2.1 · **判定规则**（蓝图输入「站点价差规则」）。阈值是配置、不是结论。'
  '每种异常类型一条；可空的阈值列 = 用服务层内置默认值，显式给 0 是合法的。';

-- ===========================================================================
-- 第三节表：price_anomaly_event · 异常事件（可统计的事实）
-- ===========================================================================
-- 🔴 这张表**不是「当前值表」**：异常是一个**有生命期的事实**——
--   first_seen_at（第一次命中）/ last_seen_at（最后一次命中）/ duration_days（持续了几天）
--   / status（OPEN → ACKNOWLEDGED → RESOLVED）。
--   「当前值」只能回答「现在还异常吗」，答不出「这个问题存在多久了」——
--   而 ① 的判定本身就是「持续多久」。
--
-- 🔴 fingerprint 是**幂等键**：同一次检测重复跑不会堆出一堆重复事件，
--   而是更新同一条事件的 last_seen_at / duration_days（事件是状态，不是快照）。
CREATE TABLE price_anomaly_event (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  anomaly_type text NOT NULL
    CHECK(anomaly_type IN ('PLATFORM_INTERNAL_DIVERGENCE','BELOW_COST','PLATFORM_AUTO_DISCOUNT','CROSS_SITE_INVERSION')),

  sku_id uuid NOT NULL,

  -- ===== 站点维度（④ 的骨架：两两比对，所以存**两**边）=====
  platform_code varchar(32) NOT NULL DEFAULT '',
  site_code     varchar(32) NOT NULL DEFAULT '',
  -- ④ 站点倒挂时的**另一边**（贵的那一边）。
  related_platform_code varchar(32) NOT NULL DEFAULT '',
  related_site_code     varchar(32) NOT NULL DEFAULT '',

  -- 计价币种（观测行的币种）。
  currency varchar(3) NOT NULL DEFAULT '',

  -- 判定的主体：PLATFORM_PRICE（平台价）/ INTERNAL_PRICE（内部价）/ ''（不区分）。
  subject_kind text NOT NULL DEFAULT ''
    CHECK(subject_kind IN ('','PLATFORM_PRICE','INTERNAL_PRICE')),

  -- 被判异常的那个价、参照的那个价、差额、差比。
  subject_price   numeric(19,4),
  reference_price numeric(19,4),
  gap_amount      numeric(19,4),
  gap_ratio       numeric(9,6),

  -- ===== ② 低于成本线时带上的成本线（来自 5.2.1.1 的 V215 事实）=====
  cost_line         numeric(19,4),
  cost_currency     varchar(3) NOT NULL DEFAULT '',
  -- 成本快照是否完整（V215 的 snapshot_complete）。
  -- **不完整时成本线只是「已知部分合计」** ⇒ 事件必须标出来，
  -- 否则一个偏低的成本会让「售价低于成本」看起来比实际更严重。
  cost_complete     boolean NOT NULL DEFAULT true,
  cost_snapshot_at  timestamptz,

  -- ===== 生命期 =====
  first_seen_at  timestamptz NOT NULL,
  last_seen_at   timestamptz NOT NULL,
  duration_days  int NOT NULL DEFAULT 0 CHECK(duration_days >= 0),

  severity text NOT NULL CHECK(severity IN ('HIGH','MEDIUM','LOW')),
  status   text NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),

  rule_id uuid,

  -- 幂等键：同一异常的稳定身份。检测重复跑时用它 upsert，不堆重复事件。
  fingerprint varchar(400) NOT NULL,

  -- 判定证据（JSON）：阈值、比对的两边、各次命中时刻等。
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, fingerprint),

  -- 🔴 ④ 站点倒挂**必须**有两个**不同**的站点：只有一个站点谈不上倒挂，
  --   两边相同是自比自，而那会被写成一个看起来很有道理的假倒挂。
  CHECK(anomaly_type <> 'CROSS_SITE_INVERSION'
        OR (btrim(site_code) <> '' AND btrim(related_site_code) <> '' AND site_code <> related_site_code)),
  CHECK(currency = '' OR currency ~ '^[A-Z]{3}$'),
  CHECK(cost_currency = '' OR cost_currency ~ '^[A-Z]{3}$')
);
COMMENT ON TABLE price_anomaly_event IS
  '5.5.2.1 · **异常事件**（可统计的事实：类型 / 站点对 / 价差 / 成本线 / 持续天数 / 状态）。'
  'fingerprint 是幂等键：重复检测更新同一条事件，而不是堆出重复行。';

-- 异常清单主查询：按类型 + 状态筛，按最近命中排序。
CREATE INDEX price_anomaly_event_by_type_status
  ON price_anomaly_event(tenant_id, anomaly_type, status, last_seen_at DESC);
-- 按 SKU 下钻（详情页 / 该 SKU 的全部异常）。
CREATE INDEX price_anomaly_event_by_sku
  ON price_anomaly_event(tenant_id, sku_id, last_seen_at DESC);
-- 「站点倒挂」清单（按站点对检索）。
CREATE INDEX price_anomaly_event_by_site_pair
  ON price_anomaly_event(tenant_id, platform_code, site_code, related_platform_code, related_site_code);
-- 「持续最久的异常」—— ① 的持续天数排序。
CREATE INDEX price_anomaly_event_by_duration
  ON price_anomaly_event(tenant_id, duration_days DESC);

-- ===========================================================================
-- 第四节表：price_anomaly_notification · 预警通知（下游 1 未交付 ⇒ 本服务内事实）
-- ===========================================================================
-- 蓝图输出句是「异常清单 + **预警通知**」。下游 **1 工作台预警未交付**
-- ⇒ 通知只落本服务内的这张表，**不写**任何不存在的服务。
-- 🔴 `owner_module` 恒为 '1.3.1.1'（通知列表）：1 落地后经授权 API / event 取用。
-- 🔴 `delivered` 恒为 false：本模块**没有**投递通道，
--   「已送达」这件事在这里既没有代码路径也没有 SQL 权限 —— 标成 true 就是骗人。
CREATE TABLE price_anomaly_notification (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 指向本模块的异常事件（**不建外键**，见第六节）。
  event_id uuid NOT NULL,

  anomaly_type text NOT NULL,
  severity text NOT NULL CHECK(severity IN ('HIGH','MEDIUM','LOW')),

  title varchar(300) NOT NULL,
  body text NOT NULL,

  channel text NOT NULL DEFAULT 'INTERNAL' CHECK(channel IN ('INTERNAL')),
  recipient_scope varchar(100) NOT NULL DEFAULT 'PRICING_OPERATOR',
  owner_module varchar(32) NOT NULL DEFAULT '1.3.1.1',

  delivered boolean NOT NULL DEFAULT false,
  delivered_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个事件一条通知（重复检测不重复通知）。
  UNIQUE(tenant_id, event_id),
  CHECK(btrim(title) <> '' AND btrim(body) <> ''),
  -- 「已送达」必须带时刻；未送达不许带时刻。
  CHECK((delivered AND delivered_at IS NOT NULL) OR (NOT delivered AND delivered_at IS NULL))
);
COMMENT ON TABLE price_anomaly_notification IS
  '5.5.2.1 · **预警通知**（本服务内事实）。下游 1 工作台预警（1.3.1.1）当前未交付 ⇒ '
  'owner_module 恒为 1.3.1.1、delivered 恒为 false：本模块没有投递通道，标成「已送达」就是骗人。';

CREATE INDEX price_anomaly_notification_by_created
  ON price_anomaly_notification(tenant_id, created_at DESC);

-- ===========================================================================
-- 第五节：触发器 —— 用引用校验代替数据库外键（第六节的纪律）
-- ===========================================================================
-- 🔴 为什么不用 FOREIGN KEY：被 TRUNCATE 的表若被别的表外键引用、
--   且引用方不在同一条 TRUNCATE 语句里，PG 直接拒绝 ——
--   那会打破同服务既有测试的清理阶段（batch-37 踩过，39 例 Errors，L2 直接红）。
--   触发器做同样的校验，但**不参与 TRUNCATE 的引用检查**，回归面 = 0。
CREATE FUNCTION guard_price_anomaly_observation_sku() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  target_id uuid;
BEGIN
  SELECT s.id INTO target_id FROM sku s
   WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.sku_id;
  IF target_id IS NULL THEN
    RAISE EXCEPTION 'Price anomaly observation % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER price_anomaly_observation_sku_guard
  BEFORE INSERT OR UPDATE ON price_anomaly_observation
  FOR EACH ROW EXECUTE FUNCTION guard_price_anomaly_observation_sku();

CREATE FUNCTION guard_price_anomaly_event_sku() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  target_id uuid;
BEGIN
  SELECT s.id INTO target_id FROM sku s
   WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.sku_id;
  IF target_id IS NULL THEN
    RAISE EXCEPTION 'Price anomaly event % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER price_anomaly_event_sku_guard
  BEFORE INSERT OR UPDATE ON price_anomaly_event
  FOR EACH ROW EXECUTE FUNCTION guard_price_anomaly_event_sku();

-- 通知必须挂在**同租户**的一条真实事件上。
CREATE FUNCTION guard_price_anomaly_notification_event() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  target_id uuid;
BEGIN
  SELECT e.id INTO target_id FROM price_anomaly_event e
   WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.event_id;
  IF target_id IS NULL THEN
    RAISE EXCEPTION 'Price anomaly notification % must reference an existing event in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER price_anomaly_notification_event_guard
  BEFORE INSERT OR UPDATE ON price_anomaly_notification
  FOR EACH ROW EXECUTE FUNCTION guard_price_anomaly_notification_event();

-- ===========================================================================
-- 第六节：RLS 与授权
-- ===========================================================================
-- 四张表都是租户级业务表：tenant_id NOT NULL + ENABLE/**FORCE** RLS + isolation 策略。
-- FORCE 不能漏：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的价格观测 ——
-- 而「别人在德国站卖多少钱」正是本模块最敏感的数据。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['price_anomaly_observation','price_anomaly_rule','price_anomaly_event','price_anomaly_notification'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性跟随 SKU：看不到 SKU 就看不到它的价格观测与异常事件
-- （与 V6 的 sku_package、V215 的 sku_cost_profile 同一条纪律）。
-- 这条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['price_anomaly_observation','price_anomaly_event'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- 通知的可见性跟随它的事件（事件跟着 SKU ⇒ 通知也跟着 SKU）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['price_anomaly_notification'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM price_anomaly_event e WHERE e.tenant_id=%I.tenant_id AND e.id=%I.event_id)) WITH CHECK (EXISTS(SELECT 1 FROM price_anomaly_event e WHERE e.tenant_id=%I.tenant_id AND e.id=%I.event_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON price_anomaly_observation TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_anomaly_observation TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_anomaly_rule TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_anomaly_rule TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_anomaly_event TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_anomaly_event TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_anomaly_notification TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_anomaly_notification TO starcon_app;
