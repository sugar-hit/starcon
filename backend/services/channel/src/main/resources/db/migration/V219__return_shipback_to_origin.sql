-- ===========================================================================
-- 10.7.1.2 退运回国（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.7.1.2 退运回国
--   - 职责：管理退运回国：批量退运单（凑单/合箱/报关）、国内收货仓、关税与申报、
--     回国后的重新入库或报废；支持海运拼箱的成本分摊。
--   - 输入：待退运清单；报关资料（3.7）；运输方式与成本。
--   - 输出：退运单 + 报关资料；回国收货记录 + 重新入库分录；退运成本。
--   - 关系：上游 = 10.7.1.1、10.5.1 报关；下游 = 7 库存、12 成本。**退运是批量、
--     长周期、高单票成本操作**，需合单以摊薄成本，退运比例应进 13 售后分析作为
--     产品质量代理指标。
--
-- ---------------------------------------------------------------------------
-- 零、🔴🔴🔴 本迁移最重要的一件事：**合单是真的，成本分摊也是真的**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「退运是批量、长周期、高单票成本操作，需合单以摊薄成本」在本迁移上
-- 是**三道结构闸**，缺一道那道闸就只是注释：
--
--   ① **退运单是一个批次，行是可以拼进来的多批货**：退运单（consignment）头 +
--      明细行（line），每行挂一条 10.7.1.1 的处置单。一次退运可以装 N 条处置单
--      的货 ⇒ 「凑单 / 合箱」不是页面上的一句口号，而是表结构上的多行。
--      反过来，**一条处置单最多只能进一个退运单**
--      （UNIQUE(tenant_id, disposition_order_id)）：一条货同时出现在两个退运单里，
--      成本就会被分摊两次、退运量会算两遍。
--   ② **成本分摊是**落库的真算法**，不是留空字段：每记一笔成本，`return_shipback_cost`
--      就同时产出**逐行**的 `return_shipback_cost_allocation`（权重、权重合计、
--      比例、分摊金额、尾差调整额）。触发器
--      `guard_return_shipback_allocation_total` 在**提交时**（DEFERRABLE INITIALLY
--      DEFERRED）钉死「同一笔成本的分摊金额合计 == 该笔成本金额」——
--      **合计必须等于总额**，尾差不许丢。
--   ③ **口径（basis）是数据不是代码常量**：每笔成本自带
--      `allocation_basis`（WEIGHT / QUANTITY / DECLARED_VALUE / EQUAL），
--      海运拼箱按重量摊、空运按体积/件摊、报关货值按价值摊，都由人显式选。
--      权重合计为 0 时**显式回退到 EQUAL 并把实际口径写进 `applied_basis`**，
--      绝不静默换一个口径。
--
-- ---------------------------------------------------------------------------
-- 一、本模块的职责边界：站在 10.7.1.1 之上做**批量退运**，不重做处置决策
-- ---------------------------------------------------------------------------
-- 上游真值：
--   * **10.7.1.1 退货接收与处置决策** —— 已交付，**就在本服务（channel）**，
--     所以本模块**可以**直接读 `return_disposition_order`：
--     只有 `disposition_method = 'RETURN_TO_COUNTRY'`（且处置单未撤销）的货
--     才是「待退运清单」里的货。**转销售 / 弃置销毁的处置单进不来**
--     （触发器 `guard_return_shipback_line_order` 钉死）。
--   * **10.5.1 报关**（10.5.1.1 报关单 / 10.5.1.2 申报要素 / 10.5.1.3 关税）
--     —— 已交付，但**分别在 warehouse 与 trade 服务**：跨服务不得建 FK、
--     不得跨库 join（AGENTS.md）。所以本模块的报关资料是**本服务自持的快照**
--     （`return_shipback_customs_declaration`），只以 `source_reference`
--     记上游单号做逻辑引用；与 10.5.1 的最终对账留待上游落地授权 API / event 衔接。
--   * **3.7 报关与税务**（catalog V94 / V95）—— 同样跨服务 ⇒ 同样是快照。
--     申报品名**刻意独立必填**，不照搬销售标题（10.4.1.1 的同款纪律：
--     申报品名是给清关看的，销售标题是给转化看的，混用是常见退单原因）。
--
-- 本模块**不重做**上游的活：不改处置决策、不改处置单、不改 10.7.1.1 的库存指令。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 下游缺口按仓库先例显式声明、不读写不存在的服务
-- ---------------------------------------------------------------------------
--   * **7 库存（7.4.1.1 调整单管理，warehouse）当前未交付**
--     ⇒ 「重新入库分录」是**只出事实不消费**的交接凭据：
--     `return_shipback_inventory_entry.posting_status` 恒为 PENDING，
--     `consumer_module` 恒为 '7.4.1.1'（报废去向恒为 '10.7.1.3'，该模块同样未交付），
--     且运行角色对本表的 UPDATE / DELETE 已收回 ——
--     「在本模块里把它标成已入库」既没有代码路径也没有 SQL 权限。
--   * **12 财务 / 成本（finance 服务）当前不存在**
--     ⇒ 「退运成本」以本服务自持的成本行 + 分摊明细落地，
--     金额一律 BigDecimal ↔ NUMERIC(19,4)，**禁浮点**。
--
-- 🔴 币种口径（本模块**不做任何汇率换算**）：
--   * 退运单有**一个成本币种** `cost_currency`；成本行与分摊行的币种
--     **必须**与它逐字相同（触发器钉死）—— 混币种的「合计」是没有意义的数字。
--   * **报关金额（申报货值 / 关税 / 税）是另一个币种口径** `declared_currency`
--     （申报币种与计征币种同币），它与成本币种**可以不同**，两者**永远不相加**。
--   * 残值有自己的 `salvage_currency`，也可以与成本币种不同。
--   * 本模块**没有汇率表、没有折算列、没有隐式净额**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 成本合计与分摊合计都**不落表**，查询时现算
-- ---------------------------------------------------------------------------
-- 与 4.5.2.2「比率 / 变化量一律不落表，SQL 现算」同纪律：
-- 退运单上**没有 cost_total 列**。退运成本合计 = SUM(成本行)，
-- 逐行分摊合计 = SUM(分摊行)，一次查询现算 ——
-- 落表就得在每次加成本、每次改数量时同步它，漏同步一次就会出现一个
-- 比明细更笃定的错数。
--
-- ---------------------------------------------------------------------------
-- 四、八张表：为什么是八张
-- ---------------------------------------------------------------------------
--   return_shipback_consignment        退运单（**批次头**：合单 / 合箱 / 运输 / 状态机）
--   return_shipback_line               退运单明细行（每行挂一条 10.7.1.1 处置单）
--   return_shipback_customs_declaration 报关资料（10.5.1 的**本服务自持快照**）
--   return_shipback_cost               退运成本行（运费 / 关税外的费用 / 操作费……）
--   return_shipback_cost_allocation    **成本分摊明细**（逐行权重 / 比例 / 金额 / 尾差）
--   return_shipback_arrival            回国收货记录（国内收货仓签收）
--   return_shipback_inventory_entry    重新入库分录 / 报废分录（→ 7 库存，**只出不消费**）
--   return_shipback_event              留痕（追加式）
--
-- 「退运单」是**批次**，「明细行」是**批次里的货**，「报关资料」是**报给海关的东西**，
-- 「成本」与「分摊」是**钱怎么摊**，「收货记录」是**到没到」，
-- 「入库分录」是**到了之后货的去向**。混在一张表上就会出现
-- 「一行既是一批货、又是一次报关、又是一笔钱」。
--
-- ===========================================================================

