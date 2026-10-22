-- ===========================================================================
-- V306 · 15.4.1.1 工单列表（crm 服务）
-- ===========================================================================
--
-- 蓝图 15-crm-and-service.md 第 190–195 行原文：
--
--   ### 15.4.1.1 工单列表
--   - 职责：客服工单列表：类型、优先级、状态、SLA 剩余、关联买家与订单、分配客服、转派与升级。
--   - 输入：工单创建（自动/人工）；筛选条件。
--   - 输出：工单列表 + SLA 预警。
--   - 关系：上游 = 15.1.1.3、11.4.2.1；下游 = 15.4.1.2 处理。
--           **SLA 计时需按营业时间而非自然时间**，否则夜间积压会造成误判。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块**不建工单表**：工单的事实来源在 trade 服务
-- ---------------------------------------------------------------------------
-- 工单（`aftersales_service_ticket` 及其转派 / 事件 / 评价 / SLA 快照四张附属事实表）
-- 由 **11.4.2.1 工单管理**（trade 服务，V270）拥有，是那张表的**唯一写入所有者**。
--
-- AGENTS.md 模块边界纪律：**禁止跨服务 SQL / 跨库 FK / 共享视图**。
--   ⇒ 本服务**一条 SQL 都不碰** trade 的库；
--   ⇒ 本服务经 **trade 的授权 HTTP API** 只读（`GET /trade/service-tickets` 等），
--     转发的是**调用方自己的 Authorization bearer**，租户由 trade 侧按已认证成员关系解析。
--
-- **为什么这里仍然要建表**：因为蓝图加粗句「SLA 计时需按营业时间而非自然时间」。
--   营业时间口径（工作日区间 + 时区 + 节假日例外）是**本租户自己的配置**，
--   它的真身必须在**本服务自己的配置表**里 —— 它既不属于 trade 的工单事实，
--   也不属于 channel 的店铺目录，而是「这家店几点在上班」这条**租户经营口径**。
--   把口径写死成常量 = 每个租户都得迁就同一张时刻表，
--   而 SLA 达标直接决定客服绩效口径。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 库里**不存**会自己过期的「剩余小时数」
-- ---------------------------------------------------------------------------
-- 工单的 SLA 剩余是**读取时刻的派生态**：库里一旦落一列「还剩 3.7 小时」，
-- 它就开始自己过期（没人会去更新它），而 SLA 剩余直接决定客服绩效口径。
--   ⇒ 本迁移**没有任何** remaining_seconds / remaining_hours 列；
--   ⇒ 剩余秒数在**读取那一刻**按营业时间轴现算，随响应返回。
--   （与 trade 侧 `ServiceTicketController` 的同一口径注释逐字对齐。）
--
-- ---------------------------------------------------------------------------
-- 三、营业时间口径的三张表
-- ---------------------------------------------------------------------------
-- 1. crm_service_business_hours   —— 每周营业时段（工作日区间 + 时区）
-- 2. crm_service_business_holiday  —— 节假日例外（整日关闭 / 特例开放）
-- 3. crm_service_business_hours_audit —— 配置变更留痕（append-only，「可审阅」的落点）
--
-- **作用域（scope_key）**：'DEFAULT' = 租户默认；'SHOP:<uuid>' = 单个店铺。
--   店铺维度来自 **CRM 本服务自己的** crm_message.shop_id（买家消息落在哪个店）。
--   ⚠️ 一个 scope 内的所有时段**必须共用同一个时区**（触发器钉死）：
--     混用两个时区会让「营业时间轴」本身没有唯一定义，
--     而 SLA 到期时刻是**在这条轴上累加**出来的 —— 轴不唯一，到期时刻就不唯一。
--
-- **工作日编号采用 ISO-8601**：1 = 周一 … 7 = 周日。
--
-- ---------------------------------------------------------------------------
-- 四、触发器（**一律不用外键** —— 理由见第八节）
-- ---------------------------------------------------------------------------
--
-- ===========================================================================
-- 1. crm_service_business_hours · 每周营业时段
-- ===========================================================================
CREATE TABLE crm_service_business_hours (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 作用域：DEFAULT（租户默认） / SHOP:<uuid>（单店）。
  scope_key varchar(64) NOT NULL CHECK (
    scope_key = 'DEFAULT'
    OR scope_key ~ '^SHOP:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  ),

  -- ISO-8601：1 = 周一 … 7 = 周日。
  weekday smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),

  -- 营业区间（左闭右开）。⚠️ 同一天**可以有多段**（午休要能表达出来），
  --   所以唯一键里带 start_time 而不是只按 weekday 唯一。
  start_time time NOT NULL,
  end_time time NOT NULL,
  CHECK (start_time < end_time),

  -- 本 scope 的时区（IANA 名，如 Asia/Shanghai / America/Los_Angeles）。
  -- ⚠️ 同一个 scope 的所有行**必须同一个时区**（触发器 4.1 钉死）。
  timezone varchar(64) NOT NULL CHECK (btrim(timezone) <> ''),

  -- 停用的时段不参与计时（租户可以把某个工作日临时关掉）。
  enabled boolean NOT NULL DEFAULT true,

  -- 谁改的（已认证成员，由服务端从 AccessService.Access 取，不接受客户端传值）。
  created_by uuid,
  updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 同一 scope 同一工作日的同一段起点不许重复（幂等重放会撞这里）。
  UNIQUE (tenant_id, scope_key, weekday, start_time)
);

