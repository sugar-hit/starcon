-- ===========================================================================
-- V363 · period_close —— 12.5.1.3 关账
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文（12.5.1.3 关账）：
--
--   - **职责**：期末关账流程：检查未处理事项（未对账差异、未结转成本、未核销款项、
--     未过账凭证），按已批准清单区分阻断项与可结转未清项后关闭期间；
--     禁止向已关闭期间录入业务凭证。
--   - **输入**：关账检查清单结果；关账确认。
--   - **输出**：关账完成记录；期间锁定。
--   - **关系**：上游 = 12.5.1.2；下游 = 12.5.2.2 报表。
--     **关账检查清单是防错最后一道关**：阻断项未解决不允许关账；
--     正常未到期应收应付不要求清零，例外与结转须保留依据。
--
-- ---------------------------------------------------------------------------
-- 0. 七条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **职责边界：12.5.1.3 拥有「关账流程」，12.5.1.2 拥有「期间记录与状态机」。**
--      ⇒ 本迁移**只 CREATE 自己的新表**（清单 / 检查项 / 检查明细 / 金额分组 /
--      执行记录 / 审批镜像），**读** `finance_voucher_period`（12.5.1.2 已接管）。
--      ⇒ **绝不新建平行的「期间表」** —— 仓库里已经有 `finance_voucher_period`
--        （V357 建、V359 接管 12.5.1.2 补 fiscal 周期与重开留痕）。
--        再建一张就是**第二个真相来源**，那是比「缺功能」严重得多的错。
--      ⇒ 本迁移**零 ALTER 别人的表、零 ADD COLUMN 到别人的表、零 DROP 别人的表**。
--      ⇒ 关账这个动作**不重新实现**：服务层直接调 12.5.1.2 的
--        `AccountingPeriodService.close(...)`（它写 status / closed_at / closed_by /
--        closed_reason，并由 V359 的 `guard_finance_voucher_period_trail` 留痕）。
--        本模块**不碰**期间状态机、**不实现**重开（那是 12.5.1.2）。
--
--   2. **检查清单要真查数据，不是空壳勾选框。**
--      四类检查项各自对应真实数据源：
--        * 未对账差异 → 12.3.2.1 对账任务 / 12.3.2.2 差异处理 **未交付**
--          ⇒ 该项如实报「上游未交付 / 不可判定」（result = NOT_DETERMINABLE），
--            **不得**假装通过、**不得**静默当成通过；它归入「可结转未清项」，
--            **不得**把关账卡死在一个永远无法满足的条件上。
--        * 未结转成本 → `finance_cost_calculation_run`（12.4.1.2，已交付）
--        * 未核销款项 → `finance_receivable`（12.1.1.1）/ `finance_receipt`（12.2.1.1）
--          / `finance_settlement`（12.3.1.1），均已交付
--        * 未过账凭证 → `finance_voucher` status='DRAFT'（12.5.2.1，已交付）
--      ⇒ 每个检查项都落**具体条数**与**可钻取明细**（一张明细表，不是「有 / 没有」布尔值）。
--
--   3. **阻断项 vs 可结转未清项，分类与依据都落库。**
--      每个检查项同时记两个分类：
--        * `rule_classification` —— **规则判定**（不可改）：BLOCKING / CARRYWARD
--        * `classification`     —— **最终分类**（人工可下调为 CARRYWARD，但必须写例外依据）
--      ⇒ DB 层 CHECK 钉死：「分类被下调 ⇒ `exception_basis` 必须非空」，
--        「分类依据必须非空」—— 依据不能只在页面上写一句。
--      ⇒ 蓝图加粗句「正常未到期应收应付不要求清零」落到规则里：
--        未到期应收 / 未到账结算 / 未核销收款 → CARRYWARD；
--        已逾期未核销 / 争议冻结 / 成本有缺口 / 草稿凭证 → BLOCKING。
--
--   4. **关账是不可逆的期末操作 ⇒ 审批留痕。**
--      走 17 审批（identity）的授权 HTTP API（`ApprovalPort`），单据类型码
--      **本模块自己的** `PERIOD_CLOSE`（**不复用**付款单的 `PAYMENT_ORDER`，
--      也不复用 12.5.1.2 的 `ACCOUNTING_PERIOD_REOPEN`）。
--      ⇒ 审批链路镜像进 `finance_period_close_approval`，可从关账记录钻取到。
--      ⇒ **审批不可达 ⇒ 如实报「审批不可达」，绝不假装审批通过。**
--
--   5. **「已关闭期间禁止过账」由 V357 的 `guard_finance_voucher_post_period` 生效**
--      ⇒ 本迁移**不重复实现、不改语义**；关账结果只如实反映
--        「该期间已锁定、禁止过账」。
--
--   6. **多币种绝不相加。**
--      金额落在**按币种分组**的 `finance_period_close_check_amount`（唯一键
--      `(tenant_id, check_id, currency)`）⇒ 结构上就不存在「跨币种求和」这一格。
--      汇率不可得就分组呈现、不瞎折算（服务层用 `ExchangeRatePort` 试折算，
--      取不到就如实标注「汇率不可得，未折算」）。
--
--   7. **金额与时间口径**：金额 numeric(19,4)、数量 numeric(19,6)、汇率 numeric(19,8)；
--      绝对时间 timestamptz（Java 侧绑 OffsetDateTime + values 位置写 `?::timestamptz`），
--      期间区间 date（期间是一段日历，不是一瞬间）。**全链路禁浮点。**
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_period_close_checklist      —— 关账检查清单（一次运行 = 一行）
--   finance_period_close_check          —— 逐项检查结果（四类检查项各一行）
--   finance_period_close_check_item     —— 可钻取明细（一个未处理事项 = 一行）
--   finance_period_close_check_amount   —— 按币种分组的金额（**永不跨币种相加**）
--   finance_period_close_execution      —— 关账执行记录（关账完成记录）
--   finance_period_close_approval       —— 审批链路镜像（identity 的本地镜像）
--
--   六张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation
--   + GRANT finance_app / starcon_app。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 2. finance_period_close_checklist —— 关账检查清单
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某期间的一次检查清单运行」。
--
-- ⚠️ **不是「一个期间一张清单」**：清单是**举证件**，重跑一次就多一份证据
--   （run_no 递增）⇒ 事后能回答「当时按哪一份清单关的账」。
--   关账执行记录指向**具体那一份**清单（checklist_id）。
--
-- 🔴 **只追加**：清单落库后不可改、不可删（运行角色 UPDATE/DELETE 已收回 +
--   触发器兜底）。要重新检查就再跑一次。
CREATE TABLE finance_period_close_checklist (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 关账对象：12.5.1.2 的期间表（**同服务同库** ⇒ 复合外键；不新建平行期间表）。
  period_id uuid NOT NULL,
  -- 第几次检查（同一期间递增）。
  run_no int NOT NULL CHECK(run_no >= 1),
  -- 清单编号（如 'CL-2026-10-0001'）—— 事后对账时的单号。
  checklist_no varchar(48) NOT NULL,
  -- 本次检查的期间区间**快照**（date）：检查口径的一部分，日后期间改了也不改这份清单。
  period_start date NOT NULL,
  period_end date NOT NULL,
  -- 期间本位币快照（多币种分组折算的目标币种）。
  base_currency char(3) NOT NULL CHECK(base_currency ~ '^[A-Z]{3}$'),
  -- ⚠️ **这里刻意不存「有几项阻断 / 几项结转」的汇总**：
  --   汇总**只有一个真相来源** —— 那四张检查项行（`finance_period_close_check`）。
  --   存两份就要同步两份，而「人工把阻断项下调为可结转」会改检查项行 ——
  --   清单是**只追加**的，改不动 ⇒ 存出来的汇总会立刻变成假数字。
  --   ⇒ 汇总一律**现算**（读检查项行）；真正需要快照的是「关账那一刻」，
  --   它记在 `finance_period_close_execution` 上（那三列关账后不可改写）。
  -- 检查开始 / 完成时刻（timestamptz）。
  started_at timestamptz NOT NULL,
  completed_at timestamptz NOT NULL,
  created_by uuid,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, period_id, run_no),
  UNIQUE(tenant_id, checklist_no),
  FOREIGN KEY(tenant_id, period_id) REFERENCES finance_voucher_period(tenant_id, id),
  CHECK(period_end >= period_start),
  CHECK(completed_at >= started_at)
);

