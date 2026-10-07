-- ===========================================================================
-- 7.6.2.1 库存健康：一次健康度评估的落库凭据
-- ===========================================================================
-- 蓝图原文：「库存健康度诊断：周转率、缺货率、超储率、呆滞占比、库存准确率、负库存发生次数，
-- 按仓库与类目打分并给出改进建议」「输出：健康度评分卡 + 趋势 + 问题清单与建议」
-- 「**库存准确率是硬指标**，低于阈值应阻断结算与对账——账不准时报表全是错的」。
--
-- **为什么拆成三张表而不是一张**
--   一张表装不下「一次评估」这个整体：一次评估 = 1 行头（窗口、范围、得分、覆盖度、评级）
--   + 6 行指标（每行一个蓝图指标，含不可用的）。把它们塞进一张表，就只能让 6 行各带一份
--   重复的得分，而「得分」是这 6 行**算出来的**结果 —— 于是同一份得分会出现 6 份副本，
--   改一处就要改六处，迟早对不上。所以：
--     inventory_health_assessment 头（一次评估一行）
--     inventory_health_metric     指标明细（一次评估六行，恒定存在，含不可用的）
--     inventory_health_verdict    结论（准确率红线判定 + 问题计数，一次评估一行）
--
-- **本表存什么、不存什么**
--   存：某一时点、某个评估窗口下，**六个指标各自的取值（或"不可用 + 原因"）**与据此打出的分数，
--        以及当时做出的阻断判定。它是"那一刻我们认为账准不准、准到什么程度"的凭据。
--   不存：周转率 / 缺货率 / 超储率 / 呆滞占比的**伪造值**。这四个指标依赖销售聚合，
--        而 13 销量与 7.6.1 均未交付。可用性设计见下方 metric_state 的说明。
--        宁可让 metric_state 如实记 UNAVAILABLE，也不拿库存自身数据拼一个假的周转率 ——
--        假指标比没有指标更危险：它会被当真去调补货，而那时真正的缺口一个也没被修掉。
--
-- **为什么已经能算的指标还要落库（两个理由，都不是"留个历史方便看"）**
--   1. 阻断判定必须是**当时**的判定。财务来问"上个月为什么没让我结算"时，答案是快照里
--      写下的那一行；重算一遍得到的可能是另一个数（窗口不同、样本不同），
--      那这份审计就失去意义了。与 V54 的核对结论同一纪律：结论一旦写下就不改。
--   2. 打分是多指标加权 + 扣分项累加，扣分项各自带证据行。趋势要画的是**当时的分数序列**，
--      不是用今天的口径把过去重算一遍 —— 后者在指标口径演进后会自我篡改。
--
-- **口径纪律**
--   - metric_state 三值：AVAILABLE / UNAVAILABLE / NO_DATA。
--     UNAVAILABLE = 上游未交付，口径写死了但**没有数据**；NO_DATA = 上游已交付但这个租户
--     窗口内没有可算的样本。两者都不给值，但原因不同，运营该做的动作也不同。
--   - 比率为 numeric(8,6) 口径，CHECK 钉死 0..1。比 0 大 1 小和空值不是一回事。
--   - score 是 0..100；**只对 AVAILABLE 的指标加权**，并把 available_metric_count /
--     total_metric_count 一起落库 —— 六个指标里只算得出一个时，那个分数必须让人看出它
--     不是"健康度"，而是"已覆盖维度上的健康度"。
--   - **按仓库打分可算，按类目不可算**：sku_ref / inventory_balance 上都没有类目列，
--     而类目的真相在 catalog（3.3.1.1 类目树），跨服务不得 join（AGENTS.md 模块边界纪律）。
--     所以本表没有 CATEGORY 粒度，如实不提供，不拿仓库的分数冒充类目的分数。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 评估头
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_health_assessment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 评估窗口：左闭右开，与 V54 核对窗口同一套语义。
  window_from timestamptz NOT NULL,
  window_to timestamptz NOT NULL,

  -- 评估粒度。TENANT 行是全租户汇总，WAREHOUSE 行按仓。
  scope_type varchar(16) NOT NULL CHECK(scope_type IN ('TENANT','WAREHOUSE')),
  scope_key uuid,
  -- 范围可读名固化下来：仓名改了，三个月前那次评估仍然读得懂（与 V54 scope_label 同理由）。
  scope_label varchar(150) NOT NULL DEFAULT '',
  -- TENANT 不带键（"全部"不是某个具体对象的 id），WAREHOUSE 必须带键。
  CHECK((scope_type='TENANT')=(scope_key IS NULL)),

  -- 只对 AVAILABLE 的指标加权求和；覆盖度一起落库，防止"一个指标 100 分"被读成"整体健康"。
  score numeric(5,2) NOT NULL CHECK(score>=0 AND score<=100),
  available_metric_count integer NOT NULL CHECK(available_metric_count>=0 AND available_metric_count<=6),
  total_metric_count integer NOT NULL CHECK(total_metric_count=6),
  -- 评级：HEALTHY / WATCH / RISK / UNVERIFIABLE。
  -- UNVERIFIABLE 是真实存在的状态：算得出的指标不足以支撑任何判断时，不该假装给个分数。
  grade varchar(16) NOT NULL
    CHECK(grade IN ('HEALTHY','WATCH','RISK','UNVERIFIABLE')),
  -- 准确率阈值随评估一起落库：事后改阈值不应改写过去的结论。
  accuracy_threshold numeric(8,6) NOT NULL CHECK(accuracy_threshold>=0 AND accuracy_threshold<=1),

  -- 评估时刻。与 window_to 分开：窗口右边界是「数据截至哪」，本列是「我们什么时候算的」。
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(window_from < window_to),
  -- 硬指标没证据时不可能判健康：UNVERIFIABLE 必须且只能出现在准确率无证据时。
  -- 少了这条就会出现"没核过账但显示健康"这种最危险的结果。
  CHECK((grade='UNVERIFIABLE') OR available_metric_count>0)
);

