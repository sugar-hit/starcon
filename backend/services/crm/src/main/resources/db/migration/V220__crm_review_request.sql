-- 15.2.2.1 Request a Review（索评）：按规则向**已签收订单的买家**发起索评，
-- 控制频次避免打扰，支持按店铺 / 时间 / 物流状态批量执行。
--
-- 蓝图 15 卷 15.2.2.1 的原话：
--   「职责：按规则向已签收订单的买家发起索评（平台允许范围内），控制频次避免打扰，
--     支持按店铺/时间/物流状态批量执行。
--     输入：索评规则（时点、范围、频次上限）；已签收订单集合。
--     输出：索评任务 + 发送记录 + 评价回收统计。
--     关系：上游 = 10.3.2.2 签收；下游 = 15.2.1.1 评价。
--     **索评频次必须受限**，过度索评会被买家投诉甚至平台处罚。」
--
-- ============================================================ 零、本文件与既有模块的边界
--
-- 本文件是 **crm 数据库的第二个迁移**（第一个是 15.4.2.1 知识库 V99）。
-- 本模块与 15.4.2.1 知识库**不共用任何一张表**：
--   * 知识库回答「这个格子此刻该给买家答哪一条」（按店铺 / 类目 / 语言组织）；
--   * 索评回答「这一位买家、这一张已签收的单子，要不要向他发起一次索评」。
-- 两者一个管**内容**，一个管**触达**，事实表与写入所有者都不同。
--
-- 与 15.2.2.2「索评规则」的边界：
--   15.2.2.2（**未开发**）负责**可配置的策略**：按店铺 / 按物流方式设不同延后时间、
--   避开节假日、A/B 话术，并输出效果对比。
--   本模块负责**一次索评请求的判定与留痕**：候选集来自上游签收事实，频次受限，
--   请求 / 发送记录 / 回收统计落本库。
--   ⇒ **本文件不建「索评策略配置表」**（那是 15.2.2.2 的事）。
--   本文件里的「规则」是**一次请求自带的规则快照**（时点 / 冷却窗口 / 频次上限），
--   以及一份**租户级频次硬上限**（crm_review_frequency_policy）。
--   后者不是 15.2.2.2 的策略配置：它是蓝图加粗句「**索评频次必须受限**」的**兜底闸门**，
--   作用是让「请求自带的规则」只能比租户硬上限**更严**，不能更松。
--
-- ============================================================ 一、跨服务纪律（零跨库 SQL / FK / 视图）
--
-- 上游 10.3.2.2 揽收与签收确认**本批刚交付**（**channel** 服务，V217，
-- 表 logistics_pickup_signoff_case / logistics_pickup_signoff_notification）。
-- 它与本模块**不同服务、不同数据库** ⇒ AGENTS.md 模块边界纪律：
-- **禁止跨库 SQL、跨库 FK、共享视图**，只走授权 HTTP API。
--
-- 本库因此**只存上游的逻辑引用 + 快照**：
--   upstream_case_id / upstream_case_no / upstream_waybill_no /
--   upstream_carrier_code / upstream_source_ref / delivered_at
-- —— 与 15.4.2.1 知识库存 shop_id + shop_name 快照同一纪律（V99 第六节）：
--   快照的作用是**列表页不必回源就能显示**，它**不构成引用完整性**，
--   真正的完整性归各服务自己的域。
--
-- ⚠️ 上游确认单**不带两个维度**，本文件如实处理，**不编**：
--   * **店铺**：确认单上只有物流渠道（channel_id），没有店铺；
--     索评请求上的 shop_id / shop_name 是**本次索评活动的目标店铺**，
--     由运营从 channel 的真实店铺清单里选；候选单据归属哪家店铺**不可从上游核实**
--     （capabilities 里 shop_attribution 维度显式标 available=false）。
--   * **买家**：本仓**没有买家档案**（15.3.1.1 客户档案未交付），
--     buyer_ref 由运营按店铺提供，**只作频次去重键**（capabilities 里 buyer_identity
--     维度显式标 available=false）。这与 trade V135 的同款纪律一致：
--     「买家联系方式归 15 / 客户档案所有」。
--
-- 下游 15.2.1.1 评价列表 / 15.2.2.2 索评规则**均未交付** ⇒
-- **只在本库落「评价回收桶」**（收到的评价原文 + 关联到索评请求），
-- **不得**假装已交付评价管理模块（capabilities 里 review_sync 维度标 available=false）。
--
-- ============================================================ 二、🔴 加粗句「索评频次必须受限」的落地
--
-- 「频次受限」必须落成**可执行的事实**（DB 约束 + 服务端校验），
-- 不是界面上的一行提示。本文件落成**三层**：
--
--   第 1 层 · 租户级硬上限（crm_review_frequency_policy）：
--     每买家上限（窗口内）、每订单上限、冷却窗口下限、时点下限、单批上限。
--     每个值都有 **CHECK 硬天花板**（例如每买家上限最多 10 次/窗口），
--     所以「把上限调大」这条路本身也被 DB 挡死 —— 这是加粗句的**兜底**。
--
--   第 2 层 · DB 触发器（guard_crm_review_request_frequency）：
--     写入索评请求时**在数据库层**判定三件事，任一不满足即抛异常拒写：
--       (a) 每订单上限 —— 同一 sales_order_no 的在途索评数；
--       (b) 每买家上限 —— 同一 buyer_ref 在统计窗口内的索评数；
--       (c) 冷却窗口 —— 同一 buyer_ref 的上一次索评距今是否已过冷却小时数。
--     并额外强制：请求自带的规则快照**只能比租户硬上限更严**（不得更松）。
--
--   第 3 层 · 服务端预检 + 留痕：
--     服务层在写库前用**同样的口径**预检一遍，给出可读原因；
--     **超限的请求不丢弃，而是以 status='REJECTED' + reject_reason/reject_code 落库**，
--     让「谁在什么时候想向哪位买家索评、被什么规则挡住了」可查。
--     REJECTED / CANCELLED **不占用额度**（只有 SCHEDULED / SENT 占用）。
--
-- ⚠️ 为什么触发器用**同库回表**而不是**数据库外键**：
--   本批 batch-37 有一条车道在迁移里给既有表建了 FOREIGN KEY，结果打破了同服务
--   既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）。本文件**不给任何既有表建外键**，本文件自己新建的四张表之间
--   也**一律用触发器做引用校验**（guard_crm_review_send_record_target /
--   guard_crm_review_recovery_target），与同批 V219__return_shipback_to_origin.sql
--   的 guard_return_shipback_line_order 同一手法。
--
-- ============================================================ 三、四张表：任务 / 发送 / 回收 / 频次策略
--
--   crm_review_frequency_policy · 租户级频次硬上限（一租户一行）
--   crm_review_request         · 索评任务（= 一次索评请求；REJECTED 也是它的一种）
--   crm_review_send_record     · 发送记录（每次触达尝试一行）
--   crm_review_recovery        · 评价回收桶（收到的评价原文 + 关联到索评请求）
--
-- 为什么「任务」与「发送记录」分两张表：
--   一次索评请求可能**一次都没发出去**（被频次规则挡住 / 到点未发），
--   也可能**发过多次**（人工交接失败后再试）。
--   合一就会出现「索评请求」既像任务又像日志的形状 ——
--   「这个买家被索评了几次」与「这个任务现在是什么状态」两个问题会互相污染。
--
-- 为什么「评价回收」独立成表而不是挂在请求上的一列：
--   回收是**收到的内容**（星级 / 原文 / 有图有视频），不是请求的属性；
--   蓝图输出句点名的是「评价回收**统计**」，统计需要**明细**才能算得出来
--   （平均星级、星级分布、回收率都要从明细聚合）。
--
-- ============================================================ 四、时点与频次的具体口径
--
--   * 时点（延后）：scheduled_at = delivered_at + rule_delay_hours。
--     rule_delay_hours 不得小于租户策略的 min_delay_hours（不能刚签收就骚扰买家）。
--   * 冷却窗口：同一 buyer_ref 的两次索评之间至少间隔 rule_cooldown_hours。
--   * 每买家上限：同一 buyer_ref 在**统计窗口**（window_days）内的索评数上限。
--   * 每订单上限：同一 sales_order_no 的索评数上限（不论窗口）。
--   * 单批上限：一次批量执行最多创建多少条索评请求（防呆 + 防打爆上游）。
--
-- 数量与时间：数量 / 小时数一律 **numeric(19,6)** + BigDecimal，**禁浮点**；
-- 时间一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--
-- ============================================================ 五、发布后不可变的强制位置
--
--   1. 服务层：索评请求的身份列（上游引用 / 订单号 / 买家 / 店铺 / 签收时刻）
--      建后不可改（改了就会出现「同一张单子有两个买家/两个签收时刻」的历史）。
--   2. CHECK 约束：REJECTED 必须带 reject_reason；rating ∈ [1,5]；
--      时点与冷却快照非负；频次策略的每个值都在硬天花板内。
--   3. 触发器（CHECK 在 PostgreSQL 里可被 NOT VALID 或迁移脚本绕过，触发器不行）：
--      guard_crm_review_request_frequency（BEFORE INSERT OR UPDATE，频次硬闸）
--      guard_crm_review_request_identity（BEFORE UPDATE，身份列不可改）
--      guard_crm_review_send_record_target（BEFORE INSERT，发送记录必须挂在存在的请求上）
--      guard_crm_review_recovery_target（BEFORE INSERT，回收必须挂在存在的请求上）
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 同一口径：角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_review_frequency_policy · 租户级频次硬上限（一租户一行）
-- ============================================================================
-- 为什么它不是 15.2.2.2 的「索评策略配置」：那是可运营的配置（按店铺 / 按物流方式
-- 设延后时间、避开节假日、A/B 话术）；本表是**不可突破的兜底闸门**，
-- 作用是让任何一次索评请求自带的规则只能更严、不能更松。
-- 一租户一行：多行会让「本租户的硬上限是多少」出现两个答案。
CREATE TABLE crm_review_frequency_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 每买家上限（统计窗口内）。天花板 10：DB 自己就把「调到 999」这条路堵死。
  max_per_buyer_per_window integer NOT NULL DEFAULT 3
    CHECK (max_per_buyer_per_window BETWEEN 1 AND 10),
  -- 统计窗口（天）。天花板 365。
  window_days integer NOT NULL DEFAULT 30
    CHECK (window_days BETWEEN 1 AND 365),
  -- 冷却窗口下限（小时）。同一买家的两次索评至少隔这么久。
  min_cooldown_hours numeric(19,6) NOT NULL DEFAULT 72
    CHECK (min_cooldown_hours > 0 AND min_cooldown_hours <= 8760),
  -- 每订单上限。天花板 5。
  max_per_order integer NOT NULL DEFAULT 1
    CHECK (max_per_order BETWEEN 1 AND 5),
  -- 时点下限（小时）：签收后至少隔这么久才允许索评（不能刚签收就骚扰买家）。
  min_delay_hours numeric(19,6) NOT NULL DEFAULT 24
    CHECK (min_delay_hours > 0 AND min_delay_hours <= 8760),
  -- 单批上限：一次批量执行最多创建多少条索评请求。
  max_batch_size integer NOT NULL DEFAULT 200
    CHECK (max_batch_size BETWEEN 1 AND 1000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一租户一行。
  UNIQUE (tenant_id)
);

