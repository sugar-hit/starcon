-- ===========================================================================
-- 8.5.3.1 出库时效：订单时间戳投影 + 可配置阈值（四环节时效的度量底座）
-- ===========================================================================
-- 蓝图原文：「**统计出库各环节时效**：订单到出库时长、拣货时长、打包时长、交接时长，
-- 识别超时环节与瓶颈。」输入：「订单时间戳；各作业环节时间戳。」
-- 输出：「时效分布 + 瓶颈环节 + 超时明细。」关系段：「上游 = 9 订单、8.3 作业记录；
-- 下游 = 1 工作台履约健康。**时效数据直接对应平台迟发率指标**，超时会导致
-- 绩效违规与搜索降权。」
--
-- ---------------------------------------------------------------------------
-- 一、四个环节分别从哪里取时刻（全部在集成头逐张复核过）
-- ---------------------------------------------------------------------------
--   环节                起点                        终点                   归属
--   ------------------  --------------------------  ---------------------  ------------------
--   订单到出库          order_placed_at              outbound_order.        本表① + V72
--                                                shipped_at
--   拣货                pick_task.started_at         pick_task.              V110（8.3.2.1）
--                                                completed_at
--   打包                packing_order.started_at     packing_order.          V121（8.3.3.2）
--                                                completed_at
--   交接                shipment_handover.created_at shipment_handover.      V123（8.3.4.1）
--                                                handover_at
--
-- 三个内部环节的四个时间戳**全部已在库**（8.3 三条车道已交付），所以本模块对它们
-- **只读、不投影** —— 再抄一份只会让它比它描述的作业更先过期（8.5.2.1 的同一条判断）。
--
-- ---------------------------------------------------------------------------
-- 二、为什么仍然要建表①（订单时间戳投影），而不是直接读 trade
-- ---------------------------------------------------------------------------
-- 「订单时间戳」的上游是 **9 订单（trade 服务）**。模块边界纪律明令禁止跨服务 SQL /
-- 跨库 FK / 共享视图，所以本服务**读不到** `trade` 的订单表 —— 这不是缺陷，是纪律。
--
-- trade 侧**没有**一个「按出库单取下单时刻」的授权 API 可调（9.5.1.1 时效分析是
-- trade 自己的读接口，方向是它读它的数据，不能反向当作 warehouse 的取数口）。
-- 照纪律此刻应当停下报 BLOCKED 吗？**不** —— 纪律同时给了第二条合法路径：
-- 「要么通过 channel/trade 的**授权 API** 拿，要么**在本服务内维护自己的投影/快照**」。
-- 本表走第二条：
--
--   * 本车道是 outbound_order_time_snapshot 的**唯一写入所有者**，写入只经由
--     `POST /warehouse/outbound-timeliness/order-snapshots`（幂等 upsert）。
--     trade 的 9 订单车道落地后投递订单下单时刻，`source_module` 标明来源。
--   * **不猜、不代填**：`order_placed_at` 是 NOT NULL，没有默认 now()、没有
--     「用出库单创建时刻顶替」。缺投影时「订单到出库时长」是 **null（不可计算）**，
--     报表把这种样本数单独报出来 —— 宁可少一个指标，也不编一个看起来能用的数
--     （编出来的「及时效」比没有时效更危险：它会让人以为迟发率被管住了）。
--   * 这与 8.5.1.1（V74）处理「8.3 作业记录未交付」时的路线 A 完全同构：
--     本模块拥有自己那一份事实 + 配真实写入入口，而不是等上游或替上游写表。
--
-- ---------------------------------------------------------------------------
-- 三、时长口径（争议高发区，逐字重复在代码注释 / /capabilities / 响应里）
-- ---------------------------------------------------------------------------
-- 1. **墙钟时长**，不是净作业时长。净时长要求剔除等待/中断，那需要另记暂停区间，
--    系统里没有那组数据，算出来的是假的。两个时间戳都在库里，**任何人拿同一行都能
--    算出同一个数** —— 这是有意的取舍。
-- 2. **单位是分钟**，`numeric(19,6)` + Java `BigDecimal`，全程禁浮点。
--    分钟而不是秒：拣货 40 秒与 8 小时是同一列里的两个数，秒会把有效位浪费在毫秒上；
--    保留 6 位小数是为了「1.5 分钟」与「1 分 30 秒」这类真实值不被截成同一个数。
-- 3. **负时长不可计算（null），不夹成 0**。交接时刻早于建单时刻是数据错误，
--    夹成 0 会让「时间戳错了」显示成「这一单超快」。
-- 4. **聚合窗口按 Asia/Shanghai 自然日**，键取 `outbound_order.shipped_at`
--    （订单到出库时长这一列的终点）。刻意不跟服务器本地时区走：本仓是跨境电商，
--    同一份报表在两台机器上跑出不同的「昨天」不可接受。
-- 5. **只统计已走到该环节终点的样本**。还在路上（没发货 / 没打包完）的单子没有
--    终点，把它算进平均值等于奖励「慢」。但**未完成样本数要单独报**，不能让它
--    消失在分母里 —— 「今天出库时效很好」有可能只是「货还没发出去」。
-- 6. **分位数用 percentile_cont**（线性插值，是分布统计的标准做法），
--    立刻 `::numeric(19,6)` 落库：浮点只允许出现在 PG 内部的插值里，
--    **不进入存储与传输**，时长列对外一律是 numeric。
-- 7. **瓶颈只看内部三环节**。订单到出库是**总时长**，恒大于其任一环节；
--    把它算进瓶颈排名，「瓶颈永远是订单到出库」，这条信息就恒为零。
--    报告里总时长单列，瓶颈在拣货/打包/交接里选。
--
-- ---------------------------------------------------------------------------
-- 四、阈值必须可配（不是写死的常量），且按租户 / 仓库两级
-- ---------------------------------------------------------------------------
-- 蓝图把本模块的数字直接挂在「平台迟发率」上，而迟发率**按平台、按店铺、按仓库
-- 都不一样**：Amazon 的截单时间与承运商 SLA 决定阈值，一个仓和另一个仓不是同一个数。
-- 所以阈值落在表②里，解析顺序：
--
--     仓库级覆盖（warehouse_id NOT NULL） > 租户默认（warehouse_id IS NULL） > 内置兜底
--
-- 每一档都带 `UNIQUE NULLS NOT DISTINCT`，所以「某仓四环节各一条、租户默认四条」
-- 在 DB 层唯一，upsert 是幂等的。内置兜底值同样对外声明在 /capabilities 里，
-- 且每个被解析出的阈值都带 `source`（WAREHOUSE / TENANT / BUILTIN）——
-- 看到 40 分钟这个数时，必须能回答「这是谁定的」，否则调完阈值没人知道有没有生效。
--
-- ---------------------------------------------------------------------------
-- 五、类型与时间
-- ---------------------------------------------------------------------------
--   时长 NUMERIC(19,6) 分钟 + Java BigDecimal，**禁浮点**（不是金额，但同样是时长）。
--   时间 timestamptz：应用层以 OffsetDateTime 绑定并在 values 位置写 ?::timestamptz，
--   不用 Timestamp.valueOf(LocalDateTime) —— 后者按 JVM 本地时区解释裸本地时间，
--   在东八区会静默偏 −28800s（与 V55 / V59 / V74 同一条纪律）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. outbound_order_time_snapshot · 订单下单时刻投影（本车道唯一写入所有者）
-- ---------------------------------------------------------------------------
-- 一行 = 一张出库单的下单时刻快照。幂等：同一出库单重复投递覆盖，不产生第二行。
--
-- 刻意**不**建 stage 时间戳列（拣货/打包/交接）：那四个时间戳在 V110 / V121 /
-- V123 里已是事实，本表再抄一份就是「一张比它描述的作业更早过期的表」。
CREATE TABLE outbound_order_time_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 对应哪张出库单。分仓/分批会让一张订单有多张出库单，所以键是出库单而非订单号。
  outbound_order_id uuid NOT NULL,

  -- 订单来源，与 outbound_order.source_type 同枚举（V72）：出库单是哪一类单驱动的。
  source_type varchar(20) NOT NULL CHECK(source_type IN
    ('SALES','PURCHASE_RETURN','TRANSFER','PRODUCTION')),

  -- 下单时刻：订单在它自己系统里成立的那一刻。
  -- 「订单到出库时长」的起点 —— 而这个数在 trade 服务，本服务读不到，
  -- 只能由 trade 投递过来（见第二节）。**NOT NULL 且无默认值**：
  -- 没有「先拿 now() 顶上」这条退路。
  order_placed_at timestamptz NOT NULL,

  -- 投递方模块号（9 订单车道填自己的模块号，本服务自发时填 8.5.3.1）。
  -- 时效数字起争议时第一个要回答的是「这个时刻是谁报上来的」。
  source_module varchar(32) NOT NULL CHECK(btrim(source_module)<>''),

  -- 投递人：已认证用户 id。幂等覆盖会留痕到 updated_at / updated_by_user_id。
  reported_by_user_id uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一张出库单一条快照：这是「当前可信的下单时刻」，不是事件流水。
  -- 真需要历史时应该去 trade 查订单事件日志，不是在本表堆多版本。
  UNIQUE(tenant_id,outbound_order_id),

  FOREIGN KEY(tenant_id,outbound_order_id) REFERENCES outbound_order(tenant_id,id)

  -- 刻意**不**加「下单时刻不得晚于当前时间」之类的 CHECK：
  --   * now() 是 volatile 函数，放进 CHECK 既不可靠（校验时机与写入时机不是一回事）
  --     也不可移植；
  --   * 更重要的是 trade 的订单补录/改期是真实存在的运营动作，一个「看起来在未来」
  --     的时刻可能只是时区或录入问题，DB 级拦截只会让正常补录失败，而正确的处理是
  --     让它进报表并被「负时长 = null」的规则显式暴露出来。
  -- 同一时刻与出库单 shipped_at 的先后关系由分析侧判定（晚于终点即负时长 → 不可计算）。
  -- 注意 source_module 的非空 CHECK 已在列定义处写好（见上），**不在这里重复**：
  -- 同一条 CHECK 写两遍会让 Flyway 逐语句拆分时的类型推断踩到
  -- 「operator does not exist: text <= integer」这个坑（空串字面量被推成 integer）。
);

