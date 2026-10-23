-- ===========================================================================
-- V345 · finance_settlement —— 12.3.1.1 结算单管理
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.3.1.1 结算单管理）原文：
--
--   职责：管理平台结算单：结算周期、店铺、平台账单汇总（销售额、退款、佣金、FBA 费用、
--         广告费、赔付、其他调整）、结算金额、到账状态。
--   输入：平台结算报告同步（2 同步）；人工上传账单；结算周期配置。
--   输出：结算单 + 汇总项 + 明细行 + 与我方账的差额。
--   关系：上游 = 2 渠道与店铺；下游 = 12.3.2.1 对账、12.2.1.1 收款、13 利润。
--         **平台账单是财务事实的重要外部来源，需保留原始文件与解析版本。**
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么（上游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **「平台结算报告同步（2 同步）」端点不存在** ⇒ 自动同步**当前不可用**。
--     全仓 grep 过：`backend/services/channel` 里**没有任何**结算报告 / 结算周期端点
--     （`SyncController` 只有同步游标 / 同步批次 / 失败重放这类**通用同步设施**，
--     它不产出平台结算账单）。所以：
--       · DB 词表**保留** `PLATFORM_SYNC`（上游交付后还要能用），
--         但服务层拒绝用它建单，错误消息写明归属模块 **卷 02 同步**；
--       · 能力声明里如实写「平台结算报告自动同步不可用，归属模块 卷 02 同步」。
--     **绝不造一份「同步来的」假账单顶替它。**
--
--   * **12.3.2.1 对账任务未交付** ⇒ 本模块**只算差额**（平台账单项 vs 我方应收），
--     **不实现**自动对账任务、不产出匹配结果、不产出差异分类。
--     差额是**实时算出来的**（见 finance_settlement_difference 端点），不是存下来的快照。
--
--   * **13 利润 / 分析不可用**（reporting 服务不在本仓，编排闸已把它列进 serviceBlocked）
--     ⇒ 本模块**不产出任何利润数字**，能力声明里如实报出归属模块 13。
--
--   * **12.2.1.1 收款单已交付，但它的 `settlement_no` 列由 CHECK 钉死恒为 NULL**
--     （V341 原文：「结算单号：12.3.1.1 结算单管理未交付 ⇒ 由 CHECK 钉死恒为 NULL」）。
--     ⇒ 本模块**不去改 V341**（它已进过集成头并被应用过，改内容会让 Flyway checksum 校验炸掉），
--     也**不去 ALTER 上游的表**。接上动作由 12.2.1.1 自己的车道做（DROP 那条 CHECK）。
--     本模块只提供**一条可被上游引用的读路径**：
--     「与收款侧的差额」按 `finance_receipt.settlement_no = 结算单号` 关联 ——
--     现在查出来恒为 0 行（因为那一列恒为 NULL），等上游放开后**自动**就有数据了。
--
--   * **12.1.1.1 应收单的 `PLATFORM_SETTLEMENT` 来源当前被服务层拒绝**
--     （V338 / V339 的政策词表里有这个值，但建单被拒，相关字段恒为 null）。
--     ⇒ 本模块**不去改 V338 / V339**，接上动作留给 12.1.1.1 的车道。
--     本模块照样**真的算差额**：拿平台结算金额去比**我方应收**（同店铺、同周期、同币种），
--     我方应收侧按来源分组报出来，让人看得见差额到底出在哪个来源上。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_settlement_cycle        —— 结算周期配置（店铺 → 周期类型 + 到账滞后天数）
--   finance_settlement_source_file  —— 原始文件与**解析版本**记录（原文留存 + 版本号）
--   finance_settlement              —— 结算单头（一个结算周期 × 一个店铺 = 一张单）
--   finance_settlement_line         —— 结算明细行（平台账单的一行一笔）
--   finance_settlement_summary      —— 结算汇总项（**由明细行派生**，运行角色写不进去）
--   finance_settlement_event        —— 单据事件流（append-only 举证凭据）
--
--   六张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「结算金额必须由明细项求和得出」怎么在 DB 层钉死
-- ---------------------------------------------------------------------------
--
--   蓝图写死的是「结算金额必须由明细项求和得出，不许手填一个与明细对不上的总额」。
--   落到本仓的做法是**三段派生链**，每一段都只有**一个**写入口：
--
--     结算明细行（唯一写入口）
--        │  触发器 finance_settlement_refresh_totals（语句级，一次导入算一次）
--        ▼
--     结算汇总项（按类别聚合；服务层**写不进去** —— 见 guard_finance_settlement_summary_derived）
--        │  同一个触发器
--        ▼
--     结算金额（单头上的普通列；服务层**写不进去** —— 见 guard_finance_settlement_amount_derived）
--
--   ⇒ 「手填一个与明细对不上的总额」在**结构上**做不到：
--     服务层根本没有写这两个地方的入口，绕过服务层直连 SQL 也一样会被触发器拒掉。
--
--   ⚠️ 为什么结算金额是**普通列**而不是生成列（GENERATED ALWAYS AS）：
--     Postgres 的生成列**只能引用同一行的其它列**，不能跨表聚合 ——
--     而结算金额的来源（汇总项）在**子表**里。所以这里用触发器写普通列，
--     并用 BEFORE UPDATE 守卫把「直接写结算金额」这条后门钉死。
--
-- ---------------------------------------------------------------------------
-- 3. 金额的符号约定（跨境结算单最容易算错的一格）
-- ---------------------------------------------------------------------------
--
--   平台账单里「退款 100」既可能写成 +100，也可能写成 −100，各平台不一样。
--   本模块**固定一套符号约定**，并在**解析阶段**归一：
--
--     销售额 SALES        →  +（收入）
--     退款   REFUND       →  −（支出）
--     佣金   COMMISSION   →  −（支出）
--     FBA 费用 FBA_FEE    →  −（支出）
--     广告费 ADVERTISING  →  −（支出）
--     赔付   COMPENSATION →  +（收入）
--     其他调整 ADJUSTMENT →  按账单原样（可正可负）
--
--   ⇒ 汇总项里的金额是**对结算金额的贡献**（正=增加、负=减少），
--     所以「各汇总项之和 == 结算金额」恒成立，而且这条等式就是派生链本身。
--   ⇒ 明细行同时留 `raw_amount`（账单里写的那个数）与 `amount`（归一后的贡献），
--     两个数都在库里，**原始文件内容**也在库里（finance_settlement_source_file.raw_content）。
--
-- ---------------------------------------------------------------------------
-- 4. 时间字段：timestamptz 一律用 OffsetDateTime 绑定
-- ---------------------------------------------------------------------------
--
--   `expected_at`（预计到账）、`arrived_at`（实际到账）、`parsed_at`、`created_at`、
--   `updated_at`、`occurred_at` 全是 timestamptz。
--   服务层一律 OffsetDateTime + `?::timestamptz` ——
--   用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s（东八区差 8 小时）。
--   结算周期与到账状态的时间判定是本模块的核心。
--

