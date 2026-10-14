-- ===========================================================================
-- 7.7.2.1 VMI 供应商管理库存
-- ===========================================================================
-- 蓝图原文（07-inventory.md 7.7.2.1）：
--   「支持供应商管理库存（VMI）：货权属供应商、我方享有可销售权，按约定补货或由系统触发供应商备货；
--     记录安全水位与补货信号，售出后与供应商对账结算。」
--   输入「VMI 协议（SKU、水位、补货倍数、结算价、结算周期）；实时销量与在库数据。」
--   输出「VMI 水位监控与补货信号（推给供应商或 6.2.1.2 备货）；VMI 结算对账单。」
--   关系「上游 = 13 销量、7.1.1.3 可用量；下游 = 6 采购（补货信号）、12 结算。
--     **VMI 的『可售』不等于『自有』**：可售量要计入可售额，货值不能计入自有资产，
--     两者必须分开统计。」
--
-- ---------------------------------------------------------------------------
-- 一、本模块唯一不可让步的一条：可售 ≠ 自有
-- ---------------------------------------------------------------------------
-- 蓝图把它写成加粗约束，07-inventory.md:24 也把它列为硬约束：
--   「寄售/VMI 库存的货主是供应商，我方只记持有位置与可销售权，不记为自有资产。」
--
-- 本迁移不把它写成一句注释，而是**四道数据库不变量**，任何一条被绕开都写不进数据：
--
--   1) vmi_agreement_line.counts_as_owned_asset 恒为 false，且 CHECK 写死 false；
--   2) vmi_agreement_line.stock_owner_id 指向的货主**不许**是「计入自有资产」的那一类
--      （触发器 guard_vmi_line_owner_not_owned 查 7.7.1.1 的 stock_owner.counts_as_owned_asset
--      生成列，为 true 直接拒绝）—— 这条最关键：光在自己表里写 false 不够，
--      货真要算成自有资产必须去动 7.7.1.1 的货主档案，那条路现在被数据库挡死了；
--   3) vmi_agreement_line.sellable_value 是生成列（在库量 × 结算价），**全表没有第二个金额列**：
--      能算出来的只有「可售额」，算不出「自有货值」不是靠应用层不说，而是压根没有那个列；
--   4) vmi_agreement_line.owned_asset_value 是生成列恒为 NULL：
--      DB 里就不存在「VMI 自有货值」这个数。服务层如实把 null 报出去，
--      给 0 会被读成「这些货值 0 元」，那是谎报。
--
-- 「可售」这一侧仍然是**真的**：VMI 货在 stock_bucket / inventory_balance 里占着真实的
-- 在手与可用量，按 7.1.1.3 的口径（SELLABLE 质量状态）参与可售额统计。可售额与自有资产
-- 两个口径在读模型里**并排返回、绝不合并**（VmiOwnershipSplit）。
--
-- ---------------------------------------------------------------------------
-- 二、货权归属怎么落：复用 7.7.1.1 的 stock_owner，不另起炉灶
-- ---------------------------------------------------------------------------
-- 兄弟模块 7.7.1.1「货主与所有权」**已交付**（V67）：stock_owner 表带 owner_kind 词表
-- （SELF / SUPPLIER_CONSIGNMENT / PLATFORM / CONSIGNEE）与生成列 counts_as_owned_asset
-- （仅 owner_kind='SELF' 为 true），且 stock_bucket.stock_owner_id 已 NOT NULL。
--
-- 因此本模块：
--   * 不 ALTER stock_bucket / inventory_balance（那是 7.1.1.3 那条线的唯一写入所有者）；
--   * 协议行直接引用 stock_owner(tenant_id,id)，货主是谁、货主算不算自有，
--     全部由 7.7.1.1 的那张表裁决 —— 本模块不复制权威列，不建第二个真相；
--   * 「这批 VMI 货有多少在库」= 按 stock_owner_id 从 stock_bucket 聚合 SELLABLE 质量状态
--     的 available（**同服务同库**，不是跨服务读）。
--
-- ---------------------------------------------------------------------------
-- 三、上游「实时销量与在库数据」：能算的算，不能算的自落地并标来源
-- ---------------------------------------------------------------------------
--   * 在库可售量：warehouse 自己的 stock_bucket/inventory_balance（7.1.1.3 已交付），
--     按货主聚合 —— 真账，不抄。
--   * 13 销量 / 9 订单**尚未交付**：售出事实由本服务自落地 vmi_sale，
--     以 (协议行, sale_ref) 幂等，source_system 标注来源；9/13 交付后由它们推这张表。
--   * VMI 货常常**物理上就在供应商自己的仓库**，压根不在我方仓里 —— 此时账本上查不到桶，
--     这不是数据缺失而是一种真实经营形态。所以另建 vmi_stock_snapshot：
--     租户可维护的真实表，stock_source 必填并 CHECK 在词表内，observed_at 必填。
--     读模型逐行报出 stockSource，前端照实显示「这行是账本算的还是租户报���的」。
--   * 6 采购（备货）与 12 结算**尚未交付**：补货信号与结算对账单只出本服务内的单据，
--     写 source_system + reference_id 定位键，不跨库写、不建跨服务 FK、不查对方表。
--
-- ---------------------------------------------------------------------------
-- 四、结算口径
-- ---------------------------------------------------------------------------
--   数量 numeric(19,6)、结算价 numeric(19,4)、明细金额 numeric(24,4) 生成列
--   （金额 = 数量 × 结算价，全表没有第二个金额来源）。
--   **单据级应付 HALF_UP 到分**（numeric(24,2)），由 DB 触发器 guard_vmi_settlement_totals
--   按 round(Σ明细金额, 2) 重算表头 —— 与服务层 setScale(2, HALF_UP) 同口径，
--   两者不会各算各的。之所以比寄售多这一步：VMI 对账单是要发给供应商对账的凭据，
--   分以下必须与发票口径一致，逐笔保留 4 位而表头 2 位是刻意的（明细精确、表头可付）。
--
-- 单号由 DB 发（VMI-/VST-/VRS- 前缀），不在应用层拼，并发建单不会撞号。
-- 8 张表全部 tenant_id NOT NULL + ENABLE/FORCE RLS + 授权。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：VMI-yyyyMMdd-000001 / VST- / VRS-
-- ---------------------------------------------------------------------------
CREATE SEQUENCE vmi_agreement_code_seq START 1;
CREATE SEQUENCE vmi_settlement_code_seq START 1;
CREATE SEQUENCE vmi_signal_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. vmi_agreement · VMI 协议头
-- ---------------------------------------------------------------------------
CREATE TABLE vmi_agreement (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_code varchar(32) NOT NULL,
  -- 货主：供应商的逻辑 ID（catalog party.id，跨服务不建 FK，见头部三）。
  -- 下面两列是建单时的展示快照：供应商主数据归 3.6.2 所有，本服务不复制权威列。
  supplier_party_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL,
  supplier_name varchar(200) NOT NULL,
  -- 账套法人：对账单归到哪个法人账下（复合外键强制同租户）。
  legal_entity_id uuid NOT NULL,
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 结算周期：按**售出时刻**归期（供应商要的是「这周期我卖出去多少」）。
  settlement_cycle varchar(16) NOT NULL CHECK(settlement_cycle IN
    ('WEEKLY','SEMI_MONTHLY','MONTHLY','ON_DEMAND')),
  -- 补货模式（蓝图「按约定补货或由系统触发供应商备货」的两种形态）：
  --   SYSTEM_SIGNAL 系统按水位与倍数出补货信号 → 推给供应商或 6.2.1.2
  --   VENDOR_OWN   供应商自行补货，系统只监控水位（不出信号，只报水位）
  replenishment_mode varchar(16) NOT NULL CHECK(replenishment_mode IN
    ('SYSTEM_SIGNAL','VENDOR_OWN')),
  -- 信号去向：SUPPLIER 推供应商 / PROCUREMENT 交给 6 采购备货。
  signal_target varchar(16) NOT NULL DEFAULT 'SUPPLIER'
    CHECK(signal_target IN ('SUPPLIER','PROCUREMENT')),
  start_date date NOT NULL,
  end_date date,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','ACTIVE','SUSPENDED','CLOSED')),
  note text NOT NULL DEFAULT '',
  created_by_user_id uuid NOT NULL,
  activated_at timestamptz, activated_by_user_id uuid,
  closed_at timestamptz, closed_by_user_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,agreement_code),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(supplier_code)<>'' AND btrim(supplier_name)<>''),
  CHECK(end_date IS NULL OR end_date>=start_date),
  -- 生命周期与时点必须自洽：ACTIVE 必须有启用时刻，CLOSED 必须有关闭时刻。
  CHECK((status<>'ACTIVE') OR activated_at IS NOT NULL),
  CHECK((status<>'CLOSED') OR closed_at IS NOT NULL),
  -- VENDOR_OWN 是「我们不推信号、只监控」，把信号去向留着没有意义且容易让人以为会推。
  CHECK(replenishment_mode<>'VENDOR_OWN' OR signal_target='SUPPLIER')
);

