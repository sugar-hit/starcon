-- 5.4.1.1 调价单：发起价格变更（选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批），
-- 记录变更前后值；生效后产出「待回写」事实给 5.4.2.1 平台价格同步。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：发起价格变更：选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若启用）；记录变更前后值。
--   **输入**：调价请求；`expected_version`；审批要求（17 组织的审批规则）。
--   **输出**：调价单（草稿/待审/已批/已执行/已驳回）；生效后触发平台回写。
--   **关系**：上游 = 5.3.1.1、5.3.2.2；下游 = 5.4.2.1、17 审批。
--   **调价是受控动作**：大额或大幅调价走审批，普通调整可配置免审。
--
-- ===========================================================================
-- 第一节：本模块与相邻已交付模块的分工（先把边界钉死，再建表）
-- ===========================================================================
--
-- 本模块**不重写**任何一套已有算术，三个上游各司其职：
--
--   * **5.3.1.1 利润测算器**（V227，本服务内）：**改价前后的利润额与毛利率**全部由它的引擎
--     （ProfitCalculatorService.calculate）算出来 —— 本模块**零个测算算法**。
--   * **5.3.2.2 价格规则**（V236，本服务内）：自动调价规则的定义与命中，属它的事。
--     本模块是**人工调价单**（5.3.2.2 的产出是「建议调价列表」，不是调价单）。
--   * **5.3.2.1 定价模板**（V231，本服务内）：建议价的来源。本模块的**新价由人输入**
--     （蓝图的职责句明写「输入新旧价」），所以本模块**不调用**模板。
--
-- 🔴 **与 5.4.1.2 批量调价的边界（两张表绝不合并）**：
--   V237 的 `batch_price_adjustment` / `batch_price_adjustment_item` 是**批量**调价的
--   写入所有者（一次对一批 listing 统一改价 + 影响预览报告）。本模块是**单张**调价单
--   （一个 SKU 在一个店铺上的一次价格变更 + 审批 + 版本），两者语义不同、生命周期不同、
--   审批口径不同 ⇒ **另起自己的三张表**，一个字符都不改 V237 的结构，也不再往它的表里写。
--
-- 🔴 **审批（17 组织）不是本表的写入所有者**：审批规则、审批实例、审批结论都属 identity 服务
--   （17.4.1.2 审批规则 / 17.4.2.1 审批收件箱；单据类型 PRICE_CHANGE 出厂注册于 V83）。
--   本表只**记**本模块自己的那几列镜像：这次评估的结论（decision / source / 命中的规则 /
--   目标流程）、投出去的实例号、以及拉回来的结论时间 —— 事实本身仍在 identity 库里，
--   本表**不复制**审批实例，更不**自己发明**审批流。
--
-- ===========================================================================
-- 第二节：🔴 本模块**不改**任何既有表（一条 ALTER 都没有）
-- ===========================================================================
--
-- 本迁移因此：
--   * **零个 FOREIGN KEY**（引用校验一律用触发器，与 V230 / V231 / V237 同一手法 ——
--     外键会参与 TRUNCATE 的依赖图，上一批（batch-37）就因此打断了同服务既有测试）；
--   * **零个 ALTER / UPDATE 既有表**（连索引都不加到别人的表上）；
--   * **不写** 5.5.2.1 的价格观测台账（price_anomaly_observation，那张表有唯一的写入所有者：
--     5.5.2.1 的 PriceAnomalyService）。本模块只**读**它取当前价；
--   * **不写** 5.4.1.2 的批量调价表（同上：唯一的写入所有者是 5.4.1.2）。
--
-- ===========================================================================
-- 第三节：三张表，职责不重叠
-- ===========================================================================
--
--   | 表                              | 一行是什么                                       | 写入 |
--   |---------------------------------|--------------------------------------------------|------|
--   | price_adjustment_order          | 一张调价单（单头：状态机 + 审批 + 口径 + 版本）   | 追加 + 状态流转 |
--   | price_adjustment_order_item     | 这张单子里那一条价格变更（SKU × 店铺，前后值）     | 追加（不可变）|
--   | price_adjustment_writeback      | 生效后待回写平台的一条事实（给 5.4.2.1）         | 追加 + 回执   |
--
-- 🔴 **一张调价单 = 一个 SKU 在一个店铺上的一次价格变更**（蓝图的职责句「选择 SKU 与目标店铺、
--   输入新旧价」是单数动作）。所以 `price_adjustment_order_item` 上有
--   UNIQUE(tenant_id, order_id) —— **一条单子只有一条明细**，这条不变量写死在 DB 上。
--   「一批 listing 统一改价」是 5.4.1.2 的事，它的表在 V237。
--
-- ===========================================================================
-- 第四节：状态机（蓝图的输出句逐字对应）
-- ===========================================================================
--
--   | status            | 含义         | 到达方式                                        |
--   |-------------------|--------------|-------------------------------------------------|
--   | DRAFT             | 草稿         | 创建                                            |
--   | PENDING_APPROVAL  | 待审         | 提交且 identity 判定「需审批」                    |
--   | APPROVED          | 已批         | 提交且 identity 判定「免审」/ 未配规则；或审批结论回来 |
--   | EXECUTED          | 已执行       | 执行（已批 ⇒ 已执行）                            |
--   | REJECTED          | 已驳回       | 审批结论为驳回；或本人把草稿撤掉                   |
--
-- 🔴 **版本列（version）**：蓝图输入句明写 `expected_version`。每次状态流转都
--   `version = version + 1`，而流转语句的 where 带 `and version = ?` ——
--   传进来的 expected_version 对不上 ⇒ 影响行数为 0 ⇒ **409 冲突**。
--   台账与详情都带出当前 version，客户端据此重算下一次要传的 expected_version。
--
-- ===========================================================================
-- 第五节表：price_adjustment_order —— 一张调价单（单头）
-- ===========================================================================
CREATE TABLE price_adjustment_order (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- ===== 状态机（第四节）=====
  status text NOT NULL
    CHECK(status IN ('DRAFT','PENDING_APPROVAL','APPROVED','EXECUTED','REJECTED')),
  -- 🔴 **乐观锁版本**（蓝图输入句：expected_version）。
  version int NOT NULL DEFAULT 1 CHECK(version >= 1),

  -- ===== 目标：一个 SKU 在一个店铺 = 一次调价 = 一条明细 = 一行明细 = 一条事实 =====
  sku_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,
  -- 计价币种（店铺的币种）。与当前价的币种必须一致 —— 跨币种**绝不按 1:1 折算**。
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 标题（提交审批实例时的 subjectTitle）与**调价原因**（蓝图的职责句：「说明原因」）。
  title  varchar(200) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  note   text NOT NULL DEFAULT '',

  -- ===== 利润口径（改价前后的利润由 5.3.1.1 的引擎算，这些是它的输入）=====
  quantity numeric(19,6) NOT NULL DEFAULT 1 CHECK(quantity > 0),
  cost_currency varchar(3) NOT NULL CHECK(cost_currency ~ '^[A-Z]{3}$'),
  -- 汇率：1 cost_currency = fx_rate currency。跨币种时必填。
  fx_rate numeric(19,8) CHECK(fx_rate IS NULL OR fx_rate > 0),
  platform_fee_rate numeric(9,6)
    CHECK(platform_fee_rate IS NULL OR (platform_fee_rate >= 0 AND platform_fee_rate < 1)),
  platform_fee_fixed numeric(19,4)
    CHECK(platform_fee_fixed IS NULL OR platform_fee_fixed >= 0),
  logistics_cost_per_unit numeric(19,4)
    CHECK(logistics_cost_per_unit IS NULL OR logistics_cost_per_unit >= 0),
  monthly_fixed_cost numeric(19,4)
    CHECK(monthly_fixed_cost IS NULL OR monthly_fixed_cost >= 0),
  cost_window_from date,
  cost_window_to date,

  -- ===== 审批（第五节之二：只记镜像，事实仍在 identity）=====
  -- 这次提交**要不要**走审批。
  approval_required boolean NOT NULL DEFAULT false,
  -- identity 的评估结论：SUBMIT_FOR_APPROVAL（需审批）/ EXEMPT（免审）。
  approval_decision text
    CHECK(approval_decision IS NULL OR approval_decision IN ('SUBMIT_FOR_APPROVAL','EXEMPT')),
  -- 结论来源：RULE（命中某条规则）/ DEFAULT（走规则集 defaultAction）/ UNCONTROLLED（未配规则集）。
  approval_source text
    CHECK(approval_source IS NULL OR approval_source IN ('RULE','DEFAULT','UNCONTROLLED')),
  -- 命中的规则与目标流程（identity 侧 id，本表只记引用，不复制规则）。
  approval_rule_id   uuid,
  approval_rule_name varchar(100),
  approval_flow_id   uuid,
  approval_flow_name varchar(100),
  -- 结论的人话解释（为什么免审 / 为什么需审批）。原样带回 identity 的 reason。
  approval_reason varchar(500) NOT NULL DEFAULT '',
  -- 投出去的审批实例（identity 侧的实例 id 与实例号）。
  approval_instance_id  uuid,
  approval_instance_no  varchar(64),
  approval_submitted_at timestamptz,
  -- 拉回来的审批结论（时间 + 决定人）。
  approval_decided_at timestamptz,
  approval_decided_by uuid,
  -- 🔴 上一次同步审批状态失败的**如实**记录（如「审批服务不可达」）。
  --   刻意**不**在失败时把单子推进到已批 —— 停���待审 + 如实标错才是诚实降级。
  approval_sync_error varchar(500) NOT NULL DEFAULT '',

  -- ===== 时间线 =====
  created_by  uuid NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  submitted_at timestamptz,
  executed_at  timestamptz,
  rejected_at  timestamptz,
  rejected_by  uuid,
  reject_reason varchar(500) NOT NULL DEFAULT '',

  UNIQUE(tenant_id, id),
  -- 平台码 / 站点码不为空，形状与 V222 / V237 的同一套约定一致。
  CHECK(btrim(platform_code) <> '' AND btrim(site_code) <> ''),
  CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(site_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  -- 跨币种必须要汇率：没有汇率时**拒绝**而不是默默按 1:1 折算。
  CHECK(currency = cost_currency OR fx_rate IS NOT NULL),
  -- 成本窗口必须自洽。
  CHECK(cost_window_from IS NULL OR cost_window_to IS NULL OR cost_window_to >= cost_window_from),
  -- 状态与时间线必须自洽：已执行必有执行时刻；已驳回必有驳回时刻与原因。
  CHECK(status <> 'EXECUTED' OR executed_at IS NOT NULL),
  CHECK(status <> 'REJECTED' OR (rejected_at IS NOT NULL AND btrim(reject_reason) <> '')),
  -- 审批实例存在 ⇒ 必已投出（有提交时刻）。
  CHECK(approval_instance_id IS NULL OR approval_submitted_at IS NOT NULL),
  -- 需审批的单子必有待审结论（免审的那张 decision='EXEMPT'）。
  CHECK(approval_required = false OR approval_decision = 'SUBMIT_FOR_APPROVAL')
);

-- 调价单台账：默认排序是「最近创建的」。
CREATE INDEX price_adjustment_order_by_created
  ON price_adjustment_order(tenant_id, created_at DESC, id);
-- 「这个 SKU 的调价单」—— 单个 SKU 的调价历史（5.5.1.1 价格历史也会问这个问题）。
CREATE INDEX price_adjustment_order_by_sku
  ON price_adjustment_order(tenant_id, sku_id, created_at DESC);
-- 「这个店铺的调价单」—— 店铺维度的复盘。
CREATE INDEX price_adjustment_order_by_shop
  ON price_adjustment_order(tenant_id, platform_code, site_code, created_at DESC);
-- 「按状态筛」—— 台账页的筛选维度。
CREATE INDEX price_adjustment_order_by_status
  ON price_adjustment_order(tenant_id, status, created_at DESC);

-- ===========================================================================
-- 第五节之二：审批镜像列的语义补充（不写进 CHECK，避免 CHECK 过长难读）
-- ===========================================================================
-- approval_required = true ⇒ 一定停在 PENDING_APPROVAL 或更后面的状态，
--   且 approval_decision = 'SUBMIT_FOR_APPROVAL'（已由上面的 CHECK 钉死）。
-- approval_required = false ⇒ decision 可能是 'EXEMPT'（免审）或 NULL（还没提交过）。
--   「还没提交过」时 status 必然是 DRAFT —— 由服务层的状态机保证，本表只记事实。
--
-- ===========================================================================
-- 第六节表：price_adjustment_order_item —— 这张单子里那一条价格变更
-- ===========================================================================
--
-- 🔴 **「记录变更前后值」（蓝图的输出句）就落在这里**：旧价、新价、价差、幅度，
--   以及改价前后的毛利额与毛利率（全部来自 5.3.1.1 的引擎）。
CREATE TABLE price_adjustment_order_item (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 所属调价单。**不建外键**（第二节）：触发器校验。
  order_id uuid NOT NULL,

  -- 被调价的那个 listing = 一个 SKU 在一个店铺。
  sku_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 变更前后值 =====
  -- 旧价：取自 catalog 内已有的价格事实（第五节之三）。可空 = 没取到（那就不该建这张单）。
  old_price numeric(19,4) CHECK(old_price IS NULL OR old_price >= 0),
  -- 新价。**必须 > 0**：把价格改成 0 就是「免费」，那不是调价。
  new_price numeric(19,4) CHECK(new_price IS NULL OR new_price > 0),
  -- 价差（新价 − 旧价）。正 = 涨价，负 = 降价。
  price_delta numeric(19,4),
  -- 幅度（(新价 − 旧价) / 旧价）。旧价为 0 时**不可计算** ⇒ null（不是 0）。
  price_rate numeric(9,6),

  -- 当前价取自哪一种事实来源：
  --   PRICE_OBSERVATION    5.5.2.1 的价格观测台账（最新一条观测）
  --   BATCH_ADJUSTMENT     5.4.1.2 的批量调价明细里记下的新价（比观测更新）
  price_source text NOT NULL
    CHECK(price_source IN ('PRICE_OBSERVATION','BATCH_ADJUSTMENT')),
  -- 当前价是**哪一刻**观测到的（观测台账只追加，「最新一条」是一个时间点而不是「当前价」）。
  price_observed_at timestamptz NOT NULL,

  -- ===== 改价前后的利润（全部由 5.3.1.1 的引擎算，本模块零算法）=====
  -- 🔴 **成本不完整时这些是 null，不是 0**：null = 「没测准」，0 = 「毛利刚好 0」。
  gross_profit_before numeric(19,4),
  gross_margin_before numeric(9,6),
  gross_profit_after  numeric(19,4),
  gross_margin_after  numeric(9,6),
  -- 决策就绪度（保存时的快照）。false 时上面的利润与毛利率必为 null。
  decision_ready boolean NOT NULL,
  -- 🔴 亏损标记（毛利额 < 0）。成本不完整时为 **null**（不可信），不是 false。
  loss_before boolean,
  loss_after  boolean,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 **一条调价单只有一条明细**（第三节）：一张单 = 一个 SKU 在一个店铺的一次变更。
  UNIQUE(tenant_id, order_id),
  -- 变更前后值必须齐全且自洽。
  CHECK(old_price IS NOT NULL AND new_price IS NOT NULL AND price_delta IS NOT NULL),
  -- 价差必须等于新价减旧价（DB 兜一道，避免应用层算错还写进来）。
  CHECK(price_delta = new_price - old_price),
  -- 幅度要么不可计算（旧价为 0），要么有值。
  CHECK(price_rate IS NULL OR old_price > 0),
  -- 平台码 / 站点码不为空，形状与 V222 / V237 的同一套约定一致。
  CHECK(btrim(platform_code) <> '' AND btrim(site_code) <> ''),
  CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(site_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  -- 没测准的行不许带利润数字（避免把「没测准」渲染成 0）。
  CHECK(decision_ready OR (gross_profit_before IS NULL AND gross_margin_before IS NULL
                           AND gross_profit_after IS NULL AND gross_margin_after IS NULL)),
  -- 亏损标记必须有毛利额支撑。
  CHECK(loss_before IS NULL OR (decision_ready AND gross_profit_before IS NOT NULL)),
  CHECK(loss_after IS NULL OR (decision_ready AND gross_profit_after IS NOT NULL))
);

-- 调价单明细：按单取明细。
CREATE INDEX price_adjustment_order_item_by_order
  ON price_adjustment_order_item(tenant_id, order_id);
-- 「这个 SKU 在这个店铺的调价明细」—— 与调价单台账同口径的追加入口。
CREATE INDEX price_adjustment_order_item_by_sku
  ON price_adjustment_order_item(tenant_id, sku_id, platform_code, site_code, created_at DESC);

-- ===========================================================================
-- 第五节之三：当前价的来源口径（不建表，只在本节写明取舍）
-- ===========================================================================
--
-- 本仓 catalog **没有** listing 的本地投影（listing 事实属 channel 的 4.1.1.1），
-- 且 AGENTS.md 禁止跨服务查表。所以「SKU 在某店铺的当前价」只能取 catalog 内已有的价格事实：
--
--   1. **5.5.2.1 价格观测台账**（price_anomaly_observation，V222）：一行 = 一个 SKU 在一个
--      站点上的一次价格观测。取该 (SKU, 店铺) 的**最新一条**：优先我方价 internal_price，
--      退到平台在售价 platform_price，两者都没有 ⇒ **取不到**。
--   2. **5.4.1.2 批量调价明细**（batch_price_adjustment_item，V237）：它记下了改完之后的新价，
--      时间上可以比观测更晚 ⇒ 也要参与「谁更新」的比较。
--
-- 🔴 **取不到就整单拒绝并说清原因，绝不用 0 顶** —— 0 会被当成「免费」，
--   而它其实是一个取不到的事实。
-- 🔴 **币种不同 ⇒ 整单拒绝，绝不按 1:1 折算**。
--
-- ===========================================================================
-- 第七节表：price_adjustment_writeback —— 生效后待回写平台的事实（给 5.4.2.1）
-- ===========================================================================
--
-- 蓝图 5.4.1.1 的输出句：「生效后**触发平台回写**」；而 5.4.2.1 平台价格同步
-- （**未交付**）的输入句是「价格变更事件」。本仓**还没有事件总线**（libraries 下只有 tenancy），
-- 所以本模块用**一张 outbox 表**把这个「待回写的事实」落下来，并经授权端点暴露给下游：
--   * 本模块**只**产出事实，**绝不**自己去调平台 API；
--   * 平台回写、平台定价规则（最低价 / 自动比价）、以及「被平台调整后的实际价」，
--     全部归 5.4.2.1 —— 它回写完成后调用 ack 端点把这条事实结掉。
CREATE TABLE price_adjustment_writeback (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 来源：这张调价单的那条明细。**不建外键**（第二节）：触发器校验。
  order_id uuid NOT NULL,
  order_item_id uuid NOT NULL,

  -- 回写目标与期望价。
  sku_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 期望回写上去的价 = 调价单的新价。
  expected_price numeric(19,4) NOT NULL CHECK(expected_price > 0),
  -- 改价之前的价（供下游记录「平台把价调成了什么」的对照）。
  previous_price numeric(19,4) CHECK(previous_price IS NULL OR previous_price >= 0),

  -- PENDING 待回写 / ACKNOWLEDGED 下游已回执（平台是否真的改了价由下游自己记）。
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACKNOWLEDGED')),
  -- 幂等键：下游按它去重，跨服务重试不会产生第二条回写。
  idempotency_key varchar(160) NOT NULL CHECK(btrim(idempotency_key) <> ''),
  -- 回执时刻。
  acknowledged_at timestamptz,
  -- 回执里带回的说明（如「平台把价压到了最低价」）。**下游的事实归下游**，这里只留一句摘要。
  ack_note varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),
  -- 一条明细只产生一条待回写事实。
  UNIQUE(tenant_id, order_item_id),
  -- 已回执的必有回执时刻；未回执的必无。
  CHECK((status = 'ACKNOWLEDGED') = (acknowledged_at IS NOT NULL)),
  CHECK(btrim(platform_code) <> '' AND btrim(site_code) <> '')
);

-- 待回写清单：下游按状态取（PENDING 是它真正要消费的那一批）。
CREATE INDEX price_adjustment_writeback_by_status
  ON price_adjustment_writeback(tenant_id, status, created_at, id);
-- 「这个 SKU 在这个店铺还有几条没回写」。
CREATE INDEX price_adjustment_writeback_by_sku
  ON price_adjustment_writeback(tenant_id, sku_id, platform_code, site_code, created_at DESC);

-- ===========================================================================
-- 第八节：引用校验用触发器，不用外键（第二节）
-- ===========================================================================
--
-- 触发器的判据与外键等价（引用不存在 ⇒ 报 23514），但**不参与 TRUNCATE 的依赖图**。

-- 明细必须挂在本租户真实存在的调价单与 SKU 上。
CREATE FUNCTION guard_price_adjustment_order_item() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM price_adjustment_order o
     WHERE o.tenant_id=NEW.tenant_id AND o.id=NEW.order_id) THEN
    RAISE EXCEPTION 'Price adjustment order item % must reference an existing order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION 'Price adjustment order item % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER price_adjustment_order_item_guard
  BEFORE INSERT OR UPDATE ON price_adjustment_order_item
  FOR EACH ROW EXECUTE FUNCTION guard_price_adjustment_order_item();

