-- ===========================================================================
-- V117 · 8.3.3.1 复核扫描（warehouse 服务）
-- ===========================================================================
--
-- 蓝图 08 卷 8.3.3.1 原文：
--   职责：打包前复核：逐件扫描商品与订单核对，检出漏件/错件/多件，支持开箱复核（高价值订单）。
--   输入：扫描记录（条码/序列号）；订单明细；复核规则。
--   输出：复核通过/异常结果；异常单转人工处理。
--   关系：上游 = 8.3.2.1 拣货任务 / 8.3.2.2 波次与合并拣货；下游 = 8.3.3.2 打包。
--         **复核是错发拦截的最后机会**，序列号商品必须逐件核对。
--
-- 卷级关键约束（本迁移逐条对应）：
--   1)「复核是错发拦截的最后机会」→ guard_recount_session_status_fn 的 PASSED 前提：
--      不许有任何一条 resolution='PENDING' 的异常、不许有任一行 line_status<>'MATCHED'。
--      「先放行再补异常」这条路在这里被 DB 关死。
--   2)「序列号商品必须逐件核对」→ requires_serial 由「实拣登记了几个序列号」派生，
--      且 line_status='MATCHED' 必须 serial_match（集合相等，不是件数相等）。
--      三个序列号里塞了一个别的号，件数对得上也不算过。
--   3)「漏件/错件/多件」三种检出 → recount_exception.kind 的三值 + SERIAL_MISMATCH /
--      DUPLICATE_SCAN / UNKNOWN_BARCODE，各自有判定来源，不是同一类问题换个名字。
--   4)「支持开箱复核（高价值订单）」→ open_box + open_box_reason + recount_rule
--      .high_value_quantity_threshold；**开箱是一个带原因的显式动作**，不是默认开着。
--
-- ---------------------------------------------------------------------------
-- 1. 本迁移建什么、不建什么
-- ---------------------------------------------------------------------------
-- 建六张表：
--   recount_rule       复核规则（输入之一：复核规则）
--   recount_session    复核单头（一次复核会话 = 一张出库单的这一次复核）
--   recount_line       复核基线：应发明细的**冻结快照** + 逐行核对结论
--   recount_scan       扫描记录（输入之一：条码 / 序列号），append-only
--   recount_exception  复核异常（输出之一）+ 转人工处置
--   recount_event      append-only 作业流水
--
-- **为什么不在 outbound_order_line 上加「已复核」列**：V72 §4.3 已把明细行钉成
-- 不可改的快照（guard_outbound_order_line_immutable），V79 也逐字承诺「实拣由
-- 8.3.2.1 拥有」。复核结论是**第三种**事实（拣过了 ≠ 核过了），它既不属于计划
-- 也不属于实拣，挤进别人的表只会把三条守卫的语义搅浑。
--
-- **为什么基线要冻结在 recount_line 上而不是每次去 join outbound_order_line**：
-- 复核是一份**当时当地**的核对凭据。半年后回看「这一单当时是按什么核的」，
-- 要能答出「当时订单明细是 3 件、拣到 3 件、扫到 3 件」；而订单明细随时可能被
-- 作废重来。与 V110 冻结 sku_code / sku_name 同一纪律（COPY 语义）。
--
-- **为什么一张出库单可以有多个复核单**：复核失败 → 退回拣货（V72 的
-- PENDING_RECHECK → PICKING）→ 重拣 → 再复核。第二轮复核是**新的一次会话**，
-- 不是把第一轮改成 PASSED —— 证据链不能被追认。同一时刻只允许一个 OPEN（部分唯一索引）。
--
-- ---------------------------------------------------------------------------
-- 2. 复核对库存做了什么、没做什么
-- ---------------------------------------------------------------------------
-- **什么都不做。** 复核只读、不搬：
--   * 货在打包区（warehouse_location.type='SHIPPING'）里，on_hand / reserved 一动不动；
--   * **不写 inventory_entry**（V1 的 CHECK 也不允许 delta=0 的分录）；
--   * 不释放预占。释放是 7 库存在发货 / 取消时的事，与 8.3.2.1 同一纪律。
-- 复核抓到「拣错/漏件」时，出口是**退回拣货**（outbound_order 走
-- PENDING_RECHECK → PICKING，由 8.3.2.1 的 reopen 接住），不是本模块偷偷改库存。
--
-- ---------------------------------------------------------------------------
-- 3. 「高价值订单」为什么用件数而不是金额（一个必须说清的口径）
-- ---------------------------------------------------------------------------
-- 蓝书写的是「支持开箱复核（**高价值**订单）」。但 warehouse 服务侧
-- **根本没有金额列**：
--   * outbound_order / outbound_order_line 上没有 amount / price / value 任何一列；
--   * V72 §1 明写「与 transfer_order_line 一样不存类目/售价/成本」（COPY 语义）。
-- 金额的owner 是 9 订单履约侧，跨服务读表是被模块边界纪律禁止的。
--
-- 所以本迁移把阈值列**如实命名为 high_value_quantity_threshold（件数阈值）**，
-- 并在 recount_rule_basis 里把这条口径原文返回给调用方，而不是把件数阈值
-- 藏在列名里假装它是金额。等 9 侧真的提供了金额口径（走授权 API 或事件），
-- 再换列并同步改列名 —— 而不是现在就造一个 warehouse 侧算不出来的金额。
--
-- ---------------------------------------------------------------------------
-- 4. 序列号逐件核对为什么不能只比件数
-- ---------------------------------------------------------------------------
-- 应发 3 件、实拣 3 件、实扫 3 件 —— 件数全对就放行？**不行**：扫的可能是
-- S1、S2、S9（前两台对、第三台是另一台机器），件数一样内容不一样。
-- 所以：
--   * recount_line.scanned_serial_count 由**触发器**从 recount_scan 去重统计（改不了）；
--   * recount_line.serial_match 由服务层按**集合相等**逐个比（SQL 的两个
--     差集查询，不是 count 相等）；
--   * line_status 由 BEFORE UPDATE 触发器从「件数 + serial_match」派生，
--     且 CHECK 保证 requires_serial 的行拿不到 MATCHED 除非 serial_match。
-- 三层里前两层都不是常量：改一次扫描记录，三层都会跟着变。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. recount_rule · 复核规则
-- ---------------------------------------------------------------------------
CREATE TABLE recount_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则编码 / 名称。与 pick_wave_rule（V113）同一形状：现场按名字挑规则，
  -- 按编码对账，所以两列都必填非空。
  rule_code varchar(32) NOT NULL,
  name varchar(200) NOT NULL,

  -- 适用仓。**可空 = 全仓通用**：一张仓网可以只有一套复核口径。
  warehouse_id uuid,

  -- 「序列号商品必须逐件核对」（蓝图关系段原话）的开关。
  -- 与 requires_serial 的关系是**与**：规则说「要逐件」且实拣确实登记了序列号，
  -- 该行才进入逐件核对（见 §4）。
  require_serial_check boolean NOT NULL DEFAULT true,

  -- 高价值 → 强制开箱的**件数**阈值（见 §3）。NULL = 不按件数强制。
  high_value_quantity_threshold numeric(19,6),

  -- 低于阈值时是否仍可手动开箱：可以（reviewer_user_id 有话说就开），
  -- 但 open_box_reason 必填 —— 「为什么这一单要开箱」是必答项。
  open_box_note varchar(300) NOT NULL DEFAULT '',

  status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  retired_at timestamptz,
  retire_reason varchar(300),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rule_code),

  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  CHECK(btrim(rule_code)<>'' AND btrim(name)<>''),
  CHECK(high_value_quantity_threshold IS NULL OR high_value_quantity_threshold>0),
  -- 状态与时刻/原因同进同出：停用一条还在生效的规则却不写原因，
  -- 事后没人说得清从哪一刻起换了口径。
  CHECK(status<>'RETIRED' OR (retired_at IS NOT NULL AND retire_reason IS NOT NULL
                              AND btrim(retire_reason)<>'')),
  CHECK(status='RETIRED' OR (retired_at IS NULL AND retire_reason IS NULL))
);

