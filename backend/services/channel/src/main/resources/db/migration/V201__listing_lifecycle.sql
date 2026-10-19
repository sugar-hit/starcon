-- ===========================================================================
-- V201 · 4.1.1.3 生命周期与下架（channel 服务）
-- ===========================================================================
-- 蓝图原文：「管理 listing 状态：在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 /
-- 平台下架 / 被封禁。支持平台侧状态变更被动同步，以及主动下架时选择是否回写平台。
-- 输出：状态变更记录（来源：平台 or 人工）+ 可选的平台操作任务。
-- 关系：上游 = 02 同步、4.3.2.2；下游 = 02 回写队列（5.1.1）。
-- **平台下架与内部下架是两个事实**，分别记录并展示差异 —— 平台把商品下架了而内部不知情，
-- 是最常见的漏单原因。」
--
-- ---------------------------------------------------------------------------
-- 本迁移的边界（AGENTS.md：禁止与既有表语义重复、禁止改既有表的状态域）
-- ---------------------------------------------------------------------------
-- 1. **不新建平行状态表**：七态 CHECK 已在 V171 的 `listing.status` 上，本迁移**不动**它。
--    状态变更事件也仍在 V171 的 `listing_status_event` 上，本迁移同样不动它的结构。
-- 2. **不改 `writeback_batch.kind` 的 CHECK**：「主动下架回写平台」复用既有的
--    kind='DELIST'（V169 文件头已把 DELIST 写进值域），**不另建下架任务表**。
-- 3. **唯一的既有对象改动**：`CREATE OR REPLACE FUNCTION record_listing_status_change()`。
--    见下面第三节 —— 这是本模块**必须**做的一件事，否则「平台下架」这条事件会被
--    记成「人工下架」，那正是本模块要消灭的那种谎。
--
-- ---------------------------------------------------------------------------
-- 1. listing_lifecycle_action · 人工动作的**意图**留痕
-- ---------------------------------------------------------------------------
-- 「状态变更记录」回答的是「状态变成什么了」；这张表回答的是另一个问题：
-- **「谁、为什么、要不要回写平台、关联到哪个平台任务」**。
--
-- 为什么必须有它（不是「顺手加的」）：
--   * 「主动下架时选择是否回写平台」——这个**选择**本身是事实，事件表里没有位置放它。
--     没有它，事后复盘「这次下架为什么没告诉平台」答不出来。
--   * 「平台下架 vs 内部下架」是**两个事实**，光看事件表只能看到状态变了，
--     看不到「这条是平台说的」还是「这是我们自己决定的」—— 后者由本表的 source 补齐。
--   * 人工动作与平台操作任务（writeback_batch）之间的关联必须**结构化**，
--     否则「点了下架但平台任务没生成」这种半途失败查不出来。
--
-- 🔴 状态变更事件本身**不在这张表里**：它由 V171 的触发器落在 `listing_status_event`
--    上（事件记录不交给服务层写）。本表只记**动作的意图与回执**。
CREATE TABLE listing_lifecycle_action (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,

  -- 动作的发起对象：始终是一条 listing（状态机改的是 listing.status）
  listing_id        uuid NOT NULL,

  -- 动作种类（与 ListingLifecycleModels.ACTIONS 同一口径）：
  --   DELIST        主动下架      → DELISTED_BY_US
  --   REPUBLISH     重新上架      → ON_SALE
  --   SUBMIT_REVIEW 提交平台审核  → UNDER_REVIEW
  --   MARK_BLOCKED  标记被封禁    → BLOCKED
  --   APPLY_PLATFORM_STATUS 平台状态被动同步（平台侧权威，内部状态随之联动）
  action            text NOT NULL
                    CHECK(action IN ('DELIST','REPUBLISH','SUBMIT_REVIEW','MARK_BLOCKED',
                                     'APPLY_PLATFORM_STATUS')),

  -- 🔴 动作前后的内部状态（逐字）。两个值都落库 ⇒ 事后能自己复核这条动作，
  --    不必回查事件表（事件表可能已经被后续动作淹没在时间线里）。
  from_status       text NOT NULL
                    CHECK(from_status IN ('DRAFT','ON_SALE','UNDER_REVIEW','REJECTED',
                                          'DELISTED_BY_US','DELISTED_BY_PLATFORM','BLOCKED')),
  to_status         text NOT NULL
                    CHECK(to_status IN ('DRAFT','ON_SALE','UNDER_REVIEW','REJECTED',
                                          'DELISTED_BY_US','DELISTED_BY_PLATFORM','BLOCKED')),

  -- 🔴 来源：MANUAL = 内部人工决定；PLATFORM = 平台侧权威状态的被动同步。
  --    「平台下架与内部下架是两个事实」全靠这一列区分。
  source            text NOT NULL DEFAULT 'MANUAL'
                    CHECK(source IN ('MANUAL','PLATFORM')),

  -- 平台事件的类型（仅 APPLY_PLATFORM_STATUS 有值）：APPROVED / REJECTED / DELISTED /
  -- THROTTLED / SUPPRESSED / CATEGORY_REVIEW / PRICE_ALERT。
  -- 与 listing_status_event.event_type 同一取值域（V199）。
  platform_event_type text NOT NULL DEFAULT '',

  -- 平台侧的权威状态原文（仅 APPLY_PLATFORM_STATUS 有值）：平台自己的 token，原样存。
  platform_status   text NOT NULL DEFAULT '',

  -- 平台驳回原因（仅 REJECTED 类事件有值）。蓝图「输入：…平台驳回原因」。
  rejection_reason  varchar(300) NOT NULL DEFAULT '',

  -- 人工动作的原因（为什么下架 / 为什么重新上架）
  reason            varchar(500) NOT NULL DEFAULT '',

  -- 🔴 这次动作**有没有选择回写平台**。false = 只改内部状态，平台侧不知情。
  --    这正是「平台下架 vs 内部下架」差异的判定依据之一。
  writeback_requested boolean NOT NULL DEFAULT false,

  -- 🔴 关联到的平台操作任务：writeback_batch（kind='DELIST'）与它的一条 item。
  --    不建 FK 到 writeback_batch_item —— 它的主键是 (tenant_id, id) 复合外键形态，
  --    与本表的 (tenant_id, id) 形态一致，可以建；这里建 FK 保住「动作 ↔ 平台任务」
  --    的结构事实（不是一条可漂移的注释）。
  writeback_batch_id uuid,
  writeback_item_id  uuid,

  -- 平台事件 id（仅 APPLY_PLATFORM_STATUS 有值）：这条联动是响应哪条平台事件。
  platform_event_id  uuid,

  -- 谁做的（人工动作 = 操作人；平台联动 = 触发联动的那一方）
  actor_id          uuid DEFAULT NULL,

  created_at        timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一条 listing 上同一个动作类型可以有多次（反复下架、反复提交审核），
  -- 但**同一次平台事件只能被联动一次** —— 平台说了一次的事，内部不该改两遍。
  UNIQUE(tenant_id, platform_event_id),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, platform_event_id) REFERENCES listing_status_event(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, writeback_batch_id) REFERENCES writeback_batch(tenant_id, id),
  FOREIGN KEY(tenant_id, writeback_item_id) REFERENCES writeback_batch_item(tenant_id, id),

  -- 🔴 要回写平台，就必须真的有一个平台任务；不要求回写，就不许挂一个任务。
  --    「说回写了但没有任务」= 界面显示已下发而平台侧什么都没收到。
  CONSTRAINT listing_lifecycle_action_writeback_has_batch
    CHECK(writeback_requested = (writeback_batch_id IS NOT NULL)),
  CONSTRAINT listing_lifecycle_action_writeback_has_item
    CHECK(writeback_batch_id IS NULL OR writeback_item_id IS NOT NULL),
  -- 平台联动必须写明平台说的是什么。
  -- ⚠️ platform_event_id **允许为空**：平台说的这件事**没有让内部状态发生变化**时
  --    （限流 / 类目审核 / 价格异常这类「商品仍在售」的通知），状态机不产生状态变更事件，
  --    平台事实由**本行**承载 —— 它记录的是「平台说了什么、我们据此做了什么」。
  --    这不是漏记：平台事件本身由 4.3.2.2 的 listing_status_event 逐字记着。
  CONSTRAINT listing_lifecycle_action_platform_payload
    CHECK(action <> 'APPLY_PLATFORM_STATUS'
          OR (source = 'PLATFORM' AND btrim(platform_status) <> '')),
  -- 人工动作不许挂平台事件（那是平台联动的专属字段）
  CONSTRAINT listing_lifecycle_action_manual_has_no_platform_event
    CHECK(action = 'APPLY_PLATFORM_STATUS' OR platform_event_id IS NULL)
);

