-- 9.3.1.2 合单。
--
-- 蓝图 09-order-and-fulfillment.md 9.3.1.2 原文：
--   职责：把同一买家的多笔订单合并成一个包裹发货，降低运费与包材成本；
--         同时识别跨店铺同买家订单（需分别回传平台）。
--   输入：候选订单集合（同一买家 + 同一收货地址）；合单规则。
--   输出：合并履约请求 + 各原订单的关联记录。
--   关系：上游 = 9.1.1.1；下游 = 8.3.3.2 打包、9.4.2.1 回传。
--         **合单后每个平台订单仍要单独回传物流号，平台不认跨订单的合并。**
--
-- 归属：**trade 服务**（蓝图 09 卷 L2 表「9.3 履约编排 → 主责服务 trade」）。
-- 本迁移**只新建表、零 ALTER 既有表** —— sales_order 簇（V99）与
-- order_fulfillment_line_progress / order_platform_writeback_task（V119）
-- 是 9.1.2.2 与 9.1.1.3 车道的写入所有者，本模块只**读**它们（跨车道 DDL 回归面 = 0）。
--
-- ===========================================================================
-- 一、上游实查结论：候选订单集合真的存在，且「同一买家」只能从地址快照取
-- ===========================================================================
-- 蓝图输入句是「候选订单集合（同一买家 + 同一收货地址）」。实查 V99：
--   sales_order **没有买家身份列**（只有 order_no / order_source / external_order_no /
--   currency / payment_method / status / 金额），也没有店铺列。
--   唯一同时承载「买家身份」与「收货地址」的地方是 order_address_snapshot
--   （address_role='SHIPPING' 的那一行）：recipient_name / recipient_phone /
--   recipient_email 是身份，country_code…address_line2 是地址。
-- 所以本模块的两个指纹都由**收货地址快照**在 SQL 里现算，不引入任何外部买家主数据：
--   * buyer_fingerprint   = md5(身份三件套)   → 蓝图说的「同一买家」
--   * address_fingerprint = md5(完整地址)     → 蓝图说的「同一收货地址」
-- **判「同一地址」用快照而不是实时地址**是硬口径：V99 第 8.3 节已用
-- guard_sales_order_snapshot_frozen 把 SUBMITTED 后的地址快照冻住，所以快照
-- 就是下单那一刻的真实投递地址；去查可变主数据会得到「现在寄到哪」，
-- 而合单要判的是「这两单本来是不是寄到同一个地方」。
--
-- ⚠️ 指纹里的分隔符用不可见字符 E'\x1f'（unit separator）而不是 '|'：
-- 收件人字段本身由运营自由录入，'|' 会让「Smith|John」与「Smith」「John」两组
-- 字段拼出同一个字符串 —— 那就是一次**跨买家的错误合单**，比不合单严重得多。
--
-- ===========================================================================
-- 二、跨店铺同买家：平台归属逐单保留，不因合并而丢失
-- ===========================================================================
-- 蓝图加粗句「同时识别跨店铺同买家订单（需分别回传平台）」是本模块最容易
-- 做错的地方：合并成一个包裹之后，如果只留「这个包裹」，就再也不知道
-- 「这 3 件货分别属于哪个平台的哪张单」，而平台**不认**跨订单的合并。
-- 因此：
--   * order_consolidation_member 一行一个原订单，逐单存 order_source /
--     platform_code / shop_code / platform_order_ref —— 照抄 V119
--     order_platform_writeback_task 的平台/店铺建模（那两个列都是
--     varchar NOT NULL DEFAULT ''，非平台单留空而不是硬编一个值）；
--   * order_consolidation 头上冗余 distinct_platform_count /
--     distinct_shop_count / cross_shop，这三个数**由触发器从成员行实算**，
--     不接受客户端填 —— 「跨店铺同买家」是要被识别出来的事实，
--     让调用方自己声明就等于允许它声明「没有跨店铺」；
--   * order_consolidation_writeback_task **一行一个平台订单**，物流号逐单回传。
--
-- ⚠️ 实查：sales_order 没有 shop_code 列（9.1 平台单同步属 D5，未交付），
-- 所以成员行的 shop_code 只能是「登记值」：AMAZON/SHOPIFY 等来源单由服务层
-- 从 order_source 推出平台、店铺留空（不凭空编一个店铺号）；同一平台跨店铺的
-- 场景要等 D5 落地后由同步器补 shop_code —— 届时本表结构与读法都不用改。
-- 这一点在 capabilities 里如实报出，而不是假装「跨店铺已完全支持」。
--
-- ===========================================================================
-- 三、「合并履约请求」的下游形状
-- ===========================================================================
-- 8.3.3.2 打包与 9.3.3.1 履约请求在本服务内都还没有落地（9.3.1.2 的下游）。
-- 本模块因此把「合并履约请求」落成 **order_consolidation 头 + member 明细**
-- 这一对（DISPATCHED 状态 = 已下发到打包/履约），并把 8.3.3.2 / 9.3.3.1
-- 标成 capabilities 里 available=false 的缺口，写明交接方式。
-- 与 V92 处理 10 运费未交付同一手法：不假装接通，不从代码常量取任何数据。
--
-- ===========================================================================
-- 四、金额与数量
-- ===========================================================================
-- 数量 numeric(19,6)、金额 numeric(19,4)，全程 BigDecimal 禁浮点。
-- **运费节省是「真实相减」而不是「拍一个数」**：
--   separate_shipping_amount 由触发器按成员订单的 order_charge 中
--   charge_type='SHIPPING' 的行实算（与 V99 同一张真实表，同服务同库不是跨服务读）；
--   consolidated_shipping_amount 是**可选的租户报价**，没报价就是 null；
--   freight_saving_amount = separate − consolidated，**没报价时也是 null** ——
--   给 0 会被读成「合并一单运费也没省」，那是结论而不是缺口（与 V92
--   「lead_time_days 留空 = 未知，不当 0」同一条纪律）。
-- 时间一律 timestamptz，服务层用 OffsetDateTime 绑定并在 values 位置写 ?::timestamptz。
--
-- 合单时间窗（rule.max_window_hours）同理是可维护口径而不是代码常量：
-- 「三小时内的单可以合、五小时后的不能」是运营口径，写进 if 分支就再也改不动。
--
-- ===========================================================================
-- 五、延迟约束触发器与可变列
-- ===========================================================================
-- ⚠️ 本文件所有约束触发器在写成员行时**一律回表读当前行**，不使用 NEW 的可变列：
-- 服务层的写入顺序是「先插头 → 写成员行 → 回填头」，延迟到提交时那次 INSERT
-- 拿着的仍是半成品的 NEW；用 NEW 每次必报。
-- （与 V92 的 guard_routing_decision_switch 同一个坑、同一个解法。）

