-- ===========================================================================
-- 7.1.2.2 账实核对：核对结果与可疑定位的存储
-- ===========================================================================
-- 蓝图原文：「定期核对『按流水累计的余额』与『当前余额快照』是否一致，差异时列出可疑区间
-- （漏记、重复记、并发丢失）」「输出：核对结果 + 差异明细 + 可疑流水定位」。
--
-- **为什么建表、为什么不建"累计值"表**
--   核对的两侧（Σ 分录、当前余额快照）都是**现算**的：流水是 append-only 的事实，余额是可重算的
--   投影，各自只有一个来源。刻意**不**另建一张"累计余额表"——那等于引入第二个真相来源，
--   核对时反而无法判断该信谁。V51 台账迁移已经按同一条理由拒绝过汇总表，本迁移延续它。
--   本迁移只落**核对这件事的结果**：一次核对产出一份不可变的记录（run）与若干条发现（finding），
--   它本身不是真相来源，而是"某时点核过、核了什么、结论是什么"的审计凭据。
--
-- **为什么不只留 run（必须留 finding）**
--   差异的价值全在"哪一段流水可疑"。只存一个差额数字，等于每次都要重跑一遍全量聚合才能回答
--   "是哪几笔"，而那时数据可能已经又变了。finding 把可疑定位固化成可回查的行：每条都能落到
--   具体的 balance 行或具体的 transaction，并带发生时间区间。
--
-- 口径提醒（读这两张表的人必须知道）：
--   run.expected_on_hand = opening_on_hand + period_on_hand + after_window_on_hand
--   run.on_hand_diff    = expected_on_hand − snapshot_on_hand
--   当核对区间覆盖全部历史时 opening 与 after_window 自然为 0，公式退化成教科书式的"累计 vs 快照"。
--   区间只覆盖一段历史时**必须**带上期初，否则拿区间内的流水去比"当前"快照会凭空差出期初量。
--   after_window 单列一档而不是并进期初：流水被记在核对区间**之后**，本身就是异常信号
--   （错期录入/事后补记），把它藏进别的桶里就没人看得见了。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 核对批次
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_reconciliation_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 核对范围。scope_key 存对应的维度 id；范围为 ALL 时为 NULL（"全部"不是某个具体对象的 id）。
  scope_type varchar(20) NOT NULL CHECK(scope_type IN ('ALL','WAREHOUSE','SKU','LEGAL_ENTITY')),
  scope_key uuid,
  -- 范围的中文名固化下来：维表改名/删除后，三个月前那次核对的范围仍然读得懂。
  scope_label varchar(128) NOT NULL DEFAULT '',
  -- 左闭右开，避免相邻区间在边界那一瞬重复计数。
  window_from timestamptz NOT NULL,
  window_to timestamptz NOT NULL,
  -- CONSISTENT 同样是有效输出：没有差异不等于没核对，差异为 0 的那次也必须有记录。
  status varchar(16) NOT NULL CHECK(status IN ('CONSISTENT','DISCREPANT')),

  -- 规模（可追溯性：核对了多少东西）
  checked_balance_count bigint NOT NULL DEFAULT 0,
  checked_entry_count bigint NOT NULL DEFAULT 0,
  checked_transaction_count bigint NOT NULL DEFAULT 0,

  -- 在手口径：期初 + 区间内累计 + 区间后累计 = 应当等于快照
  opening_on_hand numeric(19,4) NOT NULL DEFAULT 0,
  period_on_hand numeric(19,4) NOT NULL DEFAULT 0,
  after_window_on_hand numeric(19,4) NOT NULL DEFAULT 0,
  expected_on_hand numeric(19,4) NOT NULL DEFAULT 0,
  snapshot_on_hand numeric(19,4) NOT NULL DEFAULT 0,
  on_hand_diff numeric(19,4) NOT NULL DEFAULT 0,

  -- 预占口径，与在手同一套公式（预占走 reserved_delta，V1 的 CHECK 只保证两者至少一个非零）
  opening_reserved numeric(19,4) NOT NULL DEFAULT 0,
  period_reserved numeric(19,4) NOT NULL DEFAULT 0,
  after_window_reserved numeric(19,4) NOT NULL DEFAULT 0,
  expected_reserved numeric(19,4) NOT NULL DEFAULT 0,
  snapshot_reserved numeric(19,4) NOT NULL DEFAULT 0,
  reserved_diff numeric(19,4) NOT NULL DEFAULT 0,

  discrepancy_count bigint NOT NULL DEFAULT 0,
  suspect_count bigint NOT NULL DEFAULT 0,
  first_suspect_at timestamptz,
  last_suspect_at timestamptz,

  -- 结论的完整说明。差异为零也要写清"核对了哪个区间、多少笔分录"，否则这份记录无法复现。
  verdict_note text NOT NULL DEFAULT '',
  -- 触发人：已认证成员 id。将来接调度器时这一列会指向服务账号（见会话日志的后续待办）。
  triggered_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(window_from < window_to)
);

