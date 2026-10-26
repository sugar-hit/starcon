-- 11.2.3.2 转可售/残次/报废（卷 11 售后与退货 / L3 11.2.3 退货处置的第三格）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：按质检结论把退货转入对应状态：可再销售转可售库存、残次转残次库存（可用于清仓）、
--         报废则核销并计入损耗。
--   输入：质检结论；处置决定；`expected_version`。
--   输出：库存状态变更分录；可售量更新；报废损耗记录。
--   关系：上游 = 11.2.3.1；下游 = 7.1.1.3 可用量、5.4 调价（残次品清仓）、12 财务（损耗）。
--         **报废是不可逆的**，需审批与原因，不能由客服随手处理。
--
-- 归属：**trade 服务**（与 11.2.3.1 质检、11.2.3.3 索赔同侧，售后单据链不跨库分家）。
--
-- ===========================================================================
-- 一、落地服务怎么切（trade + warehouse 两边都有），以及为什么
-- ===========================================================================
--
-- 蓝图卷 11 的后端主责是「trade + finance + warehouse」，而可用的上游落点只有两个独立数据库。
-- 本模块的输出句有三样东西，分属两个库：
--
--   * **库存状态变更分录 / 可售量更新** = warehouse 的领域
--     （stock_bucket / inventory_balance / inventory_entry / inventory_transaction）。
--     AGENTS.md「模块边界纪律」禁止跨服务 SQL / FK / 共享视图 ⇒
--     **写库存的那一步必须在 warehouse 侧**，trade 不能直连 warehouse 的表。
--   * **质检结论 / 处置决定 / 审批与原因 / 报废损耗记录** = 售后域，归 trade。
--
-- ⇒ 切法：**trade 拥有处置决定与审批，并经授权 HTTP 把执行命令送到 warehouse；
--   warehouse 在自己的库里真正改库存并回执。** trade 的处置单与 warehouse 的执行回执
--   一一对应（逻辑 ID + 单号快照，不建跨库 FK）。
--
-- 【被否决的方案 A：整个模块落在 trade，trade 直连 warehouse 的表写库存】
--   ⇒ 违反 AGENTS.md 模块边界纪律；且 trade 与 warehouse 是两个独立数据库，
--   连「读一下 bucket 现状」都会变成跨库 SQL。
--
-- 【被否决的方案 B：整个模块落在 warehouse，warehouse 通过 HTTP 读 trade 的指令并自建处置单】
--   ⇒ 处置决定、审批留痕与报废损耗记录会与售后单据链分家（损耗的下游是 12 财务，
--   而 12 财务要对接的是售后单据）；更要命的是**质量状态指令的消费状态无法回写**
--   （指令表在 trade 的库里，warehouse 既不能跨库 UPDATE、也不能把它改成已消费），
--   11.2.3.1 的页面会永远显示「待消费」。
--
-- 【被否决的方案 C：复用 7.4.1.1 通用调整单（source_type='DEFECTIVE_DISPOSITION'）】
--   ⇒ 退货处置有自己的审批语义与**不可逆**语义（报废要审批 + 原因），
--   塞进「盘盈盘亏 / 损坏 / 错发纠正」那张通用单里，会让「这批报废货是谁批的、
--   为什么报」在调整单里被稀释成一行 DAMAGE/SCRAP 的备注；
--   而且 source 词表属于 7.4.1.1 的业务面，改它是动别的模块的表结构。
--
-- ===========================================================================
-- 二、与 11.2.3.1 的交接：消费的是那张质量状态指令表
-- ===========================================================================
--
-- 11.2.3.1（V179）在**判定那一刻**由 DB 触发器生成一条 `return_quality_disposition_instruction`
-- （表头写死 consumer_module = '11.2.3.2'，明确「消费方恒 11.2.3.2」），
-- 指令三值：MOVE_TO_SELLABLE / MOVE_TO_DEFECTIVE / WRITE_OFF_SCRAP。
-- **本模块就是它的消费方**，且本迁移**只消费它，不重建它**。
--
-- ⚠️ **本迁移对 11.2.3.1 的指令表做了一处纯增量改动**（唯一的跨模块 DDL 依赖，已尽量收窄）：
--   V179 把 `instruction_status` 钉成 `CHECK(instruction_status = 'PENDING')`，
--   注释写的是「消费侧：11.2.3.2 未交付 ⇒ 恒为 PENDING」。
--   **11.2.3.2 交付了，这个 CHECK 就成了假话**：指令消费掉之后状态仍然恒为 PENDING，
--   11.2.3.1 的质检单视图会永远显示「待消费」，两个同属 L3 11.2.3 的模块对不上账。
--   ⇒ 本迁移把该 CHECK 放宽为 `IN ('PENDING','CONSUMED')`，并：
--     · **只授 column 级 UPDATE(instruction_status)**（表级 UPDATE/DELETE 仍被 V179 收回），
--       其余列仍然改不动；
--     · 挂一条触发器：**只允许 PENDING → CONSUMED**，且必须有一张**已执行**的处置单
--       顶着这张指令（EXISTS 判定在同一库里真查一次），否则拒。
--   ⇒ 「偷偷把指令改成已消费」仍然没有路径：没有处置单就改不动，改了也不能倒回去。
--   ⚠️ 11.2.3.1 的会话记录（docs/sessions/2026-10/2026-10-19-11-2-3-1-takeover.md）已写明
--   「该表的 UPDATE/DELETE 权限已在本车道收回，11.2.3.2 落地时需要单独授权路径」——
--   本节的 column 级授权就是那条**单独的授权路径**。
--
-- ===========================================================================
-- 三、三条硬规则各自钉在哪 —— 为什么不能只写在服务层
-- ===========================================================================
--
-- 【硬规则 1】**报废不可逆，需审批与原因，不能由客服随手处理**（蓝图原话）。
--   四道闸门，全部在 DB 层：
--   ① 报废必须带**受控损耗原因码** + 非空说明（表级 CHECK + 触发器）；
--   ② 审批必须走**独立权限码**（服务层 trade.aftersales.approve）+ 必填审批意见；
--   ③ **提交人 ≠ 审批人**（触发器钉死，职责分离）；
--   ④ 一旦 EXECUTED，整行**冻结**（触发器），且**没有任何反执行端点**：
--      报废的货已经核销，库存分录已经落库，「反执行」等于凭空造货。
--
-- 【硬规则 2】**处置数量必须等于质检结论的数量**。
--   照抄 8.2.2.2 待检处置的口径：「一批待检货必须被全部处置掉
--   （处置一半意味着另一半永远没有结论）」。
--   ⇒ qty = 指令 qty，由触发器在写入时刻真查一次指令表比对，不相等当场拒。
--
-- 【硬规则 3】**报废损耗归 12 财务，而 12 财务服务尚不存在**
--   （backend/services/ 下没有 finance/，本批另一条车道正在建它）。
--   ⇒ 照抄 11.2.1.1 退货授权单的既有解法：
--     · **本模块自己的口径照算**：报废数量、原值、残值、可审阅公式算出的
--       proposed_loss_amount = original_value − salvage_amount（由触发器重算比对）；
--     · **12 财务侧的字段恒为 null**（finance_loss_amount / finance_currency /
--       finance_reference / finance_confirmed_at），
--       **绝不填 0 冒充「这批报废货没产生任何损耗」**（填 0 会让财务以为这批货零损耗），
--       并用 finance_owner_module = '12 财务' 标注归属模块；
--     · loss_status 恒为 PENDING_FINANCE。
--
-- ===========================================================================
-- 四、状态机：一个状态列到底
-- ===========================================================================
--
--   DRAFT            草稿：处置要素还在录，**没有**审批、**没有**动过库存
--   PENDING_APPROVAL 已提交待审
--   APPROVED         已批：可以执行（执行才是动库存的那一步）
--   REJECTED         已驳回（终态）
--   EXECUTED         已执行：库存已改、指令已消费、报废损耗已记（终态，**不可逆**）
--   EXECUTION_FAILED 执行失败（warehouse 不可达 / 校验不过）：**可原样重试**，
--                    审批结论仍然有效（货还没动过，不必重批）
--   CANCELLED        已撤销（终态）
--
-- ⚠️ DRAFT 之外**不允许改业务要素**（数量、来源库位、原因）：审批意见是对着
--   那些要素写的，改完要素再拿旧意见去执行，等于批的不是这一单。
--
-- ===========================================================================
-- 五、金额与数量口径
-- ===========================================================================
--   数量 NUMERIC(19,6) + Java BigDecimal；金额 NUMERIC(19,4)；全程禁浮点。
--   时间 timestamptz：业务时间由应用层以 OffsetDateTime 绑定并写 ?::timestamptz
--   （cast 一律不落在绑定序列末位，V59 / V62 / V172 同一条纪律）。
--   created_at 是系统时间，submitted_at / decided_at / executed_at 是业务时间，分开存不混用。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 一、处置决定单（头）
-- ---------------------------------------------------------------------------
CREATE TABLE return_inventory_disposition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：RDP + 序列（租户内唯一）。服务端派生，不接受调用方自填。
  disposition_no varchar(32) NOT NULL,

  -- ---- 上游：11.2.3.1 的质量状态指令（同一库，但**刻意不建 FK**）----
  -- 建 FK 会让本迁移对 11.2.3.1 的表产生 DDL 依赖（同批另有车道也在往 trade 落迁移）；
  -- 改用触发器在写入时刻真查一次钉死（见 guard_return_inventory_disposition_source）。
  instruction_id uuid NOT NULL,
  inspection_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  receipt_line_id uuid NOT NULL,

  instruction_type varchar(24) NOT NULL CHECK(instruction_type IN (
      'MOVE_TO_SELLABLE','MOVE_TO_DEFECTIVE','WRITE_OFF_SCRAP')),
  -- 目标库存状态：与指令类型一一对应（表级 CHECK 再钉一道）。
  target_inventory_state varchar(16) NOT NULL CHECK(target_inventory_state IN (
      'SELLABLE','DEFECTIVE','SCRAP')),

  -- ---- 标的物快照（与 V172 待检分录、V179 指令表同一纪律：快照随单据走）----
  sku_id uuid,
  sku_code varchar(128) NOT NULL DEFAULT '',
  sku_name varchar(300) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',

  -- 处置数量：必须等于指令数量（硬规则 2，触发器真查比对）。
  qty numeric(19,6) NOT NULL CHECK(qty > 0),

  -- ---- 来源库位（warehouse 的 stock_bucket）----
  -- 跨服务 ⇒ 只有逻辑 ID，**不建 FK**；代码快照是给人看的（「当时在哪个库位」）。
  -- ⚠️ 为什么必须显式指定来源库位而不是「按 SKU 自动找」：同一 SKU 的待检货
  --   可能散在多个库位 / 多个批次上，自动挑一条会把货从 A 位挪到 B 位 ——
  --   而「货到底在哪个库位」是仓库的事实，不该由售后单据猜。
  source_bucket_id uuid NOT NULL,
  source_warehouse_id uuid NOT NULL,
  source_warehouse_code varchar(64) NOT NULL DEFAULT '',
  source_location_id uuid NOT NULL,
  source_location_code varchar(64) NOT NULL DEFAULT '',
  source_lot_id uuid,
  source_lot_code varchar(128) NOT NULL DEFAULT '',
  source_legal_entity_id uuid NOT NULL,
  source_quality_status_code varchar(64) NOT NULL DEFAULT '',

  -- ---- 目标库位（转可售 / 转残次时必填；报废为空）----
  -- ⚠️ **目标库位不能沿用来源库位**：warehouse 的库位类型与质量状态是一一对应的
  --   （权威函数 location_allocation_required_type，V106）：
  --     SELLABLE → STORAGE，PENDING_INSPECTION → QUARANTINE，DEFECTIVE → DEFECTIVE。
  --   待检货在 QUARANTINE 位上，转可售要进 STORAGE 位，转残次要进 DEFECTIVE 位 ——
  --   库位类型对不上，货就落在「既不能发也不能清」的地方。
  --   目标库位由 warehouse 侧按 location_allocation_required_type 校验并拒绝跨仓。
  target_location_id uuid,
  target_location_code varchar(64) NOT NULL DEFAULT '',

  -- ---- 处置理由 ----
  -- 受控码 + 自由文本原文，两者分工不同（码是聚合口径，原文是给人看）。
  -- ⚠️ **码用的是「质检判定」词表（RESALABLE / DEFECTIVE / SCRAPPED）**，
  --   不是「库存状态」词表（SELLABLE / DEFECTIVE / SCRAP）——
  --   两套词表长得像但不是一回事：判定说的是「这批货怎么样」，库存状态说的是「这批货现在算什么」，
  --   混用会让报表按同一个 key 汇总两种含义的东西。
  reason_code varchar(16) NOT NULL CHECK(reason_code IN ('RESALABLE','DEFECTIVE','SCRAPPED')),
  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),
  -- 报废专属的**损耗原因码**（受控词表）：报废为什么报，是财务与供应商索赔都要引用的那一行。
  -- 非报废单恒为空串；报废单**必填**（表级 CHECK + 触发器）。
  -- ⚠️ 空串也在词表里：非报废单本来就没有「为什么报」这回事，
  --   让它填一个假码（比如 OTHER）会让「报废原因分布」里混进一堆不是报废的单。
  scrap_reason_code varchar(32) NOT NULL DEFAULT '' CHECK(scrap_reason_code IN (
      '','DAMAGED_UNREPAIRABLE','MISSING_PARTS','FAILED_FUNCTIONAL_TEST',
      'BEYOND_ECONOMIC_REPAIR','OTHER')),

  -- ---- 状态机 ----
  status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
      'DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','EXECUTED',
      'EXECUTION_FAILED','CANCELLED')),
  -- 乐观锁：每次写 +1（蓝图输入句的 expected_version）。
  version bigint NOT NULL DEFAULT 0,

  submitted_by uuid,
  submitted_at timestamptz,
  decided_by uuid,
  decided_at timestamptz,
  -- 审批意见：批准与驳回都必填（表级 CHECK + 触发器）。
  decision_note text NOT NULL DEFAULT '' CHECK(length(decision_note) <= 2000),
  -- 撤销理由：撤销也要写清为什么（留痕里会引用它）。
  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 2000),

  executed_by uuid,
  executed_at timestamptz,
  -- warehouse 的执行回执（逻辑 ID + 单号快照，跨服务不建 FK）。
  execution_transaction_id uuid,
  execution_bucket_id uuid,
  execution_failure_reason text NOT NULL DEFAULT '' CHECK(length(execution_failure_reason) <= 2000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,disposition_no),

  -- 指令类型 ↔ 目标状态一一对应。
  CHECK((instruction_type = 'MOVE_TO_SELLABLE'  AND target_inventory_state = 'SELLABLE')
     OR (instruction_type = 'MOVE_TO_DEFECTIVE'  AND target_inventory_state = 'DEFECTIVE')
     OR (instruction_type = 'WRITE_OFF_SCRAP'    AND target_inventory_state = 'SCRAP')),
  -- 处置理由码 ↔ 目标库存状态一一对应（RESALABLE↔SELLABLE / DEFECTIVE↔DEFECTIVE / SCRAPPED↔SCRAP）。
  CHECK((reason_code = 'RESALABLE' AND target_inventory_state = 'SELLABLE')
     OR (reason_code = 'DEFECTIVE' AND target_inventory_state = 'DEFECTIVE')
     OR (reason_code = 'SCRAPPED'  AND target_inventory_state = 'SCRAP')),
  -- **每一笔处置都必须写理由**（处置决定 = 一个有理由的决定；没理由的处置单没法追责）。
  CHECK(btrim(reason_note) <> ''),
  -- 报废额外必带损耗原因码（硬规则 1①）。
  CHECK(target_inventory_state <> 'SCRAP' OR btrim(scrap_reason_code) <> ''),
  -- 转可售 / 转残次必须有目标库位；报废没有目标（货被核销，不在任何库位上）。
  CHECK((target_inventory_state = 'SCRAP' AND target_location_id IS NULL)
     OR (target_inventory_state <> 'SCRAP' AND target_location_id IS NOT NULL)),
  -- 已批必须有审批人与审批意见（硬规则 1②）。
  CHECK(status NOT IN ('APPROVED','REJECTED','EXECUTED')
        OR (decided_at IS NOT NULL AND decided_by IS NOT NULL AND btrim(decision_note) <> '')),
  -- 已执行必须有执行人与执行时刻。
  CHECK(status <> 'EXECUTED' OR (executed_at IS NOT NULL AND executed_by IS NOT NULL)),
  -- 已提交必须有提交人与提交时刻。
  CHECK(status NOT IN ('PENDING_APPROVAL','APPROVED','REJECTED','EXECUTED','EXECUTION_FAILED')
        OR (submitted_at IS NOT NULL AND submitted_by IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- 二、留痕（追加式；由 DB 触发器自动追加，只有真变了才写）
-- ---------------------------------------------------------------------------
CREATE TABLE return_inventory_disposition_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  disposition_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'CREATED','UPDATED','SUBMITTED','APPROVED','REJECTED',
      'EXECUTED','EXECUTION_FAILED','CANCELLED')),
  from_status varchar(20) NOT NULL DEFAULT '',
  to_status varchar(20) NOT NULL DEFAULT '',
  target_inventory_state varchar(16) NOT NULL DEFAULT '',
  qty numeric(19,6),
  reason_note text NOT NULL DEFAULT '',
  -- 「谁在什么时候批的 / 为什么报」是报废要拿去和供应商、平台对质的举证材料。
  actor_id uuid,
  occurred_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id)
);

