-- 11.4.1.1 平台纠纷列表（卷 11 售后与退货 / L3 11.4 平台纠纷与客诉的第一格）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、速卖通纠纷）：类型、金额、平台时限、
--         举证要求、我方立场、结果记录。
--   输入：平台纠纷事件/同步；责任判定；举证材料。
--   输出：纠纷单 + 时效倒计时 + 举证清单。
--   关系：上游 = 2 平台同步、15 客服；下游 = 11.4.1.2 申诉、12 财务（争议金额影响结算）。
--   **平台纠纷有严格举证时限**，超期自动判败，是硬性时效要求。
--
-- 归属：**trade 服务**（卷 11 后端主责服务 = trade + finance + warehouse，取列表第一项 = trade）。
-- 同族的 11.2.3.3 供应商索赔也在 trade（V188），本模块与它是同一族「争议 / 索赔」单据。
--
-- 本迁移**只新建四张表与触发器、零 ALTER 零 DROP 既有表**：
--   * 平台主数据在 channel 独立库 ⇒ **禁止跨服务 FK / 跨库视图**：
--     平台码以**逻辑引用 + 展示快照**两列落库（platform_code + platform_name），
--     平台码形状用 CHECK 钉死（照抄 catalog V145 的 `^[A-Z0-9][A-Z0-9_-]{0,31}$`）。
--   * 上游 2 平台同步（channel）**没有纠纷专用端点**（已交付的是平台目录 / 连接 / 店铺 /
--     同步任务 / 同步运行 / 回写 / webhook / 增量补拉）⇒ 本模块**不假装**有个推送接口，
--     纠纷由**本模块自己的建单入口**登记，平台来源信息以快照落库。
--   * 上游 15 客服（crm）**未开发客服工单 / 客户模块** ⇒ 本模块不引用任何 crm 表。
--   * 下游 12 财务（finance 服务）**当前不存在**（backend/services/ 下只有
--     identity / catalog / channel / warehouse / trade / crm）⇒ 「争议金额影响结算」
--     以 **trade 侧自有事实记录**落地，等 finance 落地后经授权 API / event 衔接。
--
-- ===========================================================================
-- 一、「超期自动判败」怎么落成结构（加粗句的落点）
-- ===========================================================================
--
-- 蓝图加粗句「**平台纠纷有严格举证时限，超期自动判败，是硬性时效要求**」。
-- 它必须落成**结构**，而不是落成一段提示文案。本模块用**四层**把它钉死：
--
--   ① **截止时刻是真实的 timestamptz 列**：`evidence_deadline timestamptz NOT NULL`。
--      不是文本，不是「7 天」这种相对描述 —— 相对描述在写下的那一刻就开始过期。
--
--   ② **倒计时是派生态**：库里**没有**「剩余小时数」列（那是一列会自己过期的假数据）。
--      剩余时间由 `evidence_deadline - now()` 在**读取时刻**算出。
--
--   ③ **超期自动判败由 DB 侧强制**（守卫① `guard_platform_dispute_deadline`）：
--      只要 `evidence_deadline <= now()` 且**尚未提交举证**，纠纷就被**强制**落成
--      `RESOLVED_LOST` + `resolution_code='DEADLINE_EXPIRED'` ——
--      这不是「服务层读到过期就返回个过期状态」那种软判定，而是
--      **任何一次写入都改不掉这个结论**（绕过服务层直连 SQL 也一样）。
--      触发器挂在 `BEFORE INSERT OR UPDATE` 上 ⇒ 连「顺手更新一下备注」都会触发判负。
--
--   ④ **超期后不可再提交举证**（守卫① 同一函数的前半段 + 守卫② 举证清单守卫）：
--      - `evidence_submitted_at > evidence_deadline` ⇒ 直接 **RAISE**（写不进去）；
--      - 举证清单条目在超期后**不可再改**（守卫②）。
--      ⇒ 「先拖到超期、再补一份材料」在任何 SQL 路径下都不存在。
--
-- ⚠️ **举证时限只能收紧、不能放宽**（守卫③）：
--   平台的截止时刻是**既定事实**，放宽它等于给自己续命。
--   `NEW.evidence_deadline > OLD.evidence_deadline` ⇒ 直接 RAISE。
--
-- ⚠️ **「举证提交」与「平台裁决」是两件事**：提交了举证就**不再受截止时刻约束**
--   （`evidence_submitted_at` 非空 ⇒ 守卫① 的判负分支不触发），
--   之后由平台裁决落地 `RESOLVED_WON` / `RESOLVED_LOST`。
--   否则「按时提交了举证却因为超期被判负」就是另一条假事实。
--
-- ===========================================================================
-- 二、为什么「争议金额影响结算」不写 finance
-- ===========================================================================
--
-- 蓝图关系句写的是「下游 = … 12 财务（争议金额影响结算）」，
-- 而 **finance 服务当前不存在**。按 AGENTS.md 模块边界纪律，
-- **禁止跨服务 SQL / 跨库 FK / 共享视图**，所以本模块照抄本仓先例
-- （11.2.3.3 的 `supplier_claim_settlement`）落地为 **trade 侧自有事实记录**：
--
--   * `platform_dispute_settlement.requested_amount`（**登记**的结算影响金额）
--     与 `effective_amount`（**已生效**的金额）是**两列**；
--     **登记时 effective 恒为 0**。
--   * **表级 CHECK 钉死**（守卫⑤ + 表级约束）：
--       settlement_status <> 'CONFIRMED' ⇒ effective_amount = 0
--       settlement_status =  'CONFIRMED' ⇒ effective_amount = requested_amount
--                                       且 confirmed_by / confirmed_at / finance_reference 三样齐全
--   * 纠纷单头上的 `effective_amount` 与 `settlement_status` 由 DB 触发器
--     **从结算分录重算**（守卫④）⇒ 调用方直连 SQL 也写不进一个假的「已生效」。
--
-- ===========================================================================
-- 三、状态机
-- ===========================================================================
--
-- OPEN                已立案：纠纷已登记，尚未开始准备举证
-- EVIDENCE_PREPARING  举证准备中：举证清单在补
-- EVIDENCE_SUBMITTED  举证已提交：等待平台裁决
-- RESOLVED_WON        我方胜诉（终态）
-- RESOLVED_LOST       我方败诉（终态）—— **超期自动落到这里**
-- WITHDRAWN           我方撤诉（终态）
--
-- ⚠️ 三条流转约束（守卫⑥）：
--   ① 进入「举证准备中」必须**已有举证清单**（没有清单就没有举证要求）。
--   ② 进入「举证已提交」必须：**清单齐全**（必举证项一个都不能缺）+
--      **提交时刻不晚于截止时刻**（守卫① 再钉一道）。
--   ③ 终态**不可逆**：RESOLVED_WON / RESOLVED_LOST / WITHDRAWN 之后
--      **业务要素整行冻结**（守卫⑦）。
--      —— 但**结算分录仍然可以登记**（败诉后争议金额要进结算），
--         所以冻结只针对业务要素，`settlement_status` / `effective_amount` 不在冻结集内。
--
-- ===========================================================================
-- 四、举证清单的形状（蓝图输出句「纠纷单 + 时效倒计时 + 举证清单」）
-- ===========================================================================
--
-- `platform_dispute_evidence_item` 一行 = 一项**应举证材料**：
--   * `evidence_type` —— 材料类型（履约证明 / 物流轨迹 / 发票 / 商品照片 / …）；
--   * `required` —— 是否**必举**；
--   * `evidence_state` —— 三态：PENDING（还没看）/ PROVIDED（已具备）/ MISSING（确认缺失）。
--   * `missing_reason` —— **缺失项必须写清缺什么**（表级 CHECK 钉死）。
--
-- ⚠️ 这里落的是**清单事实**，不是材料本体，也不是申诉提交：
--   11.4.1.2 证据与申诉**未开发** ⇒ 本模块**不建申诉提交表、不调平台提交接口**。
--   「是否已具备」由人工/上游登记，本模块只保证「缺哪些、齐不齐」。
--
-- ===========================================================================
-- 五、类型与平台的对应（守卫⑧）
-- ===========================================================================
--
-- 蓝图点名的三种纠纷各自绑定一个平台：
--   A_TO_Z（Amazon A-to-Z）⇒ AMAZON
--   EBAY_DISPUTE（eBay 买家争议）⇒ EBAY
--   ALIEXPRESS_DISPUTE（速卖通纠纷）⇒ ALIEXPRESS
--   OTHER ⇒ 任意平台码
-- 「type='A_TO_Z' 但 platform_code='EBAY'」这种行在库里存不进去 ——
-- 事后没人说得清这条纠纷到底是哪家的规则判的。
--
-- ===========================================================================
-- 六、类目与命名
-- ===========================================================================
CREATE TABLE platform_dispute (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  dispute_no varchar(32) NOT NULL,

  -- ---- 平台快照（平台主数据在 channel 独立库 ⇒ 逻辑引用 + 展示快照）----
  platform_code varchar(32) NOT NULL
      CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  -- 平台名快照：登记那一刻看到的名字。平台改名是平台的事，这里存的是**当时的凭据**。
  platform_name varchar(128) NOT NULL CHECK(length(btrim(platform_name)) > 0),
  -- 平台侧纠纷单号：平台对这件纠纷的唯一编号，**必填**（事后要拿它去平台查）。
  platform_dispute_no varchar(128) NOT NULL CHECK(length(btrim(platform_dispute_no)) > 0),
  -- 平台订单号快照（平台纠纷挂在某个订单上）。
  platform_order_no varchar(128) NOT NULL DEFAULT '',

  -- ---- 纠纷类型（职责句「类型」）----
  dispute_type varchar(24) NOT NULL CHECK(dispute_type IN (
      'A_TO_Z','EBAY_DISPUTE','ALIEXPRESS_DISPUTE','OTHER'
  )),
  dispute_reason text NOT NULL DEFAULT '' CHECK(length(dispute_reason) <= 2000),

  -- ---- 金额：NUMERIC(19,4) + BigDecimal，禁浮点；一单一币种 ----
  dispute_currency char(3) NOT NULL CHECK(dispute_currency ~ '^[A-Z]{3}$'),
  dispute_amount numeric(19,4) NOT NULL CHECK(dispute_amount > 0),

  -- ---- 平台时限（职责句「平台时限」）：真实 timestamptz ----
  -- ⚠️ **没有**「剩余小时数」列：倒计时是读取时刻由截止时刻算出来的派生态。
  evidence_deadline timestamptz NOT NULL,

  -- ---- 我方立场（职责句「我方立场」）----
  our_position varchar(24) NOT NULL DEFAULT 'UNDETERMINED' CHECK(our_position IN (
      'ACCEPT','REJECT','PARTIAL_ACCEPT','NEGOTIATE','UNDETERMINED'
  )),
  position_note text NOT NULL DEFAULT '' CHECK(length(position_note) <= 2000),

  -- ---- 责任判定（蓝图输入句「责任判定」）----
  -- ⚠️ 平台纠纷**没有**对应的上游责任判定表（11.1.2.2 商品问题归因是退货链的），
  --   所以这里落的是**本模块自有的判定字段**，不跨库引任何表。
  liability_stage varchar(24) NOT NULL DEFAULT 'UNDETERMINED' CHECK(liability_stage IN (
      'MERCHANT_PRODUCT','MERCHANT_FULFILLMENT','LOGISTICS','BUYER','PLATFORM','UNDETERMINED'
  )),
  liability_note text NOT NULL DEFAULT '' CHECK(length(liability_note) <= 2000),

  -- ---- 状态机（第三节）----
  dispute_status varchar(24) NOT NULL CHECK(dispute_status IN (
      'OPEN','EVIDENCE_PREPARING','EVIDENCE_SUBMITTED',
      'RESOLVED_WON','RESOLVED_LOST','WITHDRAWN'
  )),
  -- 举证提交时刻：**超期不可提交**（守卫①）。
  evidence_submitted_at timestamptz,
  evidence_submitted_by uuid,
  evidence_submit_note text NOT NULL DEFAULT '' CHECK(length(evidence_submit_note) <= 2000),

  -- ---- 结果记录（职责句「结果记录」）----
  resolution_code varchar(24) CHECK(resolution_code IS NULL OR resolution_code IN (
      'DEADLINE_EXPIRED','PLATFORM_RULING_FOR','PLATFORM_RULING_AGAINST',
      'MUTUAL_AGREEMENT','WITHDRAWN_BY_US'
  )),
  resolution_note text NOT NULL DEFAULT '' CHECK(length(resolution_note) <= 2000),
  resolved_at timestamptz,
  resolved_by uuid,

  -- ---- 结算影响（trade 侧自有事实记录的汇总口径，见第二节）----
  -- ⚠️ effective_amount 在**财务确认之前恒为 0**。
  settlement_status varchar(24) NOT NULL DEFAULT 'NOT_APPLIED' CHECK(settlement_status IN (
      'NOT_APPLIED','PENDING_FINANCE_CONFIRM','APPLIED','FINANCE_REJECTED'
  )),
  effective_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(effective_amount >= 0),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- 最近一次改写这一行的人：留痕事件的「谁做的」从这一列取，
  -- 不必每个动作都另开一列（谁在什么时候把它推到哪一步 —— 这是它的全部职责）。
  updated_by uuid,

  -- 乐观锁：改状态都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, dispute_no),
  -- **同一个平台纠纷号只能登记一次**：重复登记会让同一笔争议在列表里出现两次，
  -- 两条记录各自倒计时、各自判负，事后没人说得清哪条是真的。
  UNIQUE(tenant_id, platform_code, platform_dispute_no),

  -- 结果记录三件套：败诉 / 胜诉 / 撤诉都必须留结果码与时刻。
  CHECK(dispute_status <> 'RESOLVED_LOST'
        OR (resolution_code IS NOT NULL AND resolved_at IS NOT NULL)),
  CHECK(dispute_status <> 'RESOLVED_WON'
        OR (resolution_code IN ('PLATFORM_RULING_FOR','MUTUAL_AGREEMENT')
            AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)),
  CHECK(dispute_status <> 'WITHDRAWN'
        OR (resolution_code = 'WITHDRAWN_BY_US'
            AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)),
  -- 「超期自动判败」**没有人** —— 判负是系统做的，不该挂一个操作员的名字上去。
  CHECK(resolution_code IS DISTINCT FROM 'DEADLINE_EXPIRED' OR resolved_by IS NULL),
  -- 已提交举证 ⇒ 必须有提交时刻与提交人。
  CHECK(dispute_status <> 'EVIDENCE_SUBMITTED'
        OR (evidence_submitted_at IS NOT NULL AND evidence_submitted_by IS NOT NULL)),
  -- 未提交举证 ⇒ 不得有提交时刻（两个口径不能各说各话）。
  CHECK(evidence_submitted_at IS NULL OR dispute_status <> 'OPEN'),
  -- **未确认的结算不得生效**：settlement_status 非 APPLIED 时生效金额恒为 0。
  CHECK(settlement_status <> 'APPLIED' OR effective_amount > 0),
  -- 已生效的金额不得超过争议金额本身。
  CHECK(effective_amount <= dispute_amount)
);

