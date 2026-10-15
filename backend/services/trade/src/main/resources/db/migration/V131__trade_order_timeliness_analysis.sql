-- ===========================================================================
-- 9.5.1.1 时效分析
-- ===========================================================================
-- 蓝图原文（09-order-and-fulfillment.md）：
--   职责：统计订单时效分布：下单到出库、出库到交接、承诺到货 vs 实际到货，
--         按店铺、仓库、商品、承运商维度拆解。
--   输入：各环节时间戳；承诺时效配置；轨迹实际时间。
--   输出：时效报表 + 分维度对比 + 超时明细。
--   关系：上游 = 9.4.1.2、10 轨迹；下游 = 13 分析、16 合规（绩效指标）。
--         **时效是平台算法权重项**，超时会持续压低自然流量。
--
-- ---------------------------------------------------------------------------
-- 一、本模块只做「统计」，不产生任何履约事实
-- ---------------------------------------------------------------------------
-- 时效分析是**读侧**模块：它不推进任何状态机、不确认交接、不回传平台。
-- 每一个时刻都必须来自**已经落库的事实**，本模块一次都不自己造时刻。
--
-- 三段时长 = 两两相减，两个端点全部来自同服务既有投影：
--
--   段                起                    止                   来源
--   -----------------  --------------------  -------------------  --------------------------
--   下单 → 出库       sales_order.submitted_at  shipment_pending_unit.packed_at
--                                                                   （9.4.1.1 已完成打包）
--   出库 → 交接       shipment_pending_unit.packed_at  shipment_record.shipped_at
--                                                                   （9.4.1.2 交接事实）
--   承诺到货 → 实际   order_timeliness_fact.promised_delivery_at / actual_delivered_at
--                                                                   （本模块**登记**，见第三节）
--
-- 「出库」在 trade 库里的**唯一**真实落点是 shipment_pending_unit.packed_at：
-- 出库单在 warehouse 服务自己的库里，AGENTS.md 禁止跨服务 SQL / FK / 共享视图，
-- 所以本模块**只读 join 同服务的 9.4.1.1 投影**，不碰 warehouse 任何一张表。
-- 9.4.1.1 还没同步过这票货时 packed_at 为空 → 该段落 UNSET 桶，如实报数而不填 0。
--
-- 「下单」取 sales_order.submitted_at（提交时刻）而不是 created_at（建单时刻）：
-- DRAFT 单的 created_at 是有人开始录入了的时刻，把它当下单时刻会让
-- 「下单到出库」凭空多出一段草稿停留时间 —— 那不是履约时效，是人的录入时长。
-- submitted_at 为 NULL（仍是草稿）的单，该段同样落 UNSET 桶。
--
-- ---------------------------------------------------------------------------
-- 二、时长一律用**整数秒**，不用浮点小时
-- ---------------------------------------------------------------------------
-- order_to_pack_seconds / pack_to_handover_seconds / order_to_handover_seconds
-- / ship_overdue_seconds / promise_vs_delivery_seconds 五列全部是
-- GENERATED ALWAYS AS ... STORED 的 bigint，**由 DB 复算**，应用层算错也改不动。
--
-- 刻意不用 numeric 小时、不用 interval、尤其不用浮点：
--   * 「平均 3.47 小时」这种数字一旦参与汇总与阈值判定，误差会逐层放大，
--     而且没人能说清报表和平台后台的 3.5 小时差在哪；
--   * 秒是**所有时效口径的最小公共单位**（平台算法权重项按分钟/秒判定），
--     展示层再除成小时/天不丢信息，存储层保留整数就永不丢精度。
-- 相减用 floor(extract(epoch from (b - a)))：负数时长（时间倒流）不生成值，
-- 落 NULL 而不是生成一个负秒数 —— 负时长是数据问题，不是「极快的履约」。
--
-- 三段时长各自独立生成而不是 a+b 拼：只要有一端缺失，整段就该是 UNSET，
-- 用两段拼会造出「出库缺失但下单到交接有值」的第三种口径。
--
-- ---------------------------------------------------------------------------
-- 三、承诺到货 vs 实际到货：**没有可读上游 ⇒ 登记而不伪造**
-- ---------------------------------------------------------------------------
-- 蓝图输入句点名的「轨迹实际时间」与职责句的「实际到货」，在本车道集成头里
-- **都没有数据源**：
--   * 10.3 物流轨迹未交付 ⇒ 没有任何承运商轨迹数据源；
--   * 自有履约路径上**没有**任何「承诺到货 / 实际到货」列
--     （V99 sales_order 族无、V124 shipment_pending_unit 无、V127
--     shipment_record 无）。V126 的 supplier_promised_ship_at / own_delivered_at
--     属**代发**（9.6.1.3）路径，口径是「供应商段」，拿它冒充自有履约的
--     到货时效等于把两个模块的事实混成一条。
--
-- 处理照抄 9.4.1.2（轨迹/回传）与 9.4.1.1（SLA）已验证的做法：
--   * capabilities 里 TRACKING_ACTUAL_TIME 与 DELIVERY_ACTUAL 两个维度
--     available=false 并点名 ownerModule 10.3.x；
--   * 两个时刻由 POST /facts/{id}/promise 与 /delivery **登记**：
--     有主体（membership）、有登记时刻、有来源（四件套互斥）、有凭据行；
--   * 凭据行 append-only（REVOKE UPDATE/DELETE + 触发器双层），
--     快照列不许脱离凭据行单独改；
--   * **未登记落独立桶，绝不按 0 算**：把没登记当「准时到货」，
--     准时率会凭空变成 100%，而这恰恰是平台算法权重项里最不能错的一个数。
--
-- promise_vs_delivery_state 是**四值**而不是两值：
--   MET            承诺与实际都登记了，且实际 ≤ 承诺
--   LATE           承诺与实际都登记了，且实际 > 承诺
--   NO_PROMISE     承诺到货**没登记**（「漏了承诺」）
--   NO_ACTUAL      实际到货**没登记**（货还在路上 / 没人报签收）
-- NO_PROMISE 与 NO_ACTUAL **绝不合并**：合并之后，
-- 「我们压根没登记承诺」会伪装成「货还在路上」，而这二者在平台申诉里
-- 需要的举证材料完全不同（前者要平台 SLA，后者要轨迹签收）。
--
-- ---------------------------------------------------------------------------
-- 四、发货超时用的是 9.4.1.2 冻结的**发货时限快照**
-- ---------------------------------------------------------------------------
-- 超时明细的判据是 shipment_record.sla_deadline_at（**发货**时限，不是到货承诺）。
-- 本表冗余一份快照，理由与 V127 第三节逐字相同：迟发要按**发货当时**的时限判定，
-- 事后改时限不能让一笔已经迟发的货变成准时。
-- 时限未登记落 ship_sla_state = 'UNSET'，既不算准时也不算超时 ——
-- 把没时限的当准时，迟发率会系统性偏低。
--
-- ---------------------------------------------------------------------------
-- 五、四维拆解：三个在头表、第四个（商品）走同服务只读 join
-- ---------------------------------------------------------------------------
-- 店铺 / 仓库 / 承运商 是**每一票货自身**的属性，冻结在头表上；
-- 商品是**订单行**的属性，一张货票对应多行，所以 SKU 维度是
-- 对同服务 sales_order_line 的**只读 join**（按 sales_order_id）。
--
-- 刻意不把 SKU 冗余进本表：订单行是订单的属性，冻结成「货票的属性」
-- 会在订单改行后出现两套 SKU 口径，而报表要回答的是
-- 「这个 SKU 的货平均多久发出去」，以订单行为准。
--
-- 四维的空值一律成**独立桶**（shop_ref = '' 等），不并进任何有值分组：
-- 「没有店铺归属的货」是一个要单独盯的口子，混进某个真实店铺
-- 会让那个店铺的时效凭空变好。
--
-- ---------------------------------------------------------------------------
-- 六、跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器，零 ALTER 任何既有表**：
--   * 不碰 V99 sales_order 族、不碰 V124 shipment_pending_* 族、
--     不碰 V127 shipment_record_* 族、不碰 V126 代发十八张；
--   * 对 shipment_record / shipment_pending_unit / sales_order /
--     sales_order_line 的引用**全部是只读 join**：
--     无 FK、无触发器、无写入、不 ALTER 对方表；
--   * 所有 FK 只指向**本迁移自己新建的** order_timeliness_* 表；
--   * 不碰 warehouse 服务任何一张表（连 ALTER 都没有，更没有跨库 FK）。
-- 被本迁移约束到的既有模块 = 无（只读引用不算约束），跨车道 DDL 回归面 = 0。
-- 四张表落在 order_timeliness_* 命名空间，与既有 fulfillment_* /
-- shipment_pending_* / shipment_record_* 并列而不混用 ——
-- 「这一票多久发出去」是履约**产出**之后的度量，
-- 与「待发货」「已发货」不是同一个维度。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：TL-yyyyMMdd-000001（时效事实号）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE order_timeliness_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. order_timeliness_fact · 时效事实头（一票货一行）
-- ---------------------------------------------------------------------------
-- 一行 = 一次**已确认交运**（9.4.1.2 的 shipment_record）的时效切片。
-- 唯一写入口是 POST /trade/fulfillment/timeliness/sync，
-- 按 source_record_id 幂等 upsert —— 时效统计的分母被翻倍就当场失真。
CREATE TABLE order_timeliness_fact (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- TL-yyyyMMdd-000001，由 DB 发（并发同步不撞号）
  fact_no varchar(32) NOT NULL,

  -- === 锚点：唯一来源是 9.4.1.2 的发货记录 ===
  -- UNIQUE 保证重复同步是幂等更新，不是插出第二条「同一票货的时效」。
  source_record_id uuid NOT NULL,
  source_record_no varchar(32) NOT NULL DEFAULT '',
  -- 刻意允许为空：9.4.1.2 的 sales_order_id 可空（9.4.1.1 还没同步过这批货）。
  -- 空着的行计入 withoutOrderSnapshot，商品维度上它就是查不到 —— 如实报数，
  -- 绝不用占位订单号把行补齐。
  sales_order_id uuid,
  sales_order_no varchar(64) NOT NULL DEFAULT '',

  -- === 三个自身维度（第五节）===
  shop_ref varchar(64) NOT NULL DEFAULT '',
  warehouse_ref varchar(64) NOT NULL DEFAULT '',
  carrier_code varchar(64) NOT NULL DEFAULT '',
  carrier_name varchar(100) NOT NULL DEFAULT '',

  -- === 三个端点时刻 ===
  -- 下单时刻 = sales_order.submitted_at（提交，不是建单；见第一节）
  ordered_at timestamptz,
  -- 出库（打包）时刻 = shipment_pending_unit.packed_at（9.4.1.1 只读 join）
  packed_at timestamptz,
  -- 交接时刻 = shipment_record.shipped_at（9.4.1.2 事实，非 NULL）
  handed_over_at timestamptz NOT NULL,

  -- === 五个整数秒时长，全部由 DB 复算（第二节）===
  order_to_pack_seconds bigint GENERATED ALWAYS AS (
    CASE WHEN ordered_at IS NOT NULL AND packed_at IS NOT NULL AND packed_at > ordered_at
      THEN floor(extract(epoch FROM (packed_at - ordered_at)))::bigint
    END) STORED,
  pack_to_handover_seconds bigint GENERATED ALWAYS AS (
    CASE WHEN packed_at IS NOT NULL AND handed_over_at > packed_at
      THEN floor(extract(epoch FROM (handed_over_at - packed_at)))::bigint
    END) STORED,
  -- 直接相减而不是前两段相加：一端缺失时整段就该 UNSET，
  -- 相加会造出「出库缺失但下单到交接有值」的第三种口径。
  order_to_handover_seconds bigint GENERATED ALWAYS AS (
    CASE WHEN ordered_at IS NOT NULL AND handed_over_at > ordered_at
      THEN floor(extract(epoch FROM (handed_over_at - ordered_at)))::bigint
    END) STORED,

  -- === 发货超时（第四节）===
  ship_sla_deadline_at timestamptz,
  ship_sla_source varchar(24) NOT NULL DEFAULT '' CHECK(ship_sla_source IN
    ('','MANUAL_ATTESTATION','PLATFORM_FEED')),
  ship_overdue_seconds bigint GENERATED ALWAYS AS (
    CASE WHEN ship_sla_deadline_at IS NOT NULL AND handed_over_at > ship_sla_deadline_at
      THEN floor(extract(epoch FROM (handed_over_at - ship_sla_deadline_at)))::bigint
    END) STORED,
  -- 三值：UNSET（没登记时限，既不算准时也不算超时）/ ON_TIME / OVERDUE
  ship_sla_state varchar(10) NOT NULL GENERATED ALWAYS AS (
    CASE WHEN ship_sla_deadline_at IS NULL THEN 'UNSET'
         WHEN handed_over_at > ship_sla_deadline_at THEN 'OVERDUE'
         ELSE 'ON_TIME' END) STORED,

  -- === 承诺到货 / 实际到货（第三节：登记，10.3 未交付）===
  promised_delivery_at timestamptz,
  delivery_promise_state varchar(12) NOT NULL DEFAULT 'UNSET' CHECK(delivery_promise_state IN
    ('UNSET','REGISTERED')),
  promise_source varchar(24) NOT NULL DEFAULT '' CHECK(promise_source IN
    ('','MANUAL_ATTESTATION','PLATFORM_FEED')),
  promise_registered_at timestamptz,
  promise_by_membership_id uuid,
  promise_note varchar(400) NOT NULL DEFAULT '',

  actual_delivered_at timestamptz,
  delivery_state varchar(14) NOT NULL DEFAULT 'UNSET' CHECK(delivery_state IN
    ('UNSET','DELIVERED','NOT_DELIVERED')),
  delivery_source varchar(24) NOT NULL DEFAULT '' CHECK(delivery_source IN
    ('','MANUAL_ATTESTATION','PLATFORM_FEED')),
  delivery_registered_at timestamptz,
  delivery_by_membership_id uuid,
  delivery_note varchar(400) NOT NULL DEFAULT '',

  -- 承诺 vs 实际：两端都登记才有值；差为正 = 迟到（超期秒数），为负 = 提前
  promise_vs_delivery_seconds bigint GENERATED ALWAYS AS (
    CASE WHEN promised_delivery_at IS NOT NULL AND actual_delivered_at IS NOT NULL
      THEN floor(extract(epoch FROM (actual_delivered_at - promised_delivery_at)))::bigint
    END) STORED,
  -- 四值：NO_PROMISE 与 NO_ACTUAL 刻意不合并（第三节）
  promise_vs_delivery_state varchar(12) NOT NULL GENERATED ALWAYS AS (
    CASE WHEN promised_delivery_at IS NULL THEN 'NO_PROMISE'
         WHEN actual_delivered_at IS NULL THEN 'NO_ACTUAL'
         WHEN actual_delivered_at > promised_delivery_at THEN 'LATE'
         ELSE 'MET' END) STORED,

  -- === 同步留痕 ===
  synced_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 幂等键：同一票货只有一条时效事实
  UNIQUE(tenant_id,source_record_id),
  UNIQUE(tenant_id,fact_no),

  -- **已登记的承诺/实际必须带来源与主体**（四件套：时刻 + 来源 + 主体 + 登记时刻）。
  -- 只写一个时刻却没有「谁说的」，事后追责就没有落点。
  CONSTRAINT order_timeliness_promise_evidence
    CHECK (delivery_promise_state <> 'REGISTERED' OR
      (promised_delivery_at IS NOT NULL AND btrim(promise_source) <> ''
       AND promise_by_membership_id IS NOT NULL AND promise_registered_at IS NOT NULL)),
  -- UNSET 态不许残留任何承诺证据，否则「没登记」这一事实本身会被污染
  CONSTRAINT order_timeliness_promise_unset_is_bare
    CHECK (delivery_promise_state <> 'UNSET' OR
      (promised_delivery_at IS NULL AND btrim(promise_source) = ''
       AND promise_by_membership_id IS NULL AND promise_registered_at IS NULL)),
  CONSTRAINT order_timeliness_delivery_evidence
    CHECK (delivery_state = 'UNSET' OR
      (actual_delivered_at IS NOT NULL AND btrim(delivery_source) <> ''
       AND delivery_by_membership_id IS NOT NULL AND delivery_registered_at IS NOT NULL)),
  CONSTRAINT order_timeliness_delivery_unset_is_bare
    CHECK (delivery_state <> 'UNSET' OR
      (actual_delivered_at IS NULL AND btrim(delivery_source) = ''
       AND delivery_by_membership_id IS NULL AND delivery_registered_at IS NULL)),
  -- NOT_DELIVERED 强制带原因：拒收/退回/丢失是三件完全不同的事
  CONSTRAINT order_timeliness_not_delivered_needs_reason
    CHECK (delivery_state <> 'NOT_DELIVERED' OR btrim(delivery_note) <> ''),
  -- 时限三件套自洽（与 V124 shipment_pending_unit 同一写法）
  CONSTRAINT order_timeliness_sla_self_consistent
    CHECK ((ship_sla_deadline_at IS NULL) = (btrim(ship_sla_source) = ''))
);

