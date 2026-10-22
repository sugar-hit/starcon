-- ===========================================================================
-- 16.4.1.2 检测与预警 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 182-187 行）：
--   ### 16.4.1.2 检测与预警
--   - 职责：对商品内容做侵权检测：标题/描述/图片/品牌字段比对侵权库，输出风险等级与命中项；
--           接入外部商标/专利查询；已上架商品的定期巡检。
--   - 输入：商品内容与图片；侵权库；外部查询结果。
--   - 输出：检测报告 + 预警 + 整改建议。
--   - 关系：上游 = 16.4.1.1、4.2.1.1；下游 = 16.2.1.2 拦截、16.4.2 授权核验。
--           **图片侵权检测难度高于文本**，误判与漏判都要留人工复核环节。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，**零 ALTER 任何既有表**：
--   * `ip_risk_entry` / `ip_risk_match_term` / `ip_risk_entry_platform`
--     （V315，**同库**）—— 上游 16.4.1.1 侵权库。
--     本模块**只读**它们（比对用的匹配特征就登记在那两张表里），
--     **零 ALTER、零 FK**：那些表是别的车道交付的，改它们的 DDL 就是制造跨车道回归面。
--     引用它们的行**只存逻辑 id**（`entry_id`）+ **快照**（`entry_code` / `right_type` /
--     `risk_level`）⇒ 条目后来改了或被停用，历史报告仍然解释得清「当时命中了什么」。
--   * `listing`（V171）/ `listing_content_snapshot`（V175）/ `listing_editor_draft`（V194）
--     —— **同库**，4 刊登与 4.2.1.1 刊登编辑器：检测对象的内容来源，**只读**。
--   * `shop` / `platform`（V1）—— **同库**，平台目录：只读。
--   * **3.3.2 品牌（catalog 服务）** —— **跨服务**：品牌名**只经授权 HTTP API**读
--     （`IpDetectionSourcePort`），**零跨库 FOREIGN KEY、零跨库视图、零跨服务 SQL**。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.4.1.2，检测与预警）⇒ `ip_detection_*`（本迁移）。
--   * 上游 16.4.1.1 ⇒ `ip_risk_*`（V315，既有）。
--   * 16.4.2.1 ⇒ `brand_authz_*`（V248）；16.4.2.2 ⇒ `brand_cred_*`（V255）。
--   * 16.2.1.2 ⇒ `compliance_intercept_*`（V313）；16.2.1.1 ⇒ `compliance_rule_*`（V311）。
--   * 16.5.1.2 ⇒ `buyer_risk_*`（V316）；16.3.2.2 ⇒ `epr_*`；4.2.3.2 ⇒ `listing_compliance_*`（V198）。
-- ⇒ 八个命名空间彼此隔离；本迁移**零 ALTER 任何既有表**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴🔴 蓝图三句加粗，逐句落成物理事实
-- ---------------------------------------------------------------------------
-- (1)「输出风险等级与命中项」⇒ **可解释性**是硬要求：
--     结论里必须**点名命中了哪一条目、命中了哪些匹配特征**，
--     命中项把条目当时的编码 / 权利类型 / 风险等级**快照**下来。
--     「为什么这个商品被判高风险」永远答得出来 —— 与 16.5.1.2「评分要能解释」同一价值观。
--
-- (2)「**图片侵权检测难度高于文本**，误判与漏判都要留人工复核环节」⇒
--     🔴 **图片命中必须进人工复核队列**：DB 层 CHECK 钉死
--     （`ip_detection_hit.field='IMAGE'` ⇒ `needs_manual_review=true`），
--     且**图片命中永远不单独构成「可判定的高风险」**（见第三节 conclusion 口径）。
--     文本命中也**可以**被运营提为复核（误判申诉），
--     复核队列里**任何一条待复核命中都不计入「可判定命中」**。
--
-- (3)「接入外部商标/专利查询」⇒
--     🔴 本仓**没有**真实的外部商标库凭据与网络出口 ⇒ **绝不写打真实域名的假 HTTP 客户端**。
--     照抄本仓承接外部边界的既有形状（10.4.2.2 的「租户登记的报送表」承接平台货件编号、
--     16.4.2.2 的文件存储承接外部授权文件）：
--     `ip_detection_external_query`（**租户登记的外部查询单**）+ `ip_detection_external_hit`
--     （**登记回来的外部命中**）。检测时外部命中**与本地侵权库一起**参与比对。
--     「自动外呼外部商标库」这件事在能力声明里逐条标 `available=false` + `ownerModule` + 影响面。
--
-- 「已上架商品的定期巡检」⇒
--   * `ip_detection_inspection_plan`（巡检计划：范围 / 间隔 / 是否含图片复核）
--   * `ip_detection_inspection_run`（**一次巡检运行的汇总，append-only**）
--   * 🔴 与 V212 的纪律一致：**本服务不装 `@Scheduled`**
--     （多副本下自带定时器要做主节点选举与分布式锁，那是平台能力不是领域能力），
--     **外部调度**（K8s CronJob）触发 `POST /inspections/runs` 一次入口，
--     天然单次、可重试、可审计。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 结论口径： CLEAR / REVIEW_REQUIRED / ACTION_REQUIRED
-- ---------------------------------------------------------------------------
--   * CLEAR            —— 一条命中都没有。
--   * REVIEW_REQUIRED  —— 有命中，但**全部**还需要人工复核（典型：图片命中）。
--   * ACTION_REQUIRED  —— 存在**可判定**的高风险文本命中（不需复核，或已被复核确认）。
--
-- 🔴 本模块**不做拦截**：下游 **16.2.1.2 拦截执行**（V313，**已交付**）拥有拦截权。
--   本模块只产出 `recommended_action`（**建议**）+ 预警 + 整改建议，
--   **绝不**改 16.2.1.2 的策略表，也**绝不**替下游判「放行 / 阻断」。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 多租户隔离：tenant_id NOT NULL + ENABLE **与 FORCE** ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 本模块的表全部是**租户级**的检测资产（报告 / 命中 / 预警 / 建议 / 复核 /
--   外部查询 / 巡检计划 / 巡检运行）⇒ `isolation` 策略即可，**不引入 data_scope**：
--   「哪些报告归哪个部门管」不是本模块的口径（那属于 4 刊登 / 16.2.1.2 拦截那一层）。
-- ===========================================================================

