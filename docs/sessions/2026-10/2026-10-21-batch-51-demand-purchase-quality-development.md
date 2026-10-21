# 2026-10-21 batch-51 —— 6.2.2.2 需求合并与批量 + 6.3.1.1 采购单列表 + 6.4.2.2 不良品处理 + 6.6.1.1 开发立项

> 批次 `batch-51`，集成头 `integration/module-dev`（并入前 `a5886c92` → 并入后 `cc0e50c7`）。
> 本批 4 条车道全部并入成功、零冲突；L2 批量验证在集成头上跑**一次**全量。
> 车道级详细决策记录在各车道自己的会话日志里（见每节「详细记录」），本文只做批次级说明。

## 本批概览

| 模块 | 名称 | 阶段 | 服务 | 迁移 | 后端端点 | 页面 | 测试 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 6.2.2.2 | 需求合并与批量 | D6 | trade | `V253__purchase_demand_merge.sql` | `/trade/demand-merges`（12 条路径） | `/purchase/demand/merge` | 后端 34 例 + 前端 33 例 |
| 6.3.1.1 | 采购单列表 | D6 | trade | `V254__purchase_order_list.sql` | `/trade/purchase-orders`（7 个端点） | `/purchase/orders` | 后端 21 例 + 前端 30 例 |
| 6.4.2.2 | 不良品处理 | D6 | trade | `V256__defective_goods_handling.sql` | `/trade/defective-dispositions`（16 个端点） | `/purchase/quality/defectives` | 后端 31 例 + 前端 20 例 |
| 6.6.1.1 | 开发立项 | D6 | trade | `V257__product_development_project.sql` | `/trade/development-projects`（16 条路径） | `/purchase/development/projects` | 后端 46 例 + 前端 17 例 |

四条车道**全部落在 trade 服务**（卷 06 后端主责服务 = trade + catalog（party），取列表第一项 = trade），
迁移版本号 **V253 / V254 / V256 / V257** 互不撞号。
⚠️ **V255 未被本批占用** —— 那是上一批预留的版本号，本批 4 条车道由 `plan` 分配时跳过了它，
**下一批的车道会从 V258 起**，不得手工指定版本号。

---

## 模块 6.2.2.2 需求合并与批量

**这个功能做什么**：采购员手里有一堆来自各处的采购需求（不同申请人、不同供应商），
不用一张一张下单，而是先批量合并 —— 但**只能同一个供应商内部合并**，
跨供应商的需求会被单独列出来并写明为什么没合上。

**技术落地**：

- 迁移：`V253__purchase_demand_merge.sql` —— **六张新表、零 ALTER 既有表、零跨库 FK / 视图**：
  `purchase_demand_merge_batch`（合并批次头，OPEN→FINALIZED 状态机）·
  `purchase_demand_merge_draft`（**采购单草稿头：一个供应商一张**）·
  `purchase_demand_merge_line`（草稿行）· `purchase_demand_merge_line_source`（行来源 append-only）·
  `purchase_demand_merge_unmerged`（**未合并行 + 明确原因码**）·
  `purchase_demand_batch_adjustment`（批量修改留痕 append-only）。
  RLS：`tenant_id NOT NULL` + ENABLE **与 FORCE** ROW LEVEL SECURITY + POLICY isolation +
  GRANT trade_app / starcon_app；三张留痕表 REVOKE UPDATE, DELETE。
- 端点：`/trade/demand-merges` —— capabilities / options / 批次清单（真分页真筛选真排序）/
  POST 批量生成合并 / `{id}` 批次详情 / `{id}/drafts` 草稿清单 / `{id}/drafts/{draftId}` 草稿详情 /
  `{id}/lines` 行清单 / `{id}/unmerged` 未合并行清单（可按原因码筛）/ `{id}/quantity` 批量改数量 /
  `{id}/supplier` 批量改供应商 / `{id}/finalize` 定稿 / POST `/print` 批量打印。
