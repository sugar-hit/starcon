-- ===========================================================================
-- V430 · platform_ai_effect_* —— 18.4.3.2「效果评估」
-- ===========================================================================
--
-- **卷 18「集成开放与智能自动化」L3 18.4 AI 治理 → 18.4.3 AI 使用与效果管理
--   → 18.4.3.2 效果评估**
-- （蓝图 18-integration-and-automation.md 18.4.3.2 原文）：
--
--   「**职责**：评估 AI 功能效果：翻译采纳率、预测准确度回测、助手答案有用性反馈，
--     识别低价值功能并下线。
--     **输入**：人工采纳/修改记录；预测回测结果；用户反馈。
--     **输出**：效果报表 + 改进建议。
--     **关系**：上游 = 18.4 全部；下游 = 18.4 迭代。
--     **采纳率是 AI 功能唯一诚实的指标**——生成了但没人用的功能应下线。」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意**做什么 / 不做什么
-- ---------------------------------------------------------------------------
--
--   * **采纳率的分母只有��个来源**：`platform_ai_usage_record`（18.4.3.1「使用与配额」
--     的**只追加**用量台账）。本模块**自己造分母**是错的 —— 拿本模块的反馈条数当分母，
--     算出来的是「反馈率」不是「采纳率」，两者差着好几个数量级。
--     ⇒ 采纳记录（platform_ai_effect_adoption）**只做分子**，永远不做分母。
--
--   * **分子与分母同源**：采纳记录**可以**挂一条真实的用量记录（usage_record_id，
--     复合外键到 platform_ai_usage_record）⇒ 分子与分母计的是同一种单位（**调用次数**），
--     比率天然落在 [0,1]。没挂上时分子仍然计入，但报表会**分开列出**
--     「已关联调用记录的采纳数」与「未关联调用记录的采纳数」——
--     **不把两个口径混成一个数**。
--
--   * **翻译采纳是三态，不是布尔**：ACCEPTED（直接采用，未修改）/ MODIFIED（人工修改后采用）
--     / REJECTED（拒绝）。蓝图原文「翻译采纳 = 用户直接采用 + 人工修改后采用」
--     ⇒ 前两者都算被采纳，REJECTED 不算。只存一个布尔值会把「改了才用」误判成「没用」。
--
--   * **产物只记引用 + 版本快照**：被评估的 AI 产物（18.4.2.1 的译文 / 18.4.2.2 的预测结果）
--     以 **产物 id + 版本号 + 人可读定位码快照**记录，**不复制内容当事实**
--     （内容的事实在它自己的表里，抄一份就变成两份真相）。
--
--   * **回测拿真实销量当实际值**：18.4.2.2 的预测结果是**预测期总需求**，
--     真实值来自 `platform_forecast_demand_history.units`（逐日销量台账）在**同一个预测期**
--     的求和。🔴 **绝不拿预测值自己当实际值**（那是拿自己给自己打满分）。
--     该 SKU 在预测期**一条实际销量都没有** ⇒ 如实记 `NO_ACTUAL_SOURCE`
--     （「无法回测，缺实际值来源」），不记 0。
--
--   * **本模块只出建议，不下线**：真正下线涉及 18.4.3.1 的**功能开关 / 配额设置**。
--     18.4.3.1 当前**没有**按能力粒度的功能开关（它只有配额与敏感策略），
--     ⇒ 本模块**只读**它的数据并输出建议清单，`offline_action` 字段**如实写明**
--     「下线动作需 18.4.3.1 提供开关 / 需人工执行，本模块只出建议清单」。
--     本模块**一个字都不写**进 18.4.3.1 的表。
--
--   * **不依赖 18.4.2.3（异常诊断）**：它在本仓**尚未交付**，助手答案的有用性反馈
--     全部落在本模块**自己的**反馈表里（platform_ai_effect_feedback）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_ai_effect_adoption  —— 人工采纳 / 修改 / 拒绝记录（**三态**）
--   platform_ai_effect_feedback  —— 助手答案有用性反馈（有用 / 部分有用 / 无用）
--   platform_ai_effect_backtest  —— 预测准确度回测结果（误差 + 命中区间）
--   platform_ai_effect_threshold —— 评估阈值（**租户级一行**；判定低价值的口径）
--   platform_ai_effect_suggestion—— 改进建议台账（含低价值功能 → 建议下线）
--
-- 五张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation
-- + 租户触发器兜底 + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「落库时间戳一律取 DB 时钟」
-- ---------------------------------------------------------------------------
--
--   `occurred_at` / `created_at` / `updated_at` 的默认值是 `clock_timestamp()`（**不是** `now()`）：
--   `now()` 在一个事务里是**冻结**的，而一次评估会��同一个事务里写多行。
--
--   ⚠️ 服务层仍然是「先 `select clock_timestamp()` 读出来、再用 `?::timestamptz` 绑定」：
--     读出后用 OffsetDateTime 绑定，避免 `Timestamp.valueOf(LocalDateTime)` 那种
--     静默偏 −28800s 的写法。
--
-- ---------------------------------------------------------------------------
-- 3. 「事实不可改，但可以撤回」
-- ---------------------------------------------------------------------------
--
--   采纳记录与有用性反馈是**事实**：判定、内容、产物引用、提交人、发生时刻
--   **一律不可改**（触发器钉死）。但**填错了要能纠正** ⇒ 提供一个**受控状态机**：
--   `ACTIVE → WITHDRAWN`（撤回），并记下撤回时刻与撤回人。
--   触发器只放行这一个转换的三个字段（status / withdrawn_at / withdrawn_by），
--   改别的字段一律拒绝。
--
--   回测结果**不是**事实而是**计算快照**：真实销量是**后补**的（销量当天没录入、
--   隔天补录），重跑回测必须能刷新 ⇒ 允许 UPDATE（**不授 DELETE**）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_ai_effect_adoption —— 人工采纳 / 修改 / 拒绝记录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个 AI 产物的一次人工处置。
--
-- 🔴 **三态**（不是布尔）：
--   ACCEPTED —— 直接采用（未修改）
--   MODIFIED —— 人工修改后采用
--   REJECTED —— 拒绝
--
-- 🔴 **同一个产物版本只有一条处置**：唯一索引 (tenant_id, artifact_type, 产物, 版本, 定位码)
--    ⇒ 同一个译文版本被登记两次就会**重复计入分子**（分子虚高 ⇒ 采纳率虚高），
--    那等于把采纳率做成一件可以刷的事。
--
-- ⚠️ **artifact_id 刻意不建外键**：18.4.2.1 的译文任务与 18.4.2.2 的预测结果
--    都可能因为自己的生命周期被删掉；本表已经**快照**了产物 id + 版本 + 定位码，
--    外键会把评估历史锁死。服务层在**写入时**校验产物确实存在且属于本租户。
CREATE TABLE platform_ai_effect_adoption (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 能力维度：哪个 AI 功能的产物被处置了（TEXT_TRANSLATION / SALES_FORECAST / …）。
  -- 取值域**开放**：本仓的 AI 能力分布在 18.4 多个兄弟模块里，由各能力自己回报。
  capability_code varchar(64) NOT NULL,
  feature_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴 三态处置。
  decision varchar(16) NOT NULL
    CHECK(decision IN ('ACCEPTED','MODIFIED','REJECTED')),

  -- 产物维度（**只记引用 + 快照，不复制内容**）：
  --   TRANSLATION —— 18.4.2.1 的译文任务（platform_text_task）
  --   FORECAST    —— 18.4.2.2 的预测结果（platform_forecast_result）
  --   ASSISTANT   —— 助手答案（该能力本仓尚未交付 ⇒ 允许不带 id，只带定位码）
  --   OTHER       —— 其它 AI 产物
  artifact_type varchar(24) NOT NULL
    CHECK(artifact_type IN ('TRANSLATION','FORECAST','ASSISTANT','OTHER')),
  artifact_id uuid,
  -- 版本快照：译文草稿的版本号（18.4.2.1 的 platform_text_draft.version）。
  artifact_version int,
  -- 人可读定位码快照（任务号 / 运行号）：产物被删后仍能看出评估的是哪一次。
  artifact_ref varchar(64) NOT NULL DEFAULT '',

  -- 🔴 **用量记录引用（可空）**：挂上之后，分子与分母计的是**同一种单位**（调用次数）。
  --   复合外键 ⇒ 引用不到本租户的调用记录，或引用一个别的租户的记录，都会被 DB 当场拒。
  --   留空 = 这条采纳没有对应的调用记录（上游能力还没接上用量台账时的常态），
  --   报表会把这类分子**单独列出**，不与已关联的混算。
  usage_record_id uuid,

  -- 反馈人：来自**已认证会话**（服务端解析），客户端不能指定。
  membership_id uuid NOT NULL,

  -- 修改后采用时，人工改了什么（**只记差异说明，不抄译文正文**）。
  edit_note varchar(500) NOT NULL DEFAULT '',
  -- 驳回原因（REJECTED 时必填）。
  reject_reason varchar(500) NOT NULL DEFAULT '',

  -- 受控状态机：ACTIVE → WITHDRAWN（撤回）。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','WITHDRAWN')),
  -- 发生时刻：DB 时钟（**客户端不可指定**）。
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  withdrawn_at timestamptz,
  withdrawn_by uuid,
  -- 撤回原因（**只允许在撤回时写**，与 status 一起改）。
  withdrawn_reason varchar(500) NOT NULL DEFAULT '',

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, usage_record_id)
    REFERENCES platform_ai_usage_record(tenant_id, id),

  CHECK(btrim(capability_code) <> '' AND length(capability_code) <= 64),
  CHECK(length(feature_code) <= 64),
  CHECK(length(artifact_ref) <= 64),
  CHECK(artifact_version IS NULL OR artifact_version >= 1),
  -- 本仓自己拥有的两类产物**必须**带 id（否则「引用」是空话）。
  CHECK(artifact_type NOT IN ('TRANSLATION','FORECAST') OR artifact_id IS NOT NULL),
  -- 驳回必须写明原因。
  CHECK(decision <> 'REJECTED' OR btrim(reject_reason) <> ''),
  -- 撤回必须有时刻。
  CHECK(status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL)
);

