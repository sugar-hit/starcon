# 跨时钟域的第 4 例：warehouse 寄售结算区间用 DB 日期，销售事实却绑 JVM 墙钟 ⇒ 本地 00:00–08:00 期间 L2 必红（2026-11-01 取证）

**优先级：P0 —— 这不是测试脆弱性，这是「夜间每一批都推不出去」的系统性阻塞。**

同族前三例（均已按「单独立项 + 专用修复车道」处置）：

| # | 模块 | 症状 | 状态 |
| --- | --- | --- | --- |
| 1 | trade `fulfillment_request_step` | 绑 JVM 墙钟撞 DB `CHECK` | 已修（`docs/backlog/2026-10-2026-10-21-trade-fulfillment-clock-domain-db-constraint.md`） |
| 2 | catalog timestamptz 往返断言 | 往返断言跨钟域假红 | 已修（`docs/backlog/2026-10-2026-10-27-catalog-timestamp-roundtrip-assertion-cross-clock-domain.md`） |
| 3 | channel `credential_ref.authorized_at` | 绑 JVM 墙钟撞 `credential_window` CHECK，被谎报成 409 | 已派修复车道 `fix-channel-l2-red`（`docs/backlog/2026-10/2026-10-28-channel-credential-window-cross-clock-domain.md`） |
| **4（本例）** | **warehouse 7.7.1.2 寄售与代销 `consignment_sale.occurred_at`** | **销售事实的瞬时值被平移 +8h，落到结算区间之外 ⇒ 400** | **本轮已派修复车道 `fix-warehouse-consignment-clock-domain`** |

**发现于 round-144（2026-11-01 00:00 tick）** 的 L2 批次闸。

---

## 一、现象

L2 第 1 步（`backend && ./mvnw verify`）红，warehouse 模块 1672 例里 **1 例错误**：

```
[ERROR] Tests run: 20, Failures: 0, Errors: 1, Skipped: 0 -- in com.starcon.warehouse.ConsignmentIntegrationTest
[ERROR] ConsignmentIntegrationTest.同一条销售事实不能进两张结算单 -- <<< ERROR!
org.springframework.web.server.ResponseStatusException: 400 BAD_REQUEST
  "该区间内没有「已售出且尚未结算」的销售事实，结算触发单必须是空的单据时，说明这期根本没卖出东西 —— 未售出不产生应付，这正是寄售该有的结果。"
    at com.starcon.warehouse.ConsignmentService.createSettlement(ConsignmentService.java:590)
    at com.starcon.warehouse.ConsignmentIntegrationTest.lambda$同一条销售事实不能进两张结算单$1(ConsignmentIntegrationTest.java:498)
```

⚠️ **报错文案把「区间算错」说成「业务上没卖出东西」** ⇒ 运营看到的是一条**误导性的业务结论**，
真实原因（结算区间少算了一天的边界）被完全掩盖。

---

## 二、根因：两个钟域在「区间边界」上对不齐

### 2.1 区间来自 **DB 钟**

`ConsignmentService.settlementWindow()`（第 1030 行起）：

```java
var today = sql.fetchOne("select current_date").get(0, LocalDate.class);   // ← DB 钟
switch (cycle) {
  case "MONTHLY" -> { start = today.withDayOfMonth(1); end = today.withDayOfMonth(today.lengthOfMonth()); }
  ...
}
```

### 2.2 区间边界被钉成 **UTC 零点**

`createSettlement()`（第 577–587 行）拿区间去查待结算销售事实：

```java
" and s.occurred_at >= ?::timestamptz and s.occurred_at < ?::timestamptz"
...
input.periodStart().atStartOfDay().atOffset(ZoneOffset.UTC),      // ← 把 DB 的本地日期当成 UTC 日期
input.periodEnd().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
```

⇒ **DB 的本地日期被直接当成 UTC 日期用**。

### 2.3 销售事实的瞬时值**没有**被平移（🔴 本节初稿被探针证伪，见下方「更正」）

**初稿（本条已被证伪）** 曾写：「绑定链路上偏移量被丢掉 ⇒ 落库后被平移 +8 小时」。

