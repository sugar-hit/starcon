-- 7.1.1.2 库存明细：bucket 事实层（库位 × 批次粒度）。
--
-- ---------------------------------------------------------------------------
-- 分层：为什么 bucket 是**新表**，不是给 inventory_balance 加两列
-- ---------------------------------------------------------------------------
-- V1 的文件头把一条设计纪律写死了：
--   「键**不含** location_id —— 履约的可用量决策单位是仓库不是库位，
--     把库位放进键里就得 SUM 后再 UPDATE，重新引入『先读余量再无锁写回』的竞态。」
-- 而 7.1.1.2 又把最小事实单位定义为
--   「法人货权 × SKU × 仓库 × **库位** × **批次** × 质量状态」。
-- 这两条同时为真，唯一自洽的解是**分层**，而不是改 balance 的键：
--
--   inventory_balance  仓库 × 货权 × SKU × 质量状态 → 可用量决策单位（不变）
--   stock_bucket       上面的六个维度 + 库位 + 批次 → 最小事实单位（新增）
--
-- 若反过来把 location_id / lot_id 塞进 inventory_balance 的唯一键，可用量的
-- 预占就变成「SUM 后再 UPDATE」——正是 V1 拒绝掉的那个竞态，而且要推翻 V1 已落库的
-- 生成列口径与 guard_balance_reservable 触发器。本迁移不推翻它。
--
-- ---------------------------------------------------------------------------
-- 守恒线：bucket 汇总必须能核对回 balance（DB 级，不是靠约定）
-- ---------------------------------------------------------------------------
-- 口径（在下方 guard_bucket_conservation 里被强��执行）：
--   对每一条 balance 行，Σ stock_bucket.on_hand   == balance.on_hand
--                    Σ stock_bucket.reserved == balance.reserved
--
-- 两条 DB 级防线：
--   1. 复合外键：bucket 的 (法人, SKU, 仓库, 质量状态) 必须与它的 balance 行**逐列相同**。
--      一行 bucket 不可能声称自己属于另一个仓或另一个 SKU。
--   2. **延迟约束触发器**（DEFERRABLE INITIALLY DEFERRED）：在同一事务提交前核对
--      Σ bucket == balance。用延迟而不是即时触发器，是因为一次库存业务本来就要
--      「先写 balance 再拆 bucket」，即时触发会在事务中途误判。
--
-- 「已分解」的判定：balance 行至少有一条 bucket 时才受守恒约束。
-- 没有任何 bucket 的 balance 行处于 7.1.1.1 的仓库级口径（还没拆到库位），
-- 不受约束 —— 这是刻意留的合法状态，不是漏洞。但**最后一条 bucket 也不允许删除**
-- （guard_bucket_no_delete）：删掉它会让余额悄悄退回「未分解」状态，核对线随之失效。
--
-- ---------------------------------------------------------------------------
-- 库位与批次维度的归属边界（不越界到 08 / 7.3）
-- ---------------------------------------------------------------------------
--   warehouse_location  库位档案。本迁移只建 bucket 需要、且明细页要显示的属性：
--                        所属仓库、编码、名称、类型（存储/收货/发货/待检/残次/冻结）、
--                        是否可拣。**库位树（层级、防环）与自动分配属 08.1.2.x，不在本迁移**，
--                        也不预留 parent_id 之类的半截列 —— 8.1.2.1 落地时再 ALTER。
--   inventory_lot       批次档案。批号 + SKU + 效期 + 收货日 + 状态，是「什么批次」这个问题
--                        所需的最小真实集合。**FEFO/FIFO 分配、临期预警属 7.3.1.2**，
--                        本迁移不实现任何分配策略，只如实展示效期。
--   sku_id 是逻辑 ID，不建外键（sku 属 catalog，跨服务 FK 违反模块边界纪律，同 V1）。
--
-- ---------------------------------------------------------------------------
-- 数量与时间口径
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,4) + Java BigDecimal，全程禁浮点（与 V1 同口径）。
--   available 同样是 GENERATED ALWAYS AS (on_hand - reserved) STORED：没有第三个来源。
--   last_movement_at 是**业务时间**（最近一次库存变动的发生时间，由库存业务写入方提供）；
--   updated_at / created_at 是**系统时间**（DB 维护）。两者分开存，不混用。
--   version 是乐观锁：每次数量变动 +1，由 DB 触发器强制，调用方无需自记 ——
--   这是 7.1.2.1 流水 / 7.4.1.1 调整 / 7.5.1.1 调拨后续并发写入的基础设施。

