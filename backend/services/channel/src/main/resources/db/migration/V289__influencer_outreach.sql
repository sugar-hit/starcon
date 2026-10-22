-- ===========================================================================
-- 14.5.1.2 建联与样品（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.5.1.2 建联与样品
--   - 职责：达人建联流程：按规则筛选达人 → 模板化私信邀约（走官方接口）→
--           寄样管理（样品单、物流、签收）→ 合作跟进 → 佣金设置。
--   - 输入：达人筛选条件；邀约模板；样品寄送信息；佣金比例。
--   - 输出：建联记录 + 样品单 + 合作状态跟踪。
--   - 关系：上游 = 14.5.1.1；下游 = 8 仓库（样品出库）、10 物流（样品物流）、
--           12 财务（佣金计提）。
--           **建联必须走官方接口**，批量私信是平台风控重点，第三方工具易导致账号风险。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 策略 / 触发器**，零 ALTER 任何既有表：
--   * V288 `influencer`（**同库同服务**，14.5.1.1 已交付）—— 建联记录与样品单挂达人，
--     写法照抄 V288 `influencer_cooperation` 用的 `tenant_id` + 复合 FK
--   * V1 `platform`（全局平台码表）+ V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
--
-- ---------------------------------------------------------------------------
-- 一、🔴 三条事实边界（写进库，不是写进注释而已）
-- ---------------------------------------------------------------------------
-- 1. **建联必须走官方接口**（蓝图加粗句，平台风控红线）。
--    所以：
--      * `influencer_outreach.contact_channel` **DB CHECK 钉死为 'OFFICIAL_API'** ——
--        写不进第三方群发通道的形状（批量群发是平台风控重点）；
--      * `influencer_outreach.idempotency_key` **UNIQUE(tenant_id, idempotency_key)** ——
--        重放一次发送不会发出第二条私信；
--      * `influencer_outreach_policy`（按平台的**每日发送上限** + **最小间隔秒数**）——
--        频率上限是**配置事实**，不是散落在代码里的常量；
--      * **没有批量群发端点**：批量能力只做到「按规则批量生成草稿」，
--        每一条草稿仍要**逐条**登记平台回执才变成 SENT。
-- 2. **8 仓库 / 10 物流 / 12 财务三个下游在本仓基线里都没有可调用的授权 API**
--    （warehouse 服务没有「达人样品出库」端点，物流没有「达人样品轨迹」端点，
--    finance 服务**根本不存在**）⇒ 样品出库 / 物流 / 佣金计提**只能在本服务内留本地事实**，
--    并且每个缺口都要**在库里说清「本地事实 ≠ 下游已交付」**：
--      * `influencer_sample_order.outbound_status` = LOCAL_PENDING / LOCAL_OUTBOUNDED
--        （本地记录的出库事实，**不是**仓库那边的真实单号）
--      * `influencer_sample_order.warehouse_outbound_ref` —— 留空字符串占位，
--        等 8 仓库交付后再回填；**DB 不允许它凭空变成一个假单号**
--      * `influencer_sample_order.tracking_available` **DB CHECK 恒为 false**
--        + `tracking_owner_module` = '10 物流'
--      * `influencer_commission_accrual.finance_posted` **DB CHECK 恒为 false**
--        + `finance_owner_module` = '12 财务'
--    **零跨库 FOREIGN KEY、零跨库视图、零跨服务 SQL**。
-- 3. **平台达人建联接口（私信）未交付** ⇒ 平台侧发送只能留一个**端口**，
--    当前实现对所有平台答「未接入」，**不返回任何达人、不编造平台接口、
--    不返回硬编码列表**。手动登记「邀约已发送 + 平台回执」这条路**完整做完**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 为什么「筛选条件 / 邀约模板 / 风控策略」是**租户级配置事实**
-- ---------------------------------------------------------------------------
-- 这三张表都不按店铺收敛（与 V288 `influencer_tag_group` / `influencer_rating_rule`、
-- V284 `ad_promotion_guardrail`、V278 `ad_budget_rule` 同一纪律）：
-- 一个运营配一条「美妆类目 + MACRO 以上 + 互动率 ≥ 3%」的筛选规则，
-- 期望它在**每一家自己的店铺**上都能用 —— 按店铺收敛会让同一套口径在每个店铺
-- 各存一份，改一次要改 N 次。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 样品单与包裹为什么分成两张表
-- ---------------------------------------------------------------------------
-- 「样品单」是**一次寄样这件事**（寄给谁、寄什么、多少钱、签收没签收），
-- 「包裹」是**一次发运**（承运商 / 物流单号 / 发货时刻 / 签收时刻）。
-- 一张样品单可能分批发（补寄、换地址），所以是 1 : N。
-- 糊成一张表就答不出「这单里的东西是不是都到了」——
-- 那正是寄样最常吵的一个点（达人说没收到，仓库说已寄出）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 佣金规则与佣金计提为什么分成两张表
-- ---------------------------------------------------------------------------
-- 「佣金规则」是**配置**（这个达人 / 这个分群按多少比例给佣金），
-- 「佣金计提」是**事实**（某次合作按当时的规则计提了多少钱）。
-- 计提行必须**快照规则**（commission_type / commission_rate / fixed_amount）——
-- 规则后来改了，已计提的那几行不能跟着变，否则账算不清。
--
-- 建表顺序（有外键依赖，顺序不能换）：
--   1 influencer_outreach_segment    筛选条件（配置）
--   2 influencer_outreach_template   邀约模板（配置）
--   3 influencer_outreach_policy     风控策略（配置，按平台）
--   4 influencer_commission_rule     佣金规则（配置）
--   5 influencer_outreach            建联记录（事实）
--   6 influencer_outreach_followup   合作跟进（事实）
--   7 influencer_sample_order        样品单（事实）
--   8 influencer_sample_shipment     样品包裹（事实）
--   9 influencer_commission_accrual  佣金计提（事实）
-- ===========================================================================

