-- ===========================================================================
-- V439 · platform_notify_delivery_permission —— 18.5.2.1「投递记录」的权限码登记
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.5「通知模板」→ 18.5.2「投递记录」。
--
-- ---------------------------------------------------------------------------
-- 一、为什么需要这个迁移（补的是既有缺口，不是本车道扩大范围）
-- ---------------------------------------------------------------------------
--
-- platform 服务的 NotifyDeliveryController 用
--     access.require(bearer, "platform.notify_delivery." + permission)
-- 强制鉴权，端点实际用到 4 个动作：read / create / retry / unsubscribe。
--
-- 但 identity 服务的 permission 表里**从来没有注册过 platform.notify_delivery 任何一行** ——
-- 而 role_permission.permission_code 有指向 permission(code) 的外键 ——
-- 于是「没有任何角色可能持有 platform.notify_delivery.read」，
-- 这些端点在真实环境里恒 403。
--
-- （与 V378 登记 platform.app.*、V380 登记 platform.text.*、V427 登记 platform.template.*、
--   V419–V428 登记 platform.automation_rule.* 的缺口**完全同构**。）
--
-- 本迁移只做一件事：把这 4 个码登记进 identity 的权限码表。
--
-- ---------------------------------------------------------------------------
-- 二、登记哪几个码
-- ---------------------------------------------------------------------------
--
--   platform.notify_delivery.read         读投递记录 / 逐次尝试 / 拦截留痕 / 退订清单 / 统计 / 能力声明
--   platform.notify_delivery.create       登记一次投递（**唯一**的投递写入口）
--   platform.notify_delivery.retry        发起一次重试
--   platform.notify_delivery.unsubscribe  登记一条退订 / 撤销一条退订
--
-- 刻意**只**登记这 4 个动作：没有端点的权限码不注册
-- （与 identity V104 / V53 / V330 / V378 / V380 同纪律）。
--
-- 「登记退订」为什么独立成一个码：它是一个**会改变事实**的动作 ——
--   蓝图的加粗句是「**退订必须被尊重并生效**」，退订登记一旦错了，
--   后果是「本该被拦下的收件人被反复触达」（投诉与合规风险）。
--   让有权看投递记录的人顺手就能替客户退订不是好事。
--
-- 「重试」为什么也独立：重试会**真的再发一次通知** ——
--   它是「对外发消息」这个动作的入口，必须能被单独收回。
--
-- ---------------------------------------------------------------------------
-- 三、为什么 page_code 取 platform.notify_delivery
-- ---------------------------------------------------------------------------
--
-- permission.page_code 有外键指向 page_resource(code)，所以必须先插页面行。
--
-- page_resource 对应**菜单入口**，不是动作。
-- 「投递记录」是 18.5.2.1 的**同一个页面**（投递记录 / 逐次尝试 / 退订清单 / 拦截留痕 /
-- 触达统计在同一处管理），不是一个动作一个页面。
--
-- 前缀取 platform.notify_delivery：权限码由 **platform 服务**消费
-- （platform.notify_delivery.read），与 catalog.spu / platform.app / platform.text /
-- platform.template 的「服务名.页面」口径一致。
--
-- ⚠️ **不碰 platform.template.***：那是 18.5.1.1 的权限码，由 V427 登记。
-- ⚠️ **不碰 platform.delivery.***：那是 18.1.2.2「webhook 投递记录」的权限码（V413 口径）。
--   本模块是**通知**投递 ⇒ 前缀必须带 notify，否则审计时会把它与 webhook 投递混成一件事。
--
-- ---------------------------------------------------------------------------
-- 四、为什么必须给存量 OWNER 补权（这是本迁移最容易漏的一步）
-- ---------------------------------------------------------------------------
--
-- 新建子租户由 create_child（V47）把 permission 表里的**全部**权限码授给 OWNER，
-- 所以新租户自动就有这 4 个码 —— 但**存量租户**的 OWNER 角色是在这 4 个码登记之前建的，
-- 不会自动获得它们 ⇒ 迁移上线后这些租户会突然「什么都点不动」。
--
-- 用 LIKE 'platform.notify_delivery.%' 扫**整个集合**，而不是只补 read 一条：
--   · 覆盖本迁移登记的全部 4 个动作；
--   · 幂等 —— 重复执行不产生重复行（ON CONFLICT DO NOTHING）。
--
-- 与 V3 / V8 / V104 / V330 / V378 / V380 / V427 同口径：租户所有者始终拥有全部页面权限。
--
-- ---------------------------------------------------------------------------
-- 五、幂等
-- ---------------------------------------------------------------------------
--
-- 三段式全部带 ON CONFLICT DO NOTHING：同一段 SQL 跑两次不报错、不产生重复行。

-- ---------------------------------------------------------------------------
-- 1. 页面资源（菜单入口）
-- ---------------------------------------------------------------------------
INSERT INTO page_resource(code,name) VALUES ('platform.notify_delivery','投递记录')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 权限码（4 个动作）
-- ---------------------------------------------------------------------------
INSERT INTO permission SELECT 'platform.notify_delivery.'||action,'platform.notify_delivery',action
FROM unnest(ARRAY['read','create','retry','unsubscribe']::text[]) action
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 存量 OWNER 补权（覆盖 platform.notify_delivery.% 整个集合）
-- ---------------------------------------------------------------------------
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'platform.notify_delivery.%' ON CONFLICT DO NOTHING;
