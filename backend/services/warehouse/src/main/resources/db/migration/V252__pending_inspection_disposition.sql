-- ===========================================================================
-- V252 8.2.2.2 待检处理（待检区库存 → 质检 → 质量状态分流）
--
-- 蓝图 08 卷 8.2.2.2：
--   职责：管理待检区库存：发起质检任务（6 质检单）、质检通过后转入可售、质检不通过转残次/报废/退货。
--   输入：待检库存清单；质检结果；处置决定。
--   输出：质量状态变更分录；可售量更新。
--   关系：上游 = 8.2.1.1、6.4.2.1；下游 = 7.1.1.3 可用量、6.4.2.2。
--         **待检品不可售**是硬规则，质检通过前不能被销售预占。
--
-- ---------------------------------------------------------------------------
-- 一、本模块在数据模型上的位置：质量状态是 balance / bucket 的**第六个维度**
-- ---------------------------------------------------------------------------
-- V1 的分层已经写死（见 V1/V2 文件头）：
--   inventory_balance  仓库 × 货权 × SKU × **质量状态** → 可用量决策单位
--   stock_bucket       上面的四个维度 + 库位 + 批次      → 最小事实单位
--
-- 所以「质检通过后转入可售」在账上的形状**不是**改一列，而是：
--   待检 balance 的 on_hand 减 Q  +  可售 balance 的 on_hand 加 Q，
--   并且待检 bucket 减 Q（货离开待检库位）+ 可售 bucket 加 Q（货落到可拣库位）。
-- 两条分录必须落在**同一个 inventory_transaction** 上，否则「这批货去哪了」会散成
-- 两笔互不相干的业务，台账上答不出来。
--
-- 本迁移因此**不新增任何库存数量表**：质量状态变更复用 V1 已有的
-- inventory_transaction / inventory_entry（append-only、只追加不修改），
-- 本模块只新增「待检处理单 + 处置明细 + 事件台账」三张**本模块自有**的表。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 硬规则「待检品不可售」：本模块不能靠应用层自觉
-- ---------------------------------------------------------------------------
-- 这一条在别处已经有两道防线，本迁移**不动**它们，只声明依赖：
--   * V1 guard_balance_reservable：非 SELLABLE 质量的 balance 预占 > 0 直接被拒；
--   * V2 guard_bucket_reservable：同一道防线在 bucket 级；
--   * 7.1.1.3 可用量计算（AvailableQuantityService）：可售基数只认 role='SELLABLE'。
-- 所以「待检品不会被销售预占」在本模块交付前就已经成立；本模块要做的是
-- **把待检品变成可售品的那一步必须真的走库存分录**，
-- 而不是只把单据上的状态从「待检」改成「可售」（那样账与物永远对不上）。
--
-- 本迁移再加第三道（属于本模块自己的表）：
--   guard_pending_inspection_task()：处置完成的瞬间回读账本分录核对 P1/P2/P3/P4，
--   绕过服务层直连 SQL 也绕不过 —— 与 8.2.2.1 上架任务的 guard 同一手法。
--
-- ---------------------------------------------------------------------------
-- 三、质检单（6 4 2 1）在 trade 服务：只留逻辑引用 + 可读快照
-- ---------------------------------------------------------------------------
-- 6.4.2.1 质检单表在 **trade 服务**的库里（V249__quality_inspection_order.sql），
-- AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 视图。所以本表只存：
--   inspection_order_id    逻辑 ID（无外键 —— 跨库 FK 会被直接拒）
--   inspection_order_code  可读单号快照（trade 不可达时仍然答得出「检的是哪张单」）
-- 发起质检这个动作由本服务经 **trade 自己的授权端点** POST /trade/quality-inspections
-- 完成（见 QualityInspectionTradePort）：质检单事实表的写入所有者始终是 trade，
-- 本服务既不在本地建质检单表，也不跨库读写。
--
-- ---------------------------------------------------------------------------
-- 四、三条核心不变量（回读真正的账本分录，不是单据上的自报数字）
-- ---------------------------------------------------------------------------
--   P1：转 DISPOSED 时，该任务那条 inventory_transaction 的**负向分录绝对值之和**
--       必须**恰好等于**任务量 —— 待检货必须**全部**被处置掉。
--       少扣 = 这批货卡在待检区，账上却已经判了结论；多扣 = 凭空少货。
--   P2：正向分录之和（转入可售 / 残次）= 任务量 − 报废量 − 退货量。
--       报废与退货是**离仓**（货不再在本仓），所以它们只出现在负向分录里。
--   P3：逐行核对每一张处置明细 —— 每行必须有一条恰好等于该行数量的负向分录；
--       转入可售 / 残次的行还必须有一条恰好等于该行数量的正向分录，
--       且落在**该行指定的目标余额行**上（落在别的余额行上 = 货进了别的地方）。
--   P4：目标余额行的质量角色必须与处置类型一致
--       （SELLABLE 行 → role='SELLABLE'；DEFECTIVE 行 → role='DEFECTIVE'），
--       且目标库位类型必须与质量角色匹配（复用 8.1.2.2 的权威函数
--       location_allocation_required_type，不在本文件里抄一份 CASE）。
--
-- 触发器放在 BEFORE UPDATE 到 DISPOSED 的那一刻，因为那才是「这批货已经有结论了」
-- 这个断言成立的时刻；分录与明细行都必须在同一次事务里先落库。
--
-- ---------------------------------------------------------------------------
-- 五、数量与时间口径（与 V1 / V2 / V70 / V109 完全一致，不另立一套）
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,6) + Java BigDecimal，全程禁浮点。
--   stock_bucket.on_hand / inventory_balance.on_hand 是 NUMERIC(19,4)，
--   所以本表的数量必须**能被 4 位小数精确表示**：行级 CHECK 用 round(x,4)=x 钉死，
--   服务层显式拒绝。向下取整 = 凭空吞货，向上取整 = 凭空多处置。
--   时间一律 timestamptz，Java 侧绑 OffsetDateTime，SQL values 位置写 ?::timestamptz。
--   （用 Timestamp.valueOf(LocalDateTime) 绑 timestamptz 会静默偏 −28800s。）

