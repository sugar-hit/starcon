# 7.1.3.1 多仓分布视图（阶段 D3）

> 模块 ID `7.1.3.1`，蓝图 07 卷「库存核算系统」第 142–147 行。
> 车道 `feat/7-1-3-1`，迁移版本 V55（预分配，避开了 7.2.2.1 的 V56 与 7.3.1.1 的 V57）。

## 决策摘要

### 1. 自有账本量与平台观测量**永不相加**，并用 DB 约束把它变成强制的

蓝图点名「**FBA 库存是平台仓观测值，不是自有账本**」，10 号卷对外部仓有同一句要求
（「必须与自营库存分开存储与对账，不能覆盖」）。本模块的做法不是靠约定，而是三层强制：

- `platform_stock_observation` 是独立表，与 `inventory_balance` / `stock_bucket` **没有外键关系**，
  任何地方都不相加；
- `guard_platform_warehouse_no_ledger`：平台仓写 balance / bucket 触发器直接抛错；
- `guard_platform_kind_change`：已有非零余额的仓不能 UPDATE 成平台仓；
- `guard_observation_requires_platform_warehouse`：非平台仓写观测同样被拒。

响应里两组列互斥有值（`onHand`… 对 `platformOnHand`…），合计也分 `own*` / `platform*` 两组。
**页面上不存在「总库存」这一列** —— 账本 100 与观测 80 加出来的 180 没有任何一个来源背书。

### 2. 同一仓的多个观测来源**分行**，不合并

两个来源各报一个「这个仓有多少」，加总出来的数谁也不认。真出现分歧时，相加会掩盖冲突，
所以矩阵行键是「仓库 × 来源」，让分歧暴露成两行。

### 3. 周转天数：算不出就返回 `null` + 中文原因，不返回 0

- **自有仓** = 在手 ÷ 日均发货出库速度。分母来自真实 `inventory_entry` 的
  `kind='SHIPMENT'` 负向 `on_hand_delta`，回看窗口 1–180 天（默认 30）。
  **刻意排除 `TRANSFER_OUT`**：调拨出库不是消耗，而本模块的下游正是 7.5.1.1 调拨 ——
  把调拨混进出库速度，会让调拨决策把自己的依据算错。
- **平台仓** 走观测快照差分（`basis=PLATFORM_OBSERVATION_DIFF`），因为它没有自有出库流水。
  这是**另一个口径**，界面上单独标注，不可与自有仓的数直接比大小。
- 出库速度为 0、观测样本 < 2 条、跨度为 0、净减少为 0 —— 全部留空并写明是哪一种。

### 4. 矩阵数量列取自 `inventory_balance` 而不是 `stock_bucket`

7.1.1.2 的桶层守恒允许一条 balance **没有任何 bucket**（「尚未拆到库位」是合法状态）。
若矩阵只从 bucket 聚合，这类仓会在调拨决策里凭空消失 —— 而那正是最不该消失的一类
（明明有货、只是没拆货位）。所以：

- 数量列 → `inventory_balance`（V1 注释「权威可用量」）；
- `stock_bucket` → 只提供分解度（bucket / 库位 / 批次条数）与最近变动时间，
  并以 `bucketCount=0` 如实标出未分解状态。

### 5. 三个蓝图没说清、按实测 schema 补上的边界（全部有记录，不是臆造）

| 缺口               | 实测情况                                                                                               | 本模块的做法                                                                                                                                                                             |
| ------------------ | ------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 仓库性质           | `warehouse`（V1）**没有**任何 type/kind 列，本地仓/海外仓/FBA/三方仓这个分类不存在于数据里             | V55 加 `kind` 受控列（`DEFAULT 'LOCAL'` 兼容存量）。warehouse 是本服务**自有**主数据，补自己域里的属性不越界                                                                             |
| SPU 维度           | `sku_ref`（V1）**没有** spu 列；`spu` 权威在 catalog（`sku.spu_id`），但跨服务不能 JOIN 也不能复制档案 | V55 在 `sku_ref` 加**可空** `spu_id` 引用登记（与该表既有的 id/code/name 快照同一性质）。存量行为 NULL 是**如实**（确实不知道），覆盖缺口由 `skuScope.unregisteredSpuCount` 在响应里报出 |
| 上游 10 外部仓观测 | **未交付**（仓库里没有任何 observation 类表）                                                          | warehouse 侧自建观测表 + 观测录入端点作为真实数据来源。10 号卷的采集器交付后调它即可；在此之前可人工录入。页面上的平台数量永远来自真实落库的行                                           |

