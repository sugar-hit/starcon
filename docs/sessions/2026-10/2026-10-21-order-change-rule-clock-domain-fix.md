# 2026-10-21 9.1.1.3 平台变更规则：种子的生效时刻必须取自 DB 时钟

## 摘要

**根因一句话**：测试用 **JVM 墙钟** 写 `order_platform_change_rule.effective_from`，而服务端用 **DB 时钟**
（PostgreSQL `now()` = `transaction_timestamp()`）判规则是否落在生效窗口内 ⇒ 这条断言实际是**跨时钟域比较**，
健康态余量只有约 50ms（插入到查询之间），DB 侧时钟相对宿主滞后超过这个余量就假红。

修法：**只改测试，零迁移、零生产代码** —— 种子的 `effective_from` 改为**由 DB 自己算**
（`(now() - interval '1 minute')`，同一个事务内），并把「生效窗口判定本身有效」写成**可执行不变量**。

## 取证链摘要（调度者已取证，本轮沿用）

1. **文件级**：本批 7 条车道对 `OrderChangeCancel*` 与 `order_platform_change_rule` 零触碰
   （`git diff --name-only dd607c5b 1755c23b` 无命中）。
2. **基线对照**：`dd607c5b` 上单跑该类 ⇒ 40/40 全绿；`1755c23b` 上单跑该类 ⇒ 40/40 全绿；
   跑完整 trade 模块 ⇒ 全绿。
3. **等价注入复现（决定性）**：把种子的 `OffsetDateTime.now()` 改成 `OffsetDateTime.now().plusSeconds(1)`
   （等价于「DB 时钟比宿主慢 1 秒」）⇒ 单跑该类复现出与 L2 完全一致的 6 例红（3 failures + 3 errors、
   同样 6 个用例名、同样 `没有登记「减少数量」` 报错文本）。
4. **DB 状态直查**：失败时超管连接看到该规则行确实存在且各字段都对，RLS 策略与 FORCE RLS 均正常
   ⇒ 行是被 WHERE 里的 `now()` 窗口排除，不是权限问题。

**本轮独立复现**：把种子改成「DB 时钟 **+ 1 小时**」（一条**尚未生效**的规则）⇒ 复现出**完全一致**的 6 例红
（同样的用例名、同样的报错文本），且新增的不变量用例同时红 ⇒ 根因确认无误。

## 修法与理由

服务端 `OrderChangeCancelService.evaluateRule` 的
`effective_from <= now() and (effective_to is null or effective_to > now())` 是**已交付模块的 API 口径**
（「规则按 DB 当前时刻是否在生效窗口内」），**本轮不动**。错的是测试把生效时刻写在**另一个时钟域**里。

| 位置 | 改前 | 改后 | 理由 |
| --- | --- | --- | --- |
| `rule(...)` 种子（`:334`） | `?::timestamptz` ← `OffsetDateTime.now()`（JVM 墙钟） | `(now() - interval '1 minute')`（**DB 自己算**） | 让「种下去的时刻」与「查询时比较的时刻」落在同一时钟域；留 1 分钟余量也贴合语义：**刚登记的规则就是已生效的**，且彻底免疫任何合理的时钟抖动 |
| 新增 `dbNow()` / `dbEffectiveFrom()`（`:356`、`:361`） | — | `select now()` 读 DB 时钟 | 服务层只收一个 `OffsetDateTime` 参数、没法让 DB 自己去算时，从 DB 时钟取值 |
| `createRule` / `updateRule` 调用（`:1403`、`:1425`、`:1447`、`:1465`） | `effectiveFrom = null` ⇒ 服务层 default 用 **JVM 墙钟** | 显式给 `dbEffectiveFrom()` | 同类审计：这两条路径把 **JVM 墙钟**写进 `effective_from`，而 `effective_to`/窗口判定全在 DB 时钟上（`updateRule` 会用 default **覆写** `effective_from`） |
| 新增不变量用例（`:860`） | — | 见下 | 把「生效窗口判定本身有效」钉成可执行不变量 |

### 审计结论：本测试类里其它写入 `timestamptz` 的时刻

| 写入点 | 时刻来源 | 被谁比较 | 判定 |
| --- | --- | --- | --- |
| `platformOrder(...)` 的 `submitted_at` / `created_at` / `updated_at` | JVM 墙钟 | 服务端 `orderAgeHours` 用 **JVM** `OffsetDateTime.now()` 比 | **同一时钟域**，不是跨域 ⇒ **保持原样** |
| `platformOrder(...)` 的 `sales_order_line.created_at` | JVM 墙钟 | 只作种子，查询不带时间窗口 | 保持原样 |
| `recordProgress(..., observed)` / `observedAt` | JVM 墙钟 | 往返零漂移断言（比的是**同一瞬间**） | 保持原样 |
| `order_cancellation_line.created_at` | JVM 墙钟 | 只作种子，查询不带时间窗口 | 保持原样 |
| `service.createRule` / `updateRule`（本类调用处） | 改前 JVM 墙钟 | `effective_from` 会被 DB `now()` 判窗口 | **同类，已修**（显式给 DB 时钟） |
| HTTP 端点建规则（`断言2_伪造租户上下文无效`） | 服务层 default（JVM 墙钟） | 该行**不参与任何窗口判定**，只做租户隔离计数 | 保持原样（请求体不带时刻；改它要动测试的 ObjectMapper 与请求体形状，收益为零） |