-- 四维拆解：三个自身维度 + 商品维度 join 的连接键
CREATE INDEX order_timeliness_fact_shop
  ON order_timeliness_fact(tenant_id, shop_ref);
CREATE INDEX order_timeliness_fact_warehouse
  ON order_timeliness_fact(tenant_id, warehouse_ref);
CREATE INDEX order_timeliness_fact_carrier
  ON order_timeliness_fact(tenant_id, carrier_code);
-- 报表默认按交接时刻圈窗口，索引跟着走
CREATE INDEX order_timeliness_fact_handover
  ON order_timeliness_fact(tenant_id, handed_over_at);
-- 超时明细清单：先筛出 OVERDUE 再排秒数
CREATE INDEX order_timeliness_fact_overdue
  ON order_timeliness_fact(tenant_id, ship_overdue_seconds)
  WHERE ship_overdue_seconds IS NOT NULL;
-- 「承诺 vs 实际」四桶清单，NO_PROMISE 单独成列
CREATE INDEX order_timeliness_fact_promise_state
  ON order_timeliness_fact(tenant_id, promise_vs_delivery_state);
-- 商品维度的 join 键
CREATE INDEX order_timeliness_fact_order
  ON order_timeliness_fact(tenant_id, sales_order_id)
  WHERE sales_order_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. order_timeliness_promise · 承诺到货登记（append-only 凭据）