-- 挑规则：按仓取，取不到就用全仓通用那条。
CREATE INDEX recount_rule_active
  ON recount_rule(tenant_id, warehouse_id, rule_code)
  WHERE status='ACTIVE';
CREATE INDEX recount_rule_code
  ON recount_rule(tenant_id, rule_code);

-- 停用只能一次、且不能复活：AFTER UPDATE 上把 RETIRED 钉死。
CREATE FUNCTION guard_recount_rule_retire_fn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status <> 'RETIRED' THEN
      RAISE EXCEPTION '复核规则 % 停用后不能复活（口径的历史必须可追）。', OLD.rule_code
        USING ERRCODE='23514';
    END IF;
    IF NEW.retired_at IS NULL OR NEW.retire_reason IS NULL OR btrim(NEW.retire_reason)='' THEN
      RAISE EXCEPTION '停用复核规则 % 必须写时刻与原因。', OLD.rule_code
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER recount_rule_retire_guard
  BEFORE UPDATE OF status ON recount_rule
  FOR EACH ROW EXECUTE FUNCTION guard_recount_rule_retire_fn();

-- ---------------------------------------------------------------------------
-- 2. recount_session · 复核单头
-- ---------------------------------------------------------------------------
CREATE TABLE recount_session (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：RS-<UTC 日期>-<8 位十六进制>，服务端派生，不接受调用方自填。
  session_code varchar(32) NOT NULL,

  -- 上游两张真外键（蓝图：上游 = 8.3.2.1 拣货任务 / 8.3.2.2 波次与合并拣货）。
  -- 8.3.2.2 的波次**不直接挂外键**：波次是排班，复核是逐单动作，
  -- 「这张复核单属于哪个波次」由 pick_task → pick_wave_task 读出来，
  -- 在表上钉一列反而会被波次改绑时静默过期。
  outbound_order_id uuid NOT NULL,
  pick_task_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,

  -- 规则可空：没配规则时用「必须逐件核对 + 不强制开箱」的内置口径，
  -- 且 verify_basis 字段如实写明「本次无规则，使用内置默认口径」——
  -- 绝不假装有一条规则。
  rule_id uuid,

  -- 开箱复核（高价值订单）。**默认不开**，开了必须有原因。
  open_box boolean NOT NULL DEFAULT false,
  open_box_reason varchar(300),
  opened_at timestamptz,
  opened_by uuid,

  -- 四态。**刻意没有「已打包」**：复核通过只是把出库单交给 8.3.3.2，
  -- 打包与称重是下游的事（与 8.3.2.1「拣货完 ≠ 已发货」同一纪律）。
  status varchar(12) NOT NULL DEFAULT 'OPEN' CHECK(status IN
    ('OPEN','PASSED','FAILED','CANCELLED')),

  reviewer_user_id uuid,

  -- 基线与实扫的合计。**唯一写入者是 refresh_recount_session_totals_fn 触发器**，
  -- 服务层只读回来，不自己算（服务层算的数与账上的数可能差一位）。
  baseline_line_count integer NOT NULL DEFAULT 0 CHECK(baseline_line_count>=0),
  baseline_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(baseline_quantity>=0),
  scanned_line_count integer NOT NULL DEFAULT 0 CHECK(scanned_line_count>=0),
  scanned_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(scanned_quantity>=0),
  matched_line_count integer NOT NULL DEFAULT 0 CHECK(matched_line_count>=0),
  mismatch_line_count integer NOT NULL DEFAULT 0 CHECK(mismatch_line_count>=0),
  serial_checked_count integer NOT NULL DEFAULT 0 CHECK(serial_checked_count>=0),
  exception_count integer NOT NULL DEFAULT 0 CHECK(exception_count>=0),
  pending_exception_count integer NOT NULL DEFAULT 0 CHECK(pending_exception_count>=0),

  -- 口径原文，随响应返回。看复核单的人有权知道「这一单是按什么口径核的」：
  -- 规则编码、序列号是否强制、高价值阈值取的是件数还是金额、基线是哪一版。
  verify_basis varchar(600) NOT NULL,

  -- **复核前出库单处在哪一档**（冻结快照）。PASSED 时守卫靠它判
  -- 「这一单本来就在待复核」—— 否则一次复核可以把一张不在复核队列里的单
  -- 直接推进到待打包。
  outbound_status_before varchar(20) NOT NULL,

  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  cancelled_at timestamptz,
  cancel_reason varchar(300),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,session_code),

  FOREIGN KEY(tenant_id,outbound_order_id) REFERENCES outbound_order(tenant_id,id),
  FOREIGN KEY(tenant_id,pick_task_id) REFERENCES pick_task(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,rule_id) REFERENCES recount_rule(tenant_id,id),

  CHECK(btrim(session_code)<>''),
  CHECK(btrim(verify_basis)<>''),
  CHECK(outbound_status_before IN
    ('PENDING_PICKING','PICKING','PENDING_RECHECK','PENDING_PACKING','PENDING_SHIPMENT')),
  -- 开箱与「为什么开箱」同进同出。
  CHECK(open_box = (opened_at IS NOT NULL AND opened_by IS NOT NULL)),
  CHECK(NOT open_box OR (open_box_reason IS NOT NULL AND btrim(open_box_reason)<>'')),
  -- 时刻与状态同进同出。
  CHECK((status IN ('PASSED','FAILED')) = (completed_at IS NOT NULL)),
  CHECK((status='CANCELLED') = (cancelled_at IS NOT NULL)),
  CHECK(status<>'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason)<>''))
);

