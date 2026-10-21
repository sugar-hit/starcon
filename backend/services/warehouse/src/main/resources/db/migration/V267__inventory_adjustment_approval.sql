-- ===========================================================================
-- 7.4.1.2 调整审批（warehouse 服务，卷 07 库存核算 / L3 锚点 7.4.1 库存调整）
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/07-inventory.md 第 212 行起）：
--   「**按金额/数量阈值审批调整单，审批通过后才执行库存分录；支持批量审批与驳回。**」
--   输入：「待审调整单；17 审批规则。」
--   输出：「审批结果；执行后的库存分录；审计记录。」
--   关系：「上游 = 7.4.1.1；下游 = 7.1.2.1 流水、12 财务（调整的会计影响）。
--         **库存调整是舞弊高发区**（无审批的调整 = 任意改账），阈值审批是必要控制。」
--
-- ---------------------------------------------------------------------------
-- 一、本模块与 7.4.1.1 的边界（上游已经把话说明白了，本文件照抄，不另起一套）
-- ---------------------------------------------------------------------------
-- 7.4.1.1（V260 / V261）的会话日志逐字写着：
--
--   | 能力                                        | 归属      | 7.4.1.1 的做法                          | 本模块的做法                     |
--   | 阈值审批规则（金额/数量阈值、批量审批、驳回工作流、17.4 审批中心接入） | 7.4.1.2 | capabilities 里 thresholdApproval 恒 false | **把这一位翻成 true**，并实现它 |
--   | 执行「批准」这一动作                        | 7.4.1.1   | 显式端点 POST {id}/approve + 必填理由 + 提交人≠审批人（DB CHECK）+ DB 状态机 | **不重复实现批准动作**；阈值判定接在它前面 |
--
-- ⇒ 本迁移**不改 7.4.1.1 的表结构与状态机一行**：
--   * 不 ALTER inventory_adjustment_order / _line / _attachment / _audit 的任何既有列；
--   * 不改 guard_inventory_adjustment_order_state 等九道护栏；
--   * 只在 inventory_adjustment_order 上**新增两个触发器**（纯增量，见第五节），
--     把「批准」这一步的**准入条件**接到本模块的阈值判定上。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 阈值规则为什么**自持**（不委托 17 组织的通用审批规则服务）
-- ---------------------------------------------------------------------------
-- 本仓先例：6.3.1.3 采购单审批（trade 服务，V262）踩过同一个坑并已证明结论 ——
-- identity 的 approval_trigger_rule（V89）维度只有：
--   金额（amount_currency_code + amount_min_amount + amount_max_amount）
--   + 跨法人 / 跨部门 / 新供应商 / 高风险商品（四个三态布尔）
-- **没有「数量阈值」这一维，也没有「调整单类型」这一维。**
-- identity 的 /approval-rules/evaluate 入参（ApprovalEvaluationRequest）同样只有
-- {documentTypeCode, currencyCode, amount, crossLegalEntity, crossDepartment,
--   newVendor, highRiskProduct} —— 数量与调整单类型都传不进去。
--
-- 而 7.4.1.2 的蓝图把「**金额/数量**阈值」和「调整单类型」都点明了：
--   * 数量阈值：库存调整的原生量纲就是数量（qty_delta），identity 表达不了；
--   * 调整单类型：7.4.1.1 的受控词表有 6 个原因码（OVERAGE / SHORTAGE / DAMAGE /
--     SCRAP / MISSHIP_CORRECTION / PLATFORM_VARIANCE），它们对应**完全不同的舞弊风险**
--     （报废核销 vs 盘盈补记 vs 错发纠正），identity 的规则模型没有这一维。
--
-- 要让 identity 表达这两维，就得**改 identity 的规则模型** —— 那是另一个服务的写入所有权
-- （AGENTS.md 模块边界纪律），而且 identity 的 Flyway 版本号**没有预分配给本车道**
-- （当前 max V163）⇒ 并行车道撞号会让 Flyway 直接炸掉，后果不可逆。
--
-- ⇒ 与 6.3.1.3 **同口径**：本模块自持规则表，判定唯一；
--   能力声明里 `identityApproval` 恒 `available=false` 并**点名原因**。
--
-- ---------------------------------------------------------------------------
-- 三、本模块拥有什么
-- ---------------------------------------------------------------------------
--   1. inventory_adjustment_approval_rule      阈值规则（调整单类型 + 币种 + 金额区间 + 数量区间 ⇒ 层级与人数）
--   2. inventory_adjustment_approval_approver  审批人授权（层级 + 金额授权区间 + 数量授权上限）
--   3. inventory_adjustment_approval_case       审批单（一张调整单一条链，提交那一刻冻结命中结果）
--   4. inventory_adjustment_approval_record     审批链路记录（追加式）
--   5. inventory_adjustment_valuation           调整的会计影响（**登记额 / 生效额分两列**）
--   + 判定函数 inventory_adjustment_approval_match（服务端与 DB 共用同一个实现）
--
-- ---------------------------------------------------------------------------
-- 四、🔴 金额从哪里来：warehouse **没有成本口径**，所以金额是「申报值」
-- ---------------------------------------------------------------------------
-- 蓝图要「按金额/数量阈值审批」，但本仓的 warehouse 库里：
--   * inventory_balance / stock_bucket **没有任何成本或金额列**（V1 / V2 逐列核对过）；
--   * 12 财务服务**不存在**（backend/services/ 下没有 finance）；
--   * catalog 的 sku_cost_revision.total_unit_cost 是**当前**成本修订，不是这次调整的估值，
--     且 catalog 是独立库（跨库读 = 越界）。
-- ⇒ 调整单上**没有原生金额**。本模块的做法：
--
--   **登记额**（registered_amount）：由提交人在提交时**申报**，**提交那一刻冻结**，
--     随审批单一起留痕（谁申报的、申报多少、什么时候申报）。
--   **生效额**（effective_amount）：恒为 0，直到该调整单**过账**（POSTED）才等于登记额。
--     这是「登记额 / 生效额分两列」写法（照抄 6.3.1.3 的 purchase_order_payable 与
--     11.2.3.3 的 supplier_claim_settlement）：**登记 ≠ 生效**，
--     避免「还没入账就当钱已经动了」。
--
-- 残余风险（如实写进能力声明，不藏）：申报值是提交人给的，理论上可以低报以躲开金额档。
-- 缓解：① 提交后**冻结不可改**；② 审批人**看得到申报值**并可否决；③ 申报人与审批人分离
-- （DB CHECK 钉死）；④ 申报值进 append-only 审批链路记录，事后可追。
-- 真正的库存估值口径随 12 财务落地后经授权 API / event 衔接。
--
-- ---------------------------------------------------------------------------
-- 五、DB 级行为约束（本模块新增的两道触发器，**纯增量**）
-- ---------------------------------------------------------------------------
-- 5.1 guard_inventory_adjustment_order_threshold_approval
--     **阈值闸门**：PENDING_APPROVAL → APPROVED 这一步必须先过阈值判定。
--       * 本租户**存在生效中的阈值规则** ⇒ 必须有审批单（case），且
--         case.status='APPROVED' 且 approved_count = required_approvals，
--         否则一律拒绝（人数没凑齐 / 已被驳回 / 根本没走阈值判定）。
--       * 本租户**没有任何生效规则** ⇒ 放行（阈值闸门未启用），
--         但 7.4.1.1 的基础管控**仍然生效**：提交人≠审批人（DB CHECK）、
--         必填理由（服务层）、状态机与入账闸门（V260 触发器）一道不少。
--         ——「没配规则就不审」是错的；「没配规则就**没有阈值档**」是对的。
--     为什么落 DB：调整是舞弊高发区，而「这张单是怎么被批成 APPROVED 的」
--     正是最容易被质疑的一环。白名单写死之后，「绕过阈值直接批」是一条会当场报错的 SQL。
--
-- 5.2 guard_inventory_adjustment_order_valuation_effective
--     调整单**过账**（→ POSTED）那一刻，把估值行的生效额置为登记额、状态置为 EFFECTIVE。
--     会计影响与库存分录**同时发生**（同一个事务），不会出现「账已过、金额没生效」。
--
-- ---------------------------------------------------------------------------
-- 六、阈值判定的唯一实现：inventory_adjustment_approval_match
-- ---------------------------------------------------------------------------
-- 服务端与 DB 共用这一个函数 ⇒ 不存在「两套判定漂移」。
-- 口径（照抄 6.3.1.3 的 purchase_order_approval_match）：
--   * 命中多条时按「具体维度多的优先 → priority 小的优先 → 建得早的优先」取**一条**；
--   * 一条都没命中 ⇒ rule_match='DEFAULT'，按**最低层级 L1、1 人**处理；
--     ⚠️ **绝不**因为「没配规则」就当已批 —— 审批这一环不能被悄悄跳过。
--
-- ---------------------------------------------------------------------------
-- 七、多租户与授权
-- ---------------------------------------------------------------------------
-- 五张表全是业务表（规则、审批人、审批单、链路记录、估值都是租户自己的），
-- 一视同仁 tenant_id NOT NULL + ENABLE **FORCE** RLS isolation + GRANT。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 命中判定：调整单类型 + 币种 + 金额区间 + 数量区间 ⇒ 审批层级与人数
-- ---------------------------------------------------------------------------
-- ⚠️ 这是**唯一的**判定实现：DB 触发器与服务端都调它 ⇒ 两侧不可能漂移。
--
--   规则 = （调整单类型, 币种, 金额区间, 数量区间）⇒ （要求的审批层级 required_tier,
--                                            要求的审批人数 required_approvals）
--
--   * 金额区间是**半开区间** [min_amount, max_amount)：max 为 NULL = 无上限。
--     半开区间保证相邻两条规则**既不重叠也不留缝**（闭区间会在边界上命中两条）。
--   * 数量区间同理 [min_qty, max_qty)，量纲是**绝对调整量** Σ|qty_delta|
--     （盘盈与盘亏都计入「动量」：一张 +10 / -10 的单对货位的动量是 20，不是 0）。
--   * reason_code / currency 上的 'ANY' 是**通配**。
-- ---------------------------------------------------------------------------
CREATE FUNCTION inventory_adjustment_approval_match(
  p_tenant_id uuid,
  p_reason_code varchar,
  p_amount numeric,
  p_currency char(3),
  p_qty numeric
) RETURNS TABLE (
  rule_id uuid,
  rule_name varchar,
  rule_match varchar,
  reason_code varchar,
  order_amount numeric,
  currency char(3),
  order_qty numeric,
  required_tier varchar,
  required_approvals int
) LANGUAGE plpgsql STABLE AS $$
DECLARE
  v_rule record;
