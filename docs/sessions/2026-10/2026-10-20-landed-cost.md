# 2026-10-20: 5.2.1.2 到岸成本

## 摘要

交付能力蓝图 **5.2.1.2 到岸成本**（catalog 服务，阶段 D6，L3 锚点 5.2.1）。

蓝图职责是「计算『货到目标仓可售』的总成本 = 采购价 + 国际头程 + 关税 + 清关 +
目的地操作费 + 尾程预备，用于海外仓/FBA 定价」，输出「按 SKU/批次的到岸单位成本；
不同目的地的成本对比」，并加粗**「到岸成本与仓库成本是两个口径（海外仓 vs 自发货），
定价时必须选对，否则自发货商品按海运成本定价会低估」**。

本车道交付：Flyway 迁移 `V223`（四张新表）、catalog 服务 8 个端点
`/catalog/landed-costs/**`（jOOQ 显式 SQL）、`/pricing/cost` 页面里的「到岸成本」分区、
契约重生成、台账与本会话日志。

## 决策 / 结论

1. **🔴 口径写进表结构与端点响应，不只写在页面上**。
   `landed_cost_statement.cost_basis` 是**只有一个取值的闭集**
   （`LANDED_TO_TARGET_WAREHOUSE`）—— 有人想往到岸成本里塞一个仓库成本口径，
   在 DB 层就被拒（23514）；`excluded_components` 固定写明**不算**的
   `STORAGE_FEE` 与 `LAST_MILE_DELIVERY_FEE`；每一份响应都带 `basis`
   （算了哪六项 + 明确不算哪两项），前端把它固定显示在页面顶部。

2. **🔴 六分量「每行恒 6 行」，缺项也落行**。
   `landed_cost_component` 每个 SKU 行恒有 6 行分量，缺的标 `available=false`
   并写 `owner_module`（归谁管）+ `impact`（缺了会怎样）。
   DB 级 CHECK 钉死「`available=false ⇒ amount IS NULL`」「`included=true ⇒ amount NOT NULL`」
   ⇒ **「缺口」与「真的是 0」在库里就是两种不同的行**，服务层与前端都不可能把缺口渲染成 0.00。
   用 0 填关税会让到岸成本**系统性低估**，而页面上看不出任何异常 —— 这是本模块最危险的失败模式。

3. **合计由延迟约束触发器在提交时核对**。
   行合计、单合计、覆盖度计数三者都核（`guard_landed_cost_totals`，`DEFERRABLE INITIALLY DEFERRED`）：
   「行合计 == 该行计入分量之和」「单合计 == 全单计入分量之和」「覆盖度计数 == 实际分量行数」。
   一个分量都没算出来时合计是 **NULL（算不出来）而不是 0**。
   `unit_landed_cost` 用 `GENERATED ALWAYS AS`（`round(total/base_quantity,4)`）—— 写不进去，也漂不了。

4. **零外键、零 ALTER 任何既有表**。
   引用校验走**触发器**（`guard_landed_cost_line_sku` / `guard_landed_cost_component_line`）
   而不是外键：PG 规定「被 TRUNCATE 的表若被别的表外键引用就拒绝 TRUNCATE」，
   给既有表建外键会打断同服务既有测试的清理（batch-37 已踩过）。
   SKU 引用校验是 **BEFORE 行触发器**（fail-fast）而不是延迟触发器：
   跨租户引用 SKU 要在**写入当下**就被拒，拖到提交才报错时，错误信息里已经带着越权事实。

5. **上游全部跨服务只读，走授权 HTTP 端口**。
   国际头程取 warehouse `first_mile_shipment`（运费 + 保险 + 操作费），
   关税取 `duty_tax_estimate`（经**报关单**关联：发运单 → 报关单 → 关税估算）。
   本服务**透传调用方自己的 `Authorization` 头**，租户由 warehouse 从成员关系解析，
   **不发明 `X-Tenant-Id`**（那等于新建一条「客户端自称租户」的旁路）。
   **上游整体读不到 ⇒ 503 报「读不到」**，绝不返回空集合冒充「没有这笔成本」。

6. **清关费与尾程预备是租户参数，其余四项是上游事实**。
   全仓**没有任何清关费事实**（`customs_declaration` 没有费用列），所以清关费只能是参数；
   尾程预备按定义就是**定价用的预留**，不是尾程派送费实际值（后者归 10.2 / 10.6.1.1，不在本模块）。
   参数按（分量, 目的国, 币种）唯一，未维护即**缺口**不是 0。

