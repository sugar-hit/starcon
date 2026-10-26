-- ===========================================================================
-- V374 · finance_reconciliation_handling* —— 12.3.2.2 差异处理
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.3.2.2 差异处理）原文：
--
--   - **职责**：处理对账差异：分类（我方多记/对方多记/时间差/汇率差/手续费遗漏）、
--     指定责任人、人工判定与调整、闭环跟踪。
--   - **输入**：差异明细；处理结论；调整凭证或说明。
--   - **输出**：差异处理记录 + 调整分录 + 闭环状态。
--   - **关系**：上游 = 12.3.2.1；下游 = 12.5.2 凭证、1.3 预警。
--     **差异分类是改进闭环的输入**：某类差异反复出现说明上游流程有系统性问题。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么（上游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **不 ALTER 12.3.2.1 的四张表。** `finance_reconciliation_difference` 的
--     触发器 `guard_finance_reconciliation_difference_immutable` 只放行 `status`
--     向前推进，其余每个字段（差异类型 / 侧 / 两侧金额 / 币种 / 差额 / 归因 /
--     原因 …）都不可改写 ⇒ **差异是执行时的事实**。
--     本迁移**只 CREATE 自己的三张表**、**只 UPDATE 上游那一张表的 `status`**，
--     **零 ALTER 零 DROP 零 ADD COLUMN**。
--
--   * **不往上游 `finance_reconciliation_event` 追加事件。** 那张表的 `event_type`
--     词表被 CHECK 钉死成 `('TASK_CREATED','TASK_SCOPE_UPDATED','MATCH_COMPLETED')`
--     —— 塞「差异认领」进去要么撞 CHECK、要么借用一个语义不对的类型。
--     而**放行新类型 = ALTER 上游的表**（明令禁止）⇒
--     本模块**自建**一条处理时间线表 `finance_reconciliation_handling_event`
--     （append-only），事件类型词表是**本模块自己的**。
--
--   * **不建「差异分类字典表」。** 五个分类是**代码内受控词表**
--     （`BOOK_OVER_RECORDED` / `COUNTERPARTY_OVER_RECORDED` / `TIMING_DIFFERENCE` /
--     `FX_DIFFERENCE` / `FEE_OMISSION`），跟 12.3.2.1 的做法一致
--     （匹配参数也是代码内词表，不是规则管理表 —— 那是 12.3.2.3 的表）。
--
--   * **不做跨币种折算，也不做跨币种合计。** 上游差异的 `difference_amount` 在
--     跨币种时**恒为 NULL**（汇率不可得，DB CHECK 钉死）；本模块的调整分录**每条
--     自带币种**，一切汇总**按币种分组** ⇒ 结构上不存在「把 USD 和 EUR 加起来」
--     这一格。
--
--   * **不产生凭证。** 蓝图说「下游 = 12.5.2 凭证」—— 12.5.2.1 **未交付**。
--     本模块只**记录调整分录**（凭证号 **或** 说明），凭证本身由 12.5.2.1 生成
--     （自己造一套凭证 = 造出第二套账）。
--
--   * **不存责任人姓名。** 本仓**没有用户目录服务**（identity 只给当前会话的
--     成员关系与权限），存姓名只能是前端编的 ⇒ 与 08 仓储的 `assignee_user_id`
--     一样，**只存用户 uuid**，显示名由调用方自己渲染。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_reconciliation_handling        —— 差异处理记录（分类 + 责任人 + 闭环状态）
--   finance_reconciliation_adjustment      —— 调整分录（凭证或说明，**append-only**）
--   finance_reconciliation_handling_event   —— 处理时间线（**append-only** 举证）
--
--   三张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 三条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **处理状态与上游差异状态严格同相**
--      处理记录上的 `status` 与上游 `finance_reconciliation_difference.status`
--      用**同一个词表**（OPEN / CLAIMED / ADJUSTED / WRITTEN_OFF），并且由
--      `guard_finance_reconciliation_handling_state` 触发器**逐次核对**：
--      处理记录写进去的状态必须**等于**上游差异当前的状态，否则直接拒。
--      ⇒ 直连 SQL 也写不出「处理记录说已核销、上游差异还挂在待处理」这种自相矛盾。
--      两边都**只能向前**（上游 `status_rank` 触发器已经钉死，本表再钉一道）。
--
--   2. **闭环必须有人负责、必须有分类、必须有结论**
--      表级 CHECK 把「差异处理」从「随手改个状态」变成一张**有约束的工单**：
--        · CLAIMED            ⇒ 有责任人（没有主人的差异不许进入处理流程）
--        · ADJUSTED/WRITTEN_OFF ⇒ 有责任人 **且** 有分类归因 **且** 有闭环结论
--        · 闭环后处理记录**整体冻结**（终态不可改写）
--      分类理由**必填**（人工分类 + 理由是蓝图点名的输入），闭环结论**必填**。
--
--   3. **调整分录是「凭证 或 说明」，不是二选一的强制**
--      蓝图输入句写的是「调整凭证**或**说明」⇒ 表级 CHECK 要求
--      `voucher_no IS NOT NULL OR btrim(explanation) <> ''`：
--      有凭证号就不必再写说明，只有说明也行，**两者都没有 = 不成立**。
--      分录**append-only**（运行角色只给 SELECT + INSERT + 触发器双保险）。
--
-- ---------------------------------------------------------------------------
-- 3. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 调整金额 numeric(19,4)，**恒正**（方向由 `direction` 列表达）⇒
--     不出现「负数代表支出」这种二义性。**全链路禁浮点**。
--   * 每条分录**自带币种** char(3) ⇒ 汇总**按币种分组**，绝不跨币种相加。
--   * 上游差异的 `difference_amount` / `exchange_rate_available` 只**原样读**、
--     **不回写**（上游字段冻结）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_reconciliation_handling —— 差异处理记录
-- ---------------------------------------------------------------------------
--
-- 一条差异 = **至多一条**处理记录（UNIQUE(tenant_id, difference_id)）。
-- 处理记录**不存在** = 这条差异还没人碰（上游状态 OPEN）。
CREATE TABLE finance_reconciliation_handling (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ---- 关联（**同服务同库** ⇒ 可建复合外键）----
  difference_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- ---- 快照（处理当时的读数；上游差异是执行时的事实，**不回写**）----
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  difference_type text NOT NULL CHECK(difference_type IN (
    'EXTERNAL_ONLY','BOOK_ONLY','AMOUNT_MISMATCH','CURRENCY_MISMATCH','DIMENSION_MISMATCH')),
  reference varchar(160) NOT NULL DEFAULT '',

  -- ---- 分类归因（人工分类 + 理由）----
  -- 蓝图点名的五类：我方多记 / 对方多记 / 时间差 / 汇率差 / 手续费遗漏。
  category text CHECK(category IS NULL OR category IN (
    'BOOK_OVER_RECORDED','COUNTERPARTY_OVER_RECORDED','TIMING_DIFFERENCE',
    'FX_DIFFERENCE','FEE_OMISSION')),
  -- 理由**必填**（有分类就必须有理由）。
  category_reason varchar(500) NOT NULL DEFAULT '',
  category_set_by uuid,
  category_set_at timestamptz,

  -- ---- 责任人（本仓没有用户目录 ⇒ **只存用户 uuid**，不存编出来的姓名）----
  assignee_id uuid,

  -- ---- 状态机（**与上游差异状态同词表、同相位**，只能向前）----
  --   OPEN        已开始处理但还没人认领（认领前可以先分类）
  --   CLAIMED     已认领并指定责任人
  --   ADJUSTED    已判定「已调整」（终态）
  --   WRITTEN_OFF 已判定「已核销」（终态）
  status text NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','CLAIMED','ADJUSTED','WRITTEN_OFF')),
  claimed_by uuid,
  claimed_at timestamptz,

  -- ---- 闭环 ----
  conclusion varchar(500) NOT NULL DEFAULT '',
  closed_by uuid,
  closed_at timestamptz,

  version int NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 一条差异**至多一条**处理记录 ⇒ 「同一条差异被处理两次」在结构上写不出来。
  UNIQUE(tenant_id, difference_id),
  FOREIGN KEY(tenant_id, difference_id) REFERENCES finance_reconciliation_difference(tenant_id, id),
  FOREIGN KEY(tenant_id, task_id) REFERENCES finance_reconciliation_task(tenant_id, id),

  -- 🔴 **分类与理由同进同出**：有分类 ⇒ 有理由 + 有分类人 + 有分类时刻；
  --   没分类 ⇒ 三者都为空（不留半截）。
  CONSTRAINT finance_reconciliation_handling_category_ck CHECK (
    (category IS NULL
      AND category_reason = '' AND category_set_by IS NULL AND category_set_at IS NULL)
    OR
    (category IS NOT NULL
      AND btrim(category_reason) <> '' AND category_set_by IS NOT NULL AND category_set_at IS NOT NULL)
  ),

  -- 🔴 **状态与留痕必须自洽**：
  --   OPEN        ⇒ 还没人认领（认领痕迹、闭环痕迹都为空）
  --   CLAIMED     ⇒ 有认领人 + 有认领时刻 + **有责任人**（没有主人的差异不许进流程）
  --   ADJUSTED / WRITTEN_OFF ⇒ 上面全部 + **有分类归因** + **有闭环结论** + 有闭环人/时刻
  CONSTRAINT finance_reconciliation_handling_status_ck CHECK (
    (status = 'OPEN'
      AND claimed_by IS NULL AND claimed_at IS NULL
      AND closed_by IS NULL AND closed_at IS NULL AND btrim(conclusion) = '')
    OR
    (status = 'CLAIMED'
      AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL AND assignee_id IS NOT NULL
      AND closed_by IS NULL AND closed_at IS NULL AND btrim(conclusion) = '')
    OR
    (status IN ('ADJUSTED','WRITTEN_OFF')
      AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL AND assignee_id IS NOT NULL
      AND category IS NOT NULL
      AND closed_by IS NOT NULL AND closed_at IS NOT NULL AND btrim(conclusion) <> '')
  )
);

