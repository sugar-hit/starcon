# 9.5.1.1 时效分析（阶段 D4，L3 锚点 9.5.1）

- 车道：`feat/9-5-1-1`，worktree `.worktrees/9.5.1.1`
- 后端归属：trade 服务；Flyway `V131__trade_order_timeliness_analysis.sql`
- 前端路由：`/orders/fulfillment/timeliness`

## 决策摘要

1. **三段时效的两个端点全部来自同服务既有投影，本模块一次都不自己造时刻。**
   下单取 `sales_order.submitted_at`，出库取 `shipment_pending_unit.packed_at`，
   交接取 `shipment_record.shipped_at`。三个上游（9.4.1.2 / 9.4.1.1 / V99）
   **全在 trade 库内**，所以本模块是本批唯一**零跨服务调用**的模块：
   出库单在 warehouse 库里，而 AGENTS.md 禁止跨服务 SQL，故一条 SELECT 都不碰 warehouse 库。
   集成测试因此也**不需要任何 HTTP 替身** —— 夹具直接以表所有者身份落真库。

2. **时长用整数秒，且由 DB 复算。** 五个时长列全是 `GENERATED ALWAYS ... STORED`
   的 `bigint`；中位数与 P90 用 `percentile_disc` 取离散精确值。刻意不用浮点小时：
   时效是平台算法权重项，浮点累加误差会一路带到阈值判定，而那时没人说得清差在哪。
   **三段各自独立生成而不是相加** —— 一端缺失时整段 UNSET，相加会造出
   「出库缺失但下单到交接有值」的第三种口径。

3. **「下单」取 submitted_at 而不是 created_at。** DRAFT 单的 created_at 是
   有人开始录入的时刻；当它当下单会让「下单到出库」凭空多出一段草稿停留 ——
   那不是履约时效，是人的录入时长。

4. **承诺到货 vs 实际到货：没有可读上游 ⇒ 登记而不伪造。**
   集成头里 10.3 轨迹未交付，自有履约路径上没有任何承诺/实际到货列；
   V126 的 `supplier_promised_ship_at` / `own_delivered_at` 属**代发**（9.6.1.3）路径，
   拿它冒充自有履约的到货时效等于把两个模块的事实混成一条。
   故 capabilities 两个维度 `available=false` 并点名 ownerModule，本模块改为**逐票登记**
   （四件套互斥 + 凭据 append-only + 快照由触发器钉死必须等于最新凭据行）。
   **未登记落 NO_PROMISE 与 NO_ACTUAL 两个独立桶，绝不合并也绝不按 0 算** ——
   合并后「压根没登记承诺」会伪装成「货还在路上」，而二者在平台申诉里要的举证
   完全不同（前者要平台 SLA，后者要轨迹签收）。

5. **比率必须带分母。** 超时率分母不含未登记时限，达成率分母不含两端任一没登记；
   构成写在响应的 `rateBasis` 里随数字一起返回，界面不自算。

6. **命名空间并列而非嵌套。** `/trade/fulfillment/timeliness` 与 shipment-records
   等并列。刻意**不**挂在 shipment-records 之下：那是「已发货事实」的登记处，
   一个只读度量模块挂在被度量对象的路径下，会让人以为改这里能改已发货事实。

## 关键 trade-off

- **登记 vs 只标缺口。** 任务书点名「实际到货没有数据源，标 `available=false`，
  不要伪造」。本模块选择在此基础上**补两个登记入口**（而非只声明缺口），
  理由：①这是 9.4.1.1 SLA / 9.4.1.2 轨迹 / 9.6.2.1 费率三处已验证的仓库做法；
  ②登记不是伪造（有时间戳、主体、来源、append-only 凭据，且未登记落独立桶）；
  ③蓝图职责句点名的第三个指标「承诺到货 vs 实际到货」因此**真的可算**，
  否则本模块只交付三段里的两段。capabilities 仍如实标 `available=false` 并点名 ownerModule。

- **商品维度用只读 join 而不是冗余。** 订单行是**订单**的属性，冻结成**货票**的属性
  会在订单改行后出现两套 SKU 口径，而报表要回答的是「这个 SKU 的货平均多久发出去」，
  以订单行为准。代价是每次商品维度查询多一次 join，可接受。

