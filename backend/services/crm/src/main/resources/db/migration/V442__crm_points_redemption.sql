-- 15.6.1.2 积分与权益：积分规则（获取 / 消耗）、积分账户与流水、积分批次（有效期）、
-- 权益目录与权益核销记录、积分负债汇总的**事实来源**。
--
-- 蓝图 15 卷 15.6.1.2 的原话：
--   「职责：管理积分与权益：积分获取（消费/评价/签到）与消耗（抵扣/兑换）、
--     积分余额与有效期、权益核销；积分负债需财务确认。
--     输入：积分规则；获取/消耗事件；权益核销。
--     输出：积分账户与流水；权益核销记录；积分负债汇总（→ 12）。
--     关系：上游 = 15.6.1.1、9 订单；下游 = 5 定价（积分抵扣）、12 财务（积分负债）。
--     **未兑付积分是负债**，需在财务侧有对应科目或备忘，不能只记在业务库。」
--
-- ============================================================================
-- 零、本文件与既有模块的边界（写入所有者各自独立）
-- ============================================================================
--
-- 本文件是 **crm 数据库的第二十四个迁移**（V99 知识库 … V440 会员等级，见目录）。
--
-- 与兄弟模块的边界 —— **零共用表、零 ALTER 既有表**：
--   * V440（15.6.1.1 会员与等级）：回答「这位客户是什么等级、能享什么权益」。
--     本文件**只读**它的 `crm_membership_profile`（会员主键 / 客户标识 / 来源店铺 /
--     买家名 / 客户主体 id）—— 用它把积分账户**挂在真实会员身份**上。
--     **一个字都不写**它的表。
--   * V294（15.2.2.1 商品评价）：回答「这条评价是什么」。本文件**只读**它的
--     `crm_product_review`（评价存在性与买家标识），用来判定「这条评价能不能发积分」。
--     **一个字都不写**它的表。
--   * trade（9 订单）：订单事实走**授权只读 HTTP API**（`GET /trade/sales-orders/list`），
--     本库**不建跨库 FK、不建跨库视图、不直连对方库**。
--
-- ============================================================================
-- 一、🔴 加粗句在本文件里的落点：**未兑付积分是负债**
-- ============================================================================
--
-- 「**未兑付积分是负债**，需在财务侧有对应科目或备忘，不能只记在业务库」——
-- 本文件做三件**如实标注**的事，一件**代劳**的事都不做：
--
--   1. **积分账户有余额**（`crm_points_account.balance`）—— 这是「未兑付」的量。
--   2. **单积分折算价值是租户数据**（`crm_points_rule` 里 `rule_type='VALUATION'`
--      的那条规则：`point_value_rate` + `liability_currency`）—— 负债金额 = 未兑付积分 × 单积分价值。
--      这条规则**必须唯一**（部分唯一索引保证一个租户同时只有一条开着的估值规则），
--      否则负债汇总会有两个答案。
--   3. **负债汇总是输出，不是入账**：本模块提供 `GET /crm/points/liability`，
--      在能力声明里把「12 财务侧建科目 / 入账」标成 `available=false` +
--      `ownerModule=12` + 影响说明。
--      🔴 本模块**不代劳**在财务侧建科目，也**不假装**已经入账 ——
--      业务库里有一张表 ≠ 账上有一笔负债。
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
-- 🔴 本文件**不存**任何 trade 的订单行快照：积分流水里记的是订单的**逻辑引用**
--   （`reference_type='ORDER'` + `reference_id`），金额与币种是**每次事件**从上游
--   拉回来的**读数**，不是本库抄来的副本。
--
-- ============================================================================
-- 三、六张表
-- ============================================================================
--
--   crm_points_rule         积分规则（获取 / 消耗 / 估值）+ 权益（**租户数据**）
--   crm_points_account      积分账户（余额 / 累计获取 / 累计消耗 / 累计过期）
--   crm_points_lot          积分批次（**有效期**与剩余可消耗量）
--   crm_points_ledger       积分流水（**只追加**，含扣前余额 → 扣后余额）
--   crm_points_entitlement  权益目录（可兑换的权益 + 兑换所需积分）
--   crm_points_redemption   权益核销记录（兑换 → 核销）
--
-- ============================================================================
-- 四、🔴 禁浮点与时间纪律
-- ============================================================================
--
--   * 积分与金额一律 numeric + BigDecimal；数量口径 numeric(19,6)（积分可以是小数）。
--     金额口径 numeric(19,4)。**禁浮点**。
--   * 时间：一律 timestamptz + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区**静默偏 −28800 秒** ——
--     而「积分有效期必须按**绝对时刻**判定」正是本模块的核心计算。
--
-- ============================================================================
-- 五、🔴 积分扣减必须可审阅
-- ============================================================================
--
-- 流水表同时记 `balance_before` 与 `balance_after` ⇒ 「扣前余额 → 扣后余额」
-- 在**一条流水行**里看得见，不靠翻别的表推。
--
-- 并发扣减有**两层** DB 保证：
--   1. **条件更新**：`update ... set balance = balance - ? where id = ? and balance >= ?`
--      —— 余额不足时**更新 0 行**，服务层据此拒绝；PostgreSQL 在行锁上重新判定
--      谓词 ⇒ 两个并发请求不会都扣成功。
--   2. **表级 CHECK**：`balance >= 0` —— 任何绕过条件更新的写法也进不来。
--
-- ============================================================================
-- 六、发布后不可变的强制位置
-- ============================================================================
--
--   1. 积分账户主键（`account_key`）建后不可改。
--   2. 积分流水**只追加**：UPDATE / DELETE 一律被拒（余额是流水推出来的，不是就地改的）。
--   3. 积分批次的 `balance` **只减不增**（消耗完就是 0，不允许被加回去）。
--   4. 权益核销记录的快照列（兑换时的权益代码 / 名称 / 所需积分 / 权益内容）建后不可改。
--   5. 权益核销状态只能 `REDEEMED → USED`（**单向**，不许回退）。
--
-- ⚠️ 为什么用**触发器**而不是**数据库外键**：
--   同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）会被外键打破。本文件**不给任何既有表建外键**，本文件自己新建的表之间
--   也**一律用触发器做引用校验**，与 V99 / V220 / V228 / V292 / V295 / V299 / V432 / V436 /
--   V440 同一手法。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 / V436 / V440 同一口径：角色可能已由运维预置，
-- 因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;
GRANT USAGE ON SCHEMA public TO starcon_app;

