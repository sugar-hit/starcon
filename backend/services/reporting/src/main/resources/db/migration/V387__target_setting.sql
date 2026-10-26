-- ===========================================================================
-- V387 · target_setting —— 13.1.2.1 目标设定
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:100）：
--
--   ### 13.1.2.1 目标设定
--   - **职责**：设定经营目标：按部门/人员/产品/店铺设定销售额、毛利额、毛利率、广告 ROI、
--     库存周转等指标与周期，支持分解到月/周。
--   - **输入**：目标定义（指标、目标值、周期、归属对象）；历史基线参考。
--   - **输出**：目标记录 + 分解计划。
--   - **关系**：上游 = 17 组织（部门人员）；下游 = 13.1.2.2 达成追踪。
--     **目标要挂在组织对象上**（部门/人/店铺），否则无法与绩效核算对接（行业普遍需求）。
--
-- ---------------------------------------------------------------------------
-- 0. 这两张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_target        —— **目标记录**（指标码 + 目标值 + 币种 + 周期 + 归属对象 + 分解方式）
--   reporting_target_bucket —— **分解计划**（把一个周期内的目标分解到月 / 周）
--
-- ⚠️ **本表不是业务事实，是「经营意图」**：卷 13 卷首写死「不产生业务事实、不做业务写入」。
--   目标是**用户设定的计划**，不是从事件投影出来的读模型 —— 所以它有写入端点
--   （这与本仓其它 reporting 表「只有投影能写」的纪律**刻意不同**，理由见下）。
--
-- ⚠️ **为什么目标要有写端点（与 reporting 其它表的纪律不同）**：
--   报表数字全部可从事件重建，但「这个部门这个季度销售额要达到 100 万」**不是事件**，
--   它是**人定的计划** —— 没有任何上游系统会推这个事件。所以它必须能写。
--   写进去的仍然**不是业务事实**（不产生订单、不改库存、不动钱），只是**一张计划**，
--   下游 13.1.2.2 达成追踪读它、17 绩效核算读它。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 口径唯一：目标**引用**一个已登记的指标码，不自己定义指标
-- ---------------------------------------------------------------------------
--
--   蓝图点名的五个目标指标里，**销售额**在两个维度上是**不同的口径**：
--
--     * 公司 / 部门 / 人员 / 产品维度 → `SALES_AMOUNT`（租户级口径表）
--     * 店铺维度                  → **三个并列口径**：
--         `STORE_GMV_PLATFORM`（平台侧成交金额）
--         `STORE_GMV_RESTORED`（还原口径成交金额）
--         `STORE_CALIBER_GAP`（两者之差）
--       ⇒ **三个都保留**，取其一改名叫「销售额」就是抹掉口径差异（13.1.1.2 的同一结论）。
--
--   ⇒ 因此**目标存的是「引用了哪个口径码」**，而 DB 层用触发器钉死：
--     归属对象是店铺 ⇒ 指标码必须已在 `reporting_store_metric_definition` 登记；
--     归属对象是部门 / 人员 / 产品 ⇒ 指标码必须已在 `reporting_metric_definition` 登记。
--   没登记的指标码**写不进来**（口径唯一，没有登记就没有那个指标）。
--
--   ⚠️ **广告 ROI 与库存周转没有登记口径码**：
--     * 广告 ROI ⇒ 店铺维度可用 `STORE_AD_ROAS`（广告销售额 ÷ 广告花费，就是 ROI 的另一种写法）；
--       公司 / 部门 / 人员 / 产品维度**没有**这个码 ⇒ 报「未登记」，**不拿 AD_SPEND 冒充**。
--     * 库存周转 ⇒ **没有任何周转码**（只有 `INVENTORY_VALUE` 存量口径）。
--       拿「库存总额」冒充「库存周转」是**换一个指标冒充另一个指标** ⇒ 报「未登记」。
--     这两条在服务层的能力声明里**如实列出**（含归属模块），不静默隐藏。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 归属对象：目标必须挂在组织对象上
-- ---------------------------------------------------------------------------
--
--   scope_type 取值：
--     DEPARTMENT —— 部门（identity 的 department）
--     MEMBER     —— 人员（identity 的 membership）
--     SHOP       —— 店铺（channel 的 shop_code，落在本服务的 reporting_store_profile）
--     PRODUCT    —— 产品（catalog 的 SPU）
--
--   ⚠️ **本服务不造一张组织表顶替**：组织本体在 identity（部门 / 成员），产品本体在 catalog。
--     AGENTS.md 明令禁止跨库查表，所以服务层走**授权只读 API** 取清单，只把**快照**
--     （owner_code / owner_name）抄到目标行上 —— 组织改名后历史目标仍认得出是谁
--     （与 1.3.2.2 车道的 `channel_name` 快照同一做法）。
--
--   ⚠️ **快照是「当时的写法」，不是外键**：owner_id 只是标识（uuid 或 shop_code 字符串），
--     本服务**不建跨库外键**（模块边界纪律）。owner_name 会被组织改名影响，
--     所以它是快照语义，页面上标「快照」。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 分解计划：合计必须**精确等于**目标值（数据层钉死）
-- ---------------------------------------------------------------------------
--
--   一个目标（周期内总额）→ 分解到月 / 周 ⇒ 分解项合计必须等于目标值。
--   ⚠️ 「均分后合计 ≠ 目标值」是浮点加法最常见的坑；本仓禁浮点，
--     金额一律 `numeric(24,4)` + Java `BigDecimal`。
--   ⇒ 这里的保证**不靠服务层自觉**，而是靠**延迟约束触发器**在提交时核对：
--     提交那一刻只要「分解项合计 ≠ 目标值」或「一个分解项都没有」，事务直接失败。
--
--   ⚠️ **粒度是受控词表**（不许自由文本）：
--     MONTHLY —— 分解项必须是自然月（首日 / 末日对齐）
--     WEEKLY  —— 分解项必须是 ISO 周（周一 / 周日对齐）
--     两者的对齐规则都写成 **CHECK 约束**，不是服务层的口头约定。
--
--   ⚠️ **权重只表示「怎么分」，不表示「分到多少」**：
--     weight 是相对权重（numeric(19,6)），实际金额由服务层按**最大余额法**算，
--     并把差额（余数）按小数部分从大到小逐个补齐 ⇒ 合计**精确等于**目标值。
--
-- ---------------------------------------------------------------------------
-- 4. 多租户
-- ---------------------------------------------------------------------------
--
--   两张表都是 tenant_id NOT NULL + ENABLE **且 FORCE** ROW LEVEL SECURITY
--   （FORCE 必须：owner 身份一连就会绕过隔离）+ GRANT reporting_app / starcon_app。
--   `reporting_target_bucket` 与 `reporting_target` 之间是**同库**外键，且是
--   **复合外键 (tenant_id, target_id)** ⇒ 桶不可能挂到别的租户的目标上。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_target —— 目标记录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个「谁 / 哪个指标 / 多少 / 什么周期 / 怎么分解」的目标。
--
-- ⚠️ **一个归属对象 + 一个指标 + 一个币种 + 一个周期只能有一个目标**
--   （UNIQUE 约束）：改目标走 UPDATE，不靠「删了再建」—— 否则历史达成追踪会断链。
--   ⚠️ **币种进了唯一键**：不同币种的目标**各存各的**，谁也不许和谁相加。
CREATE TABLE reporting_target (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 引用的**已登记口径码**（不在口径表里的码写不进来，见第二节的触发器）。
  metric_code varchar(48) NOT NULL,
  -- 归属对象类型：部门 / 人员 / 店铺 / 产品（受控词表）。
  scope_type text NOT NULL CHECK(scope_type IN ('DEPARTMENT','MEMBER','SHOP','PRODUCT')),
  -- 归属对象标识：部门 / 成员 / 产品的 uuid，或店铺的 shop_code（业务编码）。
  owner_id varchar(160) NOT NULL CHECK(btrim(owner_id) <> ''),
  -- 归属对象**快照**（组织改名后历史目标仍认得出是谁）。
  owner_code varchar(64) NOT NULL DEFAULT '',
  owner_name varchar(150) NOT NULL DEFAULT '',
  -- 目标币种。**不同币种的目标绝不相加**。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 目标值（周期内总额）。金额一律 numeric，禁浮点。
  target_value numeric(24,4) NOT NULL,
  -- 历史基线快照（设定时从已投影事实里读到的参照值，存成快照便于复盘）。
  baseline_value numeric(24,4),
  baseline_from date,
  baseline_to date,
  -- AVAILABLE 有基线 / NO_DATA 该指标在这个维度还没有事实 / NOT_AVAILABLE 该维度根本没有事实来源。
  baseline_status text NOT NULL CHECK(baseline_status IN ('AVAILABLE','NO_DATA','NOT_AVAILABLE')),
  -- 基线的口径说明（哪一级维度、为什么取不到）。
  baseline_note varchar(300) NOT NULL DEFAULT '',
  -- 目标周期（起止）。**必须与分解粒度对齐**（见下面的 CHECK）。
  period_start date NOT NULL,
  period_end date NOT NULL,
  -- 分解粒度：MONTHLY / WEEKLY（受控词表）。
  granularity text NOT NULL CHECK(granularity IN ('MONTHLY','WEEKLY')),
  -- 分解方式：EVEN 均分 / WEIGHTED 按权重（受控词表）。
  decomposition_method text NOT NULL CHECK(decomposition_method IN ('EVEN','WEIGHTED')),
  -- ACTIVE 生效中 / ARCHIVED 已归档（归档不删行：历史达成追踪还要读它）。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个归属对象 + 一个指标 + 一个币种 + 一个周期只能有一个目标。
  UNIQUE(tenant_id, scope_type, owner_id, metric_code, currency, period_start, period_end),
  CHECK(period_start <= period_end),
  -- 🔴 **周期边界必须与分解粒度对齐**（不许「半个月」这种切不干净的周期）：
  --   MONTHLY ⇒ 起是某月 1 日、止是某月末日（可以是任意整月数）；
  --   WEEKLY  ⇒ 起是 ISO 周周一、止是 ISO 周周日（可以是任意整周数）。
  --
  --   ⚠️ 「某月末日」的判据是「加一天之后正好落在下个月 1 日」——
  --     `date_trunc('month', x) = x` 判的是**月初**（那是 x 是 1 日的写法），
  --     拿它判月末会把 3 月 31 日判成「不是月末」。
  CHECK(
    (granularity = 'MONTHLY'
       AND date_trunc('month', period_start)::date = period_start
       AND (date_trunc('month', period_end + interval '1 day') - interval '1 day')::date = period_end)
    OR (granularity = 'WEEKLY'
       AND extract(isodow from period_start) = 1
       AND extract(isodow from period_end) = 7)
  ),
  -- 🔴 **基线状态与基线值必须一致**：说「有基线」就必须有值，
  --   说「没有基线」就不能挂一个 0 上去（假 0 会被读成「基线是 0」）。
  CHECK((baseline_status = 'AVAILABLE') = (baseline_value IS NOT NULL)),
  -- 有基线就必须有基线窗口（否则复盘时不知道这个数是哪一段的）。
  CHECK(baseline_status <> 'AVAILABLE' OR (baseline_from IS NOT NULL AND baseline_to IS NOT NULL))
);

