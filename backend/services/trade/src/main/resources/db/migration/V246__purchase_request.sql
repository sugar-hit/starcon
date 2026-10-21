-- 6.2.2.1 采购申请：把计划行 / 补货建议转成采购申请，走审批，
-- 确认供应商与数量后转采购单。
--
-- 蓝图 06-purchase-and-supply.md 6.2.2.1 原文：
--   职责：把计划行/补货建议转成采购申请，走审批，确认供应商与数量后转采购单。
--   输入：计划行或建议；供应商选择；数量与交期调整；审批提交。
--   输出：采购申请单（草稿/待审/已批/已转单/已驳回）；审批记录。
--   关系：上游 = 6.2.1.2/6.2.1.3；下游 = 6.3.1.2 采购单、17 审批。
--         **申请是内部决策单据，采购单是对供应商的正式承诺**，两者分离便于审批留痕与事后追溯。
--
-- 归属：**trade 服务**（蓝图 06 卷 L2 表「6.2 采购需求 → trade」；上游 6.2.1.2 补货建议与
-- 6.2.1.3 采购计划同在 trade 服务）。前端路由 /purchase/demand/requests 是**前端**路由；
-- 后端命名空间按服务走（/trade/purchase-requests，与 6.2.1.3 的 /trade/purchase-plans 同一纪律）。
--
-- ===========================================================================
-- 一、模块边界：本文件**只读**上游，不写上游
-- ===========================================================================
--   * **采购计划行**（6.2.1.3 的 `purchase_plan` / `purchase_plan_line`）只 SELECT。
--     那两张表的写入所有者是 6.2.1.3：本文件不 ALTER、不加约束、不建到它们的触发器，
--     **也不建跨表外键**（外键会在对方删计划时连带处理本表，且等于把对方的生命周期
--     绑到本模块上；6.2.1.3 自己对 6.2.1.2 的两张表也是这个口径 —— 见 V242 文件头）。
--   * **补货建议**（6.2.1.2 的 `replenishment_suggestion`）只 SELECT，同上。
--   * **供应商主数据在 catalog（party）** —— 跨服务，**禁止直查对方表**：
--     走 catalog 的授权只读接口（GET /catalog/supply/vendors，见 trade 的
--     SupplierPartyQueryPort）。本库只存**外部引用 id + 展示快照**，
--     不建供应商影子表（那会让供应商档案有第二个写入所有者）。
--   * **目标仓与法人**在 warehouse —— 跨服务，走 warehouse 的授权只读接口
--     （GET /warehouse/warehouses、GET /warehouse/legal-entities，见 trade 的
--     PurchasePlanWarehousePort）。本库不建跨库外键、不建跨库视图。
--   * **审批在 identity（17）** —— 走 identity 已有的授权接口
--     （POST /identity/approval-rules/evaluate、POST /identity/approval-instances），
--     本库**不建审批表**（自造审批表会让 identity 那边什么都不知道，还留一条没人管的待办）。
--
-- ===========================================================================
-- 二、四张业务表的职责划分
-- ===========================================================================
--   1. `purchase_request` —— **采购申请单（头）**：来源、目标仓、法人、币种与预估金额、
--      状态（草稿/待审/已批/已转单/已驳回）、审批留痕字段。
--   2. `purchase_request_line` —— **申请行**：SKU、数量、预估单价、供应商选择、
--      期望到货日、优先级。
--   3. `purchase_request_adjustment` —— **数量与交期调整留痕**（append-only）。
--   4. `purchase_request_conversion` —— **转单事实**（append-only）：记录转单参数
--      （供应商 + 数量 + 交期 + 目标仓）与可追溯的源计划行。
--
-- ===========================================================================
-- 三、🔴 蓝图加粗句「申请是内部决策单据，采购单是对供应商的正式承诺，两者分离
--     便于审批留痕与事后追溯」怎么落地
-- ===========================================================================
--   * **两张单据、两套状态机**：本文件只管申请单（草稿 → 待审 → 已批 → 已转单 / 已驳回）。
--     **已转单这一步在本模块内是一个事实**，不是一次「调用采购单服务」——
--     6.3.1.2 采购单**未交付**（本仓 `backend/services/` 下没有任何采购单实体），
--     所以本模块**绝不**造一张假采购单表占位，而是把「转成采购单时的供应商 / 数量 /
--     交期 / 目标仓」如实记在 `purchase_request_conversion` 里。
--     6.3.1.2 交付后，由它读这份转单事实去建真正的采购单。
--   * **审批留痕在申请单上**：approval_* 字段逐条记下「谁在什么时候、依据哪条规则、
--     走了哪条流程、拿到了什么结论、结论同步有没有出错」。审批结论由 identity 裁定，
--     本服务**不发明**审批流，也**没有本地批准端点**。
--
-- ===========================================================================
-- 四、单位与时间纪律
-- ===========================================================================
--   * 数量一律 `numeric(19,6)`（基础单位），金额 / 单价 `numeric(19,4)`。
--     **禁浮点**：Java 侧 BigDecimal，费率与系数一律 HALF_UP 取位。
--   * 时间列一律 `timestamptz`；Java 侧用 `OffsetDateTime` 绑定，SQL 的 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s）。
--   * 期望到货日 / 转单交期是 `date`（业务日历口径），不存时刻。
--
-- ===========================================================================
-- 1. 采购申请单（头）
-- ===========================================================================
CREATE TABLE purchase_request (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 申请单号：租户内唯一（PR-20261021-a1b2c3d4）。
  request_no varchar(64) NOT NULL CHECK(btrim(request_no) <> ''),
  -- **来源类型**：PLAN 来自 6.2.1.3 采购计划行 / SUGGESTION 来自 6.2.1.2 补货建议 /
  -- MANUAL 手工建单。三者都只是「这批行从哪来」的举证凭据，不决定生命周期。
  source_type text NOT NULL CHECK(source_type IN ('PLAN','SUGGESTION','MANUAL')),
  -- **来源计划**（同服务，但**不建外键** —— 见文件头第一条纪律）与来源批次。
  source_plan_id uuid,
  source_batch_id uuid,
  -- **目标仓**（warehouse 拥有仓库主数据 ⇒ 跨服务，不建外键，只存 id + 展示快照）。
  target_warehouse_id uuid NOT NULL,
  target_warehouse_code varchar(64) NOT NULL DEFAULT '',
  target_warehouse_name varchar(200) NOT NULL DEFAULT '',
  -- **法人**：采购单要落到法人货权上，6.3.1.2 转单时要用。
  legal_entity_id uuid NOT NULL,
  legal_entity_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_name varchar(200) NOT NULL DEFAULT '',
  -- **币种**（审批金额阈值分支按币种匹配）。与 estimated_amount 同时为空 / 同时非空。
  currency_code varchar(3) CHECK(currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
  -- **预估采购金额**：由各行「数量 × 预估单价」汇总。
  -- **只要有一行没有预估单价就是 NULL** —— 金额不完整时不拿它去匹配审批阈值，
  -- 那会让「一半行有价」的申请命中一个只对有价场景成立的阈值。
  estimated_amount numeric(19,4) CHECK(estimated_amount IS NULL OR estimated_amount >= 0),
  -- 申请状态：DRAFT 草稿 / PENDING_APPROVAL 待审 / APPROVED 已批 /
  --          CONVERTED 已转单 / REJECTED 已驳回。
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','PENDING_APPROVAL','APPROVED','CONVERTED','REJECTED')),
  -- ================================================================
  -- 审批留痕（结论由 identity 的 17 审批裁定，本服务只如实记录）
  -- ================================================================
  -- 本次提交是否需要审批（false = 免审直通进已批）。
  approval_required boolean NOT NULL DEFAULT false,
  -- identity 侧 ApprovalEvaluationView.decision：SUBMIT_FOR_APPROVAL / EXEMPT。
  approval_decision text NOT NULL DEFAULT '',
  -- identity 侧 ApprovalEvaluationView.source：RULE / DEFAULT / UNCONTROLLED。
  approval_source text NOT NULL DEFAULT '',
  -- 命中的规则与流程（免审时规则字段留空）。
  approval_rule_id uuid,
  approval_rule_name varchar(200) NOT NULL DEFAULT '',
  approval_flow_id uuid,
  approval_flow_name varchar(200) NOT NULL DEFAULT '',
  -- identity 给出的判定说明原文。
  approval_reason text NOT NULL DEFAULT '',
  -- 审批实例（identity 侧的 approval_instance.id / instance_no）。
  -- source_system='trade' + source_reference_id=本单 id ⇒ **天然幂等**，
  -- 重复提交不会在 identity 那边造出第二条待办。
  approval_instance_id uuid,
  approval_instance_no varchar(64) NOT NULL DEFAULT '',
  approval_submitted_at timestamptz,
  approval_decided_at timestamptz,
  approval_decided_by uuid,
  -- **审批同步失败原因**：审批服务不可达时**停在待审**并把原因写在这里，
  -- **绝不自动跳到已批** —— 那等于把一张没审过的申请当成已批的采购决策。
  approval_sync_error text NOT NULL DEFAULT '',
  -- 转单时刻（已转单后回填）。
  converted_at timestamptz,
  converted_by uuid,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_no),
  -- 金额与币种**各自独立**地存在：币种是单据的属性（补货建议就带币种），
  -- 金额可能算不出来（有行没填预估单价）。
  -- 「有金额没币种」不在 DB 层拒 —— 那是**审批边界**上的事：
  -- identity 的审批提交要求金额与币种同时有或同时没有（只有金额没币种，
  -- 阈值分支匹配没有唯一答案），所以服务层在送审时会把这一对**归一**成
  -- 「这次没有金额」（金额传 null、币种传 null）—— 那是不拿一个没有口径的
  -- 数字去命中阈值，而不是把金额丢掉。
  CONSTRAINT purchase_request_currency_shape CHECK(
    currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'
  ),
  -- 已批 / 已转单的申请单必须有审批实例号（免审直通的除外 —— 免审没有实例）。
  -- ⚠️ 唯一例外是**审批同步失败**：审批服务不可达时我们**没法**建出实例，
  --    但申请单必须**停在待审**并把失败原因写进 approval_sync_error ——
  --    「没实例」在那张单上的含义是「提交没成功」，不是「免审」。
  CONSTRAINT purchase_request_approval_instance_consistent CHECK(
    approval_sync_error <> ''
    OR approval_required = false
    OR (approval_instance_id IS NOT NULL AND approval_instance_no <> '')
  ),
  -- 转单状态与转单信息必须自洽：CONVERTED ⇒ 有转单时刻与转单人。
  CONSTRAINT purchase_request_converted_consistent CHECK(
    (status = 'CONVERTED' AND converted_at IS NOT NULL AND converted_by IS NOT NULL)
    OR (status <> 'CONVERTED' AND converted_at IS NULL AND converted_by IS NULL)
  )
);

