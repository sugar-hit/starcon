-- ===========================================================================
-- 10.7.1.3 弃置与销毁（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.7.1.3 弃置与销毁
--   - 职责：管理弃置与销毁：无销售价值/品牌方要求/法规不允许再销售的退货，处理销毁、
--     留存销毁凭证/照片/重量记录，满足平台与品牌方的销毁证明要求；销毁数量可作为
--     品牌方索赔依据。
--   - 输入：待销毁清单；销毁方式（压碎/焚烧/专业销毁）；凭证要求。
--   - 输出：销毁单 + 凭证 + 数量/重量记录；索赔依据（→ 6.1.2.3 / 11.2.3.3）。
--   - 关系：上游 = 10.7.1.1；下游 = 12 费用、11.2.3.3 供应商/品牌索赔。**品牌化客户常有
--     "销毁并回传证明"合约义务**，销毁凭证缺失会触发品牌方追责。
--
-- ---------------------------------------------------------------------------
-- 零、🔴🔴🔴 本迁移最重要的一件事：**销毁凭证缺失 ⇒ 不许置为完成态**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「销毁凭证缺失会触发品牌方追责」在本迁移上**不是一句提醒**，
-- 是**四道结构闸**，缺一道那道闸就只是注释：
--
--   ① **销毁方式是枚举事实**，不是自由文本：`disposal_method` 白名单
--      SHRED（压碎）/ INCINERATE（焚烧）/ PROFESSIONAL（专业销毁），
--      列上**不写 DEFAULT** —— 少给一列直接 not-null violation（23502），
--      而不是悄悄落成某个方式。
--   ② **凭证要求是算出来的，不是填出来的**：`require_photo` / `require_weight` /
--      `require_certificate` / `require_executor` 四面旗子由**触发器**按
--      「销毁方式基线 ∪ 品牌方 / 平台政策」**重算**并写回（客户端填什么都作废），
--      定到 SCHEDULED 之后**冻结**（不许事后放松）。
--   ③ **凭证是真的行**：`return_disposal_evidence` 一行一件凭证
--      （照片 / 重量 / 处置证明编号 / 执行方签核），每类**字段必填**（列级 CHECK），
--      凭证不可删（REVOKE DELETE）且销毁单完成后**不可再改**。
--   ④ **完成态的门在 DB 上**：触发器 `guard_return_disposal_order_state` 在
--      status → 'DISPOSED' 时逐项核对四面旗子 + 时刻 + 执行方，
--      缺一件直接 RAISE —— **绕过服务层直接写 SQL 也进不去**。
--      列级 CHECK 再钉一道（disposed_at / executed_by / executor_name 缺一不可）。
--
-- ---------------------------------------------------------------------------
-- 一、本模块的职责边界：站在 10.7.1.1 之上做**真的销毁**，不重做处置决策
-- ---------------------------------------------------------------------------
-- 上游 10.7.1.1 退货接收与处置决策**已交付且就在本服务（channel）**
-- ⇒ 本模块**真的读** `return_disposition_order`：
--   只有 `disposition_method = 'DISCARD'` 且 `status = 'ISSUED'` 的处置单
--   才是「待销毁清单」里的货。转销售 / 退运回国的处置单**根本进不来**
--   （服务层 422 + DB 触发器各拒一次）。
--   一条处置单最多进一个销毁单（UNIQUE(tenant_id, disposition_order_id)）。
--
-- 本模块**不重做**上游的活：不改处置决策、不改处置单、不改 10.7.1.1 的库存指令。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 下游缺口按仓库先例显式声明、不读写不存在的服务
-- ---------------------------------------------------------------------------
--   * **12 费用（finance 服务）当前不存在**
--     ⇒ 销毁费用以本服务自持的成本行落地（`return_disposal_cost`），
--       金额一律 BigDecimal ↔ NUMERIC(19,4)，**禁浮点**；
--       合计**不落表**，查询时 SUM 现算。
--   * **11.2.3.3 供应商索赔在 trade（跨服务）**
--     ⇒ 本模块**只产出索赔依据事实**（`return_disposal_claim_basis`），
--       以 `source_reference` 记上游单号做逻辑引用，
--       **零跨库 join、零跨服务 FK、绝不写 trade 的任何表**。
--   * **7 库存（7.4.1.1 调整单管理，warehouse）**
--     ⇒ 10.7.1.2 的报废分录 consumer_module 恒为 '10.7.1.3'（**本模块**）：
--       那批货到货后被本模块销毁，本模块把它销账即可，
--       **不需要**再开一张库存分录（凭空再开一张就是记两遍）。
--
-- 🔴 币种口径（本模块**不做任何汇率换算**）：
--   销毁单有**一个费用币种** `cost_currency`；成本行币种与索赔依据的索赔币种
--   **必须**与它逐字相同（触发器钉死）—— 混币种的「合计」是没有意义的数字。
--   本模块**没有汇率表、没有折算列、没有隐式净额**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 凭证要求只能**加严**，不能放松
-- ---------------------------------------------------------------------------
-- `return_disposal_evidence_policy` 是**租户级配置**（品牌方要求 / 平台政策 /
-- 租户默认），它**只加严**销毁方式的基线要求（取并集），**永远不能放松**：
-- 品牌化客户的合约义务（「销毁并回传证明」）是**下限**，不是可选项。
-- 因此这张表**只有租户隔离策略、没有负责人数据范围策略**
-- —— 与 10.7.1.1 的 `return_resale_restriction` 同纪律：
-- 一条对全租户生效的再销售 / 销毁限制，如果只有建它的人看得见，
-- 另一个运营就会在**看不见限制**的情况下把货销毁掉。
--
-- ---------------------------------------------------------------------------
-- 四、六张表：为什么是六张
-- ---------------------------------------------------------------------------
--   return_disposal_evidence_policy  凭证要求（**配置**：品牌方 / 平台 / 租户默认）
--   return_disposal_order             销毁单（**执行**：方式 / 数量 / 重量 / 状态机）
--   return_disposal_evidence          销毁凭证（**照片 / 重量 / 处置证明 / 执行方**）
--   return_disposal_cost              销毁费用行（逐笔费用，合计现算）
--   return_disposal_claim_basis       索赔依据（→ 11.2.3.3 / 6.1.2.3 的交接凭据）
--   return_disposal_event             留痕（追加式）
--
-- 「要求」是**配置**（可以登记、可以撤销，与某一次销毁无关）；
-- 「销毁单」是**执行**（这次怎么销毁的、销毁了多少）；
-- 「凭证」是**那一次的凭据**（照片 / 重量 / 证明编号 / 执行方签核）；
-- 「费用」是**钱**；「索赔依据」是**给下游索赔用的凭据**；
-- 「留痕」是**发生过什么**。混在一张表上就会出现
-- 「一行既是政策、又是这批货、又是凭证、又是一笔钱」。
--
-- ===========================================================================

