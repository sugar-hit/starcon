-- ===========================================================================
-- V400 · sku_profit —— 13.4.1.2 SKU 利润
-- ===========================================================================
--
-- **本文件做什么**：给 reporting 服务加**一张投影表**，让「SKU 级净利」这件事有地方落
-- **退款损失**这一项事实。
--
--   reporting_sku_profit_refund_fact —— 退款损失事实（**本模块自己拥有的那一笔钱**）
--
-- ---------------------------------------------------------------------------
-- 0. 蓝图原文（本迁移要兑现的全部内容）
-- ---------------------------------------------------------------------------
--
--   ### 13.4.1.2 SKU 利润
--   - **职责**：SKU 级利润明细：单品收入、采购成本、头程、平台费、物流费、广告费、
--             **退款损失**、净利，支持查看构成瀑布与订单级明细钻取。
--   - **输入**：SKU 维度；时间范围；成本分摊结果。
--   - **输出**：SKU 利润表 + 瀑布图 + 订单明细钻取。
--   - **关系**：上游 = 13.4.1.1、12.4.2.2；下游 = 5 定价、6 采购。
--             **SKU 利润是选品、定价、采购谈判的共同依据**，口径一旦有争议三方都会不认账
--             —— 因此口径必须在报表上写明。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 本模块**只读** 13.4.1.1 的投影表，不新建第二张存同一笔钱的表
-- ---------------------------------------------------------------------------
--
-- 单品收入 / 采购成本 / 头程 / 平台费 / 物流费 / 广告费 —— 这六项事实**已经在**
-- `reporting_profit_fact`（V398 建表）里了，那张表带 `sku_code / spu_code / msku_code /
-- order_ref` 四个 SKU 级维度列。
--
-- ⇒ 本模块**不复制**它们，也**不改**那张表：**SKU 利润就是同一张投影表按 SKU 维度再读一次**
--   （口径归本模块，事实归 13.4.1.1）。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 为什么退款损失要**单独一张表**，而不是扩 `reporting_profit_fact.component` 的值域
-- ---------------------------------------------------------------------------
--
-- 蓝图职责句点名「退款损失」，而 V398 的 `component` CHECK 词表里**没有**它。
-- 看上去最省事的做法是 `ALTER TABLE ... DROP CONSTRAINT ... ADD CONSTRAINT` 把值域放宽
-- （新值域是旧值域的超集，既有行仍满足，确实不会破坏别人的 INSERT）。**本模块刻意不做**，
-- 理由有两条：
--
--   ① **扩值域会让 13.4.1.1 的毛利口径静默漏掉退款**：
--      13.4.1.1 的成本项词表（`ReportingProfitReportModels.Component`）里没有 REFUND_LOSS，
--      它按自己的词表逐项累加成本合计 —— 退款事实落进那张表之后，13.4.1.1 的毛利会
--      **原样不算这笔钱**（不是报错，是算错），而 13.4.1.1 的毛利是 5 定价的下游输入。
--      两套口径一旦不一致，正是蓝图关系句警告的「三方都不认账」。
--
--   ② **一笔钱只有一个写入所有者**：退款损失这笔钱在 `reporting_profit_fact` 里
--      **压根不存在**（13.4.1.1 的职责句没有「退款损失」，它的四路上游也不投退款）。
--      本模块是这笔钱在本仓的**唯一写入所有者** —— 单独一张表，正是「一件事一个归属」的落点。
--
-- ⚠️ **代价要写清楚**：退款损失与其它六项成本**不在同一张表里** ⇒ 本模块的净利要
--   **跨两张表读**（本表 + `reporting_profit_fact`），报表口径说明里必须写明这一点。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 退款损失的口径（谁的钱、什么时候算、挂到哪个 SKU）
-- ---------------------------------------------------------------------------
--
-- **钱的来源**：9 订单 / 11 售后的**退款申请单**（trade `refund_application`）——
--   只有 `application_status = 'SUCCEEDED'` 的才**算损失**（钱真的退出去了）；
--   DRAFT / PENDING_APPROVAL / EXECUTING 都**不算**（申请了不等于退了）。
--
-- **挂到哪个 SKU**：退款申请单本身挂在**订单**上（不挂 SKU）⇒ 需要售后单的**涉及行**
--   （`aftersales_case_line`）才有 SKU。**本模块只在「退款金额与该售后单涉及行的申请金额
--   之和逐字相等」时才按行分摊**（那不是分摊，是同一笔钱的行级拆分）；
--   **对不上就不挂到任何 SKU**（部分退款 / 上游改了金额）—— 摊下去就是编造，
--   宁可让那一格显示「—（不可归属）」并如实报数量。
--
-- **币种**：一律 `char(3)` + `^[A-Z]{3}$`，**绝不跨币种相加**。
--
-- ---------------------------------------------------------------------------
-- 4. 多租户（RLS isolation）+ 授权
-- ---------------------------------------------------------------------------
--
-- 与 V398 / V399 **完全同构**：tenant_id uuid NOT NULL + ENABLE **与 FORCE** RLS
-- + CREATE POLICY isolation + GRANT reporting_app / starcon_app。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_sku_profit_refund_fact —— 退款损失事实
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个 SKU 在某个订单上的一笔已成功退款的金额」。
--
-- ⚠️ **只追加投影，随时可重建**：它由上游退款事实投影而来，清空后重放同一批上游事实即可回到同一状态。
CREATE TABLE reporting_sku_profit_refund_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== SKU 级归属（SKU 是本模块的主维度）=====
  sku_code varchar(64) NOT NULL,

  -- 🔴 **经营模式**（2.3.2.1 的 `shop.operating_mode`，投影时刻抄下来）。
  --   'UNKNOWN' = 这笔退款挂不上店铺（平台单回挂不上 / 店铺还没建档）——
  --   **不是**「自发货」，在报表里单列「未归属」，不混进任何真实模式。
  operating_mode varchar(32) NOT NULL DEFAULT 'UNKNOWN'
    CHECK(operating_mode IN
      ('SELF_FULFILLED','FULL_CONSIGNMENT','SEMI_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL','UNKNOWN')),

  -- 上游锚点（**指针不是副本**：真值仍在 trade）。
  --   order_ref     —— 退款申请单挂的那张订单（订单号）
  --   case_ref      —— 售后单号（退款申请单挂在售后单上）
  --   application_ref —— 退款申请单号
  order_ref varchar(64) NOT NULL DEFAULT '',
  case_ref varchar(64) NOT NULL DEFAULT '',
  application_ref varchar(64) NOT NULL DEFAULT '',

  -- 期间（逐日事实两列相同；月度事实起止不同）。
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- 币种（**不跨币种相加**：仓库铁律）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 退款损失金额（**正数 = 损失**：钱退出去了）。
  -- ⚠️ 允许负数：退款冲回（平台把钱退回来了）是真实发生的费用变化，截成 0 等于说谎。
  amount numeric(24,4) NOT NULL,

  -- 数量（退款件数；上游没给就留 0）。
  quantity numeric(19,6) NOT NULL DEFAULT 0,

  -- 来源服务与上游行标识（幂等 upsert 的另一半键）。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',

  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 期间不能倒过来。
  CHECK(period_end >= period_start),
  -- SKU 必须挂得上：空串意味着「不知道是哪个 SKU 的退款」—— 那就等于没归属。
  CHECK(sku_code <> '')
);