-- ============================================================================
-- 2. crm_review_request · 索评任务（一次索评请求 = 一行）
-- ============================================================================
-- 请求号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条索评就报权限不足」（10.3.2.2 踩过这个坑）。
CREATE SEQUENCE crm_review_request_no_seq START 1;

CREATE TABLE crm_review_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- RR-yyyyMMdd-000001
  request_no varchar(32) NOT NULL DEFAULT '',

  -- ===== 上游 10.3.2.2（channel 服务）的逻辑引用 + 快照，**不建跨库外键** =====
  upstream_case_id uuid,
  upstream_case_no varchar(32) NOT NULL DEFAULT '',
  upstream_waybill_no varchar(128) NOT NULL DEFAULT '',
  upstream_carrier_code varchar(64) NOT NULL DEFAULT '',
  -- 上游取数位置（可回溯到 channel 的哪一个端点/记录）。
  upstream_source_ref varchar(200) NOT NULL DEFAULT '',

  -- ===== 索评活动的目标店铺（来自 channel 的真实店铺清单，只存逻辑 ID + 名称快照）=====
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),

  -- ===== 被索评的对象 =====
  sales_order_no varchar(64) NOT NULL CHECK (btrim(sales_order_no) <> ''),
  -- 买家标识（平台买家号等，由运营按店铺提供）。**只作频次去重键**，
  -- 不是买家档案（15.3.1.1 未交付，本服务不持有买家主数据）。
  buyer_ref varchar(128) NOT NULL CHECK (btrim(buyer_ref) <> ''),
  -- 上游的物流状态快照（本模块的候选集恒为 DELIVERED，保留列是为了历史可读）。
  logistics_status varchar(24) NOT NULL DEFAULT 'DELIVERED'
    CHECK (logistics_status IN ('DELIVERED','RETURNED','EXCEPTION','VOID','PENDING_PICKUP','PICKED_UP','PICKUP_OVERDUE')),
  -- 签收时刻（上游快照）。索评时点从这里起算。
  delivered_at timestamptz NOT NULL,

  -- ===== 状态机 =====
  -- SCHEDULED 已排期（占额度）· SENT 已发出（占额度）·
  -- CANCELLED 已取消（不占额度）· REJECTED 被频次规则拒绝（不占额度，**留痕**）
  status varchar(16) NOT NULL DEFAULT 'SCHEDULED'
    CHECK (status IN ('SCHEDULED','SENT','CANCELLED','REJECTED')),

  -- ===== 规则快照（这一次索评用的规则；只能比租户硬上限更严）=====
  -- 时点：签收后延后多少小时才发。
  rule_delay_hours numeric(19,6) NOT NULL CHECK (rule_delay_hours > 0),
  -- 冷却窗口：同一买家两次索评至少隔多少小时。
  rule_cooldown_hours numeric(19,6) NOT NULL CHECK (rule_cooldown_hours > 0),
  -- 每买家上限（窗口内）。
  rule_max_per_buyer integer NOT NULL CHECK (rule_max_per_buyer BETWEEN 1 AND 10),
  -- 每订单上限。
  rule_max_per_order integer NOT NULL CHECK (rule_max_per_order BETWEEN 1 AND 5),
  -- 计划发送时刻 = delivered_at + rule_delay_hours。
  scheduled_at timestamptz NOT NULL,

  -- ===== 拒绝留痕（status='REJECTED' 时必填）=====
  reject_code varchar(40) NOT NULL DEFAULT '',
  reject_reason varchar(600) NOT NULL DEFAULT '',
  CHECK (status <> 'REJECTED' OR (reject_code <> '' AND reject_reason <> '')),

  -- 批次号：一次批量执行落一条，便于回看「这一批为什么有的成有的被挡」。
  batch_id uuid,
  -- 登记人（已认证成员，由服务端从 AccessService.Access 取，不接受客户端传值）。
  created_by uuid,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, request_no),
  -- 同一批次内同一订单同一买家只能有一条（批次幂等）。
  UNIQUE (tenant_id, batch_id, sales_order_no, buyer_ref)
);

