-- ===========================================================================
-- V357 · finance_voucher —— 12.5.2.1 凭证管理
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.5.2.1 凭证管理
--   - **职责**：生成与管理会计凭证：由业务事件自动生成或手工录入，支持过账、校验
--     （至少两行、金额有效、期间开放、借贷平衡）与红冲；已过账凭证不得反过账后改写，
--     导出标准凭证文件。
--   - **输入**：业务事件（发货、收货、收付款、费用计提）；手工凭证；`expected_version`。
--   - **输出**：凭证 + 分录行 + 过账状态；审计记录。
--   - **关系**：上游 = 全部业务系统事件；下游 = 12.5.2.2 报表。**过账后不可改行，只能红冲**；
--     过账事务要锁凭证头并在数据库层防护（实体目录 §9 明确触发器要求）。
--
-- ---------------------------------------------------------------------------
-- 0. 五条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **过账后不可改行，只能红冲 —— 由数据库层钉死**。
--      `guard_finance_voucher_line_frozen` 覆盖分录行的 INSERT / UPDATE / DELETE，
--      且**旧凭证头与新凭证头都查**（把一行从 A 挪到 B 时，A 已过账就写不成）。
--      `guard_finance_voucher_state` 覆盖凭证头的 UPDATE：过了账的凭证只放行
--      「status / reversal_voucher_id / reversed_at / version / updated_at」这组字段，
--      其余**逐字比对** ⇒ **反过账后改写写不出来**。
--      `guard_finance_voucher_no_delete` 覆盖**整单删除**：凭证是会计凭据，错了只能作废。
--
--   2. **校验四条都落在数据层能看见的地方**：
--      * 至少两行   → 服务层 `post` 的 `FOR UPDATE` 事务里数行数（并写进 line_count）。
--      * 金额有效   → 表级 CHECK（amount > 0）+ 服务层币种/小数位/本位币一致性。
--      * 期间开放   → `guard_finance_voucher_post_period`：过账那一刻查会计期间，
--                      期间不是 OPEN ⇒ **过账写不进去**（直连 SQL 也不行）。
--      * 借贷平衡   → 服务层在同一个事务里算借贷合计；触发器另钉「POSTED ⇒ 借贷相等」。
--
--   3. **科目是软引用**（🔴 本次交付的头号集成点）。
--      上游 **12.5.1.1 会计科目未交付**（同批并行车道，`finance_account_subject` 表在集成头里
--      **还不存在**）⇒ 本迁移**不建科目表、不建指向 `finance_account_subject` 的外键**
--      （那张表还没交付，建 FK 会让 Flyway 直接失败；两边同时建同名表也会在并入时撞车）。
--      分录行存 `subject_code`（科目编码，文本）+ `subject_name`（**快照**）。
--      ⇒ 12.5.1.1 交付后的接法见**第六节「交接面」**。
--
--   4. **业务事件映射由租户维护，系统不预置任何一行**。
--      `finance_voucher_mapping` 是「业务事件 → 借贷科目」的登记处：**没有任何硬编码科目常量**
--      （系统不猜用户的会计科目体系 —— 那是 12.5.1.1 的职责）。
--      ⇒ 没有登记映射就生成不出凭证，这是**如实行为**而不是缺功能。
--
--   5. **金额与时间口径**：金额 numeric(19,4)、数量 numeric(19,6)、汇率 numeric(19,8)；
--      绝对时间 timestamptz（Java 侧绑 `OffsetDateTime` + `?::timestamptz`），
--      业务日期 date。**全链路禁浮点**。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_voucher_period    —— 会计期间登记（**过渡期 bootstrap**，见第六节交接面）
--   finance_voucher           —— 凭证头
--   finance_voucher_line      —— 分录行（科目软引用）
--   finance_voucher_event     —— 审计留痕（append-only）
--   finance_voucher_mapping   —— 业务事件 → 借贷科目映射（租户维护，不预置）
--
--   五张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_voucher_period —— 会计期间登记
-- ---------------------------------------------------------------------------
--
-- ⚠️ **本表是「过渡期 bootstrap」，不是会计期间模块**。
--   蓝图 12.5.1.2「会计期间」的职责是「管理会计期间（自然月或 4-4-5 fiscal 周期）：
--   期间状态（开放/关闭）、**关账与重开**、期间内凭证限制」，上游是「会计日历」，
--   且明写「**重开已关期间是高风险操作**，必须审批留痕」。
--   12.5.1.2 **未交付** ⇒ 本模块**只**提供「期间开放/关闭」这一个过账闸门需要的事实：
--
--     * 本表**没有**关账检查清单（那是 12.5.1.3 关账）。
--     * 本表**没有** 4-4-5 fiscal 周期（那是 12.5.1.2）。
--     * 本表**没有**重开（重开 = 高风险 + 必须审批 ⇒ 审批属 17 审批（identity），
--       本模块**刻意不提供**本地审批端点，也不自己造第二套审批引擎）。
--       ⇒ 本表的期间**只能关、不能重开**（fail-closed），这是**如实行为**。
--
--   为什么还需要它：蓝图要求过账前校验「**期间开放**」。没有期间真相，
--   「期间开放」就是一句假校验（禁止假校验）。所以这里落一份**最小**期间登记，
--   让这条校验**真的能挡住**一笔不该过账的凭证。
--
--   交接：12.5.1.2 交付后**接管本表**（照 V350 接管 V346 守卫的同一手法）：
--   它加 fiscal 周期、关账检查与重开审批列，本模块继续读本表的 status 做过账闸门。
--   详见第六节。
CREATE TABLE finance_voucher_period (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 期间编码（如 '2026-10'）。租户自定，但**同租户内唯一**。
  period_code varchar(16) NOT NULL,
  -- 期间区间：日历天（date），不是 timestamptz —— 期间是一段日历，不是一瞬间。
  period_start date NOT NULL,
  period_end date NOT NULL,
  -- 开放 / 关闭。**只有这两个值**。
  status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),
  -- 本位币（账簿本位币）：过账时凭证币种必须与它一致（触发器钉死）。
  base_currency char(3) NOT NULL CHECK(base_currency ~ '^[A-Z]{3}$'),
  -- 关闭留痕：关账人 / 时刻 / 原因（关账必须说得清为什么关）。
  closed_at timestamptz,
  closed_by uuid,
  closed_reason varchar(500) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, period_code),
  CHECK(btrim(period_code) <> ''),
  CHECK(period_end >= period_start),
  -- 关闭 ⇒ 必须写明关闭人 / 时刻 / 原因；开放 ⇒ 没有关闭痕迹。
  CONSTRAINT finance_voucher_period_closed_ck CHECK (
    (status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL AND btrim(closed_reason) = '')
    OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL AND btrim(closed_reason) <> '')
  )
);

