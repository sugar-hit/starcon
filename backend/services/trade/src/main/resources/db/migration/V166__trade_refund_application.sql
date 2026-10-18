-- 11.3.1.1 退款申请（卷 11 售后与退货，资金出账前的最后一道内部闸）。
--
-- 蓝图 11-aftersales-and-returns.md 原文（11.3.1.1 退款申请）：
--   职责：发起退款：选择退款方式（原路/平台/余额）、金额（可部分）、原因，
--         校验累计退款不超过可退余额，提交审批后交财务执行。
--   输入：退款申请；可退余额校验（已退 + 在途）；审批规则。
--   输出：退款申请单（待审/已批/执行中/成功/失败）+ 可退余额更新。
--   关系：上游 = 11.1.1.2、11.2.1.1；下游 = 12.2.2.2 付款执行（退款专用业务类型，保留原收款关联）。
--         **可退余额校验必须在服务端做**——重复提交退款是资损事故的高发点。
--
-- 归属：**trade 服务**（同 L3 11.3.1 锚点，与 11.3.1.2 退款状态同库）。
--
-- ===========================================================================
-- 零、依赖实查结论（本车道在集成头 55bf6888 上逐个 grep 确认）
-- ===========================================================================
--   * 上游 11.1.1.2 售后单详情：**已交付**（V156 aftersales_case 簇 / V157 aftersales_case_line）。
--   * 上游 11.2.1.1 退货授权单：**已交付**（V161 rma_authorization 簇，同库）。
--   * 同 L3 兄弟 11.3.1.2 退款状态：**已交付**（V159 refund_execution 簇，同库）。
--   * 上游 11.1.1.1 售后单：V156（requested_amount 是本模块「可退基数」的真相）。
--   * 下游 12.2.2.2 付款执行（卷 12 财务）：**未交付** —— backend/services/ 下无 finance 服务实现。
--     ⇒ 本迁移**自己拥有**「交财务执行」这个移交动作的建模（refund_application.execution_id
--     指向 11.3.1.2 已交付的 refund_execution），并在 capabilities 里如实报
--     financeExecutionDelivered=false 与归属模块号。**不假装已对接、不造恒空的影子表。**
--
-- ===========================================================================
-- 一、职责句五个区块各自落在哪
-- ===========================================================================
-- 「选择退款方式（原路/平台/余额）」→ refund_method ∈ (ORIGINAL / PLATFORM / BALANCE)。
--      ⚠️ 词表与 V159 refund_execution.platform_refund_method **逐字同构**：
--      「平台退款」这一条会让这笔钱根本不经支付渠道，因此下游要不要做平台同步
--      由它决定（V159 有一条 platform_sync_matches_method 恒等式）。用两个词表描述
--      同一件事，会出现「申请单说原路、执行记录说平台」这种对不上的行。
-- 「金额（可部分）」          → refund_amount < 可退余额（单头，无行表，见第三节）。
-- 「原因」                  → refund_reason（内联五类词表）+ refund_reason_note。
-- 「校验累计退款不超过可退余额」→ **本迁移存在的理由**，四道闸，见第二节。
-- 「提交审批后交财务执行」   → submit / approve / dispatch / execution-outcome 四个动作，
--      蓝图点名的五态（待审 / 已批 / 执行中 / 成功 / 失败）齐备，另加三态必要两端，见第四节。
--
-- ===========================================================================
-- 二、蓝图加粗句「可退余额校验必须在服务端做」的**四道闸**
-- ===========================================================================
-- 重复提交退款是资损事故的高发点，所以这条规则不能只写在 Java 里。四道闸互相独立：
--
-- 【闸一：占用口径是一个写死的函数，不是各处现算的口径】
--   可退余额 = 可退基数 − 已退 − 在途，且四个占用桶的定义钉在 **guard_refund_application()**
--   这一个函数里（服务层 {@code balanceOf} 逐条对应，见下表）。服务层与 DB 层各算一遍，
--   两边的算法必须能逐字对照 —— 而「各处现算」正是重复退款的起点：改了一处、忘了另一处，
--   两处口径分叉，而**没有测试**会发现，因为每一处单独看都是对的。
--
--   可退基数 = aftersales_case.requested_amount（11.1.1.1 的真相，同库只读）
--   已退     settled    = Σ refund_execution[SUCCEEDED]        + Σ 本表[SUCCEEDED 且未挂执行记录]
--   在途①   dispatched = Σ refund_execution[IN_FLIGHT, UNKNOWN]
--   在途②   registered = Σ refund_execution[NOT_INITIATED]
--   在途③   pending    = Σ 本表[PENDING_APPROVAL / APPROVED / SECOND_* 且未挂执行记录]
--   争议     disputed   = Σ refund_execution[DISCREPANCY]
--   可退余额 = 基数 − (settled + dispatched + registered + pending + disputed)
--
--   ⚠️ **四个桶里为什么没有 FAILED**：V159 的 CHECK refund_execution_failure_is_definitive
--      已经钉死「FAILED ⇒ failure_class=DEFINITIVE ⇒ 钱确定没有出去」。
--      确定性失败不占用额度是**语义正确**而不是宽松：把它算进占用会让一张明确失败的退款
--      永久吃掉额度，运营只能去改库才能再退一次。
--   ⚠️ **DISCREPANCY 按占用计入**：对账发现平台与系统不一致时，
--      我们**不知道**钱出去没有 —— 蓝图的立场是「宁可拦住也不能放过」。
--      单列一个 disputed 桶就是为了让这个决定在报表里看得见，而不是悄悄并进别的桶。
--   ⚠️ **NOT_INITIATED 也占用**：它是「已登记待发起，还没有对外派发过」——
--      钱随时可能出去，放它过去等于给「登记两笔退款」开了一道缝。
--   ⚠️ **未挂执行记录的申请单与执行记录不会双算**：同一个执行记录要么被本表 execution_id
--      引用（此时本表那一行不再计入 pending/settled，由执行记录的桶计一次），
--      要么没被引用（此时它落在本表的桶里）。见第五节 execution_id 的纪律。
--   ⚠️ **执行记录按订单归属**：refund_execution 的归属是 case_id（可空），
--      恒为空的执行记录（平台直退、赔付类）按**同一张订单**计入本售后单的占用 ——
--      钱是从买家的同一笔付款里出的，跨订单的归属当前无解（见第九节口径声明）。
--
-- 【闸二：写入时刻的触发器】guard_refund_application 在 INSERT 与金额/状态变更时
--   **真查一次**上面那五段 SQL 再判，绕过服务层直连 SQL 也绕不过（与 V161 同一套写法）。
--
-- 【闸三：乐观锁 expectedVersion】审批 / 派发 / 登记结果都要带版本。
--   两个运营同时点「提交审批」时，第二次会被版本挡住。
--
-- 【闸四：**不做**幂等键，而是靠上面的三道** + 一个刻意的取舍**——
--   蓝图没有要求退款申请幂等（幂等键是 11.3.1.2 对**派发**的要求，见 V159 第一节【闸三】）。
--   在本模块加一把申请级幂等键会造出一个新语义：**同一笔退款金额被合法地拆成两笔申请**
--   （先退 30、再退 20）是完全正常的业务，幂等键会把它判成重复。
--   真正要挡的「同一笔被提交两次」由闸一/二/三挡 —— 额度只够退一次，第二次必然超额。
--
-- ===========================================================================
-- 三、单头无行表：金额只有一个
-- ===========================================================================
-- 蓝图职责句写的是「金额（可部分）」—— 一个数，且**可部分**。
-- 建一张 refund_application_line 会让人以为退款要按订单行拆，
-- 而「这次退的是哪几件」是 11.2.1.1 退货授权行（authorized_qty）的职责，
-- 本模块只回答「退多少钱、怎么退、批没批」。两件事混在一张单上，
-- 「这张单退了哪几件」会与退货授权行的数量对不上。
--
-- ⚠️ 因此本表**不复制** sku / 单价 / 数量：那些真值在 sales_order_line（V99）与
--   aftersales_case_line（V157），复制一份就会漂移。
--
-- ===========================================================================
-- 四、状态机：蓝图点名的五态 + 三个必要两端
-- ===========================================================================
--   DRAFT                   草稿：方式 / 金额 / 原因已登记，尚未提交审批
--   PENDING_APPROVAL         待审   ← 蓝图「待审」
--   APPROVED                已批（单签；金额 < 双签阈值）
--   SECOND_APPROVAL_PENDING 待复审（金额 ≥ 双签阈值，初审已过还差一个人）
--   SECOND_APPROVED         已批（双签；蓝图同样算「已批」）
--   EXECUTING               执行中 ← 蓝图「执行中」（已交 12 财务 / 平台）
--   SUCCEEDED               成功   ← 蓝图「成功」（终态）
--   FAILED                  失败   ← 蓝图「失败」（终态）
--   REJECTED                审批驳回（终态）
--   CANCELLED               人工撤销（终态，撤回 / 作废）
--
--   ⚠️ 蓝图输出句只列了五个状态，但**没有 DRAFT 会让「谁发起、什么时候改的」无处落**，
--      没有 REJECTED 会让「审批驳回」与「执行失败」在库里长成同一个形状 ——
--      而这两件事的补救动作完全相反（一个是改单重提，一个是查渠道重试）。
--      三态是两端补齐，不是新增业务语义；蓝图点名的五个状态全部齐备、逐字对应。
--
--   占用可退余额的六态：DRAFT 不占用（还没提交）；REJECTED / FAILED / CANCELLED 不占用。
--   ⚠️ 这一点与直觉相反但必须如此：审批驳回说明**根本没退**，
--      额度要立刻还给售后单，否则一张被拒的申请会永久占死一块可退余额。
--
-- 【蓝图加粗句在状态机上的落点】**没有「超时就当失败」这条自动边**。
--   执行中 → 失败必须由显式登记动作触发，且必须给出失败原因与来源（CHECK 钉死）；
--   外部超时不等于失败 —— 这条 11.3.1.2 已在 V159 用「UNKNOWN 态 + 查证闸」实现，
--   本模块**继承**它：EXECUTING 的单在渠道没回执时只能停在执行中，
--   由执行记录的 UNKNOWN 态承接，不允许由申请单单方面宣布失败。
--
-- ===========================================================================
-- 五、execution_id：**引用**兄弟模块的执行记录，不写它
-- ===========================================================================
-- refund_execution（V159）的写入所有者是 11.3.1.2 那条车道。按 AGENTS.md
-- 「每张事实表只有一个写入所有者」，本模块**不 INSERT / UPDATE 那张表**，
-- 只在派发时**引用**一条已存在的执行记录：
--
--   * execution_id 为空 ⇒ 这笔申请还没派发，额度由本表的桶计；
--   * execution_id 非空 ⇒ 额度改由那条执行记录的桶计，本表这一行**不再计入**，
--     避免同一笔钱被算两次（见第一节「未挂执行记录的申请单与执行记录不会双算」）。
--
-- 服务层在派发时真查一次：执行记录存在、属于本租户、订单一致、
-- 且状态在 NOT_INITIATED / IN_FLIGHT / UNKNOWN 之内。
-- ⚠️ 12 财务未交付 ⇒ 当前能存在的执行记录来自 11.3.1.2 的平台同步与人工登记路径。
--   真实付款执行（12.2.2.2）报 delivered=false 与归属模块号。
--
-- ===========================================================================
-- 六、审批规则（输入句第三项）：租户可维护的一张真表
-- ===========================================================================
-- 「审批规则」若只做成代码里的 if，就成了换个租户就得改代码的伪配置；
-- 若只做成请求体里的一个布尔，又等于没有规则。本表是**租户可维护的真表**：
--
--   dual_approval_threshold  金额 **≥** 此阈值 ⇒ 必须双人签（初审 + 复审）
--
-- ⚠️ **没有配置 ⇒ 双人签（fail-closed）**：没有 ACTIVE 行或行被停用时，
--   一律要求双人签。默认取「最宽松」（都不签）会让新租户在没配置任何规则的情况下
--   就能一笔退款直接放行 —— 对一个资损模块，默认必须是拦住而不是放过。
--   服务层把这一点如实报成 policySource=DEFAULT_NO_POLICY，不假装「用的是默认阈值」。
--
-- ⚠️ **双人签的两条硬规则由 DB 触发器钉死**（AGENTS.md 资金安全）：
--   ① 复审人 ≠ 初审人（同一笔钱两个人点头，但必须是两个人）；
--   ② 初审人 ≠ 提交人（自己提自己批等于没审批）。
--   两条都由 CHECK + 触发器在 DB 层执行，绕过服务层直连 SQL 也绕不过。
--
-- ===========================================================================
-- 七、零 ALTER 零 DROP 既有表
-- ===========================================================================
--   * aftersales_case 簇（V156/V157）：**只读**，提供可退基数与单据锚点；
--   * rma_authorization 簇（V161）：**只读**，作为「这笔退款来自哪张退货授权」的溯源锚点；
--   * refund_execution 簇（V159）：**只读**，提供已退与在途的占用事实；
--   * sales_order 簇（V99）：**只读**，用于执行记录的订单归属判定。
-- 刻意**不加外键**指向它们：加 FK 会让本迁移对别的车道的表产生 DDL 依赖，
-- 而同批另有三条车道也在往 trade 落迁移。一致性由**写入时刻的触发器真查一次**承担。
-- 跨车道 DDL 回归面 = 0。

