-- 10.4.1.2 在途跟踪。
--
-- 蓝图 10-logistics-and-freight.md 10.4.1.2 原文：
--   职责：跟踪头程在途：位置、预计到港、延误预警、清关状态、目的仓收货确认，节点异常主动告警。
--   输入：货代/船司/清关状态同步；人工更新。
--   输出：在途看板 + 延误预警 + 到港提醒。
--   关系：上游 = 10.4.1.1；下游 = 7.5.1.2 在途库存、6.2.1.1 补货（缺口计算）。
--         **头程延误是断货的主要外因**，预警必须提前到预计到港前若干天。
--
-- ===========================================================================
-- 一、表名刻意**不含 transit** 子串：7.5.1.2 有一条跨车道回归断言在扫它
-- ===========================================================================
-- InventoryInTransitIntegrationTest#在途只有一个事实表_外部腿与调拨腿同表 里写着：
--   select table_name from information_schema.tables
--    where table_schema='public' and table_name like '%transit%' ...
--   断言结果恰好是 inventory_transfer 与 in_transit_event 两张。
-- 任何新表名里带 transit 都会让**那条属于别的车道**的测试当场变红。
-- 所以本迁移的四张表一律用 tracking / alert / threshold 命名：
--   first_mile_tracking / first_mile_tracking_event
--   first_mile_delay_alert / first_mile_delay_threshold
-- 名字说的是**跟踪与预警**而不是「在途账」，与本模块的职责本来也对齐。
--
-- ===========================================================================
-- 二、本迁移**不建第二张在途账** —— 在途余量一律从 7.5.1.2 读
-- ===========================================================================
-- 在途腿（inventory_transfer 行）与在途事件（in_transit_event 行）的写入所有者是
-- 7.5.1.2，本模块**只读**。first_mile_tracking 里没有任何数量余额列：
-- 「发运 100、已到 60、还剩 40」这三个数全部由 service 层 join inventory_transfer
-- 现算后随响应返回。本表只记**跟踪维度的事实**（位置、清关状态、预计到港、
-- 人工与外部同步的节点事件），这两类事实 inventory_transfer 上根本没有，
-- 复刻它们才是缺口而不是重复。
--
-- 对 first_mile_shipment / first_mile_shipment_line 建**复合外键**（同服务，V80 的表）：
-- 保证跟踪记录永远挂在真实存在、且与本租户一致的发运单上。
-- 对 inventory_transfer **不建外键**：7.5.1.2 的集成测试里 truncate inventory_transfer
-- 不带本表（也不该带），建了 FK 会让那条 truncate 因外键依赖直接失败。
-- 与 V80 第三节同一条纪律。
--
-- ===========================================================================
-- 三、外部同步**未接入**：靠 source_system + reference_id + 事件流如实承载
-- ===========================================================================
-- 蓝图输入写的是「货代/船司/清关状态同步」，但 10.4.2.1 海外仓连接与货代/船司连接器
-- **均未交付**（台账记为未开发），没有可调的真实连接器。
-- 因此本迁移：
--   * **不建任何「同步任务」表**（没有调度器就没有同步任务，建一张表只会让人
--     以为已经有东西在跑了 —— 那比缺功能严重得多）；
--   * 每条外部事实都必须带 source_system（FORWARDER / CARRIER / CUSTOMS_BROKER /
--     MANUAL）与 reference_id（该来源系统里的单号），没有来源的事实一条都不许写；
--   * 状态推进一律经 guard_first_mile_tracking_source 钉死来源自洽。
-- 将来 10.4.2.1 交付后，连接器只需按同一形状往 first_mile_tracking_event 写，
-- 本表结构不用再改。
--
-- ===========================================================================
-- 四、预警阈值**落库可配置**，不写死在代码里
-- ===========================================================================
-- 蓝图关系段原文：「预警必须提前到预计到港前若干天」——
-- 「若干」是多少是**业务口径**（不同运输方式、不同目的国缓冲期不同），
-- 写进 if 分支就再也改不动了。所以 first_mile_delay_threshold 是**租户级真实表**：
-- 服务层按 (运输方式, 目的国) 取优先级最高的一条阈值行算分级，取不到才回落到
-- 模块默认行（default 行也在库里，同样可改，不是 Java 常量）。
--
-- 分级口径（剩余窗口 = 预计到港 − 现在，按阈值表逐级判定，见 service）：
--   CRITICAL   剩余窗口 < 0 且已超期，或剩余窗口 ≤ critical_lead_days 内的最紧一档
--   WARNING    剩余窗口 ≤ warning_lead_days（提前进入观察区）
--   WATCH      剩余窗口 ≤ watch_lead_days（提前进入提示区）
--   ON_TRACK   以上都不满足
-- 三档提前量全部来自阈值表行，不是代码里的 if。
--
-- ===========================================================================
-- 五、数量与时间
-- ===========================================================================
-- 数量一律 numeric(19,6)（沿用 8.2.1.1 / V80 头程口径），全程 BigDecimal 禁浮点。
-- 时间一律 timestamptz，服务层用 OffsetDateTime 绑定并在 values 位置写 ?::timestamptz。

