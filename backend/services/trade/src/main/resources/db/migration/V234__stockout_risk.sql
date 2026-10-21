-- 6.2.1.1 缺货与断货风险：按日均销量、可用库存、在途量、备货周期计算「预计断货日」，
-- 按紧急度排序输出断货风险清单。
--
-- 蓝图 06-purchase-and-supply.md 6.2.1.1 原文：
--   职责：识别断货风险 SKU，输出补货优先级与缺口量。
--   输入：销售速度（日均销量）、当前可用库存、在途量、备货周期。
--   输出：断货风险清单（SKU、可用量、日均销、可支撑天数、预计断货日、缺口量、涉及店铺），
--         按紧急度排序。
--   关系：下游 = 6.2.1.2 采购建议 / 6.2.1.3 采购申请。
--
-- 归属：**trade 服务**（蓝图 README 系统地图 06 行「后端主责服务：trade + catalog（party）」）。
-- 本仓**不存在** purchase 服务，卷 06 的采购侧单据归 trade；路由 /purchase/demand 是
-- **前端**路由，后端命名空间仍按服务走（与 9.3.2.2 前端 /orders、后端 /trade/fulfillment/routing
-- 同一纪律）。本迁移不 ALTER V1 基线与 V92 的任何一张表，跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、模块边界：本文件**一张库存表都不碰**
-- ===========================================================================
--   * 可用量、在途量、预占量都属 warehouse 服务的库存账，本文件**只存快照与逻辑引用**，
--     真实取数由 trade 的库存端口调 warehouse 的**授权只读接口**（跨服务 SQL / FK / 视图
--     被 AGENTS.md 禁止）。本文件不建任何跨库外键。
--   * 涉及店铺属 channel 服务（listing_mapping.internal_sku_id + shop_code），
--     本文件只落 **shop_code 快照**，不复制 channel 的主数据，也不建跨库外键。
--   * **销售速度是本模块自建的输入事实**：13 销量/报表服务在本仓**不存在**，
--     而「日均销量」是本模块四个输入里唯一一个**没有其它服务可以给**的。
--     所以它必须是一张**可写入、可追溯、版本化**的事实表（见第二节），
--     而不是从代码常量里取的假数。
--
-- ===========================================================================
-- 二、销售速度登记（输入事实，append-only + 生效期版本化）
-- ===========================================================================
-- 「3 月 1 日那天用的日均销是多少」必须答得上来，所以：
--   * **append-only**：登记过的统计口径（日均销量 / 统计窗口 / 来源）**不可改**。
--     改了等于让「当时按哪个数算的」永远查不回来。唯一的例外是 `effective_to`
--     （收口失效日），且**只能从 NULL 设定一次**，之后不可再动。
--   * **生效期版本化**：每条登记带 `effective_from` / `effective_to`（区间语义，
--     与 channel 4.1.2.2 的生效时间点同一套读法）。同一 SKU 同时**只允许一条未收口的
--     登记**（部分唯一索引钉死），否则「当前日均销」就有多个答案。
--   * **写入入口有来源**：`source_kind` 区分人工登记 / 批量导入 / 外部同步 ——
--     「这个数是谁给的」是补货决策的凭据，不是一个可省略的字段。
--
-- 为什么日均销量允许登记为 0：0 是**事实**（这个 SKU 本期确实没卖），与
-- 「没有登记过」是两件事。两者在本模块里的后果不同，所以分成两种状态：
--   * 没有登记        → urgency = NO_DATA（「无数据」，不是 0）
--   * 登记为 0        → urgency = LOW（登记事实：本 SKU 无消耗，请确认登记是否仍成立）
-- 把「没登记」渲染成 0 会让可支撑天数变成无穷、健康度被高估 —— 那正是要防的事。
--
-- ===========================================================================
-- 三、风险清单是**快照**（不是实时计算视图）
-- ===========================================================================
-- 「逐项展开计算过程」「扣减来源可查」「排序依据可见」「涉及店铺」这四件事都要求
-- 结果**落得住**。所以一次计算 = 一个 snapshot + 若干 item + 逐项拆解 + 店铺明细。
-- 快照不可改（它是「那一刻算出来的那份结论」的凭据），重算就是新的一份快照。
--
-- ===========================================================================
-- 1. 销售速度登记（**输入事实**：append-only + 生效期版本化）
-- ===========================================================================
CREATE TABLE stockout_sales_velocity (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- SKU（catalog 的逻辑 ID，跨服务不建外键）+ code / name 快照：
  -- catalog 改名后历史登记仍能显示当时的名称。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- **日均销量**（数量，不是金额）。numeric(19,6) 与仓库数量口径同精度。
  -- 允许 0（登记事实），但 0 与「没登记」在本模块里走不同分支（见文件头第二节）。
  daily_sales_quantity numeric(19,6) NOT NULL CHECK(daily_sales_quantity >= 0),
  -- **统计窗口**：这个日均数是在哪段销售数据上算出来的。窗口与数一起冻结，
  -- 否则「日均 12 件」这句话永远缺一句「12 是哪段日均」。
  window_start date NOT NULL,
  window_end date NOT NULL,
  -- **生效期**（区间语义 [effective_from, effective_to)）。effective_to 为 NULL = 当前生效。
  effective_from date NOT NULL,
  effective_to date,
  -- **来源**：MANUAL 人工登记 / IMPORT 批量导入 / EXTERNAL_SYNC 外部同步。
  source_kind text NOT NULL CHECK(source_kind IN ('MANUAL','IMPORT','EXTERNAL_SYNC')),
  source_ref varchar(128) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 统计窗口必须自洽（不能「从 3 月 5 日到 3 月 1 日」）。
  CONSTRAINT stockout_velocity_window_ordered CHECK(window_end >= window_start),
  -- 失效日不得早于生效日。
  CONSTRAINT stockout_velocity_effective_ordered
    CHECK(effective_to IS NULL OR effective_to >= effective_from),
  -- 同一 SKU 的同一生效日只能有一条登记：两个「从这天起生效」的版本
  -- 会让「当前日均销」出现两个答案，而那是整个模块的地基。
  UNIQUE(tenant_id,sku_id,effective_from)
);
-- **同一 SKU 同时只允许一条未收口的登记**（当前生效版本唯一）。
-- 这一条钉死了「当前日均销」的唯一性：没有它，
-- 「取生效期覆盖评估日的那条」可能在多条之间摇摆。
CREATE UNIQUE INDEX stockout_sales_velocity_open_uq
  ON stockout_sales_velocity(tenant_id,sku_id) WHERE effective_to IS NULL;

