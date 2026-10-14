-- ===========================================================================
-- 9.6.1.2 代发单与供应商下单
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md:248）：
--   职责：买家下单后，自动/手工向 1688 等供应商代发平台下单，传递地址与商品信息，
--         回填供应商单号与代发成本，计算我方差价毛利。
--   输入：已付款代发订单；供应商代发接口或人工录入；代发成本（采购价 + 供应商运费）。
--   输出：代发单（供应商、单号、发货状态）；差价成本；差异告警（供应商无货/涨价/失败）。
--   关系：上游 = 9.6.1.1、9.1 订单；下游 = 9.6.1.3 跟踪、12 财务（差价结算）、
--         10 物流（取供应商运单号）。
--         **供应商涨价或无货必须显式告警**，否则差价转负的订单会静默亏损。
--
-- ---------------------------------------------------------------------------
-- 一、本模块唯一不可让步的一条：涨价/无货/失败必须显式告警
-- ---------------------------------------------------------------------------
-- 蓝图把这句话加粗写在「关系」里，并直接点出了不做的后果：
--   「**供应商涨价或无货必须显式告警**，否则差价转负的订单会静默亏损。」
--
-- 「静默亏损」的具体形状是：运营按配置的供货价 10 下了单，供应商回执实际按 13 结算，
-- 我方售价 20 —— 差价从 +10 变成 -3。这张单在界面上仍然显示「代发单已提交」，
-- 不主动报出来就一直等到对账（9.6.2.1）甚至财务结算（12.1.2.1）才被发现。
--
-- 因此本迁移把告警做成**表 + DB 不变量**，而不是服务层里的一句提示：
--
--   fulfillment_dropship_alert 一行 = 一次**实际发生的**差异（不是规则、不是提醒）：
--     PRICE_INCREASE   供应商回执采购价 > 我方下单时的采购价      （涨价）
--     NO_STOCK         供应商回执「无货」                          （无货）
--     ORDER_FAILED     下单接口失败 / 供应商拒单                    （失败）
--     NEGATIVE_SPREAD  差价转负                                     （已亏损）
--
-- 四者互相独立：涨价不必然转负（我方售价涨了照样赚），无货不必然涨价，
-- 而「差价转负」即便没有涨价也可能是售价本身定低了。四类各自落一行，
-- 不合并成一条「有异常」，是因为处置动作不同（改供应商 / 催发货 / 改售价 / 退款）。
--
-- ⚠️ 涨价判定用的是**下单时冻结的** expected_purchase_amount，而不是实时配置价：
-- 供应商回执是「这一单贵了 3 块」，不是「这个 SKU 现在贵了 3 块」。后者是
-- 9.6.1.1 的可售判定该管的，两者混在一起会导致改一次配置价就把历史告警冲掉。
--
-- ---------------------------------------------------------------------------
-- 二、「已付款」这条输入：sales_order **没有**支付状态列
-- ---------------------------------------------------------------------------
-- 蓝图输入句第一个词是「已付款代发订单」，但 9.1 的 sales_order（V99）里：
--   * status 的 CHECK 只有 ('DRAFT','SUBMITTED') —— 这是「有没有进审单队列」，
--     **不是**支付状态；
--   * 只有 payment_method（PREPAID/COD/...）—— 那是我方与客户的**约定**，
--     不是**实收流水**。
-- V100 的文件头已经把这三件事逐一记过：支付状态归属 12 财务（未交付）。
--
-- 本模块的处理（刻意不在服务层假装读到了支付状态）：
--   * capabilities 里 PAYMENT 维度**显式 available=false**，并点名 ownerModule；
--   * 代发单**不**伪造一个 payment_status 列冒充上游事实；
--   * 改为要求一条**人工确认凭据**：payment_confirmed_at / _by_membership_id /
--     _source / _note 四件套，且 payment_confirmed_source 恒为 MANUAL_ATTESTATION。
--     这是「由有权限的成员书面确认这张单已收款」，是一个**真实发生的、可追责的事实**
--     （有主体、有时刻、有来源），与「假装系统读到已付款」有本质区别；
--   * DB CHECK + 触发器：未确认付款**不许**进入 SUPPLIER_DIRECT 提交态。
--
-- 12 财务交付实收流水后，把这四件套换成授权 API 的读取即可，表结构不变。
--
-- ---------------------------------------------------------------------------
-- 三、差价口径：代发成本 = 采购价 + 供应商运费（蓝图原话），差价可能为负
-- ---------------------------------------------------------------------------
--   dropship_cost  = purchase_amount + supplier_shipping_amount   （代发成本）
--   spread_amount  = sell_amount - purchase_amount - supplier_shipping_amount
--
-- ⚠️ spread_amount **可以为负且不拦**：拦了就录不进真实亏损的单，
-- 而亏损单恰恰是最需要被看见的（负差价自动触发 NEGATIVE_SPREAD 告警）。
-- 唯一要求是它必须**有值**（NOT NULL）——「没算」与「算了是负的」是两件事，
-- 前者才是真正的静默亏损。
--
-- 三个生成列都只引用**基列**（PostgreSQL 不允许生成列引用生成列），
-- 应用层改不了这三个数，与 V118 的 gross_spread / expected_margin_amount 同一手法。
--
-- ---------------------------------------------------------------------------
-- 四、供应商下单：端口 + 两个 vendor 实现，不写死假响应
-- ---------------------------------------------------------------------------
-- 蓝图说「向 1688 等供应商代发平台下单」。真实世界里这是**外部平台对接**，
-- 按 AGENTS.md「外部适配器放在对应服务里、每个 vendor 独立包、只暴露统一接口」：
--   * SupplierOrderPort（统一接口，com.starcon.trade.SupplierOrderPort）
--   * ManualEntrySupplierAdapter      —— 人工录入路径，**已接线**，真实落库
--   * Alibaba1688SupplierAdapter      —— 真实 HTTP 客户端，**默认未接线**：
--     必须先登记 fulfillment_dropship_supplier_endpoint（base_url + credential_ref）
--     才可调用；未登记时**显式报 503 并说明未接线**，绝不返回一份假的 1688 响应
--     冒充下单成功 —— 那正是「禁止 mock」要防的东西。
--
-- fulfillment_dropship_supplier_endpoint 只登记**引用**（credential_ref 指向
-- secret manager 里的键），**不存任何凭证明文**（AGENTS.md 安全条）。
--
-- ---------------------------------------------------------------------------
-- 五、跨服务边界
-- ---------------------------------------------------------------------------
-- 本模块的三个上游**全部在同一个 trade 库里**（9.6.1.1 的 fulfillment_dropship_* 与
-- 9.1 的 sales_order 族），所以这里可以建**同库外键**——这是模块边界纪律允许的
-- （纪律禁的是**跨服务** FK）。而 3.6.2 供应商报价、2.3.1 店铺、10 物流、12 财务
-- 分属 catalog / channel / logistics / finance 各自的库，**一条 SQL 都不碰**。
--
-- 单号由 DB 发（DSO- 前缀 + 日期 + 6 位序号），并发建单不撞号。
-- 五张表全部 tenant_id NOT NULL + ENABLE/FORCE RLS + 授权。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：DSO-yyyyMMdd-000001
-- ---------------------------------------------------------------------------
CREATE SEQUENCE dropship_order_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. fulfillment_dropship_order · 代发单头（蓝图输出第一条：供应商、单号、发货状态）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 我方代发单号：DSO-yyyyMMdd-000001，由 DB 发（并发建单不撞号）
  order_no varchar(32) NOT NULL,

  -- === 上游：9.1 订单（同库，可建 FK）===
  sales_order_id uuid NOT NULL,
  sales_order_no varchar(64) NOT NULL,
  customer_name varchar(200) NOT NULL DEFAULT '',
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),

  -- === 上游：9.6.1.1 代发配置（同库，可建 FK）===
  config_id uuid NOT NULL,
  config_code varchar(32) NOT NULL,
  supplier_code varchar(64) NOT NULL,
  fulfillment_path varchar(16) NOT NULL,
  ownership_mode varchar(16) NOT NULL,
  -- 货权判定**读 9.6.1.1 的生成列**，这里只是快照，不在本模块重算第二份
  counts_against_own_inventory boolean NOT NULL,

  -- === 状态机：DRAFT → READY → DISPATCHED → ACCEPTED → (SHIPPED / FAILED) ===
  --   DRAFT      已建单，未确认付款
  --   READY      已确认付款，可向供应商下单
  --   DISPATCHED 已提交供应商，等待回执
  --   ACCEPTED   供应商已受理并回填单号与实际成本（此处可产生告警）
  --   SHIPPED    供应商已发货（运单号交给下游 9.6.1.3 / 10 物流）
  --   FAILED     下单失败或供应商拒单（**必产生 ORDER_FAILED 告警**）
  --   CANCELLED  已取消
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','READY','DISPATCHED','ACCEPTED','SHIPPED','FAILED','CANCELLED')),
  dispatch_mode varchar(16) NOT NULL CHECK(dispatch_mode IN
    ('AUTO','MANUAL')),
  vendor_code varchar(32) NOT NULL CHECK(vendor_code IN ('MANUAL_ENTRY','ALIBABA_1688')),

  -- === 供应商回执（蓝图：回填供应商单号与代发成本）===
  supplier_order_no varchar(128) NOT NULL DEFAULT '',
  -- 供应商运单号：本模块只**记录**回执，跟踪与妥投判定属下游 9.6.1.3
  supplier_tracking_no varchar(128) NOT NULL DEFAULT '',
  supplier_shipped_at timestamptz,
  rejection_reason varchar(400) NOT NULL DEFAULT '',

  -- === 金额：基列（可写），结论列（生成列，不可写）===
  -- 我方售价：按销售订单行快照下来，下单后买家改价不影响已下的代发单
  sell_amount numeric(19,4) NOT NULL CHECK(sell_amount >= 0),
  -- 采购价（**下单时冻结**）：涨价告警比的就是它，不是实时配置价
  purchase_amount numeric(19,4) NOT NULL CHECK(purchase_amount >= 0),
  -- 供应商运费：代发成本 = 采购价 + 供应商运费（蓝图原话）
  supplier_shipping_amount numeric(19,4) NOT NULL DEFAULT 0
    CHECK(supplier_shipping_amount >= 0),
  -- 供应商回执的**实际**采购价与运费：留空 = 还没有回执；
  -- 有值且与 expected 不符时由服务层落涨价告警，并用它重算差价。
  actual_purchase_amount numeric(19,4) CHECK(actual_purchase_amount >= 0),
  actual_supplier_shipping_amount numeric(19,4)
    CHECK(actual_supplier_shipping_amount >= 0),

  -- === 三个生成列：代发成本与差价（唯一真相，服务层不另算）===
  dropship_cost numeric(19,4) GENERATED ALWAYS AS (
    purchase_amount + supplier_shipping_amount
  ) STORED,
  spread_amount numeric(19,4) GENERATED ALWAYS AS (
    sell_amount - purchase_amount - supplier_shipping_amount
  ) STORED,
  margin_rate numeric(9,6) GENERATED ALWAYS AS (
    CASE WHEN sell_amount > 0
         THEN round((sell_amount - purchase_amount - supplier_shipping_amount) / sell_amount, 6)
         ELSE NULL END
  ) STORED,

  -- === 已付款的人工确认凭据（见文件头第二节）===
  payment_confirmed_at timestamptz,
  payment_confirmed_by_membership_id uuid,
  -- 恒为 MANUAL_ATTESTATION：现在没有上游实收流水可读（ownerModule 12 财务）
  payment_confirmed_source varchar(24) NOT NULL DEFAULT ''
    CHECK(payment_confirmed_source IN ('','MANUAL_ATTESTATION')),
  payment_confirmed_note text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by_membership_id uuid,
  dispatched_at timestamptz, dispatched_by_membership_id uuid,
  accepted_at timestamptz, accepted_by_membership_id uuid,
  version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_no),
  -- 一张销售订单在同一供应商下只建一张代发单：重复建单 = 同一批货发两次。
  -- 换供应商重下是**取消后新建**，不是原地改（改了会丢掉上一张的回执与告警）。
  UNIQUE(tenant_id,sales_order_id,supplier_code),
  FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_order(tenant_id,id),
  FOREIGN KEY(tenant_id,config_id) REFERENCES fulfillment_dropship_config(tenant_id,id),

  CHECK(btrim(order_no)<>'' AND btrim(sales_order_no)<>''),
  CHECK(btrim(supplier_code)<>'' AND btrim(config_code)<>''),
  -- 只给供应商直发建代发单：自营发货的货从我方仓出，没有「向供应商下单」这回事
  CHECK(fulfillment_path = 'SUPPLIER_DIRECT'),
  -- 人工确认四件套：要么一个都没有（DRAFT），要么**全都有**（确认过）。
  -- 与 V118 的 ownership 确认同构：两个方向互斥，而不是单向蕴含。
  CHECK(
    (payment_confirmed_at IS NULL
       AND payment_confirmed_by_membership_id IS NULL
       AND coalesce(btrim(payment_confirmed_note),'') = ''
       AND payment_confirmed_source = '')
    OR
    (payment_confirmed_at IS NOT NULL
       AND payment_confirmed_by_membership_id IS NOT NULL
       AND payment_confirmed_source = 'MANUAL_ATTESTATION'
       AND coalesce(btrim(payment_confirmed_note),'') <> '')
  ),
  -- 实际成本要么都没回执，要么两件都有：只回一个会让「代发成本」有两个答案
  CHECK((actual_purchase_amount IS NULL) = (actual_supplier_shipping_amount IS NULL)),
  -- 供应商已发货必须带发货时刻与运单号，否则「已发货」无从追（下游 9.6.1.3 无从接）
  CHECK(status <> 'SHIPPED'
        OR (supplier_shipped_at IS NOT NULL AND btrim(supplier_tracking_no) <> '')),
  -- 失败必须写明原因，否则「失败」两个字在告警里无法处置
  CHECK(status <> 'FAILED' OR btrim(rejection_reason) <> ''),
  -- 受理必须回填供应商单号
  CHECK(status NOT IN ('ACCEPTED','SHIPPED') OR btrim(supplier_order_no) <> '')
);