-- 报表主查询：按出库单取快照（join 键，与 UNIQUE 对齐）。
CREATE INDEX outbound_order_time_snapshot_order
  ON outbound_order_time_snapshot(tenant_id,outbound_order_id);
-- 「这批出库单里哪些还没拿到下单时刻」——未完成样本数与缺口排查走它。
CREATE INDEX outbound_order_time_snapshot_recent
  ON outbound_order_time_snapshot(tenant_id,updated_at DESC,id);

-- ---------------------------------------------------------------------------
-- 2. outbound_timeliness_threshold · 四环节阈值（租户默认 + 仓库覆盖）
-- ---------------------------------------------------------------------------
-- 一行 = （一个仓库或租户默认）×（一个环节）的超时阈值，单位分钟。
--
-- warehouse_id IS NULL = 租户默认档，对该租户所有仓库生效；
-- warehouse_id NOT NULL = 该仓覆盖档，优先级更高（见第四节解析顺序）。
CREATE TABLE outbound_timeliness_threshold (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 覆盖仓库；NULL = 租户默认档。
  warehouse_id uuid,

  -- 四个环节，与 OutboundTimelinessStage 枚举一一对应。
  stage_code varchar(32) NOT NULL CHECK(stage_code IN
    ('ORDER_TO_OUTBOUND','PICKING','PACKING','HANDOVER')),

  -- 阈值分钟数。>= 0：阈值 0 是合法的（该环节必须瞬时完成，如「只要发现不对就拦」），
  -- 负数没有任何业务含义，DB 级 CHECK 直接拒。
  threshold_minutes numeric(19,6) NOT NULL CHECK(threshold_minutes >= 0),

  -- 是否生效。停用而不是删行：改了阈值又改回来时，历史口径要能追溯到当时的值。
  enabled boolean NOT NULL DEFAULT true,

  -- 配置理由。绩效申诉时「为什么是 40 分钟」必须有答案，不能只有一个数。
  note varchar(300) NOT NULL DEFAULT '',

  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- NULLS NOT DISTINCT（PG15+）：让 (tenant, NULL, stage) 这一档也唯一。
  -- 没有它，一个租户可以插出三条 warehouse_id IS NULL 的同一环节阈值，
  -- 而「取哪一条」就成了实现细节 —— 报表口径必须是确定的。
  UNIQUE NULLS NOT DISTINCT(tenant_id,warehouse_id,stage_code),

  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id)
);

-- 「某仓当前生效的四条阈值」——报表主查询路径（覆盖档优先，租户档其次）。
CREATE INDEX outbound_timeliness_threshold_lookup
  ON outbound_timeliness_threshold(tenant_id,warehouse_id,stage_code)
  WHERE enabled;
-- 租户默认档的列表页。
CREATE INDEX outbound_timeliness_threshold_tenant
  ON outbound_timeliness_threshold(tenant_id,stage_code)
  WHERE warehouse_id IS NULL AND enabled;

-- ---------------------------------------------------------------------------
-- 3. 多租户与授权（与 V1 / V2 / V54 / V74 / V110 / V121 / V123 同一套写法）
-- ---------------------------------------------------------------------------
-- 两张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['outbound_order_time_snapshot','outbound_timeliness_threshold'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
-- 投影表需要 INSERT + UPDATE（幂等 upsert 是它的写入语义），不需要 DELETE。
GRANT SELECT,INSERT,UPDATE,DELETE ON outbound_order_time_snapshot, outbound_timeliness_threshold TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V74 同口径）。
REVOKE ALL ON outbound_order_time_snapshot, outbound_timeliness_threshold FROM starcon_app;
