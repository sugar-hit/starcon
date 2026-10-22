-- 15.5.2.1 关怀与催办：主动触达买家（发货通知 / 物流提醒 / 催付 / 催评 / 售后进度通知 / 关怀邮件），
-- 按规则定时发送并遵守频次上限与退订要求。
--
-- 蓝图 15 卷 15.5.2.1 的原话：
--   「职责：主动触达买家：发货通知、物流提醒、催付（未付款订单）、催评、售后进度通知、
--     关怀邮件（节假日/生日），按规则定时发送并遵守频次上限与退订要求。
--     输入：触达规则；目标买家集合；退订名单。
--     输出：触达发送记录 + 打开/点击统计 + 退订记录。
--     关系：上游 = 9 订单、10 物流、15.3.2.2 接触记录；下游 = 15.1.1.2 发送、12 财务（催付加速回款）。
--     **催付有合规边界**：部分平台限制催付话术与频次，规则需按平台配置，过度催付会被投诉。」
--
-- ============================================================ 零、与既有模块的边界
--
-- 本文件是 **crm 数据库的第十一个迁移**（V99 知识库 / V220 索评请求 / V228 索评规则 /
-- V292 买家邮件 / V293 消息收件箱 / V294 商品评价 / V295 客户标签 / V296 购物车挽回 /
-- V297 消息发送 / V298 评价回复 / V299 接触记录）。
--
-- 与兄弟模块的边界 —— **本文件不改任何既有表的结构**（零 ALTER、零 DROP、零改列）：
--   * V299（接触记录）：回答「我们与这位客户什么时候、以什么方式接触过，
--     以及他现在是不是还允许我们营销他」⇒ **本模块的退订闸读它**。
--   * V297（消息发送）：回答「回复买家的那一条发出去没有」⇒ 那是 15.1.1.2 的下游，
--     本模块**只**负责「什么时候、对谁、发什么、频次是否超限、退订是否拦截」。
--
-- 🔴 **退订名单的唯一事实源是 V299 的 `crm_customer_opt_out`**：
--   本文件**绝不允许**另建一张退订表。两个事实源必然打架 ——
--   客户说了「不要再联系我」却仍被触达，是最伤信任的一类事故。
--   本文件对退订的处理只有**读**（第 4.4 节的触发器回查）与**拦截**，
--   绝写入、绝不覆盖、绝不撤销。
--
-- ============================================================ 一、加粗句的落点
--
-- 加粗句一：「**遵守频次上限与退订要求**」
--   ⇒ **服务端强制两道闸**（服务层，见 CareReminderService）：
--       ① 退订闸：读 V299 的 `crm_customer_opt_out`；
--          scope='ALL' 一律排除；scope='MARKETING' 排除营销类触达。
--       ② 频次闸：该买家在该触达类型下的窗口内触达次数 ≥ 规则上的频次上限。
--     任一命中 ⇒ **拒绝并给出明确原因**（落成一条 status='BLOCKED' 的任务 +
--     block_code + block_reason，**不是静默跳过**），任务行留痕可查。
--   ⇒ 本文件再加一道**物理闸**（第 4.4 节）：客户处于生效退订时，
--     非 BLOCKED 的触达任务**根本落不了库**。豁免依据要真的拦得住。
--
-- 加粗句二：「**催付有合规边界：部分平台限制催付话术与频次，规则需按平台配置，
--   过度催付会被投诉**」
--   ⇒ 催付规则**必须按平台配置**（本表 platform_code 单值 + UNIQUE 规则编码），
--     **不允许**一个全局规则套所有平台；
--   ⇒ **平台不在白名单里 ⇒ 催付规则建不出来、催付任务也建不出来**：
--     表级 CHECK（第 1 节规则表 + 第 2 节任务表各一道）**物理**挡住，
--     服务层再给一句人话（「该平台不支持催付」）。
--   ⇒ 频次上限与静默时段**逐规则可配**（不同平台配不同值）。
--
-- ============================================================ 二、下游未交付的不编
--
--   * **12 财务（催付加速回款）未交付** ⇒ 本文件**只**落「催付任务本身」与
--     「已提醒回款」的意图，**不写财务单据 id、不编下游 id**。
--   * **15.1.1.2 消息发送**已交付，但平台侧发送接口未接入 ⇒ transport 词表留两档：
--     MANUAL_HANDOFF 人工交接（**当前唯一可用**）/ PLATFORM_API 平台侧发送接口（**未接入**）。
--   * **平台打开/点击回执未接入** ⇒ 统计只能靠平台 webhook 或人工录入；
--     平台接口未接入时**不伪造统计数据**（端点如实答「未接入」且不写数据）。
--
-- ============================================================ 三、三张表
--
--   crm_care_reminder_rule  · 触达规则（类型 / 平台 / 人群筛选 / 频次上限 / 静默时段 / 话术）
--   crm_care_reminder_task  · 触达任务与发送记录（目标买家 / 订单 / 店铺 / 计划时刻 /
--                              实际发送时刻 / 发送结果 / 失败原因 / 拦截原因）
--   crm_care_reminder_stat  · 打开 / 点击统计（触达 id + 事件类型 + 时刻 + 来源）
--
-- 为什么规则与任务分表：规则是**跨批次复用的配置**（谁在什么平台、什么频次、
-- 什么话术），任务是**一次触达的一行**（这一位买家、这一单、这一刻）。
-- 规则改了频次上限，不应该改写已经发生的触达历史 ——
-- 任务的 note 与 subject/body 是**当时的快照**。
--
-- ============================================================ 四、时点、数量与金额
--
--   * 时刻一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     （用 Timestamp.valueOf(LocalDateTime…) 会在东八区静默偏 −28800 秒，本仓反复踩过。）
--   * 静默时段用 **time**（一天里的时刻）+ LocalTime，绑定写 `?::time`。
--   * 数量 / 金额：本模块**不存金额**（金额属订单事实，在 trade 服务）；
--     触达次数用 integer，禁浮点。
--
-- ============================================================ 五、发布后不可变的强制位置
--
--   1. NOT NULL + CHECK：规则编码不得为空串；触达任务必须挂一条存在的规则；
--      客户标识不得为空串；SENT 必须带发出时刻；FAILED 必须带失败原因；
--      BLOCKED 必须带拦截码与拦截原因。
--   2. UNIQUE (tenant_id, idempotency_key)：同一条幂等键重放**只会有一条**任务
--      （重复生成不重复记账）。
--   3. 触发器：
--      guard_crm_care_reminder_task_rule       （BEFORE INSERT：规则必须存在）
--      guard_crm_care_reminder_task_identity   （BEFORE UPDATE：身份列建后不可改）
--      guard_crm_care_reminder_task_opt_out     （BEFORE INSERT：退订物理闸）
--      guard_crm_care_reminder_stat_task        （BEFORE INSERT：统计必须挂在存在的任务上）
--      guard_crm_care_reminder_stat_identity    （BEFORE UPDATE/DELETE：统计只追加）
--
-- ⚠️ 为什么用**触发器回表**而不是**数据库外键**：
--   同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）会被外键打破。本文件**不给任何既有表建外键**，自己新建的三张表之间
--   也**一律用触发器做引用校验**，与 V99 / V220 / V228 / V292 / V293 / V295 / V296 / V299
--   同一手法。
--
-- ============================================================================
-- 零之一、角色与序列
-- ============================================================================
-- 与 V99 / V220 / V292 / V299 同一口径：角色可能已由运维预置，
-- 因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- 触达任务号（CR-yyyyMMdd-000001）用的序列：单独建、单独授权。
-- 🔴 GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成「第一条触达任务就报权限不足」。
CREATE SEQUENCE crm_care_reminder_task_no_seq START 1;

