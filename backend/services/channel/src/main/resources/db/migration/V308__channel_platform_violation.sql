-- ===========================================================================
-- 16.1.2.1 违规与处罚 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 98-103 行）：
--   ### 16.1.2.1 违规与处罚
--   - 职责：记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
--           处理状态与整改要求。
--   - 输入：平台违规通知同步；整改记录。
--   - 输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
--   - 关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
--           **违规常涉及多个商品**，需批量定位受影响 listing 与库存。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，**零 ALTER 任何既有表**：
--   * `shop` / `platform`（V1，02 渠道与店铺）—— 店铺目录的事实来源，
--     本模块**直接读本服务自己的店铺目录**，不写死店铺常量、不造平行店铺表；
--   * `listing` / `listing_variant`（V171，4.1.1.1 Listing 列表）—— 同服务同库，
--     「批量定位受影响 listing」直接读它（**不是**跨服务读）；
--   * 03 商品 SKU（catalog）**跨服务** ⇒ 这里只存逻辑 ID `sku_id`，
--     **不建 FK、不复制 catalog 的任何一列**（与 V171 `listing_variant.internal_sku_id`
--     同一纪律）；库存（07 warehouse）同样**跨服务** ⇒ 只经授权只读 API 取。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 表命名空间（这批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   * 本模块（16.1.2.1，合规域）⇒ `platform_violation_*` / `platform_penalty_*`（本迁移）。
--   * 16.1.1.1 ⇒ `account_health_*`（V300，既有）；16.1.1.2 ⇒ `account_health_performance_*`（V304）。
--   * 同卷兄弟 ⇒ `compliance_check_*` / `product_certification_*` / `brand_authz_*` / `brand_cred_*`。
--   * 裸名 `platform_violation` 留给本族前缀本身，**不要**再加 `account_health_violation_*`
--     之类与上游混淆的名字。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 值域必须由 **DB 层 CHECK 钉死**，不能只靠应用层 enum
-- ---------------------------------------------------------------------------
--   * 处罚措施（蓝图点名的五种）= WARNING 警告 / DELIST 下架 / RESTRICT_SALES 限制销售 /
--     DEDUCTION 扣款 / SUSPEND_ACCOUNT 停号 ⇒ `platform_penalty.measure` 的 CHECK。
--   * 违规类型（蓝图只写「违规类型」，未列举）⇒ 本模块给出一份**默认值域**并用 CHECK 钉死。
--     🔴 这份值域是**本模块的默认口径、不是平台事实**（各平台的违规分类并不通用），
--     能力声明里写明这一点。
--   * 处理状态（蓝图加粗句的「处理状态」）⇒ 未处理 / 处理中 / 已整改 / 申诉中 / 已关闭，
--     **合法转换由触发器钉死**（见第四节）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 扣款金额 = Money（金额 + 币种），**不同币种不得直接相加**
-- ---------------------------------------------------------------------------
-- `platform_penalty.penalty_amount numeric(19,6)` + `penalty_currency varchar(3)`：
--   * DEDUCTION（扣款）⇒ **必须**同时给出金额与币种；
--   * 其它四种措施 ⇒ **一律不带金额**（警告 / 下架 / 限制销售 / 停号没有「扣多少钱」这件事，
--     硬塞一个 0 进去会让「没扣款」和「扣款 0」变成同一件事）；
--   * 金额 ≥ 0、币种是三位大写字母 ⇒ 全部由 CHECK 钉死。
-- 🔴 合计口径：**服务层按币种分组返回合计，绝不跨币种相加**；要跨币种合计必须先有明确折算口径，
--   本模块**不假设任何汇率**（假设一个隐含汇率比不合计更危险）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 状态机：显式枚举 + 显式合法转换（DB 与服务层都拒非法转换）
-- ---------------------------------------------------------------------------
-- 违规处理状态：
--   PENDING（未处理）→ IN_PROGRESS（处理中）
--   IN_PROGRESS → RECTIFIED（已整改）/ APPEALING（申诉中）/ CLOSED（已关闭）
--   RECTIFIED   → IN_PROGRESS / APPEALING / CLOSED
--   APPEALING   → IN_PROGRESS / RECTIFIED / CLOSED
--   CLOSED      → **终态**（不可再改）
-- 整改要求状态：
--   OPEN（待整改）→ IN_PROGRESS（整改中）/ SUBMITTED（已提交）
--   IN_PROGRESS → SUBMITTED
--   SUBMITTED   → VERIFIED（已验收）/ REJECTED（已驳回）
--   REJECTED    → IN_PROGRESS
--   VERIFIED    → **终态**
-- 🔴 「已提交」必须真的提交过（有完成时刻）、「已验收」必须真的验收过（有验收时刻）——
--   这两条同样由 CHECK 钉住，不靠服务层自觉。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 整改凭证 append-only（不可改写、不可删除）
-- ---------------------------------------------------------------------------
-- `platform_violation_evidence` 是「当时交了什么」的证据 ⇒ 触发器 `..._immutable` +
-- `..._no_delete` 钉死（照抄 16.1.1.2 的 `account_health_performance_alert_*` 那套写法）。
-- 可逆性靠**追加新凭证**冲销，不靠改历史。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 幂等：唯一键落在一个**真实的外部标识**上
-- ---------------------------------------------------------------------------
-- 平台违规通知天然带一个平台侧的通知 id ⇒ `platform_violation.external_notice_id`，
-- 唯一键 `UNIQUE(tenant_id, shop_id, external_notice_id)`：
-- 同一条平台违规重复同步 ⇒ 命中唯一键 ⇒ **不产生第二条记录**（更新即幂等，不是新增）。
-- 🔴 幂等锚点**必须**是平台侧那个不透明 id，**不是**「标题 + 时间」这种能撞车的组合 ——
--   后者会让两条不同的违规被当成同一条。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 零外键（跨表引用一律用触发器校验）
-- ---------------------------------------------------------------------------
-- 与 V300 / V304 同一套写法：`shop_id` / `violation_id` 的存在性由 BEFORE 触发器校验，
-- **不建 FK**（FK 会打破同服务既有测试的 TRUNCATE 纪律）。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 多租户隔离：tenant_id NOT NULL + ENABLE **与 FORCE** ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- 🔴 本模块六张表**全部是店铺维度**的资产 ⇒ 全部带 data_scope（RESTRICTIVE）策略，
--   可见性随店铺负责人 / 部门收敛（与 V300 的 account_health_metric 同构）。
--
-- ===========================================================================

