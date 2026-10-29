-- ===========================================================================
-- V401 · profit_alert —— 13.4.3.1 利润预警
-- ===========================================================================
--
-- **本文件做什么**：给 reporting 服务（13 利润与经营分析的唯一归属服务）加**两张表**：
--
--   reporting_profit_alert_rule         —— 预警阈值规则（**租户自己的口径**，可配置）
--   reporting_profit_alert_notification —— 通知投递记录（**可观测的通知**，不是「假装发过」）
--
-- ⚠️ **本迁移不建「预警事实表」**：预警是**从利润投影算出来的读模型**，
--   事实来源是 13.4.1.1 已经落好的 `reporting_profit_fact`（V398）。
--   再建一张存同一笔钱的表就会出现**第二个真相** —— 上游改一次，两个表对不上。
--
-- ---------------------------------------------------------------------------
-- 0. 蓝图原文（本迁移要兑现的全部内容）
-- ---------------------------------------------------------------------------
--
--   ### 13.4.3.1 利润预警
--   - **职责**：利润异常预警：SKU 毛利转负、店铺利润率骤降、费用占比异常、
--             广告亏损超标、订单级亏损订单占比突增。
--   - **输入**：利润指标投影；阈值规则配置。
--   - **输出**：预警清单 + 通知 + 处置入口。
--   - **关系**：上游 = 13.4.1.1；下游 = 1 工作台、5 调价、14 广告。
--             **亏损订单占比是广告投放失控的早期信号**，比 ROAS 下降更早出现。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 为什么「预警」是**读模型**而不是一张事实表
-- ---------------------------------------------------------------------------
--
-- 蓝图第 13 章的总纪律：「**不产生业务事实、不做业务写入。报表是投影，可从事件重建；
-- 任何报表数字与业务系统冲突时，以业务系统为准。**」
--
-- ⇒ 预警是「利润指标投影 + 阈值规则配置」的**确定性计算**：
--   同一批 `reporting_profit_fact` + 同一批规则 ⇒ 同一份预警清单。
--   所以它**不需要**一张自己的事实表，只需要**两张配置 / 记录表**：
--
--   * `reporting_profit_alert_rule`         —— 阈值（租户可改，不写死）
--   * `reporting_profit_alert_notification` —— 通知投递记录（谁在什么时候被通知过、确认过没有）
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 五类预警与它们的阈值口径（**阈值不写死在代码里**）
-- ---------------------------------------------------------------------------
--
--   | 预警类型                   | 归属维度 | 阈值口径        | 阈值单位      | 默认阈值 |
--   |----------------------------|----------|-----------------|---------------|----------|
--   | SKU_NEGATIVE_GROSS_PROFIT  | SKU      | FIXED（毛利率线）| PERCENT       | 0        |
--   | SHOP_MARGIN_DROP           | SHOP     | FIXED / BASELINE| PERCENT / POINTS | 10    |
--   | FEE_RATIO_ANOMALY          | SHOP     | FIXED / BASELINE| PERCENT / POINTS | 80    |
--   | AD_LOSS_EXCEEDED           | SHOP     | FIXED（倍数）    | MULTIPLE      | 1        |
--   | LOSS_ORDER_RATIO_SPIKE     | SHOP     | FIXED / BASELINE| PERCENT / POINTS | 10    |
--
-- ⚠️ **阈值必须能按租户调**：不同品类、不同店铺的波动幅度差一个数量级，
--   写死的百分比对上一条平稳的品类和一条季节性强烈的品类含义完全不同。
--   ⇒ 阈值落成**一张租户级规则表**；服务层只播种一份**默认**规则（可在接口上改）。
--
-- ⚠️ **BASELINE 口径的阈值单位是「百分点」**（不是百分数）：
--   「骤降 10 个百分点」与「骤降 10%」是两回事，混着写会让阈值失去意义。
--   ⇒ 单位由 (预警类型 × 阈值口径) 唯一决定，**由服务层派生、不落库**
--   （落一份副本就会和口径漂移）。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 阈值取值范围写进 CHECK：让「不可能的阈值」在数据库层就被挡住
-- ---------------------------------------------------------------------------
--
--   * `SKU_NEGATIVE_GROSS_PROFIT` 的阈值是**毛利率上限**，语义上必须 **≤ 0**
--     （阈值 > 0 会变成「毛利率低于正数也报」，那不是「毛利转负」）；
--   * `AD_LOSS_EXCEEDED` 的阈值是**倍数**（广告费 ÷ 毛利），必须 **≥ 0**；
--   * 其余类型的阈值是**幅度**，必须 **≥ 0**。
--
-- ⚠️ 方向是**逐类型固定**的，不是每行随便选：
--   FIXED 口径下的「跌破线」类型（SKU 毛利转负 / 店铺利润率骤降）只能是 FIXED，
--   因为「相对基线的倍数」对它们没有意义。
--
-- ---------------------------------------------------------------------------
-- 4. 🔴 通知：做成**可观测的投递记录**，而不是「假装发过」
-- ---------------------------------------------------------------------------
--
-- 蓝图输出句点名了「通知」。真发（webhook / 邮件）需要地址与凭据 ——
-- AGENTS.md 明文规定「**任何 secret 一律不入仓库**」，所以本模块**不硬编码**
-- 任何 SMTP / webhook 地址或 token。
--
-- ⇒ 通知落成**投递记录**：命中的预警登记成一条记录（可查、可确认、可忽略），
--   通道只有 `CONSOLE`（站内通知）。将来接真通道时，
--   **投递端点写在这张表上**（多一个 channel 值），而不是改写历史记录。
--
-- ⚠️ **幂等键 = 预警指纹**（预警类型 + 归属维度 + 取值 + 币种 + 窗口）：
--   同一个窗口反复点「生成通知」不会产生第二条记录 —— 通知轰炸比没有通知更糟。
--
-- ---------------------------------------------------------------------------
-- 5. 多租户（RLS isolation）+ 授权
-- ---------------------------------------------------------------------------
--
-- 两张表都是租户级表：tenant_id uuid NOT NULL + ENABLE **与 FORCE** RLS
-- + CREATE POLICY isolation + GRANT reporting_app / starcon_app。
-- 与 V398 / V399 **完全同构**。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_profit_alert_rule —— 预警阈值规则（租户自己的口径）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某一类预警的判定阈值」。这张表存在的唯一理由是：**阈值不写死在代码里**。
CREATE TABLE reporting_profit_alert_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 预警类型（**穷尽蓝图职责句点名的五类**）。
  alert_type text NOT NULL
    CHECK(alert_type IN
      ('SKU_NEGATIVE_GROSS_PROFIT','SHOP_MARGIN_DROP','FEE_RATIO_ANOMALY',
       'AD_LOSS_EXCEEDED','LOSS_ORDER_RATIO_SPIKE')),

  -- 阈值口径：FIXED = 固定阈值线；BASELINE = 相对历史基线的变化量（百分点）。
  threshold_mode text NOT NULL CHECK(threshold_mode IN ('FIXED','BASELINE')),

  -- 阈值取值。**单位由 (预警类型, 阈值口径) 派生**（见文件头第 2 节），不在这里存副本。
  threshold_value numeric(19,6) NOT NULL,

  -- 最少历史期数：基线要**前 N 期**同口径窗口才成立（历史不够就判不了，不退化成「没异常」）。
  min_history_periods int NOT NULL CHECK(min_history_periods >= 1),

  -- 命中后的严重度（租户自己定；默认在服务层播种）。
  severity text NOT NULL CHECK(severity IN ('INFO','WARNING','CRITICAL')),

  enabled boolean NOT NULL DEFAULT true,

  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个预警类型只有一条规则。
  UNIQUE(tenant_id, alert_type),

  -- 🔴 **阈值取值范围写进 CHECK**：不可能的阈值在数据库层就被挡住，
  --   而不是等到某个租户把它改成一个语义上不存在的值之后才在页面上被误读。
  CHECK(
    (alert_type = 'SKU_NEGATIVE_GROSS_PROFIT'
       AND threshold_mode = 'FIXED'
       AND threshold_value <= 0)
    OR (alert_type = 'AD_LOSS_EXCEEDED'
       AND threshold_mode = 'FIXED'
       AND threshold_value >= 0)
    OR (alert_type IN ('SHOP_MARGIN_DROP','FEE_RATIO_ANOMALY','LOSS_ORDER_RATIO_SPIKE')
       AND threshold_value >= 0)
  )
);

