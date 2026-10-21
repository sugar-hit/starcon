-- 11.4.1.2 证据与申诉（卷 11 售后与退货 / L3 11.4 平台纠纷与客诉）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：组织举证材料（物流签收、沟通记录、商品照片、检测报告）打包提交申诉，记录平台回复与结果。
--   输入：举证材料；申诉文本（可用模板）；提交动作。
--   输出：申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。
--   关系：上游 = 11.4.1.1；下游 = 2 平台接口、16 合规（账号绩效）。
--   **举证材料必须来自系统记录**（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。
--
-- 归属：**trade 服务**（与上游 11.4.1.1 同服务 —— 举证清单 `platform_dispute_evidence_item`
-- 与纠纷单 `platform_dispute` 是本模块的直接输入，同服务同库才能既读到又不跨库）。
--
-- 本迁移**只新建四张表与触发器、零 ALTER 零 DROP 既有表**：
--   * `platform_dispute_appeal`（申诉单头）
--   * `platform_dispute_appeal_material`（证据材料引用 —— 一行 = 一项材料）
--   * `platform_dispute_appeal_submission`（**待提交平台事实**：目标系统 + 目标键 + 载荷 + 幂等键 + 状态机 + 重试计数）
--   * `platform_dispute_appeal_event`（留痕，追加式）
--
-- ===========================================================================
-- 一、跨服务纪律：为什么「提交申诉」不调平台
-- ===========================================================================
--
-- 蓝图关系句写的是「下游 = 2 平台接口、16 合规（账号绩效）」。**grep 取证结论**：
--
--   * channel 服务**没有纠纷 / 申诉提交端点**。全服务源码里 `dispute` / `appeal` 的
--     命中只有一处：`ListingLifecycleService` 里的字符串常量 `"block_appeal_workflow"`
--     （listing 生命周期的一个**下架原因标签**，与纠纷申诉无关）。
--     channel 已交付的是平台目录 / 连接 / 店铺 / 同步任务 / 同步运行 / 回写 / webhook /
--     增量补拉 / 退货处置（10.7.x）—— **没有一件能替我们把申诉递到平台**。
--     （`ReturnDisposalController` 的 `/claim-bases/{id}/submit` 是 **10.7.1.3 销毁索赔依据**
--       的内部流转，产出的仍然是本服务自有事实，不是平台侧受理回执。）
--   * **16 合规（账号绩效）未交付** ⇒ 本模块不产出任何绩效指标，也不假装有。
--
-- ⇒ **本模块不假装能调平台**。它落的是**本服务侧的「待提交平台」事实**：
--   `platform_dispute_appeal_submission` 一行 = 一次提交尝试
--   （目标系统 + 目标键 + 载荷 + 幂等键 + 状态机 PENDING/SENT/ACKNOWLEDGED/FAILED
--     + 重试计数 + 下次重试时刻 + 上次错误）。
--   平台侧（或 channel 落地申诉适配器后）经**授权 API** 回写平台回复与结果
--   （`POST /trade/platform-dispute-appeals/{id}/platform-reply`）。
--   这与本仓 9.6.1.3 的 `fulfillment_dropship_payable_task`（带状态机 + jsonb 载荷 + 幂等键）
--   是同一族做法：**先把事实落下来，下游接得上就接，接不上时人看得见「还欠平台一次提交」**。
--
-- ===========================================================================
-- 二、时限判定：库层与服务层**共用同一个 SQL 函数**（本模块最容易漂移的地方）
-- ===========================================================================
--
-- 上游 11.4.1.1（V265）已经把「举证时限」钉死了：
--   * `platform_dispute.evidence_deadline` 是**真实的 timestamptz 列**；
--   * 守卫① `guard_platform_dispute_deadline` 挂在 `BEFORE INSERT OR UPDATE` 上：
--     超期且未提交举证 ⇒ 强制落成 `RESOLVED_LOST` + `DEADLINE_EXPIRED`；
--   * 守卫① 前半段：举证提交时刻晚于截止时刻 ⇒ 直接 RAISE（超期后不可再提交举证）。
--
-- 本模块的「提交申诉」**必须走同一套判定**，否则会出现两种假事实：
--   ① 「按时提交了却因超期被判负」—— 申诉在截止时刻前提交了，
--      但纠纷单的 `evidence_submitted_at` 仍是空，V265 守卫① 到点就把纠纷判负；
--   ② 「服务层说没超期、DB 说超期」—— 两套时限逻辑各写一份，改一次就漂移。
--
-- ⇒ **判据只有一份：`platform_dispute_appeal_gate(...)`（本文件第一节的函数）**。
--   它既是**本模块 DB 触发器**（守卫①）的判据，也是**本模块服务层**（Java）的判据 ——
--   服务层通过 jOOQ 调它，**不自己写一遍 `evidence_deadline <= now()`**。
--   口径完全照抄 6.3.1.3 的 `purchase_order_approval_match(...)`：
--   「服务端与 DB 触发器共用同一个 SQL 函数，否则规则改一次就漂移」。
--
-- ⚠️ **时钟域**：该函数用 **DB 自己的 `now()`** 判定，不接受调用方传入的时刻。
--   ⇒ 服务层与 DB 触发器**永远在同一个时钟域里比较**，
--   不会出现「JVM 墙钟比库里时刻快 1 毫秒就误判超期」这种跨时钟域的偶发假红。
--   提交时刻也**不由客户端给**：服务层取 DB 时刻（`select now()`）写入。
--
-- ===========================================================================
-- 三、「举证材料必须来自系统记录」怎么落成结构
-- ===========================================================================
--
-- 蓝图加粗句：「**举证材料必须来自系统记录**（轨迹、沟通日志），临时拼凑的证据在申诉中
-- 往往不被采信。」—— 这句话必须落成**结构**，否则就只是一段提示文案。
--
-- 本模块**不接受任意上传的「随便拼的文件」**。每一条材料都必须指向**系统里已有的一条事实记录**：
--
--   | source_module                | 来源表（trade 自己的库）       | 覆盖蓝图点名的材料 |
--   |------------------------------|-------------------------------|-------------------|
--   | SHIPMENT_TRACKING_OBSERVATION| shipment_record_tracking_observation | 物流签收 / 物流轨迹 |
--   | AFTERSALES_MESSAGE           | aftersales_message            | 沟通记录 |
--   | AFTERSALES_ATTACHMENT        | aftersales_attachment         | 商品照片 / 其它凭证 |
--   | QUALITY_INSPECTION_ORDER     | quality_inspection_order      | 检测报告 |
--
-- 三条纪律：
--   ① **只存逻辑引用 + 快照元数据，不复制文件本体**（照抄 7.4.1.1 的附件做法）：
--      `source_ref_id`（逻辑引用）+ `source_ref_no` / `source_label` / `source_locator` /
--      `source_occurred_at`（快照）。文件本体仍在文件服务里，本模块**不复制、不建跨库外键**。
--   ② **来源记录必须真实存在**：判定函数 `platform_dispute_appeal_source_snapshot(...)`
--      是**唯一实现** —— 服务层用它取快照，DB 触发器用它判存在。
--      指向一条不存在的记录 ⇒ 服务层 400，直连 SQL 也被 DB 拒。
--   ③ **材料的证据类型由来源记录推导**，不是调用方自由填：
--      附件按 `evidence_kind` 映射、消息按「沟通记录」、轨迹按「物流签收」…
--      ⇒ 「随便挑个类型贴上去」在结构上不成立。
--
-- ===========================================================================
-- 四、与上游 11.4.1.1 的边界（不越界，也不留下假事实）
-- ===========================================================================
--
--   * `platform_dispute` / `platform_dispute_evidence_item` 是**上游的表**：
--     本模块**只读**（举证清单是本模块的直接输入），写入仍由 11.4.1.1 的服务层执行。
--     「提交申诉」那一刻由本模块**驱动** 11.4.1.1 的「提交举证」（见守卫① 的说明），
--     因为**申诉提交与举证提交是同一个动作**：不写 `evidence_submitted_at`，
--     V265 守卫① 就会把按时提交了申诉的纠纷判负 —— 那正是要避免的假事实 ①。
--   * 必举证项的「是否已具备」也是上游的列：本模块挂上一份覆盖某清单项的材料时，
--     **驱动** 11.4.1.1 把该清单项标为已具备（上游仍是唯一写入者）。
--   * 「结果（胜诉 / 败诉 / 部分）」是**申诉层**的结果：11.4.1.1 的结果码域
--     （PLATFORM_RULING_FOR / PLATFORM_RULING_AGAINST / MUTUAL_AGREEMENT / WITHDRAWN_BY_US
--     / DEADLINE_EXPIRED）里**没有「部分」这一档** ⇒ 「部分」只在本模块的申诉单上落地，
--     金额口径走 11.4.1.1 的结算分录（登记额 = 平台实际支持的部分金额）。
--     这一点在能力声明里**如实报出**，不假装纠纷单层有「部分」。
--
-- ===========================================================================
-- 五、状态机
-- ===========================================================================
--
-- DRAFT              草稿：材料在收集
-- SUBMITTED          已提交平台（待平台受理）
-- PLATFORM_REPLIED   已收到平台回复（结果未落定，或结果与回复一并落定）
-- RESOLVED_WON       我方胜诉（终态）
-- RESOLVED_LOST      我方败诉（终态）
-- RESOLVED_PARTIAL   部分支持（终态）—— 11.4.1.1 层没有这一档，见第四节
-- WITHDRAWN          我方撤诉（终态）
--
-- ===========================================================================
-- 六、类目与命名
-- ===========================================================================
CREATE TABLE platform_dispute_appeal (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  appeal_no varchar(32) NOT NULL,

  -- 申诉挂在哪件纠纷上（**同模块 FK**：11.4.1.1 的纠纷单与本模块同服务同库）。
  dispute_id uuid NOT NULL
      REFERENCES platform_dispute(id) ON DELETE CASCADE,

  -- ---- 状态机（第五节）----
  appeal_status varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK(appeal_status IN (
      'DRAFT','SUBMITTED','PLATFORM_REPLIED',
      'RESOLVED_WON','RESOLVED_LOST','RESOLVED_PARTIAL','WITHDRAWN'
  )),

  -- ---- 申诉文本（蓝图输入句「申诉文本（可用模板）」）----
  appeal_text text NOT NULL CHECK(length(btrim(appeal_text)) > 0 AND length(appeal_text) <= 8000),
  -- 模板标识：申诉文本「可用模板」。**只记模板码，不在库里存模板正文**
  -- —— 模板是话术资产（11.4.2.2 知识库与话术，未交付），正文改了不该改历史申诉。
  template_code varchar(64) NOT NULL DEFAULT '',

  -- ---- 主张金额（NUMERIC(19,4) + BigDecimal，禁浮点；一单一币种）----
  claimed_currency char(3) NOT NULL CHECK(claimed_currency ~ '^[A-Z]{3}$'),
  claimed_amount numeric(19,4) NOT NULL CHECK(claimed_amount > 0),

  -- ---- 平台裁定金额（**结果落定时才写**）----
  -- ⚠️ 「部分」这一档的金额只在这里（11.4.1.1 层没有「部分」档，见第四节）。
  awarded_currency char(3) CHECK(awarded_currency IS NULL OR awarded_currency ~ '^[A-Z]{3}$'),
  awarded_amount numeric(19,4) CHECK(awarded_amount IS NULL OR awarded_amount >= 0),

  -- ---- 提交记录（蓝图输出句「申诉提交记录」）----
  submitted_at timestamptz,
  submitted_by uuid,
  submit_note text NOT NULL DEFAULT '' CHECK(length(submit_note) <= 2000),

  -- ---- 平台回复（蓝图输出句「平台回复」）----
  platform_reply_at timestamptz,
  platform_reply_by uuid,
  platform_reply_text text NOT NULL DEFAULT '' CHECK(length(platform_reply_text) <= 8000),
  -- 平台侧回复单号：与平台对账的钥匙。
  platform_reply_reference varchar(128) NOT NULL DEFAULT '',

  -- ---- 结果（蓝图输出句「结果（胜诉/败诉/部分）」）----
  resolution_code varchar(24) CHECK(resolution_code IS NULL OR resolution_code IN (
      'PLATFORM_RULING_FOR','PLATFORM_RULING_AGAINST','PARTIAL_RULING',
      'MUTUAL_AGREEMENT','WITHDRAWN_BY_US'
  )),
  resolution_note text NOT NULL DEFAULT '' CHECK(length(resolution_note) <= 2000),
  resolved_at timestamptz,
  resolved_by uuid,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid,
  -- 乐观锁：改状态都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, appeal_no),

  -- ---- 状态与事实的一致性（表级 CHECK 钉死）----
  -- 已提交 ⇒ 必须有提交时刻与提交人。
  CHECK(appeal_status <> 'SUBMITTED'
        OR (submitted_at IS NOT NULL AND submitted_by IS NOT NULL)),
  -- 未提交 ⇒ 不得有提交时刻（两个口径不能各说各话）。
  CHECK(submitted_at IS NULL OR appeal_status <> 'DRAFT'),
  -- 已收到平台回复 ⇒ 必须有回复时刻与回复正文。
  CHECK(appeal_status <> 'PLATFORM_REPLIED'
        OR (platform_reply_at IS NOT NULL AND length(btrim(platform_reply_text)) > 0)),
  -- 四个终态 ⇒ 必须有结果码、结果时刻与结果人。
  CHECK(appeal_status NOT IN ('RESOLVED_WON','RESOLVED_LOST','RESOLVED_PARTIAL','WITHDRAWN')
        OR (resolution_code IS NOT NULL AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)),
  -- 胜诉 ⇒ 结果码只能是「平台判我方胜诉」或「双方达成一致」。
  CHECK(appeal_status <> 'RESOLVED_WON'
        OR resolution_code IN ('PLATFORM_RULING_FOR','MUTUAL_AGREEMENT')),
  -- 败诉 ⇒ 结果码只能是「平台判我方败诉」。
  CHECK(appeal_status <> 'RESOLVED_LOST' OR resolution_code = 'PLATFORM_RULING_AGAINST'),
  -- 撤诉 ⇒ 结果码只能是「我方撤诉」。
  CHECK(appeal_status <> 'WITHDRAWN' OR resolution_code = 'WITHDRAWN_BY_US'),
  -- **「部分」必须真的只支持了一部分**：全支持是胜诉，全不支持是败诉。
  CHECK(appeal_status <> 'RESOLVED_PARTIAL'
        OR (resolution_code = 'PARTIAL_RULING'
            AND awarded_amount IS NOT NULL
            AND awarded_amount > 0
            AND awarded_amount < claimed_amount)),
  -- 裁定币种必须与主张币种一致（不折算）。
  CHECK(awarded_currency IS NULL OR awarded_currency = claimed_currency),
  -- 裁定金额不得超过主张金额。
  CHECK(awarded_amount IS NULL OR awarded_amount <= claimed_amount)
);

