-- ===========================================================================
-- 16.5.2.2 关联风险 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 224-229 行）：
--   ### 16.5.2.2 关联风险
--   - 职责：识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
--           输出关联图谱与风险提示，提示避免触发平台关联处罚。
--   - 输入：店铺与支付/设备/地址/法人关系数据；平台政策要求。
--   - 输出：关联图谱 + 风险提示 + 整改建议。
--   - 关系：上游 = 2 店铺、12.7.1 资金账户；下游 = 1 工作台预警。
--           **关联封号是跨境多店经营的头号风险**：官方 API 授权是规避关联的关键，
--           本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 蓝图点名的能力面 ⇒ 本迁移的九张表（逐一对应，不合并、不省略）
-- ---------------------------------------------------------------------------
--   能力面                              表
--   ----------------------------------  ---------------------------------------------
--   输入：平台政策要求                  linkage_policy（一租户一条策略 + 阈值）
--                                       linkage_policy_dimension（逐维度口径与依据）
--   输入：店铺关系数据（登记面）        linkage_identifier（关联标识 / 共享因子）
--                                       linkage_shop_identifier（店铺 ↔ 标识 的边）
--   输出：关联图谱（物化当前态）        linkage_cluster（关联组）
--                                       linkage_cluster_shop（关联组成员）
--   输出：风险提示                      linkage_alert（风险提示 + 处置字段）
--                                       linkage_alert_action（处置留痕，append-only）
--   输出：整改建议                      linkage_remediation（整改建议 + 处置状态机）
--
-- ---------------------------------------------------------------------------
-- 二、🔴 本仓实查结论（不是推测，逐条给证据）
-- ---------------------------------------------------------------------------
-- 1) **店铺（2 店铺）已交付，且就在本库**：channel V1 的 `shop` 表
--    （id / code / name / platform_id / legal_entity_id / status …）。
--    ⇒ 关联图谱的**节点来源是本服务的真实表**，不需要跨服务读，也不需要影子表。
--
-- 2) **法人维度的真实数据源是 `shop.legal_entity_id`**（V1 第 118 行）：
--    同一法人下的多家店铺是**真实存在的关联事实**，本模块**直接读它**，
--    **不另建一张「法人关联表」**（那会变成第二份法人真相）。
--
-- 3) 🔴 **支付方式 / 收款账户维度本仓读不到**：12.7.1 资金账户（12.7.1.1 账户管理）
--    在台账里是**未开发**，且卷 12 的主责服务 `finance` **不在集成头**。
--    ⇒ 这两个维度**不建表、不建枚举值、不接受登记**（见第五节 CHECK），
--    能力声明里如实报 `available=false` + `ownerModule=12.7.1.1` + 影响面。
--    **绝不**在本模块里造一份「收款账户」影子数据来假装能做。
--
-- 4) 🔴 **设备 / 网络 IP / 地址维度本仓没有上游数据表**（channel 全库无对应表）。
--    ⇒ 本模块**自持一个「关联事实登记面」**：`linkage_identifier` +
--    `linkage_shop_identifier`。租户登记**已经观测到**的共享事实（设备指纹 / 网络出口 /
--    收货地址），本模块据此**实算**图谱与风险。
--    🔴 这**不是 mock 数据源**：事实落 PostgreSQL、查询走 jOOQ 显式 SQL、
--    登记必须通过校验（店铺必须真实存在且同租户），而**不是硬编码数组当数据源**。
--    能力声明里对这三个维度写明「**不自动发现**，事实靠登记」。
--
-- 5) 🔴 **只做官方 API 授权路径**：本模块**不提供**任何模拟登录 / 指纹浏览器 /
--    绕关联的能力，**表里没有任何设备指纹生成、Cookie 托管、浏览器指纹采集的列**。
--    `linkage_identifier.dimension` 的值域里也**没有**「浏览器指纹」这一类。
--    整改建议里的口径固定为「**用官方 API 授权把店铺分开**」。
--
-- 6) 🔴 **下游 1 工作台预警未交付**（非阻塞）：本模块只产出风险提示与整改建议，
--    **不代**下游推送预警，能力声明里如实标注下游状态。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 表命名空间隔离（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.5.2.2）⇒ `linkage_*`。
--   与既有命名空间**彼此不同**：channel 库的 `access_security_*`（V319）/ `pii_access_*`（V320）/
--   `compliance_*` / `epr_*` / `ip_risk_*` / `ip_detection_*` / `buyer_risk_*` /
--   `buyer_blacklist_*` / `brand_*` / `listing_compliance_*` / `product_certification_*` /
--   `tax_registration_*` / `credential_ref` / `channel_connection` / `shop`。
--   ⇒ 本迁移**不建**任何同名表，也**零 ALTER** 任何既有表。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 零外键（与 channel V1 / V300 / V316 / V319 / V320 同一纪律）
-- ---------------------------------------------------------------------------
-- channel 的既有集成测试用 `truncate <表清单>` 清库，外键会让 TRUNCATE 报 23503。
-- 所以本迁移**不建任何外键**，跨表引用（维度 → 策略、边 → 标识、边 → 店铺、
-- 组 → 关联组、提示 → 关联组、处置 → 提示、建议 → 提示）改由 BEFORE INSERT 触发器
-- 校验存在性与同租户。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V300 / V316 / V319 / V320 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **只带 isolation 策略，不带 data_scope（RESTRICTIVE）策略**：
--   关联风险是**租户级资产**（一个租户的全部店铺构成一张图），
--   不是某家店 / 某个部门的资产。（与 V319 / V316 同一处理。）
--
-- 🔴 **维度值域刻意不含 PAYMENT_METHOD / PAYOUT_ACCOUNT**：
--   这两个维度的真相归 12.7.1 资金账户（未交付），本模块**不许代它登记** ——
--   否则就造出了第二份「收款账户」真相，而两份真相必然打架。
--   同理不含 LEGAL_ENTITY：法人绑定真相在 `shop.legal_entity_id`，
--   本模块**直接读它**，不另建登记面。
-- ===========================================================================

