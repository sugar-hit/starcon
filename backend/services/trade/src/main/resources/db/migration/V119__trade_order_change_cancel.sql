-- ===========================================================================
-- 9.1.1.3 订单变更与取消（trade 服务）
-- ===========================================================================
--
-- 蓝图 09-order-and-fulfillment.md 9.1.1.3 原文：
--   职责：处理订单变更与取消：地址修改、数量调整（需平台允许）、取消未发货数量
--         （先确认仓库停止作业并释放占用；已发货数量转售后），并把变更同步回平台。
--   输入：变更/取消请求；平台可变更规则；当前履约状态。
--   输出：取消申请/处理中/已确认/拒绝或待核验状态 + 仓库停止作业及释放结果
--         + 平台回写任务；仅在责任方确认后记录对应数量的取消终态。
--   关系：上游 = 9.1.1.2；下游 = 7.2.1.3 释放、2.5.1.1 回写、11 售后（已发货转售后）。
--
-- ---------------------------------------------------------------------------
-- 第零节：本迁移**零 ALTER 既有表**（跨车道 DDL 回归面 = 0）
-- ---------------------------------------------------------------------------
-- 既有表一律不碰：V1 trade_tenancy_probe、V92 的七张 fulfillment_*、V97 的五张
-- 加工单表、V98 的两张 BOM 快照表、V99 的七张 sales_order 簇表、V100/V101 的索引。
-- 本迁移只 CREATE 十张新表，既有模块（9.1.2.2 / 9.1.1.1 / 9.1.1.2 / 9.3.2.2 /
-- 9.3.2.1 / 6.4.1.1 / 9.4.x）不需要为这条车道重跑集成测试。
--
-- ---------------------------------------------------------------------------
-- 一、本模块**不改写订单快照** —— 这是被 V99 逼出来的形态，不是选择
-- ---------------------------------------------------------------------------
-- V99 第 8.3 节：订单一旦 SUBMITTED，sales_order_line / order_address_snapshot /
-- order_charge / sales_order_party_snapshot 的 UPDATE/DELETE 被触发器
-- guard_sales_order_snapshot_frozen 挡住；第 8.5 节冻结单号/来源/外部单号；
-- 第 8.6 节禁止 SUBMITTED 退回 DRAFT。
--
-- 也就是说「地址修改」「数量调整」在已提交的订单上**在数据层就改不动**。
-- 那是 9.1.2.2 有意立的规矩：订单上的商品、地址、客户都是**下单当时**的快照，
-- 事后改写会让「当时那笔交易是什么」在历史里出现两个答案。
--
-- 本模块因此把变更与取消记成**独立单据**：order_change_request /
-- order_cancellation_request 自带变更前后的值，订单快照保持原样。
-- 审计时看到的是「这张单在 T 时刻申请把地址从 A 改成 B、于 T' 被确认」，
-- 而不是「这张单的地址现在是 B，而且没人说得清它原来是什么」。
--
-- 顺带说明为什么不另开 order_line_current / order_address_current 之类的
-- 「当前值」覆盖表：变更单本身就是带时间戳的不可变凭据，
-- 「当前值」只能是「最后一次 CONFIRMED 的变更」这一条查询，
-- 多存一份可覆盖的当前值就等于把 V99 刚钉死的「两个答案」重新请回来。
--
-- ---------------------------------------------------------------------------
-- 二、已发货数量从哪来 —— trade 侧投影，**不跨库读 warehouse**
-- ---------------------------------------------------------------------------
-- 蓝图硬规则：「**已发货订单不能取消只能转售后**，这是硬规则」
-- （实体目录 §6.5：不能「回滚 HTTP 发货」）。要执行这条，服务端必须知道
-- 每张订单行**已发货多少**。
--
-- 实查结论（写这份迁移时逐个核过）：
--   * sales_order 簇（V99）**完全没有履约列**：sales_order.status 的 CHECK 只有
--     ('DRAFT','SUBMITTED')，语义是「有没有进审单队列」；sales_order_line 只有
--     quantity，没有已发货量、没有预留量、没有拣货量。
--   * V116 fulfillment_warehouse_allocation_* 是**选哪个仓**的判定与留痕
--     （规则 / 仓档案 / 估算 / 决策 / 候选 / 挂起 / 事件），
--     它记的是分配决策与 shortfall，**不是发货事实**。
--   * 真正的发货事实（8 出库单的 SHIPPED 终态与已发件数）在 warehouse 的库里；
--     9.3.3.1 履约请求**未开发**（台账状态），trade 侧没有任何履约模块写入它。
--
-- 所以本表 order_fulfillment_line_progress 是**本服务自己的只读投影**：
-- 由本模块提供的授权入口 POST /trade/sales-orders/change/fulfillment-progress
-- 写入，来源枚举 progress_source 如实标注（MODULE_9_3_3_1 / EVENT_8_OUTBOUND /
-- TENANT_MAINTAINED）。**不建跨库 FK、不建跨库视图、不写 warehouse 的表** ——
-- AGENTS.md 模块边界纪律：跨服务走授权 API / event。
-- 9.3.3.1 落地后由同步器按同一形状写入并改来源枚举，**表结构与判定逻辑都不用改**。
--
-- 投影表为空时语义是「**没有任何记录说这些货已发出**」，不是「这些货确实没发」；
-- 这两者的差别由 capabilities 显式报出（availableProgress=false 时服务端**拒绝**
-- 按「未发货」放行取消，见第四节 guard_order_cancellation_no_shipped 的说明）。
--
-- ---------------------------------------------------------------------------
-- 三、平台可变更规则 —— 租户可维护的真实表，不是代码常量
-- ---------------------------------------------------------------------------
-- 蓝图输入句「平台可变更规则」+「数量调整（需平台允许）」。
-- 「平台允不允许改」是**平台 × 店铺 × 变更类型**的商务事实（各平台政策不同、
-- 改过还要走买家确认），代码里 if 不出来。所以建 order_platform_change_rule：
-- 租户维护，带生效窗口，改动带 version 乐观锁。
-- 窗口 deadline_hours（距下单多少小时内才允许）也是数据而不是常量：
-- Amazon 与 1688 的可改窗口本来就不一样。
--
-- ---------------------------------------------------------------------------
-- 四、四条钉在**写入路径**上的 DB 不变量（绕过服务层写 SQL 同样被挡）
-- ---------------------------------------------------------------------------
-- 1) guard_order_cancellation_line_terminal
--    「仅在责任方确认后记录对应数量的取消终态」：order_cancellation_line.cancelled_qty
--    在父单未 CONFIRMED 时**必须为 0**。申请阶段填了终态数量，
--    界面上就会显示「已取消 N 件」而责任方一个都没确认过。
--
-- 2) guard_order_cancellation_confirm
--    「先确认仓库停止作业并释放占用，不能先取消再释放」：
--    取消单置 CONFIRMED 时，它名下**每一条** order_warehouse_hold_task 都必须
--    已经 COMPLETED，且每一条 order_cancellation_line 的 cancelled_qty
--    都已等于 cancel_qty。先置终态、释放回执后补 —— 本表做不到。
--
-- 3) guard_order_cancellation_no_shipped
--    「已发货数量不可取消」：cancel_qty 不得超过 ordered_qty − shipped_qty，
--    shipped_qty 取自 order_fulfillment_line_progress。
--    投影缺行时视为「无发货记录」并放行 —— 这是**有意的**：
--    投影是 9.3.3.1 未开发期的输入缺口，若把缺行当「已发货」，
--    所有订单会一律拒绝取消，模块直接不可用；把缺行当「未发货」时
--    capabilities 会报 availableProgress=false，界面上明确提示
--    「已发货量未接入，取消未发货数量的核对不完整」，操作人知情。
--    真正的闸门是 9.3.3.1 / 8 出库事件接上投影之后 —— 那一刻本守卫自动生效，
--    不需要改代码。
--
-- 4) guard_order_fulfillment_progress_vs_cancel
--    「同一件货不能既已发货又已取消」：投影写入时
--    shipped_qty + 该行已 CONFIRMED 的 cancelled_qty 之和不得超过 ordered_qty。
--    没有这条的话，取消已确认之后到达的延迟发货事件会把同一件货
--    在两处都记成终态，之后谁也说不清该不该退钱。
--
-- 守卫一律用 BEFORE 触发器、**回表读当前行**（照抄 V85 / V90 / V92 同一解法），
-- 不使用 NEW 的可变列做判据。
--
-- ---------------------------------------------------------------------------
-- 五、取消状态机（服务层与 DB 层同一份矩阵，不各写一份）
-- ---------------------------------------------------------------------------
--   REQUESTED ──▶ PROCESSING ──▶ CONFIRMED        （责任方全部确认）
--        │            │
--        │            ├──▶ REJECTED               （责任方明确拒绝）
--        │            └──▶ PENDING_VERIFICATION   （等待平台 / 仓库核验）
--        ├──▶ REJECTED
--        └──▶ PENDING_VERIFICATION
--
-- REJECTED / CONFIRMED / PENDING_VERIFICATION 是终态（PENDING_VERIFICATION 可由
-- 仓库回执或平台回写确认后转 CONFIRMED，因此它不是死局但**不能一步跳到已取消**）。
-- 「一步到位直接置为已取消」在本表不成立：CONFIRMED 的两个前置条件由第二节
-- 的 guard_order_cancellation_confirm 在 DB 层核对。
--
-- 变更请求（order_change_request）用同一套五态，区别只在于它没有「仓库释放」
-- 这一环（改地址不释放占用，改数量减少才需要，而那属于取消的职责范围）。
--
-- ---------------------------------------------------------------------------
-- 六、十张表分工
-- ---------------------------------------------------------------------------
--   order_platform_change_rule            平台可变更规则（租户维护，含生效窗口）
--   order_fulfillment_line_progress       履约/已发货数量投影（9.3.3.1 事件写入）
--   order_change_request                  变更请求头（地址修改 / 数量增减）
--   order_change_request_line             数量调整明细（原量 / 申请量 / 差额 / 金额）
--   order_change_address_field            地址变更的字段级前后值
--   order_cancellation_request            取消请求头（五态状态机）
--   order_cancellation_line               取消明细（**终态数量确认后才落**）
--   order_warehouse_hold_task             仓库停止作业 + 释放占用（7.2.1.3）
--   order_platform_writeback_task         平台回写任务（2.5.1.1）
--   order_change_event                    事件时间线（append-only）
--
-- **刻意没有建的两张**（照 9.1.1.2 / 8.3.1.2 已立的规矩：不建占位表）：
--   * 售后单：11 售后一条未开发。已发货数量被拒时，响应里逐行给出
--     ownerModule=11 与原因，**不在 trade 侧建一张没人写的售后表**。
--   * 退款单：12 财务未开发。取消单只记 refund_amount 这个**金额**
--     （BigDecimal numeric(19,4)），退款动作本身不在本模块落库。
--
-- 金额一律 BigDecimal / numeric，禁浮点（AGENTS.md）。
-- 时间一律 timestamptz，Java 侧用 OffsetDateTime + `?::timestamptz` 绑定。

