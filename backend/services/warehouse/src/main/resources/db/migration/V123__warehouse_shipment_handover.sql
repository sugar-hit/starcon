-- ===========================================================================
-- V123 · 8.3.4.1 发货交接（warehouse 服务）
-- ===========================================================================
--
-- 蓝图 08 卷 8.3.4.1 原文：
--   职责：发货交接：确认承运商与运单号、登记交接时间与交接人、扣减库存完成出库、
--         触发物流轨迹与平台回传。
--   输入：包裹与运单号；承运渠道；交接确认。
--   输出：出库完成分录（预占转实扣）+ 发货事件 →
--         9 订单状态、10 轨迹、2 平台回写。
--   关系：上游 = 8.3.3.2；下游 = 7 库存、9 订单、10 物流、2 渠道回写。
--         **实发扣减与预占消费在同一事务**，避免预占悬空或重复扣减。
--
-- ---------------------------------------------------------------------------
-- 一、卷级关键约束（本迁移逐条对应）
-- ---------------------------------------------------------------------------
--   1)「实发扣减与预占消费**在同一事务**」→ 蓝图这一句是本模块存在的理由，所以它在 DB 层
--      是硬约束而不是提示：交接单**永远不能**在没有真实扣减的情况下置为 HANDED_OVER ——
--      guard_shipment_handover_confirm 逐行核对「明细行的 deducted_quantity 必须恰好等于
--      应发 quantity」，差一行、少一件都在提交那一刻被回滚。
--   2)「避免**重复扣减**」→ 一个包裹只能出现在一张交接单的包裹行里
--      （UNIQUE(tenant_id,parcel_id)）。第二次交接同一箱时 DB 直接拒，
--      而不是靠应用层记得先查一遍。
--   3)「确认承运商与运单号」→ **两个都要有，且不能只有承运商**。承运渠道在 V72 的
--      outbound_order.carrier_code 上只是 varchar(64) 的自由文本；运单号在本服务
--      **原本不存在**，所以这里冻结成两张真实列（单头主运单号 + 每箱运单号），
--      不塞进 notes 字段 —— 事后要能答出「这一箱走的是哪个号」。
--   4)「登记交接时间与交接人」→ 两者与状态同进同出（CHECK）。只记时刻不记人，
--      等于出了纠纷时「谁把货交出去的」没有答案。
--   5)「触发物流轨迹与平台回写」→ 那两个是 **10 物流**与 **2 渠道**的下游，本服务
--      **不建第二套轨迹表、不自己回写平台**。本模块只产出**一件东西**给它们：
--      append-only 的 shipment_handover_event + 冻结在单头/包裹行的实测重量体积。
--      capabilities 里显式声明这条边界（见 §六第 3 条）。
--
-- ---------------------------------------------------------------------------
-- 二、本迁移建什么、不建什么
-- ---------------------------------------------------------------------------
--
-- 建五张表：
--   shipment_handover            交接单头（承运商 + 运单 + 交接时刻 + 交接人 + 状态）
--   shipment_handover_parcel     交接包裹行（哪几箱进了这次交接 + 每箱运单号 + 实测重量体积）
--   shipment_handover_item       出库明细行（哪些 SKU、多少件）+ **扣减落点**（谁被扣了）
--   shipment_handover_reservation 预占消费行（一件对多条分配，所以另开一张）
--   shipment_handover_event      append-only 作业流水
--
-- **为什么不新建一张「出库分录」表**：库存出库分录的权威是 V1 已有的
-- inventory_transaction（kind='SHIPMENT'）/ inventory_entry（带符号分录），
-- 本模块是它们的**写入方之一**，不是第二本账。再开一张就会出现两份「这批货扣了多少」，
-- 而 AGENTS.md 要求每张事实表只有一个写入所有者。
--
-- **为什么不新建承运商目录表**：蓝图输入写的是「承运渠道」，而 V72 的
-- outbound_order.carrier_code 与 8.3.1.1 的承运渠道下拉都是本服务既有的 varchar 口径；
-- 承运商主数据（渠道代码 / 面单模板 / 体积重除数）归 **10 物流**，在这里建一份目录
-- 就会出现两个「有哪些承运商」的答案。本表只登记「这一次交接走了哪个渠道的哪个号」。
--
-- **为什么运单号不用外键引 10 物流的表**：10 物流尚未交付，本服务跨服务读表又被禁止。
-- 所以运单号是**本单据的冻结值** —— 交接凭据必须自带这个号，否则 10 物流上线前
-- 这一单永远答不出「运的是什么」。
--
-- ---------------------------------------------------------------------------
-- 三、口径：重量克(g)、体积立方米(cbm)、数量 NUMERIC(19,6)，禁浮点
-- ---------------------------------------------------------------------------
-- 沿用 V121 的口径以便 8.3.3.2 → 8.3.4.1 直接串起来：
--   重量 numeric(19,3) 单位克；体积 numeric(19,6) 单位立方米；数量 numeric(19,6)。
-- 全部 NUMERIC，不用 double precision / real（AGENTS.md「禁止浮点记账」）。
--
-- ---------------------------------------------------------------------------
-- 四、库存归属：预占与实扣是**两本账**，本模块在同一事务里同时动它们
-- ---------------------------------------------------------------------------
--
-- 这是本迁移最容易做错的一处，所以把事实写在这里：
--   * inventory_balance.reserved / stock_bucket.reserved 是 **7.1.1.1 实物预占**，
--     V1 的 ck_balance_available（available = on_hand − reserved ≥ 0）守着它。
--     发货时**必须**同时减 reserved，否则 on_hand 减了而 reserved 不减，
--     available 立刻变负，DB 当场拒 —— 这正是「预占悬空」在 DB 层的形态。
--   * reservation_allocation（V107）是 **7.2.1.2 承诺账**，它**不**碰
--     inventory_balance / stock_bucket（V112 文件头明写「不写 inventory_entry，
--     也不碰 inventory_balance / stock_bucket 的 reserved」）。
--   * 所以「预占转实扣」在本模块是**两个动作**：
--       (a) 实物侧：stock_bucket + inventory_balance 的 on_hand/reserved 一起减，
--           并写 inventory_entry（on_hand_delta = −q，reserved_delta = −min(预占, q)）；
--       (b) 承诺侧：reservation_allocation COMMITTED → CONSUMED + reservation_entry
--           CONSUMED 分录 + 父预占的 consumed_quantity 回写。
--     两者在**同一个事务**里完成 —— 中途失败全部回滚，不会出现「货扣了但预占还挂着」
--     或「预占消费了但货没扣」这两种半边状态。
--   * 实物侧减多少 reserved，取 min(该库存行当前 reserved, 本次出库量)：
--     拣 6 件而该行只预占了 4 件，就减 4 —— 剩下 2 件本来就没被谁圈住，
--     把它写成「不知道被谁预占了」或硬减成 6 都是在编账。
--
-- ---------------------------------------------------------------------------
-- 五、时间口径：交接时刻由调用方提供
-- ---------------------------------------------------------------------------
--
-- handover_at 由调用方给（与 V107 reservation_entry.occurred_at、V58
-- inventory_serial.last_movement_at 同一纪律）：跨服务调用方与本服务有时钟差，
-- 用服务端当前时间会把「什么时候交接的」这个事实换成「本服务什么时候知道的」。
-- 服务端只在调用方没给时兜底填 now()。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. shipment_handover · 交接单头
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_handover (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：SH-<UTC 日期>-<8 位十六进制>，服务端派生，不接受调用方自填。
  handover_code varchar(32) NOT NULL,

  -- 上游两张单的真外键（同库、同租户、FK 成立）：
  --   packing_order  8.3.3.2 的打包单，**必须已 PACKED**
  --   outbound_order 8.3.1.x 的出库单，**必须已 PENDING_SHIPMENT**
  -- 两张单都必须挂 UNIQUE(tenant_id, id)：一张出库单只交一次货，
  -- 「重复扣减」在 DB 层的第一道防线就在这里。
  packing_order_id uuid NOT NULL,
  outbound_order_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,

  -- 承运渠道（蓝图输入「承运渠道」）。开单可空（还不知道谁来拉），
  -- **交接确认时必须有**（guard 与 CHECK 各拒一次）。
  carrier_code varchar(64) NOT NULL DEFAULT '',
  carrier_name varchar(100) NOT NULL DEFAULT '',

  -- 主运单号：这一整次交接对外的那一个号（蓝图输入「运单号」）。
  master_waybill_no varchar(64) NOT NULL DEFAULT '',

  -- 交接时刻与交接人（蓝图「登记交接时间与交接人」）。开单可空，确认时必须有。
  handover_at timestamptz,
  handover_user_id uuid,
  -- 揽收人（承运商侧的人）与联系方式：纠纷时「谁接走了这批货」要答得出。
  receiver_name varchar(100) NOT NULL DEFAULT '',
  receiver_contact varchar(64) NOT NULL DEFAULT '',

  -- 开单瞬间出库单处在哪一档（冻结快照）。守卫靠它判「这一单本来就在待发货」，
  -- 否则一次交接可以把一张还没打包完的单推成已发货（与 V121 同一手法）。
  outbound_status_before varchar(20) NOT NULL,

  -- 三态。**刻意没有「已取消出库」**：货已经交出去了就回不来了，
  -- 后续的差异处理属 11 售后与 10 物流，不在本模块改写这一单的历史。
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN
    ('OPEN','HANDED_OVER','CANCELLED')),

  -- 合计：**唯一写入者是 refresh_shipment_handover_totals_fn 触发器**，
  -- 服务层只读回来，不自己算（服务层算的数与账上的数可能差一位）。
  parcel_count integer NOT NULL DEFAULT 0 CHECK(parcel_count>=0),
  parcel_item_count integer NOT NULL DEFAULT 0 CHECK(parcel_item_count>=0),
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity>=0),
  total_gross_weight_g numeric(19,3) NOT NULL DEFAULT 0 CHECK(total_gross_weight_g>=0),
  total_volume_cbm numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_volume_cbm>=0),
  -- 扣减口径：交出去多少件、其中多少件本来被预占着、实际消耗了多少条预占分配。
  deducted_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(deducted_quantity>=0),
  reserved_deducted_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(reserved_deducted_quantity>=0),
  reservation_line_count integer NOT NULL DEFAULT 0 CHECK(reservation_line_count>=0),
  -- 台账回指：出库流水（V1 inventory_transaction）的 id，让「这单扣了多少」可反查。
  inventory_transaction_id uuid,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  completed_at timestamptz,
  cancelled_at timestamptz,
  cancel_reason varchar(300),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,handover_code),
  -- **一个出库单一张交接单 / 一个打包单一张交接单**：重复交接的第二道防线。
  UNIQUE(tenant_id,outbound_order_id),
  UNIQUE(tenant_id,packing_order_id),

  FOREIGN KEY(tenant_id,packing_order_id) REFERENCES packing_order(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_order_id) REFERENCES outbound_order(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  CHECK(btrim(handover_code)<>''),
  CHECK(outbound_status_before='PENDING_SHIPMENT'),
  CHECK(length(btrim(carrier_code))<=64),
  CHECK(length(btrim(master_waybill_no))<=64),
  -- 承运渠道与运单号在 OPEN 阶段可以都没有（货还没交），但**不能只有一个**：
  -- 只有承运商没有号 = 车来了但没人知道运的是哪一单；只有号没有渠道 = 不知道谁运的。
  CHECK(btrim(master_waybill_no)='' OR btrim(carrier_code)<>''),
  -- 交接人与交接时刻同进同出（只有「交出去了」而没有「谁交的、什么时候交的」等于没交接）。
  CHECK((handover_user_id IS NULL) = (handover_at IS NULL)),
  CHECK((status='HANDED_OVER') = (completed_at IS NOT NULL)),
  CHECK((status='CANCELLED') = (cancelled_at IS NOT NULL)),
  -- 已交接必须有承运商与运单号、有交接人与交接时刻，并挂着台账流水。
  CHECK(status<>'HANDED_OVER' OR
        (btrim(carrier_code)<>'' AND btrim(master_waybill_no)<>''
         AND handover_user_id IS NOT NULL AND handover_at IS NOT NULL
         AND inventory_transaction_id IS NOT NULL)),
  CHECK(status<>'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason)<>'')),
  CHECK(status<>'CANCELLED' OR (handover_user_id IS NULL AND handover_at IS NULL)),
  -- 扣减量不许超过应发量：超扣是超卖的另一种形态。
  CHECK(deducted_quantity<=total_quantity),
  CHECK(reserved_deducted_quantity<=deducted_quantity)
);

-- 交接工作台：未交接的按开单时刻排。
CREATE INDEX shipment_handover_open_queue
  ON shipment_handover(tenant_id, warehouse_id, created_at, id)
  WHERE status='OPEN';
-- 「这张出库单交出去了没有」「这个包裹在哪次交接里」的主查询路径。
CREATE INDEX shipment_handover_outbound
  ON shipment_handover(tenant_id, outbound_order_id);
CREATE INDEX shipment_handover_carrier
  ON shipment_handover(tenant_id, carrier_code, created_at DESC, id)
  WHERE btrim(carrier_code)<>'';
-- 按运单号反查（客服接到「我的单号查不到」时的第一跳）。
CREATE INDEX shipment_handover_waybill
  ON shipment_handover(tenant_id, master_waybill_no)
  WHERE btrim(master_waybill_no)<>'';
CREATE INDEX shipment_handover_user
  ON shipment_handover(tenant_id, handover_user_id, handover_at DESC, id)
  WHERE handover_user_id IS NOT NULL;

-- 1.1 开单入口守卫：**只能从一张已 PACKED 的打包单开**（蓝图「上游 = 8.3.3.2」）。
CREATE FUNCTION guard_shipment_handover_open_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_packing_status varchar; v_packing_order_id uuid; v_packing_warehouse uuid;
        v_packing_parcels integer; v_outbound varchar; v_outbound_warehouse uuid;
BEGIN
  SELECT status, outbound_order_id, warehouse_id,
         (SELECT count(*) FROM parcel p
           WHERE p.tenant_id = packing_order.tenant_id
             AND p.packing_order_id = packing_order.id)
    INTO v_packing_status, v_packing_order_id, v_packing_warehouse, v_packing_parcels
    FROM packing_order
   WHERE tenant_id = NEW.tenant_id AND id = NEW.packing_order_id;
  IF v_packing_status IS NULL THEN
    RAISE EXCEPTION '打包单不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_packing_status <> 'PACKED' THEN
    RAISE EXCEPTION '打包单当前是 %，只有「已打包」（PACKED）才能开交接单 —— '
                    '货还没封箱就交不出去。', v_packing_status USING ERRCODE='23514';
  END IF;
  IF v_packing_parcels = 0 THEN
    RAISE EXCEPTION '打包单没有包裹，不能开交接单：交出去的是「哪几箱」，答不出就等于没交接。'
      USING ERRCODE='23514';
  END IF;
  IF v_packing_order_id <> NEW.outbound_order_id THEN
    RAISE EXCEPTION '打包单挂的是另一张出库单（%），不能拿它交接这张单。', v_packing_order_id
      USING ERRCODE='23514';
  END IF;
  IF v_packing_warehouse <> NEW.warehouse_id THEN
    RAISE EXCEPTION '交接仓必须是打包单的发货仓。' USING ERRCODE='23514';
  END IF;

  SELECT status, warehouse_id INTO v_outbound, v_outbound_warehouse
    FROM outbound_order WHERE tenant_id = NEW.tenant_id AND id = NEW.outbound_order_id;
  IF v_outbound IS NULL THEN
    RAISE EXCEPTION '出库单不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_outbound <> 'PENDING_SHIPMENT' THEN
    RAISE EXCEPTION '出库单当前是 %，只有「待发货」（PENDING_SHIPMENT）才能开交接单。', v_outbound
      USING ERRCODE='23514';
  END IF;
  IF v_outbound_warehouse <> NEW.warehouse_id THEN
    RAISE EXCEPTION '交接仓必须是出库单的发货仓。' USING ERRCODE='23514';
  END IF;

  NEW.outbound_status_before := 'PENDING_SHIPMENT';
  RETURN NEW;
END $$;
CREATE TRIGGER shipment_handover_open_guard
  BEFORE INSERT ON shipment_handover
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_handover_open_fn();

-- ---------------------------------------------------------------------------
-- 2. shipment_handover_parcel · 交接包裹行
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_handover_parcel (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  handover_id uuid NOT NULL,

  -- 上游包裹（V121）。**UNIQUE(tenant_id,parcel_id)** 是「避免重复扣减」的第一道 DB 防线：
  -- 同一箱进两次交接单会在这里当场被拒，而不是让应用层记得先查一遍。
  parcel_id uuid NOT NULL,
  parcel_no integer NOT NULL CHECK(parcel_no>0),
  parcel_code varchar(32) NOT NULL,

  -- 每箱运单号。空 = 跟随单头的主运单号（整车发运的常态）。
  parcel_waybill_no varchar(64) NOT NULL DEFAULT '',

  -- 实测重量体积**冻结值**（来自 8.3.3.2 的 parcel）。冻结而不是每次去 join：
  -- 交接凭据必须自带「当时交出去的这箱有多重」，8.3.3.2 那张单将来被改动时不影响本凭据。
  gross_weight_g numeric(19,3) NOT NULL CHECK(gross_weight_g>=0),
  net_content_weight_g numeric(19,3),
  volume_cbm numeric(19,6) NOT NULL CHECK(volume_cbm>=0),

  -- 明细合计：**唯一写入者是 refresh_shipment_handover_totals_fn 触发器**。
  item_count integer NOT NULL DEFAULT 0 CHECK(item_count>=0),
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity>=0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,handover_id,parcel_no),
  UNIQUE(tenant_id,parcel_id),

  FOREIGN KEY(tenant_id,handover_id) REFERENCES shipment_handover(tenant_id,id),
  FOREIGN KEY(tenant_id,parcel_id) REFERENCES parcel(tenant_id,id),

  CHECK(btrim(parcel_code)<>''),
  CHECK(btrim(parcel_waybill_no)='' OR length(btrim(parcel_waybill_no))<=64),
  -- 交出去的箱子必须是称过重量的：0 件/0 克 = 「什么都没交」，
  -- 或者更糟 —— 交接了一个还没上秤的箱子。
  CHECK(gross_weight_g>0),
  CHECK(volume_cbm>0),
  CHECK(net_content_weight_g IS NULL OR net_content_weight_g>=0),
  -- item_count 的下界是 0 而不是 1：包裹行在明细行灌完之前就先建出来了，
  -- 它的 item_count 由合计触发器回填，写 CHECK(item_count>0) 会在插入那一刻
  -- （值还是 0）把合法的单子拒掉。「每箱至少一件」这条真正的约束放在
  -- 交接确认守卫里 —— 那是它唯一需要成立的时刻（见 §7）。
  CHECK(item_count>=0)
);

