-- 17.2.2.2 成员开通与入职
--
-- 与 17.2.2.1 成员管理的分工：那边管「已经在册的成员」怎么改（改部门、改角色、停用、移除），
-- 这一模块管「一个人怎么进来」——建立账号（新建本地账号，或关联已有全局账号）、
-- 建立目标租户的成员关系、设定初始部门与初始角色。
--
-- 蓝图点名排除邮件邀请、接受令牌与自助激活，所以本表**刻意没有任何邀请态列**：
-- 没有 invitation_token、没有 expires_at、没有 consumed_at、没有 revoked_at。
-- 将来引入邮件邀请时，这些列必须和「单次使用、防枚举、撤销」规则一起补齐，
-- 不能只加一列 token 就当邀请功能做完了。
--
-- 凭据纪律（本模块特有）：初始凭据由**服务端生成**，只在创建那一次响应里返回明文。
-- 因此本表只记「凭据发过没有」与时刻，**不记凭据，也不记哈希**——
-- 哈希本来就在 app_user.password_hash，再存一份只是多一个可能泄露的点，
-- 而且任何读路径（列表、详情、审计、CSV 导出）都拿不到明文，也没有列可查。

CREATE TABLE member_onboarding (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 membership_id uuid NOT NULL,
 user_id uuid NOT NULL,
 -- CREATED = 这次真的建了全局账号（服务端生成了初始凭据）；
 -- LINKED = 账号早就在别的租户用过，这次只是把成员关系接进本租户（不产生也不返回凭据）。
 -- 两个值不并列成同一个「成功」，因为管理员对后续动作的预期完全不同：
 -- LINKED 的凭据在人家自己手上，不需要（也不应该）由本次操作再发一次。
 account_mode text NOT NULL CHECK(account_mode IN ('CREATED','LINKED')),
 -- 初始部门与初始角色都进快照：入职时给了什么，之后被改过也要能对比出来。
 -- 部门与角色本体仍以 membership / role_assignment 为准，这里只是入职当时的答案。
 department_id uuid,
 role_codes text[] NOT NULL DEFAULT ARRAY[]::text[],
 credential_issued boolean NOT NULL,
 credential_issued_at timestamptz,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','COMPLETED')),
 completed_at timestamptz,
 completed_by uuid,
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,membership_id) REFERENCES membership(tenant_id,id),
 FOREIGN KEY(tenant_id,department_id) REFERENCES department(tenant_id,id),
 -- 这几条 CHECK 不是形式主义：它们把「凭据发过的记录必须有时刻」「建了账号就必须发过凭据」
 -- 「完成的记录必须同时有时刻和操作人」写成数据库不变量。
 -- 少一条，脏数据就能写进来，而事后没有任何地方能看出哪一行不对齐。
 CHECK((credential_issued)=(credential_issued_at IS NOT NULL)),
 CHECK((account_mode='CREATED')=(credential_issued)),
 CHECK((status='COMPLETED')=(completed_at IS NOT NULL)),
 CHECK((status='COMPLETED')=(completed_by IS NOT NULL))
);

ALTER TABLE member_onboarding ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_onboarding FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON member_onboarding
 USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- 列表按租户倒序读（最新入职在最前），完成操作按 (租户, 状态) 命中待办集合，两条路径都吃索引。
CREATE INDEX member_onboarding_tenant ON member_onboarding(tenant_id,created_at DESC);
CREATE INDEX member_onboarding_pending ON member_onboarding(tenant_id,status);

GRANT SELECT,INSERT,UPDATE,DELETE ON member_onboarding TO identity_app;

-- ------------------------------------------------------------------
-- 页面权限
-- ------------------------------------------------------------------
-- 开通入职是一组**独立的页面 CRUD**，不挂在 identity.member 下面：
-- 成员管理是「改在册的人」，开通入职是「放人进来」——后者的风险面（账号创建、初始凭据、
-- 批量建立成员关系）明显更宽，绑在同一组权限码上会让「能改部门的人也能发账号」。
INSERT INTO page_resource VALUES ('identity.onboarding','成员开通');
INSERT INTO permission VALUES
 ('identity.onboarding.read','identity.onboarding','read'),
 ('identity.onboarding.create','identity.onboarding','create'),
 ('identity.onboarding.update','identity.onboarding','update');
-- 既有 OWNER 角色补齐；新建的子租户由 V3 的 create_child 把 permission 全量授给 OWNER，
-- 所以只有存量租户需要在这里补（与 V42 的 tenant.hierarchy 同一处理）。
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code
 FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.page_code='identity.onboarding'
 ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------------