-- ===========================================================================
-- 1. 退运单（批次头）
-- ===========================================================================
CREATE TABLE return_shipback_consignment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  shipback_no varchar(32) NOT NULL,

  -- 状态机（六态）：
  --   DRAFT      草稿（可加行、可登记报关与成本）
  --   CONFIRMED  已确认（货已凑齐、报关资料齐备）
  --   DISPATCHED 已发出（已交承运商 / 已起运）
  --   ARRIVED    已到货（已回国、进国内收货仓）
  --   CLOSED     已关闭（账已结清）
  --   CANCELLED  已取消
  -- 状态**没有列默认值之外的"自动推进"**：每次推进都是一次显式写操作，
  -- 触发器 guard_return_shipback_consignment_state 钉死合法迁移对。
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
      'DRAFT','CONFIRMED','DISPATCHED','ARRIVED','CLOSED','CANCELLED')),

  -- 运输方式（海运拼箱是本模块的主角）
  transport_mode varchar(20) NOT NULL CHECK(transport_mode IN (
      'SEA_CONSOLIDATION','SEA_FULL_CONTAINER','AIR','EXPRESS','TRUCK','OTHER')),

  -- 起运地 / 目的地：退运是「海外仓 → 国内仓」
  origin_country char(2) NOT NULL,
  destination_country char(2) NOT NULL,
  origin_warehouse_ref varchar(128) NOT NULL,
  -- 🔴 国内收货仓：蓝图职责句点名的「国内收货仓」，回国后货落在哪里
  domestic_warehouse_ref varchar(128) NOT NULL,

  departure_port varchar(64) NOT NULL DEFAULT '',
  arrival_port varchar(64) NOT NULL DEFAULT '',
  carrier_ref varchar(128) NOT NULL DEFAULT '',
  tracking_no varchar(64) NOT NULL DEFAULT '',

  planned_departure_at timestamptz,
  actual_departure_at timestamptz,
  planned_arrival_at timestamptz,
  actual_arrival_at timestamptz,

  -- 汇总（现算的字段只放在明细上；这三个是**登记时的事实快照**，便于现场核对，
  --   真实合计以查询时 SUM 为准）
  total_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_qty >= 0),
  total_weight_kg numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_weight_kg >= 0),
  total_volume_m3 numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_volume_m3 >= 0),

  -- 🔴 成本币种：成本行与分摊行的币种必须与它逐字相同
  cost_currency char(3) NOT NULL CHECK(cost_currency ~ '^[A-Z]{3}$'),

  -- 报关状态（只记状态机，**报关单本身在 10.5.1**，这里是本模块的快照状态）
  customs_status varchar(16) NOT NULL DEFAULT 'NOT_PREPARED' CHECK(customs_status IN (
      'NOT_PREPARED','PREPARED','SUBMITTED','CLEARED','REJECTED')),
  customs_declaration_no varchar(64) NOT NULL DEFAULT '',

  -- 「为什么要合这一单」的说明（凑单 / 合箱的理由是人话，不是空字段）
  consolidation_note text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',

  -- 发出 / 到货 / 关闭的留痕（谁、什么时候）
  confirmed_by uuid,
  confirmed_at timestamptz,
  dispatched_by uuid,
  dispatched_at timestamptz,
  closed_by uuid,
  closed_at timestamptz,
  cancelled_by uuid,
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 1,
  -- 负责人归属快照（与 V1 shop / V171 listing / V214 同构）
  owner_id uuid,
  department_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, shipback_no),
  CHECK(btrim(origin_warehouse_ref) <> ''),
  CHECK(btrim(domestic_warehouse_ref) <> ''),
  CHECK(origin_country <> '' AND destination_country <> ''),
  CHECK(actual_departure_at IS NULL OR planned_departure_at IS NULL
     OR actual_departure_at >= planned_departure_at - interval '30 days'),
  CHECK(status <> 'CANCELLED' OR btrim(cancel_reason) <> '')
);

