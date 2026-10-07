-- 8.2.1.2 收货差异（**处理**）。
--
-- 蓝图 08-warehouse-operations.md 8.2.1.2 原文：
--   职责：处理收货差异：短少、破损、型号不符、过期、错发；支持拍照留证、拒收、部分接收、索赔发起。
--   输入：差异明细；处理决定；凭证。
--   输出：差异处理记录 + 索赔单（6 采购）+ 库存拒收不入账记录。
--   关系：上游 = 8.2.1.1；下游 = 6.4.2 不良品处理、6.5.1 对账索赔。
--   **破损与短少必须拒收或待检入库**，直接入可售会把问题货卖出去。
--
-- ===========================================================================
-- 一、与 8.2.1.1 的分工：那边**记录事实**，这边**处理**
-- ===========================================================================
-- V70 的 goods_receipt_discrepancy 只有事实列（类型 / 数量 / 原因 / 凭证说明 / 记录人），
-- 迁移文件头「二」明确写了「本模块只记录差异事实，不建处理状态机」，
-- 并把「拍照留证、拒收决定、部分接收、索赔发起」整条划给本迁移。
--
-- 所以本迁移**不改 V70 的任何一列、任何一条约束**（那是 8.2.1.1 已交付的事实表，
-- 每张表只有一个写入所有者），只新增四张表：
--
--   goods_receipt_discrepancy_handling  处理记录（一条差异一条，带状态机与处理决定）
--   goods_receipt_discrepancy_evidence  拍照留证（凭证本体真落库，不存「将来会有的」外链）
--   goods_receipt_discrepancy_claim     索赔记录（索赔意图与状态，见文件头「三」）
--   goods_receipt_discrepancy_stock_move 库存流转（拒撤出 / 转入待检 / 正常入库）
--
-- 处理记录与差异事实是 1:1（UNIQUE(tenant_id, discrepancy_id)）：
-- 一次差异一个处理过程。要改判请另登记一条差异 —— 改判会让已经发生的库存分录
-- 无法用「一次改判」讲清楚，而新登记一条差异能让「当时判成什么、后来又出了什么事」
-- 两段事实都留在库里。这与「预期与实收分开记录」是同一条纪律：改写历史不如追加事实。
--
-- ===========================================================================
-- 二、硬规则：破损与短少不得入可售（DB 级钉死）
-- ===========================================================================
-- 蓝图原文：「**破损与短少必须拒收或待检入库**，直接入可售会把问题货卖出去。」
--
-- 「直接入可售」最危险的路径不是服务层写错代码，而是有人绕过服务层直连 SQL，
-- 或者某个下游作业拿一条差异去生成入库分录。所以这条规则钉在**两处**：
--
--   guard_discrepancy_disposition（处理记录上）
--     disposition='ACCEPT' 只对 OVER_RECEIPT / MISDELIVERED 开放；
--     DAMAGED / SHORT_RECEIPT / WRONG_ITEM / EXPIRED 一律禁止 ACCEPT。
--     为什么把 WRONG_ITEM 与 EXPIRED 一并禁掉：蓝图只点名了破损与短少，
--     但「型号不符」意味着这箱货不是买家要的那个型号，「过期」意味着它根本不该再卖 ——
--     四类里只有超收与错发是「货本身没问题、只是单据对不上」，那两类才允许正常入库。
--
--   guard_discrepancy_stock_move（库存流转上）
--     上面禁的是「决定」，这一条禁的是「实际发生的库存移动」：
--     四类风险差异的入库方向一律不得指向 role='SELLABLE' 的质量状态，
--     只能指向 PENDING_INSPECTION 或 DEFECTIVE。
--     两条触发器各管一半：改决定绕不过第一条，绕过决定直接写流转绕不过第二条。
--
-- ===========================================================================
-- 三、索赔单：只记意图与状态，不建假的 purchase_claim 表
-- ===========================================================================
-- 蓝图输出写「索赔单（6 采购）」，但 6.3.2.2 采购单域**未交付**，
-- 而 AGENTS.md 明令「每张事实表只有一个写入所有者」「禁止跨服务 SQL / FK / 共享视图」。
-- 建一张假 purchase_claim 表冒充采购侧索赔单，等于凭空造出第二个索赔事实所有者。
--
-- 照抄 8.2.1.1 对「回写采购单」的同一判断：warehouse 侧如实记 source_ref_id +
-- 索赔意图与状态（goods_receipt_discrepancy_claim），上游 6.3.2.2 交付后由**上游订阅事件**
-- 建自己的索赔单并回填 claim_ref_id。claim_ref_id 在上游交付前恒为空，
-- /capabilities 里显式报 available=false —— 这是路由契约，不是死链。
--
-- 索赔状态只到 SUBMITTED 为止：ACKNOWLEDGED / SETTLED 由 6.5.1 对账索赔拥有，
-- 那边的状态由那边推进，warehouse 不代写（代写就是第二个写入所有者）。
-- WITHDRAWN 是 warehouse 自己的终态：发起方在对方受理前撤回。
--
-- ===========================================================================
-- 四、凭证为什么真落库，而不是存一个「将来会有的」外链
-- ===========================================================================
-- 拍照留证是本模块的硬职责（「支持拍照留证」）。只存 storage_key 等于交付一个
-- 取不出照片的功能 —— 那比不做更糟，因为索赔时拿不出证据。
--
-- catalog 的 MediaStorage 端口在 catalog 服务内，跨服务 import 它会越过模块边界纪律，
-- 而本仓库也没有共享的对象存储库。因此凭证本体落 PostgreSQL（bytea），
-- 上限 1.5 MB / 条（CHECK byte_size<=1500000，绕过服务层也插不进超大行）。
-- 前端在浏览器侧先把照片压到长边 ≤1280px 的 JPEG 再上传，
-- 现场拿手机随手拍的货损照片压到这个尺寸后普遍在 100 KB 量级。
-- sha256 让「两次上传的是不是同一张照片」有答案，也让改动可被发现。
--
-- ===========================================================================
-- 五、与既有库存表的关系：仍然**只插不改**
-- ===========================================================================
-- 与 V70 同口径：本迁移不改 inventory_balance / stock_bucket / inventory_entry /
-- inventory_transaction 的任何列或约束，只按既有写法追加带符号增量：
--   拒收撤出：transaction(kind='ADJUSTMENT') + entry(on_hand_delta<0)
--               + balance.on_hand -= qty + bucket.on_hand -= qty
--   转入待检：两个方向各一条（从原质量状态减、往待检质量状态加）
--
-- 「破损不得入可售」之所以能在库存侧真的成立，正是因为这条路径每次都写
-- quality_status_id —— 而不是只在本模块的表里记一个「去向」。
-- ===========================================================================