-- 队列主视角：按状态筛（待处理 / 已认领 / 已调整 / 已核销）。
CREATE INDEX finance_reconciliation_handling_by_status
  ON finance_reconciliation_handling(tenant_id, status, updated_at DESC, id);
-- 分类分布（改进闭环的输入：某类差异反复出现说明上游流程有系统性问题）。
CREATE INDEX finance_reconciliation_handling_by_category
  ON finance_reconciliation_handling(tenant_id, category, currency);
-- 责任人工作量。
CREATE INDEX finance_reconciliation_handling_by_assignee
  ON finance_reconciliation_handling(tenant_id, assignee_id, status) WHERE assignee_id IS NOT NULL;
-- 按对账任务钻取。
CREATE INDEX finance_reconciliation_handling_by_task
  ON finance_reconciliation_handling(tenant_id, task_id, status);
-- 按币种分组（绝不跨币种合计 ⇒ 币种是索引的第一段之一）。
CREATE INDEX finance_reconciliation_handling_by_currency
  ON finance_reconciliation_handling(tenant_id, currency, status);

-- ---------------------------------------------------------------------------
-- 2. finance_reconciliation_adjustment —— 调整分录（append-only）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条调整分录。**凭证号 或 说明**至少有一个（蓝图输入句）。
CREATE TABLE finance_reconciliation_adjustment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  handling_id uuid NOT NULL,
  difference_id uuid NOT NULL,

  -- 分录序号（同一处理记录内递增，append-only ⇒ 不会重号）。
  entry_no int NOT NULL CHECK(entry_no > 0),

  -- ---- 调整凭证（下游 12.5.2 凭证；**本模块不生成凭证**，只登记号）----
  voucher_no varchar(64),

  -- ---- 调整金额（**恒正**，方向单独一列；每条**自带币种**）----
  adjustment_amount numeric(19,4) NOT NULL CHECK(adjustment_amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  direction text NOT NULL CHECK(direction IN ('IN','OUT')),

  -- ---- 说明（凭证号与说明至少有一个）----
  explanation varchar(500) NOT NULL DEFAULT '',
  -- 调整的业务日期（可空 = 未指定）。
  occurred_on date,

  recorded_by uuid NOT NULL,
  recorded_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, handling_id, entry_no),
  FOREIGN KEY(tenant_id, handling_id) REFERENCES finance_reconciliation_handling(tenant_id, id),

  -- 🔴 蓝图输入句「调整凭证**或**说明」：两者都没有 = 这条分录什么都不是。
  CONSTRAINT finance_reconciliation_adjustment_basis_ck CHECK (
    (voucher_no IS NOT NULL AND btrim(voucher_no) <> '')
    OR btrim(explanation) <> ''
  )
);

