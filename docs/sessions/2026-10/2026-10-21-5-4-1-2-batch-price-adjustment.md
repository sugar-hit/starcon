# 2026-10-21: 5.4.1.2 批量调价 —— 先预览再执行：固定金额 / 百分比 / 按模板，影响范围与反向指标

> 车道 `feat/5-4-1-2`，worktree `.worktrees/5.4.1.2`，基线 `7eef1875`（merge(6.3.2.2): 到货与逾期）。
> 能力蓝图卷 05「定价与价目」L3 锚点 5.4.1，阶段 D6，L4 模块 5.4.1.2。

## 摘要

本车道交付 5.4.1.2「批量调价」：对选中的一批 listing 统一改价（固定金额 / 百分比 / 按模板），
**先出影响预览、再确认执行**。

蓝图的核心不是「改价」这个动作，而是那句加粗的约束：

> **必须先看预览再执行**，预览要包含"改完后预计亏损的商品数"这类反向指标。

本模块把它落成**三道闸**（详见「决策摘要」第一节）：预览**结构上不可能落库** →
预览给出一个**内容哈希令牌** → 执行时服务端**当场重算**并逐字比对，对不上就 409 拒绝。

输出两张表（`batch_price_adjustment` 调价单 + 影响预览报告快照、`batch_price_adjustment_item`
逐 listing 的新旧价与前后利润），7 个端点挂 `/catalog/batch-price-adjustments`，
前端 `/pricing/adjust/batch` 是一个真实工作区（能力声明 + 调价参数 + 影响预览报告 + 调价单台账 + 明细真筛选真分页）。

## 决策摘要

### 一、「先看预览再执行」必须落在**服务端**，不是页面上的一句话

页面提示挡不住一个自动改价的规则，所以三道闸全部在服务端：

1. **预览无副作用，而且是结构性的。** 预览跑在一个**注定回滚**的事务里
   （`TransactionTemplate` + `status.setRollbackOnly()`），事务上下文由 `AccessService.run`
   在其中绑定（`spu` 的 `data_scope` 策略要读 `app.grants` / `app.actor` /
   `app.department` / `app.permission`，少绑一个预览就会看到 0 个 SKU）。
   ⇒ **预览一行都不写**，连 5.3.2.1 的应用留痕都不写。
2. **预览令牌 = 预览内容的内容哈希**（SHA-256 over 规范化后的参数 + 逐行前后对比）。
   令牌**不含随机量与时间戳** ⇒ 同一份输入永远算出同一个令牌（跨进程 / 跨实例也成立）。
3. **执行时重算预览并逐字比对令牌**，对不上 ⇒ **409 拒绝**，
   而不是照着过期的数字改价。真正防的是「预览之后有人改了价 / 改了目标集合」。

> 🔴 **踩过的坑（值得记）**：最初把预览做成「参与外层事务 + 标记 rollback-only」，
> 结果 Spring 在**外层**提交时抛 `UnexpectedRollbackException` —— 预览变成一个报错接口。
> 改成「预览自己开事务（`access.run` 参与其中）」之后，外层没有事务，
> 提交时走 `doCommit` → `isLocalRollbackOnly` → **静默回滚**，不抛异常。
> 另外 `preview()` 显式检查 `TransactionSynchronizationManager.isActualTransactionActive()`，
> 被误放进别的事务里时**明确报错**而不是让人踩坑。

### 二、「按模板」方式的建议价来自 5.3.2.1，本模块**不重写**模板怎么算

蓝图点名三种方式：固定金额 / 百分比 / 按模板。前两种是纯算术；第三种必须用 5.3.2.1
的定价模板算 —— 而 5.3.2.1 的 `apply()` 是**单个 SKU 的「一键应用」**，它会写一条应用留痕。

⇒ 本模块**直接调 `PricingTemplateService.apply()`**，不自己实现模板的基准方法 / 站点差异化 /
库存周转折扣；建议价与它的复核毛利都原样采用，**不二次舍入**（二次舍入会让它与
「一键应用」页上的建议价对不上，而对不上就会有人以为其中一边算错了）。

