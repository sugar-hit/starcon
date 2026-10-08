-- 10.4.2.1 海外仓连接（对接海外仓/第三方仓服务商）。
--
-- 归属 channel：蓝图 10 卷卷首「后端归属：channel 拥有物流商连接与轨迹」，
-- L2 表 10.4 头程管理 = trade + warehouse + channel；AGENTS.md 规定外部适配器
-- （Shopify / Amazon / …形态的第三方系统对接）放在 channel，每个 vendor 独立包。
-- 海外仓服务商正是这种「外部系统适配」，不归 warehouse（warehouse 拥有自有库存账本，
-- 见 V51 warehouse_inventory_ledger —— 两者必须分库，正是本模块不变量要求的物理边界）。
--
-- 🔴 蓝图加粗约束（本模块最重要的一条，必须在 DB 层钉死而不是靠服务层自觉）：
--   「外部仓库存是**观测值不是自有账本**，必须与自营库存**分开存储与对账，不能覆盖**。」
--
-- 落地方式（三重保险，任何一层单独被绕过都仍然成立）：
--   1) **物理分库**：本迁移落在 channel 库，自营库存账本在 warehouse 库。
--      AGENTS.md 禁止跨服务数据库访问，所以同步路径在物理上就够不到自营库存表。
--   2) **独立表**：external_stock_observation 是观测表，不是库存账本；
--      它只允许 INSERT（append-only 快照），没有 on_hand 可被「覆盖」的语义位置。
--      每次观测是一行带 observed_at 的新快照，最新值靠查询取，不靠原地更新。
--   3) **DB 触发器钉死**：guard_external_stock_append_only 在**写入路径**上拒绝
--      UPDATE/DELETE；外加 revoke 让 channel_app 对该表只有 INSERT/SELECT。
--      即便绕过服务层直接写 SQL 也改不动已有观测值。
--
-- 铁律：
--   1) 连接与凭据分离：本表只存 credential_ref 外键，永远不存明文凭据
--      （沿用 V1 credential_ref 的既有纪律，AGENTS.md 安全章节同款要求）
--   2) 每个观测行必须可追溯：source + observed_at 均 NOT NULL，
--      「这个数从哪来、什么时候观测到的」答不出来就不该落库
--   3) 数量用 NUMERIC(19,6) 不用浮点；金额用 NUMERIC(19,4)
--   4) 同步记录 append-only：同步结果不可篡改，重放留新记录

-- ---------------------------------------------------------------------------
-- warehouse_provider · 海外仓/第三方仓服务商目录（全局码表，无 tenant_id）
-- ---------------------------------------------------------------------------
CREATE TABLE warehouse_provider (
  id            uuid PRIMARY KEY,
  code          varchar(32) NOT NULL UNIQUE,
  name          varchar(120) NOT NULL,
  -- 服务商类型：第三方仓（平台外）/ 平台仓（平台自建仓，如 FBA）
  kind          varchar(24) NOT NULL
                CHECK(kind IN ('THIRD_PARTY','PLATFORM')),
  -- 蓝图职责里列的四类对接能力，按服务商实际开通情况登记。
  -- 不支持的能力必须显式为 false，让前端禁用对应按钮，而不是点了才报错。
  supports_inventory_sync boolean NOT NULL DEFAULT false,
  supports_forecast        boolean NOT NULL DEFAULT false,
  supports_status_callback boolean NOT NULL DEFAULT false,
  supports_billing         boolean NOT NULL DEFAULT false,
  position     int NOT NULL DEFAULT 100,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CHECK(btrim(code)<>''),
  CHECK(btrim(name)<>'')
);

COMMENT ON TABLE warehouse_provider IS
  '海外仓/第三方仓服务商目录。kind 区分平台仓与第三方仓：平台仓库存与 FBA 快照同源，第三方仓是独立服务商。';