-- ===========================================================================
-- 一、linkage_policy · 一租户一条关联风险策略（阈值 + 判定范围）
-- ===========================================================================
-- 🔴 **阈值不给默认值**：`min_shared_shop_count` **没有 DEFAULT**，
--   必须由租户显式选（几个店铺共享同一标识才算构成关联）。
--   「系统给个默认阈值」等于「系统替租户决定了什么算关联」—— 那不是配置，是默认值。
CREATE TABLE linkage_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  policy_name varchar(120) NOT NULL CHECK(btrim(policy_name)<>''),

  -- 策略状态（DRAFT = 还没生效；ACTIVE = 参与判定）
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE')),

  -- 🔴 关联组的最少店铺数：**没有默认值**，必须由租户显式选（>= 2）
  min_shared_shop_count int NOT NULL
    CHECK(min_shared_shop_count >= 2 AND min_shared_shop_count <= 50),

  -- 判定范围：
  --   TENANT ：全部店铺之间判关联（跨平台同设备同样是关联风险）
  --   PLATFORM：只在同一平台的店铺之间判关联
  evaluation_scope varchar(16) NOT NULL DEFAULT 'TENANT'
    CHECK(evaluation_scope IN ('TENANT','PLATFORM')),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 一租户一条策略
  UNIQUE(tenant_id),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE linkage_policy IS
  '16.5.2.2 · 一租户一条关联风险策略：阈值（租户显式选，无默认值）+ 判定范围。';

