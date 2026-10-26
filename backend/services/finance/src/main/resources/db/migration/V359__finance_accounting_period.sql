-- ===========================================================================
-- V359 · finance_accounting_period —— 12.5.1.2 会计期间
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.5.1.2 会计期间
--   - **职责**：管理会计期间（自然月或 4-4-5 fiscal 周期）：期间状态（开放/关闭）、
--     关账与重开、期间内凭证限制。
--   - **输入**：会计日历配置；关账/重开操作；审批。
--   - **输出**：期间状态；关账记录。
--   - **关系**：上游 = 会计日历；下游 = 12.5.2.1 凭证（已关闭期间禁止过账）。
--     **重开已关期间是高风险操作**，必须审批留痕。
--
-- ---------------------------------------------------------------------------
-- 0. 五条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **接管 V357 的 `finance_voucher_period`（不是新建一张平行表）**。
--      V357 第六节交接面原文：「期间表的**最终归属是 12.5.1.2**（它的蓝图输出写死
--      「期间状态；关账记录」）」「12.5.1.2 交付后**接管本表**（照 V350 接管 V346 守卫的
--      同一手法）：它 ADD COLUMN 补 fiscal 周期 / 关账检查 / 重开审批列」。
--      ⇒ 本迁移**接手续用**这张表：ALTER 加列，**零新建平行期间表**。
--      ⇒ 12.5.2.1 的凭证继续读同一张表的 `status` 做过账闸门 ⇒ **它的结构不用改**。
--
--   2. **「重开已关期间是高风险操作，必须审批留痕」钉在 DB 层**。
--      `guard_finance_voucher_period_state` 里那条「重开 ⇒ 必须存在**已批准**的审批留痕」
--      是**数据层**的约束：绕过服务层直连 SQL 也重开不了一个没有审批的期间。
--      审批本身**不**在本服务实现 —— 审批属 17 审批（identity），本表只是
--      identity 审批链路的**本地镜像**（与 V346 的 `finance_payment_approval_record` 同性质）。
--
--   3. **关账记录是 append-only 的**。
--      `finance_accounting_period_event` 只能追加（运行角色 UPDATE/DELETE 已收回 + 触发器兜底）：
--      「这一期什么时候关的、为什么关、谁批的重开」是**举证凭据**，不是可改字段。
--
--   4. **fiscal 周期由会计日历派生，不由人肉填**。
--      `finance_accounting_calendar` 记日历类型（自然月 / 4-4-5）+ 财年起始月 + 4-4-5 锚点日；
--      期间行由日历**生成**（自然月 = 12 个日历月；4-4-5 = 4-4-5 循环 × 4 轮 = 52 周）。
--      周期长度用**生成列**（`period_end - period_start + 1`）⇒ 不可能与日期漂移。
--
--   5. **期间身份字段一经入账不可改**。
--      凭证按 `period_id` 引用期间；改了期间区间 / 本位币 = 改了账期 ⇒
--      DB 触发器逐字比对 `period_code / period_start / period_end / base_currency /
--      calendar_id / fiscal_year / fiscal_period_no / period_type`，一条都不许改。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_accounting_calendar        —— 会计日历配置（上游「会计日历」）
--   finance_voucher_period             —— 会计期间（**接管 V357 的同名表**：补 fiscal 周期 + 重开留痕）
--   finance_accounting_period_event    —— 期间事件留痕（append-only）= **关账记录**
--   finance_accounting_period_approval —— 重开审批链路（identity 的本地镜像）
--
--   四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_accounting_calendar —— 会计日历配置
-- ---------------------------------------------------------------------------
--
-- 一行 = 「这个租户用哪套会计日历」。
--
-- 🔴 **日历类型决定期间怎么生成，本模块不猜**：
--   * CALENDAR_MONTH 自然月：12 个日历月，财年从 `fiscal_year_start_month` 起算
--     （start_month=4 ⇒ 财年 2026 = 2026-04-01 .. 2027-03-31）。
--   * FISCAL_445 4-4-5 fiscal 周期：12 个 13 周周期，每周段 4-4-5 循环 × 4 轮 = 52 周，
--     锚点日 `anchor_date` = 财年第一天。**必须有锚点日**（表级 CHECK 钉死）。
--   * CUSTOM：完全自定义，日历不生成期间（用户自己登记）。
CREATE TABLE finance_accounting_calendar (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 日历编码：租户内唯一（同一套日历只登记一次）。
  calendar_code varchar(32) NOT NULL,
  calendar_name varchar(120) NOT NULL,
  -- 日历类型（决定期间怎么生成）。
  calendar_type varchar(24) NOT NULL CHECK(calendar_type IN ('CALENDAR_MONTH','FISCAL_445','CUSTOM')),
  -- 财年起始月（自然月用）：1 = 财年 = 自然年；4 = 财年 4 月起。
  fiscal_year_start_month int NOT NULL CHECK(fiscal_year_start_month BETWEEN 1 AND 12),
  -- 4-4-5 锚点日（财年第一天）。自然月日历不需要（由起始月推出来）。
  anchor_date date,
  -- 本位币（账簿本位币）：期间的 base_currency 与它一致（服务层判）。
  base_currency char(3) NOT NULL CHECK(base_currency ~ '^[A-Z]{3}$'),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, calendar_code),
  CHECK(btrim(calendar_code) <> ''),
  CHECK(btrim(calendar_name) <> ''),
  -- 4-4-5 没有锚点日就没法算周期边界 ⇒ 钉死。
  CONSTRAINT finance_accounting_calendar_anchor_ck CHECK (
    calendar_type <> 'FISCAL_445' OR anchor_date IS NOT NULL
  )
);