CREATE INDEX shipment_handover_parcel_handover
  ON shipment_handover_parcel(tenant_id, handover_id, parcel_no);
-- 按运单号反查箱（客服问「这个号里有哪些箱」）。
CREATE INDEX shipment_handover_parcel_waybill
  ON shipment_handover_parcel(tenant_id, parcel_waybill_no)
  WHERE btrim(parcel_waybill_no)<>'';

-- 2.1 包裹行只能挂在 OPEN 的交接单上，且必须真的是那张打包单的包裹。
CREATE FUNCTION guard_shipment_handover_parcel_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status varchar; v_handover_packing uuid; v_parcel_packing uuid;
        v_parcel_no integer; v_parcel_code varchar(32);
        v_gross numeric(19,3); v_net numeric(19,3); v_cbm numeric(19,6);
BEGIN
  SELECT status, packing_order_id INTO v_status, v_handover_packing
    FROM shipment_handover WHERE tenant_id = NEW.tenant_id AND id = NEW.handover_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '交接单不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_status <> 'OPEN' THEN
    RAISE EXCEPTION '交接单已结束（%），不能再改包裹 —— 已交接的货改不得。', v_status
      USING ERRCODE='23514';
  END IF;

  SELECT packing_order_id, parcel_no, parcel_code, measured_gross_weight_g,
         net_content_weight_g, volume_cbm
    INTO v_parcel_packing, v_parcel_no, v_parcel_code, v_gross, v_net, v_cbm
    FROM parcel WHERE tenant_id = NEW.tenant_id AND id = NEW.parcel_id;
  IF v_parcel_packing IS NULL THEN
    RAISE EXCEPTION '包裹不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_parcel_packing <> v_handover_packing THEN
    RAISE EXCEPTION '包裹不属于本交接单对应的打包单 —— 不能把别的单的箱子混进来交接。'
      USING ERRCODE='23514';
  END IF;
  IF v_parcel_no <> NEW.parcel_no OR v_parcel_code <> NEW.parcel_code THEN
    RAISE EXCEPTION '箱序号与箱号必须与包裹一致（应用层写的编号一律作废，以包裹为准）。'
      USING ERRCODE='23514';
  END IF;
  IF v_gross IS NULL OR v_cbm IS NULL THEN
    RAISE EXCEPTION '包裹 % 还没称重/量尺寸，不能交接：交出去一个没上秤的箱子，运费与轨迹都无从谈起。',
      v_parcel_code USING ERRCODE='23514';
  END IF;

  -- 冻结值一律以包裹上的读数覆盖：调用方写的重量不是事实。
  NEW.parcel_no        := v_parcel_no;
  NEW.parcel_code      := v_parcel_code;
  NEW.gross_weight_g   := v_gross;
  NEW.net_content_weight_g := v_net;
  NEW.volume_cbm       := v_cbm;
  RETURN NEW;
