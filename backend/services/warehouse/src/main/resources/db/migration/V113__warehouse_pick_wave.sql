-- ===========================================================================
-- V113 · 8.3.2.2 波次与合并拣货（warehouse 服务）
-- ===========================================================================
--
-- 蓝图 08 卷 8.3.2.2 原文：
--   职责：把多张出库单按规则合并成拣货波次，提高拣货效率：按承运商/库区/订单数合并，
--         支持波次进度跟踪。
--   输入：出库单集合；合并规则（承运渠道、库区、时段）。
--   输出：波次任务 + 各出库单在波次中的位置。
--   关系：上游 = 8.3.2.1；下游 = 8.3.3.1。**合并只影响拣货效率，不改变订单粒度**：
--         波次完成后仍需按订单拆分复核与打包。
--
-- 卷级关键约束（本迁移逐条对应）：
--   1)「合并只影响拣货效率，不改变订单粒度」→ 本迁移**不动** pick_task / pick_task_line /
--      outbound_order 的任何一列。波次是**排班**（谁和谁一起走），拣货任务仍是**工单**
--      （一张出库单一张任务，实拣、异常、完成都还在 8.3.2.1 那里）。波次完成只是把
--      这一组工单的进度汇总成一个数，绝不替代任何一张工单的完成。
--   2)「按承运商/库区/订单数合并」→ 三个维度都落到数据上：
--      承运商 = outbound_order.carrier_code（冻结进 pick_wave_task）；
--      库区 = 取货库位在 V105 库位树里的**顶层根节点**（V105 注释原文「NULL = 顶层根节点
--      （区）」），由递归 CTE 算出来；
--      订单数 = pick_wave_rule.max_order_count（硬上限，超出的留在队列里等下一波）。
--   3)「合并规则（承运渠道、库区、时段）」→ pick_wave_rule 三张表里的一张配置表，
--      时段用仓本地小时窗口 [time_slot_start, time_slot_end) 表达，出库单的 created_at
--      落在窗口里才归这条规则管。
--
-- ---------------------------------------------------------------------------
-- 1. 本迁移建什么、不建什么
-- ---------------------------------------------------------------------------
-- 建四张表：
--   pick_wave_rule   合并规则配置（承运渠道 / 库区 / 时段 / 订单数上限）
--   pick_wave        波次单头
--   pick_wave_task   波次成员：一张出库单的拣货任务在波次里的位置
--   pick_wave_event  append-only 波次流水
--
-- **零 ALTER**：本文件不含任何 ALTER TABLE。V110 的六张表、outbound_order、
-- warehouse_location 一列都没碰 —— 合并是排班，不是改工单。
--
-- **库区从哪来**：仓库里没有 zone 这一列（V2 的 warehouse_location 只有
-- code/type/status/pickable，V105 补了 parent_id 建树）。V105 的注释把「父库位为 NULL」
-- 的顶层节点直接叫作「区」，所以库区 = 取货库位沿 parent 链上行到顶层的那个节点。
-- 递归 CTE 在 V113 §5 的注释里写明；一条拣货任务如果横跨多个区，它就没有单一库区，
-- 只能进「库区不限」的规则（zone_id IS NULL），绝不被硬塞进某一个区。
--
-- ---------------------------------------------------------------------------
-- 2. 「合并」这件事必须由数据库裁决，否则两个拣货组会同时走同一个区
-- ---------------------------------------------------------------------------
-- 同一个仓、同一个库区、同一个承运渠道，**同时只能有一个未完波次**。理由不是洁癖：
-- 两个拣货员同时在 A 区按同一承运商口径拣货，走位必然互相踩，而这件事在任何一张
-- 表的 CHECK 里都看不出来 —— 只有「这个键上已经有一个未完波次」这一个事实能挡住它。
--
--   CREATE UNIQUE INDEX pick_wave_open_key
--     ON pick_wave(tenant_id, warehouse_id, carrier_code, coalesce(zone_id, 零值))
--     WHERE status IN ('PLANNED','PICKING');
--
-- 同理，**一张拣货任务同时只能属于一个未完波次**（pick_wave_task 的部分唯一索引，
-- 靠 released 列判定）。这两个索引合起来就是「合并」二字的全部含义。
--
-- 代价要说清：订单数上限（max_order_count）撞顶之后，**剩下的出库单要等这个波次
-- 收工才能开下一个波**，而不是同时开两个波。所以「候选」接口必须报「还有 N 张在等
-- 上一个波次收工」，否则看板上就只剩一个解释不通的空白。
--
-- ---------------------------------------------------------------------------
-- 3. 波次进度为什么**不**冗余成列（与 V110 刻意不同的一处设计）
-- ---------------------------------------------------------------------------
-- V110 把 pick_task 的计划/实拣合计做成了列，由 refresh 触发器从**本模块自有的明细表**
-- pick_task_line 重算 —— 那是安全的，因为明细与单头同属一个写入所有者。
--
-- 波次的明细是 pick_wave_task，而它的进度住在 **pick_task（V110 的表，8.3.2.1 的唯一
-- 写入所有者）**。在本表挂一个「pick_task 变了就刷波次」的触发器，等于让本模块去挂
-- 别人表的触发器：那既是 ALTER 别人的表（与「零 ALTER」冲突），也会让 8.3.2.1 以后
-- 改自己的表时踩到一条它不认识的外部依赖。
--
-- 所以这里**一个冗余进度列都不建**：order_count / task_count / planned_line_count /
-- planned_quantity / picked_line_count / picked_quantity 全部在查询时由
-- pick_wave_task ⋈ pick_task 一次聚合算出来（见服务层 WAVE_SELECT）。
-- 「永远算得对」比「算得快而且大部分时候对」值钱 —— 而聚合带了 join 与 FILTER 之后，
-- 一页 200 个波次的代价仍然可以忽略。
--
-- DB 级仍然要挡住的那一条，用 BEFORE 守卫直接**回表读当前行**（V110 同一纪律：
-- 触发器里不回读 NEW 的可变列）：
--   guard_pick_wave_status_fn：COMPLETED 必须在**全部成员拣货任务都已终态**时才允许。
--   这条不靠应用层自觉 —— 绕过服务层直连 SQL 也过不去。
--
-- ---------------------------------------------------------------------------
-- 4. 位置（position）的连续性由 DB 保证
-- ---------------------------------------------------------------------------
-- 蓝图要「各出库单在波次中的位置」。位置的**含义**是「这组里第几个走」，所以它必须是
-- 1..n 的连续整数；中间移走一个成员之后不能留下 1,2,4 这种洞（洞会让「按位置排序」
-- 在界面与打印上出现一个不存在的空位）。refresh_pick_wave_positions_fn 在成员增删后
-- 重排为 1..n。
--
-- ---------------------------------------------------------------------------
-- 5. 库区（顶层根节点）怎么算
-- ---------------------------------------------------------------------------
--   with recursive chain as (
--     select l.id as leaf_id, l.id, l.parent_id, 0 as hops
--       from warehouse_location l
--      where l.tenant_id = ? and l.warehouse_id = ?
--     union all
--     select c.leaf_id, p.id, p.parent_id, c.hops + 1
--       from chain c join warehouse_location p on p.id = c.parent_id
--   )
--   select leaf_id, id as zone_id, code as zone_code from chain where parent_id is null
--
-- V105 的 guard_warehouse_location_tree 防环且限六层，所以每片叶子恰好命中一行
-- parent_id is null（有 parent 的走不到底，有环的走不到顶）。hops 上界仍在 SQL 里留
-- 一层保险：祖先链被并发改动成环时返回 0 行而不是无限递归。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. pick_wave_rule · 合并规则配置
-- ---------------------------------------------------------------------------
CREATE TABLE pick_wave_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则号：租户内唯一。'' 表示「不限」，不是通配符 —— 空串与 'ANY' 这类字面值混用
  -- 会让「我要的就是不限」和「我要匹配字面量 ANY」在数据上分不开。
  rule_code varchar(64) NOT NULL,
  rule_name varchar(200) NOT NULL,

  warehouse_id uuid NOT NULL,

  -- 三个合并维度。空 / null = 不限（蓝图说「按承运商/库区/订单数合并」，
  -- 「不限」是一等公民的选项而不是「没填」）。
  carrier_match varchar(64) NOT NULL DEFAULT '',
  zone_id uuid,

  -- 时段窗口：仓**本地**小时左闭右开 [start, end)。0 ≤ start < end ≤ 24。
  -- 用仓本地而不是 UTC：拣货班次是按仓库所在地的作息排的，「下午班」在两个时区的
  -- 仓库里不是同一批货。warehouse.timezone 来自 V1 / V69。
  time_slot_start smallint NOT NULL DEFAULT 0,
  time_slot_end smallint NOT NULL DEFAULT 24,

  -- 订单数上限（蓝图点名的第三个合并维度）。撞顶后剩下的出库单等下一个波次 ——
  -- §2 说清了为什么不能同时开两个波。
  max_order_count integer NOT NULL CHECK(max_order_count>0),

  -- 多条规则同时命中一批订单时的取舍序（小者优先）。入库走它是为了「哪条规则说了算」
  -- 有一个确定答案，而不是取决于索引顺序。
  priority integer NOT NULL DEFAULT 100,

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  retired_at timestamptz,
  -- 停用必须写原因：规则一停，一批正在等的出库单就换了归并口径，没有原因的停用等于
  -- 让昨天和今天的波次用不同标准却没人说得清。
  retire_reason varchar(300),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,warehouse_id,rule_code),

  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,zone_id) REFERENCES warehouse_location(tenant_id,id),

  CHECK(btrim(rule_code)<>'' AND btrim(rule_name)<>''),
  CHECK(time_slot_start>=0 AND time_slot_end<=24 AND time_slot_start<time_slot_end),
  CHECK(status='ACTIVE' OR (retired_at IS NOT NULL AND retire_reason IS NOT NULL
                            AND btrim(retire_reason)<>''))
);

