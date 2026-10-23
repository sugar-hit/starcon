-- 17.7.1.2 用量统计（stage D7，L3 锚点 17.7.1）。
--
-- 蓝图对本模块的职责原文：
--   「统计各租户资源用量（店铺、SKU、月订单、存储、API、AI）用于计费与容量规划，
--     口径须与套餐定义严格一致。」
--   「输入：各系统用量埋点；统计周期。输出：用量报表 + 趋势 + 计费依据。」
--   「关系：上游 = 全部系统埋点；下游 = 17.7.1.1 计费。**口径不一致会直接引发计费争议**。」
--
-- ===========================================================================
-- 一、本模块在 17.7.1.1 之上做什么、不做什么
-- ===========================================================================
--
-- 17.7.1.1（套餐与权益）已交付，**本模块不重复造同义端点**：
--   * POST /identity/saas-plans/usage            —— 登记用量（来源系统写 saas_entitlement_usage）
--   * GET  /identity/saas-plans/entitlement-check —— 超限判定
--   * saas_entitlement_usage —— **当前**用量（每个维度一行 upsert，**没有历史**）
--   * saas_plan_entitlement —— 权益定义（上限、计量周期、计量单位）
--
-- 缺的那一块正是本模块：把「当前用量」物化成**按周期分桶的统计事实**，
-- 于是有了历史（趋势）、有了单一口径的报表、有了可核对的计费依据与容量视图。
--
-- 明确不做：
--   * 不再造一个「登记用量」的端点（17.7.1.1 已有）；
--   * 不改 saas_entitlement_usage 的写入路径 —— 它的事实表写入所有者仍是 17.7.1.1，
--     本模块**只读**它（每张事实表只有一个写入所有者）；
--   * 不落任何金额：本文件**没有一张表有金额列**，没有发票 / 应收 / 付款 / 结算 /
--     收入确认。数量一律 NUMERIC(19,6)。
--
-- ===========================================================================
-- 二、口径：维度码与周期码只能用 17.7.1.1 已定义的那一套
-- ===========================================================================
--
-- 六个维度码（saas_plan_entitlement.entitlement_kind 的枚举）：
--   SHOP_COUNT / SKU_COUNT / ORDER_VOLUME / USER_COUNT / API_CALL / AI_USAGE
-- 四个周期码：DAY / MONTH / YEAR / TOTAL
-- 本文件的 CHECK 约束把这套码**写死**，任何一个服务想用别的写法都会在入库时被拒。
--
-- 「口径须与套餐定义严格一致」在本文件里的三条具体落法：
--   1. **维度口径**取自套餐定义的权益项（entitlement_code + entitlement_kind），
--      统计事实表里冗余一份 entitlement_kind，是为了让报表不必 join 套餐定义也能自解释；
--      冗余值由归集时从套餐定义抄来，不允许来源系统自己声明。
--   2. **周期口径**分两类，取自 saas_plan_entitlement.period：
--        · 流量口径（period ∈ DAY/MONTH/YEAR）：值是「该周期内的用量」，
--          桶的粒度**固定**为套餐定义的周期。来源系统只提供该粒度的值，
--          把月值切到日桶里是错的（那是计费争议的起点），所以本模块不切。
--        · 存量口径（period = TOTAL）：值是「采集时刻的快照」，
--          桶的粒度跟随**统计周期**（归集请求里的 periodType），于是存量维度也有趋势。
--   3. **上限**一律从 saas_plan_entitlement 读，统计事实表里**不存上限**——
--      上限改了，历史报表必须按新上限重算，存一份快照上限就会算出过期结论。
--
-- ===========================================================================
-- 三、蓝图里「存储」这个维度的诚实结论
-- ===========================================================================
--
-- 蓝图 17.7.1.2 的职责原文列的是「店铺、SKU、月订单、存储、API、AI」，
-- 而 17.7.1.1 的套餐定义给的是「店铺数、SKU 数、订单量、用户数、API 调用量、AI 用量」——
-- 两份清单对不上：蓝图有「存储」，套餐没有。
--
-- 本模块**不另起一个 STORAGE 维度码**（那会让统计口径与套餐定义分叉，
-- 正是蓝图警告的「口径不一致会直接引发计费争议」）。因此：
--   * 存储用量**没有可比对的权益上限**，本模块给不出存储维度的超限结论；
--   * 该缺口在 GET /identity/saas-usage/capabilities 里如实报出来
--     （available=false，ownerModule 指向套餐定义）。
--
-- ===========================================================================
-- 四、平台运营边界：继承 17.7.1.1，不放宽
-- ===========================================================================
--
-- 17.7.1.1 的纪律是「仅平台管理员可见，租户不可访问」。本模块的读出口同样只在
-- 平台运营域内：
--   * 应用侧对平台级表仍然只有 SELECT；
--   * 跨租户读一律走下面的 SECURITY DEFINER 函数，函数内部**自己再判一次闸门**；
--   * 闸门用 saas_platform_gate()，与应用层的 requirePlatformOps() 是同一套三道条件。
--
-- 权限码**沿用** 17.7.1.1 已登记的 identity.subscription.read / identity.subscription.update：
--   * 页面资源「订阅与权益用量」本来就覆盖权益用量，用量统计是它的统计视角；
--   * 另起 identity.usage.* 的话，saas_platform_register_role() 不会把新码授给
--     平台运营角色（它只授 identity.plan.% 与 identity.subscription.%），
--     新权限码会变成「登记了也拿不到」的死码；
--   * 本仓多车道并行，**不去 CREATE OR REPLACE 上游的函数**（两条车道同时改同一个
--     函数定义，后应用的会把前一条的授权分支覆盖掉）。
--
-- ===========================================================================
-- 五、统计事实表（租户级）
-- ===========================================================================
--
-- 1. saas_usage_statistic · 周期用量聚合
--    一行 = 一个租户的一个订阅的一个权益维度在一个统计周期里的**用量**。
--    这是用量计数，不是金额。
CREATE TABLE saas_usage_statistic (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL REFERENCES tenant(id),
  subscription_id  uuid NOT NULL,
  -- 权益码与维度码都来自套餐定义；归集时抄过来，来源系统不能自己声明口径。
  entitlement_code varchar(64) NOT NULL,
  entitlement_kind text NOT NULL CHECK(entitlement_kind IN (
    'SHOP_COUNT','SKU_COUNT','ORDER_VOLUME','USER_COUNT','API_CALL','AI_USAGE')),
  -- 统计周期：日 / 月 / 年 / 总量。
  period_type      text NOT NULL CHECK(period_type IN ('DAY','MONTH','YEAR','TOTAL')),
  period_start     timestamptz NOT NULL,
  period_end       timestamptz NOT NULL,
  -- 用量值。**数量**，不是金额（NUMERIC，禁浮点）。
  quantity         numeric(19,6) NOT NULL CHECK(quantity >= 0),
  -- 登记这个数的来源系统（catalog / channel / trade / …），空串表示来源未声明。
  source_system    varchar(32) NOT NULL DEFAULT '' CHECK(source_system ~ '^$|^[a-z_]{2,32}$'),
  -- 来源系统的计量时刻（登记时的 measured_at 原样带过来，便于核对数据新鲜度）。
  measured_at      timestamptz,
  collected_at     timestamptz NOT NULL DEFAULT now(),
  collected_by     uuid,
  note             varchar(300) NOT NULL DEFAULT '',
  UNIQUE(tenant_id, id),
  -- 同一订阅的同一维度在同一周期只有一行：重复归集是**覆盖**（幂等），
  -- 不是累加 —— 累加会让重复跑一次归集的租户用量翻倍。
  UNIQUE(tenant_id, subscription_id, entitlement_code, period_type, period_start),
  FOREIGN KEY (tenant_id, subscription_id)
    REFERENCES saas_subscription(tenant_id, id) ON DELETE CASCADE,
  CHECK(period_end > period_start)
);