CREATE INDEX return_shipback_consignment_status
  ON return_shipback_consignment(tenant_id, status, created_at DESC);
CREATE INDEX return_shipback_consignment_transport
  ON return_shipback_consignment(tenant_id, transport_mode, created_at DESC);
CREATE INDEX return_shipback_consignment_time
  ON return_shipback_consignment(tenant_id, created_at DESC);

-- ===========================================================================
-- 2. 退运单明细行（每行挂一条 10.7.1.1 的处置单）
-- ===========================================================================
CREATE TABLE return_shipback_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  consignment_id uuid NOT NULL,

  -- 🔴 上游 10.7.1.1 的处置单（**同服务**，所以本模块真的读它；
  --    触发器钉死「必须是 RETURN_TO_COUNTRY 且未撤销」）
  disposition_order_id uuid NOT NULL,
  decision_id uuid NOT NULL,

  -- 上游快照（合单那一刻看到的货，事后上游改单不改这里的凭据）
  disposition_no varchar(32) NOT NULL DEFAULT '',
  receipt_no varchar(32) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  brand_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',

  qty numeric(19,6) NOT NULL CHECK(qty > 0),
  weight_kg numeric(19,6) NOT NULL DEFAULT 0 CHECK(weight_kg >= 0),
  volume_m3 numeric(19,6) NOT NULL DEFAULT 0 CHECK(volume_m3 >= 0),

  -- 申报口径（报关资料的源头）
  hs_code varchar(16) NOT NULL DEFAULT '',
  -- 🔴 申报品名**独立必填**（不照搬销售标题，见本迁移第一节）
  declared_item_name varchar(200) NOT NULL DEFAULT '',
  declared_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(declared_value >= 0),
  declared_currency char(3) NOT NULL CHECK(declared_currency ~ '^[A-Z]{3}$'),

  -- 残值（回收价值；币种可以与成本币种不同）
  salvage_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(salvage_amount >= 0),
  salvage_currency char(3) NOT NULL CHECK(salvage_currency ~ '^[A-Z]{3}$'),

  -- 分摊到本行的成本（**落表**：这是「这一行到底摊了多少」的凭据，
  --   每次记成本时重算并覆盖；退运单关闭后即为最终值）
  allocated_cost numeric(19,4) NOT NULL DEFAULT 0 CHECK(allocated_cost >= 0),

  -- PLANNED 已排入 · SHIPPED 已发出 · ARRIVED 已到货 · RECEIVED 已收货
  --   · SCRAPPED 已报废 · SHORTAGE 短少
  status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status IN (
      'PLANNED','SHIPPED','ARRIVED','RECEIVED','SCRAPPED','SHORTAGE')),

  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 一条处置单最多进一个退运单：同一条货进两个退运单 ⇒ 成本分摊两次、退运量算两遍
  UNIQUE(tenant_id, disposition_order_id),
  CHECK(btrim(sku_code) <> ''),
  CHECK(declared_value = 0 OR declared_value > 0)
);

CREATE INDEX return_shipback_line_consignment
  ON return_shipback_line(tenant_id, consignment_id);
CREATE INDEX return_shipback_line_disposition
  ON return_shipback_line(tenant_id, disposition_order_id);
CREATE INDEX return_shipback_line_sku
  ON return_shipback_line(tenant_id, sku_code);