-- ===========================================================================
-- 一、platform_violation · 违规记录
-- ===========================================================================
-- 🔴 幂等锚点是 external_notice_id（平台侧不透明 id），**不是**标题 + 时间。
CREATE TABLE platform_violation (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 店铺（本服务 channel 自家库里的 `shop.id`）。零外键，存在性由触发器校验。
  shop_id           uuid NOT NULL,
  -- 🔴 平台违规通知的**外部标识**（平台侧不透明 id）。幂等锚点。
  external_notice_id varchar(200) NOT NULL,
  -- 🔴 来源必须显式区分：PLATFORM_SYNC（平台同步来的）/ MANUAL（人工登记的）。
  --   人工登记的**绝不能冒充平台同步来的**。
  source            varchar(16) NOT NULL
                    CHECK(source IN ('PLATFORM_SYNC','MANUAL')),
  -- 🔴 违规类型（受控值域，DB 层钉死）。**本模块的默认口径，不是平台事实**。
  violation_type    varchar(40) NOT NULL
                    CHECK(violation_type IN (
                      'PRODUCT_IP_INFRINGEMENT',   -- 知识产权侵权
                      'PRODUCT_SAFETY',            -- 产品安全（不合规 / 危险品）
                      'PRODUCT_MISREPRESENTATION',  -- 商品信息不实（描述与实物不符）
                      'PROHIBITED_ITEM',           -- 违禁品
                      'RESTRICTED_ITEM',           -- 禁限售
                      'ACCOUNT_HEALTH',            -- 账号健康类违规（绩效不达标）
                      'FULFILLMENT',               -- 履约违规（迟发 / 取消 / 物流）
                      'CONTENT',                   -- 详情页内容违规
                      'CUSTOMER_SERVICE',          -- 客诉与客服违规
                      'OTHER')),                   -- 其他
  title             varchar(200) NOT NULL,
  description       text NOT NULL DEFAULT '',
  -- 🔴 平台判定/通知的时刻（本模块的核心事实字段）。
  occurred_at       timestamptz NOT NULL,
  -- 🔴 平台下发通知的时刻（可空：人工登记时可能没有这个时刻）。
  notified_at       timestamptz,
  -- 🔴 处理状态（受控值域）。合法转换由触发器钉死。
  status            varchar(16) NOT NULL DEFAULT 'PENDING'
                    CHECK(status IN ('PENDING','IN_PROGRESS','RECTIFIED','APPEALING','CLOSED')),
  -- 🔴 整改期限（可空：平台没给期限时不得编一个）。
  deadline          timestamptz,
  recorded_by       uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 幂等：同一条平台违规重复同步不产生第二条记录。
  UNIQUE(tenant_id, shop_id, external_notice_id),
  CHECK(btrim(external_notice_id) <> ''),
  CHECK(btrim(title) <> '')
);
COMMENT ON TABLE platform_violation IS
  '16.1.2.1 · 平台违规记录。external_notice_id 是**平台侧不透明 id**，也是幂等锚点；'
  'source 区分平台同步与人工登记，人工登记的条目**不冒充**平台同步。'
  'violation_type / status 的值域与合法转换由 DB 层 CHECK + 触发器钉死。';
