# 2026-11-01: warehouse 寄售结算区间跨钟域修复（跨时钟域第 4 例）

## 摘要

warehouse 7.7.1.2「寄售与代销」的结算区间与销售事实使用不同钟域，导致**本地时间 00:00–08:00（东八区）集成测试必红**，
进而让每天凌晨的 L2 批次闸整体判红。本车道在 `fix/warehouse-consignment-clock-domain`（基于集成头 `311e3709`）上定位根因、
收敛口径、补断言并落 commit。修复后 L1 模块闸 56 例全绿（跑批时刻 02:42 +08:00，正落在原缺陷的复现窗口内）。

**根因判定与 backlog 的初判不同**：真正的错位在**区间侧**（会话本地日 vs UTC 零点混口径），
**写回侧实测并没有丢偏移** —— jOOQ `?::timestamptz` + `OffsetDateTime` 绑定保留瞬时。详见下节。

## 决策 / 结论

### 1. 根因：区间侧混口径（主因），写回侧未见丢偏移（次要，已加固）

| 侧 | 原实现 | 判定 |
| --- | --- | --- |
| 区间侧 | `settlementWindow()` 取 `select current_date`（**会话本地日**）；`createSettlement()` 的边界按 `periodStart.atStartOfDay().atOffset(UTC)`（**UTC 零点**） | ⛔ **真根因**：DB 的本地日期被直接当成 UTC 日期用 |
| 写回侧 | `recordSale()` 原样绑 `input.occurredAt()` 进 `?::timestamptz` | ⚠️ **实测未丢偏移**（探针见下）；但仍需显式 UTC 归一收口 |

触发链：本地 00:00–08:00 ⇒ 会话本地日 = UTC 日 **+1** ⇒ 区间整体比事实所属的 UTC 日晚一天
⇒ 当天的售出事实落在区间上界之外 ⇒ `pending` 为空 ⇒ 400，且报错文案把「区间算错」说成「业务上没卖出东西」。

### 2. 实测证据（探针，非推测）

探针源文件：`.tmp/Probe.java` / `ProbeJooq.java` / `ProbeReal.java` / `ProbeDate.java` / `ProbeWrite.java` /
`ProbeOffset.java` / `ProbeWindow.java`（jOOQ 3.19.26 + pg 42.7.7 + `postgres:16-alpine`）。

- **写回侧不丢偏移**：`?::timestamptz` 绑 `OffsetDateTime` 后**保留瞬时**。
  `2026-11-01T02:05:30.123+08:00` → 落库 `2026-10-31T18:05:30.123` UTC（同一瞬时）。
  `+08:00` / `Z` / `+05:30` / `-03:00` 四种偏移全部原样保留。
  ⇒ backlog 里「绑定链路上偏移量被丢掉 ⇒ 落库晚 8 小时」的判断被实测**证伪**。
- **唯一真会平移的写法**：把本地墙钟字面量按 UTC 盖章（`toLocalDateTime().atOffset(ZoneOffset.UTC)`）⇒ +8h。
  `toLocalDateTime()` / `Timestamp.valueOf(...)` 在会话 TZ=+08 时**不**偏移。
- **区间侧确证错位**：应用 PG 会话 TimeZone 继承 JVM 默认时区（东八区）⇒ `select current_date` 返回**本地日**，
  而区间边界用 **UTC 零点**。同一瞬时 `2026-10-31T18:05:30.123Z`：
  - DB-clock 窗口 `[2026-11-01T00:00Z, 2026-12-01T00:00Z)` → **不在区间内** ⇒ 400
  - UTC-clock 窗口 `[2026-10-01T00:00Z, 2026-11-01T00:00Z)` → **在区间内** ⇒ 绿
- 边界比较本身正确（`?::timestamptz` 中段绑定回显无误）。

### 3. 口径统一到 **UTC 日历**（不改成「会话本地日界」）

依据仓库既有先例：

- `backend/services/trade/.../PurchaseOrderListService.java:63-64`、`PurchaseArrivalService.java:88-89`
  —— `(now() at time zone 'UTC')::date`，注释明写「`current_date` 跟着会话时区走，同一份数据在两个时区的库里会差一天」。
- `backend/services/warehouse/.../V71__warehouse_consignment_agency.sql:48-51` —— 归期区间由服务层**按 UTC 日历**算出。
- `ConsignmentService.java` 类注释 L555-556 —— 「归期按**售出时刻**落期（`occurred_at` 的 UTC 日期）」。
- `backend/services/warehouse/.../OutboundTimelinessIntegrationTest.java:60-77` —— 测试夹具「全用 UTC 固定瞬间，不用 now()」。

