-- ===========================================================================
-- 8.3.1.1 出库单列表（8.3 出库作业的第一块地基）
-- ===========================================================================
-- 蓝图原文：「出库作业主列表：来源（销售/采购退货/调拨/加工出库）、SKU 行数、件数、状态
-- （待拣/拣货中/待复核/待打包/待发货/已发货/取消）、发货仓、承运渠道。」输入：「筛选条件
-- （仓库、来源、状态、时间）；用户数据范围。」输出：「分页列表 + 批量操作（生成拣货任务、
-- 打印、取消）。」上游 = 9 订单履约需求、7.5.1.1 调拨；下游 = 8.3.2.1 拣货。以及本模块被
-- 点名的那条关系：「**出库单 ≠ 销售订单**：一订单可多出库单（分仓/分批），出库单是履约
-- 动作的载体。」
--
-- ---------------------------------------------------------------------------
-- 一、为什么出库单这张表落在这里
-- ---------------------------------------------------------------------------
-- 8.3 目前是空的：调度器核实过 warehouse 服务的 12 个迁移（V1/V2/V51/V54-V62），
-- 建了 33 张表但**没有任何出库单/拣货/打包/发货表**。出库单是整条出库链路的载体，
-- 下游 8.3.2.1 拣货、8.3.3.1 复核、8.3.3.2 打包、8.3.4.1 发货交接都要引用它。
-- 所以本迁移建 outbound_order / outbound_order_line 并**拥有**它们；下游表不归本模块建。
--
-- 「一订单可多出库单」必须在数据形状上成立，不能靠注释说：external_ref 是**普通字符串**
-- 而不是指向订单表的 UUID 外键，所以同一个外部订单引用下可以并排挂任意多张出库单
-- （分仓/分批各一张）。加一个 (tenant_id, source_external_ref) 唯一索引就把这件事禁掉了，
-- 所以这里不加；取而代之的是下面的普通索引（按外部单号反查这批出库单）。
--
-- ---------------------------------------------------------------------------
-- 二、四类来源：三类外部引用 + 一类真关联，语义必须分开
-- ---------------------------------------------------------------------------
-- 蓝图列了四个来源，它们的可信度不一样，所以在表上就是三种不同的表达：
--
--   SALES            销售出库    ← 9 订单履约（未交付）：只存外部订单号字符串。
--   PURCHASE_RETURN  采购退货    ← 6 采购（未交付）：只存外部退货单号字符串。
--   PRODUCTION       加工出库    ← 10 加工（未交付）：只存外部工单号字符串。
--   TRANSFER         调拨出库    ← 7.5.1.1（**已交付**）：真外键到 transfer_order。
--
-- TRANSFER 是唯一能建外键的：transfer_order 与出库单同在 warehouse 服务（V61），
-- 而且它有 UNIQUE(tenant_id,id)，复合外键天然成立。其余三类**不建跨服务外键** ——
-- AGENTS.md 明令禁止跨服务 FK / 查对方表，而 9 / 6 / 10 尚未交付，连表名都没定。
-- 这里存 id 字符串并把它当作对外契约位：上游服务交付后按 (source_type, source_external_ref)
-- 反查这批出库单即可，不需要访问本服务的表。
--
-- CHECK 把两者钉死：TRANSFER 必须有 transfer 外键且**不得**有外部单号；
-- 其余三类必须有外部单号且**不得**有 transfer 外键。留着「TRANSFER 却没有调拨单」
-- 这种行只会让退货源的出库单凭空出现。
--
-- ---------------------------------------------------------------------------
-- 三、七态状态机（DB 级白名单，不是注释里的君子协定）
-- ---------------------------------------------------------------------------
--   PENDING_PICKING   待拣    → PICKING / CANCELLED
--   PICKING           拣货中  → PENDING_RECHECK / CANCELLED
--   PENDING_RECHECK   待复核  → PENDING_PACKING / PICKING / CANCELLED
--   PENDING_PACKING   待打包  → PENDING_SHIPMENT / PENDING_RECHECK / CANCELLED
--   PENDING_SHIPMENT  待发货  → SHIPPED / CANCELLED
--   SHIPPED           已发货  → 终态
--   CANCELLED         取消    → 终态
--
-- 两个返工边值得说明，它们不是随手加的：
--
--   PENDING_RECHECK → PICKING          复核发现拣错/缺件，退回拣货重做。
--   PENDING_PACKING → PENDING_RECHECK  打包时发现缺件，退回复核重新确认。
--
-- 复核不通过**不是**退回 PENDING_PICKING（待拣）而是 PICKING（拣货中）：待拣的含义是
-- 「一件都没拣」，而复核不通过时货已经离开库位、在返工循环里。退回待拣会让待拣同时
-- 表达「没开始」与「拣错了重来」两件事，作业台按状态分组时这两种单子必须分开处理。
-- 这个选择同时让「一张出库单只生成一次拣货任务」（outbound_pick_request 上的
-- UNIQUE(tenant_id,order_id)）站得住 —— 它本来就是一次性交接，不是反复重发的工单。
--
-- SHIPPED / CANCELLED 是终态：货已经交出去了、或者单子已经作废，这两种行上的业务列
-- 一律冻结（见 4.2 触发器）。「已发货的单还能改发货仓」在现实里等于改历史。
--
-- ---------------------------------------------------------------------------
-- 四、DB 级不变量（本迁移最要紧的部分）
-- ---------------------------------------------------------------------------
-- 4.1 outbound_order 状态机（BEFORE UPDATE）：status 变更只认第二节的白名单，
--     且 SHIPPED 必须有 shipped_at、CANCELLED 必须有 cancelled_at。
-- 4.2 终态冻结（BEFORE UPDATE）：SHIPPED / CANCELLED 行上的来源、仓库、承运渠道、
--     外部单号、创建人一律不可改。
-- 4.3 SHIPPED 必须有承运渠道（CHECK）：没有承运商就发不出货。
-- 4.4 明细行不可改不可删（BEFORE UPDATE OR DELETE）：出库单是**计划快照**。
--     拣货时实际拣了什么由 8.3.2.1 记，不回改计划行；真要改数量就取消重开一张单。
--     这一条同时免掉了「头行合计要不要同步」的问题 —— 合计是现算的（第 1 节）。
-- 4.5 明细行只能在待拣时写入（BEFORE INSERT）：单子一动就不能再加行。
-- 4.6 每张单至多一条拣货任务交接请求（UNIQUE(tenant_id,order_id)）。
-- 4.7 outbound_order_event append-only（BEFORE UPDATE OR DELETE）：状态与批量操作留痕。
--
-- 任何一条绕过服务层拿到连接的路径（运维脚本、以后新写的端点）都过不了这几关。
--
-- ---------------------------------------------------------------------------
-- 五、口径：数量与时间
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,6) + Java BigDecimal，全程禁浮点（按 AGENTS.md 硬性要求，与
--   V1/V2/V59 的 NUMERIC(19,4) 相比多留两位小数：出库明细会出现称重/散装件，
--   4 位小数会丢精度）。
--   时间 timestamptz：全部由应用层以 OffsetDateTime 绑定并写 ?::timestamptz，
--   cast 一律不落在绑定序列末位（V59 / V62 同一条纪律）。
--   SKU 行数与件数**不存列**：现算（见 outbound_order_line 的两个索引），
--   存列就一定会和明细漂移，而列表上这两个数是最容易被当场对出来的。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. outbound_order · 出库单（头）
-- ---------------------------------------------------------------------------
CREATE TABLE outbound_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：OB-<UTC 日期>-<8 位十六进制>，服务端派生，不接受调用方自填。
  order_code varchar(32) NOT NULL,

  -- 四类来源（见第二节）。TRANSFER 走真外键，其余三类走外部单号字符串。
  source_type varchar(20) NOT NULL CHECK(source_type IN
    ('SALES','PURCHASE_RETURN','TRANSFER','PRODUCTION')),
  -- 调拨来源：复合外键到 V61 的 transfer_order，同租户、同服务，FK 成立。
  -- 应用层另会校验「发货仓 = 调拨单的 from_warehouse_id」与「每个明细 SKU 在调拨单里」。
  source_transfer_order_id uuid,
  -- 外部单号：9 销售订单号 / 6 采购退货单号 / 10 加工工单号。
  -- 刻意是 varchar 而不是 uuid —— 外部系统的 id 形态由上游决定，本服务不去猜；
  -- 更重要的是它让「一个订单多张出库单」在数据上成立（分仓/分批）。
  source_external_ref varchar(64),

  -- 发货仓（蓝图「发货仓」）。出库单可以从多个仓发出，所以是筛选维度。
  warehouse_id uuid NOT NULL,

  -- 承运渠道（蓝图「承运渠道」）。待发货之前可以留空，已发货必须有（CHECK，见 4.3）。
  carrier_code varchar(64) NOT NULL DEFAULT '',

  -- 七态（见第三节）。
  status varchar(20) NOT NULL DEFAULT 'PENDING_PICKING' CHECK(status IN
    ('PENDING_PICKING','PICKING','PENDING_RECHECK','PENDING_PACKING',
     'PENDING_SHIPMENT','SHIPPED','CANCELLED')),

  -- 终态时刻：发货时刻与作废时刻各自与状态同进同出（同 V62 的理由：
  -- 「谁做的」缺一半就是「有一半动作没人负责」）。
  shipped_at timestamptz,
  cancelled_at timestamptz,
  -- 作废原因必填：取消是批量操作里最需要事后解释的一个，没有原因的取消等于没留痕。
  cancel_reason varchar(300),

  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 单号在租户内唯一：撞号会让「这单是哪张」失去唯一答案。
  UNIQUE(tenant_id,order_code),

  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,source_transfer_order_id) REFERENCES transfer_order(tenant_id,id),

  CHECK(btrim(order_code)<>''),

  -- 调拨与非调拨的两种表达互斥（第二节）：
  -- TRANSFER ↔ 有 transfer 外键；其余三类 ↔ 有外部单号。
  CHECK((source_type='TRANSFER') = (source_transfer_order_id IS NOT NULL)),
  CHECK(source_type='TRANSFER'
     OR (source_external_ref IS NOT NULL AND btrim(source_external_ref)<>'')),
  CHECK(source_transfer_order_id IS NULL
     OR (source_external_ref IS NULL AND btrim(source_external_ref)='')),

  -- 时刻与状态同进同出。
  CHECK((status='SHIPPED') = (shipped_at IS NOT NULL)),
  CHECK((status='CANCELLED') = (cancelled_at IS NOT NULL)),

  -- 取消必须写原因（4.1 的配套）。
  CHECK(status<>'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason)<>'')),

  -- 已发货必须有承运渠道：没有承运商就发不出货（4.3）。
  CHECK(status<>'SHIPPED' OR btrim(carrier_code)<>'')
);