-- ===========================================================================
-- 1. 库位档案（bucket 的库位维度；库位树属 08.1.2.x）
-- ===========================================================================
CREATE TABLE warehouse_location (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  code varchar(64) NOT NULL, name varchar(200) NOT NULL,
  -- 类型取自 08 卷 8.1.2.1 的库位类型词表。待检/残次/冻结库位天然不可拣。
  type varchar(16) NOT NULL DEFAULT 'STORAGE'
    CHECK(type IN ('STORAGE','RECEIVING','SHIPPING','QUARANTINE','DEFECTIVE','FROZEN')),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  -- 是否可拣：可售量计算（7.1.1.3）要把不可拣库位的数量排除在可发货之外，
  -- 所以这个属性是**要展示给人看的**（「为什么这个库位发不出去」），不是装饰列。
  pickable boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- bucket 的复合外键要按 (租户,仓库,库位) 定位，所以这一组也要唯一。
  UNIQUE(tenant_id,warehouse_id,id),
  UNIQUE(tenant_id,warehouse_id,code),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
CREATE INDEX warehouse_location_list ON warehouse_location(tenant_id,warehouse_id,status,code);

-- ===========================================================================
-- 2. 批次档案（bucket 的批次维度；FEFO 与效期策略属 7.3.1.2）
-- ===========================================================================
CREATE TABLE inventory_lot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- SKU 是 catalog 的逻辑 ID：不建外键（见文件头）。
  sku_id uuid NOT NULL,
  lot_code varchar(64) NOT NULL,
  -- 业务日期（不是系统时间）：效期与收货日本身就是业务事实。
  expires_on date,
  received_on date,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DEPLETED','EXPIRED')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,sku_id,lot_code),
  CHECK(btrim(lot_code)<>'')
);
CREATE INDEX inventory_lot_list ON inventory_lot(tenant_id,sku_id,status,lot_code);

-- ===========================================================================
-- 3. bucket 事实表（库存的最小事实单位）
-- ===========================================================================
-- 先给 balance 加一条覆盖 bucket 六个维度的唯一约束，作为复合外键的目标：
-- 这样「bucket 声称自己属于哪个仓/哪个 SKU/哪个质量状态」由数据库裁决，
-- 而不是由应用层保证一致。
ALTER TABLE inventory_balance
  ADD CONSTRAINT uq_balance_bucket_scope
  UNIQUE(tenant_id,id,legal_entity_id,sku_id,warehouse_id,quality_status_id);