-- ---------------------------------------------------------------------------
-- 三、报废损耗记录（输出句第三样；12 财务侧字段恒为 null）
-- ---------------------------------------------------------------------------
CREATE TABLE return_inventory_disposition_loss (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 一张报废处置单恰好一条损耗记录。
  disposition_id uuid NOT NULL UNIQUE,
  instruction_id uuid NOT NULL,

  sku_id uuid,
  sku_code varchar(128) NOT NULL DEFAULT '',

  -- 报废数量（核销掉多少）。
  scrap_qty numeric(19,6) NOT NULL CHECK(scrap_qty > 0),
  -- 原值与残值：来自 11.2.3.1 的指令快照（残值本身已被 V179 的守卫④按可审阅规则校验过）。
  original_value numeric(19,4) NOT NULL CHECK(original_value >= 0),
  salvage_amount numeric(19,4) NOT NULL CHECK(salvage_amount >= 0),
  salvage_currency char(3) NOT NULL CHECK(salvage_currency ~ '^[A-Z]{3}$'),
  -- **本模块算出的损耗口径**（可审阅公式：原值 − 残值），由触发器重算比对。
  proposed_loss_amount numeric(19,4) NOT NULL CHECK(proposed_loss_amount >= 0),

  -- ---- 12 财务侧的字段：恒为 null（12 财务服务尚不存在）----
  -- ⚠️ **绝不填 0 冒充「这批报废货没产生任何损耗」** —— 填 0 会让财务以为这批货零损耗。
  --   这四个字段的写入所有者是 12 财务（损耗入账与凭证），本模块只留位 + 标注归属模块。
  finance_loss_amount numeric(19,4),
  finance_currency char(3),
  finance_reference varchar(64),
  finance_confirmed_at timestamptz,
  finance_owner_module varchar(16) NOT NULL DEFAULT '12 财务'
      CHECK(finance_owner_module = '12 财务'),
  loss_status varchar(16) NOT NULL DEFAULT 'PENDING_FINANCE'
      CHECK(loss_status = 'PENDING_FINANCE'),

  recorded_by uuid NOT NULL,
  recorded_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),

  UNIQUE(tenant_id,id),
  CHECK(original_value >= salvage_amount)
);