CREATE INDEX platform_violation_shop
  ON platform_violation(tenant_id, shop_id, occurred_at DESC, id);
CREATE INDEX platform_violation_status
  ON platform_violation(tenant_id, status, occurred_at DESC);
CREATE INDEX platform_violation_type
  ON platform_violation(tenant_id, violation_type, occurred_at DESC);

-- ===========================================================================
-- 二、platform_violation_subject · 违规涉及的商品 / 订单（**多对多**）
-- ===========================================================================
-- 🔴 一张违规**常涉及多个商品** ⇒ 关联是多对多，不是把 sku 塞进违规行的一个列里。
--   SKU 是 catalog 的逻辑 ID（跨服务，不建 FK）；LISTING 是本服务自己的 listing.id；
--   ORDER 只存订单号（订单归 09 履约，跨服务）。
CREATE TABLE platform_violation_subject (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  violation_id  uuid NOT NULL,
  subject_type  varchar(16) NOT NULL
                CHECK(subject_type IN ('SKU','LISTING','ORDER')),
  -- 🔴 展示用引用（SKU 编码 / 平台商品 id / 订单号）。原文照抄，不折叠大小写。
  subject_ref   varchar(200) NOT NULL,
  -- 🔴 与 subject_type 对应的定位列（跨服务的只存逻辑 id，不建 FK）。
  sku_id        uuid,
  listing_id    uuid,
  order_ref     varchar(64),
  -- 🔴 受影响数量（NUMERIC(19,6)，禁浮点）。可空 = 平台没给数量。
  affected_quantity numeric(19,6),
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一张违规里同一个主体只登记一次。
  UNIQUE(tenant_id, violation_id, subject_type, subject_ref),
  CHECK(btrim(subject_ref) <> ''),
  -- 🔴 主体类型与定位列必须自洽（不许声称是 SKU 却只给订单号）。
  CHECK((subject_type = 'SKU'     AND sku_id IS NOT NULL AND listing_id IS NULL AND order_ref IS NULL)
     OR (subject_type = 'LISTING' AND listing_id IS NOT NULL AND sku_id IS NULL AND order_ref IS NULL)
     OR (subject_type = 'ORDER'   AND order_ref IS NOT NULL AND sku_id IS NULL AND listing_id IS NULL)),
  CHECK(affected_quantity IS NULL OR affected_quantity >= 0)
);
COMMENT ON TABLE platform_violation_subject IS
  '16.1.2.1 · 违规涉及的商品 / 订单（**多对多**）。'
  'subject_type 与定位列由 CHECK 钉死；跨服务的 sku_id / order_ref **只存逻辑 id**。';
