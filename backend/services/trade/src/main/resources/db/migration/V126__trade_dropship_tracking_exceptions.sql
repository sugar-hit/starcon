-- ===========================================================================
-- 9.6.1.3 代发履约跟踪与异常
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md:255）：
--   职责：跟踪代发订单的供应商发货与妥投状态，同步供应商运单号回传平台，
--         处理代发异常（延迟、丢件、供应商跑单），并把「我方」与「供应商」
--         两段物流在订单上分开呈现。
--   输入：供应商发货/物流状态（接口或录入）；平台回传结果。
--   输出：代发履约状态；异常清单 + 处置（催供应商/改自营发货/退款）。
--   关系：上游 = 9.6.1.2；下游 = 2.5.1.1 回传、11 售后、10.3 轨迹。
--         **代发模式下「及时发货」是供应商责任但平台考核算我方，因此异常必须
--         可发现可切换，否则直接掉绩效。**
--
-- ---------------------------------------------------------------------------
-- 一、本模块唯一不可让步的一条：两段物流必须分列，不能合成一个运单号
-- ---------------------------------------------------------------------------
-- 蓝图把「并把『我方』与『供应商』两段物流在订单上分开呈现」写进**职责**，
-- 而不是「关系」里的补充说明。这句话防的是一个非常具体的实现偷懒：
-- 建一个 tracking_no 列，供应商段填进去、我方段再覆盖掉。
--
-- 在纯代发模式下这看起来完全能用 —— 供应商直发到买家，压根没有我方这一段。
-- 但蓝图紧接着要求「异常必须可**切换**」，而「改自营发货」这个处置动作
-- 一旦执行，同一张订单就同时存在两段：
--   * 供应商段：已发出（或已废弃）—— 供应商确实发过这票货，留下的是**凭据**；
--   * 我方段：交运 → 在途 → 妥投 —— 从改自营那一刻起由我方承运。
-- 合成一列时，第二段必然覆盖第一段，而第一段是「供应商发过货」这个事实本身。
-- 覆盖掉之后，这票货到底谁发过、什么时候发的，就再也答不上了 ——
-- 而供应商跑单纠纷（9.6.1.3 异常三类之一）问的恰好就是这个。
--
-- 因此本迁移从**表结构**上把两段拆成两组互不相干的列，各有自己的承运商、
-- 运单号、状态枚举与时间戳：
--
--   供应商段（supplier_leg_*）      我方段（own_leg_*）
--   --------------------------     -----------------------
--   supplier_leg_status             own_leg_status
--   supplier_carrier_code           own_carrier_code
--   supplier_tracking_no            own_tracking_no
--   supplier_shipped_at             own_handed_over_at
--   supplier_last_event_at          own_last_event_at
--   supplier_delivered_at           own_delivered_at
--   supplier_promised_ship_at       （我方段没有「承诺时限」：
--                                    及时发货考核只对供应商段成立）
--
-- 两段状态**互不推导**（不是可以随手打开的开关）：
--   supplier_leg_status ∈ NOT_SHIPMENT | SHIPPED | IN_TRANSIT | DELIVERED
--                        | LOST | ABANDONED
--   own_leg_status      ∈ NOT_HANDED_OVER | HANDED_OVER | IN_TRANSIT
--                        | DELIVERED | RETURNED
-- 一张纯代发单的正常轨迹是 supplier: NOT_SHIPMENT→SHIPPED→IN_TRANSIT→DELIVERED，
-- own 段全程停在 NOT_HANDED_OVER。执行「改自营发货」后 active_leg 翻到 OWN，
-- supplier 段落进 ABANDONED（**保留**为凭据，不抹掉），own 段才开始推进。
--
-- ⚠️ 这里刻意**不去**改 V122 的 fulfillment_dropship_order.fulfillment_path：
-- V122 的 CHECK 把它钉死为 'SUPPLIER_DIRECT'，因为「代发单」这个东西的定义
-- 就是「这一票是向供应商下的」。「改自营发货」改的是**这一票接下来怎么走**，
-- 不是「它当初是不是代发单」。改 V122 那张表等于跨车道改别人的不变量。
--
-- ---------------------------------------------------------------------------
-- 二、三类异常必须由规则检出，不能手工标记
-- ---------------------------------------------------------------------------
-- 蓝图列举「延迟、丢件、供应商跑单」，三条各自对应一条**可计算的判据**。
-- 手工勾一个「延迟」标签是最容易的做法，也是蓝图那句「异常必须可发现」
-- 要防的 —— 标签只能被勾上，勾不上就没人报，而延迟恰恰是**没人主动报**的
-- 那一类（供应商不会告诉你它晚了，它只会什么都不说）。
--
-- 本迁移把三条判据落成 SQL（见服务层 evaluateTrackingExceptions，
-- 与本迁移第 4 节的阈值列配套），而不是一个下拉框：
--
--   1) DELAYED_SHIPMENT    超时未发货
--      active_leg='SUPPLIER' AND supplier_leg_status='NOT_SHIPMENT'
--      AND supplier_promised_ship_at < now() - delay_tolerance_hours
--      —— 有承诺时限、时限已过、货还没发。
--      严重度：逾期超过 critical_delay_hours → CRITICAL，否则 WARNING。
--
--   2) PARCEL_LOST         轨迹长时间无更新（丢件的前兆信号）
--      active_leg='SUPPLIER' AND supplier_leg_status IN ('SHIPPED','IN_TRANSIT')
--      AND btrim(supplier_tracking_no) <> ''
--      AND coalesce(supplier_last_event_at, supplier_shipped_at)
--          < now() - stall_threshold_hours
--      —— 已经拿到运单号却在途，轨迹却 N 小时没动过。丢件在物流侧最终会
--      变成「查无此件」，但等到那时候平台时效已经超了，所以判据是**停滞**。
--      严重度：静默超过 critical_stall_hours → CRITICAL，否则 WARNING。
--
--   3) SUPPLIER_RUNNING_AWAY  供应商报发货后无运单（跑单）
--      active_leg='SUPPLIER' AND supplier_leg_status IN ('SHIPPED','IN_TRANSIT','DELIVERED')
--      AND btrim(supplier_tracking_no) = ''
--      —— 供应商声称发了，却给不出可查的运单号。没有运单号就：无法回传平台
--      （拿什么报？）、无法向买家举证、无法对账。这是最严重的一类，恒 CRITICAL。
--
-- 三条**互不合并**：延迟要催货或切换履约，丢件要查件或补发，跑单要退款，
-- 处置动作完全不同，合并成一条「有异常」就没法派工（与 V122 四类告警同理由）。
--
-- 阈值不写死在代码里：落在 fulfillment_dropship_tracking_policy（租户级单例），
-- 检出时由视图 fulfillment_dropship_tracking_policy_effective 给出唯一真相。
-- **每条异常都把当时用的阈值与算式写进自己的行**（rule_threshold_hours /
-- rule_evidence），事后改策略不会让历史异常看起来是按新规则算出来的
-- —— 与 V118/V122 的 snapshot 纪律一致。
--
-- 不重复检出：同一 (tracking, kind) 在非 DISMISSED 状态下唯一（部分唯一索引），
-- 反复跑 evaluate 不会把同一条延迟刷成几十条。误报要**显式**置 DISMISSED 才解禁。
--
-- ---------------------------------------------------------------------------
-- 三、下游（2.5.1.1 回传 / 11 售后）尚未交付：落任务表，不造假
-- ---------------------------------------------------------------------------
-- 蓝图要两件下游都还没交付的事：
--   * 「同步供应商运单号回传平台」→ 下游 2.5.1.1（channel 服务）未交付；
--   * 处置动作「退款」→ 下游 11 售后未交付。
--
-- 按 AGENTS.md 模块边界纪律：**一条 SQL 都不碰对方的表，不建跨服务 FK**。
-- 本模块的做法是落一张**真任务表** fulfillment_dropship_task（有状态机、
-- 有载荷、有幂等键、有重试计数），在 trade 侧成为那一跳的**权威待办**：
--
--   PLATFORM_CALLBACK  → CHANNEL   （2.5.1.1 回传供应商运单号）
--   SUPPLIER_URGE      → SUPPLIER  （催供应商：外部动作，不是跨服务调用）
--   SELF_FULFILL_SWITCH → WAREHOUSE（改自营发货 → 交给自有仓发货）
--   REFUND_REQUEST     → AFTER_SALE（退款待办 → 11 售后）
--
-- 下游交付后，它消费这张表（或本服务调它），把 task_status 从 OPEN 推进到
-- SENT/ACKNOWLEDGED。**任务行在下游接手之前不会被当成已办** —— 界面上
-- 回传显示「待回传（平台）」而不是一个假的「已回传」，那才是「不许糊过去」。
--
-- ---------------------------------------------------------------------------
-- 四、处置必须留痕：三类动作各有状态机，且互相独立
-- ---------------------------------------------------------------------------
-- 蓝图输出「异常清单 + 处置（催供应商/改自营发货/退款）」。「+ 处置」意味着
-- 处置本身是一个有过程的事实，不是异常上的一个布尔勾。三条处置各自：
--
--   URGE_SUPPLIER     催供应商：落 disposition + task(SUPPLIER_URGE)，
--                      tracking.urge_count 加一（催几次要看得见）
--   SWITCH_TO_SELF    改自营发货：**active_leg 翻到 OWN**，供应商段落 ABANDONED
--                      （保留凭据），落 disposition + task(SELF_FULFILL_SWITCH)，
--                      并把该跟踪上未决的供应商段异常置为 DISPOSED
--                      —— 这就是蓝图那句「异常必须可发现可**切换**」
--   REQUEST_REFUND    发起退款：落 disposition（含退款金额与币种）+ task(REFUND_REQUEST)
--
-- disposition 状态机（DB 触发器钉死，绕开服务层写 SQL 也改不动）：
--   PENDING → SENT → ACKNOWLEDGED → COMPLETED
--   PENDING → CANCELLED（尚未发出才可撤销；已发出再「撤销」等于把已发生的
--   催单/退款从历史上抹掉，正是 V122 告警表要防的同一件事）
--
-- append-only 的凭据：
--   * fulfillment_dropship_tracking_event —— 只追加，REVOKE UPDATE/DELETE；
--   * 异常行的判定证据（exception_kind / rule_code / rule_threshold_hours /
--     rule_evidence / detected_at）不可改写，只能改处置字段（照抄 V122 的
--     guard_dropship_alert_evidence 同一手法，但分表授权）。
--
-- ---------------------------------------------------------------------------
-- 五、多租户与跨车道
-- ---------------------------------------------------------------------------
-- 六张表全部 tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT/REVOKE，
-- 策略写法与 V118 / V122 逐字一致（同一个 isolation policy 名、同一个 GUC）。
--
-- 本迁移**零 ALTER**：不碰 V99 / V118 / V122 的任何一张表，只新增两张
-- **同库**外键（→ fulfillment_dropship_order，指向本服务自己的表）。
-- 跨车道 DDL 回归面 = 0。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 四个编号发号器（全部由 DB 发，并发下不撞号）
-- ---------------------------------------------------------------------------
--   DTR-yyyyMMdd-000001  跟踪号
--   DEX-yyyyMMdd-000001  异常号
--   DSP-yyyyMMdd-000001  处置号
--   DTK-yyyyMMdd-000001  任务号
CREATE SEQUENCE dropship_tracking_no_seq START 1;
CREATE SEQUENCE dropship_tracking_exception_no_seq START 1;
CREATE SEQUENCE dropship_disposition_no_seq START 1;
CREATE SEQUENCE dropship_task_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. fulfillment_dropship_tracking_policy · 检出策略（租户级单例）
-- ---------------------------------------------------------------------------
-- 阈值是租户经营口径，不是代码常量：不同店铺的平台考核强度不一样。
-- singleton 列 + UNIQUE(tenant_id, singleton) 保证每租户**恰好一行**
-- （PG 里 UNIQUE 视 NULL 为不同，所以用恒真列而不是裸 UNIQUE(tenant_id)）。
CREATE TABLE fulfillment_dropship_tracking_policy (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  singleton boolean NOT NULL DEFAULT true CHECK(singleton),

  -- 延迟判定：承诺时限过了 delay_tolerance_hours 才算延迟（宽限，如约好次晨发）
  delay_tolerance_hours int NOT NULL DEFAULT 0 CHECK(delay_tolerance_hours >= 0),
  -- 延迟严重度分界：逾期超过它 → CRITICAL（要立刻处置），否则 WARNING
  critical_delay_hours int NOT NULL DEFAULT 24 CHECK(critical_delay_hours > 0),
  -- 停滞判定：轨迹 N 小时无更新算丢件前兆
  stall_threshold_hours int NOT NULL DEFAULT 72 CHECK(stall_threshold_hours > 0),
  -- 停滞严重度分界：静默超过它 → CRITICAL
  critical_stall_hours int NOT NULL DEFAULT 120 CHECK(critical_stall_hours > 0),
  -- 供应商报发货是否必须给出运单号；置 false 时规则 3 停用（用于「供应商线下发货、
  -- 本就不给单号」的店铺）。置 true 时**无单号即跑单**，不接受口头解释。
  require_tracking_no_on_shipped boolean NOT NULL DEFAULT true,

  note text NOT NULL DEFAULT '',
  updated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,singleton),
  -- 分界线必须宽于判定线，否则「刚过线就 CRITICAL」，告警会失去分级意义
  CHECK(critical_delay_hours > delay_tolerance_hours),
  CHECK(critical_stall_hours > stall_threshold_hours)
);

