-- ===========================================================================
-- V110 · 8.3.2.1 拣货任务（warehouse 服务）
-- ===========================================================================
--
-- 蓝图 08 卷 8.3.2.1 原文：
--   职责：生成与执行拣货任务：按库位顺序排序优化路径，记录实际拣货库位/批次/序列号/数量，
--         异常（缺货、破损）上报。
--   输入：出库单明细；库存分配结果；作业人员或 PDA 扫码。
--   输出：拣货任务与拣货明细（实际库位/批次/序列号）；缺货异常记录。
--   关系：上游 = 8.3.1.2、7.2.1.2；下游 = 8.3.3.1、7 库存（已拣未发的占用状态）。
--         **拣货完不等于已发货**：拣货只是把货从库位移到打包区，库存仍占用未释放。
--
-- 卷级关键约束（本迁移逐条对应）：
--   1)「拣货完成 ≠ 已发货，两者状态必须分开」→ pick_task.status 与 outbound_order.status
--      是两个独立的列、两套词表。本迁移只把 outbound_order 从 PICKING 推到
--      PENDING_RECHECK（提交复核），**从不**碰 SHIPPED。
--   2)「拣货必须记录实际库位/批次/序列号，不能只记 SKU 与数量」→ pick_task_pick
--      的 location_id / lot_id / serial 三者与 quantity 同表同行，逐次拣货各一行。
--   3)「所有作业动作与库存分录在同一本地事务完成」→ 见下面第 2 节的守恒说明。
--
-- ---------------------------------------------------------------------------
-- 1. 本迁移建什么、不建什么
-- ---------------------------------------------------------------------------
-- 建五张表 + 一张只读留痕：
--   pick_task          拣货任务单头（一行 = 一张出库单的拣货任务）
--   pick_task_line     拣货明细「计划」：路径优化后要走的每一个「库位 × 批次」
--   pick_task_pick     实拣记录：实际从哪个库位/批次拿了多少、序列号是哪几个
--   pick_task_serial   实拣的序列号逐个（序列号是逐件的，不能塞成一段文本）
--   pick_task_exception 缺货 / 破损等异常上报
--   pick_task_event    append-only 作业留痕
--
-- **为什么不往 outbound_order_line 上加实拣列**：V72 §4.3 已把明细行钉成不可改的
-- 计划快照（guard_outbound_order_line_immutable），V79（8.3.1.2）也逐字承诺
-- 「实拣由 8.3.2.1 拥有，不在本表」。往别人的不可改表上加列既改不动也会同时把
-- 两条守卫的语义搅浑。
--
-- **为什么一张出库单只有一张拣货任务**：outbound_pick_request 上有
-- UNIQUE(tenant_id,order_id)（V72 §3 明写「一张出库单只生成一次拣货任务」），
-- 复核返工走 PENDING_RECHECK → PICKING 而不是退回待拣（V72 第三节）。所以
-- 「第二次生成任务」在数据上本来就不成立，这里用同样的 UNIQUE 把它钉住，
-- 而不是留一个能被并发跑出来的第二条路径。返工的场景由
-- guard_pick_task_reopen（COMPLETED 任务可带原因重开）覆盖。
--
-- ---------------------------------------------------------------------------
-- 2. 拣货对库存做了什么、没做什么（本迁移最要紧的一段）
-- ---------------------------------------------------------------------------
-- 拣货把货**从拣货库位搬到打包区**。打包区是同一个仓里的另一个库位
-- （warehouse_location.type='SHIPPING'，V2 §1 的六值词表之一），所以：
--
--   * 源 bucket 与暂存 bucket 属于**同一条 inventory_balance**
--     （balance 的六元组是 sku × warehouse × legal_entity × quality_status，
--      库位与批次都不在 balance 上）。搬动因此**不改变任何 balance 的
--      on_hand / reserved**。
--   * V1 的 inventory_entry 有 CHECK(on_hand_delta<>0 OR reserved_delta<>0)：
--      一条 delta 为 0 的分录写不进去，也**本来就不该写** ——
--      「货从 A 位搬到 B 位」在 balance 粒度上什么都没发生，硬写一条
--      ±q / ∓q 的分录只会把 Σ分录 == 余额变化这条守恒式变成两笔互相抵消的噪音。
--   * V2 §4.4 的 bucket 守恒式（balance = Σ bucket）因此**天然成立**：
--      搬动只改两侧 bucket，同一事务内合计不变。
--   * reserved **跟着货一起搬**（不是留在原地、也不是释放掉）：蓝图关系段写明
--      「库存仍占用未释放」。货被本单预占了，就该跟着货走到打包区，
--      否则「这个库位上的预占是多少」会指向一批已经不在这儿的货。
--
-- 释放预占是下游 7 库存的动作（发货/取消时），不是本模块的。
--
-- 序列号同理：inventory_serial.current_location_id 改到暂存库位，status 保持
-- IN_STOCK（货还在仓里，只是已经不在拣货位上了）。guard_bucket_reservable
-- （V2 §4.1）要求预占只能落在 SELLABLE 质量状态上，本模块只在 SELLABLE 上分配。
--
-- ---------------------------------------------------------------------------
-- 3. 路径优化落到哪几列（8.5.2.1 两条建议的落地归属）
-- ---------------------------------------------------------------------------
-- 8.5.2.1 库位利用率的六类建议里，有两类 ownerModule 写的是「8.3.2.1 拣货路径优化」：
--
--   * HIGH_FREQUENT_NON_PICKABLE「高频货在不可拣库位」
--       → pick_task_line.non_pickable_source：分配时**优先可拣且 ACTIVE 的库位**，
--         只有在没有可拣来源时才退到不可拣库位，并把这一行标出来。
--         静默从一个不可拣库位取货等于让作业员白跑一趟，或者让他在
--         「待检/冻结」位上动手 —— 那是两种完全不同的错误。
--   * SKU_DISPERSION「SKU 散在多库位」
--       → pick_task_line.sku_location_count（真实跨库位计数）+ 分配的收敛策略：
--         同一个 SKU 在多个库位有货时，**优先在同一个库位一次拣齐**，
--         拣不齐才拆到下一个库位，拆出来的每一段各占一行、各占一个序号。
--         这就是「一次拣货要走好几趟」的量化口径。
--
-- 排序本身是「库位层级路径升序 + 同层按库位编码」—— 与 8.1.2.1 库位树的出树顺序
-- （order by path）逐字一致，所以拣货单上看到的行走顺序与仓库现场的层级认知
-- 是同一套；口径原文随响应返回（path_basis），不靠文档口口相传。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. pick_task · 拣货任务单头
-- ---------------------------------------------------------------------------
CREATE TABLE pick_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：PT-<UTC 日期>-<8 位十六进制>，服务端派生，不接受调用方自填。
  task_code varchar(32) NOT NULL,

  -- 交接来源。V72 §3 明写「8.3.2.1 交付后按 (tenant_id, order_id) 读这张表接手」，
  -- 所以两个外键都是**必填的真外键**：没有 outbound_pick_request 就没有拣货任务，
  -- 「凭空生成一张拣货任务」在账上说不通（拣什么？从哪个计划来？）。
  outbound_order_id uuid NOT NULL,
  pick_request_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,

  -- 打包区 / 发货暂存库位。**必填**：拣货是把货搬到打包区，没有暂存位就不知道
  -- 货拣完去哪。复合外键同时钉死「必须是同一个仓的库位」。
  staging_location_id uuid NOT NULL,

  -- 四态。四态刻意不含「已发货」：蓝图关系段点名「拣货完不等于已发货」，
  -- 发货是 outbound_order 的事（8.3.4.1），两套状态不许混在一列里。
  status varchar(20) NOT NULL DEFAULT 'CREATED' CHECK(status IN
    ('CREATED','IN_PROGRESS','COMPLETED','CANCELLED')),

  -- 作业人员 / PDA 接单人。进了 IN_PROGRESS 就必须有 assignee：
  -- 「有人开始拣了却不知道是谁」在 8.5.1.1 作业效率里是无法归因的工时。
  assignee_user_id uuid,

  -- 计划与实拣的合计。**唯一写入者是 refresh_pick_task_totals 触发器**，
  -- 服务层只读回来，不自己算（服务层算的数与账上的数可能差一位）。
  planned_line_count integer NOT NULL DEFAULT 0 CHECK(planned_line_count>=0),
  planned_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(planned_quantity>=0),
  picked_line_count integer NOT NULL DEFAULT 0 CHECK(picked_line_count>=0),
  picked_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(picked_quantity>=0),
  exception_count integer NOT NULL DEFAULT 0 CHECK(exception_count>=0),

  -- 路径口径原文，随响应返回：排序依据不是「按编码排」这种实现细节，
  -- 看拣货单的人有权知道顺序是怎么来的。
  path_basis varchar(300) NOT NULL,

  -- 时刻与状态同进同出（与 V62 / V72 同一纪律：「谁做的」缺一半就是有一半动作没人负责）。
  started_at timestamptz,
  completed_at timestamptz,
  cancelled_at timestamptz,
  -- 重开原因必填：复核返工（8.3.3.1 退回）是最需要事后解释的一步，
  -- 而它是唯一一条会让已完成任务的实拣数继续增长的边。
  reopen_count integer NOT NULL DEFAULT 0 CHECK(reopen_count>=0),
  reopen_reason varchar(300),
  cancel_reason varchar(300),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_code),
  -- 与 outbound_pick_request 的 UNIQUE(tenant_id,order_id) 同一条纪律（见文件头第 1 节）。
  UNIQUE(tenant_id,outbound_order_id),

  FOREIGN KEY(tenant_id,outbound_order_id) REFERENCES outbound_order(tenant_id,id),
  FOREIGN KEY(tenant_id,pick_request_id) REFERENCES outbound_pick_request(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id,staging_location_id)
    REFERENCES warehouse_location(tenant_id,warehouse_id,id),

  CHECK(btrim(task_code)<>''), CHECK(btrim(path_basis)<>''),

  -- 正在干活就必须有接单人与开始时刻。
  CHECK(status NOT IN ('IN_PROGRESS','COMPLETED')
     OR (assignee_user_id IS NOT NULL AND started_at IS NOT NULL)),
  CHECK((status='COMPLETED') = (completed_at IS NOT NULL)),
  CHECK((status='CANCELLED')  = (cancelled_at IS NOT NULL)),
  -- 作废必须写原因（与 V72 的 cancel_reason 同一纪律）。
  CHECK(status<>'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason)<>'')),
  -- 重开过一次就必须留原因。
  CHECK(reopen_count=0 OR (reopen_reason IS NOT NULL AND btrim(reopen_reason)<>''))
);

