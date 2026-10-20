-- 15.2.2.2 索评规则：配置索评策略 —— 按店铺、按物流方式（自发货 / 平台仓）设置不同延后时间，
-- 避开节假日，支持 A/B 测试不同话术。
--
-- 蓝图 15 卷 15.2.2.2 的原话：
--   「职责：配置索评策略：按店铺、按物流方式（自发货/平台仓）设置不同延后时间，避开节假日，
--     支持 A/B 测试不同话术。
--     输入：规则配置；话术模板。
--     输出：生效策略 + 效果对比。
--     关系：上游 = 15.2.2.1；下游 = 15.1.1.2 发送。」
--
-- ============================================================ 零、本文件与既有模块的边界
--
-- 本文件是 **crm 数据库的第三个迁移**（V99 = 15.4.2.1 知识库，V220 = 15.2.2.1 索评请求）。
--
-- 与 15.2.2.1（V220）的边界 —— 两者**不共用任何一张表**，写入所有者也各自独立：
--   * V220 回答「这一位买家、这一张已签收的单子，要不要向他发起一次索评」；
--     它落的是**一次索评请求的判定与留痕**（请求 / 发送记录 / 评价回收 / 频次硬上限）。
--   * V228（本文件）回答「**策略怎么配**」：按店铺 / 按物流方式的延后时间、节假日规避、
--     A/B 话术模板，以及由此**解析出来的生效策略**与**变体之间的效果对比**。
--   ⇒ 本文件**不改 V220 的任何一张表**（零 ALTER），只**引用**它的索评请求（逻辑引用 + 触发器校验）。
--
-- 与 15.4.2.1 知识库（V99）的边界：知识库存的是「怎么回答买家」，
-- 本文件存的是「什么时候问、问哪个店铺、问谁（哪个话术变体）」。内容与时机分离，各管各的表。
--
-- 🔴 与 15.2.2.1 的**频次硬上限**互不越权：
--   V220 的 `crm_review_frequency_policy` 是加粗句「索评频次必须受限」的**兜底闸门**，
--   每个值在 DB 层另有天花板，**本文件改不动它**（本文件不 ALTER 任何既有表）。
--   本文件的 `delay_hours` 只是一个**时点**（签收后延后多久），不是频次；
--   真正发不发得出去，仍由 V220 的触发器在数据库层判定。
--
-- ============================================================ 一、跨服务纪律（零跨库 SQL / FK / 视图）
--
-- 上游 **2.3.1.1 店铺目录**在 **channel** 服务（V1 `shop` 表，端口 8083），
-- 与本模块**不同服务、不同数据库** ⇒ AGENTS.md 模块边界纪律：
-- **禁止跨库 SQL、跨库 FK、共享视图**，只走授权 HTTP API。
--
-- 本库因此**只存店铺的逻辑引用 + 快照**：
--   shop_id / shop_name
-- —— 与 V99 知识库、V220 索评请求上的 shop_id / shop_name 快照同一纪律。
-- 快照的作用是「列表页不必回源就能显示」，它**不构成引用完整性**。
--
-- 下游 **15.1.1.2 消息发送**（蓝图写明本模块的下游）**未交付** ⇒
-- 本模块只**产出话术与生效策略**，**不发送**：
--   * 话术模板存的是「A / B 两个变体的正文」；
--   * 变体分配落 `crm_review_rule_assignment`（谁、哪条索评请求、哪个变体、排期到什么时候）；
--   * **没有**「已发送 / 已送达」这类回执字段 —— 一个自己写的「已发送」标记
--     会让「买家到底收到没收到」看起来发生过，而那正是过度索评投诉的源头。
--   capabilities 里 `message_dispatch` 维度显式标 available=false（ownerModule=15.1.1.2）。
--
-- 上游 **15.2.2.1 索评任务**（V220，`crm_review_request`）与本模块**同服务、同数据库**：
-- 本模块**只读**它（取店铺 / 买家 / 订单 / 签收时刻 / 发送状态 / 评价回收），
-- **不写**它。写入所有者仍然只有 15.2.2.1 一个。
--
-- ============================================================ 二、五张表：规则 / 模板 / 变体 / 节假日 / 分配
--
--   crm_review_rule              索评规则配置（按店铺 × 按物流方式的延后时间 + 节假日规避 + A/B 开关）
--   crm_review_message_template  索评话术模板（身份 + 语言）
--   crm_review_message_variant   话术变体（A / B）+ 权重
--   crm_review_holiday           节假日日历（索评要避开的日子）
--   crm_review_rule_assignment   一次「生效策略」的落地结果（哪条索评请求用了哪个变体）
--
-- 为什么「模板」与「变体」分两张表：
--   模板是**身份**（叫什么、什么语言、给谁用），变体是**内容**（A 版正文 / B 版正文 / 各占多少）。
--   A/B 测试的权重挂在变体上：同一个模板下 A 占 60%、B 占 40%。
--   合表的话「这个模板有哪些变体」要靠一个变体数组列去存，权重、状态、启停就都没地方放。
--
-- 为什么「分配」独立成表（而不是在索评请求上挂一列变体）：
--   `crm_review_request` 是 15.2.2.1 的表，本模块**不改它**（零 ALTER 既有表）。
--   而「哪条索评请求用了哪个变体」正是**效果对比**唯一的数据来源 ——
--   没有这层归属，回收率与平均星级就无从按 A / B 拆开。
--   分配表因此是本模块的**产出事实**：它记的是「生效策略在某个时刻落到某个索评请求上的结果」。
--
-- ============================================================ 三、规则生效的判定口径（写死在 DB 的约束里）
--
--   * 适用范围：shop_id（NULL = 适用于全部店铺）× logistics_mode
--     （ANY = 不限 / SELF_FULFILLED = 自发货 / PLATFORM_WAREHOUSE = 平台仓）。
--   * 命中优先级：**范围越具体越优先**（店铺+物流方式 > 店铺 > 物流方式 > 全局），
--     同一范围按 priority 升序（数字小的先用）。
--     ⇒ DB 层用 `UNIQUE(tenant_id, coalesce(shop_id,…), logistics_mode, priority) WHERE status='ACTIVE'`
--     保证「同一范围内同一优先级不会有两条 ACTIVE 规则」—— 否则命中谁是不确定的，
--     「生效策略」就会有两个答案。
--   * 有效期：effective_from / effective_to（NULL = 不限）。避开节假日后仍按有效期判定。
--   * 节假日规避：avoid_holidays = true 时，若算出的**排期日**落在本租户的节假日区间内
--     （holiday_region 为空 = 全部区域，否则精确匹配），则把排期**顺延** holiday_shift_days 天。
--     节假日区间支持连休（holiday_end_date）。
--
-- 🔴 时区：节假日本身是**日历概念**，必须按店铺所在时区判定，
--   否则 UTC 日界会把「东八区 10-02 07:00」算成 10-01，节假日就被错开一天。
--   ⇒ 规则上带 timezone（默认 'Asia/Shanghai'），服务层用 ZoneId 换算；
--   本文件存的是 timestamptz（绝对时刻），换算只在服务层做一次。
--
-- ============================================================ 四、禁浮点与时间纪律
--
--   * 数量 / 小时数 / 权重：一律 **numeric(19,6)** + BigDecimal，**禁浮点**。
--   * 时间：一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区静默偏 −28800 秒。
--   * 日历日期（节假日）用 **date** —— 它本来就是日历概念，不带时刻，
--     也不该带时区（带了就要回答「按谁的时区算」这个不该问的问题）。
--
-- ============================================================ 五、发布后不可变的强制位置
--
--   1. 分配记录（assignment）的身份列建后不可改：改了之后
--      「哪个变体带来了这次回收」在历史上会出现两个答案，效果对比就不可信。
--   2. 已被分配引用的规则 / 变体 / 模板**不可删** ——
--      删掉之后效果对比里那一段历史会凭空消失（不是「少了几条」，是「结论被改写了」）。
--   3. 触发器（CHECK 在 PostgreSQL 里可被 NOT VALID 或迁移脚本绕过，触发器不行）：
--      guard_crm_review_message_variant_target   （变体必须挂在存在的模板上）
--      guard_crm_review_rule_template_target     （规则引用的模板必须存在）
--      guard_crm_review_rule_assignment_target  （分配必须挂在存在的规则 / 变体 / 索评请求上）
--      guard_crm_review_rule_assignment_identity （分配的身份列建后不可改）
--      guard_crm_review_rule_delete              （有分配的规则不可删）
--      guard_crm_review_message_variant_delete   （有分配的变体不可删）
--      guard_crm_review_message_template_delete  （被引用的模板不可删）
--
-- ⚠️ 为什么用**触发器回表**而不是**数据库外键**：
--   同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）会被外键打破。本文件**不给任何既有表建外键**，本文件自己新建的表之间
--   也**一律用触发器做引用校验**，与 V219 / V220 同一手法。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 同一口径：角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
END $$;

