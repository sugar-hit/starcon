-- 6.6.1.2 打样与样品管理：管理打样与样品 —— 样品申请、样品来源（供应商 / 自有产）、
-- 样品物流、确认结果（通过 / 修改 / 放弃）、样品成本。
--
-- 蓝图 06-purchase-and-supply.md 6.6.1.2 原文：
--   职责：管理打样与样品：样品申请、样品来源（供应商/自有产）、样品物流、确认结果（通过/修改/放弃）、
--         样品成本；与达人寄样（14.5.1.2）区分打样与营销样品。
--   输入：打样申请；供应商/工厂；样品信息与图片。
--   输出：打样单 + 样品台账 + 确认记录；样品成本。
--   关系：上游 = 6.6.1.1、6.1.2.1 档口；下游 = 6.6.2.1 试产、12 费用（样品成本）。
--         **打样费用是新品开发的真实成本**，需进费用归集，否则新品利润被高估。
--
-- 归属：**trade 服务**（卷 06 后端主责服务 = trade + catalog，取列表第一项 = trade）。
-- 后端命名空间 /trade/development-samples；前端路由 /purchase/development/samples。
--
-- ===========================================================================
-- 一、模块边界：本文件**不碰任何既有表**，也不碰 catalog / channel / warehouse 的库
-- ===========================================================================
--   * **6.6.1.1 开发立项（trade 服务，同一个库，已交付，V257）**：
--     本模块要挂到开发立项上 ⇒ **只读 + 逻辑引用，零 ALTER**。
--     `development_sample_order.development_project_id` **不建外键**、**不加约束**、
--     **不改它的触发器**；同时存 `project_no` / `project_name` **快照**
--     （跨模块事实的展示快照口径，与 6.2.2.2 对 6.2.2.1 的处理同构：
--     逻辑引用 + 快照列，事实归上游所有）。
--     读取走**同库 SELECT**（同一个 trade 库，读它不违反「不跨服务读」纪律），
--     但**只 SELECT**：不改它的任何列。
--   * **6.1.2.1 档口与货源池（trade 服务，同一个库，已交付，V233）**：
--     `supplier_source_stall` 是**它的**事实 ⇒ 本模块**只 SELECT 引用 + 快照**，
--     **不建指向它的外键、不加约束、不 ALTER**。
--   * **供应商（catalog 独立库，6.1.1.x）**：`supplier_ref_id` + `supplier_code` +
--     `supplier_name` **逻辑引用 + 展示快照**，**不建跨库 FK**；
--     走 catalog 已有的 `GET /catalog/supply/vendors` 与 trade 已有的 `SupplierPartyQueryPort`。
--   * **样品图片（catalog 媒体库 3.4.1.1，已交付）**：`media_ref_id` + 元数据快照，
--     **不建跨库 FK、不跨库写**。catalog 侧唯一可读端点是 `GET /catalog/media`（已交付）。
--   * **12 费用（财务归集）**：**finance 服务在本仓不存在**
--     （backend/services/ 下只有 catalog / channel / crm / identity / trade / warehouse）
--     ⇒ 样品成本落 **trade 本库**（谁写谁负责），但「进费用归集 / 入账」这一步
--     **如实标 available=false + ownerModule='12 费用'**。
--     🔴 本文件**不造一张「费用」表去冒充 12 财务的交付物**，
--        并且用 CHECK 把「已入账」这件事在**数据库层钉死为不可能**
--        （`development_sample_cost_not_posted`）——「不要假装已经入账」。
--   * **6.6.2.1 试产单（未开发）** ⇒ 本模块**不代它建单**，如实标 available=false。
--   * **10 物流**：样品物流**没有现成的上游**（物流在 10 卷 = channel + trade + warehouse），
--     本模块**只记物流快照**（发货单号 / 承运商 / 物流状态 / 发出时刻），
--     **真实物流轨迹**如实标 `tracking_available=false` + `tracking_owner_module='10 物流'`。
--     **不硬造一个「物流已接入」的假接口。**
--   * **14.5.1.2 达人寄样**：属 14 卷（channel + reporting），**是营销样品，不是本模块的范围**。
--
-- ===========================================================================
-- 二、九张业务表的职责划分
-- ===========================================================================
--   1. `development_sample_order` —— **打样单（一次打样活动）**：编号、来源、用途、状态、
--      币种、申请人、期望到样时间。**一张打样单含多个样品。**
--   2. `development_sample_order_transition` —— 打样单**状态迁移事件**（不可改写的留痕）。
--   3. `development_sample` —— **样品台账（样品实体，一个样品一件）**：挂在打样单下，
--      带规格、数量、状态。**台账是「件」，打样单是「次」—— 1 打样单 : N 样品。**
--   4. `development_sample_transition` —— 样品**制作进度事件**（请求 → 打样中 → 已发出 → 已到样）。
--   5. `development_sample_confirmation` —— **确认记录**（通过 / 修改 / 放弃），append-only。
--   6. `development_sample_shipment` —— **样品物流快照**（发货单号 / 承运商 / 物流状态 / 发出时刻）。
--   7. `development_sample_media` —— **样品图片**（catalog 媒体库逻辑引用 + 快照元数据）。
--   8. `development_sample_cost` —— **样品成本头**（一个打样单一张，一个币种）。
--   9. `development_sample_cost_line` —— **样品成本明细行**。
--
-- ===========================================================================
-- 三、🔴 打样 vs 达人寄样：受控的「来源 / 用途」维度（蓝图的明文要求）
-- ===========================================================================
--   蓝图职责句点名的「样品来源（供应商 / 自有产）」与「与达人寄样（14.5.1.2）区分
--   打样与营销样品」是**两件事**，必须一起落成受控词表：
--
--   * `source_type`（来源，封闭三值）：
--       SUPPLIER      供应商打样
--       OWN_FACTORY   自有产打样
--       STALL         档口打样
--   * `usage_type`（用途，**只有一个值**）：
--       DEVELOPMENT   开发打样
--
--   🔴 **usage_type 的词表里没有「达人寄样」，也没有「其它」**。
--      达人寄样是**营销样品**，属 14.5.1.2（14 卷），它既不是「开发打样」，
--      也不该由本模块代管。留一个「其它」桶就等于把营销寄样塞进来 ——
--      而那正是蓝图担心的「营销费用污染开发成本 ⇒ 新品利润被高估」。
--      DB CHECK 会在**存储层**拒绝任何非 DEVELOPMENT 的用途。
--
--   🔴 **「打样 / 营销样品」的区分不是靠 source_type 做的**：
--      达人寄样同样来自供应商，光看来源分不出来。**靠 usage_type 分**。
--      本表 source_type 与 usage_type **成对出现**（服务层校验 + 触发器钉死
--      样品与其打样单的 source_type / usage_type 必须一致）。
--
-- ===========================================================================
-- 四、状态机（服务层 + 数据库层**两处**都钉死）
-- ===========================================================================
--   打样单：REQUESTED 已申请 → IN_PROGRESS 进行中 → COMPLETED 已完成 → CLOSED 已关闭；
--           任意非终态可 CANCELLED 已取消。**CANCELLED / CLOSED 是终态**（不提供「恢复」）。
--
--   样品：  REQUESTED 已申请 → MAKING 打样中 → SHIPPED 已发出 → RECEIVED 已到样
--           → CONFIRMED 已确认（通过）
--           → REVISING 修改中（修改）→ MAKING（重新打样）/ CONFIRMED（改后通过）
--           → ABANDONED 已放弃（放弃）
--           **CONFIRMED / ABANDONED 是终态。**
--
--   🔴 **确认结果（通过 / 修改 / 放弃）必须由一条不可改写的「确认记录」授权**：
--      数据库守卫触发器要求「状态变到 CONFIRMED / REVISING / ABANDONED」时，
--      样品台账里必须**先**有一条 result 对得上的确认记录，否则直接拒绝 ——
--      也就是说「改了状态却没有确认记录」在数据库层就做不到。
--   🔴 **每次状态迁移都要落一条不可改写的事件**（谁、什么时候、从哪到哪、为什么）。
--      确认记录与进度事件都是 append-only（applied_at 插入时恒为 NULL，
--      只由守卫触发器写；UPDATE / DELETE 被触发器挡 + REVOKE DELETE）。
--
-- ===========================================================================
-- 五、单位与时间纪律
-- ===========================================================================
--   * 金额一律 `numeric(19,4)`（**禁浮点**：Java 侧 BigDecimal）。
--   * 数量 `numeric(19,6)`。
--   * **一张打样单一个币种**：成本头有 currency，明细行的 currency 必须与它一致
--     （触发器钉死）—— 绝不相加不同币种的数。
--   * 时间列一律 `timestamptz`；Java 侧用 `OffsetDateTime` 绑定，
--     SQL 的 values 位置写 `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime...)`
--     会静默偏 −28800s）。
--
-- ===========================================================================
-- 1. 打样单（一次打样活动）
-- ===========================================================================
CREATE TABLE development_sample_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- **打样单号**：租户内唯一、可读（SO-20261021-a1b2c3d4）。
  -- 蓝图加粗句「把开发-打样-试产-量产纳入同一编号体系」的落点之一。
  order_no varchar(64) NOT NULL CHECK(btrim(order_no) <> ''),
  -- ---------------------------------------------------------------------
  -- 上游 6.6.1.1 开发立项：**逻辑引用 + 展示快照，零外键、零约束、零 ALTER**
  -- ---------------------------------------------------------------------
  development_project_id uuid NOT NULL,
  -- 立项编号 / 名称快照：catalog 改名（这里同理，上游改名）后仍能显示当时的立项。
  project_no varchar(64) NOT NULL CHECK(btrim(project_no) <> ''),
  project_name varchar(200) NOT NULL CHECK(btrim(project_name) <> ''),
  -- ---------------------------------------------------------------------
  -- 样品来源 / 用途（受控词表，**没有「其它」桶**）
  -- ---------------------------------------------------------------------
  source_type varchar(24) NOT NULL
    CHECK(source_type IN ('SUPPLIER','OWN_FACTORY','STALL')),
  -- **用途只有一个值**：DEVELOPMENT 开发打样。
  -- 达人寄样（14.5.1.2）**不在本模块** —— 它是营销样品，混进来会污染开发成本。
  usage_type varchar(24) NOT NULL DEFAULT 'DEVELOPMENT'
    CHECK(usage_type = 'DEVELOPMENT'),
  -- 状态：五个，单向推进。
  status varchar(24) NOT NULL DEFAULT 'REQUESTED'
    CHECK(status IN ('REQUESTED','IN_PROGRESS','COMPLETED','CLOSED','CANCELLED')),
  -- ---------------------------------------------------------------------
  -- 来源实体（**只读引用 + 快照**；catalog 是独立库 ⇒ 无跨库 FK）
  -- ---------------------------------------------------------------------
  -- 供应商（catalog 6.1.1.x，SUPPLIER 角色 id）。
  supplier_ref_id uuid,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',
  -- 档口（6.1.2.1 `supplier_source_stall`，**同一个 trade 库**）：
  -- 只 SELECT 引用 + 快照，**不建指向它的外键**（它是 6.1.2.1 的事实）。
  stall_id uuid,
  stall_code varchar(64) NOT NULL DEFAULT '',
  stall_name varchar(200) NOT NULL DEFAULT '',
  -- ---------------------------------------------------------------------
  -- 币种：一张单一个币种（成本明细行必须与它一致）
  -- ---------------------------------------------------------------------
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 申请人（打样申请的提出方）。
  requester_id uuid NOT NULL,
  requester_name varchar(100) NOT NULL DEFAULT '',
  -- 申请时刻 / 期望到样时刻。
  requested_at timestamptz NOT NULL,
  expected_delivery_at timestamptz,
  -- 打样用途说明（自由文本：这一轮要验证什么）。
  purpose_note text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, order_no),
  -- 🔴 来源与来源实体必须自洽：
  --   SUPPLIER    ⇒ 必须挂了供应商；
  --   OWN_FACTORY ⇒ 供应商与档口**都不得**挂（自有产没有外部来源实体）；
  --   STALL       ⇒ 必须挂了档口。
  CONSTRAINT development_sample_order_source_consistent CHECK(
    (source_type = 'SUPPLIER' AND supplier_ref_id IS NOT NULL)
    OR (source_type = 'OWN_FACTORY' AND supplier_ref_id IS NULL AND stall_id IS NULL)
    OR (source_type = 'STALL' AND stall_id IS NOT NULL)
  ),
  -- 🔴 用了供应商就得有供应商的展示快照（只挂一个裸 id，界面上显示什么？）。
  CONSTRAINT development_sample_order_supplier_snapshot CHECK(
    supplier_ref_id IS NULL OR btrim(supplier_name) <> ''
  ),
  CONSTRAINT development_sample_order_stall_snapshot CHECK(
    stall_id IS NULL OR btrim(stall_name) <> ''
  )
);