-- ===========================================================================
-- 一、ip_detection_report · 检测报告（**一次检测 = 一行**）
-- ===========================================================================
CREATE TABLE ip_detection_report (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 检测对象类型（受控值域，DB 层钉死）：
  --   LISTING = 巡检已上架商品（读 listing + listing_content_snapshot）
  --   DRAFT   = 刊登前检测（读 4.2.1.1 的 listing_editor_draft）
  --   MANUAL  = 直接提交商品内容（刊登编辑器之外的一次性检测）
  subject_type      varchar(16) NOT NULL
                    CHECK(subject_type IN ('LISTING','DRAFT','MANUAL')),
  -- 🔴 检测对象 id：
  --   LISTING ⇒ listing.id；DRAFT ⇒ listing_editor_draft.id；MANUAL ⇒ null。
  subject_id        uuid,
  -- 对象的人话名（listing 标题 / 草稿标题 / 手工提交时的标题摘要）—— 空着没法在列表里认。
  subject_label     varchar(300) NOT NULL DEFAULT '',
  -- 目标店铺 / 平台（LISTING 与 DRAFT 才有；MANUAL 没有 ⇒ 都可空）。
  shop_id           uuid,
  platform_code     varchar(32),
  -- 🔴 触发方式：MANUAL = 人工发起；INSPECTION = 巡检产生。
  trigger_type      varchar(16) NOT NULL DEFAULT 'MANUAL'
                    CHECK(trigger_type IN ('MANUAL','INSPECTION')),
  -- 🔴 巡检产生 ⇒ 必须挂在一轮巡检上（巡检产生的报告要能按轮次查）。
  inspection_run_id uuid,
  -- 🔴 风险等级：沿用上游 `ip_risk_entry.risk_level` 的 **HIGH / MEDIUM / LOW** 口径，
  --   另加 NONE 表示「一条命中都没有」。
  --   🔴 **不是 0..100 的裸数字**：裸数字没有口径，「72 分」没法解释成「高风险还是中风险」。
  risk_level        varchar(8) NOT NULL DEFAULT 'NONE'
                    CHECK(risk_level IN ('HIGH','MEDIUM','LOW','NONE')),
  -- 🔴 结论（受控值域，DB 层钉死）：见第三节。
  conclusion        varchar(20) NOT NULL DEFAULT 'CLEAR'
                    CHECK(conclusion IN ('CLEAR','REVIEW_REQUIRED','ACTION_REQUIRED')),
  -- 🔴 建议的下游动作（**只是建议**；拦截归 16.2.1.2，授权核验归 16.4.2）：
  --   NONE = 无；NOTIFY = 通知运营；REMEDIATE = 整改后重检；
  --   INTERCEPT = 建议下游拦截；VERIFY_AUTHORIZATION = 建议走授权核验。
  recommended_action varchar(24) NOT NULL DEFAULT 'NONE'
                    CHECK(recommended_action IN
                      ('NONE','NOTIFY','REMEDIATE','INTERCEPT','VERIFY_AUTHORIZATION')),
  -- 🔴 计数（**可解释性**：一份报告必须说清命中了什么、在哪个字段、命中几条）。
  --   text_hit_count  = 文本 / 品牌字段命中数
  --   image_hit_count = 图片字段命中数（**这些一律要人工复核**）
  --   entry_hit_count = 命中的**不同侵权条目 / 外部命中**数
  --   pending_review_count = 还在等人工复核的命中数
  text_hit_count    int NOT NULL DEFAULT 0 CHECK(text_hit_count >= 0),
  image_hit_count   int NOT NULL DEFAULT 0 CHECK(image_hit_count >= 0),
  entry_hit_count   int NOT NULL DEFAULT 0 CHECK(entry_hit_count >= 0),
  pending_review_count int NOT NULL DEFAULT 0 CHECK(pending_review_count >= 0),
  -- 🔴 实际参与比对的字段数（0 ⇒ 没有可比的内容，报告是空的 —— 如实计数，不假装扫过）。
  scanned_field_count int NOT NULL DEFAULT 0 CHECK(scanned_field_count >= 0),
  -- 内容来源说明（读到了什么 / 什么读不到 —— 「读不到」必须写出来）。
  source_note       text NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 巡检产生的报告必须挂在一轮巡检上；人工发起的报告**不得**挂巡检。
  CHECK((trigger_type = 'INSPECTION') = (inspection_run_id IS NOT NULL)),
  -- 🔴 对象类型与对象 id 必须自洽：MANUAL 没有对象 id，另两类必须有。
  CHECK((subject_type = 'MANUAL') = (subject_id IS NULL)),
  -- 🔴 **没有命中 ⇒ 风险等级必须是 NONE，且结论是 CLEAR**。
  CHECK((risk_level = 'NONE') = (entry_hit_count = 0)),
  CHECK(risk_level <> 'NONE' OR conclusion = 'CLEAR'),
  -- 🔴 命中数与计数字段必须自洽（否则「报告说没命中、命中表里有三条」这种事会出现）。
  CHECK(entry_hit_count <= text_hit_count + image_hit_count),
  CHECK(pending_review_count <= text_hit_count + image_hit_count)
);
COMMENT ON TABLE ip_detection_report IS
  '16.4.1.2 · 检测报告（**一次检测 = 一行**）。risk_level 沿用上游 16.4.1.1 的 HIGH/MEDIUM/LOW 口径，'
  '另加 NONE（无命中）；conclusion 三态（CLEAR / REVIEW_REQUIRED / ACTION_REQUIRED）。'
  '🔴 本模块**不做拦截** —— recommended_action 只是建议，真正的拦截归 16.2.1.2。';
