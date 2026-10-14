-- ===========================================================================
-- 9.6.1.1 代发模式配置
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md:241）：
--   职责：配置店铺/SKU 是否走代发（一件代发），以及代发供应商、结算方式
--         （下单/确认收货后结算、差价抽成）、可售校验规则；区分「自营发货」与
--         「供应商直发」两种履约路径。纯代理代发不占用自有仓库存；其他直发模式的
--         货权与结算规则须单独确认。
--   输入：店铺/商品代发标记；供应商与结算规则；可售校验（供应商库存是否足够）。
--   输出：代发模式生效配置；代发可售标记（决定是否可售、是否可刊登）。
--   关系：上游 = 2.3.1 店铺、3.6.2 供应商报价、7.1.1.3（代发不查自有库存）；
--         下游 = 9.6.1.2。
--
-- ---------------------------------------------------------------------------
-- 一、本模块唯一不可让步的一条：物流路径 ≠ 货权
-- ---------------------------------------------------------------------------
-- 蓝图把这句话加粗写在「关系」里：
--   「**物流路径与货权分开判断**：不经自有仓不代表不承担货权；只有已确认的纯代理模式
--     才适用不记我方库存，其他模式与 7.7 货权规则联合评审。」
--
-- 这句话最容易被实现成「走供应商直发 = 不占我方库存」的一列布尔，那正是它要防的。
-- 本迁移把**物流**与**货权**拆成两个互不推导的维度，并让「不记我方库存」成为
-- 两者**同时**成立时才成立的结论 —— 而不是一个可以随手打开的开关：
--
--   fulfillment_path  SELF_FULFILLED | SUPPLIER_DIRECT   ← 物流：谁发货、货经过谁的手
--   ownership_mode     PURE_AGENCY     | OUR_ACCOUNT      ← 货权：货在结算前算谁的
--
-- 不变量落成两个**生成列**（应用层改不了，也就没有「服务层忘了判」这条路）：
--
--   counts_against_own_inventory =
--       fulfillment_path <> 'SUPPLIER_DIRECT' OR ownership_mode <> 'PURE_AGENCY'
--   pure_agency_exempt           =
--       fulfillment_path =  'SUPPLIER_DIRECT' AND ownership_mode = 'PURE_AGENCY'
--
-- 读法：**只有**纯代理 + 直发这一种组合 exempt=true。其余三种组合
-- （自营发货、供应商直发但货权属我方）都必须占用我方库存口径：
--   * SELF_FULFILLED + OUR_ACCOUNT  自营发货，货本来就是我方的；
--   * SUPPLIER_DIRECT + OUR_ACCOUNT 供应商代我发货，但**货权在我方**
--     （VMI：可销售权归我、货主是供应商）。这条最容易被误判成「不经我方仓 = 不记我方库存」，
--     而它恰恰必须单独确认货权与结算 —— 生成列把它钉成 exempt=false；
--   * SELF_FULFILLED + PURE_AGENCY 由 CHECK 直接拒绝（自营发货不可能纯代理）。
--
-- 「须单独确认」再补一道闸：非纯代理模式**必须**带 ownership_confirmed_at /
-- ownership_confirmed_by_membership_id / ownership_confirmed_note（DB CHECK），
-- 且**激活**那一刻由触发器再查一次 —— 只在录入时确认不够，
-- 确认之后改货权模式（触发器）也会被挡住。
--
-- 与 7.7.2.1（VMI）的关系：VMI 已经在 warehouse 侧钉死「可售 ≠ 自有」。
-- 本模块**不复制**那套账，也不 ALTER V115 的任何一张表 —— 本模块是 trade 侧的
-- **配置与判定**，VMI 是 warehouse 侧的**账本**。两者各自为政但结论一致：
-- 供应商直发不等于我方自有。本模块只负责如实报出 counts_against_own_inventory，
-- 让 9.6.1.2 下单时知道该不该去预占我方库存。
--
-- ---------------------------------------------------------------------------
-- 二、上游实查结论（跨服务不读表，改用带来源标注的只读投影）
-- ---------------------------------------------------------------------------
-- AGENTS.md 明令：跨服务禁止 SQL / FK / 视图。三个上游分属三个独立数据库：
--   * 2.3.1 店铺（**channel 服务**，V1 channel_registry_connections_and_shops）
--     → 本服务自维护只读投影 fulfillment_dropship_shop_ref，带 source 与 synced_at；
--   * 3.6.2 供应商报价（**catalog 服务**，V32 supplier_quote）
--     → 本服务自维护只读投影 fulfillment_dropship_supplier_ref，带 quote 有效期；
--   * 7.1.1.3 可售量（**warehouse 服务**，V56 sellable_policy / V115 VMI）
--     → 蓝图明写「代发**不查**自有库存」，所以本模块要的**不是**我方可售量，
--       而是**供应商**能否供货：落 fulfillment_dropship_supplier_stock，
--       同样强制带 stock_source 与 observed_at（观测时点由采集方给，不许用写入时刻冒充）。
--
-- 投影表**不复制权威列**：供应商报价金额、店铺平台身份都由源服务裁决，
-- 本表只存判定必需的最小快照 + 来源标记。将来同步器按同一形状往里写、
-- 把 source 从 TENANT_MAINTAINED 改成 MODULE_* 即可，本模块表结构与判定不变。
--
-- ---------------------------------------------------------------------------
-- 三、结算口径：差价抽成全程 BigDecimal，禁浮点
-- ---------------------------------------------------------------------------
--   settlement_timing  ON_ORDER（下单结算）| ON_RECEIPT（确认收货后结算）
--   settlement_basis   FLAT_COST（固定供货价）| MARGIN_SHARE（差价抽成）
--   margin_share_rate  numeric(9,6)，**仅** MARGIN_SHARE 时非空，落在 0..1
--
-- 三个**生成列**是「差价抽成」的唯一金额真相（服务层不另算第二份）：
--   gross_spread              = 参考售价 - 供货价           （差价，可能为负）
--   expected_margin_amount    = 差价 × 我方留存比例           （MARGIN_SHARE 时）
--                             = 差价                          （FLAT_COST 时）
--   expected_supplier_amount  = 供货价 + 差价 × (1 - 留存比例)（MARGIN_SHARE 时）
--                             = 供货价                        （FLAT_COST 时）
--
-- **差价为负不拦**（拦了就无法录真实亏损的报价），但它会被可售校验抓成
-- NEGATIVE_MARGIN 并由 listing_policy 决定是否下架 —— 蓝图下游 9.6.1.2 写明
-- 「供应商涨价或无货必须显式告警，否则差价转负的订单会静默亏损」，
-- 静默亏损的闸门必须开在本模块（配置与刊登）这一层。
--
-- 单号由 DB 发（DSH- 前缀），并发建单不撞号。
-- 七张表全部 tenant_id NOT NULL + ENABLE/FORCE RLS + 授权。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：DSH-yyyyMMdd-000001
-- ---------------------------------------------------------------------------
CREATE SEQUENCE dropship_config_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. fulfillment_dropship_shop_ref · 2.3.1 店铺只读投影
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_shop_ref (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  shop_code varchar(64) NOT NULL,
  shop_name varchar(200) NOT NULL,
  platform_code varchar(32) NOT NULL,
  region_code varchar(32) NOT NULL DEFAULT '*',
  active boolean NOT NULL DEFAULT true,
  -- 来源标注：MODULE_2_3_1（2.3.1 已交付并同步）/ TENANT_MAINTAINED（租户手工登记）
  source_system varchar(32) NOT NULL CHECK(source_system IN ('MODULE_2_3_1','TENANT_MAINTAINED')),
  synced_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,shop_code),
  CHECK(btrim(shop_code)<>'' AND btrim(shop_name)<>''),
  CHECK(btrim(platform_code)<>'')
);

