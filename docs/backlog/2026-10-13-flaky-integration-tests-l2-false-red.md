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

---

## 六、批次 32：`FulfillmentRequestIntegrationTest` 违约（2026-10-20，**第 2 次出现**）

**现象**：L2 跑到 `trade` 模块失败，**全量只有 1 个 error**：

```
FulfillmentRequestIntegrationTest · 33 例中 1 error
DataIntegrityViolationException: new row for relation "fulfillment_request_step"
violates check constraint "fulfillment_request_step_check3"
Failing row contains (..., TRACK, 1, COMPLETED, null, null, 1,
                      2026-10-20 06:30:50.327037+08,   <- started_at
                      2026-10-20 06:30:50.326031+08,   <- finished_at  (比 started_at 早 1.006ms)
                      0.001, ...)
```

**取证（按 §「判据」三步走）**：

| 步骤 | 结果 |
| --- | --- |
| `git diff 89dd3148..HEAD -- '*Fulfillment*' 'V152__trade_fulfillment_request.sql'` | **空** —— 履约代码与定义该约束的 V152 迁移**逐字节未变** |
| 本批在 trade 的全部改动 | **8 个文件全部是 `A`（新增）**；V207 里 `fulfillment_request` 出现 0 次；零 ALTER 既有表 |
| 隔离复跑该类 | **连跑 3 轮，每轮 33/33 全绿**（BUILD SUCCESS ×3） |
| surefire 并行配置 | 无 `parallel` / `threadCount` ⇒ 测试类**串行**执行，不存在并发干扰 |

**根因（与 §四 同族：跨时钟域竞态）**：
`FulfillmentRequestService.completeStep` 用 **JVM 时钟** `OffsetDateTime.now()` 写 `started_at`，
而 DB 函数 `complete_fulfillment_step` 用 **PG 时钟** `clock_timestamp()` 写 `finished_at`。
表上的 `CHECK(finished_at IS NULL OR finished_at >= started_at)` 因此在
「这一步在 1ms 内完成 + JVM 时钟比 PG 快哪怕 1ms」时翻负。
**窗口宽度约 1ms** —— 与 §四 的 `effective_from <= now()` 是同一类病。

**这是同一个约束的**第 2 次**出现**：
批次 26（2026-10-19）记过**完全相同**的一例 —— 同一个 `fulfillment_request_step_check3`、
同样「隔离复跑 33/33 绿」、同样「本批未碰该测试、该服务与该约束」。
**两次的报错信息一字不差** ⇒ 这不是新问题，是**同一个坑第 2 次踩**。

**建议修法（派 worker 做，不要在批次中途改）**：

1. **主修（收敛时钟域）**：让 `started_at` 与 `finished_at` 来自**同一个时钟**。
   二选一：
   - 夹具/服务侧：`started_at` 也用 DB 的 `now()`（`select now()` 的结果绑 `?::timestamptz`），
     与函数里的 `clock_timestamp()` 保持同源；
   - 或函数侧：把 `finished_at` 改成 `GREATEST(clock_timestamp(), started_at)` 兜底，
     并让 `elapsed_seconds` 与之自洽（**注意别把负数 elapsed 写进表**）。
2. **兜底（DB 侧约束放宽）**：`CHECK(finished_at IS NULL OR finished_at >= started_at)`
   可改为容忍 1ms 级误差（如 `finished_at >= started_at - interval '1 second'`），
   但**这只是掩盖**，优先做 ①。
3. **归并到统一扫描**：本条与 §四 同族，建议按「JVM `now()` 写时间戳 + DB `now()`/`clock_timestamp()`
   读时间戳」的组合全局搜一遍
   （`grep -rn "OffsetDateTime.now()" backend/services/*/src/main/java` 配合
   `grep -rn "clock_timestamp()\|now()" backend/services/*/src/main/resources/db/migration`），
   一次性把同族问题收干净，而不是每次等 L2 撞出来。

> 批次 32 的 push **建立在这个 flake 的干净窗口补跑之上，不是首次全量绿**。
> 已在 `docs/sessions/2026-10/2026-10-20-batch-32-four-lane-delivery.md` 如实记录。

### §六之二：跨会话独立复核（2026-10-20 05:00 轮调度者只读取证）

批次 32 的 L2 跑红时，**上一轮（05:00 tick）调度者在旁路做了完全独立的只读取证**，
结论与本节一致 —— **这是本条 flake 第二次拿到独立确认**。逐项对照如下：