-- ---------------------------------------------------------------------------
-- 2. finance_voucher —— 凭证头
-- ---------------------------------------------------------------------------
--
-- 一行 = 「一张会计凭证」。
--
-- 🔴 **source_type / source_id 是逻辑来源引用，不建跨模块外键**：
--   db/schemas/v2/README.md 明写「凭证 source_type/source_id 是逻辑来源引用，
--   不给多态 source_id 加指向 sales_order 的假 FK」。业务事件分属 trade / finance 的
--   不同模块 ⇒ 这里**只存引用 + 快照单号**。
--
-- 🔴 **状态机（服务层 + 触发器双层）**：
--   DRAFT（草稿）→ POSTED（已过账）→ REVERSED（已红冲）
--   DRAFT → VOID（作废）
--   **没有「反过账」这条边** —— 已过账凭证不可回到 DRAFT。
--   要改写已过账的凭证，只能**生成一张红冲凭证**（reversal_of_id 指向原凭证），
--   并把原凭证标成 REVERSED。
CREATE TABLE finance_voucher (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  voucher_no varchar(48) NOT NULL,
  -- 来源类型：MANUAL 手工 / SHIPMENT 发货 / RECEIPT 收货 / PAYMENT 收付款 /
  -- EXPENSE_ACCRUAL 费用计提 / REVERSAL 红冲。
  source_type varchar(24) NOT NULL CHECK(source_type IN (
    'MANUAL','SHIPMENT','RECEIPT','PAYMENT','EXPENSE_ACCRUAL','REVERSAL')),
  -- 来源业务事实的逻辑引用（**无 FK**，见上）。手工凭证为空。
  source_id uuid,
  -- 来源单号快照：来源侧后来改号/删号，凭证这一侧仍能自证「当时是哪一单」。
  source_no varchar(160) NOT NULL DEFAULT '',
  -- 红冲：本凭证是那张凭证的反向凭证（自引用复合外键）。
  reversal_of_id uuid,
  -- 会计期间（本表内复合外键）。
  period_id uuid NOT NULL,
  -- 业务日期（date）：凭证归属的业务日，不是过账时刻。
  voucher_date date NOT NULL,
  description varchar(500) NOT NULL DEFAULT '',
  -- 本位币（与期间本位币一致，过账时由触发器核对）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','POSTED','REVERSED','VOID')),
  -- 借贷合计（**由服务层在过账事务里算好写入**；POSTED 之后被触发器冻结）。
  total_debit numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_debit >= 0),
  total_credit numeric(19,4) NOT NULL DEFAULT 0 CHECK(total_credit >= 0),
  -- 分录行数（服务层写入；POSTED 之后冻结）。
  line_count int NOT NULL DEFAULT 0 CHECK(line_count >= 0),
  posted_at timestamptz,
  posted_by uuid,
  -- 红冲凭证（这张凭证被哪张红冲凭证冲掉）。
  reversal_voucher_id uuid,
  reversed_at timestamptz,
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, voucher_no),
  FOREIGN KEY(tenant_id, period_id) REFERENCES finance_voucher_period(tenant_id, id),
  FOREIGN KEY(tenant_id, reversal_of_id) REFERENCES finance_voucher(tenant_id, id),
  FOREIGN KEY(tenant_id, reversal_voucher_id) REFERENCES finance_voucher(tenant_id, id),
  CHECK(btrim(voucher_no) <> ''),
  -- 草稿 / 作废 ⇒ 没有过账痕迹；已过账 / 已红冲 ⇒ 必须有。
  CONSTRAINT finance_voucher_posted_ck CHECK (
    (status IN ('DRAFT','VOID') AND posted_at IS NULL AND posted_by IS NULL)
    OR (status IN ('POSTED','REVERSED') AND posted_at IS NOT NULL AND posted_by IS NOT NULL)
  ),
  -- 已红冲 ⇒ 必须指向那张红冲凭证。
  CONSTRAINT finance_voucher_reversed_ck CHECK (
    status <> 'REVERSED' OR reversal_voucher_id IS NOT NULL
  ),
  -- 红冲凭证本身必须指向一张原凭证。
  CONSTRAINT finance_voucher_reversal_of_ck CHECK (
    source_type <> 'REVERSAL' OR reversal_of_id IS NOT NULL
  ),
  -- 不许自己冲自己。
  CHECK(reversal_of_id IS NULL OR reversal_of_id <> id)
);

