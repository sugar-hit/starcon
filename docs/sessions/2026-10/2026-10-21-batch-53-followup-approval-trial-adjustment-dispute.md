# 2026-10-21 batch-53 —— 跟单交期 / 采购审批 / 试产单 / 调整单 / 纠纷列表 + 三条假红修复车道

> 批次 `batch-53`，集成头 `integration/module-dev`（并入前 `d53fd7b2` → 本批最终 `6bd2a772`）。
> 本批 **5 条车道全部并入成功、零冲突**：6.3.2.1 跟单与交期 / 6.3.1.3 采购单审批 /
> 6.6.2.1 试产单 / 7.4.1.1 调整单管理 / 11.4.1.1 纠纷列表。
> 另派 **3 条修复车道**，修掉 L2 暴露出来的**偶发假红测试**：
> `fix/9-6-1-3-tracking-clock-assert`（9.6.1.3）·
> `fix/9-6-1-1-config-clock-assert`（9.6.1.1）·
> `fix/7-4-1-1-line-order`（7.4.1.1 行序）。
> L2 批量验证在集成头上跑了**四轮**：前两轮各红一条（都不是本批交付代码的回归），
> 定位 → 派修复车道 → 重跑，**第四轮六个阶段全绿**。
> 车道级详细决策记录在各车道自己的会话日志里，本文只做批次级说明。

## 本批概览

| 模块 | 名称 | 阶段 | 服务 | 迁移 | 后端端点 | 页面 | 测试 | 并入 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6.3.2.1 | 跟单与交期 | D6 | trade | `V259__purchase_followup_delivery.sql` | `/trade/purchase-followups`（6 个映射） | `/purchase/followups` | 后端 27 例 + 前端 21 例 | ✅ |
| 6.3.1.3 | 采购单审批 | D6 | trade | `V262__purchase_order_approval.sql` | `/trade/purchase-order-approvals`（16 个映射） | `/purchase/approvals` | 后端 29 例 + 前端 31 例 | ✅ |
| 6.6.2.1 | 试产单 | D6 | trade | `V264__production_trial_order.sql` | `/trade/trial-productions`（18 路径 / 23 动作） | `/purchase/development/trials` | 后端 62 例 + 前端 17 例 | ✅ |
| 7.4.1.1 | 调整单管理 | D6 | warehouse | `V260__inventory_adjustment_order.sql` **+ `V261__inventory_adjustment_line_no.sql`（本批修复车道补）** | `/warehouse/inventory/adjustments`（15 个端点） | `/inventory/adjustments` | 后端 25 例 + 前端 30 例 | ✅ |
| 11.4.1.1 | 纠纷列表 | D7 | trade | `V265__platform_dispute.sql` | `/trade/platform-disputes`（15 映射 / 14 路径） | `/aftersales/disputes/platform-disputes` | 后端 31 例 + 前端 31 例 | ✅ |

**5 条车道里 4 条落在 trade、1 条落在 warehouse**；迁移版本号 **V259 / V260 / V262 / V264 / V265** 互不撞号
（并入后按服务核对：trade V1..V265、warehouse V1..V260，**无重复版本号**）。
本批新增后端测试 **173 例**、前端测试 **130 例**；
三条修复车道另新增后端测试 **3 例**（各 1 条确定性回归用例）。

> ⚠️ **V263 未被本批占用**（V263 是 6.3.2.1 上一轮 plan 的过期预分配）。
> 6.3.2.1 上一轮冲突重派时**沿用了它原本的 V259** 而不是新建 V263（V259 与集成头已有的 V258 无冲突）。
> **下一批的车道从 V266 起**（warehouse 下一个是 V262），不得手工指定版本号。
> ⚠️ **V261 在两个服务里各有一份、含义不同**：trade 的 `V261__product_development_sample.sql` 是 6.6.1.2 的；
> warehouse 的 `V261__inventory_adjustment_line_no.sql` 是本批修复车道新建的。**两个服务各自独立库，互不冲突** ——
> 但**不要手工把某个服务的 V262 改成别的号**。

### 并入时的两处「行级兜底并集」已逐条核对

`merge-ready` 报了两条 `merge-union: 走了行级兜底并集，请人工确认这个文件改对了`（`packages/api-contracts/**`）。
本轮对这两处做了**独立取证**，不是靠 merge 脚本自证：

| 文件 | 核对方式 | 结论 |
| --- | --- | --- |
| `apps/web/src/lib/api-proxy.ts` | 取并入前 `d53fd7b2` 与并入后 `5cf36a0f` 两版逐行 diff；把 warehouse 那条被改写的超长正则**抽出顶层 alternation 分支做集合比对** | 整文件 **+269 行 / -1 行**，那 1 行是 warehouse 分支正则的**就地扩写**；顶层分支 **old=111 / missing_in_new=0** ⇒ **纯新增，无既有路由被删** |
| `packages/api-contracts/src/{trade,channel,warehouse,catalog,identity}.openapi.json` | 逐份解析 JSON，比对 `paths` 键集合 | 5 份**全部 JSON 合法**；trade 668→720（+52）、warehouse 489→503（+14）、其余三份 +0；**missing=0** |

⇒ 白名单与契约**没有静默丢路由**（那是 typecheck / lint / vitest / Playwright 全绿也查不出来的形状）。

---

## 模块 6.3.2.1 跟单与交期

**这个功能做什么**：采购员能对一张在途采购单**登记跟单记录**、**登记交期变更**，
并在一张时间线上看到「跟单记录 + 交期变更」合并后的实况，以及**延迟天数 / 准时率 / 已逾期**的统计。

**技术落地**：

- 迁移：`V259__purchase_followup_delivery.sql` —— **三张新表 + 2 个序列 + 追加式触发器 + RLS + GRANT**，
  **零 ALTER / 零 DROP 任何既有表**（三张表 `tenant_id NOT NULL` + ENABLE/FORCE RLS isolation）。
- 端点：`/trade/purchase-followups` —— GET `/capabilities`（能力声明）· GET/POST `/changes`（交期变更历史 / 登记变更）·
  GET `/timeline`（跟单 + 变更合并时间线）· GET `/stats`（延迟统计）· GET `/delivery`（逐单交付实况，真分页）。
- 页面：`/purchase/followups`（`app/purchase/followups/page.tsx` + `components/purchase/purchase-followup-workspace.tsx`
  + `purchase-followup-model.ts`）—— 四个视图 + 两个登记对话框 + 加载 / 空 / 错误三态。
