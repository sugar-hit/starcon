# 2026-10-14 · 8.3.2.1 拣货任务（阶段 D4，车道 feat/8-3-2-1）

## 决策摘要

在 `integration/module-dev`（bddb48d）之上新起一条 worktree 车道交付蓝图 08 卷 **8.3.2.1 拣货任务**。
交付六张表（V110）、`/warehouse/pick-tasks` 十个端点、`/warehouse/pick-tasks` 真实工作区页面、
33 例后端集成测试 + 23 例前端测试、api-proxy 一个顶层白名单分支、台账 8.3.2.1 那一行。

**开工前的上游依赖自检结论（10 分钟内做完，全部通过，未 BLOCKED）**：
V72 的 `outbound_order` / `outbound_order_line` / `outbound_pick_request` / `outbound_order_event`
（8.3.1.1 已交付）、V2 的 `warehouse_location` / `stock_bucket`、V1 的 `inventory_balance` /
`inventory_transaction` / `inventory_entry`、V58 的 `inventory_serial`、V105 的
`warehouse_location.parent_id/capacity`、V107 的 `reservation_allocation`（7.2.1.2 已交付）
在集成头全部 grep 得到。V72 §3 明写「8.3.2.1 交付后按 (tenant_id, order_id) 读这张表接手」——
那是本模块的交接契约，本实现照它做。

## 关键 trade-off

### 1. 拣货对库存做了什么：只搬库位，balance 一字不改

蓝图关系段说「拣货只是把货从库位移到打包区，库存仍占用未释放」，卷级约束又说
「所有作业动作与库存分录在同一本地事务完成」。两句话放一起会诱导人写一个
「出库分录 + 入库分录」的双向流水，**本模块刻意不写**：

* 打包区是同仓的 `warehouse_location.type='SHIPPING'`，而 `inventory_balance` 的六元组
  （sku × warehouse × legal_entity × quality）**不含库位与批次**，所以同仓内换库位
  在 balance 层什么都没发生；
* `inventory_entry` 有 `CHECK(on_hand_delta<>0 OR reserved_delta<>0)`，delta=0 的分录写不进去，
  而写两条 ±q 互相抵消只会把「Σ分录 == 余额变化」这条守恒式变成两笔噪音；
* V2 §4.4 的 bucket 守恒式（balance = Σ bucket）在事务提交时天然成立 —— 两侧 bucket 一增一减，
  合计没变。

**reserved 跟着货一起搬**（`min(reserved, 实拣量)`），既不释放也不留在原地：
货被本单预占了就该跟着走到打包区，否则「这个库位上的预占是多少」会指向一批已经不在这儿的货。

### 2. 分配基准是 `on_hand` 而不是 `available`（= on_hand − reserved）

这是实现过程中被集成测试逼出来的一次修正。原来按 `available` 分配，测试立刻炸出
「计划 10 件的出库单只排出 4 件」—— 因为出库单对应的货**正是被销售预占住的那批**。
按 available 分配的话，只要预占生效，每一张出库单都会变成「一个库位都拣不动」，
预占越成功拣货越拣不动。因此列名与字段名都从 `available_at_source` 改成 `on_hand_at_source`，
V110 那一列的注释把这个理由写死。

### 3. 允许实际取货库位与计划不同

分配是**建议**，现场是**事实**。真碰上计划的那个库位已经空了，作业员必须能在别处取到
并把真实库位记下来，而不是被系统逼着报一个缺货。被拒的是「那个库位上根本没有这件货」
与「拣超过本段计划」。前端因此把「实际库位 vs 计划库位」并排显示，漂移一眼可见。

### 4. 没有「强制完成」，但有「重开」

* 没有强制完成：9/10 的任务提交到复核，复核看到的仍然是缺件，而缺件的原因
  （本该缺货、还是有人漏拣）在那一刻已经分不清。每一段要么拣齐，要么已按缺货登记。
* 但必须有重开：8.3.1.1 的 `outbound_pick_request` 上有 `UNIQUE(tenant_id, order_id)`
  （一张出库单只交接一次），而它的状态机里留着「待复核 → 拣货中」的返工边。
  没有重开，被复核退回的订单会永远停在「拣货中」而没有作业入口。
  重开后**实拣量累加不覆盖** —— 覆盖等于抹掉第一次的事实，而复核退回的原因恰恰在第一次的记录里。

### 5. 权限码复用 `warehouse.inventory.read/.update`，不新起 `warehouse.picking.*`

identity 只注册了 `warehouse.inventory` 与 `warehouse.facilities` 两个页面资源的权限码
（V8 / V69 / V77）。用一个**没注册**的码会让连 OWNER 都 403（8.1.2.1 车道踩过这个坑并在台账里
记了原因）。拣货与 8.2.1.1 收货单、8.3.1.1 出库单、8.3.4.1 首程发货同属「出入库作业」这一族。

### 6. 8.5.2.1 那两条建议的落地位置

8.5.2.1 库位利用率的六类建议里，两类的 `ownerModule` 写的是「8.3.2.1 拣货路径优化」。
本模块把它们落成两个**同名口径**的字段，作业侧逐行标出来：

