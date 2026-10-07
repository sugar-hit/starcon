-- 17.2.1.2 部门负责人。
--
-- V6 只在 department 上留了一个 leader_membership_id「字段引用」，注释里也写明
-- 代管 / 临时授权 / 审批链不属于那一版。本迁移把它补成真正的负责人能力：
--
--   1. department_leader        负责人记录：谁负责 + 管理范围（scope_mode）+ 审批权限（额度/币种）
--   2. department_leader_proxy  代管：负责人不在时由谁代行，带生效区间
--   3. department_leader_grant  临时授权：在有界区间内把审批权限借给别人（额度必须有上限）
--   4. department_approval_chain  审批链引用：17.4 审批流按部门 + 时刻解析「现在谁有审批权」
--
-- 负责人可为空（蓝图的硬约束）：部门没有负责人时审批流必须指定兜底人，
-- 所以审批链返回 requiresFallback，而不是静默返回空链让下游以为「没人需要审批」。
--
-- 单一写入所有者：department_leader 是「谁是负责人 + 他的授权」的唯一事实源。
-- V6 遗留的 department.leader_membership_id 变成由触发器维护的镜像列，
-- 17.2.1.1 的树视图与既有端点因此不用改；而通过部门表单改负责人时，
-- 第二个触发器会把负责人记录物化出来并**保留**已有的范围与额度（换人不等于收回授权口径）。
-- 两边都带 OLD IS DISTINCT FROM NEW 守卫，互相触发一轮后自然收敛，不会无限递归。

-- ------------------------------------------------------------------
-- 1. 负责人记录
-- ------------------------------------------------------------------

CREATE TABLE department_leader (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL REFERENCES tenant(id),
  department_id      uuid NOT NULL,
  membership_id      uuid NOT NULL,
  -- 管理范围：本部门 / 本部门及下级 / 全租户。
  -- 默认 DEPARTMENT（只看自己这个部门）而不是 SUBTREE：授权默认取窄，
  -- 要管下级必须显式选，否则新建一个负责人就等于默认放开了整棵子树。
  scope_mode         text NOT NULL DEFAULT 'DEPARTMENT'
                       CHECK(scope_mode IN ('DEPARTMENT','SUBTREE','TENANT')),
  -- 审批权限：有额度的负责人只能批到该额度（按 approval_currency 计价），
  -- 为空表示不设上限，由 17.4 审批流自己的分级兜底。
  -- 一律 NUMERIC + BigDecimal（禁浮点记账）：金额列不落 float。
  approval_limit     numeric(19,6) CHECK(approval_limit > 0),
  approval_currency  varchar(3) CHECK(approval_currency ~ '^[A-Z]{3}$'),
  note               text NOT NULL DEFAULT '',
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个部门至多一个负责人。换人是 UPDATE membership_id，不是插第二条。
  UNIQUE(tenant_id, department_id),
  -- 复合外键同时约束 tenant_id：跨租户挂靠在 DB 层就写不进来。
  -- 成员被删则负责人记录随之消失（再由触发器把部门镜像列置空），
  -- 部门被删则级联清理，不留指向不存在部门的孤儿记录。
  FOREIGN KEY(tenant_id, department_id) REFERENCES department(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, membership_id) REFERENCES membership(tenant_id, id) ON DELETE CASCADE,
  -- 额度与币种必须同时有或同时没有：只给额度不给币种，17.4 无从判断这个数是多少钱，
  -- 「1000」在 USD 与 JPY 下是两回事，混起来就是错批。
  CHECK((approval_limit IS NULL) = (approval_currency IS NULL))
);

-- 「我负责哪些部门」「某人是不是某部门负责人」都按 membership 走。
CREATE INDEX department_leader_membership ON department_leader(tenant_id, membership_id);

-- ------------------------------------------------------------------
-- 2. 代管
-- ------------------------------------------------------------------