CREATE TABLE stock_bucket (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 六个维度。legal_entity / sku / warehouse / quality_status 与 balance 逐列相同
  -- （复合外键强制），location / lot 是本表新增的两级下钻维度。
  legal_entity_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  location_id uuid NOT NULL,
  -- lot_id 可空：非批次管理品就没有批次。UNIQUE NULLS NOT DISTINCT 才能把
  -- 「无批次」也纳入唯一性 —— 用普通 UNIQUE 的话，同一库位能插出任意多条无批次 bucket，
  -- 于是「这个货位上这批货到底是多少」变成多行之和，正是明细页要消灭的歧义。
  lot_id uuid,
  quality_status_id uuid NOT NULL,
  -- 指回仓库级的可用量决策单位。**不可改指**：这六个维度已经唯一决定了是哪条
  -- balance 行，改 balance_id 只会被下面的复合外键挡下。
  balance_id uuid NOT NULL,
  on_hand numeric(19,4) NOT NULL DEFAULT 0,
  reserved numeric(19,4) NOT NULL DEFAULT 0,
  available numeric(19,4) GENERATED ALWAYS AS (on_hand - reserved) STORED,
  version bigint NOT NULL DEFAULT 0,
  -- 业务时间：最近一次数量变动的发生时间，由库存业务写入方提供（见 guard_bucket_movement_time）。
  last_movement_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 蓝图 7.1.1.2 的最小事实单位：一个六元组就是一行。
  UNIQUE NULLS NOT DISTINCT(
    tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,lot_id,quality_status_id),
  FOREIGN KEY(tenant_id,balance_id,legal_entity_id,sku_id,warehouse_id,quality_status_id)
    REFERENCES inventory_balance(tenant_id,id,legal_entity_id,sku_id,warehouse_id,quality_status_id),
  FOREIGN KEY(tenant_id,warehouse_id,location_id) REFERENCES warehouse_location(tenant_id,warehouse_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  CHECK(on_hand>=0),
  CHECK(reserved>=0),
  -- 超卖的 DB 级兜底（与 V1 的 ck_balance_available 同一道防线）：
  -- 一个库位上的可用被算成负数，说明预占分配越界了。
  CHECK(available>=0)
);

-- ===========================================================================
-- 4. bucket 的 DB 级行为约束
-- ===========================================================================

-- 4.1 预占只能落在可售质量状态上（照抄 V1 的 guard_balance_reservable 同一口径）。
-- DEFECTIVE / PENDING_INSPECTION 的货一旦被预占，available 会算成「可发货」。
CREATE FUNCTION guard_bucket_reservable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r varchar(20);
BEGIN
 IF NEW.reserved<=0 THEN RETURN NEW; END IF;
 SELECT q.role INTO r FROM inventory_quality_status q
  WHERE q.tenant_id=NEW.tenant_id AND q.id=NEW.quality_status_id;
 IF r<>'SELLABLE' THEN
  RAISE EXCEPTION '质量状态「%」的 bucket 不可预占：预占会把非可售数量算成可发货', r
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_bucket_reservable BEFORE INSERT OR UPDATE ON stock_bucket
 FOR EACH ROW EXECUTE FUNCTION guard_bucket_reservable();

-- 4.2 数量变动 = 一次库存变动：version 自增 + 业务时间必须前进。
--
-- 为什么业务时间要**强制**而不是任其自填：明细页要显示「最近变动时间」，
-- 而这个时间只能来自库存业务单（V1 的 inventory_transaction.occurred_at）。
-- 如果允许写入方忘了带业务时间、最近变动时间就永远停在建行时刻 —— 那是一个
-- 看起来正常、实际骗人的时间戳。这里让 DB 挡住它，错误信息直接告诉调用方怎么写。
--
-- updated_at 是系统时间，与业务时间分开存，任何 UPDATE 都推进。
CREATE FUNCTION guard_bucket_movement_time() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.updated_at := now();
 IF NEW.on_hand IS DISTINCT FROM OLD.on_hand OR NEW.reserved IS DISTINCT FROM OLD.reserved THEN
   IF NEW.last_movement_at <= OLD.last_movement_at THEN
     RAISE EXCEPTION 'bucket 数量发生变动，last_movement_at 必须晚于原值（原 %，新 %）：'
       '「最近变动时间」只能来自库存业务的发生时间，不能留旧值',
       OLD.last_movement_at, NEW.last_movement_at USING ERRCODE='23514';
   END IF;
   NEW.version := OLD.version + 1;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_bucket_movement_time BEFORE UPDATE ON stock_bucket
 FOR EACH ROW EXECUTE FUNCTION guard_bucket_movement_time();

-- 4.3 bucket 行不删除（与 inventory_balance 同一口径：事实行保留，归零表示已清空）。
-- 少了这条，删掉某条 balance 的最后一条 bucket 会让它悄悄退回「未分解」状态，
-- 守恒式随之中断且无人察觉。
CREATE FUNCTION guard_bucket_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'stock_bucket 事实行不允许删除：清空请把 on_hand 与 reserved 归零。'
   '删除会让余额退回「未分解到库位」状态，账实核对线随之失效。' USING ERRCODE='23514';
END $$;
CREATE TRIGGER stock_bucket_no_delete BEFORE DELETE ON stock_bucket
 FOR EACH ROW EXECUTE FUNCTION guard_bucket_no_delete();

-- 4.4 守恒：bucket 侧（严格）—— 只要动过 bucket，就必须在同一事务里把 balance 对平。
--
-- 用延迟约束触发器而不是即时触发器：一次库存业务本来就是
-- 「写 balance → 拆 bucket → 写流水分录」，即时触发会在事务中途对着半成品报差异。
CREATE FUNCTION guard_bucket_conservation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t uuid; b uuid; expected record; actual record;
BEGIN
 -- DELETE 行触发器里 NEW 是未赋值记录，直接取 NEW.tenant_id 会抛
 -- 「record new is not assigned yet」。按 TG_OP 分流，两个分支的 balance_id 都取得到。
 IF TG_OP='DELETE' THEN
  t := OLD.tenant_id;
  b := OLD.balance_id;
 ELSE
  t := NEW.tenant_id;
  b := NEW.balance_id;
 END IF;
 SELECT bal.on_hand, bal.reserved INTO expected FROM inventory_balance bal
  WHERE bal.tenant_id=t AND bal.id=b;
 SELECT coalesce(sum(k.on_hand),0) AS on_hand, coalesce(sum(k.reserved),0) AS reserved
  INTO actual FROM stock_bucket k WHERE k.tenant_id=t AND k.balance_id=b;
 IF actual.on_hand IS DISTINCT FROM expected.on_hand
    OR actual.reserved IS DISTINCT FROM expected.reserved THEN
  RAISE EXCEPTION '库位明细与余额不平：balance % 的在手/预占是 %/%，其 bucket 汇总是 %/%。'
    'bucket 是库存最小事实单位，不允许出现解释不了的差额',
    b, expected.on_hand, expected.reserved, actual.on_hand, actual.reserved
    USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER bucket_conservation
 AFTER INSERT OR UPDATE OR DELETE ON stock_bucket
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_bucket_conservation();

-- 4.5 守恒：balance 侧（跳过未分解行）—— 改 balance 也必须同步 bucket。
-- 只改了 balance 没拆 bucket，汇总数就会与明细页对不上，而明细页正是查差异的地方。
CREATE FUNCTION guard_balance_bucket_conservation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE decomposed boolean; expected record; actual record;
BEGIN
 SELECT EXISTS(SELECT 1 FROM stock_bucket k
               WHERE k.tenant_id=NEW.tenant_id AND k.balance_id=NEW.id) INTO decomposed;
 IF NOT decomposed THEN RETURN NULL; END IF;   -- 未分解到库位的行不受约束（见文件头）
 SELECT bal.on_hand, bal.reserved INTO expected FROM inventory_balance bal
  WHERE bal.tenant_id=NEW.tenant_id AND bal.id=NEW.id;
 SELECT coalesce(sum(k.on_hand),0) AS on_hand, coalesce(sum(k.reserved),0) AS reserved
  INTO actual FROM stock_bucket k
  WHERE k.tenant_id=NEW.tenant_id AND k.balance_id=NEW.id;
 IF actual.on_hand IS DISTINCT FROM expected.on_hand
    OR actual.reserved IS DISTINCT FROM expected.reserved THEN
  RAISE EXCEPTION '库位明细与余额不平：balance % 的在手/预占是 %/%，其 bucket 汇总是 %/%。'
    '改余额必须同时改库位明细', NEW.id, expected.on_hand, expected.reserved,
    actual.on_hand, actual.reserved USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER balance_bucket_conservation
 AFTER UPDATE ON inventory_balance
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_balance_bucket_conservation();

-- ===========================================================================
-- 5. 明细页热读路径的索引（逐级下钻：法人 → 仓库 → 库位 → SKU → 批次）
-- ===========================================================================
-- 下钻最常见的一跳是「选中一个仓，看它每个库位上有什么」：
CREATE INDEX stock_bucket_location ON stock_bucket(tenant_id,warehouse_id,location_id,sku_id);
-- 反向的一跳是「这个 SKU 在哪些库位上」：
CREATE INDEX stock_bucket_sku ON stock_bucket(tenant_id,sku_id,warehouse_id,location_id);
-- 按批次追溯（11 售后、7.3.1.2 效期都会走这条）：
CREATE INDEX stock_bucket_lot ON stock_bucket(tenant_id,lot_id) WHERE lot_id IS NOT NULL;
-- 守恒核对与余额下钻：按 balance 取它的全部 bucket。
CREATE INDEX stock_bucket_balance ON stock_bucket(tenant_id,balance_id);

-- ===========================================================================
-- 6. 多租户：RLS + 授权（与 V1 同一套写法）
-- ===========================================================================
-- 三张新表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['warehouse_location','inventory_lot','stock_bucket'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON warehouse_location, inventory_lot, stock_bucket TO warehouse_app;
-- bucket 事实行不删除：连运行角色都不给 DELETE，兜底在权限层而不只在触发器里。
REVOKE DELETE ON stock_bucket FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 同口径）。
REVOKE ALL ON warehouse_location, inventory_lot, stock_bucket FROM starcon_app;