-- ---------------------------------------------------------------------------
-- 2. 接管 finance_voucher_period —— 补 fiscal 周期 + 重开留痕
-- ---------------------------------------------------------------------------
--
-- ⚠️ **本表由 V357 建，本迁移接管它**（V357 第六节写死「期间表的最终归属是 12.5.1.2」）。
--   ⇒ **只 ALTER 这一张表**，不新建平行期间表。
--   ⇒ V357 的 `finance_voucher` 按 `FOREIGN KEY(tenant_id, period_id)` 引用它 ⇒ 本表**不能删**
--     （新增 `guard_finance_voucher_period_no_delete`）。
--
-- 新增列：
--   * calendar_id / fiscal_year / fiscal_period_no / period_type —— **fiscal 周期**
--   * period_length_days —— **生成列**（`period_end - period_start + 1`）⇒ 与日期不可能漂移
--   * reopened_at / reopened_by / reopen_reason / reopen_approval_instance_id / reopen_count
--     —— **重开留痕**
ALTER TABLE finance_voucher_period
  ADD COLUMN calendar_id uuid,
  ADD COLUMN fiscal_year int,
  ADD COLUMN fiscal_period_no int,
  ADD COLUMN period_type varchar(24) NOT NULL DEFAULT 'CUSTOM',
  ADD COLUMN period_length_days int
    GENERATED ALWAYS AS (period_end - period_start + 1) STORED,
  ADD COLUMN reopened_at timestamptz,
  ADD COLUMN reopened_by uuid,
  ADD COLUMN reopen_reason varchar(500) NOT NULL DEFAULT '',
  ADD COLUMN reopen_approval_instance_id uuid,
  ADD COLUMN reopen_count int NOT NULL DEFAULT 0;

-- 日历引用：同服务同库 ⇒ 复合外键（跨服务才不给 FK）。
ALTER TABLE finance_voucher_period
  ADD CONSTRAINT finance_voucher_period_calendar_fk
  FOREIGN KEY(tenant_id, calendar_id) REFERENCES finance_accounting_calendar(tenant_id, id);

-- ---------------------------------------------------------------------------
-- 2.1 换掉 V357 的「只能关、不能重开」CHECK
-- ---------------------------------------------------------------------------
--
-- V357 的 `finance_voucher_period_closed_ck` 写死：
--     OPEN ⇒ closed_at / closed_by 必须为 NULL 且 closed_reason 必须为空
-- 那条约束**与重开并存不了**：重开之后期间回到 OPEN，但关账痕迹必须**留着**
-- （「关账记录」是蓝图写死的输出，不能因为重开就被抹掉）。
--
-- ⚠️ 手法与 V350 接管 V346 的 `execution_available` CHECK **逐字同构**：
--   先按 `pg_constraint` 把老 CHECK 找出来 DROP 掉，再补新的 —— 不 DROP 就加不动存量行。
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT conrelid::regclass AS tbl, conname
      FROM pg_constraint
     WHERE contype = 'c'
       AND conrelid = 'finance_voucher_period'::regclass
       AND pg_get_constraintdef(oid) LIKE '%closed_reason%'
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
  END LOOP;
