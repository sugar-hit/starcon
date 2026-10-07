-- ===========================================================================
-- 7.4.2.2 盘点差异处理：归因（五类原因）→ 处理决定（三选）→ 调整单 → 审批 → 入账
-- ===========================================================================
-- 蓝图原文：「处理盘点差异：按差异原因（损耗、错发、偷盗、未入账、时点差）分类，生成调整单
-- 走审批，盘盈盘亏入账并可追责。」输入：「盘点差异明细；差异原因；处理决定（调整/复盘/忽略）。」
-- 输出：「调整单 + 盘点完成报告（含盘盈率、差异率指标）。」上游 = 7.4.2.1；下游 = 7.4.1.1
-- 调整单、13 分析（盘点差异统计）。以及本模块被点名的红线：
-- 「**差异不直接改余额**，必须经调整单与审批，这是实体目录明确的红线。」
--
-- ---------------------------------------------------------------------------
-- 一、上游已交付的形状（V59 7.4.2.1）
-- ---------------------------------------------------------------------------
-- 本模块**不重算**差异，一行差异都不自己造：
--   stocktake_line.book_on_hand  冻结的账面数（V59 触发器钉死不可更新）
--   stocktake_line.counted_qty   实盘数；NULL = 还没盘
--   stocktake_line.variance      = counted_qty − book_on_hand，**generated column**
--
-- 所以「差异明细」这一屏是**读** stocktake_line，不是本模块的表。这条继承很关键：
-- 差异只有一份真相，而它在 7.4.2.1 那里。本模块存的是**处置**（谁、为什么、怎么处理），
-- 不是差异本身。调整单行上的 qty_delta 是一次性快照（COPY 语义），并由 3.5 的
-- 「入账数量 == 调整单明细合计」约束触发器与它对齐。
--
-- ---------------------------------------------------------------------------
-- 二、五类原因与三种决定：枚举里留着的就是真能选的
-- ---------------------------------------------------------------------------
-- 原因（五类，蓝图原文）：
--   SHRINKAGE   损耗     实物损耗/破损/过期报废
--   MISSHIP     错发     发错货导致的账面与实物错位
--   THEFT       偷盗     舞弊类，追责对象是人
--   UNRECORDED  未入账   **漏记流水**：货到了/发出去了，系统没记这笔
--   TIMING      时点差   账面快照时点与实际不符（冻结之后才发生的正常收发）
--
-- UNRECORDED 与 TIMING 必须分开，语义相反：
--   UNRECORDED  账错了 → 该补的是一笔流水（缺单），账应该被改
--   TIMING      账没错 → 冻结那一刻的快照与后来盘点时点之间有正常在途/未达账务，
--                账面数本身是**对的**，错的是拿它当今天的余额看
-- 把两者合成一类（「其他差异」）等于把「账错了」和「账没错」混进同一个筐，
-- 而盘点差异复盘时最需要回答的恰恰是这条差异要不要动账。
--
-- 处理决定（三选，蓝图原文「调整/复盘/忽略」），后果各不相同：
--   ADJUST   调整   生成调整单行 → 审批通过后才入账（唯一会改余额的路径）
--   RECOUNT  复盘   把该行的实盘数退回未录（counted_* 三列一起清空），
--                    这一行重新变成「还没盘」，等人再去数一次。V59 的
--                    stocktake_line_frozen 触发器放行的正是 counted_* 这一组。
--   IGNORE   忽略   不入账、不复盘，但**必须留痕**：谁、什么时候、为什么。
--
-- IGNORE 不是消音器：3.4 的触发器让**忽略过的行不能再被归因**（连「先忽略再偷偷
-- 改成调整」这条路都堵掉），而审计表把每一次归因/入账/忽略都记下来。
--
-- ---------------------------------------------------------------------------
-- 三、调整单实体：为什么它落在这里，而不是等 7.4.1.1
-- ---------------------------------------------------------------------------
-- 蓝图把 7.4.1.1 调整单管理列为**下游**，而它尚未交付。没有下游就不能把差异处理
-- 停摆在这里（差异不处理就永远挂着，账面与实物越差越远），所以本模块**自落地一个
-- 调整单实体**，并且明确声明：
--   - 它是 7.4.2.2 为了走完「差异 → 审批 → 入账」闭环而落的调整单，**不是** 7.4.1.1 的实现；
--   - 两者的交接靠 V1 既有的定位键 inventory_transaction(ref_type, ref_id)：
--     本模块入账时写 ref_type='STOCKTAKE_VARIANCE'、ref_id=本调整单 id。7.4.1.1 交付后，
--     由它接管单据的发起与审批，本模块的这批单据凭 ref_type/ref_id 被它按**已入账单据**
--     读回（同一套 source_system + reference_id 约定，AGENTS.md 禁止跨服务建 FK 或查对方的表）。
--   - 反向的调整单 id 不在本表留空列 —— 那是臆造一个没人会写的字段。
--
-- 状态机：DRAFT → PENDING_APPROVAL → APPROVED → POSTED，另有 REJECTED / CANCELLED 两条终止边。
-- **POSTED 只能从 APPROVED 到达**，这是 3.1 的 DB 级不变量。
--
-- ---------------------------------------------------------------------------
-- 四、审批：没接入 17.4，如实说明替代机制
-- ---------------------------------------------------------------------------
-- 17.4 审批中心**尚未交付**，本模块**没有**接入任何审批系统，也不谎称接入了。
-- 实际用的是可审计的替代机制，三件事都落在库里：
--   1. 状态机 + 3.1 的 DB 级转移约束（未 APPROVED 不可能变成 POSTED）；
--   2. 3.6 的「提交人 ≠ 审批人」职责分离（同一用户不能自己批自己提的单）；
--   3. 3.7 的 append-only 审计（谁归因、谁提交、谁审批、谁入账、什么时候，全部留痕）。
-- 17.4 交付后，approve/reject 两个端点的判定应改为调用审批中心 —— 表结构与状态机不需要变。
--
-- ---------------------------------------------------------------------------
-- 五、「未审批不得入账」钉在数据库层（不只靠 API 层自律）
-- ---------------------------------------------------------------------------
-- 这是本迁移最要紧的部分。红线若只写在服务层注释里，任何一条绕过服务拿到连接的
-- 路径（运维脚本、另一个服务、以后新写的端点）都能把差异直接写进余额。四个 DB 级保护：
--
--   3.1 stocktake_variance_adjustment 状态机：POSTED 只能从 APPROVED 到达；
--       POSTED 之后状态与关键列一律不可改（重复入账只能靠 3.4 的唯一索引与
--       inventory_transaction 的 UNIQUE(tenant_id,ref_type,ref_id)）。
--   3.2 **入账闸门（inventory_entry BEFORE INSERT）**：当这笔分录所属的
--       inventory_transaction.ref_type='STOCKTAKE_VARIANCE' 时，被引用的调整单必须
--       已经 APPROVED 或 POSTED —— 否则 DB 直接拒绝写分录。**未审批的调整单在数据库
--       层拿不到任何一条库存分录**，这正是「差异不直接改余额」的可执行形式。
--   3.3 入账数量守恒（延迟约束触发器）：Σ 该交易的 on_hand_delta 必须等于
--       调整单所有行的 qty_delta 合计。不许少记、不许多记、不许只入一部分。
--   3.4 一条差异只能入账一次：对已过账（posted_at 非空）的 stocktake_line_id 建
--       部分唯一索引；且 IGNORE 过的行不允许再被归因。
--   3.5 已过账的调整单行不可改数量/定位。
--
-- 注意 3.2 刻意**不**去拦「谁都可以 UPDATE inventory_balance」：那会连带打掉既有模块
-- 与既有集成测试的直写路径（7.1.1.x / 7.1.2.2 的夹具就直写 balance），属于跨模块回归。
-- 真正的封堵点是 3.2 + 3.3：**差异要变成余额，唯一合法通道是「已审批调整单 → 分录 →
-- 余额」，而这条通道的每一段都被数据库检查过**。任何绕过它的差异改账都拿不出分录。
--
-- ---------------------------------------------------------------------------
-- 六、口径：数量与时间
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,4) + Java BigDecimal，全程禁浮点（与 V1/V2/V54/V56/V59 同口径）。
--   比例 numeric(19,6)：率是「分母为 0 时无意义」的量，分母为 0 存 NULL 而不是 0 ——
--     0 会被读成「差异率 0%」，那是谎报。
--   时间 timestamptz：归因时刻、提交、审批、入账四个时刻都由应用层以 OffsetDateTime
--   绑定并写 ?::timestamptz，且 cast 一律不落在绑定序列末位（V59 同一条纪律）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 补一条复合唯一键：让「明细行确实属于这张盘点单」可由外键表达
-- ---------------------------------------------------------------------------
-- 归因表同时持有 sheet_id 与 line_id。两个 uuid 各自都对，但没有这一条，
-- 「A 盘点单的归因指向 B 盘点单的一行」在库里是合法的 —— 而那会让报告把两个单子的
-- 差异混在一起算。只加一个索引，不改 V59 的表结构与既有列。
CREATE UNIQUE INDEX stocktake_line_sheet_identity ON stocktake_line(tenant_id,sheet_id,id);