-- 入职记录列表
-- ------------------------------------------------------------------
-- 只返回本租户的入职记录，租户一律由 session_access(hash) 推导：
-- 函数签名里没有任何 tenant_id 参数，客户端伪造 X-Tenant-Id 在这里结构上无处可传。
--
-- username 经 app_user 关联解析（与 member_names 同一套约定：会话成员关系 → 租户 → 成员 → 用户）。
-- 部门名必须写 ::text：列是 varchar(100) 而 OUT 参数是 text，PL/pgSQL 的 RETURN QUERY
-- 不接受这两个类型之间的赋值转换，会直接报
-- "structure of query does not match function result type"（V42 实现时真实踩到过）。
CREATE FUNCTION onboardings(hash text) RETURNS TABLE(
 onboarding_id uuid,membership_id uuid,user_id uuid,account_mode text,
 username text,department_id uuid,department_name text,role_codes text[],
 credential_issued boolean,status text,
 created_by uuid,created_at timestamptz,completed_at timestamptz,completed_by uuid)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT o.id,o.membership_id,o.user_id,o.account_mode,u.username,o.department_id,d.name::text,
  o.role_codes,o.credential_issued,o.status,o.created_by,o.created_at,o.completed_at,o.completed_by
 FROM session_access(hash) a
 JOIN member_onboarding o ON o.tenant_id=a.tenant_id
 JOIN app_user u ON u.id=o.user_id
 LEFT JOIN department d ON d.id=o.department_id AND d.tenant_id=o.tenant_id
 WHERE 'identity.onboarding.read'=ANY(a.permissions)
 ORDER BY o.created_at DESC,o.id;
