# 6.6.2.2 转量产与建档（阶段 D6，L3 锚点 6.6.2）

- 日期：2026-10-21
- 车道：`feat/6-6-2-2`（worktree `integration/.worktrees/6.6.2.2`）
- 归属后端服务：**trade**（卷 06 后端主责服务 = trade + catalog（party），取列表第一项）
- 后端命名空间：`/trade/mass-productions`；前端路由：`/purchase/development/mass-production`
- 上游：6.6.2.1 试产单（同库只读）；下游：3 商品主数据（SKU 建档）/ 6 采购（6.2.1.2）/ 5 定价（5.3.1.1）

## 依赖自检结论（动手前 10 分钟）

- 上游 6.6.2.1 **已交付**（V264 八表 + `production_trial_mass_production_recommendation` 建议事实）。
- catalog **有 SKU 建档入口**：`POST /catalog/products/{id}/skus`（`SkuInput` = code/name/barcode/baseUnit/status/values/version，权限 `catalog.sku.create`，返回 SKU id）；
  报关属性走 `PUT /catalog/customs-attributes/skus/{skuId}`（`CustomsInput`，权限 `catalog.compliance.update`）。
- 6.2.1.2 补货建议**同库**，`ReplenishmentSuggestionService.compute(input, actorId, bearer)` 可调用。
- **无 pricing 服务**（`backend/services/` 只有 catalog/channel/crm/identity/trade/warehouse）⇒ 5.3.1.1 目标定价是**诚实缺口**，不 mock 算价。

## 决策摘要

1. **正式 SKU 的写入所有权在 catalog（3.1.2.1），本模块只落「建档请求事实」。**
   取证结论 = **有入口**（见上）。因此本模块**不建 SKU 表、不跨库写、不跨库 FK/视图**，
   只落「目标系统 + 目标键（SPU）+ 载荷快照 + 幂等键 + 状态机」的建档请求，
   再经 catalog 的**授权 API** 真提交，**SKU id 与编码从商品中心读回**（不是本服务编的）。

2. **🔴「结果未知（UNKNOWN）」是独立状态，且禁止重试。**
   catalog 的建档端点**不接受幂等键** ⇒ 超时/断连时无法判断建没建出来，
   重试会建出**第二个** SKU。所以 UNKNOWN 既不是 FAILED 也不是可重试态：
   页面显示「结果未知 · 不要重试」，**不提供重试按钮**，只提示先到商品中心核对该编码是否已存在。
   与之相对，「部分完成（PARTIAL）」= SKU **已经建成功**、只是报关属性没写进去 ⇒ 记 `target_sku_id`，
   可再提交**只补报关属性**，不会建第二个 SKU。

3. **建档失败/未知不抛异常。**
   抛异常会回滚事务，把「失败」这条事实一起抹掉。改成：把失败/未知**如实落库**，
   由控制器按**落库状态**映射 HTTP 码（CONFIRMED→200 / FAILED→400 / UNKNOWN→503 / PARTIAL→400），
   响应体在非 2xx 时也带着建档请求本身。

4. **6.2.1.2 采购需求：同库但只触发重算。**
   6.2.1.2 的起订额与交期是**人工维护的策略**，本模块不顶掉它 ⇒ 量产参数**进不去**它的计算。
   因此只调 `ReplenishmentSuggestionService.compute()` **触发重算**，
   **绝不 INSERT/UPDATE 它的表**，留痕如实标 `massProductionInputApplied=false`
   （与 6.3.2.1 的诚实口径同构）。

5. **5.3.1.1 价格模型未交付 ⇒ 交接留痕标缺口，不 mock 算价。**
   8.4.1 组装单在 warehouse **独立库**，同理只存逻辑引用 + 展示快照，不建组装单。

6. **目标成本取不到就是取不到（不是 0）。**
   同币种内才可比；跨币种**不折算**（5.2.2.1 汇率查询未交付）⇒ 标 `comparable=false` 并写明原因。
   目标成本按币种分组统计，**绝不跨币种加成一个总数**。

7. **决策必须举证。**
   理由必填；**上游建议 NO_GO 而本计划决策 GO 时必须写覆盖理由**，且来源只能是 `MANUAL_OVERRIDE`
   （DB CHECK 与服务层各挡一道）。决策**落库后不可改写**（DB 触发器钉死）。

