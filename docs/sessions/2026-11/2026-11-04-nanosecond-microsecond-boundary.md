# 2026-11-04: 后端红灯的最后一层 —— 纳秒与微秒的边界，以及把 CI 拆成矩阵

## 摘要

CI run `41657744918`（PR #74，17 个提交）里，前端与静态检查全绿，只有
**「Java 21 & PostgreSQL Integration」一个作业红**。它跑满了 163 分钟才出结果，
而作业超时上限是 180 分钟 —— 离超时只差 17 分钟。本轮做两件事：

1. 修掉它报出的 **6 个失败用例**；
2. 把这个作业**按服务拆成矩阵**，从「一个 163 分钟、随时会超时的大 job」
   变成「9 个并行、最慢 35 分钟的小 job」。

| # | 失败用例 | 症状 | 归属 |
| -- | -------- | ---- | ---- |
| 1 | `channel/PlatformViolationIntegrationTest.timestampsRoundTripWithoutTheEightHourShift` | expected `…398456397Z` vs actual `…398456+08:00` | 夹具 + 断言 |
| 2 | `crm/MessageClassificationIntegrationTest.分类时刻写回的是同一个绝对时刻` | expected `…937374765Z` vs `…937375Z` | **生产代码** |
| 3 | `crm/ProductReviewIntegrationTest.评分统计给出均分_分布_趋势_下滑维度` | 「三个时间窗各有一个桶」expected 3 but was **0** | **生产缺陷** |
| 4 | `trade/ReturnReceiptAllocationIntegrationTest.超期退回按收货时刻与寄回时限判定且时刻零漂移` | expected `…639725979+08:00` vs `…639726+08:00` | 夹具 |
| 5 | `trade/RmaAuthorizationIntegrationTest.全部授权并把寄回时限与基数快照落库` | expected `…595521258Z` vs `…595521+08:00` | 夹具 + 断言 |
| 6 | `trade/RmaInstructionIntegrationTest.建单时地址与时限从退货授权单真复制过来` | expected `…100584107+08:00` vs `…100584+08:00` | 夹具 |

## 根因：同一个缝，机器相关

六个用例是**同一类问题**：Java 的时刻能表示到**纳秒**，PostgreSQL 的 `timestamptz`
只存到**微秒**；两边对不齐，「写进去的」与「读回来的」就不是同一个数。

麻烦的是它**只在一半机器上出现**：

- `OffsetDateTime.now()` 在 Linux 上走 `clock_gettime`，带亚微秒尾巴（实测 `…722795824ns`）；
- 在 macOS 上走 `gettimeofday`，**恰好微秒对齐**（实测 `…431385000ns`）。

⇒ 开发机怎么跑都是绿的，CI runner 却是红的。本轮新增的守卫测试就是为了
把这个「靠机器碰运气」变成「任何机器上都能复现」。

### 两个维度要分开看

| 维度 | 现象 | 处置 |
| ---- | ---- | ---- |
| **偏移量** | 写 `Z`、读回 `+08:00` | `timestamptz` **只保时刻、不保偏移量**，读回的偏移量由 JDBC 按 JVM 默认时区换算。⇒ 断言改比**瞬间**（`toInstant()`），不比 `OffsetDateTime` 整体 |
| **精度** | 写 `…765ns`、读回 `…375µs` | PG 会把第 7 位小数落到微秒。⇒ **写之前先对齐到微秒**（`truncatedTo(ChronoUnit.MICROS)`） |

关键推论：**对齐用截断而不是四舍五入**。对齐之后文本里就没有第 7 位小数可舍了，
PG 存进去什么就读回来什么，两边严丝合缝 —— 这条规则与 PG 到底是舍还是截无关，
因而对 PG 版本不敏感。

## #3 是个真缺陷，不只是断言写错

`crm/ProductReviewService.trend()` 用 `date_bin(stride, source, origin)` 分桶，
再拿 Java 侧的 `cursor`（= `origin + k*stride`）去 map 里按 `Instant` 配对。

`origin` 来自 `now()`，**带纳秒** ⇒ PG 把它落到微秒，Java 侧却按未对齐的值算边界
⇒ 两边差 0.x 微秒 ⇒ **每个桶都配不上对** ⇒ 10 个桶的 count 全是 0。

同一个响应里 `totalReviews=9`、三窗均分 5/3/1 全对 —— 看上去像「最近没有新评价」，
而真相是**整条趋势线被精度问题吃掉了**。这类「总数对、图表空」的缺陷比红灯更危险。

修法不只是把断言改对，而是三处一起收：

- `now()` 对齐到微秒（让 API 返回的区间起点是数据库能精确表示的数）；
- `stats()` 里调用方给的 `filter.from()/to()` 同样对齐（**这是外部 API 输入，
  之前谁都没管**）；
- `trend()` 内部对原点与终点再做一次防御性对齐（让它不依赖调用方已经对齐）。

## 决策 / trade-off

- **对齐点选在「写库之前」，不是「断言时凑合」**。凑合的写法（断言时放宽到秒）
  会把精度问题从测试挪进生产：库里存的是 A、接口返回的是 B，两边永远对不上。