COMMENT ON TABLE platform_dispute IS
  '11.4.1.1 平台纠纷单：类型/金额/平台时限/举证要求/我方立场/结果记录；举证截止时刻为真实 timestamptz，超期由 DB 强制判负';

-- ===========================================================================
-- 二、举证清单（一行 = 一项应举证材料）
-- ===========================================================================
CREATE TABLE platform_dispute_evidence_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  dispute_id uuid NOT NULL
      REFERENCES platform_dispute(id) ON DELETE CASCADE,

  -- 序号由 DB 排（守卫②）：举证清单的先后顺序是「平台要求先交哪一样」的一部分。
  evidence_seq int NOT NULL CHECK(evidence_seq > 0),

  evidence_type varchar(32) NOT NULL CHECK(evidence_type IN (
      'ORDER_FULFILLMENT_PROOF','LOGISTICS_TRACKING_PROOF','INVOICE','PRODUCT_PHOTO',
      'PRODUCT_DESCRIPTION_SNAPSHOT','COMMUNICATION_LOG','PLATFORM_MESSAGE','OTHER'
  )),
  evidence_label varchar(200) NOT NULL DEFAULT '',

  -- 是否**必举**：非必举项缺失不挡提交，必举项缺失挡提交（守卫⑥）。
  required boolean NOT NULL DEFAULT true,

  -- 三态：PENDING（还没看）/ PROVIDED（已具备）/ MISSING（确认缺失）。
  evidence_state varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(evidence_state IN (
      'PENDING','PROVIDED','MISSING'
  )),
  evidence_note text NOT NULL DEFAULT '' CHECK(length(evidence_note) <= 2000),
  -- **缺失项必须写清缺什么**：只标「缺」而不说缺哪一样，提交前没人知道要补什么。
  missing_reason text NOT NULL DEFAULT '' CHECK(length(missing_reason) <= 2000),

  provided_at timestamptz,
  provided_by uuid,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, dispute_id, evidence_seq),

  -- 已具备 ⇒ 必须有具备时刻与具备人。
  CHECK(evidence_state <> 'PROVIDED'
        OR (provided_at IS NOT NULL AND provided_by IS NOT NULL)),
  -- 确认缺失 ⇒ 必须写缺失原因。
  CHECK(evidence_state <> 'MISSING' OR length(btrim(missing_reason)) > 0),
  -- 非「已具备」不许带具备时刻。
  CHECK(evidence_state = 'PROVIDED' OR provided_at IS NULL)
);

