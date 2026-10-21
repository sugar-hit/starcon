# 2026-10-20: 5.3.1.1 利润测算器

## 摘要

交付能力蓝图 **5.3.1.1 利润测算器**（catalog 服务，阶段 D6，L3 锚点 5.3.1）。

蓝图职责是「输入 SKU + 目标店铺 + 售价，输出完整利润拆解：售价 → 平台费用 → 物流成本
→ 采购成本 → 头程 → 其他 → 毛利额与毛利率；反向支持输入目标毛利率倒推可承受售价」，
输出「利润瀑布图 + 保本价 + 目标毛利价 + 盈亏平衡销量；参数可分享为方案」，
并加粗 **「这是定价决策的核心工具，必须显式列出每一项扣减并允许展开查看来源，不能只给一个毛利率数字」**。

**本车道选了 A（交付一个诚实的测算器），没有选 B（BLOCKED）。** 理由见下节第 1 条。

## 决策 / 结论

1. **🔴 选 A 而不是 B，理由是「决策就绪闸 + 手工假设机制」把虚高数字这条路堵死了。**
   蓝图的「上游 = 5.2 全部」是**卷级引用**（卷 05 的六个模块里当前只有 5.2.1.1 成本构成
   已交付），缺的两项恰好是毛利里最大的两块：平台费用 5.2.3.1（佣金通常占售价 8%~15%）
   与物流成本 5.2.3.2（每单几美元量级）。不扣它们算出来的毛利率是**系统性虚高**的。
   选项 B 的判据原文是「缺 4 项扣减算出来的毛利率是虚高的，**而它偏偏长得像个正常数字**」——
   本实现让这一句**不成立**：
   - 扣减不全时 `grossProfit` / `grossMarginRate` / `breakEvenPrice` / `targetMarginPrice` /
     `breakEvenVolume` **一律是 null**（不是 0、不是估算值），只给
     `revenueLeftForMissingDeductions`（售价减掉已计入扣减后还剩多少可用于覆盖缺失扣减），
     语义刻意不跟毛利混；
   - 这条闸**落在服务层**而不是页面：下游 5.3.2.1 模板与 5.4 调价会**程序化**消费本模块的
     输出，页面上的一句提示拦不住一个自动改价的规则；
   - 蓝图**输入句**本就把「费用规则、汇率快照」列为输入 ⇒ 上游规则模块未交付时，
     这两项输入唯一的来源就是人。所以允许**手工假设**（平台费率 / 平台固定费 / 物流单价 /
     汇率 / 月度固定成本），但：响应里标 `MANUAL_ASSUMPTION` + 点名 ownerModule、
     能力声明里对应扣减项 `available` 仍然是 **false**、`decisionBasis` 标成
     `COMPLETE_FROM_MANUAL_ASSUMPTIONS`（区别于 `COMPLETE_FROM_RULES`）。
     手工假设**不冒充**系统口径，用户拿去做 what-if 讨论是可用的，拿去做「按规则改价」不行。
   ⇒ 结论：用户**不可能**把这个数字当成可用的毛利率去改价，所以不构成 B 的条件。
   **若 5.2.3.1 / 5.2.3.2 落地，本模块的闸会自动放开**（同一套扣减结构，来源从
   MANUAL_ASSUMPTION 换成 RULE，`decisionBasis` 变成 COMPLETE_FROM_RULES）。

2. **不建任何假装上游已交付的表**。零个平台费用规则表（5.2.3.1 的写入所有权）、
   零个汇率表（5.2.2.1）、零个物流计费规则表（5.2.3.2）、零个 landed cost 表（5.2.1.2）、
   零个分摊规则表（5.2.1.3）。本模块只建「测算方案」与「物流阶梯」两张表。

3. **🔴 每一项扣减都显式列出，且都能展开看来源**（蓝图加粗句的技术落点）。
   瀑布图固定 9 行 = 售价 + 7 项扣减 + 毛利；缺项**也在**行里，显示
   「未交付 / 该扣减未计入」并写明归谁管、缺了会怎样、怎么补。
   来源四要素齐全：哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间（+ 追溯说明）。
   成本类分项的来源**直接复用 5.2.1.1 成本构成的同一份口径串** —— 同一个服务、同一套
   算法，不存在「页面上写的口径」与「测算用的口径」两套说法。

4. **🔴 正向与反向走同一个方程**。正向（售价 → 毛利）与反向（目标毛利率 → 售价）是
   `ProfitCalculatorRules.solvePrice` 的同一次调用：
   `售价 =（固定费 + 物流 + 已知成本）÷（1 − 平台费率 − 目标毛利率）`；
   保本价 = 解「毛利 = 0」，目标毛利价 = 解「毛利 = 目标毛利率 × 售价」。
   集成测试断言：反解出的售价**代回正向公式**，毛利率**恰好**等于目标值（不是接近）。