-- ===========================================================================
-- 2. 风险清单快照（一次计算 = 一份快照）
-- ===========================================================================
CREATE TABLE stockout_risk_snapshot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 快照号：租户内唯一、单调可读（SR-20261021-0001）。
  snapshot_no varchar(64) NOT NULL CHECK(btrim(snapshot_no)<>''),
  -- **评估基准日**：本次计算「站在哪一天看」。默认取数据库当天（current_date），
  -- 允许显式指定以便回溯复算 —— 但**不接受调用方指定「当前时刻」**，
  -- 时刻一律取数据库时钟。
  as_of_date date NOT NULL,
  -- **计算时刻**：这一次计算真正跑完的时刻。
  computed_at timestamptz NOT NULL,
  -- **落库时刻**：与 computed_at 分开 —— 评估基准日可以是过去（回溯复算），
  -- 而落库时刻永远是真的「这条结论什么时候写进库的」。两个时刻混成一个，
  -- 回溯算出来的历史结论就会看起来像是当时算的。
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  -- **备货周期**（天）：下单到货的天数。它是缺口量与紧急度分档的输入之一。
  lead_time_days int NOT NULL CHECK(lead_time_days > 0),
  -- 评估窗口（天）：本次评估覆盖未来多少天（默认 = 备货周期 × 2）。
  horizon_days int NOT NULL CHECK(horizon_days > 0),
  item_count int NOT NULL DEFAULT 0 CHECK(item_count >= 0),
  critical_count int NOT NULL DEFAULT 0 CHECK(critical_count >= 0),
  no_data_count int NOT NULL DEFAULT 0 CHECK(no_data_count >= 0),
  created_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,snapshot_no),
  -- 窗口至少要盖住一个备货周期，否则「缺口量」没有意义。
  CONSTRAINT stockout_snapshot_horizon_covers_lead_time CHECK(horizon_days >= lead_time_days)
);