⇒ **同类跨域比较全类只有一处（`rule(...)` 种子），已修**；其余均不是跨域或不做窗口判定。

## 断言强度变化（只增不减）

- **没有削弱、删除或跳过任何现有断言**，也没有放宽任何断言来「让它绿」。
- **新增 1 例**：`规则生效时刻取自DB时钟_种下去就已落在生效窗口内`（`:860`），断言三件事：
  1. 种进去的 `effective_from` **≤** 查询时刻的 DB `now()`（窗口不变量）；
  2. `effective_from` 与 DB `now()` 的间隔 **≥ 30 秒**（余量不变量）——
     **种子一旦被改回 JVM 墙钟，这个间隔立刻塌到 0（甚至为负），断言必红**，
     不依赖「两个时钟恰好同步」；
  3. 按**服务端同一套 WHERE**（DB 时钟）必须能命中刚种下的规则。
- 测试类用例数：40 ⇒ **41**。

## 验证（真实输出）

1. **目标类绿**
   `./mvnw -pl services/trade -am test -Dtest='OrderChangeCancelIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
   ⇒ `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`
2. **变异测试（证明断言不空转）**
   - 种子改成「DB 时钟 **+ 1 小时**」（尚未生效的规则）⇒ `Tests run: 41, Failures: 4, Errors: 3` / `BUILD FAILURE`
     —— 红的正是**原来那 6 例**（`平台规则不允许减量时_申请被拒`、`超出平台可改窗口时_申请被拒`、
     `超出单次减量上限时_申请被拒并报出上限`、`平台允许且在窗口内_减量申请通过并算出退款金额`、
     `平台单减量后建立平台回写任务_且平台未接受前不能确认变更`、`数量调整明细留痕原量申请量与差额_退款按差额算`，
     报错文本同为 `没有登记「减少数量」的可变更规则`）**＋ 新增的不变量用例**（7 例红）。
   - 改回「DB 时钟 **− 1 分钟**」⇒ `Tests run: 41, Failures: 0, Errors: 0` / `BUILD SUCCESS`。
3. **时钟域无关性**（同一命令三次，只改 `TZ`）
   `TZ=UTC` / `TZ=Asia/Shanghai` / `TZ=America/New_York` ⇒ 三次均
   `Tests run: 41, Failures: 0, Errors: 0` / `BUILD SUCCESS`。
4. **回归（整个 trade 服务）**
   `./mvnw -pl services/trade -am test` ⇒ `Tests run: 1769, Failures: 0, Errors: 0, Skipped: 0`
   （94 个测试类）/ `BUILD SUCCESS`（trade 模块 11:17 min）⇒ 没有别的类被这次改动带红。
5. `./mvnw -pl services/trade -am compile` ⇒ 退出码 **0** / `BUILD SUCCESS`；
   `git status --short` 在提交后为空。

## 涉及文件

- `backend/services/trade/src/test/java/com/starcon/trade/OrderChangeCancelIntegrationTest.java`
  （**唯一**被改的源文件：种子时钟域 + 2 个 DB 时钟帮手 + 1 例新不变量 + 4 处 `effectiveFrom` 显式值）
- `docs/sessions/2026-10/2026-10-21-order-change-rule-clock-domain-fix.md`（本文件）

## 未在范围内的事项（留给后续车道 / 调度者）

1. **生产侧同类写入口未动**（本轮明令不许改生产代码）：
   `OrderChangeCancelService.createRule` / `updateRule` 在 `input.effectiveFrom() == null` 时用
   **JVM 墙钟** `OffsetDateTime.now()` 写 `effective_from`，而窗口判定在 DB `now()` 上
   ⇒ **真实 API 调用**建出来的规则，同样存在「刚登记却被判成还没生效」的窗口（宿主与 DB 时钟不同源）。
   本轮只把**测试侧**显式值钉到 DB 时钟，生产侧这条默认值仍是 JVM 墙钟。**建议下一轮单独派车道**
   把 `createRule` / `updateRule` 的 default 改成 DB 时钟（或注入 `Clock`），**零迁移**即可。
2. **全仓同类扫描未做**：本轮只审计了本测试类。全仓仍有别的模块存在
   「JVM 墙钟写时刻 + DB `now()` 判窗口」的形状，线索见
   `docs/backlog/2026-10/2026-10-20-cross-clock-domain-test-assertion.md`
   （那是 channel 4.3.1.1 那一例）与 `docs/sessions/2026-10/2026-10-21-trade-fulfillment-clock-fix.md`
   （9.3.3.1 写侧那一族）。
3. **台账未改**：`docs/product/capability-blueprint/module-progress.md` 的状态/证据格由调度者统一补，
   本轮按任务书**未触碰**，避免与调度者冲突。
4. **未执行 `lane.mjs land`**（由调度者执行）；**未跑 `mvnw spotless:apply`**（会整仓重排 146 个文件）。

## 关联

- `docs/backlog/2026-10/2026-10-20-cross-clock-domain-test-assertion.md`（同一缺陷家族的断言侧取证）
- `docs/sessions/2026-10/2026-10-21-trade-fulfillment-clock-fix.md`（同一缺陷家族的写侧修复，9.3.3.1）
- `backend/services/trade/src/main/java/com/starcon/trade/OrderChangeCancelService.java:502`（`evaluateRule`，
  DB 时钟口径，未改动）