-- ===========================================================================
-- 1. influencer_outreach_segment · 达人筛选条件（租户级配置）
-- ===========================================================================
-- 蓝图输入：「达人筛选条件」。这是建联流程第一步「按规则筛选达人」的规则载体。
--
-- 🔴 条件之间的关系是 **AND（交集）**：填了哪几个就同时满足哪几个。
--   写成 OR 的话「类目=美妆 或 粉丝数>100万」会把明显不合适的达人捞进来，
--   而建联是**风控敏感**动作（乱发私信 = 账号风险），宁严勿松。
--   例外是 tag_codes：**多个标签之间是 OR**（命中任意一个标签即命中），
--   因为「打标」的语义本来就是「这个标签的池子」，多个池子的并集才是运营的意思。
CREATE TABLE influencer_outreach_segment (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  segment_code varchar(64) NOT NULL,
  segment_name varchar(200) NOT NULL,
  description text NOT NULL DEFAULT '',

  -- === 筛选条件（全部 AND；tag_codes 内部 OR）===

  -- 🔴 空串 = **不限**（不是「类目为空」）。用空串而不是 null 是为了让筛选条件
  --   读起来像一句完整的 SQL 条件（`= ''` 恒假 ⇒ 该列不参与筛选）。
  category varchar(64) NOT NULL DEFAULT '',
  follower_tier text NOT NULL DEFAULT '' CHECK (follower_tier IN (
    '','NANO','MICRO','MID','MACRO','MEGA','TOP')),
  min_follower_count bigint CHECK (min_follower_count IS NULL OR min_follower_count >= 0),
  min_engagement_rate NUMERIC(19,6)
    CHECK (min_engagement_rate IS NULL OR (min_engagement_rate >= 0 AND min_engagement_rate <= 1)),
  min_gmv NUMERIC(19,4) CHECK (min_gmv IS NULL OR min_gmv >= 0),
  rating_tier_code varchar(32) NOT NULL DEFAULT '',
  -- 🔴 标签编码列表（逗号分隔；命中任意一个即命中）。
  --   不用关联表：筛选条件是**配置**（不是事实），配置里存一份可读的编码列表更好维护。
  tag_codes text NOT NULL DEFAULT '',

  -- === 平台与店铺收敛 ===
  platform_id uuid REFERENCES platform(id),
  shop_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴 黑名单里的达人**默认排除**。给黑名单专门做建联是运营事故的高发点，
  --   所以默认排除，要给黑名单发私信得显式打开这个开关。
  exclude_blacklisted boolean NOT NULL DEFAULT true,

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, segment_code),
  CHECK (btrim(segment_code) <> '' AND btrim(segment_name) <> '')
);

CREATE INDEX influencer_outreach_segment_status
  ON influencer_outreach_segment(tenant_id, status, segment_code);