-- ============================================================================
-- 1. crm_care_reminder_rule —— 触达规则
-- ============================================================================
-- 🔴 催付的平台白名单 —— 加粗句「催付有合规边界」的**物理**落点。
--   本仓当前口径：只有这四个平台允许**卖家主动催付**；
--   Amazon 等平台的买家消息由平台托管、且对催付话术与频次有额外限制
--   ⇒ 这些平台**不允许**主动催付（走平台自己的催款工具）。
--   ⚠️ 该白名单在**服务层**也有一份同名常量（CareReminderService），
--   两处必须一致：DB 挡住「建出来」，服务层给出「为什么」的人话。
CREATE TABLE crm_care_reminder_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 业务键（同租户内唯一，便于运营按编码引用）。
  rule_code varchar(64) NOT NULL CHECK (btrim(rule_code) <> ''),
  rule_name varchar(200) NOT NULL CHECK (btrim(rule_name) <> ''),

  -- ===== 触达类型（照蓝图点名的六类归位）=====
  -- SHIPMENT_NOTICE   发货通知
  -- LOGISTICS_REMINDER 物流提醒
  -- PAYMENT_DEMAND    催付（未付款订单）
  -- REVIEW_REQUEST    催评
  -- AFTERSALES_PROGRESS 售后进度通知
  -- CARE_MAIL         关怀邮件（节假日 / 生日）
  reminder_type varchar(32) NOT NULL CHECK (reminder_type IN (
    'SHIPMENT_NOTICE','LOGISTICS_REMINDER','PAYMENT_DEMAND',
    'REVIEW_REQUEST','AFTERSALES_PROGRESS','CARE_MAIL')),

  -- ===== 🔴 适用平台（**单平台**）=====
  -- 加粗句「规则需按平台配置」：一个规则只对一个平台生效，
  -- 不允许一个全局规则套所有平台。不同平台的催付话术 / 频次上限因此可以各配各的。
  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),

  -- 可选地再收窄到某个店铺（NULL = 该平台全部店铺）。
  shop_id uuid,
  shop_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 目标人群筛选条件 =====
  -- 结构随来源而异（订单时间窗 / 最低金额 / 订单来源 / 显式客户清单…），
  -- 落 jsonb：筛选条件是**配置**，不是查询结构，强行拉成列会每加一个条件就改一次表。
  target_filter jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- ===== 🔴 频次上限 =====
  -- frequency_limit：窗口内**最多**触达几次（0 = 该规则不允许任何触达）。
  -- frequency_window_hours：窗口长度（小时）。加粗句「遵守频次上限」落在这两列上。
  frequency_limit integer NOT NULL DEFAULT 1 CHECK (frequency_limit >= 0),
  frequency_window_hours integer NOT NULL DEFAULT 24 CHECK (frequency_window_hours BETWEEN 1 AND 8760),

  -- ===== 🔴 定时发送（加粗句「按规则定时发送」）=====
  -- send_time：每天几点发（**规则所在时区的本地时刻**）。
  -- timezone：IANA 时区名（如 Asia/Shanghai / UTC）。服务端按它算下一次计划时刻。
  send_time time NOT NULL DEFAULT '09:00:00',
  timezone varchar(64) NOT NULL DEFAULT 'UTC' CHECK (btrim(timezone) <> ''),

  -- ===== 静默时段（一天里的时刻，time 无时区）=====
  -- 两个要么都填要么都不填；都填且不等 ⇒ 这段时间内不排期（顺延到静默结束）。
  quiet_hours_start time,
  quiet_hours_end time,
  CHECK (
    (quiet_hours_start IS NULL AND quiet_hours_end IS NULL)
    OR (quiet_hours_start IS NOT NULL AND quiet_hours_end IS NOT NULL
        AND quiet_hours_start <> quiet_hours_end)
  ),

  -- ===== 话术模板引用 =====
  -- template_code 是**引用**（逻辑引用，指向话术库）；subject/body 是**当时的快照**，
  -- 模板后来改了也不影响「当时发出去的是哪句话」。
  template_code varchar(64) NOT NULL DEFAULT '',
  subject varchar(400) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  -- ACTIVE 可用 · DISABLED 停用（停用后不能生成新任务，历史任务仍可读）。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),

  created_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, rule_code),

  -- 🔴 催付合规边界的**第一道物理闸**：白名单外的平台建不出催付规则。
  CHECK (
    reminder_type <> 'PAYMENT_DEMAND'
    OR platform_code IN ('SHOPIFY','EBAY','TIKTOK_SHOP','ALIBABA_1688')
  )
);