- 页面：`/purchase/demand/merge` —— 能力声明卡 → 批次清单 → 批次详情 → **按供应商分块的草稿区**
  → **未合并行区**（原因原文原样显示）→ 批量修改留痕区；
  加载态 / 错误态 / 空态三态齐全且文案各不相同。
- 测试：`PurchaseDemandMergeIntegrationTest` **30 例** + `PurchaseDemandMergeTenancyIntegrationTest`
  **4 例**（Testcontainers 真实 PostgreSQL 16，本地真 HTTP 替身：identity 权限 / warehouse 仓与法人 /
  catalog 供应商）；前端 `demand-merge-model.test.ts` **21 例** + allowlist 双向断言 **12 例**。

**关键设计决策**：

1. 🔴 **「合并只在同一供应商内进行」用四道闸钉死**（不是靠服务层自觉）：
   ① 草稿表 `UNIQUE(tenant_id,batch_id,supplier_id)`；② **DB 层触发器**
   `guard_demand_merge_line_single_supplier` 拒绝行的 supplier 与所属草稿不一致（绕服务层写 SQL 同样被拒）；
   ③ 草稿的供应商 / 目标仓 / 法人 / 币种**不可改写**（`guard_demand_merge_draft_guard`）；
   ④ 服务层改完重新校验 `count(distinct supplier_id)`，> 1 整批拒绝。
   —— 跨供应商合并会导致**对账与付款错误**，这是仓库里已有实据的事故形状。
2. 🔴 **跨供应商的需求不是被丢掉，而是进未合并行并带明确原因码 + 中文原因原文**
   （SUPPLIER_MISMATCH / SUPPLIER_MISSING / PRICE_CONFLICT / REQUEST_NOT_APPROVED / REQUEST_REJECTED /
   REQUEST_ALREADY_CONVERTED / TARGET_MISMATCH / CURRENCY_MISMATCH / ALREADY_MERGED）——
   静默丢弃会让用户以为合掉了，偷偷归到第一个供应商会**付错钱**。
3. 🔴 **「批量修改数量与供应商」整体生效或整体拒绝**：任一行不合法 ⇒ 整批回滚，
   **库里不留「改了一半」的中间态**。改供应商是「把行搬到新供应商的草稿」而不是就地改写；
   同 SKU 单价冲突 ⇒ 整批拒绝（合成一行会把其中一条需求的价格算错）。
4. 🔴 **交付的是「采购单草稿」而不是采购单**：6.3.1.2 未交付 ⇒ 绝不造一张占位的假采购单表，
   草稿与采购单是**两张单据**；能力声明 `purchaseOrderDelivered=false`，
   本模块**没有「直接下单 / 提交采购单」端点**。6.3.1.2 交付后由它读这份草稿建真正的采购单。
5. **上游只读 + 跨服务授权端口**：6.2.2.1 的表只 SELECT、不 ALTER、不建跨表外键；
   供应商走 catalog `GET /catalog/supply/vendors`（复用 `SupplierPartyQueryPort`，未接线时显式报「未接线」，
   **绝不降级成假供应商清单**），目标仓与法人走 warehouse 授权只读端口。

**详细记录**：`docs/sessions/2026-10/2026-10-21-demand-merge-batch.md`

---

## 模块 6.3.1.1 采购单列表

**这个功能做什么**：采购单的主列表 —— 单号、供应商、SKU 行数、总金额、币种、状态、承诺到货日、
**已收数量/比例**、逾期标记，支持多维筛选、导出，以及批量催单 / 关闭。
蓝图加粗句「**已收比例需实时反映 08 的收货结果**，这是采购员判断是否催货的第一指标」。

**技术落地**：