-- ===========================================================================
-- 2. influencer_outreach_template · 邀约模板（租户级配置）
-- ===========================================================================
-- 蓝图输入：「邀约模板」。蓝图职责句：「模板化私信邀约」——
-- 所以模板是**配置**，而**渲染结果快照**落在建联记录上。
--
-- 🔴 模板面向**单个平台**（platform_id NOT NULL）：不同平台的私信长度限制、
--   允许的链接数量、合规话术都不一样，混成一个模板必然踩其中一个平台的规则。
CREATE TABLE influencer_outreach_template (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  template_code varchar(64) NOT NULL,
  template_name varchar(200) NOT NULL,

  -- 🔴 模板面向的平台。引用全局码表 platform(id)（无 tenant_id，它就是全局码表）。
  platform_id uuid NOT NULL REFERENCES platform(id),

  -- 🔴 标题 + 正文。正文里的变量占位符是**闭合词表**（见服务层渲染），
  --   未登记的占位符渲染时会原样留下并被服务层拒绝（防止模板作者写错变量
  --   却发出一堆 `{{xxx}}` 给达人）。
  subject varchar(300) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, template_code),
  CHECK (btrim(template_code) <> '' AND btrim(template_name) <> ''),
  CHECK (btrim(body) <> '')
);

CREATE INDEX influencer_outreach_template_status
  ON influencer_outreach_template(tenant_id, status, platform_id);

-- ===========================================================================
-- 3. influencer_outreach_policy · 风控策略（按平台，租户级配置）
-- ===========================================================================
-- 🔴 蓝图加粗句「建联必须走官方接口，批量私信是平台风控重点」——
--   风控这件事必须**可配置**（不同平台额度不同），也必须**留痕**（出事了要能查
--   当时的上限是多少）。
--
-- daily_send_limit：同一租户同一平台**每天**最多登记多少条「已发送」。
-- min_interval_seconds：两条「已发送」之间至少隔多少秒（防抖）。
--
-- 一个租户一个平台**只能有一条策略**（UNIQUE(tenant_id, platform_id)）。
CREATE TABLE influencer_outreach_policy (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  platform_id uuid NOT NULL REFERENCES platform(id),

  daily_send_limit int NOT NULL DEFAULT 20 CHECK (daily_send_limit > 0),
  min_interval_seconds int NOT NULL DEFAULT 60 CHECK (min_interval_seconds >= 0),

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, platform_id)
);

-- ===========================================================================
-- 4. influencer_commission_rule · 佣金规则（租户级配置）
-- ===========================================================================
-- 蓝图输入：「佣金比例」。
--
-- 🔴 佣金只能是两种形态，**没有第三种**：
--   RATE  比例（commission_rate，0 ~ 1）
--   FIXED 固定额（fixed_amount）
-- 混合形态（「先给 5% 再减 20 美元」）在 DB 层用 CHECK 直接拒绝 ——
-- 那种口径在佣金计提时算不清（先乘后减还是先减后乘，差一个数量级）。
CREATE TABLE influencer_commission_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  rule_code varchar(64) NOT NULL,
  rule_name varchar(200) NOT NULL,

  -- 🔴 适用范围：GLOBAL 全局 / SEGMENT 某个分群 / INFLUENCER 某个达人。
  --   SEGMENT 时 segment_id 必填，INFLUENCER 时 influencer_id 必填（DB CHECK 钉死）。
  scope_type text NOT NULL CHECK (scope_type IN ('GLOBAL','SEGMENT','INFLUENCER')),
  segment_id uuid,
  FOREIGN KEY (tenant_id, segment_id)
    REFERENCES influencer_outreach_segment(tenant_id, id) ON DELETE CASCADE,
  influencer_id uuid,
  FOREIGN KEY (tenant_id, influencer_id)
    REFERENCES influencer(tenant_id, id) ON DELETE CASCADE,

  commission_type text NOT NULL CHECK (commission_type IN ('RATE','FIXED')),
  commission_rate NUMERIC(19,6)
    CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1)),
  fixed_amount NUMERIC(19,4) CHECK (fixed_amount IS NULL OR fixed_amount >= 0),
  currency char(3) NOT NULL DEFAULT '',

  -- 🔴 生效区间：timestamptz。佣金规则要能按时间切片，
  --   否则「上个月按 10%，这个月按 15%」这件事记不下来。
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code),
  CHECK (btrim(rule_code) <> '' AND btrim(rule_name) <> ''),
  CHECK (effective_to IS NULL OR effective_to > effective_from),
  -- 🔴 形态与取值必须自洽：RATE ⇒ 有比例无固定额；FIXED ⇒ 有固定额无比例。
  CHECK ((commission_type = 'RATE' AND commission_rate IS NOT NULL AND fixed_amount IS NULL)
      OR (commission_type = 'FIXED' AND fixed_amount IS NOT NULL AND commission_rate IS NULL)),
  -- 🔴 适用范围与指向必须自洽。
  CHECK ((scope_type = 'GLOBAL' AND segment_id IS NULL AND influencer_id IS NULL)
      OR (scope_type = 'SEGMENT' AND segment_id IS NOT NULL AND influencer_id IS NULL)
      OR (scope_type = 'INFLUENCER' AND segment_id IS NULL AND influencer_id IS NOT NULL))
);

