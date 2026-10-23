-- ===========================================================================
-- V353 · finance_deferred_settlement —— 12.3.1.3 延迟结算
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.3.1.3 延迟结算）原文：
--
--   职责：管理平台延迟结算（部分平台按周期后结算或滚动结算）：单列在途未结算金额、
--         结算条件、预计到账时间，支持查询「订单已发货但钱未到账」的明细。
--   输入：结算规则；订单与结算状态。
--   输出：延迟结算明细 + 在途资金统计 + 预计到账时间。
--   关系：上游 = 12.3.1.1、9 订单；下游 = 13 利润（按已结算/未结算区分口径）。
--
-- ---------------------------------------------------------------------------
-- 0. 本模块与 12.3.1.1 / 12.3.1.2 的分工（**不另起结算体系**）
-- ---------------------------------------------------------------------------
--
--   * **结算单 / 结算明细行 / 交易明细行**仍以 12.3.1.1（V345）与 12.3.1.2（V348）的
--     `finance_settlement` / `finance_settlement_line` 为准。本模块**不改那两张表的结构**，
--     也**不重复实现**「结算金额」「到账金额」「到账状态」——那些是 12.3.1.1 的职责。
--   * 本模块只承载 12.3.1.3 点名的**那一层**：
--       ① **在途未结算金额**（订单已发货、钱还没到账的那部分）；
--       ② **结算条件**（按什么规则结算：周期后结算 / 滚动结算 + 周期 + 到账偏移）；
--       ③ **预计到账时间**（由 ① 的规则 + 发货时间推出）；
--       ④ **「订单已发货但钱未到账」的明细**（订单级台账）。
--   * 「已结算口径」的钱从 12.3.1.1 的结算明细行**派生**出来（见第 4 节的派生链），
--     本模块**不维护第二份到账金额**。
--
-- ---------------------------------------------------------------------------
-- 1. 两套口径的定义（本模块对下游 13 利润的承诺，**写在数据层旁边**）
-- ---------------------------------------------------------------------------
--
--   下游 13 利润要求「按已结算 / 未结算区分口径」。本模块**同时**给出两套口径，
--   **分币种**给出，**绝不相加**（USD 与 EUR 加起来不是任何一个金额）：
--
--   ┌ 订单口径（ORDER_BASIS） ─────────────────────────────────────────────┐
--   │ 口径：平台订单总额（毛额，含税含运），来自 trade 的平台订单。       │
--   │ 含义：按「订单已发生」确认收入 —— 钱还没到账也算。                  │
--   └──────────────────────────────────────────────────────────────────────┘
--   ┌ 已结算口径（SETTLED_BASIS） ─────────────────────────────────────────┐
--   │ 口径：该订单在**已到账**结算单（status ∈ PARTIAL/SETTLED）里的        │
--   │       **销售额（SALES）行**合计。                                    │
--   │ 含义：按「钱已到账」确认收入 —— 只认结算单，且只认销售额行。          │
--   │ 为什么只认 SALES 行：佣金 / 退款 / FBA 费用 / 代扣税是平台**从货款里   │
--   │   扣走**的钱，不是「货款没到账」。把它们算进已结算口径会让在途未结算 │
--   │   金额虚高（明明已到账却还显示在途）。                               │
--   └──────────────────────────────────────────────────────────────────────┘
--
--   在途未结算金额（逐行）= max(订单口径 − 已结算口径, 0)
--     ⇒ 逐行有「订单口径 = 已结算口径 + 在途未结算」，只有当结算单按含税口径
--       记销售额（导致已结算口径 > 订单口径）时才截 0；这一格的差额在汇总里
--       以 `basis_gap`（口径基数差）**如实报出来**，不藏。
--
--   ⚠️ 两套口径的**基数不同**（毛额 vs 结算销售额行），差额里含税与运费等
--     尚未单独结算的部分。**本模块不替下游做口径取舍**，只把两套口径分币种同时给出。
--
-- ---------------------------------------------------------------------------
-- 2. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_deferred_settlement_rule  —— 结算规则（蓝图输入里的「结算规则」）
--   finance_deferred_settlement       —— 延迟结证明细（订单级台账）
--   finance_deferred_settlement_event —— 台账事件流（append-only 举证凭据）
--
--   三张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 3. 派生链（服务层写不进去的那些量）
-- ---------------------------------------------------------------------------
--
--   结算明细行 + 结算单状态（12.3.1.1 / 12.3.1.2 的表）
--        │  触发器 finance_deferred_settlement_line_refresh
--        │  触发器 finance_deferred_settlement_settlement_refresh
--        ▼
--   已结算口径金额 / 已到账结算单笔数 / 关联结算单
--        │  同一次 BEFORE 触发器
--        ▼
--   在途未结算金额（max(订单口径 − 已结算口径, 0)）
--
--   结算周期区间 + 预计到账时间（由「发货时间 + 结算模式 + 周期 + 到账偏移」派生）
--        │  BEFORE INSERT OR UPDATE 触发器 finance_deferred_settlement_derive
--        ▼
--   settlement_cycle_start / settlement_cycle_end / expected_arrived_at
--
--   ⇒ 「服务层手填一个与明细对不上的在途金额 / 预计到账时间」在**结构上**做不到：
--     绕过服务层直连 SQL 也一样会被触发器改写或拒掉。
--
-- ---------------------------------------------------------------------------
-- 4. 时间字段：timestamptz 一律用 OffsetDateTime 绑定
-- ---------------------------------------------------------------------------
--
--   `shipped_at`（发货时间）、`settlement_cycle_start` / `settlement_cycle_end`
--   （结算周期区间）、`expected_arrived_at`（预计到账时间）、`scanned_at`
--   全是 timestamptz。服务层一律 OffsetDateTime + `?::timestamptz` ——
--   用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s（东八区差 8 小时）。
--   这三个核心时间字段在本模块的集成测试里**断言写回的值**，不是只断言没抛异常。
--
-- ===========================================================================

