-- ===========================================================================
-- 9.5.2.1 履约质量
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md）：
--   职责：统计履约质量：发货及时率、完整发货率、错发漏发率、取消率、纠纷率、退货率，
--         按店铺与人员维度对比。
--   输入：订单履约与售后数据；平台绩效指标同步。
--   输出：质量指标卡 + 明细下钻。
--   关系：上游 = 9.4、11 售后；下游 = 17 绩效、16 账号健康。**纠纷率与订单缺陷率
--         直接关联账号安全**，需要与 16 联动预警。
--
-- ---------------------------------------------------------------------------
-- 一、本模块是**读模型**：不推进任何状态机、不回传平台、不改一张既有表
-- ---------------------------------------------------------------------------
-- 履约质量与同 L3 的 9.5.1.1 时效分析是同一类模块：它回答「我们做得怎么样」，
-- 而不回答「这票货现在是什么状态」。因此本迁移：
--   * **只 CREATE 表 / 索引 / 触发器，零 ALTER 任何既有表**；
--   * 对既有表的引用**全部是只读 join**（sales_order / sales_order_line /
--     order_fulfillment_line_progress / order_cancellation_request /
--     order_timeliness_fact / shipment_record / fulfillment_request_*）：
--     无 FK、无触发器、无写入、不 ALTER 对方表；
--   * 所有 FK 只指向**本迁移自己新建的** fulfillment_quality_* 表
--     （以及同服务 sales_order 这一张订单主表，见第五节说明）；
--   * 不碰 warehouse / 售后服务的任何一张表。
-- 跨车道 DDL 回归面 = 0。
--
-- ---------------------------------------------------------------------------
-- 二、六个指标的分子/分母（**口径只有这一处定义**，服务层 SQL 与端点文档逐字引用）
-- ---------------------------------------------------------------------------
-- 比率一律是**百分数**（numeric(19,6)，0~100 量纲），**分母为 0 时返回 NULL**，
-- 绝不返回 0 也不返回 1：分母 0 的意思是「这个窗口里没有任何可判定样本」，
-- 报成 0 会被读成「及时率 0%」—— 那是结论，不是缺口。
--
-- (1) 发货及时率 ship_on_time_rate
--     分子 = order_timeliness_fact 中 ship_sla_state = 'ON_TIME' 的票数
--     分母 = 'ON_TIME' + 'OVERDUE'（**不含 'UNSET'**）
--     时钟 = handed_over_at（交接时刻）
--     为什么不自己造口径：9.5.1.1 已经把「发货时限」冻结成快照
--     （sla_deadline_at / ship_overdue_seconds），本模块**只读同一张表**，
--     不另起一套时限口径 —— 同一件事两个算法必然对不上。
--     UNSET（时限未登记）不进分母，但**独立报数**：把没时限的当准时，
--     及时率会系统性偏高。
--
-- (2) 完整发货率 complete_shipment_rate
--     分子 = 有履约进度投影且 shipped_qty >= ordered_qty 的**订单行**数
--     分母 = 有履约进度投影的订单行总数（**按行**，不是按单）
--     时钟 = order_fulfillment_line_progress.shipped_at
--     为什么按行：完整发货是「这一行的量发全了没有」，按单统计会把
--     「10 行发全 9 行」和「10 行发全 10 行」压成同一个数。
--     **没有进度投影的订单行不进分母**，独立报数 withoutProgress：
--     9.4 还没同步过这批货的行不是「不完整」，是「没测过」。
--
-- (3) 错发漏发率 wrong_or_missing_rate
--     分子 = 登记过错发/漏发的**去重订单**数
--     分母 = 窗口内有履约进度投影的**去重订单**数
--     时钟（分母）= order_fulfillment_line_progress.shipped_at；
--           时钟（分子）= fulfillment_quality_fulfillment_exception.reported_at
--     **「错发」当前拿不到可读上游，如实报 unavailable 而不是猜**：
--     shipment_record / shipment_record_parcel 只有 item_count 与 total_quantity，
--     **没有 SKU 列**，order_fulfillment_line_progress 又按 order_line_id 建行，
--     所以「发出去的货对不对得上订单行」在集成头里**没有任何数据源**。
--     处理照抄 9.2.2.2（V150）与 9.5.1.1 的做法：错发落成**租户可登记的举证表**
--     （每行强制来源枚举 + 采集时刻 + 主体），11/9.4 正式接口到位后由同步器
--     按同一形状写入并把 reported_source 改成 MODULE_*。表结构与统计逻辑都不用改。
--     「漏发」则**可推导**：shipped_qty < ordered_qty 的行即漏发部分，
--     服务层同时报出 derivedUnderShippedOrders 与 tenantReported，
--     两者分开列、不合并 —— 「系统看出来的漏发」与「客服登记的漏发」
--     举证材料完全不同。
--
-- (4) 取消率 cancellation_rate
--     分子 = 有**已确认**取消申请（status = 'CONFIRMED'）的**去重订单**数
--     分母 = 窗口内已提交（submitted_at 非空）的**去重订单**数
--     时钟（分母）= sales_order.submitted_at；时钟（分子）= decided_at
--     为什么只认 CONFIRMED：REQUESTED / PROCESSING / PENDING_VERIFICATION
--     都还可能被驳回，把申请数当取消数会让取消率虚高一截。
--     REJECTED **不进分子也不进分母之外** —— 它是「申请过但没取消」，
--     与「没申请过」在分母里是同一张已提交的订单，不额外计数。
--
-- (5) 纠纷率 dispute_rate
--     分子 = 有 DISPUTE 信号的**去重订单**数
--     分母 = 窗口内有交接事实（shipment_record）或履约进度的**去重订单**数
--     时钟（分子）= fulfillment_quality_after_sale_signal.occurred_at；
--           时钟（分母）= shipment_record.shipped_at
--     **上游 11 售后在别的服务的独立库里**，AGENTS.md 禁止跨服务 SQL / 视图 / FK，
--     所以当前**读不到真实售后数据**。落成租户可维护信号表（第五节），
--     并在 capabilities 里显式报 crossServiceReadAvailable = false。
--     分母选「已发货订单」而不是「全部订单」：纠纷只可能发生在货已发出的单上，
--     用全部订单当分母会把「还没来得及发货的单」也算进去，系统性压低纠纷率。
--
-- (6) 退货率 return_rate
--     分子 = 有 RETURN 信号的**去重订单**数
--     分母 = 与纠纷率**完全相同**（已发货去重订单数），刻意共用一个分母
--     时钟同上。
--     纠纷与退货**刻意分成两个指标而不是一个「售后率」**：
--     两者在平台后台是两个独立算法权重项，合并之后无法回答
--     「压低账号安全权重的是纠纷还是退货」。
--
-- ---------------------------------------------------------------------------
-- 三、店铺维度当前**只能按平台级 order_source 判**（不假装支持 shop_code）
-- ---------------------------------------------------------------------------
-- 蓝图要求「按店铺与人员维度对比」，但 sales_order 与 shipment_record 上
-- **都没有 shop_code 列**（9.2.1.2 已核实）。本迁移**为此不 ALTER 任何既有表**
-- （跨车道 DDL 回归面必须为 0），处理方式是：
--   * 店铺维度当前落成**平台级**（sales_order.order_source / shipment_record.shop_ref）；
--   * capabilities 里显式报 shopCodeAvailable = false 并点名 ownerModule = D5 平台同步；
--   * D5 落地后由同步器补 shop_code，**表结构与统计逻辑都不用改** ——
--     维度枚举里 SHOP_SOURCE 已经是独立一维，切换取值来源不影响其余五维。
--
-- 人员维度用**真实存在的责任人字段**：fulfillment_request_work_order.assignee_ref
-- （由仓库侧作业系统回填的认领人）经 fulfillment_request.order_ref
-- 关联 sales_order.order_no。assignee_ref 为空的行落**独立空桶**，
-- 不并进任何有值分组 —— 「没认领人的作业」是要单独盯的口子，
-- 混进某个真实人员会让那个人的质量指标凭空变好。
--
-- ---------------------------------------------------------------------------
-- 四、分母为 0 ⇒ NULL（DB 层钉死，不靠服务层自觉）
-- ---------------------------------------------------------------------------
-- 比率**不落表**（是每次查询现算的聚合），所以「分母 0 → NULL」由服务层 SQL
-- 的 CASE 表达并在本文件逐条注释；但**预警阈值判定**（fulfillment_quality_alert_rule）
-- 把这件事钉成 DB 约束：阈值列与 observed 值列都要求
-- observed_value IS NULL **不触发**阈值比较（服务层用 WHERE 过滤），
-- 并且 CHECK 明确「NULL 观察值不许被当成 0 或 100」——
-- 触发器 guard_fulfillment_quality_alert_observed_null 会拒收
-- 「分子分母都没测出来却硬填了一个数」的写入。
--
-- ---------------------------------------------------------------------------
-- 五、跨服务缺口的既有解法（照抄 9.2.2.2 / V150，不新发明）
-- ---------------------------------------------------------------------------
-- 卷 11 售后在**别的服务的独立库**里 ⇒ 纠纷率与退货率当前读不到真实售后数据。
-- 照抄 V150 的 order_risk_signal：落成**租户可维护的信号表**，每行强制
--   * signal_source ∈ ( TENANT_MAINTAINED / MODULE_11_* )
--   * source_reference（非 TENANT_MAINTAINED 时必填）
--   * captured_at NOT NULL（采集时刻）
-- 与硬编码常量的区别在于：**换来源只要改 signal_source 一列**。
-- 正式接口到位后由同步器按同一形状写入，表结构与统计逻辑都不用改。
-- 界面上「无纠纷」**绝不**画成「纠纷率 0%」：没登记就是 unavailable。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. fulfillment_quality_after_sale_signal · 纠纷 / 退货信号（租户可维护）
-- ---------------------------------------------------------------------------
-- 一行 = 一张订单上一次**纠纷或退货**的发生登记。
-- 本服务**不抓取**售后数据（上游在别的库），只登记「有人报了这件事」。
CREATE TABLE fulfillment_quality_after_sale_signal (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 锚点：信号挂在**订单**上，指标按去重订单数聚合 ===
  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL DEFAULT '',
  -- 平台侧的单号（Amazon order id / Shopify name 等）：D5 同步后才有值，
  -- 现在留空。刻意不拿我方 order_no 冒充平台单号。
  platform_order_no varchar(128) NOT NULL DEFAULT '',

  -- 两种信号**分列**而不是合成一个「售后」类型（第二节 (5)(6)）
  signal_type varchar(12) NOT NULL CHECK(signal_type IN ('DISPUTE','RETURN')),

  -- 纠纷的当前态。退货只有 OPEN / CLOSED 两态有意义，
  -- 但两者共用一个词表（列封闭）比加一列 null 分支更好读。
  signal_state varchar(12) NOT NULL DEFAULT 'OPEN' CHECK(signal_state IN
    ('OPEN','UNDER_REVIEW','RESOLVED','REJECTED','CLOSED')),

  -- 事件发生时刻 —— 指标分子按这一列圈窗口，不是按登记时刻。
  -- 「纠纷三天前发生、五天前才被录进来」时，两个时刻圈出来的窗口完全不同。
  occurred_at timestamptz NOT NULL,

  -- === 四件套：主体 / 来源 / 采集时刻 / 引用（照抄 V150）===
  signal_source varchar(32) NOT NULL CHECK(signal_source IN
    ('TENANT_MAINTAINED','MODULE_11_DISPUTE','MODULE_11_RETURN')),
  -- 非租户自维护的来源必须给出外部引用号：
  -- 「从平台同步来的纠纷」没有平台单号，事后无法追这条信号是怎么来的。
  source_reference varchar(128) NOT NULL DEFAULT '',
  captured_at timestamptz NOT NULL,
  reported_by_membership_id uuid NOT NULL,
  note varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 幂等键：同一订单同一类型同一发生时刻只允许一条 ——
  -- 同步器重放不会把纠纷率的分母之外多插一行。
  UNIQUE(tenant_id,order_id,signal_type,occurred_at),
  -- 同服务订单主表：允许建 FK（不是跨服务）。
  -- 订单被删则信号失去意义（争议挂在不存在的单上答不出来），级联删除。
  FOREIGN KEY(tenant_id,order_id)
    REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE,

  CONSTRAINT fulfillment_quality_signal_source_ref
    CHECK (signal_source='TENANT_MAINTAINED' OR btrim(source_reference)<>''),
  -- 采集时刻不许早于发生时刻：事后追责时「先采集后发生」是数据错误。
  CONSTRAINT fulfillment_quality_signal_capture_order
    CHECK (captured_at >= occurred_at)
);

