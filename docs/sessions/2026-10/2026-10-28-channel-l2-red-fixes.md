# 2026-10-28: channel 服务 L2 批次闸红点修复（族 A 时钟域 / 族 B 跨钟域 409 / SSL / 容器泄漏）

> 车道 `fix/channel-l2-red`（从集成头 `integration/module-dev` @ `f8b9383a` 切出）。
> 这是 L2 批次闸（round 115）定位到的**红点修复**，不是新蓝图模块 ——
> **无迁移、无端点、无页面、无契约改动**。
> 本车道同时修**生产代码**一处（族 B），其余是 channel 服务的测试侧改动。
>
> **本轮是「重派」**：上一轮 worker 在「channel 全模块正在跑」时崩掉，留下 151 个已改未提交的文件。
> 本轮**没有推倒重来**，而是逐项核验它们的覆盖面与正确性（见 ⑥），
> **并补上了上一轮漏掉的族 A-3**（`SalesTaxIntegrationTest`，同族的第三个实例，见 ②-2）。

## 摘要

| 项 | 值 |
| --- | --- |
| 族 A | `IpRiskLibraryIntegrationTest#lifecycleRules` 拿 **JVM 墙钟**比 **DB 写的 `disabled_at`**，容差 5 分钟 ⇒ 负载型假红（实测偏差 **14m58.795119S**） |
| 族 A-2 | 同一类的**静态初始化**用裸 JDBC（`pg.getJdbcUrl()` 没带 `sslmode=disable`）⇒ SSL 握手失败 ⇒ `ExceptionInInitializerError` ⇒ **整类 14 例全灭**（空闲机器也复现） |
| 族 A-3 | **同族的第三个实例（本轮重派时由 L1 全服务跑发现）**：`SalesTaxIntegrationTest#timestamptzRoundTrips` 拿 **宿主 JVM 墙钟**比 **DB 写的 `parsed_at`**（`parsed_at` 由服务层 `dbNow()`＝`select now()` 打戳）⇒ 跨钟域 ⇒ 实测偏差 **924s**；根因是 **Docker Linux VM 的钟落后 macOS 宿主钟 925.540s**（日志时间线里的那段空档） |
| 族 B | `OAuthAuthorizationService` 把 `credential_ref.authorized_at` 绑**宿主 JVM 墙钟**，而 DB 的 `credential_window` CHECK 拿它跟 **DB 的 `now()`** 比 ⇒ 跨钟域 ⇒ 违反约束 ⇒ 被通用处理器翻成**误导性的 409**（L2 红 10/23） |
| 放大器 | channel 内 **115/148** 个测试类从不 `pg.stop()` ⇒ 一个模块就能堆 ~110 个 `postgres:16-alpine` 容器 ⇒ 机器进 swap ⇒ JVM 被卡 15~17 分钟 ⇒ 上面几条全部被放大 |
| 生产代码 | **改了 1 处**：`OAuthAuthorizationService` 的 `authorized_at` 改由 DB 打戳（`now()`），**约束一个字没动** |
| 迁移 / 端点 / 页面 / 契约 | **无 / 无 / 无 / 无** |
| L0 | `mvnw -q compile` exit 0；`pnpm typecheck --force` **3 successful / 3 total / 0 cached**（强制真跑，不吃 turbo 缓存） |
| L1（两个红点类） | `Tests run: 39, Failures: 0, Errors: 0`（`IpRiskLibraryIntegrationTest` 14 + `OAuthAuthorizationIntegrationTest` 25） |
| L1（族 B 专项判据） | 同一时钟错位开关（DB 钟拨慢 10 分钟）下：**修复前 `Tests run: 25, Failures: 11`（全是 409 误导文案）→ 修复后 `Tests run: 25, Failures: 0`** |
| L1 全模块 | `channel` 全服务：**`Tests run: 2387, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS**（18:44 min）；容器曲线：同期存活峰值 **1**，结束剩余 **0** |

## 决策摘要

### ① 族 B 是**生产代码缺陷**，不是夹具缺陷（调度者已独立复核确认）

`backend/services/channel/src/main/resources/db/migration/V1__channel_registry_connections_and_shops.sql:226`

```sql
ALTER TABLE credential_ref ADD CONSTRAINT credential_window
  CHECK(authorized_at <= now() + interval '5 minutes');
