-- 10.4.2.2 备货预报（向海外仓 / 平台仓提交备货计划 + 接收仓端回执）。
--
-- 归属 channel：蓝图 10 卷卷首「后端归属：channel 拥有物流商连接与轨迹」，
-- L2 表 10.4 头程管理 = trade + warehouse + channel；AGENTS.md 规定外部适配器
-- （Shopify / Amazon / …形态的第三方系统对接）放在 channel，每个 vendor 独立包。
-- 同 L3 的 10.4.2.1 海外仓连接已落在 channel（V93），**目的仓就是 warehouse_account**，
-- 必须同服务同库才不违反「禁止跨服务数据库访问」——跨库 FK / 视图 / 查表一律禁止。
--
-- 🔴 蓝图 10.4.2.2 的加粗句（本迁移最重要的一条）：
--   「**平台仓备货有严格规格**（箱型、贴标、托盘、混装限制），规格不符会被拒收造成额外成本与延误。」
--
-- 落地方式（规格必须**结构化**，不能只写在注释里）：
--   1) **规格是可维护的数据**（warehouse_forecast_spec，按目的仓 + 版本号），
--      四类限制各有自己的列：箱型（allowed_box_types / max_units_per_carton /
--      max_weight_per_carton_kg / max_skus_per_carton）、贴标（requires_fnsku_label /
--      requires_carton_label / allowed_label_formats）、托盘（pallet_required /
--      max_cartons_per_pallet / max_pallet_weight_kg）、混装（mixed_loading_allowed）。
--   2) **提交前必须过规格校验**：预报单在 VALIDATED 之前不能提交，校验不过
--      逐条指出违反哪一条（服务层返回结构化违规清单，DB 层另有状态机触发器钉住）。
--   3) **规格版本在预报单上冻结**（warehouse_forecast.spec_id）：预报单一旦建，
--      用的是哪一版规格就固定下来，后来改规格不影响已提交的预报单 ——
--      否则「按当时的规定验的」这句话没法追溯，拒收纠纷就没有依据。
--
-- 🔴 另一条不变量：「外部仓库存是观测值不是自有账本」由 10.4.2.1 拥有。
--   本迁移**只读** warehouse_account（目的仓），**不写** external_stock_observation，
--   **不 ALTER** 10.4.2.1 的任何表 —— 跨车道 DDL 回归面 = 0。
--
-- 铁律：
--   1) 数量用 NUMERIC(19,6) 不用浮点（支持小数件 / 称重商品）
--   2) 时间用 timestamptz，Java 侧一律 OffsetDateTime + `?::timestamptz` 绑定
--   3) 仓端回执 append-only：回执是**事实**，不可改不可删
--   4) 预报单状态机在 DB 层钉死：绕过服务层写 SQL 也跳不过状态转换规则
--
-- ---------------------------------------------------------------------------
-- warehouse_forecast_spec · 目的仓的备货规格（版本化）
-- ---------------------------------------------------------------------------
-- 一个目的仓（warehouse_account）同时只有**一版**生效规格（partial unique index）。
-- 改规格 = 建新版本 + 停用旧版本（spec_id 冻结在预报单上，历史不被改写）。
CREATE TABLE warehouse_forecast_spec (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  account_id   uuid NOT NULL,
  -- 版本号：同一目的仓内递增，便于说清「按第几版规格备的货」
  version_no   int NOT NULL CHECK(version_no >= 1),
  status       text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  -- ---- 箱型限制 ----
  -- 允许的箱型编码集合（空数组 = 不限制箱型，但仍受下面的数量/重量上限约束）
  allowed_box_types text[] NOT NULL DEFAULT '{}',
  max_units_per_carton int CHECK(max_units_per_carton IS NULL OR max_units_per_carton > 0),
  max_weight_per_carton_kg numeric(19,6) CHECK(max_weight_per_carton_kg IS NULL OR max_weight_per_carton_kg > 0),
  -- 同一箱型下允许出现的 SKU 数上限（混装口径：几个 SKU 共用一种箱型）
  max_skus_per_carton int CHECK(max_skus_per_carton IS NULL OR max_skus_per_carton > 0),
  -- ---- 贴标限制 ----
  requires_fnsku_label boolean NOT NULL DEFAULT false,
  requires_carton_label boolean NOT NULL DEFAULT false,
  -- 允许的贴标格式（空数组 = 只要填了贴标格式就算过）
  allowed_label_formats text[] NOT NULL DEFAULT '{}',
  -- ---- 托盘限制 ----
  pallet_required boolean NOT NULL DEFAULT false,
  max_cartons_per_pallet int CHECK(max_cartons_per_pallet IS NULL OR max_cartons_per_pallet > 0),
  max_pallet_weight_kg numeric(19,6) CHECK(max_pallet_weight_kg IS NULL OR max_pallet_weight_kg > 0),
  -- ---- 混装限制 ----
  -- false = 同一箱型里不许出现多个 SKU（平台仓最常见的拒收原因）
  mixed_loading_allowed boolean NOT NULL DEFAULT true,
  -- ---- 时效要求 ----
  -- 期望到货时间至少要比现在晚这么多天（仓端截单/备货周期）
  min_arrival_lead_days int NOT NULL DEFAULT 0 CHECK(min_arrival_lead_days >= 0),
  note         text NOT NULL DEFAULT '',
  created_at   timestamptz NOT NULL DEFAULT now(),
  created_by_membership_id uuid,
  retired_at   timestamptz,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, account_id, version_no),
  FOREIGN KEY(tenant_id, account_id) REFERENCES warehouse_account(tenant_id, id)
);

