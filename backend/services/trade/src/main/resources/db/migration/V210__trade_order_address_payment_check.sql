-- 9.2.2.1 地址与支付校验。
--
-- 蓝图 09-order-and-fulfillment.md 9.2.2.1 原文：
--   职责：校验买家地址可投递性（国家/邮编/街道格式）、禁运国家清单、支付状态一致性（平台已付/货到付款/未付款）。
--   输入：订单地址快照；支付方式与状态；禁运规则配置。
--   输出：校验结果 + 风险等级。
--   关系：上游 = 9.1.2.1；下游 = 9.2.1.3、10 物流（渠道可用性）。
--         **货到付款订单的履约风险最高**，需额外风控与保险策略。
--
-- 归属：**trade 服务**（蓝图 09 卷 L2 表「9.2 审单与异常 → 主责服务 trade」）。
-- 本迁移**只新建表、函数与触发器，零 ALTER 零 DROP 既有表** ——
-- sales_order 簇（V99）是 9.1.1.1 车道的写入所有者、platform_order 簇（V207）是 9.1.2.1
-- 车道的写入所有者、order_intercept_*（V153）是 9.2.1.3 车道的写入所有者，
-- 本模块只**读**它们，跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、加粗句「货到付款订单的履约风险最高，需额外风控与保险策略」的**五层**落地
-- ===========================================================================
-- 这句话在本迁移里是五层，任何一层单独拿走都还能绕过去，所以五层同时成立：
--   (1) **风险等级词表里 CRITICAL 是最高档，且只能由货到付款触发**：
--       order_address_check.risk_level 的 CHECK 是
--       `CHECK ((risk_level = 'CRITICAL') = cod_flagged)`
--       ⇒ 「是货到付款却不是最高风险」与「不是货到付款却给了最高风险」在写库这一层就被拒。
--       风险等级**不是**租户可配的阈值：把 COD 的风险做成可调项，就等于允许把
--       履约风险最高的一类单悄悄降级，而降级之后没有任何人看得出来。
--   (2) **货到付款是**独立**的一项**（明细行 item_code = 'COD_FULFILLMENT_RISK'），
--       不是「支付一致性」里的一条分支：把「COD 合法但风险最高」与「COD 与状态矛盾」
--       混成一项，就会出现「因为状态一致所以这项正常」的读法，
--       而这两件事的处置动作完全不同（一个要加保险，一个要查收款）。
--   (3) **保险策略单独成一项，且恒为未交付**：item_code = 'COD_INSURANCE_POLICY'，
--       status = 'UPSTREAM_UNAVAILABLE'。蓝图要求的是「**需额外**风控与保险策略」，
--       而保险策略在蓝图中**没有任何 L4 模块持有**（卷 10 的 10.1.1.2 只持有渠道保价规则），
--       本模块**不假装**能算保费、能推荐投保方案、能算出保额。
--       ⇒ capabilities 维度 COD_INSURANCE_POLICY 如实报 available = false +
--         ownerModule + impact（见第七节）。
--   (4) **额外风控那一半是真接通的**：风控校验 9.2.2.2（V150，同服务同库）已交付，
--       本模块不重算风控分，只在 capabilities 里指出「货到付款单的额外风控
--       走 9.2.2.2 的 order_risk_assessment」这一条真实去处，
--       而不是把「风控」两个字写进本模块的输出里当交付。
--   (5) **不存在任何一个端点能改写风险等级**：risk_level 与 cod_flagged 由
--       DB 触发器 order_address_check_apply_totals 从七行明细实算，
--       列级 REVOKE UPDATE 让运行角色改不动它们（第九节）。
--
-- ⚠️ 货到付款**不**被判成「不可投递」：COD 订单地址照样能送到，
--   它的问题是**收不到钱**的风险，不是投递问题。所以它只抬风险等级，
--   delivery_status 仍由地址类四项独立判定（第四节）。
--
-- ===========================================================================
-- 二、上游实查结论：蓝图输入里的三样，本服务能真接通两样半
-- ===========================================================================
-- 蓝图输入句写的是「订单地址快照；支付方式与状态；禁运规则配置」三样。逐样实查：
--   * **订单地址快照** —— **真 SQL**。order_address_snapshot（V99，本服务同库）：
--     address_role / recipient_name / recipient_phone / recipient_email /
--     country_code(char 2) / province / city / district / postcode /
--     address_line1 / address_line2。一个角色一条快照（收货必填、账单可空）。
--   * **支付方式** —— **真 SQL**。sales_order.payment_method（V99）：
--     PREPAID / COD / BANK_TRANSFER / CREDIT_TERM / FREIGHT_FORWARDER / OTHER。
--   * **支付状态** —— ⚠️ **本服务没有支付状态这个事实**。实查 V99 sales_order
--     **没有 payment_status 列**；V207 platform_order 也没有；
--     V100 的 SalesOrderListService 早就把 paymentStatus 恒置为 null 并写明
--     「归 12 财务」；12 财务的支付状态能力**未交付**。
--     ⇒ 本模块**不发明**一个支付状态列冒充上游事实（那会让界面显示「未付款」
--       而真相是「本服务根本不知道支付进展」—— 与 V100 同一纪律）。
--     ⇒ 支付状态由**调用方随请求提供**（POST /checks 的 paymentStatus 观察值），
--       本模块只判「它与付款方式自不自洽」。调用方没给 ⇒ 该项落 NO_REFERENCE，
--       **不是**「一致」——「没量到」与「量过了、结论是正常」是两件事。
--   * **禁运规则配置** —— 落成**租户可维护的码表**（第二节表 2），
--     **Java 里没有、也不许有**任何国家数组。
--
-- ===========================================================================
-- 三、禁运清单与国别格式规则都是**表**，不是代码常量
-- ===========================================================================
-- 表 2「禁运国家清单」与表 3「国别地址格式规则」是**租户可维护的真实业务表**：
--   * 禁运清单：国家码 + 强度（BLOCK 不可发 / REVIEW 需人工确认）+ 理由 + 生效期。
--     「禁运哪些国家」是运营与合规的判断，写死在 Java 里意味着改一次要发一次版，
--     而且发版之前那段时间里禁运名单是错的。
--   * 国别格式规则：邮编正则、邮编是否必填、街道是否必填与最小长度、
--     城市 / 省份 / 电话是否必填。**一个国家一套规则**：
--     美国是 5 位或 5-4 位数字，英国是字母数字混合，中国是 6 位数字，
--     德国是 5 位数字 —— 一套正则打天下会把一半真实地址判成错，
--      而一个永远命中的校验比没有校验更糟（界面会显示「地址有问题」而其实没问题）。
--   * 支付一致性规则：付款方式 × 观察到的支付状态 → 自不自洽 + 命中严重度。
--
-- ⚠️ 表 3 / 表 4 的**基线**由本迁移里的函数 order_address_check_install_baseline
--   按租户**幂等落库**（ON CONFLICT DO NOTHING），基线数据写在**SQL 里而不是 Java 里**：
--   本模块的硬要求是「不许写死国家数组在 Java 里」，所以基线词表以
--   迁移脚本为出处、落库后成为**普通的租户行**（能改、能停用、能加新国家），
--   照抄 V158「基线词表按租户幂等落库、结构归运营维护」的同一手法。
--   落库之后**判定真身是表**，不是那个函数 —— 改一行规则不用发版。
--
-- ===========================================================================
-- 四、判定口径：七项恒定各占一行，输出两件东西
-- ===========================================================================
-- 蓝图输出句：「校验结果 + 风险等级」——**两件，不是一件**：
--   * **校验结果** = delivery_status（可投递 / 不可投递 / 未定）+ 七行明细。
--   * **风险等级** = risk_level（LOW / MEDIUM / HIGH / CRITICAL）。
-- 两者的判定依据**刻意不同**：
--   * delivery_status 只看**地址类四项**（禁运国 / 邮编格式 / 街道格式 / 必填项），
--     因为「可不可以送到」是地址问题；支付状态矛盾不改变货能不能送到，
--     它改变的是「送到了收不收得到钱」。
--   * risk_level 看**全部七项**，且货到付款**单独抬到 CRITICAL**。
-- 风险等级阶梯（从高到低，等号生效）：
--   CRITICAL —— 货到付款（cod_flagged = true），**无论其它项结论如何**
--   HIGH     —— 存在 BLOCK 级命中（支付状态矛盾）
--   MEDIUM   —— 存在 WARN 级命中
--   LOW      —— 其它
-- ⚠️ 货到付款**压在最上面**：加粗句说它「履约风险最高」，
--   若让「支付状态矛盾」的 HIGH 压过它，一单既矛盾又是货到付款时，
--   界面显示的等级就不再是最高档 —— 那正是加粗句点名要防的情况。
--
-- 七项（item_code）恒定各占一行，缺失与未接通**也占行**：
--   COUNTRY_EMBARGO        收货国是否在禁运清单
--   POSTCODE_FORMAT        邮编是否符合该国规则
--   STREET_FORMAT          街道是否符合该国规则
--   REQUIRED_CONTACT       必填联系方式是否齐全（按该国规则）
--   PAYMENT_CONSISTENCY    支付状态与付款方式是否自洽
--   COD_FULFILLMENT_RISK   货到付款 → 最高履约风险
--   COD_INSURANCE_POLICY   货到付款保险策略（**未交付**）
-- 少一行的话，「这单为什么没被拦住」就只能从计数反推，而计数不保留是谁缺席的。
--
-- 判定基准时刻钉在订单 submitted_at 上（与 V153 同一纪律）：同一张单重复跑一万次，
-- 七项结论逐位相同。禁运清单的生效期也相对这个时刻判。
--
-- ===========================================================================
-- 五、多租户：RLS + 授权（与 trade V153 第九节、V150 第八节同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：禁运清单与国别规则是**跨订单宽条件**的判定源，
--   漏掉租户谓词就是**跨租户归因** —— A 家的禁运国清单会拦下 B 家的订单，
--   而 B 家连自己被拦都不知道。
--
-- ===========================================================================
-- 六、金额与数量
-- ===========================================================================
-- 本模块不产生金额（地址与支付**校验**不结算，金额归 12 财务）。
-- 需要数量口径的只有必填项缺失计数：integer / numeric(19,6)，
-- 全程 BigDecimal 禁浮点。时间一律 timestamptz，服务层用 OffsetDateTime 绑定
-- 并在 values 位置写 ?::timestamptz，绝不用 Timestamp.valueOf(LocalDateTime...)
-- （那会静默偏 −28800s）。

