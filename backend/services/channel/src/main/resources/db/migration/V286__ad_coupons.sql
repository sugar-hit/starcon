-- ===========================================================================
-- 14.4.1.2 优惠券与秒杀（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.4.1.2 优惠券与秒杀
--   - 职责：管理优惠券（折扣码、满减、买赠）与秒杀活动：生成码、设置库存与限领、
--           投放渠道（站内/站外/社媒），跟踪领取与核销。
--   - 输入：券规则；投放计划；核销数据同步。
--   - 输出：券记录 + 核销统计。
--   - 关系：上游 = 5 定价；下游 = 平台营销接口、15 客户（定向发放）。
--           **券的核销要与订单绑定**，否则无法评估活动真实效果与补贴成本。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 策略**，零 ALTER 任何既有表：
--   * V1 `platform`（全局平台码表）+ V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能（照抄 V284 同款写法）
--   * V171 `listing`（**同库同服务**，4.1.1.1 已交付）—— 秒杀针对单个商品
--   * V284 `ad_promotion`（**同库同服务**，14.4.1.1 已交付）—— 券可以挂在促销下
--
-- ---------------------------------------------------------------------------
-- 一、🔴 五条事实边界（写进库，不是写进注释而已）
-- ---------------------------------------------------------------------------
-- 1. **5 定价在 catalog 服务 ⇒ 禁止跨服务 SQL。**
--    定价模板 / 定价规则（V231 / V236）在 **catalog**，channel 不得 join catalog 的表，
--    不建跨库 FK，不建共享视图。
--    ⇒ 券的**门槛与面额以快照形式存在本库**：`ad_coupon.threshold_amount` /
--      `ad_coupon.discount_value` / `ad_coupon.discount_rate` /
--      `ad_coupon.max_discount_amount` 都是**建券时登记的输入**（创建当时的定价口径），
--      不是从 catalog 拉来的实时值。核销时用**这份快照**算让利额。
--
-- 2. **平台营销接口未交付 ⇒ 券与秒杀只落本地。**
--    照抄 V273 `ad_campaign.pending_platform_writeback` + V284
--    `ad_promotion.platform_sync_status` 的口径：`platform_sync_status` 默认
--    `LOCAL_ONLY` + `pending_platform_writeback=true`，界面必须写明「平台侧还没有这张券」。
--    `SYNCED` 只能由平台回写适配器产生，该适配器尚未交付 ⇒ 本批代码里**没有任何路径**
--    写入它。**不 mock 成已回写**。
--
-- 3. **15 客户（定向发放）未交付 ⇒ 定向发放自包含地做。**
--    `ad_coupon_claim.claim_kind='PUSH'` + `recipient_key` 就是定向发放的落点：
--    收件人先以**收件人标识（字符串）**记录，15 客户交付后由它解析成真实客户 id。
--    发放记录、渠道投放计划**都落在本模块自己的表里**，不跨服务写。
--
-- 4. **券的核销必须与订单绑定**（蓝图加粗句）。
--    `ad_coupon_redemption.order_ref` **NOT NULL** —— 没有订单标识的核销**写不进来**。
--    DB 层用 UNIQUE(tenant_id, coupon_id, order_ref) 钉死「同一张券在同一订单上只能核销一次」，
--    用 UNIQUE(tenant_id, code_id) 钉死「一个券码只能核销一次」。
--
-- 5. **买赠（BOGO）的补贴成本本地测不出来 ⇒ 写 NULL，不写 0。**
--    买赠的成本 = 赠品成本价，成本价在 05 定价（catalog，跨服务不可读）
--    ⇒ `ad_coupon_redemption.subsidy_cost` 对买赠券**为 NULL（不可测算）**，
--    统计里单列「N 笔不可测算」。写一个 0 会被界面渲染成「补贴成本是 0」——
--    那是假事实（真相是「拿不到成本」），与 V284 的毛利纪律同一条。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 库存 / 限领的扣减必须走显式 SQL
-- ---------------------------------------------------------------------------
-- 领取与核销都在**显式行锁**（`select ... for update`）+ **计数列**上做，
-- 计数列由 DB CHECK 钉死自洽：
--     generated_count <= total_stock        （已生成 ≤ 库存）
--     claimed_count  <= generated_count     （已领取 ≤ 已生成）
--     redeemed_count <= claimed_count       （已核销 ≤ 已领取）
-- 并发的两次领取不会把库存扣成负数（第二次拿不到行锁 / CHECK 失败 ⇒ 回滚）。
-- **不用懒加载掩盖竞态**（AGENTS.md）。
--
-- ---------------------------------------------------------------------------
-- 三、券类型的三选一由 DB CHECK 钉死（照抄 V284 折扣口径三选一）
-- ---------------------------------------------------------------------------
--   DISCOUNT_CODE 折扣码：给折扣率**或**固定折扣额（**互斥** —— 两个都填会让
--                       「让利多少」有两个答案，那是不可审阅的账）
--   FULL_REDUCTION 满减  ：给门槛 + 固定减免额
--   BOGO          买赠  ：给买 N / 送 M，不给任何金额（没有面值口径 ⇒ 补贴成本不可测算）
--
-- ===========================================================================
-- 1. ad_coupon · 优惠券定义
-- ===========================================================================
CREATE TABLE ad_coupon (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 2.1.1.1 平台目录（全局码表）===
  platform_id uuid NOT NULL REFERENCES platform(id),

  -- === 上游 2.3.1.1 店铺（UNIQUE(tenant_id,code)，复合 FK ⇒ 跨租户挂载不可能）===
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- === 14.4.1.1 促销（同库同服务，14.4.1.1 已交付）—— 券可以挂在促销下 ===
  --   NULL = 这张券不挂促销（券可以独立存在）。
  --   ON DELETE NO ACTION：促销是事实，挂着券的促销不许被物理删掉。
  promotion_id uuid,
  FOREIGN KEY (tenant_id, promotion_id) REFERENCES ad_promotion(tenant_id, id) ON DELETE NO ACTION,

  -- 券编码（租户内唯一）：运营与运营之间对券的唯一指称
  coupon_code varchar(64) NOT NULL,
  coupon_name varchar(200) NOT NULL,

  -- 🔴 券类型：折扣码 / 满减 / 买赠
  coupon_type text NOT NULL CHECK (coupon_type IN (
    'DISCOUNT_CODE','FULL_REDUCTION','BOGO')),

  -- 🔴 折扣口径（**建券时登记的快照**，不是从 05 定价拉的 —— 见第一节第 1 条）
  --   折扣率（0 < r <= 1；0.2 = 打八折让利 20%）；固定折扣额（钱）
  discount_rate NUMERIC(19,6)
    CHECK (discount_rate IS NULL OR (discount_rate > 0 AND discount_rate <= 1)),
  discount_value NUMERIC(19,4)
    CHECK (discount_value IS NULL OR discount_value >= 0),
  -- 满减门槛（适用订单的最低金额）。FULL_REDUCTION 必填。
  threshold_amount NUMERIC(19,4)
    CHECK (threshold_amount IS NULL OR threshold_amount >= 0),
  -- 单笔让利封顶（NULL = 不封顶，**不是 0**）
  max_discount_amount NUMERIC(19,4)
    CHECK (max_discount_amount IS NULL OR max_discount_amount >= 0),
  -- 买赠：买 N 送 M（数量用 NUMERIC(19,6)）
  buy_quantity NUMERIC(19,6)
    CHECK (buy_quantity IS NULL OR buy_quantity > 0),
  gift_quantity NUMERIC(19,6)
    CHECK (gift_quantity IS NULL OR gift_quantity > 0),

  currency char(3) NOT NULL DEFAULT '',

  -- 🔴 生效与失效时间。timestamptz + 服务层 OffsetDateTime 绑定 + values 位置写
  --   `?::timestamptz`（jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒）。
  start_at timestamptz,
  end_at timestamptz,
  CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at),

  -- 🔴 库存（蓝图的「设置库存」）：可生成的券码总数。0 = 不可领。
  total_stock NUMERIC(19,6) NOT NULL CHECK (total_stock >= 0),
  -- 🔴 三个计数列由服务层在**显式行锁**内维护，DB CHECK 钉死自洽（见第二节）
  generated_count NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (generated_count >= 0),
  claimed_count NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (claimed_count >= 0),
  redeemed_count NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (redeemed_count >= 0),

  -- 🔴 限领（蓝图的「限领」）：**按人**限领张数。NULL = 不限领（**不是 0**）。
  per_user_limit NUMERIC(19,6)
    CHECK (per_user_limit IS NULL OR per_user_limit > 0),

  -- 🔴 券码生成规则（蓝图的「生成码」）
  --   前缀 + 随机段（字符集去掉了易混的 0/O/1/I）
  code_prefix varchar(16) NOT NULL DEFAULT '',
  code_length int NOT NULL DEFAULT 8 CHECK (code_length BETWEEN 4 AND 32),
  code_charset varchar(64) NOT NULL DEFAULT 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789',

  -- 🔴 本地生命周期状态（**本地**状态，不是平台侧状态）
  --   DRAFT 草稿 / ACTIVE 生效中 / PAUSED 已暂停 / ENDED 已结束 / CANCELLED 已停用
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
    'DRAFT','ACTIVE','PAUSED','ENDED','CANCELLED')),

  -- 🔴 平台营销接口未交付 ⇒ 同步状态只有「本地已建、平台侧还没有」这一种真实状态
  --   SYNCED 只能由平台回写适配器产生（该适配器未交付）⇒ 本批代码里没有路径写入它。
  platform_sync_status text NOT NULL DEFAULT 'LOCAL_ONLY' CHECK (platform_sync_status IN (
    'LOCAL_ONLY','PENDING_WRITEBACK','SYNCED','FAILED')),
  -- 🔴「平台侧与本地不一致」= 待回写。DB CHECK 保证两者不能同时成立。
  pending_platform_writeback boolean NOT NULL DEFAULT true,
  local_changed_at timestamptz,

  -- 最近一次状态变更的理由（状态机每次改动的理由要能查）
  last_status_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 券编码租户内唯一
  UNIQUE(tenant_id, coupon_code),
  -- 券名与编码不许是空串（btrim）
  CHECK (btrim(coupon_code) <> '' AND btrim(coupon_name) <> ''),

  -- 🔴 折扣口径三选一（见第三节）
  CHECK (
    (coupon_type = 'DISCOUNT_CODE'
      AND ((discount_rate IS NOT NULL AND discount_value IS NULL)
        OR (discount_rate IS NULL AND discount_value IS NOT NULL))
      AND threshold_amount IS NULL
      AND buy_quantity IS NULL AND gift_quantity IS NULL)
    OR (coupon_type = 'FULL_REDUCTION'
      AND discount_rate IS NULL
      AND discount_value IS NOT NULL
      AND threshold_amount IS NOT NULL
      AND buy_quantity IS NULL AND gift_quantity IS NULL)
    OR (coupon_type = 'BOGO'
      AND discount_rate IS NULL AND discount_value IS NULL
      AND threshold_amount IS NULL AND max_discount_amount IS NULL
      AND buy_quantity IS NOT NULL AND gift_quantity IS NOT NULL)
  ),

  -- 🔴 计数列自洽（DB 层兜底，不靠服务层自觉）
  CHECK (generated_count <= total_stock),
  CHECK (claimed_count <= generated_count),
  CHECK (redeemed_count <= claimed_count),

  -- 🔴「平台侧与本地不一致」= 待回写
  CHECK (pending_platform_writeback = (platform_sync_status <> 'SYNCED'))
);