-- ===========================================================================
-- 二、linkage_policy_dimension · 逐维度的政策口径与依据
-- ===========================================================================
-- 🔴 **「平台政策要求」是本模块的输入之一**：每个维度由租户自己写明敏感度、
--   阈值与**政策依据**（依据哪条平台政策 / 内部风控口径）。
--   系统**不替租户决定**哪些维度敏感 —— 那是租户的经营决策。
--
-- 🔴 维度值域（见文件头第五节）：
--   LEGAL_ENTITY —— 事实来自 `shop.legal_entity_id`（上游真实数据，自动读取）
--   DEVICE / NETWORK_IP / ADDRESS / OTHER —— 事实来自本模块的登记面
--   **不含 PAYMENT_METHOD / PAYOUT_ACCOUNT**（归 12.7.1，未交付）
CREATE TABLE linkage_policy_dimension (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  policy_id uuid NOT NULL,

  dimension varchar(24) NOT NULL
    CHECK(dimension IN ('LEGAL_ENTITY','DEVICE','NETWORK_IP','ADDRESS','OTHER')),

  -- 该维度共享同一标识时的敏感度（受控值域，DB 层 CHECK 钉死）
  sensitivity varchar(16) NOT NULL CHECK(sensitivity IN ('HIGH','MEDIUM','LOW')),

  -- 该维度自己成立一条关联边所需的最少店铺数（>= 2，**无默认值**）
  min_shop_count int NOT NULL CHECK(min_shop_count >= 2 AND min_shop_count <= 50),

  -- 平台政策依据（租户自己写明依据哪条政策 / 内部口径）
  policy_basis varchar(400) NOT NULL DEFAULT '',

  -- 是否参与判定（false = 该维度**不产出**关联边与风险提示）
  enabled boolean NOT NULL DEFAULT true,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个策略下每个维度至多一条
  UNIQUE(tenant_id,policy_id,dimension),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE linkage_policy_dimension IS
  '16.5.2.2 · 逐维度的关联政策口径：敏感度 / 阈值 / 平台政策依据 / 是否参与判定。';

CREATE INDEX linkage_policy_dimension_by_policy
  ON linkage_policy_dimension(tenant_id, policy_id);

-- ===========================================================================
-- 三、linkage_identifier · 关联标识（共享因子）登记
-- ===========================================================================
-- 🔴 这是本模块**自持**的关联事实登记面：租户登记**已经观测到**的共享因子。
--   本仓没有设备 / 网络 IP / 地址的上游数据表 ⇒ 本模块**不自动发现**它们，
--   事实靠登记；**登记什么就是什么，绝不推断、绝不编造**。
--
-- 🔴 dimension 不含 LEGAL_ENTITY（真相在 `shop.legal_entity_id`），
--   也不含 PAYMENT_METHOD / PAYOUT_ACCOUNT（真相归 12.7.1，未交付）。
CREATE TABLE linkage_identifier (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  dimension varchar(24) NOT NULL
    CHECK(dimension IN ('DEVICE','NETWORK_IP','ADDRESS','OTHER')),

  -- 归一化后的判定键（同维度内唯一）：判定走它，展示走 display_label
  identifier_key varchar(200) NOT NULL CHECK(btrim(identifier_key)<>''),

  -- 展示用标签（可以是脱敏后的：设备尾号 / 网段摘要 / 地址到区级）
  display_label varchar(200) NOT NULL DEFAULT '' CHECK(btrim(display_label)<>''),

  -- ACTIVE = 参与判定；RETIRED = 保留为历史但不再产出关联边
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),

  first_observed_at timestamptz NOT NULL DEFAULT now(),
  last_observed_at timestamptz NOT NULL DEFAULT now(),
  note varchar(600) NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同维度内同一个标识只登记一次（重新登记 = 更新观测时刻）
  UNIQUE(tenant_id,dimension,identifier_key),
  CHECK(last_observed_at >= first_observed_at),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE linkage_identifier IS
  '16.5.2.2 · 关联标识（共享因子）登记面：租户登记已观测到的设备 / 网络 IP / 地址等共享事实。';

CREATE INDEX linkage_identifier_by_dimension
  ON linkage_identifier(tenant_id, dimension, status);

-- ===========================================================================
-- 四、linkage_shop_identifier · 店铺 ↔ 标识 的边
-- ===========================================================================
-- 🔴 shop_id **不建外键**（既有测试用 truncate 清库），但由 BEFORE INSERT 触发器
--   校验「店铺真实存在且同租户」—— 登记一条指向不存在店铺的边必须失败，
--   否则图谱里会出现**看不见的幽灵节点**。
CREATE TABLE linkage_shop_identifier (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  identifier_id uuid NOT NULL,
  shop_id uuid NOT NULL,

  -- 事实来源：租户自己登记的观测（不是本模块抓来的）
  source varchar(24) NOT NULL DEFAULT 'MANUAL'
    CHECK(source IN ('MANUAL','PLATFORM_NOTICE','OPS_REPORT')),

  observed_at timestamptz NOT NULL DEFAULT now(),
  note varchar(600) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一标识下同一店铺只登记一次
  UNIQUE(tenant_id,identifier_id,shop_id)
);
COMMENT ON TABLE linkage_shop_identifier IS
  '16.5.2.2 · 店铺与关联标识的边（当前态）：一标识被几家店铺共享 = 一条关联事实。';

CREATE INDEX linkage_shop_identifier_by_identifier
  ON linkage_shop_identifier(tenant_id, identifier_id);
CREATE INDEX linkage_shop_identifier_by_shop
  ON linkage_shop_identifier(tenant_id, shop_id);

-- ===========================================================================
-- 五、linkage_cluster · 关联组（图谱的物化当前态）
-- ===========================================================================
-- 🔴 关联组 = 共享因子连成的一张**连通分量**（A 与 B 共享设备、B 与 C 共享地址
--   ⇒ A/B/C 是一组，哪怕 A 与 C 没有直接共享）。连通分量由服务层实算后落库，
--   cluster_key 由成员店铺集合**确定性地**算出 ⇒ 重扫同一批事实得到同一个 key，
--   同一组的风险提示**不会**每次扫描都刷一条新的。
CREATE TABLE linkage_cluster (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 成员店铺集合的确定性摘要（sha256 hex）
  cluster_key varchar(64) NOT NULL CHECK(btrim(cluster_key)<>''),

  -- 组内店铺数（>= 2：只有一家店的组不是关联）
  shop_count int NOT NULL CHECK(shop_count >= 2),

  -- 参与判定的维度（逗号分隔，便于列表展示与筛选）
  dimensions varchar(200) NOT NULL DEFAULT '',

  -- 组内最高严重度（受控值域）
  severity varchar(16) NOT NULL CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  -- 风险等级（受控值域；由严重度 + 组内店铺数实算）
  risk_level varchar(16) NOT NULL CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),

  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','ACKNOWLEDGED','REMEDIATED','DISMISSED')),

  -- 人话结论（说明**凭什么**判成这一组）
  summary varchar(800) NOT NULL DEFAULT '',

  first_detected_at timestamptz NOT NULL DEFAULT now(),
  last_detected_at timestamptz NOT NULL DEFAULT now(),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一租户内同一个关联组只有一行
  UNIQUE(tenant_id,cluster_key),
  CHECK(last_detected_at >= first_detected_at),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE linkage_cluster IS
  '16.5.2.2 · 关联组：共享因子连成的连通分量（物化当前态），带实算的严重度与风险等级。';

CREATE INDEX linkage_cluster_by_risk
  ON linkage_cluster(tenant_id, risk_level, last_detected_at DESC);

-- ===========================================================================
-- 六、linkage_cluster_shop · 关联组成员
-- ===========================================================================
-- 🔴 成员是**当前态**：重扫时按新事实整体替换（先删后插），
--   但**代码 / 名称快照保留**，这样即使店铺后来改名或停用，
--   也能解释「当时关联的是哪几家店」。
CREATE TABLE linkage_cluster_shop (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  cluster_id uuid NOT NULL,
  shop_id uuid NOT NULL,

  shop_code varchar(64) NOT NULL DEFAULT '',
  shop_name varchar(150) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,cluster_id,shop_id)
);
COMMENT ON TABLE linkage_cluster_shop IS
  '16.5.2.2 · 关联组成员（当前态，保留店铺当时的代码 / 名称快照）。';