-- 指标分子：按发生时刻圈窗口后按订单去重
CREATE INDEX fulfillment_quality_signal_type_time
  ON fulfillment_quality_after_sale_signal(tenant_id, signal_type, occurred_at);
CREATE INDEX fulfillment_quality_signal_order
  ON fulfillment_quality_after_sale_signal(tenant_id, order_id, signal_type);

-- ---------------------------------------------------------------------------
-- 2. fulfillment_quality_fulfillment_exception · 错发 / 漏发登记（append-only）
-- ---------------------------------------------------------------------------
-- 一行 = 一次**错发或漏发**的登记。这是**举证凭据**：平台申诉与内部追责都要
-- 答得出「这票当时到底发错了还是发少了、谁在什么时候报的」，
-- 所以 append-only —— 要更正就追加一条并写明更正说明，不改上一条。
CREATE TABLE fulfillment_quality_fulfillment_exception (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,
  order_no varchar(64) NOT NULL DEFAULT '',
  -- 整单错发（发错了 SKU 而不是发少了量）时行号可空；
  -- 漏发几乎总是行级的（某一行少发）。
  order_line_id uuid,
  line_no int,
  sku_code varchar(64) NOT NULL DEFAULT '',

  -- 两种缺陷分列（第二节 (3)）：错发是「发的是别的东西」，漏发是「少发了」，
  -- 二者在平台算法里是完全不同的权重项。
  exception_type varchar(14) NOT NULL CHECK(exception_type IN ('WRONG_ITEM','MISSING_ITEM')),
  -- 错发时为「实际发出量」（与应发不同），漏发时为「少发量」。
  -- 可空：有人报「整批发错 SKU」但没核数量时，如实留空而不是填 0。
  affected_qty numeric(19,6) DEFAULT NULL CHECK(affected_qty IS NULL OR affected_qty > 0),

  reported_at timestamptz NOT NULL,
  reported_source varchar(32) NOT NULL CHECK(reported_source IN
    ('TENANT_MAINTAINED','MODULE_9_4_FEEDBACK','MODULE_16_ACCOUNT_HEALTH')),
  source_reference varchar(128) NOT NULL DEFAULT '',
  reported_by_membership_id uuid NOT NULL,
  note varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id)
    REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE,

  CONSTRAINT fulfillment_quality_exception_source_ref
    CHECK (reported_source='TENANT_MAINTAINED' OR btrim(source_reference)<>''),
  -- 给了行号就必须给行 id：只有行号的话，行被删后这条登记就指不到任何东西。
  CONSTRAINT fulfillment_quality_exception_line_pair
    CHECK ((order_line_id IS NULL) = (line_no IS NULL))
);