COMMENT ON TABLE platform_dispute_appeal IS
  '11.4.1.2 申诉单：证据打包 + 申诉文本 + 提交记录 + 平台回复 + 结果（胜诉/败诉/部分）；时限判定与 DB 触发器共用 platform_dispute_appeal_gate()';

-- ===========================================================================
-- 二、证据材料（一行 = 一项材料引用）
-- ===========================================================================
CREATE TABLE platform_dispute_appeal_material (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  appeal_id uuid NOT NULL
      REFERENCES platform_dispute_appeal(id) ON DELETE CASCADE,

  -- 序号由 DB 排（守卫②）：材料在申诉包里的先后顺序是证据链的一部分。
  material_seq int NOT NULL CHECK(material_seq > 0),

  -- 证据类型：**由来源记录推导**，取值域与 11.4.1.1 的举证清单同域。
  evidence_type varchar(32) NOT NULL CHECK(evidence_type IN (
      'ORDER_FULFILLMENT_PROOF','LOGISTICS_TRACKING_PROOF','INVOICE','PRODUCT_PHOTO',
      'PRODUCT_DESCRIPTION_SNAPSHOT','COMMUNICATION_LOG','PLATFORM_MESSAGE','OTHER'
  )),

  -- 覆盖 11.4.1.1 的哪一项举证清单（**跨模块，不建外键**：清单是上游的表，
  -- 它的生命周期不由本模块决定；服务层与守卫② 校验它确实属于同一件纠纷）。
  -- ⚠️ 非空 ⇒ 「必举证项有没有被材料覆盖」这条判据才有意义（第一节 gate 函数）。
  evidence_item_id uuid,

  -- ---- 来源系统记录（**逻辑引用 + 快照元数据**，不复制文件本体）----
  source_module varchar(32) NOT NULL CHECK(source_module IN (
      'SHIPMENT_TRACKING_OBSERVATION','AFTERSALES_MESSAGE',
      'AFTERSALES_ATTACHMENT','QUALITY_INSPECTION_ORDER'
  )),
  source_ref_id uuid NOT NULL,
  -- 来源单号 / 轨迹号快照。
  source_ref_no varchar(128) NOT NULL DEFAULT '',
  -- 来源描述快照（附件文件名 / 消息摘要 / 轨迹明细 / 质检单模板名）。
  source_label varchar(400) NOT NULL DEFAULT '',
  -- 对象存储定位符（**只有附件类才有**）：形如 key，不是 URL 全文。
  source_locator varchar(512) NOT NULL DEFAULT '',
  -- 来源记录的时刻快照（轨迹观测时刻 / 消息发送时刻 / 附件采集时刻 / 质检单时刻）。
  source_occurred_at timestamptz NOT NULL,

  -- 来源记录自己的类别（附件的 evidence_kind / 轨迹的 status / 消息的 channel）：
  -- 留一份快照，事后不用回源系统就能答出「这份材料在源系统里是什么」。
  source_kind varchar(32) NOT NULL DEFAULT '',

  material_note text NOT NULL DEFAULT '' CHECK(length(material_note) <= 2000),

  -- 本材料挂载时是否把上游清单项标成了「已具备」：
  -- 移除材料时据此决定**要不要回退清单项**，避免把人工标的状态冲掉。
  provided_checklist_item boolean NOT NULL DEFAULT false,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, appeal_id, material_seq),
  -- 附件类材料**必须**带对象存储定位符：没有定位符就拿不到文件本体。
  CHECK(source_module <> 'AFTERSALES_ATTACHMENT' OR length(btrim(source_locator)) > 0)
);