CREATE INDEX linkage_cluster_shop_by_cluster
  ON linkage_cluster_shop(tenant_id, cluster_id);
CREATE INDEX linkage_cluster_shop_by_shop
  ON linkage_cluster_shop(tenant_id, shop_id);

-- ===========================================================================
-- 七、linkage_alert · 风险提示
-- ===========================================================================
-- 🔴 一组关联 ⇒ 一条风险提示（dedup_key 唯一）：重扫命中同组时**更新**它，
--   而不是刷出一串一模一样的提示把运营的注意力淹掉。
CREATE TABLE linkage_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  cluster_id uuid NOT NULL,
  cluster_key varchar(64) NOT NULL CHECK(btrim(cluster_key)<>''),

  alert_type varchar(24) NOT NULL DEFAULT 'SHARED_FACTOR'
    CHECK(alert_type IN ('SHARED_FACTOR')),

  severity varchar(16) NOT NULL CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','ACKNOWLEDGED','REMEDIATED','DISMISSED')),

  -- 幂等键：SHARED_FACTOR|<cluster_key>
  dedup_key varchar(96) NOT NULL CHECK(btrim(dedup_key)<>''),

  shop_count int NOT NULL CHECK(shop_count >= 2),
  dimensions varchar(200) NOT NULL DEFAULT '',
  title varchar(200) NOT NULL CHECK(btrim(title)<>''),
  summary varchar(800) NOT NULL DEFAULT '',

  detected_at timestamptz NOT NULL DEFAULT now(),

  -- 处置字段（处置理由**必填**，由服务层校验）
  acknowledged_by uuid,
  acknowledged_at timestamptz,
  acknowledge_reason varchar(600) NOT NULL DEFAULT '',
  resolved_by uuid,
  resolved_at timestamptz,
  resolution_note varchar(600) NOT NULL DEFAULT '',
  dismissed_by uuid,
  dismissed_at timestamptz,
  dismiss_reason varchar(600) NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一租户内同一关联组只留一条提示
  UNIQUE(tenant_id,dedup_key),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE linkage_alert IS
  '16.5.2.2 · 关联风险提示：一组关联一条（dedup_key 唯一），带处置状态机与处置字段。';