-- ============================================================================
-- 1. crm_review_rule —— 索评规则配置
-- ============================================================================
-- 规则号 / 模板号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条规则就报权限不足」（10.3.2.2 踩过这个坑）。
CREATE SEQUENCE crm_review_rule_no_seq START 1;
CREATE SEQUENCE crm_review_message_template_no_seq START 1;

CREATE TABLE crm_review_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则号（RULE-yyyyMMdd-000001）。
  rule_no varchar(32) NOT NULL DEFAULT '',
  rule_name varchar(200) NOT NULL CHECK (btrim(rule_name) <> ''),

  -- 适用店铺：NULL = 适用于全部店铺（全局规则）。
  -- ⚠️ 店铺真值在 channel 服务（2.3.1.1），本库只存逻辑引用 + 名称快照。
  shop_id uuid,
  shop_name varchar(200) NOT NULL DEFAULT '',

  -- 适用物流方式：ANY 不限 / SELF_FULFILLED 自发货 / PLATFORM_WAREHOUSE 平台仓。
  logistics_mode varchar(24) NOT NULL DEFAULT 'ANY'
    CHECK (logistics_mode IN ('ANY','SELF_FULFILLED','PLATFORM_WAREHOUSE')),

  -- 时点：签收后延后多少小时才发索评（numeric，禁浮点）。
  delay_hours numeric(19,6) NOT NULL CHECK (delay_hours > 0 AND delay_hours <= 8760),

  -- 关联的话术模板（NULL = 本规则不带话术）。
  template_id uuid,

  -- A/B 测试开关 + 节假日规避。
  ab_test_enabled boolean NOT NULL DEFAULT false,
  avoid_holidays boolean NOT NULL DEFAULT true,
  -- 节假日区域：空 = 全部区域；非空 = 精确匹配 crm_review_holiday.region。
  holiday_region varchar(64) NOT NULL DEFAULT '',
  -- 命中节假日后顺延几天（0 = 不顺延，即当天不发也不顺延 → 由服务层按「跳过」处理）。
  holiday_shift_days integer NOT NULL DEFAULT 1 CHECK (holiday_shift_days BETWEEN 0 AND 30),

  -- 排期与节假日判定所用的时区（默认东八区）。
  timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',

  -- 命中优先级：同一适用范围下数字小的先用（DB 层保证不并列）。
  priority integer NOT NULL DEFAULT 100 CHECK (priority BETWEEN 1 AND 1000),

  -- 状态：DRAFT 草稿 / ACTIVE 生效 / PAUSED 暂停。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('DRAFT','ACTIVE','PAUSED')),

  -- 有效期（绝对时刻，NULL = 不限）。
  effective_from timestamptz,
  effective_to timestamptz,

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)
);

