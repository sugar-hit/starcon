-- ===========================================================================
-- 4.3.1.2 批量发布（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.3.1.2 批量发布
--   - 职责：批量刊登队列：按规则筛选待发布商品（如"SPU 状态=在售 且 未在任何店铺发布"），
--           生成任务批次，支持暂停、调整顺序、批量重试。
--   - 输入：筛选规则与数量上限；发布策略；用户确认。
--   - 输出：批次任务列表 + 总体进度 + 失败明细（可跳转 4.3.1.3）。
--   - 关系：上游 = 4.1.1.1、4.2.1.2；下游 = 4.3.1.3。
--           **批量任务必须限流**：按平台各自的 API 配额排队，否则会触发平台限流
--           并可能影响店铺安全（实体目录 §6 平台限流要求）。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本迁移最重要的一件事：限流参数**落在数据里**，而且缺配置就**不派发**
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗是整个模块的**核心不变量**：「批量任务必须限流：按平台各自的 API 配额
-- 排队，否则会触发平台限流并可能影响店铺安全」。它的物理落法是**一张按平台（可细到
-- 店铺+平台）配的限流表 `publish_rate_limit`**，加上服务层**按任务各自的限流行分组、
-- 逐组算配额**的派发算法 —— 而不是服务层写死一个全局并发上限。
--
-- 为什么"缺配置就不派发"（fail-closed）：
--   限流参数是**每个平台各自的 API 配额**，是运营/平台对接侧的真实数据（实体目录 §6）。
--   如果没配就回退到服务层某个写死的"默认并发"，那正是加粗句要防的事 —— 一个写死的
--   全局默认值在多平台场景下就是对所有平台施加同一份臆测配额，可能触发平台限流并影响
--   店铺安全。所以：某平台没有可用限流行 ⇒ 该平台本轮**一条都不派发**，并在派发摘要里
--   写明「该平台未配置限流参数」。限流参数**可配置（端点）、可查（端点）**。
--
-- 限流口径（两条同时生效，取更紧的那个）：
--   * max_in_flight       —— 该限流行当前允许的在途（DISPATCHED）并发上限
--   * requests_per_window / window_seconds —— 滑动窗口内的派发次数上限（API 配额）
--
-- ---------------------------------------------------------------------------
-- 二、三张新表，纯新建、零 ALTER 任何既有表
-- ---------------------------------------------------------------------------
-- 与 V177（4.2.1.2 批量编辑）同一手法：只被 V1 `platform` / V1 `shop` 与 V171 `listing`
-- / V177 `listing_content_draft` **只读**引用，跨车道 DDL 回归面 = 0。
--
-- ---------------------------------------------------------------------------
-- 三、任务状态机（枚举 + CHECK）
-- ---------------------------------------------------------------------------
--   PENDING    已入队，未派发（受顺序与限流约束）
--   DISPATCHED 已派发、在途（占用该平台限流行的 max_in_flight）
--   SUCCEEDED  平台侧已接受（草稿转 PUBLISHED）
--   FAILED     发布失败，记失败明细（可批量重试）
--
-- 🔴 SUCCEEDED / FAILED 是**终态**：批量重试只把 FAILED 打回 PENDING，
--    **绝不把 SUCCEEDED 打回 PENDING**（重跑已成功的 = 重复发布 / 重复建 listing，
--    正是本模块最危险的假实现）。下面的触发器从 DB 层钉死这条。
--
-- ---------------------------------------------------------------------------
-- 四、多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT（照抄 V177）
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- publish_rate_limit · 🔴 按平台（可细到店铺+平台）的 API 配额
-- ---------------------------------------------------------------------------
CREATE TABLE publish_rate_limit (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 全局只读码表（channel V1），不跨服务
  platform_id   uuid NOT NULL REFERENCES platform(id),
  -- NULL = 该平台的**平台级**默认配额；给了 shop_id = 该店铺单独的更严/更松配额
  shop_id       uuid,
  -- 🔴 在途并发上限：该限流行同一时刻允许多少条 DISPATCHED
  max_in_flight integer NOT NULL DEFAULT 1 CHECK(max_in_flight BETWEEN 1 AND 500),
  -- 🔴 滑动窗口派发上限：window_seconds 秒内最多派发 requests_per_window 条
  requests_per_window integer NOT NULL DEFAULT 60 CHECK(requests_per_window BETWEEN 1 AND 10000),
  window_seconds      integer NOT NULL DEFAULT 60 CHECK(window_seconds BETWEEN 1 AND 86400),
  enabled       boolean NOT NULL DEFAULT true,
  version       bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 配了店铺级配额时，该店铺必须真实存在（复合 FK 带 tenant_id ⇒ 跨租户挂载不可能）
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id)
);
COMMENT ON TABLE publish_rate_limit IS
  '4.3.1.2 · 🔴 批量发布的按平台 API 配额（可配置、可查）。缺配置的平台上**不派发**（fail-closed），'
  '绝不用服务层写死的全局默认值兜底 —— 那正是「触发平台限流并可能影响店铺安全」的来源。';