-- ---------------------------------------------------------------------------
-- 1. stocktake_variance_attribution · 差异归因（谁、为什么、怎么处理）
-- ---------------------------------------------------------------------------
-- 一行 = 一条差异明细**当前**的一条未结处置。差异本身不在这里（见文件头第一节），
-- 这里存的是处置与留痕。
CREATE TABLE stocktake_variance_attribution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sheet_id uuid NOT NULL,
  -- 指向 stocktake_line 的复合外键：顺带保证这一行真的属于这张盘点单。
  line_id uuid NOT NULL,

  -- 差异原因（五类，见文件头第二节）。
  reason varchar(16) NOT NULL CHECK(reason IN
    ('SHRINKAGE','MISSHIP','THEFT','UNRECORDED','TIMING')),

  -- 处理决定（三选）。三者的后果完全不同，不是一个「备注」字段。
  decision varchar(16) NOT NULL CHECK(decision IN ('ADJUST','RECOUNT','IGNORE')),

  -- 归因说明。**三类都必须写**，且 IGNORE 至少 4 个字符：
  --   ADJUST  写「为什么该调整」（凭证/说明）
  --   RECOUNT 写「为什么要重新盘」（比如怀疑录错、怀疑库位串了）
  --   IGNORE  写「为什么放过这条差异」—— 少一条理由，忽略就成了消音器
  note varchar(500) NOT NULL CHECK(btrim(note)<>''),
  CHECK(decision<>'IGNORE' OR length(btrim(note))>=4),

  -- 归因生成的调整单（ADJUST 决定才有；RECOUNT/IGNORE 为 NULL）。
  -- 刻意**不**建外键到调整单表：调整单表在本文件第 2 节才建，且循环外键会让删除与
  -- 插入顺序互相依赖。这里改由 3.5 的触发器保证「挂上去的单必须是 ADJUST 决定且未结」。
  adjustment_id uuid,

  attributed_by_user_id uuid NOT NULL,
  attributed_at timestamptz NOT NULL DEFAULT now(),

  -- 结案时刻与结案方式：
  --   RECOUNTED 复盘完成（这一行退回未录，可以重新盘）
  --   POSTED    已过账入账（终态：同一条差异不允许再入账一次）
  --   IGNORED   已被忽略（终态：不允许「先忽略再改口」，见 4.5）
  --   REJECTED  调整单被驳回，释放出来可以重新归因
  --   CANCELLED 调整单被作废，释放出来可以重新归因
  -- NULL = 未结。4.5 的部分唯一索引保证「每行至多一条未结归因」。
  -- REJECTED / CANCELLED 之所以与 POSTED / IGNORED 分开：前者是可回到桌面的，
  -- 后者是终态 —— 让「已入账」与「可重做」用同一个词，就没法用 CHECK 区分了。
  resolved_at timestamptz,
  resolved_by varchar(16) CHECK(resolved_by IN
    ('RECOUNTED','POSTED','IGNORED','REJECTED','CANCELLED')),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,sheet_id,line_id) REFERENCES stocktake_line(tenant_id,sheet_id,id),
  -- 结案时刻与结案方式同进同出：只写其一意味着「结了但不知道为什么结」。
  CHECK((resolved_at IS NULL) = (resolved_by IS NULL)),
  -- 只能从「调整」决定挂到调整单上。反过来（ADJUST 必须已挂单）**不**成立：
  -- 归因在前、建单在后，「已归因为调整、还没进任何单据」是一个合法且常见的中间态
  -- —— 调整单还没拟好就先把差异判定了。把中间态也禁掉，那只能逼人乱填单号。
  CHECK(adjustment_id IS NULL OR decision='ADJUST')
);
-- 每行至多一条未结归因（复盘后可以重新归因，见文件头第二节）。
CREATE UNIQUE INDEX stocktake_variance_attribution_open
  ON stocktake_variance_attribution(tenant_id,line_id) WHERE resolved_at IS NULL;
