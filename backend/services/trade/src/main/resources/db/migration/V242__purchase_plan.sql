-- 6.2.1.3 采购计划：按周期（月/季）、按店铺大促节奏、按仓库制定采购计划，
-- 把补货建议汇总成计划行，支持调整、锁定、导出给采购员。
--
-- 蓝图 06-purchase-and-supply.md 6.2.1.3 原文：
--   职责：按周期（月/季）、按店铺大促节奏、按仓库制定采购计划，把补货建议汇总成计划行，
--         支持调整、锁定、导出给采购员。
--   输入：补货建议集合；计划周期；大促日历；锁库策略。
--   输出：采购计划（计划行：SKU、数量、目标仓、期望到货日、优先级）。
--   关系：上游 = 6.2.1.2；下游 = 6.2.2.1 采购申请。
--         **大促备货与日常补货要分开**：大促计划的备货量大、周期长，混在一起会让日常补货失焦。
--
-- 归属：**trade 服务**（蓝图 06 卷 L2 表「6.2 采购需求 → trade」；同 L3 的 6.2.1.2 补货建议
-- 也在 trade 服务）。前端路由 /purchase/demand/plans 是**前端**路由；后端命名空间按服务走
-- （与 6.2.1.2 的 /trade/replenishment-suggestions、6.2.1.1 的 /trade/stockout-risk 同一纪律）。
--
-- ===========================================================================
-- 一、模块边界：本文件**不碰 6.2.1.2 的任何一张表**
-- ===========================================================================
--   * **补货建议**读 6.2.1.2 的 `replenishment_suggestion` / `replenishment_suggestion_batch`。
--     那两张表是 6.2.1.2 的**写入所有者**：本文件只 SELECT，一个字节都不改，
--     也不 ALTER、不加约束、不建到它们的触发器。
--   * **SKU 身份**（编码 / 名称）随建议一起读过来 —— 6.2.1.2 在计算时已经经
--     `ReplenishmentCatalogPort` 从 catalog 的授权只读接口解析过。本服务**不建 SKU 影子表**，
--     计划行上的 sku_code / sku_name 是**单据上的展示快照**（与 6.2.1.2 同一纪律），
--     不是一份新的商品档案。
--   * **目标仓与法人**经 trade 的 `PurchasePlanWarehousePort` 读 warehouse 的授权只读接口
--     （GET /warehouse/warehouses、GET /warehouse/legal-entities）。warehouse 的仓库与法人
--     登记是它的写入所有者，本文件不建任何跨库外键、不建跨库视图、不跨服务直查对方的表。
--   * **锁库（预占）**经 trade 的 `PurchasePlanLockPort` 写 warehouse 的授权写接口
--     （POST /warehouse/inventory/reservations，7.2.1.1）。预占事实的写入所有者是 warehouse
--     （7.2.1.1 inventory_reservation，迁移 V102；物理圈货到 bucket 由 7.2.1.2 落 V107）。
--     本库**只记录「已提交锁定请求 / 已确认锁定 / 锁定失败」这些属于采购计划自己的事实**，
--     **不建影子表假装预占成功**。
--
-- ===========================================================================
-- 二、七张业务表的职责划分
-- ===========================================================================
--   1. `purchase_plan_campaign` —— **大促日历**（本模块自带，租户可维护）。
--      实查集成头：backend/services/*/src/main/java/** 下**没有**任何 Campaign / Promo 类，
--      大促日历在本仓**没有任何已交付来源** ⇒ 由本模块自带，而不是假装有个上游服务。
--      这是真实的业务输入（租户自己录入大促档期与预期销量 / 备货系数），不是 mock。
--   2. `purchase_plan` —— **采购计划（头）**：类型（日常 / 大促）、周期（月 / 季）、目标仓、
--      法人、状态、优先级。
--   3. `purchase_plan_line` —— **计划行**：SKU、数量、目标仓、期望到货日、优先级
--      （蓝图输出句逐字对应）。
--   4. `purchase_plan_adjustment` —— **计划行调整留痕**（append-only）。
--   5. `purchase_plan_lock_log` —— **锁定请求留痕**（append-only）。
--   6. `purchase_plan_lock_policy` —— **锁库策略**（蓝图输入句点名的第四项输入）。
--
-- ===========================================================================
-- 三、蓝图加粗句「大促备货与日常补货要分开」怎么落地
-- ===========================================================================
--   * **结构上分开**：`purchase_plan.plan_type` 取 ROUTINE（日常补货）/ CAMPAIGN（大促备货），
--     且 `plan_type='CAMPAIGN'` 时 `campaign_id` **必填**（DB 层 CHECK 强制自洽），
--     ROUTINE 时必须为空 —— 一张计划不可能既是大促又是日常。
--   * **备货量分开算**：大促计划的计划量 = 建议量 × 销量系数 × 备货系数（取整到 6 位 HALF_UP）；
--     日常计划的计划量 = 建议量本身（系数恒为 1）。算式原文写进 `quantity_basis`。
--   * **到货日分开算**：大促计划的期望到货日 = 大促开始日 − 提前天数（备货周期长，必须更早到）；
--     日常计划用 6.2.1.2 给出的预计到货日。
--   * **视图上分开**：`GET /trade/purchase-plans/summary` 返回**两段独立**的汇总
--     （routine / campaign 各自一行块），不把两种备货量加在一起糊成一个总数 ——
--     混在一起正是蓝图加粗句要防的事。
--
-- ===========================================================================
-- 四、单位与时间纪律
-- ===========================================================================
--   * 数量一律 `numeric(19,6)`（基础单位），金额 `numeric(19,4)`，费率 / 系数 `numeric(19,6)`。
--     **禁浮点**：Java 侧 BigDecimal，费率与系数一律 HALF_UP 取 6 位。
--   * 时间列一律 `timestamptz`；Java 侧用 `OffsetDateTime` 绑定，SQL 的 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s）。
--   * 期望到货日 / 计划周期边界是 `date`（业务日历口径），不存时刻。
--
-- ===========================================================================
-- 1. 大促日历（本模块自带，租户可维护）
-- ===========================================================================
CREATE TABLE purchase_plan_campaign (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 档期编码：租户内唯一、可读（CAMPAIGN-2026-BLACK-FRIDAY）。
  code varchar(64) NOT NULL CHECK(btrim(code) <> ''),
  name varchar(200) NOT NULL CHECK(btrim(name) <> ''),
  -- **店铺**：channel 拥有店铺主数据，跨服务不建外键 ⇒ 这里只存**外部引用码**（不建索引约束）。
  -- 为空 = 全店铺通用的大促。
  shop_code varchar(64),
  -- 大促档期（业务日历口径，date）。
  start_date date NOT NULL,
  end_date date NOT NULL,
  -- **预期销量系数**：大促期间预计销量是日常的多少倍。1 = 与日常一样。
  sales_multiplier numeric(19,6) NOT NULL DEFAULT 1 CHECK(sales_multiplier > 0),
  -- **备货系数**：在销量系数之外还要多备多少（1 = 按系数备足，不再加厚）。
  stock_factor numeric(19,6) NOT NULL DEFAULT 1 CHECK(stock_factor > 0),
  -- **期望到货提前天数**：大促备货必须比日常更早到（备货量大、周期长）。
  arrival_lead_days int NOT NULL DEFAULT 0
    CHECK(arrival_lead_days >= 0 AND arrival_lead_days <= 180),
  -- 状态：PLANNED 筹备中 / ACTIVE 进行中 / CLOSED 已结束。
  status text NOT NULL DEFAULT 'PLANNED' CHECK(status IN ('PLANNED','ACTIVE','CLOSED')),
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  CONSTRAINT purchase_plan_campaign_dates_ordered CHECK(end_date >= start_date)
);

-- ===========================================================================
-- 2. 采购计划（头）
-- ===========================================================================
CREATE TABLE purchase_plan (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 计划号：租户内唯一（PP-20260101-a1b2c3d4）。
  plan_no varchar(64) NOT NULL CHECK(btrim(plan_no) <> ''),
  -- **计划类型**：ROUTINE 日常补货 / CAMPAIGN 大促备货。
  -- 蓝图加粗句「大促备货与日常补货要分开」的第一层：类型是**结构**上的字段，不是标签。
  plan_type text NOT NULL CHECK(plan_type IN ('ROUTINE','CAMPAIGN')),
  -- **大促**（plan_type='CAMPAIGN' 时必填）。同一服务内的表，可以建外键。
  campaign_id uuid,
  -- **计划周期类型**：MONTHLY 月度 / QUARTERLY 季度（蓝图「按周期（月/季）」）。
  period_type text NOT NULL CHECK(period_type IN ('MONTHLY','QUARTERLY')),
  period_start date NOT NULL,
  period_end date NOT NULL,
  -- **目标仓**（warehouse 拥有仓库主数据 ⇒ 不建跨服务外键，只存 id + 展示快照）。
  target_warehouse_id uuid NOT NULL,
  target_warehouse_code varchar(64) NOT NULL DEFAULT '',
  target_warehouse_name varchar(200) NOT NULL DEFAULT '',
  -- **法人**：锁库预占要落法人货权（warehouse 7.2.1.1 的 legal_entity_id 是必填）。
  legal_entity_id uuid NOT NULL,
  legal_entity_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_name varchar(200) NOT NULL DEFAULT '',
  -- 计划状态：DRAFT 草稿 / CONFIRMED 已确认 / LOCKING 锁定中 /
  --          LOCKED 已锁定 / PARTIALLY_LOCKED 部分锁定 / LOCK_FAILED 锁定失败。
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','CONFIRMED','LOCKING','LOCKED','PARTIALLY_LOCKED','LOCK_FAILED')),
  -- 计划整体优先级。
  priority text NOT NULL DEFAULT 'NORMAL'
    CHECK(priority IN ('CRITICAL','HIGH','NORMAL','LOW')),
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,plan_no),
  FOREIGN KEY(tenant_id,campaign_id) REFERENCES purchase_plan_campaign(tenant_id,id) ON DELETE RESTRICT,
  -- 类型与大促必须自洽：ROUTINE 不带大促，CAMPAIGN 必须带大促。
  CONSTRAINT purchase_plan_campaign_consistent CHECK(
    (plan_type = 'ROUTINE' AND campaign_id IS NULL)
    OR (plan_type = 'CAMPAIGN' AND campaign_id IS NOT NULL)
  ),
  CONSTRAINT purchase_plan_period_ordered CHECK(period_end >= period_start)
);

-- ===========================================================================
-- 3. 计划行（蓝图输出句：SKU、数量、目标仓、期望到货日、优先级）
-- ===========================================================================
CREATE TABLE purchase_plan_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- SKU 编码 / 名称：**单据上的展示快照**（catalog 拥有商品档案，本服务不建 SKU 影子表）。
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- **来源补货建议**（6.2.1.2 的建议 id 与批次 id）。手工加的行可以为空。
  source_suggestion_id uuid,
  source_batch_id uuid,
  -- **建议量**（来自 6.2.1.2，基础单位）。手工加的行可以为空。
  suggested_quantity numeric(19,6) CHECK(suggested_quantity IS NULL OR suggested_quantity > 0),
  -- **计划量**（基础单位）—— 真正要采购的量，可以被人工调整。
  plan_quantity numeric(19,6) NOT NULL CHECK(plan_quantity > 0),
  -- **计划量的算法说明**：算式原文（日常 / 大促两条不同的算式写在这里）。
  quantity_basis text NOT NULL DEFAULT '',
  -- **目标仓**（行级覆盖计划头 —— 同一张计划里不同 SKU 可能进不同的仓）。
  target_warehouse_id uuid NOT NULL,
  target_warehouse_code varchar(64) NOT NULL DEFAULT '',
  target_warehouse_name varchar(200) NOT NULL DEFAULT '',
  -- **期望到货日**（蓝图输出句逐字对应）。date 口径。
  expected_arrival_date date NOT NULL,
  -- **优先级**（蓝图输出句逐字对应）。
  priority text NOT NULL DEFAULT 'NORMAL'
    CHECK(priority IN ('CRITICAL','HIGH','NORMAL','LOW')),
  -- 行状态：PLANNED 待锁定 / ADJUSTED 已调整 / LOCK_REQUESTED 已提交锁定请求 /
  --          LOCKED 已确认锁定 / LOCK_FAILED 锁定失败。
  status text NOT NULL DEFAULT 'PLANNED'
    CHECK(status IN ('PLANNED','ADJUSTED','LOCK_REQUESTED','LOCKED','LOCK_FAILED')),
  -- **锁定状态**：NOT_REQUESTED 未请求 / REQUESTED 已提交请求 / CONFIRMED 已确认 / FAILED 失败。
  lock_status text NOT NULL DEFAULT 'NOT_REQUESTED'
    CHECK(lock_status IN ('NOT_REQUESTED','REQUESTED','CONFIRMED','FAILED')),
  -- warehouse 侧返回的预占 id / 单号 —— **如实记下来**，本服务不凭空造一个。
  lock_reservation_id uuid,
  lock_reservation_no varchar(64),
  lock_requested_at timestamptz,
  lock_confirmed_at timestamptz,
  -- 锁定失败的原文原因（warehouse 拒了 / 不可达 / 策略不允许）。
  lock_failure_reason text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  -- 排序位次：落库后可回溯「当时排第几」。
  sort_rank int NOT NULL DEFAULT 0 CHECK(sort_rank >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一张计划里一个 SKU 只有一行（重复汇总会多订）。
  UNIQUE(tenant_id,plan_id,sku_id),
  FOREIGN KEY(tenant_id,plan_id) REFERENCES purchase_plan(tenant_id,id) ON DELETE CASCADE,
  -- 锁定状态与锁定信息必须自洽：
  --   CONFIRMED ⇒ 有预占 id 与确认时刻；
  --   FAILED    ⇒ 有失败原因；
  --   其余      ⇒ 没有预占 id。
  CONSTRAINT purchase_plan_line_lock_consistent CHECK(
    (lock_status = 'CONFIRMED' AND lock_reservation_id IS NOT NULL
        AND lock_confirmed_at IS NOT NULL AND lock_failure_reason = '')
    OR (lock_status = 'FAILED' AND lock_failure_reason <> ''
        AND lock_reservation_id IS NULL)
    OR (lock_status IN ('NOT_REQUESTED','REQUESTED') AND lock_reservation_id IS NULL
        AND lock_confirmed_at IS NULL AND lock_failure_reason = '')
  ),
  -- 行状态与锁定状态必须自洽：只有 CONFIRMED 的行才是 LOCKED。
  CONSTRAINT purchase_plan_line_status_consistent CHECK(
    (status = 'LOCKED' AND lock_status = 'CONFIRMED')
    OR (status = 'LOCK_FAILED' AND lock_status = 'FAILED')
    OR (status = 'LOCK_REQUESTED' AND lock_status = 'REQUESTED')
    OR (status IN ('PLANNED','ADJUSTED') AND lock_status IN ('NOT_REQUESTED','REQUESTED'))
  )
);

-- ===========================================================================
-- 4. 计划行调整留痕（append-only）
-- ===========================================================================
CREATE TABLE purchase_plan_adjustment (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, plan_line_id uuid NOT NULL,
  -- 被改的字段名（plan_quantity / expected_arrival_date / priority / target_warehouse_id / note）。
  field text NOT NULL CHECK(btrim(field) <> ''),
  -- 改之前的值（原文）。null = 之前没有这个值。
  before_value text,
  -- 改之后的值（原文）。
  after_value text,
  -- **调整理由**。必填：一句「调整一下」不是留痕。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  adjusted_by uuid NOT NULL,
  adjusted_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,plan_line_id) REFERENCES purchase_plan_line(tenant_id,id) ON DELETE RESTRICT
);

-- ===========================================================================
-- 5. 锁定请求留痕（append-only）
-- ===========================================================================
CREATE TABLE purchase_plan_lock_log (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, plan_line_id uuid NOT NULL,
  -- REQUEST 已提交锁定请求 / CONFIRM warehouse 确认预占 / FAIL 锁定失败。
  action text NOT NULL CHECK(action IN ('REQUEST','CONFIRM','FAIL')),
  -- warehouse 侧的预占 id / 单号（失败时为空）。
  reservation_id uuid,
  reservation_no varchar(64),
  -- 结果说明原文（失败原因 / 确认凭据）。
  message text NOT NULL DEFAULT '',
  requested_by uuid NOT NULL,
  requested_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,plan_line_id) REFERENCES purchase_plan_line(tenant_id,id) ON DELETE RESTRICT,
  -- CONFIRM 必须带预占 id；REQUEST / FAIL 不带（还没成功）。
  CONSTRAINT purchase_plan_lock_log_reservation_consistent CHECK(
    (action = 'CONFIRM' AND reservation_id IS NOT NULL)
    OR (action IN ('REQUEST','FAIL') AND reservation_id IS NULL)
  )
);

-- ===========================================================================
-- 6. 锁库策略（蓝图输入句点名的第四项输入）
-- ===========================================================================
CREATE TABLE purchase_plan_lock_policy (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 作用范围：ROUTINE 日常 / CAMPAIGN 大促。两种类型的锁库节奏本来就不一样。
  plan_type text NOT NULL CHECK(plan_type IN ('ROUTINE','CAMPAIGN')),
  -- 是否允许锁库。false = 本租户对该类型不锁（锁了也拿不到货，不如不锁）。
  lock_enabled boolean NOT NULL DEFAULT true,
  -- **预占持有天数**：换算成 warehouse 的 expires_at。0 = 永不过期（warehouse 允许不传过期时刻）。
  hold_days int NOT NULL DEFAULT 30 CHECK(hold_days >= 0 AND hold_days <= 365),
  -- 是否要求计划**已确认**才能锁。true = 草稿计划锁不了（没定的计划先占货，占了也是浪费）。
  require_confirmed boolean NOT NULL DEFAULT true,
  -- **生效期**（区间语义 [effective_from, effective_to)）。effective_to 为 NULL = 当前生效。
  effective_from date NOT NULL,
  effective_to date,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  CONSTRAINT purchase_plan_lock_policy_effective_ordered
    CHECK(effective_to IS NULL OR effective_to >= effective_from)
);
-- 同一类型同时只允许一条未收口的策略：否则「日常计划现在用哪条锁库策略」有两个答案。
CREATE UNIQUE INDEX purchase_plan_lock_policy_open_uq
  ON purchase_plan_lock_policy(tenant_id, plan_type) WHERE effective_to IS NULL;

-- ===========================================================================
-- 7. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================
-- 7.1 计划行的**来源列冻结**：来源建议、建议量、算法说明是「这行是从哪条建议、按什么算式来的」
--     的举证凭据。重新汇总**不覆盖**已存在的行（服务层跳过），所以这几列永远不该被改。
CREATE FUNCTION guard_purchase_plan_line_source_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.plan_id IS DISTINCT FROM OLD.plan_id
    OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
    OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
    OR NEW.source_suggestion_id IS DISTINCT FROM OLD.source_suggestion_id
    OR NEW.source_batch_id IS DISTINCT FROM OLD.source_batch_id
    OR NEW.suggested_quantity IS DISTINCT FROM OLD.suggested_quantity
    OR NEW.quantity_basis IS DISTINCT FROM OLD.quantity_basis
    OR NEW.sort_rank IS DISTINCT FROM OLD.sort_rank THEN
  RAISE EXCEPTION '计划行「%」的来源列不可改写：它记录的是「这行从哪条补货建议、按什么算式汇总来的」。要换来源请重新建一张计划', OLD.sku_code
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER purchase_plan_line_source_frozen BEFORE UPDATE ON purchase_plan_line
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_plan_line_source_frozen();

-- 7.2 调整留痕与锁定留痕是 **append-only**：不可改写、不可删。
CREATE FUNCTION guard_purchase_plan_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是不可改写的留痕表（%）：采购计划的行调整与锁定请求记录是「当时做了什么」的举证凭据',
   TG_TABLE_NAME, TG_OP
   USING ERRCODE='23514';
END $$;
CREATE TRIGGER purchase_plan_adjustment_append_only BEFORE UPDATE OR DELETE ON purchase_plan_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_plan_append_only();
CREATE TRIGGER purchase_plan_lock_log_append_only BEFORE UPDATE OR DELETE ON purchase_plan_lock_log
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_plan_append_only();

-- ===========================================================================
-- 8. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 计划列表页：按类型 + 状态 + 周期读（清单页主查询形状）。
CREATE INDEX purchase_plan_by_type_status ON purchase_plan(tenant_id, plan_type, status, period_start desc, id);
-- 计划列表页：按周期读（「这个月 / 这个季度的计划」）。
CREATE INDEX purchase_plan_by_period ON purchase_plan(tenant_id, period_type, period_start desc, id);
-- 计划详情：按大促反查（一个大促下有几张计划）。
CREATE INDEX purchase_plan_by_campaign ON purchase_plan(tenant_id, campaign_id, period_start desc);
-- 计划行清单页：按计划 + 优先级 + 状态读。
CREATE INDEX purchase_plan_line_by_plan ON purchase_plan_line(tenant_id, plan_id, priority, status, sort_rank);
-- 计划行清单页：按 SKU 过滤 / 反查。
CREATE INDEX purchase_plan_line_by_sku ON purchase_plan_line(tenant_id, sku_id, plan_id);
-- 大促日历清单页：按档期读。
CREATE INDEX purchase_plan_campaign_by_dates ON purchase_plan_campaign(tenant_id, start_date desc, id);
-- 调整留痕：按计划行读（详情页第二段）。
CREATE INDEX purchase_plan_adjustment_by_line ON purchase_plan_adjustment(tenant_id, plan_line_id, adjusted_at);
-- 锁定留痕：按计划行读（详情页第三段）。
CREATE INDEX purchase_plan_lock_log_by_line ON purchase_plan_lock_log(tenant_id, plan_line_id, requested_at);

-- ===========================================================================
-- 9. 多租户：RLS + 授权（与 trade V1 / V92 / V234 / V238 同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_plan_campaign','purchase_plan','purchase_plan_line',
    'purchase_plan_adjustment','purchase_plan_lock_log','purchase_plan_lock_policy'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_plan_campaign,purchase_plan,purchase_plan_line,
  purchase_plan_adjustment,purchase_plan_lock_log,purchase_plan_lock_policy TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_plan_campaign,purchase_plan,purchase_plan_line,
  purchase_plan_adjustment,purchase_plan_lock_log,purchase_plan_lock_policy TO starcon_app;
-- 收回两张留痕表的改写权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON purchase_plan_adjustment,purchase_plan_lock_log FROM trade_app;
-- 计划与计划行**不提供 DELETE**：计划是业务凭据（行调整 / 锁定留痕都指向它）。
-- 删除留痕的级联会抹掉「这行当时被调整过、被锁过」的证据。
REVOKE DELETE ON purchase_plan,purchase_plan_line FROM trade_app;
-- 大促日历可以删（租户自己维护的日历），但**被计划引用时删不掉**（FK ON DELETE RESTRICT）。

-- ---------------------------------------------------------------------------
-- 10. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V234 / V238 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不写 6.2.1.2 的表**：`replenishment_suggestion` / `replenishment_suggestion_batch`
--     只被 SELECT，本文件不 ALTER 它们、不加约束它们、不建跨表外键到它们
--     （那两张表的写入所有权是 6.2.1.2）。
--   * 对 warehouse / catalog / channel 的数据**只经授权 HTTP API 读写**：
--     跨库 FK 本来就禁止，所以本文件既没有约束到对方库的任何一张表。
--   * 新增函数全部带 purchase_plan_ 前缀，与 V1 / V25 / V92 / V234 / V238 的既有函数名不冲突。