- 迁移：`V254__purchase_order_list.sql` —— **三张新表，零 ALTER 任何既有表**（跨车道 DDL 回归面 = 0）：
  `purchase_order`（头：单号 / 供应商 / 法人 / 币种 / 总金额 / 状态机 / 承诺到货日 / 目标仓 /
  负责人与部门 / 催单计数 / 乐观锁 `version`）· `purchase_order_line`（行：数量 `NUMERIC(19,6)` /
  单价 / 行金额 / 汇率快照 / `price_source`）· `purchase_order_event`（留痕，追加式，UPDATE/DELETE 被 DB 拒）；
  外加 `purchase_order_no_seq` 序列与 4 个触发器。
- 端点：`/trade/purchase-orders` —— capabilities / filters / 列表 / 详情 / export /
  POST `/from-conversions`（由 6.2.2.1 转单事实建单）/ POST `/batch/urge` / POST `/batch/close`。
  权限码复用 trade 既有 `trade.purchase.read` / `.update`（identity 零改动）。
- 页面：`/purchase/orders` —— 筛选 + 真分页 + 批量操作 + 三态。
- 测试：`PurchaseOrderListIntegrationTest` **18 例** + `PurchaseOrderListTenancyIntegrationTest` **3 例**；
  前端 `purchase-order-list-model.test.ts` **24 例** + allowlist **6 例**。

**关键设计决策**：

1. 🔴 **本模块拥有「采购单」这个聚合本身**（头 + 行 + 留痕三张表）：开工前 grep 确认
   6.3.1.2 / 6.3.1.3 **均未交付**，本仓此前**不存在任何采购单实体**；
   6.2.2.1 与 6.3.2.2 交付时都**刻意不造占位的假采购单表** ⇒ **采购单号的产出方是本模块**
   （6.3.1.1 是 L3 6.3.1 的锚点模块），口径钉死为
   `purchase_order.order_no` ↔ 6.3.2.2 的 `purchase_arrival_tracking.purchase_order_no`。
2. 🔴 **已收数量 / 比例的唯一口径 = 本库 6.3.2.2 的到货跟踪**（`purchase_arrival_tracking`），
   **不是** warehouse 的收货单 —— 两个口径不混成一个数；warehouse 收货单号只作来源快照透出。
3. 🔴 **总金额跨币种不相加**：一张采购单**只有一个币种**，列表汇总**按币种分组**返回
   （`sum(total_amount) group by currency`），**绝不**把 USD 与 EUR 加成一个数，
   也**绝不**按 1:1 或固定汇率折算；需要折算时取 catalog 的汇率台账，取不到就**只报原币种分组**。
4. 🔴 **金额与行数由 DB 从行表重算**（`refresh_purchase_order_totals` / `guard_purchase_order_line_amount`）
   ⇒ 「列表上的总金额」与「详情里各行金额之和」永远是同一个数，调用方传不进来。
   状态机同样在 DB 层钉死（`guard_purchase_order_status`），兄弟模块交付时**不必 ALTER 本表**。
5. **转单事实缺单价 / 币种 ⇒ 拒收并写明原因**（UNIT_PRICE_MISSING / CURRENCY_MISSING），
   绝不按 0 元下单，也不静默丢弃 —— 丢掉的行等于少订的货。

**详细记录**：`docs/sessions/2026-10/2026-10-21-purchase-order-list.md`

---

## 模块 6.4.2.2 不良品处理

**这个功能做什么**：来料质检判不合格之后，这批货怎么办 —— 退换货补货、返修、让步接收、报废、
转为促销品，五条去向各有各的单据、凭据与后果，处置结论联动库存状态与供应商索赔。
蓝图加粗句：**让步接收需要权限与原因，不能由质检员随手放行**。

**技术落地**：

- 迁移：`V256__defective_goods_handling.sql` —— **五张新表、零 ALTER 零 DROP 任何既有表**：
  `defective_disposition_order`（处置单头：质检结论四量快照 + **可售量上限** + 六态状态机）·
  `defective_disposition_line`（处置明细：五个去向 + **放行量** + 让步凭据 + `sort_order`）·
  `defective_inventory_instruction`（**库存状态变更指令**）·
  `defective_supplier_claim`（供应商索赔记录）· `defective_disposition_event`（事件留痕）。