-- 差异工作台主查询：这张单里「还没归因」的有差异行。
CREATE INDEX stocktake_variance_attribution_sheet
  ON stocktake_variance_attribution(tenant_id,sheet_id,reason,decision);
-- 报告按原因分组统计走这条。
CREATE INDEX stocktake_variance_attribution_reason
  ON stocktake_variance_attribution(tenant_id,sheet_id,reason)
  WHERE resolved_at IS NULL;
-- 反查「这张调整单由哪些归因生成」。
CREATE INDEX stocktake_variance_attribution_adjustment
  ON stocktake_variance_attribution(tenant_id,adjustment_id) WHERE adjustment_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. stocktake_variance_adjustment · 调整单（头）
-- ---------------------------------------------------------------------------
-- 状态机见文件头第三节。**POSTED 只能从 APPROVED 到达**由 3.1 的触发器钉死。
CREATE TABLE stocktake_variance_adjustment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sheet_id uuid NOT NULL,
  -- 单号：ADJ-ST-<UTC 日期>-<8 位十六进制>，服务端派生，不接受调用方自填。
  code varchar(32) NOT NULL,

  status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','POSTED','CANCELLED')),

  -- 单据级说明（凭证/备注）。入账分录的 notes 引用它。
  note varchar(500) NOT NULL DEFAULT '',

  -- 提交人 / 提交时刻
  submitted_by_user_id uuid,
  submitted_at timestamptz,
  -- 审批人 / 审批时刻 / 审批意见（驳回时必填，见 CHECK）
  decided_by_user_id uuid,
  decided_at timestamptz,
  decision_note varchar(500),
  -- 入账人 / 入账时刻
  posted_by_user_id uuid,
  posted_at timestamptz,
  -- 作废人 / 作废时刻
  cancelled_by_user_id uuid,
  cancelled_at timestamptz,

  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 单号在租户内唯一：撞号会让「这次入账对应哪张单」失去唯一答案。
  UNIQUE(tenant_id,code),
  FOREIGN KEY(tenant_id,sheet_id) REFERENCES stocktake_sheet(tenant_id,id),
  CHECK(btrim(code)<>''),

  -- 三个动作的字段各自同进同出，理由与归因表同一条：
  -- 「谁批的」缺一半就是「有一半动作没人负责」。
  CHECK((submitted_at IS NULL) = (submitted_by_user_id IS NULL)),
  CHECK((decided_at IS NULL) = (decided_by_user_id IS NULL)),
  CHECK((posted_at IS NULL) = (posted_by_user_id IS NULL)),
  CHECK((cancelled_at IS NULL) = (cancelled_by_user_id IS NULL)),

  -- 驳回必须写意见：只给一个 REJECTED 状态，提交的人无法知道该改什么。
  CHECK(status<>'REJECTED' OR (decision_note IS NOT NULL AND btrim(decision_note)<>'')),
  -- 过账必须有审批痕迹：POSTED 的单据不可能没有审批人。
  CHECK(status<>'POSTED' OR (decided_at IS NOT NULL AND posted_at IS NOT NULL))
);