```

右端是 **PG 的 `now()`**（DB 钟域），容差 5 分钟；而写入端（修复前）绑的是**宿主 JVM 墙钟**：

```java
java.sql.Timestamp authorizedAt = Timestamps.at(Instant.now());   // ← 宿主墙钟
"insert into credential_ref(...) values (?,?,?,?,?,?,?)"           // ← 喂进去
```

⇒ 宿主钟比 DB 钟快 >5 分钟 ⇒ CHECK 违反 ⇒ `DataIntegrityViolationException`
⇒ channel 的通用异常处理器（`ApiErrorHandler` / `ListingComplianceErrorHandler`）把**任何**
`DataAccessException` 翻成同一句 409「店铺编码重复、平台账号已被绑定，或该店铺仍被单据引用。」
⇒ **一条「时间戳比 DB 时钟快了 >5 分钟」被谎报成「店铺编码重复」**。
（同族记录：`docs/backlog/2026-10-21-global-error-handler-masks-code-defects-as-409-400.md`）

**⇒ 修法：改写入端，不改约束。** `authorized_at` 由 **DB 钟**打戳：

```java
"insert into credential_ref(...) values (?,?,?,?,?,now(),?)"
"update credential_ref set granted_scopes=?,authorized_at=now(),expires_at=?,..."
```

授权时刻**没有业务含义**（就是「写入的那一刻」），所以交给 DB 取最正确；
`credential_window` 的意图（授权时间不能是未来）**一字未动**，现在它比较的是 `now()` 与 `now()`。

**为什么族 B 是「跨用例状态碰撞」这个假设的伪命题（取证）**：
回调写入路径上**每一个**可能的约束都逐个排除了 ——

| 约束 | 会不会撞 | 依据 |
| --- | --- | --- |
| `credential_ref` `UNIQUE(tenant_id, external_ref)` | **不会** | `external_ref` = `file://<dir>/channel/<tenantId>/ebay/<accountId>`，`accountId` 每个用例随机（`"seller-" + UUID`） |
| `channel_connection` `UNIQUE(tenant_id, platform_id, authorized_account)` + 全局索引 `connection_account_owner` | **不会** | `authorized_account` = 同一个随机 `accountId` |
| `shop` `UNIQUE(tenant_id, code)` | **不会** | `code` 由 `uniqueShopCode()` 递增去重 |
| 全局索引 `shop_platform_owner(platform_id, lower(platform_shop_id))` | **本车道两例除外，且是显式构造的** | 唯一两个固定 id（`SHOP-MK-1` / `SHOP-SHARED`）**就是用来表达跨租户冲突的**；已改成「带随机后缀 + 显式共享」，冲突照样被构造，但不再污染其它用例 |
| **`credential_window`（CHECK）** | **会** | 唯一与**机器状态**（而不是用例数据）有关的约束 ⇒ 负载型红点的唯一候选 |

⇒ **L2 那 10 例 409 的成因不是「行在 23 个用例间累积」，而是「喂给 CHECK 的值来自另一个钟域」。**
且失败用例集合**完全吻合**：7 例红在**回调**（写凭据那一步）+ 3 例红在**确认**
（回调已失败 ⇒ 会话没到 AUTHORIZED ⇒ 确认先撞「该授权尚未完成平台回调」的 409）= 10，与 L2 一致。

### ② 族 A 是断言侧跨钟域比较 ⇒ 照抄 catalog 的「两端同钟域 + 夹住写入时刻」

`docs/sessions/2026-10/2026-10-27-catalog-timestamp-assertion-clock.md` 的修法：
参照时刻也取自 DB，并把**写入时刻夹在中间**（插入前取一次 DB 钟、读回后再取一次，断言落区间内）。

```java
OffsetDateTime dbBefore = dbNow();
... service.setStatus(...) ...          // 写 disabled_at = now()
OffsetDateTime dbAfter = dbNow();
assertDisabledAtWithinDbClockWindow(disabled.entry().disabledAt(), dbBefore, dbAfter);
```