CREATE INDEX influencer_commission_rule_status
  ON influencer_commission_rule(tenant_id, status, scope_type);
CREATE INDEX influencer_commission_rule_segment
  ON influencer_commission_rule(tenant_id, segment_id);
CREATE INDEX influencer_commission_rule_influencer
  ON influencer_commission_rule(tenant_id, influencer_id);

-- ===========================================================================
-- 5. influencer_outreach · 建联记录（事实）
-- ===========================================================================
-- 蓝图输出：「建联记录 + 合作状态跟踪」。
--
-- 🔴 一条记录 = **一次邀约**（一个达人 × 一个模板 × 一个分群）。
--   批量能力（批量生成草稿）落地成**多条记录**，而不是一条带数组的记录 ——
--   一条一邀约才能逐条登记平台回执、逐条追状态、逐条算佣金。
CREATE TABLE influencer_outreach (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 14.5.1.1 达人档案（复合 FK 带 tenant_id ⇒ 跨租户挂载不可能）===
  influencer_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, influencer_id)
    REFERENCES influencer(tenant_id, id) ON DELETE RESTRICT,

  -- === 本模块自己的配置（复合 FK 带 tenant_id）===
  segment_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, segment_id)
    REFERENCES influencer_outreach_segment(tenant_id, id) ON DELETE RESTRICT,
  template_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, template_id)
    REFERENCES influencer_outreach_template(tenant_id, id) ON DELETE RESTRICT,

  -- === 上游 2.1.1.1 平台 + 2.3.1.1 店铺 ===
  platform_id uuid NOT NULL REFERENCES platform(id),
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE RESTRICT,

  -- 🔴🔴 沟通渠道：**DB CHECK 钉死只能是官方接口**。
  --   蓝图加粗句「建联必须走官方接口」是风控红线 ——
  --   批量私信是平台风控重点，第三方工具易导致账号风险。
  --   所以这里**写不进**任何第三方群发通道的形状。
  contact_channel text NOT NULL DEFAULT 'OFFICIAL_API'
    CHECK (contact_channel = 'OFFICIAL_API'),

  -- 🔴🔴 幂等键（UNIQUE）：同一租户内重复登记同一条发送**不会**发出第二条私信。
  --   批量生成时由服务层按「批次键 + 达人 id」派生，单条登记时由调用方给定。
  idempotency_key varchar(128) NOT NULL,

  -- 🔴 渲染结果快照：模板后来改了，**已发出的那条不改口**（改口 = 篡改历史）。
  subject varchar(300) NOT NULL DEFAULT '',
  message_body text NOT NULL DEFAULT '',

  -- 🔴 合作状态跟踪（服务层状态机 + DB CHECK 双钉）：
  --   DRAFT      草稿（还没发出去）
  --   SENT       已发送（平台回执已登记）
  --   REPLIED    已回复
  --   ACCEPTED   已接受合作
  --   REJECTED   已拒绝（终态）
  --   NO_REPLY   无回复（终态）
  --   COOPERATING 合作中
  --   COMPLETED  已完成（终态）
  --   CANCELLED  已取消（终态）
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
    'DRAFT','SENT','REPLIED','ACCEPTED','REJECTED','NO_REPLY','COOPERATING','COMPLETED','CANCELLED')),

  -- 🔴 平台回执（**手动登记**：平台私信接口未交付时，运营自己在官方后台发完，
  --   再把「平台消息 id / 回执内容 / 发送时刻」登记回来）。
  platform_message_id varchar(128) NOT NULL DEFAULT '',
  platform_receipt text NOT NULL DEFAULT '',
  sent_at timestamptz,
  replied_at timestamptz,
  closed_at timestamptz,

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),
  CHECK (btrim(idempotency_key) <> ''),
  -- 🔴 发出去了就必须有发送时刻；还没发（DRAFT）就不许有发送时刻。
  --   CANCELLED 是例外：从 DRAFT 直接取消的邀约**从来没有发出去**，所以 sent_at 必须为空。
  CHECK (status NOT IN ('SENT','REPLIED','ACCEPTED','REJECTED','NO_REPLY','COOPERATING','COMPLETED')
      OR sent_at IS NOT NULL),
  CHECK (closed_at IS NULL OR sent_at IS NULL OR closed_at >= sent_at)
);