-- 🔴 **同一产物版本只允许一条处置**（防重复计入分子）。
--   artifact_id / artifact_version 都可能为空 ⇒ 用 COALESCE 折成哨兵值，
--   与 platform_ai_quota 的 NULL 哨兵同一手法（Postgres 的 UNIQUE 视 NULL 互不相等）。
CREATE UNIQUE INDEX platform_ai_effect_adoption_artifact_unique
  ON platform_ai_effect_adoption(
    tenant_id,
    artifact_type,
    (COALESCE(artifact_id, '00000000-0000-0000-0000-000000000000'::uuid)),
    (COALESCE(artifact_version, 0)),
    artifact_ref
  );

-- 按能力维度查采纳率（报表主路径）。
CREATE INDEX platform_ai_effect_adoption_by_capability
  ON platform_ai_effect_adoption(tenant_id, capability_code, feature_code, occurred_at DESC);
-- 按时间倒序翻页。
CREATE INDEX platform_ai_effect_adoption_by_time
  ON platform_ai_effect_adoption(tenant_id, occurred_at DESC);
-- 追某一条调用记录被采纳了几次。
CREATE INDEX platform_ai_effect_adoption_by_usage
  ON platform_ai_effect_adoption(tenant_id, usage_record_id);

-- ---------------------------------------------------------------------------
-- 2. platform_ai_effect_feedback —— 助手答案有用性反馈
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「这个答案有用吗」的反馈。
--
-- 🔴 **三档**（不是布尔）：
--   USEFUL           —— 有用
--   PARTIALLY_USEFUL —— 部分有用
--   NOT_USEFUL       —— 无用
--
-- 🔴 **理由分两截**：reason_code 是**受控词表**（可聚合、可统计），
--    reason_text 是**自由文本**（长度上限 500）。自由文本不做统计口径。
--
-- ⚠️ **不依赖 18.4.2.3**：该能力本仓尚未交付 ⇒ 反馈全部落在本表，
--    助手答案本身用 artifact_ref 定位即可（artifact_id 可空）。
CREATE TABLE platform_ai_effect_feedback (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  capability_code varchar(64) NOT NULL,
  feature_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴 有用性三档。
  usefulness varchar(24) NOT NULL
    CHECK(usefulness IN ('USEFUL','PARTIALLY_USEFUL','NOT_USEFUL')),

  -- 受控理由词表（可聚合）。'' = 未选理由。
  reason_code varchar(32) NOT NULL DEFAULT ''
    CHECK(reason_code IN ('','INACCURATE','TOO_VERBOSE','MISSING_CONTEXT','STYLE_MISMATCH',
                          'WRONG_LANGUAGE','TERM_ISSUE','DATA_STALE','OTHER')),
  -- 自由文本理由（**上限 500**，不参与统计口径）。
  reason_text varchar(500) NOT NULL DEFAULT '',

  -- 被反馈的产物（只记引用 + 快照）。
  artifact_type varchar(24) NOT NULL
    CHECK(artifact_type IN ('TRANSLATION','FORECAST','ASSISTANT','OTHER')),
  artifact_id uuid,
  artifact_version int,
  artifact_ref varchar(64) NOT NULL DEFAULT '',

  -- 反馈人：来自已认证会话。
  membership_id uuid NOT NULL,

  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','WITHDRAWN')),
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  withdrawn_at timestamptz,
  withdrawn_by uuid,
  -- 撤回原因（**只允许在撤回时写**，与 status 一起改）。
  withdrawn_reason varchar(500) NOT NULL DEFAULT '',

  UNIQUE(tenant_id, id),

  CHECK(btrim(capability_code) <> '' AND length(capability_code) <= 64),
  CHECK(length(feature_code) <= 64),
  CHECK(length(reason_text) <= 500),
  CHECK(length(artifact_ref) <= 64),
  CHECK(artifact_version IS NULL OR artifact_version >= 1),
  -- 本仓自己拥有的两类产物**必须**带 id（否则「引用」是空话）。
  CHECK(artifact_type NOT IN ('TRANSLATION','FORECAST') OR artifact_id IS NOT NULL),
  -- 撤回必须有时刻。
  CHECK(status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL)
);

