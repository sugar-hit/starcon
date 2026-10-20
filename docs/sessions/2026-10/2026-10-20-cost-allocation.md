# 2026-10-20: 5.2.1.3 成本分摊

## 摘要

交付能力蓝图 **5.2.1.3 成本分摊**（catalog 服务，阶段 D6，L3 锚点 5.2.1）。

蓝图职责是「把混合成本的货件（一次采购多个 SKU 共用头程）按重量/体积/金额/数量规则分摊到各 SKU，
支持人工微调并留痕」，输入「货件成本总额；分摊规则；人工调整值与原因」，
输出「分摊明细（每 SKU 分摊金额）；重算与差异对比」，并加粗
**「分摊规则变更会导致历史利润变化，因此调整必须留历史版本，不能就地覆盖」**。

与同 L3 的 5.2.1.1 成本构成（已交付）、5.2.1.2 到岸成本（在途）**共用页面路由 `/pricing/cost`**，
但端点与页面分区各自独立。

## 决策 / 结论

1. **「混合成本的货件」的真身 = warehouse 的头程发运单**。
   `first_mile_shipment` 是一张发运单（运费 + 保险费 + 操作费），
   `first_mile_shipment_line` 是它的行（一个 SKU 一行，带申报毛重 / 体积 / 货值）。
   **一张发运单里出现多个 SKU，就是蓝图说的「一次采购多个 SKU 共用头程」** ——
   那笔运费是几个 SKU 一起花的，必须摊到各 SKU 上。
   跨服务只读走 `CostAllocationPort`（授权 HTTP 端点，转发调用方自己的 bearer，
   **绝不发明 X-Tenant-Id**、绝不跨库 SQL / FK / 共享视图），上游读不到 → **503 报「读不到」**，
   **绝不返回空集合冒充「没有可分摊的货件」**。

2. **12 财务（finance 服务）不存在 ⇒ 分摊单与明细由本服务自持**。
   沿用仓库既有先例（10.6.1.1 物流成本分析、11.2.3.3 供应商索赔、5.2.1.1 成本构成）：
   「以本服务侧自有事实记录落地，与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接」。
   能力声明里如实写明「**这是本服务口径，不是 12 财务的 landed_cost_allocation 结果**」。
   另提供 `MANUAL` 人工货件来源（同库自持事实）。

3. **🔴 换规则 = 追加新版本，不就地覆盖**（蓝图加粗句的物理落点）。
   三张表：`cost_allocation`（分摊单 + 当前版本**指针**）/
   `cost_allocation_revision`（**只追加**的版本快照：规则 / 货件成本总额 / 生效时间 / 变更原因）/
   `cost_allocation_revision_line`（**只追加**的逐 SKU 明细行）。
   「当前的分摊明细」= 当前版本的那一组行；**没有 UPDATE 端点，也永远不会有**。
   与 5.2.1.1 的 `sku_cost_revision` 是同一条纪律 —— 5.2.1.3 回溯 5.2.1.1 的历史时口径才对得上。

4. **合计一律不落表，查询时 SUM 现算**。版本快照里**没有**任何「合计」列：
   逐行金额是唯一的事实，合计是它的聚合。落一份副本就多一处可能与逐行明细对不上的地方。

5. **分摊算术用最大余数法，保证 Σ分摊额逐分等于货件成本总额**。
   总额换算成 **0.0001 为单位的整数**，每行按占比算应得单位数（10 位小数）后**向下取整**，
   剩下的单位按**小数部分从大到小**逐个补回（并列时按 SKU id 升序 ⇒ 结果可复现）。
   分摊是**拆分**不是缩水；DB 层再加一道**延迟约束触发器**（事务提交时校验）在源头兜住。
   ⚠️ 踩过的坑：先按「分」做最大余数再换算会丢掉精度；在 0.0001 单位的整数格上做才对。