-- NULL 在 PG 的 UNIQUE 里彼此不同，所以"平台级默认"用部分唯一索引单独钉住
CREATE UNIQUE INDEX publish_rate_limit_platform_default
  ON publish_rate_limit(tenant_id, platform_id) WHERE shop_id IS NULL;
CREATE UNIQUE INDEX publish_rate_limit_per_shop
  ON publish_rate_limit(tenant_id, platform_id, shop_id) WHERE shop_id IS NOT NULL;
CREATE INDEX publish_rate_limit_lookup ON publish_rate_limit(tenant_id, platform_id) ;

-- ---------------------------------------------------------------------------
-- publish_batch · 一个批次（「生成任务批次」+ 总体进度）
-- ---------------------------------------------------------------------------
-- filter_rule / strategy 是**提交那一刻的口径快照**：批量跑到一半运营改了筛选或策略，
-- 这一批已入队的任务仍必须按提交时的口径解释，否则"当时为什么选的是这些"答不上来。
CREATE TABLE publish_batch (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- RUNNING 可派发 / PAUSED 停止派发新任务（在途不取消）/ COMPLETED 全部终态 / CANCELLED 人工中止
  state         text NOT NULL DEFAULT 'RUNNING'
                CHECK(state IN ('RUNNING','PAUSED','COMPLETED','CANCELLED')),
  -- 筛选规则快照（照抄 4.1.1.1 的筛选参数形状 + 本模块的 hasPendingDraft）
  filter_rule   jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 发布策略快照（如 {"mode":"DRAFT_CONTENT"} 等；原文存，不解释）
  strategy      jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 目标集合快照：冻结提交那一刻选中的 listing（"当时选的是哪些"可回溯）。
  --    它同时是**数据范围策略能成立**的前提 —— 见下面 publish_batch 的 data_scope：
  --    批次行先于任务行写入，若范围判定依赖"已存在的任务"，插入批次那一步就永远过不了。
  --    与 V177 listing_bulk_operation.listing_ids 同一手法。
  listing_ids   uuid[] NOT NULL,
  -- 数量上限（提交时实际生效的上限）
  limit_count   integer NOT NULL CHECK(limit_count BETWEEN 1 AND 1000),
  total_count     integer NOT NULL DEFAULT 0 CHECK(total_count>=0),
  pending_count   integer NOT NULL DEFAULT 0 CHECK(pending_count>=0),
  dispatched_count integer NOT NULL DEFAULT 0 CHECK(dispatched_count>=0),
  succeeded_count integer NOT NULL DEFAULT 0 CHECK(succeeded_count>=0),
  failed_count    integer NOT NULL DEFAULT 0 CHECK(failed_count>=0),
  actor_id      uuid,
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  completed_at  timestamptz,
  UNIQUE(tenant_id, id),
  -- 计数自洽：各状态计数之和 = 总数；且 COMPLETED 必须有时间戳
  CHECK(pending_count+dispatched_count+succeeded_count+failed_count = total_count),
  CHECK(state<>'COMPLETED' OR completed_at IS NOT NULL)
);
COMMENT ON TABLE publish_batch IS
  '4.3.1.2 · 一次批量发布批次：筛选口径快照 + 总体进度计数（CHECK 保证各状态之和 = 总数）。';

