-- 17.3.2.1 角色分配
--
-- 蓝图对本模块的硬约束只有一句，但很重：**分配时必须同时设定范围**——没有范围的角色等于全量权限，
-- 实践中极易误配。这条必须在 DDL 层挡住，否则「先建分配、以后再补范围」这种半成品状态会一直存在于库里。
--
-- 因此本次迁移做三件事：
--   1. role_assignment 补上**生效时间**（effective_from / effective_until）与撤销终态（status + revoked_*）。
--      生效时间不是展示字段：session_access 按时间窗口过滤，未生效 / 已过期的分配不产生任何权限。
--   2. 新增 role_assignment_scope：**法人 / 店铺 / 仓库 / 部门**的对象级范围引用。
--   3. 用一条**延迟约束触发器**保证「有角色必有范围」，不是应用层 if。
--
-- 为什么范围是独立的子表而不是 role_assignment 上的一列：
--   一个范围要同时表达「哪一类对象」（法人/店铺/仓库/部门）和「哪几个对象」，这两件事都会变长，
--   塞进 text[] 会把类型信息降级成字符串，校验只能靠正则。拆成 (scope_type, scope_id) 才有真正的约束力。
--
-- 关于跨服务对象：店铺归 channel、仓库归 warehouse，本服务不持有它们的表，
-- 按 AGENTS.md「禁止跨服务 FK」与 V5 法人档案的同一处置，这两类 scope_id 是**不透明引用**，
-- 存在性由调用方授权 API 保证，不建跨库外键；法人与部门由本服务持有，必须在库内校验存在且属于本租户。

-- ------------------------------------------------------------------
-- 1. 分配记录：生效时间 + 撤销终态
-- ------------------------------------------------------------------
ALTER TABLE role_assignment
  ADD COLUMN effective_from timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN effective_until timestamptz,
  ADD COLUMN status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
  ADD COLUMN assigned_by uuid,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN note varchar(500),
  ADD COLUMN revoked_at timestamptz,
  ADD COLUMN revoked_by uuid;

-- 「谁分配的」必须落到 user_id：授权变更事后追责全靠这一列。存量行没有这个信息，
-- 回填成「该租户所有者，没有就回填这条分配所属成员本人」——宁可粗一点，也不能留空。
UPDATE role_assignment ra SET assigned_by = coalesce(
  (SELECT m2.user_id FROM membership m2
     JOIN role r2 ON r2.tenant_id=m2.tenant_id AND r2.code='OWNER'
     JOIN role_assignment oa ON oa.tenant_id=m2.tenant_id AND oa.membership_id=m2.id AND oa.role_id=r2.id
   WHERE m2.tenant_id=ra.tenant_id ORDER BY m2.id LIMIT 1),
  (SELECT m3.user_id FROM membership m3 WHERE m3.id=ra.membership_id AND m3.tenant_id=ra.tenant_id));
ALTER TABLE role_assignment ALTER COLUMN assigned_by SET NOT NULL;

-- 有效期倒挂 = 永远不生效的一条分配，是配置错误不是边界情况。
ALTER TABLE role_assignment ADD CHECK(effective_until IS NULL OR effective_until>effective_from);
-- 撤销是终态：ACTIVE 不带撤销痕迹，REVOKED 必须带谁在什么时候撤的（与 V42 层级授权同口径）。
-- 两者不对齐就是脏数据，而审计要靠它还原「当时到底被撤了没有」。
ALTER TABLE role_assignment ADD CHECK((status='ACTIVE')=(revoked_at IS NULL));
ALTER TABLE role_assignment ADD CHECK((status='REVOKED')=(revoked_by IS NOT NULL));

-- 本迁移的核心：scope_mode 的默认值 'ALL' 就是「忘了填范围」的来源。
-- V1 建表时给了 DEFAULT 'ALL'，于是任何一条 INSERT 只要省略范围列，就静默变成租户内全量权限。
-- 删掉默认值之后，范围必须被显式写出来——这才是蓝图要的那道闸。
ALTER TABLE role_assignment ALTER COLUMN scope_mode DROP DEFAULT;

-- ------------------------------------------------------------------
-- 2. 对象级范围引用（法人 / 店铺 / 仓库 / 部门）
-- ------------------------------------------------------------------
-- scope_code 是冗余的展示名：法人与部门由库内触发器**覆写**成真实编码，不可能对不上；
-- 店铺与仓库由调用方提供，因为这两个服务的表不在本库（见文件头说明）。
CREATE TABLE role_assignment_scope (
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  membership_id uuid NOT NULL,
  role_id uuid NOT NULL,
  scope_type text NOT NULL CHECK(scope_type IN ('LEGAL_ENTITY','SHOP','WAREHOUSE','DEPARTMENT')),
  scope_id uuid NOT NULL,
  scope_code text NOT NULL CHECK(btrim(scope_code)<>''),
  PRIMARY KEY(tenant_id,membership_id,role_id,scope_type,scope_id),
  -- 级联删除：分配被删 / 角色被删 / 成员被删时，范围引用不允许变成孤儿行。
  -- 孤儿行比没有范围更危险——它看起来还带着一个范围，实际已经不在任何分配之下。
  FOREIGN KEY(tenant_id,membership_id,role_id)
    REFERENCES role_assignment(tenant_id,membership_id,role_id) ON DELETE CASCADE
);