-- ===========================================================================
-- 1. 凭证要求（**配置**：品牌方 / 平台 / 租户默认）
-- ===========================================================================
-- 🔴 为什么是本服务自持：蓝图把「凭证要求」列为本模块的**输入**，
--   而 trade / catalog / warehouse 全库都没有「销毁凭证要求」这类表
--   （catalog 只有品牌档案与授权凭证，11.2.3.3 的 `return_quality_disposition_instruction`
--   是**质量状态**指令不是**销毁证明要求**），所以它归本模块自持。
--
-- subject_type 三值：
--   GENERAL  租户默认要求（subject_ref 固定写 'ALL'，不参与匹配）
--   BRAND    品牌方要求（subject_ref = 品牌编码，与销毁单的 brand_code 对齐）
--   PLATFORM 平台政策（subject_ref = 平台编码，与销毁单的 platform_code 对齐）
CREATE TABLE return_disposal_evidence_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  subject_type varchar(16) NOT NULL CHECK(subject_type IN ('GENERAL','BRAND','PLATFORM')),
  subject_ref varchar(128) NOT NULL,

  -- 🔴 四面旗子 = 四类凭证：照片 / 重量 / 处置证明编号 / 执行方签核
  required_photo boolean NOT NULL DEFAULT true,
  required_weight boolean NOT NULL DEFAULT false,
  required_certificate boolean NOT NULL DEFAULT false,
  required_executor boolean NOT NULL DEFAULT true,

  effective_from timestamptz NOT NULL,
  -- 结束时间为空 = 长期有效
  effective_to timestamptz,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),

  revoked_at timestamptz,
  revoked_by uuid,
  revoke_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, subject_type, subject_ref),
  CHECK(btrim(subject_ref) <> ''),
  -- GENERAL 的 subject_ref 是保留位，不参与匹配
  CHECK(subject_type <> 'GENERAL' OR subject_ref = 'ALL'),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- 撤销必须留痕：撤销时间 / 撤销人 / 撤销理由三者缺一不可
  CHECK(status <> 'REVOKED' OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL
      AND btrim(revoke_reason) <> ''))
);

-- 判定热路径：某租户当前生效的要求（销毁单建单时要按品牌 / 平台匹配）
CREATE INDEX return_disposal_evidence_policy_active
  ON return_disposal_evidence_policy(tenant_id, subject_type, subject_ref)
  WHERE status = 'ACTIVE';

