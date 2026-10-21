-- ===========================================================================
-- 14.4.1.1 促销列表（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.4.1.1 促销列表
--   - 职责：管理平台促销活动：促销类型、适用商品、折扣幅度、起止时间、预算上限、参与店铺，
--           同步状态与效果。
--   - 输入：促销定义；平台促销接口；适用商品选择。
--   - 输出：促销记录 + 平台同步状态。
--   - 关系：上游 = 5 定价（促销价计算）；下游 = 平台促销 API、12 财务（费用）。
--           **促销的毛利必须实时测算**——折扣叠加后亏损是常态，需在下单前拦截或预警。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 策略**，零 ALTER 任何既有表：
--   * V1 `platform`（全局平台码表）+ V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
--   * V171 `listing`（**同库同服务**，4.1.1.1 已交付）—— 促销的「适用商品」挂它，
--     写法照抄 V277 `ad_campaign_group_product` 的 `listing_id` + 复合 FK
--
-- ---------------------------------------------------------------------------
-- 一、🔴 四条事实边界（写进库，不是写进注释而已）
-- ---------------------------------------------------------------------------
-- 1. **5 定价在 catalog 服务 ⇒ 禁止跨服务 SQL。**
--    定价表（`pricing_rule` / `pricing_rule_scope` 等，V236）在 **catalog**，
--    channel 不得 join catalog 的表，不建跨库 FK，不建共享视图。
--
-- 2. **catalog 没有促销价测算端点**（已 grep 确认：无 promo / discount 端点，
--    无 promo price 类服务）⇒ **促销毛利 / 利润维度没有可调用的上游**。
--    所以本模块**不建任何毛利列**：空表 / 假零会被渲染成「毛利是 0」，
--    那是假事实，比明说「拿不到」严重得多。毛利与利润维度由服务层在
--    `capabilities` 里显式声明 `available=false` + `ownerModule`（指向 5 定价）。
--
-- 3. **平台促销 API 未交付 ⇒ 同步状态必须表达「本地已建、还没回写平台」。**
--    照抄 V273 `ad_campaign.pending_platform_writeback` + `local_changed_at` 的口径：
--    否则界面上的「已创建促销」会被当成平台侧已创建，那是假事实。
--
-- 4. **12 财务（卷 12 主责服务 finance）不在本仓** ⇒ 促销费用维度不可用，
--    不去 finance 服务建表，也不跨服务读。
--
-- ---------------------------------------------------------------------------
-- 二、🔴「折扣叠加后亏损是常态，需在下单前拦截或预警」怎么落地
-- ---------------------------------------------------------------------------
-- 蓝图这句有两半，本模块**分别**对待，不混为一谈：
--
--   * **能本地算的那半 —— 真做**：
--       折后价 = 基准价 × (1 − 折扣率)      （百分比促销）
--       折后价 = 基准价 − 固定减免额         （固定额促销）
--       叠加折后价 = 基准价 × Π(各促销折后系数)
--     并据此做**真规则**：折扣幅度是否超出阈值、折后价是否低于下限、
--     多个促销是否叠加。这三件事**不需要成本数据**，本模块**真做**：
--     有真实写入口（`ad_promotion_guardrail` 规则配置 + `ad_promotion_check` 记录），
--     有真实判定与真实落库，不是「后续再补」。
--
--   * **拿不到成本的那半 —— 显式不可用**：
--     「是否亏损」要拿成本价，成本在 05 定价（catalog，跨服务不可读）
--     ⇒ 毛利维度 `available=false` + `ownerModule='5 定价'`。
--     **不写一个 0 毛利糊弄过去** —— 界面上的「毛利 0」会被当成「这单不赚钱」，
--     而真相是「拿不到成本」。两者在运营决策上完全相反。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 基准价（base_price）的来源纪律
-- ---------------------------------------------------------------------------
-- `listing` **没有价格列**（V171 明确：「无价格列：售价归 05 定价」），
-- channel 侧也没有平台售价事实（V191 注释：平台商品来源当前恒为 NULL）。
-- 所以 `ad_promotion_product.base_price` 是**促销定义自带的输入** ——
-- 建促销时登记的「该商品促销前的基准价」，是促销平台接口本身要用的参数，
-- **不是**从 05 定价拉来的（跨服务不可读），**也不是**从 listing 拉的（它没有）。
-- 没登记基准价 ⇒ 折后价维度**不可比**（null），不是 0。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 促销与商品是多对多 ⇒ 独立关联表 + 带 tenant_id 的复合 FK
-- ---------------------------------------------------------------------------
-- 一个促销可覆盖多个商品，一个商品可被多个促销覆盖（这正是「折扣叠加」的来源）。
-- 独立关联表 + `FOREIGN KEY (tenant_id, listing_id) REFERENCES listing(tenant_id, id)`
-- ⇒ 跨租户挂载在物理上不可能（写不进别人的 listing）。
--
-- ===========================================================================
-- 1. ad_promotion · 促销主表
-- ===========================================================================
CREATE TABLE ad_promotion (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 2.1.1.1 平台目录（全局码表）===
  platform_id uuid NOT NULL REFERENCES platform(id),

  -- === 上游 2.3.1.1 店铺（UNIQUE(tenant_id,code)，复合 FK ⇒ 跨租户挂载不可能）===
  -- 蓝图的「参与店铺」：一个促销先落在一个店铺上；多店铺参与是平台侧的能力，
  -- 本地模型不假装已经支持（那是平台促销 API 到位后的事情）。
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 促销编码（租户内唯一）：运营与运营之间对促销的唯一指称
  promotion_code varchar(64) NOT NULL,
  promotion_name varchar(200) NOT NULL,

  -- 🔴 促销类型：平台侧促销形态的**归一**口径
  --   PERCENT_OFF      百分比折扣（给折扣率）
  --   FIXED_AMOUNT_OFF 固定额减免（给减免额）
  --   BOGO             买 N 送 M
  --   BUNDLE           组合优惠
  --   FREE_SHIPPING    免运费
  promotion_type text NOT NULL CHECK (promotion_type IN (
    'PERCENT_OFF','FIXED_AMOUNT_OFF','BOGO','BUNDLE','FREE_SHIPPING')),

  -- 🔴 折扣幅度。百分比促销给折扣率（0 < r <= 1，0.2 = 打八折让利 20%），
  --   固定额促销给减免额（钱）。两者**互斥**由 CHECK 钉死 ——
  --   两个都填会让「折后价」有两个答案，那是不可审阅的账。
  discount_rate NUMERIC(19,6)
    CHECK (discount_rate IS NULL OR (discount_rate > 0 AND discount_rate <= 1)),
  discount_amount NUMERIC(19,4)
    CHECK (discount_amount IS NULL OR discount_amount >= 0),

  -- 🔴 预算上限（促销让利 / 补贴的封顶）。NULL = **不限**，不是 0。
  budget_cap NUMERIC(19,4) CHECK (budget_cap IS NULL OR budget_cap >= 0),
  currency char(3) NOT NULL DEFAULT '',

  -- 🔴 起止时间。timestamptz + 服务层 OffsetDateTime 绑定 + values 位置写
  --   `?::timestamptz`（jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒）。
  start_at timestamptz,
  end_at timestamptz,
  -- 结束必须晚于开始（两个都给了的时候）
  CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at),

  -- 🔴 本地生命周期状态（**本地**状态，不是平台侧状态）
  --   DRAFT 草稿 / SCHEDULED 已排期 / ACTIVE 进行中 / PAUSED 已暂停
  --   / ENDED 已结束 / CANCELLED 已取消
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
    'DRAFT','SCHEDULED','ACTIVE','PAUSED','ENDED','CANCELLED')),

  -- 🔴🔴 平台同步状态（蓝图输出「平台同步状态」）
  --   LOCAL_ONLY        本地已建，**平台侧还没有这个促销**（平台促销 API 未交付 ⇒ 初始就是这个）
  --   PENDING_WRITEBACK 回写已发起，待平台确认
  --   FAILED            回写失败（仍与平台不一致 ⇒ 仍需再回写）
  --   SYNCED            平台侧已确认一致 —— **只能由平台回写适配器产生，
  --                     该适配器尚未交付**，所以本批代码里不会有任何路径写入它。
  platform_sync_status text NOT NULL DEFAULT 'LOCAL_ONLY' CHECK (platform_sync_status IN (
    'LOCAL_ONLY','PENDING_WRITEBACK','SYNCED','FAILED')),

  -- 🔴 「本地已改、还没回写平台」的显式布尔位（照抄 V273 ad_campaign 同名字段）：
  --   语义 = **平台侧与本地不一致**。促销是在本地建的，平台侧压根没有这个促销 ⇒ true。
  --   界面上**必须**写明「本地已建，平台侧还没有」——
  --   否则「已创建促销」会被当成平台侧已创建，那是假事实。
  pending_platform_writeback boolean NOT NULL DEFAULT true,
  -- 本地最后一次变更的时刻（与上面的布尔位配套，界面要能说清「什么时候改的、平台侧还没有」）
  local_changed_at timestamptz,
  -- 最近一次状态变更的理由（状态机每次改动的理由要能查）
  last_status_reason text NOT NULL DEFAULT '',

  -- 🔴 最近一次**检查**的批次指针。
  --   「最近一次检查」不能靠「取 max(checked_at) 的那批行」来定位：
  --   一次**没有发现问题**的检查**一行都不写** ⇒ 那样会退回到上一批（还带着旧拦截）的结论，
  --   改合规之后反而永远过不去。所以指针落在促销行上，与有没有结论无关。
  last_checked_at timestamptz,
  last_check_run_id uuid,

  -- 平台侧促销 id（未回写 ⇒ 空串；不是 null，因为平台还没建）
  platform_promotion_id varchar(128) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 促销编码租户内唯一（两个促销同名好查，同码则没法区分）
  UNIQUE(tenant_id, promotion_code),

  -- 🔴 折扣口径三选一：百分比 / 固定额 / 其它类型（两者都不给）
  CHECK (
    (promotion_type = 'PERCENT_OFF' AND discount_rate IS NOT NULL AND discount_amount IS NULL)
    OR (promotion_type = 'FIXED_AMOUNT_OFF' AND discount_amount IS NOT NULL AND discount_rate IS NULL)
    OR (promotion_type IN ('BOGO','BUNDLE','FREE_SHIPPING')
        AND discount_rate IS NULL AND discount_amount IS NULL)
  ),

  -- 🔴 「平台侧与本地不一致」= 待回写。SYNCED 之外的状态一律待回写 ——
  --   DB 层钉死，绕服务层直接写 SQL 也写不出「平台还没同步却标成已同步」。
  CHECK (pending_platform_writeback = (platform_sync_status <> 'SYNCED'))
);

