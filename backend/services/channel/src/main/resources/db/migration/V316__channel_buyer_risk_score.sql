-- ===========================================================================
-- 16.5.1.2 风险评分 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 210-216 行）：
--   ### 16.5.1.2 风险评分
--   - 职责：计算买家风险分：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，
--           输出评分与建议动作。
--   - 输入：买家行为数据；风险特征规则；权重配置。
--   - 输出：风险评分 + 特征解释。
--   - 关系：上游 = 15 客户、11 售后、9 订单；下游 = 16.5.1.1、9.2.2.2。
--           **评分要能解释**（为什么这个买家 80 分），黑盒分数客服无法向平台申诉。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本表存在的理由：**评分必须逐条可解释**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「评分要能解释」是本模块的**核心约束**，它的物理落点就是三张表的关系：
--   * `buyer_risk_score`   —— 一次评分的**头**：分数、建议动作、判定状态、规则版本快照、
--                            判定基准时刻、扫描口径（订单数 / 退款订单数 / 扫描到的订单数）。
--   * `buyer_risk_feature` —— 这次评分的**逐特征明细**：特征码、原始值、标准化值、权重、
--                            **贡献分**、人话理由、量不到时的归属模块。
--   * `buyer_risk_signal_writeback` —— 回写 9.2.2.2 风控信号的**凭据**（成功 / 失败 / 跳过）。
-- 「为什么这个买家 80 分」必须能**逐条**答出来 ⇒ 没有 feature 行就没有分数：
--   分数是 feature 行的加权和，feature 行是 append-only 的，事后改不动。
--
-- 🔴 **append-only 是本模块的硬纪律**（与 16.2.2.1 合规预检的 V197 同一手法）：
--   * 权重改了**不追溯**改变历史评分 —— 旧行的 `weight` / `contribution` / `risk_score` 原样留着，
--     新的评分按新权重重算并另起一行。
--   * 一次评分不能被 UPDATE 改成另一个分数（那等于事后改写判定凭据）。
--   * 所以本迁移给三张「凭据表」都加了 BEFORE UPDATE OR DELETE 触发器，直接 RAISE。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 上游取证结论（本仓实查，不是推测）
-- ---------------------------------------------------------------------------
-- 1) **本仓没有「买家档案」**。crm 服务自己的迁移注释写明（V292__crm_buyer_email.sql:130、
--    V299__crm_customer_contact.sql:133）：crm **没有买家档案**，它的 `customer_ref` 只是
--    一条**逻辑引用**，不是买家主数据。
--    ⇒ 本模块**不把 crm 的 customer_ref 当买家主键去 join**，crm 的接触记录最多当辅助线索。
--
-- 2) **两套互不兼容的买家标识**，本模块**明确选一套并写进能力声明**：
--    * `customer_party_id`（catalog 的 party.id，uuid，**B2B 主体**口径）
--    * `buyer_key` / `buyer_fingerprint`（**收件人身份三件套的 md5**，32 位十六进制）
--    **本模块用 `buyer_key`**：平台 B2C 买家绝大多数**没有** party_id
--    （V149__trade_order_review_rules.sql:241-246 有说明），用 customer_party_id 会把
--    绝大多数买家漏掉。口径与 trade 的
--    `order_review_buyer_profile.buyer_key` **逐字同一公式**：
--        buyer_key = md5(utf8(recipient_name || E'\x1f' || recipient_phone || E'\x1f' || recipient_email))
--    （分隔符是**不可见字符** E'\x1f' 不是 '|'，理由见 V144__trade_order_consolidation.sql 文件头：
--     收件人字段由运营自由录入，'|' 会让两组不同字段拼出同一个串 —— 那是一次**跨买家的错误合并**。）
--
-- 3) 🔴 **纠纷率没有权威 join 路径 —— 这是真实缺口，不许绕过去**：
--    trade 的 `platform_dispute`（V265__platform_dispute.sql:128）**既没有 order_id、
--    也没有任何买家标识**，只有一个自由文本 `platform_order_no`。
--    ⇒ **不靠字符串模糊匹配去凑纠纷率**（那会算出看似有数、实则不可信的分数），
--    **也不用 0 冒充「纠纷率 0」**（那等于把「没数据」说成「这买家很干净」）。
--    本模块把 `DISPUTE_HISTORY` 维度标成 **量不到**（`available=false` + 原因 + 归属模块），
--    **不参与加权**（或按租户选的缺信号口径处理），并在结论里显式写「纠纷率未计入」。
--    归属模块 = 11.4.1.1（平台纠纷）：那里有纠纷，但**没有买家/订单 join 键**，
--    补上 join 键之后本维度立刻可算 —— 本表已把权重位留好，届时不用改结构。
--
-- 4) **11 售后侧可算的是「欺诈退货」**：`aftersales_case.reason_code` 的受控值域里
--    有 `FRAUD_RETURN`（V156__trade_aftersales_case_list.sql），它带 order_id ⇒ 可 join。
--    本模块把它并进 `FRAUD_PROFILE` 维度（不是「纠纷率」那一维 —— case_type 的受控值域里
--    没有「纠纷」，RETURN_REFUND/REFUND_ONLY/EXCHANGE/REISSUE/REPAIR 都不是平台纠纷）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 表命名空间隔离（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.5.1.2）⇒ `buyer_risk_*`。
--   与既有命名空间**彼此不同**：trade 库的 `order_risk_*`（9.2.2.2）、`order_review_buyer_profile`
--   （9.2.1.2）、crm 库的 `crm_*`、channel 库的 `compliance_rule_*` / `compliance_intercept_*` /
--   `epr_*` / `ip_risk_*` / `tax_registration_*`。
--   ⇒ 本迁移**不建** `order_risk_*` 同名表，也**不 ALTER** 任何既有表。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 零外键（与 channel V300 / V308 / V312 同一纪律）
-- ---------------------------------------------------------------------------
-- channel 的既有集成测试用 `truncate <表清单>` 清库，外键会让 TRUNCATE 报 23503。
-- 所以本迁移**不建任何外键**，跨表引用（feature → score）改由 BEFORE INSERT 触发器校验。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V197 / V300 / V308 / V312 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **只带 isolation 策略，不带 data_scope（RESTRICTIVE）策略**：
--   买家是**租户级主体**，不是某家店 / 某个部门的资产。硬套店铺收敛会让
--   「这个买家在跨境店铺下的历史风险」在只有 SELF 范围的成员面前凭空消失。
--   （与 V312 的 `subject_type='LEGAL_ENTITY'` 行同一处理：法人主体行只带 isolation。）
--
-- ===========================================================================
-- 一、buyer_risk_rule · 一租户一条权重与阈值配置
-- ===========================================================================
-- 🔴 **权重合计由 DB 层钉死 = 1.0000**（与 trade 的 order_risk_rule V150:179 同一做法）：
--   改权重忘了配平，在**写库那一刻**就被拒，而不是上线后让分数静默偏移。
--
-- 🔴 **缺信号口径不给默认值**（照 order_risk_rule.missing_signal_policy 的做法）：
--   SCORE_KNOWN_ONLY —— 按已有维度归一化后打分（缺失维度不贡献，权重按比例摊回）
--   BLOCK_EVALUATION —— 任一维度缺信号就**不出分**，只给「建议人工」
--   两者差着一个真实的运营后果（前者会放行，后者会攒人工队列），所以由租户选。
CREATE TABLE buyer_risk_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_name varchar(120) NOT NULL CHECK(btrim(rule_name)<>''),

  -- ---- 五个维度的权重（合计必须 = 1.0000）----
  -- 🔴 **「关联账号」这一维刻意没有权重列**：16.5.2.2 关联风险**未交付**，
  --   本模块**不编造一张「关联账号」表**（那是别人的职责）。
  --   一个**永远量不到**的维度占着权重会把分数稀释 —— 量不到的维度不进分母
  --   （与 16.2.2.1 合规预检「不可知分量不计入分母」同一纪律）。
  --   等 16.5.2.2 落地时，它会带着自己的维度与权重在**后续迁移**里进来。
  weight_refund_history   numeric(9,4) NOT NULL CHECK(weight_refund_history   >= 0 AND weight_refund_history   <= 1),
  weight_dispute_history  numeric(9,4) NOT NULL CHECK(weight_dispute_history  >= 0 AND weight_dispute_history  <= 1),
  weight_fraud_profile    numeric(9,4) NOT NULL CHECK(weight_fraud_profile    >= 0 AND weight_fraud_profile    <= 1),
  weight_address_pattern  numeric(9,4) NOT NULL CHECK(weight_address_pattern  >= 0 AND weight_address_pattern  <= 1),
  weight_order_behavior   numeric(9,4) NOT NULL CHECK(weight_order_behavior   >= 0 AND weight_order_behavior   <= 1),

  -- ---- 缺信号口径（必须显式选，不给默认）----
  missing_signal_policy varchar(24) NOT NULL
    CHECK(missing_signal_policy IN ('SCORE_KNOWN_ONLY','BLOCK_EVALUATION')),

  -- ---- 建议动作阈值（0..100，hold 必须 >= manual_review）----
  manual_review_threshold numeric(9,4) NOT NULL CHECK(manual_review_threshold >= 0 AND manual_review_threshold <= 100),
  hold_threshold           numeric(9,4) NOT NULL CHECK(hold_threshold           >= 0 AND hold_threshold           <= 100),
  CHECK(hold_threshold >= manual_review_threshold),

  -- ---- 特征阈值（**租户可配**：不同品类、不同国家的风险口径不一样）----
  -- 退款率满档：退款订单占比达到这个值 ⇒ 该维度按满档计分。
  refund_rate_full_risk numeric(9,4) NOT NULL CHECK(refund_rate_full_risk > 0 AND refund_rate_full_risk <= 1),
  -- 已拉黑买家的欺诈特征分（拉黑是运营已经确认过的结论，权重最重）。
  fraud_blacklisted_score numeric(9,4) NOT NULL CHECK(fraud_blacklisted_score >= 0 AND fraud_blacklisted_score <= 1),
  -- 买家等级 = RISK 的欺诈特征分（登记过但还没到拉黑那一步）。
  fraud_risk_level_score numeric(9,4) NOT NULL CHECK(fraud_risk_level_score >= 0 AND fraud_risk_level_score <= 1),
  -- 「欺诈退货」售后单占比达到这个值 ⇒ 按满档计分。
  fraud_case_full_risk numeric(9,4) NOT NULL CHECK(fraud_case_full_risk > 0 AND fraud_case_full_risk <= 1),
  -- 地址分散度阈值：地址种类数 / 订单数 低于它 ⇒ 不产生地址分散风险。
  address_single_ratio_threshold numeric(9,4) NOT NULL
    CHECK(address_single_ratio_threshold >= 0 AND address_single_ratio_threshold < 1),
  -- 「短时多单」判定：窗口内订单数达到它就算异常；窗口下限 2（一单不构成多单）。
  order_burst_window_hours int NOT NULL CHECK(order_burst_window_hours > 0),
  order_burst_threshold int NOT NULL CHECK(order_burst_threshold > 1),
  -- 下单频率满档：每 30 天的订单数达到它就算异常。
  order_frequency_full_risk numeric(9,4) NOT NULL CHECK(order_frequency_full_risk > 0),
  -- 统计窗口：退款率 / 地址模式 / 下单行为只看最近多少天的订单。
  order_lookback_days int NOT NULL CHECK(order_lookback_days > 0),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 **一租户一条规则**（与 trade 的 order_risk_rule 同一口径）。
  UNIQUE(tenant_id),
  -- 🔴 权重合计钉死 1.0000
  CHECK(weight_refund_history + weight_dispute_history + weight_fraud_profile
        + weight_address_pattern + weight_order_behavior = 1.0000),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE buyer_risk_rule IS
  '16.5.1.2 · 买家风险评分规则（一租户一条）。五维权重合计由 DB CHECK 钉死 1.0000；'
  'missing_signal_policy 必须由租户显式选，不给默认值。'
  '「关联账号」维刻意没有权重列：16.5.2.2 未交付，一个永远量不到的维度占着权重会稀释分数。';

