-- 8.2.1.1 收货单。
--
-- 蓝图 08-warehouse-operations.md 8.2.1.1 原文：
--   职责：执行到货收货：选择来源（采购/退货/调拨/平台仓回货），录入实收数量与差异，
--         上传收货凭证，生成入库分录。
--   输入：来源单据（6 采购单/11 退货授权/7.5 调拨单）；实收明细（SKU、数量、批次、序列号、效期）；
--         差异原因。
--   输出：收货单（草稿/已收/部分收/已关闭）+ 库存入库分录（转入待检或可售 bucket）。
--   关系：上游 = 6 采购、11 售后、7.5.1.1；下游 = 7.1.2.1 流水、6.3.2.2（更新已收）、12 应付
--         （收货确认）。**预期与实收分开记录**，超收/少收/拒收都要留痕并回写采购单。
--
-- ===========================================================================
-- 一、本模块最核心的一条语义：**预期与实收分开记录**
-- ===========================================================================
-- 货没到齐是常态，而「货没到齐」和「账记错了」必须能区分开。所以：
--
--   goods_receipt_line.expected_quantity   预期（来源单据承诺的数量，建单时定下）
--   goods_receipt_line.received_quantity   累计实收（每次 receive 累加）
--   goods_receipt_line.rejected_quantity   累计拒收（现场明确不要的部分，不入库）
--
-- 三者**分行存、永不互相覆盖**。收 60/预期 100 与 收 100/预期 100 在库里是不同的行，
-- 「这单还差多少没到」可以直接相减答出来，而不需要从事件流里回放重建。
--
-- 收货动作**不做**的是：不回写来源单据的「已收」列。6.3.2.2 与 11.2.2 尚未交付，
-- 本模块在 goods_receipt.source_ref_id 上如实记下来源单据 id，
-- 上游交付后由上游订阅收货事件更新自己的「已收」——反向写别人的表会越过模块边界纪律
-- （AGENTS.md：每张事实表只有一个写入所有者）。
--
-- ===========================================================================
-- 二、差异：本模块只**记录事实**，不处理
-- ===========================================================================
-- 蓝图把「记录差异」放在 8.2.1.1、把「处理差异」放在 8.2.1.2（短少、破损、型号不符、
-- 过期、错发；拍照留证、拒收、部分接收、索赔发起）。所以本迁移的
-- goods_receipt_discrepancy **只有事实列**：差异类型、数量、原因码、凭证说明、记录人与时刻。
--
-- 它**刻意没有** 处理状态、处理决定、索赔单号、审批列 —— 那是 8.2.1.2 的流程资产，
-- 现在建一个空的处理状态机，等于给下游预留一个谁都能写的半成品状态。
-- /capabilities 端点会如实声明差异处理流程归属 8.2.1.2 且尚未交付。
--
-- 「拒收不入库」在本模块就已经发生：rejected_quantity 的那部分**根本不写分录**，
-- 不进 inventory_balance / stock_bucket。8.2.1.2 接手的是「这批拒收货怎么索赔、
-- 要不要改判部分接收」，而不是「要不要把它从库存里删掉」—— 它从没进过库存。
--
-- ===========================================================================
-- 三、为什么收货写 kind='RECEIPT' 而不是新增 kind
-- ===========================================================================
-- V1 的 inventory_transaction.kind CHECK 里已经有 'RECEIPT'：
--     'RECEIPT','SHIPMENT','ADJUSTMENT','TRANSFER_OUT','TRANSFER_IN','RETURN',
--     'RESERVE','RELEASE'
-- 它此前没有任何写入方，是 V1 留下的空位；收货正是它的本义。
-- **加一个新 kind 会同时改 V1 的 CHECK 约束面** —— 那是 7.1.2.1 台账、7.1.1.1 总览等
-- 一堆既有模块共同依赖的表，一条 CHECK 变更会让并行车道上的既有查询与回归测试一起冒烟。
-- 用既有 kind 是零迁移风险的唯一选项，也确实语义正确。
--
-- ===========================================================================
-- 四、与既有库存表的关系：本迁移**只插不改**
-- ===========================================================================
-- 本迁移**不修改** inventory_entry / stock_bucket / inventory_balance 的任何列、约束或触发器 ——
-- 只按既有入账路径（与 V61 调拨实收同款）追加带符号增量：
--   inventory_transaction(kind='RECEIPT') + inventory_entry(on_hand_delta>0)
--   + inventory_balance.on_hand += qty + stock_bucket.on_hand += qty
--
-- 用**带符号增量**而不是覆盖式写回：覆盖式写回（set on_hand = 期望值）会把收货期间
-- 真实发生的其他交易（同时在跑的出库、预占、盘点调整）整段抹掉。
-- 既有 V2 守恒触发器要求「改余额必须同时改库位明细」，所以余额与库位两处都要增量写。
--
-- ===========================================================================
-- 五、超收必须留痕（DB 级钉死）
-- ===========================================================================
-- 蓝图点名：「超收/少收/拒收都要留痕」。少收与拒收天然留痕（收不到就是不收，
-- 少的那部分在 received_quantity 的差额里看得见）；超收则危险 —— 它是一笔**凭空多出来的货**，
-- 如果可以无声地收进去，账实差异就再也查不出来了。
--
-- 所以延迟约束触发器 guard_receipt_line_overage 钉死：
--     实收 + 拒收 > 预期  ⟹  必须存在该行的一条 OVER_RECEIPT 差异记录
-- 延迟到提交时执行，于是「先写差异再收数量」与「先收数量再写差异」都成立。
-- ===========================================================================