-- 读一个 scope 的全部时段（列表页与计时计算都走这条）。
CREATE INDEX crm_service_business_hours_by_scope
  ON crm_service_business_hours(tenant_id, scope_key, weekday, start_time);

-- ===========================================================================
-- 2. crm_service_business_holiday · 节假日例外
-- ===========================================================================
-- ⚠️ **例外优先于每周时段**：节假日当天整日不计时，
--   否则圣诞夜的积压会在第二天上班时全部变成「逾期」。
--   closed=false 的行表示「这天照常营业」（调休上班日）—— 所以节假日表
--   表达的是**例外**，不是「非节假日」。
CREATE TABLE crm_service_business_holiday (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  scope_key varchar(64) NOT NULL CHECK (
    scope_key = 'DEFAULT'
    OR scope_key ~ '^SHOP:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  ),

  -- 例外日期（当地日期；按 scope 的时区解释）。
  holiday_date date NOT NULL,
  label varchar(200) NOT NULL DEFAULT '',

  -- true = 整日关闭；false = 照常营业（调休上班日）。
  closed boolean NOT NULL DEFAULT true,

  created_by uuid,
  updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 同一 scope 同一天只有一条例外。
  UNIQUE (tenant_id, scope_key, holiday_date)
);

CREATE INDEX crm_service_business_holiday_by_scope
  ON crm_service_business_holiday(tenant_id, scope_key, holiday_date);

-- ===========================================================================
-- 3. crm_service_business_hours_audit · 配置变更留痕（append-only）
-- ===========================================================================
-- ⚠️ 「可配置、可审阅」的后半句是**可审阅**：
--   营业时间口径直接决定客服绩效口径，所以「谁在什么时候把营业时间从九点改到八点」
--   必须查得到 —— 否则改了之后没人说得清为什么那周的 SLA 全部变了。
-- ⚠️ **只追加**：改它或删它都会让「当时的口径是什么」变得不可复核。
CREATE TABLE crm_service_business_hours_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  scope_key varchar(64) NOT NULL,

  -- UPSERT_WINDOWS 营业时段改写 / UPSERT_HOLIDAYS 节假日例外改写。
  action varchar(24) NOT NULL CHECK (action IN ('UPSERT_WINDOWS','UPSERT_HOLIDAYS')),

  -- 改动后的口径快照（jsonb）：windows = [{weekday,startTime,endTime,enabled}]，
  -- holidays = [{date,label,closed}]。留**快照**而不是留 id 列表：
  -- 配置行会被下一次改写覆盖，只留 id 就查不出「当时是什么」。
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 改动人（已认证成员，服务端写入）。
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

CREATE INDEX crm_service_business_hours_audit_by_scope
  ON crm_service_business_hours_audit(tenant_id, scope_key, occurred_at DESC);

-- ===========================================================================
-- 4. 触发器
-- ===========================================================================
-- ---------------------------------------------------------------------------
-- 4.1 guard_crm_service_business_hours_scope · 同 scope 的时区必须一致 + 区间不许重叠
-- ---------------------------------------------------------------------------
-- 为什么：
--   ① **时区必须一致** —— SLA 到期时刻是在**营业时间轴**上累加出来的，
--      轴的时区不唯一 ⇒ 到期时刻不唯一 ⇒ 「这单有没有超时」有两个答案。
--   ② **区间不许重叠** —— 重叠的区间会把同一段时间**算两遍**，
--      于是「已消耗的营业时间」比真实值多，SLA 到期时刻提前（客服被冤枉逾期）。
CREATE FUNCTION guard_crm_service_business_hours_scope() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_tz varchar(64);
  v_overlap boolean;
BEGIN
  -- ① 时区一致：同 scope 的其它行必须同一个时区。
  SELECT timezone INTO v_tz
    FROM crm_service_business_hours h
   WHERE h.tenant_id = NEW.tenant_id
     AND h.scope_key = NEW.scope_key
     AND h.id <> NEW.id
     AND h.timezone <> NEW.timezone
   LIMIT 1;
  IF v_tz IS NOT NULL THEN
    RAISE EXCEPTION '同一营业时间作用域（scope_key=%）的时段必须共用同一个时区：'
      '混用两个时区会让「营业时间轴」没有唯一定义，而 SLA 到期时刻正是沿这条轴累加出来的',
      NEW.scope_key USING ERRCODE='23514';
  END IF;

  -- ② 同一天区间不许重叠（只与**启用**的其它行比；停用的行不参与计时）。
  IF NEW.enabled THEN
    SELECT EXISTS (
      SELECT 1 FROM crm_service_business_hours h
       WHERE h.tenant_id = NEW.tenant_id
         AND h.scope_key = NEW.scope_key
         AND h.weekday = NEW.weekday
         AND h.id <> NEW.id
         AND h.enabled
         AND h.start_time < NEW.end_time
         AND NEW.start_time < h.end_time
    ) INTO v_overlap;
    IF v_overlap THEN
      RAISE EXCEPTION '同一营业时间作用域（scope_key=%）第 % 个工作日的营业区间互相重叠：'
        '重叠的区间会把同一段时间算两遍，SLA 到期时刻会被提前（客服被冤枉逾期）',
        NEW.scope_key, NEW.weekday USING ERRCODE='23514';
    END IF;
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER crm_service_business_hours_scope_trg
  BEFORE INSERT OR UPDATE ON crm_service_business_hours
  FOR EACH ROW EXECUTE FUNCTION guard_crm_service_business_hours_scope();

-- ---------------------------------------------------------------------------
-- 4.2 guard_crm_service_business_hours_audit · 审计只追加
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_service_business_hours_audit_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '营业时间口径的变更留痕**只追加**：改它或删它都会让「当时的口径是什么」变得不可复核'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_service_business_hours_audit_update_trg
  BEFORE UPDATE ON crm_service_business_hours_audit
  FOR EACH ROW EXECUTE FUNCTION guard_crm_service_business_hours_audit_append_only();

CREATE TRIGGER crm_service_business_hours_audit_delete_trg
  BEFORE DELETE ON crm_service_business_hours_audit
  FOR EACH ROW EXECUTE FUNCTION guard_crm_service_business_hours_audit_append_only();

-- ===========================================================================
-- 5. 行级安全（RLS）与授权
-- ===========================================================================
-- 与 V99 / V220 / V228 / V292 / V293 / V295 / V296 / V297 / V299 / V301 / V302 / V303
-- 同一口径：ENABLE **与** FORCE 都要 —— 只有 FORCE 才挡得住「运行角色被误授 owner」。
-- 策略名统一 isolation，USING 与 WITH CHECK 用**同一表达式**（两侧都写，
-- 否则 WITH CHECK 漏写就等于留了一条写入别的租户的口子）。
DO $$
DECLARE
  tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'crm_service_business_hours',
    'crm_service_business_holiday',
    'crm_service_business_hours_audit'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_service_business_hours,
  crm_service_business_holiday,
  crm_service_business_hours_audit TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_service_business_hours,
  crm_service_business_holiday,
  crm_service_business_hours_audit TO starcon_app;

-- 🔴 审计表**只追加**：改它或删它都会让「当时的口径是什么」变得不可复核。
REVOKE UPDATE,DELETE ON crm_service_business_hours_audit FROM crm_app;
REVOKE UPDATE,DELETE ON crm_service_business_hours_audit FROM starcon_app;

REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ===========================================================================
-- 6. 留给下游 / 上游的口子（不是本模块的缺口）
-- ===========================================================================
--   * **15.4.1.2 工单处理**：**未交付** ⇒ 本模块**不提供**任何处理动作
--     （回复 / 备注 / 状态流转 / 解决确认 / 满意度回收 / SLA 暂停恢复），
--     能力声明里如实标 available=false + ownerModule=15.4.1.2。
--     工单的**写**动作仍然只在 trade 服务（11.4.2.1），本模块是**只读列表**。
--   * **11.4.2.1 工单管理**（trade 服务）：工单事实的**唯一所有者**。
--     本服务经授权 HTTP API 只读，不建跨库 FK、不建共享视图、不直连对方库。
--   * **15.1.1.3 消息分类**：已交付（crm 服务 V301）。本模块**不重造**分类：
--     工单的 source_channel='MESSAGE' 时其 source_ref 是调用方给的引用串，
--     本模块按引用串关联本服务的消息记录，仅用于补全买家，不改写工单本身。