-- ===========================================================================
-- 二、buyer_risk_score · 一次评分的凭据（append-only）
-- ===========================================================================
CREATE TABLE buyer_risk_score (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 主体键：md5(收件人三件套)，与 trade 的 order_review_buyer_profile.buyer_key 同一公式。
  buyer_key char(32) NOT NULL CHECK(buyer_key ~ '^[0-9a-f]{32}$'),
  -- 可读快照：md5 谁也念不出来，客服要能对得上人。
  buyer_name  varchar(120) NOT NULL DEFAULT '',
  buyer_phone varchar(64)  NOT NULL DEFAULT '',
  buyer_email varchar(254) NOT NULL DEFAULT '',
  -- 租户自维护的买家画像等级（STANDARD/KEY/STRATEGIC/VIP/RISK）；**空串 = 未登记画像**，
  -- 不是 STANDARD（与 V149 的同一纪律：没有画像行 ⇒ 等级未知，兜底 STANDARD 会让「VIP 自动通过」放行全网买家）。
  buyer_level varchar(16) NOT NULL DEFAULT '',
  blacklisted boolean NOT NULL DEFAULT false,
  blacklist_reason varchar(400) NOT NULL DEFAULT '',

  -- ---- 评分结果 ----
  -- 🔴 **BLOCK_EVALUATION 且确有维度缺失时刻意留 NULL**：不出分比出一个残缺的分好 ——
  --   残缺的分会被下游当成「量过了，结论是低分」，那正是不该发生的误伤。
  risk_score numeric(9,4) CHECK(risk_score IS NULL OR (risk_score >= 0 AND risk_score <= 100)),
  evaluation_status varchar(24) NOT NULL CHECK(evaluation_status IN ('SCORED','INSUFFICIENT_SIGNALS')),
  -- 🔴 「建议动作」与「最终动作」是两个概念：本模块**只出建议**，不改订单状态（改订单是 9.2 的事）。
  recommended_action varchar(16) NOT NULL
    CHECK(recommended_action IN ('RELEASE','MANUAL_REVIEW','HOLD')),
  -- 有分 ⇒ 状态是 SCORED；没分 ⇒ 状态是 INSUFFICIENT_SIGNALS（一句话钉死，不许两头都占）。
  CHECK((evaluation_status = 'SCORED') = (risk_score IS NOT NULL)),

  -- 维度计数：已知（量到了并参与计分）与缺失（量不到）各多少。
  scored_dimension_count  int NOT NULL CHECK(scored_dimension_count  >= 0),
  missing_dimension_count int NOT NULL CHECK(missing_dimension_count >= 0),

  -- ---- 规则快照（事后改权重不追溯改历史分数，靠这三列）----
  rule_id uuid NOT NULL,
  rule_version bigint NOT NULL,
  rule_name varchar(120) NOT NULL,
  missing_signal_policy varchar(24) NOT NULL
    CHECK(missing_signal_policy IN ('SCORE_KNOWN_ONLY','BLOCK_EVALUATION')),

  -- ---- 判定基准时刻（一次评分一个基准）----
  evaluated_reference_at timestamptz NOT NULL,

  -- ---- 溯源口径（这一轮的统计范围，界面上要能说清）----
  -- 本轮扫到的订单数（trade 侧订单总数，按统计窗口过滤后）。
  orders_scanned int NOT NULL DEFAULT 0 CHECK(orders_scanned >= 0),
  -- 落在**这个买家**名下的订单数（按收货地址快照实算的 buyer_key 精确匹配）。
  order_count int NOT NULL DEFAULT 0 CHECK(order_count >= 0),
  -- 其中发起过退款的订单数。
  refunded_order_count int NOT NULL DEFAULT 0 CHECK(refunded_order_count >= 0),
  -- 统计窗口（天）。
  lookback_days int NOT NULL DEFAULT 0 CHECK(lookback_days >= 0),

  -- ---- 人话结论 ----
  conclusion varchar(800) NOT NULL DEFAULT '',
  note varchar(800) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 分数与动作自洽：HOLD 一定 ≥ MANUAL_REVIEW（阈值比较的结果）——
  -- 这条由服务层保证，这里只钉「没分 ⇒ 一定建议人工」。
  CHECK(risk_score IS NOT NULL OR recommended_action = 'MANUAL_REVIEW')
);
COMMENT ON TABLE buyer_risk_score IS
  '16.5.1.2 · 一次买家风险评分的凭据（append-only）。分数、建议动作、规则版本快照、'
  '判定基准时刻与统计口径一起钉住；权重改了不追溯改变历史评分。'
  'BLOCK_EVALUATION 下 risk_score 恒为 NULL —— 不用残缺的分冒充「量过了」。';