COMMENT ON TABLE platform_dispute_evidence_item IS
  '11.4.1.1 举证清单：应举证材料项 + 是否已具备 + 缺失项；本模块产出的清单事实，不上传材料、不提交申诉';

-- ===========================================================================
-- 三、结算影响分录（**trade 侧自有事实记录**，见第二节）
-- ===========================================================================
CREATE TABLE platform_dispute_settlement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  dispute_id uuid NOT NULL
      REFERENCES platform_dispute(id) ON DELETE CASCADE,

  settlement_currency char(3) NOT NULL CHECK(settlement_currency ~ '^[A-Z]{3}$'),

  -- 登记的结算影响金额（争议金额实际落到结算上的那部分）。
  requested_amount numeric(19,4) NOT NULL CHECK(requested_amount > 0),
  -- **已生效**的金额：财务确认之前**恒为 0**。
  effective_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(effective_amount >= 0),

  settlement_status varchar(24) NOT NULL CHECK(settlement_status IN (
      'PENDING_FINANCE_CONFIRM','CONFIRMED','REJECTED'
  )),

  -- 财务确认后回填的单据号：事后要答得出「扣的是哪一笔」。
  finance_reference varchar(128) NOT NULL DEFAULT '',
  confirmed_by uuid,
  confirmed_at timestamptz,
  rejected_by uuid,
  rejected_at timestamptz,
  reject_reason text NOT NULL DEFAULT '' CHECK(length(reject_reason) <= 1000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id, id),

  -- **「没有财务确认就没有生效」的三条 CHECK**（第二节）：
  --   未确认 ⇒ 生效金额恒为 0；
  --   已确认 ⇒ 生效金额 = 登记金额，且确认人 / 确认时刻 / 财务单据号齐全。
  CHECK(settlement_status <> 'CONFIRMED'
        OR (effective_amount = requested_amount
            AND confirmed_by IS NOT NULL
            AND confirmed_at IS NOT NULL
            AND length(btrim(finance_reference)) > 0)),
  CHECK(settlement_status <> 'PENDING_FINANCE_CONFIRM' OR effective_amount = 0),
  CHECK(settlement_status <> 'REJECTED'
        OR (effective_amount = 0 AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL))
);

COMMENT ON TABLE platform_dispute_settlement IS
  '11.4.1.1 结算影响分录：trade 侧自有事实记录；effective_amount 在财务确认前恒为 0（12 财务服务当前不存在）';

-- ===========================================================================
-- 四、留痕（追加式）
-- ===========================================================================
CREATE TABLE platform_dispute_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  dispute_id uuid NOT NULL,
  event_type varchar(32) NOT NULL CHECK(event_type IN (
      'CREATED','EVIDENCE_ITEM_ADDED','EVIDENCE_ITEM_UPDATED','EVIDENCE_ITEM_REMOVED',
      'EVIDENCE_PREPARING','EVIDENCE_SUBMITTED','RESOLVED_WON','RESOLVED_LOST','WITHDRAWN',
      'SETTLEMENT_REGISTERED','SETTLEMENT_CONFIRMED','SETTLEMENT_REJECTED','UPDATED'
  )),
  from_status varchar(24),
  to_status varchar(24),

  amount numeric(19,4),
  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

COMMENT ON TABLE platform_dispute_event IS
  '11.4.1.1 纠纷留痕：追加式，DB 触发器自动追加，运行角色 UPDATE/DELETE 已收回';

-- ===========================================================================
-- 五、DB 守卫（服务层校验可以绕开，触发器绕不开）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 守卫① guard_platform_dispute_deadline：**超期自动判败 + 超期不可提交举证**
-- ---------------------------------------------------------------------------
-- 这一段是本模块最要紧的结构（第一节 ③④）。
-- ⚠️ 触发器挂在 `BEFORE INSERT OR UPDATE`（不是 `UPDATE OF dispute_status`）：
--   那样**任何一次写入**都会经过它 —— 包括「顺手改一下备注」这种与状态无关的写入。
--   超期的纠纷没有「再等等看」这条路径。
CREATE FUNCTION guard_platform_dispute_deadline() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- ---- ① 超期后不可再提交举证 ----
  IF NEW.evidence_submitted_at IS NOT NULL
     AND NEW.evidence_submitted_at > NEW.evidence_deadline THEN
    RAISE EXCEPTION '举证提交时刻（%）晚于举证截止时刻（%），超期后不可再提交举证：dispute_id=%',
      NEW.evidence_submitted_at, NEW.evidence_deadline, NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  -- ---- ② 超期自动判败 ----
  --   已提交举证的（evidence_submitted_at 非空）**不再**受截止时刻约束：
  --   提交了就在等平台裁决，不该因为「过了截止时刻」被判负。
  IF NEW.evidence_submitted_at IS NULL
     AND NEW.evidence_deadline <= now()
     AND NEW.dispute_status IN ('OPEN','EVIDENCE_PREPARING') THEN
    NEW.dispute_status    := 'RESOLVED_LOST';
    NEW.resolution_code   := 'DEADLINE_EXPIRED';
    NEW.resolved_at       := now();
    NEW.resolved_by       := NULL;   -- 系统判负，没有操作员
    NEW.updated_at        := now();
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_deadline
  BEFORE INSERT OR UPDATE ON platform_dispute
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_deadline();