-- ===========================================================================
-- 四、DB 级行为约束
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 守卫① guard_return_inventory_disposition_source：处置必须挂在一条真的、还是待消费的指令上
-- ---------------------------------------------------------------------------
-- 真查一次（不建 FK，理由见第一节）：指令必须存在、属于本租户、状态仍是 PENDING，
-- 且本单的处置数量必须**等于**指令数量（硬规则 2）。
CREATE FUNCTION guard_return_inventory_disposition_source() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_qty numeric(19,6);
  t_state varchar(16);
  t_status varchar(16);
BEGIN
  SELECT i.qty, i.target_inventory_state, i.instruction_status
    INTO t_qty, t_state, t_status
    FROM return_quality_disposition_instruction i
   WHERE i.tenant_id = NEW.tenant_id AND i.id = NEW.instruction_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '质量状态指令不存在或不属于当前租户：instruction_id=%', NEW.instruction_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_status <> 'PENDING' THEN
    RAISE EXCEPTION '质量状态指令已被消费（当前状态 %），一条指令只能被处置一次：instruction_id=%',
      t_status, NEW.instruction_id USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.qty <> t_qty THEN
    RAISE EXCEPTION '处置数量 % 与质检结论的数量 % 不相等：一批退货必须被全部处置掉'
      '（处置一半意味着另一半永远没有结论）', NEW.qty, t_qty
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.target_inventory_state <> t_state THEN
    RAISE EXCEPTION '处置目标状态 % 与质检结论的状态 % 不一致：处置决定不能改判质检结论',
      NEW.target_inventory_state, t_state USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_inventory_disposition_source
  BEFORE INSERT OR UPDATE OF qty, instruction_id, target_inventory_state
  ON return_inventory_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_return_inventory_disposition_source();