-- ===========================================================================
-- 3. 风险清单行（**逐 SKU 的结论**）
-- ===========================================================================
CREATE TABLE stockout_risk_item (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  snapshot_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- ---- 库存侧（warehouse 7.1.1.3 的口径快照）----
  on_hand_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(on_hand_quantity >= 0),
  reserved_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(reserved_quantity >= 0),
  safety_buffer_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(safety_buffer_quantity >= 0),
  -- **有效可用量**：直接取 warehouse 7.1.1.3 的**可售量**（sellable），
  -- 本模块**不重新实现一遍** available − buffer。可售量是全系统的单一口径来源，
  -- 另算一遍必然与防超卖的那个数漂移。
  available_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(available_quantity >= 0),
  -- ---- 在途侧（warehouse 7.5.1.2 的口径快照）----
  -- 在途合计（未结算量全量，含没有 ETA 的）。
  in_transit_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(in_transit_quantity >= 0),
  -- **备货周期内可到达的在途量**：只有 ETA 落在 [评估日, 评估日+备货周期] 内的在途
  -- 才可能救这一次的断货；没有 ETA 的在途**不算**（它救不了确定的那一天）。
  in_transit_arriving_quantity numeric(19,6) NOT NULL DEFAULT 0
    CHECK(in_transit_arriving_quantity >= 0),
  in_transit_no_eta_quantity numeric(19,6) NOT NULL DEFAULT 0
    CHECK(in_transit_no_eta_quantity >= 0),
  -- ---- 销售速度与结论 ----
  -- **日均销量**：没有登记时是 NULL（「无数据」），**绝不写 0**。
  daily_sales_quantity numeric(19,6),
  -- **可支撑天数**：整数天（不是小数天）。日均销量缺失或为 0 时是 NULL。
  coverage_days int CHECK(coverage_days IS NULL OR coverage_days >= 0),
  -- **预计断货日**：评估日 + 可支撑天数。日均销量缺失或为 0 时是 NULL。
  expected_stockout_date date,
  -- **补货最早可到达日**：评估日 + 备货周期（库存自备的补货路径）。
  replenishment_arrival_date date NOT NULL,
  -- **缺口量**：备货周期内预期消耗 − 有效可用量 − 备货周期内可到达在途量。
  -- 算不出（无销售速度）时为 0 —— 0 在这里表示「不构成缺口」，
  -- 而不是「缺口是 0」；后者会与 urgency=NO_DATA 一起误导人。
  shortage_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(shortage_quantity >= 0),
  -- **紧急度**：CRITICAL / HIGH / MEDIUM / LOW / NO_DATA。
  urgency text NOT NULL CHECK(urgency IN ('CRITICAL','HIGH','MEDIUM','LOW','NO_DATA')),
  -- **排序依据**：分档与排序的原文。清单页直接显示这行就能复现判定，
  -- 不必点进详情再猜 —— 与 9.3.3.2 的 rule_evidence 同一纪律。
  urgency_basis text NOT NULL CHECK(btrim(urgency_basis)<>''),
  -- 涉及店铺数（快照列，与 shop 明细表在同一个事务里写入）。
  shop_count int NOT NULL DEFAULT 0 CHECK(shop_count >= 0),
  -- **排序位次**：清单按紧急度排序后的名次，落库后可回溯「当时排第几」。
  sort_rank int NOT NULL CHECK(sort_rank >= 0),
  note text NOT NULL DEFAULT '',
  UNIQUE(tenant_id,id),
  -- 同一份快照里一个 SKU 只有一行。
  UNIQUE(tenant_id,snapshot_id,sku_id),
  FOREIGN KEY(tenant_id,snapshot_id) REFERENCES stockout_risk_snapshot(tenant_id,id) ON DELETE CASCADE,
  -- 销售速度与结论必须自洽：没有日均销 → 没有可支撑天数、没有断货日；
  -- 有日均销且为正 → 两个都有；日均销登记为 0 → 可支撑天数与断货日都答不出来（不能除以 0）。
  CONSTRAINT stockout_item_velocity_consistent CHECK(
    (daily_sales_quantity IS NULL AND coverage_days IS NULL AND expected_stockout_date IS NULL)
    OR (daily_sales_quantity = 0 AND coverage_days IS NULL AND expected_stockout_date IS NULL)
    OR (daily_sales_quantity > 0 AND coverage_days IS NOT NULL AND expected_stockout_date IS NOT NULL)
  ),
  -- 紧急度与销售速度状态必须自洽：NO_DATA 只能出现在没有日均销的行上，
  -- 其余档位必须有日均销 —— 否则「无数据」这一档会盖住真实的断货。
  CONSTRAINT stockout_item_urgency_consistent CHECK(
    (urgency = 'NO_DATA' AND daily_sales_quantity IS NULL)
    OR (urgency <> 'NO_DATA' AND daily_sales_quantity IS NOT NULL)
  ),
  -- 备货周期内可到达的在途量不可能超过在途合计。
  CONSTRAINT stockout_item_in_transit_consistent
    CHECK(in_transit_arriving_quantity <= in_transit_quantity)
);