**修复车道用真实 jar 做了绑定探针，结论相反**：

| 探针 | 结果 |
| --- | --- |
| `?::timestamptz` + `OffsetDateTime`（`+08:00` / `Z` / `+05:30` / `-03:00` 四种偏移） | ✅ **四种偏移全部原样保留**，落库瞬时 = 输入瞬时（`2026-11-01T02:05:30.123+08:00` → `2026-10-31T18:05:30.123Z`） |
| 唯一真会平移的写法 | 墙钟按 UTC 盖章（`toLocalDateTime().atOffset(UTC)`）⇒ +8h —— **本仓库当前代码没有这种写法** |
| 裸 `?`（不带 `::timestamptz`） | ❌ 直接报错 ⇒ 反证了仓库为什么要用 `?::timestamptz` 这个形状 |

⇒ **写回侧是好的。真正的根因在区间侧**（2.1 + 2.2 的混口径）。

### 2.4 真正的触发链（探针实测后修正）

**关键事实（探针实测）**：Testcontainers 的 **PG 会话时区跟随宿主 JVM** ⇒ 本机 `+08:00` 时，
`select current_date` 返回的是**会话本地日**（`2026-11-01`），而同一时刻 **UTC 日仍是 `2026-10-31`**。

于是：

| 量 | 值（本地 2026-11-01 02:0x +08:00 时） | 钟域 |
| --- | --- | --- |
| `select current_date` | `2026-11-01` | **会话本地日（+08:00）** |
| MONTHLY 区间 | `[2026-11-01, 2026-11-30]` | 会话本地日 |
| 区间边界（下界） | `2026-11-01T00:00**Z**` | **UTC 零点** ⛔ |
| 区间边界（上界） | `2026-12-01T00:00**Z**` | **UTC 零点** ⛔ |
| 销售事实 `occurred_at` | `2026-10-31T18:0xZ`（真瞬时，偏移**没丢**） | UTC 瞬时 |

⇒ **区间被「本地日历 + UTC 零点」整体前移了 8 小时** ⇒
当天 00:00–08:00 售出的销售事实落在下界**之前** ⇒ 被排除 ⇒ `pending` 为空 ⇒ 400。

**触发窗口**：UTC 日历日与本地日历日不一致的那 8 小时 ⇒ **本地 00:00–08:00**。

> ⚠️ **cron 是 `0 * * * *`（整点，全天 24 次）⇒ 每天 00:00–07:00 这 8 个整点的 L2 必然假红**
> ⇒ **不修 ⇒ 每天 1/3 的批次推不出去**（用户明确要求「开发完就 push」）。

### 2.5 ⚠️ 更正记录（本条初稿的写回侧判断被证伪）

> **2026-11-01 02:4x 更正**：本文件初稿把根因判成「绑定丢偏移 → 落库平移 +8h」。
> 修复车道的绑定探针**证伪**了该判断（`?::timestamptz` 四种偏移全部保留瞬时）。
> 真根因是**区间侧混口径**：区间按**会话本地日历**算、边界却落**UTC 零点**。
> 修复 accordingly 改为「本模块的『今天』一律按 **UTC 日历**算（`(now() at time zone 'UTC')::date`），
> 与 trade 模块既有口径一致」。
> ⇒ **教训：跨钟域缺陷不要靠读代码猜「偏移被丢了」，要用探针把绑定语义实测出来。**

---

## 三、取证矩阵（三组对照，全部实测）

| 运行环境 | 结果 |
| --- | --- |
| 集成头 `integration/module-dev`（默认 +08:00） | ❌ `Tests run: 20, Failures: 0, Errors: 1` |
| **`origin/integration/module-dev` 的 tip**（另起 worktree `.worktrees/verify-origin`） | ❌ `Tests run: 20, Failures: 0, Errors: 1` ⇒ **远端本身就在红 ⇒ 与本轮无关** |
| **同一份代码，`TZ=UTC ./mvnw -pl services/warehouse -am test -Dtest='ConsignmentIntegrationTest'`** | ✅ `Tests run: 20, Failures: 0, Errors: 0` ⇒ **JVM 默认时区就是开关** |