CREATE INDEX platform_violation_subject_violation
  ON platform_violation_subject(tenant_id, violation_id, subject_type);
CREATE INDEX platform_violation_subject_sku
  ON platform_violation_subject(tenant_id, sku_id) WHERE sku_id IS NOT NULL;
CREATE INDEX platform_violation_subject_listing
  ON platform_violation_subject(tenant_id, listing_id) WHERE listing_id IS NOT NULL;

-- ===========================================================================
-- 三、platform_penalty · 处罚措施（**含扣款金额**）
-- ===========================================================================
-- 🔴 措施是受控值域（蓝图点名的五种），DB 层钉死。
-- 🔴 扣款金额 = Money（金额 + 币种）；**其它措施一律不带金额**。
CREATE TABLE platform_penalty (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  violation_id  uuid NOT NULL,
  shop_id       uuid NOT NULL,
  -- 🔴 处罚措施（受控值域，DB 层钉死）。
  measure       varchar(24) NOT NULL
                CHECK(measure IN ('WARNING','DELIST','RESTRICT_SALES','DEDUCTION','SUSPEND_ACCOUNT')),
  -- 🔴 扣款金额（Money 的金额部分）+ 币种。**禁浮点、禁把不同币种直接相加**。
  penalty_amount   numeric(19,6),
  penalty_currency varchar(3),
  effective_at  timestamptz NOT NULL,
  ends_at       timestamptz,
  status        varchar(16) NOT NULL DEFAULT 'ACTIVE'
                CHECK(status IN ('ACTIVE','RELEASED','EXPIRED')),
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 扣款必须给出金额与币种；其它措施**不许**带金额
  --   （硬塞一个 0 进去会让「没扣款」与「扣款 0」变成同一件事）。
  CHECK((measure = 'DEDUCTION'
         AND penalty_amount IS NOT NULL
         AND penalty_currency IS NOT NULL AND btrim(penalty_currency) <> '')
     OR (measure <> 'DEDUCTION'
         AND penalty_amount IS NULL
         AND penalty_currency IS NULL)),
  CHECK(penalty_amount IS NULL OR penalty_amount >= 0),
  CHECK(penalty_currency IS NULL OR penalty_currency ~ '^[A-Z]{3}$'),
  CHECK(ends_at IS NULL OR ends_at >= effective_at)
);
COMMENT ON TABLE platform_penalty IS
  '16.1.2.1 · 处罚措施。measure 是**受控值域**（警告 / 下架 / 限制销售 / 扣款 / 停号）。'
  '扣款金额 = Money（numeric(19,6) + 三位币种），**不同币种不得直接相加**。';
CREATE INDEX platform_penalty_violation
  ON platform_penalty(tenant_id, violation_id, effective_at DESC);
CREATE INDEX platform_penalty_shop
  ON platform_penalty(tenant_id, shop_id, measure, effective_at DESC);
CREATE INDEX platform_penalty_currency
  ON platform_penalty(tenant_id, penalty_currency, effective_at DESC)
  WHERE penalty_currency IS NOT NULL;

