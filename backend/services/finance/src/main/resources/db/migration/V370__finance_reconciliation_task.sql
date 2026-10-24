-- ===========================================================================
-- V370 · finance_reconciliation_task* —— 12.3.2.1 对账任务
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.3.2.1 对账任务）原文：
--
--   - **职责**：发起与执行对账任务：选择对账范围（店铺 + 周期 + 账户）、运行自动匹配、
--     输出匹配结果与差异。
--   - **输入**：对账范围；我方账数据（结算、应收、应付、银行流水）；平台账单。
--   - **输出**：匹配结果（一对一、一对多、多对一）+ 差异清单。
--   - **关系**：上游 = 12.3.1.1、12.7.1.2；下游 = 12.3.2.2。
--     **不允许「金额相同即匹配」**（实体目录 finance 段硬约束）——金额相同只是候选，
--     必须结合订单号、日期、类型。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么（上游/下游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **不改 12.7.1.2 的九张表。** `finance_bank_recon_*` 是银行对账的写入所有权。
--     本模块要它的数据走**授权只读 API**（`GET /finance/bank-reconciliation/**`），
--     跨库 SQL / FK / 视图一律禁止 ⇒ 本迁移里**零** `finance_bank_recon_*` 引用
--     （连触发器校验都不做：连校验都是跨模块耦合）。
--
--   * **不改 12.3.1.1 / 12.1.1.1 / 12.2.2.1 的任何表。** 平台账单侧读
--     `finance_settlement` + `finance_settlement_line`，我方账侧读
--     `finance_receivable_line` + `finance_payment_order_line`
--     （**同服务同库** ⇒ 合法读路径）。本模块**零写入**上游任何一张表。
--
--   * **不建「匹配规则管理」表。** 规则**配置**归 12.3.2.3（未交付）。
--     本模块只负责「运行自动匹配」这一动作，匹配参数是**代码内受控词表 + 接口入参**
--     （`date_window_days` / `amount_tolerance`），随任务**快照**进任务行。
--
--   * **不做差异处理。** 认领 / 调整 / 核销归 12.3.2.2（未交付）。
--     本模块只**输出差异清单**（差异项 + 差额 + 归属）。差异行的 `status`
--     预留了下游要用的词表，但**本模块只写 'OPEN'**。
--
--   * **不做跨币种折算。** 账户单一币种（12.7.1.1 硬约束）⇒ 差异清单**按币种分组**，
--     **绝不**做跨币种差额合计；汇率不可得就如实报「汇率不可得」，
--     **不按 1:1 兜底**（`difference_amount` 恒为 NULL 而不是写 0）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_reconciliation_task        —— 对账任务（范围 + 执行结果摘要）
--   finance_reconciliation_match        —— 匹配结果（平台账单行 ⇄ 我方账行，**多行**）
--   finance_reconciliation_difference   —— 差异清单（差异项 + 差额 + 归属）
--   finance_reconciliation_event        —— 事件流（append-only 举证）
--
--   四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 三条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **不允许「金额相同即匹配」钉在 DB 层**
--      `finance_reconciliation_match.matched_dimensions` 是**受控词表数组**，
--      CHECK 规定它必须**至少包含两个** {REFERENCE, DATE, TYPE} 里的维度：
--        CONSTRAINT finance_reconciliation_match_dimensions_ck CHECK (
--          matched_dimensions <@ ARRAY['REFERENCE','DATE','TYPE','AMOUNT']::text[]
--          AND cardinality(matched_dimensions) >= 2
--          AND finance_reconciliation_dimension_count(matched_dimensions) >= 2
--        )
--      ⇒ 「只看金额就配成一对」在**结构上**写不出来（绕过服务层直连 SQL 也一样）。
--      `dimension_count` 再钉一道 `>= 2`（两列互为冗余，但都来自同一次判定）。
--
--      ⚠️ Postgres **没有**数组交集运算符（只有 `<@` 包含与 `&&` 相交），
--      所以「数组里有几个 {R,D,T}」用一个 IMMUTABLE 的 SQL 函数算 —— CHECK 里
--      不许子查询，但可以调不可变函数。
--
--   2. **一对多 / 多对一落成多行匹配结果**
--      匹配结果**一行 = 一个 (平台账单行, 我方账行) 对**，
--      同一组共享 `group_key` / `group_no` / `shape`：
--        ONE_TO_ONE   1 平台行 ⇄ 1 我方行
--        ONE_TO_MANY  1 平台行 ⇄ N 我方行（拆单核销：平台一笔，我方拆成几笔）
--        MANY_TO_ONE  N 平台行 ⇄ 1 我方行（平台拆单：我方一笔，平台拆成几笔）
--        MANY_TO_MANY N 平台行 ⇄ N 我方行（双方都拆）
--      组级金额（`group_external_amount` / `group_book_amount` / `group_amount_difference`）
--      落在**每一行**上 ⇒ 页面不必自己求和就知道这组平不平。
--      ⚠️ **不许**把多张单据揉成一条：主键是 (task, group, 平台行, 我方行)，
--      多张单据对一张 ⇒ 多行。
--
--   3. **执行结果是一次性事实 ⇒ 不可改写**
--      任务从 DRAFT 推进到 MATCHED 之后：
--        · 任务行的**范围**字段被触发器冻结（`guard_finance_reconciliation_task_frozen`）；
--        · 匹配结果**完全 append-only**（运行角色 UPDATE/DELETE 已收回 + 触发器双保险）；
--        · 差异清单只允许推进 `status`（认领/调整/核销归 12.3.2.2），其余字段冻结；
--        · 事件流 append-only。
--      **重跑 = 新建一个任务**，不覆盖历史 ⇒ 任务号 UNIQUE(tenant_id, task_no)。
--
-- ---------------------------------------------------------------------------
-- 3. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)；数量（本模块无数量口径，无 quantity 列）。**全链路禁浮点**。
--   * 币种 char(3) + CHECK 正则。
--   * 匹配结果与差异行的金额**恒正**（方向由 `direction` 列表达）⇒
--     不出现「负数代表支出」这种二义性。
--   * `difference_amount` **有符号**：我方账合计 − 平台账单合计
--     （> 0 = 我方账多记；< 0 = 平台账单多记）。跨币种时**恒为 NULL**。
--
-- ===========================================================================