-- ---------------------------------------------------------------------------
-- 3. finance_voucher_line —— 分录行
-- ---------------------------------------------------------------------------
--
-- 一行 = 「凭证上的一条借方或贷方」。
--
-- 🔴 **科目是软引用**（本模块的头号集成点）：
--    * `subject_code`  —— 科目编码（文本）。上游 12.5.1.1「会计科目」**未交付**
--      （`finance_account_subject` 表在集成头里不存在）⇒ **不建科目表、不建 FK**。
--    * `subject_name`  —— 科目名称**快照**：科目后来改名，历史凭证仍能读出「当时叫什么」。
--    * 12.5.1.1 交付后接 FK 的具体做法见第六节「交接面」。
--
-- 🔴 **金额必须大于 0**：「金额有效」的第一层。方向由 line_type（借/贷）表达，
--    零金额行、负金额行在数据层就写不出来。
CREATE TABLE finance_voucher_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  voucher_id uuid NOT NULL,
  -- 行号（凭证内连续，从 1 起）。
  line_no int NOT NULL CHECK(line_no > 0),
  -- 借贷方向。
  line_type varchar(8) NOT NULL CHECK(line_type IN ('DEBIT','CREDIT')),
  -- 🔴 科目软引用（见上）。
  subject_code varchar(64) NOT NULL,
  subject_name varchar(120) NOT NULL,
  -- 摘要（凭证行的说明）。
  summary varchar(500) NOT NULL DEFAULT '',
  -- 金额：numeric(19,4)，必须 > 0。
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 数量（numeric(19,6)）：业务事件带数量时才有值（纯金额行留空）。
  quantity numeric(19,6) CHECK(quantity IS NULL OR quantity <> 0),
  -- 辅助核算维度（**逻辑引用，不建跨模块 FK**）：
  -- ORDER / SUPPLIER / PAYMENT_ORDER / COST_ENTRY / NONE。
  auxiliary_type varchar(24) NOT NULL DEFAULT 'NONE' CHECK(auxiliary_type IN (
    'NONE','ORDER','SUPPLIER','PAYMENT_ORDER','COST_ENTRY','CUSTOMER','PLATFORM','ACCOUNT')),
  auxiliary_id uuid,
  auxiliary_name varchar(160) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, voucher_id, line_no),
  FOREIGN KEY(tenant_id, voucher_id) REFERENCES finance_voucher(tenant_id, id) ON DELETE CASCADE,
  CHECK(btrim(subject_code) <> ''),
  CHECK(btrim(subject_name) <> ''),
  -- 有辅助维度 ⇒ 必须有辅助对象引用；没有 ⇒ 不得有。
  CHECK((auxiliary_type = 'NONE' AND auxiliary_id IS NULL) OR (auxiliary_type <> 'NONE' AND auxiliary_id IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- 4. finance_voucher_event —— 审计留痕（append-only）
-- ---------------------------------------------------------------------------
--
-- 蓝图输出写死「**审计记录**」。这里一行 = 「凭证上发生过的���件事」。
-- 与 finance_payment_order_event / finance_payment_execution_event 同一手法：
-- 事件流是「当时发生了什么」的**唯一**出处，只能追加。
CREATE TABLE finance_voucher_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  voucher_id uuid NOT NULL,
  event_type varchar(32) NOT NULL CHECK(event_type IN (
    'CREATED','UPDATED','POSTED','REVERSAL_CREATED','REVERSED','VOIDED','EXPORTED',
    'VALIDATION_FAILED','AUTO_GENERATED')),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  -- 补充说明（谁改的、改了哪几行、导出了什么…）。**不记 PII、不记全量请求体**。
  detail varchar(1000) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 排序必须 (occurred_at, id)：同一毫秒发生的事件只看时间顺序不确定。
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, voucher_id) REFERENCES finance_voucher(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 5. finance_voucher_mapping —— 业务事件 → 借贷科目映射
-- ---------------------------------------------------------------------------
--
-- 一行 = 「这类业务事件，借哪个科目、贷哪个科目」。
--
-- 🔴 **系统不预置任何一行**：用户的会计科目体系是**用户自定的**（12.5.1.1 蓝图为原话），
--   系统只提供模板、不替用户定 ⇒ 本表**没有任何硬编码科目常量**。
--   没有登记映射 ⇒ 自动生成生成不出凭证（**如实报错**，不猜科目）。
--
-- ⚠️ **归属说明**：12.5.1.1 的蓝图输出写死「科目表 + **映射规则**」⇒
--   映射规则的**最终归属是 12.5.1.1**。本表是它未交付期间的**过渡登记处**，
--   交接做法见第六节。
CREATE TABLE finance_voucher_mapping (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 业务事件类型（与 finance_voucher.source_type 同一词表）。
  source_type varchar(24) NOT NULL CHECK(source_type IN (
    'SHIPMENT','RECEIPT','PAYMENT','EXPENSE_ACCRUAL')),
  -- 事件细分：同一 source_type 下的不同业务口径（如 PAYMENT 的付款 / 收款）。
  event_kind varchar(24) NOT NULL CHECK(event_kind IN (
    'SALE','PURCHASE','PAYMENT','RECEIPT','ACCRUAL')),
  -- 借方科目（软引用：编码 + 名称快照，理由同 finance_voucher_line.subject_code）。
  debit_subject_code varchar(64) NOT NULL,
  debit_subject_name varchar(120) NOT NULL,
  -- 贷方科目。
  credit_subject_code varchar(64) NOT NULL,
  credit_subject_name varchar(120) NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一 (来源类型, 细分) 只有一条生效映射。
  UNIQUE(tenant_id, source_type, event_kind),
  CHECK(btrim(debit_subject_code) <> ''),
  CHECK(btrim(debit_subject_name) <> ''),
  CHECK(btrim(credit_subject_code) <> ''),
  CHECK(btrim(credit_subject_name) <> '')
);

-- ===========================================================================
-- 第二节：数据库层守卫（过账后不可改行，只能红冲）
-- ===========================================================================

-- (a) 分录行冻结守卫 —— **INSERT / UPDATE / DELETE 全覆盖**。
--
--     🔴 **旧凭证头与新凭证头都查**：
--        * UPDATE 把一行从凭证 A 挪到凭证 B ⇒ 先查 A（OLD）——A 已过账就写不成；
--          再查 B（NEW）。
--        * INSERT ⇒ 查 NEW。
--        * DELETE ⇒ 查 OLD。
--     ⇒ 「过账后改行」「过账后加行」「过账后删行」「把行挪到别的凭证」**四条路全堵死**。
--
--     ⚠️ 返回值：UPDATE / INSERT 必须返回 NEW —— BEFORE 触发器返回 OLD 等于「把这一行改回旧值」，
--        那会让每次更新都静默失效（语句成功、行没变），比报错更难查。
CREATE OR REPLACE FUNCTION guard_finance_voucher_line_frozen() RETURNS trigger AS $$
DECLARE
  parent_status text;
BEGIN
  -- ① 旧凭证头（UPDATE / DELETE）
  IF TG_OP IN ('UPDATE','DELETE') THEN
    SELECT status INTO parent_status FROM finance_voucher
     WHERE tenant_id = OLD.tenant_id AND id = OLD.voucher_id;
    IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
      RAISE EXCEPTION '凭证 % 已是「%」，分录行不可增删改：过账后只能红冲（生成一张反向凭证）'
        , OLD.voucher_id, parent_status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  -- ② 新凭证头（INSERT / UPDATE）
  IF TG_OP IN ('INSERT','UPDATE') THEN
    SELECT status INTO parent_status FROM finance_voucher
     WHERE tenant_id = NEW.tenant_id AND id = NEW.voucher_id;
    IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
      RAISE EXCEPTION '凭证 % 已是「%」，分录行不可增删改：过账后只能红冲（生成一张反向凭证）'
        , NEW.voucher_id, parent_status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_voucher_line_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON finance_voucher_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_voucher_line_frozen();

-- (b) 凭证头状态守卫 —— 状态机 + 事实冻结。
--
--     ① **没有「反过账」这条边**：POSTED 不能回到 DRAFT。
--     ② 已过账 / 已红冲的凭证，**事实逐字比对**：只有
--        status / reversal_voucher_id / reversed_at / version / updated_at 能动。
--     ③ POSTED ⇒ 必须有 posted_at / posted_by。
--     ④ REVERSED ⇒ 必须指向那张红冲凭证。
CREATE OR REPLACE FUNCTION guard_finance_voucher_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
         (OLD.status = 'DRAFT'  AND NEW.status IN ('POSTED','VOID'))
      OR (OLD.status = 'POSTED' AND NEW.status = 'REVERSED')
    ) THEN
      RAISE EXCEPTION '凭证 % 的状态只能沿 草稿→已过账→已红冲 流转（草稿可作废），% → % 不允许：已过账凭证不得反过账后改写，只能红冲'
        , OLD.voucher_no, OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'POSTED' THEN
      IF NEW.posted_at IS NULL OR NEW.posted_by IS NULL THEN
        RAISE EXCEPTION '凭证 % 过账必须写明过账人与过账时刻', OLD.voucher_no
          USING ERRCODE = 'check_violation';
      END IF;
      -- 借贷平衡的第二层守卫（第一层在服务层的 FOR UPDATE 事务里）。
      IF NEW.total_debit IS DISTINCT FROM NEW.total_credit THEN
        RAISE EXCEPTION '凭证 % 借贷不平：借方合计 % ≠ 贷方合计 %，不能过账'
          , OLD.voucher_no, NEW.total_debit, NEW.total_credit
          USING ERRCODE = 'check_violation';
      END IF;
      IF NEW.line_count < 2 THEN
        RAISE EXCEPTION '凭证 % 至少要有两行分录才能过账（当前 % 行）', OLD.voucher_no, NEW.line_count
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;

    IF NEW.status = 'REVERSED' AND NEW.reversal_voucher_id IS NULL THEN
      RAISE EXCEPTION '凭证 % 标记已红冲必须指向那张红冲凭证', OLD.voucher_no
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- 已过账 / 已红冲 ⇒ 凭证的**事实冻结**（只放行红冲那一组字段）。
  IF OLD.status IN ('POSTED','REVERSED') THEN
    IF NEW.voucher_no IS DISTINCT FROM OLD.voucher_no
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_id IS DISTINCT FROM OLD.source_id
       OR NEW.source_no IS DISTINCT FROM OLD.source_no
       OR NEW.reversal_of_id IS DISTINCT FROM OLD.reversal_of_id
       OR NEW.period_id IS DISTINCT FROM OLD.period_id
       OR NEW.voucher_date IS DISTINCT FROM OLD.voucher_date
       OR NEW.description IS DISTINCT FROM OLD.description
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.total_debit IS DISTINCT FROM OLD.total_debit
       OR NEW.total_credit IS DISTINCT FROM OLD.total_credit
       OR NEW.line_count IS DISTINCT FROM OLD.line_count
       OR NEW.posted_at IS DISTINCT FROM OLD.posted_at
       OR NEW.posted_by IS DISTINCT FROM OLD.posted_by
       OR NEW.note IS DISTINCT FROM OLD.note
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
      RAISE EXCEPTION '凭证 % 已过账（%），不可改写：需要改请做红冲（生成一张反向凭证），不要反过账后改写'
        , OLD.voucher_no, OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_voucher_state
  BEFORE UPDATE ON finance_voucher
  FOR EACH ROW EXECUTE FUNCTION guard_finance_voucher_state();

-- (c) 凭证头**不可删除** —— 覆盖「整单删除」。
--
--     凭证是会计凭据：错了只能**作废**（草稿）或**红冲**（已过账），不能删。
--     这也让 finance_voucher_line 的 ON DELETE CASCADE 永远不会发生（父行删不掉）。
CREATE OR REPLACE FUNCTION guard_finance_voucher_no_delete() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '凭证 % 不可删除：凭证是会计凭据，错了只能作废（草稿）或红冲（已过账）', OLD.voucher_no
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_voucher_no_delete
  BEFORE DELETE ON finance_voucher
  FOR EACH ROW EXECUTE FUNCTION guard_finance_voucher_no_delete();

-- (d) **过账时的期间闸门** —— 「期间开放」这条校验的数据层落点。
--
--     写 POSTED 的那一刻查会计期间：
--       * 期间不存在   ⇒ 写不成（FK 也会挡，但这里给出可读的原因）。
--       * 期间不是 OPEN ⇒ 写不成（**已关闭期间禁止过账**）。
--       * 本位币不一致 ⇒ 写不成（账簿本位币只有一个）。
CREATE OR REPLACE FUNCTION guard_finance_voucher_post_period() RETURNS trigger AS $$
DECLARE
  -- ⚠️ 变量名**不能**与列名同名：PL/pgSQL 遇到 `period_code` 既可能是列又可能是变量时会报
  --    「column reference period_code is ambiguous」⇒ 这里统一加 v_ 前缀。
  v_period_status text;
  v_period_code text;
  v_period_currency char(3);
BEGIN
  IF NEW.status = 'POSTED' AND OLD.status IS DISTINCT FROM NEW.status THEN
    SELECT status, period_code, base_currency
      INTO v_period_status, v_period_code, v_period_currency
      FROM finance_voucher_period
     WHERE tenant_id = NEW.tenant_id AND id = NEW.period_id;
    IF v_period_status IS NULL THEN
      RAISE EXCEPTION '凭证 % 的会计期间不存在：不能过账', NEW.voucher_no
        USING ERRCODE = 'check_violation';
    END IF;
    IF v_period_status <> 'OPEN' THEN
      RAISE EXCEPTION '会计期间 %（%）已关闭，不允许过账', v_period_code, v_period_status
        USING ERRCODE = 'check_violation';
    END IF;
    IF v_period_currency IS DISTINCT FROM NEW.currency THEN
      RAISE EXCEPTION '会计期间 %（%）的本位币是 %，凭证币种是 %：本位币不一致不能过账'
        , v_period_code, v_period_status, v_period_currency, NEW.currency
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_voucher_post_period
  BEFORE UPDATE ON finance_voucher
  FOR EACH ROW EXECUTE FUNCTION guard_finance_voucher_post_period();

-- (e) 会计期间状态守卫 —— 只能关、不能重开。
--
--     「重开已关期间是高风险操作，必须审批留痕」（12.5.1.2 蓝图为原话）⇒
--     审批属 17 审批（identity），本模块**刻意不提供**本地审批端点，
--     也不自己造第二套审批引擎 ⇒ **本表的期间只能关、不能重开**（fail-closed）。
--     已关闭的期间**整行冻结**。
CREATE OR REPLACE FUNCTION guard_finance_voucher_period_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (OLD.status = 'OPEN' AND NEW.status = 'CLOSED') THEN
      RAISE EXCEPTION '会计期间 % 的状态只能从开放走向关闭，% → % 不允许（重开已关期间属 12.5.1.2，未交付）'
        , OLD.period_code, OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.closed_at IS NULL OR NEW.closed_by IS NULL OR btrim(NEW.closed_reason) = '' THEN
      RAISE EXCEPTION '会计期间 % 关闭必须写明关闭人、关闭时刻与关闭原因', OLD.period_code
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  IF OLD.status = 'CLOSED' THEN
    RAISE EXCEPTION '会计期间 % 已关闭，整行不可再改（重开属 12.5.1.2，未交付）', OLD.period_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_voucher_period_state
  BEFORE UPDATE ON finance_voucher_period
  FOR EACH ROW EXECUTE FUNCTION guard_finance_voucher_period_state();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 列表默认排序：创建时间倒序 + id（同一毫秒创建的凭证只看时间顺序不确定）。
CREATE INDEX finance_voucher_by_created
  ON finance_voucher(tenant_id, created_at DESC, id);
-- 按状态查（待过账的草稿就在这里）。
CREATE INDEX finance_voucher_by_status
  ON finance_voucher(tenant_id, status, created_at DESC, id);
-- 按期间查（报表按期间取数）。
CREATE INDEX finance_voucher_by_period
  ON finance_voucher(tenant_id, period_id, voucher_date DESC, id);
-- 按业务日期查。
CREATE INDEX finance_voucher_by_date
  ON finance_voucher(tenant_id, voucher_date DESC, id);
-- 一张凭证最多被红冲一次（红冲凭证唯一）。
CREATE UNIQUE INDEX uq_finance_voucher_reversal_once
  ON finance_voucher(tenant_id, reversal_of_id)
  WHERE reversal_of_id IS NOT NULL;
-- 按来源事实查（同一笔业务事件只许生成一张凭证 ⇒ 唯一索引）。
CREATE UNIQUE INDEX uq_finance_voucher_source_once
  ON finance_voucher(tenant_id, source_type, source_id)
  WHERE source_id IS NOT NULL AND source_type <> 'REVERSAL';
-- 按币种查（资金口径永远按币种分开）。
CREATE INDEX finance_voucher_by_currency
  ON finance_voucher(tenant_id, currency, created_at DESC);
-- 分录行按凭证排（行号序）。
CREATE INDEX finance_voucher_line_by_voucher
  ON finance_voucher_line(tenant_id, voucher_id, line_no);
-- 按科目查（报表按科目汇总）。
CREATE INDEX finance_voucher_line_by_subject
  ON finance_voucher_line(tenant_id, subject_code);
-- 事件流按 (occurred_at, id) 排。
CREATE INDEX finance_voucher_event_by_voucher
  ON finance_voucher_event(tenant_id, voucher_id, occurred_at, id);
-- 期间按状态查。
CREATE INDEX finance_voucher_period_by_status
  ON finance_voucher_period(tenant_id, status, period_start DESC);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_voucher_period','finance_voucher','finance_voucher_line',
    'finance_voucher_event','finance_voucher_mapping'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_voucher_period, finance_voucher, finance_voucher_line,
  finance_voucher_event, finance_voucher_mapping TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_voucher_period, finance_voucher, finance_voucher_line,
  finance_voucher_event, finance_voucher_mapping TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：
--   * 审计事件流是**举证凭据**，只能追加；
--   * 凭证头**不可删除**（错了只能作废 / 红冲）⇒ 连级联删除这条路也断掉。
--   ⚠️ **分录行的 DELETE 权限保留**：改一张**草稿**凭证要「删掉旧行、写入新行」，
--      收回 DELETE 会让草稿改不动。已过账的分录行的删改由
--      `guard_finance_voucher_line_frozen` 触发器挡住（那才是真正的那道闸）。
REVOKE UPDATE,DELETE ON finance_voucher_event FROM finance_app;
REVOKE DELETE ON finance_voucher FROM finance_app;

-- ===========================================================================
-- 第五节：金额与时间口径（写给运维与下游）
-- ===========================================================================

--  * 金额 numeric(19,4)、数量 numeric(19,6)、汇率 numeric(19,8)；Java 侧一律 BigDecimal。
--    **全链路禁浮点。**
--  * 绝对时间 timestamptz（Java 绑 OffsetDateTime + `?::timestamptz`），
--    业务日期 date（voucher_date / period_start / period_end）。
--  * 任何汇总都按币种分组 —— USD 与 EUR 加起来不是任何一个金额。
--
-- ===========================================================================
-- 第六节：交接面（写给 12.5.1.1 / 12.5.1.2 / 12.5.2.2）
-- ===========================================================================
--
-- ① **12.5.1.1 会计科目（未交付）—— 科目软引用待接 FK**：
--    本迁移**没有**建科目表，也**没有**建指向 `finance_account_subject` 的外键
--    （那张表同批并行交付，在集成头里此刻还不存在 ⇒ 建 FK 会让 Flyway 直接失败）。
--    分录行存 `subject_code`（科目编码文本）+ `subject_name`（名称快照）。
--    **12.5.1.1 交付后的接法**（由 12.5.1.1 车道或后续迁移执行，二选一）：
--       (a) 在本表加 `subject_id uuid` 列 + 复合外键
--           `FOREIGN KEY(tenant_id, subject_id) REFERENCES finance_account_subject(tenant_id, id)`，
--           并把 `subject_code` 降级为**快照**（科目编码改了就改快照，但凭证仍读得出来）；
--           或
--       (b) 保持软引用不变，由 12.5.1.1 的科目表自己声明「编码在租户内唯一」，
--           本模块在服务层加一条「科目必须存在」的业务校验。
--    ⚠️ 在 12.5.1.1 交付**之前**，本模块**允许**任何科目编码（那是一个租户自定体系，
--    没有科目表就无从校验）⇒ 这一点已如实写进能力声明，不假装「科目已校验」。
--
-- ② **12.5.1.2 会计期间（未交付）—— 接管 `finance_voucher_period`**：
--    本表是**过渡期 bootstrap**（见第 1 节说明）：只有「开放/关闭 + 本位币 + 关闭留痕」，
--    没有 fiscal 周期、没有关账检查清单、**不能重开**。
--    12.5.1.2 交付后**接管本表**（照 V350 接管 V346 守卫的同一手法）：
--    它 ADD COLUMN 补 fiscal 周期 / 关账检查 / 重开审批留痕，并把「重开」的守卫加进来；
--    本模块继续读本表的 `status` 做过账闸门 ⇒ **本模块的结构不用改**。
--    ⚠️ 期间表的**最终归属是 12.5.1.2**（它的蓝图输出写死「期间状态；关账记录」）。
--
-- ③ **12.5.1.1 的映射规则 —— `finance_voucher_mapping` 是过渡登记处**：
--    12.5.1.1 的蓝图输出写死「科目表 + **映射规则**」⇒ 映射规则的最终归属是它。
--    本表在它交付前承担「业务事件 → 借贷科目」的登记（**行由租户维护，系统不预置任何一行**）。
--    12.5.1.1 交付后：它接管映射规则，本表**退化为过渡登记处**，
--    后续迁移把两边的映射并过去（或由它 ALTER 接管本表）⇒ 本模块结构不用改。
--
-- ④ **12.5.2.2 财务报表（未交付）—— 本模块只提供「可钻取的事实」**：
--    已过账凭证 + 分录行 + 期间 + 科目编码，就是报表要的全部输入。
--    本模块**不生成任何报表**，也**不替报表做任何汇总**。
--
