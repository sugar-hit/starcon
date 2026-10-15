-- ===========================================================================
-- 9.6.2.1 代发差价与对账
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md:262）：
--   职责：管理代发业务的成本与结算：代发采购成本（供应商结算价 + 供应商运费）、
--         我方售价、差价毛利；按周期与供应商对账，识别亏损代发单（售价低于
--         代发成本+平台费）并预警。
--   输入：代发单成本与售价（9.6.1.2）；平台费用规则（5.2.3.1）；供应商结算周期。
--   输出：代发差价与毛利报表（按店铺/供应商/SKU）；供应商对账单（→ 12 应付）；
--         亏损单预警。
--   关系：上游 = 9.6.1.2、5 定价；下游 = 12.1.2.1 应付、13.4.1.2 利润。
--         **代发模式的"成本"是采购差价而非自有库存成本**，利润口径与自营不同，
--         13 利润报表需按经营模式分组呈现（13.4.1.1 已含模式维度），
--         否则代发店铺利润会被算错。
--
-- ---------------------------------------------------------------------------
-- 一、本模块唯一不可让步的一条：平台费未登记 = 「无法判定」，不是「按 0 算」
-- ---------------------------------------------------------------------------
-- 蓝图把亏损判据写成「售价低于代发成本**+ 平台费**」。但输入里的
-- 「平台费用规则（5.2.3.1）」**未交付**，5.2.3.2（物流与头程费用）同样未交付。
-- 也就是说这条判据的**两个加数里有一个根本不存在**。
--
-- 最省事的实现是把平台费当 0。那是本模块最容易犯、且后果最重的错，两个方向
-- 都错：
--   * 按 0 算 → 售价 30、代发成本 12、真实平台费 8 的单会判成「不亏」，
--     而它真实毛利只有 10（甚至售价 5、代发成本 12 时）——**真实亏损被放过去**；
--   * 更糟的是反过来：只要有一单售价低于代发成本，按 0 算会把「所有代发单」
--     判成亏损，预警列表瞬间被噪声淹没，真正的「已含平台费的亏损」反而没人看。
--
-- 因此本迁移把结论做成**三值**而不是两值，并且**用生成列把它钉在 DB 层**：
--
--   spread_before_fee = 售价 − 采购结算价 − 供应商运费        （不含平台费的差价）
--   spread_after_fee  = spread_before_fee − 平台费            （平台费未登记时为 NULL）
--
--   LOSS_CONFIRMED        售价 < 代发成本
--                         —— 即使平台费未登记也**成立**：平台费恒 ≥ 0，
--                            只会让它更亏。少了平台费只可能高估毛利、不会漏判亏损。
--   LOSS_CONFIRMED        平台费已登记 且 售价 < 代发成本 + 平台费
--   NOT_LOSS              平台费已登记 且 售价 ≥ 代发成本 + 平台费
--   UNDETERMINED_NO_FEE   售价 ≥ 代发成本 且 平台费**未登记**
--                         —— 真实结论既可能是 NOT_LOSS 也可能是 LOSS_CONFIRMED，
--                            **无法判定**。它不是「不亏」，单独成桶。
--
-- ⚠️ UNDETERMINED_NO_FEE **不能并进 NOT_LOSS 桶**（照抄 9.6.1.3 已验证的
-- 「未登记项落独立 UNSET 桶」做法）：并进去之后界面上就是一片绿，
-- 「平台费一直没登记」这件事会彻底消失，直到某天有人真的亏了才发现。
--
-- 平台费建模为**可登记的费率来源**（fulfillment_dropship_fee_rate），不是硬编码常量：
-- 5.2.3.1 交付后由它供给，在此之前由租户自行登记（source=TENANT_MAINTAINED）。
-- 费率观测值强制带 observed_at —— 用写入时刻冒充会让「这条费率是什么时候的」
-- 无法复核（与 V118 供应商库存观测同一纪律）。
--
-- ---------------------------------------------------------------------------
-- 二、利润口径隔离：代发单的「成本」是采购差价，不是自有库存成本
-- ---------------------------------------------------------------------------
-- 蓝图把这句话加粗写在「关系」段并给出后果：「否则代发店铺利润会被算错」。
--
-- 落到表结构上是两件事：
--   * fulfillment_dropship_margin_fact.business_model 恒为 'DROPSHIP'，
--     cost_basis 恒为 'PURCHASE_SPREAD'，两个都由 **CHECK 钉死**。
--     自营单的成本口径是自有库存成本（另一套会计），一行自营单进这张表
--     就会让 13 利润报表把代发店铺按自营口径算 —— 正是蓝图警告的那件事。
--     本表**只装代发单**，自营口径归 13.4.1.1（未交付，capabilities 标 false）。
--   * 对账单头同样带这两个列，任何一笔代发对账都不可能被读成自营成本对账。
--
-- ---------------------------------------------------------------------------
-- 三、每一行差价都必须能从它自己的构成项复算出来
-- ---------------------------------------------------------------------------
-- 差价毛利是本模块唯一会被拿去付款的数字。报表上一行差价如果不能就地复算，
-- 供应商一句「这单我收 13 你算 12」就只能靠翻聊天记录。
--
-- 因此：
--   * dropship_cost / spread_before_fee / spread_after_fee / loss_verdict
--     全部是 **GENERATED ALWAYS 生成列**，只引用基列。
--     应用层**改不了**这四个数，也就不可能出现「头上的差价与行里的构成项对不上」。
--   * spread_after_fee 里 platform_fee_amount 为 NULL 时**整个表达式为 NULL**，
--     而不是 0。界面上「差价 = 0」与「差价算不出来」必须长得不一样。
--   * 集成测试断言的是**这个等式本身**（差价 == 售价 − 采购 − 运费 − 平台费），
--     不是某个硬编码的期望数字。
--
-- ---------------------------------------------------------------------------
-- 四、多币种：不许跨币种相加，汇率未登记如实标 UNSET
-- ---------------------------------------------------------------------------
-- AGENTS.md 要求多币种结算，代发供应商又天然按各自币种报价（1688 人民币、
-- 1688 跨境美元、海外仓欧元……）。一张对账单要给出「应付供应商多少钱」，
-- 就必须折算，而折算需要汇率。
--
-- 汇率来源同样未交付，因此建模为**可登记**（fulfillment_dropship_fx_rate）。
-- 未登记时**不用 1.0 假装**（1.0 等于把人民币当美元用，算出来的应付是天文数字
-- 或负数，比报错更糟），而是：
--   fx_rate_status = 'SAME_CURRENCY'  原币即结算币，直接用
--                   = 'APPLIED'        按登记汇率折算，fx_rate_value 留证
--                   = 'UNSET'          汇率未登记 → 折算列**整组为 NULL**，
--                                      且该行被 is_in_totals 排除在合计之外，
--                                      单独计入 undetermined_* 桶。
--
-- 对账单头的合计是**结算币种下**的合计，不是把各币种原值加在一起。
--
-- ---------------------------------------------------------------------------
-- 五、对账结论要留证：事后改费率不能让历史对账单看起来是按新费率算的
-- ---------------------------------------------------------------------------
-- 与 9.6.1.3 的 rule_threshold_hours / rule_evidence 同一手法：
--   * margin_fact 在**算出那一刻**就把当时用的费率 id、费率状态、算式原文
--     写进自己的行；fee_rate 与 loss_evidence 由触发器逐字段不可改写。
--   * reconciliation_line 是**对账时刻的冻结快照**（原币构成项 + 折算值 +
--     汇率 + 算式），不是运行期去 join 当前费率得到的视图。
--     改了费率，历史对账单上的数不会跟着变。
--   * reconciliation 在 FINALIZED 之后，全部合计与证据字段不可改写。
--     要改只有一条路：作废（DISPUTED）后按新费率重开一张。
--
-- ---------------------------------------------------------------------------
-- 六、下游 12.1.2.1 应付单未交付：落真待办，不造假
-- ---------------------------------------------------------------------------
-- 蓝图输出「供应商对账单（→ 12 应付）」，而 12.1.2.1 在 finance 服务、**未交付**。
-- 按 AGENTS.md 模块边界纪律：**一条 SQL 都不碰对方的表，不建跨服务 FK**。
--
-- fulfillment_dropship_payable_task 因此是这一跳在 trade 侧的**权威待办**：
--   SUPPLIER_PAYABLE → FINANCE（12.1.2.1 应付单）
-- 带状态机 + jsonb 载荷 + **幂等键**（同一个对账单重复提交不会造出第二条应付待办）
-- + 重试计数。状态刻意把两件事分开：
--   SENT         已交给下游（本服务发出去了）
--   ACKNOWLEDGED 下游已受理（对方真的接了）
-- 任务行**在下游接手之前不会被当成已办**，界面上停在「待应付（12.1.2.1 未交付）」，
-- 不会画成「已生成应付单」。
--
-- ---------------------------------------------------------------------------
-- 七、跨服务与跨车道边界
-- ---------------------------------------------------------------------------
-- 上游 9.6.1.2 的 fulfillment_dropship_order / _line 与 9.6.1.1 的
-- fulfillment_dropship_config **全部同属 trade 库**，因此可以建**同库外键**与
-- 只读 join（模块边界纪律禁的是**跨服务** FK / 跨库读取，不是同库引用）。
-- 而 5.2.3.1 / 5.2.3.2（catalog）、12.1.2.1 / 13.4.1.x（finance）分属各自的库，
-- 本迁移**一条 SQL 都不碰**。
--
-- **零 ALTER 既有表**：不碰 V99 的 sales_order 族、不碰 V118 的七张
-- fulfillment_dropship_*、不碰 V122 的五张、不碰 V126 的六张、
-- 不碰 V92 / V116 / V97 / V98 / V100 / V101 / V119 / V120 / V124。
-- 跨车道 DDL 回归面 = 0。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器
-- ---------------------------------------------------------------------------
CREATE SEQUENCE dropship_margin_alert_no_seq START 1;
CREATE SEQUENCE dropship_reconciliation_no_seq START 1;
CREATE SEQUENCE dropship_payable_task_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. fulfillment_dropship_fee_rate · 平台费率登记（5.2.3.1 未交付的可登记来源）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_fee_rate (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  rate_code varchar(32) NOT NULL,
  rate_name varchar(200) NOT NULL,
  platform_code varchar(32) NOT NULL CHECK(btrim(platform_code)<>''),
  fee_type varchar(24) NOT NULL CHECK(fee_type IN
    ('COMMISSION','TRANSACTION_FEE','FULFILLMENT_FEE','OTHER')),

  -- === 两种计价形态，二选一，由 charge_basis 与 CHECK 双向钉死 ===
  -- 按售价比例（平台佣金最常见的形态）
  charge_basis varchar(16) NOT NULL CHECK(charge_basis IN ('PERCENT_OF_SELL','FIXED_PER_ORDER')),
  rate_percent numeric(9,6) CHECK(rate_percent IS NULL OR (rate_percent >= 0 AND rate_percent < 1)),
  -- 按单固定额（必须有币种：固定额没有币种就是个数）
  fixed_amount numeric(19,4) CHECK(fixed_amount IS NULL OR fixed_amount >= 0),
  fixed_currency_code varchar(3) CHECK(fixed_currency_code IS NULL
                                      OR fixed_currency_code ~ '^[A-Z]{3}$'),
  -- 平台费以哪个币种结算（折算到代发单币种时要看它）
  settlement_currency_code varchar(3) NOT NULL
    CHECK(settlement_currency_code ~ '^[A-Z]{3}$'),

  -- 生效区间：effective_to 为 NULL = 长期有效
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  -- MODULE_5_2_3_1 = 5.2.3.1 交付后由其供给；TENANT_MAINTAINED = 租户自行登记。
  -- 现在**只有**后者可用，但枚举里先把前者的位置留好，接入时不必改词表。
  source varchar(24) NOT NULL CHECK(source IN ('MODULE_5_2_3_1','TENANT_MAINTAINED')),
  -- 观测时点**必须由登记方给**：用写入时刻冒充，「这条费率是什么时候的」
  -- 就再也答不上来了（V118 供应商库存观测同一纪律）。
  observed_at timestamptz NOT NULL,

  note text NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 1,
  registered_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rate_code),
  CHECK(btrim(rate_code)<>'' AND btrim(rate_name)<>''),
  -- 比例与固定额**互斥**：两个都填的话，「这条费率到底怎么算」没有答案，
  -- 而服务层只会安静地挑一个用 —— 那正是费率算错最常见的来源。
  CHECK((charge_basis='PERCENT_OF_SELL'
         AND rate_percent IS NOT NULL AND rate_percent IS NOT NULL
         AND fixed_amount IS NULL AND fixed_currency_code IS NULL)
     OR (charge_basis='FIXED_PER_ORDER'
         AND fixed_amount IS NOT NULL
         AND rate_percent IS NULL AND fixed_currency_code IS NOT NULL)),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- 观测时点不得晚于生效起点：一条「从下周开始生效、却观测于上周」的费率
  -- 说明登记方抄错了行，而它会直接决定亏损判定。
  CHECK(observed_at <= effective_from)
);