-- ---------------------------------------------------------------------------
-- 承诺到货来自平台 SLA 面，2.5.1.1 未交付 ⇒ 无可读上游，只能登记。
-- append-only：要改就再登记一条（带更正说明），不改上一条 ——
-- 「这一票当时承诺的是哪天」在平台申诉里必须答得出来。
CREATE TABLE order_timeliness_promise (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  fact_id uuid NOT NULL,
  promised_delivery_at timestamptz NOT NULL,
  source varchar(24) NOT NULL CHECK(source IN ('MANUAL_ATTESTATION','PLATFORM_FEED')),
  -- 更正上一条时的依据：没有它就变成「随口改承诺」
  note varchar(400) NOT NULL DEFAULT '',
  registered_by_membership_id uuid NOT NULL,
  registered_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,fact_id)
    REFERENCES order_timeliness_fact(tenant_id,id) ON DELETE CASCADE,
  -- 登记时刻不许早于承诺时刻：把还没发生的事承诺出去会让准时率失真
  CONSTRAINT order_timeliness_promise_time_order
    CHECK (registered_at <= promised_delivery_at)
);

CREATE INDEX order_timeliness_promise_fact
  ON order_timeliness_promise(tenant_id, fact_id, promised_delivery_at desc);

-- ---------------------------------------------------------------------------
-- 3. order_timeliness_delivery · 实际到货登记（append-only 凭据）
-- ---------------------------------------------------------------------------
-- 实际到货的**唯一**真实来源是承运商轨迹的签收事件，10.3 未交付 ⇒ 无可读上游。
-- 人工登记是「有人报了这个事实」，不是系统抓到了轨迹，
-- 所以 source 恒为 MANUAL_ATTESTATION（PLATFORM_FEED 留给轨迹回传接上那天）。
CREATE TABLE order_timeliness_delivery (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  fact_id uuid NOT NULL,
  delivered_at timestamptz NOT NULL,
  state varchar(14) NOT NULL CHECK(state IN ('DELIVERED','NOT_DELIVERED')),
  source varchar(24) NOT NULL CHECK(source IN ('MANUAL_ATTESTATION','PLATFORM_FEED')),
  note varchar(400) NOT NULL DEFAULT '',
  registered_by_membership_id uuid NOT NULL,
  registered_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,fact_id)
    REFERENCES order_timeliness_fact(tenant_id,id) ON DELETE CASCADE,
  -- 「未送达」是**签收事件缺失**，不是「还没送到」：
  -- 货还在路上时唯一诚实的表达是没登记（NO_ACTUAL），
  -- 而不是登记一条 NOT_DELIVERED。
  -- 注：交付时刻不许早于交接时刻，但 handed_over_at 在**另一张表**上，
  -- CHECK 只能看到本行，所以那条由下面的 guard_order_timeliness_delivery_order
  -- 触发器在写路径上钉（绕过服务层直接写 SQL 同样被拒）。
  CONSTRAINT order_timeliness_not_delivered_note
    CHECK (state <> 'NOT_DELIVERED' OR btrim(note) <> '')
);

