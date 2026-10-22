# 测试用墙钟相对值 + 硬编码绝对时刻 ⇒ 用例每天定时红

发现：2026-10-22 09:13（batch-62 的批次 L2）。发现人：批次调度者。
影响范围：**模块 10.4.1.2 在途跟踪**（warehouse 服务）的
`InTransitTrackingIntegrationTest.节点_时间往返`，以及**每一个批次 L2** ——
它会把整批验证打成红的。

## 现象

2026-10-22 08:45–09:13 的批次 L2 在集成头上失败：

```
[ERROR] InTransitTrackingIntegrationTest.节点_时间往返:718->asAWrite:303->lambda$节点_时间往返$2:720
        » IllegalArgument 新的预计到港 2026-12-01T08:00+08:00 早于当前 ETA 2026-12-01T09:00+08:00：
          把 ETA 往前调会让「已经晚了几天」算成负数，而这正是本模块最需要答对的数
[ERROR] Tests run: 1639, Failures: 0, Errors: 1, Skipped: 0   （warehouse）
[ERROR] Failed to execute goal ... on project warehouse
```

而**上一个绿批次**（2026-10-22 07:06，同一条用例）是通过的。

## 根因

**缺陷在测试，不在领域逻辑。**

1. 测试辅助方法（`InTransitTrackingIntegrationTest.java` 第 312–319 行）
   **保留当前的「小时」**：

   ```java
   static OffsetDateTime eta(int daysFromNow) {
     return OffsetDateTime.now(ZoneOffset.ofHours(8))
         .plusDays(daysFromNow)
         .withMinute(0).withSecond(0).withNano(0);
   }
   ```

   跟踪记录的**基线 ETA** 来自 `ETA_ONT = 40`（第 65 行）⇒
   **基线 ETA = `eta(40)` = 今天 + 40 天、保留当前小时** ⇒ 今天 2026-10-22 即 `2026-12-01T{当前小时}:00+08:00`。

2. 用例第 717 行把「改期后的新 ETA」**硬编码**成绝对时刻：

   ```java
   var newEta = OffsetDateTime.of(2026, 12, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8));
   ```

3. 两者混用 ⇒ 这条用例**只有在墙钟（UTC+8）小时数 < 08 时才成立**：

   | 跑批次的墙钟 | 基线 ETA（`eta(40)`） | `newEta` 与它的关系 | 结果 |
   | --- | --- | --- | --- |
   | 07:06（上一个绿批次） | `2026-12-01T07:00+08:00` | 晚于它 | ✅ 绿 |
   | 09:13（本轮） | `2026-12-01T09:00+08:00` | 早于它 | ❌ 红 |

   ⇒ **每天 09:00–23:59（UTC+8）这条用例必红**，
   而 08:00–08:59 恰好相等（`isBefore` 是严格比较）所以侥幸绿。
   **这已经不是"偶发"，是"定时"失败** —— 往后的每个批次 L2 都会红。

4. 领域规则**是对的**（`InTransitTrackingService.java` 第 1059 行）：

   ```java
   if (currentEta != null && r.etaAfter().isBefore(currentEta)) {
     throw new IllegalArgumentException("新的预计到港 ... 早于当前 ETA ...");
   }
   ```

   「ETA 不许往前调，因为『已经晚了几天』会算成负数」是正确的业务纪律，**不许放宽这条规则**。

## 与最近并入车道的关系：**无关**

`git log da1f96f9..HEAD -- backend/services/warehouse/` **为空** ⇒ warehouse 后端代码与上一个绿批次
**逐字节相同**。最近并入的 4 条车道（15.1.1.1 / 15.2.1.1 / 15.3.2.1 / 15.6.2.2）**全在 crm 服务**，
不碰 warehouse 的任何代码或迁移。
⇒ 这是**既有缺陷被墙钟暴露**，不是跨车道互相破坏，**不要去二分本批的车道**。

## 取证补充

- **本轮 L2 的 reactor 顺序**：`backend → tenancy → identity → catalog → channel → warehouse(FAILURE) → trade(SKIPPED) → crm(SKIPPED)`
  ⇒ **batch-62 那 4 条 crm 模块的后端测试本轮根本没跑到**（crm 被 SKIPPED），
  前端四段（typecheck / lint / vitest / Next build）也未执行。
  下一轮 L2 必须**跑完整个 reactor** 才算覆盖。
- 全仓后端测试树里，硬编码 `2026-12-01` **只有这一处**
  （`grep -rn '2026, 12, 1' backend/services/*/src/test/java/` 仅命中该文件第 717 行）。
- 但 `eta(0)` 在同文件里被大量复用（`occurredAt` / `location` 等），
  **是否还有别的「相对当前」与「固定时刻」混着比的地方，需要自己再扫一遍**。

## 处置

已派**修复车道** `10.4.1.2`（分支 `fix/10-4-1-2-eta-clock`，
worktree `.worktrees/10.4.1.2-fix`）：

