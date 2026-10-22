-- 15.3.2.1 客户标签：维护客户标签体系（VIP、复购、退货率高、高客单、风险、渠道来源等），
-- 支持自动规则打标（按订单行为）与手工打标，用于筛选与营销。
--
-- 蓝图 15 卷 15.3.2.1 的原话：
--   「职责：维护客户标签体系（VIP、复购、退货率高、高客单、风险、渠道来源等），
--     支持自动规则打标（按订单行为）与手工打标，用于筛选与营销。
--     输入：标签定义；自动打标规则；手工操作。
--     输出：标签记录 + 标签分布统计。
--     关系：上游 = 9 订单、11 售后；下游 = 15.3.1.1 筛选、14.4 促销定向、15.5.2 关怀邮件。
--     **"退货率高"标签要谨慎使用**，避免对正常买家产生歧视性动作。」
--
-- ============================================================ 零、本文件与既有模块的边界
--
-- 本文件是 **crm 数据库的第五个迁移**（V99 = 15.4.2.1 知识库，V220 = 15.2.2.1 索评请求，
-- V228 = 15.2.2.2 索评规则，V292 = 15.1.2.1 买家邮件）。
--
-- 与兄弟模块的边界 —— 两者**不共用任何一张表**，写入所有者也各自独立：
--   * V220 / V228（索评）：回答「要不要向这位买家发起一次索评、用哪套话术」。
--   * V292（买家邮件）：回答「这位买家跟我们说了什么、回过没有」。
--   * V295（本文件）：回答「这位买家属于哪一类人」（VIP / 复购 / 退货率高 / …）。
-- ⇒ 本文件**不改 V99 / V220 / V228 / V292 的任何一张表**（零 ALTER），纯新建。
--
-- ============================================================ 一、跨服务纪律（零跨库 SQL / FK / 视图）
--
-- 上游 **9 订单**与 **11 售后**的**真值都不在本服务**：
--   * 订单事实在 **trade** 服务（`sales_order` / `sales_order_party_snapshot`，端口 8085），
--     读投影端点 `GET /trade/sales-orders/list`；
--   * 售后事实在 **trade** 服务（`aftersales_case`，端口 8085），
--     读投影端点 `GET /trade/aftersales/cases`。
-- 两边与本模块**不同服务、不同数据库** ⇒ AGENTS.md 模块边界纪律：
-- **禁止跨库 SQL、跨库 FK、共享视图**，只走授权 HTTP API。
--
-- 本库因此**只存客户与标签的逻辑引用 + 判定依据快照**：
--   customer_ref / customer_name
-- —— 快照的作用是「不回头回源就能显示」与「复盘时能看到当时依据哪几笔事实」，
-- 它**不构成引用完整性**。
--
-- 🔴 售后事实**不带买家维度**，只有 `order_id` ⇒ 归因链路是
-- 「售后单 → order_id → 订单 → customer_party_id → 客户」。
-- 这条链路**只在服务层做**（一次拉取内建索引），本库**不建任何指向 trade 的外键**。
--
-- 下游 **15.3.1.1 客户筛选**、**14.4 促销定向**、**15.5.2 关怀邮件**（15.5.2.1 未开发）
-- **全部未交付** ⇒ 本模块**只产出标签记录与分布统计**，**不执行任何动作**：
--   * 标签**不触发**任何对买家的行为（不屏蔽、不拒绝、不降权、不改价）；
--   * capabilities 里 `customer_segmentation` / `promotion_targeting` / `care_email`
--     三个维度显式标 available=false（ownerModule 分别为 15.3.1.1 / 14.4 / 15.5.2.1）。
--
-- ============================================================ 二、加粗句「"退货率高"标签要谨慎使用」的落点
--
-- 蓝图把这句话写成**加粗句**，它必须落成**产品行为上的约束**，不是注释。本文件用四道闸：
--
--   1. **样本量门槛**（`crm_customer_tag_rule.min_sample`）：订单数不足 ⇒ 结论是
--      **INDETERMINATE（不可判定）**，**不是**「是」。一个人的一单退货率是 100%，
--      那不是「退货率高的买家」，那是「样本不足」。
--   2. **必须显著高于租户基线**（`baseline_multiplier` > 1，且 `baseline` 由本租户
--      同一时间窗的真实订单 / 售后事实算出）：只比「绝对值高」是不够的 ——
--      全平台都在涨退货率的时候，一个略高于均值的买家并不特殊。
--   3. **必须带原因与证据**（`crm_customer_tag_assignment.reason` NOT NULL +
--      `evidence` jsonb）：`guard_crm_customer_tag_assignment_reason` 触发器强制
--      —— 标签定义上 `contestable=true`（退货率高 / 风险）或 `review_required=true` 时，
--      落库**必须**带非空原因，否则 DB 拒绝。人工能据此复核，也能据此纠正。
--   4. **不得触发歧视性动作**：本库**没有**任何「按标签执行动作」的表 / 端点。
--      标签只是一个**可读的事实**；谁要拿它去筛人群、去发促销、去发关怀邮件，
--      由下游模块（15.3.1.1 / 14.4 / 15.5.2.1）**自己**去实现并承担那个责任。
--      本模块**刻意不提供**「按标签筛出人群」端点（不许假装已筛出人群）。
--
-- ============================================================ 三、五张表：定义 / 规则 / 记录 / 运行 / 判定
--
--   crm_customer_tag            标签定义（VIP、复购、退货率高、高客单、风险、渠道来源…）
--   crm_customer_tag_rule        自动打标规则（判定口径 + 阈值 + 样本门槛 + 统计窗口）
--   crm_customer_tag_assignment  标签记录（客户 × 标签，**人工与自动来源可分**）
--   crm_customer_tag_run         一次规则评估的运行记录（窗口 / 计数 / 基线 / 截断标记）
--   crm_customer_tag_evaluation  逐客户判定结论（**含「不可判定」**，可审计）
--
-- 为什么「运行」与「判定结论」各一张表：
--   * 运行记录回答「这次评估扫了多少客户、打了多少标、基线是多少、扫描被截断没有」；
--   * 判定结论回答「**这一位**客户凭什么被判成 / 没被判成 / 不可判定」。
--     「样本不足 ⇒ 不可判定」这件事如果只存在运行汇总里，就没法回答
--     「为什么张三没有退货率高的标签」—— 而那正是运营要问的第一个问题。
--
-- ============================================================ 四、幂等与「自动不覆盖人工」
--
-- `crm_customer_tag_assignment` 的唯一键是
--   UNIQUE (tenant_id, customer_ref, tag_id, origin_key)
-- 而 `origin_key` 的取值是：
--   * 人工打标 ⇒ 恒为 'MANUAL'
--   * 自动打标 ⇒ 恒为 'RULE:<rule_code>'
-- ⇒ 同一个客户、同一枚标签，**人工与自动各占一行**：
--   * 自动重跑**只更新自己产出的那一行**（origin_key='RULE:xxx'），
--     **绝不覆盖人工标的标签**（origin_key='MANUAL' 是另一行）；
--   * 同一批事实重跑 ⇒ 命中同一行 ⇒ **不重复打标**（幂等），
--     运行记录里体现为 `unchanged_count` 而不是 `tagged_count`；
--   * 人工已撤销（state='REVOKED'）的那一行，自动重跑**不恢复** ——
--     人工复核的结论优先于自动规则。
--
-- ============================================================ 五、禁浮点与时间纪律
--
--   * 阈值 / 倍数 / 样本量 / 窗口天数：一律 **numeric(19,6)** + BigDecimal，**禁浮点**。
--   * 计数（扫了多少客户、打了多少标）：**integer**（纯计数，无小数位）。
--   * 金额：只出现在 `evidence` jsonb 里（BigDecimal 序列化成 JSON 数字，
--     PostgreSQL 的 jsonb 数字是 **numeric**，不丢精度）。
--   * 时间：一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区静默偏 −28800 秒。
--
-- ============================================================ 六、发布后不可变的强制位置
--
--   1. 标签记录的身份列（customer_ref / tag_id / origin_key）建后不可改：
--      改了之后「这位客户被判成过什么」在历史上会出现两个答案。
--   2. 已被规则或标签记录引用的标签 / 规则**不可删** ——
--      删掉之后判定依据与标签分布里的那一段历史会凭空消失
--      （不是「少了几条」，是**结论被改写**）。
--
-- ⚠️ 为什么用**触发器回表**而不是**数据库外键**：
--   同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）会被外键打破。本文件**不给任何既有表建外键**，本文件自己新建的表之间
--   也**一律用触发器做引用校验**，与 V99 / V220 / V228 / V292 同一手法。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 同一口径：角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
END $$;