代价是预览期间会写留痕 —— 这正是上面第一节要解决的事。

### 三、listing 的定义：一个 listing = 一个 SKU 在一个店铺

蓝图的上游写着 4.1.1.1（listing），但**本仓的 listing 事实属 channel 服务**，
catalog 没有 listing 的本地投影，而 AGENTS.md 禁止跨服务直接查对方的表。

⇒ **不新建任何 listing 影子表**去假装上游已交付。在 05 定价卷里唯一真实存在的
listing 级价格事实是 5.5.2.1 的**价格观测台账**（`price_anomaly_observation`：
一行 = 一个 SKU 在一个站点上的一次价格观测）。本模块因此把 listing 定义成 **(SKU × 店铺)**，
当前价取该台账的**最新一条**观测（优先我方价，退到平台在售价，来源与观测时刻逐行落库）。

**🔴 为什么不写那张台账**：它有唯一的写入所有者（5.5.2.1 的 `PriceAnomalyService`），
每张事实表只有一个写入所有者。所以本模块**只读**它取当前价，「改完之后的新价」记在
**本模块自己的调价单**里 —— 蓝图 5.4.1.1 对调价单的要求本来就是「记录变更前后值」。

> 由此产生一条**跨模块的已知缺口**（写进了能力声明）：5.1 价目表未交付 ⇒ 没有一个
> 大家共用的「当前价」事实源 ⇒ 5.3.1.2 的售价口径在下一次批量测算时**仍会看到旧价**。
> 这不是本模块能修的，要等 5.1 交付后统一。

### 四、取不到就跳过，绝不拿 0 顶；「没测准」与「确实是 0」是两件事

沿用 5.3.1.2 与 5.3.1.1 的同一条纪律，并扩到四种跳过：

| 情形 | 表现 |
|---|---|
| 该 listing 没有价格观测 | `SKIPPED`，旧价与前后利润全 null，亏损标记全 null |
| 价格观测的币种与本次调价的币种不同 | `SKIPPED`（**绝不按 1:1 折算**） |
| 调完不是正价 | `SKIPPED`（**绝不改成 0**） |
| 5.3.2.1 拒绝这一行（成本不完整 / 商品阶段不符 / 适用对象不匹配） | `SKIPPED`，原因原样带回 |

跳过的行**也在明细里**并写明原因：「这个范围里有 40 个 listing 调不了」是运营必须看见的事实。
另外**成本不完整时毛利额与毛利率是 null、亏损标记也是 null**（不可信，不是 false），
`profitDeltaTotal` 在有行没测准时也给 **null** —— 合计把没测准的行当 0 是一个系统性虚高的数字。

### 五、舍入：售价 2 位、内部金额 4 位、费率 6 位

蓝图没规定精度，本模块明确钉死并写在页面上：

- **售价 2 位 HALF_UP** —— 对外标价就是两位小数的钱（2.1.1.1 平台目录的 `currencyScale` 也是 2）。
- 内部金额 4 位、费率与毛利率 6 位 HALF_UP（与 5.3.1.1 逐字相同）。
- **按模板方式的建议价原样采用 5.3.2.1 的结果，不二次舍入。**

### 六、反向指标单独成卡，不塞进「预计利润变化」

蓝图加粗句点名「改完后预计亏损的商品数」。本模块同时给三个数：
**改价前 / 改价后的亏损 listing 数** + **改价后预计亏损的商品数**（至少一个 listing 亏损的去重 SKU 数）。

只看「预计利润变化」是不够的 —— 一批 listing 整体利润上升，也可能是因为其中几个
从「小赚」变成「巨亏」，而那几个正是要清的品。

## 关键 trade-off

- **预览的「无副作用」用回滚实现 vs 用「不写」实现。**
  选回滚，因为「按模板」方式**必须**调 5.3.2.1 的 `apply()`（它写留痕），
  而本模块**不改兄弟模块的代码**（不改 `PricingTemplateService` 加 dryRun 参数）。
  代价是要跟 Spring 的事务语义打交道（见「踩过的坑」）。
  备选方案是重新实现一遍模板的算术（复用 `PricingTemplateRules` 的公开静态方法），
  那样预览天然不写，但要复制约 100 行编排，且可能与 5.3.2.1 的语义漂移 —— 选了前者。