-- ===========================================================================
-- 第一节：结算周期配置
-- ===========================================================================
--
-- 蓝图输入里写着「结算周期配置」—— 它是**本模块自己的输入**，不是上游的。
-- 一个店铺一份生效配置：周期类型 + 到账滞后天数 ⇒ 预计到账时间 = 周期结束日 + 滞后天数。
--
-- ⚠️ 店铺主数据在 **channel 服务**（2.3.1.1），跨服务**不建 FK**，
--   只存逻辑 ID（AGENTS.md：禁止跨服务 SQL / FK / 共享视图）。
CREATE TABLE finance_settlement_cycle (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 店铺（channel 服务 2.3.1.1 的逻辑 ID；**不建跨库 FK**）。
  shop_id uuid NOT NULL,
  shop_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  -- 周期类型：周结 / 双周结 / 月结。
  cycle_type text NOT NULL
    CHECK(cycle_type IN ('WEEKLY','BIWEEKLY','MONTHLY')),
  -- 周期结束后多少天到账（0 = 周期结束当天）。
  -- 预计到账时间 = 周期结束日 + payout_lag_days（该日 00:00:00Z）。
  payout_lag_days int NOT NULL DEFAULT 0
    CHECK(payout_lag_days >= 0 AND payout_lag_days <= 365),
  -- 本店铺结算的默认币种（可空 = 不限）。
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个店铺一份配置（改配置就是改配置，不是新增一份）。
  UNIQUE(tenant_id, shop_id)
);