-- ---------------------------------------------------------------------------
-- 2. fulfillment_dropship_fx_rate · 汇率登记（多币种折算的唯一来源）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_fx_rate (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  base_currency varchar(3) NOT NULL CHECK(base_currency ~ '^[A-Z]{3}$'),
  quote_currency varchar(3) NOT NULL CHECK(quote_currency ~ '^[A-Z]{3}$'),
  -- 1 base = rate_value quote。18 位有效数字够用，禁浮点由 NUMERIC 保证。
  rate_value numeric(19,8) NOT NULL CHECK(rate_value > 0),
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  -- MODULE_5_2_3_2（物流与头程费用）/ MODULE_FINANCE（12 财务）/
  -- TENANT_MAINTAINED（租户自行登记）
  source varchar(24) NOT NULL CHECK(source IN
    ('MODULE_5_2_3_2','MODULE_FINANCE','TENANT_MAINTAINED')),
  observed_at timestamptz NOT NULL,

  note text NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 1,
  registered_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,base_currency,quote_currency),
  -- 同一对货币**同期只允许一条**：两条并存时「用哪一条」没有答案，
  -- 而折算口径漂移会让同一张对账单两次算出两个应付数。
  CHECK(base_currency <> quote_currency),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  CHECK(observed_at <= effective_from)
);

