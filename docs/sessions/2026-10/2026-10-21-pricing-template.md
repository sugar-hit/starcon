# 5.3.2.1 定价模板（阶段 D6，L3 锚点 5.3.2）

> 会话日期：2026-10-21 · 车道：feat/5-3-2-1 · 状态：**已交付**（待并入集成分支）

## 决策摘要

1. **建议价必须来自 5.3.1.1 的真实测算，本模块不复刻任何扣减公式。**
   蓝图关系句写的是「上游 = 5.3.1.1」，而 5.3.1.1 的加粗句是「必须显式列出每一项扣减…」。
   ⇒ 模板只存「怎么算」，算的时候调 5.3.1.1（catalog 服务内的 `ProfitCalculatorService`）：
   - 固定毛利 → 走它的 TARGET_MARGIN 求解（售价 =（固定费 + 物流 + 已知成本）÷（1 − 平台费率 − 目标毛利率））；
   - 成本加成 → 加成基数取它瀑布图里的**单位成本合计**（5 项成本分项 + 物流每单位），平台费是售价的比例、**不进加成基数**；
   - 竞品跟随 → 以人工登记的竞品价为起点。
   最后统一用建议价再跑一次它的 PRICE 方向拿**真实**毛利率（不自己算一遍）。

2. **决策就绪闸落在服务层，不在页面上。**
   5.3.1.1 在扣减不全时把毛利率 / 目标毛利价置 null；本模块照抄并把它变成 **409**：
   缺口清单原样带回（点名归谁管、缺了会怎样），**绝不给一个虚高的建议价** ——
   下游 5.4.1.1 调价单会拿它直接改价，页面上的一句提示拦不住一个自动改价的规则。

3. **两类调整项的数学形态不同，且都是有意选的。**
   - 按站点差异化 → **加在基准率上**（`有效基准率 = 基准率 + 站点增量`）：三个基准方法的基准率含义不同
     （加成率 / 目标毛利率 / 跟随率），只有加法能让「同一套站点规则」在三种基准下保持同一语义；
   - 按库存周转调整 → **乘在建议价上**（`建议价 × (1 − 折扣率)`）：滞销处理的是「这个价太高卖不动」，
     加在基准率上会让固定毛利模板在折扣很小时反而涨价。

4. **「新品与老品用不同模板」的判定依据用真实事实。**
   用 **3.1.1.3 的 SPU 生命周期状态机**（未上架 = 新品）；全仓没有「首次上架时间」这个事实，
   编一个出来就是假数据。阶段不符 ⇒ 409，不静默套用。

5. **「竞品跟随（若允许）」的「若允许」在本仓的落点。**
   本仓**没有**竞品价模块 ⇒ 竞品价只能人工登记（显式标注的手工假设），
   **不建**任何竞品价表来假装上游已交付；没登记竞品价就保存模板 ⇒ 400。

6. **输入缺口一律显式，不静默兜底。**
   - 可售天数（库存周转用）依赖 7 库存 / 09 订单，这两个上游没有给本服务可读端点 ⇒ **人工填**；
     模板配了这条规则而没填 ⇒ **400 拒绝**（不按「不打折」糊过去 —— 静默跳过一条策略等于让模板语义失效）；
   - 平台费率 / 物流单价 / 汇率沿用 5.3.1.1 的手工假设机制：留空 = 「取不到」，**不是 0**；
   - 跨币种必须要汇率（方向显式），**绝不按 1:1 折算**。

7. **模板是「怎么算」，价目表是「算完是多少」。**
   本模块只落模板 + 一次应用留痕，**不建价目表**（5.1.1.2 的写入所有权）、
   **不改生效价**（5.4.1.1 的写入所有权）。产出的永远是**建议价**；
   集成测试直接断言「应用之后 SKU 行的 version / updated_at 一个字节都没动」。

## 关键 trade-off

