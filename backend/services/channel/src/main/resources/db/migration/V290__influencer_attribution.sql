-- ===========================================================================
-- 14.5.2.1 带货归因（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.5.2.1 带货归因
--   - 职责：达人带货归因：达人带来的订单量、GMV、退款率、佣金成本、有效粉丝转化，
--           对比达人投入产出，沉淀优质达人库。
--   - 输入：达人带货数据同步；订单归因；佣金数据。
--   - 输出：达人效果报表 + ROI + 评级更新。
--   - 关系：上游 = 14.5.1.1、平台数据；下游 = 14.5.1.1 评级、13.4 利润（佣金成本）。
--           **达人佣金是真实成本，必须进利润表**，否则会高估达人带货的净收益。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 策略**，零 ALTER 任何既有表：
--   * V288 `influencer`（14.5.1.1 达人档案）
--   * V288 `influencer_cooperation`（14.5.1.1 合作历史：达人 × 活动 + 效果数据）
--   * V288 `influencer_rating_rule` / `influencer_rating_tier`（**只读引用，不改结构**）
--   * V273 `ad_campaign`（同库同服务，14.1.1.1）
--   * V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）
-- 全部复合 FK 都带 tenant_id ⇒ 跨租户挂载在物理上不可能。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 三个 GMV 口径不能混为一谈（本模块存在的理由）
-- ---------------------------------------------------------------------------
-- 14.5.1.1 已经留了两个口径，本模块是**第三个**：
--   1. **档案 GMV**（`influencer.gmv`）：入库值，手动录入 / 未来平台同步。
--   2. **合作历史合计**：本地按 `influencer_cooperation` 行求和（合作口径）。
--   3. **归因口径**（本模块 `influencer_attribution`）：按**归因规则**分摊到人以后
--      的净额 —— 会受归因窗口、分摊权重、退款口径、佣金计提口径影响。
-- 三者在报表里**分列三栏**，谁也不覆盖谁。**本迁移不改 14.5.1.1 的任何一列**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 归因规则必须固定并公示（规则变了历史报表不可比）
-- ---------------------------------------------------------------------------
-- 所以：
--   * 归因规则是**租户级配置事实**（`influencer_attribution_rule`），
--     partial unique index 保证一个租户同时**只有一套生效口径**——
--     两套口径同时生效时，「这个达人的归因 GMV 是多少」就没有答案了。
--   * 每条归因记录**自带规则快照**（归因模型 / 分摊基准 / 归因窗口 / 退款口径 /
--     佣金口径与比例）：口径改了之后，历史归因仍然能被解释清楚，
--     报表也能按 `rule_id` 过滤出「当前口径」与「历史口径」两段。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 佣金成本必须在本服务内真实留痕
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「**达人佣金是真实成本，必须进利润表**」。
-- ⚠️ **13.4 利润（reporting 服务）未交付，集成头里根本没有 reporting 服务**，
-- 所以本模块**只做到「成本在本服务内真实留痕」**：
--   * 归因行上带**佣金金额**（`commission_amount`）、**计提基数**（`commission_basis_amount`）、
--     **计提比例**（`commission_rate`）、**成本口径**（`commission_basis`）、
--     **计提状态**（`accrual_status`：PENDING / ACCRUED / REVERSED）与**计提时刻**。
--   * **本迁移与本模块都不声称「佣金成本已同步到利润分析」** —— 那件事没有接收方。
--     能力声明里 `profitIntegration.available = false`，责任模块 13.4。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 平台带货数据适配器未交付
-- ---------------------------------------------------------------------------
-- channel 的适配层（`vendor/`）目前**只有 13 个 OAuth 适配器**，
-- **没有任何平台的达人带货数据适配器**。所以本模块的归因数据**只能来自本地已有事实**：
-- 14.5.1.1 的 `influencer_cooperation` 合作记录（GMV / 订单量 / 佣金 / 退款率）
-- 与达人档案（粉丝数 / 互动率 / 退款率）。
-- 服务层用端口 `InfluencerAttributionDataPort` 表达「平台带货数据」那条接缝，
-- 当前实现 `UnavailableInfluencerAttributionDataPort` 对所有平台答「未接入」，
-- 同步入口据此返回 409 且**不写任何数据**。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 归因窗口语义（不抹掉已发生的事实）
-- ---------------------------------------------------------------------------
-- 归因窗口（天）从**合作结束时刻**起算：超窗的合作记录**不再允许新增归因**
-- （账期过了就没法补记）。但**已经归因过的记录不会被后续批次抹掉** ——
-- 已归因是**事实**（佣金已经按它计提过），删掉等于让已发生的成本凭空消失。
-- 同规则重跑只刷新**仍在窗口内**的合作；出窗的归因保留上一次的结果。
--
-- ---------------------------------------------------------------------------
-- 建表顺序（有外键依赖，顺序不能换）：
--   1 influencer_attribution_rule   归因规则（租户级配置）
--   2 influencer_attribution         归因记录（事实：达人 × 合作 × 规则）
-- ===========================================================================

