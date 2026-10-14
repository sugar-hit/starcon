# 2026-10-14 7.1.1.3 可用量计算

## 决策摘要

在车道 `feat/7-1-1-3`（worktree `.worktrees/7.1.1.3`，基线 `integration/module-dev` @ `bddb48d`）实现
能力蓝图 **7.1.1.3「可用量计算」**：统一计算并展示可售量，输出「可售量数值 + 构成拆解（每一项扣了多少）」。

三条关键决策：

1. **不建任何表，预分配的 V111 刻意没用。** 可售量是 `stock_bucket`（V2）× `inventory_quality_status`（V1）×
   `warehouse_location` / `inventory_lot`（V2）× `inventory_policy`（V1）四份**已在库事实**在读时派生出来的数。
   存一张 `available_quantity` 表等于把派生数据复制一份，而每次入库 / 预占 / 释放 / 盘点都要去同步它 ——
   漏同步一次就会出现一个比库存更笃定的错数，而错的那个恰好是**防超卖用的**。
   判断与 8.5.2.1（库位利用率）一致：派生数据存下来只会比它描述的库存更早过期。
   蓝图要的是「统一计算并展示」，统一的办法是**只有一个地方算**，不是多一张表。

2. **「不重复扣减」实现为互斥且穷尽的五片，而不是连减五刀。**
   待检、残次、未上架、批次冻结都是把「可售基数」的**筛选范围**缩小，不是减法。所以五片互斥且穷尽：

   ```
   合格可售 + 待检 + 残次 + 未上架/不可发货 + 批次冻结 == Σ available
   ```

   连减会让残次货被扣两次（先当不合格排除、再当扣减项）。后三片都带 `q.role='SELLABLE'` 前缀，
   专门保证与前两片互斥 —— 待检品放在不可拣库位上时**只落待检片**，这条有专门的反例测试。
   响应里的 `sliceBalanced` 是这条恒等式的判定位：口径坏了当场看得见，而不是等它变成一次超卖。

3. **预占只从 `reserved` 进算式，不 join `reservation_allocation` 重算一遍。**
   V2 的触发器已把 reservation 同步进 `stock_bucket.reserved`，V107 的分配表把「圈在哪」钉到仓/货位/批次三维。
   两处各算一次就是重复扣减，而且两边不一致时（分配表有终态行、余额还没回写）可售量会凭空少一块。
   分配表在本模块的正确用法是**溯源**（7.2.1.2 的输出），不是本模块的输入。

## 完整算式

```
① bucket 可用 = on_hand − reserved        ← stock_bucket.available 生成列，没有第三个来源
② 可售基数     = Σ available，限 role='SELLABLE' ∧ location.pickable ∧ location.status='ACTIVE'
                              ∧ (lot 为空 ∨ lot.status='ACTIVE')
③ 可售量       = max(0, ② − ④)
④ 安全缓冲     = Σ_SKU inventory_policy.safety_stock，按 SKU > WAREHOUSE > DEFAULT 解析
```

② 的两个输入条件不是自选：`warehouse_location.pickable` 在 V2 建表时就写明「可售量计算（7.1.1.3）
要把不可拣库位的数量排除在可发货之外」，`inventory_policy.safety_stock` 在 V1 就为 7.2.2 预留好了。

## 关键 trade-off

- **安全缓冲按 SKU 解析，不按 balance 行。** 若把缓冲 join 进主聚合再求和，一个 SKU 有 N 条 bucket
  就被扣 N 次 —— 这是最易犯且最难被发现的错（夹具上就是 5 条 bucket、缓冲 10，扣 50）。
  代价是缓冲查询走一条独立 CTE 查询，与 8.5.2.1「递归 CTE 只出结构、统计各走独立查询」同一条纪律。
- **SKU 档只计一次、WAREHOUSE 档按仓各计一次。** 安全库存的承诺单位不同：SKU 档是「这个 SKU 全网留 N 件」
  （跨 3 个仓也只能算一份），WAREHOUSE 档是「这个仓给这个 SKU 留 N 件」（3 个仓是 3 份承诺）。
  一个仓对应唯一一条 WAREHOUSE 策略行，所以按仓求和不会重复。
- **`safetyBuffer`（配置值）与 `safetyBufferDeducted`（本次减数）分成两个字段。**
  没配缓冲时 `safetyBuffer = null`（不是 0）、`safetyBufferDeducted = 0`（算式里没有减项）。
  「没留缓冲」与「留了 0 件缓冲」在业务上完全不同，前者要去配策略。
- **缓冲大于可售基数时钳到 0 并把事实报出来**，而不是发一个负的可售量回平台（那是超卖）。
- **端点命名空间用 `/inventory/sellable`，刻意不用 `/inventory/availability`。**
  后者已被 3.5.2.2 的「BOM 展开批量可用量查询」占着；两个语义完全不同的端点挤在一个路径下，
  调用方迟早会把前者当成后者，而后者带安全缓冲与排除口径 —— 那是一次防超卖口径的误用。
