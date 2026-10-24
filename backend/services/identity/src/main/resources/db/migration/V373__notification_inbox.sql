-- 1.3.1.1 通知列表 —— 「通知收件箱」的**个人维度状态**。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：系统级与业务级通知的统一收件箱，区分严重级别，支持已读/未读、批量处理、按类型订阅。
--   **输入**：业务事件产生通知（`notification` 记录）；用户已读状态。
--   **输出**：通知流（类型、严重度、时间、关联单据跳转链接）；未读计数。
--   **关系**：上游 = 各系统事件（06 缺货、09 超时、10 异常、12 对账差异、15 站内信）；下游 = 对应处理页。
--            **通知是投影，允许丢弃后重算**，不作为业务事实来源。
--
-- ============================================================ 第一节：这两张表存的是什么、不存什么
--
-- **不存通知内容**。通知内容是各域既有事件的**只读投影**（缺货预警 / 超时明细 / 同步失败 /
-- 对账差异 / 站内信），它们各自住在自己的库里（warehouse / trade / channel / finance / crm）。
-- 按 AGENTS.md 的模块边界纪律，跨库 SQL / FK / 视图一律禁止 ⇒ 本迁移**不复制**任何业务数据，
-- 也不建「notification 内容表」。前端把各域的**授权只读端点**聚合成一条通知流。
--
-- **只存本模块自己落的东西**：两件都是**个人维度**的状态 ——
--   1. `notification_read_state`       —— 哪些通知**我**读过（已读 / 未读）；
--   2. `notification_type_subscription` —— 我**订阅**哪些通知类型（按类型订阅）。
--
-- 蓝图加粗句「**通知是投影，允许丢弃后重算**，不作为业务事实来源」在这里落地成一句设计约束：
-- 这两张表里的行**随时可以被清空**，通知流会从各域重新算出来，只有「我读过没有」这个
-- 个人事实会丢。所以它们**不参与任何业务对账、不参与任何金额或数量口径**。
--
-- ============================================================ 第二节：为什么落在 identity
--
-- 两张表都是「**这个租户里的这个人**的状态」，不是业务事实：
--   * catalog / trade / channel / warehouse / crm / finance 都不拥有「个人已读状态」这个概念；
--   * identity 是身份与个人配置的服务（1.4 个人设置、1.4.1.2 的 `user_preference` 都在这里），
--     本模块的已读/订阅与它们同域。
--
-- 刻意**不复用** `user_preference`（V336 的 JSONB 载荷）存已读状态，理由有三条：
--   1. **形状不匹配**：已读状态是「一条通知一个键」的集合，条数随时间无上界；
--      `user_preference` 的载荷上限是 16 KB / 单个数组 64 项（`UserPreferencePayloadRules`），
--      读上几百条通知就会撞顶 ⇒ 到那时已读状态会**静默写不进去**（界面表现为「读了就还是未读」）。
--   2. **语义不匹配**：`user_preference` 的写入是**整份覆盖**（upsert 同一个 page_key），
--      而「批量已读」是**增量**（只把给定的那几条标成已读）。整份覆盖要先读后写，
--      两个标签页同时操作就会互相把对方的已读状态冲掉。
--   3. **键受控**：已读状态必须按「受控类型词表 + 上游主键」入库，JSONB 载荷做不到在
--      **数据层**钉住类型词表（CHECK 约束）。
--
-- 顺带说明：`user_preference` 留给兄弟模块 **1.3.1.2 通知偏好**（接收渠道 / 免打扰时段，
-- 蓝图标明它「共享数据：user_preference」）—— 那是**一份偏好**（整份覆盖正合适），
-- 与本模块的**逐条已读状态**是两件事。本迁移**不碰** `user_preference`（不 ALTER、不建同名列）。
--
-- ============================================================ 第三节：为什么按成员（membership）而不是按租户
--
-- 「已读 / 未读」是**个人**的：同事读没读过这条缺货预警，与我无关。
-- 按租户存一份共享的已读状态，等于把个人状态写成共享事实 —— 一个人标了已读，
-- 全公司的人都会看不到那条通知。所以两张表都以 **`membership_id`** 为粒度，
-- 并用复合外键把它钉在数据层：
--   FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id)
-- identity 的 `app_user` 是**跨租户的全局账号**，租户内的「这个人」是 `membership.id`。
--
-- ============================================================ 第四节：隔离（RLS + owner 双防线）
--
-- **RLS 到租户级**（与 identity V160 / V326 / V330 / V336 同构）：
--   ENABLE + **FORCE** ROW LEVEL SECURITY，policy 只认 `app.current_tenant`。
--   FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务
--   用 owner 身份一连就绕过了隔离。
--
-- **「只看自己的」在服务层**：identity 侧**没有** `app.actor` 这个 GUC，
-- 所以「本租户里的这个人」由服务层的 `membership_id` 谓词保证 ——
-- membership_id 来自**已认证会话**（session_access），不来自请求参数，更不来自请求头。
--
-- ============================================================ 第五节：类型词表钉在数据库层
--
-- `notification_type` 是**受控词表**（不是自由文本）：这五个码与蓝图的五个上游一一对应 ——
--   STOCKOUT            06 缺货      ← warehouse 缺货预警
--   TIMELINESS_OVERDUE  09 超时      ← trade 履约超时
--   SYNC_FAILURE        10 异常      ← channel 同步失败
--   RECONCILIATION_DIFF 12 对账差异  ← finance 供应商对账差异
--   MESSAGE             15 站内信    ← crm 站内信
--
-- CHECK 约束让**绕过服务层直连 SQL** 也一样写不进非法类型；服务端另外再拒一次（400）。
--
-- ============================================================ 第六节：刻意不新增权限码
--
-- 这两张表里**每一行都是「我自己的」状态**，读不到别人的、写不了别人的。
-- 边界是**数据归属**（tenant_id + membership_id + RLS），不是功能开关。
-- 另立一个 `identity.notification.*` 权限码只会带来一个坏处：管理员随时会新建角色，
-- 而新角色默认没有这个码，于是普通成员的已读状态静默失效（而且界面上看不出任何异常）。
-- 凡是**会暴露别人数据**的入口才需要独立权限码，那些入口各有各的权限码，本模块不沾。
--
-- ---------------------------------------------------------------------------
-- 一、已读状态（个人维度）
-- ---------------------------------------------------------------------------
CREATE TABLE notification_read_state (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 记录人：租户内的成员（`membership.id`，**不是** `app_user.id`）。
  -- 来自已认证会话，**不接受请求参数传入**。
  membership_id uuid NOT NULL,

  -- 通知类型：**受控词表**（CHECK 约束，见第五节）。
  notification_type varchar(40) NOT NULL,

  -- 上游主键（可带一段后缀，如 `09 超时` 的 `<factId>:<overdueKind>`）。
  -- 词表刻意收紧到「字母 / 数字 / 点 / 下划线 / 连字符 / 冒号」，不接收路径分隔符。
  notification_ref varchar(120) NOT NULL,

  -- 读到的时刻（timestamptz）。**不用** Timestamp.valueOf(LocalDateTime)，
  -- 那会按 JVM 默认时区静默偏 −28800s。
  read_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  -- 一条通知一个人一行：重复标记已读是**覆盖**（刷新读到的时刻），不新增行。
  UNIQUE (tenant_id, membership_id, notification_type, notification_ref),

  -- 「这份状态属于本租户的这个成员」钉在数据层。
  FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id) ON DELETE CASCADE,

  -- 受控类型词表。
  CHECK (notification_type IN (
    'STOCKOUT',
    'TIMELINESS_OVERDUE',
    'SYNC_FAILURE',
    'RECONCILIATION_DIFF',
    'MESSAGE'
  )),

  -- 上游主键的字符集。
  CHECK (notification_ref ~ '^[A-Za-z0-9._:-]{1,120}$')
);