-- ---------------------------------------------------------------------------
-- 1. 合单规则（租户可维护；**每租户恰好一条**）
-- ---------------------------------------------------------------------------
-- 为什么要求先有规则才允许合单：合单会真实改变包裹形态与回传口径，
-- 「没配规则 = 默认全开」在 9.3.2.2 已经吃过一次亏（MCF 资质 UNKNOWN 被当成有）。
-- 这里取同一立场：**没登记规则就报「未配置」，不给隐含默认值**。
CREATE TABLE order_consolidation_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 一租户一条规则：UNIQUE(tenant_id) 本身就是「恰好一条」的表达。
  -- 两条并存时「取哪条」取决于查询顺序，同样输入在不同时刻算出不同结果。
  UNIQUE(tenant_id),
  -- **单批最多合几笔订单**。下限 2：本模块是 N→1，max=1 等于把它关掉了，
  -- 与其让调用方合出一张「合并单」却只挂一个订单，不如在配置时就拒绝。
  max_orders_per_consolidation int NOT NULL CHECK(max_orders_per_consolidation >= 2),
  -- **是否允许跨店铺合并**。关掉时合并必须落在同一平台同一店铺；
  -- 服务层按 member.platform_code / shop_code 逐单比对，
  -- DB 侧由 guard_order_consolidation_cross_shop 触发器再独立复核一次。
  allow_cross_shop boolean NOT NULL DEFAULT true,
  -- **可合单时间窗（小时）**：以最早一笔的 submitted_at 为起点，
  -- 超过这个窗口的订单不再进候选集。留空 = 不限时间（不是 0；0 会让任何
  -- 跨时段的单都不可合，那与「不限」是两回事）。
  max_window_hours int CHECK(max_window_hours IS NULL OR max_window_hours > 0),
  -- 币种必须一致：不同币种的订单合成一个包裹，运费与包材省下来了，
  -- 但对账与退款按哪一币种结算是新的问题（12 财务未交付），这里直接挡住。
  require_same_currency boolean NOT NULL DEFAULT true,
  -- 支付方式是否必须一致：货到付款与已付款合成一箱，包裹怎么贴单、
  -- 收款怎么走会分叉（9.2 风控未交付），所以给租户一个开关而不是替他们定。
  require_same_payment_method boolean NOT NULL DEFAULT true,
  -- 是否要求成员订单都有收货地址快照。为假时无地址的单会被合进来，
  -- 而包裹地址取不到 —— 允许这个开关是因为运营有时会先合单、后补地址。
  require_shipping_address boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  updated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(updated_at>=created_at)
);

