-- ===========================================================================
-- 4.3.1.1 一键刊登（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.3.1.1 一键刊登
--   - 职责：单个 SKU 一次性发布到多个目标店铺/站点：自动套模板、填充内容、建立映射、上传媒体、
--           设置价格库存、提交平台审核。
--   - 输入：源 SKU；目标店铺与站点集合；模板选择；价格库存来源策略；`idempotency_key`。
--   - 输出：N 个发布任务（每个目标一个）+ 各任务的进度与结果（草稿/审核中/成功/驳回+原因）。
--   - 关系：上游 = 4.2.1.1、5 定价（取价）、7 库存（取可售量）、02 平台能力；
--           下游 = 2.5.1.1 回写任务。
--           **跨平台发布不是一个事务**：某个平台失败不影响其他平台，各自独立重试。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 同库已交付、本迁移**真实读取**的上游：
--   * channel V194 `listing_editor_submission` —— 4.2.1.1 刊登编辑器的**入队记录**。
--     它在入队那一刻就冻结了内容快照与校验证据，所以「一键刊登的内容来源（编辑器草稿）」
--     直接读那份冻结快照，**不重新求值**（重新求值就变成了"现在长什么样"，
--     而不是"我们当时发的是什么"）。
--   * channel V195 `listing_template` / `listing_template_field` /
--     `listing_template_asset_group` —— 4.2.2.1 刊登模板的**取值规则**。
--     本迁移只读规则，发布时对源 SKU 实时求值（规则不是一次性拷贝）。
--   * channel V1 `shop` / `shop_marketplace` / `platform` / `marketplace` —— 目标店铺与站点。
--   * channel V194 `platform_listing_field_requirement` + `platform.capabilities`
--     —— 目标平台的字段要求与能力矩阵（决定"这一栏该不该有值"）。
--   * channel V2 `platform_operation` —— 2.5.1.1 回写任务。发布请求落成
--     `operation='PUBLISH'` 的回写任务行，**下游 2.5.1.1 接手真正发给平台**。
--
-- 跨服务（只经授权 HTTP API，不跨库 SQL / FK / 视图）：
--   * 03 商品主数据 / 03 媒体库 / 03 描述与卖点库（catalog，只读）
--     —— 套用刊登模板时按规则求值，走 4.2.2.1 已交付的 `ListingTemplateSourcePort`。
--   * 07 库存可售量（warehouse，只读）—— 走 4.1.2.3 已交付的 `SellableQuantityQueryPort`。
--
-- 🔴 命名空间：本迁移占用 `listing_publish_job` / `listing_publish_job_target` /
--   `listing_publish_job_step`。**刻意不用** `publish_*` / `job` / `task` 这类裸名 ——
--   4.3.1.2 批量发布已经占了 `listing_publish_batch` / `listing_publish_task` 与
--   一批 `publish_*` 表名，两条通道必须能一眼分开。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 三张新表，**零 ALTER 既有表**
-- ---------------------------------------------------------------------------
-- 两条通道（4.3.1.1 单 SKU 多店 / 4.3.1.2 批量队列）各有各的状态机与重试语义，
-- 共用一张表就会把"一个目标一个任务、各自独立重试"和"一个批次一个队列、按平台限流"
-- 搅在一起。因此本迁移**只新建表**，不对任何既有表做 ALTER / SET NOT NULL /
-- 收紧值域的 CHECK —— 跨车道的 DDL 回归面 = 0。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 蓝图加粗句「跨平台发布不是一个事务」的物理落法
-- ---------------------------------------------------------------------------
-- 不是一句注释，是**三处结构**：
--
--   1. **粒度**：一次一键刊登 = 1 个 `listing_publish_job` + N 个
--      `listing_publish_job_target`（N = 目标店铺/站点数）。**每个目标一行**，
--      各有自己的 state / attempts / failure_reason / platform_operation_id。
--      平台 A 驳回只改 A 那一行，B、C 两行一个字节都不动。
--   2. **不变量**：目标的状态机由触发器 `guard_publish_target_state` 钉死，
--      状态**只能**在自己那一行上前进；`listing_publish_job` 上**没有**任何
--      "整体成功/整体失败"的列 —— 作业的总体结论是**从目标行聚合出来的**
--      （`COUNT(*) FILTER (WHERE state=...)`），所以"一个平台失败就把整批标失败"
--      在结构上就写不出来。
--   3. **回执**：每个目标各自挂一条 `platform_operation`（2.5.1.1 的回写任务），
--      幂等键按目标拆开（`OP1-{作业幂等键}-{目标序号}`），所以平台回执落到
--      某一个目标上时，不会波及其它目标的重试计数。
--
-- 独立重试：`listing_publish_job_target.attempts` 逐目标累加，
-- `POST /targets/{uuid}/retry` 只重试那一个目标（内容快照不变 ——
-- 重试发的是同一份内容，不是重新求值）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 蓝图加粗句「幂等」的物理落法
-- ---------------------------------------------------------------------------
-- `listing_publish_job` 上 `UNIQUE(tenant_id, idempotency_key)`：
-- 同一个键重复提交**返回同一条作业**，不产生第二条（服务层先查后写 +
-- `ON CONFLICT DO NOTHING` 兜底并发）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 蓝图加粗句「内容快照」的物理落法
-- ---------------------------------------------------------------------------
-- 「事后源商品变了要能解释『当时发的是什么』」要求快照**不可变**：
--   * `listing_publish_job_target.content_snapshot` —— 那一刻的标题 / 描述 / 卖点 /
--     图片 / 类目路径（按目标平台求值后的结果）。触发器
--     `guard_publish_target_snapshot` 钉死：写入后不许改。
--   * `listing_publish_job.content_snapshot` —— 内容来源本身（编辑器入队快照 或
--     模板规则），同样由 `guard_publish_job_snapshot` 钉死。
--   * `listing_publish_job.price_amount` / `price_currency` / `stock_quantity`
--     —— 那一刻的价格与可售量（金额 NUMERIC(19,4)，数量 NUMERIC(19,6)）。
--     价格/库存是**作业级**的：一次一键刊登给所有目标用同一个价与同一份可售量
--     （每个目标各自定价需要 05 定价，5.1.1.1 未交付）。
--   * 触发器 `guard_publish_snapshot_immutable` 额外钉住"重试不许改快照"：
--     目标从 FAILED 重试回 SUBMITTED 时，内容快照必须逐字不变。
--
-- ---------------------------------------------------------------------------
-- 五、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
--   * 金额：`price_amount numeric(19,4)` + `price_currency varchar(8)`，禁浮点。
--   * 数量：`stock_quantity numeric(19,6)`。
--   * 🔴 **没有"默认 0"**：取价来源不可用时 `price_amount IS NULL` 且
--     `price_state='UNAVAILABLE'`；不用 0 冒充"没有价格"（0 是一个价，NULL 才是没有）。
--
-- ---------------------------------------------------------------------------
-- 六、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 全部 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
--
-- ===========================================================================
-- 一、listing_publish_job · 一次一键刊登（一个源 SKU → N 个目标）
-- ===========================================================================
CREATE TABLE listing_publish_job (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 源 SKU（03 的逻辑 ID，跨服务，**不建 FK**）
  source_sku_id uuid NOT NULL,
  -- 内容来源：EDITOR_SUBMISSION（4.2.1.1 的入队记录）/ TEMPLATE（4.2.2.1 刊登模板）
  content_source text NOT NULL
                CHECK(content_source IN ('EDITOR_SUBMISSION','TEMPLATE')),
  -- 🔴 内容来源的候选 id 列表（jsonb 数组，元素是 uuid 字符串）。
  --    作业级只记"选了哪些"，**具体用了哪一个**记在目标行上（一个目标一个来源）。
  content_source_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 价格来源策略：PRICING_SERVICE（05 取价）/ MANUAL（运营手填）
  price_source  text NOT NULL CHECK(price_source IN ('PRICING_SERVICE','MANUAL')),
  -- 库存来源策略：WAREHOUSE_AVAILABLE（07 可售量）/ KEEP_UNSET（不设库存）
  stock_source  text NOT NULL CHECK(stock_source IN ('WAREHOUSE_AVAILABLE','KEEP_UNSET')),
  -- 目标仓库（WAREHOUSE_AVAILABLE 时读哪一批仓）
  warehouse_id  uuid,
  -- 幂等键：同一个键重复提交返回同一条，不产生第二条
  idempotency_key varchar(128) NOT NULL,
  -- DRAFT 已建任务未派发 / DISPATCHED 已派发（等平台回执） / CANCELLED 已撤销
  state         text NOT NULL DEFAULT 'DRAFT'
                CHECK(state IN ('DRAFT','DISPATCHED','CANCELLED')),
  -- 🔴 内容来源快照（编辑器入队快照 或 模板规则与求值依据）
  content_snapshot jsonb NOT NULL,
  -- 🔴 价格快照：金额 + 币种 + 状态（SET 已设 / UNAVAILABLE 来源不可用 / NOT_SET 未设）
  price_amount  numeric(19,4),
  price_currency varchar(8),
  price_state   text NOT NULL DEFAULT 'NOT_SET'
                CHECK(price_state IN ('SET','UNAVAILABLE','NOT_SET')),
  price_state_reason text NOT NULL DEFAULT '',
  -- 🔴 库存快照：可售量 + 状态（SET 已取 / UNAVAILABLE 读不到 / NOT_SET 未设）
  stock_quantity numeric(19,6),
  stock_state   text NOT NULL DEFAULT 'NOT_SET'
                CHECK(stock_state IN ('SET','UNAVAILABLE','NOT_SET')),
  stock_state_reason text NOT NULL DEFAULT '',
  -- 🔴 首选店铺：数据范围按它收敛（与 4.2.1.1 草稿同一手法）。
  --    每个目标另按自己的店铺收敛，所以"作业可见但某个目标不可见"是正常结果。
  primary_shop_id uuid NOT NULL,
  note          varchar(500) NOT NULL DEFAULT '',
  created_by    uuid,
  dispatched_at timestamptz,
  completed_at  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 幂等：租户内唯一
  UNIQUE(tenant_id, idempotency_key),
  FOREIGN KEY(tenant_id, primary_shop_id) REFERENCES shop(tenant_id, id),
  CHECK(btrim(idempotency_key)<>''),
  CHECK(jsonb_typeof(content_source_ids)='array'),
  CHECK(jsonb_typeof(content_snapshot)='object'),
  -- 🔴 说"已设价格"就必须有金额与币种；说"没设"就必须没有金额 —— 不许两头各说各话
  CHECK((price_state='SET') = (price_amount IS NOT NULL AND price_currency IS NOT NULL)),
  -- 🔴 同理，库存：SET 必须有数量（0 是合法的"确实没有货"，与"没取到"不同）
  CHECK((stock_state='SET') = (stock_quantity IS NOT NULL)),
  -- 🔴 说"来源不可用"就必须写清原因（前端原样展示，不许只有一个空状态）
  CHECK(price_state<>'UNAVAILABLE' OR btrim(price_state_reason)<>''),
  CHECK(stock_state<>'UNAVAILABLE' OR btrim(stock_state_reason)<>''),
  -- 🔴 派发过就必须有派发时刻
  CHECK(state<>'DISPATCHED' OR dispatched_at IS NOT NULL)
);
COMMENT ON TABLE listing_publish_job IS
  '4.3.1.1 · 🔴 一次一键刊登（一个源 SKU → N 个目标店铺/站点）。'
  '作业行**不存**总体成功/失败计数：总体结论从目标行聚合出来，'
  '所以"一个平台失败就把整批标失败"在结构上写不出来。';

