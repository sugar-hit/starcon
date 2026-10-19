# 两个既有集成测试在全量并发下 flake（L2 误报来源）

**发现时间**：2026-10-13 20:00 tick，L2 批次闸
**最近一次复现**：2026-10-18 23:42 tick（批次 22），第三个 flake，见下方 §四
**严重度**：中（不阻塞交付，但会让每批 L2 假红，浪费一次全量验证 + 一次二分）
**状态**：未修 · 本轮已确认两者都与本批代码无关

## 现象

本批 `./mvnw verify` 连续两次 BUILD FAILURE，但**两次红的用例完全不同，且都与本批改动无关**：

| 轮次    | 失败                                             | 报错                               |
| ------- | ------------------------------------------------ | ---------------------------------- |
| 第 1 次 | `catalog.DuplicateDetectionIntegrationTest` 2 例 | `expected:<200/404> but was:<503>` |
| 第 2 次 | `warehouse.StockOwnershipIntegrationTest` 1 例   | UUID 不匹配                        |

**本批改动清单里没有任何 catalog / identity / tenancy / StockOwnership 文件**
（`git diff --name-only b41a14f d6c882c` 全是 warehouse / apps/web / contracts）。

## 单独重跑，两个都绿

```
DuplicateDetectionIntegrationTest: Tests run: 19, Failures: 0  ✅
StockOwnershipIntegrationTest:     Tests run: 40, Failures: 0  ✅
```

## 根因

### ① 503 = 身份桩的临时端口被抢占

`DuplicateDetectionIntegrationTest` 用 JDK 内置 `com.sun.net.httpserver.HttpServer`
起一个假身份服务，并通过 `identity.getAddress().getPort()` 把它绑进
`IDENTITY_URL`（`@DynamicPropertySource`）。这个端口是**操作系统分配的临时端口**。

全量 `verify` 时 surefire 多 fork 并发、Testcontainers 也在密集起停 PG 容器，
临时端口被抢占/复用的概率显著上升 ⇒ stub 连不上 ⇒
`catalog/AccessService` 捕获 `RestClientException` 抛 503
（`services/catalog/.../AccessService.java:103`）⇒ 期望 404/200 的断言拿到 503。

### ② UUID 不匹配 = 测试固件未按租户/用例隔离

`StockOwnershipIntegrationTest.货权档优先级是sku大于法人大于仓库大于兜底`
用**固定业务编码**建档（`OWN-LE`、`CONS-WHU`、`CONS-SKU`…），断言解析出的 ownerId 等于刚建的那个。
并发/复用时同编码的档位被别的用例先写入或覆盖 ⇒ `service.resolve()` 按优先级命中了**另一条**记录
⇒ UUID 对不上。

### ③ warehouse 那次「失败」根本不是断言失败

`2026-10-13T20-08-06_294-jvmRun1.dumpstream`：

```
Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
```

即测试**全部跑完并通过**后，fork JVM 30 秒不退（残留非守护线程 / 容器未收干净），
surefire 判定 hang 并强杀 ⇒ reactor 报 FAILURE，但 `surefire-reports/*.txt` 里
**没有任何非零 Failures**。这是「退出超时」被当成「测试失败」。

## 对流水线的影响

组批模式下 L2 是**每批唯一一次**全量验证。这两条 flake 会：

1. 让 L2 假红 → 触发「二分定位 + 派修复任务 + 重跑 L2」整条处置流程（一次要多烧 1–2 套全量）
2. 而二分**必然查不出东西**（本批根本没碰这些文件）⇒ 每批都白烧一次全量
3. 长期会侵蚀「L2 红了就一定是跨车道互相破坏」这个判断的可信度

## 建议修法（派 worker 做，不要在批次中途改）

1. **503 桩**：端口不用临时端口，改为显式找一个空闲端口后**固定下来**，
   或改用 WireMock/MockWebServer 这类能自管端口生命周期的桩；
   至少要保证 stub 的 `HttpServer` 在 `@AfterAll` 里显式 `stop(0)`，
   避免端口泄漏到后续 fork。
2. **固件隔离**：`StockOwnershipIntegrationTest` 的建档编码改为**每次运行唯一**
   （UUID 后缀），断言只比较「本次建的那条」，不要依赖固定编码的全局唯一性。
3. **surefire hang**：给集成测试 JVM 加 `-DforkedProcessExitTimeoutInSeconds`
   与显式的 `@AfterAll` 容器清理；若确认是 Testcontainers ryuk 未回收，
   检查测试是否漏了 `container.stop()`。

## 判据（下次遇到同类「L2 红」先做这三步，别急着二分）

1. `git diff --name-only <上批HEAD> <本批HEAD> | grep -E "<失败的模块>"` — **为空就不是本批的锅**
2. 单独重跑失败类：`-Dtest=XxxTest -Dsurefire.failIfNoSpecifiedTests=false` — 绿 ⇒ flake
3. 查 `.dumpstream` 有没有 `kill self fork JVM` — 有 ⇒ 那是退出超时，不是断言失败

> 第 2 步要跑**不止一次**。2026-10-18 那次连跑三次得到 FAIL(1F+1E) / FAIL(1F+2E) / PASS ——
> 失败数本身在变，这比「绿了」更能证明是 flake。单跑一次绿不足以结案。

---

## 四、第三个 flake：`OrderChangeCancelIntegrationTest`（2026-10-18 批次 22）

