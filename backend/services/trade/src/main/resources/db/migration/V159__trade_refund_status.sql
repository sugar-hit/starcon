-- 11.3.1.2 退款状态（卷 11 售后与退货，资金安全模块）。
--
-- 蓝图 11-aftersales-and-returns.md 原文（11.3.1.2 退款状态）：
--   职责：跟踪退款执行状态与平台同步状态，处理退款失败、外部超时（先查证交易再重试，禁止重复支付）、
--         平台退款与系统退款不一致的差异。
--   输入：12 财务的执行结果回报；平台侧退款查询。
--   输出：退款状态 + 失败原因 + 重试/人工处理建议 + 差异清单。
--   关系：上游 = 12 财务；下游 = 15 客服（通知买家）、9 订单（订单退款状态）。
--   **外部退款超时不等于失败**，重复发起退款会造成实质资损。
--
-- 归属：**trade 服务**（卷 11 的 L2 表 11.3「退款与换货」→ trade → finance，取第一项 trade）。
--
-- ===========================================================================
-- 零、依赖实查结论（编排前已复核集成头 85d2eb48，本车道再次确认）
-- ===========================================================================
-- * 上游 12 财务（12.1.1.1 ~ 12.7.2.2）**全部未开发** —— backend/services/ 下无 finance 服务实现。
-- * 同 L3 的 11.3.1.1「退款申请」**未交付** —— trade 的迁移目录里没有任何退款申请单表。
-- * 下游 15 客服（通知买家）、9 订单（订单退款状态回写）**均未交付**；9 订单卷虽有 24 个模块，
--   但「订单退款状态回写」这一具体对接不存在。
--   ⇒ 本模块**自己拥有**退款执行与平台同步的完整状态模型（这是本迁移存在的理由），
--     并对每一个未交付的对接口在 capabilities 里**如实报 Delivered=false 与归属模块号**。
--     禁止假装已对接、禁止留 DomainPlaceholder、禁止造一张恒空的影子表。
--
-- ⚠️ **12 财务的执行结果回报建模成本表 refund_execution_report，但报告来源被强制标注**：
--   `report_source` 的三个取值里，FINANCE_MODULE_12 在 12 财务交付前**没有任何路径能写入**
--   （服务层拒绝该取值，见 RefundStatusService）。当前真正能写的是 PLATFORM_SYNC 与
--   MANUAL_OPERATOR —— 前者是平台侧退款查询，后者是人工登记。
--   **表结构与查询在 12 财务交付后不用改**：正式接口到位后由它按同一形状 POST 进来，
--   把来源改成 FINANCE_MODULE_12 即可（照抄 V150 order_risk_signal 的同一手法）。
--
-- ===========================================================================
-- 一、蓝图加粗句「外部退款超时不等于失败」的落地方式
-- ===========================================================================
-- 这句话是**正确性要求**，不是文案要求。任何会让同一次退款被发起两次的设计都是资损缺陷。
-- 本迁移用**三道互相独立的闸**把它挡住，每道都有对应的测试：
--
-- 【闸一：状态分离】UNKNOWN 与 FAILED 是两个**互斥**状态，且 DB 层 CHECK 钉死
--   「`FAILED` ⇒ 失败分类是确定性的」。
--   语义：一个拿不准的失败**不是** FAILED，而是 UNKNOWN。
--   CHECK refund_execution_failure_is_definitive：
--     state='FAILED' ⇒ failure_class='DEFINITIVE'
--   于是「外部超时却被写成失败」这种记录在数据库层就进不来。
--
-- 【闸二：查证强制】重试（refund_attempt.attempt_kind='RETRY'）在 DB 层被触发器
--   guard_refund_attempt_retry 挡住：
--     * 目标执行处于 UNKNOWN ⇒ **必须**携带一条 verification_id，其 verdict 必须是
--       CONFIRMED_NOT_SETTLED（查证确认钱**没有**出去）；
--     * verdict=CONFIRMED_SETTLED ⇒ 说明钱其实出去了，此时**重试就是重复退款**，
--       触发器直接拒绝，正确动作是推进到 SUCCEEDED；
--     * verdict=INCONCLUSIVE ⇒ 还是不知道，**不许重试**，必须保持 UNKNOWN。
--   ⇒ 「先查证交易再重试」不是调用方的自觉，是**绕过服务层直接 INSERT 也照样被挡**的规则。
--
-- 【闸三：幂等唯一约束】每一次**派发**都必须带幂等键，
--   refund_attempt 上有 UNIQUE(tenant_id, idempotency_key)。
--   重复提交同一幂等键在数据库层就撞唯一约束 ⇒ 不可能派出第二笔。
--   服务层捕获该冲突并**返回既有那一笔**（同结果），而不是发起第二次退款。
--   另外 refund_execution 上也有 UNIQUE(tenant_id, idempotency_key)：
--   同一笔退款**业务**不会被登记成两条执行记录。
--
-- ⚠️ 为什么不用「乐观锁 / 状态 CHECK」代替幂等键：乐观锁只能挡住**并发**重复，
--   挡不住「运营隔 10 分钟又点了一次」或「上游重试了整个 HTTP 请求」——
--   那时乐观锁版本早就变了，重试会合法地创建第二笔。资损防护必须由**幂等键**承担。
--
-- ⚠️ 追加式证据表（refund_attempt / refund_verification / refund_execution_report /
--   refund_state_event）一律 REVOKE UPDATE,DELETE：
--   能改掉或删掉一条派发记录，就等于能抹掉「这笔钱被派了两次」的证据。
--
-- ===========================================================================
-- 二、状态机（六态）与转换规则
-- ===========================================================================
--   NOT_INITIATED  退款尚未发起（只有退款额与幂等键，还没有对外派发过）
--   IN_FLIGHT      已派发、等待外部结果（正常在途）
--   UNKNOWN        已发起但**结果未知**（外部超时）—— 不是失败，不可直接重试
--   SUCCEEDED      外部/平台确认成功（终态，除非发现对账差异）
--   FAILED         外部**明确**失败（确定性失败，钱确定没有出去）
--   DISCREPANCY    已对账差异（终态，需人工裁定）
--
--   NOT_INITIATED → IN_FLIGHT            首次派发（带幂等键）
--   IN_FLIGHT     → SUCCEEDED            外部回报成功
--   IN_FLIGHT     → FAILED               外部明确拒绝（失败分类必须 DEFINITIVE）
--   IN_FLIGHT     → UNKNOWN              外部超时 / 无响应 ⇒ 结果未知
--   UNKNOWN       → SUCCEEDED            查证结论 CONFIRMED_SETTLED（钱确实出去了）
--   UNKNOWN       → FAILED               查证结论 CONFIRMED_NOT_SETTLED 且渠道已终止
--   UNKNOWN       → IN_FLIGHT            查证结论 CONFIRMED_NOT_SETTLED ⇒ 才允许重试
--   FAILED        → IN_FLIGHT            重试（确定性失败，钱确定没动，无需查证）
--   {SUCCEEDED|FAILED|UNKNOWN} → DISCREPANCY   对账发现平台与系统不一致
--   DISCREPANCY   → SUCCEEDED | FAILED   人工裁定（**只能由人**触发，系统不自动抹平）
--
-- ⚠️ **UNKNOWN → IN_FLIGHT 这一格就是蓝图那句加粗的全部含义**：
--   它存在，但它的前置条件是「有一条查证记录证明钱没出去」，而这个前置条件在 DB 层强制。
-- ⚠️ **没有 UNKNOWN → FAILED 的自动边**：外部超时永远不会自动变成失败，
--   必须先有一条查证记录。这是「超时不等于失败」最直接的一条。
--
-- ===========================================================================
-- 三、领域模型（六张表，逐个对应蓝图的输入 / 输出）
-- ===========================================================================
--   refund_execution        退款执行状态主表        → 输出：退款状态 + 失败原因
--   refund_attempt          每次派发的追加式流水    → 【闸三】幂等派发 + 重试留痕
--   refund_verification     查证交易记录            → 【闸二】先查证再重试的证据
--   refund_execution_report 外部执行结果回报        → 输入：12 财务回报 / 平台侧查询
--   refund_discrepancy      对账差异清单            → 输出：差异清单（如实报，不自动抹平）
--   refund_state_event      状态流转事件流          → 输出：状态历史
--
-- **零 ALTER 零 DROP 既有表**：sales_order 簇（V99/V100/V101，9.1.1.1 车道的写入所有者）
-- 与 aftersales_case 簇（V156，11.1.1.1 车道的写入所有者）全部**只读**。
-- 刻意**不加外键**指向它们：加 FK 会让本迁移对别的车道的表产生 DDL 依赖，
-- 而同批另有五条车道也在往 trade 落迁移。订单与售后单的存在性由服务层在写入口**真查一次**。
-- 跨车道 DDL 回归面 = 0。
CREATE SEQUENCE refund_execution_no_seq;