CREATE SEQUENCE goods_receipt_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 收货单头（草稿 → 部分收 → 已收 → 已关闭）
-- ---------------------------------------------------------------------------
CREATE TABLE goods_receipt (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  receipt_code varchar(32) NOT NULL,
  -- 来源类型。四类里只有 TRANSFER 的上游（7.5.1.1 调拨单）已交付，
  -- 另三类按仓库既有约定在 /capabilities 里显式报 available=false 并给出原因，
  -- **不建占位表、不返回空集冒充成功**。
  source_type varchar(24) NOT NULL CHECK(source_type IN (
    'PURCHASE','RETURN_AUTH','TRANSFER','PLATFORM_WAREHOUSE')),
  -- 来源单据 id：只记逻辑引用。跨服务不建外键（sku 属 catalog、6/11 属 trade，
  -- 按 AGENTS.md 禁止跨服务 FK）。source_ref_code 是可读快照，
  -- 让「这批货是哪张单来的」在来源系统不可达时仍然可读。
  source_ref_id uuid,
  source_ref_code varchar(64) NOT NULL DEFAULT '',
  warehouse_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
    'DRAFT','PARTIALLY_RECEIVED','RECEIVED','CLOSED')),
  -- 收货凭证：承运商与运单/箱单号。蓝图「上传收货凭证」在文本侧的可核形态 ——
  -- 实物照片属 8.2.1.2 的留证范围，本表只承载承运与单号这两个可检索字段。
  carrier varchar(100) NOT NULL DEFAULT '',
  waybill_no varchar(64) NOT NULL DEFAULT '',
  evidence_note text NOT NULL DEFAULT '',
  close_reason varchar(200) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  closed_at timestamptz,
  closed_by uuid,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,receipt_code),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(receipt_code)<>''),
  -- 引用来源单据时必须带上单据 id 与可读码，否则「这张收货单从哪来」永远答不出来。
  CHECK(source_ref_id IS NOT NULL OR btrim(source_ref_code)>'')
);

