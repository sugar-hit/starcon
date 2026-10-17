-- 9.2.2.2 风控校验。
--
-- 蓝图 09-order-and-fulfillment.md 9.2.2.2 原文：
--   职责：多信号风险打分：买家历史（退款率、纠纷率）、设备与 IP 关联（16）、下单频次、
--         金额异常、地址集中度；输出风险分与建议动作。
--   输入：买家行为画像（15 客户）；16 风控信号；订单聚合特征。
--   输出：风险评分 + 建议动作（放行/人工/拒发）。
--   关系：上游 = 15 客户、16 合规；下游 = 9.2.1.3。
--         **风控分数不能单独作为拒绝依据**，需人工复核，避免误伤正常复购客户。
--
-- 归属：**trade 服务**（蓝图 09 卷 L2 表「9.2 订单审核 → 主责服务 trade」）。
-- 本迁移**只新建表、零 ALTER 既有表** —— sales_order 簇（V99/V100/V101）是 9.1.1.1 车道的
-- 写入所有者，本模块只**读**它们（跨车道 DDL 回归面 = 0）。
--
-- ===========================================================================
-- 一、上游实查结论：15 客户与 16 合规在本服务里**都读不到**
-- ===========================================================================
-- 蓝图输入句写的是「买家行为画像（15 客户）；16 风控信号；订单聚合特征」三样。
-- 逐样实查集成头 20ccd017：
--   * 卷 15（客户 / crm）：24 个 L4 模块里**只有 1 个已交付**（15.4.1.1 知识库，
--     backend/services/crm，独立库 starcon_crm，端口 8086）；
--   * 卷 16（合规）：**24 个 L4 模块全部未交付**，连服务都还没有；
--   * 即便将来交付了，crm / 合规服务各有自己的数据库，按 AGENTS.md 模块边界纪律
--     **禁止跨服务 SQL / FK / 共享视图** —— 本服务永远读不到它们的表。
--
-- ⇒ 结论：「买家历史（退款率 / 纠纷率）」与「设备与 IP 关联」这两维**没有可读的上游**。
--   本模块照抄 V116 stock_source / cost_source 与 V92 authorization_source 的既有解法：
--   把这两类信号登记为**租户可维护的真实表** order_risk_signal，每行强制带
--     * signal_source  ∈ ( TENANT_MAINTAINED / MODULE_15_CUSTOMER_PROFILE / MODULE_16_RISK_LINKAGE )
--     * captured_at（采集时刻，不是登记时刻 —— 登记时刻会把「三个月前的画像」说成今天的）
--   正式接口到位后由同步器按**同一形状**写入并把 signal_source 改成 MODULE_*，
--   **表结构与打分逻辑都不需要改**。
-- ⚠️ 名称刻意**不伪造 L4 模块号**：15 / 16 里目前没有任何一个 L4 模块是「退款率画像」
--   或「设备 IP 关联」的交付方，编一个 15.3.2.9 之类的号会让后来的人去查一个不存在的模块。
--   这里用的是「卷 + 关注点」的粗粒度名，并在 capabilities 里如实标 available=false。
--
-- 另外三维**是真 SQL**，直接从本服务 sales_order 簇现算，不依赖任何未交付上游：
--   * 下单频次     → 同一买家在时间窗内的已提交订单笔数
--   * 金额异常     → 本单 grand_total 与该买家**同币种**历史均单的比值
--   * 地址集中度   → 同一收货地址指纹在时间窗内的订单笔数
-- 「订单聚合特征」这一项输入因此是真接通的，不是占位。
--
-- ===========================================================================
-- 二、「同一买家」与「同一地址」：与 9.3.1.2 同一口径，但**不复用它的代码**
-- ===========================================================================
-- 实查 V99：sales_order **没有买家身份列**（只有 order_no / order_source / currency /
-- payment_method / status / 金额），也没有店铺列。唯一同时承载「买家身份」与「收货地址」
-- 的是 order_address_snapshot（address_role='SHIPPING'）。
-- 所以买家与地址两个指纹都在 SQL 里用 md5 现算，与 V144 合单**同一口径**：
--   buyer_fingerprint   = md5(recipient_name ␟ recipient_phone ␟ recipient_email)
--   address_fingerprint = md5(完整地址各段)
--   ␟ 用不可见 unit separator U+001F 而不是 '|'：收件人字段由运营自由录入，
--   '|' 会让「Smith|John」与「Smith」+「John」两组字段拼出同一个串 —— 那是一次
--   **跨买家的风险归因**，比不归因严重得多。
-- ⚠️ 刻意**不抽公共类**：V144 是 9.3.1.2 车道的文件所有权，本车道改它会造成跨车道 DDL/代码冲突。
--   两处各自写死并互相注释指向，代价是同一段 md5 表达式出现两次，
--   换来的是并入时零冲突。合单侧若将来要复用，由该车道自己提取。
--
-- 「同币种」是硬口径而不是可配项：本服务没有任何汇率来源（10 财务未交付），
-- 拿 100 USD 与 90 EUR 比「金额异常」是纯噪声。所以历史比值**只在同币种内**算；
-- 该买家没有同币种历史时该维判 INSUFFICIENT_HISTORY 而不是拿别的币种凑。
--
-- ===========================================================================
-- 三、判定顺序：权重、阈值、缺失口径全部落库，不是 Java 里的 if 常量
-- ===========================================================================
-- 五个维度权重合计 CHECK = 1.0000（照抄 V116 第 208 行同一做法）。
-- 「退款率 40%、频次 20%」是运营口径，写进 if 分支就再也改不动；
-- 权重、两个阈值、时间窗、缺失信号口径全部存在 order_risk_rule 里，PUT /rule 可改。
--
-- 打分口径（normalized 一律 0..100，贡献 = weight × normalized，DB 侧校验）：
--   BUYER_HISTORY        = 100 × max(退款率, 纠纷率)      —— 退款率与纠纷率取**较严重**的一项
--   DEVICE_IP_LINK       = 100 × min(设备IP关联数 / device_link_max_links, 1)
--   ORDER_FREQUENCY      = 100 × min(时间窗内笔数 / frequency_max_orders, 1)
--   AMOUNT_ANOMALY       = 100 × min((比值−1) / (amount_anomaly_ratio−1), 1)
--   ADDRESS_CONCENTRATION= 100 × min(时间窗内同地址笔数 / address_concentration_max_orders, 1)
-- 总分 = Σ贡献 / Σ(SCORED 维的权重)，round 到 4 位（PostgreSQL numeric 的 round 是四舍五入远离零）。
-- 建议动作的判定顺序是**从高到低**且全部带等号，同分不产生第二种解释：
--   总分 ≥ hold_shipment_threshold      → HOLD_SHIPMENT（建议拒发）
--   否则 总分 ≥ manual_review_threshold → MANUAL_REVIEW（建议人工）
--   否则                               → RELEASE（放行）
--
-- ===========================================================================
-- 四、蓝图加粗句的落点：「风控分数不能单独作为拒绝依据」
-- ===========================================================================
-- 这句话在本迁移里是**四层**落点，任何一层单独拿走都还能绕过去，所以四层同时成立：
--   (1) order_risk_assessment.final_action 有 CHECK：final_action IS NOT NULL
--       ⇒ manual_review_required。分数自己填不出一个「已决定拒发」。
--   (2) guard_order_risk_final_action 触发器：final_action 非空时**必须已存在**
--       一条 order_risk_review 人工复核记录，否则拒绝写入。
--   (3) guard_order_risk_review_requires_manual 触发器：人工复核记录**只允许**挂在
--       manual_review_required = true 的评估上 —— 给一个「放行」的单补一条「同意拒发」
--       是另一个方向的越权，同样挡住。
--   (4) **不存在任何一个端点能只凭分数改写 final_action**：改 final_action 的唯一入口是
--       POST /reviews（必须带人工身份），分数入口 POST /assessments 只写分数与建议动作。
-- ⇒ 顺序上也成立：建议拒发（HOLD_SHIPMENT）时 manual_review_required 恒为 true，
--   一条分数永远只是「建议」。
--
-- ===========================================================================
-- 五、确定性：同样的输入必须给出同样的结论
-- ===========================================================================
-- 三个会把结果变成「在不同时刻算出不同时刻的答案」的隐患，逐个封掉：
--   * **评估基准时刻钉在订单的 submitted_at 上**（order_risk_assessment.evaluated_reference_at），
--     不用 now()：同一张单重复评估一万次，得到的分数与建议动作完全一致。
--   * **信号新鲜度相对基准时刻判**，且**排除基准时刻之后采集的信号** —— 一条在订单提交之后
--     才采集到的画像，对这一单的决策没有贡献，拿它打分是穿越。
--   * **同键多条信号取确定的那一条**：captured_at 降序，再以 id 降序兜底
--     （captured_at 相同是可能的，同一毫秒采到两条）。
--
-- ===========================================================================
-- 六、金额与数量
-- ===========================================================================
-- 风险分 numeric(9,4)（0.0000..100.0000，4 位有效刻度）；权重 numeric(9,4)；
-- 信号测量值 numeric(19,6)（比率 0..1 保留 6 位，计数到百万级）；
-- 订单金额 numeric(19,4)。全程 BigDecimal 禁浮点。
-- 比率类信号（退款率 / 纠纷率）CHECK ∈ [0,1]，计数类不设上界（真绑 CHECK 在插入分支）。
-- 时间一律 timestamptz，服务层用 OffsetDateTime 绑定并在 values 位置写 ?::timestamptz。