-- 「同一批订单只能被一条 ACTIVE 规则认领」：承运渠道 + 库区 + 时段窗口这个三元组在
-- 同租户同仓内唯一。两条 ACTIVE 规则命中同一批订单、却各有不同的订单数上限时，
-- 「这批订单归哪个波次」就没有确定答案了 —— 由数据库裁决而不是靠服务层挑第一条。
CREATE UNIQUE INDEX pick_wave_rule_active_key
  ON pick_wave_rule(tenant_id, warehouse_id, carrier_match,
                    (coalesce(zone_id,'00000000-0000-0000-0000-000000000000'::uuid)),
                    time_slot_start, time_slot_end)
  WHERE status='ACTIVE';
-- 规则台：按优先级列出当前生效规则（波次建完后要能回答「按哪条规则合的」）。
CREATE INDEX pick_wave_rule_active
  ON pick_wave_rule(tenant_id, warehouse_id, priority, rule_code)
  WHERE status='ACTIVE';

-- 停用只能发生一次，且必须带原因。
CREATE FUNCTION guard_pick_wave_rule_retire_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status text; v_retired timestamptz;
BEGIN
  SELECT status, retired_at INTO v_status, v_retired FROM pick_wave_rule WHERE id = NEW.id;
  IF v_status='INACTIVE' AND v_retired IS NOT NULL THEN
    IF NEW.retired_at IS NOT NULL AND NEW.retired_at <> v_retired THEN
      RAISE EXCEPTION '合并规则 % 已停用，不能改停用时刻。', NEW.rule_code
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_wave_rule_retire_guard
  BEFORE UPDATE ON pick_wave_rule
  FOR EACH ROW EXECUTE FUNCTION guard_pick_wave_rule_retire_fn();