BEGIN;

-- ===========================================================================
-- 1. 平台可变更规则
-- ===========================================================================
CREATE TABLE order_platform_change_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  -- 店铺维度：'*' = 该平台全部店铺共用一条规则。平台政策常按店铺谈（品牌备案、
  -- 店铺等级不同可改窗口不同），所以这一维不能省。
  shop_code varchar(64) NOT NULL DEFAULT '*',
  change_kind text NOT NULL CHECK(change_kind IN (
    'ADDRESS_MODIFY','QUANTITY_INCREASE','QUANTITY_DECREASE','CANCEL')),
  -- 是否允许。false 是**商务结论**（平台不允许 / 平台已关掉该能力），
  -- 与「查不到规则」不同：查不到规则见第五节 effective 判定，两者必须分开。
  allowed boolean NOT NULL DEFAULT false,
  -- 距下单多少小时内仍可改。NULL = 不限窗口。
  -- 是数据不是常量：各平台可改窗口本来就不同，写成代码里的 if 就是硬编码商务事实。
  deadline_hours numeric(9,2),
  -- 单次可减数量上限。NULL = 不限。超限的申请在服务层被拒并报出上限值。
  max_decrease_qty numeric(19,6),
  -- 是否需要平台侧确认才生效：true 时确认后仍要等 writeback 任务被平台接受
  -- 才能从 PENDING_VERIFICATION 转 CONFIRMED。
  requires_platform_ack boolean NOT NULL DEFAULT true,
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,
  notes varchar(400) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一 (平台, 店铺, 变更类型) 只允许一条规则：两条都"允许"时
  -- 选哪条取决于查询顺序，界面与审计会对不上。
  UNIQUE(tenant_id,platform_code,shop_code,change_kind),
  CHECK(btrim(platform_code)<>''),
  CHECK(shop_code='*' OR btrim(shop_code)<>''),
  CHECK(deadline_hours IS NULL OR deadline_hours>=0),
  CHECK(max_decrease_qty IS NULL OR max_decrease_qty>0),
  CHECK(effective_to IS NULL OR effective_to>effective_from),
  CHECK(updated_at>=created_at)
);