-- 一张出库单同时只能有一个在复核中的会话。复核失败退回拣货后开第二轮，
-- 那是**新会话**，不是把第一轮改掉。
CREATE UNIQUE INDEX recount_session_open
  ON recount_session(tenant_id, outbound_order_id)
  WHERE status='OPEN';
-- 复核作业台：未完的按开始时刻排。
CREATE INDEX recount_session_open_queue
  ON recount_session(tenant_id, warehouse_id, started_at, id)
  WHERE status='OPEN';
-- 一张出库单的历次复核（返工链要能一次读完）。
CREATE INDEX recount_session_order
  ON recount_session(tenant_id, outbound_order_id, started_at DESC, id);
CREATE INDEX recount_session_code
  ON recount_session(tenant_id, session_code);
CREATE INDEX recount_session_reviewer
  ON recount_session(tenant_id, reviewer_user_id, started_at DESC, id)
  WHERE reviewer_user_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. recount_line · 复核基线（应发明细的冻结快照）+ 逐行结论
-- ---------------------------------------------------------------------------
CREATE TABLE recount_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  -- 对应的出库单明细行。**只读引用，不改**。
  outbound_line_id uuid NOT NULL,

  -- 行号从 1 连续。打印与界面按它排序 —— 与 outbound_order_line.line_no 同步冻结。
  line_no integer NOT NULL CHECK(line_no>0),

  -- SKU 冻结（COPY 语义）：商品之后改名或停用，这份复核凭据要能回看
  -- 「当时核的是哪一件」。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL,

  -- 应发件数（订单明细，V72 outbound_order_line.quantity 的冻结读数）。
  expected_quantity numeric(19,6) NOT NULL CHECK(expected_quantity>0),
  -- 实拣件数（V110 pick_task_line.picked_quantity 的冻结读数）。
  -- 两者不等本身就是一个现场事实（拣漏了 / 多拣了），界面要能一眼看出。
  picked_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(picked_quantity>=0),

  -- 实拣登记了几个序列号（V110 pick_task_serial 的计数）。
  expected_serial_count integer NOT NULL DEFAULT 0 CHECK(expected_serial_count>=0),
  -- 是否进入逐件核对 = 规则要求 且 实拣确实登记了序列号（见文件头 §4）。
  -- NOT NULL 且由服务层在开单时从真实数据派生，不接受调用方指定。
  requires_serial boolean NOT NULL DEFAULT false,

  -- 实扫件数与实扫序列号个数：**唯一写入者是 refresh_recount_line_counts_fn 触发器**，
  -- 它按 scanned_value 去重统计（重扫不重复计数，重扫另记 DUPLICATE 异常）。
  scanned_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(scanned_quantity>=0),
  scanned_serial_count integer NOT NULL DEFAULT 0 CHECK(scanned_serial_count>=0),

  -- 序列号集合是否与实拣集合**相等**（两个差集都空）。由服务层按真实 SQL 计算。
  serial_match boolean NOT NULL DEFAULT false,

  -- 五态由 BEFORE UPDATE 触发器派生，应用层写不出与数量对不上的状态。
  line_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(line_status IN
    ('PENDING','MATCHED','MISSING','OVER','MISMATCH')),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,session_id,line_no),
  UNIQUE(tenant_id,session_id,outbound_line_id),

  FOREIGN KEY(tenant_id,session_id) REFERENCES recount_session(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_line_id) REFERENCES outbound_order_line(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),

  CHECK(btrim(sku_code)<>'' AND btrim(sku_name)<>''),
  -- 核过的序列号不可能多于实拣登记的序列号：多出来的那一个不是这个包裹的货。
  CHECK(NOT requires_serial OR scanned_serial_count<=expected_serial_count),
  -- 逐件核对的行必须真的逐件核过才能 MATCHED。
  CHECK(NOT requires_serial OR line_status<>'MATCHED' OR serial_match)
);

