-- 9.2.1.3 异常拦截。
--
-- 蓝图 09-order-and-fulfillment.md 9.2.1.3 原文：
--   职责：识别并拦截异常订单：地址不可达/信息不全、黑名单买家、重复下单、异常折扣（低于成本）、
--         疑似欺诈、超出收货国家限制、平台合规风险。
--   输入：地址校验服务；16 合规与风控信号；5 定价（低于成本线）；订单特征。
--   输出：拦截清单 + 拦截原因 + 处置建议（取消/转人工/放行）。
--   关系：上游 = 9.2.1.2；下游 = 16 合规（黑名单）、11 售后（欺诈售后）、2 平台（取消回写）。
--         **拦截不自动取消**，默认转人工——误拦真实订单的代价通常高于人工看一眼。
--
-- 归属：**trade 服务**（蓝图 09 卷 L2 表「9.2 订单审核 → 主责服务 trade」）。
-- 本迁移**只新建表、零 ALTER 既有表** —— sales_order 簇（V99/V100/V101）是 9.1.1.1 车道的
-- 写入所有者，order_review_*（V149）与 order_risk_*（V150）是另外两条车道的写入所有者，
-- 本模块只**读**它们（跨车道 DDL 回归面 = 0）。
--
-- ===========================================================================
-- 一、加粗句「拦截不自动取消，默认转人工」的落点（**本文件的第一要务**）
-- ===========================================================================
-- 这句话在本迁移里是**五层**落点，任何一层单独拿走都还能绕过去，所以五层同时成立：
--   (1) **建议动作与最终动作是两个字段**：order_intercept_check.recommended_disposition
--       （RELEASE / MANUAL_REVIEW / CANCEL）与 .final_disposition（CANCEL / HOLD / RELEASE）分开存。
--       「建议取消」这三个字写在数据里就只是**建议**。
--   (2) 表级 CHECK：manual_review_required = (recommended_disposition <> 'RELEASE')。
--       ⇒ 连「建议取消」都必须携带「需人工复核」，服务层算不出一个「已决定取消」。
--   (3) guard_order_intercept_final_disposition 触发器：final_disposition 非空时**必须已存在**
--       一条 order_intercept_disposition 人工处置留痕，否则拒绝写入。
--   (4) guard_order_intercept_disposition_requires_manual 触发器：人工处置只允许挂在
--       manual_review_required = true 的检查上 —— 给一张「放行」的单补一条「已取消」
--       是另一个方向的越权，同样挡住。
--   (5) **不存在任何一个端点能只凭拦截结果改写 final_disposition**：改它的唯一入口是
--       POST /checks/{id}/disposition（必须带已认证人工身份），跑检查的
--       POST /checks 只写 recommended_disposition。
-- ⇒ 默认值落在 MANUAL_REVIEW：任一命中且没有 BLOCK 级命中时一律 MANUAL_REVIEW，
--   「没有可评估的输入」也落到 MANUAL_REVIEW 而不是 RELEASE —— 放行一个没人看过的单，
--   与误拦真实订单一样是真实损失，而加粗句只说了后者更贵，不是只说了后者。
--
-- ⚠️ final_disposition 的词表**刻意不含任何「自动」取值**：没有 AUTO_CANCEL、
--   没有 AUTO_RELEASE。DB 里不存在「系统自己取消了这一单」这个状态，
--   因为加粗句说的就是这件事不该发生。
--
-- ===========================================================================
-- 二、上游实查结论：蓝图输入里的四样，本服务能真接通两样半
-- ===========================================================================
-- 蓝图输入句写的是「地址校验服务；16 合规与风控信号；5 定价（低于成本线）；订单特征」四样。
-- 逐样实查集成头 a052a025：
--   * **地址校验服务**（9.2.2.1 地址与支付校验）—— **未开发**。禁运国家清单、可投递性
--     校验、支付一致性都在它名下，本服务**没有任何可读的地址校验结果**。
--     ⇒ 「地址**不可达**」判不了，如实落成 finding.status = UPSTREAM_UNAVAILABLE，
--       并在 capabilities 端点显式报 addressValidationAvailable=false。
--     但蓝图同一条里的「地址**信息不全**」是**另一件事**，它能从收货地址快照的列直接推出，
--     是真 SQL（第五节），所以本模块拆成两条 finding 而不是含糊地合并成一条。
--   * **16 合规与风控信号** —— 卷 16 的 24 个 L4 **全部未交付**，连服务都还没有，
--     在别的服务的独立库里，按 AGENTS.md 模块边界纪律**禁止跨服务 SQL**，读不到。
--     ⇒ 「黑名单买家」改读 **order_review_buyer_profile**（V149，租户自维护的真实表，
--       已建、已挂端点，本模块**只读**）；「疑似欺诈」直接读 **order_risk_assessment**
--       （V150，同服务同库），复用它的 risk_score 与 recommended_action / final_action，
--       **不重算一遍风控分数，也不绕过它「分数不能单独作为拒绝依据」的口径**。
--   * **5 定价（低于成本线）** —— 卷 05 价目未交付，本服务没有任何成本来源。
--     ⇒ 成本基线落成租户可维护的真实表 order_intercept_sku_cost（第六节），
--       与 V92 fulfillment_path_cost_profile、V150 order_risk_signal 同一手法：
--       真实业务数据、真实参与判定、换上游只要换来源枚举，表结构与判定逻辑不动。
--   * **订单特征** —— **真 SQL**，全部从本服务 sales_order 簇现算（第五节），
--     不依赖任何未交付上游。
--
-- ===========================================================================
-- 三、「同一买家」与 9.2.1.2 / 9.2.2.2 同一口径，但**不复用它们的代码**
-- ===========================================================================
-- 实查 V99：sales_order **没有买家身份列**（只有 order_no / order_source / currency /
-- payment_method / status / 金额），也没有店铺列。买家身份的唯一稳定来源是
-- order_address_snapshot（address_role='SHIPPING'）三件套的 md5：
--   buyer_key = md5(recipient_name ␟ recipient_phone ␟ recipient_email)
--   ␟ 用不可见 unit separator U+001F 而不是 '|'：收件人字段由运营自由录入，
--   '|' 会让「Smith|John」与「Smith」+「John」两组字段拼出同一个串 ——
--   那是一次**跨买家的拦截归因**，比不归因严重得多。
-- ⚠️ 这个公式与 V149 order_review_buyer_profile.buyer_key **逐字相同**，
--   所以「9.2.1.2 拉黑了这个人」与「9.2.1.3 判定这个人是黑名单买家」永远指向同一个人；
--   两者若各算各的，会出现「审单说不是黑名单、拦截说是黑名单」这种无法排查的分歧。
-- ⚠️ 刻意**不抽公共类**：V149 与 V150 是别的车道的文件所有权，本车道改它们会造成
--   跨车道代码冲突。三处各自写死并互相注释指向，代价是同一段 md5 表达式出现三次，
--   换来的是并入时零冲突。将来要复用由重构车道统一提取。
--
-- **平台维度只能是平台级**：实查 V99，sales_order **没有 shop_code 列**。
-- 所以「平台合规风险」只能按 order_source 判（与 9.2.1.2 同一处境），
-- **店铺级合规风险在本服务无处可判**，capabilities 如实报 shopScopeSupported=false。
--
-- ===========================================================================
-- 四、判定口径：阈值与权重落库，不是 Java 里的 if 常量
-- ===========================================================================
-- 八个命中项**恒定各占一行**（第八节），判定顺序（从高到低，全部带等号）：
--   存在 BLOCK 级命中（禁运 / 平台合规 / 低于成本达到容忍度） → 建议 CANCEL
--   否则存在任一命中                                      → 建议 MANUAL_REVIEW
--   否则                                                    → 建议 RELEASE
-- 「几次算重复下单」「低于成本多少算异常」「风控分到多少算疑似欺诈」都是运营口径，
-- 写进 if 分支就再也改不动，所以全部存在 order_intercept_policy 里，PUT /policy 可改。
--
-- 缺参照怎么办（**必须显式选择，不给默认**，与 V150 missing_signal_policy 同一纪律）：
--   ASSESS_KNOWN_ONLY —— 按已评估项判定，缺参照项计入 missing_count 并在明细里点名缺什么
--   BLOCK_DECISION    —— 有任何一项缺参照就不出结论，只给「建议人工」
-- 两者差着一个真实的运营后果（前者会放行，后者会攒人工队列），所以由租户选。
--
-- ===========================================================================
-- 五、订单特征类判定全部是**真 SQL**
-- ===========================================================================
--   * 重复下单   → 同一 buyer_key 在 duplicate_window_hours 内、**本单之前**的已提交笔数
--   * 低于成本   → 逐行 (line_amount + discount_amount) / quantity 与租户成本基线比
--   * 地址信息不全 → 收货地址快照的列直接推（缺邮编 / 缺电话 / 只有信箱 / 地址过短 /
--                   省市皆空 / 邮编是同一个数字重复）
-- 三项都直接查本服务 sales_order 簇，「订单特征」这项输入因此是真接通的，不是占位。
-- ⚠️ 折扣金额是**负数**（V99 的 sales_order_line_discount_is_non_positive 强制 <= 0），
--   所以净额是 line_amount **加** discount_amount；写成减号会把每一行都算成高价，
--   于是「异常折扣」永远不会命中 —— 而一个永不命中的检查比没有检查更糟，
--   因为界面会显示「无异常折扣」，运营据此认为这一项量过了。
-- ⚠️ 「本单之前」是硬口径：把本单算进重复笔数会让**每一张单都至少命中一次**，
--   而一单从不与自己是重复单。
--
-- ===========================================================================
-- 六、金额与数量
-- ===========================================================================
-- 金额 numeric(19,4)、成本基线 numeric(19,4)、数量 numeric(19,6)，
-- 风控阈值 numeric(9,4)（与 V150 risk_score 同刻度，直接比较不换算），全程 BigDecimal 禁浮点。
-- 「低于成本的容忍度」用**比率**表达（0.0500 = 允许亏 5%），不用百分数 5 ——
-- 与 V150 比率类信号同一口径，避免 0.05 被读成「亏 5 厘」。
-- 时间一律 timestamptz，服务层用 OffsetDateTime 绑定并在 values 位置写 ?::timestamptz，
-- 绝不用 Timestamp.valueOf(LocalDateTime...)（那会静默偏 −28800s）。
-- 「判定基准时刻」钉在订单 submitted_at 上（第七节），不用 now()。