-- 列表主筛选：按状态 + 最近创建。
CREATE INDEX crm_review_request_by_status
  ON crm_review_request(tenant_id, status, created_at DESC);
-- 频次判定要用：同一买家的历史索评（按时间倒序取最近一次做冷却判定）。
CREATE INDEX crm_review_request_by_buyer
  ON crm_review_request(tenant_id, buyer_ref, created_at DESC);
-- 频次判定要用：同一订单的历史索评。
CREATE INDEX crm_review_request_by_order
  ON crm_review_request(tenant_id, sales_order_no, created_at DESC);
-- 店铺维度筛选。
CREATE INDEX crm_review_request_by_shop
  ON crm_review_request(tenant_id, shop_id, created_at DESC);
-- 上游回查。
CREATE INDEX crm_review_request_by_upstream
  ON crm_review_request(tenant_id, upstream_case_id);
-- 一个已签收交运件**同时只允许一条在途索评**（SCHEDULED / SENT）。
-- 重复索评同一票是过度索评最典型的形态，这里直接用部分唯一索引挡住。
CREATE UNIQUE INDEX crm_review_request_one_active_per_case
  ON crm_review_request(tenant_id, upstream_case_id)
  WHERE upstream_case_id IS NOT NULL AND status IN ('SCHEDULED','SENT');