-- ---------------------------------------------------------------------------
-- 2. vmi_agreement_line · 协议明细行（水位、补货倍数、结算价都在行上）
-- ---------------------------------------------------------------------------
CREATE TABLE vmi_agreement_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  -- 货主档案（7.7.1.1 V67）。VMI 的货权属供应商，所以引用的必须是
  -- counts_as_owned_asset=false 的货主 —— 由 guard_vmi_line_owner_not_owned 触发器强制。
  stock_owner_id uuid NOT NULL,
  -- 安全水位：低于它就要触发补货。蓝图「记录安全水位」的落点。
  safety_level numeric(19,6) NOT NULL,
  -- 补货倍数：建议补货量 =（安全水位 − 当前可售量）× 补货倍数（向上取整到件）。
  -- 允许 0（倍数 0 = 不额外放大，只补到水位线本身）。
  replenishment_multiple numeric(19,6) NOT NULL DEFAULT 1,
  -- 结算价：我方**售出时**付给供应商的单价，numeric(19,4)。
  settlement_price numeric(19,4) NOT NULL,
  -- 货主恒为供应商（07-inventory.md:24）。
  stock_owner_type varchar(16) NOT NULL DEFAULT 'SUPPLIER'
    CHECK(stock_owner_type = 'SUPPLIER'),
  -- VMI 货**永不**计入我方自有资产。CHECK 写死 false：想把它并进自有资产口径的人
  -- 会先被数据库挡住，而不是靠代码里记得判断。
  counts_as_owned_asset boolean NOT NULL DEFAULT false
    CHECK(counts_as_owned_asset = false),
  -- 累计已售数量。守恒式：on_hand + supplied - sold - withdrawn >= 0
  --   supplied  供应商送来的补货累计
  --   on_hand   租户上报/账本读到的当前在库量（**观测值，不是账**，见 vmi_stock_snapshot）
  --   sold      售出（唯一由 vmi_sale 驱动）
  --   withdrawn 期末供应商收回/我方退回
  supplied_quantity numeric(19,6) NOT NULL DEFAULT 0,
  sold_quantity numeric(19,6) NOT NULL DEFAULT 0,
  withdrawn_quantity numeric(19,6) NOT NULL DEFAULT 0,
  on_hand_quantity numeric(19,6) NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一协议下同一 SKU 在同一仓只允许一行：两行会让「这个 SKU 该补多少」有第二个答案。
  UNIQUE(tenant_id,agreement_id,sku_id,warehouse_id),
  FOREIGN KEY(tenant_id,agreement_id) REFERENCES vmi_agreement(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,stock_owner_id) REFERENCES stock_owner(tenant_id,id),
  CHECK(safety_level>=0),
  CHECK(replenishment_multiple>=0),
  -- 结算价允许 0（免费供货）与负值（红冲/返价），但必须显式落库 ——
  -- 应付 = 已售数量 × 结算价，这个价是应付唯一的单价来源。
  CHECK(supplied_quantity>=0 AND sold_quantity>=0 AND withdrawn_quantity>=0
        AND on_hand_quantity>=0),
  -- 守恒式 + 超卖兜底：卖出去的不得超过供应商送来的。
  -- 「已售 > 累计供货」意味着在卖供应商根本没给我们的货。
  CHECK(sold_quantity <= supplied_quantity)
);