CREATE INDEX buyer_risk_score_by_buyer
  ON buyer_risk_score(tenant_id, buyer_key, created_at DESC);
CREATE INDEX buyer_risk_score_by_time
  ON buyer_risk_score(tenant_id, created_at DESC);
CREATE INDEX buyer_risk_score_by_status
  ON buyer_risk_score(tenant_id, evaluation_status, created_at DESC);

-- ===========================================================================
-- 三、buyer_risk_feature · 逐特征贡献明细（append-only）
-- ===========================================================================
-- 🔴 **「为什么这个买家 80 分」的物理落点**：一行一个特征，带原始值 / 标准化值 / 权重 /
--   贡献分 / 人话理由。缺了它，buyer_risk_score.risk_score 就是一个**无法申诉的黑盒分数**。
--
-- 🔴 **available=false ⇒ normalized_value / contribution 必须是 NULL**：
--   「量不到」不是 0 分（0 分会说成「这个买家很干净」）。DB 层用 CHECK 钉死这一条。
CREATE TABLE buyer_risk_feature (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 逻辑引用（不建外键，见文件头第四节）；由触发器校验它真实存在。
  score_id uuid NOT NULL,

  dimension_code varchar(32) NOT NULL CHECK(dimension_code IN (
      'REFUND_HISTORY','DISPUTE_HISTORY','FRAUD_PROFILE','ADDRESS_PATTERN',
      'ORDER_BEHAVIOR','RELATED_ACCOUNTS')),
  feature_code varchar(32) NOT NULL CHECK(btrim(feature_code) <> ''),
  feature_label varchar(60) NOT NULL CHECK(btrim(feature_label) <> ''),

  -- 该特征**量到了没有**。
  available boolean NOT NULL,

  -- 原始值（机器可读摘要，例如 "orders=5;refunded=2;rate=0.400000"）。
  raw_value varchar(600) NOT NULL DEFAULT '',
  -- 标准化到 0..1（1 = 风险最高）；量不到时是 NULL。
  normalized_value numeric(9,6) CHECK(normalized_value IS NULL OR (normalized_value >= 0 AND normalized_value <= 1)),
  -- 权重快照（该维度的权重，**从规则那一版抄下来的**，不是实时读规则）。
  weight numeric(9,4) NOT NULL CHECK(weight >= 0 AND weight <= 1),
  -- 贡献分 = 标准化值 × 权重 × 100（量不到时是 NULL）。
  contribution numeric(9,4) CHECK(contribution IS NULL OR (contribution >= 0 AND contribution <= 100)),
  -- 人话理由：这一条为什么是这个分。**不允许空** —— 没有理由的贡献分等于没有解释。
  reason varchar(800) NOT NULL CHECK(btrim(reason) <> ''),
  -- 量不到时归谁（模块 ID）；量到了时是空串。
  owner_module varchar(32) NOT NULL DEFAULT '',
  note varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 **「量不到」与「量为 0」是两件事**：量不到时标准化值与贡献分必须是 NULL。
  CHECK(available OR (normalized_value IS NULL AND contribution IS NULL)),
  -- 🔴 量到了就必须有标准化值。
  CHECK(NOT available OR normalized_value IS NOT NULL),
  -- 🔴 量不到时必须说清归谁（或至少说清为什么）—— owner_module 与 note 至少有一个非空。
  CHECK(available OR (btrim(owner_module) <> '' OR btrim(note) <> ''))
);
COMMENT ON TABLE buyer_risk_feature IS
  '16.5.1.2 · 逐特征贡献明细（append-only）。这一张表就是「评分要能解释」的物理落点：'
  '每个特征带原始值 / 标准化值 / 权重快照 / 贡献分 / 人话理由。'
  '量不到的维度 standardized_value 与 contribution 是 NULL，不是 0。';