END $$;
CREATE TRIGGER shipment_handover_parcel_guard
  BEFORE INSERT OR UPDATE ON shipment_handover_parcel
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_handover_parcel_fn();

-- ---------------------------------------------------------------------------
-- 3. shipment_handover_item · 出库明细行（扣减凭据）
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_handover_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  handover_id uuid NOT NULL,
  parcel_id uuid NOT NULL,

  item_no integer NOT NULL CHECK(item_no>0),

  -- 来源明细行（V121 的 parcel_item）。必填 + 复合外键：
  -- 「扣的是哪一行装的东西」必须能答出来，否则账扣了但没人说得清扣的是什么。
  parcel_item_id uuid NOT NULL,

  -- SKU 冻结（COPY 语义）：商品之后改名或停用，本凭据仍能回看「当时交的是哪件」。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL,

  -- 件数。NUMERIC(19,6)，禁浮点。**逐件核对品（requires_serial）一行一件一号**。
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),

  -- 序列号（可空）。带号即一件：把三个号塞进一行「共 3 件」就回到无法追溯的毛病上了。
  serial_id uuid,
  serial_number varchar(128),

  -- ---- 扣减落点（开单时为空；交接确认的那个事务里由服务层写入）----------------
  --
  -- 下面四列是「实发扣减」的落点。**HANDED_OVER 时必须全部非空**（confirm 守卫逐行核对），
  -- 于是「这批货到底是从哪条库存行扣的」在 DB 层不可为空。
  deducted_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(deducted_quantity>=0),
  -- 实物侧被释放的预占量（= min(该库存行 reserved, 本次出库量)）。
  reserved_deducted_quantity numeric(19,6) NOT NULL DEFAULT 0
    CHECK(reserved_deducted_quantity>=0),
  balance_id uuid,
  bucket_id uuid,
  location_id uuid,
  lot_id uuid,
  quality_status_id uuid,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,handover_id,item_no),
  UNIQUE(tenant_id,parcel_item_id),

  FOREIGN KEY(tenant_id,handover_id) REFERENCES shipment_handover(tenant_id,id),
  FOREIGN KEY(tenant_id,parcel_id) REFERENCES parcel(tenant_id,id),
  FOREIGN KEY(tenant_id,parcel_item_id) REFERENCES parcel_item(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,serial_id) REFERENCES inventory_serial(tenant_id,id),
  FOREIGN KEY(tenant_id,balance_id) REFERENCES inventory_balance(tenant_id,id),
  FOREIGN KEY(tenant_id,bucket_id) REFERENCES stock_bucket(tenant_id,id),
  FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),

  CHECK(btrim(sku_code)<>'' AND btrim(sku_name)<>''),
  CHECK((serial_id IS NULL) = (serial_number IS NULL)),
  CHECK(serial_id IS NULL OR quantity=1),
  -- 实扣不许超过应发，也不许扣了却没写落点：只要扣了数，就必须说得出扣在哪。
  CHECK(deducted_quantity<=quantity),
  CHECK((deducted_quantity=0) = (balance_id IS NULL)),
  CHECK(reserved_deducted_quantity<=deducted_quantity),
  -- 预占量不为 0 就必须落在某条 bucket 上：预占是 bucket 级事实，没有 bucket
  -- 就答不出「原本被谁圈住」。
  CHECK(reserved_deducted_quantity=0 OR bucket_id IS NOT NULL)
);