8. **状态机服务层 + 数据库层两处同构钉死。**
   `MassProductionRules.allowedTargets` 与 V268 的 `guard_mass_production_plan_status()` 同一张 CASE。
   DB 守卫另钉四条：「迁到已决策必须有迁移事件」「BOM_FROZEN 需组件行非空」「SKU_FILED 需已 CONFIRMED 的建档」「RELEASED 需已定版」。

9. **BOM 与需求量复用上游 6.6.2.1 的只读端口，不重算。**
   `ProductionTrialBomPort`（BOM 候选/详情）与 `BomVersionPort.requirement()` 的 R6/R7 **原样搬运**。
   **不动 6.6.2.1 的文件**（那是别的车道的），多车道并入同一文件必冲突。

## 关键 trade-off

- **建档请求自建表 vs 直接写 catalog。**
  直接写 catalog 违反「每张事实表只有一个写入所有者 + 禁止跨库写」；
  自建表则把「本模块建了 SKU」和「商品中心建了 SKU」混成一件事。
  选**建档请求事实 + 授权 API 真调**，代价是多一层映射与状态机，
  收益是「SKU 到底建没建成功」这件事有据可查，且失败/未知的语义能表达。

- **建档失败用 HTTP 4xx/5xx 还是 200 + 状态字段。**
  用 200 会让调用方以为成功 ⇒ 改用「按落库状态映射状态码」。
  代价是前端要处理「非 2xx 但 body 里有事实」，收益是重试逻辑可以按状态分档。

- **UNKNOWN 禁止重试 vs 允许重试。**
  允许重试更好用，但没有幂等键 ⇒ 重试 = 可能建出第二个 SKU。
  选**禁止重试 + 引导去商品中心核对**，宁可让人多走一步，也不制造重复 SKU。

- **下游交接独立事务。**
  抄 6.3.2.1 的两段式：`access.run` 失败后再开新事务记 FAILED。
  交接失败**不回滚「已下达」** —— 计划已经下达了，不能因为交接失败把它抹掉。

- **api-proxy 用新增独立 `services[]` 元素，而不是往既有 trade 长正则里并分支。**
  那一支是超长单行正则，多车道并进去 git 必然判冲突（结构性，retry 不会自愈）。
  插入锚点固定在既有 `trial-productions`（6.6.2.1，直接上游）元素**之后**，
  与「`prefix: 'trade/'` 之前」「services[] 最末尾」这两个常见锚点都隔开 ≥ 1700 行。

- **契约的可空字段按上游 6.6.2.1 同一惯例处理。**
  Java 侧标 `@Schema(nullable=true)` → codegen 出 `| null`。
  这不是装饰：`MassProductionSkuFilingRow.targetSkuId` 等字段**运行时真的会是 null**，
  契约若说成非空，前端就会在 `detail.skuFiling.attributes` 上炸掉。
  代价是 `MassProductionSkuFilingRow` 整体带 `| null`（与上游 `ProductionTrialResultRow` 同构），
  收益是契约与运行时一致。

## 文件清单

**新增（后端）**

- `backend/services/trade/src/main/resources/db/migration/V268__mass_production_and_sku_filing.sql` —— 八张新表 + 守卫触发器 + RLS + GRANT
- `backend/services/trade/src/main/java/com/starcon/trade/MassProductionModels.java` —— 传输模型（记录 / 枚举 / 入参）
- `backend/services/trade/src/main/java/com/starcon/trade/MassProductionRules.java` —— 纯规则（状态机 / 决策举证 / 建档重试纪律 / 属性值形状 / 币种金额数量 / 成本偏差率）
- `backend/services/trade/src/main/java/com/starcon/trade/MassProductionCatalogPort.java` —— catalog 出站端口（`CreateSkuOutcome` / `SaveCustomsOutcome` 密封接口：Created / Rejected / Unavailable / **Unknown**）
- `backend/services/trade/src/main/java/com/starcon/trade/MassProductionService.java` —— jOOQ 显式 SQL 服务
- `backend/services/trade/src/main/java/com/starcon/trade/MassProductionController.java` —— 16 条路径，权限 `trade.mass-production.read/update`

