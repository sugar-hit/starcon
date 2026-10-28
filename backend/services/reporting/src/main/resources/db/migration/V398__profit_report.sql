-- ===========================================================================
-- V398 · profit_report —— 13.4.1.1 多模式利润报表
-- ===========================================================================
--
-- **本文件做什么**：给 reporting 服务（13 利润与经营分析的唯一归属服务）加**两张投影表**，
-- 让「按经营模式出利润报表」这件事**有地方落事实**：
--
--   reporting_profit_shop_profile —— 店铺档案 + **经营模式**（2.3.2.1 的只读投影）
--   reporting_profit_fact         —— 利润事实（收入 / 成本 / 费用）
--
-- ---------------------------------------------------------------------------
-- 0. 蓝图原文（本迁移要兑现的全部内容）
-- ---------------------------------------------------------------------------
--
--   ### 13.4.1.1 多模式利润报表
--   - **职责**：按经营模式（自发货/全托管/半托管/平台仓）分别出利润报表，
--             支持按天/月、按 MSKU/SKU/SPU/店铺/订单多维度核算。
--   - **输入**：成本数据（12.4）、费用数据（12.3/12.4）、订单收入；模式标记（2.3.2.1）；汇率快照。
--   - **输出**：利润报表（收入 - 各成本费用 = 毛利）+ 多维下钻 + 口径说明。
--   - **关系**：上游 = 12 财务全部；下游 = 1 工作台、5 定价。
--             **不同模式的利润含义完全不同**，混在一张表里会得出错误结论
--             —— 必须在报表维度上区分。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 为什么「经营模式」是一张**档案表**，而不是一个枚举常量
-- ---------------------------------------------------------------------------
--
-- 蓝图的「模式标记（2.3.2.1）」指的是 **channel 的 `shop.operating_mode`**：
-- `SELF_FULFILLED / FULL_CONSIGNMENT / SEMI_CONSIGNMENT / OVERSEAS_WAREHOUSE / LOCAL`。
--
-- ⇒ 模式**不是本模块硬编码的词表**，是**上游店铺主数据的一个字段**。
--   所以这里落一张**档案表**（`reporting_profit_shop_profile`），
--   它的每一行都是「一个店铺 + 它当前的经营模式」，
--   事实行上的 `operating_mode` 是**投影当时**从这张档案抄下来的。
--
-- ⚠️ **历史事实不随店铺改模式而回溯改写**（这是有意的）：
--   六月按自发货记下来的收入，六月把店铺改成全托管之后，它**仍然是六月的自发货**。
--   要改历史，走**重建投影**（清空后按新模式重放）—— 与 reporting 的重建纪元同一纪律。
--
-- ⚠️ **挂不上店铺的订单不丢**：它们的 `operating_mode = 'UNKNOWN'`，
--   在报表里**单列一行「未归属」**，不混进任何真实模式（混进去就是编造）。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 事实表为什么是「长表（一行一个成本项）」而不是「宽表（一行一个维度组合）」
-- ---------------------------------------------------------------------------
--
-- 利润表的每一行要同时给出：收入、六七个成本费用项、毛利。
-- 宽表就得给每个成本项一个列，于是「这个成本项**算不出来**」只能写 NULL ——
-- 而一旦某次刷新只带了三个成本项，另外四个列全是 NULL，
-- 读侧**分不清**「这四项真的是 0」还是「这四项没取到」。
--
-- ⇒ 这里用**长表**：`component` 取值把每一类金额分成**独立的一行**，
--   读侧按 `component` 分组，**缺哪一项就是真的没有那一项**
--   （⇒ 毛利算不出来，返回 null + 一句原因，而不是 0）。
--
--   component 词表（本模块自己的口径，穷尽职责句点名的成本费用项）：
--     REVENUE         订单收入（12.3.1.2 之前的 trade 订单行金额）
--     COGS            商品成本（12.4 成本归集 + 12.4.2.2 分摊结果）
--     PLATFORM_FEE    平台费（12.3.1.2 结算明细 · 佣金）
--     LOGISTICS_FEE   物流费（12.3.1.2 结算明细 · FBA 费用）
--     AD_FEE          广告费（12.3.1.2 结算明细 · 广告）
--     HEAD_FREIGHT    头程费（12.4 归集池 · HEAD_MILE_FEE 类别）
--     OTHER_COST      其它归集成本（人员 / 办公 / 订阅等 12.4 归集池）
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 维度列：全部显式列出，全部 NOT NULL DEFAULT ''
-- ---------------------------------------------------------------------------
--
-- 利润报表要按 MSKU / SKU / SPU / 店铺 / 订单多维度核算，而**每一笔钱归属到哪一层是由上游决定的**：
--
--   | 事实                     | 上游给到的归属            | 表里落到哪些列            |
--   |--------------------------|---------------------------|---------------------------|
--   | 订单行金额                | 订单 + SKU                | order_ref + sku_code      |
--   | 订单级尾差（运费/税）      | 订单                      | order_ref                 |
--   | 结算明细费用              | 店铺 + 订单               | shop_code + order_ref     |
--   | 12.4.2.2 分摊结果        | SHOP / ORDER / SKU / SPU  | 对应的那一列              |
--
-- ⇒ 维度列**全部显式列出**（空串 = 该事实不适用这个维度），不塞进 JSON 列：
--   JSON 列没法建索引，也没法在 SQL 里 GROUP BY。
--
-- ⚠️ **空串而不是 NULL**：PostgreSQL 的 UNIQUE 把 NULL 视为互不相等，
--   「平台级」行（其它维度都为空）会因为 NULL != NULL 插出第二条同样的行，
--   幂等键就不成立（与 V379 的 expense 两张表同一手法）。
--
-- ---------------------------------------------------------------------------
-- 4. 🔴 金额与数量：numeric + char(3)，绝不跨币种相加
-- ---------------------------------------------------------------------------
--
--   * amount   numeric(24,4)  —— 金额（**允许负数**：退款 / 冲回是真实发生的费用变化，
--                                 截成 0 等于把「平台退了这笔钱」写成「平台没收这笔钱」）
--   * quantity numeric(19,6)  —— 数量
--   * currency char(3)        —— 币种（CHECK ^[A-Z]{3}$，**绝不跨币种相加**）
--
-- ---------------------------------------------------------------------------
-- 5. 多租户（RLS isolation）+ 授权
-- ---------------------------------------------------------------------------
--
-- 两张表都是租户级业务表：tenant_id uuid NOT NULL + ENABLE **与 FORCE** RLS
-- + CREATE POLICY isolation + GRANT reporting_app / starcon_app。
-- 与 V379 的两张费用事实表**完全同构**。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_profit_shop_profile —— 店铺档案 + 经营模式（2.3.2.1 的投影）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个店铺在投影时刻的**经营模式**。
--
-- ⚠️ **模式值原样保留上游的词表**（SELF_FULFILLED / FULL_CONSIGNMENT /
--   SEMI_CONSIGNMENT / OVERSEAS_WAREHOUSE / LOCAL），**不另起一套**：
--   两处词表对不上时，报表与上游会各说各话。
--   上游没给模式（空 / 未知）⇒ 'UNKNOWN'，在报表里单列「未归属」。
CREATE TABLE reporting_profit_shop_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 店铺编码（channel 的 `shop.code`，与 trade 平台单的 shop_code 同一套编码）。
  shop_code varchar(64) NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴 **经营模式**：来自 channel 2.3.2.1 的 `shop.operating_mode`。
  --   'UNKNOWN' = 上游没给模式 / 店铺还没建档（**不是**「自发货」）。
  operating_mode varchar(32) NOT NULL DEFAULT 'UNKNOWN'
    CHECK(operating_mode IN
      ('SELF_FULFILLED','FULL_CONSIGNMENT','SEMI_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL','UNKNOWN')),

  source_service varchar(32) NOT NULL DEFAULT 'channel',
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个店铺只有一条档案（重投影即覆盖）。
  UNIQUE(tenant_id, shop_code)
);