-- 指标分子：按登记时刻圈窗口
CREATE INDEX fulfillment_quality_exception_time
  ON fulfillment_quality_fulfillment_exception(tenant_id, reported_at);
-- 订单去重聚合
CREATE INDEX fulfillment_quality_exception_order
  ON fulfillment_quality_fulfillment_exception(tenant_id, order_id, exception_type);
-- 错发 / 漏发两桶分列，不合并
CREATE INDEX fulfillment_quality_exception_type
  ON fulfillment_quality_fulfillment_exception(tenant_id, exception_type);

-- ---------------------------------------------------------------------------
-- 3. fulfillment_quality_alert_rule · 预警阈值（租户可配置）
-- ---------------------------------------------------------------------------
-- 蓝图关系句：「纠纷率与订单缺陷率**直接关联账号安全**，需要与 16 联动预警」。
-- 16 账号健康与 17 绩效**均未交付** ⇒ 本表是「把阈值先摆出来」，
-- 不假装已对接 16：阈值可配、可评估、可留痕，接上那天由 16 读本表。
CREATE TABLE fulfillment_quality_alert_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 六个指标各至多一条生效规则（第四节）
  metric_code varchar(28) NOT NULL CHECK(metric_code IN
    ('SHIP_ON_TIME_RATE','COMPLETE_SHIPMENT_RATE','WRONG_OR_MISSING_RATE',
     'CANCELLATION_RATE','DISPUTE_RATE','RETURN_RATE')),
  -- 阈值与观察值**同一量纲**：百分数（0~100），与指标卡一致。
  threshold_value numeric(19,6) NOT NULL CHECK(threshold_value >= 0 AND threshold_value <= 100),
  -- 方向必须显式：及时率是「越高越好」（LT 越限），
  -- 错发率是「越低越好」（GT 越限）。混用方向会让阈值反过来报警。
  comparison varchar(4) NOT NULL CHECK(comparison IN ('GT','GTE','LT','LTE')),
  severity varchar(8) NOT NULL DEFAULT 'WARN' CHECK(severity IN ('INFO','WARN','CRITICAL')),
  enabled boolean NOT NULL DEFAULT true,
  note varchar(400) NOT NULL DEFAULT '',

  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个指标一条规则：两条规则同时生效时「到底按哪条报警」没有答案
  UNIQUE(tenant_id,metric_code),
  CONSTRAINT fulfillment_quality_alert_rule_updated
    CHECK (updated_at >= created_at)
);

