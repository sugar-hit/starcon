# 7.8.2.2 缺货与饥饿预警（D3 阶段，L3 锚点 7.8.2）

> 车道 `feat/7-8-2-2`，worktree `.worktrees/7.8.2.2`，基线 `integration/module-dev` 的 `1bb270a0`。

## 决策摘要

蓝图 §7.8.2.2 只有四行，成立的与否全在一句加粗上：

> **「没货卖」和「没分到货」是两种问题**，处置完全不同，不能混在一个缺货告警里。

所以本模块的第一件事不是写判定，而是**先把「不混」变成结构**：

1. **两张互不共表的表**（`starvation_alert` / `stockout_alert`）—— 不是「一张表 + 一个
   `type` 列」。两张表连字段名都不共用（除生命周期那几列），所以「混在一个告警里」
   在 schema 层面就做不到。
2. **两套端点**（`/starvations` 与 `/stockouts`）、**两套状态枚举**、**两套建议生成逻辑**。
3. **一条硬分界线**：饥饿的成立要件里含「观察窗口内存在一轮
   `allocation_run.available_quantity > 0` 的分摊」—— 货在、这家店分不到 ⇒ 饥饿；
   货都没有 ⇒ 缺货。某 SKU 已**全网缺货**时，它的店铺**被排除**在饥饿清单之外
   （并计入 `excludedByStockoutCount`，让它们不无声消失）：那种情况处置是补货，
   调保底没有意义。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 一张表加 `type` 列 vs 两张表 | **两张表** | 两个方向的粒度不同（(SKU×店铺) vs (SKU×仓)）、字段不同、处置不同。塞进一张表就必然出现「一堆可空列 + 一个 type」 |
| 清单是**实时算**还是**落库** | **落库**（评估动作写表，列表读表） | 「预警」意味着有人认领、有人处置、事后要复盘。实时算的清单无处安放处置状态 |
| 缺货的「总可用量」取哪个数 | **`stock_bucket.available` 生成列**（= on_hand − reserved） | 蓝图卷 07 的硬约束「`available = on_hand − reserved`，**没有第三个来源**」。刻意**不**重算 7.1.1.3 的「可售量」五片口径（那套含安全缓冲与待检/残次/未上架/冻结批次排除，是全系统单一口径来源，复制一份只会漂移）—— 本模块报的是**账本可用量**，两者不是同一个数，页面同时给出分摊时的总可用量供交叉核对 |
| 仓级断货与全网缺货 | **两个取值**（`WAREHOUSE_STOCKED_OUT` / `NETWORK_STOCKED_OUT`） | 前者是「货在别的仓」⇒ 处置是仓间调拨；后者才是蓝图说的「真正的全网缺货」⇒ 处置是补货。合成一个取值就等于把两种处置混回去 |
| 「长期」怎么表达 | **按分摊轮次**，不按天 | 一天分三次还是一个月分三次，「连续三轮没分到」的分量完全不同。默认观察最近 5 轮、零分配轮次占比 ≥ 0.5、覆盖率 < 0.5 判 `STARVED` |
| `UNKNOWN_DEMAND` 的行要不要落库 | **落** | 只落「有问题的」会让「判不出来」的那部分在清单上彻底消失 —— 那正是「把不知道当成 0」的老毛病。它的处置是去登记销量，本身就该被看见 |
| 建议的形态 | **结构化指针**（目标对象 + 定位键 + 当前值 + 建议值 + 理由） | 「建议多给点货」没法落地。「把 7.8.1.2 的这档保底从 5 件调到 10 件（每轮应得 10 件）」可以 |
| 本模块要不要替上游改配置 | **不改，只给指针** | 改保底要开新档（7.8.1.2 的档位不可变）、改优先级要改名册（7.8.1.1）、补货属 06 采购域（未交付）。跨模块写配置会越过模块边界纪律 |
| 已处置的预警再次命中 | **自动重开 + `recurrence_count` + 1** | 触发器钉住「不能无痕复活」，于是「这个问题复发过几次」在行上留得下痕迹 |
| 重新评估后不再命中的旧预警 | **自动关闭**（`RESOLVED` + 「重新评估后不再命中」） | 直接删掉等于让复盘无从查证；留着不动又会让「已解决」的行永远挂在待处理里 |
| 权限码 | 复用 `warehouse.inventory.read` / `.update` | 新增要动 identity 的权限真相源，是别的车道的事。评估与认领/处置挂 `update` —— 它们会改变「哪些问题还没人管」这个事实 |
| 契约的 schema 名 | 全部 `Stockout*` / `Starvation*` 前缀 | springdoc 用 record 的**简单类名**做 `components.schemas` 的键，重名会**静默覆盖**别人的契约（7.8.2.1 已经踩过一次：`AllocationRunPage` 撞了 7.8.1.1 的同名 DTO） |

## 未交付的上游（如实标注，不假装有）

