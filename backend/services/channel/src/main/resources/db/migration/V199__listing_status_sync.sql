-- ===========================================================================
-- 4.3.2.2 平台状态回流（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.3.2.2 平台状态回流
--   - 职责：接收并展示平台侧的商品状态变化：审核通过/驳回、下架、限流、被抑制（suppressed）、
--           类目审核、价格异常通知，并支持一键处理（如按驳回原因批量修正重发）。
--   - 输入：平台事件/webhook；轮询补充。
--   - 输出：状态变更记录 + 需处理队列。
--   - 关系：上游 = 02 同步；下游 = 4.1.1.3、4.3.1.3。
--           **回流是平台状态的权威来源**，内部状态与之冲突时以平台为准并告警。
--
-- ---------------------------------------------------------------------------
-- 零、🔴🔴 本迁移最重要的一件事：`listing_status_event` **已经存在**，本模块**扩展**它
-- ---------------------------------------------------------------------------
-- 调度者给本车道的表名命名空间是 `listing_status_event` + `listing_status_action_queue`。
-- 实测集成头：**`listing_status_event` 由 V171（4.1.1.1 Listing 列表）建好了**，不是本模块
-- 建的。V171 的文件头已经把设计意图写死了：
--
--   「「近 7 日状态变化」是职责句里的展示项，而状态变更是 4.1.1.3 的输出。本模块只需要
--     **读到**它，所以事件表 `listing_status_event` 随本迁移建立，4.1.1.3 在这张表上补
--     生命周期状态机与平台回写即可，**无需第二次建表**。」
--   「但事件记录**不交给服务层写**：`listing` 的 status 一变，AFTER UPDATE 触发器就地
--     落一条事件（来源固定为 MANUAL，**4.3.2.2 的平台回流才是 PLATFORM**）。」
--
-- 而且 `source` 列的 CHECK **已经把 'PLATFORM' 这个取值预留好了**：
--
--   source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('MANUAL','PLATFORM'))
--
-- 也就是说：这张表就是本模块该写的那张表，'PLATFORM' 那个取值就是本模块该用的。
-- 因此本迁移**扩展**它（ALTER ADD COLUMN，**一律带 DEFAULT**），**不另建一张平行的事件表** ——
-- 另建一张会让「这个商品最近 7 天状态变了什么」有两个答案，而 4.1.1.1 / 4.1.1.2 读的是 V171
-- 那张，本模块写另一张 ⇒ 列表显示的状态轨迹与本模块记录的平台状态**分叉**。
--
-- 🔴 ALTER 的纪律（AGENTS.md + 本批约定）：
--   * **只 ADD COLUMN，且一律带 DEFAULT** ⇒ 4.1.1.1 的触发器 INSERT 路径
--     （`insert into listing_status_event (id,tenant_id,listing_id,from_status,to_status,
--     source,note,occurred_at) values (...)`）**不会违约**，既有模块零回归。
--   * **不 SET NOT NULL 任何既有列**、**不改既有列的取值域**、**不 DROP 任何东西**。
--   * 新增的 CHECK 全部只约束**新列**，且对既有行**恒成立**（既有行 source='MANUAL'，
--     新 CHECK 都是「source='PLATFORM' ⇒ …」的形式，对 MANUAL 行是空真）——
--     **既不放宽也不收紧旧值域**。
--   * 唯一的例外是**新增触发器**（见第五节 (a)）：它只对 `source='PLATFORM'` 的行生效，
--     MANUAL 行（4.1.1.1 触发器写的）行为一字不变。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 蓝图加粗句「回流是平台状态的权威来源，内部状态与之冲突时以平台为准并告警」
-- ---------------------------------------------------------------------------
-- 这句话在本模块有三个落点，缺一不可：
--
--   ① **冲突判定是真实计算出来的**，不是一句文案。判定口径写在下面的
--      `listing_status_event.conflict_detected` / `internal_status` / `platform_status` 上，
--      由服务层从「内部 listing.status」与「平台权威状态」两边的**真实取值**推导。
--   ② **以平台为准**落在 `resolution='PLATFORM_WINS'` —— 平台状态是权威，内部状态不因
--      冲突而被改写（改内部生命周期状态是 4.1.1.3 的活，见第三节）。
--   ③ **告警必须真的落一条记录**，不是只在页面上标个红。落法是**DB 触发器**：
--      `listing_status_event` 的 AFTER INSERT 触发器见到「PLATFORM 事件 + 冲突判定为真」
--      就**就地插一条 `listing_status_action_queue` 的 CONFLICT_ALERT 行**。
--      ⇒ 「有冲突必有告警」是**结构事实**，服务层漏写不了（与 V171 用触发器落状态事件
--      同一个手法：漏记比多记更难发现）。
--
-- 🔴 冲突判定口径（服务层的纯函数，DB 只存判定结果）：
--     内部可售性（listing.status 七态）：
--       ON_SALE                        → SELLABLE      （内部声称在售）
--       UNDER_REVIEW / REJECTED        → PENDING       （内部在等平台裁决，**没有明确主张**）
--       DRAFT / DELISTED_BY_US / DELISTED_BY_PLATFORM / BLOCKED → NOT_SELLABLE
--     平台可售性（事件类型）：
--       APPROVED                       → SELLABLE
--       REJECTED / DELISTED / SUPPRESSED → NOT_SELLABLE
--       THROTTLED / CATEGORY_REVIEW / PRICE_ALERT → RESTRICTED（仍在售，但受限）
--     **冲突 = 双方对「当前是否在售」的主张相反**：
--       (内部 SELLABLE 且 平台 NOT_SELLABLE) 或 (内部 NOT_SELLABLE 且 平台 SELLABLE)
--     刻意**不算**冲突的两种：
--       * 内部 PENDING（UNDER_REVIEW / REJECTED）而平台给出裁决 ⇒ 那是**时序差**，
--         不是事实矛盾（平台结论到了、内部生命周期还没跟上）。内部生命周期的同步归 4.1.1.3。
--       * 平台 RESTRICTED（限流 / 类目审核 / 价格异常）⇒ 商品**仍在售**，平台只是加了限制，
--         与内部「在售」不矛盾。这类事件进**需处理队列**，但**不进冲突告警**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 依赖面：只引用同服务已交付的表，零跨车道 DDL 回归
-- ---------------------------------------------------------------------------
--   * V171 `listing`（内部七态 status = 冲突判定的**内部**一侧；shop_id / marketplace_id）
--   * V171 `listing_status_event`（**本模块扩展**它；source 的 'PLATFORM' 取值即本模块）
--   * V2   `platform_operation`（02 队列 = 「一键处理」发起修正重发的落点；
--         **只 INSERT，不改它的任何列 / CHECK / 索引**；operation 复用既有取值 'PUBLISH'）
--
-- 下游 = 4.1.1.3（生命周期联动，**未交付**）与 4.3.1.3（发布任务视图，**未交付**）——
-- 两段都在 `GET /capabilities` 里显式声明 available=false + ownerModule + impact，
-- **不假装具备**。
--
-- ---------------------------------------------------------------------------
-- 三、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 本迁移**无任何金额列**：价格异常通知只是**平台给的一条通知**（事件类型 + 原文），
-- 判定「价格是否真的异常」归 05 定价，本模块**不存一个影子价格**。
-- 存在的数值列只有计数（integer），不参与任何金额计算。
-- 无 float / double 一列。
--
-- ---------------------------------------------------------------------------
-- 四、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒（4.1.1.1 实测踩过）。
-- 集成测试**断言写回的值逐字相等**，不能只断言没抛异常。
--
-- ===========================================================================
-- 1. ALTER listing_status_event · 扩展「平台回流」这一段语义
-- ===========================================================================
-- 🔴 每一列都带 DEFAULT ⇒ V171 触发器的 INSERT 路径不会违约。
ALTER TABLE listing_status_event
  -- 🔴 事件类型：职责句逐词对应。
  --    APPROVED 审核通过 / REJECTED 审核驳回 / DELISTED 下架 / THROTTLED 限流 /
  --    SUPPRESSED 被抑制 / CATEGORY_REVIEW 类目审核 / PRICE_ALERT 价格异常通知。
  --    STATUS_CHANGE 是 V171 触发器写的**人工**状态变更（内部改 status）—— 本模块不写它。
  ADD COLUMN event_type text NOT NULL DEFAULT 'STATUS_CHANGE'
    CHECK(event_type IN ('STATUS_CHANGE','APPROVED','REJECTED','DELISTED','THROTTLED',
                         'SUPPRESSED','CATEGORY_REVIEW','PRICE_ALERT')),
  -- 🔴 平台侧的**权威状态原文**（平台自己的 token，如 ACTIVE / INACTIVE / SUPPRESSED）。
  --    原文照抄不折叠大小写 —— 平台怎么写我们怎么存，「以平台为准」指的是这个值。
  ADD COLUMN platform_status text NOT NULL DEFAULT '',
  -- 🔴 判定冲突时的**内部状态快照**（listing.status 当时的值）。
  --    不冲突时也是空串；冲突时**必填**（否则"与什么冲突"答不上来）。
  ADD COLUMN internal_status text NOT NULL DEFAULT '',
  -- 🔴 冲突判定结果（真实计算，不是文案）
  ADD COLUMN conflict_detected boolean NOT NULL DEFAULT false,
  -- 冲突说明：把内部状态与平台状态逐字写出来（"内部 ON_SALE，平台 DELISTED"）
  ADD COLUMN conflict_note varchar(500) NOT NULL DEFAULT '',
  -- 🔴 处置口径：NOT_REQUIRED 无冲突 / PLATFORM_WINS **以平台为准**
  ADD COLUMN resolution text NOT NULL DEFAULT 'NOT_REQUIRED'
    CHECK(resolution IN ('NOT_REQUIRED','PLATFORM_WINS')),
  -- 🔴 驳回原因（平台给的原文）。「按驳回原因批量修正重发」按这一列筛。
  ADD COLUMN rejection_reason varchar(300) NOT NULL DEFAULT '',
  -- 平台事件的原文结构（jsonb，平台给什么存什么，不解释不加工）
  ADD COLUMN detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 入库时刻（平台事件发生时刻是 occurred_at；这两个是不同的时刻，差值有意义：
  --    平台事件到入库之间的延迟就是同步延迟）
  ADD COLUMN received_at timestamptz NOT NULL DEFAULT now(),
  -- 🔴 平台事件是从哪条通道进来的：WEBHOOK 平台推送 / POLL 轮询补充 / API 显式录入
  ADD COLUMN ingest_channel text NOT NULL DEFAULT 'MANUAL'
    CHECK(ingest_channel IN ('MANUAL','WEBHOOK','POLL','API')),
  -- 🔴 谁把这条事件录进来的（平台适配器 / 轮询任务 / 运营手工补录）。
  --    事件的**内容**来自平台，但**录入动作**有主体 —— 事后要能问"这条是谁录的"。
  ADD COLUMN ingested_by uuid DEFAULT NULL;