-- ---------------------------------------------------------------------------
-- 4. fulfillment_quality_alert · 预警记录（append-only）
-- ---------------------------------------------------------------------------
-- 一行 = 「某个窗口、某个维度下，某个指标越过了某条阈值」的留痕。
-- append-only，且**确认动作不写在本行**（写不进 append-only 表），
-- 而是落一条 fulfillment_quality_event —— 「谁在什么时候确认了这条预警」
-- 与「系统当时观测到多少」是两条独立事实，混在一行就等于允许改历史。
CREATE TABLE fulfillment_quality_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  metric_code varchar(28) NOT NULL CHECK(metric_code IN
    ('SHIP_ON_TIME_RATE','COMPLETE_SHIPMENT_RATE','WRONG_OR_MISSING_RATE',
     'CANCELLATION_RATE','DISPUTE_RATE','RETURN_RATE')),
  -- 观测值与阈值同量纲（百分数）。**分子为 0 的场景下必须是 NULL 而不是 0**
  -- （第四节）：分母为 0 时本模块**不产生预警**，因为「没有样本」不是「指标变差」。
  observed_value numeric(19,6) CHECK(observed_value IS NULL OR observed_value >= 0),
  threshold_value numeric(19,6) NOT NULL,
  comparison varchar(4) NOT NULL CHECK(comparison IN ('GT','GTE','LT','LTE')),
  severity varchar(8) NOT NULL CHECK(severity IN ('INFO','WARN','CRITICAL')),
  -- 越限时该指标的分子与分母一并冻结：不冻结的话，
  -- 事后翻到这条预警就答不出「当时到底是几除以几」。
  numerator bigint NOT NULL CHECK(numerator >= 0),
  denominator bigint NOT NULL CHECK(denominator >= 0),
  window_from timestamptz NOT NULL,
  window_to timestamptz NOT NULL,
  dimension_code varchar(16) NOT NULL DEFAULT '' CHECK(dimension_code IN
    ('','PLATFORM','PERSON')),
  dimension_value varchar(128) NOT NULL DEFAULT '',
  triggered_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,rule_id)
    REFERENCES fulfillment_quality_alert_rule(tenant_id,id) ON DELETE CASCADE,

  CONSTRAINT fulfillment_quality_alert_window_ordered
    CHECK (window_to > window_from),
  -- **没有样本就没有预警**：分母为 0 的观察值只能是 NULL，
  -- 且 NULL 观察值不许带着一个「已越限」的实数阈值出现。
  CONSTRAINT fulfillment_quality_alert_observed_needs_basis
    CHECK ((observed_value IS NULL) = (denominator = 0)),
  -- 幂等：同一规则 + 同一窗口 + 同一维度重复评估不产生第二条预警
  UNIQUE(tenant_id,rule_id,window_from,window_to,dimension_code,dimension_value)
);