CREATE TABLE department_leader_proxy (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL REFERENCES tenant(id),
  department_id  uuid NOT NULL,
  membership_id  uuid NOT NULL,
  reason         text NOT NULL DEFAULT '',
  starts_at      timestamptz NOT NULL DEFAULT now(),
  -- 允许 NULL：长期代管（例如授权副总代管）是真实业务形态，强行要求截止时间
  -- 只会逼出「填一个假截止日」的数据。NULL 表示直到显式撤销为止。
  ends_at        timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, department_id) REFERENCES department(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, membership_id) REFERENCES membership(tenant_id, id) ON DELETE CASCADE,
  -- 空区间是脏数据：起止相同的「代管」在任何时刻都不生效，却会在列表里占一行。
  CHECK(ends_at IS NULL OR ends_at > starts_at)
);

-- 审批链按 (部门, 生效区间) 过滤，同一部门可能有多条历史代管。
CREATE INDEX department_leader_proxy_department ON department_leader_proxy(tenant_id, department_id, starts_at);

-- ------------------------------------------------------------------
-- 3. 临时授权
-- ------------------------------------------------------------------

CREATE TABLE department_leader_grant (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL REFERENCES tenant(id),
  department_id     uuid NOT NULL,
  membership_id     uuid NOT NULL,
  -- 临时授权必须带额度上限：借出去的审批权没有上界等于凭空多出一个负责人，
  -- 那应该走「改负责人」而不是「临时授权」。
  approval_limit    numeric(19,6) NOT NULL CHECK(approval_limit > 0),
  approval_currency varchar(3) NOT NULL CHECK(approval_currency ~ '^[A-Z]{3}$'),
  reason            text NOT NULL DEFAULT '',
  starts_at         timestamptz NOT NULL,
  -- 临时 = 有界。ends_at 必填，否则「临时授权」会退化成第二份长期负责人。
  ends_at           timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, department_id) REFERENCES department(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, membership_id) REFERENCES membership(tenant_id, id) ON DELETE CASCADE,
  CHECK(ends_at > starts_at)
);

CREATE INDEX department_leader_grant_department ON department_leader_grant(tenant_id, department_id, starts_at);

-- ------------------------------------------------------------------
-- 4. 与 department.leader_membership_id 同步（两个方向）
-- ------------------------------------------------------------------

-- 方向一：负责人记录是权威，写它就同步部门镜像列。
-- INSERT / DELETE / UPDATE 三条路径分别取 OLD 与 NEW——PL/pgSQL 里 INSERT 行的 OLD
-- 是未赋值记录，引用 OLD.x 会直接抛 "record old is not assigned yet"，所以必须按 TG_OP 分开。
CREATE FUNCTION sync_department_leader_column() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE department SET leader_membership_id = NEW.membership_id
     WHERE tenant_id = NEW.tenant_id AND id = NEW.department_id;
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE department SET leader_membership_id = NULL
     WHERE tenant_id = OLD.tenant_id AND id = OLD.department_id;
  ELSIF NEW.membership_id IS DISTINCT FROM OLD.membership_id THEN
    UPDATE department SET leader_membership_id = NEW.membership_id
     WHERE tenant_id = NEW.tenant_id AND id = NEW.department_id;
  END IF;
  RETURN NULL;
END $$;

-- 方向二：17.2.1.1 的部门表单改 leader_membership_id 时物化 / 撤销负责人记录。
-- ON CONFLICT 只改 membership_id：换人不该把原来那套 scope_mode 与审批额度一起清零——
-- 新人接手既有范围是常见操作，而「收紧授权」有独立的 PUT 端点。
CREATE FUNCTION materialise_department_leader() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.leader_membership_id IS NULL THEN
    DELETE FROM department_leader
     WHERE tenant_id = NEW.tenant_id AND department_id = NEW.id;
  ELSE
    INSERT INTO department_leader(id,tenant_id,department_id,membership_id)
    VALUES (gen_random_uuid(), NEW.tenant_id, NEW.id, NEW.leader_membership_id)
    ON CONFLICT(tenant_id,department_id) DO UPDATE SET membership_id = EXCLUDED.membership_id;
  END IF;
  RETURN NULL;
