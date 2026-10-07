-- 02.4 同步运行：游标、同步运行、失败队列。
-- 遵循实体目录 §97：sync_cursor / sync_run / sync_failure 按连接、站点、资源、分区保存游标；
-- 只有处理提交后推进，失败可续传。
--
-- 蓝图约束（docs/product/capability-blueprint/02-channel-and-shop.md）：
--   2.4.1.1 任务粒度为（连接, 站点, 资源类型），不允许整店一把梭——否则部分失败无法续传。
--   2.4.1.3 游标只在处理提交后推进；回退重拉必须幂等。
--   2.4.2.1 失败按原因聚类；"授权失效"自动升级为工作台待办。

-- ---------------------------------------------------------------------------
-- sync_cursor · 每个（连接, 站点, 资源, 分区）一个游标
-- ---------------------------------------------------------------------------
-- 站点是粒度的一部分而不是可选维度：蓝图明确要求（连接, 站点, 资源类型）。
-- 保持 NOT NULL 也让唯一键不会因 NULL 在唯一索引里的重复语义而产生两个"看起来一样"的游标。
CREATE TABLE sync_cursor (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  connection_id  uuid NOT NULL,
  marketplace_id uuid NOT NULL,
  resource_type  text NOT NULL
                 CHECK(resource_type IN ('ORDER','LISTING','INVENTORY','SETTLEMENT')),
  -- 分区：平台按卖家/仓库/国家等维度切分增量时的续传单元，不是每个资源都非空
  partition_key  varchar(128) NOT NULL DEFAULT '',
  -- 已成功提交到的平台侧时间水位。NULL 会被"从未同步过"和"清空游标"混为一谈，
  -- 因此起始值用 epoch 而不是 NULL，重置走显式的回退动作而不是清列。
  cursor_value   timestamptz NOT NULL DEFAULT timestamptz 'epoch',
  -- 部分平台的分页标记不是时间而是字符串游标；两者都要记，否则只能整窗重拉。
  external_marker varchar(255) NOT NULL DEFAULT '',
  -- 连续失败次数：连接级"持续漏单"的判断依据，供 2.2.2.1 健康诊断读。
  consecutive_failures int NOT NULL DEFAULT 0 CHECK(consecutive_failures >= 0),
  last_run_id    uuid,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, connection_id, marketplace_id, resource_type, partition_key),
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
  -- 连接/站点/资源在运行行上冗余成快照：2.4.1.2 的运行日志必须能在游标被回退或任务被删除后
  -- 仍然讲清"那次运行拉的是哪个站点的哪类资源"，不能靠反查游标当前值。
  connection_id  uuid NOT NULL,
  marketplace_id uuid NOT NULL,
  resource_type  text NOT NULL
                 CHECK(resource_type IN ('ORDER','LISTING','INVENTORY','SETTLEMENT')),
  trigger_kind   text NOT NULL DEFAULT 'SCHEDULED'
                 CHECK(trigger_kind IN ('SCHEDULED','MANUAL','RESUME','ROLLBACK_REPLAY')),
  status         text NOT NULL DEFAULT 'RUNNING'
                 CHECK(status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED')),
  started_at     timestamptz NOT NULL DEFAULT now(),
  finished_at    timestamptz,
  duration_ms    bigint,
  -- 处理计数。fetched 与 written/updated/skipped 分开：抓到了多少和处理落库了多少必须能对账，
  -- 否则"部分失败"会被算成成功。
  fetched_count  int NOT NULL DEFAULT 0 CHECK(fetched_count >= 0),
  written_count  int NOT NULL DEFAULT 0 CHECK(written_count >= 0),
  updated_count  int NOT NULL DEFAULT 0 CHECK(updated_count >= 0),
  skipped_count  int NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),
  warning_count  int NOT NULL DEFAULT 0 CHECK(warning_count >= 0),
  error_count    int NOT NULL DEFAULT 0 CHECK(error_count >= 0),
  error_message  text NOT NULL DEFAULT '',
  -- 本次运行开始时读到的游标快照：提交后对比它才能证明"只推进到已提交点"。
  cursor_before  timestamptz NOT NULL,
  -- 平台侧字符串游标的前后值。部分平台靠它分页，只记时间水位会让运行日志讲不清断在哪一页。
  cursor_marker_before varchar(255) NOT NULL DEFAULT '',
  -- 提交后推进到的位置。只在游标真正推进时写入，FAILED 运行必须保持 NULL。
  cursor_after   timestamptz,
  cursor_marker_after varchar(255) NOT NULL DEFAULT '',
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, cursor_id) REFERENCES sync_cursor(tenant_id, id),
  -- 终态运行必须有结束时间与耗时；未结束不许伪造耗时。
  CONSTRAINT run_terminal_shape CHECK(
    (status = 'RUNNING' AND finished_at IS NULL)
    OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND duration_ms IS NOT NULL)),
  -- 推进游标的运行必须记下推进到了哪里，否则事后无法解释"漏了一小时"发生在哪个水位。
  CONSTRAINT run_cursor_after_present CHECK(
    status IN ('SUCCEEDED','PARTIAL') OR cursor_after IS NULL)
);

-- 同一游标同时只允许一个 RUNNING 运行：断点续传的前提是有唯一的推进者，
-- 两个并发运行会各自推进水位，漏掉彼此跳过的区间。
CREATE UNIQUE INDEX run_single_flight ON sync_run(tenant_id, cursor_id)
  WHERE status = 'RUNNING';