-- 运行号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一次跑规则就报权限不足」。
CREATE SEQUENCE crm_customer_tag_run_no_seq START 1;

-- ============================================================================
-- 1. crm_customer_tag —— 标签定义
-- ============================================================================
-- 分类（category）照蓝图点名的六类标签归位：
--   VALUE    价值分层（VIP、高客单）
--   BEHAVIOR 行为（复购、首单）
--   RISK     风险与售后（风险、退货率高）
--   SOURCE   渠道来源
--   CUSTOM   自定义
CREATE TABLE crm_customer_tag (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 标签编码：租户内唯一，**建后不可改**（改了之后历史记录上的编码会指错标签）。
  tag_code varchar(64) NOT NULL,
  name varchar(120) NOT NULL,
  category varchar(24) NOT NULL
    CHECK (category IN ('VALUE','BEHAVIOR','RISK','SOURCE','CUSTOM')),
  description varchar(600) NOT NULL DEFAULT '',
  -- 展示色（前端只原样展示，不在这里做算术）。
  color varchar(32) NOT NULL DEFAULT '',

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),

  -- 🔴 可争议标签（退货率高 / 风险）：打上之后**必须**人工复核，
  --   且落库时**必须**带原因（guard_crm_customer_tag_assignment_reason 强制）。
  contestable boolean NOT NULL DEFAULT false,
  -- 打上之后是否必须人工复核才生效。
  review_required boolean NOT NULL DEFAULT false,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, tag_code)
);

