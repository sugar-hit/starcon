-- 15.3.1.1 客户列表：客户标识（按店铺）、来源店铺、首末购买时间、订单数、累计消费、
-- 退款率、客单价、标签、联系方式（脱敏）、风险标记；列表 + 分层统计。
--
-- 蓝图 15 卷 15.3.1.1 的原话：
--   「职责：客户列表：客户标识（按店铺）、来源店铺、首末购买时间、订单数、累计消费、
--     退款率、客单价、标签、联系方式（脱敏）、风险标记。
--     输入：筛选条件（店铺、标签、价值分层、风险）；用户数据范围。
--     输出：客户列表 + 分层统计。
--     关系：上游 = 9 订单；下游 = 15.3.1.2 详情、15.3.1.3 跨店合并结果。
--     **列表默认脱敏显示联系方式**，需要显式授权才完整展示。」
--
-- ============================================================================
-- 零、本文件与既有模块的边界（写入所有者各自独立）
-- ============================================================================
--
-- 本文件是 **crm 数据库的第十九个迁移**（V99 知识库 … V309 工单处理，见目录）。
--
-- 与兄弟模块的边界 —— 三者**不共用任何一张表**，写入所有者也各自独立：
--   * V295（15.3.2.1 客户标签）：回答「这位客户属于哪一类人」（标签定义 / 记录 / 规则）。
--     本文件**只读**它的 `crm_customer_tag_assignment`（筛选与展示标签），
--     **一个字都不写**它的表。
--   * V299（15.3.2.2 接触记录）：回答「我跟这位客户接触过没有」（含退订状态）。
--     本文件**只读**它的 `crm_customer_opt_out`（风险标记里的「已退订」）。
--   * V432（本文件）：回答「租户有哪些客户、各自的情况怎么样」——
--     它是 15.3 客户档案的**主档**（蓝图 15.3 职责：「建立客户主档」）。
--
-- ============================================================================
-- 一、🔴 本模块的客户是「**按来源店铺**的客户」，不是「逻辑客户」
-- ============================================================================
--
-- 蓝图加粗句「客户标识（按店铺）」在本文件里的落点：
--   * 主键身份列是 **(source_shop, customer_ref)**，**不是**单列 customer_ref。
--     同一个客户标识在两个来源店铺下 ⇒ **两行**。
--   * 跨店合并（15.3.1.3 跨店合并）是**另一个模块**的事：它拥有「逻辑客户」这张事实表
--     与合并映射。本文件**不建**逻辑客户主表、**不建**合并映射表、
--     **不提前**做任何合并 —— 那会同时踩两条线：
--       (1) 「每张事实表只有一个写入所有者」；
--       (2) 15.3.1.3 的上游就是本模块，它会自己拥有合并事实表。
--   * 合并交付后，列表「按逻辑客户展示」是**下游增强**（本文件届时被读增强），
--     **不是**本模块开工的前置条件。
--
-- ⚠️ 关于「来源店铺」的诚实说明：
--   上游 9.1.1.1 的订单读投影（`GET /trade/sales-orders/list`）**没有 shop 列**，
--   只有 `order_source`（MANUAL / AMAZON / SHOPIFY / EBAY / TIKTOK_SHOP / ALIBABA_1688）。
--   而真正的店铺目录在 channel 服务（2.3.1.1 `GET /channel/shops`），
--   订单事实里**没有**指向它的列 —— 一个平台可以有多家店，从 order_source 反推 shop 是**猜**。
--   ⇒ 本表的 `source_shop` 记录的是**订单来源口径**（平台级），
--     列名与文档里都写明这一点；等 9.1.2.1 平台单同步把 shop_code 铺开之后，
--     这一列才可以细化到店铺级。**本模块不假装自己已经是店铺级。**
--
-- ============================================================================
-- 二、跨服务纪律（零跨库 SQL / FK / 视图）
-- ============================================================================
--
-- 上游 **9 订单**与 **11 售后**的真值在 **trade** 服务（端口 8085）：
--   * 订单事实：`GET /trade/sales-orders/list`（sales_order + sales_order_party_snapshot）
--   * 售后事实：`GET /trade/aftersales/cases`（aftersales_case）
-- 与本模块**不同服务、不同数据库** ⇒ 只走**授权 HTTP API**，
-- 禁止跨库 SQL / 跨库 FK / 共享视图。
--
-- 联系方式的来源有两处，本表都只存**快照**（不建跨库外键）：
--   * **catalog 3.6.1.1 party 档案**（端口 8082，`GET /catalog/parties/{id}`）——
--     蓝图 15 卷开篇写明「买家主档在 catalog（party customer_profile）」，
--     上游订单里的 `customer_party_id` 指向的就是它。
--   * **人工登记**（本服务的显式写端点）—— 用于修正 / 补齐。
--
-- 🔴 退款单**不带买家维度**，只有 `order_id` ⇒ 归因链路是
--   「售后单 → order_id → 订单 → customer_party_id → 客户」。
--   这条链路**只在服务层做**（一次拉取内建索引），本库**不建任何指向 trade 的外键**。
--
-- ============================================================================
-- 三、三张表
-- ============================================================================
--
--   crm_customer_profile     客户主档行（一个来源店铺下的一个客户 = 一行）
--   crm_customer_amount      按**币种**分开的累计消费与客单价（金额按币种分开，不相加）
--   crm_customer_sync_run    一次「从上游刷新客户档案」的运行记录（可审计）
--
-- 为什么金额要单独一张表：
--   累计消费与客单价是**金额**，而一个客户可能同时有 USD 与 EUR 的订单。
--   「USD 与 EUR 加起来不是任何一个金额」—— 所以金额**按币种分行**，
--   列表上按币种分行展示，筛选金额时**必须**同时给币种。
--
-- ============================================================================
-- 四、🔴 禁浮点与时间纪律
-- ============================================================================
--
--   * 金额：numeric(19,4) + BigDecimal；比率（退款率）：numeric(19,6) + BigDecimal；
--     客单价：numeric(19,4)（= 累计消费 ÷ 订单数，**服务端**算，前端只原样展示）。
--   * 时间：一律 timestamptz + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区**静默偏 −28800 秒**。
--
-- ============================================================================
-- 五、🔴 联系方式默认脱敏
-- ============================================================================
--
-- 本表**同时**存明文与脱敏两列：
--   * `contact_email` / `contact_phone`：**明文**快照，只在调用方**显式授权**
--     （权限码 crm.customers.pii.read）时才允许出现在响应里。
--   * `contact_email_masked` / `contact_phone_masked`：**脱敏**形态，
--     列表默认返回这一对（a***@***.com / 138****1234）。
-- 服务层在**写库之前**就把脱敏形态算好落库（不是每次读的时候现算），
-- 这样脱敏规则改了也不会让历史行显示成另一种形态。
--
-- ============================================================================
-- 六、发布后不可变的强制位置
-- ============================================================================
--
--   1. 客户身份列（source_shop + customer_ref）建后不可改 ——
--      改了之后「这位客户是谁」在历史上会出现两个答案。
--   2. 金额行必须挂在存在的客户行上（触发器校验，不用外键）。
--
-- ⚠️ 为什么用**触发器回表**而不是**数据库外键**：
--   同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）会被外键打破。本文件**不给任何既有表建外键**，本文件自己新建的表之间
--   也**一律用触发器做引用校验**，与 V99 / V220 / V228 / V292 / V295 / V299 同一手法。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 同一口径：角色可能已由运维预置，
-- 因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;
GRANT USAGE ON SCHEMA public TO starcon_app;

