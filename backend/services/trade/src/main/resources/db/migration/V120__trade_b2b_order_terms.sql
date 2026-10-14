-- 9.7.1.1 批发订单与账期（B2B 订单 · 阶梯价 · 起订量 · 账期结算 · 发货确认应收）。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：支持 B2B/批发订单：客户分级价、起订量、阶梯价、账期结算（不即时收款）、
--             开票与对账；与零售订单在订单类型、收款方式、成本口径上区分。
--   **输入**：B2B 订单录入；客户分级与账期（3.6.1.2）；阶梯价（3.6.2 / 05 价目）。
--   **输出**：批发订单 + 应收（按账期，12 应收）；开票需求。
--   **关系**：上游 = 3.6.1.2 客户档案、05 价目；下游 = 12.1.1 应收、12.6.2 发票。
--             **B2B 未发货不产生应收但发货确认为应收**，与零售「平台先收款后发货」
--             现金流方向相反，资金计划需分开（12.1.2.2）。
--
-- 版本号由调度器预分配为 V120（与 8.3.3.1 / 9.6.1.1 / 9.1.1.3 / 9.7.1.2 并行提交）。
--
-- ===========================================================================
-- 第零节：为什么 B2B 是**自己的表**，而不是 sales_order 上加一个 order_type 字段
-- ===========================================================================
-- 9.1.2.2 手工订单确立的纪律是「来源是字段不是表」（sales_order.order_source），
-- 本模块**不推翻**它 —— 但那条纪律成立的前提是「同一张表的写入所有者只有一个」。
--
-- sales_order 是 9.1.2.2 的事实表，它的写入所有者是那条车道，且它的交付说明里
-- 明确把「只新建表，不 ALTER 任何既有表，跨车道 DDL 回归面 = 0」当成验收项。
-- 本批有 4 条车道同时在 trade 落迁移（8.3.3.1 / 9.6.1.1 / 9.1.1.3 / 9.7.1.1），
-- 给别人的事实表加 order_type / cost_basis / 账期字段，会让：
--   ① 另一条车道正在跑的集成测试在迁移执行顺序变化时炸掉；
--   ② sales_order 的 CHECK 词表与 V99 的延迟约束触发器必须被本车道扩写，
--      而那套触发器是 9.1.2.2 32 条用例的断言对象；
--   ③ 「一张事实表一个写入所有者」这条 AGENTS.md 的模块边界纪律被打破。
--
-- 而 B2B 与零售的差异也不是「一个标记」能承载的：账期到期日、发货确认应收、
-- 开票需求这三件事在零售侧**根本不存在**。把它们塞进 sales_order 只会让
-- 一半的行永远是 NULL —— 那正是 catalog V30 拒绝掉的那种「巨大可空字段表」。
--
-- 所以本模块建自己的事实表族 b2b_*，与 sales_order **互不覆盖、互不 JOIN**。
-- 「与零售区分」因此是**结构性的**（不同的表 + 不同的字段），不是靠约定。
--
-- ===========================================================================
-- 第一节：三段式结构 —— 投影 / 阶梯价 / 单据
-- ===========================================================================
--
--   上游投影（只读，被事件同步写入）
--     b2b_customer_terms   ← 3.6.1.2 catalog.party_customer_profile
--                            客户分级 / 账期天数 / 默认币种 / 信用额度
--     b2b_price_book       ← 05 价目 5.1.1.2（**D6 未交付**）的 trade 侧投影
--     b2b_price_tier          SKU × 客户分级 下的**起订量**与**阶梯价档位**
--
--   单据（事实表，本模块的写入所有者）
--     b2b_order / b2b_order_line / b2b_order_event
--     b2b_shipment / b2b_shipment_line
--     b2b_receivable / b2b_receivable_line
--
-- **跨服务不建 FK、不建视图、不跨库 JOIN**（AGENTS.md 模块边界纪律）。
-- 3.6.1.2 已交付（catalog V27/V30），但那是**另一个服务另一个库**：
-- trade 只能持有逻辑 ID 与自己的只读投影。05 价目尚未交付，因此
-- b2b_price_book / b2b_price_tier 这两张表此刻是**由本服务自己的投影写入端点**
-- 维护的，source_system / source_version 两列记录来源，05 价目交付后由事件同步接管，
-- 表结构与所有下游读法都不需要改（与 9.1.2.2「价目落地后由服务层取建议价填进表单」
-- 同一手法）。