- 测试：`PurchaseFollowupIntegrationTest` **23 例** + `PurchaseFollowupTenancyIntegrationTest` **4 例**（租户隔离三断言 + SELF 数据范围）
  + 前端 `purchase-followup-model.test.ts` **14 例** + `purchase-followup-allowlist.test.ts` **7 例**（白名单双向守卫，真跑 proxy）。

**关键设计决策**：

1. **两个承诺到货日口径并存、页面同时显示**，而不是二选一。`documentPromiseDate` 读采购单上的正式承诺（归 6.3.1.1，本模块只 SELECT）；
   `followupPromiseDate` 读本模块交期变更历史里最后一条变更的 `to_promised_date`。
   两边不一致时响应里给 `promiseDiverged=true`，页面两个日期并列摆出来 —— **分叉被显式呈现，不被藏起来**。
2. **不改 `purchase_order.promised_arrival_date`**：V254 注释写死「6.3.2.1 跟单改的是它的历史」；
   6.3.1.2 未交付 ⇒ 本仓不存在合法的写入口；且 V254 的状态机守卫会冻结 CLOSED 单据的该列。
   ⇒ 本模块**零 ALTER、零 UPDATE 别人的表**，交期变更落在自己的历史表。
3. **延迟天数 = 实际到货日 − 到货时刻生效的承诺日**，可为负（提前到货）。
   多次改期时按「到货时刻生效的那一版承诺日」算，**不是**按原始承诺日算
   （3/1 答应 3/10 到、3/5 改成 3/20、3/18 到货 ⇒ 提前 2 天 = 准时；按原始日算会误判成逾期 8 天）。
   未到货的单不算「延迟天数」（延迟必须有终点），另计「已逾期天数」；**平均延迟在无已到货样本时是 null 而不是 0**。
4. **回写 6.2.1.1 / 6.2.1.2 只调用它们自己的服务方法**，不 INSERT / UPDATE 对方的表（写入所有权与不变量仍归原模块）。
   6.2.1.1 得 `available=true + delivery_input_applied=true`（备货周期 = 承诺交期天数 + 延迟缓冲）；
   6.2.1.2 得 `available=true + delivery_input_applied=false` —— 它的供应商交期取自 catalog 报价与**人工策略**，
   自动写人工策略会**顶掉**人工维护的起订额与币种 ⇒ 只触发重算并把这个限制如实报出。**这是一处诚实的「做不到」，不是假装做到。**
5. **交期变更与回写分三个独立事务**：变更落库 → 回写 6.2.1.1 → 回写 6.2.1.2。回写失败**不回滚**已落库的变更，
   只补一条 `FAILED` 留痕（含失败原因与影响说明）⇒「回写了没有」是可查的事实，不是一句返回值。
6. 时间用 `OffsetDateTime` 绑定 + values 位置 `?::timestamptz`（`Timestamp.valueOf(LocalDateTime)` 会**静默偏 −28800s**）；
   延迟天数是整数天，平均延迟用 `BigDecimal` 两位小数 + `numeric`，**不是浮点**。

---

## 模块 6.3.1.3 采购单审批

**这个功能做什么**：采购单提交后进入审批 —— 采购员**不能审自己提交的���**，
审批人按**金额阈值与采购员分级**判定需要几级审批，支持**批量审批**、**驳回并必须附意见**；
审批通过后单据**锁定数量与单价**，并登记应付、放行收货。

**技术落地**：

- 迁移：`V262__purchase_order_approval.sql` —— **七张新表 + 2 个序列 + 3 个函数 + 3 个触发器**，
  全部 `tenant_id NOT NULL` + RLS；对 V254 `purchase_order_line` 的唯一跨表增量是**新增一个 BEFORE UPDATE 触发器**（零 ALTER 列 / 约束 / 既有触发器）。
- 端点：`/trade/purchase-order-approvals` —— GET `/capabilities`· GET/POST `/rules` + PUT `/rules/{id}` + POST `/rules/{id}/status`（规则配置）·
  GET `/members` + POST `/members/buyers` + POST `/members/approvers`（采购员分级与审批人）·
  GET `/orders` + GET `/orders/{id}` + POST `/orders/{id}/approve` + POST `/orders/{id}/reject` + POST `/orders/batch`（审批）·
  GET `/payables` + POST `/payables/{id}/confirm` + POST `/payables/{id}/reject`（应付登记与生效确认）。共 16 个映射。
- 页面：`/purchase/approvals`（`app/purchase/approvals/page.tsx` + `components/purchase/purchase-order-approval-workspace.tsx` + `purchase-order-approval-model.ts`）。
- 测试：`PurchaseOrderApprovalIntegrationTest` **26 例** + `PurchaseOrderApprovalTenancyIntegrationTest` **3 例**
  + 前端 `purchase-order-approval-model.test.ts` **22 例** + `purchase-order-approval-allowlist.test.ts` **9 例**。

**关键设计决策**：

1. **审批规则放 trade 侧，不委托给 identity 的通用审批流** —— 任务书说「17 组织的审批规则服务不存在」，实查发现 identity 侧**确实有**一套通用审批，
   但蓝图那句「按金额阈值与**采购员分级**审批」在 identity 的规则模型里**表达不出来**（它的维度是单据类型 / 金额区间 / 跨法人 / 跨部门 / 新供应商 / 高风险商品，
   **没有「采购员分级」，也没有「审批人的金额授权区间」**）；要表达就得改 identity 的规则模型 —— 那是另一个服务的写入所有权（AGENTS.md 明令禁止）。
   ⇒ 本模块自持三张配置表，并在能力声明里把这条写清（`identityApproval` 标 `available=false` 并点名原因），**而不是假装「17 没这个能力」**。
2. **命中判定只有一处实现**：服务端与 DB 触发器**共用同一个 SQL 函数** `purchase_order_approval_match(tenant, order)`。
   两侧各写一份 ⇒ 规则改一次就漂移，而漂移的表现是「审批人批的和自己看到的不是同一档」。
3. **审批链在提交那一刻冻结**（DB 触发器 `purchase_order_approval_case_after_submit`）：
   「这一单要几级审批」是**审计事实**，事后规则一改它不能跟着变。已核对副作用：6.3.1.2 的 `submit()` 只 UPDATE 单头、**不动行表** ⇒ 不打断它任何行为。
4. **「审批通过后不可改数量与单价」落成 DB 触发器，不落成服务层校验** —— 服务层能被别的写入口、脚本、直接 SQL 绕过，
   而「已批的单子改数量」是**会算错钱**的那一类错误。测试**直连 SQL** 断言被拒。