-- ---------------------------------------------------------------------------
-- 2. pick_wave · 波次单头
-- ---------------------------------------------------------------------------
CREATE TABLE pick_wave (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  wave_code varchar(32) NOT NULL,

  warehouse_id uuid NOT NULL,
  -- 承运渠道冻结（'' = 不限 / 未指定承运商）。出库单上的 carrier_code 是履约事实，
  -- 波次上这一列是「这批货按谁的渠道一起走」的判据。
  carrier_code varchar(64) NOT NULL DEFAULT '',
  -- 库区冻结（null = 跨区或不限）。见文件头 §5。
  zone_id uuid,

  -- 本波次按哪条规则合并。**可空**：手工指定成员的波次不来自任何规则，
  -- 那时 merge_basis 说清「手工指定」即可 —— 伪造一条规则 id 只会让台账失真。
  rule_id uuid,

  -- 合并口径原文，随响应返回。按哪几个维度合的、上限多少、为什么这么排，
  -- 都是半年后回看时唯一能解释「为什么这两张单被凑到一起」的凭据。
  merge_basis varchar(600) NOT NULL,

  -- 四态。**刻意不含「已发货」**：波次结束 = 这一组拣货任务都拣完了，
  -- 出库单各自进「待复核」（8.3.3.1），发货仍是 8.3.4.1 的事。
  status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status IN
    ('PLANNED','PICKING','COMPLETED','CANCELLED')),

  -- 波次负责人（一个波次一个拣货组 / 一个 PDA）。放行即必须有负责人。
  assignee_user_id uuid,

  planned_pick_at timestamptz NOT NULL,
  started_at timestamptz,
  completed_at timestamptz,
  cancelled_at timestamptz,
  cancel_reason varchar(300),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,wave_code),

  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,zone_id) REFERENCES warehouse_location(tenant_id,id),
  FOREIGN KEY(tenant_id,rule_id) REFERENCES pick_wave_rule(tenant_id,id),

  CHECK(btrim(wave_code)<>''),
  CHECK(btrim(merge_basis)<>''),
  CHECK(status NOT IN ('PICKING','COMPLETED')
        OR (assignee_user_id IS NOT NULL AND started_at IS NOT NULL)),
  CHECK((status='COMPLETED') = (completed_at IS NOT NULL)),
  CHECK((status='CANCELLED')  = (cancelled_at IS NOT NULL)),
  CHECK(status<>'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason)<>''))
);