-- ---------------------------------------------------------------------------
-- 3. vmi_stock_snapshot · 在库观测（13 销量未交付，租户可维护的真实表）
-- ---------------------------------------------------------------------------
-- VMI 货常常物理上在供应商自己的仓库，我方账本里根本没有桶 —— 那不是数据缺失，
-- 而是一种真实经营形态。这类在库量必须有地方落，且必须**标明来源**，
-- 否则前端与财务会把「租户报上来的数」误当成「系统算出来的数」。
CREATE TABLE vmi_stock_snapshot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_line_id uuid NOT NULL,
  -- 来源必填且受词表约束：不允许出现「不知道这个数哪来的」的行。
  --   TENANT_MAINTAINED 租户/运营手工维护（供应商自己的仓，我方账本没有）
  --   VENDOR_FEED         供应商对接文件/接口送来的
  --   WAREHOUSE_BUCKET    我方自有仓账本（按货主从 stock_bucket 聚合，见服务层）
  stock_source varchar(24) NOT NULL CHECK(stock_source IN
    ('TENANT_MAINTAINED','VENDOR_FEED','WAREHOUSE_BUCKET')),
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL',
  -- 采集/上报时刻必填：「这个数是什么时候的」是水位判断的前提。
  observed_at timestamptz NOT NULL,
  -- 观测到的在库可售量（不是预占后的可用量口径说明见下）：
  -- 这里记的是供应商侧在手可销量，与我方 WAREHOUSE_BUCKET 口径下的
  -- stock_bucket.available（SELLABLE 质量状态）语义一致。
  observed_quantity numeric(19,6) NOT NULL,
  -- 厂商/供应商侧的可售天数等辅助量，用于覆盖率判断（可为 NULL = 没报）。
  coverage_days numeric(19,6),
  note text NOT NULL DEFAULT '',
  recorded_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一协议行同一时刻同一来源只能有一条：重复上报撞唯一约束，不会覆盖出两条互相矛盾的数。
  UNIQUE(tenant_id,agreement_line_id,stock_source,observed_at),
  FOREIGN KEY(tenant_id,agreement_line_id) REFERENCES vmi_agreement_line(tenant_id,id),
  CHECK(observed_quantity>=0),
  CHECK(coverage_days IS NULL OR coverage_days>=0)
);

