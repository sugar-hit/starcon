-- 02 渠道与店铺的页面资源与权限码。
--
-- ADR-0005：L3 子功能 = 一张 page_resource，按角色配 CRUD。
-- 蓝图 02 在本期落地的是三个 L3 页面资源（对应 2.1 / 2.2 / 2.3）：
--   channel.registry   → /channels 平台目录
--   channel.connection → /channels 授权连接
--   channel.shop       → /channels 店铺与站点
--
-- permission.page_code 有指向 page_resource 的外键，因此必须先注册页面再插权限。
INSERT INTO page_resource(code,name) VALUES
 ('channel.registry','平台目录'),
 ('channel.connection','授权连接'),
 ('channel.shop','店铺与站点')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permission SELECT 'channel.'||page||'.'||action,'channel.'||page,action
FROM unnest(ARRAY['registry','connection','shop']) page
CROSS JOIN unnest(ARRAY['read','create','update','delete']) action
ON CONFLICT (code) DO NOTHING;

-- 与 V3 同规则：租户所有者始终拥有全部页面权限，否则新接入的模块对既有租户不可见。
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'channel.%' ON CONFLICT DO NOTHING;
