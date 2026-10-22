-- 15.6.2.2 购物车与弃单挽回（crm 服务）：管理独立站购物车与弃单挽回：
-- 采集加购 / 结账未完成事件，按超时策略触发挽回（邮件 / 短信 / 站内信），跟踪挽回率。
--
-- 蓝图 15 卷 15.6.2.2 的原话：
--   「职责：管理独立站购物车与弃单挽回：采集加购/结账未完成事件（Shopify 等提供 cart/checkout 事件），
--     按超时策略触发挽回（邮件/短信/站内信），跟踪挽回率；平台订单不适用此项。
--     输入：购物车/结账事件（2 渠道 Shopify 等）；挽回规则与话术；客户联系方式与退订状态。
--     输出：弃单清单 + 挽回触达记录 + 挽回率报表。
--     关系：上游 = 2 渠道（独立站事件）、15.5.2 触达；下游 = 9 订单（挽回成交）、13 分析。
--     **此项是独立站(DTC)特有能力**，平台订单无购物车概念；触达须遵守退订与频次上限，
--     挽回话术需人工确认后启用。」
--
-- ============================================================================
-- 零、本文件与既有模块的边界
-- ============================================================================
--
-- 本文件是 **crm 数据库的第四个迁移**（前三个是 15.4.2.1 知识库 V99 / 15.2.2.1 索评 V220 /
-- 15.2.2.2 索评规则 V228 / 15.1.2.1 买家邮件 V292）。本模块与卷 15 的其它已交付模块
-- **不共用任何一张表**：
--   * 15.2.2.1 索评回答「这一位买家、这一张已签收的单子，要不要向他发起一次索评」；
--   * 15.2.2.2 索评规则回答「按店铺 / 物流方式该套哪套策略与话术」；
--   * 15.1.2.1 买家邮件回答「这位买家发来的这封邮件是什么，我们回过没有」；
--   * **本模块回答「独立站这位买家加购 / 结账没走完，我们要不要挽回、挽回回来了没有」**。
-- 四者一个管触达对象、一个管触达策略、一个管邮件线程、一个管**购物车漏斗**，事实表与写入
-- 所有者都不同。
--
-- ============================================================================
-- 一、🔴 加粗句「此项是独立站(DTC)特有能力，平台订单无购物车概念」
-- ============================================================================
--
-- 这条加粗句在本文件里落成**三层**，而不是一句提示：
--
--   1. **表级 CHECK**：crm_cart_abandonment.platform_type 的 CHECK 是 `platform_type = 'DTC'`
--      —— 平台店铺的购物车事件**在数据库层就进不来**这一张表。即使服务层被绕过、
--      即使有人手工插一行，CHECK 也会当场拒绝。
--   2. **服务层拒绝**：登记弃单时按店铺的平台码判定平台类型；判定为平台店铺时
--      **明确 409 且不写任何数据**（错误信息写明「平台订单无购物车概念」）。
--      平台店铺的事件**不进弃单清单** —— 混进来会让挽回率报表整体失真。
--   3. **平台类型是快照**：每条弃单都存 platform_code + platform_type，
--      后面平台目录改了也不会让历史弃单的类型变样。
--
-- 平台类型的判定规则（**唯一判定点在服务层**）：
--   * 已知平台码（AMAZON / EBAY / TIKTOK_SHOP / TEMU / WALMART / SHOPEE / LAZADA /
--     MERCADO_LIBRE / ALIEXPRESS / ETSY / WAYFAIR）⇒ **MARKETPLACE**，不可声明成 DTC；
--   * SHOPIFY（独立站建站平台）⇒ **DTC**，不可声明成 MARKETPLACE；
--   * 未知平台码 ⇒ 租户必须**显式声明**（crm_cart_shop_profile），否则登记弃单一律 409。
--     声明存在时按声明取值。
--
-- ============================================================================
-- 二、🔴 加粗句「触达须遵守退订与频次上限」
-- ============================================================================
--
-- 两条都落成**服务端强制 + 数据库触发器兜底**（与 15.2.2.1 索评频次同一手法）：
--
--   1. **退订是硬拦**（guard_crm_cart_recovery_touch_unsubscribe）：
--      触达落库**之前**回查 crm_cart_unsubscribe，命中即 RAISE。
--      「先发出去、报表里再过滤」是**不成立**的 —— 已经发出去的邮件收不回来。
--   2. **频次上限可配置且有默认值**（crm_cart_recovery_policy）：
--      每买家每日上限 + 每周期（cycle_days 天）上限；上限值在 DB 层另有天花板
--      （「把上限调到 999」这条路本身被 CHECK 堵死）。
--      越界由 guard_crm_cart_recovery_touch_frequency 判定并 RAISE，
--      服务层用**同一判据**预检一遍并给**可分类的原因码** ——
--      **明确失败并写明原因**，不是静默跳过（静默跳过会让运营以为发过了）。
--
-- ============================================================================
-- 三、🔴 加粗句「挽回话术需人工确认后启用」
-- ============================================================================
--
-- 这是**状态机**，不是备注字段。crm_cart_recovery_message.status 的合法迁移：
--
--     DRAFT ──approve──▶ APPROVED ──enable──▶ ENABLED ──disable──▶ DISABLED
--                             ▲                    │                   │
--                             └──── approve ───────┴───────────────────┘
--
-- 三条 DB 级保证：
--   1. **未审核 / 未启用的话术不允许被触达流程选用**
--      （guard_crm_cart_recovery_touch_message：触达引用的��术必须 status='ENABLED'，
--      否则 RAISE）。这不是「前端不给选」—— 前端能选也会被服务端挡下。
--   2. **审核动作要留痕（谁、何时、哪个版本）**
--      （guard_crm_cart_recovery_message_state：进入 APPROVED 必须带 approved_by /
--      approved_at，且 approved_version = 当前 version）。
--   3. **改了内容就回到草稿**（同一个触发器）：内容一变，version 自动 +1、status 回到
--      DRAFT、审核与启用痕迹被清空 —— 否则就会出现「审核的是 A 版、发出去的是 B 版」。
--
-- ============================================================================
-- 四、跨服务纪律（零跨库 SQL / FK / 视图）
-- ============================================================================
--
-- 上游 2 渠道在 **channel** 服务（端口 8083）：
--   * 店铺目录 —— `GET /channel/shops`（2.3.1.1），本服务经授权 HTTP API 读；
--   * 平台购物车 / 结账事件 —— **未接入**：channel/vendor 下只有 19 个 OAuth 适配器，
--     **没有任何购物车事件适配器**。⇒ 本模块新增端口 `CartEventPort` +
--     当前实现 `UnavailableCartEventPort`（逐平台答「未接入」，**不返回任何购物车事件、
--     不编造平台接口、不返回硬编码列表、不抛异常**）；未接入时同步端点明确 **409**
--     且**不写任何数据**。
-- 本服务**一条 SQL 都不碰对方的库**，只经**授权 HTTP API** 读，本库只存逻辑引用 + 快照。
--
-- 下游未交付：
--   * 15.5.2.1 关怀与催办（触达）**未交付** ⇒ 本模块**只登记触达记录**，
--     触达状态恒为「已登记待发」（REGISTERED）；**绝不假装已经发出邮件 / 短信**。
--   * 9 订单（挽回成交）**未交付** ⇒ 成交订单号由运营**人工登记**，本模块**不编造**下游 id。
--   * 13 分析**未交付** ⇒ 本模块只出**挽回率报表**，不假装已进分析平台。
--   * 15.3.1.1 客户档案**未交付** ⇒ 买家标识与联系方式由运营按店铺提供，
--     本模块**不假装**拥有买家主数据。
--
-- ============================================================================
-- 五、时间与数量
-- ============================================================================
--
--   时间：一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     （用 `Timestamp.valueOf(LocalDateTime)` 会在东八区**静默偏 −28800 秒**。）
--   数量：一律 **numeric(19,6)** + BigDecimal，**禁浮点**（与 channel V247 / V255、
--     trade V239、crm V292 的 size_bytes 同一形状）。
--   金额：一律 **numeric(19,6)**（禁浮点记账）。
--
-- ============================================================================
-- 六、发布后不可变的强制位置
-- ============================================================================
--
--   1. guard_crm_cart_abandonment_identity      （弃单身份列建后不可改）
--   2. guard_crm_cart_recovery_message_state     （话术状态机 + 留痕 + 改内容回草稿）
--   3. guard_crm_cart_recovery_touch_target      （触达必须挂在存在的弃单上）
--   4. guard_crm_cart_recovery_touch_message     （触达只能引用**已启用**的话术）
--   5. guard_crm_cart_recovery_touch_unsubscribe  （🔴 退订硬拦：发出去之前查）
--   6. guard_crm_cart_recovery_touch_frequency    （🔴 频次上限）
--   7. guard_crm_cart_recovery_message_delete    （已被触达引用过的话术删不掉）
--   8. guard_crm_cart_abandonment_delete          （已有触达 / 恢复记录的弃单删不掉）
--   9. guard_crm_cart_shop_profile_identity      （店铺档案的平台码建后不可改）
--
-- ⚠️ 本文件**一律不建数据库外键**（含本文件自己新建的表之间）：
--   外键会打断同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a
--   foreign key constraint；batch-37 有车道踩过这个坑）。
--   同一手法：跨表引用用**触发器回表校验**（V99 / V220 / V228 / V292 同一纪律）。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V292 同一口径：角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_cart_recovery_policy · 租户级频次上限 + 弃单超时策略
-- ============================================================================
-- 一租户一行（UNIQUE (tenant_id)）。没有这一行时服务层用**默认值**，
-- 所以「第一次用这个模块」不需要先建策略。
--
-- 🔴 上限值在 DB 层另有天花板：「把上限调到 999」这条路本身被 CHECK 堵死 ——
--   没有天花板的频次上限等于没有上限。
CREATE TABLE crm_cart_recovery_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 每买家每日触达上限（同一 buyer_ref 在 24 小时窗口内）。
  max_touches_per_day integer NOT NULL DEFAULT 1
    CHECK (max_touches_per_day BETWEEN 1 AND 10),
  -- 每买家每周期触达上限（周期长度 = cycle_days 天）。
  max_touches_per_cycle integer NOT NULL DEFAULT 3
    CHECK (max_touches_per_cycle BETWEEN 1 AND 20),
  -- 周期长度（天）。
  cycle_days integer NOT NULL DEFAULT 30
    CHECK (cycle_days BETWEEN 1 AND 365),
  -- 弃单超时（小时）：加购 / 结账未完成超过这个时长才算「弃单」，也才允许触达。
  abandon_timeout_hours integer NOT NULL DEFAULT 24
    CHECK (abandon_timeout_hours BETWEEN 1 AND 720),

  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id)
);