-- ---------------------------------------------------------------------------
-- 1. 拦截策略（租户可维护；**每租户恰好一条**）
-- ---------------------------------------------------------------------------
-- 为什么要求先有策略才允许检查：拦截结论会决定一单发不发，「没配策略 = 默认全放行」
-- 与加粗句是反的。所以这里取 V150 同一立场：**没登记就报「未配置」，不给隐含默认值**。
CREATE TABLE order_intercept_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 一租户一条：UNIQUE(tenant_id) 本身就是「恰好一条」的表达。
  UNIQUE(tenant_id),
  UNIQUE(tenant_id,id),
  policy_name varchar(120) NOT NULL CHECK(btrim(policy_name)<>''),
  enabled boolean NOT NULL DEFAULT true,

  -- ---- 重复下单（真 SQL：同买家窗口内、本单之前的已提交笔数）
  duplicate_window_hours int NOT NULL CHECK(duplicate_window_hours>0),
  -- 下限 2：一单寄给一个人是常态，1 会让每张单都命中。
  duplicate_max_orders int NOT NULL CHECK(duplicate_max_orders>1),

  -- ---- 低于成本（真 SQL：行净额单价 vs 租户成本基线）
  -- 允许亏的比例。0.0000 = 低于成本即命中；0.2000 = 亏两成以内放过（清仓场景）。
  below_cost_tolerance_rate numeric(9,4) NOT NULL
    CHECK(below_cost_tolerance_rate>=0 AND below_cost_tolerance_rate<=1),

  -- ---- 疑似欺诈（读 V150 的 order_risk_assessment，不重算分数）
  -- 阈值与 V150 risk_score 同为 numeric(9,4) 0..100，可以直接比、不换算。
  fraud_score_threshold numeric(9,4) NOT NULL
    CHECK(fraud_score_threshold>0 AND fraud_score_threshold<=100),

  -- ---- 缺参照怎么办（**必须显式选择，不给默认**，与 V150 missing_signal_policy 同一纪律）
  -- ASSESS_KNOWN_ONLY —— 按已评估项判定，缺参照项计入 missing_count 并点名缺什么
  -- BLOCK_DECISION    —— 有任何一项缺参照就不出结论，只给「建议人工」
  missing_reference_policy varchar(24) NOT NULL
    CHECK(missing_reference_policy IN ('ASSESS_KNOWN_ONLY','BLOCK_DECISION')),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(updated_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 2. 限制清单（租户可维护：禁运收货国 + 平台来源合规风险）
-- ---------------------------------------------------------------------------
-- 这张表是「9.2.2.1 未交付」与「16 合规未交付」的**诚实替身**，不是占位：
-- 它是真实的业务表、真实的租户维护数据、真实参与判定。
-- 与硬编码常量的区别在于：换上游接口到位后只要改 restriction_source 一列。
--
-- ⚠️ 两种限制共用一张表但**不共用谓词**：收货国查 country_code（char(2)），
--   平台来源查 order_source（V99 的词表）。塞进一张表是为了让「限制清单」在界面上
--   是一张可排序可筛选的表，而不是两张语义相同的表。
CREATE TABLE order_intercept_restriction (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  restriction_kind varchar(24) NOT NULL
    CHECK(restriction_kind IN ('SHIPPING_COUNTRY','PLATFORM_SOURCE')),
  -- 目标：SHIPPING_COUNTRY 是 ISO 3166-1 alpha-2 大写；PLATFORM_SOURCE 是
  -- sales_order.order_source 的词表值。形状由下面的 CHECK 按 kind 分开钉死。
  restriction_target varchar(64) NOT NULL CHECK(btrim(restriction_target)<>''),
  CHECK(restriction_kind<>'SHIPPING_COUNTRY' OR restriction_target ~ '^[A-Z]{2}$'),
  -- 平台来源词表与 V99 sales_order.order_source 的 CHECK 逐字相同。
  -- 写成子串查询会随 V99 改词表而悄悄失配，所以这里**写死同一份词表**并在
  -- guard_order_intercept_restriction_source_valid 触发器里对真表再核一次。
  CHECK(restriction_kind<>'PLATFORM_SOURCE' OR restriction_target IN (
      'MANUAL','PLATFORM','AMAZON','SHOPIFY','EBAY','TIKTOK_SHOP','ALIBABA_1688')),

  -- **限制强度**：BLOCK = 不可发（建议取消，仍需人工）；REVIEW = 可疑（建议转人工）。
  -- 刻意不给「放行」档：一张限制清单上写「放行」的行没有意义，
  -- 而它会让「清单里有的行 = 有问题」这条直觉失效。
  restriction_effect varchar(8) NOT NULL CHECK(restriction_effect IN ('BLOCK','REVIEW')),

  -- 来源枚举（照抄 V150 signal_source / V116 stock_source 的既有解法）：
  --   TENANT_MAINTAINED            —— 租户自己登记的（当前唯一可写来源）
  --   MODULE_9_2_2_1_SHIPPING     —— 9.2.2.1 地址与支付校验的禁运国家清单，**未交付**
  --   MODULE_16_PLATFORM_COMPLIANCE—— 16 合规的平台合规清单，**未交付**
  --   MODULE_2_PLATFORM_POLICY    —— 2 平台的来源合规策略，**未交付**
  -- ⚠️ 刻意**不伪造 L4 模块号**：这三处目前都没有任何已交付的 L4 模块是它们的交付方，
  --   编一个号会让后来的人去查一个不存在的模块。用「卷 + 关注点」的粗粒度名，
  --   并在 capabilities 里如实标 available=false（与 V150 同一做法）。
  restriction_source varchar(32) NOT NULL CHECK(restriction_source IN (
      'TENANT_MAINTAINED','MODULE_9_2_2_1_SHIPPING',
      'MODULE_16_PLATFORM_COMPLIANCE','MODULE_2_PLATFORM_POLICY'
  )),
  source_reference varchar(128) NOT NULL DEFAULT '',

  reason varchar(400) NOT NULL DEFAULT '',
  effective_from timestamptz,
  effective_to timestamptz,

  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,restriction_kind,restriction_target),
  -- 生效期有序：to 早于 from 是一条永远不生效的规则，它比报错更糟（看起来配过了）。
  CHECK(effective_from IS NULL OR effective_to IS NULL OR effective_to>=effective_from),
  CHECK(restriction_source='TENANT_MAINTAINED' OR btrim(source_reference)<>'')
);

