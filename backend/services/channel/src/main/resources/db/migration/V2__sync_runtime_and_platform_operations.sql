-- 02.4 同步运行 与 02.5 平台回写。
--
-- 铁律（蓝图 §2.4.1.3 / §2.5.1.1）：
--   1) sync_cursor **只在处理提交后推进**——回退重拉必须幂等，不允许产生重复订单
--   2) 同步任务粒度是（连接, 站点, 资源类型），不允许整店一把梭，否则部分失败无法续传
--   3) 每个回写任务必须带业务幂等键，避免重试造成平台侧重复上架或重复扣减
--   4) 平台返回**未知结果**（超时）时先查证再重试，禁止当作失败

-- ---------------------------------------------------------------------------
-- sync_cursor · 断点续传
-- ---------------------------------------------------------------------------
CREATE TABLE sync_cursor (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  connection_id   uuid NOT NULL,
  marketplace_id  uuid,
  resource_type   varchar(32) NOT NULL
                  CHECK(resource_type IN ('ORDER','LISTING','INVENTORY','SETTLEMENT','PRODUCT')),
  partition_key   varchar(64) NOT NULL DEFAULT '',
  -- 游标值：外部系统的增量位置（时间戳或 id），只在处理提交后推进
  cursor_value    varchar(128) NOT NULL DEFAULT '',
  last_run_id     uuid,
  last_success_at timestamptz,
  last_failure_at timestamptz,
  consecutive_failures int NOT NULL DEFAULT 0 CHECK(consecutive_failures >= 0),
  status          text NOT NULL DEFAULT 'ACTIVE'
                  CHECK(status IN ('ACTIVE','PAUSED','BROKEN')),
  version         bigint NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 任务粒度 = (连接, 站点, 资源类型, 分区)。站点为 NULL 表示该连接下所有站点的默认游标。
  -- 必须用 NULLS NOT DISTINCT：PG 默认认为 NULL 与 NULL 互不相等，
  -- 否则"连接级默认游标"会被插出多行，续传点分裂。
  UNIQUE NULLS NOT DISTINCT (tenant_id, connection_id, marketplace_id, resource_type, partition_key),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES channel_connection(tenant_id, id),
  FOREIGN KEY(marketplace_id) REFERENCES marketplace(id)
);