CREATE INDEX crm_customer_tag_by_status
  ON crm_customer_tag(tenant_id, status, category, tag_code);

-- ============================================================================
-- 2. crm_customer_tag_rule —— 自动打标规则
-- ============================================================================
-- 判定口径（metric）五种，逐条对应蓝图点名的标签：
--   ORDER_COUNT      窗口内订单数 ≥ threshold
--   REPEAT_PURCHASE  窗口内订单数 ≥ threshold（复购，threshold 通常是 2）
--   HIGH_VALUE       窗口内**该币种**累计消费 ≥ threshold（金额不跨币种加）
--   RETURN_RATE      窗口内售后单数 / 订单数 ≥ 租户基线 × baseline_multiplier
--   CHANNEL_SOURCE   窗口内出现过的渠道来源里含 condition_value
--
-- 🔴 RETURN_RATE 的两道闸（加粗句的物理落点）：
--   * min_sample —— 订单数不足 min_sample ⇒ **不可判定**（不是「是」）；
--   * baseline_multiplier —— 必须**显著高于**租户基线（基线由同窗真实事实算出）。
CREATE TABLE crm_customer_tag_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则编码：租户内唯一，**建后不可改**（它是 origin_key 的一部分）。
  rule_code varchar(64) NOT NULL,
  name varchar(120) NOT NULL,
  -- 命中后打哪枚标签（触发器校验，不建外键）。
  tag_id uuid NOT NULL,

  metric varchar(24) NOT NULL
    CHECK (metric IN ('ORDER_COUNT','REPEAT_PURCHASE','HIGH_VALUE','RETURN_RATE','CHANNEL_SOURCE')),
  -- 数值型口径的阈值。
  threshold numeric(19,6),
  -- HIGH_VALUE 的币种（**金额按币种分开判定**，不跨币种加）。
  currency varchar(16),
  -- 字符串型口径（CHANNEL_SOURCE）的取值。
  condition_value varchar(120),

  -- 🔴 最低样本量：低于它 ⇒ 结论「不可判定」。
  min_sample numeric(19,6) NOT NULL DEFAULT 5 CHECK (min_sample >= 1),
  -- 统计窗口天数（1..1095，即最长三年）。
  window_days numeric(19,6) NOT NULL DEFAULT 365 CHECK (window_days >= 1 AND window_days <= 1095),
  -- 🔴 「显著高于基线」的倍数，必须 > 1（等于 1 就是「不低于基线」，那不叫显著）。
  baseline_multiplier numeric(19,6) NOT NULL DEFAULT 2 CHECK (baseline_multiplier > 1),

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  note varchar(600) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, rule_code),

  -- 数值型口径必须给阈值；字符串型口径必须给条件值。两者互斥，避免留一个永不生效的口径。
  CHECK (
    (metric IN ('ORDER_COUNT','REPEAT_PURCHASE','HIGH_VALUE','RETURN_RATE') AND threshold IS NOT NULL)
    OR (metric = 'CHANNEL_SOURCE' AND condition_value IS NOT NULL)
  ),
  -- 金额口径必须钉死币种。
  CHECK (metric <> 'HIGH_VALUE' OR currency IS NOT NULL),
  -- 字符串口径不得带阈值（带了就是两套条件，得说清哪套说了算）。
  CHECK (metric <> 'CHANNEL_SOURCE' OR threshold IS NULL)
);