-- ---------------------------------------------------------------------------
-- 3. SKU 成本基线（租户可维护；「5 定价未交付」期间低于成本线的唯一依据）
-- ---------------------------------------------------------------------------
-- **刻意按 SKU 存，不按订单存**：成本是商品属性，不是订单属性。
-- 按订单存一份会让「这张单为什么被判低于成本」在成本改了之后无法复算。
-- sku_id 是 catalog 的逻辑 ID（与 sales_order_line.sku_id 同一形状），**不建跨库 FK**。
CREATE TABLE order_intercept_sku_cost (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- 可读快照：客服要能对得上货，裸 uuid 谁也念不出来。
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- **单位成本**：与 sales_order_line.unit_price 同一币种同一刻度（numeric(19,4)），
  -- 否则「低于成本」这个比较没有意义（本服务无汇率来源，10 财务未交付，
  -- 跨币种比较是纯噪声 —— 与 V150 金额异常维度的「同币种硬口径」同一纪律）。
  -- 一租户一套成本基线，不带币种列：**币种由租户自己保持单一**，
  -- 跨币种基线的正确形态是租户分开维护，本服务不做汇率换算也不假装能做。
  unit_cost numeric(19,4) NOT NULL CHECK(unit_cost>=0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 成本口径的**生效时刻**：换供应商、涨价都会改这一列，而历史拦截结论必须能复算。
  -- 取「当前有效那一条」：同一 SKU 多条时按 captured_at 降序、id 降序兜底（确定性，第七节）。
  captured_at timestamptz NOT NULL,
  note varchar(600) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,sku_id,captured_at)
);

