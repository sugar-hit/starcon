-- ===========================================================================
-- 8.5.1.1 作业效率（人效）：作业任务/作业记录事实表 + 人效口径
-- ===========================================================================
-- 蓝图原文：「统计仓库人效：人均日拣货件数/单量、拣货行走距离、打包时效、复核准确率、
-- 差错率，按人按日统计。」输入：「作业任务记录（操作人、时间、类型）；人员配置。」
-- 输出：「人效报表 + 排名 + 趋势。」上游 = 8.3 全部作业记录；下游 = 17 绩效。
-- 以及本模块被点名的红线：
-- 「**人效数据用于提成核算与排班**，需明确口径避免争议（ERP 人效报表是绩效争议高发区）。」
--
-- ---------------------------------------------------------------------------
-- 一、为什么本模块自己拥有「作业记录」这张表（路线 A 的自洽性论证）
-- ---------------------------------------------------------------------------
-- 蓝图把上游写成「8.3 全部作业记录」，而集成头里**8.3 整块尚未交付**：
-- warehouse 服务 33 张表里没有任何出库/拣货/复核/打包/发货表，8.3.1.1 出库单列表正在
-- 另一条车道落地（V72），它只会建 outbound_order / outbound_order_line，
-- **不会**建作业记录表；8.3.2.1 拣货、8.3.3.1 复核、8.3.3.2 打包都还没排上。
--
-- 所以本模块自己落地这张事实表，并**同时提供真实的写入入口**（创建 / 派工 / 开始 /
-- 完成 / 异常上报）。这不是「造一张演示用的假表等上游」，而是：
--   * 作业记录的粒度（本表）与 8.3 的作业单据粒度（出库单、波次、复核批次）**不同**——
--     本表记的是「某个人在某段时间做了多少作业、走了多远、出了几处差错」，
--     8.3 记的是「要拣哪些货」。人效统计的最小事实本来就比作业单据细一层，
--     等 8.3 落地后由 8.3 各车道把作业事件投递到本服务的端点（source_module 标明来源），
--     **不是**它们直接写这张表 —— 「每张事实表只有一个写入所有者」在这里就体现为：
--     本车道是 warehouse_work_task / warehouse_work_event 的唯一写入者。
--   * 如果本模块等 8.3 才开工，人效报表在这段时间是空的，而提成核算与排班正是最需要它的时候。
--
-- 本迁移**刻意不建** picking_task / review_task / packing_task 之类的表：那属于
-- 8.3.2.1 / 8.3.3.1 / 8.3.3.2 各自的车道，它们落地时会撞表。本表与它们正交：
-- 8.3 拥有作业**单据**，本表拥有作业**人效事实**。
--
-- ---------------------------------------------------------------------------
-- 二、三种作业类型与五个指标的对应（口径的数据库侧依据）
-- ---------------------------------------------------------------------------
-- 蓝图点了五个指标，它们各自需要的数据不一样，所以类型先钉死：
--
--   work_type='PICKING' 拣货 → 人均日拣货件数（piece_count）
--                        人均日拣货单量（order_count）
--                        拣货行走距离（travel_distance_m）
--   work_type='PACKING' 打包 → 打包时效（completed_at − started_at，墙钟时长）
--   work_type='REVIEW'  复核 → 复核准确率（(复核件数 − 差错件数) ÷ 复核件数）
--
-- 差错率跨类型统计（差错件数 ÷ 作业件数、差错单量 ÷ 涉及单量），因为它不是某一环节的
-- 专属指标：拣错、复核漏、打错包装都会记在各自那一张任务上。
--
-- 为什么只有这三种：蓝图 8.5.1.1 的五个指标只覆盖拣货、打包、复核。发货（8.3.4）另有
-- 面单与打印次数口径，不在本模块。枚举里不预留没人会写的值 —— 预留值会让
-- 「work_type='SHIPPING' 的人效在哪」变成永远查不出答案的幽灵分支。
--
-- ---------------------------------------------------------------------------
-- 三、口径（争议高发区，代码与报告里逐字重复同一套定义）
-- ---------------------------------------------------------------------------
-- 1. **按日**：自然日，**时区固定 Asia/Shanghai**，聚合键是
--    `(completed_at AT TIME ZONE 'Asia/Shanghai')::date`。
--    刻意不用服务器本地时区：本仓是跨境电商，同一份人效报表在不同机器上跑出不同的
--    「昨天」是绝对不可接受的。索引也建在这个表达式上（见下）。
--    「一天算到什么时候」以**任务完成的时刻**为准，不是任务开始或派工的时刻。
--
-- 2. **打卡时效 = 墙钟时长**（completed_at − started_at），**不是净作业时长**。
--    净作业时长要求剔除等待/中断，那需要另记「暂停」事件与暂停区间，本表没有那组数据，
--    算出来的「净时长」是假的。墙钟时长是**可复算**的口径：两个时间戳都在库里，
--    任何人拿同一行都能算出同一个数。这是有意的取舍，写在这里而不是藏起来。
--
-- 3. **打包时效的分母**：分母是**打包任务张数**（AVG over tasks），不是件数。
--    「一件货从 A 走到 B 用多久」在本表里算不出来（那需要逐件时间戳），
--    本模块不假装算得出来。异常终止（EXCEPTION）的任务**不计入**打包时效 ——
--    中途放弃的任务的墙钟时长不代表打包速度。
--
-- 4. **复核准确率的分母**：分母是**复核件数**（work_type='REVIEW' 的 piece_count 合计），
--    不是复核单数、不是复核行数。分子是复核件数 − 差错件数。
--    分母为 0 时返回 null（不可计算），**不返回 0**：0% 会被读成「复核全对」，
--    而真实含义是「今天没人复核」。这是本模块最容易出事的一处显示错误。
--
-- 5. **差错率的单位**：两个口径都出，且分母各自写明：
--      按件数 = Σ差错件数 ÷ Σ作业件数
--      按单量 = Σ差错单量 ÷ Σ涉及单量
--    差错件数不可能超过作业件数（DB 级 CHECK 钉死）—— 超过它意味着口径本身错了，
--    而不是「这个人错得离谱」。
--
-- 6. **差错 ≠ 异常**：差错（error_*）是「这个人做错了」（拣错、漏拣、打错），
--    进差错率；异常（EXCEPTION + exception_code）是「这个人做不完 / 做不了」
--    （缺货、设备故障、单据取消），进异常而不进差错率。把两者混在一起，
--    缺货的人会被算成错得最离谱 —— 这正是排班时最不公平的一种算法。
--
-- 7. **人均**：人均日 = 区间合计 ÷ (人数 × 作业天数)，其中**作业天数 = 区间内出现过
--    完成作业的自然日天数**，人数 = 区间内有过完成作业的**不同操作人**个数。
--    刻意**不**用「人数 × 区间自然日数」做分母：那样停工日、休假日会把人均拉低，
--    而停工不是任何一个人的效率问题。代价是「区间首尾的不完整日」会被当成完整日计入
--    —— 这条代价也写在这里，因为它是这个口径的真实弱点。
--
-- 8. **只统计已完成任务**：PENDING / RUNNING 不进报表。未完成的作业没有产出，
--    把它算成人效等于奖励「领了任务不干活」。EXCEPTION 计入任务张数与差错，
--    但不计入打包时效（见 3）。
--
-- 9. **操作人一旦开工就不可更改**（DB 级触发器钉死）：任务归属漂移会让行走距离与
--    时效在两个人之间来回跳，绩效争议将无从裁决。
--
-- ---------------------------------------------------------------------------
-- 四、状态机：PENDING → RUNNING → COMPLETED / EXCEPTION
-- ---------------------------------------------------------------------------
--   PENDING   待开工（可能还没派工）
--   RUNNING   作业中
--   COMPLETED 已完成（计量列在此刻落定）
--   EXCEPTION 异常终止（缺货/设备/取消），记 exception_code，可从未开工直接进入
--
-- COMPLETED 与 EXCEPTION 都是终态：其后的计量列与时间戳**一律不可改**。
-- 「先记 100 件、发现记错了改成 80」在提成核算里等于没有凭据 ——
-- 纠错必须靠异常上报留痕，而不是擦掉重写。
--
-- ---------------------------------------------------------------------------
-- 五、口径与类型
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,6) + Java BigDecimal，全程禁浮点（与 V1/V2/V59/V62 同口径）。
--   行走距离 NUMERIC(12,3) 米。
--   时间 timestamptz：应用层以 OffsetDateTime 绑定并写 ?::timestamptz，
--   不用 Timestamp.valueOf(LocalDateTime) —— 后者按 JVM 本地时区解释裸本地时间，
--   在东八区会静默偏 −28800s（见 V55/V59 与服务的注释）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. warehouse_work_task · 作业任务（人效事实主表）
-- ---------------------------------------------------------------------------
CREATE TABLE warehouse_work_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号。服务派生（WT-<yyyyMMdd>-<8 位 uuid 十六进制>），不接受调用方自填：
  -- 人手填的单号会撞，而人效报表是要拿去做提成核算的凭据。
  code varchar(32) NOT NULL,

  -- 作业类型。见文件头第二节。枚举里只有真能算出人效的三种。
  work_type varchar(16) NOT NULL CHECK(work_type IN ('PICKING','PACKING','REVIEW')),

  -- 来源车道：8.3 各车道落地后把作业事件投递到本服务端点时标明来源模块号
  -- （如 '8.3.2.1'）。本服务自己发起的任务记 '8.5.1.1'。
  -- 留这一列是为了「这条人效数据是谁报上来的」可追：报表数字有争议时，
  -- 第一个要回答的问题永远是「这批数据从哪来」。
  source_module varchar(32) NOT NULL CHECK(btrim(source_module)<>''),

  -- 上游单据号（出库单号、波次号等）。**逻辑编号，不建外键**：
  -- 8.3 尚未交付，且 AGENTS.md 禁止跨服务建 FK 或查对方的表。
  -- 空串表示「无上游单据」（如临时补录的作业），刻意不用 NULL：
  -- NULL 与「忘了填」无法区分，而这里必须能区分。
  reference_no varchar(64) NOT NULL DEFAULT '',

  warehouse_id uuid NOT NULL,

  -- 操作人：已认证用户 id（identity 解析出的成员关系）。
  -- NULL = 还没派工。本服务**无法**校验它是否真是本租户成员 ——
  -- identity 才是成员关系的所有者（与 V59 的 assigned_to_user_id 同一条理由）。
  -- 代价写在这里：派错人只影响人效归属，不会越权读写任何业务数据；
  -- 而未派工的任务**不允许开工**（服务层校验），所以人效永远不会记到 NULL 头上。
  operator_user_id uuid,

  assigned_at timestamptz,
  assigned_by_user_id uuid,

  -- 状态。见文件头第四节。
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','RUNNING','COMPLETED','EXCEPTION')),

  -- 开始时刻 = 作业开始端点写入的时刻（服务端取 now()，不接受请求参数传入：
  -- 调用方传时刻等于允许「把昨天做的活记在今天」，那会直接污染当日人效）。
  started_at timestamptz,
  -- 完成时刻 = 完成或异常终止的时刻。「日」的聚合键就是它（Asia/Shanghai）。
  completed_at timestamptz,

  -- ---- 计量列：全部在「完成」这一刻落定，之后不可改 ----
  -- 涉及单量：本任务覆盖的**去重后**单据张数（由上游给出，本服务不去重不同任务之间的同一张单，
  -- 跨任务去重需要全量明细，不是这张汇总表的粒度能做的事 —— 口径写明，不假装做得到）。
  order_count integer NOT NULL DEFAULT 0 CHECK(order_count >= 0),

  -- 作业行数：拣货行 / 打包件行 / 复核行。
  line_count integer NOT NULL DEFAULT 0 CHECK(line_count >= 0),

  -- 作业件数：拣货件数 / 打包件数 / 复核件数。
  piece_count numeric(19,6) NOT NULL DEFAULT 0 CHECK(piece_count >= 0),

  -- 拣货行走距离（米）。**只有拣货任务该有值**，服务层拒绝其他类型带这个字段；
  -- 非拣货任务一律 NULL —— 用 0 表示「没走」会把「没记」和「走了 0 米」混成一回事。
  travel_distance_m numeric(12,3)
    CHECK(travel_distance_m IS NULL OR travel_distance_m >= 0),

  -- 差错件数 / 差错单量。见文件头第三节第 5、6 条。
  -- error_piece_count ≤ piece_count 是 DB 级不变量：差错件数超过作业件数意味着口径本身错了。
  error_piece_count numeric(19,6) NOT NULL DEFAULT 0
    CHECK(error_piece_count >= 0 AND error_piece_count <= piece_count),
  error_order_count integer NOT NULL DEFAULT 0
    CHECK(error_order_count >= 0 AND error_order_count <= order_count),

  -- 异常：只在上报异常时写。差错与异常是**两回事**（见文件头第三节第 6 条）。
  exception_code varchar(24),
  exception_note varchar(500) NOT NULL DEFAULT '',

  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  -- ---- 时序与状态的一致性（服务层也校验，这里是兜底）----
  -- PENDING：两个时间戳都还没有。
  CHECK(status <> 'PENDING' OR (started_at IS NULL AND completed_at IS NULL)),
  -- RUNNING：已开始未完成。
  CHECK(status <> 'RUNNING' OR (started_at IS NOT NULL AND completed_at IS NULL)),
  -- 两个终态都必须留下完成时刻（异常终止也有终止时刻，它标记「这一天这个人到此为止」）。
  CHECK(status NOT IN ('COMPLETED','EXCEPTION') OR completed_at IS NOT NULL),
  -- 异常必须说清是什么异常，否则报表上只剩一个「异常」而无人能复盘。
  CHECK(status <> 'EXCEPTION' OR exception_code IS NOT NULL),
  -- 非异常状态不该挂着异常码。
  CHECK(status = 'EXCEPTION' OR exception_code IS NULL),
  -- 完成不早于开始（NULL 起始时无先后可比）。
  CHECK(started_at IS NULL OR completed_at IS NULL OR completed_at >= started_at),
  -- 已派工才有派工时刻与派工人。
  CHECK(operator_user_id IS NULL OR (assigned_at IS NOT NULL AND assigned_by_user_id IS NOT NULL)),
  -- 开始/完成必有操作人：没人干的作业算不出人效。
  CHECK((started_at IS NULL AND completed_at IS NULL) OR operator_user_id IS NOT NULL)
);