-- ===========================================================================
-- 2. 销毁单（**执行**：方式 / 数量 / 重量 / 状态机）
-- ===========================================================================
CREATE TABLE return_disposal_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  disposal_no varchar(32) NOT NULL,

  -- 🔴 上游 10.7.1.1 处置单（**同服务** ⇒ 真的读它；触发器钉死「必须是弃置销毁」）
  disposition_order_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  -- 上游逻辑引用：记上游处置单号（跨服务交接时不必改表结构）
  source_reference varchar(64) NOT NULL DEFAULT '',
  receipt_no varchar(32) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  brand_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴🔴 销毁方式：**枚举事实**，列上**不写 DEFAULT**（SHRED 压碎 / INCINERATE 焚烧 /
  --      PROFESSIONAL 专业销毁）。少给一列 ⇒ not-null violation（23502）。
  disposal_method varchar(24) NOT NULL CHECK(disposal_method IN (
      'SHRED','INCINERATE','PROFESSIONAL')),

  -- 数量 / 重量（禁浮点：NUMERIC(19,6) + BigDecimal）
  disposal_qty numeric(19,6) NOT NULL CHECK(disposal_qty > 0),
  -- 重量记录：销毁**时**才落数（由 WEIGHT 凭证或登记请求给出），之前为 0
  weight_kg numeric(19,6) NOT NULL DEFAULT 0 CHECK(weight_kg >= 0),
  weight_unit char(3) NOT NULL DEFAULT 'KG' CHECK(weight_unit IN ('KG','G','LB')),

  -- DRAFT 草稿 · SCHEDULED 已排期（要求在此冻结）· DISPOSED 已销毁（完成态）· CANCELLED 已取消
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN (
      'DRAFT','SCHEDULED','DISPOSED','CANCELLED')),

  planned_at timestamptz,
  -- 🔴 完成态的「时刻」与「执行方」
  disposed_at timestamptz,
  executed_by uuid,
  executor_name varchar(128) NOT NULL DEFAULT '',

  -- 🔴 凭证要求快照（**由触发器重算并冻结**，客户端填什么都不作废）
  require_photo boolean NOT NULL DEFAULT false,
  require_weight boolean NOT NULL DEFAULT false,
  require_certificate boolean NOT NULL DEFAULT false,
  require_executor boolean NOT NULL DEFAULT false,

  -- 🔴 费用币种：成本行与索赔依据的币种必须与它逐字相同
  cost_currency char(3) NOT NULL CHECK(cost_currency ~ '^[A-Z]{3}$'),

  cancelled_by uuid,
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 1,
  -- 负责人归属快照（与 V1 shop / V214 / V219 同构）
  owner_id uuid,
  department_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, disposal_no),
  -- 🔴 一条处置单最多进一个销毁单：同一批货不会被销毁两次、也不会被两张单分摊
  UNIQUE(tenant_id, disposition_order_id),
  CHECK(btrim(sku_code) <> ''),
  CHECK(btrim(source_reference) <> ''),
  CHECK(status <> 'CANCELLED' OR btrim(cancel_reason) <> ''),
  -- 🔴 完成态必须带「时刻」与「执行方」（与触发器同一口径，列级再钉一道）
  CHECK(status <> 'DISPOSED' OR (disposed_at IS NOT NULL AND executed_by IS NOT NULL
      AND btrim(executor_name) <> ''))
);

CREATE INDEX return_disposal_order_status
  ON return_disposal_order(tenant_id, status, created_at DESC);
CREATE INDEX return_disposal_order_method
  ON return_disposal_order(tenant_id, disposal_method, created_at DESC);
CREATE INDEX return_disposal_order_disposition
  ON return_disposal_order(tenant_id, disposition_order_id);
CREATE INDEX return_disposal_order_brand
  ON return_disposal_order(tenant_id, brand_code, created_at DESC);
CREATE INDEX return_disposal_order_time
  ON return_disposal_order(tenant_id, created_at DESC);

-- ===========================================================================
-- 3. 销毁凭证（**照片 / 重量 / 处置证明编号 / 执行方签核**）
-- ===========================================================================
-- 🔴 每类凭证的**专属字段必填**（列级 CHECK）—— 一条「凭证」不许是空壳：
--   照片凭证必须有照片地址，重量凭证必须有重量，处置证明必须有编号，执行方签核必须有执行方。
CREATE TABLE return_disposal_evidence (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  disposal_order_id uuid NOT NULL,
  evidence_kind varchar(16) NOT NULL CHECK(evidence_kind IN (
      'PHOTO','WEIGHT','CERTIFICATE','EXECUTOR')),

  photo_url varchar(512) NOT NULL DEFAULT '',
  weight_kg numeric(19,6),
  certificate_no varchar(64) NOT NULL DEFAULT '',
  executor_name varchar(128) NOT NULL DEFAULT '',

  -- 🔴 凭证的「时刻」：事情发生的那一刻（不是录入的那一刻）
  occurred_at timestamptz NOT NULL,
  recorded_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一类凭证一张：第二张 PHOTO 不是「更全的凭证」，是「两个互相矛盾的事实」
  UNIQUE(tenant_id, disposal_order_id, evidence_kind),
  CHECK(evidence_kind <> 'PHOTO' OR btrim(photo_url) <> ''),
  CHECK(evidence_kind <> 'WEIGHT' OR weight_kg IS NOT NULL),
  CHECK(evidence_kind <> 'CERTIFICATE' OR btrim(certificate_no) <> ''),
  CHECK(evidence_kind <> 'EXECUTOR' OR btrim(executor_name) <> '')
);

CREATE INDEX return_disposal_evidence_order
  ON return_disposal_evidence(tenant_id, disposal_order_id);

