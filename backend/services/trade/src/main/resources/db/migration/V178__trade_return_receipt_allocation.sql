-- 11.2.2.2 实收分配（卷 11 售后与退货 / L3 11.2.2 退货实收的第二张单据）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：把实收退货分配到对应的退货授权行与原订单行，处理实收多于授权（按实际退回）、
--         少收、超期退回等情况。
--   输入：实收记录；授权行可收量；分配操作。
--   输出：分配记录 + 超收/少收说明；订单行已退数量更新。
--   关系：上游 = 11.2.2.1；下游 = 9 订单（已退数量）、11.2.3.3。**按收货事件去重分配**，
--         同一批退货不重复记已收数量。
--
-- 归属：**trade 服务**（与 V172 退货入库同一条退货侧单据链）。
--
-- 本迁移**只新建五张表与四个触发器、零 ALTER 零 DROP 既有表**：
--   * return_receipt / return_receipt_line（V172，11.2.2.1 **已交付**）**只读**：
--     收货事件与实收数量的真值来源；跨表一致性由触发器在**写入时刻**真查一次钉死
--     （刻意不建 FK —— 加 FK 会让本迁移对别的车道的表产生 DDL 依赖，
--     而同批另有三条车道也在往 trade 落迁移）。
--   * rma_authorization / rma_authorization_line（V161，11.2.1.1）**只读**：
--     授权量与**寄回时限 return_by_at** —— 超期退回的判定锚点。
--   * aftersales_case_line（V157）与 sales_order_line（V99）**只读**：
--     「原订单行」与单价真值（不在本表复制一份）。
--
-- ===========================================================================
-- 一、硬规则①「按收货事件去重分配，同一批退货不重复记已收数量」钉在哪
-- ===========================================================================
--
-- 这一条是**资金相关数据**（它写已退数量 = 可退余额的事实来源），
-- 重复累加一次就是实打实的资损，所以**不靠服务层的 if 判断，钉在三层**：
--
--   ① **幂等键 + 唯一约束**：`return_receipt_allocation.idempotency_key`
--      恒为 `'RECEIPT:' || receipt_id`（由 DB 触发器与 CHECK 共同保证形状），
--      `UNIQUE(tenant_id, idempotency_key)`。
--      ⇒ **绕过服务层直连 SQL 也插不进第二条**：DB 直接报唯一冲突。
--   ② 触发器 guard_return_allocation_line 在 INSERT 时刻**再真查一次**
--      「这个收货事件是不是已经有分配」，报出一条**能读懂**的错误
--      （带已存在的分配单号），而不是把一个 500 的唯一冲突抛给运营。
--   ③ 订单行已退数量由 **DB 触发器** trg_return_allocation_line_qty 在
--      状态推到 ALLOCATED 的**那一刻**累加，且 WHEN 条件钉死
--      `NEW.allocation_status = 'ALLOCATED' AND OLD.allocation_status IS DISTINCT FROM 'ALLOCATED'`。
--      ⇒ 重复提交同一个收货事件**根本不会走到累加那一行**：
--      即使有人绕过 ① ② 硬插了一张重复分配单，它的初始状态就是 ALLOCATED，
--      累加触发器只在**状态变化**时执行，INSERT 时那条 WHEN 为假。
--
-- ⚠️ 这里刻意用「**状态变化触发累加**」而不是「INSERT 即累加」：
--   INSERT 即累加意味着任何一次重放插入都会把已退数量再加一次，
--   而状态变化触发让「累加」这件事在任何路径下都只能发生一次。
--
-- ===========================================================================
-- 二、硬规则②「超收按实际退回、少收同理、不得静默截断」钉在哪
-- ===========================================================================
--
-- 上游 V172 的额度闸门是**登记时刻、按行**判的（trg_guard_return_receipt_line
-- 拦住「实收登记数超过授权行剩余可收量」）。本模块判的是**分配时刻、按收货事件**，
-- 两者口径**不同**，这不是重复校验，是两个不同的判定：
--
--   * V172 问的是「这一笔实收登记，现在能不能录进来」。
--   * 本模块问的是「这一批货，**分到订单行上之后**，目标授权行还欠多少 / 超了多少」。
--
-- 真实会触发的场景（都不是假想）：
--   ① **跨行分配**：一个包裹里的货可以**改分配**到同订单的另一条商品行
--      （V172 第四节明说「合并/拆分属 11.2.2.2 实收分配的职责」）。
--      源授权行额度充足**不代表**目标授权行额度充足 ——
--      目标行只批了 1 件、货来了 3 件，就是**超收 2 件**。
--   ② **少收**：授权 3 件、只回来 1 件且时限已过 → 差额 2 件是**少收**，
--      必须有一条**能读懂的说明**去解释「这 2 件去哪了」，
--      而不是让 `allocated_qty` 悄悄等于 1、授权行永远挂着 2 件的差额。
--
-- 钉法（三处，任何一处被绕过都还有另外两处）：
--   ① 表级 CHECK `allocated_qty > 0` + **没有** `allocated_qty <= 剩余可收` 的 CHECK：
--      上游闸门才是那个会在表级把超收拒掉的约束，而本模块**刻意不复制它** ——
--      本模块的职责恰恰是「按实际退回记账」，在表级再钉一道上限
--      等于把「静默截断」写进 schema。
--   ② 触发器 guard_return_allocation_line 把差额算成
--      `over_received_qty = greatest(0, 本行分配数 − 目标授权行剩余可收)`，
--      **写入 NEW.over_received_qty**（不接受调用方传入）。
--      ⇒ 超收数字**由数据库算出来**，调用方传什么都不是它。
--   ③ **超收/少收说明落成独立的 return_receipt_allocation_variance 行**，
--      追加式、运行角色 UPDATE/DELETE 已收回，带 NUMERIC 金额差。
--      ⇒ 「超收了但库里找不到说明」在数据层就不可表达。
--
-- ⚠️ 金额差一律 NUMERIC + BigDecimal（禁浮点）：`variance_amount numeric(19,4)`
--   = 单件单价 × 差额数量，服务层用 BigDecimal 算、DB 用 NUMERIC 存。
--
-- ===========================================================================
-- 三、「订单行已退数量」为什么是**新表**而不是 sales_order_line 上加一列
-- ===========================================================================
--
-- sales_order_line（V99）属于 **9 订单**那条线的写入所有权，
-- 本模块对既有表**零 ALTER**（同批另有三条车道也在往 trade 落迁移，
-- ALTER 别人正在改的表 = 结构性冲突，retry 永远不会自愈）。
--
-- ⇒ 已退数量落成**本模块拥有**的 order_line_returned_qty，
--   它是「已退数量」这个事实的**唯一落点**，写入所有者唯一（本模块的 DB 触发器 +
--   服务层），下游 9 订单按 AGENTS.md「走授权 API / event」读它。
--   ⚠️ **刻意不 ALTER sales_order_line，也不写任何影子引用列** ——
--   建一列永远为 0 的 received_qty 会让页面显示成「这行从没退过」，
--   而重复退货面前这正是唯一该有的提醒。
--
-- ===========================================================================
-- 四、状态机：一个状态列到底（显式转换规则见 guard_return_allocation_frozen）
-- ===========================================================================
--
-- ALLOCATED   已分配：分配行已落、订单行已退数量已由 DB 触发器累加
-- RECONCILED  已对账：超收 / 少收 / 超期退回三种说明都已结转（终态）
-- REVERSED    已撤销：分配被撤回，已退数量已由 DB 触发器回冲（终态）
--
-- ⚠️ RECONCILED 与 REVERSED 互斥且都不可再改（终态）：
--   已退数量是**资金相关**的累计量，改正它必须有原因 + 留痕，
--   而「正了又错回来」在这个模块里等于把资损再造一次。
-- ⚠️ **没有 PARTIAL / PENDING**：分配是一次性动作（一个收货事件一张分配单），
--   不存在「分一半」的中间态 —— 实收行的数量要么全部落到订单行上，要么整张撤销。

