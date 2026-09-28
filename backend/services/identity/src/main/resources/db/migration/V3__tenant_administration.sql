CREATE TABLE department (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), name varchar(100) NOT NULL CHECK(btrim(name)<>''),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,name)
);
ALTER TABLE membership ADD COLUMN department_id uuid;
ALTER TABLE membership ADD FOREIGN KEY(tenant_id,department_id) REFERENCES department(tenant_id,id);
ALTER TABLE role ADD COLUMN name varchar(100) NOT NULL DEFAULT '';
ALTER TABLE role ADD COLUMN status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE'));
UPDATE role SET name=CASE WHEN code='OWNER' THEN '租户所有者' ELSE code END;
ALTER TABLE role_assignment DROP CONSTRAINT role_assignment_scope_mode_check;
ALTER TABLE role_assignment ADD CHECK(scope_mode IN ('ALL','DEPARTMENT','SELF'));
CREATE TABLE page_resource(code text PRIMARY KEY,name text NOT NULL);
INSERT INTO page_resource VALUES ('catalog.spu','商品档案'),('catalog.sku','SKU'),('catalog.attribute','商品属性'),
 ('catalog.research','选品与开发'),('catalog.compliance','合规与资质'),('catalog.assets','素材与翻译'),
 ('identity.role','角色配置'),('identity.member','成员管理'),('identity.department','部门管理'),('tenant.child','子租户管理');
ALTER TABLE permission ADD FOREIGN KEY(page_code) REFERENCES page_resource(code);
INSERT INTO permission SELECT page||'.'||action,page,action
 FROM unnest(ARRAY['identity.role','identity.member','identity.department']) page
 CROSS JOIN unnest(ARRAY['read','create','update','delete']) action;
INSERT INTO permission VALUES ('tenant.child.read','tenant.child','read'),('tenant.child.create','tenant.child','create'),('tenant.child.update','tenant.child','update');
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' ON CONFLICT DO NOTHING;
-- Closure rows are owned by the ancestor; listing never expands business access.
CREATE TABLE tenant_closure (
 tenant_id uuid NOT NULL REFERENCES tenant(id), descendant_id uuid NOT NULL REFERENCES tenant(id), depth int NOT NULL CHECK(depth>=0),
 PRIMARY KEY(tenant_id,descendant_id), CHECK((tenant_id=descendant_id)=(depth=0))
);
WITH RECURSIVE tree AS (
 SELECT id ancestor,id descendant,0 depth FROM tenant
 UNION ALL SELECT tree.ancestor,t.id,tree.depth+1 FROM tree JOIN tenant t ON t.parent_tenant_id=tree.descendant
) INSERT INTO tenant_closure SELECT * FROM tree;
CREATE TABLE identity_audit (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), actor_id uuid NOT NULL, resource_id uuid NOT NULL,
 action text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE identity_outbox (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), resource_id uuid NOT NULL,
 event_type text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['department','tenant_closure','identity_audit','identity_outbox'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
CREATE INDEX identity_audit_tenant ON identity_audit(tenant_id,occurred_at);
CREATE INDEX identity_outbox_tenant ON identity_outbox(tenant_id,occurred_at);
GRANT SELECT,INSERT,UPDATE,DELETE ON membership,role,role_permission,role_assignment,department TO identity_app;
GRANT SELECT ON permission,page_resource,tenant_closure TO identity_app;
GRANT INSERT ON identity_audit,identity_outbox TO identity_app;

DROP FUNCTION session_access(text);
CREATE FUNCTION session_access(hash text) RETURNS TABLE(user_id uuid,tenant_id uuid,tenant_name text,membership_id uuid,department_id uuid,permissions text[],grants jsonb)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT s.user_id,m.tenant_id,m.name,m.id,mem.department_id,
 coalesce((SELECT array_agg(DISTINCT rp.permission_code) FROM role_assignment ra JOIN role r ON r.id=ra.role_id AND r.tenant_id=ra.tenant_id AND r.status='ACTIVE'
 JOIN role_permission rp ON rp.role_id=r.id AND rp.tenant_id=r.tenant_id WHERE ra.membership_id=m.id AND ra.tenant_id=m.tenant_id),ARRAY[]::text[]),
 coalesce((SELECT jsonb_agg(jsonb_build_object('roleId',r.id,'roleCode',r.code,'scopeMode',ra.scope_mode,'permissions',
 (SELECT coalesce(jsonb_agg(rp.permission_code),'[]'::jsonb) FROM role_permission rp WHERE rp.role_id=r.id AND rp.tenant_id=r.tenant_id)))
 FROM role_assignment ra JOIN role r ON r.id=ra.role_id AND r.tenant_id=ra.tenant_id AND r.status='ACTIVE' WHERE ra.membership_id=m.id AND ra.tenant_id=m.tenant_id),'[]'::jsonb)
 FROM auth_session s JOIN app_user u ON u.id=s.user_id AND u.status='ACTIVE'
 JOIN LATERAL memberships_for(s.user_id) m ON m.id=s.membership_id JOIN membership mem ON mem.id=m.id
 WHERE s.token_hash=hash AND s.expires_at>now()
$$;
GRANT EXECUTE ON FUNCTION session_access(text) TO identity_app;
REVOKE EXECUTE ON FUNCTION session_access(text) FROM PUBLIC;
-- Narrow control-plane functions require a live Owner session, never a caller-supplied tenant.
CREATE FUNCTION administration_tenant(hash text) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; BEGIN
 SELECT * INTO a FROM session_access(hash);
 IF a IS NULL OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(a.grants) g WHERE g->>'roleCode'='OWNER' AND g->>'scopeMode'='ALL')
 THEN RAISE EXCEPTION 'Owner required' USING ERRCODE='42501'; END IF;
 RETURN a.tenant_id;