-- ---------------------------------------------------------------------------
-- publish_task · 队列里的一条发布任务（「批次任务列表」）
-- ---------------------------------------------------------------------------
CREATE TABLE publish_task (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  batch_id      uuid NOT NULL,
  listing_id    uuid NOT NULL,
  -- 本次要发布的那份内容草稿（4.2.1.2 的产出）；成功时该草稿转 PUBLISHED
  draft_id      uuid,
  -- 🔴 每条任务自带平台与店铺：限流按**它自己的**平台/店铺分行计算，
  --    这就是"按平台各自配额"在数据上的落点（不是批次级一个全局上限）。
  platform_id   uuid NOT NULL REFERENCES platform(id),
  shop_id       uuid NOT NULL,
  -- 🔴 执行顺序（可重排）：派发按 position 升序取，所以重排真的改变执行次序
  position      integer NOT NULL CHECK(position>=0),
  state         text NOT NULL DEFAULT 'PENDING'
                CHECK(state IN ('PENDING','DISPATCHED','SUCCEEDED','FAILED')),
  attempts      integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
  dispatched_at timestamptz,
  completed_at  timestamptz,
  -- 失败明细（输出：失败明细；4.3.1.3 的跳转目标侧）
  failure_reason varchar(500),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一批次里同一条 listing 只有一个发布任务（不重复入队）
  UNIQUE(tenant_id, batch_id, listing_id),
  -- 状态与时间戳自洽
  -- 🔴 任一非 PENDING 任务都必然派发过（所以 dispatched_at 非空）；反过来 PENDING 允许有
  --    （重试打回队列时保留上次派发时刻作为历史）。"派发过 → 有派发时刻"由 DB 钉住。
  CHECK(state='PENDING' OR dispatched_at IS NOT NULL),
  CHECK((state IN ('SUCCEEDED','FAILED')) = (completed_at IS NOT NULL)),
  CHECK(state<>'FAILED' OR (failure_reason IS NOT NULL AND btrim(failure_reason)<>'')),
  FOREIGN KEY(tenant_id, batch_id) REFERENCES publish_batch(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, draft_id) REFERENCES listing_content_draft(tenant_id, id)
);
COMMENT ON TABLE publish_task IS
  '4.3.1.2 · 队列里的一条发布任务；platform_id/shop_id 决定它按哪条限流行排队，'
  'position 决定执行顺序（可重排），state 决定它是否可被批量重试（只重试 FAILED）。';

CREATE INDEX publish_batch_recent     ON publish_batch(tenant_id, created_at DESC, id);
CREATE INDEX publish_batch_state      ON publish_batch(tenant_id, state);
CREATE INDEX publish_task_batch       ON publish_task(tenant_id, batch_id, position);
CREATE INDEX publish_task_batch_state ON publish_task(tenant_id, batch_id, state);
-- 🔴 「同一批次里，两个待派发任务不能占同一个顺序号」用**部分唯一索引**表达：
--   整表 UNIQUE(tenant_id,batch_id,position) 会让"先派发掉位置 0，再重排剩下的任务"直接
--   撞号 —— 终态任务冻结了它当年的 position，而重排只改 PENDING 那些，两边必然重叠。
--   部分索引把约束收在真正需要它的那部分行上（只有待派发的任务参与排序）。
CREATE UNIQUE INDEX publish_task_pending_pos ON publish_task(tenant_id, batch_id, position)
  WHERE state='PENDING';
-- 🔴 派发算配额时按 (tenant, platform, state, dispatched_at) 统计在途与窗口内次数
CREATE INDEX publish_task_dispatch    ON publish_task(tenant_id, platform_id, state, dispatched_at DESC);
CREATE INDEX publish_task_listing     ON publish_task(tenant_id, listing_id);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
-- (a) 🔴🔴 核心不变量：SUCCEEDED 是终态，重试绝不能把已成功的打回 PENDING。
--     「批量重试只重试失败项，不要重跑已成功的」在 DB 层由本触发器钉死：
--     将来谁在服务层手滑写了 update publish_task set state='PENDING' where state='SUCCEEDED'
--     会在测试期就被拒（23514），而不是等到全店重复发布。
CREATE FUNCTION guard_publish_task_terminal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.state='SUCCEEDED' AND NEW.state<>'SUCCEEDED' THEN
    RAISE EXCEPTION
      'Publish task % already SUCCEEDED; retry must only reset FAILED tasks, never re-run a succeeded publish',
      OLD.id USING ERRCODE='23514';
  END IF;
  -- 在途（DISPATCHED）不能被直接打回 PENDING（那等于悄悄取消一次已发出的平台请求）；
  -- 必须先被回报成功/失败。暂停批次**不**通过改任务状态实现（暂停只停派发）。
  IF OLD.state='DISPATCHED' AND NEW.state='PENDING' THEN
    RAISE EXCEPTION
      'Publish task % is in flight (DISPATCHED); complete or fail it before it can be requeued',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER publish_task_terminal BEFORE UPDATE ON publish_task
  FOR EACH ROW EXECUTE FUNCTION guard_publish_task_terminal();