-- ---------------------------------------------------------------------------
-- 2. fulfillment_dropship_order_line · 代发单行（传递商品信息给供应商）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_order_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),
  -- SKU 快照（照抄 V99 sales_order_line 的纪律：不引用实时商品）
  sku_id uuid NOT NULL,
  sku_ref varchar(64) NOT NULL CHECK(btrim(sku_ref)<>''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  spec_snapshot varchar(300) NOT NULL DEFAULT '',
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  -- 行金额口径与 V99 一致：line_amount = unit_price × quantity，DB 侧独立复算
  sell_unit_price numeric(19,4) NOT NULL CHECK(sell_unit_price >= 0),
  purchase_unit_price numeric(19,4) NOT NULL CHECK(purchase_unit_price >= 0),
  sell_amount numeric(19,4) NOT NULL CHECK(sell_amount >= 0),
  purchase_amount numeric(19,4) NOT NULL CHECK(purchase_amount >= 0),
  -- 供应商侧货号：代发平台按它找货，没有它供应商根本不知道发哪件
  supplier_sku_ref varchar(64) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id,line_no),
  FOREIGN KEY(tenant_id,order_id) REFERENCES fulfillment_dropship_order(tenant_id,id)
    ON DELETE CASCADE,
  CHECK(sell_amount = round(sell_unit_price * quantity, 4)),
  CHECK(purchase_amount = round(purchase_unit_price * quantity, 4))
);

