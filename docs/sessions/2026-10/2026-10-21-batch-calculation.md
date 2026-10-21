# 2026-10-21: 5.3.1.2 批量测算

## 摘要

交付能力蓝图 **5.3.1.2 批量测算**（catalog 服务，阶段 D6，L3 锚点 5.3.1）。

蓝图职责是「对一批 SKU 批量测算，按当前成本与规则输出利润表，标记亏损品与低毛利品，
支持导出并筛选（毛利率 < X%、售价 < Y）」，输入「SKU 范围（按分类/品牌/店铺）；测算参数」，
输出「批量测算结果表 + 异常标记」，并加粗
**「批量测算是选品与清库存决策的依据：亏损品要么涨价要么清仓，人工逐个测算不可行」**。

**本车道没有 BLOCKED**：上游 5.3.1.1 利润测算器在集成头里已交付（V227 +
`ProfitCalculator{Controller,Service,Rules,Models}` + 前端 `pricing/profit-calculator-*`），
本模块**复用**它的测算引擎，不重写第二套算术。

## 决策 / 结论

1. **🔴 复用 5.3.1.1 的引擎，而不是另写一套批量算术。**
   批量测算 = 把 `ProfitCalculatorService.calculate` 跑 N 次（每次换一个 SKU 与它的售价）。
   金额 4 位小数、费率 6 位小数、HALF_UP、成本口径与决策就绪闸全部沿用 —— **逐字相同**。
   理由：下游 5.4.1.2 批量调价会**程序化**消费本模块的输出并直接改价，两套口径必然改错价。
   集成测试直接断言「批量结果与 5.3.1.1 单次测算逐字一致」。

2. **🔴 售价从哪来：本仓唯一的真实售价事实是 5.5.2.1 的价格观测台账。**
   5.1 价目表未交付 ⇒ catalog 库里没有任何「我方价目表」。批量测算不能让人手填 200 个价，
   必须有一个按 SKU 取价的真实来源 —— `price_anomaly_observation`（V222）是唯一的一处。
   口径：取该 SKU 在该店铺**最新一条**观测，优先 `internal_price`（我方价）、
   退到 `platform_price`（平台在售价），**来源与观测时刻逐行落库**
   （台账是只追加的，「最新一条」是一个时间点而不是「当前价」——
   拿三个月前的价去清库存，运营必须看得见这一点）。

3. **🔴 取不到售价就整行跳过，绝不拿 0 顶。**
   `SKIPPED_NO_PRICE` 的行：售价 / 毛利额 / 毛利率全 null、两个标记全 false。
   理由：0 会被算成「亏损品」，而「亏损品」是运营拿去决定「涨价还是清仓」的依据 ——
   凭空造出来的决策依据比没有更危险。跳过行**也在**结果表里（而不是被删掉），
   因为「这个分类下有 40 个 SKU 没有价格观测」是运营必须看见的事实。

4. **🔴 两个异常标记互斥。**
   亏损品（毛利额 < 0）与低毛利品（还没亏、但毛利率低于阈值）在数值上本来重叠
   （亏 1% 的品毛利率也是负的，当然也低于 15% 阈值）。不互斥的话同一个 SKU 会被计两次，
   「问题品总数」就多算 —— 而那个数正是拿去决定清库存范围的那个数。
   ⇒ 亏损归亏损，低毛利只标「还没亏但低于阈值」。成本不完整的行**两个标记都不给**
   （它是「没测准」，不是「毛利低」）。

5. **🔴 汇总数字读时聚合，不落库。**
   落库会与结果表漂移（「列表里 3 个亏损品，汇总写着 5 个」这种矛盾会长期存在），
   而且落库的汇总做不到「随当前可见范围变化」—— 读时聚合天然受 RLS 约束。

6. **真筛选 + 真分页全部在 DB 层**，不是把整表拉回来在内存里筛。
   筛选：毛利率 < X、售价 < Y、异常标记、状态、SKU 关键字；排序：毛利率 / 售价升降序。
   **导出与列表共用同一套筛选拼装** —— 导出的就是查到的那些行。
   CSV：RFC 4180 转义 + Excel 公式注入切断（前导 `=+-@` 加单引号），
   单次上限 2 万行**明确报错**不静默截断（截断的 CSV 会让人以为数据完整）。

7. **上限显式、不静默截断。**
   一次批量测算最多 200 个 SKU（每个 SKU 的成本构成要跨服务读 warehouse，
   200 个 SKU 就是 400 次跨服务调用）；超限**明确报错**让人缩小范围。
   同理：上游不可达时**整批失败**（503），**不留半截批次** —— 一个跑了一半的批次
   比一个明确的错误危险得多。

8. **🔴 零外键，引用校验用触发器。**
   沿用 batch-37 的教训（给既有表加 FOREIGN KEY 会打破同服务既有测试的 TRUNCATE）：
   结果 → 批次、结果 → SKU 都用触发器校验，报错码与外键等价（23514）但不参与 TRUNCATE 依赖图。