CREATE INDEX ip_detection_report_tenant_created
  ON ip_detection_report(tenant_id, created_at DESC, id);
CREATE INDEX ip_detection_report_tenant_risk
  ON ip_detection_report(tenant_id, risk_level, created_at DESC, id);
CREATE INDEX ip_detection_report_tenant_conclusion
  ON ip_detection_report(tenant_id, conclusion, created_at DESC, id);
CREATE INDEX ip_detection_report_subject
  ON ip_detection_report(tenant_id, subject_type, subject_id, created_at DESC);
CREATE INDEX ip_detection_report_run
  ON ip_detection_report(tenant_id, inspection_run_id, created_at DESC);

-- ===========================================================================
-- 二、ip_detection_hit · 命中项（**append-only**：GRANT 只给 SELECT, INSERT）
-- ===========================================================================
CREATE TABLE ip_detection_hit (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  report_id         uuid NOT NULL,
  -- 🔴 命中的来源：本地侵权库条目（16.4.1.1）**或** 外部查询命中（登记回来的）。
  --   两者**恰好一个**非空（具名 CHECK）—— 不允许「都空」（说不清命中了什么），
  --   也不允许「都填」（那就是把同一件事记了两遍）。
  entry_id          uuid,
  external_hit_id   uuid,
  -- 🔴 命中时的**快照**（可解释性）：条目后来改了 / 被停用了，历史报告仍解释得清。
  entry_code        varchar(64) NOT NULL DEFAULT '',
  right_type        varchar(16) NOT NULL DEFAULT ''
                    CHECK(right_type IN ('TRADEMARK','PATENT','COPYRIGHT','IMAGE_RIGHT')),
  risk_level        varchar(8) NOT NULL
                    CHECK(risk_level IN ('HIGH','MEDIUM','LOW')),
  -- 🔴 命中在哪个字段（受控值域）：
  --   TITLE 标题 / DESCRIPTION 描述 / BULLET 卖点 / IMAGE 图片 / BRAND 品牌
  field             varchar(16) NOT NULL
                    CHECK(field IN ('TITLE','DESCRIPTION','BULLET','IMAGE','BRAND')),
  -- 🔴 命中的**匹配特征**是什么（关键词 / 短语 / 品牌名 / 标识符特征 / 外部关键词）。
  feature_kind      varchar(16) NOT NULL
                    CHECK(feature_kind IN ('TERM','BRAND_NAME','PATTERN','EXTERNAL_TERM')),
  matched_feature   varchar(200) NOT NULL,
  -- 🔴 命中处的原文片段（人可看）—— 没有片段的命中没法复核。
  matched_text      varchar(1000) NOT NULL DEFAULT '',
  -- 证据（JSON）：命中字段、图片来源、比对口径……
  evidence          jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴🔴 **图片命中必须进人工复核**（蓝图加粗句，DB 层钉死）：
  --   field='IMAGE' ⇒ needs_manual_review = true，**不允许**为 false。
  needs_manual_review boolean NOT NULL DEFAULT false,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 命中来源**恰好一个**（具名 CHECK，见上）。
  CHECK((entry_id IS NOT NULL) <> (external_hit_id IS NOT NULL)),
  CHECK(btrim(matched_feature) <> ''),
  -- 🔴🔴 **图片命中 ⇒ 必须人工复核**（蓝图：「图片侵权检测难度高于文本」）。
  CHECK(field <> 'IMAGE' OR needs_manual_review)
);
COMMENT ON TABLE ip_detection_hit IS
  '16.4.1.2 · 命中项（**append-only**）。entry_code / right_type / risk_level 是命中时的**快照** ⇒ '
  '条目后来被改或被停用，历史报告仍解释得清「当时命中了什么」。'
  '🔴 **field=''IMAGE'' ⇒ needs_manual_review=true**（DB 层 CHECK 钉死，图片命中不允许直接定性）。';
CREATE INDEX ip_detection_hit_report
  ON ip_detection_hit(tenant_id, report_id, field, id);
CREATE INDEX ip_detection_hit_entry
  ON ip_detection_hit(tenant_id, entry_id);
CREATE INDEX ip_detection_hit_external
  ON ip_detection_hit(tenant_id, external_hit_id);
CREATE INDEX ip_detection_hit_review
  ON ip_detection_hit(tenant_id, needs_manual_review, created_at DESC);

