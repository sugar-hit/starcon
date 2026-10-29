# 2026-10-29 · 并行车道 batch-110 —— 13.4.1.2 SKU 利润 + 13.4.3.1 利润预警，132 个提交一次推送

> 档位：**reduced**（已过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800`）⇒ `LANES=4/2=2`、节流闸 `50×2=100` 分钟。
> 集成头 `integration/module-dev`，基线 `9aee9a86`，本批终态 `3e83aa41`。
>
> ⚠️ **本文件由 05:00 tick（round 127）补写。** 派发这两条车道的 01:00 tick 在
> **L2 已经跑绿之后、写说明/commit/push 之前**被 Token Plan 额度熔断打死
> （`Token Plan 用量上限 (2056)`），留下 132 个提交只存在于本机。
> 本轮接管 `stale` 调度闸后：**复核 L2 绿判 → 补写说明 → commit → 一次 push 走完 132 个提交**。

## 摘要

本批交付 **2 个模块**（13.4.1.2 SKU 利润 / 13.4.3.1 利润预警）+ **1 个 L2 红点修复**
（identity SaasUsage flaky 断言）。台账 **424 → 426 已交付**（未开发 41 → 39）。

本轮最关键的一件事**不是新功能，而是把 132 个提交推出机器**：

- 这批提交从第 122 轮起就一直卡在集成头本地 —— 连续 3 轮因为 identity L2 红点而
  「L2 没绿不 push」，**期间交付的 SKU 利润 / 利润预警 / 成本结构 / identity 红点修复
  全部只存在于这台机器上**。
- 本批 L2 于 **03:21:48 跑绿**（11 个 Maven 模块全 SUCCESS，前端 9498 例全过，Next build 成功）。
- ⇒ **一次 push 走完 132 个提交**，这是本仓集成头自 round-107（2026-10-28 16:46）以来第一次出网。

## 档位与闸门判定（05:00 tick 实测）

| 项 | 取值 | 判定 |
| --- | --- | --- |
| 强度档位 | `TOKEN_HORIZON` 已过（当前 2026-10-29 05:00） | **reduced**：`LANES=2`、节流闸 `100` |
| 静默窗口 | `SILENT_WINDOW=0`（全天运行） | 未命中 |
| 批次节流闸 | 距上批 **239 分钟** ≥ 阈值 **100** | ✅ 放行（**通过后才**写时间戳） |
| 温度闸 `pmset -g therm` | `No thermal warning / performance warning / CPU power status has been recorded` | ✅ 通过（无降频项） |
| 配额闸 | running **0** / 配额 **6** | ✅ 通过 |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签 **0** / 上限 6（无标签孤儿 **0**） | ✅ 通过 |
| load 闸 | 1min **1.89** / 5min 1.98 / 15min 1.92，阈值 12 | ✅ 通过（空闲基线，无爬升） |
| 调度闸 | `gate=held` + **`stale=true`**（age **5795s** > TTL 4200s）→ `acquire` 成功 | ✅ 接管上一个崩掉的 tick |

## 进度（`sync.mjs` 实时输出，非手工计数）

- 总数 **465** · 已交付 **426**（**91.6%**）· 进行中 **0** · 未开发 **39** ⇒ 距 465 还差 **39** 个
- 分阶段：**D7 已交付 119 / 未开发 36**；**D6 已交付 75 / 未开发 3**；
  D1–D2 80/0、D5 52/0、D3 40/0、D4 60/0
- 未开发清单（39）：D6 三条定价（`5.1.1.1` / `5.1.1.2` / `5.1.2.1`）+ D7 三十六条
  （卷 13 的 13.5/13.6、卷 14 的 14.2/14.3、卷 15 的 15.3/15.6、**卷 18 全部 21 条**）
- 台账完整性复核：`grep -c` = **465**，模块 ID **无重复**，`sync.mjs --check` = **✓ 台账与蓝图一致**

## 本批回收

两条车道在 01:00 tick 内落地并入，05:00 tick `merge-ready` 返回**空**（已全部 merged）：

```
lane.mjs merge-ready → {"merged": [], "note": "没有待并入的车道"}
lane.mjs gc          → removed: 13.4.1.2, 13.4.3.1, fix-identity-saasusage-flaky
```

并入轨迹：

```
8e16e70c  merge(13.4.3.1): 利润预警
d834acb8  merge(13.4.1.2): SKU 利润
3e83aa41  merge(fix-identity-saasusage-flaky): identity SaasUsage 子串标记 flaky 断言修复
            └─ c904ec63  test(identity): 用量统计租户隔离断言改按字段值核对，消除随机 UUID 引起的假红