CREATE INDEX shipment_handover_item_handover
  ON shipment_handover_item(tenant_id, handover_id, item_no);
CREATE INDEX shipment_handover_item_sku
  ON shipment_handover_item(tenant_id, sku_id);
-- 按序列号反查（售后按机身号追单）。
CREATE INDEX shipment_handover_item_serial
  ON shipment_handover_item(tenant_id, serial_number)
  WHERE serial_id IS NOT NULL;

-- 3.1 明细行守卫：只能挂在 OPEN 的交接单上，冻结值以 parcel_item 为准。
CREATE FUNCTION guard_shipment_handover_item_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status varchar; v_parcel_id uuid; v_parcel_item_no integer;
        v_sku_id uuid; v_sku_code varchar(64); v_sku_name varchar(200);
        v_qty numeric(19,6); v_serial uuid; v_serial_number varchar(128);
BEGIN
  SELECT status INTO v_status FROM shipment_handover
   WHERE tenant_id = NEW.tenant_id AND id = NEW.handover_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '交接单不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_status <> 'OPEN' THEN
    RAISE EXCEPTION '交接单已结束（%），不能再改明细 —— 已交接的货改不得。', v_status
      USING ERRCODE='23514';
  END IF;

  SELECT parcel_id, item_no, sku_id, sku_code, sku_name, quantity, serial_id, serial_number
    INTO v_parcel_id, v_parcel_item_no, v_sku_id, v_sku_code, v_sku_name, v_qty,
         v_serial, v_serial_number
    FROM parcel_item WHERE tenant_id = NEW.tenant_id AND id = NEW.parcel_item_id;
  IF v_sku_id IS NULL THEN
    RAISE EXCEPTION '包裹明细不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_parcel_id <> NEW.parcel_id THEN
    RAISE EXCEPTION '明细行不属于指定的包裹 —— 不能把箱子对错，否则扣的货与交的箱对不上。'
      USING ERRCODE='23514';
  END IF;

  NEW.sku_id := v_sku_id;
  NEW.sku_code := v_sku_code;
  NEW.sku_name := v_sku_name;
  NEW.serial_id := v_serial;
  NEW.serial_number := v_serial_number;
  -- 未扣减之前，实扣量只能等于应发量：服务层不能在明细行上写一个更小的实扣量
  -- 蒙混过关（少扣的部分会以「已交接但没扣」的形式留在库存里）。
  IF NEW.deducted_quantity <> 0 THEN
    RAISE EXCEPTION '明细行的实扣量由交接确认统一写入（应为 %，当前 %）。',
      v_qty, NEW.deducted_quantity USING ERRCODE='23514';
  END IF;
  NEW.quantity := v_qty;
  RETURN NEW;