CREATE TABLE return_receipt_allocation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 分配单号：租户内唯一（序列发号器，末尾 GRANT USAGE）。
  allocation_no varchar(32) NOT NULL,

  -- ---- 硬规则① 的幂等键（DB 唯一约束的去重锚点）----
  -- 恒为 'RECEIPT:' || receipt_id。刻意**不**用自由文本：
  -- 自由文本的幂等键会出现「同一个收货事件用了两个不同的键」，
  -- 而那正是重复累加已退数量最常见的路径。
  idempotency_key varchar(128) NOT NULL,

  -- ---- 上游：收货事件（V172，11.2.2.1，**必填**）----
  -- 蓝图输入句「实收记录」—— 分配的对象只能是实收，没有实收就没有可分配的东西。
  receipt_id uuid NOT NULL,

  -- 上游快照 id（实时读上游表拿单号/商品名，不复制可变的展示字段）。
  rma_id uuid NOT NULL,
  case_id uuid NOT NULL,
  order_id uuid NOT NULL,

  -- ---- 超期退回的判定锚点（第三节）----
  -- 授权单的**寄回时限**快照。超期退回 = received_at > return_by_at。
  -- 存快照而不是每次实时读：分配写完、授权单可能后续被关闭，
  -- 判定「当时批的时候约定了什么时候寄回」用快照才答得出当时的事实。
  return_by_at timestamptz,
  -- 收货事件时刻（来自 return_receipt.received_at 的快照）。
  received_at timestamptz NOT NULL,
  -- 超期退回标记与超期天数：分配那一刻就算出来（不是对账时才补算），
  -- 因为「这批货到的时候就已经超期了」是一个**到场即成立**的事实。
  overdue_returned boolean NOT NULL DEFAULT false,
  days_overdue int NOT NULL DEFAULT 0 CHECK(days_overdue >= 0),

  -- ---- 状态机（第四节）----
  allocation_status varchar(16) NOT NULL CHECK(allocation_status IN (
      'ALLOCATED','RECONCILED','REVERSED'
  )),

  -- ---- 汇总（NUMERIC(19,6)，禁浮点）----
  -- ⚠️ 头部的 allocated_qty **允许为 0**，而分配行上的 allocated_qty 仍是 > 0：
  --   分配单头必须先落（它带幂等键与归属，是去重的锚点），分配行才写得进去 ——
  --   行上的外键式引用指向头。若头部也钉 > 0，第一个 INSERT 就会被 CHECK 拒掉。
  --   「空分配单不许存在」由**行**的 > 0 与服务层的 rollup（合计必须 > 0）保证。
  allocated_qty numeric(19,6) NOT NULL CHECK(allocated_qty >= 0),
  over_received_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(over_received_qty >= 0),
  short_received_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(short_received_qty >= 0),
  -- 超收/少收的**金额差**（NUMERIC(19,4)，禁浮点记账）。
  -- 刻意与数量差**分列**：数量差回答「多退/少退了几件」，
  -- 金额差回答「多退/少退了多少钱」，财务侧只认后者。
  variance_amount numeric(19,4) NOT NULL DEFAULT 0,

  allocated_by uuid NOT NULL,
  allocated_at timestamptz NOT NULL,
  reconciled_by uuid,
  reconciled_at timestamptz,
  reversed_by uuid,
  reversed_at timestamptz,
  reverse_reason text NOT NULL DEFAULT '' CHECK(length(reverse_reason) <= 1000),

  -- 对账说明：三种情形（超收 / 少收 / 超期）的结转结论，一句话讲清「差额去哪了」。
  reconcile_note text NOT NULL DEFAULT '' CHECK(length(reconcile_note) <= 2000),

  -- 乐观锁：分配 / 对账 / 撤销都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,allocation_no),
  -- ★ 硬规则① 的**唯一落点**：同一收货事件在同一租户下只能有一张分配单。
  --   绕过服务层直连 SQL 插第二条 = 唯一冲突，整个事务回滚，已退数量不可能被加两次。
  UNIQUE(tenant_id,idempotency_key),
  -- 一个收货事件一张单（与幂等键同义，但这条把「收货事件本身」也钉住，
  -- 即使有人手写一个形状不对的 idempotency_key 也绕不过去）。
  UNIQUE(tenant_id,receipt_id),

  -- 幂等键的形状：只能是 'RECEIPT:' || receipt_id 的字面文本。
  -- 自由文本键会让「同一个收货事件用了两个键」成为可能。
  CHECK(idempotency_key = 'RECEIPT:' || receipt_id::text),

  -- 已入库的分配单必须留人与时刻。
  CHECK(allocation_status = 'ALLOCATED' OR allocated_by IS NOT NULL),
  -- **撤销必须说明理由**：录错分配在退货场景里真实且常见
  -- （跨行分配选错商品行 / 隔壁包裹串了台），没有理由的撤销等于给已退数量凭空开一个洞。
  CHECK(allocation_status <> 'REVERSED'
        OR (reversed_by IS NOT NULL AND reversed_at IS NOT NULL AND length(btrim(reverse_reason)) > 0)),
  -- 对账必须留人与时刻。
  CHECK(allocation_status <> 'RECONCILED' OR (reconciled_by IS NOT NULL AND reconciled_at IS NOT NULL))
);