-- ---------------------------------------------------------------------------
-- 2. fulfillment_dropship_supplier_ref · 3.6.2 供应商报价只读投影
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_supplier_ref (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL,
  supplier_name varchar(200) NOT NULL,
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  -- 报价有效期：过期报价不能继续拿来做可售判定（下游 9.6.1.2 依赖这一点）
  quote_valid_from date,
  quote_valid_to date,
  quote_source varchar(32) NOT NULL CHECK(quote_source IN ('MODULE_3_6_2','TENANT_MAINTAINED')),
  lead_time_days numeric(9,2) CHECK(lead_time_days IS NULL OR lead_time_days >= 0),
  active boolean NOT NULL DEFAULT true,
  synced_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,supplier_code),
  CHECK(btrim(supplier_code)<>'' AND btrim(supplier_name)<>''),
  CHECK(quote_valid_from IS NULL OR quote_valid_to IS NULL OR quote_valid_to >= quote_valid_from)
);

-- ---------------------------------------------------------------------------
-- 3. fulfillment_dropship_config · 代发配置头
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_config (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  config_code varchar(32) NOT NULL,
  config_name varchar(200) NOT NULL,
  -- 适用范围：按店铺或按 SKU 生效（二选一，CHECK 钉死）
  scope_kind varchar(8) NOT NULL CHECK(scope_kind IN ('SHOP','SKU')),
  shop_code varchar(64) NOT NULL DEFAULT '',
  sku_ref varchar(64) NOT NULL DEFAULT '',

  -- === 物流维度 ===
  fulfillment_path varchar(16) NOT NULL CHECK(fulfillment_path IN
    ('SELF_FULFILLED','SUPPLIER_DIRECT')),
  -- 代发供应商（蓝图的「代发供应商」）：SUPPLIER_DIRECT 必填，SELF_FULFILLED 留空。
  -- 供应商主数据归 3.6.2 所有，这里只存逻辑引用（不建跨服务 FK，见文件头第二节）。
  supplier_code varchar(64) NOT NULL DEFAULT '',
  -- === 货权维度（与物流**互不推导**，见文件头第一节）===
  ownership_mode varchar(16) NOT NULL CHECK(ownership_mode IN
    ('PURE_AGENCY','OUR_ACCOUNT')),
  -- 非纯代理必须「单独确认」货权：三个字段缺一不可（CHECK）
  ownership_confirmed_at timestamptz,
  ownership_confirmed_by_membership_id uuid,
  ownership_confirmed_note text,
  -- 与 7.7 货权规则联合评审的对象（团队 / 模块 / 规则版本）：OUR_ACCOUNT 时必填
  ownership_reviewed_with varchar(64) NOT NULL DEFAULT '',

  -- === 结算维度 ===
  settlement_timing varchar(16) NOT NULL CHECK(settlement_timing IN ('ON_ORDER','ON_RECEIPT')),
  settlement_basis varchar(16) NOT NULL CHECK(settlement_basis IN ('FLAT_COST','MARGIN_SHARE')),
  -- 差价抽成比例：仅 MARGIN_SHARE 时非空（CHECK），落在 0..1
  margin_share_rate numeric(9,6),
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  supplier_cost numeric(19,4) NOT NULL CHECK(supplier_cost >= 0),
  reference_sell_price numeric(19,4) NOT NULL CHECK(reference_sell_price > 0),

  -- === 可售校验维度 ===
  availability_check_mode varchar(20) NOT NULL CHECK(availability_check_mode IN
    ('SUPPLIER_STOCK','CONFIRM_ON_ORDER','SKIP')),
  -- 安全余量：仅 SUPPLIER_STOCK 模式下有意义（CHECK）
  min_supplier_stock_buffer numeric(19,6),

  -- === 刊登维度 ===
  listing_policy varchar(32) NOT NULL CHECK(listing_policy IN
    ('ALWAYS_LIST','PAUSE_WHEN_UNSELLABLE','PAUSE_WHEN_NEGATIVE_MARGIN','MANUAL')),

  priority int NOT NULL DEFAULT 100,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','ACTIVE','SUSPENDED','CLOSED')),
  effective_from timestamptz,
  effective_to timestamptz,
  note text NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 1,
  created_by_membership_id uuid,
  activated_at timestamptz, activated_by_membership_id uuid,
  closed_at timestamptz, closed_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,config_code),

  -- --- 本模块的核心不变量：物流 ≠ 货权（生成列，应用层改不了）---
  -- 只有「供应商直发 + 纯代理」这一种组合不占用自有仓库存口径。
  counts_against_own_inventory boolean GENERATED ALWAYS AS (
    fulfillment_path <> 'SUPPLIER_DIRECT' OR ownership_mode <> 'PURE_AGENCY'
  ) STORED,
  pure_agency_exempt boolean GENERATED ALWAYS AS (
    fulfillment_path = 'SUPPLIER_DIRECT' AND ownership_mode = 'PURE_AGENCY'
  ) STORED,
  -- 差价抽成下的毛利 / 应付供应商：全表只有这三个金额来源（生成列）
  gross_spread numeric(19,4) GENERATED ALWAYS AS (
    reference_sell_price - supplier_cost
  ) STORED,
  expected_margin_amount numeric(19,4) GENERATED ALWAYS AS (
    CASE WHEN settlement_basis = 'MARGIN_SHARE'
         THEN round((reference_sell_price - supplier_cost) * coalesce(margin_share_rate,0), 4)
         ELSE round(reference_sell_price - supplier_cost, 4) END
  ) STORED,
  expected_supplier_amount numeric(19,4) GENERATED ALWAYS AS (
    CASE WHEN settlement_basis = 'MARGIN_SHARE'
         THEN round(supplier_cost + (reference_sell_price - supplier_cost)
                    * (1 - coalesce(margin_share_rate,0)), 4)
         ELSE round(supplier_cost, 4) END
  ) STORED,

  CHECK(btrim(config_code)<>'' AND btrim(config_name)<>''),
  -- 范围二选一
  CHECK((scope_kind='SHOP' AND btrim(shop_code)<>'' AND btrim(sku_ref)='')
     OR (scope_kind='SKU'  AND btrim(sku_ref)<>'' AND btrim(shop_code)='')),
  -- 自营发货不可能是纯代理：自营发货的货本来就是我方的
  CHECK(fulfillment_path<>'SELF_FULFILLED' OR ownership_mode='OUR_ACCOUNT'),
  -- 货权确认：纯代理**不**需要（且带上会误导），非纯代理**必须**带全三个字段。
  -- 写成两个方向互斥的分支，而不是「非纯代理 → 必须确认」的单向蕴含：
  --   后者 `ownership_mode<>'PURE_AGENCY' OR (三个字段齐)` 在 OUR_ACCOUNT 时前半段直接为真，
  --   恒过；而在 PURE_AGENCY 时前半段为假，就变成「纯代理必须带确认」——
  --   正好把两个方向都写反了（实测 check3 就是这样把纯代理配置全部拒掉的）。
  CHECK(
    (ownership_mode = 'PURE_AGENCY'
       AND ownership_confirmed_at IS NULL
       AND ownership_confirmed_by_membership_id IS NULL
       AND coalesce(btrim(ownership_confirmed_note),'') = '')
    OR
    (ownership_mode <> 'PURE_AGENCY'
       AND ownership_confirmed_at IS NOT NULL
       AND ownership_confirmed_by_membership_id IS NOT NULL
       AND coalesce(btrim(ownership_confirmed_note),'') <> '')
  ),
  -- 抽成比例只属于差价抽成：固定供货价时留着它会让人以为会抽佣
  CHECK((settlement_basis='MARGIN_SHARE' AND margin_share_rate IS NOT NULL
         AND margin_share_rate >= 0 AND margin_share_rate <= 1)
     OR (settlement_basis<>'MARGIN_SHARE' AND margin_share_rate IS NULL)),
  -- 安全余量只属于「查供应商库存」这一种校验方式
  CHECK((availability_check_mode='SUPPLIER_STOCK' AND min_supplier_stock_buffer IS NOT NULL
         AND min_supplier_stock_buffer >= 0)
     OR (availability_check_mode<>'SUPPLIER_STOCK' AND min_supplier_stock_buffer IS NULL)),
  -- ACTIVE 必须已生效且有激活时刻
  CHECK((status<>'ACTIVE') OR (activated_at IS NOT NULL AND effective_from IS NOT NULL)),
  CHECK((status<>'CLOSED') OR closed_at IS NOT NULL),
  CHECK(effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
  -- 供应商直发时必须有代发供应商，否则「哪个供应商直发」无从谈起；
  -- 自营发货不该挂着供应商，留着会让货权判断出现第三个来源。
  CHECK((fulfillment_path='SUPPLIER_DIRECT' AND btrim(supplier_code)<>'')
     OR (fulfillment_path='SELF_FULFILLED' AND btrim(supplier_code)='')),
  -- 我方货权必须记录「与 7.7 货权规则联合评审」的落点 —— 蓝图原话「其他模式与 7.7
  -- 货权规则联合评审」，不记录就等于没评审过。
  CHECK(ownership_mode<>'OUR_ACCOUNT' OR btrim(ownership_reviewed_with)<>'')
);

