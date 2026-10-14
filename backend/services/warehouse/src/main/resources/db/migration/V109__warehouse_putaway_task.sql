-- ===========================================================================
-- V109 8.2.2.1 上架任务（收货暂存位 → 正式库位）
--
-- 蓝图 08 卷 8.2.2.1：
--   职责：把已收货物从收货暂存位上架到正式库位，生成上架任务派发给作业人员（支持 PDA/扫码）。
--   输入：待上架清单；库位推荐或指定库位；作业人员分配。
--   输出：上架任务（待执行/执行中/完成）；库存 bucket 位置变更。
--   关系：上游 = 8.2.1.1、8.1.2.2；下游 = 8.3.2.1 拣货。
--         **上架只改位置不改数量**，数量在收货时已确定。
--
-- ---------------------------------------------------------------------------
-- 一、蓝图那句加粗是本模块的**全部**技术内容：「上架只改位置不改数量」
--
-- 这句话决定了本模块与 8.2.1.1（收货，库存增加）、8.4.2.1（拆解，一减一增但总量不变）
-- **在账上的形状完全不同**，所以它不是一句提醒，而是一条可以被数据库裁决的不变量：
--
--   收货：1 条正向分录，Σ on_hand_delta > 0，balance.on_hand 增加。
--   拆解：1 条负向 + M 条正向，Σ on_hand_delta = 0，但**跨 SKU**，balance 各自变动。
--   上架：**同一个 balance 行上**一条负向 + 一条正向，Σ on_hand_delta = 0，
--         且 balance.on_hand **一个数都没动**，变的只是 stock_bucket.location 上的分布。
--
-- 也就是说：上架动的是 **bucket**（库位 × 批次粒度），不是 **balance**（仓库 × 货权 × 质量状态）。
-- 这一点是数据模型分层（V2 文件头）的直接推论 —— balance 的键**刻意不含 location_id**，
-- 所以「同一批货从 A 库位挪到 B 库位」在 balance 上根本不是一个可表达的事件，
-- 它只能体现为两条落在同一 balance 上的反向分录。两者的代数和必须**恰好为 0**。
--
-- 落在下面第 5 节的 guard_putaway_task()：任务转 COMPLETED 时回读
-- inventory_entry，核对 Σ on_hand_delta = 0（P1）与 Σ 负向分录绝对值 = 任务明细实上架量（P3）。
-- 绕过服务层直连 SQL 写一个「上架」也绕不过 —— 这正是这句加粗的落点。
--
-- ---------------------------------------------------------------------------
-- 二、为什么 P1 要在「转 COMPLETED 那一刻」核对，而不是每次扫码都核对
--
-- 上架支持 PDA 逐行扫码：一张任务单可以扫三次才搬完。所以分录是**逐次追加**的，
-- 中途 Σ on_hand_delta 恒不为 0（第一次扫码只有 -Q，货在「路上」）。
-- 这是对的状态 —— 与 8.5.1.1 作业事件的思路一致：作业进行中的中间态必须允许存在。
--
-- 判据放在 COMPLETED：那才是「这张任务单搬完了」这个断言成立的时刻，
-- 也是下游 8.3.2.1 拣货开始按新库位取货的时刻。**在单据说「完成了」而账上还差一正一负，
-- 那才是真的错账**，而这个时刻正是守卫触发器回读分录的时刻。
--
-- 取消（CANCELLED）**不**核对 P1：取消的任务一条分录都不该产生（货还在收货暂存位，
-- 从没动过），如果取消时账上有分录，那是更严重的错，由下面的「终态后禁止改明细」拦住。
--
-- ---------------------------------------------------------------------------
-- 三、与 8.1.2.2 库位自动分配（V106）的关系：只借规则，不重复实现推荐
--
-- 8.1.2.2 的输出是「推荐库位列表（带推荐理由）」，**推荐本身是纯计算不落库**
-- （V106 文件头第二节：落库的只有人选定的那一个 location_allocation 行）。
-- 8.2.2.1 消费的是**已选定/已释放**的那一条决策（status = RELEASED），
-- 所以本模块：
--
--   * **不重写推荐算法**，也不在本表复制一份 recommendation 结果；需要推荐时调用方
--     先打 8.1.2.2 的 /recommend，人选定后再建本模块的任务。理由快照留在 location_allocation 上。
--   * **复用 V106 的权威规则函数** location_allocation_required_type(role)
--     判「质量状态 → 目标库位类型」，而不是在本文件里抄一份 CASE。
--     抄一份就等于将来改一处忘一处，而残次品混放是蓝图点名的硬规则。
--   * 8.1.2.2 那条「一行收货明细同时只能有一个未取消的上架决策」的部分唯一索引**继续生效**，
--     本模块不去动它；本模块自己另有一条同形状的不变量（第 4 节），
--     因为两张表的生命周期不同：决策可能 RELEASED 了还没建任务，也可能还没 RELEASED 就被手工指派。
--
-- ---------------------------------------------------------------------------
-- 四、与 8.2.1.1 收货（V70）的关系：**外键是真的**（同服务）
--
-- goods_receipt / goods_receipt_line 是本服务的表，8.4.2.1 刻意不建外键是因为
-- 「上游必须已完成」外键表达不了。本模块不同：上架任务的每一行**必须**来自一张真实的收货明细行，
-- 这用**复合外键**直接钉死，不给「凭空造一个上架清单」留后门 ——
-- 蓝图说「数量在收货时已确定」，而那条数量的来源就是 goods_receipt_line。
--
-- 仍然**刻意不建**外键的两处，各有理由：
--   * location_allocation_id：8.1.2.2 的决策可能被撤销（V106 允许 CANCELLED 后重新指派），
--     而已经建好的上架任务不该因为上游决策撤销而失效。引用 + 服务层校验。
--   * source_bucket_id / target_bucket_id：stock_bucket 的事实行**永不可删**（V2 guard_bucket_no_delete），
--     但可以归零（on_hand=0）。上架完成后目标 bucket 就是当时新建的那一行，
--     引用它是留痕，不是外键该管的事。
--
-- ---------------------------------------------------------------------------
-- 五、三条核心不变量（回读真正的账本分录，不是单据上的自报数字）
--
--   P1：状态 COMPLETED 时，该任务那条 inventory_transaction 的**全部** inventory_entry
--       的 on_hand_delta 之和 **= 0**。这是「上架只改位置不改数量」的字面形态。
--   P2：状态 COMPLETED 时，每一行的 putaway_quantity **恰好等于** planned_quantity
--       （半张单搬到一半就说「完成」，货去哪了说不清）。
--   P3：状态 COMPLETED 时，账上**负向分录绝对值之和** = 明细实上架量之和
--       （单头写着搬了 100，账上只减了 90 加了 90，P1 照样成立 —— 所以必须独立核对 P3）。
--
-- 三层强制，缺一不可：
--   1) 行级 CHECK：0 ≤ putaway_quantity ≤ planned_quantity，且必须能被库存刻度精确表示。
--   2) refresh_putaway_task_totals()：明细 SUM 回写单头合计列，**单头三个合计列只有这一个写入者**。
--   3) guard_putaway_task()：BEFORE UPDATE 到 COMPLETED 时回读 inventory_entry 核对 P1/P3，
--      并逐行核对 P2 —— 绕过服务层直连 SQL 也绕不过。
--
-- ---------------------------------------------------------------------------
-- 六、数量与时间口径（与 V1/V2/V108 完全一致，不另立一套）
--
--   数量 NUMERIC(19,6) + Java BigDecimal，全程禁浮点。stock_bucket.on_hand 是 NUMERIC(19,4)，
--   所以 planned_quantity / putaway_quantity 必须**能被 4 位小数精确表示**：
--   行级 CHECK 用 round(x,4)=x 钉死，服务层显式拒绝。向下取整 = 凭空吞货，
--   向上取整 = 凭空多搬 —— 两种都会让 P2「看起来成立」而物料对不上。
--
--   时间一律 timestamptz，Java 侧绑 OffsetDateTime，SQL values 位置写 ?::timestamptz。
--   特别注意：scanned_at 是**业务时间**（作业员扫的那一下），不是系统时间 ——
--   事后补录的扫码必须能填真实时刻，否则「这批货几点上的架」永远答不出来。
--
-- ---------------------------------------------------------------------------
-- 七、多租户：tenant_id NOT NULL + FORCE RLS（与 V1 第 9 节、V106 第 5 节同一纪律）
-- 本迁移**新建**三张表，不 ALTER 任何既有表，跨车道 DDL 回归面 = 0。
-- 复合外键一次钉死三条红线：跨租户引用、跨仓库挂库位、引用不存在的 id，全部 DB 拒（23503）。

