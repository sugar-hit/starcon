-- 6.2.2.2 需求合并与批量（trade 服务）。
--
-- 蓝图 06-purchase-and-supply.md 6.2.2.2 原文：
--   职责：把多个 SKU 的需求合并成一张采购单（同一供应商），支持批量生成、批量修改数量与供应商、批量打印。
--   输入：多行需求；供应商选择（相同供应商才能合并）。
--   输出：合并后的采购单草稿；未合并行说明原因。
--   关系：上游 = 6.2.2.1；下游 = 6.3.1.2。**合并只在同一供应商内进行**，跨供应商合并会导致对账与付款错误。
--
-- 归属：**trade 服务**（蓝图 06 卷 L2 表「6.2 采购需求 → trade」；上游 6.2.2.1 采购申请同在 trade）。
-- 后端命名空间按服务走 ⇒ /trade/demand-merges；前端路由 /purchase/demand/merge（前端路由 ≠ 后端命名空间）。
--
-- ===========================================================================
-- 一、模块边界：本文件**只读**上游，不写上游
-- ===========================================================================
--   * **采购申请单 / 申请行**（6.2.2.1 的 `purchase_request` / `purchase_request_line`）只 SELECT。
--     那两张表的写入所有者是 6.2.2.1：本文件不 ALTER、不加约束、不建到它们的触发器，
--     **也不建跨表外键**（外键会在对方删申请时连带处理本表，且等于把对方的生命周期
--     绑到本模块上；6.2.2.1 自己对 6.2.1.2 / 6.2.1.3 的表也是这个口径 —— 见 V246 文件头）。
--     本模块的「这行已被合并过」判断走**自己的** `purchase_demand_merge_line_source`
--     （那里有 UNIQUE(tenant_id, source_request_line_id) ⇒ 一条需求行只能被合并一次），
--     **不**往上游表上加列来打标记 —— 加列等于改动别人的事实表。
--   * **供应商主数据在 catalog（party）** —— 跨服务，**禁止直查对方表**：
--     走 catalog 的授权只读接口（GET /catalog/supply/vendors，见 trade 的
--     SupplierPartyQueryPort）。本库只存**外部引用 id + 展示快照**，不建供应商影子表。
--   * **目标仓与法人**在 warehouse —— 跨服务，走 warehouse 的授权只读接口
--     （GET /warehouse/warehouses、GET /warehouse/legal-entities）。本库不建跨库外键 / 视图。
--
-- ===========================================================================
-- 二、🔴 蓝图加粗句「合并只在同一供应商内进行」怎么落地
-- ===========================================================================
--   蓝图原文：**合并只在同一供应商内进行，跨供应商合并会导致对账与付款错误。**
--   这条**不是提示，是不变量**，本文件用四道闸把它钉死：
--
--   ① **分组键 = 供应商**：`purchase_demand_merge_draft` 的 UNIQUE(tenant_id, batch_id, supplier_id)
--      ⇒ 同一个合并批次里**一个供应商最多一张草稿**；一行需求只可能落进**一张**草稿。
--   ② **DB 层触发器**（`guard_demand_merge_line_single_supplier`）：
--      行上的 `supplier_id` 必须等于它所属草稿的 `supplier_id`，不等直接 RAISE 23514。
--      绕过服务层写 SQL 同样被拒 —— 「只改行的供应商不搬草稿」这种半吊子改法在这里过不去。
--   ③ **草稿的供应商不可改写**（`guard_demand_merge_draft_guard`）：
--      草稿的 supplier_id / 目标仓 / 法人 / 币种一律冻结；「改供应商」只允许**把行搬到
--      另一个供应商的草稿**，不允许就地改写草稿的供应商。
--   ④ **服务层改完重新校验**：批量改供应商之后重新查一遍每张草稿的
--      `count(distinct supplier_id)`，> 1 整批拒绝（整体回滚，不留「改了一半」的中间态）。
--
--   跨供应商的需求**不是被丢掉**，而是进 `purchase_demand_merge_unmerged`
--   （未合并行）并带上**明确原因**（SUPPLIER_MISMATCH / SUPPLIER_MISSING / ...）——
--   静默丢弃与偷偷归到第一个供应商都是错的：前者用户以为合掉了，后者会付错钱。
--
-- ===========================================================================
-- 三、🔴 「批量修改供应商」会破坏 ① —— 改完必须重新校验分组
-- ===========================================================================
--   批量改供应商 = 把一批行从供应商 SA 的草稿**搬到**供应商 SB 的草稿。
--   搬运之后：
--     * 若 SB 的草稿里已有同一个 SKU ⇒ 数量相加（单价不冲突时），
--       来源行改挂到既有行上（可追溯：这行由哪几条需求合成）；
--     * 若两行单价冲突 ⇒ **整批拒绝**（见 `guard` 与服务层），
--       合成一行会把其中一条需求的价格算错 —— 那正是对账与付款错误的来源；
--     * 原草稿被搬空 ⇒ 草稿删除（空草稿不是凭据）。
--   搬运全部在**一个事务**里：任一步不满足 ⇒ 整体回滚，库里不留中间态。
--
-- ===========================================================================
-- 四、🔴 6.3.1.2 采购单**未交付** ⇒ 本模块交付的是「采购单**草稿**」
-- ===========================================================================
--   蓝图下游是 6.3.1.2「采购单创建与编辑」，本仓 `backend/services/` 下**没有任何采购单实体**。
--   所以本模块**绝不**造一张占位的假采购单表，而是把「合并后的采购单草稿」落在自己的表上：
--   `purchase_demand_merge_draft`（草稿头）+ `purchase_demand_merge_line`（草稿行）+
--   `purchase_demand_merge_line_source`（每行由哪几条需求合成 —— 事后追溯的依据）。
--   6.3.1.2 交付后由它读这份草稿去建真正的采购单（并把草稿置为 CONVERTED）。
--   草稿与采购单是**两张单据**（与 6.2.2.1「申请 ≠ 采购单」同一纪律）。
--
-- ===========================================================================
-- 五、单位与时间纪律
-- ===========================================================================
--   * 数量一律 `numeric(19,6)`（基础单位），金额 / 单价 `numeric(19,4)`。
--     **禁浮点**：Java 侧 BigDecimal，费率与系数一律 HALF_UP 取位。
--   * 时间列一律 `timestamptz`；Java 侧用 `OffsetDateTime` 绑定，SQL 的 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s）。
--   * 期望到货日是 `date`（业务日历口径），不存时刻。
--   * **金额算不出来是 NULL 不是 0**：有一行没有单价 ⇒ 草稿与批次的金额都算不出来。
--
-- ===========================================================================
-- 1. 合并批次（头）：一次「批量生成」的事实
-- ===========================================================================
CREATE TABLE purchase_demand_merge_batch (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 批次号：租户内唯一（DM-20261021-a1b2c3d4）。
  batch_no varchar(64) NOT NULL CHECK(btrim(batch_no) <> ''),
  -- 批次状态：OPEN 可继续批量修改 / FINALIZED 已定稿（内容冻结）。
  status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','FINALIZED')),
  -- **目标仓 / 法人 / 币种**：本次合并产出的采购单草稿的属性（一张草稿只对一个仓 / 一个法人 / 一种币种）。
  target_warehouse_id uuid NOT NULL,
  target_warehouse_code varchar(64) NOT NULL DEFAULT '',
  target_warehouse_name varchar(200) NOT NULL DEFAULT '',
  legal_entity_id uuid NOT NULL,
  legal_entity_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_name varchar(200) NOT NULL DEFAULT '',
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  -- 统计（由服务层重算；定稿后不可再改 —— 见状态触发器）。
  draft_count int NOT NULL DEFAULT 0 CHECK(draft_count >= 0),
  line_count int NOT NULL DEFAULT 0 CHECK(line_count >= 0),
  unmerged_count int NOT NULL DEFAULT 0 CHECK(unmerged_count >= 0),
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity >= 0),
  -- **金额算不出来是 NULL 不是 0**（有草稿的行缺单价 ⇒ 整批算不出来）。
  estimated_amount numeric(19,4) CHECK(estimated_amount IS NULL OR estimated_amount >= 0),
  -- **来源申请单**（6.2.2.1）：本批次由哪几张申请单的需求合并而来（可追溯）。
  source_request_ids uuid[] NOT NULL DEFAULT '{}',
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  finalized_at timestamptz,
  finalized_by uuid,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,batch_no),
  -- 定稿状态与定稿信息必须自洽。
  CONSTRAINT purchase_demand_merge_batch_finalized_consistent CHECK(
    (status = 'FINALIZED' AND finalized_at IS NOT NULL AND finalized_by IS NOT NULL)
    OR (status = 'OPEN' AND finalized_at IS NULL AND finalized_by IS NULL)
  )
);