CREATE SEQUENCE refund_application_no_seq;
-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查），
-- 错误文案说的是「数据与既有记录冲突」，与「没给 nextval 权限」毫无关系（V156 同一写法）。
GRANT USAGE,SELECT ON SEQUENCE refund_application_no_seq TO trade_app;

-- ===========================================================================
-- 八、审批规则表
-- ===========================================================================
CREATE TABLE refund_approval_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则按币种维护：阈值是金额，不同币种不能共用一个数。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  policy_status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(policy_status IN ('ACTIVE','DISABLED')),

  -- 金额 **≥** 此阈值必须双人签。0 表示**任何金额**都要双人签（= 最严格档）。
  -- ⚠️ 这个字段的语义是「从多大开始要第二个人」，不是「多大以内免审批」——
  --   后者会让「免审批」成为一个默认行为，而资损模块不该有默认免审。
  dual_approval_threshold numeric(19,4) NOT NULL DEFAULT 0
    CHECK(dual_approval_threshold >= 0),

  -- 规则生效时刻：审批判定用**提交那一刻**的口径，写进申请单的审批留痕里。
  effective_from timestamptz NOT NULL,

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 1000),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(updated_at >= created_at)
);

-- 同一租户同一币种**只能有一条 ACTIVE**（部分唯一索引）：
-- 两条生效规则会让「这笔要不要双签」有两个答案，而审批判定必须在提交那一刻定死。
CREATE UNIQUE INDEX refund_approval_policy_one_active
  ON refund_approval_policy(tenant_id, currency)
  WHERE policy_status = 'ACTIVE';

