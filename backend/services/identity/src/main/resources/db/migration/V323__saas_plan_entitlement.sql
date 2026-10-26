-- 17.7.1.1 套餐与权益（stage D7，L3 锚点 17.7.1）。
--
-- 蓝图对本模块的职责原文：
--   「starcon **自身**的 SaaS 商业化：套餐定义与权益项（店铺数、SKU 数、订单量、用户数、
--     API 调用量、AI 用量）、订阅状态、试用与到期处理、超限策略。
--     **仅平台管理员可见，租户不可访问**——避免与租户自身的采购/应付业务混淆。」
--   「与 ERP 客户的销售收入完全无关（实体目录 §8），实现时需与业务库物理隔离。」
--
-- ---------------------------------------------------------------------------
-- 三条纪律在本文件里的落点
-- ---------------------------------------------------------------------------
--
-- ① 平台级 vs 租户级：两层表，一层一层的口径与 V53 的 dict_catalog / dict_item 完全一致。
--    - **平台级**（不带 tenant_id、不参与 RLS、只 GRANT SELECT）：
--      saas_plan（套餐定义）、saas_plan_entitlement（权益项）。
--      它们回答的是「starcon 有哪些套餐、每个套餐带哪些权益与上限」，与具体租户无关，
--      和 identity 的 page_resource / permission、V48 的 business_parameter_definition、
--      V53 的 dict_catalog 是同一类全局码表。
--    - **租户级**（tenant_id NOT NULL + RLS FORCE isolation）：
--      saas_subscription（订阅）、saas_entitlement_usage（权益用量）、
--      saas_subscription_event（订阅状态变更留痕）。
--      它们回答的是「这个租户买了哪个套餐、现在什么状态、用了多少权益」。
--    混合的地方只有一处：**订阅指向平台级的套餐**（saas_subscription.plan_code 外键到
--    saas_plan.code）。这不是跨服务 FK，是同一张平台级码表被引用，与
--    dict_item.dict_code → dict_catalog.code 同一形状。
--
-- ② 「与业务库物理隔离」在单库多租户架构下的可落地形态（本次取舍，写清楚）：
--    平台级表**不参与租户 RLS 体系**：没有 tenant_id，没有 isolation policy，
--    不被 app.current_tenant 过滤，也就不会出现在任何按租户上下文做的导出里；
--    表名走独立命名空间 saas_plan_* / saas_subscription_* / saas_entitlement_usage_*，
--    与 business_parameter_* / dict_* / approval_* / role_* 互不重叠；
--    零外键指向任何租户业务表（只有 tenant_id → tenant(id) 这一条本库引用，
--    以及租户级表之间 tenant_id + id 的复合外键）。
--    另外：**平台级表只授予 SELECT**，写入一律走下面的 SECURITY DEFINER 专用路径，
--    应用侧拿不到 INSERT/UPDATE/DELETE 权限——改套餐定义必须经过平台运营角色 + 权限码
--    这道闸，不能靠一条 SQL 绕过。
--
-- ③ 与 ERP 客户的销售收入完全无关：本文件**没有任何金额列**。
--    权益上限与用量都是**数量**，用 NUMERIC(19,6)（禁浮点）。
--    订单量 / API 调用量 / AI 用量是**用量计数**，不是金额。
--    没有发票、没有应收应付、没有付款、没有结算、没有收入确认——一张表都没有。
--    实体目录 §8 说「SaaS 商业化若进首发，需要补 subscription_plan/subscription/
--    entitlement/usage_record，独立于 ERP 客户的销售收入」，本次落地的是它的前半句：
--    定义、权益、订阅状态与用量口径；**计费与收入确认暂不实施**（见 capabilities 端点
--    如实标注 available=false + ownerModule）。
--
-- ---------------------------------------------------------------------------
-- ④ 「仅平台管理员可见，租户不可访问」怎么落地
-- ---------------------------------------------------------------------------
--
-- 仓内没有 PLATFORM_ADMIN 这种全局超级角色，本模块**不凭空造一个**。
-- 落地口径是三层，全部由服务端强制：
--   1. **权限码**：identity.plan.{read,create,update} 与
--      identity.subscription.{read,create,update}，走既有 page_resource + permission +
--      role_permission 模型。**刻意不授给 OWNER 角色**（V53 的先例是授给 OWNER，
--      本模块相反：授给 OWNER 就等于每个租户都能看套餐与订阅，直接违背蓝图）。
--   2. **平台运营角色登记**（saas_platform_role）：登记「本租户的哪个角色是平台运营角色」。
--      这张表对 identity_app **只授予 SELECT**：应用侧无法把自己升级成平台运营方，
--      写入只发生在下面的 saas_platform_register_role() 这条专用路径上。
--   3. **三道条件同时成立**才放行（saas_platform_gate()）：
--      会话所在租户里存在一条 saas_platform_role 登记、
--      该登记的角色**正好**是当前会话持有的角色（roleId 对得上）、
--      且该角色分配带有所需权限码且 scope_mode='ALL'。
--      —— 少任何一条都拒绝。
--
-- 登记本身走 saas_platform_register_role()：它在**会话自己的租户**里建角色、授权、
--      登记、分配给调用者本人，全程不接收任何租户 id 参数
--      （租户一律来自 session_access(hash) 推导出的成员关系，不信任客户端头）。
--
-- ---------------------------------------------------------------------------
-- ⑤ 「订阅与续费事件」没有上游
-- ---------------------------------------------------------------------------
--
-- 仓内没有 billing / payment 服务，订阅状态的变化**由平台运营在本模块内驱动**：
-- 有据可查的状态变更 + 追加不可改的 saas_subscription_event 留痕 + 审计日志。
-- 不去接不存在的支付服务，也不凭空造一个「续费 webhook」端点。
--
-- ⑥ 「权益用量的真实来源」没有统一计量接口
--
-- 店铺数 / SKU 数 / 订单量 / API 调用量 / AI 用量分散在 catalog / channel / trade / …，
-- 仓内没有统一的用量计量接口（17.7.1.2「用量统计」未开发）。因此本模块提供
-- **用量登记与读取接口**（POST /saas-plans/usage、GET /saas-plans/usage），
-- 但**不假装自己量到了**：saas_entitlement_usage.available=false 时 usage_value 必须为
-- NULL（CHECK 钉死），并带上 owner_module（缺口归属模块）与 impact（缺了会怎样）。
-- 用 0 或空列表冒充「这一项没有数据」是把缺口说成了事实。