-- ===========================================================================
-- 二、分配行：实收行 →（授权行 + 原订单行）的一次落到账
-- ===========================================================================
CREATE TABLE return_receipt_allocation_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  allocation_id uuid NOT NULL,

  -- ---- 源：这一行货是从哪个实收行分出来的 ----
  -- ★ 硬规则① 的行级锚点：同一张实收行可以**分摊**到多条分配行
  --   （一个包裹里同一条实收行的货要落到多条订单行上），
  --   但**分摊之和不得超过该实收行的实收数**（触发器校验，见 guard_return_allocation_line）。
  receipt_line_id uuid NOT NULL,

  -- ---- 源授权行（实收登记时挂的那一行，V172 已保证归属关系）----
  source_rma_authorization_line_id uuid NOT NULL,

  -- ---- 目标：原订单行（蓝图职责句「与原订单行」）----
  -- 默认真值由 source_rma_line → case_line → order_line 实时读出；
  -- **可改指**同订单的另一条商品行（跨行分配，V172 第四节把合并/拆分划给本模块）。
  order_line_id uuid NOT NULL,
  case_line_id uuid NOT NULL,

  -- 目标授权行：跨行分配时它可能**不是**源授权行，
  -- 而「目标授权行还剩多少可收」才是判超收的依据。
  target_rma_authorization_line_id uuid NOT NULL,

  -- ---- 标的物快照（跨服务交接凭据，见第三节的同一条纪律）----
  -- ⚠️ 分配行是**凭据**不是**证据**：它要交给下游 9 订单去更新已退数量，
  --   必须在订单行被改名之后仍然能读出「这批退的是哪个 SKU」。
  --   （实收行是证据，所以不复制 SKU；这里是凭据，所以复制。）
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 分配数量：按**实际退回**记账（硬规则②）----
  allocated_qty numeric(19,6) NOT NULL CHECK(allocated_qty > 0),

  -- 分配时刻的可收量快照（判超收的**当时**口径，事后授权行会变）。
  target_authorized_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(target_authorized_qty >= 0),
  -- 已被**其它**收货事件占用的量（同一收货事件的其它分配行**不占**，
  -- 否则一张单里的两行会互相把对方判成超收）。
  target_taken_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(target_taken_qty >= 0),

  -- 超收量 = greatest(0, 本行分配数 − 目标授权行剩余可收)。
  -- ⚠️ **由 DB 触发器写入**，不接受调用方传入（硬规则② 的第二处）。
  over_received_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(over_received_qty >= 0),
  -- 少收量：分配数 < 该行**实收数** 时为差额（货收到了但没落到订单行上 = 少收/待查）。
  short_received_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(short_received_qty >= 0),
  -- 超收 / 少收的金额差（NUMERIC(19,4)，禁浮点记账）。
  variance_amount numeric(19,4) NOT NULL DEFAULT 0,

  -- 超期退回标记：这一行分到的货，到货时刻已晚于寄回时限。
  overdue_returned boolean NOT NULL DEFAULT false,

  variance_note text NOT NULL DEFAULT '' CHECK(length(variance_note) <= 1000),

  allocated_by uuid NOT NULL,
  allocated_at timestamptz NOT NULL,
  -- 序号：一条分配单内的行序（详情页按它渲染，重号会让行序不稳定）。
  line_no int NOT NULL CHECK(line_no > 0),
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张分配单内序号唯一。
  UNIQUE(allocation_id,line_no),
  -- 同一张分配单里，同一条实收行 + 同一目标订单行只出现一次
  -- （分摊给同一行两次 = 两次累加同一笔已退数量）。
  UNIQUE(allocation_id,receipt_line_id,order_line_id),

  -- 差额的数量必须与「分配数 vs 上限 / 实收数」自洽：不是随手填的数。
  CHECK(over_received_qty = 0
        OR allocated_qty > target_authorized_qty - target_taken_qty),
  -- 金额差与数量差同号：金额差为正必是超收，为负必是少收。
  CHECK(variance_amount = 0 OR (over_received_qty > 0 AND variance_amount > 0)
        OR (short_received_qty > 0 AND variance_amount < 0))
);