补充取证：

- `git log origin/integration/module-dev..HEAD -- backend/services/warehouse/` → **空**
  ⇒ 未推送的 49 个 commit **没有任何一个**碰过 warehouse。
- `ConsignmentIntegrationTest.java` / `ConsignmentService.java` 最后一次改动是 `d8b15e93` / `8d4d51db`
  ⇒ `git merge-base --is-ancestor` 判定**两者都已在 origin 上** ⇒ **存量代码**。
- 仓库同款约定已在别处写死（`OutboundTimelinessIntegrationTest`）：
  「**全用 UTC 固定瞬间，不用 now()**：时长 = 终点 − 起点，任何一处飘动都会让断言变成假红」
  ⇒ 本仓的既有做法是**测试里不用 `now()`**；而寄售这条夹具用了 `now()`，且服务侧区间又是 DB 钟 ⇒ 两侧都踩了。

---

## 四、修法（给修复车道的方向）

1. **写回侧**（根因所在）：`occurred_at` 落库必须保住偏移量 ——
   绑定前显式归一到 UTC 瞬时（`occurredAt().atOffsetSameInstant(ZoneOffset.UTC)`），
   或改用 DB 钟（`now()`）生成，**不要**把本地墙钟字面量塞进 `timestamptz`。
2. **区间侧**（同源问题）：区间边界的语义要和 `occurred_at` 的钟域**一致** ——
   要么两端都用 DB 钟的本地日界，要么两端都归一到 UTC。
   ⚠️ 现状是「DB 的本地日期 + UTC 零点」的混合口径，本身就是 bug。
3. **测试侧**（兜底防复发）：夹具不要用 `now()`，改用**固定 UTC 瞬间**（照 `OutboundTimelinessIntegrationTest` 的写法），
   并补一条**往返断言**：「+08:00 传进去，读回来必须还是同一个瞬时」
   （照 catalog 那例：`docs/backlog/2026-10-2026-10-27-catalog-timestamp-roundtrip-assertion-cross-clock-domain.md`）。
4. **报错文案**：「该区间内没有已售出且尚未结算的销售事实」这句话把**区间算错**说成了**业务事实**，
   建议在区间与落库值钟域不一致时给出可诊断的错误（否则运营会被误导）。

⚠️ **本仓还有 8+ 处 `select current_date`**（catalog 2 处 / channel 1 处 / warehouse 4 处以上 …），
**每一处都要看它落边界时用的是哪个钟域** ⇒ 同族问题可能不止这一处，
建议修复车道**顺带做一次全仓扫描并把清单入库**（不要夹带修改其它模块）。

---

## 五、处置

- **不在批次里夹带修** —— 缺陷属**已交付模块 7.7.1.2**（寄售与代销），
  在交付批次里改别的模块属于「重写别人的模块」（同 `2026-10-20-cross-clock-domain-test-assertion.md` 的处置纪律）。
- **单独立项 + 专用修复车道**：本轮已派 `fix-warehouse-consignment-clock-domain`（warehouse 服务，**无迁移**）。
  - **分支**：`fix/warehouse-consignment-clock-domain`（基于集成头 `311e3709`）
  - **worktree**：`/Users/guan/Projects/starcon-worktrees/integration/.worktrees/fix-consign-clock`
  - **并入方式**：修复车道 commit 之后，调度者把该车道在 `.dev-agent/lanes.json` 里标为 `ready`
    ⇒ 下一轮 `lane.mjs merge-ready` 会自动并入（`merge(fix-warehouse-consignment-clock-domain): …`）。
    ⚠️ **不要**在别的 tick 跑 L2 期间往集成头并入（并发改集成头 ⇒ 跨车道假红，
    见 `docs/backlog/2026-10-2026-10-29-sync-merge-markers-pollute-concurrent-l2-false-red.md`）。
- **round-144 的 L2 因此判红 ⇒ 本轮不 push**；修复落地后随下一批一起 push。