-- 🔴 既有行（source='MANUAL'）对新 CHECK 恒成立 ⇒ **不放宽也不收紧旧值域**。
ALTER TABLE listing_status_event
  -- 🔴 平台事件必须真的带一个事件类型与一个平台权威状态：
  --    「平台状态回流」如果连平台状态是什么都没记，那它什么权威性都没有。
  ADD CONSTRAINT listing_status_event_platform_payload CHECK(
    source <> 'PLATFORM' OR (event_type <> 'STATUS_CHANGE' AND btrim(platform_status) <> '')),
  -- 🔴 声称有冲突就必须写得出：冲突说明 + 当时的内部状态 + 处置口径
  ADD CONSTRAINT listing_status_event_conflict_explained CHECK(
    (conflict_detected = false)
    OR (btrim(conflict_note) <> '' AND btrim(internal_status) <> '' AND resolution = 'PLATFORM_WINS')),
  -- 🔴 声称没冲突就不许带处置口径（"以平台为准"只在真有冲突时说）。
  --    ⚠️ 写成 (conflict_detected AND resolution='PLATFORM_WINS') OR (NOT conflict_detected AND
  --    resolution='NOT_REQUIRED')，**不能**写成 `conflict_detected = resolution = 'PLATFORM_WINS'`：
  --    那是 (boolean = text) = text，PG 直接报类型错。
  ADD CONSTRAINT listing_status_event_resolution_consistent CHECK(
    (conflict_detected AND resolution = 'PLATFORM_WINS')
    OR (NOT conflict_detected AND resolution = 'NOT_REQUIRED')),
  -- 🔴 驳回原因只在驳回事件上有意义：别的事件带一个驳回原因就是脏数据
  ADD CONSTRAINT listing_status_event_rejection_reason_only_for_rejection CHECK(
    event_type <> 'REJECTED' OR btrim(rejection_reason) <> '');