CREATE INDEX recount_line_session
  ON recount_line(tenant_id, session_id, line_no);
-- 「还有哪些行没过」：作业员只关心没核齐的那几行。
CREATE INDEX recount_line_open
  ON recount_line(tenant_id, session_id, line_status, line_no)
  WHERE line_status<>'MATCHED';

-- 3.1 实扫计数：由扫描记录派生，改不了。
--     去重键是 scanned_value —— 同一个号扫第二遍**不**把件数加到 2，
--     它会走 recount_scan_occurrence_fn 变成一条 DUPLICATE 异常。
CREATE FUNCTION refresh_recount_line_counts_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_session uuid;
BEGIN
  v_session := NEW.session_id;

  --
  -- **两种扫描的计件口径不一样，混在一起算就是错的**：
  --   * 条码：一扫一件。同一个条码扫三遍就是**三件**（三台同型号的机器），
  --     按值去重会把三件算成一件 —— 那会把「扫齐了」说成「还差两件」，
  --     作业员会一直扫到系统说够了为止（而实际多扫的已经进箱了）。
  --   * 序列号：一件一号。扫第二遍是**重复件**，必须去重并另记 DUPLICATE 异常，
  --     否则「扫了两遍」会被算成两件，把重复件伪装成正常件数。
  UPDATE recount_line l
     SET scanned_quantity = d.qty,
         scanned_serial_count = d.ser
    FROM (
      SELECT outbound_line_id, sum(qty) AS qty, sum(ser) AS ser
        FROM (
          SELECT outbound_line_id,
                 sum(quantity) AS qty,
                 0::bigint    AS ser
            FROM recount_scan
           WHERE session_id = v_session AND outbound_line_id IS NOT NULL
             AND scan_type = 'BARCODE'
           GROUP BY outbound_line_id
          UNION ALL
          SELECT outbound_line_id,
                 -- 每个**不同的**序列号就是一件实物，件数与序列号个数同源。
                 -- 这里必须同时贡献给 qty 与 ser：只贡献 ser 会让「只扫序列号」的行
                 -- 件数恒为 0，于是每一行都被判成漏件 —— 恰恰是逐件核对品最常见的形态。
                 count(DISTINCT scanned_value)::numeric,
                 count(DISTINCT scanned_value)
            FROM recount_scan
           WHERE session_id = v_session AND outbound_line_id IS NOT NULL
             AND scan_type = 'SERIAL' AND verdict <> 'DUPLICATE'
           GROUP BY outbound_line_id
        ) u
       GROUP BY outbound_line_id
    ) d
   WHERE l.session_id = v_session AND l.outbound_line_id = d.outbound_line_id;

  -- 一件都没扫到的行归零（否则上一轮的读数会留在原地）。
  UPDATE recount_line l
     SET scanned_quantity = 0, scanned_serial_count = 0
   WHERE l.session_id = v_session
     AND NOT EXISTS (SELECT 1 FROM recount_scan s
                      WHERE s.session_id = v_session
                        AND s.outbound_line_id = l.outbound_line_id);
  RETURN NULL;