-- ===========================================================================
-- 四、退款执行状态主表
-- ===========================================================================
-- ⚠️ **没有 shop_code 列**，照抄 V156 的同一条纪律：sales_order 实查没有 shop_code 列（V99），
--   卷 2 的店铺在 channel 服务独立库，按 AGENTS.md 模块边界纪律禁止跨服务 SQL。
--   平台维度落到平台级 order_source（V99 的 CHECK 已含五个平台来源）。
--   **不建一个恒为空的 shop 列**：空列会被读成「这家店没有退款」，
--   而真相是「系统还不知道有哪些店」。
CREATE TABLE refund_execution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 退款号：租户内唯一（UNIQUE(tenant_id, refund_no)）。序列全局，两个租户号段不重叠是额外好处。
  refund_no varchar(32) NOT NULL,

  -- 关联订单（卷 9，V99 只读）。**刻意不加外键**，见文件头第三节。
  order_id uuid NOT NULL,

  -- 关联售后单（11.1.1.1，V156 同库只读）：**可空**。
  -- 11.3.1.1「退款申请」未交付 ⇒ 本模块不假设「每笔退款都来自一张售后申请单」；
  -- 平台直退、赔付类退款同样要跟踪。恒为 null 才是错误建模。
  case_id uuid,

  -- ---- 幂等（【闸三】的另一半，见第一节）----
  -- 退款**业务**幂等键：同一笔退款不会被登记成两条执行记录。
  -- 上游重试整个 HTTP 请求、运营重复点击「登记退款」，撞的就是这个键。
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key) <> ''),

  -- 金额一律 numeric，禁浮点（AGENTS.md）。
  refund_amount numeric(19,4) NOT NULL CHECK(refund_amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ---- 状态机（第二节）----
  execution_state varchar(16) NOT NULL DEFAULT 'NOT_INITIATED' CHECK(execution_state IN (
      'NOT_INITIATED','IN_FLIGHT','UNKNOWN','SUCCEEDED','FAILED','DISCREPANCY'
  )),

  -- 失败分类：**把「失败」再分成确定性与不确定性两半**，这是「超时不等于失败」的落点。
  -- DEFINITIVE  = 外部明确拒绝，钱确定没有出去 ⇒ 允许直接重试
  -- INDETERMINATE = 超时 / 无响应 / 渠道未回执 ⇒ **不许**重试，必须先查证
  -- ⚠️ 组合被下面那条 CHECK 钉死：state='FAILED' ⇒ failure_class='DEFINITIVE'。
  --   写不出「失败但不确定」这种自相矛盾的记录。
  failure_class varchar(16) CHECK(failure_class IN ('DEFINITIVE','INDETERMINATE')),
  -- 失败码：失败时**必填**（CHECK）。取值是对外的稳定机器码，**不硬编码在本表**：
  -- 词表由服务层的 RefundFailureCode 提供（可扩展），本表只做非空与长度约束。
  failure_code varchar(48) NOT NULL DEFAULT '' CHECK(length(failure_code) <= 48),
  -- 失败原因：人话，必须能直接给运营看。
  failure_reason text NOT NULL DEFAULT '' CHECK(length(failure_reason) <= 2000),

  -- ---- 外部交易号 ----
  -- 12 财务 / 支付渠道返回的交易号：查证交易时**按它**去查，不是凭空查。
  -- 刻意允许为空：第一次派发时我们还不知道它。
  external_transaction_ref varchar(128) NOT NULL DEFAULT '',

  -- ---- 平台同步状态（与执行状态是**两条正交的轴**）----
  -- 蓝图职责句写的是「跟踪退款执行状态**与**平台同步状态」——两件事，不是同一件事的两种说法。
  -- 一笔原路退款可能压根不经过平台（platform_refund_method='ORIGINAL'）⇒ NOT_REQUESTED，
  -- 这与「平台还没同步」是完全不同的两件事，合成一个值会让前者被读成后者而漏同步。
  platform_sync_state varchar(16) NOT NULL DEFAULT 'NOT_REQUESTED' CHECK(platform_sync_state IN (
      'NOT_REQUESTED','PENDING','SYNCED','DISCREPANCY'
  )),
  -- 平台侧退款单号：与平台对账的钥匙（与外部交易号不是一回事，后者是支付渠道的）。
  platform_refund_no varchar(128) NOT NULL DEFAULT '',
  -- 平台退款方式（决定要不要走平台同步这条轴）：
  -- ORIGINAL 原路退回（走支付渠道，不经平台）/ PLATFORM 平台侧退款 / BALANCE 退回余额
  platform_refund_method varchar(16) NOT NULL DEFAULT 'ORIGINAL'
    CHECK(platform_refund_method IN ('ORIGINAL','PLATFORM','BALANCE')),

  -- ---- 时刻 ----
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- 首次派发时刻（NOT_INITIATED 时为空）。
  initiated_at timestamptz,
  -- 最近一次**查证**时刻（不是最近一次派发时刻）——两个含义不同的时刻刻意分成两列。
  last_verified_at timestamptz,
  -- 明确结论时刻：有了确定结论（成功 / 明确失败 / 已进入差异）才有它。
  resolved_at timestamptz,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,refund_no),
  -- 【闸三】退款业务幂等键的 DB 兜底。
  UNIQUE(tenant_id,idempotency_key),

  -- 【闸一】**失败必须是确定性的**。
  -- 一个拿不准的失败不是 FAILED，是 UNKNOWN。这条 CHECK 让「超时被写成失败」
  -- 在数据库层就无处可写 —— 它是本模块最重要的一条约束。
  CONSTRAINT refund_execution_failure_is_definitive
    CHECK(execution_state <> 'FAILED' OR failure_class = 'DEFINITIVE'),

  -- 失败态必须给出机器码与人话原因：蓝图输出句点名了「失败原因」。
  CONSTRAINT refund_execution_failure_has_reason
    CHECK(execution_state NOT IN ('FAILED','UNKNOWN') OR btrim(failure_reason) <> ''),

  -- 非失败态不许挂失败分类与失败码：一张 SUCCEEDED 的单上写着 failure_code='TIMEOUT'
  -- 会被读成「它其实超时过」，而真相是它成功了。
  CONSTRAINT refund_execution_success_has_no_failure
    CHECK(execution_state NOT IN ('SUCCEEDED','DISCREPANCY')
          OR (failure_code = '' AND failure_reason = '' AND failure_class IS NULL)),

  -- 明确结论 ⇔ resolved_at 严格等价（与 V156 同一写法，挡同一个方向的两种漏：
  -- 只改状态不落时刻 ⇒ 结果未知会带着一个假的结论时刻；
  -- 带时刻却不进结论态 ⇒ 一张在途单被误报成已定论）。
  CONSTRAINT refund_execution_resolved_matches_state
    CHECK((execution_state IN ('SUCCEEDED','FAILED','DISCREPANCY')) = (resolved_at IS NOT NULL)),

  -- 首次派发 ⇔ initiated_at：NOT_INITIATED 是「还没对外派发过」，
  -- 它与「派发过但结果未知」（UNKNOWN）必须能被时间列区分开。
  CONSTRAINT refund_execution_initiated_matches_state
    CHECK((execution_state = 'NOT_INITIATED') = (initiated_at IS NULL)),

  -- UNKNOWN 态**必须**已经派发过：结果未知的前提是「已经发起过」。
  CONSTRAINT refund_execution_unknown_was_initiated
    CHECK(execution_state <> 'UNKNOWN' OR initiated_at IS NOT NULL),

  -- 只有走平台的退款才谈得上「平台同步状态」。
  -- 恒等式而不是单向蕴含：既挡住「原路退款却报 SYNCED」（不存在的同步被编造出来），
  -- 也挡住「平台退款却停在 NOT_REQUESTED」（真需要同步的单被静默漏掉）。
  CONSTRAINT refund_execution_platform_sync_matches_method
    CHECK((platform_refund_method = 'PLATFORM') = (platform_sync_state <> 'NOT_REQUESTED'))
);