```

### 🔴 api-proxy 并入冲突的预防（本批的关键工程决策）

`apps/web/src/lib/api-proxy.ts` 是**共享白名单累积正则文件**，它**不走** merge-union 求并驱动器
（`merge-union.mjs` 只接管 `api-contracts` 与台账），走的是普通 `git merge` ⇒ 两条车道若插在相邻位置会**真冲突**。

⇒ 调度者为两条车道**指定了相距 12637 行的不同插入锚点**：

| 车道 | 锚点 | 实测 hunk |
| --- | --- | --- |
| 13.4.1.2 | 数组靠前段，紧随 13.4.1.1 profit-report 那条之后 | `@@ -622,0 +623,38 @@` |
| 13.4.3.1 | 数组末尾，affiliate-marketing 那条之后、`];` 之前 | `@@ -13259,0 +13298,62 @@` |

⇒ 实测并入后 `api-proxy.ts` 净增 **38 + 62 = 100 行**（`git diff --numstat` = `100 0`），**零冲突**。
命名空间各自独立首 token（`sku-profit` / `profit-alert`），与既有 17 条 `reporting/` 元素首 token 两两不相交。

### 契约求并的漂移复核（调度者实测）

`packages/api-contracts/src/reporting.openapi.json` 的 diff 看着很大（7663 行），实为**纯重新序列化**。逐项核对：

| 项 | `9aee9a86`（基线） | `8e16e70c`（并入后） | 丢失 |
| --- | --- | --- | --- |
| OpenAPI path | 93 | **104** | **0**（新增 11 = 5 条 sku-profit + 6 条 profit-alert） |
| OpenAPI schema | 205 | **227** | **0**（新增 22 = 10 个 `SkuProfit*` + 12 个 `ProfitAlert*`） |
| `contracts/reporting.ts` 导出 | 205 | **227** | **0** |

## 本批交付的模块（每模块一节）

## 模块 13.4.1.2 SKU 利润

**这个功能做什么**：运营打开「SKU 利润」页，选定时间范围与 SKU，看到**单品级**的净利拆解：
卖这一个 SKU 赚了多少钱、收入被哪些成本吃掉（采购 / 头程 / 平台费 / 物流 / 广告 / 退款）、
构成瀑布一眼看下来、还能钻到**订单级明细**逐单核对。定价、选品、采购谈判三方据此对齐口径。

**技术落地**：

- 迁移：`backend/services/reporting/src/main/resources/db/migration/V400__sku_profit.sql`
  — 一张新表 `reporting_sku_profit_refund_fact`（**退款损失**专用），
  `tenant_id uuid NOT NULL` + ENABLE **与** FORCE ROW LEVEL SECURITY + `CREATE POLICY isolation` +
  GRANT `reporting_app` / `starcon_app`；**纯 CREATE TABLE、零 ALTER 既有表**。
- 端点：**5 个**，挂 `/reporting/sku-profit`（`ReportingSkuProfitController.java` 实测 5 个 `@*Mapping`）
  `GET /capabilities`（能力声明：哪些上游真接通、哪些确实算不出来、归谁管）·
  `GET /report`（SKU 利润表，SKU × 币种，**不跨币种合并**）·
  `GET /waterfall`（构成瀑布）· `GET /orders`（订单级明细钻取）· `POST /refresh`（投影刷新）。
- 页面：`/analytics/profit/sku-profit` — `page.tsx` + `components/analytics/sku-profit-{model.ts,workspace.tsx}`；
  加载 / 空 / 错误三态齐全；**前端不做任何业务算术**（净利、构成瀑布全部来自服务端 `BigDecimal` 定标）。
- 测试：`SkuProfitIntegrationTest` **9 例**（口径）+ `SkuProfitTenancyIntegrationTest` **3 例**（租户隔离三断言）·
  前端 `sku-profit-model.test.ts` **23 例** + `sku-profit-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. 🔴 **六项成本只读 13.4.1.1 的投影表，不复制、不改那张表** ——
   `reporting_profit_fact` 已带 `sku_code / spu_code / msku_code / order_ref` 四个 SKU 级维度列，
   本模块就是**同一张表按 SKU 维度再读一次**。**口径归本模块，事实归 13.4.1.1**，
   不新建第二张存同一笔钱的表（避免两表漂移）。