CREATE INDEX fulfillment_quality_alert_time
  ON fulfillment_quality_alert(tenant_id, triggered_at desc);
CREATE INDEX fulfillment_quality_alert_metric
  ON fulfillment_quality_alert(tenant_id, metric_code, triggered_at desc);
-- 「还没确认的预警」清单：按指标 + 时刻倒序
CREATE INDEX fulfillment_quality_alert_open
  ON fulfillment_quality_alert(tenant_id, metric_code, triggered_at desc);

-- ---------------------------------------------------------------------------
-- 5. fulfillment_quality_event · 事件流（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE fulfillment_quality_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  seq bigint GENERATED ALWAYS AS IDENTITY,
  -- varchar(40) 而不是 28：词表里最长的 FULFILLMENT_EXCEPTION_REGISTERED 是 31 个字符，
  -- 按 28 截断会在写事件的那一刻炸 —— 而事件是留痕，写不进去等于这次操作没发生过。
  kind varchar(40) NOT NULL CHECK(kind IN
    ('AFTER_SALE_SIGNAL_REGISTERED','FULFILLMENT_EXCEPTION_REGISTERED',
     'ALERT_RULE_UPSERTED','ALERT_TRIGGERED','ALERT_ACKNOWLEDGED')),
  summary varchar(300) NOT NULL DEFAULT '',
  -- 事件挂到订单时给订单 id，挂到预警时给预警 id
  order_id uuid,
  alert_id uuid,
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);