- 端点：`/trade/defective-dispositions` —— capabilities / options / 列表 / summary / `{id}` 详情 /
  PUT `{id}` / `{id}/decide` 决定 / `{id}/dispatch-inventory` 派发库存指令 /
  `{id}/complete` / `{id}/cancel` / 索赔子路径 `{id}/claims/{claimId}/submit|agree|reject|withdraw`。
  另有 `DefectiveInventoryPort` 走**授权 HTTP** 派发库存指令。
- 页面：`/purchase/quality/defectives`（挂在 6.4.2.1 的 `/purchase/quality` 之下）。
- 测试：`DefectiveGoodsRulesTest` **6 例** + `DefectiveGoodsIntegrationTest` **19 例** +
  `DefectiveGoodsTenancyIntegrationTest` **6 例**；前端模型 **14 例** + 工作区测试。

**关键设计决策**：

1. 🔴 **质检结论只有一个来源**：处置单上的合格量 / 不合格量 / 让步接收量 / 扣量合计 / 可售量上限
   **全部从 6.4.2.1 质检单回读**，入参里**没有处置总量**这一栏；DB 触发器在写入时刻再核对一遍。
   让操作员再敲一遍数字 = 让同一个结论在两个服务各有一份，迟早对不上。
2. 🔴 **让步接收要权限 + 原因**：DB 层 CHECK 缺一拒绝；服务层核对记录人**真的持有**
   `trade.defective-disposition.concession` —— 这一档**比「质检结论录入」高**，质检员不能随手放行。
3. 🔴 **处置量由明细行重算**（AFTER 触发器），本模块**没有**「直接写处置量」的入口。
4. 🔴 **「可售量上限」的口径被重新解释过**：落地口径是两条同时成立 ——
   **处置量 ≤ 质检单的不合格量**（单张与累计都受约束，作废的单不占额度）；
   **可售量上限 = 合格量 + 让步接收量**被**冻结**在处置单上作为对照，处置只作用在不合格量上，
   不得侵占它（把处置量卡在「合格量 + 让步接收量」上业务上讲不通 —— 那等于只能处置判为合格的货）。
5. 🔴 **库存只发指令、不建账**：warehouse 没有可复用的处置写入口 ⇒ 指令状态如实报出，
   **绝不出现「已登记」却其实没动库存**。**12 财务不在本仓** ⇒ 索赔落 trade 本库，
   已生效冲减金额恒为 0，不假装已入账。
6. **命名撞车已规避**：trade 已有 `QualityInspection*`（6.4.2.1）与 `ReturnQuality*`（11.x），
   11.2.3.3 供应商索赔又是退货侧的 ⇒ 本模块 record / enum 一律 `Defective` 前缀，表前缀 `defective_*`。
7. **测试用「每个用例一个租户」**：共用两个固定租户会让「列表里应该只有 1 行」被别的用例残留行打穿 ——
   那不是隔离被破坏，是夹具互相污染。

**详细记录**：`docs/sessions/2026-10/2026-10-21-defective-goods-handling.md`

---

## 模块 6.6.1.1 开发立项

**这个功能做什么**：把「要开发一个新品」变成一份**有凭据、状态受控、可交接**的开发单 ——
立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、打样与量产时间计划，
状态七态（评估 / 立项 / 开发中 / 打样 / 试产 / 量产 / 终止）。
蓝图加粗句「立项不是 ERP 传统模块，但是品牌化客户的源头」。

**技术落地**：

- 迁移：`V257__product_development_project.sql` —— **七张新表、零 ALTER 零 DROP 既有表**：
  `development_project`（立项记录：编号 / 品类 / 负责人 / 目标售价 / 目标成本 / 目标毛利 / 评审结论 /
  上市时间目标）· `development_project_market`（**目标市场与平台** —— 同一款产品在不同市场不是一个价）·
  `development_project_milestone`（里程碑）· `development_project_transition`（状态流转）·
  `development_project_spec_candidate`（SKU 建档候选）· `development_project_market_observation`
  （市场观测）· `development_project_handoff`（交接）。