-- ===========================================================================
-- 1. saas_plan · 套餐定义（平台级，不带 tenant_id）
-- ===========================================================================
CREATE TABLE saas_plan (
  code        varchar(64) PRIMARY KEY,
  name        varchar(100) NOT NULL,
  -- 档位序号：套餐是分档的，档位之间要有稳定顺序。刻意**不加唯一约束**——
  -- 平台可能同时保留一个「即将下线」的旧档位与新档位，硬唯一会让下线流程走不通。
  tier_level  int NOT NULL DEFAULT 0,
  description varchar(300) NOT NULL DEFAULT '',
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  -- 试用天数：0 = 该套餐不提供试用。天数是**数量**，不是金额。
  trial_days  int NOT NULL DEFAULT 0 CHECK(trial_days >= 0),
  sort_order  int NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);

-- 套餐列表：按档位与排序读。平台级表没有 tenant_id，所以索引也不带它。
CREATE INDEX saas_plan_order ON saas_plan(status, tier_level, sort_order, code);

-- ===========================================================================
-- 2. saas_plan_entitlement · 权益项（平台级，挂在套餐下）
-- ===========================================================================
CREATE TABLE saas_plan_entitlement (
  plan_code        varchar(64) NOT NULL REFERENCES saas_plan(code) ON DELETE CASCADE,
  entitlement_code varchar(64) NOT NULL,
  -- 六个维度就是蓝图职责点名的六个：店铺数 / SKU 数 / 订单量 / 用户数 /
  -- API 调用量 / AI 用量。枚举写死在这张 CHECK 里，避免各服务各写一种口径。
  entitlement_kind text NOT NULL CHECK(entitlement_kind IN (
    'SHOP_COUNT','SKU_COUNT','ORDER_VOLUME','USER_COUNT','API_CALL','AI_USAGE')),
  -- 上限值是**数量**（NUMERIC，不用浮点）。unlimited=true 时本列不参与判定。
  limit_value      numeric(19,6) NOT NULL DEFAULT 0 CHECK(limit_value >= 0),
  unlimited        boolean NOT NULL DEFAULT false,
  -- 超限策略：阻断 / 降级 / 放行。这是本模块**产出判定结果**的那一档，
  -- 下游（17.7.2.1 平台参数与开关）据此做功能开关，本模块不实现开关机制本身。
  over_limit_action text NOT NULL DEFAULT 'BLOCK'
                    CHECK(over_limit_action IN ('BLOCK','DEGRADE','ALLOW')),
  -- 计量周期：日 / 月 / 年 / 总量。订单量与 API 调用量按周期计量，
  -- 店铺数与 SKU 数是存量口径（TOTAL）。
  period           text NOT NULL DEFAULT 'MONTH' CHECK(period IN ('DAY','MONTH','YEAR','TOTAL')),
  unit             varchar(32) NOT NULL DEFAULT '',
  sort_order       int NOT NULL DEFAULT 0,
  PRIMARY KEY (plan_code, entitlement_code),
  CHECK(btrim(entitlement_code)<>'')
);

-- 按维度读：一个套餐下有哪些权益、每个维度的上限与超限策略。
CREATE INDEX saas_plan_entitlement_kind ON saas_plan_entitlement(plan_code, sort_order, entitlement_code);