-- 单头必须挂在本租户真实存在的 SKU 上。
CREATE FUNCTION guard_price_adjustment_order_sku() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION 'Price adjustment order % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER price_adjustment_order_sku_guard
  BEFORE INSERT OR UPDATE ON price_adjustment_order
  FOR EACH ROW EXECUTE FUNCTION guard_price_adjustment_order_sku();

-- 待回写事实必须挂在真实存在的明细上。
CREATE FUNCTION guard_price_adjustment_writeback() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM price_adjustment_order_item i
     WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.order_item_id
       AND i.order_id=NEW.order_id) THEN
    RAISE EXCEPTION 'Price adjustment writeback % must reference an existing order item',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER price_adjustment_writeback_guard
  BEFORE INSERT OR UPDATE ON price_adjustment_writeback
  FOR EACH ROW EXECUTE FUNCTION guard_price_adjustment_writeback();

-- ===========================================================================
-- 第九节：RLS 与授权
-- ===========================================================================
--
-- 三张表都是租户级业务表（调价是租户自己的定价动作）。
-- 照抄 V237 第八节的 DO $$ FOREACH 写法：三张表的 isolation 策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的调价单 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['price_adjustment_order','price_adjustment_order_item','price_adjustment_writeback'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性跟随 SKU：看不到 SKU 就看不到它的调价单与明细
-- （调价单里躺着这个 SKU 的售价与改价前后利润）。
-- 这条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['price_adjustment_order','price_adjustment_order_item','price_adjustment_writeback'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
--
-- 🔴 **本模块的三张表需要 UPDATE**（状态流转 + 回执），这与 V237 的「只授 SELECT / INSERT」
--   不同 —— 调价单是**有生命周期的事实**：草稿 → 待审 → 已批 → 已执行。
--   但**不授 DELETE**：已发生过的调价不许删（删了「这次到底改成了多少」就无从追溯）；
--   要改价就**再发一张单**，并在理由里写明上一张的编号。
GRANT SELECT,INSERT,UPDATE ON price_adjustment_order TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON price_adjustment_order TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON price_adjustment_order_item TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON price_adjustment_order_item TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON price_adjustment_writeback TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON price_adjustment_writeback TO starcon_app;