- 端点：`/trade/development-projects` —— capabilities / options / summary / 列表 / `{id}` 详情 /
  PUT `{id}` / `{id}/transitions` 状态流转 / `{id}/milestones` 增改删 / `{id}/markets` 增删 /
  `{id}/spec-candidates` 增删 / `{id}/market-observations` / `{id}/cost-estimate` 成本估算。
  另有 `DevelopmentCostEstimatePort` 走授权 HTTP 读成本。
- 页面：`/purchase/development/projects`。
- 测试：`DevelopmentProjectRulesTest` **20 例** + `DevelopmentProjectIntegrationTest` **23 例** +
  `DevelopmentProjectTenancyIntegrationTest` **3 例**（后端共 46 例）；前端 `development-project-model.test.ts` **17 例**。

**关键设计决策**：

1. 🔴 **状态机是结构，不是标签** —— 服务层（Java 枚举 + 显式转换规则）与数据库层
   （守卫触发器 + 事件表）**两处**都钉死；「终止」是终态；跳过打样 / 试产必须有显式豁免记录。
2. 🔴 **目标毛利由服务端算** —— 调用方**报不上**一个毛利率；成本取不到就标 unavailable，
   **绝不拿 0 顶替**（成本 0 的毛利率是 100%，是彻头彻尾的假数据）。
3. 🔴 **只产出候选参数，不建 SKU / 不建下游单** —— 3.1.1.1 建档归 catalog，
   6.6.1.2 / 6.6.2.1 未交付 ⇒ 本模块产**交接单**，不代它们建单。
4. **毛利率刻度取 6 位而不是 2 位**：仓库既有的 5.3.1.1 利润测算器就是 6 位，
   跟既有口径走比跟举例走更可落地。
5. **成本归属显式化**：`cost_owner_module` 点名归谁管，单元测试各有一条用例钉住。

**详细记录**：`docs/sessions/2026-10/2026-10-21-development-project.md`

---

## 本批跨车道共性

- 🔴 **四条车道的迁移全部「零 ALTER 既有表、零跨库 FK / 视图」** —— 跨车道 DDL 回归面 = 0，
  这是本批能在同一轮并入且 L2 一次全绿的结构性原因。
- 🔴 **静态预检（ADD COLUMN 无 DEFAULT / SET NOT NULL / 新增 CHECK）零命中**，
  命中的三处全是 worker 在迁移注释里**显式声明自己避开了这些形状**。
- 🔴 **「下游未交付」一律如实标 `available=false` + `ownerModule`**，不假装已入账、不跨库读 finance、
  不造占位的假实体表 —— 这条纪律在 6.2.2.2 / 6.4.2.2 / 6.6.1.1 三个模块里同时生效。
- **api-proxy 白名单**：四条车道都动了共享的 `apps/web/src/lib/api-proxy.ts`，
  都按协议跑了**全量 allowlist 回归**（6.2.2.2 134 文件 789 例；6.3.1.1 65 文件 471 例）。

## 本批派发（下一轮回收）

`batch-51` 在并入上述 4 条车道后，**新派发 4 条车道**（基线 `cc0e50c7`），互不依赖、
L3 锚点互不相同：

| 模块 | 名称 | 阶段 | 服务 | 迁移 | 分支 |
| --- | --- | --- | --- | --- | --- |
| 6.3.1.2 | 采购单创建与编辑 | D6 | trade | `V258` | `feat/6-3-1-2` |
| 6.3.2.1 | 跟单与交期 | D6 | trade | `V259` | `feat/6-3-2-1` |
| 7.4.1.1 | 调整单管理 | D3 | warehouse | `V260` | `feat/7-4-1-1` |
| 6.6.1.2 | 打样与样品管理 | D6 | trade | `V261` | `feat/6-6-1-2` |