-- ---------------------------------------------------------------------------
-- 4. 拦截检查（头：一张订单一次检查 = 一行）
-- ---------------------------------------------------------------------------
-- 判定结果是**凭据不是当前状态**：拦截结论会被平台与买家追问，
-- 「当时按哪套策略、算出什么结论、谁处置的」必须能复现（与 V149 evaluation 同一纪律）。
-- 所以同一张单跑第二次检查各占一行，历史不被覆盖。
CREATE TABLE order_intercept_check (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 复合外键指到本服务自己的 sales_order：订单被删则检查一并消失
  -- （检查是对那张单的判断，单没了判断也没有意义）。与 V150 同一手法。
  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL CHECK(btrim(order_no)<>''),
  order_source varchar(32) NOT NULL CHECK(order_source IN (
      'MANUAL','PLATFORM','AMAZON','SHOPIFY','EBAY','TIKTOK_SHOP','ALIBABA_1688')),
  -- 买家键冗余一份：列表按买家筛是真查询，不让每次列表都去 join 地址快照算 md5。
  -- 形状与 V149 order_review_buyer_profile.buyer_key **完全一致**（char(32) 十六进制），
  -- 因为「黑名单买家」判定就是拿这一列去查 V149 那张表。
  buyer_key char(32) CHECK(buyer_key IS NULL OR buyer_key ~ '^[0-9a-f]{32}$'),
  order_currency char(3) NOT NULL CHECK(order_currency ~ '^[A-Z]{3}$'),
  order_amount numeric(19,4) NOT NULL CHECK(order_amount>=0),
  order_submitted_at timestamptz NOT NULL,
  shipping_country_code char(2) CHECK(shipping_country_code IS NULL
      OR shipping_country_code ~ '^[A-Z]{2}$'),

  policy_id uuid NOT NULL,
  -- 策略版本快照 + 关键阈值快照：策略后来改了，这次检查当时按哪一版判的要留住，
  -- 否则「昨天为什么放行、今天为什么拦截」无法复算。
  policy_version bigint NOT NULL,
  policy_name varchar(120) NOT NULL,

  -- **判定基准时刻**：恒等于订单的 submitted_at（第五节：确定性）。
  assessed_reference_at timestamptz NOT NULL,

  -- 命中项计数（由触发器从八行 finding 实算，见第九节）
  flagged_count int NOT NULL DEFAULT 0 CHECK(flagged_count BETWEEN 0 AND 8),
  blocked_count int NOT NULL DEFAULT 0 CHECK(blocked_count BETWEEN 0 AND 8),
  cleared_count int NOT NULL DEFAULT 0 CHECK(cleared_count BETWEEN 0 AND 8),
  missing_count int NOT NULL DEFAULT 0 CHECK(missing_count BETWEEN 0 AND 8),
  CHECK(flagged_count+cleared_count+missing_count = 8),
  -- BLOCK 级严重度恒带 FLAGGED 状态（见第 5 节的 status↔severity 硬绑定），
  -- 所以 blocked_count 不可能超过 flagged_count。
  CHECK(blocked_count<=flagged_count),

  -- ---- **建议处置**（第四节判定顺序；仍是建议，不是结论）
  recommended_disposition varchar(16) NOT NULL
    CHECK(recommended_disposition IN ('RELEASE','MANUAL_REVIEW','CANCEL')),
  -- 加粗句第 (2) 层：非 RELEASE 恒需人工复核；RELEASE 恒不需。
  -- ⇒ **「建议取消」也带 manual_review_required = true**，数据层面就表达不出
  --   「系统已经自己取消了这一单」。
  manual_review_required boolean NOT NULL,
  CHECK(manual_review_required = (recommended_disposition<>'RELEASE')),
  -- 「建议放行」必须真的**一个命中都没有**。放行是唯一不能出错的方向 ——
  -- 误拦的代价由加粗句点名，比误放更难被发现（货已经发出去了）。
  -- 服务层若在触发器算合计之前抢先写一行 RELEASE，这里当场拒掉。
  CHECK(recommended_disposition<>'RELEASE' OR (flagged_count=0 AND blocked_count=0)),

  -- ---- **最终处置**：只可能来自人工处置留痕（第一节第 (3) 层）
  -- 'CANCEL' = 人已经给过取消结论，**不是**系统给的；
  -- 'RELEASE' = 人工确认这是误拦（FALSE_POSITIVE），是加粗句最关心的那一档的正向留痕；
  -- 'HOLD'    = 已决定挂起（等材料 / 等 16 合规）。
  final_disposition varchar(16)
    CHECK(final_disposition IS NULL OR final_disposition IN ('RELEASE','HOLD','CANCEL')),
  CHECK(final_disposition IS NULL OR manual_review_required),
  disposed_at timestamptz,
  disposed_by uuid,
  CHECK((final_disposition IS NULL) = (disposed_at IS NULL)),
  CHECK((disposed_at IS NULL) = (disposed_by IS NULL)),

  -- 触发器算完合计后打的时间戳：它同时是「本行是否已被算过」的标记
  -- 与提交期不变量的判据（第九节末尾的延迟约束触发器）。
  totals_computed_at timestamptz,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,policy_id) REFERENCES order_intercept_policy(tenant_id,id),
  CHECK(totals_computed_at IS NULL OR totals_computed_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 5. 命中明细（**一行一个命中项，八项恒定各占一行**，缺失与未接通也占行）
-- ---------------------------------------------------------------------------
-- 照抄 V150 order_risk_assessment_component 同一做法：**缺失的项也占一行**。
-- 少一行的话，「这单为什么没按预期拦截」就只能从计数反推，而计数不保留是谁缺席的。
CREATE TABLE order_intercept_finding (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  check_id uuid NOT NULL,

  -- 蓝图职责句里七类异常，本表按**可独立评估的最小单元**拆成八项：
  -- 「地址不可达/信息不全」拆成 ADDRESS_INCOMPLETE（真 SQL）与
  -- ADDRESS_DELIVERABILITY（依赖未交付的 9.2.2.1）—— 前者现在就能判，后者不能，
  -- 合成一行只能让能判的那半也被一起标成「不可用」。
  check_code varchar(32) NOT NULL CHECK(check_code IN (
      'ADDRESS_INCOMPLETE',        -- 地址信息不全（真 SQL：收货地址快照的列）
      'ADDRESS_DELIVERABILITY',    -- 地址不可达（9.2.2.1 未交付 ⇒ UPSTREAM_UNAVAILABLE）
      'BLACKLISTED_BUYER',         -- 黑名单买家（读 V149 order_review_buyer_profile）
      'DUPLICATE_ORDER',           -- 重复下单（真 SQL：同买家窗口内本单之前的已提交笔数）
      'BELOW_COST_DISCOUNT',       -- 异常折扣（真 SQL：行净额单价 vs 租户成本基线）
      'SUSPECTED_FRAUD',           -- 疑似欺诈（读 V150 order_risk_assessment，不重算）
      'COUNTRY_RESTRICTED',        -- 超出收货国家限制（租户限制清单 SHIPPING_COUNTRY）
      'PLATFORM_COMPLIANCE'        -- 平台合规风险（租户限制清单 PLATFORM_SOURCE）
  )),

  -- ---- 单项状态：把「不可用」拆开而不是一个 null，让「这一项为什么没结论」
  --      在详情页上是四句不同的话：没量过、上游没接通、没参照、这一单形态上不适用。
  status varchar(24) NOT NULL CHECK(status IN (
      'FLAGGED',               -- 命中
      'CLEARED',               -- 真实 SQL 判定为正常
      'NO_REFERENCE',          -- 缺参照：无成本基线 / 无风控评估 / 买家无档案
      'UPSTREAM_UNAVAILABLE',  -- 上游未交付（ADDRESS_DELIVERABILITY 当前恒为此值）
      'NOT_APPLICABLE'         -- 这一单形态上不适用（如无收货地址快照）
  )),
  -- ---- 严重度：决定建议处置落在哪一档（第四节）
  --   INFO  仅提示（不影响建议处置）
  --   WARN  命中，建议转人工
  --   BLOCK 命中，建议取消（**仍然只是建议**，加粗句第 (1)(2) 层）
  severity varchar(8) NOT NULL CHECK(severity IN ('INFO','WARN','BLOCK')),
  -- 硬绑定：命中才谈得上严重度；没命中时恒为 INFO。
  -- 反过来写成「severity<>INFO ⇒ FLAGGED」会挡住 NOT_APPLICABLE 用 INFO 表达
  -- 「这一项不适用」这种最正确的表达。
  CHECK((status='FLAGGED') = (severity<>'INFO')),

  -- ---- 取值来源：这一项的结论**从哪来**
  -- ⚠️ 长度按最长的一个取值给（MODULE_9_2_2_2_RISK_ASSESSMENT 是 28 个字符）：
  --   给成 varchar(24) 会让「读风控评估」这一项在**第一次写入时**就被 DB 拒掉，
  --   而错误信息是「value too long」—— 没有人能从那句话联想到「枚举名太长了」。
  evidence_source varchar(40) NOT NULL CHECK(evidence_source IN (
      'DERIVED_FROM_ORDERS',      -- 真 SQL：本服务 sales_order 簇现算
      'TENANT_MAINTAINED',        -- 租户维护的限制清单 / 成本基线
      'MODULE_9_2_1_2_BUYER_PROFILE', -- 读 V149 order_review_buyer_profile（本服务同库）
      'MODULE_9_2_2_2_RISK_ASSESSMENT',-- 读 V150 order_risk_assessment（本服务同库）
      'UPSTREAM_NOT_DELIVERED'    -- 上游模块未交付，本项判不了
  )),

  -- ---- 原始观测值：计数 / 金额 / 风控分（不可用时 NULL，不是 0 ——
  --      「没量到」给 0 会被下游读成「量过了，结论是 0」）。
  -- ⚠️ **只有真正可比的数值才进这一列**。COUNTRY_RESTRICTED 与 PLATFORM_COMPLIANCE
  --   比的是「US」与「AMAZON」这样的**文本**，不是数值 —— 把文本塞进 numeric 列
  --   要么被 DB 拒，要么被写成 0 然后被读成「量过了，结论是 0」。
  --   文本比对结果一律走下面的 evidence_reference。
  observed_value numeric(19,6),
  -- 对照阈值：重复下单的笔数上限 / 低于成本的容忍度 / 风控分阈值 / 命中笔数等。
  -- 明细里同时给出「实际值」与「对照值」，客服才能原话复述
  -- 「同买家 24 小时内已下 4 单 ≥ 阈值 3」而不是「系统判定为重复下单」。
  threshold_value numeric(19,6),
  -- 命中计数：本项**在多少次比较里**命中（低于成本可能多行命中；重复下单按买家数）。
  -- 恒为 0 或 1 是不诚实的：多行同时低于成本与「只有一行」处置完全不同。
  match_count int NOT NULL DEFAULT 0 CHECK(match_count>=0),
  CHECK((status='FLAGGED') = (match_count>0)),

  -- 参照的实体：**文本比对的结果落这里**，数值比对的结果落在上面的 observed_value。
  --   COUNTRY_RESTRICTED   → 命中的收货国码（'US' / 禁运清单里的那一项）
  --   PLATFORM_COMPLIANCE  → 订单来源（'AMAZON' / 平台限制清单里的那一项）
  --   BELOW_COST_DISCOUNT  → 命中的 SKU 编码（多行命中时列出全部，行数见 match_count）
  --   BLACKLISTED_BUYER    → order_review_buyer_profile.id
  --   SUSPECTED_FRAUD      → order_risk_assessment.id（复用了哪一次评估）
  -- 让详情页能直接跳回「是哪一行、哪一单、哪个国家」而不必反推。
  evidence_reference varchar(400) NOT NULL DEFAULT '',
  -- 缺参照 / 上游未接通时必须说清**缺的是什么、为什么不可用**（不能让人反推）。
  explanation text NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,check_id,check_code),
  FOREIGN KEY(tenant_id,check_id) REFERENCES order_intercept_check(tenant_id,id) ON DELETE CASCADE,
  -- 不可用的项没有观测值：给 0 等于宣称「量过了，结果是 0」。
  CHECK(status IN ('FLAGGED','CLEARED') OR observed_value IS NULL)
);