-- ---------------------------------------------------------------------------
-- 守卫② guard_platform_dispute_evidence_item：举证清单只在「可改窗口」里可改
-- ---------------------------------------------------------------------------
-- 可改窗口 = 纠纷在 OPEN / EVIDENCE_PREPARING **且** 还没超期。
-- 提交举证之后清单就冻结（提交给平台的那一份是快照）。
CREATE FUNCTION guard_platform_dispute_evidence_item() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(24);
  t_deadline timestamptz;
  t_submitted timestamptz;
BEGIN
  SELECT dispute_status, evidence_deadline, evidence_submitted_at
    INTO t_status, t_deadline, t_submitted
    FROM platform_dispute
   WHERE tenant_id = NEW.tenant_id AND id = NEW.dispute_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '纠纷单不存在或不属于当前租户：dispute_id=%', NEW.dispute_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status NOT IN ('OPEN','EVIDENCE_PREPARING') THEN
    RAISE EXCEPTION '纠纷单当前是「%」，举证清单已冻结（提交给平台的那一份是快照）：dispute_id=%',
      t_status, NEW.dispute_id USING ERRCODE = 'check_violation';
  END IF;

  -- **超期后不可再改举证清单**：改清单是「补材料」的第一步，超期了第一步就不该有。
  IF t_submitted IS NULL AND t_deadline <= now() THEN
    RAISE EXCEPTION '举证时限已过（截止 %），不能再修改举证清单：dispute_id=%',
      t_deadline, NEW.dispute_id USING ERRCODE = 'check_violation';
  END IF;

  -- 序号由 DB 排：清单的先后顺序是证据链的一部分。
  -- ⚠️ **只在 INSERT 时排**：UPDATE 时重排会把已提交的清单整个打乱顺序。
  IF TG_OP = 'INSERT' THEN
    SELECT coalesce(max(evidence_seq), 0) + 1 INTO NEW.evidence_seq
      FROM platform_dispute_evidence_item
     WHERE tenant_id = NEW.tenant_id AND dispute_id = NEW.dispute_id;
  END IF;

  -- 「已具备」的时刻由 DB 填，调用方传不进来。
  -- ⚠️ 只在**状态转为已具备**的那一刻盖一次时刻：
  --   之后再怎么改说明文字，时刻也不动（那一份材料是什么时候拿到的，是个事实）。
  IF NEW.evidence_state = 'PROVIDED' THEN
    IF TG_OP = 'INSERT' OR OLD.evidence_state IS DISTINCT FROM 'PROVIDED' THEN
      NEW.provided_at := now();
    END IF;
  ELSE
    NEW.provided_at := NULL;
    NEW.provided_by := NULL;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_evidence_item
  BEFORE INSERT OR UPDATE ON platform_dispute_evidence_item
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_evidence_item();

-- 删除举证项也要在可改窗口里。
CREATE FUNCTION guard_platform_dispute_evidence_item_delete() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(24);
  t_deadline timestamptz;
BEGIN
  SELECT dispute_status, evidence_deadline INTO t_status, t_deadline
    FROM platform_dispute
   WHERE tenant_id = OLD.tenant_id AND id = OLD.dispute_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '纠纷单不存在或不属于当前租户：dispute_id=%', OLD.dispute_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status NOT IN ('OPEN','EVIDENCE_PREPARING') THEN
    RAISE EXCEPTION '纠纷单当前是「%」，举证清单已冻结：dispute_id=%', t_status, OLD.dispute_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_deadline <= now() THEN
    RAISE EXCEPTION '举证时限已过（截止 %），不能再修改举证清单：dispute_id=%',
      t_deadline, OLD.dispute_id USING ERRCODE = 'check_violation';
  END IF;

  RETURN OLD;
END
$$;

CREATE TRIGGER trg_platform_dispute_evidence_item_delete
  BEFORE DELETE ON platform_dispute_evidence_item
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_evidence_item_delete();

-- ---------------------------------------------------------------------------
-- 守卫③ guard_platform_dispute_deadline_immutable：**举证时限只能收紧不能放宽**
-- ---------------------------------------------------------------------------
-- 平台的截止时刻是既定事实。放宽它等于给自己续命 —— 而这条正是本模块要防的事。
CREATE FUNCTION guard_platform_dispute_deadline_immutable() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.evidence_deadline > OLD.evidence_deadline THEN
    RAISE EXCEPTION '举证截止时刻只能收紧不能放宽（当前 % → 新值 %）：举证时限是平台的既定事实，放宽等于给自己续命：dispute_id=%',
      OLD.evidence_deadline, NEW.evidence_deadline, NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_deadline_immutable
  BEFORE UPDATE OF evidence_deadline ON platform_dispute
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_deadline_immutable();

-- ---------------------------------------------------------------------------
-- 守卫④ guard_platform_dispute_settlement_columns：结算口径由分录**重算**
-- ---------------------------------------------------------------------------
-- 纠纷单头上的 settlement_status / effective_amount 是**派生量**：
-- 直接写是写不进去的 —— 每次写入都被这里从结算分录重算覆盖。
CREATE FUNCTION guard_platform_dispute_settlement_columns() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(24);
  t_amount numeric(19,4);
BEGIN
  SELECT
      CASE
        WHEN EXISTS (SELECT 1 FROM platform_dispute_settlement s
                      WHERE s.tenant_id = NEW.tenant_id AND s.dispute_id = NEW.id
                        AND s.settlement_status = 'CONFIRMED') THEN 'APPLIED'
        WHEN EXISTS (SELECT 1 FROM platform_dispute_settlement s
                      WHERE s.tenant_id = NEW.tenant_id AND s.dispute_id = NEW.id
                        AND s.settlement_status = 'PENDING_FINANCE_CONFIRM') THEN 'PENDING_FINANCE_CONFIRM'
        WHEN EXISTS (SELECT 1 FROM platform_dispute_settlement s
                      WHERE s.tenant_id = NEW.tenant_id AND s.dispute_id = NEW.id
                        AND s.settlement_status = 'REJECTED') THEN 'FINANCE_REJECTED'
        ELSE 'NOT_APPLIED'
      END,
      coalesce((SELECT sum(s.effective_amount) FROM platform_dispute_settlement s
                 WHERE s.tenant_id = NEW.tenant_id AND s.dispute_id = NEW.id
                   AND s.settlement_status = 'CONFIRMED'), 0)
    INTO t_status, t_amount;

  NEW.settlement_status := t_status;
  NEW.effective_amount := t_amount;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_settlement_columns
  BEFORE INSERT OR UPDATE ON platform_dispute
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_settlement_columns();