6. **🔴 基数取不到 ≠ 分摊到 0**。取不到的行（没申报重量、没维护成本构成）**不参与分母**，
   明确标「基数不可得」并写明原因；**所有行都取不到时拒绝分摊**（409），
   不摊出一张全 0 的单 —— 那会让下游以为「这个货件不用花钱」。

7. **四种基数的取数顺序：上游优先，catalog 回落**（页面上逐行写明「这一行的重量到底是哪来的」）：
   数量 = 货件行数量（上游口径；发运行**没有计量单位字段**，故不做单位折算）；
   毛重 = 货件行申报毛重（千克→克），为 0 时回落 catalog 的 `sku_package` 毛重；
   体积 = 货件行申报体积（立方米→立方厘米），为 0 时回落 `sku_package` 外形尺寸换算；
   货值 = 货件行申报货值，为 0 时回落 5.2.1.1 的 `sku_cost_revision.purchase_cost` × 数量
   （**币种必须与本单一致，不折算** —— 本仓没有汇率表，汇率是 12 财务的资产）。

8. **人工微调是逐行的增减，原因必填（DB CHECK 兜底）**。
   微调**故意**会让「逐行最终额之和」≠「货件成本总额」—— 那个差额是**人工决定的结果，不是错误**，
   所以它在响应与页面上**显式显示**（`variance`），而不是被藏起来。

9. **零个 FOREIGN KEY，引用校验全用触发器**（batch-37 的坑：给既有表加 FK 会打破同服务既有测试的
   `TRUNCATE` ⇒ 39 例 Errors）。四个 BEFORE 触发器（版本→分摊单、明细→版本且 allocation_id 一致、
   当前指针→版本、明细的 SKU 必须在本租户存在）+ 一个 DEFERRABLE 约束触发器（逐行之和 == 总额）。
   触发器只在 INSERT/UPDATE 跑，**TRUNCATE 不触发** ⇒ 既有测试的 truncate 不受影响。

10. **契约 DTO 一律加 `PricingCostAllocation` 前缀**。⚠️ 本轮真实踩到：
    首版用了 `AllocationRow` / `RevisionRow` / `AdjustmentInput` / `SourceCandidatePage` 等名字，
    结果 **5.2.1.1 的 `RevisionRow` 与 `AdjustmentInput` 在同一个 SpringDoc 里把本模块的同名 schema
    静默吃掉了**（后注册的覆盖先注册的），而 channel 10.6.1.1 早已占用 `CostAllocation*` 家族
    （codegen 的跨服务同名闸门直接中止生成）。⇒ 本模块全部 DTO 用 `PricingCostAllocation*` 前缀，
    与 5.2.1.1、channel 两边都**零重名**；契约 diff 为**纯增量**（19 个新 DTO + 8 条新 path，
    既有 schema 与 path **0 改动**）。

11. **api-proxy 白名单只新增一条独立 `services[]` 元素**（锚在数组**最末尾**，既有条目一字未动）。
    用脚本逐条核对了本文件里**全部 77 条**既有正则：`cost-allocations/**` **零命中**。
    ⚠️ 脚本本身也踩过一个坑：多行正则字面量上方的**注释行**以 `//` 开头，会被「以 `/` 开头即正则」的
    启发式误当成正则 —— 第一版脚本因此报出 8 处假命中（实际是 warehouse 那条正则的注释里写了
    `location-allocations`）。修正版跳过注释行后才得到真实的零命中结论。

12. **共享前端文件只做最小改动**。新代码全部放独立文件
    （`cost-allocation-model.ts` / `cost-allocation-section.tsx`）；
    `cost-composition-model.ts` 只把 5.2.1.3 那条分区由「未交付」改为「已交付」，
    `cost-composition-workspace.tsx` 只加一行 import + 一处渲染。
    ⚠️ 连带必须改 `cost-composition-model.test.ts` 里「5.2.1.2 与 5.2.1.3 显式标为未交付」那条断言 ——
    5.2.1.3 已交付后再标「未交付」就是**在自己的页面上画一个空框冒充没做完**；
    拆成「5.2.1.2 仍未交付」+「5.2.1.3 已交付且不得再写未交付」两条，保护意图不变。