CREATE INDEX listing_publish_job_recent
  ON listing_publish_job(tenant_id, created_at DESC, id);
CREATE INDEX listing_publish_job_sku
  ON listing_publish_job(tenant_id, source_sku_id, created_at DESC);
CREATE INDEX listing_publish_job_state
  ON listing_publish_job(tenant_id, state, created_at DESC);

-- ===========================================================================
-- 二、listing_publish_job_target · 🔴 每个目标一行（各自成败、各自重试）
-- ===========================================================================
CREATE TABLE listing_publish_job_target (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  job_id        uuid NOT NULL,
  -- 目标顺序（一次作业内的次序）
  position      integer NOT NULL,
  platform_id   uuid NOT NULL REFERENCES platform(id),
  shop_id       uuid NOT NULL,
  marketplace_id uuid NOT NULL REFERENCES marketplace(id),
  locale        varchar(16) NOT NULL,
  -- 目标平台类目路径（CATEGORY 作用域模板要靠它匹配）
  category_path varchar(500) NOT NULL DEFAULT '',
  -- DRAFT 草稿（已冻结内容，未提交平台）/ SUBMITTED 审核中（已提交，等平台回执）/
  -- SUCCEEDED 成功 / REJECTED 平台驳回（带原因）/ FAILED 失败（带原因，可独立重试）/
  -- CANCELLED 已撤销
  state         text NOT NULL DEFAULT 'DRAFT'
                CHECK(state IN ('DRAFT','SUBMITTED','SUCCEEDED','REJECTED','FAILED','CANCELLED')),
  -- 🔴 内容来源：这一行实际用了哪一个来源（编辑器入队记录 或 刊登模板）
  content_source text NOT NULL
                CHECK(content_source IN ('EDITOR_SUBMISSION','TEMPLATE')),
  content_source_id uuid NOT NULL,
  -- 🔴 那一刻的内容快照（标题 / 描述 / 卖点 / 图片 / 类目路径），写入后不许改
  content_snapshot jsonb NOT NULL,
  -- 平台商品 id（平台回执成功后回填；回填前为空 = 还没拿到）
  external_item_id varchar(128),
  -- 平台请求 id（2.5.1.2 未知结果核验用）
  platform_request_id varchar(128) NOT NULL DEFAULT '',
  -- 驳回 / 失败原因（REJECTED / FAILED 时非空）
  failure_reason text NOT NULL DEFAULT '',
  -- 🔴 各自独立重试：尝试次数按目标累加
  attempts      integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
  -- 下游 2.5.1.1 的回写任务（operation='PUBLISH'）
  platform_operation_id uuid,
  dispatched_at timestamptz,
  completed_at  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一作业内同一个（店铺, 站点, 语言）只允许一个目标
  UNIQUE(tenant_id, job_id, shop_id, marketplace_id, locale),
  FOREIGN KEY(tenant_id, job_id) REFERENCES listing_publish_job(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id),
  CHECK(position>=0),
  CHECK(btrim(locale)<>''),
  CHECK(jsonb_typeof(content_snapshot)='object'),
  -- 🔴 驳回 / 失败必须写清原因（前端原样展示"为什么被驳回"）
  CHECK(state NOT IN ('REJECTED','FAILED') OR btrim(failure_reason)<>''),
  -- 🔴 提交过就必须有派发时刻；拿到结果就必须有完成时刻。
  --    🔴 FAILED **不在**派发时刻这一条里：派发本身就失败的目标（回写任务写不进去）
  --    从来没有"派发过"，它的失败原因写在 failure_reason 里。
  CHECK(state NOT IN ('SUBMITTED','SUCCEEDED','REJECTED') OR dispatched_at IS NOT NULL),
  CHECK(state NOT IN ('SUCCEEDED','REJECTED','FAILED') OR completed_at IS NOT NULL)
);
COMMENT ON TABLE listing_publish_job_target IS
  '4.3.1.1 · 🔴 每个目标一行：各自的状态 / 尝试次数 / 失败原因 / 回写任务。'
  '平台 A 失败只改 A 这一行，其余目标不受影响（蓝图加粗句的物理落法）。';

