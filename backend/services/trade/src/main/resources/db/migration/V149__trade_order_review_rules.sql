-- 9.2.1.2 审单规则。
--
-- 蓝图 09-order-and-fulfillment.md 9.2.1.2 原文：
--   职责：配置自动审单规则：按店铺/金额/买家等级/商品/收货国家自动通过或转人工；
--         命中黑名单、地址异常、大额、异常折扣的强制转人工。规则有优先级与生效期。
--   输入：规则定义（条件、动作、优先级）；测试数据。
--   输出：规则命中结果（每单命中的规则链）；规则生效记录。
--   关系：上游 = —；下游 = 9.2.1.1、18 自动化（规则引擎复用）。
--         **规则命中结果要可解释**：客服被买家投诉时能说出"为什么这单被拦了"。
--
-- 归属：**trade 服务**（蓝图 09 卷 L2 表「9.2 审单 → 主责服务 trade」）。
-- 本迁移**只新建表、零 ALTER 既有表** —— sales_order 簇（V99）是 9.1.2.2 与
-- 9.1.1.2 车道的写入所有者，本模块只**读**它（同服务同库，不是跨服务读）。
-- 跨车道 DDL 回归面 = 0，与并行的 V150/V151/V152 三条车道零交集。
--
-- ===========================================================================
-- 一、规则要作用在订单上：订单侧的实查结论（依赖自检）
-- ===========================================================================
-- 蓝图职责句点名五个维度，逐个对到 V99 真实存在的列上：
--   金额       → sales_order.grand_total_amount numeric(19,4)
--   商品       → sales_order_line.sku_id / sku_code
--   收货国家   → order_address_snapshot.country_code（address_role='SHIPPING'）
--   店铺       → ⚠️ sales_order **没有 shop_code 列**，只有 order_source
--                （'MANUAL'/'PLATFORM'/'AMAZON'/'SHOPIFY'/'EBAY'/'TIKTOK_SHOP'/'ALIBABA_1688'）
--   买家等级   → ⚠️ trade 库里**没有买家主数据**。sales_order_party_snapshot 只有
--                customer_party_id / customer_code / customer_type
--                （'ORGANIZATION'/'INDIVIDUAL'），没有等级列。
--
-- 两个 ⚠️ 的处理口径（与已并入集成头的 V144 同一手法，不假装已支持）：
--   * SHOP 维度作用在 order_source 上，即**平台级**。同平台跨店铺区分不了，
--     这一点由 order_review_rule.capability_note 与 /capabilities 如实报出。
--     D5 平台单同步落地后由同步器补 shop_code，届时条件取值改成店铺码即可，
--     表结构与判定逻辑都不用动。
--   * 买家等级与黑名单改由**本服务自己维护的 order_review_buyer_profile** 提供 ——
--     它是租户自维护的运营数据（运营给买家打标、拉黑），不是从别处编出来的。
--     买家身份键 buyer_key 沿用 V144 的同一套实算口径（收货地址快照三件套的 md5），
--     两个模块对「同一个人」的判定因此完全一致，不会各算各的。
--
-- ===========================================================================
-- 二、「可解释」是加粗句，所以命中链是**三层**而不是一个布尔值
-- ===========================================================================
-- 蓝图加粗句「规则命中结果要可解释：客服被买家投诉时能说出『为什么这单被拦了』」。
-- 只落「本单被转人工」+ 一个布尔值，客服说不出任何理由，所以这里落三层：
--   order_review_rule_evaluation      一次判定 = 结论 + 命中的规则 + 订单快照
--   order_review_rule_hit             被考虑到的**每一条**规则（命中与否都记）+ 排序位次
--   order_review_rule_hit_condition   每条规则**每个条件**命中订单的哪个字段、实际值多少
-- 第三层带 actual_field / actual_value 两列：它让客服能原话复述
-- 「订单金额 1200.0000 ≥ 阈值 1000.0000」，而不是「系统判定为高额」。
-- ⚠️ **未命中的规则与条件同样留痕**（matched=false / satisfied=false）：
--    只记命中项时「为什么这条规则没兜住」同样答不上来。
--
-- ===========================================================================
-- 三、判定口径：强制转人工压过一切自动通过
-- ===========================================================================
-- 职责句的结构是「按 A/B/C 自动通过或转人工；命中 X/Y/Z/W 的**强制**转人工」。
-- 所以 forced_manual 的优先级**高于** priority：一条 priority=999 的自动通过规则，
-- 遇到一条 priority=1 的强制转人工规则，结论仍然是转人工。
-- 规则链按 priority DESC, rule_code ASC 排序后**逐条留痕**，
-- 所以「命中链能按顺序复现」是表结构保证的，不是运行时排序的副产品。
--
-- **没有任何规则命中 → MANUAL_REVIEW**（不给隐含默认值）：
-- 与 9.2.1.1「批量通过必须基于明确的规则，而不是默认全过」同一立场，
-- 也与 V144「没配规则就报未配置，不给默认值」同一纪律。
-- ⚠️ 这一条刻意**不做成租户可配置开关**：把它做成开关等于允许某个租户把
-- 「默认全过」打开，而那正是 9.2.1.1 加粗句点名要避免的事。
-- 想要「小额单默认放行」的正确写法是一条显式 AUTO_APPROVE 规则。
--
-- ===========================================================================
-- 四、金额与时间
-- ===========================================================================
-- 金额 numeric(19,4)、数量 numeric(19,6)，全程 BigDecimal 禁浮点。
-- 阈值 numeric_value 也是 numeric(19,4)，**不在 DB 里做浮点比较**。
-- 折扣比例用「百分数 numeric(19,4)」表达（85 表示 85%）而不是 0.85 的小数：
-- 界面与运营对账时看到的是「折扣 85%」，而 0.85 在别的实现里会被读成「折扣 0.85%」。
-- subtotal_amount = 0 时折扣比例是 **null 而不是 0**（0 会被读成「零元订单打了 0 折」）。
--
-- 时间一律 timestamptz：服务层用 OffsetDateTime 绑定并在 values 位置写 ?::timestamptz，
-- 绝不用 Timestamp.valueOf(LocalDateTime...)（那会静默偏 −28800s）。
-- 「生效期」是本模块的重点验收项：effective_from / effective_to 参与判定，
-- 所以 evaluated_at 与它们比较时必须是同一个时区口径，不能一个 timestamptz
-- 一个 timestamp 混着比。
--
-- ===========================================================================
-- 五、地址异常是**判定出来的**不是登记出来的
-- ===========================================================================
-- 职责句里的「地址异常」在本服务能自己算出来，全部由收货地址快照的列推出：
--   MISSING_POSTCODE     缺邮编
--   MISSING_PHONE        缺联系电话
--   PO_BOX_ONLY          只有信箱类地址且无公司名
--   INCOMPLETE_ADDRESS   地址行过短，或省市都空
--   RESERVED_POSTCODE    邮编是同一个数字重复（如 00000）
-- 判定结果落在命中明细的 actual_value 里，客服能念出是哪一种异常。