| 项 | 对方结论 | 06:00 轮独立复核 | 一致 |
| --- | --- | --- | --- |
| 失败约束 | `fulfillment_request_step_check3` = 第 4 条**表级** CHECK = `CHECK(finished_at IS NULL OR finished_at>=started_at)` | 源码复核 `V152__trade_fulfillment_request.sql` L399–L434：该表**表级** CHECK 依序为 `_check`(L431) / `_check1`(L432) / `_check2`(L433) / **`_check3`(L434)**；列级 CHECK（`step_kind` / `attempt` / `status` / `work_order_count` / `failure_code`）命名为 `<表>_<列>_check` 且**不占用编号** ⇒ `_check3` 精确对应 L434 | ✅ |
| 时钟源分裂 | `started_at` JVM 写、`finished_at` PG 写 | `FulfillmentRequestService.java:800` `markStepRunning(id, TRACK, attempt, OffsetDateTime.now(), actor)`（JVM 时钟）vs `V152:1290` `complete_fulfillment_step` 内 `finished_at = clock_timestamp()`（PG 时钟）⇒ 报错行 `step_kind='TRACK'` 正是 L800 那一步 | ✅ |
| 窗口宽度 | ~1ms（`elapsed_seconds=0.001`） | 同上；失败行 finished 比 started 早 **1.006ms** | ✅ |
| 排除 batch-32 | V207 零 ALTER、surefire 无 parallel、batch-31 的 L2 绿 | 本节 §六 已独立验证（8 文件全 `A`、履约代码零 diff、隔离复跑 3×33/33 绿） | ✅ |
| 处置 | 隔离复跑、不二分、L2 不绿不 push、修复不塞进本批 | 全部照办：隔离复跑 3 轮全绿 ⇒ **未做 bisect**（非确定性失败二分无意义）→ 干净窗口重跑**整批** L2 ⇒ 第 3 次 🟢 全绿 ⇒ **在绿之后才 push** | ✅ |

⚠️ **一处需要更正：模块 ID 是 `9.3.3.1`，不是 `9.3.1.1`。**
按蓝图 `docs/product/capability-blueprint/09-order-and-fulfillment.md`：

- **`9.3.3.1` = 履约请求**（L192）—— 编排一次履约、持久化流程状态、**拥有 `V152` 迁移与
  `FulfillmentRequestService`**，本次违约就在它名下。
- `9.3.1.1` = **拆单**（L64）—— 把一个订单拆成多个履约单元，**与本次违约无关**。

⇒ 派修复车道时**必须挂 `9.3.3.1`**。挂成 `9.3.1.1` 会让 worker 去改「拆单」，
真正的约束与函数一个都不碰 ⇒ 修完照样红。**这条更正来自本轮核对，请以本节为准。**

同族修法不变（见上），另补一条**修复时的具体落点**：
`complete_fulfillment_step`（`V152:1262` 起）里 `finished_at = clock_timestamp()`，
若选「DB 侧兜底」路线，改成 `GREATEST(clock_timestamp(), started_at)` 的同时
**必须让 `elapsed_seconds` 与之自洽**（该表 `_check4` 要求 `elapsed_seconds>=0`，
`GREATEST` 兜底后 `finished_at` 可能等于 `started_at`，此时 `elapsed_seconds` 写 0 合法，
但**不能写负数**，否则会被 `_check4` 拒绝）。
若选「同源」路线（`started_at` 也由 DB 写），则 `FulfillmentRequestService` 的
`OffsetDateTime.now()` 传参路径要改，且 `elapsed_seconds` 的计算基准同步从 JVM 切到 DB。

### §六之三：批次 33 复现并**放大了量级** —— 同族病，一次炸掉 3 个既有模块（2026-10-20）

批次 33 的 L2（第一次，08:07–08:54）**后端 trade 红**：`Tests run: 1494, Failures: 1, Errors: 31`。
**31 个错误**（批次 32 只有 1 个）⇒ 不是同一条 flake 复现，是**同一族病在更大的时钟偏差下同时命中 3 个既有模块**。

| 失败测试类 | 归属模块 | 违的约束 | 错误数 |
| --- | --- | --- | --- |
| `FulfillmentRequestIntegrationTest` | 9.3.3.1 履约请求 | `fulfillment_request_step_check3` | 1 failure + 11 errors |
| `FulfillmentMonitorIntegrationTest` | 9.3.3.2 履约流程监控 | `fulfillment_request_reservation_check5` | 18 errors |
| `OrderConsolidationIntegrationTest` | 9.3.1.2 合单 | `order_consolidation_timestamps_ordered` | 2 errors |