-- (b) 派发时 attempts 必须 +1（在途计数是限流的一部分，"派了几次"要可查）
CREATE FUNCTION guard_publish_task_attempts() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.state='DISPATCHED' AND OLD.state<>'DISPATCHED' AND NEW.attempts <> OLD.attempts + 1 THEN
    RAISE EXCEPTION
      'Publish task % must increment attempts by exactly 1 when dispatched (was %, now %)',
      OLD.id, OLD.attempts, NEW.attempts USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER publish_task_attempts BEFORE UPDATE ON publish_task
  FOR EACH ROW EXECUTE FUNCTION guard_publish_task_attempts();

-- (c) 批次进度计数由服务层重算后写入；DB 侧保证 COMPLETED ⟺ 全部终态（无 PENDING/DISPATCHED）
CREATE FUNCTION guard_publish_batch_progress() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.state='COMPLETED' AND (NEW.pending_count<>0 OR NEW.dispatched_count<>0) THEN
    RAISE EXCEPTION
      'Publish batch % cannot be COMPLETED while % pending / % dispatched tasks remain',
      NEW.id, NEW.pending_count, NEW.dispatched_count USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER publish_batch_progress BEFORE UPDATE ON publish_batch
  FOR EACH ROW EXECUTE FUNCTION guard_publish_batch_progress();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V171 / V177 同一套写法。租户由 backend/libraries/tenancy 在事务内
-- 设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'publish_rate_limit','publish_batch','publish_task' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围（RESTRICTIVE，与 V171 / V177 同构：可见性随店铺负责人 / 部门收敛）
-- ---------------------------------------------------------------------------
-- 🔴 批量发布尤其需要这条：批次里每一条任务的目标店铺都必须落在调用者的店铺范围内，
-- 否则"用户建了一批、其中 30 个店铺不属于他"会被服务层当正常批量派发出去。
-- 范围判定读批次自带的 listing_ids 快照（**不是**任务行）：批次行先于任务行写入，
-- 用"已存在的任务"做判定会让 insert 批次那一步永远过不了 RLS —— 与 V177
-- listing_bulk_operation 的 data_scope 同一手法。
-- publish_rate_limit 是平台/店铺级**配置**，平台级（shop_id IS NULL）视为租户共享配置，
-- 店铺级则随店铺收敛。
CREATE POLICY data_scope ON publish_batch AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=publish_batch.tenant_id
             AND l.id = ANY(publish_batch.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=publish_batch.tenant_id
             AND l.id = ANY(publish_batch.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON publish_task AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=publish_task.tenant_id
             AND l.id=publish_task.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=publish_task.tenant_id
             AND l.id=publish_task.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON publish_rate_limit AS RESTRICTIVE
  USING (shop_id IS NULL
         OR EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=publish_rate_limit.tenant_id
                   AND s.id=publish_rate_limit.shop_id
                   AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (shop_id IS NULL
         OR EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=publish_rate_limit.tenant_id
                   AND s.id=publish_rate_limit.shop_id
                   AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1..V177 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  publish_rate_limit, publish_batch, publish_task TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V177 同口径）。
REVOKE ALL ON publish_rate_limit, publish_batch, publish_task FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：批量发布属于「平台操作」语义，与 4.1.1.1 listing 列表、
-- 4.2.1.2 批量编辑、10.1.1.2 渠道配置同族，identity 服务零改动。
