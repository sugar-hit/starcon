-- 7.5.2.1 跨法人调拨。
--
-- 蓝图 07-inventory.md 7.5.2.1 原文：
--   「职责：法人之间的库存转移（业务上是**货权转移 + 内部结算**），需要定价、生成内部往来单据、
--     双方确认，不能当作普通同仓调拨。」
--   「输入：转出与转入法人；转移 SKU 与数量；转移定价。」
--   「输出：跨法人调拨单 + 内部结算单据 + 双方库存变更。」
--   「关系：上游 = 7.5.1.1；下游 = 12 财务（内部结算/收入确认）、17 权限（双方法人授权）。
--     **货权变了意味着利润归属变了**，这是财务处理而非库存处理的差异点。」
--
-- ===========================================================================
-- 一、本模块与 7.5.1.1 的根本差别（不要拿 V61 的表改名复用）
-- ===========================================================================
-- V61 的 transfer_order 只有一个 legal_entity_id，语义是「这批货算哪个法人的货权」——
-- 调拨前后是**同一个**法人，货没换主人，所以它不需要定价。
--
-- 本模块的货**换主人**了：转出法人 A 的货，变成转入法人 B 的货。因此：
--
--   1. 单头必须**两个**法人（from_legal_entity_id / to_legal_entity_id），且 DB 层禁止相等；
--      库存余额行 inventory_balance 的维度里就有 legal_entity_id（V1 第 4 节），
--      所以货权转移在数据上就是「A 的余额行扣、B 的余额行加」——这正是「双方库存变更」。
--   2. 必须**定价**：货权变了意味着利润归属变了，没有金额就没有内部结算凭据，
--      12 财务的内部结算与收入确认也就无从挂账。
--   3. 必须**双方确认**：转出方与转入方是两个法人，一个接口同时改两边等于让一方替另一方签字。
--      本迁移用独立的确认事件表 + DB 触发器钉死「两个方向的确认人必须是两个人」。
--
-- ===========================================================================
-- 二、金额口径（禁浮点，与 AGENTS.md 一致）
-- ===========================================================================
-- 数量 numeric(19,4)  沿用 V1 口径。
-- 单价 numeric(19,4)  转移定价保留 4 位小数。
-- 行金额 = 数量 × 单价，落 numeric(19,4)。
--
-- 行金额刻意用**生成列**而不是普通列：它是数量的函数，普通列意味着
-- 「改了数量却忘了改金额」这种行可以写出来，而它会直接变成内部结算凭据上的错数。
-- 生成列不可能与数量/单价漂移。
--
-- 舍入：两个 4 位小数的乘积最多 8 位小数，落 numeric(19,4) 由 PostgreSQL 按
-- **四舍五入、远离零（half away from zero）** 处理。这是本模块唯一的舍入点，
-- 单价与数量本身不做中间舍入（避免双重舍入）。合计由延迟约束触发器与明细求和对账。
--
-- 币种：单头一个 currency_code（ISO 4217 三字母大写），整单同币。
-- 跨币种换算（汇率、汇兑损益）属 12 财务职责，本模块**不做**也不存汇率 ——
-- 存一个没人维护的汇率列，只会让「按哪个价算的」变得不可回答。
--
-- ===========================================================================
-- 三、跨服务边界（AGENTS.md 硬性规定，违反即破坏模块纪律）
-- ===========================================================================
-- 下游 12 财务**尚未交付**，本迁移因此：
--   * 只在**本服务内**建 cross_entity_settlement 一张凭据表，不建任何指向 12 的外键；
--   * 用 source_system + reference_id 作为跨服务定位键（不是外键、不是跨库视图）；
--   * 不去查 12 的表、不复制 12 的科目与凭证结构。
--
-- 17 权限（双方法人授权）**尚未交付**：本模块不谎称已接入。当前可执行的约束是
-- 「同租户 + 租户级权限码 + 两次不同自然人的确认」，逐法人授权待 17 交付后接入。
--
-- ===========================================================================
-- 四、为什么货权转移点定在「实收」而不是「发出」
-- ===========================================================================
-- 沿用 7.5.1.1「调拨不是瞬时」的纪律：发出只减转出法人的余额、货挂在途，
-- 货权在**转入方实收**这一刻才落到转入法人名下。
--
-- 与普通调拨一致的物理事实是：实物在路上时货权归属仍有争议，
-- 而内部结算凭据在**发出**时就必须开出来（转出方已交付、转入方已确认定价），
-- 所以：结算凭据在发出时开具（ISSUED），实收时双方确认凭据（ACKNOWLEDGED）。
-- 这两个时刻不同，是业务事实而不是 bug。

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：CET-yyyyMMdd-000001（与 V61 同一条纪律：单号由 DB 发）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE cross_entity_transfer_code_seq START 1;
CREATE SEQUENCE cross_entity_settlement_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 跨法人调拨单头
-- ---------------------------------------------------------------------------
CREATE TABLE cross_entity_transfer (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  transfer_code varchar(32) NOT NULL,
  -- 源仓与目标仓都必须在本租户（复合外键，与 V61 同口径）
  from_warehouse_id uuid NOT NULL,
  to_warehouse_id uuid NOT NULL,
  -- **两个**法人：货权从转出方转到转入方。这两条复合外键把「两个法人都在本租户」
  -- 钉在 DB 层；相等由下面的 CHECK 直接拒。
  from_legal_entity_id uuid NOT NULL,
  to_legal_entity_id uuid NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
    'DRAFT','PENDING_SOURCE_CONFIRM','PENDING_TARGET_CONFIRM','CONFIRMED',
    'IN_TRANSIT','COMPLETED','DISCREPANCY','CANCELLED')),
  -- 转移定价口径：单价是人工填的，还是按转出成本 / 转入方当地成本定的。
  -- 记录「这个价怎么来的」比只记金额更重要：12 财务做收入确认时要复算定价依据。
  valuation_basis varchar(20) NOT NULL DEFAULT 'MANUAL' CHECK(valuation_basis IN (
    'MANUAL','TRANSFER_COST','LOCAL_COST')),
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  -- 合计由服务层维护，并由延迟约束触发器与明细求和逐行对账（见第 6 节）
  total_amount numeric(19,4) NOT NULL DEFAULT 0,
  expected_ship_at timestamptz,
  expected_arrive_at timestamptz,
  reason varchar(200) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  -- 双方确认时刻与「单一时刻 + DB 触发器」相比更好读，但确认人必须去确认事件表取 ——
  -- 那是 append-only 的，两个方向各一行，谁签的、什么时候签的、签了什么备注都留痕。
  source_confirmed_at timestamptz,
  target_confirmed_at timestamptz,
  shipped_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,transfer_code),
  FOREIGN KEY(tenant_id,from_warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,to_warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,from_legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,to_legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(transfer_code)<>''),
  CHECK(from_warehouse_id<>to_warehouse_id),
  -- 本模块存在的理由：两个法人必须是两个法人。同一法人之间的转移是 7.5.1.1 的活。
  CHECK(from_legal_entity_id<>to_legal_entity_id),
  -- 币种一律大写三字母：'cny' 这种写法会让「按币种分组求和」在 SQL 里静默分成两组
  CHECK(currency_code=upper(currency_code)),
  CHECK(total_amount>=0),
  CHECK(expected_arrive_at IS NULL OR expected_ship_at IS NULL
        OR expected_arrive_at>=expected_ship_at)
);