-- ===========================================================================
-- 五、派发流水（append-only）——【闸三】的落点
-- ===========================================================================
-- 每一笔**对外派发**（首次或重试）一条。UNIQUE(tenant_id, idempotency_key) 是 DB 层的最后一道：
-- 重复提交同一幂等键 ⇒ 数据库层面就撞不出第二笔。
-- ⚠️ append-only：REVOKE UPDATE,DELETE（第九节）—— 改掉一条派发记录等于抹掉重复派发的证据。
CREATE TABLE refund_attempt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,

  -- 派发幂等键：与退款业务幂等键是**两个不同的键**。
  -- 业务键标识「这是哪一笔退款」，派发键标识「这是第几次派发」——
  -- 一笔退款被重试 3 次是正常的，但**同一个派发键**出现两次就是重复派发（资损）。
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key) <> ''),

  -- 首次派发 vs 重试。重试受【闸二】的触发器约束（必须先查证）。
  attempt_kind varchar(8) NOT NULL CHECK(attempt_kind IN ('INITIAL','RETRY')),

  -- 第几次派发：从 1 起连续。UNIQUE(tenant_id,execution_id,attempt_no) 保证不重号。
  attempt_no int NOT NULL CHECK(attempt_no >= 1),

  -- 本次派发所依据的查证记录：**RETRY 必填**（表级 CHECK，见下）。
  -- INITIAL 不许带：还没派发过就查证，查的是空气。
  verification_id uuid,

  -- 本次派发时执行所处的状态（快照）：出问题时能看出「当时以为是什么状态就派了」。
  state_at_dispatch varchar(16) NOT NULL CHECK(state_at_dispatch IN (
      'NOT_INITIATED','IN_FLIGHT','UNKNOWN','SUCCEEDED','FAILED','DISCREPANCY'
  )),

  dispatched_at timestamptz NOT NULL,
  -- 派发通道：FINANCE（交 12 财务执行）/ PLATFORM（平台侧退款）/ BALANCE（退回余额）。
  dispatch_channel varchar(16) NOT NULL CHECK(dispatch_channel IN (
      'FINANCE','PLATFORM','BALANCE'
  )),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 【闸三】派发幂等键的 DB 兜底。
  UNIQUE(tenant_id,idempotency_key),
  UNIQUE(tenant_id,execution_id,attempt_no),

  -- 重试必须带查证证据；首次派发不许带。**表级 CHECK**，
  -- 与触发器 guard_refund_attempt_retry 一起构成【闸二】：这条挡住「重试没查证」，
  -- 触发器再挡住「查证了但结论是不许重试」。
  CONSTRAINT refund_attempt_retry_needs_verification
    CHECK((attempt_kind = 'RETRY') = (verification_id IS NOT NULL))
);