-- ---------------------------------------------------------------------------
-- 守卫② guard_return_inventory_disposition_state：状态机只认合法流转
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_return_inventory_disposition_state() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;

  IF NOT (
       (OLD.status = 'DRAFT'             AND NEW.status IN ('PENDING_APPROVAL','CANCELLED'))
    OR (OLD.status = 'PENDING_APPROVAL'  AND NEW.status IN ('APPROVED','REJECTED','CANCELLED'))
    OR (OLD.status = 'APPROVED'          AND NEW.status IN ('EXECUTED','EXECUTION_FAILED','CANCELLED'))
    OR (OLD.status = 'EXECUTION_FAILED'  AND NEW.status IN ('EXECUTED','CANCELLED'))
  ) THEN
    RAISE EXCEPTION '处置单状态不能从 % 变到 %（合法流转：草稿→待审→已批→已执行；'
      '执行失败可原样重试；已驳回 / 已撤销 / 已执行是终态）', OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_inventory_disposition_state
  BEFORE UPDATE OF status ON return_inventory_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_return_inventory_disposition_state();

-- ---------------------------------------------------------------------------
-- 守卫③ guard_return_inventory_disposition_frozen：终态整行冻结
-- ---------------------------------------------------------------------------
-- 已执行 / 已驳回 / 已撤销都是终态。已执行尤其重要：**报废不可逆** ——
-- 库存分录已经落库、货已经核销，改这一行等于让「这批货到底报没报」出现两个答案。
CREATE FUNCTION guard_return_inventory_disposition_frozen() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.status IN ('EXECUTED','REJECTED','CANCELLED') AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION '处置单已% ，是终态不可修改：报废不可逆（库存分录已落库，货已核销）'
      '：disposition_id=%', OLD.status, OLD.id USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_inventory_disposition_frozen
  BEFORE UPDATE ON return_inventory_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_return_inventory_disposition_frozen();