-- ---------------------------------------------------------------------------
-- 1. 风控规则（租户可维护；**每租户恰好一条**）
-- ---------------------------------------------------------------------------
-- 为什么要求先有规则才允许评估：风控分会影响一整批订单的发货节奏，
-- 「没配规则 = 默认全放行」在 9.3.2.2 已经吃过一次亏（MCF 资质 UNKNOWN 被当成有）。
-- 这里取同一立场：**没登记规则就报「未配置」，不给隐含默认值**。
CREATE TABLE order_risk_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 一租户一条规则：UNIQUE(tenant_id) 本身就是「恰好一条」的表达。
  -- 两条并存时「取哪条」取决于查询顺序，同样输入在不同时刻算出不同结果。
  UNIQUE(tenant_id),
  -- 复合外键目标：(tenant_id,id) 必须自带唯一约束，只有 PK(id) 不够 ——
  -- PostgreSQL 要求被引用列**恰好**有唯一索引，评估头要指回规则。
  UNIQUE(tenant_id,id),
  rule_name varchar(120) NOT NULL CHECK(btrim(rule_name)<>''),
  enabled boolean NOT NULL DEFAULT true,

  -- ---- 五维权重（合计必须 = 1.0000，与 V116 第 208 行同一写法）
  weight_buyer_history numeric(9,4) NOT NULL CHECK(weight_buyer_history>=0 AND weight_buyer_history<=1),
  weight_device_ip_link numeric(9,4) NOT NULL CHECK(weight_device_ip_link>=0 AND weight_device_ip_link<=1),
  weight_order_frequency numeric(9,4) NOT NULL CHECK(weight_order_frequency>=0 AND weight_order_frequency<=1),
  weight_amount_anomaly numeric(9,4) NOT NULL CHECK(weight_amount_anomaly>=0 AND weight_amount_anomaly<=1),
  weight_address_concentration numeric(9,4) NOT NULL CHECK(weight_address_concentration>=0 AND weight_address_concentration<=1),

  -- ---- 两个阈值（从高到低判，等号生效）
  manual_review_threshold numeric(9,4) NOT NULL CHECK(manual_review_threshold>=0 AND manual_review_threshold<=100),
  hold_shipment_threshold numeric(9,4) NOT NULL CHECK(hold_shipment_threshold>manual_review_threshold AND hold_shipment_threshold<=100),

  -- ---- 下单频次（真 SQL，同买家同时间窗内的已提交订单笔数）
  frequency_window_hours int NOT NULL CHECK(frequency_window_hours>0),
  frequency_max_orders int NOT NULL CHECK(frequency_max_orders>0),

  -- ---- 设备与 IP 关联（租户维护信号，计数值打满这一档得满分）
  device_link_max_links int NOT NULL CHECK(device_link_max_links>0),

  -- ---- 金额异常（真 SQL，与同币种历史均单的比值）
  -- 比值 = 1.0000 就是「和平时一样」，所以满分档必须 > 1，否则除零。
  -- 「多少次算异常」是运营口径：3 倍可能是刷单，10 倍可能只是买了台冰箱。
  amount_anomaly_ratio numeric(9,4) NOT NULL CHECK(amount_anomaly_ratio>1.0000 AND amount_anomaly_ratio<=100),
  -- 至少要多少笔同币种历史单才允许比：只有一单历史时「均单」就是那一单本身，
  -- 比值恒为 1，判它异常或正常都是噪声。
  amount_anomaly_min_orders int NOT NULL CHECK(amount_anomaly_min_orders>=2),

  -- ---- 地址集中度（真 SQL，同收货地址指纹同时间窗内的订单笔数）
  address_concentration_window_hours int NOT NULL CHECK(address_concentration_window_hours>0),
  -- 下限 2：一单寄到一个地址是常态，判它「集中」没有任何意义。
  address_concentration_max_orders int NOT NULL CHECK(address_concentration_max_orders>1),

  -- ---- 信号新鲜度（相对评估基准时刻 = 订单 submitted_at）
  signal_max_age_hours int NOT NULL CHECK(signal_max_age_hours>0),

  -- ---- 缺信号怎么办（**必须显式选择，不给默认**）
  -- SCORE_KNOWN_ONLY   ：按已有维度**归一化**后打分（缺失维度不贡献，权重按比例摊回）
  -- BLOCK_EVALUATION   ：任一维度缺信号就不出分，只给「建议人工」
  -- 这两者差着一个真实的运营后果（前者会放行，后者会攒人工队列），所以由租户选。
  missing_signal_policy varchar(24) NOT NULL CHECK(missing_signal_policy IN ('SCORE_KNOWN_ONLY','BLOCK_EVALUATION')),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(weight_buyer_history+weight_device_ip_link+weight_order_frequency
        +weight_amount_anomaly+weight_address_concentration = 1.0000),
  CHECK(updated_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 2. 风险信号（租户可维护；**每行必须说清来源与采集时刻**）
-- ---------------------------------------------------------------------------
-- 这张表是「15 客户 / 16 合规未交付」的**诚实替身**，不是占位：
-- 它是真实的业务表、真实的租户维护数据、真实参与打分。
-- 与硬编码常量的区别在于：换来源只要改 signal_source 一列。
CREATE TABLE order_risk_signal (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 信号码 → 维度是**强绑定**的，用 CHECK 钉死而不是靠服务层自觉。
  -- 退款率不可能是地址集中度；绑定错了会让某一维凭空拿到另一维的分。
  signal_code varchar(32) NOT NULL CHECK(signal_code IN (
      'REFUND_RATE','DISPUTE_RATE',                        -- → BUYER_HISTORY（15 客户）
      'DEVICE_IP_LINK_COUNT','SHARED_DEVICE_COUNT','SHARED_IP_COUNT' -- → DEVICE_IP_LINK（16 合规）
  )),
  dimension varchar(32) NOT NULL CHECK(dimension IN (
      'BUYER_HISTORY','DEVICE_IP_LINK','ORDER_FREQUENCY',
      'AMOUNT_ANOMALY','ADDRESS_CONCENTRATION'
  )),
  -- 比率类：0..1。计数类不设上界（真绑上界在插入分支的 CHECK 里，见下）。
  measurement_unit varchar(8) NOT NULL CHECK(measurement_unit IN ('RATE','COUNT')),

  -- 这条信号**说的是谁**。两把钥匙都支持，因为两个来源方给的键不一定一样：
  --   CUSTOMER_PARTY    —— catalog 03 客户档案的逻辑 ID（sales_order_party_snapshot.customer_party_id）
  --   BUYER_FINGERPRINT —— 本服务用收货地址快照现算的 md5（与 V144 合单同一口径）
  -- 两种都匹配不上的信号不会被读进来（评估会判 MISSING_SIGNAL 并点名缺哪一类）。
  subject_kind varchar(24) NOT NULL CHECK(subject_kind IN ('CUSTOMER_PARTY','BUYER_FINGERPRINT')),
  subject_key varchar(200) NOT NULL CHECK(btrim(subject_key)<>''),

  measured_value numeric(19,6) NOT NULL CHECK(measured_value>=0),

  -- **来源枚举**（照抄 V116 stock_source / cost_source 的既有解法）：
  --   TENANT_MAINTAINED           —— 租户自己登记的（当前唯一可写来源）
  --   MODULE_15_CUSTOMER_PROFILE  —— 15 客户的行为画像，**未交付**，由将来的同步器写
  --   MODULE_16_RISK_LINKAGE      —— 16 合规的关联风险，**未交付**
  -- 写成 MODULE_* 时必须带 source_reference 指明是哪份来源的哪一段，
  -- 否则「这行是同步器写的」就成了无法追溯的口头声明。
  signal_source varchar(32) NOT NULL CHECK(signal_source IN (
      'TENANT_MAINTAINED','MODULE_15_CUSTOMER_PROFILE','MODULE_16_RISK_LINKAGE'
  )),
  source_reference varchar(128) NOT NULL DEFAULT '',

  -- **采集时刻**：信号是什么时候测出来的，不是什么时候登记进来的。
  -- 「采集时刻」填 now() 会让三个月前的画像被当成今天的 —— 那正是误伤复购客户的成因。
  captured_at timestamptz NOT NULL,

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,signal_code,subject_kind,subject_key,captured_at),

  -- 比率类信号的单位必须与码相符；计数类必须 > 0（「关联数 = 0」应当**不登记这一行**，
  -- 而不是登记一个 0 —— 后者会让分数看起来像是认真量过的结果）。
  CHECK((signal_code IN ('REFUND_RATE','DISPUTE_RATE')) = (measurement_unit='RATE')),
  CHECK(measurement_unit='RATE' OR measured_value>0),
  CHECK(measurement_unit<>'RATE' OR measured_value<=1),
  -- 码与维度的绑定
  CHECK((signal_code IN ('REFUND_RATE','DISPUTE_RATE')) = (dimension='BUYER_HISTORY')),
  CHECK((signal_code IN ('DEVICE_IP_LINK_COUNT','SHARED_DEVICE_COUNT','SHARED_IP_COUNT'))
        = (dimension='DEVICE_IP_LINK')),
  -- 键的形状：uuid 文本 / 32 位十六进制 md5。写错形状在这里就被拒，不等到评估时才发现对不上。
  CHECK(subject_kind<>'CUSTOMER_PARTY'
        OR subject_key ~* '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'),
  CHECK(subject_kind<>'BUYER_FINGERPRINT' OR subject_key ~* '^[0-9a-f]{32}$'),
  CHECK(signal_source='TENANT_MAINTAINED' OR btrim(source_reference)<>'')
);