CREATE INDEX buyer_risk_feature_by_score ON buyer_risk_feature(tenant_id, score_id);
CREATE INDEX buyer_risk_feature_by_code ON buyer_risk_feature(tenant_id, feature_code);

-- ===========================================================================
-- 四、buyer_risk_signal_writeback · 回写 9.2.2.2 风控信号的凭据（append-only）
-- ===========================================================================
-- 🔴 **回写是「尽力而为」，不许阻塞本模块交付**：
--   9.2.2.2 的 `POST /trade/orders/risk-validation/signals` 是**用户权限端点**
--   （要 trade.orders.update 权限、转发调用方自己的 bearer），**不是签名的服务间内部 API** ——
--   调用方是否真有那个 trade 权限**未经运行时验证**。
--   所以：**回写失败就记 FAILED + 人话原因，本次评分照常出分**，
--   能力声明与评分结论里如实写明「未回写」。绝不因为回写失败而让评分失败。
--
-- 🔴 **signal_source 只能用 TENANT_MAINTAINED**：
--   trade 的 order_risk_signal.signal_source 枚举里 `MODULE_15_CUSTOMER_PROFILE` 与
--   `MODULE_16_RISK_LINKAGE` 是**别的模块的预留槽**，占用它们等于抢别人的登记位。
--
-- 🔴 **只回写 REFUND_RATE / DISPUTE_RATE 两个码**：
--   trade 的 signal_code 枚举里**没有「买家综合分」这个码** ⇒ 综合分进不了 trade 的信号表。
--   综合分留在本服务自己的 buyer_risk_score 里（trade 没有接收它的端点，
--   本模块也**不改 trade 的枚举** —— 那是跨服务改已交付服务的 DDL 面）。
CREATE TABLE buyer_risk_signal_writeback (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 逻辑引用（不建外键），指向这一次评分。
  score_id uuid NOT NULL,

  feature_code varchar(32) NOT NULL CHECK(feature_code IN ('REFUND_RATE','DISPUTE_RATE')),
  -- trade 侧的信号码。
  signal_code varchar(32) NOT NULL CHECK(signal_code IN ('REFUND_RATE','DISPUTE_RATE')),
  subject_kind varchar(24) NOT NULL DEFAULT 'BUYER_FINGERPRINT'
    CHECK(subject_kind = 'BUYER_FINGERPRINT'),
  -- 裸的 32 位十六进制（trade 的 subject_key 存的是**裸值**，不带前缀）。
  subject_key varchar(64) NOT NULL CHECK(subject_key ~ '^[0-9a-f]{32}$'),
  -- 🔴 **可空**：SKIPPED（这个特征量不到）时**没有信号值**，写 0 会被读成「信号值是 0」。
  measured_value numeric(19,6) CHECK(measured_value IS NULL OR (measured_value >= 0 AND measured_value <= 1)),

  -- 回写结果：SUCCEEDED 成功 / FAILED 失败（含没权限、trade 不可达）/ SKIPPED 本轮没这条信号。
  writeback_status varchar(16) NOT NULL CHECK(writeback_status IN ('SUCCEEDED','FAILED','SKIPPED')),
  writeback_note varchar(600) NOT NULL DEFAULT '',
  attempted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 「跳过」⇒ 没有值、也没有尝试过；「失败」⇒ 尝试过但没成功（必须留时刻）。
  CHECK((writeback_status = 'SKIPPED') = (measured_value IS NULL)),
  CHECK(writeback_status <> 'FAILED' OR attempted_at IS NOT NULL),
  CHECK(writeback_status <> 'SUCCEEDED' OR (attempted_at IS NOT NULL AND measured_value IS NOT NULL))
);
COMMENT ON TABLE buyer_risk_signal_writeback IS
  '16.5.1.2 · 回写 9.2.2.2 风控信号的凭据（append-only）。回写失败不阻塞评分，'
  '失败原因逐条写明；signal_source 固定 TENANT_MAINTAINED（不占用别的模块的预留槽）。';