-- ---------------------------------------------------------------------------
-- 守卫④ guard_return_inventory_disposition_draft_only：只有草稿能改业务要素
-- ---------------------------------------------------------------------------
-- 审批意见是对着「数量 / 来源库位 / 原因」写的。审批之后再改这些要素，
-- 等于拿旧意见去执行另一单。
CREATE FUNCTION guard_return_inventory_disposition_draft_only() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.status <> 'DRAFT' THEN
    IF NEW.qty IS DISTINCT FROM OLD.qty
       OR NEW.source_bucket_id IS DISTINCT FROM OLD.source_bucket_id
       OR NEW.source_warehouse_id IS DISTINCT FROM OLD.source_warehouse_id
       OR NEW.source_location_id IS DISTINCT FROM OLD.source_location_id
       OR NEW.source_lot_id IS DISTINCT FROM OLD.source_lot_id
       OR NEW.source_legal_entity_id IS DISTINCT FROM OLD.source_legal_entity_id
       OR NEW.target_location_id IS DISTINCT FROM OLD.target_location_id
       OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
       OR NEW.reason_note IS DISTINCT FROM OLD.reason_note
       OR NEW.scrap_reason_code IS DISTINCT FROM OLD.scrap_reason_code THEN
      RAISE EXCEPTION '处置单已提交（状态 %），数量 / 来源库位 / 原因不可再改：'
        '审批意见是对着这些要素写的，改完再执行等于批的不是这一单', OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_inventory_disposition_draft_only
  BEFORE UPDATE ON return_inventory_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_return_inventory_disposition_draft_only();