CREATE INDEX finance_reconciliation_adjustment_by_handling
  ON finance_reconciliation_adjustment(tenant_id, handling_id, entry_no);
-- 按币种分组汇总（调整金额按币种分开，绝不相加）。
CREATE INDEX finance_reconciliation_adjustment_by_currency
  ON finance_reconciliation_adjustment(tenant_id, currency);

-- ---------------------------------------------------------------------------
-- 3. finance_reconciliation_handling_event —— 处理时间线（append-only）
-- ---------------------------------------------------------------------------
--
-- ⚠️ 上游 `finance_reconciliation_event` 的 `event_type` 词表被 CHECK 钉死成
--   任务级事件，塞不下「差异认领」；而**放行新类型要 ALTER 上游的表**（禁止）⇒
--   本模块自建一条**处理级**时间线。
CREATE TABLE finance_reconciliation_handling_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  handling_id uuid NOT NULL,
  difference_id uuid NOT NULL,
  task_id uuid NOT NULL,

  event_type text NOT NULL CHECK(event_type IN (
    'HANDLING_CREATED','ASSIGNED','CLASSIFIED','ADJUSTMENT_RECORDED',
    'ADJUSTED','WRITTEN_OFF')),
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  summary varchar(500) NOT NULL DEFAULT '',
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, handling_id) REFERENCES finance_reconciliation_handling(tenant_id, id)
);