-- ---------------------------------------------------------------------------
-- 6. 人工处置（**拦截唯一的出口**，加粗句第 (3) 层）
-- ---------------------------------------------------------------------------
CREATE TABLE order_intercept_disposition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  check_id uuid NOT NULL,
  -- 处置结论。'CANCEL' 是**人给的**取消结论，不是系统给的。
  decision varchar(16) NOT NULL CHECK(decision IN ('RELEASE','HOLD','CANCEL')),
  -- 处置人：已认证成员关系 ID（来自 identity，不是请求头里的任何东西）。
  disposed_by uuid NOT NULL,
  disposed_at timestamptz NOT NULL,
  -- **误拦必须说得出为什么**：加粗句的理由是「误拦真实订单的代价高于人工看一眼」，
  -- 而「人工看了一眼」如果不留痕，下一次系统仍然会拦同一张单。
  reason_code varchar(32) NOT NULL CHECK(reason_code IN (
      'CONFIRMED_ANOMALY',    -- 复核后确认确有异常，按建议处置
      'FALSE_POSITIVE',       -- 误拦：真实订单被错误命中（**误拦真实订单的代价最高**）
      'PENDING_EVIDENCE',     -- 还需材料，转挂起
      'ESCALATED_COMPLIANCE'  -- 升级 16 合规人工（卷 16 未交付期间的兜底出口）
  )),
  note text NOT NULL DEFAULT '',

  UNIQUE(tenant_id,id),
  -- 一次检查只处置一次；要改结论就重新检查一次（那会带上新的成本基线与新的人工）。
  -- 不做「先放后拦」的覆写：拦截留痕被覆写就等于没有留痕。
  UNIQUE(tenant_id,check_id),
  FOREIGN KEY(tenant_id,check_id) REFERENCES order_intercept_check(tenant_id,id) ON DELETE CASCADE,
  -- 误拦必须放行：理由码与结论自相矛盾的组合在写库这一层就挡住。
  CHECK(reason_code<>'FALSE_POSITIVE' OR decision='RELEASE')
  -- ⚠️ 刻意**没有 created_at**：处置行的诞生时刻就是 disposed_at。
  -- 多一列 now() 默认值既冗余又与 disposed_at 打架
  -- （处置一条历史检查时两列必然对不上，然后被一条 CHECK 挡住）。与 V150 同一理由。
  -- 「升级合规」不结案：DEFERRED / ESCALATED 类结论由 decision 表达，
  -- 不允许用 decision='CANCEL' 冒充「已升级」（那是一起真实的客诉来源）。
);