-- ---------------------------------------------------------------------------
-- 1. b2b_customer_terms —— 3.6.1.2 客户档案的 trade 侧只读投影
-- ---------------------------------------------------------------------------
-- 词表 STRATEGIC/KEY/STANDARD/PROSPECT/RISK 与 catalog V30 party_customer_profile
-- 逐字一致：投影必须与上游同词表，否则同步进来的值会在这里被 CHECK 拒掉，
-- 而报错会指到「枚举值不合法」而不是「投影与上游词表漂移」。
--
-- credit_limit 为 NULL = **不设额度**（放行），不是「额度是 0」。
-- 与 catalog V30 R4 一致：RISK 级客户的额度只能是 NULL —— 把这两种状态
-- 折叠成一个 0 之后，12 应收无法区分「没设额度」与「额度用尽」，而前者必须放行。
CREATE TABLE b2b_customer_terms (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  customer_party_id uuid NOT NULL,
  customer_code varchar(32) NOT NULL DEFAULT '',
  customer_name varchar(160) NOT NULL DEFAULT '',
  customer_level text NOT NULL DEFAULT 'STANDARD'
    CHECK(customer_level IN ('STRATEGIC','KEY','STANDARD','PROSPECT','RISK')),
  payment_terms_days int NOT NULL DEFAULT 0
    CHECK(payment_terms_days >= 0 AND payment_terms_days <= 365),
  default_currency char(3) CHECK(default_currency IS NULL OR default_currency ~ '^[A-Z]{3}$'),
  credit_limit numeric(19,4) CHECK(credit_limit IS NULL OR credit_limit > 0),
  -- 投影的可追溯性：同步事件带版本号，版本回退必须被拒（见 guard_b2b_terms_version）。
  source_system varchar(32) NOT NULL DEFAULT 'catalog',
  source_version bigint NOT NULL DEFAULT 0,
  synced_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, customer_party_id),
  -- R1 有额度必有币种：额度只有一个币种口径（catalog V30 R5 同一纪律）。
  CHECK(credit_limit IS NULL OR default_currency IS NOT NULL),
  -- R2 风险客户的额度只能是「没设」（catalog V30 R4）。
  CHECK(customer_level <> 'RISK' OR credit_limit IS NULL)
);

-- ---------------------------------------------------------------------------
-- 2. b2b_price_book / b2b_price_tier —— 05 价目阶梯价与起订量的 trade 侧投影
-- ---------------------------------------------------------------------------
-- **价目表 / 价目项两张表**不是随意拆的，它照抄 05 价目自己的形状
-- （5.1.1.1 价目表 / 5.1.1.2 价目项），并把「起订量」放在**表头**：
--
--   起订量（MOQ）是「这个 SKU 对这个客户分级，最少买多少件」的**准入闸**，
--   它是表级属性，不是某一档的属性；阶梯价是表项的阶梯。
--   把 MOQ 复制到每一档上，就出现了「同一 SKU 的三档可能写着三个不同 MOQ」
--   这种无法表达「哪个是对的」的形态 —— 所以 MOQ 只在表头存在一次。
--
CREATE TABLE b2b_price_book (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  customer_level text NOT NULL
    CHECK(customer_level IN ('STRATEGIC','KEY','STANDARD','PROSPECT','RISK')),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 起订量。0 = 不限起订量（拼箱/试样），而不是「起订量是 0 件」。
  -- 「没有 MOQ 概念」与「MOQ 是 0」在业务上是一件事，所以只用 0 表达。
  moq_quantity numeric(19,6) NOT NULL DEFAULT 0
    CHECK(moq_quantity >= 0),
  source_system varchar(32) NOT NULL DEFAULT 'projection',
  source_version bigint NOT NULL DEFAULT 0,
  synced_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- 复合外键的目标必须**显式**声明 UNIQUE(tenant_id,id)：
  -- 主键是单列 id，而下面 b2b_price_tier 的外键是 (tenant_id, price_book_id)，
  -- PostgreSQL 要求被引用列组上存在唯一约束，不会自动把 (tenant_id,id) 当唯一。
  -- 与 V99 的 sales_order 同一写法（那里也单列写了这一行）。
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, sku_id, customer_level)
);