-- ===========================================================================
-- 0.5 一个 IMMUTABLE 辅助函数（必须在用到它的 CHECK **之前**建好）
-- ===========================================================================
--
-- 数一数维度数组里有几个 {REFERENCE, DATE, TYPE}。
--
-- ⚠️ Postgres **没有**数组交集运算符（`<@` 是包含、`&&` 是相交），
-- 而 CHECK 约束里**不许子查询** ⇒ 用一个 IMMUTABLE 的 SQL 函数。
CREATE OR REPLACE FUNCTION finance_reconciliation_dimension_count(dims text[])
RETURNS int AS $$
  SELECT count(*)::int
    FROM unnest(coalesce(dims, ARRAY[]::text[])) AS d
   WHERE d IN ('REFERENCE','DATE','TYPE');
$$ LANGUAGE sql IMMUTABLE;

-- ---------------------------------------------------------------------------
-- 1. finance_reconciliation_task —— 对账任务
-- ---------------------------------------------------------------------------
--
-- 一次对账 = 一个资金账户 × 一个周期（× 一个店铺，可空 = 全店铺）
--            × 一组「我方账来源」。
CREATE TABLE finance_reconciliation_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 任务号（租户内唯一、可读）：RT-20261024-a1b2c3d4。
  task_no varchar(48) NOT NULL CHECK(btrim(task_no) <> ''),

  -- ---- 对账账户（12.7.1.1，同服务同库 ⇒ 可建复合外键）----
  account_id uuid NOT NULL,
  -- 账户编号 / 名称 / 法人是**快照**（账户改名或换绑法人后，历史任务仍读得出当时的口径）。
  account_no varchar(48) NOT NULL,
  account_name varchar(120) NOT NULL,
  -- 币种：继承账户（单账户单币种 ⇒ 一次对账只有一个币种）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  legal_entity_id uuid NOT NULL,
  legal_entity_code varchar(48) NOT NULL,
  legal_entity_name varchar(200) NOT NULL,

  -- ---- 店铺（2 渠道与店铺；跨服务 ⇒ **无跨库 FK**，服务端按授权范围过滤）----
  -- 空 = 本租户的全部店铺（按账户的法人 + 周期过滤）。
  shop_id uuid,
  shop_code varchar(64) NOT NULL DEFAULT '',
  shop_name varchar(160) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',

  -- ---- 周期（date：周期是日历天，不是某一瞬间）----
  period_start date NOT NULL,
  period_end date NOT NULL,
  CHECK(period_end >= period_start),

  -- ---- 我方账来源（受控词表的非空子集）----
  --   RECEIVABLE     12.1.1.1 应收明细行
  --   PAYMENT_ORDER  12.2.2.1 付款单明细行
  --   BANK_STATEMENT 12.7.1.2 银行/支付平台流水行（**经授权只读 API**）
  book_sources text[] NOT NULL,
  CONSTRAINT finance_reconciliation_task_book_sources_ck CHECK (
    cardinality(book_sources) BETWEEN 1 AND 3
    AND book_sources <@ ARRAY['RECEIVABLE','PAYMENT_ORDER','BANK_STATEMENT']::text[]
  ),

  -- ---- 匹配参数（**代码内受控词表 + 接口入参**，不是规则管理表）----
  -- 日期窗口上限 31 天：窗口放到几百天等于用「金额相同」冒充匹配。
  date_window_days int NOT NULL DEFAULT 7 CHECK(date_window_days BETWEEN 1 AND 31),
  -- 金额容差默认 0；**默认 0** 是刻意的 —— 容差大了就变成「金额相同即匹配」的变体。
  amount_tolerance numeric(19,4) NOT NULL DEFAULT 0 CHECK(amount_tolerance >= 0),

  -- 状态机（**只能向前**）：
  --   DRAFT   已建任务，还没跑匹配
  --   MATCHED 已完成匹配（**终态**：本模块不再改写结果）
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','MATCHED')),

  -- ---- 计数（由匹配动作维护，运营字段）----
  external_line_count int NOT NULL DEFAULT 0 CHECK(external_line_count >= 0),
  matched_group_count int NOT NULL DEFAULT 0 CHECK(matched_group_count >= 0),
  matched_line_count int NOT NULL DEFAULT 0 CHECK(matched_line_count >= 0),
  difference_count int NOT NULL DEFAULT 0 CHECK(difference_count >= 0),

  -- ---- 执行留痕（状态与留痕必须自洽）----
  matched_at timestamptz,
  matched_by uuid,

  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, task_no),
  FOREIGN KEY(tenant_id, account_id) REFERENCES finance_fund_account(tenant_id, id),

  -- 🔴 状态与留痕必须自洽：MATCHED ⇒ 有执行人与执行时刻；
  --    DRAFT ⇒ 这两个字段一律为空（**执行之前不许预填**）。
  CONSTRAINT finance_reconciliation_task_state_consistent CHECK(
    (status = 'MATCHED' AND matched_at IS NOT NULL AND matched_by IS NOT NULL)
    OR (status = 'DRAFT' AND matched_at IS NULL AND matched_by IS NULL)
  )
);