END $$;

-- 触发器顺序：部门行落库 → 物化负责人记录 → 负责人记录回写镜像列。
-- 两个方向都带「值真的变了才动手」的守卫，所以互触发一轮后值相同即停，不会无限递归：
-- 走部门表单设负责人 → 物化出记录 → 回写同值 → 守卫判否 → 收敛。
--
-- INSERT 与 UPDATE 必须拆成两个触发器：PG 不允许 INSERT 触发器的 WHEN 引用 OLD
-- （42P17），而 UPDATE 这一侧恰恰需要 OLD 才能判「值是否真的变了」。
CREATE TRIGGER department_leader_materialise_insert
  AFTER INSERT ON department FOR EACH ROW EXECUTE FUNCTION materialise_department_leader();
CREATE TRIGGER department_leader_materialise_update
  AFTER UPDATE OF leader_membership_id ON department FOR EACH ROW
  WHEN (OLD.leader_membership_id IS DISTINCT FROM NEW.leader_membership_id)
  EXECUTE FUNCTION materialise_department_leader();

CREATE TRIGGER department_leader_sync
  AFTER INSERT OR DELETE ON department_leader FOR EACH ROW EXECUTE FUNCTION sync_department_leader_column();
CREATE TRIGGER department_leader_sync_update
  AFTER UPDATE OF membership_id ON department_leader FOR EACH ROW
  WHEN (OLD.membership_id IS DISTINCT FROM NEW.membership_id)
  EXECUTE FUNCTION sync_department_leader_column();

-- ------------------------------------------------------------------
-- 5. 读路径：负责人清单
-- ------------------------------------------------------------------

-- 一个部门一行，**没有负责人的部门也要出现在结果里**（leader_* 全为 NULL）。
-- 蓝图点名「负责人可为空」，所以列表必须让运营看见「这个部门还没有负责人、
-- 审批流那边要配兜底人」，而不是把它从结果里过滤掉。
CREATE FUNCTION department_leaders(hash text) RETURNS TABLE(
  leader_id uuid, department_id uuid, department_code text, department_name text,
  membership_id uuid, username text, scope_mode text,
  approval_limit numeric, approval_currency text, note text,
  proxy_ids uuid[], grant_ids uuid[], created_at timestamptz, updated_at timestamptz)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
  WITH a AS (SELECT * FROM session_access(hash))
  SELECT l.id, d.id, d.code, d.name, l.membership_id, u.username, l.scope_mode,
         l.approval_limit, l.approval_currency, l.note,
         coalesce(array_agg(DISTINCT p.id ORDER BY p.id) FILTER(WHERE p.id IS NOT NULL), ARRAY[]::uuid[]),
         coalesce(array_agg(DISTINCT g.id ORDER BY g.id) FILTER(WHERE g.id IS NOT NULL), ARRAY[]::uuid[]),
         l.created_at, l.updated_at
    FROM a
    JOIN department d ON d.tenant_id = a.tenant_id
    LEFT JOIN department_leader l ON l.tenant_id = d.tenant_id AND l.department_id = d.id
    LEFT JOIN app_user u ON u.id = (SELECT user_id FROM membership WHERE tenant_id = a.tenant_id AND id = l.membership_id)
    LEFT JOIN department_leader_proxy p ON p.tenant_id = a.tenant_id AND p.department_id = d.id
    LEFT JOIN department_leader_grant g ON g.tenant_id = a.tenant_id AND g.department_id = d.id
   WHERE 'identity.department_leader.read' = ANY(a.permissions)
   GROUP BY l.id, d.id, d.code, d.name, l.membership_id, u.username, l.scope_mode,
            l.approval_limit, l.approval_currency, l.note, l.created_at, l.updated_at
   ORDER BY d.code, d.name