-- ---------------------------------------------------------------------------
-- 2. fulfillment_dropship_tracking_policy_effective · 有效阈值视图（唯一真相）
-- ---------------------------------------------------------------------------
-- 未登记策略的租户也能检出：LEFT JOIN LATERAL + coalesce 给出**迁移内的默认值**，
-- 因此永远返回恰好一行，检出语句不需要写「有没有策略行」的分支。
--
-- security_invoker = true（PG15+）：视图以调用者权限查底层表，RLS 照常生效，
-- 换个租户查就拿不到别人的策略行（视图自己不加 RLS，靠底层表兜底）。
CREATE VIEW fulfillment_dropship_tracking_policy_effective
  WITH (security_invoker = true) AS
SELECT coalesce(p.delay_tolerance_hours, 0)               AS delay_tolerance_hours,
       coalesce(p.critical_delay_hours, 24)              AS critical_delay_hours,
       coalesce(p.stall_threshold_hours, 72)             AS stall_threshold_hours,
       coalesce(p.critical_stall_hours, 120)             AS critical_stall_hours,
       coalesce(p.require_tracking_no_on_shipped, true)  AS require_tracking_no_on_shipped,
       (p.id IS NOT NULL)                                AS policy_registered
FROM (SELECT 1) d
LEFT JOIN LATERAL (
  SELECT id, delay_tolerance_hours, critical_delay_hours, stall_threshold_hours,
         critical_stall_hours, require_tracking_no_on_shipped
  FROM fulfillment_dropship_tracking_policy
  ORDER BY created_at LIMIT 1
) p ON true;

