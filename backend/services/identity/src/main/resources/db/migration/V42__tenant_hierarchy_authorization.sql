-- 17.1.1.3 层级授权
--
-- ADR-0005 的硬约束：父子关系**只表示结构，不自动赋权**。集团要看子公司数据必须在这里显式授权一次。
-- 所以本表的行是「父租户 → 子租户」这条边上的一处开口，没有行就是拒绝（默认拒绝，不是默认放行）。
--
-- 为什么 tenant_id 记的是授权方（父）而不是被访问方（子）：
--   RLS 的锚点必须是「当前会话所在租户」能直接命中的那一列。会话解析出来的租户就是授权方
--   （否则会出现「进入子租户反而能给自己发授权」这种自证循环），V3 的 tenant_closure 也是
--   同一条约定：闭包行归属于祖先，不归属于后代。
--
-- 判定入口刻意不接收 X-Tenant-Id，也不接收调用方自称的身份：grant_owner() 只收会话令牌哈希，
-- 授权方一律由 session_access(hash) 推导。与 V40 同一套窄权限纪律。

-- ------------------------------------------------------------------
-- 授权记录
-- ------------------------------------------------------------------
-- 一行 = 一处授权开口的**一个数据域**：同一对父子租户可以只授权 catalog.spu 读、
-- 另一行授权 catalog.sku 写。按 (授权方, 子租户, 数据域) 唯一，避免出现两条互相矛盾的开口
-- （一条 READ 一条 WRITE）导致判定结果取决于扫描顺序。
CREATE TABLE tenant_hierarchy_grant (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 child_tenant_id uuid NOT NULL REFERENCES tenant(id),
 -- 数据域。留空数组 = 该子租户全域授权（显式选择，不靠 null 表达「全域」，
 -- 免得「没填域」和「填了全域」在审计上长得一样）。
 domains text[] NOT NULL CHECK(cardinality(domains)>0),
 access_level text NOT NULL CHECK(access_level IN ('READ','WRITE')),
 valid_from timestamptz NOT NULL,
 -- NULL = 永久。有效期是本模块的核心语义，不设默认：一行授权必须自己说清楚到什么时候为止，
 -- 或者显式声明永久，否则「忘了填」会静默变成无限期授权。
 valid_until timestamptz,
 CHECK(valid_until IS NULL OR valid_until>valid_from),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
 revoked_at timestamptz,
 revoked_by uuid,
 -- 撤销是终态：ACTIVE 不带 revoked_at，REVOKED 必须带。两者不对齐就是脏数据，
 -- 而审计要靠它还原「当时到底被撤了没有」。
 CHECK((status='ACTIVE')=(revoked_at IS NULL)),
 CHECK((status='REVOKED')=(revoked_by IS NOT NULL)),
 note varchar(500),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,child_tenant_id),
 -- 子租户不能授权给自己，也不能反向授权（祖先给后代，反过来不行）。
 CHECK(child_tenant_id<>tenant_id)
);

ALTER TABLE tenant_hierarchy_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_hierarchy_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON tenant_hierarchy_grant
 USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- 列表按授权方 + 状态过滤，判定按 (授权方, 子租户) 命中唯一约束，两条路径都吃索引。
CREATE INDEX tenant_hierarchy_grant_tenant ON tenant_hierarchy_grant(tenant_id,status);
CREATE INDEX tenant_hierarchy_grant_child ON tenant_hierarchy_grant(child_tenant_id);

GRANT SELECT,INSERT,UPDATE,DELETE ON tenant_hierarchy_grant TO identity_app;

-- ------------------------------------------------------------------
-- 页面权限
-- ------------------------------------------------------------------
-- 授权配置本身是一组页面 CRUD：谁能配、谁能看，走既有 page_resource + role 模型，
-- 不新造一套「谁能授权」的旁路角色。
INSERT INTO page_resource VALUES ('tenant.hierarchy','层级授权');
INSERT INTO permission VALUES
 ('tenant.hierarchy.read','tenant.hierarchy','read'),
 ('tenant.hierarchy.create','tenant.hierarchy','create'),
 ('tenant.hierarchy.update','tenant.hierarchy','update');