COMMENT ON TABLE platform_dispute_appeal_material IS
  '11.4.1.2 证据材料：逻辑引用 + 快照元数据（不复制文件本体、不建跨库外键）；来源必须是系统里已有的事实记录';

-- ===========================================================================
-- 三、待提交平台事实（**不假装能调平台**）
-- ===========================================================================
CREATE TABLE platform_dispute_appeal_submission (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  appeal_id uuid NOT NULL
      REFERENCES platform_dispute_appeal(id) ON DELETE CASCADE,

  -- 目标系统与目标键：平台侧接手后按这两个字段回写。
  target_system varchar(32) NOT NULL CHECK(target_system IN ('CHANNEL')),
  target_key varchar(128) NOT NULL CHECK(length(btrim(target_key)) > 0),

  -- 载荷：**用具名结构序列化**（不放 Map<String,Object>，照抄 9.6.1.3 的做法）。
  payload jsonb NOT NULL CHECK(jsonb_typeof(payload) = 'object'),

  -- 幂等键：同一次申诉重复提交**不会**造出第二条待办，
  -- 否则平台侧会收到两份一模一样的申诉。
  idempotency_key varchar(160) NOT NULL,

  -- 状态机：PENDING → SENT → ACKNOWLEDGED ／ PENDING|SENT → FAILED → PENDING（重试）
  -- ⚠️ **SENT（本服务已发出）与 ACKNOWLEDGED（平台侧已受理）是两个状态，不能合并**：
  -- 「发过去了」和「对方收下了」不是一回事（照抄 9.6.1.3 的同一条纪律）。
  submission_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(submission_status IN (
      'PENDING','SENT','ACKNOWLEDGED','FAILED'
  )),

  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  last_attempt_at timestamptz,
  next_retry_at timestamptz,
  last_error text NOT NULL DEFAULT '' CHECK(length(last_error) <= 2000),

  sent_at timestamptz,
  acknowledged_at timestamptz,
  acknowledged_by uuid,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- 最近一次改写这一行的人：留痕事件的「谁做的」从这一列取。
  updated_by uuid,

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),

  -- 已发出 ⇒ 必须有发出时刻。
  CHECK(submission_status <> 'SENT' OR sent_at IS NOT NULL),
  -- 已受理 ⇒ 必须有受理时刻与受理人。
  CHECK(submission_status <> 'ACKNOWLEDGED'
        OR (acknowledged_at IS NOT NULL AND acknowledged_by IS NOT NULL)),
  -- 发出去过 ⇒ 至少尝试过一次。
  CHECK(submission_status NOT IN ('SENT','ACKNOWLEDGED') OR attempt_count > 0)
);

COMMENT ON TABLE platform_dispute_appeal_submission IS
  '11.4.1.2 待提交平台事实：目标系统 + 目标键 + 载荷 + 幂等键 + 状态机 + 重试计数；channel 侧无申诉端点，故本模块只落事实不调平台';