-- ===========================================================================
-- 0. 角色（可能已由运维预置，只在缺失时创建，与 V1 同口径）
-- ===========================================================================
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='warehouse_app') THEN CREATE ROLE warehouse_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO warehouse_app;

-- ===========================================================================
-- 1. 待检处理单（单头）
-- ===========================================================================
CREATE SEQUENCE pending_inspection_task_code_seq START 1;

CREATE TABLE pending_inspection_task (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 单号。租户内唯一：两张单同号则「按单号找任务」有两个答案。
  task_code varchar(32) NOT NULL,
  warehouse_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 上游 8.2.1.1 收货单与收货明细行：**真外键**（同服务）。
  -- 「待检库存的来源」必须是真实的收货记录 —— 蓝图把 8.2.1.1 写死为上游，
  -- 而 6.4.2.1 质检单本身也必须挂在一张真实收货单上（trade 侧到货量来自 8 收货）。
  receipt_id uuid NOT NULL,
  receipt_code varchar(32) NOT NULL,
  receipt_line_id uuid NOT NULL,
  -- 待检货现在压着的那个 bucket（库位 × 批次 × 质量状态粒度）。
  -- **外键是真外键**：待检处理单指向的是「这批货此刻所在的库存事实行」，
  -- 不是一句「有个待检批次」的描述。没有它就答不出「处置完货还在不在原地」。
  source_bucket_id uuid NOT NULL,
  source_location_id uuid NOT NULL,
  -- 待检余额行：处置时从这里扣。
  source_balance_id uuid NOT NULL,
  lot_id uuid,
  -- 待检质量状态。role 必须是 PENDING_INSPECTION —— 由 guard_pending_inspection_task_source
  -- 在插入时回读 inventory_quality_status 裁决（跨表条件写不进 CHECK）。
  pending_quality_status_id uuid NOT NULL,
  -- 本次待检处理的批量。
  quantity numeric(19,6) NOT NULL
    CHECK(quantity > 0 AND quantity = round(quantity, 4)),
  -- 生命周期：
  --   PENDING              待发起质检
  --   INSPECTION_REQUESTED 已发起质检任务（等 trade 侧 6.4.2.1 出结论）
  --   RESULT_RECORDED      已录入质检结果（等处置决定）
  --   DISPOSED              已处置（库存分录已入账）
  --   CANCELLED            已取消（不再走这次待检处理）
  -- DISPOSED 与 CANCELLED 是终态，不可逆。
  status varchar(24) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','INSPECTION_REQUESTED','RESULT_RECORDED','DISPOSED','CANCELLED')),
  -- 6.4.2.1 质检单（trade 服务）：逻辑引用 + 可读快照，无外键（见文件头第三节）。
  inspection_order_id uuid,
  inspection_order_code varchar(64) NOT NULL DEFAULT '',
  inspection_template_id uuid,
  inspection_template_code varchar(64) NOT NULL DEFAULT '',
  inspection_requested_at timestamptz,
  inspection_requested_by uuid,
  -- 质检结果（三量，与 6.4.2.1 同口径：合格 + 不合格 + 让步接收 = 本次批量）。
  result_type varchar(24) CHECK(result_type IN ('PASSED','PARTIAL','FAILED','CONCESSION_ACCEPTED')),
  qualified_quantity numeric(19,6),
  unqualified_quantity numeric(19,6),
  concession_quantity numeric(19,6),
  result_recorded_at timestamptz,
  result_recorded_by uuid,
  result_note text NOT NULL DEFAULT '',
  -- 处置总览（明细在 pending_inspection_disposition）。四个去向：
  --   SELLABLE  转可售（质检通过）
  --   DEFECTIVE 转残次（质检不通过但留仓）
  --   SCRAP     报废（离仓，不再是本仓库存）
  --   RETURN    退货（离仓，退回发货方）
  -- 一个任务可以同时有多个去向（例如部分转可售 + 部分转残次）。
  disposition_summary varchar(24) NOT NULL DEFAULT ''
    CHECK(disposition_summary IN ('','SELLABLE','DEFECTIVE','SCRAP','RETURN','MIXED')),
  -- 库存分录凭据：一次处置 = **一条** inventory_transaction + N 条分录。
  -- ref_type='PENDING_INSPECTION'。一张任务一条交易，于是
  -- UNIQUE(tenant_id,ref_type,ref_id) 顺带给整张任务单幂等，
  -- 而 P1/P2 的核对只在单条交易内才说得清。
  inventory_transaction_id uuid,
  -- 处置前后该 SKU 在本仓的**可售量**（7.1.1.3 口径：role='SELLABLE' ∧ 库位可拣启用）。
  -- 「可售量更新」是本模块的输出之一：处置完成后这两个数一减，就是这次质检放行的货。
  -- 快照成两列而不是每次现算，是因为「处置前」的那一刻已经过去了 ——
  -- 现算只能算出处置后的数，处置前的数就永远答不出来了。
  sellable_quantity_before numeric(19,6),
  sellable_quantity_after numeric(19,6),
  disposed_at timestamptz,
  disposed_by uuid,
  cancelled_at timestamptz,
  cancelled_by uuid,
  cancel_reason varchar(200) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  notes text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_code),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES goods_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_line_id) REFERENCES goods_receipt_line(tenant_id,id),
  FOREIGN KEY(tenant_id,source_bucket_id) REFERENCES stock_bucket(tenant_id,id),
  FOREIGN KEY(tenant_id,source_location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,source_balance_id) REFERENCES inventory_balance(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  FOREIGN KEY(tenant_id,pending_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  -- 质检单引用要么两个都有、要么两个都没有（只存 id 不存单号 = 答不出「检的是哪张单」）。
  CHECK((inspection_order_id IS NULL) = (inspection_order_code = '')),
  -- 发了质检就必须有时间与发起人；录了结果就必须有时间与记录人。
  CHECK(status <> 'INSPECTION_REQUESTED'
     OR (inspection_order_id IS NOT NULL AND inspection_requested_at IS NOT NULL
         AND inspection_requested_by IS NOT NULL)),
  CHECK(status NOT IN ('RESULT_RECORDED','DISPOSED')
     OR (result_recorded_at IS NOT NULL AND result_recorded_by IS NOT NULL)),
  -- 已处置必须带分录凭据；未处置绝不能有（未处置却有分录 = 货已经动了但结论还没落）。
  CHECK((status = 'DISPOSED') = (inventory_transaction_id IS NOT NULL)),
  -- 可售量快照要么两个都有、要么两个都没有；已处置必须有两个。
  CHECK((sellable_quantity_before IS NULL) = (sellable_quantity_after IS NULL)),
  CHECK(status <> 'DISPOSED'
     OR (sellable_quantity_before IS NOT NULL AND sellable_quantity_after IS NOT NULL)),
  CHECK(status <> 'DISPOSED' OR (disposed_at IS NOT NULL AND disposed_by IS NOT NULL)),
  CHECK(status <> 'CANCELLED'
     OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND btrim(cancel_reason)<>'')),
  -- 三量必须与本次批量对得上：合格 + 不合格 + 让步 = 批量。
  -- 写不进一个「三量加起来不等于批量」的质检结果。
  CHECK(qualified_quantity IS NULL
     OR (unqualified_quantity IS NOT NULL AND concession_quantity IS NOT NULL
         AND qualified_quantity >= 0 AND unqualified_quantity >= 0 AND concession_quantity >= 0
         AND qualified_quantity + unqualified_quantity + concession_quantity = quantity)),
  CHECK(btrim(task_code)<>'' AND btrim(receipt_code)<>'' AND btrim(sku_code)<>'')
);

