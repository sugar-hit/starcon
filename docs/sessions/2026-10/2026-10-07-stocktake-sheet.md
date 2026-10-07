# 2026-10-07 · 7.4.2.1 盘点单

## 结论摘要

7.4.2.1 已交付：迁移 `V59__warehouse_stocktake_sheet.sql`、8 个端点、工作区 `/inventory/count`、
集成测试 19 例 + 前端纯函数 21 例。Flyway 版本按调度器预分配取 **V59**（未自行找最大版本号）。

蓝图对这个模块只点了一条红线，其余都是围绕它展开：

> 「**盘点时点必须冻结账面数**，否则边盘边卖会产生虚假差异。」

这条决定了整个模块的形态：**冻结必须落成数据，不能是查询**。

## 关键 trade-off

### 1. 冻结 = 复制成行 + DB 触发器钉死，而不是实时查余额

「冻结」有两种实现：

| 实现 | 账面列的数据来源 | 盘点期间卖货后 |
| --- | --- | --- |
| (A) 实时查 `stock_bucket` | 查询 | 账面列被改写 → 虚假差异 |
| (B) 复制成行 | 数据 | 账面列不动 |

取 (B)，并且**不只靠应用层自觉**：`stocktake_line_frozen` 触发器把 `book_on_hand` 与八个维度列
钉成不可更新。拿到连接直接 UPDATE 也改不动。

**为什么值得多写一个触发器**：盘点差异最终变成调整单并追责到人，而「账面数在盘点期间被改过」
正是最容易被质疑、也最难自证清白的一类争议。做成数据库不变量之后，「这张单的账面数是发起那一刻
的数」是一句可以直接查 `pg_trigger` 确认的话，而不是一句需要信任应用代码的承诺。

### 2. 差异是 generated column，人工赋值不存在这条路

`variance numeric(19,4) GENERATED ALWAYS AS (counted_qty - book_on_hand) STORED`。
手工填差异等于允许出现一个解释不了的数。测试里有一条专门验证直接 `update ... set variance`
被 PG 拒绝。

`counted_qty` 为 NULL（未录入）时 variance 自然为 NULL —— 「还没盘」与「盘出 0」必须可区分，
写成 0 会让两者变成同一个值，而后续动作完全相反（前者要派人盘，后者进差异处理）。

### 3. 进度、合计、任务状态全部派生，表里不存

`stocktake_task` 只存「派给谁」这一件它独有的事实，不存状态列；三个进度计数与三列合计全部
由明细行现算。理由：另存一份就要在每次录入后同步，漏同步一次就会出现「任务显示已完成但还有行
没盘」和「合计与明细对不上」这两类说不清的错。

盘点单状态（OPEN/COUNTING/COUNTED）是唯一存下来的进度，因为它要被列表筛选。

### 4. 范围与任务单元是两个独立维度

初版把它们当成同一个东西，结果「按仓库盘 + 按库位发任务」这种**最常见的组合**直接写不出来。
最终 `scope`（哪些 bucket 进这次盘点）与 `unit`（这些 bucket 怎么切任务）完全解耦，两条冻结
SQL 各自接收两个参数。

### 5. 盘点范围只给三种，另外两种如实说明

蓝图列了四种，实际交付：

| 范围 | 状态 | 理由 |
| --- | --- | --- |
| 按仓库 | ✅ 可用 | 本服务自有数据 |
| 按库位 | ✅ 可用 | 本服务自有数据 |
| 按类目 | ⚠️ 枚举保留，**显式拒绝 422** | `sku_ref` 刻意不存类目（V1 文件头），catalog 读取投影未接入 → `UnwiredCatalogProjectionPort`。按 7.1.1.1 已建立的同一套约定：未接入的维度显式拒绝，而不是返回空集（空集会被读成「盘不出东西」） |
| 按 ABC 分类 | ❌ **连枚举都不给** | 全库（warehouse 与 catalog 两侧所有迁移 + `db/schemas`）不存在任何 ABC 字段，也没有动销率/帕累托之类可推导它的原始列 |

ABC 之所以连枚举都不给：臆造一个按金额分位的算法冒充已有数据，会让盘点员以为盘的是公司认定的
A 类货，实际是一个没人批准过的猜测 —— 比明说「没有这个维度」危险得多。它改由
`GET /inventory/stocktakes/capabilities` 如实报出 `available=false` 与原因，页面在「为什么没有
ABC 分类」的折叠区里对用户讲清楚。

### 6. 不接受 `frozenAt` 请求参数