-- 按归属对象查（绩效核算按人 / 部门汇总时的主路径）。
CREATE INDEX reporting_target_by_owner
  ON reporting_target(tenant_id, scope_type, owner_id, period_start DESC);
-- 按指标查（某个指标的目标全景）。
CREATE INDEX reporting_target_by_metric
  ON reporting_target(tenant_id, metric_code, period_start DESC);
-- 按周期查（达成追踪按周期扫）。
CREATE INDEX reporting_target_by_period
  ON reporting_target(tenant_id, period_start, period_end);

-- ---------------------------------------------------------------------------
-- 2. reporting_target_bucket —— 分解计划
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个分解项（一个月 / 一周的目标金额）。
--
-- ⚠️ **合计必须精确等于目标值** —— 由下面那个**延迟约束触发器**在提交时核对，
--   不靠服务层自觉（见第三节）。
CREATE TABLE reporting_target_bucket (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 所属目标（**同库复合外键**：桶不可能挂到别的租户的目标上）。
  target_id uuid NOT NULL,
  -- 分解项区间。必须与 granularity 对齐（自然月 / ISO 周）。
  bucket_start date NOT NULL,
  bucket_end date NOT NULL,
  granularity text NOT NULL CHECK(granularity IN ('MONTHLY','WEEKLY')),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 该分解项的目标金额。
  target_value numeric(24,4) NOT NULL,
  -- 相对权重（EVEN 恒为 1；WEIGHTED 是用户给的相对权重）。**权重不决定金额**，
  -- 金额由服务层按最大余额法算出来，合计精确等于目标值。
  weight numeric(19,6) NOT NULL DEFAULT 1 CHECK(weight >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一目标的同一个起点只有一个分解项。
  UNIQUE(tenant_id, target_id, bucket_start),
  -- 🔴 **同库复合外键**：跨租户挂靠在数据层就不可能。
  FOREIGN KEY (tenant_id, target_id)
    REFERENCES reporting_target(tenant_id, id) ON DELETE CASCADE,
  CHECK(bucket_start <= bucket_end),
  -- 🔴 **分解项边界必须与粒度对齐**：
  --   MONTHLY ⇒ 自然月（1 日 / 月末）；WEEKLY ⇒ ISO 周（周一 / 周日）。
  CHECK(
    (granularity = 'MONTHLY'
       AND date_trunc('month', bucket_start)::date = bucket_start
       AND (date_trunc('month', bucket_end + interval '1 day') - interval '1 day')::date = bucket_end
       AND date_trunc('month', bucket_start) = date_trunc('month', bucket_end))
    OR (granularity = 'WEEKLY'
       AND extract(isodow from bucket_start) = 1
       AND bucket_end = bucket_start + 6)
  )
);

-- 读分解计划用：租户 + 目标 + 起点。
CREATE INDEX reporting_target_bucket_by_target
  ON reporting_target_bucket(tenant_id, target_id, bucket_start);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 🔴 **目标引用的指标码必须已登记**（口径唯一）：
--     店铺维度 ⇒ 必须已在店铺对比口径表 `reporting_store_metric_definition`；
--     部门 / 人员 / 产品维度 ⇒ 必须已在租户口径表 `reporting_metric_definition`。
--
--     ⚠️ 触发器是 SECURITY INVOKER（默认），所以它内部那条 SELECT **同样受 RLS 约束**
--     ⇒ 跨租户的指标码互相看不见，写不进来。
CREATE OR REPLACE FUNCTION guard_reporting_target_metric_known() RETURNS trigger AS $$
BEGIN
  IF NEW.scope_type = 'SHOP' THEN
    IF NOT EXISTS (
      SELECT 1 FROM reporting_store_metric_definition
       WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
      RAISE EXCEPTION '指标码 % 尚未在店铺对比口径表（reporting_store_metric_definition）登记，不能作为店铺目标', NEW.metric_code
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在口径表（reporting_metric_definition）登记，不能作为目标', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_target_metric_known
  BEFORE INSERT OR UPDATE ON reporting_target
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_target_metric_known();

-- (b) 🔴 **分解项合计必须精确等于目标值**，且**至少要有一个分解项**。
--
--     ⚠️ 用的是**延迟约束触发器**（DEFERRABLE INITIALLY DEFERRED）：
--       它在**提交那一刻**才核对 —— 这样「先插目标、再插分解项」这种正常写法不会中途报错，
--       而只要提交时合计对不上，**整个事务失败**（数据层兜底，不靠服务层自觉）。
--
--     ⚠️ 目标被删除时（级联删掉分解项）不校验：目标行已经不在了（下面 NOT FOUND 分支）。
CREATE OR REPLACE FUNCTION guard_reporting_target_decomposed() RETURNS trigger AS $$
DECLARE
  v_tenant uuid;
  v_target uuid;
  target_row reporting_target%ROWTYPE;
  bucket_sum numeric(24,4);
  bucket_count int;
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_tenant := OLD.tenant_id;
    v_target := OLD.id;
  ELSE
    v_tenant := NEW.tenant_id;
    v_target := NEW.id;
  END IF;

  SELECT * INTO target_row FROM reporting_target WHERE tenant_id = v_tenant AND id = v_target;
  IF NOT FOUND THEN
    RETURN NULL; -- 目标已删除（级联删分解项）⇒ 不再校验
  END IF;

  SELECT coalesce(sum(target_value), 0), count(*)
    INTO bucket_sum, bucket_count
    FROM reporting_target_bucket
   WHERE tenant_id = v_tenant AND target_id = v_target;

  IF bucket_count = 0 THEN
    RAISE EXCEPTION '目标 % 必须有分解项（分解到月 / 周）', v_target
      USING ERRCODE = 'check_violation';
  END IF;
  IF bucket_sum <> target_row.target_value THEN
    RAISE EXCEPTION '目标 % 的分解项合计 % 与目标值 % 不一致', v_target, bucket_sum, target_row.target_value
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER reporting_target_decomposed
  AFTER INSERT OR UPDATE OR DELETE ON reporting_target
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_target_decomposed();

-- (c) 🔴 同一套核对挂在分解项上（改分解项 / 删分解项同样要过合计这一关）。
CREATE OR REPLACE FUNCTION guard_reporting_target_bucket_sum() RETURNS trigger AS $$
DECLARE
  v_tenant uuid;
  v_target uuid;
  target_row reporting_target%ROWTYPE;
  bucket_sum numeric(24,4);
  bucket_count int;
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_tenant := OLD.tenant_id;
    v_target := OLD.target_id;
  ELSE
    v_tenant := NEW.tenant_id;
    v_target := NEW.target_id;
  END IF;

  SELECT * INTO target_row FROM reporting_target WHERE tenant_id = v_tenant AND id = v_target;
  IF NOT FOUND THEN
    RETURN NULL; -- 目标已删除（级联删分解项）⇒ 不再校验
  END IF;

  SELECT coalesce(sum(target_value), 0), count(*)
    INTO bucket_sum, bucket_count
    FROM reporting_target_bucket
   WHERE tenant_id = v_tenant AND target_id = v_target;

  IF bucket_count = 0 THEN
    RAISE EXCEPTION '目标 % 必须有分解项（分解到月 / 周）', v_target
      USING ERRCODE = 'check_violation';
  END IF;
  IF bucket_sum <> target_row.target_value THEN
    RAISE EXCEPTION '目标 % 的分解项合计 % 与目标值 % 不一致', v_target, bucket_sum, target_row.target_value
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER reporting_target_bucket_sum
  AFTER INSERT OR UPDATE OR DELETE ON reporting_target_bucket
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_target_bucket_sum();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_target',
    'reporting_target_bucket'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_target,
  reporting_target_bucket TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_target,
  reporting_target_bucket TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.1.2.2 达成追踪（未开发）**：它要的「目标记录 + 分解计划」就是这两张表
--     （`reporting_target` + `reporting_target_bucket`），**不需要改结构**。
--     它接上时要按**归属对象维度**取实际值 —— 而实际值投影目前只到公司 / 店铺维度
--     （部门 / 人员 / 产品维度的实际值**还没有投影**），那一格它会如实报「不可得」。
--   * **17 绩效核算（未开发）**：绩效核算按部门 / 人员汇总目标达成，
--     读 `reporting_target` 的 `scope_type` + `owner_id` + `currency` 即可。
--     本模块**只提供目标与分解计划**，不算绩效、不改人事。
