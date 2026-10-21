-- ===========================================================================
-- V245 · 5.4.2.1 平台价格同步（catalog 服务）
-- ===========================================================================
--
-- 蓝图原文：
--   「**职责**：把生效价格回写到平台，处理平台定价规则（平台可能有最低价 / 自动比价 /
--     多站点联动），记录回写结果与被平台调整后的实际价。
--     **输入**：价格变更事件；平台定价规则；`idempotency_key`。
--     **输出**：回写任务（经 02 队列）；实际价与期望价的差异记录。
--     **关系**：上游 = 5.4.1.1 / 5.4.1.2；下游 = 2.5.1.1 平台操作。
--     **平台可能拒绝或调整价格**（如低于最低价触发），差异必须显性反馈，不能假设回写即成功。」
--
-- ---------------------------------------------------------------------------
-- 0. 本模块与上游 5.4.1.1 的写入所有权边界
-- ---------------------------------------------------------------------------
--
-- 🔴 `price_adjustment_writeback`（V240）是 **5.4.1.1 的唯一写入所有者**。
--   本模块**只读**它（同一服务、同一张库的只读 SQL，不算跨服务访问），
--   并且**绝不** UPDATE 它 —— 结掉一条待回写事实只能走 5.4.1.1 自己的
--   `POST /catalog/price-adjustments/writebacks/{id}/ack` 端点。
--   本文件里**没有**对该表的任何 GRANT 变更，也**没有**触发器写它。
--
-- 🔴 「回写任务」**不另起一套队列**：蓝图说「回写任务（经 02 队列）」，
--   而 2.5.1.1 的回写任务列表已经在 channel 服务交付（V2 `platform_operation`）。
--   本模块**复用**它：本模块只负责把任务**投递**过去（跨服务授权 API），
--   并把「这次回写的事实与结果」记在自己的表里。
--   ⇒ 本仓**没有**第二张「回写任务」表，也**没有**本模块自己的 attempts / 重试计数器：
--     重试语义住在 channel 的 `platform_operation`（status / attempts / verification）。
--
-- ---------------------------------------------------------------------------
-- 1. 三张新表
-- ---------------------------------------------------------------------------
--
--   platform_price_rule        平台定价规则（租户可配置的真实数据）
--   platform_price_sync        本模块对一条待回写事实的处置与结果（一行 = 一条事实）
--   platform_price_sync_diff   实际价与期望价的**差异记录**（只追加）
--
-- 金额一律 NUMERIC(19,4) + BigDecimal，禁浮点。比例一律 NUMERIC(9,6)（0.05 = 5%）。
-- 时间一律 timestamptz（服务层用 OffsetDateTime 绑定 + `?::timestamptz`）。