-- ===========================================================================
-- 2. 履约 / 已发货数量投影（本服务只读投影，来源如实标注）
-- ===========================================================================
CREATE TABLE order_fulfillment_line_progress (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  -- 订单行总量快照：建投影那一刻从 sales_order_line 抄下来的原量。
  -- **只作留痕与闸门基数**，不与 sales_order_line 建 FK —— 那张表由 9.1.2.2 拥有，
  -- 建了跨模块 FK 就等于让两条事实表互相锁死（V99 自己的七张子表也只在
  -- tenant 维度互相引用，没有引用外部模块的表）。订单不存在时由服务层拒绝写入。
  ordered_qty numeric(19,6) NOT NULL CHECK(ordered_qty > 0),
  -- 仓库预留 / 已拣 / 已发。三个口径分列而不是一个 status：
  -- 「已拣 3 件、已发 1 件」是真实存在的中间态，压成一个状态就会丢信息，
  -- 而 8.3.1.2 已经因为同一类问题立过规矩（逐行 pickedQuantity 恒 null 而不是 0）。
  reserved_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(reserved_qty >= 0),
  picking_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(picking_qty >= 0),
  shipped_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(shipped_qty >= 0),
  shipped_at timestamptz,
  progress_source varchar(32) NOT NULL CHECK(progress_source IN (
    'MODULE_9_3_3_1','EVENT_8_OUTBOUND','TENANT_MAINTAINED')),
  -- 观测时刻：这批数是什么时候从上游看到的。
  -- 没有它，「已发 2 件」在几分钟后到达时无法判断它相对取消申请的新旧。
  observed_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id,order_line_id),
  CHECK(reserved_qty+picking_qty+shipped_qty <= ordered_qty),
  CHECK(shipped_at IS NULL OR shipped_qty > 0),
  CHECK(updated_at>=created_at)
);