CREATE INDEX crm_customer_tag_rule_by_status
  ON crm_customer_tag_rule(tenant_id, status, metric, rule_code);

-- ============================================================================
-- 3. crm_customer_tag_assignment —— 标签记录（客户 × 标签）
-- ============================================================================
CREATE TABLE crm_customer_tag_assignment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 客户标识：来自上游订单事实里的 customer_party_id（catalog 03 客户档案的逻辑 id）。
  -- 也接受运营手工填写的其它标识 —— 它是**逻辑引用**，不是跨库外键。
  customer_ref varchar(128) NOT NULL,
  -- 客户名快照（订单事实里的 buyer name）。
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- 标签（触发器校验，不建外键）。
  tag_id uuid NOT NULL,

  -- 🔴 来源可分：MANUAL = 人工打标；RULE = 自动规则打标。
  source varchar(16) NOT NULL CHECK (source IN ('MANUAL','RULE')),
  -- 🔴 origin_key：MANUAL 恒为 'MANUAL'；RULE 恒为 'RULE:<rule_code>'。
  --   唯一键含它 ⇒ 自动重跑**只覆盖自己产出的那部分**，**绝不覆盖人工标的标签**。
  origin_key varchar(96) NOT NULL,
  -- 自动来源挂的规则（人工来源恒为 NULL）。
  rule_id uuid,

  -- ACTIVE = 生效；REVOKED = 已被人工复核撤销（保留记录，不物理删）。
  state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','REVOKED')),

  -- 🔴 原因：可争议标签（退货率高 / 风险）**必须**非空（触发器强制）。
  reason varchar(600) NOT NULL DEFAULT '',
  -- 🔴 判定依据快照：这次是依据**哪几笔订单 / 哪几张售后单**算出来的。
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 待人工复核（标签定义 review_required=true 时置位）。
  pending_review boolean NOT NULL DEFAULT false,
  -- 复核人 / 复核时刻。
  decided_by uuid,
  decided_at timestamptz,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴 幂等与「不覆盖人工」都靠这一条唯一键。
  UNIQUE (tenant_id, customer_ref, tag_id, origin_key),

  -- 自动来源必须挂规则；人工来源不得挂规则。
  CHECK ((source = 'RULE' AND rule_id IS NOT NULL) OR (source = 'MANUAL' AND rule_id IS NULL)),
  -- origin_key 与 source 必须自洽（否则 origin_key 这条幂等防线可以被绕过）。
  CHECK (
    (source = 'MANUAL' AND origin_key = 'MANUAL')
    OR (source = 'RULE' AND origin_key LIKE 'RULE:%')
  ),
  -- 已撤销的行必须说清是谁撤的、什么时候撤的。
  CHECK (state <> 'REVOKED' OR decided_at IS NOT NULL)
);