END $$;

-- 新的状态约束（替身）：
--   CLOSED ⇒ 必须写明关闭人 / 关闭时刻 / 关闭原因（与 V357 一字不差）。
--   OPEN   ⇒ 要么从没关过（无关闭痕迹），要么**关过又重开**（必须留着重开的全套痕迹）。
ALTER TABLE finance_voucher_period
  ADD CONSTRAINT finance_voucher_period_state_ck CHECK (
    (status = 'CLOSED'
      AND closed_at IS NOT NULL AND closed_by IS NOT NULL AND btrim(closed_reason) <> '')
    OR
    (status = 'OPEN' AND (
      (closed_at IS NULL AND closed_by IS NULL AND btrim(closed_reason) = '')
      OR
      (closed_at IS NOT NULL AND reopened_at IS NOT NULL AND reopened_by IS NOT NULL
        AND btrim(reopen_reason) <> '' AND reopen_approval_instance_id IS NOT NULL)
    ))
  );

-- 期间类型与 fiscal 周期的一致性：
--   日历派生的期间（自然月 / 4-4-5）必须带日历 + 财年 + 周期序号；
--   手工登记的期间是 CUSTOM，三个字段都空。
ALTER TABLE finance_voucher_period
  ADD CONSTRAINT finance_voucher_period_calendar_ck CHECK (
    (period_type = 'CUSTOM'
      AND calendar_id IS NULL AND fiscal_year IS NULL AND fiscal_period_no IS NULL)
    OR
    (period_type IN ('CALENDAR_MONTH','FISCAL_445')
      AND calendar_id IS NOT NULL AND fiscal_year IS NOT NULL AND fiscal_period_no IS NOT NULL)
  );

-- 周期序号合法（4-4-5 与自然月都是 12 期，允许 13 期给 13 周期财年）。
ALTER TABLE finance_voucher_period
  ADD CONSTRAINT finance_voucher_period_fiscal_no_ck CHECK (
    fiscal_period_no IS NULL OR fiscal_period_no BETWEEN 1 AND 13
  );

-- 4-4-5 的周期长度必须是 4 周（28 天）或 5 周（35 天）。
ALTER TABLE finance_voucher_period
  ADD CONSTRAINT finance_voucher_period_445_len_ck CHECK (
    period_type <> 'FISCAL_445' OR period_length_days IN (28, 35)
  );