-- 作业台默认视图：待干活的任务（已生成未完成），按创建时间倒序。
CREATE INDEX pick_task_open
  ON pick_task(tenant_id,status,created_at DESC,id);
-- 「这个仓今天要拣哪些」（仓库筛选，最高频）。
CREATE INDEX pick_task_warehouse
  ON pick_task(tenant_id,warehouse_id,created_at DESC,id);
-- 「某个人今天拣了哪些」（接单人筛选，8.5.1.1 人效页的取数口径）。
CREATE INDEX pick_task_assignee
  ON pick_task(tenant_id,assignee_user_id,created_at DESC,id)
  WHERE assignee_user_id IS NOT NULL;
-- 关键字搜索（单号 / 出库单号）。
CREATE INDEX pick_task_code
  ON pick_task(tenant_id,task_code);

-- ---------------------------------------------------------------------------
-- 2. pick_task_line · 拣货明细（路径优化后的「库位 × 批次」计划段）
-- ---------------------------------------------------------------------------
CREATE TABLE pick_task_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- 路径序号：1 起，按库位层级路径排序后连续。**拣货单照它打印，
  -- 作业员照它走** —— 这是「按库位顺序排序优化路径」的落点。
  sequence_no integer NOT NULL CHECK(sequence_no>0),

  -- 对应的出库单明细行。一个出库单行可能被拆成多段（见下）。
  outbound_line_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- 可读名冻结（COPY 语义）：SKU 之后可能被改名或停用，而这张拣货单要能回看
  -- 「当时要拣的是哪一件」。与 outbound_order_line 同一口径。
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL,
  quality_status_id uuid NOT NULL,

  -- 分配到的**取货**库位与批次。lot_id 可空（非批次管理品）。
  -- 路径顺序按库位算，同库位内按批次算（先到期先出，与 FEFO 常识一致）。
  source_location_id uuid NOT NULL,
  source_lot_id uuid,

  -- 分配当时算出的库位层级路径：快照。库位搬了家之后这张单上的顺序仍然可解释，
  -- 而「为什么当时排在这个位置」事后是算不出来的（与 8.1.2.2 location_allocation
  -- 只冻结「决策当时的理由」、永远读当前事实同一纪律）。
  location_path varchar(600) NOT NULL,

  -- 分配时该库位该批次的手数快照。
  --
  -- **刻意是 on_hand 而不是 available（= on_hand − reserved）**：拣货要取的正是
  -- 「已经为这张单预占住的那批货」。按 available 分配的话，只要销售预占生效，
  -- 每一张出库单都变成「一库位可拣货都没有」—— 预占越成功，拣货越拣不动。
  -- 预占不释放这件事由「reserved 跟着货一起搬」表达，而不是靠少分配一点货来暗示。
  on_hand_at_source numeric(19,6) NOT NULL CHECK(on_hand_at_source>=0),

  -- 该 SKU 在本仓**可拣且 ACTIVE** 的库位上散在几处（真实跨库位计数，不是估算文案）。
  -- 这是 8.5.2.1「SKU 散在多库位」那条建议的同一口径：>1 就意味着一次拣货要多走。
  sku_location_count integer NOT NULL CHECK(sku_location_count>0),

  -- 只能退到不可拣库位取货时为 true（见文件头第 3 节）。
  non_pickable_source boolean NOT NULL DEFAULT false,

  -- 本段要拣多少。**计划量**，实拣在 pick_task_pick。
  planned_quantity numeric(19,6) NOT NULL CHECK(planned_quantity>0),
  -- 实拣累计。多次拣（返工重开）会累加，不覆盖 —— 覆盖等于抹掉第一次的事实。
  picked_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(picked_quantity>=0),

  line_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(line_status IN
    ('PENDING','PICKED','SHORT')),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_id,sequence_no),
  -- 同一个出库单行在同一库位同一批次只能有一段（NULLS NOT DISTINCT 才能把
  -- 「无批次」也纳入唯一性 —— 与 V2 的 stock_bucket 同一手法）。
  UNIQUE NULLS NOT DISTINCT(tenant_id,task_id,outbound_line_id,source_location_id,source_lot_id),

  FOREIGN KEY(tenant_id,task_id) REFERENCES pick_task(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_line_id) REFERENCES outbound_order_line(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,source_location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,source_lot_id) REFERENCES inventory_lot(tenant_id,id),

  CHECK(btrim(sku_code)<>'' AND btrim(sku_name)<>''),
  CHECK(btrim(location_path)<>''),
  -- 实拣不能超过计划（超拣是另一件事：多拣的那一件会变成多发或损耗）。
  CHECK(picked_quantity<=planned_quantity),
  -- 状态与数量必须配套：SHORT 一定没拣齐，PICKED 一定拣齐了。
  CHECK((line_status='PICKED') = (picked_quantity=planned_quantity))
);