-- 既有 OWNER 角色补齐；新建的子租户由 V3 的 create_child 把 permission 全量授给 OWNER，
-- 所以只有存量租户需要在这里补。
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code
 FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.page_code='tenant.hierarchy'
 ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------------
-- 授权方：当前租户所有者
-- ------------------------------------------------------------------
-- 与 17.1.1.1 维护基本信息、17.1.1.2 管子租户、17.2.2.1 管成员同一道闸（administration_tenant = Owner + ALL），
-- 不另立一套「谁能授权」的身份：能让集团看到子公司数据的人，必须是子公司数据的所有者本人。
CREATE FUNCTION hierarchy_owner(hash text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE tid uuid; BEGIN
 tid:=administration_tenant(hash);
 RETURN tid;
END $$;

-- ------------------------------------------------------------------
-- 授权列表
-- ------------------------------------------------------------------
-- 只返回本租户（授权方）自己配置的授权。子租户看得到「父租户授权了我什么」是**另一个模块**
-- 的事（被授权方的知情权），这里刻意不做：本模块的边界是「授权方配置自己的开口」。
--
-- note 必须写 ::text：列是 varchar(500) 而 OUT 参数是 text，PL/pgSQL 的 RETURN QUERY 不接受
-- 这两个类型之间的赋值转换，会直接报 "structure of query does not match function result type"。
-- 这是实现中真实踩到的坑，不是预防性写法。
CREATE FUNCTION hierarchy_grants(hash text) RETURNS TABLE(
 grant_id uuid,child_tenant_id uuid,child_code text,child_name text,child_status text,
 domains text[],access_level text,valid_from timestamptz,valid_until timestamptz,
 status text,note text,created_by uuid,created_at timestamptz,revoked_at timestamptz,revoked_by uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; BEGIN
 SELECT * INTO a FROM session_access(hash);
 IF a IS NULL THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
 RETURN QUERY
 SELECT g.id,g.child_tenant_id,t.code,t.name,t.status,g.domains,g.access_level,
  g.valid_from,g.valid_until,g.status,g.note::text,g.created_by,g.created_at,g.revoked_at,g.revoked_by
 FROM tenant_hierarchy_grant g JOIN tenant t ON t.id=g.child_tenant_id
 WHERE g.tenant_id=a.tenant_id AND 'tenant.hierarchy.read'=ANY(a.permissions)
 ORDER BY t.code;
END $$;

-- ------------------------------------------------------------------
-- 建立授权
-- ------------------------------------------------------------------
-- 三道服务端校验，缺一道都会留下「看起来授权成功、实际不生效」或「越权」的记录：
--   1. 授权方必须是当前租户所有者（hierarchy_owner）。
--   2. child 必须是本租户的**真后代**（tenant_closure depth>0）。层级关系来自 17.1.1.2，
--      闭包表由 create_child 维护；借闭包判定比自己 JOIN parent_tenant_id 更可靠——多层树下
--      直系与隔代都是后代，而层级可能被调整过。
--   3. domains 逐个校验格式，不接受任意字符串。
CREATE FUNCTION create_hierarchy_grant(
 hash text,new_id uuid,child uuid,new_domains text[],new_access text,
 new_from timestamptz,new_until timestamptz,actor uuid,new_note text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE parent uuid; d text; BEGIN
 parent:=hierarchy_owner(hash);
 IF new_access NOT IN ('READ','WRITE') THEN RAISE EXCEPTION 'Unsupported access level' USING ERRCODE='23514'; END IF;
 IF new_until IS NOT NULL AND new_until<=new_from THEN RAISE EXCEPTION 'Validity range is empty' USING ERRCODE='23514'; END IF;
 FOREACH d IN ARRAY new_domains LOOP
  IF d !~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$' THEN RAISE EXCEPTION 'Malformed domain' USING ERRCODE='23514'; END IF;
 END LOOP;
 -- depth>0 排除租户自己：授权自己没有任何意义，且会绕过 RLS 的边界语义。
 IF NOT EXISTS(SELECT 1 FROM tenant_closure WHERE tenant_id=parent AND descendant_id=child AND depth>0)
 THEN RAISE EXCEPTION 'Child tenant unavailable' USING ERRCODE='42501'; END IF;
 INSERT INTO tenant_hierarchy_grant
  (id,tenant_id,child_tenant_id,domains,access_level,valid_from,valid_until,note,created_by)
 VALUES (new_id,parent,child,new_domains,new_access,new_from,new_until,new_note,actor);
END $$;

-- ------------------------------------------------------------------
-- 修改授权
-- ------------------------------------------------------------------
-- 只开放「改范围、改有效期」。撤销走 revoke_hierarchy_grant 单独一条路径：
-- 撤销是审计事件，要留下 revoked_at / revoked_by，改授权不该留下这两列，
-- 否则事后无法区分「被撤销过」和「从没生效过」。
CREATE FUNCTION update_hierarchy_grant(
 hash text,gid uuid,new_domains text[],new_access text,
 new_from timestamptz,new_until timestamptz,new_note text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE parent uuid; d text; BEGIN
 parent:=hierarchy_owner(hash);
 IF new_access NOT IN ('READ','WRITE') THEN RAISE EXCEPTION 'Unsupported access level' USING ERRCODE='23514'; END IF;
 IF new_until IS NOT NULL AND new_until<=new_from THEN RAISE EXCEPTION 'Validity range is empty' USING ERRCODE='23514'; END IF;
 FOREACH d IN ARRAY new_domains LOOP
  IF d !~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$' THEN RAISE EXCEPTION 'Malformed domain' USING ERRCODE='23514'; END IF;
 END LOOP;
 UPDATE tenant_hierarchy_grant
  SET domains=new_domains,access_level=new_access,valid_from=new_from,valid_until=new_until,note=new_note
  WHERE id=gid AND tenant_id=parent AND status='ACTIVE';
 IF NOT FOUND THEN RAISE EXCEPTION 'Grant unavailable' USING ERRCODE='42501'; END IF;
END $$;

-- ------------------------------------------------------------------
-- 撤销授权
-- ------------------------------------------------------------------
-- 终态：撤销后不再授权，且不再允许改回 ACTIVE。理由与 ADR-0005 一致——「清楚的隔离边界和撤权路径」
-- 需要撤权是真的撤，而不是可以被任何人随手翻回来的开关。
CREATE FUNCTION revoke_hierarchy_grant(hash text,gid uuid,actor uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE parent uuid; BEGIN
 parent:=hierarchy_owner(hash);
 UPDATE tenant_hierarchy_grant
  SET status='REVOKED',revoked_at=now(),revoked_by=actor
  WHERE id=gid AND tenant_id=parent AND status='ACTIVE';
 IF NOT FOUND THEN RAISE EXCEPTION 'Grant unavailable' USING ERRCODE='42501'; END IF;
END $$;

-- ------------------------------------------------------------------
-- 授权判定（下游全系统数据过滤的唯一入口）
-- ------------------------------------------------------------------
-- 蓝图写明「授权判定在服务端完成」，ADR-0005 写明「汇总查询按目标逐一鉴权，不扩大 RLS 为全部后代」。
-- 两者在这里合成一个形状：**按 (目标租户, 数据域, 所需权限) 逐次判定**，
-- 既不给「进了一个子租户就等于拿到全部后代」的开关，也不接受调用方自称的身份。
--
-- 返回值是一行判定结果而不是 boolean：下游要能区分「没有授权记录」与「授权过期了」——
-- 这两种情况的处置完全不同（前者找管理员配，后者等过期或续期），只给 true/false 会让调用方
-- 把两者当成同一件事，日志里也看不出授权为什么失效。
CREATE FUNCTION evaluate_hierarchy_access(
 hash text,target uuid,domain text,need_write boolean)
RETURNS TABLE(
 allowed boolean,reason text,access_level text,valid_from timestamptz,valid_until timestamptz,grant_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; g record; BEGIN
 SELECT * INTO a FROM session_access(hash);
 IF a IS NULL THEN RETURN QUERY SELECT false,'SESSION_INVALID'::text,NULL::text,NULL::timestamptz,NULL::timestamptz,NULL::uuid; RETURN; END IF;
 IF target IS NULL THEN RETURN QUERY SELECT false,'TARGET_UNSPECIFIED'::text,NULL::text,NULL::timestamptz,NULL::timestamptz,NULL::uuid; RETURN; END IF;
 -- 授权方自己没有层级角色时，任何跨租户访问都不放行。
 -- 配置了授权 ≠ 每个成员都自动获得跨租户读权限：谁有资格用这处开口，
 -- 仍由该租户自己的角色分配决定（ADR-0005：操作与数据范围必须在同一角色分配中满足）。
 IF NOT ('tenant.hierarchy.read'=ANY(a.permissions))
 THEN RETURN QUERY SELECT false,'ROLE_NOT_AUTHORIZED'::text,NULL::text,NULL::timestamptz,NULL::timestamptz,NULL::uuid; RETURN; END IF;
 SELECT * INTO g FROM tenant_hierarchy_grant
  WHERE tenant_id=a.tenant_id AND child_tenant_id=target FOR SHARE;
 IF NOT FOUND THEN RETURN QUERY SELECT false,'NO_GRANT'::text,NULL::text,NULL::timestamptz,NULL::timestamptz,NULL::uuid; RETURN; END IF;
 IF g.status='REVOKED' THEN RETURN QUERY SELECT false,'REVOKED'::text,g.access_level,g.valid_from,g.valid_until,g.id; RETURN; END IF;
 IF g.valid_from>now() THEN RETURN QUERY SELECT false,'NOT_YET_VALID'::text,g.access_level,g.valid_from,g.valid_until,g.id; RETURN; END IF;
 IF g.valid_until IS NOT NULL AND g.valid_until<=now()
 THEN RETURN QUERY SELECT false,'EXPIRED'::text,g.access_level,g.valid_from,g.valid_until,g.id; RETURN; END IF;
 -- 域不匹配放在有效期之后：一条已过期的授权无论域匹配与否都是拒绝，
 -- 先报 EXPIRED 才能让排查的人看到真正的原因。
 IF NOT (domain=ANY(g.domains))
 THEN RETURN QUERY SELECT false,'DOMAIN_NOT_GRANTED'::text,g.access_level,g.valid_from,g.valid_until,g.id; RETURN; END IF;
 IF need_write AND g.access_level<>'WRITE'
 THEN RETURN QUERY SELECT false,'WRITE_NOT_GRANTED'::text,g.access_level,g.valid_from,g.valid_until,g.id; RETURN; END IF;
 RETURN QUERY SELECT true,'GRANTED'::text,g.access_level,g.valid_from,g.valid_until,g.id;
END $$;

REVOKE EXECUTE ON FUNCTION hierarchy_owner(text),hierarchy_grants(text),
 create_hierarchy_grant(text,uuid,uuid,text[],text,timestamptz,timestamptz,uuid,text),
 update_hierarchy_grant(text,uuid,text[],text,timestamptz,timestamptz,text),
 revoke_hierarchy_grant(text,uuid,uuid),
 evaluate_hierarchy_access(text,uuid,text,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION hierarchy_grants(text),
 create_hierarchy_grant(text,uuid,uuid,text[],text,timestamptz,timestamptz,uuid,text),
 update_hierarchy_grant(text,uuid,text[],text,timestamptz,timestamptz,text),
 revoke_hierarchy_grant(text,uuid,uuid),
 evaluate_hierarchy_access(text,uuid,text,boolean) TO identity_app;
