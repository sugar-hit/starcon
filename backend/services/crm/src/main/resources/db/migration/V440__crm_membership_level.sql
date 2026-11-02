-- 15.6.1.1 会员与等级：会员档案、等级规则（按累计消费 / 订单数 / 复购率自动升级降级）、
-- 等级权益（折扣 / 免邮 / 优先发货）与等级变更记录。
--
-- 蓝图 15 卷 15.6.1.1 的原话：
--   「职责：管理会员体系：会员注册与合并、等级规则（按累计消费/订单数/复购率自动升级降级）、
--     等级权益（折扣、免邮、优先发货），支持与平台会员体系对接或独立建档。
--     输入：订单与消费数据（9）；等级规则配置；平台会员数据同步。
--     输出：会员档案 + 等级变更记录；权益生效配置（→ 5 价目 / 14 促销）。
--     关系：上游 = 9 订单、15.3.1.2 客户档案；下游 = 5 定价、14 促销、15.6.2 复购。
--     **会员等级必须由真实消费数据驱动**，手工挂等级会导致权益成本失控。」
--
-- ============================================================================
-- 零、本文件与既有模块的边界（写入所有者各自独立）
-- ============================================================================
--
-- 本文件是 **crm 数据库的第二十三个迁移**（V99 知识库 … V437 跨店合并，见目录）。
--
-- 与兄弟模块的边界 —— **零共用表、零 ALTER 既有表**：
--   * V432（15.3.1.1 客户列表）：回答「租户有哪些客户」。本文件**只读**它的
--     `crm_customer_profile`（客户身份 / 来源店铺 / 买家名快照）—— 实际上本模块的
--     会员档案由**上游订单事实**驱动建档，只在**合并口径**上读 V437。
--   * V437（15.3.1.3 跨店合并）：回答「哪些来源店铺客户行其实是同一个人」。
--     本文件**只读**它的 `crm_customer_merge_mapping` / `crm_customer_merge_logical`，
--     用它把**同一个人的多笔消费合成一张会员档案**（= 蓝图里的「会员合并」）。
--     **一个字都不写**它的表。
--   * V435（15.3.1.2 客户详情）：回答「这位客户的时间线上有什么」。本模块**不写**它的表。
--   * V436（15.6.2.1 生命周期与流失预警）：回答「这位客户该回购却没回购」。
--     本模块回答「这位客户是什么等级、能享什么权益」。两者**不共用表、不共用端点**。
--
-- ============================================================================
-- 一、🔴 蓝图加粗句在本文件里的落点：**等级只能由真实消费数据算出来**
-- ============================================================================
--
-- 「会员等级必须由真实消费数据驱动，手工挂等级会导致权益成本失控」——
-- 本文件用**三层**把它钉死，缺一层都可能被绕过：
--
--   1. **没有「直接写等级」的写路径**：等级由 `crm_membership_profile.level_code`
--      承载，而这张表**只由重算写入**；服务端**没有**任何接受「等级代码」入参的端点。
--   2. **DB 层拒绝人工覆盖**：`level_origin` 允许的取值里虽然留了
--      `MANUAL_OVERRIDE`，但触发器 `guard_crm_membership_origin` **直接拒绝**它 ——
--      人工覆盖通道本轮**未交付**（见能力声明）。也就是说：即使有人绕过应用层
--      直连数据库写 `level_code = 'GOLD'`，只要 `level_origin` 不是 `RULE`，
--      这一行就写不进去。
--   3. **判定依据必须留痕**：每条会员档案带 `level_reason`（凭什么落这一级）
--      与 `level_rule_id`（命中哪条规则行）；每次等级变化落一条
--      `crm_membership_level_change`（含 `basis` 判定依据快照）。
--
-- 🔴 报表口径：等级来源写在 `level_origin` 上。本轮**恒为** `RULE`（规则算出来的），
--   将来若交付人工覆盖通道，报表按这一列即可区分「规则算出来的等级」与
--   「人工覆盖的等级」—— **现在不需要为了将来的报表去改口径**。
--
-- ============================================================================
-- 二、跨服务纪律（零跨库 SQL / FK / 视图）
-- ============================================================================
--
-- 上游 **9 订单**在 **trade** 服务（端口 8085，`GET /trade/sales-orders/list`），
-- 与本模块**不同服务、不同数据库** ⇒ 只走**授权 HTTP API**，禁止跨库 SQL /
-- 跨库 FK / 共享视图。转发的是**调用方自己的** `Authorization` bearer，
-- 上游侧仍按已认证成员关系解析租户并强制 RLS ⇒ 租户隔离是**两道**。
--
-- 🔴 本文件**不存**任何 trade 的订单行快照：会员档案里的消费数字是**每次重算**
--   从上游拉回来的**读数**，不是本库抄来的副本。等级规则改了，重算一次就变。
--
-- ============================================================================
-- 三、四张表
-- ============================================================================
--
--   crm_membership_level_rule   等级规则 + 等级权益（租户数据，带生效区间）
--   crm_membership_profile       会员档案（等级 + 消费指标 + 判定依据）
--   crm_membership_level_change  等级变更记录（升级 / 降级 / 首次定级）
--   crm_membership_run           一次「重算会员等级」的运行记录（可审计）
--
-- ============================================================================
-- 四、🔴 禁浮点与时间纪律
-- ============================================================================
--
--   * 金额：numeric(19,4) + BigDecimal；折扣率 / 复购率：numeric(9,6) + BigDecimal；禁浮点。
--   * 时间：一律 timestamptz + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区**静默偏 −28800 秒** ——
--     而「距上次购买多少天」「规则是否在生效期内」正是本模块的核心计算。
--
-- ============================================================================
-- 五、🔴 金额不跨币种相加
-- ============================================================================
--
-- 会员档案的 `total_spend` 只记**主币种**（该会员窗口内消费最多的那个币种）的金额；
-- 分币种的明细放在 `amounts`（jsonb 数组，一行一个币种）。
-- 等级规则的金额门槛**必须**带币种（`min_total_spend` 与 `min_total_spend_currency`
-- **同生共死**）—— 跨币种的金额不能比，这是硬 CHECK，不是约定。
--
-- ============================================================================
-- 六、发布后不可变的强制位置
-- ============================================================================
--
--   1. 会员主键（`member_key`）建后不可改（它是「这个人 / 这组合并后的人」的身份）。
--   2. 等级判定依据（`level_reason`）必须非空。
--   3. 等级来源（`level_origin`）本轮**只允许** `RULE`。
--
-- ⚠️ 为什么用**触发器**而不是**数据库外键**：
--   同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）会被外键打破。本文件**不给任何既有表建外键**，本文件自己新建的表之间
--   也**一律用触发器做引用校验**，与 V99 / V220 / V228 / V292 / V295 / V299 / V432 / V436
--   同一手法。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 / V436 同一口径：角色可能已由运维预置，
-- 因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;
GRANT USAGE ON SCHEMA public TO starcon_app;