-- 列表页按类型 / 平台 / 状态筛。
CREATE INDEX crm_care_reminder_rule_by_type
  ON crm_care_reminder_rule(tenant_id, reminder_type, status, rule_code);
CREATE INDEX crm_care_reminder_rule_by_platform
  ON crm_care_reminder_rule(tenant_id, platform_code, status, rule_code);
-- 催付规则盘点（「哪些平台配了催付、频次上限各是多少」）。
CREATE INDEX crm_care_reminder_rule_by_payment
  ON crm_care_reminder_rule(tenant_id, platform_code, frequency_limit, frequency_window_hours)
  WHERE reminder_type = 'PAYMENT_DEMAND';

-- ============================================================================
-- 2. crm_care_reminder_task —— 触达任务与发送记录
-- ============================================================================
CREATE TABLE crm_care_reminder_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- CR-yyyyMMdd-000001
  task_no varchar(32) NOT NULL DEFAULT '',

  -- ===== 规则（逻辑引用；引用校验用触发器，不建数据库外键）=====
  rule_id uuid NOT NULL,
  -- 触达类型：规则后来改了类型，历史任务仍保留**当时**的类型。
  reminder_type varchar(32) NOT NULL CHECK (reminder_type IN (
    'SHIPMENT_NOTICE','LOGISTICS_REMINDER','PAYMENT_DEMAND',
    'REVIEW_REQUEST','AFTERSALES_PROGRESS','CARE_MAIL')),

  -- ===== 目标买家（逻辑引用：本仓没有买家档案主数据，15.3.1.1 未交付）=====
  customer_ref varchar(128) NOT NULL CHECK (btrim(customer_ref) <> ''),
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 上下文快照（店铺属 channel、订单属 trade ⇒ 不建跨库外键）=====
  shop_id uuid,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),
  order_reference varchar(64) NOT NULL DEFAULT '',

  -- ===== 话术快照（规则后来改了话术，历史任务仍保留**当时**的话）=====
  subject varchar(400) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  -- ===== 触达通道 =====
  -- MANUAL_HANDOFF 人工交接（**当前唯一可用**）；
  -- PLATFORM_API    平台侧发送接口（**未接入**，服务端拒绝写这一档）。
  transport varchar(24) NOT NULL DEFAULT 'MANUAL_HANDOFF'
    CHECK (transport IN ('MANUAL_HANDOFF','PLATFORM_API')),

  -- ===== 状态 =====
  -- QUEUED   已排期待发（过了退订闸与频次闸）
  -- SENT     已发出（人工交接登记 / 平台回执）
  -- FAILED   发送失败（必须带失败原因）
  -- BLOCKED  **被拦截**（必须带拦截码与拦截原因：退订 / 频次超限 / 平台不支持 / 未签收）
  -- CANCELLED 已作废
  status varchar(16) NOT NULL DEFAULT 'QUEUED'
    CHECK (status IN ('QUEUED','SENT','FAILED','BLOCKED','CANCELLED')),

  -- 计划发送时刻（规则排期 + 静默时段顺延后的结果）。
  planned_at timestamptz NOT NULL,
  -- 实际发出时刻：SENT 时**必填**。
  sent_at timestamptz,
  CHECK (status <> 'SENT' OR sent_at IS NOT NULL),

  -- 失败原因：FAILED 时**必填**（只写「失败」而不写为什么，事后无法判断该不该重试）。
  failure_reason text NOT NULL DEFAULT '',
  CHECK (status <> 'FAILED' OR btrim(failure_reason) <> ''),

  -- ===== 🔴 拦截原因：BLOCKED 时**必填** =====
  -- 加粗句「遵守频次上限与退订要求」要求「拒绝并给出明确原因，不是静默跳过」：
  -- 拦截本身也是一条**留痕**的事实（谁在什么时候想发、被什么挡住了）。
  -- 词表**只有两档**，正好对应那两道强制闸：
  --   OPT_OUT         退订闸（读 V299 的 crm_customer_opt_out）
  --   FREQUENCY_LIMIT 频次闸（该买家在该触达类型下的窗口内触达次数超限）
  -- ⚠️ 「该平台不支持催付」**不是**一个拦截码：它是**规则级**的合规判定 ——
  --   平台不在白名单里时，这条催付规则**根本建不出来**（表级 CHECK），
  --   因此不存在「一条催付任务被平台挡住了」这种情况（那会自相矛盾）。
  block_code varchar(32) NOT NULL DEFAULT ''
    CHECK (block_code IN ('','OPT_OUT','FREQUENCY_LIMIT')),
  block_reason text NOT NULL DEFAULT '',
  CHECK (status <> 'BLOCKED' OR (btrim(block_code) <> '' AND btrim(block_reason) <> '')),

  -- 平台回执（人工交接时由运营登记平台侧拿到的东西）。
  platform_receipt text NOT NULL DEFAULT '',

  -- ===== 幂等 =====
  -- 幂等键：同租户内唯一。重放同一条键**不会**产生第二条任务。
  idempotency_key varchar(200) NOT NULL CHECK (btrim(idempotency_key) <> ''),

  created_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, task_no),
  UNIQUE (tenant_id, idempotency_key),

  -- 🔴 催付合规边界的**第二道物理闸**：白名单外的平台建不出催付任务。
  --   （即便规则表被别的路径写入，也不会因此产生越界催付。）
  CHECK (
    reminder_type <> 'PAYMENT_DEMAND'
    OR platform_code IN ('SHOPIFY','EBAY','TIKTOK_SHOP','ALIBABA_1688')
  )
);

