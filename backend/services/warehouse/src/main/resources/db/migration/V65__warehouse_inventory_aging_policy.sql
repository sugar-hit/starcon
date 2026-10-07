-- 7.6.1.1 库龄与呆滞：库龄阈值策略（逐 SKU 配置）。
--
-- ---------------------------------------------------------------------------
-- 为什么本迁移**不**建「库龄结果表」
-- ---------------------------------------------------------------------------
-- 库龄 = 今天 - 入库日，是一个**随日历每天变化**的派生量。
-- 落表意味着每天要么重算全量、要么承认表里存的是昨天的库龄 ——
-- 后者会让「库龄 90+ 天」这种数字悄悄停在某个历史时刻，
-- 而呆滞处置恰恰要求这个数字是今天的。所以库龄一律**查询时从事实层算**：
--   stock_bucket（批次粒度事实）+ inventory_lot.received_on（真实入库业务日）
-- 详见 InventoryAgingService 的库龄口径说明。
--
-- ---------------------------------------------------------------------------
-- 阈值为什么是表而不是常量（7.6.1.1 职责：识别滞销/呆滞并给处置建议）
-- ---------------------------------------------------------------------------
-- 蓝图把「品类特性」列为输入，而品类特性是租户自己的经营事实：
-- 快消 60 天不动就该清仓，家具三年不动才算呆滞。写死一个全局常量等于
-- 对所有品类说同一句话，那句话必然对大部分品类是错的。
--
-- 与 7.3.1.2 的 inventory_expiry_policy 同一套写法（逐 SKU 一行 + 兜底默认值）：
--   配了用配的，没配用 DEFAULT_* 并在响应里标 source=DEFAULT ——
--   界面上「配成 60 天」与「没人配」必须分得开，否则用户会以为系统算错了。
--
-- **刻意不建租户级（sku_id 为空）默认行**：一个 SKU 查两处配置来源
-- （自身策略 + 租户默认）会让「这个数字是谁定的」变成三态。这里只有一态。
--
-- ---------------------------------------------------------------------------
-- 「占用资金」在本迁移里**刻意缺席**
-- ---------------------------------------------------------------------------
-- 蓝图输出里有「占用资金估算」，并强调「呆滞占用的资金是最容易被忽略的利润黑洞」。
-- 但本服务的库存事实层（V1 / V2 / V57）**没有任何成本列**：
--   stock_bucket 只有 on_hand / reserved / version；
--   inventory_lot 有 produced_on / received_on / expires_on 等业务日，没有单价；
--   inventory_entry 是数量流水，同样不含金额。
-- 已 grep 全仓确认 warehouse 服务不存在 unit_cost / avg_cost / cost_amount /
-- unit_price / moving_average 任何一列。
--
-- 成本口径属 finance 卷（移动加权 / 批次计价 / 币种），而跨服务禁止读对方表
-- （AGENTS.md 模块边界纪律）。所以本模块**不编一个成本价**：
-- 库龄报表只出数量口径，资金占用字段如实返回 available=false + 原因。
-- 假成本比缺成本危险得多 —— 一个猜出来的金额会被当真去定清仓价。
-- finance 的成本接口落地后，只需在本服务加一个 Port 注入，本表结构无需改动。

-- ===========================================================================
-- 库龄阈值策略
-- ===========================================================================
CREATE TABLE inventory_aging_policy (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- SKU 是 catalog 的逻辑 ID：不建外键（同 V2 的 inventory_lot.sku_id 纪律）。
  sku_id uuid NOT NULL,
  -- 滞销线：库龄 >= 该天数的即计入滞销（60 天）。
  slow_moving_days integer NOT NULL DEFAULT 60
    CHECK(slow_moving_days>=0 AND slow_moving_days<=3650),
  -- 呆滞线：库龄 >= 该天数的即计入呆滞（90 天）。
  obsolete_days integer NOT NULL DEFAULT 90
    CHECK(obsolete_days>=0 AND obsolete_days<=3650),
  -- 呆滞线必须不早于滞销线：否则同一条 bucket 同时命中两档，
  -- 而「滞销」是「呆滞」的子集这种自相矛盾的口径会让报表自相矛盾。
  CHECK(obsolete_days>=slow_moving_days),
  -- 阈值本身不含开关语义的时刻：enabled 关闭时该 SKU 退回兜底默认值，
  -- 与 7.3.1.2 的 enabled 同一读法（关着不是 0 天，是「没人配」）。
  enabled boolean NOT NULL DEFAULT true,
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个 SKU 只允许一条策略：阈值来源必须唯一，否则「这个数字是谁定的」无解。
  UNIQUE(tenant_id,sku_id),
  CHECK(btrim(notes)<>'' OR notes='')
);
CREATE INDEX inventory_aging_policy_enabled ON inventory_aging_policy(tenant_id, sku_id) WHERE enabled;

-- ===========================================================================
-- 多租户：RLS + 授权（与 V60 同一套写法）
-- ===========================================================================
ALTER TABLE inventory_aging_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_aging_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON inventory_aging_policy
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE ON inventory_aging_policy TO warehouse_app;
-- 停用不是删除：连运行角色都不给 DELETE，兜底在权限层。
REVOKE DELETE ON inventory_aging_policy FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V2 同口径）。
REVOKE ALL ON inventory_aging_policy FROM starcon_app;