CREATE SEQUENCE goods_receipt_claim_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 处理记录（一条差异事实一条处理过程）
-- ---------------------------------------------------------------------------
-- discrepancy_type / quantity 是**快照**：处理那一刻把差异事实固化下来。
-- 8.2.1.1 后续不再改事实行，但快照仍然重要 —— 列表页要按差异类型筛、
-- 要按数量算索赔比例，都不该每次都回表 join 一张别人拥有的表。
CREATE TABLE goods_receipt_discrepancy_handling (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  discrepancy_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  line_id uuid,
  sku_id uuid,
  discrepancy_type varchar(20) NOT NULL CHECK(discrepancy_type IN (
    'OVER_RECEIPT','SHORT_RECEIPT','REJECTED','DAMAGED','WRONG_ITEM','EXPIRED','MISDELIVERED')),
  -- 差异数量快照。接受量 + 拒收量必须恰好等于它（guard_discrepancy_handling_state 钉死）：
  -- 少了就是「有一批货的账凭空不见了」，多了就是「处理了不属于这批差异的货」。
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),
  status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN (
    'PENDING','DECIDED','CLAIMED','CLOSED')),
  -- 处理决定。待处理时必须为空：没决定的事不许带决定痕迹。
  disposition varchar(24) CHECK(disposition IS NULL OR disposition IN (
    'REJECT','PARTIAL_ACCEPT','HOLD_FOR_INSPECTION','ACCEPT')),
  accepted_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(accepted_quantity>=0),
  rejected_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(rejected_quantity>=0),
  -- 入库去向质量状态：部分接收与待检入库时必填，决定那批货落到可售还是待检 bucket。
  inbound_quality_status_id uuid,
  decision_reason varchar(200) NOT NULL DEFAULT '',
  decision_note text NOT NULL DEFAULT '',
  decided_at timestamptz,
  decided_by uuid,
  version bigint NOT NULL DEFAULT 0,
  close_reason varchar(200) NOT NULL DEFAULT '',
  closed_at timestamptz,
  closed_by uuid,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一条差异一个处理过程（见文件头「一」：改判请另登记一条差异）。
  UNIQUE(tenant_id,discrepancy_id),
  FOREIGN KEY(tenant_id,discrepancy_id) REFERENCES goods_receipt_discrepancy(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES goods_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES goods_receipt_line(tenant_id,id),
  FOREIGN KEY(tenant_id,inbound_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id)
);
CREATE INDEX discrepancy_handling_list
  ON goods_receipt_discrepancy_handling(tenant_id,status,updated_at DESC);
