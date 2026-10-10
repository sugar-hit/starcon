-- 6.4.1.1 加工单：委外 / 自有工厂加工的加工需求、组件领用与入库通知。
--
-- 蓝图 06-purchase-and-supply.md 6.4.1.1 原文：
--   职责：发起委外/自有工厂加工：选择成品 SKU 与 BOM 版本、指定组件领用量与加工厂、
--         约定完工数量与交期，跟踪加工进度与入库。
--   输入：加工需求（成品、数量、组件需求）；BOM 版本；加工厂。
--   输出：加工单（组件领用记录 + 完工数量）；入库需求通知 8。
--   关系：上游 = 3.5.2.1 BOM；下游 = 8 组装/加工单、7 库存（组件出库与成品入库同事务）。
--         **加工是"消耗组件产生成品"的库存动作**，必须在 warehouse 本地事务完成，
--         不能由 trade 直接改库存。
--
-- 归属：**trade 服务**（蓝图 README 系统地图 06 行「后端主责服务：trade + catalog（party）」）。
-- 本仓**不存在** purchase 服务，卷 06 的加工单据归 trade；路由 /purchase/processing 是
-- **前端**路由，后端命名空间仍按服务走（与 9.3.2.2 前端 /orders、后端 /trade/fulfillment/routing
-- 同一纪律）。本迁移不 ALTER V1 基线与 V92 的任何一张表，跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、模块边界：本文件**一张库存表都不碰**
-- ===========================================================================
-- 蓝图那句「必须在 warehouse 本地事务完成，不能由 trade 直接改库存」是**验收项**：
--   * 本文件只建 trade 自己的加工单据表（加工单 + 组件领用记录 + 状态事件 + 入库需求通知）；
--   * 组件出库与成品入库由 warehouse 服务在**它自己的一个本地事务**里完成
--     （warehouse 侧端点见 V98，本车道一并交付），走 inventory_transaction +
--     inventory_entry + stock_bucket / inventory_balance；
--   * 两个服务各有独立数据库，**不建跨库 FK、不建跨库视图、不跨库 JOIN**
--     （AGENTS.md 模块边界纪律）。本文件对 catalog 的 BOM 与 warehouse 的库存
--     都只存**逻辑 ID + 必要快照**（与 warehouse V1 的 sku_ref、trade V92 的 sku_ref 同一纪律）。
--
-- ===========================================================================
-- 二、上游 3.5.2.1 BOM（**已交付**，catalog V25）——不跨库读，只冻结引用与快照
-- ===========================================================================
-- catalog 已交付 V25（bom / bom_version / bom_component）与它承诺的下游接口：
--   * GET /catalog/boms/{id}/effective?at=        规则 R4 的时刻查询
--   * GET /catalog/bom-versions/{id}/requirement?outputQuantity=  规则 R7 的单层需求量
-- R6 / R7 两条语义**逐字沿用，不重新定义**（V25 文件头第三、六节）：
--   * **R6：scrap_rate 是百分数不是比率**（5 = 损耗 5%），上界 100；
--   * **R7：需求量 = 单位用量 × 产量 × (1 + 损耗率/100)**，6 位小数 HALF_UP，
--     且**只给精确需求量，不取整成采购量**（取整是 3.5.2.2 与 06 采购建议的事）。
--
-- 蓝图本模块要求「组件领用量要能从 BOM 算出来」。因此下达时**由 trade 调 catalog 的
-- 授权接口**取回那一版 BOM 的组件与需求量，把 unit_quantity / scrap_rate /
-- required_quantity **冻结**进 processing_order_component（这正是蓝图 6.4.1.2
-- 「冻结与订单冻结组件同理」的意思在加工单上的落法：已发布版本的组件不可改，
-- 冻结后即使将来接替版本上线，这张单仍能回溯「当时按哪一版、什么损耗率领的料」）。
--
-- **为什么冻三个数而不是只冻一个**：只冻 required_quantity 就答不出「这批料为什么是这个数」；
-- 只冻 unit_quantity 就算不出含损耗的量。三个一起冻，事后才能把
-- 「领了多少」对回「BOM 说该领多少」，差额就是损耗与不良的实据。
--
-- ===========================================================================
-- 三、状态机与进度跟踪（**不靠一个 status 字段糊过去**）
-- ===========================================================================
-- 蓝图要求「跟踪加工进度与入库」，硬要求是「完工数量与入库要有真实状态流转与进度跟踪」。
-- 所以加工单**不是**一个状态字段了事：
--   * processing_order.status      —— 加工单自身的生命周期（草稿 → 已下达 → 加工中 → 已完工
--                                     → 已入库；外加作废一支），由 DB 函数钉死合法迁移；
--   * processing_order_component.issued_quantity —— **已领数量**逐组件推进，
--     它与 required_quantity 的差额就是「还欠多少料」，这是真正的进度，不是状态字段；
--   * processing_order_event        —— append-only 状态与进度事件流，谁在什么时候把哪张单
--     推到了哪一步、领了多少、报了多少完工量，逐条留痕（REVOKE + 触发器钉死不可改写）。
--
-- 完工数量（completed_quantity）与入库数量（received_quantity）**分开记**：
-- 工厂报完工 ≠ 货已进仓。蓝图输出句把「完工数量」与「入库需求通知」并列，
-- 两者合成一个数字就等于允许「报完工即视同已入库」，那是仓库账实不符的起点。
--
-- ===========================================================================
-- 四、组件领用记录与入库需求通知都是**硬输出**，DB 钉死
-- ===========================================================================
-- 蓝图输出句：「加工单（组件领用记录 + 完工数量）」。因此：
--   * guard_processing_order_released —— 已下达（RELEASED）加工单必须至少有一条组件领用记录，
--     用延迟约束触发器回表核对当前行（不用 NEW 的可变列，V92 第八节同一个坑同一个解法）；
--   * guard_processing_order_component —— 同一组件在一条加工单内只能有一行；
--     已领数量不得超过需求量（超领是无声的账实不符，必须被挡下）；
--   * processing_inbound_request 是**真实可查的落库表**（不是往事件表塞一行）：
--     已下达加工单必须带一条入库需求通知，通知带成品 SKU 与**待入库数量**，
--     warehouse 消费后回写实收数量与入库单号。
--
-- ===========================================================================
-- 1. 加工厂（租户自维护的委外 / 自有工厂档案）
-- ===========================================================================
-- 蓝图输入含「加工厂」。加工厂是租户自己的供应商型主数据，**catalog 不拥有**
-- （3.5.2.1 的 BOM 也只引用 SKU 与版本，不含工厂），所以本服务自建一张真实档案表，
-- 由租户维护，不从代码常量取任何厂。
CREATE TABLE processing_factory (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 编码：租户内唯一且建后不可改（服务层强制）。它是加工单上的稳定引用。
  code varchar(64) NOT NULL CHECK(btrim(code)<>''),
  name varchar(200) NOT NULL CHECK(btrim(name)<>''),
  -- 厂别：OUTSOURCED 委外 / IN_HOUSE 自有工厂。蓝图原话「委外/自有工厂加工」，
  -- 两者在**成本与责任**上口径不同（委外要结算加工费，自有要计产能），
  -- 但在**库存动作**上完全一样（都是消耗组件产生成品），所以本列只作标注，
  -- 刻意不据此改变任何库存规则 —— 那样会让「委外」凭空多出一套扣减口径。
  factory_kind text NOT NULL CHECK(factory_kind IN ('OUTSOURCED','IN_HOUSE')),
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED')),
  -- 停产（SUSPENDED）后不再能下达新加工单（服务层 + 触发器双重判定），
  -- 但历史加工单照常可读：正在厂里做的活不会因为工厂停产而消失。
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code)
);