COMMENT ON TABLE pending_inspection_task IS
  '8.2.2.2 待检处理：待检区库存的一次处理过程（发起质检 → 质检结果 → 处置决定 → 质量状态变更分录）。';
COMMENT ON COLUMN pending_inspection_task.source_bucket_id IS
  '待检货此刻所在的库存事实行（库位 × 批次 × 质量状态）。处置时从这里扣，扣不动就是货已经不在待检区了。';
COMMENT ON COLUMN pending_inspection_task.inspection_order_id IS
  '6.4.2.1 质检单（trade 服务）的逻辑 ID。无外键：跨服务 FK 被 AGENTS.md 禁止。';
COMMENT ON COLUMN pending_inspection_task.inventory_transaction_id IS
  '本任务落的那条 inventory_transaction（ref_type=PENDING_INSPECTION）。P1/P2/P3 的核对范围。';

CREATE INDEX pending_inspection_task_list
  ON pending_inspection_task(tenant_id,status,warehouse_id,created_at DESC);
CREATE INDEX pending_inspection_task_receipt
  ON pending_inspection_task(tenant_id,receipt_id,receipt_line_id);
CREATE INDEX pending_inspection_task_sku
  ON pending_inspection_task(tenant_id,sku_id,status);
CREATE INDEX pending_inspection_task_tx
  ON pending_inspection_task(tenant_id,inventory_transaction_id)
  WHERE inventory_transaction_id IS NOT NULL;

