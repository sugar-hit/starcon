-- ===========================================================================
-- 4.3.2.1 内容回写（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.3.2.1 内容回写
--   - 职责：把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、改 A+），
--           支持按变更类型触发或批量触发，并记录回写前后快照。
--   - 输入：内容变更事件；用户批量回写操作；目标 listing 集合。
--   - 输出：回写任务（经 02 队列）+ 变更历史。
--   - 关系：上游 = 3 商品主数据变更、4.2.1.2 批量编辑；下游 = 2.5.1.1。
--           **内容回写不触碰价格与库存**（那是 05/07 的事），
--           三条回写通道独立失败独立重试。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 本迁移最重要的一件事：内容通道在 DB 层就**装不下**价格与库存
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗是整个模块的**核心不变量**。它的物理落法不是「服务层记得别写」，
-- 而是**列与 CHECK 的取值域本身就没有价格与库存**：
--
--   * `listing_content_writeback_task.change_types` 与
--     `listing_content_writeback_change.field` 的 CHECK 只有五个值：
--       TITLE / BULLETS / IMAGES / DESCRIPTION / A_PLUS
--     想往本通道塞 price、stock、quantity 在**语法层面**就写不出来。
--   * 本迁移**零 ALTER 任何既有表**，也**不建任何金额列**：
--     V171 的 listing 没有价格列（售价归 05 定价）、没有内部可售量（归 07 库存），
--     照抄 V171 第二节与 4.2.1.2 第五节的同一纪律 ——
--     建一个影子价格列 = 假装有第二个价格事实来源，05 上线后两个数字迟早对不上，
--     而没人说得清该信哪个，这比「没有价格」危险得多。
--   * 「三条通道独立失败独立重试」在本模块的具体含义因此是：
--     本模块的重试入口**只**打回自己的 FAILED 任务（由下面 (a) 的触发器钉死），
--     它**碰不到** 02 队列里别的通道的行 —— 02 队列的 operation 取值域
--     （V2 的 CHECK：PUBLISH / UPDATE_PRICE / UPDATE_STOCK / SHIP_UPDATE /
--     CANCEL_ORDER）是 2.5.1.1 的对外契约，本迁移一个字都不改。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 依赖面：只引用同服务已交付的表，零跨车道 DDL 回归
-- ---------------------------------------------------------------------------
--   * V171 `listing`（线上态：标题与平台类目；owner_id / department_id 是数据范围的锚）
--   * V175 `listing_content_snapshot`（平台侧当前内容 = **改前**的真实来源；
--     按 listing + language + is_current 定位）
--   * V175 `listing_publish_record`（发布动作留痕；它的 CHECK 里
--     action='CONTENT_WRITEBACK' **就是为本模块预留的**，成功回执时写它）
--   * V177 `listing_content_draft`（内部待发布内容 = **改后**的真实来源；
--     touched_fields 精确说明"内部这次改了哪些字段"）
--   * V2   `platform_operation`（02 队列 = 「输出：回写任务（经 02 队列）」的落点；
--     **只 INSERT，不改它的任何列 / CHECK / 索引**）
--   * V183 `publish_rate_limit`（按平台 API 配额；内容回写与批量发布是**两条通道**，
--     各走各的限流行，所以两张表的在途与窗口计数天然互不干扰 —— 这也是
--     「三条通道独立失败独立重试」在限流维度上的落点）
--
-- 上游 = 3 商品主数据变更**跨服务**：AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，
-- 所以本模块**读不到** catalog 的 SPU 变更事件。它在 `/channels` 端点里被显式声明为
-- available=false + ownerModule + impact，**不假装能自动感知商品主数据变更**。
--
-- ---------------------------------------------------------------------------
-- 三、三张新表，纯新建
-- ---------------------------------------------------------------------------
--   listing_content_writeback        一次触发（「按变更类型触发」或「批量触发」）
--   listing_content_writeback_task   每个目标 listing 一条（带 02 队列的 operation_id）
--   listing_content_writeback_change 逐字段「改前 vs 改后」（= 变更历史的核心）
--
-- 与 V177 的 `listing_bulk_change` 同一手法：变更行在**建任务时**就落库，
-- 于是「界面上看到的改前改后」与「真正发出去的内容」是同一批数据，
-- 「预览一套、执行另一套」在结构上不可能 —— 而本模块**没有预览步骤**
-- （回写不是编辑，没有"确认前先看看"这个状态），所以改前改后在提交那一刻就定死。
--
-- ---------------------------------------------------------------------------
-- 四、状态机
-- ---------------------------------------------------------------------------
--   run.state     RUNNING 可派发 / COMPLETED 全部终态 / CANCELLED 人工中止
--   task.state    PENDING  已入队未派发
--                 SKIPPED_NO_CHANGE  内容与线上逐字相同，**不派发**（对平台的无意义写）
--                 DISPATCHED 已进 02 队列、在途
--                 SUCCEEDED / FAILED 终态
--
-- 🔴 SKIPPED_NO_CHANGE 之所以是**独立状态**而不是"成功"：
--   一次成功的回写意味着平台上的内容真的变了。若改后与改前逐字相同却报成功，
--   运营会以为"已经推上去了"，而平台上其实什么都没发生 —— 那是一次**谎报**。
--   这类任务因此从不派发（对平台的写调用是真实的外部副作用，不是免费的）。
--
-- 🔴 SUCCEEDED 是终态，重试绝不重跑（见下面 (a)）。
--
-- ---------------------------------------------------------------------------
-- 五、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 本迁移**无任何金额列**：内容通道不碰价格（归 05 定价），见第一节。
-- 无 float / double 一列。存在的数值列只有计数（integer），不参与任何金额计算。
--
-- ---------------------------------------------------------------------------
-- 六、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒（4.1.1.1 实测踩过）。
-- 集成测试**断言写回的值逐字相等**，不能只断言没抛异常。

