-- ===========================================================================
-- 16.3.1.2 申报日历 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md）：
--   ### 16.3.1.2 申报日历
--   - 职责：管理各辖区申报日历：申报周期、截止日、所需数据、状态
--           （未开始/数据就绪/已提交/已确认），到期提醒与责任人。
--   - 输入：申报周期配置；辖区要求；12.6.1.2 数据就绪状态。
--   - 输出：申报日历视图 + 提醒 + 状态跟踪。
--   - 关系：上游 = 16.3.1.1、12.6.1.2；下游 = 1 工作台待办。
--           **漏报罚款远高于系统能提供的价值**，日历提醒是最低成本的合规保障。
--
-- ---------------------------------------------------------------------------
-- 零、这一批迁移的边界（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **纯新建三张表**：filing_calendar_cycle_config / filing_calendar_entry /
--     filing_calendar_reminder。**零 ALTER、零 DROP、零 ADD COLUMN 到任何既有表**
--     （尤其不动 16.3.1.1 的 tax_registration_jurisdiction / tax_registration_record）。
--   * **零 FOREIGN KEY**：跨表引用一律走触发器（与 V312 / V368 同一纪律）。
--   * **辖区不另建码表**：辖区码的真值源是 16.3.1.1 的 `tax_registration_jurisdiction`
--     （全局码表，本库内）。本模块只**引用**它，由触发器钉死 ⇒ 辖区码不是自由文本。
--     本模块**不复制**那张表的任何一行。
--   * **不跨库读 finance**：12.6.1.2 的申报底稿在 **finance 服务**（另一个库），
--     只经授权只读 HTTP API（`GET /finance/filing-draft/drafts`）读。
--     本迁移**不建**任何指向 finance 的外键 / 视图 / 影子表。
--
-- ---------------------------------------------------------------------------
-- 一、三张表的职责切分
-- ---------------------------------------------------------------------------
--   filing_calendar_cycle_config  申报周期配置：辖区 × 税种 × 周期 ⇒ 期起始日、
--                                 截止偏移、提醒提前天数。**一份模板**。
--   filing_calendar_entry         申报日历条目：某辖区某税种某申报期的**那一次申报**：
--                                 截止日（快照）、所需数据、状态、责任人。
--   filing_calendar_reminder      到期提醒：由条目派生的提醒行（提前提醒 / 截止提醒），
--                                 可被确认已知悉。
--
--   「配置」与「条目」分两张表的理由：配置是**模板**（会改），条目是**那一次申报的
--   事实**（截止日在生成时**快照**进条目）。改配置不改历史条目 —— 否则「上个月到底
--   哪天截止」会被今天的配置改写，而那正是稽查时要看的东西。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 「本模块不执行申报」落成结构而不是文案
-- ---------------------------------------------------------------------------
--   蓝图 12.6.1.2 写死「本系统提供申报底稿，**不替代税务申报动作**」；本模块同理：
--   **日历不向任何税务机构提交申报**，只**跟踪**状态。
--   ⇒ status 是受控词表（NOT_STARTED / DATA_READY / SUBMITTED / CONFIRMED），
--     推进到 SUBMITTED / CONFIRMED 时**必须带依据**（谁、何时、凭据号），
--     DB 触发器钉死「没有依据就推不到已提交」—— 界面上的「已提交」必须能追到凭据。
--   ⇒ 本表**没有**任何「受理号由系统生成」的语义：submission_reference /
--     confirmation_reference 是**用户填的**外部凭据号，系统不代造、不代发。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 状态是受控词表 + 单调（不许倒退）
-- ---------------------------------------------------------------------------
--   状态推进：NOT_STARTED → DATA_READY → SUBMITTED → CONFIRMED。
--   触发器钉死 **rank 不许下降**：日历是用来盯「有没有漏报」的，
--   状态倒退 = 漏报被悄悄抹掉。
--
--   🔴 「数据就绪」是**投影**而不是抄一份底稿：
--     `data_ready` / `data_ready_draft_id` / `data_ready_checked_at` 记录的是
--     **12.6.1.2 当前**有没有一份生效中的底稿（12.6.1.2 保证同时只有一份生效底稿，
--     状态 GENERATED 有效、SUPERSEDED 失效）。
--     **本表不存任何金额**：底稿的金额留在 finance，本表只存**状态**。
--     抄一份金额进来 = 两个真值源 = 迟早不一致。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 责任人 = 租户内成员（跨服务软引用）
-- ---------------------------------------------------------------------------
--   `responsible_membership_id` 引用 identity 的 `membership.id`
--   （**跨库，不建外键**）。落库前由服务层经授权只读 API 校验它确实是**本租户**的成员；
--   校验得到才有值，校验不到 ⇒ 拒绝落库（不存一个查无此人的 id）。
--   成员姓名**不复制**到本表（与 16.3.1.1 不复制法人名称同一纪律）——
--   显示时经授权只读端口现读，读不到就显示「读不到」。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 一、filing_calendar_cycle_config · 申报周期配置
-- ---------------------------------------------------------------------------
-- 🔴 一份「辖区 × 税种 × 周期」的模板。配置**不含日期**（日期在条目上），
--   只含「怎么算」：期起始日（几号）、期长（由 filing_cycle 决定）、
--   截止偏移（期末后 N 天）、截止时刻、提醒提前天数。
CREATE TABLE filing_calendar_cycle_config (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 辖区码：必须真实落在 16.3.1.1 的 tax_registration_jurisdiction 里（触发器钉死）
  jurisdiction_code varchar(16) NOT NULL CHECK(btrim(jurisdiction_code) <> ''),
  -- 申报线（税种）：与 12.6.1.1 的税种词表同族，但**不含 WITHHOLDING**
  -- （代扣代缴不是一条申报线）。
  tax_type          varchar(16) NOT NULL
                    CHECK(tax_type IN ('VAT','IOSS','SALES_TAX','GST','CONSUMPTION_TAX')),
  -- 申报周期（月 / 季 / 半年 / 年）
  filing_cycle      varchar(16) NOT NULL
                    CHECK(filing_cycle IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
  -- 🔴 期起始日（几号开始一个申报期，1..28）。
  --   刻意**不允许 29/30/31**：短月没有那一天，配置就会在某些月份算不出期。
  period_anchor_day int NOT NULL CHECK(period_anchor_day BETWEEN 1 AND 28),
  -- 截止偏移：截止日 = 期末 + N 天（0..120）。0 = 期末当天截止。
  due_offset_days   int NOT NULL DEFAULT 10 CHECK(due_offset_days BETWEEN 0 AND 120),
  -- 截止时刻（0..23，配合 due_timezone 解释）
  due_hour          int NOT NULL DEFAULT 23 CHECK(due_hour BETWEEN 0 AND 23),
  -- 🔴 截止时刻的时区（IANA 时区名，如 'Europe/Berlin'）。
  --   截止日是一个**当地时刻**（欧盟各国截止时间都是当地时间），
  --   存时区名才能算出正确的瞬时；写死 UTC 会差几小时。
  due_timezone      varchar(64) NOT NULL DEFAULT 'UTC' CHECK(btrim(due_timezone) <> ''),
  -- 提前提醒天数（0..180）：截止日前 N 天推一条「即将到期」提醒。
  reminder_lead_days int NOT NULL DEFAULT 7 CHECK(reminder_lead_days BETWEEN 0 AND 180),
  -- 从哪天起按这份配置生成申报期
  effective_from    date NOT NULL,
  -- ACTIVE = 生效中；ARCHIVED = 撤下（**不做物理删除**，与 16.3.1.1 同一口径）
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','ARCHIVED')),
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL,
  updated_at        timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  -- 一个（辖区 × 税种 × 周期）只有一份配置：改就是改这一行，不是插第二条
  UNIQUE(tenant_id, jurisdiction_code, tax_type, filing_cycle)
);
COMMENT ON TABLE filing_calendar_cycle_config IS
  '16.3.1.2 · 申报周期配置（辖区 × 税种 × 周期）。**模板**：怎么算期、怎么算截止日、'
  '提前几天提醒。真正的截止日**快照**在 filing_calendar_entry 上 —— 改配置不改历史条目。';

CREATE INDEX filing_calendar_cycle_config_jurisdiction
  ON filing_calendar_cycle_config(tenant_id, jurisdiction_code, tax_type);
CREATE INDEX filing_calendar_cycle_config_status
  ON filing_calendar_cycle_config(tenant_id, status);

-- ---------------------------------------------------------------------------
-- 二、filing_calendar_entry · 申报日历条目
-- ---------------------------------------------------------------------------
-- 🔴 一行 = 某辖区某税种某申报期的**那一次申报**。
--   ⚠️ 刻意**不含金额列**：申报底稿的金额在 finance（12.6.1.2），
--      本表只存**状态**与**元数据**。抄金额 = 两个真值源。
CREATE TABLE filing_calendar_entry (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  jurisdiction_code varchar(16) NOT NULL CHECK(btrim(jurisdiction_code) <> ''),
  tax_type          varchar(16) NOT NULL
                    CHECK(tax_type IN ('VAT','IOSS','SALES_TAX','GST','CONSUMPTION_TAX')),
  filing_cycle      varchar(16) NOT NULL
                    CHECK(filing_cycle IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
  -- 申报期（左闭右闭，日历日）。**条目必须自带期间** —— 没说清期间的申报义务没法核。
  period_start      date NOT NULL,
  period_end        date NOT NULL,
  CHECK(period_end >= period_start),
  -- 🔴 截止日：**timestamptz**（不是 timestamp）——
  --   用 Timestamp.valueOf(LocalDateTime...) 绑值会静默偏 −28800s，页面上看不出异常。
  --   截止日不得早于申报期开始（截止日在期开始之前 = 口径就是错的）。
  due_at            timestamptz NOT NULL,
  -- 🔴 截止日的**当地时区**（IANA 名，如 'Europe/Berlin'；固定偏移写成 '+02:00'）。
  --   截止日是一个**当地时刻**（欧盟各国截止时间都是当地时间），存时区名才能算出
  --   正确的瞬时；写死 UTC 会差几小时，而差几小时就足以错过一个截止日。
  --   同时它也是**显示口径**：页面上要显示「2026-07-10 23:00（Europe/Berlin）」，
  --   而不是把瞬时换算成浏览器时区 —— 那会让一个上海用户看到德国的截止时间被改掉。
  due_timezone      varchar(64) NOT NULL DEFAULT 'UTC' CHECK(btrim(due_timezone) <> ''),
  -- 🔴 状态：受控词表，触发器钉死**单调**（不许倒退）
  status            varchar(16) NOT NULL DEFAULT 'NOT_STARTED'
                    CHECK(status IN ('NOT_STARTED','DATA_READY','SUBMITTED','CONFIRMED')),
  -- 状态推进到当前档的时刻
  status_advanced_at timestamptz NOT NULL,
  -- 🔴 所需数据（JSONB 数组）：[{"code","label","required","source"}]
  --   —— 「这次申报要凑齐哪些数据」是**这个条目自己的**口径，不是一条全局常量。
  required_data     jsonb NOT NULL DEFAULT '[]'::jsonb
                    CHECK(jsonb_typeof(required_data) = 'array'),
  -- 🔴 数据就绪（**投影**，不是抄一份底稿）：
  --   data_ready = 该辖区该周期当前**存在一份生效中的底稿**（12.6.1.2 status='GENERATED'）。
  --   data_readiness_available = 上游**读到了吗**（false ⇒ 显示「读不到」，**不是**「没就绪」）。
  data_ready        boolean NOT NULL DEFAULT false,
  data_ready_draft_id uuid,
  data_ready_checked_at timestamptz,
  data_readiness_available boolean NOT NULL DEFAULT false,
  data_readiness_note text NOT NULL DEFAULT '',
  -- 🔴 责任人：identity 的 membership 逻辑 ID（**跨库软引用**，落库前校验，不建外键）
  responsible_membership_id uuid,
  -- 提前提醒天数（0..180）：从配置快照下来，改配置不改历史条目
  reminder_lead_days int NOT NULL DEFAULT 7 CHECK(reminder_lead_days BETWEEN 0 AND 180),
  -- 🔴 「已提交」的**依据**（谁 / 何时 / 凭据号）：触发器钉死「没有依据就推不到已提交」
  submitted_at      timestamptz,
  submitted_by      uuid,
  submission_reference varchar(128),
  -- 🔴 「已确认」的依据
  confirmed_at      timestamptz,
  confirmed_by      uuid,
  confirmation_reference varchar(128),
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL,
  updated_at        timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  -- 同一辖区 × 税种 × 周期 × 期间**只有一条**申报义务（重录是改这一行，不是插第二条）
  UNIQUE(tenant_id, jurisdiction_code, tax_type, filing_cycle, period_start, period_end)
);
COMMENT ON TABLE filing_calendar_entry IS
  '16.3.1.2 · 申报日历条目（辖区 × 税种 × 申报期）。'
  '🔴 本表**不含任何金额** —— 申报底稿的金额留在 finance 12.6.1.2，本表只存**状态**。'
  'status 是受控词表且**单调**（触发器钉死不许倒退）。'
  '推进到 SUBMITTED / CONFIRMED 必须带依据（谁 / 何时 / 凭据号）：'
  '**本系统不代用户向税务机构提交申报**，只跟踪状态，不代造受理号。';

CREATE INDEX filing_calendar_entry_jurisdiction
  ON filing_calendar_entry(tenant_id, jurisdiction_code, tax_type, period_start DESC);
-- 日历视图的默认排序键：未提交且临近截止的排在最前（漏报是高代价事件）
CREATE INDEX filing_calendar_entry_due
  ON filing_calendar_entry(tenant_id, status, due_at);
CREATE INDEX filing_calendar_entry_status
  ON filing_calendar_entry(tenant_id, status, due_at);

-- ---------------------------------------------------------------------------
-- 三、filing_calendar_reminder · 到期提醒
-- ---------------------------------------------------------------------------
-- 🔴 提醒是**派生行**（由条目派生），但**持久化**：确认已知悉（acknowledged）
--   是一条**事实**（谁在什么时候知道了），不能每次读都重新算一遍。
--   kind 只有两档：提前提醒（DUE_SOON）与截止当天（DUE）。
--   「已逾期」不是一行 —— 逾期是**当前时刻与截止日的关系**，读时现算（逾期判定在服务端）。
CREATE TABLE filing_calendar_reminder (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 条目（**同库软引用**：触发器钉死必须存在且同租户）
  entry_id          uuid NOT NULL,
  kind              varchar(16) NOT NULL CHECK(kind IN ('DUE_SOON','DUE')),
  remind_at         timestamptz NOT NULL,
  -- 确认已知悉（谁 / 何时）
  acknowledged_at   timestamptz,
  acknowledged_by   uuid,
  created_at        timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  -- 一个条目同一档提醒只有一行（改截止日会重建这两行）
  UNIQUE(tenant_id, entry_id, kind)
);
COMMENT ON TABLE filing_calendar_reminder IS
  '16.3.1.2 · 到期提醒（由申报条目派生的两行：提前提醒 / 截止提醒）。'
  'acknowledged 是**事实**（谁在什么时候知道了）；「是否已逾期」不在这里落库 —— '
  '逾期是当前时刻与截止日的关系，读时现算。';

CREATE INDEX filing_calendar_reminder_due
  ON filing_calendar_reminder(tenant_id, acknowledged_at, remind_at);

-- ===========================================================================
-- 四、触发器（跨表引用一律走触发器，零外键）
-- ===========================================================================

-- 🔴 (a) 辖区码必须真实存在（**不留自由文本**的物理落点）。
--     真值源是 16.3.1.1 的 tax_registration_jurisdiction（全局码表，本库内）。
CREATE FUNCTION guard_filing_calendar_jurisdiction_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM tax_registration_jurisdiction j WHERE j.code = NEW.jurisdiction_code) THEN
    RAISE EXCEPTION
      'Filing calendar jurisdiction % is not a known jurisdiction code', NEW.jurisdiction_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER filing_calendar_cycle_config_jurisdiction_ref
  BEFORE INSERT OR UPDATE ON filing_calendar_cycle_config
  FOR EACH ROW EXECUTE FUNCTION guard_filing_calendar_jurisdiction_ref();
CREATE TRIGGER filing_calendar_entry_jurisdiction_ref
  BEFORE INSERT OR UPDATE ON filing_calendar_entry
  FOR EACH ROW EXECUTE FUNCTION guard_filing_calendar_jurisdiction_ref();

-- 🔴 (b) 提醒必须指向**同租户**真实存在的条目（零外键的跨表引用校验）。
CREATE FUNCTION guard_filing_calendar_entry_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM filing_calendar_entry e
       WHERE e.id = NEW.entry_id AND e.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION
      'Filing calendar reminder references an entry that does not exist in this tenant: %',
      NEW.entry_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER filing_calendar_entry_ref
  BEFORE INSERT OR UPDATE ON filing_calendar_reminder
  FOR EACH ROW EXECUTE FUNCTION guard_filing_calendar_entry_ref();

-- 🔴 (c) 状态**单调**：rank 不许下降。
--     日历是用来盯「有没有漏报」的；状态倒退 = 漏报被悄悄抹掉。
--     同时钉死「没有依据就推不到已提交 / 已确认」。
CREATE FUNCTION guard_filing_calendar_status_monotonic() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  old_rank int;
  new_rank int;
BEGIN
  old_rank := CASE OLD.status
                WHEN 'NOT_STARTED' THEN 0
                WHEN 'DATA_READY'   THEN 1
                WHEN 'SUBMITTED'    THEN 2
                WHEN 'CONFIRMED'    THEN 3
                ELSE 0
              END;
  new_rank := CASE NEW.status
                WHEN 'NOT_STARTED' THEN 0
                WHEN 'DATA_READY'   THEN 1
                WHEN 'SUBMITTED'    THEN 2
                WHEN 'CONFIRMED'    THEN 3
                ELSE 0
              END;
  IF new_rank < old_rank THEN
    RAISE EXCEPTION
      'Filing calendar status cannot move backwards (from % to %) on entry %',
      OLD.status, NEW.status, OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'SUBMITTED'
     AND (NEW.submitted_at IS NULL OR NEW.submitted_by IS NULL
          OR btrim(coalesce(NEW.submission_reference,'')) = '') THEN
    RAISE EXCEPTION
      'Filing calendar entry % cannot be marked SUBMITTED without evidence (who / when / reference)',
      OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'CONFIRMED'
     AND (NEW.confirmed_at IS NULL OR NEW.confirmed_by IS NULL) THEN
    RAISE EXCEPTION
      'Filing calendar entry % cannot be marked CONFIRMED without evidence (who / when)', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER filing_calendar_status_monotonic
  BEFORE UPDATE ON filing_calendar_entry
  FOR EACH ROW EXECUTE FUNCTION guard_filing_calendar_status_monotonic();

-- 🔴 (d) 截止日不得早于申报期开始（口径自洽）。
CREATE FUNCTION guard_filing_calendar_due_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.due_at AT TIME ZONE 'UTC')::date < NEW.period_start THEN
    RAISE EXCEPTION
      'Filing calendar entry % has a due date before its own period starts', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER filing_calendar_due_at
  BEFORE INSERT OR UPDATE ON filing_calendar_entry
  FOR EACH ROW EXECUTE FUNCTION guard_filing_calendar_due_at();