-- 核销号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一次兑换权益就报权限不足」。
CREATE SEQUENCE crm_points_redemption_no_seq START 1;

-- ============================================================================
-- 1. crm_points_rule —— 积分规则（获取 / 消耗 / 估值）+ 权益
-- ============================================================================
-- 三种规则共用一张表，靠 `rule_type` 区分：
--   * EARN      —— 获取规则（消费 / 评价 / 签到 / 手工）
--   * SPEND     —— 消耗规则（抵扣：订单金额最多能用多少积分抵）
--   * VALUATION —— 估值规则（每 1 积分折算多少金额 + 负债币种）
--
-- 🔴 **代码里没有任何写死的积分比率**：拿多少分、抵多少比例、单积分值多少，
--   全部是**租户数据**。
CREATE TABLE crm_points_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 规则标识（**租户自定义**，不是代码写死的枚举）=====
  rule_code varchar(48) NOT NULL CHECK (btrim(rule_code) <> ''),
  rule_name varchar(96) NOT NULL CHECK (btrim(rule_name) <> ''),
  rule_type varchar(16) NOT NULL CHECK (rule_type IN ('EARN','SPEND','VALUATION')),

  -- ===== 获取规则专用 =====
  -- 获取来源：PURCHASE 消费 / REVIEW 评价 / CHECKIN 签到 / MANUAL 人工。
  -- 🔴 **可空**：SPEND / VALUATION 规则与获取来源无关，写死一个取值只会让人
  --   误以为那条规则也是「人工发放」。EARN 规则**必须**给（下面有 CHECK）。
  earn_source varchar(16) DEFAULT 'MANUAL'
    CHECK (earn_source IS NULL OR earn_source IN ('PURCHASE','REVIEW','CHECKIN','MANUAL')),
  -- 每「一单位金额」换多少积分（**仅 PURCHASE 用**）：0.01 = 消费 1.00 得 1 分。
  points_per_unit numeric(19,6) CHECK (points_per_unit >= 0),
  -- 固定积分（REVIEW / CHECKIN / MANUAL 用）。
  fixed_points numeric(19,6) CHECK (fixed_points >= 0),
  -- 消费门槛（**仅 PURCHASE 用**）：订单金额达到门槛才给分；门槛必须带币种。
  threshold_amount numeric(19,4) CHECK (threshold_amount >= 0),
  threshold_currency char(3),
  -- 积分有效期（月数）：获取的积分多久过期；NULL = 永不过期。
  valid_months integer CHECK (valid_months > 0),
  -- 每日上限：同一会员同一自然日最多拿多少分；NULL = 不限。
  max_per_day numeric(19,6) CHECK (max_per_day >= 0),

  -- ===== 消耗规则专用 =====
  -- 抵扣率上限：0.05 = 订单金额最多用积分抵掉 5%（NULL = 该规则不允许抵扣）。
  max_deduct_rate numeric(9,6) CHECK (max_deduct_rate >= 0 AND max_deduct_rate <= 1),
  -- 单笔最少抵扣积分：低于这个数就不值得走一次抵扣（NULL = 不限）。
  min_deduct_points numeric(19,6) CHECK (min_deduct_points >= 0),

  -- ===== 估值规则专用 =====
  -- 每 1 积分折算多少金额（负债口径）。🔴 **不是代码常量**，是租户配的。
  point_value_rate numeric(19,6) CHECK (point_value_rate >= 0),
  -- 负债币种（与 point_value_rate 同生共死）。
  liability_currency char(3),

  enabled boolean NOT NULL DEFAULT true,

  -- ===== 生效区间（改规则不改历史）=====
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  -- 备注：写清「这条规则为什么是这条规则」（例：消费满 100 USD 给 1 分，12 个月有效）。
  note varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 同一规则代码 + 同一生效起点 ⇒ 只有一条。
  UNIQUE (tenant_id, rule_code, effective_from),

  -- 门槛与币种**同生共死**（跨币种的金额不能比）。
  CHECK ((threshold_amount IS NULL) = (threshold_currency IS NULL)),
  -- 生效区间必须自洽。
  CHECK (effective_to IS NULL OR effective_to > effective_from),
  -- 获取规则：固定积分与按金额换算**至少一个**非空（两个都空 = 这条规则发不出积分）。
  CHECK (rule_type <> 'EARN' OR fixed_points IS NOT NULL OR points_per_unit IS NOT NULL),
  -- 获取规则**必须**写明获取来源（不知道来源就不知道该不该发这一笔）。
  CHECK (rule_type <> 'EARN' OR earn_source IS NOT NULL),
  -- 消费获取必须按金额换算（没有金额就没有「消费」这回事）。
  CHECK (rule_type <> 'EARN' OR earn_source <> 'PURCHASE' OR points_per_unit IS NOT NULL),
  -- 消耗规则：抵扣率必填（否则「最多抵多少」没有答案）。
  CHECK (rule_type <> 'SPEND' OR max_deduct_rate IS NOT NULL),
  -- 估值规则：单积分价值与币种**同生共死**（没有币种的金额不是负债）。
  CHECK (rule_type <> 'VALUATION'
    OR (point_value_rate IS NOT NULL AND liability_currency IS NOT NULL))
);