BEGIN
  SELECT r.id, r.rule_name, r.required_tier, r.required_approvals
    INTO v_rule
    FROM inventory_adjustment_approval_rule r
   WHERE r.tenant_id = p_tenant_id
     AND r.status = 'ACTIVE'
     AND (r.reason_code = 'ANY' OR r.reason_code = p_reason_code)
     AND (r.currency = 'ANY' OR r.currency = p_currency)
     AND p_amount >= r.min_amount
     AND (r.max_amount IS NULL OR p_amount < r.max_amount)
     AND p_qty >= r.min_qty
     AND (r.max_qty IS NULL OR p_qty < r.max_qty)
   ORDER BY
     -- 具体优先：类型、币种、区间都写死的规则比通配的更具体。
     ((CASE WHEN r.reason_code = 'ANY' THEN 0 ELSE 1 END)
    + (CASE WHEN r.currency     = 'ANY' THEN 0 ELSE 1 END)
    + (CASE WHEN r.max_amount   IS NULL THEN 0 ELSE 1 END)
    + (CASE WHEN r.max_qty      IS NULL THEN 0 ELSE 1 END)) DESC,
     r.priority ASC,
     r.created_at ASC,
     r.id ASC
   LIMIT 1;

  IF FOUND THEN
    RETURN QUERY SELECT v_rule.id, v_rule.rule_name, 'RULE'::varchar,
                        p_reason_code, p_amount, p_currency, p_qty,
                        v_rule.required_tier, v_rule.required_approvals;
  ELSE
    -- 一条都没命中 ⇒ 走默认档（最低层级、1 人）。
    -- ⚠️ **绝不**因为「没配规则」就当已批：审批这一环不能被悄悄跳过。
    RETURN QUERY SELECT NULL::uuid, ''::varchar, 'DEFAULT'::varchar,
                        p_reason_code, p_amount, p_currency, p_qty,
                        'L1'::varchar, 1;
  END IF;
