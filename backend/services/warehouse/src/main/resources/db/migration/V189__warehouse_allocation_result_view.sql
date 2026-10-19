-- ===========================================================================
-- 7.8.2.1 分摊结果查看：把「分摊完了之后到底怎么样了」变成可核对的事实
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/07-inventory.md）：
--   ### 7.8.2.1 分摊结果查看
--   - 职责：展示某 SKU/仓在各店铺的分摊量、已用、剩余、回写平台的数值，
--           以及回写失败或延迟导致的实际风险敞口。
--   - 输入：分摊结果；平台回写状态（4.3.2.1）。
--   - 输出：分摊明细表 + 平台回写一致性核对。
--   - 关系：上游 = 7.8.1、4.3.2.1；下游 = 04 刊登（回写依据）。
--           **平台回写有延迟**，分摊需保留缓冲以覆盖同步窗口，缓冲不足会超卖。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 跨服务边界：平台回写状态在 channel 服务，本服务**不读它的表**
-- ---------------------------------------------------------------------------
-- 4.3.2.1 内容回写已交付在 channel 服务（V187 listing_content_writeback）。
-- AGENTS.md「模块边界纪律」禁止跨服务 SQL / 跨库 FK / 共享视图，所以本模块
-- **绝对不能**在 warehouse 里 join 或读 channel 的 listing / writeback 表。
--
-- 正确做法（也是本迁移唯一的做法）：把「平台回写状态」建模成 **warehouse 自己的
-- 投影事实** —— 一张本地表记录每一次观测到的回写状态，每条观测带
--   * source_kind      来源种类（事件推送 / 授权 API 推送 / 显式录入）
--   * source_reference 来源标识（事件 id、API 调用标识、录入单号）
--   * as_of            采集时刻
-- 于是「平台现在挂的是多少」这件事在 warehouse 内部是**可核对的**，
-- 而不需要在查询时去碰别的服务的库。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 「不知道」必须是一个状态，不能是一个 0
-- ---------------------------------------------------------------------------
-- 本模块最容易出的、且最难被发现的错，是把「本地还没收到回写状态」渲染成
-- 「回写数 = 0」或「已回写 0 件」。那等于对外宣称「平台上一件都卖不出去」，
-- 而真相是「我们不知道」。两者的处置完全相反：前者会去补货，后者该去查同步。
--
-- 所以：
--   * 表里 written_quantity / consumed_quantity **可空**，NULL = 观测方不知道；
--   * 状态词表里有 UNKNOWN（尚未回填），它与 0 是两件事；
--   * 敞口算不出来时给 UNKNOWN，**不**给 0。
-- DB 层能钉住的部分钉在 CHECK 上（SUCCEEDED 必须带确认值、FAILED 必须带原因）。
--
-- ---------------------------------------------------------------------------
-- 三、三个「缓冲」是三个不同的东西（本表用到的那个是第三个）
-- ---------------------------------------------------------------------------
--   7.2.2.1 buffer_ratio  平台可售数保留缓冲比例 ← **本模块的敞口算式用它**
--                        （抵御平台回写同步延迟）
--   7.8.1.1 buffer        分摊缓冲：同一批货里压根不参与分摊的量
--                        （抵御各平台同时下单）
--   7.8.1.2 保底池         货不够分时先保住高价值店铺的底线
-- 三者串联：可分配量 = 可用量 − 分摊缓冲；保底池 + 余量池 = 可分配量；
-- 而**平台回写缓冲是作用在「回写给平台的那个数」上的** —— 它决定
-- 「我方剩余 vs 平台挂着的数」之间能容忍多大的差。
--
-- 7.2.2.1 的口径在本服务（V56 sellable_policy），所以敞口算式**不需要**跨服务取数。
--
-- ---------------------------------------------------------------------------
-- 四、敞口算式（就这一套，服务层与页面都按它渲染）
-- ---------------------------------------------------------------------------
--   已用     consumed  = 平台侧已消耗（已售出）的件数      ← 来自回写观测
--             同样取「最近一次**带确认值**的观测」：失败的观测没有带来新的已用数，
--             但它也没有让已用数倒退 —— 用最后一次真的观测到的数，
--             而不是把「这次没测到」当成「已用 = 0」。
--   剩余     remaining = 分摊量 − 已用
--   平台挂着的 platform = 最近一次**带确认值**的观测里的 written_quantity
--             （FAILED / PENDING 的观测不改变平台当前挂着的数，
--               所以平台当前值取「最近一次带确认值的观测」，
--               而不是「最近一次观测」—— 这是本表最容易写错的一处）
--   敞口     exposure  = max(0, platform − remaining)
--             = 平台还在卖、而我方已经没有的件数 —— 这就是同步窗口里的超卖风险
--   缓冲     buffer    = 分摊量 − trunc(分摊量 × (1 − 缓冲比例) + 超卖容忍)
--             （与 7.2.2.1 的 committed 口径同形：给平台留多少）
--
--   敞口判定：exposure > buffer ⇒ **缓冲不足，同步窗口内会超卖**
--
-- ---------------------------------------------------------------------------
-- 五、本迁移的表与既有表的关系
-- ---------------------------------------------------------------------------
--   allocation_writeback_snapshot → allocation_run_line（本表复合外键）
--     观测必须挂在一次**真实存在**的分摊行上。没有这条外键的话，
--     「给一个不存在的店铺记一条回写状态」会被接受，
--     而那种行在核对时既不匹配任何分摊行、也不匹配任何平台 listing ——
--     它只会在总数里悄悄存在。
--
--   本迁移**零 ALTER 既有表**：不改 allocation_run / allocation_run_line 的任何一列，
--   不改 sellable_policy，不改 warehouse。7.8.1.1 / 7.8.1.2 / 7.2.2.1 的直写夹具
--   （别的车道在用）不会被本迁移打掉。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 全部 numeric(19,6) + BigDecimal，无 float / double。
-- 数量必须是**整件**：平台可售数不会有半件，而半件既不属于这家也不属于那家。
-- 时间字段全部 timestamptz：服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz`；集成测试**断言写回的值逐字相等**。

-- ===========================================================================
-- 1. allocation_writeback_snapshot · 平台回写状态的本地投影（append-only）
-- ===========================================================================
-- 一行 = 一次观测。观测可以来自 channel 的回写事件、来自授权 API 推送，
-- 也可以是运营显式录入的快照（source_kind=MANUAL）。
-- 刻意**不做成「每个 (run, 店铺) 一行、原地更新」**：回写失败与回写延迟
-- 恰恰是本模块要暴露的东西，把观测压成一行就看不到「失败过几次、
-- 最后一次成功是什么时候」了。append-only 让时间线本身成为证据。
CREATE TABLE allocation_writeback_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 这次观测对应的是哪一次分摊（7.8.1.1 的 allocation_run）。
  run_id uuid NOT NULL,

  -- 店铺在 channel 服务（2.3.1.1 / 2.3.2.1 已交付）：只存逻辑 id + 写入时的可读名快照，
  -- **不建外键**（与 V68 的成员表同一纪律：跨服务引用不建 FK）。
  source_system varchar(32) NOT NULL,
  reference_id uuid NOT NULL,
  reference_label varchar(150) NOT NULL DEFAULT '',

  -- 仓是**本服务自有**实体（V1 的 warehouse），所以这里可以建复合外键。
  -- 它回答「这批货是从哪个仓出的、平台上的数对应的是哪个仓的货」。
  warehouse_id uuid NOT NULL,

  -- 本次观测到的回写状态：
  --   SUCCEEDED 回写成功，平台确认已持有 attempted_quantity 件
  --   FAILED    回写失败，平台仍持有旧值
  --   PENDING   已发出未确认，平台仍持有旧值
  state varchar(16) NOT NULL CHECK(state IN ('SUCCEEDED','FAILED','PENDING')),

  -- 本次**尝试**回写的可售件数。失败/未确认时它仍然有意义：
  -- 「我们想写成 40，失败了，平台上还挂着 100」正是延迟敞口的来源。
  attempted_quantity numeric(19,6) NOT NULL
    CHECK(attempted_quantity >= 0 AND attempted_quantity = trunc(attempted_quantity)),

  -- 平台**当前**被告知的可售件数。
  -- NULL = 观测方不知道平台现在挂的是多少（**不是 0**）。
  written_quantity numeric(19,6)
    CHECK(written_quantity IS NULL OR
          (written_quantity >= 0 AND written_quantity = trunc(written_quantity))),

  -- 平台侧已用（已售出）件数。NULL = 这次观测没有拿到这个数。
  consumed_quantity numeric(19,6)
    CHECK(consumed_quantity IS NULL OR
          (consumed_quantity >= 0 AND consumed_quantity = trunc(consumed_quantity))),

  -- 失败原因。FAILED 时必填（DB 层钉死，见 §2）。
  failure_reason varchar(300) NOT NULL DEFAULT '',

  -- 来源标识：事件 id / 授权 API 调用标识 / 显式录入的单号。
  -- 没有它，一行观测就答不出「这个数是谁在什么时候告诉我们的」。
  source_kind varchar(16) NOT NULL CHECK(source_kind IN ('EVENT','API','MANUAL')),
  source_reference varchar(200) NOT NULL,
  -- 采集时刻：**观测成立的那一刻**，不是入库时刻。
  -- 一次补录的旧观测，用采集时刻而不是入库时刻才说得清「当时平台挂的是多少」。
  as_of timestamptz NOT NULL,

  -- 操作人：已认证成员 id（与 V54/V56/V68 同一纪律，不接受请求参数传入）。
  recorded_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 观测必须挂在一次**真实存在**的分摊行上（复合外键直接指到 allocation_run_line
  -- 的主键 (tenant_id, run_id, reference_id)）。
  -- 没有它的话，「给一个不存在的店铺记一条回写状态」会被接受 ——
  -- 而那种行在核对时既不匹配任何分摊行、也不匹配任何平台 listing，
  -- 只会在总数里悄悄存在。
  FOREIGN KEY (tenant_id,run_id,reference_id)
    REFERENCES allocation_run_line(tenant_id,run_id,reference_id) ON DELETE CASCADE,

  -- 仓是本服务实体，复合外键钉住「这个仓确实属于本租户」。
  FOREIGN KEY (tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  -- 成功回写必须带平台确认值：成功了却不知道平台挂了多少，那次观测什么也没证明。
  CHECK(state <> 'SUCCEEDED' OR written_quantity IS NOT NULL),
  -- 失败必须写原因：没有原因的失败在复盘时与「没发生过」长得一模一样。
  CHECK(state <> 'FAILED' OR btrim(failure_reason) <> ''),
  -- 未确认（进行中）时不该有失败原因。
  CHECK(state <> 'PENDING' OR btrim(failure_reason) = ''),

  CHECK(btrim(source_system) <> ''),
  CHECK(btrim(source_reference) <> '')
);

-- 「某个 (run, 店铺) 的最新一次观测」是本模块的主查询（结果表每行都要它）。
CREATE INDEX allocation_writeback_snapshot_latest
  ON allocation_writeback_snapshot(tenant_id, run_id, reference_id, as_of DESC, id DESC);
-- 「平台当前挂着的数」= 最近一次带确认值的观测，同一条索引的另一条访问路径。
CREATE INDEX allocation_writeback_snapshot_confirmed
  ON allocation_writeback_snapshot(tenant_id, run_id, reference_id, as_of DESC)
  WHERE written_quantity IS NOT NULL;
-- 按店铺回看「这个店被回写过什么」（跨分摊轮次的时间线）。
CREATE INDEX allocation_writeback_snapshot_shop
  ON allocation_writeback_snapshot(tenant_id, reference_id, as_of DESC);
-- 按仓回看。
CREATE INDEX allocation_writeback_snapshot_warehouse
  ON allocation_writeback_snapshot(tenant_id, warehouse_id, as_of DESC);
-- 「哪些观测是失败的」——排障与核对页的默认筛选。
CREATE INDEX allocation_writeback_snapshot_state
  ON allocation_writeback_snapshot(tenant_id, state, as_of DESC);

-- ===========================================================================
-- 2. DB 级行为约束
-- ===========================================================================

-- 2.1 观测是事实：不可改、不可删。
-- 它回答的是「那一刻平台挂的是多少、我们告诉它的是多少」。
-- 改一行等于让这个问题再也答不出来，而超卖复盘恰恰要问它。
CREATE FUNCTION guard_allocation_writeback_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'allocation_writeback_snapshot 是 append-only：不允许 %，'
   '回写状态的观测是「当时平台挂的是多少」的唯一凭据，删改它等于让超卖复盘无从查证',
   TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER allocation_writeback_append_only
  BEFORE UPDATE OR DELETE ON allocation_writeback_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_writeback_append_only();

-- 2.2 观测时刻不得早于它所描述的那次分摊。
-- 平台回写发生在分摊之后：分摊还没发生就「回写状态」，那不是观测，是编造。
CREATE FUNCTION guard_allocation_writeback_after_run() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE run_at timestamptz;
BEGIN
 SELECT r.allocated_at INTO run_at
   FROM allocation_run r
  WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.run_id;
 IF run_at IS NULL THEN
   RAISE EXCEPTION '分摊 % 不存在或不属于当前租户', NEW.run_id USING ERRCODE='23514';
 END IF;
 IF NEW.as_of < run_at THEN
   RAISE EXCEPTION '回写状态的采集时刻 % 早于该次分摊的时刻 %：'
     '平台回写发生在分摊之后，比分摊还早的「回写状态」不是观测而是编造',
     NEW.as_of, run_at USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocation_writeback_after_run
  BEFORE INSERT ON allocation_writeback_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_writeback_after_run();

-- ===========================================================================
-- 3. 多租户：RLS + 授权（与 V1/V56/V68/V76 同一套写法）
-- ===========================================================================
-- 业务表一视同仁 FORCE。current_setting('app.current_tenant') 由
-- backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['allocation_writeback_snapshot'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 既有迁移的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT ON allocation_writeback_snapshot TO warehouse_app;

-- append-only：权限层先收一道 UPDATE / DELETE，§2.1 的触发器再收一道。
REVOKE UPDATE, DELETE ON allocation_writeback_snapshot FROM warehouse_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V56/V68/V76 同口径）。
REVOKE ALL ON allocation_writeback_snapshot FROM starcon_app;