-- ===========================================================================
-- 1. listing_content_writeback · 一次触发
-- ===========================================================================
-- 一次触发的口径是**提交那一刻**的快照：目标 listing 集合、变更类型集合、
-- 显式覆盖值、解析出来的语言。批量跑到一半运营改了筛选或又编辑了一次内容，
-- 这一批已入队的任务仍按提交时的口径解释，否则"当时为什么回写这些"答不上来。
CREATE TABLE listing_content_writeback (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  -- SINGLE / BULK_EVENT：按变更类型触发 / 由上游变更事件批量触发
  trigger_kind   text NOT NULL DEFAULT 'BULK_EVENT'
                 CHECK(trigger_kind IN ('SINGLE','BULK_EVENT')),
  -- 🔴 本次请求的变更类型集合，取值域里**没有 price / stock**（见第一节）
  change_types   text[] NOT NULL,
  -- 🔴 提交那一刻的目标集合快照；它同时是本表 data_scope 能成立的前提 ——
  --    本行先于任务行写入，用"已存在的任务"判定范围会让 insert 本行永远过不了 RLS
  --    （与 V177 listing_bulk_operation / V183 publish_batch 同一手法）
  listing_ids    uuid[] NOT NULL,
  -- 🔴 显式覆盖值：{"DESCRIPTION":"…","A_PLUS":[…]}。这是 DESCRIPTION / A+ 的**唯一**
  --    真实来源（见第四节「诚实的上游边界」），原文存，不解释。
  overrides      jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 解析出来的语言：平台侧内容按 listing + language 分别保存（V175）。
  --    不给 language 时由服务层取该 listing 的当前快照语言；连快照都没有时
  --    那条任务判 SKIPPED_NO_SOURCE，**不猜一个默认语言**。
  language       varchar(16) NOT NULL DEFAULT '',
  -- RUNNING 可派发 / COMPLETED 全部终态 / CANCELLED 人工中止
  state          text NOT NULL DEFAULT 'RUNNING'
                 CHECK(state IN ('RUNNING','COMPLETED','CANCELLED')),
  total_count      integer NOT NULL DEFAULT 0 CHECK(total_count>=0),
  pending_count    integer NOT NULL DEFAULT 0 CHECK(pending_count>=0),
  skipped_count    integer NOT NULL DEFAULT 0 CHECK(skipped_count>=0),
  dispatched_count integer NOT NULL DEFAULT 0 CHECK(dispatched_count>=0),
  succeeded_count  integer NOT NULL DEFAULT 0 CHECK(succeeded_count>=0),
  failed_count     integer NOT NULL DEFAULT 0 CHECK(failed_count>=0),
  actor_id       uuid,
  note           varchar(500) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  completed_at   timestamptz,
  UNIQUE(tenant_id, id),
  -- 🔴 变更类型非空：一次"不改任何字段"的回写请求不是回写请求
  CONSTRAINT listing_content_writeback_types_nonempty CHECK(cardinality(change_types) > 0),
  -- 🔴 取值域里没有价格与库存 —— 内容通道在语法层面就装不下它们
  CONSTRAINT listing_content_writeback_types_content_only
    CHECK(change_types <@ ARRAY['TITLE','BULLETS','IMAGES','DESCRIPTION','A_PLUS']::text[]),
  -- 🔴 各状态计数之和 = 总数（与 V183 publish_batch 同一纪律），且 COMPLETED 必须有时间戳
  CONSTRAINT listing_content_writeback_counts_self_consistent
    CHECK(pending_count+skipped_count+dispatched_count+succeeded_count+failed_count = total_count),
  CONSTRAINT listing_content_writeback_completed_has_timestamp
    CHECK(state<>'COMPLETED' OR completed_at IS NOT NULL),
  -- 覆盖值只能是这五个键之一：多一个键就是有人想往内容通道塞别的东西
  CHECK((overrides - ARRAY['DESCRIPTION','A_PLUS','TITLE','BULLETS','IMAGES']::text[]) = '{}'::jsonb)
);
COMMENT ON TABLE listing_content_writeback IS
  '4.3.2.1 · 一次内容回写触发（按变更类型 / 批量）。change_types 的取值域里**没有价格与库存** —— '
  '内容通道与 05 定价 / 07 库存三条通道独立，这是「内容回写不触碰价格与库存」的物理落点。';