-- ===========================================================================
-- 1. 上架任务单头
-- ===========================================================================
CREATE SEQUENCE putaway_task_code_seq START 1;

CREATE TABLE putaway_task (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 单号。租户内唯一：两张任务单同号则「按单号找任务」有两个答案。
  task_code varchar(32) NOT NULL,
  -- 上游 8.2.1.1 收货单（V70）：这批货是哪张收货单收进来的。**同服务，真外键**（见文件头第四节）。
  receipt_id uuid NOT NULL,
  receipt_code varchar(32) NOT NULL,
  warehouse_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  -- 库位推荐的来源（8.1.2.2 的 location_allocation）。可空 = 人工直接指派库位，
  -- 这是合法的现场形态（推荐只是建议，蓝图原文是「库位推荐**或**指定库位」）。
  -- 刻意不建外键，理由见文件头第四节。
  allocation_id uuid,
  -- 作业人员分配。**可空 = 未派工**：任务单先生成，派工是现场排班的事（蓝图输入第三项）。
  assignee_id uuid,
  assigned_at timestamptz,
  assigned_by uuid,
  -- 生命周期。PENDING 待执行 → IN_PROGRESS 执行中 → COMPLETED 完成；
  -- PENDING/IN_PROGRESS 均可 CANCELLED 取消。COMPLETED 与 CANCELLED 是终态，不可逆。
  status varchar(20) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','IN_PROGRESS','COMPLETED','CANCELLED')),
  started_at timestamptz,
  started_by uuid,
  completed_at timestamptz,
  completed_by uuid,
  cancelled_at timestamptz,
  cancelled_by uuid,
  cancel_reason varchar(200) NOT NULL DEFAULT '',
  -- 库存分录凭据：一张上架任务 = **一条** inventory_transaction + 2N 条反向分录
  -- （每次扫码两条：一减一加）。ref_type=PUTAWAY_TASK。
  -- **一张任务一条交易**是刻意的：UNIQUE(tenant_id,ref_type,ref_id) 于是顺带给整张任务单幂等，
  -- 而「每扫一次就新建一条交易」会让「这批货的完整上架账」散成 N 条互不相干的交易，
  -- P1 也就无从核对（净额只在单条交易内才说得清）。
  inventory_transaction_id uuid,
  -- 三个合计列。**唯一写入者是 refresh_putaway_task_totals() 触发器**，应用层写不进去。
  detail_line_count bigint NOT NULL DEFAULT 0,
  planned_quantity numeric(19,6) NOT NULL DEFAULT 0,
  putaway_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- PDA 扫码次数（= putaway_task_scan 行数），触发器回写。放在单头是为了列表页能直接展示
  -- 「扫码几次」而不必为每一行做一次子查询。
  scan_count bigint NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  notes text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_code),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES goods_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  -- 派工状态自洽：派了人必须有派工时刻与派工人。
  CHECK((assignee_id IS NULL) = (assigned_at IS NULL)),
  CHECK((assignee_id IS NULL) OR assigned_by IS NOT NULL),
  -- 执行中必须留痕是谁开始搬的（人效核算 8.5.1.1 的事实来源之一）。
  CHECK(status <> 'IN_PROGRESS' OR (started_at IS NOT NULL AND started_by IS NOT NULL)),
  CHECK(status <> 'COMPLETED' OR (completed_at IS NOT NULL AND completed_by IS NOT NULL)),
  CHECK(status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND btrim(cancel_reason)<>'')),
  -- **只有 COMPLETED 必须带分录凭据**：CANCELLED 恰恰一条分录都不该有
  -- （货还在收货暂存位，从没动过），带一个非空分录才是错账。
  CHECK(status <> 'COMPLETED' OR inventory_transaction_id IS NOT NULL),
  -- P2 在单头级也先挡一道（逐行的 P2 由 guard 回读）：
  -- 完成的量不可能超过计划的量，那意味着「搬走的比该搬的多」。
  CHECK(putaway_quantity <= planned_quantity),
  CHECK(detail_line_count >= 0 AND scan_count >= 0),
  CHECK(btrim(task_code)<>'' AND btrim(receipt_code)<>'')
);