CREATE INDEX stocktake_variance_adjustment_sheet
  ON stocktake_variance_adjustment(tenant_id,sheet_id,created_at DESC);
-- 审批工作台：谁在等我批。
CREATE INDEX stocktake_variance_adjustment_pending
  ON stocktake_variance_adjustment(tenant_id,status,submitted_at)
  WHERE status='PENDING_APPROVAL';
-- 「这个差异行是不是已经被某张单据吃掉了」的探测走这条。
CREATE INDEX stocktake_variance_adjustment_status
  ON stocktake_variance_adjustment(tenant_id,status);

-- ---------------------------------------------------------------------------
-- 3. stocktake_variance_adjustment_line · 调整单行（差异 → 一次待入账的带符号数量）
-- ---------------------------------------------------------------------------
-- 一行 = 一条差异明细的一次性数量快照。维度与可读名同样冻结：盘点之后 SKU 与库位
-- 都可能被改名或停用，而调整单要能回看「当时调的是哪一件」。
CREATE TABLE stocktake_variance_adjustment_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  adjustment_id uuid NOT NULL,
  sheet_id uuid NOT NULL,
  line_id uuid NOT NULL,

  -- 来源盘点明细（复合外键顺带保证行属于这张单）。
  FOREIGN KEY(tenant_id,sheet_id,line_id) REFERENCES stocktake_line(tenant_id,sheet_id,id),

  -- 目标：bucket（库位/批次事实单位）与 balance（仓级可用量决策单位）。
  -- balance_id 取自 stock_bucket.balance_id —— 盘点冻结的是 bucket，而分录挂在
  -- balance 上，两者由 V2 的 balance_id 关联，不猜、不重算。
  bucket_id uuid NOT NULL,
  balance_id uuid NOT NULL,

  -- 定位维度与可读名快照
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL,
  warehouse_code varchar(64) NOT NULL,
  location_id uuid NOT NULL,
  location_code varchar(64) NOT NULL,
  lot_id uuid,
  lot_code varchar(64) NOT NULL DEFAULT '',
  quality_status_code varchar(32) NOT NULL,

  -- **带符号数量快照** = 归因那一刻的 variance（正=盘盈，负=盘亏）。
  -- 刻意不接受请求参数传入：由服务从 stocktake_line.variance 读出后写入，
  -- 让人手填一个「调整量」等于允许调整量与差异量对不上，而那正是虚增/虚减的入口。
  qty_delta numeric(19,4) NOT NULL CHECK(qty_delta<>0),

  reason varchar(16) NOT NULL CHECK(reason IN
    ('SHRINKAGE','MISSHIP','THEFT','UNRECORDED','TIMING')),
  note varchar(500) NOT NULL DEFAULT '',

  -- 入账痕迹。posted_at 非空 = 这一行已经进过账。
  posted_at timestamptz,
  posted_by_user_id uuid,
  -- 对应的库存分录 id（3.2 的入账闸门在写分录时回填）。
  entry_id uuid,
  -- 对应的业务单 id（inventory_transaction.id）。
  transaction_id uuid,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,adjustment_id,line_id),
  CHECK((posted_at IS NULL) = (posted_by_user_id IS NULL)),
  -- 入账痕迹三件套：要么都没有，要么全有（只记时刻不记人 = 追责断链）。
  CHECK(posted_at IS NULL OR (entry_id IS NOT NULL AND transaction_id IS NOT NULL))
);

-- 调整单明细页主查询。
CREATE INDEX stocktake_variance_adjustment_line_sheet
  ON stocktake_variance_adjustment_line(tenant_id,adjustment_id);