## 关键 trade-off

- **矩阵不做分页**。行数 = 仓库数（有界，几十到几百），分页只会让「翻页后某仓不见了」
  看起来像丢数据。排序因此在 Java 侧做（`InventoryDistributionService` 的 `rows.sort`）。
- **观测 append-only，不做 upsert**。覆盖掉历史就没有断货风险分析的依据了；
  同源同时点重复投递撞唯一约束（让调用方知道重试没生效），不同时点各存一条。
- **观测录入权限用 `warehouse.inventory.update`** 而不是 read。它确实写库，
  挂成 read 等于声明读权限能改数据。
- **行内入口如实标注可用性**：调拨（7.5.1.1）未交付 → `available=false` 但仍给路由契约；
  明细（7.1.1.2）与流水（7.1.2.1）已交付 → `available=true`。把已交付的标成未交付
  就是在页面上说假状态。
- **SPU 下拉用独立端点** `/warehouse/inventory/distribution/spus`，
  **没有**往共用的 `SkuRef` 上加 `spuId` —— 那个 DTO 被多个模块共用，
  而 SPU 引用登记是本模块引入的列，改共用 DTO 会让所有依赖方被动升级。

## 踩到并修掉的坑（留档，避免下次重犯）

1. **`CREATE FUNCTION ... AS $$ ... END $$;` 后面再写 `LANGUAGE plpgsql;` 是语法错误。**
   那个分号已经把语句结束了，`LANGUAGE` 变成独立语句，报 `no language specified`。
   正确写法是 `END $$ LANGUAGE plpgsql;`（分号只在最后）。
2. **排序比较器写反**：`Boolean.compare(!PLATFORM.equals(kind), ...)` 会把平台仓排到最前。
   自有仓在前应为 `Boolean.compare(PLATFORM.equals(kind), ...)`。
3. **`timestamptz` 的 cast 不能落在绑定序列末位**，会让 jOOQ 整体绑定错位一格。
   出库速度查询里把两个 `?::timestamptz` 排在 SKU 列表绑定**之前**。
   绑定值用 `OffsetDateTime`，不用 `Timestamp.valueOf(LocalDateTime)`（后者静默偏 −28800s）。
4. **Spring 把 SQLSTATE 42501 翻译成 `BadSqlGrammarException` 并丢掉驱动原文**，
   断言权限拒绝要看 `.rootCause()` 的 message，只看顶层只会看到「bad SQL grammar」。
5. **V2 的桶层守恒要求 bucket 的在手与预占**都**等于 balance**，只对齐在手会被 DB 以
   「不平」拒绝（夹具里补 `reserved` 绑定）。
6. **`numeric(19,4)` 取出来是 scale 4**，`BigDecimal("80").equals(new BigDecimal("80.0000"))`
   为 false。断言要用 `toPlainString()` 或 `isEqualByComparingTo`。
7. **Spring 单例服务不能持有请求态字段**。第一版把「本租户仓库档案缓存」提成实例字段，
   是并发的两个租户请求之间的串数据隐患，已改为方法内局部变量。

## 文件清单

### 后端（warehouse 服务）