CREATE INDEX order_timeliness_delivery_fact
  ON order_timeliness_delivery(tenant_id, fact_id, delivered_at desc);

-- 交付时刻不许早于交接时刻。写成一个触发器而不是 CHECK，是因为
-- delivered_at 与 handed_over_at 分属两张表，CHECK 只能看到本行。
CREATE OR REPLACE FUNCTION guard_order_timeliness_delivery_order() RETURNS trigger AS $$
DECLARE handover timestamptz;
BEGIN
  SELECT handed_over_at INTO handover FROM order_timeliness_fact
   WHERE tenant_id = NEW.tenant_id AND id = NEW.fact_id;
  IF handover IS NULL THEN
    RAISE EXCEPTION 'order_timeliness_delivery 找不到时效事实 %', NEW.fact_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.state = 'DELIVERED' AND NEW.delivered_at < handover THEN
    RAISE EXCEPTION
      '实际到货时刻 % 早于交接时刻 %（货还没交出去不可能已签收）',
      NEW.delivered_at, handover USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_order_timeliness_delivery_order_trg
  BEFORE INSERT ON order_timeliness_delivery
  FOR EACH ROW EXECUTE FUNCTION guard_order_timeliness_delivery_order();

-- ---------------------------------------------------------------------------
-- 4. order_timeliness_event · 事件流（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE order_timeliness_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  seq bigint GENERATED ALWAYS AS IDENTITY,
  fact_id uuid,
  kind varchar(28) NOT NULL CHECK(kind IN
    ('FACT_SYNCED','PROMISE_REGISTERED','DELIVERY_REGISTERED','DELIVERY_CORRECTED')),
  summary varchar(300) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id)
);