**本批 4 个新模块的测试在同一轮里全绿**（channel 24+3+26+4=57、trade 33+9+30+5=77）⇒ **不是本批回归**。

#### 根因：宿主机（JVM）时钟与 Docker 内 PG 时钟的**漂移**，这次达到数百毫秒

失败行直接量出了偏差（`FulfillmentRequestIntegrationTest`）：

```
requested_at  = 2026-10-20 08:28:01.659602+08   ← 应用/JVM 写
acknowledged_at = 2026-10-20 08:28:01.117591+08 ← PG clock_timestamp() 写
⇒ DB 时钟比 JVM 时钟慢约 542ms
```

同一时刻在合单表上也看到同方向的偏差（`confirmed_at` 由 PG 写、落到了 `decided_at` 之前）。

**该漂移是瞬时的**：08:28 偏差约 560ms，08:57 复测已回到 **[-52ms, +78ms]** 区间（PG 容器与宿主 `clock_timestamp()` 对比）。
批次 32 那次只有 1.006ms 的偏差 —— 同一个窗口，只是这回窗口宽了几百倍。

#### 隔离复跑：7 个类 **162 例全绿**（`BUILD SUCCESS`）

```
./mvnw -pl services/trade -am test \
  -Dtest='FulfillmentMonitorIntegrationTest,FulfillmentRequestIntegrationTest,OrderConsolidationIntegrationTest,\
AddressPaymentCheckIntegrationTest,AddressPaymentCheckTenancyIntegrationTest,\
OrderPendingReviewIntegrationTest,OrderPendingReviewTenancyIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
```
⇒ 三个「红」的类单独跑**全绿**（含本批两个新模块）⇒ **确证是环境/时钟诱发的既有脆弱点，不是 batch-33 的代码回归**。

#### 受影响面已精确定位（不必再全仓 grep）

全服务扫了所有「比较两个时间戳」的 CHECK（约 40 条），按**两侧写入方**分类后，**真正跨时钟域的只有 4 张表、7 条约束**，全在既有模块：

| 服务 | 迁移 | 表 | **DB 时钟写**的列 | **JVM 写**的列 | CHECK 条数 |
| --- | --- | --- | --- | --- | --- |
| trade | V152 | `fulfillment_request` | `completed_at` / `cancelled_at` / `blocked_at`（`clock_timestamp()`） | `requested_at` | 3 |
| trade | V152 | `fulfillment_request_reservation` | `acknowledged_at` / `released_at`（`clock_timestamp()`） | `requested_at` | 2 |
| trade | V152 | `fulfillment_request_step` | `finished_at`（`clock_timestamp()`） | `started_at` | 1 |
| trade | V116 | `fulfillment_warehouse_allocation_decision` | `confirmed_at`（`now()`） | `decided_at` | 1 |

**已确认是同源、不会翻负的**（不必动）：

- 所有 `CHECK(updated_at>=created_at)` —— 两侧都是 PG 的 `now()`；
- `CHECK(totals_computed_at>=created_at)`（V150 / V153）—— `totals_computed_at = now()`、`created_at DEFAULT now()`；
- `CHECK(finished_at>=started_at)`（trade V147 出库回传、catalog V36 重复候选）与
  `CHECK(finished_at>=requested_at)`（channel V200 刊登任务）—— 两侧都由应用写，同一个 JVM 时钟。

#### 建议修法（派 worker 做，不属任何批次车道）

1. **主修（收敛时钟域）**：凡是要与 DB 写的列做先后比较的**起始时间戳，一律改由 DB 写**。
   - 最小改法：应用侧把 `OffsetDateTime.now()` 换成 `select now()` 的结果再绑 `?::timestamptz`；
   - 或函数侧：起始列也用 `clock_timestamp()`（同一函数内取，同源）。
   - ⚠️ 修 V152 时注意 `clock_timestamp()` 与 `now()` 的差别：`now()` 是**事务开始**时间，
     同一事务内多行会相同；`clock_timestamp()` 是**实际**时间。选哪个要按语义定，不要混。
2. **兜底（约束容忍）**：`CHECK(finished_at IS NULL OR finished_at >= started_at - interval '1 second')`
   之类。**容差要按实测偏差定**（这次量到 542ms，历史上 1ms），1 秒是合理下限但不是万无一失。
   这条**只是掩盖**，不能替代 ①。
