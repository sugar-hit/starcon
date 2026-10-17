# 9.5.2.1 履约质量（阶段 D4 · trade 服务）

> 车道 `9.5.2.1`，worktree `.worktrees/9.5.2.1`，分支 `feat/9-5-2-1`，
> 基线 `integration/module-dev` @ `a052a025`。

## 决策摘要

本模块是蓝图里明确的**读模型 / 统计模块**：不推进任何状态机、不回传平台、
不改一张既有表。六个指标全部由 jOOQ 显式 SQL 现算，**不落表** —— 落了表就会与事实漂移，
而指标看板的价值恰恰在于它随时能对着事实重算一遍。

### 六个指标的口径（只在两处定义：V155 第二节 + `QualityMetric` 枚举）

| 指标 | 分子 | 分母 | 时钟 |
| --- | --- | --- | --- |
| 发货及时率 | `ship_sla_state='ON_TIME'` 的票 | `ON_TIME` + `OVERDUE`（**不含 UNSET**） | `order_timeliness_fact.handed_over_at` |
| 完整发货率 | 已发量 ≥ 下单量的**订单行** | 有履约进度投影的订单行 | `order_fulfillment_line_progress.shipped_at` |
| 错发漏发率 | 有登记或系统推导少发的**去重订单** | 窗口内有进度投影的去重订单 | 分子 `exception.reported_at` / 分母 `progress.shipped_at` |
| 取消率 | 有 `status='CONFIRMED'` 取消的去重订单 | 窗口内 `submitted_at` 非空的订单 | 分母 `sales_order.submitted_at` |
| 纠纷率 | 有 DISPUTE 信号的去重订单 | 窗口内有交接事实（`shipment_record`）的去重订单 | 分母 `shipment_record.shipped_at` |
| 退货率 | 有 RETURN 信号的去重订单 | 与纠纷率**完全相同** | 同上 |

比率一律 `numeric(19,6)`（0~100 百分数量纲），由 SQL 的
`case when 分母 = 0 then null else round(分子*100.0/分母, 6) end` 现算。
**分母为 0 返回 null**，不是 0 也不是 1 —— 报 0 会被读成「及时率 0%」，那是结论不是缺口。

## 关键 trade-off

1. **发货及时率复用 9.5.1.1 的 `order_timeliness_fact`，不另起一套时限口径。**
   同一件事两个算法必然对不上；UNSET（未登记时限）独立报数、不进分母。

2. **店铺维度只能按平台级 `order_source` 判，且不为它 ALTER 既有表。**
   `sales_order` 与 `shipment_record` 上都没有 `shop_code`（9.2.1.2 已核实）。
   处理方式是 capabilities 里显式报 `shopCodeAvailable=false` + 点名 D5；
   D5 落地后由同步器补 shop_code，**枚举与统计逻辑都不用改**。
   跨车道 DDL 回归面保持 0。

3. **人员维度走 `fulfillment_request_work_order.assignee_ref`，
   经 `fulfillment_request.order_ref` 对齐 `sales_order.order_no`。**
   ⚠ `order_ref` 是 9.3.3.1 明确声明「只存引用不 JOIN」的逻辑引用列 —— 这里按租户内
   唯一的 `order_no` 文本对齐，是**一处明确的口径选择**而非事实唯一性：认不上的落
   `UNASSIGNED` 桶并如实报出覆盖量，不猜也不丢弃。一票货多人参与时**同时**落到每个人
   下面（表达「涉及该人员的订单」），所以聚合一律 `count(distinct 计量单元)`，
   展开不会放大计数。

4. **错发当前无法判定，落成租户举证登记；漏发可推导，两者分列。**
   `shipment_record` / `shipment_record_parcel` 只有件数与总量、**没有 SKU 列**，
   履约进度又按 `order_line_id` 建行 —— 「发出去的是不是订单行那个 SKU」在集成头里
   没有任何数据源。漏发则能从 `shipped_qty < ordered_qty` 推导出来。
   下钻里 `WRONG_ITEM` / `MISSING_ITEM_DECLARED` / `MISSING_ITEM_DERIVED` 三类分列，
   举证材料不同，不合并。

5. **纠纷率与退货率当前读不到真实售后数据。**
   卷 11 在别的服务的独立库，按模块边界纪律禁止跨服务 SQL / 视图 / FK。
   照抄 9.2.2.2（V150）的解法落成租户可维护信号表，并在 capabilities 显式报
   `crossServiceReadAvailable=false` + `upstreamDelivered=false`，
   **不让调用方从 404 或空数组里猜哪一环没通**。

6. **「与 16 联动预警」做成租户可配置的阈值 + append-only 预警留痕。**
   16 与 17 均未交付，不假装已对接。分母为 0 的指标**不产生预警** ——
   「没有样本」推成「指标恶化」是预警体系里最危险的错，由 DB 触发器
   `guard_fulfillment_quality_alert_basis` 与 CHECK 双向钉死。

