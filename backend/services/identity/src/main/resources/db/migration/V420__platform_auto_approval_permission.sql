-- ===========================================================================
-- V420 · platform_auto_approval_permission —— 18.3.3.1「自动审批」的 platform.auto_approval 权限码登记
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.3「业务规则引擎」→ 18.3.3「自动审批」→ 18.3.3.1。
--
-- ---------------------------------------------------------------------------
-- 一、为什么需要这个迁移
-- ---------------------------------------------------------------------------
--
-- platform 服务的 AutoApprovalController 用
--     access.require(bearer, "platform.auto_approval." + permission)
-- 强制鉴权，端点实际用到 4 个动作：read / create / update / decide。
--
-- 但 identity 服务的 permission 表里**从来没有注册过 platform.auto_approval 任何一行** ——
-- 而 role_permission.permission_code 有指向 permission(code) 的外键 ——
-- 于是「没有任何角色可能持有 platform.auto_approval.read」，
-- 这些端点在真实环境里恒 403。
--
-- （与 V378 登记 platform.app.*、V380 登记 platform.text.*、V382 登记 platform.finance.*、
--   V411–V418 登记 platform.api.* / platform.webhook.* / platform.delivery.* /
--   platform.call.* / platform.io.* / platform.external.* 的缺口**完全同构**：
--   那几批同样是「服务已交付、权限码没登记」。）
--
-- 本迁移只做一件事：把这 4 个码登记进 identity 的权限码表。
--
-- ---------------------------------------------------------------------------
-- 二、登记哪几个码
-- ---------------------------------------------------------------------------
--
--   platform.auto_approval.read    读规则配置 / 风险信号码表 / 决策台账 / 升级台账 / 能力声明
--   platform.auto_approval.create  登记规则、登记风险信号码
--   platform.auto_approval.update  改规则、停用规则、停用风险信号码
--   platform.auto_approval.decide  **发起一次自动审批判定**（判定 + 决策留痕）
--                                    与**执行一次超时升级**（转交 / 超时视同通过）
--
-- 刻意**只**登记这 4 个动作：没有端点的权限码不注册
-- （与 identity V104 / V53 / V330 / V378 / V380 / V382 同一纪律）。
--
-- 「配置」与「判定」是**两个独立的码**，理由与 18.1.1.1 的「导出 / 推送」、
-- 18.2.2.1 的「执行 / 同步」同源：
--   · 「配置」= 在本系统里登记一条规则 / 一个信号码（**只动本系统的账**）。
--   · 「判定」= **替这张单据做决定**（自动通过 / 自动驳回 / 转人工审批）。
--     审批是内控底线，能改规则的人不一定有权让规则替他放行 —— 这两个码必须分开，
--     否则「改规则」就等于「批量放行」。
--
-- ---------------------------------------------------------------------------
-- 三、为什么 page_code 取 platform.auto_approval
-- ---------------------------------------------------------------------------
--
-- permission.page_code 有外键指向 page_resource(code)，所以必须先插页面行。
--
-- page_resource 对应**菜单入口**，不是动作。
-- 「自动审批」是 18.3.3.1 的**同一个页面**
-- （规则配置 / 风险信号码表 / 决策台账 / 超时升级在同一处管理），不是一个动作一个页面。
--
-- 前缀取 platform.auto_approval 而不是 channel.auto_approval / trade.auto_approval：
-- 本模块的权限码由 **platform 服务**消费（platform.auto_approval.read），
-- 与 catalog.spu / warehouse.facilities / platform.app / platform.text /
-- platform.finance / platform.payment / platform.external 的「服务名.页面」口径一致；
-- 权限码跟着**消费它的服务**走，才能在审计时回答「谁被授权让系统替他放行单据」。
--
-- ===========================================================================

INSERT INTO page_resource(code,name) VALUES ('platform.auto_approval','自动审批')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permission SELECT 'platform.auto_approval.'||action,'platform.auto_approval',action
FROM unnest(ARRAY['read','create','update','decide']::text[]) action
ON CONFLICT (code) DO NOTHING;

-- 🔴 **只授 OWNER**：自动审批是本仓风险最高的写操作之一 ——
--    「自动通过」= 系统替人放行单据，判错的代价是真金白银。
--    因此**刻意不把 decide 授给任何默认角色**，只授 OWNER；
--    需要放开时由租户显式把该码授给自定义角色（POST /identity/roles 的 permissions 任意组合）。
--    与 identity 的审批动作（identity.approval_inbox.approve）同一纪律。
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'platform.auto_approval.%' ON CONFLICT DO NOTHING;
