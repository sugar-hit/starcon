# 2026-10-07 调拨单（7.5.1.1）

## 决策摘要

阶段 D3，L3 锚点 7.5.1。车道 `7.5.1.1`，分支 `feat/7-5-1-1`，迁移 `V61__warehouse_transfer_order.sql`
（归属 warehouse 服务 —— 库存域的事实所有者是 warehouse，不是 catalog）。

模块四条核心语义全部落地：六态状态机、发出转入途、分次收货转入目标仓、`expected_version` 乐观锁。

## 关键 trade-off

### 1. 在途放在既有 `inventory_transfer` 上，只加一列

V1 已经建了 `inventory_transfer`，而且 7.1.1.1 总览、7.1.2.2 核对、7.2.2.1 可售量、7.1.3.1 多仓分布
**四个模块**统一按 `sum(quantity) where status='IN_TRANSIT'` 求在途。另建一张在途表 = 给同一件事制造两个
真相来源。所以本模块复用它，只 `ALTER TABLE ADD COLUMN line_id`（可空，不改任何既有列的语义与约束），
用来把每一腿接回调拨明细行。

**代价与收益**：既有四个模块一行代码都不用改，分次收货下它们的求和仍精确。

### 2. 部分收货「拆行」而不是「把余量归零」

最初的设计是 `quantity` 走余量口径、收完就归零。**被 V1 的两条 CHECK 打回**：

- `CHECK(quantity>0)` —— 不允许归零
- `CHECK((status='IN_TRANSIT')=(received_at IS NULL))` —— 非在途状态**必须**带了结时刻

最终做法：部分收货时**原行保留未到的剩余量、另起一行记已收到的那一段**（新行 `ref_type='TRANSFER_RECEIPT'`）。
这样两条 CHECK 都成立，`sum(quantity) where status='IN_TRANSIT'` 仍是精确未达量。
结案的 SHORTAGE 则把在途行置 `CANCELLED` 并带结案时刻。

**这条是本次最花时间的地方**：CHECK 冲突只在提交时才暴露，报错信息（`inventory_transfer_quantity_check`）
完全指不到「你应该拆行」这个真正的解法。

### 3. 发出明细从分录派生，不从在途行拼

拆行之后，`inventory_transfer` 里已经读不出「这一趟原本发了多少」。发出明细因此改为从 append-only 的
`inventory_entry` 反推（`sum(-on_hand_delta)`，按 sku + 质量状态对回明细行）。
这本来就该是权威出处 —— 迁移注释里也这么写。台账是事实，在途表只是余额行。

### 4. 守恒式用 DB 触发器钉死，不用只在服务层 if

`guard_transfer_line_conservation`（DEFERRABLE INITIALLY DEFERRED）强制
**已发出 = 已实收 + 已确认差异 + 仍在途**。用延迟约束是因为一次收货本来就要「改在途行 + 改明细行 + 写分录」
三件事，即时触发器会在事务中途对着半成品报差异。

它挡住的具体事故：实收后忘冲在途、短少被无声抹掉、重复发货或漏记在途行。
同理 `guard_transfer_order_state` 强制状态与三个时间戳自洽。

### 5. 差异不抹平、不挪回源仓

结案时把「已发出但永远收不到」的部分记成 `SHORTAGE` 事件（原因必填：丢失/破损/拒收/发运即短少），
在途行转 `CANCELLED` 归零。短少的货既不进源仓也不进目的仓 —— 它就是真的丢了，如实留痕。
7.5.2.1 跨法人调拨需要定价与内部结算，是另一条车道，本模块**不做**。

### 6. 与 7.1.3.1 对齐：调拨出库不是销售出库

7.1.3.1 多仓分布刻意把 `TRANSFER_OUT` 排除在周转天数的出库速度之外（调拨决策要用那一列算依据，
混进去会让调拨把自己的依据算错）。本模块因此**不**把调拨记成 `SHIPMENT`，只记 `TRANSFER_OUT`，
并有一条测试断言「调拨全流程不产生任何 SHIPMENT 事务」。

### 7. 库位层同步扣减（V2 守恒触发器的约束）

V2 的 `guard_bucket_conservation` 是延迟约束触发器：只要 balance 行已分解到库位，改余额就必须同事务改
bucket。所以 ship 时按库位逐个扣（`available>0`，按最近变动时间先后），receive 时落到指定库位。
`last_movement_at` 用 `greatest(业务时间, 原值+1µs)` 保证严格前进（V2 会挡倒退）。
balance 未分解到库位时（V1 允许的合法状态）只改余额。

