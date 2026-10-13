# 8.4.1.1 组装单（实物套装组装）— D4，warehouse 主责

车道：`feat/8-4-1-1`（worktree `.worktrees/8.4.1.1`，基线 `integration/module-dev`）
日期：2026-10-13

---

## 一、决策摘要

### 1. 建两张新表，而不是复用 V1 的 inventory_transaction

同服务的 `ProcessingOrderService`（6.4.1.1 加工单）刻意**不建表**——加工单据归 trade（V97），
仓库侧只是它的一次库存消费。8.4.1.1 相反：蓝图写死「**输出**：组装单 + 组件出库分录 + 成品入库分录」，
组装单是 warehouse **自己拥有**的现场作业单据，且业务可以不等加工单、直接按「成品 SKU + BOM 版本」发起。
没有独立单据就没有「这张单是谁发的、按哪一版 BOM、组了多少」，BOM 版本也无处挂载
（inventory_transaction 只有 ref_type/ref_id，放不下 BOM 口径）。

所以新建 `assembly_order` + `assembly_order_component`；库存分录仍复用 V1 既有
`inventory_transaction` / `inventory_entry`（ref_type=`ASSEMBLY_ORDER`）。

### 2. 跨服务 BOM：引用键 + 分录快照，**不**跨库 FK / JOIN

BOM 在 catalog 3.5.2（V25 `bom`/`bom_version`/`bom_component`、V26 `bom_explosion_*`），
加工单在 trade 6.4.1.1（V97/V98）——都不是 warehouse 的表。AGENTS.md 明令禁止跨服务
SQL / FK / 共享视图。

做法（仓库既有先例的延续）：
- `bomId` / `bomVersionId` / `bomComponentId` / `referenceId` 全部是**无外键的逻辑引用键**，只作追溯；
- 组装要用的组件明细在自己的 `assembly_order_component` 上落成**冻结快照**：
  `unit_quantity` / `scrap_rate` / `required_quantity` 全部由调用方按 catalog 规则 R7 的结果传入并原样落库；
- 本服务**不重算 BOM、不取整、不跨库回查**（与 trade 侧「R7 结果原样冻结」同一纪律）。

代价是 catalog 改版不会自动反映到已建的单上 —— 这是**有意的**：
已建单的组件明细是「这张单当时按哪一版 BOM 算的料」的凭据，改版不该改写历史。

### 3. 上游来源两种发起并存

`source_system` + `reference_id` 两个字段显式表达，不用「有 reference_id 就当作上游」去猜：

- `TRADE_PROCESSING_ORDER` —— 来自 6.4.1.1 加工单（逻辑 ID，跨服务无外键，仅作追溯）；
- `DIRECT` —— 仓库现场直接按「成品 SKU + BOM 版本」发起。

用 CHECK 钉住两者不能对调（`(source_system='DIRECT') = (reference_id IS NULL)`）：
否则「这张组装单的上游是谁」会有两个答案，其中一个指向空气。

### 4. 虚拟套装：结构上进不来，不是靠 if

蓝图点名「虚拟套装走不到这里」。**去 grep 确认** catalog 侧形状，结论：

- 虚拟套装 = `catalog.bundle`，V25 原文：「**bundle 没有任何 sku 外键 —— 虚拟组合不占 SKU 行**」；
- BOM = `catalog.bom`，**必有 `output_sku_id`**（V25 差异 1：「BOM 有成品，套装没有」）。

所以 `assembly_order.product_sku_id` NOT NULL + 服务层强制落在本服务 `sku_ref`，
虚拟套装在数据形状上就填不出合法的成品 SKU。capabilities 端点把这条边界原样报出来，
前端从 capabilities 读出来显示，不在页面里另编一套说法。

### 5. 两条不变量落成**数据库层**约束（本模块最要紧的部分）

蓝图要求「按 BOM 领用组件、消耗组件库存、产生成品 SKU 库存，**同一事务完成**」。
落成：

- **I1**：组件出库**分录**之和 = 按 BOM 展开的应领量之和
- **I2**：成品入库量 = 组装数量

四层强制，缺一不可：

| 层 | 机制 | 挡住什么 |
| --- | --- | --- |
| ① 行级 | `assembly_order_component` CHECK：`issued_quantity = 0 OR issued_quantity = required_quantity` | 部分领用（把一个套装拆成两半装） |
| ② 单头 CHECK | `status<>'COMPLETED' OR issued=required` 且 `inbound=assembly` | 单头自报数与明细不符 |
| ③ 合计触发器 | `refresh_assembly_order_totals()` —— **单头合计列的唯一写入者** | 应用层写一个「发够了但账上没发」的数 |
| ④ 守卫触发器 | `guard_assembly_order()` 在 BEFORE UPDATE **回读 `inventory_entry` / `inventory_balance`** | **绕过服务层直连 SQL** 造「只入成品不扣组件」「少扣组件」 |