-- ---------------------------------------------------------------------------
-- 3. fulfillment_dropship_tracking · 两段物流跟踪头（蓝图核心输出）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_tracking (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  tracking_no varchar(32) NOT NULL,

  -- === 上游：9.6.1.2 代发单（同库，可建 FK）===
  order_id uuid NOT NULL,
  order_no varchar(32) NOT NULL,
  sales_order_id uuid NOT NULL,
  sales_order_no varchar(64) NOT NULL,
  supplier_code varchar(64) NOT NULL,

  -- === 生效段：纯代发 = SUPPLIER；「改自营发货」后翻成 OWN ===
  -- DB 触发器强制两段的推进顺序：翻到 OWN 的那一刻供应商段必须落 ABANDONED，
  -- 否则就会出现「两段同时在途」，而两段同时在途在货权上无法解释。
  active_leg varchar(8) NOT NULL DEFAULT 'SUPPLIER'
    CHECK(active_leg IN ('SUPPLIER','OWN')),

  -- ======================================================================
  -- 第一段：供应商 → 买家（代发段）
  -- ======================================================================
  supplier_leg_status varchar(16) NOT NULL DEFAULT 'NOT_SHIPMENT' CHECK(supplier_leg_status IN
    ('NOT_SHIPMENT','SHIPPED','IN_TRANSIT','DELIVERED','LOST','ABANDONED')),
  supplier_carrier_code varchar(64) NOT NULL DEFAULT '',
  supplier_tracking_no varchar(128) NOT NULL DEFAULT '',
  supplier_shipped_at timestamptz,
  -- 轨迹最后更新时刻：**由观测方给**（接口或人工录入），不许用写入时刻冒充 ——
  -- 用「现在」冒充会让停滞检测永远算不出静默时长，丢件就永远检不出来。
  supplier_last_event_at timestamptz,
  supplier_delivered_at timestamptz,
  -- 承诺发货时限：延迟判定的基准。NOT NULL 是刻意的 ——
  -- 没有承诺时限就无从「迟」，而这恰恰是平台及时发货考核的依据。
  supplier_promised_ship_at timestamptz NOT NULL,
  supplier_exception_note varchar(400) NOT NULL DEFAULT '',

  -- ======================================================================
  -- 第二段：我方 → 买家（自营段）
  -- ======================================================================
  own_leg_status varchar(16) NOT NULL DEFAULT 'NOT_HANDED_OVER' CHECK(own_leg_status IN
    ('NOT_HANDED_OVER','HANDED_OVER','IN_TRANSIT','DELIVERED','RETURNED')),
  own_carrier_code varchar(64) NOT NULL DEFAULT '',
  own_tracking_no varchar(128) NOT NULL DEFAULT '',
  own_handed_over_at timestamptz,
  own_last_event_at timestamptz,
  own_delivered_at timestamptz,
  own_delivery_note varchar(400) NOT NULL DEFAULT '',

  -- === 「改自营发货」的切换留痕（蓝图：异常必须可切换）===
  switched_to_own_at timestamptz,
  switched_to_own_by_membership_id uuid,
  switch_reason varchar(400) NOT NULL DEFAULT '',

  -- === 催单次数：催了几次要看得见，否则「已催」和「没催过」在界面上一样 ===
  urge_count int NOT NULL DEFAULT 0 CHECK(urge_count >= 0),
  last_urged_at timestamptz,

  -- === 供应商运单号回传平台的状态（2.5.1.1 未交付，见文件头第三节）===
  -- 刻意不给「已回传」这个假结论：真实回传由 channel 侧推进，这里只登记
  -- 我方发起与结果。SENT/ACKNOWLEDGED 只能由 task 被下游接手后写回。
  callback_status varchar(16) NOT NULL DEFAULT 'NOT_REQUIRED' CHECK(callback_status IN
    ('NOT_REQUIRED','PENDING','QUEUED','SENT','ACKNOWLEDGED','FAILED')),
  callback_sent_at timestamptz,
  callback_attempts int NOT NULL DEFAULT 0 CHECK(callback_attempts >= 0),
  callback_last_error varchar(400) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 1,
  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,tracking_no),
  -- 一张代发单一条跟踪：同一票货两个跟踪头就会有两份「谁在途」的答案
  UNIQUE(tenant_id,order_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES fulfillment_dropship_order(tenant_id,id)
    ON DELETE CASCADE,

  CHECK(btrim(tracking_no)<>'' AND btrim(order_no)<>'' AND btrim(supplier_code)<>''),

  -- --- 两段各自的完整性：状态到了，时刻不能没有 ---
  -- 供应商段已发货必须带发货时刻（否则「已发货」没有起点，停滞时长无从算）
  CHECK(supplier_leg_status NOT IN ('SHIPPED','IN_TRANSIT','DELIVERED','LOST')
        OR supplier_shipped_at IS NOT NULL),
  -- 已妥投必须带妥投时刻（平台时效考核要的是妥投时刻，不是状态字）
  CHECK(supplier_leg_status <> 'DELIVERED' OR supplier_delivered_at IS NOT NULL),
  -- 我方段已交运必须带交运时刻（我方交运 = 我方段履约责任开始的那一刻）
  CHECK(own_leg_status NOT IN ('HANDED_OVER','IN_TRANSIT','DELIVERED','RETURNED')
        OR own_handed_over_at IS NOT NULL),
  CHECK(own_leg_status <> 'DELIVERED' OR own_delivered_at IS NOT NULL),

  -- --- 两段各带各的承运商，不共享 ---
  -- 有运单号却没承运商，回传给平台时平台侧无法路由（2.5.1.1 会直接拒收）。
  -- 刻意**命名**：匿名 CHECK 的拒绝消息是一整串表达式文本，人读不出是哪条规矩；
  -- 命名之后 DB 直接报出约束名，接口与测试都能指到具体那一条。
  CONSTRAINT dropship_tracking_supplier_carrier_required CHECK(
    supplier_tracking_no = '' OR supplier_carrier_code <> ''
    OR supplier_leg_status = 'ABANDONED'),
  CONSTRAINT dropship_tracking_own_carrier_required CHECK(
    own_tracking_no = '' OR own_carrier_code <> ''),

  -- --- 切换留痕的四件套：有切换就必须齐全，且时刻不早于发货承诺 ---
  CHECK(
    (switched_to_own_at IS NULL
       AND switched_to_own_by_membership_id IS NULL
       AND btrim(switch_reason) = '')
    OR
    (switched_to_own_at IS NOT NULL
       AND switched_to_own_by_membership_id IS NOT NULL
       AND btrim(switch_reason) <> '')
  ),
  -- active_leg='OWN' 必须有切换凭据：否则「怎么切的、谁切的、为什么」全都无从追
  CHECK(active_leg <> 'OWN'
        OR (switched_to_own_at IS NOT NULL AND btrim(switch_reason) <> '')),
  -- 催过单才允许带催单时刻与次数
  CHECK((urge_count = 0 AND last_urged_at IS NULL)
        OR (urge_count > 0 AND last_urged_at IS NOT NULL)),
  -- 已回传必须有回传时刻；未处于已回传状态就不该留回传时刻
  CHECK(callback_status <> 'SENT' OR callback_sent_at IS NOT NULL),
  CHECK((callback_status IN ('SENT','ACKNOWLEDGED')) = (callback_sent_at IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- 4. fulfillment_dropship_tracking_exception · 异常清单（三类互不合并）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_tracking_exception (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 异常编号（DB 发号，审计时可直接引用）
  exception_no varchar(32) NOT NULL,
  tracking_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_no varchar(32) NOT NULL,
  sales_order_no varchar(64) NOT NULL,
  supplier_code varchar(64) NOT NULL,

  -- 三类互不合并：处置动作完全不同（催货或切换 / 查件或补发 / 退款）
  exception_kind varchar(24) NOT NULL CHECK(exception_kind IN
    ('DELAYED_SHIPMENT','PARCEL_LOST','SUPPLIER_RUNNING_AWAY')),
  -- INFO | WARNING | CRITICAL：CRITICAL 必须立刻处置（逾期过深 / 静默过久 / 无单号）
  severity varchar(8) NOT NULL CHECK(severity IN ('INFO','WARNING','CRITICAL')),

  -- === 判定证据：这条异常**为什么**成立 ===
  -- rule_code 具体哪条判据；rule_threshold_hours 当时用的阈值；
  -- rule_evidence 带真实数值的算式（如「逾期 31.5 小时」）。
  -- 事后改策略**不会**让历史异常看起来是按新规则算的。
  rule_code varchar(48) NOT NULL,
  rule_threshold_hours int NOT NULL CHECK(rule_threshold_hours >= 0),
  rule_evidence varchar(400) NOT NULL,

  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN
    ('OPEN','ACKNOWLEDGED','DISPOSED','DISMISSED')),
  acknowledged_at timestamptz,
  acknowledged_by_membership_id uuid,
  resolution_note text NOT NULL DEFAULT '',
  resolved_at timestamptz,
  resolved_by_membership_id uuid,
  detected_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,exception_no),
  FOREIGN KEY(tenant_id,tracking_id) REFERENCES fulfillment_dropship_tracking(tenant_id,id)
    ON DELETE CASCADE,
  -- order_id 是冗余的「按代发单聚合」捷径；它必须与 tracking 指向同一张单，
  -- 否则会出现「异常挂在 A 单上、单号却写着 B 单」这种对不上的行
  FOREIGN KEY(tenant_id,order_id) REFERENCES fulfillment_dropship_order(tenant_id,id)
    ON DELETE CASCADE,
  CHECK(btrim(rule_code)<>'' AND btrim(rule_evidence)<>''),
  -- 已确认/已处置必须带主体与说明，否则「谁处理的、怎么处理的」无从追
  CHECK(status NOT IN ('ACKNOWLEDGED','DISPOSED')
        OR (acknowledged_at IS NOT NULL
            AND acknowledged_by_membership_id IS NOT NULL
            AND coalesce(btrim(resolution_note),'') <> '')),
  -- 处置完成必须带处置人与处置时刻
  CHECK(status <> 'DISPOSED'
        OR (resolved_at IS NOT NULL AND resolved_by_membership_id IS NOT NULL)),
  -- 误报（DISMISSED）也要写明为什么不是异常，否则下次同类会被一并放过
  CHECK(status <> 'DISMISSED' OR coalesce(btrim(resolution_note),'') <> '')
);

-- 同一 (tracking, kind) 在未结案状态下唯一 → 反复跑 evaluate 不会刷屏。
-- 刻意用**部分唯一索引**而不是 UNIQUE 约束：DISMISSED（误报）必须能再次检出，
-- 一个被证伪的延迟在下一个周期重新逾期时是一条**新事实**，不是同一条。
CREATE UNIQUE INDEX dropship_tracking_exception_active_uq
  ON fulfillment_dropship_tracking_exception(tenant_id,tracking_id,exception_kind)
  WHERE status <> 'DISMISSED';

-- ---------------------------------------------------------------------------
-- 5. fulfillment_dropship_disposition · 处置记录（三类动作 + 状态机）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_dropship_disposition (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  disposition_no varchar(32) NOT NULL,
  exception_id uuid NOT NULL,
  tracking_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_no varchar(32) NOT NULL,

  -- 三类动作（蓝图输出原文：催供应商 / 改自营发货 / 退款）
  action_kind varchar(24) NOT NULL CHECK(action_kind IN
    ('URGE_SUPPLIER','SWITCH_TO_SELF','REQUEST_REFUND')),
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN
    ('PENDING','SENT','ACKNOWLEDGED','COMPLETED','CANCELLED')),

  -- 退款要带金额与币种快照：11 售后未交付，金额此刻由运营按代发单实际成本填；
  -- 币种从代发单快照下来，避免下游按错币种入账（金额一律 numeric，不用浮点）
  refund_amount numeric(19,4) CHECK(refund_amount IS NULL OR refund_amount > 0),
  refund_currency_code varchar(3) CHECK(refund_currency_code IS NULL
    OR refund_currency_code ~ '^[A-Z]{3}$'),

  target_system varchar(24) NOT NULL CHECK(target_system IN
    ('SUPPLIER','WAREHOUSE','AFTER_SALE','CHANNEL')),
  note text NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  sent_at timestamptz,
  acknowledged_at timestamptz,
  completed_at timestamptz,
  cancelled_at timestamptz,
  outcome varchar(400) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,disposition_no),
  FOREIGN KEY(tenant_id,exception_id)
    REFERENCES fulfillment_dropship_tracking_exception(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,tracking_id)
    REFERENCES fulfillment_dropship_tracking(tenant_id,id) ON DELETE CASCADE,

  CHECK(btrim(note)<>''),
  -- 退款金额两件套同进同出：只给金额没币种，下游入账时就得猜
  CHECK((refund_amount IS NULL) = (refund_currency_code IS NULL)),
  -- 只有退款必须带金额；催单/切换带一个金额没有意义，反而会被下游误读
  CHECK(action_kind <> 'REQUEST_REFUND'
        OR (refund_amount IS NOT NULL AND refund_currency_code IS NOT NULL)),
  -- 催单只对供应商，改自营只对我方仓，退款只给售后：目标系统写错等于把退款
  -- 催给了供应商。三条动作的目标系统钉死，不给自由文本。
  CHECK((action_kind = 'URGE_SUPPLIER'  AND target_system = 'SUPPLIER')
     OR (action_kind = 'SWITCH_TO_SELF' AND target_system = 'WAREHOUSE')
     OR (action_kind = 'REQUEST_REFUND' AND target_system = 'AFTER_SALE')),
  CHECK(status <> 'SENT'       OR sent_at IS NOT NULL),
  CHECK(status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL),
  CHECK(status <> 'COMPLETED'  OR (completed_at IS NOT NULL AND btrim(outcome) <> '')),
  CHECK(status <> 'CANCELLED'  OR cancelled_at IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 6. fulfillment_dropship_task · 待回传 / 待处置任务（跨服务那一跳的落点）
-- ---------------------------------------------------------------------------
-- 见文件头第三节：2.5.1.1（channel 回传）与 11 售后（退款）尚未交付，
-- 本表是 trade 侧的**权威待办**，不是假实现。任务在下游接手前不会被当成已办。
CREATE TABLE fulfillment_dropship_task (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  task_no varchar(32) NOT NULL,
  task_kind varchar(24) NOT NULL CHECK(task_kind IN
    ('PLATFORM_CALLBACK','SUPPLIER_URGE','SELF_FULFILL_SWITCH','REFUND_REQUEST')),
  task_status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(task_status IN
    ('OPEN','CLAIMED','SENT','ACKNOWLEDGED','FAILED','CANCELLED')),

  tracking_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_no varchar(32) NOT NULL,
  sales_order_no varchar(64) NOT NULL,
  -- 指向源单据：处置类任务指 disposition，检出触发的回传类任务为空
  disposition_id uuid,
  exception_id uuid,

  -- 下游是谁：2.5.1.1 → CHANNEL，11 售后 → AFTER_SALE，我方仓 → WAREHOUSE，
  -- 供应商 → SUPPLIER（外部动作，不是跨服务调用，但同样要有待办与回执）
  target_system varchar(24) NOT NULL CHECK(target_system IN
    ('SUPPLIER','WAREHOUSE','AFTER_SALE','CHANNEL')),
  -- 载荷：回传平台需要运单号 + 承运商；退款需要金额 + 币种。
  -- 只放**跨服务动作真正需要的字段**，不复制整张代发单过去。
  payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(payload) = 'object'),
  -- 幂等键：下游重复消费同一条任务不得重复回传 / 重复退款
  idempotency_key varchar(128) NOT NULL,

  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  last_attempt_at timestamptz,
  last_error varchar(400) NOT NULL DEFAULT '',
  claimed_by_membership_id uuid,
  claimed_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,task_no),
  -- 幂等键全局唯一：同一个「这张单的这个回传」只能存在一条任务，
  -- 重跑检出/重发处置都不会造出第二条要下游执行的活
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,tracking_id)
    REFERENCES fulfillment_dropship_tracking(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,disposition_id)
    REFERENCES fulfillment_dropship_disposition(tenant_id,id) ON DELETE CASCADE,

  CHECK(btrim(idempotency_key)<>''),
  -- 目标系统与任务种类的对应关系钉死：把回传任务发给售后仓库是最典型的串台
  CHECK((task_kind = 'PLATFORM_CALLBACK'   AND target_system = 'CHANNEL')
     OR (task_kind = 'SUPPLIER_URGE'       AND target_system = 'SUPPLIER')
     OR (task_kind = 'SELF_FULFILL_SWITCH'  AND target_system = 'WAREHOUSE')
     OR (task_kind = 'REFUND_REQUEST'      AND target_system = 'AFTER_SALE')),
  CHECK(task_status <> 'CLAIMED' OR (claimed_at IS NOT NULL AND claimed_by_membership_id IS NOT NULL)),
  CHECK(task_status NOT IN ('ACKNOWLEDGED','FAILED') OR last_attempt_at IS NOT NULL),
  CHECK(task_status <> 'ACKNOWLEDGED' OR finished_at IS NOT NULL),
  -- 回传任务必须真的带着运单号：没有运单号的回传任务发了也只是空转
  CHECK(task_kind <> 'PLATFORM_CALLBACK'
        OR (payload->>'supplierTrackingNo') IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 7. fulfillment_dropship_tracking_event · 事件流（append-only）
-- ---------------------------------------------------------------------------
-- 两段状态都记进同一行事件：切换那一瞬两段同时变化，只记一段就看不出
-- 「供应商段去哪了、我方段从哪来」。
CREATE TABLE fulfillment_dropship_tracking_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  tracking_id uuid NOT NULL,
  event_kind varchar(32) NOT NULL,
  from_active_leg varchar(8) NOT NULL DEFAULT '',
  to_active_leg varchar(8) NOT NULL DEFAULT '',
  from_supplier_leg_status varchar(16) NOT NULL DEFAULT '',
  to_supplier_leg_status varchar(16) NOT NULL DEFAULT '',
  from_own_leg_status varchar(16) NOT NULL DEFAULT '',
  to_own_leg_status varchar(16) NOT NULL DEFAULT '',
  detail varchar(400) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,tracking_id)
    REFERENCES fulfillment_dropship_tracking(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 8. 索引（按本模块真实的查询形状）
-- ===========================================================================
-- 跟踪工作台主排序 + 按生效段筛选（运营最常问「哪些单已经改成我方自己发了」）
CREATE INDEX dropship_tracking_active_leg_idx
  ON fulfillment_dropship_tracking(tenant_id,active_leg,updated_at desc);
CREATE INDEX dropship_tracking_order_idx
  ON fulfillment_dropship_tracking(tenant_id,order_id);
-- 状态筛选：供应商段与「我方段」各自的工作台视图
CREATE INDEX dropship_tracking_supplier_status_idx
  ON fulfillment_dropship_tracking(tenant_id,supplier_leg_status,updated_at desc);
CREATE INDEX dropship_tracking_own_status_idx
  ON fulfillment_dropship_tracking(tenant_id,own_leg_status,updated_at desc);
-- 承诺时限排序：延迟检出与「本周该发的货」都按它排
CREATE INDEX dropship_tracking_promised_idx
  ON fulfillment_dropship_tracking(tenant_id,supplier_promised_ship_at);
-- 异常工作台：未结案异常按严重度与时间排（异常是本模块的硬输出，必须好找）
CREATE INDEX dropship_tracking_exception_open_idx
  ON fulfillment_dropship_tracking_exception(tenant_id,status,severity,detected_at desc);
CREATE INDEX dropship_tracking_exception_tracking_idx
  ON fulfillment_dropship_tracking_exception(tenant_id,tracking_id,detected_at desc);
CREATE INDEX dropship_tracking_exception_order_idx
  ON fulfillment_dropship_tracking_exception(tenant_id,order_id,detected_at desc);
-- 处置：一张异常下先后有几条处置（催多次）
CREATE INDEX dropship_disposition_exception_idx
  ON fulfillment_dropship_disposition(tenant_id,exception_id,created_at desc);
-- 任务队列：下游按目标系统 + 状态拉待办
CREATE INDEX dropship_task_queue_idx
  ON fulfillment_dropship_task(tenant_id,target_system,task_status,created_at);
CREATE INDEX dropship_task_tracking_idx
  ON fulfillment_dropship_task(tenant_id,tracking_id,created_at desc);
CREATE INDEX dropship_tracking_event_tracking_idx
  ON fulfillment_dropship_tracking_event(tenant_id,tracking_id,occurred_at desc);

-- ===========================================================================
-- 9. 不变量触发器
-- ===========================================================================
-- (a) 供应商段状态机：NOT_SHIPMENT → SHIPPED → IN_TRANSIT → DELIVERED，
--     任意在途/未发态可落 LOST（丢件）与 ABANDONED（改自营发货后放弃该段）。
--     DELIVERED 是终态；LOST 之后可以补发回到 IN_TRANSIT（查件后找到了）。
--
--     ⚠️ NOT_SHIPMENT 可以**直接**跳到 IN_TRANSIT / DELIVERED：跟踪是对供应商
--     回执与轨迹的**采样**，它不保证看得到每一个中间态（供应商可能只在批量
--     回传里给出「已到加州」这一条）。若强制一态一态地推，真实同步会被
--     状态机顶回来 —— 那等于逼运营去编一条没发生过的 SHIPPED。
--     但 CHECK 仍要求这几态必须带 supplier_shipped_at：跳过的中间态不能
--     连「什么时候发的」一起丢掉。
CREATE OR REPLACE FUNCTION guard_dropship_supplier_leg_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.supplier_leg_status IS NOT DISTINCT FROM OLD.supplier_leg_status THEN RETURN NEW; END IF;
  allowed := CASE OLD.supplier_leg_status
    WHEN 'NOT_SHIPMENT' THEN NEW.supplier_leg_status IN
      ('SHIPPED','IN_TRANSIT','DELIVERED','LOST','ABANDONED')
    WHEN 'SHIPPED'       THEN NEW.supplier_leg_status IN ('IN_TRANSIT','DELIVERED','LOST','ABANDONED')
    WHEN 'IN_TRANSIT'    THEN NEW.supplier_leg_status IN ('DELIVERED','LOST','ABANDONED')
    WHEN 'LOST'          THEN NEW.supplier_leg_status IN ('IN_TRANSIT','DELIVERED','ABANDONED')
    WHEN 'DELIVERED'     THEN false   -- 已妥投是终态；再出错走 11 售后，不在跟踪里翻案
    WHEN 'ABANDONED'     THEN false   -- 放弃的段保持废弃，不许复活
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '供应商段 % cannot move % -> %', NEW.tracking_no,
      OLD.supplier_leg_status, NEW.supplier_leg_status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_tracking_supplier_leg
  BEFORE UPDATE ON fulfillment_dropship_tracking
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_supplier_leg_state();

-- (b) 我方段状态机：NOT_HANDED_OVER → HANDED_OVER → IN_TRANSIT → DELIVERED，
--     任意未妥投态可落 RETURNED（退回）。DELIVERED / RETURNED 是终态。
CREATE OR REPLACE FUNCTION guard_dropship_own_leg_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.own_leg_status IS NOT DISTINCT FROM OLD.own_leg_status THEN RETURN NEW; END IF;
  allowed := CASE OLD.own_leg_status
    WHEN 'NOT_HANDED_OVER' THEN NEW.own_leg_status IN ('HANDED_OVER')
    WHEN 'HANDED_OVER'      THEN NEW.own_leg_status IN ('IN_TRANSIT','DELIVERED','RETURNED')
    WHEN 'IN_TRANSIT'       THEN NEW.own_leg_status IN ('DELIVERED','RETURNED')
    WHEN 'DELIVERED'        THEN false
    WHEN 'RETURNED'         THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '我方段 % cannot move % -> %', NEW.tracking_no,
      OLD.own_leg_status, NEW.own_leg_status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_tracking_own_leg
  BEFORE UPDATE ON fulfillment_dropship_tracking
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_own_leg_state();

-- (c) **两段不能同时在途**。这正是「两段物流必须分开呈现」要防的后果：
--     一票货只可能在一段的手上；两段同时 SHIPPED/IN_TRANSIT 在物理上无法解释，
--     而最常见的产生方式就是「改自营发货」时忘了把供应商段落下。
--     因此「切到 OWN」必须与「供应商段落 ABANDONED」在同一行里同时完成，
--     绕开服务层分两次写也拦得住。
CREATE OR REPLACE FUNCTION guard_dropship_legs_exclusive() RETURNS trigger AS $$
BEGIN
  IF NEW.active_leg IS NOT DISTINCT FROM OLD.active_leg
     AND NEW.supplier_leg_status IS NOT DISTINCT FROM OLD.supplier_leg_status THEN
    RETURN NEW;
  END IF;
  IF NEW.active_leg = 'OWN'
     AND NEW.supplier_leg_status IN ('SHIPPED','IN_TRANSIT','DELIVERED') THEN
    RAISE EXCEPTION
      '跟踪 % 切到自营段时，供应商段必须落 ABANDONED（保留为凭据，不能删也不能留在途）：'
      '一票货只可能在一段的手上，两段同时在途在物理上无法解释',
      NEW.tracking_no USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.active_leg = 'OWN' AND NEW.supplier_leg_status <> 'ABANDONED' THEN
    RAISE EXCEPTION '跟踪 % 的生效段是我方段时，供应商段必须落 ABANDONED', NEW.tracking_no
      USING ERRCODE = 'check_violation';
  END IF;
  -- 反向：供应商段还在途/已妥投时，生效段不能是 OWN（同上）
  IF NEW.active_leg = 'SUPPLIER'
     AND NEW.own_leg_status IN ('HANDED_OVER','IN_TRANSIT','DELIVERED') THEN
    RAISE EXCEPTION
      '跟踪 % 的我方段已在途/已妥投，生效段不能回到供应商段', NEW.tracking_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_tracking_legs_exclusive
  BEFORE UPDATE ON fulfillment_dropship_tracking
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_legs_exclusive();

-- (d) 处置状态机：PENDING → SENT → ACKNOWLEDGED → COMPLETED，
--     PENDING → CANCELLED（**只在还没发出时**）。
--     「已发出的催单/退款」不是可以回滚的东西 —— 撤销它等于把一次真实发生过的
--     催货或退款承诺从历史里抹掉，与 V122 告警表要防的是同一件事。
CREATE OR REPLACE FUNCTION guard_dropship_disposition_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
  allowed := CASE OLD.status
    WHEN 'PENDING'     THEN NEW.status IN ('SENT','CANCELLED')
    WHEN 'SENT'        THEN NEW.status IN ('ACKNOWLEDGED','COMPLETED')
    WHEN 'ACKNOWLEDGED' THEN NEW.status IN ('COMPLETED')
    WHEN 'COMPLETED'   THEN false
    WHEN 'CANCELLED'   THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '处置 % cannot move % -> %', OLD.disposition_no, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  -- 退回金额与币种是发起时定下的事实，状态流转不许顺手改（改了就变成另一笔退款）
  IF NEW.action_kind IS DISTINCT FROM OLD.action_kind
     OR NEW.refund_amount IS DISTINCT FROM OLD.refund_amount
     OR NEW.refund_currency_code IS DISTINCT FROM OLD.refund_currency_code THEN
    RAISE EXCEPTION '处置 % 的动作与退款金额不可改写', OLD.disposition_no
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_disposition_state
  BEFORE UPDATE ON fulfillment_dropship_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_disposition_state();

-- (e) 任务状态机：OPEN → CLAIMED → SENT → ACKNOWLEDGED，
--     OPEN/CLAIMED → CANCELLED，OPEN/CLAIMED/SENT → FAILED（可重试回 OPEN）。
--     FAILED → OPEN 是「重试」，不是翻案：重试次数累加在 attempt_count 上。
CREATE OR REPLACE FUNCTION guard_dropship_task_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.task_status IS NOT DISTINCT FROM OLD.task_status THEN RETURN NEW; END IF;
  allowed := CASE OLD.task_status
    WHEN 'OPEN'    THEN NEW.task_status IN ('CLAIMED','CANCELLED','FAILED')
    -- CLAIMED 可直接到 ACKNOWLEDGED：领取与回执常常是同一个往返里完成的，
    -- 强制中间再走一格 SENT 只会逼下游拆成一个没有实际意义的两步
    WHEN 'CLAIMED' THEN NEW.task_status IN ('SENT','ACKNOWLEDGED','CANCELLED','FAILED')
    WHEN 'SENT'    THEN NEW.task_status IN ('ACKNOWLEDGED','FAILED')
    WHEN 'FAILED'  THEN NEW.task_status IN ('OPEN','CANCELLED')
    WHEN 'ACKNOWLEDGED' THEN false
    WHEN 'CANCELLED'    THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '任务 % cannot move % -> %', OLD.task_no, OLD.task_status, NEW.task_status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_task_state
  BEFORE UPDATE ON fulfillment_dropship_task
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_task_state();

-- (f) 异常的判定证据不可改写，只能改处置字段（与 V122 的
--     guard_dropship_alert_evidence 同一手法，但本表独立分表授权）。
--     异常是「当时按那条规则、那个阈值、那些数**确实**检出过」这件事的凭据；
--     改掉它等于让历史异常看起来是按事后改过的规则算的。
CREATE OR REPLACE FUNCTION guard_dropship_tracking_exception_evidence() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '代发履约异常是已发生异常的凭据，不可删除（误报请置 DISMISSED）'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.exception_kind IS DISTINCT FROM OLD.exception_kind
     OR NEW.rule_code IS DISTINCT FROM OLD.rule_code
     OR NEW.rule_threshold_hours IS DISTINCT FROM OLD.rule_threshold_hours
     OR NEW.rule_evidence IS DISTINCT FROM OLD.rule_evidence
     OR NEW.detected_at IS DISTINCT FROM OLD.detected_at THEN
    RAISE EXCEPTION '代发履约异常的判定证据不可改写（只能处置）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_tracking_exception_evidence
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_tracking_exception
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_tracking_exception_evidence();

-- 处置记录**不**封死更新：状态流转（PENDING→SENT→…）本来就要改它。
-- 不可改写的是「发起了什么动作、退多少钱」（上面 (d) 的触发器逐字段挡住）。

-- (g) 事件流只追加：连运行角色都不给改删，兜底在权限层而不只在触发器里。
CREATE OR REPLACE FUNCTION guard_dropship_tracking_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '代发履约事件流只追加，不可改写或删除' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER dropship_tracking_event_append_only
  BEFORE UPDATE OR DELETE ON fulfillment_dropship_tracking_event
  FOR EACH ROW EXECUTE FUNCTION guard_dropship_tracking_event_append_only();

-- ===========================================================================
-- 10. 多租户与授权（与 V118 / V122 同一套写法）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'fulfillment_dropship_tracking_policy',
    'fulfillment_dropship_tracking',
    'fulfillment_dropship_tracking_exception',
    'fulfillment_dropship_disposition',
    'fulfillment_dropship_task',
    'fulfillment_dropship_tracking_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_dropship_tracking_policy,
  fulfillment_dropship_tracking,
  fulfillment_dropship_tracking_exception,
  fulfillment_dropship_disposition,
  fulfillment_dropship_task,
  fulfillment_dropship_tracking_event TO trade_app;
-- 事件流是凭据：连运行角色都不给改删（授权在前、收回在后，反过来写会被上面全量覆盖）
REVOKE UPDATE,DELETE ON fulfillment_dropship_tracking_event FROM trade_app;
-- 异常表**只收回 DELETE**、保留 UPDATE：处置（确认/处置/判误报）必须能改
-- status / acknowledged_* / resolution_note / resolved_*，否则「已处置」无处落地。
-- 不可改写的是判定证据那一部分，由 (f) 的触发器逐字段挡住。
REVOKE DELETE ON fulfillment_dropship_tracking_exception FROM trade_app;
-- 有效阈值视图：可读（检出与界面都要），不给写（阈值只能通过策略表改）
GRANT SELECT ON fulfillment_dropship_tracking_policy_effective TO trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V118 / V122 同口径）
REVOKE ALL ON
  fulfillment_dropship_tracking_policy,
  fulfillment_dropship_tracking,
  fulfillment_dropship_tracking_exception,
  fulfillment_dropship_disposition,
  fulfillment_dropship_task,
  fulfillment_dropship_tracking_event FROM starcon_app;
-- 序列必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列
GRANT USAGE, SELECT ON SEQUENCE
  dropship_tracking_no_seq,
  dropship_tracking_exception_no_seq,
  dropship_disposition_no_seq,
  dropship_task_no_seq TO trade_app;

-- ===========================================================================
-- 11. 跨车道回归自查
-- ===========================================================================
-- 本迁移**只 CREATE 表/序列/视图/索引/触发器**，对既有表的写入面为：
--   * **零 ALTER**：不碰 V99 的 sales_order 族、V118 的七张 fulfillment_dropship_*、
--     V122 的五张表，连一行 ALTER 都没有 —— V122 把
--     fulfillment_dropship_order.fulfillment_path 钉死为 'SUPPLIER_DIRECT'，
--     本模块的「改自营发货」改的是**跟踪上的生效段**，不是去改那张表。
--   * 两个**同库**外键（→ fulfillment_dropship_order、→ 自身的 tracking/disposition），
--     指向**本服务自己的表**：这是同库引用，不是 AGENTS.md 禁止的跨服务 FK。
--   * 加 FK 不修改被引用表的任何列、约束或触发器，因此既有模块的写入面不变。
-- 六张新表与 9.6.1.1 / 9.6.1.2 的表并列而不混用：
-- 配置说「这个 SKU 走不走代发」，代发单说「这一票怎么下的」，
-- 本模块说「这一票现在到哪了、卡在哪、怎么处置」。
-- 被本迁移约束到的既有模块 = 无，跨车道 DDL 回归面 = 0。