-- ===========================================================================
-- 第一节：结算规则
-- ===========================================================================
--
-- 蓝图输入里的「结算规则」是**本模块自己的输入**（不是上游的）：
--   平台 + 店铺 → 结算模式（周期后结算 / 滚动结算）+ 结算周期 + 到账偏移。
--
-- ⚠️ 店铺主数据在 **channel 服务**（2.3.1.1），跨服务**不建 FK**，
--   只存逻辑 ID 与 code（AGENTS.md：禁止跨服务 SQL / FK / 共享视图）。
--
-- 一条规则要么是**店铺级**（shop_code 非空），要么是**平台级默认**（shop_code 为空），
-- 两者靠两个部分唯一索引分别约束（见第八节）：同一个店铺同一平台只能有一条生效的
-- 店铺级规则，同一平台只能有一条生效的平台级默认规则。
CREATE TABLE finance_deferred_settlement_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 平台编码（amazon / ebay / tiktok_shop …），与 trade / channel 的平台词表同源。
  platform_code varchar(32) NOT NULL CHECK(platform_code <> ''),
  -- 店铺 code（channel 服务 2.3.1.1；**不建跨库 FK**）。为空 = 平台级默认规则。
  shop_code varchar(64),
  -- 店铺的逻辑 ID（channel 服务的主键；**不建跨库 FK**）。
  shop_id uuid,
  -- 结算模式：
  --   PERIODIC 周期后结算 —— 订单按发货日各自进入一个结算周期，周期结束后统一打款；
  --   ROLLING  滚动结算   —— 平台按固定窗口滚动结算，订单落进它发货日之后的下一个窗口。
  settlement_mode text NOT NULL CHECK(settlement_mode IN ('PERIODIC','ROLLING')),
  -- 结算周期长度（天）：
  --   PERIODIC = 发货后多少天进下一个结算周期（1..365）；
  --   ROLLING  = 滚动窗口长度（1..365）。
  cycle_days int NOT NULL CHECK(cycle_days > 0 AND cycle_days <= 365),
  -- 周期结束后多少天到账（0 = 周期结束当天）。
  payout_lag_days int NOT NULL DEFAULT 0 CHECK(payout_lag_days >= 0 AND payout_lag_days <= 365),
  -- 本规则的结算币种（为空 = 不限，跟随订单币种）。
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  -- 结算条件（人话描述，随行快照到明细上，让明细能自解释「按什么条件结算」）。
  condition_note varchar(500) NOT NULL DEFAULT '',
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- ===========================================================================
-- 第二节：延迟结证明细（订单级台账）
-- ===========================================================================
--
-- 一行 = 一个平台订单的延迟结算台账行（「订单已发货但钱未到账」的明细）。
--
-- ⚠️ 订单在 **trade 服务**（9.1.2.1 平台订单 / 9.4.1.2 发货记录），
--   跨服务**不建 FK**、不建视图、不直连对方库 —— 只存逻辑 ID 与编号快照。
CREATE TABLE finance_deferred_settlement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 结算规则（本模块自己的表，可以建同库 FK）。
  rule_id uuid NOT NULL,
  -- 平台订单（trade 服务 9.1.2.1 的 platform_order.id；**不建跨库 FK**）。
  platform_order_id uuid NOT NULL,
  -- 我方订单（trade 的 sales_order.id；**不建跨库 FK**）。
  sales_order_id uuid,
  -- 两套订单编号都要留：平台结算单里的 `order_reference` 可能写任意一套，
  -- 「已结算口径」按两套编号都能匹配上（V348 明确 order_reference 不指向任何订单表）。
  order_no varchar(64) NOT NULL,
  external_order_no varchar(160) NOT NULL,
  platform_code varchar(32) NOT NULL CHECK(platform_code <> ''),
  shop_code varchar(64) NOT NULL DEFAULT '',
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- **订单口径金额**：平台订单总额（毛额）。
  order_amount numeric(19,4) NOT NULL,
  -- **已结算口径金额**：**派生**（见第三节），服务层写不进去。
  settled_amount numeric(19,4) NOT NULL DEFAULT 0,
  -- **在途未结算金额**：**派生** = max(订单口径 − 已结算口径, 0)。
  unsettled_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(unsettled_amount >= 0),
  -- **发货时间**（来自 trade 9.4.1.2 发货记录，timestamptz）。
  shipped_at timestamptz NOT NULL,
  -- 发货状态快照（SHIPPED / IN_TRANSIT / DELIVERED …）。
  shipment_status varchar(24) NOT NULL DEFAULT '',
  -- 规则快照（**改规则不改历史行**）：结算模式、周期长度、到账偏移。
  settlement_mode text NOT NULL CHECK(settlement_mode IN ('PERIODIC','ROLLING')),
  cycle_days int NOT NULL CHECK(cycle_days > 0 AND cycle_days <= 365),
  payout_lag_days int NOT NULL CHECK(payout_lag_days >= 0 AND payout_lag_days <= 365),
  -- **结算周期区间**（**派生**，timestamptz）。
  settlement_cycle_start timestamptz NOT NULL,
  settlement_cycle_end timestamptz NOT NULL,
  -- **预计到账时间**（**派生** = 周期结束 + 到账偏移，timestamptz）。
  expected_arrived_at timestamptz NOT NULL,
  -- 结算条件快照（人话，明细自解释）。
  condition_note varchar(500) NOT NULL DEFAULT '',
  -- 台账状态：
  --   PENDING 在途未结算（**钱未到账**：还没出现在任何已到账结算单里）
  --   SETTLED 已结算（**钱已到账**：已出现在某张已到账结算单里）
  --   VOID    已作废（终态，不进任何口径）
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SETTLED','VOID')),
  -- 覆盖该订单的已到账结算单（最近一张，可空）。
  settlement_id uuid,
  -- 已到账结算单覆盖笔数（**派生**，状态判据）。
  settled_settlement_count int NOT NULL DEFAULT 0 CHECK(settled_settlement_count >= 0),
  -- 来源：目前只有「从 trade 扫描入台账」这一条真实路径。
  source text NOT NULL DEFAULT 'TRADE_SCAN' CHECK(source IN ('TRADE_SCAN')),
  -- 扫描时刻（timestamptz）。
  scanned_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 幂等键：同一个平台订单在台账里只有一行（重复扫描只会更新，不会重复插入）。
  UNIQUE(tenant_id, platform_order_id),
  CHECK(settlement_cycle_end > settlement_cycle_start),
  CHECK(expected_arrived_at >= settlement_cycle_end),
  FOREIGN KEY(tenant_id, rule_id) REFERENCES finance_deferred_settlement_rule(tenant_id, id),
  -- 结算单在**同库同服务**（12.3.1.1）⇒ 可以建 FK。
  -- 结算单被删时只置空本列（PG15+ 的列级 SET NULL），不动 tenant_id。
  FOREIGN KEY(tenant_id, settlement_id) REFERENCES finance_settlement(tenant_id, id)
    ON DELETE SET NULL (settlement_id)
);