-- **同一个待检 bucket 同时只能有一个未完结的待检处理单**。
-- 少了这条，同一批待检货可以被建两张单、两张单各自处置一次 ——
-- 第二次处置会把货扣成负数（被 V2 的 CHECK(available>=0) 挡住，但报错完全指不到病根）。
CREATE UNIQUE INDEX pending_inspection_task_open_bucket
  ON pending_inspection_task(tenant_id,source_bucket_id)
  WHERE status NOT IN ('DISPOSED','CANCELLED');

-- ===========================================================================
-- 2. 处置明细（一个任务 → 1..N 个去向）
-- ===========================================================================
CREATE TABLE pending_inspection_disposition (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  -- 去向。四值与单头的 disposition_summary 同口径。
  disposition_type varchar(24) NOT NULL
    CHECK(disposition_type IN ('SELLABLE','DEFECTIVE','SCRAP','RETURN')),
  quantity numeric(19,6) NOT NULL
    CHECK(quantity > 0 AND quantity = round(quantity, 4)),
  -- 目标质量状态：SELLABLE / DEFECTIVE 行必填（转入哪个质量状态），
  -- SCRAP / RETURN 行必须为空（货离仓，不再是本仓的任何一个质量状态）。
  target_quality_status_id uuid,
  -- 目标库位：SELLABLE / DEFECTIVE 行必填；SCRAP / RETURN 行必须为空。
  target_location_id uuid,
  -- 落地后的余额行与库位明细行（服务层回填；SCRAP / RETURN 为空）。
  target_balance_id uuid,
  target_bucket_id uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES pending_inspection_task(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,target_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,target_location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,target_balance_id) REFERENCES inventory_balance(tenant_id,id),
  FOREIGN KEY(tenant_id,target_bucket_id) REFERENCES stock_bucket(tenant_id,id),
  -- 留仓的行必须有目标质量状态与目标库位；离仓的行必须两样都没有。
  CHECK((disposition_type IN ('SELLABLE','DEFECTIVE'))
        = (target_quality_status_id IS NOT NULL AND target_location_id IS NOT NULL)),
  -- 离仓的行绝不能带落地余额行（货都不在本仓了，哪来的余额行）。
  CHECK(disposition_type NOT IN ('SCRAP','RETURN')
     OR (target_balance_id IS NULL AND target_bucket_id IS NULL))
);

COMMENT ON TABLE pending_inspection_disposition IS
  '待检处置明细：一次处置的去向拆分（转可售 / 转残次 / 报废 / 退货）。数量必须与账本分录逐行对上（P3）。';

CREATE INDEX pending_inspection_disposition_task
  ON pending_inspection_disposition(tenant_id,task_id);

-- ===========================================================================
-- 3. 事件台账（append-only）
-- ===========================================================================
-- 为什么要有：一次待检处理会经历「建单 → 发起质检 → 录入结果 → 处置」四步，
-- 事后追责时要能回答「谁在什么时候把这批货判成可售的」。
-- 单头上的时间戳只记最后一次动作，事件台账记全过程。
CREATE TABLE pending_inspection_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  event_type varchar(24) NOT NULL
    CHECK(event_type IN ('CREATED','INSPECTION_REQUESTED','RESULT_RECORDED','DISPOSED','CANCELLED')),
  -- 事件发生时的业务时间（不是行写入库的时刻）。
  occurred_at timestamptz NOT NULL,
  actor_id uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES pending_inspection_task(tenant_id,id) ON DELETE CASCADE
);

CREATE INDEX pending_inspection_event_task
  ON pending_inspection_event(tenant_id,task_id,occurred_at,id);

-- ===========================================================================
-- 4. 守卫触发器
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 4.1 建单时的源校验：待检 bucket 的角色、存量与收货明细行必须对得上
-- ---------------------------------------------------------------------------
-- 为什么不能用 CHECK：跨表条件（bucket 的质量角色、bucket 的在手量、
-- 收货明细行的 SKU）写不进 CHECK，只能靠触发器回读。
CREATE FUNCTION guard_pending_inspection_task_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_role varchar(20);
  v_on_hand numeric(19,4);
  v_bucket_sku uuid;
  v_line_sku uuid;
  v_line_qty numeric(19,6);
  v_line_receipt uuid;