四条车道任务书里点名的**跨模块陷阱**（下一轮回收时要重点看它们有没有踩）：

1. **6.3.1.2**：`purchase_order` / `purchase_order_line` / `purchase_order_event` **已由 V254 建好**
   ⇒ 再 `CREATE TABLE purchase_order` 会让 Flyway 直接炸、**整批 L2 全红**。
   `source_type` 枚举里 `'MANUAL'` 是给它的；状态机、金额重算都已在 DB 层钉死。
2. **6.3.2.1**：`replenishment_suggestion` 归 **6.2.1.2** 所有，既有代码明写「只读，绝不写它」
   ⇒ 回写补货建议必须走 6.2.1.2 / 6.2.1.1 已交付的写入入口，**不得直接写表**。
3. **7.4.1.1**：仓库里已有的 `stocktake_variance_*`（V62）是**盘点差异**的调整，不是通用调整单；
   但它的「过账落分录」机制（`guardNotNegative` → 改 `stock_bucket` → 插 `inventory_entry`）
   要**照抄**，不能绕过守恒触发器。
4. **6.6.1.2**：**12 财务不在集成头** ⇒ 样品成本落 trade 本库，费用归集如实标
   `available=false` + `ownerModule`(12 财务)；且**打样与达人寄样（14.5.1.2）必须分得干干净净**，
   混进去会让营销费用污染开发成本。

## 本批 L2 批量验证（集成头上跑**一次**全量）

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `cd backend && ./mvnw verify` | **BUILD SUCCESS**（`MVN_EXIT=0`，0 failures / 0 errors） |
| 类型 | `pnpm typecheck` | **绿**（`TYPECHECK_EXIT=0`） |
| Lint | `pnpm lint` | **绿**（`LINT_EXIT=0`） |
| 前端 | `pnpm test` | **绿**（`TEST_EXIT=0`，**391 文件 / 5931 例全过**） |
| Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **绿**（`BUILD_EXIT=0`） |
| 台账 | `node tools/module-progress/sync.mjs --check` | **绿**（`SYNCCHECK_EXIT=0`） |

⇒ **L2 GREEN**（2026-10-21 15:13:56 → 15:55:06，全程 41 分钟）。
本批**零二分定位**、零返工 —— 结构性原因是四条车道的迁移全部零 ALTER 既有表，
且静态预检（`ADD COLUMN` 无 `DEFAULT` / `SET NOT NULL` / 新增 `CHECK`）零命中。

⚠️ **本轮发现并修掉的一处基线漂移**：`sync.mjs --check` 在本批开工时**本来就是红的**
（集成头的总览计数落后于已并入车道的行状态：276 vs 实际 280）。
三条 worker 车道都按纪律**只改自己那一行、不重排整表**并如实上报了这一点
⇒ 由批次级 `sync.mjs` 统一刷新总览计数，**不归咎任何一条车道**。
刷新后：已交付 **280** · 进行中 0 · 未开发 **185** · 完成率 **60.2%**。

## 后续待办

- 下一轮回收 6.3.1.2 / 6.3.2.1 / 7.4.1.1 / 6.6.1.2 四条车道，重点核对上述四个跨模块陷阱。
- **V258–V261 已被本批派发占用**，下一批从 **V262** 起；**V255 空号**不得手工补用
  （Flyway 版本号一旦乱序会导致迁移历史不可信）。
- `trade` 服务的权限码（`trade.demand-merge.*` / `trade.defective-disposition.concession` 等）
  **未在 identity 注册** —— permission 表是 identity 的写入所有权，跨服务禁止，
  与 trade 服务全线既有模块同一口径，需要时由 identity 侧补注册。
- `finance` 服务尚未落地 ⇒ 12 财务相关能力（应付、税额、费用归集、索赔入账）持续以
  `available=false` + `ownerModule` 暴露。
