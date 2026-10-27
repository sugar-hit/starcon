# trade：`CHECK(updated_at>=created_at)` 跨钟域取时 —— 32 张表 / 231 处绑定点，尚未收口

- 创建: 2026-10-27
- Owner: TBD（待调度者分派；一条修复车道收不了这个口）
- 优先级: **P0**（这不是测试脆弱性，是生产正确性缺陷 —— 一次完全合法的状态推进会被 DB 挡下）
- 关联: `backend/services/trade/`、`backend/services/trade/src/main/resources/db/migration/V151__trade_order_split.sql`、
  车道 `fix/trade-clock-domain`、同族前两例 `docs/backlog/2026-10-2026-10-21-trade-fulfillment-clock-domain-db-constraint.md`
  与 `docs/backlog/2026-10-2026-10-20-cross-clock-domain-test-assertion.md`

## 背景

`CHECK(updated_at>=created_at)` 这条约束本身是**对的** —— 它防的是「记账时刻早于建行时刻」
这个真实错误。缺陷不在约束，在**喂给它的时刻来自哪个钟**：

| 端 | 谁写 | 用哪个钟 |
| --- | --- | --- |
| `created_at` | 服务层 INSERT 绑 `OffsetDateTime.now()` | **JVM 墙钟** |
| `updated_at` | DB 函数 / DB 触发器写 `now()` | **DB 钟** |

⇒ 约束比较的是**两个钟域的时刻**。宿主钟比容器钟快 1ms，一次合法的状态推进就被 DB 挡下：

```
ERROR: new row for relation "order_split" violates check constraint "order_split_timestamps_ordered"
```

本机实测（2026-10-27，Docker Desktop 上的 PG 16 容器）：**PG 容器钟领先宿主 JVM 约 3.5ms ~ 50ms**
（随负载变化，符号也会变）。差值是**毫秒级**，所以这族缺陷**空载时一次跑就绿**，
只有机器热、偏差被放大时才翻车 —— 排查成本极高，且**重跑无法定位**。

同族前两例已分别修掉：`32a16a6a`（identity 审批待办）、`8375e648`（warehouse 货权档漂移）。
**这是第三次修同一族。**

## 影响面（本轮实测口径，不是估计）

### 1. 受约束的表：32 张，横跨 11 个迁移文件

| 迁移文件 | 表 |
| --- | --- |
| `V92__trade_fulfillment_routing.sql` | `fulfillment_mcf_authorization`、`fulfillment_path_cost_profile` |
| `V116__trade_warehouse_allocation.sql` | `fulfillment_warehouse_allocation_rule`、`fulfillment_warehouse_allocation_warehouse`、`fulfillment_warehouse_allocation_shipping_estimate` |
| `V119__trade_order_change_cancel.sql` | `order_platform_change_rule`、`order_fulfillment_line_progress`、`order_change_request`、`order_cancellation_request`、`order_warehouse_hold_task` |
| `V144__trade_order_consolidation.sql` | `order_consolidation_rule`、`order_consolidation`、`order_consolidation_writeback_task` |
| `V149__trade_order_review_rules.sql` | `order_review_rule`、`order_review_buyer_profile` |
| `V150__trade_order_risk_validation.sql` | `order_risk_rule` |
| `V151__trade_order_split.sql` | `order_split_rule`、`order_split_warehouse`、`order_split_stock_snapshot`、`order_split`、`order_split_unit`、`order_split_reservation_command`、`order_split_writeback_task` |
| `V152__trade_fulfillment_request.sql` | `fulfillment_request` |
| `V153__trade_order_exception_intercept.sql` | `order_intercept_policy` |
| `V154__trade_fulfillment_monitor.sql` | `fulfillment_monitor_threshold`、`fulfillment_monitor_case`、`fulfillment_monitor_disposition` |
| `V210__trade_order_address_payment_check.sql` | `order_address_check_policy`、`order_address_check_embargo_rule`、`order_address_check_country_rule`、`order_address_check_payment_rule` |

复核命令（`backend/services/trade/src/main/resources/db/migration/` 下）：

```bash
grep -c "CHECK(updated_at>=created_at)" *.sql | grep -v ':0'
grep -h "CHECK(updated_at>=created_at)" *.sql | wc -l   # => 32
```

### 2. Java 绑定点：`OffsetDateTime.now()` 绑定进 `created_at` / `updated_at`

| 范围 | 数量 |
| --- | --- |
| `OrderSplitService`（**本车道已收**） | 基线 **22** 处 → 修复后 **9** 处（全部落在三张**配置表**上，见下） |
| trade 服务整体 | **231** 处 |
| 全后端（trade/warehouse/channel/catalog/crm/identity/reporting） | **593** 处（trade 231 / warehouse 216 / channel 84 / catalog 35 / crm 20 / identity 5 / reporting 2） |

trade 服务内绑定点最多的几个文件（`grep -rc "OffsetDateTime.now()" services/trade/src/main/java`）：

- `OrderChangeCancelService` 26、`RefundStatusService` 17、`DropshipTrackingService` 13
- `FulfillmentMonitorService` 12、`DropshipOrderService` 12、`OrderSplitService` 11（修复后）
- `OrderConsolidationService` 11、`PendingShipmentService` 10、`ExchangeAuthorizationService` 10
- `WarehouseAllocationService` 9 …（其余 25 个文件每个 1~8 处）

### 3. 危险的不是「绑了 now()」，而是「**另一端在 DB 侧**」

同一张表的 `created_at` 与 `updated_at` **若两端同域**（都 JVM 或都 DB），约束恒成立；
只有**一端 DB、一端 JVM** 时才成雷。所以逐表判定，不能按文件批量替换：

