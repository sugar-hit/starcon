-- ===========================================================================
-- V418 · external_integration —— 18.2.3.1「其他对接」
-- ===========================================================================
--
-- 蓝图 18「集成开放与智能自动化」→ 18.2「外部系统对接」→ 18.2.3「其他对接」→ 18.2.3.1：
--
--   「**职责**：对接其他外部系统：1688/淘宝货源平台、海外仓服务商、物流商、聚合认证服务、
--      自建 BI。统一管理连接配置与同步健康。
--    **输入**：连接配置；同步任务配置。
--    **输出**：连接状态 + 同步记录。
--    **关系**：上游 = 2 渠道机制；下游 = 外部服务。
--    **每个对接都有独立连接与独立健康状态**，一个外部服务故障不应影响其他集成。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个迁移**刻意不做**什么（先说清楚边界，后面每张表都按这条边界设计）
-- ---------------------------------------------------------------------------
--
--   * **不存任何外部系统的密钥明文**。蓝图写的是「连接配置」——
--     存的是**引用**（密钥管理器里的 ID），不是密钥本身。
--     ⇒ 沿用 18.1.1.1 应用凭证（V377）与 18.2.2.1 支付与资金（V416）的纪律：
--       `credential_ref` 存引用、`credential_ref_lookup` 存**引用的** SHA-256 十六进制摘要
--       （只用于按引用定位这一行）、`credential_hint` 存人眼分辨用的末四位。
--       **Secret 侧刻意没有可定位摘要**。
--
--   * **不抄 channel 的表**。上游「2 渠道机制」住在 channel 服务里，
--     AGENTS.md「模块边界纪律」禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒
--     本模块**只经 channel 的授权 HTTP 端点只读**（`/channel/platforms`、`/channel/shops`），
--     连接档案里只存**只读引用**（平台代码 / 店铺 id），不建跨库外键、不抄店铺档案。
--
--   * **不给外部系统发假请求**。本仓**没有** 1688 / 淘宝 / 海外仓 / 物流商 /
--     聚合认证 / 自建 BI 的任何真实适配器，也**没有任何真实凭据与网络通道**
--     ⇒ 适配器逐个如实答「未接入」，端点如实答 **409**。
--     ⚠️ 这与 18.2.2.1 的口径**刻意不同**：18.2.2.1 拒绝写的是「钱付出去了」这种成功痕迹；
--     本模块写的是**同步记录台账**（本系统自己的账），它记的是
--     「谁在什么时候对哪个对接发起了一次同步、外部侧结论是什么」，
--     状态字段 + `provider_notified` + `provider_notice` 三者合起来
--     **不可能**被误读成「外部侧已经登记过」。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单（三张）
-- ---------------------------------------------------------------------------
--
--   platform_external_connection  **对接连接档案**（统一连接配置管理）
--   platform_external_sync_task  **同步任务配置**（输入的第二项）
--   platform_external_sync_record **同步记录**（输出；只追加台账）
--
-- 三张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 本模块最核心的正确性要求：「每个对接都有独立连接与独立健康状态」
-- ---------------------------------------------------------------------------
--
-- 蓝图加粗句：**一个外部服务故障不应影响其他集成**。
--
-- 这条要求在本迁移里被钉成**三个物理事实**，而不是一句注释：
--
--   (a) **健康状态落在连接自己身上**：`platform_external_connection.health_status`
--       是**这一行自己的**列，不是任何跨行聚合的结果。
--       ⇒ 写 A 的健康**在物理上不可能**改到 B 的行上（UPDATE 的 WHERE 带 id）。
--
--   (b) **同步记录带 connection_id**：每一条记录都指明它属于哪个对接。
--       ⇒ 健康重算只读**同一个 connection_id** 的记录（服务层 SQL 里 WHERE 带连接 id），
--       别的连接的记录**进不来**。
--
--   (c) **一个连接一个 task_type**：`UNIQUE(tenant_id, connection_id, task_type)`
--       ⇒ 两个对接的同步任务不会互相覆盖，也不会共用一条记录。
--
-- ⚠️ **刻意没有**「全局集成健康」这一列：一旦有一个「所有对接的整体健康」，
--    运营就会看到「红色 = 全部挂了」，而真相是「一个对接挂了」。
--    整体状态只能在**读的时候逐条列出来**，不能被写进某个共享的格子里。
--
-- ---------------------------------------------------------------------------
-- 3. 金额与数量
-- ---------------------------------------------------------------------------
--
--   * 本模块**不碰金额**（它管的是连接配置与同步健康，不是账）。
--   * 数量（处理条数、超时秒数、重试次数、间隔秒数）一律 `numeric(19,6)`
--     （AGENTS.md 明文口径，禁浮点）。
--   * 时间列一律 `timestamptz`；服务层用 `OffsetDateTime` 绑定并在 values 位置写
--     `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_external_connection —— 对接连接档案（统一连接配置管理）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个外部系统（1688 / 淘宝 / 海外仓服务商 / 物流商 / 聚合认证服务 / 自建 BI）
-- 在本租户下的一份连接配置。
--
-- 🔴 **库里没有任何密钥明文**，与 V377（18.1.1.1 应用凭证）、V416（18.2.2.1）同一纪律：
--   * `credential_ref`       —— 密钥管理器里的**引用 ID**（如 `secret://external/1688/main`）。
--                              它**不是**密钥本身 —— 拿着它去密钥管理器才换得出凭据。
--   * `credential_ref_lookup` —— SHA-256 十六进制摘要，**只用于按引用定位这一行**。
--                              ⚠️ 这里摘要的是**引用串**，引用串本身不是机密
--                              （它是「去哪里取密钥」的指针），摘要它既不泄露密钥、
--                              又能按引用定位。**密钥本体从不进这张表**，
--                              所以**不存在**「Secret 侧可定位摘要」这个风险面。
--   * `credential_hint`      —— 末四位 / 人眼分辨用（非机密）。
--
-- 🔴 **系统零预置行**：不替用户默认一个「1688 账号」。连接配置由租户自己登记。
CREATE TABLE platform_external_connection (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 对接类别（蓝图点名的五类外部系统）。
  category text NOT NULL
    CHECK(category IN (
      'SUPPLY_SOURCE',            -- 1688 / 淘宝货源平台
      'OVERSEAS_WAREHOUSE',       -- 海外仓服务商
      'LOGISTICS_CARRIER',        -- 物流商
      'CERTIFICATION_AGGREGATOR', -- 聚合认证服务
      'SELF_HOSTED_BI'            -- 自建 BI
    )),

  -- 适配器代码（本仓内置的六个「未接入」实现；接上真实适配器时新增一个即可）。
  adapter_code text NOT NULL
    CHECK(adapter_code IN (
      'ALIBABA_1688',
      'TAOBAO',
      'OVERSEAS_WAREHOUSE',
      'LOGISTICS_CARRIER',
      'CERTIFICATION_AGGREGATOR',
      'SELF_HOSTED_BI'
    )),

  -- 配置名（租户内可有多个同一类别的配置，比如「美国站海外仓」「欧洲站海外仓」）。
  name varchar(120) NOT NULL,

  -- 外部系统接口地址（登记时校验是 http/https 形状；真正发起外呼前还要过主机白名单）。
  base_url varchar(300) NOT NULL DEFAULT '',

  -- 🔴 密钥**引用**（不是密钥本体）。空串 = 还没配凭据（该配置不可用于任何真实调用）。
  credential_ref varchar(200) NOT NULL DEFAULT '',
  -- 「引用」的 SHA-256 十六进制摘要：只用于按引用定位这一行。
  credential_ref_lookup varchar(64) NOT NULL DEFAULT '',
  -- 末四位 / 人眼分辨用（非机密）。
  credential_hint varchar(16) NOT NULL DEFAULT '',

  -- 上游「2 渠道机制」的**只读引用**（channel 服务，端口 8083）。
  -- ⚠️ **不建跨库外键**（AGENTS.md：禁止跨服务 FK）—— 这里是引用，不是约束。
  channel_platform_code varchar(40) NOT NULL DEFAULT '',
  channel_shop_id uuid,

  -- 连接状态：
  --   ACTIVE    启用
  --   DISABLED  停用（**不删**：历史同步记录要能复现）
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),

  -- 🔴 **这一行自己的**健康状态（详见第 2 节 (a)）。
  --   HEALTHY       最近一次同步成功
  --   DEGRADED      最近一次失败，但**这个连接**历史上成功过
  --   UNHEALTHY     这个连接的同步**从未**成功过，且最近一次失败
  --   UNKNOWN       还没跑过（**「没跑过」不等于「坏了」**）
  --   NOT_CONNECTED 外部通道未接入（适配器未交付 / 没有凭据）
  health_status text NOT NULL DEFAULT 'UNKNOWN'
    CHECK(health_status IN ('HEALTHY','DEGRADED','UNHEALTHY','UNKNOWN','NOT_CONNECTED')),
  -- 最近一次**健康重算**的时刻。
  last_health_at timestamptz,
  -- 最近一次同步记录（指向本连接自己的某条记录）。
  last_sync_record_id uuid,
  -- 最近一次同步的时刻。
  last_sync_at timestamptz,

  -- 单次外呼超时秒数 / 最大重试次数（数量，NUMERIC(19,6)，禁浮点）。
  timeout_seconds numeric(19,6) NOT NULL DEFAULT 15 CHECK(timeout_seconds > 0),
  max_attempts numeric(19,6) NOT NULL DEFAULT 3 CHECK(max_attempts >= 1),

  remark varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一租户内配置名唯一：两个同名配置会让运营在界面上分不清连的是谁。
  UNIQUE(tenant_id, name),

  CHECK(btrim(name) <> '' AND length(name) <= 120),
  -- 「填了引用就必须同时填定位摘要与提示」——三者是同一条凭据的三个面，缺一个就说不清配的是哪条。
  CHECK(
    (credential_ref = '' AND credential_ref_lookup = '' AND credential_hint = '')
    OR (btrim(credential_ref) <> '' AND length(credential_ref_lookup) = 64 AND btrim(credential_hint) <> '')
  ),
  -- 类别与适配器必须自洽：货源平台只能用 1688 / 淘宝，其余四类一一对应。
  CHECK(
    (category = 'SUPPLY_SOURCE' AND adapter_code IN ('ALIBABA_1688','TAOBAO'))
    OR (category = 'OVERSEAS_WAREHOUSE' AND adapter_code = 'OVERSEAS_WAREHOUSE')
    OR (category = 'LOGISTICS_CARRIER' AND adapter_code = 'LOGISTICS_CARRIER')
    OR (category = 'CERTIFICATION_AGGREGATOR' AND adapter_code = 'CERTIFICATION_AGGREGATOR')
    OR (category = 'SELF_HOSTED_BI' AND adapter_code = 'SELF_HOSTED_BI')
  )
);