-- 运行号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一次重算会员等级就报权限不足」。
CREATE SEQUENCE crm_membership_run_no_seq START 1;

-- ============================================================================
-- 1. crm_membership_level_rule —— 等级规则 + 等级权益（**租户数据**）
-- ============================================================================
CREATE TABLE crm_membership_level_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 等级标识（**租户自定义**，不是代码写死的枚举）=====
  -- 等级代码（如 GOLD / SILVER）。运营可以自己定档，本模块不规定必须分几档。
  level_code varchar(24) NOT NULL CHECK (btrim(level_code) <> ''),
  -- 等级名称（如「黄金会员」）：界面上显示的是它。
  level_name varchar(64) NOT NULL CHECK (btrim(level_name) <> ''),
  -- 等级序（越大越高）：升级 = 序变大，降级 = 序变小。
  -- 序相等时按生效起点新的那条说了算（应用层保证确定性）。
  level_rank integer NOT NULL CHECK (level_rank >= 0),

  -- ===== 三条判定门槛（蓝图：「按累计消费 / 订单数 / 复购率自动升级降级」）=====
  -- 🔴 **三者的口径都写在列注释里，界面上原样显示**：
  --   * 累计消费门槛：窗口内、**该币种**下的消费合计（NULL = 不限这一维）。
  --   * 订单数门槛：窗口内订单**总单数**（跨币种可加，因为单数不是金额）。
  --   * 复购率门槛：窗口内「**非首单订单数 ÷ 订单数**」（NULL = 不限这一维）。
  -- 门槛**全部可空**：只配一条也成立（那就是「只按消费定级」）。
  min_total_spend numeric(19,4) CHECK (min_total_spend >= 0),
  -- 金额门槛的币种。🔴 **必须与金额门槛同生共死**：跨币种的金额不能比。
  min_total_spend_currency char(3),
  min_order_count integer CHECK (min_order_count >= 0),
  min_repurchase_rate numeric(9,6) CHECK (min_repurchase_rate >= 0 AND min_repurchase_rate <= 1),

  -- ===== 等级权益（折扣 / 免邮 / 优先发货）=====
  -- 折扣率：0.05 = 减免 5%（0.05 ≤ x < 1；NULL = 该等级没有折扣权益）。
  discount_rate numeric(9,6) CHECK (discount_rate >= 0 AND discount_rate < 1),
  -- 免邮权益 / 优先发货权益。
  free_shipping boolean NOT NULL DEFAULT false,
  priority_shipping boolean NOT NULL DEFAULT false,

  enabled boolean NOT NULL DEFAULT true,

  -- ===== 生效区间（改规则不改历史）=====
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  -- 备注：写清「这一档为什么是这一档」（例：黄金 = 累计消费满 5000 且复购率 ≥ 30%）。
  note varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 同一等级代码 + 同一生效起点 ⇒ 只有一条。
  UNIQUE (tenant_id, level_code, effective_from),

  -- 金额门槛与币种**同生共死**。
  CHECK ((min_total_spend IS NULL) = (min_total_spend_currency IS NULL)),
  -- 生效区间必须自洽。
  CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- 🔴 同一等级代码**同时只能有一条「开着」的规则**（effective_to 为空）。