-- ===========================================================================
-- 六、查证交易记录（append-only）——【闸二】的证据来源
-- ===========================================================================
-- 「先查证交易再重试」里的「查证交易」指的就是这张表的一行：
-- 按 external_transaction_ref 去外部/平台查这笔交易到底出没出去，结论落在这里。
CREATE TABLE refund_verification (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,

  -- 查证来源：
  -- PLATFORM_QUERY  平台侧退款查询（蓝图输入句点名的第二个输入）
  -- FINANCE_QUERY   12 财务侧执行结果查询（12 财务交付前不可用，见 capabilities）
  -- MANUAL          人工查证（运营拿支付渠道后台截图登记）
  verify_source varchar(16) NOT NULL CHECK(verify_source IN (
      'PLATFORM_QUERY','FINANCE_QUERY','MANUAL'
  )),

  -- 查证结论：**三值**，没有第四种。
  -- CONFIRMED_SETTLED     确认钱已经出去了 ⇒ 只能推进到 SUCCEEDED，重试就是重复退款
  -- CONFIRMED_NOT_SETTLED 确认钱没有出去 ⇒ 才允许重试
  -- INCONCLUSIVE          还是不知道（查不到 / 渠道仍未回执）⇒ 必须保持 UNKNOWN
  -- ⚠️ 刻意不给「默认确认未支付」这个便捷值：查不出来就是查不出来，
  --   把查不出来当成没付出去，正是重复退款的起点。
  verdict varchar(24) NOT NULL CHECK(verdict IN (
      'CONFIRMED_SETTLED','CONFIRMED_NOT_SETTLED','INCONCLUSIVE'
  )),

  -- 被查证的交易号：查证必须针对一个**具体**交易，查「大概是哪一笔」不算查证。
  transaction_ref varchar(128) NOT NULL CHECK(btrim(transaction_ref) <> ''),

  -- 查证备注：人话。INCONCLUSIVE 时**必填**（CHECK）——
  -- 查不出结果却不写原因，人工接手时拿不到任何线索。
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),

  verified_at timestamptz NOT NULL,
  -- 查证人（查证是人的动作，即使是接口自动查也要记下是谁触发的这次查证）。
  verified_by uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,execution_id,verified_at),

  CONSTRAINT refund_verification_inconclusive_has_note
    CHECK(verdict <> 'INCONCLUSIVE' OR btrim(note) <> ''),
  -- 确认已支付必须带交易号回执：说「确认付了」却给不出交易号，这条查证没有可复核的凭据。
  CONSTRAINT refund_verification_settled_has_ref
    CHECK(verdict <> 'CONFIRMED_SETTLED' OR btrim(transaction_ref) <> '')
);