9. **批次表只有 isolation 策略，没有 data_scope 策略。**
   data_scope 的 WITH CHECK 在 INSERT 时也生效，而批次是在结果行写入**之前**建的 ——
   一条「批次里至少有一个可见 SKU」的策略会让批次根本插不进去。
   批次表里存的是范围定义与参数（不含逐 SKU 金额），汇总一律读时聚合 ⇒ 表本身没有可泄漏的明细。

10. **前端与 5.3.1.1 共用 `/pricing/calculator` 路由，但不重写它的骨架。**
    新建 `batch-calculation-model.ts` + `batch-calculation-section.tsx`，
    在既有 `profit-calculator-workspace.tsx` 里**只追加一个分区渲染点**。
    前端测试断言**从分区描述符本身取标题与字段**（组件也从同一个描述符渲染），
    **不断言任何「未交付」字面量** —— 兄弟模块一旦交付那个字面量就会消失，
    写死会让别的车道一交付就红（本仓 2026-10-20 踩过这个坑，害得整批 L2 返工一轮）。

## 关键 trade-off

- **同步执行 vs 异步任务**：批量测算在本车道是**同步**的（一个 HTTP 请求内跑完）。
  取舍是简单、可测、无需 worker；但 200 个 SKU 意味着一次请求内 400 次跨服务调用，
  耗时随范围线性增长。⇒ 用「200 个 SKU 的显式上限」换掉了队列与进度轮询的复杂度。
  如果后续范围变大，应改为异步任务 + 进度轮询（表结构已经为此留了位置：
  `batch_calculation_run` 就是任务头）。
- **售价来源绑在 5.5.2.1 上**：本模块的售价来自异常告警的价格观测台账，
  跨模块（05 定价 → 05 定价的另一支）耦合。取舍是「现在就有真实售价」比
  「等 5.1 价目表交付」更有用；等 5.1 落地后，`price_source` 的 CHECK 值
  （当前只有 `LATEST_OBSERVATION`）会强制这条路径**显式**改一次，而不是往里塞个没人认识的值。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V230__batch_calculation.sql`
  —— `batch_calculation_run` / `batch_calculation_result`，零外键、触发器校验、FORCE RLS。

**后端**

- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchCalculationModels.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchCalculationRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchCalculationService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchCalculationController.java`

**测试**

- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchCalcFixture.java`（共享底座）
- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchCalculationIntegrationTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchCalculationTenancyIntegrationTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchCalculationRulesTest.java`

**前端**

- `apps/web/src/components/pricing/batch-calculation-model.ts`
- `apps/web/src/components/pricing/batch-calculation-model.test.ts`
- `apps/web/src/components/pricing/batch-calculation-section.tsx`
- `apps/web/src/components/pricing/profit-calculator-workspace.tsx`（**只**追加一个分区渲染点）
- `apps/web/src/app/api/catalog/batch-calculation-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**只**新增一条 services 元素，108 → 109）

**契约与台账**

- `packages/api-contracts/src/catalog.openapi.json` + `packages/api-contracts/src/contracts/catalog.ts`
  （codegen 产物：+6 path / +7 DTO）
- `docs/product/capability-blueprint/module-progress.md`（**只**改 5.3.1.2 那一行）

## 验证

- `backend/ && ./mvnw -q compile` ✅
- `backend/ && ./mvnw -pl services/catalog -am test -Dtest='BatchCalculation*'` ✅
  29 例全绿（功能 13 / 多租户 3 / 纯规则 13），真实 PostgreSQL 16 Testcontainers + 强制 RLS，
  连接池固定 1 条连接。
- `pnpm typecheck` ✅
- `pnpm vitest run apps/web/src/components/pricing/batch-calculation-model.test.ts apps/web/src/app/api/catalog` ✅
  9 个文件 / 94 例全绿。
- `pnpm vitest run apps/web/src/app/api` ✅（改了 api-proxy.ts ⇒ 全量 allowlist 回归）
  113 个文件 / 660 例全绿。
- `pnpm --filter @starcon/web lint` ✅（只有既有模块的既有 warning，本模块无告警）。

## 后续待办

1. **5.1 价目表交付后接管售价来源**：`batch_calculation_run.price_source` 的 CHECK
   当前只有 `LATEST_OBSERVATION`；接上价目表时要显式改这条 CHECK 并加一条新取值，
   让「售价从哪来」在数据层可查。
2. **范围变大时改异步**：200 个 SKU 的上限是靠「每个 SKU 一次跨服务成本读取」撑住的。
   若运营要按分类一次测几千个 SKU，需要改成异步任务 + 进度轮询。
3. **分类口径**目前是 `spu.category` 文本的**精确匹配**（不是分类树 V14）。
   接分类树需要另加一列并明确「按文本还是按树节点」的优先级。
4. **5.4.1.2 批量调价**消费本模块输出时，必须先看 `decisionReady` 与
   `status`：`SKIPPED_NO_PRICE` 的行**不是**「毛利率 0 的亏损品」。
5. 5.2.3.1 平台费用与 5.2.3.2 物流与头程费用交付后，平台费率与物流单价会从
   「手工假设」换成系统取数，本模块的 `decisionBasis` 口径随之变化（规则与 5.3.1.1 一致，
   不需要额外改动）。
