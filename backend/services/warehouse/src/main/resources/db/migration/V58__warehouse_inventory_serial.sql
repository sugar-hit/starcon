-- ===========================================================================
-- 7.3.2.1 序列号：序列号档案、位置流转历史与出入库操作头
-- ===========================================================================
-- 蓝图原文：「序列号级库存管理：录入/扫码、状态（在库/已售/在途/维修/报废）、
-- 当前位置、绑定 SKU 与批次，出入库时强校验数量与序列号条数一致」
-- 「输出：序列号档案 + 位置流转历史 + 校验错误」
-- 「**序列号校验是一致性硬约束**：数量为 1 时必须有且仅有 1 个序列号，不允许『大概齐』」
--
-- ---------------------------------------------------------------------------
-- 为什么建三张表（而不是一张序列号表 + 一个 JSON 历史列）
-- ---------------------------------------------------------------------------
--   inventory_serial          序列号档案：**现在**这一件是什么状态、在哪个库位、绑哪个批次
--   inventory_serial_movement 位置流转历史：append-only 的事实，每一行都是一次真实的挪动/换态
--   inventory_serial_operation 出入库操作头：一次业务操作动了多少件、声明的数量是多少
--
-- 档案是**可变的投影**（货会挪、状态会变），历史是**不可变的事实**。两者混在一张表里，
-- 就得用「改一行」来表达一次挪动 —— 那等于把历史改没了，事后无法回答「这号上个月在哪个库位」。
-- 这条纪律与 V1 台账、V54 核对结论完全一致：流水/结论只追加，当前值另存一份投影。
--
-- 操作头单独立一张，是因为「数量必须等于序列号条数」这条硬约束需要**两个数字可比**：
-- 一个来自业务声明（数量），一个来自实际写入（条数）。没有承载它们的行，
-- 这条约束就只能是应用层的一段 if，绕过服务直接写 SQL 就没了 —— 而蓝图说的正是
-- 「不允许大概齐」，那就不该是一句可以被绕过的约定。
--
-- ---------------------------------------------------------------------------
-- 「每件只能处于一个有效位置」怎么由结构保证
-- ---------------------------------------------------------------------------
-- 不是靠约定，是靠 inventory_serial 一行只描述一件货：它有且只有一个 current_location_id。
-- 位置流转写进 history，不改历史。于是「一件货同时在两个库位」在物理上无处表达 ——
-- 想表达它就得插两行档案，而 UNIQUE(tenant_id,serial_number) 立刻挡住第二行。
--
-- 状态与位置的一致性写进 CHECK（不是靠应用层记得配对）：
--   在库 / 维修 → 必须有当前位置（货在本仓的某个库位上）
--   已售 / 在途 / 报废 → 必须没有当前位置（货不在任何库位上）
-- 「已售的货还挂在库位上」和「在库的货没有库位」都是真实的现场事故，
-- 它们应该在 INSERT 的那一刻就失败，而不是等月底盘点才发现。
-- 维修之所以**要**位置：维修在本仓的维修/冻结库位进行，那件货确实还在架上；
-- 只有送修外发才允许走「在途」那一条路径。
--
-- ---------------------------------------------------------------------------
-- 数量口径
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,6) + Java BigDecimal，全程禁浮点（与 V1/V2 同口径）。
--   declared_quantity 用 19,6 是为了与全系统数量列同型（比对时不会因 scale 不同被判成不等）；
--   但序列号是**离散**的，所以本迁移额外加两条 CHECK：数量必须是非负整数、
--   serial_count 必须与它**完全相等**。非整数的序列号数量在物理上就不存在。
--
-- ---------------------------------------------------------------------------
-- 与 7.3.1.1 批次管理的关系
-- ---------------------------------------------------------------------------
-- 批次档案 inventory_lot 由 V2 建立，本迁移**只读引用**它（复合外键 tenant_id+lot_id）。
-- 兄弟模块 7.3.1.1 在另一条车道上开发 V57，本迁移不依赖它的任何新增对象。
-- lot_id 可空：非批次管理品没有批次，强行要求必填会让一半的 SKU 录不进来。
--
-- sku_id 是 catalog 的逻辑 ID，不建外键（sku 属 catalog，跨服务 FK 违反模块边界纪律，同 V1/V2）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 序列号档案
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_serial (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 序列号本身。同一租户内唯一 —— 这是整个模块的地基：同一个号出现两次，
  -- 「每件只在一个位置」就无从谈起，两个库位会同时声称自己拥有这号。
  -- 大小写敏感：序列号是设备铭牌上的字面量，不做 upper() 归一。
  serial_number varchar(128) NOT NULL,

  -- 绑定 SKU：序列号与 SKU 的绑定是**永久**的（同一台设备换不成别的型号），
  -- 因此 inventory_serial_identity 触发器禁止改这一列。
  sku_id uuid NOT NULL,
  -- 绑定批次（可空：非批次管理品没有批次）。复合外键而不是裸 id 外键 ——
  -- 裸 id 外键挡不住「A 租户的 lot_id 指向 B 租户的批次」。
  lot_id uuid,

  -- 状态。在库/已售/在途/维修/报废。
  status varchar(16) NOT NULL DEFAULT 'IN_STOCK'
    CHECK(status IN ('IN_STOCK','SOLD','IN_TRANSIT','REPAIR','SCRAPPED')),
  -- 当前位置。与状态的一致性由下面的 CHECK 保证，不靠应用层记得配对。
  current_location_id uuid,

  note text NOT NULL DEFAULT '',
  -- 业务时间（最近一次位置/状态变动的发生时间，由写入方提供）；
  -- created_at/updated_at 是系统时间（DB 维护）。两者分开存，不混用（与 V2 同口径）。
  last_movement_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,serial_number),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  FOREIGN KEY(tenant_id,current_location_id) REFERENCES warehouse_location(tenant_id,id),
  CHECK(btrim(serial_number)<>''),

  -- 状态与位置必须配套：在库/维修有位置，已售/在途/报废无位置。
  CHECK(
    (status IN ('IN_STOCK','REPAIR') AND current_location_id IS NOT NULL)
    OR
    (status IN ('SOLD','IN_TRANSIT','SCRAPPED') AND current_location_id IS NULL)
  )
);