-- ===========================================================================
-- 四、留痕（追加式）
-- ===========================================================================
CREATE TABLE platform_dispute_appeal_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  appeal_id uuid NOT NULL,
  event_type varchar(32) NOT NULL CHECK(event_type IN (
      'CREATED','MATERIAL_ADDED','MATERIAL_REMOVED','TEXT_UPDATED',
      'SUBMITTED','SUBMISSION_CREATED','SUBMISSION_SENT','SUBMISSION_FAILED',
      'SUBMISSION_REQUEUED','SUBMISSION_ACKNOWLEDGED',
      'PLATFORM_REPLIED','RESOLVED_WON','RESOLVED_LOST','RESOLVED_PARTIAL','WITHDRAWN'
  )),
  from_status varchar(24),
  to_status varchar(24),

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

COMMENT ON TABLE platform_dispute_appeal_event IS
  '11.4.1.2 申诉留痕：追加式，运行角色 UPDATE/DELETE 已收回';

-- ===========================================================================
-- 五、判据一：时限 + 清单覆盖（**库层与服务层共用**）
-- ===========================================================================
--
-- 🔴 这是本模块**唯一**的时限 / 覆盖判据实现。
--    服务层通过 jOOQ 调它（`select * from platform_dispute_appeal_gate(?,?,?)`），
--    DB 触发器守卫① 也调它 —— **两侧不可能漂移**（照抄 6.3.1.3 的
--    `purchase_order_approval_match(...)` 做法）。
--
-- ⚠️ 用 **DB 自己的 now()** 判定：函数不接受调用方传入的时刻，
--    服务层与触发器永远在同一个时钟域里比较（不会跨时钟域误判超期）。
--
-- 返回的四个计数字段：
--   checklist_item_count     —— 举证清单总项数
--   missing_required_count   —— **必举证项没具备**的项数（11.4.1.1 口径：
--                               `required AND evidence_state <> 'PROVIDED'`，
--                               与 V265 守卫⑥ ② **逐字同款**）
--   covered_item_count       —— 必举证项**被本申诉的材料覆盖**的项数
--   uncovered_required_count —— 必举证项没被覆盖的项数
--
CREATE FUNCTION platform_dispute_appeal_gate(
  p_tenant_id uuid,
  p_dispute_id uuid,
  p_appeal_id uuid DEFAULT NULL
) RETURNS TABLE (
  dispute_status varchar(24),
  evidence_deadline timestamptz,
  evidence_submitted_at timestamptz,
  window_state varchar(16),
  is_submittable boolean,
  checklist_item_count int,
  missing_required_count int,
  covered_item_count int,
  uncovered_required_count int
) LANGUAGE plpgsql STABLE AS $$
DECLARE
  v_status varchar(24);
  v_deadline timestamptz;
  v_submitted timestamptz;
  v_resolution varchar(24);
  v_window varchar(16);
  v_submittable boolean;
  v_items int := 0;
  v_missing int := 0;
  v_covered int := 0;
  v_required int := 0;
BEGIN
  SELECT d.dispute_status, d.evidence_deadline, d.evidence_submitted_at, d.resolution_code
    INTO v_status, v_deadline, v_submitted, v_resolution
    FROM platform_dispute d
   WHERE d.tenant_id = p_tenant_id AND d.id = p_dispute_id;

  IF NOT FOUND THEN
    -- 单不存在：调用方负责报「不存在」，这里不猜。
    RETURN QUERY SELECT NULL::varchar, NULL::timestamptz, NULL::timestamptz,
                        'UNKNOWN'::varchar, false, 0, 0, 0, 0;
    RETURN;
  END IF;

  -- ---- 时限状态（判据的核心）----
  -- ⚠️ 顺序有意义：先看「举证已提交」（时限已解除），再看「已被系统判负」，
  --    最后才看「过了截止时刻」。与 V265 守卫① 的判据同款：
  --    `evidence_submitted_at IS NULL AND evidence_deadline <= now()` ⇒ 超期。
  IF v_submitted IS NOT NULL THEN
    v_window := 'RELEASED';            -- 举证已提交，时限解除
  ELSIF v_status = 'RESOLVED_LOST' AND v_resolution = 'DEADLINE_EXPIRED' THEN
    v_window := 'EXPIRED';             -- 已被系统判负
  ELSIF v_deadline IS NOT NULL AND v_deadline <= now() THEN
    v_window := 'EXPIRED';             -- 已过时限（V265 守卫① 下一次写入会把它落成判负）
  ELSIF v_status IN ('OPEN','EVIDENCE_PREPARING') THEN
    v_window := 'OPEN';
  ELSE
    v_window := 'CLOSED';              -- 其它终态（胜诉 / 撤诉）
  END IF;

  -- 只有**举证准备中**的纠纷才谈得上「提交申诉」
  -- （与 11.4.1.1 的状态机一致：已立案 → 举证准备 → 举证已提交）。
  v_submittable := (v_window = 'OPEN') AND (v_status = 'EVIDENCE_PREPARING');

  -- ---- 清单计数 ----
  SELECT count(*) INTO v_items
    FROM platform_dispute_evidence_item i
   WHERE i.tenant_id = p_tenant_id AND i.dispute_id = p_dispute_id;

  SELECT count(*) INTO v_missing
    FROM platform_dispute_evidence_item i
   WHERE i.tenant_id = p_tenant_id AND i.dispute_id = p_dispute_id
     AND i.required AND i.evidence_state <> 'PROVIDED';

  SELECT count(*) INTO v_required
    FROM platform_dispute_evidence_item i
   WHERE i.tenant_id = p_tenant_id AND i.dispute_id = p_dispute_id
     AND i.required;

  -- ---- 覆盖计数（需要申诉单）----
  IF p_appeal_id IS NOT NULL THEN
    SELECT count(*) INTO v_covered
      FROM platform_dispute_evidence_item i
     WHERE i.tenant_id = p_tenant_id AND i.dispute_id = p_dispute_id
       AND i.required
       AND EXISTS (SELECT 1 FROM platform_dispute_appeal_material m
                    WHERE m.tenant_id = p_tenant_id
                      AND m.appeal_id = p_appeal_id
                      AND m.evidence_item_id = i.id);
  END IF;

  RETURN QUERY SELECT v_status, v_deadline, v_submitted, v_window, v_submittable,
                      v_items, v_missing, v_covered, (v_required - v_covered);
END $$;

COMMENT ON FUNCTION platform_dispute_appeal_gate(uuid, uuid, uuid) IS
  '11.4.1.2 提交申诉判据（唯一实现）：时限窗口 + 必举证项未具备数 + 未被材料覆盖数；服务端与 DB 触发器共用';