CREATE INDEX buyer_risk_signal_writeback_by_score
  ON buyer_risk_signal_writeback(tenant_id, score_id);
CREATE INDEX buyer_risk_signal_writeback_by_status
  ON buyer_risk_signal_writeback(tenant_id, writeback_status);

-- ===========================================================================
-- 五、append-only 触发器（评分是判定凭据，事后改写不了）
-- ===========================================================================
-- 🔴 三张凭据表全部**不可 UPDATE / DELETE**：
--   一次评分改不成另一个分数（那等于事后改写判定凭据），
--   权重改了也只能**新起一次评分**，不能就地改历史行。
CREATE FUNCTION guard_buyer_risk_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是判定凭据：不可修改也不可删除（权重改了请新起一次评分）', TG_TABLE_NAME
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER buyer_risk_score_immutable
  BEFORE UPDATE OR DELETE ON buyer_risk_score
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_risk_immutable();
CREATE TRIGGER buyer_risk_feature_immutable
  BEFORE UPDATE OR DELETE ON buyer_risk_feature
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_risk_immutable();
CREATE TRIGGER buyer_risk_signal_writeback_immutable
  BEFORE UPDATE OR DELETE ON buyer_risk_signal_writeback
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_risk_immutable();

-- ===========================================================================
-- 六、引用完整性（不建外键，改用触发器 —— 见文件头第四节）
-- ===========================================================================
-- feature / writeback 指向的 score 必须真实存在且**同租户**。
CREATE FUNCTION guard_buyer_risk_feature_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM buyer_risk_score s
                 WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.score_id) THEN
    RAISE EXCEPTION '评分凭据 % 不存在或不属于当前租户', NEW.score_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER buyer_risk_feature_reference
  BEFORE INSERT ON buyer_risk_feature
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_risk_feature_reference();