-- 触达记录按状态读（待发队列 / 失败重试队列 / 拦截清单）。
CREATE INDEX crm_care_reminder_task_by_status
  ON crm_care_reminder_task(tenant_id, status, planned_at DESC, id DESC);
-- 「这位买家在这个触达类型下被触达过几次」—— 频次闸的计数依据。
CREATE INDEX crm_care_reminder_task_by_customer
  ON crm_care_reminder_task(tenant_id, customer_ref, reminder_type, created_at DESC);
-- 按规则读（一次生成的批次）。
CREATE INDEX crm_care_reminder_task_by_rule
  ON crm_care_reminder_task(tenant_id, rule_id, planned_at DESC);
-- 按店铺读。
CREATE INDEX crm_care_reminder_task_by_shop
  ON crm_care_reminder_task(tenant_id, shop_id, planned_at DESC);
-- 拦截清单（「退订拦截 / 频次拦截各多少」）。
CREATE INDEX crm_care_reminder_task_by_block
  ON crm_care_reminder_task(tenant_id, block_code, created_at DESC)
  WHERE status = 'BLOCKED';
-- 幂等键点查（重放判定）。
CREATE UNIQUE INDEX crm_care_reminder_task_one_per_idempotency
  ON crm_care_reminder_task(tenant_id, idempotency_key);

