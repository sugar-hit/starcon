# 6.3.2.2 到货与逾期（阶段 D6，L3 锚点 6.3.2）

- **日期**：2026-10-21
- **车道**：`feat/6-3-2-2`（worktree `integration/.worktrees/6.3.2.2`）
- **蓝图原文**（`docs/product/capability-blueprint/06-purchase-and-supply.md` 192-197 行）：
  > 职责：跟踪采购单的到货状态：已发货（在途）、部分到货、全部到货、逾期未到、取消。逾期自动预警并给出处置建议（催货/改供应商/调库存/延期售卖）。
  > 输入：8 仓储的收货事件；跟单记录；物流在途状态（10）。
  > 输出：到货状态 + 逾期预警 + 处置建议。
  > 关系：上游 = 8 仓储作业、10 物流；下游 = 6.2.1.1 断货风险、1 工作台待办。**部分到货必须立即更新已收数量并触发应付部分确认**，否则采购与财务三方数据对不上。

## 决策摘要

1. **本模块拥有「到货跟踪」这份事实**。蓝图输入句的三个上游逐一查证过：
   - 8.2.1.1 收货单 / 8.2.1.2 收货差异 —— 已交付，但在 **warehouse 独立库** ⇒ 授权只读端口 + 本地逻辑引用与快照。
   - 10.4.1.2 在途跟踪 —— 已交付，**也在 warehouse 独立库**（头程发运单 10.4.1.1 与在途跟踪 10.4.1.2 都归 warehouse 主责，不是任务书里写的 channel）⇒ 同上。
   - 6.3.2.1 跟单 / 6.3.1.x 采购单 —— **未交付** ⇒ 逻辑引用（采购单号 / 来源单据号 / 供应商标识），**不建指向不存在的表的 FK**。
   - 12 财务 —— **finance 服务不存在**（backend/services/ 下只有 identity / catalog / channel / warehouse / trade / crm）⇒ 应付部分确认落成 **trade 侧自有事实记录**。

2. **加粗句落成数据库不变量，不是应用层约定**。这是本模块最关键的一条：
   - 守卫①：已收数量**永远**由 DB 从到货流水重算，表级 CHECK 再兜一道（已收+未收=可收）⇒ 本模块没有「直接写已收数量」的入口。
   - 守卫③：插入一条到货流水 ⇒ **同一事务内** DB 更新头的数量与状态、**按「单价快照 × 本次到货数量」落一笔应付部分确认**、追加留痕 ⇒「登记了到货却没有应付部分确认」在本模块**没有代码路径**。
   - 守卫④：应付确认的金额/币种/单价/数量全部由 DB 取，调用方传不进来。

3. **「已发货但在途」不算逾期是硬规则**，两处同时表达：DB 守卫① 显式拒绝 `IN_TRANSIT → OVERDUE` 并写明理由（还在路上，判逾期会误伤）；Java 枚举 `canTransitionTo` 的白名单同样不包含它。在途单的逾期天数恒为 0。

4. **逾期用数据库时钟判**。判定时刻取 DB 的 `now()` 并逐字返回（`evaluatedAt`）—— 跨时钟域偏差会把没逾期的单子判成逾期。日历日口径：**承诺日当天不算逾期，次日 00:00 起算**（UTC 日历日，不跟会话时区走）。逾期落库是幂等扫描，**刻意不 bump `version`**（逾期是时间推动的系统判定，不是用户编辑，bump 了会让客户端的 expectedVersion 无故失效）。

5. **处置建议的依据来自事实**。四类建议（催货/改供应商/调库存/延期售卖）各自独立命中，依据由确定性规则从事实拼出（逾期天数、缺口数量、已收比例、在途滞留天数、该供应商在本租户下的逾期单数与累计逾期天数），人话 `basis_note` + 数字 `basis_facts`（jsonb）双份。同事实同依据，不是写死的一段话。

6. **在途滞留另立一类预警**（`alertType=IN_TRANSIT_STALLED`）。逾期与滞留两个口径刻意不混：滞留超 7 天进预警看板，但状态仍是「已发货（在途）」、逾期天数仍是 0。

## 关键 trade-off

- **「在途不判逾期」的副作用**：一票在途 30 天的货不会被标成逾期。取舍是宁可漏报也不误报（误报会让采购员天天对着假逾期清单），改由「在途滞留预警」单独兜住，代价是看板要同时承载两类预警、UI 与 API 都要多一个 `alertType` 维度。**如果以后觉得漏报更痛，改动点是守卫① 的白名单 + `EFFECTIVE_STATUS` 表达式，不是处置建议层。**
- **在途滞留天数取「最后一个轨迹节点距今几天」而不是 ETA 口径**：ETA 口径回答的是「按计划还要几天到」，滞留口径回答的是「多久没进展了」。催货建议的依据必须是后者，否则依据就是错的 —— 所以端口要取详情（节点事件流只在详情里），不能只取列表行。
- **逾期落库放在读取路径上做幂等扫描**（而不是定时任务或写路径）：本仓 trade 服务没有调度器，且读取路径刷新能让「看板永远不陈旧」。代价是 GET 会写库（只改 `arrival_status` 与 `updated_at`），并且必须保持幂等（`WHERE arrival_status IN (...)` 保证并发下第二个请求匹配 0 行）。
- **处置建议的「依据」每次读都重算，「处理状态」落库**：`basis_note`/`basis_facts` 是派生结果（事实变了依据就该变），`disposition_status`（OPEN/ADOPTED/DISMISSED）是人的动作，必须留住。所以 API 返回的是「派生依据 + 已落库的处理状态」的合并行。
- **不提供「直接写已收数量」与「直接置为已确认应付」的端点**：已收数量由 DB 重算、应付生效由财务确认驱动，任何旁路都会让「采购与财务三方数据对不上」那句话重新变成口号。

