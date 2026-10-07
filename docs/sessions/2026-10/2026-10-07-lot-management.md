# 7.3.1.1 批次管理（阶段 D3，L3 锚点 7.3.1）

- 日期：2026-10-07
- 车道：`feat/7-3-1-1`（worktree `.worktrees/7.3.1.1`）
- 基线：`integration/module-dev` @ `e869713`

## 决策摘要

### 1. 批次实体只有一张表：补列，不另建「批次档案表」

开工前的依赖自检发现 `inventory_lot` **已经存在**（V2 `V2__warehouse_inventory_bucket.sql`），
且 `stock_bucket` 用 `FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id)`
把批次接成了库存明细的一等事实单位。

因此本模块**只补列**（V57），不新建批次档案表。理由：另建一张表去关联 `inventory_lot`
等于给同一个批次制造两个真相来源，界面显示的档案与库位上挂的批次可能对不上，
而这种不一致极难被发现。

V2 已有的列：`lot_code`、`expires_on`、`received_on`、`status`。
V57 补的列：`produced_on`、`origin`、`source_system`、`source_ref_id`、`source_ref_code`、
`supplier_ref_id`、`supplier_name`、`notes`。

### 2. 本迁移刻意不写 RLS、不写 GRANT

`inventory_lot` 的隔离与授权在 V2 已坐实（ENABLE/FORCE ROW LEVEL SECURITY + `isolation` policy
+ `GRANT ... TO warehouse_app` + `REVOKE ALL FROM starcon_app`）。
本迁移没有新建任何表，`ALTER TABLE ADD COLUMN` 不会让既有策略失效，新增列自动落在同一条
`USING/WITH CHECK` 里。这是结论不是遗漏，由集成测试的三条断言实跑证明，不靠推断。

### 3. 关键 trade-off：出入库历史是「批次相关流水」，不是批次精确流水

**这是本模块最重要的一个取舍，必须写清楚。**

自检发现：`inventory_entry` 与 `inventory_balance` **都没有 `lot_id`**。
balance 的唯一键是 SKU × 仓库 × 货权法人 × 质量状态，**批次只是 `stock_bucket` 这一层的事实**
（`InventoryLedgerModels` 第 239 行原本就写了这一点）。

后果：一条分录**无法被精确归属到某个批次** —— 同一 balance 行下可能同时挂着本批次与其它批次的货。

处理方式：
- 「批次出入库历史」**复用 7.1.2.1 台账**（按任务要求，不另写一套流水查询），
  实现为 `InventoryLedgerService.page(query, lotId)` 的**重载**。
- 响应里带 `attribution = BALANCE_GRANULAR` 与 `attributionNote`，前端**必须显示**这个口径。
- 「批次库存分布」始终是批次精确的（按 `stock_bucket` 逐桶聚合）。

**为什么用重载而不是给 `InventoryLedgerQuery` 加 `lotId` 字段**：加字段会改掉那条 record 的构造
签名，台账控制器与它的集成测试里每处构造都要跟着改 —— 那是另一个模块的改动。重载是纯加法，
不传批次时行为与原来逐字节一致。

**要真正按批次记流水**，必须给 `inventory_entry` 增加批次维度，那是台账表结构变更，
不在本模块范围内。已记入下方「后续待办」。

### 4. 来源采购单/供应商是跨服务定位键，不是外键

采购(6) 与收货(8) 不在本服务，按 AGENTS.md 禁止跨服务建外键或查它们的表。
采用 `source_system + source_ref_id`（与 `inventory_transaction.ref_type/ref_id` 同一套约定），
并固化 `source_ref_code` 单号快照 —— 排障时为了看一个单号去调另一个服务是不可接受的。

供应商同理：`supplier_ref_id` + `supplier_name` 快照。固化的代价是供应商改名后旧批次仍显示旧名，
刻意接受：批次档案是**发生时的记载**。

### 5. 业务日期用 `date`，不用 `timestamptz`

生产/失效/收货日期是产线与包装上写死的事实。`received_on` 缺省时用 **SQL 侧 `current_date`** 兜底
而不是 `LocalDate.now()`：读侧的过期判定也用 `current_date`（数据库当天），
两边都按 JVM 本地日期算，跨时区部署时会差一天。

### 6. 过期（日历事实）与 status（人工判断）分开

`expired` 与 `days_to_expiry` 是查询时按数据库当天算出来的派生值，与 `inventory_lot.status`
是两条独立信息。`status=ACTIVE` 但 `expired=true` 是真实存在的待办状态
（货还在、日期过了、运营还没标），合并成一个字段就看不见了。

## 过程中被数据库挡下来的三个真实问题（都值得记）