| 上游 | 状态 | 本模块怎么表达 |
| --- | --- | --- |
| 店铺绩效（17 绩效域） | 未交付 | `capabilities.performanceAvailable=false` + `ownerModule=17`；本模块只算「长期分不到货」这个**事实**，**不算**绩效影响，影响面标成「不知道」 |
| 补货（6.2.1.2，06 采购域） | 未交付 | `capabilities.replenishmentAvailable=false` + `ownerModule=6.2.1.2`；只给**缺口件数与销量窗口**，不落采购单 |
| 工作台预警（1 工作台） | 未交付 | `capabilities.workbenchAlertAvailable=false` + `ownerModule=1`；预警只在本服务可查 |

## 绑定顺序这个坑（记一笔）

两条检测 SQL 里的 `?` 顺序**不是**按我写绑定列表时的顺序来的 —— CTE 的**书写顺序**决定
绑定顺序：`kept`（`rn <= ?`）排在 `lines`（`?::uuid`）前面，所以 `windowRounds` 排在
`referenceId` 之前；`saleswin` 排在 `ledger` 前面，所以 `windowRounds` 又排在
`warehouseId` 之前。两次都写错，PG 报的是 `cannot cast type integer to uuid` ——
一个**看起来毫不相关**的错（Integer 绑到了 uuid 位）。绑定顺序已写进两处 SQL 的 javadoc。

## 文件清单

后端（warehouse 服务）

- `backend/services/warehouse/src/main/resources/db/migration/V192__warehouse_stockout_starvation_alerts.sql`（新，两张新表 + 5 道 DB 约束 + RLS + 授权，**零 ALTER 既有表**）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/StockoutAlertModels.java`（新，13 个契约 record / 枚举）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/StockoutAlertService.java`（新，jOOQ 显式 SQL 检测 + 落库 + 清单 + 生命周期）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/StockoutAlertController.java`（新，11 个端点 / 10 个路径）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/StockoutAlertIntegrationTest.java`（新，24 例，含多租户三断言）

生成物（禁止手改，由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 产出）

- `packages/api-contracts/src/contracts/warehouse.ts`（**+13 类型 / 0 删除**）
- `packages/api-contracts/src/warehouse.openapi.json`（**+10 paths / +13 schemas，既有条目零改动**）

前端

- `apps/web/src/components/inventory/stockout-alert-model.ts`（新，接口 + 展示口径）
- `apps/web/src/components/inventory/stockout-alert-model.test.ts`（新，23 例纯函数单测）
- `apps/web/src/components/inventory/stockout-alert-workspace.tsx`（新，工作区）
- `apps/web/src/app/inventory/stockout-alerts/page.tsx`（新，主入口）
- `apps/web/src/app/inventory/allocation/stockout-alerts/page.tsx`（新，L3 路由下的第二入口）
- `apps/web/src/app/api/warehouse/stockout-alert-allowlist.test.ts`（新，6 例白名单反向断言）
- `apps/web/src/lib/api-proxy.ts`（改：**新增一条 `services[]` 元素**，插在数组最末尾）

台账与文档

- `docs/product/capability-blueprint/module-progress.md`（改：**只改 `| 7.8.2.2 |` 那一行**的状态与证据两格）
- `docs/sessions/2026-10/2026-10-20-stockout-starvation-alerts.md`（新，本文件）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` 通过；`pnpm typecheck` 3/3 通过
- L1 模块闸：`./mvnw -pl services/warehouse -am test -Dtest='StockoutAlert*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **24 例全绿**（0 失败 0 错误），含租户隔离三断言
  （两租户互不可见 / 伪造 `X-Tenant-Id` 无效 / 池大小 1 归还后 20 轮不串租户）
- 前端：`pnpm vitest run apps/web/src/components/inventory/stockout-alert-model.test.ts` → 23 例全绿；
  `pnpm vitest run apps/web/src/app/api`（**因改了 api-proxy.ts 补跑全量 allowlist 回归**）→ 79 文件 498 例全绿
- `pnpm lint` 3/3 通过（余 4 条 warning 属别的文件的既有 `exhaustive-deps`）

## 后续待办

1. **导航未登记**：与 7.8.1.1 / 7.8.1.2 / 7.8.2.1 一致，本模块的两个页面入口也**不在 nav 里**
   （那三条都不在）。等这族页面一起进 nav 时再一并处理。
2. **绩效影响面仍是「不知道」**：17 绩效域交付后，本模块应把「长期断货天数 / 考核影响」
   接进饥饿清单的严重度与说明 —— 现在只报「长期分不到货」这个事实。
3. **补货落单未接**：6.2.1.2 交付后，`REPLENISH` 建议应当能一键生成采购需求单
   （本模块现在只给缺口件数与销量窗口，**不跨库写**）。
4. **分摊轮次窗口的「长期」是相对的**：默认 5 轮、零分配占比 0.5、覆盖率 0.5 三个阈值
   写在评估请求里但**没有 UI 表单**。阈值可调这件事本身要在界面上露出来才算完整。