| 文件                                                                                                 | 说明                                                                                  |
| ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `services/warehouse/src/main/resources/db/migration/V55__warehouse_multi_warehouse_distribution.sql` | 新建。三件事 + 四条 DB 级强制 + RLS/GRANT                                             |
| `services/warehouse/src/main/java/com/starcon/warehouse/DistributionModels.java`                     | 新建。DTO 全部带 `Distribution` / `MultiWarehouse` / `PlatformObservation` 前缀避重名 |
| `services/warehouse/src/main/java/com/starcon/warehouse/InventoryDistributionService.java`           | 新建。jOOQ 显式 SQL 聚合，无 JPA                                                      |
| `services/warehouse/src/main/java/com/starcon/warehouse/InventoryController.java`                    | 改。+3 端点、+1 构造参数                                                              |
| `services/warehouse/src/test/java/com/starcon/warehouse/InventoryDistributionIntegrationTest.java`   | 新建。37 例，Testcontainers PG16，池压到 1                                            |

### 契约（codegen 产物，未手改）

`packages/api-contracts/src/catalog.ts`、`warehouse.openapi.json`、`identity.openapi.json`
（后两个由 codegen 统一重写，属预期）。语义比对：**新增 10 个类型、消失 0 个**。

### 前端

| 文件                                                           | 说明                                  |
| -------------------------------------------------------------- | ------------------------------------- |
| `apps/web/src/components/inventory/inventory-distribution.tsx` | 新建。矩阵页（两个区块分区展示）      |
| `apps/web/src/app/inventory/distribution/page.tsx`             | 新建。独立路由，接 `?skuId=` 定位参数 |
| `apps/web/src/components/inventory/inventory-model.ts`         | 改。+fetch/展示口径 helpers           |
| `apps/web/src/components/inventory/inventory-model.test.ts`    | 改。+6 例                             |
| `apps/web/src/components/inventory/inventory-workspace.tsx`    | 改。+第五个页签                       |

### 文档

- `docs/product/capability-blueprint/module-progress.md` —— 只改 7.1.3.1 一行的「状态」「证据」两格
  （路由列仍为 `—`，L3 锚点行本身没写路由；页面地址写在证据列里）。
- 本文件。

## 端点

| 方法 | 路径                                         | 权限                         | 说明                                          |
| ---- | -------------------------------------------- | ---------------------------- | --------------------------------------------- |
| GET  | `/warehouse/inventory/distribution`          | `warehouse.inventory.read`   | 多仓对照矩阵。`skuId` 与 `spuId` 互斥必居其一 |
| GET  | `/warehouse/inventory/distribution/spus`     | `warehouse.inventory.read`   | SPU 下拉                                      |
| POST | `/warehouse/inventory/platform-observations` | `warehouse.inventory.update` | 录入平台仓观测                                |

## 验证

全部为真实命令输出（详见交付报告）：

- `cd backend && ./mvnw verify` —— BUILD SUCCESS，warehouse 158 例（8+31+37+21+30+30+1）全绿
- `pnpm typecheck` —— 3/3 成功
- `pnpm lint` —— 3/3 成功（仅另一车道 `variant-combination-workspace.tsx` 的既有 warning）
- `pnpm test` —— 1046 通过 / 58 文件；**基线自测 1040**（stash 后还原基线实测），净增 6 即本模块新增用例
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` —— Compiled successfully，含 `/inventory/distribution`
- `node tools/module-progress/sync.mjs --check` —— 一致

## 后续待办

1. **7.5.1.1 调拨**交付后，把 `InventoryDistributionService.actions()` 里 TRANSFER 的
   `available` 置 true。矩阵行已经带 `warehouseId` 定位参数，调拨页可直接接上。
2. **10 号卷外部仓观测**交付后，其采集器应调
   `POST /warehouse/inventory/platform-observations`，并把 `source` 词表对齐到真实平台编码
   （当前是受控但非封闭的短字符串，租户可扩展）。
3. **`sku_ref.spu_id` 的存量回填**需要走 catalog 授权 API 或事件，不能在 warehouse 里直连
   catalog 库。本模块只负责把缺口如实报出来，不负责补齐。
4. **7.2.2.2 安全库存**会消费平台库存做「平台断货风险」判断，届时可以直接用
   `platform_stock_observation` 的历史序列（append-only 已为它留好了）。
5. 观测新鲜度目前只用 UI 文案提示（「数据较旧」）。若要做**阻断式**保护（例如快照超过 N 小时
   禁止发起调拨），那是 7.5.1.1 的决策，本模块不越界做。