-- ===========================================================================
-- 三、超收 / 少收 / 超期退回说明（追加式，资金相关证据）
-- ===========================================================================
-- ⚠️ 蓝图输出句「分配记录 + **超收/少收说明**」的唯一落点。
-- 追加式 + 运行角色 UPDATE/DELETE 在第七节收回：
-- 能被悄悄改掉的差额说明在财务对账时等于没有说明。
CREATE TABLE return_receipt_allocation_variance (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  allocation_id uuid NOT NULL,
  -- 可空：超期退回是**整单级**的说明，可以不挂在某一行上。
  allocation_line_id uuid,

  variance_type varchar(24) NOT NULL CHECK(variance_type IN (
      'OVER_RECEIVED','UNDER_RECEIVED','OVERDUE_RETURNED','REVERSED'
  )),

  -- 数量差（NUMERIC(19,6)，禁浮点）
  variance_qty numeric(19,6) NOT NULL DEFAULT 0,
  -- 金额差（NUMERIC(19,4)，禁浮点记账）。
  -- ⚠️ 金额差 = 单价 × 数量差。**禁浮点**：单价与数量都是 NUMERIC，
  --   服务层用 BigDecimal 算完再落库，不在 SQL 里做浮点乘法。
  variance_amount numeric(19,4) NOT NULL DEFAULT 0,
  currency char(3) NOT NULL DEFAULT '' CHECK(currency = '' OR currency ~ '^[A-Z]{3}$'),

  -- **说明必须有正文**：一条没有正文的差额说明在纠纷与对账里都答不出「为什么有这笔钱」。
  explanation text NOT NULL CHECK(length(btrim(explanation)) > 0
                                  AND length(explanation) <= 2000),

  -- 处置结论：这条差额最终怎么处理（与下游 11.2.3.3 / 财务的对接口径）。
  -- ⚠️ 刻意**只有两个取值**且都不是「已解决」：
  --   差异需要人工核实是真的（超收多退的钱要追回还是认了），
  --   把它编成「已解决」会让财务侧以为这笔钱已经结清了。
  resolution varchar(24) NOT NULL CHECK(resolution IN (
      'PENDING_REVIEW','ACCEPTED_AS_ACTUAL'
  )),
  -- ACCEPTED_AS_ACTUAL（超收按实际退回认了）时必须写清依据。
  resolution_note text NOT NULL DEFAULT '' CHECK(length(resolution_note) <= 1000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 同一张分配单里同一种情形只记一次（对账动作按状态流转，重放不会多出一条）。
  UNIQUE(allocation_id,variance_type),
  -- 超收必须正数、金额正数；少收必须正数数量、金额为负。
  CHECK(variance_type <> 'OVER_RECEIVED' OR (variance_qty > 0 AND variance_amount > 0)),
  CHECK(variance_type <> 'UNDER_RECEIVED' OR (variance_qty > 0 AND variance_amount < 0)),
  -- 「按实际退回认了」必须写依据。
  CHECK(resolution <> 'ACCEPTED_AS_ACTUAL' OR length(btrim(resolution_note)) > 0)
);

-- ===========================================================================
-- 四、订单行已退数量（本模块的写入所有权，第三节的落点）
-- ===========================================================================
CREATE TABLE order_line_returned_qty (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 原订单行与订单：已退数量最终要被 9 订单读走更新自己的口径。
  order_line_id uuid NOT NULL,
  order_id uuid NOT NULL,

  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- 订购数量快照：已退数量必须能被拿来与它比，
  -- 「退了 5 件但只订过 3 件」是超收里最严重的一种。
  ordered_qty numeric(19,6) CHECK(ordered_qty IS NULL OR ordered_qty > 0),

  -- **已退数量**（累计，NUMERIC(19,6)，禁浮点）。
  -- ⚠️ 只能由 trg_return_allocation_line_qty 累加，
  --   服务层**没有**任何直接写这一列的代码路径。
  returned_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(returned_qty >= 0),
  -- 已分配量（= 各张分配单的 allocated_qty 之和），与 returned_qty 分离：
  -- 撤销分配时两者不同步回退，差额就是「已退但已不在任何有效分配单上」的部分，
  -- 而那正是财务要追问的那部分。
  allocated_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(allocated_qty >= 0),

  -- 最近一次变更的分配单与时刻：变更谁、什么时候，是审计的第一问。
  last_allocation_id uuid,
  last_allocated_at timestamptz,
  allocation_count int NOT NULL DEFAULT 0 CHECK(allocation_count >= 0),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个订单行一行已退数量：两行会让「这行到底退了几件」有两个答案。
  UNIQUE(tenant_id,order_line_id),
  -- 已分配量恒 ≥ 已退数量（回冲只会让已退少于已分配）。
  CHECK(allocated_qty >= returned_qty)
);

-- ===========================================================================
-- 五、分配留痕（追加式）
-- ===========================================================================
CREATE TABLE return_receipt_allocation_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  allocation_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'ALLOCATED','RECONCILED','REVERSED'
  )),
  from_status varchar(16),
  to_status varchar(16) NOT NULL,

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

-- ===========================================================================
-- 六、四个 DB 守卫
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 守卫① guard_return_allocation_line：逐行钉死硬规则①与硬规则②
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_return_allocation_line() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  -- ⚠️ 变量**刻意一个语义一个**：早期版本把「分配单状态 / 实收单状态 / 收货事件 id」
  -- 都塞进同一批变量，于是「目标行是否属于本单」那条比较变成「id = id」恒真。
  t_alloc_status varchar(16);
  t_alloc_receipt uuid;
  t_alloc_rma uuid;
  t_alloc_order uuid;
  t_receipt_status varchar(16);
  t_line_received numeric(19,6);
  t_taken numeric(19,6);
  t_target_rma uuid;
  t_target_order uuid;
  t_target_auth numeric(19,6);
  t_target_taken numeric(19,6);