-- 分布统计按标签聚合走这条索引。
CREATE INDEX crm_customer_tag_assignment_by_tag
  ON crm_customer_tag_assignment(tenant_id, tag_id, state);
-- 客户维度检索（15.3.1.1 将来筛人群会走这条）。
CREATE INDEX crm_customer_tag_assignment_by_customer
  ON crm_customer_tag_assignment(tenant_id, customer_ref, state);
-- 规则维度（某条规则产出过哪些标签记录）。
CREATE INDEX crm_customer_tag_assignment_by_rule
  ON crm_customer_tag_assignment(tenant_id, rule_id)
  WHERE rule_id IS NOT NULL;

-- ============================================================================
-- 4. crm_customer_tag_run —— 一次规则评估的运行记录
-- ============================================================================
CREATE TABLE crm_customer_tag_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  run_no varchar(32) NOT NULL,
  rule_id uuid NOT NULL,
  -- 规则编码快照：规则改名 / 被删之后，历史上那次跑的是哪条规则仍可读。
  rule_code varchar(64) NOT NULL,

  -- SUCCEEDED = 跑完了；FAILED = 上游读不到 / 中途出错（failure_reason 有话可说）。
  status varchar(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),

  -- 统计窗口（半开区间 [window_from, window_to)）。
  window_from timestamptz NOT NULL,
  window_to timestamptz NOT NULL,

  -- 参与评估的客户数。
  customer_count integer NOT NULL DEFAULT 0,
  -- 本次**新**打上的标签数。
  tagged_count integer NOT NULL DEFAULT 0,
  -- 本次**已经打过**（幂等命中）的标签数 —— 重跑时它涨，tagged_count 不涨。
  unchanged_count integer NOT NULL DEFAULT 0,
  -- 本次被自动撤销的标签数（不再命中）。
  revoked_count integer NOT NULL DEFAULT 0,
  -- **不可判定**的客户数（样本不足）。
  indeterminate_count integer NOT NULL DEFAULT 0,
  -- 人工已撤销、自动**没有**恢复的标签数。
  revoked_skipped_count integer NOT NULL DEFAULT 0,
  -- 上游扫描被截断（扫到上限了）—— 静默少算比报错更糟，必须报出来。
  truncated boolean NOT NULL DEFAULT false,
  -- 租户基线（RETURN_RATE 用；其它口径为 NULL）。
  baseline numeric(19,6),
  -- 这次评估的事实汇总（订单数 / 售后单数 / 无法归因的售后单数 / 扫描上限…）。
  facts jsonb NOT NULL DEFAULT '{}'::jsonb,

  failure_reason varchar(600) NOT NULL DEFAULT '',
  operator_id uuid,
  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, run_no),
  CHECK (window_from < window_to)
);

CREATE INDEX crm_customer_tag_run_by_rule
  ON crm_customer_tag_run(tenant_id, rule_id, started_at DESC);