| 决策 | 取舍 |
| --- | --- |
| 竞品价 / 可售天数用人工输入，而不是等上游 | 上游没有可读端点；等下去本模块交付不了。代价是口径要显式标成手工假设（能力声明逐条写 ownerModule 与 impact），上游落地后由上游接管 |
| 商品阶段用 SPU 状态机，而不是「上架天数」 | 全仓没有「首次上架时间」事实；用状态机能拿到真实信号，代价是「老品」的含义是「已上架过」，而不是「卖满 N 天」 |
| 成本不完整一律 409，不给「带缺口的建议价」 | 少给一个数，但下游 5.4.1.1 不会拿着虚高的价去改价；这是把 5.3.1.1 的纪律往下一层传 |
| 应用留痕单独建表，不写进模板行 | 模板是「怎么算」（当前口径），留痕是「算过什么」（历史事实）；混在一张表里会让模板的历史被覆盖 |
| 调整项与适用对象整体替换（事务内删 + 插） | 避免「半成品模板」（第 2 条改了、第 3 条还没改的中间态说不清） |

## 文件清单

**后端（catalog 服务）**

- `backend/services/catalog/src/main/resources/db/migration/V231__pricing_template.sql`
  （4 表：pricing_template / _adjustment / _scope / _application；FORCE RLS；**零外键**，引用校验用触发器）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PricingTemplateModels.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PricingTemplateRules.java`（纯算术，可单测）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PricingTemplateService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PricingTemplateController.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/PricingTemplateRulesTest.java`（18 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PricingTemplateIntegrationTest.java`（15 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PricingTemplateTenancyIntegrationTest.java`（4 例）

**前端**

- `apps/web/src/components/pricing/pricing-template-model.ts`（+ `pricing-template-model.test.ts`，32 例）
- `apps/web/src/components/pricing/pricing-template-workspace.tsx`
- `apps/web/src/app/pricing/templates/page.tsx`
- `apps/web/src/app/api/catalog/pricing-template-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（**只新增一条** services 元素：插在「5.2.3.1 平台费用」之后、
  「5.3.1.1 利润测算器」之前；既有 84 条元素一字未动）

**契约 / 台账**

- `packages/api-contracts/src/catalog.openapi.json`、`packages/api-contracts/src/contracts/catalog.ts`（codegen 重生成）
- `docs/product/capability-blueprint/module-progress.md`（只改 5.3.2.1 那一行的「状态」「证据」两格）

## 踩到的坑（值得下一条车道看一眼）

1. **契约撞名**：`AdjustmentInput` 已被 5.2.1.1 成本构成占用；同名会让 codegen 里的
   schema map **后者顶掉前者** ⇒ 成本构成的契约类型被静默改形（前端 typecheck 才炸）。
   本模块改名 `TemplateAdjustmentInput` 后两边各自活着。
   ⇒ 起 DTO 名之前先 `grep` 同服务已有 schema 名，别只看 Java 类名。
2. **PostgreSQL 的表级 UNIQUE 约束不接受表达式**：`UNIQUE(..., COALESCE(platform_code,''))` 直接语法错，
   「可空列参与唯一性」必须写成唯一索引。
3. **SpringDoc 导出的 schema/paths 顺序不稳定**：同一份代码两次 codegen 的 key 顺序可能不同，
   导致 `*.openapi.json` 出现整文件级 diff（内容逐 key 相同）。
   并入靠 `tools/module-progress/merge-union.mjs` 的 `contract-union`（按 paths / 类型名求并），
   顺序差异无害；但**别的服务的快照不要跟着改**（本车道把 trade 的快照 revert 了）。
4. **Flyway 里 `CHECK(btrim(note) >= '')`** 是语法错（应为 `<> ''`）——这类错误只有真跑 PostgreSQL 才暴露。

## 后续待办

- **5.3.2.2 价格规则**：上游是本模块（模板）与 7 库存；「先建议再执行」的闸与高风险规则的审批要在那条车道落地。
- **5.4.1.1 调价单**：消费本模块的建议价时**必须先看 `decisionReady`** ——
  false 时建议价没有可用的毛利率，拿它批量改价会同时改错价与错利润。
- **5.2.3.1 平台费用 / 5.2.3.2 物流与头程费用接入 5.3.1.1 的测算路径**之后，
  模板上的平台费率 / 物流单价 / 汇率三列应当由上游接管（现在它们是手工假设）。
- **7 库存 / 09 订单提供可读端点**之后，当前可售天数改由系统取数，人工输入降级为兜底。
- **上游竞品价模块**落地之后，竞品基准价由系统取数；本模块的 `competitorReferencePrice` 降级为兜底。
- 本模块**不建价目表**（5.1.1.2 的写入所有权）与**不改生效价**（5.4.1.1 的写入所有权），
  两者都留给各自的模块。