COMMENT ON TABLE putaway_task IS
  '8.2.2.1 上架任务：把已收货物从收货暂存位上架到正式库位的现场作业单。蓝图 8.2.2.1：「上架只改位置不改数量」。';
COMMENT ON COLUMN putaway_task.inventory_transaction_id IS
  '本任务落的那条 inventory_transaction（ref_type=PUTAWAY_TASK，ref_id=任务 ID）。一张任务一条，P1 的核对范围。';
COMMENT ON COLUMN putaway_task.allocation_id IS
  '8.1.2.2 库位自动分配选定的决策（location_allocation.id），可空 = 人工指派。刻意不建外键：上游决策可被撤销，而已建任务不应失效。';
COMMENT ON COLUMN putaway_task.planned_quantity IS
  '应上架量合计。唯一写入者是 refresh_putaway_task_totals() 触发器。';

CREATE INDEX putaway_task_list ON putaway_task(tenant_id,status,warehouse_id,created_at DESC);
CREATE INDEX putaway_task_receipt ON putaway_task(tenant_id,receipt_id);
CREATE INDEX putaway_task_assignee ON putaway_task(tenant_id,assignee_id,status) WHERE assignee_id IS NOT NULL;
CREATE INDEX putaway_task_tx ON putaway_task(tenant_id,inventory_transaction_id)
  WHERE inventory_transaction_id IS NOT NULL;
CREATE INDEX putaway_task_allocation ON putaway_task(tenant_id,allocation_id) WHERE allocation_id IS NOT NULL;