-- ===========================================================================
-- 六、判据二：来源记录必须真实存在（**唯一实现**）
-- ===========================================================================
--
-- 服务层用它**取快照**（所以快照与「存在与否」永远一致），
-- DB 触发器守卫② 用它**判存在**（所以直连 SQL 也塞不进假材料）。
--
CREATE FUNCTION platform_dispute_appeal_source_snapshot(
  p_tenant_id uuid,
  p_source_module varchar(32),
  p_source_ref_id uuid
) RETURNS TABLE (
  exists_flag boolean,
  source_ref_no varchar(128),
  source_label varchar(400),
  source_locator varchar(512),
  source_occurred_at timestamptz,
  source_kind varchar(32),
  suggested_evidence_type varchar(32)
) LANGUAGE plpgsql STABLE AS $$
BEGIN
  CASE p_source_module
    WHEN 'SHIPMENT_TRACKING_OBSERVATION' THEN
      RETURN QUERY
        SELECT true,
               coalesce(t.observation_ref, ''),
               -- ⚠️ **btrim(varchar) 返回 text** ⇒ 与函数的 varchar 返回类型不匹配，
               --    PG 会报「structure of query does not match function result type」。
               --    凡是走过 btrim / left 的表达式都要显式转 varchar。
               coalesce(nullif(btrim(t.detail), ''), t.status)::varchar,
               ''::varchar,
               t.observed_at,
               t.status,
               'LOGISTICS_TRACKING_PROOF'::varchar
          FROM shipment_record_tracking_observation t
         WHERE t.tenant_id = p_tenant_id AND t.id = p_source_ref_id;
      IF NOT FOUND THEN
        RETURN QUERY SELECT false, ''::varchar, ''::varchar, ''::varchar,
                            NULL::timestamptz, ''::varchar, NULL::varchar;
      END IF;

    WHEN 'AFTERSALES_MESSAGE' THEN
      RETURN QUERY
        SELECT true,
               coalesce(t.external_message_id, ''),
               -- ⚠️ message_body 是 text ⇒ left() 返回 text，**必须显式转 varchar**：
               --    函数返回类型与查询结构不一致时 PG 直接报
               --    「structure of query does not match function result type」。
               left(coalesce(t.message_body, ''), 400)::varchar,
               ''::varchar,
               t.sent_at,
               t.channel,
               'COMMUNICATION_LOG'::varchar
          FROM aftersales_message t
         WHERE t.tenant_id = p_tenant_id AND t.id = p_source_ref_id;
      IF NOT FOUND THEN
        RETURN QUERY SELECT false, ''::varchar, ''::varchar, ''::varchar,
                            NULL::timestamptz, ''::varchar, NULL::varchar;
      END IF;

    WHEN 'AFTERSALES_ATTACHMENT' THEN
      RETURN QUERY
        SELECT true,
               coalesce(t.source_reference, ''),
               t.file_name,
               t.storage_ref,
               t.captured_at,
               t.evidence_kind,
               CASE t.evidence_kind
                 WHEN 'PRODUCT_PHOTO'  THEN 'PRODUCT_PHOTO'::varchar
                 WHEN 'DELIVERY_PROOF' THEN 'LOGISTICS_TRACKING_PROOF'::varchar
                 WHEN 'PAYMENT_PROOF'  THEN 'INVOICE'::varchar
                 WHEN 'INSPECTION_REPORT' THEN 'ORDER_FULFILLMENT_PROOF'::varchar
                 ELSE 'OTHER'::varchar
               END
          FROM aftersales_attachment t
         WHERE t.tenant_id = p_tenant_id AND t.id = p_source_ref_id;
      IF NOT FOUND THEN
        RETURN QUERY SELECT false, ''::varchar, ''::varchar, ''::varchar,
                            NULL::timestamptz, ''::varchar, NULL::varchar;
      END IF;

    WHEN 'QUALITY_INSPECTION_ORDER' THEN
      RETURN QUERY
        SELECT true,
               t.inspection_no,
               coalesce(nullif(btrim(t.template_name), ''), t.template_code)::varchar,
               ''::varchar,
               t.created_at,
               -- ⚠️ inspection_type 是 text ⇒ 同样必须显式转 varchar。
               t.inspection_type::varchar,
               'ORDER_FULFILLMENT_PROOF'::varchar
          FROM quality_inspection_order t
         WHERE t.tenant_id = p_tenant_id AND t.id = p_source_ref_id;
      IF NOT FOUND THEN
        RETURN QUERY SELECT false, ''::varchar, ''::varchar, ''::varchar,
                            NULL::timestamptz, ''::varchar, NULL::varchar;
      END IF;

    ELSE
      RETURN QUERY SELECT false, ''::varchar, ''::varchar, ''::varchar,
                          NULL::timestamptz, ''::varchar, NULL::varchar;
  END CASE;
END $$;

COMMENT ON FUNCTION platform_dispute_appeal_source_snapshot(uuid, varchar, uuid) IS
  '11.4.1.2 来源记录快照（唯一实现）：取快照与判存在是同一处；举证材料只能来自系统里已有的事实记录';

-- ===========================================================================
-- 七、DB 守卫（服务层校验可以绕开，触发器绕不开）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 守卫① guard_platform_dispute_appeal_submit：**提交申诉的判据**
-- ---------------------------------------------------------------------------
-- ① 提交时刻必须**不晚于举证截止时刻**（与 V265 守卫① 同一条硬规则）；
-- ② 必须**清单齐全**（必举证项一个都不能缺 —— 逐字同款 11.4.1.1 的口径）；
-- ③ 必须**必举证项都被本申诉的材料覆盖**（清单说齐了但包里没有 = 白交）；
-- ④ 时限窗口必须开着（未超期 / 纠纷仍在举证准备中）。
--
-- ⚠️ ①②③④ 全部来自 `platform_dispute_appeal_gate(...)` —— **与本模块服务层同一个函数**。
--    服务层在写之前调它把话说清楚（400），DB 在写的时候再钉一道（RAISE）。
--    两侧不会各说各话。
CREATE FUNCTION guard_platform_dispute_appeal_submit() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  g record;
BEGIN
  IF NEW.appeal_status IS NOT DISTINCT FROM OLD.appeal_status THEN
    RETURN NEW;
  END IF;

  IF NEW.appeal_status <> 'SUBMITTED' THEN
    RETURN NEW;
  END IF;

  -- 终态不可逆（第四节）。
  IF OLD.appeal_status IN ('RESOLVED_WON','RESOLVED_LOST','RESOLVED_PARTIAL','WITHDRAWN') THEN
    RAISE EXCEPTION '申诉单已是终态（%），不可再流转：appeal_id=%', OLD.appeal_status, OLD.id
      USING ERRCODE = 'check_violation';
  END IF;

  IF OLD.appeal_status <> 'DRAFT' THEN
    RAISE EXCEPTION '只有草稿状态的申诉可以提交（当前 %）：appeal_id=%',
      OLD.appeal_status, OLD.id USING ERRCODE = 'check_violation';
  END IF;

  -- 🔴 **判据只有这一份**（第一节）：与本模块服务层共用。
  SELECT * INTO g
    FROM platform_dispute_appeal_gate(NEW.tenant_id, NEW.dispute_id, NEW.id);

  IF g.dispute_status IS NULL THEN
    RAISE EXCEPTION '纠纷单不存在或不属于当前租户：dispute_id=%', NEW.dispute_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF NOT g.is_submittable THEN
    RAISE EXCEPTION '申诉提交窗口已关闭（时限状态 %，纠纷当前 %，举证截止 %）：超期后不可提交申诉：appeal_id=%',
      g.window_state, g.dispute_status, g.evidence_deadline, NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  IF g.missing_required_count > 0 THEN
    RAISE EXCEPTION '还有 % 项必举证材料没有具备，不能提交申诉（超期即判负，缺一项就是白丢）：appeal_id=%',
      g.missing_required_count, NEW.id USING ERRCODE = 'check_violation';
  END IF;

  IF g.uncovered_required_count > 0 THEN
    RAISE EXCEPTION '还有 % 项必举证材料没有被本次申诉的材料覆盖，不能提交申诉：appeal_id=%',
      g.uncovered_required_count, NEW.id USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.submitted_at IS NULL THEN
    RAISE EXCEPTION '必须记录申诉提交时刻：appeal_id=%', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.submitted_at > g.evidence_deadline THEN
    RAISE EXCEPTION '申诉提交时刻（%）晚于举证截止时刻（%），超期后不可提交申诉：appeal_id=%',
      NEW.submitted_at, g.evidence_deadline, NEW.id USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_appeal_submit
  BEFORE UPDATE ON platform_dispute_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_appeal_submit();