- **fixture 时刻整体挪到 2027。** `registered_at <= promised_delivery_at` 这条 CHECK
  是对的（不许承诺一件已经过去的事），而 `registered_at` 取墙上时钟（2026-10）。
  承诺/到货时刻若落在 2026-03 会被当场拒。仍然 pin 死成常量，只是整体挪到未来一年 ——
  **刻意不用 `now()` 派生**，否则会出现只在某个钟点通过的用例。

## 踩到的坑（都已在代码注释里留下原因）

1. **本仓库的 jOOQ 版本会把 `fetchValue(sql, Class, args)` 里的 `Class` 当成绑定值。**
   标量读数统一收口到 `fetchOne + get(0, Type)`（`PendingShipmentService` 已记过同一件事）。
2. **可空 timestamptz 用裸 `?` 会在值为 NULL 时让 PostgreSQL 推不出类型。**
   所有时刻列一律写 `?::timestamptz`；不加转换时 jOOQ 还会把 `OffsetDateTime`
   按 varchar 绑定，报 `cannot cast type uuid to timestamp with time zone`。
3. **列名跨表同名要带别名**：三张上游表都有 `id`/`created_at`，不加 `r.` 会报
   `column reference "id" is ambiguous`。
4. **`asOwner(...)` 返回的是 lambda，必须 `.run()` 才会真的执行** —— 只构造不调用会让
   「期望抛异常」的用例假绿（DB 级闸门测试全部踩过这一条）。
5. **V99 的 `guard_sales_order_submitted` 要求提交前已有商品行、客户快照与收货地址快照**，
   且订单头金额必须与明细行真值一致；夹具必须按「先落草稿 → 补齐 → 再提交」的顺序写。

## 文件清单

| 类别 | 路径 |
| --- | --- |
| 迁移 | `backend/services/trade/src/main/resources/db/migration/V131__trade_order_timeliness_analysis.sql` |
| 模型 | `backend/services/trade/src/main/java/com/starcon/trade/OrderTimelinessModels.java` |
| 服务 | `backend/services/trade/src/main/java/com/starcon/trade/OrderTimelinessService.java` |
| 端点 | `backend/services/trade/src/main/java/com/starcon/trade/OrderTimelinessController.java` |
| 后端测试 | `backend/services/trade/src/test/java/com/starcon/trade/OrderTimelinessIntegrationTest.java`（37 例） |
| 前端模型 | `apps/web/src/components/orders/order-timeliness-model.ts` |
| 前端工作区 | `apps/web/src/components/orders/order-timeliness-workspace.tsx` |
| 前端页面 | `apps/web/src/app/orders/fulfillment/timeliness/page.tsx` |
| 前端测试 | `apps/web/src/components/orders/order-timeliness-model.test.ts`（12 例）<br>`apps/web/src/app/api/trade/order-timeliness-allowlist.test.ts`（6 例） |
| 白名单 | `apps/web/src/lib/api-proxy.ts`（外科式追加 `fulfillment/timeliness` 一组） |
| 契约 | `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`（codegen 产物） |

## 验证

- L0：`./mvnw -q compile` 绿；`pnpm typecheck` 3/3 绿；`pnpm lint` 3/3 绿
- L1：`./mvnw -pl services/trade -am test` **499 例 0 失败**
- 前端：`pnpm vitest run`（本车道两个文件）**18 例 0 失败**

## 后续待办

1. **10.3 物流轨迹交付后**：把 `TRACKING_ACTUAL_TIME` 维度切成 `available=true`，
   「实际到货」应改为**读取**轨迹签收事件而不是人工登记；届时
   `order_timeliness_delivery.source` 的 `PLATFORM_FEED` 分支才真正有来源。
2. **2.5.1.1 平台回传交付后**：`DELIVERY_PROMISE_CONFIG` 同理切换为读取，
   并考虑用平台 SLA 预填承诺（当前是逐票登记）。
3. **13 分析 / 16 合规**：本模块已立 `DOWNSTREAM_ANALYTICS` 维度写明缺口，
   下游接上时按授权 API 读 `order_timeliness_fact`，本模块**不冒充下游指标**。
4. **既有漂移（非本车道范围，未处理）**：`logistics/waybill-applications/capabilities`
   在 api-proxy 白名单里返回 404（HEAD 上即如此），归属 10.2.1.1 车道；
   `packages/api-contracts/src/channel.openapi.json` 与集成头代码不同步（纯重排 + 少量字段）。
   两者均已确认**不是**本车道插入造成的，已 revert / 未纳入本车道改动。