2. 🔴 **退款损失单独一张表，刻意不扩 `reporting_profit_fact.component` 值域** ——
   扩值域会让 13.4.1.1 的毛利**静默漏掉退款**（不是报错，是**算错**，而它的毛利是 5 定价的下游输入）；
   且「一笔钱只有一个写入所有者」⇒ 退款损失这笔钱在本仓只有本模块写。
   **代价写清楚**：退款与其它六项不在同一张表 ⇒ 净利要跨两张表读，报表口径说明里已写明。
3. 🔴 **「算不出来」与「等于 0」分开，且分四种状态** ——
   `AVAILABLE` / `MISSING`（本 SKU 没有这类成本）/ `NOT_ATTRIBUTABLE`（上游只摊到店铺订单，
   **本模块不重算分摊**）/ `NOT_APPLICABLE`（本租户压根没有这类成本）。
   净利缺任何一项成本 ⇒ `null` + 原因（页面「**—（不可比）**」），**不是 0.00**。
4. 🔴 **退款只认 `SUCCEEDED`** —— DRAFT / PENDING_APPROVAL / EXECUTING 都不算（申请了不等于退了）；
   退款单挂在订单上不挂 SKU ⇒ 只有在「退款金额与售后单涉及行的申请金额之和**逐字相等**」时才按行拆分，
   **对不上就不挂到任何 SKU**（宁可「不可归属」，不编造）。
5. **口径必须在报表上写明**（蓝图原话：口径一旦有争议三方都会不认账）。

## 模块 13.4.3.1 利润预警

**这个功能做什么**：系统盯着利润投影，**自动算出五类利润异常**并列出预警清单：
SKU 毛利转负、店铺利润率骤降、费用占比异常、广告亏损超标、订单级亏损订单占比突增。
运营可自己调阈值、收到通知、并从预警直接跳到处置入口（调价 / 广告 / 采购）。
蓝图原话：**「亏损订单占比是广告投放失控的早期信号，比 ROAS 下降更早出现」**。

**技术落地**：

- 迁移：`backend/services/reporting/src/main/resources/db/migration/V401__profit_alert.sql`
  — 两张新表：`reporting_profit_alert_rule`（**阈值规则**，租户可改）+
  `reporting_profit_alert_notification`（**通知投递记录**）；
  `tenant_id NOT NULL` + ENABLE/FORCE RLS + `CREATE POLICY isolation` + GRANT；
  **纯 CREATE TABLE、零 ALTER 既有表**。
- 端点：**7 个**，挂 `/reporting/profit-alert`（`ReportingProfitAlertController.java` 实测 7 个 `@*Mapping`）
  `GET /capabilities` · `GET /alerts`（预警清单）· `GET /rules` + `PUT /rules/{alertType}`（阈值规则）·
  `POST /notifications` + `GET /notifications` + `PUT /notifications/{id}`（通知与处置状态）。
- 页面：`/analytics/profit/profit-alert` — `page.tsx` + `components/analytics/profit-alert-{model.ts,workspace.tsx}`；
  「判不出来」单列一区，三态齐全。
- 测试：`ProfitAlertIntegrationTest` **16 例** + `ProfitAlertTenancyIntegrationTest` **3 例** +
  前端 `profit-alert-model.test.ts` **25 例** + `profit-alert-allowlist.test.ts`。

