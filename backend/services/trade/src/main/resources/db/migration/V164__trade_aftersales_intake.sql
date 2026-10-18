-- 11.1.1.3 售后受理（卷 11 售后与退货 / L3 11.1 售后受理 / L4 11.1.1.3）。
--
-- 蓝图 11-aftersales-and-returns.md 第 86-90 行原文：
--   职责：受理平台或内部发起的售后请求：判定是否在售后期内、是否符合条件、指定责任人与处理方案、
--         设置回复时限。
--   输入：售后申请（平台消息/买家消息/内部录入）；售后政策配置（期限、条件、必退情形）。
--   输出：售后单（待处理）；政策判定结果；责任人分配。
--   关系：上游 = 15 客户与客服、2 平台消息；下游 = 11.2/11.3。
--         **必退情形（平台强制、收到即退）要自动判定**，人工不能为了业绩拖着不退导致平台判责。
--
-- 归属：**trade 服务**（卷 11 L2 表 11.1 售后受理 → 主责服务 trade）。
--
-- ===========================================================================
-- 一、写入边界：本迁移**不动 aftersales_case**
-- ===========================================================================
-- 实查集成头 f760ba37：`aftersales_case`（V156）是 11.1.1.1 的写入所有者，
-- 且 V156 第 470-473 行把它的 UPDATE 收紧到列级白名单（status / resolved_at / updated_at）。
-- 本模块**不 ALTER 它的任何一列、不 INSERT 它**，而是：
--   1. 受理时**委托** `AftersalesCaseService.create()` 建单（既有写入所有者仍是唯一写入者），
--      本模块只负责在**同一个事务**里追加自己那张判定凭据表；
--   2. 本迁移三张表全部 append-only 或内容不可改，见第四节。
--
-- ⚠️ 11.1.1.1 的 `create()` 注释已写明「它交付后应当接管本方法」——
--   接管的是**受理判定**（下面四项），不是把 INSERT SQL 抄第二份。
--   抄第二份就是 AGENTS.md「每张事实表只有一个写入所有者」的直接违反。
--
-- ===========================================================================
-- 二、四项判定各读什么（每一项都指向一张**真实存在**的表，没有一处硬编码常量）
-- ===========================================================================
-- ① 是否在售后期内
--    锚点 = `sales_order.submitted_at`，缺则回退 `created_at`（V99，同库只读；
--    9.1.1.1 车道的写入所有者，本模块**只 SELECT**）。锚点用了哪一列由
--    `order_anchor_kind` 如实记下 —— DRAFT 手工单没有 submitted_at，
--    拿 created_at 当「下单时刻」必须让人看得见，否则窗口判定会被误读。
--    期限天数 = **17.5.1.1 业务参数** `AFTERSALE_RETURN_WINDOW_DAYS`（出厂默认 30 天）。
--    ⚠️ 17.5.1.1 **已交付**（identity V48 / BusinessParameterController），
--    台账「批次 19」之前的旧结论说它未开发；本车道以集成头实测为准：
--    `business_parameter_definition` 里确有该参数且 category='AFTERSALE'。
--    它在 **identity 服务的独立库**，AGENTS.md 禁止跨服务 SQL ⇒ 由
--    `AftersalesPolicyQueryPort` 经 `/identity/business-parameters/effective` 授权只读端点读，
--    **不在本迁移里落一份影子副本**。
--
-- ② 是否符合条件（必退情形自动判定 —— 蓝图加粗句的落点）
--    情形词表 = 17.5.1.1 的 `AFTERSALE_MUST_REFUND_REASONS`（MULTI_ENUM，
--    出厂默认 `DAMAGED_ON_ARRIVAL,MISSING_ITEM,WRONG_ITEM`，合法取值 6 个）。
--    判定读三处，优先级固定（见 service）：平台声明 > 原因分类映射 > 不命中。
--    **判定结果整份冻结在 `aftersales_intake_verdict` 上**（`policy_*` 那些列），
--    照 identity V48 `business_parameter_freeze` 的同一理由：
--    「改参数不改历史单据已冻结的行为」—— 改了必退情形词表之后，
--    三个月前那张单仍要能读回「当初按哪份词表判的」。
--
-- ③ 指定责任人与处理方案
--    处理方案 `handling_plan` **必填且落库**，不是嘴上说说。
--    责任人走 11.1.1.1 已有的 `aftersales_owner_assignment`（追加式，当前责任人 =
--    captured_at 最大行）。自动派单的候选池是本迁移的 `aftersales_owner_roster`：
--    卷 15 客服在集成头只交付了 15.4.1.1（在 crm 的独立库），本服务读不到「谁是客服」，
--    ⇒ 照 V156 第 180-194 行、warehouse V56 的同一套纪律落成**租户可维护的登记表**，
--    每行强制 `owner_source ∈ (TENANT_MAINTAINED / MODULE_15_SERVICE_STAFF)`，
--    15 客服交付后由同步器按同一形状写入，**表结构与查询都不用改**。
--
-- ④ 设置回复时限
--    ⚠️ **17.5.1.1 里没有「回复时限」参数**（8 个出厂默认值里 AFTERSALE 类只有
--    `AFTERSALE_RETURN_WINDOW_DAYS` 与 `AFTERSALE_MUST_REFUND_REASONS` 两条，实测确认）。
--    所以回复时限**不能**假装是「政策参数」，它落在本迁移的
--    `aftersales_reply_sla`（租户可维护、分档生效、内容写定不可改），
--    解析优先级 PLATFORM_GIVEN（平台自己给的时限）> POLICY_SLA（租户 SLA）> OPERATOR_GIVEN。
--    **三档都取不到时报错并说清「没配置」**，而不是悄悄用 48 小时之类的写死常量 ——
--    那样做的话 capabilities 里的 replyDeadlineNote 就是假话。
--
-- ===========================================================================
-- 三、平台强制必退的不可降级（蓝图加粗句的 DB 层钉死）
-- ===========================================================================
-- 「人工不能为了业绩拖着不退导致平台判责」这句话，靠服务层自觉是守不住的：
-- 改一个 service 方法就能让一张平台强制必退的单变成「不符合条件」。
-- ⇒ `guard_aftersales_verdict_must_refund` 把优先级写成触发器：
--   **被顶替（supersedes）的判定若是 PLATFORM_DECLARED 必退，新判定必须仍然必退。**
-- 平台自己声明的必退是我们无法单方面推翻的事实 —— 要推翻只能改平台侧，不能在本地改一行 SQL。
-- 而 REASON_MAPPED 允许重新判定：词表变了，映射结论就应当跟着变。
-- ===========================================================================