END $$;
CREATE FUNCTION member_user(hash text,login text,new_id uuid,new_password_hash text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE uid uuid; BEGIN
 PERFORM administration_tenant(hash);
 SELECT id INTO uid FROM app_user WHERE username=login AND status='ACTIVE';
 IF uid IS NOT NULL THEN
  IF new_password_hash IS NOT NULL THEN RAISE EXCEPTION 'Existing account cannot be reset' USING ERRCODE='23514'; END IF;
  RETURN uid;
 END IF;
 IF new_password_hash IS NULL THEN RAISE EXCEPTION 'Account not found' USING ERRCODE='23514'; END IF;
 INSERT INTO app_user VALUES(new_id,login,new_password_hash,'ACTIVE'); RETURN new_id;
END $$;
CREATE FUNCTION member_names(hash text) RETURNS TABLE(id uuid,username text) LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT m.id,u.username FROM session_access(hash) a JOIN membership m ON m.tenant_id=a.tenant_id JOIN app_user u ON u.id=m.user_id
 WHERE 'identity.member.read'=ANY(a.permissions)
$$;
CREATE FUNCTION child_tenants(hash text) RETURNS TABLE(id uuid,code text,name text,status text)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT t.id,t.code,t.name,t.status FROM session_access(hash) a JOIN tenant t ON t.parent_tenant_id=a.tenant_id
 WHERE 'tenant.child.read'=ANY(a.permissions)
$$;
CREATE FUNCTION create_child(hash text,cid uuid,child_code text,child_name text,owner_user uuid,mid uuid,rid uuid,max_depth int) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE parent uuid; BEGIN
 parent:=administration_tenant(hash);
 PERFORM pg_advisory_xact_lock(hashtextextended('tenant-tree',0));
 IF (SELECT coalesce(max(depth),0) FROM tenant_closure WHERE descendant_id=parent)>=least(max_depth,5)
 THEN RAISE EXCEPTION 'Maximum tenant depth reached' USING ERRCODE='23514'; END IF;
 INSERT INTO tenant(id,parent_tenant_id,code,name) VALUES(cid,parent,child_code,child_name);
 INSERT INTO tenant_closure SELECT tenant_id,cid,depth+1 FROM tenant_closure WHERE descendant_id=parent;
 INSERT INTO tenant_closure VALUES(cid,cid,0);
 INSERT INTO membership(id,tenant_id,user_id) VALUES(mid,cid,owner_user);
 INSERT INTO role(id,tenant_id,code,name) VALUES(rid,cid,'OWNER','租户所有者');
 INSERT INTO role_permission SELECT cid,rid,code FROM permission;
 INSERT INTO role_assignment VALUES(cid,mid,rid,'ALL');
END $$;
CREATE FUNCTION update_child(hash text,cid uuid,child_name text,child_status text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE parent uuid; BEGIN
 parent:=administration_tenant(hash);
 UPDATE tenant SET name=child_name,status=child_status WHERE id=cid AND parent_tenant_id=parent;
 IF NOT FOUND THEN RAISE EXCEPTION 'Child unavailable' USING ERRCODE='23514'; END IF;
END $$;
REVOKE EXECUTE ON FUNCTION administration_tenant(text),member_user(text,text,uuid,text),member_names(text),child_tenants(text),create_child(text,uuid,text,text,uuid,uuid,uuid,int),update_child(text,uuid,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION member_user(text,text,uuid,text),member_names(text),child_tenants(text),create_child(text,uuid,text,text,uuid,uuid,uuid,int),update_child(text,uuid,text,text) TO identity_app;
CREATE FUNCTION switch_membership(hash text,mid uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE uid uuid; BEGIN
 SELECT user_id INTO uid FROM session_access(hash);
 IF uid IS NULL OR NOT EXISTS(SELECT 1 FROM memberships_for(uid) WHERE id=mid)
 THEN RAISE EXCEPTION 'Membership unavailable' USING ERRCODE='42501'; END IF;
 UPDATE auth_session SET membership_id=mid WHERE token_hash=hash;
END $$;
REVOKE EXECUTE ON FUNCTION switch_membership(text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION switch_membership(text,uuid) TO identity_app;

-- Keep the selected membership stable until an administration transaction commits.
CREATE FUNCTION lock_session(hash text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$ BEGIN
 PERFORM 1 FROM auth_session WHERE token_hash=hash AND expires_at>now() FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
END $$;
REVOKE EXECUTE ON FUNCTION lock_session(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lock_session(text) TO identity_app;