END $$;
-- 本函数的触发器挂在 recount_scan 上，**必须等第 4 节把 recount_scan 建出来之后**
-- 才能 CREATE TRIGGER（CREATE TRIGGER 要求目标表已存在）。挂载见第 4 节末尾。

-- 3.2 行状态派生：件数 + 序列号集合，两个数都不是常量。
CREATE FUNCTION guard_recount_line_status_fn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.requires_serial AND NOT NEW.serial_match THEN
    -- 序列号集合对不上：这是「错件」最典型的形态，优先级高于件数 ——
    -- 三件对了两件半，不如说「有一个号不对」，那才是作业员要查的事。
    NEW.line_status := 'MISMATCH';
  ELSIF NEW.scanned_quantity > NEW.expected_quantity THEN
    NEW.line_status := 'OVER';
  ELSIF NEW.scanned_quantity < NEW.expected_quantity THEN
    NEW.line_status := 'MISSING';
  ELSE
    NEW.line_status := 'MATCHED';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER recount_line_status_guard
  BEFORE UPDATE ON recount_line
  FOR EACH ROW EXECUTE FUNCTION guard_recount_line_status_fn();

-- ---------------------------------------------------------------------------
-- 4. recount_scan · 扫描记录（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE recount_scan (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  -- 条码 / 序列号。蓝图输入原话就是「扫描记录（条码/序列号）」，
  -- 所以两值枚举而不是一个混着来的字符串。
  scan_type varchar(12) NOT NULL CHECK(scan_type IN ('BARCODE','SERIAL')),
  -- 字面量：条码或序列号原文。**大小写敏感**（序列号是铭牌上的字面量，
  -- 与 V58 inventory_serial 同一纪律，不做 upper() 归一）。
  scanned_value varchar(128) NOT NULL,

  -- 同一会话内同一值第几次扫到。**由触发器赋值**（count+1），服务层写不了
  -- 「这个号是第一次」这种话。
  scan_occurrence integer NOT NULL DEFAULT 1 CHECK(scan_occurrence>=1),

  -- 解析结果。UNKNOWN 条码时 sku_id 为 NULL —— 宁可空着让作业员看见
  -- 「这是个不认识的码」，也不猜一个 SKU 填上去。
  sku_id uuid,
  sku_code varchar(200) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  serial_id uuid,
  serial_number varchar(128),

  -- 命中关系：扫到的东西对上了哪一行、哪一次实拣。对不上（多件/错件/未知）时为空。
  pick_id uuid,
  outbound_line_id uuid,

  -- 条码件每扫一次记 1；序列号件每扫一次记 1。NUMERIC(19,6)，禁浮点。
  quantity numeric(19,6) NOT NULL DEFAULT 1 CHECK(quantity>0),

  -- 判定：命中 / 错件 / 未知码 / 重复扫。**DUPLICATE 由触发器强制**：
  -- 只要同一值在本会话扫过第二次，结论就只能是 DUPLICATE。
  verdict varchar(16) NOT NULL CHECK(verdict IN
    ('MATCHED','WRONG_SKU','UNKNOWN','DUPLICATE')),

  operator_user_id uuid NOT NULL,
  -- 业务时间：由作业端传入（PDA 时钟），不是 DB 的 now()。
  scanned_at timestamptz NOT NULL,
  note varchar(300) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,session_id,scan_type,scanned_value,scan_occurrence),

  FOREIGN KEY(tenant_id,session_id) REFERENCES recount_session(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,serial_id) REFERENCES inventory_serial(tenant_id,id),
  FOREIGN KEY(tenant_id,pick_id) REFERENCES pick_task_pick(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_line_id) REFERENCES outbound_order_line(tenant_id,id),

  CHECK(btrim(scanned_value)<>''),
  -- 序列号件必须解析出一个序列号；条码件不许带 serial_id
  -- （两个混着写，「这一行到底是按号核的还是按码核的」就答不出来了）。
  CHECK((scan_type='SERIAL') = (serial_id IS NOT NULL)),
  CHECK(scan_type<>'SERIAL' OR btrim(serial_number)<>''),
  -- 判定与解析结果必须配套：UNKNOWN 就不能同时声称解析出了 SKU。
  CHECK(verdict<>'UNKNOWN' OR sku_id IS NULL)
);

CREATE INDEX recount_scan_session
  ON recount_scan(tenant_id, session_id, scanned_at, id);
-- 「这个值在本会话扫过没有」：重复扫描判定与去重统计都走它。
CREATE INDEX recount_scan_value
  ON recount_scan(tenant_id, session_id, scan_type, scanned_value);
CREATE INDEX recount_scan_line
  ON recount_scan(tenant_id, session_id, outbound_line_id)
  WHERE outbound_line_id IS NOT NULL;

-- 4.1 扫描是事实凭据：append-only，重扫是**新的一行**而不是改旧行。
CREATE FUNCTION guard_recount_scan_append_only_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '扫描记录是 append-only 的事实凭据，不许 UPDATE/DELETE（scan_id=%）。欲重扫请再扫一次。',
    OLD.id USING ERRCODE='23514';