-- ===========================================================================
-- 4. 销毁费用行（→ 12 费用；finance 服务不存在 ⇒ 本服务自有事实）
-- ===========================================================================
-- 蓝图点名的「销毁费」在这里；**合计不落表**（查询时 SUM 现算）。
CREATE TABLE return_disposal_cost (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  disposal_order_id uuid NOT NULL,
  cost_type varchar(24) NOT NULL CHECK(cost_type IN (
      'DESTROY_FEE','PICKUP_FEE','TRANSPORT_FEE','LABEL_FEE','OTHER')),
  amount numeric(19,4) NOT NULL CHECK(amount >= 0),
  -- 🔴 币种必须与销毁单的费用币种逐字相同（触发器钉死）
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 谁收的钱：销毁厂 / 回收商 / 承运商 —— 用来对账与追责
  vendor_ref varchar(128) NOT NULL DEFAULT '',
  incurred_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX return_disposal_cost_order
  ON return_disposal_cost(tenant_id, disposal_order_id);

-- ===========================================================================
-- 5. 索赔依据（→ 11.2.3.3 供应商索赔 / 6.1.2.3 的**交接凭据**）
-- ===========================================================================
-- 🔴 蓝图输出「索赔依据（→ 6.1.2.3 / 11.2.3.3）」：销毁数量可作为品牌方索赔依据。
--   11.2.3.3 在 **trade（跨服务）** ⇒ 本表是**本服务自持的事实**：
--   只记「依据是什么」（数量 / 重量 / 凭证齐备性 / 对方 / 金额 / 币种），
--   **不写 trade 的任何表**；下游索赔单号由人工回填（`claim_ref`）做逻辑引用。
--   索赔依据**一条销毁单一条**（UNIQUE）：同一批货不能被拿去索赔两次。
CREATE TABLE return_disposal_claim_basis (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  disposal_order_id uuid NOT NULL,
  -- BRAND 品牌方索赔 · SUPPLIER 供应商索赔
  claim_type varchar(16) NOT NULL CHECK(claim_type IN ('BRAND','SUPPLIER')),
  counterparty_code varchar(64) NOT NULL,

  -- 🔴 数量 / 重量记录（**必须与销毁单逐字相同**，触发器钉死）
  claim_qty numeric(19,6) NOT NULL CHECK(claim_qty > 0),
  claim_weight_kg numeric(19,6) NOT NULL DEFAULT 0 CHECK(claim_weight_kg >= 0),
  -- 索赔金额（12 费用 finance 服务不存在 ⇒ 只落本服务内事实，不与 12 对账）
  claimed_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(claimed_amount >= 0),
  -- 🔴 索赔币种必须与销毁单费用币种逐字相同
  claimed_currency char(3) NOT NULL CHECK(claimed_currency ~ '^[A-Z]{3}$'),

  -- 出依据那一刻的凭证齐备性（**由触发器重算**，客户端自报不作数）
  evidence_complete boolean NOT NULL DEFAULT false,

  -- 上游逻辑引用（上游处置单号）
  source_reference varchar(64) NOT NULL DEFAULT '',
  -- 下游索赔单号（人工回填的逻辑引用）
  claim_ref varchar(64) NOT NULL DEFAULT '',

  -- PENDING 待提交下游 · SUBMITTED 已提交下游 · REJECTED 下游驳回
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN (
      'PENDING','SUBMITTED','REJECTED')),
  submitted_at timestamptz,
  submitted_by uuid,
  reject_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, disposal_order_id),
  CHECK(btrim(counterparty_code) <> ''),
  -- 已提交下游就必须留下下游单号 + 时刻 + 人
  CHECK(status <> 'SUBMITTED' OR (btrim(claim_ref) <> ''
      AND submitted_at IS NOT NULL AND submitted_by IS NOT NULL)),
  CHECK(status <> 'REJECTED' OR btrim(reject_reason) <> '')
);

CREATE INDEX return_disposal_claim_basis_order
  ON return_disposal_claim_basis(tenant_id, disposal_order_id);
CREATE INDEX return_disposal_claim_basis_status
  ON return_disposal_claim_basis(tenant_id, status, created_at DESC);

-- ===========================================================================
-- 6. 留痕（追加式）
-- ===========================================================================
CREATE TABLE return_disposal_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  event_type varchar(32) NOT NULL CHECK(event_type IN (
      'ORDER_CREATED','ORDER_SCHEDULED','EVIDENCE_RECORDED','ORDER_DISPOSED',
      'COST_RECORDED','CLAIM_BASIS_CREATED','CLAIM_SUBMITTED','CLAIM_REJECTED',
      'ORDER_CANCELLED','POLICY_CREATED','POLICY_REVOKED')),
  order_id uuid,
  evidence_id uuid,
  cost_id uuid,
  claim_basis_id uuid,
  policy_id uuid,
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  detail text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX return_disposal_event_order
  ON return_disposal_event(tenant_id, order_id, occurred_at DESC);

-- ===========================================================================
-- 7. 单据号序列
-- ===========================================================================
CREATE SEQUENCE return_disposal_order_no_seq;

GRANT USAGE,SELECT ON SEQUENCE return_disposal_order_no_seq TO channel_app;

-- ===========================================================================
-- 8. 触发器：结构事实不许靠服务层自觉
-- ===========================================================================
-- (a) 操作者由 app.actor 盖章：客户端伪造不了「谁记的凭证、谁留的痕」。
CREATE FUNCTION stamp_return_disposal_actor() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.recorded_by := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.recorded_by IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposal_evidence_actor
  BEFORE INSERT ON return_disposal_evidence
  FOR EACH ROW EXECUTE FUNCTION stamp_return_disposal_actor();

CREATE FUNCTION stamp_return_disposal_event_actor() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.actor_id := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.actor_id IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposal_event_actor
  BEFORE INSERT ON return_disposal_event
  FOR EACH ROW EXECUTE FUNCTION stamp_return_disposal_event_actor();

-- (b) 🔴 销毁方式基线：**方式决定凭证要求**（枚举事实，不是自由文本）
--     压碎  → 照片 + 执行方签核
--     焚烧  → 照片 + 处置证明编号 + 执行方签核
--     专业销毁 → 照片 + 重量记录 + 处置证明编号 + 执行方签核
CREATE FUNCTION return_disposal_method_requirements(p_method varchar)
RETURNS TABLE(require_photo boolean, require_weight boolean,
               require_certificate boolean, require_executor boolean)
LANGUAGE plpgsql STABLE AS $$ BEGIN
  RETURN QUERY SELECT
    true,
    p_method = 'PROFESSIONAL',
    p_method IN ('INCINERATE','PROFESSIONAL'),
    true;
END $$;