--   改了门槛要新建一条（新的生效起点），旧的那条被关掉 —— 不可变优先。
--   这条索引让「两个开着同档规则」在 DB 层就进不来。
CREATE UNIQUE INDEX crm_membership_level_rule_open_code
  ON crm_membership_level_rule(tenant_id, level_code)
  WHERE effective_to IS NULL;

-- ============================================================================
-- 2. crm_membership_profile —— 会员档案
-- ============================================================================
CREATE TABLE crm_membership_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 会员身份 =====
  -- 会员主键：已合并（15.3.1.3）= 逻辑客户号（如 LC-20261102-000001）；
  -- 未合并 = `来源店铺:客户标识`。
  -- 🔴 建后不可改（触发器 6.1）：它是「这个人（或这组合并后的人）」的身份。
  member_key varchar(160) NOT NULL CHECK (btrim(member_key) <> ''),
  -- 主档来源店铺客户标识（与 15.3.1.1 的 customer_ref 同一口径）。
  customer_ref varchar(128) NOT NULL CHECK (btrim(customer_ref) <> ''),
  -- 主档来源店铺（订单来源口径，与 15.3.1.1 同一口径）。
  source_shop varchar(64) NOT NULL CHECK (btrim(source_shop) <> ''),
  customer_party_id uuid,
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 合并口径（15.3.1.3 的只读快照）=====
  -- 逻辑客户（已合并时指向它，未合并为 NULL）。**逻辑引用**，不建跨表外键。
  logical_customer_id uuid,
  -- 逻辑客户号快照（拆开以后也知道这一行原来属于谁）。
  logical_ref varchar(64),
  -- 合并进来的成员数（1 = 未合并）。
  member_count integer NOT NULL DEFAULT 1 CHECK (member_count >= 1),
  -- 成员来源店铺清单（jsonb 数组）：合并后这一行到底由哪几个店铺的订单撑起来。
  source_shops jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 成员快照（jsonb 数组）：每段一个成员（客户标识 / 店铺 / 名字 / 单数 / 金额 / 币种）。
  members jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- ===== 消费指标（**全部来自上游真实订单事实**）=====
  -- 窗口内订单总单数（跨币种可加：单数不是金额）。
  order_count integer NOT NULL DEFAULT 0 CHECK (order_count >= 0),
  -- 主币种下的窗口内累计消费。
  total_spend numeric(19,4) CHECK (total_spend >= 0),
  -- 主币种（窗口内消费最多的那个币种）。
  spend_currency char(3),
  -- 分币种明细（jsonb 数组）：{currency, orderCount, totalSpend}，**一币种一行**。
  amounts jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 复购率 = 窗口内「非首单订单数 ÷ 订单数」，scale 6、HALF_UP。
  -- 🔴 **分母为 0 时是 NULL（不可判定），不是 0** —— 窗口内没有订单就没有比率，
  -- 报 0% 会被读成「这个人一次都没复购」。
  repurchase_rate numeric(9,6),

  first_order_at timestamptz,
  last_order_at timestamptz,

  -- ===== 当前等级 =====
  -- 当前等级代码；'NONE' = **未达任何等级**（一等状态，不是「正常」）。
  level_code varchar(24) NOT NULL DEFAULT 'NONE' CHECK (btrim(level_code) <> ''),
  -- 等级序（越大越高）。
  level_rank integer NOT NULL DEFAULT 0 CHECK (level_rank >= 0),
  -- 🔴 等级来源：RULE = 规则算出来的。
  --   MANUAL_OVERRIDE = 人工覆盖 —— **本轮未交付**，触发器 6.3 直接拒绝写入。
  level_origin varchar(16) NOT NULL DEFAULT 'RULE'
    CHECK (level_origin IN ('RULE','MANUAL_OVERRIDE')),
  -- 命中的等级规则行（**逻辑引用**；未达任何等级时为 NULL）。
  level_rule_id uuid,
  -- 🔴 判定理由**必填**：凭什么落这一级必须能回答。
  level_reason varchar(600) NOT NULL DEFAULT '',

  -- ===== 统计窗口与判定时刻 =====
  window_from timestamptz,
  window_to timestamptz,
  level_evaluated_at timestamptz NOT NULL,
  -- 建档时刻：第一次有真实消费数据时落库，**重算不覆盖**。
  registered_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴 会员主键在租户内唯一。
  UNIQUE (tenant_id, member_key)
);