-- ============================================================================
-- 5. crm_customer_tag_evaluation —— 逐客户判定结论
-- ============================================================================
-- outcome 六个取值，逐个说清「这一位客户为什么是这个结论」：
--   TAGGED          命中 ⇒ 打了标签
--   ALREADY_TAGGED  命中 ⇒ 这枚标签已经在（幂等，不重复打）
--   REVOKED         本次不再命中 ⇒ 撤销了**自动产出**的那一枚
--   SKIPPED_REVOKED 本次仍命中，但人工已撤销 ⇒ **不恢复**（人工优先）
--   INDETERMINATE   **不可判定**（样本不足）—— 不是「否」，也不是「是」
--   NOT_MATCHED     判定为不命中
CREATE TABLE crm_customer_tag_evaluation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  run_id uuid NOT NULL,
  rule_id uuid NOT NULL,

  customer_ref varchar(128) NOT NULL,
  customer_name varchar(200) NOT NULL DEFAULT '',

  outcome varchar(24) NOT NULL
    CHECK (outcome IN ('TAGGED','ALREADY_TAGGED','REVOKED','SKIPPED_REVOKED','INDETERMINATE','NOT_MATCHED')),
  reason varchar(600) NOT NULL DEFAULT '',
  -- 逐客户的判定依据快照。
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

CREATE INDEX crm_customer_tag_evaluation_by_run
  ON crm_customer_tag_evaluation(tenant_id, run_id, customer_ref);
CREATE INDEX crm_customer_tag_evaluation_by_outcome
  ON crm_customer_tag_evaluation(tenant_id, run_id, outcome);

-- ============================================================================
-- 6. 触发器（**一律不用外键** —— 理由见文件头第六节）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 6.1 guard_crm_customer_tag_rule_target · 规则引用的标签必须存在
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_tag_rule_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_code varchar(64);
BEGIN
  SELECT tag_code INTO v_code FROM crm_customer_tag
   WHERE tenant_id = NEW.tenant_id AND id = NEW.tag_id;
  IF v_code IS NULL THEN
    RAISE EXCEPTION '标签 % 不存在（tenant=%），规则不能挂在不存在的标签上',
      NEW.tag_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_tag_rule_target_trg
  BEFORE INSERT OR UPDATE ON crm_customer_tag_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_rule_target();

-- ---------------------------------------------------------------------------
-- 6.2 guard_crm_customer_tag_rule_identity · 规则编码建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：origin_key 里有规则编码。改了它之后「这条自动标签是哪条规则产出的」
-- 在历史上会出现两个答案，而幂等重跑的落点也跟着漂。
CREATE FUNCTION guard_crm_customer_tag_rule_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_code IS DISTINCT FROM OLD.rule_code THEN
    RAISE EXCEPTION '规则编码建后不可改：改它会让「这条自动标签是哪条规则产出的」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_tag_rule_identity_trg
  BEFORE UPDATE ON crm_customer_tag_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_rule_identity();

-- ---------------------------------------------------------------------------
-- 6.3 guard_crm_customer_tag_assignment_target · 标签记录必须挂在存在的标签 / 规则上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_tag_assignment_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_code varchar(64); v_rule varchar(64);
BEGIN
  SELECT tag_code INTO v_code FROM crm_customer_tag
   WHERE tenant_id = NEW.tenant_id AND id = NEW.tag_id;
  IF v_code IS NULL THEN
    RAISE EXCEPTION '标签 % 不存在（tenant=%），不能给它挂标签记录',
      NEW.tag_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  IF NEW.rule_id IS NOT NULL THEN
    SELECT rule_code INTO v_rule FROM crm_customer_tag_rule
     WHERE tenant_id = NEW.tenant_id AND id = NEW.rule_id;
    IF v_rule IS NULL THEN
      RAISE EXCEPTION '规则 % 不存在（tenant=%），自动标签记录必须挂一条存在的规则',
        NEW.rule_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_tag_assignment_target_trg
  BEFORE INSERT OR UPDATE ON crm_customer_tag_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_assignment_target();