**现象**：后端 trade `Tests run: 1232, Failures: 3, Errors: 3`，全部集中在
`OrderChangeCancelIntegrationTest`，报
`400「平台 AMAZON / 店铺 SHOP-1 没有登记「减少数量」的可变更规则」`。

**取证（本次判据全流程走了一遍，结论：与本批无关）**：

| 步骤 | 结果 |
| --- | --- |
| `git diff --name-only <上批HEAD> <本批HEAD>` 里有无 `OrderChange*` | **无** —— `11.3.1.1` 未碰任何该前缀文件 |
| V166 是否碰 `order_platform_change_rule` | **无**（零 ALTER 既有表、零外键） |
| 在**上批**集成头 `d4919c4a` 单独跑 | **同样失败，错误信息一字不差** |
| 同代码连跑三次 | FAIL(1F+1E) / FAIL(1F+2E) / **PASS** |
| 批次 21 的全量跑 | **绿的**（trade SUCCESS 1210 例零失败） |

**根因（判断，未 100% 钉死）**：查询条件 `effective_from <= now()` 中，
`effective_from` 由**宿主 JVM** 的 `OffsetDateTime.now()` 写入（夹具 `rule()` 辅助方法），
而 `now()` 是**数据库**的事务开始时间 —— 典型的跨时钟边界竞态：
两者挨得够近时，刚插入的行"刚好不可见"。

**已排除**：时钟漂移。宿主与 Testcontainers PG 容器是同一瞬间
（`docker exec date` 显示差 8 小时，那是**时区显示**，非 drift）。

**建议修法**（与上面两条同类）：

1. 夹具侧：`rule()` 辅助方法写 `effective_from` 时不要用 JVM `now()`，
   改用**数据库的** `now()`（`?::timestamptz` 绑 `select now()` 的结果），
   或直接写一个明确更早的固定时刻。
2. 查询侧：`evaluateRule` 的 `effective_from <= now()` 对"刚生效的规则"天然敏感，
   可考虑放宽为 `effective_from <= now() + 间隔`，或由夹具保证有裕量。

⚠️ 批次 22 的 push **建立在这个 flake 的补跑之上，不是首次全量绿**。
已在 `docs/sessions/2026-10/2026-10-18-batch-22-*.md` 如实记录，未做「一次就绿」的表述。

---

## 五、批次 23 的两个 flake（2026-10-19）

| 模块 | 现象 | 单独重跑 | 归属 |
| --- | --- | --- | --- |
| warehouse `LegalEntityTransferIntegrationTest` | 27 例全 error | **26/26 绿** | 见 ⑤ |
| trade `RefundStatusTenancyIntegrationTest` | 4 例 `expected:<200/401/404> but was:<503>` | **连跑三次 10/10 绿** | 见 ① |

两次失败的模块在本批改动清单里**零文件涉及**
（`git diff --name-only 8300d842 647dc079 | grep -E 'warehouse|refundstatus|identity'` 为空）。

### ④ `RefundStatusTenancyIntegrationTest` = ① 的第二个受害者

§① 记的是 `catalog.DuplicateDetectionIntegrationTest`。本例证明**同一根因在 trade 也成立**：
`RefundStatusTenancyIntegrationTest.java:74` 同样是
`HttpServer.create(new InetSocketAddress(0), 0)`，第 105 行把它绑进 `IDENTITY_URL`。
`AccessService` 捕获 `RestClientException` 抛 503。

⇒ §① 的修法**适用面比记录的更广**：凡是用 JDK 内置 `HttpServer` 起身份桩的
集成测试都算，不是只有 catalog 那一个。建议按前缀搜一遍
（`grep -rln "new InetSocketAddress(0), 0)" backend/services/*/src/test/java`）。

### ⑤ 新形态：静态初始化里的 PG 握手失败，级联成一整类 error

**这一条与前四条都不同，值得单独记**：不是竞态、不是断言失败，是**一次连接失败**。

```
Caused by: java.io.EOFException
    at org.postgresql.core.PGStream.receiveChar(PGStream.java:479)
    at org.postgresql.core.v3.ConnectionFactoryImpl.enableSSL(ConnectionFactoryImpl.java:627)
Caused by: java.lang.ExceptionInInitializerError   <- 测试类 <clinit>:131 建连接
Caused by: java.lang.NoClassDefFoundError          <- 之后 26 例全部级联
```

结果看起来像「27 个用例挂了」，实际是**一次 PG 握手 EOF 导致类初始化失败**，
剩下 26 个 `NoClassDefFoundError` 全是级联噪音。

**判据补充**：看到 `Errors` 数量大且报错清一色
`NoClassDefFoundError: Could not initialize class X` 时，
**先往上翻第一条** —— 它才是根因，其余都是级联。
本次若只看数量，很容易误判成「大批功能坏了」而触发二分。

**建议修法**（与上面同类，但更该修的是夹具而非业务代码）：
静态初始化里建连接本身就脆弱（一次抖动就毒死整类）。改为
`@BeforeAll` 里建立连接、失败时显式重试或 `Assumptions.abort`，
避免把一次基础设施抖动放大成整类 error。

> 批次 23 的 push **建立在这两个 flake 的补跑之上，不是首次全量绿**。
> 已在 `docs/sessions/2026-10/2026-10-19-batch-23-*.md` 如实记录。