-- 列表主查询：默认按状态 + 创建时间倒序。状态是最高频的筛选维度。
CREATE INDEX outbound_order_status_created
  ON outbound_order(tenant_id,status,created_at DESC,id);
-- 「这个仓今天有哪些出库单」（发货仓筛选）。
CREATE INDEX outbound_order_warehouse_created
  ON outbound_order(tenant_id,warehouse_id,created_at DESC,id);
-- 「这个仓里还没发出去的」（仓库 + 非终态，部分索引只覆盖热行）。
CREATE INDEX outbound_order_open_by_warehouse
  ON outbound_order(tenant_id,warehouse_id,created_at DESC)
  WHERE status NOT IN ('SHIPPED','CANCELLED');
-- 来源筛选。
CREATE INDEX outbound_order_source
  ON outbound_order(tenant_id,source_type,created_at DESC);
-- 按外部单号反查这批出库单（分仓/分批会返回多张，见第一节）。刻意**不是**唯一索引。
CREATE INDEX outbound_order_external_ref
  ON outbound_order(tenant_id,source_type,source_external_ref)
  WHERE source_external_ref IS NOT NULL;
-- 用户数据范围 SELF 收窄到「本人操作过」时走这条。
CREATE INDEX outbound_order_created_by
  ON outbound_order(tenant_id,created_by_user_id,created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. outbound_order_line · 出库单明细行（计划快照）
-- ---------------------------------------------------------------------------
-- 一行 = 一个 SKU 的一件计划出库量。quantity 是**计划量**，不是实拣量 ——
-- 实拣（实际库位/批次/序列号）由 8.3.2.1 拣货任务拥有，不在本表。
CREATE TABLE outbound_order_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  -- 行号从 1 连续递增，同单内唯一；打印与拣货单按它排序。
  line_no integer NOT NULL CHECK(line_no>0),

  -- SKU 复合外键到 V1 的 sku_ref（同租户登记的 SKU 逻辑 ID）。
  sku_id uuid NOT NULL,
  -- 可读名冻结（COPY 语义）：SKU 之后可能被改名或停用，而这张单要能回看
  -- 「当时要发的是哪一件」。与 transfer_order_line 一样不存类目/售价/成本。
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL,

  -- 计划出库件数。NUMERIC(19,6) + BigDecimal，禁浮点。
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id,line_no),
  -- 同一张出库单里同一个 SKU 只有一行：这样「SKU 行数」= 明细行数 = count(distinct sku)
  -- 三者恒相等，蓝图里那个「SKU 行数」才有唯一含义（否则一行拆两行会让它含混）。
  UNIQUE(tenant_id,order_id,sku_id),

  FOREIGN KEY(tenant_id,order_id) REFERENCES outbound_order(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),

  CHECK(btrim(sku_code)<>'' AND btrim(sku_name)<>'')
);