CREATE FUNCTION guard_buyer_risk_writeback_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM buyer_risk_score s
                 WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.score_id) THEN
    RAISE EXCEPTION '评分凭据 % 不存在或不属于当前租户', NEW.score_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER buyer_risk_signal_writeback_reference
  BEFORE INSERT ON buyer_risk_signal_writeback
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_risk_writeback_reference();

-- ---------------------------------------------------------------------------
-- 「贡献分之和 = 总分」这条不变量由**延迟约束触发器**钉死
-- ---------------------------------------------------------------------------
-- 🔴 为什么必须是延迟约束（DEFERRABLE INITIALLY DEFERRED）：
--   feature 行是在 score 行**之后**逐条插入的，插入当下还看不到全部兄弟行，
--   逐条校验必然误判 ⇒ 推迟到提交那一刻统一核对。
--
-- 🔴 这条不变量是「评分要能解释」的**物理保证**：
--   * 出了分 ⇒ 每条参与计分的特征都必须有贡献分，且**贡献分之和恰好等于总分**；
--   * 没出分（信号不足）⇒ 贡献分之和必须是 0 —— 不允许出现「半个分数」。
--   解释不出来的分数不是分数：这条约束让「分数 = 各特征贡献分之和」在**数据层**成立，
--   而不是靠服务层自觉。
CREATE FUNCTION guard_buyer_risk_feature_sum() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_score numeric(19,4);
  v_status varchar(24);
  v_total numeric(19,4);
  v_missing int;