-- ===========================================================================
-- 四、platform_violation_remediation · 整改要求（要求内容 / 期限 / 状态）
-- ===========================================================================
CREATE TABLE platform_violation_remediation (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  violation_id  uuid NOT NULL,
  -- 🔴 整改要求的内容（人话，不许只写一个状态码）。
  requirement   varchar(1000) NOT NULL,
  -- 🔴 整改期限（可空：平台没给期限时不得编一个）。
  due_at        timestamptz,
  -- 🔴 整改状态（受控值域），合法转换由触发器钉死。
  status        varchar(16) NOT NULL DEFAULT 'OPEN'
                CHECK(status IN ('OPEN','IN_PROGRESS','SUBMITTED','VERIFIED','REJECTED')),
  -- 责任人（人 / 岗位的自由文本引用，跨服务不建 FK）。
  owner_ref     varchar(200) NOT NULL DEFAULT '',
  -- 🔴 提交时刻（SUBMITTED 必填）—— 「已提交」必须真的提交过。
  completed_at  timestamptz,
  -- 🔴 验收时刻（VERIFIED 必填）。
  verified_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(requirement) <> ''),
  CHECK(completed_at IS NULL OR verified_at IS NULL OR verified_at >= completed_at),
  -- 🔴 「已提交」必须真的提交过。
  CHECK(status <> 'SUBMITTED' OR completed_at IS NOT NULL),
  -- 🔴 「已验收」必须真的验收过。
  CHECK(status <> 'VERIFIED' OR (completed_at IS NOT NULL AND verified_at IS NOT NULL))
);
COMMENT ON TABLE platform_violation_remediation IS
  '16.1.2.1 · 整改要求（要求内容 / 期限 / 状态）。'
  '状态机由触发器钉死；「已提交 / 已验收」必须有对应时刻，**不靠服务层自觉**。';
CREATE INDEX platform_violation_remediation_violation
  ON platform_violation_remediation(tenant_id, violation_id, due_at);
CREATE INDEX platform_violation_remediation_status
  ON platform_violation_remediation(tenant_id, status, due_at);

-- ===========================================================================
-- 五、platform_violation_evidence · 整改凭证（**append-only**）
-- ===========================================================================
CREATE TABLE platform_violation_evidence (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  violation_id   uuid NOT NULL,
  remediation_id uuid NOT NULL,
  evidence_type  varchar(24) NOT NULL
                 CHECK(evidence_type IN ('DOCUMENT','IMAGE','VIDEO','LINK','NOTE')),
  -- 凭证引用（文件名 / 链接 / 附件 id）。与 summary 至少给一个。
  evidence_ref   varchar(500) NOT NULL DEFAULT '',
  summary        varchar(1000) NOT NULL DEFAULT '',
  submitted_by   uuid,
  submitted_at   timestamptz NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(evidence_ref) <> '' OR btrim(summary) <> '')
);
COMMENT ON TABLE platform_violation_evidence IS
  '16.1.2.1 · 整改凭证（**append-only**，触发器钉住不可改写不可删）。'
  '可逆性靠追加新凭证冲销，不靠改历史。';
CREATE INDEX platform_violation_evidence_remediation
  ON platform_violation_evidence(tenant_id, remediation_id, submitted_at DESC);
CREATE INDEX platform_violation_evidence_violation
  ON platform_violation_evidence(tenant_id, violation_id, submitted_at DESC);

-- ===========================================================================
-- 六、platform_violation_sync_run · 一次平台违规同步的运行记录
-- ===========================================================================
-- 🔴 同步入口必须留下运行记录：「跑了没有、拿到几条、写了几条、跳过几条」是可查的事实。
--   适配器未接入时 status=NOT_INTEGRATED 且 written_count=0 —— **一条违规都不写**，
--   但「我们试过了、缺口在谁那里」必须留痕。
CREATE TABLE platform_violation_sync_run (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  shop_id        uuid NOT NULL,
  status         varchar(16) NOT NULL
                 CHECK(status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED','NOT_INTEGRATED')),
  started_at     timestamptz NOT NULL,
  finished_at    timestamptz,
  fetched_count  int NOT NULL DEFAULT 0 CHECK(fetched_count >= 0),
  written_count  int NOT NULL DEFAULT 0 CHECK(written_count >= 0),
  skipped_count  int NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),
  failed_count   int NOT NULL DEFAULT 0 CHECK(failed_count >= 0),
  reason         varchar(500) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 没写成功就一条违规都不许写（计数必须自洽）。
  CHECK(written_count <= fetched_count),
  CHECK(status <> 'SUCCEEDED' OR failed_count = 0),
  CHECK(status <> 'NOT_INTEGRATED' OR (written_count = 0 AND fetched_count = 0))
);
COMMENT ON TABLE platform_violation_sync_run IS
  '16.1.2.1 · 一次平台违规同步的运行记录。'
  '适配器未接入时 status=NOT_INTEGRATED 且一条违规都不写（**不编造**同步数据）。';
