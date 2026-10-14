# 7.1.2.1 库存流水台账（warehouse，阶段 D3）

车道 `feat/7-1-2-1`，基线 `integration/module-dev` @ `409ff79`。

## 决策摘要

按蓝图 7.1.2.1 交付「按时间顺序展示所有库存业务 + 分录下钻 + 期间净变动汇总」。
落点是 warehouse 服务，且**是一个纯只读模块** —— 蓝图原话「流水只追加不修改，错误用反向分录纠正，
原记录永久保留」决定了它的形状。四件套：迁移 `V51`（只加索引，不建表）、
端点 3 个、页面 `/inventory/ledger` + `/inventory` 第三个页签、测试 21 例（后端）+ 16 例（前端）。

## 关键 trade-off

### 1. 不建新表，只补查询索引

V1 已有 `inventory_transaction` / `inventory_entry` 两张 append-only 事实表，带
append-only 触发器、FORCE RLS，且对 `warehouse_app` REVOKE 了 UPDATE/DELETE。
"只追加不修改"在 DB 层已经成立，本模块**不提供任何写端点**，连"冲正"也不提供
（写一条相反符号的分录属库存业务写入方的职责）。
期间净变动**不入库**：它是 Σ 分录，现算即可；存一份累计值等于制造第二个真相来源，
7.1.2.2 核对时反而无法判断该信谁。
V51 只补四组索引（occurred_at 倒序翻页、kind、ref_type、带 INCLUDE 的 entry 聚合索引），
两个方向都可达：正向走 业务单→分录→余额，反向靠 V1 已有的 balance 侧索引。

### 2. 粒度：一行 = 一笔业务，数量是整笔的净变动

这是本模块最容易算错、也最容易引发后续争议的一处，决定写进了代码注释而不只是文档：

- 行 = `inventory_transaction`，数量列 = 该笔**全部**带符号分录之和；
- SKU / 仓库筛选是**存在性过滤**（"这笔业务碰没碰过它"），用 `exists(...)` 表达，
  刻意不进主查询的 WHERE；
- 筛选维度上的量另给 `entryOnHandDelta`。

为什么不让 SKU 筛选把行的量切一半？因为行代表业务单，把它的量按筛选条件切一半之后，
**这行就不再等于任何一件真实发生过的事**。代价是带筛选时两个口径会不等，
所以响应里同时给两套并算出 `onHandConserved`，把差摆到台面上而不是让人猜。

### 3. 守恒不变量即 7.1.2.2 的核对基线

无 SKU/仓库筛选时，行口径之和与分录口径之和**必然相等**，这个等式直接算成响应里的
`onHandConserved` / `reservedConserved`，而不是只写在测试里。下游 7.1.2.2 拿它与
`inventory_balance` 快照比对即可。

### 4. 空分录的业务单单独报数，不藏

V1 没有保证"业务单一定有分录"，一条分录都没有的单是写入方中途失败的痕迹。
行查询用 `left join`，汇总里 `emptyTransactionCount` 单独计数，界面上出告警条。

### 5. 用户数据范围真的生效（不只是页面上写着）

蓝图把「用户数据范围」列为台账输入之一。SELF 范围的授权会让台账只列本人操作过的业务，
`actorId` / `selfOnly` 均由控制器从**已认证身份**推导后传入，不接受任何请求参数 ——
否则"只看我自己的流水"会变成一个能越权的开关。
DEPARTMENT 在库存上没有维度，退化为租户全量，这一点由响应里的 `dataScopeNote` 如实说明。

### 6. 盘点与组装不给造标签

蓝图列了八类业务（入库/出库/预占/释放/调整/盘点/组装/调拨），而 `inventory_transaction.kind`
的 CHECK 只有八个码且不含独立的盘点/组装 —— 写入方当前记成 `ADJUSTMENT`。
枚举跟着 DB 约束走，界面上也不造筛不出来的类型，调整这一项显式标注「含盘点 / 组装」。
要拆成独立类型需改 V1 的 CHECK，属跨模块变更，不在只读台账的改动范围内。