-- ===========================================================================
-- 第一节表：platform_price_rule —— 平台定价规则
-- ===========================================================================
--
-- 蓝图加粗句：「平台可能拒绝或调整价格（如低于最低价触发），差异必须显性反馈」。
-- 这句话的前提是：**平台侧的定价规则是租户登记的真实数据**，不是代码里的常量。
-- 所以规则落成表，租户自己配、自己改、自己停用。
--
-- 三种规则各自对应蓝图点名的三种平台行为：
--   MIN_PRICE       最低价：低于它平台直接拒收 ⇒ **REJECTED**
--   AUTO_COMPARE    自动比价：平台把我们压到对比平台的价 ⇒ **ADJUSTED**
--   MULTI_SITE_LINK 多站点联动：平台把我们拉到与参照站点同一价格带 ⇒ **ADJUSTED**
--
-- 作用域（platform_code × site_code × sku_id）：
--   site_code = ''  ⇒ 该平台的**全部**站点
--   sku_id IS NULL  ⇒ 该平台 / 站点下的**全部** SKU
--   （越具体的规则优先级越高：先按 sku 命中，再按站点命中，最后按平台命中）
CREATE TABLE platform_price_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 规则类型（见文件头）。
  rule_type text NOT NULL
    CHECK(rule_type IN ('MIN_PRICE','AUTO_COMPARE','MULTI_SITE_LINK')),

  -- 作用域。platform_code 沿用 5.2.1.1 / 3.6 体系里 platform_code 的同一套约定：
  -- **字符串码 + 不建码表副本**（channel 的 platform / marketplace 是全局码表，catalog 不复制它）。
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL DEFAULT '',
  sku_id        uuid,

  -- ACTIVE 生效 / DISABLED 停用（停用 = 保留历史口径，不再参与判定）。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),

  -- 优先级：数字越小越先判定。默认 100。
  priority int NOT NULL DEFAULT 100,

  -- ---- MIN_PRICE：平台最低价 ----
  -- 期望价低于它 ⇒ 平台拒收（本模块**不发**这次回写，并显性回报原因）。
  min_price numeric(19,4),
  currency  varchar(3),

  -- ---- AUTO_COMPARE：平台自动比价 ----
  -- 对比平台 / 对比站点；max_gap_ratio = 允许的最大价差比例（0.05 = 允许比对比价高 5%）。
  -- 超出 ⇒ 平台把价压到对比价（ADJUSTED）。
  compare_platform_code varchar(32) NOT NULL DEFAULT '',
  compare_site_code     varchar(32) NOT NULL DEFAULT '',
  max_gap_ratio         numeric(9,6),

  -- ---- MULTI_SITE_LINK：多站点联动 ----
  -- 参照站点；本站点价格必须落在参照站点价格的 [min_ratio, max_ratio] 带内，
  -- 越界 ⇒ 平台把价拉到带边界（ADJUSTED）。
  reference_site_code varchar(32) NOT NULL DEFAULT '',
  min_ratio           numeric(9,6),
  max_ratio           numeric(9,6),

  note       varchar(400) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(btrim(platform_code) <> ''),
  CHECK(site_code = '' OR btrim(site_code) <> ''),
  CHECK(btrim(reference_site_code) = '' OR btrim(reference_site_code) <> ''),

  -- 🔴 每种类型的参数**必须齐备**：缺参数的类型行写不进来，
  --   免得「一条没配最低价的最低价规则」在库里静默存在、判定时又拿不出结果。
  CHECK(rule_type <> 'MIN_PRICE'
        OR (min_price IS NOT NULL AND min_price > 0
            AND currency IS NOT NULL AND btrim(currency) ~ '^[A-Z]{3}$')),
  CHECK(rule_type <> 'AUTO_COMPARE'
        OR (btrim(compare_platform_code) <> '' AND max_gap_ratio IS NOT NULL
            AND max_gap_ratio >= 0)),
  CHECK(rule_type <> 'MULTI_SITE_LINK'
        OR (btrim(reference_site_code) <> '' AND min_ratio IS NOT NULL AND max_ratio IS NOT NULL
            AND min_ratio > 0 AND max_ratio >= min_ratio))
);

-- 规则列表：按状态 + 类型筛，按优先级（判定顺序）排。
CREATE INDEX platform_price_rule_by_scope
  ON platform_price_rule(tenant_id, status, priority, rule_type, created_at, id);
-- 「这个平台 / 站点上有哪些规则」—— 判定时的热路径。
CREATE INDEX platform_price_rule_by_target
  ON platform_price_rule(tenant_id, platform_code, site_code, status);

-- 🔴 同一作用域同一类型只能有一条**生效**规则：避免两条最低价规则互相矛盾、
--   判定结果取决于插入顺序。
--   sku_id 可空 ⇒ 用全零 uuid 占位（唯一索引里不能直接用 NULL 的等价语义）。
--   ⚠️ 必须是**唯一索引**而不是 UNIQUE 约束：PostgreSQL 的表级 UNIQUE 约束只接受列名，
--   不接受表达式（`coalesce(...)` 会报 syntax error）。
--   ⚠️ **部分索引**（WHERE status='ACTIVE'）：停用的规则不占坑 ——
--   「停用旧规则 + 新建一条同作用域的规则」是正常的运营动作，
--   若停用的规则也占坑，这条路就被堵死了。
CREATE UNIQUE INDEX platform_price_rule_scope_unique
  ON platform_price_rule(tenant_id, rule_type, platform_code, site_code,
                         coalesce(sku_id, '00000000-0000-0000-0000-000000000000'::uuid))
  WHERE status = 'ACTIVE';