BEGIN
  SELECT q.role, b.on_hand, b.sku_id
    INTO v_role, v_on_hand, v_bucket_sku
    FROM stock_bucket b
    JOIN inventory_quality_status q
      ON q.tenant_id = b.tenant_id AND q.id = b.quality_status_id
   WHERE b.tenant_id = NEW.tenant_id AND b.id = NEW.source_bucket_id;

  IF v_role IS NULL THEN
    RAISE EXCEPTION '待检处理单 % 指向的库存行不存在或无权访问', NEW.task_code USING ERRCODE='23503';
  END IF;

  IF v_role <> 'PENDING_INSPECTION' THEN
    RAISE EXCEPTION '待检处理单 % 的来源库存质量角色是「%」，不是待检：'
      '只有 role=PENDING_INSPECTION 的库存才能进待检处理', NEW.task_code, v_role USING ERRCODE='23514';
  END IF;

  IF v_on_hand < NEW.quantity THEN
    RAISE EXCEPTION '待检处理单 % 的批量 % 超过库位上的实际在手量 %：'
      '货已经不在待检区了（可能被别的流程处置过），先刷新清单',
      NEW.task_code, NEW.quantity, v_on_hand USING ERRCODE='23514';
  END IF;

  SELECT l.sku_id, l.received_quantity, l.receipt_id
    INTO v_line_sku, v_line_qty, v_line_receipt
    FROM goods_receipt_line l
   WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.receipt_line_id;

  IF v_line_sku IS NULL THEN
    RAISE EXCEPTION '待检处理单 % 引用的收货明细行不存在或无权访问', NEW.task_code USING ERRCODE='23503';
  END IF;

  IF v_line_receipt <> NEW.receipt_id THEN
    RAISE EXCEPTION '待检处理单 % 的收货单与收货明细行对不上：明细行属于另一张收货单',
      NEW.task_code USING ERRCODE='23514';
  END IF;

  IF v_line_sku <> NEW.sku_id OR v_bucket_sku <> NEW.sku_id THEN
    RAISE EXCEPTION '待检处理单 % 的 SKU 三处不一致（收货明细行 / 库存行 / 单头）：'
      '质检单与库存对不上就是错的', NEW.task_code USING ERRCODE='23514';
  END IF;

  IF v_line_qty < NEW.quantity THEN
    RAISE EXCEPTION '待检处理单 % 的批量 % 超过收货明细行的实收量 %：'
      '待检量不能多于收进来的量', NEW.task_code, NEW.quantity, v_line_qty USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;
CREATE TRIGGER pending_inspection_task_source_guard
  BEFORE INSERT OR UPDATE OF source_bucket_id, receipt_line_id, quantity, sku_id
  ON pending_inspection_task
  FOR EACH ROW EXECUTE FUNCTION guard_pending_inspection_task_source();

-- ---------------------------------------------------------------------------
-- 4.2 处置明细守卫：目标库位类型必须与质量角色匹配，且不允许混放
-- ---------------------------------------------------------------------------
-- 复用 8.1.2.2（V106）的权威函数 location_allocation_required_type(role)：
-- 规则只有一个定义，本文件不抄一份 CASE（抄一份就会改一处忘一处，
-- 而「残次品混进非残次库位」是蓝图点名的硬规则）。
CREATE FUNCTION guard_pending_inspection_disposition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_role varchar(20);
  v_required varchar(16);
  v_type varchar(16);
  v_conflict varchar(20);
  v_warehouse uuid;
  v_task_warehouse uuid;
  v_sum numeric(19,6);
  v_qty numeric(19,6);