-- 运行号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一次刷新客户档案就报权限不足」。
CREATE SEQUENCE crm_customer_sync_run_no_seq START 1;

-- ============================================================================
-- 1. crm_customer_profile —— 客户主档行（按来源店铺的客户标识）
-- ============================================================================
CREATE TABLE crm_customer_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 客户身份（**按来源店铺**，不是逻辑客户）=====
  -- 上游客户标识：9.1.1.1 订单里的 customer_party_id 的字符串形态
  -- （与 15.3.2.1 客户标签的 customer_ref 同一口径，标签才能挂得上）。
  customer_ref varchar(128) NOT NULL CHECK (btrim(customer_ref) <> ''),
  -- 来源店铺：**订单来源口径**（上游 order_source），见文件头第一节的诚实说明。
  source_shop varchar(64) NOT NULL CHECK (btrim(source_shop) <> ''),
  -- 上游逻辑 id（catalog party）。**逻辑引用**，不建跨库外键。
  customer_party_id uuid,

  -- 买家名快照（上游订单的 buyer_name）：改名之后历史订单仍显示下单当时的样子。
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 首末购买时间 =====
  -- 取下单时刻（submittedAt），没有就退到登记时刻（createdAt）。
  first_order_at timestamptz,
  last_order_at timestamptz,

  -- ===== 订单数与退款率 =====
  -- 统计窗口内**已提交**的订单数（分母）。
  order_count integer NOT NULL DEFAULT 0 CHECK (order_count >= 0),
  -- 统计窗口内可归因到本客户的**退货 / 退款**类售后单数（分子）。
  -- 口径照抄 15.3.2.1：只计 RETURN_REFUND 与 REFUND_ONLY 两类，
  -- 换货 / 补发 / 维修**不算退货**（货有问题但没退回来）。
  refund_case_count integer NOT NULL DEFAULT 0 CHECK (refund_case_count >= 0),
  -- 退款率 = 退款单数 ÷ 订单数，scale 6、HALF_UP（照抄 15.3.2.1 的同一口径与舍入）。
  -- **分母为 0 时是 NULL（不可判定），不是 0** —— 没有订单就没有比率，
  -- 报 0% 会被读成「这个人退货率是零」。
  refund_rate numeric(19,6),

  -- ===== 价值分层 =====
  -- 分档口径（服务端算，前端只原样展示）：
  --   VIP   主币种累计消费 ≥ 租户同币种人均累计消费 × 3
  --   HIGH  主币种累计消费 ≥ 租户同币种人均累计消费
  --   NORMAL 有消费但低于基线
  --   LOW   没有任何有效消费（订单全是草稿或金额为 0）
  value_tier varchar(16) NOT NULL DEFAULT 'LOW'
    CHECK (value_tier IN ('VIP','HIGH','NORMAL','LOW')),

  -- ===== 风险标记 =====
  -- NONE                     无风险标记
  -- HIGH_RETURN_RATE         退款率显著高于租户基线（≥ 2 倍，照抄 15.3.2.1 的倍率口径）
  -- OPT_OUT                  已登记生效中的退订（15.3.2.2 crm_customer_opt_out）
  -- HIGH_RETURN_RATE_AND_OPT_OUT  两者同时成立
  -- INSUFFICIENT_SAMPLE      **样本不足，不可判定**（有退款事实但订单数低于最低样本量）
  --                          —— 它与 NONE 是**两件事**：「不可判定」不是「没风险」
  risk_flag varchar(32) NOT NULL DEFAULT 'NONE'
    CHECK (risk_flag IN ('NONE','HIGH_RETURN_RATE','OPT_OUT','HIGH_RETURN_RATE_AND_OPT_OUT','INSUFFICIENT_SAMPLE')),
  -- 风险标记必须带原因（DB CHECK 强制）：没有原因的风险标记没法复核。
  risk_reason varchar(600) NOT NULL DEFAULT '',

  -- ===== 联系方式（**明文 + 脱敏两列**，见文件头第五节）=====
  contact_email varchar(254) NOT NULL DEFAULT '',
  contact_phone varchar(64) NOT NULL DEFAULT '',
  contact_email_masked varchar(254) NOT NULL DEFAULT '',
  contact_phone_masked varchar(64) NOT NULL DEFAULT '',
  -- 联系方式来源：NONE 无 / CATALOG_PARTY catalog 3.6.1.1 档案带回来的 /
  -- MANUAL 人工登记（或人工修正过）。
  contact_source varchar(24) NOT NULL DEFAULT 'NONE'
    CHECK (contact_source IN ('NONE','CATALOG_PARTY','MANUAL')),

  -- ===== 退订状态（**只读** 15.3.2.2 的表，快照到这里）=====
  -- NONE 无退订记录 / ACTIVE 生效中 / REVOKED 已撤销
  opt_out_state varchar(16) NOT NULL DEFAULT 'NONE'
    CHECK (opt_out_state IN ('NONE','ACTIVE','REVOKED')),

  -- ===== 统计窗口与刷新时刻 =====
  window_from timestamptz,
  window_to timestamptz,
  synced_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴 客户身份：**（来源店铺, 客户标识）** 在租户内唯一。
  -- 这正是「按店铺的客户标识」的落点：同一个客户标识在两个来源下是两行。
  UNIQUE (tenant_id, source_shop, customer_ref)
);

