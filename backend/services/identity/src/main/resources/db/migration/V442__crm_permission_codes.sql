-- ===========================================================================
-- V442 · crm_permission_codes —— 补齐 crm 服务全部端点所需的权限码
--
--   模块：15 卷「客户与客服」（crm 服务）
--   归属：**identity 服务**（permission / page_resource 两张表的写入所有者）
--
-- ## 为什么要补
--
-- `role_permission.permission_code` 上有指向 `permission(code)` 的**外键**：
--
--     CREATE TABLE role_permission (
--       ...
--       permission_code text NOT NULL REFERENCES permission(code), ...
--     )
--
-- ⇒ **一个权限码只要没登记进 `permission` 表，就没有任何角色能持有它**
--   （外键直接拒绝那次授权写入），而 `access.require(bearer, code)` 又是按
--   「已认证成员持有的权限码」判定的 ⇒ 端点恒定 403。
--
-- 2026-11-02 登记的 V432 只覆盖了 `crm.customers` 一族；此后 crm 服务又交付了
-- 20 个模块，它们各自 `access.require(bearer, "crm.<族>." + permission)`，
-- 而这些族**从来没有进过 `permission` 表** ⇒ 21 个族里只有 1 个能用，
-- 其余 20 个族的端点在真实部署里全部 403。
--
-- ⚠️ 这类缺陷**任何一层测试都不会红**：crm 的集成测试直接往访问上下文里塞
--   权限码（桩住 identity），走的是「这个角色**能**持有这些权限」的假设；
--   而真正的问题是「identity 的 permission 表里**根本没有这些码**」。
--   ⇒ 防回归靠的是 `CrmPermissionCodesGuardTest`（静态比对两边的登记与使用）。
--
-- ## 本次登记的清单（21 族 × read/update = 42 个码）
--
--   crm.auto_reply      15.5.1.1 自动回复
--   crm.care_reminder   15.5.2.1 关怀与催办
--   crm.cart            15.6.2.2 购物车与弃单挽回
--   crm.classification  15.1.1.3 消息分类
--   crm.contact         15.3.2.2 接触记录
--   crm.customers       15.3.1.1 客户列表（V432 已登记；此处幂等重放）
--   crm.email           15.1.2.1 邮件收发
--   crm.emailrule       15.1.2.2 邮件规则
--   crm.knowledge       15.4.2.1 客服知识库
--   crm.lifecycle       15.6.2.1 生命周期与流失预警
--   crm.membership      15.6.1.1 会员与等级
--   crm.message         15.1.1.1 消息收件箱
--   crm.message.send    15.1.1.2 消息发送
--   crm.negative_review 15.2.1.3 差评处理
--   crm.points          15.6.1.2 积分与权益
--   crm.product_review  15.2.1.1 评价列表
--   crm.review          15.2.2.1 索评申请
--   crm.review_reply    15.2.1.2 评价回复
--   crm.rule            15.2.2.2 索评规则
--   crm.service_ticket  15.2.3.x 服务工单
--   crm.tag             15.3.2.1 客户标签
--
-- ## 命名口径
--
-- `permission.code = page_code || '.' || action`，`page_code` 取**完整的族名**
-- （与 V439 `platform.notify_delivery` 同一口径）：`crm.message.send.read` 的
-- page_code 是 `crm.message.send`，不是 `crm.message` —— 后者会把「消息发送」
-- 混进「消息收件箱」的页面资源里。
--
-- ⚠️ `crm.customers.pii.read` 是 V432 的既有形状（page_code=`crm.customers`，
--   action=`pii.read`，同一族里多一级），本迁移**原样保留**，不改它的口径 ——
--   改口径会让已登记该码的角色瞬间失去「看明文联系方式」的能力。
--
-- ## 存量租户的 OWNER 补权
--
-- 与 V432 / V439 同口径：给**存量**租户的 OWNER 角色补上本次新增的码。
-- 新租户的 OWNER 角色由 identity 的 LocalBootstrap 在建租时授予当时的全部
-- permission（`insert into role_permission select ?,?,code from permission`），
-- 新租户天然齐全 ⇒ 不需要在这里补。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 页面资源（菜单入口）
-- ---------------------------------------------------------------------------
INSERT INTO page_resource(code, name) VALUES
  ('crm.auto_reply',      '自动回复'),
  ('crm.care_reminder',   '关怀与催办'),
  ('crm.cart',            '购物车与弃单挽回'),
  ('crm.classification',  '消息分类'),
  ('crm.contact',         '接触记录'),
  ('crm.customers',       '客户列表'),
  ('crm.email',           '邮件收发'),
  ('crm.emailrule',       '邮件规则'),
  ('crm.knowledge',       '客服知识库'),
  ('crm.lifecycle',       '生命周期与流失预警'),
  ('crm.membership',      '会员与等级'),
  ('crm.message',         '消息收件箱'),
  ('crm.message.send',    '消息发送'),
  ('crm.negative_review', '差评处理'),
  ('crm.points',          '积分与权益'),
  ('crm.product_review',  '评价列表'),
  ('crm.review',          '索评申请'),
  ('crm.review_reply',    '评价回复'),
  ('crm.rule',            '索评规则'),
  ('crm.service_ticket',  '服务工单'),
  ('crm.tag',             '客户标签')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 权限码（每族 read + update）
