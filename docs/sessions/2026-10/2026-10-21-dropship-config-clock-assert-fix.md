# 2026-10-21: 9.6.1.1 代发配置「有效期」断言的偶发假红

## 摘要

修能力蓝图模块 **9.6.1.1「代发模式配置」**（trade 服务，阶段 D4，**已交付模块的假红修复**）里
一条测试断言的**偶发假红**：`DropshipConfigIntegrationTest#有效时间写入后读回一致`
把 `at(-3)` / `at(30)` **各调了两次** —— 一次写进库、一次当断言基准，两次之间隔着一次 DB 往返。

`at()` 每次都重读 JVM 墙钟并**截断到整秒** ⇒ 只要这两次跨过一个整秒边界，写进库的是 `T`、
断言用的是 `T+1s` ⇒ 断言差 1 秒。**这与 9.6.1.3 那处同形状、同一根因**（上一条车道
`fix/9-6-1-3-tracking-clock-assert` 取证时顺带发现，只取证上报、没有动手），
本车道就是把那一处也修掉。

**只改测试**：业务代码零改动、无新 Flyway 迁移、`packages/api-contracts` 未动、前端零改动。

## 根因

「写进去的值」与「拿来断言的值」**必须取自同一次时钟读取**，否则两次读取之间只要跨过整秒边界就差 1 秒。
本仓库既有的正确写法就在同一个文件里（`timestamptz写入没有静默偏移`）：先把时刻存进局部变量，
建单与断言都用它。出事的那处没这么做，写与断言各取了一次时钟。

`effective_from` 与 `effective_to` **各是一处**（同一个用例里两处同形状竞态），
⇒ 该用例的偶发失败概率比 9.6.1.3 那处**更高**（两处各 ≈ 两次调用间隔 ÷ 1 秒）。

## 决策 / 结论

- **修法：只取一次时钟**。`有效时间写入后读回一致` 改走新辅助方法
  `保存配置并回读有效期(tenant, code, effectiveFrom, effectiveTo)`：两个时刻由调用方**各取一次**
  存进局部变量，写入与断言共用这两个读数（照 `timestamptz写入没有静默偏移` 的既有写法，不另起风格）。
  **辅助方法与回归用例共用** ⇒ 谁把「取两次时钟」的写法搬回来，回归用例在钉死的时钟下立刻红。
- **确定性证明（新增用例 `有效时间只取一次时钟_跨整秒边界现场下依然成立`）**：
  - 把测试时钟抽成可钉死的 `clockSource`（默认仍读 JVM 墙钟，`at()` 走它，`@AfterEach` 还原）；
    默认路径的取值与改造前**逐字等价**。
  - 用例把时钟钉死成「**每次读取整整晚 1 秒、且永远停在整秒边界前 1ns**」⇒ **任意两次相邻读取**
    截断到秒之后必然差**整 1 秒**（就是 `25.999 → 26.001` 那种必翻车的现场），且与「两次调用之间
    隔了多久」无关 ⇒ **确定性，不靠运气**。
  - 现场里：**旧写法必须红**（`assertThatThrownBy` 钉死）、**新写法必须绿**（写回库里的正是那唯一一个
    被取到的时刻 —— 断言值 == 钉死时钟的那一次读取）。
  - **变异验证**：把辅助方法改回「写入取一次时钟、断言再取一次时钟」⇒ 回归用例立刻红，
    失败形状与 L2 那次一致（`expected` 比 `was` 晚整秒）。

## 关键 trade-off / 讨论

- **否决「把断言改成容差比较 / 放宽到 ±N 秒」**：那是**掩盖**竞态而不是消除它 —— 时钟漂移一大就照样假红，
  而且真回归（写回值写错）会被容差吞掉。取一次时钟才是根治。
- **否决「在断言前 sleep 或重试」**：靠运气等它不跨边界，本质上仍是假红。
- **否决「把 `at()` 改成不截断到秒」**：截断到秒是本仓既有口径（落库列本身是 `timestamptz`，秒级足够），
  改它会牵连其余 16 处调用点的语义，超出本车道范围。
- **「钉死时钟」做成 `Supplier` 而不是注入 `Clock` Bean**：本模块业务侧一律用 `OffsetDateTime.now()`
  直读墙钟（未注入 `Clock`），**业务代码一行不动**是本车道的硬约束；测试侧自成一个时钟源即可造出现场。
- **回归用例用另一张配置单跑「旧写法」**：`saveConfig` 按 `config_code` upsert，同一张单会走更新分支，
  触发器 `dropship_ownership_clear_on_change` 会介入；另起 `DSH-CLOCK-OLD` 才跑得出「两次取时钟」的真实形状。
- **辅助方法签名把两个时刻做成入参**（而不是方法内部取时钟）：`effective_from` 与 `effective_to`
  是**语义不同**的两个值（起点/终点），不是一个值的两次读取 ⇒ 逐值各取一次，不合并。
  回归用例正是靠这个入参把「唯一一次读取」显式钉住。
- **格式**：本仓 `spotless` 未绑定构建生命周期且全仓 333 个文件本来就违反 gjf
  （`mvn spotless:check` 在 `services/trade` 报 333 个文件违规），本仓实际是**手工排版**。
  本次新增代码的排版与上一条车道（`3ac5a282`）新增的代码**逐行同构**（同样的 javadoc 断行、
  同样的 `assertThat(Duration.between(` 折行）；**未跑 `spotless:apply`**（会重排 333 个他人文件）。