5. **四条硬纪律**（服务层 + DB 层同时钉死）：不能自审；一个人在一张单上只批一次（`UNIQUE(tenant_id,case_id,approver_user_id)` 兜底）；
   「没配规则」走默认档（L1、1 人）而不是「当作已批」；**驳回必须附意见**。
6. **批量逐条给结论，且失败的那张不留半截写入** —— 每张单开一个 JDBC 保存点，失败那张回滚到保存点，
   不会出现「审批留痕已写、审批单没推进」被提交进库。
7. **权限码分四档**（`trade.purchase.read` / `.update` / **`.approve`** / `.finance`）——
   **谁能把单批成已批，不该由「能改这张单」顺带获得**。
8. **「还没发生」用状态表达，不用空对象** —— 应付登记与收货放行永远返回一个对象，`status='NONE'` 表示「还没登记 / 还没放行」，
   金额是 **null 而不是 0**（0 会被读成「这笔应付是 0 元」）。

---

## 模块 6.6.2.1 试产单

**这个功能做什么**：小批量试产能被真的管起来 —— 建试产单、挂 BOM 版本、按组件清单登记**领用需求**、
登记质检项、录试产结果（**良率由库推导**）、记试产成本，并与**量产目标成本**做同币种对比，最后产出**转量产建议**。

**技术落地**：

- 迁移：`V264__production_trial_order.sql` —— **八张新表 + 守卫触发器 + RLS + GRANT**，零 ALTER 既有表。
- 端点：`/trade/trial-productions` —— GET `/capabilities`· `/options`· `/summary`· `/bom-version-candidates`·
  GET/PUT `/{id}`· POST `/{id}/transitions`· GET/POST `/{id}/component-requirements` +
  `/{requirementId}/submit` + `/{requirementId}/release`· GET/POST/PUT `/{id}/inspection-items`·
  GET/POST `/{id}/result`· GET/POST/DELETE `/{id}/cost` + `/cost/lines`· GET `/{id}/cost-comparison`·
  GET/POST `/{id}/mass-production-recommendation`。共 18 条路径 / 23 个动作。
- 页面：`/purchase/development/trials`（`app/purchase/development/trials/page.tsx` + `components/purchase/production-trial-workspace.tsx` + `production-trial-model.ts`）。
- 测试：`ProductionTrialRulesTest` **28 例** + `ProductionTrialIntegrationTest` **31 例** + `ProductionTrialTenancyIntegrationTest` **3 例**
  + 前端 `production-trial-model.test.ts` **10 例** + `production-trial-allowlist.test.ts` **7 例**。

**关键设计决策**：

1. **「试产消耗真实组件库存，需走 8 加工单而非直接改库存」按加粗硬约束处理** —— 本模块**只产出组件领用需求**，
   **不建库存扣减表、不改 warehouse 库存、不提供「扣减」动作**。`RELEASED` 记的是 8.4.1 组装单的**逻辑引用 + 单号快照**（跨服务不建 FK、不跨库写）。
   能力声明如实标 `inventoryDeductionAvailable=false` / `assemblyOrderHandoffAvailable=false`；
   集成测试逐列断言需求表里**没有任何库存结存 / 已扣减 / 已领用列**。
2. **BOM 走既有跨服务端口，不自己重算** —— 需求量**原样搬运 catalog 的 R6/R7**（`单位用量 × 产量 × (1 + 损耗率/100)`，6 位 HALF_UP，不取整）。
   两处各实现一遍必然漂移，而漂移的后果是「按不同口径领料」。
   **新增自己的端口 `ProductionTrialBomPort`** 而不是改 `BomVersionPort`（那是 6.4.1.1 的文件，多车道并入同一个文件必冲突）。
3. **良率由数据库推导，客户端报不上来** —— BEFORE 触发器按 `合格 ÷ 产出` 算良率、按 `产出 − 合格` 算不良数量，客户端报的值一律被覆盖；
   输入模型里**根本没有良率字段** ⇒ `fail-on-unknown-properties` 直接 400。良率是**比例**（`numeric(19,6)`，0.95 = 95%）。
4. **「试产成本 vs 量产目标成本」只做同币种可比** —— 一张试产单一个币种；跨币种按币种分组、**不做固定汇率折算**（5.2.2.1 未交付）；
   量产目标成本取不到就是 **NULL（不是 0）** ⇒ 概览与详情都标 `comparable=false` 并说明原因。
5. **转量产建议是「建议事实」，不是 SKU 建档** —— 6.6.2.2 未开发 ⇒ 只落一条建议（结论 + 建议文本 + 生成条件 + 生成时实测快照），**不建 SKU 表、不调商品服务写接口**。
   「建议转量产」需要**举证**（良率达标 + 成本门槛达标，条件不达标直接 400 并报出实测值与门槛）；「暂不建议转量产」不需要举证。
6. **状态机服务层 + 数据库层两处钉死**，同一张 CASE；DB 守卫额外要求：开产前领用需求必须全部提交、完成前必须有试产结果、完成前质检项必须全部判定完毕。
7. **服务层先挡一道给可读 400，DB 守卫保留为第二道防线** —— 只靠 DB 守卫时「还有 2 条领用需求未提交」会变成 23514 约束冲突，
   被翻译成一句笼统的「数据与既有记录冲突」。
8. **测试断言不依赖「同刻两行的先后」** —— 成本明细行按 `(incurred_at, id)` 排序而 id 是随机 UUID ⇒ 同一时刻两行先后不稳定；
   初版断言「删掉第 0 行后合计是 30」变成一个**偶发失败**的 flaky 用例（实测第 3 轮跑出来过一次）。
   改成**按金额找到要删的那一行**再断言合计 —— 断言的是「删行 ⇒ 合计重算」这件事本身。

---

## 模块 7.4.1.1 调整单管理

**这个功能做什么**：库存有差异时（盘盈 / 盘亏 / 损坏 / 报废 / 错发纠正），运营能**开一张调整单**、加行、挂凭证、
提交、**批准**、**过账**（过账才真的改库存余额，分录立刻出现在库存流水台账里）。

**技术落地**：

- 迁移：`V260__inventory_adjustment_order.sql` —— **五张新表 + RLS（ENABLE **FORCE**）+ 索引 + GRANT/REVOKE + 九道护栏触发器**，零 ALTER 既有表。
- 端点：`/warehouse/inventory/adjustments`（15 个端点：capabilities / 列表 / 建单 / 详情 / 加行 / 删行 / 挂附件 / 摘附件 /
  submit / approve / reject / cancel / post）+ `/warehouse/inventory/adjustment-sources/defective-dispositions`（2 个只读投影端点，跨服务读 trade）。
