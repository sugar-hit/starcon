-- 11.5.1.1 退货分析（卷 11 售后与退货 / L3 11.5 售后分析锚点 11.5.1）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：统计退货情况：退货率（按店铺/SKU/类目/原因/批次）、退货金额趋势、拒退率、
--         处理时效、平均退款金额与原因分布。
--   输入：售后与退货数据；销售数据。
--   输出：退货分析报表 + 维度下钻 + 异常识别。
--   关系：上游 = 11.1、11.2、11.3；下游 = 13 分析、6 采购（质量改进）。
--   **退货率必须与销售额同口径比较**，只看绝对数会误判（退货率从 3% 升到 5% 严重，
--   但绝对数可能没变）。
--
-- 归属：**trade 服务**（同 L3 11.5 锚点，与兄弟模块 11.5.2.1 质量分析同服务同库）。
-- 输入句的两项上游（11.1 售后、11.2 退货）**都在本服务**，销售数据（V99 sales_order 簇）
-- 也在本服务 ⇒ 本模块是**同库只读聚合**，不碰任何跨服务路径。
--
-- 本迁移**只新建三张表与触发器、零 ALTER 零 DROP 既有表**：
--   * aftersales_case / aftersales_case_line（V156/V157，11.1.1.x 已交付）**只读**：
--     退货金额、件数、原因、售后单时效的事实来源。
--   * sales_order / sales_order_line（V99，9 章已交付）**只读**：
--     **销售额分母的唯一来源**（grand_total_amount / currency / status / submitted_at），
--     以及 SKU 行的件数与行金额。
--   * platform_order（V207，9.1.2.1 已交付）**只读**：shop_code / platform_code —— 店铺维度。
--   * rma_authorization / rma_authorization_line（V161，11.2.1.1 已交付）**只读**：
--     拒退率与授权审批时效的事实来源。
--   * return_receipt / return_receipt_line（V172，11.2.2.1 已交付）**只读**：
--     批次维度（batch_no）与实收件数、买家回寄时效。
--   * refund_application（V166，11.3.1.1 已交付）**只读**：平均退款金额与退款时效。
--   * return_quality_category / return_quality_category_sku（V179，11.2.3.1 已交付）**只读**：
--     trade 侧唯一的 SKU→类目映射（catalog 的正式商品类目在**独立库**，禁止跨库 join）。
--   * aftersales_reason_attribution_set / _item / aftersales_reason_category
--     （V158，11.1.2.1 已交付）**只读**：归因口径的原因分布。
-- ⇒ **跨车道 DDL 回归面 = 0**（同批另有五条车道也在往 trade 落迁移）。
--
-- ⚠️ **零外键**：刻意不对任何既有表加 FK，也不对本模块新表加指向既有表的外键 ——
--   加 FK 会让本迁移对别的车道的表产生 DDL 依赖，而同批并行车道随时可能 ALTER 那些表。
--   归属与存在性由服务层在读写时真查一次校验（照抄 V156/V161/V172 的同一套写法）。