-- ---------------------------------------------------------------------------
-- 2. 收货单明细行（预期 × SKU × 落库质量状态）
-- ---------------------------------------------------------------------------
-- 按 SKU × **落库质量状态**分行：收货的实收量要转入待检或可售 bucket，
-- 而同一个 SKU 的两行落到两个不同的 quality_status_id 上，是两种不同的实物状态。
-- 不按 SKU 单行分组的话，「这批货现在在待检还是在可售」无法从明细行答出来。
--
-- expected_quantity 预期量（建单时定下，之后只读）
-- received_quantity 累计实收量（每次 receive 累加，**带符号增量**，不回写覆盖）
-- rejected_quantity 累计拒收量（现场明确不要的部分，**不写分录、不入库**）
CREATE TABLE goods_receipt_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  quality_status_id uuid NOT NULL,
  expected_quantity numeric(19,6) NOT NULL,
  received_quantity numeric(19,6) NOT NULL DEFAULT 0,
  rejected_quantity numeric(19,6) NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,receipt_id,sku_id,quality_status_id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES goods_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  CHECK(expected_quantity>0),
  CHECK(received_quantity>=0),
  CHECK(rejected_quantity>=0)
);

-- ---------------------------------------------------------------------------
-- 3. 实收事件（一次 receive = 一行；逐笔留痕）
-- ---------------------------------------------------------------------------
-- 必须**逐笔**留痕：只记「总共收了 60」就答不出「这 60 是哪几笔到的」，
-- 而货损追责、批次效期核对与在途逾期的起点都是那几笔到达时间。
--
-- location_id 必填：实收必须能落到一个库位，否则目标仓的余额行有 bucket 时会因为
-- V2 的守恒触发器无处安放而失败，且「货到了哪个库位」本来就是收货单据必须回答的问题。
CREATE TABLE goods_receipt_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  received_at timestamptz NOT NULL,
  location_id uuid NOT NULL,
  -- 承运单号：逐笔记，允许同一次收货多车到货各有单号。
  waybill_no varchar(64) NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES goods_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_location(tenant_id,id)
);
CREATE INDEX goods_receipt_event_receipt ON goods_receipt_event(tenant_id,receipt_id,received_at);

-- ---------------------------------------------------------------------------
-- 4. 实收明细行（每次事件的 SKU 级实收：批次、效期、实收与拒收量）
-- ---------------------------------------------------------------------------
-- 批次与效期是**逐次实收**的属性而不是收货单行的属性：同一 SKU 分三车到货，
-- 三车可以是三个批次、三个效期。挂在单行上会逼着现场把三车的批次信息混成一个假的均值。
CREATE TABLE goods_receipt_event_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  event_id uuid NOT NULL,
  line_id uuid NOT NULL,
  received_quantity numeric(19,6) NOT NULL,
  rejected_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- lot_id 可空：非批次管理品没有批次。给了落该批次，不给落无批次 bucket。
  lot_id uuid,
  -- 效期（date 而非 timestamptz）：效期是日历日概念，
  -- 用 timestamptz 会让「2027-03-01 过期」在时区换算后漂成 02-28 或 03-02。
  expiry_date date,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,event_id) REFERENCES goods_receipt_event(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES goods_receipt_line(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  CHECK(received_quantity>0 OR rejected_quantity>0),
  CHECK(rejected_quantity>=0)
);
CREATE INDEX goods_receipt_event_line_event ON goods_receipt_event_line(tenant_id,event_id);
CREATE INDEX goods_receipt_event_line_line ON goods_receipt_event_line(tenant_id,line_id);

-- ---------------------------------------------------------------------------
-- 5. 实收序列号（离散件逐号登记）
-- ---------------------------------------------------------------------------
-- 序列号**逐行**存而不是塞进数组列：「这批货到底动了哪几个号」是一次 group by，
-- 而不是把号塞进一个 text[] 再去 unnest（与 7.3.1.x inventory_serial_movement 同一条纪律）。
--
-- 一致性由服务层强制：某条实收明细传了序列号时，**条数必须等于实收数量**，
-- 否则报「不允许大概齐」。这里再钉一道 DB 兜底：同一实收明细内不得重复登记同一个号。
CREATE TABLE goods_receipt_event_serial (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  event_line_id uuid NOT NULL,
  serial_number varchar(128) NOT NULL,
  -- 冗余固化 SKU 与批次：号改名/重录不影响历史可读性。
  sku_id uuid NOT NULL,
  lot_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,event_line_id,serial_number),
  FOREIGN KEY(tenant_id,event_line_id) REFERENCES goods_receipt_event_line(tenant_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),
  CHECK(btrim(serial_number)<>'')
);
CREATE INDEX goods_receipt_event_serial_line ON goods_receipt_event_serial(tenant_id,event_line_id);