END $$;

COMMENT ON FUNCTION inventory_adjustment_approval_match(uuid, varchar, numeric, char(3), numeric) IS
  '7.4.1.2 阈值命中判定（唯一实现）：调整单类型 + 币种 + 金额区间 + 数量区间 ⇒ 审批层级与人数';

-- ---------------------------------------------------------------------------
-- 1. inventory_adjustment_approval_rule · 阈值规则
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_adjustment_approval_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_no varchar(64) NOT NULL CHECK(btrim(rule_no) <> ''),
  rule_name varchar(200) NOT NULL CHECK(btrim(rule_name) <> ''),

  -- ---- 适用调整单类型（= 7.4.1.1 的受控原因码；'ANY' = 通配）----
  -- 六码与 inventory_adjustment_reason 逐字对应（V260 的词表）。
  reason_code varchar(32) NOT NULL DEFAULT 'ANY'
      CHECK(reason_code = 'ANY' OR reason_code IN
        ('OVERAGE','SHORTAGE','DAMAGE','SCRAP','MISSHIP_CORRECTION','PLATFORM_VARIANCE')),

  -- ---- 币种（'ANY' = 通配；金额阈值与币种绑定，绝不跨币种比大小）----
  currency char(3) NOT NULL DEFAULT 'ANY'
      CHECK(currency = 'ANY' OR currency ~ '^[A-Z]{3}$'),

  -- ---- 金额阈值：**半开区间** [min_amount, max_amount)，max 为 NULL = 无上限 ----
  min_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(min_amount >= 0),
  max_amount numeric(19,4) CHECK(max_amount IS NULL OR max_amount > min_amount),

  -- ---- 数量阈值：**半开区间** [min_qty, max_qty)，量纲 = 绝对调整量 Σ|qty_delta| ----
  min_qty numeric(19,4) NOT NULL DEFAULT 0 CHECK(min_qty >= 0),
  max_qty numeric(19,4) CHECK(max_qty IS NULL OR max_qty > min_qty),

  -- ---- 命中后要求的审批层级与人数 ----
  required_tier varchar(8) NOT NULL CHECK(required_tier IN ('L1','L2','L3')),
  required_approvals int NOT NULL DEFAULT 1
      CHECK(required_approvals >= 1 AND required_approvals <= 5),

  priority int NOT NULL DEFAULT 100,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rule_no)
);