## 文件清单

**迁移（catalog，V224 已预留给本车道）**
- `backend/services/catalog/src/main/resources/db/migration/V224__cost_allocation.sql`

**后端（catalog）**
- `.../catalog/CostAllocationModels.java`（DTO / 词表 / 输入输出模型）
- `.../catalog/CostAllocationRules.java`（纯计算：最大余数法分摊 + 差异对比）
- `.../catalog/CostAllocationPort.java`（warehouse 跨服务只读端口 + HTTP 实现 + Unwired 实现）
- `.../catalog/CostAllocationConfiguration.java`（端口装配）
- `.../catalog/CostAllocationService.java`（jOOQ 显式 SQL 的服务层）
- `.../catalog/CostAllocationController.java`（8 个端点，命名空间 `/catalog/cost-allocations`）

**测试**
- `backend/services/catalog/src/test/java/com/starcon/catalog/CostAllocationRulesTest.java`（8 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/CostAllocationIntegrationTest.java`（14 例）
- `apps/web/src/components/pricing/cost-allocation-model.test.ts`（21 例）
- `apps/web/src/app/api/catalog/cost-allocation-allowlist.test.ts`（5 例）

**前端**
- `apps/web/src/components/pricing/cost-allocation-model.ts`（新增）
- `apps/web/src/components/pricing/cost-allocation-section.tsx`（新增）
- `apps/web/src/components/pricing/cost-composition-model.ts`（共享：改 5.2.1.3 那条分区）
- `apps/web/src/components/pricing/cost-composition-workspace.tsx`（共享：一行 import + 一处渲染）
- `apps/web/src/components/pricing/cost-composition-model.test.ts`（共享：分区断言随交付状态调整）
- `apps/web/src/lib/api-proxy.ts`（共享白名单：新增一条独立元素）

**契约（codegen 产物，纯增量）**
- `packages/api-contracts/src/catalog.openapi.json`
- `packages/api-contracts/src/contracts/catalog.ts`

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（只改 5.2.1.3 那一行的「状态」「证据」两格）
- `docs/sessions/2026-10/2026-10-20-cost-allocation.md`

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` ✅（3 包绿）
- L1 模块闸：`./mvnw -pl services/catalog -am test -Dtest='CostAllocation*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **22 例绿**（单元 8 + 集成 14）
- 契约导出：`OpenApiContractTest` ✅ → `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ✅
  （**纯增量**：19 新 DTO / 8 新 path，既有 0 改动）
- 前端：`pnpm vitest run apps/web/src/components/pricing/cost-allocation-model.test.ts` → 21 例绿；
  `pnpm vitest run apps/web/src/app/api`（**全量 allowlist 回归**，因为改了 api-proxy）→ **630 例绿**
- `pnpm lint` ✅（3 包绿；我的文件零告警）

## 后续待办

1. **5.2.1.1 的「头程分摊」分项目前仍按自己的口径派生**（上游发运单按行数量摊）。
   本模块**不改**它的代码（那是 5.2.1.1 的写入范围）；两者对齐需要一条单独的车道。
2. **12 财务（finance 服务）落地后**，本模块的自持分摊口径需经授权 API / event 与
   `landed_cost_allocation` 对账，并按蓝图的「调整必须留历史版本」纪律处理口径切换。
3. **5.2.1.2 到岸成本**（在途）交付后，它与本模块共用 `/pricing/cost`：
   本模块是**货件级分摊**，它是**单据级到岸成本**，两者口径不合并，页面上仍各占一个分区。
4. 货件行数量是**上游口径**（发运行没有计量单位字段），逐 SKU 单位成本的下游换算归 5.2.1.1。