7. **🔴 承担方由贸易条款决定，不由 `risk_bearer` 决定**。
   `risk_bearer` 说的是**风险转移点**，不是费用承担方：DAP 下风险在指定地点转移，
   但**运费仍然是卖方付的**（卖方把货送到指定地点）。
   用 `risk_bearer` 判会把 DAP 下的国际头程**整笔剔掉** —— 那正是本模块最忌讳的系统性低估。
   规则表：国际头程 + 目的地操作费除 EXW/FCA（买方自提自运）外卖方承担；
   清关费除 DAP/DPU（进口清关归买方）外卖方承担；关税按上游 `duty_bearer`（V90 的事实）；
   尾程预备是定价预留，与贸易条款无关。

8. **关税与增值税/GST 分列，但都进合计**。
   `tax_duty_amount` / `tax_other_amount` 分列（10.5.1.3 把 DUTY / VAT / GST 明确分成三笔独立的钱），
   而 `amount` 是**进口环节全部税额**（= 估算单该行总税额）——
   六个分量的闭集里没有单独的 VAT 分量，把增值税剔掉就是在给到岸成本漏钱。

9. **批次级成本按数量比例摊到 SKU 行**，逐字守恒（最大余数法）。
   完整的分摊规则（重量/体积/金额 + 人工微调 + 历史版本）归 **5.2.1.3 成本分摊（未交付）**，
   本模块只开 `allocation_method='QUANTITY_PROPORTIONAL'` 一个取值，**不代它开形状**。

10. **采购价复用 catalog 同库的「实际到货金额」**。
    6.3 采购单未交付 ⇒ 用 `vendor_delivery_record`（V137）的实际到货金额按移动加权折成基础单位单价，
    来源单号是**到货单号**而不是采购单号（页面与响应都如实标注，不冒充采购单成交价）。

11. **🔴 页面路由用 `/pricing/cost` 的「到岸成本」分区，不是任务书建议的 `/pricing/landed-cost`**。
    理由三条：蓝图功能树里 5.2.1 的 L3 路由就是 `/pricing/cost`；
    台账的路由列也是 `/pricing/cost`；
    而 5.2.1.1 交付时已经在 `COST_SECTIONS` 里留了 `id:'landed'` 的**兄弟分区扩展位**
    （同款约定：分区各占一条、各有自己的端点命名空间、不重写彼此的页面骨架）。
    本车道把该分区翻成 `delivered: true` 并接上真实区块，
    **并把 5.2.1.1 的分区摘要改成点明「单据级 vs SKU 级」两个口径不合并**。

12. **api-proxy 白名单新增一条独立 `catalog/` 元素**，token 用 `landed-costs`。
    与既有全部 token（`sku-cost-compositions` / `listings` / `listing-details` /
    `listing-performance` / `listing-bulk-migrations` 等）**首 token 均不同**，不会互相吃掉；
    插入锚点固定在 5.2.1.1 `sku-cost-compositions` 那条**之后**、`channel/` 元素之前。
    `capabilities` / `comparisons` / `parameters` 三个字面量子路径**排在 uuid 分支之前**判别
    （首字母都落在 [0-9a-f] 里，写反会被当成「id=capabilities 的那张单」拼出去）。

## 关键 trade-off / 讨论

- **跨服务读数用「批次 + 来源单号」而不是「按 SKU 聚合」**：
  5.2.1.1 的 `CostBasisPort` 是按 SKU 聚合的，而到岸成本是**单据级**的
  （一张头程发运单对应一张到岸成本单，行由发运单的行派生），
  所以另建 `LandedCostPort`，**不复用** `CostBasisPort`。

- **发货单的行没有计量单位列**（V80 的 `first_mile_shipment_line` 只有数量没有 uom）：
  本模块按 **SKU 基础单位**解释这些数量（发运行的量纲由收货行带入，与 SKU 基础单位一致）；
  上游将来若区分量纲，本行的 `base_quantity` 需要跟着复核 —— 已在代码注释里写明。

- **币种不一致 ⇒ 标缺口 + 说明「无汇率表不折算」**，而不是按某个汇率折算：
  汇率是 12 财务的资产，本服务没有汇率表。

- **`recompute` 幂等**：一张发运单对应一张到岸成本单（DB 上 UNIQUE），重算是**覆盖同一张单**
  并把 `compute_count` 加一 —— 「这个到岸成本是按什么时候的头程算出来的」必须可追溯。

## 过程中修掉的真 bug（接手时测试是红的）

接手时后端骨架已存在但**测试从未跑过**。第一次跑：23 例里 1 failure + 12 errors。逐条定位后修掉：

1. **`detail()` 查询缺 `included_count` / `missing_count` 两个子查询** → 12 个 error 全部来自
   `IllegalArgumentException: Field "included_count" is not contained in row type`（列表查询有、详情查询没有）。
