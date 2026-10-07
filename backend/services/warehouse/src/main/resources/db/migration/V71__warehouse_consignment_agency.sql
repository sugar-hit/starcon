-- ===========================================================================
-- 7.7.1.2 寄售与代销
-- ===========================================================================
-- 蓝图原文（07-inventory.md 7.7.1.2）：
--   「管理寄售与代销业务：寄售协议（供应商货、我方销，结算按实际售出）、寄售可用量、
--     销售触发结算数量、未售退货与期末清理。支持『寄售库存不计入我方采购入库成本，
--     仅在售出时结转』。」
--   输入「寄售协议（供应商、SKU、寄售量、结算价、结算周期、期末处理约定）；销售事实。」
--   输出「寄售库存视图（寄售量/已售/剩余）；结算触发单（→ 12 应付）；期末退回或转入采购的单据。」
--
-- ---------------------------------------------------------------------------
-- 一、本模块唯一不可让步的一条：寄售未售出不产生采购成本与应付
-- ---------------------------------------------------------------------------
-- 蓝图把这条写成「寄售与普通采购在财务上的根本分叉」。它在本迁移里**不是一句注释**，
-- 而是四道数据库不变量，任何一条被绕开都写不进数据：
--
--   1) consignment_agreement_line.remaining_quantity 是 generated column：
--        剩余 = 寄售量 − 已售 − 已退回 − 已转采购，且 CHECK remaining >= 0。
--      「寄售量」这个数因此**永远只是寄售量**，它不是成本、不是应付、不是负债。
--   2) consignment_settlement_line.amount 是 generated column：
--        金额 = 已售数量 × 结算价。
--      应付**只能**由「已售」这一个因子生成，没有第二条按寄售量算应付的路径。
--   3) consignment_settlement_line UNIQUE(tenant_id, sale_id)：
--      一条销售事实最多进一张结算单。「售出 → 应付」因此不会重复发生。
--   4) consignment_return_line.payable_amount 是 generated column，
--      且 consignment_return.disposition='CONVERT_TO_PURCHASE' 时才非零：
--      「未售退回」不产生应付；「期末转入采购」是协议里写明的、**唯一**的例外。
--
-- ---------------------------------------------------------------------------
-- 二、货主建模：本表自带货主，**不动 stock_bucket**
-- ---------------------------------------------------------------------------
-- 同 L3 的兄弟 7.7.1.1「货主与所有权」尚未交付，stock_bucket 目前**没有** stock_owner 列。
-- 蓝图 07-inventory.md:24 的硬约束是：「寄售/VMI 库存的货主是供应商，我方只记持有位置与
-- 可销售权，不记为自有资产。」
--
-- 因此本模块的寄售事实是**自成一体的一组表**，不寄生在 stock_bucket 上：
--   - consignment_agreement.supplier_party_id：寄售货的货主（供应商），不建 FK 到 catalog
--     （AGENTS.md 禁止跨服务 FK；3.6.2 供应商档案在 catalog 库，本服务读不到）。
--   - consignment_agreement_line.stock_owner_type 恒为 'SUPPLIER'。
--   - consignment_agreement_line.counts_as_owned_asset 恒为 false，且 **CHECK 写死为 false**：
--     想把寄售货算成自有资产的人会先被数据库挡住，而不是靠代码里记得判断。
-- 这样既满足硬约束，又不与 7.7.1.1 将来给 stock_bucket 加列的工作撞车 ——
-- 两张表各自表达自己那一份事实，合并期不存在 DDL 冲突。
--
-- ---------------------------------------------------------------------------
-- 三、结算周期的口径：按「销售事实的售出时间」归期
-- ---------------------------------------------------------------------------
-- 结算周期（WEEKLY / SEMI_MONTHLY / MONTHLY / ON_DEMAND）是**按售出时刻**归期的，
-- 不是按发货时刻，也不是按入库时刻 —— 供应商要的是「这周期我卖出去多少」。
-- 归期区间由服务层按 UTC 日历算出并**显式落到结算单的 period_start/period_end 两列**，
-- DB 只校验 end > start；不在库里做日期算术（那是服务层的口径，不是数据库的）。
--
-- ---------------------------------------------------------------------------
-- 四、跨服务边界
-- ---------------------------------------------------------------------------
-- 9 订单（售出事实）、12 应付、10 物流、6 采购**都尚未交付**。因此本模块：
--   - 销售事实由本服务**自落地**一张 consignment_sale 记事实（上游 9 交付后由它推，
--     交接键是 sale_ref / source_system + reference_id，不建 FK、不查对方的表）；
--   - 结算触发单是本服务内的事实，对 12 的交接**只留 source_system + reference_id 定位键**，
--     12 交付后按该键读回，本模块不替 12 写应付、不建跨服务 FK；
--   - 期末退回的运输同样只出单据，10 物流交付后接 ref。
--
-- 数量一律 numeric(19,6)，结算价 numeric(19,6)，全程 BigDecimal，禁浮点。
-- 金额列用 numeric(24,6)：数量(13 位整数) × 价格(13 位整数) 的乘积可能超出 19 位总宽，
-- 用 19 会在真实大额数据上溢出报错。scale 保持 6 与数量同口径，不在存储层做币种抹零。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 单号发号器：CG-yyyyMMdd-000001
-- ---------------------------------------------------------------------------
-- 与 V61 调拨单同一条纪律：单号由 DB 发，应用层拼出来的单号并发建单必撞，
-- 而撞号会被 UNIQUE(tenant_id,agreement_code) 变成 409 —— 那不该发生。
CREATE SEQUENCE consignment_agreement_code_seq START 1;
CREATE SEQUENCE consignment_settlement_code_seq START 1;
CREATE SEQUENCE consignment_return_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 寄售协议头
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_agreement (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_code varchar(32) NOT NULL,
  -- 经营模式：CONSIGNMENT 寄售（供应商货、我方销）/ AGENCY 代销（我方买断、供应商代销）。
  -- 两者货主都在供应商侧，但代销是我方承担买断成本的寄售变体，期末处理与结算口径不同，
  -- 所以显式分列而不是合并成一个布尔。
  mode varchar(16) NOT NULL CHECK(mode IN ('CONSIGNMENT','AGENCY')),
  -- 货主：供应商的逻辑 ID（catalog party.id，跨服务不建 FK，见头部说明二）。
  -- 下面两列是**建单时的展示快照**：供应商主数据归 3.6.2 所有，本服务不复制权威列。
  supplier_party_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL,
  supplier_name varchar(200) NOT NULL,
  -- 协议账套法人：结算触发单归到哪个法人账下（复合外键强制同租户）。
  legal_entity_id uuid NOT NULL,
  -- 币种显式落在协议上：结算价没有币种就是一个无意义的数字。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 结算周期：WEEKLY / SEMI_MONTHLY / MONTHLY / ON_DEMAND（按需，随时出触发单）。
  settlement_cycle varchar(16) NOT NULL CHECK(settlement_cycle IN
    ('WEEKLY','SEMI_MONTHLY','MONTHLY','ON_DEMAND')),
  -- 期末处理约定：RETURN_TO_SUPPLIER 期末退回供应商 / CONVERT_TO_PURCHASE 期末转我方采购。
  -- 这是蓝图「期末处理约定」与输出「期末退回或转入采购的单据」的那份约定，落在协议上。
  end_treatment varchar(24) NOT NULL CHECK(end_treatment IN
    ('RETURN_TO_SUPPLIER','CONVERT_TO_PURCHASE')),
  start_date date NOT NULL,
  end_date date,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','ACTIVE','SUSPENDED','CLOSED')),
  note text NOT NULL DEFAULT '',
  created_by_user_id uuid NOT NULL,
  activated_at timestamptz, activated_by_user_id uuid,
  closed_at timestamptz, closed_by_user_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,agreement_code),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(supplier_code)<>'' AND btrim(supplier_name)<>''),
  CHECK(end_date IS NULL OR end_date>=start_date),
  -- 生命周期与时点必须自洽：ACTIVE 必须有启用时刻，CLOSED 必须有关闭时刻。
  -- 否则「已启用协议」查不到启用时刻，对账时无从追溯。
  CHECK((status<>'ACTIVE') OR activated_at IS NOT NULL),
  CHECK((status<>'CLOSED') OR closed_at IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 2. 寄售协议明细：寄售量 / 已售 / 退回 / 转采购 / 剩余
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_agreement_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  -- 寄售量：供应商交给我方代销的**上限**。它不是成本、不是应付、不是负债（见头部一）。
  consigned_quantity numeric(19,6) NOT NULL,
  -- 结算价：我方**售出时**付给供应商的单价。整条财务分叉就落在这一个价 × 已售数上。
  settlement_price numeric(19,6) NOT NULL,
  -- 货主恒为供应商（07-inventory.md:24 的硬约束，见头部二）。
  stock_owner_type varchar(16) NOT NULL DEFAULT 'SUPPLIER'
    CHECK(stock_owner_type = 'SUPPLIER'),
  -- 寄售货**永不**计入我方自有资产。CHECK 写死 false：这条不是代码里的自觉，
  -- 是一条数据库保证 —— 谁想把寄售货并进自有资产口径，先在这里撞墙。
  counts_as_owned_asset boolean NOT NULL DEFAULT false
    CHECK(counts_as_owned_asset = false),
  -- 三个累计量由服务层在同一事务里随事件累加；守恒式由 remaining 的 CHECK 兜住。
  sold_quantity numeric(19,6) NOT NULL DEFAULT 0,
  returned_quantity numeric(19,6) NOT NULL DEFAULT 0,
  converted_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 剩余 = 寄售量 − 已售 − 已退回 − 已转采购。generated column 是「守恒恒成立」的关键：
  -- 任何一边少记或多记，剩余都会立刻算错并被下面的 CHECK 拒绝。
  remaining_quantity numeric(19,6) GENERATED ALWAYS AS
    (consigned_quantity - sold_quantity - returned_quantity - converted_quantity) STORED,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一协议下同一 SKU 在同一仓只允许一行：两行会让「这个 SKU 还剩多少」有第二个答案。
  UNIQUE(tenant_id,agreement_id,sku_id,warehouse_id),
  FOREIGN KEY(tenant_id,agreement_id) REFERENCES consignment_agreement(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  CHECK(consigned_quantity>0),
  -- 结算价：允许 0（免费寄售，供应商不收钱）与负值（红冲/返价场景），
  -- 但必须显式落库 —— 应付 = 已售数量 × 结算价，这个价是应付唯一的单价来源，
  -- 「先不管价格以后再补」会让应付额变成 0 而不是让这个问题暴露出来。
  -- 因此这里不设符号 CHECK，只要求 NOT NULL（见上一行）。
  CHECK(sold_quantity>=0 AND returned_quantity>=0 AND converted_quantity>=0),
  -- 守恒式 + 超卖兜底：卖出去 + 退回去 + 转采购都不许超过寄售量。
  -- 「已售 > 寄售量」意味着在卖供应商根本没给我们的货。
  CHECK(remaining_quantity>=0),
  CHECK(remaining_quantity<=consigned_quantity)
);

-- ---------------------------------------------------------------------------
-- 3. 销售事实（上游 9 订单尚未交付，本服务自落地这张事实表）
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_sale (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_line_id uuid NOT NULL,
  -- 售出数量。销售事实是「结算触发数量」的唯一来源。
  sold_quantity numeric(19,6) NOT NULL,
  -- 幂等锚点：同一外部订单行重复推送时，(tenant_id, agreement_line_id, sale_ref) 撞唯一约束，
  -- 于是「卖一次算两次应付」这条路在数据库层就没有了。
  sale_ref varchar(64) NOT NULL,
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL',
  -- 我方实际售价（零售口径），仅供毛利对照；**不参与应付计算** ——
  -- 应付只认结算价 × 已售。用一个明确的字段把它和结算价分开，
  -- 免得后来有人拿它算应付。
  sale_price numeric(19,6),
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  occurred_at timestamptz NOT NULL,
  recorded_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,agreement_line_id,sale_ref),
  FOREIGN KEY(tenant_id,agreement_line_id) REFERENCES consignment_agreement_line(tenant_id,id),
  CHECK(sold_quantity>0),
  CHECK(sale_price IS NULL OR sale_price>=0)
);

