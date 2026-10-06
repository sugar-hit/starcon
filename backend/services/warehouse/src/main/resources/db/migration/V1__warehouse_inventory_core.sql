-- 7.1.1.1 库存总览：warehouse 服务首版迁移。
--
-- 服务归属（蓝图 07 卷「后端归属」）：warehouse 是 stock_bucket / inventory_balance /
-- inventory_transaction / inventory_reservation / inventory_lot / inventory_serial 的
-- **唯一**所有者。本迁移只建 7.1.1.1 总览真正读得到的那几张；reservation / lot / serial
-- 分别属 7.2.1.x 与 7.1.1.2，本期**不建空表占位**。
--
-- ---------------------------------------------------------------------------
-- 跨服务边界（本迁移的硬纪律，AGENTS.md「模块边界纪律」）
-- ---------------------------------------------------------------------------
--   * **不建任何跨服务外键。** sku 属 catalog、legal_entity 属 identity、shop 属 channel，
--     四个服务各有独立数据库，因此本库对它们只存**逻辑 ID + 必要快照**，
--     既不 REFERENCES 对方表，也不写跨库 JOIN。存在性与租户归属由 owner 的
--     授权 API / 可信事件确认（实体目录 §2）。
--   * `sku_ref` / `legal_entity_ref` 是**引用登记**而不是主数据镜像：code/name 只服务于
--     列表展示与筛选，权威仍在 catalog / identity（与 identity V5 的
--     legal_entity_reference 同一思路：跨服务引用要有一份本地可查的登记事实）。
--   * inventory_balance.sku_id / legal_entity_id 同样不建外键，只登记本服务真正持有的货。
--   * 设计稿 04-inventory.md 的 `fk_balance_sku` 是跨服务外键，**本迁移不采纳**。
--
-- ---------------------------------------------------------------------------
-- 与 db/schemas/04-inventory.md 设计稿的逐条差异（不静默偏离）
-- ---------------------------------------------------------------------------
--   1. 键**不含** location_id —— 与设计稿一致。履约的可用量决策单位是仓库不是库位，
--      把库位放进键里就得 SUM 后再 UPDATE，重新引入"先读余量再无锁写回"的竞态。
--   2. 键额外含 legal_entity_id —— 蓝图 7.1.1.1 要求「按仓库/法人/SKU 维度汇总」，
--      7.1.1.2 把最小事实单位定义为「法人货权 × SKU × 仓库 × 库位 × 批次 × 质量状态」。
--      同一仓内不同法人的货不是同一批货：混在一行就答不出"这批货算哪个法人的"，
--      货权维度的总量也无法守恒。设计稿成文时该维度还没进入总览口径。
--   3. 键额外含 quality_status_id —— 总览要分别展示可售 / 待检 / 残次。
--      把三者塞进一列就等于让应用层把一个总量拆成三个语义不同的数，
--      既不可审阅也会与 on_hand 长期漂移；按质量状态分行则守恒由外键与 UNIQUE 保证。
--   4. `available` 仍是 `GENERATED ALWAYS AS (on_hand - reserved) STORED` 生成列，
--      **没有第三个来源**：总览的可用量只能由它聚合得出。
--   5. 不保留设计稿的 `ck_balance_available` 之外的任何"应用层兜底"——
--      超卖的 DB 级兜底就是 ck_balance_available 本身。
--
-- ---------------------------------------------------------------------------
-- 数量口径（总览六列的定义；总账守恒完全依赖这一段口径）
-- ---------------------------------------------------------------------------
--   在手 on_hand     = 在仓实物 + 在途
--   在途 in_transit  = Σ inventory_transfer.quantity WHERE status='IN_TRANSIT'
--   可售 / 待检 / 残次 = 在仓实物按 inventory_quality_status.role 拆分
--   预占 reserved    = Σ inventory_balance.reserved
--   可用 available   = Σ inventory_balance.available（= on_hand − reserved，生成列）
--
--   **守恒式：在手 = 可售 + 待检 + 残次 + 在途。**
--   在途并入手却不并入可用：货权已归本租户但实物尚未到达收货仓，并入手才不会在调拨
--   在途期间凭空少一批货；但它**绝不能**计入可用 —— 未到货的库存拿去预占就是超卖，
--   而超卖是不可逆事故。所以可用严格等于生成列的 SUM，不含在途，也没有第三个来源。
--
--   数量一律 NUMERIC(19,4)（设计稿口径），Java 侧一律 BigDecimal，全程禁浮点记账。