COMMENT ON TABLE inventory_adjustment_approval_rule IS
  '7.4.1.2 阈值规则：调整单类型 + 币种 + 金额区间 + 数量区间 ⇒ 要求的审批层级与人数';

CREATE INDEX inventory_adjustment_approval_rule_active
  ON inventory_adjustment_approval_rule(tenant_id, priority, created_at)
  WHERE status='ACTIVE';

-- ---------------------------------------------------------------------------
-- 2. inventory_adjustment_approval_approver · 审批人授权
-- ---------------------------------------------------------------------------
-- 「谁能审哪一档、金额授权区间多少、数量授权上限多少」。
-- 缺了授权区间就是「这个人什么都能批」—— 那不叫授权，叫没有闸门。
CREATE TABLE inventory_adjustment_approval_approver (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  user_id uuid NOT NULL,
  user_name varchar(200) NOT NULL DEFAULT '',
  approval_tier varchar(8) NOT NULL CHECK(approval_tier IN ('L1','L2','L3')),

  -- ---- 这个人的**金额授权区间**（按币种，绝不跨币种比大小）----
  currency char(3) NOT NULL DEFAULT 'ANY'
      CHECK(currency = 'ANY' OR currency ~ '^[A-Z]{3}$'),
  min_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(min_amount >= 0),
  max_amount numeric(19,4) CHECK(max_amount IS NULL OR max_amount > min_amount),

  -- ---- 数量授权上限（NULL = 不限；量纲 = 绝对调整量）----
  max_qty numeric(19,4) CHECK(max_qty IS NULL OR max_qty >= 0),

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  -- 一个人在同一租户**同一档**只有一条授权：两条授权会让「他被授权到多少」没有答案。
  UNIQUE(tenant_id,user_id,approval_tier)
);