-- ---------------------------------------------------------------------------
-- 1. 在途跟踪头：一条发运单一行，跟踪维度的事实都挂在这里
-- ---------------------------------------------------------------------------
-- **一条发运单一行**，不按明细行拆：蓝图问的是「这批货现在在哪、什么时候到」，
-- 那是**整张发运单**的问题（一个柜/一个航班承载全部明细行），拆到行上会让
-- 「这批货到了吗」需要先聚合 N 行才能回答，而 N 行还可能给出互相矛盾的答案。
-- 明细行的真实在途余量由 7.5.1.2 的 inventory_transfer 按 leg 持有，
-- service 层在详情里逐腿带出来，不在本表复制。
--
-- status 状态机与 tracking_stage 位置状态机是**两个不同的维度**，刻意分开：
--   status         = 这条跟踪记录的生命周期（TRACKING → CLOSED，含 CANCELLED 终态）
--   tracking_stage = 这批货走到哪了（AT_ORIGIN → ... → RECEIVED / EXCEPTION）
-- 混成一个字段就会出现「已取消」既像位置又像状态、无法表达
-- 「货已取消但清关还在异常中」这类真实组合。
CREATE TABLE first_mile_tracking (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  shipment_id uuid NOT NULL,
  -- ===== 位置（蓝图的第一个职责项）=====
  -- 自由文本位置快照：海运问船名航段、空运问航班与中转场、快递问扫描点，
  -- 各家没有统一的位置编码，接连接器之前由人工/货代报这里。
  current_location varchar(200) NOT NULL DEFAULT '',
  location_occurred_at timestamptz,
  -- ===== 预计到港（预警的计算依据，不是人工标记）=====
  -- original_eta 是建跟踪时从 first_mile_shipment.expected_arrive_at 抄下来的**快照**：
  -- 货代改期后新 ETA 写 revised_eta，原 ETA 仍然留着，
  -- 「这批货从哪天开始就在延误」才答得出来（否则改期会把延误历史抹平）。
  original_eta timestamptz,
  revised_eta timestamptz,
  eta_revised_count integer NOT NULL DEFAULT 0 CHECK(eta_revised_count>=0),
  -- ===== 清关状态（蓝图的第三个职责项）=====
  customs_status varchar(16) NOT NULL DEFAULT 'NOT_STARTED' CHECK(customs_status IN (
    'NOT_STARTED','DECLARED','UNDER_REVIEW','CLEARED','HELD','REJECTED')),
  customs_status_at timestamptz,
  customs_hold_reason varchar(200) NOT NULL DEFAULT '',
  -- ===== 目的仓收货确认（蓝图的第四个职责项）=====
  -- **只记「目的仓确认收到了」这个事实，不写任何库存**：
  -- 入库落账归 7.5.1.2 的 arrive，本表越界去写会在途与在手余额出现第二个写入所有者。
  -- 实际到货数量始终从 inventory_transfer / in_transit_event 读，不在本表复制一份。
  receipt_confirmed_at timestamptz,
  receipt_confirmed_by uuid,
  receipt_reference varchar(64) NOT NULL DEFAULT '',
  -- ===== 生命周期状态 =====
  status varchar(16) NOT NULL DEFAULT 'TRACKING' CHECK(status IN (
    'TRACKING','CLOSED','CANCELLED')),
  closed_at timestamptz,
  closed_by uuid,
  -- ===== 跟踪位置状态机（与 status 分开，见文件头第五节）=====
  tracking_stage varchar(16) NOT NULL DEFAULT 'AT_ORIGIN' CHECK(tracking_stage IN (
    'AT_ORIGIN','IN_TRANSIT','ARRIVED_ORIGIN_PORT','IN_CUSTOMS','CUSTOMS_CLEARED',
    'INLAND_TO_WAREHOUSE','RECEIVED','EXCEPTION')),
  version bigint NOT NULL DEFAULT 0,
  notes text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一张发运单只有一条跟踪记录：重复建跟踪会让「这批货在哪」出现两个答案。
  UNIQUE(tenant_id,shipment_id),
  FOREIGN KEY(tenant_id,shipment_id) REFERENCES first_mile_shipment(tenant_id,id),
  -- 改期后的新 ETA 不能早于原 ETA：把 ETA 往前调会让「延误了几天」算成负数，
  -- 而「这批货已经晚了几天」正是本模块最需要答对的那个数。
  CHECK(revised_eta IS NULL OR original_eta IS NULL OR revised_eta>=original_eta),
  -- 扣关与退单必须给原因：这两个状态意味着货被卡住了，
  -- 没有原因的「扣关」事后没人知道该找谁、该补什么材料。
  CHECK(customs_status NOT IN ('HELD','REJECTED') OR btrim(customs_hold_reason)<>''),
  -- 清关状态有取值就一定有取值时间：「什么时候变成已放行」是问责要用的。
  CHECK(customs_status='NOT_STARTED' OR customs_status_at IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 2. 节点事件流（外部同步与人工更新的**共同承载**，一次事实变动 = 一行）
-- ---------------------------------------------------------------------------
-- 蓝图输入写「货代/船司/清关状态同步；人工更新」——两种来源的形状是一样的
-- （都是「某时刻、某来源、货到了某个状态」），所以落同一张事件表，
-- 用 source_system 区分来源，而不是造两张形状雷同的表。
--
-- 之所以要事件流而不只是当前值：头程延误是断货的主要外因，
-- 事后复盘要回答的是「哪一天、哪个来源说会晚、我们提前几天收到预警」，
-- 只留当前 ETA 就把这三问全部答不出来。
--
-- UNIQUE(tenant_id,source_system,reference_id,kind)：同一来源同一单号同一类节点
-- 重复推送撞唯一约束而不是重复入库 —— 外部系统重推是常态，不是异常。
CREATE TABLE first_mile_tracking_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  tracking_id uuid NOT NULL,
  kind varchar(24) NOT NULL CHECK(kind IN (
    'CREATED','LOCATION','ETA_REVISED','CUSTOMS','STAGE','RECEIPT','ALERT','NOTE')),
  -- 本次事件之后的位置阶段：STAGE 类事件必填，其余可空
  -- （「货代报了个位置」不必然推进阶段，ETA 改期也不动阶段）。
  to_stage varchar(16),
  from_stage varchar(16),
  -- 事件正文：位置描述、改期理由、清关原因都在这里，不另开自由列。
  location_text varchar(200) NOT NULL DEFAULT '',
  detail text NOT NULL DEFAULT '',
  eta_before timestamptz,
  eta_after timestamptz,
  -- ===== 外部来源（见文件头第三节）=====
  source_system varchar(24) NOT NULL CHECK(source_system IN (
    'MANUAL','FORWARDER','CARRIER','CUSTOMS_BROKER')),
  -- 外部来源的单号（船司的 booking 号、清关行的报关单号）。
  reference_id varchar(64) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,
  actor_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,tracking_id) REFERENCES first_mile_tracking(tenant_id,id),
  -- 外部来源必须给单号：MANUAL 是人工（actor 必填），
  -- 其余三种外部来源（连接器**未交付**，当前由人工代录）必须有 reference_id，
  -- 否则「这条位置是谁报的」永远答不出来，而外部事实没有出处就等于编造。
  CHECK(source_system='MANUAL' OR btrim(reference_id)<>''),
  CHECK(source_system<>'MANUAL' OR actor_id IS NOT NULL),
  -- STAGE 事件必须同时给出前后阶段：没有前后值的「阶段变更」无法审计。
  CHECK(kind<>'STAGE' OR (from_stage IS NOT NULL AND to_stage IS NOT NULL)),
  -- 改期事件必须给出改前与改后 ETA，且改后不得早于改前。
  CHECK(kind<>'ETA_REVISED' OR (eta_before IS NOT NULL AND eta_after IS NOT NULL
    AND eta_after>=eta_before))
);
CREATE INDEX first_mile_tracking_event_feed ON first_mile_tracking_event(tenant_id,tracking_id,occurred_at DESC);
CREATE INDEX first_mile_tracking_event_dedupe ON first_mile_tracking_event(tenant_id,source_system,reference_id,kind)
  WHERE btrim(reference_id)<>'';