-- ===========================================================================
-- 1. influencer_attribution_rule · 归因规则（租户级配置）
-- ===========================================================================
-- 一个租户**同一时刻只能有一套生效的归因口径**（partial unique index 钉死）。
CREATE TABLE influencer_attribution_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  rule_code varchar(64) NOT NULL,

  -- 🔴 归因模型（决定「合作效果怎么落到达人身上」）：
  --   COOPERATION_FULL      一条合作记录的效果全额归到该达人（默认，最保守）
  --   CAMPAIGN_POOL_SPLIT   同活动内分摊：活动池子按权重分给该活动下的各位达人
  attribution_model text NOT NULL DEFAULT 'COOPERATION_FULL' CHECK (attribution_model IN (
    'COOPERATION_FULL','CAMPAIGN_POOL_SPLIT')),

  -- 🔴 分摊权重基准（只在 CAMPAIGN_POOL_SPLIT 下有意义）：
  --   FOLLOWER_SHARE         按达人在该活动内的粉丝数占比分摊（触达口径）
  --   COOPERATION_GMV_SHARE  按各达人自己在该活动内的合作 GMV 占比分摊（业绩口径）
  pool_weight_basis text NOT NULL DEFAULT 'FOLLOWER_SHARE' CHECK (pool_weight_basis IN (
    'FOLLOWER_SHARE','COOPERATION_GMV_SHARE')),

  -- 🔴 归因窗口（天）：从合作结束时刻起算，超窗不再允许新增归因。
  attribution_window_days int NOT NULL DEFAULT 30
    CHECK (attribution_window_days >= 0 AND attribution_window_days <= 365),

  -- 🔴 退款口径：GROSS 毛额（不扣退款）/ NET 净额（归因 GMV 扣掉退款额）。
  refund_treatment text NOT NULL DEFAULT 'GROSS' CHECK (refund_treatment IN ('GROSS','NET')),

  -- 🔴 佣金计提口径：
  --   COOPERATION_COMMISSION   用合作记录里已录的佣金（× 分摊权重）
  --   RATE_ON_ATTRIBUTED_GMV   按**归因 GMV** × 佣金比例计提
  commission_basis text NOT NULL DEFAULT 'COOPERATION_COMMISSION' CHECK (commission_basis IN (
    'COOPERATION_COMMISSION','RATE_ON_ATTRIBUTED_GMV')),

  -- 佣金比例（0 ~ 1）。只在 RATE_ON_ATTRIBUTED_GMV 下必填 —— DB CHECK 钉死，
  -- 否则「按比例计提」会变成「按 0 计提」。
  commission_rate NUMERIC(19,6)
    CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1)),

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code),
  CHECK (btrim(rule_code) <> ''),
  CHECK ((commission_basis = 'RATE_ON_ATTRIBUTED_GMV' AND commission_rate IS NOT NULL)
      OR (commission_basis = 'COOPERATION_COMMISSION' AND commission_rate IS NULL))
);

-- 🔴 一个租户同时只能有一套生效口径（两套口径同时生效 ⇒ 归因 GMV 没有答案）。
CREATE UNIQUE INDEX influencer_attribution_rule_single_active
  ON influencer_attribution_rule(tenant_id) WHERE status = 'ACTIVE';

CREATE INDEX influencer_attribution_rule_status
  ON influencer_attribution_rule(tenant_id, status, created_at DESC);