-- ---------------------------------------------------------------------------
-- 守卫⑤ guard_platform_dispute_settlement：**生效必须有财务确认**
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_platform_dispute_settlement() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_dispute record;
BEGIN
  SELECT * INTO t_dispute
    FROM platform_dispute
   WHERE tenant_id = NEW.tenant_id AND id = NEW.dispute_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '纠纷单不存在或不属于当前租户：dispute_id=%', NEW.dispute_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF TG_OP = 'INSERT' THEN
    -- 只有**已终结**的纠纷才有结算影响（赢 / 输都可能有一部分钱要动：
    -- 输了是全额或部分赔付，赢了也可能是双方协商后我方让一步）。
    IF t_dispute.dispute_status NOT IN ('RESOLVED_WON','RESOLVED_LOST') THEN
      RAISE EXCEPTION '只有已终结（胜诉 / 败诉）的纠纷才有结算影响（当前 %）：dispute_id=%',
        t_dispute.dispute_status, NEW.dispute_id USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.settlement_currency <> t_dispute.dispute_currency THEN
      RAISE EXCEPTION '结算币种（%）必须与纠纷币种（%）一致', NEW.settlement_currency, t_dispute.dispute_currency
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.requested_amount > t_dispute.dispute_amount THEN
      RAISE EXCEPTION '登记的结算影响金额（%）不得超过争议金额（%）',
        NEW.requested_amount, t_dispute.dispute_amount USING ERRCODE = 'check_violation';
    END IF;

    -- 登记时生效金额必须是 0：登记 ≠ 生效。
    IF NEW.effective_amount <> 0 THEN
      RAISE EXCEPTION '登记结算影响时生效金额必须是 0（**没有财务确认就没有生效**）：settlement_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;

  ELSE
    -- **已被财务确认的分录是终态**：结算生效是一次财务动作，不能事后反悔。
    IF OLD.settlement_status = 'CONFIRMED' AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '结算分录已被财务确认，是终态不可修改：settlement_id=%', OLD.id
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.settlement_status = 'CONFIRMED'
       AND (NEW.confirmed_by IS NULL OR NEW.confirmed_at IS NULL
            OR length(btrim(NEW.finance_reference)) = 0) THEN
      RAISE EXCEPTION '财务确认必须留确认人、确认时刻与财务单据号（否则事后答不出「扣的是哪一笔」）：settlement_id=%',
        NEW.id USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.settlement_status = 'REJECTED'
       AND (NEW.rejected_by IS NULL OR NEW.rejected_at IS NULL) THEN
      RAISE EXCEPTION '财务驳回必须留驳回人与驳回时刻：settlement_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_settlement
  BEFORE INSERT OR UPDATE ON platform_dispute_settlement
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_settlement();

-- ---------------------------------------------------------------------------
-- 守卫⑥ guard_platform_dispute_transition：**状态机**（第三节的三条流转约束）
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_platform_dispute_transition() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_items int;
  t_missing int;
BEGIN
  IF NEW.dispute_status IS NOT DISTINCT FROM OLD.dispute_status THEN
    RETURN NEW;
  END IF;

  -- 终态不可再流转。
  IF OLD.dispute_status IN ('RESOLVED_WON','RESOLVED_LOST','WITHDRAWN') THEN
    RAISE EXCEPTION '纠纷单已是终态（%），不可再流转：dispute_id=%', OLD.dispute_status, OLD.id
      USING ERRCODE = 'check_violation';
  END IF;

  CASE NEW.dispute_status
    WHEN 'EVIDENCE_PREPARING' THEN
      IF OLD.dispute_status <> 'OPEN' THEN
        RAISE EXCEPTION '只有已立案的纠纷可以进入举证准备（当前 %）：dispute_id=%',
          OLD.dispute_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
      -- ① 进入举证准备必须有**举证清单**：没有清单就没有举证要求。
      SELECT count(*) INTO t_items
        FROM platform_dispute_evidence_item
       WHERE tenant_id = NEW.tenant_id AND dispute_id = NEW.id;
      IF t_items = 0 THEN
        RAISE EXCEPTION '还没有举证清单，不能进入举证准备（举证要求是平台给的，先把该交的列出来）：dispute_id=%',
          NEW.id USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'EVIDENCE_SUBMITTED' THEN
      IF OLD.dispute_status <> 'EVIDENCE_PREPARING' THEN
        RAISE EXCEPTION '只有举证准备中的纠纷可以提交举证（当前 %）：dispute_id=%',
          OLD.dispute_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
      -- ② 提交举证必须**清单齐全**：必举证项一个都不能缺。
      SELECT count(*) INTO t_missing
        FROM platform_dispute_evidence_item
       WHERE tenant_id = NEW.tenant_id AND dispute_id = NEW.id
         AND required AND evidence_state <> 'PROVIDED';
      IF t_missing > 0 THEN
        RAISE EXCEPTION '还有 % 项必举证材料没有具备，不能提交举证（超期即判负，缺一项就是白丢）：dispute_id=%',
          t_missing, NEW.id USING ERRCODE = 'check_violation';
      END IF;
      -- 提交时刻必须不晚于截止时刻（守卫① 再钉一道）。
      IF NEW.evidence_submitted_at IS NULL THEN
        RAISE EXCEPTION '必须记录举证提交时刻：dispute_id=%', NEW.id USING ERRCODE = 'check_violation';
      END IF;
      IF NEW.evidence_submitted_at > NEW.evidence_deadline THEN
        RAISE EXCEPTION '举证提交时刻（%）晚于举证截止时刻（%），超期后不可再提交举证：dispute_id=%',
          NEW.evidence_submitted_at, NEW.evidence_deadline, NEW.id USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'RESOLVED_WON' THEN
      IF OLD.dispute_status <> 'EVIDENCE_SUBMITTED' THEN
        RAISE EXCEPTION '只有已提交举证的纠纷才有平台裁决（当前 %）：dispute_id=%',
          OLD.dispute_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
      IF NEW.resolution_code NOT IN ('PLATFORM_RULING_FOR','MUTUAL_AGREEMENT') THEN
        RAISE EXCEPTION '胜诉的结果码只能是「平台判我方胜诉」或「双方达成一致」（当前 %）：dispute_id=%',
          NEW.resolution_code, NEW.id USING ERRCODE = 'check_violation';
      END IF;
      IF NEW.resolved_by IS NULL OR NEW.resolved_at IS NULL THEN
        RAISE EXCEPTION '胜诉必须留结果人与结果时刻：dispute_id=%', NEW.id USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'RESOLVED_LOST' THEN
      -- 两条来路：① **超期自动判负**（系统做的，没有人）；
      --            ② 平台裁决我方败诉（人做的，要留人）。
      IF NEW.resolution_code = 'DEADLINE_EXPIRED' THEN
        IF OLD.dispute_status NOT IN ('OPEN','EVIDENCE_PREPARING') THEN
          RAISE EXCEPTION '超期判负只能从已立案 / 举证准备中落下来（当前 %）：dispute_id=%',
            OLD.dispute_status, OLD.id USING ERRCODE = 'check_violation';
        END IF;
        -- 必须是**真的**超期了才允许判负：提前把自己判负是另一类造假。
        -- ⚠️ 判据用 **NEW**（本次写入后的截止时刻）而不是 OLD：
        --   「平台把截止时刻提前了」与「时钟走到了截止时刻」在库里是同一件事 ——
        --   守卫① 是在**这一行**上判负的，那一行写的就是新时刻。
        IF NEW.evidence_deadline > now() THEN
          RAISE EXCEPTION '举证截止时刻（%）还没到，不能按超期判负：dispute_id=%',
            NEW.evidence_deadline, NEW.id USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.resolved_by IS NOT NULL THEN
          RAISE EXCEPTION '超期判负是系统做的，不挂操作员：dispute_id=%', NEW.id
            USING ERRCODE = 'check_violation';
        END IF;
      ELSIF NEW.resolution_code = 'PLATFORM_RULING_AGAINST' THEN
        IF OLD.dispute_status <> 'EVIDENCE_SUBMITTED' THEN
          RAISE EXCEPTION '只有已提交举证的纠纷才有平台裁决（当前 %）：dispute_id=%',
            OLD.dispute_status, OLD.id USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.resolved_by IS NULL OR NEW.resolved_at IS NULL THEN
          RAISE EXCEPTION '败诉必须留结果人与结果时刻：dispute_id=%', NEW.id USING ERRCODE = 'check_violation';
        END IF;
      ELSE
        RAISE EXCEPTION '败诉的结果码只能是「超期自动判负」或「平台判我方败诉」（当前 %）：dispute_id=%',
          NEW.resolution_code, NEW.id USING ERRCODE = 'check_violation';
      END IF;

    WHEN 'WITHDRAWN' THEN
      IF OLD.dispute_status NOT IN ('OPEN','EVIDENCE_PREPARING','EVIDENCE_SUBMITTED') THEN
        RAISE EXCEPTION '只有已立案 / 举证准备中 / 已提交举证的纠纷可以撤回（当前 %）：dispute_id=%',
          OLD.dispute_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
      IF NEW.resolution_code <> 'WITHDRAWN_BY_US' THEN
        RAISE EXCEPTION '撤回的结果码只能是「我方主动撤回」（当前 %）：dispute_id=%',
          NEW.resolution_code, NEW.id USING ERRCODE = 'check_violation';
      END IF;
      IF NEW.resolved_by IS NULL OR NEW.resolved_at IS NULL THEN
        RAISE EXCEPTION '撤回必须留操作人与时刻：dispute_id=%', NEW.id USING ERRCODE = 'check_violation';
      END IF;

    ELSE
      RAISE EXCEPTION '未知的纠纷状态流转：% -> %（dispute_id=%）', OLD.dispute_status, NEW.dispute_status, NEW.id
        USING ERRCODE = 'check_violation';
  END CASE;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_transition
  BEFORE UPDATE ON platform_dispute
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_transition();