- **无 `at` / `asOf` 参数。** 可售量不存历史快照，回看一个时刻没有定义（预占与释放在动）；
  给了这个参数就是在承诺一件系统做不到的事。

## 边界（并行车道）

- **7.2.2.1 渠道可售量策略**：只读取与展示（`bufferRatio` 与本模块的 `safetyBuffer` 分开显示，
  一个是比例一个是件数）。共享池分配与平台回写额仍由它算，不重复实现。
- **7.2.1.3 预占过期释放（并行车道）**：**不实现释放，也不对预占用量做自动过期扣减**。
  仍在 HELD 且已超时的预占照扣（此刻确实占着货），`releaseNote` 如实说明。
  它落地后由它把 `reserved` 减下去，本模块算式一行都不用改 —— 这也正是「预占只当 `reserved` 列」的理由。
- **7.3.1.2 效期策略**：按日期的效期剔除（`expires_on < 今天`）**刻意不做**，报 `available=false`。
  没有效期策略时凭空剔货，会让一个还没设效期的租户少一批可售。

## 文件清单

后端（warehouse 服务，无迁移文件）：

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AvailableQuantityModels.java`（新）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AvailableQuantityService.java`（新）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AvailableQuantityController.java`（新）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/AvailableQuantityIntegrationTest.java`（新，24 例）

前端：

- `apps/web/src/components/inventory/inventory-sellable-model.ts`（新）
- `apps/web/src/components/inventory/inventory-sellable-model.test.ts`（新，33 例）
- `apps/web/src/components/inventory/inventory-sellable-workspace.tsx`（新）
- `apps/web/src/app/inventory/sellable/page.tsx`（新）
- `apps/web/src/lib/inventory-sellable-api-proxy.test.ts`（新，30 例）
- `apps/web/src/lib/api-proxy.ts`（改：warehouse 正则第一处 `inventory\/` 分组加一个顶层分支）

契约（codegen 产物，只由脚本重生成）：

- `packages/api-contracts/src/contracts/warehouse.ts`
- `packages/api-contracts/src/warehouse.openapi.json`

台账与会话：

- `docs/product/capability-blueprint/module-progress.md`（只改 7.1.1.3 那一行）

## 命名与契约纪律

- 6 个新 record 全部 `AvailableQuantity*` 前缀。springdoc 用**简单类名**做 `components.schemas` 键，
  重名会被**静默覆盖**（别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红）。
  本仓库已知高危裸名 `Occupancy` / `LocationRow` / `LocationTotals` / `Turnover` /
  `AvailabilityQuery` / `AvailabilityRow` / `AvailabilityView` 一个都没复用。

## api-proxy 插入位置（下一轮求并要定位）

`apps/web/src/lib/api-proxy.ts` warehouse 那条巨型单行正则里，**第一处** `inventory\/` 分组，
紧跟 `sellable-policies(...)` 之后、`stocktakes(...)` 之前：

```
...|policies(?:\/[0-9a-f-]+)?|sellable-policies(...)|sellable(?:\/(?:capabilities|skus))?|stocktakes(...)
```

**只改了第一处**：该正则里 `inventory\/` 分组出现两次且内容逐字相同（历史重复），
alternation 首个命中即返回，第一处生效即足够，第二处不动以免求并时多一处冲突点。
放在 `sellable-policies` 之后无歧义（`sellable` 是它的前缀，靠 `$` 锚点回退）；
`s` / `l` 非十六进制字符，`sellable` 永远匹配不上任何 `\/[0-9a-f-]+` 分支，
不存在「被当成 id=capabilities 凭空放行」的风险。守卫测试含 10 条反向断言（未登记形状仍 404）。

## 验证

- L0：`./mvnw -q -o -pl services/warehouse -am compile` 通过；`pnpm typecheck` 全绿
- L1：`./mvnw -pl services/warehouse -am test`（24 例新增集成测试 + 全量 warehouse 套件）
- L1：`pnpm vitest run` 两个新增测试文件 63 例；既有 6 个 api-proxy 套件 196 例仍全绿（无回归）

## 后续待办

1. **契约仍需在调度器统一重生成。** 本车道用 `--spec-dir`（其余 5 个服务取自已提交的
   `packages/api-contracts/src/*.openapi.json` 快照 + 本车道新导出的 warehouse spec）重生成，
   产物里其余 5 个服务逐字节不变。但四条车道各自导出的 spec 合并后仍应由调度器再跑一次全量 codegen
   与跨服务同名碰撞闸。
2. **7.2.1.3 落地后**，本模块无需改动算式；可考虑在 `releaseNote` 里补一句实际释放生效的日期。
3. **7.3.1.2 效期策略落地后**，按日期的效期剔除应作为**第六片**并入五片恒等式
   （而不是在②之外另做一次扣减），否则会破坏现有的互斥穷尽性。
4. `docs/product/capability-blueprint/07-inventory.md` 的 L3 锚点仍是 7.1.1，
   可用量页已挂在 `/inventory/sellable`；是否要在总览页（7.1.1.1）加「可售量」下钻入口待定。