-- ===========================================================================
-- 第二节表：platform_price_sync —— 一条待回写事实在本模块的处置与结果
-- ===========================================================================
--
-- 一行 = 上游（5.4.1.1 / 5.4.1.2）的一条待回写事实。
-- 它**不是**第二套队列：没有 attempts、没有调度器、没有重试计数。
-- 队列语义住在 channel 的 `platform_operation`（02 队列）；本表只回答
-- 「这条事实我们**怎么处置的**」「平台最后**收了多少**」。
--
-- 🔴 三个价各司其职，**不合并**（合并就丢掉了「谁说的」这个事实）：
--   expected_price  期望价 = 调价单的新价（我们**要求**平台收的价）
--   predicted_price 规则判定价（平台定价规则算出来的**承接价**，可能为空 = 判不出来）
--   actual_price    平台回执里的**实际价**（平台真正收的价；还没回执时为空）
--   price_delta     actual_price - expected_price（**实际差异**，显性反馈的核心数字）
CREATE TABLE platform_price_sync (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 上游事实。**不建外键**（同 V240 的做法）：触发器校验。
  writeback_id   uuid NOT NULL,
  order_id       uuid NOT NULL,
  order_item_id  uuid NOT NULL,

  sku_id        uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  expected_price  numeric(19,4) NOT NULL CHECK(expected_price > 0),
  predicted_price numeric(19,4) CHECK(predicted_price IS NULL OR predicted_price > 0),
  actual_price    numeric(19,4) CHECK(actual_price IS NULL OR actual_price > 0),
  price_delta     numeric(19,4),

  -- 最终处置：
  --   PENDING   已投递，等平台回执（**不是**成功，也不是失败）
  --   ACCEPTED  平台按期望价收了
  --   ADJUSTED  平台改了价（实际价 ≠ 期望价，差额在 price_delta）
  --   REJECTED  平台拒收（最低价触发 / 平台侧规则）—— reason 写明为什么
  --   FAILED    回写没发出去（渠道不可达等）—— last_error 写明为什么
  outcome text NOT NULL
    CHECK(outcome IN ('PENDING','ACCEPTED','ADJUSTED','REJECTED','FAILED')),

  -- 规则判定结果（与 outcome 分开：outcome 是**这次回写**的结局，
  -- rule_outcome 是**规则**给出的判定 —— 平台回执可能推翻规则判定，两者都要看得见）。
  rule_outcome text NOT NULL DEFAULT 'NOT_EVALUATED'
    CHECK(rule_outcome IN ('NOT_EVALUATED','NO_RULE','ACCEPTED','ADJUSTED','REJECTED')),
  rule_id     uuid,
  rule_reason varchar(500) NOT NULL DEFAULT '',

  -- 02 队列任务（channel 的 platform_operation）。
  platform_operation_id uuid,
  platform_request_id   varchar(128) NOT NULL DEFAULT '',

  -- 幂等键：**沿用上游的 idempotency_key**（它已经是「下游按它去重」的那把钥匙）。
  -- 同一条事实重复投递 ⇒ 这里命中唯一约束 ⇒ 不产生第二条回写、第二个队列任务。
  idempotency_key varchar(160) NOT NULL CHECK(btrim(idempotency_key) <> ''),

  last_error varchar(500) NOT NULL DEFAULT '',

  dispatched_at timestamptz,
  completed_at  timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, writeback_id),
  UNIQUE(tenant_id, idempotency_key),

  CHECK(btrim(platform_code) <> '' AND btrim(site_code) <> ''),

  -- 未完成的没有完成时刻，已完成的必有完成时刻。
  CHECK((outcome = 'PENDING') = (completed_at IS NULL)),

  -- 🔴 价与结局必须自洽：数字写错时**在库里**就写不进来，
  --   而不是等到页面上显示成一个自相矛盾的差异。
  CHECK(outcome <> 'ACCEPTED'
        OR (actual_price IS NOT NULL AND actual_price = expected_price AND price_delta = 0)),
  CHECK(outcome <> 'ADJUSTED'
        OR (actual_price IS NOT NULL AND actual_price <> expected_price
            AND price_delta = actual_price - expected_price)),
  CHECK(outcome NOT IN ('REJECTED','FAILED')
        OR (actual_price IS NULL AND price_delta IS NULL)),
  CHECK(outcome <> 'PENDING' OR (actual_price IS NULL AND price_delta IS NULL))
);