COMMENT ON TABLE inventory_adjustment_approval_approver IS
  '7.4.1.2 审批人授权：审批层级 + 金额授权区间 + 数量授权上限（缺了区间就是「这个人什么都能批」）';

CREATE INDEX inventory_adjustment_approval_approver_active
  ON inventory_adjustment_approval_approver(tenant_id, user_id)
  WHERE status='ACTIVE';

-- ---------------------------------------------------------------------------
-- 3. inventory_adjustment_valuation · 调整的会计影响（登记额 / 生效额分两列）
-- ---------------------------------------------------------------------------
-- 12 财务服务在本仓**不存在** ⇒ 调整的会计影响只能落 warehouse 侧自有事实，
-- 不 mock 财务、不建跨服务 FK、不跨库读 finance。
--
-- 登记额：提交时申报、**提交那一刻冻结**（阈值判定的金额口径）。
-- 生效额：恒为 0，直到调整单**过账**才等于登记额（5.2 的触发器负责）。
--         「登记 ≠ 生效」避免「还没入账就当钱已经动了」。
CREATE TABLE inventory_adjustment_valuation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,order_id) REFERENCES inventory_adjustment_order(tenant_id,id),

  -- **登记额**：提交人申报的调整估值。金额一律 numeric(19,4) + BigDecimal，禁浮点。
  registered_amount numeric(19,4) NOT NULL CHECK(registered_amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- **生效额**：未过账恒为 0。
  effective_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(effective_amount >= 0),

  status varchar(16) NOT NULL DEFAULT 'REGISTERED'
      CHECK(status IN ('REGISTERED','EFFECTIVE')),

  registered_by uuid NOT NULL,
  registered_at timestamptz NOT NULL,
  effective_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一张调整单一条估值：两条估值会让「这次调整值多少钱」没有答案。
  UNIQUE(tenant_id,order_id),
  -- 登记与生效必须成套：要么都登记、要么什么都没有。
  CHECK((registered_by IS NULL) = (registered_at IS NULL)),
  -- 未生效 ⇒ 生效额恒为 0；已生效 ⇒ 生效额 = 登记额且生效时刻齐全。
  CHECK(status <> 'EFFECTIVE'
        OR (effective_amount = registered_amount AND effective_at IS NOT NULL)),
  CHECK(status = 'EFFECTIVE' OR effective_amount = 0)
);

COMMENT ON TABLE inventory_adjustment_valuation IS
  '7.4.1.2 调整的会计影响（warehouse 侧自有事实）：登记额 / 生效额分两列 —— 登记 ≠ 生效';

CREATE INDEX inventory_adjustment_valuation_order
  ON inventory_adjustment_valuation(tenant_id, order_id);