-- ---------------------------------------------------------------------------
-- 1. 审单规则头
-- ---------------------------------------------------------------------------
-- 为什么规则必须带**生效期**：审单规则是会被投诉的（「为什么这单昨天过了今天没过」）。
-- 没有生效期就无法回答「当时生效的是哪一版」，规则一改历史结论就全部说不清了。
CREATE TABLE order_review_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 规则编号：租户内唯一，**排序的确定性靠它**（同优先级时按编号升序），
  -- 顺序不确定的话同一张单在不同时刻可能算出不同的命中链。
  rule_code varchar(64) NOT NULL CHECK(btrim(rule_code)<>''),
  rule_name varchar(120) NOT NULL CHECK(btrim(rule_name)<>''),
  description varchar(600) NOT NULL DEFAULT '',
  -- **优先级：数字大的先判定**。刻意不叫 rank：rank 是窗口函数名，容易被当成序号。
  priority int NOT NULL DEFAULT 100 CHECK(priority >= 0 AND priority <= 9999),
  -- 动作：自动通过 / 转人工。没有第三个动作 —— 「自动拒单」不在本模块职责内，
  -- 9.2.1.3 异常拦截才是拒绝/取消的主权（且它也明确「拦截不自动取消」）。
  action text NOT NULL CHECK(action IN ('AUTO_APPROVE','MANUAL_REVIEW')),
  -- **强制转人工**：命中即压过任何自动通过规则（见文件头第三节）。
  forced_manual boolean NOT NULL DEFAULT false,
  -- 规则内多个条件的组合方式：ALL = 全部满足才算命中；ANY = 任一满足即命中。
  match_mode text NOT NULL DEFAULT 'ALL' CHECK(match_mode IN ('ALL','ANY')),
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','DISABLED')),
  -- **生效期**：from 必填，to 留空 = 长期有效。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  -- 规则自身的能力自述：条件引用的字段此刻取不到值时，界面照实显示而不是猜。
  -- SHOP 维度写「平台级、店铺码待 D5」这类话，放在规则上而不是只放在文档里。
  capability_note varchar(400) NOT NULL DEFAULT '',
  note varchar(600) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by_membership_id uuid NOT NULL,
  updated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rule_code),
  CONSTRAINT order_review_rule_code_shape
    CHECK(rule_code ~ '^[A-Za-z0-9._-]{1,64}$'),
  CONSTRAINT order_review_rule_effective_window
    CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- ⚠️ 这里**刻意没有**「effective_from 不得早于 created_at」这类约束：
  -- 运营最常见的一条规则就是「今天 00:00 起全量生效」，而 created_at = now()
  -- 是当天中午 —— 那条约束会把这种最正常不过的规则全部拒掉，
  -- 报错信息（违反 check constraint）还完全指不出运营哪里填错了。
  CONSTRAINT order_review_rule_disabled_needs_note
    CHECK(status<>'DISABLED' OR btrim(note)<>''),
  CONSTRAINT order_review_rule_timestamps_ordered
    CHECK(updated_at>=created_at)
);
CREATE INDEX order_review_rule_by_status
  ON order_review_rule(tenant_id,status,priority DESC,rule_code);