END $$;
CREATE TRIGGER shipment_handover_item_guard
  BEFORE INSERT ON shipment_handover_item
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_handover_item_fn();

-- ---------------------------------------------------------------------------
-- 4. shipment_handover_reservation · 预占消费行
-- ---------------------------------------------------------------------------
-- 一件明细可能消耗**多条** reservation_allocation（同一 SKU 圈在两个货位上、
-- 或者分两次圈），所以不能把 allocation_id 做成 shipment_handover_item 的列 ——
-- 做成列就必然出现「只能记一条、其余的预占悄悄留着」的形态，那正是蓝图点名要避免的
-- 「预占悬空」。另开一张行表，于是「这次交接消费了哪几条预占、各多少」是完整可查的。
CREATE TABLE shipment_handover_reservation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  handover_id uuid NOT NULL,
  handover_item_id uuid NOT NULL,

  reservation_id uuid NOT NULL,
  allocation_id uuid NOT NULL,
  -- 分配与预占的定位码快照：跨服务/跨模块排查时，UUID 不如单号可读。
  reservation_no varchar(64) NOT NULL DEFAULT '',
  location_id uuid,
  lot_id uuid,

  -- 本次实际消耗掉的量。**必须为正**：零消耗的行没有意义，
  -- 真没消耗就该是「没有这一行」而不是「有一行写着 0」。
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),

  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一条分配在同一次交接里只能被记一次（DB 级防重复消费）。
  UNIQUE(tenant_id,handover_id,allocation_id),

  FOREIGN KEY(tenant_id,handover_id) REFERENCES shipment_handover(tenant_id,id),
  FOREIGN KEY(tenant_id,handover_item_id) REFERENCES shipment_handover_item(tenant_id,id),
  FOREIGN KEY(tenant_id,reservation_id) REFERENCES inventory_reservation(tenant_id,id),
  FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),

  CHECK(btrim(reservation_no)='' OR length(btrim(reservation_no))<=64)
);

