-- V3 · 跨服务技术表模板 (PostgreSQL 16)
-- 用途：每个业务服务在自己的库里各建一份（表结构相同，数据隔离）。
-- 状态：设计基线，未实施。落地时拆入各服务 V4__technical_core.sql。
-- 规约：见 ../README.md §1
--
-- 铁律（实体目录 §6）：
--   1) inbox 标记消费必须与业务结果同事务，不能"先标已消费再写业务"
--   2) idempotency_request 不是业务事实，清理它不能让业务长期唯一约束失效
--   3) outbox 事件本体不可变，投递状态只更新状态列

-- ---------------------------------------------------------------------------
-- inbox_message · 消费去重
-- ---------------------------------------------------------------------------
CREATE TABLE inbox_message (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  consumer       text NOT NULL,                    -- 消费者标识，如 'trade.order-sync'
  event_id       uuid NOT NULL,                    -- 来自 outbox_event.id
  event_type     text NOT NULL,
  received_at    timestamptz NOT NULL DEFAULT now(),
  processed_at   timestamptz,
  status         text NOT NULL DEFAULT 'RECEIVED'
                 CHECK (status IN ('RECEIVED','PROCESSED','FAILED','SKIPPED')),
  attempt_count  int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  last_error     text NOT NULL DEFAULT '',
  UNIQUE (tenant_id, consumer, event_id)           -- 去重的物理保证
);
-- 列表/排障按未处理优先
CREATE INDEX idx_inbox_pending ON inbox_message (tenant_id, status, received_at)
  WHERE status IN ('RECEIVED','FAILED');

-- ---------------------------------------------------------------------------
-- outbox_event · 可靠发事件
-- ---------------------------------------------------------------------------
-- 注意：已实施的 catalog V1 已有同名表，字段较少。V4 演进时补齐，不新建第二张。
CREATE TABLE outbox_event (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  aggregate_type     text NOT NULL,                -- 聚合类型，如 'sales_order'
  aggregate_id       uuid NOT NULL,
  aggregate_version  bigint NOT NULL DEFAULT 0,
  event_type         text NOT NULL,
  schema_version     int NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
  payload            jsonb NOT NULL,               -- 事件载荷，允许 JSONB
  occurred_at        timestamptz NOT NULL DEFAULT now(),
  -- 投递状态列：只更新这些列，payload 与 event_type 永不变
  published_at       timestamptz,
  attempt_count      int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  next_retry_at      timestamptz,
  lease_until        timestamptz,                  -- relay 租约，防并发重复投递
  last_error         text NOT NULL DEFAULT '',
  UNIQUE (tenant_id, id)
);
-- relay 扫描：只取未发布且到期可重试
CREATE INDEX idx_outbox_pending ON outbox_event (next_retry_at)
  WHERE published_at IS NULL;
-- 消费者按聚合查增量（用于补取与对账）
CREATE INDEX idx_outbox_aggregate ON outbox_event (tenant_id, aggregate_type, aggregate_id, aggregate_version);

-- ---------------------------------------------------------------------------
-- idempotency_request · 主动操作幂等
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_request (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  scope          text NOT NULL,                    -- 幂等域，如 'channel.platform_operation'
  idem_key       text NOT NULL,                    -- 业务提供的幂等键
  request_hash   bytea NOT NULL,                   -- 请求指纹，防"同 key 不同参"
  status         text NOT NULL DEFAULT 'IN_PROGRESS'
                 CHECK (status IN ('IN_PROGRESS','SUCCEEDED','FAILED')),
  resource_type  text NOT NULL DEFAULT '',
  resource_id    uuid,                             -- 成功后产生的资源
  response_body  jsonb,                            -- 缓存的首次响应，重放时原样返回
  created_at     timestamptz NOT NULL DEFAULT now(),
  completed_at   timestamptz,
  expires_at     timestamptz,
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, scope, idem_key)
);
CREATE INDEX idx_idem_expiry ON idempotency_request (expires_at);