**关键设计决策**：

1. 🔴 **预警是读模型，不建「预警事实表」** —— 预警 = 「13.4.1.1 投影 + 租户阈值规则」的**确定性计算**。
   建表能查历史，但会引入**第二个真相**（投影改了、预警表没重算），
   且违反「一笔钱只有一个写入所有者」与「报表可从事件重建」⇒ **不建**，历史靠「按历史窗口再查一次」得到。
2. 🔴 **「判不出来」与「没异常」必须分开（比「算不出来 ≠ 0」更要命）** ——
   历史期数不够 / 订单级毛利算不出来 / 该店铺没有广告费事实 ⇒ 单列 `undecided` 清单逐条写明原因，
   **绝不混进「没有命中」**。**误报会被处理，「没异常」会被忽略。**
3. 🔴 **阈值单位由 (类型 × 口径) 派生，不落库** —— 「骤降 10」是 10 个**百分点**还是 10**%** 是两种含义；
   存一份单位副本 ⇒ 上游改了口径、库里副本不会跟着改 ⇒ 阈值含义静默变掉。
4. **通知 = 可观测投递记录**，通道只有站内 `CONSOLE` —— 按纪律不硬编码 SMTP / webhook / token
   （secret 一律不入仓库，走 secret manager）。
5. **「亏损订单占比」的分母取可比订单数**（算得出毛利的订单）⇒ 占比是**下界**；
   总数与可比数在响应和页面同时给出。

## 静态预检（互相破坏形状扫描）

全仓基线与上一轮**完全一致**（本批新增迁移**零命中**）：

- ① `ADD COLUMN` 无 `DEFAULT`：**113**（历史存量，本批 **+0**）
- ② `SET NOT NULL`：**42**（历史存量，本批 **+0**）
- ③ `ADD CONSTRAINT…CHECK`：**28**（历史存量，本批 **+0**）

复核本批两条新迁移：

- `V400__sku_profit.sql`：1 张 `CREATE TABLE`（`reporting_sku_profit_refund_fact`），
  ① **0** · ② **0** · ③ **0**，`ALTER TABLE` 3 处**全部是 RLS 启用循环**
  （`EXECUTE format('ALTER TABLE %I ENABLE/FORCE ROW LEVEL SECURITY', tbl)`，只作用于新表）。
  ⚠️ 该迁移里有一段注释明确记录了**刻意不做**
  `ALTER TABLE ... DROP CONSTRAINT ... ADD CONSTRAINT` 扩 `reporting_profit_fact.component` 值域的理由。
- `V401__profit_alert.sql`：2 张 `CREATE TABLE`（`reporting_profit_alert_rule` /
  `reporting_profit_alert_notification`），① **0** · ② **0** · ③ **0**，`ALTER TABLE` 2 处同为 RLS 循环。

迁移版本号无冲突：`V400` / `V401` 仅存在于 reporting 服务，紧随 `V399__cost_structure.sql`。

## 🔴 本批最值得记的一件事：worker 被传输层错误连杀三次

13.4.1.2 这条车道的**代码是第一个 worker 写完的**，但它在跑契约 codegen 时被
`net::ERR_CONNECTION_CLOSED` 打死；第二个 worker 接手后跑 L0 编译闸时又被
`net::ERR_HTTP2_PING_FAILED` 打死。

⇒ 同一时间窗内，**独立的 main→集成头同步 cron 也死于 `ERR_CONNECTION_CLOSED`**，
⇒ 判定为**一次传输层基础设施抖动**，不是车道自身缺陷。

**处置（三次派发，成果零丢失）**：

| 次序 | worker | 结果 |
| --- | --- | --- |
| 1 | 原始派发 | 写完全部代码 + 契约 codegen，**死在 codegen 之后** |
| 2 | 接手 | 核对现状 + 心跳 + 启动 L0，**死在编译闸中途** |
| 3 | 接手（紧凑任务书） | L0 + L1 全绿 + 台账 + 会话日志 + 2 个 commit + **land 成功** |