-- ===========================================================================
-- 三、ip_detection_alert · 预警（**有状态机**）
-- ===========================================================================
CREATE TABLE ip_detection_alert (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  report_id         uuid NOT NULL,
  -- 🔴 预警等级：沿用 HIGH / MEDIUM / LOW 口径。
  alert_level       varchar(8) NOT NULL CHECK(alert_level IN ('HIGH','MEDIUM','LOW')),
  -- 🔴 预警类型（受控值域）：
  --   INFRINGEMENT_HIT = 检出侵权命中
  --   PENDING_REVIEW   = 有命中等着人工复核（典型：图片命中）
  --   EXTERNAL_MATCH   = 命中了登记回来的外部商标 / 专利查询结果
  --   INSPECTION_FOUND = 巡检发现的命中
  alert_type        varchar(20) NOT NULL
                    CHECK(alert_type IN
                      ('INFRINGEMENT_HIT','PENDING_REVIEW','EXTERNAL_MATCH','INSPECTION_FOUND')),
  title             varchar(200) NOT NULL,
  detail            varchar(1000) NOT NULL DEFAULT '',
  -- 🔴 状态机：OPEN → ACKNOWLEDGED → RESOLVED（也可直接 OPEN → RESOLVED）。
  status            varchar(16) NOT NULL DEFAULT 'OPEN'
                    CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
  acknowledged_by   uuid,
  acknowledged_at   timestamptz,
  resolution_note   varchar(500) NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 声称已确认 / 已处置 ⇒ 必须有确认人与确认时刻（两者不许各说各话）。
  CHECK(status = 'OPEN' OR acknowledged_at IS NOT NULL),
  CHECK(status = 'OPEN' OR acknowledged_by IS NOT NULL),
  -- 🔴 已处置必须写处置说明（否则事后没人说得清为什么放过）。
  CHECK(status <> 'RESOLVED' OR btrim(resolution_note) <> ''),
  CHECK(btrim(title) <> '')
);
COMMENT ON TABLE ip_detection_alert IS
  '16.4.1.2 · 预警。状态机 OPEN → ACKNOWLEDGED / RESOLVED 由触发器钉死（终态不可回退）。'
  '🔴 **预警不是拦截**：处置动作归 16.2.1.2，本模块只负责把「发现了什么、建议怎么办」说清楚。';
CREATE INDEX ip_detection_alert_report
  ON ip_detection_alert(tenant_id, report_id, id);
CREATE INDEX ip_detection_alert_status
  ON ip_detection_alert(tenant_id, status, created_at DESC, id);

-- ===========================================================================
-- 四、ip_detection_remediation · 整改建议（**有状态机**）
-- ===========================================================================
CREATE TABLE ip_detection_remediation (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  report_id         uuid NOT NULL,
  -- 建议针对哪条命中（可空：一条整体性建议不必挂某条命中）。
  hit_id            uuid,
  -- 🔴 整改动作类型（受控值域）：
  --   REMOVE_TERM          删掉侵权词 / 短语
  --   REWRITE_TITLE        改写标题
  --   REPLACE_IMAGE        替换图片
  --   VERIFY_BRAND_AUTH    去核验品牌授权（下游 16.4.2）
  --   APPLY_EXEMPTION      申请豁免（下游 16.2.1.2 的豁免流程）
  --   CONTACT_RIGHTS_HOLDER 联系权利人协商
  action_code       varchar(24) NOT NULL
                    CHECK(action_code IN
                      ('REMOVE_TERM','REWRITE_TITLE','REPLACE_IMAGE',
                       'VERIFY_BRAND_AUTH','APPLY_EXEMPTION','CONTACT_RIGHTS_HOLDER')),
  suggestion        varchar(1000) NOT NULL,
  priority          varchar(8) NOT NULL DEFAULT 'MEDIUM'
                    CHECK(priority IN ('HIGH','MEDIUM','LOW')),
  -- 🔴 状态机：OPEN → IN_PROGRESS → DONE / DISMISSED。
  status            varchar(16) NOT NULL DEFAULT 'OPEN'
                    CHECK(status IN ('OPEN','IN_PROGRESS','DONE','DISMISSED')),
  assignee_id       uuid,
  due_at            timestamptz,
  completed_at      timestamptz,
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(suggestion) <> ''),
  -- 🔴 声称已办完 / 已放弃 ⇒ 必须有完成时刻。
  CHECK(status NOT IN ('DONE','DISMISSED') OR completed_at IS NOT NULL)
);
COMMENT ON TABLE ip_detection_remediation IS
  '16.4.1.2 · 整改建议（蓝图的三个输出之一）。'
  '🔴 建议里的动作**指向**下游（16.4.2 授权核验 / 16.2.1.2 豁免），本模块**不代下游执行**。';
CREATE INDEX ip_detection_remediation_report
  ON ip_detection_remediation(tenant_id, report_id, id);
CREATE INDEX ip_detection_remediation_status
  ON ip_detection_remediation(tenant_id, status, priority, created_at DESC);

-- ===========================================================================
-- 五、ip_detection_review_task · 人工复核队列（**有状态机**，图片命中的必经环节）
-- ===========================================================================
CREATE TABLE ip_detection_review_task (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  report_id         uuid NOT NULL,
  -- 复核针对哪条命中。
  hit_id            uuid,
  -- 🔴 复核原因（受控值域）：
  --   IMAGE_MATCH  = 图片命中（**自动进队列**，蓝图要求）
  --   TEXT_MISMATCH = 文本命中被判误判（运营提起的申诉）
  --   APPEAL        = 权利人 / 品牌方申诉
  reason            varchar(16) NOT NULL
                    CHECK(reason IN ('IMAGE_MATCH','TEXT_MISMATCH','APPEAL')),
  -- 🔴 状态机：PENDING → CONFIRMED / DISMISSED（终态不可回退）。
  status            varchar(16) NOT NULL DEFAULT 'PENDING'
                    CHECK(status IN ('PENDING','CONFIRMED','DISMISSED')),
  reviewer_id       uuid,
  reviewed_at       timestamptz,
  review_comment    varchar(1000) NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一个命中**只允许一条**复核任务（重复进队列会让「待复核数」虚高）。
  UNIQUE(tenant_id, hit_id),
  -- 🔴 待复核 ⇒ 没有复核人、没有复核时刻、没有结论。
  CHECK(status = 'PENDING' OR (reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL)),
  CHECK(status = 'PENDING' OR btrim(review_comment) <> ''),
  CHECK(status = 'PENDING' OR reviewed_at IS NOT NULL)
);
COMMENT ON TABLE ip_detection_review_task IS
  '16.4.1.2 · 人工复核队列。🔴 **图片命中自动进这里**（蓝图：「误判与漏判都要留人工复核环节」）；'
  '文本命中也可由运营提复核（误判申诉）。终态不可回退 —— 复核结论是**证据**。';