-- ===========================================================================
-- 七、外部执行结果回报（append-only）——蓝图输入句「12 财务的执行结果回报」
-- ===========================================================================
-- ⚠️ **12 财务在别的服务独立库里，按 AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 视图**
--   ⇒ 12 财务交付后走**授权 API / event** 回报，由本服务的端点接收并落这张表。
--   12 财务交付前，FINANCE_MODULE_12 这个来源在服务层**被拒绝**（见 RefundStatusService），
--   真正能写的是 PLATFORM_SYNC（平台侧退款查询）与 MANUAL_OPERATOR（人工登记）。
--   **表结构与查询都不用改** —— 正式接口到位后换个来源值 POST 进来即可。
CREATE TABLE refund_execution_report (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,

  -- 报告来源：三个值全部在 capabilities 里如实报出可用性。
  report_source varchar(20) NOT NULL CHECK(report_source IN (
      'FINANCE_MODULE_12','PLATFORM_SYNC','MANUAL_OPERATOR'
  )),

  -- 回报结论。**TIMEOUT 与 UNKNOWN 都不允许把执行推进到 FAILED** ——
  -- 那正是「超时不等于失败」要挡住的事，由状态机（Java 侧 + 测试）强制。
  report_outcome varchar(16) NOT NULL CHECK(report_outcome IN (
      'SUCCEEDED','FAILED','TIMEOUT','UNKNOWN'
  )),

  -- 回报声称的失败分类。TIMEOUT / UNKNOWN 必须给 INDETERMINATE，
  -- SUCCESS 态不许给（CHECK）—— 与 refund_execution 的 CHECK 同一套语义。
  failure_class varchar(16) CHECK(failure_class IN ('DEFINITIVE','INDETERMINATE')),

  external_transaction_ref varchar(128) NOT NULL DEFAULT '',
  reported_amount numeric(19,4) CHECK(reported_amount IS NULL OR reported_amount > 0),
  reported_currency char(3) CHECK(reported_currency IS NULL OR reported_currency ~ '^[A-Z]{3}$'),

  failure_code varchar(48) NOT NULL DEFAULT '' CHECK(length(failure_code) <= 48),
  failure_reason text NOT NULL DEFAULT '' CHECK(length(failure_reason) <= 2000),

  reported_at timestamptz NOT NULL,
  -- 报告被本服务收到 / 登记的时刻（与 reported_at 是两件事：一个是外部说的时刻，
  -- 一个是我们落库的时刻；网络延迟会让两者不同，混用会让超时判定偏掉）。
  received_at timestamptz NOT NULL DEFAULT now(),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 超时与未知必须标成不确定性失败 —— 与 refund_execution 的 DEFINITIVE CHECK 同源。
  CONSTRAINT refund_report_timeout_is_indeterminate
    CHECK(report_outcome NOT IN ('TIMEOUT','UNKNOWN') OR failure_class = 'INDETERMINATE'),
  -- 确定性失败必须标成 DEFINITIVE，不能含糊。
  CONSTRAINT refund_report_failed_is_definitive
    CHECK(report_outcome <> 'FAILED' OR failure_class = 'DEFINITIVE'),
  -- 成功态不许带失败分类与失败码。
  CONSTRAINT refund_report_success_has_no_failure
    CHECK(report_outcome <> 'SUCCEEDED' OR (failure_class IS NULL AND failure_code = '' AND failure_reason = '')),
  -- 失败与未知必须给出失败码 + 人话原因（蓝图输出句：退款状态 + 失败原因）。
  CONSTRAINT refund_report_failure_has_reason
    CHECK(report_outcome NOT IN ('FAILED','TIMEOUT','UNKNOWN')
          OR (btrim(failure_code) <> '' AND btrim(failure_reason) <> '')),
  -- 声称成功必须带金额与币种：没有金额的「成功」无法与差异清单里的金额口径对上账。
  CONSTRAINT refund_report_success_has_amount
    CHECK(report_outcome <> 'SUCCEEDED' OR (reported_amount IS NOT NULL AND reported_currency IS NOT NULL)),
  -- 金额与币种同进同出。
  CONSTRAINT refund_report_amount_currency_together
    CHECK((reported_amount IS NULL) = (reported_currency IS NULL))
);

-- ===========================================================================
-- 八、对账差异清单（append-only + 受限的裁定列）
-- ===========================================================================
-- 蓝图：「平台退款与系统退款不一致的差异」→ 输出「差异清单」。
-- **如实报差异，不自动抹平**：检测是自动的（对账动作触发），**裁定只能由人做**。
CREATE TABLE refund_discrepancy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,

  -- 差异类型（蓝图点名的是金额 / 币种 / 时间，本迁移把「存在性」也纳进来：
  -- 平台有一笔退款而系统没有（或反过来）是**最严重**的一类，
  -- 漏报它等于让一笔平台退款永远对不上账）。
  discrepancy_kind varchar(16) NOT NULL CHECK(discrepancy_kind IN (
      'AMOUNT','CURRENCY','TIMING','EXISTENCE'
  )),

  -- 系统侧的值（我们记的）
  system_amount numeric(19,4) CHECK(system_amount IS NULL OR system_amount >= 0),
  system_currency char(3) CHECK(system_currency IS NULL OR system_currency ~ '^[A-Z]{3}$'),
  system_state varchar(16) CHECK(system_state IS NULL OR system_state IN (
      'NOT_INITIATED','IN_FLIGHT','UNKNOWN','SUCCEEDED','FAILED','DISCREPANCY'
  )),
  system_refunded_at timestamptz,

  -- 平台侧的值（平台查回来的）——「与系统退款不一致」的两边都必须**留原值**，
  -- 只报「不一致」而不报两边是什么，运营无法判断该改哪边。
  platform_amount numeric(19,4) CHECK(platform_amount IS NULL OR platform_amount >= 0),
  platform_currency char(3) CHECK(platform_currency IS NULL OR platform_currency ~ '^[A-Z]{3}$'),
  platform_state varchar(16) CHECK(platform_state IS NULL OR platform_state IN (
      'NOT_INITIATED','IN_FLIGHT','UNKNOWN','SUCCEEDED','FAILED','DISCREPANCY'
  )),
  platform_refunded_at timestamptz,

  -- 差异说明：人话，必须能让运营在不打开别的系统的情况下判断严重性。
  detail text NOT NULL CHECK(length(detail) <= 2000),

  -- 裁定状态。**OPEN → ACKNOWLEDGED → RESOLVED** 只由人的动作推进。
  resolution_state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(resolution_state IN (
      'OPEN','ACKNOWLEDGED','RESOLVED'
  )),
  resolution_note text NOT NULL DEFAULT '' CHECK(length(resolution_note) <= 2000),
  resolved_at timestamptz,
  resolved_by uuid,

  detected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- **每一类差异都必须带齐它该有的证据**（按类型钉死，见服务层 guardDiscriminantEvidence）：
  -- 一条「金额不一致」却不记任何一边金额的差异行，在清单上无法复核，等于噪音。
  CONSTRAINT refund_discrepancy_kind_has_evidence CHECK(
       (discrepancy_kind = 'AMOUNT' AND system_amount IS NOT NULL AND platform_amount IS NOT NULL
          AND system_amount <> platform_amount)
    OR (discrepancy_kind = 'CURRENCY' AND system_currency IS NOT NULL AND platform_currency IS NOT NULL
          AND system_currency <> platform_currency)
    OR (discrepancy_kind = 'TIMING' AND system_refunded_at IS NOT NULL AND platform_refunded_at IS NOT NULL
          AND system_refunded_at <> platform_refunded_at)
    OR (discrepancy_kind = 'EXISTENCE'
          AND ((system_state IS NOT NULL)::int + (platform_state IS NOT NULL)::int = 1))
  ),

  -- 裁定 ⇔ resolved_at：进 RESOLVED 必须带时刻与裁定人，出 RESOLVED 必须清空。
  -- 只改状态不落时刻 ⇒ 一条已裁定的差异仍显示为待办，人工会重复处理。
  CONSTRAINT refund_discrepancy_resolution_matches_time
    CHECK((resolution_state = 'RESOLVED') = (resolved_at IS NOT NULL AND resolved_by IS NOT NULL)),

  -- 未裁定的差异不许带裁定说明：把未裁定的差异说成已裁定，正是「自动抹平」的伪装形式。
  CONSTRAINT refund_discrepancy_unresolved_has_no_note
    CHECK(resolution_state <> 'RESOLVED' OR btrim(resolution_note) <> '')
);