CREATE INDEX platform_ai_effect_feedback_by_capability
  ON platform_ai_effect_feedback(tenant_id, capability_code, feature_code, occurred_at DESC);
CREATE INDEX platform_ai_effect_feedback_by_time
  ON platform_ai_effect_feedback(tenant_id, occurred_at DESC);
CREATE INDEX platform_ai_effect_feedback_by_usefulness
  ON platform_ai_effect_feedback(tenant_id, usefulness, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_ai_effect_backtest —— 预测准确度回测结果
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条预测结果（18.4.2.2）的回测结论。
--
-- 🔴 **实际值来源是真实销量**：`platform_forecast_demand_history.units`（逐日销量台账）
--    在**同一个预测期**内的求和。
--
-- 🔴 **预测期怎么还原**：18.4.2.2 的预测期 = [运行当日 + 1 天, 运行当日 + horizon_days]，
--    但那张表**没有存**这两个日期 ⇒ 本模块按「运行时刻的 UTC 日期 + 1 天」起算、
--    跨 `horizon_days` 天还原，并把还原出来的窗口**写进 actual_window_start/end**
--    （可审计；口径差异也写进 note）。
--
-- 🔴 **没有实际值就如实说没有**：该 SKU 在预测期**一条销量记录都没有**
--    ⇒ status='NO_ACTUAL_SOURCE' + note='无法回测，缺实际值来源'，actual_units 留 NULL。
--    **不记 0**（记 0 等于说「卖了个零」——那是把「没数据」说成「有数据且为零」）。
--
-- ⚠️ **不建外键**：18.4.2.2 的运行/结果可能被它自己的生命周期删掉；
--    本表已快照了预测值、区间、SKU 与运行号 ⇒ 引用悬空也不丢信息。
CREATE TABLE platform_ai_effect_backtest (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 被回测的预测结果（引用 18.4.2.2 的 platform_forecast_result，只记引用）。
  forecast_result_id uuid NOT NULL,
  run_id uuid NOT NULL,
  run_no varchar(32) NOT NULL DEFAULT '',

  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- 预测值快照（点估计 + 置信区间）。
  forecast_point numeric(19,6) NOT NULL,
  forecast_lower numeric(19,6) NOT NULL,
  forecast_upper numeric(19,6) NOT NULL,

  -- 🔴 实际值（真实销量求和）。没有实际值来源时为 NULL。
  actual_units numeric(19,6),
  -- 实际值来自哪张表 / 哪种口径（页面直接显示，不自己编）。
  actual_source varchar(64) NOT NULL DEFAULT '',
  actual_window_start date,
  actual_window_end date,
  -- 预测期内**有销量记录**的天数（窗口长度另由 window 推导 ⇒ 覆盖率可算）。
  actual_record_days int NOT NULL DEFAULT 0,

  -- 误差：绝对误差恒有；相对误差在**实际值为 0** 时**不可计算** ⇒ NULL（不是 0、不是 Infinity）。
  absolute_error numeric(19,6),
  relative_error numeric(9,6),
  -- 实际值是否落在预测的置信区间内（命中区间）。
  within_interval boolean,

  -- 判定：
  --   COMPARED        —— 比过了
  --   NO_ACTUAL_SOURCE—— 无法回测，缺实际值来源
  --   NOT_COMPARABLE  —— 有实际值但不可比（如预测值缺失）
  status varchar(24) NOT NULL
    CHECK(status IN ('COMPARED','NO_ACTUAL_SOURCE','NOT_COMPARABLE')),
  note varchar(500) NOT NULL DEFAULT '',

  evaluated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),
  -- 一个预测结果只有一条回测结论（重跑回测 = 刷新这一行）。
  UNIQUE(tenant_id, forecast_result_id),

  CHECK(btrim(sku_code) <> '' AND length(sku_code) <= 64),
  CHECK(length(sku_name) <= 200),
  CHECK(forecast_point >= 0 AND forecast_lower >= 0 AND forecast_upper >= 0),
  CHECK(forecast_lower <= forecast_point AND forecast_point <= forecast_upper),
  CHECK(actual_units IS NULL OR actual_units >= 0),
  CHECK(absolute_error IS NULL OR absolute_error >= 0),
  CHECK(relative_error IS NULL OR relative_error >= 0),
  CHECK(actual_record_days >= 0),
  CHECK(status <> 'COMPARED' OR actual_units IS NOT NULL),
  -- 有实际值来源就必须写清来源与窗口。
  CHECK(actual_units IS NULL OR (btrim(actual_source) <> '' AND actual_window_start IS NOT NULL
      AND actual_window_end IS NOT NULL))
);