-- ===========================================================================
-- 3. 变更请求（地址修改 / 数量调整）
-- ===========================================================================
CREATE TABLE order_change_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL,
  change_kind text NOT NULL CHECK(change_kind IN (
    'ADDRESS_MODIFY','QUANTITY_INCREASE','QUANTITY_DECREASE')),
  status text NOT NULL DEFAULT 'REQUESTED' CHECK(status IN (
    'REQUESTED','PROCESSING','CONFIRMED','REJECTED','PENDING_VERIFICATION')),
  reason varchar(500) NOT NULL DEFAULT '',
  currency varchar(3) NOT NULL CHECK(currency=upper(currency)),
  -- 数量减少的退款预估金额。**只记金额，不落退款单**（12 财务未开发）。
  -- 用 numeric / BigDecimal，禁浮点：退款金额差一分钱就是对不上账。
  refund_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(refund_amount >= 0),
  -- 命中的平台可变更规则。NULL = 本次不适用平台规则（手工单没有平台）。
  -- 建 FK 而不是把允许与否抄成布尔值：规则改了要能查到「当时按哪条判的」。
  platform_rule_id uuid,
  requested_by_membership_id uuid NOT NULL,
  requested_at timestamptz NOT NULL,
  decided_by_membership_id uuid,
  decided_at timestamptz,
  rejection_reason varchar(500) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,platform_rule_id)
    REFERENCES order_platform_change_rule(tenant_id,id),
  CHECK(rejection_reason='' OR status='REJECTED'),
  CHECK((decided_at IS NULL)=(decided_by_membership_id IS NULL)),
  -- 只有终态才带决策人：REQUESTED / PROCESSING 阶段的 decided_at 必须为空。
  CHECK(decided_at IS NULL OR status IN ('CONFIRMED','REJECTED','PENDING_VERIFICATION')),
  CHECK(updated_at>=created_at)
);

-- 数量调整明细
CREATE TABLE order_change_request_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  change_request_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  -- 申请时的原量与申请量都要留痕：原量事后可能被别的申请改过，
  -- 只存差额的话「为什么减了 2 件」永远答不出来。
  original_qty numeric(19,6) NOT NULL CHECK(original_qty > 0),
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty > 0),
  -- 差额 = requested − original。CHECK 保证两者与差额永远自洽，
  -- 避免「界面上写减 2、库里差值是 -1」这种对不上的行。
  delta_qty numeric(19,6) NOT NULL,
  unit_price numeric(19,4) NOT NULL CHECK(unit_price >= 0),
  line_amount numeric(19,4) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,change_request_id,order_line_id),
  FOREIGN KEY(tenant_id,change_request_id)
    REFERENCES order_change_request(tenant_id,id) ON DELETE CASCADE,
  CHECK(delta_qty = requested_qty - original_qty),
  CHECK(line_amount = round(unit_price * delta_qty,4))
);

-- 地址变更：字段级前后值
CREATE TABLE order_change_address_field (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  change_request_id uuid NOT NULL,
  address_role text NOT NULL CHECK(address_role IN ('SHIPPING','BILLING')),
  -- 字段枚举：收件人 / 电话 / 邮箱 / 公司 / 省 / 市 / 区 / 邮编 / 税号 / 备注。
  field_code text NOT NULL CHECK(field_code IN (
    'RECIPIENT_NAME','RECIPIENT_PHONE','RECIPIENT_EMAIL','COMPANY_NAME',
    'PROVINCE','CITY','DISTRICT','POSTCODE','TAX_ID','NOTE')),
  before_value varchar(300) NOT NULL DEFAULT '',
  after_value varchar(300) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,change_request_id,address_role,field_code),
  FOREIGN KEY(tenant_id,change_request_id)
    REFERENCES order_change_request(tenant_id,id) ON DELETE CASCADE,
  -- 「改成和原来一样」不是变更：留下来只会让变更历史里出现空条目。
  CHECK(before_value IS DISTINCT FROM after_value)
);

-- ===========================================================================
-- 4. 取消请求
-- ===========================================================================
CREATE TABLE order_cancellation_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL,
  cancel_scope text NOT NULL CHECK(cancel_scope IN ('FULL_ORDER','PARTIAL_LINES')),
  status text NOT NULL DEFAULT 'REQUESTED' CHECK(status IN (
    'REQUESTED','PROCESSING','CONFIRMED','REJECTED','PENDING_VERIFICATION')),
  reason varchar(500) NOT NULL,
  currency varchar(3) NOT NULL CHECK(currency=upper(currency)),
  -- 取消涉及的退款金额合计。BigDecimal / numeric(19,4)，禁浮点。
  refund_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(refund_amount >= 0),
  platform_rule_id uuid,
  requested_by_membership_id uuid NOT NULL,
  requested_at timestamptz NOT NULL,
  -- 申请时是否已核到已发货数量。为 false 时取消照样能提交，
  -- 但界面上「未发货数量」那一栏显示的是「未接入」而不是 0（见第二节）。
  shipped_quantity_verified boolean NOT NULL DEFAULT false,
  decided_by_membership_id uuid,
  decided_at timestamptz,
  rejection_reason varchar(500) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,platform_rule_id)
    REFERENCES order_platform_change_rule(tenant_id,id),
  CHECK(btrim(reason)<>''),
  CHECK(rejection_reason='' OR status='REJECTED'),
  CHECK((decided_at IS NULL)=(decided_by_membership_id IS NULL)),
  CHECK(decided_at IS NULL OR status IN ('CONFIRMED','REJECTED','PENDING_VERIFICATION')),
  CHECK(updated_at>=created_at)
);