7. **每条查询只绑定两个参数。** 窗口在每条 SQL 里以
   `with win as (select ?::timestamptz as f, ?::timestamptz as t)` 出现一次，
   谓词引用 `(select f from win)`。这样指标口径内嵌的窗口条件不需要各自带绑定值，
   参数顺序也不会随口径变化而错位（这是本模块踩过的一个真实坑，见下）。

## 文件清单

### 新增

- `backend/services/trade/src/main/resources/db/migration/V155__trade_fulfillment_quality.sql`
  （5 张表 + RLS + GRANT + 3 个 DB 闸门触发器；零 ALTER 既有表）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentQualityModels.java`
  （`QualityMetric` 枚举承载六条口径定义 + 全部 record 形状）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentQualityService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentQualityController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/FulfillmentQualityIntegrationTest.java`（38 例）
- `apps/web/src/components/orders/fulfillment-quality-model.ts`
- `apps/web/src/components/orders/fulfillment-quality-model.test.ts`（16 例）
- `apps/web/src/components/orders/fulfillment-quality-workspace.tsx`
- `apps/web/src/app/orders/fulfillment/quality/page.tsx`
- `apps/web/src/app/api/trade/fulfillment-quality-allowlist.test.ts`（8 例）

### 修改

- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`
  （codegen 产物，trade 侧纯新增 9 条路径）
- `apps/web/src/lib/api-proxy.ts`（**共享文件**：trade 段外科式最小追加
  `fulfillment/quality` 一支，不并进 timeliness / shipment-records 任一组）
- `docs/product/capability-blueprint/module-progress.md`（仅 9.5.2.1 那一行的状态与证据）

## 本车道踩到并修掉的三个真坑（都写进了代码注释）

1. **jOOQ 不给无类型占位符推断类型**：`?` 绑 `OffsetDateTime` / `BigDecimal`
   会被当成 varchar 发过去，PG 报「column is of type timestamp with time zone but
   expression is of type character varying」。所有这类位置必须写 `?::timestamptz` /
   `?::numeric`（与 9.5.1.1 的 `?::timestamptz` 同一纪律）。
2. **`values` 里的标量子查询占位符同样绑不了**：`values (?,?,?,(select order_no from sales_order where id = ?),...)`
   直接 `bad SQL grammar`。改成先读出订单号再作为普通参数插入。
3. **MockMvc 不对查询参数做 URL 解码**：`%2B` 会原样进 `OffsetDateTime` 解析器；
   用 `URLEncoder` 更糟（它按表单规则把空格编成 `+`）。测试里时刻原样拼即可。

另外两个不是坑但值得记：V99 的 `guard_sales_order_totals` 要求夹具头侧金额与商品行真值
一致；V152 的 `guard_fulfillment_request_ladder` 不允许请求在缺 RESERVE 步时进 WORK。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 | `cd backend && ./mvnw -q compile` | BUILD SUCCESS |
| L1 | `cd backend && ./mvnw -pl services/trade -am test` | **830 例，0 失败**，BUILD SUCCESS |
| 前端 | `pnpm exec tsc --noEmit -p apps/web/tsconfig.json` | 无输出（通过） |
| 前端 | `pnpm vitest run apps/web/src/components/orders/fulfillment-quality-model.test.ts` | 16/16 |
| 白名单 | `pnpm vitest run apps/web/src/app/api/trade/fulfillment-quality-allowlist.test.ts` | 8/8 |
| 白名单 | `pnpm vitest run apps/web/src/lib/api-proxy-union.test.ts` | 246/246 |

⚠ `pnpm typecheck` 里 `@starcon/ui:typecheck` 命中的是**别的 worktree**
（`.worktrees/11.1.1.1/packages/ui`）的 turbo 缓存 —— 按惯例不采信；
`@starcon/web` 那行是本 worktree 现跑的，且另有一条直出的 `tsc --noEmit` 作证。

## 后续待办

- **D5 平台同步落地后**补 `shop_code`：维度枚举 `PLATFORM` 换成 shop_code 取值即可，
  表结构与统计逻辑都不用改。
- **卷 11 售后正式接口到位后**由同步器按同一形状写 `fulfillment_quality_after_sale_signal`
  并把 `signal_source` 改成 `MODULE_11_*`，纠纷率与退货率自动从「租户登记」变成真实数据。
- **16 账号健康 / 17 绩效落地后**由它们读本模块的授权 API 消费
  `fulfillment_quality_alert`；阈值与留痕已经就位，本模块不主动写对方库。
- 错发的 SKU 级来源（9.4 履约反馈 / 10 物流轨迹）接上后，
  `reported_source` 会自动从 TENANT_MAINTAINED 变成 MODULE_*。