-- 报表按租户 + 周期读；趋势按租户 + 维度 + 周期读。
CREATE INDEX saas_usage_statistic_tenant ON saas_usage_statistic(tenant_id, period_type, period_start DESC);
CREATE INDEX saas_usage_statistic_kind ON saas_usage_statistic(tenant_id, entitlement_kind, period_type, period_start DESC);

-- ---------------------------------------------------------------------------
-- 2. saas_usage_collection · 归集批次留痕（追加不可改）
--    一行 = 一次归集跑过什么、量到了几个维度、还有几个没量到。
--    「没量到」是本模块最要紧的事实：它必须留在台面上，而不是被 0 淹没。
-- ---------------------------------------------------------------------------
CREATE TABLE saas_usage_collection (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL REFERENCES tenant(id),
  subscription_id  uuid NOT NULL,
  period_type      text NOT NULL CHECK(period_type IN ('DAY','MONTH','YEAR','TOTAL')),
  period_start     timestamptz NOT NULL,
  period_end       timestamptz NOT NULL,
  -- 套餐里共有多少个维度 / 本次量到了几个 / 没量到几个。
  dimension_count  int NOT NULL CHECK(dimension_count >= 0),
  measured_count   int NOT NULL CHECK(measured_count >= 0),
  unmeasured_count int NOT NULL CHECK(unmeasured_count >= 0),
  -- COMPLETED = 全部维度都量到了；PARTIAL = 有维度没接通（缺口可见）。
  status           text NOT NULL CHECK(status IN ('COMPLETED','PARTIAL')),
  -- 本次归集实际读到哪些来源系统（逗号分隔，便于一眼看出谁接通了）。
  source_systems   varchar(300) NOT NULL DEFAULT '',
  started_at       timestamptz NOT NULL DEFAULT now(),
  finished_at      timestamptz NOT NULL DEFAULT now(),
  actor_membership_id uuid,
  note             varchar(300) NOT NULL DEFAULT '',
  UNIQUE(tenant_id, id),
  FOREIGN KEY (tenant_id, subscription_id)
    REFERENCES saas_subscription(tenant_id, id) ON DELETE CASCADE,
  CHECK(measured_count + unmeasured_count = dimension_count),
  CHECK(period_end > period_start)
);