-- ---------------------------------------------------------------------------
-- 守卫⑦ guard_platform_dispute_frozen：**终态不可逆（业务要素整行冻结）**
-- ---------------------------------------------------------------------------
-- 一旦胜诉 / 败诉 / 撤回，这一行的**业务要素**就冻结。
-- ⚠️ 冻结集**不含** settlement_status / effective_amount：
--   败诉之后争议金额还要进结算（下游 12 财务），
--   把结算口径一起冻住等于「判负了钱就永远结不掉」。
CREATE FUNCTION guard_platform_dispute_frozen() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.dispute_status IN ('RESOLVED_WON','RESOLVED_LOST','WITHDRAWN') THEN
    IF (NEW.evidence_deadline, NEW.dispute_type, NEW.dispute_amount, NEW.dispute_currency,
        NEW.our_position, NEW.position_note, NEW.liability_stage, NEW.liability_note,
        NEW.resolution_code, NEW.resolved_at, NEW.evidence_submitted_at, NEW.evidence_submit_note,
        NEW.platform_dispute_no, NEW.platform_name, NEW.platform_order_no, NEW.dispute_reason)
       IS DISTINCT FROM
       (OLD.evidence_deadline, OLD.dispute_type, OLD.dispute_amount, OLD.dispute_currency,
        OLD.our_position, OLD.position_note, OLD.liability_stage, OLD.liability_note,
        OLD.resolution_code, OLD.resolved_at, OLD.evidence_submitted_at, OLD.evidence_submit_note,
        OLD.platform_dispute_no, OLD.platform_name, OLD.platform_order_no, OLD.dispute_reason) THEN
      RAISE EXCEPTION '纠纷单已是终态（%），业务要素不可修改（终态不可逆）：dispute_id=%',
        OLD.dispute_status, OLD.id USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_frozen
  BEFORE UPDATE ON platform_dispute
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_frozen();

-- ---------------------------------------------------------------------------
-- 守卫⑨ guard_platform_dispute_insert：**建单只能从「已立案」开始**
-- ---------------------------------------------------------------------------
-- 状态机守卫⑥ 挂在 `BEFORE UPDATE` 上 ⇒ INSERT 是它的盲区。
-- 不补这一条的话，绕过服务层直连 SQL 可以**凭空插一条「我方胜诉」**的纠纷
-- （CHECK 只管得住字段齐不齐，管不住「它是怎么来的」）。
-- 唯一的例外是守卫① 的超期自动判负：登记时就已超期的纠纷，
--   落下来直接就是败诉 —— 那不是伪造，那是事实。
CREATE FUNCTION guard_platform_dispute_insert() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_auto_loss boolean;
BEGIN
  -- 「建单即已超期」是**合法**的唯一例外：
  --   登记时举证时限已经过了 ⇒ 这件纠纷在系统里当场判负（守卫① 做的正是同一件事）。
  --   注意守卫① 的名字排在守卫⑨ 之前（PostgreSQL 按名字排序触发器），
  --   所以到这里时状态可能已经被它改写成 RESOLVED_LOST。
  t_auto_loss := NEW.dispute_status = 'RESOLVED_LOST'
                 AND NEW.evidence_submitted_at IS NULL
                 AND NEW.resolution_code = 'DEADLINE_EXPIRED'
                 AND NEW.resolved_by IS NULL
                 AND NEW.resolved_at IS NOT NULL
                 AND NEW.evidence_deadline <= now();

  IF NEW.dispute_status <> 'OPEN' AND NOT t_auto_loss THEN
    RAISE EXCEPTION '建单时状态只能是「已立案」（新纠纷一律从立案开始）：dispute_id=%', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.evidence_submitted_at IS NOT NULL
     OR (NEW.resolution_code IS NOT NULL AND NOT t_auto_loss) THEN
    RAISE EXCEPTION '建单时不得带举证提交时刻与结果记录（这两样都是后续动作的结果）：dispute_id=%', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_insert
  BEFORE INSERT ON platform_dispute
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_insert();

-- ---------------------------------------------------------------------------
-- 守卫⑧ guard_platform_dispute_type_platform：**类型与平台必须对得上**
-- ---------------------------------------------------------------------------
-- 蓝图点名的三种纠纷各自绑定一个平台。
CREATE FUNCTION guard_platform_dispute_type_platform() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_expected varchar(32);
BEGIN
  t_expected := CASE NEW.dispute_type
    WHEN 'A_TO_Z' THEN 'AMAZON'
    WHEN 'EBAY_DISPUTE' THEN 'EBAY'
    WHEN 'ALIEXPRESS_DISPUTE' THEN 'ALIEXPRESS'
    ELSE NULL
  END;

  IF t_expected IS NOT NULL AND NEW.platform_code <> t_expected THEN
    RAISE EXCEPTION '纠纷类型「%」只属于平台 %，与平台码「%」不一致：dispute_id=%',
      NEW.dispute_type, t_expected, NEW.platform_code, NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_type_platform
  BEFORE INSERT OR UPDATE OF dispute_type, platform_code ON platform_dispute
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_type_platform();