第 ④ 层是关键：如果只在前三层，「不变量」就只是应用层的一行 if，
绕过服务就能造出账实不符的记录。测试 `invariantIsEnforcedByDatabase` /
`emptyOrderCannotBeCompleted` 专门用直连 SQL 造这两种坏数据并断言被拒。

另有 `guard_assembly_component_after_completion()`：单据非 DRAFT 后明细不可再改
（否则可以在完成后追加组件行、或把某行 issued 归零）。

### 6. 不静默取整 —— 这是 I1 能写成严格等号的前提

`inventory_balance` / `stock_bucket` 是 `numeric(19,4)`（V1 已定），
而 BOM 应领量是 `numeric(19,6)`。若允许取整：

- 向下取整 = **凭空吞掉材料**（账上少领、实体多丢）；
- 向上取整 = **凭空多领材料**。

而且两种都**恰好能让「组件出库 = 应领量」这条不变量看起来成立** —— 数字对上了，物料对不上，
那是最糟的失败模式。所以服务层**拒绝**任何不能被 4 位小数精确表示的量，并指出是哪一行、
建议改组装数量或改 BOM，而不是替运营做主。同样的 `required_quantity = round(required_quantity, 4)`
也进了 DB CHECK，双保险。

---

## 二、关键 trade-off

| 决策 | 放弃的 | 换来的 |
| --- | --- | --- |
| 自建组装单表（不复用 V1 分录表当单据） | 与 `ProcessingOrderService`「不新建表」的简洁一致 | 组装单有独立生命周期、BOM 版本可挂载、业务可不等加工单直接发起 |
| 组件明细落**冻结快照**而不是实时读 BOM | BOM 改版自动生效 | 已建单据的历史可解释；「每张事实表只有一个写入所有者」；无跨库读 |
| 拒绝超 4 位小数的量而不是取整 | 「什么都能提交」的表面体验 | 账实相符；不变量可写成严格等号；错误信息指向具体行 |
| 上游两种发起并存（DIRECT + 加工单引用） | 单一来源的简洁 | 仓库现场不必先开加工单就能组装（蓝图输入只要求「成品 SKU 与 BOM 版本」） |
| 允许取消草稿 | 状态机更小 | 备料阶段「料没到 / 需求变了」是常态；且取消**不动库存**，无账实风险 |

---

## 三、文件清单

### 后端（warehouse）
- `backend/services/warehouse/src/main/resources/db/migration/V103__warehouse_assembly_order.sql`（新建）
  — `assembly_order` / `assembly_order_component` / `assembly_order_code_seq` /
  `refresh_assembly_order_totals()` / `guard_assembly_order()` /
  `guard_assembly_component_after_completion()` + FORCE RLS + GRANT
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AssemblyOrderModels.java`（新建）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AssemblyOrderService.java`（新建）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AssemblyOrderController.java`（新建）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/AssemblyOrderIntegrationTest.java`（新建，21 例）

### 契约（codegen 产物，未手改）
- `packages/api-contracts/src/contracts/warehouse.ts`
- `packages/api-contracts/src/warehouse.openapi.json`

### 前端
- `apps/web/src/app/warehouse/assembly-orders/page.tsx`（新建）
- `apps/web/src/components/warehouse/assembly-order-workspace.tsx`（新建）
- `apps/web/src/components/warehouse/assembly-order-model.ts`（新建）
- `apps/web/src/app/api/warehouse/assembly-order-allowlist.test.ts`（新建，6 例）
- `apps/web/src/lib/api-proxy.ts`（**只追加** `assembly-orders` 一支 + 注释，未动他人分支）

### 文档
- `docs/product/capability-blueprint/module-progress.md`（只改 8.4.1.1 一行的「状态」「证据」两格）

---

## 四、验证方式与结果

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 | `backend && ./mvnw -q compile` | BUILD SUCCESS |
| L0 | `pnpm typecheck` | 3/3 tasks successful |
| L1 | `backend && ./mvnw -pl services/warehouse -am test` | **945 例 0 失败 0 错误，BUILD SUCCESS** |
| L1（基线对照） | 同上，但把 V103 + 本模块测试类移开 | 924 例 0 失败（BUILD SUCCESS） |
| 白名单 | `pnpm vitest run apps/web/src/app/api/warehouse apps/web/src/app/api/trade apps/web/src/lib/api-proxy*.test.ts` | 13 文件 / 199 例全绿 |
| 白名单（单文件） | `pnpm vitest run apps/web/src/app/api/warehouse/assembly-order-allowlist.test.ts` | 6/6 全绿 |

### 一次 flake 的如实记录（`SellablePolicyIntegrationTest.断言三_连接池归还后不串租户`）

第一次全量跑出 **945 例 1 失败**，失败点是**本车道不拥有的** `SellablePolicyIntegrationTest`
（`Expected size: 1 but was: 0`）。没有直接归因为「既有 flake」就放过，而是做了四步排查：