-- ===========================================================================
-- 第二节：原始文件与解析版本
-- ===========================================================================
--
-- 蓝图加粗句：「**平台账单是财务事实的重要外部来源，需保留原始文件与解析版本。**」
--
-- 一行 = **一次解析**：原始文件内容 + 解析版本号 + 解析结果 + 告警。
-- 同一份账单（file_key）重解析时**版本号递增**（UNIQUE(tenant_id, file_key, parse_version)），
-- 结算单通过 source_file_id 指向**具体哪一次解析** ⇒ 结算单可追溯到
-- 「哪个文件的哪个版本的哪一次解析」。
--
-- ⚠️ 结算单**不**反向引用本表（避免两张表互相 FK 成环），
--   「这个文件版本产出了哪张结算单」靠 `finance_settlement.source_file_id` 反查。
CREATE TABLE finance_settlement_source_file (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 账单的身份（平台结算报告号 / 上传文件名）：**同一份账单**的判据。
  file_key varchar(160) NOT NULL,
  file_name varchar(200) NOT NULL DEFAULT '',
  content_type text NOT NULL CHECK(content_type IN ('CSV','JSON')),
  -- **原始文件内容**（原文，一字不改）。这是「财务事实的重要外部来源」的凭据。
  raw_content text NOT NULL,
  -- 内容指纹（sha256 十六进制）：用来判断「这次上传的内容和上次是否一样」。
  content_hash varchar(64) NOT NULL,
  byte_size bigint NOT NULL DEFAULT 0 CHECK(byte_size >= 0),
  -- **解析版本号**：同一 file_key 每重新解析一次 +1。
  parse_version int NOT NULL CHECK(parse_version >= 1),
  -- PARSED = 解析成功并已落库；REJECTED = 文件级错误，整份没进来。
  parse_status text NOT NULL CHECK(parse_status IN ('PARSED','REJECTED')),
  -- 解析告警（逐条：第几行 + 为什么）。空数组 = 没有行被跳过。
  parse_warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
  parsed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一份账单的每个解析版本各占一行。
  UNIQUE(tenant_id, file_key, parse_version)
);

-- ===========================================================================
-- 第三节：结算单头
-- ===========================================================================
--
-- ⚠️ 词表里的 `PLATFORM_SYNC`（平台结算报告自动同步）**当前不可产生**：
--   上游「卷 02 同步」没有这个端点。值保留（上游交付后还要能用），
--   但服务层拒绝用它建单并写明归属模块 —— 详见文件头第 0 节。
CREATE TABLE finance_settlement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  settlement_no varchar(48) NOT NULL,
  -- 店铺（channel 服务 2.3.1.1 的逻辑 ID；**不建跨库 FK**）。
  shop_id uuid NOT NULL,
  shop_code varchar(64) NOT NULL DEFAULT '',
  shop_name varchar(160) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  -- **结算周期**：日历区间（date，不是 timestamptz —— 周期是日历天，不是某一瞬间）。
  period_start date NOT NULL,
  period_end date NOT NULL,
  CHECK(period_end >= period_start),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- **结算金额**：**由汇总项求和派生**（触发器写；服务层写不进去）。
  -- 详见第 2 节的派生链。
  settlement_amount numeric(19,4) NOT NULL DEFAULT 0,
  -- 到账状态：
  --   PENDING    待到账（还没到）
  --   PARTIAL    部分到账（到了但没到齐）
  --   SETTLED    已到账（到齐了）
  --   VOID       已作废（终态，整单冻结）
  --   SUPERSEDED 已被同一账单的新解析版本取代（终态，不进汇总口径）
  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','PARTIAL','SETTLED','VOID','SUPERSEDED')),
  -- 预计到账时间（来自结算周期配置：周期结束日 + payout_lag_days）。
  expected_at timestamptz,
  -- 实际到账时间（**核心字段**，timestamptz）。
  arrived_at timestamptz,
  -- 实际到账金额（可能与结算金额不等：平台多付/少付都会发生）。
  received_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(received_amount >= 0),
  -- 来源：人工上传账单 / 平台结算报告同步 / 人工登记。
  source text NOT NULL
    CHECK(source IN ('MANUAL_UPLOAD','PLATFORM_SYNC','MANUAL')),
  -- 来源系统（amazon / shopify / …）与来源流水号（平台结算报告号）组成**幂等键**。
  source_system varchar(32) NOT NULL DEFAULT 'manual',
  source_reference varchar(128),
  -- **原始文件与解析版本**：指向具体哪一次解析。
  source_file_id uuid,
  -- 解析版本号（冗余一份，便于列表直接显示；权威值在 source_file_id 指向的那一行）。
  parse_version int NOT NULL DEFAULT 1 CHECK(parse_version >= 1),
  -- 结算周期配置（可空：没有配置也能建单，只是没有预计到账时间）。
  cycle_id uuid,
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, settlement_no),
  -- ⚠️ 幂等键**不**用表级 UNIQUE，而是用**部分唯一索引**（见第八节）：
  --   同一份结算报告重解析时会产生**新版本的新结算单**（旧的置为 SUPERSEDED），
  --   表级 UNIQUE 会让「重新解析」永远做不成 —— 而重新解析是本模块必须支持的
  --   （蓝图加粗句：「同一份账单重解析时版本要递增」）。
  --   部分唯一索引只约束**在册**的那一张，被取代的旧单不再挡路。
  -- source_reference 为 NULL 时（人工登记）Postgres 视 NULL 互不相等 ⇒ 不受影响。
  FOREIGN KEY(tenant_id, source_file_id) REFERENCES finance_settlement_source_file(tenant_id, id),
  FOREIGN KEY(tenant_id, cycle_id) REFERENCES finance_settlement_cycle(tenant_id, id)
);

