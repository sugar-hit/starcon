# 7.7.1.2 寄售与代销（阶段 D3，L3 锚点 7.7.1）

- 日期：2026-10-07
- 车道：`feat/7-7-1-2`（worktree `.worktrees/7.7.1.2`）
- 基线：集成头 `integration/module-dev` @ `fa18ce0`

## 交付内容

蓝图原文（`docs/product/capability-blueprint/07-inventory.md:275-280`）：
「管理寄售与代销业务：寄售协议（供应商货、我方销，结算按实际售出）、寄售可用量、销售触发结算数量、
未售退货与期末清理。支持『寄售库存不计入我方采购入库成本，仅在售出时结转』。」

四件职责各自成闭环，全部落到 PostgreSQL 16，无 mock：

| 职责 | 落点 |
| --- | --- |
| 寄售协议 | `consignment_agreement` / `consignment_agreement_line` + 8 个端点（建单/加行/启用/暂停/关闭/列表/详情） |
| 寄售可用量 | `GET /stock-view` + 协议明细行（寄售/已售/退回/转采购/剩余） |
| 销售触发结算数量 | `consignment_sale` → `consignment_settlement(_line)` + 出单/确认端点 |
| 未售退货与期末清理 | `consignment_return(_line)` + 建单/执行/作废端点 |

- 迁移：`V71__warehouse_consignment_agency.sql`（8 张表）
- 端点：17 个，挂 `/warehouse/inventory/consignments`
- 页面：`/inventory/consignment`（`consignment-workspace.tsx` + 纯函数 `consignment-model.ts` 与 16 例单测）
- 测试：`ConsignmentIntegrationTest` 20 例，Testcontainers PostgreSQL 16（连接池压到 1）

## 关键决策与 trade-off

### 1. 货主建模：自带字段，**不动 `stock_bucket`**

兄弟模块 7.7.1.1「货主与所有权」尚未交付，`stock_bucket` 确实没有 `stock_owner` 列。
若把货主塞进 bucket，会与 7.7.1.1 将来加列的工作撞车。

**选择**：寄售事实自成体系的一组表，明细行自带
`stock_owner_type = 'SUPPLIER'` 与 `counts_as_owned_asset`（`CHECK (counts_as_owned_asset = false)`）。
两条车道各写各的 DDL，合并期无冲突；07-inventory.md:24 的硬约束
「寄售货主是供应商，我方只记持有位置与可销售权，不记为自有资产」由一条 DB CHECK 强制。

**代价**：寄售可用量与 `stock_bucket` 的实物量是两套数，暂时对不上。
这是刻意的 —— 等 7.7.1.1 落地后两边按 owner 维度合流。在 capabilities 与库存视图里如实标注了这一点。

### 2. 财务分叉做成 DB 不变量而非应用层自觉

「寄售未售出不产生采购成本与应付」被拆成四道数据库不变量：

1. `remaining_quantity` 是 generated column（寄售 − 已售 − 已退回 − 已转采购）+ `CHECK >= 0`
   —— 寄售量永远只是寄售量，不会变成成本或负债；
2. `consignment_settlement_line.amount` 是 generated column（已售 × 结算价）
   —— 应付**只有**这一个来源，没有「按寄售量算应付」的第二条路径；
3. `UNIQUE(tenant_id, sale_id)` on 结算明细 —— 一条销售事实最多进一张结算单；
4. `CHECK ((disposition='CONVERT_TO_PURCHASE') OR total_payable_amount = 0)` on 退货单
   —— 未售退回不产生应付，转采购是协议写明的唯一例外。

集成测试真的去数据库硬写「按寄售量的应付」和 `counts_as_owned_asset = true`，
验证闸门当场拒绝 —— 只测「卖出去了会算应付」证明不了这条红线。

### 3. 销售事实自落地（9 订单未交付）

「销售触发结算数量」是本模块职责，不能因上游缺席就停摆。自落地 `consignment_sale`，
以 `(agreement_line_id, sale_ref)` 幂等；9 交付后由它推，本表仍是那份事实的唯一出处。
交接键 `sale_ref + source_system`，不建跨服务 FK、不查对方的表。

### 4. 对外只留定位键

12 应付 / 10 物流 / 6 采购均未交付：结算触发单与清理单只写
`source_system + reference_id`，不替它们写数据、不跨库读写。capabilities 端点如实声明。

### 5. 表头合计用**立即**触发而不是延迟约束

最初按 V62 的先例用了 `DEFERRABLE INITIALLY DEFERRED`，结果「刚建好的结算单读出来合计是 0」
（表头在 COMMIT 前没更新，而调用方在同一事务里已经把对象读走了）。
改为**立即** AFTER 触发：重算的是表头自身、不涉及跨表守恒式，每条明细一落就同步，
「表头 == Σ明细」在每条语句之后都成立 —— 比延迟触发更强而不是更弱。

### 6. 退货单的应付额由 disposition 决定

`payable_amount` 行级生成列无条件等于「数量 × 结算价」，若表头无脑求和，
`RETURN_TO_SUPPLIER` 的单据会算出非零应付并撞上自己的 CHECK。
最终由同步触发器按 disposition 决定：退回的单据，数据库自己算出来的应付额就是 0，
不依赖谁记得判断该不该产生应付。

## 踩到的坑（都写进代码注释了）

1. **序列要显式授权**：V1 的 `GRANT ... ON ALL TABLES` 只覆盖表不覆盖序列，
   漏了就是 `permission denied for sequence`（V61 有、这里差点没有）。
2. **`OffsetDateTime` 不带 cast 会被当 varchar 发**：jOOQ 挂在 Spring 异常转换器上，
   PG 直接报 `expression is of type character varying`；一律走 `t` CTE + `?::timestamptz`。
3. **`FROM` 子查询不能引用 UPDATE 目标别名**：合计同步触发器里写
   `where tenant_id = r.tenant_id` 会报 `invalid reference to FROM-clause entry for table "r"`
   （子查询不是 LATERAL）；改用触发器行上的 tenant 变量。
4. **RLS 开着 FORCE 时，事务外裸写 UPDATE 会静默影响 0 行**：
   测试里「验证数据库挡住了我」必须包在 `access.run(...)` 里，否则那句断言是自欺欺人。
5. **异常类型**：jOOQ 挂 Spring 转换器，撞唯一约束抛
   `org.springframework.dao.DuplicateKeyException`，不是 `org.jooq.exception.DataAccessException`。

## 跨车道回归自查

本迁移**只 CREATE 表，不 ALTER 任何既有表**：没有 `ADD COLUMN`、没有 `SET NOT NULL`、
没有加到既有表上的触发器 → 不存在「把别的车道的列改成 NOT NULL 而它 INSERT 不写」这类违约。

表名/类名/路由/Flyway 版本均与并行车道（V63~V74）不撞：
`consignment_*` 前缀、`ConsignmentModels/Service/Controller`、`/inventory/consignment`、V71。

导航不加条目，与 7.4.2.1/7.5.1.1 保持一致（nav 由 `IMPLEMENTED_TASK_PAGES` 驱动，
避免改共享文件产生跨车道冲突）。

## 后续待办（不在本模块范围内）

- 12 应付、10 物流、6 采购、9 订单交付后，按 `source_system + reference_id` / `sale_ref` 接读。
- 7.7.1.1 落地后，寄售可用量与 `stock_bucket` 按 owner 维度合流。
- 成本核算侧需要「仅在售出时结转」的配套（本模块只保证不产生采购成本，不替财务建成本科目）。