-- ============================================================================
-- 2. crm_cart_shop_profile · 店铺平台类型声明
-- ============================================================================
-- 为什么要有这张表：平台目录（channel 的 platform 表）**没有**「独立站 / 平台店铺」这一列
-- ——operating_mode 是经营模式（自发货 / 全托管 …），不是店铺形态。
-- 而蓝图加粗句要求「平台订单不适用此项」必须**由系统保证**，不能靠运营自觉。
--
-- 于是判定分两段：
--   * **已知平台码**由服务层的判定表直接给出结论（AMAZON ⇒ MARKETPLACE、SHOPIFY ⇒ DTC），
--     租户**不能**改 —— 这就是那条硬保证；
--   * **未知平台码**（自建站、私有部署的建站系统）由租户在本表**显式声明**，
--     没有声明就登记弃单 ⇒ 409。
-- 平台码**不**由客户端提交：服务层从上游真实店铺记录取，客户端只能声明「DTC / MARKETPLACE」。
CREATE TABLE crm_cart_shop_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  -- 平台码快照（来自上游真实店铺记录，不是客户端填的）。
  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),
  -- 平台类型：独立站 / 平台店铺。
  platform_type varchar(16) NOT NULL CHECK (platform_type IN ('DTC','MARKETPLACE')),

  declared_by uuid,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, shop_id)
);

