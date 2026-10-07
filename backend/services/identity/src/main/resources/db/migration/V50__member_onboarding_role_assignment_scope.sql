-- 17.2.2.2 入职写入路径补齐 17.3.2.1（V47）的不变式
--
-- 这是合并 17.3.2.1「角色分配」时暴露出来的遗留问题，不是新功能。V47 给 role_assignment
-- 补了两条硬要求，而 V45 的 onboard_member 是在 V47 之前写的，两条都没跟上：
--
--   1. assigned_by 被 V47 改成 NOT NULL（审计追责要靠它），V45 的 INSERT 没有这一列。
--      后果是**每一次**入职开通都撞 NOT NULL 违约，连 ALL 范围也不例外——
--      全量范围本来根本不需要 role_assignment_scope，却照样被挡住。
--   2. 「有角色必有范围」的延迟约束触发器：非 ALL/SELF 的分配必须挂至少一条 role_assignment_scope。
--      V45 只写 scope_mode，从来不写 role_assignment_scope，于是 DEPARTMENT 分配必然被拒。
--
-- 之前 MemberOnboardingIntegrationTest 之所以是绿的，是因为当时根本没有这两条约束：
-- 落库的是「声明了 DEPARTMENT 范围却没有任何范围对象」的残缺记录。V47 把这个假通过挡住了，
-- 暴露的是一条本来就残缺的写入路径。
--
-- ------------------------------------------------------------------
-- 为什么**不给 onboard_member 加范围对象参数**
--
-- 直觉上应该像 RoleAssignmentController 那样传 scope_ids / scope_types / scope_codes，
-- 但这里不需要，原因是本函数已有的入参已经**完全确定**了范围对象：
--
--   scope_mode 的枚举由 V3 定死为 ('ALL','DEPARTMENT','SELF')，其中只有 DEPARTMENT 是受限范围；
--   DEPARTMENT 范围的作用对象按定义就是「这个成员自己的部门」，也就是入参 dept
--   （V45 上面已经在 175-176 行要求 DEPARTMENT 必须带部门）。
--
-- 所以「范围对象」在入职场景不是一次选择，而是一个派生值。加一组数组参数进去，
-- 调用方只能填出同一个值，反而多出一条能被填错的路径（传了别的部门 id 怎么办？
-- 要不要校验？校验失败又返回什么？），还得连带改 DTO、重新生成契约、承担跨服务命名撞名的风险。
--
-- 这个派生口径与 17.2.2.1 的 AdministrationController.assignments() 完全一致
-- （它同样在 DEPARTMENT 范围时把成员自己的部门落成一条 role_assignment_scope），
-- 两处同口径，不是这一处自己发明的规则。
--
-- 顺带说明边界：法人 / 店铺 / 仓库这类跨服务范围**不属于入职**。
-- 入职回答的是「这个人属于哪个部门、初始给什么角色」，更细的对象级范围收窄
-- 由 17.3.2.1 的角色分配承担——那里才有生效时间、撤销终态与跨服务范围候选。
--
-- ------------------------------------------------------------------
-- 原子性
--
-- 开通成员、设部门、分角色、写范围、审计全部仍在这一个 SECURITY DEFINER 函数里，
-- 由同一个事务包裹：范围对象与分配行一起写、一起回滚，不存在「分配落库了但范围没落」的中间态。
-- 延迟约束触发器在提交时判定，看到的已经是完整的分配 + 范围。

CREATE OR REPLACE FUNCTION onboard_member(
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
  -- assigned_by 自 V47 起是 NOT NULL：授权变更事后追责全靠这一列。
  -- 入职同样是一次授权变更，操作人就是 actor（放一个人进来的是他，不是系统）。
  INSERT INTO role_assignment(tenant_id,membership_id,role_id,scope_mode,assigned_by)
   VALUES(tid,new_membership,rid,sc,actor);
  -- DEPARTMENT 范围补上对象范围行，V47 的「有角色必有范围」才成立。
  -- ALL / SELF 不写：V47 明确判定这两者自带完整范围，挂了对象反而是自相矛盾的记录。
  -- 编码取自 department.code；表内 validate_role_assignment_scope 触发器也会覆写成真实值。
  -- 写成 SELECT ... FROM department 而不是直接用 dept：部门在上面的 EXISTS 里已校验属于本租户，
  -- 这里若真查不到就插入 0 行，随后延迟约束触发器会拒绝本次提交——宁可失败，也不留残缺分配。
  IF sc='DEPARTMENT' THEN
   INSERT INTO role_assignment_scope(tenant_id,membership_id,role_id,scope_type,scope_id,scope_code)
    SELECT tid,new_membership,rid,'DEPARTMENT',d.id,d.code
    FROM department d WHERE d.id=dept AND d.tenant_id=tid;
  END IF;
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