2. **上游不可达没有翻译成 503** → 抛的是 `LandedCostUnavailableException` 而不是
   `ResponseStatusException(503)`；在服务层加 `upstreamShipment` / `upstreamDuty` 两个包装方法翻译。
3. **测试用了非法 incoterm `DDU`** → V80 `first_mile_shipment.incoterm` 的 CHECK 只接受
   Incoterms 2010 的 11 个取值（不含 DDU，DDU 是 1990 版）；改用它的 2010 对应项 `DAP`。
4. **测试夹具把 B 租户的 SKU 建在了 A 租户**（`sku(actor, "TEN-B")`）→
   `guard_landed_cost_line_sku` 触发器把跨租户引用拒掉是对的，**夹具错了**。
   同类错误还有分页用例里 `compute(actor, shipmentB)` 传了 A 租户的 actor 去算 B 租户的发运单。
5. **测试之间数据互相污染**：整个测试类共用一个 PG 容器，而到岸成本单/行/分量/参数都是**租户级**的
   ⇒ 上一个用例维护的「清关费参数」会被下一个用例读到，「参数未维护时是缺口」这类断言莫名其妙地失败。
   ⇒ `@BeforeEach` 增加清理，**删的顺序必须是 单 → 行 → 分量**（延迟约束触发器在提交时按最终状态核对合计，
   先把单头删掉，后面几层的核对就没有可对的头了）。
6. **服务的承担方规则用错了字段**（`risk_bearer` 判费用承担方）—— 见上面第 7 条决策。
7. **服务的增值税整笔漏掉**：`otherTax = 总额 − 关税 − 增值税` 把增值税算成了 0，
   结果 5 单位的增值税既不在合计里也不在分列里 —— **整笔漏钱**。改成 `otherTax = 总额 − 关税`。

## 文件清单

**后端**

| 文件 | 说明 |
| --- | --- |
| `backend/services/catalog/src/main/resources/db/migration/V223__pricing_landed_cost.sql` | 四张新表：`landed_cost_statement`（批次；`cost_basis` 闭集 + `excluded_components` 固定值 + `allocation_method` 闭集）/ `landed_cost_line`（SKU 行；`unit_landed_cost` 为 GENERATED 列）/ `landed_cost_component`（行 × 6 分量闭集；CHECK 钉死 null ≠ 0 与缺口必须 owner_module+impact）/ `landed_cost_parameter`（清关费 / 尾程预备，按目的国+币种）；护栏触发器（SKU 引用、分量挂行、**延迟**合计与覆盖度核对）；四表 ENABLE+FORCE RLS + GRANT；**零外键、零 ALTER 既有表** |
| `backend/services/catalog/src/main/java/com/starcon/catalog/LandedCostModels.java` | DTO + `LandedCostComponent` 词表（六项闭集）+ `LandedCostBasis`（口径）+ `LandedCostGap`（未交付依赖） |
| `backend/services/catalog/src/main/java/com/starcon/catalog/LandedCostRules.java` | 纯计算：最大余数法 `allocateByQuantity`（逐字守恒）、`unitAmount`、`total`（全 null 返回 null 而非 0） |
| `backend/services/catalog/src/main/java/com/starcon/catalog/LandedCostPort.java` | 跨服务只读端口：`HttpLandedCostPort`（发运单 → 报关单 → 关税估算链路，透传 `Authorization`）+ `UnwiredLandedCostPort`（显式抛错）+ `LandedCostUnavailableException` |
| `backend/services/catalog/src/main/java/com/starcon/catalog/LandedCostConfiguration.java` | `WAREHOUSE_URL` 空 ⇒ Unwired（显式报错，不是静默空集合） |
| `backend/services/catalog/src/main/java/com/starcon/catalog/LandedCostService.java` | 主体：capabilities / list / detail / components / comparison / parameters / compute / sourceShipmentId；jOOQ 显式 SQL + `TENANT` 上下文 |
| `backend/services/catalog/src/main/java/com/starcon/catalog/LandedCostController.java` | 8 个端点：`GET /capabilities`、`GET /`、`GET /comparisons`、`GET|PUT /parameters`、`GET /{id}`、`GET /{id}/components`、`POST /`、`POST /{id}/recompute`；权限沿用 `catalog.spu.read` / `catalog.spu.update` |
| `backend/services/catalog/src/test/java/com/starcon/catalog/LandedCostRulesTest.java` | 规则单测 **7 例**（最大余数法逐字守恒、null ≠ 0、BigDecimal 精度与 scale） |
| `backend/services/catalog/src/test/java/com/starcon/catalog/LandedCostIntegrationTest.java` | 集成测试 **16 例**（Testcontainers 真实 PG16 + 强制 RLS + **连接池压到 1**）：六分量逐项可追来源单号、**缺口不是 0**、合计为空而不是 0、关税与增值税分列、承担方决定是否计入、**租户隔离三断言**、上游不可达 503、幂等重算、timestamptz 往返断言写回值 |