-- (c) 🔴 凭证要求 = 方式基线 **∪** 品牌方 / 平台政策（**只能加严，不能放松**）
--     🔴 这张政策表**只有租户隔离策略、没有数据范围策略**（见本迁移第三节），
--     所以本函数在触发器里读到的**一定是本租户全部生效的要求** ——
--     看不见某条品牌要求就等于那条要求不存在，那正是蓝图要防的追责场景。
CREATE FUNCTION return_disposal_resolve_requirements(
  p_tenant uuid, p_brand varchar, p_platform varchar, p_method varchar)
RETURNS TABLE(require_photo boolean, require_weight boolean,
               require_certificate boolean, require_executor boolean)
LANGUAGE plpgsql STABLE AS $$
DECLARE
  base record;
  pol record;
BEGIN
  SELECT * INTO base FROM return_disposal_method_requirements(p_method);
  FOR pol IN
    SELECT p.required_photo, p.required_weight, p.required_certificate, p.required_executor
      FROM return_disposal_evidence_policy p
     WHERE p.tenant_id = p_tenant
       AND p.status = 'ACTIVE'
       AND (p.effective_to IS NULL OR p.effective_to > now())
       AND ( p.subject_type = 'GENERAL'
          OR (p.subject_type = 'BRAND' AND p.subject_ref = p_brand)
          OR (p.subject_type = 'PLATFORM' AND p.subject_ref = p_platform) )
  LOOP
    base.require_photo := base.require_photo OR pol.required_photo;
    base.require_weight := base.require_weight OR pol.required_weight;
    base.require_certificate := base.require_certificate OR pol.required_certificate;
    base.require_executor := base.require_executor OR pol.required_executor;
  END LOOP;
  RETURN QUERY SELECT base.require_photo, base.require_weight,
                      base.require_certificate, base.require_executor;
END $$;

-- (d) 🔴 销毁单守卫：上游必须是「弃置销毁」处置单 + 状态机 + 凭证闸
CREATE FUNCTION guard_return_disposal_order_state() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  o return_disposition_order%ROWTYPE;
  req record;
  w record;