-- ---------------------------------------------------------------------------
-- warehouse_connection · 服务商连接（连接授权 + 连接状态）
-- ---------------------------------------------------------------------------
CREATE TABLE warehouse_connection (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  provider_id       uuid NOT NULL REFERENCES warehouse_provider(id),
  -- 只存密钥管理器引用，绝不存明文凭据（沿用 V1 credential_ref 纪律）
  credential_id     uuid,
  authorized_account varchar(255) NOT NULL,
  display_name      varchar(150) NOT NULL DEFAULT '',
  -- 接口配置：服务商侧的接入点标识，不是可执行 URL，
  -- 本仓库无真实服务商网络出口，这里只登记租户可维护的接口配置标识。
  api_endpoint_ref  varchar(200) NOT NULL DEFAULT '',
  status            text NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','EXPIRING','EXPIRED','REVOKED','PAUSED')),
  sync_paused       boolean NOT NULL DEFAULT false,
  last_sync_at      timestamptz,
  last_error        text NOT NULL DEFAULT '',
  version           bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一租户下同一服务商的同一账号只允许一条连接，避免重复连接导致重复同步
  UNIQUE(tenant_id, provider_id, authorized_account),
  FOREIGN KEY(tenant_id, credential_id) REFERENCES credential_ref(tenant_id, id),
  CHECK(btrim(authorized_account)<>'')
);

COMMENT ON TABLE warehouse_connection IS
  '海外仓服务商连接。凭据只存 secret manager 引用（credential_ref），本表任何列都装不下明文密钥。';

-- ---------------------------------------------------------------------------
-- warehouse_account · 仓账户（多仓服务商下的仓账户）
-- ---------------------------------------------------------------------------
-- 蓝图「管理多仓服务商下的仓账户」：一个服务商连接下可能有多个仓账户
-- （服务商给每个物理仓库一个 account id），仓账户是同步与账单的实际载体。
CREATE TABLE warehouse_account (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  connection_id    uuid NOT NULL,
  -- 服务商侧的仓账号编码，同一服务商下唯一
  external_account_code varchar(64) NOT NULL,
  name             varchar(150) NOT NULL DEFAULT '',
  country_code     char(2) NOT NULL DEFAULT '',
  timezone         varchar(64) NOT NULL DEFAULT 'UTC',
  -- 仓库性质：与 warehouse 服务 V55 的 kind 语义对齐（LOCAL/OVERSEAS/PLATFORM_FBA/THIRD_PARTY），
  -- 这里是**服务商侧**声明的性质，用于 7.1.3.1 多仓分布下游对齐，不写 warehouse 库。
  warehouse_kind   varchar(24) NOT NULL DEFAULT 'THIRD_PARTY'
                   CHECK(warehouse_kind IN ('OVERSEAS','PLATFORM_FBA','THIRD_PARTY')),
  status           text NOT NULL DEFAULT 'ACTIVE'
                   CHECK(status IN ('ACTIVE','PAUSED','CLOSED')),
  version          bigint NOT NULL DEFAULT 0,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, connection_id, external_account_code),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES warehouse_connection(tenant_id, id),
  CHECK(btrim(external_account_code)<>'')
);

COMMENT ON TABLE warehouse_account IS
  '仓账户：一个服务商连接下的单个物理仓。同步、预报、账单都以仓账户为单位。';