-- ===========================================================================
-- 2. 申请行
-- ===========================================================================
CREATE TABLE purchase_request_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- SKU 编码 / 名称：**单据上的展示快照**（catalog 拥有商品档案，本服务不建 SKU 影子表）。
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- **来源可追溯**（同服务只读，不建外键 —— 见文件头第一条纪律）。
  -- source_plan_line_id ⇒ 可追溯到 6.2.1.3 的计划行；source_suggestion_id ⇒ 6.2.1.2 的建议。
  source_plan_line_id uuid,
  source_plan_id uuid,
  source_suggestion_id uuid,
  -- **申请数量**（基础单位）。
  requested_quantity numeric(19,6) NOT NULL CHECK(requested_quantity > 0),
  -- **预估单价**（可留空：采购单价是采购单环节的事，6.3.1.2 未交付）。
  unit_price numeric(19,4) CHECK(unit_price IS NULL OR unit_price >= 0),
  -- **行金额** = 单价 × 数量（单价为空则为空）。
  line_amount numeric(19,4) CHECK(line_amount IS NULL OR line_amount >= 0),
  -- **供应商选择**（catalog 的 party；跨服务 ⇒ 只存外部引用 id + 展示快照）。
  -- 允许为空：申请可以先不定供应商（走审批时供应商往往还没定）。
  supplier_id uuid,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',
  -- **期望到货日**（蓝图输入句「数量与交期调整」的交期侧）。
  -- 可为空 = **交期还没定**（补货建议的 expected_arrival_date 本来就可能取不到），
  -- **不是**「不需要交期」。转单时必须落一个确定的交期（见 purchase_request_conversion）。
  requested_arrival_date date,
  -- 优先级。
  priority text NOT NULL DEFAULT 'NORMAL'
    CHECK(priority IN ('CRITICAL','HIGH','NORMAL','LOW')),
  note text NOT NULL DEFAULT '',
  -- 排序位次：落库后可回溯「当时排第几」。
  sort_rank int NOT NULL DEFAULT 0 CHECK(sort_rank >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一张申请里一个 SKU 只有一行（重复汇总会多订）。
  UNIQUE(tenant_id,request_id,sku_id),
  FOREIGN KEY(tenant_id,request_id) REFERENCES purchase_request(tenant_id,id) ON DELETE CASCADE,
  -- 单价与行金额必须自洽：有单价必有行金额，没单价必没行金额。
  CONSTRAINT purchase_request_line_amount_consistent CHECK(
    (unit_price IS NULL AND line_amount IS NULL)
    OR (unit_price IS NOT NULL AND line_amount IS NOT NULL)
  )
);