-- 2.4.1.1 同步任务列表：按连接+站点+资源列出最近运行
CREATE INDEX run_cursor_started ON sync_run(tenant_id, cursor_id, started_at DESC);
CREATE INDEX run_connection_started ON sync_run(tenant_id, connection_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- sync_failure · 失败队列
-- ---------------------------------------------------------------------------
CREATE TABLE sync_failure (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  run_id         uuid NOT NULL,
  cursor_id      uuid NOT NULL,
  connection_id  uuid NOT NULL,
  resource_type  text NOT NULL
                 CHECK(resource_type IN ('ORDER','LISTING','INVENTORY','SETTLEMENT')),
  -- 2.4.2.1 的聚类维度。分类是封闭枚举：原因分布图要能画，新增原因必须显式加进来，
  -- 不能靠自由文本把"平台限流"和"网络异常"混成一类。
  failure_reason text NOT NULL
                 CHECK(failure_reason IN ('AUTH_EXPIRED','FIELD_PARSE_FAILED','MAPPING_MISSING',
                                          'RATE_LIMITED','NETWORK_ERROR','UNKNOWN')),
  -- 外部记录主键。2.4.2.2 重放幂等的依据：重复处理同一条外部记录不产生第二次落库。
  external_record_key varchar(255) NOT NULL,
  -- 脱敏原文或对象引用，绝不存明文凭据/个人敏感字段（AGENTS.md 安全条）。
  external_payload_ref text NOT NULL DEFAULT '',
  attempt_count  int NOT NULL DEFAULT 1 CHECK(attempt_count >= 1),
  status         text NOT NULL DEFAULT 'OPEN'
                 CHECK(status IN ('OPEN','REPLAYING','RESOLVED','IGNORED')),
  -- 2.4.2.1："授权失效"类失败会自动升级为工作台待办，因为它导致持续漏单而不是单条失败。
  escalated      boolean NOT NULL DEFAULT false,
  last_error     text NOT NULL DEFAULT '',
  resolved_at    timestamptz,
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, run_id) REFERENCES sync_run(tenant_id, id),
  FOREIGN KEY(tenant_id, cursor_id) REFERENCES sync_cursor(tenant_id, id),
  -- 授权失效必然升级为待办，不给"忘记点升级"留缝。
  CONSTRAINT auth_failure_escalates CHECK(failure_reason <> 'AUTH_EXPIRED' OR escalated)
);

-- 重放幂等的硬保证：同租户同连接的同一条外部记录只允许存在一条未关闭的失败。
-- 重放走更新这一行（attempt_count+1），因此重复处理不可能凭空多出第二张待办。
CREATE UNIQUE INDEX failure_open_dedup
  ON sync_failure(tenant_id, connection_id, resource_type, external_record_key)
  WHERE status IN ('OPEN','REPLAYING');

-- 2.4.2.1 失败队列按原因聚类 + 2.4.2.2 人工重放取待处理项
CREATE INDEX failure_queue ON sync_failure(tenant_id, failure_reason, created_at DESC)
  WHERE status IN ('OPEN','REPLAYING');
-- 2.4.2.1 工作台待办：待升级的授权失效
CREATE INDEX failure_escalated ON sync_failure(tenant_id, created_at DESC)
  WHERE escalated AND status IN ('OPEN','REPLAYING');

-- ---------------------------------------------------------------------------
-- 游标只能前进，除非显式声明回退意图
-- ---------------------------------------------------------------------------
-- 与 shop 负责人转移同一纪律：改字段值等于改事实来源，必须走显式的可审计动作，
-- 不能靠一条 UPDATE 悄悄把水位往回拨。
-- 正常推进由应用层在同一事务里与运行终态一起写入；回退端点通过
-- set_config('app.cursor_rollback', ...) 声明意图，触发器据此放行。
CREATE FUNCTION guard_cursor_direction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.cursor_value IS DISTINCT FROM OLD.cursor_value
    AND NEW.cursor_value < OLD.cursor_value
    AND coalesce(nullif(current_setting('app.cursor_rollback',true),'')::boolean,false) IS NOT TRUE
 THEN
  RAISE EXCEPTION 'Cursor rollback must use the explicit rewind endpoint' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER cursor_direction BEFORE UPDATE ON sync_cursor FOR EACH ROW EXECUTE FUNCTION guard_cursor_direction();

-- 游标水位与最近一次运行保持一致，否则 2.4.2.1 的"持续漏单"判断会读到过期的运行。
ALTER TABLE sync_cursor ADD CONSTRAINT cursor_run_belongs_to_cursor
  FOREIGN KEY(tenant_id, last_run_id) REFERENCES sync_run(tenant_id, id);

-- 索引：2.4.1.3 断点续传按水位找可续传窗口
CREATE INDEX cursor_watermark ON sync_cursor(tenant_id, connection_id, resource_type, cursor_value);

-- ---------------------------------------------------------------------------
-- 多租户隔离
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['sync_cursor','sync_run','sync_failure'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 同步运行数据是连接级运营数据，不带店铺负责人/部门维度：任务的粒度是
-- （连接, 站点, 资源类型），本身就是连接级事实。因此沿用 credential_scope 的做法，
-- 以 channel_scope(..., NULL, NULL) 收敛——SELF/DEPARTMENT 的数据范围只作用于
-- 2.3.2.2 有归属语义的店铺数据，把店铺负责人套到运行日志上会让"谁跑了同步"变成
-- 另一个问题（那属于审计 actor，由 app.actor 记录）。
CREATE POLICY sync_scope ON sync_cursor AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));
CREATE POLICY sync_run_scope ON sync_run AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));
CREATE POLICY sync_failure_scope ON sync_failure AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));

-- ---------------------------------------------------------------------------
-- 授权
-- ---------------------------------------------------------------------------
GRANT SELECT,INSERT,UPDATE,DELETE ON sync_cursor, sync_run, sync_failure TO channel_app;