-- 按类型取规则用（唯一索引已覆盖租户 + 类型；这里补一条显式的读取索引）。
CREATE INDEX reporting_profit_alert_rule_by_type
  ON reporting_profit_alert_rule(tenant_id, alert_type, enabled);

-- ---------------------------------------------------------------------------
-- 2. reporting_profit_alert_notification —— 通知投递记录
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某一条预警被通知过一次」。这是**本模块自己写的记录**（不是业务事实），
-- 但它仍然带 tenant_id + RLS —— 多租户隔离铁律不因为「只是记录」而放松。
CREATE TABLE reporting_profit_alert_notification (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 🔴 **预警指纹**（幂等键）：预警类型 + 归属维度 + 取值 + 币种 + 窗口。
  --   同一窗口反复登记同一条预警 ⇒ 只有一条记录（通知轰炸比没有通知更糟）。
  alert_fingerprint text NOT NULL,

  alert_type text NOT NULL
    CHECK(alert_type IN
      ('SKU_NEGATIVE_GROSS_PROFIT','SHOP_MARGIN_DROP','FEE_RATIO_ANOMALY',
       'AD_LOSS_EXCEEDED','LOSS_ORDER_RATIO_SPIKE')),
  severity text NOT NULL CHECK(severity IN ('INFO','WARNING','CRITICAL')),

  -- 归属维度与取值（SKU 预警 ⇒ SKU；其余四类 ⇒ 店铺）。
  scope_type text NOT NULL CHECK(scope_type IN ('SKU','SHOP')),
  scope_key text NOT NULL,

  -- 币种（**不跨币种相加**：仓库铁律）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 通知通道：目前只有站内（CONSOLE）。真发通道将来在这里加值，**不改写历史记录**。
  channel text NOT NULL DEFAULT 'CONSOLE' CHECK(channel IN ('CONSOLE')),

  -- 状态机：PENDING → ACKNOWLEDGED（已知悉）/ DISMISSED（误报，忽略）。**终态不可复活**。
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACKNOWLEDGED','DISMISSED')),

  -- 快照：通知发出那一刻的观测值与阈值（不重算，改了阈值也不影响历史记录的口径）。
  title text NOT NULL,
  detail text NOT NULL,
  observed_value numeric(24,4),
  observed_unit text NOT NULL CHECK(observed_unit IN ('PERCENT','PERCENTAGE_POINTS','MULTIPLE')),
  threshold_value numeric(19,6) NOT NULL,

  -- 预警窗口（事实按 period_start 落桶）。
  window_start date NOT NULL,
  window_end date NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  acknowledged_at timestamptz,
  acknowledged_by uuid,

  UNIQUE(tenant_id, id),
  -- 幂等键：同一租户、同一窗口、同一条预警 ⇒ 只有一条通知记录。
  UNIQUE(tenant_id, alert_fingerprint),

  -- 终态必须留下确认时刻与确认人（**终态不可复活**，所以这两列不能留空）。
  CHECK(
    (status = 'PENDING' AND acknowledged_at IS NULL AND acknowledged_by IS NULL)
    OR (status IN ('ACKNOWLEDGED','DISMISSED') AND acknowledged_at IS NOT NULL)
  )
);

-- 通知台账读取用：租户 + 状态 + 创建时刻倒序。
CREATE INDEX reporting_profit_alert_notification_by_status
  ON reporting_profit_alert_notification(tenant_id, status, created_at DESC);

-- ===========================================================================
-- 第二节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_profit_alert_rule','reporting_profit_alert_notification'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_profit_alert_rule, reporting_profit_alert_notification TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_profit_alert_rule, reporting_profit_alert_notification TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第三节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **1 工作台**：读 `GET /reporting/profit-alert/alerts` 的预警清单（下游调用，本模块不写它的表）。
--   * **5 调价 / 14 广告**：处置入口由本模块给出**定位链接**，真正的动作在它们那边做。
--   * **13.4.1.1 多模式利润报表**：本模块**只读**它的 `reporting_profit_fact` 投影，
--     **不重算费率、不重算分摊、不拉实时汇率**。
