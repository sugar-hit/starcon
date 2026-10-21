# 2026-10-21: 5.5.1.1 价格历史窗口断言的墙钟依赖假红

## 摘要

修能力蓝图模块 **5.5.1.1「价格历史」**（catalog 服务，阶段 D6，L3 锚点 5.5.1，**已交付模块的缺陷修复**）
里一条**恒定依赖墙上时钟**的测试断言：`PriceHistoryIntegrationTest#时间轴按维度真筛选且真分页真排序`
在 L2 批次验证里红（`Expected size: 1 but was: 0 in: []`，:417）。
在合并本批之前的提交 `dd607c5b` 上单独跑**同一条测试、同一断言、同一行号**同样失败
⇒ 与本批其它车道无关，是**潜伏缺陷**而不是本批引入的回归。

**只改测试，不改服务端**：服务端的 `LocalDate → UTC 日界` 换算是已交付模块的 API 口径（确定性的、
且被比较视图等处复用），**错的是测试的期望**。修法是让**种子时刻与查询窗口用同一套显式口径（UTC）**，
使断言在任何墙上时刻、任何机器时区下都成立。

**零迁移、零生产代码改动、零契约改动。**

## 决策 / 结论

- **根因（机制级，复核与调度者一致）**：
  - 服务端 `PriceHistoryService` 把查询窗口换算成 **UTC 零点**的半开区间
    （`[from 00:00Z, to+1 00:00Z)`，见 `PriceHistoryService` 的 `windowFrom` / `windowTo` 分支）；
  - 测试的**种子**是墙钟相对值（`OffsetDateTime.now().minusDays(10)` ⇒ A2 = now − 8 天），
    而**查询窗口**是**本地**日历日（`LocalDate.now().minusDays(9)`）；
  - 本机 +08:00 ⇒ 两者差 8 小时：本地时刻 ≥ 08:00 时 now 的 UTC 值已跨日 ⇒
    A2 的 UTC 值 ≥ 窗口上界 ⇒ 被过滤掉 ⇒ 命中 0 条 ⇒ 红；
    本地时刻 < 08:00 时 A2 落在前一日 23:0xZ ⇒ 碰巧在窗口内 ⇒ 绿。
  - 实测吻合：batch-46 的 L2 跑在 07:02–07:36（绿），本批 L2 跑在 08:15–08:26（红）。
- **修法：种子与窗口锚在同一个显式 UTC 日上**（口径统一，不是放宽断言）：
  - `windowDay = LocalDate.now(ZoneOffset.UTC).minusDays(9)`（**显式 UTC 日历日**），
    `dayStart = windowDay.atStartOfDay().atOffset(ZoneOffset.UTC)`；
  - 三个种子的位置变成**显式不变量**：A1 = `dayStart − 1 天`（窗口外）、
    A2 = `windowDay` 正午 12:00 UTC（**窗口内**）、A3 = `dayStart + 1 天`
    （= 窗口的**排他上界**，窗口外）；
  - 查询窗口用**同一个** `windowDay` ⇒ 种子必然落在 `[day 00:00Z, day+1 00:00Z)` 内，
    **与运行时刻、运行机器时区都无关**。
- **为什么不用「固定某个绝对日期」**：固定绝对日期也能绿，但会把用例变成与真实数据脱节的死样本；
  「同一个显式 UTC 日」既保持样本新鲜，又让种子与窗口的**相对关系**恒定 —— 这才是真正的不变量。
- **口径自证写成可执行断言**：把服务层的换算规则原样写进用例
  （A2 在 `[from, to+1)` 内 / A1 在 from 之前 / A3 恰等于排他上界），
  ⇒ 以后谁把种子改回「墙钟相对值」，这三条**立刻红**（不只是那条窗口断言会红）。

## 关键 trade-off / 讨论

- **否决「改服务端，把 `LocalDate` 窗口换成本地日界」**：UTC 日界是**已交付模块的 API 口径**，
  且被比较视图等处复用；改它等于改已交付契约，代价与风险都远超本车道。**错的是测试的期望。**
- **否决「把窗口放宽成 ±1 天 / 前后各几天」**：那是**用放宽断言换绿灯** ——
  「时间范围真筛选」这条用例的职责就是证明窗口能筛掉别的记录，放宽窗口等于把职责删掉。
  本次做法是**把窗口筛得更细**（多加 3 条窗口断言），而不是放宽。
- **否决「删掉那条窗口断言」或「改成断言实际返回几条」**：那是**掏空断言**，真回归（时间筛选写坏）
  会漏过去。
- **否决「注入固定 `Clock` 改造服务层」**：服务层现在没有 `Clock` 抽象，
  为一条测试断言去动**生产代码**的成本与风险都不成比例；而且本仓 Java 侧本来就没有这个惯例。
  口径统一（种子与窗口同一套 UTC 日界）就能达成同一目标，且**零生产改动**。