CREATE INDEX linkage_alert_by_status
  ON linkage_alert(tenant_id, status, detected_at DESC);
CREATE INDEX linkage_alert_by_cluster
  ON linkage_alert(tenant_id, cluster_id);

-- ===========================================================================
-- 八、linkage_alert_action · 处置留痕（append-only）
-- ===========================================================================
-- 🔴 处置一次留一行：谁 / 什么时候 / 从什么状态到什么状态 / 为什么。
--   **append-only**：改一行 = 抹掉一次处置痕迹。
CREATE TABLE linkage_alert_action (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  alert_id uuid NOT NULL,

  action varchar(24) NOT NULL CHECK(action IN ('ACKNOWLEDGE','RESOLVE','DISMISS','RESCAN')),
  from_status varchar(16) NOT NULL CHECK(from_status IN ('OPEN','ACKNOWLEDGED','REMEDIATED','DISMISSED')),
  to_status varchar(16) NOT NULL CHECK(to_status IN ('OPEN','ACKNOWLEDGED','REMEDIATED','DISMISSED')),

  actor_id uuid,
  actor_name varchar(120) NOT NULL DEFAULT '',
  reason varchar(600) NOT NULL DEFAULT '',

  occurred_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);
COMMENT ON TABLE linkage_alert_action IS
  '16.5.2.2 · 关联风险提示的处置留痕（append-only）。';

CREATE INDEX linkage_alert_action_by_alert
  ON linkage_alert_action(tenant_id, alert_id, occurred_at DESC);