CREATE INDEX discrepancy_handling_receipt
  ON goods_receipt_discrepancy_handling(tenant_id,receipt_id,created_at DESC);
CREATE INDEX discrepancy_handling_type
  ON goods_receipt_discrepancy_handling(tenant_id,discrepancy_type,status);

-- ---------------------------------------------------------------------------
-- 2. 凭证（拍照留证；本体真落库，见文件头「四」）
-- ---------------------------------------------------------------------------
-- evidence_type 四类：现场能立刻拿出来的都是照片，另外三类是随货到达的凭据
-- （复磅单、质检报告、承运异常通知单）。不建 CHECK 封闭词表之外的原因是：
-- 词表已经是枚举，而 note 是自由文本，现场要补的话写进 note 而不是硬塞进最近的一类。
CREATE TABLE goods_receipt_discrepancy_evidence (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  handling_id uuid NOT NULL,
  evidence_type varchar(16) NOT NULL CHECK(evidence_type IN (
    'PHOTO','WEIGHING','INSPECTION','DOCUMENT')),
  file_name varchar(200) NOT NULL DEFAULT '',
  content_type varchar(64) NOT NULL,
  content bytea NOT NULL,
  byte_size integer NOT NULL,
  sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
  captured_at timestamptz NOT NULL,
  uploaded_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,handling_id) REFERENCES goods_receipt_discrepancy_handling(tenant_id,id),
  -- 内容长度与声明大小必须一致：不然「显示 1 张 12 KB 的照片」可能点开是 0 字节。
  CHECK(byte_size>0 AND octet_length(content)=byte_size),
  -- 单条上限。绕过服务层直连 SQL 也塞不进超大行（见文件头「四」）。
  CHECK(byte_size<=1500000)
);
CREATE INDEX discrepancy_evidence_handling
  ON goods_receipt_discrepancy_evidence(tenant_id,handling_id,captured_at);