-- ======================================================================
-- 第一节：return_analysis_basis —— 退货分析口径（租户自维护、版本化）
-- ======================================================================
--
-- 为什么需要这张表：蓝图关系句把「**退货率必须与销售额同口径比较**」写成加粗句，
-- 而「同口径」本身有**几个必须由租户自己拍板的选择**，系统替他们拍就是替他们编：
--   ① 「仅退款」（REFUND_ONLY）是买家不退货只退钱 —— 把它算进退货金额，
--      报出来的「退货率」里混着根本没发生退货的钱；默认**不含**。
--   ② 异常识别的阈值（几倍算异常、最小基数多少）—— 换一个类目的正常波动，
--      在大店铺是噪声、在小店铺是事故，租户比系统更清楚自己的量级。
--   ③ 这套口径**必须留痕**：三个月后回看一张退货率报表，
--      答不出「当时用的是哪套口径」的话，这个数字就不能用来做改进决策。
--
-- ⇒ 口径做成**租户可维护的真实数据**，每个币种同时只有一份生效版本，
--   没登记时用**文档化的默认口径**并在响应里如实标出 `caliberSource`。
CREATE TABLE return_analysis_basis (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 口径单号：租户内唯一（序列发号器，末尾 GRANT USAGE）。
  basis_no varchar(32) NOT NULL,

  -- 口径名：租户自己给这套口径起的名字（「含仅退款口径」「亚马逊口径」……）。
  basis_name varchar(128) NOT NULL CHECK(btrim(basis_name) <> ''),

  -- 口径适用的币种：**分子分母必须同币种**（蓝图加粗句的直接落点）。
  -- 一个币种一份口径 ⇒ 换币种就是换一套口径，不会把两种币种的数混在一张报表里。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ---- 口径开关 ----
  -- 分子是否含「仅退款」类售后单（买家不退货、只退钱）。默认**不含**：
  -- 含了以后报出来的「退货率」里混着没发生退货的钱，量级一大就完全失真。
  include_refund_only boolean NOT NULL DEFAULT false,

  -- ---- 异常识别阈值（全部可调，缺省值即文档化的默认口径）----
  -- 退货率异常倍数：维度退货率 ≥ 总体退货率 × 本值 时报异常。
  rate_alert_multiplier numeric(9,4) NOT NULL DEFAULT 2.0000 CHECK(rate_alert_multiplier > 0),
  -- 退货率异常的最小基数：维度销售额 ≥ 总体销售额 × 本值 才判异常。
  -- ⚠️ **没有最小基数就没有例外**：一个只卖了 1 单的 SKU 退了 1 件，
  --   退货率就是 100%，会把每个小 SKU 都报成异常，异常清单也就没人看了。
  rate_alert_min_sales_share numeric(9,6) NOT NULL DEFAULT 0.010000
    CHECK(rate_alert_min_sales_share >= 0 AND rate_alert_min_sales_share <= 1),
  -- 拒退率异常的百分点差：店铺拒退率 ≥ 总体拒退率 + 本值（百分点）时报异常。
  rejection_alert_gap_pp numeric(9,4) NOT NULL DEFAULT 20.0000 CHECK(rejection_alert_gap_pp >= 0),
  -- 拒退率异常的最小单据数：店铺已决定单数 ≥ 本值 才判异常（同样是为了排除小样本）。
  rejection_alert_min_cases bigint NOT NULL DEFAULT 5 CHECK(rejection_alert_min_cases >= 0),
  -- 批次件数异常倍数：批次实收件数 ≥ 平均批次件数 × 本值 时报异常。
  batch_alert_multiplier numeric(9,4) NOT NULL DEFAULT 3.0000 CHECK(batch_alert_multiplier > 0),
  -- 批次件数异常的最小件数：批次实收件数 ≥ 本值 才判异常。
  batch_alert_min_qty numeric(19,6) NOT NULL DEFAULT 10 CHECK(batch_alert_min_qty >= 0),
  -- 原因集中异常占比：单一退货原因占比 ≥ 本值时报异常。
  reason_alert_share numeric(9,6) NOT NULL DEFAULT 0.600000
    CHECK(reason_alert_share >= 0 AND reason_alert_share <= 1),
  -- 原因集中异常的最小案件数：单一原因案件数 ≥ 本值 才判异常。
  reason_alert_min_cases bigint NOT NULL DEFAULT 10 CHECK(reason_alert_min_cases >= 0),
  -- 趋势恶化异常的百分点差：本期退货率 − 上期退货率 ≥ 本值（百分点）时报异常。
  trend_alert_gap_pp numeric(9,4) NOT NULL DEFAULT 5.0000 CHECK(trend_alert_gap_pp >= 0),

  -- ---- 版本与生效区间 ----
  basis_status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(basis_status IN (
    'ACTIVE','SUPERSEDED'
  )),
  -- 口径生效日：报表窗口**完全早于**这一天时，用默认口径而不是这份口径
  -- （用后来才定的口径去解释历史数字，等于事后改口径）。
  effective_from date NOT NULL,
  -- ⚠️ 刻意**可空**：生效中的口径这三列本来就是空的，写成 NOT NULL 会让建行就违约。
  effective_to date,
  source_note varchar(500) NOT NULL DEFAULT '' CHECK(length(source_note) <= 500),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  superseded_by uuid,
  superseded_at timestamptz,
  supersede_reason varchar(1000) NOT NULL DEFAULT '' CHECK(length(supersede_reason) <= 1000),
  -- 乐观锁：换版是「读版本 → 改状态」，两个人同时换版时必须有一个人被挡住。
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,basis_no),

  CHECK(effective_to IS NULL OR effective_to >= effective_from),
  -- 换过版必须留痕：老版本保留为 SUPERSEDED，且指向新版本并写明理由。
  CHECK(basis_status <> 'SUPERSEDED'
    OR (superseded_by IS NOT NULL AND length(btrim(supersede_reason)) > 0)),
  -- 生效中的口径没有截止日：有截止日却还叫「生效中」会让「用哪一份」有两个答案。
  CHECK(basis_status <> 'ACTIVE' OR effective_to IS NULL)
);