BEGIN
  SELECT t.warehouse_id, t.quantity INTO v_task_warehouse, v_qty
    FROM pending_inspection_task t
   WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.task_id;

  IF v_task_warehouse IS NULL THEN
    RAISE EXCEPTION '待检处置明细引用的待检处理单不存在或无权访问' USING ERRCODE='23503';
  END IF;

  -- 4.2.1 离仓行（SCRAP / RETURN）：不允许有目标质量状态 / 目标库位。
  IF NEW.disposition_type IN ('SCRAP','RETURN') THEN
    IF NEW.target_quality_status_id IS NOT NULL OR NEW.target_location_id IS NOT NULL THEN
      RAISE EXCEPTION '报废 / 退货的货离开本仓，不得指定目标质量状态或目标库位：'
        '货不在本仓了，落到哪个库位都是假的' USING ERRCODE='23514';
    END IF;
  ELSE
    -- 4.2.2 留仓行：目标质量状态必须存在，且库位类型必须与角色匹配。
    SELECT q.role INTO v_role
      FROM inventory_quality_status q
     WHERE q.tenant_id = NEW.tenant_id AND q.id = NEW.target_quality_status_id;
    IF v_role IS NULL THEN
      RAISE EXCEPTION '目标质量状态不存在或无权访问' USING ERRCODE='23503';
    END IF;

    SELECT loc.type, loc.warehouse_id INTO v_type, v_warehouse
      FROM warehouse_location loc
     WHERE loc.tenant_id = NEW.tenant_id AND loc.id = NEW.target_location_id;
    IF v_type IS NULL THEN
      RAISE EXCEPTION '目标库位不存在或无权访问' USING ERRCODE='23503';
    END IF;

    IF v_warehouse <> v_task_warehouse THEN
      RAISE EXCEPTION '目标库位属于另一个仓库：待检货在仓 % ，不能处置到仓 %',
        v_task_warehouse, v_warehouse USING ERRCODE='23514';
    END IF;

    v_required := location_allocation_required_type(v_role);
    IF v_required IS NULL THEN
      RAISE EXCEPTION '质量状态角色「%」没有对应的库位类型', v_role USING ERRCODE='23514';
    END IF;
    IF v_type <> v_required THEN
      RAISE EXCEPTION '质量状态「%」只能进 % 类库位，目标库位是 % 类：%', v_role, v_required, v_type,
        CASE WHEN v_role = 'DEFECTIVE' THEN '残次品混进非残次库位会让它跟着正常品一起被拣走发出去'
             WHEN v_role = 'PENDING_INSPECTION' THEN '待检品混进非待检库位等于未过质检就进了可发货区'
             ELSE '质量状态与库位类型不一致会让「这批货在不在待检区」永远答不出来' END
        USING ERRCODE='23514';
    END IF;

    -- 4.2.3 不允许混放：目标库位上已经压着别的质量角色的货。
    SELECT min(q.role) INTO v_conflict
      FROM stock_bucket k
      JOIN inventory_quality_status q
        ON q.tenant_id = k.tenant_id AND q.id = k.quality_status_id
     WHERE k.tenant_id = NEW.tenant_id AND k.location_id = NEW.target_location_id
       AND k.on_hand > 0 AND q.role <> v_role;
    IF v_conflict IS NOT NULL THEN
      RAISE EXCEPTION '目标库位上已压着质量角色「%」的货，与「%」不允许混放',
        v_conflict, v_role USING ERRCODE='23514';
    END IF;

    -- 4.2.4 处置去向必须与目标质量角色一致：转可售不能落到残次状态上。
    IF NEW.disposition_type = 'SELLABLE' AND v_role <> 'SELLABLE' THEN
      RAISE EXCEPTION '转可售的目标质量状态角色是「%」，不是 SELLABLE：不合格品不能被转成可售',
        v_role USING ERRCODE='23514';
    END IF;
    IF NEW.disposition_type = 'DEFECTIVE' AND v_role <> 'DEFECTIVE' THEN
      RAISE EXCEPTION '转残次的目标质量状态角色是「%」，不是 DEFECTIVE', v_role USING ERRCODE='23514';
    END IF;
  END IF;

  -- 4.2.5 一张任务上的处置量之和不得超过任务量。
  SELECT coalesce(sum(d.quantity),0) INTO v_sum
    FROM pending_inspection_disposition d
   WHERE d.tenant_id = NEW.tenant_id AND d.task_id = NEW.task_id;
  IF v_sum + NEW.quantity > v_qty THEN
    RAISE EXCEPTION '待检处理单的处置量之和 % 超过任务批量 %：货只有这么多',
      v_sum + NEW.quantity, v_qty USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;
CREATE TRIGGER pending_inspection_disposition_guard
  BEFORE INSERT OR UPDATE ON pending_inspection_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_pending_inspection_disposition();

-- ---------------------------------------------------------------------------
-- 4.3 状态机 + 四条不变量（回读真正的账本分录）
-- ---------------------------------------------------------------------------
-- 合法迁移：PENDING → INSPECTION_REQUESTED → RESULT_RECORDED → DISPOSED；
--           PENDING / INSPECTION_REQUESTED → CANCELLED。
-- DISPOSED 与 CANCELLED 是终态，不可逆：
--   DISPOSED 后货已经在新库位 / 已经离仓，把它改回去等于宣布「这批货还在待检区」，
--     而库里它已经不在那儿了 —— 货实不符。
--   CANCELLED 后一条分录都不该产生，把它改回 PENDING 也不会凭空多出货，
--     但会让「已取消」这个现场事实凭空消失。
CREATE FUNCTION guard_pending_inspection_task() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_tx uuid;
  v_entries bigint;
  v_out numeric(19,6);
  v_in numeric(19,6);
  v_leave numeric(19,6);
  v_lines bigint;
  v_cnt bigint;
  v_sum numeric(19,6);
  v_role varchar(20);
  v_src_role varchar(20);
  rec record;