-- ---------------------------------------------------------------------------
-- 3. finance_period_close_check —— 逐项检查结果
-- ---------------------------------------------------------------------------
--
-- 一行 = 「一类检查项在本次清单里的结果」。
--
-- 🔴 **两个分类都要留**：
--   * `rule_classification` —— **规则判定**（由查数据得到的结论，**不可改**）：
--       BLOCKING（阻断项）/ CARRYWARD（可结转未清项）。
--   * `classification` —— **最终分类**：规则判定之上允许人工下调为 CARRYWARD
--       （例外），但**必须**留下例外依据（`exception_basis` 非空，由 DB CHECK 钉死）。
--     ⇒ 蓝图加粗句「例外与结转须保留依据」落成**结构**而不是文案。
--
-- 🔴 `result` 三值：
--   PASS（检查通过）/ FAIL（查出未处理事项）/ NOT_DETERMINABLE（**不可判定**）。
--   NOT_DETERMINABLE 只用于「上游未交付、读不到」——
--   **绝不用它冒充 PASS**，也**绝不**用它悄悄放过一个真问题。
CREATE TABLE finance_period_close_check (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  checklist_id uuid NOT NULL,
  period_id uuid NOT NULL,
  -- 四类检查项（与蓝图职责一一对应，词表钉死）。
  check_code varchar(32) NOT NULL CHECK(check_code IN (
    'UNRECONCILED_DIFFERENCE','UNCARRIED_COST','UNSETTLED_PAYMENT','UNPOSTED_VOUCHER')),
  check_name varchar(100) NOT NULL,
  -- 规则判定（不可改）。
  rule_classification varchar(16) NOT NULL CHECK(rule_classification IN ('BLOCKING','CARRYWARD')),
  -- 最终分类（可下调为 CARRYWARD，但必须写例外依据）。
  classification varchar(16) NOT NULL CHECK(classification IN ('BLOCKING','CARRYWARD')),
  -- PASS / FAIL / NOT_DETERMINABLE。
  result varchar(20) NOT NULL CHECK(result IN ('PASS','FAIL','NOT_DETERMINABLE')),
  -- **分类依据**（落库）：为什么判成阻断项 / 可结转未清项。
  classification_basis varchar(1000) NOT NULL,
  -- 例外依据（人工下调分类时**必填**）。
  exception_basis varchar(1000) NOT NULL DEFAULT '',
  exception_by uuid,
  exception_at timestamptz,
  -- 未处理事项条数（具体数字，不是布尔值）。
  item_count int NOT NULL DEFAULT 0 CHECK(item_count >= 0),
  -- 数据源说明（真的查了哪张表 / 哪个上游模块）。
  data_source varchar(300) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, checklist_id, check_code),
  FOREIGN KEY(tenant_id, checklist_id)
    REFERENCES finance_period_close_checklist(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, period_id) REFERENCES finance_voucher_period(tenant_id, id),
  -- 依据不能为空：没有依据的分类等于没有分类。
  CHECK(btrim(classification_basis) <> ''),
  -- 例外必须带依据：人工把阻断项下调成可结转时，例外依据必填。
  CONSTRAINT finance_period_close_check_exception_ck CHECK (
    classification = rule_classification
    OR (classification = 'CARRYWARD' AND btrim(exception_basis) <> '' AND exception_by IS NOT NULL
        AND exception_at IS NOT NULL)
  ),
  -- 不可判定 ⇒ 一定归入可结转未清项（**不得**把关账卡死在永远满足不了的条件上）。
  CONSTRAINT finance_period_close_check_not_determinable_ck CHECK (
    result <> 'NOT_DETERMINABLE' OR classification = 'CARRYWARD'
  )
);