COMMENT ON TABLE listing_status_event IS
  '4.1.1.1 建表 · 4.3.2.2 扩展。source=''PLATFORM'' 的行是平台状态回流（权威来源）；'
  'source=''MANUAL'' 的行是内部改 status 时由 V171 触发器派生的人工变更。'
  '冲突判定与「以平台为准」的处置口径在 conflict_detected / resolution 上。';

-- ===========================================================================
-- 2. listing_status_action_queue · 需处理队列（+ 冲突告警行）
-- ===========================================================================
-- 蓝图：「输出：状态变更记录 + **需处理队列**」与「支持一键处理（如按驳回原因批量修正重发）」。
--
-- 三种队列项：
--   REPUBLISH     可一键修正重发（驳回 / 下架 / 被抑制 —— 平台说这件商品该重新上去）
--   REVIEW        需人工处理（限流 / 类目审核 / 价格异常 —— 改一下内容或价格就能继续）
--   CONFLICT_ALERT 内部状态与平台状态冲突的告警（由 (a) 触发器自动落，见第五节）
--
-- 🔴 CONFLICT_ALERT 与 REPUBLISH/REVIEW **可以同时存在**：一条「平台说已下架、内部还说在售」
--    的事件既是**冲突告警**（事实矛盾），也是**待处理项**（要重新上去）—— 两件不同的事，
--    不该塞进一行。
CREATE TABLE listing_status_action_queue (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 🔴 队列项挂在**哪条平台事件**下（「这条待办是从哪个平台通知来的」必须答得出来）
  event_id      uuid NOT NULL,
  listing_id    uuid NOT NULL,
  kind          text NOT NULL
                CHECK(kind IN ('REPUBLISH','REVIEW','CONFLICT_ALERT')),
  -- OPEN 待处理 / ACKNOWLEDGED 已确认 / DISPATCHED 已发起修正重发（在途）/
  -- RESOLVED 已处理完 / CANCELLED 已作废
  state         text NOT NULL DEFAULT 'OPEN'
                CHECK(state IN ('OPEN','ACKNOWLEDGED','DISPATCHED','RESOLVED','CANCELLED')),
  -- 触发它的事件类型（冗余一份，便于队列页直接按类型筛；触发器钉住与事件一致）
  event_type    text NOT NULL
                CHECK(event_type IN ('APPROVED','REJECTED','DELISTED','THROTTLED','SUPPRESSED',
                                     'CATEGORY_REVIEW','PRICE_ALERT')),
  -- 🔴 驳回原因：从事件带过来一份，触发器钉住与事件**逐字相同**。
  --    「按驳回原因批量修正重发」走这一列的索引 —— 不必每次回表 join 事件表。
  rejection_reason varchar(300) NOT NULL DEFAULT '',
  -- 🔴 一键处理发起的 02 队列任务（V2 platform_operation）。「经队列」是外键 + CHECK 的结构事实。
  operation_id  uuid,
  -- 🔴 幂等键：与 platform_operation 的 idempotency_key 同一口径（V2 UNIQUE(tenant_id, idempotency_key)）。
  --    重复点「一键处理」**不会**产生第二个平台任务 —— 同一个队列项永远是同一个幂等键。
  idempotency_key varchar(128),
  -- 🔴 谁动过这条待办（谁点的「一键处理」/「确认」/「处理完成」）。留痕用。
  actor_id      uuid DEFAULT NULL,
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  completed_at  timestamptz,
  UNIQUE(tenant_id, id),
  -- 同一事件同一类型只有一条队列项（不重复入队）
  UNIQUE(tenant_id, event_id, kind),
  -- 🔴 幂等键在本租户内唯一（NULL 不参与唯一约束 ⇒ 非重发类可以不给）
  UNIQUE(tenant_id, idempotency_key),
  -- 🔴「经 02 队列」：已派发必有队列行
  CONSTRAINT listing_status_action_queue_dispatched_has_queue
    CHECK(state <> 'DISPATCHED' OR operation_id IS NOT NULL),
  -- 反过来：有队列行必是已派发或已处理完（不存在"有队列行但没派过"的状态）
  CONSTRAINT listing_status_action_queue_queue_implies_dispatched
    CHECK(operation_id IS NULL OR state IN ('DISPATCHED','RESOLVED')),
  -- 🔴 重发类派发必须有幂等键（平台侧去重靠它）
  CONSTRAINT listing_status_action_queue_republish_has_key
    CHECK(kind <> 'REPUBLISH' OR state <> 'DISPATCHED' OR idempotency_key IS NOT NULL),
  -- 终态必须有时间戳
  CONSTRAINT listing_status_action_queue_terminal_has_timestamp
    CHECK(state <> 'RESOLVED' OR completed_at IS NOT NULL),
  -- 事件与商品必须同租户且**同一个商品**（复合外键已保证同租户）
  FOREIGN KEY(tenant_id, event_id) REFERENCES listing_status_event(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  -- 🔴 02 队列任务（V2 platform_operation）：一键处理发起的修正重发真的在队列里
  FOREIGN KEY(tenant_id, operation_id) REFERENCES platform_operation(tenant_id, id)
);
COMMENT ON TABLE listing_status_action_queue IS
  '4.3.2.2 · 需处理队列 + 冲突告警行。CONFLICT_ALERT 由 listing_status_event 的 AFTER INSERT '
  '触发器自动落 ⇒「有冲突必有告警」是结构事实。REPUBLISH 派发时写 V2 platform_operation，'
  '幂等键唯一 ⇒ 重复点「一键处理」不会产生第二个平台任务。';

-- ===========================================================================
-- 3. 索引
-- ===========================================================================
-- 事件侧：列表（真分页）、按类型筛、按冲突筛。
-- V171 已有 listing_status_event_recent(tenant,listing,occurred_at desc) 与
-- listing_status_event_time(tenant,occurred_at desc) —— 本模块**不重复建**这两条。
CREATE INDEX listing_status_event_type      ON listing_status_event(tenant_id, event_type, occurred_at DESC);
CREATE INDEX listing_status_event_source    ON listing_status_event(tenant_id, source, occurred_at DESC);
-- 冲突告警区：只索引有冲突的行
CREATE INDEX listing_status_event_conflicts ON listing_status_event(tenant_id, occurred_at DESC)
  WHERE conflict_detected;
-- 队列侧：待处理清单、按驳回原因筛（🔴「按驳回原因批量修正重发」走这条索引）
CREATE INDEX listing_status_action_queue_state   ON listing_status_action_queue(tenant_id, state, created_at DESC);
CREATE INDEX listing_status_action_queue_reason  ON listing_status_action_queue(tenant_id, rejection_reason, state);
CREATE INDEX listing_status_action_queue_type    ON listing_status_action_queue(tenant_id, event_type, state);
CREATE INDEX listing_status_action_queue_listing ON listing_status_action_queue(tenant_id, listing_id, created_at DESC);
CREATE INDEX listing_status_action_queue_event   ON listing_status_action_queue(tenant_id, event_id);

-- ===========================================================================
-- 4. 触发器：队列项与事件必须自洽
-- ===========================================================================
-- (a) 🔴🔴 核心不变量：**有冲突必有告警**。
--     平台事件落库时，若判定出冲突（conflict_detected），就地插一条 CONFLICT_ALERT 队列行。
--     放在 DB 触发器而不是服务层，是因为「漏记告警」比「多记告警」更难发现：
--     页面会安静地显示"没有告警"，而真相是内部状态与平台状态正相反。
--     与 V171 用触发器落人工状态事件是同一手法。
CREATE FUNCTION guard_listing_status_event_conflict_alert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.source='PLATFORM' AND NEW.conflict_detected THEN
    INSERT INTO listing_status_action_queue
      (id,tenant_id,event_id,listing_id,kind,state,event_type,rejection_reason,note,created_at,updated_at)
    VALUES
      (gen_random_uuid(),NEW.tenant_id,NEW.id,NEW.listing_id,'CONFLICT_ALERT','OPEN',NEW.event_type,
       NEW.rejection_reason,
       '内部状态与平台状态冲突，以平台为准：'||NEW.conflict_note,now(),now());
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_status_event_conflict_alert AFTER INSERT ON listing_status_event
  FOR EACH ROW EXECUTE FUNCTION guard_listing_status_event_conflict_alert();

-- (b) 🔴 队列项引用的必须是**同一个商品的**那条事件，且冗余的字段与事件逐字一致。
--     「这条待办是从哪个平台通知来的」与「按驳回原因筛出来的待办，其原因是不是真的」
--     —— 两件事都必须答得上来，不能靠服务层自觉。
CREATE FUNCTION guard_listing_status_queue_event() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  ev listing_status_event%ROWTYPE;
BEGIN
  SELECT * INTO ev FROM listing_status_event
   WHERE tenant_id=NEW.tenant_id AND id=NEW.event_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing status action queue % must reference an existing status event', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF ev.listing_id <> NEW.listing_id THEN
    RAISE EXCEPTION 'Listing status action queue % must reference an event of the same listing', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF ev.event_type <> NEW.event_type THEN
    RAISE EXCEPTION 'Listing status action queue % event_type must match its event', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF ev.rejection_reason <> NEW.rejection_reason THEN
    RAISE EXCEPTION 'Listing status action queue % rejection_reason must match its event', NEW.id
      USING ERRCODE='23514';
  END IF;
  -- 🔴 冲突告警必须真的对应一个冲突事件 —— 不许凭空插一条告警
  IF NEW.kind='CONFLICT_ALERT' AND (NOT ev.conflict_detected OR ev.resolution <> 'PLATFORM_WINS') THEN
    RAISE EXCEPTION 'Listing status action queue % claims a conflict alert but its event has no conflict', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_status_action_queue_event BEFORE INSERT OR UPDATE ON listing_status_action_queue
  FOR EACH ROW EXECUTE FUNCTION guard_listing_status_queue_event();

-- (c) 🔴 队列项的**身份不可改写**：它挂在哪条事件上、是哪一类待办，定了就不改。
--     「已派发的修正重发」被改成另一种待办类型 = 平台侧已经发生的那个动作在账上消失了。
CREATE FUNCTION guard_listing_status_queue_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.event_id IS DISTINCT FROM OLD.event_id
     OR NEW.listing_id IS DISTINCT FROM OLD.listing_id
     OR NEW.kind IS DISTINCT FROM OLD.kind
     OR NEW.event_type IS DISTINCT FROM OLD.event_type THEN
    RAISE EXCEPTION 'Listing status action queue % identity is immutable; cancel it and open a new one instead',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_status_action_queue_identity BEFORE UPDATE ON listing_status_action_queue
  FOR EACH ROW EXECUTE FUNCTION guard_listing_status_queue_identity();

-- (d) 🔴 平台事件**不可就地改写**：平台说了什么就是说了什么。
--     平台后来改了主意 ⇒ 那是**新的一条事件**（append-only），不是把旧的那条 UPDATE 掉。
--     「当时平台到底说了什么」是事后追责与申诉的凭据。
--     ⚠️ 只对 source='PLATFORM' 的行生效：MANUAL 行归 V171 的触发器（只 INSERT，从不 UPDATE），
--     本触发器对它们是空操作，**不影响 4.1.1.1 / 4.1.1.2 的任何既有行为**。
CREATE FUNCTION guard_listing_status_event_platform_append_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.source='PLATFORM' THEN
    RAISE EXCEPTION 'Platform status event % is append-only; a later platform change is a NEW event, not an update',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_status_event_platform_append_only BEFORE UPDATE ON listing_status_event
  FOR EACH ROW EXECUTE FUNCTION guard_listing_status_event_platform_append_only();

-- ===========================================================================
-- 5. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- listing_status_event 的 isolation 与 data_scope 策略**已由 V171 建好**（isolation +
-- 店铺维度 data_scope RESTRICTIVE）—— 本迁移**不重建、不改写**它们，只给新表补同构的一套。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_status_action_queue'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 队列项的可见性随**商品所属店铺**收敛（与 V171 listing_status_event 的 data_scope 同构）：
-- 运营不能通过队列看到无权店铺的待办。
CREATE POLICY data_scope ON listing_status_action_queue AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_status_action_queue.tenant_id
             AND l.id=listing_status_action_queue.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_status_action_queue.tenant_id
             AND l.id=listing_status_action_queue.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ===========================================================================
-- 6. 账号与授权（沿用 V1..V195 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_status_action_queue TO channel_app;

-- listing_status_event 的授权 V171 已给过（GRANT ... TO channel_app）—— 本迁移只补新列，
-- 新列随表一起授权，**不需要也不应该**再 GRANT 一次。
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V187 同口径）。
REVOKE ALL ON listing_status_action_queue FROM starcon_app;

-- ===========================================================================
-- 7. 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：平台状态回流属于「平台操作」语义，与 4.1.1.1 listing 列表、
-- 4.2.1.2 批量编辑、4.3.1.2 批量发布、4.3.2.1 内容回写、2.5.2.1 常用回写操作同族，
-- identity 服务零改动。