-- ---------------------------------------------------------------------------
-- 4. inventory_adjustment_approval_case · 审批单（一张调整单一条链）
-- ---------------------------------------------------------------------------
-- 命中结果在**提交那一刻冻结**进本表：这是**审计事实**，事后规则一改它不能跟着变。
-- 6.3.1.3 的 purchase_order_approval_case 是同一口径。
CREATE TABLE inventory_adjustment_approval_case (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,order_id) REFERENCES inventory_adjustment_order(tenant_id,id),

  case_no varchar(64) NOT NULL CHECK(btrim(case_no) <> ''),

  -- ---- 第几次提交（驳回后改完重提 = 第 2 次；每次提交一条**独立**的审批链）----
  -- 一张调整单只被驳回一次就作废的话，这个列永远是 1；保留它是为了「改完重提」
  -- 时**不覆盖**上一轮的判定快照与链路记录（提交那一刻冻结的东西不能被改写）。
  attempt_no int NOT NULL DEFAULT 1 CHECK(attempt_no >= 1),

  -- ---- 命中的规则（rule_match='DEFAULT' 时没有具体规则）----
  rule_id uuid,
  rule_name varchar(200) NOT NULL DEFAULT '',
  rule_match varchar(16) NOT NULL CHECK(rule_match IN ('RULE','DEFAULT')),

  -- ---- 判定口径的**冻结快照**（提交那一刻的客观事实）----
  reason_code varchar(32) NOT NULL CHECK(reason_code IN
    ('OVERAGE','SHORTAGE','DAMAGE','SCRAP','MISSHIP_CORRECTION','PLATFORM_VARIANCE')),
  order_amount numeric(19,4) NOT NULL CHECK(order_amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 绝对调整量 Σ|qty_delta|
  order_qty numeric(19,4) NOT NULL CHECK(order_qty >= 0),

  required_tier varchar(8) NOT NULL CHECK(required_tier IN ('L1','L2','L3')),
  required_approvals int NOT NULL CHECK(required_approvals >= 1 AND required_approvals <= 5),
  approved_count int NOT NULL DEFAULT 0 CHECK(approved_count >= 0),

  status varchar(16) NOT NULL DEFAULT 'PENDING'
      CHECK(status IN ('PENDING','APPROVED','REJECTED')),

  submitted_by uuid NOT NULL,
  submitted_at timestamptz NOT NULL,

  rejected_by uuid,
  rejected_at timestamptz,
  reject_reason text NOT NULL DEFAULT '' CHECK(length(reject_reason) <= 2000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id,attempt_no),
  UNIQUE(tenant_id,case_no),

  CONSTRAINT inventory_adjustment_approval_case_count_le CHECK(approved_count <= required_approvals),
  -- 通过 ⇒ 人数凑齐且没有驳回。
  CONSTRAINT inventory_adjustment_approval_case_approved_consistent CHECK(
    status <> 'APPROVED'
    OR (approved_count = required_approvals AND rejected_by IS NULL)),
  -- 驳回 ⇒ 驳回人与意见齐全（「驳回而不说为什么」等于没驳回）。
  CONSTRAINT inventory_adjustment_approval_case_rejected_consistent CHECK(
    status <> 'REJECTED'
    OR (rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND btrim(reject_reason) <> '')),
  -- 驳回之后不可能再有人批：人数不许再加。
  CONSTRAINT inventory_adjustment_approval_case_rejected_no_more CHECK(
    status <> 'REJECTED' OR approved_count = 0)
);

COMMENT ON TABLE inventory_adjustment_approval_case IS
  '7.4.1.2 审批单：提交那一刻冻结的阈值命中结果 + 审批人数累计';

-- 待我审批工作台的主查询路径（谁在等我批）。
CREATE INDEX inventory_adjustment_approval_case_pending
  ON inventory_adjustment_approval_case(tenant_id, status, submitted_at)
  WHERE status='PENDING';

-- **一张调整单同时只能有一条「活着的」审批单**（已驳回的不算活着）：
-- 两条活审批单会让「这张单该按哪条规则判、几个人批」没有答案。
CREATE UNIQUE INDEX inventory_adjustment_approval_case_live
  ON inventory_adjustment_approval_case(tenant_id, order_id)
  WHERE status <> 'REJECTED';