-- ---------------------------------------------------------------------------
-- 2. 合并头（一次合单 = 一个包裹）
-- ---------------------------------------------------------------------------
-- **一条合单一行，不做「合单实体 + 反复改」**：合单是履约凭据，
-- 「当时为什么把这几单合在一起」要能复现；拆开重来的动作是 CANCELLED + 新建。
CREATE TABLE order_consolidation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  consolidation_no varchar(64) NOT NULL CHECK(btrim(consolidation_no)<>''),
  -- 状态机：PLANNED 已合并（成员已定）→ CONFIRMED 已确认（可下发打包）
  --         → DISPATCHED 已下发到 8.3.3.2 打包 / 9.3.3.1 履约；CANCELLED 终态。
  status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status IN (
    'PLANNED','CONFIRMED','DISPATCHED','CANCELLED')),
  -- 两个指纹：与成员订单在**同一时刻**算出的值，服务层原样抄进来。
  -- 反查「这个包裹由哪些候选订单构成」靠它们，不需要全表扫。
  buyer_fingerprint varchar(64) NOT NULL CHECK(btrim(buyer_fingerprint)<>''),
  address_fingerprint char(32) NOT NULL CHECK(btrim(address_fingerprint)<>''),
  -- 买家与地址的**可读快照**：指纹是 md5，运营看不懂、客服也没法念给买家听。
  buyer_name varchar(120) NOT NULL DEFAULT '',
  buyer_phone varchar(64) NOT NULL DEFAULT '',
  buyer_email varchar(254) NOT NULL DEFAULT '',
  address_line varchar(600) NOT NULL DEFAULT '',
  country_code char(2) NOT NULL DEFAULT '',
  -- 币种：合并体内所有订单同一个币（rule.require_same_currency 为真时）。
  currency_code varchar(3) NOT NULL,
  payment_method varchar(24) NOT NULL DEFAULT '',
  -- ⚠️ 下面这五个数（order_count / distinct_platform_count / distinct_shop_count /
  -- cross_shop / total_qty / total_line_count / separate_shipping_amount）
  -- **全部由触发器 guard_order_consolidation_totals 从成员行实算回填**，
  -- 不接受客户端填 —— 见文件头第二节与 guard 的实现。
  order_count int NOT NULL DEFAULT 0 CHECK(order_count >= 0),
  distinct_platform_count int NOT NULL DEFAULT 0 CHECK(distinct_platform_count >= 0),
  distinct_shop_count int NOT NULL DEFAULT 0 CHECK(distinct_shop_count >= 0),
  cross_shop boolean NOT NULL DEFAULT false,
  total_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_qty >= 0),
  total_line_count int NOT NULL DEFAULT 0 CHECK(total_line_count >= 0),
  -- 各成员订单单独发时**本来要付的运费**（按 order_charge 的 SHIPPING 行实算）。
  separate_shipping_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(separate_shipping_amount >= 0),
  -- 合并成一个包裹的运费：**租户报价，可空**。不空时 freight_saving 才成立。
  consolidated_shipping_amount numeric(19,4)
    CHECK(consolidated_shipping_amount IS NULL OR consolidated_shipping_amount >= 0),
  -- 没给合并运费报价时是 **null 而不是 0**（见文件头第四节）。
  freight_saving_amount numeric(19,4)
    CHECK(freight_saving_amount IS NULL OR freight_saving_amount >= 0),
  -- 合并时刻由**调用方显式给出**（合单是对某个已发生动作的记账，不是 now()），
  -- 也不允许早于最早一笔成员订单的 submitted_at（延迟约束触发器复核）。
  consolidated_at timestamptz NOT NULL,
  confirmed_at timestamptz,
  confirmed_by_membership_id uuid,
  dispatched_at timestamptz,
  dispatched_by_membership_id uuid,
  cancelled_at timestamptz,
  cancel_reason varchar(400) NOT NULL DEFAULT '',
  note varchar(400) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,consolidation_no),
  -- ⚠️ 下面这些 CHECK 刻意**全部命名**：PostgreSQL 给未命名约束编的
  -- `<表名>_check<N>` 在本表有十几个 CHECK 时完全不可读 ——
  -- 提交被拒时只报「violates check constraint order_consolidation_check6」，
  -- 还得回去数第几个。这里命名换来的是 23514 里直接出现是哪条业务规则。
  CONSTRAINT order_consolidation_currency_shape
    CHECK(currency_code ~ '^[A-Z]{3}$'),
  CONSTRAINT order_consolidation_country_shape
    CHECK(country_code='' OR country_code ~ '^[A-Z]{2}$'),
  CONSTRAINT order_consolidation_confirmed_needs_actor
    CHECK(status<>'CONFIRMED' OR (confirmed_at IS NOT NULL AND confirmed_by_membership_id IS NOT NULL)),
  CONSTRAINT order_consolidation_dispatched_needs_actor
    CHECK(status<>'DISPATCHED' OR (dispatched_at IS NOT NULL AND dispatched_by_membership_id IS NOT NULL)),
  CONSTRAINT order_consolidation_cancelled_needs_reason
    CHECK(status<>'CANCELLED' OR (cancelled_at IS NOT NULL AND btrim(cancel_reason)<>'')),
  CONSTRAINT order_consolidation_planned_has_no_confirm_time
    CHECK(status<>'PLANNED' OR confirmed_at IS NULL),
  CONSTRAINT order_consolidation_saving_needs_quote
    CHECK(freight_saving_amount IS NULL OR consolidated_shipping_amount IS NOT NULL),
  -- 给了合并报价就必须给出节省额，且它必须等于两者之差（DB 侧独立复算）。
  CONSTRAINT order_consolidation_saving_is_real_difference
    CHECK(freight_saving_amount IS NULL
          OR freight_saving_amount = round(separate_shipping_amount - consolidated_shipping_amount,4)),
  -- ⚠️ **刻意不在这里放 CHECK(separate_shipping_amount >= consolidated_shipping_amount)**：
  -- separate_shipping_amount 是成员行写完之后才由 guard_order_consolidation_totals 实算回填的，
  -- INSERT 那一刻它还是 DEFAULT 0，而报价已经是真实值 —— 「0 >= 报价」永远为假，
  -- 于是任何带报价的合并在**插入头行**时就被拒，根本走不到算汇总那一步。
  -- 这条不变量改由延迟约束触发器 guard_order_consolidation_shape 在汇总算完之后复核
  -- （见第七节），那才是它唯一能成立的时机。
  CONSTRAINT order_consolidation_timestamps_ordered
    CHECK(updated_at>=created_at)
);
CREATE INDEX order_consolidation_by_buyer
  ON order_consolidation(tenant_id,buyer_fingerprint,consolidated_at DESC);
