# 6.6.2.1 试产单（阶段 D6，L3 锚点 6.6.2）

- 日期：2026-10-21
- 车道：`feat/6-6-2-1`（worktree `integration/.worktrees/6.6.2.1`）
- 归属后端服务：**trade**（卷 06 后端主责服务 = trade + catalog（party），取列表第一项）
- 后端命名空间：`/trade/trial-productions`；前端路由：`/purchase/development/trials`

## 决策摘要

1. **「试产消耗真实组件库存，需走 8 加工单而非直接改库存」按加粗硬约束处理。**
   本模块**只产出组件领用需求**（组件清单 + 需求量 + 关联试产单 + 需求状态），
   **不建库存扣减表、不改 warehouse 库存、不提供「扣减」动作**。
   需求状态 `DRAFT → SUBMITTED → RELEASED` 只表示「交出去了」，
   `RELEASED` 记的是 8.4.1 组装单的**逻辑引用（uuid）+ 组装单号快照**（跨服务不建 FK、不跨库写）。
   能力声明如实标 `inventoryDeductionAvailable=false` + `assemblyOrderHandoffAvailable=false`。
   集成测试逐列断言需求表里**没有任何库存结存 / 已扣减 / 已领用列**。

2. **BOM 版本（catalog 独立库）走既有跨服务端口，不自己重算。**
   版本快照走本车道新增的 `ProductionTrialBomPort`（`GET /catalog/bom-versions/{id}`），
   组件需求量走**既有** `BomVersionPort.requirement()`（`GET /catalog/bom-versions/{id}/requirement`）。
   需求量**原样搬运 catalog 的 R6/R7**（`单位用量 × 产量 × (1 + 损耗率/100)`，6 位 HALF_UP，不取整）——
   本服务不复制算法，两处各实现一遍必然漂移，而漂移的后果是「按不同口径领料」。
   **新增自己的端口而不是改 `BomVersionPort`**：那是 6.4.1.1 的文件，多车道并入同一个文件必冲突。

3. **良率由数据库推导，客户端报不上来。**
   `production_trial_result` 上 BEFORE 触发器按 `合格 ÷ 产出` 算良率、按 `产出 − 合格` 算不良数量，
   客户端报的值一律被覆盖。输入模型里**根本没有良率字段** ⇒ `fail-on-unknown-properties` 直接 400。
   良率是**比例**（`numeric(19,6)`，0.95 = 95%），页面显示的百分数只是同一份值的显示格式。

4. **「试产成本 vs 量产目标成本」的口径写死在服务层。**
   一张试产单一个币种；**只在同一币种内可比**；跨币种按币种分组、**不做固定汇率折算**（5.2.2.1 未交付）；
   量产目标成本取不到就是 **NULL（不是 0）** ⇒ 概览与详情都标 `comparable=false` 并说明原因。
   概览的对比组 = 「该币种下**有量产目标成本的**那些试产单」，不拿不同集合相减。

5. **转量产建议是「建议事实」，不是 SKU 建档。**
   6.6.2.2 未开发 ⇒ 只落一条建议（结论 + 建议文本 + **生成条件** + 生成时实测快照），
   **不建 SKU 表、不调商品服务写接口**。
   「建议转量产」需要**举证**（良率达标 + 成本门槛达标，条件不达标直接 400 并报出实测值与门槛）；
   「暂不建议转量产」不需要举证（关键质检项不合格也足以否掉量产）。

6. **状态机服务层 + 数据库层两处钉死。**
   `ProductionTrialRules.allowedTargets` 与 V264 的 `guard_production_trial_order_status()` 同一张 CASE。
   三条 DB 守卫额外要求：**开产前领用需求必须全部提交**、**完成前必须有试产结果**、
   **完成前质检项必须全部判定完毕**。

## 关键 trade-off

- **服务层加前置检查 vs 只靠 DB 守卫。**
  只靠 DB 守卫时，「还有 2 条组件领用需求未提交」会变成 23514 约束冲突，被
  `ApiErrorHandler` 翻译成一句笼统的「数据与既有记录冲突」。本车道在服务层先挡一道给出可读 400，
  DB 守卫保留为第二道防线（集成测试仍直接改库验证守卫自己会挡）。代价是服务层多三段查询。

- **组件需求的「承接」用人工登记（release 端点）而不是自动事件。**
  8.4.1 的自动衔接需要授权 API / event，目前不存在。做成「提交后由调用方回填组装单引用」，
  与 6.6.1.2 的「样品物流快照（人工登记）」同一口径，并在能力声明里如实标不可用。

- **api-proxy 用新增独立 `services[]` 元素，而不是往既有 trade 长正则里并分支。**
  那一支是超长单行正则，多车道并进去 git 必然判冲突（结构性，retry 不会自愈）。
  插入锚点固定在 `development-samples`（6.6.1.2）之后、`9.3.1.1 拆单`之前，与本批另外三条车道互不重叠。

