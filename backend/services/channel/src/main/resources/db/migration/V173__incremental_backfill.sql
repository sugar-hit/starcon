-- 02.6.2 增量补拉。
--
-- 铁律（蓝图 §2.6.2.1）：
--   1) **补拉必须幂等**——同一条外部记录被补拉多次不得产生第二张订单或第二次扣减。
--      幂等不是"尽量"，它在 DB 层：backfill_record 的唯一锚点是
--      (租户, 连接, 资源类型, 外部记录 ID)，跨任务全局唯一。第二次补拉撞唯一索引，
--      落为 DEDUPLICATED，而不是新建。
--   2) **平台只给"最近 N 条"时必须分段轮询 + 靠去重兜底**，并在页面上标明
--      "补拉可能延迟发现"。这一条由 CHECK 钉在 DB 层：RECENT_N_SCAN 的任务
--      不允许 delayed_discovery=false，写不成"假装完整"的那份记录。
--   3) **四个数字分开记**：拉取 / 新增 / 更新 / 去重。只记一个总数等于
--      把"补到了东西"和"补到了没见过的��西"混为一谈——后者才是缺口是否真被填上的依据。
--   4) **幂等键是输入契约的一部分**：相同 idempotency_key 重复发起不产生第二个补拉任务。
--
-- 归属纪律：本模块只写 channel 服务自己的三张表。下游是 09 订单 / 04 刊登 / 12 结算，
-- 属其它服务，**跨服务一律走事件**（outbox_event），不读它们的表、不建跨库 FK。
-- 因此这里不存金额、不存订单行：金额与扣减的真相在下游服务，本模块的职责是
-- 「把缺失区间可靠地、幂等地重新捞一遍并交出去」，不是替下游记账。

-- ---------------------------------------------------------------------------
-- backfill_task · 补拉任务
-- ---------------------------------------------------------------------------
-- 任务粒度 = (连接, 站点, 资源类型, 时间窗)，与 2.4.1.3 的 sync_cursor 同款纪律：
-- 整店一把梭会让部分失败无法续传。
CREATE TABLE backfill_task (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  connection_id     uuid NOT NULL,
  -- 站点维度。平台补拉接口多按站点组织（marketplace），与 sync_cursor.marketplace_id 对齐。
  -- 可空表示该连接下所有站点的合并补拉。
  marketplace_id    uuid,
  resource_type     varchar(32) NOT NULL
                    CHECK(resource_type IN ('ORDER','LISTING','INVENTORY','SETTLEMENT','PRODUCT')),
  -- 时间窗：闭开区间 (window_start, window_end]。用左闭右开而不是两端闭合，
  -- 否则相邻两段补拉在边界那条记录上会重复归属，"是否真漏了"就永远说不清。
  window_start      timestamptz NOT NULL,
  window_end        timestamptz NOT NULL,
  -- 触发来源四选一，全部对应蓝图「输入」里点名的三类补拉触发
  trigger_source    text NOT NULL
                    CHECK(trigger_source IN ('WEBHOOK_GAP','CURSOR_REWIND','MANUAL','PLATFORM_RECOVERY')),
  -- 幂等键：上游调用方（漏推检测 / 游标回退 / 人工发起）必须带。相同 key 重复发起
  -- 不会产生第二个补拉任务（唯一索引裁决，服务层返回既有任务）。
  idempotency_key   varchar(200) NOT NULL,
  -- 关联的断点续传游标（2.4.1.3）。补拉成功后按窗口末尾推进它，
  -- 让常规增量从补拉之后接着走，不会把刚补过的区间再拉一遍。
  cursor_id         uuid,

  -- **平台增量接口形态**，这是本模块唯一需要外部知识的判断：
  --   TIME_INCREMENTAL = 平台支持按时间/游标取增量，能直接定位到窗口
  --   RECENT_N_SCAN    = 平台只给"最近 N 条"，无法直接定位，必须分段轮询并靠去重兜底
  pagination_mode   text NOT NULL DEFAULT 'TIME_INCREMENTAL'
                    CHECK(pagination_mode IN ('TIME_INCREMENTAL','RECENT_N_SCAN')),
  -- "补拉可能延迟发现"的标记。分段轮询时，若平台在轮询过程中仍在推新数据，
  -- 轮询到的"最近 N 条"会被新数据顶走，窗口早期的那部分只能靠下一轮才补到 ——
  -- 这就是蓝图说的"可能延迟发现"。界面上必须原样标出来，不能当成一次完整补拉。
  delayed_discovery boolean NOT NULL DEFAULT false,
  -- RECENT_N_SCAN 的任务**必须**承认自己可能延迟发现。这条 CHECK 让
  -- "假装是完整补拉"写不进数据库：应用层忘了置位会直接被拒。
  CHECK(pagination_mode <> 'RECENT_N_SCAN' OR delayed_discovery),

  -- 分页上限与限频（蓝图「识别平台接口的分页上限与限频」的落库形态）
  page_size         int NOT NULL DEFAULT 100 CHECK(page_size BETWEEN 1 AND 1000),
  max_pages         int NOT NULL DEFAULT 20 CHECK(max_pages BETWEEN 1 AND 500),
  rate_limit_per_minute int NOT NULL DEFAULT 60 CHECK(rate_limit_per_minute > 0),

  -- 状态机。PENDING=已受理待执行，RUNNING=执行中，SUCCEEDED=全段完成，
  -- PARTIAL=部分完成（去重或失败都可能导致），FAILED=整体失败，
  -- CANCELLED=人工中止。
  status            text NOT NULL DEFAULT 'PENDING'
                    CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELLED')),

  -- **四个数字分开记**（蓝图「输出」逐项点名）。CHECK 保证它们自洽：
  -- 拉取条数必须等于新增+更新+去重+失败，否则统计就是假的。
  fetched_count     int NOT NULL DEFAULT 0 CHECK(fetched_count >= 0),
  created_count     int NOT NULL DEFAULT 0 CHECK(created_count >= 0),
  updated_count     int NOT NULL DEFAULT 0 CHECK(updated_count >= 0),
  deduplicated_count int NOT NULL DEFAULT 0 CHECK(deduplicated_count >= 0),
  failed_count      int NOT NULL DEFAULT 0 CHECK(failed_count >= 0),
  -- 已完成段数与总段数：分段轮询的进度（RECENT_N_SCAN 用）
  segments_done     int NOT NULL DEFAULT 0 CHECK(segments_done >= 0),
  segment_total     int NOT NULL DEFAULT 0 CHECK(segment_total >= 0),

  -- 补拉完成后推进到的游标值。只有 SUCCEEDED / PARTIAL 且已提交处理时才写回 sync_cursor。
  cursor_after      varchar(128) NOT NULL DEFAULT '',
  last_error        text NOT NULL DEFAULT '',
  duration_ms       int,
  started_at        timestamptz,
  finished_at       timestamptz,
  version           bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- **幂等键的物理保证**：同租户下相同 key 只能有一个补拉任务。
  -- 漏推检测与人工发起很容易对同一段区间各发一次，这条唯一索引让第二次直接撞墙。
  UNIQUE(tenant_id, idempotency_key),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES channel_connection(tenant_id, id),
  FOREIGN KEY(marketplace_id) REFERENCES marketplace(id),
  FOREIGN KEY(tenant_id, cursor_id) REFERENCES sync_cursor(tenant_id, id),
  -- 时间窗必须是正长度。零长窗口的补拉捞不到任何东西，
  -- 却会让人以为"该区间已确认无缺口"，比不发这个补拉更糟。
  CHECK(window_end > window_start),
  CHECK(btrim(idempotency_key) <> '')
);

