-- 1.2.1.1 全局搜索框 —— 「最近搜索记录」表 + 全局搜索的权限码登记。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **输入**：用户键入关键词（≥2 字符）；用户租户与数据范围；最近搜索记录。
--   **关系**：上游 = 各系统只读检索索引；下游 = 各系统详情页。
--
-- ============================================================ 第一节：最近搜索记录为什么落在这里
--
-- 「最近搜索记录」是**纯个人偏好**：它不是任何业务事实，而是「这个人最近搜过什么」。
-- 按 AGENTS.md「每张事实表只有一个写入所有者」与蓝图的模块归属，它属于 **1.4 个人设置**同域 ——
-- identity 服务拥有个人维度数据（1.4.1 界面偏好 / 1.4.2 账号偏好都在这里）。
--
-- 刻意**不**把它塞进某个业务服务（catalog / trade / channel / warehouse 都不拥有「个人偏好」），
-- 也刻意**不**把它只写在前端 localStorage：localStorage 只能当缓存层，
-- 权威副本必须在后端 —— 否则换一台设备、换个浏览器，最近搜索就空了，
-- 而「最近搜索」本来就是跨设备才有意义的东西。
--
-- ============================================================ 第二节：表的形状
--
-- **一人一关键词一行**（UNIQUE(tenant_id,user_id,keyword)）：重复搜同一个词不新增行，
-- 只把它的 searched_at 推到最新。列表页要的是「最近搜过的 N 个不同关键词」，
-- 同一个词出现两次会让列表出现重复项。
--
-- **domain 记的是「选中的是哪一类」**：一个关键词可能同时命中商品与订单，
-- 但用户**选中**的只有一个 —— domain / primary_ref 记的是那次选中的落点，
-- 所以「最近搜索」这一行能直接回到用户上次看的那张单据。
--
-- **RLS 到租户级**：identity 侧没有 app.actor 这个 GUC（见各服务 AccessService），
-- 所以「只看自己的」由服务层的 user_id 条件保证 —— user_id 来自**已认证会话**，
-- 不来自请求参数，更不来自请求头。
--
-- ============================================================ 第三节：权限码登记
--
-- 三条权限码，都是**登记既有事实**，不是新造一套授权模型：
--
-- ① identity.global_search.read —— 「谁能用全局搜索」是一个独立决定，
--    不是一个域的读权限的副产品。四个服务（catalog / trade / channel / warehouse）
--    的检索端点都用它做入口闸门。
--
-- ②③ trade.orders.read / trade.purchase.read —— **这两个码 trade 服务自己早就在用**
--    （9.1.1.1 订单列表、6.3.2.1 采购单列表都是 access.require(bearer, "trade.orders.read")），
--    但 identity 的 permission 表里**从来没有注册过 trade.* 任何一行**，
--    而 role_permission.permission_code 有指向 permission(code) 的外键 ——
--    于是「没有任何角色可能持有 trade.orders.read」，trade 的这两个列表端点在生产上恒 403。
--    本迁移把它们补登记，于是：
--      * 全局搜索的「订单 / 采购单」两个域真的能被授权、真的能返回数据；
--      * trade 那两个既有列表端点同时恢复可达（这是修一个既有缺口，不是本模块扩大范围）。
--    刻意**只**登记 read 两个码：没有写端点的权限码不登记（与 identity V104 同纪律）。
--
-- 租户所有者始终拥有全部页面权限（与 V3 / V4 / V8 同规则），
-- 否则新接入的动作对既有租户不可见（存量 OWNER 角色也要补上）。

-- ---------------------------------------------------------------------------
-- 一、最近搜索记录
-- ---------------------------------------------------------------------------
CREATE TABLE global_search_recent (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 记录人：来自已认证会话的 user_id。**不接受请求参数传入**。
  user_id uuid NOT NULL,

  -- 关键词：用户当时键入的原文（已去首尾空白）。长度上限 200 与界面输入框一致。
  keyword varchar(200) NOT NULL,

  -- 选中时命中的域（PRODUCT / ORDER / PURCHASE / SHOP / SUPPLIER / LOGISTICS / CUSTOMER / WAREHOUSE）。
  -- 空串 = 只是搜了、没有选中任何一条。枚举化而不是自由文本：
  -- 这个列要用来决定「点回最近搜索时跳到哪一页」，自由文本会让跳转规则散落在前端各处。
  domain varchar(32) NOT NULL DEFAULT '',

  -- 选中那条的主标识（单号 / 编码）。空串同上。
  primary_ref varchar(200) NOT NULL DEFAULT '',

  -- 最近一次命中这条关键词的时刻。列表按它倒序。
  searched_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一人一个关键词一行：重复搜同一个词只刷新 searched_at。
  UNIQUE (tenant_id, user_id, keyword),

  CHECK (btrim(keyword) <> '' AND length(keyword) <= 200),
  CHECK (length(primary_ref) <= 200),
  CHECK (
    domain = ''
    OR domain IN (
      'PRODUCT','ORDER','PURCHASE','SHOP','SUPPLIER','LOGISTICS','CUSTOMER','WAREHOUSE'
    )
  )
);

-- 「这个人最近搜过什么」的主查询口径：按用户取、按最近排。
CREATE INDEX global_search_recent_by_user
  ON global_search_recent(tenant_id, user_id, searched_at DESC);

-- ---------------------------------------------------------------------------
-- 二、租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- 与 identity V160 / V326 同构：ENABLE + FORCE RLS，policy 只认 app.current_tenant。
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，
-- 否则迁移脚本或后台任务用 owner 身份一连就绕过了隔离。
ALTER TABLE global_search_recent ENABLE ROW LEVEL SECURITY;
ALTER TABLE global_search_recent FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON global_search_recent
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 三、授权
-- ---------------------------------------------------------------------------
-- 运行角色是 identity_app：它需要能读自己的最近搜索、能写（upsert）、能删（清空）。
GRANT SELECT, INSERT, UPDATE, DELETE ON global_search_recent TO identity_app;

-- ---------------------------------------------------------------------------
-- 四、权限码登记
-- ---------------------------------------------------------------------------
-- ① 全局搜索入口权限（页面 = 全局搜索，对应 1.2.1 统一搜索）。
INSERT INTO page_resource(code, name) VALUES ('identity.global_search', '全局搜索')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission VALUES ('identity.global_search.read', 'identity.global_search', 'read')
ON CONFLICT (code) DO NOTHING;

-- ②③ trade 的两个读权限码：trade 服务自己的端点早就在 require 它们，
--      但 permission 表里从来没有这两行（见第三节的说明）。
INSERT INTO page_resource(code, name) VALUES
  ('trade.orders', '订单'),
  ('trade.purchase', '采购单')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission VALUES
  ('trade.orders.read', 'trade.orders', 'read'),
  ('trade.purchase.read', 'trade.purchase', 'read')
ON CONFLICT (code) DO NOTHING;

-- 租户所有者始终拥有全部页面权限（与 V3 / V4 / V8 / V104 同规则）：
-- 存量 OWNER 角色也要补上，否则新接入的动作对既有租户不可见。
INSERT INTO role_permission SELECT r.tenant_id, r.id, p.code FROM role r CROSS JOIN permission p
 WHERE r.code = 'OWNER'
   AND p.code IN ('identity.global_search.read', 'trade.orders.read', 'trade.purchase.read')
ON CONFLICT DO NOTHING;