-- 生效唯一：**每个币种同时只有一份生效口径**。
-- 两份同时生效时「按哪一份算退货率」没有唯一答案，而退货率是本模块的头号数字。
CREATE UNIQUE INDEX return_analysis_basis_active
  ON return_analysis_basis(tenant_id, currency)
  WHERE basis_status = 'ACTIVE';

COMMENT ON TABLE return_analysis_basis IS
  '11.5.1.1 退货分析口径：分子是否含仅退款 + 异常识别阈值（租户自维护、每个币种一份生效版本）';
COMMENT ON COLUMN return_analysis_basis.include_refund_only IS
  '分子是否含「仅退款」类售后单（买家不退货只退钱）。默认 false —— 含了以后「退货率」里混着没发生退货的钱';
COMMENT ON COLUMN return_analysis_basis.effective_from IS
  '口径生效日：报表窗口完全早于这一天时用默认口径（不用后来才定的口径解释历史数字）';

-- ======================================================================
-- 第二节：return_analysis_snapshot —— 退货分析报表快照（追加式）
-- ======================================================================
--
-- 为什么要有这张表：蓝图输出句是「**退货分析报表**」，而报表有个现实属性 ——
-- **同一个窗口重算一次，数字可能不一样**（上游补录了一单、口径换了一版）。
-- 于是「上个月那张报表是多少」这个问题，没有落库就永远答不出来，
-- 而这张报表是要拿去跟平台对账、跟采购谈质量改进的。
-- ⇒ 每次保存报表，把**当时的窗口、币种、口径与算出来的每个维度值**原样落库。
--
-- ⚠️ **追加式**：快照不改写、不删除。改错了重存一份（新 snapshot_no），
--   老的那份留着 —— 快照是「当时算出来的东西」的证据，不是可编辑的报表草稿。
CREATE TABLE return_analysis_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 快照单号：一次保存 = 一个 snapshot_no，下挂多行（每个维度值一行）。
  snapshot_no varchar(32) NOT NULL,

  -- 窗口（与算这行数字时用的窗口逐字一致）：左闭右开的上界由服务层算出。
  window_from date,
  window_to date,

  -- 币种：**分子分母同币种**，一行快照只属于一个币种。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 维度与维度取值：'OVERVIEW' + 'TOTAL' 表示总体那一行。
  dimension varchar(16) NOT NULL CHECK(dimension IN (
    'OVERVIEW','SHOP','SKU','CATEGORY','REASON','BATCH'
  )),
  dimension_key varchar(160) NOT NULL,
  dimension_label varchar(200) NOT NULL DEFAULT '',

  -- 口径标记：这行数字是**单头口径**（售后单申请金额）还是**行级口径**（售后商品行金额）
  -- 还是**件数口径**（实收件数 / 销量件数）。三者的分子分母**不是同一个东西**，
  -- 混在一张表里不标出来，读的人会把两个口径的数直接比大小。
  caliber varchar(16) NOT NULL CHECK(caliber IN (
    'CASE_LEVEL','LINE_LEVEL','QTY_LEVEL'
  )),

  -- ---- 分子分母（同币种同窗口）----
  case_count bigint NOT NULL DEFAULT 0 CHECK(case_count >= 0),
  return_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(return_qty >= 0),
  return_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(return_amount >= 0),
  sales_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(sales_amount >= 0),
  -- 退货率：**比值**，标度 6（= 4 位小数），0.050000 表示 5%。
  -- 分母为 0 时**存 null 而不是 0**：没有销售额就没有率，存 0 会被读成「退货率 0%」。
  return_rate numeric(12,6) CHECK(return_rate IS NULL OR return_rate >= 0),

  -- ---- 拒退（**总体口径**，维度行不适用 ⇒ 存 null 而不是 0）----
  -- ⚠️ 刻意**可空**：填 0 会被读成「这一行没有拒退」，
  --   而真相是「这一行是维度明细，拒退率是总体口径，另有一行 OVERVIEW 带着它」。
  rejected_count bigint CHECK(rejected_count IS NULL OR rejected_count >= 0),
  decided_count bigint CHECK(decided_count IS NULL OR decided_count >= 0),
  rejection_rate numeric(12,6) CHECK(rejection_rate IS NULL OR rejection_rate >= 0),

  -- ---- 退款与时效（同样可空：维度行不适用）----
  refund_count bigint CHECK(refund_count IS NULL OR refund_count >= 0),
  refund_total numeric(19,4) CHECK(refund_total IS NULL OR refund_total >= 0),
  avg_refund_amount numeric(19,4) CHECK(avg_refund_amount IS NULL OR avg_refund_amount >= 0),
  avg_resolution_hours numeric(19,4) CHECK(avg_resolution_hours IS NULL OR avg_resolution_hours >= 0),

  -- ---- 异常标记（这一行触发了哪条规则）----
  anomaly_code varchar(48) NOT NULL DEFAULT '',
  anomaly_note varchar(500) NOT NULL DEFAULT '',

  -- 口径说明：这一行数字是按哪套口径算出来的（原样落在快照里，日后不依赖代码版本）。
  basis_note varchar(1000) NOT NULL DEFAULT '',
  computed_at timestamptz NOT NULL,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,snapshot_no,currency,dimension,dimension_key)
);