-- 🔴 (e) 撤下的周期配置**不可再改内容**（与 16.3.1.1 的 ARCHIVED 不可变同一口径）。
CREATE FUNCTION guard_filing_calendar_config_archived_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'ARCHIVED' AND (
       NEW.status IS DISTINCT FROM OLD.status
    OR NEW.jurisdiction_code IS DISTINCT FROM OLD.jurisdiction_code
    OR NEW.tax_type IS DISTINCT FROM OLD.tax_type
    OR NEW.filing_cycle IS DISTINCT FROM OLD.filing_cycle
    OR NEW.period_anchor_day IS DISTINCT FROM OLD.period_anchor_day
    OR NEW.due_offset_days IS DISTINCT FROM OLD.due_offset_days
    OR NEW.due_hour IS DISTINCT FROM OLD.due_hour
    OR NEW.due_timezone IS DISTINCT FROM OLD.due_timezone
    OR NEW.reminder_lead_days IS DISTINCT FROM OLD.reminder_lead_days
    OR NEW.effective_from IS DISTINCT FROM OLD.effective_from) THEN
    RAISE EXCEPTION
      'Filing calendar cycle config % is archived; its configuration cannot be rewritten', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER filing_calendar_config_archived_immutable
  BEFORE UPDATE ON filing_calendar_cycle_config
  FOR EACH ROW EXECUTE FUNCTION guard_filing_calendar_config_archived_immutable();

-- ===========================================================================
-- 五、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
-- 🔴 三张表都带 tenant_id NOT NULL，policy 只认 app.current_tenant。
--   FORCE 是必须的：表 owner 在 FORCE 下也受策略约束。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['filing_calendar_cycle_config','filing_calendar_entry',
                             'filing_calendar_reminder'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 六、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON filing_calendar_cycle_config TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON filing_calendar_entry TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON filing_calendar_reminder TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到本模块的业务表（与 V1..V365 同口径）。
REVOKE ALL ON filing_calendar_cycle_config FROM starcon_app;
REVOKE ALL ON filing_calendar_entry FROM starcon_app;
REVOKE ALL ON filing_calendar_reminder FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- 🔴 **序列授权**：本迁移的三张表**全部用应用侧生成的 uuid 作主键**，
--   没有任何 serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 申报日历属于「平台操作 / 合规台账」语义，
-- 与 16.3.1.1 税号与辖区、16.3.2.3 销售税同族，复用 identity 已注册的
-- channel.operation.*（读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------