-- ===========================================================================
-- 四、三张表
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 4.1 aftersales_reply_sla · 回复时限策略（分档生效，内容写定不可改）
-- ---------------------------------------------------------------------------
-- ⚠️ 刻意**没有**「平台必给时限就够用」的兜底常量：一个租户配了 SLA 与没配是
--   两件完全不同的事，前者是「我们承诺 24 小时回」，后者是「还没定过规矩」。
--   合成一个值会让前者被后者冒名。
--
-- ⚠️ platform_scope 取 sales_order.order_source 的同一词表（V99 的 CHECK）
--   而不是自造一套平台码：11.1.1.1 的平台维度本来就是 order_source，
--   这里再造一套会让「按平台筛」在两个模块里给出两个答案。
--   '' = 全平台兜底档，解析顺序 PLATFORM 精确档 > '' 兜底档。
CREATE TABLE aftersales_reply_sla (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  platform_scope varchar(32) NOT NULL DEFAULT '' CHECK(platform_scope IN (
      '','MANUAL','PLATFORM','AMAZON','SHOPIFY','EBAY','TIKTOK_SHOP','ALIBABA_1688'
  )),

  -- 时限一律**整数小时**（照 V99 payment_terms_days 的同一理由：
  -- 「30 天」在某个月差 1 天）。上限 8760 = 一年，避免把「一年后才回」配成策略。
  reply_sla_hours integer NOT NULL CHECK(reply_sla_hours > 0 AND reply_sla_hours <= 8760),
  -- 处理时限可空：平台有时只管「什么时候回我」，不管「什么时候修好」。
  resolve_sla_hours integer CHECK(resolve_sla_hours IS NULL
      OR (resolve_sla_hours > 0 AND resolve_sla_hours <= 8760)),

  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 回复时限不得晚于处理时限（与 V156 对 reply_due_at/resolve_due_at 的同一约束，
  -- 在配置这一层就挡住，免得配出来的档每次受理都解析失败）。
  CHECK(resolve_sla_hours IS NULL OR resolve_sla_hours >= reply_sla_hours),
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);