BEGIN
  -- 单头状态与归属一次查齐。
  -- ⚠️ return_receipt_allocation 上**没有** receipt_status（实收单状态在 return_receipt 上），
  --   且本表的状态列叫 allocation_status —— 名字写错会在**第一条写入**时才炸，
  --   而那看起来像迁移没跑对。
  SELECT a.allocation_status, a.receipt_id, a.rma_id, a.order_id
    INTO t_alloc_status, t_alloc_receipt, t_alloc_rma, t_alloc_order
    FROM return_receipt_allocation a
   WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.allocation_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '实收分配单不存在或不属于当前租户：allocation_id=%', NEW.allocation_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 终态后不能改行：已退数量是资金相关的累计量，正了又错回来就是第二次资损。
  IF t_alloc_status <> 'ALLOCATED' THEN
    RAISE EXCEPTION '实收分配单已% ，分配行不能改：allocation_id=%',
      CASE t_alloc_status WHEN 'RECONCILED' THEN '对账' ELSE '撤销' END,
      NEW.allocation_id USING ERRCODE = 'check_violation';
  END IF;

  -- ★ 硬规则① 的第二处：同一个收货事件只能有一张分配单。
  --   DB 的 UNIQUE 约束（第一节）是第一处，这里是**第二处**：
  --   报错带可读信息，而不是把一个 500 的唯一冲突抛给运营。
  PERFORM 1
     FROM return_receipt_allocation a
    WHERE a.tenant_id = NEW.tenant_id
      AND a.receipt_id = t_alloc_receipt
      AND a.id <> NEW.allocation_id;

  IF FOUND THEN
    RAISE EXCEPTION '该收货事件已经分配过了（按收货事件去重，同一批退货不重复记已收数量）'
      USING ERRCODE = 'unique_violation';
  END IF;

  -- 只有**已入库**的实收单才谈得上分配：未入库时货还没进待检，
  -- 已退数量先走一步会让「待检分录有货、而已退数量已记」变成两份真相。
  SELECT rr.receipt_status, rl.received_qty
    INTO t_receipt_status, t_line_received
    FROM return_receipt_line rl
    JOIN return_receipt rr ON rr.tenant_id = rl.tenant_id AND rr.id = rl.receipt_id
   WHERE rl.tenant_id = NEW.tenant_id AND rl.id = NEW.receipt_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '退货实收行不存在或不属于当前租户：receipt_line_id=%', NEW.receipt_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_receipt_status <> 'POSTED' THEN
    -- ⚠️ 格式串刻意写成**一个**字面量而不是 `'...' || '...'`：
    --   plpgsql 的 RAISE 对复合表达式要求整体加括号，`'a' || 'b', arg` 会直接报
    --   「syntax error at or near "||"」，而报错位置落在函数结尾那一行，与真正的原因隔着几百行。
    RAISE EXCEPTION '只有已入库的退货实收单才能分配（当前 %）：实收还没入库，已退数量先走一步会让待检分录与已退数量变成两份真相', t_receipt_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 实收行必须属于本分配单对应的那个收货事件：
  -- 少了这一段，「A 包裹的分配单去分 B 包裹的货」在库里完全合法。
  IF NOT EXISTS (
    SELECT 1 FROM return_receipt_line rl
     WHERE rl.tenant_id = NEW.tenant_id
       AND rl.id = NEW.receipt_line_id
       AND rl.receipt_id = t_alloc_receipt
  ) THEN
    RAISE EXCEPTION '实收行不属于本分配单对应的收货事件：receipt_line_id=%', NEW.receipt_line_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 分摊不得超过该实收行的实收数（硬规则① 的行级口径）----
  -- ⚠️ 口径 = **未撤销**的其它分配行：一张单里的两行会互相把对方挡住，
  --   所以排除的是同一张分配单内的行（rl2.allocation_id <> NEW.allocation_id 之外
  --   还要排除 id <> NEW.id）；已撤销的分配行不再占用额度。
  SELECT coalesce(sum(rl2.allocated_qty), 0) INTO t_taken
    FROM return_receipt_allocation_line rl2
    JOIN return_receipt_allocation a2
      ON a2.tenant_id = rl2.tenant_id AND a2.id = rl2.allocation_id
   WHERE rl2.tenant_id = NEW.tenant_id
     AND rl2.receipt_line_id = NEW.receipt_line_id
     AND rl2.id <> NEW.id
     AND a2.allocation_status <> 'REVERSED';

  IF NEW.allocated_qty > t_line_received - t_taken THEN
    RAISE EXCEPTION '分摊数量 % 超过该实收行的实收数 %（本收货事件已分摊 %）',
      NEW.allocated_qty, t_line_received, t_taken
      USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 目标授权行必须属于本单所关联的那张退货授权单 ----
  SELECT l.rma_id, c.order_id
    INTO t_target_rma, t_target_order
    FROM rma_authorization_line l
    JOIN aftersales_case_line cl ON cl.tenant_id = l.tenant_id AND cl.id = l.case_line_id
    JOIN aftersales_case c      ON c.tenant_id = cl.tenant_id AND c.id = cl.case_id
   WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.target_rma_authorization_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '目标退货授权行不存在或不属于当前租户：rma_line_id=%',
      NEW.target_rma_authorization_line_id USING ERRCODE = 'no_data_found';
  END IF;

  IF t_target_rma <> t_alloc_rma THEN
    RAISE EXCEPTION '目标退货授权行不属于本分配单所关联的退货授权单：rma_line_id=%',
      NEW.target_rma_authorization_line_id USING ERRCODE = 'check_violation';
  END IF;

  -- 跨行分配只允许在**同一张订单**内：跨订单分配会让「这批货退给了谁」变成两笔资金事实。
  IF t_target_order <> t_alloc_order THEN
    RAISE EXCEPTION '目标订单行必须属于本分配单同一张销售订单：order_line_id=%', NEW.order_line_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- ⚠️ **NEW.order_line_id 本身也要查**：上面那条是从**授权行**推导订单的，
  --   而 order_line_id 是调用方可以显式传入的 ——
  --   不单独查一次，「同订单的授权行 + 另一张订单的订单行」这一种跨订单组合
  --   在库里完全合法，而已退数量会记到**别的订单**的商品行上（跨订单资金事实）。
  IF NOT EXISTS (
    SELECT 1 FROM sales_order_line ol
     WHERE ol.tenant_id = NEW.tenant_id AND ol.id = NEW.order_line_id
  ) THEN
    RAISE EXCEPTION '目标订单行不存在或不属于当前租户：order_line_id=%', NEW.order_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF (SELECT ol3.order_id FROM sales_order_line ol3
       WHERE ol3.tenant_id = NEW.tenant_id AND ol3.id = NEW.order_line_id) <> t_alloc_order THEN
    RAISE EXCEPTION '目标订单行必须属于本分配单同一张销售订单：order_line_id=%', NEW.order_line_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- ---- ★ 硬规则②：超收量由**数据库**算出来（不接受调用方传入）----
  SELECT coalesce(l.authorized_qty, 0) INTO t_target_auth
    FROM rma_authorization_line l
   WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.target_rma_authorization_line_id;

  -- ⚠️ 「同一收货事件已占用」**不算**占用（排除 a3.id = NEW.allocation_id）：
  --   一张分配单里的两行若互相占用，每一行都会被判成超收。
  --   口径 = 其它收货事件在该授权行上、且**未撤销**的分配量。
  SELECT coalesce(sum(rl3.allocated_qty), 0) INTO t_target_taken
    FROM return_receipt_allocation_line rl3
    JOIN return_receipt_allocation a3
      ON a3.tenant_id = rl3.tenant_id AND a3.id = rl3.allocation_id
   WHERE rl3.tenant_id = NEW.tenant_id
     AND rl3.target_rma_authorization_line_id = NEW.target_rma_authorization_line_id
     AND rl3.id <> NEW.id
     AND a3.id <> NEW.allocation_id
     AND a3.allocation_status <> 'REVERSED';

  NEW.target_authorized_qty := coalesce(t_target_auth, 0);
  NEW.target_taken_qty := coalesce(t_target_taken, 0);
  -- ★ 按实际退回记账：分配数**不被截断**，
  --   超出的部分整额进 over_received_qty，并要求写清说明。
  NEW.over_received_qty := greatest(
      NEW.allocated_qty - greatest(coalesce(t_target_auth, 0) - coalesce(t_target_taken, 0), 0), 0);
  NEW.short_received_qty := greatest(t_line_received - t_taken - NEW.allocated_qty, 0);

  -- ★ 金额差同样由**数据库**算（硬规则② 的金额侧）：
  --   差额金额 = (超收数 − 少收数) × 订单行单价。
  --   ⚠️ PG 的 NUMERIC × NUMERIC 是**任意精度十进制**运算，不是浮点 ——
  --   单价 NUMERIC(19,4) × 数量 NUMERIC(19,6) 全程精确，四位小数量化只丢该丢的。
  --   ⚠️ 数量列与金额列**分列**：前者回答「多退/少退几件」，后者回答「多退/少退多少钱」。
  NEW.variance_amount := round(
      (NEW.over_received_qty - NEW.short_received_qty) * coalesce((
          SELECT ol.unit_price FROM sales_order_line ol
           WHERE ol.tenant_id = NEW.tenant_id AND ol.id = NEW.order_line_id), 0), 4);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_allocation_line
  BEFORE INSERT OR UPDATE OF receipt_line_id, target_rma_authorization_line_id,
    allocated_qty, order_line_id ON return_receipt_allocation_line
  FOR EACH ROW EXECUTE FUNCTION guard_return_allocation_line();

