-- ===========================================================================
-- 7.3.1.1 批次管理：补齐 inventory_lot 的批次档案属性
-- ===========================================================================
-- 蓝图原文：「管理批次（lot）：批号、生产日期、失效日期、来源采购单/供应商，
-- 支持按批次查询库存与流向追溯」，输入「批次建立（收货时生成或手工建）；批次属性；查询条件」，
-- 输出「批次档案 + 批次库存分布 + 出入库历史」。
--
-- ---------------------------------------------------------------------------
-- 为什么是"补列"而不是"另建批次档案表"
-- ---------------------------------------------------------------------------
-- V2 已经建了 inventory_lot，并且 stock_bucket 用
--   FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id)
-- 把批次作为一等事实单位接进了库存明细。**批次实体只有这一个**。
-- 若另建一张"批次档案表"再去关联 inventory_lot，同一个批次就会有两条互相竞争的真相：
-- 界面上显示的档案与明细里挂在库位上的批次可能对不上，而这种不一致极难被发现。
-- 因此本迁移**只补列**：把 V2 建模时留白的批次属性补齐，不引入第二张表。
--
-- 对照蓝图，V2 的 inventory_lot 已有 lot_code（批号）、expires_on（失效日期）、
-- received_on（收货日期）、status，缺的是下面这四项。
--
-- ---------------------------------------------------------------------------
-- 为什么本迁移不写 RLS、不写 GRANT（这是刻意结论，不是遗漏）
-- ---------------------------------------------------------------------------
-- 本迁移**没有新建任何表**，只 ALTER 已存在的 inventory_lot。inventory_lot 的租户隔离
-- 与授权在 V2 里已经坐实：
--   - ALTER TABLE ... ENABLE/FORCE ROW LEVEL SECURITY + CREATE POLICY isolation
--   - GRANT SELECT,INSERT,UPDATE,DELETE ON ... inventory_lot ... TO warehouse_app
--   - REVOKE ALL ON ... FROM starcon_app（跨服务只读角色拿不到）
-- ALTER TABLE ADD COLUMN 不会使既有策略失效，新增列自动落在同一条 USING/WITH CHECK 里。
-- 集成测试实跑三断言（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户，
-- 池压到 1）证明这一点，不是靠推断。
--
-- ===========================================================================

ALTER TABLE inventory_lot
  -- 生产日期。**业务日期不是时间戳**：蓝图说的是"生产日期"，它是产线写死在包装上的事实，
  -- 与"这批货什么时候入库系统"（received_on / created_at）不是同一件事，
  -- 也不能用 timestamptz 硬套 —— 那会让"哪天生产的"随服务器时区漂一天。
  ADD COLUMN IF NOT EXISTS produced_on date,

  -- 建立方式：RECEIPT = 收货时生成（由 8 收货调用），MANUAL = 手工建。
  -- 固化成列而不是从 source_system 反推：手工补登一个批次时也会带来源单据，
  -- 那时"它是收货生成的还是人建的"是两条不同的信息，不能互相替代。
  ADD COLUMN IF NOT EXISTS origin varchar(16) NOT NULL DEFAULT 'MANUAL'
    CHECK(origin IN ('RECEIPT','MANUAL')),

  -- 来源采购单/供应商：**跨服务定位键，不是外键**。
  -- 采购(6) 与收货(8) 不在本服务，AGENTS.md 明令禁止跨服务建外键或查它们的表。
  -- source_system + source_ref_id 是跨服务引用的标准写法（与 inventory_transaction.ref_type/ref_id
  -- 同一套约定）：本服务只存"这批货来自哪张单子的哪个 id"，单据本身由属主服务解释。
  -- source_ref_code 固化下单号字符串，是为了让界面在**不联查采购服务**的前提下也能读出
  -- "来自哪张单"—— 排障时不该为了看一个单号去调另一个服务。
  ADD COLUMN IF NOT EXISTS source_system varchar(32),
  ADD COLUMN IF NOT EXISTS source_ref_id uuid,
  ADD COLUMN IF NOT EXISTS source_ref_code varchar(64) NOT NULL DEFAULT '',

  -- 供应商：同上，跨服务逻辑 ID + 名称快照。supplier_name 会被订单列表直接展示，
  -- 固化的代价是供应商改名后旧批次仍显示旧名 —— 刻意接受：批次档案是**发生时的记载**，
  -- 显示"这批货当时是向谁买的"比显示供应商的当前名字更符合追溯用途。
  ADD COLUMN IF NOT EXISTS supplier_ref_id uuid,
  ADD COLUMN IF NOT EXISTS supplier_name varchar(128) NOT NULL DEFAULT '',

  -- 批次备注：收货质检结论、退运原因等不属于结构化字段的补充说明。
  ADD COLUMN IF NOT EXISTS notes text NOT NULL DEFAULT '';