-- ============================================================================
-- 3. crm_cart_abandonment · 弃单（购物车 / 结账未完成）
-- ============================================================================
-- 弃单号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条弃单就报权限不足」（10.3.2.2 踩过这个坑）。
CREATE SEQUENCE crm_cart_abandonment_no_seq START 1;

CREATE TABLE crm_cart_abandonment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- CR-yyyyMMdd-000001
  cart_no varchar(32) NOT NULL DEFAULT '',

  -- ===== 店铺（来自 channel 的真实店铺清单，只存逻辑 ID + 名称快照）=====
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),
  -- 🔴 **CHECK 直接把平台店铺挡在门外**（加粗句「平台订单不适用此项」的物理落点）：
  --   这张表里只允许独立站的弃单。服务层已经会拒绝，这里再钉一道 ——
  --   万一有人绕过服务层直接写库，CHECK 也会当场报错。
  platform_type varchar(16) NOT NULL DEFAULT 'DTC' CHECK (platform_type = 'DTC'),

  -- ===== 购物车 / 结账 =====
  -- 平台侧的购物车或结账 id（幂等键：同一购物车只登记一次）。
  cart_ref varchar(200) NOT NULL DEFAULT '',
  external_event_id varchar(200) NOT NULL DEFAULT '',
  -- CART 只加购未下单 · CHECKOUT 进了结账流程未完成。
  stage varchar(16) NOT NULL CHECK (stage IN ('CART','CHECKOUT')),

  -- ===== 买家 =====
  buyer_ref varchar(128) NOT NULL,
  buyer_email varchar(320) NOT NULL DEFAULT '',
  buyer_phone varchar(64) NOT NULL DEFAULT '',

  -- ===== 金额与数量（禁浮点）=====
  currency varchar(8) NOT NULL DEFAULT '',
  amount numeric(19,6) NOT NULL DEFAULT 0 CHECK (amount >= 0),
  item_count numeric(19,6) NOT NULL DEFAULT 0 CHECK (item_count >= 0),
  item_summary varchar(500) NOT NULL DEFAULT '',

  -- ===== 时间 =====
  -- 购物车被放弃的时刻（事件时刻，可以是过去）。
  abandoned_at timestamptz NOT NULL,
  -- 挽回时点 = abandoned_at + 租户策略的 abandon_timeout_hours。
  --   **未到这一刻不允许触达** —— 「按超时策略触发挽回」的落点。
  recover_deadline_at timestamptz NOT NULL,

  -- ===== 状态 =====
  -- ABANDONED 待挽回 · RECOVERED 已挽回成交 · EXPIRED 超过挽回窗口未挽回。
  status varchar(16) NOT NULL DEFAULT 'ABANDONED'
    CHECK (status IN ('ABANDONED','RECOVERED','EXPIRED')),
  recovered_at timestamptz,
  recovered_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK (recovered_amount >= 0),
  -- 成交订单号：**人工登记**（9 订单未交付，本模块不编造下游 id）。
  recovered_order_no varchar(64) NOT NULL DEFAULT '',

  -- MANUAL_ENTRY 人工登记 · PLATFORM_EVENT 平台事件同步（适配器未交付，当前恒为前者）。
  source varchar(16) NOT NULL DEFAULT 'MANUAL_ENTRY'
    CHECK (source IN ('MANUAL_ENTRY','PLATFORM_EVENT')),

  created_by uuid,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  CHECK (status <> 'RECOVERED' OR recovered_at IS NOT NULL),
  CHECK (recover_deadline_at >= abandoned_at)
);