-- ===========================================================================
-- 2. 打样单状态迁移事件（不可改写的留痕）
-- ===========================================================================
CREATE TABLE development_sample_order_transition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  from_status varchar(24) NOT NULL
    CHECK(from_status IN ('REQUESTED','IN_PROGRESS','COMPLETED','CLOSED','CANCELLED')),
  to_status varchar(24) NOT NULL
    CHECK(to_status IN ('REQUESTED','IN_PROGRESS','COMPLETED','CLOSED','CANCELLED')),
  -- **为什么**（必填）：一句「推进一下」不是留痕。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  actor_id uuid NOT NULL,
  actor_name varchar(100) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,
  -- 由状态守卫触发器写入：这条事件已经被哪次状态变更用掉了。
  -- 插入时恒为 NULL（见 guard_development_sample_order_transition_insert）。
  applied_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, order_id) REFERENCES development_sample_order(tenant_id, id) ON DELETE RESTRICT,
  -- 不能自己转自己。
  CONSTRAINT development_sample_order_transition_distinct CHECK(from_status <> to_status)
);

-- ===========================================================================
-- 3. 样品台账（样品实体，**一个样品一件**）
-- ===========================================================================
CREATE TABLE development_sample (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- **样品编号**：租户内唯一（SP-20261021-a1b2c3d4）。
  sample_no varchar(64) NOT NULL CHECK(btrim(sample_no) <> ''),
  -- **归属打样单**（本模块自己的表 ⇒ 正常外键）。
  order_id uuid NOT NULL,
  -- 立项引用与快照（与打样单同口径，**逻辑引用 + 快照**，零外键）。
  development_project_id uuid NOT NULL,
  project_no varchar(64) NOT NULL CHECK(btrim(project_no) <> ''),
  project_name varchar(200) NOT NULL CHECK(btrim(project_name) <> ''),
  -- 来源 / 用途（与打样单**必须一致** —— 由 guard_development_sample_source_consistent 钉死）。
  source_type varchar(24) NOT NULL
    CHECK(source_type IN ('SUPPLIER','OWN_FACTORY','STALL')),
  usage_type varchar(24) NOT NULL DEFAULT 'DEVELOPMENT'
    CHECK(usage_type = 'DEVELOPMENT'),
  -- 状态：七个。
  status varchar(24) NOT NULL DEFAULT 'REQUESTED'
    CHECK(status IN ('REQUESTED','MAKING','SHIPPED','RECEIVED','CONFIRMED','REVISING','ABANDONED')),
  -- 样品规格（颜色 / 尺寸 / 材质 …）+ 规格值。
  spec_name varchar(120) NOT NULL DEFAULT '',
  spec_value varchar(200) NOT NULL DEFAULT '',
  -- 数量：**NUMERIC(19,6)**（禁浮点）。
  quantity numeric(19,6) NOT NULL DEFAULT 1 CHECK(quantity > 0),
  -- 该样品的币种（与打样单一致；成本明细行必须与它一致）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 确认轮次：第几轮打样（第一次 = 1）。「修改」后重新打样 ⇒ 轮次 +1。
  revision_round int NOT NULL DEFAULT 1 CHECK(revision_round >= 1),
  remark text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, sample_no),
  FOREIGN KEY(tenant_id, order_id) REFERENCES development_sample_order(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 4. 样品制作进度事件（不可改写的留痕）
-- ===========================================================================
CREATE TABLE development_sample_transition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sample_id uuid NOT NULL,
  from_status varchar(24) NOT NULL
    CHECK(from_status IN ('REQUESTED','MAKING','SHIPPED','RECEIVED','CONFIRMED','REVISING','ABANDONED')),
  to_status varchar(24) NOT NULL
    CHECK(to_status IN ('REQUESTED','MAKING','SHIPPED','RECEIVED','CONFIRMED','REVISING','ABANDONED')),
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  actor_id uuid NOT NULL,
  actor_name varchar(100) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,
  applied_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, sample_id) REFERENCES development_sample(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT development_sample_transition_distinct CHECK(from_status <> to_status)
);