-- ===========================================================================
-- 2. 采购单草稿（头）：**一个供应商一张**
-- ===========================================================================
CREATE TABLE purchase_demand_merge_draft (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  -- 草稿号：租户内唯一（PD-20261021-a1b2c3d4）。
  draft_no varchar(64) NOT NULL CHECK(btrim(draft_no) <> ''),
  -- **供应商**（分组键的硬规则所在，见文件头第二节）。
  supplier_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',
  -- 目标仓 / 法人 / 币种：继承自批次（一张草稿只对一个仓 / 一个法人 / 一种币种）。
  target_warehouse_id uuid NOT NULL,
  target_warehouse_code varchar(64) NOT NULL DEFAULT '',
  target_warehouse_name varchar(200) NOT NULL DEFAULT '',
  legal_entity_id uuid NOT NULL,
  legal_entity_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_name varchar(200) NOT NULL DEFAULT '',
  currency_code varchar(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  -- 统计。
  line_count int NOT NULL DEFAULT 0 CHECK(line_count >= 0),
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity >= 0),
  -- 金额算不出来是 NULL 不是 0。
  estimated_amount numeric(19,4) CHECK(estimated_amount IS NULL OR estimated_amount >= 0),
  -- 草稿状态：DRAFT 草稿（本模块唯一产出的状态）/ CONVERTED 已转成真正的采购单
  -- （6.3.1.2 交付后由它置位 —— 本模块**不**自己造采购单）。
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','CONVERTED')),
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,draft_no),
  -- 🔴 **一个批次内一个供应商只允许一张草稿** —— 「合并只在同一供应商内进行」的第一道闸。
  UNIQUE(tenant_id,batch_id,supplier_id),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES purchase_demand_merge_batch(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 3. 采购单草稿行
-- ===========================================================================
CREATE TABLE purchase_demand_merge_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  draft_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- **行的供应商**：必须与所属草稿的供应商一致（DB 触发器钉死）。
  supplier_id uuid NOT NULL,
  -- **合并后的下单量**（批量改数量改的就是它）。
  merged_quantity numeric(19,6) NOT NULL CHECK(merged_quantity > 0),
  -- **源需求量的合计**：合并时同一 SKU 的多条需求相加的结果。
  -- 批量改数量后它与 merged_quantity 会分叉 —— 分叉本身就是「改过」的证据（留痕表另有一份）。
  source_quantity numeric(19,6) NOT NULL CHECK(source_quantity > 0),
  -- 预估单价（可为空：采购单价是 6.3.1.2 采购单环节的事）。
  unit_price numeric(19,4) CHECK(unit_price IS NULL OR unit_price >= 0),
  -- 行金额 = 单价 × 合并后的数量（单价为空则为空）。
  line_amount numeric(19,4) CHECK(line_amount IS NULL OR line_amount >= 0),
  -- 期望到货日（取源需求行的最早值；可为空 = 交期还没定）。
  arrival_date date,
  -- 由几条需求合并而成。
  source_request_count int NOT NULL DEFAULT 1 CHECK(source_request_count >= 1),
  sort_rank int NOT NULL DEFAULT 0 CHECK(sort_rank >= 0),
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一张草稿里一个 SKU 只有一行（重复汇总会多订）。
  UNIQUE(tenant_id,draft_id,sku_id),
  FOREIGN KEY(tenant_id,draft_id) REFERENCES purchase_demand_merge_draft(tenant_id,id) ON DELETE CASCADE,
  -- 单价与行金额必须自洽：有单价必有行金额，没单价必没行金额。
  CONSTRAINT purchase_demand_merge_line_amount_consistent CHECK(
    (unit_price IS NULL AND line_amount IS NULL)
    OR (unit_price IS NOT NULL AND line_amount IS NOT NULL)
  )
);