-- ---------------------------------------------------------------------------
-- 4. fulfillment_dropship_config_line · SKU 明细行
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_config_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  config_id uuid NOT NULL,
  sku_ref varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 行级供货价覆盖（空 = 用配置头的 supplier_cost）
  supplier_cost_override numeric(19,4),
  reference_sell_price_override numeric(19,4),
  active boolean NOT NULL DEFAULT true,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,config_id,sku_ref),
  FOREIGN KEY(tenant_id,config_id)
    REFERENCES fulfillment_dropship_config(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(sku_ref)<>''),
  CHECK(supplier_cost_override IS NULL OR supplier_cost_override >= 0),
  CHECK(reference_sell_price_override IS NULL OR reference_sell_price_override > 0)
);

-- ---------------------------------------------------------------------------
-- 5. fulfillment_dropship_supplier_stock · 供应商可供货量（代发侧的「可售」）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_supplier_stock (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL,
  sku_ref varchar(64) NOT NULL,
  available_qty numeric(19,6) NOT NULL CHECK(available_qty >= 0),
  -- 来源：MODULE_7_1_1_3 / MODULE_3_6_2 / TENANT_MAINTAINED
  stock_source varchar(32) NOT NULL CHECK(stock_source IN
    ('MODULE_7_1_1_3','MODULE_3_6_2','TENANT_MAINTAINED')),
  observed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个供应商一个 SKU 只保留一条当前值（观测值按时刻覆盖，不堆历史）
  UNIQUE(tenant_id,supplier_code,sku_ref),
  CHECK(btrim(supplier_code)<>'' AND btrim(sku_ref)<>'')
);

