-- ===========================================================================
-- 10.2.1.1 运单列表
-- ===========================================================================
-- 蓝图原文（10-logistics-and-freight.md:128）：
--   职责：运单主列表：运单号、订单/包裹、承运商渠道、发货仓、状态（待取号/已取号/
--         已揽收/运输中/已签收/异常/已取消）、下单时间、重量、运费。
--   输入：筛选条件（渠道、状态、时间、目的国）；用户数据范围。
--   输出：列表 + 批量操作（申请运单、取消、打印）。
--   关系：上游 = 8.3.4.1、10.2.1.2；下游 = 10.3.1.1 轨迹。
--         **运单状态从承运商侧获取为准**，内部状态仅作展示。
--
-- ---------------------------------------------------------------------------
-- 零、本模块的边界与上游缺口（读迁移前必看）
-- ---------------------------------------------------------------------------
-- 上游盘点（本 worktree 逐个 grep 核实，不是照抄台账）：
--
--   * 10.2.1.2 运单申请 —— **同服务同库**，V128。运单号的**唯一写入所有者**。
--     本模块只**只读** logistics_waybill_request：不 ALTER、不加触发器、不建跨模块
--     FK、不写任何一列。运单清单行全部在**查询时**从 V128 现算（jOOQ 显式 SQL），
--     因此本模块**不存在投影漂移** —— 不需要 sync 定时任务，也不会出现
--     「投影没刷新 → 列表空着 → 现场以为今天没单」。
--     逐字段对账（蓝图要 8 列，V128 给了 7 列）：
--       运单号   → waybill_no            ✅
--       订单/包裹 → sales_order_no / outbound_order_no / pending_parcel_id ✅
--       承运商渠道 → carrier_code / carrier_channel ✅
--       发货仓   → warehouse_ref         ✅
--       状态     → status（**展示态由本模块映射**，见第 4 节）
--       下单时间 → created_at            ✅
--       重量     → gross_weight_kg       ✅
--       运费     → ❌ **V128 没有运费列**（全库 grep freight/shipping_cost 只在
--                  V116 命中，那是 9.3.3.1 分仓决策的**每单估算快照**，既不是本票
--                  运单的实际运费、也不是同一个事实）→ 落到第 2 节登记表。
--
--   * 8.3.4.1 发货交接 —— **跨服务**（warehouse）。本模块**不**读它：交接单是
--     「货已交运」的事实，运单列表这一行在出号那一刻就成立，而交接发生在出号之后。
--     真要跨服务读，形态必须是 ShipmentHandoverQueryPort 那种授权只读 HTTP
--     （不直连对方库 / 不建跨库视图 / 不建跨库 FK）。本模块刻意**不引入**这条
--     依赖：列表是高频读，为它引入一个「不可达就整页 503」的跨服务依赖，会让
--     出号成功但仓库宕机时运营连单号都看不到。→ 交接状态不进本清单，
--     capabilities 里写明 ownerModule=8.3.4.1 与理由。
--
--   * 10.3 物流跟踪 —— **未交付**，集成头无任何承运商轨迹数据源。
--     ⚠️ 这是本迁移最要紧的一处：蓝图那七个展示态里，**已揽收 / 运输中 /
--        已签收 / 异常（承运商侧）四个没有可信数据源**。
--        加粗句写的是「运单状态从承运商侧获取为准」—— 没有 10.3 就**取不到**。
--        所以本模块只从 V128 的**内部**申请状态映射出三个真能兑现的展示态
--        （待取号 / 已取号 / 已取消）+ 一个「申请失败」并**明确它不是物流异常**，
--        另外三个态在 capabilities 里 available=false 并点名 ownerModule=10.3.x。
--        **绝不用 0 / 空串 / 默认「运输中」冒充** —— 留空会被读成「一切正常」。
--
--   * 8.3.4.2 面单与标签打印 —— **未交付**。本模块的「批量打印」因此**不渲染 ZPL、
--     不建第二个打印引擎**，只把 10.2.1.2 申请时登记的 label_uri / label_format
--     作为凭据逐件发出，并把「这一票有没有面单凭据」如实标出来。
--
--   * 10.2.1.3 改址与取消 —— **未交付**。已出号的运单取消属它，本模块对此
--     **409 并点名**，不代做（代做等于在没有时限规则、没有承运商接口的情况下
--     把货「取消成功」，那是最贵的一种显示与事实不一致）。
--
-- 被本迁移约束到的既有表 = 无。零 ALTER、零触发器挂载、零 FK 指向别模块的表。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 运费登记（**本模块的新事实**：蓝图清单里那一列「运费」的落点）
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么是「登记」而不是「算」：运费的唯一可信来源是渠道计费口径
--   （10.1.2.1 运费计算器 + 10.1.2.2 比价），**两卷全未交付** —— 台账确认
--   10.1.1.1/10.1.1.2/10.1.1.3/10.1.2.1/10.1.2.2 全「未开发」，即**没有任何
--   费率与折扣配置**，也没有承运商连接去取回执金额。
--   所以本模块能给出的最诚实答案是「这一票的运费**尚未登记**」并说清归谁，
--   而不是一个编出来的 0 或者一个从别处抄来的估算值。
--
-- ⚠️ 9.4.1.2 已把这条路走过一遍（轨迹状态改为 append-only 登记 + 显式
--   UNSET 桶，绝不用空值冒充「没有轨迹」），本表沿用同一形状。
--
-- 金额：NUMERIC(19,4) + BigDecimal，**禁浮点**（AGENTS.md）。运费是金额，
-- 不是数量也不是重量 —— 重量在 V128 的 gross_weight_kg NUMERIC(19,6)。
CREATE TABLE logistics_waybill_freight (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- === 上游 10.2.1.2（逻辑引用，不是 FK）===
  -- 刻意**不建 FK**：logistics_waybill_request 是 10.2.1.2 拥有的表，
  -- 本车道在它上面建跨模块外键会在对方车道 ALTER DDL 时产生回归面
  -- （同 V128 引用 V124 时刻意不建 FK 的同一理由）。
  -- 真实存在性由**服务层在事务里核**（找不到就是 404，不是 500），
  -- 外加下面那条触发器做第二道。
  request_id uuid NOT NULL,
  request_no varchar(32) NOT NULL CHECK(btrim(request_no) <> ''),
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),

  -- 币种与金额。零运费是合法事实（渠道活动 / 内部调拨），所以 CHECK 只禁负数；
  -- 「没有这一行」与「这一行是 0」是**两回事**，服务层与汇总都分开计数。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  amount numeric(19,4) NOT NULL CHECK(amount >= 0),

  charge_type varchar(24) NOT NULL CHECK(charge_type IN
    ('FORWARD','RETURN','SUPPLEMENTAL','OTHER')),
  -- 来源。刻意只有两种，且**都不是「系统算的」**：
  --  MANUAL_REGISTERED    人工登记（写清依据）
  --  CHANNEL_FEE_STATEMENT 渠道对账单登记
  -- 没有 CARRIER_QUOTE / AUTO_CALCULATED 这类值 —— 加进去就等于宣称
  -- 「本模块能自己算运费」，而 10.1 未交付时那是假的。
  source varchar(48) NOT NULL CHECK(source IN
    ('MANUAL_REGISTERED','CHANNEL_FEE_STATEMENT')),
  evidence_ref varchar(200) NOT NULL DEFAULT '',

  -- append-only + is_current：更正一笔运费是**追加一条新的**并把旧的
  -- 撤下，而不是就地改 —— 运费进了 10.4.1 的物流成本口径，改写历史等于
  -- 让「上个月运费多少」这个问题失去唯一答案。
  is_current boolean NOT NULL DEFAULT true,

  registered_by_membership_id uuid NOT NULL,
  registered_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id)
);