CREATE INDEX listing_publish_job_target_job
  ON listing_publish_job_target(tenant_id, job_id, position);
CREATE INDEX listing_publish_job_target_state
  ON listing_publish_job_target(tenant_id, state, created_at DESC);
CREATE INDEX listing_publish_job_target_shop
  ON listing_publish_job_target(tenant_id, shop_id, marketplace_id);

-- ===========================================================================
-- 三、listing_publish_job_step · 步骤时间线（目标级 + 作业级）
-- ===========================================================================
-- 目标级步骤：TEMPLATE 套模板 / CONTENT 填充内容 / MAPPING 建立映射 /
--             MEDIA 上传媒体 / SUBMIT 提交平台审核 / RESULT 平台结果
-- 作业级步骤：PRICE 设置价格 / STOCK 设置库存（价格与库存是**作业级**的：
--             一次一键刊登给所有目标用同一个价与同一份可售量）
CREATE TABLE listing_publish_job_step (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  job_id        uuid NOT NULL,
  -- NULL = 作业级步骤；非空 = 目标级步骤
  target_id     uuid,
  step_key      text NOT NULL
                CHECK(step_key IN ('TEMPLATE','CONTENT','MAPPING','MEDIA','PRICE','STOCK','SUBMIT','RESULT')),
  position      integer NOT NULL,
  -- PENDING 未开始 / RUNNING 进行中 / SUCCEEDED 成功 / FAILED 失败 /
  -- SKIPPED 跳过 / BLOCKED 被前置条件挡住
  state         text NOT NULL DEFAULT 'PENDING'
                CHECK(state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED','BLOCKED')),
  detail        text NOT NULL DEFAULT '',
  started_at    timestamptz,
  finished_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, job_id) REFERENCES listing_publish_job(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, target_id) REFERENCES listing_publish_job_target(tenant_id, id) ON DELETE CASCADE,
  CHECK(position>=0),
  -- 🔴 步骤的归属是固定的：PRICE / STOCK 只能是作业级，其余只能是目标级。
  --    混在一起就分不清"这一步是整个作业做的"还是"这个目标做的"。
  CHECK((step_key IN ('PRICE','STOCK')) = (target_id IS NULL)),
  -- 🔴 终态的步骤必须有完成时刻；进行中的必须有开始时刻
  CHECK(state NOT IN ('SUCCEEDED','FAILED','SKIPPED','BLOCKED') OR finished_at IS NOT NULL),
  CHECK(state<>'RUNNING' OR started_at IS NOT NULL)
);
COMMENT ON TABLE listing_publish_job_step IS
  '4.3.1.1 · 步骤时间线。4.3.1.3（发布任务）未交付，本表只落数据 + 供进度/结果 API 读，'
  '不提供单次发布任务的时间线页面。';