-- ---------------------------------------------------------------------------
-- 6. fulfillment_dropship_evaluation · 代发可售标记（蓝图两大输出之二）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_evaluation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  config_id uuid NOT NULL,
  config_line_id uuid,
  sku_ref varchar(64) NOT NULL,
  shop_code varchar(64) NOT NULL DEFAULT '',
  supplier_code varchar(64) NOT NULL DEFAULT '',
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty > 0),
  required_qty numeric(19,6),
  -- **没有观测记录时是 NULL，不是 0**：0 是「供应商确实没货」，
  -- null 是「没人报过」—— 画成同一个 0 会让人以为这单确定发不出。
  supplier_available_qty numeric(19,6),
  sellable boolean NOT NULL,
  listable boolean NOT NULL,
  reason_code varchar(32) NOT NULL CHECK(reason_code IN
    ('SELLABLE','CONFIG_NOT_ACTIVE','CONFIG_NOT_EFFECTIVE','NO_SUPPLIER_STOCK_RECORD',
     'SUPPLIER_STOCK_INSUFFICIENT','NEGATIVE_MARGIN','LISTING_PAUSED','QUOTE_EXPIRED',
     'SHOP_INACTIVE','LINE_INACTIVE')),
  reason_detail varchar(400) NOT NULL DEFAULT '',
  -- 判定当时的快照：事后改配置不能让旧结论看起来是按新规则算的
  settlement_basis varchar(16) NOT NULL,
  expected_margin_amount numeric(19,4),
  counts_against_own_inventory boolean NOT NULL,
  evaluated_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,config_id)
    REFERENCES fulfillment_dropship_config(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(sku_ref)<>'')
);