CREATE INDEX platform_ai_effect_backtest_by_run
  ON platform_ai_effect_backtest(tenant_id, run_id, sku_code);
CREATE INDEX platform_ai_effect_backtest_by_sku
  ON platform_ai_effect_backtest(tenant_id, sku_code, evaluated_at DESC);
CREATE INDEX platform_ai_effect_backtest_by_status
  ON platform_ai_effect_backtest(tenant_id, status, evaluated_at DESC);

-- ---------------------------------------------------------------------------
-- 4. platform_ai_effect_threshold —— 评估阈值（租户级一行）
-- ---------------------------------------------------------------------------
--
-- 「什么算低价值」是**业务口径**，不是代码里的常量 ⇒ 归租户维护。
--
-- 🔴 **一行**：同一租户只有一条阈值（UNIQUE(tenant_id)）⇒ 不存在「两条互相矛盾的阈值」
--    （那会让同一个功能的判定取决于读到哪一行）。
--
-- ⚠️ **没配置就用系统默认值**：服务层在没有这一行时返回**文档化的默认阈值**，
--    并在响应里写明「未配置，使用系统默认阈值」—— 不谎称是租户定的。
CREATE TABLE platform_ai_effect_threshold (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 判定「样本不足」的门槛：调用次数低于它就不下低价值结论（**不拿 3 次调用判死刑**）。
  min_calls int NOT NULL DEFAULT 20 CHECK(min_calls >= 0),
  -- 采纳率低于它 ⇒ 低价值（可下线候选）。
  low_adoption_rate numeric(9,6) NOT NULL DEFAULT 0.200000
    CHECK(low_adoption_rate >= 0 AND low_adoption_rate <= 1),
  -- 判定「反馈样本不足」的门槛。
  min_feedback_count int NOT NULL DEFAULT 5 CHECK(min_feedback_count >= 0),
  -- 正向反馈占比低于它 ⇒ 有用性偏低。
  low_useful_rate numeric(9,6) NOT NULL DEFAULT 0.500000
    CHECK(low_useful_rate >= 0 AND low_useful_rate <= 1),
  -- 预测平均绝对百分比误差高于它 ⇒ 预测准确度偏低。
  max_mape numeric(9,6) NOT NULL DEFAULT 0.300000 CHECK(max_mape >= 0),

  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),
  -- 一个租户只有一行阈值。
  UNIQUE(tenant_id)
);