| 8.5.2.1 建议 | 本模块字段 | 触发 |
|---|---|---|
| HIGH_FREQUENT_NON_PICKABLE「高频货在不可拣库位」 | `pick_task_line.non_pickable_source` | 优先可拣且 ACTIVE 的库位；没有可拣来源才退到不可拣位，并把该行标出来 |
| SKU_DISPERSION「SKU 散在多库位」 | `pick_task_line.sku_location_count`（真实跨库位计数）+ 收敛分配 | 同一 SKU 优先在一个库位一次拣齐，拣不齐才拆段 |

另有一条 UI 侧的告示把两个数回指建议来源模块。

### 7. 不写 `outbound_order_event`

本模块只改 `outbound_order.status`（完成时 PICKING → PENDING_RECHECK，这是 V72 §3 承诺的
「并回写状态」），**不往 8.3.1.1 的事件表里写行**：那张表的唯一写入所有者是它自己，
而且 V72 的 `event_type` CHECK 没有本模块的取值。本模块的作业留痕在自己的
`pick_task_event` 上。

## 文件清单

**迁移**
- `backend/services/warehouse/src/main/resources/db/migration/V110__warehouse_pick_task.sql`

**后端**
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/PickTaskModels.java`（新建）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/PickTaskService.java`（新建）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/PickTaskController.java`（新建）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/PickTaskIntegrationTest.java`（新建，33 例）

**契约（codegen 产物，只由脚本写出）**
- `packages/api-contracts/src/warehouse.openapi.json`
- `packages/api-contracts/src/contracts/warehouse.ts`

**前端**
- `apps/web/src/components/warehouse/pick-task-model.ts`（新建）
- `apps/web/src/components/warehouse/pick-task-model.test.ts`（新建，15 例）
- `apps/web/src/components/warehouse/pick-task-workspace.tsx`（新建）
- `apps/web/src/app/warehouse/pick-tasks/page.tsx`（新建）
- `apps/web/src/app/api/warehouse/pick-task-allowlist.test.ts`（新建，8 例）
- `apps/web/src/lib/api-proxy.ts`（**只追加** `pick-tasks` 一个顶层分支）

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（只改 8.3.2.1 那一行）
- `docs/sessions/2026-10/2026-10-14-picking-task.md`（本文件）

## api-proxy 求并说明（下一轮必读）

新增分支插在 **`disassembly-orders` 之后、全表 `$/,` 之前**（即 warehouse 那条巨型正则的最后一条）。
插入点之前 **7258 字符逐字节相同**，其余所有行零改动，既有分支的顺序与内容一个字节未动。

新分支本体：

```
pick-tasks(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:assign|picks|exceptions(?:\/[0-9a-f-]+\/resolve)?|complete|reopen|cancel))?)?
```

* `\/capabilities` **排在 `\/[0-9a-f-]+` 之前**（alternation 首个命中即返回，
  且 "capabilities" 里的 c/a/p/b/l/t/i/e/s 大量落在 `[0-9a-f-]` 内，顺序反了会被 UUID 分支吃掉）。
* 十个真实形状全放行；`{id}/waves`（8.3.2.2 未交付）、多一层后缀、`pick-task` / `pick_task` 拼错仍 404。

## 验证

* L0：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` 3/3 ✅
* L1：`cd backend && ./mvnw -pl services/warehouse -am test` ✅（含 PickTaskIntegrationTest 33 例）
* L1 前端：`pnpm vitest run <pick-task-model.test.ts> <pick-task-allowlist.test.ts>` → 2 files / 23 tests 全绿
* 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir packages/api-contracts/src`
  → 1583 DTOs / 6 服务，新增 22 schema + 10 path，零重名

## 后续待办

1. **8.3.2.2 波次与合并拣货**：`capabilities.waveAvailable` 已如实报 false 并指认归属。
   波次合并只影响拣货效率、不改变订单粒度，落地时要在 `pick_task` 上加波次引用而不是改工单粒度。
2. **8.3.3.1 复核扫描**：本模块完成时只把出库单交到「待复核」。复核要按 `pick_task_pick`
   逐件核对序列号，落地时直接读本模块的实拣记录（同一服务同库，不需要新增接口）。
3. **8.5.1.1 作业效率**若要归因拣货工时，读 `pick_task.assignee_user_id` + `started_at` /
   `completed_at`，本模块已按这个口径建索引（`pick_task_assignee` 部分索引）。
4. **identity 权限**：本模块复用了 `warehouse.inventory.*`。若后续要按「拣货」单独授权
   （例如拣货员只读拣货、不能改仓库档案），需要 identity 单独注册
   `warehouse.picking` page_resource —— 那是 identity 服务的一条迁移，不在本车道范围。
5. **PDA 端**：`recordPick` 的 `pickedAt` 接受带偏移量的 ISO-8601 串并原样落库，
   PDA 端可直接透传自己的时钟；序列号走 `serialIds` 数组，逐件核对。