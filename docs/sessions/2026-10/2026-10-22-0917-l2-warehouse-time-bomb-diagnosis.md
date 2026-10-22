# 2026-10-22 09:00 tick —— L2 红在 warehouse：墙钟地雷，与 batch-62 无关

> 本文件是**调度者只读诊断记录**，不是批次交付说明。
> 本轮 `lane.mjs gate acquire` 退出码 **3**（调度闸被上一轮 tick 持有）⇒
> **零派发、零改动**：不 commit 台账、不 push、不跑第二个 L2。
> 本文只新增一个文件，不碰 `module-progress.md` 与 batch-62 的说明。
>
> ⚠️ **本文已于 09:31 更正三处表述 + 一处 codegen 横幅结论**（更正依据见文末「更正记录」）。
> **引用本文时以更正后的版本为准。**

---

## 一、结论先说

**batch-62 的批次 L2 红了，但红点不在本批的 4 个模块上。**

| 项 | 事实 |
| --- | --- |
| 红的测试 | `warehouse` 服务 `InTransitTrackingIntegrationTest.节点_时间往返` |
| 错误 | `IllegalArgument: 新的预计到港 2026-12-01T08:00+08:00 早于当前 ETA 2026-12-01T09:00+08:00` |
| 后端 verify | `Tests run: 1639, Failures: 0, Errors: 1`，`BUILD FAILURE`，耗时 27:46 |
| reactor | identity / catalog / channel **SUCCESS**；**warehouse FAILURE**；**trade / crm SKIPPED** |
| 根因 | **测试依赖墙钟**，从 **2026-10-22 09:00+08:00** 起必红 |
| 与 batch-62 的关系 | **无关**。`git diff da1f96f9..HEAD -- backend/services/warehouse/` **输出为空** |

**最关键的一条**：batch-62 的 4 个模块（`15.1.1.1` 消息收件箱 / `15.2.1.1` 评价列表 /
`15.3.2.1` 客户标签 / `15.6.2.2` 购物车与弃单挽回）**全部在 `crm` 服务**，
而本轮 reactor 在 `warehouse` 就中断 ⇒ **`crm` 根本没跑到**。
⇒ 这批模块在批次 L2 里的通过与否**至今未验证**，不能因为「L2 只红了一个」就以为它们过了。

---

## 二、根因推导（可复核）

```java
// backend/services/warehouse/src/test/java/.../InTransitTrackingIntegrationTest.java
static final int ETA_ONT = 40, ETA_WATCH = 5, ETA_WARNING = 2, ETA_OVERDUE = -2;   // :65

/** ETA：相对现在的天数。 */
static OffsetDateTime eta(int daysFromNow) {                                        // :313
  return OffsetDateTime.now(ZoneOffset.ofHours(8))
      .plusDays(daysFromNow)
      .withMinute(0)
      .withSecond(0)
      .withNano(0);
}

// 失败用例里：
var shipmentId = dispatched(a, ETA_ONT, "100");   // → 当前 ETA = eta(40) = now+40d
var newEta = OffsetDateTime.of(2026, 12, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8));      // :717 硬编码
```

领域规则（`InTransitTrackingService.java:1060`）拒绝对「ETA 往前调」：

```java
if (currentEta != null && r.etaAfter().isBefore(currentEta)) {
  throw new IllegalArgumentException("新的预计到港 " + r.etaAfter()
      + " 早于当前 ETA " + currentEta + "：把 ETA 往前调会让「已经晚了几天」算成负数…");
}
```

⇒ 测试用**硬编码绝对时刻**（`2026-12-01T08:00+08:00`）去改期，
而被改的基准 ETA 是 **`now + 40 天`**。

### ⚠️ 关键细节：`eta()` 截断到**小时**，不是分钟

```java
.withMinute(0).withSecond(0).withNano(0);   // 只清分/秒/纳秒，**小时保留**
```

⇒ `eta(40)` 的**小时** = 当前小时。而领域规则用的是 **`isBefore`（严格小于）**，
不是 `!isAfter`。于是**相等时不触发**：

| `now` 落在 | `eta(40)` | 与 `newEta`(08:00) 的关系 | `isBefore` | 结果 |
| --- | --- | --- | --- | --- |
| `[08:00, 09:00)` | `2026-12-01T08:00` | **相等** | false | **绿**（侥幸） |
| `≥ 09:00` | `2026-12-01T09:00` | 08:00 早于 09:00 | **true** | **红** |

### 临界时刻