-- ---------------------------------------------------------------------------
-- 5. platform_ai_effect_suggestion —— 改进建议台账
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条针对「某个功能」的改进建议。
--
-- 🔴 **每条建议必须写清四件事**（否则就是空泛口号）：
--   ① 针对哪个功能（capability_code + feature_code）；
--   ② 当前指标是多少（current_metric）；
--   ③ 建议做什么（recommendation）；
--   ④ 依据是什么（evidence）。
--
-- 🔴 **建议类型**：
--   OFFLINE_CANDIDATE   —— 低价值功能，建议下线（蓝图加粗句的落点）
--   LOW_USEFULNESS      —— 有用性反馈里正向占比偏低
--   FORECAST_INACCURACY —— 预测误差超阈值
--   DATA_GAP            —— 样本不足 / 缺实际值来源（**不是**低价值，是没数据）
--
-- 🔴 **本模块只出建议，不执行下线**：offline_action 字段**如实写明**
--   「下线动作需 18.4.3.1 提供开关 / 需人工执行，本模块只出建议清单」。
--
-- ⚠️ **状态机**：OPEN → ACCEPTED / REJECTED（人工决策，**只记录决定，不执行**），
--    另有 RESOLVED（重新生成时条件已不成立 ⇒ 自动关闭）。
CREATE TABLE platform_ai_effect_suggestion (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  capability_code varchar(64) NOT NULL,
  feature_code varchar(64) NOT NULL DEFAULT '',

  suggestion_type varchar(32) NOT NULL
    CHECK(suggestion_type IN ('OFFLINE_CANDIDATE','LOW_USEFULNESS','FORECAST_INACCURACY','DATA_GAP')),
  severity varchar(16) NOT NULL CHECK(severity IN ('HIGH','MEDIUM','LOW')),

  title varchar(200) NOT NULL,

  -- ② 当前指标是多少。
  current_metric varchar(500) NOT NULL DEFAULT '',
  -- ③ 建议做什么。
  recommendation varchar(1000) NOT NULL DEFAULT '',
  -- ④ 依据是什么。
  evidence varchar(1000) NOT NULL DEFAULT '',

  -- 归谁（哪个兄弟模块 / 哪条产品线）。
  action_owner varchar(64) NOT NULL DEFAULT '',
  -- 下线动作怎么走（**本模块不执行**）。
  offline_action varchar(500) NOT NULL DEFAULT '',

  -- 生成这条建议时用的时间窗（可审计：这条建议是在哪个窗口上算出来的）。
  window_start timestamptz NOT NULL,
  window_end timestamptz NOT NULL,

  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','ACCEPTED','REJECTED','RESOLVED')),
  decided_by uuid,
  decided_at timestamptz,
  decision_note varchar(500) NOT NULL DEFAULT '',

  generated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),
  -- 同一功能 + 同一建议类型只有一条（重新生成 = 刷新这一条，不堆重复行）。
  UNIQUE(tenant_id, capability_code, feature_code, suggestion_type),

  CHECK(btrim(capability_code) <> '' AND length(capability_code) <= 64),
  CHECK(length(feature_code) <= 64),
  CHECK(length(title) <= 200),
  CHECK(length(current_metric) <= 500),
  CHECK(length(recommendation) <= 1000),
  CHECK(length(evidence) <= 1000),
  CHECK(length(action_owner) <= 64),
  CHECK(length(offline_action) <= 500),
  CHECK(window_end > window_start),
  CHECK(status NOT IN ('ACCEPTED','REJECTED') OR decided_at IS NOT NULL)
);