CREATE INDEX ip_detection_review_task_queue
  ON ip_detection_review_task(tenant_id, status, created_at DESC, id);
CREATE INDEX ip_detection_review_task_report
  ON ip_detection_review_task(tenant_id, report_id, id);

-- ===========================================================================
-- 六、ip_detection_external_query · 外部商标 / 专利查询登记（承接外部边界）
-- ===========================================================================
CREATE TABLE ip_detection_external_query (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 查询编码：租户内稳定业务键（**幂等锚点**）。
  query_code        varchar(64) NOT NULL,
  -- 🔴 权利类型：沿用上游口径（四种）。
  right_type        varchar(16) NOT NULL
                    CHECK(right_type IN ('TRADEMARK','PATENT','COPYRIGHT','IMAGE_RIGHT')),
  -- 查询词（品牌名 / 商标名 / 专利名）。
  keyword           varchar(200) NOT NULL,
  -- 适用平台（可空 = 不限平台）。
  platform_code     varchar(32),
  -- 🔴 查询来源（人可读）：外部商标库 / 代理机构 / 平台知识产权投诉库 ……
  source_name       varchar(200) NOT NULL DEFAULT '',
  -- 🔴 查询状态：
  --   REGISTERED = 已登记（还没查）
  --   RETURNED   = 已查回结果（result_count > 0）
  --   NO_RESULT  = 查了，没有结果
  --   FAILED     = 查了，没查成（error_message 必填）
  query_status      varchar(16) NOT NULL DEFAULT 'REGISTERED'
                    CHECK(query_status IN ('REGISTERED','RETURNED','NO_RESULT','FAILED')),
  result_count      int NOT NULL DEFAULT 0 CHECK(result_count >= 0),
  error_message     varchar(500) NOT NULL DEFAULT '',
  queried_at        timestamptz,
  note              varchar(1000) NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 租户内查询编码唯一 ⇒ 重复登记不产生第二条（**幂等锚点**）。
  UNIQUE(tenant_id, query_code),
  CHECK(btrim(query_code) <> ''),
  CHECK(btrim(keyword) <> ''),
  -- 🔴 查回结果 ⇒ 必须有结果数且 > 0，且必须有查询时刻。
  CHECK(query_status <> 'RETURNED' OR (result_count > 0 AND queried_at IS NOT NULL)),
  -- 🔴 查成空 ⇒ 结果数必须是 0（不许「查回 0 条」和「查回 3 条」同时成立）。
  CHECK(query_status <> 'NO_RESULT' OR result_count = 0),
  -- 🔴 查失败 ⇒ 必须写原因（不然就是静默丢了一次查询）。
  CHECK(query_status <> 'FAILED' OR btrim(error_message) <> '')
);
COMMENT ON TABLE ip_detection_external_query IS
  '16.4.1.2 · 外部商标 / 专利查询登记。'
  '🔴 本仓**没有**外部商标库的凭据与网络出口 ⇒ 本表是**承接外部边界的登记面**'
  '（与 10.4.2.2 报送表、16.4.2.2 凭证文件同一手法），**不是**自动外呼。';
CREATE INDEX ip_detection_external_query_tenant
  ON ip_detection_external_query(tenant_id, created_at DESC, id);
CREATE INDEX ip_detection_external_query_status
  ON ip_detection_external_query(tenant_id, query_status, created_at DESC);

-- ===========================================================================
-- 七、ip_detection_external_hit · 外部查询结果（**append-only**）
-- ===========================================================================
CREATE TABLE ip_detection_external_hit (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  query_id          uuid NOT NULL,
  -- 🔴 外部库返回的关键词 / 品牌名（**检测时与内容比对的就是它**）。
  matched_key       varchar(200) NOT NULL,
  right_type        varchar(16) NOT NULL
                    CHECK(right_type IN ('TRADEMARK','PATENT','COPYRIGHT','IMAGE_RIGHT')),
  risk_level        varchar(8) NOT NULL CHECK(risk_level IN ('HIGH','MEDIUM','LOW')),
  -- 外部注册号 / 申请号（可空）。
  registration_no   varchar(100),
  rights_holder     varchar(200) NOT NULL DEFAULT '',
  source_name       varchar(200) NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(matched_key) <> '')
);
COMMENT ON TABLE ip_detection_external_hit IS
  '16.4.1.2 · 外部商标 / 专利查询**回来的命中**（**append-only**）。'
  '检测时它与本地侵权库**一起**参与比对（命中项用 external_hit_id 指过来）。';
CREATE INDEX ip_detection_external_hit_query
  ON ip_detection_external_hit(tenant_id, query_id, id);
CREATE INDEX ip_detection_external_hit_key
  ON ip_detection_external_hit(tenant_id, matched_key);