CREATE INDEX return_analysis_snapshot_by_run
  ON return_analysis_snapshot(tenant_id, snapshot_no, currency, dimension);
CREATE INDEX return_analysis_snapshot_by_dimension
  ON return_analysis_snapshot(tenant_id, dimension, currency, computed_at);

COMMENT ON TABLE return_analysis_snapshot IS
  '11.5.1.1 退货分析报表快照：一次保存 = 一个 snapshot_no + 每个维度值一行（追加式，不可改写）';
COMMENT ON COLUMN return_analysis_snapshot.return_rate IS
  '退货率（比值，标度 6 = 4 位小数；0.050000 表示 5%）。分母为 0 时为 null —— 没有销售额就没有率';

-- ======================================================================
-- 第三节：return_analysis_event —— 留痕（追加式）
-- ======================================================================
CREATE TABLE return_analysis_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  event_scope varchar(24) NOT NULL CHECK(event_scope IN ('BASIS','SNAPSHOT')),
  event_type varchar(32) NOT NULL CHECK(btrim(event_type) <> ''),
  subject_id uuid NOT NULL,
  event_note varchar(1000) NOT NULL DEFAULT '' CHECK(length(event_note) <= 1000),
  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,event_scope,subject_id,event_type,occurred_at)
);

CREATE INDEX return_analysis_event_by_subject
  ON return_analysis_event(tenant_id, event_scope, subject_id, occurred_at);

COMMENT ON TABLE return_analysis_event IS
  '11.5.1.1 分析侧留痕：口径登记与换版、报表快照生成';

-- ======================================================================
-- 第四节：守卫触发器（把「不能编」钉在 DB 层，服务层与 DB 各判一次）
-- ======================================================================

-- 守卫①：口径换版**必须留痕**。
--   换一版口径会让**整页退货率数字不同** —— 三个月后再看这张报表，
--   「上次是多少」答不上来的话，这个数字就再也不能用来做改进决策了。
CREATE OR REPLACE FUNCTION guard_return_analysis_basis() RETURNS trigger AS $$
BEGIN
  IF NEW.basis_status = 'SUPERSEDED'
     AND OLD.basis_status <> 'SUPERSEDED'
     AND NOT EXISTS (
       SELECT 1 FROM return_analysis_event e
        WHERE e.tenant_id = NEW.tenant_id
          AND e.event_scope = 'BASIS'
          AND e.subject_id = NEW.id
          AND e.event_type = 'SUPERSEDED'
     ) THEN
    RAISE EXCEPTION '口径换版必须先写留痕（return_analysis_event / BASIS / SUPERSEDED）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER return_analysis_basis_guard
  BEFORE UPDATE ON return_analysis_basis
  FOR EACH ROW EXECUTE FUNCTION guard_return_analysis_basis();