-- ===========================================================================
-- 九、状态流转事件流（append-only）
-- ===========================================================================
-- 与 V156 的 aftersales_event 同一套写法：每次状态转换一条，from/to 都留原值，
-- 「这张单为什么从 UNKNOWN 变成了 SUCCEEDED」必须能从事件流里查出来
-- （尤其是中间那次查证结论 —— 它是全部资损防护的证据）。
CREATE TABLE refund_status_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'REGISTERED','DISPATCHED','REPORT_RECEIVED','VERIFIED',
      'PLATFORM_SYNCED','DISCREPANCY_DETECTED','DISCREPANCY_RESOLVED','MANUAL_FLAGGED'
  )),

  from_state varchar(16) CHECK(from_state IS NULL OR from_state IN (
      'NOT_INITIATED','IN_FLIGHT','UNKNOWN','SUCCEEDED','FAILED','DISCREPANCY'
  )),
  to_state varchar(16) NOT NULL CHECK(to_state IN (
      'NOT_INITIATED','IN_FLIGHT','UNKNOWN','SUCCEEDED','FAILED','DISCREPANCY'
  )),

  -- 事件详情：人话 / 机器可读补充（如查证结论、失败码）。
  detail text NOT NULL DEFAULT '' CHECK(length(detail) <= 2000),
  -- 关联的查证 / 回报 / 差异记录：让事件流能直接追到证据行，而不是只有一句描述。
  verification_id uuid,
  report_id uuid,
  discrepancy_id uuid,
  actor_membership_id uuid,

  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- **状态真变了的事件（from_state 非空）必须 from ≠ to**。
  -- ⚠️ 这条 CHECK 刻意**不**要求 from_state 非空：登记、查证、收到报告、平台同步、
  --   差异检出 / 裁定这些事件**不改变执行状态**（或说「状态还没动」），
  --   强行给它们编一个 from/to 会造出一堆假的状态转换记录 ——
  --   而事件流的价值恰恰在于它记的是**真发生过的**转换。
  --   from_state 为空 ⇒ 这条事件没有伴随状态转换；to_state 始终是事件发生后的真实状态。
  CONSTRAINT refund_status_event_change_is_real
    CHECK(from_state IS NULL OR from_state <> to_state)
);

-- ===========================================================================
-- 十、护栏触发器：把蓝图硬规则钉在 DB 层（绕过服务层也照样生效）
-- ===========================================================================
-- ⚠️ **为什么规则要落成触发器而不只是服务层 if**：
--   AGENTS.md 要求「关键 SQL 必须可审阅」，而**只有服务层 if 挡住的规则挡不住**
--   「有人直接连库插一行」「某个脚本绕过应用」「将来某个模块写错了调用顺序」。
--   本触发器是【闸二】的执行点，集成测试里有专门的用例**绕过服务层直接 INSERT**
--   来证明它真的生效（并用对照组证明那条插入语句本身没写坏）。
CREATE FUNCTION guard_refund_attempt_retry() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_state varchar(16);
  t_verdict varchar(24);
  t_ver_execution uuid;
  t_ver_tenant uuid;
  t_attempt_no int;
BEGIN
  -- 锁住执行记录：并发重试时第二个事务会等在这里，
  -- 等完再读到的状态已经是第一个事务推进过的状态 ⇒ 两个并发重试只有一个能过。
  SELECT execution_state INTO t_state
    FROM refund_execution
   WHERE tenant_id = NEW.tenant_id AND id = NEW.execution_id
     FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION '退款执行记录不存在或不属于当前租户：execution_id=%', NEW.execution_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- attempt_no 必须连续递增（防重号 / 防跳号：跳号会让「第几次派发」对不上账）。
  SELECT coalesce(max(attempt_no), 0) + 1 INTO t_attempt_no
    FROM refund_attempt
   WHERE tenant_id = NEW.tenant_id AND execution_id = NEW.execution_id;
  IF NEW.attempt_no <> t_attempt_no THEN
    RAISE EXCEPTION '派发序号必须连续递增：期望 %，收到 %', t_attempt_no, NEW.attempt_no
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.attempt_kind = 'INITIAL' THEN
    IF t_state <> 'NOT_INITIATED' THEN
      RAISE EXCEPTION '首次派发要求执行处于 NOT_INITIATED，当前是 %', t_state
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  -- ===== 重试：这里就是蓝图加粗句的执行点 =====
  -- 已经成功或已进差异 ⇒ 重试就是重复退款，直接拒。
  IF t_state IN ('SUCCEEDED','DISCREPANCY') THEN
    RAISE EXCEPTION '执行已处于 %，重试会造成重复退款（实质资损），已拒绝', t_state
      USING ERRCODE = 'check_violation';
  END IF;

  -- 还在途 ⇒ 结果没到就再发一次 = 重复支付，直接拒。
  IF t_state = 'IN_FLIGHT' THEN
    RAISE EXCEPTION '执行仍在途（IN_FLIGHT），结果未到就重发会造成重复支付，已拒绝'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 尚未发起过 ⇒ 不存在「重试」，那应该是首次派发。
  IF t_state = 'NOT_INITIATED' THEN
    RAISE EXCEPTION '执行尚未发起（NOT_INITIATED），应走首次派发而不是重试'
      USING ERRCODE = 'check_violation';
  END IF;

  -- FAILED：确定性失败，钱确定没动 ⇒ 允许重试（表级 CHECK 已保证带了查证记录，
  -- 但这里不依赖那条 —— 下面统一要求查证结论为 CONFIRMED_NOT_SETTLED）。
  -- UNKNOWN：**必须先查证交易**，这是本触发器存在的全部理由。

  -- 查证记录必须存在、属于同一笔执行、属于同一租户。
  IF NEW.verification_id IS NULL THEN
    RAISE EXCEPTION '重试必须先查证交易：未携带查证记录（执行状态 %）', t_state
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT verdict, execution_id, tenant_id INTO t_verdict, t_ver_execution, t_ver_tenant
    FROM refund_verification
   WHERE tenant_id = NEW.tenant_id AND id = NEW.verification_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '查证记录不存在或不属于当前租户：verification_id=%', NEW.verification_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_ver_execution <> NEW.execution_id THEN
    RAISE EXCEPTION '查证记录不属于这笔退款：verification 属于 execution=%，本次派发属于 %',
      t_ver_execution, NEW.execution_id USING ERRCODE = 'check_violation';
  END IF;

  -- 查证结论决定能不能重试 —— 「查证结果决定能否重试」这条规则在这里落成可执行判定。
  IF t_verdict = 'CONFIRMED_SETTLED' THEN
    RAISE EXCEPTION '查证结论为 CONFIRMED_SETTLED（钱已付出），重试将造成重复退款，已拒绝；'
      '正确动作是把执行推进到 SUCCEEDED' USING ERRCODE = 'check_violation';
  END IF;

  IF t_verdict = 'INCONCLUSIVE' THEN
    RAISE EXCEPTION '查证结论为 INCONCLUSIVE（仍无法确认），不允许重试；'
      '执行必须保持 UNKNOWN 直到查证出确定结论' USING ERRCODE = 'check_violation';
  END IF;

  -- 只有 CONFIRMED_NOT_SETTLED 能走到这里。
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_refund_attempt_retry
  BEFORE INSERT ON refund_attempt
  FOR EACH ROW EXECUTE FUNCTION guard_refund_attempt_retry();