-- 🔴 同一规则代码**同时只能有一条「开着」的规则**（effective_to 为空）。
--   改了门槛要新建一条（新的生效起点），旧的那条被关掉 —— 不可变优先。
CREATE UNIQUE INDEX crm_points_rule_open_code
  ON crm_points_rule(tenant_id, rule_code)
  WHERE effective_to IS NULL;

-- 🔴 **一个租户同时只能有一条「开着」的估值规则** —— 负债折算口径必须唯一，
--   否则「积分负债汇总」会有两个答案，财务侧没法对账。
CREATE UNIQUE INDEX crm_points_rule_open_valuation
  ON crm_points_rule(tenant_id)
  WHERE rule_type = 'VALUATION' AND effective_to IS NULL;

-- ============================================================================
-- 2. crm_points_account —— 积分账户
-- ============================================================================
-- 账户挂在 **15.6.1.1 的会员身份**上：account_key 与 member_key 同一口径
-- （已合并 = 逻辑客户号；未合并 = `来源店铺 客户标识`）。
CREATE TABLE crm_points_account (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 账户主键（与 15.6.1.1 的 member_key 同一口径）。
  -- 🔴 建后不可改（触发器 6.1）：它是「这个人的积分钱包」的身份。
  account_key varchar(160) NOT NULL CHECK (btrim(account_key) <> ''),
  -- 会员主键（15.6.1.1 的 member_key；**逻辑引用**，不建跨表外键）。
  member_key varchar(160) NOT NULL CHECK (btrim(member_key) <> ''),
  -- 会员档案快照（客户标识 / 来源店铺 / 买家名）。
  customer_ref varchar(128) NOT NULL DEFAULT '',
  source_shop varchar(64) NOT NULL DEFAULT '',
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- 🔴 **余额永不为负**：DB 层硬保证（并发扣减的第二层保险）。
  balance numeric(19,6) NOT NULL DEFAULT 0 CHECK (balance >= 0),
  -- 累计获取 / 累计消耗 / 累计过期（**只增不减**，报表口径）。
  earned_total numeric(19,6) NOT NULL DEFAULT 0 CHECK (earned_total >= 0),
  spent_total numeric(19,6) NOT NULL DEFAULT 0 CHECK (spent_total >= 0),
  expired_total numeric(19,6) NOT NULL DEFAULT 0 CHECK (expired_total >= 0),

  last_earned_at timestamptz,
  last_spent_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴 账户主键在租户内唯一（一个人一个积分钱包）。
  UNIQUE (tenant_id, account_key)
);