-- 「此刻生效中的规则」是判定时的唯一一条查询，按生效期建部分索引。
CREATE INDEX order_review_rule_effective
  ON order_review_rule(tenant_id,effective_from,effective_to)
  WHERE status = 'ACTIVE';
CREATE INDEX order_review_rule_by_created
  ON order_review_rule(tenant_id,created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. 规则条件（一行一个条件）
-- ---------------------------------------------------------------------------
-- 八个维度**逐个对应职责句里的一个词**，没有泛化的「任意表达式」：
--   SHOP              店铺（实为 order_source，平台级 —— 见文件头第一节）
--   AMOUNT            金额（大额就是它配出来的）
--   BUYER_LEVEL       买家等级
--   ITEM              商品
--   SHIPPING_COUNTRY  收货国家
--   BUYER_BLACKLIST   黑名单
--   ADDRESS_ANOMALY   地址异常
--   DISCOUNT_RATIO    异常折扣
-- 不给自由表达式是有意的：一个能写任意表达式的规则引擎，出错时说不出
-- 「哪个条件命中了哪个字段」，而可解释正是本模块的加粗句要求。
CREATE TABLE order_review_rule_condition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  -- 条件序号：命中明细按它排序，保证同一规则的解释顺序可复现。
  seq_no int NOT NULL CHECK(seq_no > 0),
  dimension text NOT NULL CHECK(dimension IN (
    'SHOP','AMOUNT','BUYER_LEVEL','ITEM','SHIPPING_COUNTRY',
    'BUYER_BLACKLIST','ADDRESS_ANOMALY','DISCOUNT_RATIO')),
  -- 运算符分三族：集合型（IN/NOT_IN）、数值型（GTE/GT/LTE/LT/EQ）、
  -- 标记型（IS_TRUE/IS_FALSE）。
  -- ⚠️ 标记型存在的原因：黑名单是布尔量，让它挤进 EQ 就要么把 EQ 变成
  -- 「既可数值又可集合」，要么在 Java 里把 true 翻译成 1 —— 后者会让
  -- 命中明细里的 actual_value 显示成 1 而不是「在黑名单里」，
  -- 而可解释正是本模块的加粗句要求。标记型让它原样说人话。
  operator text NOT NULL CHECK(operator IN (
    'IN','NOT_IN','GTE','GT','LTE','LT','EQ','IS_TRUE','IS_FALSE')),
  -- 取值集合：IN / NOT_IN 用。大小写不敏感由服务层统一转大写（国家码与订单源都是大写词表）。
  value_set text[] NOT NULL DEFAULT '{}',
  -- 阈值：GTE / GT / LTE / LT / EQ 用。DISCOUNT_RATIO 的口径是百分数（85 = 85%）。
  value_numeric numeric(19,4),
  description varchar(400) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rule_id,seq_no),
  FOREIGN KEY(tenant_id,rule_id) REFERENCES order_review_rule(tenant_id,id) ON DELETE CASCADE,
  -- 集合型取值不能为空：空集合的 IN 恒假，空集合的 NOT_IN 恒真，
  -- 后者会让一条「NOT_IN 空集合」的规则命中每一张单 —— 那是一条静默放行所有订单的规则。
  CONSTRAINT order_review_rule_condition_set_shape
    CHECK((operator IN ('IN','NOT_IN')) = (cardinality(value_set) > 0)),
  CONSTRAINT order_review_rule_condition_numeric_shape
    CHECK((operator IN ('GTE','GT','LTE','LT','EQ')) = (value_numeric IS NOT NULL)),
  -- 标记型既不带集合也不带阈值。
  CONSTRAINT order_review_rule_condition_flag_shape
    CHECK((operator IN ('IS_TRUE','IS_FALSE'))
          = (cardinality(value_set) = 0 AND value_numeric IS NULL)),
  -- 集合型不许带阈值、数值型不许带集合：两套取值同时存在时「用哪个」没有答案。
  CONSTRAINT order_review_rule_condition_no_mixed_values
    CHECK(NOT (cardinality(value_set) > 0 AND value_numeric IS NOT NULL)),
  -- ⚠️ 维度与运算符必须匹配（这条把一大类「配了但永远判不出结果」的条件挡在库外）：
  --     数值型只能配 AMOUNT / DISCOUNT_RATIO；
  --     标记型只能配 BUYER_BLACKLIST；
  --     其余维度（SHOP / ITEM / SHIPPING_COUNTRY / BUYER_LEVEL / ADDRESS_ANOMALY）只能配集合型。
  -- 一条「收货国家 GTE 50」这样的条件在 Java 里只会判 false，
  -- 而界面上看上去「规则配了、就是不命中」——最难查的一类故障。
  CONSTRAINT order_review_rule_condition_operator_fits_dimension
    CHECK((dimension IN ('AMOUNT','DISCOUNT_RATIO')) = (operator IN ('GTE','GT','LTE','LT','EQ'))
      AND (dimension = 'BUYER_BLACKLIST') = (operator IN ('IS_TRUE','IS_FALSE'))),
  -- 集合成员不得为空串：'' 不会匹配任何订单上的任何值，而 NOT_IN 会把它当作「不在集合内」。
  -- ⚠️ 用 array_remove 而不是 NOT EXISTS(...)：**PostgreSQL 不允许 CHECK 里带子查询**
  -- （cannot use subquery in check constraint），这里踩过一次。
  -- ⚠️ 这条只挡**字面空串**；纯空格成员（' '）由服务层统一 trim 后拒绝，
  -- 因为 btrim 放进 CHECK 需要子查询/自定义函数才能遍历数组。
  CONSTRAINT order_review_rule_condition_no_blank_values
    CHECK(cardinality(value_set) = cardinality(array_remove(value_set, '')))
);