-- ---------------------------------------------------------------------------
-- 4. finance_period_close_check_item —— 可钻取明细
-- ---------------------------------------------------------------------------
--
-- 一行 = 「一个未处理事项」（一张草稿凭证 / 一笔未核销应收 / 一个未到账结算 …）。
--
-- 🔴 **source_id 是跨模块的逻辑引用，不建跨模块外键**：
--   凭证属 12.5.2.1、应收属 12.1.1.1、收款属 12.2.1.1、结算属 12.3.1.1、
--   成本批次属 12.4.1.2 —— 它们的表结构会演进，本模块不该被别人的迁移卡住。
--   （本模块自己的表之间的引用一律用复合外键 + tenant_id。）
--
-- 🔴 **金额按币种存**：一行一个币种 ⇒ 汇总时天然「按币种分组」，
--   不需要靠代码自觉避免跨币种求和。
CREATE TABLE finance_period_close_check_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  checklist_id uuid NOT NULL,
  check_id uuid NOT NULL,
  period_id uuid NOT NULL,
  -- 上游归属模块（如 '12.5.2.1'）。
  source_module varchar(16) NOT NULL,
  -- 来源事实类型：VOUCHER / COST_RUN / RECEIVABLE / RECEIPT / SETTLEMENT。
  source_type varchar(24) NOT NULL CHECK(source_type IN (
    'VOUCHER','COST_RUN','RECEIVABLE','RECEIPT','SETTLEMENT')),
  source_id uuid NOT NULL,
  -- 来源单号快照（来源侧后来改号 / 删号，本模块仍能自证「当时是哪一单」）。
  source_no varchar(64) NOT NULL DEFAULT '',
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 未处理金额（该事项的未清余额 / 借方合计 / 缺口成本额）。
  amount numeric(19,4) NOT NULL DEFAULT 0,
  -- 数量口径（如成本缺口数量）；数量一律 NUMERIC(19,6)，禁浮点。
  quantity numeric(19,6),
  -- 该事项为什么算「未处理」（说人话）。
  reason varchar(500) NOT NULL,
  -- 规则级分类（与所属检查项的 rule_classification 一致）。
  classification varchar(16) NOT NULL CHECK(classification IN ('BLOCKING','CARRYWARD')),
  -- 到期日（应收有；其它来源为空）。
  due_date date,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, check_id)
    REFERENCES finance_period_close_check(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, checklist_id)
    REFERENCES finance_period_close_checklist(tenant_id, id) ON DELETE CASCADE,
  CHECK(btrim(reason) <> '')
);