-- 取消明细：cancel_qty 是申请量，cancelled_qty 是**责任方确认后**的终态量
CREATE TABLE order_cancellation_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  cancellation_request_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  ordered_qty numeric(19,6) NOT NULL CHECK(ordered_qty > 0),
  -- 申请时刻的已发货量快照。它是**申请当时的实况**，
  -- 与 order_fulfillment_line_progress 的区别：投影会随事件更新，这一条不更新。
  shipped_qty_at_request numeric(19,6) NOT NULL DEFAULT 0 CHECK(shipped_qty_at_request >= 0),
  cancel_qty numeric(19,6) NOT NULL CHECK(cancel_qty > 0),
  -- 终态取消量：CONFIRMED 之前**必须为 0**（guard_order_cancellation_line_terminal）。
  cancelled_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(cancelled_qty >= 0),
  unit_price numeric(19,4) NOT NULL CHECK(unit_price >= 0),
  refund_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(refund_amount >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,cancellation_request_id,order_line_id),
  FOREIGN KEY(tenant_id,cancellation_request_id)
    REFERENCES order_cancellation_request(tenant_id,id) ON DELETE CASCADE,
  -- 申请量不得超过「原量 − 已发量」：蓝图硬规则的第一道静态形态
  -- （真正的动态形态在 guard_order_cancellation_no_shipped，它对着投影核对）。
  CHECK(cancel_qty <= ordered_qty - shipped_qty_at_request),
  CHECK(cancelled_qty <= cancel_qty),
  CHECK(refund_amount = round(unit_price * cancelled_qty,4))
);

-- ===========================================================================
-- 5. 仓库停止作业与释放占用（下游 7.2.1.3，warehouse 服务）
-- ===========================================================================
CREATE TABLE order_warehouse_hold_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  cancellation_request_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  -- 预占引用：跨服务**只存对方的业务键**，不存对方的主键 uuid（V101 已确立同一纪律：
  -- 跨服务不猜对方 id 形态）。7.2.1.3 的释放入口按这个引用定位。
  reservation_ref varchar(64) NOT NULL DEFAULT '',
  task_stage text NOT NULL CHECK(task_stage IN ('STOP_WORK','RELEASE_OCCUPANCY')),
  -- 两个阶段都要走：先 STOP_WORK（仓库确认不再作业），再 RELEASE_OCCUPANCY（释放占用）。
  -- 蓝图原话是「先确认仓库停止作业**并**释放占用」，所以 STOP_WORK 单独 COMPLETED
  -- **不足以**让取消单确认 —— guard_order_cancellation_confirm 要求两条都 COMPLETED。
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN (
    'PENDING','ACKNOWLEDGED','COMPLETED','FAILED','REJECTED')),
  target_api varchar(200) NOT NULL,
  owner_module varchar(16) NOT NULL DEFAULT '7.2.1.3',
  target_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(target_quantity >= 0),
  -- 实际释放量。与 target_quantity 分开：仓库释放 2 件申请 3 件是真实发生过的事，
  -- 抹平成一样就丢了「为什么少释放了 1 件」的线索。
  completed_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(completed_quantity >= 0),
  result_note varchar(500) NOT NULL DEFAULT '',
  failure_reason varchar(500) NOT NULL DEFAULT '',
  acknowledged_at timestamptz,
  completed_at timestamptz,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,cancellation_request_id,task_stage,warehouse_ref),
  FOREIGN KEY(tenant_id,cancellation_request_id)
    REFERENCES order_cancellation_request(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(warehouse_ref)<>''),
  CHECK(btrim(target_api)<>''),
  CHECK((completed_at IS NULL) OR status='COMPLETED'),
  CHECK(completed_quantity=0 OR status IN ('COMPLETED','ACKNOWLEDGED')),
  CHECK((failure_reason='') OR status IN ('FAILED','REJECTED')),
  CHECK(updated_at>=created_at)
);

-- ===========================================================================
-- 6. 平台回写任务（下游 2.5.1.1，channel 服务）
-- ===========================================================================
CREATE TABLE order_platform_writeback_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 恰好一个父：回写任务要么服务于变更，要么服务于取消。
  change_request_id uuid,
  cancellation_request_id uuid,
  platform_code varchar(32) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  -- 平台侧订单号（external_order_no）。平台回写按它定位，
  -- 跨服务只存业务键不猜 uuid（与 order_warehouse_hold_task 同一纪律）。
  platform_order_ref varchar(128) NOT NULL DEFAULT '',
  change_kind text NOT NULL CHECK(change_kind IN (
    'ADDRESS_MODIFY','QUANTITY_INCREASE','QUANTITY_DECREASE','CANCEL')),
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN (
    'PENDING','SUBMITTED','CONFIRMED','REJECTED','PENDING_VERIFICATION')),
  owner_module varchar(16) NOT NULL DEFAULT '2.5.1.1',
  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  last_error varchar(500) NOT NULL DEFAULT '',
  submitted_at timestamptz,
  settled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,change_request_id)
    REFERENCES order_change_request(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,cancellation_request_id)
    REFERENCES order_cancellation_request(tenant_id,id) ON DELETE CASCADE,
  -- 恰好一个父：两个都空会让任务无从归属，两个都填会出现双重写入所有者。
  CHECK((change_request_id IS NULL) <> (cancellation_request_id IS NULL)),
  CHECK(change_request_id IS NOT NULL OR platform_code<>''),
  CHECK((submitted_at IS NULL) OR status<>'PENDING'),
  CHECK((settled_at IS NULL) OR status IN ('CONFIRMED','REJECTED','PENDING_VERIFICATION'))
);