- 停顿/时钟跳变只会把**区间撑宽**，不会把一个本来正确的时刻挤出区间 ⇒ 不再假红；
- **真偏 8 小时（±28800s）必然落在区间外** ⇒ 守卫照样当场抓住
  （并新增 `theDisableTimestampGuardStillRejectsTheEightHourShift` 自证：人为偏 ±8h，守卫当场拒绝）。
- 健康跑法下区间宽度只有两次 DB 往返（毫秒级），比旧的「±5 分钟」**严**得多 ⇒ **守卫没被削弱，反而更严**。

### ②-2 族 A-3：`SalesTaxIntegrationTest` 是同一个族的第三个实例（本轮重派时发现）

上一轮只点名了 `IpRiskLibraryIntegrationTest`。本轮把 **channel 整个服务**跑起来之后，
`SalesTaxIntegrationTest#timestamptzRoundTrips` 当场红：

```
[ERROR] com.starcon.channel.SalesTaxIntegrationTest.timestamptzRoundTrips -- Time elapsed: 0.015 s <<< FAILURE!
java.lang.AssertionError: [parsed_at 必须是真正的时刻，不是偏了 8 小时的本地时间]
Expecting actual: 924L to be less than: 300L
```

**取证（不是推断）**：

1. **失败用例本身只花了 15ms** ⇒ 断言内部**没有**发生停顿 ⇒ 924s 的偏差不是「两次读数之间卡了一下」。
2. **日志时间线里有一段 925.540s 的空档**：
   `05:11:02.538 → 05:26:28.078`，空档前后紧挨着
   `HikariPool-9 - Thread starvation or clock leap detected (housekeeper delta=15m54s961ms)`。
   ⇒ 机器在这段时间里整体卡死（`load average 8.30`、`swap 5GB/6GB`，同期还有 3 条车道在跑全量）。
3. **两端的钟域不同**（这是病灶）：
   - `parsed_at` 由服务层 `SalesTaxService#dbNow()` 打戳，`dbNow()` 是 **`select now()`** ⇒ **DB 的钟**；
   - 旧断言比的是 `java.time.Instant.now()` ⇒ **宿主 JVM 墙钟**。
   ⇒ Docker Desktop 的 Linux VM 钟一旦落后 macOS 宿主钟（VM 被挂起/负载挤压时就会发生），
   断言就必然假红。事后实测宿主↔容器偏移已回到 0s（VM 钟随后自愈），所以**只有撞上那段空档的用例会红**。

**⇒ 修法与族 A 完全同构**：参照时刻也取自 DB，且把写入时刻夹在中间
（`dbBefore = select clock_timestamp()` → 写入 → `dbAfter = select clock_timestamp()`，
断言写回值落在 `[dbBefore, dbAfter]`）。
注意参照时刻必须用 **`clock_timestamp()`（语句时刻）**而不是 `now()`（事务开始时刻）——
后者在长事务里会被冻结，那正是 924s 偏差的来源。

**守卫强度没有被削弱**：新增 `theTimestampGuardStillRejectsTheEightHourShift` 自证用例，
人为把写回值偏 ±8 小时，守卫当场拒绝（基准取自 DB 钟 ⇒ 这条用例自己不会假红）。

**全仓同类排查（逐个核验，不是抽样）**——channel 测试里所有拿宿主钟去比 DB 值的断言：