-- ===========================================================================
-- 七、触发器
-- ===========================================================================
-- 本文件所有约束触发器在写行时**一律回表读当前行**，不使用 NEW 的可变列：
-- 服务层的写入顺序是「先插头 → 写 finding 行 → 回填头」，
-- 延迟到提交时那次 INSERT 拿着的仍是半成品的 NEW；用 NEW 每次必报。
-- （与 V92 guard_routing_decision_switch、V150 guard_order_risk_assessment_totals_settled 同一个坑、同一个解法。）

-- ---------------------------------------------------------------------------
-- 7.1 限制清单的平台来源必须真的存在于 sales_order 词表
-- ---------------------------------------------------------------------------
-- 表级 CHECK 写死了 V99 的词表副本。两份词表一旦漂移（本迁移写的是旧版），
-- 表级 CHECK 仍会通过而判定永远不命中 —— 界面显示「已限制 ALIBABA_1688」，
-- 实际一张单都拦不住。所以写入时对真表再核一次。
CREATE OR REPLACE FUNCTION guard_order_intercept_restriction_source_valid() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_ok boolean;
BEGIN
  IF NEW.restriction_kind = 'PLATFORM_SOURCE' THEN
    SELECT EXISTS (
      SELECT 1 FROM pg_constraint c
       WHERE c.conrelid = 'sales_order'::regclass AND c.contype = 'c'
         AND pg_get_constraintdef(c.oid) LIKE '%' || NEW.restriction_target || '%'
    ) INTO v_ok;
    IF NOT v_ok THEN
      RAISE EXCEPTION '平台来源 "%" 不在 sales_order.order_source 的现行词表里：本行永远不会命中任何订单，'
        '而界面会显示「已限制」', NEW.restriction_target USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_intercept_restriction_source_valid
  BEFORE INSERT OR UPDATE ON order_intercept_restriction
  FOR EACH ROW EXECUTE FUNCTION guard_order_intercept_restriction_source_valid();