-- ---------------------------------------------------------------------------
-- 6.4 guard_crm_customer_tag_assignment_reason · 可争议标签必须带原因
-- ---------------------------------------------------------------------------
-- 🔴 加粗句「"退货率高"标签要谨慎使用」的第二道闸：
--   标签定义上 contestable（退货率高 / 风险）或 review_required 为真时，
--   落库**必须**带非空原因 —— 没有原因的「退货率高」标签是没法复核的，
--   复核不了就意味着没法纠正，纠正不了就意味着它在悄悄决定对谁做什么。
CREATE FUNCTION guard_crm_customer_tag_assignment_reason() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_contestable boolean; v_review boolean;
BEGIN
  SELECT contestable, review_required INTO v_contestable, v_review FROM crm_customer_tag
   WHERE tenant_id = NEW.tenant_id AND id = NEW.tag_id;
  IF v_contestable IS NULL THEN
    RETURN NEW; -- 标签不存在由 6.3 负责报错。
  END IF;
  IF (v_contestable OR v_review) AND btrim(NEW.reason) = '' THEN
    RAISE EXCEPTION '这枚标签（contestable=% / review_required=%）必须写明原因：没有原因的可争议标签没法复核，也没法纠正',
      v_contestable, v_review USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_tag_assignment_reason_trg
  BEFORE INSERT OR UPDATE ON crm_customer_tag_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_assignment_reason();

-- ---------------------------------------------------------------------------
-- 6.5 guard_crm_customer_tag_assignment_identity · 标签记录的身份列建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：这一行是「这位客户被判成过什么」的凭据。改了客户 / 标签 / 来源键之后，
-- 「幂等重跑会不会重复打标」的判定在历史上会出现两个答案。
CREATE FUNCTION guard_crm_customer_tag_assignment_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.customer_ref IS DISTINCT FROM OLD.customer_ref THEN
    RAISE EXCEPTION '标签记录的客户建后不可改：改它会让「这位客户被判成过什么」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.tag_id IS DISTINCT FROM OLD.tag_id THEN
    RAISE EXCEPTION '标签记录的标签建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.origin_key IS DISTINCT FROM OLD.origin_key THEN
    RAISE EXCEPTION '标签记录的来源键建后不可改：改它会让「幂等重跑会不会重复打标」的判定在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.source IS DISTINCT FROM OLD.source THEN
    RAISE EXCEPTION '标签记录的来源建后不可改' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_tag_assignment_identity_trg
  BEFORE UPDATE ON crm_customer_tag_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_assignment_identity();

-- ---------------------------------------------------------------------------
-- 6.6 guard_crm_customer_tag_run_target · 运行记录必须挂在存在的规则上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_tag_run_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_code varchar(64);
BEGIN
  SELECT rule_code INTO v_code FROM crm_customer_tag_rule
   WHERE tenant_id = NEW.tenant_id AND id = NEW.rule_id;
  IF v_code IS NULL THEN
    RAISE EXCEPTION '规则 % 不存在（tenant=%），不能给它记运行记录',
      NEW.rule_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_tag_run_target_trg
  BEFORE INSERT ON crm_customer_tag_run
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_run_target();

-- ---------------------------------------------------------------------------
-- 6.7 guard_crm_customer_tag_evaluation_target · 判定结论必须挂在存在的运行记录上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_tag_evaluation_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  SELECT run_no INTO v_no FROM crm_customer_tag_run
   WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '运行记录 % 不存在（tenant=%），不能给它记判定结论',
      NEW.run_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_tag_evaluation_target_trg
  BEFORE INSERT ON crm_customer_tag_evaluation
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_evaluation_target();