-- ---------------------------------------------------------------------------
-- 6. 差异事实记录（**只记录事实，处理流程归 8.2.1.2**）
-- ---------------------------------------------------------------------------
-- 见文件头「二」：本表没有处理状态、没有处理决定、没有索赔单号 —— 那些是下游资产。
-- 有了本表，8.2.1.2 落地时可以直接读这里的差异明细作为处理输入，不必重新扫一遍收货事件。
--
-- discrepancy_type 与蓝图的差异种类对齐（8.2.1.2 原文：短少、破损、型号不符、过期、错发），
-- 外加超收与拒收两个本模块就能判定的类型：
--   OVER_RECEIPT 超收 / SHORT_RECEIPT 少收 / REJECTED 拒收
--   DAMAGED 破损 / WRONG_ITEM 型号不符 / EXPIRED 过期 / MISDELIVERED 错发
--
-- quantity 是**差异数量**而非该行的总量：超收 5 件记 5，少收 8 件记 8，
-- 拒收 3 件记 3 —— 与实收量分属两个口径，混在一起就又是一个「多退少补」的糊涂账。
CREATE TABLE goods_receipt_discrepancy (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  line_id uuid,
  discrepancy_type varchar(20) NOT NULL CHECK(discrepancy_type IN (
    'OVER_RECEIPT','SHORT_RECEIPT','REJECTED','DAMAGED','WRONG_ITEM','EXPIRED','MISDELIVERED')),
  quantity numeric(19,6) NOT NULL,
  -- reason_code 是自由词表（现场口语：外包装破损 / 型号对不上 / 临期）。
  -- **不建 CHECK 白名单**：差异原因要能随业务长，硬编码一个封闭词表只会逼着现场
  -- 把真实原因塞进 closest 的那个词条里，反而丢掉信息。
  reason_code varchar(64) NOT NULL DEFAULT '',
  reason_note text NOT NULL DEFAULT '',
  -- 凭证说明：留证的事实描述。实物照片属 8.2.1.2；本列存可检索的文字线索。
  evidence_note text NOT NULL DEFAULT '',
  recorded_at timestamptz NOT NULL,
  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES goods_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES goods_receipt_line(tenant_id,id),
  CHECK(quantity>0),
  CHECK(btrim(reason_code)<>'' OR btrim(reason_note)<>'' OR btrim(evidence_note)<>'')
);

-- ---------------------------------------------------------------------------
-- 7. 状态机：状态与时间戳自洽（草稿不该有关闭时间）
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_receipt_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.status = 'CLOSED') <> (NEW.closed_at IS NOT NULL) THEN
  RAISE EXCEPTION '状态「%」与关闭时间不自洽：只有已关闭才有关闭时间，其余状态必须为空', NEW.status
   USING ERRCODE='23514';
 END IF;
 -- 草稿不该带关闭人/关闭原因：没有发生过的事不能留下处理痕迹。
 IF NEW.status <> 'CLOSED' AND (NEW.closed_by IS NOT NULL OR btrim(NEW.close_reason)>'') THEN
  RAISE EXCEPTION '状态「%」不是已关闭，不允许带关闭人或关闭原因', NEW.status
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER receipt_state BEFORE INSERT OR UPDATE ON goods_receipt
 FOR EACH ROW EXECUTE FUNCTION guard_receipt_state();