CREATE INDEX ad_coupon_list ON ad_coupon(tenant_id, status, id);
CREATE INDEX ad_coupon_shop ON ad_coupon(tenant_id, shop_code, status);
CREATE INDEX ad_coupon_type ON ad_coupon(tenant_id, coupon_type);
CREATE INDEX ad_coupon_sync ON ad_coupon(tenant_id, platform_sync_status);
CREATE INDEX ad_coupon_window ON ad_coupon(tenant_id, start_at DESC, end_at DESC);
CREATE INDEX ad_coupon_promotion ON ad_coupon(tenant_id, promotion_id);

-- ===========================================================================
-- 2. ad_coupon_code · 券码表（生成码）
-- ===========================================================================
-- 「生成码」的产物落在这里：一张券一次生成若干码，码本身是**事实**（领了、用了都要留痕）。
CREATE TABLE ad_coupon_code (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  coupon_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, coupon_id)
    REFERENCES ad_coupon(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 券码本体。UNIQUE(tenant_id, code)：**唯一性约束带租户维度** ——
  --   两个租户可以各自有一个叫 "SUMMER10" 的码（互不可见，没有歧义），
  --   但同一个租户内绝不能有两个同码（核销时按码找券会找花）。
  code varchar(64) NOT NULL,

  -- 码的生命周期：GENERATED 已生成未领 / CLAIMED 已领取 / USED 已核销
  --              / EXPIRED 已过期 / VOID 已作废
  status text NOT NULL DEFAULT 'GENERATED' CHECK (status IN (
    'GENERATED','CLAIMED','USED','EXPIRED','VOID')),

  -- 领取人标识（领取后写入；定向发放时是收件人标识）
  claimant_key varchar(128) NOT NULL DEFAULT '',
  claimed_at timestamptz,
  used_at timestamptz,
  -- 核销绑定的订单标识（**冗余**便于按订单反查；权威值在 ad_coupon_redemption）
  order_ref varchar(128) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),
  UNIQUE(tenant_id, coupon_id, code),
  CHECK (btrim(code) <> '')
);