-- ===========================================================================
-- 八、ip_detection_inspection_plan · 巡检计划
-- ===========================================================================
CREATE TABLE ip_detection_inspection_plan (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 计划编码：租户内稳定业务键（**幂等锚点**）。
  plan_code         varchar(64) NOT NULL,
  -- 🔴 巡检范围：ALL = 本租户全部已上架商品；SHOP = 某个店铺。
  scope             varchar(8) NOT NULL CHECK(scope IN ('ALL','SHOP')),
  shop_id           uuid,
  -- 🔴 巡检间隔（天）。外部调度按这个间隔调本模块的运行入口。
  interval_days     int NOT NULL CHECK(interval_days BETWEEN 1 AND 365),
  -- 🔴 是否包含图片检测。**默认含**（图片命中一律走人工复核）——
  --   关掉它等于承认「图片不用查」，那是漏判，页面与能力声明都显式标出来。
  include_image     boolean NOT NULL DEFAULT true,
  enabled           boolean NOT NULL DEFAULT true,
  last_run_at       timestamptz,
  next_run_at       timestamptz,
  note              varchar(500) NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, plan_code),
  CHECK(btrim(plan_code) <> ''),
  -- 🔴 范围与店铺必须自洽：SHOP 必须指定店铺，ALL 不得带店铺。
  CHECK((scope = 'SHOP') = (shop_id IS NOT NULL))
);
COMMENT ON TABLE ip_detection_inspection_plan IS
  '16.4.1.2 · 已上架商品的定期巡检计划。'
  '🔴 **本服务不装 @Scheduled`**（多副本下自带定时器要做主节点选举与分布式锁，'
  '那是平台能力不是领域能力）—— 外部调度（K8s CronJob）按 interval_days 调运行入口。';
CREATE INDEX ip_detection_inspection_plan_tenant
  ON ip_detection_inspection_plan(tenant_id, enabled, next_run_at, id);

-- ===========================================================================
-- 九、ip_detection_inspection_run · 巡检运行（**append-only**：一次运行 = 一行）
-- ===========================================================================
CREATE TABLE ip_detection_inspection_run (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  plan_id           uuid NOT NULL,
  -- 🔴 运行状态（**终态**：COMPLETED 全部扫完 / PARTIAL 有商品没扫成 / FAILED 整轮没跑成）。
  --   🔴 **没有「运行中」这一态**：本表的行是**一轮跑完之后的回执**，
  --   跑的过程中先不落行 ⇒ 表可以真正做到 append-only（只给 SELECT, INSERT）。
  --   一轮扫描是**同步**的，调用方拿到的就是终态；中途崩溃则这一轮**没有回执**，
  --   计划的 last_run_at 也不会前进 ⇒ 下次调度会重跑这一轮（不会静默丢一次巡检）。
  status            varchar(16) NOT NULL
                    CHECK(status IN ('COMPLETED','PARTIAL','FAILED')),
  -- 🔴 汇总计数（这一轮扫了多少、查出多少）。
  listing_scanned   int NOT NULL DEFAULT 0 CHECK(listing_scanned >= 0),
  report_count      int NOT NULL DEFAULT 0 CHECK(report_count >= 0),
  hit_listing_count int NOT NULL DEFAULT 0 CHECK(hit_listing_count >= 0),
  error_message     varchar(1000) NOT NULL DEFAULT '',
  started_at        timestamptz NOT NULL DEFAULT now(),
  finished_at       timestamptz NOT NULL,
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 跑失败 ⇒ 必须写原因。
  CHECK(status <> 'FAILED' OR btrim(error_message) <> '')
);
COMMENT ON TABLE ip_detection_inspection_run IS
  '16.4.1.2 · 巡检运行汇总（**append-only**）。一轮运行产生多份 ip_detection_report'
  '（每份挂 inspection_run_id）⇒ 「上次巡检查出了什么」答得出来。';
CREATE INDEX ip_detection_inspection_run_plan
  ON ip_detection_inspection_run(tenant_id, plan_id, started_at DESC, id);

-- ===========================================================================
-- 十、触发器
-- ===========================================================================

-- 🔴 (a) 命中项：必须挂在本租户真实存在的一份检测报告上；**不可改写、不可删除**。
--   命中项是**证据**：事后改写它等于把「当时命中了什么」做成假的。
CREATE FUNCTION guard_ip_detection_hit_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM ip_detection_report r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.report_id) THEN
    RAISE EXCEPTION
      '命中项引用了一份在本租户不存在的检测报告：%', NEW.report_id
      USING ERRCODE='23514';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '命中项 % 不可删除（append-only）', OLD.id USING ERRCODE='23514';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION '命中项 % 不可改写（append-only）', OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_hit_ref BEFORE INSERT OR UPDATE OR DELETE ON ip_detection_hit
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_hit_ref();

-- 🔴 (b) 外部命中：必须挂在一条真实存在的外部查询上；**不可改写、不可删除**。
CREATE FUNCTION guard_ip_detection_external_hit_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM ip_detection_external_query q
        WHERE q.tenant_id = NEW.tenant_id AND q.id = NEW.query_id) THEN
    RAISE EXCEPTION
      '外部命中引用了一条在本租户不存在的外部查询：%', NEW.query_id
      USING ERRCODE='23514';
  END IF;
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION '外部命中 % 不可改写或删除（append-only）', OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_external_hit_ref
  BEFORE INSERT OR UPDATE OR DELETE ON ip_detection_external_hit
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_external_hit_ref();

-- 🔴 (c) 巡检运行：必须挂在一条真实存在的巡检计划上；**不可改写、不可删除**。
CREATE FUNCTION guard_ip_detection_inspection_run_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM ip_detection_inspection_plan p
        WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.plan_id) THEN
    RAISE EXCEPTION
      '巡检运行引用了一条在本租户不存在的巡检计划：%', NEW.plan_id
      USING ERRCODE='23514';
  END IF;
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION '巡检运行 % 不可改写或删除（append-only）', OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_inspection_run_ref
  BEFORE INSERT OR UPDATE OR DELETE ON ip_detection_inspection_run
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_inspection_run_ref();