-- ============================================================================
-- 3. crm_points_lot —— 积分批次（**有效期**的载体）
-- ============================================================================
-- 一次获取 = 一个批次。批次带 `expires_at`（**绝对时刻**）与 `balance`。
--
-- 🔴 列名口径：批次这一列叫 `balance`，**不叫 `remaining`** —— CRM 服务有一条 schema 级
--    守卫（ServiceTicketListIntegrationTest），不许出现名字里带 `remaining`/`remain` 的列：
--    那类列是「写下去就开始自己过期」的剩余值（如「剩余小时数」）。本列**不会自己过期** ——
--    它只在三个**显式事件**里变：获取（earn）、消耗（spend）、到期核销（POST /crm/points/expire，
--    把已到期批次的未消耗额写销账：减账户 balance、加 expired_total、写一条 EXPIRE 流水）。
--    「过期」在本设计里是**被显式物化**的，不是靠墙钟自己发生的。
--    命名与同模块的 `crm_points_account.balance`（同样是「随消耗递减」的存量余额）一致。
-- 消耗按**到期最早先消耗**（先到期先花）—— 这是积分有效期的正确语义。
CREATE TABLE crm_points_lot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 所属账户（**逻辑引用**；触发器校验它真的存在）。
  account_id uuid NOT NULL,
  account_key varchar(160) NOT NULL,
  member_key varchar(160) NOT NULL,
  -- 产生这批积分的流水行（**逻辑引用**）。
  source_ledger_id uuid NOT NULL,

  -- 本批获取的积分。
  amount numeric(19,6) NOT NULL CHECK (amount > 0),
  -- 🔴 剩余可消耗积分：只减不增（触发器 8.4）。
  balance numeric(19,6) NOT NULL CHECK (balance >= 0 AND balance <= amount),
  -- 🔴 **有效期按绝对时刻判定**：expires_at <= now() 即为过期。
  --   NULL = 永不过期。
  expires_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- ============================================================================