-- ---------------------------------------------------------------------------
-- 4. 结算触发单（→ 12 应付）
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_settlement (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  settlement_code varchar(32) NOT NULL,
  agreement_id uuid NOT NULL,
  -- 归期区间：按**售出时刻**落期（头部三）。DB 只校验区间自洽。
  period_start date NOT NULL,
  period_end date NOT NULL,
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN
    ('OPEN','CONFIRMED','CANCELLED')),
  -- 表头合计**永远等于明细之和**（见下方 delay_constraint 触发器），不是应用层自觉。
  total_quantity numeric(19,6) NOT NULL DEFAULT 0,
  total_amount numeric(24,6) NOT NULL DEFAULT 0,
  -- 对 12 应付的交接定位键：跨服务不建 FK、不查对方的表，12 交付后按此键读回。
  -- 与 V1 的 inventory_transaction(source_system, reference_id) 同一套约定。
  source_system varchar(32) NOT NULL DEFAULT 'WAREHOUSE',
  reference_id varchar(64),
  triggered_at timestamptz NOT NULL DEFAULT now(),
  confirmed_at timestamptz, confirmed_by_user_id uuid,
  cancelled_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,settlement_code),
  UNIQUE(tenant_id,agreement_id,period_start,period_end),
  FOREIGN KEY(tenant_id,agreement_id) REFERENCES consignment_agreement(tenant_id,id),
  CHECK(period_end>period_start),
  CHECK(total_quantity>=0 AND total_amount>=0),
  -- 一期只能出一张结算单：同协议同区间重复出单会让供应商收到两份应付。
  CHECK((status<>'CONFIRMED') OR confirmed_at IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 5. 结算单明细：应付金额只由「已售 × 结算价」生成
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_settlement_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  settlement_id uuid NOT NULL,
  agreement_line_id uuid NOT NULL,
  -- 指向**具体那条销售事实**：这是「售出才产生应付」的直接落点。
  -- 应付挂在销售事实上而不是挂在寄售协议行上，未售出的货就永远不会出现在这里。
  sale_id uuid NOT NULL,
  quantity numeric(19,6) NOT NULL,
  unit_price numeric(19,6) NOT NULL,
  -- 应付金额 = 已售数量 × 结算价（generated）。全表没有第二个金额来源。
  amount numeric(24,6) GENERATED ALWAYS AS (quantity * unit_price) STORED,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 「售出 → 应付」至多一次：同一条销售事实不能进两张结算单。
  UNIQUE(tenant_id,sale_id),
  UNIQUE(tenant_id,settlement_id,agreement_line_id,sale_id),
  FOREIGN KEY(tenant_id,settlement_id) REFERENCES consignment_settlement(tenant_id,id),
  FOREIGN KEY(tenant_id,agreement_line_id) REFERENCES consignment_agreement_line(tenant_id,id),
  FOREIGN KEY(tenant_id,sale_id) REFERENCES consignment_sale(tenant_id,id),
  CHECK(quantity>0)
);