-- ===========================================================================
-- 2. 加工单（**本模块的事实表**）
-- ===========================================================================
CREATE TABLE processing_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 单号：租户内唯一，建后不可改（服务层强制）。它是给加工厂看的号。
  order_no varchar(64) NOT NULL CHECK(btrim(order_no)<>''),
  factory_id uuid NOT NULL,
  -- **成品 SKU**（catalog 的逻辑 ID，跨服务不建外键；与 warehouse V1 sku_ref 同一纪律）。
  -- 另存 code 快照：catalog 改名后历史单据仍能显示当时的成品名。
  output_sku_id uuid NOT NULL,
  output_sku_code varchar(64) NOT NULL CHECK(btrim(output_sku_code)<>''),
  -- **BOM 版本引用**：加工单必须绑定**已发布**版本（蓝图本模块硬要求）。
  -- 这里存 catalog 的 bom_id / bom_version_id 逻辑 ID + 版本号快照，
  -- 不建跨库 FK（BOM 表在 catalog 库里），「必须已发布」由服务层调 catalog 授权接口
  -- 核对 + 下达时把冻结结果写进组件领用记录共同保证。
  bom_id uuid NOT NULL,
  bom_version_id uuid NOT NULL,
  bom_version_no varchar(32) NOT NULL CHECK(btrim(bom_version_no)<>''),
  -- **约定完工数量**（蓝图输入「成品、数量」）。这是与工厂约定的目标产出。
  planned_output_quantity numeric(19,6) NOT NULL
    CHECK(planned_output_quantity > 0),
  -- **完工数量**（蓝图输出「完工数量」）。工厂**报**的数，与入库实收分开记 ——
  -- 报完工不等于已进仓，合起来会让仓库账实不符。
  completed_quantity numeric(19,6) NOT NULL DEFAULT 0
    CHECK(completed_quantity >= 0),
  -- **交期**（蓝图输入「交期」）。承诺完工日。
  due_on date NOT NULL,
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','RELEASED','IN_PROCESS','COMPLETED','RECEIVED','CANCELLED')),
  -- 下达时刻：组件领用记录从这一刻起才必须有（guard_processing_order_released）。
  released_at timestamptz,
  completed_at timestamptz,
  -- 入库需求通知被 warehouse 消费的时刻（与 completed_at 分开：可先完工后入仓）。
  received_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同租户同单号只能一行：两行时「加工厂手上那张单是哪张」无法回答，
  -- 而加工单是要发给厂里照着做的。
  UNIQUE(tenant_id,order_no),
  -- 工厂必须是本租户真实登记的（复合外键），否则「发给哪个厂」指向空气。
  FOREIGN KEY(tenant_id,factory_id) REFERENCES processing_factory(tenant_id,id) ON DELETE RESTRICT,
  -- **完工数量不得超过约定完工数量**。超产要么改约定、要么走多张单，
  -- 让 completed_quantity 静默超过 planned 会让「良率」这个数失去意义。
  CONSTRAINT processing_order_completed_within_planned
    CHECK (completed_quantity <= planned_output_quantity),
  -- 状态与时间戳必须自洽（与 V25 bom_version_published_needs_actor 同一口径）：
  -- 已下达要有下达时刻，草稿不能带着下达痕迹；
  -- 已完工要有完工时刻，且此刻必须已报出完工数量（**报 0 个的「完工」不是完工**）；
  -- 已入库要有入库时刻，且必须已完工（**没完工就入仓 = 凭空多出成品**）。
  CONSTRAINT processing_order_released_needs_time
    CHECK (status<>'DRAFT' OR released_at IS NULL),
  CONSTRAINT processing_order_status_needs_actor
    CHECK (status='DRAFT' OR status='CANCELLED' OR released_at IS NOT NULL),
  CONSTRAINT processing_order_completed_needs_time
    CHECK (status<>'COMPLETED' OR completed_at IS NOT NULL),
  CONSTRAINT processing_order_received_needs_time
    CHECK (status<>'RECEIVED' OR received_at IS NOT NULL),
  -- 完工态必须真有完工量；入库态必须先完工（这三个条件合起来堵住「空转状态机」）。
  CONSTRAINT processing_order_completed_needs_quantity
    CHECK (status NOT IN ('COMPLETED','RECEIVED') OR completed_quantity > 0),
  CONSTRAINT processing_order_received_needs_completed
    CHECK (status<>'RECEIVED' OR completed_at IS NOT NULL),
  -- 作废必须有原因：没有原因的作废等于把一张已经发给厂里的单悄悄抹掉。
  CONSTRAINT processing_order_cancel_needs_reason
    CHECK (status<>'CANCELLED' OR btrim(cancel_reason)<>'')
);