-- 一个槽位（租户 + 平台档）同一时刻只能有一档生效：正在用的那档至多一行。
CREATE UNIQUE INDEX aftersales_reply_sla_one_open_per_scope
  ON aftersales_reply_sla(tenant_id, platform_scope)
  WHERE effective_to IS NULL;
-- 解析生效档：WHERE 命中 + ORDER BY effective_from DESC LIMIT 1 走这条索引。
CREATE INDEX aftersales_reply_sla_resolve
  ON aftersales_reply_sla(tenant_id, platform_scope, effective_from DESC);
-- ⚠️ 已关区间的行**刻意保留在表里**（warehouse V56 第 5-12 行同一套约定）：
--   「历史」不是另一份拷贝，而是同一批行里已关区间的那些。
--   刻意不建第二张变更历史表 —— 两张表各自漂移是这类设计最常见的病。

-- 内容列写定后不许改，只允许把 effective_to 从 NULL 收到一个值（换版或作废）。
-- 理由与 sellable_policy_immutable 同源：回复时限是平台判责的计时基准，
-- 能悄悄改掉一条正在生效的档，等于把那道防线本身拆了。
CREATE FUNCTION guard_aftersales_reply_sla_immutable() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.platform_scope IS DISTINCT FROM OLD.platform_scope
     OR NEW.reply_sla_hours IS DISTINCT FROM OLD.reply_sla_hours
     OR NEW.resolve_sla_hours IS DISTINCT FROM OLD.resolve_sla_hours
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
    RAISE EXCEPTION '回复时限策略档内容写定后不可改（sla_id=%）：请收口旧档（effective_to）再开新档',
      OLD.id USING ERRCODE = 'check_violation';
  END IF;
  -- 已关的档不许再关一次（会把历史区间改写成一个对不上的形状）。
  IF OLD.effective_to IS NOT NULL THEN
    RAISE EXCEPTION '这一档已收口（effective_to=%），不可再次修改', OLD.effective_to
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_reply_sla_immutable
  BEFORE UPDATE ON aftersales_reply_sla
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_reply_sla_immutable();

-- 同槽位生效区间不许重叠：重叠的区间在解析时**没有唯一答案**，
-- 而报错要带上冲突档的 id，否则只能靠猜。
CREATE FUNCTION guard_aftersales_reply_sla_interval() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_conflict uuid;
BEGIN
  SELECT r.id INTO t_conflict
    FROM aftersales_reply_sla r
   WHERE r.tenant_id = NEW.tenant_id
     AND r.platform_scope = NEW.platform_scope
     AND r.id <> NEW.id
     AND tstzrange(r.effective_from, r.effective_to, '[)')
      && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
   LIMIT 1;
  IF t_conflict IS NOT NULL THEN
    RAISE EXCEPTION '回复时限策略生效区间与已有档重叠：冲突档 sla_id=%', t_conflict
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_reply_sla_interval
  BEFORE INSERT OR UPDATE ON aftersales_reply_sla
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_reply_sla_interval();