-- ---------------------------------------------------------------------------
-- 结算分录 → 纠纷单：结算口径**重算**并回写
-- ---------------------------------------------------------------------------
-- 这是「争议金额影响结算」在 trade 侧的**执行点**：
--   分录一变成 CONFIRMED，纠纷头上的生效金额才从 0 变成真实金额。
--   分录还在 PENDING_FINANCE_CONFIRM 时，生效金额恒为 0。
CREATE FUNCTION platform_dispute_sync_settlement() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_dispute_id uuid;
  t_tenant uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN
    t_dispute_id := OLD.dispute_id;
    t_tenant  := OLD.tenant_id;
  ELSE
    t_dispute_id := NEW.dispute_id;
    t_tenant  := NEW.tenant_id;
  END IF;

  -- 头表上的结算口径由守卫④ 在每次写入时重算 —— 这里只需要把 updated_at 拨一下，
  -- 让列表的「最近更新」反映结算变化。（口径本身不在这里写，避免两处各写一份。）
  UPDATE platform_dispute
     SET updated_at = now()
   WHERE tenant_id = t_tenant AND id = t_dispute_id;

  RETURN coalesce(NEW, OLD);
END
$$;

CREATE TRIGGER trg_platform_dispute_sync_settlement
  AFTER INSERT OR UPDATE OR DELETE ON platform_dispute_settlement
  FOR EACH ROW EXECUTE FUNCTION platform_dispute_sync_settlement();