**前端**

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/components/pricing/landed-cost-model.ts` | 类型、端点、文案与本地校验（`landedMoneyText` 把 null 渲染成「缺口」而不是 0.00；`landedCompleteness` 明写「已知部分合计」） |
| `apps/web/src/components/pricing/landed-cost-workspace.tsx` | 真实工作区：口径声明（算了六项 / 明确不算两项 / 未交付依赖）/ 计算入口 / 筛选 / 台账（真分页）/ 明细（逐行 × 六分量，含来源单号、承担方、税额分列）/ 参数维护 |
| `apps/web/src/components/pricing/landed-cost-model.test.ts` | 模型单测（null 不渲染成 0、覆盖度措辞、分量三态、来源分色、校验） |
| `apps/web/src/app/api/catalog/landed-cost-allowlist.test.ts` | api-proxy 白名单**双向**断言：从**自己生成的契约**反向抽 path 逐条放行 + 写动作真转发到 8082 + 伪造 `X-Tenant-Id` 不转发 + **拒绝方向只用本模块前缀的形状** + **兄弟车道路由未被挤掉** + 字面量子路径不被 uuid 分支吃掉 |
| `apps/web/src/components/pricing/cost-composition-model.ts` | 改：`COST_SECTIONS` 里 `landed` 分区翻成 `delivered: true`，摘要点明「单据级 vs SKU 级」与「不含仓储费」 |
| `apps/web/src/components/pricing/cost-composition-workspace.tsx` | 改：渲染 `LandedCostWorkspace` 分区块 |
| `apps/web/src/components/pricing/cost-composition-model.test.ts` | 改：分区断言从「5.2.1.2 与 5.2.1.3 都未交付」改成「5.2.1.2 已交付且写明口径差异、5.2.1.3 仍未交付」 |
| `apps/web/src/lib/api-proxy.ts` | 改：新增**一条** `catalog/` 元素（`landed-costs`），插在 `sku-cost-compositions` 之后、`channel/` 之前 |

**契约与台账**

- `packages/api-contracts/src/catalog.openapi.json` + `packages/api-contracts/src/contracts/catalog.ts`
  （codegen 产物：**纯新增** 7 条 path 与 17 个 schema；其余 5 个服务的契约逐字节不变 ——
  channel 侧本来就有别的车道留下的未再生成漂移，本车道**复原**不碰）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 5.2.1.2 这一行**的「状态」「证据」两格

## 验证

- `./mvnw -q compile` → 退出 0
- `./mvnw -pl services/catalog -am test -Dtest='LandedCost*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **Tests run: 23, Failures: 0, Errors: 0**（规则 7 + 集成 16）
- `pnpm typecheck` → 3/3 通过
- `pnpm --filter @starcon/web lint` → 通过（本车道零告警）
- `pnpm vitest run apps/web/src/components/pricing apps/web/src/app/api` → **108 文件 663 例全绿**
  （含**全量 `apps/web/src/app/api` 回归**，确认白名单没挤掉兄弟车道路径）

## 后续待办

1. **5.2.1.3 成本分摊交付后**换掉本模块的 `allocation_method='QUANTITY_PROPORTIONAL'`：
   本模块的按数量比例摊只是**临时口径**（`allocation_method` 在 DB 里是闭集，
   5.2.1.3 交付时需要一次迁移把它扩成规则表引用）。
2. **16.3.1.1 税率表交付后**，关税的税率来源从「租户在 10.5.1.3 维护」切到统一税率表
   （本模块只读估算单，不自己算税率 —— 这一点不变）。
3. **6.3 采购单交付后**，采购价改用真实采购单成交价，并显式展示与「实际到货金额」的差额
   （照 5.2.3.1 的「费用差异」纪律）。
4. **warehouse 侧若补「按发运单聚合」的只读端点**，`HttpLandedCostPort` 可换实现，
   服务层与页面无需改动（与 5.2.1.1 的同款待办）。
5. **12 财务汇率表交付后**，跨币种对比才能给统一口径；现在跨币种**绝不相加**（页面上分行显示）。
6. `module-progress.md` 的总览计数由 `node tools/module-progress/sync.mjs` 重新统计
   （本车道按纪律只改了自己那一行）。