-- 列表默认按最近变动排：序列号工作台最常问的是「最近动了哪些号」。
CREATE INDEX inventory_serial_recent
  ON inventory_serial(tenant_id, updated_at DESC, id);
-- 按 SKU + 状态筛（「这个型号还有几台在库」是序列号页最高频的一个问题）。
CREATE INDEX inventory_serial_sku
  ON inventory_serial(tenant_id, sku_id, status, serial_number);
-- 按库位反查「这个库位上有哪些号」，库位树属 08.1.2.x，本迁移只做这一层反查。
CREATE INDEX inventory_serial_location
  ON inventory_serial(tenant_id, current_location_id, serial_number)
  WHERE current_location_id IS NOT NULL;
-- 按批次反查（16 合规要按批次追溯电子产品）。
CREATE INDEX inventory_serial_lot
  ON inventory_serial(tenant_id, lot_id, status) WHERE lot_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. 出入库操作头
-- ---------------------------------------------------------------------------
-- 一次业务操作（收这批货 / 发这批货 / 在仓内挪位 / 改状态 / 改批次绑）动到的一组序列号。
-- 存在的理由只有一个：让「数量」与「序列号条数」这两个数字能被同时记下来并被数据库比对。
CREATE TABLE inventory_serial_operation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 方向。INBOUND 入库 / OUTBOUND 出库 / RELOCATION 库位转移 / STATUS_CHANGE 状态流转 /
  -- REBIND 批次改绑。REBIND 也占一个方向：改批号是一次真实的档案变更，
  -- 混进 STATUS_CHANGE 会让「状态没变却多了一条状态流转」这种记录出现。
  direction varchar(16) NOT NULL
    CHECK(direction IN ('INBOUND','OUTBOUND','RELOCATION','STATUS_CHANGE','REBIND')),

  sku_id uuid NOT NULL,
  lot_id uuid,
  warehouse_id uuid,
  location_id uuid,

  -- 业务单据。ref_type/ref_id 指回 6 采购、8 收货拣货或 11 售后的那张单，
  -- 使「这批货当时是为什么动的」可以被回查，而不是只剩一句备注。
  ref_type varchar(32) NOT NULL DEFAULT '',
  ref_id uuid,

  -- **这条硬约束就在这里**：声明的数量与实际序列号条数必须完全相等。
  -- 蓝图说「数量为 1 时必须有且仅有 1 个序列号，不允许『大概齐』」——
  -- 这条 CHECK 让「大概齐」在落库那一刻就不可能发生。
  declared_quantity numeric(19,6) NOT NULL,
  serial_count integer NOT NULL,

  actor_id uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  -- 业务时间（货真正动的那一刻），与 created_at（系统入库时刻）分开。
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_location(tenant_id,id),
  CHECK(declared_quantity >= 0),
  -- 序列号是离散件，数量不允许带小数。
  CHECK(declared_quantity = trunc(declared_quantity)),
  -- 条数与数量必须一致（上面这条 CHECK 的执行点）。
  CHECK(serial_count = declared_quantity),
  CHECK(serial_count >= 0)
);