-- ---------------------------------------------------------------------------
-- 5. finance_period_close_check_amount —— 按币种分组的金额
-- ---------------------------------------------------------------------------
--
-- 🔴 **这张表的存在就是为了让「跨币种求和」在结构上不成立**：
--   唯一键 (tenant_id, check_id, currency) ⇒ 一个检查项一个币种一行。
--   想知道「这个检查项一共多少钱」必须**按币种**回答。
--
-- ⚠️ 金额与明细表是**两份口径**：
--   * 明细表（check_item）是逐条事实（可钻取）；
--   * 本表是按币种的**合计**（列表页一眼看到）。
--   服务层在同一次检查里从明细聚合出来，两者必须一致（集成测试会断言）。
CREATE TABLE finance_period_close_check_amount (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  checklist_id uuid NOT NULL,
  check_id uuid NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 该币种下的未处理金额合计（**只在本币种内求和**）。
  amount numeric(19,4) NOT NULL DEFAULT 0,
  -- 该币种下的事项条数。
  item_count int NOT NULL DEFAULT 0 CHECK(item_count >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, check_id, currency),
  FOREIGN KEY(tenant_id, check_id)
    REFERENCES finance_period_close_check(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, checklist_id)
    REFERENCES finance_period_close_checklist(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 6. finance_period_close_execution —— 关账执行记录（关账完成记录）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「一次关账执行」。
--
-- 🔴 **关账完成记录是举证凭据**：它把「按哪份清单关的（checklist_id）、
--   当时有几项阻断 / 几项结转（快照）、结转依据是什么（落库）、
--   审批实例是哪一条（可钻取）」钉死。
--
-- ⚠️ 状态机（服务层 + 触发器双层）：
--   PENDING_APPROVAL（已提交审批）→ APPROVED（审批通过，待执行）
--                              → REJECTED（审批被驳回，期间**仍然开放**）
--   APPROVED → CLOSED（关账已执行，期间**已锁定、禁止过账**）
CREATE TABLE finance_period_close_execution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  period_id uuid NOT NULL,
  checklist_id uuid NOT NULL,
  -- 关账单号（如 'PC-2026-10-0001'）。
  close_no varchar(48) NOT NULL,
  status varchar(20) NOT NULL CHECK(status IN (
    'PENDING_APPROVAL','APPROVED','REJECTED','CLOSED')),
  -- 当时的分类快照（清单会被改吗？不会，但快照让关账记录自证）。
  blocking_count int NOT NULL DEFAULT 0 CHECK(blocking_count >= 0),
  carryforward_count int NOT NULL DEFAULT 0 CHECK(carryforward_count >= 0),
  not_determinable_count int NOT NULL DEFAULT 0 CHECK(not_determinable_count >= 0),
  -- **结转依据**（落库）：蓝图「例外与结转须保留依据」。
  carryforward_basis varchar(2000) NOT NULL DEFAULT '',
  -- 关账原因（写给审计看的一句话）。
  reason varchar(500) NOT NULL DEFAULT '',
  -- 审批：17 审批（identity）的实例（跨服务 ⇒ 无 FK）。
  approval_instance_id uuid,
  approval_instance_no varchar(48) NOT NULL DEFAULT '',
  approval_status varchar(24) NOT NULL DEFAULT '',
  -- **期间锁定**：关账执行后为 true —— 「该期间已锁定、禁止过账」。
  -- 🔴 由 V357 的 `guard_finance_voucher_post_period` 真正拦住过账；
  --    本列只是**如实反映**那条守卫的语义，不重复实现它。
  period_locked boolean NOT NULL DEFAULT false,
  lock_note varchar(500) NOT NULL DEFAULT '',
  closed_at timestamptz,
  closed_by uuid,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, close_no),
  FOREIGN KEY(tenant_id, period_id) REFERENCES finance_voucher_period(tenant_id, id),
  FOREIGN KEY(tenant_id, checklist_id) REFERENCES finance_period_close_checklist(tenant_id, id),
  -- 有结转项 ⇒ 必须有结转依据。
  CONSTRAINT finance_period_close_execution_carryforward_ck CHECK (
    carryforward_count = 0 OR btrim(carryforward_basis) <> ''
  ),
  -- 关账完成 ⇒ 必须有锁定标记 + 关账人 / 时刻。
  CONSTRAINT finance_period_close_execution_closed_ck CHECK (
    status <> 'CLOSED'
    OR (period_locked AND closed_at IS NOT NULL AND closed_by IS NOT NULL
        AND btrim(lock_note) <> '')
  ),
  -- 未关账 ⇒ 不许有锁定标记。
  CONSTRAINT finance_period_close_execution_not_locked_ck CHECK (
    status = 'CLOSED' OR NOT period_locked
  )
);