-- ---------------------------------------------------------------------------
-- 3. 风控评估（头：一张订单一次打分）
-- ---------------------------------------------------------------------------
CREATE TABLE order_risk_assessment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 复合外键指到本服务自己的 sales_order：同服务同库不是跨服务读，
  -- 与 V144 对 sales_order 的复合外键同一手法。订单被删则评估一并消失
  -- （评估是对那张单的判断，单没了判断也没有意义）。
  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL CHECK(btrim(order_no)<>''),
  -- 买家指纹冗余一份：列表按单号搜与按买家筛是真查询，
  -- 不让每一次列表都去 join sales_order 顺带 join 地址快照算 md5。
  -- ⚠️ 形状与 9.3.1.2 合单的 order_consolidation.buyer_fingerprint **同一口径**：
  --   'P:<catalog 客户档案 uuid>' —— 有客户档案逻辑 ID 时优先用它（换了地址也还是同一个人）
  --   'A:<md5(身份三件套)>'       —— 没有客户档案时的退回路径
  -- 带前缀不是装饰：两个来源的买家标识可能撞上，前缀让「它们是同一个人」有据可依。
  -- 而信号表 order_risk_signal.subject_key 存的是**裸值**（uuid 文本 / 32 位十六进制），
  -- 查信号时必须剥掉前缀 —— 见 OrderRiskService.subjectPredicate 的注释。
  buyer_fingerprint varchar(64) CHECK(buyer_fingerprint IS NULL
      OR buyer_fingerprint ~ '^(P:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|A:[0-9a-f]{32})$'),
  order_currency char(3) NOT NULL CHECK(order_currency ~ '^[A-Z]{3}$'),
  order_amount numeric(19,4) NOT NULL CHECK(order_amount>=0),
  order_submitted_at timestamptz NOT NULL,

  rule_id uuid NOT NULL,
  -- 规则版本快照：规则后来改了，这次评估当时按哪一版权重算的要留住，
  -- 否则「昨天为什么放行、今天为什么拒发」无法复算。
  rule_version bigint NOT NULL,
  -- 规则快照也留一份权重，理由同上：五维权重随规则走，逐维存。
  rule_name varchar(120) NOT NULL,

  -- **评估基准时刻**：恒等于订单的 submitted_at（第五节：确定性）。
  evaluated_reference_at timestamptz NOT NULL,

  -- 总分 0.0000..100.0000。
  -- ⚠️ BLOCK_EVALUATION 且确有维度缺失时**刻意留 NULL**：不出分比出一个残缺的分好，
  --   残缺的分会被下游当成「量过了，结论是低分」—— 那正是不该发生的误伤。
  risk_score numeric(9,4) CHECK(risk_score IS NULL OR (risk_score>=0 AND risk_score<=100)),

  -- **建议动作**与最终动作是两个字段，不是一个。
  recommended_action varchar(20) NOT NULL CHECK(recommended_action IN ('RELEASE','MANUAL_REVIEW','HOLD_SHIPMENT')),
  -- 第四节的第 (1) 层：非 RELEASE 恒需人工复核；RELEASE 恒不需。
  manual_review_required boolean NOT NULL,
  CHECK(manual_review_required = (recommended_action<>'RELEASE')),

  evaluation_status varchar(24) NOT NULL CHECK(evaluation_status IN ('SCORED','INSUFFICIENT_SIGNALS')),
  CHECK((evaluation_status='SCORED') = (risk_score IS NOT NULL)),
  -- 没出分时不可能「建议拒发」：没有分数就没有拒发依据，
  -- 只给「建议人工」这一档。
  CHECK(evaluation_status<>'INSUFFICIENT_SIGNALS' OR recommended_action='MANUAL_REVIEW'),

  -- 有几个维度没拿到可用数据（由触发器从组件行实算）。
  scored_dimension_count int NOT NULL CHECK(scored_dimension_count BETWEEN 0 AND 5),
  missing_dimension_count int NOT NULL CHECK(missing_dimension_count BETWEEN 0 AND 5),
  CHECK(scored_dimension_count+missing_dimension_count = 5),

  -- **最终动作**：只可能来自人工复核（第四节的第 (1)(2) 层）。
  -- NULL = 还没人复核。'SHIP' / 'REJECT' = 人已经给过结论。
  final_action varchar(16) CHECK(final_action IS NULL OR final_action IN ('SHIP','REJECT')),
  CHECK(final_action IS NULL OR manual_review_required),
  reviewed_at timestamptz,
  reviewed_by uuid,

  -- 触发器算完合计后打的时间戳：它同时是「本行是否已被算过」的标记
  -- 与提交期不变量的判据（见第五节末尾的延迟约束触发器）。
  totals_computed_at timestamptz,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE,
  -- ⚠️ 刻意**不**对 (tenant_id, order_id, evaluated_reference_at) 加唯一约束：
  -- 同一张单同一基准时刻**允许**存在多份评估，因为「之后补登了一条采集时刻早于订单的历史信号」
  -- 是一个合法的**新事实**，它该产生一条新的留痕而不是被静默丢弃。
  -- 确定性靠的是基准时刻钉死 + 同键信号取确定的那一条，不是靠禁止重复评估：
  -- 两次评估输入相同时，两个 risk_score 必然逐位相同（集成测试逐条断言这一点）。
  FOREIGN KEY(tenant_id,rule_id) REFERENCES order_risk_rule(tenant_id,id),
  CHECK((final_action IS NULL) = (reviewed_at IS NULL)),
  CHECK((reviewed_at IS NULL) = (reviewed_by IS NULL)),
  CHECK(totals_computed_at IS NULL OR totals_computed_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 4. 评估组件（**一行一个维度**，五个维度各一行，缺信号的维度也在）
-- ---------------------------------------------------------------------------
-- 刻意让缺失的维度也占一行（status != 'SCORED'、normalized = 0）：
-- 少一行的话，「这单为什么没按预期打分」就只能从总分反推，而总分不保留是谁缺席的。
CREATE TABLE order_risk_assessment_component (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  assessment_id uuid NOT NULL,

  dimension varchar(32) NOT NULL CHECK(dimension IN (
      'BUYER_HISTORY','DEVICE_IP_LINK','ORDER_FREQUENCY',
      'AMOUNT_ANOMALY','ADDRESS_CONCENTRATION'
  )),
  status varchar(24) NOT NULL CHECK(status IN (
      'SCORED',                 -- 有可用数据，已计分
      'MISSING_SIGNAL',         -- 该维依赖的租户维护信号一条都没有
      'STALE_SIGNAL',           -- 有，但采集时刻超出 signal_max_age_hours
      'INSUFFICIENT_HISTORY',   -- 真 SQL 维：同币种/同买家历史笔数不够
      'NOT_APPLICABLE'          -- 真 SQL 维：订单形态上不适用（如无收货地址快照）
  )),

  -- 权重恒等于规则里该维的权重（**照抄不写零**）：
  -- 缺失维度也带着权重，是为了让「这一维本来占多少」在明细里看得见。
  weight numeric(9,4) NOT NULL CHECK(weight>=0 AND weight<=1),
  -- 原始值：比率 0..1、计数为整数、金额异常为倍数；不可用时 NULL（不是 0）。
  raw_value numeric(19,6),
  normalized_score numeric(9,4) NOT NULL CHECK(normalized_score>=0 AND normalized_score<=100),
  -- 贡献 = 权重 × 归一化分。缺失维度贡献为 0，但上面的 normalized 也必须是 0。
  contribution numeric(9,4) NOT NULL,
  CHECK(abs(contribution - weight*normalized_score) <= 0.0001),
  CHECK(status='SCORED' OR (normalized_score=0 AND contribution=0)),

  -- 该维实际用到的信号来源与采集时刻（真 SQL 维写 DERIVED_FROM_ORDERS）。
  signal_source varchar(32) NOT NULL CHECK(signal_source IN (
      'TENANT_MAINTAINED','MODULE_15_CUSTOMER_PROFILE','MODULE_16_RISK_LINKAGE',
      'DERIVED_FROM_ORDERS'
  )),
  signal_captured_at timestamptz,
  -- 缺信号时必须说清缺的是什么、为什么不可用（第五节：不能让人反推）。
  explanation text NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,assessment_id,dimension),
  FOREIGN KEY(tenant_id,assessment_id) REFERENCES order_risk_assessment(tenant_id,id) ON DELETE CASCADE,
  -- 真 SQL 维**没有**采集时刻（它不是量出来的信号，是查出来的数）。
  -- ⚠️ 刻意**单向**：反过来写成「非 DERIVED_FROM_ORDERS 就必须有 captured_at」是错的 ——
  -- 一维因为「租户根本没登记这个信号」而不可用时，本来就没有任何采集时刻，
  -- 那条 CHECK 会把「如实报告缺信号」这个最正确的行为挡在门外。
  CHECK(signal_source<>'DERIVED_FROM_ORDERS' OR signal_captured_at IS NULL)
);