CREATE INDEX ad_promotion_list ON ad_promotion(tenant_id, status, id);
CREATE INDEX ad_promotion_shop ON ad_promotion(tenant_id, shop_code, status);
CREATE INDEX ad_promotion_platform ON ad_promotion(tenant_id, platform_id, status);
CREATE INDEX ad_promotion_type ON ad_promotion(tenant_id, promotion_type);
CREATE INDEX ad_promotion_sync ON ad_promotion(tenant_id, platform_sync_status);
CREATE INDEX ad_promotion_window ON ad_promotion(tenant_id, start_at DESC, end_at DESC);

-- ===========================================================================
-- 2. ad_promotion_product · 促销适用商品（多对多）
-- ===========================================================================
-- 蓝图的「适用商品选择」是**独立关联表**（不是 listing 上的一个字段）：
-- 一个促销可覆盖多个商品，一个商品可被多个促销覆盖 —— 后者正是「折扣叠加」的来源。
CREATE TABLE ad_promotion_product (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  promotion_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, promotion_id)
    REFERENCES ad_promotion(tenant_id, id) ON DELETE CASCADE,

  -- === 上游 4.1.1.1 listing（同库同服务，复合 FK 带 tenant_id）===
  --   写法照抄 V277 `ad_campaign_group_product`。
  --   ON DELETE RESTRICT：挂着促销的商品不许被物理删掉 ——
  --   悄悄删掉会让一个促销的覆盖范围无声改变。
  listing_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, listing_id)
    REFERENCES listing(tenant_id, id) ON DELETE RESTRICT,

  -- 🔴 基准价（促销前的原价）：**促销定义自带的输入**（见第三节）。
  --   没登记 ⇒ 折后价维度不可比（null），**不是 0**。
  base_price NUMERIC(19,4) CHECK (base_price IS NULL OR base_price >= 0),
  currency char(3) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个促销里同一个 listing 只挂一次
  UNIQUE(tenant_id, promotion_id, listing_id)
);