CREATE INDEX crm_review_rule_by_shop ON crm_review_rule (tenant_id, shop_id);
CREATE INDEX crm_review_rule_by_mode ON crm_review_rule (tenant_id, logistics_mode);
CREATE INDEX crm_review_rule_by_status ON crm_review_rule (tenant_id, status);

-- 同一适用范围 + 同一优先级**不允许**有两条 ACTIVE 规则：
-- 否则「生效策略」有两个答案（命中谁取决于插入顺序），而输出句点名要的正是「生效策略」。
CREATE UNIQUE INDEX crm_review_rule_one_active_per_priority
  ON crm_review_rule (
    tenant_id,
    coalesce(shop_id, '00000000-0000-0000-0000-000000000000'::uuid),
    logistics_mode,
    priority
  ) WHERE status = 'ACTIVE';

-- ============================================================================
-- 2. crm_review_message_template —— 索评话术模板
-- ============================================================================
CREATE TABLE crm_review_message_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  template_no varchar(32) NOT NULL DEFAULT '',
  template_name varchar(200) NOT NULL CHECK (btrim(template_name) <> ''),
  -- 买家看到的语言（BCP-47 简写，例如 zh-CN / en-US）。
  locale varchar(16) NOT NULL DEFAULT 'zh-CN' CHECK (btrim(locale) <> ''),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX crm_review_message_template_one_name
  ON crm_review_message_template (tenant_id, template_name, locale);