ALTER TABLE role_assignment_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_assignment_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON role_assignment_scope
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- 分配列表按成员取（主键前缀已覆盖）；这个索引服务反向查询「谁在这个店铺/仓库上有权限」，
-- 那是 17.3.3.1 越权检测与 17.3.2.2 数据范围落地的入口，缺了只能全表扫。
CREATE INDEX role_assignment_scope_object ON role_assignment_scope(tenant_id,scope_type,scope_id);

GRANT SELECT,INSERT,UPDATE,DELETE ON role_assignment_scope TO identity_app;

-- 法人 / 部门是本服务持有的对象，必须在库内校验存在且属于本租户。
-- 不这么做的话，一个跨租户的 scope_id 能被写进来，权限预览会显示一个本租户根本不存在的作用范围。
-- 顺带把 scope_code 覆写成真实编码：范围名不该由调用方自称（与 V5 法人档案「不采信调用方自称」一致）。
CREATE FUNCTION validate_role_assignment_scope() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE real_code text; BEGIN
  IF NEW.scope_type='LEGAL_ENTITY' THEN
    SELECT code INTO real_code FROM legal_entity WHERE tenant_id=NEW.tenant_id AND id=NEW.scope_id;
    IF real_code IS NULL THEN RAISE EXCEPTION 'Legal entity unavailable' USING ERRCODE='23503'; END IF;
  ELSIF NEW.scope_type='DEPARTMENT' THEN
    SELECT code INTO real_code FROM department WHERE tenant_id=NEW.tenant_id AND id=NEW.scope_id;
    IF real_code IS NULL THEN RAISE EXCEPTION 'Department unavailable' USING ERRCODE='23503'; END IF;
  ELSE
    -- SHOP / WAREHOUSE 归 channel / warehouse 服务，本库没有对应表，不做存在性校验。
    RETURN NEW;
  END IF;
  NEW.scope_code := real_code;
  RETURN NEW;
END $$;
CREATE TRIGGER role_assignment_scope_validate BEFORE INSERT OR UPDATE ON role_assignment_scope
  FOR EACH ROW EXECUTE FUNCTION validate_role_assignment_scope();

-- ------------------------------------------------------------------
-- 3. 「有角色必有范围」——DDL 层硬约束
-- ------------------------------------------------------------------
-- 为什么用延迟约束触发器而不是 CHECK：约束要跨两张表（分配行 + 它的范围行），
-- CHECK 只能看同一行，做不到「至少有一条范围」。
--
-- 为什么延迟到提交时才判定：写入路径是「先删旧范围、再写新范围」的多步操作
-- （改一次分配要删掉旧行再插新的）。若在语句末尾立刻判定，
-- 「删完全部旧范围、还没插新的」这个中间态会被误判成违规。
--
-- 判定口径（与 V3 既有 scope_mode 语义保持一致，不新造枚举）：
--   ALL      → 显式选择了租户内全部，本身就是一种范围声明，合法；
--   SELF     → 行级范围（仅本人数据），没有对象可列，合法；
--   其余     → 必须显式列出至少一个对象范围，否则拒绝。
-- 即：库里不存在「有角色、没范围」的中间状态。
--
-- 反向也要挡：ALL / SELF 已经自带完整范围，再挂对象范围是自相矛盾的记录
-- （既声明全量、又声明只限某几个对象）。这类行会让下游不知道该信哪一半，所以直接拒绝。
CREATE FUNCTION require_role_assignment_scope() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE tid uuid; mid uuid; rid uuid; mode text; live text; objects int; BEGIN
  IF TG_OP='DELETE' THEN
    tid:=OLD.tenant_id; mid:=OLD.membership_id; rid:=OLD.role_id;
  ELSE
    tid:=NEW.tenant_id; mid:=NEW.membership_id; rid:=NEW.role_id;
  END IF;
  -- 分配行已经不在了（被删 / 被撤销）：没有分配就没有「缺少范围」这回事。
  SELECT scope_mode,status INTO mode,live
    FROM role_assignment WHERE tenant_id=tid AND membership_id=mid AND role_id=rid;
  IF NOT FOUND OR live<>'ACTIVE' THEN RETURN NULL; END IF;
  SELECT count(*) INTO objects FROM role_assignment_scope
    WHERE tenant_id=tid AND membership_id=mid AND role_id=rid;
  IF mode IN ('ALL','SELF') THEN
    IF objects>0 THEN
      RAISE EXCEPTION 'Tenant-wide and self scopes cannot carry scope objects' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
  END IF;
  IF objects=0 THEN
    RAISE EXCEPTION 'Role assignment requires an explicit data scope' USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER role_assignment_scope_present
  AFTER INSERT OR UPDATE ON role_assignment
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_role_assignment_scope();
CREATE CONSTRAINT TRIGGER role_assignment_scope_present
  AFTER INSERT OR UPDATE OR DELETE ON role_assignment_scope
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_role_assignment_scope();