-- ============================================================================
-- 3. crm_review_send_record · 发送记录（每次触达尝试一行）
-- ============================================================================
CREATE TABLE crm_review_send_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 挂在哪条索评请求上。**用触发器做引用校验**，不建数据库外键
  -- （理由见文件头第二节末）。
  request_id uuid NOT NULL,

  -- 触达通道。
  -- PLATFORM_API 平台侧索评接口（**当前未接入**，服务端拒绝写这一档）；
  -- MANUAL_HANDOFF 人工交接（运营把索评请求交给平台侧 / 人工发出后登记）。
  transport varchar(24) NOT NULL CHECK (transport IN ('PLATFORM_API','MANUAL_HANDOFF')),
  -- QUEUED 已登记待发 · SENT 已发出 · FAILED 失败（带原因）· CANCELLED 已作废。
  status varchar(16) NOT NULL DEFAULT 'QUEUED'
    CHECK (status IN ('QUEUED','SENT','FAILED','CANCELLED')),

  attempted_at timestamptz,
  failure_reason varchar(600) NOT NULL DEFAULT '',
  -- 登记人（已认证成员，服务端写入）。
  operator_id uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  CHECK (status <> 'SENT' OR attempted_at IS NOT NULL),
  CHECK (status <> 'FAILED' OR btrim(failure_reason) <> '')
);