CREATE INDEX ad_coupon_code_coupon ON ad_coupon_code(tenant_id, coupon_id, status);
CREATE INDEX ad_coupon_code_code ON ad_coupon_code(tenant_id, code);

-- ===========================================================================
-- 3. ad_coupon_channel · 投放渠道与投放计划（站内 / 站外 / 社媒）
-- ===========================================================================
-- 一张券可以同时投在多个渠道上 ⇒ **独立表**（不是券上的一个字段）。
-- 「投放计划」= plan_start_at / plan_end_at / planned_reach / status。
-- 实际领取数由 ad_coupon_claim 聚合而来（**真聚合**，不靠人工填）。
CREATE TABLE ad_coupon_channel (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  coupon_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, coupon_id)
    REFERENCES ad_coupon(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 渠道类型：站内 ON_SITE / 站外 OFF_SITE / 社媒 SOCIAL
  channel_type text NOT NULL CHECK (channel_type IN ('ON_SITE','OFF_SITE','SOCIAL')),
  -- 投放位（首页 banner / EDM 列表页 / 社媒账号 …）
  placement varchar(200) NOT NULL,

  -- 投放计划的时间窗与计划触达量
  plan_start_at timestamptz,
  plan_end_at timestamptz,
  planned_reach NUMERIC(19,6) CHECK (planned_reach IS NULL OR planned_reach >= 0),

  -- 投放状态：PLANNED 已排期 / RUNNING 投放中 / PAUSED 已暂停
  --          / FINISHED 已结束 / CANCELLED 已取消
  status text NOT NULL DEFAULT 'PLANNED' CHECK (status IN (
    'PLANNED','RUNNING','PAUSED','FINISHED','CANCELLED')),

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一张券的同一个渠道上，同一个投放位只登记一次
  UNIQUE(tenant_id, coupon_id, channel_type, placement),
  CHECK (btrim(placement) <> ''),
  CHECK (plan_end_at IS NULL OR plan_start_at IS NULL OR plan_end_at > plan_start_at)
);