$$;
REVOKE EXECUTE ON FUNCTION department_leaders(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION department_leaders(text) TO identity_app;

-- ------------------------------------------------------------------
-- 6. 读路径：审批链引用（下游 17.4 审批流的取数口）
-- ------------------------------------------------------------------

-- 解析「某一时刻某部门的审批权落在谁身上」，顺序固定：
--   1 LEADER 正式负责人（额度与范围取自负责人记录）
--   2 PROXY  代管中的人（继承负责人的范围与额度——代的是整个职务，不是部分权限）
--   3 GRANT  临时授权的人（只拿借给他的那部分额度，范围收敛到本部门）
--
-- 只认 status='ACTIVE' 的成员与账号：停用成员不能继续审批，
-- 否则人已离职审批链还指向他，等于把单据卡死。
-- 参数 as_of 可空，空表示当前时刻；给了就能回答「上周三这条单该谁批」。
CREATE FUNCTION department_approval_chain(hash text,dept uuid,as_of timestamptz)
RETURNS TABLE(seq int, source text, membership_id uuid, username text,
               scope_mode text, approval_limit numeric, approval_currency text,
               starts_at timestamptz, ends_at timestamptz)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
  WITH a AS (SELECT * FROM session_access(hash)),
  moment AS (SELECT coalesce(as_of, now()) AS ts),
  holder AS (
    SELECT 1 AS ord, 'LEADER'::text AS source, l.membership_id, l.scope_mode,
           l.approval_limit, l.approval_currency, l.created_at AS starts_at,
           NULL::timestamptz AS ends_at
      FROM a JOIN department_leader l
        ON l.tenant_id = a.tenant_id AND l.department_id = dept
     WHERE 'identity.department_leader.read' = ANY(a.permissions)
    UNION ALL
    SELECT 2, 'PROXY', p.membership_id, l.scope_mode, l.approval_limit, l.approval_currency,
           p.starts_at, p.ends_at
      FROM a JOIN department_leader_proxy p
        ON p.tenant_id = a.tenant_id AND p.department_id = dept
      JOIN department_leader l
        ON l.tenant_id = p.tenant_id AND l.department_id = p.department_id
      CROSS JOIN moment m
     WHERE 'identity.department_leader.read' = ANY(a.permissions)
       AND p.starts_at <= m.ts AND (p.ends_at IS NULL OR p.ends_at > m.ts)
    UNION ALL
    SELECT 3, 'GRANT', g.membership_id, 'DEPARTMENT', g.approval_limit, g.approval_currency,
           g.starts_at, g.ends_at
      FROM a JOIN department_leader_grant g
        ON g.tenant_id = a.tenant_id AND g.department_id = dept
      CROSS JOIN moment m
     WHERE 'identity.department_leader.read' = ANY(a.permissions)
       AND g.starts_at <= m.ts AND g.ends_at > m.ts
  )
  SELECT h.ord, h.source, h.membership_id, u.username, h.scope_mode,
         h.approval_limit, h.approval_currency, h.starts_at, h.ends_at
    FROM holder h
    JOIN membership m ON m.id = h.membership_id AND m.status = 'ACTIVE'
    JOIN app_user u ON u.id = m.user_id AND u.status = 'ACTIVE'
   ORDER BY h.ord, u.username
$$;
REVOKE EXECUTE ON FUNCTION department_approval_chain(text,uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION department_approval_chain(text,uuid,timestamptz) TO identity_app;

-- ------------------------------------------------------------------
-- 7. RLS 与授权
-- ------------------------------------------------------------------

DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['department_leader','department_leader_proxy','department_leader_grant'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON department_leader,department_leader_proxy,department_leader_grant TO identity_app;

-- 页面与权限码：负责人是独立页面（组织管理的授权面），不复用 identity.department ——
-- 能建部门树的人不一定有权决定「谁能批多少钱」。
INSERT INTO page_resource(code,name) VALUES ('identity.department_leader','部门负责人')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.department_leader.'||action,'identity.department_leader',action
FROM unnest(ARRAY['read','create','update','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.department_leader.%' ON CONFLICT DO NOTHING;