-- ---------------------------------------------------------------------------
-- 3. 买家画像（等级 + 黑名单）
-- ---------------------------------------------------------------------------
-- 为什么放在 trade 而不是等 15 客户 / 16 合规：
--   * 15 客户（CRM 买家主数据）与 16 合规（黑名单）都未交付；
--   * 按模块边界纪律，trade 也**不能**去读它们的库（customer_party_id 只是一个
--     指向 catalog 的 id，本服务拿不到等级，也不得跨库联表）。
-- 所以这里落的是**租户自维护的运营数据**：运营在自己的待审队列里给买家打标、
-- 拉黑，数据落在自己租户下。15/16 落地后由同步器补 buyer_level 与合规黑名单，
-- 本表的 buyer_key 口径不变，规则条件不用改。
--
-- ⚠️ **未登记 = 等级未知，不是 STANDARD**：没有画像行的买家在
-- BUYER_LEVEL 条件下**不满足**（判定返回 null 而不是兜一个 STANDARD）。
-- 兜底 STANDARD 会让「VIP 自动通过」这条规则把全网买家都放过去。
CREATE TABLE order_review_buyer_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 买家身份键：收货地址快照三件套的 md5，与 V144 buyerFingerprint 的**地址分支**
  -- 同一公式（分隔符用 E'\x1f' 不可见字符而不是 '|'，理由见 V144 文件头）。
  -- 刻意**只用地址分支、不用 V144 的 customer_party_id 分支**：那个 id 指向 catalog
  -- 的客户主数据，平台单常常为空，而收货地址快照是 V99 对每张 SUBMITTED 单的硬要求，
  -- 地址分支对两类订单都稳定。两个模块对「同一个人」的判定必须一致，
  -- 否则会出现「合单认为是同一个人、审单认为是两个人」。
  buyer_key char(32) NOT NULL CHECK(buyer_key ~ '^[0-9a-f]{32}$'),
  -- 可读快照三件套：客服要能对得上人，md5 谁也念不出来。
  buyer_name varchar(120) NOT NULL DEFAULT '',
  buyer_phone varchar(64) NOT NULL DEFAULT '',
  buyer_email varchar(254) NOT NULL DEFAULT '',
  -- 买家等级。UNKNOWN 不做成取值：等级未知的状态由「**没有这一行**」表达。
  buyer_level text NOT NULL DEFAULT 'STANDARD' CHECK(buyer_level IN (
    'STANDARD','KEY','STRATEGIC','VIP','RISK')),
  blacklisted boolean NOT NULL DEFAULT false,
  -- **拉黑必须写理由**：没有理由的黑名单，客服无法向买家解释，
  -- 正是本模块加粗句要解决的那类场景。
  blacklist_reason varchar(400) NOT NULL DEFAULT '',
  blacklisted_at timestamptz,
  note varchar(600) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  updated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,buyer_key),
  CONSTRAINT order_review_buyer_profile_blacklist_needs_reason
    CHECK(NOT blacklisted OR btrim(blacklist_reason)<>''),
  CONSTRAINT order_review_buyer_profile_blacklist_needs_time
    CHECK(NOT blacklisted OR blacklisted_at IS NOT NULL),
  CONSTRAINT order_review_buyer_profile_not_blacklisted_no_time
    CHECK(blacklisted OR blacklisted_at IS NULL),
  CONSTRAINT order_review_buyer_profile_email_shape
    CHECK(btrim(buyer_email)='' OR buyer_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
  CONSTRAINT order_review_buyer_profile_timestamps_ordered
    CHECK(updated_at>=created_at)
);
-- 按邮箱反查：同一个人的收货地址快照里姓名拼写可能不同（"Ada Lovelace" / "A. Lovelace"），
-- 身份键对不上，但邮箱一样 —— 判定时先用身份键、再用邮箱，两次都查不到才算未知。
CREATE INDEX order_review_buyer_profile_by_email
  ON order_review_buyer_profile(tenant_id,lower(buyer_email))
  WHERE btrim(buyer_email)<>'';
