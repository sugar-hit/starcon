# 7.6.2.1 库存健康（阶段 D3，L3 锚点 7.6.2）

> 分支 `feat/7-6-2-1` · 迁移 `V66__warehouse_inventory_health.sql` · 蓝图为 7.6.2「库存健康」

## 决策摘要

**蓝图六个指标，逐个核实数据源后只有两个算得出来，其余四个如实标记为不可用。**

| 指标           | 结论                   | 依据                                                                                             |
| -------------- | ---------------------- | ------------------------------------------------------------------------------------------------ |
| 库存准确率     | **可算**（本模块重点） | 7.1.2.2 核对（V54）优先、7.4.2.1 盘点（V59）回落，两个数据源都已交付                             |
| 负库存发生次数 | **可算，恒为 0**       | V1/V2 的 `CHECK(on_hand>=0)/CHECK(reserved>=0)/CHECK(available>=0)` 结构上排除；测试实跑 DB 证明 |
| 周转率         | **不可用**             | 依赖 13 销量，warehouse 不持有销售事实                                                           |
| 缺货率         | **不可用**             | 分母「有需求却没货」必须来自销售/订单事实                                                        |
| 超储率         | **不可用**             | 需求基线来自销售；`inventory_policy.max_on_hand` 是配置上限，口径不同，不拿它冒充                |
| 呆滞占比       | **不可用**             | 依赖同批在途的 7.6.1（V65），口径归属该模块，不抢跑                                              |

**「按类目打分」不提供**：`sku_ref` 与 `inventory_balance` 上都没有类目列，类目真相在 catalog，
跨服务不得 join（AGENTS.md 模块边界纪律）。宁可少一维，不用仓库的分数冒充类目的分数。

### 关键 trade-off

1. **假指标比没有指标危险**。四个算不出的指标照样占一行、照样带 `basis` 与 `blockedByModule`，
   但**不带值**。DB 层用 CHECK 钉死「AVAILABLE 必须有值且必须有 `metric_source`」「UNAVAILABLE 必须有
   `blocked_by_module`」，从数据层杜绝「标记为不可用却带着一个数」。
2. **打分只对可算指标加权，并把覆盖度一起返回**。六个指标只算得出一个时，那个分数必须让人看出它
   不是「整体健康度」。配套地，**准确率没有证据时评级强制为 `UNVERIFIABLE`** —— 负库存恒为 0 会白送满分，
   照常评级就会出现「从没核过账但显示健康」这种最危险的结果。这条是实现中途补上的：最初版本会报
   HEALTHY，集成测试抓到后改为按准确率是否有证据封顶。
3. **红线无证据时也阻断**。蓝图只说「低于阈值应阻断」，但结算场景下「无法证明准」与「不准」必须同等对待，
   否则「从没核过账」会成为绕过红线的最短路径。理由文案明确区分「低于阈值」与「无证据」。
4. **准确率取窗口内最后一次核对批次，不取平均**。重复核对同一批余额行会让分母被数好几遍，
   平均出来的数没有口径；历史批次不丢，它们在趋势里。

## 数据模型：三张表而不是一张

一次评估 = **1 行头 + 6 行指标 + 1 行结论**。

最初想把头与指标塞进一张表，集成测试直接暴露了问题：6 行指标各带一份重复的得分，
而得分是这 6 行**算出来的**结果 —— 同一份得分出现 6 份副本，改一处就要改六处，迟早对不上。
拆成 `inventory_health_assessment`（头）/ `inventory_health_metric`（明细）/ `inventory_health_verdict`（结论）。

**问题清单不单独存表**：它是指标的确定性函数，存一份就多一个可能与指标漂移的真相来源。
读路径用同一套口径从落库的指标行重算，保证「页面上看到的扣分」与「列表里的问题」永远说同一件事。

## 准确率口径（全模块唯一一套）

1. **核对口径（优先）**：窗口内**最后一次**该范围的批次，
   `(checked_balance_count − discrepancy_count) / checked_balance_count`。
   全租户口径只认 `scope_type='ALL'`，仓库口径只认该仓自己的批次 —— SKU/法人范围的批次分母只覆盖
   一部分余额行，当成全范围准确率会把偏高的覆盖率说成「账很准」。
2. **盘点口径（回落）**：窗口内全部已录实盘数的盘点行，`variance = 0 的行数 / 已录行数`。
   逐行取自不可变的 `stocktake_line`，跨单相加不会重复计数。用 `line.warehouse_id` 过滤而不是单的
   `scope_key`（盘点单可以按库位下派）。
3. 两个都没有 → `NO_DATA`。

`metric_source` 记下用了哪个口径，分母/分子跟着值一起落库，否则事后没人能复核这个分数怎么来的。

## 红线的接口边界（蓝图红线）

蓝图：「库存准确率是硬指标，低于阈值应阻断结算与对账」。