-- ===========================================================================
-- 7. 事件时间线（append-only）
-- ===========================================================================
CREATE TABLE order_change_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  change_request_id uuid,
  cancellation_request_id uuid,
  event_type text NOT NULL CHECK(event_type IN (
    'REQUESTED','PROCESSING','CONFIRMED','REJECTED','PENDING_VERIFICATION',
    'WAREHOUSE_TASK_CREATED','WAREHOUSE_TASK_ACKNOWLEDGED','WAREHOUSE_TASK_COMPLETED',
    'WAREHOUSE_TASK_FAILED','WRITEBACK_CREATED','WRITEBACK_SUBMITTED',
    'WRITEBACK_CONFIRMED','WRITEBACK_REJECTED','PROGRESS_RECORDED','RULE_MATCHED')),
  from_status text NOT NULL DEFAULT '',
  to_status text NOT NULL DEFAULT '',
  detail varchar(500) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,change_request_id)
    REFERENCES order_change_request(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,cancellation_request_id)
    REFERENCES order_cancellation_request(tenant_id,id) ON DELETE CASCADE,
  CHECK((change_request_id IS NULL) <> (cancellation_request_id IS NULL)),
  CHECK(btrim(event_type)<>'')
);

-- ===========================================================================
-- 8. 索引（按真实查询形状建，前导列一律 tenant_id —— RLS 谓词在租户列上）
-- ===========================================================================
-- 变更 / 取消请求列表：按订单倒序（订单详情页的操作入口从这里进）。
CREATE INDEX order_change_request_by_order
  ON order_change_request(tenant_id,order_id,created_at DESC);
CREATE INDEX order_change_request_by_status
  ON order_change_request(tenant_id,status,created_at DESC);
CREATE INDEX order_cancellation_request_by_order
  ON order_cancellation_request(tenant_id,order_id,created_at DESC);
CREATE INDEX order_cancellation_request_by_status
  ON order_cancellation_request(tenant_id,status,created_at DESC);
-- 规则表按平台 + 店铺查（判「平台允不允许」时的主查询形状）。
CREATE INDEX order_platform_change_rule_by_platform
  ON order_platform_change_rule(tenant_id,platform_code,shop_code,change_kind);
-- 投影按订单读（取消申请时一次性取全部行的已发量）。
CREATE INDEX order_fulfillment_line_progress_by_order
  ON order_fulfillment_line_progress(tenant_id,order_id);
-- 仓库任务按取消单读（确认前的校验与界面列表都按这个形状）。
CREATE INDEX order_warehouse_hold_task_by_cancellation
  ON order_warehouse_hold_task(tenant_id,cancellation_request_id,task_stage);
-- 回写任务按父单读。
CREATE INDEX order_platform_writeback_task_by_change
  ON order_platform_writeback_task(tenant_id,change_request_id);
CREATE INDEX order_platform_writeback_task_by_cancellation
  ON order_platform_writeback_task(tenant_id,cancellation_request_id);
-- 时间线按 (父单, 发生时刻, id) 升序：id 兜底是必要的，
-- 同一毫秒发生的事件光看 occurred_at 顺序不确定，界面上会随机跳行。
CREATE INDEX order_change_event_by_change
  ON order_change_event(tenant_id,change_request_id,occurred_at,id);
CREATE INDEX order_change_event_by_cancellation
  ON order_change_event(tenant_id,cancellation_request_id,occurred_at,id);
-- 服务层有一处「某订单行是否已被某单取消」的核对（数量调整的净量口径），按行查。
CREATE INDEX order_cancellation_line_by_line
  ON order_cancellation_line(tenant_id,order_line_id);

-- ===========================================================================
-- 9. 四条 DB 不变量（写入路径上生效，绕过服务层写 SQL 同样被挡）
-- ===========================================================================

-- 9.1 取消明细的终态量只能在父单 CONFIRMED 之后落。
--
--     唯一例外是 SECURITY DEFINER 函数 confirm_order_cancellation 打开的
--     transaction-local 窗口（与 V92 / V116 同一解法）：「写终态量」与「翻父单状态」
--     必须在**同一个事务**里完成 —— 先翻状态守卫 9.2 会因终态量还没落而拒绝，
--     先落终态量本守卫又会拒绝，两者单独都走不通。开窗只放行这一个事务，
--     事务外任何 UPDATE 都被挡住。
CREATE FUNCTION guard_order_cancellation_line_terminal() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE parent_status text;
BEGIN
  IF current_setting('app.cancellation_confirm', true) = 'on' THEN RETURN NEW; END IF;
  SELECT status INTO parent_status FROM order_cancellation_request
    WHERE tenant_id = NEW.tenant_id AND id = NEW.cancellation_request_id;
  IF parent_status IS NULL THEN RETURN NEW; END IF;
  IF parent_status <> 'CONFIRMED' AND NEW.cancelled_qty <> 0 THEN
    RAISE EXCEPTION '取消单尚未被责任方确认（当前 %），取消终态数量必须为 0 而不是 %：终态是「责任方确认了确实取消了这 % 件」的结论，申请阶段先填上去会让界面显示「已取消」而仓库一个货都没放、平台一个单都没退',
      parent_status, NEW.cancelled_qty, NEW.cancelled_qty
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_cancellation_line_terminal
  BEFORE INSERT OR UPDATE ON order_cancellation_line
  FOR EACH ROW EXECUTE FUNCTION guard_order_cancellation_line_terminal();

