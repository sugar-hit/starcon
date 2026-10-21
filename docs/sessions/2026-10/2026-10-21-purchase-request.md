# 2026-10-21: 6.2.2.1 采购申请（阶段 D6）

## 摘要

车道 `6.2.2.1`：在 `integration/module-dev` 基线上交付蓝图 06 卷 6.2.2.1「采购申请」，
后端主责服务 **trade**。

交付内容：迁移 `V246__purchase_request.sql`（四张新表 + DB 层状态机 + RLS）、
6 个 Java 类（契约模型 / 审批端口 / 审批装配 / 供应商端口 / 服务 / 控制器）、
前端页面 `/purchase/demand/requests`（工作区 + 纯函数模型）、
api-proxy 一条**独立**白名单元素 + 双向 allowlist 断言、
契约重生成（+20 DTO / +10 路径）、台账与本会话记录。

L0 编译闸绿；L1 模块闸 `PurchaseRequest*` **31 例 0 失败 0 错误 BUILD SUCCESS**；
`pnpm typecheck` 3/3 通过；前端 `apps/web/src/app/api` **126 文件 732 例全绿**。

## 决策 / 结论

### 1. 蓝图加粗句「申请是内部决策单据，采购单是对供应商的正式承诺」怎么落地

6.3.1.2 采购单**未交付**（`backend/services/` 下没有任何采购单实体）。⇒ 两种「假做法」都排除：
既不自造一张假采购单表占位，也不假装能调一个不存在的端点。

**本模块的「已转单」是一个事实**：`purchase_request_conversion` 逐行记录转单参数
（供应商 + 数量 + 交期 + 目标仓）与**可追溯的源计划行 / 源建议**。
6.3.1.2 交付后由它读这份事实去建真正的采购单。
能力声明里 `purchaseOrderDelivered=false` + `purchaseOrderNote` 写清这件事，页面据此显示警示块。

### 2. 状态机用 DB 层触发器钉死，而不是只在服务层判断

`guard_purchase_request_status`（BEFORE UPDATE ON purchase_request）：
合法流转只有 **草稿→待审/已批、待审→已批/已驳回、已批→已转单**，其余一律 RAISE 23514。

同一条触发器还管**「离开草稿后内容冻结」**：数量 / 交期 / 供应商 / 目标仓 / 法人 / 金额 / 备注
在离开草稿后一律不许再改，只允许状态与审批留痕字段变。
理由：改了就会与「已审批的那一版」对不上，审批留痕随之失去意义 ——
这正是加粗句「便于审批留痕与事后追溯」的反面。

配套三条触发器：`guard_purchase_request_line_draft_only`（申请行只在草稿期可改）、
`guard_purchase_request_line_source_frozen`（来源列冻结）、
`guard_purchase_request_append_only`（调整留痕与转单事实不可改写不可删）。

### 3. 审批不自造：复用 identity（17）已交付的授权接口

- 求值 `POST /identity/approval-rules/evaluate`
- 提交 `POST /identity/approval-instances`（带 `sourceSystem='trade'` + `sourceReferenceId=本单 id`
  ⇒ **天然幂等**，重复提交不产生第二条待办；409 按「幂等命中」处理并回读既有实例，不重投）
- 拉结论 `GET /identity/approval-records/{id}`（**拉取式**同步，本仓无事件总线）

**本模块没有本地批准端点**。本地批准会把审批流本地化 —— identity 那边什么都不知道，
还留一条没人管的待办。`/{uuid}/approve`、`/reject`、`/approval/decide` 三条路径后端不存在、
白名单也刻意不放行，并各有断言钉住。

**单据类型用 identity 出厂注册的 `PURCHASE_ORDER`**（V83 注册的 12 种之一，语义即
「向供应商下单，按金额走分级审批」）。identity 侧**没有**注册独立的「采购申请」类型，
而注册新单据类型属于 identity 的写入所有权（跨服务禁止）⇒ 复用而不自造。

### 4. 审批服务不可达时「停在待审 + 如实写失败原因」的一个陷阱

第一次实现把失败写成 `throw ResponseStatusException`，结果**痕迹被一起回滚了** ——
`submit` 是 `@Transactional`，抛异常 ⇒ `approval_sync_error` 与 `status='PENDING_APPROVAL'` 都没了，
这张单变成「什么都没发生过」。

修法（与 catalog 5.4.1.1 同一手法）：服务层**先记下痕迹再返回**一个结果对象
（`PurchaseRequestApprovalResult`），**不抛异常** ⇒ 事务正常提交、痕迹落库；
由控制器在**事务提交之后**翻成 503 / 409。
这个坑值得记住：**失败痕迹与失败响应不能放在同一个事务里**。

### 5. 金额与币种的两个设计决定

- **预估金额算不出来时是 NULL 而不是 0**：有一行没填预估单价，整张单就**算不出来**
  （0 会被读成「这笔采购不要钱」）。
- **单价不从上游搬**：补货建议的 `unit_price` 是**每采购单位**口径，
  而申请行的数量是**基础单位**口径 —— 照抄会算出差一个箱规倍数的错金额。
  所以 `unit_price` 只能由人工填（建单时或调整时）。
- **审批边界归一**：identity 的提交要求「金额与币种同时有或同时没有」，
  只有金额没有币种时阈值分支匹配没有唯一答案 ⇒ 这一次**不拿**那个没有口径的数字去命中阈值
  （当作「这次没有金额」），而不是把金额丢掉或拿 0 去顶。

### 6. 上游一律只读，跨库不建 FK