- 页面：`/inventory/adjustments`（`app/inventory/adjustments/page.tsx` + `components/inventory/inventory-adjustment-workspace.tsx` + `inventory-adjustment-model.ts`）。
- 测试：`InventoryAdjustmentIntegrationTest` **21 例** + `InventoryAdjustmentTenancyIntegrationTest` **3 例**（租户隔离三断言实跑）
  + 前端 `inventory-adjustment-model.test.ts` **22 例** + `inventory-adjustment-allowlist.test.ts` **8 例**。
- 连带改动：`InventoryBucketService` 把 ADJUST 动作的 `available` 由 false 翻成 true（7.1.1.2 的代码注释逐字写着「目标模块交付时只要改这里的 available」）。

**关键设计决策**：

1. **新建五张表而不是复用 V62 的 `stocktake_variance_*`** —— V62 的调整单头表有 NOT NULL `sheet_id`（必须有盘点表才能建单），
   而本模块处理的是损坏 / 报废 / 错发纠正这类**通用调整**，它们根本没有盘点表。
   硬复用等于把「盘出来的」和「盘不出来的」塞进同一张单据，而审批人恰恰要靠这个判断风险（盘出来的有账面数兜底，盘不出来的没有）。
   ⇒ 零 ALTER 既有表，跨车道 DDL 回归面 = 0。
2. **「调整必须走单据」这条蓝图红线落在 DB 层** —— 九道触发器钉死：状态机 / 入账闸门（未 APPROVED 不得写分录）/ 入账数量守恒 /
   行不可改 / 单不可删 / 审计 append-only / 行六维与 bucket 一致 / 只有草稿能删行 / 只有草稿能挂摘附件。应用层可以被绕过，DB 层不会。
3. **状态枚举一次写全六个**（DRAFT / PENDING_APPROVAL / APPROVED / REJECTED / POSTED / CANCELLED）⇒ 7.4.1.2 交付时不必 ALTER 本表。
4. **不建「调整流水表」** —— 分录落既有 `inventory_entry`，7.1.2.1 流水台账自然可见（集成测试实跑 `GET /warehouse/inventory/ledger?refTypes=INVENTORY_ADJUSTMENT` 断言）。
   再建一张就是第二个口径。
5. **审批动作归本模块、阈值规则归 7.4.1.2** —— 本模块交付的是「执行批准这一动作」（`POST {id}/approve` + 必填理由 + 提交人 ≠ 审批人的 DB CHECK），
   阈值规则（金额 / 数量阈值、批量审批、驳回工作流、17.4 审批中心接入）在能力声明里恒 `available=false` + `ownerModule=7.4.1.2`。
   理由：没有批准动作，7.4.1.2 就没有可审批的对象；蓝图列的本模块输出也明写着「调整单（草稿 / 待审 / 已批 / 已执行）」。
6. **过账时再校一次 `expected_version`** —— 建单时校一次不够：一张单从建单到过账可能隔很久，「拿旧数字改新余额」是这条路径最典型的翻车方式。
7. **原因码是受控词表（6 个），且是全局参考数据**（`inventory_adjustment_reason` 表**没有 `tenant_id` 列**，RLS 显式关闭、只 `GRANT SELECT`）——
   它是跨租户共用的码表，租户隔离靠 order 表而不是靠码表；按租户分行会让「原因码 CHECK 与词表」两处真相漂移。
   ⚠️ **这是全仓第一处 `DISABLE ROW LEVEL SECURITY`**（其余迁移一律 ENABLE + FORCE）。理由已写在迁移第 674–678 行。
   **建议人工复核这一处口径** —— 若将来要统一成「所有表都带 tenant_id」，这里需要改设计而不是改这一行。
8. **平台仓差异（`PLATFORM_VARIANCE`）保留词表码但建行时显式 422 拒绝** —— V55 的 `guard_platform_warehouse_no_ledger` 禁止给 `PLATFORM_FBA` 仓写 balance / bucket 行，
   平台仓根本没有可调的 `stock_bucket` 行可调；把平台观测值当成自货调整掉会制造「平台仓也有自有账本」的第二个真相。
9. **路径上刻意不放 DELETE** —— 调整单不可删（作废留痕），删行与摘凭证都是显式动作后缀（`POST .../remove`），
   且数据库只允许**草稿**单删行/摘凭证。放行 DELETE 会让「能不能把一条已提交的调整痕迹抹掉」看起来是能。

---

## 模块 11.4.1.1 纠纷列表

**这个功能做什么**：平台纠纷（Amazon A-to-Z / eBay 争议 / 速卖通纠纷）能被真的管起来 —— 登记纠纷单与金额、
**盯举证时限倒计时**、维护举证清单、记录我方立场与结果、登记结算影响。
**超期自动判败**是硬性时效要求，本模块把它做成了**库层强制**而不是界面上一个过期标签。

**技术落地**：

- 迁移：`V265__platform_dispute.sql` —— **四张新表 + 十个守卫 + RLS + GRANT + 索引**，零 ALTER 零 DROP 既有表。
- 端点：`/trade/platform-disputes` —— GET `/capabilities`· GET `/{id}`· GET/POST/PUT/DELETE `/{id}/evidence-items`（举证清单）·
  POST `/{id}/start-evidence`· POST `/{id}/position`（我方立场）· POST `/{id}/submit-evidence`· POST `/{id}/resolve`· POST `/{id}/withdraw`·
  POST `/{id}/settlements` + `/{settlementId}/confirm` + `/{settlementId}/reject`。共 15 个映射 / 14 条路径形状。
- 页面：`/aftersales/disputes/platform-disputes`（`app/aftersales/disputes/platform-disputes/page.tsx`
  + `components/aftersales/platform-dispute-workspace.tsx` + `platform-dispute-model.ts`）。