-- 9.2 取消单确认时：每条仓库任务都 COMPLETED，且每条明细的终态量都等于申请量。
CREATE FUNCTION guard_order_cancellation_confirm() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_pending int; v_missing int;
BEGIN
  IF NEW.status <> 'CONFIRMED' OR (TG_OP = 'UPDATE' AND OLD.status = 'CONFIRMED') THEN
    RETURN NEW;
  END IF;
  SELECT count(*) INTO v_pending FROM order_warehouse_hold_task
    WHERE tenant_id = NEW.tenant_id AND cancellation_request_id = NEW.id
      AND status <> 'COMPLETED';
  IF v_pending > 0 THEN
    RAISE EXCEPTION '取消单「%」还有 % 条仓库停止作业/释放任务没有 COMPLETED，不能记为已确认：蓝图要求「先确认仓库停止作业并释放占用」，先置终态再补释放回执等于让库存占用在取消之后还挂在订单上',
      NEW.order_no, v_pending
      USING ERRCODE='23514';
  END IF;
  SELECT count(*) INTO v_missing FROM order_cancellation_line
    WHERE tenant_id = NEW.tenant_id AND cancellation_request_id = NEW.id
      AND cancelled_qty <> cancel_qty;
  IF v_missing > 0 THEN
    RAISE EXCEPTION '取消单「%」有 % 条明细的终态数量还不等于申请数量，不能记为已确认：终态是逐条数量的结论，少记的差额事后没人说得清是货没放还是漏登',
      NEW.order_no, v_missing
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_cancellation_confirm
  BEFORE UPDATE OF status ON order_cancellation_request
  FOR EACH ROW EXECUTE FUNCTION guard_order_cancellation_confirm();

-- 9.3 已发货数量不可取消：取消量不得超过「原量 − 投影里的已发量」。
--
--     直接读 NEW.order_line_id 而不回表：本行的订单行号就在参数里，
--     回表在 BEFORE INSERT 上读不到还没落库的这一行（照抄这里会静默取到 NULL）。
CREATE FUNCTION guard_order_cancellation_no_shipped() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_shipped numeric; v_line uuid := NEW.order_line_id;
BEGIN
  SELECT p.shipped_qty INTO v_shipped FROM order_fulfillment_line_progress p
    WHERE p.tenant_id = NEW.tenant_id AND p.order_line_id = v_line;
  v_shipped := COALESCE(v_shipped, 0);
  IF NEW.shipped_qty_at_request < v_shipped THEN
    RAISE EXCEPTION '订单行 % 在本服务记录的已发货数量是 %，比申请时抄下的 % 还多：已发货的数量不能取消，只能转售后（ownerModule 11）。这是蓝图加粗的硬规则 —— 已发出去的货退回来要走退货入库与质检，不能靠取消单把它变成"没发过"',
      v_line, v_shipped, NEW.shipped_qty_at_request
      USING ERRCODE='23514';
  END IF;
  IF NEW.cancel_qty > NEW.ordered_qty - v_shipped THEN
    RAISE EXCEPTION '订单行 % 申请取消 % 件，但原量 % 中已有 % 件发货，最多只能取消 % 件：已发货数量不可取消，只能转售后（ownerModule 11）',
      v_line, NEW.cancel_qty, NEW.ordered_qty, v_shipped, NEW.ordered_qty - v_shipped
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_cancellation_no_shipped
  BEFORE INSERT OR UPDATE ON order_cancellation_line
  FOR EACH ROW EXECUTE FUNCTION guard_order_cancellation_no_shipped();

-- 9.4 同一件货不能既已发货又已取消：投影写入时对照已确认的取消终态。
CREATE FUNCTION guard_order_fulfillment_progress_vs_cancel() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_cancelled numeric;
BEGIN
  -- 回表读当前行，不用 NEW 的可变列（照 V85 / V90 / V92 同一解法）。
  SELECT COALESCE(sum(l.cancelled_qty),0) INTO v_cancelled
    FROM order_cancellation_line l
    JOIN order_cancellation_request c
      ON c.tenant_id = l.tenant_id AND c.id = l.cancellation_request_id
    WHERE l.tenant_id = NEW.tenant_id
      AND l.order_line_id = NEW.order_line_id
      AND c.status = 'CONFIRMED';
  IF NEW.shipped_qty + v_cancelled > NEW.ordered_qty THEN
    RAISE EXCEPTION '订单行 % 已有 % 件被确认取消，现在又要记 % 件发货，超过原量 %：同一件货不能既已发货又已取消。若这批发货事件确实在取消确认之前发生，那它比取消申请更早，应该改的是投影而不是让两个终态并存',
      NEW.order_line_id, v_cancelled, NEW.shipped_qty, NEW.ordered_qty
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_fulfillment_progress_vs_cancel
  BEFORE INSERT OR UPDATE ON order_fulfillment_line_progress
  FOR EACH ROW EXECUTE FUNCTION guard_order_fulfillment_progress_vs_cancel();

-- 9.5 事件时间线 append-only：REVOKE 之外再加触发器（V99 第 8.4 节同一解法）。
CREATE FUNCTION guard_order_change_event_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的时间线（%）：事件流是「谁在什么时候把这张单推到哪一步」的举证凭据，改它等于让事后复盘可以按需要重写',
    TG_TABLE_NAME, TG_OP
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_change_event_append_only
  BEFORE UPDATE OR DELETE ON order_change_event
  FOR EACH ROW EXECUTE FUNCTION guard_order_change_event_append_only();