CREATE INDEX saas_usage_collection_tenant ON saas_usage_collection(tenant_id, started_at DESC);

-- 追加不可改：应用层「不写 UPDATE」不是保证，拿到连接就能直接改。
-- 这里用触发器在数据库层挡住 UPDATE/DELETE，权限层再 REVOKE 一次，双重防线。
CREATE FUNCTION saas_usage_collection_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '用量归集批次不可修改或删除（17.7.1.2 归集留痕）' USING ERRCODE='42501';
END $$;

CREATE TRIGGER saas_usage_collection_immutable_guard
  BEFORE UPDATE OR DELETE ON saas_usage_collection
  FOR EACH ROW EXECUTE FUNCTION saas_usage_collection_immutable();

-- ===========================================================================
-- 六、租户隔离（RLS）
-- ===========================================================================
-- 两张表都是租户级事实表：tenant_id NOT NULL + ENABLE + FORCE RLS + isolation 策略。
-- 策略与 V323 完全同形（app.current_tenant 为空时读到零行，不放行「全部租户」）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'saas_usage_statistic',
    'saas_usage_collection'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 七、授权
-- ===========================================================================
-- 统计事实：读 + 写（归集时 upsert）。**不给 DELETE** —— 统计事实不提供物理删除。
GRANT SELECT,INSERT,UPDATE ON saas_usage_statistic TO identity_app;
-- 归集留痕：只追加。
GRANT SELECT,INSERT ON saas_usage_collection TO identity_app;

-- ===========================================================================
-- 八、跨租户读出口（平台运营看板的唯一读路径）
-- ===========================================================================
-- 与 V323 的 saas_platform_subscription_list 同一套纪律：
--   * SECURITY DEFINER：函数内部**自己再判一次闸门**，应用层漏判不会让数据漏出去；
--   * 每个函数都先 PERFORM saas_platform_gate(hash,'identity.subscription.read')；
--   * 返回列的类型必须与查询结果**逐字一致**（PL/pgSQL 的 RETURN QUERY 是严格类型比较，
--     varchar(64) 与 text 不相等），不一致会在**第一次调用**时才炸，
--     而不是在建函数时 —— 所以下面每个 cast 都是刻意写出来的。
--
-- 跨租户读为什么必须走函数：ADR-0005「每次业务事务选定一个明确目标租户」，
-- 而平台看板天然要跨租户；裸查询（不带租户上下文也能查）会把这个纪律拆掉。
--
-- 「当前订阅」的口径：每个租户取**最新创建**的那条订阅（created_at 相同则按 id 排序），
-- 且排除 CANCELLED —— 已取消的租户不再占平台容量，也不再参与归集。
-- 历史报表仍按各自的订阅出（换过套餐的租户不会丢历史）。