| 断言 | 值的一端 | 比的另一端 | 钟域 | 结论 |
| --- | --- | --- | --- | --- |
| `SalesTaxIntegrationTest#timestamptzRoundTrips` | DB（`select now()`） | 宿主 `Instant.now()` | **跨** | **本轮修** |
| `IpRiskLibraryIntegrationTest`（族 A） | DB | 宿主 `OffsetDateTime.now()` | **跨** | 已修 |
| `OAuthAuthorizationIntegrationTest`（族 B） | DB（CHECK 比 DB `now()`） | 宿主钟喂进去的值 | **跨** | 已修 |
| `WebhookReceiptIntegrationTest#...receivedAt` | 宿主 `OffsetDateTime.now(UTC)` | 宿主窗口 | 同 | 不改 |
| `WebhookDedupIntegrationTest#receiveAndProcessingTimes...` | 宿主 | 宿主窗口 | 同 | 不改 |
| `CompliancePrecheckIntegrationTest`（`checked_at`） | 宿主 `OffsetDateTime.now()` | 宿主窗口 | 同 | 不改 |
| `ListingCompliancePrecheckIntegrationTest`（`scanned_at`） | 宿主 `OffsetDateTime.now(UTC)` | 宿主窗口 | 同 | 不改 |
| `AdGroupKeywordReportIntegrationTest#cacheHasExplicitInvalidation` | 宿主（服务层 `OffsetDateTime.now()`） | 宿主 `now()` | 同 | 不改 |

⇒ **channel 内跨钟域的断言到此为止全部收敛**；其余 5 处都是「宿主写、宿主比」，同钟域，不需要动。

### ③ 族 A-2（SSL）是确定性的，与钟域无关 ⇒ 补 `sslmode=disable`

`pg.getJdbcUrl()` 不带 `sslmode`，pgjdbc 默认 `sslmode=prefer` ⇒ 会先做 SSL 协商；
负载/握手超时 ⇒ `PSQLException: 进行 SSL 连线时发生错误` ⇒ 静态块抛 `ExceptionInInitializerError`
⇒ **整类用例全灭**（不是红一例，是全灭）。

**改到哪一层**：**channel 服务内全部 148 个用 `PostgreSQLContainer` 的测试类**，
统一在容器声明上加 `.withUrlParam("sslmode", "disable")`。
- 理由一：**一行改动同时覆盖**了裸 JDBC 连接与 Spring 应用自己的连接池（`DB_URL` 也取自 `pg.getJdbcUrl()`）；
- 理由二：族 A-2 在**空闲机器上也复现**（不是负载型），属于 latent 缺陷 —— 只修本车道点名的 2 个类，
  下一次全量跑换一个类照样全灭；
- 理由三：本车道本来就要逐个改这批文件（容器回收，见下），顺手一并改，**不扩大改动面**。
- **边界**：别的服务（warehouse / reporting / platform / identity）还有同样的写法，**本车道不动**，留给后续专门一轮。

### ④ 容器泄漏是「让 L2 变绿」的必要条件（放大器，不是族 B 的成因）

`docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`（2026-10-22 已登记，未处置）：
仓库里 343 个集成测试类用 `PostgreSQLContainer`，其中 97 个不 `.stop()`；
Ryuk 只在 JVM 退出时按 session 标签回收 ⇒ 全量跑之前容器一直活着 ⇒ 机器进 swap ⇒ JVM 被卡十几分钟。

**channel 内的实测面**：148 个用容器的类里 **115 个从不 `.stop()`**。
⇒ 按仓库既有写法（照抄 `OAuthAuthorizationIntegrationTest` 的 `@AfterAll`）补回收：
**先停外部服务（identity 桩 / 本地 stub 服务器），再 `pg.stop()`** —— 顺序反了会出现
「容器已停但还有 in-flight 请求打进来」（该类的注释里记了这个教训）。
**没有**加任何 `drop schema`（那个语句已因锁表爆掉被删除，不能加回来）。

**本轮复核出的精确分布**（按 HEAD 逐文件比对 `pg.stop()` 得出，可直接对账）：

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| HEAD 上**已有** `pg.stop()` 的 PG 类 | **33** | 本车道只给它们加 `sslmode=disable` |
| HEAD 上**没有** `pg.stop()` 的 PG 类 | **115** | 本车道给它们补 `pg.stop()`（与上一轮记的「115/148」**完全吻合**） |
| 其中：**新增**一个 `@AfterAll` 方法 | **98** | 原本没有收尾方法可加的 |
| 其中：**挂到已有** `@AfterAll` 上 | **17** | 已有收尾方法（如 `stopStubs` / `stop`），只补一行 `pg.stop()` |
| **合计 PG 类** | **148** | 98 + 17 + 33 = 148 ✅ |