-- ---------------------------------------------------------------------------
-- 6. 未售退货与期末清理单
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_return (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  return_code varchar(32) NOT NULL,
  agreement_id uuid NOT NULL,
  -- UNSOLD_RETURN 未售退货（周期内退未售出）/ PERIOD_END_CLEANUP 期末清理。
  kind varchar(24) NOT NULL CHECK(kind IN ('UNSOLD_RETURN','PERIOD_END_CLEANUP')),
  -- RETURN_TO_SUPPLIER 退回供应商（**不产生应付**）
  -- CONVERT_TO_PURCHASE 转入我方采购（协议约定的唯一例外，**才**产生应付）
  disposition varchar(24) NOT NULL CHECK(disposition IN
    ('RETURN_TO_SUPPLIER','CONVERT_TO_PURCHASE')),
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','EXECUTED','CANCELLED')),
  total_quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 只有 disposition='CONVERT_TO_PURCHASE' 才非零，见 check 约束。
  total_payable_amount numeric(24,6) NOT NULL DEFAULT 0,
  -- 转采购时交给 6 采购 / 12 应付的定位键（两者均未交付，只留键不跨库写）。
  source_system varchar(32) NOT NULL DEFAULT 'WAREHOUSE',
  reference_id varchar(64),
  executed_at timestamptz, executed_by_user_id uuid,
  cancelled_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,return_code),
  FOREIGN KEY(tenant_id,agreement_id) REFERENCES consignment_agreement(tenant_id,id),
  CHECK(total_quantity>=0),
  -- 未售退回不产生应付、转入采购才产生：钉成 DB 不变量而不是服务层 if。
  CHECK((disposition='CONVERT_TO_PURCHASE') OR total_payable_amount = 0),
  CHECK((status<>'EXECUTED') OR executed_at IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 7. 退货/清理单明细
-- ---------------------------------------------------------------------------
CREATE TABLE consignment_return_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  return_id uuid NOT NULL,
  agreement_line_id uuid NOT NULL,
  quantity numeric(19,6) NOT NULL,
  -- 结算价快照：清理当天沿用协议价，转采购的应付额 = 本行数量 × 本价。
  settlement_price numeric(19,6) NOT NULL,
  payable_amount numeric(24,6) GENERATED ALWAYS AS (quantity * settlement_price) STORED,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,return_id,agreement_line_id),
  FOREIGN KEY(tenant_id,return_id) REFERENCES consignment_return(tenant_id,id),
  FOREIGN KEY(tenant_id,agreement_line_id) REFERENCES consignment_agreement_line(tenant_id,id),
  CHECK(quantity>0)
);