-- ===========================================================================
-- 九、linkage_remediation · 整改建议
-- ===========================================================================
-- 🔴 建议是**按维度生成的**（共享设备 ⇒ 建议分离设备；共享法人 ⇒ 建议核对法人绑定），
--   加上「用官方 API 授权把店铺分开」这一条通用口径（严重度 HIGH 以上才加）。
--   建议有自己的状态机（建议 → 已采纳 / 已忽略 / 已完成），租户可以真的推进整改。
CREATE TABLE linkage_remediation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  alert_id uuid NOT NULL,

  recommendation_code varchar(32) NOT NULL CHECK(recommendation_code IN (
      'SEPARATE_DEVICE','SEPARATE_NETWORK','SEPARATE_ADDRESS',
      'VERIFY_LEGAL_ENTITY','REVIEW_LINKAGE','OFFICIAL_API_AUTHORIZATION')),
  dimension varchar(24) NOT NULL CHECK(dimension IN (
      'LEGAL_ENTITY','DEVICE','NETWORK_IP','ADDRESS','OTHER','ALL')),

  title varchar(200) NOT NULL CHECK(btrim(title)<>''),
  detail varchar(800) NOT NULL DEFAULT '',
  priority varchar(16) NOT NULL CHECK(priority IN ('LOW','MEDIUM','HIGH')),

  status varchar(16) NOT NULL DEFAULT 'PROPOSED'
    CHECK(status IN ('PROPOSED','ACCEPTED','DISMISSED','DONE')),

  -- 幂等键：<alert_id>|<recommendation_code>
  dedup_key varchar(120) NOT NULL CHECK(btrim(dedup_key)<>''),

  decided_by uuid,
  decided_at timestamptz,
  decision_note varchar(600) NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,dedup_key),
  CHECK(updated_at >= created_at)
);
COMMENT ON TABLE linkage_remediation IS
  '16.5.2.2 · 关联风险整改建议：按维度生成，带独立的处置状态机。';

CREATE INDEX linkage_remediation_by_alert
  ON linkage_remediation(tenant_id, alert_id);
CREATE INDEX linkage_remediation_by_status
  ON linkage_remediation(tenant_id, status);

-- ===========================================================================
-- 十、状态机触发器（守卫在 DB 层，不靠服务层自觉）
-- ===========================================================================
-- 🔴 关联组 / 风险提示：新警 → 已确认 / 已处置 / 已忽略
CREATE FUNCTION guard_linkage_alert_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;
  IF NOT (
    (OLD.status = 'OPEN'          AND NEW.status IN ('ACKNOWLEDGED','REMEDIATED','DISMISSED')) OR
    (OLD.status = 'ACKNOWLEDGED' AND NEW.status IN ('REMEDIATED','DISMISSED'))
  ) THEN
    RAISE EXCEPTION '关联风险提示状态不能从 % 变到 %（新警 → 已确认 / 已处置 / 已忽略；已确认 → 已处置 / 已忽略；已处置与已忽略是终态）',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_alert_status_guard
  BEFORE UPDATE OF status ON linkage_alert
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_alert_status();

CREATE TRIGGER linkage_cluster_status_guard
  BEFORE UPDATE OF status ON linkage_cluster
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_alert_status();

-- 🔴 整改建议：建议 → 已采纳 / 已忽略 / 已完成
CREATE FUNCTION guard_linkage_remediation_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;
  IF NOT (
    (OLD.status = 'PROPOSED' AND NEW.status IN ('ACCEPTED','DISMISSED','DONE')) OR
    (OLD.status = 'ACCEPTED'  AND NEW.status IN ('DISMISSED','DONE'))
  ) THEN
    RAISE EXCEPTION '整改建议状态不能从 % 变到 %（建议 → 已采纳 / 已忽略 / 已完成；已采纳 → 已忽略 / 已完成；已忽略与已完成是终态）',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_remediation_status_guard
  BEFORE UPDATE OF status ON linkage_remediation
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_remediation_status();

-- ===========================================================================
-- 十一、append-only 触发器（处置留痕不可改写）
-- ===========================================================================
-- 🔴 处置留痕改一行 = 抹掉一次处置痕迹。
CREATE FUNCTION guard_linkage_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是留痕凭据：不可修改也不可删除', TG_TABLE_NAME
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER linkage_alert_action_immutable
  BEFORE UPDATE OR DELETE ON linkage_alert_action
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_immutable();

-- ===========================================================================
-- 十二、引用完整性（不建外键，改用触发器 —— 见文件头第四节）
-- ===========================================================================
CREATE FUNCTION guard_linkage_dimension_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM linkage_policy p
                 WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.policy_id) THEN
    RAISE EXCEPTION '关联策略 % 不存在或不属于当前租户', NEW.policy_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_policy_dimension_reference
  BEFORE INSERT ON linkage_policy_dimension
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_dimension_reference();