CREATE INDEX order_review_buyer_profile_by_blacklist
  ON order_review_buyer_profile(tenant_id,buyer_key)
  WHERE blacklisted;

-- ---------------------------------------------------------------------------
-- 4. 规则生效记录（蓝图输出②：一次判定 = 一行）
-- ---------------------------------------------------------------------------
-- **一条判定一行，不做「反复重判」**：审单结论会被平台与买家追问，
-- 「当时按哪套规则、算出什么结论」必须能复现，所以判定结果是凭据不是当前状态。
-- 同一张单被重判多次时各占一行（evaluated_at 不同），历史不被覆盖。
CREATE TABLE order_review_rule_evaluation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 订单快照：判定是「某时刻对某张单」的事实，订单本身后续会改，
  -- 结论页要能复现当时的输入，所以金额与来源在这里各留一份。
  order_no varchar(64) NOT NULL DEFAULT '',
  order_source varchar(32) NOT NULL DEFAULT '',
  currency_code char(3) NOT NULL DEFAULT '',
  order_total numeric(19,4) NOT NULL DEFAULT 0,
  discount_rate_percent numeric(19,4),
  -- DRY_RUN = 拿真实订单试跑规则（蓝图输入句「测试数据」）；
  -- RECORDED = 真实生效判定。两者都留痕，试跑不会污染生产的生效记录视图。
  mode text NOT NULL DEFAULT 'RECORDED' CHECK(mode IN ('DRY_RUN','RECORDED')),
  -- 试跑的取值范围：EFFECTIVE = 只跑此刻生效中的规则；SELECTED = 只跑调用方点名的
  -- 那几条（哪怕还是 DRAFT）。后者是「规则还没启用，先拿这张单试跑一遍」的入口，
  -- 不写这一列就分不清一次试跑到底试了哪些规则。
  evaluated_scope text NOT NULL DEFAULT 'EFFECTIVE' CHECK(evaluated_scope IN ('EFFECTIVE','SELECTED')),
  -- 最终结论。AUTO_APPROVE 只有在**至少有一条规则明确放行**时才可能出现。
  decision text NOT NULL CHECK(decision IN ('AUTO_APPROVE','MANUAL_REVIEW')),
  -- **一句话结论**：客服第一眼要看的就是它，必须说清是哪条规则、为什么。
  decision_reason varchar(600) NOT NULL,
  -- 决定结论的那条规则；没有任何规则命中时为 null（默认转人工）。
  winning_rule_id uuid,
  winning_rule_code varchar(64) NOT NULL DEFAULT '',
  winning_rule_name varchar(120) NOT NULL DEFAULT '',
  -- 参与判定的规则数与其中的命中数、强制命中数：与命中明细行数互为对照。
  considered_rule_count int NOT NULL DEFAULT 0 CHECK(considered_rule_count >= 0),
  matched_rule_count int NOT NULL DEFAULT 0 CHECK(matched_rule_count >= 0),
  forced_rule_count int NOT NULL DEFAULT 0 CHECK(forced_rule_count >= 0),
  -- 命中的地址异常码（同一张单可能同时命中多种）。
  address_anomaly_codes text[] NOT NULL DEFAULT '{}',
  evaluated_at timestamptz NOT NULL,
  -- 自动判定（由 9.2.1.1 批处理触发）时为空，人工试跑时有值。
  evaluated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id),
  CONSTRAINT order_review_rule_evaluation_auto_needs_rule
    CHECK(decision<>'AUTO_APPROVE' OR winning_rule_id IS NOT NULL),
  CONSTRAINT order_review_rule_evaluation_forced_is_manual
    CHECK(forced_rule_count = 0 OR decision = 'MANUAL_REVIEW')
);
CREATE INDEX order_review_rule_evaluation_by_order
  ON order_review_rule_evaluation(tenant_id,order_id,evaluated_at DESC);