-- 列表默认按最近一次排：核对比流水更常被问的是"最近核过什么、结论如何"。
CREATE INDEX inventory_reconciliation_run_recent
  ON inventory_reconciliation_run(tenant_id, created_at DESC, id);
-- 按结论筛历史批次（工作台预警要取最近若干次 DISCREPANT）。
CREATE INDEX inventory_reconciliation_run_status
  ON inventory_reconciliation_run(tenant_id, status, created_at DESC, id);
-- 同一区间被反复核对的场景（人工点几次、定时任务补跑），从区间定位历史批次。
CREATE INDEX inventory_reconciliation_run_window
  ON inventory_reconciliation_run(tenant_id, window_from, window_to);

-- ---------------------------------------------------------------------------
-- 2. 核对发现（差异明细 + 可疑流水定位）
-- ---------------------------------------------------------------------------
-- 一张表装两类发现，用 kind 区分：DISCREPANCY 是"哪一行余额对不上"，
-- SUSPECT 是"为什么对不上、该去看哪几笔流水"。两类共用 SKU/仓库/法人/质量状态四组定位维度
-- （与 V2 的 balance 维度一致），所以放在一张表里，下钻与回查走同一条路径。
CREATE TABLE inventory_reconciliation_finding (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,

  kind varchar(16) NOT NULL CHECK(kind IN ('DISCREPANCY','SUSPECT')),
  -- 可疑类别，只对 SUSPECT 有值：漏记 / 漏记的晚期形态（迟到补记）/ 重复记 / 并发丢失。
  -- 取值集合在 InventoryReconciliationModels.SuspectCategory，DB 侧用 CHECK 兜住。
  category varchar(20) CHECK(category IS NULL OR category IN ('MISSING','BACKFILL','DUPLICATE','LOST_UPDATE')),
  severity varchar(8) NOT NULL DEFAULT 'INFO' CHECK(severity IN ('INFO','WARN','HIGH')),

  -- 定位维度。DISCREPANCY 行四组必有；SUSPECT 行同样必带（可疑流水总是挂在某个 balance 上）。
  balance_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  warehouse_id uuid NOT NULL,
  warehouse_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_id uuid NOT NULL,
  legal_entity_code varchar(32) NOT NULL DEFAULT '',
  quality_status_id uuid NOT NULL,
  quality_status_code varchar(32) NOT NULL DEFAULT '',

  -- 指向具体流水。DISCREPANCY 行记录"最后一次动过它的业务"，SUSPECT 行记录可疑的那一笔。
  -- 漏记类可能整段区间内无流水可指，此时 transaction_id 为 NULL —— 这本身就是结论。
  transaction_id uuid,
  ref_type varchar(32) NOT NULL DEFAULT '',
  ref_id uuid,
  tx_kind varchar(24) NOT NULL DEFAULT '',
  actor_id uuid,
  occurred_at timestamptz,
  entry_created_at timestamptz,
  -- 重复记/并发丢失的**另一笔**流水，以及两笔相差多少秒。
  -- 怀疑必须成对给出：只说"重复了"而不告诉你是跟谁重复，人还是得回去翻流水。
  related_transaction_id uuid,
  gap_seconds bigint,
  -- 分录入库时刻比声称的发生时刻晚多少秒（迟到补记用）。
  lateness_seconds bigint,

  -- 数量。口径随 kind 变化：DISCREPANCY 行是"这条余额行的累计"，SUSPECT 行是"这一笔分录的带符号值"。
  on_hand_sum numeric(19,4) NOT NULL DEFAULT 0,
  reserved_sum numeric(19,4) NOT NULL DEFAULT 0,
  snapshot_on_hand numeric(19,4) NOT NULL DEFAULT 0,
  snapshot_reserved numeric(19,4) NOT NULL DEFAULT 0,
  on_hand_diff numeric(19,4) NOT NULL DEFAULT 0,
  reserved_diff numeric(19,4) NOT NULL DEFAULT 0,
  entry_count bigint NOT NULL DEFAULT 0,
  transaction_count bigint NOT NULL DEFAULT 0,

  -- 可疑区间：把差异圈到一段时间上，而不是只给一个差额数字。
  -- SUSPECT 行 = 该笔流水所属的时间桶（按天，对齐运营查账的自然粒度）；
  -- DISCREPANCY 行 = 该余额行全部流水的发生时间跨度。
  window_start timestamptz,
  window_end timestamptz,

  reason text NOT NULL DEFAULT '',

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,run_id) REFERENCES inventory_reconciliation_run(tenant_id,id)
);