-- 「这条差异有没有被入账过」：与 3.4 的部分唯一索引配合。
CREATE INDEX stocktake_variance_adjustment_line_posted
  ON stocktake_variance_adjustment_line(tenant_id,line_id) WHERE posted_at IS NOT NULL;

-- ===========================================================================
-- 4. DB 级行为约束
-- ===========================================================================

-- 4.1 状态机：合法的转移白名单 + POSTED 终态 + 身份列不可改。
--
-- 为什么必须在 DB：盘点差异最终要变成调整单并追责到人，而「这张单是怎么从 DRAFT
-- 一路变成 POSTED 的」正是最容易被质疑的环节。白名单写死之后，「跳过审批直接过账」
-- 是一条会当场报错的 SQL，而不是一句需要信任应用代码的承诺。
--
-- 允许的转移（其余一律拒绝）：
--   DRAFT           → PENDING_APPROVAL（提交）、CANCELLED（作废）
--   PENDING_APPROVAL→ APPROVED（批准）、REJECTED（驳回）、CANCELLED（撤回）
--   REJECTED        → DRAFT（改完重提）、CANCELLED
--   APPROVED        → POSTED（入账）、CANCELLED
--   POSTED          → （无）终态
--   CANCELLED       → （无）终态
CREATE FUNCTION guard_variance_adjustment_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- 身份列不可改：改了就等于把一张单据换成另一张，而账已经按旧的记过了。
  IF (NEW.tenant_id,NEW.sheet_id,NEW.code,NEW.created_by_user_id,NEW.created_at)
     IS DISTINCT FROM
     (OLD.tenant_id,OLD.sheet_id,OLD.code,OLD.created_by_user_id,OLD.created_at) THEN
    RAISE EXCEPTION '调整单的租户/盘点单/单号/创建痕迹不可修改：单据身份变了，'
      '而账可能已经按旧身份记过' USING ERRCODE='23514';
  END IF;

  IF NEW.status = OLD.status THEN
    -- 同状态更新只允许补齐痕迹字段（见下方痕迹守恒），不放行内容改写。
    NULL;
  ELSIF NOT (
      (OLD.status='DRAFT'            AND NEW.status IN ('PENDING_APPROVAL','CANCELLED')) OR
      (OLD.status='PENDING_APPROVAL'  AND NEW.status IN ('APPROVED','REJECTED','CANCELLED')) OR
      (OLD.status='REJECTED'         AND NEW.status IN ('DRAFT','CANCELLED')) OR
      (OLD.status='APPROVED'         AND NEW.status IN ('POSTED','CANCELLED'))) THEN
    RAISE EXCEPTION '调整单状态不能从 % 走到 %：状态机只允许 '
      'DRAFT→PENDING_APPROVAL→APPROVED→POSTED，以及 PENDING_APPROVAL→REJECTED、'
      '任一未过账状态→CANCELLED、REJECTED→DRAFT。'
      '**未审批的调整单不能过账** —— 差异不直接改余额，必须经调整单与审批。',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;

  -- 痕迹守恒：已过账/已审批/已提交的单据不能把痕迹抹掉。
  IF OLD.submitted_at IS NOT NULL AND NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN
    RAISE EXCEPTION '提交痕迹不可改写' USING ERRCODE='23514';
  END IF;
  IF OLD.decided_at IS NOT NULL AND NEW.decided_at IS DISTINCT FROM OLD.decided_at THEN
    RAISE EXCEPTION '审批痕迹不可改写：已审批的单据不允许换审批人或审批时间' USING ERRCODE='23514';
  END IF;
  IF OLD.posted_at IS NOT NULL AND NEW.posted_at IS DISTINCT FROM OLD.posted_at THEN
    RAISE EXCEPTION '入账痕迹不可改写：已过账的调整单是账的一部分，冲正必须写新单据' USING ERRCODE='23514';
  END IF;
  IF OLD.cancelled_at IS NOT NULL AND NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at THEN
    RAISE EXCEPTION '作废痕迹不可改写' USING ERRCODE='23514';
  END IF;
  -- 过账必须真的有审批痕迹（不是只改 status 就能伪造出「已审批已过账」）。
  IF NEW.status='POSTED' AND NEW.decided_at IS NULL THEN
    RAISE EXCEPTION '过账的调整单必须先有审批痕迹' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER stocktake_variance_adjustment_state
  BEFORE UPDATE ON stocktake_variance_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_variance_adjustment_state();

-- 4.2 已过账的调整单**不可删除**：账已经按它记过了，删单等于账没了凭据。
CREATE FUNCTION guard_variance_adjustment_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '调整单不允许删除：它可能已经过账入账，删掉它等于账没了凭据。'
    ' 要作废一张单请走「作废」，那会留下痕迹。' USING ERRCODE='23514';
END $$;
CREATE TRIGGER stocktake_variance_adjustment_no_delete
  BEFORE DELETE ON stocktake_variance_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_variance_adjustment_no_delete();