-- 4. crm_points_ledger —— 积分流水（**只追加**）
-- ============================================================================
-- 🔴 每一行都带 `balance_before` / `balance_after` ⇒ 「扣前余额 → 扣后余额」
--   在一行里看得见。流水**不可改、不可删**（触发器 6.4）。
CREATE TABLE crm_points_ledger (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  account_id uuid NOT NULL,
  account_key varchar(160) NOT NULL,
  member_key varchar(160) NOT NULL,

  -- 方向：EARN 获取 / SPEND 消耗 / EXPIRE 过期。
  direction varchar(8) NOT NULL CHECK (direction IN ('EARN','SPEND','EXPIRE')),
  -- 变动数量（**恒为正**；方向由 direction 表达）。
  amount numeric(19,6) NOT NULL CHECK (amount > 0),
  -- 🔴 扣前余额 → 扣后余额（可审阅的关键两列）。
  balance_before numeric(19,6) NOT NULL CHECK (balance_before >= 0),
  balance_after numeric(19,6) NOT NULL CHECK (balance_after >= 0),

  -- 命中的规则行（**逻辑引用**）。
  rule_id uuid,
  -- 事由：PURCHASE 消费 / REVIEW 评价 / CHECKIN 签到 / MANUAL 人工 /
  --        DEDUCT 抵扣 / REDEEM 兑换 / EXPIRE 过期。
  reason_code varchar(16) NOT NULL
    CHECK (reason_code IN ('PURCHASE','REVIEW','CHECKIN','MANUAL','DEDUCT','REDEEM','EXPIRE')),

  -- 外部事实的**逻辑引用**（订单号 / 评价号 / 权益编码）—— 不是跨库 FK。
  reference_type varchar(16) NOT NULL DEFAULT ''
    CHECK (reference_type IN ('','ORDER','REVIEW','ENTITLEMENT')),
  reference_id varchar(128) NOT NULL DEFAULT '',
  -- 权益核销时指向的权益行（**逻辑引用**）。
  entitlement_id uuid,

  -- 事件发生的时刻（**绝对时刻**）。
  occurred_at timestamptz NOT NULL,
  operator_id uuid,
  note varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 🔴 **同一个订单 / 同一条评价只允许发一次积分**。
--   这条部分唯一索引是「重复发分」的 DB 层保险 ——
--   没有它，两个并发请求能让同一笔消费拿到两份积分。
CREATE UNIQUE INDEX crm_points_ledger_reference_uniq
  ON crm_points_ledger(tenant_id, reference_type, reference_id)
  WHERE direction = 'EARN' AND reference_type IN ('ORDER','REVIEW');

-- ============================================================================
-- 5. crm_points_entitlement —— 权益目录
-- ============================================================================
-- 「可兑换的权益」是**租户数据**：兑换要多少积分、给什么权益、还剩多少份，
-- 全部由租户配，代码里不写死任何一份权益。
CREATE TABLE crm_points_entitlement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  entitlement_code varchar(48) NOT NULL CHECK (btrim(entitlement_code) <> ''),
  entitlement_name varchar(96) NOT NULL CHECK (btrim(entitlement_name) <> ''),
  -- 兑换所需积分。
  point_cost numeric(19,6) NOT NULL CHECK (point_cost > 0),

  -- 权益形态：DISCOUNT 折扣 / FREE_SHIPPING 免邮 / PHYSICAL_GIFT 实物 / COUPON 券。
  benefit_type varchar(16) NOT NULL
    CHECK (benefit_type IN ('DISCOUNT','FREE_SHIPPING','PHYSICAL_GIFT','COUPON')),
  -- 权益内容（折扣率 / 面额 …；免邮权益为 NULL）。
  benefit_value numeric(19,6) CHECK (benefit_value >= 0),
  -- 面额币种（与 benefit_value 同生共死；折扣率不需要币种）。
  benefit_currency char(3),

  -- 剩余库存（NULL = 不限）。
  stock integer CHECK (stock >= 0),
  -- 每人限兑份数（NULL = 不限）。
  per_member_limit integer CHECK (per_member_limit > 0),

  enabled boolean NOT NULL DEFAULT true,

  -- 生效区间（改权益不改历史）。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  note varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, entitlement_code, effective_from),

  -- 面额与币种**同生共死**。
  CHECK ((benefit_value IS NULL) = (benefit_currency IS NULL)),
  -- 生效区间必须自洽。
  CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- ============================================================================