BEGIN
  -- ---- 4.3.1 终态不可逆
  IF OLD.status IN ('DISPOSED','CANCELLED') AND NEW.status IS DISTINCT FROM OLD.status THEN
    RAISE EXCEPTION '待检处理单 % 已经是终态 %，不能再流转成 %：货已经处置掉了，'
      '把它改回去等于宣布这批货还在待检区，而库里它已经不在那儿了',
      OLD.task_code, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;

  -- ---- 4.3.2 已录入结果后不允许再改数量 / 来源（货已经判过了）
  IF OLD.status IN ('RESULT_RECORDED','DISPOSED')
     AND (NEW.quantity IS DISTINCT FROM OLD.quantity
          OR NEW.source_bucket_id IS DISTINCT FROM OLD.source_bucket_id
          OR NEW.pending_quality_status_id IS DISTINCT FROM OLD.pending_quality_status_id) THEN
    RAISE EXCEPTION '待检处理单 % 已录入质检结果，批量与来源不可再改：'
      '质检结论是针对这一批货判的，换一批货结论就不作数了',
      OLD.task_code USING ERRCODE='23514';
  END IF;

  IF NEW.status = 'DISPOSED' THEN
    v_tx := NEW.inventory_transaction_id;
    IF v_tx IS NULL THEN
      RAISE EXCEPTION '待检处理单 % 标记完成但没有库存分录凭据：一次处置必须留下可审阅的账',
        NEW.task_code USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO v_entries
      FROM inventory_transaction t
     WHERE t.tenant_id = NEW.tenant_id AND t.id = v_tx
       AND t.ref_type = 'PENDING_INSPECTION' AND t.ref_id = NEW.id;
    IF v_entries = 0 THEN
      RAISE EXCEPTION '待检处理单 % 的库存分录凭据不存在或来源对不上：'
        'ref_type 必须是 PENDING_INSPECTION 且 ref_id 指向本单',
        NEW.task_code USING ERRCODE='23514';
    END IF;

    -- ---- P1：负向分录绝对值之和必须**恰好等于**任务量（待检货全部被处置）。
    SELECT count(*), coalesce(sum(-e.on_hand_delta),0)
      INTO v_cnt, v_out
      FROM inventory_entry e
     WHERE e.tenant_id = NEW.tenant_id AND e.transaction_id = v_tx AND e.on_hand_delta < 0;
    -- 转入量 = **正向**分录之和（负向子集的和是负数，用它比就是拿负数比正数）。
    SELECT coalesce(sum(e.on_hand_delta),0) INTO v_in
      FROM inventory_entry e
     WHERE e.tenant_id = NEW.tenant_id AND e.transaction_id = v_tx AND e.on_hand_delta > 0;
    IF v_cnt = 0 THEN
      RAISE EXCEPTION '待检处理单 % 标记完成但账上没有任何出账分录：货已经处置了却没有留下账（P1）',
        NEW.task_code USING ERRCODE='23514';
    END IF;
    IF v_out <> NEW.quantity THEN
      RAISE EXCEPTION '待检处理单 % 违反不变量：账上转出量 % ≠ 任务批量 %（P1）。'
        '少扣 = 这批货卡在待检区却已经判了结论；多扣 = 凭空少货',
        NEW.task_code, v_out, NEW.quantity USING ERRCODE='23514';
    END IF;

    -- ---- P2：转入量 = 任务量 − 离仓量（报废 + 退货）。
    SELECT coalesce(sum(d.quantity),0) INTO v_leave
      FROM pending_inspection_disposition d
     WHERE d.tenant_id = NEW.tenant_id AND d.task_id = NEW.id
       AND d.disposition_type IN ('SCRAP','RETURN');
    IF v_in <> v_out - v_leave THEN
      RAISE EXCEPTION '待检处理单 % 违反不变量：账上转入量 % ≠ 转出量 % − 离仓量 %（P2）。'
        '报废与退货的货离仓，不再转入任何质量状态',
        NEW.task_code, v_in, v_out, v_leave USING ERRCODE='23514';
    END IF;

    -- ---- P3：逐行核对。
    -- 关键：**分录归属只能按「余额行」对，不能按「第几行」对** ——
    -- 一条 inventory_entry 不记它是第几条明细生成的（那需要额外一列，且必然漂）。
    -- 所以对账口径是：每个目标余额行上的转入量之和 = 指向该余额行的明细量之和。
    SELECT count(*) INTO v_lines
      FROM pending_inspection_disposition d
     WHERE d.tenant_id = NEW.tenant_id AND d.task_id = NEW.id;
    IF v_lines = 0 THEN
      RAISE EXCEPTION '待检处理单 % 没有任何处置明细就要标记完成：没有处置明细等于没处置',
        NEW.task_code USING ERRCODE='23514';
    END IF;

    -- 每条明细恰好一条转出分录：条数必须相等（多了 = 有没登记的扣减，少了 = 有明细没入账）。
    SELECT count(*) INTO v_cnt
      FROM inventory_entry e
     WHERE e.tenant_id = NEW.tenant_id AND e.transaction_id = v_tx
       AND e.balance_id = NEW.source_balance_id AND e.on_hand_delta < 0;
    IF v_cnt <> v_lines THEN
      RAISE EXCEPTION '待检处理单 % 的转出分录条数 % ≠ 处置明细条数 %（P3）：'
        '每条明细必须恰好有一条等量的转出分录',
        NEW.task_code, v_cnt, v_lines USING ERRCODE='23514';
    END IF;

    -- 目标余额行上的转入量 = 指向它的明细量之和。**两个方向都要比**：
    -- 只比一个方向，「账上多了一笔转入」与「明细多了一条」都会被放过。
    IF EXISTS (
         SELECT d.target_balance_id AS bal, sum(d.quantity) AS qty
           FROM pending_inspection_disposition d
          WHERE d.tenant_id = NEW.tenant_id AND d.task_id = NEW.id
            AND d.target_balance_id IS NOT NULL
          GROUP BY d.target_balance_id
         EXCEPT
         SELECT e.balance_id, sum(e.on_hand_delta)
           FROM inventory_entry e
          WHERE e.tenant_id = NEW.tenant_id AND e.transaction_id = v_tx
            AND e.on_hand_delta > 0
          GROUP BY e.balance_id
       )
       OR EXISTS (
         SELECT e.balance_id, sum(e.on_hand_delta)
           FROM inventory_entry e
          WHERE e.tenant_id = NEW.tenant_id AND e.transaction_id = v_tx
            AND e.on_hand_delta > 0
          GROUP BY e.balance_id
         EXCEPT
         SELECT d.target_balance_id, sum(d.quantity)
           FROM pending_inspection_disposition d
          WHERE d.tenant_id = NEW.tenant_id AND d.task_id = NEW.id
            AND d.target_balance_id IS NOT NULL
          GROUP BY d.target_balance_id
       ) THEN
      RAISE EXCEPTION '待检处理单 % 的转入分录与处置明细对不上（P3）：'
        '货没有落到它该落的余额行上，或者账上多了一笔没登记的转入',
        NEW.task_code USING ERRCODE='23514';
    END IF;

    -- ---- P4：目标余额行的质量角色必须与处置类型一致。
    FOR rec IN
      SELECT d.id, d.disposition_type, d.target_balance_id
        FROM pending_inspection_disposition d
       WHERE d.tenant_id = NEW.tenant_id AND d.task_id = NEW.id
         AND d.target_balance_id IS NOT NULL
    LOOP
      SELECT q.role INTO v_role
        FROM inventory_balance b
        JOIN inventory_quality_status q
          ON q.tenant_id = b.tenant_id AND q.id = b.quality_status_id
       WHERE b.tenant_id = NEW.tenant_id AND b.id = rec.target_balance_id;
      IF v_role IS NULL THEN
        RAISE EXCEPTION '待检处理单 % 的目标余额行不存在或无权访问（P4）', NEW.task_code
          USING ERRCODE='23503';
      END IF;
      IF rec.disposition_type = 'SELLABLE' AND v_role <> 'SELLABLE' THEN
        RAISE EXCEPTION '待检处理单 % 的转可售明细落到了质量角色「%」的余额行上（P4）：'
          '不合格品不能被转成可售', NEW.task_code, v_role USING ERRCODE='23514';
      END IF;
      IF rec.disposition_type = 'DEFECTIVE' AND v_role <> 'DEFECTIVE' THEN
        RAISE EXCEPTION '待检处理单 % 的转残次明细落到了质量角色「%」的余额行上（P4）',
          NEW.task_code, v_role USING ERRCODE='23514';
      END IF;
    END LOOP;

    -- ---- 来源余额行的角色必须是待检：把可售货当待检处置掉就是错账。
    SELECT q.role INTO v_src_role
      FROM inventory_balance b
      JOIN inventory_quality_status q
        ON q.tenant_id = b.tenant_id AND q.id = b.quality_status_id
     WHERE b.tenant_id = NEW.tenant_id AND b.id = NEW.source_balance_id;
    IF v_src_role <> 'PENDING_INSPECTION' THEN
      RAISE EXCEPTION '待检处理单 % 的来源余额行质量角色是「%」，不是待检（P4）',
        NEW.task_code, v_src_role USING ERRCODE='23514';
    END IF;
  END IF;

  -- 处置完成后不允许再改处置明细（货已经动了，改明细等于改账）。
  IF OLD.status IN ('DISPOSED','CANCELLED') AND TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION '待检处理单已经 %，处置明细不可再改：货一旦处置，分录就跟着入账，改明细等于改账',
      OLD.status USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;