CREATE INDEX platform_external_connection_by_category
  ON platform_external_connection(tenant_id, category, created_at DESC);
CREATE INDEX platform_external_connection_by_health
  ON platform_external_connection(tenant_id, health_status, created_at DESC);

-- 🔴 **同一个引用**在同一租户内只能登记一次（定位列唯一索引）。
-- ⚠️ 这里用**部分唯一索引**（`WHERE credential_ref_lookup <> ''`）而不是
--    `UNIQUE(tenant_id, credential_ref_lookup)`：
--    后者会把「没填凭据」的空串 '' 也当成一个值，于是**同一个租户只能有一个没配凭据的连接** ——
--    而本模块的正常使用方式恰恰是「先登记好五个对接、凭据后补」。
CREATE UNIQUE INDEX platform_external_connection_credential_ref
  ON platform_external_connection(tenant_id, credential_ref_lookup)
  WHERE credential_ref_lookup <> '';

-- ---------------------------------------------------------------------------
-- 2. platform_external_sync_task —— 同步任务配置（输入的第二项）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个连接上的一类同步任务。
--
-- 🔴 **一个连接一个 task_type**（UNIQUE(tenant_id, connection_id, task_type)）：
--   同一条连接上「货源商品同步」只有一份配置，不会出现两份配置抢同一条游标。
--   **两个连接之间互不影响** —— 这是「一个外部服务故障不应影响其他集成」在配置层的落点。
CREATE TABLE platform_external_sync_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 关联的连接（**配置不存在** ⇒ 400，不允许对着不存在的对接配任务）。
  connection_id uuid NOT NULL,

  -- 同步类型（按类别各自成立；服务层校验「类型与连接的类别相容」）。
  task_type text NOT NULL
    CHECK(task_type IN (
      'CATALOG_SYNC',           -- 货源平台：商品 / 货源清单
      'INVENTORY_SYNC',         -- 海外仓：库存观测
      'SHIPMENT_TRACKING_SYNC', -- 物流商：运单 / 轨迹
      'CERTIFICATE_SYNC',       -- 聚合认证：认证状态
      'BI_DATASET_SYNC'         -- 自建 BI：数据集
    )),

  -- 方向：
  --   PULL  从外部系统拉数据回来
  --   PUSH  向外部系统推数据
  direction text NOT NULL DEFAULT 'PULL' CHECK(direction IN ('PULL','PUSH')),

  -- 任务名（同一连接内可读）。
  name varchar(120) NOT NULL,

  -- 同步对象（外部系统侧的标识：店铺代码、数据集名、运单前缀…）。
  resource_ref varchar(200) NOT NULL DEFAULT '',

  -- 调度：
  --   MANUAL   只手动触发
  --   INTERVAL 固定间隔（interval_seconds）
  --   CRON     cron 表达式（schedule_expression）
  schedule_kind text NOT NULL DEFAULT 'MANUAL' CHECK(schedule_kind IN ('MANUAL','INTERVAL','CRON')),
  schedule_expression varchar(120) NOT NULL DEFAULT '',
  interval_seconds numeric(19,6),

  -- 启用开关 + 配置状态（DISABLED 是**停用**，不是删：历史同步记录要能复现）。
  enabled boolean NOT NULL DEFAULT true,
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),

  -- 最近一次同步的结论（'' = 还没跑过）。
  last_run_status text NOT NULL DEFAULT ''
    CHECK(last_run_status IN ('','SUCCEEDED','FAILED','TIMEOUT','NOT_CONNECTED')),
  last_run_at timestamptz,
  last_record_id uuid,

  remark varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 一个连接一个 task_type（见上）。
  UNIQUE(tenant_id, connection_id, task_type),

  FOREIGN KEY(tenant_id, connection_id) REFERENCES platform_external_connection(tenant_id, id),

  CHECK(btrim(name) <> '' AND length(name) <= 120),
  -- 选了调度方式就必须给出对应的参数：选了 INTERVAL 却不给间隔 = 永远不跑。
  CHECK(schedule_kind <> 'INTERVAL' OR (interval_seconds IS NOT NULL AND interval_seconds > 0)),
  CHECK(schedule_kind <> 'CRON' OR btrim(schedule_expression) <> '')
);

