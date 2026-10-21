# Batch 48 —— 质检单 / 采购对账 / 采购分析（+ 5.5.1.1 时刻无关修复）

> 并行车道流水线 · 批次 48 · 2026-10-21（08:00 tick 的后半程）
> 集成头 `integration/module-dev` · 与 batch-47 同一 tick 的第二组车道
> 批次号接在 batch-47 之后（`docs/sessions/2026-10/` 下现有最大为 batch-47，已确认无重号）

## 本批概览

| 模块 ID | 名称 | 阶段 | 归属服务 | 迁移版本 | 车道分支 |
| --- | --- | --- | --- | --- | --- |
| 6.4.2.1 | 质检单 | D6 | trade | `V249__quality_inspection_order.sql` | `feat/6-4-2-1` |
| 6.5.1.1 | 采购对账 | D6 | trade | `V250__purchase_reconciliation.sql` | `feat/6-5-1-1` |
| 6.5.2.1 | 采购分析 | D6 | trade | `V251__purchase_analytics.sql` | `feat/6-5-2-1` |
| 5.5.1.1 | 价格历史（**修复车道**） | D6 | catalog | 无迁移 | `fix/5-5-1-1-price-history-window` |

3 条新车道全部落在 **trade** 服务、**3 个互不相同的 L3 锚点**（`6.4.2` / `6.5.1` / `6.5.2`），
彼此无依赖；第 4 条是 batch-47 的 L2 红了之后派出的**定点修复车道**（见文末「修复」一节）。

---

## 模块 6.4.2.1 质检单

**这个功能做什么**：来料/成品质检 —— 按类目模板算抽样比例、逐项记录检验结论
（外观 / 功能 / 规格 / 包装 / 认证标识）、判不合格并扣量，最后给出
**合格量 / 不合格量 / 让步接收量**三个数。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V249__quality_inspection_order.sql`
  — **5 张新表**（质检模板 / 模板检验项 / 质检单 / 质检明细 / 让步接收），
  `tenant_id NOT NULL` + **ENABLE 与 FORCE** RLS + 索引 + GRANT trade_app，
  **4 个护栏触发器**，**零 ALTER 任何既有表**。
- 端点：**17 个** `/trade/quality-inspections`（按 `@XxxMapping` 逐条统计）。
- 页面：`/purchase/quality`（工作区 + 纯函数模型）。
- 测试：**38 例**（`QualityInspection*`，含租户隔离三断言）+ 前端模型与 allowlist 断言；
  全量 allowlist 回归 **133 文件 / 838 例**全绿。

**关键设计决策**：

1. **三量的口径定成「本次质检覆盖的批量」而不是「到货量」**：批量 `lot_size ≤ arrived_quantity`，
   三量和的基数是 `lot_size`。这是卷 06 加粗句「采购收货是分批、可有差异的」的直接落点 ——
   一批到货可以分多次质检，每次质检只对自己的批量负责。
2. **三量自洽由 DB CHECK 钉死**：`COMPLETED` 态强制 `qualified + defective + concession = lot_size`。
   绕过服务层直连 SQL 也写不进不自洽的质检单。
3. **让步接收从扣量合计「里面」出**：`concession ≤ gross_deducted`、
   `defective + concession = gross_deducted`（两条 DB CHECK）。
   让步接收**必须带原因与权限码**（纪律来自 6.4.2.2：让步接收需要权限与原因，
   不能由质检员随手放行），但**不实现处置流程**（退换 / 返修 / 报废归 6.4.2.2，未交付）。
4. **抽样比例是 0~1 比率**：服务端算 `批量 × 比例 → 夹逼[min,max] → 批量封顶 → 舍入口径取整`，
   算式原文落 `sampling_basis` —— 不是前端传来的数。
5. **库存质量状态分流如实标 `available=false` + `ownerModule` + 影响说明，不假装已分流**：
   warehouse 侧没有任何「按质检结论把货登记到待检 / 残次 bucket」的授权写端点
   （唯一质量状态写入口是 8.2.1.2 的差异处置决定，且必须挂在真实收货差异上）。
   `disposition_status` 标 `UNAVAILABLE`。
6. **未交付项如实标注**：6.4.2.2 不良品处理、6.1.1.3 评级、6.5.1 采购对账。

---

## 模块 6.5.1.1 采购对账

**这个功能做什么**：采购侧按期间与供应商发起对账 —— 汇总应收数量、单价、金额、
代扣代缴费用与索赔，生成对账单发给供应商确认，差异逐条挂起。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V250__purchase_reconciliation.sql`
  — **5 张新表**，`tenant_id NOT NULL` + **ENABLE 与 FORCE** RLS + GRANT，
  **零 ALTER 既有表**。