CREATE INDEX ad_promotion_product_promotion ON ad_promotion_product(tenant_id, promotion_id);
-- 叠加检测的入口索引：按 listing 反查「这个商品被哪些促销覆盖了」
CREATE INDEX ad_promotion_product_listing ON ad_promotion_product(tenant_id, listing_id);

-- ===========================================================================
-- 3. ad_promotion_guardrail · 促销拦截 / 预警规则（租户级配置）
-- ===========================================================================
-- 蓝图：「折扣叠加后亏损是常态，需在下单前拦截或预警」。
-- 本表是那套规则的**真实落点**：阈值可配置、规则有真判定、判定结果落 `ad_promotion_check`。
--
-- 🔴 规则只覆盖**本地可算**的三件事（不需要成本数据）：
--   * DISCOUNT_DEPTH   折扣幅度是否超出阈值（百分比促销）
--   * PRICE_FLOOR      折后价是否低于下限
--   * PROMOTION_STACKING 多个促销是否叠加覆盖同一商品
-- 「是否亏损」**不在**这里判定 —— 那要成本价，成本在 05 定价（跨服务不可读）。
CREATE TABLE ad_promotion_guardrail (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  guardrail_code varchar(64) NOT NULL,
  guardrail_name varchar(200) NOT NULL,

  -- 🔴 折扣幅度上限（折扣率 > 上限 ⇒ 拦截）。NULL = 这一条不设阈值。
  max_discount_rate NUMERIC(19,6)
    CHECK (max_discount_rate IS NULL OR (max_discount_rate > 0 AND max_discount_rate <= 1)),
  -- 🔴 折后价下限（折后价 < 下限 ⇒ 拦截）。NULL = 这一条不设阈值。
  min_net_price NUMERIC(19,4) CHECK (min_net_price IS NULL OR min_net_price >= 0),
  -- 🔴 促销叠加是否**拦截**（false = 只预警不拦截）
  block_on_stacking boolean NOT NULL DEFAULT true,

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, guardrail_code),
  CHECK (btrim(guardrail_code) <> '' AND btrim(guardrail_name) <> '')
);