-- 同一购物车只登记一次（幂等）。空 cart_ref 不参与去重 ——
-- 人工登记时允许不填购物车号，填了才按它去重。
CREATE UNIQUE INDEX crm_cart_abandonment_by_cart_ref
  ON crm_cart_abandonment(tenant_id, cart_ref)
  WHERE cart_ref <> '';
-- 弃单清单：按店铺 + 状态 + 时间倒序读。
CREATE INDEX crm_cart_abandonment_by_shop
  ON crm_cart_abandonment(tenant_id, shop_id, abandoned_at DESC);
-- 触达频次判定：按买家 + 登记时刻读。
CREATE INDEX crm_cart_abandonment_by_buyer
  ON crm_cart_abandonment(tenant_id, buyer_ref, abandoned_at DESC);

-- ============================================================================
-- 4. crm_cart_recovery_message · 挽回话术（状态机）
-- ============================================================================
-- 话术编号是业务键（建后不可改）：改它会让「这次挽回用的是哪套话术」在历史里出现两个答案。
CREATE TABLE crm_cart_recovery_message (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  message_code varchar(64) NOT NULL,
  name varchar(200) NOT NULL,
  -- 触达通道：邮件 / 短信 / 站内信。
  channel varchar(16) NOT NULL CHECK (channel IN ('EMAIL','SMS','IN_APP')),
  locale varchar(16) NOT NULL DEFAULT 'zh-CN',
  subject varchar(500) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  -- 🔴 状态机（见文件头第三节）。
  status varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT','APPROVED','ENABLED','DISABLED')),
  -- 内容版本：内容每改一次自动 +1（由触发器负责）。
  version integer NOT NULL DEFAULT 1 CHECK (version >= 1),

  -- 审核留痕（谁、何时、哪个版本）。
  approved_by uuid,
  approved_at timestamptz,
  approved_version integer,
  -- 启用留痕。
  enabled_by uuid,
  enabled_at timestamptz,
  enabled_version integer,
  -- 停用留痕。
  disabled_by uuid,
  disabled_at timestamptz,

  created_by uuid,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, message_code)
);

-- 话术清单：按状态 + 通道读。
CREATE INDEX crm_cart_recovery_message_by_status
  ON crm_cart_recovery_message(tenant_id, status, channel, message_code);