-- 拣货单按序号打印：「这张单要按什么顺序走」。
CREATE INDEX pick_task_line_task_sequence
  ON pick_task_line(tenant_id,task_id,sequence_no);
-- 「这个 SKU 一共排了几段」（排查散在多库位）。
CREATE INDEX pick_task_line_sku
  ON pick_task_line(tenant_id,task_id,sku_id,sequence_no);
-- 不可拣来源的行：作业台要能把它们顶到最前面提醒作业员。
CREATE INDEX pick_task_line_non_pickable
  ON pick_task_line(tenant_id,task_id)
  WHERE non_pickable_source;

-- ---------------------------------------------------------------------------
-- 3. pick_task_pick · 实拣记录（实际库位 / 批次 / 序列号 / 数量）
-- ---------------------------------------------------------------------------
-- 蓝图关键约束：「拣货必须记录实际库位/批次/序列号，不能只记 SKU 与数量」。
-- 所以实拣**逐次**一行，且 location_id 允许与计划不一致 —— 分配是建议，
-- 现场才是事实（真出现「计划的那个库位已经空了」时，作业员必须能在别处取到并
-- 把真实库位记下来，而不是被系统逼着报一个缺货）。
CREATE TABLE pick_task_pick (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  line_id uuid NOT NULL,

  -- 实际取货库位与批次（可以与计划的 source_* 不同，见上）。
  location_id uuid NOT NULL,
  lot_id uuid,

  -- 本次实拣数量。NUMERIC(19,6) + BigDecimal，禁浮点。
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),

  -- 序列号逐个记录在 pick_task_serial；这里存条数以便「数量与序列号条数对不对」
  -- 这件事能被一条 CHECK 当场比对（V58 的 inventory_serial_operation 同一手法）。
  serial_count integer NOT NULL DEFAULT 0 CHECK(serial_count>=0),

  picked_by uuid NOT NULL,
  -- 业务时间：由作业端传入（PDA 时钟与库内作业时刻），不是 DB 的 now()。
  picked_at timestamptz NOT NULL,

  -- 备注是**可选**的：空串是「没写备注」，不是非法值。这里刻意不给 CHECK
  -- （pick_task_exception.detail 是必填的那一列，它的 CHECK 才留）。
  note varchar(300) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES pick_task_line(tenant_id,id),
  FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id)
);
-- 「这张任务拣了些什么」，按时间读。
CREATE INDEX pick_task_pick_task
  ON pick_task_pick(tenant_id,task_id,picked_at,id);