CREATE TABLE b2b_price_tier (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  price_book_id uuid NOT NULL,
  -- 档位下界（含）。**区间是 (上一档下界, 本档下界]**：
  -- 数量恰好等于本档下界 → 落在本档；落在两档之间 → 落在下一档。
  -- 「恰好等于阈值」因此不需要任何特殊分支，它是 <= 关系的自然结果。
  min_quantity numeric(19,6) NOT NULL CHECK(min_quantity > 0),
  -- 单价：精确到 4 位（与 V115 结算价同刻度），**禁用浮点**。
  unit_price numeric(19,4) NOT NULL CHECK(unit_price > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, price_book_id, min_quantity),
  FOREIGN KEY(tenant_id, price_book_id) REFERENCES b2b_price_book(tenant_id, id)
    ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 3. b2b_order —— 批发订单头
-- ---------------------------------------------------------------------------
-- 三个「与零售区分」的字段是本模块的**结构验收项**，不是描述：
--   order_type     订单类型：B2B_WHOLESALE / B2B_DISTRIBUTION / B2B_PROJECT
--   payment_method 收款方式：ORDER_SETTLEMENT / ON_RECEIPT / CREDIT_TERM
--   cost_basis     成本口径：STANDARD / MOVING_WEIGHTED / LOT
-- 零售单走的是 sales_order（order_source=PLATFORM/MANUAL），三张表各写各的，
-- 「区分」不依赖任何约定。
--
-- 收款方式与「要不要产生应收」是同一条规则的两个出口：
--   ORDER_SETTLEMENT 下单结算：款项在下单时已收讫 → **不产生应收**
--   ON_RECEIPT     确认收货后结算：发货确认时产生应收，到期日 = 发货确认时刻
--   CREDIT_TERM    账期：发货确认时产生应收，到期日 = 发货确认时刻 + 账期天数
-- 「账期结算不即时收款」因此是数据层的 CHECK，不是文案。
CREATE TABLE b2b_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_no varchar(48) NOT NULL,
  order_type text NOT NULL
    CHECK(order_type IN ('B2B_WHOLESALE','B2B_DISTRIBUTION','B2B_PROJECT')),
  customer_party_id uuid NOT NULL,
  -- 客户分级是**下单当时的快照**：分级变了不影响已下单的阶梯价与账期。
  customer_level text NOT NULL
    CHECK(customer_level IN ('STRATEGIC','KEY','STANDARD','PROSPECT','RISK')),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  payment_method text NOT NULL
    CHECK(payment_method IN ('ORDER_SETTLEMENT','ON_RECEIPT','CREDIT_TERM')),
  payment_terms_days int NOT NULL DEFAULT 0
    CHECK(payment_terms_days >= 0 AND payment_terms_days <= 365),
  cost_basis text NOT NULL
    CHECK(cost_basis IN ('STANDARD','MOVING_WEIGHTED','LOT')),
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','CONFIRMED','PARTIALLY_SHIPPED','SHIPPED')),
  -- 唯一金额来源：Σ 行金额。头级没有第二个金额列，所以「订单总额」不可能有两个答案。
  total_amount numeric(24,4) NOT NULL DEFAULT 0 CHECK(total_amount >= 0),
  -- 累计已确认应收，由发货累加（guard_b2b_receivable_running_total）。
  -- 它必须恒 ≤ total_amount：这是「未发货不产生应收」的数值表达。
  receivable_amount numeric(24,4) NOT NULL DEFAULT 0
    CHECK(receivable_amount >= 0 AND receivable_amount <= total_amount),
  confirmed_at timestamptz,
  completed_at timestamptz,
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, order_no),
  -- R3 账期与非账期必须自洽：非账期不许带天数（否则到期日算式没有意义）。
  CHECK(payment_method <> 'CREDIT_TERM' OR payment_terms_days > 0),
  CHECK(payment_method = 'CREDIT_TERM' OR payment_terms_days = 0),
  -- R4 非草稿必须有确认时刻；草稿没有确认时刻。
  CHECK((status = 'DRAFT') = (confirmed_at IS NULL))
);

-- ---------------------------------------------------------------------------
-- 4. b2b_order_line —— 批发订单行（阶梯落档在此冻结）
-- ---------------------------------------------------------------------------
-- applied_tier_min_quantity 是**落档结果**的快照：它是本次实际命中的档位下界。
-- 事后价目改了档位，历史订单仍然能回答「这行当时按哪一档算的价」——
-- 只存 unit_price 不够，因为两档可能同价，事后无法从价格反推档位。
CREATE TABLE b2b_order_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  price_book_id uuid NOT NULL,
  applied_tier_id uuid NOT NULL,
  applied_tier_min_quantity numeric(19,6) NOT NULL CHECK(applied_tier_min_quantity > 0),
  unit_price numeric(19,4) NOT NULL CHECK(unit_price > 0),
  -- 起订量也冻结在行上：MOQ 后来被调低，历史订单仍然能复现当时的准入判定。
  moq_quantity numeric(19,6) NOT NULL CHECK(moq_quantity >= 0),
  line_amount numeric(24,4) GENERATED ALWAYS AS (round(quantity * unit_price, 4)) STORED,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, order_id, line_no),
  UNIQUE(tenant_id, order_id, sku_id),
  FOREIGN KEY(tenant_id, order_id) REFERENCES b2b_order(tenant_id, id) ON DELETE CASCADE,
  -- R5 落档自洽：命中的档位下界必须 ≤ 本行数量，且必须已过起订量。
  --    数量恰好等于档位下界时 <= 成立，所以边界不靠特殊分支。
  CHECK(applied_tier_min_quantity <= quantity),
  CHECK(moq_quantity = 0 OR quantity >= moq_quantity)
);