CREATE INDEX platform_ai_effect_suggestion_by_capability
  ON platform_ai_effect_suggestion(tenant_id, capability_code, feature_code, status);
CREATE INDEX platform_ai_effect_suggestion_by_status
  ON platform_ai_effect_suggestion(tenant_id, status, generated_at DESC);

-- ===========================================================================
-- 6. 租户隔离（RLS + 触发器兜底）
-- ===========================================================================
--
-- 五张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
-- 🔴 **RLS 之外再加一道租户触发器**：RLS 挡的是「策略不匹配的行」，
--    但**直连 SQL 绕过策略写入**这一类问题仍需要独立兜底 ——
--    写入方的 tenant_id 必须等于当前租户上下文（与 V422 同一纪律）。

ALTER TABLE platform_ai_effect_adoption ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_effect_adoption FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_effect_adoption
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_ai_effect_feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_effect_feedback FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_effect_feedback
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_ai_effect_backtest ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_effect_backtest FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_effect_backtest
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_ai_effect_threshold ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_effect_threshold FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_effect_threshold
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_ai_effect_suggestion ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ai_effect_suggestion FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_ai_effect_suggestion
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- 🔴 **租户触发器兜底**：五张表各一个（错误信息各自点名，避免只看到「租户不对」）。
CREATE FUNCTION platform_ai_effect_adoption_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入采纳记录'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '采纳记录的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_ai_effect_adoption_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_ai_effect_adoption_tenant_guard() TO platform_app;