-- ---------------------------------------------------------------------------
-- 5. inventory_adjustment_approval_record · 审批链路记录（追加式）
-- ---------------------------------------------------------------------------
-- 「每一步记：谁批的、以什么层级、按哪条规则、批当时的金额与币种、意见是什么。」
CREATE TABLE inventory_adjustment_approval_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  case_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,case_id) REFERENCES inventory_adjustment_approval_case(tenant_id,id),
  order_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,order_id) REFERENCES inventory_adjustment_order(tenant_id,id),

  -- 动作。固定枚举而不是自由文本：自由文本让「这个动作算不算审批」变成读日志的人自己判断。
  action varchar(24) NOT NULL CHECK(action IN
    ('SUBMITTED','APPROVED','REJECTED','EVALUATED')),

  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,

  -- 批当时的层级与口径快照（事后规则一改，这些也不变）。
  approval_tier varchar(8) NOT NULL CHECK(approval_tier IN ('L1','L2','L3')),
  order_amount numeric(19,4) NOT NULL CHECK(order_amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  order_qty numeric(19,4) NOT NULL CHECK(order_qty >= 0),

  detail text NOT NULL CHECK(btrim(detail) <> ''),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);

COMMENT ON TABLE inventory_adjustment_approval_record IS
  '7.4.1.2 审批链路记录（追加式）：每一步是谁、什么层级、什么口径、什么意见';

CREATE INDEX inventory_adjustment_approval_record_case
  ON inventory_adjustment_approval_record(tenant_id, case_id, occurred_at, id);

-- append-only：审批链路只能追加。
CREATE FUNCTION guard_inventory_adjustment_approval_record_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'inventory_adjustment_approval_record 是 append-only：不允许 %，'
    '审批追责依赖未被改写的时间线。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER inventory_adjustment_approval_record_append_only
  BEFORE UPDATE OR DELETE ON inventory_adjustment_approval_record
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_approval_record_append_only();

-- ===========================================================================
-- 6. DB 级行为约束（对 7.4.1.1 的表**只加不改**）
-- ===========================================================================
--
-- 6.1 阈值闸门：PENDING_APPROVAL → APPROVED 必须先过阈值判定。
--
--     为什么必须落 DB：应用层校验可以被绕过（别的写入口、脚本、直接 SQL），
--     而「这张单是怎么被批成 APPROVED 的」正是库存调整最容易被质疑的一环。
--     V260 自己的口径同款：「CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行」。
--
--     两种放行口径（都不是「绕过审批」）：
--       A. 本租户存在**生效中**的阈值规则 ⇒ 必须有审批单，且
--          case.status='APPROVED' 且 approved_count = required_approvals。
--       B. 本租户**没有任何生效规则** ⇒ 阈值闸门未启用，放行；
--          但 7.4.1.1 的基础管控一道不少（提交人≠审批人的 DB CHECK、
--          必填理由、状态机、入账闸门）。
--
--     ⚠️ 本触发器**只加不改**：不改 V260 的 guard_inventory_adjustment_order_state、
--     不改任何既有列与约束，也不改变 APPROVED 之外任何状态的流转。
CREATE FUNCTION guard_inventory_adjustment_order_threshold_approval() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  -- ⚠️ 变量名**不能**叫 c：plpgsql 会把 SQL 里的 c.status 当成「记录变量 c 的字段」替换掉，
  -- 而 c 恰恰是下面那条 FROM 的表别名 ⇒ 同名遮蔽会让判定读到错误的行（甚至直接报字段不存在）。
  v_case record;
  v_gated boolean;