CREATE INDEX fulfillment_quality_event_tenant
  ON fulfillment_quality_event(tenant_id, seq desc);
CREATE INDEX fulfillment_quality_event_order
  ON fulfillment_quality_event(tenant_id, order_id, seq desc);
CREATE INDEX fulfillment_quality_event_alert
  ON fulfillment_quality_event(tenant_id, alert_id, seq desc);

-- ---------------------------------------------------------------------------
-- 6. DB 级闸门
-- ---------------------------------------------------------------------------
-- (a) 举证凭据 append-only：错发/漏发登记、预警记录、事件流连运行角色都不给改删。
CREATE OR REPLACE FUNCTION reject_fulfillment_quality_credential_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'fulfillment_quality 凭据行 append-only，不允许 % （要更正请追加一条）',
    TG_OP USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_fulfillment_quality_exception_append_only
  BEFORE UPDATE OR DELETE ON fulfillment_quality_fulfillment_exception
  FOR EACH ROW EXECUTE FUNCTION reject_fulfillment_quality_credential_mutation();

CREATE TRIGGER guard_fulfillment_quality_alert_append_only
  BEFORE UPDATE OR DELETE ON fulfillment_quality_alert
  FOR EACH ROW EXECUTE FUNCTION reject_fulfillment_quality_credential_mutation();