COMMENT ON TABLE warehouse_forecast_spec IS
  '目的仓备货规格（版本化）。箱型/贴标/托盘/混装四类限制的结构化存放；同一目的仓同时只有一版 ACTIVE。';

-- 同一目的仓同时只能有一版生效规格：换规格 = 建新版 + 停用旧版，不是就地改。
CREATE UNIQUE INDEX warehouse_forecast_spec_active
  ON warehouse_forecast_spec(tenant_id, account_id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- warehouse_forecast · 预报单（单头）
-- ---------------------------------------------------------------------------
CREATE TABLE warehouse_forecast (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  forecast_no   varchar(40) NOT NULL,
  -- 目的仓：仓账户（10.4.2.1 的 warehouse_account，同库同服务，外键合法）
  account_id    uuid NOT NULL,
  -- 建单时冻结的规格版本：改规格不影响已建预报单
  spec_id       uuid NOT NULL,
  -- 输入来源：6.2.1.2 补货建议 / FBA 发货计划 / 人工登记
  source        text NOT NULL DEFAULT 'MANUAL'
                CHECK(source IN ('REPLENISHMENT_SUGGESTION','FBA_SHIPMENT_PLAN','MANUAL')),
  status        text NOT NULL DEFAULT 'DRAFT'
                CHECK(status IN ('DRAFT','VALIDATED','SUBMITTED','SHIPMENT_CREATED','ACCEPTED','REJECTED','CANCELLED')),
  -- 期望到货时间（timestamptz）
  expected_arrival_at timestamptz NOT NULL,
  -- 平台货件编号：由仓端/平台侧回报或租户登记写入（见 warehouse_shipment_report）
  platform_shipment_id varchar(64) NOT NULL DEFAULT '',
  validated_at  timestamptz,
  submitted_at  timestamptz,
  closed_at     timestamptz,
  last_error    text NOT NULL DEFAULT '',
  note          text NOT NULL DEFAULT '',
  version       bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, forecast_no),
  FOREIGN KEY(tenant_id, account_id) REFERENCES warehouse_account(tenant_id, id),
  FOREIGN KEY(tenant_id, spec_id) REFERENCES warehouse_forecast_spec(tenant_id, id)
);

COMMENT ON TABLE warehouse_forecast IS
  '备货预报单。spec_id 冻结建单时的规格版本；platform_shipment_id 由仓端/平台侧回报或租户登记写入。';

CREATE SEQUENCE stock_forecast_no_seq START 1;