-- ============================================================================
-- 3. crm_review_message_variant —— 话术变体（A / B）与权重
-- ============================================================================
CREATE TABLE crm_review_message_variant (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  -- 变体码：A / B（A/B 测试的两个臂）。
  variant_code varchar(8) NOT NULL CHECK (variant_code IN ('A','B')),
  subject varchar(300) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',
  -- 权重：百分比（> 0 且 ≤ 100），分桶时按累计权重落到 0..9999 的桶里。
  weight numeric(19,6) NOT NULL DEFAULT 50 CHECK (weight > 0 AND weight <= 100),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX crm_review_message_variant_one_per_code
  ON crm_review_message_variant (tenant_id, template_id, variant_code);
CREATE INDEX crm_review_message_variant_by_template
  ON crm_review_message_variant (tenant_id, template_id);

-- ============================================================================
-- 4. crm_review_holiday —— 节假日日历
-- ============================================================================
CREATE TABLE crm_review_holiday (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 节假日区间：holiday_end_date 为空 = 单日；非空 = 连休（春节等）。
  holiday_date date NOT NULL,
  holiday_end_date date,
  holiday_name varchar(200) NOT NULL CHECK (btrim(holiday_name) <> ''),
  -- 区域：空 = 全部区域；非空 = 精确匹配规则的 holiday_region。
  region varchar(64) NOT NULL DEFAULT '',
  -- HOLIDAY 法定节假日 / PROMOTION 大促（同样要避开索评）。
  kind varchar(16) NOT NULL DEFAULT 'HOLIDAY' CHECK (kind IN ('HOLIDAY','PROMOTION')),
  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (holiday_end_date IS NULL OR holiday_end_date >= holiday_date)
);

CREATE UNIQUE INDEX crm_review_holiday_one_per_day
  ON crm_review_holiday (tenant_id, holiday_date, region);
CREATE INDEX crm_review_holiday_by_region ON crm_review_holiday (tenant_id, region);

-- ============================================================================
-- 5. crm_review_rule_assignment —— 生效策略的一次落地
-- ============================================================================
CREATE TABLE crm_review_rule_assignment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 命中的规则（15.2.2.2 自己的表）。
  rule_id uuid NOT NULL,
  -- 命中的话术模板与变体（变体可能为空：规则没带模板或没开 A/B）。
  template_id uuid,
  variant_id uuid,

  -- 被应用策略的索评请求（15.2.2.1 的 crm_review_request，同库逻辑引用）。
  review_request_id uuid NOT NULL,

  -- 当时的目标店铺与物流方式（快照）。
  shop_id uuid,
  shop_name varchar(200) NOT NULL DEFAULT '',
  logistics_mode varchar(24) NOT NULL DEFAULT 'ANY'
    CHECK (logistics_mode IN ('ANY','SELF_FULFILLED','PLATFORM_WAREHOUSE')),
  buyer_ref varchar(128) NOT NULL DEFAULT '',
  sales_order_no varchar(64) NOT NULL DEFAULT '',

  -- 签收时刻（快照，来自索评请求）。
  delivered_at timestamptz,

  -- 规则快照（建后不可改）：这次到底按什么参数排的期。
  delay_hours numeric(19,6) NOT NULL CHECK (delay_hours > 0 AND delay_hours <= 8760),
  scheduled_at timestamptz NOT NULL,
  shifted_days integer NOT NULL DEFAULT 0 CHECK (shifted_days >= 0),
  -- 命中的节假日名（空 = 没命中）。
  holiday_name varchar(200) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- 一条索评请求只能被分配一次：重复分配会让「这个变体带来了这次回收」有两个答案。
CREATE UNIQUE INDEX crm_review_rule_assignment_one_per_request
  ON crm_review_rule_assignment (tenant_id, review_request_id);
CREATE INDEX crm_review_rule_assignment_by_variant
  ON crm_review_rule_assignment (tenant_id, variant_id);
CREATE INDEX crm_review_rule_assignment_by_rule
  ON crm_review_rule_assignment (tenant_id, rule_id);
CREATE INDEX crm_review_rule_assignment_by_created
  ON crm_review_rule_assignment (tenant_id, created_at);

-- ============================================================================
-- 6. 引用校验触发器（**不用外键**：理由见文件头第五节末）
-- ============================================================================

-- 6.1 变体必须挂在存在的模板上。
CREATE FUNCTION guard_crm_review_message_variant_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM crm_review_message_template
                  WHERE tenant_id = NEW.tenant_id AND id = NEW.template_id) THEN
    RAISE EXCEPTION '话术模板 % 不存在（tenant=%），不能给它挂变体',
      NEW.template_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_message_variant_target_trg
  BEFORE INSERT OR UPDATE ON crm_review_message_variant
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_message_variant_target();