5. **跨币种绝不相加**。售价币种 ≠ 成本币种时**必须**给汇率（方向显式：1 成本币种 =
   X 售价币种，5.2.2.1 的加粗句），不给就**拒绝测算**（400）—— 绝不按 1:1 折算，
   那会把两个币种的金额当成同一个币种加在一起。

6. **物流阶梯**（蓝图输入句「数量（用于阶梯与运费）」）。段序升序、每段「覆盖到第几单 +
   每单位运费」、**最后一段必须无上限**（否则超出阶梯的数量没有运费定义 ⇒ 给 null，
   而不是拿前几段的平均值糊过去）。盈亏平衡销量按阶梯**分段精确求解**（段内单位贡献是
   常数，累加到转正为止），不是「固定成本 ÷ 某个平均运费」。

7. **成本口径的边界写清楚**：取 5.2.1.1 的**单位成本构成**（🔴 不是 5.2.1.2 到岸成本口径，
   海外仓与自发货的口径差异等它落地后由它接管）；头程当前是**按数量**摊的
   （🔴 5.2.1.3 成本分摊未交付，不是按重量/体积/金额规则摊的）；关税是**本服务口径**
   不是 12 财务核算结果（finance 服务不存在）。

8. **目标店铺来自 2.1.1.1 平台目录**，**跨服务只读走 HTTP 端口**（照抄 `CostBasisPort` +
   `CostBasisConfiguration` 的写法）：`ChannelPlatformPort` / `ChannelPlatformConfiguration`，
   透传调用方自己的 Authorization 头（租户由 channel 从成员关系解析），未配 `CHANNEL_URL`
   时走 Unwired 实现**显式抛错**而不是返回空列表。上游不可达 ⇒ 站点列表 **503**。

9. **🔴 零外键，用触发器做引用校验**（batch-37 的坑：给既有表加外键会打破同服务既有
   测试的 TRUNCATE ⇒ 39 例 Errors ⇒ L2 红）。方案 → SKU、阶梯 → 方案都走 BEFORE INSERT OR
   UPDATE 触发器（报 23514），既不参与 TRUNCATE 的依赖图，判据又与外键等价。
   引用可见性则用 RESTRICTIVE 的 `data_scope` 策略（与 isolation 策略同时满足才放行）。

10. **测算方案**（蓝图输出句「参数可分享为方案」）。参数 + 手工假设 + 物流阶梯 +
    **成本窗口**一起落库：采购成本由窗口内的到货事实派生，不记下窗口，方案在别的一天
    复算会得到不同的采购成本。方案是**租户内**的 —— 定价参数（成本、费率、汇率）
    跨租户可见是越权不是协作，所以「分享」= 同租户可复算，不做跨租户分享链接。

11. **api-proxy 白名单外科式最小追加**：**新增一条** `profit-calculator` 元素，追加在
    数组末尾，既有条目一字未动。字面量子路径（`capabilities` / `sites` / `calculate`）
    与 uuid 分支（只在 `scenarios/` 之下）不会互相吃掉。

## 关键 trade-off / 讨论

- **「不给毛利率」会不会让页面变成一个空壳？** 不会：瀑布图照样把每一项扣减列出来并
  展开看来源，成本类分项的来源信息是完整的（哪张表、什么口径、什么生效时间），
  另有「剩余可覆盖缺失扣减的金额」这个**决策有用**的数（它其实是缺失扣减的预算上限，
  不是毛利）。加上手工假设机制，用户随时能补齐并拿到完整数字。
- **为什么不给缺失项也返回「已知部分合计」？** 因为蓝图那句「不能只给一个毛利率数字」
  的反面是「不能给一个**看起来能用**的毛利率数字」。「已知部分合计」在界面上就是一个
  长得像毛利率的数字。宁可不给。
- **手工假设会不会变成「第二个事实来源」？** 会，所以它被刻意做成**弱来源**：
  标 MANUAL_ASSUMPTION + 点名 ownerModule + 能力声明里 available=false +
  decisionBasis 单独一档 + 方案里原样存下来（写清假设来源，便于事后复盘）。
  上游规则模块落地后由它接管，人填的值不会与系统值混淆。

## 文件清单

### 后端（catalog 服务）

- `backend/services/catalog/src/main/resources/db/migration/V227__profit_calculator.sql`
  —— `profit_calculator_scenario` + `profit_calculator_logistics_tier`，
  tenant_id NOT NULL + FORCE RLS + 零外键 + 两条引用校验触发器 + GRANT。
- `backend/services/catalog/src/main/java/com/starcon/catalog/ProfitCalculatorModels.java`
  —— 请求 / 响应 / 能力声明 / 方案的全部记录与枚举（CalcMode / Deduction / SourceKind /
  DecisionBasis）。