CREATE INDEX order_timeliness_event_tenant
  ON order_timeliness_event(tenant_id, seq desc);
CREATE INDEX order_timeliness_event_fact
  ON order_timeliness_event(tenant_id, fact_id, seq desc);

-- ---------------------------------------------------------------------------
-- 5. DB 级闸门
-- ---------------------------------------------------------------------------
-- (a) 两张凭据表 append-only：连运行角色都不给改删。
CREATE OR REPLACE FUNCTION reject_order_timeliness_credential_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'order_timeliness 凭据行 append-only，不允许 % （要更正请追加一条新登记）',
    TG_OP USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_order_timeliness_promise_append_only
  BEFORE UPDATE OR DELETE ON order_timeliness_promise
  FOR EACH ROW EXECUTE FUNCTION reject_order_timeliness_credential_mutation();

CREATE TRIGGER guard_order_timeliness_delivery_append_only
  BEFORE UPDATE OR DELETE ON order_timeliness_delivery
  FOR EACH ROW EXECUTE FUNCTION reject_order_timeliness_credential_mutation();

CREATE TRIGGER guard_order_timeliness_event_append_only
  BEFORE UPDATE OR DELETE ON order_timeliness_event
  FOR EACH ROW EXECUTE FUNCTION reject_order_timeliness_credential_mutation();