顺带：`AccessSecurityIdentityStub` / `LinkageRiskIdentityStub` 原本**没有** `close()`，
它们的 `HttpServer` 端口会占满整个 JVM 生命周期 ⇒ 给这两个桩补了 `close()`。

### ⑤ 族 B 的验收标准：用仓库自带的「时钟错位开关」把 L2 的条件搬进单类跑

`SyncHealthIntegrationTest` 里已有一个**把数据库容器自己的墙上时钟拨动 N 秒**的开关
（`date -u -s`，需要 `CAP_SYS_TIME`，所以只在开关打开时才提权）。
把它抄到 OAuth 这一类（`starcon.channel.oauth.dbClockSkewSeconds`）：

- **拨慢（负数）** ⇒ DB 钟比宿主钟慢 ⇒ 宿主钟写的 `authorized_at` 落在 `now() + 5 分钟` 之外
  ⇒ CHECK 违反 ⇒ 409。**这正是 L2 的条件。**
- 修复前：`-600`（拨慢 10 分钟）⇒ **25 例红 11 例**（L2 的 10 例 + 新增的回归用例）；
- 修复后：同样 `-600` ⇒ **25/25 全绿**。

⚠️ 已知的不稳定来源：Docker Desktop 的 **VM 自身的时间同步**会把手工拨动的偏移纠回来
⇒ 只在静态块里拨一次的话，跑到后面用例时偏移可能已被纠回。
⇒ 在 `@BeforeEach` 里**每个用例前重新钉一次**，复现才稳定。

### ⑥ 本轮（重派）对上一轮 152 个文件的覆盖面核验

上一轮 worker 崩在「channel 全模块正在跑」时，留下 **151 个已改未提交**的文件
（1 个生产文件 + 150 个测试文件）＋ 1 份会话日志。本轮**没有推倒重来**，
而是逐项核验它们的覆盖面与正确性：

| 核验项 | 方法 | 结果 |
| --- | --- | --- |
| 有没有漏改的测试类 | 对 channel 测试目录里每个含 `PostgreSQLContainer` 的文件，机器统计 `start` / `.stop()` / `@AfterAll` 三个计数 | **148/148** 全部有 `@AfterAll` 且都含 `pg.stop()`；**零漏网** |
| `sslmode=disable` 覆盖面 | 同上，逐文件查 `sslmode` | **148/148** 全带 |
| 回收顺序对不对 | 逐个解析 `@AfterAll` 方法体，比对「外部桩 stop/close」与「`pg.stop()`」的行序 | **148/148** 全部是**先停外部服务、再停容器**；**零个反序** |
| 会不会误删断言 | 把 150 个测试文件的 diff 里**所有删除行**汇总去重 | 只有 3 类：148 行容器声明、IpRisk 的跨钟域断言（4 行）、OAuth 两个固定店铺 id（2 行）、1 个不再用的 `import` ⇒ **没有任何一条断言被删掉** |
| 多租户三断言还在不在 | 查 `AccessSecurityTenancyIntegrationTest` | `tenantsAreIsolated` / `forgedTenantHeaderIsIgnored` / `pooledConnectionsDoNotBleedTenants` **原样在位**（该文件的 diff 只动过容器声明 + 追加 `@AfterAll`） |
| 有没有改错的东西 | 全量 diff 逐类归类 | 改法**一致且正确**，**不需要修正**；本轮只**补**了上一轮漏掉的族 A-3（见 ②-2） |

**注意 `grep -rL 'stopTestResources'` 这个查法在本仓库会漏报** ——
上一轮给不同文件用了不同的 `@AfterAll` 方法名（`stopStubs` / `stop` / `stopTestResources` …），
按方法名 grep 会误判成「漏了 90 多个类」。真正的判据是
「**含 `PostgreSQLContainer` 的文件里，有没有 `pg.stop()`**」，上面按这个判据核。

## 关键取证（本机实测，不是推断）