-- ---------------------------------------------------------------------------
-- 守卫② guard_platform_dispute_appeal_material：**材料只能来自系统记录**
-- ---------------------------------------------------------------------------
-- ① 申诉单必须是**草稿**（提交给平台的那一份是快照，之后不可改）；
-- ② 来源记录**必须真实存在**（用第一节的 source_snapshot 判据）；
-- ③ 覆盖的清单项必须**属于同一件纠纷**且**类型对得上**；
-- ④ 序号由 DB 排（只在 INSERT 时排）。
CREATE FUNCTION guard_platform_dispute_appeal_material() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(24);
  t_dispute_id uuid;
  t_appeal_id uuid;
  s record;
  t_item_type varchar(32);
  t_item_dispute uuid;
BEGIN
  -- ⚠️ DELETE 触发器里 NEW **未赋值**（引用 NEW.x 会直接报错）⇒ 先取到局部变量。
  IF TG_OP = 'DELETE' THEN
    t_appeal_id := OLD.appeal_id;
    SELECT a.appeal_status, a.dispute_id INTO t_status, t_dispute_id
      FROM platform_dispute_appeal a
     WHERE a.tenant_id = OLD.tenant_id AND a.id = OLD.appeal_id;
  ELSE
    t_appeal_id := NEW.appeal_id;
    SELECT a.appeal_status, a.dispute_id INTO t_status, t_dispute_id
      FROM platform_dispute_appeal a
     WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.appeal_id;
  END IF;

  IF NOT FOUND THEN
    RAISE EXCEPTION '申诉单不存在或不属于当前租户：appeal_id=%',
      t_appeal_id USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status <> 'DRAFT' THEN
    RAISE EXCEPTION '申诉单当前是「%」，材料包已冻结（提交给平台的那一份是快照）：appeal_id=%',
      t_status, t_appeal_id USING ERRCODE = 'check_violation';
  END IF;

  IF TG_OP <> 'DELETE' THEN
    -- ② 来源记录必须真实存在（**判据唯一实现**，与本模块服务层同一个函数）。
    SELECT * INTO s
      FROM platform_dispute_appeal_source_snapshot(NEW.tenant_id, NEW.source_module, NEW.source_ref_id);
    IF NOT s.exists_flag THEN
      RAISE EXCEPTION '举证材料必须来自系统里已有的事实记录：来源 % / % 在当前租户下不存在',
        NEW.source_module, NEW.source_ref_id USING ERRCODE = 'check_violation';
    END IF;

    -- ③ 覆盖的清单项必须属于同一件纠纷，且证据类型对得上。
    IF NEW.evidence_item_id IS NOT NULL THEN
      SELECT i.evidence_type, i.dispute_id INTO t_item_type, t_item_dispute
        FROM platform_dispute_evidence_item i
       WHERE i.tenant_id = NEW.tenant_id AND i.id = NEW.evidence_item_id;
      IF NOT FOUND THEN
        RAISE EXCEPTION '举证清单项不存在或不属于当前租户：evidence_item_id=%', NEW.evidence_item_id
          USING ERRCODE = 'check_violation';
      END IF;
      IF t_item_dispute <> t_dispute_id THEN
        RAISE EXCEPTION '举证清单项不属于这件纠纷（清单项属于纠纷 %，本申诉属于纠纷 %）',
          t_item_dispute, t_dispute_id USING ERRCODE = 'check_violation';
      END IF;
      IF t_item_type <> NEW.evidence_type THEN
        RAISE EXCEPTION '证据类型（%）与清单项的类型（%）对不上', NEW.evidence_type, t_item_type
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;

    -- ④ 序号由 DB 排。
    IF TG_OP = 'INSERT' THEN
      SELECT coalesce(max(material_seq), 0) + 1 INTO NEW.material_seq
        FROM platform_dispute_appeal_material
       WHERE tenant_id = NEW.tenant_id AND appeal_id = NEW.appeal_id;
    END IF;
  END IF;

  RETURN coalesce(NEW, OLD);
END
$$;

CREATE TRIGGER trg_platform_dispute_appeal_material
  BEFORE INSERT OR UPDATE OR DELETE ON platform_dispute_appeal_material
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_appeal_material();

-- ---------------------------------------------------------------------------
-- 守卫③ guard_platform_dispute_appeal_transition：**状态机 + 终态冻结**
-- ---------------------------------------------------------------------------
-- 终态（胜诉 / 败诉 / 部分 / 撤诉）之后**业务要素整行冻结** ——
-- 申诉是要拿去跟平台对质的凭据，「我们当时交了什么、平台回了什么」必须只有一个答案。
CREATE FUNCTION guard_platform_dispute_appeal_transition() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.appeal_status IN ('RESOLVED_WON','RESOLVED_LOST','RESOLVED_PARTIAL','WITHDRAWN') THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '申诉单已是终态（%），业务要素整行冻结：appeal_id=%',
        OLD.appeal_status, OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.appeal_status IS NOT DISTINCT FROM OLD.appeal_status THEN
    RETURN NEW;
  END IF;

  CASE NEW.appeal_status
    WHEN 'SUBMITTED' THEN
      IF OLD.appeal_status <> 'DRAFT' THEN
        RAISE EXCEPTION '只有草稿状态的申诉可以提交（当前 %）：appeal_id=%',
          OLD.appeal_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
    WHEN 'PLATFORM_REPLIED' THEN
      IF OLD.appeal_status NOT IN ('SUBMITTED','PLATFORM_REPLIED') THEN
        RAISE EXCEPTION '只有已提交平台的申诉才有平台回复（当前 %）：appeal_id=%',
          OLD.appeal_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
    WHEN 'RESOLVED_WON' THEN
      IF OLD.appeal_status NOT IN ('SUBMITTED','PLATFORM_REPLIED') THEN
        RAISE EXCEPTION '只有已提交平台的申诉才有平台裁决（当前 %）：appeal_id=%',
          OLD.appeal_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
    WHEN 'RESOLVED_LOST' THEN
      IF OLD.appeal_status NOT IN ('SUBMITTED','PLATFORM_REPLIED') THEN
        RAISE EXCEPTION '只有已提交平台的申诉才有平台裁决（当前 %）：appeal_id=%',
          OLD.appeal_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
    WHEN 'RESOLVED_PARTIAL' THEN
      IF OLD.appeal_status NOT IN ('SUBMITTED','PLATFORM_REPLIED') THEN
        RAISE EXCEPTION '只有已提交平台的申诉才有平台裁决（当前 %）：appeal_id=%',
          OLD.appeal_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
    WHEN 'WITHDRAWN' THEN
      IF OLD.appeal_status NOT IN ('DRAFT','SUBMITTED','PLATFORM_REPLIED') THEN
        RAISE EXCEPTION '只有未终结的申诉可以撤诉（当前 %）：appeal_id=%',
          OLD.appeal_status, OLD.id USING ERRCODE = 'check_violation';
      END IF;
    ELSE
      RAISE EXCEPTION '未知的申诉状态：%', NEW.appeal_status USING ERRCODE = 'check_violation';
  END CASE;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_appeal_transition
  BEFORE UPDATE ON platform_dispute_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_appeal_transition();