-- 按模式取店铺用：租户 + 模式。
CREATE INDEX reporting_profit_shop_profile_by_mode
  ON reporting_profit_shop_profile(tenant_id, operating_mode, shop_code);

-- ---------------------------------------------------------------------------
-- 2. reporting_profit_fact —— 利润事实（收入 / 成本 / 费用）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个成本项 / 某种归属维度 / 某个期间」的**一个金额**。
--
-- ⚠️ **只追加投影，随时可重建**：它由上游事实投影而来，清空后重放同一批上游事实即可回到同一状态。
CREATE TABLE reporting_profit_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 🔴 **经营模式**：投影时刻店铺的经营模式（2.3.2.1）。
  --   'UNKNOWN' = 这笔钱挂不上店铺 ⇒ **不混进任何真实模式**，报表里单列「未归属」。
  operating_mode varchar(32) NOT NULL DEFAULT 'UNKNOWN'
    CHECK(operating_mode IN
      ('SELF_FULFILLED','FULL_CONSIGNMENT','SEMI_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL','UNKNOWN')),

  -- 成本项（长表：每个成本项独立成行，缺项即「算不出来」）。
  component varchar(32) NOT NULL
    CHECK(component IN
      ('REVENUE','COGS','PLATFORM_FEE','LOGISTICS_FEE','AD_FEE','HEAD_FREIGHT','OTHER_COST')),

  -- ===== 归属维度（空串 = 该事实不适用这个维度）=====
  shop_code varchar(64) NOT NULL DEFAULT '',
  sku_code  varchar(64) NOT NULL DEFAULT '',
  spu_code  varchar(64) NOT NULL DEFAULT '',
  msku_code varchar(160) NOT NULL DEFAULT '',
  order_ref varchar(64) NOT NULL DEFAULT '',

  -- 期间（逐日事实两列相同；月度分摊池 period_start = 当月 1 号、period_end = 当月末）。
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- 币种（**不跨币种相加**：仓库铁律）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 金额（**允许负数**：退款 / 冲回是真实的费用变化）。
  amount numeric(24,4) NOT NULL,
  -- 数量（收入行是销量；成本行留 0）。
  quantity numeric(19,6) NOT NULL DEFAULT 0,

  -- 来源服务与上游行标识（幂等 upsert 的另一半键）。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',

  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 期间不能倒过来。
  CHECK(period_end >= period_start)
);