-- ============================================================================
-- 5. crm_cart_recovery_touch · 挽回触达记录
-- ============================================================================
-- 触达记录 = 「我们确实登记了一次触达」。
-- 🔴 状态只有三档：REGISTERED 已登记待发 · SENT 已发出（人工交接后回填）· FAILED 失败。
--   **没有「已发出」以外的自动成功路径** —— 15.5.2.1 触达未交付，
--   本服务**不假装**已经发出邮件 / 短信。
CREATE TABLE crm_cart_recovery_touch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  cart_id uuid NOT NULL,
  -- 弃单号快照（弃单删不掉，但快照让历史记录独立可读）。
  cart_no varchar(32) NOT NULL DEFAULT '',

  message_id uuid,
  -- 话术编号快照（话术行被改掉后，历史里「用的哪套话术」仍可读）。
  message_code varchar(64) NOT NULL DEFAULT '',
  channel varchar(16) NOT NULL CHECK (channel IN ('EMAIL','SMS','IN_APP')),
  -- 🔴 只有人工交接：没有真实通道时**绝不**出现「已发到平台」的记录。
  transport varchar(24) NOT NULL DEFAULT 'MANUAL_HANDOFF'
    CHECK (transport IN ('MANUAL_HANDOFF')),

  status varchar(16) NOT NULL DEFAULT 'REGISTERED'
    CHECK (status IN ('REGISTERED','SENT','FAILED')),
  -- 收件人（由弃单上的联系方式派生，不是客户端另填的 ——
  --   另填会让「退订硬拦」查错对象）。
  recipient varchar(320) NOT NULL DEFAULT '',
  subject varchar(500) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  failure_reason varchar(600) NOT NULL DEFAULT '',
  sent_at timestamptz,

  operator_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  CHECK (status <> 'SENT' OR sent_at IS NOT NULL),
  CHECK (status <> 'FAILED' OR btrim(failure_reason) <> '')
);

-- 触达时间线：按弃单读。
CREATE INDEX crm_cart_recovery_touch_by_cart
  ON crm_cart_recovery_touch(tenant_id, cart_id, created_at DESC);
-- 频次判定：按创建时刻读。
CREATE INDEX crm_cart_recovery_touch_by_created
  ON crm_cart_recovery_touch(tenant_id, created_at DESC);
-- 话术删不掉的判定要用。
CREATE INDEX crm_cart_recovery_touch_by_message
  ON crm_cart_recovery_touch(tenant_id, message_id)
  WHERE message_id IS NOT NULL;

-- ============================================================================
-- 6. crm_cart_unsubscribe · 退订名单（硬拦）
-- ============================================================================
-- contact_type 与触达通道同一套词表：按通道分别退订。
-- shop_id 为空 = 该租户全店铺生效；不为空 = 只对该店铺生效。
CREATE TABLE crm_cart_unsubscribe (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  contact_type varchar(16) NOT NULL CHECK (contact_type IN ('EMAIL','SMS','IN_APP')),
  contact_value varchar(320) NOT NULL,
  shop_id uuid,

  reason varchar(500) NOT NULL DEFAULT '',
  unsubscribed_at timestamptz NOT NULL,
  created_by uuid,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  CHECK (btrim(contact_value) <> '')
);

-- 同一个联系人同一通道同一店铺范围只登记一次（邮箱比对忽略大小写）。
CREATE UNIQUE INDEX crm_cart_unsubscribe_by_contact
  ON crm_cart_unsubscribe(tenant_id, contact_type, lower(contact_value),
                          coalesce(shop_id,'00000000-0000-0000-0000-000000000000'::uuid));
-- 退订硬拦的判定：按租户 + 通道 + 联系人查。
CREATE INDEX crm_cart_unsubscribe_by_lookup
  ON crm_cart_unsubscribe(tenant_id, contact_type, lower(contact_value));

-- ============================================================================
-- 7. 触发器（**一律不用外键** —— 理由见文件头第六节末）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 7.1 guard_crm_cart_abandonment_identity · 弃单身份列建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：这些列是「这是哪一张弃单、发生在哪家独立站、哪个购物车」的凭据。
-- 改了之后，「这个买家弃过几次」「这条挽回率分子分母各是什么」在历史上会出现两个答案。
-- status（是否已挽回）/ recovered_* / note / updated_at 仍然可改（它们是流程状态）。
CREATE FUNCTION guard_crm_cart_abandonment_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.cart_no IS DISTINCT FROM OLD.cart_no THEN
    RAISE EXCEPTION '弃单号建后不可改：改它会让「这条弃单是哪一个」在历史里出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION '弃单的店铺建后不可改：换店就等于换了一条弃单' USING ERRCODE='23514';
  END IF;
  IF NEW.platform_code IS DISTINCT FROM OLD.platform_code THEN
    RAISE EXCEPTION '弃单的平台码建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.platform_type IS DISTINCT FROM OLD.platform_type THEN
    RAISE EXCEPTION '弃单的平台类型建后不可改：平台订单无购物车概念，这一列是「独立站弃单」这条红线的凭据' USING ERRCODE='23514';
  END IF;
  IF NEW.cart_ref IS DISTINCT FROM OLD.cart_ref THEN
    RAISE EXCEPTION '弃单的购物车号建后不可改：它既是幂等键，也是「同一个购物车」的唯一凭据' USING ERRCODE='23514';
  END IF;
  IF NEW.buyer_ref IS DISTINCT FROM OLD.buyer_ref THEN
    RAISE EXCEPTION '弃单的买家标识建后不可改：改它会让「这个买家弃过几次」这个频次计数在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.abandoned_at IS DISTINCT FROM OLD.abandoned_at THEN
    RAISE EXCEPTION '弃单的事件时刻建后不可改：改它会让「什么时候该挽回」无从查证' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_cart_abandonment_identity_trg
  BEFORE UPDATE ON crm_cart_abandonment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_abandonment_identity();