CREATE INDEX order_consolidation_by_address
  ON order_consolidation(tenant_id,address_fingerprint,consolidated_at DESC);
CREATE INDEX order_consolidation_by_status
  ON order_consolidation(tenant_id,status,consolidated_at DESC);
CREATE INDEX order_consolidation_by_created
  ON order_consolidation(tenant_id,created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. 成员行（各原订单的关联记录 = 蓝图输出②）
-- ---------------------------------------------------------------------------
-- 平台/店铺/平台单号三列照抄 V119 order_platform_writeback_task 的建模：
-- 非平台单（MANUAL）平台列留空，**不硬编一个 'NONE'** —— 留空与真值
-- 可以用「有没有平台列」区分，硬编的哨兵值不能。
CREATE TABLE order_consolidation_member (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  consolidation_id uuid NOT NULL,
  -- 与 sales_order 建同服务同库 FK：V99 自己的七张子表都这么建。
  -- **刻意不加 ON DELETE CASCADE**：合单是举证凭据，已被合进包裹的订单
  -- 在数据层就不该被物理删除（级联删掉会让「这单发过货」无从追查）。
  order_id uuid NOT NULL,
  -- 我方单号快照：外部订单号可能为空，我方单号不会，空值判定不能靠它。
  order_no varchar(64) NOT NULL DEFAULT '',
  order_source varchar(32) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  platform_order_ref varchar(128) NOT NULL DEFAULT '',
  currency_code varchar(3) NOT NULL DEFAULT '',
  payment_method varchar(24) NOT NULL DEFAULT '',
  -- 本单的行数与件数：由 guard_order_consolidation_totals 实算回填。
  line_count int NOT NULL DEFAULT 0 CHECK(line_count >= 0),
  total_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_qty >= 0),
  -- 该单单独发时本来要付的运费（order_charge 的 SHIPPING 行实算）。
  separate_shipping_amount numeric(19,4) NOT NULL DEFAULT 0
    CHECK(separate_shipping_amount >= 0),
  -- 成员状态：PLANNED → CONSOLIDATED（随合并确认）→ DISPATCHED（随下发），
  -- CANCELLED（随合并作废）。与合并头同向，不允许成员比头更靠前。
  member_status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(member_status IN (
    'PLANNED','CONSOLIDATED','DISPATCHED','CANCELLED')),
  -- 该成员单被合进本包裹时算出的两个指纹（抄自 order_address_snapshot 的现算值）。
  buyer_fingerprint varchar(64) NOT NULL DEFAULT '',
  address_fingerprint char(32) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- ⚠️ **一个订单只能在一个未终止的合并里出现一次**：这是「同一包裹不重复发货」
  -- 与「同一张单不进两个包裹」两道闸的同一道表达式。
  -- 跨租户重复由 UNIQUE(tenant_id, consolidation_id, order_id) 之外的这个部分唯一索引负责。
  UNIQUE(tenant_id,consolidation_id,order_id),
  FOREIGN KEY(tenant_id,consolidation_id) REFERENCES order_consolidation(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id),
  -- 我方单号与平台单号**至少有一个非空**：两个都空的成员行在事后无法定位是哪张单，
  -- 而「合到哪张单上去了」是这类操作最常被追问的一问。
  CHECK(btrim(order_no)<>'' OR btrim(platform_order_ref)<>'')
);
-- 部分唯一索引：只约束「未终止」的合并。已作废的合并不该继续占着订单，
-- 否则一次取消会让这些订单**永远**合不进下一批 —— 这是最容易踩的一类死锁。
CREATE UNIQUE INDEX order_consolidation_member_active_order
  ON order_consolidation_member(tenant_id,order_id)
  WHERE member_status <> 'CANCELLED';