-- ===========================================================================
-- 4. 行来源（append-only）：这一行是由哪几条需求合成的
-- ===========================================================================
CREATE TABLE purchase_demand_merge_line_source (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  draft_line_id uuid NOT NULL,
  -- **来源**（6.2.2.1 的申请单与申请行；跨模块只引用，不建外键 —— 见文件头第一条纪律）。
  source_request_id uuid NOT NULL,
  source_request_line_id uuid NOT NULL,
  source_sku_id uuid NOT NULL,
  source_sku_code varchar(64) NOT NULL DEFAULT '',
  -- 这一条需求贡献了多少量（多条相加 = 行的 source_quantity）。
  source_quantity numeric(19,6) NOT NULL CHECK(source_quantity > 0),
  merged_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 🔴 **一条需求行只能被合并一次** ⇒ 同一行不会被合进两张草稿（重复下单）。
  UNIQUE(tenant_id,source_request_line_id),
  -- ON DELETE RESTRICT（不是 CASCADE）：删行时若还挂着来源行 ⇒ 直接被拒。
  -- 否则「删掉行」会连带抹掉「这行已经被合并过」的证据，那条需求就能被再合一次。
  FOREIGN KEY(tenant_id,draft_line_id) REFERENCES purchase_demand_merge_line(tenant_id,id) ON DELETE RESTRICT
);