### 4. 三个钟域必须同属 UTC 日历

「售出事实的瞬时」「区间的日历日」「区间两端的边界」三者必须同属 UTC 日历，缺一个就会出现
「今天的售出落在明天的区间里」这类错位。

## 涉及文件

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ConsignmentService.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ConsignmentIntegrationTest.java`
- `docs/sessions/2026-11/2026-11-01-warehouse-consignment-clock-domain-fix.md`（本文）

**未改动**：`backend/services/warehouse/src/main/resources/db/migration/V71__warehouse_consignment_agency.sql`
（本车道无迁移）、其它任何模块。

## 改动清单

### `ConsignmentService.java`

1. **类注释新增「日历口径纪律」**（L49-56）：本模块的「今天」一律按 UTC 日历算（`(now() at time zone 'UTC')::date`），
   不用 `current_date`；售出事实按瞬时落库，区间两端按 UTC 零点落边界。
2. **`recordSale()`**（L474-487）：绑定前显式归一
   `var occurredAt = input.occurredAt().withOffsetSameInstant(ZoneOffset.UTC);`
   ⇒ 落库值恒为同一瞬时的 UTC 写法，不受调用方偏移影响。
   ⚠️ 真实 API 是 **`withOffsetSameInstant`**；backlog L108 写的 `atOffsetSameInstant` 在 `OffsetDateTime` 上不存在（探针已实测编译失败）。
3. **`createSettlement()`**（L579-611）：抽出 `periodLo` / `periodHi` 两个 UTC 零点边界（语义不变，但可复用），
   并把**区间边界写进 400 报错** —— 原文案把「区间算错」说成「业务上没卖出东西」，边界可见才谈得上可诊断。
4. **`settlementWindow()`**（L1043-1085）：「今天」改 `utcToday()`（UTC 日历）；
   新增重载 `settlementWindow(UUID agreementId, LocalDate asOf)`，让区间成为「周期 + 锚定日」的纯函数
   （补结算 / 跨期合并可按历史锚定日重算，也让测试不必依赖墙钟）。无参重载委托给 `utcToday()`。
5. **`nextCode()`**（L1460-1478）：单号日期同样改用 `utcToday()`，与结算区间同一日历。

### `ConsignmentIntegrationTest.java`

1. **夹具去墙钟**：`now()` 助手删除，改为固定 UTC 瞬间常量
   `T_SALE = 2026-03-02T01:00:00Z` 与 `AS_OF = 2026-03-02`（照 `OutboundTimelinessIntegrationTest` 写法）；
   全部 `LocalDate.now()` 一并锚到 `AS_OF`。
2. **新增 `结算区间的今天按UTC日历而不是会话本地日算`**：在事务内 `set_config('TimeZone', <固定偏移区>, true)`
   把会话时区改成「本地日必然 ≠ UTC 日」的偏移区（UTC 时刻 <12h 用 `Etc/GMT+12`＝UTC−12，≥12h 用 `Etc/GMT-14`＝UTC+14；
   两条合起来覆盖一整天），先钉死前提（本地日 ≠ UTC 日），再断言 `window.today() == UTC 日` 且区间两端同属 UTC 日历。
   ⇒ **这条断言在任何时刻都能抓住「按会话本地日算」的回归**，不依赖跑批落在哪个钟点。
3. **新增 `售出时刻的偏移不参与落库只保留瞬时`**：同一个瞬时用 `+08:00` / `Z` / `+05:30` / `-03:00` 四种偏移写入，
   每条读回后用 `toInstant()` 比对 —— 偏移只是写法，落库只保留瞬时。
4. **强化 `售出时刻写回不被时区偏移`**：往返断言改为显式的「+08:00 传进去，读回来必须还是同一个瞬时」。
5. **`同一条销售事实不能进两张结算单`**：改用 `settlementWindow(agreement, AS_OF)` —— 区间确定，端到端守住
   「区间按哪个日历算 + 边界按哪个钟域落」两端。

## 关键 trade-off / 讨论

- **为什么统一到 UTC 日历，而不是改成「会话本地日界」？**
  若把区间两端也改成会话本地日界，则 `occurred_at`（UTC 瞬时）与边界仍不同属一个日历 —— 换个形式继续错位。
  V71 迁移注释与模块注释都已把寄售归期定在 UTC 日历上，改口径等于推翻既有迁移语义。
- **为什么要给 `settlementWindow` 加 as-of 重载？**
  「结算区间 → 出结算单」这条端到端链路必须能被**确定性**复现：区间若锚在「今天」，售出事实就必须落在「今天」，
  夹具就又回到了 `now()`，于是 00:00–08:00 依旧假红。锚定日让区间成为纯函数，夹具可以用固定 UTC 瞬间。
  该重载同时对应模块既有能力（注释 L572-573：「允许显式改期（补结算、跨期合并都要用得到）」）。
  **未**把它暴露成 REST 参数：动了就要重生成 OpenAPI 契约与 `packages/api-contracts` / `packages/sdk`（只读生成物），超出本车道范围。
- **为什么写回侧还要显式 UTC 归一？**
  实测没丢偏移，但 `timestamptz` 存的是瞬时、偏移只是写法：显式归一把「调用方给什么偏移都存成同一瞬时」钉成代码事实，
  也顺手把 `Timestamp.valueOf` 那类写法彻底挡在门外。属收口加固，不是本次红的直接原因。
- **为什么不改其它模块的 `current_date`？**
  跨模块纪律：其它模块的区间口径归其它车道裁决。本车道只扫描、只列清单（见下节），不动一行。

## 验证

全部在**默认时区**（Asia/Shanghai，UTC+08:00）下执行，**未**使用 `TZ=UTC`。

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `./mvnw -q compile` | exit 0 |
| L1 模块 | `./mvnw -pl services/warehouse -am test -Dtest='Consignment*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | `Tests run: 56, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS（22 + 31 + 3） |
| 格式 | `spotless:check -DspotlessFiles=".*ConsignmentService\.java\|.*ConsignmentIntegrationTest\.java"`（**须用 JDK 21**） | exit 0 |
| 修复前复现 | 同 L1（修复前代码） | `Tests run: 20, Failures: 0, Errors: 1` → 400（同一条销售事实不能进两张结算单） |