-- ===========================================================================
-- 九、退款申请单
-- ===========================================================================
CREATE TABLE refund_application (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 申请单号：租户内唯一。序列全局，两个租户号段不重叠是额外好处。
  application_no varchar(32) NOT NULL,

  -- ---- 上游锚点（只读，刻意不加外键，见第七节）----
  -- 售后单（11.1.1.2 / V156）：退款一定挂在某张售后单上 ——
  -- 蓝图关系句的上游就是 11.1.1.2 与 11.2.1.1，而后者又依赖前者。
  case_id uuid NOT NULL,
  -- 订单（V99，只读）：可退占用判定按订单归属执行记录（第一节 ⚠️ 条目），存一份指针。
  -- ⚠️ 这是**指针**不是副本：真值仍在 sales_order，订单金额变了不会回头改这一列。
  order_id uuid NOT NULL,
  -- 退货授权单（11.2.1.1 / V161）：**可空** ——
  -- REFUND_ONLY（未发货即退）、赔付类退款没有退货授权，塞一个恒为 null 的必填列
  -- 会让这些退款在本模块根本进不来。
  rma_id uuid,

  -- ---- 状态机（第四节）----
  application_status varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK(application_status IN (
      'DRAFT','PENDING_APPROVAL','APPROVED','SECOND_APPROVAL_PENDING','SECOND_APPROVED',
      'EXECUTING','SUCCEEDED','FAILED','REJECTED','CANCELLED'
  )),

  -- ---- 职责句的三个要素 ----
  -- 退款方式：原路 / 平台 / 余额（与 V159 platform_refund_method 逐字同构，见第一节）。
  refund_method varchar(16) NOT NULL CHECK(refund_method IN ('ORIGINAL','PLATFORM','BALANCE')),
  -- 退款金额（可部分）。**禁浮点**：numeric + BigDecimal（AGENTS.md）。
  refund_amount numeric(19,4) NOT NULL CHECK(refund_amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 退款原因：五类内联词表（照抄 V156 的同一手法 —— 11.1.2.1 未交付时用内联词表
  -- 而不是外键到原因分类树，建一张只有自己写的分类树表等于替它定义了它没定义的语义）。
  -- ⚠️ 这五类是**退款**的原因，与售后单的 reason_code（七类）是**两个不同的轴**：
  --   售后单记的是「货为什么有问题」，这里记的是「这笔钱为什么退出去」。
  refund_reason varchar(24) NOT NULL CHECK(refund_reason IN (
      'RETURN_REFUND','CANCELLATION','WRONG_AMOUNT','GOODWILL','OTHER'
  )),
  -- OTHER 必须给说明（CHECK）：归到「其他」又不写为什么，等于把原因这一栏留空。
  refund_reason_note text NOT NULL DEFAULT '' CHECK(length(refund_reason_note) <= 1000),

  -- 凭据（退款依据）：原路退款必须有原收款流水号才能查证「钱退没退出去」，
  -- 平台退款必须有平台侧退款单号。缺失会让事后对账无从下手（CHECK 钉死非平台方式的必填项）。
  original_payment_ref varchar(128) NOT NULL DEFAULT '',
  platform_refund_ref varchar(128) NOT NULL DEFAULT '',

  -- ---- 审批留痕字段（写入人 / 时刻两两成对，由 CHECK 与触发器钉死）----
  submitted_by uuid,
  submitted_at timestamptz,
  first_approved_by uuid,
  first_approved_at timestamptz,
  second_approved_by uuid,
  second_approved_at timestamptz,
  -- 审批决定说明：驳回**必须**写（CHECK）；通过时可选。
  approval_note text NOT NULL DEFAULT '' CHECK(length(approval_note) <= 1000),

  -- 驳回留痕：**刻意不复用** first_approved_* ——
  --   那两列的语义是「审下来同意」，让驳回也往里写会让
  --   「这次审批是同意还是驳回」出现两个答案，而审批单据的自相矛盾在审计里是要命的。
  --   副作用是「谁驳回的」必须另有地方放 —— 审计恰恰最需要这一条。
  rejected_by uuid,
  rejected_at timestamptz,

  -- ---- 交财务执行（第五节）----
  dispatched_by uuid,
  dispatched_at timestamptz,
  execution_id uuid,
  -- 执行结果：成功 / 失败。
  outcome_source varchar(20) CHECK(outcome_source IN (
      'FINANCE_MODULE_12','PLATFORM_SYNC','MANUAL_OPERATOR'
  )),
  outcome_recorded_by uuid,
  outcome_recorded_at timestamptz,
  -- 失败原因（失败必填，CHECK）；成功必须为空（CHECK）——
  -- 一张 SUCCEEDED 的单上写着 failure_reason 会被读成「它其实失败过」。
  failure_reason text NOT NULL DEFAULT '' CHECK(length(failure_reason) <= 1000),

  cancelled_by uuid,
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 1000),

  -- 乐观锁：改草稿 / 提交 / 审批 / 派发 / 登记结果都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,application_no),

  -- ---- 状态机的时间戳恒等式 ----
  -- ⚠️ **这四条写成「蕴含」而不是「等价」**，是有原因的：
  --   CANCELLED 可以从 PENDING_APPROVAL / APPROVED / SECOND_APPROVAL_PENDING / SECOND_APPROVED
  --   **四种**状态撤销而来，于是「到过初审」这件事在 CANCELLED 上**可有可无**。
  --   用等价式（status IN (...) = timestamp IS NOT NULL）会当场拒绝
  --   「已双签的单被撤销」这个完全合法的动作 —— 而那正是最需要留痕的一种撤销。
  --
  -- 正向：走到这些状态 ⇒ 相应那一步审批一定发生过。
  CHECK(application_status NOT IN ('APPROVED','SECOND_APPROVAL_PENDING','SECOND_APPROVED',
                                   'EXECUTING','SUCCEEDED','FAILED')
        OR (first_approved_at IS NOT NULL AND first_approved_by IS NOT NULL)),
  CHECK(application_status NOT IN ('SECOND_APPROVED','EXECUTING','SUCCEEDED','FAILED')
        OR (second_approved_at IS NOT NULL AND second_approved_by IS NOT NULL)),
  -- 反向：还没审过的状态不许挂着审批留痕。
  --   ⚠️ CANCELLED 不在反向列表里 —— 它既可能没审过，也可能审到一半才撤。
  CHECK(first_approved_at IS NULL
        OR application_status NOT IN ('DRAFT','PENDING_APPROVAL','REJECTED')),
  CHECK(second_approved_at IS NULL
        OR application_status IN ('SECOND_APPROVED','EXECUTING','SUCCEEDED','FAILED','CANCELLED')),

  -- 提交 ⇔ 待审及以后：没提交的单不会有提交时刻，已提交的单不能没有。
  CHECK((application_status = 'DRAFT') = (submitted_at IS NULL
        AND submitted_by IS NULL)),

  -- 驳回留痕 ⇔ 驳回态（与撤销同一条纪律）。
  CHECK((application_status = 'REJECTED') = (rejected_at IS NOT NULL AND rejected_by IS NOT NULL)),

  -- 双签的两个人必须不同（第六节规则①）。
  CHECK(second_approved_by IS NULL OR second_approved_by <> first_approved_by),
  -- 提交人不能自己批（第六节规则②）。
  CHECK(first_approved_by IS NULL OR first_approved_by <> submitted_by),

  -- 驳回必须说明理由：不写理由的驳回在事后无法解释「为什么不退」。
  CHECK(application_status <> 'REJECTED' OR btrim(approval_note) <> ''),

  -- ---- 派发与结果的恒等式 ----
  -- 执行中及以后必须有派发留痕；派发留痕存在即表示已经交出去了。
  CHECK((application_status IN ('EXECUTING','SUCCEEDED','FAILED'))
        = (dispatched_at IS NOT NULL AND dispatched_by IS NOT NULL)),
  -- 派发时必须指明它落到哪条执行记录上（第五节）。
  CHECK(dispatched_at IS NULL OR execution_id IS NOT NULL),

  -- 明确结果 ⇔ 结果留痕：成功 / 失败必须有来源与人与时刻。
  CHECK((application_status IN ('SUCCEEDED','FAILED'))
        = (outcome_recorded_at IS NOT NULL
           AND outcome_recorded_by IS NOT NULL
           AND outcome_source IS NOT NULL)),
  -- 失败必须给出原因（蓝图输出句点名「失败原因」）。
  CHECK(application_status <> 'FAILED' OR btrim(failure_reason) <> ''),
  -- 非失败态不许挂失败原因（同一方向的另一种漏）。
  CHECK(application_status = 'FAILED' OR failure_reason = ''),

  -- 撤销时刻 ⇔ 撤销态（防「状态已撤但时刻没落」导致倒计时不停）。
  CHECK((application_status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)),
  CHECK(application_status <> 'CANCELLED' OR btrim(cancel_reason) <> ''),

  -- OTHER 类原因必须写说明。
  CHECK(refund_reason <> 'OTHER' OR btrim(refund_reason_note) <> ''),

  -- 原路退款必须带原收款流水号；平台退款必须带平台侧退款单号；
  -- 退回余额两条都不需要（钱没出过支付渠道，没有什么可查的）。
  CHECK(refund_method <> 'ORIGINAL' OR btrim(original_payment_ref) <> ''),
  CHECK(refund_method <> 'PLATFORM' OR btrim(platform_refund_ref) <> ''),

  -- ⚠️ **刻意不写「refund_amount ≤ 售后单申请金额」的表级 CHECK**：
  --   那需要一条跨表子查询，而售后单表开着 FORCE RLS —— CHECK 是在**写入时刻**求值的，
  --   它的报错会混进 RLS 的权限错误里，排查时极难看出真因。
  --   这条约束由 guard_refund_application 真查一次执行（t_available ≤ t_base ⇒ amount ≤ base），
  --   绕过服务层直连 SQL 也一样被拦。见第十一节 ②。

  CHECK(updated_at >= created_at)
);