CREATE INDEX platform_external_sync_task_by_connection
  ON platform_external_sync_task(tenant_id, connection_id, created_at);
CREATE INDEX platform_external_sync_task_by_type
  ON platform_external_sync_task(tenant_id, task_type, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_external_sync_record —— 同步记录（输出；只追加台账）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次同步的结论。
--
-- 🔴 **只追加**：已结束的同步记录不可修改（与 18.2.2.1 的资金流水台账同一纪律）——
--   能改的同步历史等于没有同步历史。由**触发器**钉死，不是靠少授一个权限码。
--
-- 🔴 **每条记录都带 connection_id** —— 健康重算只读**同一个连接**的记录。
--   这是「一个外部服务故障不应影响其他集成」的物理保证（见第 2 节 (b)）。
--
-- 🔴 **状态取值域刻意收窄**（没有 RUNNING）：
--   记录在**同步结束之后**一次写入，状态就是终态。
--   「在跑」是任务维度的状态（`platform_external_sync_task.last_run_status` 之外的运行中态），
--   不是记录维度的 —— 记录一旦存在就是**已经发生的结论**。
CREATE TABLE platform_external_sync_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 指明这条记录属于**哪个对接**（健康重算的过滤条件）。
  connection_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- 同一个任务内的运行序号（从 1 递增）。
  run_no int NOT NULL CHECK(run_no >= 1),

  -- 结论：
  --   SUCCEEDED      同步成功
  --   FAILED         外部系统明确失败（拒绝 / 数据错误）
  --   TIMEOUT        超时（**结果未知**：外部侧可能已经动过）
  --   NOT_CONNECTED 外部通道未接入（**根本没发出去**）
  status text NOT NULL
    CHECK(status IN ('SUCCEEDED','FAILED','TIMEOUT','NOT_CONNECTED')),

  -- 来源：
  --   DIRECT   经由本系统的外部通道发起
  --   EXTERNAL 外部侧**已经**完成、人工登记进本系统台账（**没有**经过本系统的通道）
  source text NOT NULL DEFAULT 'DIRECT' CHECK(source IN ('DIRECT','EXTERNAL')),

  -- 处理条数（数量，NUMERIC(19,6)，禁浮点）。
  records_total numeric(19,6) NOT NULL DEFAULT 0 CHECK(records_total >= 0),
  records_succeeded numeric(19,6) NOT NULL DEFAULT 0 CHECK(records_succeeded >= 0),
  records_failed numeric(19,6) NOT NULL DEFAULT 0 CHECK(records_failed >= 0),

  -- 外部系统侧的标识（批次号 / 游标 / 单号…）。
  external_ref varchar(200) NOT NULL DEFAULT '',

  -- 失败 / 未接入的理由（**人可读**，写清「缺什么、归谁」）。
  failure_reason varchar(500) NOT NULL DEFAULT '',
  -- 失败分类：
  --   REJECTED       外部系统明确拒绝
  --   TIMEOUT        超时
  --   UNAVAILABLE    不可达
  --   NOT_CONFIGURED 未接入（缺凭据 / 适配器未交付）
  --   DATA_ERROR     数据错误
  failure_class text NOT NULL DEFAULT ''
    CHECK(failure_class IN ('','REJECTED','TIMEOUT','UNAVAILABLE','NOT_CONFIGURED','DATA_ERROR')),

  -- 🔴 本次同步**是否已与外部系统通道核对过**（外部侧返回了明确结论）。
  --   这一格与 `status` **刻意分开**：
  --   · DIRECT + SUCCEEDED ⇒ 一定是 true（见下面的 CHECK）。
  --   · DIRECT + NOT_CONNECTED ⇒ false（**根本没发出去**）。
  --   · EXTERNAL ⇒ false（本系统**没有**与外部系统核对过，是人工登记的事实）。
  --   ⇒ 「本地台账成功」永远**不可能**被读成「外部侧已登记过」。
  provider_notified boolean NOT NULL DEFAULT false,
  -- 外部侧没被告知 / 没核对时的说明（为什么没告知）。
  provider_notice varchar(500) NOT NULL DEFAULT '',

  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个任务内的运行序号唯一。
  UNIQUE(tenant_id, task_id, run_no),

  FOREIGN KEY(tenant_id, connection_id) REFERENCES platform_external_connection(tenant_id, id),
  FOREIGN KEY(tenant_id, task_id) REFERENCES platform_external_sync_task(tenant_id, id) ON DELETE CASCADE,

  CHECK(records_succeeded <= records_total),
  CHECK(records_failed <= records_total),
  CHECK(finished_at >= started_at),
  -- 🔴 **经由通道的成功必须真的核对过**：DIRECT + SUCCEEDED ⇒ provider_notified = true。
  --    这一格把「没核对过却显示成功」的形状在数据层直接禁掉。
  CHECK(NOT (source = 'DIRECT' AND status = 'SUCCEEDED') OR provider_notified),
  -- 未接入就必然是 NOT_CONNECTED 且没核对过。
  CHECK(status <> 'NOT_CONNECTED' OR (NOT provider_notified AND failure_class = 'NOT_CONFIGURED'))
);