-- 结果清单：按结局筛（运营最常问「哪些被平台拒了」）。
CREATE INDEX platform_price_sync_by_outcome
  ON platform_price_sync(tenant_id, outcome, created_at DESC, id);
-- 「这个 SKU 在这个店铺回写过几次、结果分别是什么」。
CREATE INDEX platform_price_sync_by_sku
  ON platform_price_sync(tenant_id, sku_id, platform_code, site_code, created_at DESC);
-- 按 02 队列任务反查（核验通道要用）。
CREATE INDEX platform_price_sync_by_operation
  ON platform_price_sync(tenant_id, platform_operation_id);

-- ===========================================================================
-- 第三节表：platform_price_sync_diff —— 实际价与期望价的差异记录（只追加）
-- ===========================================================================
--
-- 蓝图输出句：「**实际价与期望价的差异记录**」。
-- 只追加，不改不删：一次回写可能留下多条差异记录
-- （规则判定一条、平台回执一条），历史不能被后来的结论覆盖掉。
CREATE TABLE platform_price_sync_diff (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  sync_id      uuid NOT NULL,
  writeback_id uuid NOT NULL,
  sku_id       uuid NOT NULL,

  platform_code varchar(32) NOT NULL,
  site_code     varchar(32) NOT NULL,
  currency      varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  expected_price numeric(19,4) NOT NULL CHECK(expected_price > 0),
  actual_price   numeric(19,4) CHECK(actual_price IS NULL OR actual_price > 0),
  -- 实际差异 = actual - expected。
  price_delta    numeric(19,4),
  -- 偏离比例 = |delta| / expected（0.05 = 差 5%）。expected 为 0 时无意义，故可空。
  deviation_ratio numeric(9,6),

  -- MATCHED  实际价 = 期望价（无差异）
  -- ADJUSTED 平台改了价
  -- REJECTED 平台拒收（没有实际价）
  -- FAILED   回写没发出去（没有实际价）
  outcome text NOT NULL
    CHECK(outcome IN ('MATCHED','ADJUSTED','REJECTED','FAILED')),

  -- 差异是哪一步产生的：
  --   RULE_EVALUATION   规则判定（派发时算出来的平台承接价）
  --   PLATFORM_RECEIPT  平台回执（平台真正给的价）
  source text NOT NULL
    CHECK(source IN ('RULE_EVALUATION','PLATFORM_RECEIPT')),

  reason_code    varchar(64)  NOT NULL DEFAULT '',
  reason_message varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 差异与它的价必须自洽。
  CHECK(outcome <> 'MATCHED' OR (actual_price IS NOT NULL AND actual_price = expected_price)),
  CHECK(outcome <> 'ADJUSTED' OR (actual_price IS NOT NULL AND actual_price <> expected_price)),
  CHECK(outcome NOT IN ('REJECTED','FAILED') OR actual_price IS NULL)
);

-- 差异台账：按回写记录取全部差异（详情页用）。
CREATE INDEX platform_price_sync_diff_by_sync
  ON platform_price_sync_diff(tenant_id, sync_id, created_at, id);
-- 「这个 SKU 在这个店铺被平台改过多少次价、差多少」（运营最常问的那句话）。
CREATE INDEX platform_price_sync_diff_by_sku
  ON platform_price_sync_diff(tenant_id, sku_id, platform_code, site_code, created_at DESC);
-- 只看「被改价 / 被拒收」的那批。
CREATE INDEX platform_price_sync_diff_by_outcome
  ON platform_price_sync_diff(tenant_id, outcome, created_at DESC, id);