-- ---------------------------------------------------------------------------
-- 5. b2b_shipment / b2b_shipment_line —— 发货（应收的**唯一**触发源）
-- ---------------------------------------------------------------------------
-- 批发订单常态是分批发货，所以发货带明细行而不是一个总数：
-- 「应收 = 本次发货的金额」只有逐行数量才能算准。
CREATE TABLE b2b_shipment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  shipment_no varchar(48) NOT NULL,
  shipped_at timestamptz NOT NULL,
  carrier_ref varchar(64) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, shipment_no),
  FOREIGN KEY(tenant_id, order_id) REFERENCES b2b_order(tenant_id, id)
);

CREATE TABLE b2b_shipment_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  shipment_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  unit_price numeric(19,4) NOT NULL CHECK(unit_price > 0),
  line_amount numeric(24,4) GENERATED ALWAYS AS (round(quantity * unit_price, 4)) STORED,
  FOREIGN KEY(tenant_id, shipment_id) REFERENCES b2b_shipment(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, order_line_id) REFERENCES b2b_order_line(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- 6. b2b_receivable / b2b_receivable_line —— 应收（**只在发货确认时产生**）
-- ---------------------------------------------------------------------------
-- 这是本模块最关键的一处设计：**表存在不等于行会产生**。
-- 应收行唯一的写入路径是「发货确认」这一个动作，DB 上再用
-- 「应收必须挂在一张发货单上」的外键把这条路径钉死 ——
-- 于是「下单就生成应收」在数据层根本写不出来。
--
-- invoice_state 表达的是「**开票需求**」（蓝图的输出之一）：
-- 本服务只把需求标记出来并留下时间戳，**不写 12 财务的表**，
-- 12.6.2 发票交付后由它读这张表。
CREATE TABLE b2b_receivable (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receivable_no varchar(48) NOT NULL,
  order_id uuid NOT NULL,
  -- 一张发货单最多一张应收：应收的金额 = 本次发货金额，重复生成就是重复确认。
  shipment_id uuid NOT NULL,
  customer_party_id uuid NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  payment_method text NOT NULL
    CHECK(payment_method IN ('ON_RECEIPT','CREDIT_TERM')),
  payment_terms_days int NOT NULL DEFAULT 0
    CHECK(payment_terms_days >= 0 AND payment_terms_days <= 365),
  amount numeric(24,4) NOT NULL CHECK(amount > 0),
  -- 账期起算点：发货确认时刻。到期日由它 + 账期天数算出（见 guard_b2b_receivable_terms）。
  confirmed_at timestamptz NOT NULL,
  -- 账期到期日。**timestamptz**：账期是日历天数，不是「本地时间墙上时钟」。
  due_date timestamptz NOT NULL,
  status text NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','SETTLED','CANCELLED')),
  invoice_state text NOT NULL DEFAULT 'NOT_REQUESTED'
    CHECK(invoice_state IN ('NOT_REQUESTED','REQUESTED','INVOICED')),
  invoice_requested_at timestamptz,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, receivable_no),
  UNIQUE(tenant_id, shipment_id),
  FOREIGN KEY(tenant_id, order_id) REFERENCES b2b_order(tenant_id, id),
  FOREIGN KEY(tenant_id, shipment_id) REFERENCES b2b_shipment(tenant_id, id),
  -- R6 账期与非账期自洽，与订单头同一口径。
  CHECK(payment_method <> 'CREDIT_TERM' OR payment_terms_days > 0),
  CHECK(payment_method = 'CREDIT_TERM' OR payment_terms_days = 0),
  -- R7 开票状态与时间戳必须自洽。
  CHECK((invoice_state = 'NOT_REQUESTED') = (invoice_requested_at IS NULL))
);