- 测试：`PlatformDisputeIntegrationTest` **27 例** + `PlatformDisputeTenancyIntegrationTest` **4 例**
  + 前端 `platform-dispute-model.test.ts` **26 例** + `platform-dispute-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. **「超期自动判败」落成 DB 侧触发器守卫①，不是服务层的软判定** ——
   `evidence_deadline timestamptz NOT NULL`（真实时刻列，不是「7 天」这种相对描述）；
   守卫①挂在 **`BEFORE INSERT OR UPDATE`**（刻意不是 `UPDATE OF dispute_status`）：
   只要 `evidence_deadline <= now()` 且**尚未提交举证**，就把状态强制改写成 `RESOLVED_LOST` + `resolution_code='DEADLINE_EXPIRED'` + `resolved_at=now()`。
   ⇒ **任何一次写入**（哪怕只是更新一句备注）都会触发判负，**绕过服务层直连 SQL 也一样**。
   软判定的致命处：直连 SQL 写进去的那一行仍然是「举证准备中」，而这行是要拿去跟平台对质的凭据。
2. **倒计时是派生态，库里没有「剩余小时数」列** —— 那一列写下去就开始自己过期。
   剩余秒数与时限状态在**读取时刻**由 `evidence_deadline - now()` 算出；测试直接查 `information_schema.columns` 断言没有这一类列。
3. **举证时限只能收紧不能放宽**（放宽等于给自己续命）· 超期后不可再提交举证 · 超期后举证清单不可再改 · 建单只能从「已立案」开始
   （补上状态机在 INSERT 上的盲区，否则直连 SQL 能凭空插一条「我方胜诉」）。
4. **服务层只做「读时收口」** —— `expireOverdueDisputes()` 用与守卫①**完全相同**的判据在读列表/详情前把结论显形并写回，
   否则「自动判负」要等下一次**别的**写入才会显形，界面上会一直显示「举证准备中」。
   **最终结论由 DB 写进那一行**，服务层不自己判一遍。
5. **平台同步上游缺失 ⇒ 不假装有个平台推送接口，也不 mock 一个「Amazon 拉到的纠纷」** ——
   grep 取证：channel 服务**零** `dispute` 命中；crm 只有索评与知识库，客服工单模块全部未开发。
   ⇒ 本模块提供**自己的建单入口**，平台来源信息以**快照**落库（平台码 + 平台名 + 平台纠纷号 + 平台订单号）；
   平台码用 CHECK 钉死形状（照抄 catalog V145 的 `^[A-Z0-9][A-Z0-9_-]{0,31}$`）。
   「与 channel 自动同步的衔接」作为**下游缺口**写进能力声明（`caps.platformSyncGap`），前端也在「模块边界」卡片里如实显示。
6. **finance 服务不存在 ⇒ 「争议金额影响结算」照抄 11.2.3.3 的先例** 落成 trade 侧自有事实记录 `platform_dispute_settlement`：
   登记额与生效额**分两列**，登记时生效恒为 0；表级 CHECK 钉死「未 CONFIRMED ⇒ effective = 0；
   CONFIRMED ⇒ effective = requested 且确认人 / 确认时刻 / 财务单据号三样齐全」；纠纷单头上的口径由守卫④**从分录重算**（直连 SQL 也写不进假的「已生效」）。
   财务确认用**独立权限码** `trade.aftersales.finance`。
7. **结算状态拆成两个枚举**（`PlatformSettlementStatus` = 分录口径 / `PlatformSettlementState` = 纠纷单头口径），**刻意不合并** ——
   合并就会出现「分录是 CONFIRMED，头上的状态也叫 CONFIRMED」这种两处都读得通、却在同一张单据上出现两个答案的口径。
8. **前端倒计时每秒由绝对截止时刻现算**，不用接口返回的 `secondsRemaining` 快照 —— 快照是「请求那一刻」的，页面停着不动就会慢慢变成假数据。

---

## L2 批次闸：两轮

### 第一轮（18:06 → 18:45）：**六阶段里五绿一红**

| 阶段 | 退出码 | 结果 |
| --- | --- | --- |
| `backend && ./mvnw verify` | **1** | ❌ trade 2132 例红 1 例 |
| `pnpm typecheck` | 0 | ✅ 3/3 turbo tasks |
| `pnpm lint` | 0 | ✅ |
| `pnpm test` | 0 | ✅ **405 文件 / 6110 例全绿** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 0 | ✅ |
| `node tools/module-progress/sync.mjs --check` | 0 | ✅ |

后端各模块：identity **270** ✅ / catalog **1981** ✅ / channel **1266** ✅ / warehouse **1612** ✅ / trade **2132**（**红 1**）

**红的那一条**（全仓唯一红）：

```
com.starcon.trade.DropshipTrackingIntegrationTest.建跟踪时两段各自独立_互不预填:428
[承诺发货时限是延迟判定的唯一基准，必须真的写进去]
expected: 2026-10-23T10:40:26Z
 but was: 2026-10-23T10:40:25Z
