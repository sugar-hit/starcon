-- 17.1.1.1 租户列表
--
-- 这是全系统**唯一允许的跨租户列表查询路径**，所以这份迁移的全部设计都服务于一件事：
-- 租户集合只能由「当前已认证用户的 membership 关系」推导，绝不由调用方指定。
--
-- 窄权限路径怎么保证（三层，缺一层就出现越权）：
--   1. 函数签名里只有 hash（会话令牌哈希），**没有任何 tenant_id 参数**。
--      客户端想传租户也没有落点——这不是「不校验」，是结构上无处可传。
--   2. 租户集合一律从 memberships_for(a.user_id) 起算，而 memberships_for 内部只认
--      membership.status='ACTIVE' 且祖先链上没有非 ACTIVE 的租户。
--   3. 运行时角色 identity_app 是 nosuperuser nobypassrls，membership/role/role_assignment
--      都是 ENABLE + FORCE RLS，所以就算应用代码写错也只能看到 app.current_tenant 那一个租户。
-- X-Tenant-Id 头在整条链路上没有任何读取点：租户来自令牌解析出的成员关系。
--
-- 「最后登录」为什么挂在 membership 上而不是 app_user 上：
-- 它回答的是「我上次进入**这个**租户是什么时候」，而不是「我上次登录系统是什么时候」。
-- 同一个人可以是 3 个租户的成员，三个租户各有各的最后进入时刻；挂在用户上会把
-- 三个租户显示成同一个时间，而那个时间对另外两个租户没有任何含义。
-- 两个入口都要打点：登录（create_session）与切换租户（switch_membership），
-- 漏掉切换的话「只切换不重新登录」的用户最后登录永远是空的。

ALTER TABLE membership ADD COLUMN last_login_at timestamptz;

-- 窄权限路径的入口形状：memberships_for(uid) 按 user_id 过滤。
-- 原来的 UNIQUE(tenant_id,user_id) 只有 tenant_id 前缀，走 user_id 只能全表扫。
CREATE INDEX membership_user ON membership(user_id);
-- 租户内成员计数（当前上下文的成员数）按 (tenant_id, status) 过滤。
CREATE INDEX membership_tenant_status ON membership(tenant_id,status);

-- 登录时打点。函数返回类型没变，但函数体变了，PL/pgSQL 必须整体重建。
DROP FUNCTION create_session(text,uuid,uuid);
CREATE FUNCTION create_session(hash text,uid uuid,mid uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM memberships_for(uid) WHERE id=mid) THEN RAISE EXCEPTION 'Membership unavailable'; END IF;
 INSERT INTO auth_session VALUES(hash,uid,mid,now()+interval '8 hours');
 UPDATE membership SET last_login_at=now() WHERE id=mid;
END $$;
REVOKE EXECUTE ON FUNCTION create_session(text,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION create_session(text,uuid,uuid) TO identity_app;

-- 切换租户同样算一次「进入」。少了这一句，反复切换的用户最后登录会一直是登录时那一次。
DROP FUNCTION switch_membership(text,uuid);
CREATE FUNCTION switch_membership(hash text,mid uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE uid uuid; BEGIN
 SELECT user_id INTO uid FROM session_access(hash);
 IF uid IS NULL OR NOT EXISTS(SELECT 1 FROM memberships_for(uid) WHERE id=mid)
 THEN RAISE EXCEPTION 'Membership unavailable' USING ERRCODE='42501'; END IF;
 UPDATE auth_session SET membership_id=mid WHERE token_hash=hash;
 UPDATE membership SET last_login_at=now() WHERE id=mid;
END $$;
REVOKE EXECUTE ON FUNCTION switch_membership(text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION switch_membership(text,uuid) TO identity_app;

-- 可切换租户列表：名称、标识、状态、角色、最后登录，外加 is_current 标出当前上下文。
--
-- 为什么角色在这里现算而不是回读 session_access 的 grants：grants 只解当前会话所在的那个
-- 租户，而列表要一次给出**所有**可切换租户里的角色，只能按 membership 逐个租户聚合。
-- 聚合仍然只认 role_assignment 里真实存在的分配——没有成员关系的租户根本进不了 FROM，
-- 而不是靠租户表 join 出去再在前端过滤。
CREATE FUNCTION switchable_tenants(hash text) RETURNS TABLE(
 membership_id uuid,tenant_id uuid,code text,name text,status text,
 roles text[],last_login_at timestamptz,is_current boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; BEGIN
 -- 会话无效必须 401，不能退化成「空列表」：空列表会被前端渲染成「你还没有租户」，
 -- 于是用户以为自己被踢了，实际是登录过期。session_access 无行就是无行，直接拒。
 SELECT * INTO a FROM session_access(hash);
 IF a IS NULL THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
 RETURN QUERY
 SELECT m.id,m.tenant_id,t.code,t.name,t.status,
  coalesce(rl.roles,ARRAY[]::text[]),m.last_login_at,m.id=a.membership_id
 FROM memberships_for(a.user_id) f
 JOIN membership m ON m.id=f.id AND m.tenant_id=f.tenant_id
 JOIN tenant t ON t.id=f.tenant_id
 LEFT JOIN LATERAL (
  SELECT array_agg(DISTINCT r.code ORDER BY r.code) AS roles
  FROM role_assignment ra
  JOIN role r ON r.id=ra.role_id AND r.tenant_id=ra.tenant_id AND r.status='ACTIVE'
  WHERE ra.membership_id=m.id AND ra.tenant_id=m.tenant_id) rl ON true
 ORDER BY t.code;
END $$;
REVOKE EXECUTE ON FUNCTION switchable_tenants(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION switchable_tenants(text) TO identity_app;

-- 当前租户上下文档案。蓝图的「输出 = 可切换租户列表 + 当前上下文标识」中的后者。
-- 仍然只有 hash 参数：本租户由会话推导，没有「查某个指定租户」的入口。
CREATE FUNCTION tenant_context(hash text) RETURNS TABLE(
 tenant_id uuid,code text,name text,status text,parent_tenant_id uuid,
 member_count int,child_count int,last_login_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; BEGIN
 SELECT * INTO a FROM session_access(hash);
 IF a IS NULL THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
 RETURN QUERY
 SELECT t.id,t.code,t.name,t.status,t.parent_tenant_id,
  (SELECT count(*)::int FROM membership ms WHERE ms.tenant_id=t.id AND ms.status='ACTIVE'),
  (SELECT count(*)::int FROM tenant tc WHERE tc.parent_tenant_id=t.id),
  m.last_login_at
 FROM tenant t JOIN membership m ON m.id=a.membership_id WHERE t.id=a.tenant_id;
END $$;
REVOKE EXECUTE ON FUNCTION tenant_context(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenant_context(text) TO identity_app;

-- 租户管理员维护本租户基本信息。归属判定直接复用 administration_tenant（Owner + ALL），
-- 与 17.1.1.2 子租户、17.2.2.1 成员管理同一道闸，不另立一套「谁是管理员」。
--
-- code 不开放修改：tenant.code 是全局唯一且已被外部集成引用，改它等于让所有已配置的
-- 映射指向一个不存在的租户，而蓝图只要求「基本信息」维护。名称与状态覆盖了基本信息本身。
CREATE FUNCTION update_tenant_profile(hash text,new_name text,new_status text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE tid uuid; BEGIN
 tid:=administration_tenant(hash);
 UPDATE tenant SET name=btrim(new_name),status=new_status WHERE id=tid;
END $$;
REVOKE EXECUTE ON FUNCTION update_tenant_profile(text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION update_tenant_profile(text,text,text) TO identity_app;