-- 列表默认排序：周期倒序；账户 / 状态 / 店铺各自是常用筛选维度。
CREATE INDEX finance_reconciliation_task_by_account
  ON finance_reconciliation_task(tenant_id, account_id, period_start DESC);
CREATE INDEX finance_reconciliation_task_by_status
  ON finance_reconciliation_task(tenant_id, status);
CREATE INDEX finance_reconciliation_task_by_period
  ON finance_reconciliation_task(tenant_id, period_end DESC, task_no DESC);
CREATE INDEX finance_reconciliation_task_by_shop
  ON finance_reconciliation_task(tenant_id, shop_id, period_start DESC);

-- ---------------------------------------------------------------------------
-- 2. finance_reconciliation_match —— 匹配结果（append-only）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「平台账单的一行 ⇄ 我方账的一行」配成了一对。
--
-- ⚠️ **一对多 / 多对一落成多行**（不是把多张单据揉成一条）：
--   同一组共享 group_key / group_no / shape；组内每一对各占一行。
CREATE TABLE finance_reconciliation_match (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- ---- 分组（一次执行内，一组 = 一个匹配簇）----
  group_key uuid NOT NULL,
  group_no int NOT NULL CHECK(group_no > 0),
  -- 匹配形态（**一对一 / 一对多 / 多对一** 明确区分）。
  shape text NOT NULL
    CHECK(shape IN ('ONE_TO_ONE','ONE_TO_MANY','MANY_TO_ONE','MANY_TO_MANY')),
  -- 我方账来源
  book_source text NOT NULL
    CHECK(book_source IN ('RECEIVABLE','PAYMENT_ORDER','BANK_STATEMENT')),

  -- ---- 平台账单侧（快照）----
  -- SETTLEMENT       12.3.1.1 结算单头（平台结算单 = 平台账单）
  -- SETTLEMENT_LINE  12.3.1.2 结算明细行（平台交易明细）
  external_kind text NOT NULL CHECK(external_kind IN ('SETTLEMENT','SETTLEMENT_LINE')),
  external_line_id uuid NOT NULL,
  external_no varchar(64) NOT NULL DEFAULT '',
  external_reference varchar(160) NOT NULL DEFAULT '',
  external_type varchar(32) NOT NULL DEFAULT '',
  external_occurred_on date,
  external_amount numeric(19,4) NOT NULL CHECK(external_amount > 0),

  -- ---- 我方账侧（快照）----
  book_line_id uuid NOT NULL,
  book_no varchar(64) NOT NULL DEFAULT '',
  book_reference varchar(160) NOT NULL DEFAULT '',
  book_type varchar(32) NOT NULL DEFAULT '',
  book_occurred_on date,
  book_amount numeric(19,4) NOT NULL CHECK(book_amount > 0),

  -- ---- 币种与方向（一次对账只有一个币种 ⇒ 两侧必然同币）----
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  direction text NOT NULL CHECK(direction IN ('IN','OUT')),

  -- ---- 组级金额（组内合计相等 ⇒ 差额恒为 0；落在每一行上，页面不必自己求和）----
  group_external_amount numeric(19,4) NOT NULL CHECK(group_external_amount > 0),
  group_book_amount numeric(19,4) NOT NULL CHECK(group_book_amount > 0),
  group_amount_difference numeric(19,4) NOT NULL DEFAULT 0,

  -- ---- 匹配依据（人话 + 机器可读）----
  -- 🔴 **卷 12 硬约束钉在这里**：至少两个独立维度（订单号 / 日期 / 类型）。
  matched_dimensions text[] NOT NULL,
  CONSTRAINT finance_reconciliation_match_dimensions_ck CHECK (
    matched_dimensions <@ ARRAY['REFERENCE','DATE','TYPE','AMOUNT']::text[]
    AND cardinality(matched_dimensions) >= 2
    AND finance_reconciliation_dimension_count(matched_dimensions) >= 2
  ),
  dimension_count int NOT NULL CHECK(dimension_count >= 2),
  -- 命中的维度说明（人话）：如「订单号相等 + 交易类型一致 + 金额相等」。
  match_basis varchar(500) NOT NULL DEFAULT '',
  -- 两侧日期相差的天数。
  day_difference int NOT NULL DEFAULT 0,

  matched_by uuid NOT NULL,
  matched_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 一组内 (平台行, 我方行) 不重复 ⇒ 多张单据对一张是**多行**，不是一行多值。
  UNIQUE(tenant_id, task_id, group_key, external_line_id, book_line_id),
  FOREIGN KEY(tenant_id, task_id) REFERENCES finance_reconciliation_task(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_reconciliation_match_by_task
  ON finance_reconciliation_match(tenant_id, task_id, group_no, external_line_id);
-- 按形态筛（一对一 / 一对多 / 多对一 是页面的主视角）。
CREATE INDEX finance_reconciliation_match_by_shape
  ON finance_reconciliation_match(tenant_id, task_id, shape);
-- 按我方账来源筛。
CREATE INDEX finance_reconciliation_match_by_source
  ON finance_reconciliation_match(tenant_id, task_id, book_source);

-- ---------------------------------------------------------------------------
-- 3. finance_reconciliation_difference —— 差异清单
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条**没配平**的事实。**这是本模块的输出，不是处理队列。**
--
-- ⚠️ 差异类型 5 种：
--   EXTERNAL_ONLY     平台账单有、我方账没有（平台多记）
--   BOOK_ONLY         我方账有、平台账单没有（我方多记）
--   AMOUNT_MISMATCH   两边按维度配上了，但**组内合计不等**（差多少）
--   CURRENCY_MISMATCH 币种不一致 ⇒ **汇率不可得**，差额恒为 NULL（不按 1:1 兜底）
--   DIMENSION_MISMATCH 维度只对上 1 个（**不足以构成匹配**）⇒ 落成差异并说明差在哪
--
-- ⚠️ **归属（attribution）**是数据层的一等公民：这条差异归哪张单 / 哪个来源，
--   人不用回上游翻单据就知道该找谁。
CREATE TABLE finance_reconciliation_difference (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  difference_type text NOT NULL CHECK(difference_type IN (
    'EXTERNAL_ONLY','BOOK_ONLY','AMOUNT_MISMATCH','CURRENCY_MISMATCH','DIMENSION_MISMATCH')),
  -- 哪一侧多出来的：BOTH = 两边都参与了但没配平。
  side text NOT NULL CHECK(side IN ('EXTERNAL','BOOK','BOTH')),
  -- 我方账来源（平台侧多出来时为空 —— 那条差异没有我方来源）
  book_source text
    CHECK(book_source IS NULL OR book_source IN ('RECEIVABLE','PAYMENT_ORDER','BANK_STATEMENT')),

  -- ---- 关联项 ----
  group_key uuid,
  external_kind text CHECK(external_kind IS NULL OR external_kind IN ('SETTLEMENT','SETTLEMENT_LINE')),
  external_line_id uuid,
  book_line_id uuid,

  -- ---- 快照事实 ----
  occurred_on date,
  reference varchar(160) NOT NULL DEFAULT '',
  transaction_type varchar(32) NOT NULL DEFAULT '',
  -- 平台账单侧金额（可空：只有我方账多出来的那一侧时为空）
  external_amount numeric(19,4) CHECK(external_amount IS NULL OR external_amount > 0),
  -- 我方账侧金额（可空：只有平台账单多出来的那一侧时为空）
  book_amount numeric(19,4) CHECK(book_amount IS NULL OR book_amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  direction text NOT NULL CHECK(direction IN ('IN','OUT')),

  -- ---- 差额（我方账合计 − 平台账单合计；**跨币种恒为 NULL**）----
  difference_amount numeric(19,4),
  -- 汇率是否可得。本模块**不折算** ⇒ 跨币种恒为 false。
  exchange_rate_available boolean NOT NULL DEFAULT true,

  -- ---- 归属与原因（人话）----
  attribution varchar(200) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',

  -- ---- 状态：本模块**只写 'OPEN'** ----
  --    认领 / 调整 / 核销归 12.3.2.2（未交付）⇒ 词表先留好，下游推进时由触发器放行。
  status text NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','CLAIMED','ADJUSTED','WRITTEN_OFF')),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, task_id) REFERENCES finance_reconciliation_task(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_reconciliation_difference_by_task
  ON finance_reconciliation_difference(tenant_id, task_id, difference_type, id);
-- 🔴 **按币种分组呈现**：差异清单必须按币种分组，**绝不做跨币种差额合计**。
CREATE INDEX finance_reconciliation_difference_by_currency
  ON finance_reconciliation_difference(tenant_id, task_id, currency);
CREATE INDEX finance_reconciliation_difference_by_source
  ON finance_reconciliation_difference(tenant_id, task_id, book_source);

-- ---------------------------------------------------------------------------
-- 4. finance_reconciliation_event —— 事件流（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_reconciliation_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  event_type text NOT NULL CHECK(event_type IN (
    'TASK_CREATED','TASK_SCOPE_UPDATED','MATCH_COMPLETED')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  summary varchar(500) NOT NULL DEFAULT '',
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, task_id) REFERENCES finance_reconciliation_task(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_reconciliation_event_by_task
  ON finance_reconciliation_event(tenant_id, task_id, occurred_at, id);

-- ===========================================================================
-- 多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_reconciliation_task','finance_reconciliation_match',
    'finance_reconciliation_difference','finance_reconciliation_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_reconciliation_task TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_reconciliation_task TO starcon_app;
-- 🔴 匹配结果是**一次性执行的事实** ⇒ append-only：运行角色只给 SELECT + INSERT。
GRANT SELECT,INSERT ON finance_reconciliation_match TO finance_app;
GRANT SELECT,INSERT ON finance_reconciliation_match TO starcon_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_match FROM finance_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_match FROM starcon_app;
-- 差异清单：只读 + 追加；**不允许删**。`status` 的推进留给 12.3.2.2（触发器管住其余字段）。
GRANT SELECT,INSERT,UPDATE ON finance_reconciliation_difference TO finance_app;
GRANT SELECT,INSERT,UPDATE ON finance_reconciliation_difference TO starcon_app;
REVOKE DELETE ON finance_reconciliation_difference FROM finance_app;
REVOKE DELETE ON finance_reconciliation_difference FROM starcon_app;
-- 事件流是**追加式**举证：运行角色只给 SELECT + INSERT。
GRANT SELECT,INSERT ON finance_reconciliation_event TO finance_app;
GRANT SELECT,INSERT ON finance_reconciliation_event TO starcon_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_event FROM starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- ===========================================================================
-- 数据层守卫（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 任务一旦执行完成，**范围**冻结；只允许 DRAFT → MATCHED 这一步推进。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_task_frozen() RETURNS trigger AS $$
DECLARE
  scope_changed boolean;
BEGIN
  IF OLD.status = 'DRAFT' AND NEW.status = 'MATCHED' THEN
    -- 推进时范围**必须逐字不变**（否则「执行时的口径」与「事后看到的口径」会打架）。
    scope_changed :=
      NEW.account_id IS DISTINCT FROM OLD.account_id
      OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
      OR NEW.period_start IS DISTINCT FROM OLD.period_start
      OR NEW.period_end IS DISTINCT FROM OLD.period_end
      OR NEW.book_sources IS DISTINCT FROM OLD.book_sources
      OR NEW.date_window_days IS DISTINCT FROM OLD.date_window_days
      OR NEW.amount_tolerance IS DISTINCT FROM OLD.amount_tolerance;
    IF scope_changed THEN
      RAISE EXCEPTION '执行匹配时不得改动对账范围（% / % / %）',
        OLD.account_no, OLD.period_start, OLD.period_end
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status <> 'DRAFT' THEN
    RAISE EXCEPTION '对账任务已执行完成，结果不可改写（要重来请新建一个任务）：%',
      OLD.task_no USING ERRCODE = 'check_violation';
  END IF;

  -- DRAFT 态：范围可以改，但**状态**只能向前（DRAFT → MATCHED）。
  IF NEW.status <> OLD.status THEN
    RAISE EXCEPTION '对账任务状态只能 DRAFT → MATCHED，不能 % → %',
      OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_task_guard
  BEFORE UPDATE ON finance_reconciliation_task
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_task_frozen();

-- (b) 匹配结果 append-only（运行角色权限已收回，这里是第二道保险）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '匹配结果是一次性执行的事实，不可改写（要重来请新建一个对账任务）'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_append_only
  BEFORE UPDATE OR DELETE ON finance_reconciliation_match
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_append_only();

-- (c) 匹配结果里的两侧单据**必须真的存在于本租户**（同服务同库 ⇒ 可校验）。
--     平台侧：12.3.1.1 的结算单头 / 12.3.1.2 的结算明细行。
--     我方侧：12.1.1.1 应收明细行 / 12.2.2.1 付款单明细行 /
--             12.7.1.2 银行流水行 —— ⚠️ 银行流水是**别的模块的表**，
--             本模块**不建跨模块外键、也不加跨模块触发器**（模块边界纪律）：
--             它的存在性由**授权只读 API**保证（读不出来的行根本进不了候选集）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_sources() RETURNS trigger AS $$
BEGIN
  IF NEW.external_kind = 'SETTLEMENT' THEN
    IF NOT EXISTS (SELECT 1 FROM finance_settlement s
                    WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.external_line_id) THEN
      RAISE EXCEPTION '结算单不存在或不属于当前租户：%', NEW.external_line_id
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.external_kind = 'SETTLEMENT_LINE' THEN
    IF NOT EXISTS (SELECT 1 FROM finance_settlement_line l
                    WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.external_line_id) THEN
      RAISE EXCEPTION '结算明细行不存在或不属于当前租户：%', NEW.external_line_id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  IF NEW.book_source = 'RECEIVABLE' THEN
    IF NOT EXISTS (SELECT 1 FROM finance_receivable_line l
                    WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.book_line_id) THEN
      RAISE EXCEPTION '应收明细行不存在或不属于当前租户：%', NEW.book_line_id
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.book_source = 'PAYMENT_ORDER' THEN
    IF NOT EXISTS (SELECT 1 FROM finance_payment_order_line l
                    WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.book_line_id) THEN
      RAISE EXCEPTION '付款单明细行不存在或不属于当前租户：%', NEW.book_line_id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_source_guard
  BEFORE INSERT ON finance_reconciliation_match
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_sources();

-- (d) 组级金额必须自洽：组内平台合计 = 我方合计 ⇒ 差额恒为 0（容差内也写实际差额）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_match_group_amount() RETURNS trigger AS $$
BEGIN
  IF NEW.group_external_amount <> NEW.group_book_amount THEN
    -- 组内合计不等 ⇒ 这**不是**匹配结果（那是差异清单里的 AMOUNT_MISMATCH）。
    RAISE EXCEPTION '匹配结果的组内合计必须相等：平台 % ≠ 我方 %',
      NEW.group_external_amount, NEW.group_book_amount
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.group_amount_difference <> 0 THEN
    RAISE EXCEPTION '匹配结果的组级差额必须为 0（实际是 %）', NEW.group_amount_difference
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_match_group_amount_guard
  BEFORE INSERT ON finance_reconciliation_match
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_match_group_amount();

-- (e) 差异清单：除了 `status` 之外全部冻结；`status` 只能向前推进。
--     ⚠️ 本模块**只写 'OPEN'**；认领 / 调整 / 核销归 12.3.2.2（未交付）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_difference_immutable() RETURNS trigger AS $$
DECLARE
  status_rank_old int;
  status_rank_new int;
BEGIN
  status_rank_old := CASE OLD.status
    WHEN 'OPEN' THEN 0 WHEN 'CLAIMED' THEN 1 WHEN 'ADJUSTED' THEN 2 WHEN 'WRITTEN_OFF' THEN 3
    ELSE -1 END;
  status_rank_new := CASE NEW.status
    WHEN 'OPEN' THEN 0 WHEN 'CLAIMED' THEN 1 WHEN 'ADJUSTED' THEN 2 WHEN 'WRITTEN_OFF' THEN 3
    ELSE -1 END;

  IF status_rank_new < status_rank_old THEN
    RAISE EXCEPTION '差异状态不能回退：% → %', OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 除 status 外，任何字段都不可改写（差异是执行时的事实）。
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.task_id IS DISTINCT FROM OLD.task_id
     OR NEW.difference_type IS DISTINCT FROM OLD.difference_type
     OR NEW.side IS DISTINCT FROM OLD.side
     OR NEW.book_source IS DISTINCT FROM OLD.book_source
     OR NEW.group_key IS DISTINCT FROM OLD.group_key
     OR NEW.external_kind IS DISTINCT FROM OLD.external_kind
     OR NEW.external_line_id IS DISTINCT FROM OLD.external_line_id
     OR NEW.book_line_id IS DISTINCT FROM OLD.book_line_id
     OR NEW.occurred_on IS DISTINCT FROM OLD.occurred_on
     OR NEW.reference IS DISTINCT FROM OLD.reference
     OR NEW.transaction_type IS DISTINCT FROM OLD.transaction_type
     OR NEW.external_amount IS DISTINCT FROM OLD.external_amount
     OR NEW.book_amount IS DISTINCT FROM OLD.book_amount
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.direction IS DISTINCT FROM OLD.direction
     OR NEW.difference_amount IS DISTINCT FROM OLD.difference_amount
     OR NEW.exchange_rate_available IS DISTINCT FROM OLD.exchange_rate_available
     OR NEW.attribution IS DISTINCT FROM OLD.attribution
     OR NEW.reason IS DISTINCT FROM OLD.reason THEN
    RAISE EXCEPTION '差异清单的字段不可改写（只有 status 能向前推进）'
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_difference_guard
  BEFORE UPDATE ON finance_reconciliation_difference
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_difference_immutable();

-- (f) 跨币种时**差额必须为 NULL** —— 不按 1:1 兜底（写 0 会被读成「账是平的」）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_difference_currency() RETURNS trigger AS $$
BEGIN
  IF NEW.exchange_rate_available = false AND NEW.difference_amount IS NOT NULL THEN
    RAISE EXCEPTION '汇率不可得时差额必须为 NULL（不能按 1:1 兜底）'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.difference_type = 'CURRENCY_MISMATCH'
     AND (NEW.exchange_rate_available <> false OR NEW.difference_amount IS NOT NULL) THEN
    RAISE EXCEPTION '币种不一致的差异必须标记为「汇率不可得」且差额为 NULL'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_difference_currency_guard
  BEFORE INSERT OR UPDATE ON finance_reconciliation_difference
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_difference_currency();

-- (g) 事件流 append-only（运行角色权限已收回，这里是第二道保险）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '事件流是追加式举证，不可改写' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_event_append_only
  BEFORE UPDATE OR DELETE ON finance_reconciliation_event
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_event_append_only();