-- ---------------------------------------------------------------------------
-- 7. fulfillment_dropship_event · 事件流（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  config_id uuid,
  event_kind varchar(32) NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  detail varchar(400) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id)
);

-- ===========================================================================
-- 8. 索引
-- ===========================================================================
CREATE INDEX dropship_config_status_idx
  ON fulfillment_dropship_config(tenant_id,status,priority,config_code);
CREATE INDEX dropship_config_scope_idx
  ON fulfillment_dropship_config(tenant_id,scope_kind,shop_code,sku_ref);
-- 「此刻生效的那些配置」是最高频查询（9.6.1.2 下单时逐 SKU 找生效配置）
CREATE INDEX dropship_config_effective_idx
  ON fulfillment_dropship_config(tenant_id,effective_from,effective_to)
  WHERE status = 'ACTIVE';
CREATE INDEX dropship_line_config_idx
  ON fulfillment_dropship_config_line(tenant_id,config_id,sku_ref);
CREATE INDEX dropship_line_sku_idx
  ON fulfillment_dropship_config_line(tenant_id,sku_ref) WHERE active;
CREATE INDEX dropship_stock_lookup_idx
  ON fulfillment_dropship_supplier_stock(tenant_id,sku_ref,supplier_code);
CREATE INDEX dropship_eval_config_idx
  ON fulfillment_dropship_evaluation(tenant_id,config_id,evaluated_at desc);