CREATE INDEX platform_violation_sync_run_shop
  ON platform_violation_sync_run(tenant_id, shop_id, started_at DESC);
CREATE INDEX platform_violation_sync_run_status
  ON platform_violation_sync_run(tenant_id, status, started_at DESC);

-- ===========================================================================
-- 七、触发器
-- ===========================================================================

-- 🔴 (a) 违规处理状态机：**非法转换直接拒写**。
CREATE FUNCTION guard_platform_violation_status_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  allowed boolean := false;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;
  allowed := CASE OLD.status
    WHEN 'PENDING'     THEN NEW.status IN ('IN_PROGRESS')
    WHEN 'IN_PROGRESS' THEN NEW.status IN ('RECTIFIED','APPEALING','CLOSED')
    WHEN 'RECTIFIED'   THEN NEW.status IN ('IN_PROGRESS','APPEALING','CLOSED')
    WHEN 'APPEALING'   THEN NEW.status IN ('IN_PROGRESS','RECTIFIED','CLOSED')
    WHEN 'CLOSED'      THEN false   -- 终态：不可再改
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION
      '平台违规 % 的处理状态不能从 % 转到 %', OLD.id, OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_violation_status_transition BEFORE UPDATE ON platform_violation
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_status_transition();

-- 🔴 (b) 整改状态机：**非法转换直接拒写**。
CREATE FUNCTION guard_platform_violation_remediation_status_transition() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  allowed boolean := false;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;
  allowed := CASE OLD.status
    WHEN 'OPEN'        THEN NEW.status IN ('IN_PROGRESS','SUBMITTED')
    WHEN 'IN_PROGRESS' THEN NEW.status IN ('SUBMITTED')
    WHEN 'SUBMITTED'   THEN NEW.status IN ('VERIFIED','REJECTED')
    WHEN 'REJECTED'    THEN NEW.status IN ('IN_PROGRESS')
    WHEN 'VERIFIED'    THEN false   -- 终态：已验收的整改不可再改
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION
      '整改要求 % 的状态不能从 % 转到 %', OLD.id, OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_violation_remediation_status_transition
  BEFORE UPDATE ON platform_violation_remediation
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_remediation_status_transition();

-- 🔴 (c) 整改凭证**不可追溯改写**：提交了什么就是提交了什么。
CREATE FUNCTION guard_platform_violation_evidence_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.evidence_type IS DISTINCT FROM OLD.evidence_type
     OR NEW.evidence_ref IS DISTINCT FROM OLD.evidence_ref
     OR NEW.summary IS DISTINCT FROM OLD.summary
     OR NEW.remediation_id IS DISTINCT FROM OLD.remediation_id
     OR NEW.violation_id IS DISTINCT FROM OLD.violation_id
     OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
     OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN
    RAISE EXCEPTION
      '整改凭证 % 是追加式凭据，不可改写', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_violation_evidence_immutable BEFORE UPDATE ON platform_violation_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_evidence_immutable();

-- 🔴 (d) 整改凭证**不可删除**：它是「当时交了什么」的证据。
CREATE FUNCTION guard_platform_violation_evidence_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    '整改凭证 % 是追加式凭据，不可删除', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER platform_violation_evidence_no_delete BEFORE DELETE ON platform_violation_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_evidence_no_delete();

