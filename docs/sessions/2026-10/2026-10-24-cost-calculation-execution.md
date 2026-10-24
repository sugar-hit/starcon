# 2026-10-24: 12.4.1.2 成本计算执行（阶段 D6，L3 锚点 12.4.1）

> 车道：`feat/12-4-1-2`（worktree `.worktrees/12.4.1.2`），基线 `bee48ef4`（集成头 `integration/module-dev`）。
> 蓝图：`docs/product/capability-blueprint/12-finance.md` → 12.4.1.2「成本计算执行」。
> 同批并行车道（各自独立 `services[]` 元素 + 独立 Flyway 版本）：
> 12.5.1.2 会计期间（V359）· 12.5.2.2 财务报表（V360）· 12.6.1.1 税率配置（V361）。
> 本车道占用 **V358**，api-proxy 插入锚点 = **数组最开头**，首 token `cost-calculation`。

## 摘要

12.4.1.2 把「这段时间里卖出去的货，按当时生效的计价方法应该结转多少成本」**算出来并落成账**：
读 warehouse 的真实库存流水（只读）拿到出库量，读 catalog 的真实到货记录派生的成本层，
按 12.4.1.1 的计价方法消耗成本层，产出**成本层**（按批次扣减的口径）与**成本分录**（按出库记账的口径：应结转金额），
并支持**按期间重算**（另起批次，旧批次不被改写）与**两次批次之间的差异对比**。

关键边界落在数据层而不是注释里：覆盖量 + 缺口量 = 出库量由触发器钉死；
有缺口 ⇒ 分录状态 INCOMPLETE 且**单价留空**；已完成的批次及其成本层 / 成本分录**不可改写**。

## 决策 / trade-off

### 1. 计价方法只有一个真相：读 12.4.1.1，不另抄一份

方法与生效版本从 `finance_cost_valuation_method` 读，本模块只在批次行记**快照**
（方法 + 版本号 + 配置行 ID）。

🔴 **刻意不按 `status='ACTIVE'` 过滤**：那只是「当前」那一版。
算历史期间要的是**当时生效**的那一版（早已被收口成 `SUPERSEDED`），
所以按生效区间定位（`effective_from <= at < effective_to`）并排除 `CANCELLED`。
按 `ACTIVE` 过滤会让「2026 上半年的成本」用 2026 下半年才切的方法重算 —— 那正是历史成本最不能被改写的地方。

请求里可以显式指定方法（覆盖），此时批次行会记下「本次**显式指定**了方法，与配置不同」。

### 2. 「已重算」在 12.4.1.1 那侧结构上写不出来 ⇒ 本模块接住

V355 的注释已写明「12.4.1.2 落地后由它自己把标记改掉」。
本模块在重算成功后把该范围生效版本的 `recalculation_status` 由 `PENDING` 改回 `NOT_REQUIRED`。

🔴 **只改已有列的取值，不改表结构** —— V355 的 CHECK 只允许 `NOT_REQUIRED` / `PENDING` 两个值，
本模块只用这两个值；且 V355 的历史不可改写触发器**不覆盖**这一列，改它是允许的。
「已重算」这个事实本身落在**本模块的批次**里 —— 那才是重算的落点。

### 3. 四种方法的口径与 12.4.1.1 试算**逐字一致**

试算页显示的数字和实际结转的数字必须对得上，否则试算就是废的。
所以 MOVING_AVERAGE 的单位成本 = 池内剩余层总金额 ÷ 剩余总数量（与 12.4.1.1 的
`movingAverageResult` 同式），而不是「时间加权」。

**已知取舍**：真正的移动加权平均需要把**期间内的入库**与出库按时间交织，
才算得出「那一刻的加权平均价」。本模块的成本层池是**一次性**从 catalog 的窗口成本构成建起来的，
所以 MA 是「窗口内可用成本层的加权平均」，**不是**逐笔时点加权平均。
做到逐笔时点加权需要 warehouse 侧给出入库分录、且两侧单位口径统一（catalog 给的是基础单位、
warehouse 流水给的是仓储单位），两个上游目前都不提供这个换算 —— 这是**上游缺口**，不是本模块偷懒，
已如实写进 `basis_note`。

### 4. 重算 = 另起批次，旧批次不被改写

旧批次是「当时那笔计算」的证据。就地改它，差异对比就变成「同一个批次跟自己比」，历史也不再是证据。
⇒ 新批次（ id）+ 重算链（`supersedes_run_id` / `superseded_by_run_id`），
「已完成的批次不可改写」「已完成批次的结果行不可改 / 不可删」由**触发器**钉死。