1. **L2 时间线对齐**（`/Users/guan/Projects/starcon-worktrees/integration/.dev-agent/l2-round115.log`）：
   - `00:46:55` ~ `00:47:02` 连续多条 `HikariPool-N - Thread starvation or clock leap detected (housekeeper delta=15m27s9xx ms)`；
   - `OAuthAuthorizationIntegrationTest` 紧接着在 `00:47:02` 起跑、`00:47:09` 报 10 例红；
   - 同一族的 `IpRiskLibraryIntegrationTest` 在 `23:54` 报 `14M58.795119S` vs `5M`（同批 `delta=15m28s`）。
   ⇒ **同一次机器级事件同时解释了两族**：宿主钟与 DB 钟错开 ≈15 分钟。
2. **失败用例集合完全吻合**：族 B 的 10 例 = 7 例红在回调 + 3 例红在确认（回调失败的次生）；
   错误路径用例（凭据被拒 / 权限不足 / 用户取消 / 无 profile …）**全部通过** —— 它们根本走不到写凭据那一步。
3. **约束原文**：`V1__...sql:226` 的 `credential_window`（见上）。
4. **自证用例**：`theCredentialWindowGuardRejectsAHostClockValue` 直接把
   `authorized_at = now() + 10 minutes` 写进 `credential_ref`，DB **当场拒绝并指名 `credential_window`**
   ⇒ 约束名与失效机制都在测试里钉住了（不依赖任何真实时钟）。
5. **修复前红 / 修复后绿**：见「验证」一节。

## 文件清单

| 文件 | 改了什么 |
| --- | --- |
| `backend/services/channel/src/main/java/com/starcon/channel/oauth/OAuthAuthorizationService.java` | **唯一的生产代码改动**：`credential_ref.authorized_at` 的 INSERT 与 UPDATE 都改由 **DB 钟**打戳（`now()`），不再绑宿主 JVM 墙钟；约束不动 |
| `backend/services/channel/src/test/java/com/starcon/channel/OAuthAuthorizationIntegrationTest.java` | 加 `sslmode=disable`；加「授权时刻由 DB 打戳」回归用例 + 「`credential_window` 守卫自证」用例；加时钟错位开关（拨慢 DB 钟）；两个固定外部店铺 id 改成「带随机后缀 + 显式共享」 |
| `backend/services/channel/src/test/java/com/starcon/channel/IpRiskLibraryIntegrationTest.java` | 加 `sslmode=disable`；停用时刻断言改**两端同钟域 + 夹住写入时刻**；新增守卫自证用例；`@AfterAll` 补 `pg.stop()` |
| `backend/services/channel/src/test/java/com/starcon/channel/SalesTaxIntegrationTest.java` | 加 `sslmode=disable`；`@AfterAll` 补 `pg.stop()`；**本轮新增**：`parsed_at` 时刻断言改**两端同钟域 + 夹住写入时刻**（族 A-3），并新增守卫自证用例 |
| `backend/services/channel/src/test/java/com/starcon/channel/*Test.java`（其余 145 个用容器的类） | 容器声明加 `.withUrlParam("sslmode", "disable")` |
| `backend/services/channel/src/test/java/com/starcon/channel/*Test.java`（115 个从不 `.stop()` 的类） | 补 `@AfterAll` 回收：先停外部桩，再 `pg.stop()` |
| `backend/services/channel/src/test/java/com/starcon/channel/AccessSecurityIdentityStub.java`、`LinkageRiskIdentityStub.java` | 补 `close()`（停掉内部的 `HttpServer`） |
| `docs/sessions/2026-10/2026-10-28-channel-l2-red-fixes.md` | 本文件 |

**未改**：任何迁移文件、任何端点 / DTO / 契约、`packages/api-contracts`、`packages/sdk`、前端页面、
`docs/product/capability-blueprint/module-progress.md`（本车道不是蓝图模块，没有对应行）。

## 验证方式

### L0 编译闸

```
$ cd backend && ./mvnw -q compile
COMPILE_EXIT=0

$ pnpm typecheck --force      # 强制真跑，不吃 turbo 的共享缓存
 Tasks:    3 successful, 3 total
Cached:    0 cached, 3 total
  Time:   46.315s
TYPECHECK_EXIT=0
```