-- 同一对货币同期唯一（部分唯一索引：effective_to 为 NULL 表示长期有效）
CREATE UNIQUE INDEX dropship_fx_rate_window_idx
  ON fulfillment_dropship_fx_rate(tenant_id,base_currency,quote_currency,effective_from)
  WHERE effective_to IS NULL;
CREATE INDEX dropship_fx_rate_pair_idx
  ON fulfillment_dropship_fx_rate(tenant_id,base_currency,quote_currency,effective_from desc);

-- ---------------------------------------------------------------------------
-- 3. fulfillment_dropship_settlement_cycle · 供应商结算周期（蓝图输入第三条）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_settlement_cycle (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  cycle_code varchar(32) NOT NULL,
  supplier_code varchar(64) NOT NULL CHECK(btrim(supplier_code)<>''),
  -- 结算币种：这一家供应商按哪个币种结（1688 人民币 / 跨境美元 / 海外仓欧元）
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  cycle_kind varchar(12) NOT NULL CHECK(cycle_kind IN
    ('WEEKLY','BIWEEKLY','MONTHLY','CUSTOM')),
  -- MONTHLY / CUSTOM 的结算日（1..31）；WEEKLY / BIWEEKLY 留空（周结算不看「几号」）。
  -- 留着它会让「周结的每月几号结算」这种自相矛盾的登记混进来。
  settle_day int CHECK(settle_day IS NULL OR (settle_day >= 1 AND settle_day <= 31)),
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  status varchar(12) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('DRAFT','ACTIVE','SUSPENDED')),

  note text NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 1,
  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,cycle_code),
  UNIQUE(tenant_id,supplier_code,currency_code,effective_from),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- 周结 / 双周结不许带结算日；月结与自定义结必须带
  CHECK((cycle_kind IN ('WEEKLY','BIWEEKLY') AND settle_day IS NULL)
     OR (cycle_kind IN ('MONTHLY','CUSTOM') AND settle_day IS NOT NULL))
);

CREATE INDEX dropship_settlement_cycle_supplier_idx
  ON fulfillment_dropship_settlement_cycle(tenant_id,supplier_code,status);

-- ---------------------------------------------------------------------------
-- 4. fulfillment_dropship_margin_fact · 差价毛利事实（唯一真相）
-- ---------------------------------------------------------------------------
-- 一行 = 一张代发单在某个时点算出来的差价毛利。**只装代发单**。
CREATE TABLE fulfillment_dropship_margin_fact (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 上游 9.6.1.2 代发单（同库 FK）
  order_id uuid NOT NULL,
  order_no varchar(32) NOT NULL,
  sales_order_id uuid NOT NULL,
  sales_order_no varchar(64) NOT NULL,
  -- 报表维度（蓝图输出：按店铺/供应商/SKU）。shop_code 从 9.6.1.1 配置
  -- 只读快照下来 —— 代发单本身没有店铺列。
  shop_code varchar(64) NOT NULL DEFAULT '',
  supplier_code varchar(64) NOT NULL,
  config_code varchar(32) NOT NULL DEFAULT '',
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),

  -- === 口径隔离：两个列由 CHECK 钉死，应用层改不了（见文件头第二节）===
  business_model varchar(16) NOT NULL DEFAULT 'DROPSHIP'
    CHECK(business_model = 'DROPSHIP'),
  cost_basis varchar(24) NOT NULL DEFAULT 'PURCHASE_SPREAD'
    CHECK(cost_basis = 'PURCHASE_SPREAD'),
  -- 构成项来自 9.6.1.2 已冻结的值（改报价不改历史事实）
  cost_basis_source varchar(24) NOT NULL DEFAULT 'MODULE_9_6_1_2'
    CHECK(cost_basis_source = 'MODULE_9_6_1_2'),

  -- === 基列：构成项（全部 NUMERIC，禁浮点）===
  sell_amount numeric(19,4) NOT NULL CHECK(sell_amount >= 0),
  purchase_amount numeric(19,4) NOT NULL CHECK(purchase_amount >= 0),
  supplier_shipping_amount numeric(19,4) NOT NULL
    CHECK(supplier_shipping_amount >= 0),

  -- === 平台费：可登记来源；未登记时 amount 为 NULL，绝不当 0 ===
  fee_registration_status varchar(16) NOT NULL
    CHECK(fee_registration_status IN ('REGISTERED','UNREGISTERED')),
  platform_fee_rate_id uuid,
  platform_fee_amount numeric(19,4) CHECK(platform_fee_amount IS NULL
                                          OR platform_fee_amount >= 0),

  -- === 生成列：差价的唯一真相，服务层不另算第二份（见文件头第三、四节）===
  -- 代发成本 = 供应商结算价 + 供应商运费（蓝图原话）
  dropship_cost numeric(19,4) GENERATED ALWAYS AS (
    purchase_amount + supplier_shipping_amount
  ) STORED,
  -- 不含平台费的差价：任何时候都有值（含平台费才能比）
  spread_before_fee numeric(19,4) GENERATED ALWAYS AS (
    sell_amount - purchase_amount - supplier_shipping_amount
  ) STORED,
  -- 含平台费的差价：平台费未登记时**整个表达式为 NULL**，不是 0
  spread_after_fee numeric(19,4) GENERATED ALWAYS AS (
    sell_amount - purchase_amount - supplier_shipping_amount - platform_fee_amount
  ) STORED,
  margin_rate numeric(9,6) GENERATED ALWAYS AS (
    CASE WHEN sell_amount > 0 AND platform_fee_amount IS NOT NULL
         THEN round((sell_amount - purchase_amount - supplier_shipping_amount
                     - platform_fee_amount) / sell_amount, 6)
         ELSE NULL END
  ) STORED,
  -- 三值结论（见文件头第一节）。DB 层钉死：应用层算错或想「优化」成两值
  -- 都改不动它。UNSET 单独成桶，不并进 NOT_LOSS。
  loss_verdict varchar(24) GENERATED ALWAYS AS (
    CASE
      WHEN (sell_amount - purchase_amount - supplier_shipping_amount) < 0
        THEN 'LOSS_CONFIRMED'
      WHEN platform_fee_amount IS NULL
        THEN 'UNDETERMINED_NO_FEE'
      WHEN (sell_amount - purchase_amount - supplier_shipping_amount
            - platform_fee_amount) < 0
        THEN 'LOSS_CONFIRMED'
      ELSE 'NOT_LOSS'
    END
  ) STORED,

  -- === 证据：当时用的费率与算式，事后改费率改不动这里 ===
  loss_evidence text NOT NULL CHECK(btrim(loss_evidence)<>''),
  -- 算出这条事实的时刻（对账周期以它归期）
  computed_at timestamptz NOT NULL DEFAULT now(),
  computed_by_membership_id uuid,
  note text NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一张代发单一条差价事实：重算走 UPSERT 而不是插第二行，
  -- 两行差价会让「这个供应商一共赚了多少」有两个答案。
  UNIQUE(tenant_id,order_id),
  FOREIGN KEY (tenant_id,order_id) REFERENCES fulfillment_dropship_order(tenant_id,id)
    ON DELETE CASCADE,
  FOREIGN KEY (tenant_id,platform_fee_rate_id)
    REFERENCES fulfillment_dropship_fee_rate(tenant_id,id),
  -- 费率登记状态与金额**必须一致**：说「已登记」却没金额（或反过来），
  -- 亏损判定立刻失去依据，而这两列正是生成列的唯一输入。
  CHECK((fee_registration_status='REGISTERED'
         AND platform_fee_rate_id IS NOT NULL AND platform_fee_amount IS NOT NULL)
     OR (fee_registration_status='UNREGISTERED'
         AND platform_fee_rate_id IS NULL AND platform_fee_amount IS NULL)),
  CHECK(btrim(supplier_code)<>'')
);

