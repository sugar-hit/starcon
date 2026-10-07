-- ===========================================================================
-- 7.1.3.1 多仓分布视图：仓库性质维度 + 平台仓观测值 + SPU 引用登记
-- ===========================================================================
-- 蓝图原文：「把同一 SKU（或 SPU）在所有仓库的库存并列展示，含本地仓/海外仓/FBA/三方仓/
-- 在途」「**FBA 库存是平台仓观测值，不是自有账本**，需标注来源与采集时间」。
--
-- 本迁移只做三件「不建就做不出这个视图」的事，每件都对应蓝图里的一句话：
--
--   1. warehouse.kind           —— 让「本地仓/海外仓/FBA/三方仓」这个分类**真的存在于数据里**。
--   2. platform_stock_observation —— 让 FBA 那一行有**真实的数量来源**，且强制带来源与采集时间。
--   3. sku_ref.spu_id           —— 让输入能按 SPU 过滤（矩阵的输入是「SKU 或 SPU」）。
--
-- 刻意**不**做的事：
--   * 不建任何汇总/缓存表。矩阵每一格都能在同一批事实表上重算出来 —— 与 7.1.1.1
--     「总览只是聚合视图，不存独立数据」同一纪律。
--   * 不在 FBA 仓上写自有账本（见 guard_platform_warehouse_no_ledger）。
--   * 不动 stock_bucket / inventory_balance 的数量语义：多仓矩阵只是换个方向聚合它们。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 仓库性质：LOCAL / OVERSEAS / PLATFORM_FBA / THIRD_PARTY
-- ---------------------------------------------------------------------------
-- 为什么现在才加：V1 建 warehouse 时只有 country_code / timezone，无法回答「这一行是
-- 自营仓还是平台仓」——而这恰恰是 7.1.3.1 整张矩阵的**行分组轴**。没有它，FBA 行只能靠
-- 仓库编码前缀去猜，那不是分类，是约定外的东西，租户改个编码就错了。
--
-- warehouse 是本服务**自有**主数据（V1 第 2 节：「仓库主数据（本服务自有，可建外键）」），
-- 所以补这一列是在自己的域里补属性，不是复制上游的权威数据。
--
-- 为什么是受控词表而不是自由文本：矩阵要按它分组，还要用它决定「这行数字来自自有账本
-- 还是平台观测」，自由文本会出现拼写漂移导致同一类仓被拆成两组。CHECK 约束锁死取值。
--
-- 存量兼容：V1 已有仓库行，补列必须给 DEFAULT —— 存量行按 LOCAL 解释，因为 V1 建表时
-- 全部仓库都是按自有账本使用的（V1 没有任何平台仓概念）。改这一列会触发下面的
-- guard_platform_warehouse_no_ledger：把已有账本余额的仓改成 PLATFORM_FBA 会被 DB 拒绝，
-- 除非先清干净它的余额 —— 这正是「FBA 不是自有账本」该有的强制力。
ALTER TABLE warehouse
  ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'LOCAL'
    CHECK(kind IN ('LOCAL','OVERSEAS','PLATFORM_FBA','THIRD_PARTY'));

-- 矩阵的热读路径：按性质分组 + 按国家/编码排序。带 kind 前缀，GROUP BY kind 直接走索引。
CREATE INDEX warehouse_kind_list ON warehouse(tenant_id,kind,status,code);

-- ---------------------------------------------------------------------------
-- 2. DB 级强制：平台仓不得有自有账本余额
-- ---------------------------------------------------------------------------
-- 这是本迁移最要紧的一条。蓝图说 FBA 库存「不是自有账本」，如果只靠约定，迟早会出现
-- 一个既有 inventory_balance 又有 platform_stock_observation 的 FBA 仓 —— 于是
-- 「这批货到底有多少」在系统里有两个数，且两个数都能在页面上显示出来。
--
-- 在 DB 层挡住，比在服务层 if 一段要可靠：服务层只能挡住走本服务的写入，挡不住 SQL、
-- 迁移脚本和运维手工连接。
--
-- 检查 balance 与 bucket 两张表：bucket 是最小事实单位，光挡 balance 会出现
-- 「balance 干净但 bucket 有行」的缝（V2 的守恒触发器虽会延迟到提交时报错，但那时
-- 报错信息指向守恒而不是「平台仓不该有账本」，会让人查错方向）。
CREATE FUNCTION guard_platform_warehouse_no_ledger() RETURNS trigger AS $$
DECLARE k varchar(16);
BEGIN
  SELECT w.kind INTO k FROM warehouse w
   WHERE w.tenant_id=NEW.tenant_id AND w.id=NEW.warehouse_id;
  IF k='PLATFORM_FBA' THEN
    RAISE EXCEPTION '仓库 % 是平台仓（FBA）：平台仓库存是**观测值**，不能记入自有账本 %',
      NEW.warehouse_id, TG_TABLE_NAME
      USING ERRCODE='23514',
            HINT='平台仓数量请写入 platform_stock_observation（带来源与采集时间），不要写 balance/bucket';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_platform_balance_no_ledger
  BEFORE INSERT OR UPDATE OF warehouse_id ON inventory_balance
  FOR EACH ROW EXECUTE FUNCTION guard_platform_warehouse_no_ledger();