CREATE INDEX ad_coupon_channel_coupon ON ad_coupon_channel(tenant_id, coupon_id);
CREATE INDEX ad_coupon_channel_status ON ad_coupon_channel(tenant_id, status);
CREATE INDEX ad_coupon_channel_type ON ad_coupon_channel(tenant_id, channel_type);

-- ===========================================================================
-- 4. ad_coupon_claim · 领取记录（含定向发放）
-- ===========================================================================
-- 领取 = 「限领校验真读真扣」落地的表：
--   * 蓝图的「限领」按人计 ⇒ (coupon_id, recipient_key) 的累计张数是判据
--   * 蓝图的「定向发放」（下游 15 客户）自包含地做 ⇒ claim_kind='PUSH'
CREATE TABLE ad_coupon_claim (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  coupon_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, coupon_id)
    REFERENCES ad_coupon(tenant_id, id) ON DELETE CASCADE,

  -- 被领走的那个券码（一个码只能被领一次 ⇒ UNIQUE(tenant_id, code_id)）
  code_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, code_id)
    REFERENCES ad_coupon_code(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 领取方式：
  --   PULL    用户主动领取
  --   PUSH    定向发放（收件人标识 = 目标客户）
  --   CHANNEL 渠道投放领取
  claim_kind text NOT NULL CHECK (claim_kind IN ('PULL','PUSH','CHANNEL')),

  -- 🔴 领取人 / 收件人标识（**字符串**，不跨服务解析 —— 15 客户未交付）
  recipient_key varchar(128) NOT NULL,

  -- 领取渠道：ON_SITE / OFF_SITE / SOCIAL / DIRECT（DIRECT = 定向发放）
  channel_type text NOT NULL CHECK (channel_type IN ('ON_SITE','OFF_SITE','SOCIAL','DIRECT')),
  -- 关联的投放渠道记录（定向发放没有投放位 ⇒ NULL）
  channel_id uuid,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES ad_coupon_channel(tenant_id, id) ON DELETE NO ACTION,

  -- 领取张数（买赠可能一次领多张）
  quantity NUMERIC(19,6) NOT NULL DEFAULT 1 CHECK (quantity > 0),

  claimed_at timestamptz NOT NULL,

  UNIQUE(tenant_id, id),
  -- 🔴 一个券码只能被领一次
  UNIQUE(tenant_id, code_id),
  CHECK (btrim(recipient_key) <> '')
);