-- ===========================================================================
-- 2. listing_content_writeback_task · 一个目标 listing 一条回写任务
-- ===========================================================================
-- 🔴 operation_id → V2 platform_operation：「输出：回写任务（**经 02 队列**）」。
--    有这个外键，「经队列」就不是一句描述而是结构：一条在途/终态的回写任务
--    **不可能**没有对应的 02 队列任务（下面的 CHECK 再钉一道）。
CREATE TABLE listing_content_writeback_task (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  writeback_id  uuid NOT NULL,
  listing_id    uuid NOT NULL,
  -- 🔴 每条任务自带平台与店铺：限流按**它自己的**平台/店铺分行计算（V183 同一手法），
  --    所以内容回写与批量发布各走各的限流行，互不占额度。
  platform_id   uuid NOT NULL REFERENCES platform(id),
  shop_id       uuid NOT NULL,
  language      varchar(16) NOT NULL DEFAULT '',
  -- 本次按什么口径解析出的「改后」；用于变更历史的可回溯性
  draft_id      uuid,
  -- 🔴 改前基线来源：平台侧当前内容快照（V175）。null = 该 listing 没有当前快照
  snapshot_id   uuid,
  change_types  text[] NOT NULL,
  state         text NOT NULL DEFAULT 'PENDING'
                CHECK(state IN ('PENDING','SKIPPED_NO_CHANGE','DISPATCHED','SUCCEEDED','FAILED')),
  attempts      integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
  -- 🔴 02 队列任务（V2 platform_operation）。派发时写入，成功回执后保留。
  operation_id  uuid,
  failure_reason varchar(500),
  dispatched_at timestamptz,
  completed_at  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一次触发里同一条 listing 只有一个任务（不重复入队）
  UNIQUE(tenant_id, writeback_id, listing_id),
  -- 🔴 取值域里没有价格与库存（同上）
  CONSTRAINT listing_content_writeback_task_types_nonempty CHECK(cardinality(change_types) > 0),
  CONSTRAINT listing_content_writeback_task_types_content_only
    CHECK(change_types <@ ARRAY['TITLE','BULLETS','IMAGES','DESCRIPTION','A_PLUS']::text[]),
  -- 🔴🔴 「经 02 队列」由 DB 钉死：**只有真正派发出去过的任务才一定有队列行**，
  --    而「内容与线上逐字相同因而从未派发」的那条一定没有队列行。
  --    少了后半句，一个"绕开队列直接调平台"的实现能写出 DISPATCHED 却查不到队列任务，
  --    而 2.5.1.1 的回写任务列表里那条就凭空消失了。
  --    PENDING 允许**有**队列行：一条失败后被打回队列重试的任务，它的队列行仍然在那儿
  --    —— 幂等键是「一条任务一个」，重试复用同一个 platform_operation 而不是再插一条，
  --    那正是 02 队列 idempotency_key 的用途（V169 第五节：「避免重试造成平台侧重复写入」）。
  --    重新入队 = 复用同一个队列任务，不是新开一个。
  CONSTRAINT listing_content_writeback_task_skipped_has_no_queue
    CHECK(state<>'SKIPPED_NO_CHANGE' OR operation_id IS NULL),
  --    换句话说：**只有真正派发过的任务才一定有队列行，只有从未派发的才一定没有。**
  CONSTRAINT listing_content_writeback_task_dispatched_has_queue
    CHECK(state IN ('PENDING','SKIPPED_NO_CHANGE') OR operation_id IS NOT NULL),
  -- 状态与时间戳自洽
  -- 🔴 SKIPPED_NO_CHANGE 与 PENDING 一样**从未派发**，所以没有 dispatched_at；
  --    只有真的派发出去过的（DISPATCHED / 终态）才必然有派发时刻。
  CONSTRAINT listing_content_writeback_task_dispatch_has_timestamp
    CHECK(state IN ('PENDING','SKIPPED_NO_CHANGE') OR dispatched_at IS NOT NULL),
  CONSTRAINT listing_content_writeback_task_terminal_has_timestamp
    CHECK((state IN ('SUCCEEDED','FAILED')) = (completed_at IS NOT NULL)),
  CONSTRAINT listing_content_writeback_task_failed_has_reason
    CHECK(state<>'FAILED' OR (failure_reason IS NOT NULL AND btrim(failure_reason)<>'')),
  -- SKIPPED_NO_CHANGE 必须写明理由：光一个"跳过"没人知道是内容真的没变还是别的原因
  CONSTRAINT listing_content_writeback_task_skipped_has_reason
    CHECK(state<>'SKIPPED_NO_CHANGE' OR btrim(failure_reason)<>''),
  -- 引用的草稿与快照必须是**本 listing 的**：跨商品引用会让变更历史对不上号
  FOREIGN KEY(tenant_id, writeback_id) REFERENCES listing_content_writeback(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, draft_id) REFERENCES listing_content_draft(tenant_id, id),
  FOREIGN KEY(tenant_id, snapshot_id) REFERENCES listing_content_snapshot(tenant_id, id),
  FOREIGN KEY(tenant_id, operation_id) REFERENCES platform_operation(tenant_id, id)
);
COMMENT ON TABLE listing_content_writeback_task IS
  '4.3.2.1 · 一个目标 listing 的回写任务；operation_id 指向 V2 platform_operation —— '
  '「经 02 队列」是结构事实（有外键 + CHECK），不是描述。';

