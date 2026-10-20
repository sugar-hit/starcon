# 2026-10-20: batch-36 集成头 L2 红 —— 契约重名与任务页标记

## 摘要

修复上一批（batch-36）并入集成头后 L2 全量验证跑出来的**两个**缺陷，基线为集成头
`integration/module-dev`（commit `0d53030e`），分支 `fix/batch-36-l2-green`。**不是新功能交付，
模块状态无变化，台账未动。**

- **D1 —— 跨服务同名 DTO 碰撞**（导致 `pnpm typecheck` / `next build` 红）：
  catalog（5.2.1.1 成本构成）与 channel（10.6.1.1 成本分析）各自定义了一个**结构不同**的
  `CostCapabilities`，`packages/api-contracts/src/index.ts` 的 `export *` 撞名 → TS2308。
  修法：**在 channel 侧改名** `CostCapabilities` → `LogisticsCostCapabilities`（后端 Java 改），
  重新生成 channel 的 OpenAPI spec，再跑 codegen 重生成契约。
- **D2 —— 任务页 implemented 标记没翻**（导致 `pnpm test` 红 2 例）：
  `/logistics/tracking` 已有 10.3.1.1 轨迹查询的真实页面，但 `task-pages.ts` 里仍标
  `implemented: false`，与真实文件系统不一致。修法：翻成 `true`，并把该条的
  label / description / fields / workflow 一并对齐成「轨迹查询」的真实内容。

## 决策 / 结论

1. **改名，不进 allowlist**。
   `tools/codegen/contract-collision-allowlist.json` 全仓从未接受过一次「结构不同」的跨服务
   重名（当前仍是 `{"allowed": []}`）。`tools/codegen/catalog-contracts.py` 的 `gate()` 对结构
   不同的重名**在写出任何文件之前**就非零退出，并点名「谁会凭空多出 / 谁独有字段会消失」。
   同类问题在 10.3.1.1 那条车道的处理也是**改名**（`TrackingEventRow` →
   `CarrierTrackingEventRow`），并明确「不写进 allowlist 接受覆盖」。本轮沿用同一条既有做法。

2. **改 channel 侧，不动 catalog 侧**。
   catalog 的 `CostCapabilities` 属于 5.2.1.1 **已并入**的成果，改它会波及另一条已交付车道；
   channel 的 `CostCapabilities` 属于 10.6.1.1 物流成本分析模块，同族命名已经是
   `LogisticsCostAnalysisController` / `LogisticsCostAnalysisModels` /
   `LogisticsCostAnalysisService` / `LogisticsCostParameterRow` / `LogisticsCostParameterPage`
   —— 改名为 `LogisticsCostCapabilities` 与同族一致，**只有这一个类型的名字变**。

3. **桶文件 `index.ts` 无需改动**。
   改名后全仓只剩 catalog 一家导出 `CostCapabilities`，`export *` 不再有歧义，**不需要消歧行** ——
   实测 `index.ts` 与基线**逐字节相同**。桶文件里原有的 `Dimension`（warehouse 与 trade
   结构完全相同 ⇒ 显式取 warehouse 那份）保持原样，未受影响。

4. **`contracts/channel.ts` 的类型集合差集恰好等于这次改名**。
   实测：改前 708 个类型 / 改后 708 个类型，`removed = {CostCapabilities}`、
   `added = {LogisticsCostCapabilities}`，无其它增减。

5. **`channel.openapi.json` 的大 diff 是路径重排噪音，不是内容变化**。
   springdoc 的 path 顺序随控制器注册顺序变，所以重新生成的快照里 `paths` 整块重排 ——
   这正是本仓既有车道提交的一贯形状（10.6.1.1 的并入提交 `0d53030e` 对同一个文件就是
   16526 行变更）。本次重生成的**路径顺序与 L2 跑出来的原始 spec 完全一致**（可复现）。

6. **D2 只翻标记会造成错标入口，所以文案一并对齐**。
   该条原 `label` 是「物流异常」、description / fields 描述的是**异常件**，而这个路径上的真实
   页面是 10.3.1.1 的**轨迹查询**（节点时间线 / 预计时效对比 / 异常节点标记 / 按承运商批量拉取）。
   只翻标记会让「物流」系统下多出一个「物流异常 → 实际是轨迹查询页」的错标入口。
   label / description / fields 直接取自页面自己的文案（`tracking-query-workspace.tsx` 的
   PageHeader 描述与轨迹清单表头），**没有凭空编**。

7. **workflow 也一并对齐**。
   原 workflow `['监测异常', '联系承运商', '追踪处理']` 描述的是**异常处置**，而该页**明确不做
   处置**（联系承运商 / 理赔 / 补发 / 改址归 10.3.1.2，未交付；异常只标记不处置）。留着它
   与留着旧 label 是同一类错标，故按真实页面的动作链改为
   `['同步运单集合', '批量拉取轨迹', '查时间线与时效对比']`（仍满足「workflow 恰好 3 步」的既有不变量）。

8. **菜单入口自动出现，不需要手写菜单条目**。
   `packages/ui/src/lib/nav-config.ts` 的 `NAV_DESTINATIONS` 是**派生**的
   （`SYSTEMS` → `system.items` → `item.children` → `IMPLEMENTED_TASK_PAGES.filter(domain === ...)`），
   所以翻 `implemented` 即自动出现在「物流」系统下。