-- 逐行反查（复核 8.3.3.1 要按行逐件核对）。
CREATE INDEX pick_task_pick_line
  ON pick_task_pick(tenant_id,line_id,picked_at,id);

-- ---------------------------------------------------------------------------
-- 4. pick_task_serial · 实拣的序列号逐个
-- ---------------------------------------------------------------------------
-- 为什么不用 text[] 或一列 CSV：序列号是**逐件核对**的对象（8.3.3.1
-- 「序列号商品必须逐件核对」），要能反查「这个号是在哪次拣货、从哪个库位被拿走的」。
CREATE TABLE pick_task_serial (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  pick_id uuid NOT NULL,
  line_id uuid NOT NULL,
  serial_id uuid NOT NULL,

  picked_by uuid NOT NULL,
  picked_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个序列号在一张拣货任务里只能出现一次：同一个号被拣两次一定有一边是错的。
  UNIQUE(tenant_id,task_id,serial_id),
  FOREIGN KEY(tenant_id,pick_id) REFERENCES pick_task_pick(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES pick_task_line(tenant_id,id),
  FOREIGN KEY(tenant_id,serial_id) REFERENCES inventory_serial(tenant_id,id)
);
-- 「这个号是哪次拣货拿走的」反查。
CREATE INDEX pick_task_serial_serial
  ON pick_task_serial(tenant_id,serial_id);

-- ---------------------------------------------------------------------------
-- 5. pick_task_exception · 异常上报（缺货 / 破损）
-- ---------------------------------------------------------------------------
CREATE TABLE pick_task_exception (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  line_id uuid,

  -- 五类。前两类是蓝图点名的那两个（缺货、破损），后三类是现场真实存在的
  -- 「拣不出来的原因」—— 每多一类都要人写清单报出来，所以刻意收窄。
  kind varchar(20) NOT NULL CHECK(kind IN
    ('SHORTAGE','DAMAGED','MISSING_SERIAL','WRONG_LOCATION','OTHER')),

  -- 缺货数量：只有 SHORTAGE 必填（其余三类报的是「这件不能按原样发」，
  -- 数量由拣货任务的实拣与计划之差体现，重复记两遍会两个数对不上）。
  shortage_quantity numeric(19,6),
  -- 异常发生时的库位：报「这个库位的货是坏的」时需要它。
  location_id uuid,

  detail varchar(500) NOT NULL,
  reported_by uuid NOT NULL,
  reported_at timestamptz NOT NULL,

  -- 处理结论（人写的，不是系统推的）：换货 / 拆单 / 报损 / 忽略。
  resolution varchar(300),
  -- 处理说明：与结论分开存，因为结论是个枚举词（「换货」）而说明是这个单子
  -- 上唯一能解释「后来到底怎么解决的」的那句话。
  resolve_note varchar(300),
  resolved_by uuid,
  resolved_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES pick_task(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES pick_task_line(tenant_id,id),
  FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_location(tenant_id,id),

  CHECK(btrim(detail)<>''),
  -- 缺货必须给数量：报一个「缺货」而不写缺几件，仓库只知道要补货不知道要补多少。
  CHECK(kind<>'SHORTAGE'
     OR (shortage_quantity IS NOT NULL AND shortage_quantity>0)),
  CHECK(kind='SHORTAGE' OR shortage_quantity IS NULL),
  -- 处理与处理人同进同出。
  CHECK((resolved_at IS NULL) = (resolved_by IS NULL)),
  CHECK(resolved_at IS NULL OR (resolution IS NOT NULL AND btrim(resolution)<>''))
);
CREATE INDEX pick_task_exception_task
  ON pick_task_exception(tenant_id,task_id,reported_at,id);
-- 未处理的异常顶到作业台最前面。
CREATE INDEX pick_task_exception_open
  ON pick_task_exception(tenant_id,task_id,reported_at)
  WHERE resolved_at IS NULL;

-- ---------------------------------------------------------------------------
-- 6. pick_task_event · 作业留痕（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE pick_task_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN
    ('GENERATED','ASSIGNED','PICKED','EXCEPTION_REPORTED','REOPENED',
     'COMPLETED','CANCELLED')),
  from_status varchar(20),
  to_status varchar(20),
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  -- 人读得懂的一句话：半年后只看这一行也要知道当时发生了什么。
  detail text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES pick_task(tenant_id,id),
  -- 有起始就必须有落点：只填一半的话，读时间线的人无法判断这是一次跃迁还是一次普通操作。
  CHECK(from_status IS NULL OR to_status IS NOT NULL),
  CHECK(btrim(detail)<>'')
);
CREATE INDEX pick_task_event_task
  ON pick_task_event(tenant_id,task_id,occurred_at,id);

