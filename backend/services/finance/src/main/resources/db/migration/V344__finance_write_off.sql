-- ===========================================================================
-- V344 · finance_write_off —— 12.2.1.2 核销
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.2.1.2 核销）原文：
--
--   职责：把收款核销到应收：自动匹配（金额+订单号+日期）或人工指定，支持部分核销、
--         合并核销、预收挂账、核销撤销。
--   输入：收款单；待核销应收；匹配规则或人工分配。
--   输出：核销记录 + 应收余额更新 + 预收余额。
--   关系：上游 = 12.2.1.1、12.1.1.1；下游 = 13 利润（回款口径）。
--         **核销必须精确到明细行**，只核销到订单头无法处理部分回款与折让。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么
-- ---------------------------------------------------------------------------
--
--   * **零 ALTER 既有表**：V338（12.1.1.1 应收）与 V341（12.2.1.1 收款）已经进过集成头并
--     被 Flyway 应用过。本文件**不碰**它们的任何一列、任何一条约束、任何一条触发器、
--     任何一条 GRANT —— 只**新建**四张表，并在**服务层**用 jOOQ 显式 SQL 读写上游列。
--
--   * **不写上游单头的核销列**：核销金额落在**明细行**的 `settled_amount` 上
--     （V338 原文：「只放行 settled_amount（核销金额）—— 那是 12.2.1.2 的写入所有权」），
--     单头的 `settled_amount` / `status` 由 V338 自己的派生触发器
--     `guard_finance_receivable_amount` 带出来 —— **本模块不写单头**，
--     也**不新挂触发器到上游表上**（那会绕过 V338 的冻结守卫）。
--
--   * **不造利润数据**：下游 13「利润与经营分析」**未交付**（reporting 服务不在集成头里）。
--     「回款口径」那一段**当前不可产生** ⇒ 能力声明里如实写明归属模块 13，
--     **绝不拿假报表顶替**。
--
--   * **不代做 12.1.1.3 坏账的「作废 + 新单」**：那是坏账模块的域。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_write_off            —— 核销记录（单头：一笔核销 = 一张单）
--   finance_write_off_allocation —— 核销 ↔ 应收**明细行**的分配关系（部分核销 / 合并核销）
--   finance_write_off_event      —— 核销事件流（append-only 举证凭据）
--   finance_advance_ledger       —— 预收挂账账（12.2.1.2 的输出之一：预收余额）
--
--   四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 关键约束怎么落到数据层
-- ---------------------------------------------------------------------------
--
--   * 「**核销必须精确到明细行**」（卷 12 加粗句）⇒ 分配关系上**必须**带
--     `receivable_line_id`，且由触发器校验它**确实属于**该 `receivable_id`
--     （只核销到订单头 ⇒ 部分回款与折让表达不了）。
--
--   * 「金额一律 BigDecimal + NUMERIC，**禁止浮点**」⇒ 全部 numeric(19,4)。
--
--   * **单头的三个金额是派生的**（`settlement_amount` / `advance_amount` / `total_amount`）：
--     由分配明细求和得出（`guard_finance_write_off_amount`）⇒
--     「单头说核销了 300、分配明细合计 280」这种漂移在数据层写不出来。
--
--   * **状态机穷举**：POSTED → REVERSED，只有这一个方向；REVERSED 是**终态**，整单冻结。
--
-- ---------------------------------------------------------------------------
-- 3. 预收挂账的口径（为什么它**不**减收款单的未核销余额）
-- ---------------------------------------------------------------------------
--
--   12.2.1.1（V341）对预收的口径是「**预收 ⊂ 未核销余额**」：
--   带 `advance_flag` 的收款单，其 `unsettled_amount` 仍然全额留在未核销余额里，
--   预收只是**其中被分类为预收的那一部分**。
--
--   本模块与它**保持同一条口径**：预收挂账**不**增加收款单的 `settled_amount`
--   （钱还在未核销余额里，只是被分类成「预收」），而是记进 `finance_advance_ledger`。
--   ⇒ 两个口径**互不重复计数**：
--     · 本模块的预收余额 = ledger 里 status='OPEN' 的行（来自**未标记**预收的收款单）；
--     · 12.2.1.1 的 advance_amount = 带 advance_flag 的收款单的未核销余额。
--   同一个收款单**不会**同时出现在两边（服务层拒绝在带预收标记的收款单上挂账）。
--
--   挂账的钱是**专用**的：它不能被拿去核销别的应收（服务层按
--   「未核销余额 − 未释放挂账额」封顶），要动用先显式释放挂账。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_write_off —— 核销记录（单头）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_write_off (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  write_off_no varchar(48) NOT NULL,
  -- 资金来源：**永远是一张收款单**（12.2.1.1）。同服务同库 ⇒ 复合外键合法。
  receipt_id uuid NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 核销方式：自动匹配 / 人工指定 / 预收挂账。
  mode text NOT NULL
    CHECK(mode IN ('AUTO_MATCH','MANUAL','ADVANCE_PARKING')),
  -- POSTED（已核销）→ REVERSED（已撤销，**终态**，整单冻结）。
  status text NOT NULL DEFAULT 'POSTED'
    CHECK(status IN ('POSTED','REVERSED')),
  -- 三个金额**由分配明细派生**（见 guard_finance_write_off_amount）。
  settlement_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(settlement_amount >= 0),
  advance_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(advance_amount >= 0),
  total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_amount >= 0),
  note varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 核销发生时刻（timestamptz）：核销时间是要进审计的口径。
  occurred_at timestamptz NOT NULL,
  reversed_at timestamptz,
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, write_off_no),
  FOREIGN KEY(tenant_id, receipt_id) REFERENCES finance_receipt(tenant_id, id),
  -- 已撤销 ⇒ 必须写明撤销时刻；未撤销 ⇒ 必须没有。两者成对出现。
  CHECK((status = 'REVERSED') = (reversed_at IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- 2. finance_write_off_allocation —— 核销 ↔ 应收明细行（部分核销 / 合并核销）
-- ---------------------------------------------------------------------------
--
-- 「**核销必须精确到明细行**」⇒ SETTLEMENT 分配**必须**带 receivable_line_id；
-- 「**一笔收款可核销多笔应收**（合并回款）」⇒ 一张核销单可以有多条分配。
CREATE TABLE finance_write_off_allocation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  write_off_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  -- SETTLEMENT：指向应收单头（便于按单汇总）。
  receivable_id uuid,
  -- SETTLEMENT：**必须**指向应收**明细行**（行级核销金额落在那里）。
  receivable_line_id uuid,
  -- SETTLEMENT = 核销到某条应收明细行；ADVANCE = 预收挂账（还没有对应应收）。
  allocation_type text NOT NULL
    CHECK(allocation_type IN ('SETTLEMENT','ADVANCE')),
  -- 订单号快照：明细行的 order_no（可空 —— 佣金 / 罚款类行天然没有订单）。
  order_no varchar(64) NOT NULL DEFAULT '',
  -- 本次核销金额。**部分核销**就是它小于该行的未核销余额。
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  status text NOT NULL DEFAULT 'POSTED'
    CHECK(status IN ('POSTED','REVERSED')),
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, write_off_id) REFERENCES finance_write_off(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, receipt_id) REFERENCES finance_receipt(tenant_id, id),
  FOREIGN KEY(tenant_id, receivable_id) REFERENCES finance_receivable(tenant_id, id),
  FOREIGN KEY(tenant_id, receivable_line_id) REFERENCES finance_receivable_line(tenant_id, id),
  -- 核销到应收 ⇒ 必须同时有单头与明细行；预收挂账 ⇒ 必须**都**没有
  -- （挂账就是「还没有对应应收」）。
  CHECK((allocation_type = 'SETTLEMENT') = (receivable_id IS NOT NULL)),
  CHECK((allocation_type = 'SETTLEMENT') = (receivable_line_id IS NOT NULL))
);