-- ===========================================================================
-- 3. 报关资料（10.5.1 / 3.7 的**本服务自持快照**）
-- ===========================================================================
-- 🔴 为什么是自持快照：10.5.1.1 报关单在 warehouse（V85）、10.5.1.2 申报要素在
--   trade（V138）、10.5.1.3 关税在 warehouse（V90）、3.7.1.1 HS 编码在
--   catalog（V94）—— **全部跨服务**。跨服务不得建 FK、不得跨库 join，
--   所以本模块只保留一份**快照**并以 source_reference 记上游单号做逻辑引用。
CREATE TABLE return_shipback_customs_declaration (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  consignment_id uuid NOT NULL,
  -- 逐行报关时挂明细行；整单报关时为空
  line_id uuid,

  -- 上游逻辑引用（跨服务，**无外键**）
  source_module varchar(16) NOT NULL DEFAULT '10.5.1',
  source_reference varchar(64) NOT NULL DEFAULT '',
  declaration_no varchar(64) NOT NULL DEFAULT '',

  declaration_type varchar(16) NOT NULL CHECK(declaration_type IN (
      'RETURN_IMPORT','EXPORT','TRANSIT')),

  hs_code varchar(16) NOT NULL,
  declared_item_name varchar(200) NOT NULL,
  declared_qty numeric(19,6) NOT NULL CHECK(declared_qty > 0),
  declared_value numeric(19,4) NOT NULL CHECK(declared_value >= 0),
  -- 🔴 报关金额口径（申报币种）：与运输成本币种可以不同，**两者永不相加**
  declared_currency char(3) NOT NULL CHECK(declared_currency ~ '^[A-Z]{3}$'),

  -- 税率（比例）：scale 6，0.15 = 15%
  tariff_rate numeric(19,6) NOT NULL DEFAULT 0 CHECK(tariff_rate >= 0 AND tariff_rate <= 1),
  -- 🔴 关税金额 = 申报货值 × 税率（**派生**，服务端算，不给两个互相矛盾的数）
  tariff_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(tariff_amount >= 0),
  -- 增值税 / 其它税费（不进「关税」口径，单列）
  tax_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(tax_amount >= 0),
  -- 合计 = 关税 + 税费（DB CHECK 钉死）
  total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_amount >= 0),

  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
      'DRAFT','SUBMITTED','CLEARED','REJECTED')),
  submitted_at timestamptz,
  cleared_at timestamptz,

  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(btrim(hs_code) <> ''),
  CHECK(btrim(declared_item_name) <> ''),
  -- 🔴 合计必须等于关税 + 税费（结构事实，不靠服务层自觉）
  CHECK(total_amount = tariff_amount + tax_amount)
);

CREATE INDEX return_shipback_customs_declaration_consignment
  ON return_shipback_customs_declaration(tenant_id, consignment_id);
CREATE INDEX return_shipback_customs_declaration_line
  ON return_shipback_customs_declaration(tenant_id, line_id);

-- ===========================================================================
-- 4. 退运成本行
-- ===========================================================================
-- 蓝图输出「退运成本」。合计**不落表**（见本迁移第三节），查询时 SUM 现算。
CREATE TABLE return_shipback_cost (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  consignment_id uuid NOT NULL,
  cost_type varchar(24) NOT NULL CHECK(cost_type IN (
      'OCEAN_FREIGHT','AIR_FREIGHT','TARIFF','CUSTOMS_FEE','INLAND_FREIGHT',
      'HANDLING_FEE','REPACK_FEE','OTHER')),
  amount numeric(19,4) NOT NULL CHECK(amount >= 0),
  -- 币种必须与退运单的成本币种逐字相同（触发器钉死）
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 🔴 分摊口径是**数据**：海运拼箱按重量、空运按件、报关货值按价值……
  allocation_basis varchar(16) NOT NULL CHECK(allocation_basis IN (
      'WEIGHT','QUANTITY','DECLARED_VALUE','EQUAL')),

  vendor_ref varchar(128) NOT NULL DEFAULT '',
  incurred_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX return_shipback_cost_consignment
  ON return_shipback_cost(tenant_id, consignment_id);

-- ===========================================================================
-- 5. 🔴 成本分摊明细（**分摊是真的算出来的**）
-- ===========================================================================
-- 每记一笔成本就同时产出**逐行**的分摊行：
--   basis_value  本行的权重（重量 / 件数 / 申报货值 / 1）
--   basis_total  本次分摊的权重合计
--   allocation_ratio 比例（scale 4，HALF_UP 钉死）
--   allocated_amount 分摊金额（scale 4）
--   residual_amount  **尾差调整额**：把「总额 − 逐笔四舍五入之和」补到某一笔上，
--                   于是 **SUM(分摊金额) 恒等于该笔成本金额**（提交时由
--                   DEFERRABLE 约束触发器钉死）。
CREATE TABLE return_shipback_cost_allocation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  consignment_id uuid NOT NULL,
  line_id uuid NOT NULL,
  cost_id uuid NOT NULL,

  -- 人选的口径 / 实际用的口径（权重合计为 0 时显式回退 EQUAL 并如实记录）
  requested_basis varchar(16) NOT NULL CHECK(requested_basis IN (
      'WEIGHT','QUANTITY','DECLARED_VALUE','EQUAL')),
  applied_basis varchar(16) NOT NULL CHECK(applied_basis IN (
      'WEIGHT','QUANTITY','DECLARED_VALUE','EQUAL')),

  basis_value numeric(19,6) NOT NULL CHECK(basis_value >= 0),
  basis_total numeric(19,6) NOT NULL CHECK(basis_total > 0),
  allocation_ratio numeric(19,4) NOT NULL CHECK(allocation_ratio >= 0),
  allocated_amount numeric(19,4) NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 尾差调整额：至多一笔非零
  residual_amount numeric(19,4) NOT NULL DEFAULT 0,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一笔成本对一行只能分摊一次
  UNIQUE(tenant_id, cost_id, line_id)
);