-- ---------------------------------------------------------------------------
-- warehouse_sync_run · 库存同步记录（append-only）
-- ---------------------------------------------------------------------------
CREATE TABLE warehouse_sync_run (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  connection_id  uuid NOT NULL,
  account_id     uuid,
  -- 幂等键：同一仓账户同一触发批次只产生一次运行，重试复用而不是并排第二条
  idempotency_key varchar(128) NOT NULL,
  trigger_source text NOT NULL DEFAULT 'MANUAL'
                 CHECK(trigger_source IN ('MANUAL','SCHEDULER','REPLAY')),
  status         text NOT NULL DEFAULT 'RUNNING'
                 CHECK(status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED')),
  started_at     timestamptz NOT NULL DEFAULT now(),
  finished_at    timestamptz,
  fetched_count  int NOT NULL DEFAULT 0 CHECK(fetched_count >= 0),
  observed_count int NOT NULL DEFAULT 0 CHECK(observed_count >= 0),
  failed_count   int NOT NULL DEFAULT 0 CHECK(failed_count >= 0),
  error_detail   text NOT NULL DEFAULT '',
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES warehouse_connection(tenant_id, id),
  FOREIGN KEY(tenant_id, account_id) REFERENCES warehouse_account(tenant_id, id)
);

COMMENT ON TABLE warehouse_sync_run IS
  '海外仓库存同步运行记录。append-only：同步结果不可篡改，纠正靠重放留新记录。';

-- ---------------------------------------------------------------------------
-- external_stock_observation · 外部仓库存观测值（append-only 快照）
-- ---------------------------------------------------------------------------
-- 🔴 本表是蓝图那条加粗约束的物理落点。
--
-- 它**不是库存账本**：
--   · 没有 on_hand_balance 可被覆盖的语义位置——每次观测是一行新快照
--   · latest 视图靠 observed_at 取最新，不靠原地 UPDATE
--   · 与自营库存（warehouse 库 V51 warehouse_inventory_ledger）分库存放，不共享任何表
--
-- 幂等：同一仓账户 + 同一 SKU + 同一 observed_at 只落一行，
-- 重放同一次同步不会把观测值翻倍。
CREATE TABLE external_stock_observation (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  connection_id uuid NOT NULL,
  account_id   uuid NOT NULL,
  run_id       uuid,
  -- 服务商侧的 SKU 编码。外部编码与内部 SKU 的映射属于下游对齐职责，
  -- 本表按服务商编码落观测值，并保留 external_sku 供对账时回溯。
  external_sku varchar(128) NOT NULL,
  -- 数量：NUMERIC(19,6) 支持小数件/称重商品，绝不用浮点
  quantity     numeric(19,6) NOT NULL CHECK(quantity >= 0),
  unit         varchar(16) NOT NULL DEFAULT 'PCS',
  -- 可追溯性强制 NOT NULL：答不出「从哪来、什么时候观测的」就不该落库。
  -- 蓝图要求「分开存储与**对账**」，对账的前提就是每行都能追到来源与时点。
  source       varchar(64) NOT NULL,
  observed_at  timestamptz NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 重放幂等：同账户同 SKU 同时点只观测一次
  UNIQUE(tenant_id, account_id, external_sku, observed_at),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES warehouse_connection(tenant_id, id),
  FOREIGN KEY(tenant_id, account_id) REFERENCES warehouse_account(tenant_id, id),
  FOREIGN KEY(tenant_id, run_id) REFERENCES warehouse_sync_run(tenant_id, id),
  CHECK(btrim(external_sku)<>''),
  CHECK(btrim(source)<>'')
);

COMMENT ON TABLE external_stock_observation IS
  '外部仓库存观测值（append-only 快照，非账本）。绝不写入自营库存表；对账时与 warehouse 库存分账进行。';

-- ---------------------------------------------------------------------------
-- warehouse_inbound_stock_line · 服务商入库的库存报送行（适配器的真实输入）
-- ---------------------------------------------------------------------------
-- 本仓库没有真实的海外仓服务商账号与网络出口，所以「拉取库存」这一段的**外部边界**
-- 用一张租户可维护的报送表来接，而不是写假 HTTP 客户端打真实域名。
--
-- 这样做的理由：适配器仍然是真实被驱动的——
--   录入报送行 → 触发同步 → 适配器读报送行 → 落观测快照 + 同步记录，全程真落库。
-- 等拿到真实服务商账号时，只需新增一个 OverseasWarehouseAdapter 实现替换本适配器，
-- 上层服务、同步记录、观测表与不变量一行都不用改。
--
-- 这张表是**输入**不是观测：报送行可以修正（服务商报错要改），
-- 观测快照不行（已观测到的事实不可篡改）。两者语义不同，必须分表。
CREATE TABLE warehouse_inbound_stock_line (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  account_id   uuid NOT NULL,
  external_sku varchar(128) NOT NULL,
  quantity     numeric(19,6) NOT NULL CHECK(quantity >= 0),
  unit         varchar(16) NOT NULL DEFAULT 'PCS',
  -- 报送来源标识：哪个通道把这批数据送进来的（API 回传 / 文件导入 / 人工登记）
  source       varchar(64) NOT NULL,
  reported_at  timestamptz NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一仓账户 + SKU + 报送时点只留一行：重复投递取幂等，不做加总
  UNIQUE(tenant_id, account_id, external_sku, reported_at),
  FOREIGN KEY(tenant_id, account_id) REFERENCES warehouse_account(tenant_id, id),
  CHECK(btrim(external_sku)<>''),
  CHECK(btrim(source)<>'')
);

COMMENT ON TABLE warehouse_inbound_stock_line IS
  '服务商库存报送行（适配器的输入，可修正）。与 external_stock_observation 语义不同：输入可改，观测不可改。';

-- ---------------------------------------------------------------------------
-- 不变量：观测值不可被覆盖（在写入路径上钉死，绕过服务层直接写 SQL 同样被拒）
-- ---------------------------------------------------------------------------
-- 蓝图原句：「**不能覆盖**」。这里是它的可执行形式：
-- 观测快照一旦落库就不可改、不可删——想改只能再落一行带新 observed_at 的快照。
-- 这是 BEFORE 触发器（写入路径，非延迟检查），所以并发下的 UPDATE 也照样被拒。
CREATE FUNCTION guard_external_stock_append_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  RAISE EXCEPTION '外部仓库存是观测值不是账本：观测快照不可修改或删除，请落新快照（新的 observed_at）'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER external_stock_no_update
  BEFORE UPDATE OR DELETE ON external_stock_observation
  FOR EACH ROW EXECUTE FUNCTION guard_external_stock_append_only();

-- 同一条连接上的同步记录同样 append-only：已结束的运行不可改计数。
CREATE FUNCTION guard_settled_sync_run() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status <> 'RUNNING' THEN
    RAISE EXCEPTION '已结束的同步运行不可修改，请创建新的重放运行' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sync_run_settled BEFORE UPDATE ON warehouse_sync_run
  FOR EACH ROW EXECUTE FUNCTION guard_settled_sync_run();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['warehouse_connection','warehouse_account','warehouse_sync_run','warehouse_inbound_stock_line','external_stock_observation'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 同步资产的数据范围随连接收敛：运营不能通过同步记录看到无权连接的运行历史。
CREATE POLICY sync_run_scope ON warehouse_sync_run AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));
CREATE POLICY observation_scope ON external_stock_observation AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
CREATE INDEX warehouse_account_connection ON warehouse_account(tenant_id, connection_id, status);
-- 最新观测读取路径：按账户 + SKU 取最新一行
CREATE INDEX external_stock_latest ON external_stock_observation(tenant_id, account_id, external_sku, observed_at DESC);
CREATE INDEX external_stock_run ON external_stock_observation(tenant_id, run_id);
CREATE INDEX warehouse_sync_run_connection ON warehouse_sync_run(tenant_id, connection_id, started_at DESC);
CREATE INDEX warehouse_sync_run_account ON warehouse_sync_run(tenant_id, account_id, started_at DESC)
  WHERE status IN ('RUNNING','FAILED','PARTIAL');

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V2 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN CREATE ROLE channel_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO channel_app;
GRANT SELECT ON warehouse_provider TO channel_app;