CREATE INDEX order_consolidation_member_by_consolidation
  ON order_consolidation_member(tenant_id,consolidation_id,order_no);

-- ---------------------------------------------------------------------------
-- 4. 物流号回传任务（一行一个平台订单 = 蓝图加粗句的直接落法）
-- ---------------------------------------------------------------------------
-- 「合单后每个平台订单仍要单独回传物流号」这句如果只写在注释里，
-- 下一次有人把回传做成「一个包裹一个任务」时没有任何东西会拦住他。
-- 所以它在**表结构**上成立：一个包裹里有多少个平台订单就有多少个回传任务，
-- UNIQUE(tenant_id, member_id) 保证一单一任务，不多不少。
--
-- **非平台单（platform_code 为空）不产生回传任务**：手工单没有平台可回传，
-- 给它造一个任务是给「回传零失败」这个指标掺水。
CREATE TABLE order_consolidation_writeback_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  consolidation_id uuid NOT NULL,
  member_id uuid NOT NULL,
  -- 恰好一个父：回传任务要么服务于合并成员，要么是合并级（整包作废通知）。
  consolidation_level boolean NOT NULL DEFAULT false,
  platform_code varchar(32) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  platform_order_ref varchar(128) NOT NULL DEFAULT '',
  -- **物流号**：合单之后每个平台订单要单独回传的就是这一个值。
  tracking_number varchar(128) NOT NULL DEFAULT '',
  carrier_code varchar(32) NOT NULL DEFAULT '',
  status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(status IN (
    'PENDING','SUBMITTED','CONFIRMED','REJECTED','PENDING_VERIFICATION')),  -- 结果未知要先查证再重发（蓝图 9.4.2.1 加粗句）：所以有独立的
  -- PENDING_VERIFICATION，而不是把「不知道」塞进 REJECTED。
  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  last_error varchar(500) NOT NULL DEFAULT '',
  submitted_at timestamptz,
  settled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一单一个任务：不多（重复任务 = 重复回传 = 平台侧重复发货记录），
  -- 不少（漏一个 = 那张平台单永远显示未发货）。
  UNIQUE(tenant_id,member_id),
  FOREIGN KEY(tenant_id,consolidation_id) REFERENCES order_consolidation(tenant_id,id),
  FOREIGN KEY(tenant_id,member_id) REFERENCES order_consolidation_member(tenant_id,id),
  -- 确认回传成功必须带物流号，否则「已回传」是无内容的一句谎。
  -- 全部命名：未命名约束的 `_checkN` 编号在本表有多条 CHECK 时完全不可读（见第 6 节说明）。
  CONSTRAINT order_consolidation_writeback_confirmed_needs_tracking
    CHECK(status<>'CONFIRMED' OR btrim(tracking_number)<>''),
  CONSTRAINT order_consolidation_writeback_submitted_needs_time
    CHECK((submitted_at IS NULL) OR status<>'PENDING'),
  CONSTRAINT order_consolidation_writeback_settled_status_only
    CHECK((settled_at IS NULL) OR status IN ('CONFIRMED','REJECTED','PENDING_VERIFICATION')),
  CONSTRAINT order_consolidation_writeback_timestamps_ordered
    CHECK(updated_at>=created_at)
);
CREATE INDEX order_consolidation_writeback_pending
  ON order_consolidation_writeback_task(tenant_id,status,created_at)
  WHERE status IN ('PENDING','PENDING_VERIFICATION','REJECTED');
CREATE INDEX order_consolidation_writeback_by_consolidation
  ON order_consolidation_writeback_task(tenant_id,consolidation_id);