CREATE INDEX dropship_eval_sku_idx
  ON fulfillment_dropship_evaluation(tenant_id,sku_ref,evaluated_at desc);
CREATE INDEX dropship_event_config_idx
  ON fulfillment_dropship_event(tenant_id,config_id,occurred_at desc);

-- ===========================================================================
-- 9. 不变量触发器
-- ===========================================================================
-- (a) 配置状态机：DRAFT → ACTIVE → SUSPENDED ↔ ACTIVE → CLOSED，CLOSED 不可复活。
CREATE OR REPLACE FUNCTION guard_dropship_config_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT'     THEN NEW.status = 'ACTIVE'
    WHEN 'ACTIVE'    THEN NEW.status IN ('SUSPENDED','CLOSED')
    WHEN 'SUSPENDED' THEN NEW.status IN ('ACTIVE','CLOSED')
    WHEN 'CLOSED'    THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION 'dropship config % cannot move % -> %', OLD.config_code, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_config_state
  BEFORE UPDATE ON fulfillment_dropship_config
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_config_state();

-- (b) 货权 / 结算维度一改，**既有货权确认立刻作废**：
--     货权确认是对「这一组货权与结算安排」的确认，换了安排就得重新确认。
--     刻意放在 (c) 之前跑 —— 见下方触发器名后的排序说明。
CREATE OR REPLACE FUNCTION guard_dropship_ownership_clear_on_change() RETURNS trigger AS $$
BEGIN
  IF (NEW.fulfillment_path, NEW.ownership_mode, NEW.settlement_basis,
      NEW.margin_share_rate, NEW.supplier_cost, NEW.reference_sell_price, NEW.supplier_code)
     IS DISTINCT FROM
     (OLD.fulfillment_path, OLD.ownership_mode, OLD.settlement_basis,
      OLD.margin_share_rate, OLD.supplier_cost, OLD.reference_sell_price, OLD.supplier_code) THEN
    NEW.ownership_confirmed_at := NULL;
    NEW.ownership_confirmed_by_membership_id := NULL;
    NEW.ownership_confirmed_note := NULL;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_ownership_clear_on_change
  BEFORE UPDATE ON fulfillment_dropship_config
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_ownership_clear_on_change();

-- (c) 货权确认在**每次写入与激活时**复查 —— 只在录入表单里确认不够：
--     配置录完、确认完，再改货权模式或改结算安排，(b) 会把确认作废，
--     这里必须因此拒绝，直到人重新确认过一次。
--     注意 INSERT 上 OLD 是 NULL，这里用 TG_OP 显式分流而不是依赖那个 NULL 语义。
--
-- ⚠️ 触发器名决定执行顺序：PostgreSQL 对同一张表的多个 BEFORE 触发器按**名字字母序**触发。
-- dropship_ownership_clear_on_change < dropship_ownership_confirmed（cl < co），
-- 所以先作废、再复查。反过来命名的后果是「先复查通过、再把确认清空」，
-- 那条路径等于让作废形同虚设 —— 改名之前请先确认字母序。
CREATE OR REPLACE FUNCTION guard_dropship_ownership_confirmed() RETURNS trigger AS $$
BEGIN
  IF NEW.ownership_mode = 'PURE_AGENCY' THEN RETURN NEW; END IF;
  -- 本次写入自带完整确认 —— 录入与后续每次保存都走这一支。
  IF NEW.ownership_confirmed_at IS NOT NULL
     AND NEW.ownership_confirmed_by_membership_id IS NOT NULL
     AND coalesce(btrim(NEW.ownership_confirmed_note),'') <> '' THEN
    RETURN NEW;
  END IF;
  -- 沿用**既有且未被 (b) 作废**的确认：只改状态、改备注这类不碰货权/结算的编辑。
  IF TG_OP = 'UPDATE' AND OLD.ownership_mode = 'OUR_ACCOUNT'
     AND coalesce(btrim(OLD.ownership_confirmed_note),'') <> ''
     AND NEW.ownership_confirmed_at IS NOT DISTINCT FROM OLD.ownership_confirmed_at
     AND NEW.ownership_confirmed_note IS NOT DISTINCT FROM OLD.ownership_confirmed_note THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION
    '代发配置 % 声明货权在「我方」（%）：这是纯代理以外的所有直发模式，'
    '货权与结算必须单独确认后才能生效 —— 请给出 ownership_confirmed_at、'
    '确认人与确认说明（与 7.7 货权规则联合评审）', NEW.config_code, NEW.fulfillment_path
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_ownership_confirmed
  BEFORE INSERT OR UPDATE ON fulfillment_dropship_config
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_ownership_confirmed();