-- 列表默认按最近一次评估排：问得最多的是"现在这仓有多准"。
CREATE INDEX inventory_health_assessment_recent
  ON inventory_health_assessment(tenant_id, created_at DESC, id);
-- 画趋势：同一范围按窗口倒序扫。
CREATE INDEX inventory_health_assessment_trend
  ON inventory_health_assessment(tenant_id, scope_type, scope_key, window_to DESC, id);

-- ---------------------------------------------------------------------------
-- 2. 指标明细（一次评估恒定六行，含不可用的）
-- ---------------------------------------------------------------------------
-- 六行恒定存在：可算的给 value，算不出的 value 为 NULL 且 state 说明原因。
-- 用固定行而不是稀疏 JSON：不可用本身就是要被查询、被上报、被追债的信息。
CREATE TABLE inventory_health_metric (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  assessment_id uuid NOT NULL,

  metric_type varchar(24) NOT NULL CHECK(metric_type IN (
    'TURNOVER','STOCKOUT_RATE','OVERSTOCK_RATE','STAGNANT_SHARE',
    'ACCURACY','NEGATIVE_STOCK')),
  metric_state varchar(16) NOT NULL CHECK(metric_state IN ('AVAILABLE','UNAVAILABLE','NO_DATA')),
  -- value 的取值范围随指标而变（比率 0..1，次数 >=0），用 CHECK 按类型分档钉死。
  value numeric(18,6),
  -- 取值来源：ACCURACY 有两个真实来源 —— 7.1.2.2 核对口径与 7.4.2.1 盘点口径。
  -- 两者不各占一行（UNIQUE(assessment_id,metric_type) 决定了每种指标只有一行），
  -- 而是按服务层写死的优先级合成一个值，并用 metric_source 记下用了哪个。
  -- 分母/分子必须跟着值一起存，否则事后无法判断这个分数是怎么算出来的。
  metric_source varchar(24)
    CHECK(metric_source IS NULL OR metric_source IN ('RECONCILIATION','STOCKTAKE','INVENTORY_BALANCE')),
  numerator numeric(19,4),
  denominator numeric(19,4),
  -- 不可用原因 / 无样本原因。AVAILABLE 时为空串。
  reason varchar(500) NOT NULL DEFAULT '',
  -- 阻塞该指标的模块号（如 '13'、'7.6.1'）。等它交付后本行自然转成 AVAILABLE，
  -- 不需要改表结构。
  blocked_by_module varchar(16),
  -- 扣分：这条指标从满分里扣了多少。UNAVAILABLE / NO_DATA 恒为 0 ——
  -- 扣了分就等于把"没测出来"说成"测出来不合格"，那是两回事。
  deduction numeric(5,2) NOT NULL DEFAULT 0 CHECK(deduction>=0 AND deduction<=100),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,assessment_id,metric_type),
  FOREIGN KEY(tenant_id,assessment_id) REFERENCES inventory_health_assessment(tenant_id,id),
  -- 值与状态必须配套：AVAILABLE 必须有值，另两种状态必须没有值。
  -- 少了这条就会出现"标记为不可用却带着一个数"，读取方无从判断该信哪个。
  CHECK((metric_state='AVAILABLE')=(value IS NOT NULL)),
  -- 来源与状态同理：算得出来的值必须说得出是从哪张表算的，否则"准确率 0.99"无法复核。
  CHECK((metric_state='AVAILABLE')=(metric_source IS NOT NULL)),
  CHECK((metric_state='AVAILABLE') OR (numerator IS NULL AND denominator IS NULL)),
  CHECK((metric_state='UNAVAILABLE')=(blocked_by_module IS NOT NULL)),
  -- 比率类指标的范围（0..1）；次数类只要求非负。NEGATIVE_STOCK 的值恒为 0，
  -- 由 V1 的 CHECK(on_hand>=0) / V2 的 CHECK(on_hand>=0) 在结构上保证，
  -- 服务层与集成测试都按"可证明的 0"如实返回，而不是省略这一行。
  CHECK(metric_type='NEGATIVE_STOCK'
        OR (value IS NULL OR (value>=0 AND value<=1)))
);