-- 守卫②：快照的**分子分母必须是同一个币种**。
--   跨币种相除算出来的「退货率」是一个没有意义的数（100 元退货 ÷ 1000 日元销售额 = 10%）。
--   本表的 currency 列保证行内是同一个币种，而分子分母各自来自同一币种的口径
--   —— 由服务层保证（两端都用同一 currency 分组查出来再相除）。
--   这里再钉两条**永远不成立**的组合：
--   ① 退货率为负 —— 分子分母都是非负金额，比值不可能是负的；
--   ② 拒退率大于 1 —— 拒退单的集合是已决定单的**子集**，比值必然 ≤ 1。
--
-- ⚠️ **刻意不拦「退货率 > 1」**：本期退款金额大于本期销售额是**可能**发生的
--   （上期卖出的货这期退回来），把它当成口径错误一刀切掉，
--   等于把一张真实的报表卡住存不进去 —— 而报表存不进去时，
--   运营的反应是「这个功能不好用」，不是「这个口径有问题」。
--   服务层在**总体行**上把这种情形显式标出来（见 ReturnAnalysisService 的快照说明）。
CREATE OR REPLACE FUNCTION guard_return_analysis_snapshot() RETURNS trigger AS $$
BEGIN
  IF NEW.return_rate IS NOT NULL AND NEW.return_rate < 0 THEN
    RAISE EXCEPTION '退货率 % 是负数 —— 分子分母都是非负金额，比值不可能为负', NEW.return_rate
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.rejection_rate IS NOT NULL AND NEW.rejection_rate < 0 THEN
    RAISE EXCEPTION '拒退率 % 是负数 —— 拒退单数不可能为负', NEW.rejection_rate
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.rejection_rate IS NOT NULL AND NEW.rejection_rate > 1 THEN
    RAISE EXCEPTION '拒退率 % 超过 1 —— 拒退单的集合是已决定单的子集，比值必然 ≤ 1',
      NEW.rejection_rate
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER return_analysis_snapshot_guard
  BEFORE INSERT OR UPDATE ON return_analysis_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_return_analysis_snapshot();

-- ======================================================================
-- 第五节：RLS / GRANT
-- ======================================================================
DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'return_analysis_basis','return_analysis_snapshot','return_analysis_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  return_analysis_basis, return_analysis_snapshot, return_analysis_event TO trade_app;

-- 授权在前、收回在后。
-- 留痕追加式：口径换版与快照生成留的是「当时算出来的东西」的证据，
-- 能被悄悄改掉的证据等于没有证据。
REVOKE UPDATE,DELETE ON return_analysis_event FROM trade_app;
-- ⚠️ **刻意收回 return_analysis_snapshot 的 UPDATE 与 DELETE**：
--   快照是**追加式**的事实（照抄 V185 对 quality_analysis_event 的同一决定）：
--   改错了重存一份（新 snapshot_no），老的那份留着 ——
--   能被改写的快照证明不了「当时算出来的是这个数」。
REVOKE UPDATE,DELETE ON return_analysis_snapshot FROM trade_app;
-- ⚠️ **刻意不收回 return_analysis_basis 的 DELETE**：
--   换版是留痕的（老版本保留为 SUPERSEDED），删除不是 ——
--   删掉的那份口径会立刻让「这个币种当前按哪套口径算」失去唯一答案。
REVOKE DELETE ON return_analysis_basis FROM trade_app;

-- 单号序列（口径单号 / 快照单号各一条，租户之间不重叠是额外好处）。
CREATE SEQUENCE return_analysis_basis_no_seq;
CREATE SEQUENCE return_analysis_snapshot_no_seq;
GRANT USAGE,SELECT ON SEQUENCE return_analysis_basis_no_seq TO trade_app;
GRANT USAGE,SELECT ON SEQUENCE return_analysis_snapshot_no_seq TO trade_app;

-- 本迁移**不 ALTER 任何既有表**：aftersales_case 簇、sales_order 簇、platform_order、
-- rma_authorization 簇、return_receipt 簇、refund_application、return_quality_category 簇、
-- aftersales_reason_* 簇全部**只读**。
-- 本模块对它们的全部依赖是 SELECT / JOIN 与「写入时刻查一次」，
-- **跨车道 DDL 回归面 = 0**（同批另有五条车道也在往 trade 落迁移）。
-- catalog 的商品类目与店铺主数据更是在**另一个数据库**里，本迁移连引用都没有 ——
-- 店铺维度走 trade 侧的 platform_order 投影，类目维度走 trade 侧的
-- return_quality_category_sku 映射（沿用 11.5.2.1 处理 quality_supplier_ref 的同一先例）。