6.2.1.3 的 `purchase_plan` / `purchase_plan_line` 与 6.2.1.2 的 `replenishment_suggestion`
只 SELECT：不 ALTER、不加约束、**不建跨表外键**（写入所有权分别是 6.2.1.3 与 6.2.1.2；
6.2.1.3 对 6.2.1.2 也是同一口径 —— 见 V242 文件头）。

供应商在 catalog（party）→ 复用 6.1.2.3 已交付的 `SupplierPartyQueryPort`，
外面套一层 `PurchaseRequestSupplierPort` 只为多报一件事实：**供应商没接线时显式报「未接线」**
（`CATALOG_URL` 为空），绝不降级成一份假供应商清单。选供应商时服务端会经 catalog 确认它真的存在。

目标仓与法人在 warehouse → 复用 6.2.1.3 的 `PurchasePlanWarehousePort`。

## 关键 trade-off

- **一张申请单只对一个目标仓 / 法人 / 币种**，行级只有 SKU、数量、单价、供应商、交期。
  从计划转单时若选中的计划行跨了仓 ⇒ **拒绝**（提示分批建单），而不是静默归到计划头的仓
  （货走错仓比建单失败贵得多）。
- **供应商选在行级而不是单级**：不同 SKU 可能来自不同供应商；单级供应商会让「一张申请单一个供应商」
  这个假设反过来限制业务。
- **单价与金额由人工填，不由系统推**：见上面第 5 条 —— 系统推出来的金额一旦口径错了，
  后面审批阈值、转单事实全部跟着错。
- **转单必须覆盖全部行**：`convert` 一次转整张申请单（`lines` 只提供逐行覆盖参数），
  避免出现「单已转单但有几行没转」这种半截状态。

## 文件清单

**新增（后端）**

- `backend/services/trade/src/main/resources/db/migration/V246__purchase_request.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestApprovalPort.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestApprovalConfiguration.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestSupplierPort.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestController.java`

**新增（测试）**

- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseRequestFixture.java`
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseRequestIntegrationTest.java`（27 例）
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseRequestTenancyIntegrationTest.java`（4 例）

**新增（前端）**

- `apps/web/src/components/purchase/purchase-request-model.ts`
- `apps/web/src/components/purchase/purchase-request-model.test.ts`（18 例）
- `apps/web/src/components/purchase/purchase-request-workspace.tsx`
- `apps/web/src/app/purchase/demand/requests/page.tsx`
- `apps/web/src/app/api/trade/purchase-request-allowlist.test.ts`

**改动**

- `apps/web/src/lib/api-proxy.ts` —— **纯增量**新增一条 `purchase-requests` 元素
  （独立一条 `services[]` 元素，插入锚点固定在最后一条 `prefix: 'trade/'` 元素之后 = 原 3992 行）
- `packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json`
  —— codegen 产物，**+20 DTO / +10 路径**
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.2.2.1 那一行的「状态」「证据」两格

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 后端编译 | `cd backend && ./mvnw -q compile` | exit 0 |
| L0 前端类型 | `pnpm typecheck` | 3/3 通过 |
| L1 模块闸 | `./mvnw -pl services/trade -am test -Dtest='PurchaseRequest*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **31 例 0 失败 0 错误 BUILD SUCCESS** |
| 前端本模块 | `pnpm vitest run apps/web/src/components/purchase/purchase-request-model.test.ts apps/web/src/app/api/trade/purchase-request-allowlist.test.ts` | 27 例全绿 |
| **api-proxy 全量回归**（改了 `api-proxy.ts`） | `pnpm vitest run apps/web/src/app/api` | **126 文件 732 例全绿** |
| 契约 | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | 6 服务快照；trade +20 DTO / +10 路径，其余零漂移 |
| Lint | `pnpm exec eslint <本模块 6 个文件>` | 零告警 |

多租户三断言（`PurchaseRequestTenancyIntegrationTest`）：

1. **两租户互不可见** —— 列表各自一张；A 按 ID 查 B 返回 404；
   **按主键直查用 `trade_app` 应用账号连**（超级用户绕过 RLS，那条断言会永远「通过」）。
2. **伪造租户头无效** —— 伪造 `X-Tenant-Id` 改不动租户上下文；
   并断言**伪造的租户号绝不出现在上游请求里**（上游只收到调用方自己的 bearer）。
3. **连接池归还后不串租户** —— 池压到 **1 条**连接，A→B→A 三轮各只看见自己那一张；
   事务外（无租户上下文）一行都看不见。

## 后续待办

1. **6.3.1.2 采购单未交付** —— 本模块只产出转单事实。6.3.1.2 落地后由它读
   `purchase_request_conversion` 去建真正的采购单；在那之前页面上**不得**出现「采购单已创建」。
2. **`trade.purchase-request.*` 权限码未在 identity 注册** —— `permission` 表是 identity 的写入所有权
   （跨服务禁止，本车道不碰）。这与 trade 服务**全线**既有模块同一个口径
   （`trade.purchase-plan.*`、`trade.orders.*` 等均未注册），需要时由 identity 侧统一补注册。
3. **6.2.1.3 能力声明里的 `purchaseRequestAvailable` 仍是 false** —— 那是 6.2.1.3 的文件
   （写入所有权在 6.2.1.3 车道），本车道按「只碰自己的文件」的纪律**没有改它**。
   ⇒ 需要 6.2.1.3 车道或调度者把它改成 true 并更新说明文案。
4. **集成头里 `packages/api-contracts/src/catalog.openapi.json` 有既有漂移** ——
   本车道跑 codegen 时它被连带刷新（price-history 的类型重排），已**还原**以免与
   5.5.1.1 车道撞车；该漂移不属于本模块，由调度者/对应车道处理。