-- ---------------------------------------------------------------------------
-- 3. 索赔记录（索赔意图与状态；见文件头「三」）
-- ---------------------------------------------------------------------------
-- claim_code = CLM-日期-六位，发号器与收货单单号同一条纪律（DB 序列，不在应用侧拼）。
-- source_ref_id 指向来源单据（6.3.2.2 未交付，故只记逻辑引用、不建跨服务外键）。
-- claimed_amount 可空：供应商还没报价时先把索赔发起，金额等对账时补 ——
-- 「金额必填」会让现场因为等一个还没人给的数字而压着货损不报。
CREATE TABLE goods_receipt_discrepancy_claim (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  claim_code varchar(32) NOT NULL,
  handling_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  source_ref_id uuid,
  source_ref_code varchar(64) NOT NULL DEFAULT '',
  claim_type varchar(20) NOT NULL CHECK(claim_type IN (
    'SHORTAGE','DAMAGE','WRONG_ITEM','EXPIRED','MISDELIVERY','OVER_RECEIPT')),
  claimed_quantity numeric(19,6) NOT NULL CHECK(claimed_quantity>0),
  claimed_amount numeric(19,4) CHECK(claimed_amount IS NULL OR claimed_amount>0),
  currency varchar(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  -- 状态只到 SUBMITTED 为止；ACKNOWLEDGED / SETTLED 归 6.5.1 对账索赔（见文件头「三」）。
  status varchar(20) NOT NULL DEFAULT 'INITIATED' CHECK(status IN (
    'INITIATED','SUBMITTED','WITHDRAWN')),
  -- 6.5.1 交付后由上游回填。上游未交付时恒为空，这不是漏填。
  claim_ref_id uuid,
  submitted_at timestamptz,
  submitted_by uuid,
  withdrawn_at timestamptz,
  withdrawn_reason varchar(200) NOT NULL DEFAULT '',
  reason text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,claim_code),
  -- 一条处理记录最多一条索赔：同一批差异发两次索赔会让对账那边收到重复金额。
  UNIQUE(tenant_id,handling_id),
  FOREIGN KEY(tenant_id,handling_id) REFERENCES goods_receipt_discrepancy_handling(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES goods_receipt(tenant_id,id)
);
CREATE INDEX discrepancy_claim_receipt
  ON goods_receipt_discrepancy_claim(tenant_id,receipt_id,created_at DESC);
CREATE INDEX discrepancy_claim_status
  ON goods_receipt_discrepancy_claim(tenant_id,status,created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. 库存流转（拒收撤出 / 转入待检 / 正常入库）
-- ---------------------------------------------------------------------------
-- 这张表回答蓝图输出里那句「库存拒收不入账记录」：哪一批货因为哪条差异
-- 从哪个质量状态、哪个库位、哪个批次被撤走了。
--
-- direction 五值：
--   NOT_POSTED           拒收不入账：这批货从没进过库存（蓝图的「库存拒收不入账记录」就是这个）
--   WITHDRAW             撤出：从某个质量状态扣掉（负增量）。两种来源共用这一个方向 ——
--                        拒收撤出、以及「已入库的货改判待检」时的质量状态流转出。
--                        两者在库存上是同一个动作，方向如实由「从哪个质量状态减」推导；
--                        具体是拒收还是改判由处理记录上的 disposition 表达，不在这里重复一遍。
--   INBOUND_TO_HOLD      转入待检 / 残次：往非可售质量状态加（正增量）
--   INBOUND_TO_SELLABLE  正常入库：往可售质量状态加（正增量）
--   NO_MOVE              无需移动：货已经在去向质量状态里（例如超收确认 —— 收货时就入了可售）。
--                        单独记一行是为了回答「为什么这条处理没有库存变动」：
--                        完全没有痕迹的话，界面上「库存没动」看起来像处理漏了。
--
-- NOT_POSTED 与 WITHDRAW 的区别就是「这批货到底进过库没有」：
-- 短少这类差异的货从一开始就不在库里，拒收它唯一的库存痕迹就是这一行 NOT_POSTED ——
-- 「为什么不入库」答不出来才是真问题。进了库又改判的才走 WITHDRAW 真扣余额。
--
-- from_quality_status_id 在撤出时必填（从哪儿撤的），在 NOT_POSTED 时必须为空
-- （从没进过库就不存在「从哪个质量状态撤」）。
CREATE TABLE goods_receipt_discrepancy_stock_move (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  handling_id uuid NOT NULL,
  discrepancy_id uuid NOT NULL,
  -- sku_id 可空：差异可以挂在单级（line_id 为空），比如「整单少了 3 箱」这种
  -- 没有具体 SKU 的短少。这种差异仍然要有「拒收不入账」的痕迹，
  -- 但它**不能**产生真实库存移动 —— 没有 SKU 就没法动库存。
  -- 所以下面用 guard_discrepancy_stock_move 钉死：真动库存的三种方向必须有 sku_id。
  sku_id uuid,
  warehouse_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  direction varchar(24) NOT NULL CHECK(direction IN (
    'NOT_POSTED','WITHDRAW','INBOUND_TO_HOLD','INBOUND_TO_SELLABLE','NO_MOVE')),
  from_quality_status_id uuid,
  to_quality_status_id uuid,
  location_id uuid,
  lot_id uuid,
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),
  -- 关联的 inventory_transaction.id：让「这笔差异处理对应哪笔库存分录」可回溯，
  -- 而不是只在本表里记一个孤零零的数量。
  transaction_id uuid,
  occurred_at timestamptz NOT NULL,
  actor_id uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,handling_id) REFERENCES goods_receipt_discrepancy_handling(tenant_id,id),
  FOREIGN KEY(tenant_id,discrepancy_id) REFERENCES goods_receipt_discrepancy(tenant_id,id),
  FOREIGN KEY(tenant_id,from_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,to_quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id,location_id) REFERENCES warehouse_location(tenant_id,warehouse_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id)
);
CREATE INDEX discrepancy_stock_move_handling
  ON goods_receipt_discrepancy_stock_move(tenant_id,handling_id,occurred_at);

