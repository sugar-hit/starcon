CREATE TABLE tenant (
 id uuid PRIMARY KEY, parent_tenant_id uuid REFERENCES tenant(id), code text NOT NULL UNIQUE,
 name text NOT NULL, status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED')),
 CHECK(parent_tenant_id IS DISTINCT FROM id)
);
CREATE TABLE app_user (id uuid PRIMARY KEY, username text NOT NULL UNIQUE, password_hash text NOT NULL, status text NOT NULL DEFAULT 'ACTIVE');
CREATE TABLE membership (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), user_id uuid NOT NULL REFERENCES app_user(id),
 status text NOT NULL DEFAULT 'ACTIVE', UNIQUE(tenant_id,user_id), UNIQUE(tenant_id,id)
);
CREATE TABLE role (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), code text NOT NULL, UNIQUE(tenant_id,id), UNIQUE(tenant_id,code));
CREATE TABLE permission (code text PRIMARY KEY, page_code text NOT NULL, action text NOT NULL, UNIQUE(page_code,action));
CREATE TABLE role_permission (
 tenant_id uuid NOT NULL REFERENCES tenant(id), role_id uuid NOT NULL, permission_code text NOT NULL REFERENCES permission(code),
 PRIMARY KEY(tenant_id,role_id,permission_code), FOREIGN KEY(tenant_id,role_id) REFERENCES role(tenant_id,id)
);
CREATE TABLE role_assignment (
 tenant_id uuid NOT NULL, membership_id uuid NOT NULL, role_id uuid NOT NULL,
 scope_mode text NOT NULL DEFAULT 'ALL' CHECK(scope_mode='ALL'),
 PRIMARY KEY(tenant_id,membership_id,role_id),
 FOREIGN KEY(tenant_id,membership_id) REFERENCES membership(tenant_id,id),
 FOREIGN KEY(tenant_id,role_id) REFERENCES role(tenant_id,id)
);
-- Sessions are global authentication records, not tenant-owned business data.
CREATE TABLE auth_session (
 token_hash text PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id),
 membership_id uuid NOT NULL REFERENCES membership(id), expires_at timestamptz NOT NULL
);
CREATE INDEX auth_session_expiry ON auth_session(expires_at);
INSERT INTO permission SELECT 'catalog.'||page||'.'||action,'catalog.'||page,action
FROM unnest(ARRAY['spu','sku','attribute','research','compliance','assets']) page
CROSS JOIN unnest(ARRAY['read','create','update','delete']) action;

-- Identity's narrow authenticated functions resolve membership before business RLS context exists.
-- Service account cannot directly enumerate users, membership or sessions.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
CREATE FUNCTION authenticate(login text) RETURNS TABLE(user_id uuid,password_hash text)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT id,password_hash FROM app_user WHERE username=login AND status='ACTIVE'
$$;
CREATE FUNCTION memberships_for(uid uuid) RETURNS TABLE(id uuid,tenant_id uuid,name text)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT m.id,m.tenant_id,t.name FROM membership m JOIN tenant t ON t.id=m.tenant_id
 WHERE m.user_id=uid AND m.status='ACTIVE' AND NOT EXISTS (
   WITH RECURSIVE chain AS (SELECT id,parent_tenant_id,status FROM tenant WHERE id=t.id
     UNION ALL SELECT p.id,p.parent_tenant_id,p.status FROM tenant p JOIN chain c ON c.parent_tenant_id=p.id)
   SELECT 1 FROM chain WHERE status<>'ACTIVE')
$$;
CREATE FUNCTION create_session(hash text,uid uuid,mid uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM memberships_for(uid) WHERE id=mid) THEN RAISE EXCEPTION 'Membership unavailable'; END IF;
 INSERT INTO auth_session VALUES(hash,uid,mid,now()+interval '8 hours'); END $$;
CREATE FUNCTION session_access(hash text) RETURNS TABLE(user_id uuid,tenant_id uuid,tenant_name text,permissions text[])
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT s.user_id,m.tenant_id,m.name,coalesce(array_agg(DISTINCT rp.permission_code) FILTER(WHERE rp.permission_code IS NOT NULL),ARRAY[]::text[])
 FROM auth_session s JOIN app_user u ON u.id=s.user_id AND u.status='ACTIVE'
 JOIN LATERAL memberships_for(s.user_id) m ON m.id=s.membership_id
 LEFT JOIN role_assignment ra ON ra.membership_id=m.id AND ra.tenant_id=m.tenant_id
 LEFT JOIN role_permission rp ON rp.role_id=ra.role_id AND rp.tenant_id=ra.tenant_id
 WHERE s.token_hash=hash AND s.expires_at>now() GROUP BY s.user_id,m.tenant_id,m.name
$$;
CREATE FUNCTION revoke_session(hash text) RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$ DELETE FROM auth_session WHERE token_hash=hash $$;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO starcon_app;
GRANT EXECUTE ON FUNCTION authenticate(text), memberships_for(uuid), create_session(text,uuid,uuid), session_access(text), revoke_session(text) TO starcon_app;
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['membership','role','role_permission','role_assignment'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