-- 「SKU 行数 / 件数」现算走这条（列表页每行一次聚合）。不存列，理由见文件头第五节。
CREATE INDEX outbound_order_line_order
  ON outbound_order_line(tenant_id,order_id);

-- ---------------------------------------------------------------------------
-- 3. outbound_pick_request · 拣货任务交接请求（8.3.2.1 的契约位）
-- ---------------------------------------------------------------------------
-- 为什么这张表只到「请求」为止，而不就叫 picking_task：
--   * 蓝图把 8.3.2.1「拣货任务」列为**下游**，且它拥有拣货任务表、拣货明细
--     （实际库位/批次/序列号）、路径排序与异常上报。本模块**不建**那张表，也不假装它存在。
--   * 「生成拣货任务」这个批量操作现在是真的：它校验待拣状态、写一行交接请求、
--     把状态从 待拣 推到 拣货中、并留审计事件。8.3.2.1 交付后按
--     (tenant_id, order_id) 读这张表接手，生成真正的拣货任务并回写状态。
--   * 行数与件数在这里是**一次性快照**：出库单明细是计划快照且不可改（4.4），
--     所以这个快照与计划恒等；留一份是为了 8.3.2.1 接手时不必回查就能看到交接量。
CREATE TABLE outbound_pick_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,

  -- 交接量快照（理由同上）。
  line_count integer NOT NULL CHECK(line_count>0),
  total_piece_count numeric(19,6) NOT NULL CHECK(total_piece_count>0),

  -- 交接说明：批次策略、库区要求、优先级等作业指令。
  note varchar(500) NOT NULL DEFAULT '',

  requested_by_user_id uuid NOT NULL,
  requested_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一张出库单只生成一次拣货任务（见第三节：复核返工走 PICKING 而不是退回待拣，
  -- 所以不存在「第二次生成」的合法场景）。重复请求靠这个约束 + 服务层的 409 挡住。
  UNIQUE(tenant_id,order_id),

  FOREIGN KEY(tenant_id,order_id) REFERENCES outbound_order(tenant_id,id)
);
CREATE INDEX outbound_pick_request_order
  ON outbound_pick_request(tenant_id,order_id);

