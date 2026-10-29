# 2026-10-27: catalog 平台类目映射的 timestamptz 守卫 —— 跨钟域断言抖动（假红）修复

> 车道 `fix/catalog-timestamp-assertion-clock`（从集成头 `integration/module-dev` @ `01e2ef06` 切出）。
> 这是 L2 批次闸（round 113）定位到的**断言抖动修复**，不是新蓝图模块 ——
> **无迁移、无端点、无页面、无契约改动**；**只改了一个测试文件**，生产代码一个字没动。

## 摘要

| 项 | 值 |
| --- | --- |
| 缺陷 | `PlatformCategoryMappingIntegrationTest#timestampsRoundTripWithoutTheSilentEightHourShift` 拿 **JVM 墙钟**（`OffsetDateTime.now()`）比 **PG 的 `now()`**（`created_at DEFAULT now()`）⇒ 跨钟域比较，容差只有 5 分钟 |
| 现象 | round 113 的 L2 全量（机器有负载）里 catalog 1998 例红 1 例：`Expecting actual: 15L to be less than: 5L`，前一行是 `HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=15m52s273ms)` |
| 改动 | 1 个 Java 测试文件：断言两端改到**同一个钟域**（都取 DB 时钟），并加 1 条守卫力度自证用例 |
| 迁移 / 端点 / 页面 / 契约 | **无 / 无 / 无 / 无** |
| 生产代码 | **未改**（生产侧是对的：`created_at` 由 V145 的 `DEFAULT now()` 生成） |
| 多租户三断言 | **原样保留**，未触碰 |
| L0 | `mvnw -q compile` exit 0；`pnpm typecheck` 3/3 successful |
| L1 | `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（14 → 15，多出来的是新增的守卫自证用例） |
| PG 容器 | 测试期间存活 **2** 个 `postgres:*` 容器：1 个是本用例的 Testcontainers（`postgres:16-alpine`），另 1 个是与本次无关的 `langfuse-postgres-1`（已跑 8 天） |

## 决策摘要

### ① 根因不是时区，是「两个读数之间隔了一次真实停顿」

旧断言：

```java
OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);  // JVM 墙钟
...  // 插入（PG 用 now() 打 created_at）
OffsetDateTime dbCreated = ...;                                                // 读回
assertThat(Math.abs(Duration.between(before, dbCreated).toMinutes())).isLessThan(5);
```

- `Duration.between` 比较的是**时刻**，时区写法不影响结果 —— 所以这不是时区 bug，
  是 **`before` 与「PG 打戳的那一瞬间」之间隔了 15 分钟的真实停顿**（容器被负载挤压 → 线程停顿）。
- 报错的 `15L` 单位是**分钟**：`toMinutes()` 的结果。`15 分钟 ≪ 8 小时`，
  说明**没有**发生「timestamptz 被当 LocalDateTime 绑定」那个真缺陷（那会是 480 分钟）。
- 调度者已取证：同 HEAD 的 L2 第 1 跑绿、本批 58 commit 触及 catalog 文件数 = 0、
  空闲机器隔离复跑 14/14 绿 ⇒ 负载型假红。

### ② 修法：两端同钟域 —— 参照时刻也从 DB 取，且**夹住**插入时刻

**没有**照搬「取一个 DB `now()` 当基准」的写法（那是任务书里的「优先做法」），
因为**单个基准点在停顿发生于插入之前时仍会假红**：

- `dbRef` 若取在**插入之后** ⇒ 停顿被吸收，OK；
- 但若只取一个**插入之前**的基准 ⇒ 差值 = 停顿全长 ⇒ 还是红。

⇒ 改成**区间**：插入前取一次 DB 时钟 `dbBefore`，读回 `created_at` 后再取一次 `dbAfter`，
断言 `dbCreated ∈ [dbBefore, dbAfter]`。插入时刻**必然**落在这个区间里（插入事务在
`dbBefore` 事务提交之后才开始、在 `dbAfter` 之前结束），所以：

- **停顿只会把区间撑宽，不会把一个本来正确的时刻挤出区间** ⇒ 不再假红；
- **真偏 8 小时（−28800s）必然落在区间外** ⇒ 守卫照样当场抓住。

**关键 trade-off：为什么不是「把 5 分钟容差放大到 30 分钟」？**
那是把守卫换掉：8 小时量级的漂移在 30 分钟容差下**依然**能被抓（所以那写法至少不致命），
但它把「正常跑法下区间只有毫秒级」这个强约束换成了「30 分钟内都算对」，
真正的时区漂移若偏 20 分钟就会漏网。⇒ 本车道**没有放大任何容差**：
健康跑法下区间宽度只有两次 DB 往返（毫秒级），比旧的「±5 分钟」**严**得多。

**守卫强度没有被削弱，还加了自证。**
新增 `theTimestampGuardStillRejectsTheEightHourShift`：把写回值人为偏 ±8 小时，
断言守卫当场拒绝（`AssertionError`）。基准时刻是**写死的常量**，不依赖任何真实时钟，
因此它自己不会假红。**没有这一条，「放大容差」也能让全量跑变绿 —— 那等于把守卫废了。**

### ③ 同族的 `updated_at` 断言**没有动**

`firstUpdated` vs `secondUpdated` 那条比的是**两个 DB 值**（first vs second），
形状本来就正确 ⇒ 原样保留（含 `Thread.sleep(1100)`）。

### ④ 顺带确认生产侧是对的（不需要改）

- V145 `platform_category_mapping.created_at timestamptz NOT NULL DEFAULT now()`；
- 迁移、端点、前端、契约**全部未改**；
- 多租户三断言（`tenantIsolationTwoWayForgedHeaderAndConnectionReuse` 等）**原样保留**。

## 关键取证（都是本机实测，不是推断）

1. **守卫仍能抓住真漂移（临时实验，已回滚，不在提交里）**：
   在测试类里临时加一条用例，按「把 timestamptz 当 LocalDateTime 绑定」的读法
   （`created_at at time zone 'UTC'` → `LocalDateTime` → 按 JVM 本地时区还原）读回值：

   ```
   TEMP-EXPERIMENT driftMinutes=480 (8h = 480) jvmZone=Asia/Shanghai
   Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
   ```

   ⇒ 真实漂移量 = **480 分钟 = 正好 8 小时**；守卫**当场拒绝**了它。
   实验结束后已从备份还原，提交里不含该临时用例。

2. **假红是停顿不是漂移**：`15L` 的单位是分钟（`toMinutes()`），8 小时应是 `480L`。

3. **同族写法已在仓内落地**：channel 服务 `ListingPublishJobIntegrationTest` 早就因为同一个坑
   （1133 例里红 1 例、隔离复跑 18/18 绿）把断言改成同源 DB 钟；warehouse / catalog 也各有 `dbNow()`。
   本车道沿用 `dbNow()` 这个名字与形状（`sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class)`）。

## 文件清单

| 文件 | 性质 |
| --- | --- |
| `backend/services/catalog/src/test/java/com/starcon/catalog/PlatformCategoryMappingIntegrationTest.java` | **唯一改动**：新增 `dbNow()` / `assertWithinDbClockWindow()` 两个辅助；改写 `timestampsRoundTripWithoutTheSilentEightHourShift` 的时刻断言；新增 `theTimestampGuardStillRejectsTheEightHourShift`；删掉因此不再使用的 `Duration` / `ChronoUnit` import |
| `docs/sessions/2026-10/2026-10-27-catalog-timestamp-assertion-clock.md` | 本文件 |

**未改**：迁移、端点、生产代码、前端、`packages/api-contracts`、台账、backlog。

## 验证方式

```bash
# L0 编译闸（秒级，无容器）
cd backend && ./mvnw -q compile                      # exit 0
pnpm typecheck                                       # 3 successful, 3 total

# L1 模块闸（只跑自己改的那个测试类）
cd backend && ./mvnw -pl services/catalog -am test \
    -Dtest='PlatformCategoryMappingIntegrationTest*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# ⇒ Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 + BUILD SUCCESS
```

未跑全量 `./mvnw verify`（那是调度者的 L2 批次闸），未跑 `pnpm --filter @starcon/web build`。

## 后续待办

- **无阻塞项。** 生产代码不需要改。
- 可选（本车道未做，超出范围）：仓内还有几处「JVM 墙钟 vs DB 钟」的同类写法，
  建议在下一批 L2 闸里继续按「两端同钟域」的方向逐个收敛（本车道只处理调度者指派的那一条）。
- 环境备注（非本次改动引入）：本机默认 JDK 是 25，`spotless:check` 因
  google-java-format 1.22.0 与 JDK 25 不兼容（`NoSuchMethodError: DeferredDiagnosticHandler.getDiagnostics`）
  而跑不起来；spotless 未绑定到构建生命周期，**不影响** L0 / L1 闸。仓库要求的工具链是 JDK 21
  （本机已装：`/Users/guan/Library/Java/JavaVirtualMachines/openjdk-21`）。