-- ---------------------------------------------------------------------------
-- 5. 人工复核（**风控分数唯一的出口**）
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「风控分数不能单独作为拒绝依据，需人工复核，避免误伤正常复购客户」
-- 在这一张表上落地：最终动作 SHIP / REJECT **只能**由这一行的存在推出来。
-- 一条分数无论多高，都产不出 final_action —— 必须先有人在这里落一笔。
CREATE TABLE order_risk_review (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  assessment_id uuid NOT NULL,
  decision varchar(20) NOT NULL CHECK(decision IN ('SHIP','REJECT','DEFERRED','ESCALATED')),
  -- 复核人：已认证成员关系 ID（来自 identity，不是请求头里的任何东西）。
  reviewed_by uuid NOT NULL,
  reviewed_at timestamptz NOT NULL,
  reason_code varchar(32) NOT NULL CHECK(reason_code IN (
      'CONFIRMED_RISK',      -- 复核后确认有问题（真按拒发处理）
      'FALSE_POSITIVE',      -- 复核后确认是误伤（正常复购客户最常见的一档）
      'PENDING_EVIDENCE',    -- 还需材料，转延期
      'ESCALATED_COMPLIANCE' -- 升级给合规人工（16 未交付期间的兜底出口）
  )),
  note text NOT NULL DEFAULT '',

  UNIQUE(tenant_id,id),
  -- 一份评估只复核一次；要改结论就重新评估一次（那会带上新的信号与新的人工）。
  -- 不做「先拒后放」的覆写：风控留痕被覆写就等于没有留痕。
  UNIQUE(tenant_id,assessment_id),
  FOREIGN KEY(tenant_id,assessment_id) REFERENCES order_risk_assessment(tenant_id,id) ON DELETE CASCADE
  -- DEFERRED 与 ESCALATED **不改**评估的 final_action：前者还在等材料，后者不在本服务里结案。
  -- 只认 SHIP / REJECT 两种终局结论 —— 「升级给合规」被当成「已拒发」是一起真实的客诉来源。
  -- ⚠️ 刻意**没有 created_at**：复核行的诞生时刻就是 reviewed_at，
  -- 多一列 now() 默认值的「创建时间」既冗余又与 reviewed_at 打架
  -- （复核一条三个月前的历史评估时，两列必然对不上，然后被一条 CHECK 挡住）。
);