CREATE INDEX finance_reconciliation_handling_event_by_handling
  ON finance_reconciliation_handling_event(tenant_id, handling_id, occurred_at, id);
CREATE INDEX finance_reconciliation_handling_event_by_difference
  ON finance_reconciliation_handling_event(tenant_id, difference_id, occurred_at, id);

-- ===========================================================================
-- 多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_reconciliation_handling','finance_reconciliation_adjustment',
    'finance_reconciliation_handling_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE ON finance_reconciliation_handling TO finance_app;
GRANT SELECT,INSERT,UPDATE ON finance_reconciliation_handling TO starcon_app;
-- 处理记录**不可删**（闭环记录删掉 = 差异凭空消失）。
REVOKE DELETE ON finance_reconciliation_handling FROM finance_app;
REVOKE DELETE ON finance_reconciliation_handling FROM starcon_app;
-- 调整分录是**一次性事实** ⇒ append-only：运行角色只给 SELECT + INSERT。
GRANT SELECT,INSERT ON finance_reconciliation_adjustment TO finance_app;
GRANT SELECT,INSERT ON finance_reconciliation_adjustment TO starcon_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_adjustment FROM finance_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_adjustment FROM starcon_app;
-- 时间线是**追加式**举证：运行角色只给 SELECT + INSERT。
GRANT SELECT,INSERT ON finance_reconciliation_handling_event TO finance_app;
GRANT SELECT,INSERT ON finance_reconciliation_handling_event TO starcon_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_handling_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_reconciliation_handling_event FROM starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- ===========================================================================
-- 数据层守卫（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 处理记录与**上游差异状态同相**，且只能向前；闭环后**整体冻结**。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_handling_state() RETURNS trigger AS $$
DECLARE
  upstream_status text;
  upstream_task uuid;
  rank_old int;
  rank_new int;