CREATE INDEX platform_external_sync_record_by_connection
  ON platform_external_sync_record(tenant_id, connection_id, finished_at DESC, run_no DESC);
CREATE INDEX platform_external_sync_record_by_task
  ON platform_external_sync_record(tenant_id, task_id, run_no DESC);
CREATE INDEX platform_external_sync_record_by_status
  ON platform_external_sync_record(tenant_id, status, finished_at DESC);

-- ---------------------------------------------------------------------------
-- 4. 只追加台账的触发器（同步记录不可改写）
-- ---------------------------------------------------------------------------
--
-- 🔴 用**触发器**而不是「少授一个权限码」：少授会被「迁移脚本用 owner 身份跑」绕开，
--    触发器不会（与 V416 的资金台账同一纪律）。
CREATE FUNCTION refuse_platform_external_sync_record_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_external_sync_record 是只追加台账：已结束的同步记录不可修改（%）',
    TG_OP;
END;
$$;

CREATE FUNCTION refuse_platform_external_sync_record_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_external_sync_record 是只追加台账：已结束的同步记录不可删除（%）',
    TG_OP;
END;
$$;

CREATE TRIGGER guard_platform_external_sync_record_update
  BEFORE UPDATE ON platform_external_sync_record
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_external_sync_record_update();

CREATE TRIGGER guard_platform_external_sync_record_delete
  BEFORE DELETE ON platform_external_sync_record
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_external_sync_record_delete();

-- ===========================================================================
-- 5. 租户隔离（RLS）
-- ===========================================================================
--
-- 三张表全部：tenant_id NOT NULL + ENABLE **与** FORCE RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V416 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_external_connection ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_external_connection FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_external_connection
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_external_sync_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_external_sync_task FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_external_sync_task
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_external_sync_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_external_sync_record FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_external_sync_record
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 6. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的对接配置与同步台账、能写
-- （登记配置 / 登记任务 / 执行同步 / 记同步记录 / 重算健康）。
--
-- ⚠️ 只追加台账也给了 UPDATE / DELETE 授权，但**触发器**会拒绝它们 ——
--    「不可改」这件事由数据层的触发器钉死。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_external_connection TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_external_sync_task TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_external_sync_record TO platform_app;