-- ---------------------------------------------------------------------------
-- 7.2 合计与建议处置由 DB 实算：服务层**不**自己写计数与建议处置
-- ---------------------------------------------------------------------------
-- 为什么必须在 DB 侧算：服务层算一遍、触发器再算一遍，早晚会有一次两边不一致，
-- 而「检查记录上写着命中 2 条，它与八行 finding 算出来的不一样」是无法排查的。
-- 判定顺序（从高到低，等号生效，与第四节口径逐字对应）：
--   存在 BLOCK 级命中 → CANCEL（建议取消，manual_review_required 恒为 true）
--   否则存在任一命中 → MANUAL_REVIEW
--   否则               → RELEASE
--
-- ⚠️ ASSESS_KNOWN_ONLY 且**一条都没评上**时不出「放行」：判 MANUAL_REVIEW。
--   「八个命中项一个都没结论」推导出「放行」= 放行了一个没人看过的单，
--   与误拦真实订单一样是真实损失，而加粗句只说了后者更贵，不是只说了后者。
-- ⚠️ BLOCK_DECISION 且有缺参照时同样不出「放行」，只给 MANUAL_REVIEW。
--
-- ⚠️ SECURITY DEFINER 是**必需的**，不是装饰：7.5 对这些列做了列级 UPDATE REVOKE，
--   非 SECURITY DEFINER 的触发器会以调用者（trade_app）的权限执行那个 UPDATE，
--   于是每次检查都撞 "permission denied"。加上它之后触发器以迁移角色（表 owner）执行 ——
--   而 FORCE ROW LEVEL SECURITY 对 owner 同样生效，RLS 一道都不少。
CREATE OR REPLACE FUNCTION order_intercept_apply_totals(p_check uuid, p_tenant uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  v_check order_intercept_check%ROWTYPE;
  v_total int;
  v_flagged int;
  v_blocked int;
  v_cleared int;
  v_missing int;
  v_policy varchar(24);
  v_action varchar(16);
BEGIN
  SELECT * INTO v_check FROM order_intercept_check
   WHERE id = p_check AND tenant_id = p_tenant;
  IF NOT FOUND THEN
    RAISE EXCEPTION '拦截检查 % 不存在或不属于本租户', p_check USING ERRCODE='23503';
  END IF;

  -- 回表读 finding 行，而不是相信调用方传进来的任何数字。
  SELECT count(*),
         count(*) FILTER (WHERE f.status='FLAGGED'),
         count(*) FILTER (WHERE f.status='FLAGGED' AND f.severity='BLOCK'),
         count(*) FILTER (WHERE f.status='CLEARED'),
         count(*) FILTER (WHERE f.status IN ('NO_REFERENCE','UPSTREAM_UNAVAILABLE','NOT_APPLICABLE'))
    INTO v_total, v_flagged, v_blocked, v_cleared, v_missing
    FROM order_intercept_finding f
   WHERE f.check_id = p_check AND f.tenant_id = p_tenant;

  IF v_total = 0 THEN
    RETURN;
  END IF;

  SELECT p.missing_reference_policy INTO v_policy
    FROM order_intercept_policy p
   WHERE p.id = v_check.policy_id AND p.tenant_id = p_tenant;
  IF v_policy IS NULL THEN
    RAISE EXCEPTION '拦截策略 % 不存在或不属于本租户：检查结论无法计算', v_check.policy_id
      USING ERRCODE='23503';
  END IF;

  IF v_blocked > 0 THEN
    v_action := 'CANCEL';
  ELSIF v_flagged > 0 THEN
    v_action := 'MANUAL_REVIEW';
  ELSIF v_cleared > 0 AND (v_missing = 0 OR v_policy = 'ASSESS_KNOWN_ONLY') THEN
    v_action := 'RELEASE';
  ELSE
    -- 一条都没评上，或 BLOCK_DECISION 下有缺参照：不出「放行」。
    v_action := 'MANUAL_REVIEW';
  END IF;

  UPDATE order_intercept_check
     SET flagged_count = v_flagged,
         blocked_count = v_blocked,
         cleared_count = v_cleared,
         missing_count = v_missing,
         recommended_disposition = v_action,
         -- 加粗句的落点之一：建议取消与建议人工恒带「需人工复核」，
         -- 建议放行恒不带 —— 由 CHECK (manual_review_required = (recommended_disposition<>'RELEASE'))
         -- 从表级再兜一遍，服务层写错也过不去。
         manual_review_required = (v_action <> 'RELEASE'),
         totals_computed_at = now()
   WHERE id = p_check AND tenant_id = p_tenant;
END $$;

-- 合计由**第八个 finding 落下时**触发，而不是头行 INSERT 时：
-- 服务层的写入顺序是「先插头 → 再写 8 行 finding」，头插入那一刻 finding 一张都没有，
-- 在头上挂触发器只能算出「0 项」，真正的算法永远跑不到。
CREATE OR REPLACE FUNCTION guard_order_intercept_finding_row_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE v_total int;
BEGIN
  SELECT count(*) INTO v_total
    FROM order_intercept_finding f
   WHERE f.check_id = NEW.check_id AND f.tenant_id = NEW.tenant_id;
  -- ⚠️ 只在**满 8 行**时算合计，**不要**在每一行插入时就断言「必须已经是 8」：
  -- 服务层是分 8 条 INSERT 写 finding 的，前七行落下时 count 分别是 1..7，
  -- 在那里抛错等于让本模块自己的写入路径永远走不通。
  IF v_total = 8 THEN
    PERFORM order_intercept_apply_totals(NEW.check_id, NEW.tenant_id);
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER order_intercept_finding_row_complete
  AFTER INSERT ON order_intercept_finding
  FOR EACH ROW EXECUTE FUNCTION guard_order_intercept_finding_row_complete();

-- ---------------------------------------------------------------------------
-- 7.3 提交期不变量：**没有算过合计的检查不许留在库里**
-- ---------------------------------------------------------------------------
-- 头先插、finding 后写：服务层中途抛异常会整体回滚，那没问题；
-- 怕的是**有人绕开服务层直连写入**，留下一条 recommended_disposition='RELEASE'
-- 的头 —— 清单页读到的就是「放行」。所以延迟到提交再查一次 totals_computed_at。
CREATE OR REPLACE FUNCTION guard_order_intercept_check_totals_settled() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_totals timestamptz;
BEGIN
  -- ⚠️ 必须**回表读当前状态**，不能看 NEW。
  --   延迟约束触发器把事件排队时，队里存的是 INSERT 那一刻的 NEW，
  --   那时 totals_computed_at 当然是 NULL（合计要到第 8 个 finding 落下才回填）。
  SELECT c.totals_computed_at INTO v_totals
    FROM order_intercept_check c
   WHERE c.id = NEW.id AND c.tenant_id = NEW.tenant_id;
  -- 行已被删（订单被删 → 检查级联删掉）：没有行可断言，放行。
  IF FOUND AND v_totals IS NULL THEN
    RAISE EXCEPTION '拦截检查 % 从未被算过合计（recommended_disposition=%s）：命中行没写完就落库会让这单被读成「放行」',
      NEW.id, NEW.recommended_disposition USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER order_intercept_check_totals_settled
  AFTER INSERT OR UPDATE ON order_intercept_check
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_intercept_check_totals_settled();

-- ---------------------------------------------------------------------------
-- 7.4 命中明细不可改写：检查是「当时按那一版策略算出来的」凭据
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_intercept_finding_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：改一格命中说明就再也复算不出当时为什么放行',
    TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_intercept_finding_append_only BEFORE UPDATE OR DELETE ON order_intercept_finding
  FOR EACH ROW EXECUTE FUNCTION guard_order_intercept_finding_append_only();

-- ---------------------------------------------------------------------------
-- 7.5 最终处置必须有人工处置背书（第一节第 (3) 层）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_intercept_final_disposition() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.final_disposition IS NOT NULL THEN
    IF NOT NEW.manual_review_required THEN
      RAISE EXCEPTION '拦截检查 % 建议放行却带着最终处置 %：建议放行的单没有「取消」这回事',
        NEW.id, NEW.final_disposition USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM order_intercept_disposition d
       WHERE d.check_id = NEW.id AND d.tenant_id = NEW.tenant_id
         AND d.decision = NEW.final_disposition
    ) THEN
      -- 这是加粗句最硬的一道闸：拦截结果自己填不出 final_disposition。
      -- 写的人以为可以，结果数据库不给 —— 「拦截自动取消」从这里被挡住。
      RAISE EXCEPTION '拦截检查 % 的最终处置 % 必须有对应的人工处置留痕（order_intercept_disposition）：拦截不自动取消，默认转人工',
        NEW.id, NEW.final_disposition USING ERRCODE='23514';
    END IF;
    -- 处置人 / 处置时刻**取处置行上的值**，不写 now()：
    -- 处置一条历史检查时 now() 会与 order_intercept_disposition.disposed_at 对不上，
    -- 两份留痕说同一个决定的时刻不一样，事后没法对账。
    SELECT d.disposed_by, d.disposed_at INTO NEW.disposed_by, NEW.disposed_at
      FROM order_intercept_disposition d
     WHERE d.check_id = NEW.id AND d.tenant_id = NEW.tenant_id
       AND d.decision = NEW.final_disposition;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_intercept_final_disposition_needs_record
  BEFORE INSERT OR UPDATE ON order_intercept_check
  FOR EACH ROW WHEN (NEW.final_disposition IS NOT NULL)
  EXECUTE FUNCTION guard_order_intercept_final_disposition();