$$;
REVOKE EXECUTE ON FUNCTION onboardings(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION onboardings(text) TO identity_app;

-- ------------------------------------------------------------------
-- 开通入职
-- ------------------------------------------------------------------
-- 一次调用完成「账号（新建或关联）+ 成员关系 + 初始角色 + 入职记录」，全部在同一事务里：
-- 中途失败不留半条记录。返回 account_mode 是为了让调用方知道**这次到底该不该返回初始凭据**——
-- 关联已有账号时服务端根本不知道那个人的密码，返回一个假的比不返回更糟。
--
-- 入职闸与 17.2.2.1 成员管理、17.1.1.2 子租户、17.1.1.3 层级授权同一道：
-- administration_tenant = 当前租户所有者 + ALL 范围，不另立一套「谁能放人进来」的身份。
-- 放一个人进来等于给了他本租户的身份，必须是所有者本人。
CREATE FUNCTION onboard_member(
 hash text,new_membership uuid,new_onboarding uuid,login text,new_user uuid,
 new_password_hash text,dept uuid,new_roles uuid[],new_scopes text[],actor uuid)
RETURNS TABLE(membership_id uuid,account_mode text,user_id uuid,credential_issued boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE
 tid uuid; uid uuid; mode text; rid uuid; rc text; st text; sc text; i int;
BEGIN
 tid:=administration_tenant(hash);
 login:=btrim(login);
 IF login='' THEN RAISE EXCEPTION 'Username is required' USING ERRCODE='23514'; END IF;
 IF cardinality(new_roles)<>cardinality(new_scopes)
 THEN RAISE EXCEPTION 'Role and scope count mismatch' USING ERRCODE='23514'; END IF;
 -- 角色不能重复。与 role_assignment 的主键冲突相比，提前判能把
 -- 「同一个角色填了两次」变成一条能读懂的原因，而不是一个约束名。
 IF (SELECT count(DISTINCT x) FROM unnest(new_roles) x)<>cardinality(new_roles)
 THEN RAISE EXCEPTION 'Duplicate roles' USING ERRCODE='23514'; END IF;
 -- 部门必须属于本租户。复合外键只在落库那一刻兜底，这里先判是为了给出可读原因。
 IF dept IS NOT NULL AND NOT EXISTS(SELECT 1 FROM department WHERE id=dept AND tenant_id=tid)
 THEN RAISE EXCEPTION 'Department unavailable' USING ERRCODE='23514'; END IF;

 -- 账号：新建或关联。判重只看 username，与 17.2.2.1 的 member_user 同一口径。
 -- 这里与 member_user 有一处**刻意不同**：member_user 遇到已有账号且带密码会报
 -- 'Existing account cannot be reset'，因为那个密码是**管理员填的**，继续走下去等于允许
 -- 借「建成员」改掉一个已有账号的登录凭据。本模块的 new_password_hash 一律由**服务端生成**
 -- （见 MemberOnboardingController.generatePassword），调用方无从选择它，而且仅在真的
 -- 新建账号时才随响应返回一次。所以关联分支直接丢弃这份哈希：既没有可被劫持的输入，
 -- 也不需要为「这个人已有密码」去报错——「关联已有全局账号」本身就是蓝图的合法结果。
 SELECT id INTO uid FROM app_user WHERE username=login;
 IF uid IS NOT NULL THEN
  mode:='LINKED';
 ELSE
  uid:=new_user;
  INSERT INTO app_user VALUES(new_user,login,new_password_hash,'ACTIVE');
  mode:='CREATED';
 END IF;

 -- 已经在这个租户里有成员关系就不是「开通」了，是重复开通。
 -- m 必须起别名：RETURNS TABLE 的 OUT 参数里有一个叫 user_id，与 membership.user_id 同名，
 -- 不加表限定符时 PostgreSQL 会报 "column reference user_id is ambiguous"。
 IF EXISTS(SELECT 1 FROM membership m WHERE m.tenant_id=tid AND m.user_id=uid)
 THEN RAISE EXCEPTION 'Membership already exists' USING ERRCODE='23505'; END IF;
 INSERT INTO membership(id,tenant_id,user_id,status,department_id)
  VALUES(new_membership,tid,uid,'ACTIVE',dept);

 -- 初始角色。与 17.2.2.1 的 assignments() 同一套业务规则：不能给 OWNER（要走所有者转移）、
 -- 不能给停用角色、本部门范围必须先有部门、非 ALL 范围只能绑纯 catalog 权限。
 i:=0;
 FOREACH rid IN ARRAY new_roles LOOP
  i:=i+1;
  SELECT r.code,r.status INTO rc,st FROM role r WHERE r.id=rid AND r.tenant_id=tid;
  IF NOT FOUND THEN RAISE EXCEPTION 'Role unavailable' USING ERRCODE='23514'; END IF;
  IF rc='OWNER' THEN RAISE EXCEPTION 'Use owner transfer instead' USING ERRCODE='23514'; END IF;
  IF st<>'ACTIVE' THEN RAISE EXCEPTION 'Cannot assign an inactive role' USING ERRCODE='23514'; END IF;
  sc:=new_scopes[i];
  IF sc NOT IN ('ALL','DEPARTMENT','SELF')
  THEN RAISE EXCEPTION 'Invalid scope' USING ERRCODE='23514'; END IF;
  IF sc='DEPARTMENT' AND dept IS NULL
  THEN RAISE EXCEPTION 'Department scope requires a department' USING ERRCODE='23514'; END IF;
  IF sc<>'ALL' AND EXISTS(
     SELECT 1 FROM role_permission WHERE role_id=rid AND tenant_id=tid
      AND permission_code NOT LIKE 'catalog.%')
  THEN RAISE EXCEPTION 'Administrative permissions require full tenant scope' USING ERRCODE='23514'; END IF;
  INSERT INTO role_assignment(tenant_id,membership_id,role_id,scope_mode) VALUES(tid,new_membership,rid,sc);
 END LOOP;

 INSERT INTO member_onboarding(
  id,tenant_id,membership_id,user_id,account_mode,department_id,role_codes,
  credential_issued,credential_issued_at,created_by)
 VALUES(
  new_onboarding,tid,new_membership,uid,mode,dept,
  COALESCE((SELECT array_agg(r.code ORDER BY r.code) FROM unnest(new_roles) x JOIN role r ON r.id=x),ARRAY[]::text[]),
  mode='CREATED',CASE WHEN mode='CREATED' THEN now() ELSE NULL END,actor);
 RETURN QUERY SELECT new_membership,mode,uid,(mode='CREATED');
END $$;
REVOKE EXECUTE ON FUNCTION onboard_member(text,uuid,uuid,text,uuid,text,uuid,uuid[],text[],uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION onboard_member(text,uuid,uuid,text,uuid,text,uuid,uuid[],text[],uuid) TO identity_app;

-- ------------------------------------------------------------------
-- 完成入职
-- ------------------------------------------------------------------
-- 走 POST 而不是 DELETE：完成是一次有操作人和时刻的状态流转，不是资源消失。
-- 完成是终态，不允许翻回 PENDING——入职没做完可以再补一次入职记录，
-- 但把一条已完成的记录改回未完成会让「这个账号什么时候真正能用」在审计里失真。
CREATE FUNCTION complete_onboarding(hash text,oid uuid,actor uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE tid uuid; BEGIN
 tid:=administration_tenant(hash);
 UPDATE member_onboarding
  SET status='COMPLETED',completed_at=now(),completed_by=actor
  WHERE id=oid AND tenant_id=tid AND status='PENDING';
 IF NOT FOUND THEN RAISE EXCEPTION 'Onboarding unavailable' USING ERRCODE='23505'; END IF;
END $$;
REVOKE EXECUTE ON FUNCTION complete_onboarding(text,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION complete_onboarding(text,uuid,uuid) TO identity_app;