-- ===========================================================================
-- 3. 数量与交期调整留痕（append-only）
-- ===========================================================================
CREATE TABLE purchase_request_adjustment (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, request_line_id uuid NOT NULL,
  -- 被改的字段名（requested_quantity / requested_arrival_date / unit_price / priority / note）。
  field text NOT NULL CHECK(btrim(field) <> ''),
  -- 改之前的值（原文）。null = 之前没有这个值。
  before_value text,
  -- 改之后的值（原文）。
  after_value text,
  -- **调整理由**。必填：一句「调整一下」不是留痕。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  adjusted_by uuid NOT NULL,
  adjusted_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,request_line_id) REFERENCES purchase_request_line(tenant_id,id) ON DELETE RESTRICT
);

-- ===========================================================================
-- 4. 转单事实（append-only）
--    —— 6.3.1.2 采购单未交付 ⇒ 本表是「转成采购单时的参数」的**事实**，
--       而不是一张占位的假采购单。6.3.1.2 交付后由它读这份事实去建真正的采购单。
-- ===========================================================================
CREATE TABLE purchase_request_conversion (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  request_line_id uuid NOT NULL,
  -- **转单参数：供应商**（确认后的那一个）。
  supplier_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',
  -- **转单参数：数量**（确认后的数量，可以与申请数量不同 —— 那是最终承诺给供应商的量）。
  converted_quantity numeric(19,6) NOT NULL CHECK(converted_quantity > 0),
  -- **转单参数：交期**。
  converted_arrival_date date NOT NULL,
  -- **转单参数：目标仓**。
  target_warehouse_id uuid NOT NULL,
  target_warehouse_code varchar(64) NOT NULL DEFAULT '',
  target_warehouse_name varchar(200) NOT NULL DEFAULT '',
  -- **可追溯到源计划行 / 源建议**（从申请行原样带过来，不重算）。
  source_plan_line_id uuid,
  source_plan_id uuid,
  source_suggestion_id uuid,
  note text NOT NULL DEFAULT '',
  converted_by uuid NOT NULL,
  converted_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一行只能转一次单：重复转单会造出两份对供应商的承诺。
  UNIQUE(tenant_id,request_line_id),
  FOREIGN KEY(tenant_id,request_id) REFERENCES purchase_request(tenant_id,id) ON DELETE RESTRICT,
  FOREIGN KEY(tenant_id,request_line_id) REFERENCES purchase_request_line(tenant_id,id) ON DELETE RESTRICT
);