-- 人效报表主索引：按人 + 按日聚合，日就是 completed_at 在 Asia/Shanghai 下的自然日。
-- 索引建在**聚合键本身**上，而不是 completed_at 上 ——
-- 否则每次报表都要对整段数据做时区换算再分组。
CREATE INDEX warehouse_work_task_operator_day
  ON warehouse_work_task(tenant_id,operator_user_id,
    ((completed_at AT TIME ZONE 'Asia/Shanghai')::date))
  WHERE status IN ('COMPLETED','EXCEPTION');

-- 按类型 + 日：排名与趋势按作业类型切口径时走它。
CREATE INDEX warehouse_work_task_type_day
  ON warehouse_work_task(tenant_id,work_type,
    ((completed_at AT TIME ZONE 'Asia/Shanghai')::date))
  WHERE status IN ('COMPLETED','EXCEPTION');

-- 任务列表：状态筛选 + 最近更新（待办区永远看「最近动过的」）。
CREATE INDEX warehouse_work_task_status_updated
  ON warehouse_work_task(tenant_id,status,updated_at DESC);

-- 按仓库 + 日：报表按仓库切口径时走它。
CREATE INDEX warehouse_work_task_warehouse_day
  ON warehouse_work_task(tenant_id,warehouse_id,
    ((completed_at AT TIME ZONE 'Asia/Shanghai')::date))
  WHERE status IN ('COMPLETED','EXCEPTION');