- **测试断言不依赖「同刻两行的先后」。**
  成本明细行按 `(incurred_at, id)` 排序，而 id 是随机 UUID ⇒ **同一时刻的两行先后不稳定**。
  最初的写法断言「删掉第 0 行后合计是 30」，于是变成一个**偶发失败**的 flaky 用例
  （实测第 3 轮跑出来过一次）。改成**按金额找到要删的那一行**、再断言剩下的合计 ——
  断言的是「删行 ⇒ 合计重算」这件事本身，而不是行的排列顺序。
  同类检查：组件领用需求按 `line_no` 排序、质检项按 `(sort_order, item_code)` 排序，都是确定的。

## 文件清单

**新增（后端）**

- `backend/services/trade/src/main/resources/db/migration/V264__production_trial_order.sql` —— 八张新表 + 守卫触发器 + RLS + GRANT
- `backend/services/trade/src/main/java/com/starcon/trade/ProductionTrialModels.java` —— 传输模型（记录 / 枚举）
- `backend/services/trade/src/main/java/com/starcon/trade/ProductionTrialRules.java` —— 纯规则（状态机 / 良率 / 成本偏差 / 建议举证）
- `backend/services/trade/src/main/java/com/starcon/trade/ProductionTrialService.java` —— jOOQ 显式 SQL 服务
- `backend/services/trade/src/main/java/com/starcon/trade/ProductionTrialController.java` —— 18 条路径 / 23 个动作
- `backend/services/trade/src/main/java/com/starcon/trade/ProductionTrialBomPort.java` —— catalog BOM 版本候选端口（本车道新增，不动别的车道的 `BomVersionPort`）

**新增（测试）**

- `backend/services/trade/src/test/java/com/starcon/trade/ProductionTrialRulesTest.java` —— 28 例
- `backend/services/trade/src/test/java/com/starcon/trade/ProductionTrialIntegrationTest.java` —— 31 例
- `backend/services/trade/src/test/java/com/starcon/trade/ProductionTrialTenancyIntegrationTest.java` —— 3 例

**新增（前端）**

- `apps/web/src/components/purchase/production-trial-model.ts`
- `apps/web/src/components/purchase/production-trial-model.test.ts` —— 11 例
- `apps/web/src/components/purchase/production-trial-workspace.tsx`
- `apps/web/src/app/purchase/development/trials/page.tsx`
- `apps/web/src/app/api/trade/production-trial-allowlist.test.ts` —— 6 例

**改动**

- `apps/web/src/lib/api-proxy.ts` —— 新增一条 `trial-productions` `services[]` 元素（既有条目一字未动）
- `packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json` —— codegen 产物（新增 31 个 DTO / 18 条路径）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.6.2.1 一行的「状态」「证据」两格

## 验证

- `./mvnw -q compile` → 退出 0
- `./mvnw -pl services/trade -am test -Dtest='ProductionTrial*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` → **62 例 0 失败 0 错误**（连跑两轮均绿）
- `pnpm typecheck` → 3/3 通过
- `pnpm lint` → 通过（本车道零告警）
- `pnpm vitest run apps/web/src/components/purchase/production-trial-model.test.ts apps/web/src/app/api/trade/production-trial-allowlist.test.ts` → **17 例全绿**
- `pnpm vitest run apps/web/src/app/api/trade` → **60 文件 330 例全绿**（api-proxy 全量 allowlist 回归）
- **Testcontainers 容器**：本车道三个测试类各自起一个 `postgres:16-alpine` 容器（surefire 串行 ⇒ **同时最多存活 1 个**），
  每个测试类在 `@AfterAll` 里 `pg.stop()`；两轮跑完 `docker ps -a --filter ancestor=postgres:16-alpine` = **0 个残留**，
  即**结束前已被 ryuk 回收**（本机只有一个 `testcontainers-ryuk` 容器，四个车道共用同一个 ryuk 会话）。

## 后续待办

- **8.4.1 组装单（warehouse 独立库）与本模块的自动衔接**：授权 API / event 未落地。
  现在组件领用需求提交后停在「已提交领用需求」，真实领用与库存扣减由 warehouse 承接。
  衔接落地后，`RELEASED` 应由 warehouse 侧回写而不是人工登记。
- **6.6.2.2 转量产与建档**：待其交付后消费本模块的转量产建议事实（本模块只出建议，不建 SKU）。
- **5.2.2.1 汇率查询**：待其交付后才可能做跨币种成本折算；在此之前跨币种**只分组不折算**。
- **catalog BOM 组件名称**：catalog 的 `BomRequirementLine` 带 `skuName`，但既有 `BomVersionPort.RequirementLine`
  没有这个字段 ⇒ 本模块的组件需求行暂时只有 `sku_code`，名称为空。若要在界面显示组件名，
  需要在 `BomVersionPort`（6.4.1.1 的文件）加字段 —— 本车道**故意没动**它，以免与其它车道冲突。
