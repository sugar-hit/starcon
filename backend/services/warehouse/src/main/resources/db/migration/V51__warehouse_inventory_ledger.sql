-- ===========================================================================
-- 7.1.2.1 库存流水台账：只读查询面的索引
-- ===========================================================================
-- 本模块是 append-only 台账的**读面**，不是新的写入面，因此**不建任何新表**：
--   * 事实表 inventory_transaction（业务单）/ inventory_entry（带符号分录）在 V1 已建，
--     两者都带 tenant_id NOT NULL、ENABLE + FORCE ROW LEVEL SECURITY、append-only 触发器，
--     并已 REVOKE UPDATE,DELETE 给 warehouse_app —— "只追加不修改" 是 DB 层已经成立的约束，
--     本模块刻意不提供任何写端点来绕过它（冲正靠写一条相反符号的分录，那是库存业务写入方的事）。
--   * "期间净变动" 不需要自己的汇总表：它是 Σ 分录，按查询条件现算。存一份累计值等于
--     引入第二个真相来源，7.1.2.2 核对时反而无法判断该信谁。
-- 取舍说明：唯一要做的是把台账的查询路径索引到位 —— 台账每次打开都是「按时间倒序翻页 +
-- 按业务类型/来源单据类型过滤 + 汇总」，这三件事在 V1 的索引上都会退化成顺序扫描。
--
-- 索引方向的选择（两个方向都需要，因为筛选维度住在不同侧）：
--   * 正向：流水 → 分录（inventory_entry_transaction，V1 已有）→ 余额行 → SKU/仓库。
--     时间范围是台账的主过滤条件，(tenant_id, occurred_at desc, id) 让它走索引区间扫描。
--   * 反向：按 SKU/仓库找"哪些业务碰过它"时，从 balance 出发更划算；
--     V1 的 inventory_balance_sku / inventory_balance_overview 已覆盖，entry 侧靠
--     inventory_entry_balance 回连到业务单，所以反向也不必全表扫。

-- 台账主查询：租户 + 时间范围倒序翻页。id 作为决胜键，同一毫秒多笔业务时翻页不会错位或重复。
CREATE INDEX inventory_transaction_ledger
  ON inventory_transaction(tenant_id, occurred_at DESC, id);

-- 业务类型筛选（蓝图输入之一：入库/出库/预占/释放/调整/调拨…）。kind 在 CHECK 约束里是低基数字符串，
-- 放在第二段让「先按时间收窄、再按类型过滤」走同一个索引。
CREATE INDEX inventory_transaction_kind
  ON inventory_transaction(tenant_id, kind, occurred_at DESC, id);

-- 来源单据类型筛选（蓝图输入之一：来源单据类型）。ref_type 是自由文本（不是受控词表），
-- 低基数度与 kind 相当，同样按 (tenant_id, ref_type, occurred_at) 建。
-- 注意 ref_id 的点查由 V1 的 UNIQUE(tenant_id, ref_type, ref_id) 索引承担，幂等锚点同时是查询锚点。
CREATE INDEX inventory_transaction_ref_type
  ON inventory_transaction(tenant_id, ref_type, occurred_at DESC, id);

-- 行的数量列（净在手/净预占/分录条数）由「业务单 + 全部带符号分录」聚合而来，
-- 每翻一页都会重算一次。INCLUDE 把聚合要读的两列带进索引，避免回表逐行取数。
CREATE INDEX inventory_entry_ledger_aggregate
  ON inventory_entry(tenant_id, transaction_id) INCLUDE (on_hand_delta, reserved_delta, balance_id);

-- ---------------------------------------------------------------------------
-- 多租户与授权：本迁移不新增业务表，所以不重复声明 RLS 策略。
-- inventory_transaction / inventory_entry 的 ENABLE + FORCE RLS 与 isolation 策略由 V1 建立，
-- 对本模块的读查询同样生效（集成测试实跑三断言证明租户上下文注入正确）。
-- 授权同理：V1 的 GRANT SELECT ... ON ALL TABLES 已覆盖这两张表，warehouse_app 本就读得到，
-- 且已 REVOKE UPDATE,DELETE —— 台账只读这件事在权限层同样成立。