CREATE TABLE b2b_receivable_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receivable_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  unit_price numeric(19,4) NOT NULL CHECK(unit_price > 0),
  line_amount numeric(24,4) GENERATED ALWAYS AS (round(quantity * unit_price, 4)) STORED,
  FOREIGN KEY(tenant_id, receivable_id) REFERENCES b2b_receivable(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 7. b2b_order_event —— 订单状态事件流（append-only 举证凭据）
-- ---------------------------------------------------------------------------
CREATE TABLE b2b_order_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  event_type text NOT NULL
    CHECK(event_type IN ('CREATED','UPDATED','CONFIRMED','SHIPPED','INVOICE_REQUESTED')),
  -- 24 而不是 16：PARTIALLY_SHIPPED 有 17 个字符，varchar(16) 会在发货那一刻
  -- 写不进事件流 —— 而事件流恰恰是那一刻最该留下的凭据。
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定，
  -- 界面上会随机跳行（与 V99 同一理由）。
  occurred_at timestamptz NOT NULL,
  FOREIGN KEY(tenant_id, order_id) REFERENCES b2b_order(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================
-- 与 V99 / V115 同一手法：能在数据层说清的事不放在应用层。
-- 服务层算错时这些闸会挡住提交，而不是等到对账时才发现总额对不上。

-- (a) 投影版本单调：事件乱序到达时旧版本不得覆盖新版本。
CREATE OR REPLACE FUNCTION guard_b2b_terms_version() RETURNS trigger AS $$
BEGIN
  IF NEW.source_version < OLD.source_version THEN
    RAISE EXCEPTION '客户投影版本回退：收到 % ，本地已是 %', NEW.source_version, OLD.source_version
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_customer_terms_version BEFORE UPDATE ON b2b_customer_terms
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_terms_version();

-- (b) 阶梯阶梯必须**覆盖得起订量**。
--     档位区间是 (上一档下界, 本档下界]，所以「过了起订量的任何数量都必须落进某一档」
--     等价于「最低档下界 ≤ 起订量」。方向写反会同时制造两种错：
--       lowest > moq  → 数量落在 (moq, lowest] 时**无档可落**，阶梯落档直接查不到行；
--       lowest < moq  → 被误判成非法，可这是完全正常的（MOQ 10、起步档 5、优惠档 100）。
--     用 AFTER 而非 BEFORE：AFTER 才能看到**含本行**的完整阶梯，
--     BEFORE 时本行尚不可见，第一档永远算成「空阶梯」。
CREATE OR REPLACE FUNCTION guard_b2b_tier_ladder() RETURNS trigger AS $$
DECLARE book_moq numeric(19,6); lowest numeric(19,6);
BEGIN
  SELECT moq_quantity INTO book_moq FROM b2b_price_book
   WHERE tenant_id = NEW.tenant_id AND id = NEW.price_book_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '阶梯价 % 挂在不存在的价目表上', NEW.price_book_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  SELECT min(min_quantity) INTO lowest FROM b2b_price_tier
   WHERE tenant_id = NEW.tenant_id AND price_book_id = NEW.price_book_id;
  -- 只写第一档时还没有可比对的阶梯，放行；下一档会带上完整阶梯来校验。
  IF lowest IS NULL THEN RETURN NULL; END IF;
  IF book_moq > 0 AND lowest > book_moq THEN
    RAISE EXCEPTION '价目表 % 的最低档 % 件高于起订量 % 件：会存在「过了起订量却落不进任何档」的空洞',
      NEW.price_book_id, lowest, book_moq USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_price_tier_ladder AFTER INSERT OR UPDATE ON b2b_price_tier
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_tier_ladder();

-- (c) 订单行落档必须真的命中价目表里的那一档，且单价必须等于该档单价。
--     服务层自己算一遍、DB 独立复算一遍：算错时在这里被挡下。
CREATE OR REPLACE FUNCTION guard_b2b_line_tier() RETURNS trigger AS $$
DECLARE tier_price numeric(19,4); tier_min numeric(19,6); book_moq numeric(19,6);
BEGIN
  SELECT unit_price, min_quantity INTO tier_price, tier_min
    FROM b2b_price_tier
   WHERE tenant_id = NEW.tenant_id AND id = NEW.applied_tier_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '订单行命中的阶梯 % 不存在', NEW.applied_tier_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF tier_min <> NEW.applied_tier_min_quantity THEN
    RAISE EXCEPTION '订单行记录的落档 % 件与阶梯 % 的真实下界 % 件不一致',
      NEW.applied_tier_min_quantity, NEW.applied_tier_id, tier_min
      USING ERRCODE = 'check_violation';
  END IF;
  IF tier_price <> NEW.unit_price THEN
    RAISE EXCEPTION '订单行单价 % 与阶梯 % 的单价 % 不一致：阶梯价是系统算的，不接受手工改价',
      NEW.unit_price, NEW.applied_tier_id, tier_price USING ERRCODE = 'check_violation';
  END IF;
  SELECT moq_quantity INTO book_moq FROM b2b_price_book
   WHERE tenant_id = NEW.tenant_id AND id = NEW.price_book_id;
  IF book_moq IS DISTINCT FROM NEW.moq_quantity THEN
    RAISE EXCEPTION '订单行起订量 % 与价目表 % 的起订量 % 不一致',
      NEW.moq_quantity, NEW.price_book_id, book_moq USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_order_line_tier BEFORE INSERT ON b2b_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_line_tier();

-- (d) 订单确认时的四道闸（延迟约束触发器，提交时回表核对）：
--     行 ≥ 1、总额 == Σ 行金额、每行已过起订量、状态机合法。
--     DEFERRABLE 是「先插头 → 写行 → 提交」这条写入顺序能成立的原因：
--     当场算会把「还没写完」误判成「不完整」。
CREATE OR REPLACE FUNCTION guard_b2b_order_confirmed() RETURNS trigger AS $$
DECLARE n int; s numeric(24,4);
BEGIN
  IF NEW.status = 'DRAFT' THEN RETURN NULL; END IF;
  SELECT count(*), coalesce(sum(line_amount),0) INTO n, s
    FROM b2b_order_line WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id;
  IF n < 1 THEN
    RAISE EXCEPTION '订单 % 确认时没有任何明细行：一张没有行的批发订单不是订单', NEW.order_no
      USING ERRCODE = 'check_violation';
  END IF;
  IF s <> NEW.total_amount THEN
    RAISE EXCEPTION '订单 % 总额 % 与明细合计 % 不一致', NEW.order_no, NEW.total_amount, s
      USING ERRCODE = 'check_violation';
  END IF;
  IF EXISTS (SELECT 1 FROM b2b_order_line l
              WHERE l.tenant_id = NEW.tenant_id AND l.order_id = NEW.id
                AND l.moq_quantity > 0 AND l.quantity < l.moq_quantity) THEN
    RAISE EXCEPTION '订单 % 存在低于起订量的明细行：起订量是硬校验，不能确认', NEW.order_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER b2b_order_confirmed_check
  AFTER INSERT OR UPDATE ON b2b_order
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_order_confirmed();

-- (e) 状态机：草稿只能确认一次；已确认的单只能被发货推进。
--     「回退」在 B2B 里是真实业务（客户改单），但那属于 9.2 的改单动作，
--     本模块刻意**不提供**回退 —— 单向状态机让「账期从哪天起算」永远只有一个答案。
CREATE OR REPLACE FUNCTION guard_b2b_order_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT'              THEN NEW.status = 'CONFIRMED'
    WHEN 'CONFIRMED'          THEN NEW.status IN ('PARTIALLY_SHIPPED','SHIPPED')
    WHEN 'PARTIALLY_SHIPPED'  THEN NEW.status = 'SHIPPED'
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '订单状态不能从 % 变到 %', OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_order_state BEFORE UPDATE ON b2b_order
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_order_state();

-- (f) 已确认后冻结订单行：明细、单价、落档都是下单当时的快照，
--     事后改价会让「这笔交易当时是什么」在历史里出现两个答案。
CREATE OR REPLACE FUNCTION guard_b2b_line_frozen() RETURNS trigger AS $$
DECLARE st varchar(16);
BEGIN
  SELECT status INTO st FROM b2b_order
   WHERE tenant_id = OLD.tenant_id AND id = OLD.order_id;
  IF st IS NOT NULL AND st <> 'DRAFT' THEN
    RAISE EXCEPTION '订单已确认，明细与阶梯落档已冻结：改数据等于让「当时那笔交易是什么」有两个答案'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_order_line_frozen BEFORE UPDATE OR DELETE ON b2b_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_line_frozen();

-- (g) 累计发货量不得超过订单量：超发是批发最容易出的错，且直接放大应收。
CREATE OR REPLACE FUNCTION guard_b2b_shipment_quantity() RETURNS trigger AS $$
DECLARE ordered numeric(19,6); shipped numeric(19,6); qty numeric(19,6); code varchar(64);
BEGIN
  SELECT l.quantity, l.sku_code INTO ordered, code
    FROM b2b_order_line l
   WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.order_line_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '发货行引用了不存在的订单行 %', NEW.order_line_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  qty := COALESCE(NEW.quantity, OLD.quantity);
  SELECT coalesce(sum(sl.quantity),0) INTO shipped
    FROM b2b_shipment_line sl
    JOIN b2b_shipment s ON s.tenant_id = sl.tenant_id AND s.id = sl.shipment_id
   WHERE sl.tenant_id = NEW.tenant_id AND sl.order_line_id = NEW.order_line_id
     AND sl.id <> COALESCE(NEW.id, OLD.id);
  IF shipped + qty > ordered THEN
    RAISE EXCEPTION 'SKU % 累计发货 % 件超过订单量 % 件', code, shipped + qty, ordered
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_shipment_quantity BEFORE INSERT OR UPDATE ON b2b_shipment_line
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_shipment_quantity();

-- (h) 应收的到期日必须等于「发货确认时刻 + 账期天数」，由 DB 独立复算。
--     「账期到期日」是本模块最容易被静默偏 8 小时的时间字段：
--     服务层与 DB 各算一次，算错时在这里被挡下。
CREATE OR REPLACE FUNCTION guard_b2b_receivable_terms() RETURNS trigger AS $$
BEGIN
  IF NEW.due_date <> NEW.confirmed_at + make_interval(days => NEW.payment_terms_days) THEN
    RAISE EXCEPTION '应收 % 的到期日 % 与「发货确认 % + 账期 % 天」不一致',
      NEW.receivable_no, NEW.due_date, NEW.confirmed_at, NEW.payment_terms_days
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_receivable_terms BEFORE INSERT OR UPDATE ON b2b_receivable
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_receivable_terms();

-- (i) 应收表头金额 == Σ 应收明细；且发货明细必须真的有行。
CREATE OR REPLACE FUNCTION guard_b2b_receivable_lines() RETURNS trigger AS $$
DECLARE n int; s numeric(24,4);
BEGIN
  SELECT count(*), coalesce(sum(line_amount),0) INTO n, s
    FROM b2b_receivable_line WHERE tenant_id = NEW.tenant_id AND receivable_id = NEW.id;
  IF n < 1 THEN
    RAISE EXCEPTION '应收 % 没有任何明细行：没有明细的应收无法对账', NEW.receivable_no
      USING ERRCODE = 'check_violation';
  END IF;
  IF s <> NEW.amount THEN
    RAISE EXCEPTION '应收 % 表头金额 % 与明细合计 % 不一致', NEW.receivable_no, NEW.amount, s
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER b2b_receivable_lines_check
  AFTER INSERT OR UPDATE ON b2b_receivable
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_receivable_lines();

-- (j) 订单头的累计应收必须等于其下所有应收之和。
--     「未发货不产生应收」在这里有第二重表达：应收行只能经由
--     b2b_receivable.shipment_id 这一个入口产生，而订单头这个数只认它。
CREATE OR REPLACE FUNCTION guard_b2b_receivable_running_total() RETURNS trigger AS $$
DECLARE t numeric(24,4); booked numeric(24,4);
BEGIN
  SELECT coalesce(sum(amount),0) INTO t FROM b2b_receivable
   WHERE tenant_id = NEW.tenant_id AND order_id = NEW.order_id;
  -- 注意读的是**订单头**上那个数：NEW 是 b2b_receivable 行，
  -- 它自己没有 receivable_amount 列，写成 NEW.receivable_amount 会让本函数在
  -- 运行期报「列不存在」，而延迟约束触发器是提交时才跑的 —— 表现为一个完全没有线索的 409。
  SELECT receivable_amount INTO booked FROM b2b_order
   WHERE tenant_id = NEW.tenant_id AND id = NEW.order_id;
  IF t <> booked THEN
    RAISE EXCEPTION '订单累计应收 % 与应收单合计 % 不一致', booked, t
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER b2b_receivable_running_total_check
  AFTER INSERT OR UPDATE ON b2b_receivable
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_receivable_running_total();

-- (k) 应收单号 / 发货单号 不可改：它们是对账时与客户往来函件引用的凭据号。
CREATE OR REPLACE FUNCTION guard_b2b_identity() RETURNS trigger AS $$
BEGIN
  IF NEW.order_no <> OLD.order_no THEN
    RAISE EXCEPTION '订单号不可更改：它是对账凭据号' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_order_identity BEFORE UPDATE ON b2b_order
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_identity();

-- (l) 事件流 append-only。
CREATE OR REPLACE FUNCTION guard_b2b_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '订单事件流是 append-only 举证凭据，不允许修改或删除'
    USING ERRCODE = 'insufficient_privilege';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER b2b_order_event_append_only BEFORE UPDATE OR DELETE ON b2b_order_event
  FOR EACH ROW EXECUTE FUNCTION guard_b2b_event_append_only();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================
CREATE INDEX b2b_customer_terms_by_level
  ON b2b_customer_terms(tenant_id, customer_level, customer_party_id);
CREATE INDEX b2b_price_book_by_sku ON b2b_price_book(tenant_id, sku_id);
CREATE INDEX b2b_price_book_by_level ON b2b_price_book(tenant_id, customer_level, sku_id);
CREATE INDEX b2b_price_tier_by_book ON b2b_price_tier(tenant_id, price_book_id, min_quantity);
CREATE INDEX b2b_order_by_customer
  ON b2b_order(tenant_id, customer_party_id, created_at desc, id);
CREATE INDEX b2b_order_by_status ON b2b_order(tenant_id, status, created_at desc, id);
-- 开票队列：财务按「已请求且未开票」取单，这个复合顺序就是取单顺序。
CREATE INDEX b2b_receivable_by_invoice
  ON b2b_receivable(tenant_id, invoice_state, due_date, id);
CREATE INDEX b2b_receivable_by_order ON b2b_receivable(tenant_id, order_id, created_at, id);
-- 账期到期扫描：资金计划（12.1.2.2）按到期日取未结应收。
CREATE INDEX b2b_receivable_due ON b2b_receivable(tenant_id, status, due_date, id);
CREATE INDEX b2b_shipment_by_order ON b2b_shipment(tenant_id, order_id, shipped_at, id);
CREATE INDEX b2b_shipment_line_by_shipment ON b2b_shipment_line(tenant_id, shipment_id);
CREATE INDEX b2b_shipment_line_by_order_line ON b2b_shipment_line(tenant_id, order_line_id);
CREATE INDEX b2b_receivable_line_by_receivable
  ON b2b_receivable_line(tenant_id, receivable_id);
CREATE INDEX b2b_order_line_by_order ON b2b_order_line(tenant_id, order_id, line_no);
CREATE INDEX b2b_order_event_by_order ON b2b_order_event(tenant_id, order_id, occurred_at, id);

-- ===========================================================================
-- 第四节：多租户 —— RLS + 授权（与 trade V1 / V92 / V97 / V99 同一套写法）
-- ===========================================================================
-- 十二张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它（AccessService.Access 来自已认证成员关系）。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'b2b_customer_terms','b2b_price_book','b2b_price_tier',
   'b2b_order','b2b_order_line','b2b_order_event',
   'b2b_shipment','b2b_shipment_line',
   'b2b_receivable','b2b_receivable_line'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  b2b_customer_terms,b2b_price_book,b2b_price_tier,
  b2b_order,b2b_order_line,b2b_order_event,
  b2b_shipment,b2b_shipment_line,
  b2b_receivable,b2b_receivable_line TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  b2b_customer_terms,b2b_price_book,b2b_price_tier,
  b2b_order,b2b_order_line,b2b_order_event,
  b2b_shipment,b2b_shipment_line,
  b2b_receivable,b2b_receivable_line TO starcon_app;

-- 「授权在前、收回在后」，顺序不能颠倒，否则下面这几条会被上面的 GRANT 覆盖回去：
-- 事件流与应收明细是 append-only 的对账举证凭据，收回改写权。
REVOKE UPDATE,DELETE ON b2b_order_event FROM trade_app;
REVOKE UPDATE,DELETE ON b2b_receivable_line FROM trade_app;
-- 客户档案投影只有 upsert、从来没有业务上的删除路径，收回删除权。
-- **b2b_price_tier 刻意不给这条 REVOKE**：阶梯是「一档集合」，调价时的正确动作是
-- **整体替换**（删掉旧档再写新档），删档是调价最常见的操作。收回它的 DELETE 会让
-- 整体替换走不通（表现为一个完全没有信息量的 409），而「阶梯不可变」在这里并不是
-- 需要保护的业务不变量 —— 历史订单的落档冻结在 b2b_order_line 的
-- applied_tier_min_quantity 上，改价表动不了已成立的订单。
REVOKE DELETE ON b2b_customer_terms FROM trade_app;
-- 已确认订单的行在触发器里被冻结，这里**同时收回删除权**。
REVOKE DELETE ON b2b_order_line,b2b_shipment_line FROM trade_app;

-- ===========================================================================
-- 第五节：与上下游的边界（本文件明确划出，不越界）
-- ===========================================================================
--   * **3.6.1.2 客户档案（catalog V27/V30 已交付）**：只存逻辑 ID + trade 侧只读投影
--     b2b_customer_terms。**不建跨库 FK、不跨库 JOIN、不回查对方表**。
--     客户改名 / 改级别 / 改账期只改投影；已下单订单用的是当时快照，不受影响。
--   * **05 价目 / 阶梯价（5.1.1.2，D6 未交付）**：b2b_price_book / b2b_price_tier
--     目前由本服务的投影写入端点维护，source_system='projection'。
--     05 价目交付后由事件同步接管同两张表，表结构与所有下游读法都不需要改。
--   * **12.1.1 应收 / 12.6.2 发票（未交付）**：本模块在 trade 侧产出 b2b_receivable
--     作为**应收事实**，并用 invoice_state 表达开票需求。
--     不跨库写财务表、不建跨服务 FK、不查对方表。
--   * **9.3 履约 / 9.1.2.2 手工订单**：履约链路当前只认 sales_order 一张表，
--     b2b_order 不在其中。交接方式写在 /trade/b2b-orders/capabilities 里，
--     不在本迁移里假装已经接通。