-- ---------------------------------------------------------------------------
-- 留痕追加（只写「真变了」的事）
-- ---------------------------------------------------------------------------
CREATE FUNCTION platform_dispute_append_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- ⚠️ 触发器挂在 `AFTER INSERT OR UPDATE`（不是 `UPDATE OF dispute_status`）：
  --   守卫① 会在**任何一次写入**上把超期纠纷的状态改写成 RESOLVED_LOST ——
  --   那次写入未必改过 dispute_status 列（可能只是更新了备注）。
  --   只挂 `UPDATE OF dispute_status` 的话，自动判负就**没有留痕**了。
  IF TG_OP = 'UPDATE' AND NEW.dispute_status IS NOT DISTINCT FROM OLD.dispute_status THEN
    RETURN NEW;
  END IF;

  INSERT INTO platform_dispute_event(
      id, tenant_id, dispute_id, event_type, from_status, to_status,
      amount, reason_note, occurred_by, occurred_at)
  VALUES (
      gen_random_uuid(),
      NEW.tenant_id,
      NEW.id,
      CASE
        WHEN TG_OP = 'INSERT'                    THEN 'CREATED'
        WHEN NEW.dispute_status = 'EVIDENCE_PREPARING' THEN 'EVIDENCE_PREPARING'
        WHEN NEW.dispute_status = 'EVIDENCE_SUBMITTED' THEN 'EVIDENCE_SUBMITTED'
        WHEN NEW.dispute_status = 'RESOLVED_WON'  THEN 'RESOLVED_WON'
        WHEN NEW.dispute_status = 'RESOLVED_LOST' THEN 'RESOLVED_LOST'
        WHEN NEW.dispute_status = 'WITHDRAWN'     THEN 'WITHDRAWN'
        ELSE 'UPDATED'
      END,
      CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.dispute_status END,
      NEW.dispute_status,
      CASE
        WHEN NEW.dispute_status = 'RESOLVED_LOST' THEN NEW.dispute_amount
        ELSE NULL
      END,
      CASE
        WHEN TG_OP = 'INSERT' THEN '登记平台纠纷：' || coalesce(NEW.dispute_type, '')
        WHEN NEW.dispute_status = 'EVIDENCE_PREPARING'
          THEN '进入举证准备：已列出平台要求提交的材料'
        WHEN NEW.dispute_status = 'EVIDENCE_SUBMITTED'
          THEN '已向平台提交举证：' || coalesce(NEW.evidence_submit_note, '')
        WHEN NEW.dispute_status = 'RESOLVED_WON'
          THEN '我方胜诉（' || coalesce(NEW.resolution_code, '') || '）：'
               || coalesce(nullif(btrim(NEW.resolution_note), ''), '（无说明）')
        WHEN NEW.dispute_status = 'RESOLVED_LOST'
          THEN CASE NEW.resolution_code
                WHEN 'DEADLINE_EXPIRED'
                  THEN '**举证超期，自动判负**（举证截止 ' || NEW.evidence_deadline::text || '）'
                ELSE '我方败诉（' || coalesce(NEW.resolution_code, '') || '）：'
                     || coalesce(nullif(btrim(NEW.resolution_note), ''), '（无说明）')
              END
        WHEN NEW.dispute_status = 'WITHDRAWN'
          THEN '我方主动撤诉：' || coalesce(nullif(btrim(NEW.resolution_note), ''), '（无说明）')
        ELSE '纠纷要素变更'
      END,
      -- ⚠️ 「谁做的」按动作取：**超期自动判负是系统做的，没有操作员**（留空）。
      CASE
        WHEN NEW.resolution_code = 'DEADLINE_EXPIRED' THEN NULL
        ELSE coalesce(
            CASE WHEN NEW.dispute_status IN ('RESOLVED_WON','RESOLVED_LOST','WITHDRAWN')
                 THEN NEW.resolved_by END,
            NEW.evidence_submitted_by,
            NEW.updated_by,
            NEW.created_by)
      END,
      CASE
        WHEN TG_OP = 'INSERT'                THEN NEW.created_at
        WHEN NEW.dispute_status = 'EVIDENCE_PREPARING' THEN coalesce(NEW.updated_at, now())
        WHEN NEW.dispute_status = 'EVIDENCE_SUBMITTED' THEN coalesce(NEW.evidence_submitted_at, now())
        WHEN NEW.dispute_status = 'RESOLVED_WON'  THEN coalesce(NEW.resolved_at, now())
        WHEN NEW.dispute_status = 'RESOLVED_LOST' THEN coalesce(NEW.resolved_at, now())
        WHEN NEW.dispute_status = 'WITHDRAWN'     THEN coalesce(NEW.resolved_at, now())
        ELSE now()
      END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_event
  AFTER INSERT OR UPDATE ON platform_dispute
  FOR EACH ROW EXECUTE FUNCTION platform_dispute_append_event();

-- 举证清单增删也留痕：「这份举证清单是什么时候补齐的」要答得出来。
CREATE FUNCTION platform_dispute_append_evidence_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_dispute_id uuid;
  t_tenant uuid;
  t_by uuid;
  t_type varchar(32);
  t_note text;
  t_amount numeric(19,4);
BEGIN
  IF TG_OP = 'DELETE' THEN
    t_dispute_id := OLD.dispute_id;
    t_tenant  := OLD.tenant_id;
    t_by      := OLD.created_by;
    t_type    := OLD.evidence_type;
    t_amount  := NULL;
    t_note    := '移除举证材料项：' || coalesce(OLD.evidence_label, '') || '（' || OLD.evidence_type || '）';
  ELSE
    t_dispute_id := NEW.dispute_id;
    t_tenant  := NEW.tenant_id;
    t_by      := NEW.created_by;
    t_type    := NEW.evidence_type;
    t_amount  := NULL;
    t_note    := CASE WHEN TG_OP = 'INSERT'
                      THEN '加入举证材料项：' || coalesce(NEW.evidence_label, '') || '（' || NEW.evidence_type || '）'
                      ELSE '更新举证材料项 ' || NEW.evidence_seq::text || '：' || NEW.evidence_state
                           || coalesce('，缺：' || NEW.missing_reason, '')
                      END;
  END IF;

  INSERT INTO platform_dispute_event(
      id, tenant_id, dispute_id, event_type, from_status, to_status,
      amount, reason_note, occurred_by, occurred_at)
  SELECT gen_random_uuid(), t_tenant, t_dispute_id,
         CASE WHEN TG_OP = 'INSERT' THEN 'EVIDENCE_ITEM_ADDED'
              WHEN TG_OP = 'DELETE' THEN 'EVIDENCE_ITEM_REMOVED'
              ELSE 'EVIDENCE_ITEM_UPDATED' END,
         -- ⚠️ 清单增删**不是**状态流转 ⇒ from_status 留空。
         NULL, c.dispute_status,
         t_amount, t_note, t_by, now()
    FROM platform_dispute c
   WHERE c.tenant_id = t_tenant AND c.id = t_dispute_id;

  RETURN coalesce(NEW, OLD);
END
$$;

CREATE TRIGGER trg_platform_dispute_evidence_event
  AFTER INSERT OR UPDATE OR DELETE ON platform_dispute_evidence_item
  FOR EACH ROW EXECUTE FUNCTION platform_dispute_append_evidence_event();

-- 结算分录留痕。
CREATE FUNCTION platform_dispute_append_settlement_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_type varchar(32);
  t_amount numeric(19,4);
  t_note text;
  t_at timestamptz;
  t_by uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    t_type   := 'SETTLEMENT_REGISTERED';
    t_amount := NEW.requested_amount;
    t_note   := '登记结算影响：' || NEW.requested_amount::text
                || ' ' || NEW.settlement_currency || '，**等待财务确认后才生效**';
    t_at     := NEW.created_at;
    t_by     := NEW.created_by;
  ELSE
    t_type   := CASE NEW.settlement_status
                  WHEN 'CONFIRMED' THEN 'SETTLEMENT_CONFIRMED'
                  WHEN 'REJECTED'  THEN 'SETTLEMENT_REJECTED'
                  ELSE 'SETTLEMENT_REGISTERED'
                END;
    t_amount := NEW.effective_amount;
    t_note   := CASE NEW.settlement_status
                  WHEN 'CONFIRMED' THEN '财务确认结算生效：' || NEW.effective_amount::text
                                      || '，财务单据号 ' || NEW.finance_reference
                  WHEN 'REJECTED'  THEN '财务驳回结算影响：'
                                      || coalesce(nullif(btrim(NEW.reject_reason), ''), '（无理由）')
                  ELSE '结算分录变更'
                END;
    t_at     := coalesce(NEW.confirmed_at, NEW.rejected_at, NEW.updated_at, now());
    t_by     := coalesce(NEW.confirmed_by, NEW.rejected_by, NEW.created_by);
  END IF;

  INSERT INTO platform_dispute_event(
      id, tenant_id, dispute_id, event_type, from_status, to_status,
      amount, reason_note, occurred_by, occurred_at)
  SELECT gen_random_uuid(), NEW.tenant_id, NEW.dispute_id, t_type,
         NULL, c.dispute_status,
         t_amount, t_note, t_by, t_at
    FROM platform_dispute c
   WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.dispute_id;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_settlement_event
  AFTER INSERT OR UPDATE ON platform_dispute_settlement
  FOR EACH ROW EXECUTE FUNCTION platform_dispute_append_settlement_event();

-- ===========================================================================
-- 六、索引与发号器
-- ===========================================================================
-- ① 纠纷列表（按状态 + 时刻）。
CREATE INDEX platform_dispute_by_status
  ON platform_dispute(tenant_id, dispute_status, created_at desc);
-- ② **按平台反查**（同一个平台的纠纷聚在一起；平台码是逻辑引用，没有跨库 FK）。
CREATE INDEX platform_dispute_by_platform
  ON platform_dispute(tenant_id, platform_code, created_at desc);
-- ③ **按截止时刻取数**（超时队列：谁快到点了）。
CREATE INDEX platform_dispute_by_deadline
  ON platform_dispute(tenant_id, evidence_deadline)
  WHERE evidence_submitted_at IS NULL;
-- ④ 结算口径统计（待财务确认队列的取数路径）。
CREATE INDEX platform_dispute_by_settlement
  ON platform_dispute(tenant_id, settlement_status, created_at desc);
-- ⑤ 举证清单按单读（按序号）。
CREATE INDEX platform_dispute_evidence_item_by_dispute
  ON platform_dispute_evidence_item(tenant_id, dispute_id, evidence_seq);
-- ⑥ 结算分录按单读。
CREATE INDEX platform_dispute_settlement_by_dispute
  ON platform_dispute_settlement(tenant_id, dispute_id, created_at);
-- ⑦ **一张纠纷只能有一条已确认的结算分录**（DB 不变量而不是应用层先查一次 ——
--    并发下两个请求都能查到「还没有」，靠应用层判会双双通过）。
CREATE UNIQUE INDEX platform_dispute_one_confirmed_settlement
  ON platform_dispute_settlement(tenant_id, dispute_id)
  WHERE settlement_status = 'CONFIRMED';
-- ⑧ 留痕回放。
CREATE INDEX platform_dispute_event_by_dispute
  ON platform_dispute_event(tenant_id, dispute_id, occurred_at);

CREATE SEQUENCE platform_dispute_no_seq;
GRANT USAGE,SELECT ON SEQUENCE platform_dispute_no_seq TO trade_app;

-- ===========================================================================
-- 七、多租户：RLS + 授权
-- ===========================================================================
-- 四张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：纠纷单里同时有**平台争议金额、我方立场、责任判定与举证清单**，
--   端点又大多是**按 id 直达**的 URL —— 猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_dispute','platform_dispute_evidence_item',
    'platform_dispute_settlement','platform_dispute_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  platform_dispute, platform_dispute_evidence_item,
  platform_dispute_settlement, platform_dispute_event TO trade_app;

-- 授权在前、收回在后。
-- ① **举证清单的「已具备时刻」由 DB 盖**：触发器在状态转为 PROVIDED 的那一刻写 now()，
--    调用方改不动它 —— 「什么时候拿到的材料」是个事实，不是可填字段。
--    （清单的 UPDATE 权限保留：PENDING → PROVIDED / MISSING 是一次正常的业务动作，
--      与 11.2.3.3「索赔明细整行不可改」不同 —— 举证清单本来就是会推进的清单。）
-- ② **留痕追加式**：能被悄悄改掉的留痕等于没有留痕。
REVOKE UPDATE,DELETE ON platform_dispute_event FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：不碰 V188 的 supplier_claim 簇，
-- 也不碰 channel / warehouse / catalog 的任何表（跨服务禁止 SQL / FK / 视图）。
-- 本模块对外部的全部依赖都是**字符串快照**（平台码 / 平台名 / 平台纠纷号 / 平台订单号），
-- 跨车道 DDL 回归面 = 0（同批另有三条车道也在往 trade 落迁移）。