-- ---------------------------------------------------------------------------
-- 7. finance_period_close_approval —— 审批链路镜像（identity 的本地镜像）
-- ---------------------------------------------------------------------------
--
-- 🔴 **这张表不是审批引擎，是镜像**：真正的审批实例 / 节点 / 动作 / 审批人
--   **全部住在 identity 服务**（17 审批，已交付）。本表存的是「从 identity 读回来的
--   那条链路」⇒ 关账记录里能钻取到审批实例与逐步动作。
--
-- 幂等：按 (approval_instance_id, action_id) 去重 ⇒ 反复同步不灌重复行。
CREATE TABLE finance_period_close_approval (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  execution_id uuid NOT NULL,
  period_id uuid NOT NULL,
  -- identity 侧的审批实例（跨服务 ⇒ 无 FK）。
  approval_instance_id uuid NOT NULL,
  approval_instance_no varchar(48) NOT NULL DEFAULT '',
  -- identity 侧的审批动作 id（提交那条没有 ⇒ 为 NULL）。
  action_id uuid,
  action varchar(16) NOT NULL CHECK(action IN ('SUBMIT','APPROVE','REJECT','TRANSFER')),
  node_key varchar(32) NOT NULL DEFAULT '',
  node_name varchar(100) NOT NULL DEFAULT '',
  node_order int NOT NULL DEFAULT 0,
  actor_id uuid,
  actor_name varchar(160) NOT NULL DEFAULT '',
  comment varchar(500) NOT NULL DEFAULT '',
  decided_at timestamptz NOT NULL,
  dwell_seconds bigint NOT NULL DEFAULT 0,
  -- 镜像来源系统（恒为 'finance'：本模块是提交方）。
  source_system varchar(32) NOT NULL DEFAULT 'finance',
  synced_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, execution_id)
    REFERENCES finance_period_close_execution(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, period_id) REFERENCES finance_voucher_period(tenant_id, id)
);

-- ===========================================================================
-- 第二节：索引
-- ===========================================================================

-- 清单按期间排（同一期间按 run_no 倒序）。
CREATE INDEX finance_period_close_checklist_by_period
  ON finance_period_close_checklist(tenant_id, period_id, run_no DESC);