-- ---------------------------------------------------------------------------
-- 守卫⑤ guard_return_inventory_disposition_approval：职责分离 + 审批意见必填
-- ---------------------------------------------------------------------------
-- 硬规则 1②③：**提交人 ≠ 审批人**；批准与驳回都要写理由。
-- 「不能由客服随手处理」在这里被钉成 DB 不变量 —— 拿到连接直接 UPDATE 也绕不过。
CREATE FUNCTION guard_return_inventory_disposition_approval() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.status IN ('APPROVED','REJECTED','EXECUTED')
     AND OLD.status IS DISTINCT FROM NEW.status THEN
    IF NEW.decided_by IS NULL OR NEW.decided_at IS NULL THEN
      RAISE EXCEPTION '审批必须留审批人与审批时刻：disposition_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
    IF btrim(NEW.decision_note) = '' THEN
      RAISE EXCEPTION '审批意见必填：报废是不可逆的动作，必须写清为什么：disposition_id=%', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.submitted_by IS NOT NULL AND NEW.decided_by = NEW.submitted_by THEN
      RAISE EXCEPTION '提交人不能审批自己提交的处置单（职责分离）：submitted_by=decided_by=%',
        NEW.submitted_by USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_inventory_disposition_approval
  BEFORE UPDATE OF status ON return_inventory_disposition
  FOR EACH ROW EXECUTE FUNCTION guard_return_inventory_disposition_approval();