-- ===========================================================================
-- 六、触发器
-- ===========================================================================
-- 本文件所有约束触发器在写行时**一律回表读当前行**，不使用 NEW 的可变列：
-- 服务层的写入顺序是「先插头 → 写组件行 → 回填头」，延迟到提交时那次 INSERT
-- 拿着的仍是半成品的 NEW；用 NEW 每次必报。（与 V92 guard_routing_decision_switch、
-- V144 guard_order_consolidation_totals 同一个坑、同一个解法。）

-- ---------------------------------------------------------------------------
-- 6.1 合计由 DB 实算：服务层**不**自己写总分与建议动作
-- ---------------------------------------------------------------------------
-- 为什么必须在 DB 侧算：服务层算一遍、触发器再算一遍，早晚会有一次两边不一致，
-- 而「评估记录上写着一个总分，它与五个组件算出来的不一样」是无法排查的
-- （到底是服务层写错还是触发器算错？）。合成一处只有一个真相。
-- 判定顺序（从高到低，等号生效，与第三节口径逐字对应）：
--   一个维度都没评上 / BLOCK_EVALUATION 且有缺失 → 不出分，建议人工
--   否则 总分 ≥ hold_shipment_threshold           → HOLD_SHIPMENT
--   否则 总分 ≥ manual_review_threshold          → MANUAL_REVIEW
--   否则                                        → RELEASE
-- ⚠️ 归一化只在**已评分维度**之间做：缺失维度的权重不参与分母，
--   否则「五个维度缺两个」会让总分被稀释成一个看起来很安全的低分 ——
--   那正是误伤之外更糟的一种错误：**放行了一个没人看过的单**。
-- ⚠️ SECURITY DEFINER 是**必需的**，不是装饰：第八节对 risk_score / evaluation_status /
--   recommended_action / manual_review_required / final_action 做了列级 UPDATE REVOKE，
--   非 SECURITY DEFINER 的触发器会以调用者（trade_app）的权限执行那个 UPDATE，
--   于是每次评估都撞 "permission denied"。加上它之后触发器以迁移角色（表 owner）执行 ——
--   而 FORCE ROW LEVEL SECURITY 对 owner 同样生效，RLS 一道都不少。
CREATE OR REPLACE FUNCTION order_risk_apply_totals(p_assessment uuid, p_tenant uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  v_assessment order_risk_assessment%ROWTYPE;
  v_total_components int;
  v_scored_count int;
  v_missing_count int;
  v_weight_scored numeric(19,10);
  v_contribution_scored numeric(19,10);
  v_score numeric(9,4);
  v_action varchar(20);
  v_policy varchar(24);
  v_review_threshold numeric(9,4);
  v_hold_threshold numeric(9,4);
BEGIN
  SELECT * INTO v_assessment FROM order_risk_assessment
   WHERE id = p_assessment AND tenant_id = p_tenant;
  IF NOT FOUND THEN
    RAISE EXCEPTION '风控评估 % 不存在或不属于本租户', p_assessment USING ERRCODE='23503';
  END IF;

  -- 回表读组件行，而不是相信调用方传进来的任何数字。
  SELECT count(*),
         count(*) FILTER (WHERE c.status='SCORED'),
         coalesce(sum(c.weight) FILTER (WHERE c.status='SCORED'),0),
         coalesce(sum(c.contribution) FILTER (WHERE c.status='SCORED'),0)
    INTO v_total_components, v_scored_count, v_weight_scored, v_contribution_scored
    FROM order_risk_assessment_component c
   WHERE c.assessment_id = p_assessment AND c.tenant_id = p_tenant;

  IF v_total_components = 0 THEN
    RETURN;
  END IF;
  v_missing_count := v_total_components - v_scored_count;

  SELECT r.missing_signal_policy, r.manual_review_threshold, r.hold_shipment_threshold
    INTO v_policy, v_review_threshold, v_hold_threshold
    FROM order_risk_rule r
   WHERE r.id = v_assessment.rule_id AND r.tenant_id = p_tenant;

  IF v_policy IS NULL THEN
    RAISE EXCEPTION '风控规则 % 不存在或不属于本租户：评估结论无法计算', v_assessment.rule_id
      USING ERRCODE='23503';
  END IF;

  IF v_scored_count = 0 OR (v_policy = 'BLOCK_EVALUATION' AND v_missing_count > 0) THEN
    -- 出不了分就**不出分**：risk_score 留 NULL，理由写进每一行组件的 explanation。
    UPDATE order_risk_assessment
       SET risk_score = NULL,
           evaluation_status = 'INSUFFICIENT_SIGNALS',
           recommended_action = 'MANUAL_REVIEW',
           manual_review_required = true,
           scored_dimension_count = v_scored_count,
           missing_dimension_count = v_missing_count,
           totals_computed_at = now()
     WHERE id = p_assessment AND tenant_id = p_tenant;
    RETURN;
  END IF;

  -- round(numeric, 4) 是四舍五入远离零，与服务层 BigDecimal HALF_UP 到 4 位同一口径。
  v_score := round(v_contribution_scored / v_weight_scored, 4);

  IF v_score >= v_hold_threshold THEN
    v_action := 'HOLD_SHIPMENT';
  ELSIF v_score >= v_review_threshold THEN
    v_action := 'MANUAL_REVIEW';
  ELSE
    v_action := 'RELEASE';
  END IF;

  UPDATE order_risk_assessment
     SET risk_score = v_score,
         evaluation_status = 'SCORED',
         recommended_action = v_action,
         -- 蓝图加粗句的落点之一：建议拒发恒带「需人工复核」，
         -- 建议放行恒不带 —— 由 CHECK (manual_review_required = (recommended_action<>'RELEASE'))
         -- 从表级再兜一遍，服务层写错也过不去。
         manual_review_required = (v_action <> 'RELEASE'),
         scored_dimension_count = v_scored_count,
         missing_dimension_count = v_missing_count,
         totals_computed_at = now()
   WHERE id = p_assessment AND tenant_id = p_tenant;
END $$;

-- 合计由**第五个组件行落下时**触发，而不是头行 INSERT 时：
-- 服务层的写入顺序是「先插头 → 再写 5 行组件」，头插入那一刻组件一张都没有，
-- 在头上挂触发器只能算出「0 个维度」，真正的算法永远跑不到。
CREATE OR REPLACE FUNCTION guard_order_risk_component_row_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE v_total int;
BEGIN
  SELECT count(*) INTO v_total
    FROM order_risk_assessment_component c
   WHERE c.assessment_id = NEW.assessment_id AND c.tenant_id = NEW.tenant_id;
  -- ⚠️ 只在**满 5 行**时算合计，**不要**在每一行插入时就断言「必须已经是 5」：
  -- 服务层是分 5 条 INSERT 写组件的，前四行落下时 count 分别是 1..4，
  -- 在那里抛错等于让本模块自己的写入路径永远走不通。
  -- 「必须一行一个维度共 5 行」这条不变量的正确位置是**提交期**（6.2）：
  -- 组件写不满 5 行就提交的话，order_risk_apply_totals 一次都没跑过，
  -- totals_computed_at 仍是 NULL，延迟约束触发器会挡下它。
  IF v_total = 5 THEN
    PERFORM order_risk_apply_totals(NEW.assessment_id, NEW.tenant_id);
  END IF;
  RETURN NULL;
END $$;

CREATE TRIGGER order_risk_component_row_complete
  AFTER INSERT ON order_risk_assessment_component
  FOR EACH ROW EXECUTE FUNCTION guard_order_risk_component_row_complete();

-- ---------------------------------------------------------------------------
-- 6.2 提交期不变量：**没有算过合计的评估不许留在库里**
-- ---------------------------------------------------------------------------
-- 头先插、组件后写：服务层中途抛异常会整体回滚，那没问题；
-- 怕的是**有人绕开服务层直连写入**，留下一条 risk_score=0 / recommended_action='RELEASE'
-- 的头 —— 列表页读到的就是「放行」。所以延迟到提交再查一次 totals_computed_at。
CREATE OR REPLACE FUNCTION guard_order_risk_assessment_totals_settled() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_totals timestamptz;
BEGIN
  -- ⚠️ 必须**回表读当前状态**，不能看 NEW。
  --   延迟约束触发器把事件排队时，队里存的是 INSERT 那一刻的 NEW，
  --   那时 totals_computed_at 当然是 NULL（合计要到第 5 个组件落下才回填）；
  --   直接看 NEW 会让每一次**正常**的评估都在提交时误报「从没被算过合计」。
  --   （本文件开头那条「一律回表读当前行」的纪律，这里正是最容易漏掉的一处。）
  SELECT a.totals_computed_at INTO v_totals
    FROM order_risk_assessment a
   WHERE a.id = NEW.id AND a.tenant_id = NEW.tenant_id;
  -- 行已被删（订单被删 → 评估级联删掉）：没有行可断言，放行。
  IF FOUND AND v_totals IS NULL THEN
    RAISE EXCEPTION '风控评估 % 从未被算过合计（risk_score=%s / recommended_action=%s）：组件行没写完就落库会让这单被读成「放行」',
      NEW.id, NEW.risk_score, NEW.recommended_action USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER order_risk_assessment_totals_settled
  AFTER INSERT OR UPDATE ON order_risk_assessment
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_risk_assessment_totals_settled();

-- ---------------------------------------------------------------------------
-- 6.3 组件不可改写：评估是「当时按那一版规则算出来的」凭据
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_risk_component_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：改一格分数就再也复算不出当时为什么放行',
    TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_risk_component_append_only BEFORE UPDATE OR DELETE ON order_risk_assessment_component
  FOR EACH ROW EXECUTE FUNCTION guard_order_risk_component_append_only();

-- ---------------------------------------------------------------------------
-- 6.4 最终动作必须有人工复核背书（第四节的第 (2) 层）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_risk_final_action() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.final_action IS NOT NULL THEN
    IF NOT NEW.manual_review_required THEN
      RAISE EXCEPTION '风控评估 % 建议放行却带着最终动作 %：建议放行的单没有「拒发」这回事',
        NEW.id, NEW.final_action USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM order_risk_review r
       WHERE r.assessment_id = NEW.id AND r.tenant_id = NEW.tenant_id
         AND r.decision = NEW.final_action
    ) THEN
      -- 这是蓝图加粗句最硬的一道闸：分数自己填不出 final_action。
      -- 写的人以为可以，结果数据库不给 —— 「误伤正常复购客户」从这里被挡住。
      RAISE EXCEPTION '风控评估 % 的最终动作 % 必须有对应的人工复核记录（order_risk_review）：风控分数不能单独作为拒绝依据',
        NEW.id, NEW.final_action USING ERRCODE='23514';
    END IF;
    -- 复核人 / 复核时刻**取复核行上的值**，不写 now()：
    -- 复核一份历史评估时 now() 会与 order_risk_review.reviewed_at 对不上，
    -- 两份留痕说同一个决定的时刻不一样，事后没法对账。
    SELECT r.reviewed_by, r.reviewed_at INTO NEW.reviewed_by, NEW.reviewed_at
      FROM order_risk_review r
     WHERE r.assessment_id = NEW.id AND r.tenant_id = NEW.tenant_id
       AND r.decision = NEW.final_action;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_risk_final_action_needs_review
  BEFORE INSERT OR UPDATE ON order_risk_assessment
  FOR EACH ROW WHEN (NEW.final_action IS NOT NULL)
  EXECUTE FUNCTION guard_order_risk_final_action();