BEGIN
  -- ① 上游：10.7.1.1 决策为「弃置销毁」且未撤销的处置单
  SELECT * INTO o FROM return_disposition_order
   WHERE tenant_id=NEW.tenant_id AND id=NEW.disposition_order_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return disposal order % must reference an existing disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF o.disposition_method <> 'DISCARD' THEN
    RAISE EXCEPTION 'Return disposal order % must reference a DISCARD disposition order (this one is %)',
      NEW.id, o.disposition_method USING ERRCODE='23514';
  END IF;
  IF o.status <> 'ISSUED' THEN
    RAISE EXCEPTION 'Return disposal order % references a cancelled disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF o.decision_id IS NULL OR o.registration_id IS NULL THEN
    RAISE EXCEPTION 'Return disposal order % references a malformed disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.decision_id <> o.decision_id THEN
    RAISE EXCEPTION 'Return disposal order % decision must match its disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 上游逻辑引用必须逐字等于上游单号
  IF NEW.source_reference <> o.disposition_no THEN
    RAISE EXCEPTION 'Return disposal order % source reference must match its disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 快照必须与处置单逐字一致（SKU / 批次不许在建单时漂移）
  IF NEW.sku_code <> o.sku_code OR NEW.batch_no <> o.batch_no
     OR NEW.receipt_no <> o.receipt_no THEN
    RAISE EXCEPTION 'Return disposal order % snapshot must match its disposition order',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 销毁数量不得超过处置单的处置量
  IF NEW.disposal_qty > o.disposed_qty THEN
    RAISE EXCEPTION 'Return disposal order % quantity % exceeds disposed quantity %',
      NEW.id, NEW.disposal_qty, o.disposed_qty USING ERRCODE='23514';
  END IF;

  -- ② 凭证要求：**触发器重算**（客户端填的作废），DRAFT 期随方式变；SCHEDULED 起冻结
  IF TG_OP='INSERT' THEN
    IF NEW.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'Return disposal order % must be created as DRAFT', NEW.id USING ERRCODE='23514';
    END IF;
    SELECT * INTO req FROM return_disposal_resolve_requirements(
      NEW.tenant_id, NEW.brand_code, NEW.platform_code, NEW.disposal_method);
    NEW.require_photo := req.require_photo;
    NEW.require_weight := req.require_weight;
    NEW.require_certificate := req.require_certificate;
    NEW.require_executor := req.require_executor;
  ELSIF OLD.status = 'DRAFT' THEN
    -- DRAFT 期改方式 ⇒ 要求跟着重算（定到 SCHEDULED 的那一刻才冻结）
    SELECT * INTO req FROM return_disposal_resolve_requirements(
      NEW.tenant_id, NEW.brand_code, NEW.platform_code, NEW.disposal_method);
    NEW.require_photo := req.require_photo;
    NEW.require_weight := req.require_weight;
    NEW.require_certificate := req.require_certificate;
    NEW.require_executor := req.require_executor;
  ELSE
    IF NEW.require_photo IS DISTINCT FROM OLD.require_photo
       OR NEW.require_weight IS DISTINCT FROM OLD.require_weight
       OR NEW.require_certificate IS DISTINCT FROM OLD.require_certificate
       OR NEW.require_executor IS DISTINCT FROM OLD.require_executor THEN
      RAISE EXCEPTION 'Return disposal % evidence requirements are frozen once scheduled',
        OLD.id USING ERRCODE='23514';
    END IF;
    IF NEW.disposal_method IS DISTINCT FROM OLD.disposal_method THEN
      RAISE EXCEPTION 'Return disposal % disposal method is frozen once scheduled',
        OLD.id USING ERRCODE='23514';
    END IF;
    IF OLD.status = 'SCHEDULED' AND NEW.disposal_qty IS DISTINCT FROM OLD.disposal_qty THEN
      RAISE EXCEPTION 'Return disposal % quantity is frozen once scheduled',
        OLD.id USING ERRCODE='23514';
    END IF;
  END IF;

  -- ③ 状态机：合法迁移对白名单，终态不许离开
  IF TG_OP='UPDATE' AND OLD.status <> NEW.status AND NOT (
       (OLD.status='DRAFT'     AND NEW.status IN ('SCHEDULED','CANCELLED'))
    OR (OLD.status='SCHEDULED' AND NEW.status IN ('DISPOSED','CANCELLED'))
  ) THEN
    RAISE EXCEPTION 'Return disposal % cannot move from % to %',
      OLD.id, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;

  -- ④ 已销毁的单子**完全不可变**（数量 / 重量 / 方式 / 时刻 / 执行方）
  IF TG_OP='UPDATE' AND OLD.status='DISPOSED' AND (
       NEW.disposal_qty IS DISTINCT FROM OLD.disposal_qty
    OR NEW.weight_kg IS DISTINCT FROM OLD.weight_kg
    OR NEW.disposal_method IS DISTINCT FROM OLD.disposal_method
    OR NEW.disposed_at IS DISTINCT FROM OLD.disposed_at
    OR NEW.executed_by IS DISTINCT FROM OLD.executed_by
    OR NEW.executor_name IS DISTINCT FROM OLD.executor_name
  ) THEN
    RAISE EXCEPTION 'Return disposal % is already disposed and cannot be altered',
      OLD.id USING ERRCODE='23514';
  END IF;

  -- ⑤ 🔴🔴🔴 **完成态的凭证闸**：凭证不齐 ⇒ 不许置为已销毁
  IF NEW.status = 'DISPOSED' THEN
    IF NEW.disposed_at IS NULL THEN
      RAISE EXCEPTION 'Return disposal % requires a disposal moment', NEW.id USING ERRCODE='23514';
    END IF;
    IF NEW.executed_by IS NULL OR btrim(NEW.executor_name) = '' THEN
      RAISE EXCEPTION 'Return disposal % requires the executor', NEW.id USING ERRCODE='23514';
    END IF;
    IF NEW.require_photo AND NOT EXISTS (
        SELECT 1 FROM return_disposal_evidence e
         WHERE e.tenant_id=NEW.tenant_id AND e.disposal_order_id=NEW.id
           AND e.evidence_kind='PHOTO') THEN
      RAISE EXCEPTION 'Return disposal % cannot be completed without a PHOTO evidence record',
        NEW.id USING ERRCODE='23514';
    END IF;
    IF NEW.require_weight THEN
      SELECT * INTO w FROM return_disposal_evidence e
       WHERE e.tenant_id=NEW.tenant_id AND e.disposal_order_id=NEW.id
         AND e.evidence_kind='WEIGHT';
      IF NOT FOUND THEN
        RAISE EXCEPTION 'Return disposal % cannot be completed without a WEIGHT evidence record',
          NEW.id USING ERRCODE='23514';
      END IF;
      IF NEW.weight_kg IS NULL OR NEW.weight_kg <> w.weight_kg THEN
        RAISE EXCEPTION 'Return disposal % weight must match its WEIGHT evidence record',
          NEW.id USING ERRCODE='23514';
      END IF;
    END IF;
    IF NEW.require_certificate AND NOT EXISTS (
        SELECT 1 FROM return_disposal_evidence e
         WHERE e.tenant_id=NEW.tenant_id AND e.disposal_order_id=NEW.id
           AND e.evidence_kind='CERTIFICATE') THEN
      RAISE EXCEPTION 'Return disposal % cannot be completed without a CERTIFICATE evidence record',
        NEW.id USING ERRCODE='23514';
    END IF;
    IF NEW.require_executor AND NOT EXISTS (
        SELECT 1 FROM return_disposal_evidence e
         WHERE e.tenant_id=NEW.tenant_id AND e.disposal_order_id=NEW.id
           AND e.evidence_kind='EXECUTOR') THEN
      RAISE EXCEPTION 'Return disposal % cannot be completed without an EXECUTOR evidence record',
        NEW.id USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposal_order_state
  BEFORE INSERT OR UPDATE ON return_disposal_order
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposal_order_state();