-- ---------------------------------------------------------------------------
-- 5. 状态机与硬规则：全部钉在 DB 上
-- ---------------------------------------------------------------------------

-- 5.1 状态、决定与时间戳自洽。
-- 「已决定」却没有决定内容，等于给了一个永远显示「待决定」的记录；
-- 「还没决定」却带着决定痕迹，等于凭空多出一个没人做过的决定。
CREATE FUNCTION guard_discrepancy_handling_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE decided boolean;
BEGIN
  decided := NEW.status IN ('DECIDED','CLAIMED','CLOSED');
  IF decided <> (NEW.disposition IS NOT NULL) THEN
    RAISE EXCEPTION '处理状态与处理决定不自洽：状态「%」而 disposition=%（待处理不得带决定，已决定/已索赔/已关闭必须带决定）', NEW.status, NEW.disposition USING ERRCODE='23514';
  END IF;
  IF decided <> (NEW.decided_at IS NOT NULL) THEN
    RAISE EXCEPTION '处理状态与决定时间不自洽：状态「%」而 decided_at=%', NEW.status, NEW.decided_at
     USING ERRCODE='23514';
  END IF;
  IF NOT decided AND (NEW.decided_by IS NOT NULL OR btrim(NEW.decision_reason)>''
     OR btrim(NEW.decision_note)>'') THEN
    RAISE EXCEPTION '状态「%」尚未决定，不允许带决定人、决定原因或决定说明', NEW.status
     USING ERRCODE='23514';
  END IF;
  -- 接受量 + 拒收量必须把差异数量整个分掉。少了是有一批货的账凭空不见了，
  -- 多了是处理了不属于这批差异的货 —— 两种都是账实不符。
  IF decided AND NEW.accepted_quantity + NEW.rejected_quantity <> NEW.quantity THEN
    RAISE EXCEPTION '处理数量对不上：差异数量 %，接受 % + 拒收 % = %。接受量与拒收量之和必须恰好等于差异数量',
     NEW.quantity, NEW.accepted_quantity, NEW.rejected_quantity,
     NEW.accepted_quantity + NEW.rejected_quantity USING ERRCODE='23514';
  END IF;
  IF NOT decided AND (NEW.accepted_quantity<>0 OR NEW.rejected_quantity<>0) THEN
    RAISE EXCEPTION '状态「%」尚未决定，不允许带接受量或拒收量', NEW.status USING ERRCODE='23514';
  END IF;
  -- 关单痕迹与状态自洽（与 V70 的 guard_receipt_state 同一条纪律）。
  IF (NEW.status = 'CLOSED') <> (NEW.closed_at IS NOT NULL) THEN
    RAISE EXCEPTION '状态「%」与关闭时间不自洽：只有已关闭才有关闭时间，其余状态必须为空', NEW.status
     USING ERRCODE='23514';
  END IF;
  IF NEW.status <> 'CLOSED' AND (NEW.closed_by IS NOT NULL OR btrim(NEW.close_reason)>'') THEN
    RAISE EXCEPTION '状态「%」不是已关闭，不允许带关闭人或关闭原因', NEW.status USING ERRCODE='23514';
  END IF;
  -- 有实际入库去向的决定必须指明去向质量状态，否则那批货落到可售还是待检无从判断。
  IF decided AND NEW.accepted_quantity>0 AND NEW.inbound_quality_status_id IS NULL THEN
    RAISE EXCEPTION '处理决定接受了 % 但没有指定入库去向质量状态：接受入库的货必须落到可售或待检',
     NEW.accepted_quantity USING ERRCODE='23514';
  END IF;
  IF NEW.accepted_quantity=0 AND NEW.inbound_quality_status_id IS NOT NULL THEN
    RAISE EXCEPTION '接受量为 0 时不允许指定入库去向质量状态：没有货入库就不存在去向'
     USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER discrepancy_handling_state BEFORE INSERT OR UPDATE
  ON goods_receipt_discrepancy_handling
 FOR EACH ROW EXECUTE FUNCTION guard_discrepancy_handling_state();