-- ============================================================================
-- 2. crm_customer_amount —— 按币种分开的累计消费与客单价
-- ============================================================================
CREATE TABLE crm_customer_amount (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 客户主档行（**逻辑引用**，触发器校验，不用外键 —— 见文件头第六节）。
  customer_id uuid NOT NULL,

  -- ISO 4217 alpha-3（照抄 9.1.1.1 的口径）。
  currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),

  -- 累计消费：统计窗口内该客户该币种的订单金额之和（numeric + BigDecimal，禁浮点）。
  total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
  -- 该币种的订单数（客单价的分母）。
  order_count integer NOT NULL DEFAULT 0 CHECK (order_count >= 0),
  -- 客单价 = 累计消费 ÷ 订单数，scale 4、HALF_UP，**服务端**算（前端不做算术）。
  -- 分母为 0 时是 NULL（不可判定），不是 0。
  avg_order_amount numeric(19,4),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一个客户一个币种一行。
  UNIQUE (tenant_id, customer_id, currency)
);

-- ============================================================================
-- 3. crm_customer_sync_run —— 刷新客户档案的运行记录
-- ============================================================================
CREATE TABLE crm_customer_sync_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- CS-yyyyMMdd-000001
  run_no varchar(32) NOT NULL DEFAULT '',

  -- SUCCEEDED 成功 / FAILED 上游读不到（一条客户档案都不落）
  status varchar(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),

  -- 统计窗口
  window_from timestamptz NOT NULL,
  window_to timestamptz NOT NULL,

  -- 计数（纯计数，无小数位）
  customer_count integer NOT NULL DEFAULT 0 CHECK (customer_count >= 0),
  order_count integer NOT NULL DEFAULT 0 CHECK (order_count >= 0),
  case_count integer NOT NULL DEFAULT 0 CHECK (case_count >= 0),
  -- 归不到客户的售后单数（其订单不在扫描窗口内）：这些单**没有进入任何人的退款率**。
  unattributed_case_count integer NOT NULL DEFAULT 0 CHECK (unattributed_case_count >= 0),
  -- 联系方式补齐情况：取到几个 / 没取到几个。
  contact_resolved_count integer NOT NULL DEFAULT 0 CHECK (contact_resolved_count >= 0),
  contact_unavailable_count integer NOT NULL DEFAULT 0 CHECK (contact_unavailable_count >= 0),
  -- 扫描被截断（翻页到上限）：名单**不是全量**，如实标出来。
  truncated boolean NOT NULL DEFAULT false,

  -- 退款率基线（窗口内退货单数 ÷ 窗口内订单数，scale 6）与价值分层基线快照。
  baseline numeric(19,6),
  -- 失败原因（FAILED 时必填）；联系方式补齐失败的原因也记在这里。
  failure_reason varchar(600) NOT NULL DEFAULT '',
  -- 判定依据快照（JSON）。
  facts jsonb NOT NULL DEFAULT '{}'::jsonb,

  operator_id uuid,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, run_no)
);