-- ---------------------------------------------------------------------------
-- warehouse_forecast_line · 预报明细（SKU + 数量 + 箱型/贴标/托盘声明）
-- ---------------------------------------------------------------------------
CREATE TABLE warehouse_forecast_line (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  forecast_id uuid NOT NULL,
  sku_code    varchar(128) NOT NULL,
  quantity    numeric(19,6) NOT NULL CHECK(quantity > 0),
  unit        varchar(16) NOT NULL DEFAULT 'PCS',
  -- 箱型（规格里允许的箱型之一）
  box_type    varchar(32) NOT NULL DEFAULT '',
  -- 箱数（混装/单箱件数/箱重上限都要靠它换算）
  carton_count int CHECK(carton_count IS NULL OR carton_count > 0),
  -- 托盘数（规格要求托盘时必须给出）
  pallet_count int CHECK(pallet_count IS NULL OR pallet_count > 0),
  -- 贴标格式（如 FNSKU / FBA 标签类型）
  label_format varchar(32) NOT NULL DEFAULT '',
  -- 箱唛 / 箱标签编号（规格要求箱标时必须给出）
  carton_label_ref varchar(64) NOT NULL DEFAULT '',
  -- 单件重量（kg）：规格有重量上限时才需要，缺了就报「未提供单重」而不是按 0 算
  unit_weight_kg numeric(19,6) CHECK(unit_weight_kg IS NULL OR unit_weight_kg > 0),
  note        text NOT NULL DEFAULT '',
  position    int NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, forecast_id) REFERENCES warehouse_forecast(tenant_id, id),
  CHECK(btrim(sku_code)<>'')
);

COMMENT ON TABLE warehouse_forecast_line IS
  '预报明细行：SKU 与数量，外加箱型/箱数/托盘/贴标声明——规格校验逐项比对这些声明。';

-- ---------------------------------------------------------------------------
-- warehouse_shipment_task · 平台货件创建任务
-- ---------------------------------------------------------------------------
-- 本仓库没有真实的平台账号与网络出口 ⇒ 任务**不**打真实平台域名，
-- 也**不**放假 HTTP 客户端。任务落自己的表，状态可推进、可查、可重试；
-- 平台货件编号由仓端/平台侧回报或租户登记（warehouse_shipment_report）带进来。
CREATE TABLE warehouse_shipment_task (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  forecast_id uuid NOT NULL,
  account_id  uuid NOT NULL,
  provider_code varchar(32) NOT NULL DEFAULT '',
  task_no     varchar(40) NOT NULL,
  status      text NOT NULL DEFAULT 'PENDING'
              CHECK(status IN ('PENDING','SUBMITTED','SUCCEEDED','FAILED','CANCELLED')),
  attempts    int NOT NULL DEFAULT 0 CHECK(attempts >= 0),
  platform_shipment_id varchar(64) NOT NULL DEFAULT '',
  last_error  text NOT NULL DEFAULT '',
  acknowledged_at timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一张预报单一张货件任务：重试复用同一行（attempts 累加），不并排第二条
  UNIQUE(tenant_id, forecast_id),
  UNIQUE(tenant_id, task_no),
  FOREIGN KEY(tenant_id, forecast_id) REFERENCES warehouse_forecast(tenant_id, id),
  FOREIGN KEY(tenant_id, account_id) REFERENCES warehouse_account(tenant_id, id)
);

COMMENT ON TABLE warehouse_shipment_task IS
  '平台货件创建任务。PENDING→SUBMITTED→SUCCEEDED/FAILED；FAILED 可重试（attempts 累加），成功才回填 platform_shipment_id。';

CREATE SEQUENCE stock_shipment_task_no_seq START 1;

-- ---------------------------------------------------------------------------
-- warehouse_shipment_report · 平台货件报送行（货件任务的真实输入）
-- ---------------------------------------------------------------------------
-- 与 10.4.2.1 的 warehouse_inbound_stock_line 同一形状：**外部边界用租户/通道
-- 可维护的报送表承接**，而不是写假客户端打真实域名。
--   · 登记方：平台回调（PLATFORM_CALLBACK）或人工登记（MANUAL_REGISTRATION）
--   · 语义：这一批预报**已经**以某个平台货件编号推给了平台仓
-- 适配器读这张表产出 ShipmentAck（带 platform_shipment_id），
-- 将来接真实平台 API 时只需新增一个 OverseasWarehouseAdapter 实现，上层一行不改。
CREATE TABLE warehouse_shipment_report (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  account_id  uuid NOT NULL,
  forecast_id uuid NOT NULL,
  external_shipment_ref varchar(64) NOT NULL,
  source      text NOT NULL DEFAULT 'MANUAL_REGISTRATION'
              CHECK(source IN ('PLATFORM_CALLBACK','MANUAL_REGISTRATION')),
  reported_at timestamptz NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一张预报单 + 同一个货件编号只留一行：重复投递取幂等
  UNIQUE(tenant_id, forecast_id, external_shipment_ref),
  FOREIGN KEY(tenant_id, account_id) REFERENCES warehouse_account(tenant_id, id),
  FOREIGN KEY(tenant_id, forecast_id) REFERENCES warehouse_forecast(tenant_id, id),
  CHECK(btrim(external_shipment_ref)<>'')
);