END $$;
CREATE TRIGGER recount_scan_append_only
  BEFORE UPDATE OR DELETE ON recount_scan
  FOR EACH ROW EXECUTE FUNCTION guard_recount_scan_append_only_fn();

-- 4.1b 挂上第 3.1 节的行计数派生（表建出来了才挂得上，见该处的说明）。
--     顺序有意义：先赋值/判重（4.2 的 BEFORE INSERT），再由 AFTER INSERT 回灌行计数，
--     行状态随后由 recount_line_status_guard 派生。
CREATE TRIGGER recount_line_counts_refresh
  AFTER INSERT ON recount_scan
  FOR EACH ROW EXECUTE FUNCTION refresh_recount_line_counts_fn();

-- 4.2 第几次扫到 + DUPLICATE 结论，**由 DB 判定**，绕开服务层也判得对。
CREATE FUNCTION recount_scan_occurrence_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_prior int; v_open boolean;
BEGIN
  SELECT count(*) INTO v_prior
    FROM recount_scan
   WHERE session_id = NEW.session_id
     AND scan_type = NEW.scan_type
     AND scanned_value = NEW.scanned_value;

  NEW.scan_occurrence := v_prior + 1;
  -- **只有序列号才存在「重复」**：一件一号，同一个号在这个包裹里出现第二遍
  -- 要么是塞了两件，要么是扫错了重来，两种都该被看见。
  -- 条码扫第二遍是正常的（又一件同型号的），当成重复会把正常件报成异常。
  IF NEW.scan_type = 'SERIAL' AND v_prior >= 1 THEN
    NEW.verdict := 'DUPLICATE';
  END IF;

  SELECT status = 'OPEN' INTO v_open FROM recount_session WHERE id = NEW.session_id;
  IF NOT COALESCE(v_open, false) THEN
    RAISE EXCEPTION '复核单不在复核中，不能再扫。' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER recount_scan_occurrence
  BEFORE INSERT ON recount_scan
  FOR EACH ROW EXECUTE FUNCTION recount_scan_occurrence_fn();

-- ---------------------------------------------------------------------------
-- 5. recount_exception · 复核异常（漏件 / 错件 / 多件）+ 转人工
-- ---------------------------------------------------------------------------
CREATE TABLE recount_exception (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  -- 触发它的扫描记录。**漏件没有对应扫描**（东西压根没来），所以可空；
  -- 多件/错件/重复扫/未知码必有。
  scan_id uuid,

  -- 六类检出，各有判定来源，不是同一类问题换个名字：
  --   MISSING_ITEM    漏件：该发的行没扫够
  --   WRONG_ITEM      错件：扫到的东西 SKU 与该行不符 / 序列号集合不相等
  --   EXTRA_ITEM      多件：扫到的东西这张单压根没有
  --   DUPLICATE_SCAN  重复扫：同一个值第二次起
  --   SERIAL_MISMATCH 序列号集合与实拣集合不相等（可与 MISSING 并存）
  --   UNKNOWN_BARCODE 未知码：扫出来的码在本仓解析不出 SKU
  kind varchar(20) NOT NULL CHECK(kind IN
    ('MISSING_ITEM','WRONG_ITEM','EXTRA_ITEM','DUPLICATE_SCAN','SERIAL_MISMATCH','UNKNOWN_BARCODE')),

  outbound_line_id uuid,
  sku_id uuid,
  sku_code varchar(200) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- 序列号类异常具体到号：只说「序列号不对」等于让人自己去翻三个号。
  serial_id uuid,
  serial_number varchar(128),

  -- 应发与实扫的两个数。缺件必填 expected/scanned 才有「少了几件」；
  -- 重复扫这类不涉及数量的，expected 为空。
  expected_quantity numeric(19,6),
  scanned_quantity numeric(19,6),

  -- 处置。**PENDING 是「还没人管」**，只有 PENDING 被清干净才许判通过
  -- （见 §6.1 守卫）。MANUAL = 转人工处理（蓝图输出原话）。
  resolution varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(resolution IN
    ('PENDING','MANUAL','CORRECTED','ACCEPTED','IGNORED')),

  -- 转人工与人工结论必填：转人工不给理由 = 甩给下游而没人接。
  manual_note varchar(300),
  resolved_at timestamptz,
  resolved_by uuid,

  -- 一句人读得懂的话（必填）。半年后回看「这单当时为什么没过」靠它。
  detail varchar(500) NOT NULL,
  raised_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  FOREIGN KEY(tenant_id,session_id) REFERENCES recount_session(tenant_id,id),
  FOREIGN KEY(tenant_id,scan_id) REFERENCES recount_scan(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_line_id) REFERENCES outbound_order_line(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,serial_id) REFERENCES inventory_serial(tenant_id,id),

  CHECK(btrim(detail)<>''),
  CHECK(expected_quantity IS NULL OR expected_quantity>=0),
  CHECK(scanned_quantity IS NULL OR scanned_quantity>=0),
  -- 漏件必须同时有「应发」与「实扫」，否则「少了几件」答不出来。
  CHECK(kind<>'MISSING_ITEM' OR (expected_quantity IS NOT NULL AND scanned_quantity IS NOT NULL
                                 AND expected_quantity>scanned_quantity)),
  -- 有数量对比的两类（漏件/多件）必须有 scan（多出来的那一件确实被扫到了）。
  CHECK(kind NOT IN ('EXTRA_ITEM','DUPLICATE_SCAN','UNKNOWN_BARCODE') OR scan_id IS NOT NULL),
  -- 处置与时刻/责任人同进同出。
  CHECK((resolution='PENDING') = (resolved_at IS NULL AND resolved_by IS NULL)),
  CHECK(resolution<>'PENDING' OR manual_note IS NULL),
  -- 转人工与人工处置必须写下处置意见（CORRECTED / ACCEPTED 尤其不能空着过）。
  CHECK(resolution NOT IN ('MANUAL','CORRECTED','ACCEPTED')
     OR (manual_note IS NOT NULL AND btrim(manual_note)<>''))
);