## 涉及文件

改动（7 个 + 1 个新增会话日志）：

| 文件 | 改动 |
| --- | --- |
| `backend/services/channel/src/main/java/com/starcon/channel/LogisticsCostAnalysisModels.java` | record 声明改名 |
| `backend/services/channel/src/main/java/com/starcon/channel/LogisticsCostAnalysisService.java` | 返回类型 + `new` 改名 |
| `backend/services/channel/src/main/java/com/starcon/channel/LogisticsCostAnalysisController.java` | 返回类型改名 |
| `backend/services/channel/src/test/java/com/starcon/channel/LogisticsCostAnalysisIntegrationTest.java` | 测试局部变量类型改名 |
| `packages/api-contracts/src/channel.openapi.json` | codegen 重新生成（改名 + 路径重排） |
| `packages/api-contracts/src/contracts/channel.ts` | codegen 重新生成（改名） |
| `packages/ui/src/lib/task-pages.ts` | `/logistics/tracking` 一条：翻标记 + 对齐文案 |
| `docs/sessions/2026-10/2026-10-20-fix-batch36-contract-collision-and-nav-flag.md` | 本文件（新增） |

**刻意未改**：

- `packages/api-contracts/src/index.ts` —— 改名后无需消歧，与基线逐字节相同。
- `tools/codegen/contract-collision-allowlist.json` —— 保持 `{"allowed": []}`，未绕过碰撞。
- `packages/api-contracts/src/contracts/catalog.ts` —— catalog 侧的 `CostCapabilities` 原样保留。
- `packages/api-contracts/src/{identity,catalog,warehouse,crm}.openapi.json` 与
  `packages/api-contracts/src/contracts/{identity,catalog,warehouse,trade,crm}.ts` ——
  codegen 重生成后**逐字节未变**；`trade.openapi.json` 有变（见「后续待办」），已 `git checkout --` 还原。
- `docs/product/capability-blueprint/module-progress.md` —— 修复任务，模块状态无变化。

## 关键 trade-off / 讨论

- **「改 Java 改名」vs「在 TS 契约里手工消歧」** —— 选前者。契约是 codegen 生成物（手改即破坏
  契约），而 `export *` 撞名的正解是让两个服务不再同名，而不是在桶文件里挑一份暴露、
  另一份的独有字段在消费端悄悄消失。
- **「改 channel」vs「改 catalog」** —— 选 channel。catalog 侧是 5.2.1.1 已并入的交付物，
  改它会波及另一条车道的成果；channel 侧 10.6.1.1 与本车道同批，且改名后与 `LogisticsCost*`
  同族命名自洽。
- **「保留 channel.openapi.json 的整块重排」vs「手工把重排抹掉」** —— 选保留。手工编辑生成物
  会让下一次 codegen 又重排一遍，反而制造新的噪音与漂移；且既有车道提交（`0d53030e`）就是这个形状。
- **「只改 label/description/fields」vs「workflow 一并改」** —— 选一并改。旧 workflow 描述的
  异常处置被该页明确排除在外，属于同一处错标，留着会与已修正的 label 自相矛盾。

## 后续待办

1. **trade 的 OpenAPI 快照已陈旧（不在本车道范围，已还原）**。
   重新生成 `packages/api-contracts/src/trade.openapi.json` 会**新增一整个端点**
   `/trade/order-address-payment-check/policy`（及 schema `AddressPaymentPolicy`），
   而当前入库的快照里没有 —— 说明 trade 契约在上游端点合入后没有重新生成过。
   本车道按「不越界改别的模块生成物」已 `git checkout --` 还原，**建议在 trade 车道补一次重新生成**。
2. **契约漂移目前无人看守**。仓库里的 `sync --check` 是
   `tools/module-progress/sync.mjs --check`（**模块台账**同步检查），**不是**契约漂移检查 ——
   `packages/api-contracts/src/*.openapi.json` 与 `contracts/*.ts` 目前没有任何 CI 闸门保证与
   后端 spec 同步。这正是本次 D1 能溜到集成头的土壤，建议进 backlog（加一个
   `contracts:check`：重生成后 diff 非零即失败）。
3. `packages/api-contracts/src/channel.openapi.json` 入库版本的 path 顺序与当前代码生成的顺序
   不同（本次已随重新生成对齐到与 L2 原始 spec 一致的可复现顺序）。

## 关联

- `tools/codegen/catalog-contracts.py` —— `gate()` / `collisions()` / `render_index()`：碰撞判定与
  桶文件生成规则。
- `tools/codegen/contract-collision-allowlist.json` —— 保持 `{"allowed": []}`。
- `packages/ui/src/lib/nav-routes.test.ts` —— 「标记 ↔ 真实文件系统」守卫。
- `packages/ui/src/lib/nav-config.ts` —— `NAV_DESTINATIONS` 的派生逻辑。
- `docs/sessions/2026-10/2026-10-20-tracking-query.md` —— 10.3.1.1 轨迹查询的交付记录（D2 的页面来源）。
- `docs/sessions/2026-10/2026-10-20-logistics-cost-analysis.md` —— 10.6.1.1 成本分析的交付记录（D1 的 channel 侧来源）。
- `docs/sessions/2026-10/2026-10-20-sku-cost-composition.md` —— 5.2.1.1 成本构成的交付记录（D1 的 catalog 侧来源）。