-- ===========================================================================
-- 5. 未合并行（append-only）：**没合成的行 + 明确原因**
-- ===========================================================================
CREATE TABLE purchase_demand_merge_unmerged (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  -- 来源（6.2.2.1 的申请单与申请行）。
  source_request_id uuid NOT NULL,
  source_request_line_id uuid NOT NULL,
  source_sku_id uuid NOT NULL,
  source_sku_code varchar(64) NOT NULL DEFAULT '',
  source_sku_name varchar(200) NOT NULL DEFAULT '',
  source_quantity numeric(19,6) CHECK(source_quantity IS NULL OR source_quantity > 0),
  -- 源需求行自己的供应商（可能为空 = 还没选供应商）。
  source_supplier_id uuid,
  source_supplier_name varchar(200) NOT NULL DEFAULT '',
  -- **原因码**：见文件头第六节。
  reason_code text NOT NULL CHECK(reason_code IN (
    'REQUEST_NOT_APPROVED',    -- 源申请单还没审（草稿 / 待审）
    'REQUEST_REJECTED',        -- 源申请单已被驳回
    'REQUEST_ALREADY_CONVERTED', -- 源申请单已转单，再合会多下一单
    'SUPPLIER_MISSING',        -- 这行还没选供应商
    'SUPPLIER_MISMATCH',       -- 跨供应商（本次只合并某个供应商时它不属于那个供应商）
    'TARGET_MISMATCH',         -- 源申请单的目标仓 / 法人与本次合并的目标不一致
    'CURRENCY_MISMATCH',       -- 源申请单的币种与本次合并的币种不一致
    'ALREADY_MERGED',          -- 这行已经被别的批次合并过
    'PRICE_CONFLICT'           -- 同一 SKU 在同一供应商下有两个不同单价，不能合成一行
  )),
  -- **原因原文**（中文，说清为什么没合上）。必填：光有原因码用户看不懂。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一个批次里同一条需求行只记一次未合并。
  UNIQUE(tenant_id,batch_id,source_request_line_id),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES purchase_demand_merge_batch(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 6. 批量修改留痕（append-only）
-- ===========================================================================
CREATE TABLE purchase_demand_batch_adjustment (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  -- 批量动作：QUANTITY 批量改数量 / SUPPLIER 批量改供应商。
  scope text NOT NULL CHECK(scope IN ('QUANTITY','SUPPLIER')),
  -- 被改的那一行（批量动作逐行留痕）。
  line_id uuid NOT NULL,
  draft_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  -- 被改的字段名（merged_quantity / supplier_id）。
  field text NOT NULL CHECK(btrim(field) <> ''),
  -- 改之前的值 / 改之后的值（原文）。
  before_value text,
  after_value text,
  -- **理由**。必填：一句「调整一下」不是留痕。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  adjusted_by uuid NOT NULL,
  adjusted_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES purchase_demand_merge_batch(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 7. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================
-- 7.1 🔴 **同一供应商硬规则**：行的 supplier_id 必须等于草稿的 supplier_id。
CREATE FUNCTION guard_demand_merge_line_single_supplier() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  draft_supplier uuid;
  batch_status text;
BEGIN
  SELECT d.supplier_id, b.status INTO draft_supplier, batch_status
    FROM purchase_demand_merge_draft d
    JOIN purchase_demand_merge_batch b
      ON b.tenant_id = d.tenant_id AND b.id = d.batch_id
   WHERE d.tenant_id = NEW.tenant_id AND d.id = NEW.draft_id;
  IF draft_supplier IS NULL THEN
    RAISE EXCEPTION '合并行找不到所属的采购单草稿（tenant=% draft=%）', NEW.tenant_id, NEW.draft_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.supplier_id IS DISTINCT FROM draft_supplier THEN
    RAISE EXCEPTION '合并只能在同一供应商内进行：行的供应商（%）与草稿的供应商（%）不一致 —— 跨供应商合并会导致对账与付款错误',
      NEW.supplier_id, draft_supplier
      USING ERRCODE='23514';
  END IF;
  IF batch_status <> 'OPEN' THEN
    RAISE EXCEPTION '合并批次已定稿（当前状态 %），不可再改行内容', batch_status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER purchase_demand_merge_line_single_supplier
  BEFORE INSERT OR UPDATE ON purchase_demand_merge_line
  FOR EACH ROW EXECUTE FUNCTION guard_demand_merge_line_single_supplier();

-- 7.2 草稿的**供应商 / 目标仓 / 法人 / 币种不可改写**：
--     「改供应商」只允许把行搬到另一个供应商的草稿，不允许就地改写草稿的供应商。
--     就地改写会让「这张草稿是哪个供应商的」与「行上的供应商」分叉。
CREATE FUNCTION guard_demand_merge_draft_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE batch_status text;
BEGIN
  IF NEW.supplier_id IS DISTINCT FROM OLD.supplier_id
     OR NEW.batch_id IS DISTINCT FROM OLD.batch_id
     OR NEW.target_warehouse_id IS DISTINCT FROM OLD.target_warehouse_id
     OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id
     OR NEW.currency_code IS DISTINCT FROM OLD.currency_code THEN
    RAISE EXCEPTION '采购单草稿「%」的供应商 / 目标仓 / 法人 / 币种不可改写：改供应商要走「批量改供应商」（把行搬到另一个供应商的草稿）', OLD.draft_no
      USING ERRCODE='23514';
  END IF;
  SELECT status INTO batch_status FROM purchase_demand_merge_batch
   WHERE tenant_id = OLD.tenant_id AND id = OLD.batch_id;
  IF batch_status = 'FINALIZED' AND (
       NEW.line_count IS DISTINCT FROM OLD.line_count
    OR NEW.total_quantity IS DISTINCT FROM OLD.total_quantity
    OR NEW.estimated_amount IS DISTINCT FROM OLD.estimated_amount
    OR NEW.note IS DISTINCT FROM OLD.note) THEN
    RAISE EXCEPTION '合并批次已定稿，采购单草稿「%」不可再改内容', OLD.draft_no
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER purchase_demand_merge_draft_guard BEFORE UPDATE ON purchase_demand_merge_draft
  FOR EACH ROW EXECUTE FUNCTION guard_demand_merge_draft_guard();

-- 7.3 批次**状态机**（OPEN → FINALIZED，一言堂）与定稿后的冻结。
CREATE FUNCTION guard_demand_merge_batch_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (OLD.status = 'OPEN' AND NEW.status = 'FINALIZED') THEN
      RAISE EXCEPTION '合并批次「%」的状态不能从 % 改成 %：合法流转只有 OPEN→FINALIZED',
        OLD.batch_no, OLD.status, NEW.status
        USING ERRCODE='23514';
    END IF;
    IF NEW.finalized_at IS NULL OR NEW.finalized_by IS NULL THEN
      RAISE EXCEPTION '合并批次「%」定稿必须记下定稿时刻与定稿人', OLD.batch_no
        USING ERRCODE='23514';
    END IF;
  END IF;
  IF OLD.status = 'FINALIZED' AND (
       NEW.draft_count IS DISTINCT FROM OLD.draft_count
    OR NEW.line_count IS DISTINCT FROM OLD.line_count
    OR NEW.unmerged_count IS DISTINCT FROM OLD.unmerged_count
    OR NEW.total_quantity IS DISTINCT FROM OLD.total_quantity
    OR NEW.estimated_amount IS DISTINCT FROM OLD.estimated_amount
    OR NEW.note IS DISTINCT FROM OLD.note
    OR NEW.source_request_ids IS DISTINCT FROM OLD.source_request_ids
    OR NEW.target_warehouse_id IS DISTINCT FROM OLD.target_warehouse_id
    OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id
    OR NEW.currency_code IS DISTINCT FROM OLD.currency_code) THEN
    RAISE EXCEPTION '合并批次「%」已定稿，内容不可再改', OLD.batch_no
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER purchase_demand_merge_batch_status BEFORE UPDATE ON purchase_demand_merge_batch
  FOR EACH ROW EXECUTE FUNCTION guard_demand_merge_batch_status();

-- 7.4 行来源 / 未合并行 / 批量修改留痕是 **append-only**：不可改写、不可删。
--     「当时合了哪些行、没合的为什么没合、改了多少」是事后追溯的凭据。
CREATE FUNCTION guard_demand_merge_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：合并的来源、未合并原因与批量修改记录是「当时做了什么」的举证凭据',
   TG_TABLE_NAME, TG_OP
   USING ERRCODE='23514';
END $$;
CREATE TRIGGER purchase_demand_merge_line_source_append_only
  BEFORE UPDATE OR DELETE ON purchase_demand_merge_line_source
  FOR EACH ROW EXECUTE FUNCTION guard_demand_merge_append_only();
CREATE TRIGGER purchase_demand_merge_unmerged_append_only
  BEFORE UPDATE OR DELETE ON purchase_demand_merge_unmerged
  FOR EACH ROW EXECUTE FUNCTION guard_demand_merge_append_only();
CREATE TRIGGER purchase_demand_batch_adjustment_append_only
  BEFORE UPDATE OR DELETE ON purchase_demand_batch_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_demand_merge_append_only();

-- ===========================================================================
-- 8. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 批次清单页：按创建时间倒序读。
CREATE INDEX purchase_demand_merge_batch_by_created ON purchase_demand_merge_batch(tenant_id, created_at desc, id);
-- 批次清单页：按状态读。
CREATE INDEX purchase_demand_merge_batch_by_status ON purchase_demand_merge_batch(tenant_id, status, created_at desc);
-- 草稿：按批次读（详情页第一段）+ 找/建某供应商的草稿。
CREATE INDEX purchase_demand_merge_draft_by_batch ON purchase_demand_merge_draft(tenant_id, batch_id, supplier_id);
-- 草稿：按供应商反查（「这个供应商有哪些采购单草稿」）。
CREATE INDEX purchase_demand_merge_draft_by_supplier ON purchase_demand_merge_draft(tenant_id, supplier_id, created_at desc);
-- 行：按草稿 + 排序位次读（详情页第二段 / 打印）。
CREATE INDEX purchase_demand_merge_line_by_draft ON purchase_demand_merge_line(tenant_id, draft_id, sort_rank, id);
-- 行：按 SKU 反查。
CREATE INDEX purchase_demand_merge_line_by_sku ON purchase_demand_merge_line(tenant_id, sku_id, draft_id);
-- 来源：按行读（可追溯面板）。
CREATE INDEX purchase_demand_merge_line_source_by_line ON purchase_demand_merge_line_source(tenant_id, draft_line_id, merged_at);
-- 未合并行：按批次 + 原因码读（未合并清单页）。
CREATE INDEX purchase_demand_merge_unmerged_by_batch ON purchase_demand_merge_unmerged(tenant_id, batch_id, reason_code);
-- 批量修改留痕：按批次读。
CREATE INDEX purchase_demand_batch_adjustment_by_batch ON purchase_demand_batch_adjustment(tenant_id, batch_id, adjusted_at);

-- ===========================================================================
-- 9. 多租户：RLS + 授权（与 trade V1 / V92 / V234 / V238 / V242 / V246 同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_demand_merge_batch','purchase_demand_merge_draft','purchase_demand_merge_line',
    'purchase_demand_merge_line_source','purchase_demand_merge_unmerged',
    'purchase_demand_batch_adjustment'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_demand_merge_batch,purchase_demand_merge_draft,purchase_demand_merge_line,
  purchase_demand_merge_line_source,purchase_demand_merge_unmerged,
  purchase_demand_batch_adjustment TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_demand_merge_batch,purchase_demand_merge_draft,purchase_demand_merge_line,
  purchase_demand_merge_line_source,purchase_demand_merge_unmerged,
  purchase_demand_batch_adjustment TO starcon_app;
-- 收回三张留痕表的改写权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON purchase_demand_merge_line_source,purchase_demand_merge_unmerged,
  purchase_demand_batch_adjustment FROM trade_app;
-- 批次 / 草稿 / 行**不提供 DELETE 端点**（草稿是业务凭据，6.3.1.2 要读它去建真正的采购单）。
-- 但 trade_app 保留 DELETE 权限：批量改供应商时要把「同 SKU 合并后被吸收掉的那一行」
-- 与「被搬空后剩下的空草稿」清掉 —— 那是**分组重排**的内部动作，不是业务删除。
REVOKE DELETE ON purchase_demand_merge_batch FROM trade_app;

-- ---------------------------------------------------------------------------
-- 10. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V234 / V238 / V242 / V246 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不写 6.2.2.1 的表**：`purchase_request` / `purchase_request_line` 只被 SELECT，
--     本文件不 ALTER 它们、不加约束它们、**不建跨表外键到它们**。
--   * 对 warehouse / catalog 的数据**只经授权 HTTP API 读**：跨库 FK 本来就禁止。
--   * 新增函数全部带 purchase_demand_ / guard_demand_merge_ 前缀，与既有函数名不冲突。