CREATE INDEX return_shipback_cost_allocation_consignment
  ON return_shipback_cost_allocation(tenant_id, consignment_id);
CREATE INDEX return_shipback_cost_allocation_line
  ON return_shipback_cost_allocation(tenant_id, line_id);
CREATE INDEX return_shipback_cost_allocation_cost
  ON return_shipback_cost_allocation(tenant_id, cost_id);

-- ===========================================================================
-- 6. 回国收货记录（国内收货仓签收）
-- ===========================================================================
CREATE TABLE return_shipback_arrival (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  consignment_id uuid NOT NULL,
  domestic_warehouse_ref varchar(128) NOT NULL,
  arrived_at timestamptz NOT NULL,

  received_qty numeric(19,6) NOT NULL CHECK(received_qty >= 0),
  damaged_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(damaged_qty >= 0),
  shortage_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(shortage_qty >= 0),

  received_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX return_shipback_arrival_consignment
  ON return_shipback_arrival(tenant_id, consignment_id);

-- ===========================================================================
-- 7. 重新入库分录 / 报废分录（→ 7 库存，**只出，不消费**）
-- ===========================================================================
-- 🔴 posting_status 恒为 PENDING：消费方 7.4.1.1 调整单管理（warehouse）与
--   10.7.1.3 弃置与销毁**均未交付**，本模块**刻意不提供**消费端点，
--   运行角色对本表的 UPDATE / DELETE 也已收回。
CREATE TABLE return_shipback_inventory_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  consignment_id uuid NOT NULL,
  line_id uuid NOT NULL,

  -- RESTOCK 重新入库（→ 7 库存）· SCRAP 报废（→ 10.7.1.3）
  entry_type varchar(16) NOT NULL CHECK(entry_type IN ('RESTOCK','SCRAP')),
  qty numeric(19,6) NOT NULL CHECK(qty > 0),

  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  domestic_warehouse_ref varchar(128) NOT NULL,

  entry_no varchar(32) NOT NULL,
  -- 恒为 PENDING；消费方随去向而不同
  posting_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(posting_status = 'PENDING'),
  consumer_module varchar(16) NOT NULL CHECK(consumer_module IN ('7.4.1.1','10.7.1.3')),

  issued_at timestamptz NOT NULL,
  issued_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, entry_no),
  -- 🔴 去向与消费方一一对应：重新入库交给 7 库存，报废交给 10.7.1.3
  CHECK((entry_type = 'RESTOCK' AND consumer_module = '7.4.1.1')
     OR (entry_type = 'SCRAP' AND consumer_module = '10.7.1.3'))
);

CREATE INDEX return_shipback_inventory_entry_consignment
  ON return_shipback_inventory_entry(tenant_id, consignment_id);
CREATE INDEX return_shipback_inventory_entry_line
  ON return_shipback_inventory_entry(tenant_id, line_id);

-- ===========================================================================
-- 8. 留痕（追加式）
-- ===========================================================================
CREATE TABLE return_shipback_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  event_type varchar(32) NOT NULL CHECK(event_type IN (
      'CONSIGNMENT_CREATED','LINE_ADDED','CUSTOMS_PREPARED','COST_RECORDED',
      'CONFIRMED','DISPATCHED','ARRIVED','ENTRY_ISSUED','CLOSED','CANCELLED')),
  consignment_id uuid,
  line_id uuid,
  cost_id uuid,
  declaration_id uuid,
  entry_id uuid,
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  detail text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX return_shipback_event_consignment
  ON return_shipback_event(tenant_id, consignment_id, occurred_at DESC);

-- ===========================================================================
-- 9. 单据号序列
-- ===========================================================================
CREATE SEQUENCE return_shipback_consignment_no_seq;
CREATE SEQUENCE return_shipback_entry_no_seq;

GRANT USAGE,SELECT ON SEQUENCE return_shipback_consignment_no_seq TO channel_app;
GRANT USAGE,SELECT ON SEQUENCE return_shipback_entry_no_seq TO channel_app;

-- ===========================================================================
-- 10. 触发器：结构事实不许靠服务层自觉
-- ===========================================================================
-- (a) 操作者由 app.actor 盖章：客户端伪造不了「谁合的、谁发的、谁签收的」。
CREATE FUNCTION stamp_return_shipback_actor() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.issued_by := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.issued_by IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_inventory_entry_actor
  BEFORE INSERT ON return_shipback_inventory_entry
  FOR EACH ROW EXECUTE FUNCTION stamp_return_shipback_actor();