-- 一次操作带着它的 SKU/方向，工作台要按「最近收了哪些型号」翻。
CREATE INDEX inventory_serial_operation_recent
  ON inventory_serial_operation(tenant_id, created_at DESC, id);
CREATE INDEX inventory_serial_operation_sku
  ON inventory_serial_operation(tenant_id, sku_id, direction, created_at DESC, id);
-- 按来源单据回查一次操作动了哪些号（对账/售后追单的主路径）。
CREATE INDEX inventory_serial_operation_ref
  ON inventory_serial_operation(tenant_id, ref_type, ref_id) WHERE ref_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. 位置流转历史（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_serial_movement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 一次操作可动多件，每件一行。它们共用 operation_id，
  -- 于是「这一单到底动了哪几个号」是一次 group by，而不是把号塞进一个数组列。
  serial_id uuid NOT NULL,
  operation_id uuid NOT NULL,

  movement_kind varchar(16) NOT NULL
    CHECK(movement_kind IN ('INBOUND','OUTBOUND','RELOCATION','STATUS_CHANGE','REBIND')),
  -- 序列号本身（冗余固化）：号改名/重录不影响历史可读性，
  -- 三个月后回查时不必先去档案表 join 才能知道当时动的是哪个号。
  serial_number varchar(128) NOT NULL,

  from_status varchar(16) NOT NULL
    CHECK(from_status IN ('IN_STOCK','SOLD','IN_TRANSIT','REPAIR','SCRAPPED')),
  to_status varchar(16) NOT NULL
    CHECK(to_status IN ('IN_STOCK','SOLD','IN_TRANSIT','REPAIR','SCRAPPED')),
  from_location_id uuid,
  to_location_id uuid,
  from_lot_id uuid,
  to_lot_id uuid,

  ref_type varchar(32) NOT NULL DEFAULT '',
  ref_id uuid,
  actor_id uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  -- 业务时间。与 operation 上的 occurred_at 一致，逐行各存一份是为了按号回查历史时
  -- 不必 join —— 「这个号的时间线」是最常见的读法。
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,serial_id) REFERENCES inventory_serial(tenant_id,id),
  FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_serial_operation(tenant_id,id),
  FOREIGN KEY(tenant_id,from_location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,to_location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,from_lot_id) REFERENCES inventory_lot(tenant_id,id),
  FOREIGN KEY(tenant_id,to_lot_id) REFERENCES inventory_lot(tenant_id,id),

  -- 一次挪动要么换位置，要么换状态，要么改批次，不能三样都没变 ——
  -- 否则「什么都没发生」也会留下一行历史，历史里就会掺进噪音。
  CHECK(
    (from_status        IS DISTINCT FROM to_status)
    OR (from_location_id IS DISTINCT FROM to_location_id)
    OR (from_lot_id      IS DISTINCT FROM to_lot_id)
  ),
  -- INBOUND/OUTBOUND/RELOCATION 必须真的换了库位，否则位置流转历史名不副实。
  CHECK(movement_kind NOT IN ('INBOUND','OUTBOUND','RELOCATION')
        OR from_location_id IS DISTINCT FROM to_location_id),
  -- 状态流转必须真的换了状态。
  CHECK(movement_kind <> 'STATUS_CHANGE' OR from_status IS DISTINCT FROM to_status)
);