-- 4.3 **入账闸门（核心）**：盘点差异的分录只能挂在已审批的调整单上。
--
-- inventory_entry 的 CHECK 只保证「一条分录非零」，不保证「它凭什么存在」。
-- 这里补上：凡是 ref_type='STOCKTAKE_VARIANCE' 的业务单，它引用的调整单必须已经
-- APPROVED 或 POSTED。
--
-- 结果：**未审批（DRAFT / PENDING_APPROVAL / REJECTED / CANCELLED）的调整单在数据库
-- 层拿不到任何一条库存分录**。这就是蓝图红线「差异不直接改余额，必须经调整单与审批」
-- 的可执行形式 —— 拿到连接直接 INSERT 也绕不过去。
--
-- 对其他 ref_type 一律放行：收货/出库/调拨等既有单据不属于盘点差异，
-- 本模块没有资格给它们加规则（那是跨模块越界）。
CREATE FUNCTION guard_variance_entry_approved() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t record;
BEGIN
  SELECT ref_type, ref_id, tenant_id INTO t
    FROM inventory_transaction tx
   WHERE tx.tenant_id = NEW.tenant_id AND tx.id = NEW.transaction_id;
  IF t IS NULL THEN
    RAISE EXCEPTION '库存分录引用了不存在的业务单 %', NEW.transaction_id USING ERRCODE='23503';
  END IF;
  IF t.ref_type <> 'STOCKTAKE_VARIANCE' THEN
    RETURN NEW;   -- 非盘点差异业务单：不管（见本函数注释）
  END IF;
  IF NOT EXISTS (SELECT 1 FROM stocktake_variance_adjustment a
                  WHERE a.tenant_id = t.tenant_id AND a.id = t.ref_id
                    AND a.status IN ('APPROVED','POSTED')) THEN
    RAISE EXCEPTION '调整单 % 尚未审批通过，不允许写入库存分录：盘点差异不直接改余额，'
      '必须先生成调整单并经审批。当前状态不是 APPROVED/POSTED。', t.ref_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER stocktake_variance_entry_approved
  BEFORE INSERT ON inventory_entry
  FOR EACH ROW EXECUTE FUNCTION guard_variance_entry_approved();

-- 4.4 入账数量守恒：Σ 分录 on_hand_delta == Σ 调整单行 qty_delta。
--
-- 延迟约束触发器：入账是「写业务单 → 写若干条分录 → 改余额」三步，必须在**提交前**
-- 一次性核对，中途核对会对着半成品报错。
--
-- 作用：不许少记、不许多记、不许只入一部分就过账。少记的那部分差异会永远挂着，
-- 而账面已经动过 —— 那是对不上账的经典形态。
CREATE FUNCTION guard_variance_posting_sum() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t record; expected numeric(19,4); actual numeric(19,4);
BEGIN
  SELECT ref_type, ref_id, tenant_id INTO t
    FROM inventory_transaction tx
   WHERE tx.tenant_id = NEW.tenant_id AND tx.id = NEW.transaction_id;
  IF t IS NULL OR t.ref_type <> 'STOCKTAKE_VARIANCE' THEN RETURN NULL; END IF;

  SELECT coalesce(sum(qty_delta),0) INTO expected
    FROM stocktake_variance_adjustment_line
   WHERE tenant_id = t.tenant_id AND adjustment_id = t.ref_id;
  SELECT coalesce(sum(e.on_hand_delta),0) INTO actual
    FROM inventory_entry e
   WHERE e.tenant_id = NEW.tenant_id AND e.transaction_id = NEW.transaction_id;
  IF actual IS DISTINCT FROM expected THEN
    RAISE EXCEPTION '盘点差异入账数量不平：调整单 % 的行合计是 %，实际写进分录的是 %。'
      ' 入账必须与调整单明细逐行相等，不允许少记或多记。', t.ref_id, expected, actual
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER stocktake_variance_posting_sum
  AFTER INSERT ON inventory_entry
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_variance_posting_sum();

-- 4.5 一条差异只能入账一次，且忽略过的行不允许被翻案。
--
-- (a) 对已过账的 stocktake_line_id 建部分唯一索引：同一条差异明细无论被多少张调整单
--     引用，只有第一条能过账。重复入账 = 同一批货被扣两次。
-- (b) BEFORE INSERT 触发器：如果这一行曾经被 IGNORE 掉过，拒绝再次归因。
--     「先忽略、再偷偷改成调整」是消音器的标准变体，这里直接堵掉 —— 要复盘就
--     走 RECOUNT（它会留下痕迹并且是真的重新去数）。
CREATE UNIQUE INDEX stocktake_variance_adjustment_line_once
  ON stocktake_variance_adjustment_line(tenant_id,line_id) WHERE posted_at IS NOT NULL;