-- ===========================================================================
-- 5. 确认记录（通过 / 修改 / 放弃）—— 不可改写的留痕
-- ===========================================================================
CREATE TABLE development_sample_confirmation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sample_id uuid NOT NULL,
  -- 确认结果：**通过 / 修改 / 放弃**（蓝图职责句点名的三个结果）。
  --   PASSED    通过 —— 样品确认通过，样品进入终态 CONFIRMED。
  --   MODIFY    修改 —— 样品要改，样品回到 REVISING（可再编辑、可重新打样）。
  --   ABANDONED 放弃 —— 样品作废，样品进入终态 ABANDONED。
  result varchar(16) NOT NULL CHECK(result IN ('PASSED','MODIFY','ABANDONED')),
  -- **第几次确认**（1、2、3 …）。
  -- 🔴 刻意**不叫** revision_round：样品的 revision_round 是「第几轮**打样**」，
  --    这里的是「第几次**确认**」—— 两件事，混用一个名字必然对不上。
  --    （同一样品在「修改中 → 直接通过」这条路上会在**同一轮打样**里确认两次。）
  confirm_seq int NOT NULL CHECK(confirm_seq >= 1),
  -- **为什么**（必填）。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  -- 修改意见：**result='MODIFY' 时必填** —— 只说「要改」不说改什么，
  -- 下一轮打样的人还是得猜。
  issue_note text NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  actor_name varchar(100) NOT NULL DEFAULT '',
  confirmed_at timestamptz NOT NULL,
  applied_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一样品的第 N 次确认只有一条。
  UNIQUE(tenant_id, sample_id, confirm_seq),
  FOREIGN KEY(tenant_id, sample_id) REFERENCES development_sample(tenant_id, id) ON DELETE RESTRICT,
  -- 🔴 「修改」必须写清楚改什么。
  CONSTRAINT development_sample_confirmation_issue_required CHECK(
    result <> 'MODIFY' OR btrim(issue_note) <> ''
  )
);