-- ---------------------------------------------------------------------------
-- 7.6 人工处置只允许挂在「需人工复核」的检查上（第一节第 (4) 层）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_intercept_disposition_requires_manual() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_manual boolean; v_action varchar(16);
BEGIN
  SELECT c.manual_review_required, c.recommended_disposition
    INTO v_manual, v_action
    FROM order_intercept_check c
   WHERE c.id = NEW.check_id AND c.tenant_id = NEW.tenant_id;
  IF v_manual IS NULL THEN
    RAISE EXCEPTION '拦截检查 % 不存在或不属于本租户', NEW.check_id USING ERRCODE='23503';
  END IF;
  IF NOT v_manual THEN
    RAISE EXCEPTION '拦截检查 % 建议 % ，无需人工复核：给一张放行的单补一条处置记录是反方向的越权',
      NEW.check_id, v_action USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_intercept_disposition_needs_manual
  BEFORE INSERT ON order_intercept_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_order_intercept_disposition_requires_manual();

-- 处置落下后回填检查的 final_disposition。
-- ⚠️ 三种结论都回填：RELEASE（误拦放行）/ HOLD（挂起，等材料或等合规）/ CANCEL（取消）。
--   与 V150 不同 —— 那里 DEFERRED / ESCALATED 不改结论是因为它们**不是结案**；
--   这里的 HOLD 就是「已决定挂起」，它是一个真实的结论，回填了才不会让清单页
--   永远显示「待处置」而实际已经有人处理过。
CREATE OR REPLACE FUNCTION guard_order_intercept_disposition_apply() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
  UPDATE order_intercept_check
     SET final_disposition = NEW.decision
   WHERE id = NEW.check_id AND tenant_id = NEW.tenant_id;
  RETURN NULL;
END $$;
CREATE TRIGGER order_intercept_disposition_apply
  AFTER INSERT ON order_intercept_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_order_intercept_disposition_apply();

-- 处置记录同样不可改写：「先放后拦」的两次结论如果允许覆写，拦截留痕就不存在了。
CREATE OR REPLACE FUNCTION guard_order_intercept_disposition_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：处置结论被覆写就等于没有留痕',
    TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_intercept_disposition_append_only BEFORE UPDATE OR DELETE ON order_intercept_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_order_intercept_disposition_append_only();

-- ---------------------------------------------------------------------------
-- 7.7 策略变更有留痕
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_intercept_policy_versioned() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.version = OLD.version THEN
    RAISE EXCEPTION '拦截策略必须带 version 递增提交：检查记录引用的是 (policy_id, policy_version)，'
      '两条同名策略会让「当时按哪一版判的」无法复算' USING ERRCODE='23514';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER order_intercept_policy_versioned BEFORE UPDATE ON order_intercept_policy
  FOR EACH ROW EXECUTE FUNCTION guard_order_intercept_policy_versioned();

-- ===========================================================================
-- 八、索引
-- ===========================================================================
-- ① 拦截清单列表（本模块最常跑的一条查询）：按判定基准时刻倒序。
CREATE INDEX order_intercept_check_by_reference
  ON order_intercept_check(tenant_id, assessed_reference_at DESC, order_no, id);
-- ② 待人工处置队列：「有多少建议取消/建议人工还没人处置」。
CREATE INDEX order_intercept_check_pending_disposition
  ON order_intercept_check(tenant_id, assessed_reference_at DESC)
  WHERE manual_review_required AND final_disposition IS NULL;
-- ③ 按建议处置筛列表页。
CREATE INDEX order_intercept_check_by_recommendation
  ON order_intercept_check(tenant_id, recommended_disposition, assessed_reference_at DESC);
-- ④ 按买家筛（「这个人被拦过几次」）—— 索引建成 buyer_key 上，
--    因为黑名单判定与重复下单判定都以它为键。
CREATE INDEX order_intercept_check_by_buyer
  ON order_intercept_check(tenant_id, buyer_key, assessed_reference_at DESC);
-- ⑤ 重复下单判定的热查询形状：(租户, 买家键, 提交时刻) 窗口内计数。
--    建在 sales_order 的地址快照无法直接给 buyer_key 建索引（它是 md5 现算的），
--    所以这个索引服务于**本模块自己的检查记录**上的同键聚簇；
--    真实计数走 order_address_snapshot 的 (tenant_id, order_id) 再过滤。
CREATE INDEX order_intercept_finding_by_code
  ON order_intercept_finding(tenant_id, check_id, status);
CREATE INDEX order_intercept_disposition_by_check
  ON order_intercept_disposition(tenant_id, check_id);
-- ⑥ 限制清单的两种判定各走一个索引：禁运国按国家码、平台合规按来源。
CREATE INDEX order_intercept_restriction_by_target
  ON order_intercept_restriction(tenant_id, restriction_kind, restriction_target)
  WHERE enabled;
-- ⑦ 成本基线取「当前有效那一条」：倒序建在采集时刻上。
CREATE INDEX order_intercept_sku_cost_by_sku
  ON order_intercept_sku_cost(tenant_id, sku_id, captured_at DESC, id DESC);

-- ===========================================================================
-- 九、多租户：RLS + 授权（与 trade V92 第 9 节、V144 第 10 节、V150 第 8 节同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：判定查询是「同一 buyer_key」「同一收货国」的**跨订单宽条件**，
--   漏掉租户谓词就是**跨租户归因** —— 两家互不相识的公司会共用一份拦截结论，
--   A 家的黑名单会拦下 B 家的订单，而 B 家连自己被拦都不知道。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_intercept_policy','order_intercept_restriction','order_intercept_sku_cost',
    'order_intercept_check','order_intercept_finding','order_intercept_disposition'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_intercept_policy,order_intercept_restriction,order_intercept_sku_cost,
  order_intercept_check,order_intercept_finding,order_intercept_disposition TO trade_app;
-- 授权在前、收回在后（V144 第 9/10 节的顺序）：命中明细与人工处置是留痕，不可改不可删。
REVOKE UPDATE,DELETE ON order_intercept_finding FROM trade_app;
REVOKE UPDATE,DELETE ON order_intercept_disposition FROM trade_app;
-- 合计、建议处置与最终处置只能由触发器写：运行角色改不动这三个值。
-- ⚠️ 刻意**不收回** recommended_disposition 之外的其他列：服务层要写 observed_value、
--   explanation 等普通业务列，收得太宽会让整张表只读。
REVOKE UPDATE(flagged_count, blocked_count, cleared_count, missing_count,
              recommended_disposition, manual_review_required, totals_computed_at,
              final_disposition)
  ON order_intercept_check FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**（sales_order / sales_order_line / order_address_snapshot /
-- sales_order_party_snapshot / order_review_buyer_profile（V149）/
-- order_risk_assessment（V150）全部只读），跨车道 DDL 回归面 = 0。