-- 按请求读发送记录（时间线）。
CREATE INDEX crm_review_send_record_by_request
  ON crm_review_send_record(tenant_id, request_id, created_at DESC);
-- 一条索评请求**最多一条 SENT 发送记录**：发出去过就是发出去过，
-- 再登记一条「已发出」会让「这个买家到底收到几次」出现两个答案。
CREATE UNIQUE INDEX crm_review_send_record_one_sent
  ON crm_review_send_record(tenant_id, request_id)
  WHERE status = 'SENT';

-- ============================================================================
-- 4. crm_review_recovery · 评价回收桶（收到的评价原文 + 关联到索评请求）
-- ============================================================================
-- ⚠️ 下游 15.2.1.1 评价列表 / 评价同步**未交付** ⇒ 本表**只是回收桶**：
-- 记录「收到的评价原文」，**不假装**已交付评价管理（无回复、无申诉、无标签）。
CREATE TABLE crm_review_recovery (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 关联到索评请求。**用触发器做引用校验**，不建数据库外键。
  request_id uuid NOT NULL,

  -- 星级（1..5）。没有星级的评价在平台上不存在，所以 NOT NULL。
  rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
  -- 评价原文（可能为空：有些平台允许只打星）。
  content text NOT NULL DEFAULT '',
  has_image boolean NOT NULL DEFAULT false,
  has_video boolean NOT NULL DEFAULT false,

  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),
  -- 来源：MANUAL_ENTRY 人工登记（**当前唯一可用**，15.2.1.1 未交付）；
  -- PLATFORM_SYNC 平台同步（**未接入**，服务端拒绝写这一档）。
  source varchar(24) NOT NULL DEFAULT 'MANUAL_ENTRY'
    CHECK (source IN ('MANUAL_ENTRY','PLATFORM_SYNC')),

  -- 买家在平台上的评价时刻（不是本系统的登记时刻）。
  received_at timestamptz NOT NULL,
  -- 登记人（已认证成员，服务端写入）。
  recorded_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 按请求读回收明细。
CREATE INDEX crm_review_recovery_by_request
  ON crm_review_recovery(tenant_id, request_id, received_at DESC);
-- 统计要用：按平台 + 时间聚合。
CREATE INDEX crm_review_recovery_by_received
  ON crm_review_recovery(tenant_id, platform_code, received_at DESC);