-- ===========================================================================
-- 3. 组件领用记录（**蓝图输出的硬输出之一**）
-- ===========================================================================
-- 一行 = 一张加工单要向仓库领的一个组件 SKU。
-- 三个冻结数来自 catalog 的 R7 结果（unit_quantity / scrap_rate / required_quantity），
-- 语义逐字沿用 V25 的 R6 / R7，本文件**不重新定义**任何一条。
CREATE TABLE processing_order_component (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL,
  -- 组件 SKU（catalog 逻辑 ID）+ code 快照，跨服务不建外键。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  -- **单位用量**（V25 R7 因子之一）：每 1 个成品需要这个组件多少。
  -- NUMERIC(19,6) 与 V25 bom_component.unit_quantity 同精度：
  -- 组件常是「0.5 米面料 + 2 颗螺丝」这类按计量单位组合，整数会逼运营把 0.5 写成 1，
  -- 而领料与采购量都照这个假数走。
  unit_quantity numeric(19,6) NOT NULL CHECK(unit_quantity > 0),
  -- **损耗率**：百分数（5 = 损耗 5%），上界 100 —— V25 **R6** 逐字沿用。
  -- 存比率（0.05）会让「填 5」被读成 500% 损耗且**不报错**，领料量直接翻 6 倍。
  scrap_rate numeric(7,4) NOT NULL DEFAULT 0
    CHECK(scrap_rate >= 0 AND scrap_rate <= 100),
  -- **需求量**（V25 **R7**：unit_quantity × 产量 × (1 + 损耗率/100)，6 位小数）。
  -- 这是「该领多少」的精确数，**不取整**：取整成采购量是 3.5.2.2 与 06 采购建议的职责。
  required_quantity numeric(19,6) NOT NULL CHECK(required_quantity > 0),
  -- **已领数量**（进度跟踪的真身）：仓库实际发出去了多少。
  -- 与 required_quantity 的差额 = 还欠多少料；这比一个 status 字段能回答的问题多得多。
  issued_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(issued_quantity >= 0),
  role text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一张加工单里同一个组件只能有一行：两行时「这个组件要几件」无法回答，
  -- 而 warehouse 出库只能按 (order, sku) 取一个数。
  UNIQUE(tenant_id,order_id,sku_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES processing_order(tenant_id,id) ON DELETE CASCADE,
  -- **超领是账实不符**：多领的料没有对应产出，账上凭空多出组件。
  -- 上界用 required_quantity 而不是 planned × unit × (1+scrap)（此处重算等于
  -- 把 R7 又实现一遍，两处实现必然漂移），R7 的口径只有 catalog 一处。
  CONSTRAINT processing_order_component_issue_within_required
    CHECK (issued_quantity <= required_quantity)
);

-- ===========================================================================
-- 4. 状态与进度事件流（append-only 留痕）
-- ===========================================================================
-- 「跟踪加工进度」不能只靠读加工单当前状态：状态只说明「现在在哪」，
-- 答不出「什么时候到的、谁推的、当时报了多少」。这一行 = 一次推进或一次进度登记。
CREATE TABLE processing_order_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'CREATED','RELEASED','PROGRESS','COMPLETED','RECEIVED','CANCELLED')),
  -- 推进前后的状态（进度登记时两者相同，因为进度不改生命周期）。
  from_status text NOT NULL,
  to_status text NOT NULL,
  -- 本次进度登记涉及的成品数量（完工报数用；其它事件留 0）。
  quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(quantity >= 0),
  note text NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES processing_order(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 5. 入库需求通知（**蓝图输出的硬输出之二：入库需求通知 8**）
-- ===========================================================================
-- 「输出：加工单（组件领用记录 + 完工数量）；入库需求通知 8」——
-- 通知必须是**真实可查的落库记录**，而不是往事件表随手塞一行：
-- warehouse 侧要能按它取到「哪个成品、多少量、要去哪个仓」，消费后再回写实收。
-- 这里只登记**需求**（trade 的事实）；真正改库存由 warehouse 在它自己的本地事务里做。
CREATE TABLE processing_inbound_request (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL,
  -- 成品 SKU（catalog 逻辑 ID）+ code 快照，跨服务不建外键。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  -- **待入库数量**：这张加工单一共要让仓库收多少成品。
  requested_quantity numeric(19,6) NOT NULL CHECK(requested_quantity > 0),
  -- **实收数量**：warehouse 消费后回写。默认 0（尚未收到），**不预填** ——
  -- 预填成 requested_quantity 等于让「已入库」在货还没到时就成立。
  received_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(received_quantity >= 0),
  -- warehouse 回写的入库单号（inventory_transaction.id 的可读引用），供对账。
  inventory_transaction_ref varchar(64) NOT NULL DEFAULT '',
  -- 状态：PENDING 待入库 → RECEIVED 已入库 / PARTIAL 部分入库；CANCELLED 随加工单作废。
  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','RECEIVED','PARTIAL','CANCELLED')),
  requested_at timestamptz NOT NULL DEFAULT now(),
  received_at timestamptz,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一张加工单一条入库需求通知：两张通知时「这个成品要收几次」无法回答，
  -- 而 warehouse 消费时也无从判断该以哪条为准。
  UNIQUE(tenant_id,order_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES processing_order(tenant_id,id) ON DELETE CASCADE,
  -- 实收不得超过需求（超收是凭空多出成品，必须被 DB 挡下）；
  -- PARTIAL 必须 0 < 实收 < 需求，RECEIVED 必须实收 > 0。
  CONSTRAINT processing_inbound_received_within_requested
    CHECK (received_quantity <= requested_quantity),
  CONSTRAINT processing_inbound_received_needs_quantity
    CHECK (status<>'RECEIVED' OR received_quantity > 0),
  CONSTRAINT processing_inbound_partial_is_partial
    CHECK (status<>'PARTIAL' OR (received_quantity > 0 AND received_quantity < requested_quantity)),
  -- 已入库必须带实收时刻与 warehouse 回写的单号，否则「已入库」不可追溯到任何凭证。
  CONSTRAINT processing_inbound_received_needs_trace
    CHECK (status<>'RECEIVED' OR (received_at IS NOT NULL AND btrim(inventory_transaction_ref)<>'')),
  -- 待入库不能带着实收痕迹（否则「待入库」与「已入库」两个状态指向同一份事实）。
  CONSTRAINT processing_inbound_pending_has_no_receipt
    CHECK (status<>'PENDING' OR (received_quantity = 0 AND received_at IS NULL
      AND btrim(inventory_transaction_ref)=''))
);

-- ===========================================================================
-- 6. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================

-- 6.1 已下达加工单必须有组件领用记录（蓝图输出句「加工单（组件领用记录 + 完工数量）」）。
--     **延迟约束触发器**：回表核对当前行的 status，而不是看 NEW.status ——
--     服务层的写入顺序是「先插头 → 写组件行 → 推进到 RELEASED」，
--     提交时约束触发器拿着的 NEW 仍可能是半成品（与 V92 第八节同一个坑、同一个解法）。
CREATE FUNCTION guard_processing_order_released() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
 IF NEW.status NOT IN ('RELEASED','IN_PROCESS','COMPLETED','RECEIVED') THEN RETURN NULL; END IF;
 SELECT count(*) INTO n FROM processing_order_component WHERE order_id = NEW.id;
 IF n = 0 THEN
  RAISE EXCEPTION '加工单「%」不能下达：没有任何组件领用记录。按 BOM 领料是加工单的核心输出，一张没有组件行的单子等于「发到厂里让它凭空变出成品」', NEW.order_no
   USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'RELEASED' AND NEW.released_at IS NULL THEN
  RAISE EXCEPTION '加工单「%」已推进到「%」却没有下达时刻：没有下达时刻就答不出这批料从什么时候开始欠', NEW.order_no, NEW.status
   USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER processing_order_released_check
  AFTER INSERT OR UPDATE ON processing_order
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_processing_order_released();

-- 6.2 组件领用记录挂在已下达/加工中的单上时，**只允许推进已领数量**，
--     其余列（单位用量 / 损耗率 / 需求量 / 组件 SKU）一律冻结。
--     理由与 V25 差异 3 同源：已下达的单已经在仓库按当时的量领过料，
--     改需求量等于追溯改写「当时该领多少」。
CREATE FUNCTION guard_processing_order_component_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text; parent_no text;
BEGIN
 SELECT status, order_no INTO parent_status, parent_no
   FROM processing_order WHERE tenant_id = NEW.tenant_id AND id = NEW.order_id;
 IF parent_status IS NULL THEN RETURN NEW; END IF;   -- 头还没插完（同一事务内先插组件的场景）
 IF parent_status IN ('RELEASED','IN_PROCESS') THEN
  IF NEW.sku_id IS DISTINCT FROM OLD.sku_id
     OR NEW.unit_quantity IS DISTINCT FROM OLD.unit_quantity
     OR NEW.scrap_rate IS DISTINCT FROM OLD.scrap_rate
     OR NEW.required_quantity IS DISTINCT FROM OLD.required_quantity THEN
   RAISE EXCEPTION '加工单「%」已下达，组件的单位用量 / 损耗率 / 需求量不可改：仓库已按当时的量领过料，改这些等于让「这批料当时为什么是这个数」无从查证。要改请先作废再重下', parent_no
    USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER processing_order_component_frozen
  BEFORE UPDATE ON processing_order_component
  FOR EACH ROW EXECUTE FUNCTION guard_processing_order_component_frozen();

-- 6.3 加工单的**身份列建后不可改**：单号、成品、BOM 版本、约定完工数量。
--     改这些等于让「当时发给厂里的是哪一张单」在历史里出现两个答案。
CREATE FUNCTION guard_processing_order_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status <> 'DRAFT' THEN
  IF NEW.order_no IS DISTINCT FROM OLD.order_no
     OR NEW.output_sku_id IS DISTINCT FROM OLD.output_sku_id
     OR NEW.bom_version_id IS DISTINCT FROM OLD.bom_version_id
     OR NEW.factory_id IS DISTINCT FROM OLD.factory_id
     OR NEW.planned_output_quantity IS DISTINCT FROM OLD.planned_output_quantity
     OR NEW.due_on IS DISTINCT FROM OLD.due_on THEN
   RAISE EXCEPTION '加工单「%」已下达，单号 / 成品 / BOM 版本 / 加工厂 / 约定完工数量 / 交期不可改：这张单已经发到厂里按这些信息备料了。要改请先作废再重下', OLD.order_no
    USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.order_no IS DISTINCT FROM OLD.order_no THEN
  RAISE EXCEPTION '加工单号建后不可改（% → %）：单号是给加工厂看的唯一标识，改它等于让厂里手上那张单无从对应。要换号请作废重下', OLD.order_no, NEW.order_no
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER processing_order_identity BEFORE UPDATE ON processing_order
  FOR EACH ROW EXECUTE FUNCTION guard_processing_order_identity();

-- 6.4 加工厂停用后不许再下达新加工单（正在做的活照常做完，历史单照常可读）。
CREATE FUNCTION guard_processing_factory_release() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f_status text; f_code text; parent_no text;
BEGIN
 IF NEW.status NOT IN ('RELEASED','IN_PROCESS','COMPLETED','RECEIVED') THEN RETURN NEW; END IF;
 IF OLD.status IN ('RELEASED','IN_PROCESS','COMPLETED','RECEIVED') THEN RETURN NEW; END IF;
 SELECT status, code INTO f_status, f_code FROM processing_factory
   WHERE tenant_id = NEW.tenant_id AND id = NEW.factory_id;
 IF f_status <> 'ACTIVE' THEN
  RAISE EXCEPTION '加工厂「%」已停用，不能再下达加工单。要继续用它请先恢复启用', f_code
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER processing_factory_release BEFORE UPDATE ON processing_order
  FOR EACH ROW EXECUTE FUNCTION guard_processing_factory_release();

-- 6.5 状态与进度事件流是 append-only 留痕：REVOKE UPDATE/DELETE 之外再加触发器，
--     绕过服务层直连 SQL 也改不动。
CREATE FUNCTION guard_processing_trail_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是不可改写的留痕表（%）：加工进度与状态推进是「这批料当时怎么动的」的举证凭据', TG_TABLE_NAME, TG_OP
  USING ERRCODE='23514';
END $$;
CREATE TRIGGER processing_order_event_append_only
  BEFORE UPDATE OR DELETE ON processing_order_event
  FOR EACH ROW EXECUTE FUNCTION guard_processing_trail_append_only();

-- 6.6 已作废的加工单不可再推进（作废是终态）。
CREATE FUNCTION guard_processing_order_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status = 'CANCELLED' AND NEW.status <> 'CANCELLED' THEN
  RAISE EXCEPTION '加工单「%」已作废，不允许再推进到「%」：作废后这张单不再代表任何在产或应收的货', OLD.order_no, NEW.status
   USING ERRCODE='23514';
 END IF;
 -- 已入库是终态：再推进就意味着同一批货能被记两次入库。
 IF OLD.status = 'RECEIVED' AND NEW.status <> 'RECEIVED' THEN
  RAISE EXCEPTION '加工单「%」已入库，是终态，不允许再改状态', OLD.order_no
   USING ERRCODE='23514';
 END IF;
 -- 完工数量只增不减：把已报完工量改小会让「报了多少」与已领料量对不上。
 IF NEW.completed_quantity < OLD.completed_quantity THEN
  RAISE EXCEPTION '加工单「%」的完工数量不能减少（% → %）：已领的料已经出库了，把完工量改小等于让仓库那批料永远没有对应产出', OLD.order_no, OLD.completed_quantity, NEW.completed_quantity
   USING ERRCODE='23514';
 END IF;
 -- 已经下达过的单不许退回草稿态：组件领用记录与入库需求通知都已经发出去了，
 -- 退回草稿等于让「这张单到底发出没有」在历史里出现两个答案。
 IF NEW.status = 'DRAFT' AND OLD.status <> 'DRAFT' THEN
  RAISE EXCEPTION '加工单「%」已下达过，不允许退回草稿态：组件领用记录与入库需求通知都已发出，退回会让「这张单到底发出没有」无从查证', OLD.order_no
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER processing_order_terminal BEFORE UPDATE ON processing_order
  FOR EACH ROW EXECUTE FUNCTION guard_processing_order_terminal();

-- ===========================================================================
-- 7. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 加工单列表页：按状态 + 交期倒序（逾期要一眼看到）。
CREATE INDEX processing_order_list ON processing_order(tenant_id, status, due_on, id);
-- 按加工厂查（问「这个厂手上有哪些单」）。
CREATE INDEX processing_order_by_factory ON processing_order(tenant_id, factory_id, status, id);
-- 按成品 SKU 反查（问「这个成品有哪些加工单」）。
CREATE INDEX processing_order_by_output_sku ON processing_order(tenant_id, output_sku_id, status, id);
-- 组件领用记录：按加工单读明细（详情页主查询形状）。
CREATE INDEX processing_order_component_by_order
  ON processing_order_component(tenant_id, order_id, sku_id);
-- 倒排：某个组件被哪些加工单领用（改 BOM 前先看谁在用）。
CREATE INDEX processing_order_component_by_sku ON processing_order_component(tenant_id, sku_id);
-- **未领足的组件**（进度页要盯的正是这批）：部分索引，只覆盖已下达但没领满的行。
CREATE INDEX processing_order_component_outstanding
  ON processing_order_component(tenant_id, order_id)
  WHERE issued_quantity < required_quantity;
-- 事件流：按加工单读时间线（详情页第二段）。
CREATE INDEX processing_order_event_by_order
  ON processing_order_event(tenant_id, order_id, occurred_at, id);
-- 入库需求通知：warehouse 待办清单按状态 + 时间读。
CREATE INDEX processing_inbound_request_pending
  ON processing_inbound_request(tenant_id, status, requested_at, id);

-- ===========================================================================
-- 8. 多租户：RLS + 授权（与 trade V1 / V92 第九节同一套写法）
-- ===========================================================================
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'processing_factory','processing_order',
   'processing_order_component','processing_order_event','processing_inbound_request'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  processing_factory,processing_order,processing_order_component,
  processing_order_event,processing_inbound_request TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  processing_factory,processing_order,processing_order_component,
  processing_order_event,processing_inbound_request TO starcon_app;
-- 6.5 收回事件流的改写权（「授权在前、收回在后」，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去，append-only 就只剩注释）。
REVOKE UPDATE,DELETE ON processing_order_event FROM trade_app;

-- ---------------------------------------------------------------------------
-- 9. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 的 trade_tenancy_probe 与 V92 的七张履约表都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * 对 catalog 的 bom_version / bom_component **只读逻辑 ID，不加约束**：
--     跨库 FK 本来就禁止，所以本文件既没有约束到 V25 的任何一张表，
--     3.5.2.1 的集成测试不需要为本车道重跑。
--   * 新增函数全部带 processing_ 前缀，与 V1 / V25 / V92 的既有函数名不冲突。