-- (e) 🔴 凭证守卫：挂在真实存在的销毁单上；每类凭证的专属字段齐备；重量与销毁单一致
CREATE FUNCTION guard_return_disposal_evidence() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  o return_disposal_order%ROWTYPE;
BEGIN
  SELECT * INTO o FROM return_disposal_order
   WHERE tenant_id=NEW.tenant_id AND id=NEW.disposal_order_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return disposal evidence % must reference an existing disposal order',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 已销毁 / 已取消的单子**不再接受新凭证**（那一次的凭据已经定死）
  IF o.status IN ('DISPOSED','CANCELLED') THEN
    RAISE EXCEPTION 'Return disposal % is %; no further evidence may be recorded',
      o.id, o.status USING ERRCODE='23514';
  END IF;
  IF NEW.evidence_kind='PHOTO' AND btrim(NEW.photo_url) = '' THEN
    RAISE EXCEPTION 'Return disposal evidence % of kind PHOTO requires photo_url',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.evidence_kind='WEIGHT' THEN
    IF NEW.weight_kg IS NULL THEN
      RAISE EXCEPTION 'Return disposal evidence % of kind WEIGHT requires weight_kg',
        NEW.id USING ERRCODE='23514';
    END IF;
    -- 🔴 重量凭证是**称重那一刻的读数**，是销毁单重量的**唯一来源**：
    --    销毁单上的 weight_kg 在完成态由它决定（见状态机第 ⑤ 条），
    --    所以这里**不**拿它去比对销毁单（那会变成「先有重量还是先有凭证」的死循环）。
    --    销毁单上不允许另填一个与凭证不同的重量：完成态触发器会拒。
  END IF;
  IF NEW.evidence_kind='CERTIFICATE' AND btrim(NEW.certificate_no) = '' THEN
    RAISE EXCEPTION 'Return disposal evidence % of kind CERTIFICATE requires certificate_no',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.evidence_kind='EXECUTOR' AND btrim(NEW.executor_name) = '' THEN
    RAISE EXCEPTION 'Return disposal evidence % of kind EXECUTOR requires executor_name',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposal_evidence_guard
  BEFORE INSERT OR UPDATE ON return_disposal_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposal_evidence();

-- (f) 🔴 费用行：挂在真实存在的销毁单上；币种与销毁单逐字相同
CREATE FUNCTION guard_return_disposal_cost() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  o return_disposal_order%ROWTYPE;
BEGIN
  SELECT * INTO o FROM return_disposal_order
   WHERE tenant_id=NEW.tenant_id AND id=NEW.disposal_order_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return disposal cost % must reference an existing disposal order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF o.status = 'CANCELLED' THEN
    RAISE EXCEPTION 'Return disposal % is CANCELLED; no further cost may be recorded',
      o.id USING ERRCODE='23514';
  END IF;
  IF o.cost_currency <> NEW.currency THEN
    RAISE EXCEPTION 'Return disposal cost % currency must match the disposal order cost currency',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposal_cost_guard
  BEFORE INSERT OR UPDATE ON return_disposal_cost
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposal_cost();

-- (g) 🔴 索赔依据：只能在**已销毁**的单子上生成；数量 / 重量与销毁单逐字相同；
--     凭证齐备性由触发器**重算**（客户端自报不作数）
CREATE FUNCTION return_disposal_evidence_complete(p_tenant uuid, p_order uuid)
RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE
  o return_disposal_order%ROWTYPE;
  need text[];
  i int;
BEGIN
  SELECT * INTO o FROM return_disposal_order
   WHERE tenant_id=p_tenant AND id=p_order;
  IF NOT FOUND THEN RETURN false; END IF;
  need := array[]::text[];
  IF o.require_photo THEN need := array_append(need,'PHOTO'); END IF;
  IF o.require_weight THEN need := array_append(need,'WEIGHT'); END IF;
  IF o.require_certificate THEN need := array_append(need,'CERTIFICATE'); END IF;
  IF o.require_executor THEN need := array_append(need,'EXECUTOR'); END IF;
  IF coalesce(array_length(need,1),0) > 0 THEN
    FOR i IN 1..array_length(need,1) LOOP
      IF NOT EXISTS (SELECT 1 FROM return_disposal_evidence e
                      WHERE e.tenant_id=p_tenant AND e.disposal_order_id=p_order
                        AND e.evidence_kind=need[i]) THEN
        RETURN false;
      END IF;
    END LOOP;
  END IF;
  RETURN true;
END $$;

CREATE FUNCTION guard_return_disposal_claim_basis() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  o return_disposal_order%ROWTYPE;
BEGIN
  SELECT * INTO o FROM return_disposal_order
   WHERE tenant_id=NEW.tenant_id AND id=NEW.disposal_order_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return disposal claim basis % must reference an existing disposal order',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 🔴 只有**真的销毁了**的货才能作为索赔依据
  IF o.status <> 'DISPOSED' THEN
    RAISE EXCEPTION 'Return disposal % is %; a claim basis requires a disposed order',
      o.id, o.status USING ERRCODE='23514';
  END IF;
  IF NEW.claim_qty <> o.disposal_qty THEN
    RAISE EXCEPTION 'Return disposal claim basis % quantity must match the disposal order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.claim_weight_kg <> o.weight_kg THEN
    RAISE EXCEPTION 'Return disposal claim basis % weight must match the disposal order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.claimed_currency <> o.cost_currency THEN
    RAISE EXCEPTION 'Return disposal claim basis % currency must match the disposal order cost currency',
      NEW.id USING ERRCODE='23514';
  END IF;
  -- 凭证齐备性由触发器重算
  NEW.evidence_complete := return_disposal_evidence_complete(NEW.tenant_id, NEW.disposal_order_id);
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposal_claim_basis_guard
  BEFORE INSERT OR UPDATE ON return_disposal_claim_basis
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposal_claim_basis();

-- (h) 索赔依据状态机：PENDING → SUBMITTED / REJECTED，终态不许离开
CREATE FUNCTION guard_return_disposal_claim_basis_state() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status = NEW.status THEN RETURN NEW; END IF;
  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'Return disposal claim basis % is % and cannot change',
      OLD.id, OLD.status USING ERRCODE='23514';
  END IF;
  IF NEW.status NOT IN ('SUBMITTED','REJECTED') THEN
    RAISE EXCEPTION 'Return disposal claim basis % cannot move from % to %',
      OLD.id, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposal_claim_basis_state
  BEFORE UPDATE ON return_disposal_claim_basis
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposal_claim_basis_state();