-- ---------------------------------------------------------------------------
-- 4.2 aftersales_owner_roster · 责任人名册（自动派单轮转池）
-- ---------------------------------------------------------------------------
-- 一人一行（UNIQUE(tenant_id, owner_ref)）：名册是「这个人能接单」的事实，
-- 不是「谁在什么时候接了哪一单」—— 后者是 aftersales_owner_assignment 的职责，
-- 刻意不合并成一张表，否则改一次名册就会毁掉责任人流转的举证链。
--
-- owner_source 刻意**不伪造 L4 模块号**（照 V156 第 191-193 行的同一纪律）：
-- 卷 15 里目前没有任何一个 L4 是「售后责任人分配」的交付方。
CREATE TABLE aftersales_owner_roster (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  owner_ref varchar(128) NOT NULL CHECK(btrim(owner_ref) <> ''),
  owner_display_name varchar(128) NOT NULL DEFAULT '',
  owner_role_label varchar(64) NOT NULL DEFAULT '',
  owner_source varchar(32) NOT NULL CHECK(owner_source IN (
      'TENANT_MAINTAINED','MODULE_15_SERVICE_STAFF'
  )),
  source_reference varchar(128) NOT NULL DEFAULT '',

  -- 停接新单（人在休假 / 已离职）时**不删行**：删了就没法回答
  -- 「三个月前那批单是谁派的」，只把开关关掉，历史分配照样读得到。
  accepting_new_cases boolean NOT NULL DEFAULT true,
  -- 权重 1~100：轮转时按权重摊派，100 = 十倍于权重 1。
  -- 刻意不叫 priority —— priority 在本服务里是工单优先级（AftersalesServiceTicket），
  -- 同名不同义会让两个字段在同一个响应里对不上。
  weight integer NOT NULL DEFAULT 1 CHECK(weight BETWEEN 1 AND 100),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,owner_ref),
  CHECK(owner_source = 'TENANT_MAINTAINED' OR btrim(source_reference) <> '')
);

-- 轮转选人走这条索引：只扫「在接单」的行。
CREATE INDEX aftersales_owner_roster_assignable
  ON aftersales_owner_roster(tenant_id, owner_ref)
  WHERE accepting_new_cases;