CREATE FUNCTION guard_linkage_identifier_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM linkage_identifier i
                 WHERE i.tenant_id = NEW.tenant_id AND i.id = NEW.identifier_id) THEN
    RAISE EXCEPTION '关联标识 % 不存在或不属于当前租户', NEW.identifier_id
      USING ERRCODE='23514';
  END IF;
  -- 🔴 店铺必须真实存在且同租户：否则图谱里会出现**看不见的幽灵节点**
  IF NOT EXISTS (SELECT 1 FROM shop s
                 WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.shop_id) THEN
    RAISE EXCEPTION '店铺 % 不存在或不属于当前租户', NEW.shop_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_shop_identifier_reference
  BEFORE INSERT ON linkage_shop_identifier
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_identifier_reference();

CREATE FUNCTION guard_linkage_cluster_shop_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM linkage_cluster c
                 WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.cluster_id) THEN
    RAISE EXCEPTION '关联组 % 不存在或不属于当前租户', NEW.cluster_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_cluster_shop_reference
  BEFORE INSERT ON linkage_cluster_shop
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_cluster_shop_reference();

CREATE FUNCTION guard_linkage_alert_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM linkage_cluster c
                 WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.cluster_id) THEN
    RAISE EXCEPTION '关联组 % 不存在或不属于当前租户', NEW.cluster_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_alert_reference
  BEFORE INSERT ON linkage_alert
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_alert_reference();

CREATE FUNCTION guard_linkage_alert_action_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM linkage_alert a
                 WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.alert_id) THEN
    RAISE EXCEPTION '关联风险提示 % 不存在或不属于当前租户', NEW.alert_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_alert_action_reference
  BEFORE INSERT ON linkage_alert_action
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_alert_action_reference();

CREATE FUNCTION guard_linkage_remediation_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM linkage_alert a
                 WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.alert_id) THEN
    RAISE EXCEPTION '关联风险提示 % 不存在或不属于当前租户', NEW.alert_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_remediation_reference
  BEFORE INSERT ON linkage_remediation
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_remediation_reference();