-- 9.6 取消确认的窄函数（SECURITY DEFINER）
--
--     「写终态量」与「翻父单状态」必须在同一事务里完成（见 9.1 的窗口说明），
--     所以确认走这一个窄函数，而不是让服务层先 UPDATE 明细再 UPDATE 头
--     —— 后者两步之间任何一步单独成功都会留下「终态量已落而状态没翻」的脏数据。
--
--     **租户安全**：本函数以定义者（迁移用户）权限执行，会绕过 RLS，
--     所以每条语句都显式带 tenant_id，并且**先核对调用方事务当前绑定的租户**
--     与参数一致，不一致直接拒绝 —— 否则「拿着 A 的会话传 B 的租户号」
--     就能改到别的租户去。V116 的 advance_warehouse_allocation_status 没有这一层
--     核对，本函数不沿用那个缺口。
--     事件行 id 由调用方传入（服务层 java.util.UUID.randomUUID()）：
--     本服务的既有迁移里没有任何一处依赖 SQL 侧 UUID 生成函数
--     （V99 / V116 的 id 全部由服务层生成），这里不为了一个函数引入新依赖。
CREATE FUNCTION confirm_order_cancellation(
  p_tenant_id uuid, p_cancellation_id uuid, p_actor uuid, p_occurred_at timestamptz,
  p_event_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record;
BEGIN
  IF current_setting('app.current_tenant', true) IS DISTINCT FROM p_tenant_id::text THEN
    RAISE EXCEPTION '确认取消时租户上下文与目标取消单所属租户不一致：拒绝跨租户写入'
      USING ERRCODE='42501';
  END IF;
  SELECT id, order_no, status INTO head FROM order_cancellation_request
    WHERE tenant_id = p_tenant_id AND id = p_cancellation_id;
  IF head IS NULL THEN
    RAISE EXCEPTION '取消单 % 不存在或不属于本租户', p_cancellation_id USING ERRCODE='23503';
  END IF;
  IF head.status NOT IN ('REQUESTED','PROCESSING','PENDING_VERIFICATION') THEN
    RAISE EXCEPTION '取消单当前状态「%」不能确认', head.status USING ERRCODE='23514';
  END IF;
  -- 开窗 → 落终态量 → 翻状态 → 记事件。守卫 9.2 仍会在翻状态的那一刻
  -- 逐条核对「仓库任务是否全部 COMPLETED」「终态量是否逐条齐全」。
  PERFORM set_config('app.cancellation_confirm', 'on', true);
  UPDATE order_cancellation_line
     SET cancelled_qty = cancel_qty,
         -- 退款金额在**确认这一刻**才结算：申请阶段的金额只是预估，
         -- 与 order_cancellation_line 的 CHECK(refund_amount=round(unit_price*cancelled_qty,4))
         -- 天然对齐，所以这一列不会与终态数量出现两个答案。
         refund_amount = round(unit_price * cancel_qty, 4)
   WHERE tenant_id = p_tenant_id AND cancellation_request_id = p_cancellation_id;
  UPDATE order_cancellation_request
     SET status = 'CONFIRMED',
         refund_amount = (SELECT COALESCE(sum(refund_amount),0)
                            FROM order_cancellation_line
                           WHERE tenant_id = p_tenant_id
                             AND cancellation_request_id = p_cancellation_id),
         decided_by_membership_id = p_actor,
         decided_at = p_occurred_at
   WHERE tenant_id = p_tenant_id AND id = p_cancellation_id;
  PERFORM set_config('app.cancellation_confirm', 'off', true);
  INSERT INTO order_change_event(id,tenant_id,cancellation_request_id,event_type,from_status,
    to_status,detail,actor_membership_id,occurred_at)
    VALUES (p_event_id,p_tenant_id,p_cancellation_id,'CONFIRMED',head.status,'CONFIRMED',
      '仓库停止作业与释放占用已全部确认，取消终态数量落库',p_actor,p_occurred_at);
END $$;

-- ===========================================================================
-- 10. 多租户：RLS + 授权
-- ===========================================================================
DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_platform_change_rule',
    'order_fulfillment_line_progress',
    'order_change_request',
    'order_change_request_line',
    'order_change_address_field',
    'order_cancellation_request',
    'order_cancellation_line',
    'order_warehouse_hold_task',
    'order_platform_writeback_task',
    'order_change_event'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_platform_change_rule,
  order_fulfillment_line_progress,
  order_change_request,
  order_change_request_line,
  order_change_address_field,
  order_cancellation_request,
  order_cancellation_line,
  order_warehouse_hold_task,
  order_platform_writeback_task,
  order_change_event TO trade_app;
-- 授权在前、收回在后：把 REVOKE 写在 GRANT **之前**会被上面这条全量覆盖回去，
-- append-only 于是只剩注释（V92 第 9 节踩过同一个坑）。
REVOKE UPDATE,DELETE ON order_change_event FROM trade_app;
-- 状态推进函数是 SECURITY DEFINER，必须显式授权。
GRANT EXECUTE ON FUNCTION confirm_order_cancellation(uuid,uuid,uuid,timestamptz,uuid) TO trade_app;

COMMIT;

-- 本迁移**零 ALTER** 任何既有表，被本迁移约束到的既有模块 = 无，
-- 跨车道 DDL 回归面 = 0。