-- ===========================================================================
-- 2. influencer_attribution · 归因记录（达人 × 合作 × 规则）
-- ===========================================================================
CREATE TABLE influencer_attribution (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 14.5.1.1 达人档案（复合 FK 带 tenant_id）===
  influencer_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, influencer_id)
    REFERENCES influencer(tenant_id, id) ON DELETE RESTRICT,

  -- === 上游 14.5.1.1 合作历史（复合 FK 带 tenant_id）===
  --   归因**必须**挂一条真实存在的合作记录：归因数据只来自本地已有事实。
  cooperation_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, cooperation_id)
    REFERENCES influencer_cooperation(tenant_id, id) ON DELETE RESTRICT,

  -- === 归因规则（复合 FK）===
  rule_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, rule_id)
    REFERENCES influencer_attribution_rule(tenant_id, id) ON DELETE RESTRICT,

  -- 活动（镜像合作记录的活动，便于按活动聚合；复合 FK 带 tenant_id）
  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE RESTRICT,

  -- === 上游 2.3.1.1 店铺（复合 FK 带 tenant_id）===
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE RESTRICT,

  -- -------------------------------------------------------------------------
  -- 🔴 规则快照（口径固定并公示）
  -- -------------------------------------------------------------------------
  attribution_model text NOT NULL CHECK (attribution_model IN (
    'COOPERATION_FULL','CAMPAIGN_POOL_SPLIT')),
  pool_weight_basis text NOT NULL CHECK (pool_weight_basis IN (
    'FOLLOWER_SHARE','COOPERATION_GMV_SHARE')),
  attribution_window_days int NOT NULL CHECK (attribution_window_days >= 0),
  refund_treatment text NOT NULL CHECK (refund_treatment IN ('GROSS','NET')),
  commission_basis text NOT NULL CHECK (commission_basis IN (
    'COOPERATION_COMMISSION','RATE_ON_ATTRIBUTED_GMV')),
  commission_rate NUMERIC(19,6)
    CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1)),

  -- 🔴 归因权重（0 ~ 1）：这条合作在池子里分到这个达人的份额。
  --   COOPERATION_FULL 恒为 1；CAMPAIGN_POOL_SPLIT 下按 pool_weight_basis 算。
  attribution_weight NUMERIC(19,6) NOT NULL
    CHECK (attribution_weight >= 0 AND attribution_weight <= 1),

  -- -------------------------------------------------------------------------
  -- 🔴 源快照（**这次合作自身的值**）：用来解释「归因 GMV = 归因基数 × 权重」
  -- -------------------------------------------------------------------------
  --   null = **还没录**（不可比），不是 0。
  source_gmv NUMERIC(19,4) CHECK (source_gmv IS NULL OR source_gmv >= 0),
  source_order_count NUMERIC(19,6) CHECK (source_order_count IS NULL OR source_order_count >= 0),
  source_commission NUMERIC(19,4) CHECK (source_commission IS NULL OR source_commission >= 0),
  source_refund_rate NUMERIC(19,6)
    CHECK (source_refund_rate IS NULL OR (source_refund_rate >= 0 AND source_refund_rate <= 1)),
  source_currency char(3) NOT NULL DEFAULT '',

  -- -------------------------------------------------------------------------
  -- 🔴 归因基数（**实际拿来乘权重的那一组数**）
  -- -------------------------------------------------------------------------
  --   COOPERATION_FULL      ⇒ 基数 = 这次合作自身的值（权重恒为 1）
  --   CAMPAIGN_POOL_SPLIT   ⇒ 基数 = 该活动下的**池子合计**（权重 = 本人的份额）
  --   两者都记下来，报表里「归因 GMV = 基数 × 权重」永远可复核。
  attribution_base_gmv NUMERIC(19,4)
    CHECK (attribution_base_gmv IS NULL OR attribution_base_gmv >= 0),
  attribution_base_order_count NUMERIC(19,6)
    CHECK (attribution_base_order_count IS NULL OR attribution_base_order_count >= 0),
  attribution_base_refund_rate NUMERIC(19,6)
    CHECK (attribution_base_refund_rate IS NULL OR (attribution_base_refund_rate >= 0 AND attribution_base_refund_rate <= 1)),

  -- -------------------------------------------------------------------------
  -- 🔴 归因结果（**第三口径**：不是档案 GMV，也不是合作历史合计）
  -- -------------------------------------------------------------------------
  attributed_gmv NUMERIC(19,4) CHECK (attributed_gmv IS NULL OR attributed_gmv >= 0),
  attributed_order_count NUMERIC(19,6)
    CHECK (attributed_order_count IS NULL OR attributed_order_count >= 0),
  -- 归因退款率（0 ~ 1）
  attributed_refund_rate NUMERIC(19,6)
    CHECK (attributed_refund_rate IS NULL OR (attributed_refund_rate >= 0 AND attributed_refund_rate <= 1)),
  -- 归因退款额（= 归因毛额 × 归因退款率）
  --   🔴 归因毛额 = 归因基数 × 权重（**两种退款口径下都成立**）——
  --   报表据此算加权退款率（不需要另存一个毛额列）。
  attributed_refund_amount NUMERIC(19,4)
    CHECK (attributed_refund_amount IS NULL OR attributed_refund_amount >= 0),
  -- 有效订单量（归因订单量扣掉退款部分）
  effective_order_count NUMERIC(19,6)
    CHECK (effective_order_count IS NULL OR effective_order_count >= 0),
  -- 有效粉丝转化率（0 ~ 1）= 有效订单量 / 粉丝数快照
  follower_conversion_rate NUMERIC(19,6)
    CHECK (follower_conversion_rate IS NULL OR (follower_conversion_rate >= 0 AND follower_conversion_rate <= 1)),
  -- 粉丝数快照（归因时刻的触达规模；有效粉丝转化率的分母）
  follower_count_snapshot bigint NOT NULL DEFAULT 0 CHECK (follower_count_snapshot >= 0),
  currency char(3) NOT NULL DEFAULT '',

  -- -------------------------------------------------------------------------
  -- 🔴 佣金成本（真实成本，在本服务内留痕；**不声称已进利润表**）
  -- -------------------------------------------------------------------------
  --   佣金金额（null = 还没录 / 合作记录里没录佣金，不是 0）
  commission_amount NUMERIC(19,4) CHECK (commission_amount IS NULL OR commission_amount >= 0),
  -- 计提基数（比例乘在谁身上）
  commission_basis_amount NUMERIC(19,4)
    CHECK (commission_basis_amount IS NULL OR commission_basis_amount >= 0),
  -- 🔴 佣金差额：口径变了 / 源数据改了，重算出来的佣金与**已计提**佣金之间的差。
  --   已计提的佣金**不自动改写**（计提是记账事实），差额显式暴露出来等人处理。
  commission_variance NUMERIC(19,4),

  -- 🔴 计提状态：PENDING 待计提 / ACCRUED 已计提 / REVERSED 已冲销。
  accrual_status text NOT NULL DEFAULT 'PENDING' CHECK (accrual_status IN (
    'PENDING','ACCRUED','REVERSED')),
  -- 计提时刻（ACCRUED / REVERSED 必填，PENDING 必须为空）
  accrued_at timestamptz,
  accrual_note text NOT NULL DEFAULT '',

  -- 归因时刻（timestamptz）
  attributed_at timestamptz NOT NULL DEFAULT now(),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个达人在同一条合作、同一个规则下只归因一次（重跑是幂等刷新，不是插重复行）
  UNIQUE(tenant_id, influencer_id, cooperation_id, rule_id),
  CHECK ((accrual_status = 'PENDING' AND accrued_at IS NULL)
      OR (accrual_status IN ('ACCRUED','REVERSED') AND accrued_at IS NOT NULL))
);