-- ---------------------------------------------------------------------------
-- 4. vmi_sale · 售出事实（上游 13 销量 / 9 订单尚未交付，本服务自落地）
-- ---------------------------------------------------------------------------
CREATE TABLE vmi_sale (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_line_id uuid NOT NULL,
  sold_quantity numeric(19,6) NOT NULL,
  -- 幂等锚点：同一外部订单行重复推送时 (tenant_id, agreement_line_id, sale_ref) 撞唯一约束，
  -- 于是「卖一次算两次应付」这条路在数据库层就没有了。
  sale_ref varchar(64) NOT NULL,
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL',
  -- 我方实际售价（零售口径），仅供毛利对照；**不参与应付计算**。
  sale_price numeric(19,4),
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  occurred_at timestamptz NOT NULL,
  recorded_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,agreement_line_id,sale_ref),
  FOREIGN KEY(tenant_id,agreement_line_id) REFERENCES vmi_agreement_line(tenant_id,id),
  CHECK(sold_quantity>0),
  CHECK(sale_price IS NULL OR sale_price>=0)
);

-- ---------------------------------------------------------------------------
-- 5. vmi_replenishment_signal · 补货信号（推给供应商或 6.2.1.2 备货）
-- ---------------------------------------------------------------------------
CREATE TABLE vmi_replenishment_signal (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  signal_code varchar(32) NOT NULL,
  agreement_line_id uuid NOT NULL,
  -- 触发原因（蓝图「按约定补货或由系统触发供应商备货」的判据）：
  --   STOCKOUT 断货（在库为 0）
  --   BELOW_SAFETY_LEVEL 低于安全水位
  --   BELOW_BUFFER 低于水位但仍高于安全水位（倍数放大后的余量不足）
  trigger_reason varchar(24) NOT NULL CHECK(trigger_reason IN
    ('STOCKOUT','BELOW_SAFETY_LEVEL','BELOW_BUFFER')),
  -- 触发时的水位快照：让「为什么补这么多」在三个月后仍可复算。
  current_sellable_quantity numeric(19,6) NOT NULL,
  safety_level numeric(19,6) NOT NULL,
  -- 建议补货量 = ceil(max(0, 安全水位 − 当前可售量) × 补货倍数)，服务层算、DB 校形。
  suggested_quantity numeric(19,6) NOT NULL,
  -- 补货倍数快照：倍数改了也不影响已发出信号的解释力。
  replenishment_multiple numeric(19,6) NOT NULL,
  -- 信号去向：SUPPLIER 推供应商 / PROCUREMENT 交给 6 采购备货（下游 6 尚未交付，只留定位键）。
  signal_target varchar(16) NOT NULL CHECK(signal_target IN ('SUPPLIER','PROCUREMENT')),
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN
    ('OPEN','ACKNOWLEDGED','DISPATCHED','FULFILLED','CANCELLED')),
  source_system varchar(32) NOT NULL DEFAULT 'WAREHOUSE',
  -- 交 6.2.1.2 / 12 的定位键：跨服务不建 FK、不查对方的表。
  reference_id varchar(64),
  acknowledged_at timestamptz, acknowledged_by_user_id uuid,
  dispatched_at timestamptz,
  fulfilled_at timestamptz,
  fulfilled_quantity numeric(19,6),
  cancelled_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,signal_code),
  FOREIGN KEY(tenant_id,agreement_line_id) REFERENCES vmi_agreement_line(tenant_id,id),
  CHECK(current_sellable_quantity>=0 AND safety_level>=0 AND replenishment_multiple>=0),
  -- 信号必须真的指向一个缺口：没有缺口就不该有信号，否则「待办」列表里全是噪音。
  CHECK(suggested_quantity>0),
  CHECK((status<>'ACKNOWLEDGED') OR acknowledged_at IS NOT NULL),
  CHECK((status<>'DISPATCHED') OR dispatched_at IS NOT NULL),
  CHECK((status<>'FULFILLED') OR (fulfilled_at IS NOT NULL AND fulfilled_quantity IS NOT NULL)),
  CHECK(fulfilled_quantity IS NULL OR fulfilled_quantity>=0)
);
-- 一行同时只允许有一条「未了结」的信号：水位没恢复就再出第二条，等于让供应商收到
-- 两份重复的备货要求。部分唯一索引 —— 带条件的唯一性不能用 UNIQUE 约束表达。
CREATE UNIQUE INDEX uq_vmi_signal_open ON vmi_replenishment_signal(tenant_id, agreement_line_id)
  WHERE status IN ('OPEN','ACKNOWLEDGED','DISPATCHED');