-- ---------------------------------------------------------------------------
-- 3. 延误预警（蓝图输出「延误预警 + 到港提醒」）
-- ---------------------------------------------------------------------------
-- 一行 = 一次「算出来的预警」。**不是**在 tracking 上挂一个 level 就完事：
-- 预警要能逐条 ack / close、要能回答「这次延误我们什么时候知道的」，
-- 挂一个字段就只剩「现在是延误」这一个瞬间，预警历史全部丢失。
--
-- **预警由服务层按阈值表现算并落库**，不做定时任务（常驻调度未接入，
-- 见文件头第三节），所以本表既有「已落库的预警行」也有「本次评估窗口内」的语义：
-- 评估接口负责幂等刷新窗口内的行。
CREATE TABLE first_mile_delay_alert (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  tracking_id uuid NOT NULL,
  level varchar(16) NOT NULL CHECK(level IN ('WATCH','WARNING','CRITICAL')),
  -- 触发口径落库：这次预警是按哪条阈值行算出来的。
  -- 记下来才能解释「为什么昨天是 WARNING 今天是 CRITICAL」——
  -- 阈值被改过以后，光看 level 是复现不出当时判断的。
  threshold_id uuid,
  lead_days integer NOT NULL DEFAULT 0,
  -- 剩余窗口（天）：负数 = 已经过了预计到港还没到货。
  remaining_days numeric(9,2) NOT NULL DEFAULT 0,
  message varchar(300) NOT NULL DEFAULT '',
  -- 一张跟踪记录同一档位同时只允许一条**未结**预警：
  -- 否则每次评估都插一行，看板上同一个延误会堆出十几条一模一样的告警。
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN (
    'OPEN','ACKNOWLEDGED','CLOSED')),
  raised_at timestamptz NOT NULL DEFAULT now(),
  raised_for timestamptz NOT NULL,
  acknowledged_at timestamptz,
  acknowledged_by uuid,
  closed_at timestamptz,
  closed_by uuid,
  close_reason varchar(200) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,tracking_id) REFERENCES first_mile_tracking(tenant_id,id),
  CHECK(closed_at IS NULL OR status='CLOSED'),
  CHECK(status='CLOSED' OR closed_at IS NULL),
  CHECK(acknowledged_at IS NULL OR status IN ('ACKNOWLEDGED','CLOSED'))
);
-- 未结预警只查「有没有人在处理」，所以按 status 前置过滤建部分索引。
CREATE INDEX first_mile_delay_alert_open ON first_mile_delay_alert(tenant_id,status,raised_at DESC)
  WHERE status IN ('OPEN','ACKNOWLEDGED');
