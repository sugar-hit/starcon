-- 17.5.3.1 审计日志。
--
-- V3 建的 identity_audit 只有「谁、对什么、做了什么、什么时候」六个骨架列，
-- 没有资源类型、没有授权上下文、没有变更前后值，而且只 GRANT 了 INSERT：
-- 记录写得进去，但没人查得到。本迁移补齐读侧与变更留痕，让模块真正收口。
--
-- 边界（重要）：蓝图说「上游 = 全部业务系统」，但 catalog / channel 各有自己的
-- audit_event 表，而 AGENTS.md 禁止跨服务数据库访问。所以本表是 identity
-- **自己拥有的**审计事实，只覆盖本服务的关键操作；跨服务的统一审计视图属于
-- 事件总线/合规系统的工作，不在这里用跨库 SQL 硬凑。

-- ------------------------------------------------------------------
-- 1. 补列
-- ------------------------------------------------------------------

-- 资源类型：只有 resource_id 时既无法按类型检索，也无法渲染可读标题。
-- 先建可空并回填存量行，再置为 NOT NULL，避免迁移在非空表上失败。
ALTER TABLE identity_audit ADD COLUMN resource_type varchar(64);
-- 资源可读标题（角色编码 / 用户名 / 部门名 …）在写入时快照，
-- 否则成员被移除或改名后，历史审计行会渲染成空白。
ALTER TABLE identity_audit ADD COLUMN resource_label varchar(200);
-- 操作人可读名同样快照：审计要能长期自解释，不能靠 JOIN 一张会变的表。
ALTER TABLE identity_audit ADD COLUMN actor_name varchar(100);
-- 授权上下文：事后解释「当时凭什么放行这个操作」的唯一依据（17.5 角色与数据范围）。
-- 列名避开 PostgreSQL 保留字 AUTHORIZATION，所以用 authorization_context。
ALTER TABLE identity_audit ADD COLUMN authorization_context jsonb NOT NULL DEFAULT '{}'::jsonb;
-- 变更详情：{"before":{...},"after":{...}}，写入侧已脱敏（见 AuditRedactor）。
ALTER TABLE identity_audit ADD COLUMN changes jsonb NOT NULL DEFAULT '{"before":null,"after":null}'::jsonb;

-- 存量回填：V3 时期写入的动作名是确定的，资源类型可以由动作前缀无歧义推出。
UPDATE identity_audit SET resource_type = CASE
  WHEN action LIKE 'Role%' THEN 'identity.role'
  WHEN action LIKE 'Member%' OR action = 'OwnerTransferred' THEN 'identity.member'
  WHEN action LIKE 'Department%' THEN 'identity.department'
  WHEN action LIKE 'Child%' THEN 'tenant.child'
  WHEN action LIKE 'LegalEntity%' THEN 'identity.legal_entity'
  ELSE 'unknown'
END WHERE resource_type IS NULL;
ALTER TABLE identity_audit ALTER COLUMN resource_type SET NOT NULL;

-- actor_id 指向 app_user，同库可回填出可读名；查不到（如账号已注销）就留空，
-- 不用别的东西顶替——审计宁可缺一个名字，也不要写一个假的。
UPDATE identity_audit a SET actor_name = u.username FROM app_user u WHERE u.id = a.actor_id AND a.actor_name IS NULL;

-- 存量行的变更前后值在 V3 时期根本没采集，如实标记为不可用，
-- 而不是补一个空对象假装「变更过但是空的」。
UPDATE identity_audit SET changes = '{"before":null,"after":null,"unavailable":true}'::jsonb;
-- 列级 CHECK 而不是枚举表：格式（小写点分标识符）在这里就该被约束住，
-- 但资源种类留给后续服务扩展，查询端也不必因为加一类资源就改数据库。
ALTER TABLE identity_audit ADD CONSTRAINT identity_audit_resource_type_format
  CHECK(resource_type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$');

-- ------------------------------------------------------------------
-- 2. 检索索引
-- ------------------------------------------------------------------
-- 蓝图点名三种检索维度：按资源、按操作人、按时间。时间索引 V3 已建。
CREATE INDEX identity_audit_resource ON identity_audit(tenant_id, resource_type, resource_id, occurred_at DESC);
CREATE INDEX identity_audit_actor ON identity_audit(tenant_id, actor_id, occurred_at DESC);
CREATE INDEX identity_audit_action ON identity_audit(tenant_id, action, occurred_at DESC);

-- ------------------------------------------------------------------
-- 3. 不可篡改（数据库层保证）
-- ------------------------------------------------------------------
-- 应用层「不写 UPDATE」不是保证：任何人拿到连接都能直接改。这里用触发器
-- 对所有角色（含表属主与迁移账号）挡住 UPDATE/DELETE，让不可篡改成为事实。
CREATE FUNCTION identity_audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '审计记录不可修改或删除（17.5.3.1 不可篡改）' USING ERRCODE='42501';
END $$;

CREATE TRIGGER identity_audit_immutable_guard
  BEFORE UPDATE OR DELETE ON identity_audit
  FOR EACH ROW EXECUTE FUNCTION identity_audit_immutable();

-- 双重防线：权限层不给 UPDATE/DELETE，触发器层即使被绕过也照样拒。
REVOKE UPDATE, DELETE ON identity_audit FROM identity_app;
GRANT SELECT, INSERT ON identity_audit TO identity_app;

-- RLS 策略 V3 已建（ENABLE + FORCE + isolation），新增列全部带 tenant_id，
-- 原策略已完整覆盖，无需重建。

-- ------------------------------------------------------------------
-- 4. 授权上下文取操作人可读名
-- ------------------------------------------------------------------
-- 只返回「当前会话自己」的成员名，不泄露他人身份，所以不需要 member.read 权限：
-- 否则一个只被授予审计读的合规角色，写审计时会因为解析不出操作人而失败。
CREATE FUNCTION session_actor_name(hash text) RETURNS text
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 SELECT u.username FROM session_access(hash) a
 JOIN membership m ON m.id=a.membership_id AND m.tenant_id=a.tenant_id
 JOIN app_user u ON u.id=m.user_id
$$;
REVOKE EXECUTE ON FUNCTION session_actor_name(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION session_actor_name(text) TO identity_app;

-- ------------------------------------------------------------------
-- 5. 页面授权
-- ------------------------------------------------------------------
-- 审计只读：记录由各操作点追加，不存在「编辑审计」这种动作，
-- 所以只注册 read 一项权限，检索、详情与导出共用。
INSERT INTO page_resource(code,name) VALUES ('identity.audit','审计日志')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission VALUES ('identity.audit.read','identity.audit','read')
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code='identity.audit.read' ON CONFLICT DO NOTHING;