-- ---------------------------------------------------------------------------
-- 第二道触发器：状态转换规则本身（DB 层也钉死，第二条实现不是多余的）
-- ---------------------------------------------------------------------------
-- ⚠️ 状态机在 Java 侧（RefundStatusStateMachine）是**唯一**的业务真身；
--   本触发器是它的 DB 镜像。之所以要两份：Java 侧挡住 API 误用，
--   DB 侧挡住「绕过应用直接改状态」与「将来另一个模块写了错误的调用顺序」。
--   两份规则刻意**逐条对齐**，集成测试里有一例**同时**比对两侧的可转换集合，
--   一旦某一侧漏了一条边就会红。
CREATE FUNCTION refund_transition_allowed(from_state varchar, to_state varchar)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE from_state
    WHEN 'NOT_INITIATED' THEN to_state IN ('IN_FLIGHT')
    -- IN_FLIGHT 也能进差异：平台侧对账可能在我们还在等回执时就查出一笔不一致的退款，
    -- 那是真实存在的业务情况（平台已退、系统未认），不该被状态机挡住。
    WHEN 'IN_FLIGHT'     THEN to_state IN ('SUCCEEDED','FAILED','UNKNOWN','DISCREPANCY')
    -- UNKNOWN：可查到成功、可查到明确失败、可重试、可进差异；
    -- **刻意没有 UNKNOWN → FAILED 的无条件自动边**：超时永远不能自己变成失败，
    -- 必须经由一条 CONFIRMED_NOT_SETTLED 的查证。
    WHEN 'UNKNOWN'       THEN to_state IN ('SUCCEEDED','FAILED','IN_FLIGHT','DISCREPANCY')
    WHEN 'FAILED'        THEN to_state IN ('IN_FLIGHT','DISCREPANCY')
    WHEN 'SUCCEEDED'     THEN to_state IN ('DISCREPANCY')
    WHEN 'DISCREPANCY'   THEN to_state IN ('SUCCEEDED','FAILED')
    ELSE false
  END
$$;

CREATE FUNCTION guard_refund_execution_transition() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.execution_state IS DISTINCT FROM OLD.execution_state THEN
    IF NOT refund_transition_allowed(OLD.execution_state, NEW.execution_state) THEN
      RAISE EXCEPTION '非法的退款状态转换：% → %（合法目标：见状态机）',
        OLD.execution_state, NEW.execution_state USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_refund_execution_transition
  BEFORE UPDATE OF execution_state ON refund_execution
  FOR EACH ROW EXECUTE FUNCTION guard_refund_execution_transition();

-- ===========================================================================
-- 十一、索引：每一条都对应一个真实会被跑的查询形状
-- ===========================================================================
-- ① **待处置队列**：UNKNOWN / FAILED / DISCREPANCY 是「需要人看一眼」的三种状态。
--    部分索引 ⇒ 索引里只有待处置的行，列表的默认视图直接走它。
--    ⚠️ 这条索引是本模块最重要的性能索引：运营每天打开的就是这个视图。
CREATE INDEX refund_execution_action_queue
  ON refund_execution(tenant_id, execution_state, updated_at DESC)
  WHERE execution_state IN ('UNKNOWN','FAILED','DISCREPANCY');
-- ② 按状态筛 + 按登记时刻倒序（「全部退款」页签的默认序）。
CREATE INDEX refund_execution_by_state
  ON refund_execution(tenant_id, execution_state, created_at DESC);
-- ③ 按订单反查（9 订单退款状态回写的读取侧；下游 9 未交付，但本模块自己的详情要查）。
CREATE INDEX refund_execution_by_order
  ON refund_execution(tenant_id, order_id, created_at DESC);
-- ④ 按售后单反查（11 售后退款视图）。
CREATE INDEX refund_execution_by_case
  ON refund_execution(tenant_id, case_id, created_at DESC)
  WHERE case_id IS NOT NULL;
-- ⑤ 按币种 + 金额区间筛选（**闭区间**，口径由响应原样报出）。
CREATE INDEX refund_execution_by_amount
  ON refund_execution(tenant_id, currency, refund_amount);