-- ---------------------------------------------------------------------------
-- 守卫④ guard_platform_dispute_appeal_submission：**待提交平台事实**
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_platform_dispute_appeal_submission() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(24);
BEGIN
  SELECT a.appeal_status INTO t_status
    FROM platform_dispute_appeal a
   WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.appeal_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '申诉单不存在或不属于当前租户：appeal_id=%', NEW.appeal_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF TG_OP = 'INSERT' THEN
    -- 只有**已提交**的申诉才有待提交事实：没提交就往平台递，等于递了个空包。
    IF t_status <> 'SUBMITTED' THEN
      RAISE EXCEPTION '只有已提交平台的申诉才有待提交事实（当前 %）：appeal_id=%',
        t_status, NEW.appeal_id USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    IF OLD.submission_status IN ('ACKNOWLEDGED') AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '提交记录已被平台受理，是终态不可修改：submission_id=%', OLD.id
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.submission_status = 'ACKNOWLEDGED'
       AND (NEW.acknowledged_at IS NULL OR NEW.acknowledged_by IS NULL) THEN
      RAISE EXCEPTION '已受理必须留受理时刻与受理人：submission_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.submission_status = 'SENT' AND NEW.sent_at IS NULL THEN
      RAISE EXCEPTION '已发出必须留发出时刻：submission_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_appeal_submission
  BEFORE INSERT OR UPDATE ON platform_dispute_appeal_submission
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_appeal_submission();