-- ===========================================================================
-- 3. listing_content_writeback_change · 逐字段「改前 vs 改后」（= 变更历史核心）
-- ===========================================================================
-- 🔴 before_value 来自**平台侧当前快照**（V175），after_value 来自
--    **内部待发布内容**（V177 草稿）或调用方显式给的覆盖值。
--    两侧都是本仓真实持有的事实，不存在"用改后值假装改前值"的写法 ——
--    没有平台侧快照时 before_value 记空串并在 outcome 里写明 SKIPPED_NO_SOURCE。
CREATE TABLE listing_content_writeback_change (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  task_id      uuid NOT NULL,
  listing_id   uuid NOT NULL,
  field        text NOT NULL
               CHECK(field IN ('TITLE','BULLETS','IMAGES','DESCRIPTION','A_PLUS')),
  before_value text NOT NULL,
  after_value  text NOT NULL,
  changed      boolean NOT NULL,
  outcome      text NOT NULL
               CHECK(outcome IN ('APPLIED','NO_CHANGE','SKIPPED_NO_SOURCE')),
  -- 改前值取自哪个快照 / 改后值取自哪份草稿，写进历史才能复盘
  before_source varchar(32) NOT NULL DEFAULT ''
               CHECK(before_source IN ('','PLATFORM_SNAPSHOT','MISSING')),
  after_source  varchar(32) NOT NULL DEFAULT ''
               CHECK(after_source  IN ('','INTERNAL_DRAFT','OVERRIDE','MISSING')),
  reason       varchar(300) NOT NULL DEFAULT '',
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一条任务的同一个字段只有一行（一个字段被两条规则改两次 = 说不清最终值）
  UNIQUE(tenant_id, task_id, field),
  -- 🔴 声称改了却前后一字不差，或声称没改却前后不同，两种自相矛盾都直接拒
  --    （照抄 V177 listing_bulk_change 的同一 CHECK）
  CONSTRAINT listing_content_writeback_change_outcome_matches_changed CHECK((outcome='APPLIED') = changed),
  -- 声称有改后值就必须写得出它的来源；声称是 MISSING 就必须真的没有值
  CONSTRAINT listing_content_writeback_change_after_source_consistent
    CHECK((after_source='MISSING') = (after_value='')),
  CONSTRAINT listing_content_writeback_change_before_source_consistent
    CHECK((before_source='MISSING') = (before_value='')),
  FOREIGN KEY(tenant_id, task_id) REFERENCES listing_content_writeback_task(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_content_writeback_change IS
  '4.3.2.1 · 🔴 逐字段改前/改后。改前来自 V175 平台侧当前快照，改后来自 V177 内部草稿或'
  '显式覆盖值；两侧来源都逐行记下（before_source / after_source），复盘时能回答"这个改前值是什么时候的"。';

-- ===========================================================================
-- 索引
-- ===========================================================================
CREATE INDEX listing_content_writeback_recent ON listing_content_writeback(tenant_id, created_at DESC, id);
CREATE INDEX listing_content_writeback_state  ON listing_content_writeback(tenant_id, state);
CREATE INDEX listing_content_writeback_task_run   ON listing_content_writeback_task(tenant_id, writeback_id, state);
CREATE INDEX listing_content_writeback_task_list  ON listing_content_writeback_task(tenant_id, listing_id, created_at DESC);
-- 🔴 派发算配额时按 (tenant, platform, state, dispatched_at) 统计在途与窗口内次数
CREATE INDEX listing_content_writeback_task_dispatch
  ON listing_content_writeback_task(tenant_id, platform_id, state, dispatched_at DESC);
-- 变更历史的两个取法：按任务取（任务详情页）、按 listing 取（商品维度的历史时间线）
CREATE INDEX listing_content_writeback_change_task   ON listing_content_writeback_change(tenant_id, task_id);
CREATE INDEX listing_content_writeback_change_listing ON listing_content_writeback_change(tenant_id, listing_id, created_at DESC);
CREATE INDEX listing_content_writeback_change_field   ON listing_content_writeback_change(tenant_id, listing_id, field);

-- ===========================================================================
-- 触发器
-- ===========================================================================
-- (a) 🔴🔴 核心不变量：SUCCEEDED 是终态，重试绝不重跑已成功的回写。
--     「三条通道独立失败独立重试」在服务层的具体含义就是：**只**把 FAILED 打回队列。
--     将来谁在服务层手滑写了
--       update listing_content_writeback_task set state='PENDING' where state='SUCCEEDED'
--     会在测试期就被拒（23514），而不是等到平台上被重复改了一轮内容。
--     已在途（DISPATCHED）也不能被直接打回 PENDING —— 那等于悄悄撤回一次已发出的平台写请求。
CREATE FUNCTION guard_content_writeback_terminal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.state='SUCCEEDED' AND NEW.state<>'SUCCEEDED' THEN
    RAISE EXCEPTION
      'Content writeback task % already SUCCEEDED; retry must only reset FAILED tasks, never re-write content that the platform already accepted',
      OLD.id USING ERRCODE='23514';
  END IF;
  IF OLD.state='DISPATCHED' AND NEW.state='PENDING' THEN
    RAISE EXCEPTION
      'Content writeback task % is in flight (DISPATCHED); report its result before requeueing',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER content_writeback_terminal BEFORE UPDATE ON listing_content_writeback_task
  FOR EACH ROW EXECUTE FUNCTION guard_content_writeback_terminal();

-- (b) 派发时 attempts 必须 +1（「派了几次」是限流与重试审计的一部分）
CREATE FUNCTION guard_content_writeback_attempts() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.state='DISPATCHED' AND OLD.state<>'DISPATCHED' AND NEW.attempts <> OLD.attempts + 1 THEN
    RAISE EXCEPTION
      'Content writeback task % must increment attempts by exactly 1 when dispatched (was %, now %)',
      OLD.id, OLD.attempts, NEW.attempts USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER content_writeback_attempts BEFORE UPDATE ON listing_content_writeback_task
  FOR EACH ROW EXECUTE FUNCTION guard_content_writeback_attempts();

-- (c) 🔴 变更行引用的 listing 必须与它的任务一致。
--     复合外键已经保证同租户，这里再保证**同一个商品** ——
--     跨商品挂引用会让「这条改前改后属于哪个商品」永远答不出来。
CREATE FUNCTION guard_content_writeback_change_listing() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NOT EXISTS(
    SELECT 1 FROM listing_content_writeback_task t
     WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.task_id AND t.listing_id = NEW.listing_id
  ) THEN
    RAISE EXCEPTION 'Content writeback change must reference a task of the same listing' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER content_writeback_change_listing BEFORE INSERT OR UPDATE ON listing_content_writeback_change
  FOR EACH ROW EXECUTE FUNCTION guard_content_writeback_change_listing();

-- (d) 🔴 变更历史**不可就地改写**：「当时推了什么、改前是什么」是事后追责与申诉的凭据。
--     改用追加新的一次触发来更正，而不是 UPDATE 历史（与 V175 guard_publish_record_immutable
--     同一纪律）。
CREATE FUNCTION guard_content_writeback_change_append_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  RAISE EXCEPTION 'Content writeback change history is append-only; issue a new writeback instead of rewriting history'
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER content_writeback_change_append_only BEFORE UPDATE ON listing_content_writeback_change
  FOR EACH ROW EXECUTE FUNCTION guard_content_writeback_change_append_only();

-- (e) 🔴 改前 / 改后的长度上限：一次灾难级的输入不该能把「改前值」撑成一个无界的文本，
--     变更历史要可读、界面要能显示。DB 层给第二道防线（与 V177 guard_bulk_change_not_oversized 同一手法）。
CREATE FUNCTION guard_content_writeback_change_not_oversized() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF char_length(NEW.before_value)>20000 OR char_length(NEW.after_value)>20000 THEN
    RAISE EXCEPTION
      'Content writeback change % produced an oversized value (% / % chars)',
      NEW.id, char_length(NEW.before_value), char_length(NEW.after_value) USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER content_writeback_change_not_oversized BEFORE INSERT ON listing_content_writeback_change
  FOR EACH ROW EXECUTE FUNCTION guard_content_writeback_change_not_oversized();

-- (f) 一次触发的进度计数由服务层重算后写入；DB 侧保证 COMPLETED ⟺ 全部终态
--     （既无 PENDING 也无 DISPATCHED）。
CREATE FUNCTION guard_content_writeback_progress() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.state='COMPLETED' AND (NEW.pending_count<>0 OR NEW.dispatched_count<>0) THEN
    RAISE EXCEPTION
      'Content writeback % cannot be COMPLETED while % pending / % dispatched tasks remain',
      NEW.id, NEW.pending_count, NEW.dispatched_count USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER content_writeback_progress BEFORE UPDATE ON listing_content_writeback
  FOR EACH ROW EXECUTE FUNCTION guard_content_writeback_progress();

-- ===========================================================================
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V171 / V177 / V183 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'listing_content_writeback','listing_content_writeback_task',
    'listing_content_writeback_change' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 数据范围（RESTRICTIVE，与 V171 / V177 / V183 同构：可见性随店铺负责人 / 部门收敛）
-- ===========================================================================
-- 🔴 批量回写尤其需要这条：目标集合里的每一条 listing 都必须落在调用者的店铺范围内，
--    否则"用户勾了 100 条、其中 30 条不属于他"会被服务层当正常批量派发出去。
--    范围判定读本表自带的 listing_ids 快照（**不是**任务行）：本行先于任务行写入，
--    用"已存在的任务"做判定会让 insert 本行这一步永远过不了 RLS
--    （与 V177 listing_bulk_operation / V183 publish_batch 同一手法）。
CREATE POLICY data_scope ON listing_content_writeback AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_writeback.tenant_id
             AND l.id = ANY(listing_content_writeback.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_writeback.tenant_id
             AND l.id = ANY(listing_content_writeback.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_content_writeback_task AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_writeback_task.tenant_id
             AND l.id=listing_content_writeback_task.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_writeback_task.tenant_id
             AND l.id=listing_content_writeback_task.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_content_writeback_change AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_writeback_change.tenant_id
             AND l.id=listing_content_writeback_change.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_writeback_change.tenant_id
             AND l.id=listing_content_writeback_change.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ===========================================================================
-- 账号与授权（沿用 V1..V183 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  listing_content_writeback, listing_content_writeback_task,
  listing_content_writeback_change TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V183 同口径）。
REVOKE ALL ON
  listing_content_writeback, listing_content_writeback_task,
  listing_content_writeback_change FROM starcon_app;

-- ===========================================================================
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：内容回写属于「平台操作」语义，与 4.1.1.1 listing 列表、
-- 4.2.1.2 批量编辑、4.3.1.2 批量发布、2.5.2.1 常用回写操作同族，identity 服务零改动。