COMMENT ON TABLE listing_lifecycle_action IS
  '4.1.1.3 · 人工动作的意图留痕（谁 / 为什么 / 是否回写平台 / 关联到哪个平台任务）。'
  '状态变更事件本身在 listing_status_event 上，由 V171 的触发器落 —— 本表不重复记事件。';

-- 索引：按商品查动作（时间线里的「动作」段）、按平台事件查联动、按回写批次查。
CREATE INDEX listing_lifecycle_action_listing   ON listing_lifecycle_action(tenant_id, listing_id, created_at DESC);
CREATE INDEX listing_lifecycle_action_source    ON listing_lifecycle_action(tenant_id, source, created_at DESC);
CREATE INDEX listing_lifecycle_action_action    ON listing_lifecycle_action(tenant_id, action, created_at DESC);
CREATE INDEX listing_lifecycle_action_writeback ON listing_lifecycle_action(tenant_id, writeback_batch_id)
  WHERE writeback_batch_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ---------------------------------------------------------------------------
-- 与 V171 / V199 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_lifecycle_action'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围随**商品所属店铺**收敛（与 V171 listing / listing_status_event 同构）：
-- 运营不能通过动作留痕看到无权店铺的商品动作。
CREATE POLICY data_scope ON listing_lifecycle_action AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_lifecycle_action.tenant_id AND l.id=listing_lifecycle_action.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_lifecycle_action.tenant_id AND l.id=listing_lifecycle_action.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ---------------------------------------------------------------------------
-- 3. 🔴🔴 record_listing_status_change() · 让事件的**来源**说真话
-- ---------------------------------------------------------------------------
-- 背景：V171 建这个触发器时把 source 硬写成 'MANUAL'（当时只有人工改状态这一种来源）。
-- 现在有了第二种来源：**平台侧状态变更被动同步**（本模块的职责）。
--
-- 如果不改这个函数，平台说「这个商品已下架」、内部状态随之联动成 DELISTED_BY_PLATFORM 时，
-- 触发器会落一条 source='MANUAL' 的事件 —— 也就是说：
--   **平台下架会被记成「人工下架」。**
-- 那正是蓝图加粗句「平台下架与内部下架是两个事实」要消灭的东西：
-- 时间线上会说「我们主动把它下架了」，而真相是平台下的 —— 漏单原因就此被掩盖。
--
-- 🔴 改法是**向后兼容**的：GUC `app.lifecycle_context` 没设时，落库的值与 V171
--    原来那条 INSERT **逐字段相同**（source='MANUAL' / event_type='STATUS_CHANGE' /
--    platform_status='' / internal_status='' / conflict_detected=false /
--    conflict_note='' / resolution='NOT_REQUIRED' / rejection_reason='' /
--    detail='{}' / note='' / occurred_at=now()）。
--    ⇒ 4.1.1.1（列表）与 4.1.1.2（详情）对事件的既有断言**零回归**。
--
-- 🔴 事件记录**仍然不交给服务层写**：本函数照旧是那条唯一的 INSERT 路径。
--    服务层只负责**设置 GUC**（app.lifecycle_context）并 UPDATE listing.status。
--    服务层直接 INSERT listing_status_event 仍然是错的（会与触发器重复记）。
--
-- GUC 载荷（jsonb）：
--   source            'MANUAL' | 'PLATFORM'          —— 缺省 'MANUAL'
--   eventType         平台事件类型；缺省 'STATUS_CHANGE'
--   platformStatus    平台权威状态原文；缺省 ''
--   internalStatus    判定冲突时的内部状态快照；缺省 ''
--   conflictDetected  布尔；缺省 false
--   conflictNote      冲突说明；缺省 ''
--   resolution        'NOT_REQUIRED' | 'PLATFORM_WINS'；缺省 'NOT_REQUIRED'
--   rejectionReason   驳回原因；缺省 ''
--   detail            平台事件原文结构（jsonb）；缺省 '{}'
--   note              备注；缺省 ''
--   occurredAt        事件发生时刻（ISO-8601 带偏移量）；缺省 now()（= 事务开始时刻）
--   ingestChannel     WEBHOOK / POLL / API / MANUAL；缺省 'MANUAL'
--   ingestedBy        谁录进来的（uuid）；缺省 NULL
--
-- ⚠️ source='PLATFORM' 时必须同时给出 eventType（且不能是 'STATUS_CHANGE'）与
--    非空 platformStatus —— V199 的 listing_status_event_platform_payload CHECK
--    会强制这一点：「平台状态回流如果连平台状态是什么都没记，那它什么权威性都没有」。
CREATE OR REPLACE FUNCTION record_listing_status_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  ctx jsonb;
BEGIN
 IF NEW.status IS DISTINCT FROM OLD.status THEN
  ctx := COALESCE(NULLIF(current_setting('app.lifecycle_context',true),'')::jsonb, '{}'::jsonb);
  INSERT INTO listing_status_event
    (id,tenant_id,listing_id,from_status,to_status,source,event_type,platform_status,
     internal_status,conflict_detected,conflict_note,resolution,rejection_reason,detail,
     note,occurred_at,received_at,ingest_channel,ingested_by)
  VALUES
    (gen_random_uuid(),NEW.tenant_id,NEW.id,OLD.status,NEW.status,
     COALESCE(ctx->>'source','MANUAL'),
     COALESCE(NULLIF(ctx->>'eventType',''),'STATUS_CHANGE'),
     COALESCE(ctx->>'platformStatus',''),
     COALESCE(ctx->>'internalStatus',''),
     COALESCE((ctx->>'conflictDetected')::boolean,false),
     COALESCE(ctx->>'conflictNote',''),
     COALESCE(NULLIF(ctx->>'resolution',''),'NOT_REQUIRED'),
     COALESCE(ctx->>'rejectionReason',''),
     COALESCE(ctx->>'detail','{}')::jsonb,
     COALESCE(ctx->>'note',''),
     COALESCE((ctx->>'occurredAt')::timestamptz, now()),
     now(),
     COALESCE(NULLIF(ctx->>'ingestChannel',''),'MANUAL'),
     (ctx->>'ingestedBy')::uuid);
 END IF;
 RETURN NEW;