-- ===========================================================================
-- 第三节：台账事件流（append-only 举证凭据）
-- ===========================================================================
--
-- 与 finance_settlement_event 同一手法：事件流是「当时发生了什么」的唯一出处，
-- 运行角色的 UPDATE / DELETE 已收回 —— 事件只能追加。
CREATE TABLE finance_deferred_settlement_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  deferred_settlement_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN ('SCANNED','UPDATED','SETTLED','VOIDED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, deferred_settlement_id)
    REFERENCES finance_deferred_settlement(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第四节：派生链（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 结算周期区间
-- ---------------------------------------------------------------------------
--
-- ⚠️ **一律按 UTC 日历日**计算：周期与滚动窗口是**日历**概念，不是「某一瞬间」。
--   用 UTC 而不是本地时区，是为了让同一批订单在任何机器上算出同一个区间 ——
--   预计到账时间是给财务对账用的，不能因为服务器时区不同而漂 8 小时。
--
--   PERIODIC（周期后结算）：
--     周期开始 = 发货日 00:00:00Z
--     周期结束 = 发货日 + cycle_days 天 00:00:00Z
--
--   ROLLING（滚动结算）：
--     窗口按 1970-01-01 对齐，每 cycle_days 天滚动一次。
--     周期结束 = 发货日之后的**第一个**窗口边界（严格大于发货日；
--       发货日正好落在边界上时算进**下一个**窗口 —— 边界当天的货已经错过这一轮）。
--     周期开始 = 周期结束 − cycle_days 天。
CREATE OR REPLACE FUNCTION finance_deferred_settlement_cycle_window(
  p_shipped_at timestamptz,
  p_mode text,
  p_cycle_days int,
  OUT o_cycle_start timestamptz,
  OUT o_cycle_end timestamptz
) RETURNS record AS $$
DECLARE
  base_day date;
  epoch_day int;
  boundary date;
BEGIN
  IF p_shipped_at IS NULL THEN
    RAISE EXCEPTION '发货时间不能为空：预计到账时间是本模块的核心字段'
      USING ERRCODE = 'check_violation';
  END IF;
  IF p_cycle_days IS NULL OR p_cycle_days <= 0 THEN
    RAISE EXCEPTION '结算周期天数必须为正数' USING ERRCODE = 'check_violation';
  END IF;

  base_day := (p_shipped_at AT TIME ZONE 'UTC')::date;

  IF p_mode = 'ROLLING' THEN
    epoch_day := (base_day - DATE '1970-01-01')::int;
    boundary := DATE '1970-01-01' + (((epoch_day / p_cycle_days) + 1) * p_cycle_days)::int;
    o_cycle_end := boundary::timestamp AT TIME ZONE 'UTC';
    o_cycle_start := (boundary - p_cycle_days)::timestamp AT TIME ZONE 'UTC';
  ELSE
    o_cycle_start := base_day::timestamp AT TIME ZONE 'UTC';
    o_cycle_end := (base_day + p_cycle_days)::timestamp AT TIME ZONE 'UTC';
  END IF;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- (b) 已结算口径事实（从 12.3.1.1 / 12.3.1.2 的结算明细行派生）
-- ---------------------------------------------------------------------------
--
-- 只认**已到账**的结算单（status ∈ PARTIAL/SETTLED，V345 钉死了这两个状态必须写
-- arrived_at），且只认**销售额（SALES）行**。
--
-- ⚠️ 匹配键是 `finance_settlement_line.order_reference` 与本行的
--   `order_no` / `external_order_no` 的**文本相等**：
--   V348 明确「order_reference 不指向任何订单表，也没有跨库 FK」，
--   所以这里只能是尽力而为的文本匹配 —— 这是全仓一致的既有约定，不是本模块新造的松散。
CREATE OR REPLACE FUNCTION finance_deferred_settlement_settled_facts(
  p_tenant uuid,
  p_order_no text,
  p_external_order_no text,
  OUT o_amount numeric,
  OUT o_count bigint,
  OUT o_settlement_id uuid
) RETURNS record AS $$
DECLARE
  v_amount numeric;
  v_count bigint;
  v_settlement uuid;
BEGIN
  SELECT coalesce(sum(l.amount), 0),
         count(DISTINCT s.id),
         (array_agg(s.id ORDER BY s.created_at DESC, s.id))[1]
    INTO v_amount, v_count, v_settlement
    FROM finance_settlement_line l
    JOIN finance_settlement s
      ON s.tenant_id = l.tenant_id
     AND s.id = l.settlement_id
   WHERE l.tenant_id = p_tenant
     AND l.category = 'SALES'
     AND s.status IN ('PARTIAL','SETTLED')
     AND (l.order_reference = p_order_no OR l.order_reference = p_external_order_no);

  o_amount := coalesce(v_amount, 0);
  o_count := coalesce(v_count, 0);
  o_settlement_id := v_settlement;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- (c) BEFORE INSERT OR UPDATE：派生 + 守卫
-- ---------------------------------------------------------------------------
--
-- 这一个触发器同时做四件事：
--   ① 算出结算周期区间与预计到账时间（**无条件覆盖**服务层传进来的值）；
--   ② 算出已结算口径金额 / 已到账结算单笔数 / 关联结算单；
--   ③ 算出在途未结算金额 = max(订单口径 − 已结算口径, 0)；
--   ④ 状态判定：钱到账了没。
--
-- ⚠️ `pg_trigger_depth() < 2` 的含义：现在是**直接**写已结算口径金额（深度 1）就拒；
--   而由下面的刷新触发器写（深度 2）放行 —— 与 V345 的 guard 手法完全一致。
CREATE OR REPLACE FUNCTION trg_finance_deferred_settlement_derive() RETURNS trigger AS $$
DECLARE
  v_start timestamptz;
  v_end timestamptz;
  v_amount numeric;
  v_count bigint;
  v_settlement uuid;
  -- ⚠️ 必须**先**把调用方传进来的预计到账时间存下来：下面立刻就用派生值覆盖 NEW，
  --   到那时再比就永远相等，守卫会形同虚设。
  v_incoming_expected timestamptz;
BEGIN
  v_incoming_expected := NEW.expected_arrived_at;

  -- ① 结算周期区间 + 预计到账时间（派生，服务层写不进去）。
  SELECT * INTO v_start, v_end
    FROM finance_deferred_settlement_cycle_window(
      NEW.shipped_at, NEW.settlement_mode, NEW.cycle_days);
  NEW.settlement_cycle_start := v_start;
  NEW.settlement_cycle_end := v_end;
  NEW.expected_arrived_at := v_end + make_interval(days => NEW.payout_lag_days);

  -- ② 已结算口径事实（来自 12.3.1.1 / 12.3.1.2 的结算明细行）。
  SELECT * INTO v_amount, v_count, v_settlement
    FROM finance_deferred_settlement_settled_facts(
      NEW.tenant_id, NEW.order_no, NEW.external_order_no);

  -- 守卫：已结算口径金额只允许派生写入。
  IF TG_OP = 'INSERT' THEN
    IF NEW.settled_amount IS DISTINCT FROM 0
       AND NEW.settled_amount IS DISTINCT FROM coalesce(v_amount, 0) THEN
      RAISE EXCEPTION '已结算口径金额由结算明细行派生，不允许直接写入'
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.settled_amount IS DISTINCT FROM OLD.settled_amount
        AND pg_trigger_depth() < 2 THEN
    RAISE EXCEPTION '已结算口径金额由结算明细行派生，不允许直接写入'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 守卫：预计到账时间只允许派生写入。
  -- ⚠️ 这里**拒绝**而不是「静默改回」：手填一个对不上的预计到账时间如果被默默改掉，
  --   调用方会以为写成功了 —— 报错才说得清「这个字段归派生链管」。
  IF TG_OP = 'UPDATE'
     AND v_incoming_expected IS DISTINCT FROM OLD.expected_arrived_at
     AND pg_trigger_depth() < 2 THEN
    RAISE EXCEPTION '预计到账时间由结算周期派生（发货时间 + 结算周期 + 到账偏移），不允许直接写入'
      USING ERRCODE = 'check_violation';
  END IF;

  NEW.settled_amount := coalesce(v_amount, 0);
  NEW.settled_settlement_count := coalesce(v_count, 0)::int;
  NEW.settlement_id := v_settlement;

  -- ③ 在途未结算金额（派生）。
  NEW.unsettled_amount := greatest(NEW.order_amount - NEW.settled_amount, 0);

  -- ④ 状态：已出现在已到账结算单里 = 钱已到账。
  IF TG_OP = 'INSERT' THEN
    NEW.status := CASE WHEN NEW.settled_settlement_count > 0 THEN 'SETTLED' ELSE 'PENDING' END;
  ELSIF OLD.status = 'VOID' THEN
    IF NEW.status IS DISTINCT FROM 'VOID' THEN
      RAISE EXCEPTION '延迟结算台账行已作废（终态），不可恢复'
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.status = 'VOID' THEN
    -- 服务层作废：允许，且此后不可恢复（下一分支拦住）。
    NULL;
  ELSE
    NEW.status := CASE WHEN NEW.settled_settlement_count > 0 THEN 'SETTLED' ELSE 'PENDING' END;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_deferred_settlement_derive
  BEFORE INSERT OR UPDATE ON finance_deferred_settlement
  FOR EACH ROW EXECUTE FUNCTION trg_finance_deferred_settlement_derive();

-- ---------------------------------------------------------------------------
-- (d) 结算明细行变动 → 重算受影响订单的台账行
-- ---------------------------------------------------------------------------
--
-- 12.3.1.2 往 finance_settlement_line 追加一行销售额（订单已结算）时，
-- 对应的延迟结算台账行的「已结算口径金额」必须跟着变 ——
-- 否则「钱到账了没」这件事会一直停在「未到账」。
CREATE OR REPLACE FUNCTION trg_finance_deferred_settlement_line_refresh() RETURNS trigger AS $$
DECLARE
  v_tenant uuid;
  v_ref text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_tenant := OLD.tenant_id;
    v_ref := OLD.order_reference;
  ELSE
    v_tenant := NEW.tenant_id;
    v_ref := NEW.order_reference;
  END IF;
  IF v_ref IS NULL THEN
    RETURN NULL;
  END IF;

  UPDATE finance_deferred_settlement d
     SET updated_at = now()
   WHERE d.tenant_id = v_tenant
     AND (d.order_no = v_ref OR d.external_order_no = v_ref);

  -- UPDATE 时旧引用也要重算（订单号被改写的情况）。
  IF TG_OP = 'UPDATE' AND OLD.order_reference IS DISTINCT FROM NEW.order_reference
     AND OLD.order_reference IS NOT NULL THEN
    UPDATE finance_deferred_settlement d
       SET updated_at = now()
     WHERE d.tenant_id = OLD.tenant_id
       AND (d.order_no = OLD.order_reference OR d.external_order_no = OLD.order_reference);
  END IF;

  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_deferred_settlement_line_refresh
  AFTER INSERT OR UPDATE OR DELETE ON finance_settlement_line
  FOR EACH ROW EXECUTE FUNCTION trg_finance_deferred_settlement_line_refresh();

-- ---------------------------------------------------------------------------
-- (e) 结算单状态变动 → 重算（作废 / 被取代 / 登记到账都走这条路）
-- ---------------------------------------------------------------------------
--
-- ⚠️ 结算单被作废（VOID）或被新版本取代（SUPERSEDED）时，它带的明细行
--   **不会**跟着变（明细行是 append-only 的外部凭据），但「已到账」这件事变了 ——
--   所以必须显式重算受影响的台账行。
CREATE OR REPLACE FUNCTION trg_finance_deferred_settlement_settlement_refresh() RETURNS trigger AS $$
DECLARE
  r record;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NULL;
  END IF;
  FOR r IN
    SELECT DISTINCT l.order_reference
      FROM finance_settlement_line l
     WHERE l.tenant_id = NEW.tenant_id
       AND l.settlement_id = NEW.id
       AND l.order_reference IS NOT NULL
  LOOP
    UPDATE finance_deferred_settlement d
       SET updated_at = now()
     WHERE d.tenant_id = NEW.tenant_id
       AND (d.order_no = r.order_reference OR d.external_order_no = r.order_reference);
  END LOOP;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_deferred_settlement_settlement_refresh
  AFTER UPDATE OF status ON finance_settlement
  FOR EACH ROW EXECUTE FUNCTION trg_finance_deferred_settlement_settlement_refresh();

-- ===========================================================================
-- 第五节：索引
-- ===========================================================================
--
-- 结算规则：一个店铺一份生效规则（店铺级），一个平台一份生效的平台级默认规则。
-- 用**部分唯一索引**（WHERE status = 'ACTIVE'）而不是表级 UNIQUE：
--   停用一条规则后应当能再登记一条新的，旧的留痕不挡路。
CREATE UNIQUE INDEX finance_deferred_settlement_rule_shop_unique
  ON finance_deferred_settlement_rule(tenant_id, platform_code, shop_code)
  WHERE shop_code IS NOT NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX finance_deferred_settlement_rule_platform_unique
  ON finance_deferred_settlement_rule(tenant_id, platform_code)
  WHERE shop_code IS NULL AND status = 'ACTIVE';
-- 规则清单（按平台 / 店铺查）。
CREATE INDEX finance_deferred_settlement_rule_by_platform
  ON finance_deferred_settlement_rule(tenant_id, platform_code, shop_code);

-- 台账：默认排序 = 预计到账时间升序（最该催的排最前）+ id。
CREATE INDEX finance_deferred_settlement_by_status
  ON finance_deferred_settlement(tenant_id, status, expected_arrived_at, id);
-- 按币种查（口径汇总的取数路径）。
CREATE INDEX finance_deferred_settlement_by_currency
  ON finance_deferred_settlement(tenant_id, currency, status, expected_arrived_at, id);
-- 按店铺查（一家店一屏）。
CREATE INDEX finance_deferred_settlement_by_shop
  ON finance_deferred_settlement(tenant_id, platform_code, shop_code, expected_arrived_at, id);
-- 订单号反查（两套编号都要能查到：结算单按哪个号写的都能定位回来）。
CREATE INDEX finance_deferred_settlement_by_order
  ON finance_deferred_settlement(tenant_id, order_no);
CREATE INDEX finance_deferred_settlement_by_external_order
  ON finance_deferred_settlement(tenant_id, external_order_no);
-- 规则反查（一个规则下的所有明细）。
CREATE INDEX finance_deferred_settlement_by_rule
  ON finance_deferred_settlement(tenant_id, rule_id);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_deferred_settlement_event_by_settlement
  ON finance_deferred_settlement_event(tenant_id, deferred_settlement_id, occurred_at, id);

-- ===========================================================================
-- 第六节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_deferred_settlement_rule','finance_deferred_settlement','finance_deferred_settlement_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_deferred_settlement_rule, finance_deferred_settlement, finance_deferred_settlement_event TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_deferred_settlement_rule, finance_deferred_settlement, finance_deferred_settlement_event TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：事件流是**举证凭据**，只能追加。
REVOKE UPDATE,DELETE ON finance_deferred_settlement_event FROM finance_app;

-- ===========================================================================
-- 第七节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13 利润（未交付）**：本模块**不产出任何利润数字**，但它把下游要的两套口径
--     （订单口径 / 已结算口径）与在途未结算金额**分币种**算好了 ——
--     下游取数时不必自己重算一遍「哪些订单的钱还没到账」。
--     口径定义见本文件第 1 节（也写进了能力声明与会话日志）。
--
--   * **12.3.1.1 结算单（已交付）**：本模块**只读**它的结算明细行与结算单状态，
--     **不改** V345 / V348 的任何结构。接上动作全部在本模块这一侧。
--
--   * **12.3.2.1 对账任务（未交付）**：本模块**不做**自动匹配、不产出差异分类 ——
--     「订单金额与结算金额对不对得上」是 12.3.2.1 的职责。
--
--   * **9 订单（已交付）**：平台订单与发货记录经 trade 的**授权 HTTP API**只读，
--     跨服务**不建 FK / 视图 / 直连**（AGENTS.md 模块边界纪律）。