CREATE INDEX dropship_margin_fact_supplier_idx
  ON fulfillment_dropship_margin_fact(tenant_id,supplier_code,computed_at desc);
CREATE INDEX dropship_margin_fact_shop_idx
  ON fulfillment_dropship_margin_fact(tenant_id,shop_code,computed_at desc);
-- 亏损预警工作台：只索引「有结论要报」的那些，别把 NOT_LOSS 也拖进来
CREATE INDEX dropship_margin_fact_verdict_idx
  ON fulfillment_dropship_margin_fact(tenant_id,loss_verdict,computed_at desc);

-- ---------------------------------------------------------------------------
-- 5. fulfillment_dropship_loss_alert · 亏损单预警
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_loss_alert (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  alert_no varchar(32) NOT NULL,
  margin_fact_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_no varchar(32) NOT NULL,
  shop_code varchar(64) NOT NULL DEFAULT '',
  supplier_code varchar(64) NOT NULL,
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),

  -- 两种预警**不合并**：处置动作不同（确认亏损 → 停投 / 改售价 / 换供应商；
  -- 无法判定 → 去把 5.2.3.1 或租户费率登记补上）。合成一条「有异常」
  -- 就没法派工 —— 与 V122 四类告警、V126 三类异常同一理由。
  alert_kind varchar(24) NOT NULL CHECK(alert_kind IN
    ('LOSS_CONFIRMED','UNDETERMINED_NO_FEE')),
  -- ⚠️ UNDETERMINED_NO_FEE 恒为 'UNDETERMINED'：它**不是**一个已确认的问题，
  -- 画成 WARNING 会让人去查供应商，而真正该做的是把平台费登记补上。
  severity varchar(16) NOT NULL
    CHECK(severity IN ('CRITICAL','WARNING','UNDETERMINED')),
  rule_code varchar(48) NOT NULL,
  rule_evidence text NOT NULL CHECK(btrim(rule_evidence)<>''),
  -- 判定当时的费率快照（证据的一部分，逐字段不可改写）
  fee_registration_status varchar(16) NOT NULL,
  platform_fee_rate_id uuid,
  platform_fee_amount numeric(19,4),
  -- 参与判定的金额
  sell_amount numeric(19,4) NOT NULL,
  dropship_cost numeric(19,4) NOT NULL,
  spread_before_fee numeric(19,4) NOT NULL,
  spread_after_fee numeric(19,4),
  -- 检出时的周期端点：对账按周期圈定范围时要用，且不能被事后改期
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,

  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED','DISMISSED')),
  acknowledged_at timestamptz,
  acknowledged_by_membership_id uuid,
  resolution_note text NOT NULL DEFAULT '',
  resolved_at timestamptz,
  resolved_by_membership_id uuid,
  detected_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,alert_no),
  FOREIGN KEY (tenant_id,margin_fact_id)
    REFERENCES fulfillment_dropship_margin_fact(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id,order_id) REFERENCES fulfillment_dropship_order(tenant_id,id),
  -- 严重度与种类一一对应：UNSET 桶不许被写成 CRITICAL
  CHECK((alert_kind='LOSS_CONFIRMED' AND severity IN ('CRITICAL','WARNING'))
     OR (alert_kind='UNDETERMINED_NO_FEE' AND severity='UNDETERMINED')),
  CHECK((status NOT IN ('ACKNOWLEDGED','RESOLVED'))
        OR (acknowledged_at IS NOT NULL AND acknowledged_by_membership_id IS NOT NULL
            AND coalesce(btrim(resolution_note),'') <> '')),
  CHECK(status <> 'RESOLVED'
        OR (resolved_at IS NOT NULL AND resolved_by_membership_id IS NOT NULL)),
  CHECK(period_end > period_start)
);

-- 不重复检出：同一 (fact, kind) 在非 DISMISSED 状态下唯一。
-- 反复跑 evaluate 不会把同一条亏损刷成几十条；误报要**显式**判误报才解禁。
CREATE UNIQUE INDEX dropship_loss_alert_open_idx
  ON fulfillment_dropship_loss_alert(tenant_id,margin_fact_id,alert_kind)
  WHERE status <> 'DISMISSED';
CREATE INDEX dropship_loss_alert_workbench_idx
  ON fulfillment_dropship_loss_alert(tenant_id,status,severity,detected_at desc);