冻结时刻就是发起的那一刻，由服务端取。允许调用方指定时刻等于允许「盘三天前的账」，而账面数与
三天前那一刻的库存无从对齐（中间的交易还没落进这条单）—— 那等于给了一个造虚假差异的入口。

### 7. 扫码做成手工录入 + 条码字段

没有扫码硬件对接协议。凭空造一个扫码流程只会得到一个假的成功提示。条码列让后续接扫码枪/PDA 时
不必改表结构。

## 踩到并修掉的问题

1. **`stocktake_line` 的外键指向尚未创建的 `stocktake_task`** —— PG 不允许。调整建表顺序：
   sheet → task → line。
2. **`stocktake_sheet` 写入少一个占位符** —— 11 列对 10 个表达式，PG 报
   `INSERT has more target columns than expressions`，完全指向不到真正的原因（少了一个 `?`）。
3. **`from stock_bucket k, frozen join ...` 的作用域错误** —— 逗号先把 `k` 划到 JOIN 作用域之外，
   ON 子句引用 `k` 报 `invalid reference to FROM-clause entry for table "k"`。改成末尾显式
   `cross join frozen`。
4. **`select` + 以逗号开头的聚合片段** 拼出 `select, count(...)`。聚合片段改成自足形式。
5. **`where` 拼在 `left join` 之前** —— 拼出 `from ... where ... left join ...`，PG 报
   `syntax error at or near "left"`。
6. **测试夹具违反 V1 的 balance 唯一约束** —— balance 的键是「仓 × 货权 × SKU × 质量状态」，
   我最初给同一 SKU 同一仓建了两条 balance。这正是 V1/V2 的分层口径（余额按仓决策，明细按库位
   拆分），改成一条 balance + N 条 bucket 后通过。
7. **`pg_class` 的列名是 `relrowsecurity` 不是 `rowsecurity`**。
8. **api-proxy 正则少一个右括号** —— 编辑时把 `sellable-policies` 分组的收尾 `)` 吃掉了，
   `Unterminated group`。

## 文件清单

**后端**
- `backend/services/warehouse/src/main/resources/db/migration/V59__warehouse_stocktake_sheet.sql`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/StocktakeModels.java`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/StocktakeService.java`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryController.java`（+8 端点与注入）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/StocktakeIntegrationTest.java`（新增，19 例）

**契约**（codegen 产物，按流程生成不手改）
- `packages/api-contracts/src/warehouse.openapi.json`、`packages/api-contracts/src/catalog.ts`

**前端**
- `apps/web/src/app/inventory/count/page.tsx`（新增）
- `apps/web/src/components/inventory/stocktake-workspace.tsx`（新增）
- `apps/web/src/components/inventory/stocktake-model.ts`（新增）
- `apps/web/src/components/inventory/stocktake-model.test.ts`（新增，21 例）
- `apps/web/src/lib/api-proxy.ts`（放行盘点路径形状）

**台账与会话**
- `docs/product/capability-blueprint/module-progress.md`（仅 7.4.2.1 那一行的状态与证据）
- 本文件

## 验证

- `./mvnw -pl services/warehouse -am test -Dtest=StocktakeIntegrationTest` → 19 例全过
  （真实 Testcontainers PostgreSQL 16、强制 RLS、连接池压到 1）
- 核心用例真的在发起盘点后出了一笔 −30 的出库，再逐列断言账面合计纹丝不动、实盘 95、差异 −5，
  并核对当前余额确实已变成 70 —— 证明那笔出入库真的发生了，不是没生效的空转。
- 租户隔离三断言全跑：两租户互不可见（列表/详情/明细/任务/录入全隔离）、伪造 `X-Tenant-Id`
  无效、A→B→A→B 交替复用同一条物理连接不串租户，并逐张表确认 `relforcerowsecurity`。
- 前端 `pnpm typecheck` / `pnpm lint` / `pnpm test` 全绿；测试数 1040（基线）→ 1061（+21，
  新增 1 个文件），无下降。
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` 成功，产出 `/inventory/count`。

## 后续待办（下游，不是本模块欠账）

1. **7.4.2.2 盘点差异处理** 消费本模块的 `variance` 列：按原因分类、生成调整单走 7.4.1.2 审批。
   本模块刻意**不**替差异下结论，录入实盘也**不直接改库存余额**（蓝图红线）。
2. **ABC 分类**：需要有人先把分类字段落进数据模型（大概率在 catalog 侧），本模块的
   `capabilities` 端点届时会自动报 `available=true`（前提是枚举先加上）。
3. **类目范围**：`CatalogProjectionPort` 的读取适配器接入后即可放行，本模块逻辑不动。
4. 扫码硬件对接（若有）：条码列已就位，只需在录入路径上加设备来源。