-- ---------------------------------------------------------------------------
-- 约束（对既有行同样安全）
-- ---------------------------------------------------------------------------
-- 收货生成的批次必须能指回来源单据，否则"这批货是怎么来的"就断了 —— 而这正是批次追溯的起点。
-- 手工建的批次允许没有来源（运营补登历史批次就是这种情况），所以只约束 RECEIPT 一侧。
DO $$ BEGIN
  ALTER TABLE inventory_lot DROP CONSTRAINT IF EXISTS ck_lot_origin_source;
  ALTER TABLE inventory_lot
    ADD CONSTRAINT ck_lot_origin_source
    CHECK(origin<>'RECEIPT' OR (source_system IS NOT NULL AND source_ref_id IS NOT NULL));
END $$;

-- 生产日期不得晚于失效日期：写反的效期会让 7.3.1.2 的 FEFO 推荐排出完全错误的顺序。
-- 两列任一为空都放行（无保质期品没有生产日期是合法的），三列皆空时 CHECK 结果为 NULL 也放行。
DO $$ BEGIN
  ALTER TABLE inventory_lot DROP CONSTRAINT IF EXISTS ck_lot_produced_before_expiry;
  ALTER TABLE inventory_lot
    ADD CONSTRAINT ck_lot_produced_before_expiry
    CHECK(produced_on IS NULL OR expires_on IS NULL OR expires_on>=produced_on);
END $$;

-- source_system 取值词表：只允许本服务认识的上游来源系统。
-- 刻意**不**做成外键或跨表引用 —— source_system 是字符串协议，不是本库的一张表。
DO $$ BEGIN
  ALTER TABLE inventory_lot DROP CONSTRAINT IF EXISTS ck_lot_source_system;
  ALTER TABLE inventory_lot
    ADD CONSTRAINT ck_lot_source_system
    CHECK(source_system IS NULL OR source_system IN ('PURCHASE','RECEIPT','MANUAL'));
END $$;

-- ---------------------------------------------------------------------------
-- 索引：只为本模块的**真实查询**建，不为"将来可能查"建
-- ---------------------------------------------------------------------------
-- 批次列表的默认排序是「按 SKU → 批号」（V2 的 inventory_lot_list 已覆盖），无需重复建设。
-- 下面两个索引对应蓝图"查询条件"里两条真实存在的查询路径：

-- 1) 按失效日期取批���（效期视图：哪些批次快到期 / 已过期）。部分索引只收有失效日期的行，
--    非效期品不占索引空间 —— 效期品在库存品里永远是少数。
CREATE INDEX IF NOT EXISTS inventory_lot_expiry
  ON inventory_lot(tenant_id, expires_on, sku_id) WHERE expires_on IS NOT NULL;

-- 2) 从采购单/收货单反查批次。**这是追溯的入口方向**：售后拿着一张采购单号来问
--    "这批货现在在哪些库位"，走的正是 source_system + source_ref_id 的等值查找。
CREATE INDEX IF NOT EXISTS inventory_lot_source
  ON inventory_lot(tenant_id, source_system, source_ref_id) WHERE source_ref_id IS NOT NULL;

-- 按供应商反查批次（供应商质量追溯：某供应商出的货还有哪些在库）。
CREATE INDEX IF NOT EXISTS inventory_lot_supplier
  ON inventory_lot(tenant_id, supplier_ref_id) WHERE supplier_ref_id IS NOT NULL;