-- ---------------------------------------------------------------------------
-- 6. fulfillment_dropship_reconciliation · 供应商对账单头（→ 12 应付）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_reconciliation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  statement_no varchar(32) NOT NULL,
  supplier_code varchar(64) NOT NULL,
  -- 对账周期端点**由调用方显式给出**，不从墙上时钟推导：
  -- 「上个月」这种键在月初和月末会圈出不同的范围，没法复算也没法断言。
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,
  cycle_kind varchar(12) NOT NULL CHECK(cycle_kind IN
    ('WEEKLY','BIWEEKLY','MONTHLY','CUSTOM')),
  settlement_cycle_id uuid,
  -- 结算币种：头部所有合计都是这个币种下的数
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),

  -- 口径隔离（与 margin_fact 同一组列，见文件头第二节）
  business_model varchar(16) NOT NULL DEFAULT 'DROPSHIP'
    CHECK(business_model='DROPSHIP'),
  cost_basis varchar(24) NOT NULL DEFAULT 'PURCHASE_SPREAD'
    CHECK(cost_basis='PURCHASE_SPREAD'),

  -- 状态机：DRAFT → FINALIZED → (DISPUTED) → CLOSED
  -- 争议不是状态机的终点而是可回到的点：供应商说「这单我收 13」，
  -- 要能把它从 CLOSED 里拉出来重开，而不是新建一张把历史丢掉。
  status varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','FINALIZED','DISPUTED','CLOSED')),

  -- === 合计：结算币种下、仅含 is_in_totals 的行（见第七节 line 表）===
  order_count int NOT NULL DEFAULT 0 CHECK(order_count >= 0),
  loss_order_count int NOT NULL DEFAULT 0 CHECK(loss_order_count >= 0),
  total_sell_amount numeric(19,4) NOT NULL DEFAULT 0,
  total_purchase_amount numeric(19,4) NOT NULL DEFAULT 0,
  total_supplier_shipping numeric(19,4) NOT NULL DEFAULT 0,
  total_platform_fee numeric(19,4) NOT NULL DEFAULT 0,
  total_dropship_cost numeric(19,4) NOT NULL DEFAULT 0,
  total_spread numeric(19,4) NOT NULL DEFAULT 0,
  -- 应付供应商 = 采购结算价 + 供应商运费（差价抽成下由 9.6.1.1 的
  -- expected_supplier_amount 决定代发单本身，这里按构成项如实相加）
  total_payable numeric(19,4) NOT NULL DEFAULT 0,

  -- === 未登记桶：汇率 UNSET 的行，**绝不并进上面任何合计** ===
  undetermined_order_count int NOT NULL DEFAULT 0 CHECK(undetermined_order_count >= 0),
  undetermined_payable numeric(19,4) NOT NULL DEFAULT 0,
  -- ⚠️ 平台费未登记的行数：total_platform_fee **不含**这 N 行的平台费。
  -- 少了这一列，一个「平台费全是 0」的合计会被当成真实结论读出去 ——
  -- 而它其实只是「没人登记过」。与 undetermined_* 是同一类诚实性约束，
  -- 但针对的是平台费而不是汇率（两条独立的缺口，不能互相顶替）。
  fee_unregistered_order_count int NOT NULL DEFAULT 0 CHECK(fee_unregistered_order_count >= 0),

  -- === 证据：折算口径 + 算式原文 ===
  fx_rate_id uuid,
  fx_rate_value numeric(19,8),
  fx_rate_status varchar(16) NOT NULL DEFAULT 'UNSET'
    CHECK(fx_rate_status IN ('SAME_CURRENCY','APPLIED','UNSET')),
  rule_evidence text NOT NULL DEFAULT '',
  -- 口径说明随行走：下游 12 拿这张单时必须能看到「这里的成本是采购差价」
  basis_note text NOT NULL DEFAULT '',

  finalized_at timestamptz,
  finalized_by_membership_id uuid,
  closed_at timestamptz,
  dispute_note text NOT NULL DEFAULT '',
  created_by_membership_id uuid,
  version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,statement_no),
  -- 同一供应商同一周期**只允许一张**：两张都在时「这个月该付多少」有两个答案
  UNIQUE(tenant_id,supplier_code,period_start,period_end,currency_code),
  FOREIGN KEY (tenant_id,settlement_cycle_id)
    REFERENCES fulfillment_dropship_settlement_cycle(tenant_id,id),
  CHECK(period_end > period_start),
  CHECK(status='DRAFT'
        OR (finalized_at IS NOT NULL AND finalized_by_membership_id IS NOT NULL)),
  CHECK(status <> 'CLOSED' OR closed_at IS NOT NULL),
  -- 头侧折算口径与汇率列的一致性。
  -- ⚠️ 头只在**全程同一个汇率**时才记 fx_rate_id / fx_rate_value：一批对账里
  -- 混着多种原币时，「这张单的汇率是多少」没有单一答案，硬填一个会让下游按它
  -- 折算出错的应付。每**行**始终各自留证（fx_rate_id + fx_rate_value + 算式），
  -- 所以这里允许 APPLIED 但头侧为空 —— 宁可留空，也不写一个假的单一汇率。
  CHECK((fx_rate_status IN ('SAME_CURRENCY','UNSET')
         AND fx_rate_id IS NULL AND fx_rate_value IS NULL)
     OR (fx_rate_status='APPLIED')),
  CHECK(fx_rate_id IS NULL OR fx_rate_value IS NOT NULL),
  -- FINALIZED 必须有算式留证：没有算式的对账单无法复核
  CHECK(status='DRAFT' OR coalesce(btrim(rule_evidence),'') <> '')
);

CREATE INDEX dropship_reconciliation_supplier_idx
  ON fulfillment_dropship_reconciliation(tenant_id,supplier_code,period_start desc);
CREATE INDEX dropship_reconciliation_status_idx
  ON fulfillment_dropship_reconciliation(tenant_id,status,period_start desc);

-- ---------------------------------------------------------------------------
-- 7. fulfillment_dropship_reconciliation_line · 对账明细（冻结快照，不是视图）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_reconciliation_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  statement_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_no varchar(32) NOT NULL,
  sales_order_no varchar(64) NOT NULL,
  shop_code varchar(64) NOT NULL DEFAULT '',
  supplier_code varchar(64) NOT NULL,
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,

  -- === 原币构成项：对账时刻的冻结值 ===
  original_currency_code varchar(3) NOT NULL CHECK(original_currency_code ~ '^[A-Z]{3}$'),
  statement_currency_code varchar(3) NOT NULL
    CHECK(statement_currency_code ~ '^[A-Z]{3}$'),
  sell_amount numeric(19,4) NOT NULL,
  purchase_amount numeric(19,4) NOT NULL,
  supplier_shipping_amount numeric(19,4) NOT NULL,
  fee_registration_status varchar(16) NOT NULL,
  platform_fee_rate_id uuid,
  platform_fee_amount numeric(19,4),
  loss_verdict varchar(24) NOT NULL,

  -- === 折算 === 汇率 UNSET 时下面五列整组为 NULL（见文件头第四节）
  fx_rate_status varchar(16) NOT NULL
    CHECK(fx_rate_status IN ('SAME_CURRENCY','APPLIED','UNSET')),
  fx_rate_value numeric(19,8),
  fx_rate_id uuid,
  sell_amount_in_statement numeric(19,4),
  purchase_amount_in_statement numeric(19,4),
  supplier_shipping_in_statement numeric(19,4),
  platform_fee_in_statement numeric(19,4),
  dropship_cost_in_statement numeric(19,4),
  spread_in_statement numeric(19,4),
  -- 应付供应商 = 采购结算价 + 供应商运费（折算后）
  payable_in_statement numeric(19,4),

  -- 算式原文：这一行的数是怎么来的
  rule_evidence text NOT NULL CHECK(btrim(rule_evidence)<>''),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,statement_id,order_id),
  -- 这条 FK 刻意 DEFERRABLE INITIALLY DEFERRED：生成对账的顺序是
  -- **先落行、再落头**（头的合计要按行算出来才写得出），若 FK 立即校验，
  -- 第一行就会被「statement_id 指向尚不存在的头」拒掉 —— 而头尾合计的
  -- 约束触发器本来就必须延迟到提交时校验（见第 10 节 (d)）。
  FOREIGN KEY (tenant_id,statement_id)
    REFERENCES fulfillment_dropship_reconciliation(tenant_id,id) ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (tenant_id,order_id) REFERENCES fulfillment_dropship_order(tenant_id,id),

  -- 折算状态与折算列必须一致：SAME_CURRENCY / APPLIED 必须有值，UNSET 必须全空。
  -- 「说折算过了但没有折算后的数」比「明说没折算」更坏。
  --
  -- ⚠️ 平台费那一项按 **fee_registration_status** 判定，不是一律要求非空：
  -- 「平台费未登记」时 platform_fee_in_statement 合法地是 NULL（那正是 UNSET 桶
  -- 的含义）。写成 coalesce(platform_fee_in_statement, platform_fee_amount) IS NOT NULL
  -- 会把「如实登记为未登记」的行也拒掉 —— 那等于逼着应用层编一个 0 出来。
  CHECK((fx_rate_status IN ('SAME_CURRENCY','APPLIED')
         AND sell_amount_in_statement IS NOT NULL
         AND purchase_amount_in_statement IS NOT NULL
         AND supplier_shipping_in_statement IS NOT NULL
         AND dropship_cost_in_statement IS NOT NULL
         AND payable_in_statement IS NOT NULL
         AND ((fee_registration_status='REGISTERED'
               AND platform_fee_in_statement IS NOT NULL)
           OR (fee_registration_status='UNREGISTERED'
               AND platform_fee_in_statement IS NULL)))
     OR (fx_rate_status='UNSET'
         AND fx_rate_id IS NULL AND fx_rate_value IS NULL
         AND sell_amount_in_statement IS NULL
         AND purchase_amount_in_statement IS NULL
         AND supplier_shipping_in_statement IS NULL
         AND platform_fee_in_statement IS NULL
         AND dropship_cost_in_statement IS NULL
         AND spread_in_statement IS NULL
         AND payable_in_statement IS NULL)),
  -- APPLIED 必须留下用了哪条汇率、什么值
  CHECK(fx_rate_status <> 'APPLIED' OR (fx_rate_id IS NOT NULL AND fx_rate_value IS NOT NULL)),
  -- 原币与平台费的登记状态要和 fact 同一套口径
  CHECK((fee_registration_status='REGISTERED' AND platform_fee_amount IS NOT NULL)
     OR (fee_registration_status='UNREGISTERED' AND platform_fee_amount IS NULL)),
  -- 应付 = 采购 + 运费（折算后）：这是本模块会被拿去付款的等式，DB 侧独立复核
  CHECK(fx_rate_status='UNSET'
        OR payable_in_statement =
             purchase_amount_in_statement + supplier_shipping_in_statement)
);