3. **回归防护**：加一个**跨时钟域**的集成测试 —— 故意让应用侧时间戳比 DB 时钟偏 1 秒，
   断言约束不翻负（或断言两者同源）。否则修完还会以别的形式复发。
4. **流水线侧**（调度者/工具链，不属业务代码）：
   - L2 **开工前**量一次宿主机 vs PG 容器 `clock_timestamp()` 的偏差并记进日志，
     偏差超阈值（例如 100ms）时**先不要开跑**（等 VM 时钟回同步或重启 Docker Desktop VM）；
   - 这类失败**不要**当成「代码回归」去二分 —— 先隔离复跑，见 §六之三。

> 批次 33 的 push 建立在**时钟对齐后的整批 L2 重跑全绿**之上，不是首次全量绿。
> 已在 `docs/sessions/2026-10/2026-10-20-batch-33-four-lane-delivery.md` 如实记录。

---

## 七、批次 36：`ImportWizardIntegrationTest` 任务时间戳（2026-10-20）—— 同族第 4 次，且**偏差达 ~5 分钟**

**现象**：L2 第一轮后端红 **1/1527**，唯一失败例：

```
[ERROR] ImportWizardIntegrationTest.任务时间戳是带时区写回的值:897
Expecting actual:
  2026-10-20T07:04:04.678109Z
to be after or equal to:
  2026-10-20T07:09:01.931931Z
```

**取证**：

| 步骤 | 结果 |
| --- | --- |
| 断言位置 | `backend/services/catalog/src/test/java/com/starcon/catalog/ImportWizardIntegrationTest.java:897` |
| `before` 的时钟源 | **JVM**：`OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)`（L889） |
| `job.createdAt()` 的时钟源 | **PG**：`V29__import_wizard.sql:239` `created_at timestamptz NOT NULL DEFAULT now()` |
| 本批是否碰 catalog / V29 / ImportWizard | **零文件涉及**（本批 4 个模块全在 channel） |
| 隔离复跑该类 | **19/19 全绿**（BUILD SUCCESS） |
| 命中家族 | §六之三 的「JVM 写 vs PG 写」跨时钟域族 |

**偏差量级异常**：这次量到 **PG 比 JVM 慢约 297 秒（≈4 分 57 秒）**，
远大于历史上记录过的 542ms（§六之三）与 1.006ms（§六）。
⇒ 时钟漂移**没有上界**，不能靠「几百毫秒以内可忽略」来假设。

**本轮的特殊背景（首次值得记的一条）**：这次 L2 是**与 4 条 worker 车道并发**跑的
（PG 容器峰值 41、load 峰值 63）。历史记录里 §六之三 的 542ms 发生在**单独跑**时。
⇒ **高并发负载与宿主机/VM 时钟漂移幅度之间可能存在相关性**，
这与 §六之三 §流水线侧「开工前量偏差、超阈值别开跑」的建议方向一致，
但建议把阈值从 100ms 放宽到「**秒级**」并**在 L2 全程复查**（而不是只在开工前量一次）。

**建议修法**：

1. **主修（同族统一做法）**：该断言拿 JVM 时钟去比 PG 写的列，属于**设计上的跨时钟域比较**。
   两条出路二选一：
   - 断言改成**只比同源的两列**（如 `finishedAt >= startedAt`，二者都由应用写），
     **删掉** `createdAt >= JVM now` 这条跨域断言 —— 这条断言本来也不是它想验证的东西
     （它想验证的是「时区没被二次偏移」，`finishedAt >= startedAt` 才是那个证据，L902 已经有了）；
   - 或让被比较的列也由 **DB 写**（`created_at` 已是 DB 写，那 `before` 也应改成 `select now()` 的结果）。
   - **首选前者**：把跨域断言去掉，本测试就与时钟漂移**彻底解耦**。
2. **收口动作**：按 §六之三 的全局扫描建议，
   `grep -rn "OffsetDateTime.now()" backend/services/*/src/test/java | grep -i "isAfterOrEqualTo\|isBefore"`
   把「用 JVM 时钟去比 PG 写的列」的断言**一次性找齐**，
   而不是每次等 L2 撞出来再补一条。
3. **流水线侧**：见上 —— 阈值放宽到秒级 + L2 全程复查。

> 批次 36 的 push 建立在这个 flake 的**干净窗口补跑全绿**之上，不是首次全量绿。
> 已在 `docs/sessions/2026-10/2026-10-20-batch-36-merge-4-lanes-and-l2.md` 如实记录。