-- ===========================================================================
-- 十、审批与状态留痕（append-only）
-- ===========================================================================
-- 退款的每一次决定都要能回放：谁提的、谁批的、批了多少钱、后来怎么结的。
-- 改掉或删掉一行等于抹掉这段证据，所以运行角色连 UPDATE/DELETE 权限都没有。
CREATE TABLE refund_application_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  application_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'CREATED','SUBMITTED','FIRST_APPROVED','SECOND_APPROVED','REJECTED',
      'DISPATCHED','SUCCEEDED','FAILED','CANCELLED','UPDATED'
  )),
  -- 变更前后状态：建单那一条 from_status 为 NULL（此前不存在）。
  from_status varchar(24),
  to_status varchar(24) NOT NULL,

  -- 事件金额快照：钱是逐笔审批的，事后必须能回答「批的时候是多少钱」——
  -- 改金额（只有草稿可改）会让「当初批的数字」与「最后退的数字」不是同一个。
  amount_snapshot numeric(19,4) NOT NULL CHECK(amount_snapshot > 0),
  currency_snapshot char(3) NOT NULL CHECK(currency_snapshot ~ '^[A-Z]{3}$'),
  -- 审批依据（必填的占位）：驳回 / 失败带决定理由，通过带审批说明。
  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

-- ===========================================================================
-- 十一、闸二 + 状态机 + 审批规则：**写入时刻的触发器**
-- ===========================================================================
-- guard_refund_application 在 INSERT 与关键列变更时真查五件事：
--   ① 上游锚点存在、属于本租户、币种一致、售后单未结案；
--   ② 可退余额（五桶口径，第二节）—— 蓝图加粗句的主闸；
--   ③ 状态流转合法性（第四节）；
--   ④ 审批规则（第六节）：首次审批必须走对分支（单签 / 双签），
--      且双人签的两个人不同、自己不能批自己；
--   ⑤ 派发的执行记录存在且状态允许（第五节）。
CREATE FUNCTION guard_refund_application() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_case_status varchar(20);
  t_case_currency char(3);
  t_base numeric(19,4);
  t_settled numeric(19,4);
  t_dispatched numeric(19,4);
  t_registered numeric(19,4);
  t_pending numeric(19,4);
  t_disputed numeric(19,4);
  t_available numeric(19,4);
  t_threshold numeric(19,4);
  t_old_status varchar(24);
  t_exec_state varchar(16);
  t_exec_order uuid;
  t_exec_case uuid;