CREATE INDEX listing_publish_job_step_job
  ON listing_publish_job_step(tenant_id, job_id, position);
CREATE INDEX listing_publish_job_step_target
  ON listing_publish_job_step(tenant_id, target_id, position);

-- ===========================================================================
-- 四、触发器
-- ===========================================================================
-- (a) 🔴 目标状态机：状态只能在自己这一行上前进，**不许跳步**。
--     DRAFT → SUBMITTED → SUCCEEDED / REJECTED / FAILED；FAILED → SUBMITTED（独立重试）。
--     SUCCEEDED / REJECTED / CANCELLED 是终态。
CREATE FUNCTION guard_publish_target_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.state = OLD.state THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'DRAFT' AND NEW.state IN ('SUBMITTED','FAILED','CANCELLED') THEN allowed := true; END IF;
  IF OLD.state = 'SUBMITTED' AND NEW.state IN ('SUCCEEDED','REJECTED','FAILED') THEN allowed := true; END IF;
  IF OLD.state = 'FAILED' AND NEW.state = 'SUBMITTED' THEN allowed := true; END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION 'Publish target % cannot move from % to %', OLD.id, OLD.state, NEW.state
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER publish_target_state BEFORE UPDATE ON listing_publish_job_target
  FOR EACH ROW EXECUTE FUNCTION guard_publish_target_state();