## 文件清单

### 迁移（trade 服务，V235）

- `backend/services/trade/src/main/resources/db/migration/V235__purchase_arrival_tracking.sql` — 五张新表 + 五个守卫触发器 + 索引 + 序列 + RLS(FORCE) + GRANT/REVOKE。**零 ALTER 任何既有表**。

### 后端

- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseArrivalModels.java` — 传输模型 + 六个枚举（含显式状态机 `canTransitionTo` / `transitionBlockedReason`）。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseArrivalService.java` — jOOQ 显式 SQL 的服务层（列表 / 详情 / 建单 / 发货 / 到货 / 取消 / 在途同步 / 逾期看板 / 财务确认与驳回 / 建议处理 + 逾期落库 + 建议推导引擎）。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseArrivalController.java` — 12 个端点，命名空间 `/trade/purchase-arrivals`。
- `backend/services/trade/src/main/java/com/starcon/trade/WarehouseReceiptQueryPort.java` — 8.2.1.1 收货单授权只读端口。
- `backend/services/trade/src/main/java/com/starcon/trade/FirstMileTrackingQueryPort.java` — 10.4.1.2 在途跟踪授权只读端口。

### 测试

- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseArrivalIntegrationTest.java` — 22 例。
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseArrivalTenancyIntegrationTest.java` — 3 例（多租户三断言）。
- `apps/web/src/components/purchase/purchase-arrival-model.test.ts` — 27 例。

### 前端

- `apps/web/src/app/purchase/arrivals/page.tsx`
- `apps/web/src/components/purchase/purchase-arrival-workspace.tsx`
- `apps/web/src/components/purchase/purchase-arrival-model.ts`
- `apps/web/src/lib/api-proxy.ts` — **新增一条独立 `services[]` 元素**（首段 `purchase-arrivals`），插在第 3479 行（9.3.3.1 履约请求元素结尾）之后、第 3480 行（9.3.1.2 合单元素开头）之前。既有条目**一字未动**（`git diff` 42 增 0 删，`grep -c "prefix: '"` = 111）。

### 契约与台账

- `packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json` — 由 `tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成（trade.ts +21 类型，openapi +11 路径，**0 路径 / 0 schema 被删**）。
- `docs/product/capability-blueprint/module-progress.md` — 只改 6.3.2.2 那一行的「状态」「证据」两格。

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q compile
cd .. && pnpm typecheck

# L1 模块闸
cd backend && ./mvnw -pl services/trade -am test \
  -Dtest='PurchaseArrival*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# → Tests run: 25, Failures: 0, Errors: 0

# 前端
pnpm vitest run apps/web/src/components/purchase/purchase-arrival-model.test.ts   # 27 例
pnpm vitest run apps/web/src/app/api/trade                                        # 45 文件 238 例（api-proxy 全量 allowlist 回归）
```

## 后续待办

1. **6.3.1.x 采购单与 6.3.2.1 跟单交付后**，把本模块的逻辑引用升级成真引用：采购单表出现后加跨服务端口（catalog/trade 同库则可加 CHECK），跟单交付后 `promise_source` 的快照来源要接上跟单记录。**在此之前不要建 FK**（指向不存在的表会炸）。
2. **12 财务（finance 服务）落地后**，应付部分确认的 `PENDING_FINANCE_CONFIRM → CONFIRMED` 应经**授权 API / event** 衔接；本仓当前的「财务确认」是 trade 侧的独立动作 + 独立权限，落地后要把这条路径换成跨服务调用。
3. **warehouse 侧 8.2.1.1 的 `PURCHASE` 来源仍未开放**（它的能力声明写明「6 采购与供应尚未交付」）。本模块交付的是 6.3.2.2 到货跟踪、**不是 6.3.1.x 采购单** ⇒ 那条限制依然成立。等 6.3.1.x 落地后，仓库侧才可能接受采购来源的收货单，届时本模块的 `warehouse_receipt_code` 关联可以从「人工填报 + 授权核对」升级为「仓库侧直接挂采购单」。
4. **6.2.1.1 断货风险交付后**，「调库存」「延期售卖」两条建议的依据可以从「本单缺口与已收比例」升级为真实断货风险结论 —— 目前的依据里已写明这条限制。
5. **1 工作台待办**未交付：逾期预警目前只在 `/purchase/arrivals` 的看板里，未推送到工作台待办。
6. `trade.purchase.read` / `trade.purchase.update` / `trade.purchase.finance` 三个权限码**尚未在 identity 的 `permission` 表里 seed**（与本仓其它 trade 模块如 `trade.processing.read` / `trade.aftersales.read` 同一状况）。等 identity 侧补一批 trade 权限种子时一并加入。