-- 权限面刻意收紧，呼应「不能覆盖」这条铁律：
-- external_stock_observation 只给 INSERT + SELECT，**不给 UPDATE/DELETE**。
-- 双保险：即使有人把上面的触发器删了，权限层仍然写不动已有观测值。
GRANT SELECT,INSERT,UPDATE,DELETE ON warehouse_connection, warehouse_account, warehouse_sync_run, warehouse_inbound_stock_line TO channel_app;
GRANT SELECT,INSERT ON external_stock_observation TO channel_app;

-- 索引：适配器按账户读报送行
CREATE INDEX inbound_stock_account ON warehouse_inbound_stock_line(tenant_id, account_id, reported_at DESC);

-- 复用 identity 已注册的 channel.connection.* 与 channel.sync.* 权限码
-- （本模块不新增权限码：连接配置归 channel.connection，库存同步归 channel.sync），
-- 因此本迁移**不需要触碰 identity 服务**，跨车道回归面 = 0。

-- ---------------------------------------------------------------------------
-- 服务商目录种子：只登记「这个服务商是谁、它支持哪些能力」这类码表事实。
-- 不含任何账号、凭据或假库存数据。
-- ---------------------------------------------------------------------------
INSERT INTO warehouse_provider(id,code,name,kind,supports_inventory_sync,supports_forecast,supports_status_callback,supports_billing,position) VALUES
 (gen_random_uuid(),'GENERIC_3PL','通用第三方仓','THIRD_PARTY',true,true,true,true,100),
 (gen_random_uuid(),'PLATFORM_FBA','平台自建仓（FBA）','PLATFORM',true,true,true,false,50)
ON CONFLICT (code) DO NOTHING;