-- 幂等键：同一 (租户, SKU, 模式, 订单, 售后单, 退款申请单, 期间, 币种, 来源服务, 上游行) 只有一条事实。
CREATE UNIQUE INDEX reporting_sku_profit_refund_fact_identity
  ON reporting_sku_profit_refund_fact(
    tenant_id, sku_code, operating_mode, order_ref, case_ref, application_ref,
    period_start, period_end, currency, source_service, source_reference);

-- 按期间聚合用：租户 + SKU + 期间 + 币种。
CREATE INDEX reporting_sku_profit_refund_fact_by_period
  ON reporting_sku_profit_refund_fact(tenant_id, sku_code, period_start, currency);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_sku_profit_refund_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_sku_profit_refund_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_sku_profit_refund_fact TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：口径归属（不是本模块的缺口，是本模块的**边界**）
-- ===========================================================================
--
--   * **单品收入 / 采购成本 / 头程 / 平台费 / 物流费 / 广告费**：读 `reporting_profit_fact`
--     （13.4.1.1 的事实）。SKU 维度上的成本**只取上游已经分摊到 SKU 的那些行** ——
--     本模块**不重算分摊**（上游 12.4.2.2 分摊到店铺 / 订单时，SKU 维度就没有这笔成本；
--     那一格显示「—（不可归属）」，**不是 0**）。
--   * **退款损失**：本表（13.4.1.2 拥有的一笔钱），口径见第三节。
--   * **其它成本（OTHER_COST）**：不在 13.4.1.2 的职责句里，归 13.4.1.1 —— 本模块不报它。