- 端点：**15 个** `/trade/purchase-reconciliations`（capabilities / options / 集合读+建单 /
  {id} / lines / deductions / send / confirmations / discrepancies(GET+POST) / …/resolve /
  reopen / close / export）。
- 页面：`/purchase/reconciliation`（真实工作区 + 纯函数模型）。
- 测试：**17 例**（`PurchaseReconciliationIntegrationTest` 13 + `PurchaseReconciliationTenancyIntegrationTest` 4）；
  前端模型 20 例 + allowlist 5 例；全量 allowlist 回归 **126 文件 / 729 例**全绿。

**关键设计决策**：

1. **职责分离是硬约束**：对账由采购发起，**采购侧不确认应付**。
   `finance_handoffStatus` 恒为 `PENDING_FINANCE_SERVICE`，并由**表级 CHECK 钉死** ——
   **没有任何路径能把它写成「已确认应付」**。
2. **质检扣量端口未接线 ⇒ 数量是 `NULL` 而不是 0**：`QualityDeductionPort` 未接线时，
   明细行 `qualityDeductionStatus='UNAVAILABLE'`、扣量数量 **NULL**（不是 0），
   capabilities `sections` 与详情 `basis.unavailableSources` 都点名。
   **写成 0 会被读成「没有质检扣量」，那是凭空造出来的结论。**
3. **一处与任务书前提不同的实测结论（重要）**：任务书说「8.2.1.1 收货单已交付 ⇒ 收货来源真实可读」。
   **实查不成立** —— warehouse 的 `requireSourceAvailable` 只放行 `TRANSFER`
   （6.3.1 采购单未交付 ⇒ PURCHASE 来源建不出单），且收货行**没有单价与币种**，即便能建也算不出应收金额。
   ⇒ 改用 **6.3.2.2 到货流水**（trade 本库、已交付、数量 + 单价 + 币种齐全）作基数，
   warehouse 收货单号只作来源快照，并在 capabilities 的 `related` 里如实写明。**未建任何影子表。**
4. **差异逐条可查**：差异不是聚合成一个差额总数，每条带差在哪个数量 / 金额 / 哪一行，可挂起可解决。
5. **币种与汇率**：跨币种汇总带汇率快照，**不按 1:1 折算**。

---

## 模块 6.5.2.1 采购分析