CREATE INDEX ad_coupon_claim_coupon ON ad_coupon_claim(tenant_id, coupon_id, claimed_at DESC);
-- 限领判据的反查索引：这张券上这个人领了几张
CREATE INDEX ad_coupon_claim_recipient ON ad_coupon_claim(tenant_id, coupon_id, recipient_key);
CREATE INDEX ad_coupon_claim_channel ON ad_coupon_claim(tenant_id, channel_id);

-- ===========================================================================
-- 5. ad_coupon_redemption · 核销记录（🔴 绑定订单标识）
-- ===========================================================================
-- 蓝图加粗句的落点：**核销必须与订单绑定**，否则无法评估活动真实效果与补贴成本。
CREATE TABLE ad_coupon_redemption (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  coupon_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, coupon_id)
    REFERENCES ad_coupon(tenant_id, id) ON DELETE CASCADE,

  -- 被核销的券码（一个码只能核销一次 ⇒ UNIQUE(tenant_id, code_id)）
  code_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, code_id)
    REFERENCES ad_coupon_code(tenant_id, id) ON DELETE CASCADE,

  -- 🔴🔴 订单标识（NOT NULL）：没有订单标识的核销**写不进来**
  order_ref varchar(128) NOT NULL,

  -- 订单金额（核销数据同步的输入）
  order_amount NUMERIC(19,4) CHECK (order_amount IS NULL OR order_amount >= 0),
  -- 本次核销给客户的让利额（钱）
  redeemed_amount NUMERIC(19,4) CHECK (redeemed_amount IS NULL OR redeemed_amount >= 0),
  -- 🔴 补贴成本（钱）。**买赠券为 NULL（本地测不出成本价）** —— 见第一节第 5 条。
  subsidy_cost NUMERIC(19,4) CHECK (subsidy_cost IS NULL OR subsidy_cost >= 0),
  currency char(3) NOT NULL DEFAULT '',

  redeemed_at timestamptz NOT NULL,

  UNIQUE(tenant_id, id),
  -- 🔴 同一张券在同一订单上只能核销一次
  UNIQUE(tenant_id, coupon_id, order_ref),
  -- 🔴 一个券码只能核销一次
  UNIQUE(tenant_id, code_id),
  CHECK (btrim(order_ref) <> '')
);

CREATE INDEX ad_coupon_redemption_coupon ON ad_coupon_redemption(tenant_id, coupon_id, redeemed_at DESC);
CREATE INDEX ad_coupon_redemption_order ON ad_coupon_redemption(tenant_id, order_ref);