-- ---------------------------------------------------------------------------
-- 4. outbound_order_event · 出库单作业留痕（append-only）
-- ---------------------------------------------------------------------------
-- 状态推进与三项批量操作都往这里留一行。它不是 8.3.1.2 的「作业时间线」页面
-- （那是下游模块的界面），但留痕这件事必须先落在库里 —— 否则取消过一次的单子
-- 和从没取消过的单子在数据上长得一模一样。
CREATE TABLE outbound_order_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,

  -- 事件类型：
  --   CREATED         建单
  --   PICK_REQUESTED  生成拣货任务（待拣 → 拣货中）
  --   STATUS_CHANGED  一般状态推进（含下游返工边）
  --   CANCELLED       作废
  --   PRINTED         打印（批量，按单留痕）
  event_type varchar(20) NOT NULL CHECK(event_type IN
    ('CREATED','PICK_REQUESTED','STATUS_CHANGED','CANCELLED','PRINTED')),
  from_status varchar(20),
  to_status varchar(20),

  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  -- 人读得懂的一句话。
  detail text NOT NULL CHECK(btrim(detail)<>''),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES outbound_order(tenant_id,id),
  -- 有「起始状态」就必须有「目标状态」：只填一半意味着读时间线的人无法判断这是一次跃迁
  -- 还是一次普通操作。CREATED 没有起始（from_status 为 NULL）但要有落点（待拣），
  -- PRINTED 两者都填（状态没变，只是一次打印动作）。
  CHECK(from_status IS NULL OR to_status IS NOT NULL)
);
-- 「这张单上发生过什么」按时间顺序读。
CREATE INDEX outbound_order_event_order
  ON outbound_order_event(tenant_id,order_id,occurred_at,id);