**新增（测试）**

- `backend/services/trade/src/test/java/com/starcon/trade/MassProductionRulesTest.java` —— 26 例
- `backend/services/trade/src/test/java/com/starcon/trade/MassProductionIntegrationTest.java` —— 27 例
- `backend/services/trade/src/test/java/com/starcon/trade/MassProductionTenancyIntegrationTest.java` —— 3 例

**新增（前端）**

- `apps/web/src/components/purchase/mass-production-model.ts`
- `apps/web/src/components/purchase/mass-production-model.test.ts` —— 14 例
- `apps/web/src/components/purchase/mass-production-workspace.tsx`
- `apps/web/src/app/purchase/development/mass-production/page.tsx`
- `apps/web/src/app/api/trade/mass-production-allowlist.test.ts` —— 7 例（白名单**双向**断言）

**改动**

- `apps/web/src/lib/api-proxy.ts` —— 新增一条 `mass-productions` `services[]` 元素（既有条目一字未动）
- `packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json` —— codegen 产物（+29 个 DTO / +16 条路径）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.6.2.2 一行的「状态」「证据」两格

## 验证

- `cd backend && ./mvnw -q compile` → 退出 0
- `cd backend && ./mvnw -pl services/trade -am test -Dtest='MassProduction*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` → **56 例 0 失败 0 错误**（26 规则 + 27 集成 + 3 多租户；连跑两轮均绿）
  （同一条命令还跑出了 Flyway `Successfully applied 72 migrations to schema "public", now at version v268`）
- `pnpm typecheck` → 3/3 通过
- `pnpm lint` → 3/3 通过（**本车道零告警**；输出里的 6 条 warning 全在 `catalog/variant-combination-workspace.tsx`、
  `orders/pending-shipment-workspace.tsx`、`warehouse/goods-receipt.tsx`、`warehouse/putaway-task-workspace.tsx` 等既有文件上）
- `pnpm vitest run apps/web/src/components/purchase/mass-production-model.test.ts apps/web/src/app/api/trade/mass-production-allowlist.test.ts` → **21 例全绿**
- `pnpm vitest run apps/web/src/app/api/trade` → **64 文件 358 例全绿**（改了 api-proxy 必跑的全量 allowlist 回归，无兄弟车道误伤）
- **Testcontainers 容器**：测试类各自起一个 `postgres:16-alpine` 容器，surefire 串行 ⇒ **同时最多存活 1 个**
  （实测运行中 `docker ps -a --filter ancestor=postgres:16-alpine` = 1 个 `Up 5 seconds`）；
  运行结束后同一命令 = **0 个残留**，即**结束前已被 ryuk 回收**。
- **契约语义 diff**（脚本自查）：`paths added 16 / removed 0`、`schemas added 29 / removed 0`、
  `existing paths changed []`、`existing schemas changed []` ⇒ **纯增量**。
- **台账自查**：`grep -E '^\| 6\.6\.2\.2 ' module-progress.md | tr -cd '|' | wc -c` = **7**（证据格内零半角竖线）。

## 后续待办

- **5.3.1.1 目标定价（pricing 服务不存在）**：待其交付后才可能把量产参数真正带进价格模型；
  在此之前交接留痕恒为 `SKIPPED` + `available=false`，页面明写「不 mock 算价」。
- **6.2.1.2 的采购需求真正吃进量产参数**：目前只触发重算、量产参数进不去它的计算
  （它的起订额/交期是人工策略，本模块不顶掉）。若要真带入，需要 6.2.1.2 侧扩参数入口。
- **8.4.1 组装单下达**：warehouse 独立库，授权 API / event 未落地；
  现在只存逻辑引用 + 展示快照，不建组装单、不扣库存。
- **catalog 建档端点的幂等键**：当前端点不接受幂等键 ⇒ UNKNOWN 无法自动收敛；
  若 catalog 侧补上幂等键，本模块的 UNKNOWN 禁用重试策略可以放宽为「按幂等键重试」。
- **5.2.2.1 汇率查询**：待其交付后才可能做跨币种成本折算；在此之前跨币种**只分组不折算**。