-- ---------------------------------------------------------------------------
-- 守卫② guard_return_allocation_frozen：终态不可改 + 状态转换规则
-- ---------------------------------------------------------------------------
-- 显式转换规则（AGENTS.md：状态机用 enum / sealed interface 配显式转换规则表达）：
--   ALLOCATED → RECONCILED
--   ALLOCATED → REVERSED
--   RECONCILED / REVERSED 为终态，**不可再转**（含不可转回 ALLOCATED）。
-- 理由：已退数量是资金相关的累计量；「对账完又撤销」会让差额说明与已退数量互相矛盾，
-- 而这类矛盾在财务对账时几乎无法自证是哪一次操作造成的。
CREATE FUNCTION guard_return_allocation_frozen() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- ⚠️ **终态不可改任何东西**（含汇总列）：已对账 / 已撤销后连头都不能碰。
  IF OLD.allocation_status <> 'ALLOCATED' THEN
    RAISE EXCEPTION '实收分配单已% ，是终态，不能再改（已退数量是资金相关的累计量，正了又错回来就是第二次资损）',
      CASE OLD.allocation_status WHEN 'RECONCILED' THEN '对账' ELSE '撤销' END
      USING ERRCODE = 'check_violation';
  END IF;

  -- ⚠️ **状态没变时是内容更新，不是状态流转** —— 必须放行。
  --   分配行落完后要回写一次头部汇总（超收/少收量与金额差），
  --   那次 UPDATE 的状态仍然是 ALLOCATED；若不先判「状态未变」，
  --   它会被下面的转换规则当成「转换到 ALLOCATED」而报
  --   「只能从 ALLOCATED 转到 RECONCILED 或 REVERSED」——
  --   而它只是想回写汇总，状态压根没转。
  IF NEW.allocation_status IS NOT DISTINCT FROM OLD.allocation_status THEN
    RETURN NEW;
  END IF;

  -- 真正的状态转换：只允许两条路，且都只能从 ALLOCATED 出发。
  IF NEW.allocation_status NOT IN ('RECONCILED','REVERSED') THEN
    RAISE EXCEPTION '实收分配单状态只能从 ALLOCATED 转到 RECONCILED 或 REVERSED，当前 %',
      NEW.allocation_status USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_allocation_frozen
  BEFORE UPDATE ON return_receipt_allocation
  FOR EACH ROW EXECUTE FUNCTION guard_return_allocation_frozen();

-- ---------------------------------------------------------------------------
-- 守卫③（两条）：**已退数量只在这里被改**
-- ---------------------------------------------------------------------------
-- ⚠️ 这是「订单行已退数量更新」的**唯一落点**：
--   服务层**没有**任何直接写 order_line_returned_qty.returned_qty 的代码路径，
--   运行角色的 UPDATE/DELETE 也已在第八节收回。
--
-- ⚠️ 拆成**两个触发器**而不是一个，是因为分配单头的写入顺序：
--   头先落（它带幂等键与归属，是去重的锚点），**行后写**。
--   若把累加挂在「分配单头 INSERT」上，触发器跑到时行还不存在 ——
--   FOR 循环一条都扫不到，已退数量永远是 0，而报错是**空**的：
--   分配成功、已退数量不涨，下游 9 订单读到的永远是「这行从没退过」。
--   本模块开发中真实踩到过这个坑，故把理由写在这里。
--
-- ⚠️ **恰好一次**由两张表约束保证，不靠触发器时序：
--   `UNIQUE(tenant_id, idempotency_key)` + `UNIQUE(tenant_id, receipt_id)`
--   使「一个收货事件一张分配单」在 DB 层不可能重复；
--   分配行只在分配单为 ALLOCATED 时写得进去（guard_return_allocation_line），
--   而 ALLOCATED 只能转出一次 ⇒ 每条分配行恰好累加一次。