- **新增可移植的守卫测试** `crm/DbPrecisionIntegrationTest`：它**不依赖运行机器的
  时钟**，直接把带亚微秒尾巴的时刻灌进真实的 PostgreSQL 16，于是
  - 「带尾巴写进去读回来会漂（幅度 < 1µs）」—— 证明不对齐就会漂；
  - 「对齐后写进去读回来分毫不差」—— 证明对齐是有效的；
  - 「`date_bin` 原点对齐时桶起点 = `origin + k*stride`、不对齐时对不上」——
    证明分桶被精度吃掉的机制。

  这是这类缺陷**唯一跨平台可复现的回归守卫**；其余用例都得靠 CI 的 Linux runner 才抓得住。
- **CI 后端作业改成 9 路服务矩阵**：
  - `fail-fast: false` ⇒ 一个服务炸了其余 8 个照跑，一次就能修完全部红灯；
  - **故意不加 `-Dtest` 过滤**：一过滤就有「表达式写错 ⇒ 静默跑 0 个用例却报绿」
    的风险，那比红灯更坏 —— 它会让整个后端门禁形同虚设；
  - `-pl services/<module> -am` 是完整构建：服务之间**没有** Maven 依赖
    （每个服务只依赖 `libraries/tenancy`），本地已逐个验证 9 个模块 `verify` 通过。
  - **代价**：runner 分钟数约翻倍（9 × 35 ≈ 315 分钟 vs 单 job 163 分钟）。
    换来的是离超时上限 2 倍以上余量，以及「一次修完」而不是「一次修一个」。
- **CI Java 作业超时从 180 回落到 90 分钟**：矩阵里最慢的是 catalog/warehouse
  （各约 31 分钟），90 分钟仍有 2 倍余量；180 分钟是为单 reactor 准备的，
  留着它只会让人以为「还有 17 分钟富余」。

## 变异自证

改了「时刻对齐」这件事，必须证明**断言真的在守这个性质**，而不是恰好绿。
做法：把所有 `.truncatedTo(ChronoUnit.MICROS)` 换成 `.withNano(937374765)`
（等价于把 Linux 的亚微秒尾巴硬塞进来），重跑：

| 用例 | 变异后 | 修复后 |
| ---- | ------ | ------ |
| `ProductReviewIntegrationTest.评分统计给出均分_分布_趋势_下滑维度` | 🔴 红（`:625`） | 🟢 绿 |
| `MessageClassificationIntegrationTest.分类时刻写回的是同一个绝对时刻` | 🔴 红（`:853`） | 🟢 绿 |
| `PlatformViolationIntegrationTest.timestampsRoundTripWithoutTheEightHourShift` | 🔴 红（`:1121`） | 🟢 绿 |
| `ReturnReceiptAllocationIntegrationTest.超期退回按收货时刻与寄回时限判定且时刻零漂移` | 🔴 红（`:599`） | 🟢 绿 |
| `RmaAuthorizationIntegrationTest.全部授权并把寄回时限与基数快照落库` | 🔴 红（`:455`） | 🟢 绿 |
| `RmaInstructionIntegrationTest.建单时地址与时限从退货授权单真复制过来` | 🔴 红（`:339`） | 🟢 绿 |

六个用例名与 CI 报出的完全一致（行号因改动而位移）⇒ 本地就能复现 CI 红灯，
不必再靠一轮 CI 来确认「修好了」。

## 文件清单

| 文件 | 改动 |
| ---- | ---- |
| `backend/services/crm/src/main/java/com/starcon/crm/ProductReviewService.java` | `now()` 对齐微秒；`stats()` 对齐调用方给的时间范围；`trend()` 防御性对齐原点 |
| `backend/services/crm/src/main/java/com/starcon/crm/MessageClassificationService.java` | `now()` 对齐微秒 |
| `backend/services/crm/src/test/java/com/starcon/crm/DbPrecisionIntegrationTest.java` | **新增** 可移植的精度边界守卫（4 例） |
| `backend/services/channel/src/test/java/com/starcon/channel/PlatformViolationIntegrationTest.java` | 夹具对齐；断言改比瞬间 |
| `backend/services/trade/src/test/java/com/starcon/trade/ReturnReceiptAllocationIntegrationTest.java` | `now8()` 对齐 |
| `backend/services/trade/src/test/java/com/starcon/trade/RmaAuthorizationIntegrationTest.java` | `plusDays()` 对齐；断言改比瞬间 |
| `backend/services/trade/src/test/java/com/starcon/trade/RmaInstructionIntegrationTest.java` | `due()` 对齐 |
| `.github/workflows/ci.yml` | 后端作业改 9 路服务矩阵，超时 90 分钟 |

## 后续待办

- 本仓库还有 2582 处 `OffsetDateTime.now(`。它们多数只参与「写入后不再逐值比较」，
  因此不受影响；**但只要有第二处「写进去的 Java 时刻」被逐值比较，就会重演本轮的红灯**。
  ⇒ 真正的长期解法是**在数据访问层统一做一次精度对齐**（而不是每个调用点自觉截断），
  已记在 `docs/backlog/` 待办里。
- 其余 6 个服务（identity / catalog / warehouse / finance / reporting / platform）
  本轮未改代码，靠 CI 矩阵逐个跑；若有红灯会一次性暴露。