-- ---------------------------------------------------------------------------
INSERT INTO permission(code, page_code, action) VALUES
  ('crm.auto_reply.read',        'crm.auto_reply',      'read'),
  ('crm.auto_reply.update',      'crm.auto_reply',      'update'),
  ('crm.care_reminder.read',     'crm.care_reminder',   'read'),
  ('crm.care_reminder.update',   'crm.care_reminder',   'update'),
  ('crm.cart.read',              'crm.cart',            'read'),
  ('crm.cart.update',            'crm.cart',            'update'),
  ('crm.classification.read',    'crm.classification',  'read'),
  ('crm.classification.update',  'crm.classification',  'update'),
  ('crm.contact.read',           'crm.contact',         'read'),
  ('crm.contact.update',         'crm.contact',         'update'),
  ('crm.customers.read',         'crm.customers',       'read'),
  ('crm.customers.update',       'crm.customers',       'update'),
  ('crm.customers.pii.read',     'crm.customers',       'pii.read'),
  ('crm.email.read',             'crm.email',           'read'),
  ('crm.email.update',           'crm.email',           'update'),
  ('crm.emailrule.read',         'crm.emailrule',       'read'),
  ('crm.emailrule.update',       'crm.emailrule',       'update'),
  ('crm.knowledge.read',         'crm.knowledge',       'read'),
  ('crm.knowledge.update',       'crm.knowledge',       'update'),
  ('crm.lifecycle.read',         'crm.lifecycle',       'read'),
  ('crm.lifecycle.update',       'crm.lifecycle',       'update'),
  ('crm.membership.read',        'crm.membership',      'read'),
  ('crm.membership.update',      'crm.membership',      'update'),
  ('crm.message.read',           'crm.message',         'read'),
  ('crm.message.update',         'crm.message',         'update'),
  ('crm.message.send.read',      'crm.message.send',    'read'),
  ('crm.message.send.update',    'crm.message.send',    'update'),
  ('crm.negative_review.read',   'crm.negative_review', 'read'),
  ('crm.negative_review.update', 'crm.negative_review', 'update'),
  ('crm.points.read',            'crm.points',          'read'),
  ('crm.points.update',          'crm.points',          'update'),
  ('crm.product_review.read',    'crm.product_review',  'read'),
  ('crm.product_review.update',  'crm.product_review',  'update'),
  ('crm.review.read',            'crm.review',          'read'),
  ('crm.review.update',          'crm.review',          'update'),
  ('crm.review_reply.read',      'crm.review_reply',    'read'),
  ('crm.review_reply.update',    'crm.review_reply',    'update'),
  ('crm.rule.read',              'crm.rule',            'read'),
  ('crm.rule.update',            'crm.rule',            'update'),
  ('crm.service_ticket.read',    'crm.service_ticket',  'read'),
  ('crm.service_ticket.update',  'crm.service_ticket',  'update'),
  ('crm.tag.read',               'crm.tag',             'read'),
  ('crm.tag.update',             'crm.tag',             'update')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 存量租户的 OWNER 补权（覆盖 crm.% 整个集合，幂等）
-- ---------------------------------------------------------------------------
INSERT INTO role_permission
SELECT r.tenant_id, r.id, p.code
  FROM role r CROSS JOIN permission p
 WHERE r.code = 'OWNER' AND p.code LIKE 'crm.%'
ON CONFLICT DO NOTHING;