-- ---------------------------------------------------------------------------
-- 3. fulfillment_dropship_supplier_endpoint · 供应商代发接口登记（只存引用，不存凭证）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_supplier_endpoint (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  vendor_code varchar(32) NOT NULL CHECK(vendor_code IN ('MANUAL_ENTRY','ALIBABA_1688')),
  vendor_name varchar(200) NOT NULL CHECK(btrim(vendor_name)<>''),
  base_url varchar(300) NOT NULL DEFAULT '',
  -- ⚠️ 凭证**引用**（指向 secret manager / K8s External Secrets 的键），
  -- 迁移与代码里都不出现任何明文 key/secret（AGENTS.md 安全条）
  credential_ref varchar(200) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  -- 未登记 = 该 vendor **未接线**：服务层会显式报 503，而不是伪造一份平台响应
  wired boolean NOT NULL DEFAULT false,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,vendor_code),
  -- 声称已接线就必须真的给出调用信息，否则「已接线」只是个标签
  CHECK(wired = false OR btrim(base_url) <> '')
);

-- ---------------------------------------------------------------------------
-- 4. fulfillment_dropship_alert · 差异告警（蓝图点名必须显式的一条）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_alert (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 四类差异互不合并：处置动作不同（改供应商 / 催发货 / 改售价 / 退款）
  alert_kind varchar(24) NOT NULL CHECK(alert_kind IN
    ('PRICE_INCREASE','NO_STOCK','ORDER_FAILED','NEGATIVE_SPREAD')),
  severity varchar(8) NOT NULL DEFAULT 'WARNING' CHECK(severity IN ('INFO','WARNING','CRITICAL')),
  -- 判定当时的两个数：涨价告警存 expected（下单冻结价）与 actual（回执价），
  -- 事后改配置不会让历史告警看起来是按新规则算的
  expected_amount numeric(19,4),
  actual_amount numeric(19,4),
  detail varchar(400) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
  acknowledged_at timestamptz,
  acknowledged_by_membership_id uuid,
  resolution_note text NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES fulfillment_dropship_order(tenant_id,id)
    ON DELETE CASCADE,
  CHECK(btrim(detail)<>''),
  -- 已处置必须带处置人与处置说明
  CHECK(status = 'OPEN'
        OR (acknowledged_at IS NOT NULL
            AND acknowledged_by_membership_id IS NOT NULL
            AND coalesce(btrim(resolution_note),'') <> ''))
);