-- ===========================================================================
-- 7. DB 级不变量（绕过服务层直连 SQL 也照样拒）
-- ===========================================================================

-- 7.1 终态后拣货明细不可改：COMPLETED 的拣货任务已经是交给复核的凭据
--     （8.3.3.1 要按它逐件核对），事后改明细等于改证据。
CREATE FUNCTION guard_pick_task_line_terminal_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_task uuid; v_status text;
BEGIN
  v_task := CASE WHEN TG_OP='DELETE' THEN OLD.task_id ELSE NEW.task_id END;
  SELECT status INTO v_status FROM pick_task WHERE id = v_task;
  IF v_status IS NOT NULL AND v_status IN ('COMPLETED','CANCELLED') THEN
    RAISE EXCEPTION '拣货任务已 %，拣货明细不可再改：它已经是交给复核（8.3.3.1）的凭据，'
      '要重拣请走「重开」并写原因。', v_status USING ERRCODE='23514';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_task_line_terminal_guard
  BEFORE INSERT OR UPDATE OR DELETE ON pick_task_line
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_line_terminal_fn();

-- 7.2 取货库位必须在任务的仓里 ——「从 A 仓的货拣到 B 仓的打包区」在物理上不成立。
CREATE FUNCTION guard_pick_task_line_placement_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_warehouse uuid; v_src_warehouse uuid;
BEGIN
  SELECT warehouse_id INTO v_warehouse FROM pick_task WHERE id = NEW.task_id;
  IF v_warehouse IS NULL THEN
    RAISE EXCEPTION '拣货任务 % 不存在。', NEW.task_id USING ERRCODE='23503';
  END IF;
  SELECT warehouse_id INTO v_src_warehouse FROM warehouse_location WHERE id = NEW.source_location_id;
  IF v_src_warehouse IS DISTINCT FROM v_warehouse THEN
    RAISE EXCEPTION '取货库位不属于本任务的仓库：拣货只能在同一个仓里进行。'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_task_line_placement_guard
  BEFORE INSERT OR UPDATE OF task_id, source_location_id ON pick_task_line
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_line_placement_fn();