-- ---------------------------------------------------------------------------
-- 角色
-- ---------------------------------------------------------------------------
-- 角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行（与 channel V1 同口径）。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='warehouse_app') THEN CREATE ROLE warehouse_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO warehouse_app;

-- ===========================================================================
-- 1. 跨服务引用登记（逻辑 ID + 必要快照，无外键）
-- ===========================================================================

-- 法人引用登记。identity 是法人主数据的唯一所有者（identity V5__legal_entity.sql），
-- warehouse 只登记"我这里这批货算哪个法人"这一个事实，属性以 identity 为准。
CREATE TABLE legal_entity_ref (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(64) NOT NULL, name varchar(200) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);

-- SKU 引用登记。catalog 是 SKU 档案的唯一所有者；这里存的是 SKU 本身的逻辑 ID 与
-- 展示快照，**不存**类目 / 品牌 / 售价 / 库存 —— 那些必须走 catalog 授权 API 或事件，
-- 绝不复制成本地权威列（否则会出现两份"库存/类目"各说各话）。
CREATE TABLE sku_ref (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(64) NOT NULL, name varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);

-- ===========================================================================
-- 2. 仓库主数据（本服务自有，可建外键）
-- ===========================================================================
CREATE TABLE warehouse (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(64) NOT NULL, name varchar(200) NOT NULL,
  country_code varchar(2) NOT NULL, timezone varchar(64) NOT NULL,
  -- 货权法人：仓库本身可归属默认法人，但**货权按 balance 行判定**，不靠仓库默认值
  -- 推断（同一仓可以同时存放多个法人的货，见下方 inventory_balance）。
  default_legal_entity_id uuid,
  is_default boolean NOT NULL DEFAULT false,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE','ARCHIVED')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,code),
  FOREIGN KEY(tenant_id,default_legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(code)<>'' AND btrim(name)<>'' AND length(btrim(country_code))=2)
);
-- 每个租户至多一个默认发货仓（部分唯一索引：带条件的唯一约束不能用 UNIQUE 约束表达）。
CREATE UNIQUE INDEX uq_warehouse_default ON warehouse(tenant_id) WHERE is_default AND status='ACTIVE';

-- ===========================================================================
-- 3. 质量状态维度（租户级真实字典，不是写死的 enum）
-- ===========================================================================
-- 为什么是表而不是 Java enum 常量：
--   * 总览要按质量状态分行，待检 / 残次是**租户自己的经营口径**（不同品类对"待检"的
--     定义不同），写死成代码常量就没法按租户调整。
--   * 7.1.2.x 的质检单据要往同一套状态里落值，枚举常量改一次就要动所有已落库的行。
--   * role 是**受控的三值词表**（不是自由文本）：守恒式要求在仓实物能被这三个角色
--     完整覆盖，否则 Σ on_hand 会漏掉一块，总账立刻对不上。
CREATE TABLE inventory_quality_status (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL, name varchar(100) NOT NULL,
  role varchar(20) NOT NULL CHECK(role IN ('SELLABLE','PENDING_INSPECTION','DEFECTIVE')),
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
CREATE INDEX inventory_quality_status_role ON inventory_quality_status(tenant_id,role,code);

-- ===========================================================================
-- 4. 库存余额（权威可用量；总览唯一的数量来源）
-- ===========================================================================
CREATE TABLE inventory_balance (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  quality_status_id uuid NOT NULL,
  on_hand numeric(19,4) NOT NULL DEFAULT 0,
  reserved numeric(19,4) NOT NULL DEFAULT 0,
  -- 可用量是**生成列**：不可能与 on_hand / reserved 漂移。若它是普通列，
  -- 任何一次忘记同步的 UPDATE 都会让它长期失真，而且极难被发现。
  available numeric(19,4) GENERATED ALWAYS AS (on_hand - reserved) STORED,
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,sku_id,warehouse_id,legal_entity_id,quality_status_id),
  -- 仓、货权法人、质量状态都在本服务内，可以建复合外键；sku 不行（见文件头）。
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  CHECK(on_hand>=0),
  CHECK(reserved>=0),
  -- 超卖的 DB 级兜底：即使应用层漏了判断，DB 也会拒绝把余额预占成负数。
  CHECK(available>=0)
);