-- ---------------------------------------------------------------------------
-- 6.8 guard_crm_customer_tag_delete · 被引用的标签删不掉
-- ---------------------------------------------------------------------------
-- 为什么：标签是**配置**（删了就是删了），但**已经被标签记录引用过的**一旦删掉，
-- 「这位客户身上有哪枚标签」在历史里就只剩一个孤零零的 tag_id ——
-- 结论被改写，而不是少了几条。
CREATE FUNCTION guard_crm_customer_tag_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_count integer; v_rule integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_customer_tag_assignment
   WHERE tenant_id = OLD.tenant_id AND tag_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '标签 % 已被 % 条标签记录引用，删不掉：删掉它会让「这位客户身上有哪枚标签」在历史里无从查证。可以改成 DISABLED 停用',
      OLD.tag_code, v_count USING ERRCODE='23514';
  END IF;
  SELECT count(*) INTO v_rule FROM crm_customer_tag_rule
   WHERE tenant_id = OLD.tenant_id AND tag_id = OLD.id;
  IF v_rule > 0 THEN
    RAISE EXCEPTION '标签 % 已被 % 条规则引用，删不掉：可以改成 DISABLED 停用',
      OLD.tag_code, v_rule USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_customer_tag_delete_trg
  BEFORE DELETE ON crm_customer_tag
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_delete();

-- ---------------------------------------------------------------------------
-- 6.9 guard_crm_customer_tag_rule_delete · 被引用的规则删不掉
-- ---------------------------------------------------------------------------
-- 为什么：规则产出过的标签记录与运行记录是**判定依据**的载体。
-- 删掉规则之后，那次评估「依据哪几笔订单算出来的」在历史里就查不到了。
CREATE FUNCTION guard_crm_customer_tag_rule_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_count integer; v_runs integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_customer_tag_assignment
   WHERE tenant_id = OLD.tenant_id AND rule_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '规则 % 已被 % 条标签记录引用，删不掉：删掉它会让「这条自动标签是依据哪几笔订单算出来的」在历史里无从查证。可以改成 DISABLED 停用',
      OLD.rule_code, v_count USING ERRCODE='23514';
  END IF;
  SELECT count(*) INTO v_runs FROM crm_customer_tag_run
   WHERE tenant_id = OLD.tenant_id AND rule_id = OLD.id;
  IF v_runs > 0 THEN
    RAISE EXCEPTION '规则 % 已有 % 次运行记录，删不掉：可以改成 DISABLED 停用',
      OLD.rule_code, v_runs USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_customer_tag_rule_delete_trg
  BEFORE DELETE ON crm_customer_tag_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_tag_rule_delete();

-- ============================================================================
-- 7. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_customer_tag','crm_customer_tag_rule','crm_customer_tag_assignment','crm_customer_tag_run','crm_customer_tag_evaluation'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_customer_tag, crm_customer_tag_rule, crm_customer_tag_assignment, crm_customer_tag_run, crm_customer_tag_evaluation TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_customer_tag, crm_customer_tag_rule, crm_customer_tag_assignment, crm_customer_tag_run, crm_customer_tag_evaluation TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一次跑规则就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_customer_tag_run_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_customer_tag_run_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 8. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.3.1.1 客户筛选**（**未交付**）：本模块**不提供**「按标签筛出人群」的端点 ——
--     那会把「还没筛」说成「筛好了」。本模块只产出**可被筛的事实**（标签记录）。
--     capabilities 里 customer_segmentation 维度 available=false（ownerModule=15.3.1.1）。
--   * **14.4 促销定向**（**未交付**）：本模块**不实现**「按标签定向发促销」，
--     也不登记任何「待发促销」意图 —— 那是 14.4 的写入。
--     capabilities 里 promotion_targeting 维度 available=false（ownerModule=14.4）。
--   * **15.5.2.1 关怀邮件**（**未交付**）：本模块**不实现**「按标签发关怀邮件」，
--     capabilities 里 care_email 维度 available=false（ownerModule=15.5.2.1）。
--   * **15.3.1.3 跨店合并**（**未交付**）：本模块的 customer_ref 是**单店口径**的
--     （上游 customer_party_id 指向 catalog 03 客户档案的逻辑 id，本仓尚无客户档案表），
--     合并后的逻辑客户由 15.3.1.3 负责；本模块**不假装**已跨店合并。