-- ===========================================================================
-- 十三、🔴 fail-closed 守卫：登记的维度必须已纳入策略判定
-- ===========================================================================
-- 🔴 蓝图口径「平台政策要求是输入」：一个**没有**被策略纳入判定的维度，
--   不许凭空产出关联边 —— 否则会有一批事实永远不参与判定，
--   而运营在界面上却以为「这个维度已经在管了」。
--
--   守卫覆盖两个入口：
--     1) 登记标识（维度必须已存在一条 enabled 的策略维度）
--     2) 挂店铺到标识（同样要求该维度已启用）
--
--   ⚠️ 策略维度被停用时**不**回删已登记的事实（那是租户自己的观测记录），
--   但它**立刻**不再产出关联边 —— 停用即刻生效。
CREATE FUNCTION linkage_assert_dimension_enabled(p_tenant uuid, p_dimension text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  v_enabled int;
BEGIN
  SELECT count(*) INTO v_enabled
    FROM linkage_policy_dimension d
    JOIN linkage_policy p ON p.tenant_id = d.tenant_id AND p.id = d.policy_id
   WHERE d.tenant_id = p_tenant AND d.dimension = p_dimension AND d.enabled;
  IF v_enabled < 1 THEN
    RAISE EXCEPTION '维度 % 还没有被纳入关联风险判定（策略里没有启用它的口径）—— 请先在策略里启用该维度并写明政策依据', p_dimension
      USING ERRCODE='23514';
  END IF;
END $$;

CREATE FUNCTION guard_linkage_identifier_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM linkage_assert_dimension_enabled(NEW.tenant_id, NEW.dimension);
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_identifier_insert_guard
  BEFORE INSERT ON linkage_identifier
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_identifier_insert();

CREATE FUNCTION guard_linkage_shop_identifier_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM linkage_assert_dimension_enabled(
    NEW.tenant_id,
    (SELECT i.dimension FROM linkage_identifier i
      WHERE i.tenant_id = NEW.tenant_id AND i.id = NEW.identifier_id));
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_shop_identifier_insert_guard
  BEFORE INSERT ON linkage_shop_identifier
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_shop_identifier_insert();

-- ===========================================================================
-- 十四、🔴 维度值域守卫：支付方式 / 收款账户归 12.7.1，本模块不许代它登记
-- ===========================================================================
-- 🔴 这两个维度的真相归 **12.7.1 资金账户（12.7.1.1 账户管理，未交付）**。
--   本模块**不建它们的影子数据** —— 两份真相必然打架。
--   守卫在数据层钉死，任何绕过服务层的写入都会被拒。
CREATE FUNCTION guard_linkage_dimension_not_finance_owned() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.dimension IN ('PAYMENT_METHOD','PAYOUT_ACCOUNT') THEN
    RAISE EXCEPTION '维度 % 的真相归 12.7.1 资金账户（未交付），本模块不代它登记 —— 否则会造出第二份资金账户真相', NEW.dimension
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER linkage_identifier_dimension_guard
  BEFORE INSERT OR UPDATE OF dimension ON linkage_identifier
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_dimension_not_finance_owned();

CREATE TRIGGER linkage_policy_dimension_scope_guard
  BEFORE INSERT OR UPDATE OF dimension ON linkage_policy_dimension
  FOR EACH ROW EXECUTE FUNCTION guard_linkage_dimension_not_finance_owned();

-- ===========================================================================
-- 十五、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['linkage_policy','linkage_policy_dimension',
                              'linkage_identifier','linkage_shop_identifier',
                              'linkage_cluster','linkage_cluster_shop',
                              'linkage_alert','linkage_alert_action',
                              'linkage_remediation'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
-- 🔴 **刻意不建 data_scope（RESTRICTIVE）策略**：关联风险是**租户级资产**
--   （一个租户的全部店铺构成一张图），不是某家店 / 某个部门的资产。
--   （与 V319 的 access_security_*、V316 的 buyer_risk_* 同一处理。）
--
-- ⚠️ **读 `shop` 时仍受既有 `data_scope` 策略约束**（V1 的 RESTRICTIVE 策略）：
--   读店铺必须用 `channel.operation.read` 权限码（否则**静默读到零行**），
--   这与 16.5.2.1 读 `credential_ref` 的教训同源。

-- ===========================================================================
-- 十六、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO channel_app;

-- 可改的配置与登记类表
GRANT SELECT,INSERT,UPDATE ON linkage_policy TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON linkage_policy_dimension TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON linkage_identifier TO channel_app;
GRANT SELECT,INSERT,DELETE ON linkage_shop_identifier TO channel_app;

-- 物化当前态：重扫时按新事实整体替换成员
GRANT SELECT,INSERT,UPDATE,DELETE ON linkage_cluster TO channel_app;
GRANT SELECT,INSERT,DELETE ON linkage_cluster_shop TO channel_app;

-- 风险提示与整改建议：可改状态，但**没有 DELETE**（提示与建议都要留痕）
GRANT SELECT,INSERT,UPDATE ON linkage_alert TO channel_app;
GRANT SELECT,INSERT,UPDATE ON linkage_remediation TO channel_app;

-- 🔴🔴 **处置留痕是 append-only 的凭据：刻意只给 SELECT,INSERT，不给 UPDATE/DELETE**
--   （另有 BEFORE UPDATE OR DELETE 触发器直接 RAISE —— 连迁移超级账号都改不动）
GRANT SELECT,INSERT ON linkage_alert_action TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V320 同口径）。
REVOKE ALL ON linkage_policy FROM starcon_app;
REVOKE ALL ON linkage_policy_dimension FROM starcon_app;
REVOKE ALL ON linkage_identifier FROM starcon_app;
REVOKE ALL ON linkage_shop_identifier FROM starcon_app;
REVOKE ALL ON linkage_cluster FROM starcon_app;
REVOKE ALL ON linkage_cluster_shop FROM starcon_app;
REVOKE ALL ON linkage_alert FROM starcon_app;
REVOKE ALL ON linkage_alert_action FROM starcon_app;
REVOKE ALL ON linkage_remediation FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;