-- ---------------------------------------------------------------------------
-- 5. fulfillment_dropship_order_event · 事件流（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_order_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  event_kind varchar(32) NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  detail varchar(400) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES fulfillment_dropship_order(tenant_id,id)
    ON DELETE CASCADE
);

-- ===========================================================================
-- 6. 索引（按本模块真实的查询形状，不多建）
-- ===========================================================================
-- 代发单列表主排序（按建单时间倒序）+ 状态筛选
CREATE INDEX dropship_order_status_idx
  ON fulfillment_dropship_order(tenant_id,status,created_at desc);
-- 「这张销售订单的代发单是哪张」与同供应商查单
CREATE INDEX dropship_order_sales_idx
  ON fulfillment_dropship_order(tenant_id,sales_order_id);
CREATE INDEX dropship_order_supplier_idx
  ON fulfillment_dropship_order(tenant_id,supplier_code,created_at desc);
-- 告警工作台：未处置告警按严重度与时间排（蓝图的告警是硬输出，必须好找）
CREATE INDEX dropship_alert_open_idx
  ON fulfillment_dropship_alert(tenant_id,status,severity,occurred_at desc);
CREATE INDEX dropship_alert_order_idx
  ON fulfillment_dropship_alert(tenant_id,order_id,occurred_at desc);