-- ---------------------------------------------------------------------------
-- backfill_record · 外部记录台账（幂等锚点，蓝图 §2.6.2.1 的本体）
-- ---------------------------------------------------------------------------
-- 一行 = 一条外部记录在本租户某连接上的**首次**入库事实。
-- 重复补拉不会在这里产生第二行 —— 撞唯一索引后落成同任务内的 DEDUPLICATED 计数。
CREATE TABLE backfill_record (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 首次被哪次补拉带进来：事后能回答"这条漏单当初是谁补回来的"
  first_task_id     uuid NOT NULL,
  connection_id     uuid NOT NULL,
  resource_type     varchar(32) NOT NULL
                    CHECK(resource_type IN ('ORDER','LISTING','INVENTORY','SETTLEMENT','PRODUCT')),
  -- 外部记录主键。与 sync_failure.external_key 同款：平台侧的不透明 ID。
  external_key      varchar(200) NOT NULL,
  -- 内容指纹：决定第二次来的是"重复"还是"更新"。
  -- 指纹相同 = DEDUPLICATED（不产生第二条业务记录）；
  -- 指纹不同 = UPDATED（下游按外部键更新，不新增）。二者必须分得开，
  -- 否则"平台改了订单"会被误判成"重复推送"而丢掉变更。
  payload_digest    varchar(64) NOT NULL DEFAULT '',
  -- 原始报文（入库前已脱敏），供下游事件消费。不存明文凭据（AGENTS.md）。
  payload           jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 记录在平台侧的发生时刻（不是我们捞到它的时刻），补拉统计与排查都要它
  external_occurred_at timestamptz NOT NULL,
  -- 重见次数：>1 说明这条被重复补拉到过，几次都是去重而不是新建
  seen_count        int NOT NULL DEFAULT 1 CHECK(seen_count >= 1),
  last_seen_at      timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- **本模块不可让步的一条**：同租户 + 同连接 + 同资源类型下，
  -- 一个外部记录 ID 全局只允许存在一行。第二次补拉撞这里 → DEDUPLICATED。
  UNIQUE(tenant_id, connection_id, resource_type, external_key),
  FOREIGN KEY(tenant_id, first_task_id) REFERENCES backfill_task(tenant_id, id),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES channel_connection(tenant_id, id),
  CHECK(btrim(external_key) <> '')
);