-- ---------------------------------------------------------------------------
-- 3. finance_accounting_period_event —— 期间事件留痕（append-only）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「这个期间身上发生过的一件事」。
-- 蓝图输出写死「**关账记录**」⇒ 关账那一刻必须在这里留一条，
-- 重开申请 / 重开完成同样留一条 —— 事后能回答「这期关过没有、谁关的、为什么又开了」。
--
-- 🔴 **append-only**：运行角色的 UPDATE / DELETE 已被收回，且有触发器兜底。
CREATE TABLE finance_accounting_period_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  period_id uuid NOT NULL,
  -- CREATED 建期 / CLOSED 关账 / REOPEN_REQUESTED 提交重开申请 /
  -- REOPENED 重开完成 / UPDATED 改了备注等非状态字段。
  event_type varchar(24) NOT NULL CHECK(event_type IN (
    'CREATED','CLOSED','REOPEN_REQUESTED','REOPENED','UPDATED')),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  actor_id uuid,
  -- 重开申请关联的 identity 审批实例（跨服务 ⇒ 无 FK）。
  approval_instance_id uuid,
  -- 审批状态：PENDING / APPROVED / REJECTED；与审批无关的事件为空。
  approval_status varchar(24) NOT NULL DEFAULT '',
  -- 补充说明（如「已提交的审批实例号」「跳过的原因」）。
  detail varchar(1000) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, period_id) REFERENCES finance_voucher_period(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- 4. finance_accounting_period_approval —— 重开审批链路（identity 的本地镜像）
-- ---------------------------------------------------------------------------
--
-- 🔴 **这张表不是审批引擎，是镜像**：真正的审批实例 / 节点 / 动作 / 审批人
-- **全部住在 identity 服务**（17 审批，已交付）。本表存的是「从 identity 读回来的那条链路」。
--
-- 它的**关键作用**是给 DB 守卫当证据：`guard_finance_voucher_period_state`
-- 要求「重开时必须已经有一条 action='APPROVE' 的留痕」——
-- ⇒ **绕过服务层直连 SQL 也重开不了一个没审批的期间**。
--
-- 幂等：按 (approval_instance_id, action_id) 去重 ⇒ 反复同步不灌重复行。
CREATE TABLE finance_accounting_period_approval (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
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
  FOREIGN KEY(tenant_id, period_id) REFERENCES finance_voucher_period(tenant_id, id)
);

-- ===========================================================================
-- 第二节：接管 V357 的期间守卫（照 V350 接管 V346 的同一手法）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 期间状态守卫 —— **换掉** V357 的版本。
-- ---------------------------------------------------------------------------
--
-- V357 那一版的原话是「会计期间**只能关、不能重开**（重开属 12.5.1.2，未交付）」
-- ⇒ 本模块交付了 ⇒ 这条限制**由本模块接管并放宽**：
--
--   * 路径 OPEN → CLOSED（关账）：**一字未改** —— 仍必须写明关闭人 / 时刻 / 原因。
--   * 路径 CLOSED → OPEN（重开）：**新增**，且比「能改回来」强得多 ——
--     必须同时满足四条，缺一不可：
--       ① 重开人 / 重开时刻 / 重开原因 / 审批实例 id 都写明；
--       ② **本地镜像里有这条审批实例的 APPROVE 动作**（DB 层查得到证据）；
--     ⇒ 「没有审批留痕的重开」在数据层写不出来，直连 SQL 也不行。
--   * 其余路径（OPEN → OPEN、CLOSED → CLOSED 之外的乱跳）仍然拒绝。
--
-- 另加两条「期间身份不可改」：
--   * 期间编码 / 起止日 / 本位币 / 会计日历 / fiscal 周期 —— 一经入账不可改
--     （凭证按 period_id 引用它；改了期间区间 = 改了账期）。
--   * 已关闭期间**关账留痕本身**不可再改（只能整体重开）。
--
-- ⚠️ **变量名不能与列名同名**（PL/pgSQL 遇到同名会报「column reference is ambiguous」）。
CREATE OR REPLACE FUNCTION guard_finance_voucher_period_state() RETURNS trigger AS $$
DECLARE
  v_has_approval boolean;
BEGIN
  -- (1) 期间身份字段逐字比对：一条都不许改。
  IF NEW.period_code IS DISTINCT FROM OLD.period_code
     OR NEW.period_start IS DISTINCT FROM OLD.period_start
     OR NEW.period_end IS DISTINCT FROM OLD.period_end
     OR NEW.base_currency IS DISTINCT FROM OLD.base_currency
     OR NEW.calendar_id IS DISTINCT FROM OLD.calendar_id
     OR NEW.fiscal_year IS DISTINCT FROM OLD.fiscal_year
     OR NEW.fiscal_period_no IS DISTINCT FROM OLD.fiscal_period_no
     OR NEW.period_type IS DISTINCT FROM OLD.period_type THEN
    RAISE EXCEPTION '会计期间 % 的期间编码 / 起止日 / 本位币 / 会计日历 / fiscal 周期一经入账不可修改：凭证按期间引用它，改了等于改账期'
      , OLD.period_code
      USING ERRCODE = 'check_violation';
  END IF;

  -- (2) 状态迁移。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF OLD.status = 'OPEN' AND NEW.status = 'CLOSED' THEN
      -- 关账：必须写明关闭人 / 关闭时刻 / 关闭原因（V357 的原话，一字未改）。
      IF NEW.closed_at IS NULL OR NEW.closed_by IS NULL OR btrim(NEW.closed_reason) = '' THEN
        RAISE EXCEPTION '会计期间 % 关闭必须写明关闭人、关闭时刻与关闭原因', OLD.period_code
          USING ERRCODE = 'check_violation';
      END IF;
    ELSIF OLD.status = 'CLOSED' AND NEW.status = 'OPEN' THEN
      -- 重开：高风险 ⇒ 必须审批留痕（蓝图为原话）。
      IF NEW.reopened_at IS NULL OR NEW.reopened_by IS NULL
         OR btrim(NEW.reopen_reason) = '' OR NEW.reopen_approval_instance_id IS NULL THEN
        RAISE EXCEPTION '会计期间 % 不允许重开：重开必须写明重开人、重开时刻、重开原因与审批实例（重开已关期间是高风险操作，必须审批留痕）'
          , OLD.period_code
          USING ERRCODE = 'check_violation';
      END IF;
      -- 🔴 **审批留痕必须在数据层查得到**（本地镜像里这条实例的 APPROVE 动作）。
      SELECT EXISTS (
        SELECT 1
          FROM finance_accounting_period_approval a
         WHERE a.tenant_id = NEW.tenant_id
           AND a.period_id = NEW.id
           AND a.approval_instance_id = NEW.reopen_approval_instance_id
           AND a.action = 'APPROVE'
      ) INTO v_has_approval;
      IF NOT v_has_approval THEN
        RAISE EXCEPTION '会计期间 % 没有已批准的审批留痕，不允许重开：重开已关期间是高风险操作，必须审批留痕'
          , OLD.period_code
          USING ERRCODE = 'check_violation';
      END IF;
    ELSE
      RAISE EXCEPTION '会计期间 % 的状态只能 开放→关闭 或 关闭→开放（重开），% → % 不允许'
        , OLD.period_code, OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF OLD.status = 'CLOSED' AND NEW.status = 'CLOSED' THEN
    -- 已关闭期间：关账留痕本身不可再改（要改只能整体重开）。
    IF NEW.closed_at IS DISTINCT FROM OLD.closed_at
       OR NEW.closed_by IS DISTINCT FROM OLD.closed_by
       OR NEW.closed_reason IS DISTINCT FROM OLD.closed_reason THEN
      RAISE EXCEPTION '会计期间 % 已关闭，关账留痕不可再改：要改请先重开（重开需审批留痕）', OLD.period_code
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- ⚠️ **必须重建触发器**：只换函数体而不重建触发器，触发器与函数就不是一一对应了
--    （V350 接管 V346 时的同一条纪律）。
DROP TRIGGER IF EXISTS finance_voucher_period_state ON finance_voucher_period;

CREATE TRIGGER finance_voucher_period_state
  BEFORE UPDATE ON finance_voucher_period
  FOR EACH ROW EXECUTE FUNCTION guard_finance_voucher_period_state();

-- ---------------------------------------------------------------------------
-- (b) 期间不可删除 —— 凭证按 period_id 引用它，删掉期间账就没了。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_finance_voucher_period_no_delete() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '会计期间 % 不可删除：期间是会计事实，错了只能关账或重开', OLD.period_code
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_voucher_period_no_delete
  BEFORE DELETE ON finance_voucher_period
  FOR EACH ROW EXECUTE FUNCTION guard_finance_voucher_period_no_delete();

-- ---------------------------------------------------------------------------
-- (b-1) 关账 / 重开留痕 —— **由数据层写**，不靠「哪条服务路径碰巧调了它」。
-- ---------------------------------------------------------------------------
--
-- 蓝图输出写死「**关账记录**」⇒ 关账那一刻必须留下记录。
--
-- 🔴 **为什么放在触发器里而不是服务层**：12.5.2.1 的过渡端点
--    `POST /finance/vouchers/periods/{id}/close` 也会关账 —— 它是 12.5.2.1 的文件，
--    本模块**不改**它。若留痕只写在服务层，走那条过渡路径关掉的期间就**没有关账记录**。
--    ⇒ 留痕下沉到数据层：**任何**路径关账 / 重开都会留痕（直连 SQL 也一样）。
--
-- ⚠️ 留痕里写入的是 NEW 上的**事实**（关账人 / 时刻 / 原因 / 重开人 / 时刻 / 原因 /
--    审批实例 id）—— 不是编出来的文案。
CREATE OR REPLACE FUNCTION guard_finance_voucher_period_trail() RETURNS trigger AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status = 'CLOSED' THEN
      INSERT INTO finance_accounting_period_event(
        id, tenant_id, period_id, event_type, from_status, to_status, reason, actor_id,
        approval_instance_id, approval_status, detail, occurred_at)
      VALUES (
        gen_random_uuid(), NEW.tenant_id, NEW.id, 'CLOSED', OLD.status, NEW.status,
        NEW.closed_reason, NEW.closed_by, NULL, '',
        '期间关闭（账期 ' || NEW.period_code || '）', NEW.closed_at);
    ELSIF OLD.status = 'CLOSED' AND NEW.status = 'OPEN' THEN
      INSERT INTO finance_accounting_period_event(
        id, tenant_id, period_id, event_type, from_status, to_status, reason, actor_id,
        approval_instance_id, approval_status, detail, occurred_at)
      VALUES (
        gen_random_uuid(), NEW.tenant_id, NEW.id, 'REOPENED', OLD.status, NEW.status,
        NEW.reopen_reason, NEW.reopened_by, NEW.reopen_approval_instance_id, 'APPROVED',
        '已按审批实例 ' || NEW.reopen_approval_instance_id || ' 重开期间（账期 '
          || NEW.period_code || '）', NEW.reopened_at);
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_voucher_period_trail
  AFTER UPDATE ON finance_voucher_period
  FOR EACH ROW EXECUTE FUNCTION guard_finance_voucher_period_trail();

-- ---------------------------------------------------------------------------
-- (c) 会计日历守卫 —— 一旦有期间引用它，日历的「形状」就不可再改。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_finance_accounting_calendar_state() RETURNS trigger AS $$
BEGIN
  IF NEW.calendar_code IS DISTINCT FROM OLD.calendar_code
     OR NEW.calendar_type IS DISTINCT FROM OLD.calendar_type
     OR NEW.fiscal_year_start_month IS DISTINCT FROM OLD.fiscal_year_start_month
     OR NEW.anchor_date IS DISTINCT FROM OLD.anchor_date
     OR NEW.base_currency IS DISTINCT FROM OLD.base_currency THEN
    IF EXISTS (
      SELECT 1 FROM finance_voucher_period p
       WHERE p.tenant_id = OLD.tenant_id AND p.calendar_id = OLD.id
    ) THEN
      RAISE EXCEPTION '会计日历 % 已被期间引用，日历类型 / 起始月 / 锚点日 / 本位币不可再改：改了等于改已生成的账期'
        , OLD.calendar_code
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_accounting_calendar_state
  BEFORE UPDATE ON finance_accounting_calendar
  FOR EACH ROW EXECUTE FUNCTION guard_finance_accounting_calendar_state();

-- 日历不可删除（有期间引用时由 FK 挡住；这里给出可读的原因）。
CREATE OR REPLACE FUNCTION guard_finance_accounting_calendar_no_delete() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '会计日历 % 不可删除：日历是账期的事实来源，错了请停用', OLD.calendar_code
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_accounting_calendar_no_delete
  BEFORE DELETE ON finance_accounting_calendar
  FOR EACH ROW EXECUTE FUNCTION guard_finance_accounting_calendar_no_delete();

-- ---------------------------------------------------------------------------
-- (d) 期间事件留痕 —— append-only（举证凭据只能追加）。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_finance_accounting_period_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '会计期间事件留痕只能追加，不能修改或删除：它是关账与重开的举证凭据（%s）', OLD.event_type
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_accounting_period_event_append_only
  BEFORE UPDATE OR DELETE ON finance_accounting_period_event
  FOR EACH ROW EXECUTE FUNCTION guard_finance_accounting_period_event_append_only();

-- ===========================================================================
-- 第三节：索引
-- ===========================================================================

-- 期间列表：按状态查（待关账的就在这里）+ 按起始日倒序。
-- ⚠️ V357 已经建过 `finance_voucher_period_by_status(tenant_id, status, period_start DESC)`
--    —— 形状**正好**是本模块要的（按状态查期间），**不重复建**（同名会报 42P07 直接让 Flyway 失败）。
-- 按日历查（一个日历生成的期间）。
CREATE INDEX finance_voucher_period_by_calendar
  ON finance_voucher_period(tenant_id, calendar_id, fiscal_year, fiscal_period_no);
-- 按财年查（报表按财年取数）。
CREATE INDEX finance_voucher_period_by_fiscal_year
  ON finance_voucher_period(tenant_id, fiscal_year, fiscal_period_no);
-- 同一日历同一财年的周期序号唯一（重跑生成不会灌出两个第 3 期）。
CREATE UNIQUE INDEX uq_finance_voucher_period_calendar_seq
  ON finance_voucher_period(tenant_id, calendar_id, fiscal_year, fiscal_period_no)
  WHERE calendar_id IS NOT NULL;
-- 事件流按期间排（关账记录按时间倒序读）。
CREATE INDEX finance_accounting_period_event_by_period
  ON finance_accounting_period_event(tenant_id, period_id, occurred_at DESC, id);
-- 事件流按审批实例查（某次重开申请的全部事件）。
CREATE INDEX finance_accounting_period_event_by_approval
  ON finance_accounting_period_event(tenant_id, approval_instance_id)
  WHERE approval_instance_id IS NOT NULL;
-- 审批镜像按期间排。
CREATE INDEX finance_accounting_period_approval_by_period
  ON finance_accounting_period_approval(tenant_id, period_id, decided_at, id);
-- 同一审批动作只镜像一次（反复同步不灌重复行）。
CREATE UNIQUE INDEX uq_finance_accounting_period_approval_action
  ON finance_accounting_period_approval(tenant_id, approval_instance_id, action_id)
  WHERE action_id IS NOT NULL;
-- 日历按状态查。
CREATE INDEX finance_accounting_calendar_by_status
  ON finance_accounting_calendar(tenant_id, status, calendar_code);

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_accounting_calendar','finance_accounting_period_event',
    'finance_accounting_period_approval'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ⚠️ `finance_voucher_period` 的 RLS 由 V357 建好（ENABLE + FORCE + isolation 策略），
--    本迁移**不重复建策略**（重复建会报「policy already exists」）。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_accounting_calendar, finance_accounting_period_event,
  finance_accounting_period_approval TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_accounting_calendar, finance_accounting_period_event,
  finance_accounting_period_approval TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON finance_voucher_period TO finance_app;
GRANT SELECT,INSERT,UPDATE ON finance_voucher_period TO starcon_app;

-- 「授权在前、收回在后」：
--   * 期间事件留痕是**举证凭据**，只能追加 ⇒ 收回 UPDATE / DELETE。
--   * 审批镜像是**读回来的证据**，只能追加 ⇒ 收回 UPDATE / DELETE。
--   * 期间本身**不可删除** ⇒ 收回 DELETE。
REVOKE UPDATE,DELETE ON finance_accounting_period_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_accounting_period_approval FROM finance_app;
REVOKE DELETE ON finance_voucher_period FROM finance_app;

-- ===========================================================================
-- 第五节：金额与时间口径（写给运维与下游）
-- ===========================================================================

--  * 期间是**日期**概念：period_start / period_end / anchor_date 一律 **date**，
--    不是 timestamptz（期间是一段日历，不是一瞬间）。
--  * 绝对时刻（关闭 / 重开 / 留痕）一律 timestamptz：Java 侧绑 OffsetDateTime
--    + values 位置写 `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--  * 本模块**没有金额**：期间是账期配置，金额在凭证（12.5.2.1）那边。
--    唯一与数字有关的字段是 fiscal_year / fiscal_period_no / period_length_days，都是整数。
--
-- ===========================================================================
-- 第六节：交接面（写给 12.5.1.3 关账 / 12.5.2.2 报表）
-- ===========================================================================

-- ① **12.5.1.3 关账（未交付）—— 检查清单归它**：
--    本表**没有**关账检查清单（阻断项 / 可结转未清项）—— 蓝图明写「关账检查清单是防错
--    最后一道关」，那是 12.5.1.3 的职责。本模块只提供「关账这个动作」与「关账记录」。
--    ⇒ 本模块的关账**不假装**跑过检查清单。
--
-- ② **12.5.2.1 凭证（已交付）—— 结构不用改**：
--    `guard_finance_voucher_post_period`（V357）继续读本表的 `status` 做过账闸门，
--    「已关闭期间禁止过账」**继续生效** ⇒ 本模块**没有**改它一个字。
--    本模块新增的 `reopen_*` 列与 fiscal 列对它**完全透明**（它只读 status / period_code /
--    base_currency）。
--
-- ③ **12.5.2.2 报表（未交付）—— 只提供可钻取的事实**：
--    期间（fiscal 周期 + 状态）+ 已过账凭证 = 报表要的全部输入。本模块**不生成任何报表**。
--