-- 6. crm_points_redemption —— 权益核销记录
-- ============================================================================
-- 兑换 = 扣积分 + 落一条 REDEEMED 记录；核销 = 把它标成 USED。
-- 权益内容以**快照**落库（兑换当时是什么，就是什么）—— 之后改权益目录不影响历史。
CREATE TABLE crm_points_redemption (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- PR-yyyyMMdd-000001（序列单独授权，见授权段）。
  redemption_no varchar(32) NOT NULL DEFAULT '',

  account_id uuid NOT NULL,
  account_key varchar(160) NOT NULL,
  member_key varchar(160) NOT NULL,

  -- 权益快照（**建后不可改**，触发器 6.5）。
  entitlement_id uuid NOT NULL,
  entitlement_code varchar(48) NOT NULL,
  entitlement_name varchar(96) NOT NULL,
  point_cost numeric(19,6) NOT NULL CHECK (point_cost > 0),
  -- 权益内容快照（jsonb）：折扣率 / 面额 / 免邮标记 / 券码。
  benefit_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 状态：REDEEMED 已兑换待核销 / USED 已核销（**单向**，触发器 6.6）。
  status varchar(12) NOT NULL CHECK (status IN ('REDEEMED','USED')),
  -- 兑换时刻（**绝对时刻**）。
  redeemed_at timestamptz NOT NULL,
  -- 核销必须带核销时刻。
  used_at timestamptz,

  -- 核销时关联的订单号（逻辑引用）。
  order_ref varchar(64) NOT NULL DEFAULT '',

  operator_id uuid,
  note varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, redemption_no),

  -- 🔴 「已核销」必须带核销时刻。
  CHECK (status <> 'USED' OR used_at IS NOT NULL)
);

-- ============================================================================
-- 7. 索引
-- ============================================================================
-- 账户默认排序：余额降序（余额多的排最前）+ 账户主键。
CREATE INDEX crm_points_account_by_balance
  ON crm_points_account(tenant_id, balance DESC, account_key);

CREATE INDEX crm_points_account_by_member
  ON crm_points_account(tenant_id, member_key);

CREATE INDEX crm_points_account_by_shop
  ON crm_points_account(tenant_id, source_shop, account_key);

-- 批次：按账户 + 到期时刻（**先到期先消耗**就是靠这条索引定位）。
CREATE INDEX crm_points_lot_by_account
  ON crm_points_lot(tenant_id, account_id, expires_at);

CREATE INDEX crm_points_lot_by_expiry
  ON crm_points_lot(tenant_id, expires_at)
  WHERE balance > 0;

-- 流水默认排序：发生时刻降序。
CREATE INDEX crm_points_ledger_by_occurred
  ON crm_points_ledger(tenant_id, occurred_at DESC, id DESC);

CREATE INDEX crm_points_ledger_by_account
  ON crm_points_ledger(tenant_id, account_id, occurred_at DESC);

CREATE INDEX crm_points_ledger_by_direction
  ON crm_points_ledger(tenant_id, direction, occurred_at DESC);

-- 核销记录：按账户 + 兑换时刻降序。
CREATE INDEX crm_points_redemption_by_account
  ON crm_points_redemption(tenant_id, account_id, redeemed_at DESC);

CREATE INDEX crm_points_redemption_by_entitlement
  ON crm_points_redemption(tenant_id, entitlement_id, redeemed_at DESC);

CREATE INDEX crm_points_redemption_by_created
  ON crm_points_redemption(tenant_id, created_at DESC, id DESC);

-- 权益目录：按编码 + 生效区间。
CREATE INDEX crm_points_entitlement_by_code
  ON crm_points_entitlement(tenant_id, entitlement_code, effective_from DESC);