-- ============================================================================
-- 3. crm_membership_level_change —— 等级变更记录
-- ============================================================================
CREATE TABLE crm_membership_level_change (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  member_key varchar(160) NOT NULL CHECK (btrim(member_key) <> ''),
  customer_ref varchar(128) NOT NULL DEFAULT '',
  source_shop varchar(64) NOT NULL DEFAULT '',
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 变更前后 =====
  -- 变更前等级（建档时为 'NONE'）。
  from_level_code varchar(24) NOT NULL DEFAULT 'NONE' CHECK (btrim(from_level_code) <> ''),
  -- 变更后等级（'NONE' = 掉出所有等级）。
  to_level_code varchar(24) NOT NULL CHECK (btrim(to_level_code) <> ''),
  from_level_rank integer NOT NULL DEFAULT 0 CHECK (from_level_rank >= 0),
  to_level_rank integer NOT NULL DEFAULT 0 CHECK (to_level_rank >= 0),

  -- ===== 变更性质 =====
  -- REGISTERED 首次建档定级 / UPGRADE 升级 / DOWNGRADE 降级。
  change_kind varchar(16) NOT NULL
    CHECK (change_kind IN ('REGISTERED','UPGRADE','DOWNGRADE')),

  -- 🔴 等级来源：本轮**只允许** RULE（触发器 6.4 直接拒绝 MANUAL_OVERRIDE）。
  origin varchar(16) NOT NULL DEFAULT 'RULE'
    CHECK (origin IN ('RULE','MANUAL_OVERRIDE')),

  -- 命中的规则行（**逻辑引用**）。
  rule_id uuid,
  -- 🔴 变更理由**必填**。
  reason varchar(600) NOT NULL DEFAULT '',
  -- 判定依据快照（jsonb）：当时的消费指标 + 命中的门槛。
  basis jsonb NOT NULL DEFAULT '{}'::jsonb,

  changed_at timestamptz NOT NULL,
  operator_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- ============================================================================
-- 4. crm_membership_run —— 重算运行记录
-- ============================================================================
CREATE TABLE crm_membership_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- MB-yyyyMMdd-000001
  run_no varchar(32) NOT NULL DEFAULT '',
  -- SUCCEEDED 成功 / FAILED 上游读不到（一条会员档案都不落）
  status varchar(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),

  -- 🔴 窗口起点**可空** = 全部历史（不限起点）。终点恒为判定时刻。
  window_from timestamptz,
  window_to timestamptz NOT NULL,

  -- 计数（纯计数）
  member_count integer NOT NULL DEFAULT 0 CHECK (member_count >= 0),
  -- 本次发生等级变化的会员数。
  level_changed_count integer NOT NULL DEFAULT 0 CHECK (level_changed_count >= 0),
  -- 参与判定的等级规则条数（生效中的）。
  rule_count integer NOT NULL DEFAULT 0 CHECK (rule_count >= 0),
  -- 未达任何等级的会员数。
  unmatched_count integer NOT NULL DEFAULT 0 CHECK (unmatched_count >= 0),
  -- 因跨币种而**无法用该规则**判定的会员数（金额门槛币种对不上）。
  currency_skipped_count integer NOT NULL DEFAULT 0 CHECK (currency_skipped_count >= 0),
  -- 上游扫描被截断（订单太多，翻页到上限）。
  truncated boolean NOT NULL DEFAULT false,

  failure_reason varchar(600) NOT NULL DEFAULT '',
  facts jsonb NOT NULL DEFAULT '{}'::jsonb,

  operator_id uuid,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, run_no)
);

