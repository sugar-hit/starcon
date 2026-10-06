-- 17.2.1.1 部门树。
--
-- 部门是**组织维度**，与法人（法律主体维度，V5）分离：不能用一个组织节点混装两者。
-- 所以 legal_entity_id 只是部门的一条归属引用，法人表本身仍是独立的货权/记账主体。
--
-- 层级放在 department 自身（parent_id 自引用），而不是新建 adjacency/closure 表：
-- 部门树是单租户内的小规模结构（百级节点），递归 CTE 一次就能取回整棵，
-- 省掉 closure 表的写入维护成本。防环不靠 DB 触发器：DB 只挡最浅的自环，
-- 深层防环在应用层用同一条递归 CTE 判断（把父挂到自己的子孙下会被拒绝）。

-- code 先可空回填再加唯一约束：V3 建表时没有编码列，存量行必须先有值，
-- 否则 CREATE UNIQUE INDEX 会因为多行 code='' 直接失败。
ALTER TABLE department ADD COLUMN code varchar(64) NOT NULL DEFAULT '';
ALTER TABLE department ADD COLUMN parent_id uuid;
ALTER TABLE department ADD COLUMN legal_entity_id uuid;
-- 负责人可为空：蓝图 17.2.1.2 明确「负责人可为空」，没有负责人的部门在审批流里指定兜底人。
-- 这里只是**字段引用**（指向某个 membership），代管/临时授权/审批链不属于本模块。
ALTER TABLE department ADD COLUMN leader_membership_id uuid;

-- 存量行回填一个租户内唯一且确定的编码。直接用 uuid 的 32 位十六进制，
-- 保证不会撞车，也保证迁移在非空表上可重复判断；后续可由业务改名。
UPDATE department SET code='DEPT-'||replace(id::text,'-','') WHERE btrim(code)='';
ALTER TABLE department ALTER COLUMN code DROP DEFAULT;

-- 部门编码在租户内唯一（报表与导出按编码引用部门）。名字的唯一性沿用 V3 的 UNIQUE(tenant_id,name)。
CREATE UNIQUE INDEX department_tenant_code ON department(tenant_id, code);

-- 最浅的自环：父节点不能是自己。更深的环（父挂到子孙下）由应用层递归校验拒绝。
ALTER TABLE department ADD CONSTRAINT department_parent_not_self CHECK(parent_id IS DISTINCT FROM id);

-- 上级部门：复合外键同时约束 tenant_id，跨租户挂靠在 DB 层就不可写入。
-- 删除语义选「拒绝」而不是级联：有子部门时删除失败，由应用层给出「有 N 个子部门」的具体原因。
ALTER TABLE department ADD FOREIGN KEY(tenant_id,parent_id) REFERENCES department(tenant_id,id);

-- 所属法人：部门只是引用法人，硬删一个法人不该被部门挡住；
-- 法人没了就把部门这一列置空（PG 15+ 的列清单形式，只清被引用列，tenant_id 不能动）。
ALTER TABLE department ADD FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity(tenant_id,id)
  ON DELETE SET NULL (legal_entity_id);

-- 负责人同理：成员被移除后部门不能悬空引用一个不存在的负责人。
ALTER TABLE department ADD FOREIGN KEY(tenant_id,leader_membership_id) REFERENCES membership(tenant_id,id)
  ON DELETE SET NULL (leader_membership_id);

-- 树查询与「我有哪些子部门」都按 (tenant_id,parent_id) 走索引。
CREATE INDEX department_tree ON department(tenant_id, parent_id);

-- RLS：department 表在 V3 已建 isolation 策略（USING/WITH CHECK 都按 tenant_id），
-- 新增列全部经由复合外键绑定 tenant_id，原策略已完整覆盖，无需重建策略。

-- 部门树一次递归取回。写成 SECURITY DEFINER 函数而不是控制器内联 SQL，
-- 与 V3 的 member_names / child_tenants 同一套约定：
--   * app_user 没有直接授予 identity_app（V1 REVOKE ALL ON ALL TABLES），
--     负责人用户名只能在 SECURITY DEFINER 里解析，否则查询会以 42501 被拒。
--   * 租户不依赖 RLS 是否生效，而是像 member_names 那样显式 join 到 session_access 的 tenant_id。
CREATE FUNCTION department_tree(hash text) RETURNS TABLE(
  id uuid, code text, name text, parent_id uuid,
  legal_entity_id uuid, legal_name text,
  leader_membership_id uuid, leader_name text,
  member_count int, depth int)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
  WITH RECURSIVE node AS (
    SELECT d.id AS id, d.tenant_id AS tenant_id, d.parent_id AS parent_id, 0 AS depth
      FROM department d
     WHERE d.parent_id IS NULL
    UNION ALL
    SELECT c.id, c.tenant_id, c.parent_id, n.depth + 1
      FROM department c
      JOIN node n ON c.parent_id = n.id AND c.tenant_id = n.tenant_id
  )
  SELECT n.id, d.code, d.name, d.parent_id, d.legal_entity_id, le.legal_name,
         d.leader_membership_id, u.username,
         -- 只统计直接挂靠本部门的人：父部门不重复累计子部门的人数
         (SELECT count(*)::int FROM membership m
           WHERE m.tenant_id = d.tenant_id AND m.department_id = d.id),
         n.depth
    FROM session_access(hash) a
    JOIN node n ON n.tenant_id = a.tenant_id
    JOIN department d ON d.id = n.id AND d.tenant_id = a.tenant_id
    LEFT JOIN legal_entity le ON le.tenant_id = d.tenant_id AND le.id = d.legal_entity_id
    LEFT JOIN membership lm ON lm.tenant_id = d.tenant_id AND lm.id = d.leader_membership_id
    LEFT JOIN app_user u ON u.id = lm.user_id
   WHERE 'identity.department.read' = ANY(a.permissions)
   ORDER BY n.depth, d.code, d.name
$$;
REVOKE EXECUTE ON FUNCTION department_tree(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION department_tree(text) TO identity_app;