CREATE INDEX dropship_line_order_idx
  ON fulfillment_dropship_order_line(tenant_id,order_id,line_no);
CREATE INDEX dropship_event_order_idx
  ON fulfillment_dropship_order_event(tenant_id,order_id,occurred_at desc);

-- ===========================================================================
-- 7. 不变量触发器
-- ===========================================================================
-- (a) 代发单状态机。FAILED 可从 READY / DISPATCHED / ACCEPTED 到达
--     （提交给供应商的那一刻就可能被拒，订单当时还在 READY），
--     CANCELLED 只允许在还没提交给供应商之前 —— 已提交后取消要走 11 售后。
CREATE OR REPLACE FUNCTION guard_dropship_order_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT'      THEN NEW.status IN ('READY','CANCELLED')
    WHEN 'READY'      THEN NEW.status IN ('DISPATCHED','FAILED','CANCELLED')
    WHEN 'DISPATCHED' THEN NEW.status IN ('ACCEPTED','FAILED')
    WHEN 'ACCEPTED'   THEN NEW.status IN ('SHIPPED','FAILED')
    WHEN 'SHIPPED'    THEN false
    WHEN 'FAILED'     THEN NEW.status = 'DISPATCHED'   -- 换供应商/重试可再提交
    WHEN 'CANCELLED'  THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '代发单 % cannot move % -> %', OLD.order_no, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_order_state
  BEFORE UPDATE ON fulfillment_dropship_order
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_order_state();

