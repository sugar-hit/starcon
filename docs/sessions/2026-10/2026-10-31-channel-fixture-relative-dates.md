# 2026-10-31: channel 夹具日期改相对当下（修 L2 的 21 例墙钟假红）

## 摘要

集成头跑全量 `./mvnw verify` 时，**channel** 模块判红：`2484 例中 14 failures + 7 errors`，
集中在 6 个测试类。根因不是业务代码坏了，而是**测试夹具把种子时间硬编码成 2026-10-01 ~ 2026-10-05**，
而 channel 有 4 个服务的默认取数区间是**以当下（`now()`）为锚的滚动窗口**（最近 29/30 天）。
墙钟从 2026-10-30 翻到 2026-10-31 的那一刻，窗口下界从 `2026-10-01` 推到 `2026-10-02`，
最早的那行种子掉出窗口 ⇒ 历史少一行、报表少一条、指标读回 0、归因全部 `skippedOutOfWindow`。

**改的是夹具，不是服务**：服务的「默认最近 30 天」是合理的产品行为，改服务等于把测试改成迎合实现。
本次只把 10 个 channel 测试类的**夹具日期来源**从固定日历日改成相对 `LocalDate.now(ZoneOffset.UTC)`，
断言一条都没放宽（该 5 还是 5、该 null 还是 null、该 1000L 还是 1000L）。

## 决策 / 结论

1. **夹具锚点统一用 `LocalDate.now(ZoneOffset.UTC)`**（不是 `LocalDate.now()`）。
   服务窗口也是按 UTC 日历日算的（`LocalDate.now(ZoneOffset.UTC)`），
   两边同基准 ⇒ 与运行机器时区无关。若一边本地、一边 UTC，本地 +08:00 在 08:00 之后会跨日，窗口必然落空。
   （catalog 的 `PriceHistoryIntegrationTest` 里有同一条教训的注释。）
2. **保留「day 越大时间越晚」的序号语义**，只换日历日的算法：
   `fixtureDay(day) = TODAY.minusDays(FIXTURE_DAYS - day + 1)`。
   ⇒ `day=1` 是最早的夹具日，`day=FIXTURE_DAYS` 是最近的（今天-1 天）。
   所有夹具日都落在 `[今天-29, 今天]` 内 ⇒ 服务默认窗口恒定覆盖。
   **小时/分钟/秒/纳秒与原写死值逐字不变**（AdKeyword 的 `30,15,123456000` 也保留）。
3. **`TODAY` 取类加载时的 `static final` 快照**：同一测试类内所有 `at()` 调用共享同一锚点 ⇒
   跨 UTC 午夜也不会出现「前几次调用用旧锚点、后几次用新锚点」的排序错乱；
   距窗口下界还有 ≥13 天余量，跨午夜不会滑出窗口。
4. **显式区间参数同步改相对日期**：原测试把 `"2026-10-01" ~ "2026-10-31"` 硬传给
   `service.trend/comparison`，改夹具后必须一起改，否则区间不再覆盖种子。
   统一改成**该服务自己的默认窗口**：`windowFrom()/windowTo()`（排名/广告/跟卖 = 今天-29，跟踪变更 = 今天-30）。
5. **`OLD_ENDED_AT`（2020-01-01）保持不动** —— 它是**故意**钉「窗口外 ⇒ 不新增归因」的用例，改相对就废了这个断言。
6. **`ListingHijacker` 一并改了**（详见下节 trade-off）。
7. **不动别的服务**：`finance` / `reporting` / `warehouse` / `trade` 里的 2026-10 硬编码夹具不动 ——
   那些服务不做 `now()` 滚动窗口、当前全绿；用存量债的体量压垮增量流程是本仓反复踩过的坑。
   同理，channel 内 `ListingPerformanceIntegrationTest` / `InfluencerIntegrationTest` /
   `WarehouseConnectionIntegrationTest` / `StockForecastIntegrationTest` 等未点名类也**没动**。

## 根因与对应修法（服务窗口 → 夹具改法）

| 服务 | 默认窗口 | 代码位置 | 夹具改法 |
| --- | --- | --- | --- |
| `ListingKeywordRankingService` | `toDate = now(UTC)`；`fromDate = toDate-29` | `dayRange()` 230–246 | `at()` → `fixtureDay()`；显式 trend 区间 → `windowFrom/To` |
| `AdKeywordService` | 同上（`minusDays(29)`，多处） | 361/365、992/993、1820/1821、2349/2350 | `at()` + `bucket_date` → `fixtureDay()`；否定依据区间右端 → `TODAY` |
| `InfluencerAttributionService` | `OffsetDateTime.now()` 判合作是否在归因窗口内 | 1061（`withinWindow(endedAt, windowDays, now)`） | 合作 `ENDED_AT` → 今天-1 天；`OLD_ENDED_AT` 保持旧 |
| `ListingChangeService` | `now(UTC)-30` ~ `now(UTC)` | `comparison()` 1708 | `at()` → `fixtureDay()`；对比区间 → `windowFrom/To`（今天-30） |
| `ListingHijackerService` | `now(UTC)-29` ~ `now(UTC)` | `comparison()` 1385/1392 | `at()` → `fixtureDay()`；对比区间 → `windowFrom/To`（今天-29） |