CREATE INDEX first_mile_delay_alert_tracking ON first_mile_delay_alert(tenant_id,tracking_id,raised_at DESC);
-- 「一张跟踪同一档位最多一条未结」的判据落成部分唯一索引，DB 级兜住重复插入。
CREATE UNIQUE INDEX first_mile_delay_alert_one_open ON first_mile_delay_alert(tenant_id,tracking_id,level)
  WHERE status IN ('OPEN','ACKNOWLEDGED');

-- ---------------------------------------------------------------------------
-- 4. 预警阈值（租户级真实表，见文件头第四节）
-- ---------------------------------------------------------------------------
-- 「预警必须提前到预计到港前若干天」——「若干」是业务口径，所以它必须可改、可审、可存。
--
-- 三级优先匹配，越具体越优先（服务层的取法）：
--   (transport_mode='SEA', country='US')   >   (transport_mode='SEA', country='*')
--   >   (*, *)                              >   模块默认行
-- country 用 '*' 表示「不限国家」，与 17.5.1.1 业务参数的自由键同一条约定。
--
-- 三档提前量都是 NOT NULL 的真实列：默认值来自 DEFAULT 行（也在库里，不是 Java 常量），
-- 运营改一行就换口径，不用发版。
CREATE TABLE first_mile_delay_threshold (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  transport_mode varchar(16) NOT NULL DEFAULT '*'
    CHECK(transport_mode IN ('*','SEA','AIR','RAIL','EXPRESS')),
  country_code varchar(2) NOT NULL DEFAULT '*',
  watch_lead_days integer NOT NULL DEFAULT 7 CHECK(watch_lead_days>=0),
  warning_lead_days integer NOT NULL DEFAULT 3 CHECK(warning_lead_days>=0),
  critical_lead_days integer NOT NULL DEFAULT 0 CHECK(critical_lead_days>=0),
  -- 三档必须递减：warning 提前量小于 watch 才说得通。
  -- 不钉的话 (watch=3, warning=7) 会让「进观察区」反而比「进提示区」晚，
  -- 而 service 的取法是「谁先满足给谁」，递减性错了分级就会静默倒挂。
  CHECK(warning_lead_days<=watch_lead_days),
  -- 同一 (运输方式, 国家) 只能有一条：两条并存时「取哪条」取决于查询顺序，
  -- 同样的输入在不同时候算出不同分级，这是配置表最典型的坑。
  UNIQUE(tenant_id,transport_mode,country_code),
  CHECK(country_code='*' OR length(btrim(country_code))=2),
  notes varchar(200) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(updated_at>=created_at)
);
CREATE INDEX first_mile_delay_threshold_lookup
  ON first_mile_delay_threshold(tenant_id,transport_mode,country_code);