COMMENT ON TABLE warehouse_shipment_report IS
  '平台货件报送行（货件任务的真实输入）。平台货件编号由平台回调或人工登记带进来，不是本服务凭空生成的。';

-- ---------------------------------------------------------------------------
-- warehouse_forecast_receipt · 仓端回执（接收 / 拒收）
-- ---------------------------------------------------------------------------
-- 蓝图：「接收仓端接收与拒收反馈」。**拒收是真实业务结果**，必须能落库并可查：
-- 规格不符被拒收会造成额外成本与延误，这条链路不能因为「难看」就不留痕。
-- append-only：回执是已经发生的事实，纠正靠再登记一条，不靠改历史。
CREATE TABLE warehouse_forecast_receipt (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  forecast_id uuid NOT NULL,
  receipt_type text NOT NULL CHECK(receipt_type IN ('ACCEPTED','REJECTED')),
  receipt_ref varchar(64) NOT NULL DEFAULT '',
  detail      text NOT NULL DEFAULT '',
  -- 拒收理由（只有 REJECTED 才有意义）
  rejected_reason text NOT NULL DEFAULT '',
  received_at timestamptz NOT NULL,
  source      text NOT NULL DEFAULT 'MANUAL_REGISTRATION'
              CHECK(source IN ('WAREHOUSE_FEEDBACK','MANUAL_REGISTRATION')),
  recorded_by_membership_id uuid,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, forecast_id) REFERENCES warehouse_forecast(tenant_id, id),
  -- 拒收必须写理由：没有理由的拒收记录等于没有信息
  CHECK(receipt_type <> 'REJECTED' OR btrim(rejected_reason) <> '')
);

COMMENT ON TABLE warehouse_forecast_receipt IS
  '仓端回执（接收 / 拒收）。append-only：事实不可改，纠正靠再登记一条。';

-- ---------------------------------------------------------------------------
-- 不变量 1：仓端回执 append-only（DB 触发器 + 权限面各挡一层）
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_forecast_receipt_append_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  RAISE EXCEPTION '仓端回执是已发生的事实，不可修改或删除；纠正请另登记一条'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER forecast_receipt_no_update
  BEFORE UPDATE OR DELETE ON warehouse_forecast_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_forecast_receipt_append_only();

-- ---------------------------------------------------------------------------
-- 不变量 2：预报单状态机（DB 层钉死，绕过服务层写 SQL 也跳不过去）
-- ---------------------------------------------------------------------------
-- DRAFT → VALIDATED → SUBMITTED → SHIPMENT_CREATED → ACCEPTED / REJECTED
--   DRAFT / VALIDATED / SUBMITTED → CANCELLED（作废）
-- 其余一律拒绝。状态不变的自更新（改备注、改版本号）放行。
CREATE FUNCTION guard_forecast_status_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;
  allowed := CASE OLD.status
    WHEN 'DRAFT' THEN NEW.status IN ('VALIDATED','CANCELLED')
    WHEN 'VALIDATED' THEN NEW.status IN ('SUBMITTED','CANCELLED')
    WHEN 'SUBMITTED' THEN NEW.status IN ('SHIPMENT_CREATED','CANCELLED')
    WHEN 'SHIPMENT_CREATED' THEN NEW.status IN ('ACCEPTED','REJECTED')
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '预报单状态不能从 % 变到 %（合法流转：DRAFT→VALIDATED→SUBMITTED→SHIPMENT_CREATED→ACCEPTED/REJECTED，DRAFT/VALIDATED/SUBMITTED→CANCELLED）',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER forecast_status_transition
  BEFORE UPDATE ON warehouse_forecast
  FOR EACH ROW EXECUTE FUNCTION guard_forecast_status_transition();

-- ---------------------------------------------------------------------------
-- 不变量 3：明细只在草稿态可改
-- ---------------------------------------------------------------------------
-- 预报单一旦离开 DRAFT，明细就是「已提交给平台仓的承诺」，不能再改。
-- 改内容 = 作废后另建一张单，留下可追溯的作废痕迹。
CREATE FUNCTION guard_forecast_line_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st text;
BEGIN
  SELECT f.status INTO st FROM warehouse_forecast f
   WHERE f.tenant_id = OLD.tenant_id AND f.id = OLD.forecast_id;
  IF st IS NOT NULL AND st <> 'DRAFT' THEN
    RAISE EXCEPTION '预报单已离开草稿态，明细不可修改（请作废后另建一张）' USING ERRCODE='23514';
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$;