-- ============================================================================
-- 5. 索引
-- ============================================================================
-- 会员档案默认排序：等级序降序（等级高的排最前） + 累计消费降序。
CREATE INDEX crm_membership_profile_by_level
  ON crm_membership_profile(tenant_id, level_code, level_rank DESC, member_key);

CREATE INDEX crm_membership_profile_by_spend
  ON crm_membership_profile(tenant_id, total_spend DESC NULLS LAST, member_key);

CREATE INDEX crm_membership_profile_by_ref
  ON crm_membership_profile(tenant_id, customer_ref);

CREATE INDEX crm_membership_profile_by_shop
  ON crm_membership_profile(tenant_id, source_shop, member_key);

-- 等级变更记录默认排序：变更时刻降序。
CREATE INDEX crm_membership_level_change_by_changed
  ON crm_membership_level_change(tenant_id, changed_at DESC, id DESC);

CREATE INDEX crm_membership_level_change_by_member
  ON crm_membership_level_change(tenant_id, member_key, changed_at DESC);

CREATE INDEX crm_membership_level_change_by_kind
  ON crm_membership_level_change(tenant_id, change_kind, changed_at DESC);

-- 规则查询：按等级代码 + 生效区间。
CREATE INDEX crm_membership_level_rule_by_code
  ON crm_membership_level_rule(tenant_id, level_code, effective_from DESC);

CREATE INDEX crm_membership_level_rule_by_rank
  ON crm_membership_level_rule(tenant_id, level_rank DESC, effective_from DESC);

CREATE INDEX crm_membership_run_by_created
  ON crm_membership_run(tenant_id, created_at DESC, id DESC);

-- ============================================================================
-- 6. 触发器（**一律不用外键** —— 理由见文件头第六节）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 6.1 guard_crm_membership_identity · 会员主键与身份列建后不可改
-- ---------------------------------------------------------------------------
-- 会员主键是「这个人（或这组合并后的人）」的身份：改了它，
-- 等级变更记录里指向的就不再是同一个人。
CREATE FUNCTION guard_crm_membership_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.member_key IS DISTINCT FROM OLD.member_key THEN
    RAISE EXCEPTION '会员主键建后不可改：它是「这个人（或合并后的这一组人）」的身份' USING ERRCODE='23514';
  END IF;
  IF NEW.customer_ref IS DISTINCT FROM OLD.customer_ref THEN
    RAISE EXCEPTION '客户标识建后不可改：改了它会让「这位会员是谁」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.source_shop IS DISTINCT FROM OLD.source_shop THEN
    RAISE EXCEPTION '来源店铺建后不可改：它是「按店铺的客户标识」的另一半' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_membership_profile_identity_trg
  BEFORE UPDATE ON crm_membership_profile
  FOR EACH ROW EXECUTE FUNCTION guard_crm_membership_identity();