BEGIN
  -- ================================================================ ① 上游锚点
  -- ⚠️ 本表**没有** case_type / order 快照列，case_type 在 aftersales_case 上。
  --   这两个名字写错会直接让触发器报「column does not exist」，
  --   而那是在**第一条写入**时才炸，看起来像迁移没跑对。
  SELECT c.status, c.currency, c.requested_amount
    INTO t_case_status, t_case_currency, t_base
    FROM aftersales_case c
   WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.case_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '售后单不存在或不属于当前租户：case_id=%', NEW.case_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF NEW.currency <> t_case_currency THEN
    RAISE EXCEPTION '退款币种 % 与售后单币种 % 不一致：跨币种退款会让可退余额的比失去意义',
      NEW.currency, t_case_currency
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_case_status IN ('RESOLVED','REJECTED','CLOSED') THEN
    RAISE EXCEPTION '售后单已结案（status=%），不能再发起退款申请', t_case_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- ================================================================ ② 可退余额（五桶）
  -- ⚠️ 每个子查询都带 tenant_id 谓词；RLS 是最后一道，查询本身也带条件。
  -- ⚠️ **本行自己必须排除**：改金额 / 改状态时本行的新值还没生效，
  --   把它算进占用里会让「改大一点的金额」永远触发「超过可退余额」。
  -- ⚠️⚠️ **还要排除「本行即将挂上去的那条执行记录」**（IS DISTINCT FROM NEW.execution_id）：
  --   派发那一刻，同一笔钱会**同时**被两边算 —— 本行的「待审占用」已经退场
  --   （执行记录的桶带着 a.id &lt;&gt; NEW.id 把本行排除掉了），而它指向的那条执行记录占上了额度。
  --   不排除的结果是：**每一次合法的派发都会被判成超额**，而症状极其隐蔽 ——
  --   占用看起来「凭空多出一笔」，真去查会发现两笔都是自己人。
  --   IS DISTINCT FROM 对 null 也正确：还没挂执行记录时它不排除任何一行。
  -- ⚠️⚠️ **还要排除「本行即将挂上去的那条执行记录」**（`e.id is distinct from new.execution_id`）：
  --   派发那一刻，同一笔钱会**同时**被两边算 —— 本行的「待审占用」还没退场（执行记录的桶
  --   带着 `a.id <> new.id` 把本行排除掉了），而它指向的那条执行记录已经占上额度了。
  --   不排除的结果是：**每一次合法的派发都会被判成超额**，
  --   而症状极其隐蔽 —— 占用看起来「凭空多出一笔」，真去查会发现两笔都是自己人。
  --   `is distinct from` 对 null 也正确：还没挂执行记录时它不排除任何一行。
  SELECT coalesce(sum(e.refund_amount), 0) INTO t_settled
    FROM refund_execution e
   WHERE e.tenant_id = NEW.tenant_id
     AND e.order_id = NEW.order_id
     AND (e.case_id = NEW.case_id OR e.case_id IS NULL)
     AND e.id IS DISTINCT FROM NEW.execution_id
     AND e.execution_state = 'SUCCEEDED';

  SELECT coalesce(sum(e.refund_amount), 0) INTO t_dispatched
    FROM refund_execution e
   WHERE e.tenant_id = NEW.tenant_id
     AND e.order_id = NEW.order_id
     AND (e.case_id = NEW.case_id OR e.case_id IS NULL)
     AND e.id IS DISTINCT FROM NEW.execution_id
     AND e.execution_state IN ('IN_FLIGHT','UNKNOWN');

  SELECT coalesce(sum(e.refund_amount), 0) INTO t_registered
    FROM refund_execution e
   WHERE e.tenant_id = NEW.tenant_id
     AND e.order_id = NEW.order_id
     AND (e.case_id = NEW.case_id OR e.case_id IS NULL)
     AND e.id IS DISTINCT FROM NEW.execution_id
     AND e.execution_state = 'NOT_INITIATED';

  SELECT coalesce(sum(e.refund_amount), 0) INTO t_disputed
    FROM refund_execution e
   WHERE e.tenant_id = NEW.tenant_id
     AND e.order_id = NEW.order_id
     AND (e.case_id = NEW.case_id OR e.case_id IS NULL)
     AND e.id IS DISTINCT FROM NEW.execution_id
     AND e.execution_state = 'DISCREPANCY';

  -- 本表侧：**未挂执行记录**的申请单（挂了 execution_id 的改由执行记录的桶计一次）。
  SELECT coalesce(sum(a.refund_amount), 0) INTO t_pending
    FROM refund_application a
   WHERE a.tenant_id = NEW.tenant_id
     AND a.case_id = NEW.case_id
     AND a.id <> NEW.id
     AND a.execution_id IS NULL
     AND a.application_status IN ('PENDING_APPROVAL','APPROVED',
                                  'SECOND_APPROVAL_PENDING','SECOND_APPROVED','EXECUTING');

  -- ⚠️ **刻意没有「已成功但未挂执行记录」这一桶**：它看起来该有（人工登记成功时不挂执行记录），
  --   但表级 CHECK 规定 dispatched_at 非空 ⇒ execution_id 非空，而 dispatched_at 在
  --   EXECUTING / SUCCEEDED / FAILED 三态都必须非空 ⇒ execution_id 在这三态恒非空。
  --   换句话说这条分支**走不到**：写一个永远为 0 的桶，只会让读代码的人以为存在一条
  --   「人工成功且不挂执行记录」的路径，而实际上没有任何动作能产生它。已退一律来自执行记录。

  t_available := coalesce(t_base, 0) - (coalesce(t_settled,0) + coalesce(t_dispatched,0)
             + coalesce(t_registered,0) + coalesce(t_pending,0) + coalesce(t_disputed,0));

  -- ⚠️ **草稿同样要判**：占用（pending 桶）只统计**别的**单，草稿自己不在里面，
  --   所以判据对草稿与在审单是同一条：金额不得超过当前可退余额。
  --   少了这一条就能先存一张超额草稿，等别单的额度退回来再提交，绕过一次校验。
  IF NEW.refund_amount > coalesce(t_available, 0) THEN
    RAISE EXCEPTION '退款金额 % 超过可退余额 %（基数 %、已退 %、在途派发 %、已登记待发起 %、待审占用 %、对账争议 %）',
      NEW.refund_amount, t_available, coalesce(t_base,0), coalesce(t_settled,0),
      coalesce(t_dispatched,0), coalesce(t_registered,0), coalesce(t_pending,0),
      coalesce(t_disputed,0)
      USING ERRCODE = 'check_violation';
  END IF;

  -- ================================================================ ③ 状态流转
  IF TG_OP = 'UPDATE' THEN
    t_old_status := OLD.application_status;
    IF NEW.application_status <> OLD.application_status THEN
      IF NOT (
        (OLD.application_status = 'DRAFT'
          AND NEW.application_status IN ('PENDING_APPROVAL','CANCELLED'))
     OR (OLD.application_status = 'PENDING_APPROVAL'
          AND NEW.application_status IN ('APPROVED','SECOND_APPROVAL_PENDING','REJECTED','CANCELLED'))
     OR (OLD.application_status = 'SECOND_APPROVAL_PENDING'
          AND NEW.application_status IN ('SECOND_APPROVED','REJECTED','CANCELLED'))
     OR (OLD.application_status IN ('APPROVED','SECOND_APPROVED')
          AND NEW.application_status IN ('EXECUTING','CANCELLED'))
     OR (OLD.application_status = 'EXECUTING'
          AND NEW.application_status IN ('SUCCEEDED','FAILED'))
      ) THEN
        RAISE EXCEPTION '非法的退款申请状态流转：% → %', OLD.application_status, NEW.application_status
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;

  -- ================================================================ ④ 审批规则
  -- 只在**首次审批落定**的那一刻判一次：阈值是「提交时点的规则」，
  -- 提交之后再改规则不该让一张待审的单凭空多出一次审批（或凭空少掉）。
  IF NEW.application_status IN ('APPROVED','SECOND_APPROVAL_PENDING','SECOND_APPROVED') THEN
    SELECT p.dual_approval_threshold INTO t_threshold
      FROM refund_approval_policy p
     WHERE p.tenant_id = NEW.tenant_id
       AND p.currency = NEW.currency
       AND p.policy_status = 'ACTIVE';

    -- ⚠️⚠️ **必须显式判 NOT FOUND**，不能只写 `SELECT coalesce(col,0) INTO var`：
    --   查不到行时那个 coalesce 保护的是**列值**，而「零行」根本不会产生列值 ——
    --   变量被置成 NULL，于是 `amount >= NULL` 与 `amount < NULL` **两个分支都不进**，
    --   fail-closed 默认从此静默失效（实测：没有配任何规则时，一笔 50 的退款被单签直接放行）。
    IF NOT FOUND THEN
      t_threshold := 0;
    END IF;

    -- ⚠️ **没有配置 ⇒ 阈值取 0 ⇒ 「金额 ≥ 0」恒成立 ⇒ 一律要求双人签**。
    --   没有规则时取最宽松（都不签）会让新租户在没配置任何规则的情况下就能一笔退款直接放行 ——
    --   对一个资损模块，默认必须是拦住而不是放过。
    IF NEW.refund_amount >= t_threshold THEN
      IF NEW.application_status = 'APPROVED' THEN
        RAISE EXCEPTION '退款金额 % 达到双人签阈值 %，首次审批只能推进到 SECOND_APPROVAL_PENDING',
          NEW.refund_amount, t_threshold
          USING ERRCODE = 'check_violation';
      END IF;
    ELSE
      IF NEW.application_status <> 'APPROVED' THEN
        RAISE EXCEPTION '退款金额 % 未达双人签阈值 %，不应进入 SECOND_APPROVAL_PENDING',
          NEW.refund_amount, t_threshold
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;

  -- 复审必须在初审之后（CHECK 已钉两个 id 不同，这里钉时刻顺序）。
  IF NEW.second_approved_at IS NOT NULL AND NEW.first_approved_at IS NOT NULL
     AND NEW.second_approved_at < NEW.first_approved_at THEN
    RAISE EXCEPTION '复审时刻早于初审时刻' USING ERRCODE = 'check_violation';
  END IF;

  -- ================================================================ ⑤ 派发的执行记录
  -- ⚠️⚠️ **只在「这一次真的挂上了执行记录」时才校验**：
  --   触发器是 `UPDATE OF ... application_status ...`，所以登记执行结果、改金额这些
  --   **不碰 execution_id** 的 UPDATE 也会进来。若不加这个条件，
  --   执行记录一旦定论（SUCCEEDED / FAILED / DISCREPANCY），
  --   **每一次后续的状态更新都会被这条挡死** —— 而「派发之后登记结果」恰恰是必经之路。
  --   症状是：派发成功、然后登记结果永远报「执行记录已定论，不能再挂到新的申请上」。
  IF TG_OP = 'INSERT' OR NEW.execution_id IS DISTINCT FROM OLD.execution_id THEN
    IF NEW.execution_id IS NOT NULL THEN
    SELECT e.execution_state, e.order_id, e.case_id
      INTO t_exec_state, t_exec_order, t_exec_case
      FROM refund_execution e
     WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.execution_id;

    IF NOT FOUND THEN
      RAISE EXCEPTION '退款执行记录不存在或不属于当前租户：execution_id=%', NEW.execution_id
        USING ERRCODE = 'no_data_found';
    END IF;

    IF t_exec_order <> NEW.order_id
       OR (t_exec_case IS NOT NULL AND t_exec_case <> NEW.case_id) THEN
      RAISE EXCEPTION '退款执行记录的订单 / 售后单与本申请不一致（execution %←order %/case %，申请 order %/case %）',
        NEW.execution_id, t_exec_order, t_exec_case, NEW.order_id, NEW.case_id
        USING ERRCODE = 'check_violation';
    END IF;

    -- ⚠️ 只许挂**还没定论**的执行记录。挂一条 SUCCEEDED / FAILED / DISCREPANCY 的执行记录
    --   会让「这笔钱退没退」出现两个来源各说各话。
    IF t_exec_state NOT IN ('NOT_INITIATED','IN_FLIGHT','UNKNOWN') THEN
      RAISE EXCEPTION '退款执行记录已定论（state=%），不能再挂到新的申请上', t_exec_state
        USING ERRCODE = 'check_violation';
    END IF;
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_refund_application
  BEFORE INSERT OR UPDATE OF refund_amount, application_status, case_id, execution_id, currency
    ON refund_application
  FOR EACH ROW EXECUTE FUNCTION guard_refund_application();