-- ============================================================================
-- 8. 触发器（**一律不用外键** —— 理由见文件头第六节）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 8.1 guard_crm_points_account_identity · 账户主键建后不可改
-- ---------------------------------------------------------------------------
-- 账户主键是「这个人的积分钱包」的身份：改了它，历史流水里的余额就串到别人身上了。
CREATE FUNCTION guard_crm_points_account_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.account_key IS DISTINCT FROM OLD.account_key THEN
    RAISE EXCEPTION '积分账户主键建后不可改：它是「这个人的积分钱包」的身份' USING ERRCODE='23514';
  END IF;
  IF NEW.member_key IS DISTINCT FROM OLD.member_key THEN
    RAISE EXCEPTION '积分账户的会员主键建后不可改：积分是记在**这个人**名下的' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_points_account_identity_trg
  BEFORE UPDATE ON crm_points_account
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_account_identity();

-- ---------------------------------------------------------------------------
-- 8.2 guard_crm_points_account_ref · 积分账户必须挂在**真实会员**上
-- ---------------------------------------------------------------------------
-- 积分账户的上游是 15.6.1.1 的会员档案。引用校验用**触发器**而不是外键
-- （外键会打破同服务既有测试的 TRUNCATE）。
CREATE FUNCTION guard_crm_points_account_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM crm_membership_profile p
     WHERE p.tenant_id = NEW.tenant_id AND p.member_key = NEW.member_key
  ) THEN
    RAISE EXCEPTION '积分账户必须挂在真实会员档案上：会员 % 不存在（先由 15.6.1.1 建档）', NEW.member_key
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_points_account_ref_trg
  BEFORE INSERT OR UPDATE ON crm_points_account
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_account_ref();

-- ---------------------------------------------------------------------------
-- 8.3 guard_crm_points_lot_ref · 积分批次必须挂在真实账户与真实流水上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_points_lot_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM crm_points_account a
     WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.account_id
  ) THEN
    RAISE EXCEPTION '积分批次必须挂在真实积分账户上：账户 % 不存在', NEW.account_id
      USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM crm_points_ledger l
     WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.source_ledger_id
  ) THEN
    RAISE EXCEPTION '积分批次必须挂在真实积分流水上：流水 % 不存在', NEW.source_ledger_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_points_lot_ref_trg
  BEFORE INSERT ON crm_points_lot
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_lot_ref();

-- ---------------------------------------------------------------------------
-- 8.4 guard_crm_points_lot_balance · 批次剩余量**只减不增**
-- ---------------------------------------------------------------------------
-- 「积分过期就归零」与「消费掉就归零」都只往下走；
-- 把 balance 加回去等于凭空造积分 —— 那必须走一条新的 EARN 流水。
CREATE FUNCTION guard_crm_points_lot_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.balance > OLD.balance THEN
    RAISE EXCEPTION '积分批次的剩余量只减不增：加回积分必须走一条新的获取流水' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_points_lot_balance_trg
  BEFORE UPDATE ON crm_points_lot
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_lot_balance();

-- ---------------------------------------------------------------------------
-- 8.5 guard_crm_points_ledger_ref · 积分流水必须挂在真实账户上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_points_ledger_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM crm_points_account a
     WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.account_id
  ) THEN
    RAISE EXCEPTION '积分流水必须挂在真实积分账户上：账户 % 不存在', NEW.account_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_points_ledger_ref_trg
  BEFORE INSERT ON crm_points_ledger
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_ledger_ref();

-- ---------------------------------------------------------------------------
-- 8.6 guard_crm_points_ledger_append_only · 积分流水**只追加**
-- ---------------------------------------------------------------------------
-- 余额是流水推出来的，不是就地改的。改一笔流水 = 改一个已经发生过的历史事实。
CREATE FUNCTION guard_crm_points_ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '积分流水只追加，不允许修改或删除：余额是流水推出来的' USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_points_ledger_append_only_trg
  BEFORE UPDATE OR DELETE ON crm_points_ledger
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_ledger_append_only();

-- ---------------------------------------------------------------------------
-- 8.7 guard_crm_points_redemption_ref · 核销记录必须挂在真实账户与真实权益上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_points_redemption_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM crm_points_account a
     WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.account_id
  ) THEN
    RAISE EXCEPTION '权益核销记录必须挂在真实积分账户上：账户 % 不存在', NEW.account_id
      USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM crm_points_entitlement e
     WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.entitlement_id
  ) THEN
    RAISE EXCEPTION '权益核销记录必须挂在真实权益上：权益 % 不存在', NEW.entitlement_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_points_redemption_ref_trg
  BEFORE INSERT ON crm_points_redemption
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_redemption_ref();