BEGIN
  SELECT risk_score, evaluation_status INTO v_score, v_status
    FROM buyer_risk_score WHERE tenant_id = NEW.tenant_id AND id = NEW.score_id;
  SELECT coalesce(sum(contribution), 0) INTO v_total
    FROM buyer_risk_feature WHERE tenant_id = NEW.tenant_id AND score_id = NEW.score_id;
  SELECT count(*) INTO v_missing
    FROM buyer_risk_feature
    WHERE tenant_id = NEW.tenant_id AND score_id = NEW.score_id
      AND available AND contribution IS NULL;

  IF v_score IS NULL THEN
    IF v_total <> 0 THEN
      RAISE EXCEPTION '本次评分没有分数（状态 %），各特征贡献分之和却是 % —— 信号不足时不允许出现半个分数',
        v_status, v_total USING ERRCODE='23514';
    END IF;
  ELSE
    IF v_missing > 0 THEN
      RAISE EXCEPTION '本次评分有分数（%），但有 % 条参与计分的特征没有贡献分', v_score, v_missing
        USING ERRCODE='23514';
    END IF;
    IF v_total <> v_score THEN
      RAISE EXCEPTION '各特征贡献分之和（%）与总分（%）不一致 —— 解释不出来的分数不是分数',
        v_total, v_score USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER buyer_risk_feature_sum
  AFTER INSERT ON buyer_risk_feature
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_buyer_risk_feature_sum();

-- ===========================================================================
-- 七、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['buyer_risk_rule','buyer_risk_score','buyer_risk_feature',
                              'buyer_risk_signal_writeback'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
-- 🔴 **刻意不建 data_scope（RESTRICTIVE）策略**：买家是**租户级主体**，不是某家店 /
--   某个部门的资产（与 V312 的法人主体行同一处理）。

-- ===========================================================================
-- 八、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON buyer_risk_rule TO channel_app;
GRANT SELECT,INSERT ON buyer_risk_score TO channel_app;
GRANT SELECT,INSERT ON buyer_risk_feature TO channel_app;
GRANT SELECT,INSERT ON buyer_risk_signal_writeback TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V312 同口径）。
REVOKE ALL ON buyer_risk_rule FROM starcon_app;
REVOKE ALL ON buyer_risk_score FROM starcon_app;
REVOKE ALL ON buyer_risk_feature FROM starcon_app;
REVOKE ALL ON buyer_risk_signal_writeback FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;