-- ---------------------------------------------------------------------------
-- 4.3 aftersales_intake_verdict · 受理判定凭据（本模块的核心产出）
-- ---------------------------------------------------------------------------
-- ⚠️ **append-only**：判定凭据是纠纷举证的一部分（「当初按哪份政策判的必退」）。
--   改判定不是 UPDATE 那一行，而是**再插一行并把上一行标记 superseded_at** ——
--   与 V158 `aftersales_reason_attribution_set` 的 superseded 纪律同源。
--
-- ⚠️ **一张售后单同一时刻只有一条生效判定**：部分唯一索引 (tenant_id, case_id)
--   WHERE superseded_at IS NULL。没有它，「当前判定是谁」就变成「取最新那条」，
--   而两条判定 inserted_at 相同或时钟回拨都会给出不同答案。
CREATE TABLE aftersales_intake_verdict (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  case_id uuid NOT NULL,

  -- ---- 受理来源（蓝图输入句：平台消息 / 买家消息 / 内部录入）----
  intake_source varchar(24) NOT NULL CHECK(intake_source IN (
      'PLATFORM_MESSAGE','BUYER_MESSAGE','INTERNAL_ENTRY'
  )),
  -- 平台侧申请号：平台重投同一条申请时靠它幂等（见本表末尾的部分唯一索引）。
  intake_reference varchar(128) NOT NULL DEFAULT '',
  -- 受理渠道（与 V157 `aftersales_message.channel` 同一词表，两处能对上）。
  intake_channel varchar(16) NOT NULL CHECK(intake_channel IN (
      'PLATFORM','EMAIL','CHAT','PHONE','INTERNAL_NOTE'
  )),

  -- 请求到达时刻 = 时效计时的起点。内部补录的单晚录入也不该多算时限，
  -- 所以这个时刻由调用方给（平台消息带的是平台受理时刻）。
  request_received_at timestamptz NOT NULL,
  judged_at timestamptz NOT NULL,
  judged_by uuid NOT NULL,

  -- ---- 判定一：是否在售后期内 ----
  -- 锚点用了哪一列：SUBMITTED_AT 优先，缺则 CREATED_AT。如实记下，不合并。
  order_anchor_kind varchar(16) NOT NULL CHECK(order_anchor_kind IN ('SUBMITTED_AT','CREATED_AT')),
  order_anchor_at timestamptz NOT NULL,
  policy_return_window_days integer NOT NULL CHECK(policy_return_window_days > 0),
  return_window_deadline_at timestamptz NOT NULL,
  in_return_window boolean NOT NULL,
  -- 超期小时数（窗口内为负 = 还剩多少小时）。可空表示没算。
  window_margin_hours bigint,

  -- ---- 判定二：是否符合条件（必退情形自动判定）----
  -- 冻结下来的词表**原样**（排序归一后存），不是「命中的那一个」——
  -- 只存命中项就答不出「当初还有哪些情形算必退」。
  policy_must_refund_reasons text[] NOT NULL,
  must_refund boolean NOT NULL,
  -- PLATFORM_DECLARED 平台自己声明必退 / REASON_MAPPED 由原因分类映射命中 /
  -- NONE 不命中任何必退情形。
  must_refund_basis varchar(24) NOT NULL CHECK(must_refund_basis IN (
      'PLATFORM_DECLARED','REASON_MAPPED','NONE'
  )),
  must_refund_situation varchar(32) NOT NULL DEFAULT '',
  -- ACCEPTED 可正常受理 / ACCEPTED_MUST_REFUND 必退（强制）/
  -- OUT_OF_WINDOW_PENDING 超窗但要人工定 / REJECTED_OUT_OF_WINDOW 超窗且非必退。
  eligibility varchar(28) NOT NULL CHECK(eligibility IN (
      'ACCEPTED','ACCEPTED_MUST_REFUND','OUT_OF_WINDOW_PENDING','REJECTED_OUT_OF_WINDOW'
  )),

  -- ---- 政策读取凭据（冻结，理由见第二节 ②）----
  -- 生效档来自哪一档：SHOP > LEGAL_ENTITY > GLOBAL > DEFINITION_DEFAULT
  -- （与 17.5.1.1 同一套优先级；本服务不传 legalEntity/shop 时自然落到后面两档）。
  policy_source_scope varchar(32) NOT NULL CHECK(policy_source_scope IN (
      'SHOP','LEGAL_ENTITY','GLOBAL','DEFINITION_DEFAULT'
  )),
  -- 政策的唯一真身永远是 identity 侧；这里只记「读的是哪个模块的哪条参数」。
  policy_parameter_codes text[] NOT NULL,
  policy_read_at timestamptz NOT NULL,

  -- ---- 判定三：处理方案 ----
  -- 与 case_type 独立：处理方案是「打算怎么办」，case_type 是「买家要什么」。
  -- REJECT 是合法结论（超窗且非必退）—— 受理判定本来就该能给出「不受理」。
  handling_plan varchar(24) NOT NULL CHECK(handling_plan IN (
      'RETURN_REFUND','REFUND_ONLY','EXCHANGE','REISSUE','REPAIR','REJECT'
  )),
  handling_plan_source varchar(32) NOT NULL CHECK(handling_plan_source IN (
      'MUST_REFUND_FORCED','OPERATOR'
  )),

  -- ---- 判定四：回复时限 ----
  -- ⚠️ **只有两档**：平台自己给的时限，或租户配的 SLA 推算出来的时限。
  --   刻意不设「受理人随手指定」这一档 —— 受理时限是平台判责的计时基准，
  --   「谁随手填的」不该成为它的合法来源。两档都取不到时**受理直接失败并说清没配置**
  --   （见 AftersalesIntakeService#resolveReplyDue），而不是默默替租户选一个数字。
  reply_due_at timestamptz NOT NULL,
  reply_due_basis varchar(24) NOT NULL CHECK(reply_due_basis IN (
      'PLATFORM_GIVEN','POLICY_SLA'
  )),
  reply_sla_hours integer CHECK(reply_sla_hours IS NULL OR reply_sla_hours > 0),
  resolve_due_at timestamptz,
  resolve_sla_hours integer CHECK(resolve_sla_hours IS NULL OR resolve_sla_hours > 0),

  -- ---- 责任人分配 ----
  owner_assignment_mode varchar(24) NOT NULL CHECK(owner_assignment_mode IN (
      'OPERATOR_SPECIFIED','ROSTER_ROUND_ROBIN','UNASSIGNED'
  )),

  -- ---- 版本链（重新判定 = 追加，不改写）----
  supersedes_id uuid,
  superseded_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- ⚠️ **这里刻意不给 supersedes_id / superseded_at 加 CHECK**。
  --   四种组合全都是合法的：
  --     (NULL,     NULL) 首版判定，生效中
  --     (NULL,  有值)   首版判定，已被顶替
  --     (有值,    NULL) 后继判定，生效中
  --     (有值,  有值)   后继判定，已被顶替
  --   早先两个方向都写过（`superseded_at is null or supersedes_id is not null`
  --   与它的反向），两次都让「重新判定」这一步直接撞 CHECK ——
  --   因为**后继判定本身就是「顶替了别人 + 生效中」**，任何把这两列关联起来的
  --   CHECK 都会砍掉一种合法状态。
  --   真正需要的不变式各有各的归属：
  --     * 一张单同一时刻只有一条生效判定 → 部分唯一索引 one_current；
  --     * 判定内容不可改写 → 列级 GRANT 只授 superseded_at；
  --     * 顶替时刻只能写一次 → guard_aftersales_verdict_supersede_once；
  --     * 顶替与降级规则 → guard_aftersales_verdict_must_refund。
  -- 非必退不许带「命中的情形码」，必退必须带：
  -- 少一个方向就分别会出现「没命中却写着命中哪个」和「必退却查不出理由」。
  CHECK((must_refund_basis <> 'NONE') = (btrim(must_refund_situation) <> '')),
  -- 处理方案为 REJECT 时**不能**标成「必退强制」：必退单没有「不办」这个选项，
  -- 这条 CHECK 让「平台强制必退 + 判不受理」在数据库层就不可能存在。
  CHECK(handling_plan = 'REJECT' OR handling_plan_source <> 'MUST_REFUND_FORCED'
        OR must_refund)
);