-- ---------------------------------------------------------------------------
-- 留痕追加（只有真变了状态才写）
-- ---------------------------------------------------------------------------
CREATE FUNCTION return_inventory_disposition_append_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;

  INSERT INTO return_inventory_disposition_event(
      id, tenant_id, disposition_id, event_type, from_status, to_status,
      target_inventory_state, qty, reason_note, actor_id, occurred_at)
  VALUES (
      gen_random_uuid(),
      NEW.tenant_id,
      NEW.id,
      CASE
        WHEN TG_OP = 'INSERT'                  THEN 'CREATED'
        WHEN NEW.status = 'PENDING_APPROVAL'   THEN 'SUBMITTED'
        WHEN NEW.status = 'APPROVED'           THEN 'APPROVED'
        WHEN NEW.status = 'REJECTED'           THEN 'REJECTED'
        WHEN NEW.status = 'EXECUTED'           THEN 'EXECUTED'
        WHEN NEW.status = 'EXECUTION_FAILED'   THEN 'EXECUTION_FAILED'
        WHEN NEW.status = 'CANCELLED'          THEN 'CANCELLED'
        ELSE 'UPDATED'
      END,
      -- 建单时还没有「从哪来」：空串而不是 null（from_status 是 NOT NULL，
      -- 而 null 会让「按状态回放」的查询永远带一个洞）。
      CASE WHEN TG_OP = 'INSERT' THEN '' ELSE OLD.status END,
      NEW.status,
      NEW.target_inventory_state,
      NEW.qty,
      CASE
        WHEN NEW.status = 'EXECUTED' AND NEW.target_inventory_state = 'SCRAP'
          THEN '报废核销：' || NEW.scrap_reason_code || ' — ' || coalesce(nullif(btrim(NEW.reason_note),''), '未写说明')
        WHEN NEW.status = 'EXECUTED'
          THEN '已执行库存状态变更：' || NEW.target_inventory_state || ' ' || NEW.qty
        WHEN NEW.status = 'APPROVED'  THEN coalesce(nullif(btrim(NEW.decision_note),''), '已批准')
        WHEN NEW.status = 'REJECTED'  THEN coalesce(nullif(btrim(NEW.decision_note),''), '已驳回')
        WHEN NEW.status = 'CANCELLED' THEN coalesce(nullif(btrim(NEW.cancel_reason),''), '人工撤销')
        WHEN NEW.status = 'EXECUTION_FAILED' THEN coalesce(nullif(btrim(NEW.execution_failure_reason),''), '执行失败')
        ELSE '草稿要素变更'
      END,
      coalesce(NEW.executed_by, NEW.decided_by, NEW.submitted_by, NEW.created_by),
      CASE
        WHEN TG_OP = 'INSERT'                THEN NEW.created_at
        WHEN NEW.status = 'EXECUTED'         THEN NEW.executed_at
        WHEN NEW.status = 'APPROVED'         THEN NEW.decided_at
        WHEN NEW.status = 'REJECTED'         THEN NEW.decided_at
        WHEN NEW.status = 'PENDING_APPROVAL'  THEN NEW.submitted_at
        ELSE now()
      END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_return_inventory_disposition_event
  AFTER INSERT OR UPDATE OF status ON return_inventory_disposition
  FOR EACH ROW EXECUTE FUNCTION return_inventory_disposition_append_event();

-- ---------------------------------------------------------------------------
-- 守卫⑥ guard_return_inventory_disposition_loss_amount：损耗口径由 DB 重算并比对
-- ---------------------------------------------------------------------------
-- 照抄 11.2.3.1 守卫④的纪律：金额不是「服务层算好传进来」的，是 DB 按可审阅公式重算的。
-- 公式只有一个：损耗 = 原值 − 残值。
CREATE FUNCTION guard_return_inventory_disposition_loss_amount() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE t_expected numeric(19,4);
BEGIN
  t_expected := round(NEW.original_value - NEW.salvage_amount, 4);

  IF NEW.proposed_loss_amount IS DISTINCT FROM t_expected THEN
    RAISE EXCEPTION '报废损耗口径与「原值 − 残值」算出来的不一致：提交 %，应为 %',
      NEW.proposed_loss_amount, t_expected USING ERRCODE = 'check_violation';
  END IF;

  -- 12 财务侧的字段**恒为 null**：12 财务服务尚不存在。
  IF NEW.finance_loss_amount IS NOT NULL
     OR NEW.finance_currency IS NOT NULL
     OR NEW.finance_reference IS NOT NULL
     OR NEW.finance_confirmed_at IS NOT NULL THEN
    RAISE EXCEPTION '12 财务侧的损耗字段必须恒为 null（12 财务服务尚未交付）：'
      '本模块只留位并标注归属模块，填值等于冒充财务已经入账'
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_inventory_disposition_loss
  BEFORE INSERT OR UPDATE ON return_inventory_disposition_loss
  FOR EACH ROW EXECUTE FUNCTION guard_return_inventory_disposition_loss_amount();

-- ===========================================================================
-- 五、对 11.2.3.1 指令表的纯增量改动（唯一的跨模块 DDL 依赖）
-- ===========================================================================
--
-- ① 放宽 instruction_status 的 CHECK：'PENDING' → 'PENDING' / 'CONSUMED'。
--    约束名动态取（不写死自动生成名，跨 PG 版本更稳）。
DO $$ DECLARE c text; BEGIN
  SELECT conname INTO c
    FROM pg_constraint
   WHERE conrelid = 'return_quality_disposition_instruction'::regclass
     AND contype = 'c'
     AND pg_get_constraintdef(oid) ILIKE '%instruction_status%';
  IF c IS NOT NULL THEN
    EXECUTE format('ALTER TABLE return_quality_disposition_instruction DROP CONSTRAINT %I', c);
  END IF;
END $$;

ALTER TABLE return_quality_disposition_instruction
  ADD CONSTRAINT return_quality_disposition_instruction_instruction_status_check
  CHECK (instruction_status IN ('PENDING','CONSUMED'));

-- ② 只授 column 级 UPDATE(instruction_status)。
--    表级 UPDATE / DELETE 仍被 V179 收回（其余列依旧改不动）。
GRANT UPDATE (instruction_status) ON return_quality_disposition_instruction TO trade_app;

-- ③ 消费守卫：只允许 PENDING → CONSUMED，且必须有一张**已执行**的处置单顶着这条指令。
--    「偷偷把指令改成已消费」既没有权限路径（只能改这一列），
--    也没有语义路径（没有已执行的处置单就改不动，改了也不能倒回去）。
CREATE FUNCTION guard_return_quality_disposition_instruction_consumed() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE t_count int;
BEGIN
  IF NEW.instruction_status IS NOT DISTINCT FROM OLD.instruction_status THEN
    RETURN NEW;
  END IF;

  IF NEW.instruction_status <> 'CONSUMED' OR OLD.instruction_status <> 'PENDING' THEN
    RAISE EXCEPTION '质量状态指令只能从 PENDING 变为 CONSUMED，不能变回去也不能改成别的值：'
      '% → %', OLD.instruction_status, NEW.instruction_status
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT count(*) INTO t_count
    FROM return_inventory_disposition d
   WHERE d.tenant_id = NEW.tenant_id
     AND d.instruction_id = NEW.id
     AND d.status = 'EXECUTED';

  IF t_count = 0 THEN
    RAISE EXCEPTION '质量状态指令只能在处置单真正执行（库存已改）之后才标为已消费：'
      'instruction_id=%', NEW.id USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_quality_disposition_instruction_consumed
  BEFORE UPDATE OF instruction_status ON return_quality_disposition_instruction
  FOR EACH ROW EXECUTE FUNCTION guard_return_quality_disposition_instruction_consumed();

-- ===========================================================================
-- 六、索引与发号器
-- ===========================================================================
-- ① 待处置队列：质检已判定但还没被处置的指令（本模块的入口队列）。
CREATE INDEX return_inventory_disposition_by_status
  ON return_inventory_disposition(tenant_id, status, created_at);
-- ② **一条质量状态指令同时只能有一张在途处置单**（DB 不变量而不是应用层先查一次 ——
--    并发下两个请求都能查到「还没有」，靠应用层判会双双通过）。
--    「在途」= 不在 ('CANCELLED','REJECTED') 里：撤销与驳回之后必须能对同一条指令重新处置，
--    否则一次误操作就把这批货永久卡住（而指令状态仍是 PENDING，货却没人能处置）。
CREATE UNIQUE INDEX return_inventory_disposition_one_open_per_instruction
  ON return_inventory_disposition(tenant_id, instruction_id)
  WHERE status NOT IN ('CANCELLED','REJECTED');
-- ② 按指令反查处置单（消费链路）。
CREATE INDEX return_inventory_disposition_by_instruction
  ON return_inventory_disposition(tenant_id, instruction_id);
-- ③ 按质检单反查（11.2.3.1 的质检单详情要能看到处置去了哪）。
CREATE INDEX return_inventory_disposition_by_inspection
  ON return_inventory_disposition(tenant_id, inspection_id);
-- ④ 报废单检索（损耗口径报表与索赔举证）。
CREATE INDEX return_inventory_disposition_by_scrap
  ON return_inventory_disposition(tenant_id, scrap_reason_code, created_at)
  WHERE target_inventory_state = 'SCRAP';
-- ⑤ 留痕回放。
CREATE INDEX return_inventory_disposition_event_by_disposition
  ON return_inventory_disposition_event(tenant_id, disposition_id, occurred_at);
-- ⑥ 损耗记录按状态检索（12 财务未来按 PENDING_FINANCE 取数）。
CREATE INDEX return_inventory_disposition_loss_by_status
  ON return_inventory_disposition_loss(tenant_id, loss_status, recorded_at);

CREATE SEQUENCE return_inventory_disposition_no_seq;
GRANT USAGE,SELECT ON SEQUENCE return_inventory_disposition_no_seq TO trade_app;

-- ===========================================================================
-- 七、多租户：RLS + 授权
-- ===========================================================================
-- 三张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：处置决定直接决定这批货**以什么身份重新进入销售**，
--   而「谁批的报废」是供应商索赔与平台申诉的举证材料，
--   端点又大多是**按 id 直达**的 URL —— 猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'return_inventory_disposition','return_inventory_disposition_event',
    'return_inventory_disposition_loss'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  return_inventory_disposition,
  return_inventory_disposition_event,
  return_inventory_disposition_loss TO trade_app;

-- 留痕追加式：报废的审批轨迹是要拿去对质的证据，能被悄悄改掉的证据等于没有证据。
REVOKE UPDATE,DELETE ON return_inventory_disposition_event FROM trade_app;
-- 损耗记录不可改：12 财务未来确认时走它自己的写入路径，不在本模块改。
REVOKE UPDATE,DELETE ON return_inventory_disposition_loss FROM trade_app;

-- 本迁移对 11.2.3.1 的指令表**只做了一处改动**：放宽 instruction_status 的 CHECK
-- 并加 column 级 UPDATE 授权（第五节）。其余列与表级 UPDATE/DELETE 权限**一字未动**。