⇒ **worktree 是成果的保险箱**：三次 worker 死亡，代码一次没丢。
第 2 次接手时任务书第 1 节就是「摸清现状」，并写死「**不要 `git reset --hard`、不要删掉重来**」。

## L2 批量验证：第一次红、定位后第二次绿

### 第 1 轮：红在 identity [3/11]，1/644 失败

```
./mvnw verify → [ERROR] Tests run: 644, Failures: 1, Errors: 0, Skipped: 0  → BUILD FAILURE
  ✗ SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions
    Expecting actual: "[{... "subscriptionId":"01a183ac-a805-73ec-9e39-3554907f1112" ...,
                               "quantity":222.0 ...}]"
    not to contain: "111"
```

**不是假红形状**：全仓只有这 1 处失败，**没有** `Connection refused` / `503` / 连接池耗尽
（协议描述的那种「L2 与在途车道 L1 并发」会打出成片 `seed() 503`）⇒ 是**真失败**。

### 定位（不靠 bisect，直接取证）

| 证据 | 结论 |
| --- | --- |
| `git diff --name-only 9aee9a86..8e16e70c` 里 **零个 identity 文件** | 本批两条车道都在 `reporting` 服务，**与本批无关** |
| 隔离复跑 `-Dtest='SaasUsageIntegrationTest'` **4/4 全绿** | 随机性 |
| 此前一次 identity 全套 **644/644 全绿** | 随机性 |
| `git log` 显示该测试来自 `21289bb0 feat(17.7.1.2): 用量统计` | **早于本批**的既有缺陷 |

**根因**：测试拿**裸数字 `111` / `222` 当子串标记**，对**整份 JSON 原文**做
`contains` / `doesNotContain`。但响应体里除 `quantity` 外还有**随机 UUID**
（`subscriptionId` / `tenantId` / `collectionId`…）——
本次 `subscriptionId` 随机成 `...3554907f1112`，尾部 `111` 让「forB 响应不含 111」假红。
⚠️ 业务代码是对的，**红的是测试的标记选法**。

### 修复（走独立修复车道，**不在集成头上直接改**）

协议要求「L2 红了不要在集成头上继续改」⇒ 建**修复车道**
`fix-identity-saasusage-flaky`（分支 `fix/identity-saasusage-substring-marker`，
worktree `.worktrees/fix-identity-saasusage-flaky`），派 worker 只改这一个测试文件。

**修法**：把「对 JSON 原文做子串匹配」改成「**对解析后的结构按字段值断言**」——
`request()` 本来就返回已解析的 `JsonNode`，旧代码用 `.toString()` 把结构丢掉了。
新增 `assertUsageOnlyFor(rows, tenantId, ownQuantity, foreignQuantity)`：
逐行断言 `tenantId == 目标租户` 且 `quantity` 是数值且 `== 本租户量` 且 `!= 对方量`。

**新断言比旧的更强**（不是弱化换绿）：

| 维度 | 旧断言 | 新断言 |
| --- | --- | --- |
| 判据落点 | 整份 JSON 原文 | 逐行的 `tenantId` + `quantity` **字段值** |
| 租户归属 | ❌ 从不检查 | ✅ 每一行都断言 `tenantId` == 目标租户（**旧测试完全没有这一条**） |
| 对 UUID 的敏感性 | ❌ 随机 UUID 含 `111`/`222` 即误判 | ✅ 完全无关 |
| 其它数字字段 | ❌ `entitlementLimit`(3000) / `utilization`(0.074) 同样可能误伤 | ✅ 只看 `quantity` |

**验证**：

| 闸 | 结果 |
| --- | --- |
| L0 `./mvnw -q compile` / `test-compile` | exit 0 |
| L1 `SaasUsageIntegrationTest` **连跑 5 次** | **5/5 绿**，每次 14 例 |
| L1 identity 全套 | **644 例，0 失败 0 错误**，`BUILD SUCCESS` |
| **变异实验 1**（让 forB 取 A 的行） | 按预期**红**：`报表每一行都必须属于本租户 … but was: "9988e6a2-…"` |
| **变异实验 2**（把 A 的期望量改成 222） | 按预期**红**：`报表每一行的用量都必须是本租户的 222.0 but was: 111.0` |