-- ---------------------------------------------------------------------------
-- 6.2 guard_crm_membership_reason · 会员档案必须带判定理由
-- ---------------------------------------------------------------------------
-- 凭什么落这一级必须能回答 —— 没有理由的等级没法复核，
-- 也无法与「手工挂等级」区分开。
CREATE FUNCTION guard_crm_membership_reason() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF btrim(NEW.level_reason) = '' THEN
    RAISE EXCEPTION '会员档案必须写明等级判定理由：凭什么落 % 这一级必须能回答', NEW.level_code USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_membership_profile_reason_trg
  BEFORE INSERT OR UPDATE ON crm_membership_profile
  FOR EACH ROW EXECUTE FUNCTION guard_crm_membership_reason();

-- ---------------------------------------------------------------------------
-- 6.3 guard_crm_membership_origin · 🔴 人工覆盖等级本轮未交付
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「会员等级必须由真实消费数据驱动，手工挂等级会导致权益成本失控」
-- 在 DB 层的落点：**人工覆盖等级这一行写不进去**。
-- 本轮只交付**规则算出来的**等级；将来真要交付人工覆盖通道，
-- 必须先设计「原因 + 留痕 + 有效期」三件套，再放开这个触发器。
CREATE FUNCTION guard_crm_membership_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.level_origin <> 'RULE' THEN
    RAISE EXCEPTION '会员等级只能由真实消费数据算出：人工覆盖通道本轮未交付，level_origin 只允许 RULE' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_membership_profile_origin_trg
  BEFORE INSERT OR UPDATE ON crm_membership_profile
  FOR EACH ROW EXECUTE FUNCTION guard_crm_membership_origin();

-- ---------------------------------------------------------------------------
-- 6.4 guard_crm_membership_change_origin · 等级变更记录同样只允许 RULE
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_membership_change_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.origin <> 'RULE' THEN
    RAISE EXCEPTION '等级变更只能由规则算出：人工覆盖通道本轮未交付，origin 只允许 RULE' USING ERRCODE='23514';
  END IF;
  IF btrim(NEW.reason) = '' THEN
    RAISE EXCEPTION '等级变更记录必须写明变更理由' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_membership_level_change_origin_trg
  BEFORE INSERT OR UPDATE ON crm_membership_level_change
  FOR EACH ROW EXECUTE FUNCTION guard_crm_membership_change_origin();

-- ============================================================================
-- 7. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 / V436 同一口径：策略名固定 isolation，
-- USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['crm_membership_level_rule','crm_membership_profile','crm_membership_level_change','crm_membership_run'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_membership_level_rule, crm_membership_profile, crm_membership_level_change, crm_membership_run TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_membership_level_rule, crm_membership_profile, crm_membership_level_change, crm_membership_run TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一次重算会员等级就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_membership_run_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_membership_run_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 8. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--
--   * **5 定价 / 14 促销**：本模块**只输出**「权益生效配置」（哪个等级享受什么权益、
--     什么条件生效）—— 不改价目表、不改促销表（跨服务禁止）。
--   * **15.6.2 复购**（15.6.2.1 已交付、15.6.2.2 已交付）：本模块的会员档案是它们的
--     **上游事实**，它们消费本模块的结果，本模块不代劳。
--   * **平台会员体系对接**（channel 服务未交付平台会员接口）：本模块交付的是
--     **独立建档**口径 —— 会员档案由租户真实消费数据驱动。平台侧会员等级
--     **不回写**本服务（那样会绕过「真实消费数据」这条硬约束）。