-- 🔴 一个达人**同时只能有一条「没走完」的邀约**（partial unique index）。
--   走完 = REJECTED / NO_REPLY / COMPLETED / CANCELLED。
--   没有这条，两个运营各建一条草稿、同一批私信发两遍 —— 这是风控事故的经典成因。
CREATE UNIQUE INDEX influencer_outreach_open_unique
  ON influencer_outreach(tenant_id, influencer_id)
  WHERE status IN ('DRAFT','SENT','REPLIED','ACCEPTED','COOPERATING');

-- 🔴 列表主索引：租户 + 状态 + 发出时刻（建联台账最常见的排序）。
-- 🔴 索引名**不能与表名同名**（PostgreSQL 里表与索引共用同一个 relation 命名空间），
--   所以这里是 `..._by_segment` / `..._by_template` 而不是 `..._segment` / `..._template`。
CREATE INDEX influencer_outreach_status ON influencer_outreach(tenant_id, status, sent_at DESC);
CREATE INDEX influencer_outreach_influencer ON influencer_outreach(tenant_id, influencer_id);
CREATE INDEX influencer_outreach_by_segment ON influencer_outreach(tenant_id, segment_id, status);
CREATE INDEX influencer_outreach_by_template ON influencer_outreach(tenant_id, template_id);
CREATE INDEX influencer_outreach_shop ON influencer_outreach(tenant_id, shop_code, status);
CREATE INDEX influencer_outreach_platform ON influencer_outreach(tenant_id, platform_id, status);
-- 🔴 频率上限的计数走这条索引（按平台 + 发出时刻数当天条数）。
CREATE INDEX influencer_outreach_rate ON influencer_outreach(tenant_id, platform_id, sent_at DESC);

-- ===========================================================================
-- 6. influencer_outreach_followup · 合作跟进（事实）
-- ===========================================================================
-- 蓝图职责句里的「合作跟进」。跟进是**追加型事实**（不覆盖），
-- 所以这张表只有 INSERT 语义，没有状态机。
CREATE TABLE influencer_outreach_followup (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  outreach_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, outreach_id)
    REFERENCES influencer_outreach(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 跟进方式封闭词表：电话 / 站内私信 / 邮件 / 样品反馈 / 谈判 / 其它。
  --   「其它」是**有**的 —— 跟进方式这件事留一个兜底桶，
  --   否则运营只能硬塞进「谈判」或「邮件」，两类都变成噪音。
  followup_type text NOT NULL CHECK (followup_type IN (
    'CALL','IM','EMAIL','SAMPLE_FEEDBACK','NEGOTIATION','OTHER')),

  content text NOT NULL,
  occurred_at timestamptz NOT NULL,

  -- 🔴 下次跟进时刻（timestamptz）：跟进是要排期的，不是记流水账。
  next_followup_at timestamptz,

  owner_user_id uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK (btrim(content) <> ''),
  CHECK (next_followup_at IS NULL OR next_followup_at >= occurred_at)
);

CREATE INDEX influencer_outreach_followup_outreach
  ON influencer_outreach_followup(tenant_id, outreach_id, occurred_at DESC);
CREATE INDEX influencer_outreach_followup_next
  ON influencer_outreach_followup(tenant_id, next_followup_at);