### 7. 跨租户下钻返回 404 而非 403

RLS 下查不到就是查不到；403 会泄露"这笔业务存在"。

## 文件清单

新增：

- `backend/services/warehouse/src/main/resources/db/migration/V51__warehouse_inventory_ledger.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryLedgerModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryLedgerService.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryLedgerIntegrationTest.java`
- `apps/web/src/components/inventory/inventory-ledger.tsx`
- `apps/web/src/app/inventory/ledger/page.tsx`

修改：

- `InventoryController.java`（3 个只读端点 + 时间解析 + 数据范围推导）
- `InventoryBucketService.java`（7.1.1.2 的「查看流水」入口翻成 available=true）
- `InventoryBucketIntegrationTest.java`（对应断言：只有 7.1.2.1 这条变可用）
- `inventory-model.ts` / `inventory-model.test.ts` / `inventory-workspace.tsx`
- `packages/ui/src/lib/task-pages.ts`（`/inventory/ledger` 标记为已实现）
- `packages/api-contracts/src/*`（codegen 产物，纯新增 7 个类型、0 删除）
- `docs/product/capability-blueprint/module-progress.md`（7.1.2.1 行的状态与证据）

## 踩到并修掉的问题

1. **相关子查询漏 join `inventory_balance`**：维度条件的 `exists(...)` 里
   连了 sku_ref / warehouse 却没连 balance 本体（外层别名子查询看不见），直接报
   `missing FROM-clause entry for table "bal2"`。别名参数化后这个洞才暴露出来。
2. **`count(*)` 数成了分录条数**：总笔数最初套了带 `left join` 的 FROM，
   一笔跨 3 个 SKU 的业务会被算成 3 笔，翻页页数随即算错。改为只查 `inventory_transaction`。
3. **聚合出来的时间列取不到 `OffsetDateTime`**：`min/max(occurred_at)` 在纯文本语句里
   jOOQ 拿不到列类型，返回的是 `Instant`，`r.get(field, OffsetDateTime.class)` 抛 ClassCastException。
   加了 `moment()` 统一收口。
4. **时间断言要比较瞬间而不是墙钟**：`timestamptz` 读回按会话/JVM 时区呈现，
   同一瞬间在 UTC 与 +08:00 下是两个不同的 `OffsetDateTime`。
   比 `Instant` 才是"有没有偏 28800s"这件事。（顺带：AssertJ 对 `OffsetDateTime`
   断言的 `isEqualTo(Instant)` 也会内部强转报错，两边都转成 Instant。）

## 验证

- `backend/ ./mvnw -pl services/warehouse -am verify` → 91 例全绿（本模块新增 21 例）
- `pnpm typecheck` / `pnpm lint` → 绿
- `pnpm test` → 934 例全绿（基线 918，+16，无下降）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 绿，`/inventory/ledger` 已产出
- `node tools/module-progress/sync.mjs --check` → 一致

**基线既有失败（非本车道引入）**：`identity` 的 `MemberOnboardingIntegrationTest` 7 例 409，
在 `git stash -u` 清空本车道改动后于基线提交上**同样复现**，所以全量 `./mvnw verify`
会在 identity 段中断，warehouse 段需单独跑（本车道 91 例全绿）。

## 后续待办

- **7.1.2.2 账实核对**直接依赖本模块的 `onHandConserved` / `entryOnHandDelta` 与
  `inventory_balance` 快照比对，可作为定时任务常驻运行。
- **盘点 / 组装要独立成类型**的话，需改 V1 的 `inventory_transaction.kind` CHECK 约束（跨模块变更）。
- **台账行的路由列仍是 `—`**：`sync.mjs` 的路由取自蓝图 L3 锚点 `7.1.2 库存流水`，
  该行没有路由；给它加 `→ /inventory` 会连 7.1.2.2 一起变成 `/inventory`
  （而 7.1.2.2 并未开发），故未改蓝图。页面地址在证据列里已写明。
- 蓝图中"组装"业务目前无写入方，本模块只读不写，不受影响。