-- ---------------------------------------------------------------------------
-- 7.2 guard_crm_cart_recovery_message_state · 话术状态机 + 留痕 + 改内容回草稿
-- ---------------------------------------------------------------------------
-- 三件事都在这一个触发器里：
--   ① 状态迁移只允许白名单里的那几条；
--   ② 内容一变，version 自动 +1、status 回 DRAFT、审核 / 启用痕迹清空；
--      （否则会出现「审核的是 A 版、发出去的是 B 版」）
--   ③ APPROVED / ENABLED 状态必须带齐留痕（谁、何时、哪个版本）。
CREATE FUNCTION guard_crm_cart_recovery_message_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_content_changed boolean;
BEGIN
  v_content_changed :=
       NEW.name IS DISTINCT FROM OLD.name
    OR NEW.channel IS DISTINCT FROM OLD.channel
    OR NEW.locale IS DISTINCT FROM OLD.locale
    OR NEW.subject IS DISTINCT FROM OLD.subject
    OR NEW.body IS DISTINCT FROM OLD.body;

  -- ① 改内容 ⇒ 回到草稿并清空痕迹（由触发器统一负责，服务层不必重复这套规则）。
  IF v_content_changed THEN
    NEW.version := OLD.version + 1;
    NEW.status := 'DRAFT';
    NEW.approved_by := NULL;
    NEW.approved_at := NULL;
    NEW.approved_version := NULL;
    NEW.enabled_by := NULL;
    NEW.enabled_at := NULL;
    NEW.enabled_version := NULL;
    NEW.disabled_by := NULL;
    NEW.disabled_at := NULL;
    RETURN NEW;
  END IF;

  -- ② 状态迁移白名单。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
         (OLD.status = 'DRAFT'     AND NEW.status IN ('APPROVED','DISABLED'))
      OR (OLD.status = 'APPROVED'  AND NEW.status IN ('ENABLED','DISABLED'))
      OR (OLD.status = 'ENABLED'   AND NEW.status = 'DISABLED')
      OR (OLD.status = 'DISABLED'  AND NEW.status IN ('APPROVED','DRAFT'))
    ) THEN
      RAISE EXCEPTION '话术状态不能从 % 变成 %：话术的生命周期是 草稿 → 已审核 → 已启用 → 已停用，每一步都要人工确认',
        OLD.status, NEW.status USING ERRCODE='23514';
    END IF;
  END IF;

  -- ③ 留痕必须齐全：谁、何时、哪个版本。
  IF NEW.status IN ('APPROVED','ENABLED') THEN
    IF NEW.approved_by IS NULL OR NEW.approved_at IS NULL THEN
      RAISE EXCEPTION '话术进入「%」必须留下审核痕迹（审核人 + 审核时刻）：挽回话术需人工确认后启用', NEW.status USING ERRCODE='23514';
    END IF;
    IF NEW.approved_version IS DISTINCT FROM NEW.version THEN
      RAISE EXCEPTION '话术进入「%」时记录的审核版本（%）必须等于当前版本（%）：审核的是哪一版必须查得到',
        NEW.status, NEW.approved_version, NEW.version USING ERRCODE='23514';
    END IF;
  END IF;
  IF NEW.status = 'ENABLED' THEN
    IF NEW.enabled_by IS NULL OR NEW.enabled_at IS NULL OR NEW.enabled_version IS DISTINCT FROM NEW.version THEN
      RAISE EXCEPTION '话术进入「已启用」必须留下启用痕迹（启用人 + 启用时刻 + 启用版本）' USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_cart_recovery_message_state_trg
  BEFORE UPDATE ON crm_cart_recovery_message
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_recovery_message_state();

-- ---------------------------------------------------------------------------
-- 7.3 guard_crm_cart_recovery_touch_target · 触达必须挂在存在的弃单上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_cart_recovery_touch_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  SELECT cart_no INTO v_no FROM crm_cart_abandonment
   WHERE tenant_id = NEW.tenant_id AND id = NEW.cart_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '弃单 % 不存在（tenant=%），不能给它登记触达',
      NEW.cart_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_cart_recovery_touch_target_trg
  BEFORE INSERT ON crm_cart_recovery_touch
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_recovery_touch_target();