-- (i) 留痕追加式：UPDATE / DELETE 直接拒
CREATE FUNCTION guard_return_disposal_event_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  RAISE EXCEPTION 'Return disposal event % is append-only', OLD.id USING ERRCODE='23514';
END $$;
CREATE TRIGGER return_disposal_event_append_only
  BEFORE UPDATE OR DELETE ON return_disposal_event
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposal_event_append_only();

-- ===========================================================================
-- 9. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过策略，
--    隔离测试就成了自欺欺人。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'return_disposal_evidence_policy','return_disposal_order','return_disposal_evidence',
    'return_disposal_cost','return_disposal_claim_basis','return_disposal_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 🔴 凭证要求是**租户级配置**：只挂租户隔离，**不挂数据范围**
--    （与 10.7.1.1 的 return_resale_restriction 同纪律）。
--    一条对全租户生效的销毁凭证要求如果只有建它的人看得见，
--    另一个运营就会在看不见要求的情况下把货销毁掉 —— 那正是蓝图要防的追责场景。
--    🔴 因此这张表**不设 owner_id / department_id**：它不属于任何个人，
--    归属是整个租户（与 return_resale_restriction 同构）。

-- 销毁单自带负责人归属快照（与 V1 shop / V214 / V219 同构）
CREATE FUNCTION stamp_return_disposal_order_owner() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
    NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
    IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
  ELSE
    IF (NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.department_id IS DISTINCT FROM OLD.department_id)
       AND coalesce(nullif(current_setting('app.transfer',true),'')::boolean,false) IS NOT TRUE
    THEN RAISE EXCEPTION 'Return disposal order ownership transfer must use the explicit transfer flag'
      USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposal_order_owner
  BEFORE INSERT OR UPDATE ON return_disposal_order
  FOR EACH ROW EXECUTE FUNCTION stamp_return_disposal_order_owner();

CREATE POLICY data_scope ON return_disposal_order AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

-- 子表可见性随**父行**收敛，不允许绕过父行直接枚举。
CREATE POLICY data_scope ON return_disposal_evidence AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_disposal_order c
             WHERE c.tenant_id=return_disposal_evidence.tenant_id
             AND c.id=return_disposal_evidence.disposal_order_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_disposal_order c
             WHERE c.tenant_id=return_disposal_evidence.tenant_id
             AND c.id=return_disposal_evidence.disposal_order_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

CREATE POLICY data_scope ON return_disposal_cost AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_disposal_order c
             WHERE c.tenant_id=return_disposal_cost.tenant_id
             AND c.id=return_disposal_cost.disposal_order_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_disposal_order c
             WHERE c.tenant_id=return_disposal_cost.tenant_id
             AND c.id=return_disposal_cost.disposal_order_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

CREATE POLICY data_scope ON return_disposal_claim_basis AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_disposal_order c
             WHERE c.tenant_id=return_disposal_claim_basis.tenant_id
             AND c.id=return_disposal_claim_basis.disposal_order_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_disposal_order c
             WHERE c.tenant_id=return_disposal_claim_basis.tenant_id
             AND c.id=return_disposal_claim_basis.disposal_order_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

-- 🔴 凭证要求的登记 / 撤销**不属于任何一张销毁单**（它是租户级配置），
--    所以这两类留痕没有 order_id：它们按**租户**可见（与凭证要求表同口径），
--    其余留痕随**父行**收敛，不允许绕过父行直接枚举。
CREATE POLICY data_scope ON return_disposal_event AS RESTRICTIVE
  USING ( (return_disposal_event.order_id IS NULL
           AND return_disposal_event.event_type IN ('POLICY_CREATED','POLICY_REVOKED'))
       OR EXISTS(SELECT 1 FROM return_disposal_order c
             WHERE c.tenant_id=return_disposal_event.tenant_id
             AND c.id=return_disposal_event.order_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)))
  WITH CHECK ( (return_disposal_event.order_id IS NULL
           AND return_disposal_event.event_type IN ('POLICY_CREATED','POLICY_REVOKED'))
       OR EXISTS(SELECT 1 FROM return_disposal_order c
             WHERE c.tenant_id=return_disposal_event.tenant_id
             AND c.id=return_disposal_event.order_id
             AND channel_scope(channel_operation(),c.owner_id,c.department_id)));

-- ===========================================================================
-- 10. 账号与授权（沿用 V1..V219 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  return_disposal_evidence_policy, return_disposal_order, return_disposal_evidence,
  return_disposal_cost, return_disposal_claim_basis, return_disposal_event TO channel_app;

-- 🔴 销毁凭证**不可删**（UPDATE 仍可改，但已销毁的单子触发器会拒）——
--    凭证是那一次的凭据，删掉它就等于销毁没有发生过。
REVOKE DELETE ON return_disposal_evidence FROM channel_app;

-- 🔴 留痕追加式：UPDATE / DELETE 收回（触发器也拦，两道闸）。
REVOKE UPDATE,DELETE ON return_disposal_event FROM channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V219 同口径）。
REVOKE ALL ON
  return_disposal_evidence_policy, return_disposal_order, return_disposal_evidence,
  return_disposal_cost, return_disposal_claim_basis, return_disposal_event FROM starcon_app;

-- ===========================================================================
-- 11. 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：弃置与销毁属于「平台 / 运营操作」语义，
-- 与 10.1.1.1 承运商、10.3.2.1 交运批次、10.7.1.1 退货处置、10.7.1.2 退运回国同族，
-- identity 服务零改动。