-- ===========================================================================
-- 3. saas_subscription · 订阅（租户级）
-- ===========================================================================
CREATE TABLE saas_subscription (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  -- 指向平台级套餐定义。套餐下线（status='RETIRED'）不影响存量订阅，
  -- 所以这里**不**加级联动作。
  plan_code   varchar(64) NOT NULL REFERENCES saas_plan(code),
  status      text NOT NULL DEFAULT 'TRIAL'
              CHECK(status IN ('TRIAL','ACTIVE','EXPIRED','SUSPENDED','CANCELLED')),
  -- 试用起止。两个都是时间字段，绑定一律用 OffsetDateTime + ?::timestamptz。
  trial_start timestamptz,
  trial_end   timestamptz,
  started_at  timestamptz,
  expires_at  timestamptz,
  cancelled_at timestamptz,
  suspend_reason varchar(200) NOT NULL DEFAULT '',
  note        varchar(500) NOT NULL DEFAULT '',
  version     bigint NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  created_by_membership_id uuid,
  UNIQUE(tenant_id, id),
  -- 一个租户在同一套餐下只有一条订阅：续期与状态流转都是**就地改**，
  -- 历史靠 saas_subscription_event 追加留痕，而不是堆多行快照
  -- （多行快照会让「这个租户当前是什么状态」有两个答案）。
  UNIQUE(tenant_id, plan_code),
  -- 试用中必须说清楚试用从哪天到哪天。
  -- 反过来**不**要求「不是试用就一定没有试用起止」：试用转正式之后，试用起止是**已经发生的
  -- 事实**，清掉它等于抹掉一次试用记录。所以这里只约束「是试用就必须有起止」，
  -- 不约束「不是试用就不能有起止」——早先写成双向等值时，试用转正式会被 CHECK 拒掉。
  CHECK(status <> 'TRIAL' OR (trial_start IS NOT NULL AND trial_end IS NOT NULL)),
  CHECK(trial_end IS NULL OR trial_start IS NULL OR trial_end >= trial_start),
  -- 取消是终态：必须留下取消时刻，否则事后无法解释「什么时候停的」。
  CHECK((status='CANCELLED') = (cancelled_at IS NOT NULL)),
  CHECK(expires_at IS NULL OR started_at IS NULL OR expires_at > started_at)
);

CREATE INDEX saas_subscription_tenant ON saas_subscription(tenant_id, status);
CREATE INDEX saas_subscription_plan ON saas_subscription(plan_code, status);

-- ===========================================================================
-- 4. saas_entitlement_usage · 权益用量（租户级）
-- ===========================================================================
-- 一行 = 一个订阅下一个权益维度的**当前用量**。这是用量计数，不是金额。
CREATE TABLE saas_entitlement_usage (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  subscription_id uuid NOT NULL,
  entitlement_code varchar(64) NOT NULL,
  -- 用量值。available=false 时**必须**是 NULL——没有接通的计量不能拿 0 冒充
  -- 「量过了，正好是 0」，那是把缺口说成了事实。
  usage_value     numeric(19,6) CHECK(usage_value IS NULL OR usage_value >= 0),
  -- 量到了没有。没接通时带 owner_module（缺口归属模块）与 impact（缺了会怎样）。
  available       boolean NOT NULL DEFAULT false,
  owner_module    varchar(32) NOT NULL DEFAULT '',
  impact          varchar(300) NOT NULL DEFAULT '',
  -- 登记来源系统（catalog / channel / trade / …），与 V53 dict_item_usage 同一套跨服务边界做法：
  -- identity 不去查它们的表，用量由来源系统经授权 API 登记。
  source_system   varchar(32) NOT NULL DEFAULT '' CHECK(source_system ~ '^$|^[a-z_]{2,32}$'),
  measured_at     timestamptz,
  period_start    timestamptz,
  period_end      timestamptz,
  note            varchar(300) NOT NULL DEFAULT '',
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, subscription_id, entitlement_code),
  FOREIGN KEY (tenant_id, subscription_id)
    REFERENCES saas_subscription(tenant_id, id) ON DELETE CASCADE,
  -- 「量到了」与「有数值」必须同时成立，否则接口里的 available 就是在骗下游。
  CHECK((available AND usage_value IS NOT NULL) OR (NOT available AND usage_value IS NULL)),
  CHECK((available AND measured_at IS NOT NULL) OR (NOT available AND measured_at IS NULL)),
  -- 没接通就必须说清归谁、缺了会怎样，不能留空。
  CHECK(available OR (btrim(owner_module)<>'' AND btrim(impact)<>''))
);

CREATE INDEX saas_entitlement_usage_tenant ON saas_entitlement_usage(tenant_id, subscription_id);
CREATE INDEX saas_entitlement_usage_code ON saas_entitlement_usage(tenant_id, entitlement_code);