-- 「我读过的通知」的主查询口径：按成员取，按类型 + 主键定位。
-- 与上面的 UNIQUE(tenant_id, membership_id, notification_type, notification_ref) 同序，
-- 所以这一条索引同时服务「按类型批量查」与「列出我读过的全部」。
CREATE INDEX notification_read_state_by_membership
  ON notification_read_state(tenant_id, membership_id);

-- ---------------------------------------------------------------------------
-- 二、按类型订阅（个人维度）
-- ---------------------------------------------------------------------------
CREATE TABLE notification_type_subscription (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  membership_id uuid NOT NULL,

  -- 订阅的通知类型：**与上面同一份受控词表**。
  notification_type varchar(40) NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  -- 一个类型订阅一次：重复订阅是覆盖（幂等），不新增行。
  UNIQUE (tenant_id, membership_id, notification_type),

  FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id) ON DELETE CASCADE,

  CHECK (notification_type IN (
    'STOCKOUT',
    'TIMELINESS_OVERDUE',
    'SYNC_FAILURE',
    'RECONCILIATION_DIFF',
    'MESSAGE'
  ))
);

-- 「我的订阅清单」的主查询口径。
CREATE INDEX notification_type_subscription_by_membership
  ON notification_type_subscription(tenant_id, membership_id);

-- ---------------------------------------------------------------------------
-- 三、租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- 与 identity V160 / V326 / V330 / V336 同构：ENABLE + FORCE RLS，policy 只认 app.current_tenant。
ALTER TABLE notification_read_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_read_state FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON notification_read_state
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE notification_type_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_type_subscription FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON notification_type_subscription
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 四、授权
-- ---------------------------------------------------------------------------
-- 运行角色是 identity_app：它需要能读自己的状态、能标已读 / 取消已读、能改自己的订阅。
GRANT SELECT, INSERT, UPDATE, DELETE ON notification_read_state TO identity_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON notification_type_subscription TO identity_app;