-- ③-A 生效：分配行写入那一刻累加该行数量
CREATE FUNCTION apply_return_allocation_line_qty() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  t_order_id uuid;
  t_allocated_at timestamptz;
BEGIN
  -- 分配单必须还是「已分配」状态：已对账 / 已撤销的单不允许再加行
  -- （guard_return_allocation_line 已拦过一道，这里是第二道）。
  IF NOT EXISTS (
    SELECT 1 FROM return_receipt_allocation a
     WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.allocation_id
       AND a.allocation_status = 'ALLOCATED'
  ) THEN
    RAISE EXCEPTION '只有已分配且未对账的分配单才能写入分配行：allocation_id=%', NEW.allocation_id
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT a.order_id, a.allocated_at INTO t_order_id, t_allocated_at
    FROM return_receipt_allocation a
   WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.allocation_id;

  INSERT INTO order_line_returned_qty(
      id, tenant_id, order_line_id, order_id, sku_id, sku_code, sku_name,
      ordered_qty, returned_qty, allocated_qty, last_allocation_id,
      last_allocated_at, allocation_count, created_at, updated_at)
  VALUES (
      gen_random_uuid(), NEW.tenant_id, NEW.order_line_id, t_order_id,
      NEW.sku_id, NEW.sku_code, NEW.sku_name,
      (SELECT ol.quantity FROM sales_order_line ol
        WHERE ol.tenant_id = NEW.tenant_id AND ol.id = NEW.order_line_id),
      NEW.allocated_qty, NEW.allocated_qty, NEW.allocation_id,
      t_allocated_at, 1, now(), now())
  ON CONFLICT (tenant_id, order_line_id) DO UPDATE
    SET returned_qty = order_line_returned_qty.returned_qty + EXCLUDED.returned_qty,
        allocated_qty = order_line_returned_qty.allocated_qty + EXCLUDED.allocated_qty,
        sku_code = EXCLUDED.sku_code,
        sku_name = EXCLUDED.sku_name,
        sku_id = EXCLUDED.sku_id,
        ordered_qty = EXCLUDED.ordered_qty,
        last_allocation_id = EXCLUDED.last_allocation_id,
        last_allocated_at = EXCLUDED.last_allocated_at,
        allocation_count = order_line_returned_qty.allocation_count + 1,
        version = order_line_returned_qty.version + 1,
        updated_at = now();

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_return_allocation_line_qty
  AFTER INSERT ON return_receipt_allocation_line
  FOR EACH ROW EXECUTE FUNCTION apply_return_allocation_line_qty();

-- ③-B 回冲：撤销分配那一刻减去本单全部行的数量
--
-- ⚠️ **SECURITY DEFINER 不是可选的**：本函数是撤销路径上唯一能改
--   order_line_returned_qty 的地方，而第八节刻意把该表的 UPDATE/DELETE
--   从运行角色收回 —— SECURITY INVOKER 下撤销会被权限拒绝直接打回，
--   「撤销分配」这个动作在生产里根本执行不了
--   （测试里能过是因为测试用超级用户跑迁移，那是测试环境的假象）。
--   search_path 显式钉死，避免被调用方通过临时 schema 劫持函数解析。
--   ⚠️ FORCE RLS 对表所有者同样生效 ⇒ 触发器里的读写**依旧受 app.current_tenant 约束**，
--   「用 definer 绕开权限」不会变成「绕开租户隔离」。
CREATE FUNCTION reverse_return_allocation_order_qty() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  rec_line record;
BEGIN
  IF NEW.allocation_status <> 'REVERSED'
     OR OLD.allocation_status IS DISTINCT FROM 'ALLOCATED' THEN
    -- 对账（ALLOCATED → RECONCILED）**不改**已退数量：它是累计口径，
    -- 超收/少收只是结转说明。把差额算进已退数量会让「已退」这个数
    -- 同时包含「真实收到的」和「还在核对的」，两者口径必须分开。
    RETURN NEW;
  END IF;

  FOR rec_line IN
    SELECT l.order_line_id, l.allocated_qty
      FROM return_receipt_allocation_line l
     WHERE l.tenant_id = NEW.tenant_id AND l.allocation_id = NEW.id
  LOOP
    -- 回冲：已退数量减去本行的分配量，**已分配量保留** ——
    -- 两者之差就是「已退但已不在任何有效分配单上」的部分，
    -- 而那正是财务要追问的部分（表级 CHECK allocated_qty >= returned_qty 也依赖这一点）。
    UPDATE order_line_returned_qty
       SET returned_qty = returned_qty - rec_line.allocated_qty,
           last_allocation_id = NEW.id,
           last_allocated_at = NEW.reversed_at,
           allocation_count = allocation_count + 1,
           version = version + 1,
           updated_at = now()
     WHERE tenant_id = NEW.tenant_id
       AND order_line_id = rec_line.order_line_id
       AND returned_qty >= rec_line.allocated_qty;

    IF NOT FOUND THEN
      RAISE EXCEPTION '撤销分配失败：订单行 % 的已退数量不足以回冲 %（可能已被其它分配单占用）',
        rec_line.order_line_id, rec_line.allocated_qty
        USING ERRCODE = 'check_violation';
    END IF;
  END LOOP;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_return_allocation_reverse_qty
  AFTER UPDATE OF allocation_status ON return_receipt_allocation
  FOR EACH ROW EXECUTE FUNCTION reverse_return_allocation_order_qty();