-- 🔴 (d) 复核任务：必须挂在真实存在的报告与命中上；🔴 **图片命中必须真的进队列**。
CREATE FUNCTION guard_ip_detection_review_task_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM ip_detection_report r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.report_id) THEN
    RAISE EXCEPTION
      '复核任务引用了一份在本租户不存在的检测报告：%', NEW.report_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.hit_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM ip_detection_hit h
        WHERE h.tenant_id = NEW.tenant_id AND h.id = NEW.hit_id
          AND h.report_id = NEW.report_id) THEN
    RAISE EXCEPTION
      '复核任务引用的命中不存在或不属于同一份报告：%', NEW.hit_id
      USING ERRCODE='23514';
  END IF;
  -- 🔴🔴 **图片命中不许绕过人工复核**：
  --   若这条命中是图片命中且标记为「不需要复核」，直接拒 —— 蓝图要求图片命中必须人工复核。
  --   🔴 这道闸**只在 INSERT 时生效**：复核**结论**本身（PENDING → CONFIRMED / DISMISSED）
  --   正是「过了人工复核」的结果，不能被这道闸误伤。
  IF TG_OP = 'INSERT' AND NEW.hit_id IS NOT NULL AND EXISTS (
       SELECT 1 FROM ip_detection_hit h
        WHERE h.id = NEW.hit_id AND h.field = 'IMAGE' AND h.needs_manual_review) THEN
    IF NEW.status <> 'PENDING' THEN
      RAISE EXCEPTION
        '图片命中 % 的复核任务必须从 PENDING 开始（图片命中必须过人工复核）', NEW.hit_id
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_review_task_ref BEFORE INSERT OR UPDATE ON ip_detection_review_task
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_review_task_ref();

-- 🔴 (e) 复核状态机：PENDING → CONFIRMED / DISMISSED，**终态不可回退、不可删除**。
CREATE FUNCTION guard_ip_detection_review_task_state() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '复核任务 % 不可删除（复核结论是证据）', OLD.id USING ERRCODE='23514';
  END IF;
  IF OLD.status <> 'PENDING' AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION
      '复核任务 % 已终结（%），不可回退到 %', OLD.id, OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;
  IF OLD.status = 'PENDING' AND NEW.status <> 'PENDING'
     AND NEW.status NOT IN ('CONFIRMED','DISMISSED') THEN
    RAISE EXCEPTION
      '复核任务 % 只能从 PENDING 走到 CONFIRMED / DISMISSED，不能走到 %', OLD.id, NEW.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_review_task_state BEFORE UPDATE OR DELETE ON ip_detection_review_task
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_review_task_state();

-- 🔴 (f) 预警状态机：OPEN → ACKNOWLEDGED / RESOLVED；**终态不可回退、不可删除**。
CREATE FUNCTION guard_ip_detection_alert_state() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '预警 % 不可删除（预警记录是证据）', OLD.id USING ERRCODE='23514';
  END IF;
  IF OLD.status IN ('ACKNOWLEDGED','RESOLVED') AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION
      '预警 % 已走到 %，不可回退到 %', OLD.id, OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;
  IF OLD.status = 'OPEN' AND NEW.status NOT IN ('ACKNOWLEDGED','RESOLVED') THEN
    RAISE EXCEPTION
      '预警 % 只能从 OPEN 走到 ACKNOWLEDGED / RESOLVED，不能走到 %', OLD.id, NEW.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_alert_state BEFORE UPDATE OR DELETE ON ip_detection_alert
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_alert_state();

-- 🔴 (g) 整改建议状态机：OPEN → IN_PROGRESS → DONE / DISMISSED；终态不可回退、不可删除。
CREATE FUNCTION guard_ip_detection_remediation_state() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '整改建议 % 不可删除', OLD.id USING ERRCODE='23514';
  END IF;
  IF OLD.status IN ('DONE','DISMISSED') AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION
      '整改建议 % 已终结（%），不可回退到 %', OLD.id, OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;
  IF OLD.status = 'OPEN' AND NEW.status NOT IN ('IN_PROGRESS','DONE','DISMISSED') THEN
    RAISE EXCEPTION
      '整改建议 % 只能从 OPEN 走到 IN_PROGRESS / DONE / DISMISSED，不能走到 %', OLD.id, NEW.status
      USING ERRCODE='23514';
  END IF;
  IF OLD.status = 'IN_PROGRESS' AND NEW.status NOT IN ('DONE','DISMISSED') THEN
    RAISE EXCEPTION
      '整改建议 % 只能从 IN_PROGRESS 走到 DONE / DISMISSED，不能走到 %', OLD.id, NEW.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_remediation_state BEFORE UPDATE OR DELETE ON ip_detection_remediation
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_remediation_state();

-- 🔴 (h) 整改建议 / 复核任务必须挂在本租户真实存在的报告上。
CREATE FUNCTION guard_ip_detection_remediation_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM ip_detection_report r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.report_id) THEN
    RAISE EXCEPTION
      '整改建议引用了一份在本租户不存在的检测报告：%', NEW.report_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_remediation_ref BEFORE INSERT ON ip_detection_remediation
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_remediation_ref();

-- 🔴 (i) 巡检计划引用的店铺必须在本租户真实存在（零外键，触发器校验）。
CREATE FUNCTION guard_ip_detection_inspection_plan_shop() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.scope = 'SHOP' AND NOT EXISTS (
       SELECT 1 FROM shop s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.shop_id) THEN
    RAISE EXCEPTION
      '巡检计划引用了一个在本租户不存在的店铺：%', NEW.shop_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_inspection_plan_shop BEFORE INSERT OR UPDATE ON ip_detection_inspection_plan
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_inspection_plan_shop();