⇒ 变异实验证明新断言**是活的、不是空断言**（变异后能抓住，说明判据真的在判）。
修复 commit `c904ec63`，并入 `8e16e70c → 3e83aa41`。

### 第 2 轮：绿（02:13:47 → 03:21:48）

```
=== STEP: backend mvnw verify   @ 02:13:47 ===  rc=0
=== STEP: pnpm typecheck         @ 03:19:55 ===  rc=0
=== STEP: pnpm lint              @ 03:19:57 ===  rc=0
=== STEP: pnpm test              @ 03:19:57 ===  rc=0
=== STEP: next web build         @ 03:20:17 ===  rc=0
=== STEP: sync --check           @ 03:21:48 ===  rc=0
=== L2 GREEN at 03:21:48 ===
```

**后端 11 个 Maven 模块全 SUCCESS**（reactor 实测）：

| 模块 | 耗时 | 用例 | Failures / Errors |
| --- | --- | --- | --- |
| tenancy | 0.4s | — | 0 / 0 |
| identity | 03:03 | **644** | 0 / 0 |
| catalog | 05:22 | **1999** | 0 / 0 |
| channel | 20:57 | **2417** | 0 / 0 |
| warehouse | 12:47 | **1672** | 0 / 0 |
| trade | 16:56 | **2460** | 0 / 0 |
| crm | 01:24 | **491** | 0 / 0 |
| finance | 03:39 | **970** | 0 / 0 |
| reporting | 01:38 | **402** | 0 / 0 |
| platform | 18.4s | **97** | 0 / 0 |
| **合计** | — | **11,152** | **0 / 0** |

`BUILD SUCCESS`；全日志 `Failures: [1-9]` / `Errors: [1-9]` **零命中**。

**前端**：vitest **673 个测试文件 / 9498 例全过**（`Test Files 673 passed (673)`）。
**Next build**：`✓ Compiled successfully in 24.8s`，`✓ Generating static pages (388/388)`。
**台账**：`✓ 台账与蓝图一致`。

### 🔴 绿判的有效性复核（05:00 tick 接管时独立取证）

接管时不能只信「status 文件写着 L2:GREEN」，必须确认**这份绿判覆盖的就是本轮要 push 的那棵树**。逐项取证：

| # | 取证 | 结果 |
| - | ---- | ---- |
| 1 | `git log --since='2026-10-29 03:21:48'` | **空** ⇒ 绿判之后**没有任何新 commit** |
| 2 | HEAD 提交时刻 `02:13:42` vs L2 启动时刻 `02:13:47` | HEAD **早于** L2 启动 5 秒 ⇒ L2 跑的就是当前 HEAD |
| 3 | `git diff HEAD --name-only`（排除台账） | **空** ⇒ 工作区代码与绿判时的树**逐字节相同** |
| 4 | 全量 surefire 报告 `find -newermt '2026-10-29 00:00'` 中 `failures="[1-9]\|errors="[1-9]` | **零命中**（727 份报告，最新 03:19） |
| 5 | reactor 摘要 `BUILD SUCCESS` / `BUILD FAILURE` 计数 | **1 / 0** |
| 6 | `origin/integration/module-dev` 是否 HEAD 的祖先 | **是** ⇒ push 是**纯 fast-forward**，不会覆盖远端 |

⇒ 六条独立证据一致 ⇒ **绿判有效且覆盖当前 HEAD**，本轮直接沿用，不重跑 L2
（重跑一次全量要 60+ 分钟机器时间，而被验的代码一个字节都没变 —— 这正是组批协议要省掉的重复）。

## ⛔ 收尾三件套（本轮逐件点名）

| 件 | 状态 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ 完成 | 本文件（batch-110）+ 两条车道各自的会话日志 + round-126/127 两份轮次日志 |
| ② commit | ✅ 完成 | 说明文件与台账**同一个 commit**（台账净改动 = 424→426 已交付、41→39 未开发、完成率 91.2%→91.6%、D7 117→119、卷 13 系统表 15→17、新增 18.5.2.1 行） |
| ③ push | ✅ 完成 | **132 个提交一次推送**（含本批 2 个模块 + identity 红点修复 + 第 122–126 轮积压的 125 个提交），`origin/integration/module-dev` fast-forward |