-- ===========================================================================
-- 第四节：结算明细行
-- ===========================================================================
--
-- 一行 = 平台账单里的一笔（销售额的一笔、退款的一笔、佣金的一笔 …）。
--
-- ⚠️ **append-only**：明细行一旦写入就不可改、不可删 ——
--   账单是**外部事实**。改一行等于改写一份外部凭据；
--   正确的做法是**重新解析**（新版本 → 新结算单，旧单置为 SUPERSEDED）。
--   所以运行角色对这张表的 UPDATE / DELETE 已被收回。
--
-- ⚠️ **允许没有订单行的费用类明细**：佣金、FBA 费用、月度调整天然没有订单 ——
--   蓝图 12.3.1.2 把这条列为硬约束（「不强制每行都有订单」）。
--   所以这里**没有** order_no 的 NOT NULL 外键式约束，只有可空的 external_reference。
CREATE TABLE finance_settlement_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  settlement_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),
  -- 类别（与 finance_settlement_summary.category 同一词表）。
  category text NOT NULL
    CHECK(category IN ('SALES','REFUND','COMMISSION','FBA_FEE','ADVERTISING','COMPENSATION','ADJUSTMENT')),
  -- **归一后的贡献**（正=增加结算金额，负=减少）。符号约定见文件头第 3 节。
  amount numeric(19,4) NOT NULL,
  -- **账单里写的那个数**（原样保留，便于对账时逐行核对）。
  raw_amount numeric(19,4) NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 件数（可空：费用类明细没有件数）。数量一律 NUMERIC(19,6)，禁浮点。
  quantity numeric(19,6) CHECK(quantity IS NULL OR quantity > 0),
  -- 账单行日期（date —— 账单行是日历日，不是某一瞬间）。
  occurred_on date,
  -- 外部流水号（平台交易号 / 订单号 / 结算行号）。可空：**费用类明细天然没有订单行**。
  external_reference varchar(160),
  description varchar(300) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, settlement_id, line_no),
  FOREIGN KEY(tenant_id, settlement_id) REFERENCES finance_settlement(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第五节：结算汇总项
-- ===========================================================================
--
-- 一行 = 一个类别的合计。**由明细行派生**（见第六节的触发器），
-- 服务层**写不进去** —— guard_finance_settlement_summary_derived 会把它挡掉。
--
-- 之所以要单独一张表而不是七个列：
--   ① 类别是**词表**，将来加一类（比如「平台补贴」）只加一行 CHECK 值；
--   ② 每类别的**笔数**（line_count）也有价值（"退款 12 笔"）；
--   ③ 汇总项是蓝图点名的输出（「结算单 + 汇总项 + 明细行」），它得是**可查的实体**。
CREATE TABLE finance_settlement_summary (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  settlement_id uuid NOT NULL,
  category text NOT NULL
    CHECK(category IN ('SALES','REFUND','COMMISSION','FBA_FEE','ADVERTISING','COMPENSATION','ADJUSTMENT')),
  -- 对结算金额的**贡献**（正=增加、负=减少）。各行之和 == 结算单头的 settlement_amount。
  amount numeric(19,4) NOT NULL,
  -- 该类别的明细行笔数。
  line_count bigint NOT NULL DEFAULT 0 CHECK(line_count >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, settlement_id, category),
  FOREIGN KEY(tenant_id, settlement_id) REFERENCES finance_settlement(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第六节：单据事件流（append-only 举证凭据）
-- ===========================================================================
--
-- 与 finance_receipt_event 同一手法：事件流是「当时发生了什么」的**唯一**出处，
-- 运行角色的 UPDATE / DELETE 已收回 —— 事件只能追加。
CREATE TABLE finance_settlement_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  settlement_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'CREATED','IMPORTED','REPARSED','ARRIVED','VOIDED','SUPERSEDED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, settlement_id) REFERENCES finance_settlement(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第七节：数据层不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 派生链：明细行 → 汇总项 → 结算金额
-- ---------------------------------------------------------------------------
--
-- ⚠️ 这段是本模块最要紧的一段：**结算金额不是录进来的，是算出来的。**
CREATE OR REPLACE FUNCTION finance_settlement_refresh_totals(p_tenant uuid, p_settlement uuid)
RETURNS void AS $$
DECLARE
  total numeric(19,4);
BEGIN
  -- 1) 汇总项按类别重算（明细行删了也要跟着少）。
  DELETE FROM finance_settlement_summary
   WHERE tenant_id = p_tenant AND settlement_id = p_settlement;

  INSERT INTO finance_settlement_summary
    (id, tenant_id, settlement_id, category, amount, line_count, created_at, updated_at)
  SELECT gen_random_uuid(), p_tenant, p_settlement, l.category,
         sum(l.amount), count(*), now(), now()
    FROM finance_settlement_line l
   WHERE l.tenant_id = p_tenant AND l.settlement_id = p_settlement
   GROUP BY l.category;

  -- 2) 结算金额 = 各汇总项之和。
  SELECT coalesce(sum(s.amount), 0) INTO total
    FROM finance_settlement_summary s
   WHERE s.tenant_id = p_tenant AND s.settlement_id = p_settlement;

  UPDATE finance_settlement
     SET settlement_amount = total,
         updated_at = now(),
         version = version + 1
   WHERE tenant_id = p_tenant AND id = p_settlement;
END $$ LANGUAGE plpgsql;

-- 明细行**插入**：语句级触发器（一次导入算一次，不是每行算一次）。
CREATE OR REPLACE FUNCTION trg_finance_settlement_line_insert() RETURNS trigger AS $$
DECLARE
  row record;
BEGIN
  FOR row IN SELECT DISTINCT tenant_id, settlement_id FROM new_rows LOOP
    PERFORM finance_settlement_refresh_totals(row.tenant_id, row.settlement_id);
  END LOOP;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_settlement_line_refresh_insert
  AFTER INSERT ON finance_settlement_line
  REFERENCING NEW TABLE AS new_rows
  FOR EACH STATEMENT EXECUTE FUNCTION trg_finance_settlement_line_insert();

-- 明细行**改 / 删**：行级触发器（这条路径很少见，但必须有 —— 否则改了行金额汇总会失真）。
CREATE OR REPLACE FUNCTION trg_finance_settlement_line_change() RETURNS trigger AS $$
DECLARE
  old_tenant uuid;
  old_settlement uuid;
  new_tenant uuid;
  new_settlement uuid;
BEGIN
  old_tenant := CASE WHEN TG_OP IN ('UPDATE','DELETE') THEN OLD.tenant_id END;
  old_settlement := CASE WHEN TG_OP IN ('UPDATE','DELETE') THEN OLD.settlement_id END;
  new_tenant := CASE WHEN TG_OP IN ('INSERT','UPDATE') THEN NEW.tenant_id END;
  new_settlement := CASE WHEN TG_OP IN ('INSERT','UPDATE') THEN NEW.settlement_id END;

  IF old_settlement IS NOT NULL THEN
    PERFORM finance_settlement_refresh_totals(old_tenant, old_settlement);
  END IF;
  IF new_settlement IS NOT NULL
     AND (new_settlement IS DISTINCT FROM old_settlement
          OR new_tenant IS DISTINCT FROM old_tenant) THEN
    PERFORM finance_settlement_refresh_totals(new_tenant, new_settlement);
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_settlement_line_refresh_change
  AFTER UPDATE OR DELETE ON finance_settlement_line
  FOR EACH ROW EXECUTE FUNCTION trg_finance_settlement_line_change();

-- 汇总项：**只允许派生写入**。
--
-- ⚠️ `pg_trigger_depth() < 2` 的含义：现在是**直接**写汇总项（深度 1）就拒；
--   而由上面的派生触发器写（深度 2）放行。
--   这样「服务层手写汇总项」这条后门在 DB 层就被钉死了 ——
--   汇总项与明细行对不上时，数据层根本进不来。
CREATE OR REPLACE FUNCTION guard_finance_settlement_summary_derived() RETURNS trigger AS $$
BEGIN
  IF pg_trigger_depth() < 2 THEN
    RAISE EXCEPTION '结算汇总项由结算明细行派生（触发器维护），不允许直接写入：请写 finance_settlement_line'
      USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_settlement_summary_derived_guard
  BEFORE INSERT OR UPDATE OR DELETE ON finance_settlement_summary
  FOR EACH ROW EXECUTE FUNCTION guard_finance_settlement_summary_derived();

-- 结算金额：同样只允许派生写入。
CREATE OR REPLACE FUNCTION guard_finance_settlement_amount_derived() RETURNS trigger AS $$
BEGIN
  IF NEW.settlement_amount IS DISTINCT FROM OLD.settlement_amount
     AND pg_trigger_depth() < 2 THEN
    RAISE EXCEPTION '结算金额由结算汇总项求和派生（触发器维护），不允许直接写入'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_settlement_amount_derived_guard
  BEFORE UPDATE ON finance_settlement
  FOR EACH ROW EXECUTE FUNCTION guard_finance_settlement_amount_derived();

-- ---------------------------------------------------------------------------
-- (b) 结算单的事实不可改；合法变化只有：登记到账、作废、被新版本取代
-- ---------------------------------------------------------------------------
--
--     冻结清单：店铺、结算周期、币种、来源、来源系统、来源流水号、
--               原始文件引用、解析版本、结算周期配置、预计到账时间。
--     理由：平台结算单是**外部事实**。事后改金额 / 改周期等于改写一份外部凭据 ——
--           正确做法是重新解析（新版本 → 新单）。
--
--     可变：到账状态、到账时间、到账金额、备注、结算金额（**只由派生触发器写**）。
CREATE OR REPLACE FUNCTION guard_finance_settlement_frozen() RETURNS trigger AS $$
DECLARE
  head finance_settlement%ROWTYPE;
BEGIN
  -- 终态：已到账 / 已作废 / 已被取代 ⇒ 整单冻结，一列都不许改。
  IF OLD.status IN ('SETTLED','VOID','SUPERSEDED') THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '结算单 % 已是终态（%），不可再改：只能新建一张结算单',
        OLD.settlement_no, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id
     OR NEW.shop_code IS DISTINCT FROM OLD.shop_code
     OR NEW.platform_code IS DISTINCT FROM OLD.platform_code
     OR NEW.period_start IS DISTINCT FROM OLD.period_start
     OR NEW.period_end IS DISTINCT FROM OLD.period_end
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.source IS DISTINCT FROM OLD.source
     OR NEW.source_system IS DISTINCT FROM OLD.source_system
     OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
     OR NEW.source_file_id IS DISTINCT FROM OLD.source_file_id
     OR NEW.parse_version IS DISTINCT FROM OLD.parse_version
     OR NEW.cycle_id IS DISTINCT FROM OLD.cycle_id
     OR NEW.expected_at IS DISTINCT FROM OLD.expected_at THEN
    RAISE EXCEPTION '结算单 % 的结算事实（店铺/周期/币种/来源/原始文件版本/预计到账）不可再改：只能重新解析建新单',
      OLD.settlement_no USING ERRCODE = 'check_violation';
  END IF;

  -- 状态只允许走到：SETTLED / PARTIAL / VOID / SUPERSEDED。
  IF NEW.status IS DISTINCT FROM OLD.status
     AND NEW.status NOT IN ('PARTIAL','SETTLED','VOID','SUPERSEDED') THEN
    RAISE EXCEPTION '结算单 % 的状态只能走到 PARTIAL / SETTLED / VOID / SUPERSEDED，不可改成 %',
      OLD.settlement_no, NEW.status USING ERRCODE = 'check_violation';
  END IF;

  -- 到账状态与到账时间必须成对：有到账时间才能说到了账。
  IF NEW.status IN ('PARTIAL','SETTLED') AND NEW.arrived_at IS NULL THEN
    RAISE EXCEPTION '结算单 % 标记为 % 时必须写明到账时间', OLD.settlement_no, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 到账时间一旦登记就不可抹掉：到账时刻是**外部事实**。
  IF OLD.arrived_at IS NOT NULL AND NEW.arrived_at IS DISTINCT FROM OLD.arrived_at THEN
    RAISE EXCEPTION '结算单 % 的到账时间是外部事实，不可再改', OLD.settlement_no
      USING ERRCODE = 'check_violation';
  END IF;

  -- SETTLED 必须到齐；PARTIAL 必须没到齐。
  IF NEW.status = 'SETTLED' AND NEW.received_amount < NEW.settlement_amount THEN
    RAISE EXCEPTION '结算单 % 标记为已到账，但到账金额 % 小于结算金额 %',
      OLD.settlement_no, NEW.received_amount, NEW.settlement_amount
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.status = 'PARTIAL' AND NEW.received_amount >= NEW.settlement_amount THEN
    RAISE EXCEPTION '结算单 % 标记为部分到账，但到账金额 % 已经不小于结算金额 %（那是已到账）',
      OLD.settlement_no, NEW.received_amount, NEW.settlement_amount
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_settlement_frozen
  BEFORE UPDATE ON finance_settlement
  FOR EACH ROW EXECUTE FUNCTION guard_finance_settlement_frozen();

-- ---------------------------------------------------------------------------
-- (c) 明细行只能在**未到账**的结算单上追加
-- ---------------------------------------------------------------------------
--
--     已到账 / 已作废 / 已被取代的结算单，其明细行是**已经入账的事实**，不许再动。
--     补录的正确做法是重新解析 → 新版本 → 新结算单。
CREATE OR REPLACE FUNCTION guard_finance_settlement_line_append() RETURNS trigger AS $$
DECLARE
  head finance_settlement%ROWTYPE;
BEGIN
  SELECT * INTO head FROM finance_settlement
   WHERE tenant_id = NEW.tenant_id AND id = NEW.settlement_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION '结算单不存在或不属于当前租户' USING ERRCODE = 'check_violation';
  END IF;
  IF head.status IN ('SETTLED','VOID','SUPERSEDED') THEN
    RAISE EXCEPTION '结算单 % 已是终态（%），不可再追加明细行：请重新解析建新单',
      head.settlement_no, head.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_settlement_line_append_guard
  BEFORE INSERT ON finance_settlement_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_settlement_line_append();

-- ===========================================================================
-- 第八节：索引
-- ===========================================================================

-- 幂等键：同一来源系统的同一份结算报告**在册的**只允许一张单。
-- ⚠️ 用**部分唯一索引**（WHERE status <> 'SUPERSEDED'）而不是表级 UNIQUE：
--   表级 UNIQUE 会让「同一份账单重新解析」永远做不成 ——
--   而重新解析是本模块必须支持的（蓝图加粗句：「同一份账单重解析时版本要递增」）。
--   被新版本取代的旧单（SUPERSEDED）留在库里（它是「当时登记过」的留痕），
--   但不再占用这个幂等键。
CREATE UNIQUE INDEX finance_settlement_live_report_unique
  ON finance_settlement(tenant_id, source_system, source_reference)
  WHERE status <> 'SUPERSEDED' AND source_reference IS NOT NULL;

-- 列表默认排序：结算周期结束日倒序（最近一期在上面）+ id。
CREATE INDEX finance_settlement_by_period
  ON finance_settlement(tenant_id, status, period_end DESC, id);
-- 按店铺查（一家店一个周期的结算单）。
CREATE INDEX finance_settlement_by_shop
  ON finance_settlement(tenant_id, shop_id, period_end DESC, id);
-- 幂等键 + 「这个来源带来了多少」。
CREATE INDEX finance_settlement_by_source
  ON finance_settlement(tenant_id, source, source_system, source_reference);
-- 到账状态（待到账的台账）。
CREATE INDEX finance_settlement_by_arrival
  ON finance_settlement(tenant_id, status, expected_at, id)
  WHERE status IN ('PENDING','PARTIAL');
-- 原始文件版本反查（这个文件版本产出了哪张结算单）。
CREATE INDEX finance_settlement_by_source_file
  ON finance_settlement(tenant_id, source_file_id);
-- 同一份账单的最新解析版本（版本号递增靠它）。
CREATE INDEX finance_settlement_source_file_by_key
  ON finance_settlement_source_file(tenant_id, file_key, parse_version DESC);
-- 明细行按结算单取（按行号排序）。
CREATE INDEX finance_settlement_line_by_settlement
  ON finance_settlement_line(tenant_id, settlement_id, line_no);
-- 汇总项按结算单取。
CREATE INDEX finance_settlement_summary_by_settlement
  ON finance_settlement_summary(tenant_id, settlement_id, category);
-- 事件流按 (occurred_at, id) 排 —— 同一毫秒的事件只看时间顺序不确定。
CREATE INDEX finance_settlement_event_by_settlement
  ON finance_settlement_event(tenant_id, settlement_id, occurred_at, id);

-- ===========================================================================
-- 第九节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_settlement_cycle','finance_settlement_source_file','finance_settlement',
    'finance_settlement_line','finance_settlement_summary','finance_settlement_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_settlement_cycle, finance_settlement_source_file, finance_settlement,
  finance_settlement_line, finance_settlement_summary, finance_settlement_event TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_settlement_cycle, finance_settlement_source_file, finance_settlement,
  finance_settlement_line, finance_settlement_summary, finance_settlement_event TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：
--   ① 事件流是**举证凭据**，只能追加；
--   ② 明细行是**外部事实**（账单原文），只能追加 —— 改账单等于改写外部凭据，
--      正确做法是重新解析（新版本 → 新结算单）。
REVOKE UPDATE,DELETE ON finance_settlement_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_settlement_line FROM finance_app;

-- ===========================================================================
-- 第十节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.2.1.1 收款单（已交付，但接上动作归它自己）**：
--     `finance_receipt.settlement_no` 由 CHECK 钉死恒为 NULL（V341）。
--     **接上动作**：12.2.1.1 的车道 DROP 那条 CHECK，
--     并允许以 `source = 'PLATFORM_SETTLEMENT'` + `settlement_no = 结算单号` 建收款单。
--     本模块**不改 V341**（已进过集成头，改内容会让 Flyway checksum 校验炸掉），
--     但**已经把读路径铺好了**：本模块的「与收款侧的差额」按
--     `finance_receipt.settlement_no = 结算单号` 关联 —— 上游一放开就自动有数据。
--
--   * **12.1.1.1 应收单（已交付，但接上动作归它自己）**：
--     服务层当前拒绝 `source = 'PLATFORM_SETTLEMENT'` 建单（V338 / V339）。
--     **接上动作**：12.1.1.1 的车道放开该来源，
--     并用 `source_reference = 结算单号` 作为幂等键。
--     本模块**不改 V338 / V339**，但「与我方应收的差额」已经真的在算：
--     同店铺、同周期、同币种的应收按来源分组报出来。
--
--   * **12.3.2.1 对账任务（未交付）**：本模块只**算差额**，
--     不实现自动匹配、不产出匹配结果、不产出差异分类。
--
--   * **13 利润（未交付）**：结算单是它的一处上游输入，
--     本模块**不产出任何利润数字**。