-- ============================================================================
-- 3. crm_care_reminder_stat —— 打开 / 点击统计
-- ============================================================================
-- ⚠️ 平台回执**未接入** ⇒ 统计只能靠平台 webhook 或人工录入。
--   平台接口未接入时**不伪造统计数据**（服务层端点如实答「未接入」且不写数据）。
CREATE TABLE crm_care_reminder_stat (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 挂在哪条触达任务上（逻辑引用；引用校验用触发器，不建数据库外键）。
  task_id uuid NOT NULL,

  -- OPEN 打开 · CLICK 点击。
  event_type varchar(16) NOT NULL CHECK (event_type IN ('OPEN','CLICK')),
  -- 事件发生时刻（平台回执时刻 / 人工登记时刻）。
  occurred_at timestamptz NOT NULL,
  -- 来源：平台 webhook 标识 / 人工登记人说明。
  source varchar(120) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 按任务读事件（一条触达被打开了几次、被点了哪些链接）。
CREATE INDEX crm_care_reminder_stat_by_task
  ON crm_care_reminder_stat(tenant_id, task_id, occurred_at DESC);
-- 按类型 + 时刻读（打开率 / 点击率的服务端聚合依据）。
CREATE INDEX crm_care_reminder_stat_by_type
  ON crm_care_reminder_stat(tenant_id, event_type, occurred_at DESC);

-- ============================================================================
-- 4. 触发器（**一律不用外键** —— 理由见文件头第五节）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 4.1 guard_crm_care_reminder_task_rule · 触达任务必须挂在存在的规则上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_care_reminder_task_rule() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_rule_type varchar(32);
BEGIN
  SELECT reminder_type INTO v_rule_type
    FROM crm_care_reminder_rule r
   WHERE r.id = NEW.rule_id AND r.tenant_id = NEW.tenant_id;
  IF v_rule_type IS NULL THEN
    RAISE EXCEPTION '触达任务必须挂在同一租户下真实存在的触达规则上（rule_id=%）', NEW.rule_id
      USING ERRCODE='23514';
  END IF;
  -- 类型必须与规则一致：否则「这条触达到底是哪一类」在历史上会有两个答案。
  IF v_rule_type <> NEW.reminder_type THEN
    RAISE EXCEPTION '触达任务的类型（%）必须与规则的类型（%）一致：否则「这条触达到底是哪一类」在历史上会有两个答案',
      NEW.reminder_type, v_rule_type USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_care_reminder_task_rule_trg
  BEFORE INSERT ON crm_care_reminder_task
  FOR EACH ROW EXECUTE FUNCTION guard_crm_care_reminder_task_rule();

-- ---------------------------------------------------------------------------
-- 4.2 guard_crm_care_reminder_task_identity · 身份列建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：这一行是「这一次触达是谁的、按哪条规则、排在哪一刻」的凭据。
-- 改了之后「这条触达是哪一次」在历史上会出现两个答案。
-- 可改的只有**处理态**列（状态 / 失败原因 / 拦截原因 / 平台回执 / 发出时刻）。
CREATE FUNCTION guard_crm_care_reminder_task_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_id IS DISTINCT FROM OLD.rule_id
     OR NEW.reminder_type IS DISTINCT FROM OLD.reminder_type
     OR NEW.customer_ref IS DISTINCT FROM OLD.customer_ref
     OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
     OR NEW.planned_at IS DISTINCT FROM OLD.planned_at
     OR NEW.platform_code IS DISTINCT FROM OLD.platform_code
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION '触达任务的身份列（规则/类型/客户/幂等键/计划时刻/平台/创建时刻）建后不可改：改了就出现「同一次触达有两套说法」的历史' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_care_reminder_task_identity_trg
  BEFORE UPDATE ON crm_care_reminder_task
  FOR EACH ROW EXECUTE FUNCTION guard_crm_care_reminder_task_identity();

-- ---------------------------------------------------------------------------
-- 4.3 guard_crm_care_reminder_stat_task · 统计必须挂在存在的触达任务上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_care_reminder_stat_task() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_care_reminder_task t
       WHERE t.id = NEW.task_id AND t.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '打开/点击统计必须挂在同一租户下真实存在的触达任务上（task_id=%）', NEW.task_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_care_reminder_stat_task_trg
  BEFORE INSERT ON crm_care_reminder_stat
  FOR EACH ROW EXECUTE FUNCTION guard_crm_care_reminder_stat_task();

-- ---------------------------------------------------------------------------
-- 4.4 guard_crm_care_reminder_task_opt_out · 🔴 退订物理闸
-- ---------------------------------------------------------------------------
-- 加粗句「遵守频次上限与退订要求」的反向闸：
--   豁免依据如果只是「一条可读的事实」，它拦不住任何东西。
--   这里让它**真的拦得住**：客户处于生效退订时，
--   **非 BLOCKED** 的触达任务根本落不了库。
--
-- ⚠️ 判定用 V299 的 `crm_customer_opt_out`（**唯一事实源**）：
--   scope='ALL'      ⇒ 全部触达都排除；
--   scope='MARKETING' ⇒ 只排除营销类触达（关怀邮件 / 催评）；
--   其余 scope='MARKETING' 的情形（发货通知 / 物流提醒 / 催付 / 售后进度通知）
--   属于**服务性通知**，照常发 —— 与 V299 自己的营销豁免口径一致。
--
-- ⚠️ BLOCKED 的行**允许**落库：拦截本身也是一条要留痕的事实
--   （「我们想在 2026-10-22 想给他发，被退订挡住了」必须查得到）。
CREATE FUNCTION guard_crm_care_reminder_task_opt_out() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_scope varchar(16);
  v_recorded_at timestamptz;
BEGIN
  -- 拦截留痕行不受本闸约束。
  IF NEW.status = 'BLOCKED' THEN
    RETURN NEW;
  END IF;
  SELECT scope, recorded_at INTO v_scope, v_recorded_at
    FROM crm_customer_opt_out o
   WHERE o.tenant_id = NEW.tenant_id
     AND o.customer_ref = NEW.customer_ref
     AND o.state = 'ACTIVE'
   ORDER BY o.recorded_at DESC
   LIMIT 1;
  IF v_scope IS NULL THEN
    RETURN NEW;
  END IF;
  IF v_scope = 'ALL' THEN
    RAISE EXCEPTION '客户 % 已于 % 登记退订（范围 ALL：不要再联系我）：触达任务不能落库（拦截留痕行 status=BLOCKED 例外）',
      NEW.customer_ref, v_recorded_at USING ERRCODE='23514';
  END IF;
  IF v_scope = 'MARKETING' AND NEW.reminder_type IN ('CARE_MAIL','REVIEW_REQUEST') THEN
    RAISE EXCEPTION '客户 % 已于 % 登记退订（范围 MARKETING：营销触达）：触达类型 % 属于营销触达，不能落库（拦截留痕行 status=BLOCKED 例外）',
      NEW.customer_ref, v_recorded_at, NEW.reminder_type USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_care_reminder_task_opt_out_trg
  BEFORE INSERT ON crm_care_reminder_task
  FOR EACH ROW EXECUTE FUNCTION guard_crm_care_reminder_task_opt_out();

-- ---------------------------------------------------------------------------
-- 4.5 guard_crm_care_reminder_stat_identity · 统计只追加
-- ---------------------------------------------------------------------------
-- 统计事件是「打开/点击」这种**发生过就发生过**的事实：
-- 改它或删它都会让「打开率 / 点击率」在事后变得不可复核。
CREATE FUNCTION guard_crm_care_reminder_stat_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '打开/点击统计**只追加**：改它或删它都会让「打开率 / 点击率」在事后变得不可复核' USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_care_reminder_stat_identity_trg
  BEFORE UPDATE ON crm_care_reminder_stat
  FOR EACH ROW EXECUTE FUNCTION guard_crm_care_reminder_stat_identity();

CREATE TRIGGER crm_care_reminder_stat_delete_trg
  BEFORE DELETE ON crm_care_reminder_stat
  FOR EACH ROW EXECUTE FUNCTION guard_crm_care_reminder_stat_identity();

-- ============================================================================
-- 6. 行级安全（RLS）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V293 / V295 / V296 / V297 / V299 同一口径：
-- ENABLE **与** FORCE 都要 —— 只有 FORCE 才挡得住「运行角色被误授 owner」。
-- 策略名统一 isolation，USING 与 WITH CHECK 用**同一表达式**（两侧都写，
-- 否则 WITH CHECK 漏写就等于留了一条写入别的租户的口子）。
DO $$
DECLARE
  tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'crm_care_reminder_rule',
    'crm_care_reminder_task',
    'crm_care_reminder_stat'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_care_reminder_rule, crm_care_reminder_task, crm_care_reminder_stat TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_care_reminder_rule, crm_care_reminder_task, crm_care_reminder_stat TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条触达任务就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_care_reminder_task_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_care_reminder_task_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 7. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.1.1.2 消息发送**（V297，`crm_message_send`）：本模块**不重造**发送。
--     平台侧发送接口未接入 ⇒ 本模块 transport 恒为 MANUAL_HANDOFF，
--     运营在平台侧实际发出后回本服务登记结果（已发出 / 失败原因 / 平台回执）。
--   * **12 财务（催付加速回款）**：**未交付** ⇒ 本模块只落**催付任务本身**
--     与「已提醒回款」的意图，**不写财务单据 id、不编下游 id**。
--   * **平台打开/点击回执**：**未接入** ⇒ 统计只能靠平台 webhook 或人工录入；
--     自动采集端点如实答「未接入」且**不写数据**。
--   * **15.5.2 买家关怀**（上层卷内模块）：本模块是 15.5.2 下的触达子域，
--     不越界到 15.5.2 的其它子域。