- **雷（DB 侧会改 `updated_at`）**：`order_split`、`order_split_unit`、
  `order_split_reservation_command`（`advance_order_split_status` 里三处 `updated_at=now()`）。
  同形：`order_consolidation`（V144）、`fulfillment_request_step`（V152，已在前例修掉）、
  `platform_dispute`（V265 守卫① 写 `updated_at:=now()`）。
- **暂时安全（两端都是 JVM 写的）**：`order_split_rule`、`order_split_warehouse`、
  `order_split_stock_snapshot` —— 服务层自己 INSERT 也自己 UPDATE，两端同钟域。
  **但它们不是免疫**：宿主钟回跳（`updated_at` 落到 `created_at` 之前）一样会撞约束。

## 待办内容

- [ ] **按表判定 + 按表收口**，不要按文件批量替换 `OffsetDateTime.now()` ——
      先查清每张表的 `updated_at` 到底是谁写的（DB 函数 / DB 触发器 / 服务层），再动手。
- [ ] **收口方向（建议）**：**记账时刻归 DB，业务时刻另设独立列**。
      - `created_at` / `updated_at` = 「这一行是什么时候记进账的」⇒ **由 DB 取**（`now()`），
        服务层**不再绑 JVM 墙钟**。这样任何一端再改 `updated_at`（DB 或服务层）都在同一钟域。
      - 「事情发生在什么时候」⇒ **另设独立列**（如 `occurred_at` / `planned_at` / `ship_by_at` /
        `evidence_deadline`），由入参给，**不与 `created_at` 比较**。
        V151 对 `order_split_event.occurred_at` 刻意不加 CHECK 就是这个道理。
      - ⚠️ **不许放宽也不许删掉那条 CHECK** —— 它防的是真实错误；错的是喂给它的跨钟域值。
- [ ] **⚠️「两个钟域各管一段时，只换一端反而制造新的跨钟域依赖」** ——
      换 `created_at` 的那一端时，**必须把同一张表的 `updated_at` 一起换**。
      本车道 `OrderSplitService` 就是这么做的：`recordReservation` / `recordWriteback`
      里那两处 `updated_at` 跟建行时刻一起从 JVM 墙钟换成了 DB 的 `now()`。
- [ ] **每个改动都要有确定性回归断言**，不能靠「再跑三遍看看绿不绿」。
      可用的判据（两个钟域**在值上完全一样**时也能分辨）：
      - PostgreSQL 的 `now()` 是**事务开始时刻**，同一事务里写的每一行拿到的都是**同一个值**
        ⇒ 断言「同一笔单据的整族行共享同一个 `created_at`」，
        绑 JVM 墙钟时必然参差 ⇒ **每次都红**，不依赖钟差。
      - 读回值必须**落在 DB 钟的区间里**（`dbNow()` 前后两次采样夹住它），
        而不是落在宿主钟的区间里。
      - 夹具要跟 DB 侧判定比，就取 **DB 自己的钟**（照抄
        `com.starcon.warehouse.StockOwnershipIntegrationTest#dbNow()`）。
- [ ] 其余 231 - 11 = **220** 处 trade 侧绑定点、以及 warehouse / channel / catalog / crm 侧的
      **362** 处，按上表逐模块开修复车道（一条车道收一个模块，别开一条收全仓的）。

## 本车道（`fix/trade-clock-domain`）收掉的部分 —— 只有 order_split 一族

**已收**（`backend/services/trade/src/main/java/com/starcon/trade/OrderSplitService.java`）：

- `order_split`、`order_split_unit`、`order_split_reservation_command`、
  `order_split_writeback_task`：INSERT 的 `created_at` / `updated_at` 改成 DB 的 `now()`；
  `order_split_unit_line`、`order_split_candidate`、`order_split_event`：只改 `created_at`。
- `recordReservation` / `recordWriteback` 的 `updated_at` 一并改成 DB 的 `now()`（避免新造跨钟域依赖）。
- `order_split_event.occurred_at` **保持业务时刻**（入参给），不与 `created_at` 比较。
- 回归断言落在 `OrderSplitIntegrationTest.拆单记账时刻与DB钟同域`：
  **整族行共享同一个 `created_at`** + 状态推进后 `updated_at>=created_at`
  + 预占回执 / 发货回传两条「改写既有行」的路径同域。
  该断言对**修复前的代码每次都红**（已实测：14 行的时刻互不相同，差在微秒级）。

**没收（仍在 backlog）**：

- `order_split_rule` / `order_split_warehouse` / `order_split_stock_snapshot`
  —— 三张**配置表**，两端都是 JVM 写的，暂时自洽；但 `OrderSplitService` 里还剩 **9** 处
  `OffsetDateTime.now()` 绑定点，没动。
- 其余 29 张带同一条 CHECK 的表，以及 trade 其余 34 个文件里的 220 处绑定点。
- `PlatformDisputeTenancyIntegrationTest` 那边只改了**测试夹具**（改用 DB 钟域），
  **生产代码一行没动** —— `platform_dispute.evidence_deadline` 的判定本来就统一在 DB 钟域，是对的。

## 完成标准

- [ ] 32 张表的 `created_at` / `updated_at` 全部落在**同一个钟域**里（逐表判定并留证据）。
- [ ] 每张表配一条**确定性**回归断言（值相等 / 落在 DB 钟区间内），不是「多跑几遍」。
- [ ] 11 个迁移文件涉及的表全部有对应的测试覆盖；
      后端其余 362 处绑定点按模块收口完毕。
- [ ] 收口期间**不放宽、不删除**任何一条 `CHECK(updated_at>=created_at)`。