-- ===========================================================================
-- 7. influencer_sample_order · 样品单（事实）
-- ===========================================================================
-- 蓝图职责句：「寄样管理（样品单、物流、签收）」；蓝图输入：「样品寄送信息」。
--
-- 🔴 样品是**商品**（catalog 的 SKU，独立服务、独立库）⇒ **逻辑引用 + 快照**，
--   **零跨库 FOREIGN KEY**（AGENTS.md：禁止跨服务数据库访问）。
--   sku_code 是引用，sku_name 是快照（catalog 改名后历史样品单还看得到当时的名字）。
CREATE TABLE influencer_sample_order (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游：本模块自己的建联记录 + 14.5.1.1 达人档案 ===
  outreach_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, outreach_id)
    REFERENCES influencer_outreach(tenant_id, id) ON DELETE RESTRICT,
  influencer_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, influencer_id)
    REFERENCES influencer(tenant_id, id) ON DELETE RESTRICT,

  -- === 上游 2.3.1.1 店铺（数据范围随店铺收敛）===
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE RESTRICT,

  -- === 样品（catalog SKU，逻辑引用 + 快照，零跨库 FK）===
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 🔴 数量 NUMERIC(19,6)（不锁死整数 —— 寄样有时按重量/体积算）。
  quantity NUMERIC(19,6) NOT NULL DEFAULT 1 CHECK (quantity > 0),

  -- 🔴 样品成本（本地事实）。**12 财务未交付 ⇒ 未进费用归集**，
  --   计提那一侧另有 `influencer_commission_accrual`。
  sample_value NUMERIC(19,4) CHECK (sample_value IS NULL OR sample_value >= 0),
  sample_currency char(3) NOT NULL DEFAULT '',

  -- === 收件信息 ===
  recipient_name varchar(200) NOT NULL DEFAULT '',
  recipient_phone varchar(64) NOT NULL DEFAULT '',
  recipient_address text NOT NULL DEFAULT '',

  -- 🔴 样品单状态（服务层状态机 + DB CHECK 双钉）：
  --   REQUESTED  已申请（待出库）
  --   OUTBOUNDED 已寄出（本地事实）
  --   DELIVERED  已送达（本地事实）
  --   SIGNED     已签收
  --   CANCELLED  已取消
  status text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN (
    'REQUESTED','OUTBOUNDED','DELIVERED','SIGNED','CANCELLED')),

  -- 🔴🔴 8 仓库（样品出库）**未交付** ⇒ 出库只是**本地事实**：
  --   outbound_status 是本地记录，warehouse_outbound_ref 是给 8 仓库留的回填位
  --   （**未交付 ⇒ 恒为空串**；回填要等 8 仓库真的交付之后）。
  outbound_status text NOT NULL DEFAULT 'LOCAL_PENDING' CHECK (outbound_status IN (
    'LOCAL_PENDING','LOCAL_OUTBOUNDED')),
  outbound_at timestamptz,
  warehouse_outbound_ref varchar(128) NOT NULL DEFAULT '',
  warehouse_owner_module text NOT NULL DEFAULT '8 仓库',

  -- 🔴🔴 10 物流（样品物流）**未交付** ⇒ 真实轨迹**不可查**。
  --   DB CHECK 恒为 false：写 true 等于宣称「物流已接通」，那是假事实。
  tracking_available boolean NOT NULL DEFAULT false CHECK (tracking_available = false),
  tracking_owner_module text NOT NULL DEFAULT '10 物流',

  signed_at timestamptz,
  signed_by varchar(200) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 同一张邀约下同一个 SKU 只寄一次（重复提交是幂等的，不是错）。
  UNIQUE(tenant_id, outreach_id, sku_code),
  CHECK (btrim(sku_code) <> ''),
  -- 🔴 出了库（含已送达 / 已签收）就必须有出库时刻。
  CHECK (status NOT IN ('OUTBOUNDED','DELIVERED','SIGNED') OR outbound_at IS NOT NULL),
  CHECK (status <> 'SIGNED' OR signed_at IS NOT NULL)
);

CREATE INDEX influencer_sample_order_status
  ON influencer_sample_order(tenant_id, status, created_at DESC);
CREATE INDEX influencer_sample_order_influencer
  ON influencer_sample_order(tenant_id, influencer_id, status);
CREATE INDEX influencer_sample_order_outreach
  ON influencer_sample_order(tenant_id, outreach_id);
CREATE INDEX influencer_sample_order_shop
  ON influencer_sample_order(tenant_id, shop_code, status);