CREATE TRIGGER guard_platform_bucket_no_ledger
  BEFORE INSERT OR UPDATE OF warehouse_id ON stock_bucket
  FOR EACH ROW EXECUTE FUNCTION guard_platform_warehouse_no_ledger();

-- 反向也锁：已有账本余额的仓不能改成平台仓。少了这条，上面那个触发器只在「写入时」生效，
-- 而「把 LOCAL 仓直接 UPDATE 成 PLATFORM_FBA」就能把已有余额变成平台仓观测之外的孤儿账。
CREATE FUNCTION guard_platform_kind_change() RETURNS trigger AS $$
DECLARE n bigint;
BEGIN
  IF NEW.kind='PLATFORM_FBA' AND OLD.kind IS DISTINCT FROM 'PLATFORM_FBA' THEN
    SELECT count(*) INTO n FROM inventory_balance b
     WHERE b.tenant_id=NEW.tenant_id AND b.warehouse_id=NEW.id AND (b.on_hand<>0 OR b.reserved<>0);
    IF n>0 THEN
      RAISE EXCEPTION '仓库 % 仍有 % 条非零自有账本余额，不能改为平台仓（FBA）', NEW.id, n
        USING ERRCODE='23514',
              HINT='先把该仓库存清零并结清调拨在途，或保留为自有仓类型';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_warehouse_kind_change
  BEFORE UPDATE OF kind ON warehouse
  FOR EACH ROW EXECUTE FUNCTION guard_platform_kind_change();

-- ---------------------------------------------------------------------------
-- 3. 平台仓库存观测（FBA 数量的唯一来源）
-- ---------------------------------------------------------------------------
-- 10 号卷蓝图对外部仓的原话：「**外部仓库存是观测值不是自有账本**，必须与自营库存分开
-- 存储与对账，不能覆盖」。本表就是那个「分开存储」的地方：它与 inventory_balance /
-- stock_bucket 没有外键关系，两者在任何地方都不相加。
--
-- 为什么是 append-only 而不是 upsert 最新值：
--   * 「上次采集到多少」本身就是 7.2.2.2 判断平台断货风险的依据，覆盖掉就没有历史了；
--   * 观测值出错时应该补一条新观测让序列延续，而不是改掉历史 —— 与 7.1.2.1 流水台账
--     「错误用反向分录纠正，原记录永久保留」同一纪律；
--   * 矩阵取当前值时用 distinct on 取每个来源的最新一条，历史仍在表里可查。
--
-- source 与 collected_at 都是 NOT NULL 且无默认：蓝图点名「需标注来源与采集时间」。
-- 如果允许 NULL，FBA 行就会退化成一行没有来源、没有新鲜度的数字，比不显示更危险 ——
-- 用户会把它当成和自有账本一样确定的数。
CREATE TABLE platform_stock_observation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- 货权法人仍然要记：平台仓里的货货权属于本租户的某个法人，矩阵支持按法人过滤，
  -- 且海外仓账单（12 财务）要按法人归集。这里是引用登记，法人档案以 identity 为准。
  legal_entity_id uuid NOT NULL,
  -- 采集来源：'AMAZON_FBA' / 'SHOPIFY_3PL' 等，租户可扩展，故为受控但非封闭的短字符串。
  source varchar(32) NOT NULL,
  -- 采集时间：**观测值的发生时点**，由采集器给出，不由写入时刻冒充。
  -- 两者混同会让「数据太旧了」这种判断永远做不出来。
  collected_at timestamptz NOT NULL,
  -- 平台侧报出的数量。三者都可能为 0（缺货就是 0），但不允许 NULL —— 平台没报这个字段
  -- 说明采集协议没对上，写 0 是在撒谎。
  observed_on_hand numeric(19,4) NOT NULL DEFAULT 0 CHECK(observed_on_hand>=0),
  observed_reserved numeric(19,4) NOT NULL DEFAULT 0 CHECK(observed_reserved>=0),
  observed_available numeric(19,4) NOT NULL DEFAULT 0 CHECK(observed_available>=0),
  -- 发往该平台仓的入库在途：这是**平台报出来的**在途，与自有仓在途
  -- （inventory_transfer 中 status='IN_TRANSIT'）不是同一批货，不能混算。
  observed_inbound numeric(19,4) NOT NULL DEFAULT 0 CHECK(observed_inbound>=0),
  raw_reference varchar(128) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一来源在同一个时点只能有一条：重复投递（采集器重试）不会污染当前值取值，
  -- 而「同源同时点两条数量不同」在业务上没有意义。
  UNIQUE(tenant_id,warehouse_id,sku_id,source,collected_at),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(source)<>'')
);