BEGIN
  SELECT d.status, d.task_id INTO upstream_status, upstream_task
    FROM finance_reconciliation_difference d
   WHERE d.tenant_id = NEW.tenant_id AND d.id = NEW.difference_id;

  IF upstream_status IS NULL THEN
    RAISE EXCEPTION '差异不存在或不属于当前租户：%', NEW.difference_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF upstream_task IS DISTINCT FROM NEW.task_id THEN
    RAISE EXCEPTION '处理记录的对账任务与上游差异不一致：% vs %', NEW.task_id, upstream_task
      USING ERRCODE = 'check_violation';
  END IF;

  -- 🔴 **同相**：处理记录写什么状态，上游差异就必须已经是那个状态。
  IF upstream_status <> NEW.status THEN
    RAISE EXCEPTION '处理记录状态必须与上游差异状态一致：处理 % vs 上游 %',
      NEW.status, upstream_status USING ERRCODE = 'check_violation';
  END IF;

  IF TG_OP = 'UPDATE' THEN
    rank_old := CASE OLD.status
      WHEN 'OPEN' THEN 0 WHEN 'CLAIMED' THEN 1 WHEN 'ADJUSTED' THEN 2 WHEN 'WRITTEN_OFF' THEN 3
      ELSE -1 END;
    rank_new := CASE NEW.status
      WHEN 'OPEN' THEN 0 WHEN 'CLAIMED' THEN 1 WHEN 'ADJUSTED' THEN 2 WHEN 'WRITTEN_OFF' THEN 3
      ELSE -1 END;

    IF rank_new < rank_old THEN
      RAISE EXCEPTION '差异处理状态不能回退：% → %', OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;

    -- 🔴 **闭环后整体冻结**：已调整 / 已核销是终态，连分类都不能再改。
    IF OLD.status IN ('ADJUSTED','WRITTEN_OFF') THEN
      RAISE EXCEPTION '差异处理已闭环（%），记录不可再改写', OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_handling_state_guard
  BEFORE INSERT OR UPDATE ON finance_reconciliation_handling
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_handling_state();

-- (b) 调整分录：**append-only**（运行角色权限已收回，这里是第二道保险），
--     而且**只能挂在已认领的处理记录上**（没主人就没资格记账）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_adjustment_append_only() RETURNS trigger AS $$
DECLARE
  handling_status text;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION '调整分录是一次性事实，不可改写' USING ERRCODE = 'check_violation';
  END IF;

  SELECT h.status INTO handling_status
    FROM finance_reconciliation_handling h
   WHERE h.tenant_id = NEW.tenant_id AND h.id = NEW.handling_id;

  IF handling_status IS NULL THEN
    RAISE EXCEPTION '差异处理记录不存在或不属于当前租户：%', NEW.handling_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF handling_status <> 'CLAIMED' THEN
    RAISE EXCEPTION '只有在「已认领」的差异处理记录上才能登记调整分录（当前 %）', handling_status
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_adjustment_append_only
  BEFORE INSERT OR UPDATE OR DELETE ON finance_reconciliation_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_adjustment_append_only();

-- (c) 时间线 append-only（运行角色权限已收回，这里是第二道保险）。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_handling_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '处理时间线是追加式举证，不可改写' USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_handling_event_append_only
  BEFORE UPDATE OR DELETE ON finance_reconciliation_handling_event
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_handling_event_append_only();

-- (d) 闭环时**至少要有一条调整分录**（核销除外 —— 核销 = 判定不需要调整）。
--     🔴 写成 DB 触发器 ⇒ 直连 SQL 也绕不过「没调整就判已调整」。
CREATE OR REPLACE FUNCTION guard_finance_reconciliation_handling_close() RETURNS trigger AS $$
DECLARE
  adjustment_count int;
BEGIN
  IF NEW.status = 'ADJUSTED' AND OLD.status IS DISTINCT FROM NEW.status THEN
    SELECT count(*) INTO adjustment_count
      FROM finance_reconciliation_adjustment a
     WHERE a.tenant_id = NEW.tenant_id AND a.handling_id = NEW.id;
    IF adjustment_count = 0 THEN
      RAISE EXCEPTION '判定「已调整」必须至少有一条调整分录（凭证或说明）'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_reconciliation_handling_close_guard
  BEFORE UPDATE ON finance_reconciliation_handling
  FOR EACH ROW EXECUTE FUNCTION guard_finance_reconciliation_handling_close();