CREATE TRIGGER guard_fulfillment_quality_event_append_only
  BEFORE UPDATE OR DELETE ON fulfillment_quality_event
  FOR EACH ROW EXECUTE FUNCTION reject_fulfillment_quality_credential_mutation();

-- (b) 第四节：观测值不许脱离分母单填。
--     「分子分母都没测出来，却硬填了一个观察值」是预警体系里最危险的错 ——
--     它会在没有任何样本的情况下向 16 推送一次「指标恶化」。
CREATE OR REPLACE FUNCTION guard_fulfillment_quality_alert_basis() RETURNS trigger AS $$
BEGIN
  IF NEW.denominator = 0 AND NEW.observed_value IS NOT NULL THEN
    RAISE EXCEPTION
      '预警 % 的分母为 0，observed_value 必须是 NULL（没有样本不是指标变差）',
      NEW.id USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.denominator > 0 AND NEW.observed_value IS NULL THEN
    RAISE EXCEPTION
      '预警 % 的分母为 %，必须给出 observed_value',
      NEW.id, NEW.denominator USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_fulfillment_quality_alert_basis_trg
  BEFORE INSERT OR UPDATE ON fulfillment_quality_alert
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_quality_alert_basis();

-- (c) 信号登记不许早于发生时刻。与表上的 CHECK 同源，
--     这里用触发器是为了把「采集时刻」也一并钉住（CHECK 已经在做），
--     保留触发器是为了将来信号表加列时不必记得同步改 CHECK。
CREATE OR REPLACE FUNCTION guard_fulfillment_quality_signal_time() RETURNS trigger AS $$
BEGIN
  IF NEW.captured_at < NEW.occurred_at THEN
    RAISE EXCEPTION '采集时刻 % 早于发生时刻 %（时间倒流）',
      NEW.captured_at, NEW.occurred_at USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_fulfillment_quality_signal_time_trg
  BEFORE INSERT OR UPDATE ON fulfillment_quality_after_sale_signal
  FOR EACH ROW EXECUTE FUNCTION guard_fulfillment_quality_signal_time();

-- ---------------------------------------------------------------------------
-- 7. 多租户与授权（与 trade V1 第 2 节、V131 第 6 节、V150 同一套写法）
-- ---------------------------------------------------------------------------
-- 五张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- 本模块是**统计模块**：聚合查询同样受这层 RLS 保护 ——
-- 「WHERE 里写了 tenant_id 就够了」在本仓库不成立，只有 FORCE RLS 才是兜底。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'fulfillment_quality_after_sale_signal',
    'fulfillment_quality_fulfillment_exception',
    'fulfillment_quality_alert_rule',
    'fulfillment_quality_alert',
    'fulfillment_quality_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_quality_after_sale_signal,
  fulfillment_quality_fulfillment_exception,
  fulfillment_quality_alert_rule,
  fulfillment_quality_alert,
  fulfillment_quality_event TO trade_app;
-- 凭据 / 预警 / 事件三类是举证与留痕：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON
  fulfillment_quality_fulfillment_exception,
  fulfillment_quality_alert,
  fulfillment_quality_event FROM trade_app;
-- 售后信号允许改状态（纠纷从 OPEN 走到 RESOLVED 是正常流转，不是改历史），
-- 但**禁止删除**：删掉一条纠纷信号就等于把一次平台处罚抹掉。
REVOKE DELETE ON fulfillment_quality_after_sale_signal FROM trade_app;
-- 阈值规则可改（调阈值是运营动作）。
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V127 / V131 同口径）。
REVOKE ALL ON
  fulfillment_quality_after_sale_signal,
  fulfillment_quality_fulfillment_exception,
  fulfillment_quality_alert_rule,
  fulfillment_quality_alert,
  fulfillment_quality_event FROM starcon_app;