-- ---------------------------------------------------------------------------
-- 2. 状态机与终态不可改（DB 级不变量）
-- ---------------------------------------------------------------------------
-- 蓝图那句「人效数据用于提成核算与排班，需明确口径避免争议」在这里落成可执行的东西：
-- 争议之所以无法裁决，通常是因为数据能被随手改。这两个触发器保证：
--   1. 状态只能沿合法边走（PENDING → RUNNING → COMPLETED/EXCEPTION，EXCEPTION 可从 PENDING 直接进）；
--   2. 终态之后计量列与时间戳一律冻结，纠错只能靠异常上报留痕，不能擦掉重写；
--   3. **已开工的任务不许换操作人** —— 归属漂移会让行走距离与时效在两个人之间来回跳。
--
-- 只有这条 UPDATE 路径能改这两个表：服务层在同一事务里写状态 + 计量 + 事件。
CREATE FUNCTION guard_work_task_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- updated_at 由触发器统一维护：任何一次 UPDATE 都算「这张任务刚被动过」，
  -- 待办区按它排序，不靠每个写入路径记得自己填。
  NEW.updated_at := now();

  -- 身份与定位列恒不可改：单号、类型、来源、仓库都是「这张任务是谁、属于哪」的凭据，
  -- 改了就没法解释这行人效是怎么来的。
  IF (NEW.tenant_id,NEW.id,NEW.code,NEW.work_type,NEW.source_module,NEW.reference_no,
      NEW.warehouse_id,NEW.created_at)
     IS DISTINCT FROM
     (OLD.tenant_id,OLD.id,OLD.code,OLD.work_type,OLD.source_module,OLD.reference_no,
      OLD.warehouse_id,OLD.created_at) THEN
    RAISE EXCEPTION '作业任务「%」的单号/类型/来源/仓库不可改：它们是这行人效的归属凭据。',
      OLD.code USING ERRCODE='23514';
  END IF;

  -- 已开工不许换人：人效归属一旦漂移，行走距离与时效的归属就无从裁决。
  -- 这一条对**任何** UPDATE 都成立（包括状态迁移本身）——
  -- 完成 / 异常端点不会改 operator，所以不会被误伤。
  IF OLD.started_at IS NOT NULL AND
     (NEW.operator_user_id,NEW.assigned_at,NEW.assigned_by_user_id)
     IS DISTINCT FROM
     (OLD.operator_user_id,OLD.assigned_at,OLD.assigned_by_user_id) THEN
    RAISE EXCEPTION '作业任务「%」已开工，不许更换操作人：已产生的时长与行走距离归属'
      '不能在人之间来回漂移。', OLD.code USING ERRCODE='23514';
  END IF;

  IF OLD.status = NEW.status THEN
    -- 状态没变 = 这不是一次状态迁移，所以计量与时间一个字都不许动：
    --   * 还没开工时 —— 计量只能由开工 / 完成 / 异常端点写入，不许手工填；
    --   * 已定稿之后 —— 那是提成核算的凭据，发现记错请另报异常留痕。
    -- （注意判据是「状态没变」而不是「已开工」：完成端点本来就要在同一条 UPDATE 里
    --  既写计量列又改状态，那是定稿，不是改。）
    IF (NEW.started_at,NEW.completed_at,NEW.order_count,NEW.line_count,NEW.piece_count,
        NEW.travel_distance_m,NEW.error_piece_count,NEW.error_order_count,
        NEW.exception_code,NEW.exception_note)
       IS DISTINCT FROM
       (OLD.started_at,OLD.completed_at,OLD.order_count,OLD.line_count,OLD.piece_count,
        OLD.travel_distance_m,OLD.error_piece_count,OLD.error_order_count,
        OLD.exception_code,OLD.exception_note) THEN
      RAISE EXCEPTION '作业任务「%」当前是 % 状态且状态未变，计量与时间不可改：'
        '计量只能由开工 / 完成 / 异常端点写入，已定稿的数字不能擦掉重写。',
        OLD.code, OLD.status USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;

  -- 合法边：PENDING → RUNNING；PENDING|RUNNING → COMPLETED|EXCEPTION。
  -- 这一条同时承担「终态不可回退」：终态任务无论怎么改计量或时间都到不了别的状态，
  -- 所以这里只需要按旧状态判一次，不需要再为终态单写一段。
  -- 单独允许 PENDING → COMPLETED：8.3 各车道批量上报的作业可能没有开工事件，
  -- 这类任务的 started_at 留空，打包时效对它自然是 NULL（AVG 会跳过），
  -- 不会污染时效均值 —— 比强行要求一条它根本没有的开工事件更诚实。
  IF NOT (
       (OLD.status = 'PENDING' AND NEW.status IN ('RUNNING','COMPLETED','EXCEPTION')) OR
       (OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED','EXCEPTION'))
     ) THEN
    RAISE EXCEPTION '作业任务「%」不能从 % 变成 %：状态机只认 PENDING → RUNNING → '
      'COMPLETED/EXCEPTION，终态不可回退。', OLD.code, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;
CREATE TRIGGER warehouse_work_task_transition
  BEFORE UPDATE ON warehouse_work_task
  FOR EACH ROW EXECUTE FUNCTION guard_work_task_transition();

-- ---------------------------------------------------------------------------
-- 3. warehouse_work_event · 作业事件（append-only：谁、什么时候、把哪张任务怎么了）
-- ---------------------------------------------------------------------------
-- 为什么不把状态历史塞进 warehouse_work_task 的几个时间戳：
-- 那几个时间戳只留「最后一次」，而人效争议要问的是「这个人什么时候接的任务、
-- 中间有没有停过、异常是谁报的」。四列时间戳表达不了一次完整的处理过程。
--
-- append-only：只能追加，不能改也不能删 —— 绩效争议复盘依赖未被改写的时间线。
-- 「先记开始再改开始时间」在任何系统里都做得到，这里不做。
CREATE TABLE warehouse_work_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- 事件类型。固定枚举而不是自由文本。
  event_type varchar(24) NOT NULL CHECK(event_type IN
    ('CREATED','ASSIGNED','STARTED','COMPLETED','EXCEPTION')),

  -- 动作发起人：已认证用户 id。派工事件记录**派工人**，
  -- 其余事件记录**操作人本人**（代理打卡时由服务端按任务归属写入，界面上如实说明）。
  actor_user_id uuid NOT NULL,

  occurred_at timestamptz NOT NULL DEFAULT now(),

  -- 异常码：只在 EXCEPTION 事件上有值。
  exception_code varchar(24),
  -- 人读得懂的一句话：派给了谁、为什么异常、完成了多少。
  detail text NOT NULL CHECK(btrim(detail)<>''),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES warehouse_work_task(tenant_id,id),

  -- 异常事件必须带异常码，否则时间线上会出现一条讲不清的「异常」。
  CHECK(event_type <> 'EXCEPTION' OR exception_code IS NOT NULL),
  -- 非异常事件不该挂异常码。
  CHECK(event_type = 'EXCEPTION' OR exception_code IS NULL)
);