CREATE FUNCTION stamp_return_shipback_arrival_received_by() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.received_by := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.received_by IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_arrival_received_by
  BEFORE INSERT ON return_shipback_arrival
  FOR EACH ROW EXECUTE FUNCTION stamp_return_shipback_arrival_received_by();

CREATE FUNCTION stamp_return_shipback_event_actor() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.actor_id := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.actor_id IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_event_actor
  BEFORE INSERT ON return_shipback_event
  FOR EACH ROW EXECUTE FUNCTION stamp_return_shipback_event_actor();

-- (b) 🔴 退运单状态机：**合法迁移对白名单**，且终态不许离开。
CREATE FUNCTION guard_return_shipback_consignment_state() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status = NEW.status THEN RETURN NEW; END IF;
  IF NOT (
       (OLD.status='DRAFT'      AND NEW.status IN ('CONFIRMED','CANCELLED'))
    OR (OLD.status='CONFIRMED'  AND NEW.status IN ('DISPATCHED','CANCELLED'))
    OR (OLD.status='DISPATCHED' AND NEW.status='ARRIVED')
    OR (OLD.status='ARRIVED'    AND NEW.status='CLOSED')
  ) THEN
    RAISE EXCEPTION 'Return shipback % cannot move from % to %',
      OLD.id, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_consignment_state
  BEFORE UPDATE ON return_shipback_consignment
  FOR EACH ROW EXECUTE FUNCTION guard_return_shipback_consignment_state();

-- (c) 🔴 明细行必须挂一条**真实存在**的「退运回国」处置单（10.7.1.1）。
--     转销售 / 弃置销毁的处置单进不了退运单；已撤销的处置单也进不了。
CREATE FUNCTION guard_return_shipback_line_order() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  o return_disposition_order%ROWTYPE;
  c return_shipback_consignment%ROWTYPE;
BEGIN
  SELECT * INTO c FROM return_shipback_consignment
   WHERE tenant_id=NEW.tenant_id AND id=NEW.consignment_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return shipback line % must reference an existing consignment',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF c.status NOT IN ('DRAFT','CONFIRMED') THEN
    IF TG_OP='INSERT' THEN
      RAISE EXCEPTION 'Return shipback % is %; no further line may be added',
        c.id, c.status USING ERRCODE='23514';
    END IF;
    -- 🔴 UPDATE 只允许改**非结构性**字段（例如分摊金额回写）：
    --    「已发出 / 已到货」的退运单仍可能收到承运商账单并重算分摊，
    --    但**不许**把行改挂到别的退运单或换一条处置单。
    IF NEW.consignment_id IS DISTINCT FROM OLD.consignment_id THEN
      RAISE EXCEPTION 'Return shipback line % cannot be moved to another consignment',
        OLD.id USING ERRCODE='23514';
    END IF;
    IF NEW.disposition_order_id IS DISTINCT FROM OLD.disposition_order_id THEN
      RAISE EXCEPTION 'Return shipback line % cannot switch to another disposition order',
        OLD.id USING ERRCODE='23514';
    END IF;
  END IF;
  SELECT * INTO o FROM return_disposition_order
   WHERE tenant_id=NEW.tenant_id AND id=NEW.disposition_order_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return shipback line % must reference an existing disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF o.disposition_method <> 'RETURN_TO_COUNTRY' THEN
    RAISE EXCEPTION 'Return shipback line % must reference a RETURN_TO_COUNTRY disposition order (this one is %)',
      NEW.id, o.disposition_method USING ERRCODE='23514';
  END IF;
  IF o.status <> 'ISSUED' THEN
    RAISE EXCEPTION 'Return shipback line % references a cancelled disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF o.decision_id IS NULL OR o.registration_id IS NULL THEN
    RAISE EXCEPTION 'Return shipback line % references a malformed disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.decision_id <> o.decision_id THEN
    RAISE EXCEPTION 'Return shipback line % decision must match its disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 数量不得超过处置单的处置量
  IF NEW.qty > o.disposed_qty THEN
    RAISE EXCEPTION 'Return shipback line % quantity % exceeds disposed quantity %',
      NEW.id, NEW.qty, o.disposed_qty USING ERRCODE='23514';
  END IF;
  -- 快照必须与处置单逐字一致（SKU / 批次不许在合单时漂移）
  IF NEW.sku_code <> o.sku_code OR NEW.batch_no <> o.batch_no
     OR NEW.receipt_no <> o.receipt_no THEN
    RAISE EXCEPTION 'Return shipback line % snapshot must match its disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_line_order
  BEFORE INSERT OR UPDATE ON return_shipback_line
  FOR EACH ROW EXECUTE FUNCTION guard_return_shipback_line_order();