- **预览令牌用纯内容哈希 vs 用服务端密钥签名。**
  选纯哈希：它是**内容绑定**（防的是「预览之后目标或价格变了」这个真实危害），
  不是防伪造的凭证；而密钥签名在多实例部署下会自己打断「预览 → 确认」这条链
  （实例 A 预览、实例 B 执行 ⇒ 令牌对不上）。
- **调价单只授 SELECT / INSERT，不授 UPDATE / DELETE。**
  批量调价单与明细是**不可变事实**（蓝图 5.4.1.1 对调价单的要求就是「记录变更前后值」）。
  要改就**再发一张单**，不能就地改写已经执行过的调价记录。

## 涉及文件

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V237__batch_price_adjustment.sql`

**后端（catalog 服务）**
- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchPriceAdjustmentModels.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchPriceAdjustmentRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchPriceAdjustmentService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/BatchPriceAdjustmentController.java`

**测试（catalog 服务）**
- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchPriceAdjustmentFixture.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchPriceAdjustmentIntegrationTest.java`（15 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchPriceAdjustmentTenancyIntegrationTest.java`（3 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchPriceAdjustmentRulesTest.java`（11 例）

**前端**
- `apps/web/src/components/pricing/batch-price-adjustment-model.ts`
- `apps/web/src/components/pricing/batch-price-adjustment-section.tsx`
- `apps/web/src/components/pricing/batch-price-adjustment-model.test.ts`（18 例）
- `apps/web/src/app/pricing/adjust/batch/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立元素** `batch-price-adjustments`，锚点 = `services[]` 数组最末尾）

**契约与台账**
- `packages/api-contracts/src/catalog.openapi.json`、`packages/api-contracts/src/contracts/catalog.ts`（codegen 产物）
- `packages/api-contracts/src/trade.openapi.json`、`packages/api-contracts/src/contracts/trade.ts`
  （**同一条 codegen 命令的既有产出，语义零变化**）：差异全部来自生成器输出的排版归一化 ——
  类型声明按字母排序、生成头从文件末尾移到首行。核对口径：trade.ts 导出类型集合
  1157 → 1157（净增 0 / 净减 0）；trade.openapi.json 逐路径 + 逐 schema 深度比对 0 处差异。
- `docs/product/capability-blueprint/module-progress.md`（**只改 5.4.1.2 那一行**的状态与证据两格）

## 后续待办

- **5.4.2.1 平台价格同步**（未交付）：本模块的调价单明细里有**新价**，它就是回写平台的数据源。
  本模块**不代它开形状**（api-proxy 里刻意不放行 `batch-price-adjustments/{uuid}/sync`）。
- **5.1 价目表**：交付后「当前价」应该收敛到价目表，届时 5.3.1.2 的售价口径与本模块的
  当前价口径可以统一（现在本模块明确写明了「改完的新价在自己的调价单里，5.3.1.2 仍会看到旧价」）。
- **5.4.1.1 调价单**（未交付）：单 SKU 的调价与审批在它那里；
  本模块是**批量**版本，不替它开端点形状（也不共用分区，页面路由各自独立）。
- **CSV 导出**：本模块**刻意不做**导出（蓝图的输出是「调价单 + 影响预览报告」）。
  如果运营要拿明细去开会，需要另开一条需求。

## 关联

- 蓝图：`docs/product/capability-blueprint/05-pricing.md`（5.4.1.2）
- 上游模块：5.3.1.2 批量测算（`batch-calculations/**`）、5.3.1.1 利润测算器（`profit-calculator/**`）、
  5.3.2.1 定价模板（`pricing-templates/**`）、5.5.2.1 异常告警（价格观测台账）
- 下游模块：5.4.2.1 平台价格同步（未交付）、5.4.1.1 调价单（未交付）
- 纪律来源：`docs/architecture/multi-tenancy.md`、`docs/decisions/0002-multi-tenancy-strategy.md`