- 修法约束：**让「改期后的新 ETA」与「记录里当前的 ETA」同源**（例如 `eta(0).plusDays(1)`），
  使其**无论墙钟几点都严格晚于当前 ETA**；
- **禁止**用硬编码绝对时刻去"对齐"（明天 09:00 又会红）；
- **禁止**放宽断言 / 删断言 / `@Disabled` / `assumeTrue` 绕过；
- 必须用**至少两个不同时刻基准**证明墙钟无关；
- **领域规则不许动**。

### ✅ 已闭环（2026-10-22 10:19）

修复车道已落地并入集成头（commit `a74a5817`，并入 `cd4e29a5`）：
新 ETA 改为从跟踪行读回 `effectiveEta()` 再 + 1 天（**用领域规则比较用的同一个值当基准**），
注入可钉死测试时钟，新增永久回归用例「改期基准_与墙钟小时数无关」（07/09/23 三个基准），
原断言一条未删未放宽，领域规则未动。
L1 **19 例全绿**，三基准分别单跑均绿，**负对照**（放回旧硬编码值）三档全红。
⇒ **10:20 的 L2 重跑已跑过 warehouse 段（全绿）**，本条不再是阻塞项。

## 全仓同类形态扫描（2026-10-22 调度者自查）

**方法**：静态扫描 `backend/services/*/src/test/java/` 全部测试文件，
筛出**同时**含「`now()` 派生时间戳」与「硬编码绝对日期/时刻（2025–2039）」的文件 ⇒ **32 个**。
逐个核对硬编码值在比较式里的位置，判定是否与 `now()` 派生值互相比对。

**结论：全仓只有 `InTransitTrackingIntegrationTest` 一处存在「绝对值 ↔ now 派生值」互相比对**，
其余 31 个文件里的硬编码绝对值都是**两侧同为绝对值的自洽夹具**（写入什么、断言什么、比较什么全是同一个绝对值），
天然与墙钟无关。已逐个核对的高风险候选：

| 文件 | 硬编码值 | 与 `now()` 的关系 | 判定 |
| --- | --- | --- | --- |
| `warehouse/FirstMileShipmentIntegrationTest` | `ETA = 2026-11-20T09:45+08:00` | 建单/确认两侧都用 `ETA` / `ETA.minusDays(30)`；文件里仅有的 `now()` 只写 `dispatched_at` / `cancelled_at` 审计列，从不与 ETA 比较 | 自洽绝对夹具，**无耦合** |
| `trade/CustomsElementSetIntegrationTest` | `T0/T1/T2 = 2026-01-01 / 2026-07-01 / 2027-01-01` | 「只能设在未来」那一闸比较的是**收紧候选** `2026-06-01`，它无条件落在过去；且作者已自带静态护栏 `assertThat(past).isAfter(T0)`，注释明写「这个『过去』必须晚于生效起点，否则走不到它声称在测的那一闸」 | 已有护栏，**无耦合** |
| `trade/PurchaseRequestFixture` | `PERIOD_START/END/ARRIVAL = 2026-11-01 / 2026-11-30 / 2026-12-05` | 只作 DB 夹具列写入；`PurchaseRequestService` 全文件**无** `now()` 比较 | 自洽绝对夹具，**无耦合** |
| `catalog/InventoryLotIntegrationTest` | `produced/expires` 等成对绝对日期 | 生产/失效日期成对写入，读回断言同值 | 自洽绝对夹具，**无耦合** |
| `channel/ListingPerformanceIntegrationTest` / `AdGroupKeywordReportIntegrationTest` | `DAY_1..DAY_3 = 2026-10-01..03` | 查询窗口与快照日期同源，测试内自比较 | 自洽绝对夹具，**无耦合** |

**残留风险（非当前故障）**：`FirstMileShipmentIntegrationTest.ETA` 在 2026-11-20 之后会变成「过去时刻」。
当前没有任何规则拿 ETA 与 `now()` 比，所以**不会红**；但若将来有人加「ETA 必须晚于今天」这类规则，
这个夹具会首当其冲。建议届时改相对基准，不属于本轮范围。

**给后续批次的结论**：这类地雷罕见的原因很具体 —— 仓库里绝大多数夹具是「两侧同为绝对值」，
只有当**一半绝对、一半相对**时才会炸。所以排查时不必全仓泛扫，
只需盯住「测试里同一个字段/同一类记录，既有 `now()` 派生值又有绝对字面量」的地方。

## ⚠️ 给下一个 tick 的提醒

**本条已闭环**：修复车道 `10.4.1.2` 已于 2026-10-22 10:19 落地并入，
10:20 的 L2 重跑**已跑过 warehouse 段（全绿）**。

若你仍看到 `InTransitTrackingIntegrationTest.节点_时间往返` 报同一条
「新的预计到港 … 早于当前 ETA …」，那**不是**本条缺陷复发，而是**别的**墙钟耦合
（本次全仓扫描已确认除本处外没有第二处同形地雷，见上节）——
请按新回归用例「改期基准_与墙钟小时数无关」失败的位置排查。