-- ===========================================================================
-- 第四节：引用校验用触发器，不用外键（照抄 V240 第二节的做法）
-- ===========================================================================
--
-- 理由与 V240 逐字相同：触发器的判据与外键等价（引用不存在 ⇒ 报 23514），
-- 但**不参与 TRUNCATE 的依赖图**。

-- 规则若指定了 SKU，必须挂在本租户真实存在的 SKU 上。
CREATE FUNCTION guard_platform_price_rule() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.sku_id IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION 'Platform price rule % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_price_rule_guard
  BEFORE INSERT OR UPDATE ON platform_price_rule
  FOR EACH ROW EXECUTE FUNCTION guard_platform_price_rule();

-- 回写记录必须挂在真实存在的待回写事实上（上游 5.4.1.1 的那张表）。
CREATE FUNCTION guard_platform_price_sync() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM price_adjustment_writeback w
     WHERE w.tenant_id=NEW.tenant_id AND w.id=NEW.writeback_id) THEN
    RAISE EXCEPTION 'Platform price sync % must reference an existing price adjustment writeback',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION 'Platform price sync % must reference an existing SKU in the same tenant',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_price_sync_guard
  BEFORE INSERT OR UPDATE ON platform_price_sync
  FOR EACH ROW EXECUTE FUNCTION guard_platform_price_sync();

-- 差异记录必须挂在真实存在的回写记录上。
CREATE FUNCTION guard_platform_price_sync_diff() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM platform_price_sync s
     WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sync_id) THEN
    RAISE EXCEPTION 'Platform price sync diff % must reference an existing platform price sync',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_price_sync_diff_guard
  BEFORE INSERT OR UPDATE ON platform_price_sync_diff
  FOR EACH ROW EXECUTE FUNCTION guard_platform_price_sync_diff();

-- ===========================================================================
-- 第五节：RLS 与授权
-- ===========================================================================
--
-- 三张表都是租户级业务表。照抄 V240 第九节的 DO $$ FOREACH 写法：
-- 三张表的 isolation 策略必须逐字相同（漏掉 FORCE 之后，表 owner 仍能绕过策略读别人的行）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['platform_price_rule','platform_price_sync','platform_price_sync_diff'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性跟随 SKU：看不到 SKU 就看不到它的价格回写记录与差异
-- （记录里躺着这个 SKU 的期望价与平台实际价 —— 那就是别人的定价与利润结构）。
-- 这条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['platform_price_sync','platform_price_sync_diff'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- 🔴 规则表的可见性**不是**「跟随 SKU」：一条平台级规则（sku_id IS NULL）对全部 SKU 生效，
--   若照抄上面的 EXISTS 写法，它会因为 sku_id 为空而**永远不可见** ——
--   于是「平台最低价规则」在界面上根本不显示，而判定时却又生效。
--   正确口径：**指定了 SKU 的规则**跟随 SKU 可见性；**平台级规则**只要同租户即可见。
DO $$ BEGIN
  EXECUTE 'CREATE POLICY data_scope ON platform_price_rule AS RESTRICTIVE
    USING (sku_id IS NULL OR EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=platform_price_rule.tenant_id AND s.id=platform_price_rule.sku_id))
    WITH CHECK (sku_id IS NULL OR EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=platform_price_rule.tenant_id AND s.id=platform_price_rule.sku_id))';
END $$;

-- 授权：与 V240 同一口径 —— 需要 UPDATE（规则可停用、回写结果会流转），
-- **不授 DELETE**（已发生过的回写与差异记录不许删：删了「平台到底收了多少」就无从追溯）。
GRANT SELECT,INSERT,UPDATE ON platform_price_rule TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON platform_price_rule TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON platform_price_sync TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON platform_price_sync TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON platform_price_sync_diff TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON platform_price_sync_diff TO starcon_app;

-- 🔴 **刻意没有**对 `price_adjustment_writeback` 的任何 GRANT 变更：
--   那张表是 5.4.1.1 的唯一写入所有者，本模块**只读**它。