-- ============================================================================
-- 4. 索引
-- ============================================================================
-- 列表默认排序：累计消费降序 / 订单数降序 / 客户标识升序（稳定全序）。
CREATE INDEX crm_customer_profile_by_shop
  ON crm_customer_profile(tenant_id, source_shop, customer_ref);

CREATE INDEX crm_customer_profile_by_tier
  ON crm_customer_profile(tenant_id, value_tier, customer_ref);

CREATE INDEX crm_customer_profile_by_risk
  ON crm_customer_profile(tenant_id, risk_flag, customer_ref);

CREATE INDEX crm_customer_profile_by_orders
  ON crm_customer_profile(tenant_id, order_count DESC, customer_ref);

CREATE INDEX crm_customer_profile_by_ref
  ON crm_customer_profile(tenant_id, customer_ref);

CREATE INDEX crm_customer_amount_by_customer
  ON crm_customer_amount(tenant_id, customer_id);

-- 分层统计按币种聚合走这一条。
CREATE INDEX crm_customer_amount_by_currency
  ON crm_customer_amount(tenant_id, currency);

CREATE INDEX crm_customer_sync_run_by_created
  ON crm_customer_sync_run(tenant_id, created_at DESC, id DESC);

-- ============================================================================
-- 5. 触发器（**一律不用外键** —— 理由见文件头第六节）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 5.1 guard_crm_customer_profile_identity · 客户身份列建后不可改
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_profile_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.customer_ref IS DISTINCT FROM OLD.customer_ref THEN
    RAISE EXCEPTION '客户标识建后不可改：改了它会让「这位客户是谁」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.source_shop IS DISTINCT FROM OLD.source_shop THEN
    RAISE EXCEPTION '来源店铺建后不可改：它是「按店铺的客户标识」的另一半，改了它会让同一个人在两个店铺下变成两个人（或反过来）' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_profile_identity_trg
  BEFORE UPDATE ON crm_customer_profile
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_profile_identity();