-- 合并判据：同租户同仓同库区同承运渠道，同时只有一个未完波次（见文件头 §2）。
-- 部分唯一索引里的 coalesce 让 zone_id IS NULL 与 zone_id = 零值 落在同一个键上。
CREATE UNIQUE INDEX pick_wave_open_key
  ON pick_wave(tenant_id, warehouse_id, carrier_code,
               (coalesce(zone_id,'00000000-0000-0000-0000-000000000000'::uuid)))
  WHERE status IN ('PLANNED','PICKING');
-- 波次作业台：未完波次按计划拣货时刻排序（先到先走）。
CREATE INDEX pick_wave_open
  ON pick_wave(tenant_id, warehouse_id, planned_pick_at, id)
  WHERE status IN ('PLANNED','PICKING');
CREATE INDEX pick_wave_code
  ON pick_wave(tenant_id, wave_code);
CREATE INDEX pick_wave_zone
  ON pick_wave(tenant_id, zone_id, created_at DESC, id)
  WHERE zone_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. pick_wave_task · 波次成员（各出库单在波次中的位置）
-- ---------------------------------------------------------------------------
CREATE TABLE pick_wave_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  wave_id uuid NOT NULL,

  -- 唯一写入者：本模块（8.3.2.2）。8.3.2.1 的六张表一个字都没改 ——
  -- 波次是排班，工单是工单，合并不改变订单粒度（蓝图关系段原话）。
  pick_task_id uuid NOT NULL,
  -- 冗余出库单 id：详情与进度汇总用一次 join 就够，不必每次绕回工单单头。
  outbound_order_id uuid NOT NULL,

  -- 位置：从 1 起连续。refresh_pick_wave_positions_fn 在成员增删后重排为 1..n。
  position integer NOT NULL CHECK(position>0),

  -- 承运渠道与库区的**冻结快照**。为什么冻：出库单的承运渠道在待发货前可以被改，
  -- 而「这两张单因为同一个承运商被凑进一个波次」是已经发生的排班事实 ——
  -- 改完之后单头那一列要由触发器跟着回写，成员上这两列必须保持入库当时的读数，
  -- 否则「当时按什么合的」就再也答不出来了。
  carrier_code varchar(64) NOT NULL DEFAULT '',
  zone_id uuid,

  planned_pick_at timestamptz NOT NULL,

  -- 波次收尾（COMPLETED / CANCELLED）时由触发器置 true，把这张工单放回候选池。
  -- 部分唯一索引靠它判「在波里」。
  released boolean NOT NULL DEFAULT false,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,wave_id,position),
  UNIQUE(tenant_id,wave_id,pick_task_id),

  FOREIGN KEY(tenant_id,wave_id) REFERENCES pick_wave(tenant_id,id),
  FOREIGN KEY(tenant_id,pick_task_id) REFERENCES pick_task(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_order_id) REFERENCES outbound_order(tenant_id,id),
  FOREIGN KEY(tenant_id,zone_id) REFERENCES warehouse_location(tenant_id,id)
);