CREATE TRIGGER platform_ai_effect_adoption_tenant_guard_trigger
  BEFORE INSERT ON platform_ai_effect_adoption
  FOR EACH ROW EXECUTE FUNCTION platform_ai_effect_adoption_tenant_guard();

CREATE FUNCTION platform_ai_effect_feedback_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入有用性反馈'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '有用性反馈的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_ai_effect_feedback_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_ai_effect_feedback_tenant_guard() TO platform_app;

CREATE TRIGGER platform_ai_effect_feedback_tenant_guard_trigger
  BEFORE INSERT ON platform_ai_effect_feedback
  FOR EACH ROW EXECUTE FUNCTION platform_ai_effect_feedback_tenant_guard();

CREATE FUNCTION platform_ai_effect_backtest_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入回测结果'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '回测结果的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_ai_effect_backtest_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_ai_effect_backtest_tenant_guard() TO platform_app;

CREATE TRIGGER platform_ai_effect_backtest_tenant_guard_trigger
  BEFORE INSERT ON platform_ai_effect_backtest
  FOR EACH ROW EXECUTE FUNCTION platform_ai_effect_backtest_tenant_guard();

CREATE FUNCTION platform_ai_effect_threshold_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入评估阈值'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '评估阈值的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_ai_effect_threshold_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_ai_effect_threshold_tenant_guard() TO platform_app;

CREATE TRIGGER platform_ai_effect_threshold_tenant_guard_trigger
  BEFORE INSERT ON platform_ai_effect_threshold
  FOR EACH ROW EXECUTE FUNCTION platform_ai_effect_threshold_tenant_guard();

CREATE FUNCTION platform_ai_effect_suggestion_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入改进建议'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '改进建议的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION platform_ai_effect_suggestion_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_ai_effect_suggestion_tenant_guard() TO platform_app;

CREATE TRIGGER platform_ai_effect_suggestion_tenant_guard_trigger
  BEFORE INSERT ON platform_ai_effect_suggestion
  FOR EACH ROW EXECUTE FUNCTION platform_ai_effect_suggestion_tenant_guard();

-- ===========================================================================
-- 7. 事实不可改（触发器）
-- ===========================================================================
--
-- 🔴 采纳记录与有用性反馈是**事实**：判定 / 内容 / 引用 / 提交人 / 发生时刻**不可改**。
--    但**填错了要能纠正** ⇒ 只放行一个受控转换：ACTIVE → WITHDRAWN。
--    触发器逐列比对：事实列有任何改动 ⇒ 当场拒。
--
-- ⚠️ 为什么不用「只追加 + 不可撤回」：一条填错的采纳记录会让采纳率**永远偏**，
--    而采纳率是本模块唯一的诚实指标 —— 它必须能被纠正。