-- ===========================================================================
-- 2. 上架任务明细（一条收货明细行 → 一个上架行）
-- ===========================================================================
CREATE TABLE putaway_task_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  -- **上游收货明细行**：真外键（同服务，见文件头第四节）。
  -- 「数量在收货时已确定」的这条数量就挂在这里，不另存一份没有来源的应上架量。
  receipt_line_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  -- 质量状态**冻结快照**：建任务那一刻的收货质量状态。
  -- 它决定目标库位类型（蓝图/8.1.2.2 的硬规则），也决定货能不能被销售预占 ——
  -- 上架不会把待检品变成可售品，所以这里存的是快照而不是「目标状态」。
  quality_status_id uuid NOT NULL,
  -- 源位（收货暂存位）：货现在在哪儿。**必须是真实持有这批货的 bucket**，
  -- 服务层按六元组定位并回填 source_bucket_id，不接受调用方随便指定一个库位。
  source_location_id uuid NOT NULL,
  source_bucket_id uuid,
  -- 目标位（正式库位）：本次要上的架位。
  target_location_id uuid NOT NULL,
  target_bucket_id uuid,
  lot_id uuid,
  -- 收货行上那个「总量」，冻结成 planned_quantity：建任务之后收货行再改也不影响已建的任务
  -- （V70 的 goods_receipt_line 有 version，收货方改了数量不该让在场的作业单凭空变形）。
  planned_quantity numeric(19,6) NOT NULL
    CHECK(planned_quantity > 0 AND planned_quantity = round(planned_quantity, 4)),
  -- 实上架量。行级 P2 的第一层：0（还没搬）或 [0, planned]（搬到一半）。
  putaway_quantity numeric(19,6) NOT NULL DEFAULT 0
    CHECK(putaway_quantity >= 0 AND putaway_quantity = round(putaway_quantity, 4)),
  -- 需求容量与需求承重：调用方量出来的这批货要占多少。**可空 = 未提供**，
  -- 可空不是「零」：「没量过」与「不占地方」是两件事（与 V106 同一口径）。
  required_capacity numeric(19,6) CHECK(required_capacity IS NULL OR required_capacity>=0),
  required_weight numeric(19,6) CHECK(required_weight IS NULL OR required_weight>=0),
  -- 指回仓库级可用量决策单位。上架不改 balance，但分录要落在它上面 ——
  -- 这两列是「P1 = 净额为 0」能被核对的前提（两条反向分录必须在同一 balance 行）。
  balance_id uuid,
  -- **派生量**：本明细行的任务是否仍然「占用」这张收货明细行。
  -- 唯一写入者是 refresh_putaway_task_active()（任务转 CANCELLED 时置 false），应用层写不进去。
  -- 它存在的唯一理由是让「一张收货明细同时只能有一个未取消的上架任务」能落成
  -- **部分唯一索引**（第 2 节末）—— 索引谓词里不能 join 子查询去问任务的状态。
  task_active boolean NOT NULL DEFAULT true,
  completed_at timestamptz,
  completed_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一张任务单里同一个收货明细行只能有一行。
  UNIQUE(tenant_id,task_id,receipt_line_id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES putaway_task(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,receipt_line_id) REFERENCES goods_receipt_line(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  FOREIGN KEY(tenant_id,source_bucket_id) REFERENCES stock_bucket(tenant_id,id),
  FOREIGN KEY(tenant_id,target_bucket_id) REFERENCES stock_bucket(tenant_id,id),
  -- 源位与目标位都用带 warehouse_id 的三列复合外键，一次钉死「跨租户引用 / 跨仓库挂库位 /
  -- 库里根本不存在的 id」三条红线（23503），与 V106 同一手法。
  -- warehouse_id 不在明细上：它从单头继承，而复合外键需要它在本地 —— 所以把单头那一份
  -- 复制到明细上，由 refresh 触发器与状态守卫共同维护（见第 4 节的 warehouse_id 一致性）。
  CHECK(putaway_quantity <= planned_quantity),
  -- 上架是把货「挪个地方」，源位与目标位相同等于什么都没做却记了一笔账。
  CHECK(source_location_id <> target_location_id),
  -- 搬过的行必须有落点余额行，否则「这批货进了哪条余额」无从回答。
  CHECK(putaway_quantity = 0 OR (balance_id IS NOT NULL AND target_bucket_id IS NOT NULL)),
  CHECK(btrim(sku_code)<>'')
);

COMMENT ON TABLE putaway_task_line IS
  '上架任务明细：8.2.1.1 收货明细行的冻结快照 + 源位/目标位。数量不在这里产生，只在这里被搬运。';
COMMENT ON COLUMN putaway_task_line.planned_quantity IS
  '应上架量 = 收货明细行的实收量，冻结在建任务那一刻。收货方事后改单不影响已建任务。';
COMMENT ON COLUMN putaway_task_line.quality_status_id IS
  '质量状态冻结快照。它决定目标库位类型（SELLABLE→STORAGE / PENDING_INSPECTION→QUARANTINE / DEFECTIVE→DEFECTIVE），上架不改质量状态。';

-- **一行收货明细同时只能有一个未取消的上架任务**。
--
-- 「未取消」用 denormalize 的 task_active 表达，而不是写成
-- `WHERE task.status <> 'CANCELLED'`（部分唯一索引的谓词里不能 join 子查询）。
-- task_active 的唯一写入者是第 3 节的触发器，**应用层写不进去**，所以不会漂 ——
-- 与 refresh 触发器同一纪律。
CREATE UNIQUE INDEX putaway_task_line_open
  ON putaway_task_line(tenant_id, receipt_line_id)
  WHERE task_active;

-- ===========================================================================
-- 3. PDA 扫码事件（append-only）
-- ===========================================================================
-- 蓝图职责里点名「支持 PDA/扫码」，所以扫码不是一句 UI 描述，而是要留痕的事实：
-- 谁、什么时候、用什么设备、扫到了什么码、把哪一行从哪个库位搬到了哪个库位、搬了多少。
--
-- 每次扫码产生**一对**分录（源位 -Q、目标位 +Q），两者的 id 都记在本行上，
-- 于是「这条扫码在账上对应哪两条分录」可以逐条回溯，而不是只能按交易汇总看一个净额。
CREATE TABLE putaway_task_scan (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  line_id uuid NOT NULL,
  -- 本次扫码搬走的量。必须能被库存刻度精确表示（与明细同一 CHECK）。
  scanned_quantity numeric(19,6) NOT NULL
    CHECK(scanned_quantity > 0 AND scanned_quantity = round(scanned_quantity, 4)),
  source_location_id uuid NOT NULL,
  target_location_id uuid NOT NULL,
  -- 扫码读到的码（库位条码 / SKU 条码 / 单号）。**可空**：允许人工指派库位时纯数量上架，
  -- 但 PDA 作业一定有码 —— 有码才能追「扫错没扫错」。
  scan_reference varchar(128) NOT NULL DEFAULT '',
  -- 设备类型。PDA 是蓝图点名的形态；DESKTOP 用于后台补录。
  device_type varchar(16) NOT NULL DEFAULT 'PDA'
    CHECK(device_type IN ('PDA','MOBILE','DESKTOP')),
  -- **业务时间**：作业员扫的那一下，不是行写入库的时刻。
  -- 事后补录必须能填真实时刻，否则「这批货几点上的架」永远答不出来。
  scanned_at timestamptz NOT NULL,
  scanner_id uuid NOT NULL,
  -- 本次扫码在账上留下的两条分录。
  inventory_entry_out_id uuid,
  inventory_entry_in_id uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES putaway_task(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,line_id) REFERENCES putaway_task_line(tenant_id,id),
  FOREIGN KEY(tenant_id,inventory_entry_out_id) REFERENCES inventory_entry(tenant_id,id),
  FOREIGN KEY(tenant_id,inventory_entry_in_id) REFERENCES inventory_entry(tenant_id,id),
  -- 搬过货的扫码必须指向它自己的那两条分录，否则「扫码与账」之间没有连线。
  CHECK(inventory_entry_out_id IS NOT NULL AND inventory_entry_in_id IS NOT NULL),
  -- 扫码的两个库位必须与明细一致：明细是「搬到哪」，扫码是「这一次搬」的凭据，
  -- 两者对不上说明有人绕过了明细直接改账。
  CHECK(source_location_id <> target_location_id)
);

COMMENT ON TABLE putaway_task_scan IS
  '8.2.2.1 PDA/扫码事件台账（append-only）。每次扫码在账上留下一对反向分录，id 记在本行上可逐条回溯。';

-- 同一张任务单里，同一个扫描码只能出现一次：PDA 重复提交是现场常态（网络抖动重发），
-- 幂等锚点就是它 —— 没有这条，一次抖动就会把同一批货搬两遍（源位扣两次、目标位加两次，
-- 而 P1 的净额仍然是 0，所以「只改位置不改数量」这条不变量**抓不到**这个 bug）。
CREATE UNIQUE INDEX putaway_task_scan_idempotent
  ON putaway_task_scan(tenant_id, task_id, scan_reference)
  WHERE scan_reference <> '';

CREATE INDEX putaway_task_scan_task ON putaway_task_scan(tenant_id,task_id,scanned_at);
CREATE INDEX putaway_task_scan_line ON putaway_task_scan(tenant_id,line_id);

-- append-only：扫码是现场事实的原始记录，改它等于改作业记录。
-- 两道不可篡改：权限层 REVOKE + 触发器（表 owner 与超级用户仍能改，触发器补第二道）。
CREATE FUNCTION guard_putaway_scan_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'putaway_task_scan 是 append-only：不允许 %。扫码是现场作业的原始凭据，'
    '改它等于改作业记录；记错了就再扫一次纠偏扫码', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER putaway_task_scan_append_only
  BEFORE UPDATE OR DELETE ON putaway_task_scan
  FOR EACH ROW EXECUTE FUNCTION guard_putaway_scan_append_only();

-- ===========================================================================
-- 4. 派生量：单头合计与 task_active（应用层唯一不能写的两处）
-- ===========================================================================
-- 为什么必须用触发器而不是让应用层算：
--   P1/P2/P3 要能防住「绕过服务层直连 SQL」。只要合计列可以由应用层随意赋值，
--   就存在「单头写着搬完了、账上其实少搬」这种自相矛盾的记录。
--   触发器让合计成为明细的**派生量**，应用层写不进去，也就不会漂。
CREATE FUNCTION refresh_putaway_task_totals() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_task   uuid;
  v_tenant uuid;
BEGIN
  -- AFTER 触发器：INSERT/UPDATE 走 NEW，DELETE 走 OLD —— DELETE 时 NEW 是未赋值记录，
  -- 直接写 COALESCE(NEW.task_id, OLD.task_id) 会在取字段时报错，所以按 TG_OP 分支。
  IF TG_OP = 'DELETE' THEN
    v_task := OLD.task_id;
    v_tenant := OLD.tenant_id;
  ELSE
    v_task := NEW.task_id;
    v_tenant := NEW.tenant_id;
  END IF;
  -- CANCELLED 的单不再回写：它的货从来没动过，让合计继续跟着明细走会让人误以为
  -- 「这张被取消的单还占了库位」。
  UPDATE putaway_task t
     SET detail_line_count = agg.lines,
         planned_quantity  = agg.planned,
         putaway_quantity  = agg.putaway,
         scan_count        = agg.scans,
         version = t.version + 1,
         updated_at = now()
    FROM (SELECT count(*) AS lines,
                 COALESCE(sum(l.planned_quantity),0) AS planned,
                 COALESCE(sum(l.putaway_quantity),0) AS putaway,
                 (SELECT count(*) FROM putaway_task_scan sc
                   WHERE sc.tenant_id = v_tenant AND sc.task_id = v_task) AS scans
            FROM putaway_task_line l
           WHERE l.tenant_id = v_tenant AND l.task_id = v_task) agg
   WHERE t.id = v_task AND t.tenant_id = v_tenant AND t.status <> 'CANCELLED';
  RETURN NULL;
END $$;
CREATE TRIGGER putaway_task_line_totals
  AFTER INSERT OR UPDATE OR DELETE ON putaway_task_line
  FOR EACH ROW EXECUTE FUNCTION refresh_putaway_task_totals();

-- 扫码次数由明细以外的第二处触发器回写（扫码不改明细，所以上面那个触发器不会跟着响）。
CREATE FUNCTION refresh_putaway_task_scans() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_task   uuid;
  v_tenant uuid;
  v_status varchar(20);
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_task := OLD.task_id;
    v_tenant := OLD.tenant_id;
  ELSE
    v_task := NEW.task_id;
    v_tenant := NEW.tenant_id;
  END IF;
  SELECT status INTO v_status FROM putaway_task WHERE id = v_task AND tenant_id = v_tenant;
  IF v_status IS NOT NULL AND v_status <> 'CANCELLED' THEN
    UPDATE putaway_task t
       SET scan_count = (SELECT count(*) FROM putaway_task_scan sc
                          WHERE sc.tenant_id = v_tenant AND sc.task_id = v_task),
           version = t.version + 1,
           updated_at = now()
     WHERE t.id = v_task AND t.tenant_id = v_tenant;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER putaway_task_scan_totals
  AFTER INSERT OR DELETE ON putaway_task_scan
  FOR EACH ROW EXECUTE FUNCTION refresh_putaway_task_scans();

-- task_active 的唯一写入者：任务被取消时，它的明细行解除「一张收货明细只能上一个架」的占用。
-- 仍然保持 true 的两种状态：
--   PENDING / IN_PROGRESS —— 货还没（或正在）被搬走，收货明细行还被占着。
--   COMPLETED —— 货已经搬完了，这张收货明细行的上架**永远只发生一次**。
--     把它也置 false 会允许「同一批货再上一次架」，那不是上架任务，是库位间调拨，属别的模块。
CREATE FUNCTION refresh_putaway_task_active() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = 'CANCELLED' AND OLD.status IS DISTINCT FROM 'CANCELLED' THEN
    UPDATE putaway_task_line l
       SET task_active = false, updated_at = now()
     WHERE l.tenant_id = NEW.tenant_id AND l.task_id = NEW.id AND l.task_active;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER putaway_task_active
  AFTER UPDATE OF status ON putaway_task
  FOR EACH ROW EXECUTE FUNCTION refresh_putaway_task_active();

-- ===========================================================================
-- 5. 库位硬规则进 DB：质量状态 → 库位类型、不许混放、源位必须真有货
-- ===========================================================================
-- 蓝图/8.1.2.2 的红线：「残次品入残次库位是**硬规则**，不允许混放」。
-- 只在应用层 if 的话，一条运维 SQL 或一次 type 字段的手改就能把残次品放进存储位。
--
-- **刻意复用 V106 的 location_allocation_required_type(role)** 而不是抄一份 CASE：
-- 抄一份就等于将来改一处忘一处，而这是蓝图点名的红线。两处规则必须是同一张映射。
CREATE FUNCTION guard_putaway_task_line_placement() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_role      varchar(20);
  v_type      varchar(16);
  v_required  varchar(16);
  v_src_type  varchar(16);
  v_src_status varchar(16);
  v_wh        uuid;
  v_tgt_wh    uuid;
  v_conflict  varchar(20);
  v_bucket_wh uuid;
  v_on_hand   numeric(19,4);
  v_planned   numeric(19,6);
  v_cap_limit numeric(19,6);
  v_wt_limit  numeric(19,6);
  v_taken_cap numeric;
  v_taken_wt  numeric;
BEGIN
  SELECT q.role INTO v_role
    FROM inventory_quality_status q
   WHERE q.tenant_id = NEW.tenant_id AND q.id = NEW.quality_status_id;
  IF v_role IS NULL THEN
    -- 参数必须与格式串**同一行**：plpgsql 的 RAISE 一旦遇到换行就认为语句结束，
    -- 参数另起一行会被解析成新语句（与 V106 同一坑）。
    RAISE EXCEPTION '质量状态 % 在本租户不存在'
      '（库位类型由质量状态决定，不能凭空指派）', NEW.quality_status_id
      USING ERRCODE='23514';
  END IF;

  -- ---- 5.1 目标库位必须属于本单所在的仓（跨仓库上架是另一个动作，不在本模块）
  SELECT l.type, l.warehouse_id, l.status INTO v_type, v_tgt_wh, v_src_status
    FROM warehouse_location l
   WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.target_location_id;
  IF v_type IS NULL THEN
    RAISE EXCEPTION '目标库位 % 在本租户不存在', NEW.target_location_id USING ERRCODE='23514';
  END IF;
  IF v_src_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '目标库位 % 当前状态是 %', NEW.target_location_id, v_src_status
      USING ERRCODE='23514';
  END IF;
  SELECT warehouse_id INTO v_wh FROM putaway_task WHERE id = NEW.task_id AND tenant_id = NEW.tenant_id;
  IF v_wh IS NULL OR v_tgt_wh <> v_wh THEN
    RAISE EXCEPTION '目标库位 % 属于仓库 %，而这张上架任务在仓库 %',
      NEW.target_location_id, v_tgt_wh, v_wh USING ERRCODE='23514';
  END IF;

  -- ---- 5.2 硬规则：质量状态 → 库位类型（复用 V106 的权威函数，不抄 CASE）
  v_required := location_allocation_required_type(v_role);
  IF v_required IS NULL THEN
    RAISE EXCEPTION '质量状态角色 % 没有对应的库位类型', v_role USING ERRCODE='23514';
  END IF;
  IF v_type <> v_required THEN
    RAISE EXCEPTION '质量状态「%」只能进 % 类库位，目标库位 % 是 % 类：%', v_role, v_required,
      NEW.target_location_id, v_type,
      CASE WHEN v_role = 'DEFECTIVE' THEN '残次品混进非残次库位会让它跟着正常品一起被拣走发出去'
           WHEN v_role = 'PENDING_INSPECTION' THEN '待检品混进非待检库位等于未过质检就进了可发货区'
           ELSE '质量状态与库位类型不一致会让「这批货在不在待检区」永远答不出来' END
      USING ERRCODE='23514';
  END IF;

  -- ---- 5.3 不允许混放：目标库位上已压着别的质量角色的货，就不能再塞当前角色。
  -- 类型映射已经挡掉大部分情况，但库位类型是可以被人直接改的（8.1.2.1 允许改），
  -- 所以这里独立再查一次当前实际存放的 bucket，让「混放」是一条运行时事实。
  SELECT min(q.role) INTO v_conflict
    FROM stock_bucket k
    JOIN inventory_quality_status q ON q.tenant_id = k.tenant_id AND q.id = k.quality_status_id
   WHERE k.tenant_id = NEW.tenant_id AND k.location_id = NEW.target_location_id AND k.on_hand > 0
     AND q.role <> v_role;
  IF v_conflict IS NOT NULL THEN
    RAISE EXCEPTION '目标库位 % 上已压着质量角色「%」的货，与「%」不允许混放',
      NEW.target_location_id, v_conflict, v_role USING ERRCODE='23514';
  END IF;

  -- ---- 5.4 **源位必须真的压着这批货**。这是上架与「凭空改位置」的分界线：
  -- 没有这条守卫，一个只填了 SKU 与库位的上架行就能让服务层去加一个并不存在的库存。
  -- 定位方式是 stock_bucket 的唯一六元组（V2 的 UNIQUE NULLS NOT DISTINCT 保证了它唯一）。
  SELECT k.id, k.warehouse_id, k.on_hand INTO NEW.source_bucket_id, v_bucket_wh, v_on_hand
    FROM stock_bucket k
   WHERE k.tenant_id = NEW.tenant_id AND k.sku_id = NEW.sku_id
     AND k.quality_status_id = NEW.quality_status_id
     AND k.location_id = NEW.source_location_id
     AND k.lot_id IS NOT DISTINCT FROM NEW.lot_id;
  IF NEW.source_bucket_id IS NULL THEN
    RAISE EXCEPTION '收货暂存位 % 上没有该 SKU/批次/质量状态的库存行：'
      '上架是把**已有的货**挪个位置，源位上没有的东西搬不走（sku=% lot=% quality=%）',
      NEW.source_location_id, NEW.sku_id, coalesce(NEW.lot_id::text,'-'), v_role
      USING ERRCODE='23514';
  END IF;
  IF v_bucket_wh <> v_wh THEN
    RAISE EXCEPTION '源库位 % 属于仓库 %，而这张上架任务在仓库 %',
      NEW.source_location_id, v_bucket_wh, v_wh USING ERRCODE='23514';
  END IF;
  IF v_on_hand < NEW.planned_quantity THEN
    RAISE EXCEPTION '源库位 % 上只有 % 在手，本次要上架 %：超源位库存上架等于凭空多出这批货',
      NEW.source_location_id, v_on_hand, NEW.planned_quantity USING ERRCODE='23514';
  END IF;
  -- 余额行定位：同一 balance 上的一减一加，正是 P1 = 净额 0 的前提。
  SELECT b.id INTO NEW.balance_id
    FROM inventory_balance b
   WHERE b.tenant_id = NEW.tenant_id AND b.sku_id = NEW.sku_id
     AND b.warehouse_id = v_wh AND b.quality_status_id = NEW.quality_status_id;
  IF NEW.balance_id IS NULL THEN
    RAISE EXCEPTION '该 SKU/仓库/质量状态没有库存余额行：货在 bucket 上却没有余额行，守恒式已破'
      USING ERRCODE='23514';
  END IF;

  -- ---- 5.5 容量与承重：两边都有数才判。任一为空都**不**拦 —— 因为那不是「够」，
  -- 而是「没量过」，服务层会把 evaluated=false 如实报出来（与 V106 同一口径）。
  -- 口径也相同：只算**本模块自己承诺过的量**，不算库位上既有库存占了多少
  -- （capacity 是体积单位、on_hand 是件数，拿件数凑利用率是单位错配，见 V106 文件头第四节）。
  IF NEW.required_capacity IS NOT NULL THEN
    SELECT l.capacity INTO v_cap_limit
      FROM warehouse_location l
     WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.target_location_id;
    IF v_cap_limit IS NOT NULL THEN
      SELECT coalesce(sum(p.required_capacity),0) INTO v_taken_cap
        FROM putaway_task_line p
        JOIN putaway_task t ON t.tenant_id = p.tenant_id AND t.id = p.task_id
       WHERE p.tenant_id = NEW.tenant_id AND p.target_location_id = NEW.target_location_id
         AND t.status <> 'CANCELLED' AND p.id <> NEW.id;
      IF v_taken_cap + NEW.required_capacity > v_cap_limit THEN
        RAISE EXCEPTION '目标库位 % 容量 % 已被未取消的上架承诺占用 %，本次还要 %，超出上限',
          NEW.target_location_id, v_cap_limit, v_taken_cap, NEW.required_capacity
          USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;
  IF NEW.required_weight IS NOT NULL THEN
    SELECT l.weight_limit INTO v_wt_limit
      FROM warehouse_location l
     WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.target_location_id;
    IF v_wt_limit IS NOT NULL THEN
      SELECT coalesce(sum(p.required_weight),0) INTO v_taken_wt
        FROM putaway_task_line p
        JOIN putaway_task t ON t.tenant_id = p.tenant_id AND t.id = p.task_id
       WHERE p.tenant_id = NEW.tenant_id AND p.target_location_id = NEW.target_location_id
         AND t.status <> 'CANCELLED' AND p.id <> NEW.id;
      IF v_taken_wt + NEW.required_weight > v_wt_limit THEN
        RAISE EXCEPTION '目标库位 % 承重上限 % 已被未取消的上架承诺占用 %，本次还要 %，超出上限',
          NEW.target_location_id, v_wt_limit, v_taken_wt, NEW.required_weight
          USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER putaway_task_line_placement_guard
  BEFORE INSERT OR UPDATE OF sku_id, quality_status_id, source_location_id, target_location_id,
    lot_id, planned_quantity, required_capacity, required_weight
  ON putaway_task_line
  FOR EACH ROW EXECUTE FUNCTION guard_putaway_task_line_placement();

-- ===========================================================================
-- 6. 状态机与三条不变量守卫（回读真正的账本分录，不是单据上的自报数字）
-- ===========================================================================
-- 合法迁移：PENDING → IN_PROGRESS → COMPLETED；PENDING/IN_PROGRESS → CANCELLED。
-- COMPLETED 与 CANCELLED 是终态，不可逆：
--   COMPLETED 后货已经在正式库位上，把它改回 PENDING 等于宣布「这批货还在收货暂存位」，
--     而库里它已经不在那儿了 —— 货实不符。
--   CANCELLED 后一条分录都没产生，把它改回 PENDING 也不会凭空多出货，但会让
--     「已取消」这个现场事实凭空消失。
CREATE FUNCTION guard_putaway_task() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_lines      bigint;
  v_incomplete bigint;
  v_net        numeric(19,4);
  v_out        numeric(19,4);
  v_moved      numeric(19,6);
  v_tx         uuid;
  v_entries    bigint;
BEGIN
  -- ---- 6.1 终态不可逆
  IF OLD.status IN ('COMPLETED','CANCELLED') AND NEW.status IS DISTINCT FROM OLD.status THEN
    RAISE EXCEPTION '上架任务 % 已经是终态 %，不能再流转成 %：货已经在正式库位上，'
      '把它改回去等于宣布这批货还在收货暂存位，而库里它已经不在那儿了',
      OLD.task_code, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;

  IF NEW.status = 'COMPLETED' THEN
    -- ---- 6.2 必须真的有明细。一张 0 行的「已完成上架任务」没有任何含义。
    SELECT count(*) INTO v_lines
      FROM putaway_task_line
     WHERE tenant_id = NEW.tenant_id AND task_id = NEW.id;
    IF v_lines = 0 THEN
      RAISE EXCEPTION '上架任务 % 没有任何明细就要标记完成：没有明细的上架等于没上架',
        NEW.task_code USING ERRCODE='23514';
    END IF;

    -- ---- 6.3 P2：逐行核对，实上架量必须**恰好等于**应上架量。
    -- 半张单搬到一半就说「完成」，货去哪了说不清 —— 而「只剩一点点没搬」
    -- 正是现场最常见的真实情形，所以这一条必须硬。
    SELECT count(*) INTO v_incomplete
      FROM putaway_task_line
     WHERE tenant_id = NEW.tenant_id AND task_id = NEW.id
       AND putaway_quantity <> planned_quantity;
    IF v_incomplete > 0 THEN
      RAISE EXCEPTION '上架任务 % 还有 % 行没搬完（实上架量 ≠ 应上架量）就不能标记完成（P2）：'
        '「基本搬完了」不是完成，货去哪了说不清',
        NEW.task_code, v_incomplete USING ERRCODE='23514';
    END IF;

    -- ---- 6.4 **P1：回读账本分录，净额必须恰好为 0。**
    -- 这是蓝图加粗句「上架只改位置不改数量」的字面形态，也是整个模块的技术核心。
    -- 比对的对象是 inventory_entry（账本），不是 putaway_task_line 上的数字 ——
    -- 单据上的自报数字可以骗人，账本不行。
    v_tx := NEW.inventory_transaction_id;
    IF v_tx IS NULL THEN
      RAISE EXCEPTION '上架任务 % 标记完成但没有库存分录凭据：一次上架必须留下可审阅的账',
        NEW.task_code USING ERRCODE='23514';
    END IF;

    SELECT count(*), coalesce(sum(e.on_hand_delta),0),
           coalesce(sum(CASE WHEN e.on_hand_delta < 0 THEN -e.on_hand_delta ELSE 0 END),0)
      INTO v_entries, v_net, v_out
      FROM inventory_entry e
      JOIN inventory_transaction t
        ON t.tenant_id = e.tenant_id AND t.id = e.transaction_id
     WHERE e.transaction_id = v_tx
       AND t.tenant_id = NEW.tenant_id AND t.ref_type = 'PUTAWAY_TASK' AND t.ref_id = NEW.id;

    IF v_entries = 0 THEN
      RAISE EXCEPTION '上架任务 % 标记完成但账上没有任何分录：货已经上架却没有留下账（P1）',
        NEW.task_code USING ERRCODE='23514';
    END IF;

    IF v_net <> 0 THEN
      RAISE EXCEPTION '上架任务 % 违反不变量：账上分录净额为 %，必须恰好为 0（P1）。'
        '蓝图 8.2.2.1 原话是「上架只改位置不改数量」—— 净额不为 0 意味着这次上架'
        '凭空增减了库存，它已经不再是上架而是收货或发货了',
        NEW.task_code, v_net USING ERRCODE='23514';
    END IF;

    -- ---- 6.5 P3：负向分录绝对值之和 = 明细实上架量之和。
    -- 为什么 P1 抓不到这个：源位减 90、目标位加 90，净额依然是 0，
    -- 但单头写着搬了 100 —— 所以必须独立核对「搬了多少」。
    SELECT coalesce(sum(l.putaway_quantity),0) INTO v_moved
      FROM putaway_task_line l
     WHERE l.tenant_id = NEW.tenant_id AND l.task_id = NEW.id;

    IF v_out <> v_moved THEN
      RAISE EXCEPTION '上架任务 % 违反不变量：账上源位出库分录之和 % ≠ 明细实上架量之和 %（P3）。'
        '净额为 0 只保证「不凭空增减」，这一条才保证「搬的量与单上写的量一致」',
        NEW.task_code, v_out, v_moved USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER putaway_task_guard BEFORE UPDATE ON putaway_task
  FOR EACH ROW EXECUTE FUNCTION guard_putaway_task();

-- 终态任务与进行中的任务，明细的可改范围不一样，分两种拦：
--
--   ① **终态（COMPLETED / CANCELLED）**：明细一律不可再改。否则可以在完成后追加明细行、
--      或把某行 putaway_quantity 归零重扫一遍 —— 那等于改账。
--   ② **IN_PROGRESS**：只允许「搬了多少」往前走，**路由字段一律锁死**
--      （目标库位 / 源位 / 应上架量 / SKU / 批次 / 质量状态）。
--      原因是部分扫码已经把货搬到目标库位了，此时把目标库位改掉等于让已发生的分录
--      与单据上的「搬到哪」对不上 —— 而这正是 P3 想防的那类自相矛盾。
--      注意 ① 不能写成 `status <> 'PENDING'`：那样会连 PDA 扫码更新 putaway_quantity
--      一起挡掉，而那正是执行中的任务最该允许的写。
--
-- PENDING 全部可改（人还没开始搬，改目标库位是正常的现场调整）。
-- 建任务时明细必然先于任何终态存在，所以只拦 UPDATE/DELETE，不拦 INSERT 单头后的明细插入。
CREATE FUNCTION guard_putaway_task_line_after_completion_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_task_id uuid;
  v_status   text;
BEGIN
  v_task_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.task_id ELSE NEW.task_id END;
  SELECT status INTO v_status FROM putaway_task WHERE id = v_task_id;
  IF v_status IS NULL THEN
    -- 单据不存在时不拦（建单流程是先插单头再插明细，单头一定先在）。
    IF TG_OP = 'DELETE' THEN
      RETURN OLD;
    END IF;
    RETURN NEW;
  END IF;

  -- **取消时的派生量解锁**要先放行，否则第 4 节的 task_active 触发器会把自己锁死：
  -- 任务转 CANCELLED 要把它的明细行 task_active 置 false，而那一刻本守卫看到的状态
  -- 已经是 CANCELLED，于是「取消」被自己的解锁动作挡住 —— 一个自己拦自己的死锁。
  -- 放行条件写得很窄：**只有** task_active 变了，数量与全部路由字段一个都没动，
  -- 所以它不可能被用来改账（要改数/改库位仍然要过下面的检查）。
  IF v_status = 'CANCELLED'
     AND TG_OP = 'UPDATE'
     AND NEW.task_active IS DISTINCT FROM OLD.task_active
     AND NEW.putaway_quantity IS NOT DISTINCT FROM OLD.putaway_quantity
     AND NEW.planned_quantity IS NOT DISTINCT FROM OLD.planned_quantity
     AND NEW.source_location_id IS NOT DISTINCT FROM OLD.source_location_id
     AND NEW.target_location_id IS NOT DISTINCT FROM OLD.target_location_id
     AND NEW.source_bucket_id IS NOT DISTINCT FROM OLD.source_bucket_id
     AND NEW.target_bucket_id IS NOT DISTINCT FROM OLD.target_bucket_id
     AND NEW.balance_id IS NOT DISTINCT FROM OLD.balance_id THEN
    RETURN NEW;
  END IF;

  IF v_status IN ('COMPLETED','CANCELLED') THEN
    RAISE EXCEPTION '上架任务已经 %，明细不可再改：货一旦开始搬动，库存分录就跟着入账，'
      '改明细等于改账；取消任务请走 cancel 端点（它不动库存）', v_status
      USING ERRCODE='23514';
  END IF;

  IF v_status = 'IN_PROGRESS' AND TG_OP = 'UPDATE' THEN
    IF NEW.sku_id IS DISTINCT FROM OLD.sku_id
       OR NEW.quality_status_id IS DISTINCT FROM OLD.quality_status_id
       OR NEW.source_location_id IS DISTINCT FROM OLD.source_location_id
       OR NEW.target_location_id IS DISTINCT FROM OLD.target_location_id
       OR NEW.lot_id IS DISTINCT FROM OLD.lot_id
       OR NEW.planned_quantity IS DISTINCT FROM OLD.planned_quantity
       OR NEW.receipt_line_id IS DISTINCT FROM OLD.receipt_line_id THEN
      RAISE EXCEPTION '上架任务已经执行中，货正在往目标库位搬，'
        'SKU / 批次 / 质量状态 / 源位 / 目标库位 / 应上架量都已锁死：'
        '此时改路由会让已入账的分录与单据上的「搬到哪」对不上（P3）'
        USING ERRCODE='23514';
    END IF;
    -- 执行中只允许实上架量往前走，不允许往回改（否则扫码可以「先搬了再抹掉」）。
    IF NEW.putaway_quantity < OLD.putaway_quantity THEN
      RAISE EXCEPTION '执行中的上架任务不允许把实上架量从 % 往回改到 %：'
        '货已经搬走了，写回更小的数等于让账上的货凭空消失；扫错了请取消任务重开',
        OLD.putaway_quantity, NEW.putaway_quantity USING ERRCODE='23514';
    END IF;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER putaway_task_line_guard
  BEFORE UPDATE OR DELETE ON putaway_task_line
  FOR EACH ROW EXECUTE FUNCTION guard_putaway_task_line_after_completion_fn();

-- ===========================================================================
-- 7. 多租户：RLS + 授权（与 V1 第 9 节、V106 第 5 节、V108 第 5 节同一纪律）
-- ===========================================================================
-- 本迁移**新建**三张表，所以 ENABLE / FORCE / policy / GRANT 都是本表的归属，
-- 不重复写 goods_receipt / stock_bucket / warehouse_location 的策略 —— 归属写错比不写更糟。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['putaway_task','putaway_task_line','putaway_task_scan'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON putaway_task, putaway_task_line TO warehouse_app;
-- 扫码台账 append-only：只给 SELECT/INSERT。UPDATE/DELETE 已经被上面的触发器挡了一道，
-- 这里再从权限层去掉（与 V104 的 warehouse_config_change 同一手法）。
GRANT SELECT,INSERT ON putaway_task_scan TO warehouse_app;
REVOKE UPDATE,DELETE ON putaway_task_scan FROM warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建包会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE putaway_task_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V103、V108 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;