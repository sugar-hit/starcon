-- 1.4.1.2 列表与表格偏好 —— 「个人视图状态」表。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：记住各列表页的列显示、排序、分页大小、筛选条件，降低重复操作成本。
--   **输入**：用户在列表页的列显隐/排序/分页操作；`user_preference`。
--   **输出**：下次进入该页时恢复上次的视图状态。
--   **关系**：上游 = 各系统列表页；下游 = 各列表页初始状态。
--            **偏好按页面 key 存储**，新增字段不影响旧偏好。
--
-- ============================================================ 第一节：这张表为什么落在这里
--
-- 「列表与表格偏好」是**纯个人偏好**：它不是任何业务事实，而是「这个人上次在这个列表页
-- 把列怎么排、按什么排、每页几条、筛了什么」。
-- 与 V330（最近搜索记录）同一套论证：**identity 服务拥有个人维度数据**
-- （1.4.1 界面偏好 / 1.4.2 账号偏好都在这里），而 catalog / trade / channel / warehouse
-- 都不拥有「个人偏好」这个概念。
--
-- 也刻意**不**只写在前端 localStorage：localStorage 只能当缓存层，权威副本必须在后端 ——
-- 否则换一台设备、换个浏览器，用户的列表视图就丢了。
--
-- ============================================================ 第二节：表的形状为什么是「一页一行 + JSONB」
--
-- 蓝图把「**偏好按页面 key 存储**，新增字段不影响旧偏好」写成了硬约束。
-- 这条约束直接否掉了「每种偏好一列」的大宽表：那样每加一个偏好字段就要 ALTER 表，
-- 而老行的默认值语义会被迫重写 —— 老用户下次进页面时被新字段的默认值**静默覆盖**。
--
-- 所以这里取「**一张表 + page_key + JSONB 载荷**」：
--
--   user_preference(id, tenant_id, membership_id, page_key, preference jsonb, ...)
--   UNIQUE(tenant_id, membership_id, page_key)
--
-- * **一页一行**：同一个 page_key 第二次写入是**覆盖**（upsert），不新增行。
-- * **载荷是 JSONB**：读的时候**缺哪个键就套哪个键的默认值**（前端 hook 负责），
--   于是「新增字段」不需要 ALTER、不需要数据迁移、不影响任何一条老偏好。
-- * **零 ALTER 任何既有表**（本迁移只新建一张表）。
--
-- ============================================================ 第三节：谁算「这个人」
--
-- identity 的 `app_user` 是**跨租户的全局账号**（没有 tenant_id），
-- 租户内的「这个人」是 **`membership.id`**。
-- 所以本表的主键侧用 `membership_id`，并用复合外键
-- `FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id)`
-- 把「这份偏好属于本租户的这个成员」钉在**数据层**（不是只在应用层记得带一句 where）。
--
-- ============================================================ 第四节：隔离（RLS + owner 双防线）
--
-- **RLS 到租户级**（与 identity V160 / V326 / V330 同构）：
--   ENABLE + **FORCE** ROW LEVEL SECURITY，policy 只认 `app.current_tenant`。
--   FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务
--   用 owner 身份一连就绕过了隔离。
--
-- **「只看自己的」在服务层**：identity 侧**没有** `app.actor` 这个 GUC，
-- 所以「本租户里的这个人」由服务层的 `membership_id` 谓词保证 ——
-- membership_id 来自**已认证会话**（session_access），不来自请求参数，更不来自请求头。
--
-- ============================================================ 第五节：刻意不新增权限码（理由写在这里）
--
-- 本表里**每一行都是「我自己的」偏好**，读不到别人的、写不了别人的。
-- 边界是**数据归属**（tenant_id + membership_id + RLS），不是**功能开关**。
--
-- 刻意**不**新登记 `identity.user_preference.*` 权限码，理由是：
--   * 新权限码必须由管理员逐个角色授予，而角色是管理员**随时会新建**的 ——
--     新角色默认没有这个码，于是「普通成员的列表页偏好全都不生效」，
--     而界面上看不出任何异常（静默降级成默认值）。
--   * 偏好数据**不含任何业务事实**，也不跨成员可见；
--     给它加一个功能闸门，只会把「数据边界」错换成「功能开关」，
--     却拿不到任何额外安全性。
-- 凡是**会暴露别人数据**的入口（全局搜索、操作日志、审批记录…）才需要独立权限码，
-- 那些入口另有自己的权限码，本表不沾。
--
-- ============================================================ 第六节：与兄弟模块 1.4.1.1 的关系
--
-- `user_preference` 的 schema 由**本模块（1.4.1.2）定义**。
-- 兄弟模块 **1.4.1.1 工作台布局**（同一 L3）本轮未交付；它将来复用**同一张表**，
-- 用它自己的 page_key（例如 `dashboard.layout`）存自己的载荷 ——
-- 这正是「一页一行 + JSONB」而不是「大宽表」要换来的东西：
-- 1.4.1.1 落地时**不需要** ALTER 本表，也不需要数据迁移。
--
-- ---------------------------------------------------------------------------
-- 一、个人视图状态
-- ---------------------------------------------------------------------------
CREATE TABLE user_preference (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 记录人：租户内的成员（`membership.id`，**不是** `app_user.id`）。
  -- 来自已认证会话，**不接受请求参数传入**。
  membership_id uuid NOT NULL,

  -- 页面 key：稳定的页面标识（约定见下），不是人类可读标题。
  -- 词表刻意收紧到「小写字母 / 数字 / 点 / 下划线 / 连字符」，
  -- 这样它既能当 URL 路径段用，又不会带进路径分隔符之类的字符。
  page_key varchar(160) NOT NULL,

  -- 载荷：列显隐与顺序、排序键与方向、分页大小、筛选条件。
  -- 缺键是**正常**的（老偏好没有新字段）—— 读的时候由前端 hook 对缺失键套默认值。
  preference jsonb NOT NULL DEFAULT '{}'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  -- 一页一行：同一 page_key 重复写入走 upsert 覆盖，不新增行。
  UNIQUE (tenant_id, membership_id, page_key),

  -- 「这份偏好属于本租户的这个成员」钉在数据层。
  FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id) ON DELETE CASCADE,

  -- page_key 的词表：小写开头，只允许 [a-z0-9._-]，长度 1..160。
  CHECK (page_key ~ '^[a-z0-9][a-z0-9._-]{0,159}$'),

  -- 载荷必须是一个 JSON **对象**（不是数组、不是标量）：
  -- 「缺键套默认值」这件事要求载荷的形状是「键 → 值」。
  CHECK (jsonb_typeof(preference) = 'object')
);

-- 「我这一页的偏好」的主查询口径：按成员取、按页面 key 定位。
-- 与上面的 UNIQUE(tenant_id, membership_id, page_key) 同序，
-- 所以这一条索引同时服务「按页读」与「列出我全部页」。
CREATE INDEX user_preference_by_membership
  ON user_preference(tenant_id, membership_id, page_key);

-- ---------------------------------------------------------------------------
-- 二、租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- 与 identity V160 / V326 / V330 同构：ENABLE + FORCE RLS，policy 只认 app.current_tenant。
ALTER TABLE user_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON user_preference
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 三、授权
-- ---------------------------------------------------------------------------
-- 运行角色是 identity_app：它需要能读自己的偏好、能 upsert、能删（重置）。
GRANT SELECT, INSERT, UPDATE, DELETE ON user_preference TO identity_app;