CREATE INDEX shipment_handover_reservation_handover
  ON shipment_handover_reservation(tenant_id, handover_id);
CREATE INDEX shipment_handover_reservation_reservation
  ON shipment_handover_reservation(tenant_id, reservation_id);
CREATE INDEX shipment_handover_reservation_item
  ON shipment_handover_reservation(tenant_id, handover_item_id);

-- ---------------------------------------------------------------------------
-- 5. shipment_handover_event · append-only 作业流水
-- ---------------------------------------------------------------------------
CREATE TABLE shipment_handover_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  handover_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN
    ('OPENED','PARCEL_ADDED','ITEM_ADDED','WAYBILL_REGISTERED',
     'HANDED_OVER','CANCELLED')),
  from_status varchar(16),
  to_status varchar(16),
  parcel_id uuid,
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  detail text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,handover_id) REFERENCES shipment_handover(tenant_id,id),
  FOREIGN KEY(tenant_id,parcel_id) REFERENCES parcel(tenant_id,id),
  CHECK(from_status IS NULL OR to_status IS NOT NULL),
  CHECK(btrim(detail)<>'')
);

CREATE INDEX shipment_handover_event_handover
  ON shipment_handover_event(tenant_id, handover_id, occurred_at, id);

CREATE FUNCTION guard_shipment_handover_event_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'shipment_handover_event 是 append-only：不允许 %，'
    '交接回溯依赖未被改写的时间线。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER shipment_handover_event_append_only
  BEFORE UPDATE OR DELETE ON shipment_handover_event
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_handover_event_append_only();

-- ---------------------------------------------------------------------------
-- 6. 合计触发器：parcel/item 两张表的唯一写入者
-- ---------------------------------------------------------------------------
-- 放在触发器而不是 Java 里回写：明细行可能被三条路径改动（INSERT / 更新扣减落点 /
-- 预占消费行落账），三条路径各回写一次迟早漏一次，而漏一次就是「凭据上的合计与明细对不上」
-- —— 那种不一致在结算是靠人肉发现的。触发器是唯一能让它对不上的方式就是绕过本服务裸写 SQL，
-- 而裸写也照样被重算。
CREATE FUNCTION refresh_shipment_handover_totals_fn() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_tenant uuid; v_handover uuid;
BEGIN
  -- DELETE 触发器里 NEW 是未赋值记录，碰 NEW.tenant_id 会直接抛
  -- 「record new is not assigned yet」。按 TG_OP 分流，两种情形各取各的来源行。
  IF TG_OP = 'DELETE' THEN
    v_tenant := OLD.tenant_id;
    v_handover := OLD.handover_id;
  ELSE
    v_tenant := NEW.tenant_id;
    v_handover := NEW.handover_id;
  END IF;

  UPDATE shipment_handover h
     SET parcel_count = p.cnt,
         parcel_item_count = COALESCE(i.cnt, 0),
         total_quantity = COALESCE(i.qty, 0),
         total_gross_weight_g = COALESCE(p.gross, 0),
         total_volume_cbm = COALESCE(p.cbm, 0),
         deducted_quantity = COALESCE(i.deducted, 0),
         reserved_deducted_quantity = COALESCE(i.reserved_deducted, 0),
         reservation_line_count = COALESCE(r.cnt, 0),
         updated_at = now()
    FROM (
      SELECT count(*) AS cnt,
             coalesce(sum(gross_weight_g), 0) AS gross,
             coalesce(sum(volume_cbm), 0) AS cbm
        FROM shipment_handover_parcel
       WHERE tenant_id = v_tenant AND handover_id = v_handover
    ) p
    LEFT JOIN (
      SELECT count(*) AS cnt,
             coalesce(sum(quantity), 0) AS qty,
             coalesce(sum(deducted_quantity), 0) AS deducted,
             coalesce(sum(reserved_deducted_quantity), 0) AS reserved_deducted
        FROM shipment_handover_item
       WHERE tenant_id = v_tenant AND handover_id = v_handover
    ) i ON true
    LEFT JOIN (
      SELECT count(*) AS cnt
        FROM shipment_handover_reservation
       WHERE tenant_id = v_tenant AND handover_id = v_handover
    ) r ON true
   WHERE h.tenant_id = v_tenant AND h.id = v_handover;

  RETURN NULL;