-- ---------------------------------------------------------------------------
-- 6. vmi_settlement · VMI 结算对账单（→ 12 结算）
-- ---------------------------------------------------------------------------
CREATE TABLE vmi_settlement (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  settlement_code varchar(32) NOT NULL,
  agreement_id uuid NOT NULL,
  -- 归期区间：按**售出时刻**落期。DB 只校验区间自洽，日期算术是服务层的口径。
  period_start date NOT NULL,
  period_end date NOT NULL,
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN
    ('OPEN','CONFIRMED','CANCELLED')),
  line_count integer NOT NULL DEFAULT 0,
  total_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- **单据级应付 HALF_UP 到分**（numeric(24,2)），由触发器按 round(Σ明细金额,2) 重算。
  -- 与明细的 4 位金额刻意不同：明细精确到分以下用于追溯，表头是拿去对账付款的数。
  total_amount numeric(24,2) NOT NULL DEFAULT 0,
  source_system varchar(32) NOT NULL DEFAULT 'WAREHOUSE',
  reference_id varchar(64),
  triggered_at timestamptz NOT NULL DEFAULT now(),
  confirmed_at timestamptz, confirmed_by_user_id uuid,
  cancelled_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,settlement_code),
  UNIQUE(tenant_id,agreement_id,period_start,period_end),
  FOREIGN KEY(tenant_id,agreement_id) REFERENCES vmi_agreement(tenant_id,id),
  CHECK(period_end>period_start),
  CHECK(line_count>=0 AND total_quantity>=0),
  CHECK((status<>'CONFIRMED') OR confirmed_at IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 7. vmi_settlement_line · 对账明细：应付金额只由「已售 × 结算价」生成
-- ---------------------------------------------------------------------------
CREATE TABLE vmi_settlement_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  settlement_id uuid NOT NULL,
  agreement_line_id uuid NOT NULL,
  -- 指向**具体那条售出事实**：这是「售出才对账」的直接落点。
  sale_id uuid NOT NULL,
  quantity numeric(19,6) NOT NULL,
  -- 结算价快照 numeric(19,4)：协议价后来改了，已出对账单的金额也不能跟着变。
  unit_price numeric(19,4) NOT NULL,
  amount numeric(24,4) GENERATED ALWAYS AS (quantity * unit_price) STORED,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 「售出 → 对账」至多一次：同一条销售事实不能进两张对账单。
  UNIQUE(tenant_id,sale_id),
  UNIQUE(tenant_id,settlement_id,agreement_line_id,sale_id),
  FOREIGN KEY(tenant_id,settlement_id) REFERENCES vmi_settlement(tenant_id,id),
  FOREIGN KEY(tenant_id,agreement_line_id) REFERENCES vmi_agreement_line(tenant_id,id),
  FOREIGN KEY(tenant_id,sale_id) REFERENCES vmi_sale(tenant_id,id),
  CHECK(quantity>0)
);

-- ---------------------------------------------------------------------------
-- 8. vmi_audit · 审计（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE vmi_audit (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_id uuid NOT NULL,
  event_type varchar(32) NOT NULL,
  detail text NOT NULL DEFAULT '',
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,agreement_id) REFERENCES vmi_agreement(tenant_id,id)
);