-- 一个运单同一时刻只有一条「现行」运费；历史行照常留档并计入历史查询
CREATE UNIQUE INDEX waybill_freight_current
  ON logistics_waybill_freight(tenant_id,request_id) WHERE is_current;
CREATE INDEX waybill_freight_request
  ON logistics_waybill_freight(tenant_id,request_id,registered_at desc);
-- 汇总要按币种加总，跨币种直接相加是 10.4 那句「跨币种属 10 运费试算职责」
--   （那条线未交付）—— 所以按币种分组给，不给一个混币的总数。
CREATE INDEX waybill_freight_currency
  ON logistics_waybill_freight(tenant_id,currency) WHERE is_current;

-- 运费只能挂在**真实存在**的运单申请上，且承运商必须与申请一致。
-- 挂在不存在的 id 上 = 一条永远查不出来的幽灵运费，会让汇总虚高。
CREATE OR REPLACE FUNCTION guard_waybill_freight_target() RETURNS trigger AS $$
DECLARE want_carrier varchar(64); want_no varchar(32);
BEGIN
  SELECT carrier_code, request_no INTO want_carrier, want_no
    FROM logistics_waybill_request
   WHERE tenant_id = NEW.tenant_id AND id = NEW.request_id;
  IF want_carrier IS NULL THEN
    RAISE EXCEPTION '运单申请 % 不存在（tenant=%），不能挂运费',
      NEW.request_id, NEW.tenant_id USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF want_carrier <> NEW.carrier_code OR want_no <> NEW.request_no THEN
    RAISE EXCEPTION
      '运费行的承运商/申请单号(%/%)与运单申请 % 的(%/%)不一致',
      NEW.carrier_code, NEW.request_no, NEW.request_id, want_carrier, want_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_freight_target_trg
  BEFORE INSERT ON logistics_waybill_freight
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_freight_target();