-- 🔴 (j) 巡检运行完成后，计划的 last_run_at / next_run_at 只能由运行动作推进 ——
--   直接改这两个字段等于伪造「巡检跑过」。触发器只允许**前进**。
CREATE FUNCTION guard_ip_detection_inspection_plan_run_marks() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.last_run_at IS NOT NULL AND NEW.last_run_at IS NOT NULL
     AND NEW.last_run_at < OLD.last_run_at THEN
    RAISE EXCEPTION
      '巡检计划的 last_run_at 只能前进，不能回退（% → %）', OLD.last_run_at, NEW.last_run_at
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ip_detection_inspection_plan_run_marks
  BEFORE UPDATE ON ip_detection_inspection_plan
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_inspection_plan_run_marks();

-- 🔴 (k) **不做物理删除**：报告 / 预警 / 整改建议 / 复核任务都不可 DELETE。
--   下游 16.2.1.2 拦截与 16.4.2 授权核验可能已经引用过这些结论，
--   物理删除会让那些历史记录悬空。
CREATE FUNCTION guard_ip_detection_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% % 不可物理删除（结论与证据都要保留）', TG_TABLE_NAME, OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER ip_detection_report_no_delete BEFORE DELETE ON ip_detection_report
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_no_delete();
CREATE TRIGGER ip_detection_alert_no_delete BEFORE DELETE ON ip_detection_alert
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_no_delete();
CREATE TRIGGER ip_detection_remediation_no_delete BEFORE DELETE ON ip_detection_remediation
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_no_delete();
CREATE TRIGGER ip_detection_review_task_no_delete BEFORE DELETE ON ip_detection_review_task
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_no_delete();
CREATE TRIGGER ip_detection_external_query_no_delete BEFORE DELETE ON ip_detection_external_query
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_no_delete();
CREATE TRIGGER ip_detection_inspection_plan_no_delete BEFORE DELETE ON ip_detection_inspection_plan
  FOR EACH ROW EXECUTE FUNCTION guard_ip_detection_no_delete();

-- ===========================================================================
-- 十一、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ip_detection_report','ip_detection_hit','ip_detection_alert',
    'ip_detection_remediation','ip_detection_review_task',
    'ip_detection_external_query','ip_detection_external_hit',
    'ip_detection_inspection_plan','ip_detection_inspection_run' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 十二、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

-- 🔴 **append-only 的三张表刻意不给 UPDATE / DELETE**：
--   命中项、外部命中、巡检运行这三样是**证据 / 回执** ——
--   事后能改写它们，检测结论就随时可以做成假的。
GRANT SELECT,INSERT ON
  ip_detection_hit, ip_detection_external_hit, ip_detection_inspection_run TO channel_app;

-- 其余六张是**可流转的状态**（报告的结论随复核结果修订、预警与建议要走状态机）。
-- 🔴 仍然**一律不给 DELETE**（上面的 guard 触发器也钉着）。
GRANT SELECT,INSERT,UPDATE ON
  ip_detection_report, ip_detection_alert, ip_detection_remediation,
  ip_detection_review_task, ip_detection_external_query, ip_detection_inspection_plan TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V316 同口径）。
REVOKE ALL ON
  ip_detection_report, ip_detection_hit, ip_detection_alert,
  ip_detection_remediation, ip_detection_review_task,
  ip_detection_external_query, ip_detection_external_hit,
  ip_detection_inspection_plan, ip_detection_inspection_run FROM starcon_app;

-- 🔴 **序列授权**：本迁移的九张表**全部用应用侧生成的 uuid 作主键**，
--   没有任何 serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 / V300 / V311 / V315 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 检测与预警属于「平台操作 / 合规台账」语义，
-- 与 16.4.1.1 / 16.2.1.2 同族，复用 identity 已注册的 channel.operation.*
-- （读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 十三、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **上游 16.4.1.1 侵权库**（V315，同库）：本模块**只读** `ip_risk_entry` /
--     `ip_risk_match_term` / `ip_risk_entry_platform`；**零 ALTER、零 FK**。
--     命中项只存**逻辑 id + 快照** ⇒ 上游条目后来被停用 / 改写，历史报告仍解释得清。
--   * **上游 4.2.1.1 刊登编辑器**（V194，同库）：`listing_editor_draft.field_values`
--     的 TITLE / BULLETS / DESCRIPTION / IMAGES 是「刊登前检测」的内容来源，**只读**。
--   * **上游 3.3.2 品牌**（catalog 服务）：品牌名**只经授权 HTTP API**读
--     （`IpDetectionSourcePort`），**零跨库 FK / 影子表**；
--     读不到时**显式说读不到**（available=false + 原因），
--     **绝不返回空字符串冒充「这个商品没有品牌」**。
--   * **下游 16.2.1.2 拦截执行**（V313，**已交付**）：拦截权归它。
--     本模块**只产出建议**（`recommended_action`），**不写它的策略表、不代它判放行 / 阻断**。
--   * **下游 16.4.2 授权核验**（16.4.2.1 V248 / 16.4.2.2 V255，**已交付**）：
--     品牌类命中整改建议指向它（`VERIFY_BRAND_AUTH`），本模块**不代它核验授权**。
--   * **外部商标 / 专利库自动外呼**：**未交付**（本仓无凭据、无网络出口）⇒
--     以 `ip_detection_external_query` / `ip_detection_external_hit` **登记承接**，
--     能力声明里如实标 available=false + ownerModule + 影响面。
-- ---------------------------------------------------------------------------