-- 🔴 (e) 跨表引用校验（**零外键**）：shop_id 必须在本租户的店铺目录里真实存在。
CREATE FUNCTION guard_platform_violation_shop_ref() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  ref_shop uuid;
BEGIN
  ref_shop := NEW.shop_id;
  IF NOT EXISTS (
       SELECT 1 FROM shop s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = ref_shop) THEN
    RAISE EXCEPTION
      '平台违规相关行引用了一个在本租户店铺目录里不存在的店铺（或超出当前权限范围）：%',
      ref_shop
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_violation_shop_ref BEFORE INSERT OR UPDATE ON platform_violation
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_shop_ref();
CREATE TRIGGER platform_penalty_shop_ref BEFORE INSERT OR UPDATE ON platform_penalty
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_shop_ref();
CREATE TRIGGER platform_violation_sync_run_shop_ref BEFORE INSERT OR UPDATE ON platform_violation_sync_run
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_shop_ref();

-- 🔴 (f) 跨表引用校验（**零外键**）：子表的 violation_id 必须落在本租户的违规记录里。
CREATE FUNCTION guard_platform_violation_parent_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM platform_violation v
        WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.violation_id) THEN
    RAISE EXCEPTION
      '平台违规子表引用了一条在本租户不存在的违规记录：%', NEW.violation_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_violation_subject_parent_ref BEFORE INSERT OR UPDATE ON platform_violation_subject
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_parent_ref();
CREATE TRIGGER platform_violation_remediation_parent_ref BEFORE INSERT OR UPDATE ON platform_violation_remediation
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_parent_ref();
CREATE TRIGGER platform_violation_evidence_parent_ref BEFORE INSERT OR UPDATE ON platform_violation_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_parent_ref();

-- 🔴 (g) 整改凭证引用的整改要求必须与它同属一条违规。
CREATE FUNCTION guard_platform_violation_evidence_remediation_ref() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM platform_violation_remediation r
        WHERE r.tenant_id = NEW.tenant_id
          AND r.id = NEW.remediation_id
          AND r.violation_id = NEW.violation_id) THEN
    RAISE EXCEPTION
      '整改凭证 % 引用的整改要求不属于同一条违规记录', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_violation_evidence_remediation_ref BEFORE INSERT ON platform_violation_evidence
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_evidence_remediation_ref();

-- 🔴 (h) 同步运行**结束后不可篡改计数**：可逆性靠新的运行记录，不靠改历史。
CREATE FUNCTION guard_platform_violation_sync_run_settled() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'RUNNING' THEN
    RAISE EXCEPTION '已结束的违规同步运行不可修改，请重新触发一次同步' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_violation_sync_run_settled BEFORE UPDATE ON platform_violation_sync_run
  FOR EACH ROW EXECUTE FUNCTION guard_platform_violation_sync_run_settled();

-- ===========================================================================
-- 八、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_violation','platform_violation_subject','platform_penalty',
    'platform_violation_remediation','platform_violation_evidence','platform_violation_sync_run' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V300 / V304 同构）：违规与处罚是**店铺维度**的资产，
-- 可见性随店铺负责人 / 部门收敛。
CREATE POLICY data_scope ON platform_violation AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=platform_violation.tenant_id
             AND s.id=platform_violation.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=platform_violation.tenant_id
             AND s.id=platform_violation.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON platform_penalty AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=platform_penalty.tenant_id
             AND s.id=platform_penalty.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=platform_penalty.tenant_id
             AND s.id=platform_penalty.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON platform_violation_sync_run AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=platform_violation_sync_run.tenant_id
             AND s.id=platform_violation_sync_run.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=platform_violation_sync_run.tenant_id
             AND s.id=platform_violation_sync_run.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 🔴 子表（涉及主体 / 整改要求 / 整改凭证）**没有自己的 shop_id** ⇒ 数据范围沿父违规收敛，