-- ===========================================================================
-- 5. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================
-- 5.1 申请单**状态机**（草稿/待审/已批/已转单/已驳回）—— 用 DB 层钉死，非法转换一律拒。
--     这是蓝图加粗句「两者分离便于审批留痕与事后追溯」的物理保证：
--     一张申请单**不可能**在没审批的情况下变成已批，也不可能已转单后又退回草稿。
CREATE FUNCTION guard_purchase_request_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status = 'DRAFT'          AND NEW.status IN ('PENDING_APPROVAL','APPROVED'))
      OR (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('APPROVED','REJECTED'))
      OR (OLD.status = 'APPROVED'      AND NEW.status = 'CONVERTED')
    ) THEN
      RAISE EXCEPTION '采购申请单「%」的状态不能从 % 改成 %：合法流转只有 草稿→待审/已批、待审→已批/已驳回、已批→已转单',
        OLD.request_no, OLD.status, NEW.status
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- 5.2 **提交后冻结内容**：一旦离开草稿，数量 / 交期 / 供应商就不许再改 ——
  --     改了就与「已审批的那一版」对不上，审批留痕也就失去意义。
  --     允许继续变化的只有状态、审批留痕字段与转单字段。
  IF OLD.status <> 'DRAFT' THEN
    IF NEW.request_no IS DISTINCT FROM OLD.request_no
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_plan_id IS DISTINCT FROM OLD.source_plan_id
       OR NEW.source_batch_id IS DISTINCT FROM OLD.source_batch_id
       OR NEW.target_warehouse_id IS DISTINCT FROM OLD.target_warehouse_id
       OR NEW.target_warehouse_code IS DISTINCT FROM OLD.target_warehouse_code
       OR NEW.target_warehouse_name IS DISTINCT FROM OLD.target_warehouse_name
       OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id
       OR NEW.legal_entity_code IS DISTINCT FROM OLD.legal_entity_code
       OR NEW.legal_entity_name IS DISTINCT FROM OLD.legal_entity_name
       OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
       OR NEW.estimated_amount IS DISTINCT FROM OLD.estimated_amount
       OR NEW.note IS DISTINCT FROM OLD.note
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
      RAISE EXCEPTION '采购申请单「%」已提交（当前状态 %），内容字段不可再改：审批留痕要能指回「当时送审的那一版」。要改请另建一张申请单',
        OLD.request_no, OLD.status
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER purchase_request_status_guard BEFORE UPDATE ON purchase_request
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_request_status();