> 修复前复现发生于 `2026-11-01 02:04 +08:00`；修复后绿跑于 `2026-11-01 02:44 +08:00` ——
> **两次都落在本地 00:00–08:00 这个「必红窗口」内**，因此绿跑不是靠躲开时钟窗口换来的。

> ⚠️ `spotless` 在本机默认 JDK 25 上崩溃（`NoSuchMethodError: com.sun.tools.javac.util.Log$DeferredDiagnosticHandler.getDiagnostics()`），
> 崩在我**没碰过**的 `PickWaveIntegrationTest.java` / `PendingInspectionIntegrationTest.java` 上 ⇒ **既有环境问题，非本车道引入**。
> 换 JDK 21 跑即可验证；本车道两个文件用 JDK 21 跑 `spotless:check` 为 exit 0。
> 注：JDK 21 下全模块 `spotless:check` 仍会报 `PendingInspectionIntegrationTest.java` 的**既存**违规 —— 属别的车道，本车道未夹带修改。

### 往返断言的「故意弄丢一次 → 红 → 改回来 → 绿」真实输出

**演示 A（写回侧）**：把绑定改成 backlog 假设的平移写法
`input.occurredAt().toLocalDateTime().atOffset(ZoneOffset.UTC)`（墙钟按 UTC 盖章 ⇒ +8h）：

```
[ERROR] Failures:
[ERROR]   ConsignmentIntegrationTest.售出时刻写回不被时区偏移:871
expected: 2026-10-07T07:30:00Z
 but was: 2026-10-07T15:30:00Z
[ERROR]   ConsignmentIntegrationTest.售出时刻的偏移不参与落库只保留瞬时:902
expected: 2026-03-02T01:00:00Z
 but was: 2026-03-02T09:00:00Z
[ERROR] Tests run: 22, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

**演示 B（区间侧）**：把「今天」改回 `select current_date`（会话本地日）：

```
[ERROR] Failures:
[ERROR]   ConsignmentIntegrationTest.结算区间的今天按UTC日历而不是会话本地日算:645
expected: 2026-10-31 (java.time.LocalDate)
 but was: 2026-11-01 (java.time.LocalDate)
[ERROR] Tests run: 22, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

（`expected` 是 UTC 日 `2026-10-31`，`but was` 是会话本地日 `2026-11-01` —— **正好差一天**，与复现分析完全对上。）