-- ---------------------------------------------------------------------------
-- audit_event · 审计（append-only）
-- 已实施 catalog V1 仅有 id/tenant/actor/resource/action/occurred_at，
-- 缺"变更前后值"，无法回答"改了什么"。V4 补齐本列。
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  actor_id       uuid NOT NULL,
  action         text NOT NULL,                    -- 如 'sku.update'
  resource_type  text NOT NULL DEFAULT '',
  resource_id    uuid,
  -- 授权上下文：越权审计必需（当时凭什么能操作）
  auth_context   jsonb NOT NULL DEFAULT '{}'::jsonb, -- 角色/数据范围摘要，已脱敏
  -- 变更前后值，已脱敏；不记密码、token、完整客户地址
  changes        jsonb,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id)
);
CREATE INDEX idx_audit_resource ON audit_event (tenant_id, resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_audit_actor    ON audit_event (tenant_id, actor_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- reference_projection · 跨服务只读副本
-- 各服务按需建具名投影（如 legal_entity_projection），不建万能跨库实体表。
-- ---------------------------------------------------------------------------
CREATE TABLE reference_projection (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  source_service text NOT NULL,                    -- 如 'identity'
  source_type    text NOT NULL,                    -- 如 'legal_entity'
  source_id      uuid NOT NULL,
  payload        jsonb NOT NULL,
  source_version bigint NOT NULL DEFAULT 0,        -- 源版本，用于乱序丢弃
  refreshed_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, source_service, source_type, source_id)
);
-- 幂等重建：按源版本整块替换
CREATE INDEX idx_projection_lookup ON reference_projection (tenant_id, source_type);

-- ---------------------------------------------------------------------------
-- job_run · 定时与后台任务运行记录
-- 蓝图 02.4.1.2 同步运行日志、18.3.2.1 规则执行日志共用此结构。
-- ---------------------------------------------------------------------------
CREATE TABLE job_run (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  job_name       text NOT NULL,
  run_key        text NOT NULL,                    -- 业务幂等键，如 'sync:conn:2026-09-28'
  status         text NOT NULL DEFAULT 'RUNNING'
                 CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  started_at     timestamptz NOT NULL DEFAULT now(),
  finished_at    timestamptz,
  duration_ms    bigint CHECK (duration_ms IS NULL OR duration_ms >= 0),
  cursor_from    text NOT NULL DEFAULT '',
  cursor_to      text NOT NULL DEFAULT '',
  processed_rows bigint NOT NULL DEFAULT 0 CHECK (processed_rows >= 0),
  failed_rows    bigint NOT NULL DEFAULT 0 CHECK (failed_rows >= 0),
  error_summary  text NOT NULL DEFAULT '',
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, job_name, run_key)
);
CREATE INDEX idx_job_run_recent ON job_run (tenant_id, job_name, started_at DESC);

-- ---------------------------------------------------------------------------
-- 不可变保护：事件与审计只能追加
-- ---------------------------------------------------------------------------
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Table % is append-only; correct by appending a reversing entry',
                TG_TABLE_NAME USING ERRCODE = '42501';
END $$;

-- outbox 载荷不可变：UPDATE 与 DELETE 需分两个触发器（DELETE 触发的 WHEN 不能引用 NEW）
CREATE TRIGGER outbox_payload_immutable BEFORE UPDATE ON outbox_event
  FOR EACH ROW WHEN (OLD.payload IS DISTINCT FROM NEW.payload
                 OR OLD.event_type IS DISTINCT FROM NEW.event_type
                 OR OLD.schema_version IS DISTINCT FROM NEW.schema_version)
  EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER outbox_no_delete BEFORE DELETE ON outbox_event
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER audit_no_mutation BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------
-- RLS：全部技术表按租户隔离（app_user 除外，这里没有全局表）
-- ---------------------------------------------------------------------------
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['inbox_message','outbox_event','idempotency_request','audit_event','reference_projection','job_run'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)', t);
  END LOOP;
END $$;