-- ---------------------------------------------------------------------------
-- 留痕追加（只写「真变了」的事）
-- ---------------------------------------------------------------------------
CREATE FUNCTION platform_dispute_appeal_append_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.appeal_status IS NOT DISTINCT FROM OLD.appeal_status
     AND NEW.appeal_text IS NOT DISTINCT FROM OLD.appeal_text THEN
    RETURN NEW;
  END IF;

  INSERT INTO platform_dispute_appeal_event(
      id, tenant_id, appeal_id, event_type, from_status, to_status,
      reason_note, occurred_by, occurred_at)
  VALUES (
    gen_random_uuid(),
    NEW.tenant_id,
    NEW.id,
    CASE
      WHEN TG_OP = 'INSERT'                 THEN 'CREATED'
      WHEN NEW.appeal_text IS DISTINCT FROM OLD.appeal_text THEN 'TEXT_UPDATED'
      WHEN NEW.appeal_status = 'SUBMITTED'  THEN 'SUBMITTED'
      WHEN NEW.appeal_status = 'PLATFORM_REPLIED' THEN 'PLATFORM_REPLIED'
      WHEN NEW.appeal_status = 'RESOLVED_WON' THEN 'RESOLVED_WON'
      WHEN NEW.appeal_status = 'RESOLVED_LOST' THEN 'RESOLVED_LOST'
      WHEN NEW.appeal_status = 'RESOLVED_PARTIAL' THEN 'RESOLVED_PARTIAL'
      WHEN NEW.appeal_status = 'WITHDRAWN' THEN 'WITHDRAWN'
      ELSE 'TEXT_UPDATED'
    END,
    CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.appeal_status END,
    NEW.appeal_status,
    CASE
      WHEN TG_OP = 'INSERT' THEN '创建申诉单：' || coalesce(NEW.template_code, '')
      WHEN NEW.appeal_text IS DISTINCT FROM OLD.appeal_text THEN '修改申诉文本'
      WHEN NEW.appeal_status = 'SUBMITTED' THEN '提交申诉到平台：' || coalesce(NEW.submit_note, '')
      WHEN NEW.appeal_status = 'PLATFORM_REPLIED'
        THEN '收到平台回复：' || coalesce(NEW.platform_reply_reference, '')
      WHEN NEW.appeal_status = 'RESOLVED_WON' THEN '我方胜诉：' || coalesce(NEW.resolution_note, '')
      WHEN NEW.appeal_status = 'RESOLVED_LOST' THEN '我方败诉：' || coalesce(NEW.resolution_note, '')
      WHEN NEW.appeal_status = 'RESOLVED_PARTIAL'
        THEN '平台部分支持：' || coalesce(NEW.resolution_note, '')
      WHEN NEW.appeal_status = 'WITHDRAWN' THEN '我方撤诉：' || coalesce(NEW.resolution_note, '')
      ELSE '申诉要素变更'
    END,
    CASE
      WHEN NEW.appeal_status IN ('RESOLVED_WON','RESOLVED_LOST','RESOLVED_PARTIAL','WITHDRAWN')
        THEN NEW.resolved_by
      WHEN NEW.appeal_status = 'SUBMITTED' THEN NEW.submitted_by
      WHEN NEW.appeal_status = 'PLATFORM_REPLIED' THEN NEW.platform_reply_by
      ELSE NEW.updated_by
    END,
    CASE
      WHEN TG_OP = 'INSERT' THEN NEW.created_at
      WHEN NEW.appeal_status = 'SUBMITTED' THEN coalesce(NEW.submitted_at, now())
      WHEN NEW.appeal_status = 'PLATFORM_REPLIED' THEN coalesce(NEW.platform_reply_at, now())
      WHEN NEW.appeal_status IN ('RESOLVED_WON','RESOLVED_LOST','RESOLVED_PARTIAL','WITHDRAWN')
        THEN coalesce(NEW.resolved_at, now())
      ELSE now()
    END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_appeal_event
  AFTER INSERT OR UPDATE ON platform_dispute_appeal
  FOR EACH ROW EXECUTE FUNCTION platform_dispute_appeal_append_event();

-- 材料增删也留痕：「这份证据包是什么时候凑齐的」要答得出来。
CREATE FUNCTION platform_dispute_appeal_append_material_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_appeal_id uuid;
  t_tenant uuid;
  t_by uuid;
  t_type varchar(32);
  t_source varchar(32);
  t_note text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    t_appeal_id := OLD.appeal_id;
    t_tenant    := OLD.tenant_id;
    t_by        := OLD.created_by;
    t_type      := OLD.evidence_type;
    t_source    := OLD.source_module;
    t_note      := '移除证据材料 ' || OLD.material_seq::text || '（' || OLD.evidence_type
                   || '，来源 ' || OLD.source_module || '）';
  ELSE
    t_appeal_id := NEW.appeal_id;
    t_tenant    := NEW.tenant_id;
    t_by        := NEW.created_by;
    t_type      := NEW.evidence_type;
    t_source    := NEW.source_module;
    t_note      := '加入证据材料 ' || NEW.material_seq::text || '（' || NEW.evidence_type
                   || '，来源 ' || NEW.source_module || '）';
  END IF;

  INSERT INTO platform_dispute_appeal_event(
      id, tenant_id, appeal_id, event_type, from_status, to_status,
      reason_note, occurred_by, occurred_at)
  SELECT gen_random_uuid(), t_tenant, t_appeal_id,
         CASE WHEN TG_OP = 'INSERT' THEN 'MATERIAL_ADDED' ELSE 'MATERIAL_REMOVED' END,
         NULL, a.appeal_status,
         t_note, t_by, now()
    FROM platform_dispute_appeal a
   WHERE a.tenant_id = t_tenant AND a.id = t_appeal_id;

  RETURN coalesce(NEW, OLD);
END
$$;

CREATE TRIGGER trg_platform_dispute_appeal_material_event
  AFTER INSERT OR UPDATE OR DELETE ON platform_dispute_appeal_material
  FOR EACH ROW EXECUTE FUNCTION platform_dispute_appeal_append_material_event();

-- 提交尝试的留痕（发出 / 失败 / 受理）。
CREATE FUNCTION platform_dispute_appeal_append_submission_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_type varchar(32);
  t_note text;
  t_at timestamptz;
  t_by uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    t_type := 'SUBMISSION_CREATED';
    t_note := '登记待提交平台事实：目标 ' || NEW.target_system || ' / ' || NEW.target_key
              || '（幂等键 ' || NEW.idempotency_key || '）';
    t_at  := NEW.created_at;
    t_by  := NEW.created_by;
  ELSE
    t_type := CASE NEW.submission_status
                WHEN 'SENT' THEN 'SUBMISSION_SENT'
                WHEN 'ACKNOWLEDGED' THEN 'SUBMISSION_ACKNOWLEDGED'
                WHEN 'FAILED' THEN 'SUBMISSION_FAILED'
                WHEN 'PENDING' THEN 'SUBMISSION_REQUEUED'
                ELSE 'SUBMISSION_SENT'
              END;
    t_note := CASE NEW.submission_status
                WHEN 'SENT' THEN '已发出申诉到 ' || NEW.target_system
                WHEN 'ACKNOWLEDGED' THEN '平台已受理：' || coalesce(nullif(btrim(NEW.last_error), ''), '（无说明）')
                WHEN 'FAILED' THEN '提交失败：' || coalesce(nullif(btrim(NEW.last_error), ''), '（无原因）')
                WHEN 'PENDING' THEN '重新排队（第 ' || NEW.attempt_count::text || ' 次尝试）'
                ELSE '提交记录变更'
              END;
    t_at  := coalesce(NEW.last_attempt_at, NEW.updated_at, now());
    t_by  := NEW.updated_by;
  END IF;

  INSERT INTO platform_dispute_appeal_event(
      id, tenant_id, appeal_id, event_type, from_status, to_status,
      reason_note, occurred_by, occurred_at)
  SELECT gen_random_uuid(), NEW.tenant_id, NEW.appeal_id, t_type,
         NULL, a.appeal_status,
         t_note, t_by, t_at
    FROM platform_dispute_appeal a
   WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.appeal_id;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_platform_dispute_appeal_submission_event
  AFTER INSERT OR UPDATE ON platform_dispute_appeal_submission
  FOR EACH ROW EXECUTE FUNCTION platform_dispute_appeal_append_submission_event();

-- ===========================================================================
-- 八、索引与发号器
-- ===========================================================================
-- ① 申诉列表（按状态 + 时刻）。
CREATE INDEX platform_dispute_appeal_by_status
  ON platform_dispute_appeal(tenant_id, appeal_status, created_at desc);
-- ② **按纠纷反查**（一件纠纷一份申诉是常态，详情页要顺着纠纷找申诉）。
CREATE INDEX platform_dispute_appeal_by_dispute
  ON platform_dispute_appeal(tenant_id, dispute_id, created_at desc);
-- ③ 待提交平台事实的取数路径（谁还欠平台一次提交）。
CREATE INDEX platform_dispute_appeal_submission_by_status
  ON platform_dispute_appeal_submission(tenant_id, submission_status, next_retry_at);
-- ④ 材料按单读（按序号）。
CREATE INDEX platform_dispute_appeal_material_by_appeal
  ON platform_dispute_appeal_material(tenant_id, appeal_id, material_seq);
-- ⑤ **同一份材料不能重复充数**（部分唯一索引，**不是**表级 UNIQUE ——
--    表级约束带不了 WHERE）：一个清单项在一份申诉里只认一份材料。
CREATE UNIQUE INDEX platform_dispute_appeal_material_one_per_item
  ON platform_dispute_appeal_material(tenant_id, appeal_id, evidence_item_id)
  WHERE evidence_item_id IS NOT NULL;
-- ⑥ **一次申诉同时只允许一条未终结的提交尝试**（重试复用同一行 ⇒ 这条索引是它的兜底）。
CREATE UNIQUE INDEX platform_dispute_appeal_submission_one_open
  ON platform_dispute_appeal_submission(tenant_id, appeal_id)
  WHERE submission_status IN ('PENDING','SENT');
-- ⑦ 留痕回放。
CREATE INDEX platform_dispute_appeal_event_by_appeal
  ON platform_dispute_appeal_event(tenant_id, appeal_id, occurred_at);

CREATE SEQUENCE platform_dispute_appeal_no_seq;
GRANT USAGE,SELECT ON SEQUENCE platform_dispute_appeal_no_seq TO trade_app;

-- ===========================================================================
-- 九、多租户：RLS + 授权
-- ===========================================================================
-- 四张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：申诉包里同时有**我方主张、证据引用、平台回复与裁决金额**，
--   端点又大多是**按 id 直达**的 URL —— 猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_dispute_appeal','platform_dispute_appeal_material',
    'platform_dispute_appeal_submission','platform_dispute_appeal_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  platform_dispute_appeal, platform_dispute_appeal_material,
  platform_dispute_appeal_submission, platform_dispute_appeal_event TO trade_app;

-- 授权在前、收回在后。
-- ① **留痕追加式**：能被悄悄改掉的留痕等于没有留痕。
REVOKE UPDATE,DELETE ON platform_dispute_appeal_event FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：不碰 V265 的 platform_dispute 簇，
-- 也不碰 channel / warehouse / catalog 的任何表（跨服务禁止 SQL / FK / 视图）。
-- 本模块对外部的全部依赖都是**逻辑引用 + 快照**（平台码 / 平台名 / 平台纠纷号 /
-- 平台订单号 / 来源记录 id 与元数据），跨车道 DDL 回归面 = 0。