-- 一张任务的完整时间线（详情页读它）。
CREATE INDEX warehouse_work_event_task
  ON warehouse_work_event(tenant_id,task_id,occurred_at,id);
-- 「某个环节出异常是什么时候的事」：异常复盘按时间倒查走它。
CREATE INDEX warehouse_work_event_exception
  ON warehouse_work_event(tenant_id,event_type,occurred_at DESC)
  WHERE event_type = 'EXCEPTION';

-- append-only：审计只能追加。
CREATE FUNCTION guard_work_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'warehouse_work_event 是 append-only：不允许 %，'
    '人效争议复盘依赖未被改写的时间线。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER warehouse_work_event_append_only
  BEFORE UPDATE OR DELETE ON warehouse_work_event
  FOR EACH ROW EXECUTE FUNCTION guard_work_event_append_only();

-- ---------------------------------------------------------------------------
-- 4. 多租户与授权（与 V1/V2/V54/V56/V59/V62 同一套写法）
-- ---------------------------------------------------------------------------
-- 两张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['warehouse_work_task','warehouse_work_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON warehouse_work_task, warehouse_work_event TO warehouse_app;
-- 事件是审计凭据：不给 DELETE（触发器再收一道）。任务表需要 UPDATE（状态机与派工），
-- 但终态冻结与状态机已由上面的触发器钉死。
REVOKE DELETE ON warehouse_work_event FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54/V56/V59/V62 同口径）。
REVOKE ALL ON warehouse_work_task, warehouse_work_event FROM starcon_app;