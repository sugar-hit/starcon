-- ===========================================================================
-- 4.3.1.3 发布任务（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.3.1.3 发布任务
--   - 职责：单次发布任务的进度视图：各步骤状态（内容校验 → 平台创建 → 变体创建 → 媒体上传 →
--           价格库存设置 → 提交审核）、失败步骤与原因、可重试的步骤。
--   - 输入：任务 ID；平台返回结果。
--   - 输出：步骤时间线 + 错误明细 + 重试操作。
--   - 关系：上游 = 4.3.1.1/4.3.1.2；下游 = 2.5.1.1。
--           **平台提交审核是异步的**，任务在"审核中"结束，实际结果由 4.3.2.2 回流。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 本迁移最重要的一件事：本模块**是进度视图，不是另一个发布系统**
-- ---------------------------------------------------------------------------
-- 4.3.1.1 一键刊登（V196）已经把发布作业的表建好了：
--   * listing_publish_job        —— 作业（一次一键刊登）
--   * listing_publish_job_target —— 🔴 **每个目标一行 = 一个发布任务**（4.3.1.1 输出句原话：
--                                    「N 个发布任务（每个目标一个）」）
--   * listing_publish_job_step   —— 步骤时间线（目标级 + 作业级）
-- 4.3.1.2 批量发布（V183）建了 publish_batch / publish_task（队列里的一条发布任务）。
--
-- 本模块把这两条上游的「发布任务」聚成**一个进度视图**：读它们的行、算它们的进度、
-- 给出错误明细、发起重试。**本迁移一张发布作业/任务表都不建** ——
-- 另建一张「发布任务」表会让「这个发布任务现在什么状态」有两个答案，
-- 而 4.3.1.1 与 4.3.1.2 读的是 V196 / V183 那两张 ⇒ 分叉。
--
-- 🔴 命名空间：4.3.1.2 批量发布已经占了 `publish_batch` / `publish_task`，
--   4.3.1.1 占用了 `listing_publish_job*`。本模块**只新增一张**
--   `listing_publish_step_attempt`（前缀 `listing_publish_`），四张表一眼分得开。
--   刻意**不用** `publish_task` / `listing_task` / `publish_job` 这类裸名 ——
--   那些名字在 4.3.1.2 / 4.3.1.1 那里已经有主，语义重复的表是最难查的 bug。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 为什么需要这张新表（表预算用在了刀刃上）
-- ---------------------------------------------------------------------------
-- 蓝图输出句里有三项：「步骤时间线 + 错误明细 + **重试操作**」。
--   * 步骤时间线 → 读 listing_publish_job_step（4.3.1.1 的表，本模块只读）
--   * 错误明细   → 读步骤的 detail + 任务行的 failure_reason（本模块只读）
--   * 🔴 重试操作 → 需要**留痕**：每一次重试的尝试记录（这是本模块唯一需要写的东西）
--
-- 「重试操作」不写进既有表的原因（模块边界纪律：每张事实表只有一个写入所有者）：
--   * listing_publish_job_target / listing_publish_job_step 的**写入所有者是 4.3.1.1**，
--     它的状态机由 DB 触发器 guard_publish_target_state / guard_publish_step_state 钉死。
--     本模块**不写**这两张表 —— 重试动作**委托**给 4.3.1.1 的既有重试路径执行，
--     由它（唯一的写入所有者）去推进状态。本模块只**记录自己发起的尝试**。
--   * platform_operation（2.5.1.1 的回写任务）也由 4.3.1.1 写 ——
--     蓝图那句「下游 = 2.5.1.1」指的是**任务**流向 2.5.1.1，不是本模块直接建回写任务。
--
-- ⇒ listing_publish_step_attempt = 「4.3.1.3 发起的每一次重试尝试」的事实表。
--   没有它，「重试操作」就是一次没留痕的点击：失败了没人知道失败在哪一次、
--   成功了几次、每次的失败原因是什么。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 零 ALTER 任何既有表
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 一张新表**，对 listing_publish_job / listing_publish_job_target /
-- listing_publish_job_step / publish_task / publish_batch / platform_operation
-- 一律**零 ALTER、零索引、零约束、零触发器** ⇒ 跨车道 DDL 回归面 = 0。
--
-- 查询走既有索引即可覆盖：
--   * listing_publish_job_target_recent / _state / _shop（V196）
--   * listing_publish_job_step_job / _target（V196）
--   * publish_task_batch / _batch_state、publish_batch_recent（V183）
-- 既有索引的租户前缀已覆盖本模块的查询形状（tenant_id 恒为等值条件）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 蓝图加粗句「平台提交审核是异步的，任务在『审核中』结束」
-- ---------------------------------------------------------------------------
-- 这句话的物理落法是**本表没有「平台已发布」这种状态**：
--   * 任务行的状态由 4.3.1.1 的 listing_publish_job_target.state 决定，
--     其中的 SUBMITTED = 已提交平台、等回执（= 审核中）。
--   * 本表只记**尝试**（谁在什么时候重试了哪一步、成功没成功），
--     **不记**「发布成功」—— 成功与否由平台回执决定，由 4.3.2.2 回流。
--   * attempt 的 outcome 只有两值：SUCCEEDED（重试动作本身被接受并已重新提交平台）
--     / FAILED（重试动作被拒或执行失败，带原因）。
--     🔴 **没有**「发布成功」这个取值 —— 提交成功 ≠ 平台审核通过，
--     把两者混成一个布尔值就是蓝图那句话明写要防的事。
--
-- ---------------------------------------------------------------------------
-- 四、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 本表**不含金额与数量列** —— 金额与可售量的快照在 4.3.1.1 的作业行上
-- （price_amount numeric(19,4) / stock_quantity numeric(19,6)），本模块只读不写。
-- 本表只有时间列（timestamptz）与计数（integer）。
--
-- ---------------------------------------------------------------------------
-- 五、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
--
-- ===========================================================================
-- 一、listing_publish_step_attempt · 4.3.1.3 的重试尝试留痕
-- ===========================================================================
CREATE TABLE listing_publish_step_attempt (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 被重试的那条**发布任务**（4.3.1.1 的目标行 = 一个发布任务）
  task_id       uuid NOT NULL,
  -- 所属一键刊登作业（4.3.1.1 的 listing_publish_job.id）
  job_id        uuid NOT NULL,
  -- 🔴 被重试的步骤键（取值域与 listing_publish_job_step.step_key 完全一致）。
  --    为空 = **任务级**重试（整条任务重来，不是某一步）。
  step_key      text
                CHECK(step_key IS NULL OR step_key IN ('TEMPLATE','CONTENT','MAPPING','MEDIA','PRICE','STOCK','SUBMIT','RESULT')),
  -- 🔴 重试的粒度：TASK 整条任务 / TARGET 目标级步骤 / JOB 作业级步骤（PRICE / STOCK）
  scope         text NOT NULL CHECK(scope IN ('TASK','TARGET','JOB')),
  -- 🔴 该任务上的第几次重试尝试（**跨步骤递增**：一次重试动的是「整条任务的第 N 次尝试」，
  --    步骤键另记在 step_key 上 —— 分开记才不会把「第 2 次重试 MEDIA」和「第 2 次重试 SUBMIT」
  --    混成同一个序号）
  attempt_no    integer NOT NULL CHECK(attempt_no>=1),
  -- 触发方式：STEP_RETRY = 在发布任务页点了「重试这一步」/ TASK_RETRY = 点了「重试整条任务」
  trigger_kind  text NOT NULL DEFAULT 'STEP_RETRY'
                CHECK(trigger_kind IN ('STEP_RETRY','TASK_RETRY')),
  -- 🔴 尝试结果：SUCCEEDED = 重试动作被接受并已重新提交平台（≠ 平台审核通过）
  --              FAILED    = 重试动作被拒或执行失败（reason 必填）
  outcome       text NOT NULL CHECK(outcome IN ('SUCCEEDED','FAILED')),
  -- 失败原因 / 说明（人能读；FAILED 时必填）
  reason        text NOT NULL DEFAULT '',
  -- 下游 2.5.1.1 的回写任务（重试成功后由 4.3.1.1 生成，本模块只记它的 id）
  platform_operation_id uuid,
  requested_at  timestamptz NOT NULL,
  finished_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一条任务上的尝试序号唯一 ⇒ 并发重试不会写出两条「第 1 次」
  --    （这里用**任务级**序号而不是「任务+步骤」级：任务级序号在唯一索引里不含 NULL 列，
--      不会出现「NULL 与 NULL 不相等」导致唯一约束形同虚设的坑）
  UNIQUE(tenant_id, task_id, attempt_no),
  FOREIGN KEY(tenant_id, task_id) REFERENCES listing_publish_job_target(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, job_id) REFERENCES listing_publish_job(tenant_id, id) ON DELETE CASCADE,
  -- 🔴 失败必须写清原因（前端原样展示「为什么重试没成」）
  CHECK(outcome<>'FAILED' OR btrim(reason)<>''),
  -- 🔴 粒度与步骤键必须自洽：任务级重试没有步骤键，步骤级重试必须有
  CHECK((scope='TASK') = (step_key IS NULL)),
  -- 🔴 作业级步骤只有 PRICE / STOCK（与 listing_publish_job_step 的归属纪律一致）
  CHECK(scope<>'JOB' OR step_key IN ('PRICE','STOCK')),
  -- 🔴 尝试有始有终：发起时刻必填；出结果时必须有完成时刻
  CHECK(finished_at IS NULL OR finished_at >= requested_at)
);
COMMENT ON TABLE listing_publish_step_attempt IS
  '4.3.1.3 · 🔴 发布任务进度视图的**重试尝试留痕**。'
  '步骤时间线与错误明细读 4.3.1.1 的 listing_publish_job_step（只读），'
  '本表只记本模块发起的每一次重试。outcome=SUCCEEDED 只表示「已重新提交平台」，'
  '**不代表平台审核通过** —— 提交审核是异步的，结果由 4.3.2.2 回流。';