-- ---------------------------------------------------------------------------
-- 8. 审计（append-only）
-- ---------------------------------------------------------------------------
-- 寄售的每个动作都要能追责：协议启用/暂停/关闭、记销售、出结算单、确认、退回/转采购。
-- append-only + 触发器禁删：审计被改过就等于没审计过。
CREATE TABLE consignment_audit (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  agreement_id uuid NOT NULL,
  event_type varchar(32) NOT NULL,
  detail text NOT NULL DEFAULT '',
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,agreement_id) REFERENCES consignment_agreement(tenant_id,id)
);

-- ---------------------------------------------------------------------------
-- 9. 索引（RLS 之后的实际查询面）
-- ---------------------------------------------------------------------------
CREATE INDEX idx_consignment_agreement_list ON consignment_agreement(tenant_id, status, created_at DESC);
CREATE INDEX idx_consignment_agreement_supplier ON consignment_agreement(tenant_id, supplier_party_id);
CREATE INDEX idx_consignment_line_agreement ON consignment_agreement_line(tenant_id, agreement_id);
CREATE INDEX idx_consignment_sale_line ON consignment_sale(tenant_id, agreement_line_id, occurred_at);
CREATE INDEX idx_consignment_sale_period ON consignment_sale(tenant_id, occurred_at);
CREATE INDEX idx_consignment_settlement_agreement ON consignment_settlement(tenant_id, agreement_id, status);
CREATE INDEX idx_consignment_settlement_ref ON consignment_settlement(tenant_id, source_system, reference_id);
CREATE INDEX idx_consignment_settlement_line_settlement ON consignment_settlement_line(tenant_id, settlement_id);
CREATE INDEX idx_consignment_return_agreement ON consignment_return(tenant_id, agreement_id, status);
CREATE INDEX idx_consignment_return_line_return ON consignment_return_line(tenant_id, return_id);
CREATE INDEX idx_consignment_audit_agreement ON consignment_audit(tenant_id, agreement_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 10. 触发器：状态机 / 守恒 / append-only
-- ---------------------------------------------------------------------------
-- (a) 协议状态机：只认 DRAFT→ACTIVE→SUSPENDED↔ACTIVE→CLOSED，
--     且 ACTIVE↔SUSPENDED 之外的跳步一律拒绝。已关闭的协议不可复活。
CREATE OR REPLACE FUNCTION guard_consignment_agreement_state() RETURNS trigger AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT'    THEN NEW.status = 'ACTIVE'
    WHEN 'ACTIVE'   THEN NEW.status IN ('SUSPENDED','CLOSED')
    WHEN 'SUSPENDED' THEN NEW.status IN ('ACTIVE','CLOSED')
    WHEN 'CLOSED'   THEN false
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION 'consignment agreement % cannot move % -> %', OLD.agreement_code, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_agreement_state
  BEFORE UPDATE ON consignment_agreement
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_agreement_state();

-- (b) 协议已关闭后，其明细不得再有任何数量变动（货已结清，账不能再动）。
--     这是「期末清理之后不能再售出」的 DB 级保证。
CREATE OR REPLACE FUNCTION guard_consignment_line_after_close() RETURNS trigger AS $$
DECLARE closed boolean;
BEGIN
  IF NEW.sold_quantity IS NOT DISTINCT FROM OLD.sold_quantity
     AND NEW.returned_quantity IS NOT DISTINCT FROM OLD.returned_quantity
     AND NEW.converted_quantity IS NOT DISTINCT FROM OLD.converted_quantity
     AND NEW.consigned_quantity IS NOT DISTINCT FROM OLD.consigned_quantity THEN
    RETURN NEW;
  END IF;
  SELECT (status = 'CLOSED') INTO closed
    FROM consignment_agreement WHERE tenant_id = NEW.tenant_id AND id = NEW.agreement_id;
  IF closed THEN
    RAISE EXCEPTION 'consignment line % belongs to a CLOSED agreement and is frozen', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_line_frozen
  BEFORE UPDATE ON consignment_agreement_line
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_line_after_close();

-- (c) 结算单表头合计 == Σ明细（延迟到事务末尾校验）。
--     应用层算错了、或者将来有人绕过服务层直接插明细，都会被这道闸门挡住。
CREATE OR REPLACE FUNCTION guard_consignment_settlement_totals() RETURNS trigger AS $$
DECLARE target_id uuid; target_tenant uuid;
BEGIN
  target_id := coalesce(NEW.settlement_id, OLD.settlement_id);
  target_tenant := coalesce(NEW.tenant_id, OLD.tenant_id);
  -- 子查询里只能用触发器行上的 tenant_id，**不能**引用 UPDATE 目标别名 s：
  -- FROM 子查询不是 LATERAL，引不到 UPDATE 的目标表（PG 会报
  -- "invalid reference to FROM-clause entry for table s"）。
  UPDATE consignment_settlement s
     SET total_quantity = agg.qty, total_amount = agg.amt, updated_at = now()
    FROM (select coalesce(sum(quantity),0) as qty, coalesce(sum(amount),0) as amt
            from consignment_settlement_line
           where tenant_id = target_tenant and settlement_id = target_id) agg
   WHERE s.tenant_id = target_tenant and s.id = target_id;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

-- **立即**触发而不是延迟到事务末尾：延迟触发会让「刚建好的结算单读出来合计是 0」
-- （表头合计在 COMMIT 前一直没更新，而调用方在同一事务里已经把对象读走了）。
-- 这里重算的是表头自身、不涉及跨表守恒式，所以每条明细一落就同步，
-- 「表头 == Σ明细」在每条语句之后都成立，比延迟触发更强而不是更弱。
CREATE TRIGGER consignment_settlement_totals
  AFTER INSERT OR UPDATE OR DELETE ON consignment_settlement_line
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_settlement_totals();

-- (d) 已确认的结算单明细不可再改：应付已经交出去了，改它等于篡改对外账。
CREATE OR REPLACE FUNCTION guard_consignment_settlement_line_locked() RETURNS trigger AS $$
DECLARE st varchar(16);
BEGIN
  SELECT status INTO st FROM consignment_settlement
   WHERE tenant_id = NEW.tenant_id AND id = NEW.settlement_id;
  IF st IN ('CONFIRMED','CANCELLED') THEN
    RAISE EXCEPTION 'settlement % is % and its lines are frozen', NEW.settlement_id, st
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_settlement_line_locked
  BEFORE INSERT OR UPDATE OR DELETE ON consignment_settlement_line
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_settlement_line_locked();

-- (e) 退货单表头合计 == Σ明细，且转采购才允许有应付额。
CREATE OR REPLACE FUNCTION guard_consignment_return_totals() RETURNS trigger AS $$
DECLARE target_id uuid; target_tenant uuid;
BEGIN
  target_id := coalesce(NEW.return_id, OLD.return_id);
  target_tenant := coalesce(NEW.tenant_id, OLD.tenant_id);
  -- 同上：子查询里不能用 UPDATE 目标别名 r。
  --
  -- 应付额**按 disposition 决定**，而不是把行的 payable_amount 无脑求和：
  -- 退回供应商的单据，数据库自己算出来的应付额就是 0，不依赖谁记得判断该不该产生应付。
  -- （头的 CHECK 再兜一道：disposition<>'CONVERT_TO_PURCHASE' 时合计必须是 0。）
  UPDATE consignment_return r
     SET total_quantity = agg.qty,
         total_payable_amount = case when r.disposition = 'CONVERT_TO_PURCHASE' then agg.amt else 0 end,
         updated_at = now()
    FROM (select coalesce(sum(quantity),0) as qty, coalesce(sum(payable_amount),0) as amt
            from consignment_return_line
           where tenant_id = target_tenant and return_id = target_id) agg
   WHERE r.tenant_id = target_tenant and r.id = target_id;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

-- 同上：立即触发，让「刚建好的退货单读出来合计是 0」不会发生。
CREATE TRIGGER consignment_return_totals
  AFTER INSERT OR UPDATE OR DELETE ON consignment_return_line
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_return_totals();

-- (f) 退货单状态机 + 终态明细冻结。
CREATE OR REPLACE FUNCTION guard_consignment_return_state() RETURNS trigger AS $$
DECLARE st varchar(16);
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('EXECUTED','CANCELLED'))) THEN
      RAISE EXCEPTION 'consignment return % cannot move % -> %', OLD.return_code, OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  SELECT status INTO st FROM consignment_return WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
  IF st IN ('EXECUTED','CANCELLED') THEN
    RAISE EXCEPTION 'consignment return % is % and its lines are frozen', NEW.return_code, st
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_return_state
  BEFORE UPDATE ON consignment_return_line
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_return_state();