-- (b) **未确认付款不许提交给供应商**。触发器而不只是服务层校验：
--     「确认过已付款」是这张单上唯一不可逆的动作的前提，一旦绕过服务层写 SQL
--     就不该拦得住。
CREATE OR REPLACE FUNCTION guard_dropship_order_payment_confirmed() RETURNS trigger AS $$
BEGIN
  IF NEW.status IN ('DISPATCHED','ACCEPTED','SHIPPED') THEN
    IF NEW.payment_confirmed_at IS NULL
       OR NEW.payment_confirmed_by_membership_id IS NULL
       OR NEW.payment_confirmed_source <> 'MANUAL_ATTESTATION'
       OR coalesce(btrim(NEW.payment_confirmed_note),'') = '' THEN
      RAISE EXCEPTION
        '代发单 % 未确认收款，禁止提交给供应商：销售订单（9.1）没有支付状态列，'
        '12 财务的实收流水尚未交付，因此本模块要求有权限的成员书面确认'
        '（payment_confirmed_at / 确认人 / MANUAL_ATTESTATION / 依据）',
        NEW.order_no USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_order_payment_confirmed
  BEFORE INSERT OR UPDATE ON fulfillment_dropship_order
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_order_payment_confirmed();

-- (c) 代发单头金额必须等于行金额之和（与 V99 的 sales_order_totals_check 同一手法）：
--     「差价毛利」只有一个真相，头上一个数、行里另一组数时对账必然对不上。
--
--     必须是 **DEFERRABLE INITIALLY DEFERRED 的约束触发器**：建单是先插头再插行，
--     用 BEFORE 触发器的话头插入那一刻行还不存在，sum 恒为 0，头上的金额必然对不上。
--     V99 的 sales_order_totals_check 就是这么写的，本模块照抄，不另发明一套。
CREATE OR REPLACE FUNCTION guard_dropship_order_amounts() RETURNS trigger AS $$
DECLARE s_sell numeric(19,4); s_buy numeric(19,4);
BEGIN
  SELECT coalesce(sum(sell_amount),0), coalesce(sum(purchase_amount),0)
    INTO s_sell, s_buy FROM fulfillment_dropship_order_line WHERE order_id = NEW.id;
  IF NEW.sell_amount <> s_sell OR NEW.purchase_amount <> s_buy THEN
    RAISE EXCEPTION
      '代发单 % 金额与行不符：sell_amount=% purchase_amount=%，行合计 sell=% purchase=%',
      NEW.order_no, NEW.sell_amount, NEW.purchase_amount, s_sell, s_buy
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER dropship_order_amounts
  AFTER INSERT OR UPDATE ON fulfillment_dropship_order
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_order_amounts();