-- 5.2 **硬规则第一处**：破损与短少不得「正常入库」（见文件头「二」）。
CREATE FUNCTION guard_discrepancy_disposition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.disposition = 'ACCEPT'
     AND NEW.discrepancy_type IN ('DAMAGED','SHORT_RECEIPT','WRONG_ITEM','EXPIRED') THEN
    RAISE EXCEPTION '差异类型「%」不允许按「正常入库」处理：蓝图要求破损与短少必须拒收或待检入库，直接入可售会把问题货卖出去。可选处理：拒收 / 部分接收 / 待检入库',
     NEW.discrepancy_type USING ERRCODE='23514';
  END IF;
  -- 全额拒收不得同时给出入库去向；全额接受不得有拒收量（上面的和式已经钉住数量，
  -- 这里钉住的是语义：REJECT 的拒收量必须等于差异量）。
  IF NEW.disposition = 'REJECT' AND NEW.accepted_quantity<>0 THEN
    RAISE EXCEPTION '处理决定是「拒收」，接受量必须为 0（当前 %）', NEW.accepted_quantity
     USING ERRCODE='23514';
  END IF;
  IF NEW.disposition = 'ACCEPT' AND NEW.rejected_quantity<>0 THEN
    RAISE EXCEPTION '处理决定是「正常入库」，拒收量必须为 0（当前 %）', NEW.rejected_quantity
     USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER discrepancy_disposition BEFORE INSERT OR UPDATE
  ON goods_receipt_discrepancy_handling
 FOR EACH ROW EXECUTE FUNCTION guard_discrepancy_disposition();