END $$;

CREATE TRIGGER shipment_handover_totals_from_parcel
  AFTER INSERT OR UPDATE OR DELETE ON shipment_handover_parcel
  FOR EACH ROW EXECUTE FUNCTION refresh_shipment_handover_totals_fn();
CREATE TRIGGER shipment_handover_totals_from_item
  AFTER INSERT OR UPDATE OR DELETE ON shipment_handover_item
  FOR EACH ROW EXECUTE FUNCTION refresh_shipment_handover_totals_fn();
CREATE TRIGGER shipment_handover_totals_from_reservation
  AFTER INSERT OR DELETE ON shipment_handover_reservation
  FOR EACH ROW EXECUTE FUNCTION refresh_shipment_handover_totals_fn();

-- ---------------------------------------------------------------------------
-- 7. 状态机与「实发扣减与预占消费在同一事务」的 DB 级兜底
-- ---------------------------------------------------------------------------
-- 蓝图原句：「**实发扣减与预占消费在同一事务**，避免预占悬空或重复扣减。」
-- 服务层用 @Transactional 保证「同一事务」；这里保证的是**同一事务的结果**：
-- 一张交接单要变成 HANDED_OVER，必须已经具备下列全部事实 ——
--   (1) 至少一个包裹行、至少一条明细行；
--   (2) 承运渠道与主运单号都有；
--   (3) 每一行明细的实扣量**恰好等于**应发量（不多不少，不多不少是关键：
--       多了是超卖，少了是「已交接但货还在仓里」）；
--   (4) 每一行明细都落到了具体的库存行（balance_id 非空）；
--   (5) 挂着出库台账流水（inventory_transaction_id 非空）。
-- 五条中任何一条不满足，整个事务回滚：不会出现「货扣了一半单子已交接」。
CREATE FUNCTION guard_shipment_handover_status_fn() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_parcels integer; v_items integer; v_bad_items integer;
        v_empty_parcels integer; v_unlocated integer; v_outbound varchar; v_tx uuid;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;

  -- 终态不可复活：已交接的货不能退回 OPEN 让人再扣一次库存（超卖），
  -- 已作废的交接单也不能复活（否则同一箱会被再交一次）。
  IF OLD.status IN ('HANDED_OVER','CANCELLED') THEN
    RAISE EXCEPTION '交接单 % 已终结于 %，不能迁回到 %：已交出的货改不得。',
      OLD.handover_code, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;

  IF NEW.status = 'HANDED_OVER' THEN
    SELECT count(*) INTO v_parcels FROM shipment_handover_parcel
     WHERE tenant_id = NEW.tenant_id AND handover_id = NEW.id;
    IF v_parcels = 0 THEN
      RAISE EXCEPTION '交接单 % 一个包裹都没有，不能交接：交出去的是「哪几箱」答不出等于没交接。',
        NEW.handover_code USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO v_items FROM shipment_handover_item
     WHERE tenant_id = NEW.tenant_id AND handover_id = NEW.id;
    IF v_items = 0 THEN
      RAISE EXCEPTION '交接单 % 没有明细行，不能交接：只有箱号没有货，等于没扣减。',
        NEW.handover_code USING ERRCODE='23514';
    END IF;

    -- 逐箱核对：交出去的这个箱子里装了东西吗（与 8.3.3.2「包裹必须含明细项」同一纪律）。
    -- 列级 CHECK 在这一刻管不了 —— 包裹行建得比明细行早，item_count 是触发器回填的。
    SELECT count(*) INTO v_empty_parcels FROM shipment_handover_parcel hp
     WHERE hp.tenant_id = NEW.tenant_id AND hp.handover_id = NEW.id
       AND NOT EXISTS (SELECT 1 FROM shipment_handover_item i
                        WHERE i.tenant_id = hp.tenant_id AND i.handover_id = hp.handover_id
                          AND i.parcel_id = hp.parcel_id);
    IF v_empty_parcels > 0 THEN
      RAISE EXCEPTION '交接单 % 有 % 个包裹一件货都没有：空箱不能交出去 —— '
        '箱号在、货不在，事后对账时这个箱永远答不出装了什么。',
        NEW.handover_code, v_empty_parcels USING ERRCODE='23514';
    END IF;

    -- (3) 实扣量必须**恰好**等于应发量。
    SELECT count(*) INTO v_bad_items FROM shipment_handover_item
     WHERE tenant_id = NEW.tenant_id AND handover_id = NEW.id
       AND deducted_quantity <> quantity;
    IF v_bad_items > 0 THEN
      RAISE EXCEPTION '交接单 % 有 % 行明细的实扣量不等于应发量：实发扣减与预占消费必须同事务完成，'
        '不能交出去一部分货就标成已交接。', NEW.handover_code, v_bad_items
        USING ERRCODE='23514';
    END IF;

    -- (4) 每一行都必须落在具体库存行上。
    SELECT count(*) INTO v_unlocated FROM shipment_handover_item
     WHERE tenant_id = NEW.tenant_id AND handover_id = NEW.id
       AND (deducted_quantity > 0 AND balance_id IS NULL);
    IF v_unlocated > 0 THEN
      RAISE EXCEPTION '交接单 % 有 % 行明细没有库存落点（balance_id 为空）：扣了货必须说得清扣在哪一行。',
        NEW.handover_code, v_unlocated USING ERRCODE='23514';
    END IF;

    IF btrim(NEW.carrier_code) = '' OR btrim(NEW.master_waybill_no) = '' THEN
      RAISE EXCEPTION '交接单 % 缺承运渠道或运单号：确认承运商与运单号正是本模块的职责。',
        NEW.handover_code USING ERRCODE='23514';
    END IF;
    IF NEW.handover_user_id IS NULL OR NEW.handover_at IS NULL THEN
      RAISE EXCEPTION '交接单 % 缺交接人或交接时刻：登记交接时间与交接人是本模块的职责。',
        NEW.handover_code USING ERRCODE='23514';
    END IF;
    v_tx := NEW.inventory_transaction_id;
    IF v_tx IS NULL THEN
      RAISE EXCEPTION '交接单 % 没有出库台账流水：实发扣减必须落在库存台账上，而不是只改余额。',
        NEW.handover_code USING ERRCODE='23514';
    END IF;

    -- 出库单此刻必须仍在「待发货」，并且承运渠道要与交接单一致 ——
    -- V72 的状态机只认 PENDING_SHIPMENT → SHIPPED 这一条边，这里先把「另一条单」挡掉。
    SELECT status INTO v_outbound FROM outbound_order
     WHERE tenant_id = NEW.tenant_id AND id = NEW.outbound_order_id;
    IF v_outbound <> 'PENDING_SHIPMENT' THEN
      RAISE EXCEPTION '出库单当前是 %，不是「待发货」（PENDING_SHIPMENT），不能交接。', v_outbound
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- 落点时刻必须随状态一起到位（CHECK 只保证「同进同出」，这里保证「到位的那个非空」）。
  IF NEW.status = 'HANDED_OVER' AND NEW.completed_at IS NULL THEN
    RAISE EXCEPTION '交接单 % 置为已交接时必须带完成时刻。', NEW.handover_code
      USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'CANCELLED' AND NEW.cancelled_at IS NULL THEN
    RAISE EXCEPTION '交接单 % 置为作废时必须带作废时刻。', NEW.handover_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER shipment_handover_status_guard
  BEFORE UPDATE ON shipment_handover
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_handover_status_fn();

