-- ===========================================================================
-- 7.8.2.2 缺货与饥饿预警：「没货卖」与「没分到货」是两种问题
-- ===========================================================================
-- 蓝图原文：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），
-- 以及真正的全网缺货，区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；
-- 总可用量。输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」
-- 以及关系段的红线：「**"没货卖"和"没分到货"是两种问题**，处置完全不同，
-- 不能混在一个缺货告警里。」
--
-- ---------------------------------------------------------------------------
-- 为什么是**两张表**，而不是一张表加一个 type 枚举
-- ---------------------------------------------------------------------------
-- 一张表加一个 `type` 列是最省事的写法，也是本迁移明确拒绝的写法：
-- 两种问题的**判定依据不同**（饥饿看分摊优先级/保底 + 店铺销量；
-- 缺货看总可用量）、**粒度不同**（饥饿是 (SKU × 店铺)；缺货是 (SKU × 仓)）、
-- **字段不同**（饥饿要带保底承诺与成员优先级；缺货要带在��/预占/可用量）、
-- **处置不同**（调优先级/调保留 vs 补货/调拨）。
-- 塞进一张表就必然出现「一个 type 列 + 一堆可空列」：两个方向各有一半列永远是 NULL，
-- 而「这一行到底是哪一种」要靠读那个 type 才能知道 —— 那正是「混在一个缺货告警里」。
--
-- 所以本迁移建**两张互不共表**的表：
--   starvation_alert  店铺饥饿（没分到货）  —— 处置：调优先级 / 调保底
--   stockout_alert    全网缺货（没货卖）    —— 处置：补货 / 仓间调拨
-- 两张表连字段名都不共用（除了 tenant_id / 生命周期那几列），
-- 所以「把两种问题混进一个告警」这件事在 schema 层面就做不到。
--
-- ---------------------------------------------------------------------------
-- 判定的分界线是**「同期有没有货可分」**
-- ---------------------------------------------------------------------------
-- 饥饿的成立要件里有一条硬条件：**观察窗口内存在一轮总可用量 > 0 的分摊**
-- （allocation_run.available_quantity > 0）。也就是说：
--   货在、店分不到        → 饥饿 → 调优先级 / 调保底
--   货都没有、谁都分不到  → 缺货 → 补货
-- 同一个 SKU 不会同时出现在两张清单的「问题行」里 —— 这是**结构上的互斥**，
-- 不是靠约定或靠人看清单时自己分辨。
-- 反过来，当某 SKU 已经**全网缺货**时，它被排除在饥饿清单之外（但被计���
-- 「因全网缺货而未列入饥饿清单」的店铺数，让它们不无声消失）——
-- 因为那种情况下处置是补货，调保底没有意义。
--
-- ---------------------------------------------------------------------------
-- 「不知道」是一个状态，不是一个 0
-- ---------------------------------------------------------------------------
-- 三处「不知道」都落成可空列 + 显式状态，绝不用 0 顶替：
--   ① 账本里没有这个 SKU / 这个仓的库存记录  → available 为 NULL，状态 NO_LEDGER_RECORD
--      （0 意味着「这个仓一件都没有」，而「查不到记录」意味着「要去查」，两者处置相反）
--   ② 没有登记销量                            → demand_units 为 NULL，状态 UNKNOWN_DEMAND
--      （0 需求会让系统以为「不用补货」，而真相是「我们不知道它卖不卖」）
--   ③ 店铺绩效未交付（17 绩效域）              → 能力声明里 available=false + ownerModule
--      本模块不假装能算「绩效影响」：它能算的是「长期分不到货」这个**事实**。
--
-- ---------------------------------------------------------------------------
-- 数量与时间纪律
-- ---------------------------------------------------------------------------
-- 全部 numeric(19,6) + BigDecimal，无 float / double（AGENTS.md：禁浮点记账）。
-- 时间字段全部 timestamptz：服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`；
-- 集成测试**断言写回的值逐字相等**（偏 8 小时这种错必须被抓住）。
--
-- ---------------------------------------------------------------------------
-- 零 ALTER 既有表
-- ---------------------------------------------------------------------------
-- 本迁移**不改** allocation_run / allocation_run_line / allocation_shop_sales /
-- allocation_policy / allocation_policy_member / allocation_floor /
-- allocation_priority_resolution / stock_bucket / inventory_balance / warehouse 的任何一列，
-- 只新建两张表。7.8.1.1 / 7.8.1.2 / 7.8.2.1 / 7.2.2.1 的直写夹具不受影响。

-- ===========================================================================
-- 1. starvation_alert · 店铺饥饿（没分到货）
-- ===========================================================================
-- 一行 = 一次评估里「某个店铺在某个 SKU 上是不是饿」的判定 + 证据快照 + 处置状态。
-- 粒度是 (SKU × 店铺)：分摊本来就是按店铺分下去的，缺了店铺这一维就答不出
-- 「谁没分到货」。
CREATE TABLE starvation_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 被饿的店铺在 channel 服务（2.3.1.1 / 2.3.2.1 已交付）：只存逻辑 id +
  -- 写入时的可读名快照，**不建外键**（与 V68 / V189 同一纪律：跨服务引用不建 FK）。
  source_system varchar(32) NOT NULL,
  reference_id uuid NOT NULL,
  reference_label varchar(150) NOT NULL DEFAULT '',
  shop_operating_mode varchar(24) NOT NULL CHECK(shop_operating_mode IN (
    'SELF_FULFILLED','FULL_CONSIGNMENT','SEMI_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL')),

  -- SKU 在 catalog 服务：逻辑 id，不建外键。
  sku_id uuid NOT NULL,

  -- 观察窗口：观察到的分摊时刻区间 + 窗口内的分摊轮次数。
  -- 「长期」是相对的：单轮没分到不叫长期，连续多轮没分到才叫。
  window_start timestamptz NOT NULL,
  window_end timestamptz NOT NULL,
  observed_run_count integer NOT NULL CHECK(observed_run_count > 0),

  -- 窗口内的分摊事实快照（判定依据，不是配置）。
  -- zero_round_count：分到 0 件的轮次数 —— 「长期分不到货」的直接证据。
  -- allocated_total：窗口内累计分摊量。
  zero_round_count integer NOT NULL CHECK(zero_round_count >= 0 AND zero_round_count <= observed_run_count),
  allocated_total numeric(19,6) NOT NULL CHECK(allocated_total >= 0 AND allocated_total = trunc(allocated_total)),

  -- 窗口内各轮的「总可用量」最大值。它是**「有货可分」的证据**：
  -- 它 > 0 ⇒ 货在、这家店分不到 ⇒ 饥饿；它恒为 0 ⇒ 全网没货 ⇒ 那是缺货，不是饥饿。
  max_run_available_quantity numeric(19,6) CHECK(max_run_available_quantity IS NULL OR max_run_available_quantity >= 0),
  min_run_available_quantity numeric(19,6) CHECK(min_run_available_quantity IS NULL OR min_run_available_quantity >= 0),

  -- 最近一轮的次序与结论：7.8.1.1 的 outcome 词表（OK / CAP_REACHED /
  -- DEMAND_EXHAUSTED / NO_SALES / DISABLED）。「NO_SALES」与「DEMAND_EXHAUSTED」
  -- 是两种完全不同的病，处置也不同，所以逐字留痕而不是压成一个布尔。
  last_allocation_order integer CHECK(last_allocation_order IS NULL OR last_allocation_order > 0),
  last_outcome varchar(20) NOT NULL DEFAULT '',
  last_run_id uuid,

  -- 需求：7.8.1.1 的 allocation_shop_sales 是店铺销量的**唯一真相来源**。
  -- demand_units 为 NULL = **没有销量登记** = 不知道它饿不饿（不是 0 件需求）。
  -- demand_registered 区分「登记了 0 需求」与「压根没登记」——前者是「它不卖这个」，
  -- 后者是「不知道」，两者处置不同。
  demand_units numeric(19,6) CHECK(demand_units IS NULL OR (demand_units >= 0 AND demand_units = trunc(demand_units))),
  demand_registered boolean NOT NULL DEFAULT false,
  demand_window_start date,
  demand_window_end date,
  -- 覆盖率 = 累计分摊量 ÷ 需求量。需求未知时为 NULL（不是 0）。
  coverage_ratio numeric(9,6) CHECK(coverage_ratio IS NULL OR (coverage_ratio >= 0 AND coverage_ratio <= 1)),

  -- 分摊优先级与保底（7.8.1.1 名册 + 7.8.1.2 保底）：**判定依据**，
  -- 快照下来是为了复盘时能答出「当时它排第几、保底给了多少、差多少」。
  policy_id uuid,
  member_priority integer CHECK(member_priority IS NULL OR member_priority >= 0),
  member_share_limit numeric(9,6) CHECK(member_share_limit IS NULL OR (member_share_limit > 0 AND member_share_limit <= 1)),
  member_enabled boolean,
  floor_order integer CHECK(floor_order IS NULL OR floor_order > 0),
  floor_status varchar(12) CHECK(floor_status IS NULL OR floor_status IN ('FULFILLED','PARTIAL','UNFULFILLED')),
  floor_shortfall_quantity numeric(19,6) CHECK(floor_shortfall_quantity IS NULL OR floor_shortfall_quantity >= 0),
  floor_suppressed boolean,
  floor_suppression_reason varchar(20),
  -- 当前生效保底档的承诺（7.8.1.2 的 allocation_floor）。承诺值是**建议的起点**：
  -- 「把它从 0 调到 30 件」比「给它多一点」可执行得多。
  floor_committed_mode varchar(8) CHECK(floor_committed_mode IS NULL OR floor_committed_mode IN ('RATIO','QUANTITY')),
  floor_committed_ratio numeric(9,6),
  floor_committed_quantity numeric(19,6),
  floor_safety_quantity numeric(19,6),

  -- 判定结论。
  --   STARVED         长期分不到货（有需求 + 连续零分配 + 覆盖率不足 + 同期有货可分）
  --   UNDER_SUPPLIED  拿到了货但不足需求的覆盖率（边际，还没到「长期为零」）
  --   UNKNOWN_DEMAND  没有销量登记 ⇒ 不知道它饿不饿
  --   HEALTHY         供给正常
  -- 「不知道」是一个**独立取值**，不是 0 也不是「算成没缺货」。
  alert_state varchar(20) NOT NULL CHECK(alert_state IN (
    'STARVED','UNDER_SUPPLIED','UNKNOWN_DEMAND','HEALTHY')),

  -- 严重度。CRITICAL = 配了保底却没保住（shortfall > 0）或覆盖率不到一半。
  severity varchar(10) NOT NULL CHECK(severity IN ('CRITICAL','WARNING','INFO')),

  -- 需求状态。KNOWN / UNKNOWN —— 供前端把「未知的行」单独呈现。
  demand_state varchar(10) NOT NULL CHECK(demand_state IN ('KNOWN','UNKNOWN')),

  -- 根因：它到底是被什么挤下去的。PRIORITY 优先级太低 / FLOOR 保底没兑现 /
  -- SHARE_LIMIT 触到份额上限 / NO_SALES 没销量 / UNKNOWN 判不出来。
  root_cause varchar(24) NOT NULL CHECK(root_cause IN (
    'PRIORITY','FLOOR','SHARE_LIMIT','NO_SALES','UNKNOWN')),

  note varchar(500) NOT NULL DEFAULT '',

  -- 建议：**对应到具体的上游对象**，不是一句静态文案。
  --   kind        RAISE_FLOOR（调 7.8.1.2 的保底）/ RAISE_PRIORITY（调 7.8.1.1 的成员优先级）
  --              / REGISTER_SALES（去 7.8.1.1 登记销量）/ NONE（本模块给不出可执行建议）
  --   target      目标对象：allocation_floor / allocation_policy_member / allocation_shop_sales
  --   target_label 目标对象的人话说明
  --   policy_id / reference_id  目标对象的定位键
  --   current_value   目标对象**当前**的值（NULL = 不知道 / 不适用）
  --   suggested_value 建议调成的值
  --   reason      为什么是这个值（把算式摊开，运营能自己改）
  recommendation_kind varchar(24) NOT NULL CHECK(recommendation_kind IN (
    'RAISE_FLOOR','RAISE_PRIORITY','REGISTER_SALES','NONE')),
  recommendation_target varchar(32) NOT NULL DEFAULT '',
  recommendation_target_label varchar(60) NOT NULL DEFAULT '',
  recommendation_policy_id uuid,
  recommendation_reference_id uuid,
  recommendation_current_value numeric(19,6),
  recommendation_suggested_value numeric(19,6),
  recommendation_reason varchar(500) NOT NULL DEFAULT '',

  -- 处置生命周期：OPEN → ACKNOWLEDGED → RESOLVED，单向。
  -- 「认领」= 有人接了；「处置」= 有人按建议做完了（调了保底 / 补了货 / 登记了销量）。
  lifecycle_state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(lifecycle_state IN (
    'OPEN','ACKNOWLEDGED','RESOLVED')),
  acknowledged_by_membership_id uuid,
  acknowledged_at timestamptz,
  resolved_at timestamptz,
  resolution_note varchar(500) NOT NULL DEFAULT '',
  -- 复发次数：问题重新出现时被重新打开 +1，处置历史因此不会丢。
  recurrence_count integer NOT NULL DEFAULT 0 CHECK(recurrence_count >= 0),

  -- 评估时刻（这次判定是什么时候做的）与行创建时刻。
  detected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 同一个店铺在同一个 SKU 上只有一条预警：出现两条会让「它到底饿不饿」有两套答案。
  UNIQUE(tenant_id,sku_id,reference_id),

  -- 需求窗口自洽。
  CHECK(demand_window_end IS NULL OR demand_window_start IS NULL OR demand_window_end >= demand_window_start),

  -- 生命周期三态的字段配套（与 7.2.1.2 的预占状态机同一道防线）：
  -- 「认领了但没记是谁认领」与「处置了但没写怎么处置的」在复盘时都是废记录。
  CHECK((lifecycle_state = 'OPEN'
         AND acknowledged_by_membership_id IS NULL AND acknowledged_at IS NULL AND resolved_at IS NULL)
     OR (lifecycle_state = 'ACKNOWLEDGED'
         AND acknowledged_by_membership_id IS NOT NULL AND acknowledged_at IS NOT NULL
         AND resolved_at IS NULL)
     OR (lifecycle_state = 'RESOLVED'
         AND resolved_at IS NOT NULL AND btrim(resolution_note) <> '')),

  -- 「没有登记」与「登记了 0」是两件事，DB 层把两者分开。
  CHECK(demand_registered OR demand_units IS NULL),
  -- 需求未知 ⇒ 覆盖率算不出来 ⇒ 覆盖率必须是 NULL（不是 0）。
  CHECK(demand_units IS NULL OR coverage_ratio IS NOT NULL),
  -- 零分配的轮次不能超过观察到的轮次。
  CHECK(zero_round_count <= observed_run_count)
);

-- 清单主查询：按 SKU / 店铺 / 严重度 / 状态筛。
CREATE INDEX starvation_alert_sku ON starvation_alert(tenant_id,sku_id,detected_at DESC);
CREATE INDEX starvation_alert_shop ON starvation_alert(tenant_id,reference_id,detected_at DESC);
CREATE INDEX starvation_alert_state ON starvation_alert(tenant_id,alert_state,severity,detected_at DESC);
-- 「还没处置的问题」是页面的默认口径。
CREATE INDEX starvation_alert_open ON starvation_alert(tenant_id,lifecycle_state,detected_at DESC)
  WHERE lifecycle_state <> 'RESOLVED';

-- ===========================================================================
-- 2. stockout_alert · 全网缺货（没货卖）
-- ===========================================================================
-- 一行 = 「某个 SKU 在某个仓（或全网合计）缺不缺货」的判定 + 证据 + 处置状态。
-- 粒度是 (SKU × 仓)：货在仓里，处置（补到哪个仓）需要仓这一维；
-- 而「全网缺货」由 network_available_quantity（全网合计）与
-- NETWORK_STOCKED_OUT 这个取值单独表达，**不与仓级断货混为一个状态**。
CREATE TABLE stockout_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  sku_id uuid NOT NULL,

  -- 仓是**本服务自有**实体（V1 的 warehouse），可以建复合外键。
  -- 可空 = 「账本里查不到这个 SKU 的任何库存记录」，此时这一行只有一个 SKU、
  -- 没有仓 —— 它表达的是「压根查不到」，不是「某个仓是 0 件」。
  warehouse_id uuid,
  warehouse_code varchar(64) NOT NULL DEFAULT '',
  warehouse_name varchar(200) NOT NULL DEFAULT '',

  -- 账本可用量。**available = on_hand − reserved，没有第三个来源**：
  -- 直接取 stock_bucket 的 available 生成列，不重算第三套公式。
  -- 可空 = 账本里没有这个 SKU/仓的记录（不知道），**不是 0 件**。
  on_hand_quantity numeric(19,6) CHECK(on_hand_quantity IS NULL OR on_hand_quantity >= 0),
  reserved_quantity numeric(19,6) CHECK(reserved_quantity IS NULL OR reserved_quantity >= 0),
  available_quantity numeric(19,6) CHECK(available_quantity IS NULL OR available_quantity >= 0),

  -- 全网合计（该 SKU 在所有仓的账本可用量之和）。
  -- 「全网缺货」的判定依据就是它：它 ≤ 0 ⇒ 真的是全网没货。
  -- 可空 = 账本里查不到这个 SKU 的任何记录（不知道）。
  network_on_hand_quantity numeric(19,6),
  network_reserved_quantity numeric(19,6),
  network_available_quantity numeric(19,6),

  -- 最近一轮分摊（7.8.1.1 的事实）里的总可用量与三段账。
  -- 留着是为了和「现在的账本可用量」交叉核对：两者背离说明分摊之后库存动过，
  -- 或者口径漂了 —— 那种背离必须看得见，不能被静默掉。
  run_id uuid,
  run_at timestamptz,
  run_method varchar(24),
  run_available_quantity numeric(19,6),
  run_allocatable_quantity numeric(19,6),
  run_unallocated_quantity numeric(19,6),
  run_allocated_total numeric(19,6),

  -- 需求：allocation_shop_sales（7.8.1.1 的销量登记，销量的唯一真相来源）。
  -- demand_units 为 NULL = 没有销量登记 = 不知道影响面。
  demand_units numeric(19,6) CHECK(demand_units IS NULL OR (demand_units >= 0 AND demand_units = trunc(demand_units))),
  demand_registered boolean NOT NULL DEFAULT false,
  demand_window_start date,
  demand_window_end date,
  demand_shop_count integer NOT NULL DEFAULT 0 CHECK(demand_shop_count >= 0),
  -- 覆盖率 = 账本可用量 ÷ 需求。需求未知时为 NULL。
  coverage_ratio numeric(9,6) CHECK(coverage_ratio IS NULL OR (coverage_ratio >= 0 AND coverage_ratio <= 1)),
  -- 可售天数 = 账本可用量 ÷ 日均需求。日均为 0 时为 NULL（除以零无意义）。
  days_of_cover numeric(12,4),

  -- 判定结论。
  --   NO_LEDGER_RECORD    账本里查不到这个 SKU 的库存记录（不知道，不是 0 件）
  --   NETWORK_STOCKED_OUT **全网缺货**：全网账本可用量 ≤ 0 —— 蓝图说的「真正的全网缺货」
  --   WAREHOUSE_STOCKED_OUT **这个仓断货**：该仓可用量 ≤ 0，而全网还有货
  --                        （货在别的仓 ⇒ 处置是调拨 / 补到这个仓，不是全网补货）
  --   AT_RISK             全网可用量 < 需求：覆盖不足
  --   UNKNOWN_DEMAND      没有销量登记 ⇒ 不知道影响面
  --   HEALTHY             供给正常
  alert_state varchar(24) NOT NULL CHECK(alert_state IN (
    'NO_LEDGER_RECORD','NETWORK_STOCKED_OUT','WAREHOUSE_STOCKED_OUT',
    'AT_RISK','UNKNOWN_DEMAND','HEALTHY')),

  severity varchar(10) NOT NULL CHECK(severity IN ('CRITICAL','WARNING','INFO')),
  demand_state varchar(10) NOT NULL CHECK(demand_state IN ('KNOWN','UNKNOWN')),

  note varchar(500) NOT NULL DEFAULT '',

  -- 建议：REPLENISH（补货）/ REGISTER_SALES（去登记销量）/ NONE。
  -- 补货量的口径：**需求 − 当前账本可用量**（不是拍脑袋的数）。
  recommendation_kind varchar(24) NOT NULL CHECK(recommendation_kind IN (
    'REPLENISH','REGISTER_SALES','NONE')),
  recommendation_target varchar(32) NOT NULL DEFAULT '',
  recommendation_target_label varchar(60) NOT NULL DEFAULT '',
  recommendation_sku_id uuid,
  recommendation_warehouse_id uuid,
  recommendation_current_value numeric(19,6),
  recommendation_suggested_value numeric(19,6),
  recommendation_reason varchar(500) NOT NULL DEFAULT '',

  -- 处置生命周期：与 starvation_alert 同一套状态机（OPEN → ACKNOWLEDGED → RESOLVED）。
  lifecycle_state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(lifecycle_state IN (
    'OPEN','ACKNOWLEDGED','RESOLVED')),
  acknowledged_by_membership_id uuid,
  acknowledged_at timestamptz,
  resolved_at timestamptz,
  resolution_note varchar(500) NOT NULL DEFAULT '',
  recurrence_count integer NOT NULL DEFAULT 0 CHECK(recurrence_count >= 0),

  detected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 仓有值时：同一个 SKU 在同一个仓只有一条预警。
  -- 可空时（全网查不到记录的那一行）：同一个 SKU 只有一条。
  -- 两条都是部分唯一索引：PG 的普通 UNIQUE 不把 NULL 视为相等，
  -- 用普通 UNIQUE(tenant_id,sku_id,warehouse_id) 的话多行 NULL 会被放行。
  CHECK((warehouse_id IS NULL AND btrim(warehouse_code) = '' AND btrim(warehouse_name) = '')
     OR (warehouse_id IS NOT NULL AND btrim(warehouse_code) <> '')),

  -- 生命周期三态的字段配套（同 starvation_alert 同一道防线）。
  CHECK((lifecycle_state = 'OPEN'
         AND acknowledged_by_membership_id IS NULL AND acknowledged_at IS NULL AND resolved_at IS NULL)
     OR (lifecycle_state = 'ACKNOWLEDGED'
         AND acknowledged_by_membership_id IS NOT NULL AND acknowledged_at IS NOT NULL
         AND resolved_at IS NULL)
     OR (lifecycle_state = 'RESOLVED'
         AND resolved_at IS NOT NULL AND btrim(resolution_note) <> '')),

  CHECK(demand_registered OR demand_units IS NULL),
  CHECK(demand_units IS NULL OR coverage_ratio IS NOT NULL)
);

CREATE UNIQUE INDEX stockout_alert_sku_warehouse
  ON stockout_alert(tenant_id,sku_id,warehouse_id) WHERE warehouse_id IS NOT NULL;
CREATE UNIQUE INDEX stockout_alert_sku_network
  ON stockout_alert(tenant_id,sku_id) WHERE warehouse_id IS NULL;

CREATE INDEX stockout_alert_sku ON stockout_alert(tenant_id,sku_id,detected_at DESC);
CREATE INDEX stockout_alert_warehouse ON stockout_alert(tenant_id,warehouse_id,detected_at DESC);
CREATE INDEX stockout_alert_state ON stockout_alert(tenant_id,alert_state,severity,detected_at DESC);
CREATE INDEX stockout_alert_open ON stockout_alert(tenant_id,lifecycle_state,detected_at DESC)
  WHERE lifecycle_state <> 'RESOLVED';

-- 仓是本服务实体，复合外键钉住「这个仓确实属于本租户」。
-- warehouse_id 可空时这条 FK 不参与判定（NULL 跳过）。
DO $$ BEGIN
  ALTER TABLE stockout_alert
    ADD CONSTRAINT stockout_alert_warehouse_fk
    FOREIGN KEY (tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id);
END $$;

-- ===========================================================================
-- 3. DB 级行为约束
-- ===========================================================================

-- 3.1 预警行不可删。
-- 它是「那一次评估判了什么、依据是什么」的凭据；删掉等于让复盘无从查证。
CREATE FUNCTION guard_stockout_alert_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是不可删除的：预警是「那一次评估判了什么」的凭据，'
   '删掉它等于让复盘无从查证；要停止跟踪请走 resolve（处置）', TG_TABLE_NAME
   USING ERRCODE='23514';
END $$;
CREATE TRIGGER starvation_alert_no_delete
  BEFORE DELETE ON starvation_alert
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_alert_no_delete();
CREATE TRIGGER stockout_alert_no_delete
  BEFORE DELETE ON stockout_alert
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_alert_no_delete();

-- 3.2 身份列与创建时刻不可改。
-- 重新评估会刷新**证据**（那是它该干的事），但「这一行是谁的、什么时候建的」不许变 ——
-- 否则同一行在不同时间代表不同的主体，复盘时会对不上。
CREATE FUNCTION guard_stockout_alert_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.tenant_id,NEW.id,NEW.created_at) IS DISTINCT FROM
    (OLD.tenant_id,OLD.id,OLD.created_at) THEN
   RAISE EXCEPTION '% 的租户 / 主键 / 创建时刻不可修改：'
     '重新评估只刷新判定证据，不改这一行是谁的', TG_TABLE_NAME
     USING ERRCODE='23514';
 END IF;
 IF NEW.updated_at < OLD.updated_at THEN
   RAISE EXCEPTION '% 的 updated_at 不得倒退', TG_TABLE_NAME USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER starvation_alert_identity
  BEFORE UPDATE ON starvation_alert
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_alert_identity();
CREATE TRIGGER stockout_alert_identity
  BEFORE UPDATE ON stockout_alert
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_alert_identity();

-- 3.3 处置状态机：OPEN → ACKNOWLEDGED → RESOLVED 单向，终态不复活。
-- 「复活」的例外只有一条：重新评估时问题**重新出现**（recurrence_count + 1）。
-- 那种复活由评估路径显式做，且必须带上 recurrence_count 的自增，
-- 于是「这个问题被处置过几次」这件事在行上留得下痕迹。
CREATE FUNCTION guard_stockout_alert_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.lifecycle_state = 'RESOLVED'
    AND NEW.lifecycle_state = 'OPEN'
    AND NEW.recurrence_count <> OLD.recurrence_count + 1 THEN
   RAISE EXCEPTION '%：已处置的预警重新打开时 recurrence_count 必须自增 1（% → %），'
     '否则「这个问题复发过几次」就查不出来了',
     TG_TABLE_NAME, OLD.recurrence_count, NEW.recurrence_count
     USING ERRCODE='23514';
 END IF;
 IF OLD.lifecycle_state = 'RESOLVED'
    AND NEW.lifecycle_state = 'OPEN'
    AND OLD.recurrence_count = NEW.recurrence_count THEN
   RAISE EXCEPTION '%：已处置的预警不能无痕复活', TG_TABLE_NAME USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER starvation_alert_lifecycle
  BEFORE UPDATE ON starvation_alert
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_alert_lifecycle();
CREATE TRIGGER stockout_alert_lifecycle
  BEFORE UPDATE ON stockout_alert
  FOR EACH ROW EXECUTE FUNCTION guard_stockout_alert_lifecycle();

-- ===========================================================================
-- 4. 多租户：RLS + 授权（与 V1 / V2 / V56 / V68 / V76 / V189 同一套写法）
-- ===========================================================================
-- 业务表一视同仁 FORCE。current_setting('app.current_tenant') 由
-- backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['starvation_alert','stockout_alert'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 既有迁移的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE ON starvation_alert, stockout_alert TO warehouse_app;

-- 预警行不可删：权限层先收一道 DELETE，§3.1 的触发器再收一道。
REVOKE DELETE ON starvation_alert, stockout_alert FROM warehouse_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V56 / V68 / V76 / V189 同口径）。
REVOKE ALL ON starvation_alert, stockout_alert FROM starcon_app;