-- 🔴 检索入口：达人 / 活动 / 店铺 / 规则。
CREATE INDEX influencer_attribution_influencer
  ON influencer_attribution(tenant_id, influencer_id, attributed_at DESC);
CREATE INDEX influencer_attribution_campaign
  ON influencer_attribution(tenant_id, campaign_id, attributed_at DESC);
CREATE INDEX influencer_attribution_shop
  ON influencer_attribution(tenant_id, shop_code, attributed_at DESC);
-- 🔴 索引名**不能**与表名同名（PG 的 relation 名字空间是共享的：
--   索引叫 influencer_attribution_rule 会报 "relation already exists"）。
CREATE INDEX influencer_attribution_by_rule
  ON influencer_attribution(tenant_id, rule_id, attributed_at DESC);
CREATE INDEX influencer_attribution_cooperation
  ON influencer_attribution(tenant_id, cooperation_id);
CREATE INDEX influencer_attribution_accrual
  ON influencer_attribution(tenant_id, accrual_status);

-- ===========================================================================
-- 3. 多租户隔离：两张表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V93 / V146 / V273 / V277 / V284 / V288 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'influencer_attribution_rule', 'influencer_attribution' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：归因可见性随**店铺**收敛（照抄 V288 `influencer_cooperation` 同构）
-- ---------------------------------------------------------------------------
-- 归因规则是**租户级配置事实**，不按店铺收敛 ——
-- 与 V288 `influencer_tag_group` / `influencer_rating_rule` 同一纪律。
CREATE POLICY data_scope ON influencer_attribution AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer_attribution.tenant_id AND s.code=influencer_attribution.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=influencer_attribution.tenant_id AND s.code=influencer_attribution.shop_code
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
  influencer_attribution_rule, influencer_attribution TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V288 同口径）。
REVOKE ALL ON influencer_attribution_rule, influencer_attribution FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：带货归因属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.5.1.1 达人库同族，因此 identity 服务零改动，跨车道回归面 = 0。