-- 6.2 规则引用的模板必须存在。
CREATE FUNCTION guard_crm_review_rule_template_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.template_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM crm_review_message_template
       WHERE tenant_id = NEW.tenant_id AND id = NEW.template_id) THEN
    RAISE EXCEPTION '话术模板 % 不存在（tenant=%），规则不能引用不存在的模板',
      NEW.template_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_rule_template_target_trg
  BEFORE INSERT OR UPDATE ON crm_review_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_rule_template_target();

-- 6.3 分配必须挂在存在的规则 / 变体 / 索评请求上，
--     且变体必须属于规则引用的模板（否则「A/B 对比」会拿错臂）。
CREATE FUNCTION guard_crm_review_rule_assignment_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_template uuid;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM crm_review_rule
                  WHERE tenant_id = NEW.tenant_id AND id = NEW.rule_id) THEN
    RAISE EXCEPTION '索评规则 % 不存在（tenant=%），不能登记生效策略的落地',
      NEW.rule_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM crm_review_request
                  WHERE tenant_id = NEW.tenant_id AND id = NEW.review_request_id) THEN
    RAISE EXCEPTION '索评请求 % 不存在（tenant=%），不能对它登记生效策略的落地',
      NEW.review_request_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;

  SELECT template_id INTO v_template FROM crm_review_rule
   WHERE tenant_id = NEW.tenant_id AND id = NEW.rule_id;

  IF NEW.variant_id IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM crm_review_message_variant
                    WHERE tenant_id = NEW.tenant_id AND id = NEW.variant_id) THEN
      RAISE EXCEPTION '话术变体 % 不存在（tenant=%），不能登记生效策略的落地',
        NEW.variant_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
    END IF;
    IF v_template IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM crm_review_message_variant
         WHERE tenant_id = NEW.tenant_id AND id = NEW.variant_id AND template_id = v_template) THEN
      RAISE EXCEPTION '话术变体 % 不属于规则 % 引用的模板：把别的模板的臂记到这条规则下，'
        'A/B 效果对比会拿错臂', NEW.variant_id, NEW.rule_id USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_rule_assignment_target_trg
  BEFORE INSERT OR UPDATE ON crm_review_rule_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_rule_assignment_target();

-- 6.4 分配的身份列建后不可改：改了之后「哪个变体带来了这次回收」在历史上会有两个答案。
CREATE FUNCTION guard_crm_review_rule_assignment_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_id IS DISTINCT FROM OLD.rule_id THEN
    RAISE EXCEPTION '生效策略落地的规则引用建后不可改：改它会让「哪条规则带来了这次回收」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.variant_id IS DISTINCT FROM OLD.variant_id THEN
    RAISE EXCEPTION '生效策略落地的话术变体建后不可改：A/B 效果对比是按变体拆的，改它等于改写结论' USING ERRCODE='23514';
  END IF;
  IF NEW.template_id IS DISTINCT FROM OLD.template_id THEN
    RAISE EXCEPTION '生效策略落地的话术模板建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.review_request_id IS DISTINCT FROM OLD.review_request_id THEN
    RAISE EXCEPTION '生效策略落地的索评请求引用建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.delay_hours IS DISTINCT FROM OLD.delay_hours
     OR NEW.scheduled_at IS DISTINCT FROM OLD.scheduled_at
     OR NEW.shifted_days IS DISTINCT FROM OLD.shifted_days
     OR NEW.holiday_name IS DISTINCT FROM OLD.holiday_name THEN
    RAISE EXCEPTION '生效策略落地的规则快照建后不可改：改它会让「这次为什么排到这个时刻」无从查证' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_rule_assignment_identity_trg
  BEFORE UPDATE ON crm_review_rule_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_rule_assignment_identity();