### 本批 commit 与并入轨迹

```
8e16e70c  merge(13.4.3.1): 利润预警                     ← 车道 b76b238a 并入
d834acb8  merge(13.4.1.2): SKU 利润                    ← 车道 83ea7a2d 并入
3e83aa41  merge(fix-identity-saasusage-flaky): identity SaasUsage 子串标记 flaky 断言修复
            └─ c904ec63  test(identity): 用量统计租户隔离断言改按字段值核对，消除随机 UUID 引起的假红
```

### push 结果

```
git rev-list --count origin/integration/module-dev..HEAD  →  132
git merge-base --is-ancestor origin/integration/module-dev HEAD  →  是（fast-forward）
git push origin integration/module-dev                   →  成功
```

推送后 `origin/integration/module-dev` 的 tip 与本地集成头一致，**第 122 轮以来积压的
132 个提交（含 13.4.2.1 成本结构、13.4.1.2 SKU 利润、13.4.3.1 利润预警、identity 红点修复）
全部出网**，不再只存在于本机。

## 吞吐预期（按当前档位现算，不引用任何历史文档的写死数字）

- 档位：`MODE=reduced` ⇒ `LANES=2`、`THROTTLE_MIN=100`
- cron：`0 * * * *`（整点，24 触发点/天）+ `SILENT_WINDOW=0` ⇒ 全天可放行
- 节流闸 100 分钟 > 60 分钟栅格 ⇒ **每 2 个整点放行一批 ≈ 12 批/天**
- 名义吞吐 = 2 × 12 = **24 个/天**；× 综合折损 **0.70** ⇒ **实际约 16–17 个/天**
- 剩余 **39** 个 ⇒ 39 ÷ 16.8 ≈ **2.3 天** ⇒ **诚实区间 2–4 天**
  （上沿假设：Token Plan 额度再次熔断打断在途车道，或 L2 再红一次）

## 后续待办（归下一轮）

1. **本轮已派发 2 条车道**（14.3.1.3 商品维度报表 / V402、13.5.1.1 库存分析 / V403），
   它们在后台开发 ⇒ **下一轮 tick 回收并入**，下一轮再跑本批 L2。
2. **D6 的 3 个定价模块**（`5.1.1.1` / `5.1.1.2` / `5.1.2.1`）适合单独排一批 ——
   它们是卷 5 定价的入口，与卷 13/14/15/18 的 D7 依赖链独立。
3. **卷 18（数据合规与风险）还有 21 个未开发**，是剩余 39 个里最大的一块。
4. **Token Plan 额度熔断仍是最大威胁** —— 本轮实测同时段有 5 个会话死于
   `Token Plan 用量上限 (2056)`（含 01:00 的流水线 tick 与本仓同步 tick）。
   ⇒ 若下一轮发现车道被判 orphan，**优先回收已有半成品**，不要 `git reset --hard` 清掉。
5. **main→集成头同步任务已连续 18 轮卡在 26 个并入冲突**（同墙）⇒
   本流水线不碰它，但建议由同步任务侧排查那 26 个冲突的根因。

---

## 涉及文件

- `docs/sessions/2026-10/2026-10-29-batch-110-sku-profit-and-profit-alert.md`（本文件）
- `docs/sessions/2026-10/2026-10-29-sku-profit.md`（13.4.1.2 车道会话日志）
- `docs/sessions/2026-10/2026-10-29-profit-alert.md`（13.4.3.1 车道会话日志）
- `docs/sessions/2026-10/2026-10-29-0300-pipeline-round126-gate-held-live-peer-l2.md`（round-126 轮次日志）
- `docs/sessions/2026-10/2026-10-29-0500-pipeline-round127-stale-takeover-push-backlog.md`（本轮 round-127 轮次日志）
- `docs/product/capability-blueprint/module-progress.md`（台账，424 → 426）