-- 回查某次评估的六项指标（端点与问题清单都走这条索引）。
CREATE INDEX inventory_health_metric_assessment
  ON inventory_health_metric(tenant_id, assessment_id, metric_type);
-- 按指标状态筛：工作台要问"哪些指标至今算不出来"。
CREATE INDEX inventory_health_metric_state
  ON inventory_health_metric(tenant_id, metric_type, metric_state, assessment_id);

-- ---------------------------------------------------------------------------
-- 3. 结论（准确率红线判定）
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_health_verdict (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  assessment_id uuid NOT NULL,

  scope_type varchar(16) NOT NULL CHECK(scope_type IN ('TENANT','WAREHOUSE')),
  scope_key uuid,
  scope_label varchar(150) NOT NULL DEFAULT '',
  CHECK((scope_type='TENANT')=(scope_key IS NULL)),

  score numeric(5,2) NOT NULL CHECK(score>=0 AND score<=100),
  grade varchar(16) NOT NULL CHECK(grade IN ('HEALTHY','WATCH','RISK','UNVERIFIABLE')),

  accuracy_rate numeric(8,6) CHECK(accuracy_rate IS NULL OR (accuracy_rate>=0 AND accuracy_rate<=1)),
  accuracy_state varchar(16) NOT NULL CHECK(accuracy_state IN ('AVAILABLE','UNAVAILABLE','NO_DATA')),
  -- 蓝图红线：低于阈值应阻断结算与对账。本列是**判定结果**，不是"已阻断"——
  -- 阻断由下游（12 财务结算、7.1.2.2 对账）各自在自己的边界内执行，
  -- 本服务既不知道它们的单据状态，也不应该替它们扣单。
  blocked boolean NOT NULL DEFAULT false,
  block_reason varchar(500) NOT NULL DEFAULT '',

  -- 问题清单（数据驱动，每条都能追到具体证据行）。
  issue_count integer NOT NULL DEFAULT 0 CHECK(issue_count>=0),
  critical_issue_count integer NOT NULL DEFAULT 0 CHECK(critical_issue_count>=0),
  -- 可读结论：人话版摘要，供工作台直接显示。
  summary varchar(1000) NOT NULL DEFAULT '',

  evaluated_at timestamptz NOT NULL DEFAULT now(),
  evaluated_by uuid NOT NULL,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,assessment_id),
  FOREIGN KEY(tenant_id,assessment_id) REFERENCES inventory_health_assessment(tenant_id,id),
  CHECK((accuracy_state='AVAILABLE')=(accuracy_rate IS NOT NULL)),
  -- 阻断必须能说出理由：blocked=true 而 block_reason 为空等于没阻断。
  CHECK((NOT blocked) OR btrim(block_reason)<>'')
);

-- 问得最多的是"现在能结算吗"：按范围取最近一次判定。
CREATE INDEX inventory_health_verdict_recent
  ON inventory_health_verdict(tenant_id, scope_type, scope_key, evaluated_at DESC, id);
-- 被阻断的范围清单（结算前置校验要用）。
CREATE INDEX inventory_health_verdict_blocked
  ON inventory_health_verdict(tenant_id, blocked, evaluated_at DESC, id) WHERE blocked;

-- ===========================================================================
-- 4. 多租户与授权（与 V1/V2/V54 同一套写法）
-- ===========================================================================
-- 这三张表汇总的是**全租户**的余额、核对与盘点结果，漏掉 tenant_id 或漏掉 RLS
-- 就是把 A 租户的账目健康度泄露给 B 租户，因此同样 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它（集成测试实跑三断言证明）。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['inventory_health_assessment','inventory_health_metric','inventory_health_verdict'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE
  ON inventory_health_assessment, inventory_health_metric, inventory_health_verdict TO warehouse_app;
-- 评估结论同样是审计凭据：写下就不许改，不许删。要作废就重评一次，新旧并存 ——
-- 这正是"可追溯"的含义（与 V54 核对结论同一条纪律）。
CREATE TRIGGER inventory_health_assessment_append_only
  BEFORE UPDATE OR DELETE ON inventory_health_assessment
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();
CREATE TRIGGER inventory_health_metric_append_only
  BEFORE UPDATE OR DELETE ON inventory_health_metric
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();
CREATE TRIGGER inventory_health_verdict_append_only
  BEFORE UPDATE OR DELETE ON inventory_health_verdict
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();
REVOKE UPDATE,DELETE
  ON inventory_health_assessment, inventory_health_metric, inventory_health_verdict FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54 同口径）。
REVOKE ALL
  ON inventory_health_assessment, inventory_health_metric, inventory_health_verdict FROM starcon_app;