-- ===========================================================================
-- 十二、状态流转的单点真相：每次状态真的变了就追加一条留痕
-- ===========================================================================
-- 退款审批本身就是纠纷与审计的原材料（平台会问「这笔钱为什么退、谁批的」），
-- 而只留头表上的一个当前状态，事后没人能说清中间改过几次。
-- **只有真的变了才追加**（UPDATE 时状态没变直接返回），否则每次改备注都会留下一条假状态变更。
CREATE FUNCTION refund_application_append_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.application_status = OLD.application_status THEN
    RETURN NEW;
  END IF;

  INSERT INTO refund_application_event(
    id, tenant_id, application_id, event_type, from_status, to_status,
    amount_snapshot, currency_snapshot, reason_note, occurred_by, occurred_at)
  VALUES (
    gen_random_uuid(),
    NEW.tenant_id,
    NEW.id,
    CASE
      WHEN TG_OP = 'INSERT' THEN 'CREATED'
      WHEN NEW.application_status = 'PENDING_APPROVAL'        THEN 'SUBMITTED'
      WHEN NEW.application_status = 'APPROVED'                THEN 'FIRST_APPROVED'
      WHEN NEW.application_status = 'SECOND_APPROVAL_PENDING' THEN 'FIRST_APPROVED'
      WHEN NEW.application_status = 'SECOND_APPROVED'         THEN 'SECOND_APPROVED'
      WHEN NEW.application_status = 'REJECTED'                THEN 'REJECTED'
      WHEN NEW.application_status = 'EXECUTING'               THEN 'DISPATCHED'
      WHEN NEW.application_status = 'SUCCEEDED'               THEN 'SUCCEEDED'
      WHEN NEW.application_status = 'FAILED'                  THEN 'FAILED'
      WHEN NEW.application_status = 'CANCELLED'               THEN 'CANCELLED'
      ELSE 'UPDATED'
    END,
    CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.application_status END,
    NEW.application_status,
    NEW.refund_amount,
    NEW.currency,
    CASE
      WHEN TG_OP = 'INSERT'                            THEN '登记退款申请草稿'
      WHEN NEW.application_status = 'PENDING_APPROVAL'  THEN '提交审批'
      WHEN NEW.application_status = 'APPROVED'          THEN coalesce(nullif(btrim(NEW.approval_note), ''), '初审通过（金额未达双人签阈值）')
      WHEN NEW.application_status = 'SECOND_APPROVAL_PENDING' THEN '初审通过，进入复审'
      WHEN NEW.application_status = 'SECOND_APPROVED'   THEN coalesce(nullif(btrim(NEW.approval_note), ''), '复审通过')
      WHEN NEW.application_status = 'REJECTED'          THEN NEW.approval_note
      WHEN NEW.application_status = 'EXECUTING'         THEN '交财务执行'
      WHEN NEW.application_status = 'SUCCEEDED'         THEN '退款执行成功'
      WHEN NEW.application_status = 'FAILED'            THEN NEW.failure_reason
      WHEN NEW.application_status = 'CANCELLED'         THEN NEW.cancel_reason
      ELSE '草稿要素变更'
    END,
    coalesce(NEW.second_approved_by, NEW.first_approved_by, NEW.submitted_by,
             NEW.rejected_by, NEW.dispatched_by, NEW.outcome_recorded_by, NEW.cancelled_by,
             NEW.created_by),
    -- ⚠️ **每类事件用它自己那一刻**，不是 coalesce 出一列通用值：
    --   早期写法写 coalesce(...) 会把「驳回」的时刻记成提交那一刻，
    --   排序退化成按随机 UUID 排，而那是**时好时坏**的那种。
    CASE
      WHEN TG_OP = 'INSERT'                            THEN NEW.created_at
      WHEN NEW.application_status = 'PENDING_APPROVAL'  THEN NEW.submitted_at
      WHEN NEW.application_status = 'APPROVED'          THEN NEW.first_approved_at
      WHEN NEW.application_status = 'SECOND_APPROVAL_PENDING' THEN NEW.first_approved_at
      WHEN NEW.application_status = 'SECOND_APPROVED'   THEN NEW.second_approved_at
      WHEN NEW.application_status = 'REJECTED'          THEN NEW.rejected_at
      WHEN NEW.application_status = 'EXECUTING'         THEN NEW.dispatched_at
      WHEN NEW.application_status = 'SUCCEEDED'         THEN NEW.outcome_recorded_at
      WHEN NEW.application_status = 'FAILED'            THEN NEW.outcome_recorded_at
      WHEN NEW.application_status = 'CANCELLED'         THEN NEW.cancelled_at
      ELSE NEW.updated_at
    END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_refund_application_event
  AFTER INSERT OR UPDATE OF application_status ON refund_application
  FOR EACH ROW EXECUTE FUNCTION refund_application_append_event();

-- ===========================================================================
-- 十三、索引
-- ===========================================================================
-- ① 详情页与「这张售后单退过几笔」：按售后单反查。
CREATE INDEX refund_application_by_case
  ON refund_application(tenant_id, case_id);
-- ② 待审工作面（运营每天的待办队列）：只看在审单，随状态推进自然退出索引。
CREATE INDEX refund_application_pending_review
  ON refund_application(tenant_id, submitted_at)
  WHERE application_status in ('PENDING_APPROVAL','SECOND_APPROVAL_PENDING');
-- ③ 执行中（已交财务、结果未定）：这是最需要盯的一段。
CREATE INDEX refund_application_inflight_execution
  ON refund_application(tenant_id, dispatched_at)
  WHERE application_status = 'EXECUTING';
-- ④ 留痕回放：按发生时刻读一单的完整审批轨迹。
CREATE INDEX refund_application_event_by_application
  ON refund_application_event(tenant_id, application_id, occurred_at);
-- ⑤ 按执行记录反查（对账时从执行记录找到是哪张申请单派出去的）。
CREATE INDEX refund_application_by_execution
  ON refund_application(tenant_id, execution_id)
  WHERE execution_id IS NOT NULL;

-- ===========================================================================
-- 十四、多租户：RLS + 授权（与 V157 / V161 第九节同一套写法）
-- ===========================================================================
-- 三张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：申请单带着**原收款流水号**与**平台退款单号**，
--   且审批 / 派发 / 登记结果都是**按 id 直达**的 URL —— 漏掉租户谓词就是跨租户资金数据泄露。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'refund_approval_policy','refund_application','refund_application_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  refund_approval_policy, refund_application, refund_application_event TO trade_app;

-- 授权在前、收回在后（与 V156 / V159 / V161 同序）。
-- 留痕表**追加式**：审批轨迹是审计与纠纷的原材料，UPDATE/DELETE 一律收回。
-- ⚠️ 这一条必须**真的执行**，不能只写在注释里：注释声称 append-only 而权限没收回时，
--   运行角色仍能改掉「这笔钱当初批的是多少」，而没有任何一处测试会红。
REVOKE UPDATE,DELETE ON refund_application_event FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：aftersales_case 簇（V156/V157）、
-- rma_authorization 簇（V161）、refund_execution 簇（V159）与 sales_order 簇（V99）全部只读。
-- 本模块对它们的全部依赖都是 SELECT / JOIN 与「写入时刻查一次」，跨车道 DDL 回归面 = 0。