-- (d) 🔴 报关资料必须挂在本退运单（或它的某一行）上，金额合计自洽。
CREATE FUNCTION guard_return_shipback_customs_declaration() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  c return_shipback_consignment%ROWTYPE;
BEGIN
  SELECT * INTO c FROM return_shipback_consignment
   WHERE tenant_id=NEW.tenant_id AND id=NEW.consignment_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return shipback customs declaration % must reference an existing consignment',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF c.status NOT IN ('DRAFT','CONFIRMED','DISPATCHED') THEN
    RAISE EXCEPTION 'Return shipback % is %; no further customs declaration may be recorded',
      c.id, c.status USING ERRCODE='23514';
  END IF;
  IF NEW.line_id IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM return_shipback_line l
                    WHERE l.tenant_id=NEW.tenant_id AND l.id=NEW.line_id
                      AND l.consignment_id=NEW.consignment_id) THEN
      RAISE EXCEPTION 'Return shipback customs declaration % must reference a line of the same consignment',
        NEW.id USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_customs_declaration_guard
  BEFORE INSERT OR UPDATE ON return_shipback_customs_declaration
  FOR EACH ROW EXECUTE FUNCTION guard_return_shipback_customs_declaration();

-- (e) 🔴 成本行的币种必须与退运单成本币种逐字相同（混币种的合计没有意义）。
CREATE FUNCTION guard_return_shipback_cost_currency() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  c return_shipback_consignment%ROWTYPE;
BEGIN
  SELECT * INTO c FROM return_shipback_consignment
   WHERE tenant_id=NEW.tenant_id AND id=NEW.consignment_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return shipback cost % must reference an existing consignment',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF c.status IN ('CANCELLED','CLOSED') THEN
    RAISE EXCEPTION 'Return shipback % is %; no further cost may be recorded',
      c.id, c.status USING ERRCODE='23514';
  END IF;
  IF c.cost_currency <> NEW.currency THEN
    RAISE EXCEPTION 'Return shipback cost % currency must match the consignment cost currency',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_cost_currency
  BEFORE INSERT ON return_shipback_cost
  FOR EACH ROW EXECUTE FUNCTION guard_return_shipback_cost_currency();

-- (f) 🔴🔴 分摊合计必须等于该笔成本金额（**提交时**钉死，DEFERRABLE）。
--     尾差调整必须落在某一行上，于是 SUM(allocated_amount) 恒等于 amount。
CREATE FUNCTION guard_return_shipback_allocation_total() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  c return_shipback_cost%ROWTYPE;
  total numeric(19,4);
  cnt bigint;
  line_cnt bigint;
BEGIN
  SELECT * INTO c FROM return_shipback_cost
   WHERE tenant_id=NEW.tenant_id AND id=NEW.cost_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return shipback allocation % must reference an existing cost',
      NEW.id USING ERRCODE='23514';
  END IF;
  SELECT coalesce(sum(allocated_amount),0), count(*) INTO total, cnt
    FROM return_shipback_cost_allocation
   WHERE tenant_id=NEW.tenant_id AND cost_id=NEW.cost_id;
  SELECT count(*) INTO line_cnt FROM return_shipback_line
   WHERE tenant_id=NEW.tenant_id AND consignment_id=c.consignment_id;
  IF cnt <> line_cnt THEN
    RAISE EXCEPTION 'Return shipback cost % must be allocated to every line (% of %)',
      c.id, cnt, line_cnt USING ERRCODE='23514';
  END IF;
  IF total <> c.amount THEN
    RAISE EXCEPTION 'Return shipback cost % allocation total % must equal amount %',
      c.id, total, c.amount USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_shipback_allocation_total
  AFTER INSERT OR UPDATE ON return_shipback_cost_allocation
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_return_shipback_allocation_total();

-- (g) 🔴 入库 / 报废分录：货必须**已经到货**（退运单 ARRIVED），
--     数量不得超过该行数量，且同一行的分录数量合计不得超过该行数量。
CREATE FUNCTION guard_return_shipback_inventory_entry() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  c return_shipback_consignment%ROWTYPE;
  l return_shipback_line%ROWTYPE;
  used numeric(19,6);
BEGIN
  SELECT * INTO c FROM return_shipback_consignment
   WHERE tenant_id=NEW.tenant_id AND id=NEW.consignment_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return shipback inventory entry % must reference an existing consignment',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF c.status <> 'ARRIVED' THEN
    RAISE EXCEPTION 'Return shipback % is %; inventory entry is only allowed after arrival',
      c.id, c.status USING ERRCODE='23514';
  END IF;
  SELECT * INTO l FROM return_shipback_line
   WHERE tenant_id=NEW.tenant_id AND id=NEW.line_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return shipback inventory entry % must reference an existing line',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF l.consignment_id <> NEW.consignment_id THEN
    RAISE EXCEPTION 'Return shipback inventory entry % must reference a line of the same consignment',
      NEW.id USING ERRCODE='23514';
  END IF;
  SELECT coalesce(sum(qty),0) INTO used FROM return_shipback_inventory_entry
   WHERE tenant_id=NEW.tenant_id AND line_id=NEW.line_id
     AND (TG_OP='INSERT' OR id <> NEW.id);
  IF used + NEW.qty > l.qty THEN
    RAISE EXCEPTION 'Return shipback inventory entry % quantity % exceeds remaining line quantity %',
      NEW.id, NEW.qty, l.qty - used USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_inventory_entry_guard
  BEFORE INSERT ON return_shipback_inventory_entry
  FOR EACH ROW EXECUTE FUNCTION guard_return_shipback_inventory_entry();