```
truncate_hour(now + 40d) > 2026-12-01T08:00   ⇔   now ≥ 2026-10-22T09:00+08:00
```

| 批次 L2 | warehouse 模块段 | `eta(40)` | 结果 |
| --- | --- | --- | --- |
| batch-61（绿，commit `da1f96f9`） | **07:22:26 – 07:34:07** | `2026-12-01T07:00` | 08:00 晚于 07:00 ⇒ **绿** |
| batch-62（本轮） | **09:01:26 – 09:13:07** | `2026-12-01T09:00` | 08:00 早于 09:00 ⇒ **红** |

本轮报错原文「当前 ETA **2026-12-01T09:00**+08:00」**直接印证**了 `eta(40)` 取的是 09 点。

**红线 09:00 正好落在两次 L2 之间**（绿批次 warehouse 段 07:22–07:34，红批次 09:01–09:13）。
这不是「上一批绿、这批红」的竞态，而是**同一天内定时到期的墙钟地雷**。

### 为什么可以断定「不是本批回归」

```bash
cd "$PROJECT_ROOT"
git diff --stat da1f96f9..HEAD -- backend/services/warehouse/   # 输出为空
git log --oneline da1f96f9..HEAD -- backend/services/warehouse/ # 空
```

⇒ `warehouse` 的**后端代码与测试文件与上一绿批次逐字节相同**。
本批 4 个 merge commit（`46a83058` / `aec08bbc` / `32be1d28` / `52f5ebeb`）改的全是 `crm` + 前端 + 契约。

**⇒ 二分本批的 merge commit 是浪费机器时间，定位已经闭环。**

---

## 三、修法建议（只改测试，**不要动领域规则**）

领域规则「ETA 不能往前调」是**正确的业务规则**（往前调会让「已经晚了几天」算成负数），
错的是**测试写死了绝对时刻**。

- 把 `newEta` 从硬编码改成**与基准同源推导**（例如 `eta(ETA_ONT).plusDays(1)`），
  **禁止**用另一个硬编码绝对时刻去「对齐」——那是把地雷换个位置再埋一次。
- **禁止**放宽断言、禁止 `@Disabled`、禁止 `assumeTrue` 跳过。
- **必须用至少两个不同时刻的基准**证明与墙钟无关（否则换个时刻又炸）。

### ⚠️ 不要顺手改这两个文件（它们不是同一种病）

全仓后端测试树里硬编码绝对时刻有几十处，**绝大多数是确定性 fixture（自己跟自己比）**。
真正的判据是：**「一边来自 `now()`、另一边是硬编码」**。只有
`InTransitTrackingIntegrationTest` 同时满足这两条。据此核对：

| 文件 | 硬编码内容 | `now()` 的角色 | 判定 |
| --- | --- | --- | --- |
| `warehouse/FirstMileShipmentIntegrationTest.java` | `ETA = 2026-11-20T09:45+08:00` | 只出现在 INSERT 的 `created_at`（:304）与若干时间戳字段（:567/1236/1291/1303） | **确定性 fixture**：:1658 的断言用的是同一个绝对值（`2026-11-20T01:45Z`），不与 `now()` 比较 ⇒ **不用改** |
| `channel/LogisticsTransitIntegrationTest.java` | `WINDOW_FROM/TO`、`INSIDE/OUTSIDE_WINDOW` | 文件 :52 / :151 注释**明确写着**「本测试不用 `now()` 去喂一个会被断言的分片键」 | **确定性窗口** ⇒ **不用改**（这正是我们想要的正确写法） |

---

## 四、这一轮我独立核过、可以直接复用的四项审计（L2 抓不到的那类风险）

### 1. api-proxy 白名单完整性（静默删路由）

`services[]` 正则数 **基线 148 → HEAD 152（净 +4）**；
**基线正则丢失 0 条** ⇒ 没有路由被静默删掉。
新增 4 条首 token 分别是 `message-inbox` / `reviews` / `customer-tags` / `cart-recovery`，
与本批 4 个模块一一对应。

> 这一项**必须每次批次单独核**：`api-proxy.ts` 是共享白名单累积文件，
> 取错一侧会删掉别人已交付的路由，而 typecheck / lint / vitest / Playwright **全绿**。

### 2. OpenAPI 契约语义 diff（codegen 产物只增不减）

| 维度 | 基线 | HEAD | 被删 |
| --- | --- | --- | --- |
| `paths` | 44 | 91 | **0** ✓ |
| `components.schemas` | 76 | 159 | **0** ✓ |
| 既有 path 上被删的 method | — | — | **0** ✓ |