-- 打包区类型守卫挂在单头上：改 staging_location_id 不能指到一个普通存储位上去。
CREATE FUNCTION guard_pick_task_staging_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_type varchar(16); v_status varchar(16);
BEGIN
  IF NEW.staging_location_id IS NOT DISTINCT FROM OLD.staging_location_id THEN
    RETURN NEW;
  END IF;
  SELECT type, status INTO v_type, v_status FROM warehouse_location
    WHERE id = NEW.staging_location_id;
  IF v_type IS NULL THEN
    RAISE EXCEPTION '打包区库位 % 不存在。', NEW.staging_location_id USING ERRCODE='23503';
  END IF;
  IF v_type <> 'SHIPPING' THEN
    RAISE EXCEPTION '拣货的打包区必须是发货暂存库位（type=SHIPPING），当前是 %。'
      '拣完的货要有一个明确的去处，搬到普通存储位等于没搬。', v_type
      USING ERRCODE='23514';
  END IF;
  IF v_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '打包区库位已停用（status=%）。', v_status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_task_staging_guard
  BEFORE INSERT OR UPDATE OF staging_location_id ON pick_task
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_staging_fn();

-- 7.3 实拣只能在任务进行中发生，且一次不能拣超过本段剩余量。
--     「超拣」是另一件事（多发或损耗），系统不替作业员做那个决定。
CREATE FUNCTION guard_pick_task_pick_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status varchar(20); v_planned numeric(19,6); v_picked numeric(19,6);
        v_line_task uuid; v_location_warehouse uuid; v_task_warehouse uuid;
BEGIN
  SELECT task_id, planned_quantity, picked_quantity INTO v_line_task, v_planned, v_picked
    FROM pick_task_line WHERE id = NEW.line_id;
  IF v_line_task IS NULL THEN
    RAISE EXCEPTION '拣货明细 % 不存在。', NEW.line_id USING ERRCODE='23503';
  END IF;
  IF v_line_task <> NEW.task_id THEN
    RAISE EXCEPTION '实拣记录的任务与明细行不一致：明细行属于任务 %。', v_line_task
      USING ERRCODE='23514';
  END IF;
  SELECT status INTO v_status FROM pick_task WHERE id = NEW.task_id;
  IF v_status <> 'IN_PROGRESS' THEN
    RAISE EXCEPTION '拣货任务当前状态为 %，只有在「拣货中」才能登记实拣。', v_status
      USING ERRCODE='23514';
  END IF;
  IF v_picked + NEW.quantity > v_planned THEN
    RAISE EXCEPTION '超拣：本段计划 %，已拣 %，本次再拣 % 就超了。'
      '超拣要么会变成多发、要么会变成损耗，两件都不是本动作该顺手做的事。',
      v_planned, v_picked, NEW.quantity USING ERRCODE='23514';
  END IF;
  -- 取货库位必须与任务同一个仓（跨仓搬货是调拨 7.5.1.1 的事，不是拣货）。
  SELECT l.warehouse_id INTO v_location_warehouse FROM warehouse_location l
    WHERE l.id = NEW.location_id;
  SELECT warehouse_id INTO v_task_warehouse FROM pick_task WHERE id = NEW.task_id;
  IF v_location_warehouse IS DISTINCT FROM v_task_warehouse THEN
    RAISE EXCEPTION '实拣库位不属于本任务的仓库。' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_task_pick_guard
  BEFORE INSERT ON pick_task_pick
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_pick_fn();

-- 7.4 序列号三校验：在库、SKU 对得上、**确实在这次实拣的那个库位**。
--     最后一条是「错拣」的最后一道闸：号 A 挂在 3 号位，你却在 7 号位把它记成
--     拣走了 —— 那是在给复核（8.3.3.1）喂一条假记录。
CREATE FUNCTION guard_pick_task_serial_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status varchar(20); v_sku uuid; v_loc uuid; v_pick_location uuid;
        v_pick_task uuid; v_line_sku uuid;