-- 「更正一笔运费」= 追加新行 + 撤下旧行，而不是就地改金额。所以 UPDATE 只允许
-- 动 is_current 这一列：金额/币种/来源/凭据一经写入就是凭据，改写它们等于让
-- 「上个月这票运费多少」失去唯一答案（10.4.1 物流成本口径要全口径、可回溯）。
CREATE OR REPLACE FUNCTION guard_waybill_freight_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.tenant_id        IS DISTINCT FROM OLD.tenant_id
     OR NEW.request_id   IS DISTINCT FROM OLD.request_id
     OR NEW.request_no   IS DISTINCT FROM OLD.request_no
     OR NEW.carrier_code IS DISTINCT FROM OLD.carrier_code
     OR NEW.currency     IS DISTINCT FROM OLD.currency
     OR NEW.amount       IS DISTINCT FROM OLD.amount
     OR NEW.charge_type  IS DISTINCT FROM OLD.charge_type
     OR NEW.source       IS DISTINCT FROM OLD.source
     OR NEW.evidence_ref IS DISTINCT FROM OLD.evidence_ref
     OR NEW.registered_by_membership_id IS DISTINCT FROM OLD.registered_by_membership_id
     OR NEW.registered_at IS DISTINCT FROM OLD.registered_at THEN
    RAISE EXCEPTION
      '运费登记是凭据：只能追加新行并撤下旧行（改 is_current），不允许改写已有金额'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_freight_immutable_trg
  BEFORE UPDATE ON logistics_waybill_freight
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_freight_immutable();

-- ---------------------------------------------------------------------------
-- 2. 打印留痕（**本模块的新事实**：蓝图输出句「批量操作（…、打印）」的落点）
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么打印要留痕而不只是「返回一份 label 列表」：8.3.4.2 面单打印未交付，
--   本模块只发凭据、不渲染。但「谁在什么时候把哪张面单发到哪台打印机」是**已经
--   发生过的动作**，必须落库 —— 只在响应里回一下的话，出了「面单漏打 / 重复打」
--   就没有任何东西可查。动作本身是真的（真读凭据、真逐件成行、真落一条批次），
-- 唯一的缺口是**重新渲染**，那一条在 capabilities 里点名 8.3.4.2。
--
-- 一行 = **一个批次里的一张面单**。批次号把一次批量打印的逐件结果重新聚起来。
CREATE SEQUENCE waybill_print_batch_no_seq START 1;

CREATE TABLE logistics_waybill_print_log (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- WPL-yyyyMMdd-000001：一次批量打印一个号，逐件共享
  batch_no varchar(32) NOT NULL CHECK(btrim(batch_no) <> ''),

  -- === 上游 10.2.1.2（逻辑引用，理由同第 1 节）===
  request_id uuid NOT NULL,
  request_no varchar(32) NOT NULL CHECK(btrim(request_no) <> ''),
  -- 面单必须**已有号**：没出号就没有面单，DB 层直接把这条路堵死，
  -- 免得留下「打印了空面单」这种凭据污染。
  waybill_no varchar(128) NOT NULL CHECK(btrim(waybill_no) <> ''),
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),

  -- 面单凭据快照（申请当时登记的，10.2.1.2 拥有其唯一写入权）
  label_format varchar(24) NOT NULL DEFAULT '',
  label_uri varchar(400) NOT NULL DEFAULT '',
  -- 这一票**到底有没有面单凭据**，如实标出而不是留空：
  -- RECORDED_REFERENCE 申请时有登记 label_uri，可直接送印
  -- NO_REFERENCE        申请时就没有（承运商未返回 / 模板未取到），**不可送印**
  label_availability varchar(24) NOT NULL CHECK(label_availability IN
    ('RECORDED_REFERENCE','NO_REFERENCE')),

  printer_target varchar(120) NOT NULL DEFAULT '',
  remark varchar(400) NOT NULL DEFAULT '',

  printed_by_membership_id uuid NOT NULL,
  printed_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  -- 同一批次里同一个运单只出现一次（重复提交是幂等命中，不是第二行）
  UNIQUE(tenant_id,batch_no,request_id)
);