-- ---------------------------------------------------------------------------
-- 守卫④ trg_return_allocation_event：留痕追加（只写「真变了」的状态变更）
-- ---------------------------------------------------------------------------
CREATE FUNCTION return_allocation_append_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.allocation_status = OLD.allocation_status THEN
    RETURN NEW;
  END IF;

  INSERT INTO return_receipt_allocation_event(
      id, tenant_id, allocation_id, event_type, from_status, to_status,
      reason_note, occurred_by, occurred_at)
  VALUES (
      gen_random_uuid(),
      NEW.tenant_id,
      NEW.id,
      CASE
        WHEN TG_OP = 'INSERT'               THEN 'ALLOCATED'
        WHEN NEW.allocation_status = 'REVERSED'  THEN 'REVERSED'
        WHEN NEW.allocation_status = 'RECONCILED' THEN 'RECONCILED'
        ELSE 'ALLOCATED'
      END,
      CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.allocation_status END,
      NEW.allocation_status,
      -- ⚠️ **每类事件用它自己那一刻**，不是 coalesce 出一列通用值：
      --   对账事件的时刻被记成分配时刻，排序会退化成按随机 UUID 排。
      CASE
        WHEN TG_OP = 'INSERT'                    THEN '按收货事件分配（已退数量已更新）'
        WHEN NEW.allocation_status = 'REVERSED'  THEN coalesce(nullif(btrim(NEW.reverse_reason), ''), '人工撤销分配')
        WHEN NEW.allocation_status = 'RECONCILED' THEN coalesce(nullif(btrim(NEW.reconcile_note), ''), '超收/少收/超期已结转')
        ELSE '分配要素变更'
      END,
      CASE
        WHEN TG_OP = 'INSERT'                    THEN NEW.allocated_by
        WHEN NEW.allocation_status = 'REVERSED'  THEN NEW.reversed_by
        WHEN NEW.allocation_status = 'RECONCILED' THEN NEW.reconciled_by
        ELSE NEW.allocated_by
      END,
      CASE
        WHEN TG_OP = 'INSERT'                    THEN NEW.allocated_at
        WHEN NEW.allocation_status = 'REVERSED'  THEN NEW.reversed_at
        WHEN NEW.allocation_status = 'RECONCILED' THEN NEW.reconciled_at
        ELSE NEW.updated_at
      END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_return_allocation_event
  AFTER INSERT OR UPDATE OF allocation_status ON return_receipt_allocation
  FOR EACH ROW EXECUTE FUNCTION return_allocation_append_event();

-- ===========================================================================
-- 七、索引与发号器
-- ===========================================================================
-- ① 详情页：按分配单取行。
CREATE INDEX return_allocation_line_by_allocation
  ON return_receipt_allocation_line(tenant_id, allocation_id);
-- ② ★ 硬规则② 的额度查询热点：服务层与触发器都按「目标授权行」算剩余可收。
CREATE INDEX return_allocation_line_by_target_rma_line
  ON return_receipt_allocation_line(tenant_id, target_rma_authorization_line_id);
-- ③ 按收货事件反查（客服手上只有一个收货单号时的入口）。
--    ⚠️ 这个入口在多租户下尤其不能漏租户谓词，否则就成了一台跨租户搜索引擎。
CREATE INDEX return_allocation_by_receipt
  ON return_receipt_allocation(tenant_id, receipt_id);
-- ④ 按订单 / 售后单 / 授权单反查：下游 9 订单按订单行找已退数量。
CREATE INDEX return_allocation_by_order
  ON return_receipt_allocation(tenant_id, order_id);
CREATE INDEX return_allocation_by_case
  ON return_receipt_allocation(tenant_id, case_id);
-- ⑤ **超期退回扫描**：未对账且已超期的单子（运营每天要盯的那一批）。
--    部分索引只看未终态的单，已对账的不再命中。
CREATE INDEX return_allocation_overdue_scan
  ON return_receipt_allocation(tenant_id, received_at, return_by_at)
  WHERE allocation_status <> 'REVERSED' AND overdue_returned;
-- ⑥ 已退数量页 / 9 订单取数。
CREATE INDEX order_line_returned_qty_by_order
  ON order_line_returned_qty(tenant_id, order_id);
-- ⑦ 差额说明按发生时刻回放（追加式）。
CREATE INDEX return_allocation_variance_by_allocation
  ON return_receipt_allocation_variance(tenant_id, allocation_id, occurred_at);
-- ⑧ 留痕按发生时刻回放。
CREATE INDEX return_allocation_event_by_allocation
  ON return_receipt_allocation_event(tenant_id, allocation_id, occurred_at);

CREATE SEQUENCE return_allocation_no_seq;
-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查）。
GRANT USAGE,SELECT ON SEQUENCE return_allocation_no_seq TO trade_app;

-- ===========================================================================
-- 八、多租户：RLS + 授权（与 V161 第九节 / V172 同一套写法）
-- ===========================================================================
-- 五张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：分配行带着**逐条订单行与 SKU 快照**，
--   差额说明带着**金额差**（财务敏感），而详情 / 对账 / 撤销端点都是**按 id 直达**的 URL ——
--   猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'return_receipt_allocation','return_receipt_allocation_line',
    'return_receipt_allocation_variance','order_line_returned_qty',
    'return_receipt_allocation_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  return_receipt_allocation, return_receipt_allocation_line,
  return_receipt_allocation_variance, order_line_returned_qty,
  return_receipt_allocation_event TO trade_app;

-- 授权在前、收回在后（与 V161 / V165 / V172 同序）。
-- ⚠️ **已退数量是账**：运行角色刻意**不给** UPDATE / DELETE。
--   它只能由 trg_return_allocation_line_qty / trg_return_allocation_reverse_qty 改 ——
--   改一条已退数量而不改分配单，等于凭空给一行造出一笔退款额度。
REVOKE UPDATE,DELETE ON order_line_returned_qty FROM trade_app;
-- 差额说明**追加式**：能被悄悄改掉的差额说明在财务对账时等于没有说明。
REVOKE UPDATE,DELETE ON return_receipt_allocation_variance FROM trade_app;
-- 留痕**追加式**：分配轨迹是资损追责的证据。
REVOKE UPDATE,DELETE ON return_receipt_allocation_event FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：return_receipt 簇（V172）、rma_authorization 簇（V161）、
-- aftersales_case 簇（V156/V157）与 sales_order_line（V99）全部只读。
-- 本模块对它们的全部依赖都是 SELECT / JOIN 与「写入时刻查一次」，跨车道 DDL 回归面 = 0。
