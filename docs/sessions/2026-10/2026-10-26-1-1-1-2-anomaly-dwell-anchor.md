# 2026-10-26: 1.1.1.2 异常订单队列 · 停留时长时间锚漂移修复

## 摘要

`trade` 服务的 `AnomalyOrderIntegrationTest` 有两条用例在 L2 批次闸上打红（实际 260106 秒 vs 期望
`[259080, 259320]`）。取证后确认：服务端算「停留时长」用的是**查询那一刻的 JVM 墙钟**
（`AnomalyOrderService.dwellSeconds` → `OffsetDateTime.now(ZoneOffset.UTC)`），而测试把基准时刻
固定在**类加载那一刻**（`static final OffsetDateTime NOW`），于是停留时长 =
「72 小时 +（查询时刻 − 类加载时刻）」。机器一慢（Testcontainers 起容器 + 其它模块抢 CPU），
这个差就到 906 秒，越过 ±120 秒容差 ⇒ 断言翻车，而被测行为一行都没改。

按本仓先例 `3f8b6744`（红黄绿与时间戳断言不再依赖「客户端墙钟 vs 数据库时钟」的相对位置）的方法
修掉了两个类里同款模式：**基准每个用例重取一次数据库时钟**；停留时长的断言改成
**在服务端判定所用的那个钟域里前后夹逼**；加一个默认关闭的**数据库容器时钟错位开关**。

## 决策 / 结论

1. **服务端停留时长的真实算法（取证结论）**
   - 起点 `oldest_since` = 命中信号的 `min(since_at)`（`AnomalyOrderService:191`），
     各信号取值：
     - `ADDRESS_UNDELIVERABLE` → `order_address_check.assessed_reference_at`
       （= 订单 `submitted_at`，由 `AddressPaymentCheckService.assess` 显式写入，**不是** `now()`）
     - `DUPLICATE_ORDER` / `ADDRESS_FRAUD_SUSPECTED` → `order_risk_assessment.evaluated_reference_at`
     - `PLATFORM_SHIPMENT_TIMEOUT` → `shipment_writeback_task.created_at`
     - `BUYER_MESSAGE_PENDING` → `platform_order.platform_placed_at`
     全部是**显式写入**的事实列，没有一个是 `DEFAULT now()`。
   - 终点（判定时刻）= `OffsetDateTime.now(ZoneOffset.UTC)`，**JVM 墙钟**，在行映射时取值
     （`AnomalyOrderService:1080`，被 `:481` 调用）。
   - 数据库时钟（PG `now()`）只用在**别处**：进队列的阈值（`:159`、`:172`）、停留时长筛选
     （`:864`）、汇总里的 `oldest`（`:885`）、`dataAsOf`（`:1110`）。

2. **与任务书前提的差异（以代码事实为准）**：任务书写「判定时刻取数据库时钟」。这对
   **队列成员阈值**成立，但对**停留时长这个值**不成立 —— 停留时长是 JVM 墙钟减播种时刻。
   所以不能照抄「把基准换成 `dbNow()` 就完事」：那样会把起点挪进数据库钟域、终点留在 JVM 钟域，
   **反而引入一个新的跨钟域依赖**。本次按「服务端在哪个钟域判定，就在哪个钟域里比」的原则分域处理：
   - 进队列阈值、停留筛选 → 数据库钟域 ⇒ 基准取 `dbNow()`；
   - 停留时长这个值 → JVM 钟域 ⇒ 用 JVM 墙钟前后各读一次做夹逼。

3. **断言不许被削弱**：停留时长断言从「固定窗口 `72h ± 120s`」改成
   「`dwell ≡ 判定时刻 − 判定基准时刻`」，窗口 = **查询耗时**（通常 ≤ 1 秒 ⇒ 上下界相等），
   比分母更严；另外新增一条「停留起点必须**逐位等于**播种的判定基准时刻」的断言，把锚点钉死。

4. **时钟错位开关默认关闭**：`-Dstarcon.trade.anomaly.dbClockSkewSeconds=N` 拨动**数据库容器自己
   的墙上时钟**（`date -s`，需提权，只在开关打开时提权），`@AfterAll` 还原。正常 CI 不产生任何等待。

## 涉及文件

- `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderIntegrationTest.java`
  —— 时间基准由类级 static 改为**每用例重取**（`dbNow()`）；新增 `dwellFloor()`（与服务端
  `dwellSeconds` 同一算法）与 JVM 墙钟前后夹逼；两条停留时长断言改写并补「停留起点逐位相等」断言；
  新增默认关闭的数据库钟错位开关 + `@AfterAll` 还原。
- `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderTenancyIntegrationTest.java`
  —— 同款 static 基准改为**每用例重取**的数据库时钟；三条多租户断言一条未动。

## 关键 trade-off / 讨论

- **基准取数据库钟还是客户端钟？** 取数据库钟：`now()` 才是服务端判「进不进队列」用的钟，
  且先例 `3f8b6744` 就是这个口径。代价是停留时长这个**值**会随两钟之差整体平移（错位 1 小时 ⇒
  值变 71/73 小时）—— 但断言比的是**关系**（判定时刻 − 基准），关系不受影响，且这正是「两钟错开
  也必须绿」要证明的东西。
- **要不要把停留时长的绝对值也钉死在 72 小时？** 不要。机器在播种与查询之间卡 20 分钟是**环境**问题，
  不是被测行为；把它写进断言就是拿环境抖动当回归。绝对值由「基准 = 每用例重取」保证（漂移从
  906 秒降到毫秒级），精确性由夹逼保证。
- **多租户测试要不要也加时钟错位开关？** 没加。那里唯一与钟有关的是 24 小时超时阈值，而播种点
  距阈值有 48 小时余量（拨 1 小时翻不了案），加它只增加提权容器与 `date -s` 的风险、不增加证据。
  该类的真实收益是去掉类加载期基准这个潜伏模式。

## 后续待办

- 同一模式在别的模块是否还有残留：`static final OffsetDateTime NOW = OffsetDateTime.now(...)`
  这类**类加载期固定基准**，建议在 L2 闸里加一条静态检查（或按模块逐个排查）。本次按任务书范围
  只处理 trade 的这两个类。
- `AnomalyOrderService.dwellSeconds` 用 JVM 墙钟、而阈值用数据库钟，本身是**两个钟域混用**；
  本次未动生产代码（任务书明确禁止）。若将来统一到数据库钟域，测试夹逼也要跟着换钟域。

## 关联

- 先例：`3f8b6744` fix(test): 红黄绿与时间戳断言不再依赖「客户端墙钟 vs 数据库时钟」的相对位置
- 模块：1.1.1.2 异常订单队列（trade，只读投影，无迁移、无契约改动）
- 蓝图：`docs/product/capability-blueprint/01-dashboard-and-portal.md`（1.1.1）