-- ---------------------------------------------------------------------------
-- 8.8 guard_crm_points_redemption_snapshot · 核销快照建后不可改
-- ---------------------------------------------------------------------------
-- 兑换当时给的是什么，就是什么 —— 之后改权益目录不该改写已发生的兑换记录。
CREATE FUNCTION guard_crm_points_redemption_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.entitlement_code IS DISTINCT FROM OLD.entitlement_code THEN
    RAISE EXCEPTION '权益核销记录的权益代码建后不可改：它是兑换当时的快照' USING ERRCODE='23514';
  END IF;
  IF NEW.entitlement_name IS DISTINCT FROM OLD.entitlement_name THEN
    RAISE EXCEPTION '权益核销记录的权益名称建后不可改：它是兑换当时的快照' USING ERRCODE='23514';
  END IF;
  IF NEW.point_cost IS DISTINCT FROM OLD.point_cost THEN
    RAISE EXCEPTION '权益核销记录的所需积分建后不可改：它是兑换当时的快照' USING ERRCODE='23514';
  END IF;
  IF NEW.benefit_snapshot IS DISTINCT FROM OLD.benefit_snapshot THEN
    RAISE EXCEPTION '权益核销记录的权益内容快照建后不可改：它是兑换当时的快照' USING ERRCODE='23514';
  END IF;
  IF NEW.account_key IS DISTINCT FROM OLD.account_key THEN
    RAISE EXCEPTION '权益核销记录的账户主键建后不可改' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_points_redemption_snapshot_trg
  BEFORE UPDATE ON crm_points_redemption
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_redemption_snapshot();

-- ---------------------------------------------------------------------------
-- 8.9 guard_crm_points_redemption_status · 核销状态**单向**
-- ---------------------------------------------------------------------------
-- REDEEMED → USED 可以；USED → REDEEMED 不行（核销过的事实不许撤回）。
CREATE FUNCTION guard_crm_points_redemption_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'USED' AND NEW.status <> 'USED' THEN
    RAISE EXCEPTION '权益核销状态只能从 REDEEMED 到 USED，不许回退：核销过的事实不能撤回'
      USING ERRCODE='23514';
  END IF;
  IF OLD.status = NEW.status AND NEW.status = 'USED' AND NEW.used_at IS DISTINCT FROM OLD.used_at THEN
    RAISE EXCEPTION '权益核销记录已核销，核销时刻不可改写' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_points_redemption_status_trg
  BEFORE UPDATE ON crm_points_redemption
  FOR EACH ROW EXECUTE FUNCTION guard_crm_points_redemption_status();

-- ============================================================================
-- 9. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 / V436 / V440 同一口径：策略名固定 isolation，
-- USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['crm_points_rule','crm_points_account','crm_points_lot','crm_points_ledger','crm_points_entitlement','crm_points_redemption'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_points_rule, crm_points_account, crm_points_lot, crm_points_ledger, crm_points_entitlement, crm_points_redemption TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_points_rule, crm_points_account, crm_points_lot, crm_points_ledger, crm_points_entitlement, crm_points_redemption TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一次兑换权益就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_points_redemption_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_points_redemption_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 10. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--
--   * **5 定价（积分抵扣）**：本模块**只输出**抵扣口径（抵扣率上限、单积分价值、
--     可用积分余额）—— 不改价目表、不改促销表（跨服务禁止）。
--   * **12 财务（积分负债）**：本模块**只输出**负债汇总（未兑付积分 × 单积分价值）。
--     🔴 **本模块不代劳在财务侧建科目，也不假装已经入账** ——
--     业务库里有一张表 ≠ 账上有一笔负债；能力声明里这一维是 `available=false`，
--     `ownerModule=12`。
--   * **15.6.1.1 会员与等级**：本模块**只读**它的会员档案 —— 积分挂在会员身份上。
--   * **9 订单**：订单事实走**授权只读 HTTP API**，本库只存逻辑引用。