CREATE INDEX waybill_print_batch ON logistics_waybill_print_log(tenant_id,batch_no);
CREATE INDEX waybill_print_request ON logistics_waybill_print_log(tenant_id,request_id,printed_at desc);
CREATE INDEX waybill_print_printed_at ON logistics_waybill_print_log(tenant_id,printed_at desc);

-- 运单号与承运商必须与上游申请一致（同第 1 节理由）
CREATE OR REPLACE FUNCTION guard_waybill_print_target() RETURNS trigger AS $$
DECLARE want_no varchar(128); want_carrier varchar(64); want_request_no varchar(32);
BEGIN
  SELECT waybill_no, carrier_code, request_no INTO want_no, want_carrier, want_request_no
    FROM logistics_waybill_request
   WHERE tenant_id = NEW.tenant_id AND id = NEW.request_id;
  IF want_no IS NULL THEN
    RAISE EXCEPTION '运单申请 % 不存在（tenant=%），不能打印',
      NEW.request_id, NEW.tenant_id USING ERRCODE = 'foreign_key_violation';
  END IF;
  -- 上游还没出号时也堵：与本表 CHECK 的 btrim(waybill_no)<>'' 一致，
  -- 上游出了号又被改写的情形由 V128 自己的触发器挡，这里只做双保险。
  IF btrim(want_no) = '' THEN
    RAISE EXCEPTION '运单申请 % 尚未取得运单号，没有面单可打印', NEW.request_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF want_no <> NEW.waybill_no OR want_carrier <> NEW.carrier_code
     OR want_request_no <> NEW.request_no THEN
    RAISE EXCEPTION
      '打印行(%/%/%)与运单申请 % 的(%/%/%)不一致',
      NEW.waybill_no, NEW.carrier_code, NEW.request_no, NEW.request_id,
      want_no, want_carrier, want_request_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_waybill_print_target_trg
  BEFORE INSERT ON logistics_waybill_print_log
  FOR EACH ROW EXECUTE FUNCTION guard_waybill_print_target();

-- ---------------------------------------------------------------------------
-- 3. 多租户与授权（与 trade V1 / V116 / V124 / V128 同一套写法）
-- ---------------------------------------------------------------------------
-- 两张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_waybill_freight',
    'logistics_waybill_print_log' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT ON
  logistics_waybill_freight,
  logistics_waybill_print_log TO trade_app;

-- 两张表都是 append-only 的凭据 / 动作留痕：连运行角色都不给改删，
-- 兜底在权限层而不只在服务层。授权在前、收回在后 —— 反过来写会被上面那条
-- GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON
  logistics_waybill_freight,
  logistics_waybill_print_log FROM trade_app;

-- 运费表**例外**：更正一笔运费 = 追加新行 + 把旧行的 is_current 撤下。
-- 只放行这一列的 UPDATE（PG 的列级授权），金额/币种/来源仍不可改 ——
-- 另有 guard_waybill_freight_immutable 触发器在行级再钉一道（列级授权只限制
-- 「能写哪几列」，触发器限制「这次 UPDATE 实际改了什么」，两层都要）。
GRANT UPDATE (is_current) ON logistics_waybill_freight TO trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V116 / V128 同口径）。
REVOKE ALL ON
  logistics_waybill_freight,
  logistics_waybill_print_log FROM starcon_app;

-- 打印批次号发号器：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列，必须单独授权。
GRANT USAGE, SELECT ON SEQUENCE waybill_print_batch_no_seq TO trade_app;