## 关键 trade-off / 讨论

### 改服务 vs 改夹具

**改夹具**。`dayRange()` 的「默认最近 30 天」是产品行为（报表/历史默认看近期），
改成固定窗口或注入时钟会让服务行为偏离产品语义，也会把「夹具没对齐窗口」这个真问题掩盖掉。

### `ListingHijacker` 改还是不改

任务书要求「先验证再决定」。验证结果：

- `service.records(...)` / `service.alerts(...)` **不套默认窗口**（只有显式传 from/to 才过滤）⇒ 这两条路径与墙钟无关。
- `service.comparison(...)` **套默认窗口**（`fromDate = toDate.minusDays(29)`），而测试**全部显式传**了区间 ⇒ 当前不会红。
- 但夹具本身硬编码 2026-10 ⇒ 一旦有人改成不传区间（走默认窗口），种子立刻掉出窗口 —— **与本次修的 6 个类是同一形状的潜在失败**。

⇒ **决定改**。依据：① `at(day, hour)` 的 `day` 语义与 `ListingChange` 完全同形（day-of-month、day 越大时间越晚）；
② 显式区间与夹具同步改成相对日期后，L1 实测仍全绿；③ 改完这 4 个类（ListingChange / ListingChangeTenancy /
ListingHijacker / ListingHijackerTenancy）都不再有墙钟耦合。

### `ListingChangeIntegrationTest` 是绿的，为什么也改

同形状潜在失败：`ListingChangeService.comparison()` 用 `now(UTC)-30`，
测试虽然显式传了区间而没走默认窗口，但一旦夹具与区间不同步就立刻空集。一起改掉。

### 为什么不把 `day` 直接改成「几天前」的参数名

保留 `at(int day, int hour)` 的签名与语义（day=第几天），只在下面换算法，
是为了让「序号越大时间越晚」的排序含义在读代码时保持不变，也最小化 diff。

## 涉及文件

只改测试文件（`backend/services/channel/src/main/java/**` **零改动**）：

- `backend/services/channel/src/test/java/com/starcon/channel/ListingKeywordRankingIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ListingKeywordRankingTenancyIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AdKeywordIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AdKeywordTenancyIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/InfluencerAttributionIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/InfluencerAttributionTenancyIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ListingChangeIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ListingChangeTenancyIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ListingHijackerIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ListingHijackerTenancyIntegrationTest.java`

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q compile                       # EXIT=0
cd backend && ./mvnw -q -pl services/channel test-compile  # EXIT=0

# L1 模块闸（只跑受影响的类）
cd backend && ./mvnw -pl services/channel -am test \
  -Dtest='ListingKeywordRanking*,AdKeyword*,InfluencerAttribution*,ListingChange*,ListingHijacker*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
```

- **改之前（同一 worktree 的基线，stash 掉改动后跑同一条命令）**：
  `Tests run: 149, Failures: 14, Errors: 7` ⇒ **21 例红**，红在 6 个类
  （LKR 2+2、AdKeyword 1+2、Influencer 7+4+3）。
- **改之后**：`Tests run: 149, Failures: 0, Errors: 0, Skipped: 0` ⇒ **BUILD SUCCESS**。

### 已知环境问题（非本次引入）

`./mvnw spotless:apply` 在本机 **JDK 25** 下会挂在 google-java-format 1.22.0
（`NoSuchMethodError: Log$DeferredDiagnosticHandler.getDiagnostics()`），
换 `JAVA_HOME` 指向 **JDK 21** 可跑通，但跑完会把整个 channel 模块 575 个文件重排
（说明仓库基线本来就没按 google-java-format 格式化过，spotless 未绑定任何生命周期阶段）。
⇒ 本次**不跑 spotless**，手工保持与周边代码一致的 2 空格缩进 / 100 列风格。

## 后续待办

1. **未点名的同类债**（本轮**故意不动**，避免扩大增量面）：
   - channel 内 `ListingPerformanceIntegrationTest` / `InfluencerIntegrationTest` /
     `WarehouseConnectionIntegrationTest` / `StockForecastIntegrationTest` / `HandoverBatchIntegrationTest` 等仍有 2026-10 硬编码夹具；
   - `finance` / `reporting` / `warehouse` / `trade` 侧同类夹具更多。
   ⇒ 建议单独开一批「夹具相对日期化」的债清理批次，并**先确认对应服务是否做 `now()` 滚动窗口**再动手。
2. **加一道防复发闸**：channel 的 CI 里加「夹具里出现硬编码 `LocalDate.of(20xx, ...)` / `OffsetDateTime.of(20xx, ...)`」的静态检查，
   或用注入时钟替换 `LocalDate.now(ZoneOffset.UTC)`。本次没有加（超范围）。
3. `docs/product/capability-blueprint/module-progress.md` **故意未改** —— 这些模块已交付，本次只修测试夹具，状态与证据不变。

## 关联

- `docs/decisions/0004-java-spring-boot-microservices.md`（后端形态）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java`
  （同仓已有的「夹具与窗口必须同一 UTC 口径」教训注释，本次沿用其口径）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceAnomalyIntegrationTest.java`
  （同仓已有的 `LocalDate.now()` 相对锚点写法）