-- 落在合计里的行（UNSET 行被排除）—— 头部合计只对这一支求和
ALTER TABLE fulfillment_dropship_reconciliation_line
  ADD COLUMN is_in_totals boolean GENERATED ALWAYS AS (
    fx_rate_status <> 'UNSET'
  ) STORED;

CREATE INDEX dropship_reconciliation_line_statement_idx
  ON fulfillment_dropship_reconciliation_line(tenant_id,statement_id,order_no);
CREATE INDEX dropship_reconciliation_line_shop_idx
  ON fulfillment_dropship_reconciliation_line(tenant_id,shop_code);

-- ---------------------------------------------------------------------------
-- 8. fulfillment_dropship_payable_task · 12.1.2.1 应付待办（真待办，不是假结果）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_payable_task (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_no varchar(32) NOT NULL,
  task_kind varchar(32) NOT NULL DEFAULT 'SUPPLIER_PAYABLE'
    CHECK(task_kind='SUPPLIER_PAYABLE'),
  target_system varchar(16) NOT NULL DEFAULT 'FINANCE'
    CHECK(target_system='FINANCE'),
  -- 状态机：OPEN → CLAIMED → SENT → ACKNOWLEDGED ／ CLAIMED → FAILED
  -- ⚠️ SENT（本服务已发出）与 ACKNOWLEDGED（下游**已受理**）是两个状态，
  -- 不能合并：「发过去了」和「对方收下了」不是一回事，合并后界面就会把
  -- 一个没人接的待办画成「应付单已生成」（见文件头第六节）。
  task_status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(task_status IN ('OPEN','CLAIMED','SENT','ACKNOWLEDGED','FAILED')),

  statement_id uuid NOT NULL,
  statement_no varchar(32) NOT NULL,
  supplier_code varchar(64) NOT NULL,
  -- 应付金额与币种：下游 12 凭这一对生成应付单，缺一不可
  payable_amount numeric(19,4) NOT NULL CHECK(payable_amount >= 0),
  payable_currency_code varchar(3) NOT NULL
    CHECK(payable_currency_code ~ '^[A-Z]{3}$'),
  -- 载荷用具名结构序列化，不放 Map<String,Object>（见 9.6.1.3 已踩过的坑）
  payload jsonb NOT NULL,
  -- 幂等键：同一张对账单重复提交**不会**造出第二条应付待办，
  -- 否则下游会收到两条金额相同的应付单。
  idempotency_key varchar(160) NOT NULL,

  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  last_attempt_at timestamptz,
  last_error text NOT NULL DEFAULT '',
  claimed_by_membership_id uuid,
  claimed_at timestamptz,
  sent_at timestamptz,
  acknowledged_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_no),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY (tenant_id,statement_id)
    REFERENCES fulfillment_dropship_reconciliation(tenant_id,id) ON DELETE CASCADE,
  -- 对下游已受理 / 已失败，必须有时刻与主体
  CHECK(task_status NOT IN ('ACKNOWLEDGED','FAILED')
        OR (claimed_at IS NOT NULL AND claimed_by_membership_id IS NOT NULL)),
  CHECK(task_status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL),
  CHECK(task_status <> 'SENT' OR sent_at IS NOT NULL)
);

CREATE INDEX dropship_payable_task_status_idx
  ON fulfillment_dropship_payable_task(tenant_id,task_status,created_at desc);
CREATE INDEX dropship_payable_task_statement_idx
  ON fulfillment_dropship_payable_task(tenant_id,statement_id);

-- ---------------------------------------------------------------------------
-- 9. fulfillment_dropship_margin_event · 事件流（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_margin_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  event_kind varchar(32) NOT NULL,
  fact_id uuid,
  statement_id uuid,
  alert_id uuid,
  task_id uuid,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  detail varchar(400) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id)
);
CREATE INDEX dropship_margin_event_occurred_idx
  ON fulfillment_dropship_margin_event(tenant_id,occurred_at desc);
CREATE INDEX dropship_margin_event_statement_idx
  ON fulfillment_dropship_margin_event(tenant_id,statement_id,occurred_at desc);

-- ===========================================================================
-- 10. 不变量触发器
-- ===========================================================================

-- (a) **已被非草稿对账单引用的差价事实，其构成项与结论不可改写**。
--     分层很关键：margin_fact 是「当前最好的一次结论」，成本在订单落定前还会变，
--     所以**允许**重算（否则供应商回执改了成本、代发单就永远停在旧差价上）；
--     而一旦被一张定稿对账单引用，它就成了那一时刻的**对账凭据** —— 此时
--     再改费率会让历史对账单看起来是按新费率算的，必须挡住。
--     （冻结副本本身另有一道：reconciliation_line 是 INSERT 时写下的快照。）
CREATE OR REPLACE FUNCTION guard_dropship_margin_fact_evidence() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '差价毛利事实是已发生结论的凭据，不可删除' USING ERRCODE = 'check_violation';
  END IF;
  IF EXISTS (
    SELECT 1 FROM fulfillment_dropship_reconciliation_line l
      JOIN fulfillment_dropship_reconciliation s
        ON s.tenant_id = l.tenant_id AND s.id = l.statement_id
     WHERE l.tenant_id = OLD.tenant_id AND l.order_id = OLD.order_id
       AND s.status <> 'DRAFT') THEN
    RAISE EXCEPTION
      '差价毛利事实（代发单 %）已被定稿对账单引用，构成项与费率不可改写'
      , OLD.order_no USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_margin_fact_evidence
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_margin_fact
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_margin_fact_evidence();