CREATE FUNCTION guard_variance_attribution_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ignored boolean; posted boolean; open_count bigint;
BEGIN
  SELECT EXISTS(SELECT 1 FROM stocktake_variance_attribution a
                 WHERE a.tenant_id=NEW.tenant_id AND a.line_id=NEW.line_id
                   AND a.decision='IGNORE' AND a.resolved_by='IGNORED')
    INTO ignored;
  IF ignored THEN
    RAISE EXCEPTION '这条差异已被「忽略」处理，不能再归因或入账：忽略是留痕的终态决定，'
      '不允许先忽略再改口。要重新处理请对该行走「复盘」，让这一行回到未录状态重新盘。'
      USING ERRCODE='23514';
  END IF;
  SELECT EXISTS(SELECT 1 FROM stocktake_variance_adjustment_line l
                 WHERE l.tenant_id=NEW.tenant_id AND l.line_id=NEW.line_id AND l.posted_at IS NOT NULL)
    INTO posted;
  IF posted THEN
    RAISE EXCEPTION '这条差异已经过账入账，不能再归因：同一条差异被入账两次等于把账扣两遍。'
      '要冲正请发起新的调整单。' USING ERRCODE='23514';
  END IF;
  -- 一行至多一条未结归因（与部分唯一索引同一条纪律，这里给出可读的错误）。
  SELECT count(*) INTO open_count FROM stocktake_variance_attribution a
   WHERE a.tenant_id=NEW.tenant_id AND a.line_id=NEW.line_id AND a.resolved_at IS NULL;
  IF open_count > 0 THEN
    RAISE EXCEPTION '这条差异已有未结的归因，请先处理完那一条（提交审批 / 复盘 / 忽略）'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER stocktake_variance_attribution_terminal
  BEFORE INSERT ON stocktake_variance_attribution
  FOR EACH ROW EXECUTE FUNCTION guard_variance_attribution_terminal();

-- 4.6 归因只允许结案，不允许改口：已结案的归因行不可修改。
--
-- 归因是「谁在什么时候把这条差异判成什么原因」的事实。改它等于改审计记录。
-- 要改判请对同一行新做一次归因（未结的会被结案后再来），而那两次都在审计里。
CREATE FUNCTION guard_variance_attribution_resolved() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.resolved_at IS NOT NULL THEN
    RAISE EXCEPTION '已结案的归因不可修改：归因是「谁在什么时候把这条差异判成什么原因」的事实，'
      '改它等于改审计记录。要改判请对该行重新归因（两次归因都会留痕）。' USING ERRCODE='23514';
  END IF;
  -- 唯一允许的「内容改动」：把一张还没进任何单据的调整决定挂到刚建好的调整单上
  -- （OLD 必须是空 —— 已经挂过单据的归因不许改挂，那是把一条差异挪到另一张单上）。
  IF OLD.adjustment_id IS NULL AND NEW.adjustment_id IS NOT NULL
     AND (NEW.id,NEW.tenant_id,NEW.sheet_id,NEW.line_id,NEW.reason,NEW.decision,NEW.note,
          NEW.attributed_by_user_id,NEW.attributed_at,NEW.created_at)
         IS NOT DISTINCT FROM
         (OLD.id,OLD.tenant_id,OLD.sheet_id,OLD.line_id,OLD.reason,OLD.decision,OLD.note,
          OLD.attributed_by_user_id,OLD.attributed_at,OLD.created_at)
     AND NEW.decision = 'ADJUST' THEN
    RETURN NEW;
  END IF;
  IF (NEW.id,NEW.tenant_id,NEW.sheet_id,NEW.line_id,NEW.reason,NEW.decision,NEW.note,
      NEW.adjustment_id,NEW.attributed_by_user_id,NEW.attributed_at,NEW.created_at)
     IS DISTINCT FROM
     (OLD.id,OLD.tenant_id,OLD.sheet_id,OLD.line_id,OLD.reason,OLD.decision,OLD.note,
      OLD.adjustment_id,OLD.attributed_by_user_id,OLD.attributed_at,OLD.created_at) THEN
    RAISE EXCEPTION '未结案的归因只能被结案，不能改内容：改原因或改决定等于事后重写判定。'
      '请结案后对同一行重新归因。' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER stocktake_variance_attribution_resolved
  BEFORE UPDATE ON stocktake_variance_attribution
  FOR EACH ROW EXECUTE FUNCTION guard_variance_attribution_resolved();

-- 4.7 归因不删除：审计凭据。
CREATE FUNCTION guard_variance_attribution_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '归因记录不允许删除：它是差异追责的第一手凭据。' USING ERRCODE='23514';
END $$;
CREATE TRIGGER stocktake_variance_attribution_no_delete
  BEFORE DELETE ON stocktake_variance_attribution
  FOR EACH ROW EXECUTE FUNCTION guard_variance_attribution_no_delete();