-- ============================================================================
-- 5. 触发器：频次硬闸 + 身份列不可改 + 两处引用校验
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 5.1 guard_crm_review_request_frequency · 🔴 加粗句「索评频次必须受限」的 DB 闸门
-- ---------------------------------------------------------------------------
-- 只有**占用额度**的状态参与判定：SCHEDULED / SENT。
-- REJECTED（被频次规则拒绝）与 CANCELLED（已取消）**不消耗额度** ——
-- 否则「试一次被挡掉」会把额度吃掉，运营改个时点重试就再也发不出去了。
--
-- ⚠️ 为什么用**同库回表**而不是外键：外键会打断同服务既有测试的 TRUNCATE
-- （batch-37 踩过）。本触发器只做**计数判定**，不建立引用约束。
--
-- ⚠️ 计数为什么是可靠的：RLS 的 WITH CHECK 保证 tenant_id = 已绑定租户，
-- 而本触发器的回表同样受 RLS 约束 ⇒ 计数**只会数到本租户的行**。
-- 未绑定租户时 RLS 直接拒写，所以不存在「无租户上下文时计数恒为 0」这条漏路径。
CREATE FUNCTION guard_crm_review_request_frequency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  p_max_per_buyer integer;
  p_window_days integer;
  p_min_cooldown numeric;
  p_max_per_order integer;
  p_min_delay numeric;
  v_count integer;
  v_last timestamptz;