BEGIN
  SELECT task_id, location_id INTO v_pick_task, v_pick_location
    FROM pick_task_pick WHERE id = NEW.pick_id;
  IF v_pick_task IS NULL THEN
    RAISE EXCEPTION '实拣记录 % 不存在。', NEW.pick_id USING ERRCODE='23503';
  END IF;
  IF v_pick_task <> NEW.task_id THEN
    RAISE EXCEPTION '序列号记录的任务与实拣记录不一致。' USING ERRCODE='23514';
  END IF;
  SELECT sku_id INTO v_line_sku FROM pick_task_line WHERE id = NEW.line_id;
  SELECT status, sku_id, current_location_id INTO v_status, v_sku, v_loc
    FROM inventory_serial WHERE id = NEW.serial_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '序列号 % 不存在。', NEW.serial_id USING ERRCODE='23503';
  END IF;
  IF v_status <> 'IN_STOCK' THEN
    RAISE EXCEPTION '序列号当前状态为 %，只有「在库」的号能被拣走。', v_status
      USING ERRCODE='23514';
  END IF;
  IF v_sku <> v_line_sku THEN
    RAISE EXCEPTION '序列号绑定的 SKU 与拣货行不符：序列号与 SKU 的绑定是永久的，'
      '不能拣成别的型号。' USING ERRCODE='23514';
  END IF;
  IF v_loc IS DISTINCT FROM v_pick_location THEN
    RAISE EXCEPTION '序列号不在本次实拣的库位上（号在库位 %，本次从 % 拣）：'
      '这会把一个没被拿走的号记成被拿走了。', v_loc, v_pick_location
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_task_serial_guard
  BEFORE INSERT ON pick_task_serial
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_serial_fn();

-- 7.5 异常只能在任务未终态时上报；处理结论只能写一次。
CREATE FUNCTION guard_pick_task_exception_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status varchar(20);
BEGIN
  IF TG_OP='UPDATE' THEN
    IF NEW.resolved_at IS DISTINCT FROM OLD.resolved_at AND OLD.resolved_at IS NOT NULL THEN
      RAISE EXCEPTION '异常 % 已经处理过（%），处理结论不可再改：异常处置是审计对象。',
        OLD.id, OLD.resolved_at USING ERRCODE='23514';
    END IF;
    IF NEW.kind IS DISTINCT FROM OLD.kind
       OR NEW.line_id IS DISTINCT FROM OLD.line_id
       OR NEW.location_id IS DISTINCT FROM OLD.location_id
       OR NEW.shortage_quantity IS DISTINCT FROM OLD.shortage_quantity
       OR NEW.detail IS DISTINCT FROM OLD.detail THEN
      RAISE EXCEPTION '异常 % 的事实部分不可改：要补充说明请另报一条。', OLD.id
        USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;
  SELECT status INTO v_status FROM pick_task WHERE id = NEW.task_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '拣货任务 % 不存在。', NEW.task_id USING ERRCODE='23503';
  END IF;
  IF v_status IN ('COMPLETED','CANCELLED') THEN
    RAISE EXCEPTION '拣货任务已 %，不能再上报异常：它已经交给下游了。', v_status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_task_exception_guard
  BEFORE INSERT OR UPDATE ON pick_task_exception
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_exception_fn();

-- 7.6 实拣记录与序列号只增不改（append-only）：拣过就是事实，
--     更正靠「重开 + 补拣」，不靠抹掉一次错记。
CREATE FUNCTION guard_pick_task_pick_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'pick_task_pick / pick_task_serial 是事实记录，不允许 %：'
    '更正靠「重开 + 补拣」，不靠抹掉一次错记。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER pick_task_pick_append_only
  BEFORE UPDATE OR DELETE ON pick_task_pick
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_pick_append_only();
CREATE TRIGGER pick_task_serial_append_only
  BEFORE UPDATE OR DELETE ON pick_task_serial
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_pick_append_only();

-- 7.7 留痕表 append-only。
CREATE FUNCTION guard_pick_task_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'pick_task_event 是 append-only：不允许 %，'
    '拣货回溯依赖未被改写的时间线。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER pick_task_event_append_only
  BEFORE UPDATE OR DELETE ON pick_task_event
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_event_append_only();

