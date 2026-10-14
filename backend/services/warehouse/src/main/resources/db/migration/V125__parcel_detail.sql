-- ===========================================================================
-- V125 · 10.2.2.1 包裹详情（warehouse 服务）
-- ===========================================================================
--
-- 蓝图 10-logistics-and-freight.md 第 146–151 行原文：
--   ### 10.2.2.1 包裹详情
--   - 职责：单包裹视图：包裹内 SKU 与数量（parcel_item）、订单行对应关系、重量体积、
--         面单、轨迹、交运信息。
--   - 输入：parcel_id；关联订单行与轨迹。
--   - 输出：包裹详情 + 操作入口。
--   - 关系：上游 = 8.3.3.2；下游 = 10.3.1.1、12 财务（运费成本归集）。
--     **parcel_item 与订单行的对应关系是成本归集到行的基础**，缺了就只能算到订单级。
--
-- ---------------------------------------------------------------------------
-- 1. 本迁移落在哪个服务、为什么（这是本模块最容易做错的地方）
-- ---------------------------------------------------------------------------
--
-- 蓝图卷 10 写「主责服务 = channel + trade + warehouse」，那三个字说的是
-- **谁提供物流能力**，不是「单包裹视图落在哪个服务」。
--
-- 本模块是**对 parcel / parcel_item 的只读详情视图**，而这两张表由上游
-- 8.3.3.2 打包与称重（**warehouse 服务**，V121）拥有：
--     CREATE TABLE parcel (...);      -- 含 ENABLE/FORCE ROW LEVEL SECURITY
--     CREATE TABLE parcel_item (...);
--
-- AGENTS.md「模块边界纪律」明令：跨服务禁止直读对方的表、跨库 FK 或视图。
-- 所以「把详情查询原样写进 channel、然后连 warehouse 的库 SELECT」是违规的。
-- **表所有者的读模型只能长在表所有者自己的服务里** → 本迁移、端点、页面全落 warehouse。
--
-- ---------------------------------------------------------------------------
-- 2. 蓝图那句点名要求怎么落地（核心）
-- ---------------------------------------------------------------------------
--
-- 「**parcel_item 与订单行的对应关系是成本归集到行的基础，缺了就只能算到订单级。**」
--
-- 先把「订单行」这个词在本仓库里的两个含义分清，否则会建错外键：
--
--   * V121 的 parcel_item 已经有 outbound_line_id → outbound_order_line。
--     那是**出库单行**（warehouse 自己的计划快照）。它是发货链路上的真实关联，
--     但**不是财务归集要的那一行**：运费要摊到买家下的**销售订单行**上，
--     否则 12 财务能回答的只有「这一单运费多少」，回答不了「第 2 行这件货
--     分摊了多少运费」—— 那正是蓝图说的「只能算到订单级」。
--
--   * 销售订单行 sales_order_line 属 **trade 服务**（V99）。跨服务不建 FK，
--     warehouse 手上也没有 trade 的 uuid（outbound_order 只有 source_external_ref
--     这个订单号**字符串**）。
--
-- 所以本迁移补的是**缺的那一环**：一张
--     parcel_item_sales_order_line
-- 把每条包裹明细项 **显式** 关联到 trade 的销售订单行，携带
-- 「这一箱里分给这一行的件数」与匹配口径。
--
-- **刻意建成表而不是加一列**：加一列只能存一个 uuid，答不出「按什么口径匹配上的」
-- 与「这一行分到几件」；而这两条正是 12 财务敢不敢拿它分摊的依据。
--
-- ---------------------------------------------------------------------------
-- 3. 匹配口径：靠 SKU 对，不靠猜
-- ---------------------------------------------------------------------------
--
-- outbound_order_line 与 sales_order_line **两边都有** UNIQUE(tenant_id, order_id, sku_id)
-- （V72 与 V99 各一条）—— 即「一张单里同一个 SKU 只有一行」。于是
--
--     parcel_item.sku_id → outbound_order_line.sku_id → sales_order_line.sku_id
--
-- 是一条**在两边都唯一**的路径。这不是靠行号对齐（行号在两个服务里各自编号，
-- 按行号对齐是纯猜），是按 SKU 唯一键对齐。
--
-- 只有 source_type='SALES' 的出库单才有销售订单行：调拨/采购退货/加工三类出库单
-- 在 trade 侧没有对应销售订单，本表对它们**不写行**（detail 里如实报「无销售订单行」），
-- 绝不硬造一个 uuid 填上去。
--
-- ---------------------------------------------------------------------------
-- 4. 本表**不**做什么
-- ---------------------------------------------------------------------------
--
--   * 不跨服务建 FK：sales_order_id / sales_order_line_id 是**裸 uuid 引用**，
--     与 V121 对 sku_id / declaration_line_id 的处理同一条纪律（不跨库 FK）。
--   * 不存金额、不存运费：运费金额归 12 财务，本表只提供**归集的分母**
--     （件数 / 重量 / 体积由 parcel 与 parcel_item 提供）。
--   * 不改 parcel / parcel_item 的任何列与触发器：包裹是 8.3.3.2 的凭据，
--     本模块是**只读视图**，一个字节都不许改写那些表。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. parcel_item_sales_order_line · 包裹明细项 → 销售订单行的显式关联
-- ---------------------------------------------------------------------------
CREATE TABLE parcel_item_sales_order_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 来源明细项（**真复合外键**，同服务）：一条包裹明细项恰好对应一个销售订单行。
  parcel_item_id uuid NOT NULL,
  parcel_id uuid NOT NULL,

  -- **跨服务引用，不建 FK**（AGENTS.md 模块边界纪律）：trade 服务 V99 的
  -- sales_order / sales_order_line。存裸 uuid 是本仓库既有的跨服务写法
  -- （V121 的 sku_id / declaration_line_id 同理）。
  sales_order_id uuid NOT NULL,
  sales_order_line_id uuid NOT NULL,

  -- 人类可读的关联凭据（COPY 语义）：订单号与行号随响应一起返回，
  -- 详情页要能显示「第几单第几行」，而不是让人回 trade 查一串 uuid。
  sales_order_no varchar(64) NOT NULL,
  sales_order_line_no integer NOT NULL CHECK(sales_order_line_no>0),

  -- SKU 对齐的凭据：两边都按这个唯一键对齐（见文件头 §3）。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,

  -- **归集分母**：这一箱里，分给这一销售订单行的件数（NUMERIC(19,6)，禁浮点）。
  -- 它必须等于该 parcel_item 的 quantity —— 一条明细项整体归一行，不在箱内再切分；
  -- 「一箱里同一行只装了一部分」的形态由 parcel_item 自身的 quantity 表达。
  -- 12 财务按 (件数 / 重量 / 体积) 三个口径各自加权，分母错一分运费就错一分。
  allocated_quantity numeric(19,6) NOT NULL CHECK(allocated_quantity>0),

  -- 匹配口径（append-only 的凭据，事后要答得出「这一行凭什么归到那件货」）。
  --   SKU_UNIQUE_ALIGNED = 两边都按 (order, sku) 唯一键对齐（V72/V99 各一条 UNIQUE）
  --   SKU_NAME_FALLBACK   = SKU uuid 对不上但 sku_code 一致（**如实标注**，不是等价匹配）
  -- 两个口径分开，是为了让财务能区分「强关联」与「弱关联」并对后者追加人工复核。
  match_basis varchar(24) NOT NULL DEFAULT 'SKU_UNIQUE_ALIGNED' CHECK(match_basis IN
    ('SKU_UNIQUE_ALIGNED','SKU_NAME_FALLBACK')),

  -- 匹配时的销售订单件数（冻结快照）：事后能答出「对齐时那一行说要发几件」。
  -- 不与 allocated_quantity 相等是正常的（一行跨箱分装），两者用途不同：
  -- 前者是订单侧应发，后者是本箱实装。
  sales_line_quantity numeric(19,6) NOT NULL CHECK(sales_line_quantity>0),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一条明细项只归一个销售订单行。两条意味着「同一件货同时记在两个行上」，
  -- 12 财务会把这一件的运费**重复**摊一次 —— 这是本表最贵的错，当场在 DB 层拒。
  UNIQUE(tenant_id,parcel_item_id),
  -- 同一 (parcel, sales_order_line) 只一条：同一箱里同一销售订单行只能有一个聚合口径。
  UNIQUE(tenant_id,parcel_id,sales_order_line_id),

  FOREIGN KEY(tenant_id,parcel_item_id) REFERENCES parcel_item(tenant_id,id),
  FOREIGN KEY(tenant_id,parcel_id) REFERENCES parcel(tenant_id,id),

  CHECK(btrim(sales_order_no)<>''),
  CHECK(btrim(sku_code)<>''),
  -- 跨服务引用没有 FK 可依赖，所以这里把「必须是 uuid 形态」钉死：
  -- 填个空串或 'unknown' 进去会让下游拿到一个查不出来的引用却以为是真关联。
  -- （uuid 列类型本身已拒非 uuid 字符串，此处是双保险的可读性约束。）
  CHECK(sales_order_id IS NOT NULL AND sales_order_line_id IS NOT NULL)
);