-- ---------------------------------------------------------------------------
-- 7.4 guard_crm_cart_recovery_touch_message · 触达只能引用**已启用**的话术
-- ---------------------------------------------------------------------------
-- 🔴 加粗句「挽回话术需人工确认后启用」的物理落点：
--   未审核 / 未启用的话术在**数据库层**就选不出来 ——
--   前端能选也会被挡下，服务层绕过也挡得住。
CREATE FUNCTION guard_crm_cart_recovery_touch_message() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status varchar(16); v_code varchar(64);
BEGIN
  IF NEW.message_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT status, message_code INTO v_status, v_code FROM crm_cart_recovery_message
   WHERE tenant_id = NEW.tenant_id AND id = NEW.message_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '挽回话术 % 不存在（tenant=%），不能引用它',
      NEW.message_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  IF v_status <> 'ENABLED' THEN
    RAISE EXCEPTION '挽回话术 % 当前是「%」，不能被触达流程选用：挽回话术需人工确认后启用（草稿 → 已审核 → 已启用）',
      v_code, v_status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_cart_recovery_touch_message_trg
  BEFORE INSERT ON crm_cart_recovery_touch
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_recovery_touch_message();

-- ---------------------------------------------------------------------------
-- 7.5 guard_crm_cart_recovery_touch_unsubscribe · 🔴 退订硬拦
-- ---------------------------------------------------------------------------
-- 为什么必须在**落库之前**查：已经发出去的邮件收不回来。
-- 「先发出去、报表里再过滤」是不成立的 —— 报表过滤只影响数字，不影响买家。
CREATE FUNCTION guard_crm_cart_recovery_touch_unsubscribe() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_hit integer; v_reason text;
BEGIN
  SELECT count(*), min(coalesce(reason,'')) INTO v_hit, v_reason
    FROM crm_cart_unsubscribe u
   WHERE u.tenant_id = NEW.tenant_id
     AND u.contact_type = NEW.channel
     AND lower(u.contact_value) = lower(NEW.recipient)
     AND (u.shop_id IS NULL OR u.shop_id = (SELECT a.shop_id FROM crm_cart_abandonment a
                                               WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.cart_id));
  IF v_hit > 0 THEN
    RAISE EXCEPTION '收件人 % 已退订（%s），一律不触达：退订是硬拦，发出去之前就要查',
      NEW.recipient, v_reason USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_cart_recovery_touch_unsubscribe_trg
  BEFORE INSERT ON crm_cart_recovery_touch
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_recovery_touch_unsubscribe();

-- ---------------------------------------------------------------------------
-- 7.6 guard_crm_cart_recovery_touch_frequency · 🔴 频次上限
-- ---------------------------------------------------------------------------
-- 与 15.2.2.1 索评频次同一手法：上限在 DB 层判定，服务层用同一判据预检并给原因码。
-- 口径与 CartRecoveryService.touchFrequency() **逐字一致**（同一张表、同一时间窗、同一计数）。
CREATE FUNCTION guard_crm_cart_recovery_touch_frequency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_buyer varchar(128);
  v_per_day integer;
  v_per_cycle integer;
  v_cycle_days integer;
  v_day_count integer;
  v_cycle_count integer;
BEGIN
  SELECT buyer_ref INTO v_buyer FROM crm_cart_abandonment
   WHERE tenant_id = NEW.tenant_id AND id = NEW.cart_id;
  IF v_buyer IS NULL THEN
    RETURN NEW; -- 挂不上的弃单由 7.3 拦，这里不重复报错。
  END IF;

  SELECT max_touches_per_day, max_touches_per_cycle, cycle_days
    INTO v_per_day, v_per_cycle, v_cycle_days
    FROM crm_cart_recovery_policy WHERE tenant_id = NEW.tenant_id;
  IF NOT FOUND THEN
    v_per_day := 1; v_per_cycle := 3; v_cycle_days := 30;
  END IF;

  SELECT count(*) INTO v_day_count
    FROM crm_cart_recovery_touch t
    JOIN crm_cart_abandonment a ON a.tenant_id = t.tenant_id AND a.id = t.cart_id
   WHERE t.tenant_id = NEW.tenant_id AND a.buyer_ref = v_buyer
     AND t.created_at >= NEW.created_at - interval '1 day';
  IF v_day_count >= v_per_day THEN
    RAISE EXCEPTION '买家 % 在 24 小时内已被触达 % 次，达到每日上限 % 次：触达须遵守频次上限',
      v_buyer, v_day_count, v_per_day USING ERRCODE='23514';
  END IF;

  SELECT count(*) INTO v_cycle_count
    FROM crm_cart_recovery_touch t
    JOIN crm_cart_abandonment a ON a.tenant_id = t.tenant_id AND a.id = t.cart_id
   WHERE t.tenant_id = NEW.tenant_id AND a.buyer_ref = v_buyer
     AND t.created_at >= NEW.created_at - make_interval(days => v_cycle_days);
  IF v_cycle_count >= v_per_cycle THEN
    RAISE EXCEPTION '买家 % 在 % 天周期内已被触达 % 次，达到周期上限 % 次：触达须遵守频次上限',
      v_buyer, v_cycle_days, v_cycle_count, v_per_cycle USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER crm_cart_recovery_touch_frequency_trg
  BEFORE INSERT ON crm_cart_recovery_touch
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_recovery_touch_frequency();