-- ⑥ 平台同步待办（平台侧退款还没同步的单）。
CREATE INDEX refund_execution_by_platform_sync
  ON refund_execution(tenant_id, platform_sync_state, updated_at DESC)
  WHERE platform_sync_state IN ('PENDING','DISCREPANCY');
-- ⑦ 派发流水按执行反查（详情页的「派发历史」，走索引拿第一行拿 attempt_no）。
CREATE INDEX refund_attempt_by_execution
  ON refund_attempt(tenant_id, execution_id, attempt_no DESC);
-- ⑧ 查证记录按执行反查（详情页的「查证历史」；也服务重试时「最近一次查证结论」的取值）。
CREATE INDEX refund_verification_by_execution
  ON refund_verification(tenant_id, execution_id, verified_at DESC);
-- ⑨ 回报记录按执行反查（「外部说了什么」的时间线）。
CREATE INDEX refund_report_by_execution
  ON refund_execution_report(tenant_id, execution_id, received_at DESC);
-- ⑩ **未裁定的差异清单**（蓝图输出句点名的清单，默认视图就是它）。
CREATE INDEX refund_discrepancy_open
  ON refund_discrepancy(tenant_id, detected_at DESC)
  WHERE resolution_state <> 'RESOLVED';
-- ⑪ 差异按执行反查（详情页展开）。
CREATE INDEX refund_discrepancy_by_execution
  ON refund_discrepancy(tenant_id, execution_id, detected_at DESC);
-- ⑫ 按差异类型筛（「金额不一致的有多少」是财务最常问的一句）。
CREATE INDEX refund_discrepancy_by_kind
  ON refund_discrepancy(tenant_id, discrepancy_kind, detected_at DESC);
-- ⑬ 事件流按执行反查（状态历史）。
CREATE INDEX refund_status_event_by_execution
  ON refund_status_event(tenant_id, execution_id, occurred_at, id);

-- ===========================================================================
-- 十二、多租户：RLS + 授权（与 V156 同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块**尤其**不能省：退款状态含买家可退金额、外部交易号与失败原因，
--   漏掉租户谓词就是**跨租户资损泄露** —— A 家能看到 B 家「哪些买家正在退钱、卡在哪一步」。
--   FORCE 的原因：迁移/owner 角色在别的表上靠 RLS 豁免跑迁移，本服务统一 FORCE，
--   保证连 owner 连接也过策略（V156 / V150 同一结论）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'refund_execution','refund_attempt','refund_verification',
    'refund_execution_report','refund_discrepancy','refund_status_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  refund_execution,refund_attempt,refund_verification,
  refund_execution_report,refund_discrepancy,refund_status_event TO trade_app;
-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查），**不授 UPDATE** ——
-- 运行角色不该能把计数器往回拨（拨回去就会与已有退款号撞主键）。
-- ⚠️ 漏了 USAGE 的话，登记端点会以 BadSqlGrammarException → 409 挂掉，
--   而错误文案说的是「数据与既有记录冲突」，与根因毫无关系（V156 同一坑）。
GRANT USAGE,SELECT ON SEQUENCE refund_execution_no_seq TO trade_app;

-- ---- 追加式证据表：授权在前、收回在后 ----
-- 派发流水、查证记录、外部回报、状态事件流四张表追加式：
-- 改掉或删掉其中一行，等于抹掉「这笔钱被派了两次」的证据（V156 同一纪律）。
REVOKE UPDATE,DELETE ON refund_attempt FROM trade_app;
REVOKE UPDATE,DELETE ON refund_verification FROM trade_app;
REVOKE UPDATE,DELETE ON refund_execution_report FROM trade_app;
REVOKE UPDATE,DELETE ON refund_status_event FROM trade_app;

-- ---- 差异清单：检测事实不可改，只有裁定三列可动 ----
-- ⚠️ **UPDATE 必须做成列级白名单，不能只 REVOKE 列**：
--   PostgreSQL 里表级 UPDATE 蕴含该表**每一列**的 UPDATE 权限，
--   在表级 GRANT 之后写 `REVOKE UPDATE(system_amount)` 是**空操作** ——
--   has_column_privilege 仍返回 true，等于「以为收紧了其实没收紧」
--   （V156 第九节被测试逼出来的真实缺陷，同一条坑不再踩第二次）。
-- 先收回表级 UPDATE（顺带把检测事实真的关掉），再逐列授回。
REVOKE UPDATE ON refund_discrepancy FROM trade_app;
-- 只授回裁定相关的三列：系统改不了「检测到的事实」，只能改「怎么处理」。
GRANT UPDATE(resolution_state,resolution_note,resolved_at,resolved_by) ON refund_discrepancy TO trade_app;

-- ---- 退款执行主表：状态流转相关列 ----
-- 刻意**不授回** idempotency_key、refund_no、refund_amount、currency、order_id、case_id：
-- 让运行角色改得了幂等键 = 让「同一笔退款」可以摇身变成两笔，正是本模块要挡的资损；
-- 改得了金额 = 让「已对账一致的退款」事后被改成另一个数（V99 对金额列用了同样的收紧）。
-- initiated_at 在名单里是因为它是**状态机的派生量**：NOT_INITIATED → IN_FLIGHT 的那一刻必须落时刻
-- （由 guard_refund_execution_transition + refund_execution_initiated_matches_state 钉死），
-- 它不是自由输入的字段。改坏它顶多让时效显示失真，不会造成重复退款 ——
-- 而幂等键 / 金额能改则会，两者不可同日而语。
REVOKE UPDATE ON refund_execution FROM trade_app;
GRANT UPDATE(execution_state,failure_class,failure_code,failure_reason,
             external_transaction_ref,platform_sync_state,platform_refund_no,
             initiated_at,last_verified_at,resolved_at,updated_at)
  ON refund_execution TO trade_app;

-- 本迁移**不 ALTER 任何既有表**：sales_order / sales_order_line /
-- sales_order_party_snapshot / order_address_snapshot / aftersales_case 全部只读。
-- 本模块对它们的全部依赖都是 SELECT 与服务层的存在性校验，跨车道 DDL 回归面 = 0。
