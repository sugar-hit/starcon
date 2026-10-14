-- ===========================================================================
-- 8.1.1.2 仓库详情：仓库档案的「改属性」权限码
-- ===========================================================================
--
-- ADR-0005：L3 子功能 = 一张 page_resource，按角色配 CRUD。
-- 蓝图 08 的 8.1「仓库与库位」是 L3，本模块是它的第二个 L4。
--
-- ---------------------------------------------------------------------------
-- 为什么本迁移只补 update，且不补 delete
-- ---------------------------------------------------------------------------
-- 上一条车道（8.1.1.1，identity V69）在文件头写死了分工：
-- 「刻意只注册 read + create，不注册 update / delete。
--   改仓库属性属 8.1.1.2 详情、行为参数属 8.1.1.3 配置，两条车道落地时各自补自己的码。」
-- 本迁移就是那个「8.1.1.2 落地时补自己的码」的兑现。
--
-- 补 update 的依据是蓝图 8.1.1.2 的职责第一条「基础信息与地址」：
-- 控制塔页面不只是一个只读页，还要能维护这组字段（国家、时区、地址、启用状态）。
-- 仓库列表（8.1.1.1）是「展示 + 筛选 + 新增」，把改属性留在那里会让
-- 「详情页是仓库的控制塔」这句话落空 —— 用户看到地址填错了却改不了。
--
-- **刻意不注册 delete**，三条理由逐条钉死：
--   1. 仓库不是可以物理删除的主数据 —— 它是全公司库存归属的顶层结构，
--      删一个仓意味着它的 balance / bucket / 分录全部失去归属对象（FK 会挡，但那只是
--      「恰好被外键救下」，不是设计）。停用与归档才是正确的出口。
--   2. 与 identity V53 数据字典同一纪律：**没有端点的权限码不注册**。
--      本服务没有 DELETE /warehouse/facilities/{id} 端点，注册 delete 只会让
--      授权界面显示一个「可以删除仓库」的条目，而界面上没有任何入口。
--   3. 放行一条 delete 形状会让「能不能把一个仓连同它的库存痕迹抹掉」看起来是能 ——
--      这正是 inventory 前缀下 transfers / stocktakes / outbound-orders / goods-receipts
--      一律不留 DELETE 段的同一个理由（见 api-proxy.ts 的逐段注释）。
--
-- ---------------------------------------------------------------------------
-- 为什么复用 warehouse.facilities 页面而不是新建 page_resource
-- ---------------------------------------------------------------------------
-- 8.1.1.2 是 8.1.1.1 的**同一个页面**（/warehouse/facilities 及其详情路由），
-- 不是新页面。ADR-0005 的 page_resource 对应 L3 子功能即一个菜单入口，
-- 详情页挂在列表页之下不构成新入口。所以 page_resource 沿用 V69 已注册的那一行，
-- 本迁移**不**再插 page_resource（插了会在菜单里多出一个「仓库档案」重复项）。
--
-- permission.page_code 有指向 page_resource 的外键，因此这里能直接插权限行。

INSERT INTO permission VALUES ('warehouse.facilities.update','warehouse.facilities','update')
ON CONFLICT (code) DO NOTHING;

-- 与 V8 / V53 / V69 同规则：租户所有者始终拥有全部页面权限，
-- 否则新接入的动作对既有租户不可见（存量 OWNER 角色也要补上）。
-- 用 LIKE 重新扫一遍整个 warehouse.facilities.* 集合是幂等的：
-- read / create 已在 V69 授予，这里只是把 update 一并带上。
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'warehouse.facilities.%' ON CONFLICT DO NOTHING;