-- (b) 🔴 内容快照不可变：写入后不许改（"当时发的是什么"必须一直答得出来）。
--     重试也**不许**改快照 —— 重试发的是同一份内容。
CREATE FUNCTION guard_publish_target_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.content_snapshot IS DISTINCT FROM OLD.content_snapshot
     OR NEW.content_source IS DISTINCT FROM OLD.content_source
     OR NEW.content_source_id IS DISTINCT FROM OLD.content_source_id
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
     OR NEW.marketplace_id IS DISTINCT FROM OLD.marketplace_id
     OR NEW.locale IS DISTINCT FROM OLD.locale
     OR NEW.position IS DISTINCT FROM OLD.position THEN
    RAISE EXCEPTION
      'Publish target % snapshot and identity are frozen: a retry must resend the same content',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER publish_target_snapshot BEFORE UPDATE ON listing_publish_job_target
  FOR EACH ROW EXECUTE FUNCTION guard_publish_target_snapshot();

-- (c) 🔴 作业级快照不可变（内容来源快照 + 价格 + 库存）。
CREATE FUNCTION guard_publish_job_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.content_snapshot IS DISTINCT FROM OLD.content_snapshot
     OR NEW.content_source IS DISTINCT FROM OLD.content_source
     OR NEW.content_source_ids IS DISTINCT FROM OLD.content_source_ids
     OR NEW.source_sku_id IS DISTINCT FROM OLD.source_sku_id
     OR NEW.price_amount IS DISTINCT FROM OLD.price_amount
     OR NEW.price_currency IS DISTINCT FROM OLD.price_currency
     OR NEW.price_state IS DISTINCT FROM OLD.price_state
     OR NEW.stock_quantity IS DISTINCT FROM OLD.stock_quantity
     OR NEW.stock_state IS DISTINCT FROM OLD.stock_state
     OR NEW.primary_shop_id IS DISTINCT FROM OLD.primary_shop_id THEN
    RAISE EXCEPTION
      'Publish job % snapshot and identity are frozen: what was published must stay explainable',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER publish_job_snapshot BEFORE UPDATE ON listing_publish_job
  FOR EACH ROW EXECUTE FUNCTION guard_publish_job_snapshot();