-- 1) 归集范围：有当前订阅的租户与其订阅 id。归集是写动作，必须逐个租户单独成事务，
--    所以这里只提供**范围**，不在函数里跨租户写。
CREATE FUNCTION saas_platform_usage_scope(hash text)
RETURNS TABLE(tenant_id uuid, subscription_id uuid, plan_code varchar(64))
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.subscription.read');
  RETURN QUERY
  SELECT DISTINCT ON (s.tenant_id) s.tenant_id AS tenant_id, s.id AS subscription_id, s.plan_code AS plan_code
  FROM saas_subscription s
  WHERE s.status <> 'CANCELLED'
  ORDER BY s.tenant_id, s.created_at DESC, s.id DESC;
END $$;

-- 2) 用量报表：租户 × 权益维度 × 统计周期一行，含权益上限与超限结论。
--    p_tenant / p_kind / p_period_type 传 NULL 或空串 = 不过滤。
CREATE FUNCTION saas_platform_usage_report(hash text, p_tenant uuid, p_kind text, p_period_type text)
RETURNS TABLE(
  tenant_id uuid, tenant_code text, tenant_name text,
  subscription_id uuid, plan_code varchar(64), plan_name varchar(100), subscription_status text,
  entitlement_code varchar(64), entitlement_kind text,
  entitlement_limit numeric, entitlement_unlimited boolean, over_limit_action text,
  entitlement_unit varchar(32), entitlement_period text,
  period_type text, period_start timestamptz, period_end timestamptz,
  quantity numeric, source_system varchar(32), measured_at timestamptz, collected_at timestamptz,
  utilization numeric, over_limit boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.subscription.read');
  RETURN QUERY
  SELECT t.id AS tenant_id, t.code AS tenant_code, t.name AS tenant_name,
    s.id AS subscription_id, s.plan_code AS plan_code, p.name AS plan_name, s.status AS subscription_status,
    e.entitlement_code AS entitlement_code, e.entitlement_kind AS entitlement_kind,
    e.limit_value::numeric AS entitlement_limit, e.unlimited AS entitlement_unlimited,
    e.over_limit_action AS over_limit_action, e.unit AS entitlement_unit, e.period AS entitlement_period,
    u.period_type AS period_type, u.period_start AS period_start, u.period_end AS period_end,
    u.quantity::numeric AS quantity, u.source_system AS source_system,
    u.measured_at AS measured_at, u.collected_at AS collected_at,
    CASE WHEN e.unlimited OR e.limit_value = 0 THEN NULL::numeric
         ELSE round(u.quantity / e.limit_value, 6) END AS utilization,
    (NOT e.unlimited) AND (u.quantity > e.limit_value) AS over_limit
  FROM tenant t
  JOIN saas_subscription s ON s.tenant_id = t.id
  JOIN saas_plan p ON p.code = s.plan_code
  JOIN saas_usage_statistic u ON u.tenant_id = s.tenant_id AND u.subscription_id = s.id
  JOIN saas_plan_entitlement e ON e.plan_code = s.plan_code AND e.entitlement_code = u.entitlement_code
  WHERE (p_tenant IS NULL OR t.id = p_tenant)
    AND (p_kind IS NULL OR p_kind = '' OR e.entitlement_kind = p_kind)
    AND (p_period_type IS NULL OR p_period_type = '' OR u.period_type = p_period_type)
  ORDER BY t.code, u.period_start DESC, e.sort_order;
END $$;

-- 3) 计费依据：每个租户的每个权益维度，取**最新一个统计周期**的用量与结论。
--
--    ⚠️ 这里用 LEFT JOIN LATERAL 而不是 JOIN：**没量到的维度也要出现在结果里**。
--    「没量到」不是「用量是 0」，把没量到的维度直接丢掉，报表上就会少一行，
--    看板上等于说「这个维度没问题」——那正是计费争议的起点。
--    量不到的维度 quantity 为 NULL，状态是 UNDETERMINED（判定不了）。
--
--    ⚠️ 本模块落的是**数量**：没有单价、没有金额、没有发票。
CREATE FUNCTION saas_platform_usage_billing(hash text, p_tenant uuid, p_kind text, p_period_type text)
RETURNS TABLE(
  tenant_id uuid, tenant_code text, tenant_name text,
  plan_code varchar(64), plan_name varchar(100), subscription_status text,
  entitlement_code varchar(64), entitlement_kind text,
  entitlement_limit numeric, entitlement_unlimited boolean, over_limit_action text,
  entitlement_unit varchar(32), entitlement_period text,
  period_type text, period_start timestamptz, period_end timestamptz,
  quantity numeric, source_system varchar(32), measured_at timestamptz, collected_at timestamptz,
  utilization numeric, over_limit boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.subscription.read');
  RETURN QUERY
  SELECT t.id AS tenant_id, t.code AS tenant_code, t.name AS tenant_name,
    s.plan_code AS plan_code, p.name AS plan_name, s.status AS subscription_status,
    e.entitlement_code AS entitlement_code, e.entitlement_kind AS entitlement_kind,
    e.limit_value::numeric AS entitlement_limit, e.unlimited AS entitlement_unlimited,
    e.over_limit_action AS over_limit_action, e.unit AS entitlement_unit, e.period AS entitlement_period,
    u.period_type AS period_type, u.period_start AS period_start, u.period_end AS period_end,
    u.quantity::numeric AS quantity, u.source_system AS source_system,
    u.measured_at AS measured_at, u.collected_at AS collected_at,
    CASE WHEN e.unlimited OR e.limit_value = 0 OR u.quantity IS NULL THEN NULL::numeric
         ELSE round(u.quantity / e.limit_value, 6) END AS utilization,
    (NOT e.unlimited) AND (u.quantity IS NOT NULL) AND (u.quantity > e.limit_value) AS over_limit
  FROM tenant t
  JOIN saas_subscription s ON s.tenant_id = t.id
  JOIN saas_plan p ON p.code = s.plan_code
  JOIN saas_plan_entitlement e ON e.plan_code = s.plan_code
  LEFT JOIN LATERAL (
    SELECT st.period_type, st.period_start, st.period_end, st.quantity,
           st.source_system, st.measured_at, st.collected_at
    FROM saas_usage_statistic st
    WHERE st.tenant_id = s.tenant_id AND st.subscription_id = s.id
      AND st.entitlement_code = e.entitlement_code
      AND (p_period_type IS NULL OR p_period_type = '' OR st.period_type = p_period_type)
    ORDER BY st.period_start DESC, st.collected_at DESC
    LIMIT 1
  ) u ON true
  WHERE (p_tenant IS NULL OR t.id = p_tenant)
    AND (p_kind IS NULL OR p_kind = '' OR e.entitlement_kind = p_kind)
  ORDER BY t.code, e.sort_order;
END $$;

-- 4) 容量规划：每个维度一行 —— 已授权容量 vs 实际用量 vs 余量。
--    「已授权容量」= 各租户当前套餐里该维度的上限之和（不设上限的租户单独计数，
--    它们的用量照样计入 used_total，但不占 licensed_total）。
--    p_period_type 传 NULL 或空串 = 不限粒度（每个维度各取自己最新的那个桶）。
CREATE FUNCTION saas_platform_usage_capacity(hash text, p_period_type text)
RETURNS TABLE(
  entitlement_kind text,
  subscribed_tenant_count bigint,
  measured_tenant_count bigint,
  unmeasured_tenant_count bigint,
  unlimited_tenant_count bigint,
  used_total numeric,
  licensed_total numeric,
  headroom numeric,
  latest_period_start timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.subscription.read');
  RETURN QUERY
  WITH current_sub AS (
    SELECT DISTINCT ON (s.tenant_id) s.tenant_id AS tenant_id, s.id AS subscription_id, s.plan_code AS plan_code
    FROM saas_subscription s
    WHERE s.status <> 'CANCELLED'
    ORDER BY s.tenant_id, s.created_at DESC, s.id DESC
  ),
  subscribed AS (
    SELECT DISTINCT cs.tenant_id, e.entitlement_kind
    FROM current_sub cs
    JOIN saas_plan_entitlement e ON e.plan_code = cs.plan_code
  ),
  licensed AS (
    SELECT e.entitlement_kind, sum(e.limit_value) AS licensed_total
    FROM current_sub cs
    JOIN saas_plan_entitlement e ON e.plan_code = cs.plan_code
    WHERE NOT e.unlimited
    GROUP BY e.entitlement_kind
  ),
  unlimited AS (
    SELECT e.entitlement_kind, count(DISTINCT cs.tenant_id) AS tenants
    FROM current_sub cs
    JOIN saas_plan_entitlement e ON e.plan_code = cs.plan_code
    WHERE e.unlimited
    GROUP BY e.entitlement_kind
  ),
  latest AS (
    SELECT DISTINCT ON (st.tenant_id, st.entitlement_code)
      st.tenant_id, st.entitlement_code, st.entitlement_kind, st.quantity, st.period_start
    FROM saas_usage_statistic st
    WHERE (p_period_type IS NULL OR p_period_type = '' OR st.period_type = p_period_type)
    ORDER BY st.tenant_id, st.entitlement_code, st.period_start DESC, st.collected_at DESC
  ),
  used AS (
    SELECT l.entitlement_kind AS kind,
           count(DISTINCT l.tenant_id) AS tenants,
           sum(l.quantity) AS used_total,
           max(l.period_start) AS latest_period_start
    FROM latest l
    GROUP BY l.entitlement_kind
  )
  SELECT k.kind AS entitlement_kind,
    (SELECT count(*) FROM subscribed sub WHERE sub.entitlement_kind = k.kind),
    COALESCE((SELECT u.tenants FROM used u WHERE u.kind = k.kind), 0),
    (SELECT count(*) FROM subscribed sub WHERE sub.entitlement_kind = k.kind)
      - COALESCE((SELECT u.tenants FROM used u WHERE u.kind = k.kind), 0),
    COALESCE((SELECT ul.tenants FROM unlimited ul WHERE ul.entitlement_kind = k.kind), 0),
    COALESCE((SELECT u.used_total FROM used u WHERE u.kind = k.kind), 0)::numeric,
    COALESCE((SELECT l.licensed_total FROM licensed l WHERE l.entitlement_kind = k.kind), 0)::numeric,
    COALESCE((SELECT l.licensed_total FROM licensed l WHERE l.entitlement_kind = k.kind), 0)::numeric
      - COALESCE((SELECT u.used_total FROM used u WHERE u.kind = k.kind), 0)::numeric,
    (SELECT u.latest_period_start FROM used u WHERE u.kind = k.kind)
  FROM (SELECT unnest(ARRAY['SHOP_COUNT','SKU_COUNT','ORDER_VOLUME','USER_COUNT','API_CALL','AI_USAGE']) AS kind) k
  ORDER BY k.kind;
END $$;

-- 5) 维度覆盖与来源系统现状：每个维度一行，说明「谁真的接通了」。
--    registered_tenant_count 来自 saas_entitlement_usage 里 available=true 且声明了
--    来源系统的登记数 —— **不是**来自本模块自己造的一张「已接入」清单。
CREATE FUNCTION saas_platform_usage_coverage(hash text)
RETURNS TABLE(
  entitlement_kind text,
  subscribed_tenant_count bigint,
  registered_tenant_count bigint,
  measured_tenant_count bigint,
  wired_source_systems text,
  state text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.subscription.read');
  RETURN QUERY
  WITH current_sub AS (
    SELECT DISTINCT ON (s.tenant_id) s.tenant_id AS tenant_id, s.id AS subscription_id, s.plan_code AS plan_code
    FROM saas_subscription s
    WHERE s.status <> 'CANCELLED'
    ORDER BY s.tenant_id, s.created_at DESC, s.id DESC
  ),
  subscribed AS (
    SELECT DISTINCT cs.tenant_id, e.entitlement_kind
    FROM current_sub cs
    JOIN saas_plan_entitlement e ON e.plan_code = cs.plan_code
  ),
  registered AS (
    SELECT e.entitlement_kind AS kind,
           count(DISTINCT u.tenant_id) AS tenants,
           string_agg(DISTINCT u.source_system, ',' ORDER BY u.source_system) AS sources
    FROM saas_entitlement_usage u
    JOIN current_sub cs ON cs.tenant_id = u.tenant_id AND cs.subscription_id = u.subscription_id
    JOIN saas_plan_entitlement e ON e.plan_code = cs.plan_code AND e.entitlement_code = u.entitlement_code
    WHERE u.available AND u.source_system <> ''
    GROUP BY e.entitlement_kind
  ),
  measured AS (
    SELECT m.entitlement_kind AS kind, count(DISTINCT m.tenant_id) AS tenants
    FROM saas_usage_statistic m
    GROUP BY m.entitlement_kind
  )
  SELECT k.kind AS entitlement_kind,
    (SELECT count(*) FROM subscribed sub WHERE sub.entitlement_kind = k.kind),
    COALESCE((SELECT r.tenants FROM registered r WHERE r.kind = k.kind), 0),
    COALESCE((SELECT m.tenants FROM measured m WHERE m.kind = k.kind), 0),
    COALESCE((SELECT r.sources FROM registered r WHERE r.kind = k.kind), ''),
    CASE
      WHEN (SELECT count(*) FROM subscribed sub WHERE sub.entitlement_kind = k.kind) = 0 THEN 'NO_SUBSCRIPTION'
      WHEN COALESCE((SELECT r.tenants FROM registered r WHERE r.kind = k.kind), 0) = 0 THEN 'NOT_WIRED'
      WHEN COALESCE((SELECT r.tenants FROM registered r WHERE r.kind = k.kind), 0)
           < (SELECT count(*) FROM subscribed sub WHERE sub.entitlement_kind = k.kind) THEN 'PARTIAL'
      ELSE 'WIRED'
    END
  FROM (SELECT unnest(ARRAY['SHOP_COUNT','SKU_COUNT','ORDER_VOLUME','USER_COUNT','API_CALL','AI_USAGE']) AS kind) k
  ORDER BY k.kind;
END $$;

-- 6) 归集批次历史（跨租户）。
CREATE FUNCTION saas_platform_usage_collections(hash text, p_tenant uuid)
RETURNS TABLE(
  collection_id uuid, tenant_id uuid, tenant_code text, tenant_name text,
  subscription_id uuid, period_type text, period_start timestamptz, period_end timestamptz,
  dimension_count int, measured_count int, unmeasured_count int, status text,
  source_systems varchar(300), started_at timestamptz, finished_at timestamptz, note varchar(300))
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.subscription.read');
  RETURN QUERY
  SELECT c.id AS collection_id, t.id AS tenant_id, t.code AS tenant_code, t.name AS tenant_name,
    c.subscription_id AS subscription_id, c.period_type AS period_type,
    c.period_start AS period_start, c.period_end AS period_end,
    c.dimension_count AS dimension_count, c.measured_count AS measured_count,
    c.unmeasured_count AS unmeasured_count, c.status AS status,
    c.source_systems AS source_systems, c.started_at AS started_at, c.finished_at AS finished_at,
    c.note AS note
  FROM saas_usage_collection c
  JOIN tenant t ON t.id = c.tenant_id
  WHERE (p_tenant IS NULL OR c.tenant_id = p_tenant)
  ORDER BY c.started_at DESC, c.id DESC;
END $$;

REVOKE EXECUTE ON FUNCTION saas_platform_usage_scope(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_usage_report(text,uuid,text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_usage_billing(text,uuid,text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_usage_capacity(text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_usage_coverage(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_usage_collections(text,uuid) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION saas_platform_usage_scope(text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_usage_report(text,uuid,text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_usage_billing(text,uuid,text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_usage_capacity(text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_usage_coverage(text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_usage_collections(text,uuid) TO identity_app;