-- ---------------------------------------------------------------------------
-- 5. 事件流（append-only 留痕，与 V92 的 fulfillment_routing_event 同形）
-- ---------------------------------------------------------------------------
CREATE TABLE order_consolidation_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  consolidation_id uuid NOT NULL,
  event_type varchar(24) NOT NULL CHECK(event_type IN (
    'CONSOLIDATED','CONFIRMED','DISPATCHED','CANCELLED','WRITEBACK_RECORDED')),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  detail varchar(400) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,consolidation_id) REFERENCES order_consolidation(tenant_id,id)
  -- ⚠️ **刻意不加 occurred_at>=created_at 这类 CHECK**：occurred_at 记的是业务上
  -- 真实发生的那一刻（发货时刻、平台回执时刻），它**允许早于**本行被写库的日期 ——
  -- 补录昨天的发货、或把一批历史合并一次性补进来，都是正常业务动作。
  -- 反过来要求「事件不许发生在写入之前」会让补录数据根本进不来，
  -- 而「补录」恰恰是 ERP 上线后最常见的动作。
);
CREATE INDEX order_consolidation_event_stream
  ON order_consolidation_event(tenant_id,consolidation_id,occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 6. 实算触发器：头的汇总数一律从成员行算
-- ---------------------------------------------------------------------------
-- **为什么必须由 DB 实算**：order_count / total_qty / separate_shipping_amount /
-- cross_shop 这四个数是「这个包裹里到底有什么」的唯一答案。让服务层算一遍再写进去，
-- 就等于允许「明细 3 单、头写 2 单」这种自相矛盾的记录存在 —— 而它会直接
-- 误导「这单合了多少钱运费、要不要回传」。
--
-- 运费按 **order_charge 的 charge_type='SHIPPING' 行**实算（V99 的真实表，
-- 同服务同库）：V99 明确「运费不会被静默塞进别的费用」，所以取这一类是准确的。
-- 金额求和后 round(...,4) 与 numeric(19,4) 对齐，避免浮点尾数。
CREATE FUNCTION guard_order_consolidation_totals() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  head record;
  ship numeric(19,4);
  quoted numeric(19,4);
BEGIN
  SELECT * INTO head FROM order_consolidation
    WHERE id = coalesce(NEW.consolidation_id, OLD.consolidation_id) AND tenant_id = NEW.tenant_id;
  IF head.id IS NULL THEN RETURN NULL; END IF;

  -- 成员行里的行数与件数也由 sales_order_line 实算：它们是「这单有几行几件」，
  -- 快照过来就可能与订单本身分叉。
  -- ⚠️ 聚合源**不能**写成「UPDATE ... FROM (SELECT ... FROM sales_order l WHERE l.id = m.order_id)」：
  -- PostgreSQL 不允许在 UPDATE 的 FROM 子查询里引用更新目标表（invalid reference to
  -- FROM-clause entry）。正确形状是先把成员订单 id 收成一张集合，再对 sales_order_line 分组。
  UPDATE order_consolidation_member m
     SET line_count = agg.line_count,
         total_qty = agg.total_qty,
         separate_shipping_amount = agg.ship
    FROM (SELECT ol.order_id,
                 count(*) AS line_count,
                 sum(ol.quantity) AS total_qty,
                 (SELECT coalesce(round(sum(ch.amount),4),0) FROM order_charge ch
                   WHERE ch.tenant_id = ol.tenant_id AND ch.order_id = ol.order_id
                     AND ch.charge_type = 'SHIPPING') AS ship
            FROM sales_order_line ol
           WHERE ol.tenant_id = NEW.tenant_id
             AND ol.order_id IN (SELECT order_id FROM order_consolidation_member
                                  WHERE tenant_id = NEW.tenant_id
                                    AND consolidation_id = NEW.consolidation_id)
           GROUP BY ol.tenant_id, ol.order_id) agg
   WHERE m.tenant_id = NEW.tenant_id AND m.consolidation_id = NEW.consolidation_id
     AND m.order_id = agg.order_id;

  SELECT coalesce(round(sum(separate_shipping_amount),4),0),
         coalesce(sum(total_qty),0),
         coalesce(sum(line_count),0),
         count(*),
         count(DISTINCT nullif(platform_code,'')),
         count(DISTINCT nullif(shop_code,'')),
         (count(DISTINCT nullif(platform_code,'')) > 1 OR count(DISTINCT nullif(shop_code,'')) > 1)
    INTO ship, head.total_qty, head.total_line_count, head.order_count,
         head.distinct_platform_count, head.distinct_shop_count, head.cross_shop
    FROM order_consolidation_member
   WHERE tenant_id = NEW.tenant_id AND consolidation_id = NEW.consolidation_id
     AND member_status <> 'CANCELLED';

  UPDATE order_consolidation
     SET order_count = head.order_count,
         total_qty = head.total_qty,
         total_line_count = head.total_line_count,
         separate_shipping_amount = ship,
         distinct_platform_count = head.distinct_platform_count,
         distinct_shop_count = head.distinct_shop_count,
         cross_shop = head.cross_shop,
         freight_saving_amount = CASE
           WHEN head.consolidated_shipping_amount IS NULL THEN NULL
           ELSE round(ship - head.consolidated_shipping_amount,4) END
   WHERE tenant_id = NEW.tenant_id AND id = NEW.consolidation_id;

  -- ③ 「合并成一个包裹的运费不该比分开发还贵」：**只能放在这里**。
  --    separate_shipping_amount 正是本触发器刚刚实算回填的，放在形状触发器或 CHECK 里
  --    都太早 —— 头行插入那一刻它还是 DEFAULT 0，而报价已是真实值，
  --    两者一比就是「报价 11.25 > 分开发 0」的假阳性（本迁移踩过这个坑）。
  SELECT consolidated_shipping_amount INTO quoted
    FROM order_consolidation WHERE tenant_id = NEW.tenant_id AND id = NEW.consolidation_id;
  IF quoted IS NOT NULL AND quoted > ship THEN
    RAISE EXCEPTION '合并运费报价 % 高于分开发合计 %：合单是为了省运费，这样只会多花钱',
      quoted, ship USING ERRCODE = '23514';
  END IF;

  RETURN NULL;
END $$;
-- ⚠️ **UPDATE OF 列清单是防死循环的关键，不是可选的优化**：
-- 本触发器自己会 UPDATE order_consolidation_member 的 line_count / total_qty /
-- separate_shipping_amount。若监听整行，更新自己写的那几列会**再次排队**一个
-- 本触发器的事件 → 每轮又写一次同样的值 → 提交时无限循环，连接被挂死。
-- 只监听「谁属于哪个包裹」与「成员状态」这几列：它们的变更真的会影响汇总口径。
CREATE CONSTRAINT TRIGGER order_consolidation_totals_guard
  AFTER INSERT OR DELETE OR UPDATE OF consolidation_id, order_id, member_status
  ON order_consolidation_member
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_consolidation_totals();

-- ---------------------------------------------------------------------------
-- 7. 合单时刻与跨店铺规则复核
-- ---------------------------------------------------------------------------
-- ① consolidated_at 不许早于最早一笔成员订单的提交时刻：
--    「合单时间早于下单时间」在业务上不成立，而它出现时最常见的原因是
--    客户端传了 now() 之外的随手时间。
-- ② 规则要求不许跨店铺时，头上实算出的 cross_shop 必须为 false —— 复核的是
--    **实算值**而不是「谁说过没跨店铺」。
CREATE FUNCTION guard_order_consolidation_shape() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  c record;
  earliest timestamptz;
  rule_allow_cross boolean;
BEGIN
  SELECT * INTO c FROM order_consolidation WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
  IF c.id IS NULL THEN RETURN NULL; END IF;

  SELECT min(l.submitted_at) INTO earliest
    FROM order_consolidation_member m
    JOIN sales_order l ON l.tenant_id = m.tenant_id AND l.id = m.order_id
   WHERE m.tenant_id = NEW.tenant_id AND m.consolidation_id = NEW.id
     AND m.member_status <> 'CANCELLED';
  IF earliest IS NOT NULL AND c.consolidated_at < earliest THEN
    RAISE EXCEPTION '合单时刻 % 早于最早一笔成员订单的提交时刻 %', c.consolidated_at, earliest
      USING ERRCODE = '23514';
  END IF;

  SELECT allow_cross_shop INTO rule_allow_cross
    FROM order_consolidation_rule WHERE tenant_id = NEW.tenant_id;
  -- 没配规则时**不拦**形状（合成本身已经被服务层的「未配置规则」409 挡住了），
  -- 这里只复核「配了规则且规则不许跨店铺」这一种情况。
  IF rule_allow_cross IS FALSE AND c.cross_shop THEN
    RAISE EXCEPTION '合单规则不允许跨店铺合并，但 % 号合并跨了 % 个平台 / % 个店铺',
      c.consolidation_no, c.distinct_platform_count, c.distinct_shop_count USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER order_consolidation_shape_guard
  AFTER INSERT OR UPDATE ON order_consolidation
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_consolidation_shape();

-- 成员状态不许比头更靠前：DISPATCHED 的成员不能挂在 PLANNED 的头上，
-- 否则「包裹还没确认、货已经发走了」这种记录能写进库。
CREATE FUNCTION guard_order_consolidation_member_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  head_status varchar(16);
BEGIN
  SELECT status INTO head_status FROM order_consolidation
    WHERE tenant_id = NEW.tenant_id AND id = NEW.consolidation_id;
  IF head_status IS NULL THEN RETURN NULL; END IF;
  IF NEW.member_status IN ('CONSOLIDATED','DISPATCHED')
     AND head_status NOT IN ('CONFIRMED','DISPATCHED') THEN
    RAISE EXCEPTION '合并头处于 %，成员行不能是 %（成员不许比头更靠前）',
      head_status, NEW.member_status USING ERRCODE = '23514';
  END IF;
  IF NEW.member_status = 'CANCELLED' AND head_status <> 'CANCELLED' THEN
    RAISE EXCEPTION '合并头未作废，成员行不能是 CANCELLED' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER order_consolidation_member_status_guard
  AFTER INSERT OR UPDATE OF member_status ON order_consolidation_member
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_consolidation_member_status();

-- ---------------------------------------------------------------------------
-- 8. 状态推进函数（SECURITY DEFINER，与 V92 advance_routing_decision_status 同形）
-- ---------------------------------------------------------------------------
-- 单向状态机 PLANNED → CONFIRMED → DISPATCHED，以及任一非终态 → CANCELLED。
-- **CANCELLED 不可离开**（要重来就新建一次合单）；DISPATCHED 之后不可作废
-- —— 包裹已经进打包了，此时「作废」在业务上等于让仓库凭空多一个包裹。
CREATE FUNCTION advance_order_consolidation_status(uuid,varchar,uuid,varchar,timestamptz)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE c order_consolidation%ROWTYPE;
BEGIN
  SELECT * INTO c FROM order_consolidation
    WHERE tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid AND id = $1 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '合单不存在' USING ERRCODE='P40401'; END IF;
  IF $2 = 'CONFIRMED' AND c.status = 'PLANNED' THEN
    IF (SELECT count(*) FROM order_consolidation_member
         WHERE tenant_id=c.tenant_id AND consolidation_id=c.id) < 2 THEN
      RAISE EXCEPTION '合单至少要含两笔订单才能确认（本模块是 N→1）' USING ERRCODE='23514';
    END IF;
    UPDATE order_consolidation SET status='CONFIRMED', confirmed_at=$5, confirmed_by_membership_id=$3,
           updated_at=now(), version=version+1 WHERE tenant_id=c.tenant_id AND id=c.id;
    UPDATE order_consolidation_member SET member_status='CONSOLIDATED'
     WHERE tenant_id=c.tenant_id AND consolidation_id=c.id AND member_status='PLANNED';
  ELSIF $2 = 'DISPATCHED' AND c.status = 'CONFIRMED' THEN
    UPDATE order_consolidation SET status='DISPATCHED', dispatched_at=$5, dispatched_by_membership_id=$3,
           updated_at=now(), version=version+1 WHERE tenant_id=c.tenant_id AND id=c.id;
    UPDATE order_consolidation_member SET member_status='DISPATCHED'
     WHERE tenant_id=c.tenant_id AND consolidation_id=c.id AND member_status IN ('PLANNED','CONSOLIDATED');
  ELSIF $2 = 'CANCELLED' AND c.status IN ('PLANNED','CONFIRMED') THEN
    IF btrim(coalesce($4,'')) = '' THEN
      RAISE EXCEPTION '作废必须给原因' USING ERRCODE='23514';
    END IF;
    UPDATE order_consolidation SET status='CANCELLED', cancelled_at=$5, cancel_reason=$4,
           updated_at=now(), version=version+1 WHERE tenant_id=c.tenant_id AND id=c.id;
    UPDATE order_consolidation_member SET member_status='CANCELLED'
     WHERE tenant_id=c.tenant_id AND consolidation_id=c.id;
  ELSE
    RAISE EXCEPTION '非法状态流转 % → %', c.status, $2 USING ERRCODE='23514';
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 9. append-only：事件流不可改写（照抄 V92 第 8.5 节纪律）
-- ---------------------------------------------------------------------------
REVOKE UPDATE,DELETE ON order_consolidation_event FROM trade_app;
CREATE FUNCTION guard_consolidation_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：合单事件是回传与对账的证据',
  TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_consolidation_event_append_only BEFORE UPDATE OR DELETE ON order_consolidation_event
  FOR EACH ROW EXECUTE FUNCTION guard_consolidation_event_append_only();

-- ---------------------------------------------------------------------------
-- 10. 多租户：RLS + 授权（与 trade V92 第 9 节、warehouse V85 第 9 节同一套写法）
-- ---------------------------------------------------------------------------
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 合单尤其不能省：候选检索是「同一买家 + 同一地址」的宽条件查询，
-- 一旦漏掉租户谓词就是**跨租户合单** —— 两个互不相识的公司的订单会被
-- 打进同一个包裹并互相看到对方的买家姓名与地址。这是最严重的一类泄露。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_consolidation_rule','order_consolidation',
    'order_consolidation_member','order_consolidation_writeback_task',
    'order_consolidation_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_consolidation_rule,order_consolidation,
  order_consolidation_member,order_consolidation_writeback_task,
  order_consolidation_event TO trade_app;
-- 第 9 节的 REVOKE 写在 GRANT 之前会被覆盖回去，这里按顺序重放：
-- 明确「授权在前、收回在后」。
REVOKE UPDATE,DELETE ON order_consolidation_event FROM trade_app;
-- 状态推进函数是 SECURITY DEFINER，显式授权让「谁能推进合单状态」在 \df+ 上一眼可见。
GRANT EXECUTE ON FUNCTION advance_order_consolidation_status(uuid,varchar,uuid,varchar,timestamptz) TO trade_app;
-- 本迁移**不 ALTER 任何既有表**（sales_order / sales_order_line / order_address_snapshot /
-- order_charge / order_fulfillment_line_progress 全部只读），
-- 跨车道 DDL 回归面 = 0。