BEGIN
  -- 只管「待审 → 已批」这一步；其它流转（提交、驳回、作废、过账）一律不管。
  IF NEW.status <> 'APPROVED' OR OLD.status <> 'PENDING_APPROVAL' THEN
    RETURN NEW;
  END IF;

  -- 本租户是否启用了阈值闸门 = 存在生效中的阈值规则。
  SELECT EXISTS (SELECT 1 FROM inventory_adjustment_approval_rule r
                  WHERE r.tenant_id = NEW.tenant_id AND r.status='ACTIVE')
    INTO v_gated;

  -- 记录型变量：一条都没查到时整条记录为 NULL（与 V260 的 guard_inventory_adjustment_entry_approved 同款写法）。
  -- 「活着」= 还没被驳回：被驳回的那一版是历史快照，不是当前该按哪条规则判的依据。
  SELECT c.status, c.approved_count, c.required_approvals, c.case_no
    INTO v_case
    FROM inventory_adjustment_approval_case c
   WHERE c.tenant_id = NEW.tenant_id
     AND c.order_id  = NEW.id
     AND c.status <> 'REJECTED';

  IF v_case IS NULL THEN
    IF v_gated THEN
      RAISE EXCEPTION '调整单 % 的阈值审批未生效：本租户启用了阈值规则，'
        '批准必须走 /warehouse/inventory/adjustment-approvals（先提交冻结判定，再按规则审批）。'
        '直接调用调整单的批准端点会绕过金额/数量阈值。', NEW.id USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;

  IF v_case.status <> 'APPROVED' THEN
    RAISE EXCEPTION '调整单 % 的审批单 % 当前是 %，不能批准：'
      '只有审批人数凑齐（%/%）的审批单才能把调整单批成已批。', NEW.id, v_case.case_no, v_case.status,
      v_case.approved_count, v_case.required_approvals USING ERRCODE='23514';
  END IF;

  IF v_case.approved_count <> v_case.required_approvals THEN
    RAISE EXCEPTION '调整单 % 的审批单 % 人数未凑齐（%/%）：不能批准。',
      NEW.id, v_case.case_no, v_case.approved_count, v_case.required_approvals USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER inventory_adjustment_order_threshold_approval
  BEFORE UPDATE ON inventory_adjustment_order
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_order_threshold_approval();

COMMENT ON FUNCTION guard_inventory_adjustment_order_threshold_approval() IS
  '7.4.1.2 阈值闸门：待审 → 已批必须先过阈值判定（人数凑齐）；本租户无生效规则时阈值闸门未启用';

-- 6.2 会计影响生效：调整单过账（→ POSTED）那一刻，估值行的生效额 = 登记额。
--
--     为什么用触发器而不是服务层：过账是 7.4.1.1 的端点（POST /post），
--     本模块不改它的语义（那会动别人车道的端点）；而「账过了、金额没生效」
--     正是「登记 ≠ 生效」这条纪律被绕过的地方 —— 触发器让它与库存分录同时发生。
CREATE FUNCTION guard_inventory_adjustment_order_valuation_effective() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status <> 'POSTED' OR OLD.status = 'POSTED' THEN
    RETURN NULL;
  END IF;
  -- ⚠️ SET 的目标列**不能**带表别名（`SET v.effective_amount = ...` 是语法错误），
  --    右侧引用才可以带别名。
  UPDATE inventory_adjustment_valuation v
     SET effective_amount = v.registered_amount,
         status           = 'EFFECTIVE',
         effective_at     = NEW.posted_at
   WHERE v.tenant_id = NEW.tenant_id
     AND v.order_id  = NEW.id;
  RETURN NULL;
END $$;

CREATE TRIGGER inventory_adjustment_order_valuation_effective
  AFTER UPDATE ON inventory_adjustment_order
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_order_valuation_effective();

COMMENT ON FUNCTION guard_inventory_adjustment_order_valuation_effective() IS
  '7.4.1.2 会计影响生效：调整单过账那一刻，估值行的生效额 = 登记额';

-- ===========================================================================
-- 7. 多租户与授权（与 V260 / V262 同一套写法）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'inventory_adjustment_approval_rule',
    'inventory_adjustment_approval_approver',
    'inventory_adjustment_approval_case',
    'inventory_adjustment_approval_record',
    'inventory_adjustment_valuation'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  inventory_adjustment_approval_rule, inventory_adjustment_approval_approver,
  inventory_adjustment_approval_case, inventory_adjustment_approval_record,
  inventory_adjustment_valuation TO warehouse_app;
-- 审批链路记录是追责凭据：连运行角色都不给改删（触发器再收一道）。
REVOKE UPDATE,DELETE ON inventory_adjustment_approval_record FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V2 / V62 / V260 同口径）。
REVOKE ALL ON
  inventory_adjustment_approval_rule, inventory_adjustment_approval_approver,
  inventory_adjustment_approval_case, inventory_adjustment_approval_record,
  inventory_adjustment_valuation FROM starcon_app;