| 检查 | 结果 |
| --- | --- |
| 移开 V103，单独跑 `SellablePolicyIntegrationTest` | 17/17 通过 |
| 恢复 V103，单独跑 `SellablePolicyIntegrationTest` | 17/17 通过（**说明不是迁移引入**） |
| 两类同跑（`AssemblyOrderIntegrationTest,SellablePolicyIntegrationTest`） | 38/38 通过 |
| 恢复本模块后**复跑全量** | **945 例 0 失败，BUILD SUCCESS** |

结论：是**非确定性的既有 flake**（全量负载下偶发），不是本模块引入的确定性破坏。
根因未定位（`断言三` 在池大小为 1 的连接上做 20 轮双租户交替往返），**不在本车道范围内，
未修改他人测试文件**。若调度器在集成头上仍偶发撞到它，属该模块车道的事。

### 本车道测试期间同时存活的 PG 容器数

surefire 默认 `forkCount=1 / reuseForks=true`（顺序执行），每个测试类**自己 start 一个
`postgres:16-alpine` 并在 `@AfterAll` 停掉**。因此本车道测试期间同时存活的测试 PG 容器
**峰值 = 1**（中途抽查 `docker ps` 观测到的正是 1 个 `postgres:16-alpine`，外加若干
`testcontainers/ryuk`；ryuk 与并发跑的其他车道实例混在一起，无法按车道归属）。

结束后已确认：所有本车道测试 PG 容器**均被 ryuk 回收**，`docker ps` 只剩长期存在的
`starcon-mig-v2` 开发库（Up 6 days，非本车道创建）与一个 3 个月前就退出的
`starcon-postgres`。

多租户三断言（AGENTS.md 硬要求，Testcontainers 真实 PostgreSQL 16，连接池压到 1）：

1. **两租户互不可见** —— `tenantACannotSeeTenantB`：B 自建单，A 列表 0 条、按 id 查 404
2. **伪造 tenant header 无效** —— `forgedTenantHeaderIsIgnored`：带 `X-Tenant-Id: <B>` 请求，
   身份仍由 Bearer 决定，读到的是 A 的数据
3. **连接池归还后不串租户** —— `connectionPoolDoesNotLeakTenant`：池压到 1，20 轮 A/B/无上下文往返

时间字段断言：`timestampRoundTripsWithoutDrift` **断言写回的值**（`issued_at` 的 instant 与
offset 都与返回的 `occurredAt` 相同），不是「没抛异常」。

---

## 五、过程中真实踩到并修掉的坑（留给下一条车道参考）

1. **`call(auth, "read")` 会拼出 `warehouse.read`** —— `AccessService.require` 要求权限码是
   `warehouse.<模块>.<动作>`。写成 `call(auth, "read")` 时只读身份拿到 403 而测试报「expected 200」，
   根因在控制器不在权限模型。既有 controller 一律写 `call(auth, "processing.read", …)`。
2. **`AssemblyOrderIssueInput` 可空而服务层直接 `input.notes()`** —— HTTP 侧没有 body 也是合法请求，
   服务层必须自己兜住，否则单测直连服务就 NPE。
3. **`stockExact` 用 `RoundingMode.UNNECESSARY` 会抛裸 `ArithmeticException`** —— 必须 catch 并换成
   人话（告诉用户是哪一行、为什么不能取整、该怎么改）。
4. **api-proxy 的 warehouse 分支上 `outbound-orders` / `printing` / `consignments` / `stocktake-variances`
   在集成头上就未登记**（HEAD 基线即为 404，非本车道引入）。做「不误伤」反向锚点时**不能用它们**，
   否则会写出一条永远失败的断言。已用「基线 vs 当前正则逐路径 diff」确认本车道未改变任何兄弟路径的行为。

---

## 六、后续待办

1. **8.4.2.1 拆解单**（同组兄弟，未开发）：把已完成的组装单回拆成组件。需要把料退回 + 货取出，
   组件质量状态由接收方决定（不能默认全部回可售）。capabilities 已显式报 `available=false`。
2. **组装单的现场作业流**：拣货 / 复核 / 上架的任务流未建模，本模块只到「组件出库 + 成品入库」为止。
3. **api-proxy 的既有路由缺口**：`outbound-orders` / `printing` / `consignments` / `stocktake-variances`
   四支未登记，应由对应模块车道自己补（不在 8.4.1.1 范围内，未修）。
4. **与 trade 6.4.1.1 的分工可以再收一收**：目前 trade 的加工单仍通过
   `POST /warehouse/processing-orders/issues` 做库存动作，而组装单是 warehouse 自有单据、
   可以用 `source_system=TRADE_PROCESSING_ORDER` 引用加工单。两条路径并存是有意的
   （加工走委外/工厂，组装走仓库现场），但等真实业务跑起来后值得复核一次是否有重复建模。