CREATE INDEX recount_exception_session
  ON recount_exception(tenant_id, session_id, kind, raised_at, id);
-- 「这单还有什么没处理」：判通过前必须空。
CREATE INDEX recount_exception_pending
  ON recount_exception(tenant_id, session_id, raised_at, id)
  WHERE resolution='PENDING';
CREATE INDEX recount_exception_line
  ON recount_exception(tenant_id, session_id, outbound_line_id)
  WHERE outbound_line_id IS NOT NULL;

-- 处置只能往前走：PENDING → MANUAL / CORRECTED / ACCEPTED / IGNORED 一次到位，
-- 不许从一个已处置的状态再改到另一个（那会让人在一张复核单上反复涂改事实）。
CREATE FUNCTION guard_recount_exception_resolution_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.resolution IS DISTINCT FROM OLD.resolution AND OLD.resolution <> 'PENDING' THEN
    RAISE EXCEPTION '复核异常 % 已经处置成 %，不能改成 %（事实只能处置一次）。',
      OLD.id, OLD.resolution, NEW.resolution
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER recount_exception_resolution_guard
  BEFORE UPDATE OF resolution ON recount_exception
  FOR EACH ROW EXECUTE FUNCTION guard_recount_exception_resolution_fn();

-- ---------------------------------------------------------------------------
-- 6. recount_event · append-only 复核流水
-- ---------------------------------------------------------------------------
CREATE TABLE recount_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN
    ('OPENED','OPEN_BOX','SCANNED','EXCEPTION_RAISED','ESCALATED','RESOLVED',
     'PASSED','FAILED','CANCELLED')),
  from_status varchar(12),
  to_status varchar(12),
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  detail text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,session_id) REFERENCES recount_session(tenant_id,id),
  CHECK(from_status IS NULL OR to_status IS NOT NULL),
  CHECK(btrim(detail)<>'')
);
CREATE INDEX recount_event_session
  ON recount_event(tenant_id, session_id, occurred_at, id);

CREATE FUNCTION guard_recount_event_append_only_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '复核流水是 append-only 的事实凭据，不许 UPDATE/DELETE（event_id=%）。', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER recount_event_append_only
  BEFORE UPDATE OR DELETE ON recount_event
  FOR EACH ROW EXECUTE FUNCTION guard_recount_event_append_only_fn();

-- ---------------------------------------------------------------------------
-- 7. DB 级不变量（绕过服务层直连 SQL 也照样拒）
-- ---------------------------------------------------------------------------