-- 4.8 已过账的调整单行不可改数量与定位：过账之后它就是账的一部分。
CREATE FUNCTION guard_variance_adjustment_line_posted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.posted_at IS NOT NULL THEN
    RAISE EXCEPTION '已过账的调整单行不可修改：它已经是库存分录的一部分，'
      '冲正必须写一张新单据（新的差异来源），而不是改旧行。' USING ERRCODE='23514';
  END IF;
  -- 行与单据的绑定不可改：换了 adjustment_id 等于把一行从一张单挪到另一张。
  IF (NEW.tenant_id,NEW.adjustment_id,NEW.sheet_id,NEW.line_id,NEW.bucket_id,NEW.balance_id,
      NEW.qty_delta,NEW.reason,NEW.created_at)
     IS DISTINCT FROM
     (OLD.tenant_id,OLD.adjustment_id,OLD.sheet_id,OLD.line_id,OLD.bucket_id,OLD.balance_id,
      OLD.qty_delta,OLD.reason,OLD.created_at) THEN
    RAISE EXCEPTION '调整单行的数量/定位不可修改：入账数量必须与归因那一刻的差异一致，'
      '改它等于让账与差异对不上' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER stocktake_variance_adjustment_line_posted
  BEFORE UPDATE ON stocktake_variance_adjustment_line
  FOR EACH ROW EXECUTE FUNCTION guard_variance_adjustment_line_posted();

-- 4.9 调整单行不删除（未过账的也不给删）：删行会让「这条差异被判为要调整」这件事消失，
-- 而归因仍指向这张单，报告就会算出一个解释不了的差额。
CREATE FUNCTION guard_variance_adjustment_line_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '调整单行不允许删除：归因仍指向这张单，删行会让差异处理凭空少一条。'
    '作废整张单请走「作废」。' USING ERRCODE='23514';
END $$;
CREATE TRIGGER stocktake_variance_adjustment_line_no_delete
  BEFORE DELETE ON stocktake_variance_adjustment_line
  FOR EACH ROW EXECUTE FUNCTION guard_variance_adjustment_line_no_delete();

-- ---------------------------------------------------------------------------
-- 5. stocktake_variance_audit · 差异处理审计（谁归因、谁提交、谁审批、谁入账）
-- ---------------------------------------------------------------------------
-- append-only 事实表：谁在什么时候把哪条差异判成什么原因、谁提交、谁批准、谁入账。
-- 「盘盈盘亏入账并可追责」里「可追责」三个字就落在这张表上。
--
-- 为什么不存「归因表 + 调整单 + 分录」三处就够了：归因表只留**未结**那条的当前状态，
-- 复盘后重新归因会覆盖掉「上一次判成什么」。审计表按时间追加，两次判定都在。
CREATE TABLE stocktake_variance_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sheet_id uuid NOT NULL,
  line_id uuid,
  adjustment_id uuid,

  -- 动作。固定枚举而不是自由文本：自由文本让「这个动作算不算入账」变成读日志的人自己判断。
  action varchar(24) NOT NULL CHECK(action IN
    ('ATTRIBUTED','ADJUSTMENT_CREATED','SUBMITTED','APPROVED','REJECTED',
     'POSTED','RECOUNTED','IGNORED','CANCELLED')),

  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  -- 人读得懂的一句话：为什么、批了什么、入了多少账。
  detail text NOT NULL CHECK(btrim(detail)<>''),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,sheet_id) REFERENCES stocktake_sheet(tenant_id,id)
);
-- 审计时间线主查询（一张盘点单的完整处理过程）。
CREATE INDEX stocktake_variance_audit_sheet
  ON stocktake_variance_audit(tenant_id,sheet_id,occurred_at,id);
-- 「这张调整单上发生过什么」。
CREATE INDEX stocktake_variance_audit_adjustment
  ON stocktake_variance_audit(tenant_id,adjustment_id,occurred_at)
  WHERE adjustment_id IS NOT NULL;

-- append-only：审计只能追加。
CREATE FUNCTION guard_variance_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'stocktake_variance_audit 是 append-only：不允许 %，'
    '差异追责依赖未被改写的时间线。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER stocktake_variance_audit_append_only
  BEFORE UPDATE OR DELETE ON stocktake_variance_audit
  FOR EACH ROW EXECUTE FUNCTION guard_variance_audit_append_only();

-- ===========================================================================
-- 6. 多租户与授权（与 V1/V2/V54/V56/V59 同一套写法）
-- ===========================================================================
-- 四张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['stocktake_variance_attribution','stocktake_variance_adjustment',
                             'stocktake_variance_adjustment_line','stocktake_variance_audit'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  stocktake_variance_attribution, stocktake_variance_adjustment,
  stocktake_variance_adjustment_line, stocktake_variance_audit TO warehouse_app;
-- 归因、调整单、调整单行、审计全部是审计凭据：不给 DELETE（触发器再收一道）。
REVOKE DELETE ON
  stocktake_variance_attribution, stocktake_variance_adjustment,
  stocktake_variance_adjustment_line, stocktake_variance_audit FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54/V56/V59 同口径）。
REVOKE ALL ON
  stocktake_variance_attribution, stocktake_variance_adjustment,
  stocktake_variance_adjustment_line, stocktake_variance_audit FROM starcon_app;