-- 预占只能发生在**可售**质量状态上。DEFECTIVE / PENDING_INSPECTION 的货一旦被预占，
-- available 会算成"可发货"，这是超卖的另一种形态。应用层会先判，这里再兜一道。
CREATE FUNCTION guard_balance_reservable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r varchar(20);
BEGIN
 IF NEW.reserved<=0 THEN RETURN NEW; END IF;
 SELECT q.role INTO r FROM inventory_quality_status q
  WHERE q.tenant_id=NEW.tenant_id AND q.id=NEW.quality_status_id;
 IF r<>'SELLABLE' THEN
  RAISE EXCEPTION '质量状态「%」的货不可预占：预占会把非可售数量算成可发货', r USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_balance_reservable BEFORE INSERT OR UPDATE ON inventory_balance
 FOR EACH ROW EXECUTE FUNCTION guard_balance_reservable();

-- ===========================================================================
-- 5. 库存业务单 + 带符号分录（append-only，一次业务 = 1 单 + N 条分录）
-- ===========================================================================
-- 为什么必须是「单 + 分录」而不是直接 UPDATE 余额：余额是可被随时重算的**投影**，
-- 一次业务涉及几个 SKU / 几个仓时靠多次 UPDATE 记不住"这批改动属于哪一件事"，
-- 对不上账时也拿不出证据。分录是带符号的，Σ 分录 = 余额变化，这是可审阅的。
CREATE TABLE inventory_transaction (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  kind varchar(24) NOT NULL CHECK(kind IN (
    'RECEIPT','SHIPMENT','ADJUSTMENT','TRANSFER_OUT','TRANSFER_IN','RETURN','RESERVE','RELEASE')),
  -- 幂等锚点：同一来源单据重复投递时靠它去重（同 7.1.2.1 流水台账口径）。
  ref_type varchar(32) NOT NULL, ref_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  actor_id uuid NOT NULL,
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,ref_type,ref_id),
  CHECK(btrim(ref_type)<>'')
);

CREATE TABLE inventory_entry (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  transaction_id uuid NOT NULL,
  balance_id uuid NOT NULL,
  -- 带符号：入库为正、出库为负、预占走 reserved_delta。
  on_hand_delta numeric(19,4) NOT NULL,
  reserved_delta numeric(19,4) NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,transaction_id) REFERENCES inventory_transaction(tenant_id,id),
  FOREIGN KEY(tenant_id,balance_id) REFERENCES inventory_balance(tenant_id,id),
  CHECK(on_hand_delta<>0 OR reserved_delta<>0)
);
CREATE INDEX inventory_entry_balance ON inventory_entry(tenant_id,balance_id,created_at DESC);
CREATE INDEX inventory_entry_transaction ON inventory_entry(tenant_id,transaction_id);

-- append-only：余额的真相来源只能追加，冲正靠再写一条相反的分录。
-- 少了这条，任何"顺手改一下"都能把账改平而查不出是谁改的。
CREATE FUNCTION guard_inventory_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是 append-only：不允许 %，要改就写一条相反的 % 分录',
  TG_TABLE_NAME, TG_OP, TG_OP
  USING ERRCODE='23514';
END $$;
CREATE TRIGGER inventory_entry_append_only BEFORE UPDATE OR DELETE ON inventory_entry
 FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();
CREATE TRIGGER inventory_transaction_append_only BEFORE UPDATE OR DELETE ON inventory_transaction
 FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();

-- ===========================================================================
-- 6. 在途（7.5 调拨与在途未开发；这里只建最小的**真实**存储，不写死 0）
-- ===========================================================================
-- 口径：已从发货仓出库、尚未在收货仓入库的货。货权已离开原仓的 on_hand，
-- 收货仓确认入库时才进入收货仓的 on_hand（TRANSFER_IN 分录）。
CREATE TABLE inventory_transfer (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  from_warehouse_id uuid NOT NULL,
  to_warehouse_id uuid NOT NULL,
  quality_status_id uuid NOT NULL,
  quantity numeric(19,4) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'IN_TRANSIT' CHECK(status IN ('IN_TRANSIT','RECEIVED','CANCELLED')),
  ref_type varchar(32) NOT NULL DEFAULT '', ref_id uuid,
  shipped_at timestamptz NOT NULL,
  received_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,from_warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,to_warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  CHECK(quantity>0),
  CHECK(from_warehouse_id<>to_warehouse_id),
  -- 状态与时间戳必须一致：已收货 / 已取消的必须有收货时间，在途的必须没有。
  -- 少了这条，"在途"这一列的求和口径就随时间漂移。
  CHECK((status='IN_TRANSIT')=(received_at IS NULL))
);
CREATE INDEX inventory_transfer_in_transit
  ON inventory_transfer(tenant_id,sku_id,to_warehouse_id) WHERE status='IN_TRANSIT';