--   否则一条属于「看不见的店铺」的违规，它的整改凭证会被别人读到。
CREATE POLICY data_scope ON platform_violation_subject AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM platform_violation v JOIN shop s
             ON s.tenant_id=v.tenant_id AND s.id=v.shop_id
             WHERE v.tenant_id=platform_violation_subject.tenant_id
             AND v.id=platform_violation_subject.violation_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM platform_violation v JOIN shop s
             ON s.tenant_id=v.tenant_id AND s.id=v.shop_id
             WHERE v.tenant_id=platform_violation_subject.tenant_id
             AND v.id=platform_violation_subject.violation_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON platform_violation_remediation AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM platform_violation v JOIN shop s
             ON s.tenant_id=v.tenant_id AND s.id=v.shop_id
             WHERE v.tenant_id=platform_violation_remediation.tenant_id
             AND v.id=platform_violation_remediation.violation_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM platform_violation v JOIN shop s
             ON s.tenant_id=v.tenant_id AND s.id=v.shop_id
             WHERE v.tenant_id=platform_violation_remediation.tenant_id
             AND v.id=platform_violation_remediation.violation_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON platform_violation_evidence AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM platform_violation v JOIN shop s
             ON s.tenant_id=v.tenant_id AND s.id=v.shop_id
             WHERE v.tenant_id=platform_violation_evidence.tenant_id
             AND v.id=platform_violation_evidence.violation_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM platform_violation v JOIN shop s
             ON s.tenant_id=v.tenant_id AND s.id=v.shop_id
             WHERE v.tenant_id=platform_violation_evidence.tenant_id
             AND v.id=platform_violation_evidence.violation_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 九、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  platform_violation, platform_violation_subject, platform_penalty,
  platform_violation_remediation, platform_violation_evidence, platform_violation_sync_run TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V304 同口径）。
REVOKE ALL ON
  platform_violation, platform_violation_subject, platform_penalty,
  platform_violation_remediation, platform_violation_evidence, platform_violation_sync_run FROM starcon_app;

-- 🔴 **序列授权**：本迁移的六张表**全部用应用侧生成的 uuid 作主键**，没有任何
--   serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。
--   （`GRANT ... ON ALL TABLES` 不覆盖序列；将来有人给这几张表加 identity 列时，
--    必须同时补 `GRANT USAGE, SELECT ON SEQUENCE ...`，否则第一条记录就报权限不足。）

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 / V300 / V304 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 违规与处罚属于「平台操作 / 合规台账」语义，
-- 与 16.1.1.1 / 16.1.1.2 同族，复用 identity 已注册的 channel.operation.*
-- （读 / 录 / 改）与 channel.sync.trigger（触发同步），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 十、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **上游 2 同步**：平台违规通知的拉取适配器**未交付** ⇒ 服务层立
--     `vendor/PlatformViolationPort` + `vendor/UnavailablePlatformViolationPort`，
--     同步端点在适配器接上之前**恒 409 且一条违规都不写**，只落一条
--     `platform_violation_sync_run(status=NOT_INTEGRATED)` 说明缺口在谁那里。
--   * **下游 12 财务（罚扣款）**：finance 服务在本仓**尚未落地** ⇒ 本模块**只把扣款金额
--     落在自己的表里**（按币种分组，不跨币种相加），**不建财务表、不编造放行形状**。
--   * **下游 4 刊登（问题商品下架）**：4.1.1.1 的**批量下架已交付**（同服务）⇒
--     本模块可以真的把那批 listing 下架（复用 `POST /channel/listings/batch` 的 DELIST）。
--   * **下游 16.1.2.2 申诉管理**：**未交付** ⇒ 违规状态机里的 APPEALING 只表示
--     「已在申诉」，**本模块不编造申诉单、不编造申诉结果**。
-- ---------------------------------------------------------------------------