-- 7.6b 行状态由数量**派生**：BEFORE 触发器改写 NEW.line_status，
--      所以调用方只写 picked_quantity 就够了 —— 写一个「拣齐了却还是 PENDING」
--      的状态在数据上根本不成立（上面的 CHECK 也不允许）。
--      为什么不给服务层自己写 line_status 的权力：那是同一件事的第二个定义，
--      两个定义迟早会对不上，而「拣齐了没有」是拣货作业唯一要判的结论。
CREATE FUNCTION set_pick_task_line_status_fn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.line_status := CASE
    WHEN NEW.picked_quantity = NEW.planned_quantity THEN 'PICKED'
    WHEN NEW.picked_quantity > 0 THEN 'SHORT'
    ELSE 'PENDING' END;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_task_line_status_derive
  BEFORE INSERT OR UPDATE OF planned_quantity, picked_quantity, line_status ON pick_task_line
  FOR EACH ROW EXECUTE FUNCTION set_pick_task_line_status_fn();

-- 7.8 合计的**唯一写入者**：从明细表重算并回写。
--     应用层自己算的话，服务层算的数与账上的数会差一位，而这种差一位
--     在「拣齐了没有」这个判断上等于错判。
CREATE FUNCTION refresh_pick_task_totals_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_task uuid; t record;
BEGIN
  v_task := CASE WHEN TG_OP='DELETE' THEN OLD.task_id ELSE NEW.task_id END;
  SELECT count(*) AS lines, coalesce(sum(planned_quantity),0) AS planned,
         coalesce(sum(picked_quantity),0) AS picked,
         count(*) FILTER (WHERE line_status='PICKED') AS picked_lines
    INTO t FROM pick_task_line WHERE task_id = v_task;

  UPDATE pick_task p
     SET planned_line_count = t.lines,
         planned_quantity  = t.planned,
         picked_quantity   = t.picked,
         picked_line_count = t.picked_lines,
         exception_count   = (SELECT count(*) FROM pick_task_exception WHERE task_id = v_task),
         updated_at = now()
   WHERE p.id = v_task;
  RETURN NULL;
END $$;
-- 三张明细表各挂一个行级 AFTER 触发器：每次变动都把单头合计刷成真相。
-- 之所以不用「服务层回写合计」：那样合计就成了可被任何一条 UPDATE 写歪的列。
CREATE TRIGGER pick_task_line_totals_refresh
  AFTER INSERT OR UPDATE OR DELETE ON pick_task_line
  FOR EACH ROW EXECUTE FUNCTION refresh_pick_task_totals_fn();
CREATE TRIGGER pick_task_pick_totals_refresh
  AFTER INSERT ON pick_task_pick
  FOR EACH ROW EXECUTE FUNCTION refresh_pick_task_totals_fn();
CREATE TRIGGER pick_task_exception_totals_refresh
  AFTER INSERT ON pick_task_exception
  FOR EACH ROW EXECUTE FUNCTION refresh_pick_task_totals_fn();

-- 7.9 状态机：四条边，与服务层的校验矩阵一一对应。
--     COMPLETED → IN_PROGRESS 那一条例外（复核返工重开）**必须带原因**：
--     它是唯一一条会让已完成任务的实拣数继续增长的边，也是事后最需要解释的一步。
CREATE FUNCTION guard_pick_task_status_fn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status='CREATED'      AND NEW.status IN ('IN_PROGRESS','CANCELLED')) OR
      (OLD.status='IN_PROGRESS'  AND NEW.status IN ('COMPLETED','CANCELLED')) OR
      (OLD.status='COMPLETED'    AND NEW.status='IN_PROGRESS') OR
      (OLD.status='CANCELLED'    AND NEW.status='IN_PROGRESS')
    ) THEN
      RAISE EXCEPTION '拣货任务 % 状态非法跃迁：% → %。合法跃迁：待拣→拣货中→已完成，'
        '或任一未完态→已取消；已完成只能带原因重开。',
        OLD.task_code, OLD.status, NEW.status USING ERRCODE='23514';
    END IF;
    IF OLD.status IN ('COMPLETED','CANCELLED') AND NEW.status='IN_PROGRESS'
       AND (NEW.reopen_reason IS NULL OR btrim(NEW.reopen_reason)='') THEN
      RAISE EXCEPTION '拣货任务 % 处于终态，重开必须写原因（reopen_reason）。', OLD.task_code
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_task_status_guard
  BEFORE UPDATE ON pick_task
  FOR EACH ROW EXECUTE FUNCTION guard_pick_task_status_fn();

-- ===========================================================================
-- 8. 多租户与授权（与 V1 第 9 节、V72 第 6 节、V108 第 5 节同一条纪律）
-- ===========================================================================
-- 六张表都是租户业务表，一视同仁 ENABLE + FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它；FORCE 保证连表 owner 也绕不过。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['pick_task','pick_task_line','pick_task_pick',
                             'pick_task_serial','pick_task_exception','pick_task_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  pick_task, pick_task_line, pick_task_pick,
  pick_task_serial, pick_task_exception, pick_task_event TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V90、V91、V103、V108 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;