-- ---------------------------------------------------------------------------
-- 6.5 人工复核只允许挂在「需人工复核」的评估上（第四节的第 (3) 层）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_risk_review_requires_manual() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_manual boolean; v_action varchar(20);
BEGIN
  SELECT a.manual_review_required, a.recommended_action
    INTO v_manual, v_action
    FROM order_risk_assessment a
   WHERE a.id = NEW.assessment_id AND a.tenant_id = NEW.tenant_id;
  IF v_manual IS NULL THEN
    RAISE EXCEPTION '风控评估 % 不存在或不属于本租户', NEW.assessment_id USING ERRCODE='23503';
  END IF;
  IF NOT v_manual THEN
    RAISE EXCEPTION '风控评估 % 建议 % ，无需人工复核：给一张放行的单补一条复核记录是反方向的越权',
      NEW.assessment_id, v_action USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_risk_review_needs_manual
  BEFORE INSERT ON order_risk_review
  FOR EACH ROW EXECUTE FUNCTION guard_order_risk_review_requires_manual();

-- 复核落下后回填评估的 final_action（**只对 SHIP / REJECT 生效**，
-- DEFERRED / ESCALATED 不改结论 —— 「升级给合规」不等于「已拒发」）。
CREATE OR REPLACE FUNCTION guard_order_risk_review_apply() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
  IF NEW.decision IN ('SHIP','REJECT') THEN
    UPDATE order_risk_assessment
       SET final_action = NEW.decision
     WHERE id = NEW.assessment_id AND tenant_id = NEW.tenant_id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER order_risk_review_apply
  AFTER INSERT ON order_risk_review
  FOR EACH ROW EXECUTE FUNCTION guard_order_risk_review_apply();

