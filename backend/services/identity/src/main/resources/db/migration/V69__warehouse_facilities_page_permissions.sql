-- ===========================================================================
-- 8.1.1.1 仓库档案页面资源与权限码（warehouse 服务接入）
-- ===========================================================================
--
-- ADR-0005：L3 子功能 = 一张 page_resource，按角色配 CRUD。
-- 蓝图 08 的 8.1「仓库与库位」是 L3，前端入口 /warehouse/facilities。
-- 本迁移为 8.1.1.1 仓库列表注册它自己的页面资源。
--
-- ---------------------------------------------------------------------------
-- 为什么不复用 warehouse.inventory（V8 已注册）
-- ---------------------------------------------------------------------------
-- warehouse 服务的既有端点都挂在 warehouse.inventory 下面（7.1.x / 7.2.x / 7.4.x / 7.5.x
-- 全部共用这一组码）。仓库档案**不能**也挂上去，理由是权限语义：
--
--   * 8.1.1.1 是**主数据写入**页面（新增仓库），7.1.x 是**库存聚合只读**页面。
--     两者的风险面完全不同 —— 建一个仓会改变全公司库存归属的顶层结构，
--     调错一个库存数字不会。
--   * 绑在同一组权限码上会让「能改库存总览的人也能建仓」，反过来「被授权建仓的人
--     顺带拿到全部库存读写」。授权配置界面也会把两者显示成一个条目，用户无法分开授予。
--
-- 「同一服务共用一组页面权限」是 7.x 阶段的临时状态，不是要延续到 8.x 的规矩：
-- 8.1 仓库与库位与 7.1 库存本来就是 L3 与 L4 之外的两个独立 L3。
--
-- ---------------------------------------------------------------------------
-- 刻意只注册 read + create，不注册 update / delete
-- ---------------------------------------------------------------------------
-- 与 identity V53 数据字典同一纪律：**没有端点的权限码不注册**。
-- 8.1.1.1 仓库列表的职责范围是「展示 + 筛选 + 新增」，
-- 改仓库属性属 8.1.1.2 详情、行为参数属 8.1.1.3 配置，两条车道落地时各自补自己的码。
-- 现在就放行 update/delete 只会让人以为「仓库能改能删」，而界面上没有任何入口。
--
-- permission.page_code 有指向 page_resource 的外键，因此必须先注册页面再插权限。
INSERT INTO page_resource(code,name) VALUES ('warehouse.facilities','仓库档案')
ON CONFLICT (code) DO NOTHING;

-- 与 V8 同一条坑：unnest(ARRAY[...]) 的元素类型由字面量推断，显式给 text[]，
-- 否则 PG 会把单元素数组推成 text 再与 varchar 比较，报 "text = integer"。
INSERT INTO permission SELECT 'warehouse.'||page||'.'||action,'warehouse.'||page,action
FROM unnest(ARRAY['facilities']::text[]) page
CROSS JOIN unnest(ARRAY['read','create']::text[]) action
ON CONFLICT (code) DO NOTHING;

-- 与 V8 / V3 同规则：租户所有者始终拥有全部页面权限，
-- 否则新接入的模块对既有租户不可见（存量 OWNER 角色也要补上）。
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'warehouse.facilities.%' ON CONFLICT DO NOTHING;