## 踩到的坑（都记一下）

1. **GRANT TABLE 不带序列**：漏 `GRANT USAGE ON SEQUENCE` 时建单报
   `permission denied for sequence`，错误文案完全指不到授权上。
2. **jOOQ 纯文本 SQL 解析**：`nextval(...)::text` 拼字符串、`case when ?>?0`、
   聚合 SELECT 里放引用未分组列的相关子查询（`subquery uses ungrouped column`），三者都被打回。
   结论：纯文本语句里不写表达式类型推断，值都在 Java 侧算好再绑。
3. **FORCE RLS 下测试里裸查表会静默返回空**：没绑 `app.current_tenant` 的查询看不到任何行，
   断言会变成「一切都等于 0」而看起来通过。所有裸查都要包进 `access.run`。
4. **api-proxy 正则的分组层级**：给 `inventory/` 组加子路径时，若把外层捕获组的 `)` 提前关掉，
   `warehouses` 这类顶层路径会全部静默 404。必须逐条比对正则，不能只看新路径通不通。
5. **catalog `ZombieCandidateIntegrationTest` 是既有 flaky**：`最后维护时刻走timestamptz往返` 断言
   毫秒级精度，会出现 `...018Z` vs `...019Z` 的 1ms 漂移。已在干净基线上复现（与本模块无关）。

## 文件清单

**迁移**

- `backend/services/warehouse/src/main/resources/db/migration/V61__warehouse_transfer_order.sql`

**后端**

- `.../java/com/starcon/warehouse/InventoryTransferModels.java`（DTO 一律带 `TransferOrder`/`Transfer` 前缀）
- `.../java/com/starcon/warehouse/InventoryTransferService.java`（jOOQ 显式 SQL；`deriveStatus` 是状态机唯一真相）
- `.../java/com/starcon/warehouse/InventoryTransferController.java`（8 个端点）
- `.../test/java/com/starcon/warehouse/InventoryTransferIntegrationTest.java`（20 例）

**契约（codegen 产物，禁止手改）**

- `packages/api-contracts/src/warehouse.openapi.json`
- `packages/api-contracts/src/catalog.ts`（新增 22 个类型，消失 0 个）

**前端**

- `apps/web/src/app/inventory/transfers/page.tsx`
- `apps/web/src/components/inventory/inventory-transfer.tsx`
- `apps/web/src/components/inventory/inventory-transfer-model.ts`
- `apps/web/src/components/inventory/inventory-transfer-model.test.ts`（19 例）
- `apps/web/src/lib/api-proxy.ts`（放行 transfers 五个读形状 + 四个动作，刻意无 DELETE 段）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（把新路由逐条钉住）

**台账与会话**

- `docs/product/capability-blueprint/module-progress.md`（只改 7.5.1.1 的状态与证据两格）
- `docs/sessions/2026-10/2026-10-07-transfer-order.md`

## 验证

- `cd backend && ./mvnw verify`：catalog 段的 `ZombieCandidateIntegrationTest` 因 1ms 漂移 flaky 中断，
  **已在干净基线上复现，既有问题**；本模块单独 `./mvnw -o -pl services/warehouse verify` 全绿（209 例）。
- `pnpm typecheck` / `pnpm lint` / `pnpm test`：全绿。基线量得 1056 例 → 本车道 1075 例（+19）。
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`：通过，产出 `/inventory/transfers`。
- `node tools/module-progress/sync.mjs --check`：一致。

## 后续待办

- **7.5.1.2 在途库存**：本模块已把在途挂准（`inventory_transfer` 余量 + 明细行守恒），7.5.1.2 可以直接在上面做
  起止仓/批次/预计到达的清单与逾期预警，并补「在途缺口必须扣减」的补货口径。
- **批次级拆分**：调拨按蓝图契约是 SKU × 数量、不按批次拆分。收货时 `lotId` 可选（给了落该批次，
  不给落无批次 bucket）。批次级拆分属 7.3.1.x 的演进范围，届时发出侧要改成按批次分配而不是按库位先到先出。
- **库位分配策略**：当前发出按库位 `available>0` + 最近变动时间先后扣减（简单 FIFO），
  真正的库位分配树属 08.1.2.x，落地后应替换掉这里的排序。
- **catalog flaky**：`ZombieCandidateIntegrationTest` 的毫秒级断言建议改成容差比较，
  否则会持续随机打断整条流水线的 verify。