CREATE TRIGGER pending_inspection_task_guard
  BEFORE UPDATE ON pending_inspection_task
  FOR EACH ROW EXECUTE FUNCTION guard_pending_inspection_task();

-- 已处置 / 已取消的任务，其处置明细一律不可再改。
CREATE FUNCTION guard_pending_inspection_disposition_after_close() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_status text;
BEGIN
  SELECT t.status INTO v_status
    FROM pending_inspection_task t
   WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.task_id;
  IF v_status IN ('DISPOSED','CANCELLED') THEN
    RAISE EXCEPTION '待检处理单已经 %，处置明细不可再改：货一旦处置，分录就跟着入账，改明细等于改账',
      v_status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pending_inspection_disposition_after_close_guard
  BEFORE UPDATE OR DELETE ON pending_inspection_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_pending_inspection_disposition_after_close();

-- ===========================================================================
-- 5. 多租户：RLS + 授权（与 V1 第 9 节、V70、V109 同一纪律）
-- ===========================================================================
-- 本迁移**新建**三张表，所以 ENABLE / FORCE / policy / GRANT 都是本表的归属，
-- 不重复写 inventory_balance / stock_bucket / goods_receipt 的策略 —— 归属写错比不写更糟。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['pending_inspection_task','pending_inspection_disposition','pending_inspection_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON pending_inspection_task, pending_inspection_disposition TO warehouse_app;
-- 事件台账 append-only：只给 SELECT/INSERT，UPDATE/DELETE 从权限层就去掉
-- （与 V109 的 putaway_task_scan、V104 的 warehouse_config_change 同一手法）。
GRANT SELECT,INSERT ON pending_inspection_event TO warehouse_app;
REVOKE UPDATE,DELETE ON pending_inspection_event FROM warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建包会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE pending_inspection_task_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V103、V108 同口径）。
REVOKE ALL ON pending_inspection_task, pending_inspection_disposition, pending_inspection_event FROM starcon_app;