-- (d) 步骤只能前进：PENDING → RUNNING → 终态；FAILED 可回到 RUNNING（独立重试）。
CREATE FUNCTION guard_publish_step_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.state = OLD.state THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'PENDING' AND NEW.state IN ('RUNNING','SUCCEEDED','FAILED','SKIPPED','BLOCKED') THEN allowed := true; END IF;
  IF OLD.state = 'RUNNING' AND NEW.state IN ('SUCCEEDED','FAILED') THEN allowed := true; END IF;
  IF OLD.state = 'FAILED' AND NEW.state = 'RUNNING' THEN allowed := true; END IF;
  IF OLD.state = 'BLOCKED' AND NEW.state = 'RUNNING' THEN allowed := true; END IF;
  IF NOT allowed THEN
    RAISE EXCEPTION 'Publish step % cannot move from % to %', OLD.id, OLD.state, NEW.state
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER publish_step_state BEFORE UPDATE ON listing_publish_job_step
  FOR EACH ROW EXECUTE FUNCTION guard_publish_step_state();

-- (e) 作业的幂等键不可变（重复提交返回同一条，靠它）。
CREATE FUNCTION guard_publish_job_idempotency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key THEN
    RAISE EXCEPTION 'Publish job % idempotency key is immutable', OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER publish_job_idempotency BEFORE UPDATE ON listing_publish_job
  FOR EACH ROW EXECUTE FUNCTION guard_publish_job_idempotency();

-- ===========================================================================
-- 五、多租户隔离：租户业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V171 / V177 / V194 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_publish_job','listing_publish_job_target','listing_publish_job_step'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V171 / V177 / V194 同构：可见性随店铺负责人 / 部门收敛）
-- 作业按**首选店铺**收敛；每个目标按自己的店铺收敛。
CREATE POLICY data_scope ON listing_publish_job AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_publish_job.tenant_id
             AND s.id=listing_publish_job.primary_shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_publish_job.tenant_id
             AND s.id=listing_publish_job.primary_shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON listing_publish_job_target AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_publish_job_target.tenant_id
             AND s.id=listing_publish_job_target.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_publish_job_target.tenant_id
             AND s.id=listing_publish_job_target.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON listing_publish_job_step AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_publish_job_target t
             WHERE t.tenant_id=listing_publish_job_step.tenant_id AND t.id=listing_publish_job_step.target_id
               AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=t.tenant_id AND s.id=t.shop_id
                         AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
         OR EXISTS(SELECT 1 FROM listing_publish_job j
             WHERE j.tenant_id=listing_publish_job_step.tenant_id AND j.id=listing_publish_job_step.job_id
               AND listing_publish_job_step.target_id IS NULL
               AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=j.tenant_id AND s.id=j.primary_shop_id
                         AND channel_scope(channel_operation(),s.owner_id,s.department_id))))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_publish_job_target t
             WHERE t.tenant_id=listing_publish_job_step.tenant_id AND t.id=listing_publish_job_step.target_id
               AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=t.tenant_id AND s.id=t.shop_id
                         AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
         OR EXISTS(SELECT 1 FROM listing_publish_job j
             WHERE j.tenant_id=listing_publish_job_step.tenant_id AND j.id=listing_publish_job_step.job_id
               AND listing_publish_job_step.target_id IS NULL
               AND EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=j.tenant_id AND s.id=j.primary_shop_id
                         AND channel_scope(channel_operation(),s.owner_id,s.department_id))));

-- ===========================================================================
-- 六、账号与授权
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
  listing_publish_job, listing_publish_job_target, listing_publish_job_step TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V195 同口径）。
REVOKE ALL ON
  listing_publish_job, listing_publish_job_target, listing_publish_job_step FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 一键刊登属于「平台操作」语义，
-- 与 4.1.1.1 listing 列表、4.2.1.1 刊登编辑器、4.3.1.2 批量发布同族，
-- 复用 identity 已注册的 channel.operation.*，identity 服务零改动。
-- ---------------------------------------------------------------------------