-- ===========================================================================
-- 4. 涉及店铺（一行 = 一个 SKU 涉及的一个店铺）
-- ===========================================================================
-- 蓝图输出句最后一项「涉及店铺」。一个 SKU 常常同时挂在几个店铺上，
-- 断货的影响面就是这几个店铺 —— 所以是**多行明细**而不是一个逗号串。
CREATE TABLE stockout_risk_item_shop (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, item_id uuid NOT NULL,
  -- **店铺编码**（channel 的 shop_code 快照，跨服务不建外键、不复制店铺主数据）。
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code)<>''),
  -- 该店铺下挂到本 SKU 的平台变体条数（映射条数）：影响面的量级。
  listing_count int NOT NULL DEFAULT 0 CHECK(listing_count >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一行里同一店铺只出现一次。
  UNIQUE(tenant_id,item_id,shop_code),
  FOREIGN KEY(tenant_id,item_id) REFERENCES stockout_risk_item(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 5. 计算过程拆解（一行 = 算式里的一步）
-- ===========================================================================
-- 「输出：断货风险清单」要能被复算，就不能只给结论。这张表逐行记下
-- 算式里的每一项（在手 / 预占 / 安全缓冲 / 有效可用量 / 在途 / 日均销 /
-- 备货周期消耗 / 缺口），界面点开详情就能看到「这个数是怎么来的」。
CREATE TABLE stockout_risk_item_deduction (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, item_id uuid NOT NULL,
  -- 步骤序号（从 1 起）：拆解的显示顺序。
  step_order int NOT NULL CHECK(step_order > 0),
  -- 步骤种类：ON_HAND / RESERVED / SAFETY_BUFFER / SELLABLE / IN_TRANSIT_TOTAL /
  -- IN_TRANSIT_ARRIVING / IN_TRANSIT_NO_ETA / DAILY_SALES / LEAD_TIME_CONSUMPTION /
  -- SHORTAGE。
  step_kind text NOT NULL CHECK(step_kind IN (
    'ON_HAND','RESERVED','SAFETY_BUFFER','SELLABLE','IN_TRANSIT_TOTAL',
    'IN_TRANSIT_ARRIVING','IN_TRANSIT_NO_ETA','DAILY_SALES',
    'LEAD_TIME_CONSUMPTION','SHORTAGE')),
  -- 该步骤的量。**可为空**：「日均销量」在无登记时就是空的（NULL），
  -- 而不是 0 —— 0 与「没数据」在断货风险里是两件事。
  quantity numeric(19,6),
  -- 该步骤的口径说明（公式原文 / 来源端点）。
  basis text NOT NULL DEFAULT '',
  -- 数据来源（如「warehouse 7.1.1.3 可用量」「warehouse 7.5.1.2 在途」）。
  source_ref text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,item_id,step_order),
  FOREIGN KEY(tenant_id,item_id) REFERENCES stockout_risk_item(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 6. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================
-- 6.1 销售速度登记的**统计口径冻结**：日均销量 / 统计窗口 / 生效起点 / 来源
--     一律不可改。唯一可动的是 effective_to，且**只能设定一次**。
--     理由：改这些等于追溯改写「当时是按哪个数算的断货日」。
CREATE FUNCTION guard_stockout_velocity_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.sku_id IS DISTINCT FROM OLD.sku_id
    OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
    OR NEW.sku_name IS DISTINCT FROM OLD.sku_name
    OR NEW.daily_sales_quantity IS DISTINCT FROM OLD.daily_sales_quantity
    OR NEW.window_start IS DISTINCT FROM OLD.window_start
    OR NEW.window_end IS DISTINCT FROM OLD.window_end
    OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
    OR NEW.source_kind IS DISTINCT FROM OLD.source_kind
    OR NEW.source_ref IS DISTINCT FROM OLD.source_ref THEN
  RAISE EXCEPTION '销售速度登记「%」的统计口径（SKU / 日均销量 / 统计窗口 / 生效起点 / 来源）不可改：它要回答「当时按哪个数算的断货日」。要改请登记新的一条并收口旧的一条', OLD.sku_code
   USING ERRCODE='23514';
 END IF;
 IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
  IF OLD.effective_to IS NOT NULL THEN
   RAISE EXCEPTION '销售速度登记「%」的失效日已经定过（%），不能再改', OLD.sku_code, OLD.effective_to
    USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '销售速度登记「%」的失效日只能设定一次，不能再置空', OLD.sku_code
    USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stockout_velocity_frozen BEFORE UPDATE ON stockout_sales_velocity
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_velocity_frozen();

-- 6.2 销售速度登记**只允许追加**：登记过的行不可删。
--     （它的 UPDATE 只用于收口失效日，且被 6.1 限死只动那一列。）
CREATE FUNCTION guard_stockout_velocity_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '销售速度登记是 append-only 的输入事实（%）：删掉它等于让「当时按哪个数算的断货日」永远查不回来', TG_OP
  USING ERRCODE='23514';
END $$;
CREATE TRIGGER stockout_velocity_append_only BEFORE DELETE ON stockout_sales_velocity
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_velocity_append_only();

-- 6.3 快照与拆解是**留痕**：不可改写。
--     快照是「那一刻算出来的那份结论」的凭据；拆解是复算的依据。
CREATE FUNCTION guard_stockout_snapshot_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是不可改写的留痕表（%）：风险快照与计算拆解是「那一刻算出来的结论」的举证凭据', TG_TABLE_NAME, TG_OP
  USING ERRCODE='23514';
END $$;
CREATE TRIGGER stockout_snapshot_append_only BEFORE UPDATE OR DELETE ON stockout_risk_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_snapshot_append_only();
CREATE TRIGGER stockout_item_append_only BEFORE UPDATE OR DELETE ON stockout_risk_item
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_snapshot_append_only();
CREATE TRIGGER stockout_item_shop_append_only BEFORE UPDATE OR DELETE ON stockout_risk_item_shop
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_snapshot_append_only();
CREATE TRIGGER stockout_item_deduction_append_only BEFORE UPDATE OR DELETE ON stockout_risk_item_deduction
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_snapshot_append_only();

-- 6.4 风险行与快照的租户必须一致（复合外键已覆盖），这里再钉一条：
--     风险行必须属于一份**已存在**的快照，且排序位次不得为负。
--     （快照行数与实际行数的一致性由服务层在同一事务里保证 —— 那是读时派生，
--       存一份计数就要同步一次，漏同步一次就会出现「清单说 3 条、点开只有 2 条」。）

-- ===========================================================================
-- 7. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 清单页：按快照 + 紧急度 + 位次读（清单页主查询形状）。
CREATE INDEX stockout_risk_item_by_snapshot ON stockout_risk_item(tenant_id, snapshot_id, urgency, sort_rank);
-- 清单页：按 SKU 过滤。
CREATE INDEX stockout_risk_item_by_sku ON stockout_risk_item(tenant_id, sku_id, snapshot_id);
-- 快照列表页：按计算时刻倒序（最近一次在前）。
CREATE INDEX stockout_risk_snapshot_by_time ON stockout_risk_snapshot(tenant_id, computed_at desc, id);
-- 销售速度：按 SKU + 生效期读（取评估日生效的那条）。
CREATE INDEX stockout_velocity_by_sku ON stockout_sales_velocity(tenant_id, sku_id, effective_from, effective_to);
-- 销售速度列表页：按生效期倒序。
CREATE INDEX stockout_velocity_by_effective ON stockout_sales_velocity(tenant_id, effective_from desc, id);
-- 店铺明细：按风险行读（详情页主查询形状）。
CREATE INDEX stockout_item_shop_by_item ON stockout_risk_item_shop(tenant_id, item_id, shop_code);
-- 拆解：按风险行读（详情页第二段）。
CREATE INDEX stockout_item_deduction_by_item ON stockout_risk_item_deduction(tenant_id, item_id, step_order);

-- ===========================================================================
-- 8. 多租户：RLS + 授权（与 trade V1 / V92 / V97 同一套写法）
-- ===========================================================================
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'stockout_sales_velocity','stockout_risk_snapshot','stockout_risk_item',
   'stockout_risk_item_shop','stockout_risk_item_deduction'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  stockout_sales_velocity,stockout_risk_snapshot,stockout_risk_item,
  stockout_risk_item_shop,stockout_risk_item_deduction TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  stockout_sales_velocity,stockout_risk_snapshot,stockout_risk_item,
  stockout_risk_item_shop,stockout_risk_item_deduction TO starcon_app;
-- 收回快照与拆解的改写权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON
  stockout_risk_snapshot,stockout_risk_item,
  stockout_risk_item_shop,stockout_risk_item_deduction FROM trade_app;
-- 销售速度允许 UPDATE —— 但**只有 effective_to 可动**（由 6.1 的触发器限死），
-- 那是「收口旧版本」的唯一合法动作。DELETE 一律收回。
REVOKE DELETE ON stockout_sales_velocity FROM trade_app;

-- ---------------------------------------------------------------------------
-- 9. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 的 trade_tenancy_probe、V92 的履约表、V97 的加工单表
--     都不碰，因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * 对 warehouse 的库存账与 channel 的映射表**只读逻辑引用，不加约束**：
--     跨库 FK 本来就禁止，所以本文件既没有约束到对方库的任何一张表，
--     warehouse / channel 的集成测试不需要为本车道重跑。
--   * 新增函数全部带 stockout_ 前缀，与 V1 / V25 / V92 / V97 的既有函数名不冲突。