-- ------------------------------------------------------------------
-- 4. 生效时间必须真的生效
-- ------------------------------------------------------------------
-- 只在页面上显示「生效时间」而不参与鉴权，等于给了一个假的时间字段：管理员以为下周才生效的分配
-- 今天就已经在给权限了。所以 session_access 同时按 status 与时间窗口过滤。
--
-- 函数返回结构与 V3 完全一致（{roleId,roleCode,scopeMode,permissions}）——
-- catalog 的 AccessService.Grant 按这四个字段反序列化，形状不能因为本模块加列而变化。
CREATE OR REPLACE FUNCTION session_access(hash text)
RETURNS TABLE(user_id uuid,tenant_id uuid,tenant_name text,membership_id uuid,department_id uuid,permissions text[],grants jsonb)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT s.user_id,m.tenant_id,m.name,m.id,mem.department_id,
 coalesce((SELECT array_agg(DISTINCT rp.permission_code) FROM role_assignment ra JOIN role r ON r.id=ra.role_id AND r.tenant_id=ra.tenant_id AND r.status='ACTIVE'
 JOIN role_permission rp ON rp.role_id=r.id AND rp.tenant_id=r.tenant_id WHERE ra.membership_id=m.id AND ra.tenant_id=m.tenant_id
 AND ra.status='ACTIVE' AND ra.effective_from<=now() AND (ra.effective_until IS NULL OR ra.effective_until>now())),ARRAY[]::text[]),
 coalesce((SELECT jsonb_agg(jsonb_build_object('roleId',r.id,'roleCode',r.code,'scopeMode',ra.scope_mode,'permissions',
 (SELECT coalesce(jsonb_agg(rp.permission_code),'[]'::jsonb) FROM role_permission rp WHERE rp.role_id=r.id AND rp.tenant_id=r.tenant_id)))
 FROM role_assignment ra JOIN role r ON r.id=ra.role_id AND r.tenant_id=ra.tenant_id AND r.status='ACTIVE' WHERE ra.membership_id=m.id AND ra.tenant_id=m.tenant_id
 AND ra.status='ACTIVE' AND ra.effective_from<=now() AND (ra.effective_until IS NULL OR ra.effective_until>now())),'[]'::jsonb)
 FROM auth_session s JOIN app_user u ON u.id=s.user_id AND u.status='ACTIVE'
 JOIN LATERAL memberships_for(s.user_id) m ON m.id=s.membership_id JOIN membership mem ON mem.id=m.id
 WHERE s.token_hash=hash AND s.expires_at>now()
$$;

-- V3 的 create_child 用的是位置式 INSERT INTO role_assignment VALUES(...)，本迁移给表加了列之后
-- 位置式 INSERT 会直接报 "INSERT has more target columns than expressions"，连带所有新建子租户失败。
-- 因此在同一个版本里把它改成显式列名：升级必须一次成功，不能留一个「迁移能过、建子租户全崩」的状态。
CREATE OR REPLACE FUNCTION create_child(hash text,cid uuid,child_code text,child_name text,owner_user uuid,mid uuid,rid uuid,max_depth int) RETURNS void
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
 -- 新租户所有者是即时的租户内全量：ALL 本身就是一种显式范围声明，满足上一节的约束触发器。
 INSERT INTO role_assignment(tenant_id,membership_id,role_id,scope_mode,assigned_by)
  VALUES(cid,mid,rid,'ALL',owner_user);
END $$;

-- ------------------------------------------------------------------
-- 5. 页面权限
-- ------------------------------------------------------------------
-- 撤销是状态流转不是资源删除，所以没有 delete 权限码（与 V42 层级授权同一取舍）。
INSERT INTO page_resource VALUES ('identity.assignment','角色分配');
INSERT INTO permission VALUES
 ('identity.assignment.read','identity.assignment','read'),
 ('identity.assignment.create','identity.assignment','create'),
 ('identity.assignment.update','identity.assignment','update');
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code
  FROM role r CROSS JOIN permission p
  WHERE r.code='OWNER' AND p.page_code='identity.assignment'
  ON CONFLICT DO NOTHING;