-- 一张单同一时刻只有一条生效判定。
CREATE UNIQUE INDEX aftersales_intake_verdict_one_current
  ON aftersales_intake_verdict(tenant_id, case_id)
  WHERE superseded_at IS NULL;
-- 判定凭据按单倒序读（「当初按什么判的」要能按时间回看）。
CREATE INDEX aftersales_intake_verdict_by_case
  ON aftersales_intake_verdict(tenant_id, case_id, judged_at DESC);
-- 必退单进队列：运营每天要看的就是这批单，且它们不许消失。
CREATE INDEX aftersales_intake_verdict_must_refund
  ON aftersales_intake_verdict(tenant_id, reply_due_at)
  WHERE must_refund AND superseded_at IS NULL;
-- 平台重投幂等：只有「平台消息 + 有平台申请号」才判重，
-- 内部录入与买家消息没有平台号可依，凭空造一个号会让第二次登记被静默吞掉。
CREATE UNIQUE INDEX aftersales_intake_verdict_platform_reference
  ON aftersales_intake_verdict(tenant_id, btrim(intake_reference))
  WHERE intake_source = 'PLATFORM_MESSAGE' AND btrim(intake_reference) <> '';

-- 判定必须挂在真实的、未结案的售后单上（与 V156 的 guard_aftersales_authorization_kind 同源：
-- 给一张已结案的单补一条「受理判定」等于凭空造出一段从没发生过的受理历史）。
CREATE FUNCTION guard_aftersales_intake_verdict_case() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(20);
BEGIN
  SELECT c.status INTO t_status
    FROM aftersales_case c
   WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.case_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '售后单不存在或不属于当前租户：case_id=%', NEW.case_id
      USING ERRCODE = 'no_data_found';
  END IF;
  IF t_status IN ('RESOLVED','REJECTED','CLOSED') THEN
    RAISE EXCEPTION '售后单已结案（status=%），不能再追加受理判定', t_status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_intake_verdict_case
  BEFORE INSERT ON aftersales_intake_verdict
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_intake_verdict_case();

-- 蓝图加粗句「人工不能为了业绩拖着不退导致平台判责」的 DB 层钉死（理由见第三节）。
CREATE FUNCTION guard_aftersales_verdict_must_refund() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_prev_must_refund boolean;
  t_prev_basis varchar(24);
  t_prev_case uuid;