-- ===========================================================================
-- 5. 状态机与不变量（触发器）
-- ===========================================================================

-- 5.1 outbound_order 状态机：只认第三节的白名单。
CREATE FUNCTION guard_outbound_order_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    -- CANCELLED 的落点单独判，好给出「谁都不许取消已发货单」这条明确理由。
    IF NEW.status = 'CANCELLED' AND OLD.status = 'SHIPPED' THEN
      RAISE EXCEPTION '出库单 % 已发货，不能取消：货已经交出去了。', OLD.order_code
        USING ERRCODE='23514';
    END IF;
    IF NOT (
      (OLD.status='PENDING_PICKING'  AND NEW.status IN ('PICKING','CANCELLED')) OR
      (OLD.status='PICKING'          AND NEW.status IN ('PENDING_RECHECK','CANCELLED')) OR
      (OLD.status='PENDING_RECHECK'  AND NEW.status IN ('PENDING_PACKING','PICKING','CANCELLED')) OR
      (OLD.status='PENDING_PACKING'  AND NEW.status IN ('PENDING_SHIPMENT','PENDING_RECHECK','CANCELLED')) OR
      (OLD.status='PENDING_SHIPMENT' AND NEW.status IN ('SHIPPED','CANCELLED'))
    ) THEN
      RAISE EXCEPTION '出库单 % 状态非法跃迁：% → %。合法跃迁见 V72 第三节。',
        OLD.order_code, OLD.status, NEW.status USING ERRCODE='23514';
    END IF;
    -- 落点时刻必须随状态一起到位（CHECK 只保证「同进同出」，这里保证「到位的那个非空」）。
    IF NEW.status = 'SHIPPED' AND NEW.shipped_at IS NULL THEN
      RAISE EXCEPTION '出库单 % 置为已发货时必须带发货时刻。', OLD.order_code
        USING ERRCODE='23514';
    END IF;
    IF NEW.status = 'CANCELLED' AND NEW.cancelled_at IS NULL THEN
      RAISE EXCEPTION '出库单 % 置为取消时必须带作废时刻。', OLD.order_code
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER outbound_order_status_guard
  BEFORE UPDATE ON outbound_order
  FOR EACH ROW EXECUTE FUNCTION guard_outbound_order_status();