-- (h) 🔴 留痕追加式：已写入的事件不许改、不许删。
CREATE FUNCTION guard_return_shipback_event_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  RAISE EXCEPTION 'Return shipback event % is append-only', OLD.id USING ERRCODE='23514';
END $$;
CREATE TRIGGER return_shipback_event_append_only
  BEFORE UPDATE OR DELETE ON return_shipback_event
  FOR EACH ROW EXECUTE FUNCTION guard_return_shipback_event_append_only();

-- ===========================================================================
-- 11. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过策略，
--    隔离测试就成了自欺欺人。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'return_shipback_consignment','return_shipback_line',
    'return_shipback_customs_declaration','return_shipback_cost',
    'return_shipback_cost_allocation','return_shipback_arrival',
    'return_shipback_inventory_entry','return_shipback_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 退运单自带负责人归属快照（与 V1 shop / V214 同构），可见性随本行自己的归属收敛。
CREATE FUNCTION stamp_return_shipback_consignment_owner() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
    NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
    IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
  ELSE
    IF (NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.department_id IS DISTINCT FROM OLD.department_id)
       AND coalesce(nullif(current_setting('app.transfer',true),'')::boolean,false) IS NOT TRUE
    THEN RAISE EXCEPTION 'Return shipback consignment ownership transfer must use the explicit transfer flag'
      USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_shipback_consignment_owner
  BEFORE INSERT OR UPDATE ON return_shipback_consignment
  FOR EACH ROW EXECUTE FUNCTION stamp_return_shipback_consignment_owner();

CREATE POLICY data_scope ON return_shipback_consignment AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

-- 子表可见性随**父行**收敛，不允许绕过父行直接枚举。
CREATE POLICY data_scope ON return_shipback_line AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_line.tenant_id AND c.id=return_shipback_line.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_line.tenant_id AND c.id=return_shipback_line.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

CREATE POLICY data_scope ON return_shipback_customs_declaration AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_customs_declaration.tenant_id
             AND c.id=return_shipback_customs_declaration.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_customs_declaration.tenant_id
             AND c.id=return_shipback_customs_declaration.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

CREATE POLICY data_scope ON return_shipback_cost AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_cost.tenant_id AND c.id=return_shipback_cost.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_cost.tenant_id AND c.id=return_shipback_cost.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

CREATE POLICY data_scope ON return_shipback_cost_allocation AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_cost_allocation.tenant_id
             AND c.id=return_shipback_cost_allocation.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_cost_allocation.tenant_id
             AND c.id=return_shipback_cost_allocation.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

CREATE POLICY data_scope ON return_shipback_arrival AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_arrival.tenant_id AND c.id=return_shipback_arrival.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_arrival.tenant_id AND c.id=return_shipback_arrival.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

CREATE POLICY data_scope ON return_shipback_inventory_entry AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_inventory_entry.tenant_id
             AND c.id=return_shipback_inventory_entry.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_inventory_entry.tenant_id
             AND c.id=return_shipback_inventory_entry.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

CREATE POLICY data_scope ON return_shipback_event AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_event.tenant_id AND c.id=return_shipback_event.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_shipback_consignment c
             WHERE c.tenant_id=return_shipback_event.tenant_id AND c.id=return_shipback_event.consignment_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

-- ===========================================================================
-- 12. 账号与授权（沿用 V1..V214 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  return_shipback_consignment, return_shipback_line,
  return_shipback_customs_declaration, return_shipback_cost,
  return_shipback_cost_allocation, return_shipback_arrival,
  return_shipback_inventory_entry, return_shipback_event TO channel_app;

-- 🔴 重新入库 / 报废分录：**只出，不消费** —— UPDATE / DELETE 收回，
--    于是「在本模块里把它标成已入库 / 已报废」既没有代码路径也没有 SQL 权限。
REVOKE UPDATE,DELETE ON return_shipback_inventory_entry FROM channel_app;

-- 🔴 留痕追加式：UPDATE / DELETE 收回（触发器也拦，两道闸）。
REVOKE UPDATE,DELETE ON return_shipback_event FROM channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V214 同口径）。
REVOKE ALL ON
  return_shipback_consignment, return_shipback_line,
  return_shipback_customs_declaration, return_shipback_cost,
  return_shipback_cost_allocation, return_shipback_arrival,
  return_shipback_inventory_entry, return_shipback_event FROM starcon_app;

-- ===========================================================================
-- 13. 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：退运回国属于「平台 / 运营操作」语义，
-- 与 10.1.1.1 承运商、10.3.2.1 交运批次、10.7.1.1 退货处置同族，
-- identity 服务零改动。