-- 同一张核销单里，同一条明细行**只出现一次**（一次核销里不会把同一行核销两遍）。
CREATE UNIQUE INDEX finance_write_off_allocation_line_unique
  ON finance_write_off_allocation(tenant_id, write_off_id, receivable_line_id)
  WHERE receivable_line_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. finance_advance_ledger —— 预收挂账账（预收余额）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一笔「钱到了但还没有对应应收」的挂账。
-- status：OPEN（还挂在预收账上）/ RELEASED（已被核销用掉）/ REVERSED（挂账本身被撤销）。
CREATE TABLE finance_advance_ledger (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  write_off_id uuid NOT NULL,
  allocation_id uuid NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  status text NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','RELEASED','REVERSED')),
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, receipt_id) REFERENCES finance_receipt(tenant_id, id),
  FOREIGN KEY(tenant_id, write_off_id) REFERENCES finance_write_off(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, allocation_id) REFERENCES finance_write_off_allocation(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- 4. finance_write_off_event —— 核销事件流（append-only 举证凭据）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_write_off_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  write_off_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN (
    'AUTO_MATCHED','MANUAL_ASSIGNED','ADVANCE_PARKED','RELEASED','REVERSED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, write_off_id) REFERENCES finance_write_off(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 单头的三个金额**由分配明细派生**。
--
--     理由与 V338 一样：「单头 300、明细合计 280」这种错误如果没人兜住，
--     会一路活到对账那天才被发现。
CREATE OR REPLACE FUNCTION guard_finance_write_off_amount() RETURNS trigger AS $$
DECLARE
  head finance_write_off%ROWTYPE;
  settlement_sum numeric(19,4);
  advance_sum numeric(19,4);
BEGIN
  SELECT * INTO head FROM finance_write_off
   WHERE tenant_id = NEW.tenant_id AND id = NEW.write_off_id
   FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;

  SELECT coalesce(sum(amount) FILTER (WHERE allocation_type = 'SETTLEMENT'), 0),
         coalesce(sum(amount) FILTER (WHERE allocation_type = 'ADVANCE'), 0)
    INTO settlement_sum, advance_sum
    FROM finance_write_off_allocation
   WHERE tenant_id = NEW.tenant_id AND write_off_id = NEW.write_off_id;

  IF head.settlement_amount IS DISTINCT FROM settlement_sum
     OR head.advance_amount IS DISTINCT FROM advance_sum
     OR head.total_amount IS DISTINCT FROM settlement_sum + advance_sum THEN
    UPDATE finance_write_off
       SET settlement_amount = settlement_sum,
           advance_amount = advance_sum,
           total_amount = settlement_sum + advance_sum,
           updated_at = now(),
           version = version + 1
     WHERE tenant_id = head.tenant_id AND id = head.id;
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_write_off_allocation_amount
  AFTER INSERT OR UPDATE OR DELETE ON finance_write_off_allocation
  FOR EACH ROW EXECUTE FUNCTION guard_finance_write_off_amount();

-- (b) 核销记录的**快照字段**在落库之后冻结；状态只允许 POSTED → REVERSED。
--
--     冻结清单：收款单、币种、方式、金额（三个派生金额除外）、核销时刻、备注。
--     理由：核销是**已经发生的事实** —— 允许事后改金额/时刻，等于给账目留了一个
--     可以改写历史数字的后门（与 V338 汇率快照冻结同一条纪律）。
CREATE OR REPLACE FUNCTION guard_finance_write_off_frozen() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'REVERSED' THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '核销记录 % 已撤销（终态），不可再改', OLD.write_off_no
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  -- 三个金额是**派生**的：由 guard_finance_write_off_amount 回写 ⇒ 不在冻结清单里。
  -- ⚠️ `note`（备注）**不在冻结清单里** —— 与 V338 / V341 同口径：
  --   备注是说明，不是账目事实；冻结它只会逼着人「作废重登」。
  IF NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.mode IS DISTINCT FROM OLD.mode
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id THEN
    RAISE EXCEPTION '核销记录 % 的收款单/币种/方式/核销时刻不可再改：撤销请走「核销撤销」', OLD.write_off_no
      USING ERRCODE = 'check_violation';
  END IF;

  -- 状态只允许走到 REVERSED（终态）。
  IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status <> 'REVERSED' THEN
    RAISE EXCEPTION '核销记录 % 的状态只能走到 REVERSED（已撤销），不可改成 %', OLD.write_off_no, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_write_off_frozen
  BEFORE UPDATE ON finance_write_off
  FOR EACH ROW EXECUTE FUNCTION guard_finance_write_off_frozen();

-- (c) 分配明细的三道闸：类型与目标一致、明细行确实属于该应收单、金额为正。
--
--     「核销必须精确到明细行」在数据层的落法：
--     SETTLEMENT 分配的行**必须**属于它写的那张应收单 ——
--     挂错单头会让「按单汇总」与「按行核销」各说各话。
CREATE OR REPLACE FUNCTION guard_finance_write_off_allocation() RETURNS trigger AS $$
DECLARE
  head finance_write_off%ROWTYPE;
  line_receivable uuid;
  line_open numeric(24,4);
  head_currency char(3);
  receivable_status text;
BEGIN
  SELECT * INTO head FROM finance_write_off
   WHERE tenant_id = NEW.tenant_id AND id = NEW.write_off_id
   FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION '核销记录不存在或不属于当前租户' USING ERRCODE = 'check_violation';
  END IF;
  IF head.status = 'REVERSED' THEN
    RAISE EXCEPTION '核销记录 % 已撤销（终态），分配明细不可再改', head.write_off_no
      USING ERRCODE = 'check_violation';
  END IF;

  -- ⚠️ **撤销路径不走下面的核销校验**：撤销是「把已核销的金额退回去」，
  --   不是「再核销一次」⇒ 金额上限、币种一致、应收单状态这些**建单时**的校验
  --   在这里都不适用（退回来的钱会让明细行的未核销余额变大，不是变小）。
  --   真正的边界由 V338 的冻结守卫兜着（终态应收单退不回去）。
  IF NEW.status = 'REVERSED' THEN
    RETURN NEW;
  END IF;

  IF NEW.allocation_type = 'SETTLEMENT' THEN
    SELECT l.receivable_id, l.amount - l.settled_amount
      INTO line_receivable, line_open
      FROM finance_receivable_line l
     WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.receivable_line_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION '应收明细行 % 不存在或不属于当前租户', NEW.receivable_line_id
        USING ERRCODE = 'check_violation';
    END IF;
    IF line_receivable <> NEW.receivable_id THEN
      RAISE EXCEPTION '应收明细行 % 不属于应收单 %：核销必须精确到明细行（只核销到单头无法处理部分回款与折让）',
        NEW.receivable_line_id, NEW.receivable_id USING ERRCODE = 'check_violation';
    END IF;

    SELECT r.currency, r.status INTO head_currency, receivable_status
      FROM finance_receivable r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.receivable_id;
    IF head_currency <> head.currency THEN
      RAISE EXCEPTION '跨币种不允许核销：核销记录 % 的币种是 %，而目标应收单的币种是 %',
        head.write_off_no, head.currency, head_currency USING ERRCODE = 'check_violation';
    END IF;
    IF receivable_status = 'DRAFT' THEN
      RAISE EXCEPTION '应收单还没确认，不可核销：先确认再核销' USING ERRCODE = 'check_violation';
    END IF;
    IF receivable_status IN ('VOID', 'SETTLED') THEN
      RAISE EXCEPTION '应收单已是终态（%），不可核销', receivable_status
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.amount > line_open THEN
      RAISE EXCEPTION '核销金额 % 超过该明细行的未核销余额 %', NEW.amount, line_open
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_write_off_allocation_guard
  BEFORE INSERT OR UPDATE ON finance_write_off_allocation
  FOR EACH ROW EXECUTE FUNCTION guard_finance_write_off_allocation();

-- (d) 预收挂账行必须对应一条 **ADVANCE** 分配；且**只能**追加、不能改金额。
--
--     预收余额 = status='OPEN' 的行之和。已释放 / 已撤销的行是**留痕**，
--     不是「删掉重来」—— 删掉就没有痕迹了。
CREATE OR REPLACE FUNCTION guard_finance_advance_ledger() RETURNS trigger AS $$
DECLARE
  allocation_type text;
  parent_status text;
BEGIN
  SELECT a.allocation_type, w.status INTO allocation_type, parent_status
    FROM finance_write_off_allocation a
    JOIN finance_write_off w ON w.tenant_id = a.tenant_id AND w.id = a.write_off_id
   WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.allocation_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '预收挂账行对应的核销分配不存在或不属于当前租户'
      USING ERRCODE = 'check_violation';
  END IF;
  IF allocation_type <> 'ADVANCE' THEN
    RAISE EXCEPTION '预收挂账只能挂在 ADVANCE（预收挂账）分配上' USING ERRCODE = 'check_violation';
  END IF;
  IF parent_status = 'REVERSED' THEN
    RAISE EXCEPTION '核销记录已撤销（终态），预收挂账行不可再改' USING ERRCODE = 'check_violation';
  END IF;

  IF TG_OP = 'UPDATE' THEN
    IF NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
       OR NEW.allocation_id IS DISTINCT FROM OLD.allocation_id
       OR NEW.write_off_id IS DISTINCT FROM OLD.write_off_id THEN
      RAISE EXCEPTION '预收挂账行的金额/币种/来源不可再改：只能释放或撤销' USING ERRCODE = 'check_violation';
    END IF;
    -- 状态只允许 OPEN → RELEASED / REVERSED。
    IF NEW.status IS DISTINCT FROM OLD.status AND OLD.status <> 'OPEN' THEN
      RAISE EXCEPTION '预收挂账行已经是「%」，不可再改', OLD.status USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_advance_ledger_guard
  BEFORE INSERT OR UPDATE ON finance_advance_ledger
  FOR EACH ROW EXECUTE FUNCTION guard_finance_advance_ledger();

-- (e) 一张核销记录**必须有分配明细**，且分配合计必须与单头一致。
--
--     用**延迟约束触发器**（DEFERRABLE INITIALLY DEFERRED）在**提交时**判定：
--     单头先落库、分配后落库是本模块的写入顺序（分配要外键指向单头），
--     非延迟的触发器在单头插入的瞬间看不到分配明细，会误判。
--
--     ⚠️ **必须回表读当前值，不能用 NEW 里的行镜像**：
--     延迟触发器排队时携带的是**当时**的行镜像（单头插入那一刻 total_amount 还是 0），
--     而单头的金额随后才被 guard_finance_write_off_amount 派生回写成真值 ——
--     用行镜像会把一笔完全正常的核销判成「合计不一致」。
CREATE OR REPLACE FUNCTION guard_finance_write_off_complete() RETURNS trigger AS $$
DECLARE
  allocation_count int;
  allocation_sum numeric(19,4);
  head_total numeric(19,4);
BEGIN
  -- 回表读**当前**的单头金额（派生触发器已经回写好的真值）。
  SELECT total_amount INTO head_total
    FROM finance_write_off
   WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;

  SELECT count(*), coalesce(sum(amount), 0) INTO allocation_count, allocation_sum
    FROM finance_write_off_allocation
   WHERE tenant_id = NEW.tenant_id AND write_off_id = NEW.id;
  IF allocation_count = 0 THEN
    RAISE EXCEPTION '核销记录 % 没有任何分配明细：一笔核销必须落到具体的应收明细行或预收挂账上',
      NEW.write_off_no USING ERRCODE = 'check_violation';
  END IF;
  IF allocation_sum <> head_total THEN
    RAISE EXCEPTION '核销记录 % 的分配明细合计 % 与记录金额 % 不一致',
      NEW.write_off_no, allocation_sum, head_total USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER finance_write_off_complete
  AFTER INSERT OR UPDATE ON finance_write_off
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_finance_write_off_complete();

-- (f) 分配明细的金额与状态在落库后冻结（撤销走「核销撤销」整单处理）。
CREATE OR REPLACE FUNCTION guard_finance_write_off_allocation_frozen() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'REVERSED' THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION '核销分配明细已撤销（终态），不可再改' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.amount IS DISTINCT FROM OLD.amount
     OR NEW.receivable_id IS DISTINCT FROM OLD.receivable_id
     OR NEW.receivable_line_id IS DISTINCT FROM OLD.receivable_line_id
     OR NEW.allocation_type IS DISTINCT FROM OLD.allocation_type
     OR NEW.write_off_id IS DISTINCT FROM OLD.write_off_id
     OR NEW.receipt_id IS DISTINCT FROM OLD.receipt_id THEN
    RAISE EXCEPTION '核销分配明细的金额与目标不可再改：撤销请走「核销撤销」'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status <> 'REVERSED' THEN
    RAISE EXCEPTION '核销分配明细的状态只能走到 REVERSED（已撤销），不可改成 %', NEW.status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_write_off_allocation_frozen
  BEFORE UPDATE ON finance_write_off_allocation
  FOR EACH ROW EXECUTE FUNCTION guard_finance_write_off_allocation_frozen();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：核销时刻倒序 + id。
CREATE INDEX finance_write_off_by_occurred
  ON finance_write_off(tenant_id, status, occurred_at DESC, id);
-- 按收款单取（一笔收款的多笔核销 = 合并回款的痕迹）。
CREATE INDEX finance_write_off_by_receipt
  ON finance_write_off(tenant_id, receipt_id, occurred_at DESC);
-- 按应收单取（这笔应收被核销过几次）。
CREATE INDEX finance_write_off_by_receivable
  ON finance_write_off(tenant_id, occurred_at DESC);
-- 分配明细按核销单取 / 按明细行取（行级核销的入口）。
CREATE INDEX finance_write_off_allocation_by_write_off
  ON finance_write_off_allocation(tenant_id, write_off_id, allocation_type);
CREATE INDEX finance_write_off_allocation_by_line
  ON finance_write_off_allocation(tenant_id, receivable_line_id, created_at DESC);
-- 预收挂账按收款单取 / 按状态取（预收余额的出口）。
CREATE INDEX finance_advance_ledger_by_receipt
  ON finance_advance_ledger(tenant_id, receipt_id, status);
CREATE INDEX finance_advance_ledger_by_status
  ON finance_advance_ledger(tenant_id, currency, status)
  WHERE status = 'OPEN';
-- 事件流按 (occurred_at, id) 排。
CREATE INDEX finance_write_off_event_by_write_off
  ON finance_write_off_event(tenant_id, write_off_id, occurred_at, id);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_write_off','finance_write_off_allocation','finance_write_off_event','finance_advance_ledger'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_write_off, finance_write_off_allocation, finance_write_off_event, finance_advance_ledger TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_write_off, finance_write_off_allocation, finance_write_off_event, finance_advance_ledger TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：事件流是**举证凭据**，只能追加。
REVOKE UPDATE,DELETE ON finance_write_off_event FROM finance_app;

-- 预收挂账是**账**：不能物理删除（撤销走 status='REVERSED'）。
REVOKE DELETE ON finance_advance_ledger FROM finance_app;

-- ===========================================================================
-- 第五节：留给下游 / 上游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13 利润与经营分析（未交付）**：核销是「回款口径」的一处上游输入。
--     reporting 服务不在集成头里 ⇒ 这一段**当前不可产生**。
--     本模块**留了归属标记**（能力声明里 ownerModule='13'，available=false），
--     **绝不造利润数据、绝不用假报表顶替**。
--
--   * **12.1.1.3 坏账与核销（未交付）**：坏账核销走「作废 + 新单」那条路，
--     **不直接写行级 settled_amount** ⇒ 该列的写者始终只有 12.2.1.2（本模块）一个。
--
--   * **12.3.1 结算（未交付）**：核销的输入是**收款单**，收款单的上游是结算；
--     结算未交付时「平台回款」来源当前不可产生（由 12.2.1.1 的服务层拒绝）。
--