CREATE INDEX order_review_rule_evaluation_by_decision
  ON order_review_rule_evaluation(tenant_id,decision,evaluated_at DESC);
CREATE INDEX order_review_rule_evaluation_by_created
  ON order_review_rule_evaluation(tenant_id,created_at DESC);

-- ---------------------------------------------------------------------------
-- 5. 命中链（蓝图输出①：被考虑到的每一条规则）
-- ---------------------------------------------------------------------------
-- ⚠️ **未命中的规则同样留一行**。只记命中项时，「为什么这条规则没兜住」
-- 答不上来，而那恰恰是客服被追问时的第二个问题。
-- eval_rank 是判定时的真实次序（priority DESC, rule_code ASC 之后的名次），
-- 命中链按它排序即可复现，不依赖任何人记得当时怎么排的。
CREATE TABLE order_review_rule_hit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  evaluation_id uuid NOT NULL,
  -- 规则在本条命中行里的快照：规则后来被改名改条件，历史命中行不受影响
  -- （否则「当时按哪条规则判的」会随规则编辑而变，命中链就不可复现了）。
  rule_id uuid NOT NULL,
  rule_code varchar(64) NOT NULL,
  rule_name varchar(120) NOT NULL,
  priority int NOT NULL,
  action text NOT NULL CHECK(action IN ('AUTO_APPROVE','MANUAL_REVIEW')),
  forced_manual boolean NOT NULL DEFAULT false,
  eval_rank int NOT NULL CHECK(eval_rank > 0),
  matched boolean NOT NULL,
  -- 命中或未命中的理由（人话）。未命中时写清是哪个条件没过。
  reason varchar(600) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一次判定里一条规则只出现一次。
  UNIQUE(tenant_id,evaluation_id,rule_id),
  -- ⚠️ 只建到 evaluation 的 FK，**不建到 rule 的 FK**：规则可被停用但不可删，
  -- 删掉规则会让历史命中链断链，而命中链是要给平台和买家看的东西。
  FOREIGN KEY(tenant_id,evaluation_id)
    REFERENCES order_review_rule_evaluation(tenant_id,id) ON DELETE CASCADE,
  -- 命中行必须带理由（命中或未命中都要说清哪一步得出的）。
  CONSTRAINT order_review_rule_hit_reason_present
    CHECK(btrim(reason)<>'')
);
CREATE INDEX order_review_rule_hit_by_evaluation
  ON order_review_rule_hit(tenant_id,evaluation_id,eval_rank);