-- 状态与实收事件数自洽：草稿一件货都没收到过。
-- 「有实收却还是草稿」会让这张单既不进库存统计、也不出现在待办里，成为账外单据。
CREATE FUNCTION guard_receipt_event_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE events integer; head_status varchar(20);
BEGIN
 SELECT status INTO head_status FROM goods_receipt WHERE tenant_id=NEW.tenant_id AND id=NEW.receipt_id;
 IF head_status IS NULL THEN RETURN NULL; END IF;
 SELECT count(*) INTO events FROM goods_receipt_event e
  WHERE e.tenant_id=NEW.tenant_id AND e.receipt_id=NEW.receipt_id;
 IF (events > 0) = (head_status = 'DRAFT') THEN
  RAISE EXCEPTION '收货单状态与实收笔数不自洽：状态「%」而实收笔数 %（实收后不得回到草稿）',
   head_status, events USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER receipt_event_state
 AFTER INSERT OR UPDATE ON goods_receipt_event
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_receipt_event_state();

-- ---------------------------------------------------------------------------
-- 8. 超收必须留痕（见文件头「五」）
-- ---------------------------------------------------------------------------
-- 延迟约束触发器：一次收货要「写事件 + 改明细行 + 写差异 + 写分录」四件事，
-- 即时触发器会在事务中途对着半成品报错。
CREATE FUNCTION guard_receipt_line_overage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.received_quantity + NEW.rejected_quantity > NEW.expected_quantity
    AND NOT EXISTS(
      SELECT 1 FROM goods_receipt_discrepancy d
       WHERE d.tenant_id=NEW.tenant_id AND d.line_id=NEW.id AND d.discrepancy_type='OVER_RECEIPT') THEN
  RAISE EXCEPTION '超收未留痕：明细行 % 预期 %，实收 % + 拒收 %。超收必须同时登记一条 OVER_RECEIPT 差异记录',
   NEW.id, NEW.expected_quantity, NEW.received_quantity, NEW.rejected_quantity
   USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER receipt_line_overage
 AFTER INSERT OR UPDATE ON goods_receipt_line
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_receipt_line_overage();

-- 明细行归属：差异必须指向一张真实存在的收货明细行（等价于一条逻辑外键，
-- 因为差异可以在行级也可以是单级（line_id 可空，见下））。
CREATE FUNCTION guard_discrepancy_line_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.line_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM goods_receipt_line l WHERE l.tenant_id=NEW.tenant_id AND l.id=NEW.line_id) THEN
  RAISE EXCEPTION '差异记录 % 指向的收货明细 % 不存在', NEW.id, NEW.line_id USING ERRCODE='23503';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER discrepancy_line_ref BEFORE INSERT OR UPDATE ON goods_receipt_discrepancy
 FOR EACH ROW EXECUTE FUNCTION guard_discrepancy_line_ref();

-- ---------------------------------------------------------------------------
-- 9. 热读路径索引
-- ---------------------------------------------------------------------------
-- 列表按状态筛 + 按更新/创建时间排；详情按收货单拉事件与差异。
CREATE INDEX goods_receipt_list ON goods_receipt(tenant_id,status,updated_at DESC);
CREATE INDEX goods_receipt_warehouse ON goods_receipt(tenant_id,warehouse_id,created_at DESC);
CREATE INDEX goods_receipt_source ON goods_receipt(tenant_id,source_type,source_ref_id);
CREATE INDEX goods_receipt_line_receipt ON goods_receipt_line(tenant_id,receipt_id);
CREATE INDEX goods_receipt_line_sku ON goods_receipt_line(tenant_id,sku_id);
CREATE INDEX goods_receipt_discrepancy_receipt ON goods_receipt_discrepancy(tenant_id,receipt_id);

-- ---------------------------------------------------------------------------
-- 10. 多租户：RLS + 授权（与 V1 第 9 节、V61 第 9 节同一条纪律）
-- ---------------------------------------------------------------------------
-- 六张业务表一视同仁 FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'goods_receipt','goods_receipt_line','goods_receipt_event','goods_receipt_event_line',
   'goods_receipt_event_serial','goods_receipt_discrepancy'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON goods_receipt, goods_receipt_line, goods_receipt_event,
  goods_receipt_event_line, goods_receipt_event_serial, goods_receipt_discrepancy TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条的话建单会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE goods_receipt_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V61 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;