BEGIN
  -- 不占用额度的状态：直接放行（规则快照的合法性仍由 CHECK 保证）。
  IF NEW.status NOT IN ('SCHEDULED','SENT') THEN
    RETURN NEW;
  END IF;

  SELECT max_per_buyer_per_window, window_days, min_cooldown_hours, max_per_order, min_delay_hours
    INTO p_max_per_buyer, p_window_days, p_min_cooldown, p_max_per_order, p_min_delay
    FROM crm_review_frequency_policy
   WHERE tenant_id = NEW.tenant_id;

  -- 没有策略 ⇒ 不许索评。**这是加粗句的兜底**：没有上限就没有「受限」。
  IF NOT FOUND THEN
    RAISE EXCEPTION '本租户尚未配置索评频次策略：索评频次必须受限，没有硬上限就不允许发起索评。请先调用 PUT /crm/review-requests/frequency-policy 配置每买家上限、冷却窗口与时点下限' USING ERRCODE='23514';
  END IF;

  -- 请求自带的规则快照**只能比租户硬上限更严**，不能更松。
  IF NEW.rule_max_per_buyer > p_max_per_buyer THEN
    RAISE EXCEPTION '本次规则的每买家上限 % 次比本租户的硬上限 % 次更松：规则只能比硬上限更严，不能更松',
      NEW.rule_max_per_buyer, p_max_per_buyer USING ERRCODE='23514';
  END IF;
  IF NEW.rule_max_per_order > p_max_per_order THEN
    RAISE EXCEPTION '本次规则的每订单上限 % 次比本租户的硬上限 % 次更松：规则只能比硬上限更严，不能更松',
      NEW.rule_max_per_order, p_max_per_order USING ERRCODE='23514';
  END IF;
  IF NEW.rule_cooldown_hours < p_min_cooldown THEN
    RAISE EXCEPTION '本次规则的冷却窗口 % 小时比本租户的下限 % 小时更短：冷却窗口只能更长，不能更短',
      NEW.rule_cooldown_hours, p_min_cooldown USING ERRCODE='23514';
  END IF;
  IF NEW.rule_delay_hours < p_min_delay THEN
    RAISE EXCEPTION '本次规则的时点 % 小时比本租户的下限 % 小时更早：刚签收就索评会打扰买家，时点只能更晚，不能更早',
      NEW.rule_delay_hours, p_min_delay USING ERRCODE='23514';
  END IF;

  -- 时点：计划发送时刻不得早于「签收 + 最小时点」。
  -- ⚠️ 小时数是 numeric(19,6)，PG 没有 `numeric * interval` 这个运算符，
  --   所以先折成整秒再乘 interval（int8 * interval 走 float8 * interval，
  --   内部按微秒存 int64，秒级精度对小时窗口绰绰有余）。
  IF NEW.scheduled_at < NEW.delivered_at + ((p_min_delay * 3600)::bigint * interval '1 second') THEN
    RAISE EXCEPTION '计划发送时刻 % 早于「签收时刻 + 时点下限 % 小时」：刚签收就索评会打扰买家',
      NEW.scheduled_at, p_min_delay USING ERRCODE='23514';
  END IF;

  -- (a) 每订单上限：同一订单的索评次数。
  SELECT count(*) INTO v_count FROM crm_review_request
   WHERE tenant_id = NEW.tenant_id
     AND sales_order_no = NEW.sales_order_no
     AND status IN ('SCHEDULED','SENT')
     AND id <> NEW.id;
  IF v_count >= NEW.rule_max_per_order THEN
    RAISE EXCEPTION '订单 % 已经索评 % 次，达到本次规则的每订单上限 % 次：同一张单子反复索评会被买家投诉，也可能触发平台处罚。请跳过该订单或由运营显式调整上限（不得超过租户硬上限 % 次）',
      NEW.sales_order_no, v_count, NEW.rule_max_per_order, p_max_per_order USING ERRCODE='23514';
  END IF;

  -- (b) 每买家上限（统计窗口内）：同一买家的索评次数。
  SELECT count(*) INTO v_count FROM crm_review_request
   WHERE tenant_id = NEW.tenant_id
     AND buyer_ref = NEW.buyer_ref
     AND status IN ('SCHEDULED','SENT')
     AND created_at >= now() - make_interval(days => p_window_days)
     AND id <> NEW.id;
  IF v_count >= NEW.rule_max_per_buyer THEN
    RAISE EXCEPTION '买家 % 在最近 % 天内已经被索评 % 次，达到本次规则的每买家上限 % 次：过度索评会被买家投诉甚至平台处罚。请延长冷却窗口、跳过该买家或由运营显式调整上限（不得超过租户硬上限 % 次）',
      NEW.buyer_ref, p_window_days, v_count, NEW.rule_max_per_buyer, p_max_per_buyer USING ERRCODE='23514';
  END IF;

  -- (c) 冷却窗口：同一买家的上一次索评距今是否已过冷却小时数。
  SELECT max(created_at) INTO v_last FROM crm_review_request
   WHERE tenant_id = NEW.tenant_id
     AND buyer_ref = NEW.buyer_ref
     AND status IN ('SCHEDULED','SENT')
     AND id <> NEW.id;
  IF v_last IS NOT NULL
     AND now() < v_last + ((NEW.rule_cooldown_hours * 3600)::bigint * interval '1 second') THEN
    RAISE EXCEPTION '买家 % 最近一次索评在 %，冷却窗口 % 小时还没到：冷却窗口内再索评就是打扰买家，过度索评会被买家投诉甚至平台处罚',
      NEW.buyer_ref, v_last, NEW.rule_cooldown_hours USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_request_frequency_trg
  BEFORE INSERT OR UPDATE ON crm_review_request
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_request_frequency();