-- ===========================================================================
-- 6. ad_flash_sale · 秒杀活动
-- ===========================================================================
-- 秒杀与券是**两件事**：秒杀不需要券码，它靠**库存 + 限领 + 限时**驱动，
-- 所以是独立表（不塞进 ad_coupon 打折券类型的字段里 —— 那样会让
-- 「这张券有没有码」变成一个说不清的问题）。
CREATE TABLE ad_flash_sale (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  platform_id uuid NOT NULL REFERENCES platform(id),
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  sale_code varchar(64) NOT NULL,
  sale_name varchar(200) NOT NULL,

  -- === 上游 4.1.1.1 listing（同库同服务）—— 秒杀针对单个商品 ===
  --   ON DELETE RESTRICT：挂着秒杀的商品不许被物理删掉（悄悄删掉会让秒杀的标的无声改变）。
  listing_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, listing_id)
    REFERENCES listing(tenant_id, id) ON DELETE RESTRICT,

  -- 🔴 秒杀价（**建活动时登记的快照**，不从 05 定价拉 —— 跨服务不可读）
  flash_price NUMERIC(19,4) NOT NULL CHECK (flash_price >= 0),
  -- 原价快照（NULL = 没登记 ⇒ 不可比，**不是 0**）
  original_price NUMERIC(19,4) CHECK (original_price IS NULL OR original_price >= 0),
  currency char(3) NOT NULL DEFAULT '',

  -- 🔴 秒杀库存与限领（数量用 NUMERIC(19,6)）
  total_stock NUMERIC(19,6) NOT NULL CHECK (total_stock >= 0),
  claimed_quantity NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (claimed_quantity >= 0),
  redeemed_quantity NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (redeemed_quantity >= 0),
  per_user_limit NUMERIC(19,6)
    CHECK (per_user_limit IS NULL OR per_user_limit > 0),

  -- 🔴 秒杀是限时活动 ⇒ 起止时间**必填**
  start_at timestamptz NOT NULL,
  end_at timestamptz NOT NULL,
  CHECK (end_at > start_at),

  -- 投放渠道（站内 / 站外 / 社媒）。秒杀一次只投一个渠道 ⇒ 字段即可。
  channel_type text NOT NULL CHECK (channel_type IN ('ON_SITE','OFF_SITE','SOCIAL')),
  placement varchar(200) NOT NULL DEFAULT '',

  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
    'DRAFT','ACTIVE','PAUSED','ENDED','CANCELLED')),
  platform_sync_status text NOT NULL DEFAULT 'LOCAL_ONLY' CHECK (platform_sync_status IN (
    'LOCAL_ONLY','PENDING_WRITEBACK','SYNCED','FAILED')),
  pending_platform_writeback boolean NOT NULL DEFAULT true,
  local_changed_at timestamptz,
  last_status_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, sale_code),
  CHECK (btrim(sale_code) <> '' AND btrim(sale_name) <> ''),
  CHECK (claimed_quantity <= total_stock),
  CHECK (redeemed_quantity <= claimed_quantity),
  CHECK (pending_platform_writeback = (platform_sync_status <> 'SYNCED'))
);

CREATE INDEX ad_flash_sale_list ON ad_flash_sale(tenant_id, status, id);
CREATE INDEX ad_flash_sale_shop ON ad_flash_sale(tenant_id, shop_code, status);
CREATE INDEX ad_flash_sale_listing ON ad_flash_sale(tenant_id, listing_id);
CREATE INDEX ad_flash_sale_window ON ad_flash_sale(tenant_id, start_at DESC, end_at DESC);

-- ===========================================================================
-- 7. ad_flash_sale_claim · 秒杀领取与核销
-- ===========================================================================
-- 一条记录 = 一次秒杀领取；核销在**同一条记录**上补齐订单标识与补贴成本
-- （一个领取最多对应一个订单 ⇒ 不需要第二张核销表）。
CREATE TABLE ad_flash_sale_claim (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  flash_sale_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, flash_sale_id)
    REFERENCES ad_flash_sale(tenant_id, id) ON DELETE CASCADE,

  -- 领取人标识（字符串，不跨服务解析）
  recipient_key varchar(128) NOT NULL,

  quantity NUMERIC(19,6) NOT NULL DEFAULT 1 CHECK (quantity > 0),
  claimed_at timestamptz NOT NULL,

  -- 🔴 核销绑定的订单标识（核销后写入）
  order_ref varchar(128),
  redeemed_at timestamptz,
  -- 核销让利额（秒杀价与原价之差 × 数量；原价为空 ⇒ NULL，不可比）
  redeemed_amount NUMERIC(19,4) CHECK (redeemed_amount IS NULL OR redeemed_amount >= 0),
  -- 补贴成本（= 让利额；秒杀是价格直降 ⇒ 本地可算）
  subsidy_cost NUMERIC(19,4) CHECK (subsidy_cost IS NULL OR subsidy_cost >= 0),
  currency char(3) NOT NULL DEFAULT '',

  status text NOT NULL DEFAULT 'CLAIMED' CHECK (status IN (
    'CLAIMED','REDEEMED','CANCELLED')),

  UNIQUE(tenant_id, id),
  CHECK (btrim(recipient_key) <> ''),
  -- 已核销的行必须有订单标识（与「核销要与订单绑定」同一条纪律）
  CHECK (status <> 'REDEEMED' OR (order_ref IS NOT NULL AND btrim(order_ref) <> ''))
);