-- 7.1 复核单状态机 + 「通过 = 真的全核过了」
--
-- 「复核是错发拦截的最后机会」这句话在 DB 层的落点就是这一段：
-- PASSED 要求**同时**满足四件事，缺一不可 ——
--   a) 出库单复核前确实在 PENDING_RECHECK（不是从别的状态硬推过来的）；
--   b) 每一行 line_status='MATCHED'（含序列号集合相等）；
--   c) 没有一条 resolution='PENDING' 的异常（「先放行再补异常」在此关死）；
--   d) 有复核人与完成时刻（「谁做的」缺一半就是有一半动作没人负责）。
CREATE FUNCTION guard_recount_session_status_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE v_bad int; v_pending int; v_lines int;
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    -- 合法跃迁：OPEN → PASSED / FAILED / CANCELLED。
    -- 没有 FAILED → OPEN（判失败后要重核，是**新的一张复核单**），
    -- 也没有任何终态 → 别的终态（一张复核单只有一个结局）。
    IF NOT (OLD.status='OPEN' AND NEW.status IN ('PASSED','FAILED','CANCELLED')) THEN
      RAISE EXCEPTION '复核单 % 不能从 % 变成 %（合法跃迁：OPEN→PASSED/FAILED/CANCELLED）。',
        OLD.session_code, OLD.status, NEW.status
        USING ERRCODE='23514';
    END IF;
  END IF;

  IF NEW.status = 'PASSED' THEN
    IF NEW.outbound_status_before <> 'PENDING_RECHECK' THEN
      RAISE EXCEPTION '复核单 % 接手时出库单处于 %，不是「待复核」，不许判通过。',
        OLD.session_code, NEW.outbound_status_before
        USING ERRCODE='23514';
    END IF;
    IF NEW.reviewer_user_id IS NULL THEN
      RAISE EXCEPTION '复核单 % 判通过必须有复核人。', OLD.session_code
        USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO v_lines FROM recount_line WHERE session_id = NEW.id;
    IF v_lines = 0 THEN
      RAISE EXCEPTION '复核单 % 一行基线都没有，不能判通过。', OLD.session_code
        USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO v_bad FROM recount_line
     WHERE session_id = NEW.id AND line_status <> 'MATCHED';
    IF v_bad > 0 THEN
      RAISE EXCEPTION '复核单 % 还有 % 行没核过（漏件/错件/多件未清），不能判通过 —— '
                      '复核是错发拦截的最后机会，放行等于把错件发出去。',
        OLD.session_code, v_bad
        USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO v_pending FROM recount_exception
     WHERE session_id = NEW.id AND resolution = 'PENDING';
    IF v_pending > 0 THEN
      RAISE EXCEPTION '复核单 % 还有 % 条异常没人处理，不能判通过。', OLD.session_code, v_pending
        USING ERRCODE='23514';
    END IF;
  END IF;

  IF NEW.status = 'FAILED' THEN
    -- 判失败却一条异常都没有 = 「没过」但说不出为什么，过的是谁、错的是哪件。
    SELECT count(*) INTO v_pending FROM recount_exception WHERE session_id = NEW.id;
    IF v_pending = 0 THEN
      RAISE EXCEPTION '复核单 % 判失败却没有任何异常记录 —— 说不出错在哪件，不接受。',
        OLD.session_code USING ERRCODE='23514';
    END IF;
    IF NEW.reviewer_user_id IS NULL THEN
      RAISE EXCEPTION '复核单 % 判失败必须有复核人。', OLD.session_code USING ERRCODE='23514';
    END IF;
  END IF;

  RETURN NEW;
END $$;
CREATE TRIGGER recount_session_status_guard
  BEFORE UPDATE ON recount_session
  FOR EACH ROW EXECUTE FUNCTION guard_recount_session_status_fn();

-- 7.2 复核单的合计：从行与异常派生，服务层算的数不算数。
CREATE FUNCTION refresh_recount_session_totals_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE v_session uuid;
BEGIN
  v_session := NEW.session_id;

  UPDATE recount_session s
     SET baseline_line_count  = COALESCE(t.lines, 0),
         baseline_quantity    = COALESCE(t.expected_qty, 0),
         scanned_line_count   = COALESCE(t.scanned_lines, 0),
         scanned_quantity     = COALESCE(t.scanned_qty, 0),
         matched_line_count   = COALESCE(t.matched, 0),
         mismatch_line_count  = COALESCE(t.bad, 0),
         serial_checked_count = COALESCE(t.ser_lines, 0),
         updated_at           = now()
    FROM (
      SELECT count(*) AS lines,
             sum(expected_quantity) AS expected_qty,
             count(*) FILTER (WHERE scanned_quantity>0) AS scanned_lines,
             sum(scanned_quantity) AS scanned_qty,
             count(*) FILTER (WHERE line_status='MATCHED') AS matched,
             count(*) FILTER (WHERE line_status<>'MATCHED') AS bad,
             count(*) FILTER (WHERE requires_serial) AS ser_lines
        FROM recount_line WHERE session_id = v_session
    ) t
   WHERE s.id = v_session;

  -- 基线清空时把合计也归零（否则残留上一轮的数）。
  UPDATE recount_session s
     SET baseline_line_count=0, baseline_quantity=0, updated_at=now()
   WHERE s.id = v_session
     AND NOT EXISTS (SELECT 1 FROM recount_line WHERE session_id = v_session);
  RETURN NULL;
END $$;
CREATE TRIGGER recount_session_totals_from_line
  AFTER INSERT OR UPDATE OR DELETE ON recount_line
  FOR EACH ROW EXECUTE FUNCTION refresh_recount_session_totals_fn();

CREATE FUNCTION refresh_recount_session_exception_totals_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE v_session uuid;
BEGIN
  v_session := NEW.session_id;
  UPDATE recount_session s
     SET exception_count = COALESCE(e.cnt, 0),
         pending_exception_count = COALESCE(e.pend, 0),
         updated_at = now()
    FROM (
      SELECT count(*) AS cnt,
             count(*) FILTER (WHERE resolution='PENDING') AS pend
        FROM recount_exception WHERE session_id = v_session
    ) e
   WHERE s.id = v_session;
  RETURN NULL;
END $$;
CREATE TRIGGER recount_session_totals_from_exception
  AFTER INSERT OR UPDATE OR DELETE ON recount_exception
  FOR EACH ROW EXECUTE FUNCTION refresh_recount_session_exception_totals_fn();

-- ===========================================================================
-- 8. 多租户与授权（与 V1 第 9 节、V72 第 6 节、V110 第 8 节同一条纪律）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['recount_rule','recount_session','recount_line','recount_scan',
                              'recount_exception','recount_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  recount_rule, recount_session, recount_line, recount_scan, recount_exception, recount_event
  TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V90、V110 同口径）。