BEGIN
  IF NEW.supersedes_id IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT v.must_refund, v.must_refund_basis, v.case_id
    INTO t_prev_must_refund, t_prev_basis, t_prev_case
    FROM aftersales_intake_verdict v
   WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.supersedes_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '被顶替的受理判定不存在或不属于当前租户：supersedes_id=%', NEW.supersedes_id
      USING ERRCODE = 'no_data_found';
  END IF;
  -- 版本链必须落在**同一张售后单**上：跨单顶替会把两张单的判定接成一条链，
  -- 于是「这张单被判过几次」与「那张单被判过几次」各少一版，谁也读不全。
  -- ⚠️ 刻意**不**限制「只能顶替未顶替过的那一条」：重新判定的正常顺序就是
  --   先把上一条标记 superseded 再插新一条，加那条限制会让本模块自己的流程撞墙，
  --   而「一张单同一时刻只有一条生效判定」已经由上面的部分唯一索引保证了。
  IF t_prev_case IS DISTINCT FROM NEW.case_id THEN
    RAISE EXCEPTION '被顶替的判定属于另一张售后单（%），不能接到本单（%）上来',
      t_prev_case, NEW.case_id USING ERRCODE = 'check_violation';
  END IF;

  IF t_prev_basis = 'PLATFORM_DECLARED' AND t_prev_must_refund AND NOT NEW.must_refund THEN
    RAISE EXCEPTION
      '平台声明的必退情形不可在本地降级为非必退（原判定 verdict_id=%）：平台强制、收到即退，'
      '要推翻只能改平台侧，不能在本地改判定', NEW.supersedes_id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_verdict_must_refund
  BEFORE INSERT ON aftersales_intake_verdict
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_verdict_must_refund();

-- 运行角色能写的那一列（superseded_at）也只能**单向**写一次：
-- 顶替时刻一旦写下就不许改，也不许被抹回 NULL ——
-- 否则「哪一版生效」就成了一个可以被悄悄翻转的开关，
-- 而版本链的意义恰恰在于它一旦写下就不可篡改。
CREATE FUNCTION guard_aftersales_verdict_supersede_once() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.superseded_at IS DISTINCT FROM OLD.superseded_at THEN
    IF OLD.superseded_at IS NOT NULL THEN
      RAISE EXCEPTION '受理判定已于 % 被顶替，顶替时刻不可改写或抹回：verdict_id=%',
        OLD.superseded_at, OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.superseded_at IS NULL THEN
      RAISE EXCEPTION '顶替时刻不可抹回 NULL：verdict_id=%', OLD.id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_verdict_supersede_once
  BEFORE UPDATE ON aftersales_intake_verdict
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_verdict_supersede_once();

-- ===========================================================================
-- 五、多租户：RLS + 授权（与 V156 第九节、V157 同一套写法）
-- ===========================================================================
-- ⚠️ 本模块尤其不能省：**判定凭据里冻结着政策参数原文与必退结论**，
--   漏掉租户谓词就是跨租户泄露 —— A 家会看到 B 家的必退口径与判责风险。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'aftersales_reply_sla','aftersales_owner_roster','aftersales_intake_verdict'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  aftersales_reply_sla,aftersales_owner_roster,aftersales_intake_verdict TO trade_app;

-- 授权在前、收回在后（V156 第九节的顺序）。
-- 判定凭据追加式：受理判定是纠纷举证的原材料，改掉或删掉一行等于毁掉证据。
REVOKE UPDATE,DELETE ON aftersales_intake_verdict FROM trade_app;
-- ⚠️ **只授回 superseded_at 一列的 UPDATE**，理由与 V156 第 463-473 行完全同源：
--   PostgreSQL 里表级 UPDATE 蕴含该表**每一列**的 UPDATE 权限，
--   在表级 GRANT 之后写 `REVOKE UPDATE(must_refund)` 是**空操作**；
--   要真的收住必须先收回表级 UPDATE 再逐列授回。
--   重新判定需要「把上一条标记成已顶替」，这个动作必须能做（否则版本链根本立不起来），
--   但**判定内容本身一律不可改写** —— 收窄到一列正是这个分界。
GRANT UPDATE(superseded_at) ON aftersales_intake_verdict TO trade_app;
-- 名册可改（关停接单、改权重），但不可物理删除（删了就没法回答「这批单是谁派的」）。
REVOKE DELETE ON aftersales_owner_roster FROM trade_app;
-- SLA 表可改，但触发器只允许收口 effective_to —— 收紧在 DB 层而非只靠服务层自觉。

-- 本迁移**不 ALTER 任何既有表**：aftersales_case / aftersales_owner_assignment（V156）、
-- aftersales_case_note / aftersales_authorization / aftersales_service_ticket（V156）、
-- aftersales_message / aftersales_attachment / aftersales_case_line（V157）、
-- aftersales_reason_*（V158）全部只读或经既有写入所有者写。
-- 本模块对它们的全部依赖都是 SELECT，跨车道 DDL 回归面 = 0。