## 独立核对结论（本车道自己做的第二遍核对）

本文件**全部 `at(...)` 调用点逐条过了一遍**（本文件**无** `hoursAgo(...)`），共 **19 处**：

| 类别 | 处数 | 处置 |
| --- | --- | --- |
| **写入值与断言值各取一次时钟的竞态** | **2**（`effective_from` 一处、`effective_to` 一处） | **已修** |
| 本来就取到局部变量（`timestamptz写入没有静默偏移` 的 `sent = at(-7)`） | 1 | 未动（已正确） |
| 夹具只写不读（`seedTenant` 的 `synced_at` / `created_at` / `updated_at` / `observed_at`） | 9 | 未动（无读回断言，不构成竞态） |
| `ownershipConfirmedAt`（只写不读：相关用例断言的是「被拒绝」或 `isNotNull()`，不比时刻） | 5 | 未动 |
| 回归用例里**刻意**保留的旧写法 | （新增代码内） | 刻意保留 |

⇒ **本文件内没有第三处同类竞态。**

- **跨时钟域比较复核**：本文件所有 `OffsetDateTime` 读回点共 5 处 ——
  `ownership_confirmed_at` 只做 `isNotNull()` 判定（不比时刻）、
  `observed_at` 与 `sent`（同一个值的往返，`timestamptz` 微秒精度 + `at()` 截秒 ⇒ 精确相等，无需容差）、
  `effective_from` / `effective_to`（本车道修的两处）。
  ⇒ **本文件内没有「JVM 墙钟 vs DB 时刻」且无容差的比较**，不需要额外修。
- **⚠️ 别的文件里的同类但当前安全的比较（不在本车道范围，未改）**：
  `backend/services/trade/src/test/java/com/starcon/trade/DropshipMarginIntegrationTest.java`
  第 1212–1217 行有一处「JVM 墙钟 vs DB 时刻」的跨时钟域比较，**带 120 秒容差**
  （`Duration.between(...)` + `.isLessThan(120)`）⇒ 当前不会假红，**只取证上报、不动**。

## 涉及文件

- `backend/services/trade/src/test/java/com/starcon/trade/DropshipConfigIntegrationTest.java`
  （改：`有效时间写入后读回一致` 的有效期断言改为只取一次时钟；新增辅助 `保存配置并回读有效期`；
  新增可钉死的 `clockSource` / `WALL_CLOCK` / `now()` 与 `@AfterEach restoreClockSource`；
  新增回归用例 `有效时间只取一次时钟_跨整秒边界现场下依然成立`；类注释补「时刻基准同源」一条）
- `docs/product/capability-blueprint/module-progress.md`
  （**只改 9.6.1.1 那一行的「证据」格**（在末尾追加本次修复说明），「状态」格保持 `已交付` 不变，
  未重排整表、未动其它行；证据格内分隔符用**全角 `｜`**，不引入 ASCII 半角 `|`）
- `docs/sessions/2026-10/2026-10-21-dropship-config-clock-assert-fix.md`（本文件）

**零改动**：`DropshipConfigService` 等任何业务代码、任何 Flyway 迁移、`packages/api-contracts`、
任何前端文件、`DropshipTrackingIntegrationTest.java`（9.6.1.3 的文件，属上一条车道）。

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ⇒ 成功（退出码 0，仅 JDK 自身告警）。
- **L0 类型闸**：`pnpm typecheck` ⇒ 3/3 turbo tasks 成功（本车道未动任何 TS）。
- **L1 模块闸**：`./mvnw -pl services/trade -am test -Dtest='DropshipConfigIntegrationTest'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ **25 例全绿**（原 24 + 新增 1），0 failure / 0 error，BUILD SUCCESS。
- **确定性连跑 5 轮**：每轮 25/25 全绿（9.483s / 9.336s / 9.240s / 9.274s / 9.331s）
  ⇒ 新回归用例是确定性的。
- **变异验证**：把辅助方法改回「写入取一次时钟、断言再取一次时钟」⇒
  `有效时间只取一次时钟_跨整秒边界现场下依然成立` 立刻 FAIL
  （`expected: 2026-10-18T11:13:18Z` / `but was: 2026-10-18T11:13:16Z`，形状与 L2 那次一致）；
  变异已回滚。
- **容器**：verify 期间同时存活的 testcontainers PG 容器峰值 = **1**（`postgres:16-alpine`）；
  跑完后 `docker ps --filter "label=org.testcontainers.sessionId"` = **0**，
  `docker ps -a --filter "ancestor=postgres:16-alpine"` 为空 ⇒ 已被 ryuk 回收干净。

## 后续待办

- 本仓还散落着「JVM 墙钟 vs DB 时刻」的**跨时钟域**比较（例如 `DropshipMarginIntegrationTest`
  里 120 秒容差的时刻比较）：与本次「同一个值取两次」不是同一形状，但同属时间基准脆弱面，
  可在后续专项车道统一收敛（同源 DB 时钟）。
- 本车道**未跑**全量 `mvnw verify`（按调度纪律由调度者在集成头上每批只跑一次）。

## 关联

- `docs/product/capability-blueprint/09-order-and-fulfillment.md` §9.6.1.1（蓝图原文）
- `docs/product/capability-blueprint/module-progress.md`（台账 9.6.1.1 行）
- `docs/sessions/2026-10/2026-10-21-dropship-tracking-clock-assert-fix.md`
  （上一条车道：9.6.1.3 同形状假红，本车道是其取证时发现的第二处）