-- 5.2 终态冻结：已发货 / 已取消的行，业务列一律不可改。
-- 「已发货的单还能改发货仓」在现实里等于改历史；这里把它钉在 DB 层而不是靠端点自律。
CREATE FUNCTION guard_outbound_order_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('SHIPPED','CANCELLED') AND (
       NEW.source_type IS DISTINCT FROM OLD.source_type
    OR NEW.source_transfer_order_id IS DISTINCT FROM OLD.source_transfer_order_id
    OR NEW.source_external_ref IS DISTINCT FROM OLD.source_external_ref
    OR NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
    OR NEW.carrier_code IS DISTINCT FROM OLD.carrier_code
    OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id
  ) THEN
    RAISE EXCEPTION '出库单 % 处于终态（%），来源/发货仓/承运渠道/创建人不可再改。',
      OLD.order_code, OLD.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER outbound_order_frozen_guard
  BEFORE UPDATE ON outbound_order
  FOR EACH ROW EXECUTE FUNCTION guard_outbound_order_frozen();

-- 5.3 明细行不可改不可删（4.4）：出库单是计划快照，实拣由 8.3.2.1 记。
CREATE FUNCTION guard_outbound_order_line_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '出库单明细是计划快照，不允许 %：拣货实际记录在 8.3.2.1 的拣货任务里，'
    '要改计划数量请取消这张单并重开。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER outbound_order_line_immutable
  BEFORE UPDATE OR DELETE ON outbound_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_outbound_order_line_immutable();

-- 5.4 明细行只能在待拣时写入（4.5）：单子一动起来就不能再加行。
CREATE FUNCTION guard_outbound_order_line_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_status varchar(20);
BEGIN
  SELECT status INTO head_status FROM outbound_order
    WHERE tenant_id = NEW.tenant_id AND id = NEW.order_id;
  IF head_status IS NULL THEN
    RAISE EXCEPTION '出库单不存在（tenant_id=%s, order_id=%s）。', NEW.tenant_id, NEW.order_id
      USING ERRCODE='23503';
  END IF;
  IF head_status <> 'PENDING_PICKING' THEN
    RAISE EXCEPTION '出库单当前状态为 %，只有待拣的单能加明细行。', head_status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER outbound_order_line_insert_guard
  BEFORE INSERT ON outbound_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_outbound_order_line_insert();

-- 5.5 留痕表 append-only。
CREATE FUNCTION guard_outbound_order_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'outbound_order_event 是 append-only：不允许 %，'
    '出库作业回溯依赖未被改写的时间线。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER outbound_order_event_append_only
  BEFORE UPDATE OR DELETE ON outbound_order_event
  FOR EACH ROW EXECUTE FUNCTION guard_outbound_order_event_append_only();

-- ===========================================================================
-- 6. 多租户与授权（与 V1/V2/V54/V56/V59/V62 同一套写法）
-- ===========================================================================
-- 四张表都是租户业务表，一视同仁 ENABLE + FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['outbound_order','outbound_order_line',
                             'outbound_pick_request','outbound_order_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  outbound_order, outbound_order_line, outbound_pick_request, outbound_order_event
  TO warehouse_app;
-- 留痕表与交接请求是审计凭据：不给 DELETE（触发器再收一道）。
REVOKE DELETE ON outbound_pick_request, outbound_order_event FROM warehouse_app;
-- 明细行是计划快照，不给 UPDATE/DELETE（触发器同样收一道）。
REVOKE UPDATE,DELETE ON outbound_order_line FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V62 同口径）。
REVOKE ALL ON
  outbound_order, outbound_order_line, outbound_pick_request, outbound_order_event
  FROM starcon_app;