- **`TZ` 环境变量在本仓 surefire 下确实生效**（已实测：JVM `ZoneId.systemDefault()` 随 `TZ` 变化，
  `America/New_York` 下 `LocalDate.now()` 落在**前一日**）⇒ 四个时区的跑法是一份**真的**时刻无关证明，
  不是摆设。

## 涉及文件

- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java`
  （唯一被改的代码文件）：
  - 新增私有辅助 `changesInWindow(skuId, from, to)`（窗口查询集中一处，窗口语义写进 javadoc）；
  - `时间轴按维度真筛选且真分页真排序`：种子与窗口重锚到同一个显式 UTC 日 + 3 条口径自证断言；
  - 窗口断言 1 条 ⇒ **4 条**（窗口内恰好 1 条且是 A2 且**生效时刻逐字等于种进去的值** /
    前一天窗口恰好 A1 / 次日窗口恰好 A3 ⇒ 排他上界语义 / 空窗口命中 0 条）；
  - 分页断言从「页大小 + 两页互不相交」加到「页大小 + 页内容逐条为 A3/A2 与 A1 + 互不相交」；
  - 排序断言（A1/A2/A3 升序与倒序）、按 SKU / 来源 / 店铺筛、非法排序报错 —— **原样保留**。
- `docs/product/capability-blueprint/module-progress.md`（只改 5.5.1.1 那一行的「证据」格，追加本次修复）
- `docs/sessions/2026-10/2026-10-21-price-history-clock-window-fix.md`（本文件）

**零改动**：`PriceHistoryService` 及其它任何生产代码、任何 Flyway 迁移、`packages/api-contracts`、
任何前端文件。

## 验证

- **复现（改前）**：`./mvnw -pl services/catalog -am test -Dtest='PriceHistoryIntegrationTest'`
  ⇒ `Tests run: 16, Failures: 1`（`时间轴按维度真筛选且真分页真排序:417`，`Expected size: 1 but was: 0`）。
- **L0 编译闸**：`cd backend && ./mvnw -q compile` ⇒ 成功（无输出，退出码 0）。
- **L1 模块闸 + 时刻无关证明**（`TZ` 实测生效，四个时区各跑一遍同一测试类）：
  `./mvnw -pl services/catalog -am test -Dtest='PriceHistory*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ `UTC` / `Asia/Shanghai` / `America/New_York` / `Pacific/Kiritimati`
  **各 22 例全绿**（`PriceHistoryIntegrationTest` 16 + `PriceHistoryTenancyIntegrationTest` 6，0 failure / 0 error）。
- **多租户三断言**：`PriceHistoryTenancyIntegrationTest` 6 例在四个时区下**全绿**
  （两租户互不可见含按主键直查读到零行 / 伪造 `X-Tenant-Id` 头无效且写入仍落在调用者名下 /
  连接池压到 1 条时 5 轮交替读写不串租户）；直查断言走应用账号（RLS 生效，非超级用户绕过）。
- **真回归仍然抓得住**：临时把 A2 的种子改回墙钟相对值（`OffsetDateTime.now().minusDays(8)`）
  ⇒ 该用例立即 FAIL（本地时刻已过 08:00，落在窗口外）；临时改动已回滚。
- `pnpm typecheck` ⇒ 本车道未动任何 TS 文件。

## 后续待办

- **不属本车道**：全仓后端测试里其它用 `LocalDate.now()` / `OffsetDateTime.now()` 构造窗口或种子的地方
  （`PricingRuleIntegrationTest`、`BatchCalcFixture`、`ProfitCalculatorIntegrationTest`、
  `SkuCostCompositionIntegrationTest`、`ExchangeRateSnapshotIntegrationTest` 等）用的是类加载期固定的
  `static final` 日常量，**不是同类**（期望值不随墙上时刻漂移），本轮 L2 全部通过 —— 未动。
- **建议后续单开一条车道**：扫一遍 `backend/services/**/src/test/**` 里
  「墙钟相对种子 + 相对窗口」的组合（本次只修 5.5.1.1 这一处）。
- **本仓 Java 格式化另有既有问题**：`spotless` 未绑定构建生命周期，CI 里 `verify spotless:check`
  会把 145 个既有文件判为违规（javadoc 重排版噪声），与本车道无关；本车道**未跑任何格式化器**，
  只保证改动风格与被改文件周围一致。

## 关联

- `docs/sessions/2026-10/2026-10-21-price-history.md`（5.5.1.1 原始交付）
- `docs/sessions/2026-10/2026-10-21-listing-publish-clock-assert-fix.md`（同类缺陷的既有先例：跨时钟域断言）
- `docs/product/capability-blueprint/module-progress.md`（台账 5.5.1.1 行）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceHistoryService.java`
  （UTC 日界换算口径，本次**未改动**）