CREATE INDEX order_review_rule_hit_by_rule
  ON order_review_rule_hit(tenant_id,rule_id,created_at DESC);

-- ---------------------------------------------------------------------------
-- 6. 逐条件明细（加粗句「哪条规则的哪个条件命中了订单的哪个字段」的落点）
-- ---------------------------------------------------------------------------
-- actual_field / actual_value 是本表存在的全部理由：
--   actual_field = 'sales_order.grand_total_amount'
--   actual_value = '1200.0000'
--   explanation  = '订单金额 1200.0000 ≥ 阈值 1000.0000'
-- 客服可以把这三句原话念给买家听。少了 actual_value 就只剩
-- 「命中了金额条件」，那仍然答不出「为什么」。
CREATE TABLE order_review_rule_hit_condition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  hit_id uuid NOT NULL,
  seq_no int NOT NULL CHECK(seq_no > 0),
  dimension text NOT NULL CHECK(dimension IN (
    'SHOP','AMOUNT','BUYER_LEVEL','ITEM','SHIPPING_COUNTRY',
    'BUYER_BLACKLIST','ADDRESS_ANOMALY','DISCOUNT_RATIO')),
  operator text NOT NULL,
  -- 规则侧的期望值（当时的条件快照，同样不受后续规则编辑影响）。
  expected varchar(400) NOT NULL DEFAULT '',
  -- **订单侧被比对的列**（含表名，客服与排障都能立刻定位）。
  actual_field varchar(120) NOT NULL DEFAULT '',
  -- **订单侧的实际值**：取不到时是空串而不是一个编出来的默认值。
  actual_value varchar(400) NOT NULL DEFAULT '',
  satisfied boolean NOT NULL,
  -- 人话解释：一句话说清「拿什么比了什么、结论是什么」。
  explanation varchar(600) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,hit_id,seq_no),
  FOREIGN KEY(tenant_id,hit_id) REFERENCES order_review_rule_hit(tenant_id,id) ON DELETE CASCADE,
  -- 每条明细都必须有一句解释：没有解释的明细等于没有明细。
  CONSTRAINT order_review_rule_hit_condition_explains
    CHECK(btrim(explanation)<>'')
);
CREATE INDEX order_review_rule_hit_condition_by_hit
  ON order_review_rule_hit_condition(tenant_id,hit_id,seq_no);