-- 5.3 **硬规则第二处**：风险差异的入库方向不得指向可售（见文件头「二」）。
-- 5.2 管的是「决定」，这条管的是「实际发生的库存移动」：
-- 绕过服务层直连 SQL 改流转表时，这条仍然拦得住。
CREATE FUNCTION guard_discrepancy_stock_move() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE risk boolean; role varchar(20); move_type varchar(20);
BEGIN
  SELECT d.discrepancy_type INTO move_type
    FROM goods_receipt_discrepancy d
   WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.discrepancy_id;
  risk := move_type IN ('DAMAGED','SHORT_RECEIPT','WRONG_ITEM','EXPIRED');

  IF NEW.direction = 'INBOUND_TO_SELLABLE' AND risk THEN
    RAISE EXCEPTION '差异类型「%」的货不允许入可售（数量 %）：蓝图要求破损与短少必须拒收或待检入库，直接入可售会把问题货卖出去', move_type, NEW.quantity USING ERRCODE='23514';
  END IF;
  IF NEW.direction = 'INBOUND_TO_SELLABLE' AND NEW.to_quality_status_id IS NULL THEN
    RAISE EXCEPTION '正常入库必须指明入库去向质量状态' USING ERRCODE='23514';
  END IF;
  -- 入库必须指向一个真实的、且**不是可售**的质量状态（风险差异）。
  IF NEW.to_quality_status_id IS NOT NULL THEN
    SELECT q.role INTO role FROM inventory_quality_status q
     WHERE q.tenant_id=NEW.tenant_id AND q.id=NEW.to_quality_status_id;
    IF role IS NULL THEN
      RAISE EXCEPTION '入库去向质量状态不存在或无权访问：%', NEW.to_quality_status_id
       USING ERRCODE='23503';
    END IF;
    IF risk AND NEW.direction <> 'WITHDRAW' AND role = 'SELLABLE' THEN
      RAISE EXCEPTION '差异类型「%」的货不得入可售质量状态（数量 %，去向 %）：必须入待检或残次',
       move_type, NEW.quantity, NEW.to_quality_status_id USING ERRCODE='23514';
    END IF;
  END IF;
  IF NEW.direction = 'WITHDRAW' AND NEW.from_quality_status_id IS NULL THEN
    RAISE EXCEPTION '拒收撤出必须指明从哪个质量状态撤出：没有来源就答不出「这批货是从可售撤下来的」'
     USING ERRCODE='23514';
  END IF;
  -- 真动库存必须有 SKU、仓库与法人：没有 SKU 就没有「这批货」可动。
  IF NEW.direction <> 'NOT_POSTED' AND NEW.sku_id IS NULL THEN
    RAISE EXCEPTION '库存流转方向「%」必须指定 SKU：这条差异是单级差异（没有具体 SKU），只能记为「拒收不入账」，不能凭空动库存', NEW.direction USING ERRCODE='23514';
  END IF;
  -- 从没进过库就不存在「从哪个质量状态撤」：NOT_POSTED 带来源会让
  -- 「这批货从没入过库」与「进了库又撤出」在台账上分不开。
  IF NEW.direction = 'NOT_POSTED' AND (NEW.from_quality_status_id IS NOT NULL
     OR NEW.to_quality_status_id IS NOT NULL) THEN
    RAISE EXCEPTION '拒收不入账（NOT_POSTED）不允许带质量状态：这批货从没进过库存，没有来源也没有去向'
     USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER discrepancy_stock_move BEFORE INSERT OR UPDATE
  ON goods_receipt_discrepancy_stock_move
 FOR EACH ROW EXECUTE FUNCTION guard_discrepancy_stock_move();

-- 5.4 索赔状态与时间戳自洽；已提交不可撤回（撤回只能发生在 INITIATED）。
-- 「提交后又撤回」等于让对账那边收到过一条又消失的索赔，所以禁止。
CREATE FUNCTION guard_discrepancy_claim_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.status = 'WITHDRAWN') <> (NEW.withdrawn_at IS NOT NULL) THEN
    RAISE EXCEPTION '索赔状态「%」与撤回时间不自洽', NEW.status USING ERRCODE='23514';
  END IF;
  IF NEW.status <> 'WITHDRAWN' AND btrim(NEW.withdrawn_reason)>'' THEN
    RAISE EXCEPTION '索赔状态「%」不是已撤回，不允许带撤回原因', NEW.status USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'SUBMITTED' AND NEW.submitted_at IS NULL THEN
    RAISE EXCEPTION '索赔已提交但没有提交时间' USING ERRCODE='23514';
  END IF;
  -- 已提交不可撤回：撤回只允许发生在 INITIATED。
  IF TG_OP = 'UPDATE' AND OLD.status = 'SUBMITTED' AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION '索赔 % 已提交，不可再改状态：对方可能已经受理，撤回会让对账出现一笔凭空消失的索赔',
     OLD.claim_code USING ERRCODE='23514';
  END IF;
  -- 上游 6.5.1 未交付，claim_ref_id 必须为空（见文件头「三」）。
  IF NEW.claim_ref_id IS NOT NULL THEN
    RAISE EXCEPTION '索赔单据 id 归 6.5.1 对账索赔拥有，上游未交付时不允许自行填写 claim_ref_id'
     USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER discrepancy_claim_state BEFORE INSERT OR UPDATE
  ON goods_receipt_discrepancy_claim
 FOR EACH ROW EXECUTE FUNCTION guard_discrepancy_claim_state();