-- 清单按创建时刻排（列表页默认排序）。
CREATE INDEX finance_period_close_checklist_by_created
  ON finance_period_close_checklist(tenant_id, created_at DESC);
-- 检查项按清单排。
CREATE INDEX finance_period_close_check_by_checklist
  ON finance_period_close_check(tenant_id, checklist_id, check_code);
-- 明细按检查项排（钻取列表）。
CREATE INDEX finance_period_close_check_item_by_check
  ON finance_period_close_check_item(tenant_id, check_id, currency, id);
-- 明细按来源事实排（从一个来源单反查它影响了哪次关账）。
CREATE INDEX finance_period_close_check_item_by_source
  ON finance_period_close_check_item(tenant_id, source_type, source_id);
-- 金额按检查项 + 币种（唯一键已覆盖，这里补一个按清单排的）。
CREATE INDEX finance_period_close_check_amount_by_checklist
  ON finance_period_close_check_amount(tenant_id, checklist_id, check_id);
-- 执行记录按期间排。
CREATE INDEX finance_period_close_execution_by_period
  ON finance_period_close_execution(tenant_id, period_id, created_at DESC);
-- 执行记录按状态排（待办：等审批 / 已关账）。
CREATE INDEX finance_period_close_execution_by_status
  ON finance_period_close_execution(tenant_id, status, created_at DESC);
-- 审批镜像按执行记录排。
CREATE INDEX finance_period_close_approval_by_execution
  ON finance_period_close_approval(tenant_id, execution_id, decided_at, id);
-- 同一审批动作只镜像一次（反复同步不灌重复行）。
CREATE UNIQUE INDEX uq_finance_period_close_approval_action
  ON finance_period_close_approval(tenant_id, approval_instance_id, action_id)
  WHERE action_id IS NOT NULL;

-- ===========================================================================
-- 第三节：DB 层守卫（服务层与 DB 各判一次）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 清单 / 明细 / 金额 / 审批镜像：**只能追加**
-- ---------------------------------------------------------------------------
--
-- 🔴 为什么清单不能改：关账是**期末事实**，事后要能回答
--   「这期关账时看到的未处理事项到底是哪几条、金额多少」。
--   清单一旦可改，那份证据就不存在了 —— 与 12.5.1.2 的
--   `guard_finance_accounting_period_event_append_only` 同一手法。
--
-- ⚠️ **变量名不能与列名同名**（PL/pgSQL 会报 column reference is ambiguous）。
CREATE OR REPLACE FUNCTION guard_finance_period_close_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% 是举证凭据，只允许追加（不允许 UPDATE / DELETE）', TG_TABLE_NAME
    USING ERRCODE = '42501';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_period_close_checklist_append_only
  BEFORE UPDATE OR DELETE ON finance_period_close_checklist
  FOR EACH ROW EXECUTE FUNCTION guard_finance_period_close_append_only();

CREATE TRIGGER finance_period_close_check_item_append_only
  BEFORE UPDATE OR DELETE ON finance_period_close_check_item
  FOR EACH ROW EXECUTE FUNCTION guard_finance_period_close_append_only();

CREATE TRIGGER finance_period_close_check_amount_append_only
  BEFORE UPDATE OR DELETE ON finance_period_close_check_amount
  FOR EACH ROW EXECUTE FUNCTION guard_finance_period_close_append_only();

CREATE TRIGGER finance_period_close_approval_append_only
  BEFORE UPDATE OR DELETE ON finance_period_close_approval
  FOR EACH ROW EXECUTE FUNCTION guard_finance_period_close_append_only();

-- 检查项本身允许 UPDATE（人工下调分类），但**不可删**。
CREATE TRIGGER finance_period_close_check_no_delete
  BEFORE DELETE ON finance_period_close_check
  FOR EACH ROW EXECUTE FUNCTION guard_finance_period_close_append_only();