-- 读「这个号的时间线」：按号倒序翻。
CREATE INDEX inventory_serial_movement_serial
  ON inventory_serial_movement(tenant_id, serial_id, occurred_at DESC, id);
-- 全局最近流转（序列号工作台首页）。
CREATE INDEX inventory_serial_movement_recent
  ON inventory_serial_movement(tenant_id, occurred_at DESC, id);
-- 一次操作的全部流转行。
CREATE INDEX inventory_serial_movement_operation
  ON inventory_serial_movement(tenant_id, operation_id, id);
-- 按来源单据回查。
CREATE INDEX inventory_serial_movement_ref
  ON inventory_serial_movement(tenant_id, ref_type, ref_id) WHERE ref_id IS NOT NULL;

-- ===========================================================================
-- 4. 数量与序列号条数一致：把声明兑现成事实的那道闸
-- ===========================================================================
-- operation.serial_count 是**声明**，movement 的行数是**事实**。CHECK 只能保证声明自洽
-- （数量 == 声明的条数），保证不了「实际真的写了这么多行」—— 少写几行它照样通过。
-- 这道延迟约束触发器在 COMMIT 时数一遍真正落库的行，对不上就让整笔事务失败：
-- 于是「数量对得上、实际少写了两件」这种半成品操作在数据库层就不可能提交成功。
--
-- 必须 DEFERRABLE INITIALLY DEFERRED：写入顺序是「先操作头、后 N 行流转」，
-- 若立即检查，操作头 INSERT 的那一刻 movement 还没写，必然误判。
CREATE FUNCTION guard_inventory_serial_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  actual integer;
BEGIN
  SELECT count(*) INTO actual
    FROM inventory_serial_movement m
   WHERE m.tenant_id = NEW.tenant_id AND m.operation_id = NEW.id;
  IF actual <> NEW.serial_count THEN
    RAISE EXCEPTION
      '序列号操作 % 声明 % 条序列号，实际写入 % 条：出入库数量必须与序列号条数完全一致',
      NEW.id, NEW.serial_count, actual USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER inventory_serial_operation_count
  AFTER INSERT ON inventory_serial_operation
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_serial_operation();

-- ===========================================================================
-- 5. 不可变的两处
-- ===========================================================================
-- 序列号档案不是 append-only（货会挪、状态会变），但有两条**永久**不变式：
--   一、序列号与 SKU 的绑定不可改 —— 同一台设备换不成别的型号，改了就是货账两岔。
--   二、报废是终态 —— 报废的号不能复活成在库/已售，否则「报废」就不是一个结论
--      而是一个可以撤销的建议；真要恢复只能新建档案并留下 REBIND/入库记录。
-- 这两条写进触发器而不是只写在 Java 里：绕过服务的直接 SQL 同样不得违反。
CREATE FUNCTION guard_inventory_serial_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.sku_id IS DISTINCT FROM OLD.sku_id THEN
    RAISE EXCEPTION '序列号 % 已绑定 SKU %，不允许改绑到 %：序列号与 SKU 的绑定不可变',
      OLD.serial_number, OLD.sku_id, NEW.sku_id USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.status = 'SCRAPPED' AND NEW.status <> 'SCRAPPED' THEN
    RAISE EXCEPTION '序列号 % 已报废，报废是终态，不允许变回 %：要恢复请新建档案',
      OLD.serial_number, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER inventory_serial_identity
  BEFORE UPDATE ON inventory_serial
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_serial_identity();