-- (b) **对账单在 FINALIZED 之后合计与证据不可改写**。
--     改费率不该让一张已确认的对账单看起来是按新费率算的 —— 要改走
--     DISPUTED → 重开一张，历史那张原样留着。
CREATE OR REPLACE FUNCTION guard_dropship_reconciliation_finalized() RETURNS trigger AS $$
BEGIN
  IF OLD.status <> 'DRAFT' THEN
    IF NEW.period_start IS DISTINCT FROM OLD.period_start
       OR NEW.period_end IS DISTINCT FROM OLD.period_end
       OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
       OR NEW.supplier_code IS DISTINCT FROM OLD.supplier_code
       OR NEW.order_count IS DISTINCT FROM OLD.order_count
       OR NEW.loss_order_count IS DISTINCT FROM OLD.loss_order_count
       OR NEW.total_sell_amount IS DISTINCT FROM OLD.total_sell_amount
       OR NEW.total_purchase_amount IS DISTINCT FROM OLD.total_purchase_amount
       OR NEW.total_supplier_shipping IS DISTINCT FROM OLD.total_supplier_shipping
       OR NEW.total_platform_fee IS DISTINCT FROM OLD.total_platform_fee
       OR NEW.total_dropship_cost IS DISTINCT FROM OLD.total_dropship_cost
       OR NEW.total_spread IS DISTINCT FROM OLD.total_spread
       OR NEW.total_payable IS DISTINCT FROM OLD.total_payable
       OR NEW.undetermined_order_count IS DISTINCT FROM OLD.undetermined_order_count
       OR NEW.undetermined_payable IS DISTINCT FROM OLD.undetermined_payable
       OR NEW.fee_unregistered_order_count IS DISTINCT FROM OLD.fee_unregistered_order_count
       OR NEW.fx_rate_status IS DISTINCT FROM OLD.fx_rate_status
       OR NEW.fx_rate_id IS DISTINCT FROM OLD.fx_rate_id
       OR NEW.fx_rate_value IS DISTINCT FROM OLD.fx_rate_value
       OR NEW.rule_evidence IS DISTINCT FROM OLD.rule_evidence THEN
      RAISE EXCEPTION
        '对账单 % 已 %，合计与折算证据不可改写（要改请置为 DISPUTED 后重开一张）',
        OLD.statement_no, OLD.status USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_reconciliation_finalized
  BEFORE UPDATE ON fulfillment_dropship_reconciliation
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_reconciliation_finalized();

-- (c) **对账状态机**。DISPUTED 可回到 FINALIZED（供应商补齐材料后继续推进），
--     DRAFT 只能向前，FINALIZED 之后不能凭空回到 DRAFT。
CREATE OR REPLACE FUNCTION guard_dropship_reconciliation_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT'     THEN NEW.status IN ('FINALIZED','CLOSED')
    WHEN 'FINALIZED' THEN NEW.status IN ('DISPUTED','CLOSED')
    WHEN 'DISPUTED'  THEN NEW.status IN ('FINALIZED','CLOSED')
    WHEN 'CLOSED'    THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '对账单 % cannot move % -> %', OLD.statement_no, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  -- 争议必须写明争议内容，否则「有争议」三个字没人能处理
  IF NEW.status = 'DISPUTED' AND coalesce(btrim(NEW.dispute_note),'') = '' THEN
    RAISE EXCEPTION '对账单 % 置为争议必须写明争议内容', OLD.statement_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_reconciliation_state
  BEFORE UPDATE ON fulfillment_dropship_reconciliation
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_reconciliation_state();

-- (d) **头部合计必须等于行之和**（与 V99 / V122 同一手法）：
--     「这个供应商这个周期应付多少」只有一个真相。头上一个数、行里另一组数时
--     付款金额就说不清。
--     必须是 **DEFERRABLE INITIALLY DEFERRED 的约束触发器**：对账是先插头再插行，
--     BEFORE 触发器在头插入那一刻行还不存在，sum 恒为 0。
--     只对 is_in_totals 的行求和（UNSET 行单独计入 undetermined_*）。
CREATE OR REPLACE FUNCTION guard_dropship_reconciliation_totals() RETURNS trigger AS $$
DECLARE
  c_count int; c_loss int; c_fee_unreg int;
  s_sell numeric(19,4); s_pur numeric(19,4); s_ship numeric(19,4);
  s_fee numeric(19,4); s_cost numeric(19,4); s_spread numeric(19,4);
  s_pay numeric(19,4);
  u_count int; u_pay numeric(19,4);
BEGIN
  SELECT count(*), coalesce(sum(case when loss_verdict='LOSS_CONFIRMED' then 1 else 0 end),0),
         coalesce(sum(case when fee_registration_status='UNREGISTERED' then 1 else 0 end),0),
         coalesce(sum(sell_amount_in_statement),0),
         coalesce(sum(purchase_amount_in_statement),0),
         coalesce(sum(supplier_shipping_in_statement),0),
         coalesce(sum(coalesce(platform_fee_in_statement,0)),0),
         coalesce(sum(dropship_cost_in_statement),0),
         coalesce(sum(spread_in_statement),0),
         coalesce(sum(payable_in_statement),0)
    INTO c_count, c_loss, c_fee_unreg, s_sell, s_pur, s_ship, s_fee, s_cost, s_spread, s_pay
    FROM fulfillment_dropship_reconciliation_line
   WHERE statement_id = NEW.id AND is_in_totals;

  SELECT count(*), coalesce(sum(purchase_amount + supplier_shipping_amount),0)
    INTO u_count, u_pay
    FROM fulfillment_dropship_reconciliation_line
   WHERE statement_id = NEW.id AND NOT is_in_totals;

  IF NEW.order_count <> c_count OR NEW.loss_order_count <> c_loss
     OR NEW.fee_unregistered_order_count <> c_fee_unreg
     OR NEW.total_sell_amount <> s_sell OR NEW.total_purchase_amount <> s_pur
     OR NEW.total_supplier_shipping <> s_ship OR NEW.total_platform_fee <> s_fee
     OR NEW.total_dropship_cost <> s_cost OR NEW.total_spread <> s_spread
     OR NEW.total_payable <> s_pay
     OR NEW.undetermined_order_count <> u_count
     OR NEW.undetermined_payable <> u_pay THEN
    RAISE EXCEPTION
      '对账单 % 合计与行不符：头 order=% loss=% fee未登记=% pay=% undet=%/%，行 order=% loss=% fee未登记=% pay=% undet=%/%',
      NEW.statement_no, NEW.order_count, NEW.loss_order_count, NEW.fee_unregistered_order_count,
      NEW.total_payable, NEW.undetermined_order_count, NEW.undetermined_payable,
      c_count, c_loss, c_fee_unreg, s_pay, u_count, u_pay
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER dropship_reconciliation_totals
  AFTER INSERT OR UPDATE ON fulfillment_dropship_reconciliation
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_reconciliation_totals();

-- 行侧的合计复核也钉一道：行本身必须满足「应付 = 采购 + 运费」（CHECK 已有），
-- 但行插入 / 改折算后必须让**头**重新被复核一次 —— 头不变而行变了，
-- 上面的约束触发器不会被唤醒（它只挂在头上）。
CREATE OR REPLACE FUNCTION guard_dropship_reconciliation_line_totals() RETURNS trigger AS $$
DECLARE st uuid;
BEGIN
  st := coalesce(NEW.statement_id, OLD.statement_id);
  -- 只在头还没定稿时允许改行；已 FINALIZED 的对账单行是凭据。
  IF (SELECT status FROM fulfillment_dropship_reconciliation WHERE id = st) <> 'DRAFT' THEN
    RAISE EXCEPTION
      '对账单明细行不可在定稿后修改（明细行是那一时刻的冻结快照）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER dropship_reconciliation_line_totals
  AFTER INSERT OR UPDATE OR DELETE ON fulfillment_dropship_reconciliation_line
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_reconciliation_line_totals();