-- ---------------------------------------------------------------------------
-- (b) 检查项：**只有分类可以被人工下调，且必须带例外依据**
-- ---------------------------------------------------------------------------
--
-- 规则判定（rule_classification / result / item_count / classification_basis /
-- 数据源 / 检查项编码）**一经落库不可改** —— 改了就是「把查出来的账改好看」。
--
-- 允许的唯一下调路径：BLOCKING → CARRYWARD（写例外依据、例外人、例外时刻）。
-- ⚠️ 阻断项**不能**被「升级」回阻断（那没有意义），也不能从 CARRYWARD 再改成 BLOCKING
--   （那会让关账记录与清单对不上）。
CREATE OR REPLACE FUNCTION guard_finance_period_close_check_classification() RETURNS trigger AS $$
BEGIN
  IF NEW.checklist_id IS DISTINCT FROM OLD.checklist_id
     OR NEW.period_id IS DISTINCT FROM OLD.period_id
     OR NEW.check_code IS DISTINCT FROM OLD.check_code
     OR NEW.check_name IS DISTINCT FROM OLD.check_name
     OR NEW.rule_classification IS DISTINCT FROM OLD.rule_classification
     OR NEW.result IS DISTINCT FROM OLD.result
     OR NEW.item_count IS DISTINCT FROM OLD.item_count
     OR NEW.classification_basis IS DISTINCT FROM OLD.classification_basis
     OR NEW.data_source IS DISTINCT FROM OLD.data_source THEN
    RAISE EXCEPTION '检查项的判定内容不可修改（check_code=% / result=%）：要重新检查请重跑一次检查清单',
      OLD.check_code, OLD.result
      USING ERRCODE = '42501';
  END IF;

  IF NEW.classification IS DISTINCT FROM OLD.classification THEN
    IF OLD.classification <> 'BLOCKING' OR NEW.classification <> 'CARRYWARD' THEN
      RAISE EXCEPTION '分类只允许从阻断项下调为可结转未清项（当前 % → %）',
        OLD.classification, NEW.classification
        USING ERRCODE = '42501';
    END IF;
    IF btrim(NEW.exception_basis) = '' THEN
      RAISE EXCEPTION '把阻断项下调为可结转未清项必须写明例外依据'
        USING ERRCODE = '42501';
    END IF;
    IF NEW.exception_by IS NULL OR NEW.exception_at IS NULL THEN
      RAISE EXCEPTION '例外必须留下例外人与例外时刻'
        USING ERRCODE = '42501';
    END IF;
  ELSE
    -- 分类没变 ⇒ 例外依据也不许被偷偷改写（避免「例外依据」名存实亡）。
    IF NEW.exception_basis IS DISTINCT FROM OLD.exception_basis
       OR NEW.exception_by IS DISTINCT FROM OLD.exception_by
       OR NEW.exception_at IS DISTINCT FROM OLD.exception_at THEN
      RAISE EXCEPTION '例外依据不可单独改写（要改请重新调整分类）'
        USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_period_close_check_classification
  BEFORE UPDATE ON finance_period_close_check
  FOR EACH ROW EXECUTE FUNCTION guard_finance_period_close_check_classification();

-- ---------------------------------------------------------------------------
-- (c) 关账执行记录：状态机 + 「锁定」只随关账发生
-- ---------------------------------------------------------------------------
--
-- 允许的路径：
--   PENDING_APPROVAL → APPROVED / REJECTED
--   APPROVED → CLOSED
-- 其余路径（复活已驳回的、乱跳的）一律拒绝。
--
-- 🔴 `period_locked` 只在 status='CLOSED' 时才允许为 true ——
--   「审批中」就说「期间已锁定」是撒谎（期间这时仍然开放、仍然能过账）。
CREATE OR REPLACE FUNCTION guard_finance_period_close_execution_state() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('APPROVED','REJECTED','CLOSED'))
      OR (OLD.status = 'APPROVED' AND NEW.status = 'CLOSED')
    ) THEN
      RAISE EXCEPTION '关账状态不允许从 % 变到 %', OLD.status, NEW.status
        USING ERRCODE = '42501';
    END IF;
  END IF;

  IF NEW.period_locked AND (OLD.period_locked IS DISTINCT FROM NEW.period_locked) THEN
    IF NEW.status <> 'CLOSED' THEN
      RAISE EXCEPTION '只有关账执行完成（status=CLOSED）才能标记期间已锁定'
        USING ERRCODE = '42501';
    END IF;
    IF NEW.closed_at IS NULL OR NEW.closed_by IS NULL THEN
      RAISE EXCEPTION '标记期间已锁定必须写明关账人与关账时刻'
        USING ERRCODE = '42501';
    END IF;
  END IF;

  -- 归因信息（阻断 / 结转统计、结转依据）不可在关账后被改写。
  IF OLD.status = 'CLOSED' THEN
    IF NEW.blocking_count IS DISTINCT FROM OLD.blocking_count
       OR NEW.carryforward_count IS DISTINCT FROM OLD.carryforward_count
       OR NEW.not_determinable_count IS DISTINCT FROM OLD.not_determinable_count
       OR NEW.carryforward_basis IS DISTINCT FROM OLD.carryforward_basis
       OR NEW.checklist_id IS DISTINCT FROM OLD.checklist_id
       OR NEW.period_id IS DISTINCT FROM OLD.period_id THEN
      RAISE EXCEPTION '关账完成后的归因信息不可改写（关账记录是举证凭据）'
        USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_period_close_execution_state
  BEFORE UPDATE ON finance_period_close_execution
  FOR EACH ROW EXECUTE FUNCTION guard_finance_period_close_execution_state();

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_period_close_checklist','finance_period_close_check',
    'finance_period_close_check_item','finance_period_close_check_amount',
    'finance_period_close_execution','finance_period_close_approval'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_period_close_checklist, finance_period_close_check,
  finance_period_close_check_item, finance_period_close_check_amount,
  finance_period_close_execution, finance_period_close_approval TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_period_close_checklist, finance_period_close_check,
  finance_period_close_check_item, finance_period_close_check_amount,
  finance_period_close_execution, finance_period_close_approval TO starcon_app;