-- 幂等键：同一 (租户, 模式, 成本项, 维度, 期间, 币种, 来源服务, 上游行) 只有一条事实。
CREATE UNIQUE INDEX reporting_profit_fact_identity
  ON reporting_profit_fact(
    tenant_id, operating_mode, component,
    shop_code, sku_code, spu_code, msku_code, order_ref,
    period_start, period_end, currency, source_service, source_reference);

-- 按期间聚合用：租户 + 成本项 + 期间 + 币种。
CREATE INDEX reporting_profit_fact_by_period
  ON reporting_profit_fact(tenant_id, component, period_start, currency);
-- 按维度钻选用：租户 + 模式 + 成本项 + 店铺 / SKU / 订单。
CREATE INDEX reporting_profit_fact_by_scope
  ON reporting_profit_fact(tenant_id, operating_mode, component, shop_code, sku_code, order_ref, period_start);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_profit_shop_profile','reporting_profit_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_profit_shop_profile, reporting_profit_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_profit_shop_profile, reporting_profit_fact TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.4.1.2 SKU 利润**：读同一批事实（`reporting_profit_fact`），
--     按 SKU 出 SKU 级利润 —— 本表**提供事实**，口径归它。
--   * **13.4.2.1 成本结构**：按同一批 `component` 算占比 —— 本表不提供占比口径。
--   * **13.4.3.1 利润预警**：毛利与毛利率是预警的输入 —— 阈值配置不在本模块。
--   * **1 工作台 / 5 定价**：读本模块的报表端点（下游调用，本模块不写它们的表）。