-- 5.5 处理状态与索赔必须自洽：已索赔必然有索赔记录；未决定不得有索赔。
-- 「未决定的记录却已发起索赔」会让对账那边收到一笔没有事实依据的金额。
CREATE FUNCTION guard_discrepancy_handling_claim() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE claims integer;
BEGIN
  SELECT count(*) INTO claims FROM goods_receipt_discrepancy_claim c
   WHERE c.tenant_id=NEW.tenant_id AND c.handling_id=NEW.id;
  -- 只有「已索赔」必须有索赔记录。「已关闭」不同：一条不留索赔、只留留证与库存流转的
  -- 差异处理同样应该能结案（现场货已入库、供应商认了、这次不索赔）——
  -- 把关单也要求有索赔，会逼着现场为了一张本可以关闭的差异单硬造一笔索赔。
  IF NEW.status = 'CLAIMED' AND claims = 0 THEN
    RAISE EXCEPTION '处理状态「已索赔」，但这条处理记录下没有任何索赔记录'
     USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'PENDING' AND claims > 0 THEN
    RAISE EXCEPTION '处理状态「%」尚未决定，却已有 % 条索赔记录', NEW.status, claims
     USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER discrepancy_handling_claim
 AFTER INSERT OR UPDATE ON goods_receipt_discrepancy_handling
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_discrepancy_handling_claim();

-- 5.6 关闭时必须有处理决定（由 5.1 保证）**且**必须留痕：
-- 关掉一条没有任何凭证、没有库存流转、也没有索赔的差异，等于把货损抹掉了。
CREATE FUNCTION guard_discrepancy_handling_close() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE evidences integer; moves integer; claims integer;
BEGIN
  IF NEW.status <> 'CLOSED' OR TG_OP = 'INSERT' THEN RETURN NULL; END IF;
  SELECT count(*) INTO evidences FROM goods_receipt_discrepancy_evidence e
   WHERE e.tenant_id=NEW.tenant_id AND e.handling_id=NEW.id;
  SELECT count(*) INTO moves FROM goods_receipt_discrepancy_stock_move m
   WHERE m.tenant_id=NEW.tenant_id AND m.handling_id=NEW.id;
  SELECT count(*) INTO claims FROM goods_receipt_discrepancy_claim c
   WHERE c.tenant_id=NEW.tenant_id AND c.handling_id=NEW.id;
  IF evidences = 0 AND moves = 0 AND claims = 0 THEN
    RAISE EXCEPTION '处理记录 % 不能直接关闭：没有任何凭证、库存流转或索赔记录。关闭一条空白的差异处理等于把货损痕迹抹掉', NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER discrepancy_handling_close
 AFTER INSERT OR UPDATE ON goods_receipt_discrepancy_handling
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_discrepancy_handling_close();

-- ---------------------------------------------------------------------------
-- 6. 多租户：RLS + 授权（与 V70 第 10 节完全同一条纪律）
-- ---------------------------------------------------------------------------
-- 四张业务表一视同仁 FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'goods_receipt_discrepancy_handling','goods_receipt_discrepancy_evidence',
   'goods_receipt_discrepancy_claim','goods_receipt_discrepancy_stock_move'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON goods_receipt_discrepancy_handling,
  goods_receipt_discrepancy_evidence, goods_receipt_discrepancy_claim,
  goods_receipt_discrepancy_stock_move TO warehouse_app;
-- 序列是索赔单发号器，漏这一条会报「permission denied for sequence」而错误文案指不到授权。
GRANT USAGE, SELECT ON SEQUENCE goods_receipt_claim_code_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V61、V70 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