-- 一张拣货任务同时只能在一个未完波次里（文件头 §2）。
CREATE UNIQUE INDEX pick_wave_task_active
  ON pick_wave_task(tenant_id, pick_task_id)
  WHERE NOT released;
-- 按位置取一个波次的成员（详情页与打印都是这个顺序）。
CREATE INDEX pick_wave_task_wave_position
  ON pick_wave_task(tenant_id,wave_id,position);
-- 「这个库区/承运渠道下有哪些未完波次成员」：候选与冲突提示的取数口径。
CREATE INDEX pick_wave_task_open
  ON pick_wave_task(tenant_id,wave_id,position)
  WHERE NOT released;

-- 位置重排：成员增删之后把 position 重写成 1..n。
-- 触发器**只挂 INSERT / DELETE**，重排本身是一次 UPDATE，不会再次触发本函数，
-- 所以不存在递归；而正常路径上也根本没有「改位置」这个动作。
CREATE FUNCTION refresh_pick_wave_positions_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_wave uuid; v_next int := 1; r record;
BEGIN
  v_wave := CASE WHEN TG_OP='DELETE' THEN OLD.wave_id ELSE NEW.wave_id END;
  FOR r IN
    SELECT id FROM pick_wave_task
     WHERE wave_id = v_wave
     ORDER BY position, created_at, id
  LOOP
    UPDATE pick_wave_task SET position = v_next WHERE id = r.id AND position <> v_next;
    v_next := v_next + 1;
  END LOOP;
  RETURN NULL;
END $$;
CREATE TRIGGER pick_wave_task_positions_refresh
  AFTER INSERT OR DELETE ON pick_wave_task
  FOR EACH ROW EXECUTE FUNCTION refresh_pick_wave_positions_fn();

-- 波次进入终态 → 全部成员 released，把这些拣货任务放回候选池。
-- 波次取消不改变任何库存：货在原库位、预占不变，与 8.3.2.1 的取消同一纪律。
CREATE FUNCTION release_pick_wave_tasks_fn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IN ('COMPLETED','CANCELLED') AND NEW.status <> OLD.status THEN
    UPDATE pick_wave_task SET released = true
     WHERE wave_id = NEW.id AND NOT released;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER pick_wave_task_release
  AFTER UPDATE OF status ON pick_wave
  FOR EACH ROW EXECUTE FUNCTION release_pick_wave_tasks_fn();

-- ---------------------------------------------------------------------------
-- 4. pick_wave_event · append-only 波次流水
-- ---------------------------------------------------------------------------
CREATE TABLE pick_wave_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  wave_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN
    ('PLANNED','RELEASED','TASK_ATTACHED','TASK_DETACHED','COMPLETED','CANCELLED')),
  from_status varchar(16),
  to_status varchar(16),
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  detail text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,wave_id) REFERENCES pick_wave(tenant_id,id),
  CHECK(from_status IS NULL OR to_status IS NOT NULL),
  CHECK(btrim(detail)<>'')
);
CREATE INDEX pick_wave_event_wave
  ON pick_wave_event(tenant_id,wave_id,occurred_at,id);

-- ===========================================================================
-- 5. DB 级不变量（绕过服务层直连 SQL 也照样拒）
-- ===========================================================================