-- 「这个号有没有被业务引用过」的**唯一**判据。
--
-- 注意不是「流转条数 <= 1」：录错后又被挪过一次位的号同样只有一条流转，
-- 而它已经被引用了。所以判据是「最多一条，且那一条必须是建档时那次纯入库
-- （from_status = to_status）」—— 挪过位、换过态、改过批号的号一律不算。
CREATE FUNCTION inventory_serial_unreferenced(p_tenant uuid, p_serial uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT
    (SELECT count(*) FROM inventory_serial_movement m
      WHERE m.tenant_id = p_tenant AND m.serial_id = p_serial) <= 1
    AND NOT EXISTS (
      SELECT 1 FROM inventory_serial_movement m
       WHERE m.tenant_id = p_tenant AND m.serial_id = p_serial
         AND NOT (m.movement_kind = 'INBOUND' AND m.from_status = m.to_status));
$$;

-- 档案的删除是「录错了」而不是「没发生过」：已经产生过流转的号被任何单据引用过，
-- 删掉它等于让那张单据凭空少了一件货 —— 那种情况只能走报废。
-- 因此只有**仅有建档那一次入库**、且仍在库的号可以删（这是扫码录错的标准修法）。
CREATE FUNCTION guard_inventory_serial_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  movements integer;
BEGIN
  SELECT count(*) INTO movements
    FROM inventory_serial_movement m
   WHERE m.tenant_id = OLD.tenant_id AND m.serial_id = OLD.id;
  IF OLD.status <> 'IN_STOCK' THEN
    RAISE EXCEPTION '序列号 % 当前状态为 %，只有仍在库的档案可以删除，其余一律走报废',
      OLD.serial_number, OLD.status USING ERRCODE = 'check_violation';
  END IF;
  IF NOT inventory_serial_unreferenced(OLD.tenant_id, OLD.id) THEN
    RAISE EXCEPTION '序列号 % 已产生 % 条流转记录，已被业务引用，不可删除；请改用报废',
      OLD.serial_number, movements USING ERRCODE = 'check_violation';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER inventory_serial_delete_guard
  BEFORE DELETE ON inventory_serial
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_serial_delete();

-- 操作头与流转历史是审计凭据：写完不许改。删是另一回事，见下面 discard_unused_serial ——
-- 「删掉一条从未被引用的建档记录」是扫码录错的标准修法，而「删掉一条被引用过的流转」是毁账。
-- 权限层把 DELETE 整个收走（REVOKE 下面还有），只留一条带守卫的窄路径。
CREATE TRIGGER inventory_serial_operation_append_only
  BEFORE UPDATE ON inventory_serial_operation
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();
CREATE TRIGGER inventory_serial_movement_append_only
  BEFORE UPDATE ON inventory_serial_movement
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();

-- ---------------------------------------------------------------------------
-- 作废一次录错的录入
-- ---------------------------------------------------------------------------
-- 「录错了」和「没发生过」是两件事，都表现为「这个号不该存在」。区别在于**有没有被引用过**：
-- 有流转历史 = 已被某张单据引用，删掉它等于让那张单据凭空少一件货，只能走报废；
-- 没有流转历史（只有建档那一次）= 从来没人引用过，删掉不伤任何账。
--
-- 走 SECURITY DEFINER 函数而不是应用里直接删，是因为流转表是 append-only：
-- 应用角色连 DELETE 权限都没有（本迁移末尾 REVOKE），只有这个函数能删，
-- 而它把守卫条件**重新校验一遍** —— 数据库里唯一被允许删除序列号历史的入口。
-- 纯文本 SQL 里 tenant_id 不作为参数传：租户从表里取，伪造的租户结构上就传不进来。
CREATE FUNCTION discard_unused_serial(p_id uuid) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  v_tenant uuid;
  v_status varchar(16);
  v_moves integer;
  v_op uuid;
BEGIN
  SELECT tenant_id, status INTO v_tenant, v_status
    FROM inventory_serial WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION '序列号不存在：%', p_id USING ERRCODE = 'no_data_found';
  END IF;
  IF v_status <> 'IN_STOCK' THEN
    RAISE EXCEPTION '序列号 % 当前状态为 %，只有仍在库的档案可以作废，其余一律走报废',
      p_id, v_status USING ERRCODE = 'check_violation';
  END IF;
  SELECT count(*) INTO v_moves
    FROM inventory_serial_movement WHERE tenant_id = v_tenant AND serial_id = p_id;
  IF NOT inventory_serial_unreferenced(v_tenant, p_id) THEN
    RAISE EXCEPTION '序列号 % 已产生 % 条流转记录，已被业务引用，不可作废；请改用报废',
      p_id, v_moves USING ERRCODE = 'check_violation';
  END IF;

  -- 逐个操作头处理：只删「这个号在库时那一次入库」的操作头，
  -- 而且只在它名下再无其它流转时才删 —— 一批导 20 台时不能把整批的凭据一起带走。
  FOR v_op IN
    SELECT DISTINCT operation_id FROM inventory_serial_movement
     WHERE tenant_id = v_tenant AND serial_id = p_id
  LOOP
    DELETE FROM inventory_serial_movement
     WHERE tenant_id = v_tenant AND serial_id = p_id AND operation_id = v_op;
    IF NOT EXISTS (
      SELECT 1 FROM inventory_serial_movement
       WHERE tenant_id = v_tenant AND operation_id = v_op
    ) THEN
      DELETE FROM inventory_serial_operation WHERE tenant_id = v_tenant AND id = v_op;
    END IF;
  END LOOP;

  -- guard_inventory_serial_delete 会再校验一次同样的条件：两条守卫不是重复，
  -- 而是「谁能走到这一步」与「走到这一步时数据还是不是这样」各管一段。
  DELETE FROM inventory_serial WHERE tenant_id = v_tenant AND id = p_id;
  RETURN true;
END $$;

-- ===========================================================================
-- 6. 多租户与授权
-- ===========================================================================
-- 与 V1/V2/V54 同一套写法。序列号档案尤其要 FORCE：它同时绑着 SKU 与批次，
-- 漏掉 tenant_id 或漏掉 RLS 就等于把 A 租户的整机序列号清单泄露给 B 租户 ——
-- 序列号是设备身份，泄露它比泄露一个 SKU 编码严重得多。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它（集成测试实跑三断言证明）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'inventory_serial','inventory_serial_operation','inventory_serial_movement'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE
  ON inventory_serial, inventory_serial_operation, inventory_serial_movement TO warehouse_app;
-- 操作头与流转历史不可改删（档案本身要能改：货会挪、状态会变）。
-- DELETE 被收走之后，应用角色删不掉任何一条流转历史 ——
-- 「作废一次录错」只能走 discard_unused_serial，那条路径自己会校验守卫条件。
REVOKE UPDATE,DELETE ON inventory_serial_operation, inventory_serial_movement FROM warehouse_app;
-- 作废入口只给 warehouse_app；starcon_app 是跨服务只读角色，连作废都不该有。
GRANT EXECUTE ON FUNCTION discard_unused_serial(uuid) TO warehouse_app;
REVOKE ALL ON FUNCTION discard_unused_serial(uuid) FROM starcon_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2 同口径）。
REVOKE ALL ON inventory_serial, inventory_serial_operation, inventory_serial_movement FROM starcon_app;