-- ===========================================================================
-- 5. saas_subscription_event · 订阅变更留痕（租户级，追加不可改）
-- ===========================================================================
CREATE TABLE saas_subscription_event (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  subscription_id uuid NOT NULL,
  action          text NOT NULL CHECK(action IN (
    'CREATED','UPDATED','STATUS_CHANGED','RENEWED','TRIAL_STARTED','TRIAL_ENDED',
    'EXPIRED','CANCELLED','SUSPENDED','RESUMED')),
  from_status     text,
  to_status       text,
  reason          varchar(300) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  actor_name      varchar(100),
  occurred_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY (tenant_id, subscription_id)
    REFERENCES saas_subscription(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX saas_subscription_event_sub ON saas_subscription_event(tenant_id, subscription_id, occurred_at DESC);

-- 追加不可改：应用层「不写 UPDATE」不是保证，拿到连接就能直接改。
-- 这里用触发器在数据库层挡住 UPDATE/DELETE，权限层再 REVOKE 一次，双重防线。
CREATE FUNCTION saas_subscription_event_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '订阅变更留痕不可修改或删除（17.7.1.1 变更留痕）' USING ERRCODE='42501';
END $$;

CREATE TRIGGER saas_subscription_event_immutable_guard
  BEFORE UPDATE OR DELETE ON saas_subscription_event
  FOR EACH ROW EXECUTE FUNCTION saas_subscription_event_immutable();

-- ===========================================================================
-- 6. saas_platform_role · 平台运营角色登记（租户级，但应用侧只读）
-- ===========================================================================
-- 一行 = 「这个租户的这个角色是平台运营角色」。
--
-- 为什么需要这张表：既有 role / role_permission 模型里，任何租户的所有者都能
-- 自己建一个角色并把任意**已登记**的权限码授给自己（AdministrationController
-- .saveRole 只校验权限码是否存在）。如果平台运营权限码只登记在 permission 表里，
-- 那么每个租户都能把自己升级成平台运营方——「租户不可访问」当场失效。
--
-- 所以平台运营的判定**不只看权限码**，还要看这个角色是否被登记在这里：
--   - 登记表的写入只发生在 saas_platform_register_role()（专用路径）；
--   - identity_app 对这张表只有 SELECT，自己加不进去；
--   - 登记的是 roleId 而不是角色编码，所以别的租户建一个同名角色也不算数
--     （roleId 是租户内唯一的）。
CREATE TABLE saas_platform_role (
  tenant_id    uuid NOT NULL REFERENCES tenant(id),
  role_id      uuid NOT NULL,
  role_code    varchar(64) NOT NULL,
  registered_at timestamptz NOT NULL DEFAULT now(),
  registered_by uuid,
  note         varchar(300) NOT NULL DEFAULT '',
  PRIMARY KEY (tenant_id, role_id),
  FOREIGN KEY (tenant_id, role_id) REFERENCES role(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 7. 租户隔离（RLS）
-- ===========================================================================
-- 平台级表（saas_plan / saas_plan_entitlement）**刻意不加 RLS**：
-- 它们不带 tenant_id，是所有租户共读的一份出厂目录（与 dict_catalog 同一先例）。
-- 必须隔离的是订阅、用量与留痕这三张表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'saas_subscription',
    'saas_entitlement_usage',
    'saas_subscription_event',
    'saas_platform_role'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 8. 授权
-- ===========================================================================
-- 平台级表：只读。写入走下面的 SECURITY DEFINER 专用路径（应用侧拿不到写权限）。
GRANT SELECT ON saas_plan TO identity_app;
GRANT SELECT ON saas_plan_entitlement TO identity_app;
-- 租户级表：正常读写，但**不给 DELETE**——订阅与用量是事实，不提供物理删除。
GRANT SELECT,INSERT,UPDATE ON saas_subscription TO identity_app;
GRANT SELECT,INSERT,UPDATE ON saas_entitlement_usage TO identity_app;
-- 留痕只追加。
GRANT SELECT,INSERT ON saas_subscription_event TO identity_app;
-- 平台运营角色登记：只读。应用侧不能自己登记（见第 6 节说明）。
GRANT SELECT ON saas_platform_role TO identity_app;

-- ===========================================================================
-- 9. 平台运营闸门
-- ===========================================================================
-- 三条同时成立才放行：
--   (1) 会话所在租户里存在一条平台运营角色登记；
--   (2) 当前会话持有的角色正好是被登记的那一个 roleId，且 scope_mode='ALL'；
--   (3) 该角色分配带有所需权限码。
-- 少一条都拒绝 —— 这就是「租户不可访问」在服务端的具体形状。
--
-- SECURITY DEFINER：登记表受 RLS 保护，而这段判定要在**平台租户自己的上下文**里读它，
-- 函数的 owner（迁移账号）不受行级策略影响，所以判定口径不会随调用方上下文漂移。
CREATE FUNCTION saas_platform_gate(hash text, needed_permission text) RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; matched int; BEGIN
  SELECT * INTO a FROM session_access(hash);
  IF a IS NULL THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
  SELECT count(*) INTO matched
  FROM saas_platform_role pr
  WHERE pr.tenant_id=a.tenant_id
    AND needed_permission=ANY(a.permissions)
    AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(a.grants) g
      WHERE g->>'roleId'=pr.role_id::text AND g->>'scopeMode'='ALL');
  IF matched=0 THEN
    RAISE EXCEPTION '套餐与权益是平台运营域，当前角色没有此操作权限' USING ERRCODE='42501';
  END IF;
  RETURN a.tenant_id;
END $$;

-- 登记平台运营角色（平台运营侧专用路径）。
--
-- 全程**不接收任何租户 id 参数**：租户一律来自 session_access(hash) 推导出的成员关系，
-- 与 V40「租户集合只能由已认证成员关系推导」同一纪律。
--
-- 做四件事：
--   1. 在会话租户里建角色（不存在就建，编码相同则复用）；
--   2. 把平台运营权限码授给这个角色；
--   3. 在 saas_platform_role 里登记这个 roleId（应用侧只读，登记不了别的租户）；
--   4. 把这个角色分配给调用者本人（scope_mode='ALL'，满足 V47「有角色必有范围」）。
CREATE FUNCTION saas_platform_register_role(hash text, requested_code text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; rid uuid; tid uuid; BEGIN
  SELECT * INTO a FROM session_access(hash);
  IF a IS NULL THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
  IF requested_code !~ '^[A-Z][A-Z0-9_]{1,63}$' THEN
    RAISE EXCEPTION 'Malformed platform role code' USING ERRCODE='23514';
  END IF;
  tid := a.tenant_id;
  SELECT id INTO rid FROM role WHERE tenant_id=tid AND code=requested_code;
  IF rid IS NULL THEN
    rid := gen_random_uuid();
    INSERT INTO role(id,tenant_id,code,name,status)
      VALUES(rid,tid,requested_code,'平台运营','ACTIVE');
  END IF;
  INSERT INTO role_permission(tenant_id,role_id,permission_code)
    SELECT tid,rid,p.code FROM permission p
    WHERE p.code LIKE 'identity.plan.%' OR p.code LIKE 'identity.subscription.%'
    ON CONFLICT DO NOTHING;
  INSERT INTO saas_platform_role(tenant_id,role_id,role_code,registered_by)
    VALUES(tid,rid,requested_code,a.user_id)
    ON CONFLICT (tenant_id,role_id) DO NOTHING;
  INSERT INTO role_assignment(tenant_id,membership_id,role_id,scope_mode,assigned_by)
    VALUES(tid,a.membership_id,rid,'ALL',a.user_id)
    ON CONFLICT DO NOTHING;
END $$;

-- ===========================================================================
-- 10. 平台级表的写入专用路径（应用侧只有 SELECT，写权限在函数里）
-- ===========================================================================
-- 为什么套餐定义要这样写：平台级表按 dict_catalog 的先例只授予 SELECT，
-- 也就是说 identity_app **没有** INSERT/UPDATE 权限。要让平台运营能维护套餐，
-- 就必须有一条受闸门保护的专用写入路径——这就是这里的 SECURITY DEFINER 函数。
-- 每条都先过 saas_platform_gate()，拿不到平台运营角色就写不进去。

CREATE FUNCTION saas_platform_create_plan(
  hash text, p_code text, p_name text, p_tier int, p_desc text, p_trial_days int, p_sort int) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.plan.create');
  IF p_code !~ '^[A-Z][A-Z0-9_]{1,63}$' THEN RAISE EXCEPTION 'Malformed plan code' USING ERRCODE='23514'; END IF;
  IF btrim(coalesce(p_name,''))='' THEN RAISE EXCEPTION 'Plan name required' USING ERRCODE='23514'; END IF;
  INSERT INTO saas_plan(code,name,tier_level,description,status,trial_days,sort_order,created_at,updated_at)
    VALUES(p_code,btrim(p_name),p_tier,coalesce(p_desc,''),'ACTIVE',p_trial_days,p_sort,now(),now());
END $$;

CREATE FUNCTION saas_platform_update_plan(
  hash text, p_code text, p_name text, p_tier int, p_desc text, p_status text, p_trial_days int, p_sort int) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.plan.update');
  IF p_status NOT IN ('ACTIVE','RETIRED') THEN RAISE EXCEPTION 'Unsupported plan status' USING ERRCODE='23514'; END IF;
  IF btrim(coalesce(p_name,''))='' THEN RAISE EXCEPTION 'Plan name required' USING ERRCODE='23514'; END IF;
  UPDATE saas_plan SET name=btrim(p_name),tier_level=p_tier,description=coalesce(p_desc,''),
    status=p_status,trial_days=p_trial_days,sort_order=p_sort,updated_at=now()
  WHERE code=p_code;
  IF NOT FOUND THEN RAISE EXCEPTION 'Plan unavailable' USING ERRCODE='42501'; END IF;
END $$;

CREATE FUNCTION saas_platform_create_entitlement(
  hash text, p_plan text, p_code text, p_kind text, p_limit numeric, p_unlimited boolean,
  p_action text, p_period text, p_unit text, p_sort int) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.plan.create');
  IF p_code !~ '^[A-Z][A-Z0-9_]{1,63}$' THEN RAISE EXCEPTION 'Malformed entitlement code' USING ERRCODE='23514'; END IF;
  IF p_kind NOT IN ('SHOP_COUNT','SKU_COUNT','ORDER_VOLUME','USER_COUNT','API_CALL','AI_USAGE')
    THEN RAISE EXCEPTION 'Unsupported entitlement kind' USING ERRCODE='23514'; END IF;
  IF p_action NOT IN ('BLOCK','DEGRADE','ALLOW') THEN RAISE EXCEPTION 'Unsupported over limit action' USING ERRCODE='23514'; END IF;
  IF p_period NOT IN ('DAY','MONTH','YEAR','TOTAL') THEN RAISE EXCEPTION 'Unsupported period' USING ERRCODE='23514'; END IF;
  IF p_unlimited IS NOT TRUE AND p_limit IS NULL THEN RAISE EXCEPTION 'Limit required' USING ERRCODE='23514'; END IF;
  IF p_limit IS NOT NULL AND p_limit<0 THEN RAISE EXCEPTION 'Limit must not be negative' USING ERRCODE='23514'; END IF;
  INSERT INTO saas_plan_entitlement
    (plan_code,entitlement_code,entitlement_kind,limit_value,unlimited,over_limit_action,period,unit,sort_order)
    VALUES(p_plan,p_code,p_kind,coalesce(p_limit,0),coalesce(p_unlimited,false),p_action,p_period,coalesce(p_unit,''),p_sort);
END $$;

CREATE FUNCTION saas_platform_update_entitlement(
  hash text, p_plan text, p_code text, p_kind text, p_limit numeric, p_unlimited boolean,
  p_action text, p_period text, p_unit text, p_sort int) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.plan.update');
  IF p_kind NOT IN ('SHOP_COUNT','SKU_COUNT','ORDER_VOLUME','USER_COUNT','API_CALL','AI_USAGE')
    THEN RAISE EXCEPTION 'Unsupported entitlement kind' USING ERRCODE='23514'; END IF;
  IF p_action NOT IN ('BLOCK','DEGRADE','ALLOW') THEN RAISE EXCEPTION 'Unsupported over limit action' USING ERRCODE='23514'; END IF;
  IF p_period NOT IN ('DAY','MONTH','YEAR','TOTAL') THEN RAISE EXCEPTION 'Unsupported period' USING ERRCODE='23514'; END IF;
  IF p_unlimited IS NOT TRUE AND p_limit IS NULL THEN RAISE EXCEPTION 'Limit required' USING ERRCODE='23514'; END IF;
  IF p_limit IS NOT NULL AND p_limit<0 THEN RAISE EXCEPTION 'Limit must not be negative' USING ERRCODE='23514'; END IF;
  UPDATE saas_plan_entitlement
    SET entitlement_kind=p_kind,limit_value=coalesce(p_limit,0),unlimited=coalesce(p_unlimited,false),
        over_limit_action=p_action,period=p_period,unit=coalesce(p_unit,''),sort_order=p_sort
  WHERE plan_code=p_plan AND entitlement_code=p_code;
  IF NOT FOUND THEN RAISE EXCEPTION 'Entitlement unavailable' USING ERRCODE='42501'; END IF;
END $$;

-- 删权益项：**已被用量登记引用过的不许删**。
-- 与 V53「字典项被引用后只能停用不能删除」同一纪律：删掉之后历史用量记录
-- 会指向一个不存在的权益维度，报表上会出现空白行。
CREATE FUNCTION saas_platform_delete_entitlement(hash text, p_plan text, p_code text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE used int; BEGIN
  PERFORM saas_platform_gate(hash,'identity.plan.update');
  SELECT count(*) INTO used FROM saas_entitlement_usage WHERE entitlement_code=p_code;
  IF used>0 THEN
    RAISE EXCEPTION '权益项「%」已有用量登记（% 条），只能停用不能删除', p_code, used USING ERRCODE='42501';
  END IF;
  DELETE FROM saas_plan_entitlement WHERE plan_code=p_plan AND entitlement_code=p_code;
  IF NOT FOUND THEN RAISE EXCEPTION 'Entitlement unavailable' USING ERRCODE='42501'; END IF;
END $$;

-- ===========================================================================
-- 11. 跨租户订阅视图（平台运营看板的唯一读出口）
-- ===========================================================================
-- 平台运营要看到**所有租户**的订阅状态，这天然跨租户。按 ADR-0005
-- 「每次业务事务选定一个明确目标租户」的纪律，这里不把 RLS 放大成「可见全部后代」，
-- 而是用一条 SECURITY DEFINER 函数承担这次跨租户读取，并且函数内部**自己再判一次闸门**：
-- 应用层漏判不会让数据漏出去。
--
-- 返回行以 tenant 为左表：**没有订阅的租户也在列表里**（plan_code 为 NULL），
-- 否则平台运营看不到「哪些客户还没开通套餐」——那正是这个看板要回答的问题。
-- ⚠️ 返回列的类型必须与查询结果**逐字一致**：PL/pgSQL 的 RETURN QUERY 做的是严格类型比较，
-- varchar(64) 与 text 不相等（不是隐式转换），不一致会直接报
-- 「structure of query does not match function result type」——整条读出口全废，而且这个错
-- 在建函数时不会报，只有第一次调用才炸。
CREATE FUNCTION saas_platform_subscription_list(hash text)
RETURNS TABLE(
  subscription_id uuid, tenant_id uuid, tenant_code text, tenant_name text,
  plan_code varchar(64), plan_name varchar(100), status text,
  trial_start timestamptz, trial_end timestamptz, started_at timestamptz,
  expires_at timestamptz, cancelled_at timestamptz,
  usage_available_count bigint, usage_missing_count bigint, blocked_count bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,'identity.subscription.read');
  RETURN QUERY
  SELECT s.id AS subscription_id, t.id AS tenant_id, t.code AS tenant_code, t.name AS tenant_name,
    s.plan_code AS plan_code, p.name AS plan_name, s.status AS status,
    s.trial_start AS trial_start, s.trial_end AS trial_end, s.started_at AS started_at,
    s.expires_at AS expires_at, s.cancelled_at AS cancelled_at,
    (SELECT count(*) FROM saas_entitlement_usage u
      WHERE u.subscription_id=s.id AND u.available) AS usage_available_count,
    (SELECT count(*) FROM saas_entitlement_usage u
      WHERE u.subscription_id=s.id AND NOT u.available) AS usage_missing_count,
    (SELECT count(*) FROM saas_entitlement_usage u
      JOIN saas_plan_entitlement e ON e.plan_code=s.plan_code AND e.entitlement_code=u.entitlement_code
      WHERE u.subscription_id=s.id AND u.available AND NOT e.unlimited
        AND u.usage_value > e.limit_value AND e.over_limit_action='BLOCK') AS blocked_count
  FROM tenant t
  LEFT JOIN saas_subscription s ON s.tenant_id=t.id
  LEFT JOIN saas_plan p ON p.code=s.plan_code
  ORDER BY t.code;
END $$;

-- 由订阅 id 反查它属于哪个租户：订阅是租户级表，平台运营要跨租户读写，
-- 必须先确定「本次事务的目标租户」（ADR-0005：每次业务事务选定一个明确目标租户）。
-- 这条函数只返回目标租户 id，不返回业务数据；真正的读写仍在目标租户的 RLS 上下文里做。
-- p_permission 传进来的原因：读与写是两种风险，id → 租户这条路径本身也要按权限码分。
--
-- 找不到时**返回 NULL 而不是抛错**：「不存在」是 404，「没有权限」是 403，
-- 两者混在一个异常里会让调用方分不清自己是被拦了还是问错了。
CREATE FUNCTION saas_platform_subscription_tenant(hash text, p_id uuid, p_permission text) RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE tid uuid; BEGIN
  PERFORM saas_platform_gate(hash,p_permission);
  SELECT tenant_id INTO tid FROM saas_subscription WHERE id=p_id;
  RETURN tid;
END $$;

-- 目标租户档案（编码与名称）。
--
-- 为什么需要这条专用路径：仓里**刻意不给 identity_app 直读 tenant 表的权限**
-- （V3 只授了 permission / page_resource / tenant_closure 的 SELECT，tenant 一律经
-- SECURITY DEFINER 函数出去）——「租户集合只能由已认证成员关系推导」这条纪律的落地方式。
-- 应用侧直接 select ... from tenant 会拿到 42501（没有权限），不是查不到。
CREATE FUNCTION saas_platform_tenant_info(hash text, p_tenant uuid, p_permission text)
RETURNS TABLE(tenant_code text, tenant_name text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM saas_platform_gate(hash,p_permission);
  -- ⚠️ 必须显式起别名：PL/pgSQL 的 RETURN QUERY 会把**结果集的列名换成查询里的列名**，
  -- 不是用 RETURNS TABLE 里声明的名字。不起别名的话调用方拿到的是 code / name，
  -- 按 tenant_code 取值会直接抛「Field "tenant_code" is not contained in row type」。
  RETURN QUERY SELECT t.code AS tenant_code, t.name AS tenant_name FROM tenant t WHERE t.id=p_tenant;
END $$;

REVOKE EXECUTE ON FUNCTION saas_platform_gate(text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_register_role(text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_create_plan(text,text,text,int,text,int,int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_update_plan(text,text,text,int,text,text,int,int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_create_entitlement(text,text,text,text,numeric,boolean,text,text,text,int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_update_entitlement(text,text,text,text,numeric,boolean,text,text,text,int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_delete_entitlement(text,text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_subscription_list(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_subscription_tenant(text,uuid,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION saas_platform_tenant_info(text,uuid,text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION saas_platform_gate(text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_register_role(text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_create_plan(text,text,text,int,text,int,int) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_update_plan(text,text,text,int,text,text,int,int) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_create_entitlement(text,text,text,text,numeric,boolean,text,text,text,int) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_update_entitlement(text,text,text,text,numeric,boolean,text,text,text,int) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_delete_entitlement(text,text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_subscription_list(text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_subscription_tenant(text,uuid,text) TO identity_app;
GRANT EXECUTE ON FUNCTION saas_platform_tenant_info(text,uuid,text) TO identity_app;

-- ===========================================================================
-- 12. 页面权限
-- ===========================================================================
-- 平台运营专属权限码。**刻意不授给 OWNER 角色**：
-- V53 的先例是「授给 OWNER」，本模块相反——授给 OWNER 等于每个租户都能看套餐与订阅，
-- 直接违背蓝图「仅平台管理员可见，租户不可访问」。
-- 授权路径是 saas_platform_register_role()：它把权限码授给**被登记的平台运营角色**，
-- 而不是授给某个固定编码的角色。
INSERT INTO page_resource(code,name) VALUES ('identity.plan','套餐与权益')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.plan.'||action,'identity.plan',action
FROM unnest(ARRAY['read','create','update']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO page_resource(code,name) VALUES ('identity.subscription','订阅与权益用量')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.subscription.'||action,'identity.subscription',action
FROM unnest(ARRAY['read','create','update']::text[]) action
ON CONFLICT (code) DO NOTHING;

-- ===========================================================================
-- 13. 出厂套餐
-- ===========================================================================
-- 出厂套餐随代码发布（与 dict_catalog 同一先例）：平台级表只授予 SELECT，
-- 应用侧改不了它们，要改就走迁移。
--
-- 上限值全是**数量**。订单量 / API 调用量 / AI 用量的计量周期是月，
-- 店铺数 / SKU 数 / 用户数是存量口径（TOTAL）。
--
-- 「超限策略」逐条给出口径：阻断（BLOCK）= 直接不让你用；
-- 降级（DEGRADE）= 还能用但功能受限；放行（ALLOW）= 只记录不拦。
INSERT INTO saas_plan(code,name,tier_level,description,status,trial_days,sort_order) VALUES
  ('TRIAL','体验版',10,
   '用于售前试用的入门套餐：店铺与 SKU 上限很低，试用结束即到期，不自动转正式。','ACTIVE',14,10),
  ('STARTER','标准版',20,
   '小型跨境卖家：1 个店铺、1000 个 SKU、每月 3000 单、5 个成员席位。','ACTIVE',0,20),
  ('GROWTH','成长版',30,
   '成长中的团队：5 个店铺、20000 个 SKU、每月 30000 单、20 个成员席位。','ACTIVE',0,30),
  ('ENTERPRISE','旗舰版',40,
   '多店铺集团：店铺数与 SKU 数不设上限，按月计量订单量与 API 调用量。','ACTIVE',0,40);

INSERT INTO saas_plan_entitlement
  (plan_code,entitlement_code,entitlement_kind,limit_value,unlimited,over_limit_action,period,unit,sort_order) VALUES
  ('TRIAL','SHOP','SHOP_COUNT',1,false,'BLOCK','TOTAL','个',10),
  ('TRIAL','SKU','SKU_COUNT',100,false,'BLOCK','TOTAL','个',20),
  ('TRIAL','ORDER','ORDER_VOLUME',100,false,'BLOCK','MONTH','单',30),
  ('TRIAL','MEMBER','USER_COUNT',2,false,'BLOCK','TOTAL','人',40),
  ('TRIAL','API','API_CALL',1000,false,'DEGRADE','MONTH','次',50),
  ('TRIAL','AI','AI_USAGE',100,false,'DEGRADE','MONTH','次',60),

  ('STARTER','SHOP','SHOP_COUNT',1,false,'BLOCK','TOTAL','个',10),
  ('STARTER','SKU','SKU_COUNT',1000,false,'BLOCK','TOTAL','个',20),
  ('STARTER','ORDER','ORDER_VOLUME',3000,false,'DEGRADE','MONTH','单',30),
  ('STARTER','MEMBER','USER_COUNT',5,false,'BLOCK','TOTAL','人',40),
  ('STARTER','API','API_CALL',20000,false,'DEGRADE','MONTH','次',50),
  ('STARTER','AI','AI_USAGE',500,false,'DEGRADE','MONTH','次',60),

  ('GROWTH','SHOP','SHOP_COUNT',5,false,'BLOCK','TOTAL','个',10),
  ('GROWTH','SKU','SKU_COUNT',20000,false,'DEGRADE','TOTAL','个',20),
  ('GROWTH','ORDER','ORDER_VOLUME',30000,false,'DEGRADE','MONTH','单',30),
  ('GROWTH','MEMBER','USER_COUNT',20,false,'BLOCK','TOTAL','人',40),
  ('GROWTH','API','API_CALL',200000,false,'DEGRADE','MONTH','次',50),
  ('GROWTH','AI','AI_USAGE',5000,false,'DEGRADE','MONTH','次',60),

  ('ENTERPRISE','SHOP','SHOP_COUNT',0,true,'ALLOW','TOTAL','个',10),
  ('ENTERPRISE','SKU','SKU_COUNT',0,true,'ALLOW','TOTAL','个',20),
  ('ENTERPRISE','ORDER','ORDER_VOLUME',0,true,'ALLOW','MONTH','单',30),
  ('ENTERPRISE','MEMBER','USER_COUNT',0,true,'ALLOW','TOTAL','人',40),
  ('ENTERPRISE','API','API_CALL',0,true,'ALLOW','MONTH','次',50),
  ('ENTERPRISE','AI','AI_USAGE',0,true,'ALLOW','MONTH','次',60);