### 5. 差异对比**不落第四张表**

差异数不在任何一行里 —— 它是 Σ 分录。再存一份就是**第二个真相来源**，
两边对不上时反而不知道该信谁。⇒ 服务层从两批分录现算，逐 SKU 对比；
期间或币种不一致时在结论里**如实说明**（差额里含期间差异 / 币种不同不可直接解读）。

### 6. 数量账在 warehouse、价值账在 finance

出库量经只读端口 `GET /warehouse/inventory/ledger` + `/{transactionId}` 读（**只 GET**，
台账本身也被 warehouse 的 append-only 触发器与 REVOKE UPDATE,DELETE 双重锁死）。
集成测试直接断言：跑完一轮计算之后，对上游的**写调用次数为 0**。

### 7. 成本层窗口比期间宽 365 天

期初就有的货也会在期间内被卖掉，所以成本层的取数窗口 = 期间起点 − 365 天 ~ 期间止。
这一条写进 `basis_note`，避免半年后没人说得清口径。

## 文件清单

**迁移（finance）**
- `backend/services/finance/src/main/resources/db/migration/V358__finance_cost_calculation.sql`
  —— 三张新表 + RLS(FORCE) + GRANT + 三个触发器（覆盖量守恒 / 已完成批次不可改写 / 结果行不可改删）

**后端（finance）**
- `backend/services/finance/src/main/java/com/starcon/finance/CostCalculationModels.java` —— 传输模型 + 四个枚举
- `backend/services/finance/src/main/java/com/starcon/finance/CostCalculationFlowPort.java` —— warehouse 7.1.2.1 只读端口
- `backend/services/finance/src/main/java/com/starcon/finance/CostCalculationService.java` —— 计算 / 重算 / 差异对比 / 读模型
- `backend/services/finance/src/main/java/com/starcon/finance/CostCalculationController.java` —— 9 个端点
- `backend/services/finance/src/test/java/com/starcon/finance/CostCalculationIntegrationTest.java` —— 19 条业务断言
- `backend/services/finance/src/test/java/com/starcon/finance/CostCalculationTenancyIntegrationTest.java` —— 7 条租户隔离断言

**前端**
- `apps/web/src/components/finance/cost-calculation-model.ts` + `.test.ts`（15 条）
- `apps/web/src/components/finance/cost-calculation-workspace.tsx` —— 真实工作区
- `apps/web/src/app/finance/cost-calculation/page.tsx`
- `apps/web/src/app/api/finance/cost-calculation-allowlist.test.ts`（5 条）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**（数组最开头）

**共享产物**
- `packages/api-contracts/src/contracts/finance.ts`（+14 类型）· `packages/api-contracts/src/finance.openapi.json`（+8 路径）
  —— 由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成，**未手改**。

**台账**
- `docs/product/capability-blueprint/module-progress.md` —— 只改 12.4.1.2 那一行的「状态」「证据」两格

## 验证

- L0：`cd backend && ./mvnw -q compile` 通过；`pnpm typecheck` 通过（3 包全绿）；`pnpm lint` 通过（仅既有告警）
- L1：`./mvnw -pl services/finance -am test -Dtest='CostCalculation*'` ⇒ **26 条全绿**（19 + 7）
- 前端：`pnpm vitest run apps/web/src/components/finance/cost-calculation-model.test.ts` ⇒ 15 条绿
- 前端：`pnpm vitest run apps/web/src/app/api` ⇒ **230 文件 / 1300 例全绿**（api-proxy 是累积白名单，必须全量跑）
- 契约：`git diff` 逐 schema / 逐 path 比对 ⇒ **纯增量**（+14 schema、+8 path、0 删除、0 改动）

## 后续待办

1. ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**
   （四条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
2. **12.4.1.3 成本差异分析**（未交付）：本模块的「差异对比」是两次批次之间的逐 SKU 对比，
   **不是**差异分析报表。
3. **13 利润**（未交付）：本模块只产出应结转成本，利润自己去取。
4. **移动加权平均的时点口径**：若要做到真正的时点加权平均，
   需要 warehouse 侧给出入库分录 + 两侧单位口径统一（见 §3 的已知取舍）。
5. **上游读不到的降级策略**：目前 warehouse / catalog 任一不可达 ⇒ 整次计算 503 失败，
   不做「部分成功」。若将来要求部分成功，需另定「半截批次」的状态语义。