-- ===========================================================================
-- 6. 样品物流快照
-- ===========================================================================
--   🔴 **只记快照，不假装已接通物流平台**：真实物流轨迹在 10 卷
--      （channel + trade + warehouse），本模块**没有**可读的真实轨迹来源。
--      ⇒ `tracking_available` 恒为 false，`tracking_owner_module` 点名 '10 物流'。
--      发货单号 / 承运商 / 物流状态 / 发出时刻是**人工登记的快照**，
--      它是货真的发出去了的凭据，但不是「平台轨迹」。
CREATE TABLE development_sample_shipment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sample_id uuid NOT NULL,
  -- **发货单号**（快递单号）：必填 —— 没有单号，「发出去了」就没有凭据。
  tracking_no varchar(128) NOT NULL CHECK(btrim(tracking_no) <> ''),
  -- 承运商（代码 + 名称快照）。
  carrier_code varchar(32) NOT NULL DEFAULT '',
  carrier_name varchar(100) NOT NULL DEFAULT '',
  -- 物流状态（人工登记的快照口径，**不是**平台轨迹状态）。
  logistics_status varchar(16) NOT NULL DEFAULT 'SHIPPED'
    CHECK(logistics_status IN ('SHIPPED','IN_TRANSIT','DELIVERED','EXCEPTION','RETURNED')),
  -- 发出时刻 / 预计到达 / 实际签收。
  shipped_at timestamptz NOT NULL,
  expected_arrival_at timestamptz,
  delivered_at timestamptz,
  -- 🔴 真实物流轨迹**取不到**（10 物流未接线）⇒ 恒为 false + 点名归谁管。
  tracking_available boolean NOT NULL DEFAULT false,
  tracking_owner_module varchar(32) NOT NULL DEFAULT '10 物流',
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一样品的同一个发货单号只有一条（重发要换单号）。
  UNIQUE(tenant_id, sample_id, tracking_no),
  FOREIGN KEY(tenant_id, sample_id) REFERENCES development_sample(tenant_id, id) ON DELETE CASCADE,
  -- 签收了 ⇒ 必须有签收时刻。
  CONSTRAINT development_sample_shipment_delivered_consistent CHECK(
    logistics_status <> 'DELIVERED' OR delivered_at IS NOT NULL
  ),
  -- 🔴 「轨迹可查」与「归谁管」必须自洽：轨迹可查 ⇒ 不再由 10 物流兜底。
  CONSTRAINT development_sample_shipment_tracking_consistent CHECK(
    tracking_available OR btrim(tracking_owner_module) <> ''
  )
);