CREATE INDEX ad_promotion_guardrail_status ON ad_promotion_guardrail(tenant_id, status);

-- 🔴 一个租户**同一时刻只能有一套生效的促销护栏**。
--   两套阈值同时生效时，「折扣幅度上限到底是多少」就没有答案了 ——
--   DB 层用部分唯一索引钉死，服务层也按这个口径走（启用新的一套 ⇒ 旧的自动停用）。
CREATE UNIQUE INDEX ad_promotion_guardrail_single_active
  ON ad_promotion_guardrail(tenant_id) WHERE status = 'ACTIVE';

-- ===========================================================================
-- 4. ad_promotion_check · 拦截 / 预警记录（判定结果的真落点）
-- ===========================================================================
-- 每次跑检查都留痕：拦下了什么、警报了什么、观测值多少、阈值多少。
-- 不留痕 ⇒ 运营事后问「为什么这条促销没生效」查不出来。
CREATE TABLE ad_promotion_check (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  promotion_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, promotion_id)
    REFERENCES ad_promotion(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 商品级检查才有 listing_id；促销级检查（如折扣幅度）没有 ⇒ NULL。
  listing_id uuid,
  FOREIGN KEY (tenant_id, listing_id)
    REFERENCES listing(tenant_id, id) ON DELETE RESTRICT,

  rule_code text NOT NULL CHECK (rule_code IN (
    'DISCOUNT_DEPTH','PRICE_FLOOR','PROMOTION_STACKING','BUDGET_CAP')),
  -- BLOCKED 拦截（不允许继续）/ WARNING 预警（允许但要人知道）
  severity text NOT NULL CHECK (severity IN ('BLOCKED','WARNING')),

  -- 🔴 检查批次 id：一次检查写出的多行共享同一个 check_run_id。
  --   「最近一次检查」= 按 (checked_at desc, check_run_id desc) 取最新那一批 ——
  --   只靠 checked_at 排序在两次检查落在同一微秒时会串批，串批会让计数错。
  check_run_id uuid NOT NULL,

  -- 🔴 说清为什么：拦截/预警的结论必须带一句人话
  message text NOT NULL DEFAULT '',

  -- 观测值与阈值（可空 = 该规则这次没有可比数值）
  observed_value NUMERIC(19,6),
  threshold_value NUMERIC(19,6),

  checked_at timestamptz NOT NULL,

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_promotion_check_promotion ON ad_promotion_check(tenant_id, promotion_id, checked_at DESC);
CREATE INDEX ad_promotion_check_listing ON ad_promotion_check(tenant_id, listing_id, checked_at DESC);

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V146 / V273 / V277 / V278 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_promotion', 'ad_promotion_product',
    'ad_promotion_guardrail', 'ad_promotion_check' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（照抄 V273 `ad_campaign` / V277 `ad_campaign_group_product` 同构）
-- ---------------------------------------------------------------------------
-- 促销挂在店铺下，运营不能绕过店铺直接枚举别家的促销。
-- 规则表 `ad_promotion_guardrail` 是**租户级配置事实**（一个租户一套促销护栏），
-- 不按店铺收敛 —— 与 V278 `ad_budget_rule` 同一纪律。
CREATE POLICY data_scope ON ad_promotion AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_promotion.tenant_id AND s.code=ad_promotion.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_promotion.tenant_id AND s.code=ad_promotion.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_promotion_product AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_product.tenant_id AND p.id=ad_promotion_product.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_product.tenant_id AND p.id=ad_promotion_product.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_promotion_check AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_check.tenant_id AND p.id=ad_promotion_check.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_promotion p JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_promotion_check.tenant_id AND p.id=ad_promotion_check.promotion_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V273/V277/V278 的条件建角色，保证迁移可重复执行）
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
  ad_promotion, ad_promotion_product,
  ad_promotion_guardrail, ad_promotion_check TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V277 / V278 同口径）。
REVOKE ALL ON
  ad_promotion, ad_promotion_product,
  ad_promotion_guardrail, ad_promotion_check FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：促销管理属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.2.2.1 预算分配同族，因此 identity 服务零改动，跨车道回归面 = 0。