-- ---------------------------------------------------------------------------
-- 5.2 guard_crm_customer_profile_risk_reason · 风险标记必须带原因
-- ---------------------------------------------------------------------------
-- 没有原因的风险标记没法复核 —— 「这个人为什么被标成风险」必须能回答。
CREATE FUNCTION guard_crm_customer_profile_risk_reason() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.risk_flag <> 'NONE' AND btrim(NEW.risk_reason) = '' THEN
    RAISE EXCEPTION '风险标记 % 必须写明原因：没有原因的风险标记没法复核', NEW.risk_flag USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_profile_risk_reason_trg
  BEFORE INSERT OR UPDATE ON crm_customer_profile
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_profile_risk_reason();

-- ---------------------------------------------------------------------------
-- 5.3 guard_crm_customer_amount_target · 金额行必须挂在存在的客户行上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_amount_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_ref varchar(128); v_shop varchar(64);
BEGIN
  SELECT customer_ref, source_shop INTO v_ref, v_shop FROM crm_customer_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.customer_id;
  IF v_ref IS NULL THEN
    RAISE EXCEPTION '客户行 % 不存在（tenant=%），不能给它挂金额',
      NEW.customer_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_amount_target_trg
  BEFORE INSERT OR UPDATE ON crm_customer_amount
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_amount_target();

-- ---------------------------------------------------------------------------
-- 5.4 guard_crm_customer_amount_consistency · 客单价必须与累计消费 / 订单数自洽
-- ---------------------------------------------------------------------------
-- 客单价是**派生值**：分母为 0 时必须为 NULL（不可判定），
-- 分母不为 0 时必须等于累计消费 ÷ 订单数（scale 4、HALF_UP）。
CREATE FUNCTION guard_crm_customer_amount_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_expected numeric(19,4);
BEGIN
  IF NEW.order_count = 0 THEN
    IF NEW.avg_order_amount IS NOT NULL THEN
      RAISE EXCEPTION '订单数为 % 时客单价必须为空（不可判定），不能报 0', NEW.order_count USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;
  v_expected := round(NEW.total_amount / NEW.order_count, 4);
  IF NEW.avg_order_amount IS NULL OR NEW.avg_order_amount <> v_expected THEN
    RAISE EXCEPTION '客单价必须等于累计消费 ÷ 订单数（scale 4、HALF_UP）：期望 %，实际 %',
      v_expected, NEW.avg_order_amount USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_amount_consistency_trg
  BEFORE INSERT OR UPDATE ON crm_customer_amount
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_amount_consistency();

-- ============================================================================
-- 6. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 同一口径：策略名固定 isolation，
-- USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_customer_profile','crm_customer_amount','crm_customer_sync_run'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_customer_profile, crm_customer_amount, crm_customer_sync_run TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_customer_profile, crm_customer_amount, crm_customer_sync_run TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一次刷新客户档案就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_customer_sync_run_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_customer_sync_run_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 7. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.3.1.2 客户详情**（未交付）：本模块**只提供列表与分层统计**，
--     不提供客户详情视图；详情要的订单 / 售后 / 消息 / 标签 / 接触记录时间线由它自己聚合。
--   * **15.3.1.3 跨店合并**（未交付）：本模块的客户是**按来源店铺**的，
--     逻辑客户与合并映射由 15.3.1.3 拥有（它的上游就是本模块）。
--   * **15.6.2.1 生命周期与流失预警**（未交付）：本模块只提供「首末购买时间与订单数」
--     这两个可复用的维度，预警判定由它自己实现。
--   * **联系方式**：catalog 3.6.1.1 party 档案 + 人工登记两处来源；
--     调用方无权读 catalog 时联系方式留空，并在刷新运行记录里**如实报出**没取到几个。