-- ===========================================================================
-- 7. 样品图片（catalog 媒体库 3.4.1.1：**逻辑引用 + 快照元数据**）
-- ===========================================================================
--   🔴 **不建跨库 FK、不跨库写**：catalog 的 media_* 表是**它**的写入所有权。
--      本表只存 media id + 一份元数据快照（catalog 改名 / 换存储键后仍能显示当时那一张）。
CREATE TABLE development_sample_media (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sample_id uuid NOT NULL,
  -- catalog 媒体库的对象 id（**逻辑引用**，无跨库外键）。
  media_ref_id uuid NOT NULL,
  -- 元数据快照。
  media_name varchar(200) NOT NULL DEFAULT '',
  media_type varchar(16) NOT NULL DEFAULT '',
  file_format varchar(16) NOT NULL DEFAULT '',
  mime_type varchar(100) NOT NULL DEFAULT '',
  content_sha256 varchar(64) NOT NULL DEFAULT '',
  byte_size numeric(19,6),
  width_px int,
  height_px int,
  -- catalog 侧的存储键快照（不建跨库外键，只是「当时那张图存在哪」的凭据）。
  storage_key varchar(200) NOT NULL DEFAULT '',
  -- 挂在样品上的时刻。
  uploaded_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一样品挂同一个媒体对象只有一条。
  UNIQUE(tenant_id, sample_id, media_ref_id),
  FOREIGN KEY(tenant_id, sample_id) REFERENCES development_sample(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT development_sample_media_size_consistent CHECK(
    byte_size IS NULL OR byte_size >= 0
  )
);

-- ===========================================================================
-- 8. 样品成本（成本头：一张打样单一张，**一个币种**）
-- ===========================================================================
CREATE TABLE development_sample_cost (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 币种：一张单一个币种（明细行必须与它一致，触发器钉死）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 合计金额：由明细行自动维护（guard_development_sample_cost_total 触发器）。
  total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_amount >= 0),
  -- 🔴 **进费用归集 / 入账这一步做不到**：12 费用（finance 服务）**在本仓不存在**。
  --    ⇒ expense_posted **恒为 false**（CHECK 钉死在数据库层），
  --      owner_module 点名 '12 费用'，note 写清影响。
  --    蓝图加粗句「打样费用是新品开发的真实成本，需进费用归集，否则新品利润被高估」
  --    —— 本模块如实记下**成本事实**，但**不假装已经入账**。
  expense_posted boolean NOT NULL DEFAULT false,
  expense_owner_module varchar(32) NOT NULL DEFAULT '12 费用',
  expense_note text NOT NULL DEFAULT '',
  posted_at timestamptz,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个打样单一张成本头。
  UNIQUE(tenant_id, order_id),
  FOREIGN KEY(tenant_id, order_id) REFERENCES development_sample_order(tenant_id, id) ON DELETE CASCADE,
  -- 🔴 **不假装已经入账**：12 费用未交付前，任何成本行都不许声称「已入账」。
  --    12 费用交付后由那条车道的新迁移放开本约束。
  CONSTRAINT development_sample_cost_not_posted CHECK(expense_posted = false),
  -- 「已入账」必须有时刻（为将来放开做准备，语义先钉死）。
  CONSTRAINT development_sample_cost_posted_at_consistent CHECK(
    expense_posted = false OR posted_at IS NOT NULL
  )
);