-- ---------------------------------------------------------------------------
-- 7.7 guard_crm_cart_recovery_message_delete · 已被触达引用过的话术删不掉
-- ---------------------------------------------------------------------------
-- 为什么：话术是**配置**，但**已经被触达引用过的**话术一旦删掉，
-- 「这次挽回用的是哪套话术」在历史里就只剩一个编号快照、没有正文可查 ——
-- 结论被改写，而不是少了几条。
CREATE FUNCTION guard_crm_cart_recovery_message_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_count integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_cart_recovery_touch
   WHERE tenant_id = OLD.tenant_id AND message_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '挽回话术 % 已被 % 条触达记录引用，删不掉：删掉它会让「这次挽回用的哪套话术」在历史里无从查证。可以改成 DISABLED 停用',
      OLD.message_code, v_count USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_cart_recovery_message_delete_trg
  BEFORE DELETE ON crm_cart_recovery_message
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_recovery_message_delete();

-- ---------------------------------------------------------------------------
-- 7.8 guard_crm_cart_abandonment_delete · 已有触达 / 恢复记录的弃单删不掉
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_cart_abandonment_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_count integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_cart_recovery_touch
   WHERE tenant_id = OLD.tenant_id AND cart_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '弃单 % 已有 % 条触达记录，删不掉：删掉它会让「挽回率的分母」在历史里少算一条',
      OLD.cart_no, v_count USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_cart_abandonment_delete_trg
  BEFORE DELETE ON crm_cart_abandonment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_abandonment_delete();

-- ---------------------------------------------------------------------------
-- 7.9 guard_crm_cart_shop_profile_identity · 店铺档案的平台码建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：平台码是**平台类型判定的输入**。改了它就等于把「这是平台店铺」偷偷改成
-- 「这是独立站」—— 而这条改动的后果是平台订单混进弃单清单。
CREATE FUNCTION guard_crm_cart_shop_profile_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION '店铺档案的店铺建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.platform_code IS DISTINCT FROM OLD.platform_code THEN
    RAISE EXCEPTION '店铺档案的平台码建后不可改：平台码是平台类型判定的输入，改它等于把平台店铺偷偷改成独立站' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_cart_shop_profile_identity_trg
  BEFORE UPDATE ON crm_cart_shop_profile
  FOR EACH ROW EXECUTE FUNCTION guard_crm_cart_shop_profile_identity();

-- ============================================================================
-- 8. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V292 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_cart_recovery_policy','crm_cart_shop_profile','crm_cart_abandonment','crm_cart_recovery_message','crm_cart_recovery_touch','crm_cart_unsubscribe'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_cart_recovery_policy, crm_cart_shop_profile, crm_cart_abandonment, crm_cart_recovery_message, crm_cart_recovery_touch, crm_cart_unsubscribe TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_cart_recovery_policy, crm_cart_shop_profile, crm_cart_abandonment, crm_cart_recovery_message, crm_cart_recovery_touch, crm_cart_unsubscribe TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条弃单就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_cart_abandonment_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_cart_abandonment_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 9. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **2 渠道购物车事件**（**未接入**）：本模块新增端口 CartEventPort，
--     当前实现 UnavailableCartEventPort 对所有平台答「未接入」。
--     同步端点据此明确 **409** 且**不写任何数据** —— 一个「返回空列表」的假同步
--     会让运营以为这个店铺没有购物车，挽回率报表的分母就错了。
--     适配器接上后本端点会自动放行，无需改前端。
--   * **15.5.2.1 关怀与催办（触达）**（**未交付**）：本模块**只登记触达记录**，
--     状态恒为「已登记待发」；**不假装**已经发出邮件 / 短信。
--   * **9 订单（挽回成交）**（**未交付**）：成交订单号由运营**人工登记**，
--     本模块**不编造**下游订单 id。
--   * **13 分析**（**未交付**）：本模块只出**挽回率报表**。
--   * **15.3.1.1 客户档案**（**未交付**）：买家标识与联系方式由运营按店铺提供，
--     本模块**不假装**拥有买家主数据。
--   * **15.4.2.1 知识库 / 15.2.2.2 索评规则**：本模块的挽回话术是**本模块自己的**
--     （带审核状态机），与知识库、索评规则**不共用任何一张表**。