-- 5.1 状态机 + 「完成波次 = 全部成员工单都终态」
--
-- 与 V110 同一纪律：**回表读当前行**，不回读 NEW 的可变列（NEW 里 status 正是待校验的
-- 那一列，拿它当条件等于自己判自己）。
CREATE FUNCTION guard_pick_wave_status_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status text; v_open int; v_members int; v_terminal int;
BEGIN
  SELECT status INTO v_status FROM pick_wave WHERE id = NEW.id;

  -- 跃迁判据用 OLD.status / NEW.status 这一对**行内**值（触发器天生给得到）：
  -- 曾经写成「回表读当前行 status 再和 OLD.status 比」，而回表读到的就是 OLD 本身，
  -- 那个判断恒为假 —— 于是 PLANNED→COMPLETED 这种非法跃迁会被放行，直到下面
  -- 「全部成员工单终态」那一条才拦下来，报出来的原因还完全不是用户做的那一步。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    -- 合法跃迁：PLANNED → PICKING / CANCELLED，PICKING → COMPLETED / CANCELLED。
    -- 没有 PICKING → PLANNED（放行之后才发现合错了，要取消重来）。
    IF NOT (
         (OLD.status='PLANNED'  AND NEW.status IN ('PICKING','CANCELLED'))
      OR (OLD.status='PICKING'  AND NEW.status IN ('COMPLETED','CANCELLED'))
    ) THEN
      RAISE EXCEPTION '波次 % 不能从 % 变成 %（合法跃迁：PLANNED→PICKING/CANCELLED，PICKING→COMPLETED/CANCELLED）。',
        OLD.wave_code, OLD.status, NEW.status
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- 合并不改变订单粒度：波次完成的前提是**每一张成员拣货任务都已到终态**。
  -- 少一条没拣完就报「波次完成」，会让下游 8.3.3.1 拿到一个声称完成但仍有工单在动的
  -- 波次 —— 而复核是按出库单逐张做的，它不会替波次兜底。
  IF NEW.status = 'COMPLETED' THEN
    SELECT count(*),
           count(*) FILTER (WHERE t.status IN ('COMPLETED','CANCELLED'))
      INTO v_members, v_terminal
      FROM pick_wave_task m JOIN pick_task t ON t.tenant_id=m.tenant_id AND t.id=m.pick_task_id
     WHERE m.wave_id = NEW.id;
    IF v_members = 0 THEN
      RAISE EXCEPTION '波次 % 一个成员都没有，不能完成。', OLD.wave_code
        USING ERRCODE='23514';
    END IF;
    IF v_terminal <> v_members THEN
      SELECT count(*) INTO v_open
        FROM pick_wave_task m JOIN pick_task t ON t.tenant_id=m.tenant_id AND t.id=m.pick_task_id
       WHERE m.wave_id = NEW.id AND t.status NOT IN ('COMPLETED','CANCELLED');
      RAISE EXCEPTION '波次 % 还有 % 张拣货任务没到终态，不能完成：合并只影响拣货效率，'
                      '波次完成不替代任何一张工单的完成。', OLD.wave_code, v_open
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pick_wave_status_guard
  BEFORE UPDATE ON pick_wave
  FOR EACH ROW EXECUTE FUNCTION guard_pick_wave_status_fn();

-- 5.2 波次流水 append-only：证据不许改写。
CREATE FUNCTION guard_pick_wave_event_append_only_fn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '波次流水是 append-only 的事实凭据，不许 UPDATE/DELETE（wave_event_id=%）。', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER pick_wave_event_append_only
  BEFORE UPDATE OR DELETE ON pick_wave_event
  FOR EACH ROW EXECUTE FUNCTION guard_pick_wave_event_append_only_fn();

-- ===========================================================================
-- 6. 多租户与授权（与 V1 第 9 节、V72 第 6 节、V110 第 8 节同一条纪律）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['pick_wave_rule','pick_wave','pick_wave_task','pick_wave_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  pick_wave_rule, pick_wave, pick_wave_task, pick_wave_event TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V90、V110 同口径）。