⇒ **纯增量**。文本层面 `crm.openapi.json` 有 1521 行删除，是 codegen 重排/重排版造成的，不是语义删除。

**类型零丢失（逐车道核对）**：车道提交 `bdceadbd` 的 `crm.ts` 有 **91** 个 `export type`，
HEAD 有 **159** 个；**车道有而 HEAD 没有的 = 0**，HEAD 多出的 68 个来自同批另外 3 条车道 ⇒ 类型一个不缺。

**一条「横幅位置」漂移（不影响功能，但会让下次 codegen 产生首行 churn）**：

`// Generated by tools/codegen/catalog-contracts.py from Spring OpenAPI. DO NOT EDIT.`
这一行**没有丢**，但**不在第 1 行，而是在文件末尾**：

| 文件 | 横幅行号 / 总行数 | 基线 `da1f96f9` | 归属 |
| --- | --- | --- | --- |
| `catalog.ts` / `identity.ts` / `warehouse.ts` | 第 1 行 | 第 1 行 | 正常 |
| `channel.ts` | **1349 / 1349**（末行） | 末行 | **既有漂移**（更早批次留下） |
| `trade.ts` | **1718 / 1718**（末行） | 末行 | **既有漂移**（更早批次留下） |
| `crm.ts` | **160 / 160**（末行） | 第 1 行（本批之前正常） | **本批 merge 驱动器造成** |

⇒ 合并求并驱动器把横幅当普通行处理、求并后落到文件末尾。
**车道提交 `bdceadbd` 的 `crm.ts` 首行是横幅，其 merge commit `52f5ebeb` 首行就不是了**，
而 `grep -c 'DO NOT EDIT'` 仍为 1 ⇒ 横幅在，只是位置变了。
⇒ **结论修正**：不是「横幅丢了」，而是「横幅挪到了末行」。类型零丢失，功能不受影响。
⇒ 按 `docs/backlog/2026-10-20-contract-drift-ci-gate.md` 的规矩处理：**工具链域、单开修复车道、不要塞进功能批次**。

### 3. 本批 4 个迁移的「互相破坏三形状」静态预检 —— **全部 0 命中**

| 迁移 | 表数 | `ADD COLUMN` 无 DEFAULT | `SET NOT NULL` | 新增 CHECK |
| --- | --- | --- | --- | --- |
| `V293__crm_message_inbox.sql` | 3 | 0 ✓ | 0 ✓ | 0 ✓ |
| `V294__crm_product_review.sql` | 1 | 0 ✓ | 0 ✓ | 0 ✓ |
| `V295__crm_customer_tag.sql` | 5 | 0 ✓ | 0 ✓ | 0 ✓ |
| `V296__crm_cart_recovery.sql` | 6 | 0 ✓ | 0 ✓ | 0 ✓ |

四个迁移全是**纯 `CREATE TABLE` + `FORCE ROW LEVEL SECURITY`**，零 ALTER 既有表 ⇒ 不存在跨车道违约面。
各服务 Flyway 版本号**无重复**（crm 共 8 个：V292/293/294/295/296…）。

### 4. 台账