-- ---------------------------------------------------------------------------
-- 1. 校验策略（租户可维护；**每租户恰好一条**）
-- ---------------------------------------------------------------------------
-- 为什么要求先有策略才允许检查：校验结论会被 9.2.1.3 异常拦截与 10 物流读，
-- 「没配策略 = 默认全放行」会让「无禁运清单」与「清单位国家真的不在禁运清单里」
-- 这两件事长得一模一样。取 V153 同一立场：**没登记就报「未配置」，不给隐含默认值**。
CREATE TABLE order_address_check_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 一租户一条：UNIQUE(tenant_id) 本身就是「恰好一条」的表达。
  UNIQUE(tenant_id),
  UNIQUE(tenant_id,id),
  policy_name varchar(120) NOT NULL CHECK(btrim(policy_name)<>''),
  enabled boolean NOT NULL DEFAULT true,

  -- ---- 缺参照怎么办（**必须显式选择，不给默认**，与 V153 / V150 同一纪律）
  -- ASSESS_KNOWN_ONLY —— 按已评估项判定，缺参照项计入 missing_count 并在明细里点名缺什么
  -- BLOCK_DECISION    —— 有任何一项缺参照就不出「可投递」结论，只给「未定」
  -- 两者差着一个真实的运营后果（前者会放行，后者会攒人工队列），所以由租户选。
  missing_reference_policy varchar(24) NOT NULL
    CHECK(missing_reference_policy IN ('ASSESS_KNOWN_ONLY','BLOCK_DECISION')),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(updated_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 2. 禁运国家清单（租户可维护的**码表**）
-- ---------------------------------------------------------------------------
-- ⚠️ 刻意**没有**任何「内置禁运国家」默认值：禁运哪些国家是运营与合规的判断，
--   凭据是「这一版禁运名单」而不是「代码里那几个国家」。空清单 = 没禁运任何国家，
--   这是租户自己的选择，界面上必须显示为「清单为空」而不是「都通过」。
-- source 枚举照抄 V150 signal_source / V116 stock_source 的既有解法：
--   TENANT_MAINTAINED        —— 租户自己登记的（当前唯一可写来源）
--   MODULE_16_COMPLIANCE     —— 16 合规的国别禁运清单，**未交付**
-- ⚠️ 刻意**不伪造 L4 模块号**：16 合规的禁运清单当前没有任何已交付的 L4 模块是它的交付方，
--   编一个号会让后来的人去查一个不存在的模块。用「卷 + 关注点」的粗粒度名。
CREATE TABLE order_address_check_embargo_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- ISO 3166-1 alpha-2 大写。形状由 CHECK 钉死，不靠服务层自觉。
  country_code char(2) NOT NULL CHECK(country_code ~ '^[A-Z]{2}$'),
  -- **限制强度**：BLOCK = 不可发；REVIEW = 可疑（需人工或渠道确认）。
  -- 刻意不给「放行」档：一张禁运清单上写「放行」的行没有意义，
  -- 而它会让「清单里有的行 = 有问题」这条直觉失效。
  embargo_level varchar(8) NOT NULL CHECK(embargo_level IN ('BLOCK','REVIEW')),
  reason varchar(400) NOT NULL DEFAULT '',
  source varchar(32) NOT NULL CHECK(source IN ('TENANT_MAINTAINED','MODULE_16_COMPLIANCE')),
  source_reference varchar(200) NOT NULL DEFAULT '',
  effective_from timestamptz,
  effective_to timestamptz,
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个国家只能有一条当前规则：两条强度不同的禁运行会让「这国能不能发」有两个答案。
  UNIQUE(tenant_id,country_code),
  -- 生效期有序：to 早于 from 是一条永远不生效的规则，它比报错更糟（看起来配过了）。
  CHECK(effective_from IS NULL OR effective_to IS NULL OR effective_to>=effective_from),
  CHECK(source='TENANT_MAINTAINED' OR btrim(source_reference)<>''),
  CHECK(updated_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 3. 国别地址格式规则（租户可维护的**码表**；一个国家一套规则）
-- ---------------------------------------------------------------------------
-- 街道最小长度：不是「街道不能为空」而是「街道至少要有这么多个字符」——
--   只有信箱（PO Box）或门牌号单独一行的地址在跨境件里也是合法收件地址，
--   但一段只有一个字符的街道显然是占位符。长度下限卡在「占位符」这一档，
--   不卡「信箱」那一档（信箱由 9.2.1.3 的地址信息不全项单独判）。
CREATE TABLE order_address_check_country_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  country_code char(2) NOT NULL CHECK(country_code ~ '^[A-Z]{2}$'),

  -- ---- 邮编
  postcode_required boolean NOT NULL DEFAULT true,
  -- **正则源**：Java 侧**不硬编码**任何国家正则，一律从本列读。
  -- 规则形如 ^[0-9]{5}(-[0-9]{4})?$ —— 用 ^ $ 锚住，
  -- 否则「12345 任意后缀」也会被判成合法邮编。
  postcode_pattern varchar(200) NOT NULL DEFAULT '',
  postcode_note varchar(200) NOT NULL DEFAULT '',
  -- 必填就必须给出正则，否则这项永远判不了（规则存在却不可用 = 假配置）。
  CHECK(postcode_required = (btrim(postcode_pattern)<>'')),

  -- ---- 街道
  street_required boolean NOT NULL DEFAULT true,
  street_min_length int NOT NULL DEFAULT 4 CHECK(street_min_length>=0),

  -- ---- 其它必填项
  city_required boolean NOT NULL DEFAULT true,
  region_required boolean NOT NULL DEFAULT false,
  phone_required boolean NOT NULL DEFAULT true,

  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,country_code),
  CHECK(updated_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 4. 支付状态一致性规则（租户可维护的**码表**）
-- ---------------------------------------------------------------------------
-- 键是「付款方式 × 观察到的支付状态」。两个维度的词表都写死成 CHECK：
--   payment_method 与 V99 sales_order.payment_method 逐字相同
--   （写成子串查询会随 V99 改词表而悄悄失配）；
--   observed_status 是本模块的观察值词表。
-- expected_consistent = true  ⇒ 该组合**自洽**（明细落 CLEARED）
-- expected_consistent = false ⇒ 该组合**矛盾**（明细落 FLAGGED，严重度取 expected_severity）
-- ⚠️ **自洽组合也必须落行**：只落矛盾组合的话，「表里没有这一行」就有两种读法 ——
--   「这一行被判为自洽」与「租户根本没配这一组合」——
--   而这两种读法的后果完全相反（一个放行，一个该转人工）。
--   落全 24 行之后，「查不到规则」只有一种含义：**缺参照**（NO_REFERENCE），
--   与 V153「无成本基线落 NO_REFERENCE 而不是 CLEARED」同一纪律。
--   词表严格自洽：自洽 ⇔ 严重度为 OK；矛盾 ⇔ 严重度为 WARN / BLOCK。
CREATE TABLE order_address_check_payment_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  payment_method varchar(24) NOT NULL CHECK(payment_method IN (
      'PREPAID','COD','BANK_TRANSFER','CREDIT_TERM','FREIGHT_FORWARDER','OTHER')),
  observed_status varchar(8) NOT NULL CHECK(observed_status IN ('PAID','UNPAID','PARTIAL','COD')),
  expected_consistent boolean NOT NULL,
  expected_severity varchar(8) NOT NULL CHECK(expected_severity IN ('OK','WARN','BLOCK')),
  reason varchar(400) NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,payment_method,observed_status),
  -- 自洽 ⇔ OK；矛盾 ⇔ WARN / BLOCK。写反了的服务层在写库这一层就被拒。
  CHECK((expected_consistent) = (expected_severity = 'OK')),
  CHECK(updated_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 5. 校验结果（头：一张订单一次校验 = 一行）
-- ---------------------------------------------------------------------------
-- 判定结果是**凭据不是当前状态**：校验结论会被 9.2.1.3 异常拦截与 10 物流读，
-- 「当时按哪一版策略、算出什么结论」必须能复现（与 V153 evaluation 同一纪律）。
-- 所以同一张单跑第二次校验各占一行，历史不被覆盖。
CREATE TABLE order_address_check (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 复合外键指到本服务自己的 sales_order：订单被删则校验一并消失
  -- （校验是对那张单的判断，单没了判断也没有意义）。与 V153 同一手法。
  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL CHECK(btrim(order_no)<>''),
  order_source varchar(32) NOT NULL CHECK(order_source IN (
      'MANUAL','PLATFORM','AMAZON','SHOPIFY','EBAY','TIKTOK_SHOP','ALIBABA_1688')),
  order_currency char(3) NOT NULL CHECK(order_currency ~ '^[A-Z]{3}$'),
  order_amount numeric(19,4) NOT NULL CHECK(order_amount>=0),
  order_submitted_at timestamptz NOT NULL,

  -- ---- 付款方式（**快照**：读自 sales_order.payment_method）
  -- 之所以冗余存一份：支付一致性判定要用它，而它可能在订单被改后变化；
  -- 校验结论必须能复现当时看到的那一份。
  payment_method varchar(24) NOT NULL CHECK(payment_method IN (
      'PREPAID','COD','BANK_TRANSFER','CREDIT_TERM','FREIGHT_FORWARDER','OTHER')),
  -- ---- 支付状态（**观察值，由调用方提供**；本服务没有支付状态这个事实）
  -- NULL = 调用方没给 ⇒ PAYMENT_CONSISTENCY 落 NO_REFERENCE，**不是**「一致」。
  payment_status varchar(8) CHECK(payment_status IS NULL OR payment_status IN ('PAID','UNPAID','PARTIAL','COD')),
  -- 观察值的来源与时刻：有来源才追得回「这个状态是谁在什么时候说的」。
  payment_status_source varchar(24) CHECK(payment_status_source IS NULL OR payment_status_source IN (
      'PLATFORM_CALLBACK','TENANT_REPORTED','FINANCE_LEDGER')),
  payment_status_observed_at timestamptz,
  -- 给状态就必须同时给来源与时刻，否则「一致」这个结论追不回出处。
  CHECK(payment_status IS NULL OR (payment_status_source IS NOT NULL AND payment_status_observed_at IS NOT NULL)),

  -- ---- 收货地址快照（**快照**：读自 order_address_snapshot，address_role='SHIPPING'）
  shipping_country_code char(2) CHECK(shipping_country_code IS NULL
      OR shipping_country_code ~ '^[A-Z]{2}$'),
  shipping_postcode varchar(32) NOT NULL DEFAULT '',
  shipping_street varchar(300) NOT NULL DEFAULT '',

  -- ---- 策略快照：策略后来改了，这次校验当时按哪一版判的要留住
  policy_id uuid NOT NULL,
  policy_version bigint NOT NULL,
  policy_name varchar(120) NOT NULL,

  -- **判定基准时刻**：恒等于订单的 submitted_at（确定性）。
  assessed_reference_at timestamptz NOT NULL,

  -- ---- **输出之一：校验结果（可投递性）**
  -- DELIVERABLE     —— 地址类四项全部核对通过
  -- NOT_DELIVERABLE —— 存在 BLOCK 级地址命中
  -- UNDETERMINED    —— 有地址项缺参照（无该国格式规则）且策略选了 BLOCK_DECISION
  delivery_status varchar(16) NOT NULL
    CHECK(delivery_status IN ('DELIVERABLE','NOT_DELIVERABLE','UNDETERMINED')),

  -- ---- **输出之二：风险等级**
  risk_level varchar(8) NOT NULL CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  -- **货到付款标记**：由触发器从 payment_method 实算。
  cod_flagged boolean NOT NULL,
  -- 加粗句第 (1) 层：CRITICAL **只能**由货到付款触发，货到付款**必然**是 CRITICAL。
  -- 两头都钉：只钉一头的话，「把 COD 单降级」与「给非 COD 单虚标最高风险」都有缝。
  CHECK((risk_level = 'CRITICAL') = cod_flagged),

  -- ---- 命中项计数（由触发器从七行明细实算，见第八节）
  item_count int NOT NULL DEFAULT 7 CHECK(item_count = 7),
  flagged_count int NOT NULL DEFAULT 0 CHECK(flagged_count BETWEEN 0 AND 7),
  cleared_count int NOT NULL DEFAULT 0 CHECK(cleared_count BETWEEN 0 AND 7),
  no_reference_count int NOT NULL DEFAULT 0 CHECK(no_reference_count BETWEEN 0 AND 7),
  upstream_unavailable_count int NOT NULL DEFAULT 0 CHECK(upstream_unavailable_count BETWEEN 0 AND 7),
  not_applicable_count int NOT NULL DEFAULT 0 CHECK(not_applicable_count BETWEEN 0 AND 7),
  blocked_count int NOT NULL DEFAULT 0 CHECK(blocked_count BETWEEN 0 AND 7),
  warn_count int NOT NULL DEFAULT 0 CHECK(warn_count BETWEEN 0 AND 7),
  critical_count int NOT NULL DEFAULT 0 CHECK(critical_count BETWEEN 0 AND 7),
  -- 合计恒等于七行明细的条数。缺参照/未接通/不适用都算「评过但没结论」。
  CHECK(flagged_count+cleared_count+no_reference_count
        +upstream_unavailable_count+not_applicable_count = 7),
  CHECK(blocked_count+warn_count+critical_count <= flagged_count),

  -- 触发器算完合计后打的时间戳：它同时是「本行是否已被算过」的标记
  -- 与提交期不变量的判据（第八节末尾的延迟约束触发器）。
  totals_computed_at timestamptz,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,policy_id) REFERENCES order_address_check_policy(tenant_id,id),
  CHECK(totals_computed_at IS NULL OR totals_computed_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 6. 校验明细（**一行一个检查项，七项恒定各占一行**，缺失与未接通也占行）
-- ---------------------------------------------------------------------------
-- 照抄 V153 order_intercept_finding 同一做法：**缺失的项也占一行**。
-- 少一行的话，「这单为什么没被拦住」就只能从计数反推，而计数不保留是谁缺席的。
CREATE TABLE order_address_check_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  check_id uuid NOT NULL,

  item_code varchar(32) NOT NULL CHECK(item_code IN (
      'COUNTRY_EMBARGO',        -- 收货国是否在禁运清单
      'POSTCODE_FORMAT',        -- 邮编是否符合该国规则
      'STREET_FORMAT',          -- 街道是否符合该国规则
      'REQUIRED_CONTACT',       -- 必填联系方式是否齐全（按该国规则）
      'PAYMENT_CONSISTENCY',    -- 支付状态与付款方式是否自洽
      'COD_FULFILLMENT_RISK',   -- 货到付款 → 最高履约风险
      'COD_INSURANCE_POLICY'    -- 货到付款保险策略（**未交付**）
  )),

  -- ---- 单项状态：把「不可用」拆开而不是一个 null，让「这一项为什么没结论」
  --      在详情页上是四句不同的话：没量过、没参照、上游没接通、这一单形态上不适用。
  status varchar(24) NOT NULL CHECK(status IN (
      'FLAGGED',               -- 命中
      'CLEARED',               -- 真实 SQL / 真实规则判定为正常
      'NO_REFERENCE',          -- 缺参照：没有该国格式规则 / 没有支付状态观察值
      'UPSTREAM_UNAVAILABLE',  -- 上游未交付（COD_INSURANCE_POLICY 恒为此值）
      'NOT_APPLICABLE'         -- 这一单形态上不适用（如非货到付款单）
  )),
  -- ---- 严重度：决定风险等级落在哪一档（第四节）
  --   INFO     仅提示（不影响任何一档）
  --   WARN     命中 → 风险等级至少 MEDIUM
  --   BLOCK    命中 → 地址类则不可投递，非地址类则风险等级至少 HIGH
  --   CRITICAL 命中 → 风险等级 CRITICAL（**只有货到付款能用这一档**）
  severity varchar(8) NOT NULL CHECK(severity IN ('INFO','WARN','BLOCK','CRITICAL')),
  -- 硬绑定：命中才谈得上严重度；没命中时恒为 INFO。
  CHECK((status='FLAGGED') = (severity<>'INFO')),
  -- **CRITICAL 只能由货到付款项使用**：DB 层钉死，防止把「最高风险」这个档
  -- 随手发给任何一个命中项 —— 那会让「货到付款风险最高」这句话失去专属性。
  CHECK(severity<>'CRITICAL' OR item_code='COD_FULFILLMENT_RISK'),
  -- 反过来：货到付款项要么是 **CRITICAL 的命中**，要么根本不适用 ——
  -- 不许出现「货到付款项命中了但严重度是 WARN」这种含糊表达：
  -- 那样「最高风险」这一档就有了第二个来源，加粗句失去专属性。
  CHECK(item_code<>'COD_FULFILLMENT_RISK'
        OR (status='FLAGGED' AND severity='CRITICAL')
        OR (status='NOT_APPLICABLE' AND severity='INFO')),
  -- 保险策略项只有两种状态：**未交付**（货到付款单）或**不适用**（非货到付款单）。
  -- 不许出现「CLEARED」—— 蓝图中没有任何模块持有保险策略，
  -- 说它「已核对、未命中」等于宣称「保险策略没问题」，那是**假绿**。
  CHECK(item_code<>'COD_INSURANCE_POLICY'
        OR status IN ('UPSTREAM_UNAVAILABLE','NOT_APPLICABLE')),
  -- 两个货到付款项必须同时成立：一个说「这一单是货到付款」，另一个说
  -- 「而它的保险策略未交付」。只落其一会让「货到付款」这件事有第二种读法。
  CHECK(item_code<>'COD_INSURANCE_POLICY' OR severity='INFO'),

  -- ---- 取值来源：这一项的结论**从哪来**
  evidence_source varchar(40) NOT NULL CHECK(evidence_source IN (
      'ADDRESS_SNAPSHOT',              -- 读 order_address_snapshot（V99，本服务同库）
      'TENANT_EMBARGO_LIST',           -- 租户可维护的禁运国家清单
      'TENANT_COUNTRY_RULE',           -- 租户可维护的国别地址格式规则
      'TENANT_PAYMENT_RULE',           -- 租户可维护的支付一致性规则
      'ORDER_PAYMENT_METHOD',          -- 读 sales_order.payment_method
      'CALLER_PAYMENT_STATUS',         -- 调用方随请求提供的支付状态观察值
      'MODULE_10_INSURANCE_NOT_DELIVERED' -- 保险策略无 L4 归属，未交付
  )),

  -- ---- 原始观测值：**只有真正可比的数值才进这一列**。
  --   REQUIRED_CONTACT → 缺失的必填项个数（0 = 齐全）
  --   其余项是**文本比对**（国家码、邮编字面量、状态组合），
  --   文本比对结果一律走 evidence_reference，不进 numeric 列
  --   （把文本塞进 numeric 列要么被 DB 拒，要么被写成 0 然后被读成「量过了，结果是 0」）。
  observed_value numeric(19,6),
  -- 对照阈值：街道最小长度、缺失项上限等。给出「实际值」与「对照值」，
  -- 客服才能原话复述「街道 3 个字符 < 最小 5 个」而不是「系统判定为街道异常」。
  threshold_value numeric(19,6),
  -- 命中计数：本项在多少次比较里命中。恒为 0 或 1 是不诚实的：
  -- 必填项可能一次缺三个，而「缺三个」与「缺一个」处置完全不同。
  match_count int NOT NULL DEFAULT 0 CHECK(match_count>=0),
  CHECK((status='FLAGGED') = (match_count>0)),
  -- 不可用的项没有观测值：给 0 等于宣称「量过了，结果是 0」。
  CHECK(status IN ('FLAGGED','CLEARED') OR observed_value IS NULL),

  -- 参照的实体：**文本比对的结果落这里**。
  --   COUNTRY_EMBARGO   → 命中的收货国码（'US' / 禁运清单里的那一项）
  --   POSTCODE_FORMAT   → 命中的邮编字面量与该国规则
  --   STREET_FORMAT     → 命中的街道字面量与该国规则
  --   REQUIRED_CONTACT  → 缺失的字段名清单
  --   PAYMENT_CONSISTENCY → 命中的 (payment_method, observed_status) 组合
  -- 让详情页能直接跳回「是哪一行、哪一单、哪个国家」而不必反推。
  evidence_reference varchar(400) NOT NULL DEFAULT '',
  -- 缺参照 / 上游未接通时必须说清**缺的是什么、为什么不可用**（不能让人反推）。
  explanation text NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,check_id,item_code),
  FOREIGN KEY(tenant_id,check_id) REFERENCES order_address_check(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 七、基线码表：按租户幂等落库（ON CONFLICT DO NOTHING）
-- ===========================================================================
-- ⚠️ 落库之后**判定真身是表**，不是这个函数：改一行规则不用发版。
--   函数只负责「第一次登记策略时给新租户装一份基线」。
-- ⚠️ 基线数据写在**迁移脚本（SQL）**里而不是 Java 里 —— 本模块的硬要求是
--   「不许写死国家数组在 Java 里」。Java 侧只读表，永远不内置任何国家清单。
--
-- 国别邮编基线（跨境件最常见的一批；覆盖不到的国家租户自己加行即可）：
--   一个国家一套规则：邮编位数、字母数字混排、是否必填、街道/城市/省份/电话是否必填。
CREATE OR REPLACE FUNCTION order_address_check_install_baseline(p_tenant uuid)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
  v_rows bigint;
BEGIN
  -- ---- 国别地址格式规则基线
  INSERT INTO order_address_check_country_rule(
    id, tenant_id, country_code, postcode_required, postcode_pattern, postcode_note,
    street_required, street_min_length, city_required, region_required, phone_required,
    enabled, created_at, updated_at)
  SELECT gen_random_uuid(), p_tenant, v.country, v.postcode_required, v.postcode_pattern,
         v.postcode_note, v.street_required, v.street_min, v.city_required, v.region_required,
         v.phone_required, true, now(), now()
    FROM (VALUES
      ('US', true,  '^[0-9]{5}(-[0-9]{4})?$', 'ZIP 或 ZIP+4',              true,  5, true,  false, true),
      ('CA', true,  '^[A-Z][0-9][A-Z] ?[0-9][A-Z][0-9]$', '加拿大邮编 A1A 1A1',   true,  5, true,  true,  true),
      ('GB', true,  '^[A-Z]{1,2}[0-9][A-Z0-9]? ?[0-9][A-Z]{2}$', '英国邮编',   true,  4, true,  true,  true),
      ('DE', true,  '^[0-9]{5}$',                          '德国 5 位数字',        true,  3, true,  true,  true),
      ('FR', true,  '^[0-9]{5}$',                          '法国 5 位数字',        true,  3, true,  true,  true),
      ('IT', true,  '^[0-9]{5}$',                          '意大利 5 位数字',      true,  3, true,  true,  true),
      ('ES', true,  '^[0-9]{5}$',                          '西班牙 5 位数字',      true,  3, true,  true,  true),
      ('NL', true,  '^[0-9]{4} ?[A-Z]{2}$',                '荷兰 4 位 + 2 字母',   true,  3, true,  true,  true),
      ('BE', true,  '^[0-9]{4}$',                          '比利时 4 位数字',      true,  3, true,  true,  true),
      ('AT', true,  '^[0-9]{4}$',                          '奥地利 4 位数字',      true,  3, true,  true,  true),
      ('PT', true,  '^[0-9]{4}(-?[0-9]{3})?$',            '葡萄牙 4 位 + 3 位',   true,  3, true,  true,  true),
      ('IE', false, '',                                    '爱尔兰无统一邮编',     true,  3, true,  false, true),
      ('CH', true,  '^[0-9]{4}$',                          '瑞士 4 位数字',        true,  3, true,  true,  true),
      ('SE', true,  '^[0-9]{3} ?[0-9]{2}$',                '瑞典 5 位',            true,  3, true,  true,  true),
      ('PL', true,  '^[0-9]{2}-[0-9]{3}$',                 '波兰 NN-NNN',          true,  3, true,  true,  true),
      ('CN', true,  '^[0-9]{6}$',                          '中国 6 位数字',        true,  4, true,  true,  true),
      ('JP', true,  '^[0-9]{3}-?[0-9]{4}$',                '日本 7 位',            true,  4, true,  true,  true),
      ('KR', true,  '^[0-9]{5}$',                          '韩国 5 位数字',        true,  3, true,  true,  true),
      ('HK', false, '',                                    '香港无邮编',           true,  4, true,  false, true),
      ('TW', true,  '^[0-9]{3}([0-9]{2})?$',               '台湾 3 位 / 5 位',      true,  4, true,  true,  true),
      ('SG', true,  '^[0-9]{6}$',                          '新加坡 6 位数字',      true,  4, true,  true,  true),
      ('MY', true,  '^[0-9]{5}$',                          '马来西亚 5 位数字',    true,  3, true,  true,  true),
      ('TH', true,  '^[0-9]{5}$',                          '泰国 5 位数字',        true,  3, true,  true,  true),
      ('VN', true,  '^[0-9]{5}$',                          '越南 5 位数字',        true,  3, true,  true,  true),
      ('IN', true,  '^[0-9]{6}$',                          '印度 6 位数字',        true,  4, true,  true,  true),
      ('AE', false, '',                                    '阿联酋无统一邮编',     true,  3, true,  true,  true),
      ('SA', true,  '^[0-9]{5}(-[0-9]{4})?$',              '沙特 5 位 / 5-4 位',   true,  3, true,  true,  true),
      ('AU', true,  '^[0-9]{4}$',                          '澳大利亚 4 位数字',    true,  3, true,  true,  true),
      ('NZ', true,  '^[0-9]{4}$',                          '新西兰 4 位数字',      true,  3, true,  true,  true),
      ('BR', true,  '^[0-9]{5}-?[0-9]{3}$',                '巴西 5 位 / 8 位',     true,  4, true,  true,  true),
      ('MX', true,  '^[0-9]{5}$',                          '墨西哥 5 位数字',      true,  3, true,  true,  true),
      ('AR', true,  '^[A-Z]?[0-9]{4}[A-Z]{0,3}$',          '阿根廷邮编带字母',     true,  4, true,  true,  true),
      ('RU', true,  '^[0-9]{6}$',                          '俄罗斯 6 位数字',      true,  4, true,  true,  true),
      ('ZA', true,  '^[0-9]{4}$',                          '南非 4 位数字',        true,  3, true,  true,  true)
    ) AS v(country, postcode_required, postcode_pattern, postcode_note,
           street_required, street_min, city_required, region_required, phone_required)
  ON CONFLICT (tenant_id, country_code) DO NOTHING;

  -- ---- 支付状态一致性规则基线（**6 种付款方式 × 4 种观察状态 = 24 行，恒落全**）
  -- ⚠️ 自洽组合**也落行**：只落矛盾组合的话，「表里没有这一行」就有两种读法
  --   （「被判为自洽」与「租户没配」），而这两种读法的后果完全相反。
  --   落全之后「查不到规则」只意味着**缺参照**（NO_REFERENCE），不是「自洽」。
  -- ⚠️ expected_consistent **由严重度派生**（severity = 'OK' ⇒ 自洽），
  --   不另写一份 CASE：两份 CASE 各判各的，迟早有一格不一致 ——
  --   而「自洽」与「矛盾」不一致的行会让「这一单到底放不放行」有两个答案。
  INSERT INTO order_address_check_payment_rule(
    id, tenant_id, payment_method, observed_status, expected_consistent,
    expected_severity, reason, enabled, created_at, updated_at)
  SELECT gen_random_uuid(), p_tenant, m.method, s.status,
         (v.severity = 'OK'), v.severity, v.reason, true, now(), now()
    FROM unnest(ARRAY['PREPAID','COD','BANK_TRANSFER','CREDIT_TERM','FREIGHT_FORWARDER','OTHER'])
         AS m(method)
   CROSS JOIN unnest(ARRAY['PAID','UNPAID','PARTIAL','COD']) AS s(status)
   CROSS JOIN LATERAL (
    SELECT CASE
             -- 货到付款单报「货到付款」是**自洽**的（这一格必须排在 s.status='COD' 之前，
             -- 否则「货到付款」这一列会被一律判成矛盾）。
             WHEN m.method = 'COD' AND s.status = 'COD' THEN 'OK'
             WHEN s.status = 'COD' THEN 'BLOCK'
             WHEN m.method = 'PREPAID' AND s.status = 'UNPAID' THEN 'BLOCK'
             WHEN m.method = 'COD' AND s.status = 'PAID' THEN 'BLOCK'
             WHEN s.status = 'PARTIAL' THEN 'WARN'
             ELSE 'OK'
           END AS severity,
           CASE
             WHEN m.method = 'COD' AND s.status = 'COD' THEN '付款方式 COD 与支付状态 COD 自洽'
             WHEN s.status = 'COD' THEN '付款方式 ' || m.method || ' 却报货到付款：付款方式与支付状态互相矛盾'
             WHEN m.method = 'PREPAID' AND s.status = 'UNPAID' THEN '预付单未收款：发出去可能收不回钱'
             WHEN m.method = 'COD' AND s.status = 'PAID' THEN '货到付款单却报已收款：要么状态串了，要么这单不是货到付款'
             WHEN s.status = 'PARTIAL' THEN '只收到部分款项：金额与状态需要人工核对'
             ELSE '付款方式 ' || m.method || ' 与支付状态 ' || s.status || ' 自洽'
           END AS reason
   ) v
  ON CONFLICT (tenant_id, payment_method, observed_status) DO NOTHING;

  -- 返回装完之后本租户的行数（码表总行数）：调用方能立刻确认「基线装上了」，
  -- 而不是「调用没报错 ⇒ 大概装上了」。
  SELECT count(*) INTO v_rows FROM order_address_check_country_rule WHERE tenant_id = p_tenant;
  v_rows := v_rows
    + (SELECT count(*) FROM order_address_check_payment_rule WHERE tenant_id = p_tenant);
  RETURN v_rows;
END $$;

-- ===========================================================================
-- 八、触发器
-- ===========================================================================
-- 本文件所有约束触发器在写行时**一律回表读当前行**，不使用 NEW 的可变列：
-- 服务层的写入顺序是「先插头 → 写七行明细 → 回填头」，
-- 延迟到提交时那次 INSERT 拿着的仍是半成品的 NEW；用 NEW 每次必报。
-- （与 V92 guard_routing_decision_switch、V150 guard_order_risk_assessment_totals_settled 同一个坑、同一个解法。）

-- ---------------------------------------------------------------------------
-- 8.1 合计与两个输出由 DB 实算：服务层**不**自己写计数、风险等级与可投递性
-- ---------------------------------------------------------------------------
-- 为什么必须在 DB 侧算：服务层算一遍、触发器再算一遍，早晚会有一次两边不一致，
-- 而「校验记录上写着命中 2 条，它与七行明细算出来的不一样」是无法排查的。
-- 风险等级阶梯（第一节 / 第四节）：
--   货到付款 → CRITICAL（**压在最上面**：加粗句说它履约风险最高）
--   否则有 BLOCK 级命中 → HIGH
--   否则有 WARN 级命中 → MEDIUM
--   否则 → LOW
-- 可投递性只看**地址类四项**（禁运国 / 邮编 / 街道 / 必填联系方式）：
--   有地址类 BLOCK 命中 → NOT_DELIVERABLE
--   否则有地址类缺参照且策略是 BLOCK_DECISION → UNDETERMINED
--   否则 → DELIVERABLE
-- ⚠️ SECURITY DEFINER 是**必需的**，不是装饰：8.4 对这些列做了列级 UPDATE REVOKE，
--   非 SECURITY DEFINER 的触发器会以调用者（trade_app）的权限执行那个 UPDATE，
--   于是每次校验都撞 "permission denied"。加上它之后触发器以迁移角色（表 owner）执行 ——
--   而 FORCE ROW LEVEL SECURITY 对 owner 同样生效，RLS 一道都不少。
CREATE OR REPLACE FUNCTION order_address_check_apply_totals(p_check uuid, p_tenant uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  v_check order_address_check%ROWTYPE;
  v_policy varchar(24);
  v_total int;
  v_flagged int;
  v_cleared int;
  v_no_reference int;
  v_upstream int;
  v_not_applicable int;
  v_blocked int;
  v_warn int;
  v_critical int;
  v_cod boolean;
  v_address_blocked int;
  v_address_missing int;
  v_risk varchar(8);
  v_delivery varchar(16);
BEGIN
  SELECT * INTO v_check FROM order_address_check
   WHERE id = p_check AND tenant_id = p_tenant;
  IF NOT FOUND THEN
    RAISE EXCEPTION '地址支付校验 % 不存在或不属于本租户', p_check USING ERRCODE='23503';
  END IF;

  -- 回表读明细行，而不是相信调用方传进来的任何数字。
  SELECT count(*),
         count(*) FILTER (WHERE i.status='FLAGGED'),
         count(*) FILTER (WHERE i.status='CLEARED'),
         count(*) FILTER (WHERE i.status='NO_REFERENCE'),
         count(*) FILTER (WHERE i.status='UPSTREAM_UNAVAILABLE'),
         count(*) FILTER (WHERE i.status='NOT_APPLICABLE'),
         count(*) FILTER (WHERE i.status='FLAGGED' AND i.severity='BLOCK'),
         count(*) FILTER (WHERE i.status='FLAGGED' AND i.severity='WARN'),
         count(*) FILTER (WHERE i.status='FLAGGED' AND i.severity='CRITICAL'),
         -- 地址类四项：禁运国 / 邮编 / 街道 / 必填联系方式
         count(*) FILTER (WHERE i.status='FLAGGED' AND i.severity='BLOCK'
                            AND i.item_code IN ('COUNTRY_EMBARGO','POSTCODE_FORMAT',
                                                'STREET_FORMAT','REQUIRED_CONTACT')),
         count(*) FILTER (WHERE i.status='NO_REFERENCE'
                            AND i.item_code IN ('COUNTRY_EMBARGO','POSTCODE_FORMAT',
                                                'STREET_FORMAT','REQUIRED_CONTACT'))
    INTO v_total, v_flagged, v_cleared, v_no_reference, v_upstream, v_not_applicable,
         v_blocked, v_warn, v_critical, v_address_blocked, v_address_missing
    FROM order_address_check_item i
   WHERE i.check_id = p_check AND i.tenant_id = p_tenant;

  IF v_total = 0 THEN
    RETURN;
  END IF;

  SELECT p.missing_reference_policy INTO v_policy
    FROM order_address_check_policy p
   WHERE p.id = v_check.policy_id AND p.tenant_id = p_tenant;
  IF v_policy IS NULL THEN
    RAISE EXCEPTION '校验策略 % 不存在或不属于本租户：校验结论无法计算', v_check.policy_id
      USING ERRCODE='23503';
  END IF;

  -- **货到付款标记**从订单快照的付款方式实算（不是从明细行反推）：
  -- 货到付款是订单的属性，「这一单是不是货到付款」不该由「校验时碰巧写了哪几行」决定。
  v_cod := (v_check.payment_method = 'COD');

  -- ---- 风险等级：货到付款压在最上面（第四节）
  IF v_cod THEN
    v_risk := 'CRITICAL';
  ELSIF v_blocked > 0 THEN
    v_risk := 'HIGH';
  ELSIF v_warn > 0 THEN
    v_risk := 'MEDIUM';
  ELSE
    v_risk := 'LOW';
  END IF;

  -- ---- 可投递性：只看地址类四项
  IF v_address_blocked > 0 THEN
    v_delivery := 'NOT_DELIVERABLE';
  ELSIF v_address_missing > 0 AND v_policy = 'BLOCK_DECISION' THEN
    v_delivery := 'UNDETERMINED';
  ELSE
    v_delivery := 'DELIVERABLE';
  END IF;

  UPDATE order_address_check
     SET item_count = 7,
         flagged_count = v_flagged,
         cleared_count = v_cleared,
         no_reference_count = v_no_reference,
         upstream_unavailable_count = v_upstream,
         not_applicable_count = v_not_applicable,
         blocked_count = v_blocked,
         warn_count = v_warn,
         critical_count = v_critical,
         cod_flagged = v_cod,
         risk_level = v_risk,
         delivery_status = v_delivery,
         totals_computed_at = now()
   WHERE id = p_check AND tenant_id = p_tenant;
END $$;

-- 合计由**第七个明细落下时**触发，而不是头行 INSERT 时：
-- 服务层的写入顺序是「先插头 → 再写 7 行明细」，头插入那一刻明细一张都没有，
-- 在头上挂触发器只能算出「0 项」，真正的算法永远跑不到。
CREATE OR REPLACE FUNCTION guard_order_address_check_item_row_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE v_total int;
BEGIN
  SELECT count(*) INTO v_total
    FROM order_address_check_item i
   WHERE i.check_id = NEW.check_id AND i.tenant_id = NEW.tenant_id;
  -- ⚠️ 只在**满 7 行**时算合计，**不要**在每一行插入时就断言「必须已经是 7」：
  -- 服务层是分 7 条 INSERT 写明细的，前六行落下时 count 分别是 1..6，
  -- 在那里抛错等于让本模块自己的写入路径永远走不通。
  IF v_total = 7 THEN
    PERFORM order_address_check_apply_totals(NEW.check_id, NEW.tenant_id);
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER order_address_check_item_row_complete
  AFTER INSERT ON order_address_check_item
  FOR EACH ROW EXECUTE FUNCTION guard_order_address_check_item_row_complete();

-- ---------------------------------------------------------------------------
-- 8.2 提交期不变量：**没有算过合计的校验不许留在库里**
-- ---------------------------------------------------------------------------
-- 头先插、明细后写：服务层中途抛异常会整体回滚，那没问题；
-- 怕的是**有人绕开服务层直连写入**，留下一条 risk_level='LOW' 的头
-- —— 清单页读到的就是「低风险」。所以延迟到提交再查一次 totals_computed_at。
CREATE OR REPLACE FUNCTION guard_order_address_check_totals_settled() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_totals timestamptz;
BEGIN
  -- ⚠️ 必须**回表读当前状态**，不能看 NEW。
  --   延迟约束触发器把事件排队时，队里存的是 INSERT 那一刻的 NEW，
  --   那时 totals_computed_at 当然是 NULL（合计要到第 7 个明细落下才回填）。
  SELECT c.totals_computed_at INTO v_totals
    FROM order_address_check c
   WHERE c.id = NEW.id AND c.tenant_id = NEW.tenant_id;
  -- 行已被删（订单被删 → 校验级联删掉）：没有行可断言，放行。
  IF FOUND AND v_totals IS NULL THEN
    RAISE EXCEPTION '地址支付校验 % 从未被算过合计（风险等级 %）：明细行没写完就落库会让这单被读成「低风险」',
      NEW.id, NEW.risk_level USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER order_address_check_totals_settled
  AFTER INSERT OR UPDATE ON order_address_check
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_address_check_totals_settled();

-- ---------------------------------------------------------------------------
-- 8.3 明细不可改写：校验是「当时按那一版策略算出来的」凭据
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_order_address_check_item_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：改一格校验说明就再也复算不出当时为什么判成这个风险等级',
    TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_address_check_item_append_only BEFORE UPDATE OR DELETE ON order_address_check_item
  FOR EACH ROW EXECUTE FUNCTION guard_order_address_check_item_append_only();

-- ---------------------------------------------------------------------------
-- 8.4 货到付款项必须是 CRITICAL 的命中（加粗句第 (1) 层的兜底）
-- ---------------------------------------------------------------------------
-- 表级 CHECK 已经钉了「CRITICAL 只能由 COD_FULFILLMENT_RISK 使用」与
-- 「COD_FULFILLMENT_RISK 要么是 CRITICAL 命中要么不适用」。
-- 这里再钉一层：**码表里的国家规则与支付规则的 pattern 不得为空**，
-- 否则「规则存在却不可用」会让该项永远判不了，而界面会显示「已配置」。
CREATE OR REPLACE FUNCTION guard_order_address_check_country_rule_pattern() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.postcode_required AND btrim(NEW.postcode_pattern) = '' THEN
    RAISE EXCEPTION '国别规则 %：邮编必填却没给正则（该项会永远判不了，而界面会显示「已配置」）',
      NEW.country_code USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_address_check_country_rule_pattern
  BEFORE INSERT OR UPDATE ON order_address_check_country_rule
  FOR EACH ROW EXECUTE FUNCTION guard_order_address_check_country_rule_pattern();

-- ===========================================================================
-- 九、索引
-- ===========================================================================
-- ① 校验结果列表（本模块最常跑的一条查询）：按判定基准时刻倒序。
CREATE INDEX order_address_check_by_reference
  ON order_address_check(tenant_id, assessed_reference_at DESC, order_no, id);
-- ② 风险等级筛列表页：货到付款单要能一眼捞出来（加粗句的落点之一）。
CREATE INDEX order_address_check_by_risk
  ON order_address_check(tenant_id, risk_level, assessed_reference_at DESC);
-- ③ 按订单查最近一次校验（9.2.1.3 异常拦截的读路径）。
CREATE INDEX order_address_check_by_order
  ON order_address_check(tenant_id, order_id, assessed_reference_at DESC, id DESC);
-- ④ 明细按检查项筛（「哪些单命中了禁运国」）。
CREATE INDEX order_address_check_item_by_code
  ON order_address_check_item(tenant_id, check_id, status);
-- ⑤ 禁运清单按国家码查（判定热路径）。
CREATE INDEX order_address_check_embargo_by_country
  ON order_address_check_embargo_rule(tenant_id, country_code) WHERE enabled;
-- ⑥ 国别规则按国家码查（判定热路径）。
CREATE INDEX order_address_check_country_by_code
  ON order_address_check_country_rule(tenant_id, country_code) WHERE enabled;
-- ⑦ 支付规则按 (付款方式, 观察状态) 查（判定热路径）。
CREATE INDEX order_address_check_payment_by_key
  ON order_address_check_payment_rule(tenant_id, payment_method, observed_status) WHERE enabled;

-- ===========================================================================
-- 十、多租户：RLS + 授权（与 trade V153 第九节、V150 第八节同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：禁运清单与国别规则是**跨订单宽条件**的判定源，
--   漏掉租户谓词就是**跨租户归因** —— A 家的禁运国清单会拦下 B 家的订单，
--   而 B 家连自己被拦都不知道。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_address_check_policy','order_address_check_embargo_rule',
    'order_address_check_country_rule','order_address_check_payment_rule',
    'order_address_check','order_address_check_item'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_address_check_policy,order_address_check_embargo_rule,
  order_address_check_country_rule,order_address_check_payment_rule,
  order_address_check,order_address_check_item TO trade_app;
-- 授权在前、收回在后（V153 第十节的顺序）：校验明细是留痕，不可改不可删。
REVOKE UPDATE,DELETE ON order_address_check_item FROM trade_app;
-- 头表：运行角色**完全不能 UPDATE** —— 合计、风险等级、可投递性三组值全部由触发器写。
--
-- ⚠️ **必须收回表级 UPDATE，不能只写列级 REVOKE**：
--   PG 里表级 UPDATE 蕴含每列 UPDATE，所以
--   「GRANT ... UPDATE ON t」之后紧跟「REVOKE UPDATE(列) ON t」是**空操作** ——
--   has_column_privilege 仍然是 true，撤权限这件事看起来做成了、其实没做成。
--   后果不是「权限没收紧」这么轻：撤掉 REVOKE 之后，运行角色立刻就能改风险等级，
--   而**测试仍然全绿**（因为表级 CHECK 还在挡着）—— 直到有人把 CHECK 也改掉，
--   「货到付款恒为最高风险」这件事就悄无声息地没了。
--   11.1.1.1 车道已经在同一批踩过这个坑并把它记进台账，这里照抄它的修法：
--   **先 REVOKE 表级 UPDATE，再按需要授回允许的列**。本表的允许列集合为空
--   （服务层只 INSERT 头行，头行的任何 UPDATE 都只应由触发器做），所以直接收回整张表。
REVOKE UPDATE ON order_address_check FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**（sales_order / sales_order_line / order_address_snapshot /
-- sales_order_party_snapshot（V99）、platform_order 簇（V207）、order_intercept_*（V153）、
-- order_risk_*（V150）全部只读），跨车道 DDL 回归面 = 0。