**本模块提供的是可查询、可复现、带证据的判定，不代下游执行阻断。** 阻断动作发生在 12 财务结算与
7.1.2.2 对账各自的边界内 —— warehouse 既不知道它们的单据状态，也不应该替它们扣单。
下游要接的是「结算前先问一句 `/gate`，被阻断就拒绝」。

**接入状态：未接入。** 13 分析与 1 工作台均未交付，本次不谎称已接入。

## 关键实现坑（都踩过）

1. **`?::timestamptz` 不能落在绑定序列末位** —— 会让 jOOQ 整体错位一格。
   `persistAssessment` 把 `created_at` 提到第 1 位就是为了这个；`persistVerdict` 的
   `evaluated_at` cast 在第 16 位、末位是普通 uuid 绑定。
2. **纯文本 SQL 的 `OffsetDateTime` 绑定必须显式 cast**，否则 PG 按 varchar 推断，
   报 `column "window_from" is of type timestamp with time zone but expression is of type character varying`。
3. **`with win as (...)` CTE 必须在 FROM 里出现**（`cross join win`），
   否则 `relation "win" does not exist`。定义了常量却忘了加前缀 —— 一次编译期查不出来。
4. **列一律按列名读**（`row.get("col")`）而不是按下标：纯文本 SQL 的列序在改查询时会悄悄漂移。
5. **`metric_source` 定 varchar(16) 会截断 `INVENTORY_BALANCE`（17 字符）** —— DB 层报错才暴露。
6. **测试夹具的落点时间**：默认评估窗口是 `[now-30d, now)` 且右边界截断到秒，
   夹具直接写 `now()` 会因为带毫秒而落在窗口外，「夹具自己把自己排除掉了」，断言变成莫名其妙的 NO_DATA。

## 文件清单

| 类型     | 路径                                                                                                              |
| -------- | ----------------------------------------------------------------------------------------------------------------- |
| 迁移     | `backend/services/warehouse/src/main/resources/db/migration/V66__warehouse_inventory_health.sql`                  |
| 模型     | `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryHealthModels.java`                       |
| 服务     | `.../InventoryHealthService.java`                                                                                 |
| 端点     | `.../InventoryHealthController.java`                                                                              |
| 后端测试 | `.../InventoryHealthIntegrationTest.java`（21 例）                                                                |
| 契约     | `packages/api-contracts/src/warehouse.openapi.json`、`catalog.ts`（新增 10 个 `InventoryHealth*` 类型、5 条路径） |
| 页面     | `apps/web/src/app/inventory/health/page.tsx`                                                                      |
| 组件     | `apps/web/src/components/inventory/inventory-health.tsx`                                                          |
| 前端模型 | `.../inventory-health-model.ts` + `.test.ts`（22 例）                                                             |
| 白名单   | `apps/web/src/lib/api-proxy.ts`（`health` 加进 `inventory/(?:…)` 组内）                                           |

### 端点

| 方法 | 路径                                 | 权限                         |
| ---- | ------------------------------------ | ---------------------------- |
| GET  | `/warehouse/inventory/health`        | `warehouse.inventory.read`   |
| GET  | `/warehouse/inventory/health/trend`  | `warehouse.inventory.read`   |
| GET  | `/warehouse/inventory/health/gate`   | `warehouse.inventory.read`   |
| GET  | `/warehouse/inventory/health/issues` | `warehouse.inventory.read`   |
| POST | `/warehouse/inventory/health/assess` | `warehouse.inventory.update` |

## 验证（真实结果）

- `./mvnw -pl services/warehouse -am test -Dtest=InventoryHealthIntegrationTest` → **21 通过**
- `./mvnw verify` → 全绿
- `pnpm typecheck` / `pnpm lint` → 通过
- `pnpm test` → **1164 通过**（基线 1142，+22 恰为本模块新增；无回归）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 成功，`/inventory/health` 预渲染
- `node tools/module-progress/sync.mjs --check` → 一致
- api-proxy 反向抽取断言：5 条 health 路径全部放行、**0 回归**、
  `inventory/adjustments` / `inventory/reservations` 仍被挡住、`inventory/health/xyz` 未被过度放行

## 后续待办

1. **13 销量交付后**：三个销售依赖指标（周转率/缺货率/超储率）转 `AVAILABLE`。
   `HealthMetricType` 已有 `blockedByModule`，届时只需补数据源，不必改表结构。
2. **7.6.1 库龄与呆滞交付后**：呆滞占比转 `AVAILABLE`。
3. **按类目打分**：需要 catalog 授权 API 暴露「SKU → 类目」，或 3.3.1.1 类目树提供事件投影。
   在那之前本模块不提供 CATEGORY 粒度。
4. **红线接入下游**：12 财务结算与 7.4.2.1 对账流程在前置校验处调 `/gate`。
   接入前 `blocked=true` 只是一个待执行信号。
5. **调度器**：评估目前只能手动触发（`POST /assess`），尚未挂定时任务。
   `evaluated_by` 列已为将来指向服务账号留好位置（与 V54 `triggered_by` 同一纪律）。