- `backend/services/catalog/src/main/java/com/starcon/catalog/ProfitCalculatorRules.java`
  —— **纯算术**引擎（无 IO、无租户上下文，可单测）：正向 / 反向同方程、阶梯摊分、
  分段求解盈亏平衡销量、BigDecimal 精度。
- `backend/services/catalog/src/main/java/com/starcon/catalog/ProfitCalculatorService.java`
  —— jOOQ 显式 SQL + 编排（复用 `SkuCostCompositionService` 取 5.2.1.1 成本构成）。
- `backend/services/catalog/src/main/java/com/starcon/catalog/ProfitCalculatorController.java`
  —— 6 个端点。
- `backend/services/catalog/src/main/java/com/starcon/catalog/ChannelPlatformPort.java`
  + `ChannelPlatformConfiguration.java`
  —— 读 2.1.1.1 平台目录的跨服务只读端口（含 Unwired 兜底）。
- 测试：`ProfitCalculatorRulesTest.java`（**15 例**）、
  `ProfitCalculatorIntegrationTest.java`（**12 例**，Testcontainers PG16 + 连接池 1）。

### 前端

- `apps/web/src/components/pricing/profit-calculator-model.ts` —— 类型 + 纯函数 + 端点。
- `apps/web/src/components/pricing/profit-calculator-model.test.ts` —— **18 例**。
- `apps/web/src/components/pricing/profit-calculator-workspace.tsx` —— 真实工作区。
- `apps/web/src/app/pricing/calculator/page.tsx` —— 页面（`/pricing/calculator`）。
- `apps/web/src/app/api/catalog/profit-calculator-allowlist.test.ts` —— **5 例**。
- `apps/web/src/lib/api-proxy.ts` —— **仅追加一条** `profit-calculator` 元素（末尾锚点）。

### 契约 / 台账 / 文档

- `packages/api-contracts/src/catalog.openapi.json` + `packages/api-contracts/src/contracts/catalog.ts`
  —— codegen 重生成（catalog 纯新增；其余 5 个服务逐字节不变）。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 5.3.1.1 一行。
- `docs/sessions/2026-10/2026-10-20-profit-calculator.md` —— 本文件。

## 端点

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/catalog/profit-calculator/capabilities` | GET | 七个扣减项的来源 / 可用性 / 归谁管 / 影响 + 决策就绪闸说明 |
| `/catalog/profit-calculator/sites` | GET | 目标店铺（站点）列表（跨服务只读；上游不可达 ⇒ 503） |
| `/catalog/profit-calculator/calculate` | POST | 一次测算（不落库） |
| `/catalog/profit-calculator/scenarios` | GET / POST | 方案列表（分页） / 保存方案 |
| `/catalog/profit-calculator/scenarios/{id}` | GET / DELETE | 方案详情（含复算） / 删除 |

## 验证

- `./mvnw -q compile` —— 退出 0。
- `./mvnw -pl services/catalog -am test -Dtest='ProfitCalculator*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  —— **27 例 0 失败 0 错误**（12 集成 + 15 规则）。
- `pnpm typecheck` —— 3/3 通过。
- `pnpm lint` —— 通过（本车道零告警）。
- `pnpm vitest run apps/web/src/components/pricing` —— 43 例全绿。
- `pnpm vitest run apps/web/src/app/api` —— **全量 allowlist 回归 107 文件 630 例全绿**。

## 后续待办

1. **5.2.3.1 平台费用落地后**：把平台费从 MANUAL_ASSUMPTION 换成规则取数，
   `decisionBasis` 自动变成 COMPLETE_FROM_RULES，决策就绪闸放开。**不需要改扣减结构**。
2. **5.2.3.2 物流与头程费用落地后**：同上；顺带把「数量 → 运费」的阶梯换成
   渠道计费规则（按重量/体积/件数 + 附加费）。
3. **5.2.2.1 / 5.2.2.2 汇率落地后**：汇率从手工假设换成系统取数，并按 5.2.2.2 把汇率
   冻结到方案上（历史利润可复算）。
4. **5.2.1.2 到岸成本落地后**：成本口径从「单位成本构成」切到「到岸成本」，
   海外仓与自发货分成两条口径由用户选。
5. **5.2.1.3 成本分摊落地后**：头程分摊从「按数量」换成按重量/体积/金额规则，
   混合货件的分摊差异会体现在这一项上。
6. **5.3.1.2 批量测算**：本模块的 `POST /calculate` 是它的天然底座（批量 = 循环 + 结果表），
   但**本车道不预建批量端点**（5.3.1.2 未交付，不占它的端点命名空间）。
7. **5.3.2.1 模板 / 5.4 调价**：消费本模块输出前**必须**先看 `decisionReady` /
   `decisionBasis`；INCOMPLETE 的结果里没有毛利率数字可读。