CREATE INDEX ad_flash_sale_claim_sale ON ad_flash_sale_claim(tenant_id, flash_sale_id, claimed_at DESC);
CREATE INDEX ad_flash_sale_claim_recipient
  ON ad_flash_sale_claim(tenant_id, flash_sale_id, recipient_key);
CREATE INDEX ad_flash_sale_claim_order ON ad_flash_sale_claim(tenant_id, order_ref);

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V146 / V273 / V277 / V278 / V284 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_coupon', 'ad_coupon_code', 'ad_coupon_channel',
    'ad_coupon_claim', 'ad_coupon_redemption',
    'ad_flash_sale', 'ad_flash_sale_claim' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（照抄 V273 `ad_campaign` / V284 `ad_promotion` 同构）
-- ---------------------------------------------------------------------------
-- 券与秒杀挂在店铺下，运营不能绕过店铺直接枚举别家的券。
CREATE POLICY data_scope ON ad_coupon AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_coupon.tenant_id AND s.code=ad_coupon.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_coupon.tenant_id AND s.code=ad_coupon.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_coupon_code AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_coupon c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_coupon_code.tenant_id AND c.id=ad_coupon_code.coupon_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_coupon c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_coupon_code.tenant_id AND c.id=ad_coupon_code.coupon_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_coupon_channel AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_coupon c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_coupon_channel.tenant_id AND c.id=ad_coupon_channel.coupon_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_coupon c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_coupon_channel.tenant_id AND c.id=ad_coupon_channel.coupon_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_coupon_claim AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_coupon c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_coupon_claim.tenant_id AND c.id=ad_coupon_claim.coupon_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_coupon c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_coupon_claim.tenant_id AND c.id=ad_coupon_claim.coupon_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_coupon_redemption AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_coupon c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_coupon_redemption.tenant_id AND c.id=ad_coupon_redemption.coupon_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_coupon c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_coupon_redemption.tenant_id AND c.id=ad_coupon_redemption.coupon_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_flash_sale AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_flash_sale.tenant_id AND s.code=ad_flash_sale.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_flash_sale.tenant_id AND s.code=ad_flash_sale.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_flash_sale_claim AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_flash_sale f JOIN shop s ON s.tenant_id=f.tenant_id AND s.code=f.shop_code
             WHERE f.tenant_id=ad_flash_sale_claim.tenant_id AND f.id=ad_flash_sale_claim.flash_sale_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_flash_sale f JOIN shop s ON s.tenant_id=f.tenant_id AND s.code=f.shop_code
             WHERE f.tenant_id=ad_flash_sale_claim.tenant_id AND f.id=ad_flash_sale_claim.flash_sale_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V273/V277/V278/V284 的条件建角色，保证迁移可重复执行）
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
  ad_coupon, ad_coupon_code, ad_coupon_channel,
  ad_coupon_claim, ad_coupon_redemption,
  ad_flash_sale, ad_flash_sale_claim TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V277 / V278 / V284 同口径）。
REVOKE ALL ON
  ad_coupon, ad_coupon_code, ad_coupon_channel,
  ad_coupon_claim, ad_coupon_redemption,
  ad_flash_sale, ad_flash_sale_claim FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：优惠券 / 秒杀管理属于「平台操作」语义，与 14.4.1.1 促销列表、
-- 14.2.2.1 预算分配同族，因此 identity 服务零改动，跨车道回归面 = 0。