-- ===========================================================================
-- 7. 库存阈值策略（超储判定的**真实**来源；7.2.2 在此基础上扩展）
-- ===========================================================================
-- 不硬编码常量：超储线是租户经营决策，不同品类差一个数量级。
-- 刻意不把字段设计死：`scope_type` / `scope_key` 用受控词表 + 自由键，
-- 7.2.2 要加补货点 / 安全库存 / 采购提前期时，改的是列和取值，不是整张表。
-- 解析顺序 SKU > CATEGORY > WAREHOUSE > DEFAULT（越具体越优先），在服务层写死并测试。
CREATE TABLE inventory_policy (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  scope_type varchar(16) NOT NULL CHECK(scope_type IN ('SKU','CATEGORY','WAREHOUSE','DEFAULT')),
  -- SKU: sku_ref.id；CATEGORY: 类目文本（catalog 侧是自由文本列，3.3.1.1 类目树未开发，
  -- 与 catalog V9 的 category_spec_axis 同一口径）；WAREHOUSE: warehouse.id；DEFAULT: ''。
  scope_key varchar(100) NOT NULL DEFAULT '',
  max_on_hand numeric(19,4),
  min_on_hand numeric(19,4),
  -- 预留 7.2.2：补货点与安全库存先落在这里，本期总览不读，但列一旦缺了 7.2.2
  -- 只能改表；先把可扩展的口子留好，避免下个模块来重开本迁移。
  reorder_point numeric(19,4),
  safety_stock numeric(19,4),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- NULLS NOT DISTINCT：DEFAULT 行的 scope_key 是 ''，不能靠 IS NULL 去重，
  -- 否则同一租户能插出多条 DEFAULT 阈值，解析顺序就没有唯一答案了。
  UNIQUE NULLS NOT DISTINCT(tenant_id,scope_type,scope_key),
  CHECK(max_on_hand IS NULL OR max_on_hand>0),
  CHECK(min_on_hand IS NULL OR min_on_hand>=0),
  CHECK(reorder_point IS NULL OR reorder_point>=0),
  CHECK(safety_stock IS NULL OR safety_stock>=0),
  -- 范围与键必须配套：DEFAULT 不带键，SKU 必须带键。少一条就会出现一条
  -- 永远命中不了的策略行，超储判定随调用参数漂移。
  CHECK((scope_type='DEFAULT')=(btrim(scope_key)=''))
);
CREATE INDEX inventory_policy_lookup ON inventory_policy(tenant_id,scope_type,scope_key) WHERE enabled;

-- ===========================================================================
-- 8. 总览热读路径的索引
-- ===========================================================================
-- 汇总表主查询：租户 + 可选仓/法人/SKU 过滤后按 on_hand 排序取页。
CREATE INDEX inventory_balance_overview ON inventory_balance(tenant_id,warehouse_id,legal_entity_id,sku_id);
-- 零库存识别：available 是生成列，表达式索引让 available<=0 的扫描走索引。
CREATE INDEX inventory_balance_zero ON inventory_balance(tenant_id) WHERE available<=0;
-- 单 SKU 在多仓的分布（行内展开的仓库级拆解走这条）。
CREATE INDEX inventory_balance_sku ON inventory_balance(tenant_id,sku_id,warehouse_id);
CREATE INDEX legal_entity_ref_list ON legal_entity_ref(tenant_id,status,code);
CREATE INDEX sku_ref_list ON sku_ref(tenant_id,code);
CREATE INDEX warehouse_list ON warehouse(tenant_id,status,code);

-- ===========================================================================
-- 9. 多租户：RLS + 授权
-- ===========================================================================
-- 全部是租户业务表，一视同仁 FORCE。`current_setting('app.current_tenant')` 由
-- backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'legal_entity_ref','sku_ref','warehouse','inventory_quality_status','inventory_balance',
   'inventory_transaction','inventory_entry','inventory_transfer','inventory_policy'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- warehouse_app 是运行角色：全表 RLS 隔离后可读写。
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO warehouse_app;
-- 流水与分录 append-only：连运行角色都不给改删，兜底在权限层而不是只在触发器里。
REVOKE UPDATE,DELETE ON inventory_entry, inventory_transaction FROM warehouse_app;
REVOKE ALL ON flyway_schema_history FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 channel V1 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