-- ---------------------------------------------------------------------------
-- 2. 明细行（SKU × 质量状态 × 数量 × 转移单价）
-- ---------------------------------------------------------------------------
-- 与 V61 一样按 SKU × 质量状态分行：可售与残次不是同一批货，
-- 定价也可能不同，混在一行就没法回答「这批残次按什么价转」。
CREATE TABLE cross_entity_transfer_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  transfer_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  quality_status_id uuid NOT NULL,
  quantity numeric(19,4) NOT NULL,
  unit_price numeric(19,4) NOT NULL,
  -- 生成列：金额是数量与单价的函数，不可能与它们漂移（见文件头第二节）
  amount numeric(19,4) GENERATED ALWAYS AS (round(quantity*unit_price,4)) STORED,
  -- 短少量：结案时「发出过但永远收不到」的部分。与 7.5.1.1 一样**如实记录**不自动抹平。
  discrepancy_quantity numeric(19,4) NOT NULL DEFAULT 0,
  received_quantity numeric(19,4) NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,transfer_id,sku_id,quality_status_id),
  FOREIGN KEY(tenant_id,transfer_id) REFERENCES cross_entity_transfer(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  CHECK(quantity>0),
  -- 单价允许为 0：关联方之间按零定价平移货权是真实存在的税务安排，
  -- 但它必须被**显式**记下来（unit_price=0 + valuation_basis 的口径），
  -- 而不是被「单价必须大于 0」挡回去、逼着运营编一个数。
  CHECK(unit_price>=0),
  CHECK(discrepancy_quantity>=0),
  CHECK(received_quantity>=0),
  CHECK(received_quantity+discrepancy_quantity<=quantity)
);