-- ---------------------------------------------------------------------------
-- 7. 延迟约束触发器：一条 ACTIVE 规则至少要有一个条件
-- ---------------------------------------------------------------------------
-- ⚠️ 延迟到提交时才检查：服务层的写入顺序是「先插规则头 → 再写条件行」，
-- 在 INSERT 规则头那一刻条件行还不存在，用普通触发器每次建规则都会报「没有条件」。
-- （与 V144 guard_order_consolidation_shape、V92 guard_routing_decision_switch
-- 同一个坑、同一个解法。）
--
-- 为什么这条不能省：一个**零条件的 ACTIVE 规则**在 match_mode='ANY' 下命中每一张单，
-- 在 'ALL' 下永远不命中 —— 前者是一条静默放行所有订单的规则，后者是一条永不生效的
-- 规则。两者都必须由 DB 挡住，而不是指望调用方记得先写条件。
CREATE FUNCTION guard_order_review_rule_has_conditions() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM order_review_rule_condition
   WHERE tenant_id = NEW.tenant_id AND rule_id = NEW.id;
  IF NEW.status = 'ACTIVE' AND n = 0 THEN
    RAISE EXCEPTION '规则 % 启用时至少要有一个条件：零条件的 ANY 规则会放行每一张单，ALL 规则则永不生效',
      NEW.rule_code USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER order_review_rule_has_conditions
  AFTER INSERT OR UPDATE ON order_review_rule
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_review_rule_has_conditions();

-- ---------------------------------------------------------------------------
-- 8. append-only：判定留痕不可改写（照抄 V92 第 8.5 节、V144 第 9 节纪律）
-- ---------------------------------------------------------------------------
-- 判定记录与命中明细是给平台、买家与监管看的东西。
-- 「客服改一下命中记录里的数字」在别的模块是修数据，在这里是销毁证据。
REVOKE UPDATE,DELETE ON order_review_rule_evaluation FROM trade_app;
CREATE FUNCTION guard_order_review_evaluation_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的判定留痕表（%）：审单结论是客服向买家解释的依据',
  TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_review_rule_evaluation_append_only BEFORE UPDATE OR DELETE ON order_review_rule_evaluation
  FOR EACH ROW EXECUTE FUNCTION guard_order_review_evaluation_append_only();

-- 命中链与逐条件明细同样只增不改：判定结论一旦落库就定死，
-- 要改结论就重新判定（新增一行判定记录），而不是改旧的那一行。
REVOKE UPDATE,DELETE ON order_review_rule_hit,order_review_rule_hit_condition FROM trade_app;
CREATE TRIGGER order_review_rule_hit_append_only BEFORE UPDATE OR DELETE ON order_review_rule_hit
  FOR EACH ROW EXECUTE FUNCTION guard_order_review_evaluation_append_only();
CREATE TRIGGER order_review_rule_hit_condition_append_only BEFORE UPDATE OR DELETE ON order_review_rule_hit_condition
  FOR EACH ROW EXECUTE FUNCTION guard_order_review_evaluation_append_only();

-- ---------------------------------------------------------------------------
-- 9. 多租户：RLS + 授权（与 trade V144 第 10 节同一套写法）
-- ---------------------------------------------------------------------------
-- 六张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 审单规则尤其不能省：命中断言会把订单金额、买家邮箱与地址异常原样写进
-- 命中明细。漏掉租户谓词 = A 租户的客服能在自己的界面上读到 B 租户
-- 「哪张单因为邮箱命中了黑名单」，这是带 PII 的跨租户泄露。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_review_rule','order_review_rule_condition','order_review_buyer_profile',
    'order_review_rule_evaluation','order_review_rule_hit','order_review_rule_hit_condition'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_review_rule,order_review_rule_condition,order_review_buyer_profile,
  order_review_rule_evaluation,order_review_rule_hit,order_review_rule_hit_condition TO trade_app;
-- 第 8 节的 REVOKE 写在 GRANT 之前会被覆盖回去，这里按顺序重放：
-- 明确「授权在前、收回在后」。
REVOKE UPDATE,DELETE ON order_review_rule_evaluation,order_review_rule_hit,
  order_review_rule_hit_condition FROM trade_app;
-- 本迁移**不 ALTER 任何既有表**（sales_order / sales_order_line /
-- sales_order_party_snapshot / order_address_snapshot 全部只读），
-- 与并行车道的 V150 / V151 / V152 零交集，跨车道 DDL 回归面 = 0。