**改回来后**：`Tests run: 56, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS。

原始日志：`.tmp/l1-break-writeback.log`、`.tmp/l1-break-window.log`、`.tmp/l1-final-green.log`、`.tmp/repro-before.log`。

## 全仓 `select current_date` 清单（只列不改）

### 字面量 `select current_date`（服务侧）

| 模块 | 位置 |
| --- | --- |
| warehouse | `ConsignmentStockService.java:1458`、`CustomsDeclarationService.java:467`、`DutyTaxService.java:740`、`FirstMileShipmentService.java:808`、`GoodsReceiptDiscrepancyService.java:1196`、`GoodsReceiptService.java:595`、`InventoryTransferService.java:528`、`TradeDocumentService.java:1268,1883`、`VmiService.java:1023,1869` |
| catalog | `LandedCostService.java:821`、`SupplierQuoteService.java:981` |
| channel | `SalesTaxService.java:145` |

> warehouse `ConsignmentService.java` 的两处（`settlementWindow` / `nextCode`）**已在本车道改为 UTC 日历口径**。
> trade `PurchaseOrderListService.java:63`、`PurchaseArrivalService.java:88` **本来就是** UTC 日历口径（不是缺陷）。

### `current_date` 表达式（服务侧，含 WHERE 条件里的用法）

- catalog：`CompletenessScoreService.java:80,82,1702,1730,1732`；`SkuCostCompositionService.java:101,117,118,1055,1056,1266,1267`；
  `SupplierQuoteService.java:1006`
- trade：`DropshipConfigService.java:263`；`ProcessingOrderService.java:287,310`；`ReplenishmentSuggestionService.java:242,372`；
  `StockoutRiskService.java:212`、`StockoutRiskModels.java:231`（注释）
- warehouse：`InventoryAgingService.java:126,147,149,150,858`；`InventoryExpiryService.java:155,157,158,431,654,655,857,1027,1028,1164`；
  `InventoryLotService.java:125,126,201,204,208,312,432,433,464,597,599,727,789`；`InventoryRiskProjectionService.java:80`；
  `InventoryAgingModels.java:18,288`
- reporting：`ReportingProductPerformanceCatalog.java:379`（仅注释文案）

### 测试侧

`ConsignmentIntegrationTest.java:637`（本车道新增的**对抗性探针**：故意读会话本地日来证明它 ≠ UTC 日）；
`InventoryAgingIntegrationTest.java:37,170`；`InventoryExpiryIntegrationTest.java:166,383,416,476`；
`InventoryLotIntegrationTest.java:353`；`InventoryBucketIntegrationTest.java:259`；`InventoryDistributionIntegrationTest.java:270`；
`AllocationPolicyIntegrationTest.java:1030,1058`；catalog `VendorListIntegrationTest.java:413`、`SkuMergeIntegrationTest.java:381,463`；
finance `PaymentExecutionTenancyIntegrationTest.java:219,320`；trade `DropshipConfigIntegrationTest.java:286`、
`ProcessingOrderIntegrationTest.java:932`

### 判读口径

这些 `current_date` **本身不一定是缺陷** —— 多数用于「过期 / 临期 / 有效期」这类**纯日期语义**的判定，
和寄售结算那种「区间两端落边界」的用法不同。**真正的判据是：该处的日期最终是否要落成 `timestamptz` 的边界。**
若落成边界，就与本例同源，必须逐个核钟域；若只做 date 列比较，则会话本地日与 UTC 日的差异需要单独评估。

## 后续待办

1. **同族扫描（建议单独立项）**：按上面的清单逐个核「是否落成 timestamptz 边界」；
   重点候选：warehouse 的 `InventoryLotService`（有效期区间）、`CustomsDeclarationService` / `DutyTaxService`（申报/税期）、
   catalog 的 `SkuCostCompositionService`（成本有效期），trade 的 `ProcessingOrderService`（`due_on < current_date`）。
2. **本模块的后续**：把 `settlementWindow` 的 as-of 暴露成 REST 可选参数（需重生成 OpenAPI 契约），供补结算场景直接按历史锚定日出区间。
3. **契约测试**：`packages/api-contracts` / `packages/sdk` 本次**未改动**（服务端 DTO 未变，`SettlementWindow` 结构未变），
   契约闸无需重生成。

## 关联

- backlog：`docs/backlog/2026-11/2026-11-01-warehouse-consignment-settlement-window-cross-clock-domain.md`
- 同族前三例：
  - `docs/backlog/2026-10-2026-10-21-trade-fulfillment-clock-domain-db-constraint.md`
  - `docs/backlog/2026-10-2026-10-27-catalog-timestamp-roundtrip-assertion-cross-clock-domain.md`
  - `docs/backlog/2026-10/2026-10-28-channel-credential-window-cross-clock-domain.md`
- 迁移：`backend/services/warehouse/src/main/resources/db/migration/V71__warehouse_consignment_agency.sql`（L48-51 归期口径注释）
- 先例：`backend/services/trade/.../PurchaseOrderListService.java:63`、`PurchaseArrivalService.java:88`、
  `backend/services/warehouse/.../OutboundTimelinessIntegrationTest.java:60-77`、
  `backend/services/channel/.../V310__channel_platform_appeal.sql:243`