-- (e) **预警判定证据逐字段不可改写**，处置字段可写（与 V122 alerts 同手法：
--     「能改处置」与「不能改事实」是两件事，分开授权）。
CREATE OR REPLACE FUNCTION guard_dropship_loss_alert_evidence() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '亏损预警是已发生判定结论的凭据，不可删除' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.alert_kind IS DISTINCT FROM OLD.alert_kind
     OR NEW.margin_fact_id IS DISTINCT FROM OLD.margin_fact_id
     OR NEW.fee_registration_status IS DISTINCT FROM OLD.fee_registration_status
     OR NEW.platform_fee_rate_id IS DISTINCT FROM OLD.platform_fee_rate_id
     OR NEW.platform_fee_amount IS DISTINCT FROM OLD.platform_fee_amount
     OR NEW.sell_amount IS DISTINCT FROM OLD.sell_amount
     OR NEW.dropship_cost IS DISTINCT FROM OLD.dropship_cost
     OR NEW.spread_before_fee IS DISTINCT FROM OLD.spread_before_fee
     OR NEW.spread_after_fee IS DISTINCT FROM OLD.spread_after_fee
     OR NEW.rule_code IS DISTINCT FROM OLD.rule_code
     OR NEW.rule_evidence IS DISTINCT FROM OLD.rule_evidence
     OR NEW.period_start IS DISTINCT FROM OLD.period_start
     OR NEW.period_end IS DISTINCT FROM OLD.period_end
     OR NEW.detected_at IS DISTINCT FROM OLD.detected_at THEN
    RAISE EXCEPTION '亏损预警的判定依据不可改写（只能处置）' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_loss_alert_evidence
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_loss_alert
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_loss_alert_evidence();

-- (f) **应付任务状态机**：SENT（已发出）→ ACKNOWLEDGED（下游已受理）。
--     不允许跳过：没有发出过就谈不上受理，「下游已受理」必须有一个真实主体。
CREATE OR REPLACE FUNCTION guard_dropship_payable_task_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.task_status IS NOT DISTINCT FROM OLD.task_status THEN RETURN NEW; END IF;
  allowed := CASE OLD.task_status
    WHEN 'OPEN'         THEN NEW.task_status IN ('CLAIMED')
    WHEN 'CLAIMED'      THEN NEW.task_status IN ('SENT','FAILED')
    WHEN 'SENT'         THEN NEW.task_status IN ('ACKNOWLEDGED','FAILED')
    WHEN 'FAILED'       THEN NEW.task_status IN ('CLAIMED')
    WHEN 'ACKNOWLEDGED' THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '应付待办 % cannot move % -> %', OLD.task_no, OLD.task_status, NEW.task_status
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.task_status = 'FAILED' AND coalesce(btrim(NEW.last_error),'') = '' THEN
    RAISE EXCEPTION '应付待办 % 置为失败必须写明失败原因', OLD.task_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_payable_task_state
  BEFORE UPDATE ON fulfillment_dropship_payable_task
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_payable_task_state();

-- (g) 事件流 append-only。
CREATE OR REPLACE FUNCTION guard_dropship_margin_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '差价对账事件流只追加，不可改写或删除' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_margin_event_append_only
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_margin_event
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_margin_event_append_only();

-- ===========================================================================
-- 11. 多租户与授权（与 trade V1 第 2 节、V118 / V122 / V126 同一套写法）
-- ===========================================================================
-- 九张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'fulfillment_dropship_fee_rate',
    'fulfillment_dropship_fx_rate',
    'fulfillment_dropship_settlement_cycle',
    'fulfillment_dropship_margin_fact',
    'fulfillment_dropship_loss_alert',
    'fulfillment_dropship_reconciliation',
    'fulfillment_dropship_reconciliation_line',
    'fulfillment_dropship_payable_task',
    'fulfillment_dropship_margin_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_dropship_fee_rate,
  fulfillment_dropship_fx_rate,
  fulfillment_dropship_settlement_cycle,
  fulfillment_dropship_margin_fact,
  fulfillment_dropship_loss_alert,
  fulfillment_dropship_reconciliation,
  fulfillment_dropship_reconciliation_line,
  fulfillment_dropship_payable_task,
  fulfillment_dropship_margin_event TO trade_app;

-- 事件流是凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON fulfillment_dropship_margin_event FROM trade_app;
-- 预警表只收回 DELETE、保留 UPDATE：处置一条预警（acknowledge / resolve /
-- dismiss）必须能改 status / acknowledged_* / resolution_note，否则「已处置」
-- 无处落地。不可改写的是**判定证据**那一部分，由上面的触发器逐字段挡住。
REVOKE DELETE ON fulfillment_dropship_loss_alert FROM trade_app;
-- 差价事实与对账明细是「当时算成什么样」的凭据，同样只收回 DELETE。
REVOKE DELETE ON fulfillment_dropship_margin_fact FROM trade_app;
REVOKE DELETE ON fulfillment_dropship_reconciliation_line FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V118 / V126 同口径）。
REVOKE ALL ON
  fulfillment_dropship_fee_rate,
  fulfillment_dropship_fx_rate,
  fulfillment_dropship_settlement_cycle,
  fulfillment_dropship_margin_fact,
  fulfillment_dropship_loss_alert,
  fulfillment_dropship_reconciliation,
  fulfillment_dropship_reconciliation_line,
  fulfillment_dropship_payable_task,
  fulfillment_dropship_margin_event FROM starcon_app;

-- 三个发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE
  dropship_margin_alert_no_seq,
  dropship_reconciliation_no_seq,
  dropship_payable_task_no_seq TO trade_app;

-- ---------------------------------------------------------------------------
-- 12. 跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器**，对既有表的写入面为：
--   * **零 ALTER 既有表**：不碰 V99 的 sales_order 族、不碰 V118 的七张
--     fulfillment_dropship_*、不碰 V122 的五张、不碰 V126 的六张，
--     不碰 V92 / V97 / V98 / V100 / V101 / V116 / V119 / V120 / V124 的任何一张。
--   * 三个**同库**外键（→ fulfillment_dropship_order、→ fulfillment_dropship_config
--     经 margin_fact 的只读 join、→ 本服务自己的 fee_rate / fx_rate / cycle /
--     statement）指向**本服务自己的表**：同库引用，不是 AGENTS.md 禁止的跨服务 FK。
--     加 FK 不修改被引用表的任何列、约束或触发器，既有模块写入面不变。
-- 九张新表落在 fulfillment_dropship_{fee_rate,fx_rate,settlement_cycle,margin_fact,
-- loss_alert,reconciliation,reconciliation_line,payable_task,margin_event}：
-- 与 9.6.1.1 的「配置」、9.6.1.2 的「怎么下的」、9.6.1.3 的「到哪了」并列而不混用 ——
-- 本模块说的是「这一票赚没赚、这个周期该付供应商多少」。
-- 被本迁移约束到的既有模块 = 无，跨车道 DDL 回归面 = 0。