> ⚠️ 备注：不加 `--force` 时 turbo 会命中**共享 worktree 缓存**，回放的是**别的 worktree** 跑出来的日志。
> 本车道用 `--force` 重跑了一遍，确认是在本 worktree 里真跑出来的（路径 `/Users/guan/.../fix-channel-l2-red/apps/web`）。

### L1 定向闸（两个红点所在的测试类）

```
$ cd backend && ./mvnw -pl services/channel -am test \
    -Dtest='IpRiskLibraryIntegrationTest,OAuthAuthorizationIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false

[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.channel.IpRiskLibraryIntegrationTest
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.channel.OAuthAuthorizationIntegrationTest
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

> `IpRiskLibraryIntegrationTest` **恰好 14 例** —— 与 L2 报的 `NoClassDefFound` 14 个 error 完全吻合：
> 那不是「14 个用例各坏一次」，而是**整类 14 例被类初始化失败一次性污染**（第一个失败 ⇒ 其余全是
> `NoClassDefFound: Could not initialize class ...`）。

### 族 B 的「红在修复前 / 绿在修复后」判据（同一个开关、同一个偏移）

用仓库自带的**时钟错位开关**把 L2 的条件搬进单类跑：
`-Dstarcon.channel.oauth.dbClockSkewSeconds=-600` ⇒ **DB 容器墙上钟拨慢 10 分钟**
⇒ 宿主钟域比 DB 钟域快 600 秒 ⇒ 远超 `credential_window` 的 5 分钟容差。

| | 命令 | 结果 |
| --- | --- | --- |
| **修复前**（临时把 `authorized_at` 改回绑宿主钟） | 同一命令 | `Tests run: 25, Failures: 11, Errors: 0` —— 11 例**全是 409**，正文一律是那句「店铺编码重复、平台账号已被绑定，或该店铺仍被单据引用。」 |
| **修复后**（`authorized_at` 由 DB 打戳） | 同一命令 | `Tests run: 25, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

**本轮重派时独立复现过一遍**（不是采信上一轮的记录）：把 `OAuthAuthorizationService.java` 临时
`git checkout HEAD --` 回退到修复前，跑同一条命令 → `MVNW_EXIT=1`、`Failures: 11`；
再把修复后的文件拷回（逐字节比对确认还原一致）→ `Failures: 0`。
⇒ **同一个开关、同一个偏移，改之前红、改之后绿**，这条回归用例抓的确实是这个病因。

修复前的失败签名与 L2 **逐条同形**：

```
com.starcon.channel.OAuthAuthorizationIntegrationTest.shopAlreadyClaimedByAnotherTenantIsRejectedAtConfirmTime
    java.lang.AssertionError: Status expected:<200> but was:<409>
com.starcon.channel.OAuthAuthorizationIntegrationTest.shopSiteIsLinkedWhenPlatformReturnsAKnownMarketplace
    org.opentest4j.AssertionFailedError: expected: "AUTHORIZED" but was: 409
```

⇒ **同一个开关下、改之前红、改之后绿** ⇒ 这条回归用例抓的确实是这个病因。

**约束名在测试里钉住**（不依赖任何真实时钟）：
`theCredentialWindowGuardRejectsAHostClockValue` 直接把
`authorized_at = now() + interval '10 minutes'` 写进 `credential_ref`，
DB **当场拒绝并指名 `credential_window`**（断言走异常链，不只看外层消息）。

⚠️ 已知的不稳定来源与处置：Docker Desktop 的 **VM 自身的时间同步**会把手工拨动的偏移纠回来
⇒ 只在静态块里拨一次的话，跑到后面用例时偏移可能已被纠回（实测出现过「拨了 600 秒但用例仍全绿」）。
⇒ 在 `@BeforeEach` 里**每个用例前重新钉一次**，复现才稳定（连跑两次 11 红）。

### 容器曲线（族 A-2 / 放大器的判据）

判据不看断言，看**容器曲线**：跑 channel 整个服务的全程，每 3 秒采一次
`docker ps --filter "ancestor=postgres:16-alpine" --filter "label=org.testcontainers=true"` 的条数。