-- (g) 审计 append-only：禁改禁删（权限层还会再收一道，见下）。
CREATE OR REPLACE FUNCTION guard_consignment_audit_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'consignment audit is append-only' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_audit_append_only
  BEFORE UPDATE OR DELETE ON consignment_audit
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_audit_append_only();

-- (h) 销售事实不可改删：它已经是应付的依据，改它等于改账。
CREATE OR REPLACE FUNCTION guard_consignment_sale_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'consignment sale is immutable; record a correcting sale instead'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER consignment_sale_immutable
  BEFORE UPDATE OR DELETE ON consignment_sale
  FOR EACH ROW EXECUTE FUNCTION guard_consignment_sale_immutable();

-- ===========================================================================
-- 10. 多租户与授权（与 V1/V2/V54/V56/V59/V62 同一套写法）
-- ===========================================================================
-- 八张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['consignment_agreement','consignment_agreement_line','consignment_sale',
                             'consignment_settlement','consignment_settlement_line','consignment_return',
                             'consignment_return_line','consignment_audit'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  consignment_agreement, consignment_agreement_line, consignment_sale,
  consignment_settlement, consignment_settlement_line, consignment_return,
  consignment_return_line, consignment_audit TO warehouse_app;
-- 销售事实与审计是凭据：连运行角色都不给改删，兜底在权限层而不是只在触发器里。
REVOKE UPDATE,DELETE ON consignment_sale, consignment_audit FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54/V56/V59/V62 同口径）。
REVOKE ALL ON
  consignment_agreement, consignment_agreement_line, consignment_sale,
  consignment_settlement, consignment_settlement_line, consignment_return,
  consignment_return_line, consignment_audit FROM starcon_app;

-- 单号发号器同样要显式授权：V1 的 GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列
-- （与 V61 的 transfer_order_code_seq 同一处授权，漏了就是 "permission denied for sequence"）。
GRANT USAGE, SELECT ON SEQUENCE
  consignment_agreement_code_seq, consignment_settlement_code_seq,
  consignment_return_code_seq TO warehouse_app;

-- ---------------------------------------------------------------------------
-- 11. 跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表，不 ALTER 任何既有表**：没有 ADD COLUMN、没有 SET NOT NULL、
-- 没有加到既有表上的触发器。因此不存在「把别的车道的列改成 NOT NULL 而它 INSERT 不写」
-- 这类跨车道违约（V63~V74 各车道同理）。既有模块的插入路径完全不受影响。