CREATE INDEX listing_publish_step_attempt_task
  ON listing_publish_step_attempt(tenant_id, task_id, attempt_no DESC);
CREATE INDEX listing_publish_step_attempt_step
  ON listing_publish_step_attempt(tenant_id, task_id, step_key, attempt_no DESC);
CREATE INDEX listing_publish_step_attempt_job
  ON listing_publish_step_attempt(tenant_id, job_id, created_at DESC);
CREATE INDEX listing_publish_step_attempt_recent
  ON listing_publish_step_attempt(tenant_id, created_at DESC, id);

-- ===========================================================================
-- 二、🔴 多租户隔离：租户业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V171 / V196 / V199 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant，set_config(..., true) 是事务局部量，
-- 连接归还池子后不带标记），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_publish_step_attempt'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V196 的 listing_publish_job_target 同构：
-- 可见性随**发布任务所属店铺**收敛）—— 运营不能通过重试留痕看到无权店铺的任务。
CREATE POLICY data_scope ON listing_publish_step_attempt AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_publish_job_target t
             WHERE t.tenant_id=listing_publish_step_attempt.tenant_id
               AND t.id=listing_publish_step_attempt.task_id
               AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=t.tenant_id AND s.id=t.shop_id
                         AND channel_scope(channel_operation(),s.owner_id,s.department_id))))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_publish_job_target t
             WHERE t.tenant_id=listing_publish_step_attempt.tenant_id
               AND t.id=listing_publish_step_attempt.task_id
               AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=t.tenant_id AND s.id=t.shop_id
                         AND channel_scope(channel_operation(),s.owner_id,s.department_id))));

-- ===========================================================================
-- 三、账号与授权（沿用 V1..V199 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_publish_step_attempt TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V199 同口径）。
REVOKE ALL ON listing_publish_step_attempt FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 发布任务进度视图属于「平台操作」语义，
-- 与 4.1.1.1 listing 列表、4.3.1.1 一键刊登、4.3.1.2 批量发布、4.3.2.2 平台状态回流同族，
-- 复用 identity 已注册的 channel.operation.*，identity 服务零改动。
-- ---------------------------------------------------------------------------