-- ---------------------------------------------------------------------------
-- sync_run · 单次同步运行
-- ---------------------------------------------------------------------------
CREATE TABLE sync_run (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  cursor_id      uuid NOT NULL,
  trigger_source text NOT NULL DEFAULT 'SCHEDULER'
                 CHECK(trigger_source IN ('SCHEDULER','MANUAL','REPLAY','BACKFILL')),
  status         text NOT NULL DEFAULT 'RUNNING'
                 CHECK(status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELLED')),
  started_at     timestamptz NOT NULL DEFAULT now(),
  finished_at    timestamptz,
  fetched_count  int NOT NULL DEFAULT 0 CHECK(fetched_count >= 0),
  written_count  int NOT NULL DEFAULT 0 CHECK(written_count >= 0),
  skipped_count  int NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),
  failed_count   int NOT NULL DEFAULT 0 CHECK(failed_count >= 0),
  -- 本次运行推进到的游标值。只有 status='SUCCEEDED' 时才会被写回 sync_cursor
  cursor_after   varchar(128) NOT NULL DEFAULT '',
  duration_ms    int,
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, cursor_id) REFERENCES sync_cursor(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- sync_failure · 失败队列
-- ---------------------------------------------------------------------------
CREATE TABLE sync_failure (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  run_id         uuid NOT NULL,
  cursor_id      uuid NOT NULL,
  -- 外部系统的主键，重放时用它去重：同一条外部记录不会被处理第二次
  external_key   varchar(200) NOT NULL,
  resource_type  varchar(32) NOT NULL,
  -- 失败原因分类，蓝图 2.4.2.1 要求按此聚类。
  -- AUTHORIZATION_EXPIRED 会自动升级为工作台待办，因为它导致持续漏单。
  reason         text NOT NULL
                 CHECK(reason IN ('AUTHORIZATION_EXPIRED','FIELD_PARSE_FAILED','MAPPING_MISSING',
                                  'RATE_LIMITED','NETWORK_ERROR','PLATFORM_ERROR')),
  detail         text NOT NULL DEFAULT '',
  -- 已脱敏的原始载荷，绝不存明文凭据或完整客户地址
  payload_digest varchar(64) NOT NULL DEFAULT '',
  attempts       int NOT NULL DEFAULT 0 CHECK(attempts >= 0),
  status         text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','REPLAYED','RESOLVED','IGNORED')),
  last_error     text NOT NULL DEFAULT '',
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一游标下同一外部记录只失败一次，重放不会产生第二条失败记录
  UNIQUE(tenant_id, cursor_id, external_key),
  FOREIGN KEY(tenant_id, run_id) REFERENCES sync_run(tenant_id, id),
  FOREIGN KEY(tenant_id, cursor_id) REFERENCES sync_cursor(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- platform_operation · 发往平台的写操作
-- ---------------------------------------------------------------------------
CREATE TABLE platform_operation (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  shop_id        uuid NOT NULL,
  marketplace_id uuid,
  operation      varchar(32) NOT NULL
                 CHECK(operation IN ('PUBLISH','UPDATE_PRICE','UPDATE_STOCK','SHIP_UPDATE','CANCEL_ORDER')),
  -- 业务幂等键：重试时用它去重，避免平台侧重复上架或重复扣减
  idempotency_key varchar(128) NOT NULL,
  payload        jsonb NOT NULL DEFAULT '{}'::jsonb,
  status         text NOT NULL DEFAULT 'PENDING'
                 CHECK(status IN ('PENDING','SENDING','SUCCEEDED','FAILED','UNKNOWN')),
  -- 平台返回的请求 ID，用于核验未知结果
  platform_request_id varchar(128) NOT NULL DEFAULT '',
  -- 未知结果核验结论：2.5.1.2 要求先查证再决定重发
  verification   text NOT NULL DEFAULT 'NOT_REQUIRED'
                 CHECK(verification IN ('NOT_REQUIRED','CONFIRMED_APPLIED','CONFIRMED_NOT_APPLIED','STILL_UNKNOWN')),
  verified_at    timestamptz,
  attempts       int NOT NULL DEFAULT 0 CHECK(attempts >= 0),
  last_error     text NOT NULL DEFAULT '',
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY(marketplace_id) REFERENCES marketplace(id)
);

-- ---------------------------------------------------------------------------
-- 多租户隔离
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['sync_cursor','sync_run','sync_failure','platform_operation'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 同步资产的数据范围随店铺收敛：运营不能通过同步队列看到无权店铺的运行记录。
CREATE POLICY cursor_scope ON sync_cursor AS RESTRICTIVE
 USING (channel_scope(channel_operation(),NULL,NULL))
 WITH CHECK (channel_scope(channel_operation(),NULL,NULL));
CREATE POLICY failure_scope ON sync_failure AS RESTRICTIVE
 USING (channel_scope(channel_operation(),NULL,NULL))
 WITH CHECK (channel_scope(channel_operation(),NULL,NULL));

-- 游标失败次数过多时标为 BROKEN：游标跑飞是事故，需要人工介入而不是无限重试
CREATE FUNCTION mark_broken_cursor() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.consecutive_failures >= 5 AND NEW.status = 'ACTIVE' THEN
    NEW.status := 'BROKEN';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cursor_break BEFORE INSERT OR UPDATE ON sync_cursor FOR EACH ROW EXECUTE FUNCTION mark_broken_cursor();

-- 已完成的运行不可篡改计数：可逆性靠冲销新记录，不靠改历史
CREATE FUNCTION guard_settled_run() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status <> 'RUNNING' THEN
    RAISE EXCEPTION '已结束的同步运行不可修改，请创建新的重放运行' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER run_settled BEFORE UPDATE ON sync_run FOR EACH ROW EXECUTE FUNCTION guard_settled_run();

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
CREATE INDEX sync_run_cursor ON sync_run(tenant_id, cursor_id, started_at DESC);
CREATE INDEX sync_run_status ON sync_run(tenant_id, status, started_at DESC)
  WHERE status IN ('RUNNING','FAILED','PARTIAL');
CREATE INDEX sync_failure_open ON sync_failure(tenant_id, reason, occurred_at DESC)
  WHERE status = 'OPEN';
CREATE INDEX sync_failure_run ON sync_failure(tenant_id, run_id);
CREATE INDEX operation_pending ON platform_operation(tenant_id, status, occurred_at)
  WHERE status IN ('PENDING','SENDING','UNKNOWN');
CREATE INDEX operation_shop ON platform_operation(tenant_id, shop_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN CREATE ROLE channel_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO channel_app;
-- sync_run 需要 UPDATE：completeRun 要写入结束状态与计数。
-- "已结束的运行不可篡改"由 run_settled 触发器保证（只允许 RUNNING → 终态），
-- 不靠撤掉表权限——那会连合法的收尾写入一起禁掉。
GRANT SELECT,INSERT,UPDATE ON sync_cursor, sync_run, sync_failure, platform_operation TO channel_app;
GRANT DELETE ON sync_cursor, sync_failure, platform_operation TO channel_app;

-- 同步与回写的权限码挂在 identity 的 permission 表，这里只声明 channel 服务期望的粒度：
--   channel.sync      02.4 同步运行（trigger/complete 分开：触发是读级，写入是操作级）
--   channel.operation 02.5 平台回写
-- 对应的 page_resource 与 permission 由 identity 的迁移维护（那里是权限的真相源）。