-- 回查某次核对的全部发现（端点按 kind 分页即走这条索引）。
CREATE INDEX inventory_reconciliation_finding_run
  ON inventory_reconciliation_finding(tenant_id, run_id, kind, severity, id);
-- 从差异行下钻到它的可疑流水：同一 balance 下的发现排在一起。
CREATE INDEX inventory_reconciliation_finding_balance
  ON inventory_reconciliation_finding(tenant_id, balance_id, kind);
-- 直接跳到某笔流水的定位结论（工作台预警与外部仓对账按 transaction 追）。
CREATE INDEX inventory_reconciliation_finding_transaction
  ON inventory_reconciliation_finding(tenant_id, transaction_id) WHERE transaction_id IS NOT NULL;
-- 定位"哪一段时间可疑"：按可疑区间倒序扫（finding 的时间桶聚合成可疑区间排行即走它）。
CREATE INDEX inventory_reconciliation_finding_window
  ON inventory_reconciliation_finding(tenant_id, run_id, kind, window_start);

-- ===========================================================================
-- 3. 多租户与授权
-- ===========================================================================
-- 与 V1/V2 同一套写法。核对结果表尤其要 FORCE：它汇总的是**全租户**的流水与余额，
-- 一旦漏掉 tenant_id 或漏掉 RLS，就是把 A 租户的账目泄露给 B 租户。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它（集成测试实跑三断言证明）。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['inventory_reconciliation_run','inventory_reconciliation_finding'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_reconciliation_run, inventory_reconciliation_finding TO warehouse_app;
-- 核对结论是审计凭据：一旦写下就不许改，更不许删（与流水台账同一条纪律）。
-- 查出来的差异要作废，只能重跑一次核对，新旧两批结果并存 —— 这正是"可追溯"的意思。
-- 权限层之外再加一道触发器（复用 V1 的 guard_inventory_append_only）：REVOKE 只挡住应用角色，
-- 触发器挡住所有路径，少一层就少一处"有人顺手改了一下结论而没人发现"的可能。
CREATE TRIGGER inventory_reconciliation_run_append_only
  BEFORE UPDATE OR DELETE ON inventory_reconciliation_run
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();
CREATE TRIGGER inventory_reconciliation_finding_append_only
  BEFORE UPDATE OR DELETE ON inventory_reconciliation_finding
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();
REVOKE UPDATE,DELETE ON inventory_reconciliation_run, inventory_reconciliation_finding FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2 同口径）。
REVOKE ALL ON inventory_reconciliation_run, inventory_reconciliation_finding FROM starcon_app;