END $$;

COMMENT ON FUNCTION record_listing_status_change() IS
  '4.1.1.1 建表 · 4.1.1.3 扩展来源。source 由 app.lifecycle_context 决定：'
  '没设 ⇒ ''MANUAL''（与 4.1.1.1 原行为逐字段相同）；设成 ''PLATFORM'' ⇒ 平台侧'
  '状态变更被动同步的事件，事件记录仍由本触发器落，服务层不直接 INSERT。';

-- ---------------------------------------------------------------------------
-- 4. 支撑索引（只加索引，**不改任何既有表结构**）
-- ---------------------------------------------------------------------------
-- listing_status_event 上补两条复合索引：
--   ① (tenant_id, source, listing_id, occurred_at DESC)
--      —— 「某个商品的最新一条平台事件」是差异区的核心查询（每个商品取一次），
--         V171 的 (tenant_id, listing_id, occurred_at DESC) 排不了 source，
--         V199 的 (tenant_id, source, occurred_at DESC) 排不了 listing_id。
--   ② (tenant_id, to_status, source, occurred_at DESC)
--      —— 七态 + 来源的复合筛选（时间线按状态与来源筛）。
CREATE INDEX listing_status_event_listing_source
  ON listing_status_event(tenant_id, source, listing_id, occurred_at DESC);
CREATE INDEX listing_status_event_status_source
  ON listing_status_event(tenant_id, to_status, source, occurred_at DESC);

-- listing 上补一条七态筛选索引：V171 的 listing_status 是 (tenant_id, status)，
-- 列表页要按状态 + 最近更新排序，补一条带排序键的复合索引。
CREATE INDEX listing_status_recent
  ON listing(tenant_id, status, updated_at DESC, id);

-- ---------------------------------------------------------------------------
-- 5. 账号与授权（沿用 V1..V199 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_lifecycle_action TO channel_app;

-- listing_status_event / listing / writeback_* 的授权在 V171 / V169 已给过 ——
-- 本迁移**没有**给那些表加列，**不需要**也不应该再 GRANT 一次。
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V199 同口径）。
REVOKE ALL ON listing_lifecycle_action FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 6. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：生命周期状态属于「平台操作」语义，与 4.1.1.1 listing 列表、
-- 4.2.1.2 批量编辑、4.3.1.2 批量发布、4.3.2.1 内容回写、4.3.2.2 平台状态回流、
-- 2.5.2.1 常用回写操作同族，identity 服务零改动。