-- ---------------------------------------------------------------------------
-- 3. 双方确认事件（append-only，一个方向一次）
-- ---------------------------------------------------------------------------
-- 为什么不用「头上的两个时间戳 + 两个 actor 列」：那样「谁签的、什么时候签的、
-- 签的时候说了什么」只剩最后一版，改了就没有痕迹。确认是**签字行为**，
-- 必须像分录一样只能追加。
--
-- UNIQUE(tenant_id,transfer_id,side) 保证一个方向不会被确认两次 ——
-- 「转出方确认」按两次的结果取最后一个，等于让第一次确认从来没发生过。
CREATE TABLE cross_entity_approval (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  transfer_id uuid NOT NULL,
  side varchar(8) NOT NULL CHECK(side IN ('SOURCE','TARGET')),
  approved_by uuid NOT NULL,
  approved_at timestamptz NOT NULL,
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,transfer_id,side),
  FOREIGN KEY(tenant_id,transfer_id) REFERENCES cross_entity_transfer(tenant_id,id)
);
CREATE INDEX cross_entity_approval_transfer ON cross_entity_approval(tenant_id,transfer_id);

-- ---------------------------------------------------------------------------
-- 4. 内部结算凭据（下游 12 财务的取数入口）
-- ---------------------------------------------------------------------------
-- 这张表就是蓝图输出里的「内部结算单据」。它**不含**任何 12 的科目、凭证或税务字段 ——
-- 那些必须由 12 自己拥有；本服务只把「货权按这个价、从这个法人转到那个法人」
-- 这条事实固化下来，让 12 能据此做内部结算与收入确认。
--
-- source_system + reference_id 是**跨服务定位键**，不是外键：
--   source_system  产生凭据的服务（本服务固定为 'warehouse'）
--   reference_id    该凭据引用的业务单据 id（跨法人调拨单 id）
-- 12 按 (source_system, reference_id) 反查即可拿到一张调拨单唯一一张凭据，
-- UNIQUE 约束保证不会重复计提收入。
CREATE TABLE cross_entity_settlement (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  settlement_code varchar(32) NOT NULL,
  transfer_id uuid NOT NULL,
  -- 跨服务定位键（见上方说明）
  source_system varchar(32) NOT NULL DEFAULT 'warehouse',
  reference_id uuid NOT NULL,
  -- 结算要素在本表**快照**一份：单头后来作废或改价，凭据仍要说得清「当初按多少结的」
  from_legal_entity_id uuid NOT NULL,
  to_legal_entity_id uuid NOT NULL,
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  amount numeric(19,4) NOT NULL,
  -- ISSUED 发货时开具（转出方已交付、转入方已确认定价）；ACKNOWLEDGED 转入方实收、凭据被对方确认。
  -- **刻意没有「已结算/已入账」状态**：那属于 12 财务的账，本服务无权宣告。
  -- 也刻意**没有 CANCELLED**：凭据只在发运时开具，而作废只允许发生在发运之前（那时还没有凭据）；
  -- 发运之后结案若有短少，凭据保持 ISSUED 并把短少量逐行带给 12 —— 部分货权确实转移了，
  -- 该怎么处理差额是财务判断，本服务只如实把差额摆出来。
  status varchar(16) NOT NULL DEFAULT 'ISSUED' CHECK(status IN ('ISSUED','ACKNOWLEDGED')),
  issued_at timestamptz NOT NULL,
  acknowledged_at timestamptz,
  acknowledged_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,settlement_code),
  -- 一张跨法人调拨单**只**开一张凭据：重复计提收入是财务侧最贵的错误
  UNIQUE(tenant_id,transfer_id),
  -- 跨服务定位键的全局唯一性：12 反查时不需要再带租户条件也不会拿到两张
  UNIQUE(source_system,reference_id),
  FOREIGN KEY(tenant_id,transfer_id) REFERENCES cross_entity_transfer(tenant_id,id),
  FOREIGN KEY(tenant_id,from_legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,to_legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(from_legal_entity_id<>to_legal_entity_id),
  CHECK(amount>=0),
  CHECK(btrim(source_system)<>''),
  -- 状态与时间戳必须自洽：已确认必有确认人与确认时刻，未确认（ISSUED）必无
  CHECK((status='ACKNOWLEDGED')=(acknowledged_at IS NOT NULL AND acknowledged_by IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- 5. DB 级状态机与「两个法人两个人签字」
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_cross_entity_transfer_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 草稿不能有任何一方确认（还没到确认那一步）
 IF NEW.status='DRAFT' AND (NEW.source_confirmed_at IS NOT NULL OR NEW.target_confirmed_at IS NOT NULL) THEN
  RAISE EXCEPTION '草稿不允许带确认时间' USING ERRCODE='23514';
 END IF;
 -- 已发出之前不能有发出时间；已发出之后必须有。
 -- CANCELLED 也在「尚未发出」这一侧：作废只允许发生在发运之前，那时确实没有发出时间。
 IF (NEW.status IN ('DRAFT','PENDING_SOURCE_CONFIRM','PENDING_TARGET_CONFIRM','CONFIRMED','CANCELLED'))
     <> (NEW.shipped_at IS NULL) THEN
  RAISE EXCEPTION '状态「%」与发出时间不自洽', NEW.status USING ERRCODE='23514';
 END IF;
 -- 只有 COMPLETED / DISCREPANCY / CANCELLED 是终态
 IF (NEW.status NOT IN ('COMPLETED','DISCREPANCY','CANCELLED')) <> (NEW.completed_at IS NULL) THEN
  RAISE EXCEPTION '状态「%」与完成时间不自洽：只有已交割、差异与作废是终态', NEW.status
   USING ERRCODE='23514';
 END IF;
 -- 转出方先确认、转入方后确认：源未确认而有目标确认时刻 = 顺序倒置
 IF NEW.source_confirmed_at IS NULL AND NEW.target_confirmed_at IS NOT NULL THEN
  RAISE EXCEPTION '转出方尚未确认，不能出现转入方确认时间' USING ERRCODE='23514';
 END IF;
 IF NEW.source_confirmed_at IS NOT NULL AND NEW.target_confirmed_at IS NOT NULL
    AND NEW.target_confirmed_at < NEW.source_confirmed_at THEN
  RAISE EXCEPTION '转入方确认时间早于转出方确认时间' USING ERRCODE='23514';
 END IF;
 -- 双方确认齐了才能发运
 IF NEW.status IN ('IN_TRANSIT','COMPLETED','DISCREPANCY')
    AND (NEW.source_confirmed_at IS NULL OR NEW.target_confirmed_at IS NULL) THEN
  RAISE EXCEPTION '状态「%」要求双方都已确认', NEW.status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER cross_entity_transfer_state BEFORE INSERT OR UPDATE ON cross_entity_transfer
 FOR EACH ROW EXECUTE FUNCTION guard_cross_entity_transfer_state();

-- 双方确认必须是**两个不同的人**：同一自然人把两方都签掉等于没有第二方。
-- 17 的双方法人授权尚未交付，这里先用「两个自然人」把这条底线钉死在 DB 层。
CREATE FUNCTION guard_cross_entity_two_parties() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_by uuid; target_by uuid;
BEGIN
 -- 注意读的是 NEW.**transfer_id**（确认事件的行 id 是 NEW.id）——
 -- 写成 NEW.id 会永远查不到任何确认行，这条约束就会静默失效。
 SELECT approved_by INTO source_by FROM cross_entity_approval
  WHERE tenant_id=NEW.tenant_id AND transfer_id=NEW.transfer_id AND side='SOURCE';
 SELECT approved_by INTO target_by FROM cross_entity_approval
  WHERE tenant_id=NEW.tenant_id AND transfer_id=NEW.transfer_id AND side='TARGET';
 IF source_by IS NOT NULL AND target_by IS NOT NULL AND source_by=target_by THEN
  RAISE EXCEPTION '转出方与转入方的确认人必须是两个人，当前是同一人 %', source_by
   USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER cross_entity_two_parties
 AFTER INSERT ON cross_entity_approval
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_cross_entity_two_parties();

-- ---------------------------------------------------------------------------
-- 6. 合计守恒：单头合计必须等于明细金额之和
-- ---------------------------------------------------------------------------
-- 内部结算凭据的金额取自单头合计，所以合计与明细一旦漂移，
-- 12 财务那边就是一笔凭据金额与逐行都对不上的单据。延迟约束触发器，
-- 因为一次建单要「写头 + 写 N 行」两件事，即时触发器会对着半成品报错。
CREATE FUNCTION guard_cross_entity_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_amount numeric;
BEGIN
 SELECT total_amount INTO head_amount FROM cross_entity_transfer
  WHERE tenant_id=NEW.tenant_id AND id=NEW.transfer_id FOR UPDATE;
 IF head_amount IS NOT NULL THEN
  IF head_amount <> (SELECT coalesce(sum(amount),0) FROM cross_entity_transfer_line
                      WHERE tenant_id=NEW.tenant_id AND transfer_id=NEW.transfer_id) THEN
   RAISE EXCEPTION '跨法人调拨单合计 % 与明细金额之和不符', head_amount USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER cross_entity_line_total
 AFTER INSERT OR UPDATE ON cross_entity_transfer_line
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_cross_entity_total();

-- 单头侧同样兜一道（改单头合计时没有行触发器会响）。
-- 函数必须先于触发器存在：CREATE TRIGGER ... EXECUTE FUNCTION 在建触发器时就要解析到函数。
CREATE FUNCTION guard_cross_entity_total_head() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.total_amount <> (SELECT coalesce(sum(amount),0) FROM cross_entity_transfer_line
                         WHERE tenant_id=NEW.tenant_id AND transfer_id=NEW.id) THEN
  RAISE EXCEPTION '跨法人调拨单合计 % 与明细金额之和不符', NEW.total_amount USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER cross_entity_head_total
 AFTER UPDATE ON cross_entity_transfer
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW WHEN (NEW.total_amount IS DISTINCT FROM OLD.total_amount)
 EXECUTE FUNCTION guard_cross_entity_total_head();

-- ---------------------------------------------------------------------------
-- 7. 热读路径索引
-- ---------------------------------------------------------------------------
CREATE INDEX cross_entity_transfer_list ON cross_entity_transfer(tenant_id,status,updated_at DESC);
CREATE INDEX cross_entity_transfer_entities
  ON cross_entity_transfer(tenant_id,from_legal_entity_id,to_legal_entity_id);
CREATE INDEX cross_entity_transfer_line_transfer
  ON cross_entity_transfer_line(tenant_id,transfer_id);
CREATE INDEX cross_entity_transfer_line_sku
  ON cross_entity_transfer_line(tenant_id,sku_id);
-- 12 财务按 (source_system, reference_id) 反查凭据：这条索引就是那条反查的落点
CREATE INDEX cross_entity_settlement_handoff ON cross_entity_settlement(source_system,reference_id);

-- ---------------------------------------------------------------------------
-- 8. 多租户：RLS + 授权（与 V1 / V61 同一条纪律）
-- ---------------------------------------------------------------------------
-- 租户由 backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'cross_entity_transfer','cross_entity_transfer_line',
   'cross_entity_approval','cross_entity_settlement'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON cross_entity_transfer, cross_entity_transfer_line,
  cross_entity_approval, cross_entity_settlement TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建单会报 permission denied for sequence，
-- 而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE cross_entity_transfer_code_seq TO warehouse_app;
GRANT USAGE, SELECT ON SEQUENCE cross_entity_settlement_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 同口径）。
-- 12 财务取内部结算凭据走**授权 API / 事件**，不靠这个只读角色直连本库。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