-- 复核记录同样不可改写：「先放后拒」的两次结论如果允许覆写，风控留痕就不存在了。
CREATE OR REPLACE FUNCTION guard_order_risk_review_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：复核结论被覆写就等于没有留痕',
    TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_risk_review_append_only BEFORE UPDATE OR DELETE ON order_risk_review
  FOR EACH ROW EXECUTE FUNCTION guard_order_risk_review_append_only();

-- ---------------------------------------------------------------------------
-- 6.6 规则变更有留痕
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_risk_rule_versioned() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.version = OLD.version THEN
    RAISE EXCEPTION '风控规则必须带 version 递增提交：评估记录引用的是 (rule_id, rule_version)，
两个同名规则会让「当时按哪一版权重算的」无法复算' USING ERRCODE='23514';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER order_risk_rule_versioned BEFORE UPDATE ON order_risk_rule
  FOR EACH ROW EXECUTE FUNCTION guard_order_risk_rule_versioned();

-- ===========================================================================
-- 七、索引
-- ===========================================================================
-- ① 打分读信号的主查询形状：(租户, 码, 键, 采集时刻倒序) 取「最新且未过期」那一条。
--    倒序建在采集时刻上，让 ORDER BY captured_at DESC LIMIT 1 走索引。
CREATE INDEX order_risk_signal_by_code_subject
  ON order_risk_signal(tenant_id, signal_code, subject_kind, subject_key, captured_at DESC, id DESC);