CREATE FUNCTION platform_ai_effect_adoption_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.capability_code IS DISTINCT FROM OLD.capability_code
     OR NEW.feature_code IS DISTINCT FROM OLD.feature_code
     OR NEW.decision IS DISTINCT FROM OLD.decision
     OR NEW.artifact_type IS DISTINCT FROM OLD.artifact_type
     OR NEW.artifact_id IS DISTINCT FROM OLD.artifact_id
     OR NEW.artifact_version IS DISTINCT FROM OLD.artifact_version
     OR NEW.artifact_ref IS DISTINCT FROM OLD.artifact_ref
     OR NEW.usage_record_id IS DISTINCT FROM OLD.usage_record_id
     OR NEW.membership_id IS DISTINCT FROM OLD.membership_id
     OR NEW.edit_note IS DISTINCT FROM OLD.edit_note
     OR NEW.reject_reason IS DISTINCT FROM OLD.reject_reason
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION '采纳记录是事实记录，不允许改写（只能撤回）'
      USING ERRCODE = '42501';
  END IF;
  -- 只允许 ACTIVE → WITHDRAWN（撤回一次）或原样不动。
  IF NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'WITHDRAWN') THEN
    RAISE EXCEPTION '采纳记录的状态只能从 ACTIVE 变为 WITHDRAWN'
      USING ERRCODE = '42501';
  END IF;
  IF NEW.status = 'WITHDRAWN' AND NEW.withdrawn_at IS NULL THEN
    RAISE EXCEPTION '撤回采纳记录必须写明撤回时刻'
      USING ERRCODE = '42501';
  END IF;
  -- 撤回原因只允许**在撤回时**写；未撤回的记录不能偷偷带一个撤回原因。
  IF NEW.withdrawn_reason IS DISTINCT FROM OLD.withdrawn_reason
     AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'WITHDRAWN') THEN
    RAISE EXCEPTION '撤回原因只能在撤回时写入'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER guard_platform_ai_effect_adoption_update
  BEFORE UPDATE ON platform_ai_effect_adoption
  FOR EACH ROW EXECUTE FUNCTION platform_ai_effect_adoption_guard_update();

CREATE FUNCTION platform_ai_effect_feedback_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.capability_code IS DISTINCT FROM OLD.capability_code
     OR NEW.feature_code IS DISTINCT FROM OLD.feature_code
     OR NEW.usefulness IS DISTINCT FROM OLD.usefulness
     OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
     OR NEW.reason_text IS DISTINCT FROM OLD.reason_text
     OR NEW.artifact_type IS DISTINCT FROM OLD.artifact_type
     OR NEW.artifact_id IS DISTINCT FROM OLD.artifact_id
     OR NEW.artifact_version IS DISTINCT FROM OLD.artifact_version
     OR NEW.artifact_ref IS DISTINCT FROM OLD.artifact_ref
     OR NEW.membership_id IS DISTINCT FROM OLD.membership_id
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION '有用性反馈是事实记录，不允许改写（只能撤回）'
      USING ERRCODE = '42501';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'WITHDRAWN') THEN
    RAISE EXCEPTION '有用性反馈的状态只能从 ACTIVE 变为 WITHDRAWN'
      USING ERRCODE = '42501';
  END IF;
  IF NEW.status = 'WITHDRAWN' AND NEW.withdrawn_at IS NULL THEN
    RAISE EXCEPTION '撤回有用性反馈必须写明撤回时刻'
      USING ERRCODE = '42501';
  END IF;
  -- 撤回原因只允许**在撤回时**写；未撤回的记录不能偷偷带一个撤回原因。
  IF NEW.withdrawn_reason IS DISTINCT FROM OLD.withdrawn_reason
     AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'WITHDRAWN') THEN
    RAISE EXCEPTION '撤回原因只能在撤回时写入'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER guard_platform_ai_effect_feedback_update
  BEFORE UPDATE ON platform_ai_effect_feedback
  FOR EACH ROW EXECUTE FUNCTION platform_ai_effect_feedback_guard_update();

-- ===========================================================================
-- 8. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app。
--
-- ⚠️ **事实台账不给 DELETE**：改不了也删不掉（触发器与授权两个闸门都留着）。
--   回测结果给 UPDATE（真实销量后补 ⇒ 重跑要能刷新），但**不给 DELETE**。
GRANT SELECT,INSERT,UPDATE ON platform_ai_effect_adoption TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_ai_effect_feedback TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_ai_effect_backtest TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_ai_effect_threshold TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_ai_effect_suggestion TO platform_app;