-- 5.3 申请行的**来源列冻结**：来源计划行 / 来源建议是「这行是从哪来的」的举证凭据。
--     重新汇总**不覆盖**已存在的行（服务层跳过），所以这几列永远不该被改。
CREATE FUNCTION guard_purchase_request_line_source_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.request_id IS DISTINCT FROM OLD.request_id
     OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
     OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
     OR NEW.source_plan_line_id IS DISTINCT FROM OLD.source_plan_line_id
     OR NEW.source_plan_id IS DISTINCT FROM OLD.source_plan_id
     OR NEW.source_suggestion_id IS DISTINCT FROM OLD.source_suggestion_id
     OR NEW.sort_rank IS DISTINCT FROM OLD.sort_rank THEN
    RAISE EXCEPTION '申请行「%」的来源列不可改写：它记录的是「这行是从哪条计划行 / 补货建议来的」。要换来源请重新建一张申请单', OLD.sku_code
     USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER purchase_request_line_source_frozen BEFORE UPDATE ON purchase_request_line
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_request_line_source_frozen();

-- 5.4 申请行**只在草稿期可改**：申请一旦提交（待审 / 已批 / 已转单 / 已驳回），
--     行的数量 / 交期 / 供应商都不许再动 —— 与头部的冻结是同一条纪律的另一半。
CREATE FUNCTION guard_purchase_request_line_draft_only() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  head_status text;
BEGIN
  SELECT status INTO head_status FROM purchase_request
   WHERE id = NEW.request_id AND tenant_id = NEW.tenant_id;
  IF head_status IS NULL THEN
    RAISE EXCEPTION '申请行找不到所属申请单（tenant=% request=%）', NEW.tenant_id, NEW.request_id
      USING ERRCODE='23514';
  END IF;
  IF head_status <> 'DRAFT' THEN
    RAISE EXCEPTION '采购申请单已提交（当前状态 %），申请行不可再改：改了就与「已审批的那一版」对不上', head_status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER purchase_request_line_draft_only BEFORE INSERT OR UPDATE OR DELETE ON purchase_request_line
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_request_line_draft_only();