CREATE TRIGGER forecast_line_frozen
  BEFORE UPDATE OR DELETE ON warehouse_forecast_line
  FOR EACH ROW EXECUTE FUNCTION guard_forecast_line_frozen();

-- ---------------------------------------------------------------------------
-- 不变量 4：货件任务状态机
-- ---------------------------------------------------------------------------
-- PENDING → SUBMITTED → SUCCEEDED / FAILED；FAILED → PENDING（重试）；CANCELLED 终态。
CREATE FUNCTION guard_shipment_task_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowed boolean := false;
BEGIN
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;
  allowed := CASE OLD.status
    WHEN 'PENDING' THEN NEW.status IN ('SUBMITTED','FAILED','CANCELLED')
    WHEN 'SUBMITTED' THEN NEW.status IN ('SUCCEEDED','FAILED')
    WHEN 'FAILED' THEN NEW.status IN ('PENDING','CANCELLED')
    ELSE false
  END;
  IF NOT allowed THEN
    RAISE EXCEPTION '货件任务状态不能从 % 变到 %（合法流转：PENDING→SUBMITTED→SUCCEEDED/FAILED，FAILED→PENDING 重试）',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER shipment_task_transition
  BEFORE UPDATE ON warehouse_shipment_task
  FOR EACH ROW EXECUTE FUNCTION guard_shipment_task_transition();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['warehouse_forecast_spec','warehouse_forecast','warehouse_forecast_line','warehouse_shipment_task','warehouse_shipment_report','warehouse_forecast_receipt'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
CREATE INDEX warehouse_forecast_spec_account ON warehouse_forecast_spec(tenant_id, account_id, version_no DESC);
CREATE INDEX warehouse_forecast_list ON warehouse_forecast(tenant_id, status, expected_arrival_at DESC, id);
CREATE INDEX warehouse_forecast_account ON warehouse_forecast(tenant_id, account_id, created_at DESC);
CREATE INDEX warehouse_forecast_line_forecast ON warehouse_forecast_line(tenant_id, forecast_id, position, id);
CREATE INDEX warehouse_forecast_line_sku ON warehouse_forecast_line(tenant_id, sku_code);
CREATE INDEX warehouse_shipment_task_status ON warehouse_shipment_task(tenant_id, status, updated_at DESC);
CREATE INDEX warehouse_shipment_report_forecast ON warehouse_shipment_report(tenant_id, forecast_id, reported_at DESC);
CREATE INDEX warehouse_forecast_receipt_forecast ON warehouse_forecast_receipt(tenant_id, forecast_id, received_at DESC);

-- ---------------------------------------------------------------------------
-- 账号与授权
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN CREATE ROLE channel_app; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO channel_app;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  warehouse_forecast_spec, warehouse_forecast, warehouse_forecast_line,
  warehouse_shipment_task, warehouse_shipment_report TO channel_app;

-- 🔴 仓端回执只给 SELECT,INSERT，**刻意不给 UPDATE/DELETE**：
-- 回执是已发生的事实，双保险（权限面 + 上面的触发器）使绕过服务层也改不动。
GRANT SELECT,INSERT ON warehouse_forecast_receipt TO channel_app;
REVOKE UPDATE,DELETE ON warehouse_forecast_receipt FROM channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 同口径）。
REVOKE ALL ON
  warehouse_forecast_spec, warehouse_forecast, warehouse_forecast_line,
  warehouse_shipment_task, warehouse_shipment_report, warehouse_forecast_receipt FROM starcon_app;

-- 单号发号器必须显式授权：GRANT ... ON ALL TABLES 只覆盖表，不覆盖序列。
GRANT USAGE, SELECT ON SEQUENCE stock_forecast_no_seq, stock_shipment_task_no_seq TO channel_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：备货预报属于「平台侧操作」，复用 identity V4 已有的
-- channel.operation.{read,create,update,delete}，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
--
-- 10.4.2.1 的 warehouse_provider 目录**不新增行**：PLATFORM_FBA（平台自建仓 FBA）
-- 在 V93 的种子里已经存在，且 supports_forecast / supports_status_callback 都是 true ——
-- 平台仓正是本模块「严格规格」那条加粗句说的对象。
-- 本迁移**不 ALTER 10.4.2.1 的任何表**（跨车道 DDL 回归面 = 0）。