`node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**。

**465 总数 / 316 已交付 / 0 进行中 / 149 未开发 / 68.0%**，离全部交付还差 **149**。

---

## 五、本轮的边界与后续待办

### 本轮做了什么 / 没做什么

| 协议动作 | 结果 |
| --- | --- |
| 同步台账 | ✓ `sync.mjs --check` 一致（465/316/0/149） |
| 取调度闸 | **退出码 3**（闸被上一轮 tick 持有，09:04:46 仍有其 keepalive）⇒ **零派发** |
| 回收已落地车道 | 无可回收（`lanesRunning: 0`、`lanesReady: 0`，batch-62 的 4 条已全部并入） |
| 批量验证 L2 | **红**（warehouse 墙钟地雷）；`crm` 被 SKIPPED，batch-62 的 4 个模块**仍未验证** |
| 静态预检 | ✓ 0 命中 |
| 说明 | ✓ 本文 + batch-62 说明（`2026-10-22-batch-62-message-inbox-cart-recovery.md`） |
| commit | ⚠️ **batch-62 的说明 + 台账留给持闸会话提交**（避免两个 tick 抢同一 worktree）；本文单独提交 |
| push | ❌ **未 push —— L2 红**。成果与诊断留在集成头本地，修复后随下一批一起推 |

### 后续待办（交给持闸会话 / 下一轮）

1. **修复车道**：`10.4.1.2`（分支 `fix/10-4-1-2-eta-clock`）——
   只改测试、新 ETA 与当前 ETA **同源推导**、禁止硬编码时刻「对齐」、
   禁止放宽断言 / `@Disabled` / `assumeTrue`、必须用**至少两个不同时刻基准**证明墙钟无关、**领域规则不动**。
2. **重跑 L2**：修复并入后重跑**整个 reactor**（注意 `crm`/`trade` 这轮被 SKIPPED，必须跑到）。
3. **绿了再 push**：batch-62 的 4 个模块（`15.1.1.1` / `15.2.1.1` / `15.3.2.1` / `15.6.2.2`）
   连同本文一起推上 `origin/integration/module-dev`。
4. **别再二分**：warehouse 那条已定位到墙钟，**不要**对本批 4 个 merge commit 做二分。
5. **不要改那两个姊妹文件**（见 §三）——它们是确定性 fixture，不是地雷。

### 一条给流水线的通用教训

> **集成头里存在依赖墙钟的测试时，「批次 L2 全绿」这个信号会随时间自动失效。**
> 上一个绿批次只证明「在那个时刻绿」。这类测试应该在**入仓前**就被识别出来，
> 否则它会以「某个批次突然红了」的形式出现，并误导定位方向（指向最近的批次而不是它自己）。
>
> 识别口径：**一边来自 `now()`、另一边是硬编码** —— 两者相遇才是地雷；
> 硬编码对硬编码（自己跟自己比）是确定性 fixture，是**正确**的写法。

---

## 更正记录（2026-10-22 09:31）

本文初版有四处需要更正，均已由持闸会话复核、独立重算后确认：

| # | 初版表述 | 更正后 | 错在哪 |
| --- | --- | --- | --- |
| 1 | 临界时刻 `now ≥ 2026-10-22 08:00+08:00` | **`now ≥ 2026-10-22 09:00+08:00`** | 漏了 `withMinute(0)` **保留小时**这一级截断 ⇒ 漏掉了「相等 ⇒ `isBefore` 为 false ⇒ 不触发」的 08:00–08:59 侥幸绿区间 |
| 2 | warehouse 模块段 `08:54–08:57`；「绿批次离红线只差 54 分钟」 | **`09:01:26 – 09:13:07`**；红线 09:00 落在两次 L2 之间 | 把 Maven reactor summary 的**每模块耗时**误读成**累计耗时**。正确算法：各模块时间相加 = 0.001+0.427+76+322+565+701 = **1664.4s ≈ Total 27:46 min** ⇒ 每模块时间；warehouse 起点 = 08:45:23 + (0.427+76+322+565)s = **09:01:26**，与 surefire fork 进程实际起始 **09:01:28** 吻合 |
| 3 | 把 `FirstMileShipmentIntegrationTest` / `LogisticsTransitIntegrationTest` 列为「同形地雷，建议一并扫」 | **两者都是确定性 fixture，不用改** | 判据应是「一边 `now()`、一边硬编码」；这两个文件都是硬编码对硬编码 |
| 4 | 「`crm.ts` 少了 codegen 横幅」 | **横幅没丢，被 merge 驱动器挪到了末行**；`channel.ts`/`trade.ts` 早就是末行（既有漂移）；类型零丢失（91 ⊆ 159） | 只看了首行 diff 与 `head -1`，没查 `grep -n 'DO NOT EDIT'` 的实际行号 |

---

## 文件清单

| 文件 | 性质 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-22-0917-l2-warehouse-time-bomb-diagnosis.md` | 本文件（新增，调度者只读诊断 + 09:31 更正） |
| `docs/sessions/2026-10/2026-10-22-batch-62-message-inbox-cart-recovery.md` | batch-62 说明（持闸会话已提交） |
| `docs/backlog/2026-10-22-in-transit-eta-test-wall-clock-dependency.md` | backlog 条目（持闸会话写） |
| `docs/backlog/2026-10-20-contract-drift-ci-gate.md` | 契约漂移 CI 闸（持闸会话追加） |
| `docs/product/capability-blueprint/module-progress.md` | 台账 316/149（持闸会话提交） |
| `backend/services/warehouse/src/test/java/com/starcon/warehouse/InTransitTrackingIntegrationTest.java` | 待修（**本轮未改**） |
| `backend/services/warehouse/src/main/java/com/starcon/warehouse/InTransitTrackingService.java` | 领域规则，**正确，不要改** |