-- 矩阵取当前值：每个 (仓库, SKU, 来源) 的最新一条。
CREATE INDEX platform_stock_observation_latest
  ON platform_stock_observation(tenant_id,sku_id,warehouse_id,source,collected_at DESC);
-- 观测历史差分算周转天数时按仓 + 时间扫。
CREATE INDEX platform_stock_observation_history
  ON platform_stock_observation(tenant_id,warehouse_id,sku_id,collected_at);

-- 观测值同样 append-only：复用 V1 的 guard_inventory_append_only，结论写错就补一条新的。
CREATE TRIGGER platform_stock_observation_append_only
  BEFORE UPDATE OR DELETE ON platform_stock_observation
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();

-- 只有平台仓允许观测自有账本之外的量吗？反过来锁：非平台仓写观测值同样是建模错误 ——
-- 自有仓的数量只有一个真相来源（balance/bucket），再记一份观测就是制造第二个数。
CREATE FUNCTION guard_observation_requires_platform_warehouse() RETURNS trigger AS $$
DECLARE k varchar(16);
BEGIN
  SELECT w.kind INTO k FROM warehouse w
   WHERE w.tenant_id=NEW.tenant_id AND w.id=NEW.warehouse_id;
  IF k<>'PLATFORM_FBA' THEN
    RAISE EXCEPTION '仓库 % 的性质是 %，不是平台仓：自有仓数量只有一个真相来源，请写 balance/bucket',
      NEW.warehouse_id, k
      USING ERRCODE='23514',
            HINT='platform_stock_observation 只用于平台仓（FBA）观测值';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_observation_platform_only
  BEFORE INSERT OR UPDATE ON platform_stock_observation
  FOR EACH ROW EXECUTE FUNCTION guard_observation_requires_platform_warehouse();

-- ---------------------------------------------------------------------------
-- 4. sku_ref 补 SPU 引用登记（矩阵的输入是「SKU 或 SPU」）
-- ---------------------------------------------------------------------------
-- 蓝图输入写的是「SKU 或 SPU」，但 V1 的 sku_ref 只有 id/code/name，没有任何 SPU 列。
-- 这里如实说明边界：**catalog 的 sku 表有 spu_id（catalog V1/V10），warehouse 的
-- sku_ref 没有**。两者分属不同服务，不能跨库 JOIN，也不能在 warehouse 里复制一份权威
-- SPU 档案 —— 那会出现两份「这个 SKU 属于哪个 SPU」各说各话。
--
-- 所以补的是**引用登记**：只登记 catalog 已判定的 spu_id 这一个逻辑 ID，与 sku_ref 现有的
-- id / code / name 快照同一性质（V1 文件头：「这里存的是 SKU 本身的逻辑 ID 与展示快照」）。
-- 权威仍在 catalog，warehouse 不据此做任何 SPU 属性决策。
--
-- 为什么允许 NULL：本迁移之前登记的 SKU 全部没有 SPU 归属（当时不需要这个维度）。
-- 存量行补 NULL 是**如实**（确实不知道），不是默认值糊过去。按 SPU 过滤时这些行不命中，
-- 与「这个 SKU 确实不属于该 SPU」在语义上确实无法区分 —— 这一点由矩阵响应的
-- skuScope.unregisteredSpuCount 如实报出，不在界面上假装筛选是完整的。
ALTER TABLE sku_ref ADD COLUMN spu_id uuid;
CREATE INDEX sku_ref_spu ON sku_ref(tenant_id,spu_id) WHERE spu_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. 多租户 + 授权
-- ---------------------------------------------------------------------------
-- 与 V1/V2 同口径：ENABLE + FORCE，策略列走 app.current_tenant（客户端头进不来）。
ALTER TABLE platform_stock_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_stock_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_stock_observation
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- 观测只追加：给 INSERT + SELECT，不给 UPDATE/DELETE（与 7.1.2.2 结论表同一纪律）。
GRANT SELECT,INSERT ON platform_stock_observation TO warehouse_app;
REVOKE UPDATE,DELETE ON platform_stock_observation FROM warehouse_app;