| 跑次 | 采样数 | **同期存活峰值** | 采样分布 | **跑完剩余** |
| --- | --- | --- | --- | --- |
| 修复后 第 1 跑（带族 A-3 失败那次） | 160 | **3** | 0×9 / 1×125 / 2×23 / 3×3 | **0** |
| 修复后 第 2 跑（**最终验收跑**） | 377 | **1** | 0×25 / 1×352 | **0** |

⇒ **峰值从「一个模块能堆到 110+」降到 1**，而且**跑完一个不剩**。
（峰值偶尔到 2~3 是相邻两个测试类的交接：上一类的容器还在 docker stop 的过程中，
下一类已经起了一个 —— 两个容器的 `org.testcontainers.sessionId` **不同**，
即它们属于两个不同的测试类，不是同一个容器泄漏。）

### L1 全模块闸（channel 整个服务）

```
$ cd backend && ./mvnw -pl services/channel -am test \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false

[INFO] Tests run: 2387, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  18:44 min
```

（**第 1 跑**同一条命令是 `Tests run: 2386, Failures: 1` —— 唯一那条失败就是族 A-3
`SalesTaxIntegrationTest#timestamptzRoundTrips`，本轮补上同钟域修法后第 2 跑 2387 例全绿；
2386 → 2387 多出来的那 1 例是新增的守卫自证用例。）

## 后续待办

1. **别的服务的容器泄漏**：warehouse / reporting / platform / identity 里同样有「从不 `.stop()`」的类
   （仓库级 backlog：`docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`）——
   本车道只动了 channel，**跨服务的留给后续专门一轮**。
2. **别的服务的 `sslmode=disable`**：同理，channel 之外的模块仍是裸连接写法。
3. **`ChannelService` 的 `authorized_at` 是「调用方给的业务时刻」**（`input.authorizedAt()`，
   为空才回落 `Instant.now()`）⇒ `credential_window` 对它是**有意的守卫**（不许授权时间在未来），
   **本车道不动**；但那个「为空才回落宿主钟」的分支与族 B 同形，值得下一轮单独判一次。
   **本轮补充取证（把风险说准）**：`ChannelService.java:382-384` 的回落分支写的是
   `credential_ref.authorized_at`（**同一个被 CHECK 约束的列**），回落值是宿主 `Instant.now()`
   ⇒ 宿主钟比 DB 钟快 >5 分钟时，**这条路径会撞出同一句误导性的 409**。
   但：① 回落只在**调用方不传** `authorizedAt` 时发生，传入时是业务时刻、CHECK 是有意守卫；
   ② channel 测试里**没有任何用例走 `PUT /connections/{id}/credential` 这条路径**
   ⇒ **它不是 L2 这两个红点的成因**；
   ③ 本车道被明确限定「只改 OAuth 服务与 channel 测试」，`ChannelService` 不在其中，
   **故本车道不改，留给下一轮**。
   下一轮建议的修法（与族 B 同构，但要保留业务语义）：
   `authorizedAt` 为空时改由 DB 打戳（`authorized_at=now()`），
   调用方传了值时仍原样写入、让 CHECK 继续当「不许在未来」的守卫。
4. **通用错误处理器把任何 `DataAccessException` 翻成同一句 409**
   （`docs/backlog/2026-10-21-global-error-handler-masks-code-defects-as-409-400.md`）——
   本车道只修了被它掩盖的那一个缺陷，**没有**改错误处理器本身（那是跨模块改动）。
5. **`OAuthAuthorizationService` 里剩下的 3 处 `Instant.now()`**（凭据 `status` 的 EXPIRED/EXPIRING 判定、
   `expires_at` 与宿主钟的比较）：`expires_at` 来自**平台**返回的到期时刻，是第三个钟域
   （平台自己的钟），拿宿主钟或 DB 钟去比它都不「更对」；且**不受任何 `now()` 约束** ⇒ 本车道不动，
   在此登记待下一轮判。
6. **环境备注**：本机默认 JDK 25，`spotless:check` 因 google-java-format 1.22.0 与 JDK 25 不兼容而跑不起来；
   spotless 未绑定到构建生命周期，**不影响** L0 / L1 闸。仓库要求的工具链是 JDK 21。