-- ---------------------------------------------------------------------------
-- 5.2 guard_crm_review_request_identity · 身份列建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：这行是**一次索评请求的凭据**。改了上游引用 / 订单号 / 买家 / 店铺 /
-- 签收时刻之后，「这个买家被索评过几次」与「这一单有没有被索评过」两个计数
-- 都会在历史上出现两个答案 —— 而加粗句要的正是**这个数**可靠。
-- status / scheduled_at / reject_* / note / updated_at 仍然可改（它们是流程状态）。
CREATE FUNCTION guard_crm_review_request_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.upstream_case_id IS DISTINCT FROM OLD.upstream_case_id THEN
    RAISE EXCEPTION '索评请求的上游签收单引用建后不可改：改它会让「这一票有没有被索评过」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.upstream_case_no IS DISTINCT FROM OLD.upstream_case_no THEN
    RAISE EXCEPTION '索评请求的上游签收单号建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.sales_order_no IS DISTINCT FROM OLD.sales_order_no THEN
    RAISE EXCEPTION '索评请求的订单号建后不可改：改它会让「这一单有没有被索评过」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.buyer_ref IS DISTINCT FROM OLD.buyer_ref THEN
    RAISE EXCEPTION '索评请求的买家标识建后不可改：改它会让「这个买家被索评过几次」这个频次计数在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION '索评请求的目标店铺建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.delivered_at IS DISTINCT FROM OLD.delivered_at THEN
    RAISE EXCEPTION '索评请求的签收时刻建后不可改：索评时点是从它起算的，改它会让「为什么在这时索评」无从查证' USING ERRCODE='23514';
  END IF;
  IF NEW.rule_delay_hours IS DISTINCT FROM OLD.rule_delay_hours
     OR NEW.rule_cooldown_hours IS DISTINCT FROM OLD.rule_cooldown_hours
     OR NEW.rule_max_per_buyer IS DISTINCT FROM OLD.rule_max_per_buyer
     OR NEW.rule_max_per_order IS DISTINCT FROM OLD.rule_max_per_order THEN
    RAISE EXCEPTION '索评请求的规则快照建后不可改：改它会让「这次为什么被频次规则挡住」无从查证' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_request_identity_trg
  BEFORE UPDATE ON crm_review_request
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_request_identity();

-- ---------------------------------------------------------------------------
-- 5.3 guard_crm_review_send_record_target · 发送记录必须挂在存在的索评请求上
-- ---------------------------------------------------------------------------
-- 用**触发器回表核对**而不是 FOREIGN KEY（理由见文件头第二节末）。
-- 没有这条，「为一条不存在的索评请求登记发送记录」会成为可能 ——
-- 那是最不该被记录下来的一次「触达」。
CREATE FUNCTION guard_crm_review_send_record_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  SELECT request_no INTO v_no FROM crm_review_request
   WHERE tenant_id = NEW.tenant_id AND id = NEW.request_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '索评请求 % 不存在（tenant=%），不能为它登记发送记录',
      NEW.request_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_send_record_target_trg
  BEFORE INSERT ON crm_review_send_record
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_send_record_target();

-- ---------------------------------------------------------------------------
-- 5.4 guard_crm_review_recovery_target · 评价回收必须挂在存在的索评请求上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_review_recovery_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  SELECT request_no INTO v_no FROM crm_review_request
   WHERE tenant_id = NEW.tenant_id AND id = NEW.request_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '索评请求 % 不存在（tenant=%），不能为它登记评价回收',
      NEW.request_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_recovery_target_trg
  BEFORE INSERT ON crm_review_recovery
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_recovery_target();

-- ============================================================================
-- 6. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_review_frequency_policy','crm_review_request','crm_review_send_record','crm_review_recovery'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_review_frequency_policy, crm_review_request, crm_review_send_record, crm_review_recovery TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_review_frequency_policy, crm_review_request, crm_review_send_record, crm_review_recovery TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条索评就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_review_request_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_request_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 7. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.2.1.1 评价列表 / 评价同步**（**未开发**）：本模块只落**评价回收桶**
--     （收到的评价原文 + 关联到索评请求）+ 回收统计。评价的回复（15.2.1.2）、
--     差评处理（15.2.1.3）、平台评价同步（2 同步）**都不在本模块**，
--     本模块也**不假装**它们存在（capabilities 里 review_sync 维度 available=false）。
--   * **15.2.2.2 索评规则**（**未开发**）：本模块的规则是**一次请求自带的快照**，
--     租户级频次硬上限是**兜底闸门**（crm_review_frequency_policy）。
--     15.2.2.2 落地后它管「按店铺 / 按物流方式的延后时间、避开节假日、A/B 话术」，
--     仍然改不动本表的硬天花板。
--   * **平台侧索评接口**（**未接入**）：本模块**刻意不放假 vendor 实现**。
--     发送记录的 transport='PLATFORM_API' 由**服务层拒绝**，
--     只允许 MANUAL_HANDOFF（人工交接）并如实登记状态 ——
--     一个「返回成功回执」的假通道会让「买家已经被索评过」在数据上看起来发生过，
--     而过度索评的代价正是买家投诉与平台处罚。