-- 5.5 调整留痕与转单事实是 **append-only**：不可改写、不可删。
--     「当时申请的是多少、转出去的是多少」是审批留痕与事后追溯的凭据。
CREATE FUNCTION guard_purchase_request_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：采购申请的调整与转单记录是「当时做了什么」的举证凭据',
   TG_TABLE_NAME, TG_OP
   USING ERRCODE='23514';
END $$;
CREATE TRIGGER purchase_request_adjustment_append_only BEFORE UPDATE OR DELETE ON purchase_request_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_request_append_only();
CREATE TRIGGER purchase_request_conversion_append_only BEFORE UPDATE OR DELETE ON purchase_request_conversion
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_request_append_only();

-- ===========================================================================
-- 6. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 申请清单页：按状态 + 创建时间倒序读（清单页主查询形状）。
CREATE INDEX purchase_request_by_status ON purchase_request(tenant_id, status, created_at desc, id);
-- 申请清单页：按来源计划反查（「这张计划派出了哪几张申请」）。
CREATE INDEX purchase_request_by_source_plan ON purchase_request(tenant_id, source_plan_id, created_at desc);
-- 申请清单页：按目标仓 + 状态读。
CREATE INDEX purchase_request_by_warehouse ON purchase_request(tenant_id, target_warehouse_id, status, created_at desc);
-- 申请行清单页：按申请 + 排序位次读。
CREATE INDEX purchase_request_line_by_request ON purchase_request_line(tenant_id, request_id, sort_rank, id);
-- 申请行清单页：按 SKU 反查（「这个 SKU 在哪些申请里」）。
CREATE INDEX purchase_request_line_by_sku ON purchase_request_line(tenant_id, sku_id, request_id);
-- 调整留痕：按申请行读（详情页第二段）。
CREATE INDEX purchase_request_adjustment_by_line ON purchase_request_adjustment(tenant_id, request_line_id, adjusted_at);
-- 转单事实：按申请读（详情页第三段）。
CREATE INDEX purchase_request_conversion_by_request ON purchase_request_conversion(tenant_id, request_id, converted_at);

-- ===========================================================================
-- 7. 多租户：RLS + 授权（与 trade V1 / V92 / V234 / V238 / V242 同一套写法）
-- ===========================================================================
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_request','purchase_request_line',
    'purchase_request_adjustment','purchase_request_conversion'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_request,purchase_request_line,
  purchase_request_adjustment,purchase_request_conversion TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_request,purchase_request_line,
  purchase_request_adjustment,purchase_request_conversion TO starcon_app;
-- 收回两张留痕表的改写权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON purchase_request_adjustment,purchase_request_conversion FROM trade_app;
-- 申请单**不提供 DELETE**：申请是业务凭据（行调整 / 转单事实都指向它）。
-- 删除留痕的级联会抹掉「这张申请当时被调整过、被转过单」的证据。
REVOKE DELETE ON purchase_request,purchase_request_line FROM trade_app;

-- ---------------------------------------------------------------------------
-- 8. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V234 / V238 / V242 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不写 6.2.1.2 / 6.2.1.3 的表**：`replenishment_suggestion` / `purchase_plan` /
--     `purchase_plan_line` 只被 SELECT，本文件不 ALTER 它们、不加约束它们、
--     **不建跨表外键到它们**（那两张表的写入所有权分别是 6.2.1.2 与 6.2.1.3）。
--   * 对 warehouse / catalog 的数据**只经授权 HTTP API 读**：
--     跨库 FK 本来就禁止，所以本文件既没有约束到对方库的任何一张表。
--   * 新增函数全部带 purchase_request_ 前缀，与 V1 / V25 / V92 / V234 / V238 / V242
--     的既有函数名不冲突。