-- ② 「这个买家/客户名下都登记过哪些信号」—— 详情页按主体反查。
CREATE INDEX order_risk_signal_by_subject
  ON order_risk_signal(tenant_id, subject_kind, subject_key, captured_at DESC);
-- ③ 评估列表：按单取最新一次评估（详情页）。
CREATE INDEX order_risk_assessment_by_order
  ON order_risk_assessment(tenant_id, order_id, evaluated_reference_at DESC);
-- ④ 人工队列：本服务里最常跑的一条查询 —— 「有多少建议人工/建议拒发还没人复核」。
CREATE INDEX order_risk_assessment_pending_review
  ON order_risk_assessment(tenant_id, final_action, evaluated_reference_at DESC)
  WHERE manual_review_required AND final_action IS NULL;
-- ⑤ 按建议动作筛列表页。
CREATE INDEX order_risk_assessment_by_action
  ON order_risk_assessment(tenant_id, recommended_action, evaluated_reference_at DESC);
-- ⑥ 按买家筛（「这个买家的历史评估」）。
CREATE INDEX order_risk_assessment_by_buyer
  ON order_risk_assessment(tenant_id, buyer_fingerprint, evaluated_reference_at DESC);
-- ⑦ 组件与复核按父行读明细。
CREATE INDEX order_risk_component_by_assessment
  ON order_risk_assessment_component(tenant_id, assessment_id);
CREATE INDEX order_risk_review_by_assessment
  ON order_risk_review(tenant_id, assessment_id);

-- ===========================================================================
-- 八、多租户：RLS + 授权（与 trade V92 第 9 节、V144 第 10 节同一套写法）
-- ===========================================================================
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：打分查询是「同一买家」「同一地址」这种**跨订单的宽条件**，
--   漏掉租户谓词就是**跨租户归因** —— 两家互不相识的公司会共用一个风险分，
--   A 家的风控结论会决定 B 家的货发不发，而 B 家连自己被扣分都不知道。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_risk_rule','order_risk_signal','order_risk_assessment',
    'order_risk_assessment_component','order_risk_review'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_risk_rule,order_risk_signal,order_risk_assessment,
  order_risk_assessment_component,order_risk_review TO trade_app;
-- 授权在前、收回在后（V144 第 9/10 节的顺序）：组件与复核是留痕，不可改不可删。
REVOKE UPDATE,DELETE ON order_risk_assessment_component FROM trade_app;
REVOKE UPDATE,DELETE ON order_risk_review FROM trade_app;
-- 合计与最终动作只能由触发器写：运行角色改不动这两个值。
REVOKE UPDATE(risk_score, evaluation_status, recommended_action, manual_review_required,
              scored_dimension_count, missing_dimension_count, totals_computed_at, final_action)
  ON order_risk_assessment FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**（sales_order / sales_order_party_snapshot /
-- sales_order_line / order_address_snapshot / order_charge 全部只读），
-- 跨车道 DDL 回归面 = 0。