-- (b) 快照列不许脱离凭据行单独改。服务层因此被钉死成
--     「先落凭据行，再更新快照」，同一个事务里完成。
CREATE OR REPLACE FUNCTION guard_order_timeliness_snapshots() RETURNS trigger AS $$
DECLARE latest_promise timestamptz;
DECLARE latest_delivery timestamptz;
DECLARE latest_state text;
BEGIN
  IF NEW.promised_delivery_at IS DISTINCT FROM OLD.promised_delivery_at THEN
    SELECT promised_delivery_at INTO latest_promise
      FROM order_timeliness_promise
     WHERE tenant_id = NEW.tenant_id AND fact_id = NEW.id
     ORDER BY promised_delivery_at desc, created_at desc
     LIMIT 1;
    IF latest_promise IS DISTINCT FROM NEW.promised_delivery_at THEN
      RAISE EXCEPTION
        'order_timeliness_fact 承诺到货快照置为 % 但最新承诺凭据是 % （先落 order_timeliness_promise 再更新快照）',
        NEW.promised_delivery_at, coalesce(latest_promise::text, '（无登记）')
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  IF NEW.actual_delivered_at IS DISTINCT FROM OLD.actual_delivered_at
     OR NEW.delivery_state IS DISTINCT FROM OLD.delivery_state THEN
    SELECT delivered_at, state INTO latest_delivery, latest_state
      FROM order_timeliness_delivery
     WHERE tenant_id = NEW.tenant_id AND fact_id = NEW.id
     ORDER BY delivered_at desc, created_at desc
     LIMIT 1;
    IF latest_delivery IS DISTINCT FROM NEW.actual_delivered_at
       OR latest_state IS DISTINCT FROM NEW.delivery_state THEN
      RAISE EXCEPTION
        'order_timeliness_fact 实际到货快照置为 %/% 但最新交付凭据是 %/% （先落 order_timeliness_delivery 再更新快照）',
        NEW.actual_delivered_at, NEW.delivery_state,
        coalesce(latest_delivery::text, '（无登记）'), coalesce(latest_state, '（无登记）')
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_order_timeliness_snapshots_trg
  BEFORE UPDATE ON order_timeliness_fact
  FOR EACH ROW EXECUTE FUNCTION guard_order_timeliness_snapshots();