**这个功能做什么**：采购侧报表 —— 采购金额趋势、供应商集中度与贡献、采购价格波动、
到货准时率、采购成本占销售额比，以及按类目 / 店铺维度的采购效率；每条指标都能下钻到具体单据。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V251__purchase_analytics.sql`
  — **3 张新表**，`tenant_id NOT NULL` + **ENABLE 与 FORCE** RLS + GRANT，**零 ALTER 既有表**。
- 端点：**14 个** `/trade/purchase-analytics`。
- 页面：`/purchase/analytics`（真实工作区 + 纯函数模型，9 例单测）。
- 测试：**19 例**（`PurchaseAnalyticsIntegrationTest` 14 + `PurchaseAnalyticsTenancyIntegrationTest` 5）；
  全量 allowlist 回归 **125 文件 / 724 例**全绿。

**关键设计决策**：

1. **采购成本占销售额比：分母拿不到就「算不出」，不造假比率**。
   分子（采购成本）照算并按币种返回；分母（销售额）来自尚未落地的 13 利润与经营分析
   ⇒ `available=false` + `ownerModule=13 利润与经营分析`，
   前端显式显示归谁管与影响，13 落地后端点直接亮起、前端不用改。
2. **口径写进返回结构**：集中度是 top-N 占比还是 HHI、准时率的分母是「承诺到货行」还是「收货行」、
   价格波动是环比还是同比 —— 口径写在响应里，不让读的人猜。
3. **多源读取都走授权只读端口**（catalog 的类目与汇率、warehouse 的收货单），
   **不跨服务 SQL / FK / 视图**（AGENTS.md 硬规则）；读不到显式标「不可用」，
   **绝不拿 0 或空集合冒充**。
4. **未接线项逐条点名**：13 利润与经营分析（分母）、6.4.2.1 质检单、6.3.1.x 采购单原单。

---

## 修复 —— 5.5.1.1 价格历史：把「依赖墙上时钟时刻」的断言改成时刻无关

**为什么有这条修复**：batch-47 的 L2 红了 1 例，失败用例是
`PriceHistoryIntegrationTest.时间轴按维度真筛选且真分页真排序`。
调度者已取证该失败**在本批合并前的提交 `dd607c5b` 上同样复现**，
且失败与否是**本机墙上时钟时刻**的函数 ⇒ 与本批车道无关，是 5.5.1.1 的潜伏缺陷。
完整取证链见 `2026-10-21-batch-47-consignment-pricing-request-certification.md` 的 L2 一节。

**根因一句话**：测试用 `LocalDate.now().minusDays(9)` 当查询窗口，服务端把 `LocalDate`
换算成 **UTC 零点**（半开区间 `[from, to+1)`），而被断言的记录落在 `now − 8 天` ——
「A2 属于（now−9）那一日」这个期望**只在 JVM 本地时刻 < 08:00 时成立**
（本地 07:0x 时 now 的 UTC 值落在前一日 23:0x，A2 才落进 UTC 日界）。

**修法**：**只改测试，不改服务端的 UTC 日界语义** ——
后者是已交付模块的确定性的 API 口径，改它等于改已交付契约；错的是测试的期望。

具体改法（`PriceHistoryIntegrationTest.java`，**生产代码零改动、零迁移**）：

| 位置 | 改法 |
| --- | --- |
| `:145-160` | 新增私有辅助 `changesInWindow(skuId, from, to)`，把窗口语义写进 javadoc |
| `:370-393` | 种子重锚：`windowDay = LocalDate.now(ZoneOffset.UTC).minusDays(9)`；A1 = 该日前一天零点、A2 = 该日**正午 UTC**、A3 = 该日次日零点（= 窗口**排他上界**） |
| `:400-406` | **新增 3 条可执行口径不变量**：A2 落在 `[from, to+1)` 内 / A1 在 from 之前 / A3 恰等于排他上界 |
| `:441-458` | 窗口断言 **1 条 ⇒ 4 条**（窗口内恰好 1 条且逐字等于种进去的生效时刻；前一天窗口恰好 A1；次日窗口恰好 A3；空窗口命中 0 条） |
| `:475-479` | 分页断言加到「页内容逐条为 A3/A2 与 A1」，两页 id 互不相交 |

**断言强度只增不减**：窗口内 1 条 + 窗口外逐日逐条 + 空窗口 0 条 + 分页逐条内容 + 排序升倒序，
按 SKU / 来源 / 店铺筛与非法排序报错全部原样保留。

**双份证明**（都是实测）：

1. **四时区全绿**（`TZ` 实测确实改变 JVM 默认时区，`America/New_York` 下 `LocalDate.now()` 甚至落到前一日，
   这使四时区跑法成为**真的**时刻无关证明而不是摆设）：
   ```
   TZ=UTC                → Tests run: 22, Failures: 0, Errors: 0  BUILD SUCCESS  EXIT=0
   TZ=Asia/Shanghai      → Tests run: 22, Failures: 0, Errors: 0  BUILD SUCCESS  EXIT=0
   TZ=America/New_York   → Tests run: 22, Failures: 0, Errors: 0  BUILD SUCCESS  EXIT=0
   TZ=Pacific/Kiritimati → Tests run: 22, Failures: 0, Errors: 0  BUILD SUCCESS  EXIT=0
   ```
2. **变异测试（证明断言不空转）**：把 A2 种子改回墙钟相对值 ⇒ 该用例**立即 FAIL**
   （新的口径不变量先红），回滚后复跑绿。

**同类断言审计**：审了本文件里全部 `LocalDate.now()` / `OffsetDateTime.now()` ——
**只有这一处是「相对种子 + 相对窗口」的同类缺陷**，已修；
其余（`:149/:172/:211/:256/:339/:478/:629/:668`）只作种子、查询不带时间窗口 ⇒ 期望值不随墙上时刻漂移；
`:507-508`、`:764-765` 是销量端口的 503 断言（`UnwiredSalesFactPort` 无条件抛错）⇒ 非同类。

**归属**：模块 5.5.1.1（catalog），分支 `fix/5-5-1-1-price-history-window`，**无迁移**，
两个原子提交 `2c77cb38`（测试修复）+ `051ee2ff`（台账证据格 + 会话日志）。

### ⚠️ 附带事故：车道误跑 `spotless:apply`，把整仓 146 个文件重排版

修复车道在开工时跑过一次 `spotless:apply`，造成 **146 个文件被改**
（145 个被 google-java-format 重排 javadoc，**含生产代码**）。
调度者发现后 steer 回滚，最终交付态 `git status --short` = **空**，
**零字节进入 commit**。

补充事实（本轮新取证，写给 backlog）：本仓 `spotless` 配置（gjf 1.22.0 默认档）与
**已提交的 145 个 Java 文件基线不一致** —— `spotless:apply` 会整仓重排，
而 `spotless:check` 只报 `SkuCostCompositionRulesTest.java` 一个文件
⇒ **检查与修复行为不一致**。这解释了为什么「跑一下格式化器」会造成这种规模的事故。
已记入 `docs/backlog/2026-10-21-spotless-apply-mass-reformat-hazard.md`。

---

## L2 第二次全量验证（09:26 → 10:03）：后端红 6 例 ⇒ 又一处潜伏缺陷

### 结果

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `cd backend && ./mvnw verify` | **EXIT=1** —— trade 模块 `OrderChangeCancelIntegrationTest` 40 例中 **3 failures + 3 errors**；reactor 在 trade 中止（模块 8 未跑到） |
| 类型 | `pnpm typecheck` | ✅ EXIT=0 |
| Lint | `pnpm lint` | ✅ EXIT=0 |
| 单测 | `pnpm test` | ✅ EXIT=0 |
| 构建 | `pnpm --filter @starcon/web build` | ✅ EXIT=0 |
| 台账 | `node tools/module-progress/sync.mjs --check` | ✅ EXIT=0（台账与蓝图一致） |

**按协议：L2 未全绿 ⇒ 本轮不 push。**（第一次 L2 的红是 5.5.1.1 那处墙钟断言，已修；第二次的红是**另一处**潜伏缺陷，见下。）

### 红灯在哪个用例

`com.starcon.trade.OrderChangeCancelIntegrationTest`（模块 **9.1.1.3 订单变更与取消**，V119 迁移，**不在本批 7 条车道的范围内**）。

6 个失败用例全部依赖同一个夹具：先往 `order_platform_change_rule` 插一条「减少数量」规则。报错统一是
`400 没有登记「减少数量」的可变更规则` —— 服务层 `OrderChangeCancelService.evaluateRule` 判定「**查不到规则**」。

该类 40 例里**只有 6 例用到规则夹具**，而失败的正好是这 6 例 ⇒ 规则夹具整体失效，不是某个用例的逻辑问题。

### 归因链（五条，全部实测）

1. **文件级零接触**：`git diff --name-only dd607c5b 1755c23b` 对 `OrderChangeCancel*` / `order_platform_change_rule` **零命中**。本批对 trade 的改动只有 V246/V249/V250/V251 四个**只建新表**的迁移 + 三个新模块的类；迁移里的 RLS/GRANT 循环作用于**各自新表的显式清单**，不碰既有表。
2. **基线绿**：在合并前基线 `dd607c5b` 上单独跑该类 ⇒ `Tests run: 40, Failures: 0, Errors: 0`（BUILD SUCCESS，22 秒）。
3. **HEAD 绿**：在 `1755c23b` 上单独跑该类 ⇒ 40/40 绿；跑**完整 trade 模块**（88+ 个测试类）⇒ 该类仍 40/40 绿。
4. **DB 状态直查**：失败现场用超管连接查 `order_platform_change_rule` ⇒ **规则行确实存在**，且 `platform_code=AMAZON` / `shop_code=*` / `change_kind=QUANTITY_DECREASE` / `effective_to=NULL` **与查询条件逐项一致**；`pg_policies` 显示 `isolation` 策略正常、`relrowsecurity=t` `relforcerowsecurity=t` ⇒ 行**不是被 RLS 挡掉的**，是被 WHERE 里的 `now()` 窗口排除。
5. **等价注入复现（决定性）**：把夹具种子的 `OffsetDateTime.now()` 改成 `OffsetDateTime.now().plusSeconds(1)`（**等价于「DB 时钟比宿主慢 1 秒」**）⇒ 单跑该类**复现出与 L2 一模一样的 6 例红**：同样 3 failures + 3 errors、同样 6 个用例名、同样 `没有登记「减少数量」` 报错文本。

### 根因一句话

**测试用 JVM 墙钟写规则的 `effective_from`，服务端用 DB 时钟 `now()`（= `transaction_timestamp()`）判定「规则是否在生效窗口内」** ——
这是**跨时钟域**比较，只有「两个时钟恰好同步」才成立，而健康态余量只有**插入到查询之间的约 50ms**。

L2 全量验证时机器上有 **43 个 Testcontainers PG 容器**在跑、load 约 5/10 核，Docker 虚拟机的时钟相对宿主滞后数百毫秒
⇒ 刚种下的规则被判成「不在生效窗口内」⇒ 6 例红。**与本批车道无关，是潜伏缺陷**（与本 tick 5.5.1.1 那处同族：断言依赖墙上时钟）。

> 健康态偏差实测（`extract(epoch from clock_timestamp())` 对宿主 `date +%s.%N`）：DB 侧在 ±20ms 内且略快 ⇒ 余量确实只有几十毫秒量级。

### 修法（只改测试，不改生产代码、零迁移）

与本 tick 5.5.1.1 的修法同族：

- 把种子的 `effective_from` 锚到**同一个 DB 时钟**（在种规则的那个事务里 `select now()`，并留出余量），让「种下去的时刻」与「查询比较的时刻」落在**同一时钟域**；
- **服务端的 `now()` 语义不动**：「规则按 DB 当前时刻是否生效」是已交付模块的 API 口径，改它等于改已交付契约；错的是测试把时刻写在了另一个时钟域；
- 把「生效窗口判定本身有效」写成**可执行不变量**（断言种进去的 `effective_from` ≤ 查询时刻的 DB `now()`）⇒ 将来谁把种子改回 JVM 墙钟，断言立刻红；
- **变异测试**：种子改成「DB 时钟 + 1 小时」（一条尚未生效的规则）⇒ 必须红；改回「DB 时钟 − 1 分钟」⇒ 必须绿 ⇒ 证明窗口判定没被改空转。

修复车道：`fix/9-1-1-3-rule-effective-window`（worktree `.worktrees/9.1.1.3-fix`，基线 `1755c23b`，`migrationVersion=none`，无迁移）。

**修复车道的验证（全部真实输出）**：

| 项 | 结果 |
| --- | --- |
| 目标类 | `Tests run: 41, Failures: 0, Errors: 0` · BUILD SUCCESS（**比原来多 1 例** = 新增的不变量用例） |
| 变异测试 | 种子改成「DB 时钟 **+ 1 小时**」（尚未生效的规则）⇒ `Tests run: 41, Failures: 4, Errors: 3` · BUILD FAILURE —— 红的正是**原来那 6 例**（同样用例名、同样报错文本）**＋ 新增的不变量用例** = 7 例红；改回「DB 时钟 − 1 分钟」⇒ 41/41 绿 |
| 时钟域无关性 | `TZ=UTC` / `TZ=Asia/Shanghai` / `TZ=America/New_York` **各** 41/41 绿 |
| 回归 | `./mvnw -pl services/trade -am test` ⇒ `Tests run: 1769, Failures: 0, Errors: 0`（94 个测试类）· BUILD SUCCESS ⇒ 没有别的类被带红 |
| 编译 | `./mvnw -pl services/trade -am compile` EXIT=0；提交后 `git status --short` 为空 |

**改法细节**（唯一被改的源文件 `OrderChangeCancelIntegrationTest.java`）：
种子 `effective_from` 改成**由 DB 自己算**的 `(now() - interval '1 minute')`（同一时钟域 + 1 分钟余量，贴合「刚登记的规则就是已生效的」语义）；
`createRule`/`updateRule` 的 `effectiveFrom` 显式给 DB 时钟值（**服务层 default 是 JVM 墙钟**，且 `updateRule` 会用它**覆写** `effective_from`）；
新增 1 例可执行不变量（种下去的 `effective_from` ≤ 查询时刻 DB `now()`、且与 DB `now()` 间隔 ≥ 30 秒 ⇒ 改回 JVM 墙钟必红；外加按服务端同一套 WHERE 能命中刚种下的规则）。
**原有 40 例断言一条未削弱、未删除、未放宽。**

**⚠️ 未在范围内（生产侧同类写入口仍在）**：`OrderChangeCancelService.createRule` / `updateRule` 在 `effectiveFrom == null` 时仍用
**JVM 墙钟**写 `effective_from`，而窗口判定在 DB `now()` 上 ⇒ **真实 API 调用**建出的规则同样存在「刚登记被判成还没生效」的窗口。
本轮明令只改测试，故只把测试侧显式值钉到 DB 时钟；生产侧这条默认值**建议下一轮单独派车道**收口到 DB 时钟（或注入 `Clock`），**零迁移**。

---

## 16.4.2.1 品牌授权：已落地但**本轮不并入**

车道 `feat/16-4-2-1`（channel / V248 / head `02123ff1`，4 个原子提交，state=ready，工作区干净）在本 tick 末尾落地。
**本轮刻意不并入**：并进去就要再跑一次全量 L2，而本 tick 已经因为两处潜伏缺陷各跑过一次；
下一轮并入可与后续车道同批，摊薄 L2 成本。

已做**非破坏性合并预检**：`git merge-tree --write-tree HEAD 02123ff1` ⇒ **零冲突**（exit 0），台账按模块 ID 求并集、契约纯增量。
该车道的 api-proxy 插入块（52 行、0 删除）落在锚点 `// 15.2.2.1 Request a Review…` 之前，与本批 7 条车道的插入点两两间隔 ≥52 行。

---

## 涉及文件

- 本批 3 条车道 + 2 条修复车道（以实际合并 diff 为准）
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）
- `docs/sessions/2026-10/2026-10-21-batch-48-*.md` —— 本文件

## 关联

- `tools/module-progress/PIPELINE.md` —— 流水线协议（组批协议 / 三层验证 / 收尾三件套）
- `docs/sessions/2026-10/2026-10-21-batch-47-consignment-pricing-request-certification.md` —— 同一 tick 的前半程（batch-47）
- `docs/sessions/2026-10/2026-10-21-quality-inspection-order.md` —— 6.4.2.1 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-purchase-reconciliation.md` —— 6.5.1.1 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-purchase-analytics.md` —— 6.5.2.1 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-price-history-clock-window-fix.md` —— 5.5.1.1 修复车道会话日志
- `docs/sessions/2026-10/2026-10-21-order-change-rule-clock-domain-fix.md` —— 9.1.1.3 修复车道会话日志（规则生效窗口的跨时钟域缺陷）