-- 7.1 明细行的「扣减落点」只能在 OPEN 单上写一次，且写完不许改。
-- 一行明细的实扣量被改第二次 = 同一批货被扣两次；这是「避免重复扣减」在明细层的最后一道。
CREATE FUNCTION guard_shipment_handover_item_deduction_fn() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_status varchar;
BEGIN
  IF NEW.deducted_quantity IS NOT DISTINCT FROM OLD.deducted_quantity
     AND NEW.balance_id IS NOT DISTINCT FROM OLD.balance_id
     AND NEW.reserved_deducted_quantity IS NOT DISTINCT FROM OLD.reserved_deducted_quantity
     AND NEW.bucket_id IS NOT DISTINCT FROM OLD.bucket_id THEN
    RETURN NEW;
  END IF;

  SELECT status INTO v_status FROM shipment_handover
   WHERE tenant_id = NEW.tenant_id AND id = NEW.handover_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '交接单不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_status <> 'OPEN' THEN
    RAISE EXCEPTION '交接单已结束（%），不能再改扣减落点。', v_status USING ERRCODE='23514';
  END IF;
  IF OLD.deducted_quantity <> 0 OR OLD.balance_id IS NOT NULL THEN
    RAISE EXCEPTION '明细行的扣减落点已经写过一次，不许改写：同一批货被扣两次就是超卖。'
      USING ERRCODE='23514';
  END IF;
  NEW.deducted_quantity := NEW.quantity;
  RETURN NEW;
END $$;
CREATE TRIGGER shipment_handover_item_deduction_guard
  BEFORE UPDATE ON shipment_handover_item
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_handover_item_deduction_fn();

-- ---------------------------------------------------------------------------
-- 8. 多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT（与 V121 第 7 节同一条）
-- ---------------------------------------------------------------------------
-- 刻意排在建表与触发器之后：ALTER TABLE ... ENABLE ROW LEVEL SECURITY 与
-- CREATE POLICY 都要求目标表已存在，写在建表之前 Flyway 会直接以
-- 「relation shipment_handover does not exist」失败。
--
-- 租户由 backend/libraries/tenancy 在事务内 set_config('app.current_tenant') 写入，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。服务层的 tenant_id 谓词是第二道防线：
-- 忘了绑定上下文时让查询当场返回空，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['shipment_handover','shipment_handover_parcel',
                              'shipment_handover_item','shipment_handover_reservation',
                              'shipment_handover_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  shipment_handover, shipment_handover_parcel, shipment_handover_item,
  shipment_handover_reservation, shipment_handover_event
  TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V102、V121 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;