-- ---------------------------------------------------------------------------
-- 5. DB 级不变量
-- ---------------------------------------------------------------------------
-- 5.1 状态与时间戳自洽（照抄 V80 的 guard_first_mile_state 纪律）：
--     CLOSED 与 CANCELLED 必须带关闭时间且不可离开；TRACKING 不允许带关闭痕迹。
CREATE FUNCTION guard_first_mile_tracking_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.status = 'CLOSED') <> (NEW.closed_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与关闭时间不自洽：只有已关闭才可能带关闭时间', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'CLOSED'
    AND (NEW.closed_at IS NOT NULL OR NEW.closed_by IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」不是已关闭，不允许带关闭痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.status = 'CANCELLED' AND NEW.tracking_stage = 'RECEIVED' THEN
  RAISE EXCEPTION '已取消的跟踪记录不允许处于「已收货」阶段' USING ERRCODE='23514';
 END IF;
 -- 终态不许离开（只拦离开，不拦进入：TRACKING → CLOSED 必须放行）。
 IF TG_OP = 'UPDATE' AND OLD.status IN ('CLOSED','CANCELLED') AND NEW.status <> OLD.status THEN
  RAISE EXCEPTION '「%」是终态：% ，不允许改到「%」', OLD.status,
   CASE OLD.status WHEN 'CLOSED' THEN '货已闭环，后续变化不再跟踪'
    ELSE '已取消的跟踪记录不再接受任何动作' END,
   NEW.status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_tracking_state BEFORE INSERT OR UPDATE ON first_mile_tracking
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_tracking_state();

-- 5.2 跟踪记录必须挂在**真实存在且属于本租户**的发运单上。
--     这是本表唯一一条跨表 DB 约束：发运单是本服务的表（V80），允许建复合外键；
--     而对 inventory_transfer 故意不建（文件头第二节）。
CREATE FUNCTION guard_first_mile_tracking_shipment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record;
BEGIN
 SELECT status, expected_arrive_at INTO head FROM first_mile_shipment
  WHERE tenant_id = NEW.tenant_id AND id = NEW.shipment_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '跟踪记录指向的发运单 % 不存在或不属于本租户', NEW.shipment_id
   USING ERRCODE='23503';
 END IF;
 -- **只有已发出的发运单才谈得上「在途」**：DRAFT/CONFIRMED 阶段货还没走，
 -- 给它建跟踪记录会让看板把一堆还没发的单子混进在途里。
 IF head.status <> 'DISPATCHED' THEN
  RAISE EXCEPTION '发运单当前状态是「%」，货还没发出，此时建立跟踪记录会让未发运的单混进在途看板', head.status
   USING ERRCODE='23514';
 END IF;
 -- 建跟踪那一刻把 ETA 抄成 original_eta（快照）：之后货代改期写 revised_eta，
 -- 原值留着，「从哪天开始就在延误」才答得出来。
 IF NEW.original_eta IS NULL THEN
  NEW.original_eta := head.expected_arrive_at;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_tracking_shipment BEFORE INSERT ON first_mile_tracking
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_tracking_shipment();

-- 5.3 位置与阶段必须一起有值：报了位置却没推进阶段（或反之）
--     会让看板出现「货在新加坡」但阶段还停在 AT_ORIGIN 的自相矛盾行。
CREATE FUNCTION guard_first_mile_tracking_stage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF btrim(NEW.current_location)<>'' AND NEW.location_occurred_at IS NULL THEN
  RAISE EXCEPTION '报了位置却没有位置发生时间：「%」是什么时候的事说不出来', NEW.current_location
   USING ERRCODE='23514';
 END IF;
 IF NEW.tracking_stage = 'RECEIVED' AND NEW.receipt_confirmed_at IS NULL THEN
  RAISE EXCEPTION '阶段是「已收货」必须有目的仓收货确认时间' USING ERRCODE='23514';
 END IF;
 IF NEW.receipt_confirmed_at IS NOT NULL AND NEW.receipt_confirmed_by IS NULL THEN
  RAISE EXCEPTION '目的仓收货确认必须记确认人' USING ERRCODE='23514';
 END IF;
 -- ETA 改期次数与改期痕迹必须自洽。
 IF (NEW.eta_revised_count > 0) <> (NEW.revised_eta IS NOT NULL) THEN
  RAISE EXCEPTION '改期次数「%」与改期后 ETA 不自洽', NEW.eta_revised_count
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_tracking_stage BEFORE INSERT OR UPDATE ON first_mile_tracking
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_tracking_stage();

-- 5.4 事件来源必须自洽（见文件头第三节）：
--     外部来源必须有单号，人工来源必须有人。
CREATE FUNCTION guard_first_mile_tracking_event_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record;
BEGIN
 SELECT status INTO head FROM first_mile_tracking
  WHERE tenant_id = NEW.tenant_id AND id = NEW.tracking_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '事件指向的跟踪记录 % 不存在', NEW.tracking_id USING ERRCODE='23503';
 END IF;
 -- 已关闭的跟踪记录只允许追加只读节点事件（NOTE / ALERT 类），
 -- 不允许再改位置或阶段：货都闭环了还在写「现在在东京」是脏数据。
 IF head.status = 'CLOSED' AND NEW.kind NOT IN ('NOTE','ALERT') THEN
  RAISE EXCEPTION '跟踪记录已闭环，不再接受「%」类节点更新（只能追加备注与预警）', NEW.kind
   USING ERRCODE='23514';
 END IF;
 -- STAGE 事件的前后阶段不许倒退：跟踪是**推进**过程，
 -- 倒退会让「这批货到过哪」永远答不出来。
 IF NEW.from_stage IS NOT NULL AND NEW.to_stage IS NOT NULL THEN
  IF stage_rank(NEW.to_stage) < stage_rank(NEW.from_stage) THEN
   RAISE EXCEPTION '跟踪阶段不允许从「%」倒退到「%」：在途是推进过程，倒退会让「到过哪」无法回答',
    NEW.from_stage, NEW.to_stage USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
-- 阶段序号集中在一处，避免 if/case 与 CHECK 各写一份顺序而互相漂移。
CREATE FUNCTION stage_rank(stage varchar) RETURNS integer LANGUAGE sql IMMUTABLE AS $$
 SELECT CASE stage
  WHEN 'AT_ORIGIN' THEN 1 WHEN 'IN_TRANSIT' THEN 2
  WHEN 'ARRIVED_ORIGIN_PORT' THEN 3 WHEN 'IN_CUSTOMS' THEN 4
  WHEN 'CUSTOMS_CLEARED' THEN 5 WHEN 'INLAND_TO_WAREHOUSE' THEN 6
  WHEN 'RECEIVED' THEN 7 WHEN 'EXCEPTION' THEN 8
  ELSE 0 END;
$$;
CREATE TRIGGER first_mile_tracking_event_source BEFORE INSERT ON first_mile_tracking_event
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_tracking_event_source();

-- 5.5 预警必须挂在真实存在的跟踪记录上，且阈值行必须属于本租户
--     （逻辑引用不建外键到阈值表：阈值行可能被运营删掉重建，
--     预警必须保住「当时按哪条阈值算的」这条线索，不能因为删配置就丢历史）。
CREATE FUNCTION guard_first_mile_delay_alert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; thr record;
BEGIN
 SELECT status, tracking_stage INTO head FROM first_mile_tracking
  WHERE tenant_id = NEW.tenant_id AND id = NEW.tracking_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '预警指向的跟踪记录 % 不存在', NEW.tracking_id USING ERRCODE='23503';
 END IF;
 -- 已闭环 / 已取消的跟踪不再产生新预警：货都到了或单都取消了还报延误是噪音。
 IF head.status <> 'TRACKING' THEN
  RAISE EXCEPTION '跟踪记录状态是「%」，不再产生新预警', head.status USING ERRCODE='23514';
 END IF;
 IF head.tracking_stage = 'RECEIVED' THEN
  RAISE EXCEPTION '货已收货确认，不再产生延误预警' USING ERRCODE='23514';
 END IF;
 IF NEW.threshold_id IS NOT NULL THEN
  SELECT id INTO thr FROM first_mile_delay_threshold
   WHERE tenant_id = NEW.tenant_id AND id = NEW.threshold_id;
  IF thr IS NULL THEN
   RAISE EXCEPTION '预警引用的阈值 % 不存在或不属于本租户', NEW.threshold_id USING ERRCODE='23503';
  END IF;
 END IF;
 -- 剩余窗口必须与 raised_for（触发时的预计到港）自洽：
 -- 预警是「按触发那一刻的口径」算的，raised_for 之后 ETA 可能被改过。
 RETURN NEW;
END $$;
CREATE TRIGGER first_mile_delay_alert BEFORE INSERT OR UPDATE ON first_mile_delay_alert
 FOR EACH ROW EXECUTE FUNCTION guard_first_mile_delay_alert();

-- ---------------------------------------------------------------------------
-- 6. 热读路径索引（围绕「在途看板」与「未结预警」两条真实查询）
-- ---------------------------------------------------------------------------
-- 看板的主查询是「按阶段 + 剩余窗口排序列出在途中」，所以 stage 与 ETA 都在索引里。
CREATE INDEX first_mile_tracking_board ON first_mile_tracking(tenant_id,tracking_stage,updated_at DESC);
CREATE INDEX first_mile_tracking_shipment ON first_mile_tracking(tenant_id,shipment_id);
-- ETA 预警的热路径：只扫还在途且有 ETA 的行。
CREATE INDEX first_mile_tracking_eta ON first_mile_tracking(tenant_id,coalesce(revised_eta,original_eta))
  WHERE status='TRACKING';
-- 阶段推进的真实查询是「这张发运单最近一次位置事件是什么时候」。
CREATE INDEX first_mile_tracking_event_stage ON first_mile_tracking_event(tenant_id,tracking_id,kind,occurred_at DESC)
  WHERE kind IN ('LOCATION','STAGE','ETA_REVISED','CUSTOMS');

-- ---------------------------------------------------------------------------
-- 7. 多租户：RLS + 授权（与 V1 第 9 节、V63 第 5 节、V70 第 10 节、V80 第 7 节同一条纪律）
-- ---------------------------------------------------------------------------
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'first_mile_tracking','first_mile_tracking_event',
   'first_mile_delay_alert','first_mile_delay_threshold'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON first_mile_tracking, first_mile_tracking_event,
  first_mile_delay_alert, first_mile_delay_threshold TO warehouse_app;
-- 本迁移不建序列（跟踪记录 id 由应用生成 UUID，与 V80 的发号器不同），
-- 因此没有 sequence 的 GRANT —— 漏授会报 permission denied for sequence。
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V63、V70、V80 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;