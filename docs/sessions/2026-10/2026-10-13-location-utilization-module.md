# 8.5.2.1 库位利用率（D4，L3 锚点 8.5.2）

车道：`feat/8-5-2-1`（worktree `.worktrees/8.5.2.1`，基线集成头 `integration/module-dev` @ `6273501`）

## 决策摘要

蓝图职责：「分析库位占用：容量利用率、周转率、空置库位、爆满库位、低效库位，给出调整建议。
输入：库位与库存占用；出库频次。输出：利用率报表 + 热力图 + 建议。」
关系段点名「上游 = 8.1.2.1；下游 = 8.1.2.2 自动分配优化。**库位热力数据同时优化拣货路径**
（高频商品放近出口），一举两得。」

### 1. V109 预分配版本号：刻意留空，不建迁移

本模块是**纯分析/报表**，三样输入全在既有事实表里：

| 输入     | 真相来源                                                                        |
| -------- | ------------------------------------------------------------------------------- |
| 容量     | `warehouse_location.capacity numeric(19,6)`（8.1.2.1 的 V105 补的列）           |
| 占用     | `stock_bucket`（V2）                                                            |
| 出库频次 | `inventory_transaction(kind='SHIPMENT')` + `inventory_entry`（V1，append-only） |

建议是**读时派生**的：存一张「建议表」只会让它比它描述的库存更先过期。
也没有「为了有自己的表」而新建 —— `inventory_entry` 的写入所有者是 7.1.2.1，
本车道代写一条库位级流水会直接违反「每张事实表只有一个写入所有者」。

### 2. 关键发现：出库频次在库位粒度上**系统里没有记录**

`inventory_entry` 是 **balance 粒度**（仓库 × SKU × 法人 × 质量状态），表上**没有 `location_id`**。
「这一件是从哪个库位拣走的」这件事，集成头里没有任何一张表在记。

所以口径只能是「SKU 粒度真实、库位粒度摊分」：

```
库位出库量 = Σ_SKU( 该 SKU 本仓窗口内出库量 × 该库位占该 SKU 全仓在手量的比例 )
```

这个口径的关键性质是**可加**：同一 SKU 在各库位的占比之和恒为 1，所以
`Σ库位出库量 == 仓库真实出库量`，既不重复计数也不漏计。
（对照 8.1.1.1「平台仓观测值不相加」—— 那里是**会**重复，这里必须**配平**，同一个坑的两面。）

粒度以字符串 `outboundFrequencyGrain` 随响应返回，不只写在注释里。

### 3. 三处分母边界一律 null

| 情形                           | 结论                                               | 为什么不是别的值                                                                  |
| ------------------------------ | -------------------------------------------------- | --------------------------------------------------------------------------------- |
| 容量未量（`capacity IS NULL`） | `capacityUtilization = null`                       | 缺的是**知识**；给 0 会被读成「量过，是空的」                                     |
| 容量量出来是 0                 | `capacityUtilization = null` + `capacityZero=true` | 缺的是**定义**（除数是 0）；给 0 会被读成「一个都没放」，给 100% 是替物理事实编数 |
| 在手为 0                       | `turnover = null` + `turnoverComputable=false`     | 出库 0 ÷ 在手 0 是**除以零**；给 0 会说「周转很慢」，而事实是「压根没货」         |

合计利用率另有第四道：**仅在全部库位都量过容量且容量和 > 0 时**才给，
否则给 `utilizationComputable=false`，界面显示「仅覆盖 N/M 个库位」。

### 4. record 名：10 个 `LocationUtilization*`，开工前逐个扫到 0 冲突

刻意不复用仓库已有的裸名：`Occupancy`（8.1.2.1）、`LocationRow` / `LocationTotals`（8.1.1.x）、
`Turnover`（`InventoryDistributionService` 私有 record）。
springdoc 用**简单类名**做 `components.schemas` 的键，重名会被**静默覆盖** ——
上一批 `38cc6e1` 记的真实事故：单测全绿、只有契约比对才红。

### 5. 命名空间：`/warehouse/location-utilization`

刻意**不**挂 `location-trees`（维护档案，语义是改）、`facilities/{id}/locations`（分页清单）、
`/warehouse/locations`（7.1.x 只读下拉参照项）。
权限**只复用** `warehouse.facilities.read` 且**无任何写端点**。

## 关键 trade-off

- **不建库位级出库流水表**（哪怕它能让「逐库位实测」成立）：那是 7.1.2.1 的写入所有权范围，
  越界代写违反模块边界纪律。摊分 + 把粒度说破，是当前唯一诚实的选择。
- **摊分在 Java 侧而非 SQL 侧**：`skuDetailRows` 取 SKU 级真值，`flowByLocation` 做摊分，
  两步分开让「真实值」与「摊分值」各自可审阅。代价是多一个查询。
- **低效库位的判据是「有货且窗口内 0 出库」**，与「空置」（无货）分开成两类建议：
  前者是货不动，后者是位空着，处置动作完全不同。
- **热力色阶放在前端**：颜色是呈现责任且要跨模块复用同一套色板；后端只给 0..100 的
  `heat` 与 `metric`（强度来自哪个真指标），保证「这个格子为什么红」可解释。
- **热力 `IDLE` metric 只在「没量过容量且没货」时出现**；「量过容量且空」走
  `CAPACITY_UTILIZATION` 且值为 0 —— 因为那是**关于事实的真断言**，而前者连相对值都没得比。

## 文件清单

**后端（新增，未动任何既有文件）**

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LocationUtilizationModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LocationUtilizationService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LocationUtilizationController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/LocationUtilizationIntegrationTest.java`（20 例）

**迁移**：无（V109 留空，理由见上）

**契约（codegen 产物，非手改）**

- `packages/api-contracts/src/contracts/warehouse.ts`（+9 类型，纯新增）
- `packages/api-contracts/src/warehouse.openapi.json`

**前端（新增）**

- `apps/web/src/app/warehouse/facilities/[id]/utilization/page.tsx`
- `apps/web/src/components/warehouse/warehouse-location-utilization-workspace.tsx`
- `apps/web/src/components/warehouse/warehouse-location-utilization-model.ts`
- `apps/web/src/components/warehouse/warehouse-location-utilization-model.test.ts`（19 例）

**前端（共享文件，仅追加）**

- `apps/web/src/lib/api-proxy.ts`：只追加 `location-utilization` 一个顶层分支；
  `location-trees` 分支**逐字节未变**（脚本比对 old/new 子串相同，插入点之前 6883 字符整段相同）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`：追加 3 例

**台账**

- `docs/product/capability-blueprint/module-progress.md`：只改第 305 行（8.5.2.1）的状态与证据两格

## 后续待办

- **8.1.2.2 自动分配**可消费本模块的「空置库位 / 爆满库位」建议做分流上架；本模块
  `capabilities.autoAllocationAvailable` 恒为 `false` + `ownerModule` 指认，交付后翻开关。
- **8.3.2.1 拣货路径优化**可消费 `SKU_DISPERSION` 与 `HIGH_FREQUENT_NON_PICKABLE` 两条建议；
  本模块只指出问题并指认归属，不越界执行。
- 若将来 7.1.2.1 在 `inventory_entry` 上补了库位维度（或新增 append-only 的库位级流水），
  本模块的摊分口径可换成实测，**届时 `outboundFrequencyGrain` 字符串必须同步改** ——
  它是使用方判断这个数字可不可信的唯一依据。
- 「高频商品放近出口」里的**距离**目前无真相来源（无库位坐标/出口表），
  本模块只做到「离散度 + 可拣性」这两个可测维度；真正的路径优化属 8.3.2.1。