-- ===========================================================================
-- 8. influencer_sample_shipment · 样品包裹（事实）
-- ===========================================================================
-- 🔴 一张样品单可以**分批发**（补寄、换地址），所以包裹是 1 : N（见第三节）。
--   签收挂在**包裹**上（谁签的、什么时候签的），样品单的 SIGNED 由
--   「全部包裹都签收」推导 —— 这正是「达人到底收到没」要的那个答案。
CREATE TABLE influencer_sample_shipment (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  sample_order_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, sample_order_id)
    REFERENCES influencer_sample_order(tenant_id, id) ON DELETE CASCADE,

  carrier_code varchar(64) NOT NULL DEFAULT '',
  tracking_no varchar(128) NOT NULL DEFAULT '',

  package_count NUMERIC(19,6) NOT NULL DEFAULT 1 CHECK (package_count > 0),

  -- 🔴 包裹状态：SHIPPED 已发出 / IN_TRANSIT 运输中 / DELIVERED 已送达 /
  --   SIGNED 已签收 / LOST 丢件 / RETURNED 退回。
  --   🔴 LOST / RETURNED 是**必须有的终态**：寄样丢件是真实发生的事，
  --   没有这两个值就只能记成「一直没签收」，那会把丢件和拖延混成一类。
  status text NOT NULL DEFAULT 'SHIPPED' CHECK (status IN (
    'SHIPPED','IN_TRANSIT','DELIVERED','SIGNED','LOST','RETURNED')),

  shipped_at timestamptz NOT NULL,
  delivered_at timestamptz,
  signed_at timestamptz,
  signed_by varchar(200) NOT NULL DEFAULT '',

  -- 🔴🔴 10 物流未交付 ⇒ 轨迹只有**本地快照**，真实轨迹不可查。
  tracking_available boolean NOT NULL DEFAULT false CHECK (tracking_available = false),
  tracking_owner_module text NOT NULL DEFAULT '10 物流',

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK (status NOT IN ('DELIVERED','SIGNED') OR delivered_at IS NOT NULL),
  CHECK (status <> 'SIGNED' OR signed_at IS NOT NULL)
);

CREATE INDEX influencer_sample_shipment_order
  ON influencer_sample_shipment(tenant_id, sample_order_id, shipped_at DESC);
CREATE INDEX influencer_sample_shipment_status
  ON influencer_sample_shipment(tenant_id, status, shipped_at DESC);

-- ===========================================================================
-- 9. influencer_commission_accrual · 佣金计提（事实）
-- ===========================================================================
-- 🔴🔴 12 财务（佣金计提）**在本仓基线里不存在**（backend/services/ 下没有 finance 服务）
--   ⇒ 计提金额**只落本地事实**，DB CHECK 钉死 finance_posted = false，
--   并带上 finance_owner_module = '12 财务'。
--   **不假装已经入账**，也**不造一张「财务费用」表**去冒充 12 财务的交付物。
--
-- 🔴 计提行**快照规则**（commission_type / commission_rate / fixed_amount）：
--   规则后来改了，已计提的这几行不动 —— 否则佣金账算不清。
CREATE TABLE influencer_commission_accrual (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  outreach_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, outreach_id)
    REFERENCES influencer_outreach(tenant_id, id) ON DELETE RESTRICT,
  influencer_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, influencer_id)
    REFERENCES influencer(tenant_id, id) ON DELETE RESTRICT,

  -- 规则可空：计提时规则可能已被删（规则删了不影响已计提的事实）。
  rule_id uuid,
  FOREIGN KEY (tenant_id, rule_id)
    REFERENCES influencer_commission_rule(tenant_id, id) ON DELETE SET NULL,

  -- 🔴 规则快照。
  commission_type text NOT NULL CHECK (commission_type IN ('RATE','FIXED')),
  commission_rate NUMERIC(19,6)
    CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1)),
  fixed_amount NUMERIC(19,4) CHECK (fixed_amount IS NULL OR fixed_amount >= 0),

  -- 🔴 计提基数：带货 GMV（本地口径）。
  --   🔴 **归因口径归 14.5.2.1 带货归因，未交付** —— 所以这里的基数是
  --   **人工登记**的归因 GMV，不是系统算出来的订单归因结果。
  base_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (base_amount >= 0),
  base_currency char(3) NOT NULL DEFAULT '',

  -- 🔴 计提金额：RATE ⇒ 基数 × 比例；FIXED ⇒ 固定额。由服务层算，DB 只钉非负。
  accrued_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (accrued_amount >= 0),
  accrued_currency char(3) NOT NULL DEFAULT '',

  -- 计提状态：PENDING 待确认 / ACCRUED 已计提（本地） / POSTED 已入财务
  --   （POSTED **在 12 财务交付前不可能出现** —— DB 不禁止写，但服务层不提供该动作）。
  status text NOT NULL DEFAULT 'ACCRUED' CHECK (status IN (
    'PENDING','ACCRUED','POSTED','CANCELLED')),

  -- 🔴🔴 12 财务未交付 ⇒ 恒为 false（DB CHECK 钉死，写 true 会被数据库直接拒）。
  finance_posted boolean NOT NULL DEFAULT false CHECK (finance_posted = false),
  finance_owner_module text NOT NULL DEFAULT '12 财务',

  accrued_at timestamptz NOT NULL DEFAULT now(),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK ((commission_type = 'RATE' AND commission_rate IS NOT NULL AND fixed_amount IS NULL)
      OR (commission_type = 'FIXED' AND fixed_amount IS NOT NULL AND commission_rate IS NULL)),
  CHECK (status = 'POSTED' OR finance_posted = false)
);