-- 「授权在前、收回在后」：
--   * 清单 / 明细 / 金额 / 审批镜像是**举证凭据** ⇒ 收回 UPDATE / DELETE。
--   * 检查项允许 UPDATE（人工下调分类），但**不可删** ⇒ 收回 DELETE。
--   * 执行记录允许 UPDATE（审批结论回流），但**不可删** ⇒ 收回 DELETE。
REVOKE UPDATE,DELETE ON finance_period_close_checklist FROM finance_app;
REVOKE UPDATE,DELETE ON finance_period_close_check_item FROM finance_app;
REVOKE UPDATE,DELETE ON finance_period_close_check_amount FROM finance_app;
REVOKE UPDATE,DELETE ON finance_period_close_approval FROM finance_app;
REVOKE DELETE ON finance_period_close_check FROM finance_app;
REVOKE DELETE ON finance_period_close_execution FROM finance_app;

-- ===========================================================================
-- 第五节：交接面（写给 12.5.2.2 报表 / 后续车道）
-- ===========================================================================
--
-- ① **12.5.1.2 会计期间（已交付）**：本模块**读** `finance_voucher_period`，
--    关账动作走它的 `AccountingPeriodService.close(...)` ——
--    本模块**没有**自己的期间状态机、**没有**平行的期间表、
--    **没有**重开流程（重开属 12.5.1.2）。
--
-- ② **12.5.2.1 凭证（已交付）**：「已关闭期间禁止过账」由 V357 的
--    `guard_finance_voucher_post_period` 触发器生效 ——
--    本模块**不重复实现、不改语义**，只在关账结果里如实反映
--    「该期间已锁定、禁止过账」（`finance_period_close_execution.period_locked`）。
--
-- ③ **12.3.2.1 对账任务 / 12.3.2.2 差异处理（未交付）**：
--    「未对账差异」这一检查项**如实报「上游未交付 / 不可判定」**
--    （result='NOT_DETERMINABLE'，classification='CARRYWARD'），
--    **不得**假装通过、**不得**静默当成通过。
--
-- ④ **12.5.2.2 财务报表（本批同批在途，迁移 V360）**：它是本模块的**下游**
--    （关账完成后才出报表）⇒ 本模块**不依赖**它、也**不读**它的表。
--
-- ===========================================================================
-- 第六节：金额与时间口径（写给运维与下游）
-- ===========================================================================
--
--  * 期间区间是**日期**概念：period_start / period_end / due_date 一律 **date**。
--  * 绝对时刻（检查完成 / 关账 / 审批动作）一律 timestamptz：
--    Java 侧绑 OffsetDateTime + values 位置写 `?::timestamptz`
--    （用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--  * 金额 numeric(19,4)、数量 numeric(19,6)、**禁浮点**。
--  * **金额按币种分组**（`finance_period_close_check_amount` 唯一键含 currency）
--    ⇒ 结构上不存在「跨币种求和」这一格。
--
-- ===========================================================================