-- (c) 时刻不许倒流：下单 → 出库 → 交接 必须单调。
--     服务层已经按此顺序过滤，这个触发器是给「绕过服务层直接写 SQL」准备的。
CREATE OR REPLACE FUNCTION guard_order_timeliness_milestones() RETURNS trigger AS $$
BEGIN
  IF NEW.packed_at IS NOT NULL AND NEW.ordered_at IS NOT NULL
     AND NEW.packed_at < NEW.ordered_at THEN
    RAISE EXCEPTION '出库时刻 % 早于下单时刻 % （时间倒流）', NEW.packed_at, NEW.ordered_at
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.packed_at IS NOT NULL AND NEW.handed_over_at < NEW.packed_at THEN
    RAISE EXCEPTION '交接时刻 % 早于出库时刻 % （时间倒流）', NEW.handed_over_at, NEW.packed_at
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_order_timeliness_milestones_trg
  BEFORE INSERT OR UPDATE ON order_timeliness_fact
  FOR EACH ROW EXECUTE FUNCTION guard_order_timeliness_milestones();

-- ---------------------------------------------------------------------------
-- 6. 多租户与授权（与 trade V1 第 2 节、V127 第 8 节同一套写法）
-- ---------------------------------------------------------------------------
-- 四张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_timeliness_fact',
    'order_timeliness_promise',
    'order_timeliness_delivery',
    'order_timeliness_event' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_timeliness_fact,
  order_timeliness_promise,
  order_timeliness_delivery,
  order_timeliness_event TO trade_app;
-- 三张凭据/事件表是举证凭据：连运行角色都不给改删，兜底在权限层而不只在触发器里。
-- 授权在前、收回在后 —— 反过来写会被上面这条 GRANT 全量覆盖回去。
REVOKE UPDATE,DELETE ON
  order_timeliness_promise,
  order_timeliness_delivery,
  order_timeliness_event FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V127 同口径）。
REVOKE ALL ON
  order_timeliness_fact,
  order_timeliness_promise,
  order_timeliness_delivery,
  order_timeliness_event FROM starcon_app;
-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE order_timeliness_no_seq TO trade_app;