-- 成本归集的主查询路径：按销售订单行取它落在哪些箱、每箱分到几件。
-- 12 财务（下游）与本模块的详情页都走它。
CREATE INDEX parcel_item_sales_line_by_order_line
  ON parcel_item_sales_order_line(tenant_id, sales_order_line_id, parcel_id);
-- 按销售订单汇总（订单级视图用）。
CREATE INDEX parcel_item_sales_line_by_order
  ON parcel_item_sales_order_line(tenant_id, sales_order_id, sales_order_line_id);
-- 按 SKU 反查（对账 / 找「这个 SKU 在哪些箱里」）。
CREATE INDEX parcel_item_sales_line_by_sku
  ON parcel_item_sales_order_line(tenant_id, sku_id, parcel_id);
-- 详情页的主查询路径：单个包裹的全部明细关联。
CREATE INDEX parcel_item_sales_line_by_parcel
  ON parcel_item_sales_order_line(tenant_id, parcel_id, sales_order_line_no);

-- ---------------------------------------------------------------------------
-- 2. 守卫：这条关联必须与包裹明细项对得上
-- ---------------------------------------------------------------------------
--
-- 绕过服务层直连 SQL 写入时，下列三条照样拒：
--   a) allocated_quantity 必须等于该明细项的实装件数（分母不许编）；
--   b) sku_id 必须与明细项一致（不能拿着 A 件货的号去指 B 行的货，
--      那会让这一件货的运费摊到不相干的行上）；
--   c) parcel_id 必须与明细项所属的包裹一致（明细项与包裹对不上就是张冠李戴）。
CREATE FUNCTION guard_parcel_item_sales_line_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE v_qty numeric(19,6); v_sku uuid; v_parcel uuid;
BEGIN
  SELECT quantity, sku_id, parcel_id INTO v_qty, v_sku, v_parcel
    FROM parcel_item WHERE tenant_id = NEW.tenant_id AND id = NEW.parcel_item_id;
  IF v_qty IS NULL THEN
    RAISE EXCEPTION '包裹明细项不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_sku <> NEW.sku_id THEN
    RAISE EXCEPTION '关联行的 SKU 与包裹明细项不是同一个商品 —— '
                    '把运费摊到不相干的销售订单行上，比不摊更糟。' USING ERRCODE='23514';
  END IF;
  IF v_parcel <> NEW.parcel_id THEN
    RAISE EXCEPTION '关联行的包裹与包裹明细项所属的包裹不是同一个。' USING ERRCODE='23514';
  END IF;
  IF NEW.allocated_quantity <> v_qty THEN
    RAISE EXCEPTION '归集件数 % 必须等于该明细项的实装件数 % —— '
                    '分母编错会让运费按错的权重摊到每一行。', NEW.allocated_quantity, v_qty
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER parcel_item_sales_line_guard
  BEFORE INSERT ON parcel_item_sales_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_parcel_item_sales_line_fn();

-- 2.1 事实表不许改写：一条关联一旦写下就是凭据（对齐口径与分母都可能成为举证材料）。
--     要更正就删掉重写一条，并在 packing/审计留痕里说明。
CREATE FUNCTION guard_parcel_item_sales_line_ro_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '包裹明细与销售订单行的关联是事实凭据，不许 UPDATE '
                  '（如需更正请删除后重建，id=%）。', OLD.id USING ERRCODE='23514';
END $$;
CREATE TRIGGER parcel_item_sales_line_readonly
  BEFORE UPDATE ON parcel_item_sales_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_parcel_item_sales_line_ro_fn();

-- ---------------------------------------------------------------------------
-- 3. 多租户与授权（与 V121 §7、V1 §9 同一条纪律，一个字不差）
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['parcel_item_sales_order_line'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,DELETE ON parcel_item_sales_order_line TO warehouse_app;
-- 刻意**不给 UPDATE**：第 2.1 条守卫把本表钉成只增不删的事实凭据，
-- 给 warehouse_app UPDATE 权限等于给了一个绕过守卫的旁路。
-- starcon_app 是跨服务只读角色，拿不到本表（与 V1、V85、V90、V121 同口径）。
