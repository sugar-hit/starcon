-- 07 库存的页面资源与权限码（warehouse 服务接入）。
--
-- ADR-0005：L3 子功能 = 一张 page_resource，按角色配 CRUD。
-- 蓝图 07 本期落地的是 7.1.1.1 库存总览这一个 L3 页面资源：
--   warehouse.inventory → /inventory 库存总览
--
-- permission.page_code 有指向 page_resource 的外键，因此必须先注册页面再插权限。
-- 权限码前缀 warehouse.* 由 warehouse 服务校验（AccessService.require），identity 只负责
-- 登记与授权 —— 租户和角色权限的事实所有者始终是 identity，warehouse 不自建角色表。
INSERT INTO page_resource(code,name) VALUES
 ('warehouse.inventory','库存总览')
ON CONFLICT (code) DO NOTHING;

-- unnest(ARRAY[...]) 的元素类型由字面量推断，这里显式给 text[]，
-- 否则 PG 会把单元素数组推成 text 再与 varchar 比较，报 "text = integer"（与 V5 同坑）。
INSERT INTO permission SELECT 'warehouse.'||page||'.'||action,'warehouse.'||page,action
FROM unnest(ARRAY['inventory']::text[]) page
CROSS JOIN unnest(ARRAY['read','create','update','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;

-- 与 V3 / V4 同规则：租户所有者始终拥有全部页面权限，
-- 否则新接入的模块对既有租户不可见（存量 OWNER 角色也要补上）。
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'warehouse.%' ON CONFLICT DO NOTHING;