CREATE INDEX influencer_commission_accrual_outreach
  ON influencer_commission_accrual(tenant_id, outreach_id);
CREATE INDEX influencer_commission_accrual_influencer
  ON influencer_commission_accrual(tenant_id, influencer_id, accrued_at DESC);
CREATE INDEX influencer_commission_accrual_status
  ON influencer_commission_accrual(tenant_id, status, accrued_at DESC);

-- ===========================================================================
-- 10. 多租户隔离：九张表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V93 / V146 / V273 / V277 / V284 / V288 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'influencer_outreach_segment', 'influencer_outreach_template', 'influencer_outreach_policy',
    'influencer_commission_rule',
    'influencer_outreach', 'influencer_outreach_followup',
    'influencer_sample_order', 'influencer_sample_shipment',
    'influencer_commission_accrual' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（照抄 V273 `ad_campaign` / V288 `influencer` 同构）
-- ---------------------------------------------------------------------------
-- 筛选条件 / 邀约模板 / 风控策略 / 佣金规则是**租户级配置事实**，不按店铺收敛 ——
-- 与 V288 `influencer_tag_group` / `influencer_rating_rule`、
-- V284 `ad_promotion_guardrail`、V278 `ad_budget_rule` 同一纪律。
-- 建联记录 / 样品单挂店铺；跟进 / 包裹 / 计提经父行收敛。
CREATE POLICY data_scope ON influencer_outreach AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer_outreach.tenant_id AND s.code=influencer_outreach.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer_outreach.tenant_id AND s.code=influencer_outreach.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON influencer_sample_order AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer_sample_order.tenant_id AND s.code=influencer_sample_order.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer_sample_order.tenant_id AND s.code=influencer_sample_order.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON influencer_outreach_followup AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM influencer_outreach o JOIN shop s ON s.tenant_id=o.tenant_id AND s.code=o.shop_code
             WHERE o.tenant_id=influencer_outreach_followup.tenant_id AND o.id=influencer_outreach_followup.outreach_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM influencer_outreach o JOIN shop s ON s.tenant_id=o.tenant_id AND s.code=o.shop_code
             WHERE o.tenant_id=influencer_outreach_followup.tenant_id AND o.id=influencer_outreach_followup.outreach_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON influencer_sample_shipment AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM influencer_sample_order o JOIN shop s ON s.tenant_id=o.tenant_id AND s.code=o.shop_code
             WHERE o.tenant_id=influencer_sample_shipment.tenant_id AND o.id=influencer_sample_shipment.sample_order_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM influencer_sample_order o JOIN shop s ON s.tenant_id=o.tenant_id AND s.code=o.shop_code
             WHERE o.tenant_id=influencer_sample_shipment.tenant_id AND o.id=influencer_sample_shipment.sample_order_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON influencer_commission_accrual AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM influencer_outreach o JOIN shop s ON s.tenant_id=o.tenant_id AND s.code=o.shop_code
             WHERE o.tenant_id=influencer_commission_accrual.tenant_id AND o.id=influencer_commission_accrual.outreach_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM influencer_outreach o JOIN shop s ON s.tenant_id=o.tenant_id AND s.code=o.shop_code
             WHERE o.tenant_id=influencer_commission_accrual.tenant_id AND o.id=influencer_commission_accrual.outreach_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V273/V288 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  influencer_outreach_segment, influencer_outreach_template, influencer_outreach_policy,
  influencer_commission_rule,
  influencer_outreach, influencer_outreach_followup,
  influencer_sample_order, influencer_sample_shipment,
  influencer_commission_accrual TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V288 同口径）。
REVOKE ALL ON
  influencer_outreach_segment, influencer_outreach_template, influencer_outreach_policy,
  influencer_commission_rule,
  influencer_outreach, influencer_outreach_followup,
  influencer_sample_order, influencer_sample_shipment,
  influencer_commission_accrual FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：建联与样品管理属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.5.1.1 达人库同族，因此 identity 服务零改动，跨车道回归面 = 0。