```

### 定位：不是本批回归，是**存量测试脆弱性**

三条独立取证：

1. **本批 5 条车道没有一行文件碰过 dropship** ——
   `git diff --name-only d53fd7b2..5cf36a0f | grep -i dropship` ⇒ **空**。
2. **隔离复跑 3 次，29 例全绿**（0 失败）⇒ 时序相关，不是稳定缺陷。
3. **根因在源码里看得到** —— `DropshipTrackingIntegrationTest.at()` 每次都重读 JVM 墙钟并截断到整秒；
   出问题的用例把 `at(2)` **调了两次**（第 419 行写库、第 428 行断言），中间隔一次 DB 往返。
   两次调用一旦**跨过整秒边界** ⇒ 写入值比断言值早整 1 秒 ⇒ 假红。
   失败概率 ≈ 两次调用间隔 ÷ 1 秒 ⇒ 每轮全量有百分之几的概率炸。
   ⚠️ **同一个文件里已经有正确写法**（第 1002–1019 行 `时间往返不发生静默偏移`：先取到局部变量、再写、断言用同一个局部变量）。

⇒ 与 batch-38 那次（`fix/4-3-1-1-clock-assert`）**是同一类根因**（断言基准与写入值不共用同一个时刻来源）。

### 处置：派**两条**修复车道

按协议「定位到具体车道后，派一个**修复任务**（只改这一个模块，不要重写）」：

**车道 A —— `fix/9-6-1-3-tracking-clock-assert`（模块 9.6.1.3 代发履约跟踪与异常）**

- worktree `.worktrees/9.6.1.3-fix`，commit `3ac5a282`，**只改测试**：不新增迁移、不改业务代码、不动契约、不动前端。
- 修法：把 `at(2)` 取一次存进局部变量，建单与断言共用同一个值（**照该文件第 1002 行既有的写法**）。
- 按本仓先例（`8dfc5b8f`）的证明标准新增一条**确定性**回归用例
  `承诺时限只取一次时钟_跨整秒边界现场下依然成立`：把测试时钟抽成可钉死的 `clockSource`，
  钉死成「每次读取整整晚 1 秒、永远停在整秒边界前 1ns」⇒ **任意两次相邻读取**截断到秒后必差整 1 秒
  ⇒ 确定性地造出旧写法必翻车的现场；`assertThatThrownBy` 钉死**旧写法红**、**新写法绿**。
- **变异验证**：把辅助方法改回「取两次时钟」⇒ 回归用例立刻红，失败形状与 L2 那次一致；变异已回滚。
- **连跑 5 轮**，每轮 30 例全绿（原 29 + 新增 1）。
- 台账只追加 9.6.1.3 那一行的「证据」格，**状态格保持「已交付」不变**；证据格内**禁用半角 `|`**（`sync.mjs` 的行正则遇 `|` 即止）。

**车道 B —— `fix/9-6-1-1-config-clock-assert`（模块 9.6.1.1 代发模式配置）**

车道 A 的取证**顺带发现了同一形状的第二处**：`DropshipConfigIntegrationTest`
第 944/945 行写入 `at(-3)` / `at(30)`，第 955/957 行**各再取一次时钟**来断言 ⇒ 同一个用例里两处竞态，
**偶发失败概率比车道 A 修的那处更高**。车道 A 按「只改本模块」的硬约束**只取证上报、没有动**。
⇒ 调度者据此单开车道 B 修掉它，让**两条修复与本批 5 个模块在同一次 L2 里一起被验**，而不是把它留给下一轮再赔一次 L2 周期。

车道 B 的交付：commit `2e214854`，同样**只改测试**（`DropshipConfigIntegrationTest.java` + 台账 9.6.1.1 那一行 + 会话日志）。
- 修法：`at(-3)` / `at(30)` **各取一次**存进局部变量，写入与断言共用同一个值。
  ⚠️ `effective_from` 与 `effective_to` 是**语义不同**的两个值（起 / 止），**各自取一次**、**不合并成一次读取**。
- 同样新增一条**确定性**回归用例（同样的 `clockSource` 钉死手法）：**连跑 5 轮**，每轮 **25 例全绿**（原 24 + 新增 1）。
- **变异验证**：把写法改回「写入取一次、断言再取一次」⇒ 回归用例立刻红（`expected: ...11:13:18Z / but was: ...11:13:16Z`，形状与 L2 那次一致）；变异已回滚。
- 核对结论：该文件共 **19 处** `at()` 调用点，**只有 2 处**是「写入值与断言值各取一次时钟」，其余 17 处要么只取一次、
  要么本来就取到局部变量、要么成对传入的是不同语义的时刻。
- 台账只追加 9.6.1.1 那一行的「证据」格，**状态格保持「已交付」不变**；该行半角 `|` 数量 **7 → 7（未变）**。
- 两条修复车道的 PG 容器：**峰值各 1 个**，跑完 `docker ps -a --filter "ancestor=postgres:16-alpine"` 为空 ⇒ **已被 ryuk 回收干净**。

### 第二轮（两条修复并入后，19:20 → 19:44）：**又红一条，但换了个地方**

| 阶段 | 退出码 | 结果 |
| --- | --- | --- |
| `backend && ./mvnw verify` | **1** | ❌ warehouse 1612 例红 1 例（trade / crm 未跑到） |
| `pnpm typecheck` | 0 | ✅ |
| `pnpm lint` | 0 | ✅ |
| `pnpm test` | 0 | ✅ **405 文件 / 6110 例全绿** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 0 | ✅ |
| `node tools/module-progress/sync.mjs --check` | 0 | ✅ |

后端各模块：identity **270** ✅ / catalog **1981** ✅ / channel **1266** ✅ / warehouse **1612**（**红 1**）

**红的那一条**（本批自己的模块）：

```
com.starcon.warehouse.InventoryAdjustmentIntegrationTest.一次建单多行_批量调整落到同一张单:475
Expecting map: {... "locationCode"="A-02" "lotCode"="" "skuCode"="SKU-1" ...}
to contain entries: ["locationCode"="A-01"]
but the following map entries had different values: ["locationCode"="A-02" (expected: "A-01")]
```

⇒ **同一个断言上一轮是绿的** ⇒ 又一个偶发。

**根因（调度者定位到行）**：服务端返回的**行顺序不确定** ——
`InventoryAdjustmentService.java` 第 **314** 行（详情读行）与第 **752** 行（过账前置读行）都是
`... order by l.id`，而 `inventory_adjustment_order_line.id` 是**随机 UUID 主键** ⇒ 同一张单的两行谁在前**每次都可能不一样**；
而测试断言的是**索引位置**（`lines.get(0)` 期望 A-01、`lines.get(1)` 期望 A-02）⇒ **约 50% 概率红**。
表里**没有 `line_no` 序号列**（已核对 V260 的表定义）。

⚠️ 与 6.6.2.1 试产单车道当初遇到的**同一形状**（「成本明细行按 `(incurred_at, id)` 排序而 id 是随机 UUID ⇒ 断言第 0 行 ⇒ flaky」）。

### 第三轮：派**第三条**修复车道（`fix/7-4-1-1-line-order`）

- 模块 7.4.1.1 调整单管理（warehouse）——**本批次刚交付、尚未推送**，所以修复可以包含**本模块自己的**表结构与服务代码。
- 两处 `order by l.id` 都要改；优先方案是给 `inventory_adjustment_order_line` 加**行序号列**（本模块自己的表，
  V260 是本批次新建的、没有别的车道拥有它 ⇒ 加列不构成跨车道 DDL 回归），
  新迁移用 **warehouse 的下一个空闲版本 V261**（⚠️ **trade 的 V261 是 6.6.1.2 的，不要动那个文件** —— 两服务各自独立库）。
- 加列的 `NOT NULL` 兜底、并发加行不撞号，必须想清楚 —— 这正是本仓反复出事的形状（「加了列但没给默认值 ⇒ 别人的老 INSERT 必违约」）。
- 测试断言改成**按行的六维身份去找那一行**，不再依赖索引位置；并新增一条**确定性**回归用例把「行顺序是确定的」这个契约钉死，
  要求**变异验证**（回退到旧写法，用例必须立刻红）。
- 台账只追加 7.4.1.1 那一行的「证据」格，**状态格保持「已交付」不变**。

**车道 C 的交付**：commit `4a35c899`，6 个文件（迁移 / 服务 / 2 个测试类 / 台账 / 会话日志），+527 / −14。

- **迁移 `V261__inventory_adjustment_line_no.sql`**（**warehouse 服务**，148 行）——
  ⚠️ **trade 的 `V261__product_development_sample.sql` 是 6.6.1.2 的，两个服务各自独立库，没有动那个文件**。
  加列走的是本仓反复验证过的**三步安全法**：
  ① `ADD COLUMN line_no integer NOT NULL DEFAULT 0`（加列这一刻在任何已有数据上都不会失败）→
  ② 回填历史行 → ③ `DROP DEFAULT`（之后任何 INSERT 都**必须显式**给行号）→
  ④ 加 `CHECK(line_no > 0)` 与 `UNIQUE(tenant_id, order_id, line_no)` 兜底。
- **行序号是本仓既有口径** —— `V117 recount_line.line_no`、`V213 logistics_cost_allocation_line.line_no`、
  `V254 purchase_order_line.line_no` 都是同一写法（单内从 1 递增 + `UNIQUE(单头, line_no)`）⇒ 不另起风格。
- **并发加行不撞号**：服务层取号时**锁住单头行**（`select ... for update` 语义），
  否则两个事务会算出同一个 `max(line_no)+1` ⇒ 撞 UNIQUE。顺带把「加行期间单据被别人提交/作废」这条竞态也关上了。
- **两处 ORDER BY 都改了**（第 320 行详情读行、第 769 行过账写分录）⇒ `order by l.line_no`。
  第二处尤其重要：**过账写分录的先后必须与单据上的行序一致**。
- **为什么不是「按业务键排」**：次选方案（`sku_code, location_code, lot_code, l.id`）顺序也确定，
  但含义变成「按 SKU / 库位 / 批次排」，而**批量调整单里操作员给的顺序本身有业务含义**（按货架走、按清点顺序）
  ⇒ 「审批人看到的顺序 = 操作员提交的顺序」这条链会断。**行序号才能表达它。**
- **对外表现的变化**：批量调整单的明细行**不会再在两次请求之间自己换位置** ——
  之前「审批人看到的第 1 行未必是操作员提交的第 1 行」，而「按单据逐行核对」这条动作链在单据自己换顺序时是不成立的。
- **测试**：`InventoryAdjustmentIntegrationTest` 22 例（原 21 + 新增 1）+ `InventoryAdjustmentTenancyIntegrationTest` 3 例 = **25 例全绿**；
  原用例的断言改成**按行的六维身份去找那一行**，不再依赖索引位置；
  新增确定性用例 `行的顺序是确定的_按加行顺序而不是随机UUID` —— **真实构造**现场：
  把两行主键改成与加行顺序相反的两个定值 UUID（`ffffffff-…` / `00000000-…`），
  于是任何按 id 排的读法必把 A-02 排前；`assertThatThrownBy` 钉死旧写法红、新写法绿。
- **变异验证**：把两处 ORDER BY 改回 `order by l.id` ⇒ **新用例立刻红**（22 例里只有这 1 例红），
  而**改写后的原用例仍绿** ⇒ 证明「原用例已不依赖位置」「新用例确实盯着排序键」。变异已回滚。
- **确定性连跑 5 轮**，每轮 25/25 全绿；提交前又跑了 1 轮。
- ⚠️ **7.4.1.2 若要写调整行，必须显式给 `line_no`** —— 漏给会撞 23502（**响亮地炸，不会静默落错值**），已写进会话日志待办。
- PG 容器：峰值 1 个，跑完 `postgres:16-alpine` 与 `ryuk` 计数均为 0 ⇒ **已被 ryuk 回收干净**。

### 第四轮（第三条修复并入后，19:53 → 20:31）：**六个阶段全绿** ✅

| 阶段 | 退出码 | 结果 |
| --- | --- | --- |
| `backend && ./mvnw verify` | **0** | ✅ **BUILD SUCCESS** |
| `pnpm typecheck` | 0 | ✅ 3/3 turbo tasks |
| `pnpm lint` | 0 | ✅ |
| `pnpm test` | 0 | ✅ **405 文件 / 6110 例全绿** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 0 | ✅ |
| `node tools/module-progress/sync.mjs --check` | 0 | ✅ |

后端各模块：**identity 270 · catalog 1981 · channel 1266 · warehouse 1613 · trade 2134 · crm 70**
⇒ **合计 7334 例，0 失败 0 错误**（`BUILD SUCCESS`）。

三处被修的地方在全量跑里**逐条转绿**：

| 测试类 | 第一/二轮 | 第四轮 | 说明 |
| --- | --- | --- | --- |
| `DropshipTrackingIntegrationTest` | 🔴 差 1 秒（第一轮） | ✅ **30 例全绿** | 原 29 + 新增 1 条确定性回归 |
| `DropshipConfigIntegrationTest` | （未跑到） | ✅ **25 例全绿** | 原 24 + 新增 1 条确定性回归 |
| `InventoryAdjustmentIntegrationTest` | 🔴 `get(0)` 顺序错（第二轮） | ✅ **22 例全绿** | 原 21 + 新增 1 条确定性回归 |

（warehouse 1612→**1613**、trade 2132→**2134** 正是这 3 条新回归用例带来的增量。）

⇒ **本批 5 个模块 + 3 条修复车道 = 8 个模块的交付，在同一次全量验证里全部转绿。**

---

## 静态预检（5.1）：本批 5 条新迁移**零命中**

对**本批新迁移**（而不是全仓历史）跑「已知互相破坏形状」扫描：

| 迁移 | CREATE TABLE | ALTER TABLE（除 ADD COLUMN） | ADD COLUMN 无 DEFAULT | SET NOT NULL | ADD CHECK |
| --- | --- | --- | --- | --- | --- |
| `trade/V259__purchase_followup_delivery.sql` | 3 | 2 | 0 | 0 | 0 |
| `warehouse/V260__inventory_adjustment_order.sql` | 5 | 3 | 0 | 0 | 0 |
| `trade/V262__purchase_order_approval.sql` | 7 | 2 | 0 | 0 | 0 |
| `trade/V264__production_trial_order.sql` | 8 | 2 | 0 | 0 | 0 |
| `trade/V265__platform_dispute.sql` | 4 | 2 | 0 | 0 | 0 |

⇒ ①「加列没默认值」②「改必填但没有触发器兜底」③「新增 CHECK 收紧旧值域」**三条全部零命中**。
所有 `ALTER TABLE` 都是**对新表**做 `ENABLE/FORCE ROW LEVEL SECURITY` 的 `EXECUTE format(...)` 循环；
唯一的例外是 `V260` 对全局码表 `inventory_adjustment_reason` 的 `DISABLE ROW LEVEL SECURITY`（该表**没有 `tenant_id` 列**，只 `GRANT SELECT`）——
**理由写在迁移第 674–678 行，本批已如实点名，建议人工复核这一处口径。**

**Flyway 版本号撞号核对**（并入后按服务逐份数版本号）：
identity V1..V163（28 份）· catalog V1..V245（61 份）· channel V1..V255（55 份）· trade V1..V265（71 份）·
warehouse V1..V260（56 份）· crm V99..V228（3 份）⇒ **每个服务内部无重复版本号**。

---

## 本轮调度上的六个发现（留给下一轮）

1. **本轮 L2 连红两次，红的都不是本批交付的代码，但暴露了同一类「测试脆弱性」** ——
   **两次的根因形状不同，判据也不同**：
   - 第一次（9.6.1.3 / 9.6.1.1）：**写入时取一次时钟、断言时又取一次时钟** ⇒ 跨整秒边界就差整 1 秒。
   - 第二次（7.4.1.1）：**断言依赖行的索引位置，而服务按随机 UUID 排序** ⇒ 约 50% 概率红。
   ⇒ **通用判据（下一轮可直接拿去筛）**：
   **① 断言值与写入值是否共用同一个时刻来源；② 断言是否依赖了一个没有业务含义的排序键（随机 UUID / 随机 id）？**
   这两条都不必等它炸了才查 —— **grep「断言里出现的 `at(` / `now()` / `get(0)`」就能筛出来**。
   本仓已有先例：`fix/4-3-1-1-clock-assert`（batch-38）、6.6.2.1 试产单车道自己修的同类成本明细行 flaky。
2. **`merge-ready` 的「行级兜底并集」必须由调度者独立取证，不能靠脚本自证** ——
   本批 `api-proxy.ts` 与 `packages/api-contracts/**` 各走了一次兜底并集。
   核对手段（可复用）：`api-proxy.ts` 取两版逐行 diff + **把超长正则的顶层 alternation 分支抽出来做集合比对**；
   契约按「逐份解析 JSON + 比对 `paths` 键集合」。
   本批结果：白名单 +269/-1（那 1 行是就地扩写，顶层分支 missing=0）；契约 5 份全部 JSON 合法、missing=0。
3. **`V260` 是全仓第一处 `DISABLE ROW LEVEL SECURITY`** —— 一个**全局码表**（无 `tenant_id` 列、只 `GRANT SELECT`）。
   理由成立且写在了迁移里，但这是**新口径**，本批已点名，建议人工确认是否接受「全局参考数据表不参与租户 RLS」这条先例。
4. **`merge-ready` 要求集成头工作区干净** —— 说明文件若还没提交，`merge-ready` 会拒绝切分支。
   ⇒ 本轮的实际顺序是：先把未提交的说明文件**暂存到 scratchpad**、把台账还原到 HEAD ⇒ `merge-ready` 才能跑
   ⇒ 并入后再 `sync` + L2 ⇒ 最后把说明文件取回、与台账**同一个 commit** 提交。
   这条顺序不是可选的洁癖，是脚本的硬前置。
5. **本轮单轮时长已超过调度闸的 70 分钟 TTL**（18:00 → 20:4x）⇒ 期间 19:00 / 20:00 两个 cron 触发点
   都会撞闸并按协议**零派发 + 只读汇报**（`gate=held, stale=false` ⇒ 不抢闸）。
   这与 batch-38 点名的同一个问题：**单轮 40–100 分钟 vs 闸 TTL 70 分钟 vs cron 栅格 60 分钟**三者不匹配，
   必然出现「每小时有一轮空转」。⇒ **加长闸 TTL / 改 cron 栅格 / 缩短单轮时长**是**用户该决定的事**，
   调度器**不得自行改节流闸与 cron**（PIPELINE.md 明令）。本轮已点名待决。
6. **⚠️ 台账「证据」格里混进 ASCII 半角竖线 ⇒ 下一次 `sync` 会静默截断别人的交付记录**（本轮实测踩到并已修）
   - 6.3.1.3 车道把自己证据里的**路由形状列表**写成了 ASCII 竖线分隔：
     `rules[/{uuid}[/status]] | members[/buyers | /approvers] | orders[/batch | ...]`
     ⇒ 那一行的竖线总数变成 **15**（正常是 7），**证据格不是合法的单元格**。
   - `sync.mjs` 的行正则是 `^\|\s*(id)\s*\|([^|\n]*)\|…\|([^|\n]*)\|` ——
     证据格里只要出现一个 ASCII 竖线，匹配就在那里**断掉** ⇒ 该格被截断，**后半段证据整段丢失**。
     实测：HEAD 里那一行有 4869 字符，`sync` 之后只剩 3521 —— **1348 字符的交付记录被静默 degrade**。
     而 `typecheck` / `lint` / `vitest` / L2 **全绿**，没有任何一个阶段会报警。
   - **本仓的既定写法是全角 `｜`（U+FF5C）**：本批三条修复车道追加证据时都用了全角；
     7.4.1.1 / 9.6.1.3 的原始证据里那些「分隔符」也全是全角。
   - ⇒ **本轮处置**：把 6.3.1.3 那一行证据里的 **8 个 ASCII 竖线改成全角**，**4835 字符证据一字未少**；
     改完全表 **0 个畸形行**，且 `sync` 变得**幂等**（再跑一次 diff 为空 ⇒ 不再有二次截断）。
   - ⇒ **给下一轮的硬判据**：**台账每行的 ASCII 竖线数必须恒等于 7**，
     多一个就说明有人往证据格里塞了半角竖线；并入前扫一遍，**不要等 `sync` 去截**。
     （可自查：`grep -E '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md | awk -F'|' 'NF-1!=7'`）

---

## L2 最终结果

**第四轮（19:53 → 20:31）六个阶段全绿 ✅** ——
后端 `mvnw verify` **7334 例 / 0 失败 0 错误 / BUILD SUCCESS**，
前端 typecheck + lint + **405 文件 6110 例** + Next production build + `sync --check` 全部退出码 **0**。

本批 **8 个模块**（5 个新交付 + 3 条修复车道）在同一次全量验证里全部转绿。

**收尾三件套**：说明写在本文件 ⇒ 与台账**同一个 commit** 提交 ⇒ push 到 `integration/module-dev`
（**不 push main**）。