-- ===========================================================================
-- 9. 样品成本明细行
-- ===========================================================================
CREATE TABLE development_sample_cost_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  cost_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 成本类型（**封闭词表，无「其它」**）：
  --   SAMPLE_FEE  打样费（供应商 / 工厂收的打样费）
  --   MATERIAL    物料费（打样用掉的材料）
  --   TOOLING     模具 / 治具费
  --   FREIGHT     物流费（样品往返运费）
  --   INSPECTION  检品 / 验货费
  cost_type varchar(24) NOT NULL
    CHECK(cost_type IN ('SAMPLE_FEE','MATERIAL','TOOLING','FREIGHT','INSPECTION')),
  -- 金额：**NUMERIC(19,4)**，禁浮点。
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  -- 币种：必须与成本头一致（触发器钉死 —— 不同币种的数绝不相加）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  description varchar(200) NOT NULL DEFAULT '',
  -- 发生时刻（timestamptz；Java 侧用 OffsetDateTime 绑定）。
  incurred_at timestamptz NOT NULL,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, cost_id) REFERENCES development_sample_cost(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 10. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================
-- 10.1 迁移 / 进度事件 / 确认记录：**插入时 applied_at 恒为 NULL** ——
--      它只能被状态守卫触发器写上去。不这样的话，应用可以在插入时就伪造
--      「这条事件已经被用掉了」，守卫随后会认为「没有可用的事件」而拒绝变更。
CREATE FUNCTION guard_development_sample_order_transition_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.applied_at := NULL;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_order_transition_insert BEFORE INSERT ON development_sample_order_transition
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_order_transition_insert();

CREATE FUNCTION guard_development_sample_transition_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.applied_at := NULL;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_transition_insert BEFORE INSERT ON development_sample_transition
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_transition_insert();

CREATE FUNCTION guard_development_sample_confirmation_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.applied_at := NULL;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_confirmation_insert BEFORE INSERT ON development_sample_confirmation
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_confirmation_insert();

-- 10.2 三张留痕表**不可改写、不可删**（只允许把 applied_at 从 NULL 标成一次时刻）。
CREATE FUNCTION guard_development_sample_order_transition_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '打样单状态迁移事件不可删除（%）：删掉它就等于抹掉「当时为什么这么转」的证据', OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.order_id IS DISTINCT FROM OLD.order_id
     OR NEW.from_status IS DISTINCT FROM OLD.from_status
     OR NEW.to_status IS DISTINCT FROM OLD.to_status
     OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR (OLD.applied_at IS NOT NULL AND NEW.applied_at IS DISTINCT FROM OLD.applied_at) THEN
    RAISE EXCEPTION '打样单状态迁移事件不可改写（%）', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_order_transition_immutable BEFORE UPDATE OR DELETE ON development_sample_order_transition
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_order_transition_immutable();

CREATE FUNCTION guard_development_sample_transition_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '样品制作进度事件不可删除（%）：删掉它就等于抹掉「当时它到哪一步了」的证据', OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.sample_id IS DISTINCT FROM OLD.sample_id
     OR NEW.from_status IS DISTINCT FROM OLD.from_status
     OR NEW.to_status IS DISTINCT FROM OLD.to_status
     OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR (OLD.applied_at IS NOT NULL AND NEW.applied_at IS DISTINCT FROM OLD.applied_at) THEN
    RAISE EXCEPTION '样品制作进度事件不可改写（%）', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_transition_immutable BEFORE UPDATE OR DELETE ON development_sample_transition
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_transition_immutable();

CREATE FUNCTION guard_development_sample_confirmation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '样品确认记录不可删除（%）：删掉它就等于抹掉「当时为什么通过 / 修改 / 放弃」的证据', OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.sample_id IS DISTINCT FROM OLD.sample_id
     OR NEW.result IS DISTINCT FROM OLD.result
     OR NEW.confirm_seq IS DISTINCT FROM OLD.confirm_seq
     OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.issue_note IS DISTINCT FROM OLD.issue_note
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
     OR NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR (OLD.applied_at IS NOT NULL AND NEW.applied_at IS DISTINCT FROM OLD.applied_at) THEN
    RAISE EXCEPTION '样品确认记录不可改写（%）', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_confirmation_immutable BEFORE UPDATE OR DELETE ON development_sample_confirmation
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_confirmation_immutable();

-- 10.3 🔴 **打样单状态守卫**：数据库层钉死状态机。
--     * 只放行 REQUESTED → IN_PROGRESS → COMPLETED → CLOSED 与任意非终态 → CANCELLED；
--     * CANCELLED / CLOSED 是终态（没有出边，不提供「恢复」）；
--     * **每次状态变更都必须有一条对应的迁移事件**（谁 / 何时 / 从哪到哪 / 为什么）；
--     * **COMPLETED 要求这张单下的样品全部落到终态**（CONFIRMED / ABANDONED）——
--       样品还在改还要改的单子不许说「已完成」。
CREATE FUNCTION guard_development_sample_order_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_allowed boolean := false;
  v_event_id uuid;
  v_pending int;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;

  v_allowed := CASE OLD.status
    WHEN 'REQUESTED'   THEN NEW.status IN ('IN_PROGRESS','CANCELLED')
    WHEN 'IN_PROGRESS' THEN NEW.status IN ('COMPLETED','CANCELLED')
    WHEN 'COMPLETED'   THEN NEW.status IN ('CLOSED')
    ELSE false
  END;
  IF NOT v_allowed THEN
    RAISE EXCEPTION '打样单状态不允许从 % 迁移到 %（已关闭 / 已取消是终态，不提供恢复）', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  SELECT t.id INTO v_event_id
    FROM development_sample_order_transition t
   WHERE t.tenant_id = NEW.tenant_id
     AND t.order_id = NEW.id
     AND t.from_status = OLD.status
     AND t.to_status = NEW.status
     AND t.applied_at IS NULL
   ORDER BY t.created_at DESC, t.id DESC
   LIMIT 1;
  IF NOT FOUND THEN
    RAISE EXCEPTION '打样单状态变更缺少对应迁移事件（% → %）：每次状态迁移都必须落一条不可改写的记录', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  -- 完成前，样品必须全部落到终态。
  IF NEW.status = 'COMPLETED' THEN
    SELECT count(*) INTO v_pending
      FROM development_sample s
     WHERE s.tenant_id = NEW.tenant_id
       AND s.order_id = NEW.id
       AND s.status NOT IN ('CONFIRMED','ABANDONED');
    IF v_pending > 0 THEN
      RAISE EXCEPTION '打样单还有 % 件样品没到终态（已确认 / 已放弃），不能标记为已完成', v_pending
        USING ERRCODE='23514';
    END IF;
  END IF;

  UPDATE development_sample_order_transition SET applied_at = now() WHERE id = v_event_id;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_order_status_guard BEFORE UPDATE ON development_sample_order
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_order_status();

-- 10.4 🔴 **样品状态守卫**：数据库层钉死状态机。
--     * 进度边（请求 → 打样中 → 已发出 → 已到样；修改中 → 重新打样）
--       必须先有一条对应的**进度事件**；
--     * 确认边（→ 已确认 / 修改中 / 已放弃）必须先有一条 result 对得上的**确认记录**；
--     * CONFIRMED / ABANDONED 是终态（没有出边）；
--     * 「改了状态却没有事件 / 确认记录」在数据库层就做不到。
CREATE FUNCTION guard_development_sample_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_allowed boolean := false;
  v_event_id uuid;
  v_result varchar(16);
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;

  v_allowed := CASE OLD.status
    WHEN 'REQUESTED' THEN NEW.status IN ('MAKING','ABANDONED')
    WHEN 'MAKING'    THEN NEW.status IN ('SHIPPED','ABANDONED')
    WHEN 'SHIPPED'   THEN NEW.status IN ('RECEIVED','ABANDONED')
    WHEN 'RECEIVED'  THEN NEW.status IN ('CONFIRMED','REVISING','ABANDONED')
    WHEN 'REVISING'  THEN NEW.status IN ('MAKING','CONFIRMED','ABANDONED')
    ELSE false
  END;
  IF NOT v_allowed THEN
    RAISE EXCEPTION '样品状态不允许从 % 迁移到 %（已确认 / 已放弃是终态，不提供恢复）', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  -- 确认边：必须有对应的确认记录（结果与目标状态必须对得上）。
  IF NEW.status IN ('CONFIRMED','REVISING','ABANDONED') THEN
    v_result := CASE NEW.status
      WHEN 'CONFIRMED' THEN 'PASSED'
      WHEN 'REVISING'  THEN 'MODIFY'
      ELSE 'ABANDONED'
    END;
    SELECT c.id INTO v_event_id
      FROM development_sample_confirmation c
     WHERE c.tenant_id = NEW.tenant_id
       AND c.sample_id = NEW.id
       AND c.result = v_result
       AND c.applied_at IS NULL
     ORDER BY c.confirmed_at DESC, c.id DESC
     LIMIT 1;
    IF NOT FOUND THEN
      RAISE EXCEPTION '样品状态变更到「%」缺少对应的确认记录（result=%s）：确认结果必须留一条不可改写的记录', NEW.status, v_result
        USING ERRCODE='23514';
    END IF;
    UPDATE development_sample_confirmation SET applied_at = now() WHERE id = v_event_id;
  ELSE
    -- 进度边：必须有对应的进度事件。
    SELECT t.id INTO v_event_id
      FROM development_sample_transition t
     WHERE t.tenant_id = NEW.tenant_id
       AND t.sample_id = NEW.id
       AND t.from_status = OLD.status
       AND t.to_status = NEW.status
       AND t.applied_at IS NULL
     ORDER BY t.occurred_at DESC, t.id DESC
     LIMIT 1;
    IF NOT FOUND THEN
      RAISE EXCEPTION '样品状态变更缺少对应进度事件（% → %）：每次状态迁移都必须落一条不可改写的记录', OLD.status, NEW.status
        USING ERRCODE='23514';
    END IF;
    UPDATE development_sample_transition SET applied_at = now() WHERE id = v_event_id;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_status_guard BEFORE UPDATE ON development_sample
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_status();

-- 10.5 打样单 / 样品的**身份列冻结**：编号、创建人、创建时刻是「这条记录是谁、
--     什么时候立的」的举证凭据，改编号会让历史事件与成本行指向一个不存在的东西。
CREATE FUNCTION guard_development_sample_order_identity_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.order_no IS DISTINCT FROM OLD.order_no
     OR NEW.development_project_id IS DISTINCT FROM OLD.development_project_id
     OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION '打样单「%」的身份列不可改写（编号 / 立项引用 / 创建人 / 创建时刻）：要换编号请新建一张打样单', OLD.order_no
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_order_identity_frozen BEFORE UPDATE ON development_sample_order
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_order_identity_frozen();

CREATE FUNCTION guard_development_sample_identity_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.sample_no IS DISTINCT FROM OLD.sample_no
     OR NEW.order_id IS DISTINCT FROM OLD.order_id
     OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION '样品「%」的身份列不可改写（编号 / 打样单 / 创建人 / 创建时刻）：要换编号请新建一条样品', OLD.sample_no
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_identity_frozen BEFORE UPDATE ON development_sample
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_identity_frozen();

-- 10.6 🔴 **样品的来源 / 用途必须与它的打样单一致**。
--     样品挂在打样单下，却写着另一个来源 ⇒ 成本归集与「这个样品从哪来」答不出来。
CREATE FUNCTION guard_development_sample_source_consistent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_source varchar(24);
  v_usage varchar(24);
  v_currency varchar(3);
BEGIN
  SELECT o.source_type, o.usage_type, o.currency
    INTO v_source, v_usage, v_currency
    FROM development_sample_order o
   WHERE o.tenant_id = NEW.tenant_id
     AND o.id = NEW.order_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '样品的打样单不存在（order_id=%）', NEW.order_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.source_type IS DISTINCT FROM v_source
     OR NEW.usage_type IS DISTINCT FROM v_usage
     OR NEW.currency IS DISTINCT FROM v_currency THEN
    RAISE EXCEPTION '样品的来源 / 用途 / 币种必须与打样单一致（单：% / % / %）', v_source, v_usage, v_currency
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_source_consistent BEFORE INSERT OR UPDATE ON development_sample
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_source_consistent();

-- 10.7 🔴 **成本明细的币种必须与成本头一致；成本头合计由明细行自动维护**。
--     不同币种的数绝不相加 —— 所以「一张单一个币种」是**数据库层**的纪律。
CREATE FUNCTION guard_development_sample_cost_line_currency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_currency varchar(3);
BEGIN
  SELECT c.currency INTO v_currency
    FROM development_sample_cost c
   WHERE c.tenant_id = NEW.tenant_id
     AND c.id = NEW.cost_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '成本明细的成本头不存在（cost_id=%）', NEW.cost_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.currency IS DISTINCT FROM v_currency THEN
    RAISE EXCEPTION '成本明细的币种（%）必须与成本头一致（%）：一张单一个币种，不同币种的数绝不相加', NEW.currency, v_currency
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_sample_cost_line_currency BEFORE INSERT OR UPDATE ON development_sample_cost_line
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_cost_line_currency();

CREATE FUNCTION guard_development_sample_cost_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_cost_id uuid;
  v_total numeric(19,4);
BEGIN
  v_cost_id := COALESCE(NEW.cost_id, OLD.cost_id);
  SELECT COALESCE(SUM(l.amount), 0) INTO v_total
    FROM development_sample_cost_line l
   WHERE l.tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
     AND l.cost_id = v_cost_id;
  UPDATE development_sample_cost
     SET total_amount = v_total, updated_at = now()
   WHERE tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
     AND id = v_cost_id;
  RETURN NULL;
END $$;
CREATE TRIGGER development_sample_cost_total_after
  AFTER INSERT OR UPDATE OR DELETE ON development_sample_cost_line
  FOR EACH ROW EXECUTE FUNCTION guard_development_sample_cost_total();

-- ===========================================================================
-- 11. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 打样单清单页：按状态 + 来源读（清单页主查询形状）。
CREATE INDEX development_sample_order_by_status ON development_sample_order(tenant_id, status, created_at desc, id);
CREATE INDEX development_sample_order_by_source ON development_sample_order(tenant_id, source_type, created_at desc, id);
-- 打样单清单页：按立项反查（「这个立项打了哪些样」）。
CREATE INDEX development_sample_order_by_project ON development_sample_order(tenant_id, development_project_id, created_at desc, id);
-- 打样单清单页：按供应商 / 档口反查。
CREATE INDEX development_sample_order_by_supplier ON development_sample_order(tenant_id, supplier_ref_id, created_at desc, id);
CREATE INDEX development_sample_order_by_stall ON development_sample_order(tenant_id, stall_id, created_at desc, id);
-- 迁移事件：按打样单 + 发生时刻读（详情页时间线）。
CREATE INDEX development_sample_order_transition_by_order ON development_sample_order_transition(tenant_id, order_id, occurred_at desc, id);
-- 样品台账：按打样单 + 登记顺序读（详情页第二段）。
CREATE INDEX development_sample_by_order ON development_sample(tenant_id, order_id, created_at, id);
-- 样品台账（跨单清单）：按状态 + 来源读。
CREATE INDEX development_sample_by_status ON development_sample(tenant_id, status, created_at desc, id);
CREATE INDEX development_sample_by_source ON development_sample(tenant_id, source_type, created_at desc, id);
-- 样品台账（跨单清单）：按立项反查。
CREATE INDEX development_sample_by_project ON development_sample(tenant_id, development_project_id, created_at desc, id);
-- 进度事件 / 确认记录：按样品 + 时刻读。
CREATE INDEX development_sample_transition_by_sample ON development_sample_transition(tenant_id, sample_id, occurred_at desc, id);
CREATE INDEX development_sample_confirmation_by_sample ON development_sample_confirmation(tenant_id, sample_id, confirmed_at desc, id);
-- 物流快照：按样品 + 发出时刻读。
CREATE INDEX development_sample_shipment_by_sample ON development_sample_shipment(tenant_id, sample_id, shipped_at desc, id);
-- 图片：按样品读。
CREATE INDEX development_sample_media_by_sample ON development_sample_media(tenant_id, sample_id, created_at, id);
-- 成本明细：按成本头读。
CREATE INDEX development_sample_cost_line_by_cost ON development_sample_cost_line(tenant_id, cost_id, incurred_at, id);

-- ===========================================================================
-- 12. 多租户：RLS + 授权（与 trade V1 / V92 / V257 同一套写法）
-- ===========================================================================
-- 九张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'development_sample_order','development_sample_order_transition',
    'development_sample','development_sample_transition',
    'development_sample_confirmation','development_sample_shipment',
    'development_sample_media','development_sample_cost','development_sample_cost_line'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  development_sample_order,development_sample_order_transition,
  development_sample,development_sample_transition,
  development_sample_confirmation,development_sample_shipment,
  development_sample_media,development_sample_cost,development_sample_cost_line TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  development_sample_order,development_sample_order_transition,
  development_sample,development_sample_transition,
  development_sample_confirmation,development_sample_shipment,
  development_sample_media,development_sample_cost,development_sample_cost_line TO starcon_app;
-- 三张留痕表**不可删**：删掉它就等于抹掉「当时为什么这么转 / 为什么这么确认」的证据。
REVOKE DELETE ON development_sample_order_transition FROM trade_app;
REVOKE DELETE ON development_sample_transition FROM trade_app;
REVOKE DELETE ON development_sample_confirmation FROM trade_app;
-- 打样单**不提供 DELETE**：它挂着样品、成本行与全部留痕，
-- 删除会抹掉「这个打样经历过什么」的证据。
REVOKE DELETE ON development_sample_order FROM trade_app;

-- ---------------------------------------------------------------------------
-- 13. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V233 / V257 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不写别的模块的表**：本文件只建自己的九张表；
--     6.6.1.1（development_project）、6.1.2.1（supplier_source_stall）
--     只被**只读引用 + 快照**，一个字节都不碰；
--     catalog 的 media_* / party 表**不碰也不建跨库外键**（那是它们的写入所有权）。
--   * 新增函数全部带 development_sample_ 前缀，与 V1 / V25 / V92 / V233 / V257
--     的既有函数名不冲突。