-- ---------------------------------------------------------------------------
-- backfill_segment · 分段轮询记录
-- ---------------------------------------------------------------------------
-- RECENT_N_SCAN 平台（只给"最近 N 条"）的分段轮询留痕。
-- 没有这张表，"分段轮询"就只是一句口号：出了漏单无从证明轮询到哪一段、
-- 哪一段开始顶掉了新数据、缺口为什么没补上。
CREATE TABLE backfill_segment (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  task_id         uuid NOT NULL,
  segment_no      int NOT NULL CHECK(segment_no >= 1),
  -- 该段实际覆盖到的时间水位（从平台取回的最早一条记录的时刻）。
  -- 逐段向窗口起点推进：这一列单调递减就是"往回够"的证据。
  covered_from    timestamptz NOT NULL,
  covered_to      timestamptz NOT NULL,
  -- 该段是否已经把窗口起点之前的内容覆盖完。false = 还有更早的段没轮到。
  reached_window_start boolean NOT NULL DEFAULT false,
  record_count    int NOT NULL DEFAULT 0 CHECK(record_count >= 0),
  -- 该段里有多少条被判定为重复。分段轮询的兜底全靠这一列体现。
  duplicate_count int NOT NULL DEFAULT 0 CHECK(duplicate_count >= 0),
  -- 平台限频命中：该段被限流后按退避重试，命中要留痕（蓝图「识别限频」）
  rate_limited    boolean NOT NULL DEFAULT false,
  polled_at       timestamptz NOT NULL DEFAULT now(),
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一任务里段号唯一：重放同一段不会产生第二条轮询记录
  UNIQUE(tenant_id, task_id, segment_no),
  FOREIGN KEY(tenant_id, task_id) REFERENCES backfill_task(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- 多租户隔离
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['backfill_task','backfill_record','backfill_segment'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围随店铺收敛，与 webhook_receipt / sync_failure 同一纪律。
CREATE POLICY task_scope ON backfill_task AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));
CREATE POLICY record_scope ON backfill_record AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));
CREATE POLICY segment_scope ON backfill_segment AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
-- 工作台默认视图：按发起时间倒序翻页
CREATE INDEX backfill_task_recent ON backfill_task(tenant_id, created_at DESC);
-- 缺口排查主路径：这个连接/站点的这个资源，最近有哪些补拉
CREATE INDEX backfill_task_scope ON backfill_task(tenant_id, connection_id, resource_type, created_at DESC);
-- 未完成任务看板：跑飞的任务必须一眼可见（同 sync_cursor 的 BROKEN 纪律）
CREATE INDEX backfill_task_open ON backfill_task(tenant_id, status, created_at DESC)
  WHERE status IN ('PENDING','RUNNING');
-- 台账视角：这条外部记录被补拉过几次
CREATE INDEX backfill_record_seen ON backfill_record(tenant_id, connection_id, resource_type, external_key, last_seen_at DESC);
-- 重见记录单独可检索："哪些记录是重复补拉捞回来的"是排查重复投递的第一现场
CREATE INDEX backfill_record_repeated ON backfill_record(tenant_id, last_seen_at DESC)
  WHERE seen_count > 1;
-- 分段轮询进度
CREATE INDEX backfill_segment_task ON backfill_segment(tenant_id, task_id, segment_no);

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V170 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN CREATE ROLE channel_app; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO channel_app;
GRANT SELECT,INSERT,UPDATE ON backfill_task, backfill_record, backfill_segment TO channel_app;

-- ---------------------------------------------------------------------------
-- 已终结的补拉任务不可再被改写
-- ---------------------------------------------------------------------------
-- 与 webhook_receipt 的 guard_settled_receipt 同一纪律：可逆性靠补新任务冲销，
-- 不靠改历史。否则"补拉了多久、当时几个数"在事后就永远说不清了。
CREATE FUNCTION guard_settled_backfill() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status IN ('SUCCEEDED','FAILED','CANCELLED') THEN
    RAISE EXCEPTION '该补拉任务已终结，不可修改' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER backfill_settled BEFORE UPDATE ON backfill_task
  FOR EACH ROW EXECUTE FUNCTION guard_settled_backfill();

-- 四个数字必须自洽：拉取 = 新增 + 更新 + 去重 + 失败。
-- 这条 CHECK 让"统计造假"写不进库 —— 只记一个总数、或把去重混进新增，
-- 都会在这里被挡下。蓝图要求区分的四个数字，缺一个都对不上。
CREATE FUNCTION guard_backfill_counts() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.fetched_count <> NEW.created_count + NEW.updated_count + NEW.deduplicated_count + NEW.failed_count THEN
    RAISE EXCEPTION '补拉计数不自洽：拉取数必须等于新增+更新+去重+失败' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER backfill_counts BEFORE INSERT OR UPDATE ON backfill_task
  FOR EACH ROW EXECUTE FUNCTION guard_backfill_counts();

-- 权限码复用 channel.sync.*（与 2.6.1.1 接收记录同一处置）：
-- 增量补拉就是同步运行的补偿面，与游标、断点续传同属平台对接面。
