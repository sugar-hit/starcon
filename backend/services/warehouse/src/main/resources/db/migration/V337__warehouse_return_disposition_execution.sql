-- 11.2.3.2 转可售/残次/报废 —— **warehouse 侧执行**（L3 11.2.3 退货处置的第三格）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：按质检结论把退货转入对应状态：可再销售转可售库存、残次转残次库存（可用于清仓）、
--         报废则核销并计入损耗。
--   输出：库存状态变更分录；可售量更新；报废损耗记录。
--   关系：下游 = 7.1.1.3 可用量、5.4 调价（残次品清仓）、12 财务（损耗）。
--         **报废是不可逆的**，需审批与原因，不能由客服随手处理。
--
-- ===========================================================================
-- 一、为什么执行落在 warehouse（而不是 trade）
-- ===========================================================================
--
-- 「库存状态变更分录」与「可售量更新」这两个输出项的事实归属在 warehouse：
--   stock_bucket / inventory_balance / inventory_entry / inventory_transaction
--   都是 warehouse 库里的表，**每一张事实表只有一个写入所有者**
--   （AGENTS.md「模块边界纪律」：跨服务禁止 SQL / FK / 共享视图）。
--
-- ⇒ **写库存的那一步只能在 warehouse 侧。** trade 侧拥有处置决定、审批与报废损耗记录
--   （V337__trade_return_inventory_disposition.sql），经**授权 HTTP** 把执行命令送到本服务；
--   本服务在自己的库里真正改库存，并把回执（分录 id、目标 bucket、可售量前后值）交回去。
--
-- ⚠️ **租户不由此端口传递**：转发的是**调用方自己的** Authorization bearer，
--   本服务仍按已认证成员关系解析租户并强制 RLS —— 隔离是**两道**
--   （warehouse 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
--
-- ===========================================================================
-- 二、本表是「执行回执」，**不是**第二张库存流水表
-- ===========================================================================
--
-- ⚠️ 照抄 7.4.1.1 调整单（V260）文件头的纪律：
--   「**绝不另建一张「调整流水表」**：7.1.2.1 库存流水台账读的就是 inventory_entry，
--   分录一落库流水自然可见；另建一张就是同一件事的第二个事实源，账实两套口径。」
--
-- ⇒ 本表**不存数量事实**：数量分录一律写 inventory_transaction（头）+ inventory_entry（带符号分录），
--   那才是库存台账读的那一份。本表只存**执行回执与因果链**：
--   哪张处置单（trade 的逻辑 ID + 单号快照）导致了这笔库存变动、
--   从哪个 bucket 挪到哪个 bucket、执行前后的可售量是多少。
--   幂等靠 UNIQUE(tenant_id, disposition_id)：**一条处置单只能执行一次**
--   （报废不可逆；重复提交必须幂等而不是把货扣两次）。
--
-- ===========================================================================
-- 三、报废为什么是「减库存」而不是「挪到残次 bucket」
-- ===========================================================================
--
-- warehouse 的质量状态词表 inventory_quality_status.role 只有三值：
--   SELLABLE / PENDING_INSPECTION / DEFECTIVE（V1 建表时钉死）。
-- **没有 SCRAP**：报废的货已经核销，物理上不在库位上了，
--   往一个「报废 bucket」里记数量，等于让这批货永远占着一个库位。
-- ⇒ 报废 = 从待检 bucket 减掉 + 记一笔损耗；可售 / 残次 = 换质量状态留在原库位
--   （**本模块只改质量状态，不改库位**：移库属 7.5.1.1 调拨）。
--
-- ===========================================================================
-- 四、数量与时间口径
-- ===========================================================================
--   数量 NUMERIC(19,4)（照抄既有 bucket / balance 口径，不另立新口径）+ Java BigDecimal，禁浮点。
--   时间 timestamptz：occurred_at 是**业务时间**（由调用方提供，不写 now() 糊弄），
--   created_at 是系统时间，两者分开存不混用。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 一、执行回执（因果链 + 幂等锚点）
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_return_disposition_execution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：RDE + 序列（租户内唯一）。
  execution_no varchar(32) NOT NULL,

  -- ---- 上游：trade 的处置决定单（跨服务 ⇒ 只有逻辑 ID + 单号快照，不建 FK）----
  disposition_id uuid NOT NULL,
  disposition_no varchar(32) NOT NULL DEFAULT '',
  instruction_id uuid NOT NULL,
  inspection_id uuid NOT NULL,

  target_inventory_state varchar(16) NOT NULL CHECK(target_inventory_state IN (
      'SELLABLE','DEFECTIVE','SCRAP')),

  -- ---- 标的物快照 ----
  sku_id uuid NOT NULL,
  sku_code varchar(128) NOT NULL DEFAULT '',
  sku_name varchar(300) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  qty numeric(19,4) NOT NULL CHECK(qty > 0),

  -- ---- 来源 bucket（待检）----
  source_bucket_id uuid NOT NULL,
  source_balance_id uuid NOT NULL,
  source_legal_entity_id uuid NOT NULL,
  source_warehouse_id uuid NOT NULL,
  source_location_id uuid NOT NULL,
  source_lot_id uuid,
  source_quality_status_id uuid NOT NULL,
  source_quality_status_code varchar(64) NOT NULL DEFAULT '',

  -- ---- 目标 bucket（报废为空：核销掉的货不在任何库位上）----
  target_bucket_id uuid,
  target_balance_id uuid,
  target_quality_status_id uuid,
  target_quality_status_code varchar(64) NOT NULL DEFAULT '',

  -- **库存状态变更分录**的表头 id：inventory_transaction.kind='ADJUSTMENT'、
  -- ref_type='RETURN_DISPOSITION'，分录本体在 inventory_entry（库存台账读的那一份）。
  inventory_transaction_id uuid NOT NULL,

  -- 可售量前后值（输出句「可售量更新」的口径证据）：
  -- 只认 role='SELLABLE' ∧ 库位可拣 ∧ 库位启用（与 7.1.1.3 AvailableQuantityService 同口径）。
  sellable_quantity_before numeric(19,4) NOT NULL,
  sellable_quantity_after numeric(19,4) NOT NULL,

  -- 业务时间（调用方提供）+ 系统时间。
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  executed_by uuid NOT NULL,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,execution_no),
  -- **幂等锚点**：一条处置单只执行一次（报废不可逆 + 重复提交不能把货扣两次）。
  UNIQUE(tenant_id,disposition_id),

  -- 报废没有目标 bucket；有目标状态（非报废）必须有目标 bucket 与余额行。
  CHECK((target_inventory_state = 'SCRAP'
         AND target_bucket_id IS NULL AND target_balance_id IS NULL
         AND target_quality_status_id IS NULL)
     OR (target_inventory_state <> 'SCRAP'
         AND target_bucket_id IS NOT NULL AND target_balance_id IS NOT NULL
         AND target_quality_status_id IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- 二、索引
-- ---------------------------------------------------------------------------
-- ① 按处置单反查（幂等命中 + 因果链回放）。
CREATE INDEX inventory_return_disposition_execution_by_disposition
  ON inventory_return_disposition_execution(tenant_id, disposition_id);
-- ② 按 SKU 追溯：这批货被处置过几次、分别判成什么。
CREATE INDEX inventory_return_disposition_execution_by_sku
  ON inventory_return_disposition_execution(tenant_id, sku_id, occurred_at);
-- ③ 报废口径统计（损耗报表与索赔举证）。
CREATE INDEX inventory_return_disposition_execution_by_scrap
  ON inventory_return_disposition_execution(tenant_id, occurred_at)
  WHERE target_inventory_state = 'SCRAP';
-- ④ 按库存分录反查（库存台账 → 执行回执）。
CREATE INDEX inventory_return_disposition_execution_by_transaction
  ON inventory_return_disposition_execution(tenant_id, inventory_transaction_id);

CREATE SEQUENCE inventory_return_disposition_execution_no_seq;
GRANT USAGE,SELECT ON SEQUENCE inventory_return_disposition_execution_no_seq TO warehouse_app;

-- ===========================================================================
-- 三、多租户：RLS + 授权
-- ===========================================================================
-- 本表 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：执行回执决定这批货**以什么身份重新进入销售**，
--   而「谁批的报废」是供应商索赔与平台申诉的举证材料。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'inventory_return_disposition_execution'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  inventory_return_disposition_execution TO warehouse_app;

-- 执行回执是**事实的账**：已执行的那一笔不可改（改了就是伪造一笔执行过的库存变动）。
-- 只有「失败原因」这种执行前就写好的字段允许在同一次执行里补齐。
REVOKE UPDATE,DELETE ON inventory_return_disposition_execution FROM warehouse_app;

-- ===========================================================================
-- 四、本迁移**零 ALTER 既有表**
-- ===========================================================================
-- stock_bucket / inventory_balance / inventory_entry / inventory_transaction /
-- inventory_quality_status / warehouse_location / inventory_lot 全部**只读 + 按既有口径写入**，
-- 与本批其它车道在 warehouse 的 DDL 回归面 = 0。