-- (d) 配置已关闭后其明细行冻结（货已按最终结算走完，账不能再动）。
CREATE OR REPLACE FUNCTION guard_dropship_line_after_close() RETURNS trigger AS $$
DECLARE st varchar(16);
BEGIN
  SELECT status INTO st FROM fulfillment_dropship_config
   WHERE tenant_id = NEW.tenant_id AND id = NEW.config_id;
  IF st = 'CLOSED' THEN
    RAISE EXCEPTION 'dropship config line % belongs to a CLOSED config and is frozen', NEW.sku_ref
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_line_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON fulfillment_dropship_config_line
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_line_after_close();

-- (e) 可售标记是不可改写的判定凭据：事后改它等于把「当时判成可售」改成「当时没货」。
CREATE OR REPLACE FUNCTION guard_dropship_evaluation_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '代发可售标记不可改写或删除；重新评估请另起一条（判定依据会随配置变化）'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_evaluation_immutable
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_evaluation
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_evaluation_immutable();

-- (f) 事件流 append-only。
CREATE OR REPLACE FUNCTION guard_dropship_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '代发配置事件流只追加，不可改写或删除' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_event_append_only
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_event
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_event_append_only();

-- ===========================================================================
-- 10. 多租户与授权（与 trade V1 第 2 节、V116 第 10 节同一套写法）
-- ===========================================================================
-- 七张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'fulfillment_dropship_shop_ref',
    'fulfillment_dropship_supplier_ref',
    'fulfillment_dropship_config',
    'fulfillment_dropship_config_line',
    'fulfillment_dropship_supplier_stock',
    'fulfillment_dropship_evaluation',
    'fulfillment_dropship_event'  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_dropship_shop_ref,
  fulfillment_dropship_supplier_ref,
  fulfillment_dropship_config,
  fulfillment_dropship_config_line,
  fulfillment_dropship_supplier_stock,
  fulfillment_dropship_evaluation,
  fulfillment_dropship_event TO trade_app;
-- 可售标记与事件流是判定凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON
  fulfillment_dropship_evaluation, fulfillment_dropship_event FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V116 同口径）。
REVOKE ALL ON
  fulfillment_dropship_shop_ref,
  fulfillment_dropship_supplier_ref,
  fulfillment_dropship_config,
  fulfillment_dropship_config_line,
  fulfillment_dropship_supplier_stock,
  fulfillment_dropship_evaluation,
  fulfillment_dropship_event FROM starcon_app;
-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE dropship_config_code_seq TO trade_app;

-- ---------------------------------------------------------------------------
-- 11. 跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表/序列/索引/触发器，零 ALTER 任何既有表**：
--   * 没有 ALTER V92 的 fulfillment_routing_*（9.3.2.2 已交付）
--   * 没有 ALTER V116 的 fulfillment_warehouse_allocation_*（9.3.2.1 已交付）
--   * 没有 ALTER V1 的 trade_tenancy_probe
--   * 没有跨库访问 channel / catalog / warehouse 的任何一张表
-- 七张表全部落在 fulfillment_dropship_* 新命名空间，与既有 fulfillment_routing_* /
-- fulfillment_warehouse_allocation_* 并列而不混用 ——
-- 代发配置与分仓决策不是同一个维度：代发决定「走不走我方仓」，分仓决定「走哪个仓」。
-- 被本迁移约束到的既有模块 = 无，跨车道 DDL 回归面 = 0。