1. **`guard_bucket_conservation`**：balance 的在手/预占必须等于其 bucket 汇总，且是**延迟约束**
   （提交前才对平）。夹具最初分两次写，被 DB 拒绝 —— 这正是设计意图。
2. **唯一冲突的异常类型**：jOOQ 走的 DataSource 上，驱动异常先被 Spring 的
   SQL 异常翻译器转成 `org.springframework.dao.DuplicateKeyException`，
   **不是** jOOQ 自己的 `DataAccessException`。接错一个包，409 永远不会触发。
   SQLState 要沿 `getCause()` 链从 `PSQLException` 取。
3. **`current_date - expires_on` 的类型**：纯文本语句里 jOOQ 拿不到列类型，
   `r.get(field, Integer.class)` 会因类型不匹配静默返回 null —— 于是每一行都显示成「没有效期」，
   而这恰恰是最该显示的字段。

另有一处前端 bug 被自己写的测试抓到：`sourceText` 没 trim `sourceRefCode`，
只填空格时会拼出悬空的「收货单   」。

## 文件清单

### 后端（新增）
- `backend/services/warehouse/src/main/resources/db/migration/V57__warehouse_inventory_lot_archive.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryLotModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryLotService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryLotController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryLotIntegrationTest.java`

### 后端（改动，均为纯加法或最小可见性调整）
- `InventoryLedgerService.java` — 新增 `page(query, lotId)` 重载，批次条件加在
  `dimensionConditions` 内（用 `exists` 而非 `join`：一个 balance 可能挂多个批次，
  join 会把同一条分录复制成多行，计数与求和都会翻倍）。既有方法签名、行为、调用方全部不变。
- `InventoryController.java` — `moment` 与 `selfOnly` 由 `private` 改为包级可见，
  供批次控制器复用。**刻意不复制**数据范围推导逻辑：两份拷贝迟早漂移，
  而「只看我自己的流水」一旦漂移就是越权。

### 前端（新增）
- `apps/web/src/components/inventory/inventory-lot-model.ts`
- `apps/web/src/components/inventory/inventory-lot.tsx`
- `apps/web/src/components/inventory/inventory-lot-model.test.ts`
- `apps/web/src/app/inventory/lots/page.tsx`

### 契约与台账
- `packages/api-contracts/src/warehouse.openapi.json`、`catalog.ts` — codegen 产物，新增 11 个类型、4 条路径
- `packages/api-contracts/src/identity.openapi.json` — **顺带刷新的既有漂移**，见下
- `docs/product/capability-blueprint/module-progress.md` — 只改 7.3.1.1 那一行的状态与证据

## 端点

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/warehouse/inventory/lots` | `warehouse.inventory.read` |
| GET | `/warehouse/inventory/lots/{lotId}` | `warehouse.inventory.read` |
| GET | `/warehouse/inventory/lots/{lotId}/distribution` | `warehouse.inventory.read` |
| GET | `/warehouse/inventory/lots/{lotId}/movements` | `warehouse.inventory.read` |
| POST | `/warehouse/inventory/lots` | `warehouse.inventory.update` |
| PUT | `/warehouse/inventory/lots/{lotId}` | `warehouse.inventory.update` |

命名空间刻意用 `/warehouse/inventory/lots` 而不是 `/warehouse/lots` ——
后者是 7.1.1.2 明细页的批次下拉（`InventoryController.lots`），一个只读精简列表，
与本模块的完整档案不是同一个东西。

## 后续待办

1. **按批次精确记流水**：给 `inventory_entry` 增加批次维度（表结构变更，需与 7.1.2.1 协调）。
   在此之前，批次流水只到 balance 口径。届时期末时效（FEFO）出库会在 `stock_bucket` 上扣减，
   库存分布这一层始终批次精确。
2. **7.3.1.2 效期与先进先出**：本模块已把「N 天内到期」的**取数**做在列表筛选里
   （`expiringWithinDays`），但**不产生任何预警** —— 临期/过期预警与 FEFO 推荐属 7.3.1.2。
3. **identity 契约快照漂移**：`packages/api-contracts/src/identity.openapi.json` 在本次
   codegen 前就是**过期的**（`NumberRuleController` 里的 `/identity/numbering/rules/{id}`
   等端点早已在 identity 源码里，但快照里没有）。按「先刷快照再生成」的既定流程，
   本次一并刷新了它（+1885/−1885 行）。这是既有漂移，不是本模块引入的；
   并入时按路径自动求和，不会覆盖别人的内容。
4. **批次身份三件套不可改**：批号 / SKU / origin 是批次身份，改了等于换批次，
   而 `stock_bucket.lot_id` 还指着旧身份。要换批号请新建批次。