-- 6.5 有分配的规则不可删：删掉之后效果对比里那一段历史会凭空消失。
CREATE FUNCTION guard_crm_review_rule_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_count integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_review_rule_assignment
   WHERE tenant_id = OLD.tenant_id AND rule_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '索评规则 % 已经被 % 条生效策略落地引用，不能删除：删掉之后效果对比里那一段历史会凭空消失（结论被改写，而不是少了几条）。请改为把它置为 PAUSED',
      OLD.rule_no, v_count USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_review_rule_delete_trg
  BEFORE DELETE ON crm_review_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_rule_delete();

-- 6.6 有分配的变体不可删（理由同 6.5）。
CREATE FUNCTION guard_crm_review_message_variant_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_count integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_review_rule_assignment
   WHERE tenant_id = OLD.tenant_id AND variant_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '话术变体 % 已经被 % 条生效策略落地引用，不能删除：请改为把它置为 PAUSED',
      OLD.variant_code, v_count USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_review_message_variant_delete_trg
  BEFORE DELETE ON crm_review_message_variant
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_message_variant_delete();

-- 6.7 被变体 / 规则 / 分配引用的模板不可删。
CREATE FUNCTION guard_crm_review_message_template_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_count integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_review_message_variant
   WHERE tenant_id = OLD.tenant_id AND template_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '话术模板 % 下面还挂着 % 个变体，不能删除：请先删变体，或把模板置为 PAUSED',
      OLD.template_no, v_count USING ERRCODE='23514';
  END IF;
  SELECT count(*) INTO v_count FROM crm_review_rule
   WHERE tenant_id = OLD.tenant_id AND template_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '话术模板 % 还被 % 条索评规则引用，不能删除：请先改这些规则',
      OLD.template_no, v_count USING ERRCODE='23514';
  END IF;
  SELECT count(*) INTO v_count FROM crm_review_rule_assignment
   WHERE tenant_id = OLD.tenant_id AND template_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '话术模板 % 还被 % 条生效策略落地引用，不能删除',
      OLD.template_no, v_count USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_review_message_template_delete_trg
  BEFORE DELETE ON crm_review_message_template
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_message_template_delete();

-- ============================================================================
-- 7. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_review_rule','crm_review_message_template','crm_review_message_variant','crm_review_holiday','crm_review_rule_assignment'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_review_rule, crm_review_message_template, crm_review_message_variant, crm_review_holiday, crm_review_rule_assignment TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_review_rule, crm_review_message_template, crm_review_message_variant, crm_review_holiday, crm_review_rule_assignment TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条规则就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_review_rule_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_rule_no_seq TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_message_template_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_message_template_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 8. 留给上游 / 下游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.1.1.2 消息发送**（**未交付**）：本模块只**产出**话术与生效策略，**不发送**。
--     变体分配落在 crm_review_rule_assignment 上，等下游接上时按它发。
--     本模块**刻意不写**「已发送 / 已送达」这类回执 —— 自己写的「已发送」会让
--     「买家到底收到没收到」看起来发生过，而那正是过度索评投诉的源头。
--   * **15.2.2.1 索评任务**（已交付，V220）：本模块**只读**它，取店铺 / 买家 / 订单 /
--     签收时刻 / 发送状态 / 评价回收。写入所有者仍然只有它一个。
--     本模块**改不动**它的频次硬上限（crm_review_frequency_policy）——
--     本文件零 ALTER 既有表。
--   * **评价同步**（2 同步未交付 15.2.1.1）：效果对比的「回收」来自 15.2.2.1 的
--     **评价回收桶**（人工登记），不是平台自动同步。capabilities 里 review_sync 维度
--     available=false（ownerModule=15.2.1.1）。