-- ---------------------------------------------------------------------------
-- 9. 索引（RLS 之后的实际查询面）
-- ---------------------------------------------------------------------------
CREATE INDEX idx_vmi_agreement_list ON vmi_agreement(tenant_id, status, created_at DESC);
CREATE INDEX idx_vmi_agreement_supplier ON vmi_agreement(tenant_id, supplier_party_id);
CREATE INDEX idx_vmi_line_agreement ON vmi_agreement_line(tenant_id, agreement_id);
-- 水位巡检按货主找桶（服务层聚合 stock_bucket 时的主查询面）
CREATE INDEX idx_vmi_line_owner ON vmi_agreement_line(tenant_id, stock_owner_id, sku_id, warehouse_id);
CREATE INDEX idx_vmi_snapshot_line ON vmi_stock_snapshot(tenant_id, agreement_line_id, observed_at DESC);
CREATE INDEX idx_vmi_sale_line ON vmi_sale(tenant_id, agreement_line_id, occurred_at);
CREATE INDEX idx_vmi_sale_period ON vmi_sale(tenant_id, occurred_at);
CREATE INDEX idx_vmi_signal_line ON vmi_replenishment_signal(tenant_id, agreement_line_id, status);
CREATE INDEX idx_vmi_signal_list ON vmi_replenishment_signal(tenant_id, status, created_at DESC);
CREATE INDEX idx_vmi_signal_ref ON vmi_replenishment_signal(tenant_id, source_system, reference_id);
CREATE INDEX idx_vmi_settlement_agreement ON vmi_settlement(tenant_id, agreement_id, status);
CREATE INDEX idx_vmi_settlement_ref ON vmi_settlement(tenant_id, source_system, reference_id);
CREATE INDEX idx_vmi_settlement_line_settlement ON vmi_settlement_line(tenant_id, settlement_id);
CREATE INDEX idx_vmi_audit_agreement ON vmi_audit(tenant_id, agreement_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 10. 触发器：货权红线 / 状态机 / 守恒 / append-only
-- ---------------------------------------------------------------------------

-- (a)【本模块的红线】VMI 协议行引用的货主绝不允许是「计入自有资产」的那一类。
--     7.7.1.1（V67）把 counts_as_owned_asset 做成了生成列（仅 owner_kind='SELF' 为 true）。
--     光在自己表里写死 false 不够 —— 真要把 VMI 货算成自有资产，必须去动货主档案。
--     这条触发器把那扇门焊死：货主的 counts_as_owned_asset 为 true 就直接拒绝写。
CREATE OR REPLACE FUNCTION guard_vmi_line_owner_not_owned() RETURNS trigger AS $$
DECLARE owned boolean; owner_status varchar(16); owner_code varchar(32);
BEGIN
  SELECT counts_as_owned_asset, status, code INTO owned, owner_status, owner_code
    FROM stock_owner WHERE tenant_id = NEW.tenant_id AND id = NEW.stock_owner_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '货主档案 % 不存在', NEW.stock_owner_id USING ERRCODE = 'check_violation';
  END IF;
  IF owned THEN
    RAISE EXCEPTION 'VMI 货权的货主不能是「计入自有资产」的货主（货主 % 码 %）：'
      'VMI 货属供应商，我方只有可销售权，计入自有资产会让供应商的货进我方资产负债表',
      owner_code, owner_status USING ERRCODE = 'check_violation';
  END IF;
  IF owner_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '货主 % 的状态是 %，不是 ACTIVE', owner_code, owner_status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_line_owner_not_owned
  BEFORE INSERT OR UPDATE OF stock_owner_id ON vmi_agreement_line
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_line_owner_not_owned();

-- (a2) 已建好的协议行，其货主后来被改成「计入自有资产」时必须挡住整条协议行 ——
--      否则改一次货主档案就能把整批 VMI 货悄悄搬进自有资产口径。
CREATE OR REPLACE FUNCTION guard_vmi_agreement_line_owner() RETURNS trigger AS $$
DECLARE owned boolean; owner_code varchar(32);
BEGIN
  -- OLD.id 是**货主**的 id（触发器挂在 stock_owner 上），所以要问的是
  -- 「这个货主现在算不算自有资产，而且有没有 VMI 协议行还指着它」。
  -- 输出变量刻意不叫 code：PL/pgSQL 默认 plpgsql.variable_conflict=error，
  -- 输出变量与列同名会直接报 "column reference \"code\" is ambiguous"，
  -- 而那句报错完全指不到「货权红线」这件事上。
  SELECT s.counts_as_owned_asset, s.code INTO owned, owner_code FROM stock_owner s
   WHERE s.tenant_id = OLD.tenant_id AND s.id = OLD.id;
  IF NOT owned THEN RETURN NULL; END IF;
  IF EXISTS (SELECT 1 FROM vmi_agreement_line l
              WHERE l.tenant_id = OLD.tenant_id AND l.stock_owner_id = OLD.id) THEN
    RAISE EXCEPTION '货主 % 已被改为「计入自有资产」，其下还有 VMI 协议行指向它：'
      'VMI 货属供应商，请先把协议行迁到另一个货主再改这里', owner_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

-- **立即**触发而不是 DEFERRABLE 延迟到事务末尾，理由与 V71 的
-- consignment_settlement_totals 同源：延迟约束触发器在 COMMIT 时抛错，Spring 的异常转换
-- 会把它翻译成一句没有消息的「JDBC commit; bad SQL grammar []」，
-- 调用方拿到的是一个指不到原因的 500。这里不涉及跨表守恒（只是问「有没有 VMI 行指着它」），
-- 立即触发能把 PostgreSQL 的真实原因原样抛出来 —— 拒绝要让人看得懂为什么被拒。
CREATE TRIGGER vmi_line_owner_still_not_owned
  AFTER UPDATE ON stock_owner
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_agreement_line_owner();

-- (b) 协议状态机：只认 DRAFT→ACTIVE→SUSPENDED↔ACTIVE→CLOSED，CLOSED 不可复活。
CREATE OR REPLACE FUNCTION guard_vmi_agreement_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT'     THEN NEW.status = 'ACTIVE'
    WHEN 'ACTIVE'    THEN NEW.status IN ('SUSPENDED','CLOSED')
    WHEN 'SUSPENDED' THEN NEW.status IN ('ACTIVE','CLOSED')
    WHEN 'CLOSED'    THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION 'vmi agreement % cannot move % -> %', OLD.agreement_code, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_agreement_state
  BEFORE UPDATE ON vmi_agreement
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_agreement_state();

-- (c) 协议已关闭后其明细行不得再有任何数量变动（货已结清，账不能再动）。
CREATE OR REPLACE FUNCTION guard_vmi_line_after_close() RETURNS trigger AS $$
DECLARE closed boolean;
BEGIN
  IF NEW.supplied_quantity IS NOT DISTINCT FROM OLD.supplied_quantity
     AND NEW.sold_quantity IS NOT DISTINCT FROM OLD.sold_quantity
     AND NEW.withdrawn_quantity IS NOT DISTINCT FROM OLD.withdrawn_quantity
     AND NEW.on_hand_quantity IS NOT DISTINCT FROM OLD.on_hand_quantity
     AND NEW.safety_level IS NOT DISTINCT FROM OLD.safety_level
     AND NEW.replenishment_multiple IS NOT DISTINCT FROM OLD.replenishment_multiple
     AND NEW.settlement_price IS NOT DISTINCT FROM OLD.settlement_price THEN
    RETURN NEW;
  END IF;
  SELECT (status = 'CLOSED') INTO closed FROM vmi_agreement
   WHERE tenant_id = NEW.tenant_id AND id = NEW.agreement_id;
  IF closed THEN
    RAISE EXCEPTION 'vmi line % belongs to a CLOSED agreement and is frozen', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_line_frozen
  BEFORE UPDATE ON vmi_agreement_line
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_line_after_close();

-- (d) 售出不得超过累计供货 + 售出不得超过当前在库（服务层之外的第二道兜底）。
CREATE OR REPLACE FUNCTION guard_vmi_line_sold_bounds() RETURNS trigger AS $$
BEGIN
  IF NEW.sold_quantity > NEW.supplied_quantity THEN
    RAISE EXCEPTION '已售 % 超过累计供货 %：在卖供应商根本没送到的货',
      NEW.sold_quantity, NEW.supplied_quantity USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.on_hand_quantity + NEW.sold_quantity < NEW.withdrawn_quantity THEN
    RAISE EXCEPTION '退回 % 超过「在库 % + 已售 %」：供应商收回了不存在的货',
      NEW.withdrawn_quantity, NEW.on_hand_quantity, NEW.sold_quantity
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_line_sold_bounds
  BEFORE INSERT OR UPDATE ON vmi_agreement_line
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_line_sold_bounds();

-- (e) 对账单表头合计 == round(Σ明细金额, 2)，明细数 == Σ行数。
--     **立即**触发而不是延迟到事务末尾：延迟触发会让「刚建好的对账单读出来合计是 0」。
CREATE OR REPLACE FUNCTION guard_vmi_settlement_totals() RETURNS trigger AS $$
DECLARE target_id uuid; target_tenant uuid;
BEGIN
  target_id := coalesce(NEW.settlement_id, OLD.settlement_id);
  target_tenant := coalesce(NEW.tenant_id, OLD.tenant_id);
  -- 子查询里不能用 UPDATE 的目标别名 s（FROM 子查询不是 LATERAL，引不到目标表）。
  UPDATE vmi_settlement s
     SET total_quantity = agg.qty,
         total_amount   = agg.amt,
         line_count     = agg.cnt,
         updated_at     = now()
    FROM (select coalesce(sum(quantity),0) as qty,
                 round(coalesce(sum(amount),0), 2) as amt,
                 count(*) as cnt
            from vmi_settlement_line
           where tenant_id = target_tenant and settlement_id = target_id) agg
   WHERE s.tenant_id = target_tenant and s.id = target_id;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_settlement_totals
  AFTER INSERT OR UPDATE OR DELETE ON vmi_settlement_line
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_settlement_totals();

-- (f) 已确认/已作废的对账明细不可再改：单据已经发给供应商对账，改它等于篡改对外账。
CREATE OR REPLACE FUNCTION guard_vmi_settlement_line_locked() RETURNS trigger AS $$
DECLARE st varchar(16);
BEGIN
  SELECT status INTO st FROM vmi_settlement
   WHERE tenant_id = NEW.tenant_id AND id = NEW.settlement_id;
  IF st IN ('CONFIRMED','CANCELLED') THEN
    RAISE EXCEPTION 'vmi settlement % is % and its lines are frozen', NEW.settlement_id, st
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_settlement_line_locked
  BEFORE INSERT OR UPDATE OR DELETE ON vmi_settlement_line
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_settlement_line_locked();

-- (g) 对账单状态机：OPEN → CONFIRMED / CANCELLED，终态不可翻案。
CREATE OR REPLACE FUNCTION guard_vmi_settlement_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status
     AND NOT ((OLD.status = 'OPEN' AND NEW.status IN ('CONFIRMED','CANCELLED'))) THEN
    RAISE EXCEPTION 'vmi settlement % cannot move % -> %',
      OLD.settlement_code, OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.status = 'CONFIRMED' AND NEW.line_count = 0 THEN
    RAISE EXCEPTION '对账单 % 没有任何明细，不允许确认：没有售出就没有应付',
      NEW.settlement_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_settlement_state
  BEFORE UPDATE ON vmi_settlement
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_settlement_state();

-- (h) 售出事实不可改删：它已经是对账依据，改它等于改账。
CREATE OR REPLACE FUNCTION guard_vmi_sale_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'vmi sale is immutable; record a correcting sale instead'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_sale_immutable
  BEFORE UPDATE OR DELETE ON vmi_sale
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_sale_immutable();

-- (i) 信号状态机：OPEN → ACKNOWLEDGED → DISPATCHED → FULFILLED，任一步可 → CANCELLED。
CREATE OR REPLACE FUNCTION guard_vmi_signal_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    allowed := CASE OLD.status
      WHEN 'OPEN'         THEN NEW.status IN ('ACKNOWLEDGED','DISPATCHED','CANCELLED')
      WHEN 'ACKNOWLEDGED' THEN NEW.status IN ('DISPATCHED','CANCELLED')
      WHEN 'DISPATCHED'   THEN NEW.status IN ('FULFILLED','CANCELLED')
      ELSE false   -- FULFILLED / CANCELLED 是终态，不可翻案
    END;
    IF NOT allowed THEN
      RAISE EXCEPTION 'vmi signal % cannot move % -> %',
        OLD.signal_code, OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_signal_state
  BEFORE UPDATE ON vmi_replenishment_signal
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_signal_state();

-- (j) 审计 append-only：禁改禁删（权限层再收一道，见下）。
CREATE OR REPLACE FUNCTION guard_vmi_audit_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'vmi audit is append-only' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER vmi_audit_append_only
  BEFORE UPDATE OR DELETE ON vmi_audit
  FOR EACH ROW EXECUTE FUNCTION guard_vmi_audit_append_only();

-- ===========================================================================
-- 11. 多租户与授权（与 V1/V2/V67/V71 同一套写法）
-- ===========================================================================
-- 八张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['vmi_agreement','vmi_agreement_line','vmi_stock_snapshot',
                             'vmi_sale','vmi_replenishment_signal','vmi_settlement',
                             'vmi_settlement_line','vmi_audit'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  vmi_agreement, vmi_agreement_line, vmi_stock_snapshot, vmi_sale,
  vmi_replenishment_signal, vmi_settlement, vmi_settlement_line, vmi_audit TO warehouse_app;
-- 售出事实与审计是凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
REVOKE UPDATE,DELETE ON vmi_sale, vmi_audit FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V67/V71 同口径）。
REVOKE ALL ON
  vmi_agreement, vmi_agreement_line, vmi_stock_snapshot, vmi_sale,
  vmi_replenishment_signal, vmi_settlement, vmi_settlement_line, vmi_audit FROM starcon_app;

-- 单号发号器同样要显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列
-- （漏了就是 "permission denied for sequence"，而错误文案完全指不到授权上）。
GRANT USAGE, SELECT ON SEQUENCE
  vmi_agreement_code_seq, vmi_settlement_code_seq, vmi_signal_code_seq TO warehouse_app;

-- ---------------------------------------------------------------------------
-- 12. 跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表/序列/索引/触发器，不 ALTER 任何既有表**：
--   * 没有 ALTER TABLE stock_bucket / inventory_balance（7.1.1.3 那条线的唯一写入所有者）；
--   * 没有 ALTER TABLE stock_owner（7.7.1.1 已交付）—— 只在它上面**挂一个延迟约束触发器**
--     监听 UPDATE，不改它的任何列、约束或索引；
--   * 没有 ALTER 寄售的 consignment_*（7.7.1.2 已交付）。
-- 因此既有模块的插入路径完全不受影响。