-- (d) 告警与事件流 append-only：告警是**已经发生的差异**的凭据，
--     事后改掉它等于把「曾经贵过 3 块」这段历史抹掉 —— 那正是静默亏损的另一种形状。
--     处置走 status / acknowledged_* 字段，不改历史行。
CREATE OR REPLACE FUNCTION guard_dropship_alert_evidence() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '代发差异告警是已发生差异的凭据，不可删除' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.alert_kind IS DISTINCT FROM OLD.alert_kind
     OR NEW.expected_amount IS DISTINCT FROM OLD.expected_amount
     OR NEW.actual_amount IS DISTINCT FROM OLD.actual_amount
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at THEN
    RAISE EXCEPTION '代发差异告警的种类与判定数值不可改写（只能处置）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_alert_evidence
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_alert
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_alert_evidence();

CREATE OR REPLACE FUNCTION guard_dropship_order_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '代发单事件流只追加，不可改写或删除' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_order_event_append_only
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_order_event
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_order_event_append_only();

-- (e) 代发单进入终态后冻结明细行（货已按最终结算走完，行金额不能再动）。
CREATE OR REPLACE FUNCTION guard_dropship_line_after_terminal() RETURNS trigger AS $$
DECLARE st varchar(16);
BEGIN
  SELECT status INTO st FROM fulfillment_dropship_order WHERE id = NEW.order_id;
  IF st IN ('SHIPPED','CANCELLED') THEN
    RAISE EXCEPTION '代发单已处于终态 %，明细行不可再改', st USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_line_after_terminal
  BEFORE INSERT OR UPDATE ON fulfillment_dropship_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_line_after_terminal();

-- ===========================================================================
-- 8. 多租户与授权（与 trade V1 第 2 节、V118 第 10 节同一套写法）
-- ===========================================================================
-- 五张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'fulfillment_dropship_order',
    'fulfillment_dropship_order_line',
    'fulfillment_dropship_supplier_endpoint',
    'fulfillment_dropship_alert',
    'fulfillment_dropship_order_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_dropship_order,
  fulfillment_dropship_order_line,
  fulfillment_dropship_supplier_endpoint,
  fulfillment_dropship_alert,
  fulfillment_dropship_order_event TO trade_app;
-- 事件流是凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON
  fulfillment_dropship_order_event FROM trade_app;
-- 告警表**只收回 DELETE**、保留 UPDATE：处置一条告警（acknowledge）必须能改
-- status / acknowledged_* / resolution_note，否则「已处置」无处落地。
-- 不可改写的是**判定证据**那一部分（alert_kind / expected / actual / occurred_at），
-- 由 triggers_dropship_alert_evidence 在 DB 层逐字段挡住（见第 7 节 (d)）——
-- 也就是说「能改处置」与「不能改事实」是两件事，分开授权。
REVOKE DELETE ON fulfillment_dropship_alert FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V118 同口径）。
REVOKE ALL ON
  fulfillment_dropship_order,
  fulfillment_dropship_order_line,
  fulfillment_dropship_supplier_endpoint,
  fulfillment_dropship_alert,
  fulfillment_dropship_order_event FROM starcon_app;
-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE dropship_order_no_seq TO trade_app;

-- ---------------------------------------------------------------------------
-- 9. 跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表/序列/索引/触发器**，对既有表的写入面为：
--   * **零 ALTER**：不碰 V99 的 sales_order / sales_order_line /
--     order_address_snapshot，不碰 V118 的七张 fulfillment_dropship_*，
--     不碰 V92 / V116 / V97 / V98 的任何一张表。
--   * 两个**同库**外键（→ sales_order、→ fulfillment_dropship_config）指向
--     **本服务自己的表**：这是同库引用，不是 AGENTS.md 禁止的跨服务 FK。
--     加 FK 不修改被引用表的任何列、约束或触发器，因此既有模块的写入面不变。
-- 五张新表落在 fulfillment_dropship_order* / fulfillment_dropship_alert / 
-- fulfillment_dropship_supplier_endpoint，与 9.6.1.1 的 fulfillment_dropship_config
-- 并列而不混用：配置说「这个 SKU 走不走代发」，代发单说「这一票具体怎么下的」。
-- 被本迁移约束到的既有模块 = 无，跨车道 DDL 回归面 = 0。